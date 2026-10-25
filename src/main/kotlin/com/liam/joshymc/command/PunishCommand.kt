package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.PunishmentManager
import com.liam.joshymc.punish.PunishFlags
import com.liam.joshymc.punish.PunishRules
import com.liam.joshymc.punish.RuleCategory
import com.liam.joshymc.punish.RulePunishManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/punish` (issue #1298).
 *
 *  - `/punish <player> <rule> [gm:<survival|lifesteal>] [scope:network] [tier:<n>] [alt] [evidence...]`
 *    looks up the rule, works out the next tier from the player's history and the staff member's
 *    authority, and opens a confirmation ([RulePunishManager]). Nothing is applied until confirmed.
 *  - `/punish <player>` opens the rule picker; `history`, `preview`, `rules`, `confirm`, `cancel`,
 *    `overturn`, `review`, `audit verify` and `reload` are subcommands.
 *  - The older `/punish <player> <ban|tempban|tempmute|unban|unmute|warn|unwarn> ...` forms still
 *    work unchanged, each behind its own `joshymc.punish.<type>` permission.
 */
class PunishCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        val TYPES = listOf("ban", "tempban", "tempmute", "unban", "unmute", "warn", "unwarn")
        val TEMP_DURATIONS = listOf("30m", "1h", "6h", "12h", "1d", "3d", "7d", "14d", "30d")
        /** Legacy types still suggested: the reversals. The others still work when typed. */
        private val SUGGESTED_TYPES = listOf("unban", "unmute", "unwarn")
        private val SUBCOMMANDS = listOf("history", "preview", "rules", "confirm", "cancel", "overturn", "review", "audit", "reload")
        private const val USAGE = "/punish <player> <rule> [evidence] | /punish <history|preview|rules|confirm|cancel|overturn|review|reload> ..."
    }

    private val rules: RulePunishManager get() = plugin.rulePunishManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            Msg.usage(sender, USAGE)
            return true
        }
        when (args[0].lowercase()) {
            "reload" -> {
                if (!sender.hasPermission("joshymc.punish.reload")) { Msg.noPermission(sender); return true }
                val problems = rules.reload()
                if (problems == 0) Msg.success(sender, "Reloaded punish-rules.yml (${rules.registry.rules.size} rules).")
                else Msg.error(sender, "Reloaded punish-rules.yml with $problems problem(s); see the console. Invalid entries use the defaults.")
                return true
            }
            "rules" -> return handleRules(sender, args)
            "history" -> return handleHistory(sender, args)
            "preview" -> return handlePreview(sender, args)
            "confirm" -> {
                if (rules.levelOf(sender) == null) { Msg.noPermission(sender); return true }
                val id = args.getOrNull(1) ?: run { Msg.usage(sender, "/punish confirm <id> [evidence]"); return true }
                rules.confirm(sender, id, args.drop(2).joinToString(" ").takeIf { it.isNotBlank() })
                return true
            }
            "cancel" -> {
                val id = args.getOrNull(1) ?: run { Msg.usage(sender, "/punish cancel <id>"); return true }
                rules.cancel(sender, id)
                return true
            }
            "overturn" -> {
                val id = args.getOrNull(1)?.removePrefix("#")?.toIntOrNull()
                val reason = args.drop(2).joinToString(" ").trim()
                if (id == null || reason.isEmpty()) { Msg.usage(sender, "/punish overturn <incident> <reason>"); return true }
                rules.overturn(sender, id, reason)
                return true
            }
            "review" -> {
                val id = args.getOrNull(1)?.removePrefix("#")?.toIntOrNull()
                if (args.size > 1 && id == null) { Msg.usage(sender, "/punish review [incident] [note]"); return true }
                rules.review(sender, id, args.drop(2).joinToString(" ").trim().takeIf { it.isNotEmpty() })
                return true
            }
            "audit" -> {
                if (args.getOrNull(1)?.equals("verify", ignoreCase = true) != true) { Msg.usage(sender, "/punish audit verify"); return true }
                rules.verifyAudit(sender)
                return true
            }
        }

        if (args.size == 1) {
            if (sender !is Player) { Msg.usage(sender, USAGE); return true }
            if (rules.levelOf(sender) == null) { Msg.noPermission(sender); return true }
            val target = resolveOfflinePlayer(args[0]) ?: run { Msg.playerNotFound(sender, args[0]); return true }
            if (sender.uniqueId == target.first) {
                Msg.error(sender, "You cannot punish yourself.")
                return true
            }
            plugin.punishMenus.openCategories(sender, RulePunishManager.Target(target.first, target.second))
            return true
        }

        val type = args[1].lowercase()
        if (type in TYPES) return handleLegacy(sender, args, type)
        return handleRule(sender, args)
    }

    // ── Rule punishments ────────────────────────────────────────

    private fun handleRule(sender: CommandSender, args: Array<out String>): Boolean {
        if (rules.levelOf(sender) == null) { Msg.noPermission(sender); return true }
        val rule = rules.registry.find(args[1]) ?: run { unknownRule(sender, args[1]); return true }
        val target = resolveOfflinePlayer(args[0]) ?: run { Msg.playerNotFound(sender, args[0]); return true }
        val flags = parseFlags(sender, args.drop(2)) ?: return true
        rules.begin(sender, RulePunishManager.Target(target.first, target.second), rule, flags)
        return true
    }

    private fun handlePreview(sender: CommandSender, args: Array<out String>): Boolean {
        if (rules.levelOf(sender) == null) { Msg.noPermission(sender); return true }
        if (args.size < 3) { Msg.usage(sender, "/punish preview <player> <rule> [flags]"); return true }
        val rule = rules.registry.find(args[2]) ?: run { unknownRule(sender, args[2]); return true }
        val target = resolveOfflinePlayer(args[1]) ?: run { Msg.playerNotFound(sender, args[1]); return true }
        val flags = parseFlags(sender, args.drop(3)) ?: return true
        rules.preview(sender, RulePunishManager.Target(target.first, target.second), rule, flags)
        return true
    }

    private fun parseFlags(sender: CommandSender, tokens: List<String>): PunishFlags? =
        when (val parsed = PunishFlags.parse(tokens)) {
            is PunishFlags.Companion.Parsed.Error -> { Msg.error(sender, parsed.message); null }
            is PunishFlags.Companion.Parsed.Ok -> parsed.flags
        }

    private fun handleHistory(sender: CommandSender, args: Array<out String>): Boolean {
        if (rules.levelOf(sender) == null && !sender.hasPermission("joshymc.history")) { Msg.noPermission(sender); return true }
        if (args.size < 2) { Msg.usage(sender, "/punish history <player> [rule]"); return true }
        val target = resolveOfflinePlayer(args[1]) ?: run { Msg.playerNotFound(sender, args[1]); return true }
        val rule = args.getOrNull(2)?.let { raw -> rules.registry.find(raw) ?: run { unknownRule(sender, raw); return true } }
        rules.sendHistory(sender, RulePunishManager.Target(target.first, target.second), rule)
        return true
    }

    private fun handleRules(sender: CommandSender, args: Array<out String>): Boolean {
        if (rules.levelOf(sender) == null) { Msg.noPermission(sender); return true }
        val category = args.getOrNull(1)?.let { raw ->
            RuleCategory.fromId(raw) ?: run {
                Msg.error(sender, "Unknown category. Categories: ${RuleCategory.entries.joinToString(", ") { it.id }}")
                return true
            }
        }
        if (sender is Player) {
            if (category == null) plugin.punishMenus.openCategories(sender, null) else plugin.punishMenus.openCategory(sender, category, null)
            return true
        }
        for (c in category?.let { listOf(it) } ?: RuleCategory.entries) {
            sender.sendMessage(Component.text(c.displayName, NamedTextColor.GOLD))
            for (rule in rules.registry.inCategory(c)) {
                val tiers = rule.tiers?.joinToString(", ") { it.code() } ?: "informational"
                sender.sendMessage(Component.text("  #${rule.id} ${rule.keyword} - ${rule.displayName} [$tiers] ${rule.minLevel?.displayName ?: ""}", NamedTextColor.GRAY))
            }
        }
        return true
    }

    private fun unknownRule(sender: CommandSender, input: String) {
        val key = PunishRules.normalize(input)
        val close = rules.usableRules(sender).map { it.keyword }.filter { it.contains(key) || key.contains(it) }.take(5)
        Msg.error(sender, "Unknown rule '$input'." + if (close.isNotEmpty()) " Did you mean: ${close.joinToString(", ")}?" else " See /punish rules.")
    }

    // ── Legacy /punish <player> <type> ... ──────────────────────

    private fun handleLegacy(sender: CommandSender, args: Array<out String>, type: String): Boolean {
        if (!sender.hasPermission("joshymc.punish.$type")) {
            Msg.noPermission(sender)
            return true
        }

        val target = resolveOfflinePlayer(args[0])
        if (target == null) {
            Msg.playerNotFound(sender, args[0])
            return true
        }
        val (targetUuid, targetName) = target

        if (sender is Player && sender.uniqueId == targetUuid) {
            Msg.error(sender, "You cannot punish yourself.")
            return true
        }

        if (sender is Player) {
            val senderWeight = plugin.rankManager.getPlayerRankById(sender.uniqueId)?.weight ?: 0
            val targetWeight = plugin.rankManager.getPlayerRankById(targetUuid)?.weight ?: 0
            if (targetWeight > senderWeight) {
                Msg.error(sender, "You cannot punish a player with a higher rank than you.")
                return true
            }
        }

        val punisherUuid = (sender as? Player)?.uniqueId
        val punisherName = sender.name

        return when (type) {
            "ban" -> handleBan(sender, args, targetUuid, targetName, punisherUuid, punisherName)
            "tempban" -> handleTempban(sender, args, targetUuid, targetName, punisherUuid, punisherName)
            "tempmute" -> handleTempmute(sender, args, targetUuid, targetName, punisherUuid, punisherName)
            "unban" -> handleUnban(sender, args, targetUuid, targetName, punisherUuid, punisherName)
            "unmute" -> handleUnmute(sender, args, targetUuid, targetName, punisherUuid, punisherName)
            "warn" -> handleWarn(sender, args, targetUuid, targetName, punisherUuid, punisherName)
            "unwarn" -> handleUnwarn(sender, args, targetUuid, targetName, punisherUuid, punisherName)
            else -> true
        }
    }

    private fun handleBan(sender: CommandSender, args: Array<out String>, targetUuid: java.util.UUID, targetName: String, punisherUuid: java.util.UUID?, punisherName: String): Boolean {
        val reason = args.drop(2).joinToString(" ").trim()
        if (reason.isBlank()) {
            Msg.usage(sender, "/punish <player> ban <reason>")
            return true
        }

        val inserted = plugin.punishmentManager.ban(targetUuid, targetName, punisherName, punisherUuid, reason)
        Bukkit.getPlayer(targetUuid)?.kick(
            plugin.punishmentManager.buildBanMessage(inserted.id, targetUuid, reason, punisherName, null, null)
        )

        val msg = Component.text("$targetName has been permanently banned - $reason", NamedTextColor.RED)
        Msg.reply(sender, msg)
        notifyStaff(sender, msg)
        return true
    }

    private fun handleTempban(sender: CommandSender, args: Array<out String>, targetUuid: java.util.UUID, targetName: String, punisherUuid: java.util.UUID?, punisherName: String): Boolean {
        if (args.size < 3) {
            Msg.usage(sender, "/punish <player> tempban <duration> <reason>")
            return true
        }
        val durationMs = PunishmentManager.parseDuration(args[2])
        if (durationMs == null) {
            Msg.error(sender, "Invalid duration: ${args[2]} (e.g. 1d, 2h30m, 7d, 1w, 1mo)")
            return true
        }
        val reason = args.drop(3).joinToString(" ").trim()
        if (reason.isBlank()) {
            Msg.usage(sender, "/punish <player> tempban <duration> <reason>")
            return true
        }

        val inserted = plugin.punishmentManager.tempban(targetUuid, targetName, punisherName, punisherUuid, reason, durationMs)
        Bukkit.getPlayer(targetUuid)?.kick(
            plugin.punishmentManager.buildBanMessage(inserted.id, targetUuid, reason, punisherName, durationMs, inserted.expiresAt)
        )

        val durationStr = PunishmentManager.formatDuration(durationMs)
        val msg = Component.text("$targetName has been banned for $durationStr - $reason", NamedTextColor.RED)
        Msg.reply(sender, msg)
        notifyStaff(sender, msg)
        return true
    }

    private fun handleTempmute(sender: CommandSender, args: Array<out String>, targetUuid: java.util.UUID, targetName: String, punisherUuid: java.util.UUID?, punisherName: String): Boolean {
        if (args.size < 3) {
            Msg.usage(sender, "/punish <player> tempmute <duration> <reason>")
            return true
        }
        val durationMs = PunishmentManager.parseDuration(args[2])
        if (durationMs == null) {
            Msg.error(sender, "Invalid duration: ${args[2]} (e.g. 1d, 2h30m, 7d, 1w, 1mo)")
            return true
        }
        val reason = args.drop(3).joinToString(" ").trim()
        if (reason.isBlank()) {
            Msg.usage(sender, "/punish <player> tempmute <duration> <reason>")
            return true
        }

        plugin.punishmentManager.tempmute(targetUuid, targetName, punisherName, punisherUuid, reason, durationMs)

        val durationStr = PunishmentManager.formatDuration(durationMs)
        val msg = Component.text("$targetName has been muted for $durationStr - $reason", NamedTextColor.RED)
        Msg.reply(sender, msg)
        notifyStaff(sender, msg)

        Bukkit.getPlayer(targetUuid)?.let { online ->
            plugin.commsManager.send(
                online,
                Component.text("You have been muted for $durationStr! Reason: $reason", NamedTextColor.RED),
                CommunicationsManager.Category.ADMIN
            )
        }
        return true
    }

    private fun handleUnban(sender: CommandSender, args: Array<out String>, targetUuid: java.util.UUID, targetName: String, punisherUuid: java.util.UUID?, punisherName: String): Boolean {
        val reason = args.drop(2).joinToString(" ").trim()
        if (reason.isBlank()) {
            Msg.usage(sender, "/punish <player> unban <reason>")
            return true
        }
        if (plugin.punishmentManager.isBanned(targetUuid) == null) {
            Msg.error(sender, "$targetName is not currently banned.")
            return true
        }

        plugin.punishmentManager.unban(targetUuid, punisherName, punisherUuid, reason)
        val msg = Component.text("$targetName has been unbanned.", NamedTextColor.GREEN)
        Msg.reply(sender, msg)
        notifyStaff(sender, msg)
        return true
    }

    private fun handleUnmute(sender: CommandSender, args: Array<out String>, targetUuid: java.util.UUID, targetName: String, punisherUuid: java.util.UUID?, punisherName: String): Boolean {
        val reason = args.drop(2).joinToString(" ").trim()
        if (reason.isBlank()) {
            Msg.usage(sender, "/punish <player> unmute <reason>")
            return true
        }
        if (plugin.punishmentManager.isMuted(targetUuid) == null) {
            Msg.error(sender, "$targetName is not currently muted.")
            return true
        }

        plugin.punishmentManager.unmute(targetUuid, punisherName, punisherUuid, reason)
        val msg = Component.text("$targetName has been unmuted.", NamedTextColor.GREEN)
        Msg.reply(sender, msg)
        notifyStaff(sender, msg)

        Bukkit.getPlayer(targetUuid)?.let { online ->
            plugin.commsManager.send(online, Component.text("You have been unmuted.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        }
        return true
    }

    private fun handleWarn(sender: CommandSender, args: Array<out String>, targetUuid: java.util.UUID, targetName: String, punisherUuid: java.util.UUID?, punisherName: String): Boolean {
        val reason = args.drop(2).joinToString(" ").trim()
        if (reason.isBlank()) {
            Msg.usage(sender, "/punish <player> warn <reason>")
            return true
        }

        plugin.punishmentManager.warn(targetUuid, targetName, punisherName, punisherUuid, reason)
        val activeWarns = plugin.punishmentManager.getActiveWarnings(targetUuid).size

        val msg = Component.text("$targetName has been warned ($activeWarns active) - $reason", NamedTextColor.YELLOW)
        Msg.reply(sender, msg)
        notifyStaff(sender, msg)

        Bukkit.getPlayer(targetUuid)?.let { online ->
            plugin.commsManager.send(online, Component.text("You have been warned! Reason: $reason", NamedTextColor.YELLOW), CommunicationsManager.Category.ADMIN)
        }
        return true
    }

    private fun handleUnwarn(sender: CommandSender, args: Array<out String>, targetUuid: java.util.UUID, targetName: String, punisherUuid: java.util.UUID?, punisherName: String): Boolean {
        val activeWarnings = plugin.punishmentManager.getActiveWarnings(targetUuid)
        val explicitId = args.getOrNull(2)?.toIntOrNull()?.takeIf { id -> activeWarnings.any { it.id == id } }

        val reasonArgs = if (explicitId != null) args.drop(3) else args.drop(2)
        val reason = reasonArgs.joinToString(" ").trim()

        if (explicitId == null && activeWarnings.size > 1) {
            val ids = activeWarnings.joinToString(", ") { "#${it.id}" }
            sender.sendMessage(Component.text("$targetName has multiple active warnings: $ids", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("Specify which one: /punish $targetName unwarn <id> <reason>", NamedTextColor.YELLOW))
            return true
        }

        if (reason.isBlank()) {
            Msg.usage(sender, "/punish <player> unwarn [id] <reason>")
            return true
        }

        if (activeWarnings.isEmpty()) {
            Msg.error(sender, "$targetName has no active warnings.")
            return true
        }

        val warnId = explicitId ?: activeWarnings.first().id
        val removed = plugin.punishmentManager.unwarn(targetUuid, warnId, punisherName, punisherUuid, reason)
        if (!removed) {
            Msg.error(sender, "Warning #$warnId not found for $targetName.")
            return true
        }

        val remaining = plugin.punishmentManager.getActiveWarnings(targetUuid).size
        val msg = Component.text("Warning removed from $targetName ($remaining remaining)", NamedTextColor.GREEN)
        Msg.reply(sender, msg)
        notifyStaff(sender, msg)

        Bukkit.getPlayer(targetUuid)?.let { online ->
            plugin.commsManager.send(online, Component.text("A warning has been removed from your record.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        }
        return true
    }

    // ── Tab completion ──────────────────────────────────────────

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val staff = rules.levelOf(sender) != null
        fun matching(options: Iterable<String>, typed: String) = options.filter { it.startsWith(typed, ignoreCase = true) }
        fun ruleKeywords() = rules.usableRules(sender).map { it.keyword }

        if (args.size == 1) {
            val subs = SUBCOMMANDS.filter { sub ->
                when (sub) {
                    "reload" -> sender.hasPermission("joshymc.punish.reload")
                    "history" -> staff || sender.hasPermission("joshymc.history")
                    else -> staff
                }
            }
            return matching(subs, args[0]) + onlinePlayerNames(args[0])
        }
        when (args[0].lowercase()) {
            "history" -> return when (args.size) {
                2 -> onlinePlayerNames(args[1])
                3 -> matching(rules.registry.rules.filter { !it.informational }.map { it.keyword }, args[2])
                else -> emptyList()
            }
            "preview" -> return when {
                !staff -> emptyList()
                args.size == 2 -> onlinePlayerNames(args[1])
                args.size == 3 -> matching(ruleKeywords(), args[2])
                else -> matching(PunishFlags.FLAG_SUGGESTIONS, args.last())
            }
            "rules" -> return if (args.size == 2 && staff) matching(RuleCategory.entries.map { it.id }, args[1]) else emptyList()
            "audit" -> return if (args.size == 2 && staff) matching(listOf("verify"), args[1]) else emptyList()
            "confirm", "cancel", "overturn", "review", "reload" -> return emptyList()
        }

        if (args.size == 2) {
            val legacy = SUGGESTED_TYPES.filter { sender.hasPermission("joshymc.punish.$it") }
            return matching(ruleKeywords() + legacy, args[1])
        }
        val type = args[1].lowercase()
        if (type in TYPES) return legacyTabComplete(args, type)
        if (!staff || rules.registry.find(args[1]) == null) return emptyList()
        // Flags only come before the evidence; once evidence has started, stop suggesting them.
        val typedSoFar = args.drop(2).dropLast(1)
        val parsed = PunishFlags.parse(typedSoFar)
        if (parsed is PunishFlags.Companion.Parsed.Ok && parsed.flags.evidence != null) return emptyList()
        return matching(PunishFlags.FLAG_SUGGESTIONS, args.last())
    }

    private fun legacyTabComplete(args: Array<out String>, type: String): List<String> {
        if (args.size != 3) return emptyList()
        return when (type) {
            "tempban", "tempmute" -> TEMP_DURATIONS.filter { it.startsWith(args[2], ignoreCase = true) }
            "unwarn" -> {
                val target = resolveOfflinePlayer(args[0]) ?: return emptyList()
                plugin.punishmentManager.getActiveWarnings(target.first)
                    .map { it.id.toString() }
                    .filter { it.startsWith(args[2]) }
            }
            else -> emptyList()
        }
    }
}
