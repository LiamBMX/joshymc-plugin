package com.liam.joshymc.advent

import com.liam.joshymc.Joshymc
import com.liam.joshymc.advent.AdventRules.Availability
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.staff.AdminAudit
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `/advent` (aliases `/adventcalendar`, `/christmas`; issue #1424): opens the calendar. An NPC
 * opens it the same way (`/npc setcommand <id> advent`, run as the clicking player), so the NPC
 * path has exactly the command's checks. Staff tools live under `/advent admin`:
 * `joshymc.advent.admin` for status/history/failed/reload/setnpc and `joshymc.advent.admin.recover`
 * for anything that can make a reward deliverable (reissue, free passes). Both default to op.
 */
class AdventCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val manager get() = plugin.adventCalendarManager
    private val cat = CommunicationsManager.Category.EVENT

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty() && args[0].equals("admin", ignoreCase = true)) {
            if (!sender.hasPermission(ADMIN)) return true.also { Msg.noPermission(sender, cat) }
            admin(sender, args.drop(1))
            return true
        }
        val player = sender as? Player ?: return true.also { Msg.playersOnly(sender) }
        if (!player.hasPermission(USE)) return true.also { Msg.noPermission(player, cat) }
        AdventMenu(plugin).open(player)
        return true
    }

    private fun admin(sender: CommandSender, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "status" -> status(sender)
            "history" -> history(sender, args)
            "failed" -> failed(sender)
            "reissue" -> recover(sender) { reissue(sender, args) }
            "grantfree" -> recover(sender) { freePass(sender, args, grant = true) }
            "revokefree" -> recover(sender) { freePass(sender, args, grant = false) }
            "reload" -> {
                manager.reload()
                AdminAudit.log(plugin, sender, "ADVENT_RELOAD")
                if (manager.settings == null) Msg.error(sender, "advent-calendar.yml has problems (see the console); the calendar stays closed.", cat)
                else Msg.success(sender, "Advent Calendar settings reloaded.", cat)
            }
            "setnpc" -> setNpc(sender, args)
            else -> Msg.usage(sender, "/advent admin <status|history|failed|reissue|grantfree|revokefree|reload|setnpc>", cat)
        }
    }

    private inline fun recover(sender: CommandSender, block: () -> Unit) {
        if (!sender.hasPermission(RECOVER)) Msg.noPermission(sender, cat) else block()
    }

    private fun gamemode(sender: CommandSender): ServerGamemode? {
        val gm = plugin.gamemodeLockManager.gamemodeOf(sender)
        if (!manager.runsHere(gm)) {
            Msg.error(sender, "No ${gm.displayName} Advent Calendar runs on this server.", cat)
            return null
        }
        return gm
    }

    private fun currentYear(): Int = when (val a = manager.availability()) {
        is Availability.Open -> a.year
        else -> Instant.now().atZone(manager.settings?.schedule?.zone ?: java.time.ZoneId.of("America/New_York")).year
    }

    private fun status(sender: CommandSender) {
        val gm = gamemode(sender) ?: return
        val s = manager.settings
        reply(sender, "Advent Calendar (${gm.displayName})", NamedTextColor.GOLD)
        if (s == null) {
            reply(sender, "advent-calendar.yml is INVALID, so the calendar is closed:", NamedTextColor.RED)
            manager.problems.take(10).forEach { reply(sender, " - $it", NamedTextColor.RED) }
            return
        }
        val a = manager.availability()
        reply(sender, "enabled: ${s.enabled}, schedule.automatic: ${s.schedule.automatic}, manual-override: ${s.schedule.overrideEnabled}" +
            (if (s.schedule.overrideEnabled) " (year ${s.schedule.overrideYear.takeIf { it > 0 } ?: "current"}, day ${s.schedule.overrideDay.takeIf { it > 0 } ?: "by date"})" else ""), NamedTextColor.GRAY)
        reply(sender, "timezone: ${s.schedule.zone.id}, missed days: ${if (s.catchUpEnabled) manager.credits(s.catchUpCost) else "off"}", NamedTextColor.GRAY)
        reply(sender, "${gm.id} calendar: ${if (s.calendar(gm)?.enabled == true) "enabled" else "disabled"}", NamedTextColor.GRAY)
        when (a) {
            Availability.Disabled -> reply(sender, "Status: DISABLED", NamedTextColor.RED)
            is Availability.Closed -> reply(sender, "Status: CLOSED (${a.reason.name})${a.opensAt?.let { ", opens ${manager.dateText(it)}" } ?: ""}", NamedTextColor.YELLOW)
            is Availability.Open -> {
                reply(sender, "Status: OPEN for ${a.year}${if (a.manual) " (manual override)" else ""}, today = day ${a.today}" +
                    (a.nextUnlockAt?.let { ", next unlock ${manager.dateText(it)}" } ?: ""), NamedTextColor.GREEN)
                reply(sender, "Claims this year: ${manager.store.claimCount(gm.id, a.year)}", NamedTextColor.GRAY)
            }
        }
        val counts = manager.store.stateCounts(gm.id)
        reply(sender, "Reward rows: ${AdventStore.RewardState.entries.joinToString(", ") { "${it.name} ${counts[it] ?: 0}" }}", NamedTextColor.GRAY)
        val unmapped = s.placeholders.filterValues { it.isBlank() }.keys
        if (unmapped.isNotEmpty()) reply(sender, "Placeholders not set up: ${unmapped.joinToString(", ")}", NamedTextColor.YELLOW)
    }

    private fun target(sender: CommandSender, name: String?): Pair<UUID, String>? {
        if (name == null) return null
        val p = plugin.playerResolver.resolve(name) ?: return null.also { Msg.playerNotFound(sender, name, cat) }
        return p.uniqueId to (p.name ?: name)
    }

    private fun history(sender: CommandSender, args: List<String>) {
        if (args.size < 2) return Msg.usage(sender, "/advent admin history <player> [year]", cat)
        val gm = gamemode(sender) ?: return
        val (uuid, name) = target(sender, args[1]) ?: return
        val year = args.getOrNull(2)?.toIntOrNull()
        val claims = manager.store.claims(gm.id, uuid, year)
        reply(sender, "$name's ${gm.displayName} Advent claims${year?.let { " ($it)" } ?: ""}: ${claims.size}", NamedTextColor.GOLD)
        for (c in claims.takeLast(30)) {
            reply(sender, "${c.year} day ${c.day}: ${c.type.name}${if (c.creditsPaid > 0) " (${manager.credits(c.creditsPaid)})" else ""} at ${TIME.format(Instant.ofEpochMilli(c.claimedAt).atZone(manager.settings?.schedule?.zone ?: java.time.ZoneId.systemDefault()))}", NamedTextColor.YELLOW)
            for (r in manager.store.rewardsOf(c.id)) {
                val delivery = r.deliveryId?.let { plugin.purchaseFulfillmentManager.store.get(it.toInt()) }
                val state = when (r.state) {
                    AdventStore.RewardState.QUEUED -> "delivery #${r.deliveryId} ${delivery?.state?.name ?: "?"}"
                    AdventStore.RewardState.WAITING -> "WAITING: ${r.waitingReason ?: "not buildable yet"}"
                    AdventStore.RewardState.READY -> "READY (queued within a minute)"
                }
                reply(sender, "   #${r.id} ${r.label} - $state", NamedTextColor.GRAY)
            }
        }
    }

    private fun failed(sender: CommandSender) {
        val gm = gamemode(sender) ?: return
        val failed = manager.store.failedDeliveries(gm.id)
        val waiting = manager.store.rewardsIn(gm.id, AdventStore.RewardState.WAITING, null, 2000)
        reply(sender, "Failed Advent deliveries: ${failed.size}", if (failed.isEmpty()) NamedTextColor.GREEN else NamedTextColor.RED)
        failed.take(20).forEach { (r, why) ->
            reply(sender, " #${r.id} ${r.playerName} ${r.year} day ${r.day}: ${r.label} (delivery #${r.deliveryId} $why) -> /advent admin reissue ${r.id}", NamedTextColor.GRAY)
        }
        reply(sender, "Waiting rewards (not buildable on this server yet): ${waiting.size}", NamedTextColor.YELLOW)
        waiting.groupBy { it.waitingReason ?: "?" }.entries.take(10).forEach { (why, rows) -> reply(sender, " ${rows.size}x $why", NamedTextColor.GRAY) }
    }

    private fun reissue(sender: CommandSender, args: List<String>) {
        val id = args.getOrNull(1)?.toLongOrNull() ?: return Msg.usage(sender, "/advent admin reissue <reward id>", cat)
        when (val r = manager.reissue(id, sender.name)) {
            is AdventCalendarManager.ReissueOutcome.Queued -> Msg.success(sender, "Reward #$id is queued as delivery #${r.deliveryId}.", cat)
            is AdventCalendarManager.ReissueOutcome.Retried -> Msg.success(sender, "Delivery #${r.deliveryId} retried: now ${r.state}.", cat)
            is AdventCalendarManager.ReissueOutcome.Nothing -> Msg.error(sender, r.reason, cat)
        }
        AdminAudit.log(plugin, sender, "ADVENT_REISSUE", details = "reward=$id")
    }

    private fun freePass(sender: CommandSender, args: List<String>, grant: Boolean) {
        val usage = if (grant) "/advent admin grantfree <player|*> <day> [year] [reason]" else "/advent admin revokefree <player|*> <day> [year]"
        if (args.size < 3) return Msg.usage(sender, usage, cat)
        val gm = gamemode(sender) ?: return
        val everyone = args[1] == "*"
        val target = if (everyone) null else (target(sender, args[1]) ?: return)
        val day = args[2].toIntOrNull()?.takeIf { it in 1..AdventRules.DAYS } ?: return Msg.error(sender, "Day must be 1 to ${AdventRules.DAYS}.", cat)
        val yearArg = args.getOrNull(3)?.toIntOrNull()
        val year = yearArg ?: currentYear()
        val reason = args.drop(if (yearArg != null) 4 else 3).joinToString(" ").ifBlank { null }
        val who = if (everyone) "everyone" else target!!.second
        val now = System.currentTimeMillis()
        val changed = if (grant) manager.store.grantFreePass(gm.id, year, day, target?.first, sender.name, reason, now)
        else manager.store.revokeFreePass(gm.id, year, day, target?.first, sender.name, now)
        AdminAudit.log(plugin, sender, if (grant) "ADVENT_FREE_PASS" else "ADVENT_FREE_PASS_REVOKE", target?.first, who,
            "gamemode=${gm.id} year=$year day=$day${reason?.let { " reason=$it" } ?: ""}")
        when {
            grant && changed -> Msg.success(sender, "$who can now recover ${gm.displayName} $year day $day for free (only if it was missed; never twice).", cat)
            grant -> Msg.error(sender, "$who already has a free pass for $year day $day.", cat)
            changed -> Msg.success(sender, "Removed $who's free pass for $year day $day.", cat)
            else -> Msg.error(sender, "$who has no free pass for $year day $day.", cat)
        }
    }

    private fun setNpc(sender: CommandSender, args: List<String>) {
        val id = args.getOrNull(1)?.lowercase() ?: return Msg.usage(sender, "/advent admin setnpc <npc id>", cat)
        if (id !in plugin.npcManager.getNPCIds()) return Msg.error(sender, "No NPC '$id'. Create one with /npc create $id Advent Calendar.", cat)
        plugin.npcManager.setCommand(id, "advent")
        AdminAudit.log(plugin, sender, "ADVENT_SET_NPC", details = "npc=$id")
        Msg.success(sender, "NPC '$id' now opens the Advent Calendar for whoever clicks it (runs /advent as that player).", cat)
    }

    private fun reply(sender: CommandSender, text: String, color: NamedTextColor) = Msg.reply(sender, Component.text(text, color), cat)

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission(ADMIN)) return emptyList()
        return when (args.size) {
            1 -> listOf("admin").filter { it.startsWith(args[0].lowercase()) }
            2 -> if (args[0].equals("admin", true)) ADMIN_SUBS.filter { it.startsWith(args[1].lowercase()) } else emptyList()
            3 -> when (args[1].lowercase()) {
                "history" -> plugin.server.onlinePlayers.map { it.name }.filter { it.startsWith(args[2], true) }
                "grantfree", "revokefree" -> (listOf("*") + plugin.server.onlinePlayers.map { it.name }).filter { it.startsWith(args[2], true) }
                "setnpc" -> plugin.npcManager.getNPCIds().filter { it.startsWith(args[2].lowercase()) }
                else -> emptyList()
            }
            4 -> if (args[1].lowercase() in setOf("grantfree", "revokefree")) (1..AdventRules.DAYS).map { it.toString() }.filter { it.startsWith(args[3]) } else emptyList()
            else -> emptyList()
        }
    }

    companion object {
        const val USE = "joshymc.advent.use"
        const val ADMIN = "joshymc.advent.admin"
        const val RECOVER = "joshymc.advent.admin.recover"
        private val ADMIN_SUBS = listOf("status", "history", "failed", "reissue", "grantfree", "revokefree", "reload", "setnpc")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm z")
    }
}
