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
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent withdraw-only storage ("/overflow") that Orders and Auction House deliveries fall
 * back to when a buyer's inventory is full, so an already-committed delivery is never dropped,
 * deleted, or duplicated. Players can take items out but cannot manually deposit into it.
 */
class OverflowManager(private val plugin: Joshymc) : Listener {

    enum class OverflowType(val label: String, val size: Int) {
        ORDERS("Orders Overflow", 54),
        AUCTION_HOUSE("AH Overflow", 9)
    }

    companion object {
        private val HUB_TITLE: Component = Component.text("         ")
            .append(Component.text("Overflow Storage", NamedTextColor.GOLD))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
    }

    /** uuid -> (type, live inventory) for whoever currently has an Overflow storage open. */
    private val openOverflow = ConcurrentHashMap<UUID, Pair<OverflowType, Inventory>>()

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
        plugin.logger.info("[Overflow] OverflowManager started.")
    }

    /** Saves any currently open Overflow storages. Called on server shutdown/reload. */
    fun saveOpenOverflows() {
        for ((uuid, entry) in openOverflow.toMap()) {
            saveOverflow(uuid, entry.first, entry.second)
        }
        openOverflow.clear()
    }

    // ---- Item serialization ----

    private fun serializeItem(item: ItemStack): String = Base64.getEncoder().encodeToString(item.serializeAsBytes())

    private fun deserializeItem(base64: String): ItemStack = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64))

    // ---- Deposit (used by Orders/AH delivery fallback) ----

    /**
     * Persists [item] into the player's Overflow storage of [type], using the SAME stack that
     * would otherwise have been delivered (no copies made). Returns false without storing
     * anything if that storage has no free slot left.
     */
    fun depositItem(uuid: UUID, type: OverflowType, item: ItemStack): Boolean {
        if (item.amount <= 0) return true

        // If the player currently has this exact storage open, deposit straight into the live
        // inventory instead of the DB, so we don't clobber what they're looking at right now.
        val open = openOverflow[uuid]
        if (open != null && open.first == type) {
            return open.second.addItem(item).isEmpty()
        }

        val usedSlots = plugin.databaseManager.query(
            "SELECT slot FROM overflow_storage WHERE uuid = ? AND type = ?",
            uuid.toString(), type.name
        ) { rs -> rs.getInt("slot") }.toSet()

        val freeSlot = (0 until type.size).firstOrNull { it !in usedSlots } ?: return false

        plugin.databaseManager.execute(
            "INSERT INTO overflow_storage (uuid, type, slot, item) VALUES (?, ?, ?, ?)",
            uuid.toString(), type.name, freeSlot, serializeItem(item)
        )
        return true
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

    fun openHub(player: Player) {
        val gui = CustomGui(HUB_TITLE, 27)
        for (i in 0 until 27) gui.inventory.setItem(i, FILLER.clone())

        gui.setItem(11, hubIcon(Material.CHEST, OverflowType.ORDERS, player)) { p, _ ->
            p.closeInventory()
            openOverflow(p, OverflowType.ORDERS)
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
        val uuid = player.uniqueId
        val title = Component.text(type.label).decoration(TextDecoration.ITALIC, false)
        val inv = Bukkit.createInventory(null, type.size, title)

        val items = plugin.databaseManager.query(
            "SELECT slot, item FROM overflow_storage WHERE uuid = ? AND type = ?",
            uuid.toString(), type.name
        ) { rs -> rs.getInt("slot") to deserializeItem(rs.getString("item")) }

        for ((slot, item) in items) {
            if (slot in 0 until type.size) inv.setItem(slot, item)
        }

        // Anti-dupe: clear the DB immediately after loading — the only copy of these items
        // now lives in this inventory. It gets written back on close or quit.
        plugin.databaseManager.execute(
            "DELETE FROM overflow_storage WHERE uuid = ? AND type = ?",
            uuid.toString(), type.name
        )

        openOverflow[uuid] = type to inv
        player.openInventory(inv)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun saveOverflow(uuid: UUID, type: OverflowType, inventory: Inventory) {
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

        if (event.clickedInventory == inv) {
            val cursorHasItem = !event.cursor.type.isAir
            val isDeposit = event.click == ClickType.NUMBER_KEY ||
                event.click == ClickType.SWAP_OFFHAND ||
                event.action == InventoryAction.CLONE_STACK ||
                (cursorHasItem && (event.click == ClickType.LEFT || event.click == ClickType.RIGHT))
            if (isDeposit) {
                event.isCancelled = true
            }
            return
        }

        // Clicked the player's own inventory while Overflow is open — block shift-clicking
        // items INTO the storage; ordinary rearranging of their own inventory is fine.
        if (event.isShiftClick) {
            event.isCancelled = true
        }
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
        val entry = openOverflow.remove(player.uniqueId) ?: return
        saveOverflow(player.uniqueId, entry.first, event.inventory)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        val entry = openOverflow.remove(uuid) ?: return
        saveOverflow(uuid, entry.first, entry.second)
    }
}
