package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.SkillManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class SkillsCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        // /skills — open GUI
        if (args.isEmpty()) {
            plugin.skillManager.openSkillsMenu(sender)
            return true
        }

        val sub = args[0].lowercase()

        // /skills top [skill]
        if (sub == "top") {
            handleTop(sender, args)
            return true
        }

        // /skills <skill> — detailed info
        val skill = SkillManager.Skill.entries.find { it.name.equals(sub, ignoreCase = true) }
        if (skill == null) {
            plugin.commsManager.send(sender, Component.text("Unknown skill or subcommand '$sub'.", NamedTextColor.RED))
            return true
        }

        showSkillDetail(sender, skill)
        return true
    }

    private fun showSkillDetail(player: Player, skill: SkillManager.Skill) {
        val uuid = player.uniqueId
        val level = plugin.skillManager.getLevel(uuid, skill)
        val xp = plugin.skillManager.getXp(uuid, skill)
        val progress = plugin.skillManager.getProgress(uuid, skill)
        val xpNeeded = plugin.skillManager.getXpForNextLevel(uuid, skill)
        val perks = plugin.skillManager.getSkillPerks(skill)

        val percent = (progress * 100).toInt()
        val filled = (progress * 20).toInt().coerceIn(0, 20)
        val empty = 20 - filled
        val bar = "&a" + "\u2588".repeat(filled) + "&7" + "\u2591".repeat(empty)

        player.sendMessage(Component.empty())
        player.sendMessage(legacy.deserialize("${skill.color}&l\u2550\u2550\u2550 ${skill.displayName} \u2550\u2550\u2550"))
        player.sendMessage(legacy.deserialize("&7Level: ${skill.color}$level &7/ ${SkillManager.MAX_LEVEL}"))

        if (level >= SkillManager.MAX_LEVEL) {
            player.sendMessage(legacy.deserialize("$bar &eMMAX"))
            player.sendMessage(legacy.deserialize("&7Total XP: &f${formatNumber(xp)}"))
        } else {
            player.sendMessage(legacy.deserialize("$bar &7$percent%"))
            player.sendMessage(legacy.deserialize("&7XP to next level: &f${formatNumber(xpNeeded)}"))
            player.sendMessage(legacy.deserialize("&7Total XP: &f${formatNumber(xp)}"))
        }

        player.sendMessage(Component.empty())
        player.sendMessage(legacy.deserialize("&7Perks:"))
        for (perk in perks) {
            val color = if (level >= perk.level) "&a" else "&8"
            val check = if (level >= perk.level) "\u2714" else "\u2716"
            player.sendMessage(legacy.deserialize("$color $check Lv${perk.level}: ${perk.description}"))
        }
        player.sendMessage(Component.empty())
    }

    private fun handleTop(player: Player, args: Array<out String>) {
        val skillName = args.getOrNull(1)?.lowercase()

        if (skillName == null) {
            plugin.commsManager.send(player, Component.text("Usage: /skills top <skill>", NamedTextColor.GRAY))
            return
        }

        val skill = SkillManager.Skill.entries.find { it.name.equals(skillName, ignoreCase = true) }
        if (skill == null) {
            plugin.commsManager.send(player, Component.text("Unknown skill '$skillName'.", NamedTextColor.RED))
            return
        }

        val top = plugin.skillManager.getTopPlayers(skill, 10)

        player.sendMessage(Component.empty())
        player.sendMessage(legacy.deserialize("${skill.color}&l\u2550\u2550\u2550 ${skill.displayName} Leaderboard \u2550\u2550\u2550"))

        if (top.isEmpty()) {
            player.sendMessage(legacy.deserialize("&7No data yet."))
        } else {
            for ((index, entry) in top.withIndex()) {
                val (uuidStr, level, xp) = entry
                val name = try {
                    Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuidStr)).name ?: uuidStr
                } catch (_: Exception) {
                    uuidStr
                }
                val rank = index + 1
                val rankColor = when (rank) {
                    1 -> "&6"
                    2 -> "&f"
                    3 -> "&c"
                    else -> "&7"
                }
                player.sendMessage(legacy.deserialize(
                    "$rankColor#$rank &f$name ${skill.color}Lv$level &7(${formatNumber(xp)} XP)"
                ))
            }
        }
        player.sendMessage(Component.empty())
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (sender !is Player) return emptyList()

        if (args.size == 1) {
            val prefix = args[0].lowercase()
            val options = SkillManager.Skill.entries.map { it.name.lowercase() } + "top"
            return options.filter { it.startsWith(prefix) }
        }

        if (args.size == 2 && args[0].equals("top", ignoreCase = true)) {
            val prefix = args[1].lowercase()
            return SkillManager.Skill.entries.map { it.name.lowercase() }.filter { it.startsWith(prefix) }
        }

        return emptyList()
    }

    private fun formatNumber(value: Long): String = "%,d".format(value)
}
