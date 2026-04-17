package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class NightVisionCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        const val SETTING_KEY = "night_vision"
        private val INFINITE = PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, true)

        fun applyNightVision(player: Player, enabled: Boolean) {
            if (enabled) {
                player.addPotionEffect(INFINITE)
            } else {
                player.removePotionEffect(PotionEffectType.NIGHT_VISION)
            }
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.nightvision")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.SETTINGS)
            return true
        }

        val explicit = args.getOrNull(0)?.lowercase()
        val newValue = when (explicit) {
            "on" -> true
            "off" -> false
            else -> !plugin.settingsManager.getSetting(sender, SETTING_KEY)
        }

        plugin.settingsManager.setSetting(sender, SETTING_KEY, newValue)
        applyNightVision(sender, newValue)

        val status = if (newValue) Component.text("enabled", NamedTextColor.GREEN)
        else Component.text("disabled", NamedTextColor.RED)
        plugin.commsManager.send(sender, Component.text("Night vision ", NamedTextColor.GRAY).append(status), CommunicationsManager.Category.SETTINGS)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return listOf("on", "off").filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}
