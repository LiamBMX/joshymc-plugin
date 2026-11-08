package com.liam.joshymc.lifesteal.bank

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.bounty.BountyMoney
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
 * `/bank` on the Lifesteal server (issue #1427):
 *
 *  - `/bank` opens the Protected Bank menu.
 *  - `/bank balance` shows the wallet and the bank.
 *  - `/bank deposit <amount|all>` / `/bank withdraw <amount|all>`.
 *  - `/bank admin ...` (`joshymc.lifesteal.bank.admin`, op): read-only investigation (balances,
 *    history, PvP transfers, suspicious kills, failed transactions, totals) and `review` of
 *    failed rows. `add`/`remove` need the separate `joshymc.lifesteal.bank.admin.adjust` (op),
 *    are capped per adjustment, need a reason, respect staff protection and are audited.
 *
 * Every transfer goes through [LifestealBankManager.transfer], which re-checks combat, ranked,
 * events and freezes when it runs, so aliases, `/joshymc:bank`, dispatched commands and the GUI
 * all meet the same rules.
 */
class LifestealBankCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val manager: LifestealBankManager get() = plugin.lifestealBankManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.firstOrNull()?.lowercase()
        if (sub == "admin") {
            admin(sender, args.drop(1))
            return true
        }
        if (!sender.hasPermission(USE_PERMISSION)) {
            Msg.noPermission(sender)
            return true
        }
        val player = sender as? Player ?: run {
            help(sender)
            return true
        }
        manager.unavailableReason()?.let {
            Msg.error(player, it)
            return true
        }
        when (sub) {
            null -> manager.menu.openMain(player)
            "balance", "bal" -> balance(player)
            "deposit", "dep", "d" -> move(player, BankRules.Direction.DEPOSIT, args)
            "withdraw", "with", "w" -> move(player, BankRules.Direction.WITHDRAW, args)
            else -> help(player)
        }
        return true
    }

    private fun help(sender: CommandSender) {
        val lines = mutableListOf(
            "&6&lProtected Bank &7(Lifesteal)",
            "&e/bank &7- open the bank",
            "&e/bank balance &7- your wallet and bank",
            "&e/bank deposit <amount|all> &7- protect coins from PvP",
            "&e/bank withdraw <amount|all> &7- move coins back to your wallet",
        )
        if (sender.hasPermission(ADMIN_PERMISSION)) lines += "&e/bank admin &7- staff tools"
        lines.forEach { sender.sendMessage(plugin.commsManager.parseLegacy(it)) }
    }

    private fun balance(player: Player) {
        val b = manager.balances(player.uniqueId) ?: return Msg.error(player, "Your balances couldn't be read. Please try again.")
        val capacity = manager.capacityFor(player.uniqueId)
        manager.send(player, "&7Wallet: &6${BountyMoney.format(b.walletCents)} &8| &7Protected Bank: &a${BountyMoney.format(b.bankCents)}" +
            (capacity?.let { " &8/ &7${BountyMoney.format(it)}" } ?: ""))
    }

    private fun move(player: Player, direction: BankRules.Direction, args: Array<out String>) {
        if (args.size != 2) return Msg.usage(player, "/bank ${direction.id} <amount|all>")
        val raw = args[1]
        val cents: Long? = if (raw.equals("all", ignoreCase = true)) null else when (val parsed = BountyMoney.parse(raw)) {
            is BountyMoney.Parsed.Ok -> parsed.cents
            else -> return Msg.error(player, parseProblem(parsed))
        }
        // A fresh id per command: typing the command twice is two requests, a retried one is not.
        manager.transfer(player, direction, cents, UUID.randomUUID().toString(), "command")
    }

    // ── /bank admin ─────────────────────────────────────────

    private fun admin(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) return Msg.noPermission(sender)
        manager.unavailableReason()?.let { return Msg.error(sender, it) }
        fun line(text: String) = sender.sendMessage(plugin.commsManager.parseLegacy(text))
        fun target(index: Int, usage: String): Pair<UUID, String>? {
            val name = args.getOrNull(index) ?: run { Msg.usage(sender, usage); return null }
            val resolved = if (name.matches(NAME)) plugin.playerResolver.resolve(name) else null
            if (resolved == null) {
                Msg.playerNotFound(sender, name)
                return null
            }
            return resolved.uniqueId to (resolved.name ?: name)
        }
        try {
            when (args.firstOrNull()?.lowercase()) {
                "view", "info" -> {
                    val (uuid, name) = target(1, "/bank admin view <player>") ?: return
                    val b = manager.store.balances(uuid)
                    line("&6$name&7: wallet &6${BountyMoney.format(b.walletCents)}&7, bank &a${BountyMoney.format(b.bankCents)}&7, capacity &f${manager.capacityFor(uuid)?.let { BountyMoney.format(it) } ?: "unlimited"}")
                }
                "history" -> {
                    val (uuid, name) = target(1, "/bank admin history <player> [page]") ?: return
                    val page = (args.getOrNull(2)?.toIntOrNull() ?: 1).coerceIn(1, 1000)
                    val rows = manager.store.history(uuid, (page - 1) * PAGE, PAGE)
                    line("&6Bank history of $name &7(page $page)")
                    if (rows.isEmpty()) line("&7No entries.")
                    rows.forEach { r ->
                        line("&8#${r.id} &7${time(r.at)} &e${r.type} &6${BountyMoney.format(r.amountCents)}" +
                            (if (r.feeCents > 0) " &7fee &c${BountyMoney.format(r.feeCents)}" else "") +
                            (r.counterparty?.let { " &7vs &f${nameOf(it)}" } ?: "") +
                            " &7${r.state.lowercase()}" + (r.season?.let { " &8[$it]" } ?: "") + " &8tx ${r.txId.take(13)}" +
                            (r.actor?.takeIf { r.source == "admin" }?.let { " &7by $it" } ?: "") + (r.detail?.let { " &8($it)" } ?: ""))
                    }
                }
                "transfers", "steals" -> {
                    val (uuid, name) = target(1, "/bank admin transfers <player>") ?: return
                    val rows = manager.store.steals(uuid, PAGE)
                    line("&6PvP coin transfers involving $name")
                    if (rows.isEmpty()) line("&7None.")
                    rows.forEach { r ->
                        line("&7${time(r.at)} &f${nameOf(r.killer)} &7took &6${BountyMoney.format(r.amountCents)} &7from &f${nameOf(r.victim)}" +
                            " &8(wallet before ${BountyMoney.format(r.victimWalletBeforeCents)}, ${r.source}${if (r.ranked) ", ranked" else ""}${r.season?.let { ", $it" } ?: ""})")
                    }
                }
                "suspicious" -> {
                    val rows = manager.store.suspicious(PAGE)
                    line("&6Suspicious coin transfers &7(logged only unless block-shared-connections)")
                    if (rows.isEmpty()) line("&7None.")
                    rows.forEach { line("&7${time(it.at)} &f${nameOf(it.killer)} &7-> &f${nameOf(it.victim)}&7: ${it.detail}") }
                }
                "failed" -> {
                    val rows = manager.store.failed(PAGE, includeReviewed = args.getOrNull(1).equals("all", ignoreCase = true))
                    line("&6Failed bank transactions &7(rolled back: nothing moved)")
                    if (rows.isEmpty()) line("&7None.")
                    rows.forEach {
                        line("&8#${it.id} &7${time(it.at)} &e${it.type} &f${nameOf(it.uuid)} &7${it.detail ?: ""}" +
                            (it.reviewedAt?.let { _ -> " &a(reviewed by ${it.reviewedBy}: ${it.reviewNote})" } ?: ""))
                    }
                }
                "review" -> {
                    val id = args.getOrNull(1)?.toLongOrNull() ?: return Msg.usage(sender, "/bank admin review <id> <note>")
                    val note = args.drop(2).joinToString(" ").trim()
                    if (note.isEmpty()) return Msg.usage(sender, "/bank admin review <id> <note>")
                    if (manager.store.review(id, actorOf(sender), note, System.currentTimeMillis())) {
                        AdminAudit.log(plugin, sender, "BANK_FAILURE_REVIEWED", details = "ledger=$id note=$note")
                        Msg.success(sender, "Marked failed transaction #$id as reviewed.")
                    } else Msg.error(sender, "No unreviewed failed transaction #$id.")
                }
                "summary" -> line("&7Coins held in all Lifesteal banks: &a${BountyMoney.format(manager.store.totalBankCents())}")
                "add", "remove" -> adjust(sender, args)
                else -> {
                    line("&6&lBank admin")
                    line("&e/bank admin view <player> &7- wallet, bank and capacity")
                    line("&e/bank admin history <player> [page] &7- deposits, withdrawals, PvP and adjustments")
                    line("&e/bank admin transfers <player> &7- PvP coin transfers")
                    line("&e/bank admin suspicious &7- repeated kills and shared connections")
                    line("&e/bank admin failed [all] &7- rolled back transactions")
                    line("&e/bank admin review <id> <note> &7- mark a failed transaction investigated")
                    line("&e/bank admin summary &7- coins held in all banks")
                    if (sender.hasPermission(ADJUST_PERMISSION)) line("&e/bank admin add|remove <player> <amount> <reason> &7- correct a bank balance")
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("[Bank] /bank admin failed: ${e.message}")
            Msg.error(sender, "That couldn't be read right now.")
        }
    }

    private fun adjust(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(ADJUST_PERMISSION)) return Msg.noPermission(sender)
        val op = if (args[0].equals("add", ignoreCase = true)) BankStore.AdminOp.ADD else BankStore.AdminOp.REMOVE
        if (args.size < 4) return Msg.usage(sender, "/bank admin ${op.name.lowercase()} <player> <amount> <reason>")
        val name = args[1]
        val resolved = (if (name.matches(NAME)) plugin.playerResolver.resolve(name) else null) ?: return Msg.playerNotFound(sender, name)
        val uuid = resolved.uniqueId
        plugin.staffRankManager.protectionProblem(sender, uuid)?.let { return Msg.error(sender, it) }
        val cents = when (val parsed = BountyMoney.parse(args[2])) {
            is BountyMoney.Parsed.Ok -> parsed.cents
            else -> return Msg.error(sender, parseProblem(parsed))
        }
        val reason = args.drop(3).joinToString(" ").trim()
        when (val result = manager.adjust(actorOf(sender), uuid, op, cents, reason)) {
            is BankStore.AdminResult.Refused -> Msg.error(sender, result.message)
            is BankStore.AdminResult.Done -> {
                AdminAudit.log(plugin, sender, "BANK_${op.name}", uuid, resolved.name ?: name,
                    "amount=${BountyMoney.format(cents)} bank_after=${BountyMoney.format(result.bankAfterCents)} reason=$reason")
                Msg.success(sender, "${if (op == BankStore.AdminOp.ADD) "Added" else "Removed"} ${BountyMoney.format(cents)} " +
                    "${if (op == BankStore.AdminOp.ADD) "to" else "from"} ${resolved.name ?: name}'s bank. Bank: ${BountyMoney.format(result.bankAfterCents)}.")
            }
        }
    }

    private fun actorOf(sender: CommandSender): String = if (sender is Player) "${sender.uniqueId} (${sender.name})" else "CONSOLE"

    private fun nameOf(uuid: UUID): String = Bukkit.getPlayer(uuid)?.name ?: plugin.playerResolver.lastKnownName(uuid) ?: uuid.toString().take(8)

    private fun time(at: Long): String = TIME.format(Instant.ofEpochMilli(at))

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val subs = mutableListOf("balance", "deposit", "withdraw")
        if (sender.hasPermission(ADMIN_PERMISSION)) subs += "admin"
        return when (args.size) {
            1 -> subs.filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> when (args[0].lowercase()) {
                "deposit", "withdraw" -> listOf("all", "1000", "10k").filter { it.startsWith(args[1], ignoreCase = true) }
                "admin" -> if (sender.hasPermission(ADMIN_PERMISSION)) {
                    (listOf("view", "history", "transfers", "suspicious", "failed", "review", "summary") +
                        (if (sender.hasPermission(ADJUST_PERMISSION)) listOf("add", "remove") else emptyList()))
                        .filter { it.startsWith(args[1], ignoreCase = true) }
                } else emptyList()
                else -> emptyList()
            }
            3 -> if (args[0].equals("admin", ignoreCase = true) && sender.hasPermission(ADMIN_PERMISSION) &&
                args[1].lowercase() in setOf("view", "history", "transfers", "add", "remove")) {
                Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
            } else emptyList()
            else -> emptyList()
        }
    }

    companion object {
        const val USE_PERMISSION = "joshymc.lifesteal.bank.use"
        const val ADMIN_PERMISSION = "joshymc.lifesteal.bank.admin"
        const val ADJUST_PERMISSION = "joshymc.lifesteal.bank.admin.adjust"
        private const val PAGE = 10
        private val NAME = Regex("^[A-Za-z0-9_.]{1,32}$")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

        /** Player words for an amount that didn't parse (shared with the GUI's custom amount). */
        fun parseProblem(parsed: BountyMoney.Parsed): String = when (parsed) {
            BountyMoney.Parsed.Negative -> "The amount can't be negative."
            BountyMoney.Parsed.Zero -> "The amount must be more than 0."
            BountyMoney.Parsed.TooPrecise -> "Amounts can have at most two decimals."
            BountyMoney.Parsed.TooLarge -> "That amount is too large."
            BountyMoney.Parsed.Invalid -> "That isn't an amount. Use a number like 5000, 75k or 1.5m, or \"all\"."
            is BountyMoney.Parsed.Ok -> "OK"
        }
    }
}
