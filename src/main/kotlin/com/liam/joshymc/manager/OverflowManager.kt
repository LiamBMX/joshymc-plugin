package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent withdraw-only storage ("/overflow"). Items from fulfilled Buy Orders always land in
 * its Orders section (issue #1233, never the buyer's inventory); Auction House deliveries fall
 * back to it when a buyer's inventory is full, so an already-committed delivery is never
 * dropped, deleted, or duplicated. Players can take items out but cannot manually deposit into it.
 * Crate keys given in the Event World land in its Crate Keys section (issue #1178); none of
 * the storages can be opened from inside the Event World.
 */
class OverflowManager(private val plugin: Joshymc) : Listener {

    enum class OverflowType(val label: String, val size: Int) {
        ORDERS("Orders Overflow", 54),
        AUCTION_HOUSE("AH Overflow", 9),
        /** Crate keys given while the player was in the Event World (issue #1178). */
        CRATE_KEYS("Crate Keys", 54)
    }

    companion object {
        private val HUB_TITLE: Component = Component.text("         ")
            .append(Component.text("Overflow Storage", NamedTextColor.GOLD))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        /** How often changed open storages are written back to the DB (1 second). */
        private const val FLUSH_TICKS = 20L

        private val FILLER =ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
    }

    /** uuid -> (type, live inventory) for whoever currently has an Overflow storage open. */
    private val openOverflow = ConcurrentHashMap<UUID, Pair<OverflowType, Inventory>>()

    /** Players whose open storage changed since its last write to the DB. */
    private val dirty = ConcurrentHashMap.newKeySet<UUID>()
    private var flushTask: BukkitTask? = null

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS overflow_storage (
                uuid TEXT NOT NULL,
                type TEXT NOT NULL,
                slot INTEGER NOT NULL,
                item TEXT NOT NULL,
                PRIMARY KEY (uuid, type, slot)
            )
        """.trimIndent())
        // The DB stays the authoritative copy while a storage is open: changes are written
        // back within a second, so a hard crash loses at most that last second of clicks.
        flushTask?.cancel()
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { flushDirty() }, FLUSH_TICKS, FLUSH_TICKS)
        plugin.logger.info("[Overflow] OverflowManager started.")
    }

    /** Saves any currently open Overflow storages. Called on server shutdown/reload. */
    fun saveOpenOverflows() {
        for ((uuid, entry) in openOverflow.toMap()) {
            saveOverflow(uuid, entry.first, entry.second)
        }
        openOverflow.clear()
        dirty.clear()
    }

    /** Writes every open storage that changed since its last save. */
    private fun flushDirty() {
        for (uuid in dirty.toList()) {
            val entry = openOverflow[uuid]
            if (entry == null) {
                dirty.remove(uuid)
                continue
            }
            saveOverflow(uuid, entry.first, entry.second)
        }
    }

    // ---- Item serialization ----

    private fun serializeItem(item: ItemStack): String = Base64.getEncoder().encodeToString(item.serializeAsBytes())

    private fun deserializeItem(base64: String): ItemStack = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64))

    // ---- Deposit (used by Orders/AH delivery fallback) ----

    /**
     * Stores a copy of [item] in the player's Overflow storage of [type] and returns what did
     * NOT fit: null when everything was stored, otherwise the exact leftover stack (all of it
     * when nothing fit), with the item's full metadata. [item] itself is never modified, so
     * callers must queue only the returned leftover.
     */
    fun depositItem(uuid: UUID, type: OverflowType, item: ItemStack): ItemStack? {
        if (item.amount <= 0) return null
        val copy = item.clone()

        // If the player currently has this exact storage open, deposit straight into the live
        // inventory instead of the DB, so we don't clobber what they're looking at right now.
        val open = openOverflow[uuid]
        if (open != null && open.first == type) {
            val leftover = open.second.addItem(copy.clone()).values.firstOrNull()?.clone()
            if (leftover != null && leftover.amount >= copy.amount) return leftover
            // Save right away: a committed delivery must never live only in memory. If that
            // write fails, the fitted part is still in the live inventory and the next flush
            // or close persists it, so only the leftover goes back to the caller.
            try {
                saveOverflow(uuid, type, open.second)
            } catch (e: Exception) {
                dirty.add(uuid)
                plugin.logger.severe("[Overflow] Saving $type storage of $uuid failed: ${e.message}")
            }
            return leftover
        }

        if (type != OverflowType.AUCTION_HOUSE) return depositStacking(uuid, type, copy)

        return try {
            val usedSlots = plugin.databaseManager.query(
                "SELECT slot FROM overflow_storage WHERE uuid = ? AND type = ?",
                uuid.toString(), type.name
            ) { rs -> rs.getInt("slot") }.toSet()

            val freeSlot = (0 until type.size).firstOrNull { it !in usedSlots } ?: return copy

            plugin.databaseManager.execute(
                "INSERT INTO overflow_storage (uuid, type, slot, item) VALUES (?, ?, ?, ?)",
                uuid.toString(), type.name, freeSlot, serializeItem(copy)
            )
            null
        } catch (e: Exception) {
            // Nothing was stored: hand the whole stack back so the caller can still queue it.
            plugin.logger.severe("[Overflow] Depositing into $type storage of $uuid failed: ${e.message}")
            copy
        }
    }

    /**
     * Stores [copy] in a closed Orders or Crate Keys storage the way addItem would (see
     * [storeStacking]). All rows are written in one transaction, so a failed write stores
     * nothing and the whole stack is handed back.
     */
    private fun depositStacking(uuid: UUID, type: OverflowType, copy: ItemStack): ItemStack? {
        return try {
            var leftover: ItemStack? = null
            plugin.databaseManager.transaction { leftover = storeStacking(uuid, type, listOf(copy)).firstOrNull() }
            leftover
        } catch (e: Exception) {
            plugin.logger.severe("[Overflow] Depositing into $type storage of $uuid failed: ${e.message}")
            copy
        }
    }

    /**
     * Writes [items] into a closed storage's rows, stacking like addItem: identical stacks are
     * topped up first, then free slots get stacks of at most the item's max size. Reads the
     * storage once for the whole list. Returns, per item that didn't fully fit, the exact
     * leftover stack. Throws on any DB error; callers run it inside a transaction.
     */
    private fun storeStacking(uuid: UUID, type: OverflowType, items: List<ItemStack>): List<ItemStack> {
        val db = plugin.databaseManager
        // slot -> stored stack (null when a row can't be read: its slot stays taken).
        val stored = sortedMapOf<Int, ItemStack?>()
        db.query(
            "SELECT slot, item FROM overflow_storage WHERE uuid = ? AND type = ?",
            uuid.toString(), type.name
        ) { rs -> rs.getInt("slot") to rs.getString("item") }
            .forEach { (slot, data) -> stored[slot] = runCatching { deserializeItem(data) }.getOrNull() }

        val updated = mutableSetOf<Int>()
        val inserted = mutableSetOf<Int>()
        val leftovers = mutableListOf<ItemStack>()
        for (item in items) {
            if (item.amount <= 0) continue
            val max = item.maxStackSize.coerceAtLeast(1)
            var remaining = item.amount
            for ((slot, stack) in stored) {
                if (remaining <= 0) break
                if (stack == null || stack.amount >= max || !stack.isSimilar(item)) continue
                val add = minOf(max - stack.amount, remaining)
                stack.amount += add
                remaining -= add
                if (slot !in inserted) updated.add(slot)
            }
            for (slot in 0 until type.size) {
                if (remaining <= 0) break
                if (stored.containsKey(slot)) continue
                val stack = item.clone().apply { amount = minOf(max, remaining) }
                stored[slot] = stack
                inserted.add(slot)
                remaining -= stack.amount
            }
            if (remaining > 0) leftovers.add(item.clone().apply { amount = remaining })
        }

        for (slot in updated) {
            db.execute(
                "UPDATE overflow_storage SET item = ? WHERE uuid = ? AND type = ? AND slot = ?",
                serializeItem(stored.getValue(slot)!!), uuid.toString(), type.name, slot
            )
        }
        for (slot in inserted) {
            db.execute(
                "INSERT INTO overflow_storage (uuid, type, slot, item) VALUES (?, ?, ?, ?)",
                uuid.toString(), type.name, slot, serializeItem(stored.getValue(slot)!!)
            )
        }
        return leftovers
    }

    // ---- Transactional deposit (Buy Order deliveries, issue #1233) ----

    /**
     * Saves and closes [uuid]'s [type] storage if they have it open, so its DB rows become the
     * only copy and [storeItems] can write to them. Returns true when a storage was settled
     * (the caller may reopen it afterwards so the player sees the new items). Throws if the
     * save fails, leaving the storage open and untouched.
     */
    fun settleOpenStorage(uuid: UUID, type: OverflowType): Boolean {
        val entry = openOverflow[uuid] ?: return false
        if (entry.first != type) return false
        saveOverflow(uuid, type, entry.second)
        openOverflow.remove(uuid, entry)
        val player = Bukkit.getPlayer(uuid)
        if (player != null && player.openInventory.topInventory === entry.second) player.closeInventory()
        return true
    }

    /**
     * Stores copies of [items] in [uuid]'s [type] storage as DB rows only, stacking with what's
     * there, and returns the leftovers that didn't fit. Joins an enclosing transaction and
     * throws on any DB error, so the caller's whole operation rolls back with it. The storage
     * must not be open: call [settleOpenStorage] first.
     */
    fun storeItems(uuid: UUID, type: OverflowType, items: List<ItemStack>): List<ItemStack> {
        check(openOverflow[uuid]?.first != type) { "$type storage of $uuid is open" }
        var leftovers = emptyList<ItemStack>()
        plugin.databaseManager.transaction { leftovers = storeStacking(uuid, type, items) }
        return leftovers
    }

    // ---- Event World crate keys (issue #1178) ----

    /** Players already told this event visit that their keys went to /overflow. */
    private val eventKeyNotified = ConcurrentHashMap.newKeySet<UUID>()

    /**
     * Stores a crate key given to [player] while they're in the Event World in their Crate Keys
     * storage, so it never enters the temporary event inventory. Returns what didn't fit, like
     * [depositItem]. The player is told once per event visit, and reminded when they leave.
     */
    fun storeEventCrateKey(player: Player, item: ItemStack): ItemStack? {
        val leftover = depositItem(player.uniqueId, OverflowType.CRATE_KEYS, item)
        val stored = item.amount - (leftover?.amount ?: 0)
        if (stored > 0) {
            plugin.logger.info("[Overflow] Stored $stored event-world crate key(s) for ${player.name} (${player.uniqueId}).")
            if (eventKeyNotified.add(player.uniqueId)) {
                plugin.commsManager.send(
                    player,
                    Component.text("You received crate keys while participating in an event! Your keys have been safely stored in ", NamedTextColor.YELLOW)
                        .append(Component.text("/overflow", NamedTextColor.GOLD))
                        .append(Component.text(".", NamedTextColor.YELLOW))
                )
            }
        }
        return leftover
    }

    @EventHandler
    fun onChangedWorld(event: PlayerChangedWorldEvent) {
        val player = event.player
        val eventWorld = plugin.eventManager.eventWorldName
        if (player.world.name == eventWorld) {
            // Never leave a storage open inside the event (teleports normally close it already).
            if (openOverflow.containsKey(player.uniqueId)) player.closeInventory()
            return
        }
        if (event.from.name != eventWorld || !eventKeyNotified.remove(player.uniqueId)) return
        plugin.commsManager.send(
            player,
            Component.text("Crate keys you received during the event are waiting in ", NamedTextColor.YELLOW)
                .append(Component.text("/overflow", NamedTextColor.GOLD))
                .append(Component.text(" → Crate Keys.", NamedTextColor.YELLOW))
        )
    }

    fun getItemCount(uuid: UUID, type: OverflowType): Int {
        val open = openOverflow[uuid]
        if (open != null && open.first == type) {
            return open.second.contents.count { it != null && it.type != Material.AIR }
        }
        return plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as cnt FROM overflow_storage WHERE uuid = ? AND type = ?",
            uuid.toString(), type.name
        ) { rs -> rs.getInt("cnt") } ?: 0
    }

    // ---- Hub GUI ----

    /** True (and tells the player) when [player] is in the Event World, where /overflow is closed. */
    private fun blockedInEventWorld(player: Player): Boolean {
        if (!plugin.eventManager.isInEventWorld(player)) return false
        plugin.commsManager.send(player, Component.text("You can't open /overflow in the event world. Use /spawn to leave first.", NamedTextColor.RED))
        return true
    }

    fun openHub(player: Player) {
        if (blockedInEventWorld(player)) return
        val gui = CustomGui(HUB_TITLE, 27)
        for (i in 0 until 27) gui.inventory.setItem(i, FILLER.clone())

        gui.setItem(11, hubIcon(Material.CHEST, OverflowType.ORDERS, player)) { p, _ ->
            p.closeInventory()
            openOverflow(p, OverflowType.ORDERS)
        }
        gui.setItem(13, hubIcon(Material.TRIPWIRE_HOOK, OverflowType.CRATE_KEYS, player)) { p, _ ->
            p.closeInventory()
            openOverflow(p, OverflowType.CRATE_KEYS)
        }
        gui.setItem(15, hubIcon(Material.ENDER_CHEST, OverflowType.AUCTION_HOUSE, player)) { p, _ ->
            p.closeInventory()
            openOverflow(p, OverflowType.AUCTION_HOUSE)
        }
        gui.setItem(22, ItemStack(Material.BARRIER).apply {
            editMeta {
                it.displayName(
                    Component.text("Close", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
            }
        }) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun hubIcon(material: Material, type: OverflowType, player: Player): ItemStack {
        val count = getItemCount(player.uniqueId, type)
        val icon = ItemStack(material)
        icon.editMeta { meta ->
            meta.displayName(
                Component.text(type.label, NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  $count / ${type.size} slot(s) used", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  Click to open", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
            ))
        }
        return icon
    }

    // ---- Open a storage ----

    fun openOverflow(player: Player, type: OverflowType) {
        if (blockedInEventWorld(player)) return
        val uuid = player.uniqueId
        // Settle a storage that's still open before loading from the DB, so its items are
        // written back (and can't be lost or loaded twice) when this one replaces it.
        openOverflow.remove(uuid)?.let { saveOverflow(uuid, it.first, it.second) }

        val title = Component.text(type.label).decoration(TextDecoration.ITALIC, false)
        val inv = Bukkit.createInventory(null, type.size, title)

        val items = plugin.databaseManager.query(
            "SELECT slot, item FROM overflow_storage WHERE uuid = ? AND type = ?",
            uuid.toString(), type.name
        ) { rs -> rs.getInt("slot") to deserializeItem(rs.getString("item")) }

        for ((slot, item) in items) {
            if (slot in 0 until type.size) inv.setItem(slot, item)
        }

        // The rows stay in the DB while the storage is open. Every save replaces the whole
        // storage in one transaction, so the DB always holds exactly one copy (the last saved
        // contents of this inventory), never the old rows plus the live items.
        openOverflow[uuid] = type to inv
        player.openInventory(inv)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun saveOverflow(uuid: UUID, type: OverflowType, inventory: Inventory) {
        dirty.remove(uuid)
        plugin.databaseManager.transaction {
            plugin.databaseManager.execute(
                "DELETE FROM overflow_storage WHERE uuid = ? AND type = ?",
                uuid.toString(), type.name
            )
            for (slot in 0 until inventory.size) {
                val item = inventory.getItem(slot)
                if (item != null && item.type != Material.AIR) {
                    plugin.databaseManager.execute(
                        "INSERT INTO overflow_storage (uuid, type, slot, item) VALUES (?, ?, ?, ?)",
                        uuid.toString(), type.name, slot, serializeItem(item)
                    )
                }
            }
        }
    }

    // ---- Withdraw-only protection ----
    // Players may take items out (normal pickup clicks, shift-click out, drop) but may not
    // put anything in (deposit clicks, hotbar/offhand swaps into a slot, shift-click in, drags,
    // or creative clone-stack duplication).

    @EventHandler(priority = EventPriority.LOWEST)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val entry = openOverflow[player.uniqueId] ?: return
        val inv = entry.second
        if (event.view.topInventory != inv) return

        // Nothing comes out of Overflow inside the Event World.
        if (plugin.eventManager.isInEventWorld(player)) {
            event.isCancelled = true
            return
        }

        if (event.clickedInventory == inv) {
            val cursorHasItem = !event.cursor.type.isAir
            val isDeposit = event.click == ClickType.NUMBER_KEY ||
                event.click == ClickType.SWAP_OFFHAND ||
                event.action == InventoryAction.CLONE_STACK ||
                (cursorHasItem && (event.click == ClickType.LEFT || event.click == ClickType.RIGHT))
            if (isDeposit) {
                event.isCancelled = true
            } else if (event.isShiftClick && !fitsAny(player, event.currentItem)) {
                // Vanilla leaves the stack where it is; just say why.
                plugin.commsManager.send(player, Component.text("Your inventory is full. The items stay safely in Overflow.", NamedTextColor.RED))
            }
            return
        }

        // Clicked the player's own inventory while Overflow is open — block shift-clicking
        // items INTO the storage; ordinary rearranging of their own inventory is fine.
        if (event.isShiftClick) {
            event.isCancelled = true
        }
    }

    /** True if at least one of [item] would fit in the player's storage slots. */
    private fun fitsAny(player: Player, item: ItemStack?): Boolean {
        if (item == null || item.type.isAir) return true
        return player.inventory.storageContents.any {
            it == null || it.type.isAir || (it.isSimilar(item) && it.amount < it.maxStackSize)
        }
    }

    // Clicks and drags that got through may have withdrawn items: mark the storage for the
    // next flush instead of writing to the DB on every click.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onClickMonitor(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val entry = openOverflow[player.uniqueId] ?: return
        if (event.view.topInventory === entry.second) dirty.add(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDragMonitor(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val entry = openOverflow[player.uniqueId] ?: return
        if (event.view.topInventory === entry.second) dirty.add(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val entry = openOverflow[player.uniqueId] ?: return
        val inv = entry.second
        if (event.rawSlots.any { it < inv.size }) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val entry = openOverflow[player.uniqueId] ?: return
        // Only the storage's own window counts: openInventory() closes whatever was open
        // before it, and saving THAT inventory would write its contents into Overflow.
        if (event.inventory !== entry.second) return
        if (!openOverflow.remove(player.uniqueId, entry)) return
        saveOverflow(player.uniqueId, entry.first, entry.second)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        val entry = openOverflow.remove(uuid) ?: return
        saveOverflow(uuid, entry.first, entry.second)
    }
}
