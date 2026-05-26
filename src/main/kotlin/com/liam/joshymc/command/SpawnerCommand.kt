package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID

class SpawnerCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val adminSubcommands = listOf("shop", "give", "list")
    private val playerSubcommands = listOf("trust", "untrust", "trustedlist")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // /spawner (no args) → open shop GUI for everyone
        if (args.isEmpty()) {
            if (sender !is Player) {
                sendMsg(sender, Component.text("Players only.", NamedTextColor.RED))
                return true
            }
            plugin.spawnerManager.openShop(sender)
            return true
        }

        // /spawner shop → also opens shop
        if (args[0].equals("shop", ignoreCase = true)) {
            if (sender !is Player) {
                sendMsg(sender, Component.text("Players only.", NamedTextColor.RED))
                return true
            }
            plugin.spawnerManager.openShop(sender)
            return true
        }

        // Trust subcommands — available to all players
        if (sender is Player) {
            when (args[0].lowercase()) {
                "trust" -> { handleTrust(sender, args); return true }
                "untrust" -> { handleUntrust(sender, args); return true }
                "trustedlist" -> { handleTrustedList(sender); return true }
            }
        }

        // Other subcommands require admin permission
        if (!sender.hasPermission("joshymc.spawner.admin")) {
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
            target.inventory.addItem(item).values.forEach { overflow ->
                target.world.dropItemNaturally(target.location, overflow)
            }
            given++
        }

        sendMsg(sender, Component.text("Gave $amount $typeId spawner(s) to $given player(s).", NamedTextColor.GREEN))
    }

    private fun handleTrust(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            sendMsg(player, Component.text("Usage: /spawner trust <player> [duration]", NamedTextColor.RED))
            sendMsg(player, Component.text("Duration examples: 1h, 30m, 7d (omit for permanent)", NamedTextColor.GRAY))
            return
        }
        val targetName = args[1]
        val target = Bukkit.getOfflinePlayerIfCached(targetName)
            ?: Bukkit.getPlayer(targetName)?.let { Bukkit.getOfflinePlayer(it.uniqueId) }
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline)) {
            sendMsg(player, Component.text("Player not found: $targetName", NamedTextColor.RED))
            return
        }
        if (target.uniqueId == player.uniqueId) {
            sendMsg(player, Component.text("You can't trust yourself.", NamedTextColor.RED))
            return
        }
        val expiresAt: Long = if (args.size >= 3) {
            val seconds = parseDuration(args[2])
            if (seconds == null) {
                sendMsg(player, Component.text("Invalid duration '${args[2]}'. Use e.g. 1h, 30m, 7d.", NamedTextColor.RED))
                return
            }
            System.currentTimeMillis() / 1000L + seconds
        } else {
            -1L
        }
        plugin.spawnerManager.addTrust(player.uniqueId, target.uniqueId, expiresAt)
        val targetDisplayName = target.name ?: targetName
        val durationText = if (expiresAt == -1L) "permanently" else "for ${args[2]}"
        sendMsg(player, Component.text("$targetDisplayName can now access your spawners $durationText.", NamedTextColor.GREEN))
    }

    private fun handleUntrust(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            sendMsg(player, Component.text("Usage: /spawner untrust <player>", NamedTextColor.RED))
            return
        }
        val targetName = args[1]
        val target = Bukkit.getOfflinePlayerIfCached(targetName)
            ?: Bukkit.getPlayer(targetName)?.let { Bukkit.getOfflinePlayer(it.uniqueId) }
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline)) {
            sendMsg(player, Component.text("Player not found: $targetName", NamedTextColor.RED))
            return
        }
        val trusted = plugin.spawnerManager.getTrustedPlayers(player.uniqueId)
        if (!trusted.containsKey(target.uniqueId)) {
            sendMsg(player, Component.text("${target.name ?: targetName} is not trusted.", NamedTextColor.RED))
            return
        }
        plugin.spawnerManager.removeTrust(player.uniqueId, target.uniqueId)
        sendMsg(player, Component.text("${target.name ?: targetName} can no longer access your spawners.", NamedTextColor.YELLOW))
    }

    private fun handleTrustedList(player: Player) {
        val trusted = plugin.spawnerManager.getTrustedPlayers(player.uniqueId)
        if (trusted.isEmpty()) {
            sendMsg(player, Component.text("No players are trusted on your spawners.", NamedTextColor.GRAY))
            return
        }
        val now = System.currentTimeMillis() / 1000L
        sendMsg(player, Component.text("Trusted players (${trusted.size}):", NamedTextColor.GREEN))
        for ((uuid, expiresAt) in trusted) {
            val name = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
            val expiry = if (expiresAt == -1L) "permanent" else {
                val remaining = expiresAt - now
                if (remaining <= 0) "expired" else formatDuration(remaining)
            }
            player.sendMessage(
                Component.text("  - $name ", NamedTextColor.GRAY)
                    .append(Component.text("($expiry)", NamedTextColor.DARK_GRAY))
            )
        }
    }

    /** Parse a duration string like "1h", "30m", "7d", "1d12h". Returns seconds or null if invalid. */
    private fun parseDuration(input: String): Long? {
        val regex = Regex("""(\d+)([smhd])""", RegexOption.IGNORE_CASE)
        val matches = regex.findAll(input).toList()
        if (matches.isEmpty()) return null
        var total = 0L
        for (match in matches) {
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            total += when (match.groupValues[2].lowercase()) {
                "s" -> amount
                "m" -> amount * 60
                "h" -> amount * 3600
                "d" -> amount * 86400
                else -> return null
            }
        }
        return if (total > 0) total else null
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds < 60) return "${seconds}s"
        if (seconds < 3600) return "${seconds / 60}m"
        if (seconds < 86400) return "${seconds / 3600}h"
        return "${seconds / 86400}d"
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
                    .append(Component.text("(${type.mob.name}, ${type.intervalSeconds}s, ${type.minDropsPerInterval}-${type.maxDropsPerInterval} drops)", NamedTextColor.DARK_GRAY))
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
        val isAdmin = sender.hasPermission("joshymc.spawner.admin")
        val allSubs = if (isAdmin) playerSubcommands + adminSubcommands else playerSubcommands

        return when (args.size) {
            1 -> allSubs.filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "give" -> if (isAdmin) plugin.spawnerManager.getTypes().map { it.id }.filter { it.startsWith(args[1].lowercase()) } else emptyList()
                "trust", "untrust" -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "give" -> if (isAdmin) {
                    val names = Bukkit.getOnlinePlayers().map { it.name } + listOf("@a", "@r")
                    names.filter { it.startsWith(args[2], ignoreCase = true) }
                } else emptyList()
                "trust" -> listOf("1h", "6h", "1d", "7d", "30d").filter { it.startsWith(args[2]) }
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
