package com.liam.joshymc.communityevent.game

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.SqliteDatabase
import org.bukkit.inventory.ItemStack

/**
 * A saved hosted-event kit (Crystal PvP, issue #1189; Mob Waves, issue #1192): one item per
 * player-inventory slot (0-35 storage, 36-39 armor boots to helmet, 40 off-hand), as Paper's
 * item bytes so enchantments, metadata and amounts survive exactly. Kept in its own [table] in
 * data.db and in memory once loaded. [label] names the kit in messages.
 */
class EventKit(private val plugin: Joshymc, private val table: String, val label: String) {

    private var store: EventKitStore? = null
    private var slots: Map<Int, ItemStack> = emptyMap()

    val available: Boolean get() = store != null
    val isEmpty: Boolean get() = slots.isEmpty()
    val size: Int get() = slots.size

    /** Copies of the saved items by slot. */
    fun items(): Map<Int, ItemStack> = slots.mapValues { it.value.clone() }

    fun load() {
        try {
            val s = EventKitStore(plugin.databaseManager, table)
            s.createSchema()
            val loaded = LinkedHashMap<Int, ItemStack>()
            for ((slot, bytes) in s.loadAll()) {
                val item = runCatching { ItemStack.deserializeBytes(bytes) }.getOrElse {
                    plugin.logger.warning("[Event] $label kit slot $slot can't be read (${it.message}); it was skipped.")
                    null
                } ?: continue
                if (slot in SLOTS && !item.type.isAir) loaded[slot] = item
            }
            slots = loaded
            store = s
        } catch (e: Exception) {
            store = null
            plugin.logger.severe("[Event] Couldn't load the $label kit; $label can't start until the next restart: ${e.message}")
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
            plugin.logger.warning("[Event] Failed to save the $label kit: ${e.message}")
            return "Couldn't save the kit (${e.message}). The old kit is unchanged."
        }
        slots = clean
        return null
    }

    companion object {
        val SLOTS = 0..40
    }
}

/** Result of `/event <type> setkit`. */
data class KitSave(val saved: Boolean, val message: String)

/** One kit table: slot -> item bytes. No Bukkit. [table] is a fixed name from code, never user input. */
class EventKitStore(private val db: SqliteDatabase, private val table: String) {

    fun createSchema() {
        db.createTable("CREATE TABLE IF NOT EXISTS $table (slot INTEGER PRIMARY KEY, item BLOB NOT NULL)")
    }

    fun loadAll(): List<Pair<Int, ByteArray>> =
        db.query("SELECT slot, item FROM $table ORDER BY slot") { rs -> rs.getInt("slot") to rs.getBytes("item") }

    /** Swaps the whole kit in one transaction. */
    fun replace(items: Map<Int, ByteArray>) {
        db.transaction {
            db.execute("DELETE FROM $table")
            for ((slot, bytes) in items) db.execute("INSERT INTO $table (slot, item) VALUES (?, ?)", slot, bytes)
        }
    }
}
