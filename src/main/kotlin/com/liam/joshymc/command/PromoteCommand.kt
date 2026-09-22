package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class PromoteCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        private val COOLDOWN_MS = java.time.Duration.ofMinutes(30).toMillis()
        private val USERNAME_REGEX = Regex("^[A-Za-z0-9_.]{1,25}$")
        private const val USAGE = "Usage: /promote live <twitch/tiktok/youtube> <username>"

        fun createTable(plugin: Joshymc) {
            plugin.databaseManager.createTable(
                """
                CREATE TABLE IF NOT EXISTS promote_live_cooldowns (
                    uuid TEXT PRIMARY KEY,
                    last_used_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private enum class Platform(val label: String, val buildUrl: (String) -> String) {
        TWITCH("Twitch", { username -> "https://www.twitch.tv/$username" }),
        TIKTOK("TikTok", { username -> "https://www.tiktok.com/@$username" }),
        YOUTUBE("YouTube", { username -> "https://www.youtube.com/@$username" })
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Only players can use this command.")
            return true
        }

        if (!sender.hasPermission("joshymc.promote.live")) {
            plugin.commsManager.send(sender, Component.text("You do not have permission to use this command.", NamedTextColor.RED))
            return true
        }

        if (args.size != 3 || !args[0].equals("live", ignoreCase = true)) {
            plugin.commsManager.send(sender, Component.text(USAGE, NamedTextColor.RED))
            return true
        }

        val platform = Platform.entries.find { it.name.equals(args[1], ignoreCase = true) }
        if (platform == null) {
            plugin.commsManager.send(sender, Component.text(USAGE, NamedTextColor.RED))
            return true
        }

        val username = args[2]
        if (!USERNAME_REGEX.matches(username)) {
            plugin.commsManager.send(
                sender,
                Component.text("Invalid username — only letters, numbers, underscores, and periods are allowed.", NamedTextColor.RED)
            )
            return true
        }

        val remaining = getCooldownRemaining(sender.uniqueId)
        if (remaining > 0) {
            plugin.commsManager.send(
                sender,
                Component.text("You can promote another livestream in ${formatCooldown(remaining)}.", NamedTextColor.RED)
            )
            return true
        }

        val url = platform.buildUrl(username)
        setCooldown(sender.uniqueId)

        val message = Component.text("[LIVE] ", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
            .append(Component.text("${sender.name} is now live on ${platform.label}!\n", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false))
            .append(Component.text("Click here to watch: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            .append(
                Component.text(url, NamedTextColor.AQUA)
                    .decoration(TextDecoration.UNDERLINED, true)
                    .decoration(TextDecoration.ITALIC, false)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to open ${platform.label}", NamedTextColor.GRAY)))
            )

        plugin.commsManager.broadcast(message)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("live").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> if (args[0].equals("live", ignoreCase = true)) {
                Platform.entries.map { it.name.lowercase() }.filter { it.startsWith(args[1], ignoreCase = true) }
            } else emptyList()
            else -> emptyList()
        }
    }

    private fun getCooldownRemaining(uuid: java.util.UUID): Long {
        val lastUsedAt = plugin.databaseManager.queryFirst(
            "SELECT last_used_at FROM promote_live_cooldowns WHERE uuid = ?",
            uuid.toString()
        ) { rs -> rs.getLong("last_used_at") } ?: return 0L

        val elapsed = System.currentTimeMillis() - lastUsedAt
        val remaining = COOLDOWN_MS - elapsed
        return if (remaining > 0) remaining else 0L
    }

    private fun setCooldown(uuid: java.util.UUID) {
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO promote_live_cooldowns (uuid, last_used_at) VALUES (?, ?)",
            uuid.toString(), System.currentTimeMillis()
        )
    }

    private fun formatCooldown(millis: Long): String {
        val totalSeconds = (millis + 999) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes}m ${seconds}s"
    }
}
