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
        val onToggle: ((Player, Boolean) -> Unit)? = null
    )

    private val settings = mutableListOf<SettingDef>()
    private val cache = ConcurrentHashMap<UUID, MutableMap<String, Boolean>>()

    private val SETTINGS_TITLE = Component.text("       ")
        .append(Component.text("S", TextColor.color(0x55FFFF)))
        .append(Component.text("e", TextColor.color(0x66EEFF)))
        .append(Component.text("t", TextColor.color(0x77DDFF)))
        .append(Component.text("t", TextColor.color(0x88CCFF)))
        .append(Component.text("i", TextColor.color(0x99BBFF)))
        .append(Component.text("n", TextColor.color(0xAAAAFF)))
        .append(Component.text("g", TextColor.color(0xBB99FF)))
        .append(Component.text("s", TextColor.color(0xCC88FF)))
        .decoration(TextDecoration.BOLD, true)
        .decoration(TextDecoration.ITALIC, false)

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { meta -> meta.displayName(Component.empty()) }
    }

    private val BORDER = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
        editMeta { meta -> meta.displayName(Component.empty()) }
    }

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_settings (
                uuid TEXT NOT NULL,
                setting TEXT NOT NULL,
                value INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY (uuid, setting)
            )
        """.trimIndent())
    }

    fun register(setting: SettingDef) {
        settings.add(setting)
    }

    fun getRegisteredSettings(): List<SettingDef> = settings.toList()

    fun getSetting(player: Player, key: String): Boolean {
        val playerCache = cache.getOrPut(player.uniqueId) { loadSettings(player.uniqueId) }
        val def = settings.find { it.key == key }
        return playerCache[key] ?: def?.default ?: true
    }

    fun setSetting(player: Player, key: String, value: Boolean) {
        cache.getOrPut(player.uniqueId) { loadSettings(player.uniqueId) }[key] = value
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO player_settings (uuid, setting, value) VALUES (?, ?, ?)",
            player.uniqueId.toString(), key, if (value) 1 else 0
        )
    }

    fun toggle(player: Player, key: String): Boolean {
        val current = getSetting(player, key)
        val newValue = !current
        setSetting(player, key, newValue)
        return newValue
    }

    fun openGui(player: Player) {
        val visibleSettings = settings.filter { def ->
            def.permission == null || player.hasPermission(def.permission)
        }

        // 5 rows (45 slots) for a clean look
        val gui = CustomGui(SETTINGS_TITLE, 45)

        // Fill entire inventory with filler
        for (i in 0 until 45) {
            gui.inventory.setItem(i, FILLER.clone())
        }

        // Border on top and bottom rows
        for (i in 0..8) {
            gui.inventory.setItem(i, BORDER.clone())
            gui.inventory.setItem(36 + i, BORDER.clone())
        }
        // Border on left/right edges
        for (row in 1..3) {
            gui.inventory.setItem(row * 9, BORDER.clone())
            gui.inventory.setItem(row * 9 + 8, BORDER.clone())
        }

        // Center the settings in the middle rows (rows 1-3, columns 1-7)
        // Available slots: 7 per row, 3 rows = 21 max
        val availableSlots = mutableListOf<Int>()
        for (row in 1..3) {
            for (col in 1..7) {
                availableSlots.add(row * 9 + col)
            }
        }

        // Center the items within the available space
        val centeredSlots = centerItems(visibleSettings.size, availableSlots)

        for ((settingIdx, slot) in centeredSlots.withIndex()) {
            val def = visibleSettings[settingIdx]
            val enabled = getSetting(player, def.key)
            gui.setItem(slot, buildSettingItem(def, enabled)) { p, event ->
                if (def.permission != null && !p.hasPermission(def.permission)) {
                    p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                    return@setItem
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
        plugin.databaseManager.query(
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

    /**
     * Centers N items within a grid of available slots.
     * Returns the slot positions to use for each item.
     */
    private fun centerItems(count: Int, availableSlots: List<Int>): List<Int> {
        if (count == 0) return emptyList()
        if (count >= availableSlots.size) return availableSlots.take(count)

        // Group slots by row
        val rows = availableSlots.groupBy { it / 9 }.values.toList()
        val itemsPerRow = 7 // columns 1-7

        return when {
            count <= itemsPerRow -> {
                // Single row — center in middle row (row 2)
                val middleRow = rows.getOrElse(1) { rows[0] }
                centerInRow(count, middleRow)
            }
            count <= itemsPerRow * 2 -> {
                // Two rows — use rows 1 and 3 (skip middle for symmetry)
                val topCount = (count + 1) / 2
                val bottomCount = count - topCount
                val topRow = rows.getOrElse(0) { rows[0] }
                val bottomRow = rows.getOrElse(2) { rows.last() }
                centerInRow(topCount, topRow) + centerInRow(bottomCount, bottomRow)
            }
            else -> {
                // Three rows
                val perRow = (count + 2) / 3
                val result = mutableListOf<Int>()
                for ((i, row) in rows.withIndex()) {
                    val rowCount = when (i) {
                        rows.lastIndex -> count - result.size
                        else -> perRow.coerceAtMost(count - result.size)
                    }
                    result.addAll(centerInRow(rowCount, row))
                }
                result
            }
        }
    }

    private fun centerInRow(count: Int, rowSlots: List<Int>): List<Int> {
        if (count >= rowSlots.size) return rowSlots
        val offset = (rowSlots.size - count) / 2
        return rowSlots.subList(offset, offset + count)
    }
}
