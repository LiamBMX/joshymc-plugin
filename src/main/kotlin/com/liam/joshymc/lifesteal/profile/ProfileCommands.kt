package com.liam.joshymc.lifesteal.profile

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID

private fun reply(plugin: Joshymc, sender: CommandSender, message: Component) {
    if (sender is Player) plugin.commsManager.send(sender, message) else sender.sendMessage(message)
}

private fun names(prefix: String): List<String> = Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(prefix, ignoreCase = true) }

/** Resolves a typed name to a player this server has seen (never a Mojang lookup). */
private fun resolve(plugin: Joshymc, name: String): Pair<UUID, String>? {
    val player = plugin.playerResolver.resolve(name) ?: return null
    return player.uniqueId to (player.name ?: plugin.playerResolver.lastKnownName(player.uniqueId) ?: name)
}

/**
 * `/profile [player]` (GUI) and `/stats [player]` (chat summary, also from the console), issue #1429.
 * Lifesteal only: registered only where Lifesteal is hosted, and refused outside Lifesteal worlds.
 */
class ProfileCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val manager get() = plugin.lifestealProfileManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission(LifestealProfileManager.PERMISSION_USE)) return true.also { Msg.noPermission(sender) }
        val chat = command.name.equals("stats", ignoreCase = true)
        manager.unavailableReason(sender as? Player)?.let { return true.also { reply(plugin, sender, Component.text(it, NamedTextColor.RED)) } }
        if (!chat && args.firstOrNull()?.lowercase(Locale.ROOT) == "private") {
            val player = sender as? Player ?: return true.also { Msg.playersOnly(sender) }
            return true.also { setPrivate(player, args.getOrNull(1)) }
        }
        val targetName = args.firstOrNull()
        val target: Pair<UUID, String> = when {
            targetName == null -> (sender as? Player)?.let { it.uniqueId to it.name } ?: return true.also { Msg.usage(sender, "/${command.name} <player>") }
            else -> {
                if (sender is Player && !targetName.equals(sender.name, ignoreCase = true) && !sender.hasPermission(LifestealProfileManager.PERMISSION_OTHERS)) {
                    return true.also { Msg.noPermission(sender) }
                }
                resolve(plugin, targetName) ?: return true.also { Msg.playerNotFound(sender, targetName) }
            }
        }
        if (!chat && sender is Player) {
            manager.menus.openProfile(sender, target.first, target.second)
            return true
        }
        summary(sender, target.first, target.second)
        return true
    }

    private fun setPrivate(player: Player, value: String?) {
        val s = manager.settings ?: return
        if (!s.allowPrivate) return reply(plugin, player, Component.text("Private profiles are turned off on this server.", NamedTextColor.RED))
        val on = when (value?.lowercase(Locale.ROOT)) { "on", "true" -> true; "off", "false" -> false; else -> return Msg.usage(player, "/profile private <on|off>") }
        manager.read("profile settings", { manager.store.settings(player.uniqueId) }) { current, problem ->
            if (current == null) return@read reply(plugin, player, Component.text(problem ?: "Couldn't load your settings.", NamedTextColor.RED))
            manager.saveSettings(player, current.copy(private = on)) { ok ->
                reply(plugin, player, if (ok) Component.text("Your profile is now ${if (on) "private" else "public"}.", NamedTextColor.GREEN) else Component.text("Couldn't change that right now.", NamedTextColor.RED))
            }
        }
    }

    private fun summary(sender: CommandSender, uuid: UUID, name: String) {
        manager.loadProfile(uuid, name) { profile, problem ->
            if (profile == null) return@loadProfile reply(plugin, sender, Component.text(problem ?: "No profile.", NamedTextColor.RED))
            val own = sender is Player && sender.uniqueId == uuid
            if (!own && profile.settings.private && manager.settings?.allowPrivate == true && !sender.hasPermission(LifestealProfileManager.PERMISSION_PRIVATE_BYPASS)) {
                return@loadProfile reply(plugin, sender, Component.text("$name's profile is private.", NamedTextColor.RED))
            }
            val season = profile.periods.season
            val life = profile.periods.lifetime
            fun v(values: StatValues?, stat: ProfileStat) = values?.get(stat)?.toString() ?: "-"
            val lines = mutableListOf(
                Component.text("Lifesteal profile: ", NamedTextColor.GOLD).append(Component.text(profile.name, NamedTextColor.YELLOW)),
                Component.text("Hearts: ${profile.hearts ?: "-"}   Team: ${profile.teamName ?: "None"}" + (profile.playerRating?.let { "   Player Elo: $it" } ?: ""), NamedTextColor.GRAY),
                Component.text("Season ${profile.periods.current?.id ?: "-"}: kills ${v(season, ProfileStat.PVP_KILLS)}, deaths ${v(season, ProfileStat.PVP_DEATHS)}, " +
                    "K/D ${season?.let { PvpCounting.kdText(it[ProfileStat.PVP_KILLS], it[ProfileStat.PVP_DEATHS]) } ?: "-"}, best streak ${v(season, ProfileStat.BEST_KILL_STREAK)}, bounties ${v(season, ProfileStat.BOUNTY_CLAIMS)}", NamedTextColor.GRAY),
                Component.text("All time: kills ${life[ProfileStat.PVP_KILLS]}, deaths ${life[ProfileStat.PVP_DEATHS]}, K/D ${PvpCounting.kdText(life[ProfileStat.PVP_KILLS], life[ProfileStat.PVP_DEATHS])}, " +
                    "best streak ${life[ProfileStat.BEST_KILL_STREAK]}, bounties ${life[ProfileStat.BOUNTY_CLAIMS]}, ranked wins ${life[ProfileStat.RANKED_WINS]}", NamedTextColor.GRAY),
                Component.text("Achievements: ${profile.rows.size} tier(s) completed" + (profile.playtimeSeconds?.let { "   Playtime: ${plugin.playtimeManager.formatPlaytime(it)}" } ?: ""), NamedTextColor.GRAY),
            )
            for ((w, values) in profile.periods.previous.take(3)) {
                lines += Component.text("${w.id}: kills ${values[ProfileStat.PVP_KILLS]}, deaths ${values[ProfileStat.PVP_DEATHS]}, best streak ${values[ProfileStat.BEST_KILL_STREAK]}", NamedTextColor.DARK_GRAY)
            }
            lines.forEach { reply(plugin, sender, it) }
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> = when {
        args.size == 1 && sender.hasPermission(LifestealProfileManager.PERMISSION_OTHERS) ->
            names(args[0]) + (if (!command.name.equals("stats", true) && "private".startsWith(args[0], true)) listOf("private") else emptyList())
        args.size == 2 && args[0].equals("private", true) && !command.name.equals("stats", true) -> listOf("on", "off").filter { it.startsWith(args[1], true) }
        else -> emptyList()
    }
}

/** `/achievements` (GUI) and read-only staff diagnostics `/achievements admin status|player <name>|recover`. */
class AchievementsCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val manager get() = plugin.lifestealProfileManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.firstOrNull()?.equals("admin", true) == true) return true.also { admin(sender, args.drop(1)) }
        val player = sender as? Player ?: return true.also { Msg.playersOnly(sender) }
        if (!player.hasPermission(LifestealProfileManager.PERMISSION_ACHIEVEMENTS)) return true.also { Msg.noPermission(player) }
        manager.unavailableReason(player)?.let { return true.also { reply(plugin, player, Component.text(it, NamedTextColor.RED)) } }
        if (manager.settings?.achievementsEnabled != true) return true.also { reply(plugin, player, Component.text("Achievements are turned off right now.", NamedTextColor.RED)) }
        val category = args.firstOrNull()?.let { AchievementCategory.of(it) }
        if (category != null) manager.menus.openCategory(player, category, 0, AchievementRules.Sort.DEFAULT) else manager.menus.openAchievements(player)
        return true
    }

    private fun admin(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(LifestealProfileManager.PERMISSION_ADMIN)) return Msg.noPermission(sender)
        if (!manager.hosted) return reply(plugin, sender, Component.text("Profiles only exist on the Lifesteal server.", NamedTextColor.RED))
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "status" -> {
                manager.statusLines().forEach { reply(plugin, sender, Component.text(it, NamedTextColor.GRAY)) }
                manager.read("achievement status", { manager.store.countByStatus() }) { counts, _ ->
                    reply(plugin, sender, Component.text("Achievement rows: ${counts ?: "unavailable"}", NamedTextColor.GRAY))
                }
            }
            "player" -> {
                val name = args.getOrNull(1) ?: return Msg.usage(sender, "/achievements admin player <name>")
                val (uuid, resolved) = resolve(plugin, name) ?: return Msg.playerNotFound(sender, name)
                manager.read("achievement audit", { manager.store.achievements(uuid) to manager.store.recentAudit(uuid, 15) }) { data, problem ->
                    if (data == null) return@read reply(plugin, sender, Component.text(problem ?: "Unavailable.", NamedTextColor.RED))
                    reply(plugin, sender, Component.text("$resolved: ${data.first.size} achievement row(s) " +
                        data.first.groupingBy { it.status }.eachCount().toString(), NamedTextColor.GOLD))
                    data.second.forEach { reply(plugin, sender, Component.text(it, NamedTextColor.GRAY)) }
                }
            }
            "recover" -> {
                manager.recoverClaims()
                reply(plugin, sender, Component.text("Re-sending interrupted achievement claims (by the same references, never twice).", NamedTextColor.GREEN))
            }
            else -> Msg.usage(sender, "/achievements admin <status|player <name>|recover>")
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val admin = sender.hasPermission(LifestealProfileManager.PERMISSION_ADMIN)
        return when (args.size) {
            1 -> (AchievementCategory.entries.map { it.id } + if (admin) listOf("admin") else emptyList()).filter { it.startsWith(args[0], true) }
            2 -> if (admin && args[0].equals("admin", true)) listOf("status", "player", "recover").filter { it.startsWith(args[1], true) } else emptyList()
            3 -> if (admin && args[0].equals("admin", true) && args[1].equals("player", true)) names(args[2]) else emptyList()
            else -> emptyList()
        }
    }
}

