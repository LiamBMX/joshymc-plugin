package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Event
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Expands every player's Ender Chest from vanilla's 27 slots to a
 * double-chest-sized 54. Slots 0-26 mirror the player's real vanilla Ender
 * Chest (so anything else touching `player.enderChest` — death, plugins,
 * dimension changes — keeps working untouched), while slots 27-53 are
 * JoshyMC-persisted extra storage kept in the `enderchest_extra` table.
 *
 * All access points (placed blocks, /enderchest, ModMode ECSee) funnel
 * through [open] so there is only ever one live, authoritative inventory
 * per target player, shared by every viewer.
 */
class EnderChestManager(private val plugin: Joshymc) : Listener {

    companion object {
        const val EXTRA_SLOTS = 27
        private val TITLE: Component = Component.text("Ender Chest")
    }

    private class Holder(val targetUuid: UUID) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    /** Target UUID -> the single live merged inventory currently open for them. */
    private val sessions = ConcurrentHashMap<UUID, Inventory>()

    fun start() {
        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS enderchest_extra (
                uuid TEXT PRIMARY KEY,
                data TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    /** Opens [target]'s expanded 54-slot Ender Chest for [viewer]. [target] must be online. */
    fun open(viewer: Player, target: Player) {
        val inv = sessions.getOrPut(target.uniqueId) { buildSession(target) }
        viewer.openInventory(inv)
    }

    /** Read-only snapshot of a player's extra (slots 27-53) storage, for staff view GUIs. */
    fun snapshotExtra(uuid: UUID): List<Pair<Int, ItemStack>> = loadExtra(uuid)

    private fun buildSession(target: Player): Inventory {
        val holder = Holder(target.uniqueId)
        val inv = Bukkit.createInventory(holder, 54, TITLE)
        holder.inv = inv

        val base = target.enderChest.contents
        for (i in 0 until minOf(EXTRA_SLOTS, base.size)) {
            inv.setItem(i, base[i])
        }
        for ((slot, item) in loadExtra(target.uniqueId)) {
            inv.setItem(EXTRA_SLOTS + slot, item)
        }
        return inv
    }

    private fun persist(targetUuid: UUID, inv: Inventory) {
        val target = Bukkit.getPlayer(targetUuid)
        if (target != null) {
            val ec = target.enderChest
            for (i in 0 until EXTRA_SLOTS) {
                ec.setItem(i, inv.getItem(i))
            }
        }

        val extra = mutableListOf<Pair<Int, ItemStack>>()
        for (i in 0 until EXTRA_SLOTS) {
            val item = inv.getItem(EXTRA_SLOTS + i) ?: continue
            if (item.type == Material.AIR) continue
            extra.add(i to item)
        }
        saveExtra(targetUuid, extra)
    }

    private fun loadExtra(uuid: UUID): List<Pair<Int, ItemStack>> {
        val data = plugin.databaseManager.queryFirst(
            "SELECT data FROM enderchest_extra WHERE uuid = ?", uuid.toString()
        ) { rs -> rs.getString("data") } ?: return emptyList()
        if (data.isBlank()) return emptyList()

        val result = mutableListOf<Pair<Int, ItemStack>>()
        for (entry in data.split(";")) {
            val colonIdx = entry.indexOf(':')
            if (colonIdx < 0) continue
            val slot = entry.substring(0, colonIdx).toIntOrNull() ?: continue
            if (slot !in 0 until EXTRA_SLOTS) continue
            try {
                val bytes = Base64.getDecoder().decode(entry.substring(colonIdx + 1))
                result.add(slot to ItemStack.deserializeBytes(bytes))
            } catch (_: Exception) {
                // Skip corrupted slots
            }
        }
        return result
    }

    private fun saveExtra(uuid: UUID, items: List<Pair<Int, ItemStack>>) {
        val data = items.joinToString(";") { (slot, item) ->
            "$slot:${Base64.getEncoder().encodeToString(item.serializeAsBytes())}"
        }
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO enderchest_extra (uuid, data) VALUES (?, ?)",
            uuid.toString(), data
        )
    }

    /** Saves + drops every currently open session. Called on server shutdown/reload. */
    fun saveOpenSessions() {
        for ((targetUuid, inv) in sessions.toMap()) {
            persist(targetUuid, inv)
        }
        sessions.clear()
    }

    // ---- Events ----

    /** Redirects placed Ender Chest blocks to the expanded 54-slot inventory. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onInteractBlock(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.ENDER_CHEST) return

        event.setUseInteractedBlock(Event.Result.DENY)
        open(event.player, event.player)
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? Holder ?: return
        persist(holder.targetUuid, event.inventory)
        // Only drop the cached session once every viewer (self + any staff) has left.
        if (event.inventory.viewers.size <= 1) {
            sessions.remove(holder.targetUuid)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        val inv = sessions[uuid] ?: return
        persist(uuid, inv)
        if (inv.viewers.none { it.uniqueId != uuid }) {
            sessions.remove(uuid)
        }
    }
}
