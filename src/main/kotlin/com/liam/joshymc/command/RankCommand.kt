package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID

/**
 * `/rank` manages exact rank membership: `add`/`remove` only ever touch the
 * one rank named on the command line, so a player can hold any combination
 * of ranks (e.g. a purchasable rank + a staff rank) without one clobbering
 * the other. There is intentionally no generic `set` — that implied a
 * single "current rank" model that doesn't hold once multiple rank
 * categories can coexist.
 *
 * `promote`/`demote` are a special case layered on top: they only ever
 * touch the Trainee → Lead Mod staff ladder ([STAFF_LADDER]), stepping the
 * player one rung at a time while leaving every other rank (paid ranks,
 * Media, unrelated groups) completely untouched.
 */
class RankCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        /** Managed staff ladder, lowest to highest. Only these ranks are touched by promote/demote. */
        private val STAFF_LADDER = listOf("trainee", "helper", "jr_mod", "mod", "lead_mod")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.rank")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            "check" -> handleCheck(sender, args)
            "list" -> handleList(sender)
            "add" -> handleAdd(sender, args)
            "remove" -> handleRemove(sender, args)
            "promote" -> handlePromote(sender, args)
            "demote" -> handleDemote(sender, args)
            else -> showHelp(sender)
        }

        return true
    }

    /**
     * Current managed staff rank held by [uuid], normalized to the single
     * highest rung on [STAFF_LADDER] if the player somehow holds more than
     * one (e.g. leftover from manual `/rank add`).
     */
    private fun currentStaffRank(uuid: UUID): String? {
        val held = plugin.rankManager.getPlayerRankIds(uuid)
        return STAFF_LADDER.lastOrNull { it in held }
    }

    /** Remove every managed staff rank [uuid] currently holds, then add [newRankId] if not null. */
    private fun applyStaffRank(uuid: UUID, newRankId: String?) {
        val held = plugin.rankManager.getPlayerRankIds(uuid)
        for (rankId in STAFF_LADDER) {
            if (rankId in held) plugin.rankManager.removeRank(uuid, rankId)
        }
        if (newRankId != null) plugin.rankManager.addRank(uuid, newRankId)
    }

    private fun handlePromote(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.rank.staff")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return
        }

        val playerName = args.getOrNull(1)
        if (playerName == null) {
            sender.sendMessage(Component.text("Usage: /rank promote <player>", NamedTextColor.RED))
            return
        }

        val target = resolveTarget(playerName)
        val displayName = target.name ?: playerName
        val current = currentStaffRank(target.uniqueId)
        val currentIndex = current?.let { STAFF_LADDER.indexOf(it) } ?: -1

        if (currentIndex == STAFF_LADDER.lastIndex) {
            reply(
                sender,
                Component.text("$displayName is already the highest staff rank managed by this command.", NamedTextColor.RED)
            )
            return
        }

        val newRankId = STAFF_LADDER[currentIndex + 1]
        val newRank = plugin.rankManager.getRank(newRankId)
        if (newRank == null) {
            sender.sendMessage(Component.text("Staff rank '$newRankId' is not configured. Check config.yml ranks.list.", NamedTextColor.RED))
            return
        }

        applyStaffRank(target.uniqueId, newRankId)

        if (sender is Player) {
            plugin.adminManager.logAction(sender, "RANK_PROMOTE", target, "${current ?: "none"} -> $newRankId")
        }

        val tagDisplay = plugin.commsManager.parseLegacy(newRank.displayTag)
        reply(
            sender,
            Component.text("$displayName has been promoted to ", NamedTextColor.GREEN)
                .append(tagDisplay)
                .append(Component.text(".", NamedTextColor.GREEN))
        )

        val onlineTarget = Bukkit.getPlayer(playerName)
        if (onlineTarget != null && onlineTarget != sender) {
            plugin.commsManager.send(
                onlineTarget,
                Component.text("You have been promoted to ", NamedTextColor.GREEN)
                    .append(tagDisplay)
                    .append(Component.text(".", NamedTextColor.GREEN))
            )
        }
    }

    private fun handleDemote(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.rank.staff")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return
        }

        val playerName = args.getOrNull(1)
        val reason = args.drop(2).joinToString(" ").trim()
        if (playerName == null || reason.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /rank demote <player> <reason>", NamedTextColor.RED))
            return
        }

        val target = resolveTarget(playerName)
        val displayName = target.name ?: playerName
        val current = currentStaffRank(target.uniqueId)

        if (current == null) {
            reply(
                sender,
                Component.text("$displayName does not currently hold a staff rank managed by this command.", NamedTextColor.RED)
            )
            return
        }

        val currentIndex = STAFF_LADDER.indexOf(current)
        val newRankId = if (currentIndex == 0) null else STAFF_LADDER[currentIndex - 1]

        applyStaffRank(target.uniqueId, newRankId)

        if (sender is Player) {
            plugin.adminManager.logAction(sender, "RANK_DEMOTE", target, "$current -> ${newRankId ?: "removed"} | Reason: $reason")
        }

        val onlineTarget = Bukkit.getPlayer(playerName)

        if (newRankId == null) {
            reply(sender, Component.text("$displayName has been removed from staff. Reason: $reason", NamedTextColor.YELLOW))
            if (onlineTarget != null && onlineTarget != sender) {
                plugin.commsManager.send(
                    onlineTarget,
                    Component.text("You have been removed from staff. Reason: $reason", NamedTextColor.YELLOW)
                )
            }
            return
        }

        val newRank = plugin.rankManager.getRank(newRankId)
        val tagDisplay = newRank?.let { plugin.commsManager.parseLegacy(it.displayTag) } ?: Component.text(newRankId)
        reply(
            sender,
            Component.text("$displayName has been demoted to ", NamedTextColor.YELLOW)
                .append(tagDisplay)
                .append(Component.text(". Reason: $reason", NamedTextColor.YELLOW))
        )
        if (onlineTarget != null && onlineTarget != sender) {
            plugin.commsManager.send(
                onlineTarget,
                Component.text("You have been demoted to ", NamedTextColor.YELLOW)
                    .append(tagDisplay)
                    .append(Component.text(". Reason: $reason", NamedTextColor.YELLOW))
            )
        }
    }

    private fun handleAdd(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.rank.add")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return
        }

        val playerName = args.getOrNull(1)
        val rankId = args.getOrNull(2)?.lowercase()
        if (playerName == null || rankId == null) {
            sender.sendMessage(Component.text("Usage: /rank add <player> <rank>", NamedTextColor.RED))
            return
        }

        val target = resolveTarget(playerName)
        val displayName = target.name ?: playerName
        val rank = plugin.rankManager.getRank(rankId)
        if (rank == null) {
            sender.sendMessage(Component.text("Unknown rank: $rankId. Use /rank list to see available ranks.", NamedTextColor.RED))
            return
        }

        val tagDisplay = plugin.commsManager.parseLegacy(rank.displayTag)
        val added = plugin.rankManager.addRank(target.uniqueId, rankId)
        if (!added) {
            reply(
                sender,
                Component.text("$displayName already has the ", NamedTextColor.RED)
                    .append(tagDisplay)
                    .append(Component.text(" rank.", NamedTextColor.RED))
            )
            return
        }

        if (sender is Player) {
            plugin.adminManager.logAction(sender, "RANK_ADD", target, rankId)
        }

        reply(
            sender,
            Component.text("Added ", NamedTextColor.GREEN)
                .append(tagDisplay)
                .append(Component.text(" to $displayName.", NamedTextColor.GREEN))
        )

        val onlineTarget = Bukkit.getPlayer(playerName)
        if (onlineTarget != null && onlineTarget != sender) {
            plugin.commsManager.send(
                onlineTarget,
                Component.text("You were given the ", NamedTextColor.GREEN)
                    .append(tagDisplay)
                    .append(Component.text(" rank.", NamedTextColor.GREEN))
            )
        }
    }

    private fun handleRemove(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.rank.remove")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return
        }

        val playerName = args.getOrNull(1)
        val rankId = args.getOrNull(2)?.lowercase()
        if (playerName == null || rankId == null) {
            sender.sendMessage(Component.text("Usage: /rank remove <player> <rank>", NamedTextColor.RED))
            return
        }

        val target = resolveTarget(playerName)
        val displayName = target.name ?: playerName
        val rank = plugin.rankManager.getRank(rankId)
        if (rank == null) {
            sender.sendMessage(Component.text("Unknown rank: $rankId. Use /rank list to see available ranks.", NamedTextColor.RED))
            return
        }

        val tagDisplay = plugin.commsManager.parseLegacy(rank.displayTag)
        val removed = plugin.rankManager.removeRank(target.uniqueId, rankId)
        if (!removed) {
            reply(
                sender,
                Component.text("$displayName does not have the ", NamedTextColor.RED)
                    .append(tagDisplay)
                    .append(Component.text(" rank.", NamedTextColor.RED))
            )
            return
        }

        if (sender is Player) {
            plugin.adminManager.logAction(sender, "RANK_REMOVE", target, rankId)
        }

        reply(
            sender,
            Component.text("Removed ", NamedTextColor.GREEN)
                .append(tagDisplay)
                .append(Component.text(" from $displayName.", NamedTextColor.GREEN))
        )

        val onlineTarget = Bukkit.getPlayer(playerName)
        if (onlineTarget != null && onlineTarget != sender) {
            plugin.commsManager.send(
                onlineTarget,
                Component.text("Your ", NamedTextColor.YELLOW)
                    .append(tagDisplay)
                    .append(Component.text(" rank was removed.", NamedTextColor.YELLOW))
            )
        }
    }

    private fun handleList(sender: CommandSender) {
        val gold = TextColor.color(0xFFD700)
        // Group by category, ordering categories by their lowest-weight rank
        // first so cheaper/earlier ranks (e.g. player ranks) list ahead of
        // higher ones (e.g. staff, then special) without hardcoding names.
        val byCategory = plugin.rankManager.getAllRanks()
            .sortedBy { it.weight }
            .groupBy { it.category }

        val msg = Component.text()
            .append(Component.text("--- Ranks ---", gold).decoration(TextDecoration.BOLD, true))

        for ((category, rankList) in byCategory) {
            msg.append(Component.newline())
                .append(Component.text(category, NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))
            for (rank in rankList.sortedByDescending { it.weight }) {
                msg.append(Component.newline())
                    .append(Component.text("  - ", NamedTextColor.DARK_GRAY))
                    .append(plugin.commsManager.parseLegacy(rank.displayTag))
                    .append(Component.text(" (${rank.id})", NamedTextColor.DARK_GRAY))
            }
        }

        sender.sendMessage(msg.build())
    }

    private fun handleCheck(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.rank.check")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return
        }

        val playerName = args.getOrNull(1) ?: (sender as? Player)?.name
        if (playerName == null) {
            sender.sendMessage(Component.text("Usage: /rank check <player>", NamedTextColor.RED))
            return
        }

        val target = resolveTarget(playerName)
        val displayName = target.name ?: playerName
        val assignedIds = plugin.rankManager.getPlayerRankIds(target.uniqueId)

        if (assignedIds.isEmpty()) {
            reply(
                sender,
                Component.text("$displayName currently has no managed ranks.", NamedTextColor.GRAY)
            )
            return
        }

        val tags = assignedIds.mapNotNull { plugin.rankManager.getRank(it) }.sortedByDescending { it.weight }
        val msg = Component.text()
            .append(Component.text("$displayName's ranks: ", NamedTextColor.GRAY))
        for ((index, rank) in tags.withIndex()) {
            if (index > 0) msg.append(Component.text(", ", NamedTextColor.GRAY))
            msg.append(plugin.commsManager.parseLegacy(rank.displayTag))
        }

        reply(sender, msg.build())
    }

    /** Online players resolve directly; offline lookups fall back to name-based lookup like the rest of the plugin's admin commands. */
    private fun resolveTarget(playerName: String): OfflinePlayer =
        Bukkit.getPlayer(playerName) ?: Bukkit.getOfflinePlayer(playerName)

    /** Sends via commsManager (prefix + chat rules) for players; plain for console. */
    private fun reply(sender: CommandSender, message: Component) {
        if (sender is Player) {
            plugin.commsManager.send(sender, message)
        } else {
            sender.sendMessage(message)
        }
    }

    private fun showHelp(sender: CommandSender) {
        val gold = TextColor.color(0xFFD700)
        val msg = Component.text()
            .append(Component.text("--- Ranks ---", gold).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("/rank add <player> <rank>", NamedTextColor.YELLOW))
            .append(Component.text(" — Add a rank to a player", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/rank remove <player> <rank>", NamedTextColor.YELLOW))
            .append(Component.text(" — Remove a rank from a player", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/rank list", NamedTextColor.YELLOW))
            .append(Component.text(" — Show all managed ranks", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/rank check [player]", NamedTextColor.YELLOW))
            .append(Component.text(" — Check a player's ranks", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/rank promote <player>", NamedTextColor.YELLOW))
            .append(Component.text(" — Advance a player one rung on the staff ladder", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/rank demote <player> <reason>", NamedTextColor.YELLOW))
            .append(Component.text(" — Step a player down the staff ladder", NamedTextColor.GRAY))

        sender.sendMessage(msg.build())
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("check", "list", "add", "remove", "promote", "demote").filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "check", "add", "remove", "promote", "demote" -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "add" -> plugin.rankManager.getRankIds().filter { it.startsWith(args[2].lowercase()) }.toList()
                "remove" -> {
                    val targetUuid = Bukkit.getPlayer(args[1])?.uniqueId ?: Bukkit.getOfflinePlayer(args[1]).uniqueId
                    val assigned = plugin.rankManager.getPlayerRankIds(targetUuid)
                    val pool = assigned.ifEmpty { plugin.rankManager.getRankIds() }
                    pool.filter { it.startsWith(args[2].lowercase()) }.toList()
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
