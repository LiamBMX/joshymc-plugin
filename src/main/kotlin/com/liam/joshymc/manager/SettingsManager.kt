package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SettingsManager(private val plugin: Joshymc) {

    data class SettingDef(
        val key: String,
        val displayName: String,
        val description: String,
        val material: Material,
        val disabledMaterial: Material = Material.GRAY_DYE,
        val default: Boolean,
        val permission: String? = null,
        val onToggle: ((Player, Boolean) -> Unit)? = null,
        // Hidden settings stay registered (so getSetting/setSetting keep working
        // for the dedicated command/listener that owns them) but are no longer
        // shown in the /settings GUI or discoverable via /settings <key>.
        val hidden: Boolean = false,
        // Returns why the player can't turn this setting on right now (e.g. Team
        // Chat without a team), or null when enabling is allowed. Turning a
        // setting off is never blocked.
        val enableBlockedReason: ((Player) -> String?)? = null
    )

    private val settings = mutableListOf<SettingDef>()
    private val cache = ConcurrentHashMap<UUID, MutableMap<String, Boolean>>()

    // Settings are the first system migrated to playerdata.db (issue #758).
    // Falls back to data.db if playerdata.db init/migration fails, so a bad
    // migration never leaves settings unreadable.
    private var store: SqliteDatabase = plugin.databaseManager

    private val SETTINGS_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS player_settings (
            uuid TEXT NOT NULL,
            setting TEXT NOT NULL,
            value INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY (uuid, setting)
        )
    """.trimIndent()

    // Inventory titles always start at the client's fixed left edge, so the only
    // portable way to center one is plain leading spaces. The padding is measured
    // from vanilla glyph widths and stays unbolded (bold spaces are 1px wider).
    private val SETTINGS_TITLE = Component.text(" ".repeat(SettingsLayout.TITLE_PADDING_SPACES))
        .append(
            Component.text("S", TextColor.color(0x55FFFF))
                .append(Component.text("e", TextColor.color(0x66EEFF)))
                .append(Component.text("t", TextColor.color(0x77DDFF)))
                .append(Component.text("t", TextColor.color(0x88CCFF)))
                .append(Component.text("i", TextColor.color(0x99BBFF)))
                .append(Component.text("n", TextColor.color(0xAAAAFF)))
                .append(Component.text("g", TextColor.color(0xBB99FF)))
                .append(Component.text("s", TextColor.color(0xCC88FF)))
                .decoration(TextDecoration.BOLD, true)
        )
        .decoration(TextDecoration.ITALIC, false)

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { meta -> meta.displayName(Component.empty()) }
    }

    fun start() {
        // Old table stays in data.db as rollback safety; never dropped here.
        plugin.databaseManager.createTable(SETTINGS_TABLE_SQL)

        try {
            plugin.playerDatabaseManager.createTable(SETTINGS_TABLE_SQL)
            migrateToPlayerDatabase()
            store = plugin.playerDatabaseManager
        } catch (e: Exception) {
            plugin.logger.severe("[Settings] Failed to migrate to playerdata.db, staying on data.db: ${e.message}")
            store = plugin.databaseManager
        }
    }

    /**
     * One-time copy of player_settings from data.db into playerdata.db.
     * Source rows are left untouched. Only marks migrated once the row
     * count in playerdata.db matches the source, so a partial failure
     * retries on next startup instead of silently switching over.
     */
    private fun migrateToPlayerDatabase() {
        val migrationName = "player_settings_v1"
        if (plugin.playerDatabaseManager.hasMigrated(migrationName)) return

        val rows = plugin.databaseManager.query(
            "SELECT uuid, setting, value FROM player_settings"
        ) { rs -> Triple(rs.getString("uuid"), rs.getString("setting"), rs.getInt("value")) }

        plugin.playerDatabaseManager.transaction {
            for ((uuid, setting, value) in rows) {
                plugin.playerDatabaseManager.execute(
                    "INSERT OR REPLACE INTO player_settings (uuid, setting, value) VALUES (?, ?, ?)",
                    uuid, setting, value
                )
            }
        }

        val migratedCount = plugin.playerDatabaseManager.queryFirst(
            "SELECT COUNT(*) AS c FROM player_settings"
        ) { it.getInt("c") } ?: 0

        if (migratedCount < rows.size) {
            throw IllegalStateException("expected ${rows.size} rows, playerdata.db has $migratedCount")
        }

        plugin.playerDatabaseManager.markMigrated(migrationName)
        plugin.logger.info("[Settings] Migrated ${rows.size} player_settings row(s) to playerdata.db")
    }

    fun register(setting: SettingDef) {
        settings.add(setting)
    }

    fun getRegisteredSettings(): List<SettingDef> = settings.toList()

    fun getVisibleSettings(player: Player): List<SettingDef> = settings.filter { def ->
        !def.hidden && (def.permission == null || player.hasPermission(def.permission))
    }

    fun getSetting(player: Player, key: String): Boolean {
        val playerCache = cache.getOrPut(player.uniqueId) { loadSettings(player.uniqueId) }
        val def = settings.find { it.key == key }
        return playerCache[key] ?: def?.default ?: true
    }

    fun setSetting(player: Player, key: String, value: Boolean) {
        cache.getOrPut(player.uniqueId) { loadSettings(player.uniqueId) }[key] = value
        store.execute(
            "INSERT OR REPLACE INTO player_settings (uuid, setting, value) VALUES (?, ?, ?)",
            player.uniqueId.toString(), key, if (value) 1 else 0
        )
    }

    /**
     * Writes a setting for a player who may be offline (e.g. clearing Team Chat
     * when they're kicked from their team). Only touches the cache if it's loaded.
     */
    fun setSetting(uuid: UUID, key: String, value: Boolean) {
        cache[uuid]?.set(key, value)
        store.execute(
            "INSERT OR REPLACE INTO player_settings (uuid, setting, value) VALUES (?, ?, ?)",
            uuid.toString(), key, if (value) 1 else 0
        )
    }

    fun toggle(player: Player, key: String): Boolean {
        val current = getSetting(player, key)
        val newValue = !current
        setSetting(player, key, newValue)
        return newValue
    }

    fun openGui(player: Player) {
        val visibleSettings = getVisibleSettings(player)

        // One centered row (issue #1263); extra rows only past nine settings.
        val layout = SettingsLayout.slots(visibleSettings.size)
        val gui = CustomGui(SETTINGS_TITLE, SettingsLayout.size(visibleSettings.size))

        for (i in 0 until gui.inventory.size) {
            gui.inventory.setItem(i, FILLER.clone())
        }

        for ((settingIdx, slot) in layout.withIndex()) {
            val def = visibleSettings[settingIdx]
            val enabled = getSetting(player, def.key)
            gui.setItem(slot, buildSettingItem(def, enabled)) { p, event ->
                if (def.permission != null && !p.hasPermission(def.permission)) {
                    p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                    return@setItem
                }

                if (!getSetting(p, def.key)) {
                    val blocked = def.enableBlockedReason?.invoke(p)
                    if (blocked != null) {
                        p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                        plugin.commsManager.send(p, Component.text(blocked, NamedTextColor.RED), CommunicationsManager.Category.SETTINGS)
                        return@setItem
                    }
                }

                val newValue = toggle(p, def.key)
                event.inventory.setItem(slot, buildSettingItem(def, newValue))

                // Fire the callback so features can react (e.g. apply/remove night vision)
                def.onToggle?.invoke(p, newValue)

                // Sound feedback
                if (newValue) {
                    p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 2.0f)
                } else {
                    p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f)
                }

                val status = if (newValue)
                    Component.text(" ENABLED", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true)
                else
                    Component.text(" DISABLED", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)

                p.sendMessage(
                    Component.text(def.displayName, NamedTextColor.WHITE).append(status)
                )
            }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    fun evictCache(uuid: UUID) {
        cache.remove(uuid)
    }

    private fun loadSettings(uuid: UUID): MutableMap<String, Boolean> {
        val map = mutableMapOf<String, Boolean>()
        store.query(
            "SELECT setting, value FROM player_settings WHERE uuid = ?",
            uuid.toString()
        ) { rs ->
            map[rs.getString("setting")] = rs.getInt("value") == 1
        }
        return map
    }

    private fun buildSettingItem(def: SettingDef, enabled: Boolean): ItemStack {
        val material = if (enabled) def.material else def.disabledMaterial
        val item = ItemStack(material)
        val meta = item.itemMeta!!

        val nameColor = if (enabled) TextColor.color(0x55FF55) else TextColor.color(0xFF5555)
        val statusText = if (enabled) "ON" else "OFF"
        val statusColor = if (enabled) NamedTextColor.GREEN else NamedTextColor.RED
        val toggleText = if (enabled) "Click to disable" else "Click to enable"

        meta.displayName(
            Component.text(def.displayName, nameColor)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true)
        )

        meta.lore(listOf(
            Component.empty(),
            Component.text(def.description, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            Component.text("  ", NamedTextColor.DARK_GRAY)
                .append(if (enabled)
                    Component.text("\u25C9 ", NamedTextColor.GREEN) // ◉
                else
                    Component.text("\u25CB ", NamedTextColor.RED))  // ○
                .append(Component.text(statusText, statusColor).decoration(TextDecoration.BOLD, true))
                .decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            Component.text("  $toggleText", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ))

        if (enabled) {
            meta.setEnchantmentGlintOverride(true)
        }

        item.itemMeta = meta
        return item
    }
}
