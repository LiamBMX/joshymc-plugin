package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.DedicatedServerFeatures
import com.liam.joshymc.gamemode.GamemodeLockManager
import com.liam.joshymc.lifesteal.LifestealConfigManager
import com.liam.joshymc.gamemode.GamemodeLockManager.ChangeResult
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * `/jgamemode lock|unlock <survival|lifesteal> confirm` (issue #1289). Each subcommand has its
 * own permission (`joshymc.jgm.lock` / `joshymc.jgm.unlock`, default false); a sender only sees
 * and can run the ones they hold. Nothing changes unless the last argument is literally `confirm`.
 * `/jgamemode` and `/jgamemode info` show the locks and this server's network identity
 * (issue #1323) to anyone holding either permission.
 */
class JGamemodeCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private enum class Sub(val id: String, val permission: String, val lock: Boolean) {
        LOCK("lock", "joshymc.jgm.lock", true),
        UNLOCK("unlock", "joshymc.jgm.unlock", false),
    }

    private fun allowedSubs(sender: CommandSender) = Sub.entries.filter { sender.hasPermission(it.permission) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val allowed = allowedSubs(sender)
        if (allowed.isEmpty()) {
            Msg.noPermission(sender)
            return true
        }

        if (args.getOrNull(0).equals("info", ignoreCase = true)) {
            showStatus(sender, allowed)
            return true
        }

        val sub = args.getOrNull(0)?.let { raw -> Sub.entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } }
        if (sub == null) {
            showStatus(sender, allowed)
            return true
        }
        if (sub !in allowed) {
            Msg.noPermission(sender)
            return true
        }

        val rawGamemode = args.getOrNull(1)
        if (rawGamemode == null) {
            Msg.usage(sender, "/jgamemode ${sub.id} <survival|lifesteal> confirm")
            return true
        }
        val gamemode = ServerGamemode.fromId(rawGamemode)
        if (gamemode == null) {
            Msg.error(sender, "Unknown gamemode: $rawGamemode. Use survival or lifesteal.")
            return true
        }

        val locks = plugin.gamemodeLockManager
        if (args.getOrNull(2) != "confirm" || args.size > 3) {
            val effect = if (sub.lock) {
                "Locking ${gamemode.displayName} restricts ${gamemode.displayName} commands and features to ${gamemode.displayName}."
            } else {
                "Unlocking ${gamemode.displayName} lifts that restriction."
            }
            Msg.reply(
                sender,
                Component.text("$effect Nothing was changed. To continue, type ", NamedTextColor.YELLOW)
                    .append(Component.text("/jgamemode ${sub.id} ${gamemode.id} confirm", NamedTextColor.WHITE))
            )
            return true
        }

        val verb = if (sub.lock) "locked" else "unlocked"
        when (locks.setLocked(gamemode, sub.lock, sender)) {
            ChangeResult.CHANGED -> Msg.success(
                sender,
                if (sub.lock) "${gamemode.displayName} is now locked. ${gamemode.displayName} commands and features only work on ${gamemode.displayName}."
                else "${gamemode.displayName} is now unlocked. Its isolation restriction is lifted."
            )
            ChangeResult.ALREADY -> Msg.error(sender, "${gamemode.displayName} is already $verb.")
            ChangeResult.FAILED -> Msg.error(sender, "Couldn't save the lock state, so nothing was changed. Check the console.")
        }
        if (!sub.lock && locks.isImplied(gamemode)) {
            Msg.error(
                sender,
                "${gamemode.displayName} stays isolated anyway: this dedicated ${locks.serverGamemode.displayName} server doesn't run it " +
                    "(gamemode-lock.server-gamemode / gamemode-lock.worlds in config.yml, restart to change)."
            )
        }
        return true
    }

    private fun showStatus(sender: CommandSender, allowed: List<Sub>) {
        val locks = plugin.gamemodeLockManager
        Msg.reply(
            sender,
            Component.text("Gamemode locks ", NamedTextColor.GOLD)
                .append(Component.text("(this server: ${locks.serverGamemode.displayName})", NamedTextColor.GRAY))
        )
        for (gamemode in ServerGamemode.entries) {
            var line = GamemodeLockManager.statusLine(gamemode, locks.isLocked(gamemode))
            if (locks.isImplied(gamemode)) line = line.append(Component.text(" (dedicated server, automatic)", NamedTextColor.GRAY))
            sender.sendMessage(line)
        }
        val network = plugin.networkIdentityManager
        sender.sendMessage(infoLine("Server ID", Component.text(network.serverId, NamedTextColor.WHITE)))
        var gamemodeValue = Component.text(locks.serverGamemode.displayName, NamedTextColor.WHITE)
        if (!network.gamemodeValid) {
            gamemodeValue = gamemodeValue.append(
                Component.text(" (gamemode-lock.server-gamemode is invalid, see the console)", NamedTextColor.RED)
            )
        }
        sender.sendMessage(infoLine("Server gamemode", gamemodeValue))
        sender.sendMessage(
            infoLine(
                "Primary network instance",
                if (network.isPrimary) Component.text("yes", NamedTextColor.GREEN) else Component.text("no", NamedTextColor.RED)
            )
        )
        showBootstrap(sender)
        for (sub in allowed) {
            sender.sendMessage(
                Component.text("/jgamemode ${sub.id} <survival|lifesteal> confirm", NamedTextColor.GRAY)
            )
        }
    }

    /** Issue #1336: dedicated-server state, Lifesteal settings and the essential services. No secrets. */
    private fun showBootstrap(sender: CommandSender) {
        val network = plugin.networkIdentityManager
        val isolated = network.isolatedGamemodes
        if (ServerGamemode.SURVIVAL in isolated) {
            val off = DedicatedServerFeatures.SURVIVAL_ONLY.keys
            sender.sendMessage(
                infoLine("Survival-only startup features kept off", Component.text("${off.size}", NamedTextColor.WHITE))
                    .hoverEvent(Component.text(off.joinToString(", "), NamedTextColor.GRAY))
            )
        }

        val lifesteal = plugin.lifestealConfigManager
        val lifestealValue = when (lifesteal.status) {
            LifestealConfigManager.Status.READY -> {
                val s = lifesteal.settings!!
                Component.text("loaded", NamedTextColor.GREEN).append(
                    Component.text(
                        " (hearts ${s.hearts.starting}, ${s.hearts.minimum}-${s.hearts.maximum}; teams up to ${s.teams.maxSize}; " +
                            "${s.seasons.lengthMonths}-month seasons)",
                        NamedTextColor.GRAY
                    )
                )
            }
            LifestealConfigManager.Status.INVALID -> Component.text(
                "invalid (${lifesteal.problems.count { it.severe }} error(s), see the console)", NamedTextColor.RED
            )
            LifestealConfigManager.Status.NOT_HOSTED -> Component.text("not used on this server", NamedTextColor.GRAY)
        }
        sender.sendMessage(infoLine("Lifesteal settings (lifesteal.yml)", lifestealValue))

        sender.sendMessage(
            infoLine(
                "Database",
                if (plugin.databaseManager.isOpen) Component.text("open (local to this server)", NamedTextColor.GREEN)
                else Component.text("closed", NamedTextColor.RED)
            )
        )
        val transfers = plugin.transferManager.settings
        sender.sendMessage(
            infoLine(
                "Network transfers",
                if (transfers.active) Component.text("on (Velocity)", NamedTextColor.GREEN)
                else Component.text("off (standalone)", NamedTextColor.GRAY)
            )
        )
        sender.sendMessage(
            infoLine(
                "Discord bot",
                if (network.discordBotAllowed) Component.text("runs on this server", NamedTextColor.WHITE)
                else Component.text("left to the primary server", NamedTextColor.GRAY)
            )
        )
    }

    private fun infoLine(label: String, value: Component): Component =
        Component.text("$label: ", NamedTextColor.GRAY).append(value)

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        val allowed = allowedSubs(sender)
        if (allowed.isEmpty()) return emptyList()
        val options = when (args.size) {
            1 -> listOf("info") + allowed.map { it.id }
            2 -> if (allowed.any { it.id.equals(args[0], ignoreCase = true) }) ServerGamemode.entries.map { it.id } else emptyList()
            3 -> if (allowed.any { it.id.equals(args[0], ignoreCase = true) } && ServerGamemode.fromId(args[1]) != null) listOf("confirm") else emptyList()
            else -> emptyList()
        }
        return options.filter { it.startsWith(args.last(), ignoreCase = true) }
    }
}
