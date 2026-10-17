package com.liam.joshymc.communityevent.game

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.SqliteDatabase
import org.bukkit.inventory.ItemStack

/**
 * The saved Crystal PvP kit (issue #1189): one item per player-inventory slot (0-35 storage,
 * 36-39 armor boots to helmet, 40 off-hand), as Paper's item bytes so enchantments, metadata
 * and amounts survive exactly. Kept in `event_cpvp_kit` in data.db and in memory once loaded.
 */
class CrystalPvpKit(private val plugin: Joshymc) {

    private var store: CrystalPvpKitStore? = null
    private var slots: Map<Int, ItemStack> = emptyMap()

    val available: Boolean get() = store != null
    val isEmpty: Boolean get() = slots.isEmpty()
    val size: Int get() = slots.size

    /** Copies of the saved items by slot. */
    fun items(): Map<Int, ItemStack> = slots.mapValues { it.value.clone() }

    fun load() {
        try {
            val s = CrystalPvpKitStore(plugin.databaseManager)
            s.createSchema()
            val loaded = LinkedHashMap<Int, ItemStack>()
            for ((slot, bytes) in s.loadAll()) {
                val item = runCatching { ItemStack.deserializeBytes(bytes) }.getOrElse {
                    plugin.logger.warning("[Event] Crystal PvP kit slot $slot can't be read (${it.message}); it was skipped.")
                    null
                } ?: continue
                if (slot in SLOTS && !item.type.isAir) loaded[slot] = item
            }
            slots = loaded
            store = s
        } catch (e: Exception) {
            store = null
            plugin.logger.severe("[Event] Couldn't load the Crystal PvP kit; Crystal PvP can't start until the next restart: ${e.message}")
        }
    }

    /** Replaces the kit. Returns null when saved, or why not. Nothing changes if the save fails. */
    fun save(items: Map<Int, ItemStack>): String? {
        val s = store ?: return "Kit storage failed to load at startup; check the console."
        val clean = items.filter { (slot, item) -> slot in SLOTS && !item.type.isAir }.mapValues { it.value.clone() }
        if (clean.isEmpty()) return "Your inventory is empty; there's nothing to save."
        try {
            s.replace(clean.mapValues { it.value.serializeAsBytes() })
        } catch (e: Exception) {
            plugin.logger.warning("[Event] Failed to save the Crystal PvP kit: ${e.message}")
            return "Couldn't save the kit (${e.message}). The old kit is unchanged."
        }
        slots = clean
        return null
    }

    companion object {
        val SLOTS = 0..40
    }
}

/** `event_cpvp_kit`: slot -> item bytes. No Bukkit. */
class CrystalPvpKitStore(private val db: SqliteDatabase) {

    fun createSchema() {
        db.createTable("CREATE TABLE IF NOT EXISTS event_cpvp_kit (slot INTEGER PRIMARY KEY, item BLOB NOT NULL)")
    }

    fun loadAll(): List<Pair<Int, ByteArray>> =
        db.query("SELECT slot, item FROM event_cpvp_kit ORDER BY slot") { rs -> rs.getInt("slot") to rs.getBytes("item") }

    /** Swaps the whole kit in one transaction. */
    fun replace(items: Map<Int, ByteArray>) {
        db.transaction {
            db.execute("DELETE FROM event_cpvp_kit")
            for ((slot, bytes) in items) db.execute("INSERT INTO event_cpvp_kit (slot, item) VALUES (?, ?)", slot, bytes)
        }
    }
}
