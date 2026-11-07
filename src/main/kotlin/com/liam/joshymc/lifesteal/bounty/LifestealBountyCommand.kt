package com.liam.joshymc.lifesteal.bounty

import com.liam.joshymc.Joshymc
import com.liam.joshymc.staff.AdminAudit
import com.liam.joshymc.util.Msg
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `/bounty` in Lifesteal (issue #1401), reached through [com.liam.joshymc.command.BountyCommandRouter]:
 *
 *  - `/bounty` opens the active bounty menu.
 *  - `/bounty add <player> <amount>` creates or adds to a bounty (exact player names, never prefixes).
 *  - `/bounty stats` shows the sender's own statistics and active contributions (private).
 *  - `/bounty top [richest|claims] [page]` shows the two hunter leaderboards.
 *  - `/bounty admin ...` (`joshymc.lifesteal.bounty.admin`, op): read-only investigation of bounties,
 *    contributors, payouts, refunds, failures and suspicious kills, plus `retry`, which only runs the
 *    normal idempotent sweep (it can't mint coins or touch escrow).
 */
class LifestealBountyCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val manager: LifestealBountyManager get() = plugin.lifestealBountyManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission(USE_PERMISSION) && !sender.hasPermission(ADMIN_PERMISSION)) {
            Msg.noPermission(sender)
            return true
        }
        when (args.firstOrNull()?.lowercase()) {
            null -> {
                val player = sender as? Player ?: return help(sender).let { true }
                manager.menu.openList(player)
            }
            "add", "place", "set" -> add(sender, args)
            "stats" -> {
                val player = sender as? Player ?: return Msg.playersOnly(sender).let { true }
                sendStats(plugin, manager, player)
            }
            "top", "leaderboard", "leaderboards" -> top(sender, args)
            "admin" -> admin(sender, args.drop(1))
            "help" -> help(sender)
            else -> help(sender)
        }
        return true
    }

    private fun help(sender: CommandSender) {
        val lines = mutableListOf(
            "&6&lBounties &7(Lifesteal)",
            "&e/bounty &7- browse active bounties",
            "&e/bounty add <player> <amount> &7- place or add to a bounty (min ${BountyMoney.format(manager.settings?.minimumContributionCents ?: BountySettings.DEFAULTS.minimumContributionCents)})",
            "&e/bounty stats &7- your bounty statistics",
            "&e/bounty top [richest|claims] &7- bounty hunter leaderboards",
        )
        if (sender.hasPermission(ADMIN_PERMISSION)) lines += "&e/bounty admin &7- staff tools"
        lines.forEach { sender.sendMessage(plugin.commsManager.parseLegacy(it)) }
    }

    // ── /bounty add ─────────────────────────────────────────

    private fun add(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: return Msg.playersOnly(sender)
        if (!player.hasPermission(USE_PERMISSION)) return Msg.noPermission(player)
        if (args.size != 3) return Msg.usage(player, "/bounty add <player> <amount>")
        manager.unavailableReason(player)?.let { return Msg.error(player, it) }
        val minimum = manager.settings?.minimumContributionCents ?: return
        val parsed = BountyMoney.parse(args[2])
        BountyRules.parseProblem(parsed, minimum)?.let { return Msg.error(player, it) }
        val cents = (parsed as BountyMoney.Parsed.Ok).cents
        val name = args[1]
        // Exact name (case-insensitive) only: online, Paper's user cache, then JoshyMC's name table. Never a prefix match.
        val target = if (name.matches(NAME)) plugin.playerResolver.resolve(name) else null
        if (target == null) return Msg.error(player, "No player called $name has played here.")
        if (target.uniqueId == player.uniqueId) return Msg.error(player, BountyStore.Refusal.SELF.message)
        manager.contribute(player, target.uniqueId, target.name ?: name, cents, UUID.randomUUID().toString(), null, "command")
    }

    // ── /bounty top ─────────────────────────────────────────

    private fun top(sender: CommandSender, args: Array<out String>) {
        val board = when (args.getOrNull(1)?.lowercase()) {
            null, "richest", "money", "coins", "earned" -> BountyStore.Board.EARNED
            "claims", "successful", "kills", "most" -> BountyStore.Board.CLAIMS
            else -> return Msg.usage(sender, "/bounty top [richest|claims] [page]")
        }
        val page = (args.getOrNull(2)?.toIntOrNull() ?: 1).coerceAtLeast(1) - 1
        if (sender is Player) return manager.menu.openLeaderboard(sender, board, page)
        if (!manager.processing) return Msg.error(sender, manager.unavailableReason() ?: "Bounties are unavailable.")
        manager.loadBoard(board, page, 10) { data ->
            if (data == null) return@loadBoard Msg.error(sender, "The leaderboard couldn't be loaded.")
            sender.sendMessage(plugin.commsManager.parseLegacy(
                "&6&l${if (board == BountyStore.Board.EARNED) "Richest" else "Most Successful"} Bounty Hunters &7(page ${data.page + 1}/${data.pages})"
            ))
            if (data.entries.isEmpty()) sender.sendMessage(plugin.commsManager.parseLegacy("&7No bounty hunters yet."))
            data.entries.forEach {
                sender.sendMessage(plugin.commsManager.parseLegacy("&e#${it.position} &f${nameOf(plugin, it.uuid)} &7- &6${BountyMoney.format(it.earnedCents)} &7from &f${it.claims} &7claim(s)"))
            }
        }
    }

    // ── /bounty admin ───────────────────────────────────────

    private fun admin(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) return Msg.noPermission(sender)
        if (!manager.processing) return Msg.error(sender, manager.unavailableReason() ?: "Bounties are unavailable on this server.")
        fun line(s: String) = sender.sendMessage(plugin.commsManager.parseLegacy(s))
        when (args.firstOrNull()?.lowercase()) {
            "list" -> {
                val list = manager.activeBounties
                line("&6&lActive bounties &7(${list.size})")
                list.take(30).forEach { line("&f${nameOf(plugin, it.target)} &7- &6${BountyMoney.format(it.totalCents)} &7(${it.contributions} contribution(s), expires in ${BountyRules.formatRemaining(it.expiresAt - System.currentTimeMillis())}) &8${it.id}") }
            }
            "info" -> {
                val name = args.getOrNull(1) ?: return Msg.usage(sender, "/bounty admin info <player>")
                val target = plugin.playerResolver.resolve(name) ?: return Msg.playerNotFound(sender, name)
                manager.read("admin info", { s -> s.bountiesOn(target.uniqueId, 5).map { Triple(it, s.contributionsOf(it.id), s.payoutFor(it.id)) } }) { rows ->
                    if (rows == null) return@read Msg.error(sender, "Couldn't read the bounties.")
                    line("&6&lBounties on ${target.name ?: name} &7(latest ${rows.size})")
                    if (rows.isEmpty()) line("&7None.")
                    for ((b, contributions, payout) in rows) {
                        line("&e${b.state} &6${BountyMoney.format(b.totalCents)} &7created ${time(b.createdAt)} expires ${time(b.expiresAt)} &8${b.id}")
                        contributions.forEach { c ->
                            line("  &7- &f${c.contributorName} &6${BountyMoney.format(c.amountCents)} &7${time(c.createdAt)} via ${c.source}${c.refundState?.let { " &erefund $it" } ?: ""} &8${c.id}")
                        }
                        payout?.let { line("  &7Payout: &f${it.killerName} &6${BountyMoney.format(it.amountCents)} &e${it.state} &7attempts ${it.attempts}${it.lastError?.let { e -> " &c$e" } ?: ""}") }
                    }
                }
                AdminAudit.log(plugin, sender, "BOUNTY_INSPECT", target.uniqueId, target.name ?: name)
            }
            "history" -> {
                val name = args.getOrNull(1) ?: return Msg.usage(sender, "/bounty admin history <player>")
                val target = plugin.playerResolver.resolve(name) ?: return Msg.playerNotFound(sender, name)
                manager.read("admin history", { s -> s.stats(target.uniqueId) to s.payoutsInvolving(target.uniqueId) }) { data ->
                    if (data == null) return@read Msg.error(sender, "Couldn't read the history.")
                    val (stats, payouts) = data
                    line("&6&lBounty history of ${target.name ?: name}")
                    line("&7Claims &f${stats.claims} &7earned &6${BountyMoney.format(stats.earnedCents)} &7contributed &6${BountyMoney.format(stats.contributedCents)} &7(${stats.contributions}) refunded &6${BountyMoney.format(stats.refundedCents)}")
                    payouts.forEach { line("&7${time(it.createdAt)} &f${it.killerName} &7killed &f${it.targetName} &6${BountyMoney.format(it.amountCents)} &e${it.state}") }
                }
                AdminAudit.log(plugin, sender, "BOUNTY_INSPECT", target.uniqueId, target.name ?: name, "history")
            }
            "pending" -> manager.read("admin pending", { s -> s.pendingPayouts() to s.pendingRefunds() }) { data ->
                if (data == null) return@read Msg.error(sender, "Couldn't read pending operations.")
                val (payouts, refunds) = data
                line("&6&lPending payouts &7(${payouts.size})")
                payouts.forEach { line("&f${it.killerName} &6${BountyMoney.format(it.amountCents)} &7for ${it.targetName}, attempts ${it.attempts}${it.lastError?.let { e -> " &c$e" } ?: ""} &8${it.id}") }
                line("&6&lPending refunds &7(${refunds.size})")
                refunds.forEach { line("&f${it.contributorName} &6${BountyMoney.format(it.amountCents)}&7, attempts ${it.refundAttempts}${it.refundError?.let { e -> " &c$e" } ?: ""} &8${it.id}") }
            }
            "suspicious", "audit", "failures" -> {
                val action = if (args[0].equals("suspicious", true)) "SUSPICIOUS" else null
                val page = (args.getOrNull(1)?.toIntOrNull() ?: 1).coerceAtLeast(1) - 1
                val failuresOnly = args[0].equals("failures", true)
                manager.read("admin audit", { s ->
                    if (failuresOnly) s.auditLog("PAYOUT_FAILED", page * 10, 10) + s.auditLog("REFUND_FAILED", page * 10, 10)
                    else s.auditLog(action, page * 10, 10)
                }) { rows ->
                    if (rows == null) return@read Msg.error(sender, "Couldn't read the audit log.")
                    line("&6&lBounty ${args[0].lowercase()} &7(page ${page + 1})")
                    if (rows.isEmpty()) line("&7Nothing recorded.")
                    rows.sortedByDescending { it.id }.forEach {
                        line("&7${time(it.at)} &e${it.action} &f${actorName(it.actor)}${it.subject?.let { s -> " &7-> &f${actorName(s)}" } ?: ""}${it.amountCents?.let { a -> " &6${BountyMoney.format(a)}" } ?: ""} &7${it.detail ?: ""}")
                    }
                }
            }
            "check" -> manager.read("admin check", { s -> s.escrowCents() to s.consistencyProblems() }) { data ->
                if (data == null) return@read Msg.error(sender, "Couldn't run the check.")
                line("&6&lBounty escrow: &6${BountyMoney.format(data.first)} &7(active totals + pending refunds + pending payouts)")
                if (data.second.isEmpty()) line("&aEvery bounty, contribution, payout and refund is consistent.")
                else data.second.take(20).forEach { line("&c$it") }
            }
            "retry" -> {
                AdminAudit.log(plugin, sender, "BOUNTY_RETRY", details = "ran the bounty expiration/refund/payout sweep")
                val actor = (sender as? Player)?.uniqueId?.toString() ?: "CONSOLE"
                manager.read("admin retry audit", { s -> s.audit(System.currentTimeMillis(), "ADMIN_RETRY", actor, null, null, null, sender.name) }) {}
                manager.sweep { summary -> Msg.success(sender, "Bounty sweep: $summary.") }
            }
            else -> {
                line("&6&lBounty staff tools")
                line("&e/bounty admin list &7- active bounties")
                line("&e/bounty admin info <player> &7- bounties on a player, with contributors")
                line("&e/bounty admin history <player> &7- a player's statistics and claims")
                line("&e/bounty admin pending &7- payouts and refunds not yet completed")
                line("&e/bounty admin failures [page] &7- failed payouts and refunds")
                line("&e/bounty admin suspicious [page] &7- repeated kills and shared connections")
                line("&e/bounty admin audit [page] &7- every bounty action")
                line("&e/bounty admin check &7- escrow total and consistency check")
                line("&e/bounty admin retry &7- process due expirations, refunds and payouts now (safe to repeat)")
            }
        }
    }

    private fun actorName(raw: String): String =
        runCatching { UUID.fromString(raw) }.getOrNull()?.let { nameOf(plugin, it) } ?: raw

    // ── Tab completion ──────────────────────────────────────

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        val admin = sender.hasPermission(ADMIN_PERMISSION)
        fun matching(options: List<String>, prefix: String) = options.filter { it.startsWith(prefix, ignoreCase = true) }
        // Only online Lifesteal players' names: never contributors, never anything private.
        fun players(prefix: String) = matching(Bukkit.getOnlinePlayers().filter { manager.isLifesteal(it) }.map { it.name }, prefix)
        return when (args.size) {
            1 -> matching(listOf("add", "stats", "top", "help") + if (admin) listOf("admin") else emptyList(), args[0])
            2 -> when (args[0].lowercase()) {
                "add" -> players(args[1])
                "top" -> matching(listOf("richest", "claims"), args[1])
                "admin" -> if (admin) matching(listOf("list", "info", "history", "pending", "failures", "suspicious", "audit", "check", "retry"), args[1]) else emptyList()
                else -> emptyList()
            }
            3 -> when {
                args[0].equals("add", true) -> matching(listOf("10000", "25000", "50000", "100000"), args[2])
                args[0].equals("admin", true) && admin && (args[1].equals("info", true) || args[1].equals("history", true)) -> players(args[2])
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    companion object {
        const val USE_PERMISSION = "joshymc.lifesteal.bounty.use"
        const val ADMIN_PERMISSION = "joshymc.lifesteal.bounty.admin"

        private val NAME = Regex("^[.*]?[A-Za-z0-9_]{1,16}$")
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

        fun time(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis)) + " UTC"

        fun nameOf(plugin: Joshymc, uuid: UUID): String =
            Bukkit.getPlayer(uuid)?.name ?: plugin.playerResolver.lastKnownName(uuid) ?: uuid.toString().take(8)

        /** The player's own statistics (private): claims, earnings, contributions and their active contributions. */
        fun sendStats(plugin: Joshymc, manager: LifestealBountyManager, player: Player) {
            if (!manager.processing) return Msg.error(player, manager.unavailableReason(player) ?: "Bounties are unavailable.")
            val uuid = player.uniqueId
            manager.loadStats(uuid) { data ->
                val p = Bukkit.getPlayer(uuid) ?: return@loadStats
                if (data == null) return@loadStats Msg.error(p, "Your bounty statistics couldn't be loaded. Please try again.")
                val s = data.stats
                val now = System.currentTimeMillis()
                val lines = mutableListOf(
                    "&6&lYour Bounty Stats",
                    "&7Bounties claimed: &f${s.claims}",
                    "&7Coins earned from bounties: &6${BountyMoney.format(s.earnedCents)}",
                    "&7Coins contributed to bounties: &6${BountyMoney.format(s.contributedCents)} &7(${s.contributions} contribution(s))",
                    "&7Refunded from expired bounties: &6${BountyMoney.format(s.refundedCents)}",
                    "&7Active contributions: &f${data.active.size}" +
                        if (data.active.isNotEmpty()) " &7(${BountyMoney.format(data.active.sumOf { it.amountCents })} held)" else "",
                )
                data.active.take(15).forEach {
                    lines += "  &7- &c${nameOf(plugin, it.target)}&7: you gave &6${BountyMoney.format(it.amountCents)}&7, total &6${BountyMoney.format(it.bountyTotalCents)}&7, expires in &f${BountyRules.formatRemaining(it.expiresAt - now)} &8(${time(it.expiresAt)})"
                }
                if (data.active.size > 15) lines += "  &7... and ${data.active.size - 15} more"
                lines.forEach { line -> p.sendMessage(plugin.commsManager.parseLegacy(line)) }
            }
        }
    }
}
