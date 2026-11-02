package com.liam.joshymc.lifesteal.hearts

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.BedrockUtil
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/lifesteal hearts get|set|add|remove <player> [amount]` (issue #1341). `/lifesteal` itself is
 * the cross-server transfer command (issue #1331), so this is a subcommand of that executor
 * rather than a new command. Admin only ([PERMISSION]) and only on a server that runs Lifesteal;
 * works for offline players the server has seen (by UUID, via the PlayerResolver).
 */
class LifestealHeartsCommand(private val plugin: Joshymc) {

    /** Whether [sender] should see the subcommand at all (permission and a Lifesteal server). */
    fun visibleTo(sender: CommandSender): Boolean =
        sender.hasPermission(PERMISSION) && plugin.lifestealHeartManager.hosted

    /** [args] starts after `hearts`. */
    fun execute(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(PERMISSION)) {
            Msg.noPermission(sender)
            return
        }
        val hearts = plugin.lifestealHeartManager
        if (!hearts.hosted) {
            Msg.error(sender, "Hearts only exist on the Lifesteal server.")
            return
        }
        if (!hearts.active) {
            Msg.error(sender, "Hearts are paused: lifesteal.yml is invalid (see the console and /jgamemode info).")
            return
        }
        val action = args.getOrNull(0)?.lowercase()
        val op = HeartRules.AdminOp.fromId(action)
        if (action != "get" && op == null || args.size < 2) {
            Msg.usage(sender, USAGE)
            return
        }
        val target = plugin.playerResolver.resolve(args[1])
        if (target == null) {
            Msg.playerNotFound(sender, args[1])
            return
        }
        val name = target.name ?: args[1]
        val uuid = target.uniqueId

        if (action == "get") {
            if (args.size != 2) {
                Msg.usage(sender, USAGE)
                return
            }
            val record = try {
                hearts.storedRecord(uuid)
            } catch (e: Exception) {
                Msg.error(sender, "Couldn't read the heart record: ${e.message}")
                return
            }
            if (record == null) {
                Msg.reply(sender, Component.text("$name has no hearts yet (they haven't played Lifesteal here).", NamedTextColor.GRAY))
                return
            }
            val limits = hearts.limits ?: return
            val edition = if (BedrockUtil.isBedrockPlayer(uuid)) " (Bedrock)" else ""
            val stored = record.hearts
            val line = when {
                stored == null -> Component.text("$name$edition: stored hearts are unreadable. Repair with /lifesteal hearts set $name <hearts>.", NamedTextColor.RED)
                stored != limits.clamp(stored) -> Component.text(
                    "$name$edition: $stored hearts stored, applied as ${limits.clamp(stored)} (limits ${limits.minimum}-${limits.maximum}).",
                    NamedTextColor.YELLOW
                )
                else -> Component.text("$name$edition: ", NamedTextColor.GRAY)
                    .append(Component.text("$stored❤", NamedTextColor.RED))
                    .append(Component.text(" (limits ${limits.minimum}-${limits.maximum})", NamedTextColor.GRAY))
            }
            Msg.reply(sender, line)
            return
        }

        if (args.size != 3) {
            Msg.usage(sender, USAGE)
            return
        }
        val amount = HeartRules.parseAmount(args[2])
        if (amount == null) {
            Msg.error(sender, "'${args[2]}' is not a whole number of hearts.")
            return
        }
        val actor = (sender as? Player)?.uniqueId?.toString() ?: "console"
        val result = try {
            hearts.adminChange(uuid, op!!, amount, actor, sender.name)
        } catch (e: Exception) {
            plugin.logger.severe("[Hearts] ${sender.name}: ${op!!.id} $amount for $name failed and was rolled back: ${e.message}")
            Msg.error(sender, "The change couldn't be saved, so nothing changed.")
            return
        }
        when (result) {
            HeartStore.AdminResult.NoRecord -> Msg.error(sender, "$name has no hearts yet (they haven't played Lifesteal here).")
            is HeartStore.AdminResult.Invalid -> Msg.error(sender, result.reason)
            is HeartStore.AdminResult.Unchanged -> Msg.reply(sender, Component.text("$name already has ${result.hearts} hearts; nothing changed.", NamedTextColor.GRAY))
            is HeartStore.AdminResult.Changed -> {
                val limits = hearts.limits
                val note = if (result.clamped && limits != null) " (stopped at the ${limits.minimum}-${limits.maximum} limit)" else ""
                Msg.success(sender, "$name now has ${result.after} hearts (was ${result.before ?: "unreadable"})$note.")
            }
        }
    }

    fun complete(sender: CommandSender, args: List<String>): List<String> {
        if (!visibleTo(sender)) return emptyList()
        return when (args.size) {
            1 -> listOf("get", "set", "add", "remove").filter { it.startsWith(args[0].lowercase()) }
            2 -> plugin.server.onlinePlayers.map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
            else -> emptyList()
        }
    }

    companion object {
        const val PERMISSION = "joshymc.lifesteal.hearts.admin"
        const val USAGE = "/lifesteal hearts <get|set|add|remove> <player> [amount]"
    }
}
