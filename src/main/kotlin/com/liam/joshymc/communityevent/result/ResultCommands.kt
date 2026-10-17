package com.liam.joshymc.communityevent.result

import com.liam.joshymc.Joshymc
import com.liam.joshymc.communityevent.EventPermissions
import com.liam.joshymc.communityevent.EventType
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/**
 * `/event result ...`, `/event rewards ...`, `/event history`, `/event hoststats` and
 * `/event stats` (issue #1199). Every subcommand checks its own permission; players use only
 * `/event stats`, which the event-world command lock still blocks inside the event world.
 */
class ResultCommands(
    private val plugin: Joshymc,
    private val results: EventResults,
    private val menu: ResultsMenu,
) {
    private val category = CommunicationsManager.Category.EVENT
    private val store get() = results.store

    val subcommands = listOf("result", "rewards", "history", "hoststats", "stats")

    /** [subcommands] plus their aliases, for dispatch. */
    val handled = subcommands + listOf("results", "reward")

    private val resultActions = listOf("confirm", "setwinner", "addwinner", "removewinner", "setplace", "clear", "syncrewards")
    private val rewardActions = listOf("add", "remove", "clear", "status", "distribute", "failed", "retry")

    fun usageLines(sender: CommandSender): List<String> = buildList {
        if (sender.hasPermission(EventPermissions.JOIN)) add("&e/event stats [player] &7- Your event statistics")
        if (!EventPermissions.canHost(sender)) return@buildList
        add("&e/event result [#id] &7- Review a finished event's result")
        add("&e/event result <#id> confirm &7- Finalize it and give the rewards")
        if (EventPermissions.canOverrideResults(sender)) {
            add("&e/event result <#id> setwinner|addwinner|removewinner <player> &7- Correct the winners")
            add("&e/event result <#id> setplace <first|second|third|none> <player> &7- Set a placement")
            add("&e/event result <#id> clear &7- Clear every placement")
        }
        add("&e/event rewards [type] &7- Show the reward presets")
        if (EventPermissions.canConfigureRewards(sender)) {
            add("&e/event rewards add <type> ${EventRewards.USAGE} &7- Add a reward")
            add("&e/event rewards remove <type> <line> | clear <type> &7- Remove rewards")
            add("&e/event result <#id> syncrewards &7- Use the current preset for an unconfirmed result")
        }
        add("&e/event rewards status|distribute <#id> &7- Payout status, queue missing payouts")
        if (EventPermissions.canRecoverRewards(sender)) add("&e/event rewards failed | retry <payout> [confirm] &7- Recover failed payouts")
        add("&e/event history [page] &7- Recent events")
        add("&e/event hoststats${if (EventPermissions.canViewOtherHosts(sender)) " [player]" else ""} &7- Hosting statistics")
    }

    fun handle(sender: CommandSender, args: Array<out String>) {
        when (args[0].lowercase()) {
            "result", "results" -> if (host(sender)) result(sender, args.drop(1))
            "rewards", "reward" -> if (host(sender)) rewards(sender, args.drop(1))
            "history" -> if (host(sender)) history(sender, args.getOrNull(1)?.toIntOrNull()?.minus(1) ?: 0)
            "hoststats" -> if (host(sender)) hostStats(sender, args.getOrNull(1))
            "stats" -> playerStats(sender, args.getOrNull(1))
        }
    }

    // ── /event result ─────────────────────────────────────

    private fun result(sender: CommandSender, rest: List<String>) {
        val explicitId = rest.firstOrNull()?.takeIf { it.removePrefix("#").toIntOrNull() != null }
        val tail = if (explicitId != null) rest.drop(1) else rest
        val record = results.resolve(explicitId) ?: run {
            Msg.error(sender, if (explicitId != null) "No recorded event #${explicitId.removePrefix("#")}." else "No result is waiting for confirmation. See /event history.", category)
            return
        }
        val action = tail.firstOrNull()?.lowercase()
        if (action != null && explicitId == null && action in resultActions) {
            // Changes always name the event, so a result that finished in the meantime can't be confirmed by mistake.
            Msg.error(sender, "Name the event: /event result ${record.id} $action ... (#${record.id} is the newest awaiting confirmation).", category)
            return
        }
        val reply: EventResults.Action = when (action) {
            null -> {
                if (sender is Player) menu.openResult(sender, record.id) else showResult(sender, record)
                return
            }
            "confirm" -> results.confirm(sender, record)
            "setwinner", "addwinner", "removewinner" -> {
                val name = tail.getOrNull(1) ?: run { Msg.usage(sender, "/event result <#id> $action <player>", category); return }
                when (action) {
                    "setwinner" -> results.setPlacement(sender, record, name, Placement.FIRST, sole = true)
                    "addwinner" -> results.setPlacement(sender, record, name, Placement.FIRST)
                    else -> {
                        val player = results.findParticipant(record, name)
                        if (player != null && player.placement != Placement.FIRST) EventResults.Action(false, "${player.name} isn't a winner of #${record.id}.")
                        else results.setPlacement(sender, record, name, null)
                    }
                }
            }
            "setplace" -> {
                val placeArg = tail.getOrNull(1)
                val name = tail.getOrNull(2)
                if (placeArg == null || name == null) { Msg.usage(sender, "/event result <#id> setplace <first|second|third|none> <player>", category); return }
                val place = if (placeArg.equals("none", true)) null else Placement.of(placeArg)?.takeIf { it != Placement.PARTICIPANT }
                    ?: run { Msg.error(sender, "Placement must be first, second, third or none.", category); return }
                results.setPlacement(sender, record, name, place)
            }
            "clear" -> results.clearPlacements(sender, record)
            "syncrewards" -> results.syncRewards(sender, record)
            else -> { Msg.usage(sender, "/event result [#id] | /event result <#id> <${resultActions.joinToString("|")}>", category); return }
        }
        send(sender, reply)
    }

    private fun showResult(sender: CommandSender, record: EventRecord) {
        val players = store.players(record.id)
        header(sender, "${results.typeName(record.type)} #${record.id}")
        row(sender, "Status", record.status.label)
        row(sender, "Result", record.outcome.label)
        record.summary?.let { row(sender, "Summary", it) }
        row(sender, "Host", record.hostName)
        row(sender, "Arena", record.arenaName ?: "none")
        row(sender, "Participants", record.participantCount.toString())
        for (place in Placement.placed) {
            val names = players.filter { it.placement == place }.map { it.name }
            if (names.isNotEmpty()) row(sender, place.label, names.joinToString(", "), NamedTextColor.GOLD)
        }
        val rewards = store.rewards(record.id)
        row(sender, "Rewards", rewards.joinToString(", ") { results.describe(it) }.ifEmpty { "none" })
        if (record.status == RecordStatus.AWAITING_CONFIRMATION) {
            Msg.reply(sender, Component.text("Finalize with ", NamedTextColor.YELLOW)
                .append(Component.text("/event result ${record.id} confirm", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.suggestCommand("/event result ${record.id} confirm"))), category)
        }
    }

    // ── /event rewards ────────────────────────────────────

    private fun rewards(sender: CommandSender, rest: List<String>) {
        val action = rest.firstOrNull()?.lowercase()
        when {
            action == null -> if (sender is Player) menu.openPresets(sender) else EventType.entries.forEach { showPreset(sender, it) }
            EventType.fromId(action) != null -> showPreset(sender, EventType.fromId(action)!!)
            action == "add" -> {
                val type = typeArg(sender, rest.getOrNull(1), "/event rewards add <type> ${EventRewards.USAGE}") ?: return
                send(sender, results.addPreset(sender, type, rest.drop(2)))
            }
            action == "remove" -> {
                val type = typeArg(sender, rest.getOrNull(1), "/event rewards remove <type> <line>") ?: return
                val line = rest.getOrNull(2)?.toIntOrNull() ?: run { Msg.usage(sender, "/event rewards remove <type> <line>", category); return }
                send(sender, results.removePreset(sender, type, line))
            }
            action == "clear" -> {
                val type = typeArg(sender, rest.getOrNull(1), "/event rewards clear <type>") ?: return
                send(sender, results.clearPreset(sender, type))
            }
            action == "status" || action == "distribute" -> {
                val record = results.resolve(rest.getOrNull(1) ?: run { Msg.usage(sender, "/event rewards $action <#id>", category); return })
                    ?: run { Msg.error(sender, "No recorded event #${rest[1].removePrefix("#")}.", category); return }
                if (action == "distribute") send(sender, results.distributeCommand(sender, record))
                else if (sender is Player) menu.openPayouts(sender, record.id)
                else showPayouts(sender, store.payouts(record.id), "Rewards of #${record.id}")
            }
            action == "failed" -> {
                if (!EventPermissions.canRecoverRewards(sender)) { Msg.noPermission(sender, category); return }
                showPayouts(sender, store.failedPayouts(20), "Failed event rewards")
            }
            action == "retry" -> {
                val id = rest.getOrNull(1)?.removePrefix("#")?.toIntOrNull() ?: run { Msg.usage(sender, "/event rewards retry <payout> [confirm]", category); return }
                send(sender, results.retry(sender, id, rest.getOrNull(2).equals("confirm", ignoreCase = true)))
            }
            else -> Msg.usage(sender, "/event rewards [type|${rewardActions.joinToString("|")}]", category)
        }
    }

    private fun showPreset(sender: CommandSender, type: EventType) {
        val entries = results.presetFor(type)
        header(sender, "${type.displayName} Rewards")
        if (entries.isEmpty()) Msg.reply(sender, Component.text("No rewards configured.", NamedTextColor.GRAY), category)
        entries.forEachIndexed { i, entry ->
            val problem = results.validity(entry)
            Msg.reply(sender, Component.text("${i + 1}. ", NamedTextColor.GRAY)
                .append(Component.text(results.describe(entry), if (problem == null) NamedTextColor.AQUA else NamedTextColor.RED))
                .append(Component.text(problem?.let { " (invalid: $it, skipped)" } ?: "", NamedTextColor.RED)), category)
        }
    }

    private fun showPayouts(sender: CommandSender, payouts: List<Payout>, title: String) {
        header(sender, title)
        if (payouts.isEmpty()) Msg.reply(sender, Component.text("None.", NamedTextColor.GRAY), category)
        for (p in payouts.take(50)) {
            val color = when (p.state) {
                PayoutState.DELIVERED -> NamedTextColor.GREEN
                PayoutState.FAILED -> NamedTextColor.RED
                PayoutState.CANCELLED -> NamedTextColor.GRAY
                else -> NamedTextColor.YELLOW
            }
            Msg.reply(sender, Component.text("#${p.id} ", NamedTextColor.GRAY)
                .append(Component.text("${p.recipientName}: ${p.entry?.let { results.describe(it) } ?: "?"} ", NamedTextColor.WHITE))
                .append(Component.text(p.state.label, color))
                .append(Component.text(" (event #${p.eventId}${p.deliveryId?.let { ", delivery #$it" } ?: ""})${p.note?.let { " $it" } ?: ""}", NamedTextColor.DARK_GRAY)), category)
        }
        if (payouts.size > 50) Msg.reply(sender, Component.text("...and ${payouts.size - 50} more.", NamedTextColor.GRAY), category)
    }

    // ── /event history, hoststats, stats ──────────────────

    private fun history(sender: CommandSender, page: Int) {
        if (sender is Player) { menu.openHistory(sender, page.coerceAtLeast(0)); return }
        val perPage = 10
        results.async({ store.page(page.coerceAtLeast(0) * perPage, perPage).let { it to store.winnerNames(it.map { r -> r.id }) } }) { (records, winners) ->
            header(sender, "Event History (page ${page.coerceAtLeast(0) + 1})")
            if (records.isEmpty()) Msg.reply(sender, Component.text("No events on this page.", NamedTextColor.GRAY), category)
            for (r in records) {
                val w = winners[r.id].orEmpty().joinToString(", ").ifEmpty { "no winner" }
                Msg.reply(sender, Component.text("#${r.id} ${results.typeName(r.type)} ", NamedTextColor.GOLD)
                    .append(Component.text("by ${r.hostName}, ${r.participantCount} player(s), $w, ${r.status.label}", NamedTextColor.GRAY)), category)
            }
        }
    }

    private fun hostStats(sender: CommandSender, name: String?) {
        val target: Pair<UUID, String> = if (name == null) {
            val player = sender as? Player ?: run { Msg.usage(sender, "/event hoststats <player>", category); return }
            player.uniqueId to player.name
        } else {
            if (!EventPermissions.canViewOtherHosts(sender) && !(sender is Player && sender.name.equals(name, true))) {
                Msg.noPermission(sender, category); return
            }
            val resolved = plugin.playerResolver.resolve(name) ?: run { Msg.playerNotFound(sender, name, category); return }
            resolved.uniqueId to (resolved.name ?: name)
        }
        if (sender is Player) { menu.openHostStats(sender, target.first, target.second); return }
        results.async({ store.hostStats(target.first) }) { s ->
            header(sender, "Hosting Statistics: ${target.second}")
            row(sender, "Total Events Hosted", s.total.toString())
            row(sender, "Completed Events", s.completed.toString())
            row(sender, "Cancelled Events", s.cancelled.toString())
            row(sender, "Total Participants", "${s.participants} (average ${"%.1f".format(java.util.Locale.ENGLISH, s.average)})")
            row(sender, "Favorite Event Type", s.favorite?.let { results.typeName(it) } ?: "none")
            if (s.byType.isNotEmpty()) row(sender, "By type", s.byType.entries.joinToString(", ") { "${results.typeName(it.key)} ${it.value}" })
            row(sender, "Last Event Hosted", s.last?.let { "${results.typeName(it.type)} #${it.id}" } ?: "none")
        }
    }

    /** Players see their own statistics; staff (hosts) may look anyone up by exact name. */
    private fun playerStats(sender: CommandSender, name: String?) {
        if (!sender.hasPermission(EventPermissions.JOIN) && !EventPermissions.canHost(sender)) { Msg.noPermission(sender, category); return }
        val target: Pair<UUID, String> = if (name == null || (sender is Player && sender.name.equals(name, true))) {
            val player = sender as? Player ?: run { Msg.usage(sender, "/event stats <player>", category); return }
            player.uniqueId to player.name
        } else {
            if (!EventPermissions.canHost(sender)) { Msg.noPermission(sender, category); return }
            val resolved = plugin.playerResolver.resolve(name) ?: run { Msg.playerNotFound(sender, name, category); return }
            resolved.uniqueId to (resolved.name ?: name)
        }
        results.async({ store.playerStats(target.first) }) { s ->
            if (sender is Player && !sender.isOnline) return@async
            header(sender, "Event Stats: ${target.second}")
            row(sender, "Events Joined", s.joined.toString())
            row(sender, "Events Completed", s.completed.toString())
            row(sender, "Event Wins", s.wins.toString(), NamedTextColor.GOLD)
            if (s.winsByType.isNotEmpty()) row(sender, "Wins by type", s.winsByType.entries.joinToString(", ") { "${results.typeName(it.key)} ${it.value}" })
            row(sender, "Rewards Received", s.rewardsReceived.toString())
            row(sender, "Most Recent Event", s.last?.let { r ->
                "${results.typeName(r.type)}, " + if (sender is Player) menu.date(sender, r.endedAt) else java.time.Instant.ofEpochMilli(r.endedAt).toString()
            } ?: "none")
        }
    }

    // ── Tab completion ────────────────────────────────────

    fun tab(sender: CommandSender, args: Array<out String>): List<String> {
        val sub = args[0].lowercase()
        val last = args.last().lowercase()
        val options: List<String> = when (sub) {
            "result", "results" -> if (!EventPermissions.canHost(sender)) emptyList() else when (args.size) {
                2 -> store.awaiting(10).map { it.id.toString() }
                else -> {
                    val action = args.getOrNull(2)?.lowercase()
                    when {
                        args[1].removePrefix("#").toIntOrNull() == null -> emptyList()
                        args.size == 3 -> resultActions
                        action == "setplace" && args.size == 4 -> listOf("first", "second", "third", "none")
                        action in setOf("setwinner", "addwinner", "removewinner") && args.size == 4 ||
                            action == "setplace" && args.size == 5 -> participantNames(args[1])
                        else -> emptyList()
                    }
                }
            }
            "rewards", "reward" -> if (!EventPermissions.canHost(sender)) emptyList() else when (args.size) {
                2 -> rewardActions + EventType.ids
                3 -> when (args[1].lowercase()) { "add", "remove", "clear" -> EventType.ids; else -> emptyList() }
                4 -> if (args[1].equals("add", true)) Placement.ids else emptyList()
                5 -> if (args[1].equals("add", true)) RewardKind.ids else emptyList()
                6 -> if (args[1].equals("add", true) && args[4].equals("cratekey", true)) plugin.crateManager.getCrateTypes() else emptyList()
                else -> emptyList()
            }
            "hoststats" -> if (args.size == 2 && EventPermissions.canViewOtherHosts(sender)) Bukkit.getOnlinePlayers().map { it.name } else emptyList()
            "stats" -> if (args.size == 2 && EventPermissions.canHost(sender)) Bukkit.getOnlinePlayers().map { it.name } else emptyList()
            else -> emptyList()
        }
        return options.filter { it.lowercase().startsWith(last) }
    }

    private fun participantNames(idArg: String): List<String> {
        val record = results.resolve(idArg) ?: return emptyList()
        return store.players(record.id).map { it.name }
    }

    // ── Helpers ───────────────────────────────────────────

    private fun typeArg(sender: CommandSender, arg: String?, usage: String): EventType? {
        if (arg == null) { Msg.usage(sender, usage, category); return null }
        return EventType.fromId(arg) ?: run {
            Msg.error(sender, "Unknown event type '$arg'. Choose one of: ${EventType.ids.joinToString(", ")}.", category)
            null
        }
    }

    private fun host(sender: CommandSender): Boolean {
        if (EventPermissions.canHost(sender)) return true
        Msg.noPermission(sender, category)
        return false
    }

    private fun send(sender: CommandSender, action: EventResults.Action) =
        if (action.ok) Msg.success(sender, action.message, category) else Msg.error(sender, action.message, category)

    private fun header(sender: CommandSender, text: String) =
        Msg.reply(sender, Component.text("=== $text ===", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true), category)

    private fun row(sender: CommandSender, label: String, value: String, color: NamedTextColor = NamedTextColor.WHITE) =
        Msg.reply(sender, Component.text("$label: ", NamedTextColor.GRAY).append(Component.text(value, color)), category)
}
