package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.giveItemSafely
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class SpawnerCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val adminSubcommands = listOf("give", "list")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // /spawner (no args) or /spawner shop → open the spawner shop GUI
        if (args.isEmpty() || args[0].equals("shop", ignoreCase = true)) {
            if (sender !is Player) {
                sendMsg(sender, Component.text("Players only.", NamedTextColor.RED))
                return true
            }
            plugin.spawnerManager.openShop(sender)
            return true
        }

        // Other subcommands require admin permission
        // (joshymc.spawner.admin is the legacy singular form, kept for backward compatibility)
        if (!sender.hasPermission("joshymc.spawner.admin") && !sender.hasPermission("joshymc.spawners.admin")) {
            sendMsg(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        when (args[0].lowercase()) {
            "give" -> handleGive(sender, args)
            "list" -> handleList(sender)
            else -> sendUsage(sender)
        }

        return true
    }

    private fun handleGive(sender: CommandSender, args: Array<out String>) {
        // /spawner give <id> [player] [amount]
        if (args.size < 2) {
            sendMsg(sender, Component.text("Usage: /spawner give <id> [player|@a] [amount]", NamedTextColor.RED))
            return
        }

        val typeId = args[1].lowercase()
        val type = plugin.spawnerManager.getType(typeId)
        if (type == null) {
            sendMsg(sender, Component.text("Unknown spawner type: $typeId", NamedTextColor.RED))
            val available = plugin.spawnerManager.getTypes().joinToString(", ") { it.id }
            sendMsg(sender, Component.text("Available: $available", NamedTextColor.GRAY))
            return
        }

        val targetArg = args.getOrNull(2) ?: (sender as? Player)?.name ?: run {
            sendMsg(sender, Component.text("Specify a player when running from console.", NamedTextColor.RED))
            return
        }

        val amount = (args.getOrNull(3)?.toIntOrNull() ?: 1).coerceIn(1, 64)

        val targets: List<Player> = when (targetArg.lowercase()) {
            "@a", "*" -> Bukkit.getOnlinePlayers().toList()
            "@r" -> Bukkit.getOnlinePlayers().toList().shuffled().take(1)
            else -> {
                val p = Bukkit.getPlayer(targetArg)
                if (p == null) {
                    sendMsg(sender, Component.text("Player not found: $targetArg", NamedTextColor.RED))
                    return
                }
                listOf(p)
            }
        }

        if (targets.isEmpty()) {
            sendMsg(sender, Component.text("No matching players online.", NamedTextColor.RED))
            return
        }

        var given = 0
        for (target in targets) {
            val item = plugin.spawnerManager.createSpawnerItem(typeId, amount) ?: continue
            plugin.giveItemSafely(target, item)
            given++
        }

        sendMsg(sender, Component.text("Gave $amount $typeId spawner(s) to $given player(s).", NamedTextColor.GREEN))
    }

    private fun handleList(sender: CommandSender) {
        val types = plugin.spawnerManager.getTypes()
        if (types.isEmpty()) {
            sendMsg(sender, Component.text("No spawner types defined in spawners.yml.", NamedTextColor.GRAY))
            return
        }
        sendMsg(sender, Component.text("Spawner Types (${types.size}):", NamedTextColor.GREEN))
        for (type in types) {
            sender.sendMessage(
                Component.text("  - ${type.id} ", NamedTextColor.GRAY)
                    .append(Component.text("(${type.mob.name})", NamedTextColor.DARK_GRAY))
            )
        }
    }

    private fun sendMsg(sender: CommandSender, message: Component) {
        if (sender is Player) {
            plugin.commsManager.send(sender, message, CommunicationsManager.Category.ADMIN)
        } else {
            sender.sendMessage(message)
        }
    }

    private fun sendUsage(sender: CommandSender) {
        sendMsg(sender, Component.text("Spawner Commands:", NamedTextColor.GREEN))
        val usages = listOf(
            "/spawner shop",
            "/spawner give <id> [player|@a] [amount]",
            "/spawner list"
        )
        for (usage in usages) {
            sender.sendMessage(
                Component.text("  ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(usage, NamedTextColor.GRAY))
            )
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val isAdmin = sender.hasPermission("joshymc.spawner.admin") || sender.hasPermission("joshymc.spawners.admin")
        val allSubs = if (isAdmin) listOf("shop") + adminSubcommands else listOf("shop")

        return when (args.size) {
            1 -> allSubs.filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "give" -> if (isAdmin) plugin.spawnerManager.getTypes().map { it.id }.filter { it.startsWith(args[1].lowercase()) } else emptyList()
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "give" -> if (isAdmin) {
                    val names = Bukkit.getOnlinePlayers().map { it.name } + listOf("@a", "@r")
                    names.filter { it.startsWith(args[2], ignoreCase = true) }
                } else emptyList()
                else -> emptyList()
            }
            4 -> when (args[0].lowercase()) {
                "give" -> if (isAdmin) listOf("1", "4", "8", "16", "32", "64").filter { it.startsWith(args[3]) } else emptyList()
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
