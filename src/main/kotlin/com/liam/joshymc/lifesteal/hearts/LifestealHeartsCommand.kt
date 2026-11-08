package com.liam.joshymc.lifesteal.hearts

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.BedrockUtil
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/**
 * `/lifesteal hearts get|set|add|remove <player> [amount]` (issue #1341). `/lifesteal` itself is
 * the cross-server transfer command (issue #1331), so this is a subcommand of that executor
 * rather than a new command. Admin only ([PERMISSION]) and only on a server that runs Lifesteal;
 * works for offline players the server has seen (by UUID, via the PlayerResolver).
 */
class LifestealHeartsCommand(private val plugin: Joshymc) {

    /** Whether [sender] should see the subcommand at all (permission and a Lifesteal server). */
    fun visibleTo(sender: CommandSender): Boolean =
        (sender.hasPermission(PERMISSION) || sender.hasPermission(GIVE_PERMISSION)) && plugin.lifestealHeartManager.hosted

    /** [args] starts after `hearts`. */
    fun execute(sender: CommandSender, args: List<String>) {
        // Heart item grants (issue #1344) have their own permission, checked before anything else.
        if (args.firstOrNull()?.equals("give", ignoreCase = true) == true) {
            give(sender, args)
            return
        }
        if (!sender.hasPermission(PERMISSION)) {
            Msg.noPermission(sender)
            return
        }
        when (args.firstOrNull()?.lowercase()) {
            "items" -> return items(sender, args)
            "void" -> return void(sender, args)
            "reviews" -> return reviews(sender, args)
        }
        val hearts = plugin.lifestealHeartManager
        if (!hearts.hosted) {
            Msg.error(sender, "Hearts only exist on the Lifesteal server.")
            return
        }
        if (!hearts.active) {
            Msg.error(sender, "Hearts are paused: lifesteal.yml is invalid (see the console and /jgamemode info).")
            return
        }
        val action = args.getOrNull(0)?.lowercase()
        val op = HeartRules.AdminOp.fromId(action)
        if (action != "get" && op == null || args.size < 2) {
            Msg.usage(sender, USAGE)
            return
        }
        val target = plugin.playerResolver.resolve(args[1])
        if (target == null) {
            Msg.playerNotFound(sender, args[1])
            return
        }
        val name = target.name ?: args[1]
        val uuid = target.uniqueId

        if (action == "get") {
            if (args.size != 2) {
                Msg.usage(sender, USAGE)
                return
            }
            val record = try {
                hearts.storedRecord(uuid)
            } catch (e: Exception) {
                Msg.error(sender, "Couldn't read the heart record: ${e.message}")
                return
            }
            if (record == null) {
                Msg.reply(sender, Component.text("$name has no hearts yet (they haven't played Lifesteal here).", NamedTextColor.GRAY))
                return
            }
            val limits = hearts.limits ?: return
            val edition = if (BedrockUtil.isBedrockPlayer(uuid)) " (Bedrock)" else ""
            val stored = record.hearts
            val line = when {
                stored == null -> Component.text("$name$edition: stored hearts are unreadable. Repair with /lifesteal hearts set $name <hearts>.", NamedTextColor.RED)
                stored != limits.clamp(stored) -> Component.text(
                    "$name$edition: $stored hearts stored, applied as ${limits.clamp(stored)} (limits ${limits.minimum}-${limits.maximum}).",
                    NamedTextColor.YELLOW
                )
                else -> Component.text("$name$edition: ", NamedTextColor.GRAY)
                    .append(Component.text("$stored❤", NamedTextColor.RED))
                    .append(Component.text(" (limits ${limits.minimum}-${limits.maximum})", NamedTextColor.GRAY))
            }
            Msg.reply(sender, line)
            withdrawStatus(sender, uuid)
            return
        }

        if (args.size != 3) {
            Msg.usage(sender, USAGE)
            return
        }
        val amount = HeartRules.parseAmount(args[2])
        if (amount == null) {
            Msg.error(sender, "'${args[2]}' is not a whole number of hearts.")
            return
        }
        val actor = (sender as? Player)?.uniqueId?.toString() ?: "console"
        val result = try {
            hearts.adminChange(uuid, op!!, amount, actor, sender.name)
        } catch (e: Exception) {
            plugin.logger.severe("[Hearts] ${sender.name}: ${op!!.id} $amount for $name failed and was rolled back: ${e.message}")
            Msg.error(sender, "The change couldn't be saved, so nothing changed.")
            return
        }
        when (result) {
            HeartStore.AdminResult.NoRecord -> Msg.error(sender, "$name has no hearts yet (they haven't played Lifesteal here).")
            is HeartStore.AdminResult.Invalid -> Msg.error(sender, result.reason)
            is HeartStore.AdminResult.Unchanged -> Msg.reply(sender, Component.text("$name already has ${result.hearts} hearts; nothing changed.", NamedTextColor.GRAY))
            is HeartStore.AdminResult.Changed -> {
                val limits = hearts.limits
                val note = if (result.clamped && limits != null) " (stopped at the ${limits.minimum}-${limits.maximum} limit)" else ""
                Msg.success(sender, "$name now has ${result.after} hearts (was ${result.before ?: "unreadable"})$note.")
            }
        }
    }