/** `/lstop [category] [season|alltime] [page]`: the Lifesteal leaderboards (GUI for players, chat for the console). */
class LifestealLeaderboardCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val manager get() = plugin.lifestealProfileManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission(LifestealProfileManager.PERMISSION_LEADERBOARDS)) return true.also { Msg.noPermission(sender) }
        manager.unavailableReason(sender as? Player)?.let { return true.also { reply(plugin, sender, Component.text(it, NamedTextColor.RED)) } }
        val s = manager.settings
        if (s == null || !s.boardsEnabled) return true.also { reply(plugin, sender, Component.text("Leaderboards are turned off.", NamedTextColor.RED)) }
        val categories = manager.boardCategories()
        val category = args.getOrNull(0)?.let { BoardCategory.of(it)?.takeIf { c -> c in categories } ?: return true.also { Msg.usage(sender, "/$label [${categories.joinToString("|") { c -> c.id }}] [season|alltime] [page]") } }
        val period = args.getOrNull(1)?.let { BoardPeriod.of(it) } ?: BoardPeriod.SEASON
        val page = (args.getOrNull(2)?.toIntOrNull() ?: 1).coerceAtLeast(1) - 1
        if (sender is Player) {
            manager.menus.openLeaderboards(sender, category, period, page)
            return true
        }
        val cat = category ?: categories.firstOrNull { !it.ranked } ?: return true
        if (cat.ranked) return true.also { reply(plugin, sender, Component.text("Use /ranked top for the Elo leaderboards.", NamedTextColor.YELLOW)) }
        val boards = manager.boards ?: return true.also {
            reply(plugin, sender, Component.text("The leaderboards are still being built; try again in a moment.", NamedTextColor.YELLOW))
            manager.rebuildBoards()
        }
        val list = boards.lists[cat to period].orEmpty()
        reply(plugin, sender, Component.text("${cat.label} - ${period.label} (${list.size} players)", NamedTextColor.GOLD))
        for (e in Leaderboards.page(list, page, 10)) {
            val name = plugin.playerResolver.lastKnownName(e.uuid) ?: e.uuid.toString().take(8)
            val value = if (cat == BoardCategory.KD) "%.2f".format(e.value / 100.0) else e.value.toString()
            reply(plugin, sender, Component.text("#${e.position} $name - $value", NamedTextColor.GRAY))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> = when (args.size) {
        1 -> manager.boardCategories().map { it.id }.filter { it.startsWith(args[0], true) }
        2 -> listOf("season", "alltime").filter { it.startsWith(args[1], true) }
        else -> emptyList()
    }
}
