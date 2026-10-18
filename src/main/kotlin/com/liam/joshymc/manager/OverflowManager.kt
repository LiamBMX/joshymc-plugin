package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent withdraw-only storage ("/overflow") that Orders and Auction House deliveries fall
 * back to when a buyer's inventory is full, so an already-committed delivery is never dropped,
 * deleted, or duplicated. Players can take items out but cannot manually deposit into it.
 * Crate keys given in the Event World land in its Crate Keys section (issue #1178); none of
 * the storages can be opened from inside the Event World.
 *
 * Issue #1234: the storages have no size cap. Every stack is its own row in [OverflowStore],
 * the GUI shows one page of [PAGE_SIZE] stacks at a time, and a click withdraws through the
 * database first (a conditional update), so items never live only in an open inventory.
 */
class OverflowManager(private val plugin: Joshymc) : Listener {

    enum class OverflowType(val label: String) {
        ORDERS("Orders Overflow"),
        AUCTION_HOUSE("AH Overflow"),
        /** Crate keys given while the player was in the Event World (issue #1178). */
        CRATE_KEYS("Crate Keys")
    }

    companion object {
        private val HUB_TITLE: Component = Component.text("         ")
            .append(Component.text("Overflow Storage", NamedTextColor.GOLD))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private const val GUI_SIZE = 54
        /** Stacks shown per page: the five top rows; the bottom row holds the controls. */
        const val PAGE_SIZE = 45
        private const val WITHDRAW_ALL_SLOT = 53
        /** Rows read per step of Withdraw All. */
        private const val WITHDRAW_ALL_BATCH = 45

        private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }

        /** Number of pages for [stacks] stored stacks: at least 1, never overflowing an Int. */
        fun totalPages(stacks: Long): Int {
            if (stacks <= 0) return 1
            val pages = (stacks - 1) / PAGE_SIZE + 1
            return pages.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private lateinit var store: OverflowStore

    /** One open storage page: which storage, which page. The items themselves stay in the DB. */
    private class View(val gui: CustomGui, val type: OverflowType, var page: Int)

    /** uuid -> the Overflow storage page they have open. */
    private val viewers = ConcurrentHashMap<UUID, View>()

    fun start() {
        store = OverflowStore(plugin.databaseManager)
        store.createSchema()
        migrateLegacyStorage()
        plugin.logger.info("[Overflow] OverflowManager started.")
    }

    /**
     * Moves storages saved before issue #1234 (the old fixed-slot `overflow_storage` table)
     * into the uncapped rows. Restart-safe: see [OverflowStore.migrateLegacy]. If it fails,
     * the old table is untouched and the migration simply runs again on the next start.
     */
    private fun migrateLegacyStorage() {
        try {
            val result = store.migrateLegacy { data ->
                val item = deserializeItem(data)
                if (item.type.isAir || item.amount <= 0) null else encode(item) to item.amount
            }
            if (result.migrated > 0) {
                plugin.logger.info("[Overflow] Migrated ${result.migrated} stored stack(s) from the old slot storage.")
            }
            if (result.unreadable > 0) {
                plugin.logger.warning("[Overflow] ${result.unreadable} old stored stack(s) couldn't be read and were left in the overflow_storage table.")
            }
        } catch (e: Exception) {
            plugin.logger.severe("[Overflow] Migrating the old slot storage failed (nothing was moved, it will retry next start): ${e.message}")
        }
    }

    /** Closes every open Overflow page. Called on server shutdown/reload; nothing is held in memory. */
    fun closeOpenStorages() {
        for (uuid in viewers.keys.toList()) {
            val player = plugin.server.getPlayer(uuid) ?: continue
            if (plugin.guiManager.getOpenGui(player) === viewers[uuid]?.gui) player.closeInventory()
        }
        viewers.clear()
    }

    // ---- Item serialization ----

    private fun serializeItem(item: ItemStack): String = Base64.getEncoder().encodeToString(item.serializeAsBytes())

    private fun deserializeItem(base64: String): ItemStack = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64))

    /** One unit of [item], plus a key that is equal for stacks that can merge. */
    private fun encode(item: ItemStack): OverflowStore.Encoded {
        val bytes = item.clone().apply { amount = 1 }.serializeAsBytes()
        val key = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return OverflowStore.Encoded(Base64.getEncoder().encodeToString(bytes), key, item.maxStackSize.coerceAtLeast(1))
    }

    private fun decode(stack: OverflowStore.StoredStack): ItemStack? =
        runCatching { deserializeItem(stack.item).apply { amount = stack.amount } }.getOrNull()

    // ---- Deposit (used by Orders/AH delivery fallback) ----

    /**
     * Stores a copy of [item] in the player's Overflow storage of [type]. There is no capacity
     * limit: this returns null once everything is stored, and the whole stack (with its full
     * metadata) only when the database write failed, so the caller can still hold it some
     * other way. [item] itself is never modified. Pass a stable [reference] for deliveries that
     * might be retried: a reference that was already stored stores nothing again.
     */
    fun depositItem(uuid: UUID, type: OverflowType, item: ItemStack, reference: String? = null): ItemStack? {
        if (item.type.isAir || item.amount <= 0) return null
        val copy = item.clone()
        return try {
            store.deposit(uuid, type.name, encode(copy), copy.amount, reference)
            refreshViewer(uuid, type)
            null
        } catch (e: Exception) {
            plugin.logger.severe("[Overflow] Depositing into $type storage of $uuid failed: ${e.message}")
            copy
        }
    }

    // ---- Event World crate keys (issue #1178) ----

    /** Players already told this event visit that their keys went to /overflow. */
    private val eventKeyNotified = ConcurrentHashMap.newKeySet<UUID>()

    /**
     * Stores a crate key given to [player] while they're in the Event World in their Crate Keys
     * storage, so it never enters the temporary event inventory. Returns what wasn't stored, like
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
            val view = viewers[player.uniqueId]
            if (view != null && plugin.guiManager.getOpenGui(player) === view.gui) player.closeInventory()
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

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        viewers.remove(event.player.uniqueId)
    }

    /** Number of stacks stored in [type] (one GUI slot each). */
    fun getItemCount(uuid: UUID, type: OverflowType): Long = store.countStacks(uuid, type.name)

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
            openOverflow(p, OverflowType.ORDERS)
        }
        gui.setItem(13, hubIcon(Material.TRIPWIRE_HOOK, OverflowType.CRATE_KEYS, player)) { p, _ ->
            openOverflow(p, OverflowType.CRATE_KEYS)
        }
        gui.setItem(15, hubIcon(Material.ENDER_CHEST, OverflowType.AUCTION_HOUSE, player)) { p, _ ->
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
        val stacks = store.countStacks(player.uniqueId, type.name)
        val items = store.countItems(player.uniqueId, type.name)
        val icon = ItemStack(material)
        icon.editMeta { meta ->
            meta.displayName(
                Component.text(type.label, NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  $items item(s) in $stacks stack(s)", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  Click to open", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
            ))
        }
        return icon
    }

    // ---- Storage pages ----

    fun openOverflow(player: Player, type: OverflowType, page: Int = 0) {
        if (blockedInEventWorld(player)) return
        val gui = CustomGui(Component.text(type.label).decoration(TextDecoration.ITALIC, false), GUI_SIZE)
        val view = View(gui, type, page)
        gui.onClose = { p -> viewers.remove(p.uniqueId, view) }
        render(player, view)
        viewers[player.uniqueId] = view
        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    /** Redraws [view] from the database: the page is clamped to the pages that exist now. */
    private fun render(player: Player, view: View) {
        val uuid = player.uniqueId
        val type = view.type
        val gui = view.gui
        val stacks = store.countStacks(uuid, type.name)
        val pages = totalPages(stacks)
        view.page = view.page.coerceIn(0, pages - 1)
        val rows = store.page(uuid, type.name, view.page, PAGE_SIZE)

        gui.clickHandlers.clear()
        gui.inventory.clear()
        rows.forEachIndexed { slot, row ->
            val item = decode(row)
            if (item == null) {
                gui.setItem(slot, GuiStyle.item(
                    Material.BARRIER,
                    Component.text("Unreadable item", NamedTextColor.RED),
                    listOf(Component.text("Ask a staff member for help.", NamedTextColor.GRAY))
                ))
            } else {
                gui.setItem(slot, item) { p, _ -> withdrawStack(p, view, row.id) }
            }
        }
        if (rows.isEmpty()) {
            gui.setItem(22, GuiStyle.item(Material.GRAY_STAINED_GLASS_PANE, Component.text("Nothing stored", NamedTextColor.GRAY)))
        }

        for (slot in PAGE_SIZE until GUI_SIZE) gui.inventory.setItem(slot, FILLER.clone())
        gui.setItem(GuiStyle.backSlot(GUI_SIZE), GuiStyle.back("Back to Overflow")) { p, _ ->
            GuiStyle.clickSound(p)
            openHub(p)
        }
        if (view.page > 0) {
            gui.setItem(GuiStyle.prevSlot(GUI_SIZE), GuiStyle.previousPage(view.page, pages)) { p, _ ->
                GuiStyle.pageSound(p)
                view.page--
                render(p, view)
            }
        }
        gui.setItem(GuiStyle.closeSlot(GUI_SIZE), GuiStyle.close()) { p, _ -> p.closeInventory() }
        if (view.page < pages - 1) {
            gui.setItem(GuiStyle.nextSlot(GUI_SIZE), GuiStyle.nextPage(view.page, pages)) { p, _ ->
                GuiStyle.pageSound(p)
                view.page++
                render(p, view)
            }
        }
        gui.setItem(WITHDRAW_ALL_SLOT, GuiStyle.item(
            Material.HOPPER,
            Component.text("Withdraw All", NamedTextColor.GREEN),
            listOf(
                GuiStyle.pageLine(view.page, pages),
                Component.text("$stacks stack(s) stored", NamedTextColor.GRAY),
                Component.empty(),
                Component.text("Click a stack to withdraw it, or", NamedTextColor.YELLOW),
                Component.text("here to take as much as fits.", NamedTextColor.YELLOW)
            )
        )) { p, _ -> withdrawAll(p, view) }
    }

    /** Redraws [uuid]'s open page of [type] after a deposit, so new items show up right away. */
    private fun refreshViewer(uuid: UUID, type: OverflowType) {
        val view = viewers[uuid] ?: return
        if (view.type != type || !plugin.server.isPrimaryThread) return
        val player = plugin.server.getPlayer(uuid) ?: return
        if (plugin.guiManager.getOpenGui(player) !== view.gui) return
        render(player, view)
    }

    // ---- Withdrawals ----
    // The GUI never holds the items: GuiManager cancels every click on it (and blocks
    // shift/number-key/double clicks from the player's own inventory), and each withdrawal
    // first takes the items out of the database with a conditional update, then gives them.
    // A stale slot, a double click or a second withdrawal of the same stack finds nothing left.

    /** False (and tells the player) when withdrawing isn't allowed right now. */
    private fun canWithdraw(player: Player): Boolean {
        if (plugin.eventManager.isInEventWorld(player)) {
            player.closeInventory()
            plugin.commsManager.send(player, Component.text("You can't withdraw from /overflow in the event world.", NamedTextColor.RED))
            return false
        }
        return true
    }

    private fun withdrawStack(player: Player, view: View, id: Long) {
        if (!canWithdraw(player)) return
        when (withdrawRow(player, view.type, id)) {
            Withdrawal.FULL -> {
                GuiStyle.errorSound(player)
                plugin.commsManager.send(player, Component.text("Your inventory is full. The items stay safely in Overflow.", NamedTextColor.RED))
            }
            Withdrawal.TAKEN -> GuiStyle.clickSound(player)
            Withdrawal.GONE, Withdrawal.FAILED -> GuiStyle.errorSound(player)
        }
        render(player, view)
    }

    private fun withdrawAll(player: Player, view: View) {
        if (!canWithdraw(player)) return
        var lastId = 0L
        var taken = 0
        var full = false
        while (true) {
            val batch = store.after(player.uniqueId, view.type.name, lastId, WITHDRAW_ALL_BATCH)
            if (batch.isEmpty()) break
            for (row in batch) {
                when (withdrawRow(player, view.type, row.id)) {
                    Withdrawal.TAKEN -> taken++
                    Withdrawal.FULL -> full = true
                    Withdrawal.GONE, Withdrawal.FAILED -> {}
                }
            }
            lastId = batch.last().id
            // Once no slot is empty, later stacks can at most top up a few partial stacks.
            if (player.inventory.firstEmpty() == -1) {
                full = full || store.after(player.uniqueId, view.type.name, lastId, 1).isNotEmpty()
                break
            }
        }
        if (taken > 0) GuiStyle.successSound(player) else GuiStyle.errorSound(player)
        if (full) {
            plugin.commsManager.send(player, Component.text("Your inventory is full. The rest stays safely in Overflow.", NamedTextColor.YELLOW))
        }
        render(player, view)
    }

    private enum class Withdrawal { TAKEN, FULL, GONE, FAILED }

    /** Moves as much of stack [id] as fits into [player]'s inventory; the rest stays stored. */
    private fun withdrawRow(player: Player, type: OverflowType, id: Long): Withdrawal {
        val uuid = player.uniqueId
        val row = try {
            store.get(uuid, type.name, id)
        } catch (e: Exception) {
            plugin.logger.severe("[Overflow] Reading $type stack $id of $uuid failed: ${e.message}")
            return Withdrawal.FAILED
        } ?: return Withdrawal.GONE
        val item = decode(row) ?: return Withdrawal.FAILED
        val fit = minOf(spaceFor(player, item), row.amount)
        if (fit <= 0) return Withdrawal.FULL

        val taken = try {
            store.take(uuid, type.name, id, fit)
        } catch (e: Exception) {
            plugin.logger.severe("[Overflow] Withdrawing $type stack $id of $uuid failed: ${e.message}")
            false
        }
        if (!taken) return Withdrawal.GONE

        val leftover = player.inventory.addItem(item.clone().apply { amount = fit }).values.firstOrNull()
        if (leftover != null && leftover.amount > 0) {
            // spaceFor said it fits, so this shouldn't happen; never lose what was taken.
            depositItem(uuid, type, leftover)?.let { unstored ->
                plugin.logger.severe("[Overflow] Couldn't put ${unstored.amount}x ${unstored.type} back into $type storage of $uuid; dropped at the player.")
                player.world.dropItemNaturally(player.location, unstored)
            }
        }
        return Withdrawal.TAKEN
    }

    /** How many of [item] the player's storage slots can take right now. */
    private fun spaceFor(player: Player, item: ItemStack): Int {
        val max = item.maxStackSize.coerceAtLeast(1)
        var space = 0L
        for (slot in player.inventory.storageContents) {
            space += when {
                slot == null || slot.type.isAir -> max
                slot.isSimilar(item) -> (max - slot.amount).coerceAtLeast(0)
                else -> 0
            }
        }
        return space.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
