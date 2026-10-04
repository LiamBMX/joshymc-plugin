package com.liam.joshymc.gui

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.PunishPresetManager.Preset
import com.liam.joshymc.manager.PunishPresetManager.PresetType
import com.liam.joshymc.manager.PunishPresetManager.Punishment
import com.liam.joshymc.manager.PunishmentManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `/punish <player>` preset picker. Picking a preset runs the typed `/punish` command as the
 * staff member, so permission checks, rank checks, history, Discord logs and staff
 * notifications are exactly the same as the typed form. Bans ask for confirmation first.
 */
class PunishGui(private val plugin: Joshymc) {

    companion object {
        /** Window in which a second punishment for the same target is refused (rapid-click guard). */
        private const val DUPLICATE_WINDOW_MS = 3000L
    }

    private val recent = ConcurrentHashMap<UUID, Long>()

    fun open(staff: Player, targetUuid: UUID, targetName: String) {
        val presets = plugin.punishPresetManager.getEnabledPresets()
            .filter { preset ->
                // Hide presets the staff member can't use for the offense level this target is at
                staff.hasPermission(plugin.punishPresetManager.resolve(targetUuid, preset).type.permission)
            }
        if (presets.isEmpty()) {
            plugin.commsManager.send(staff, Component.text("No punishment presets are available to you.", NamedTextColor.RED))
            return
        }

        val size = ((presets.size + 8) / 9 * 9).coerceIn(9, 54)
        val gui = CustomGui(Component.text("Punish: $targetName".take(28)), size)
        for ((index, preset) in presets.take(54).withIndex()) {
            val punishment = plugin.punishPresetManager.resolve(targetUuid, preset)
            val offense = plugin.punishPresetManager.priorOffenses(targetUuid, preset) + 1
            val lore = mutableListOf(
                line("Action: ${describe(punishment)}", NamedTextColor.YELLOW)
            )
            if (preset.escalation.isNotEmpty()) lore.add(line("Offense #$offense", NamedTextColor.GRAY))
            lore.add(line(if (punishment.type == PresetType.BAN) "Click to review" else "Click to punish", NamedTextColor.GREEN))
            gui.setItem(index, item(preset.material, preset.name, NamedTextColor.RED, lore)) { p, _ ->
                select(p, targetUuid, targetName, preset)
            }
        }
        plugin.guiManager.open(staff, gui)
    }

    private fun select(staff: Player, targetUuid: UUID, targetName: String, preset: Preset) {
        val punishment = plugin.punishPresetManager.resolve(targetUuid, preset)
        if (!staff.hasPermission(punishment.type.permission)) {
            staff.closeInventory()
            plugin.commsManager.send(staff, Component.text("No permission.", NamedTextColor.RED))
            return
        }
        if (punishment.type == PresetType.BAN) confirm(staff, targetUuid, targetName, preset, punishment)
        else execute(staff, targetUuid, targetName, preset)
    }

    private fun confirm(staff: Player, targetUuid: UUID, targetName: String, preset: Preset, shown: Punishment) {
        val gui = CustomGui(Component.text("Confirm ban: $targetName".take(28)), 27)
        gui.setItem(11, item(Material.LIME_CONCRETE, "Confirm", NamedTextColor.GREEN, listOf(
            line("Target: $targetName", NamedTextColor.GRAY),
            line("Reason: ${preset.name}", NamedTextColor.GRAY),
            line("Action: ${describe(shown)}", NamedTextColor.RED)
        ))) { p, _ -> execute(p, targetUuid, targetName, preset, expected = shown) }
        gui.setItem(15, item(Material.RED_CONCRETE, "Cancel", NamedTextColor.RED, emptyList())) { p, _ ->
            open(p, targetUuid, targetName)
        }
        plugin.guiManager.open(staff, gui)
    }

    /**
     * Runs the typed command. [expected] is what the staff member was shown; if escalation
     * moved the target to a different action in the meantime, nothing is issued.
     */
    private fun execute(staff: Player, targetUuid: UUID, targetName: String, preset: Preset, expected: Punishment? = null) {
        staff.closeInventory()

        val punishment = plugin.punishPresetManager.resolve(targetUuid, preset)
        if (expected != null && expected != punishment) {
            plugin.commsManager.send(staff, Component.text("$targetName's offense level changed - please review and try again.", NamedTextColor.RED))
            return
        }
        if (!staff.hasPermission(punishment.type.permission)) {
            plugin.commsManager.send(staff, Component.text("No permission.", NamedTextColor.RED))
            return
        }

        val now = System.currentTimeMillis()
        val last = recent.put(targetUuid, now)
        if (last != null && now - last < DUPLICATE_WINDOW_MS) {
            plugin.commsManager.send(staff, Component.text("$targetName was just punished - ignoring duplicate.", NamedTextColor.RED))
            return
        }
        recent.values.removeIf { now - it > DUPLICATE_WINDOW_MS }

        val duration = punishment.duration?.let { " $it" } ?: ""
        Bukkit.dispatchCommand(staff, "punish $targetName ${punishment.type.commandType}$duration ${preset.name}")
    }

    private fun describe(p: Punishment): String = when (p.type) {
        PresetType.WARN -> "Warn"
        PresetType.MUTE -> "Mute for ${PunishmentManager.formatDuration(PunishmentManager.parseDuration(p.duration!!)!!)}"
        PresetType.TEMPBAN -> "Ban for ${PunishmentManager.formatDuration(PunishmentManager.parseDuration(p.duration!!)!!)}"
        PresetType.BAN -> "Permanent ban"
    }

    private fun line(text: String, color: NamedTextColor) =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private fun item(material: Material, name: String, color: NamedTextColor, lore: List<Component>): ItemStack {
        val stack = ItemStack(material)
        stack.editMeta { meta ->
            meta.displayName(line(name, color))
            meta.lore(lore)
        }
        return stack
    }
}
