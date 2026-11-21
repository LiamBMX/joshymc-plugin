package com.liam.joshymc.lootbox

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.ranked.RankedIntegrity
import com.liam.joshymc.manager.PurchaseFulfillmentManager
import com.liam.joshymc.manager.PurchaseRewards
import com.liam.joshymc.staff.AdminAudit
import com.liam.joshymc.util.Msg
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * `/lootbox` (issue #1602). Players: `help`. Staff: `give`, `giveall` (`joshymc.lootbox.give`),
 * `list`, `admin pending|inspect` (`joshymc.lootbox.admin`), `reload` (`joshymc.lootbox.reload`),
 * `admin review|resolve ... confirm` (`joshymc.lootbox.recovery`). Lootboxes are handed out only
 * through the purchase delivery queue (`lootbox` reward kind): online players get them when they
 * have room, offline players on their next join, and nothing is ever dropped.
 */
class LootboxCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val lootboxes get() = checkNotNull(plugin.lootboxManagerOrNull())

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (plugin.lootboxManagerOrNull() == null) {
            Msg.error(sender, "Lootboxes are turned off on this server.")
            return true
        }
        when (args.firstOrNull()?.lowercase()) {
            null, "help" -> help(sender, label)
            "give" -> give(sender, args.drop(1))
            "giveall" -> giveAll(sender, args.drop(1))
            "list" -> list(sender)
            "reload" -> reload(sender)
            "admin" -> admin(sender, args.drop(1))
            else -> help(sender, label)
        }
        return true
    }

    private fun help(sender: CommandSender, label: String) {
        reply(sender, "&6&lLootboxes")
        reply(sender, "&7Right-click a Lootbox, pick &f5 &7of the &f27 &7mystery slots and confirm.")
        reply(sender, "&7Every pick wins one prize drawn at random from that level's rewards.")
        reply(sender, "&7Rewards that don't fit wait for you and are never dropped.")
        if (sender.hasPermission(GIVE)) {
            reply(sender, "&e/$label give <player> <level> [amount] &7- give Lootboxes (offline: on join)")
            reply(sender, "&e/$label giveall <level> [amount] &7- give every online player Lootboxes")
        }
        if (sender.hasPermission(ADMIN)) {
            reply(sender, "&e/$label list &7- levels and their status")
            reply(sender, "&e/$label admin pending &7- openings that need delivery or a staff check")
            reply(sender, "&e/$label admin inspect <player> &7- a player's recent openings")
        }
        if (sender.hasPermission(RELOAD)) reply(sender, "&e/$label reload &7- reread lootboxes.yml")
        if (sender.hasPermission(RECOVERY)) {
            reply(sender, "&e/$label admin review <id> grant|void confirm &7- settle an opening in REVIEW")
            reply(sender, "&e/$label admin resolve <id> <prize 0-4> resend|delivered confirm &7- settle an INTERRUPTED/FAILED prize")
        }
    }

    // ── Giving ───────────────────────────────────────────────────

    private fun parseLevel(sender: CommandSender, raw: String?): Int? {
        val level = raw?.toIntOrNull()
        if (level == null || level !in 1..LootboxConfig.LEVELS) {
            Msg.error(sender, "Level must be a whole number from 1 to ${LootboxConfig.LEVELS}.")
            return null
        }
        lootboxes.levelProblem(level)?.let {
            Msg.error(sender, it)
            return null
        }
        return level
    }

    private fun parseAmount(sender: CommandSender, raw: String?): Int? {
        if (raw == null) return 1
        val amount = raw.toIntOrNull()
        if (amount == null || amount !in 1..PurchaseRewards.MAX_ITEM_QUANTITY) {
            Msg.error(sender, "Amount must be a whole number from 1 to ${PurchaseRewards.MAX_ITEM_QUANTITY}.")
            return null
        }
        return amount
    }

    private fun give(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(GIVE)) return Msg.noPermission(sender)
        if (args.size !in 2..3) return Msg.usage(sender, "/lootbox give <player> <level> [amount]")
        val target = plugin.purchaseFulfillmentManager.resolvePlayer(args[0]) ?: return Msg.playerNotFound(sender, args[0])
        val level = parseLevel(sender, args[1]) ?: return
        val amount = parseAmount(sender, args.getOrNull(2)) ?: return
        if (refusedRanked(sender, target.first, target.second)) return
        val delivery = enqueue(sender, target.first, target.second, level, amount) ?: return
        AdminAudit.log(plugin, sender, "LOOTBOX_GIVE", target.first, target.second, "level=$level amount=$amount delivery=#${delivery.id}")
        val left = delivery.remaining
        Msg.success(sender, "Gave ${target.second} ${amount}x Level $level Lootbox" +
            if (left == 0) "." else " ($left waiting for them to be online with room; delivery #${delivery.id}).")
    }

    private fun giveAll(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(GIVE)) return Msg.noPermission(sender)
        if (args.size !in 1..2) return Msg.usage(sender, "/lootbox giveall <level> [amount]")
        val level = parseLevel(sender, args[0]) ?: return
        val amount = parseAmount(sender, args.getOrNull(1)) ?: return
        var given = 0
        var skipped = 0
        for (player in Bukkit.getOnlinePlayers().toList()) {
            val refused = try {
                plugin.lifestealRankedManager.inventoryReplacedLater(player)
            } catch (_: Exception) {
                false
            }
            // Ranked players still get theirs: the queue holds item deliveries until the restore is done.
            if (refused) skipped++
            if (enqueue(sender, player.uniqueId, player.name, level, amount, quiet = true) != null) given++
        }
        AdminAudit.log(plugin, sender, "LOOTBOX_GIVEALL", details = "level=$level amount=$amount players=$given")
        Msg.success(sender, "Gave $given online player(s) ${amount}x Level $level Lootbox." +
            if (skipped > 0) " $skipped in a Ranked match get theirs once their inventory is restored." else "")
    }

    private fun refusedRanked(sender: CommandSender, uuid: UUID, name: String): Boolean = try {
        plugin.lifestealRankedManager.refusesStaffAction(sender, uuid, name, RankedIntegrity.StaffAction.ITEM_GRANTS)
    } catch (_: Exception) {
        false
    }

    /** One `lootbox` row in the purchase delivery queue: delivered now if it fits, otherwise when they're online with room. */
    private fun enqueue(sender: CommandSender, uuid: UUID, name: String, level: Int, amount: Int, quiet: Boolean = false) =
        when (val result = plugin.purchaseFulfillmentManager.enqueue(
            uuid, name, PurchaseRewards.Spec(PurchaseRewards.Kind.LOOTBOX, level.toString(), amount),
            "lootbox-give:${UUID.randomUUID()}", "${PurchaseFulfillmentManager.LOOTBOX_SOURCE_PREFIX}give:${sender.name}",
        )) {
            is PurchaseFulfillmentManager.FulfillResult.Recorded -> result.delivery
            is PurchaseFulfillmentManager.FulfillResult.Duplicate -> result.existing
            else -> {
                if (!quiet) Msg.error(sender, "Couldn't queue the Lootboxes for $name (database error); nothing was given.")
                null
            }
        }

    // ── Info / reload ────────────────────────────────────────────

    private fun list(sender: CommandSender) {
        if (!sender.hasPermission(ADMIN)) return Msg.noPermission(sender)
        val settings = lootboxes.settings
        if (settings == null) {
            reply(sender, "&clootboxes.yml is invalid; nothing can be opened or given:")
            lootboxes.problems.take(10).forEach { reply(sender, "&8 - &7$it") }
            return
        }
        reply(sender, "&6&lLootbox levels &7(${if (settings.enabled) "&aenabled" else "&cdisabled"}&7)")
        for (n in 1..LootboxConfig.LEVELS) {
            val def = settings.level(n) ?: continue
            val problem = lootboxes.levelProblem(n)
            val active = def.activeRewards
            val weight = active.sumOf { it.weight }
            reply(sender, "&e$n&7: ${def.name} &7| ${def.gamemodes.joinToString(",") { it.id }} | ${active.size} reward(s), total weight " +
                "${"%.2f".format(weight)} | " + (problem?.let { "&c$it" } ?: "&aready"))
        }
    }

    private fun reload(sender: CommandSender) {
        if (!sender.hasPermission(RELOAD)) return Msg.noPermission(sender)
        val problems = lootboxes.reload()
        AdminAudit.log(plugin, sender, "LOOTBOX_RELOAD", details = "problems=${problems.size}")
        if (problems.isEmpty()) Msg.success(sender, "Reloaded lootboxes.yml.")
        else {
            Msg.error(sender, "Reloaded lootboxes.yml with ${problems.size} problem(s):")
            problems.take(10).forEach { reply(sender, "&8 - &7$it") }
        }
        val ready = (1..LootboxConfig.LEVELS).filter { lootboxes.levelProblem(it) == null }
        reply(sender, "&7Ready to open: ${if (ready.isEmpty()) "none" else ready.joinToString(", ")}")
    }

    // ── Admin ────────────────────────────────────────────────────

    private fun admin(sender: CommandSender, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "pending" -> pending(sender)
            "inspect" -> inspect(sender, args.drop(1))
            "review" -> review(sender, args.drop(1))
            "resolve" -> resolve(sender, args.drop(1))
            else -> if (sender.hasPermission(ADMIN) || sender.hasPermission(RECOVERY)) {
                Msg.usage(sender, "/lootbox admin <pending|inspect|review|resolve>")
            } else Msg.noPermission(sender)
        }
    }

    private fun pending(sender: CommandSender) {
        if (!sender.hasPermission(ADMIN)) return Msg.noPermission(sender)
        val openings = try {
            lootboxes.store.attention(20)
        } catch (e: Exception) {
            return Msg.error(sender, "Database error: ${e.message}")
        }
        val counts = lootboxes.store.counts()
        reply(sender, "&6&lLootbox openings needing attention &7(prizes: ${counts.entries.joinToString(", ") { "${it.value} ${it.key}" }.ifEmpty { "none" }})")
        if (openings.isEmpty()) return reply(sender, "&7Nothing waiting.")
        for (o in openings) {
            val rewards = lootboxes.store.rewards(o.id)
            reply(sender, "&e#${o.id} &f${o.name} &7Level ${o.level} ${o.gamemode} &f${o.state} &7${time(o.createdAt)} | " +
                rewards.joinToString(" ") { "${it.index}:${it.state}" })
        }
    }

    private fun inspect(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(ADMIN)) return Msg.noPermission(sender)
        if (args.size != 1) return Msg.usage(sender, "/lootbox admin inspect <player>")
        val target = plugin.purchaseFulfillmentManager.resolvePlayer(args[0]) ?: return Msg.playerNotFound(sender, args[0])
        val openings = lootboxes.store.recentFor(target.first, 10)
        reply(sender, "&6&l${target.second}'s recent Lootbox openings")
        if (openings.isEmpty()) return reply(sender, "&7None.")
        for (o in openings) {
            reply(sender, "&e#${o.id} &7Level ${o.level} ${o.gamemode} &f${o.state} &7${time(o.createdAt)} slots ${o.slots}" + (o.note?.let { " &8($it)" } ?: ""))
            for (r in lootboxes.store.rewards(o.id)) {
                reply(sender, "&8  ${r.index}. &f${r.rewardId} &7${r.rawType} x${r.amount} &f${r.state}" +
                    (if (r.remaining in 1 until r.amount) " &7(${r.remaining} left)" else "") + (r.note?.let { " &8($it)" } ?: ""))
            }
        }
    }

    private fun review(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(RECOVERY)) return Msg.noPermission(sender)
        val id = args.getOrNull(0)?.toIntOrNull()
        val action = args.getOrNull(1)?.lowercase()
        if (id == null || action !in setOf("grant", "void") || args.size != 3 || args[2] != "confirm") {
            return Msg.usage(sender, "/lootbox admin review <id> grant|void confirm")
        }
        val opening = lootboxes.store.get(id) ?: return Msg.error(sender, "No opening #$id.")
        if (opening.state != LootboxStore.OpeningState.REVIEW) return Msg.error(sender, "Opening #$id is ${opening.state}, not REVIEW.")
        if (!lootboxes.review(id, action == "grant", sender.name)) return Msg.error(sender, "Opening #$id changed meanwhile; nothing was done.")
        AdminAudit.log(plugin, sender, "LOOTBOX_REVIEW_${action!!.uppercase()}", opening.uuid, opening.name, "opening=$id level=${opening.level}")
        Msg.success(sender, if (action == "grant") "Opening #$id granted: its five prizes are now owed to ${opening.name}."
            else "Opening #$id voided: nothing is owed (${opening.name} keeps the Lootbox they still have).")
    }

    private fun resolve(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(RECOVERY)) return Msg.noPermission(sender)
        val id = args.getOrNull(0)?.toIntOrNull()
        val index = args.getOrNull(1)?.toIntOrNull()
        val action = args.getOrNull(2)?.lowercase()
        if (id == null || index == null || index !in 0..4 || action !in setOf("resend", "delivered") || args.size != 4 || args[3] != "confirm") {
            return Msg.usage(sender, "/lootbox admin resolve <id> <prize 0-4> resend|delivered confirm")
        }
        val row = lootboxes.store.reward(id, index) ?: return Msg.error(sender, "No prize $index in opening #$id.")
        if (row.state != LootboxStore.RewardState.INTERRUPTED && row.state != LootboxStore.RewardState.FAILED) {
            return Msg.error(sender, "Prize $index of #$id is ${row.state}; only INTERRUPTED or FAILED prizes are resolved.")
        }
        if (!lootboxes.resolve(id, index, action == "resend", sender.name)) return Msg.error(sender, "That prize changed meanwhile; nothing was done.")
        val name = lootboxes.store.get(id)?.name
        AdminAudit.log(plugin, sender, "LOOTBOX_RESOLVE_${action!!.uppercase()}", row.uuid, name, "opening=$id prize=$index reward=${row.rewardId} remaining=${row.remaining}")
        Msg.success(sender, if (action == "resend") "Prize $index of #$id will be delivered again (${row.remaining} item(s))."
            else "Prize $index of #$id recorded as delivered.")
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun reply(sender: CommandSender, text: String) = Msg.reply(sender, plugin.commsManager.parseLegacy(text))

    private fun time(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(millis))

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (plugin.lootboxManagerOrNull() == null) return emptyList()
        fun match(options: Collection<String>, typed: String) = options.filter { it.startsWith(typed, ignoreCase = true) }
        val levels = (1..LootboxConfig.LEVELS).map { it.toString() }
        return when (args.size) {
            1 -> match(buildList {
                add("help")
                if (sender.hasPermission(GIVE)) { add("give"); add("giveall") }
                if (sender.hasPermission(ADMIN)) add("list")
                if (sender.hasPermission(RELOAD)) add("reload")
                if (sender.hasPermission(ADMIN) || sender.hasPermission(RECOVERY)) add("admin")
            }, args[0])
            2 -> when (args[0].lowercase()) {
                "give" -> if (sender.hasPermission(GIVE)) match(Bukkit.getOnlinePlayers().map { it.name }, args[1]) else emptyList()
                "giveall" -> if (sender.hasPermission(GIVE)) match(levels, args[1]) else emptyList()
                "admin" -> match(buildList {
                    if (sender.hasPermission(ADMIN)) { add("pending"); add("inspect") }
                    if (sender.hasPermission(RECOVERY)) { add("review"); add("resolve") }
                }, args[1])
                else -> emptyList()
            }
            3 -> when {
                args[0].equals("give", true) && sender.hasPermission(GIVE) -> match(levels, args[2])
                args[0].equals("admin", true) && args[1].equals("inspect", true) && sender.hasPermission(ADMIN) ->
                    match(Bukkit.getOnlinePlayers().map { it.name }, args[2])
                args[0].equals("admin", true) && args[1].equals("review", true) && sender.hasPermission(RECOVERY) ->
                    match(lootboxes.store.attention(20).filter { it.state == LootboxStore.OpeningState.REVIEW }.map { it.id.toString() }, args[2])
                else -> emptyList()
            }
            4 -> when {
                args[0].equals("admin", true) && args[1].equals("review", true) && sender.hasPermission(RECOVERY) -> match(listOf("grant", "void"), args[3])
                args[0].equals("admin", true) && args[1].equals("resolve", true) && sender.hasPermission(RECOVERY) -> match((0..4).map { it.toString() }, args[3])
                else -> emptyList()
            }
            5 -> when {
                args[0].equals("admin", true) && args[1].equals("review", true) && sender.hasPermission(RECOVERY) -> match(listOf("confirm"), args[4])
                args[0].equals("admin", true) && args[1].equals("resolve", true) && sender.hasPermission(RECOVERY) -> match(listOf("resend", "delivered"), args[4])
                else -> emptyList()
            }
            6 -> if (args[0].equals("admin", true) && args[1].equals("resolve", true) && sender.hasPermission(RECOVERY)) match(listOf("confirm"), args[5]) else emptyList()
            else -> emptyList()
        }
    }

    companion object {
        const val GIVE = "joshymc.lootbox.give"
        const val ADMIN = "joshymc.lootbox.admin"
        const val RELOAD = "joshymc.lootbox.reload"
        const val RECOVERY = "joshymc.lootbox.recovery"
    }
}
