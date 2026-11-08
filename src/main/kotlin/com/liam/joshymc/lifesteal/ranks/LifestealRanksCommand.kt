package com.liam.joshymc.lifesteal.ranks

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.ranks.LifestealRankManager.Outcome
import com.liam.joshymc.staff.AdminAudit
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID

/**
 * `/lsranks` (issue #1428, Lifesteal only; only registered where Lifesteal is hosted).
 *
 *  - `/lsranks`: the rank GUI. `/lsranks info [player]` (others: `joshymc.lifesteal.ranks.view.others`).
 *  - `/lsranks claim <rank>`: claim a progression rank whose requirements are met.
 *  - `/lsranks admin grant|revoke <player> <rank> [reason]`, `history <player>`, `pending`, `failed`,
 *    `retry <change-id>`, `cancel <change-id> <reason>`, `reload`, `problems`
 *    (`joshymc.lifesteal.ranks.admin`, op). Grants/revokes respect staff-rank protection (never
 *    yourself, only players strictly below you; console always) and every action is audited
 *    (`lifesteal_rank_audit` and `/admin logs`).
 */
class LifestealRanksCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val manager get() = plugin.lifestealRankManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!manager.hosted) {
            Msg.error(sender, "Lifesteal ranks only exist on the Lifesteal server.")
            return true
        }
        when (args.getOrNull(0)?.lowercase()) {
            null -> openMenu(sender)
            "info" -> info(sender, args.getOrNull(1))
            "claim" -> claim(sender, args.getOrNull(1))
            "admin" -> admin(sender, args.drop(1))
            else -> Msg.usage(sender, "/$label [info [player]|claim <rank>]")
        }
        return true
    }

    private fun openMenu(sender: CommandSender) {
        if (sender !is Player) return Msg.playersOnly(sender)
        if (!manager.isLifesteal(sender)) return Msg.error(sender, "Lifesteal ranks can only be viewed in Lifesteal.")
        if (manager.settings == null) return Msg.error(sender, "Lifesteal ranks are unavailable right now (server configuration). Please tell staff.")
        manager.menu.open(sender)
    }

    private fun claim(sender: CommandSender, rankId: String?) {
        if (sender !is Player) return Msg.playersOnly(sender)
        if (rankId == null) return Msg.usage(sender, "/lsranks claim <rank>")
        manager.claimProgression(sender, rankId.lowercase())
    }

    private fun info(sender: CommandSender, targetName: String?) {
        val settings = manager.settings ?: return Msg.error(sender, "Lifesteal ranks are unavailable right now (server configuration).")
        val target: Pair<UUID, String> = if (targetName == null) {
            if (sender !is Player) return Msg.usage(sender, "/lsranks info <player>")
            sender.uniqueId to sender.name
        } else {
            if (!sender.hasPermission(VIEW_OTHERS)) return Msg.noPermission(sender)
            resolve(targetName) ?: return Msg.playerNotFound(sender, targetName)
        }
        val owned = manager.ownedRanks(target.first)
        val current = LifestealRankRules.currentRank(settings, owned)
        Msg.reply(sender, Component.text("${target.second}'s Lifesteal rank: ", NamedTextColor.GRAY)
            .append(current?.let { Component.text(it.displayName, TextColor.fromHexString(it.color) ?: NamedTextColor.WHITE) } ?: Component.text("none", NamedTextColor.WHITE)))
        if (owned.isNotEmpty()) {
            sender.sendMessage(Component.text("  Owned: ${owned.sorted().joinToString { settings.rank(it)?.displayName ?: "$it (not in config)" }}", NamedTextColor.GRAY))
        }
        val perks = LifestealRankRules.perks(settings, owned)
        if (perks.isNotEmpty()) sender.sendMessage(Component.text("  Perks: ${perks.entries.joinToString { "${it.key.label} ${it.value}" }}", NamedTextColor.GRAY))
        manager.store.pendingFor(target.first)?.let {
            sender.sendMessage(Component.text("  Pending: ${it.action.name.lowercase()} ${it.rankId} (change ${it.id.take(8)}, ${it.attempts} attempt(s))", NamedTextColor.YELLOW))
        }
    }

    // ── Admin ───────────────────────────────────────────────────────────

    private fun admin(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(ADMIN)) return Msg.noPermission(sender)
        when (args.getOrNull(0)?.lowercase()) {
            "grant" -> change(sender, args, grant = true)
            "revoke" -> change(sender, args, grant = false)
            "history" -> history(sender, args.getOrNull(1))
            "pending" -> list(sender, manager.store.pending(20), "Pending rank changes")
            "failed" -> list(sender, manager.store.failed(20), "Failed rank changes")
            "retry" -> retry(sender, args.getOrNull(1))
            "cancel" -> cancel(sender, args.getOrNull(1), args.drop(2).joinToString(" "))
            "reload" -> {
                manager.reload()
                AdminAudit.log(plugin, sender, "LIFESTEAL_RANKS_RELOAD", details = "status=${manager.status.name}")
                if (manager.settings != null) Msg.success(sender, "Reloaded ${LifestealRankConfig.FILE}: ${manager.settings!!.ranks.size} rank(s).")
                else Msg.error(sender, "${LifestealRankConfig.FILE} is invalid; rank perks, kits and grants are paused. See /lsranks admin problems.")
            }
            "problems" -> {
                if (manager.problems.isEmpty()) Msg.success(sender, "${LifestealRankConfig.FILE} has no problems (${manager.status.label}).")
                for (p in manager.problems) sender.sendMessage(Component.text((if (p.severe) "ERROR " else "WARN ") + p.message, if (p.severe) NamedTextColor.RED else NamedTextColor.YELLOW))
            }
            else -> Msg.usage(sender, "/lsranks admin <grant|revoke|history|pending|failed|retry|cancel|reload|problems>")
        }
    }

    private fun change(sender: CommandSender, args: List<String>, grant: Boolean) {
        val verb = if (grant) "grant" else "revoke"
        val name = args.getOrNull(1)
        val rankId = args.getOrNull(2)?.lowercase()
        if (name == null || rankId == null) return Msg.usage(sender, "/lsranks admin $verb <player> <rank> [reason]")
        val reason = args.drop(3).joinToString(" ").take(120).ifBlank { null }
        val target = resolve(name) ?: return Msg.playerNotFound(sender, name)
        val (uuid, targetName) = target
        if (sender is Player && sender.uniqueId == uuid) return Msg.error(sender, "You can't change your own Lifesteal rank.")
        plugin.staffRankManager.protectionProblem(sender, uuid)?.let { return Msg.error(sender, it) }
        val settings = manager.settings ?: return Msg.error(sender, "${LifestealRankConfig.FILE} is invalid; fix it first.")
        val rank = settings.rank(rankId) ?: return Msg.error(sender, "Unknown Lifesteal rank '$rankId'. Ranks: ${settings.ranks.joinToString { it.id }}.")
        val actor = actorOf(sender)
        AdminAudit.log(plugin, sender, if (grant) "LIFESTEAL_RANK_GRANT" else "LIFESTEAL_RANK_REVOKE", uuid, targetName, "rank=${rank.id}" + (reason?.let { " reason=$it" } ?: ""))
        val onResult: (Outcome) -> Unit = { outcome -> report(sender, outcome, "${rank.displayName} for $targetName", verb) }
        if (grant) {
            manager.requestGrant(uuid, targetName, rank.id, LifestealRankRules.Source.ADMIN, null, actor, onResult)
        } else {
            manager.requestRevoke(uuid, targetName, rank.id, actor, onResult)
        }
    }

    private fun report(sender: CommandSender, outcome: Outcome, what: String, verb: String) {
        when (outcome) {
            is Outcome.Completed -> Msg.success(sender, "${verb.replaceFirstChar { it.uppercase() }}ed $what (LuckPerms saved).")
            is Outcome.Refused -> Msg.error(sender, when (outcome.decision) {
                LifestealRankRules.Decision.UNKNOWN_RANK -> if (verb == "revoke") "They don't own that rank." else outcome.decision.message
                LifestealRankRules.Decision.ALREADY_OWNED -> "They already own that rank."
                LifestealRankRules.Decision.HIGHER_OWNED -> "They already have a higher rank."
                else -> outcome.decision.message
            })
            is Outcome.Duplicate -> Msg.error(sender, "That change already exists (${outcome.existing.id.take(8)}, ${outcome.existing.status}).")
            is Outcome.Pending -> Msg.reply(sender, Component.text("Recorded the $verb of $what (change ${outcome.op.id.take(8)}); LuckPerms hasn't saved it yet (${outcome.reason}). It is retried automatically.", NamedTextColor.YELLOW))
            is Outcome.Failed -> Msg.error(sender, "Couldn't $verb $what: ${outcome.reason}." + (outcome.op?.let { " See /lsranks admin failed (${it.id.take(8)})." } ?: ""))
        }
    }

    private fun history(sender: CommandSender, name: String?) {
        if (name == null) return Msg.usage(sender, "/lsranks admin history <player>")
        val (uuid, targetName) = resolve(name) ?: return Msg.playerNotFound(sender, name)
        val rows = manager.store.history(uuid, 15)
        if (rows.isEmpty()) return Msg.reply(sender, Component.text("$targetName has no Lifesteal rank history.", NamedTextColor.GRAY))
        Msg.reply(sender, Component.text("Lifesteal rank history of $targetName (newest first):", NamedTextColor.GOLD))
        for (row in rows) {
            sender.sendMessage(Component.text("  ${time(row.at)} ${row.action} ${row.rankId} by ${row.actor}" + (row.detail?.let { " ($it)" } ?: ""), NamedTextColor.GRAY))
        }
    }

    private fun list(sender: CommandSender, rows: List<LifestealRankStore.Op>, title: String) {
        if (rows.isEmpty()) return Msg.reply(sender, Component.text("$title: none.", NamedTextColor.GRAY))
        Msg.reply(sender, Component.text("$title:", NamedTextColor.GOLD))
        for (op in rows) {
            sender.sendMessage(Component.text(
                "  ${op.id} ${op.action.name.lowercase()} ${op.rankId} for ${op.playerName} (${op.source}, ${op.attempts} attempt(s))" +
                    (op.lastError?.let { ": $it" } ?: ""),
                NamedTextColor.GRAY
            ))
        }
    }

    private fun retry(sender: CommandSender, id: String?) {
        if (id == null) return Msg.usage(sender, "/lsranks admin retry <change-id>")
        val op = manager.store.get(id) ?: return Msg.error(sender, "No rank change with id $id.")
        AdminAudit.log(plugin, sender, "LIFESTEAL_RANK_RETRY", op.uuid, op.playerName, "change=${op.id} rank=${op.rankId}")
        val started = manager.retry(op.id, actorOf(sender)) { outcome -> report(sender, outcome, "${op.rankId} for ${op.playerName}", op.action.name.lowercase()) }
        if (!started) Msg.error(sender, "Only a FAILED change can be retried, and only while that player has no other pending change.")
    }

    private fun cancel(sender: CommandSender, id: String?, reason: String) {
        if (id == null || reason.isBlank()) return Msg.usage(sender, "/lsranks admin cancel <change-id> <reason>")
        val op = manager.store.get(id) ?: return Msg.error(sender, "No rank change with id $id.")
        if (manager.store.cancel(op.id, actorOf(sender), reason)) {
            AdminAudit.log(plugin, sender, "LIFESTEAL_RANK_CANCEL", op.uuid, op.playerName, "change=${op.id} rank=${op.rankId} reason=$reason")
            Msg.success(sender, "Cancelled change ${op.id.take(8)}. Nothing more will be sent to LuckPerms for it.")
        } else {
            Msg.error(sender, "Only a PENDING or FAILED change can be cancelled.")
        }
    }

    private fun resolve(name: String): Pair<UUID, String>? {
        val online = plugin.server.getPlayerExact(name)
        if (online != null) return online.uniqueId to online.name
        val offline = plugin.playerResolver.resolve(name) ?: return null
        return offline.uniqueId to (offline.name ?: plugin.playerResolver.lastKnownName(offline.uniqueId) ?: name)
    }

    private fun actorOf(sender: CommandSender): String = if (sender is Player) "staff:${sender.uniqueId}" else "console:${sender.name}"

    private fun time(at: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(at)) + "Z"

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val ranks = manager.settings?.ranks?.map { it.id }.orEmpty()
        val admin = sender.hasPermission(ADMIN)
        val options: List<String> = when (args.size) {
            1 -> listOf("info", "claim") + if (admin) listOf("admin") else emptyList()
            2 -> when (args[0].lowercase()) {
                "claim" -> ranks
                "info" -> if (sender.hasPermission(VIEW_OTHERS)) plugin.server.onlinePlayers.map { it.name } else emptyList()
                "admin" -> if (admin) listOf("grant", "revoke", "history", "pending", "failed", "retry", "cancel", "reload", "problems") else emptyList()
                else -> emptyList()
            }
            3 -> if (admin && args[0].equals("admin", true) && args[1].lowercase() in setOf("grant", "revoke", "history")) plugin.server.onlinePlayers.map { it.name } else emptyList()
            4 -> if (admin && args[0].equals("admin", true) && args[1].lowercase() in setOf("grant", "revoke")) ranks else emptyList()
            else -> emptyList()
        }
        val last = args.lastOrNull().orEmpty()
        return options.filter { it.startsWith(last, ignoreCase = true) }
    }

    companion object {
        const val ADMIN = "joshymc.lifesteal.ranks.admin"
        const val VIEW_OTHERS = "joshymc.lifesteal.ranks.view.others"
    }
}
