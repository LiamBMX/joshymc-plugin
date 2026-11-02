package com.liam.joshymc.lifesteal.combat

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.BedrockUtil
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `/lifesteal combat status <player> | logs [player] | incident <id> | failures` (issue #1350):
 * read-only staff diagnostics for Lifesteal combat logging, a subcommand of the existing
 * `/lifesteal` executor like `/lifesteal hearts`. [PERMISSION] (op) only, Lifesteal servers only.
 * There is deliberately no command that applies or removes a penalty.
 */
class LifestealCombatCommand(private val plugin: Joshymc) {

    fun visibleTo(sender: CommandSender): Boolean = sender.hasPermission(PERMISSION) && plugin.lifestealCombatManager.hosted

    /** [args] starts after `combat`. */
    fun execute(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(PERMISSION)) {
            Msg.noPermission(sender)
            return
        }
        val combat = plugin.lifestealCombatManager
        if (!combat.hosted) {
            Msg.error(sender, "Lifesteal combat logging only exists on the Lifesteal server.")
            return
        }
        when (args.firstOrNull()?.lowercase()) {
            "status" -> status(sender, args)
            "logs" -> logs(sender, args)
            "incident" -> incident(sender, args)
            "failures" -> failures(sender)
            else -> Msg.usage(sender, USAGE)
        }
    }

    fun complete(sender: CommandSender, args: List<String>): List<String> {
        if (!visibleTo(sender)) return emptyList()
        return when (args.size) {
            1 -> SUBCOMMANDS.filter { it.startsWith(args[0].lowercase()) }
            2 -> if (args[0].lowercase() in setOf("status", "logs")) Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) } else emptyList()
            else -> emptyList()
        }
    }

    private fun status(sender: CommandSender, args: List<String>) {
        if (args.size != 2) return Msg.usage(sender, "/lifesteal combat status <player>")
        val target = plugin.playerResolver.resolve(args[1]) ?: return Msg.playerNotFound(sender, args[1])
        val combat = plugin.lifestealCombatManager
        val name = target.name ?: args[1]
        val edition = if (BedrockUtil.isBedrockPlayer(target.uniqueId)) " (Bedrock)" else ""
        val status = combat.status(target.uniqueId)
        val now = System.currentTimeMillis()
        val expires = status.tagExpiresAt
        if (expires == null) {
            reply(sender, "$name$edition is not in combat.", NamedTextColor.GRAY)
        } else {
            reply(sender, "$name$edition is in combat: ${seconds(expires - now)}s left.", NamedTextColor.RED)
            status.session?.let { s ->
                reply(sender, "  Since ${seconds(now - s.startedAt)}s ago, last hit ${seconds(now - s.lastHitAt)}s ago. Opponents: ${names(s.opponents)}", NamedTextColor.GRAY)
            }
        }
        val counted = try {
            combat.countedSince(target.uniqueId, now - 30L * 86_400_000L)
        } catch (e: Exception) {
            return Msg.error(sender, "Couldn't read combat-log incidents: ${e.message}")
        }
        reply(sender, "  Counted combat logs in the last 30 days: $counted.", NamedTextColor.GRAY)
        if (!combat.active) reply(sender, "  Combat-log recording is off on this server (see the console / lifesteal.yml).", NamedTextColor.YELLOW)
    }

    private fun logs(sender: CommandSender, args: List<String>) {
        if (args.size > 2) return Msg.usage(sender, "/lifesteal combat logs [player]")
        val player = args.getOrNull(1)?.let { plugin.playerResolver.resolve(it)?.uniqueId ?: return Msg.playerNotFound(sender, it) }
        val list = try {
            plugin.lifestealCombatManager.recent(10, player)
        } catch (e: Exception) {
            return Msg.error(sender, "Couldn't read combat-log incidents: ${e.message}")
        }
        if (list.isEmpty()) return reply(sender, "No combat-log incidents recorded.", NamedTextColor.GRAY)
        reply(sender, "Recent combat-log incidents (newest first):", NamedTextColor.GOLD)
        for (i in list) {
            val color = if (i.counted) NamedTextColor.RED else NamedTextColor.GRAY
            reply(sender, " ${i.id.substring(0, 8)}  ${time(i.disconnectedAt)}  ${name(i.player)}  ${i.kind.label}  ${if (i.counted) "COUNTED" else "not counted"}", color)
        }
        reply(sender, "Details: /lifesteal combat incident <id>", NamedTextColor.DARK_GRAY)
    }

    private fun incident(sender: CommandSender, args: List<String>) {
        if (args.size != 2) return Msg.usage(sender, "/lifesteal combat incident <id>")
        val found = try {
            plugin.lifestealCombatManager.incident(args[1])
        } catch (e: Exception) {
            return Msg.error(sender, "Couldn't read combat-log incidents: ${e.message}")
        }
        when {
            found.isEmpty() -> return Msg.error(sender, "No incident starts with '${args[1]}' (use at least 4 characters).")
            found.size > 1 -> return Msg.error(sender, "More than one incident starts with '${args[1]}'; type more of the id.")
        }
        val i = found.first()
        reply(sender, "Combat-log incident ${i.id}", NamedTextColor.GOLD)
        reply(sender, " Player: ${name(i.player)} (${i.player})", NamedTextColor.GRAY)
        reply(sender, " Opponents: ${names(i.opponents)}", NamedTextColor.GRAY)
        reply(sender, " Server: ${i.serverId}", NamedTextColor.GRAY)
        reply(sender, " Combat started ${time(i.combatStartedAt)}, last hit ${time(i.lastHitAt)}, tag until ${time(i.tagExpiresAt)}", NamedTextColor.GRAY)
        reply(sender, " Disconnected ${time(i.disconnectedAt)}: ${i.kind.label} (quit ${i.quitReason ?: "?"}${i.kickCause?.let { ", kick $it" } ?: ""})", NamedTextColor.GRAY)
        reply(sender, " Result: ${i.status.label}; combat-log NPC ${if (i.npcSpawned) "left" else "not left"}; heart transaction ${i.heartTransactionId ?: "none"}", if (i.counted) NamedTextColor.RED else NamedTextColor.GRAY)
    }

    private fun failures(sender: CommandSender) {
        val list = plugin.lifestealCombatManager.failures()
        if (list.isEmpty()) return reply(sender, "No combat-log write failures since the last restart.", NamedTextColor.GREEN)
        reply(sender, "Combat-log write failures since the last restart (newest last, max ${LifestealCombatManager.MAX_FAILURES}):", NamedTextColor.GOLD)
        for (f in list.takeLast(10)) reply(sender, " ${time(f.at)}  ${f.incidentId.substring(0, 8)}  ${name(f.player)}: ${f.message}", NamedTextColor.RED)
    }

    private fun reply(sender: CommandSender, text: String, color: NamedTextColor) = Msg.reply(sender, Component.text(text, color))

    private fun name(uuid: UUID): String = Bukkit.getPlayer(uuid)?.name ?: plugin.playerResolver.lastKnownName(uuid) ?: uuid.toString().substring(0, 8)
    private fun names(list: List<UUID>): String = if (list.isEmpty()) "unknown" else list.joinToString { name(it) }
    private fun seconds(millis: Long): Long = (millis.coerceAtLeast(0) + 999) / 1000
    private fun time(millis: Long): String = FORMAT.format(Instant.ofEpochMilli(millis))

    companion object {
        const val PERMISSION = "joshymc.lifesteal.combat.admin"
        const val USAGE = "/lifesteal combat <status <player>|logs [player]|incident <id>|failures>"
        private val SUBCOMMANDS = listOf("status", "logs", "incident", "failures")
        private val FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("America/New_York"))
    }
}
