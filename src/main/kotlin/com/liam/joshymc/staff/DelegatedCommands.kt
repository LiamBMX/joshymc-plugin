package com.liam.joshymc.staff

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.PluginCommand
import org.bukkit.entity.Player
import org.bukkit.permissions.PermissionDefault

/**
 * Bukkit side of [DelegatedCommandRules] (issue #1353): resolves the typed label through the
 * server's command map (so aliases and `plugin:` names land on the real command) and reads the
 * resolved command's declared permission defaults. `/sudo`, NPC clicks and portal `command`
 * actions call [problem] before `performCommand`, and the NPC/portal setters call it before
 * storing a command.
 */
object DelegatedCommands {

    fun decide(commandLine: String): Pair<DelegatedCommandRules.Decision, String> {
        val line = commandLine.trim().removePrefix("/")
        val typed = line.substringBefore(' ').lowercase()
        val resolved = try { Bukkit.getCommandMap().getCommand(typed) } catch (_: Exception) { null }
        val nodes = resolved?.permission
            ?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        // JoshyMC's own staff commands are named in PRIVILEGED; its player features may use
        // `default: op` nodes granted through LuckPerms (`/pv`), so only judge others' by default.
        val ownCommand = (resolved as? PluginCommand)?.plugin is Joshymc
        val defaults = if (ownCommand) emptyList() else nodes.map { node ->
            when (Bukkit.getPluginManager().getPermission(node)?.default) {
                PermissionDefault.TRUE -> DelegatedCommandRules.NodeDefault.TRUE
                PermissionDefault.NOT_OP -> DelegatedCommandRules.NodeDefault.NOT_OP
                PermissionDefault.OP -> DelegatedCommandRules.NodeDefault.OP
                PermissionDefault.FALSE -> DelegatedCommandRules.NodeDefault.FALSE
                null -> DelegatedCommandRules.NodeDefault.UNREGISTERED
            }
        }
        val decision = DelegatedCommandRules.decide(
            line,
            canonical = resolved?.name,
            staffCommand = DelegatedCommandRules.staffCommand(defaults),
        )
        return decision to DelegatedCommandRules.label(line)
    }

    /** Null when [commandLine] may run in [context], else the refusal message. */
    fun problem(commandLine: String, context: DelegatedCommandRules.Context): String? {
        val (decision, label) = decide(commandLine)
        return decision.message(label, context)
    }

    /**
     * Run-time check for a stored NPC/portal command about to run as [player]. A refusal is
     * logged to the console (commands stored before issue #1353 may now be refused) and the
     * player gets a short notice; returns true when the command may run.
     */
    fun allowRun(plugin: Joshymc, player: Player, commandLine: String, context: DelegatedCommandRules.Context, source: String): Boolean {
        val problem = problem(commandLine, context) ?: return true
        plugin.logger.warning("[DelegatedCommands] Refused $source command '/${commandLine.trim().removePrefix("/")}' for ${player.name}: $problem")
        plugin.commsManager.send(player, Component.text("This can't run that command.", NamedTextColor.RED))
        return false
    }
}