    /** Shared refusals for the Heart item subcommands; true = go on. */
    private fun ready(sender: CommandSender): Boolean {
        val hearts = plugin.lifestealHeartManager
        if (!hearts.hosted) {
            Msg.error(sender, "Hearts only exist on the Lifesteal server.")
            return false
        }
        if (!hearts.active) {
            Msg.error(sender, "Hearts are paused: lifesteal.yml is invalid (see the console and /jgamemode info).")
            return false
        }
        return true
    }

    /** `give <player> <amount>`: recorded Heart items for an online Lifesteal player ([GIVE_PERMISSION]). */
    private fun give(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(GIVE_PERMISSION)) {
            Msg.noPermission(sender)
            return
        }
        if (!ready(sender)) return
        if (args.size != 3) {
            Msg.usage(sender, GIVE_USAGE)
            return
        }
        val target = plugin.playerResolver.online(args[1])
        if (target == null) {
            Msg.error(sender, "${args[1]} isn't online. Heart items can only be given to online players.")
            return
        }
        val amount = HeartRules.parseAmount(args[2])
        if (amount == null || amount < 1 || amount > HeartRules.MAX_ADMIN_ITEM_GRANT) {
            Msg.error(sender, "The amount must be a whole number from 1 to ${HeartRules.MAX_ADMIN_ITEM_GRANT}.")
            return
        }
        val actor = (sender as? Player)?.uniqueId?.toString() ?: "console"
        val result = try {
            plugin.lifestealHeartManager.grantItems(target, amount, actor, sender.name)
        } catch (e: Exception) {
            plugin.logger.severe("[Hearts] ${sender.name}: giving ${target.name} $amount Heart item(s) failed and was rolled back: ${e.message}")
            Msg.error(sender, "The grant couldn't be saved, so no Heart items were made.")
            return
        }
        when (result) {
            is HeartStore.GrantResult.Granted -> Msg.success(sender, "Gave ${target.name} $amount Heart item(s).")
            is HeartStore.GrantResult.Invalid -> Msg.error(sender, result.reason)
            HeartStore.GrantResult.NoRecord -> Msg.error(sender, "${target.name} has no hearts yet (they haven't played Lifesteal here).")
        }
    }

    /** `items <player>`: Heart items issued to a player that were never redeemed or voided. */
    private fun items(sender: CommandSender, args: List<String>) {
        if (!ready(sender)) return
        if (args.size != 2) {
            Msg.usage(sender, "/lifesteal hearts items <player>")
            return
        }
        val target = plugin.playerResolver.resolve(args[1])
        if (target == null) {
            Msg.playerNotFound(sender, args[1])
            return
        }
        val name = target.name ?: args[1]
        val rows = try {
            plugin.lifestealHeartManager.outstandingItems(target.uniqueId)
        } catch (e: Exception) {
            Msg.error(sender, "Couldn't read the Heart items: ${e.message}")
            return
        }
        if (rows.isEmpty()) {
            Msg.reply(sender, Component.text("$name has no outstanding Heart items.", NamedTextColor.GRAY))
            return
        }
        Msg.reply(sender, Component.text("Outstanding Heart items issued to $name (newest first, up to 20):", NamedTextColor.GRAY))
        val format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault())
        for (row in rows) {
            val source = if (row.source == HeartStore.ItemSource.ADMIN) "staff grant" else "withdrawn"
            sender.sendMessage(
                Component.text(" ${row.serial}", NamedTextColor.WHITE)
                    .append(Component.text(" $source ${format.format(java.time.Instant.ofEpochMilli(row.issuedAt))}", NamedTextColor.GRAY))
            )
        }
    }

    /** Issue #1457: Lifesteal playtime and withdrawal eligibility under `get`. */
    private fun withdrawStatus(sender: CommandSender, uuid: UUID) {
        val status = try {
            plugin.lifestealHeartManager.withdrawStatus(uuid)
        } catch (e: Exception) {
            Msg.error(sender, "Couldn't read the withdrawal eligibility: ${e.message}")
            return
        } ?: return
        val (playtime, eligibility) = status
        val played = if (playtime <= 0) "none" else HeartRules.formatWait(playtime)
        val verdict = if (eligibility.eligible) Component.text("may withdraw hearts", NamedTextColor.GREEN)
        else Component.text(
            listOfNotNull(
                "needs ${HeartRules.formatWait(eligibility.playtimeMissingMillis)} more playtime".takeIf { eligibility.playtimeMissingMillis > 0 },
                "account too new for ${HeartRules.formatWait(eligibility.accountAgeMissingMillis)}".takeIf { eligibility.accountAgeMissingMillis > 0 },
            ).joinToString(", "),
            NamedTextColor.YELLOW
        )
        sender.sendMessage(Component.text(" Lifesteal playtime: $played. Withdrawal: ", NamedTextColor.GRAY).append(verdict))
    }

    /**
     * `reviews [player]` (issue #1457): withdrawn Heart items redeemed by an account that shared a
     * connection with the player who withdrew them. Names and UUIDs only, never connection data.
     */
    private fun reviews(sender: CommandSender, args: List<String>) {
        if (!ready(sender)) return
        if (args.size !in 1..2) {
            Msg.usage(sender, "/lifesteal hearts reviews [player]")
            return
        }
        val target = args.getOrNull(1)?.let { name ->
            plugin.playerResolver.resolve(name) ?: run {
                Msg.playerNotFound(sender, name)
                return
            }
        }
        val rows = try {
            plugin.lifestealHeartManager.transferReviews(target?.uniqueId)
        } catch (e: Exception) {
            Msg.error(sender, "Couldn't read the transfer reviews: ${e.message}")
            return
        }
        val scope = target?.let { " involving ${it.name ?: args[1]}" } ?: ""
        if (rows.isEmpty()) {
            Msg.reply(sender, Component.text("No shared-connection Heart item transfers$scope.", NamedTextColor.GRAY))
            return
        }
        Msg.reply(sender, Component.text("Shared-connection Heart item transfers$scope (newest first, up to 20). A shared connection isn't proof of one owner:", NamedTextColor.GRAY))
        val format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault())
        fun name(uuid: UUID) = plugin.playerResolver.lastKnownName(uuid) ?: uuid.toString()
        for (row in rows) {
            val age = row.issuerFirstJoin?.let { " (issuer's account was ${HeartRules.formatWait((row.issuedAt - it).coerceAtLeast(0))} old)" } ?: ""
            sender.sendMessage(
                Component.text(" ${format.format(java.time.Instant.ofEpochMilli(row.at))} ", NamedTextColor.GRAY)
                    .append(Component.text(name(row.issuer), NamedTextColor.WHITE))
                    .append(Component.text(" -> ", NamedTextColor.GRAY))
                    .append(Component.text(name(row.redeemer), NamedTextColor.WHITE))
                    .append(Component.text(" ${row.serial}$age", NamedTextColor.DARK_GRAY))
            )
        }
    }

    /** `void <serial> [refund]`: recovery for a lost Heart item (see [HeartStore.voidItem]). */
    private fun void(sender: CommandSender, args: List<String>) {
        if (!ready(sender)) return
        val refund = args.getOrNull(2)?.equals("refund", ignoreCase = true) == true
        if (args.size !in 2..3 || (args.size == 3 && !refund)) {
            Msg.usage(sender, "/lifesteal hearts void <serial> [refund]")
            return
        }
        val actor = (sender as? Player)?.uniqueId?.toString() ?: "console"
        val result = try {
            plugin.lifestealHeartManager.voidItem(args[1], refund, actor, sender.name)
        } catch (e: Exception) {
            plugin.logger.severe("[Hearts] ${sender.name}: voiding Heart item ${args[1]} failed and was rolled back: ${e.message}")
            Msg.error(sender, "The change couldn't be saved, so nothing changed.")
            return
        }
        when (result) {
            HeartStore.VoidResult.UnknownItem -> Msg.error(sender, "No Heart item with serial ${args[1]} was ever issued here.")
            is HeartStore.VoidResult.NotOutstanding -> Msg.error(sender, "That Heart item is already ${result.status?.name?.lowercase() ?: "closed"}.")
            is HeartStore.VoidResult.Refused -> Msg.error(sender, result.reason)
            is HeartStore.VoidResult.Voided -> Msg.success(
                sender,
                if (result.refunded) "Voided it and refunded the heart (${result.before} -> ${result.after})." else "Voided it; it can no longer be redeemed."
            )
        }
    }

    fun complete(sender: CommandSender, args: List<String>): List<String> {
        if (!visibleTo(sender)) return emptyList()
        val admin = sender.hasPermission(PERMISSION)
        return when (args.size) {
            1 -> buildList {
                if (admin) addAll(listOf("get", "set", "add", "remove", "items", "void", "reviews"))
                if (sender.hasPermission(GIVE_PERMISSION)) add("give")
            }.filter { it.startsWith(args[0].lowercase()) }
            2 -> if (args[0].equals("void", ignoreCase = true)) emptyList()
                else plugin.server.onlinePlayers.map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
            3 -> if (args[0].equals("void", ignoreCase = true) && admin) listOf("refund").filter { it.startsWith(args[2].lowercase()) } else emptyList()
            else -> emptyList()
        }
    }

    companion object {
        const val PERMISSION = "joshymc.lifesteal.hearts.admin"
        /** Making Heart items (issue #1344) is its own permission, not part of [PERMISSION]. */
        const val GIVE_PERMISSION = "joshymc.lifesteal.hearts.give"
        const val USAGE = "/lifesteal hearts <get|set|add|remove> <player> [amount] | items <player> | void <serial> [refund] | reviews [player]"
        const val GIVE_USAGE = "/lifesteal hearts give <player> <amount>"
    }
}
