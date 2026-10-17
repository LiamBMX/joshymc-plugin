package com.liam.joshymc.communityevent.result

import com.liam.joshymc.Joshymc
import com.liam.joshymc.communityevent.EventPermissions
import com.liam.joshymc.communityevent.EventType
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The Event Results, Reward Status, Event History, Hosting Statistics and Reward Presets
 * sections of the Event Manager (issue #1199).
 *
 * Every click re-checks the viewer's permission and acts on fresh database state, so a stale
 * menu can't confirm twice or edit a finalized result. Lists are loaded off the main thread
 * one page at a time.
 */
class ResultsMenu(
    private val plugin: Joshymc,
    private val results: EventResults,
    private val backToMain: (Player) -> Unit,
) {
    private val category = CommunicationsManager.Category.EVENT
    private val store get() = results.store

    companion object {
        private const val SIZE = 54
        private const val PAGE = 45
        private const val PLAYER_SLOTS = 36
        private const val SLOT_REWARDS = 2
        private const val SLOT_INFO = 4
        private const val SLOT_ACTION = 6
        private const val SLOT_OTHERS = 8
        private val DATE = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm", Locale.ENGLISH)
    }

    // ── Event Results ─────────────────────────────────────

    private data class ResultView(
        val record: EventRecord?,
        val players: List<RecordPlayer>,
        val rewards: List<RewardEntry>,
        val summary: RewardSummary?,
        val awaiting: List<EventRecord>,
    )

    /** The result to review: [eventId], or the newest one awaiting confirmation. */
    fun openResult(player: Player, eventId: Int? = null, page: Int = 0) {
        if (!guard(player)) return
        results.async({
            val record = if (eventId != null) store.get(eventId) else store.awaiting(1).firstOrNull()
            ResultView(
                record,
                record?.let { store.players(it.id) } ?: emptyList(),
                record?.let { store.rewards(it.id) } ?: emptyList(),
                record?.let { store.rewardSummaries(listOf(it.id))[it.id] },
                store.awaiting(EventResults.MAX_AWAITING_LIST),
            )
        }) { view -> if (player.isOnline) renderResult(player, view, page) }
    }

    private fun renderResult(player: Player, view: ResultView, page: Int) {
        val record = view.record
        val gui = CustomGui(title(if (record == null) "Event Results" else "${results.typeName(record.type)} #${record.id}"), SIZE)
        if (record == null) {
            gui.setItem(22, GuiStyle.item(Material.BOOK, Component.text("No results to review", NamedTextColor.GOLD), listOf(
                Component.text("Finished events wait here until staff", NamedTextColor.GRAY),
                Component.text("confirm them. Nothing is awaiting now.", NamedTextColor.GRAY),
                Component.empty(),
                Component.text("Click to open Event History", NamedTextColor.YELLOW),
            ))) { p, _ -> GuiStyle.clickSound(p); openHistory(p) }
            footer(gui) { backToMain(it) }
            plugin.guiManager.open(player, gui)
            return
        }
        val awaiting = record.status == RecordStatus.AWAITING_CONFIRMATION
        val canOverride = awaiting && EventPermissions.canOverrideResults(player)

        gui.setItem(SLOT_INFO, recordItem(player, record, view.players, view.summary))
        gui.setItem(SLOT_REWARDS, rewardsItem(view.rewards, awaiting && EventPermissions.canConfigureRewards(player))) { p, _ ->
            if (!guard(p)) return@setItem
            if (store.get(record.id)?.status != RecordStatus.AWAITING_CONFIRMATION || !EventPermissions.canConfigureRewards(p)) return@setItem
            report(p, results.syncRewards(p, record))
            openResult(p, record.id, page)
        }

        when (record.status) {
            RecordStatus.AWAITING_CONFIRMATION -> gui.setItem(SLOT_ACTION, GuiStyle.item(Material.LIME_CONCRETE, Component.text("Confirm Result", NamedTextColor.GREEN), listOf(
                Component.text("Finalize the winners and give the rewards.", NamedTextColor.GRAY),
                Component.text("The result can't change afterwards.", NamedTextColor.GRAY),
                Component.empty(),
                Component.text("Click to review and confirm", NamedTextColor.YELLOW),
            ))) { p, _ -> if (guard(p)) { GuiStyle.clickSound(p); openConfirm(p, record.id) } }
            RecordStatus.CONFIRMED -> gui.setItem(SLOT_ACTION, GuiStyle.item(Material.CHEST_MINECART, Component.text("Reward Status", NamedTextColor.GOLD), listOf(
                Component.text(view.summary?.label() ?: "No rewards", NamedTextColor.WHITE),
                Component.empty(),
                Component.text("Click to see every payout", NamedTextColor.YELLOW),
            ))) { p, _ -> if (guard(p)) { GuiStyle.clickSound(p); openPayouts(p, record.id) } }
            RecordStatus.CANCELLED -> gui.setItem(SLOT_ACTION, GuiStyle.item(Material.GRAY_CONCRETE, Component.text("Cancelled", NamedTextColor.GRAY), listOf(
                Component.text("Cancelled events have no winners", NamedTextColor.GRAY),
                Component.text("and give no rewards.", NamedTextColor.GRAY),
            )))
        }

        val others = view.awaiting.filter { it.id != record.id }
        if (others.isNotEmpty()) {
            gui.setItem(SLOT_OTHERS, GuiStyle.item(Material.WRITABLE_BOOK, Component.text("Other Results Awaiting", NamedTextColor.YELLOW),
                others.take(8).map { Component.text("#${it.id} ${results.typeName(it.type)} - ${it.hostName}", NamedTextColor.GRAY) } +
                    listOf(Component.empty(), Component.text("Click to review the next one", NamedTextColor.YELLOW)),
            )) { p, _ -> GuiStyle.pageSound(p); openResult(p, others.first().id) }
        }

        val pages = ((view.players.size + PLAYER_SLOTS - 1) / PLAYER_SLOTS).coerceAtLeast(1)
        val current = page.coerceIn(0, pages - 1)
        view.players.drop(current * PLAYER_SLOTS).take(PLAYER_SLOTS).forEachIndexed { i, rp ->
            gui.setItem(9 + i, playerItem(rp, canOverride)) { p, event ->
                if (!guard(p) || !canOverride) return@setItem
                val fresh = store.get(record.id) ?: return@setItem
                val next = if (event.isRightClick) when (rp.placement) {
                    Placement.SECOND -> Placement.THIRD
                    Placement.THIRD -> null
                    else -> Placement.SECOND
                } else if (rp.placement == Placement.FIRST) null else Placement.FIRST
                report(p, results.setPlacement(p, fresh, rp, next))
                openResult(p, record.id, current)
            }
        }
        if (view.players.isEmpty()) {
            gui.setItem(22, GuiStyle.item(Material.BARRIER, Component.text("No participants recorded", NamedTextColor.GRAY)))
        }
        if (current > 0) gui.setItem(GuiStyle.prevSlot(SIZE), GuiStyle.previousPage(current, pages)) { p, _ -> GuiStyle.pageSound(p); openResult(p, record.id, current - 1) }
        if (current < pages - 1) gui.setItem(GuiStyle.nextSlot(SIZE), GuiStyle.nextPage(current, pages)) { p, _ -> GuiStyle.pageSound(p); openResult(p, record.id, current + 1) }
        footer(gui) { backToMain(it) }
        plugin.guiManager.open(player, gui)
    }

    private fun openConfirm(player: Player, eventId: Int) {
        results.async({ Triple(store.get(eventId), store.players(eventId), store.rewards(eventId)) }) { (record, players, rewards) ->
            if (!player.isOnline || record == null) return@async
            if (record.status != RecordStatus.AWAITING_CONFIRMATION) { openResult(player, eventId); return@async }
            val gui = CustomGui(title("Confirm Result"), 27)
            val placed = Placement.placed.flatMap { place -> players.filter { it.placement == place }.map { "${place.label}: ${it.name}" } }
            gui.setItem(13, GuiStyle.item(Material.PAPER, Component.text("Confirm ${results.typeName(record.type)} #${record.id}?", NamedTextColor.YELLOW),
                buildList {
                    add(Component.text("Result: ${record.outcome.label}", NamedTextColor.WHITE))
                    if (placed.isEmpty()) add(Component.text("No winners", NamedTextColor.GRAY))
                    placed.forEach { add(Component.text(it, NamedTextColor.GOLD)) }
                    add(Component.empty())
                    if (rewards.isEmpty()) add(Component.text("No rewards are configured.", NamedTextColor.GRAY))
                    rewards.forEach { add(Component.text(results.describe(it), NamedTextColor.AQUA)) }
                    add(Component.empty())
                    add(GuiStyle.warning("Rewards are given once and the result is final."))
                },
            ))
            gui.setItem(11, GuiStyle.item(Material.LIME_CONCRETE, Component.text("Confirm", NamedTextColor.GREEN))) { p, _ ->
                if (!guard(p)) return@setItem
                val fresh = store.get(eventId) ?: return@setItem
                report(p, results.confirm(p, fresh))
                openResult(p, eventId)
            }
            gui.setItem(15, GuiStyle.item(Material.RED_CONCRETE, Component.text("Cancel", NamedTextColor.RED))) { p, _ -> GuiStyle.clickSound(p); openResult(p, eventId) }
            gui.fill(GuiStyle.filler())
            plugin.guiManager.open(player, gui)
        }
    }

    // ── Reward Status ─────────────────────────────────────

    fun openPayouts(player: Player, eventId: Int, page: Int = 0) {
        if (!guard(player)) return
        results.async({ store.get(eventId) to store.payouts(eventId) }) { (record, payouts) ->
            if (!player.isOnline || record == null) return@async
            val gui = CustomGui(title("Rewards #${record.id}"), SIZE)
            val pages = ((payouts.size + PAGE - 1) / PAGE).coerceAtLeast(1)
            val current = page.coerceIn(0, pages - 1)
            val canRecover = EventPermissions.canRecoverRewards(player)
            payouts.drop(current * PAGE).take(PAGE).forEachIndexed { i, payout ->
                gui.setItem(i, payoutItem(payout, canRecover)) { p, event ->
                    if (!guard(p) || payout.state != PayoutState.FAILED || !EventPermissions.canRecoverRewards(p)) return@setItem
                    report(p, results.retry(p, payout.id, confirmed = event.isShiftClick))
                    openPayouts(p, eventId, current)
                }
            }
            if (payouts.isEmpty()) gui.setItem(22, GuiStyle.item(Material.BARRIER, Component.text("No payouts", NamedTextColor.GRAY), listOf(
                Component.text("This result had no rewards for its winners.", NamedTextColor.GRAY),
            )))
            if (record.status == RecordStatus.CONFIRMED) {
                gui.setItem(SIZE - 4, GuiStyle.item(Material.HOPPER, Component.text("Distribute Rewards", NamedTextColor.GREEN), listOf(
                    Component.text("Queues any reward not queued yet.", NamedTextColor.GRAY),
                    Component.text("Already queued or delivered rewards", NamedTextColor.GRAY),
                    Component.text("are never given twice.", NamedTextColor.GRAY),
                ))) { p, _ ->
                    if (!guard(p)) return@setItem
                    store.get(eventId)?.let { report(p, results.distributeCommand(p, it)) }
                    openPayouts(p, eventId, current)
                }
            }
            if (current > 0) gui.setItem(GuiStyle.prevSlot(SIZE), GuiStyle.previousPage(current, pages)) { p, _ -> GuiStyle.pageSound(p); openPayouts(p, eventId, current - 1) }
            if (current < pages - 1) gui.setItem(GuiStyle.nextSlot(SIZE), GuiStyle.nextPage(current, pages)) { p, _ -> GuiStyle.pageSound(p); openPayouts(p, eventId, current + 1) }
            footer(gui, "Back to Result") { openResult(it, eventId) }
            plugin.guiManager.open(player, gui)
        }
    }

    // ── Event History ─────────────────────────────────────

    private data class HistoryPage(val total: Int, val records: List<EventRecord>, val winners: Map<Int, List<String>>, val rewards: Map<Int, RewardSummary>)

    fun openHistory(player: Player, page: Int = 0, host: UUID? = null, hostName: String? = null) {
        if (!guard(player)) return
        if (host != null && host != player.uniqueId && !EventPermissions.canViewOtherHosts(player)) { Msg.noPermission(player, category); return }
        results.async({
            val total = store.count(host)
            val pages = ((total + PAGE - 1) / PAGE).coerceAtLeast(1)
            val records = store.page(page.coerceIn(0, pages - 1) * PAGE, PAGE, host)
            val ids = records.map { it.id }
            HistoryPage(total, records, store.winnerNames(ids), store.rewardSummaries(ids))
        }) { data ->
            if (!player.isOnline) return@async
            val pages = ((data.total + PAGE - 1) / PAGE).coerceAtLeast(1)
            val current = page.coerceIn(0, pages - 1)
            val gui = CustomGui(title(if (hostName != null) "Events: $hostName" else "Event History"), SIZE)
            data.records.forEachIndexed { i, record ->
                gui.setItem(i, historyItem(player, record, data.winners[record.id].orEmpty(), data.rewards[record.id])) { p, _ ->
                    GuiStyle.clickSound(p); openResult(p, record.id)
                }
            }
            if (data.records.isEmpty()) gui.setItem(22, GuiStyle.item(Material.BOOK, Component.text("No events recorded yet", NamedTextColor.GRAY)))
            if (current > 0) gui.setItem(GuiStyle.prevSlot(SIZE), GuiStyle.previousPage(current, pages)) { p, _ -> GuiStyle.pageSound(p); openHistory(p, current - 1, host, hostName) }
            if (current < pages - 1) gui.setItem(GuiStyle.nextSlot(SIZE), GuiStyle.nextPage(current, pages)) { p, _ -> GuiStyle.pageSound(p); openHistory(p, current + 1, host, hostName) }
            footer(gui) { backToMain(it) }
            plugin.guiManager.open(player, gui)
        }
    }

    // ── Hosting Statistics ────────────────────────────────

    fun openHostStats(player: Player, host: UUID = player.uniqueId, hostName: String = player.name) {
        if (!guard(player)) return
        if (host != player.uniqueId && !EventPermissions.canViewOtherHosts(player)) { Msg.noPermission(player, category); return }
        results.async({ store.hostStats(host) }) { stats ->
            if (!player.isOnline) return@async
            val gui = CustomGui(title("Host Stats: $hostName"), 36)
            fun stat(slot: Int, material: Material, name: String, value: String, extra: List<Component> = emptyList()) =
                gui.setItem(slot, GuiStyle.item(material, Component.text(name, NamedTextColor.GOLD),
                    listOf(Component.text(value, NamedTextColor.WHITE)) + extra))
            stat(10, Material.NETHER_STAR, "Total Events Hosted", stats.total.toString())
            stat(11, Material.LIME_DYE, "Completed Events", stats.completed.toString())
            stat(12, Material.RED_DYE, "Cancelled Events", stats.cancelled.toString())
            stat(13, Material.PLAYER_HEAD, "Total Participants", stats.participants.toString(),
                listOf(Component.text("Average: ${"%.1f".format(Locale.ENGLISH, stats.average)} per event", NamedTextColor.GRAY)))
            stat(14, stats.favorite?.let { EventType.fromId(it)?.icon } ?: Material.BOOK, "Favorite Event Type",
                stats.favorite?.let { results.typeName(it) } ?: "None yet",
                stats.byType.map { (type, n) -> Component.text("${results.typeName(type)}: $n", NamedTextColor.GRAY) })
            stat(15, Material.CLOCK, "Last Event Hosted",
                stats.last?.let { "${results.typeName(it.type)} #${it.id}" } ?: "None yet",
                stats.last?.let { listOf(Component.text(date(player, it.endedAt), NamedTextColor.GRAY), Component.text(it.outcome.label, NamedTextColor.GRAY)) } ?: emptyList())
            gui.setItem(16, GuiStyle.item(Material.WRITABLE_BOOK, Component.text("Hosted Events", NamedTextColor.YELLOW), listOf(
                Component.text("Click to list $hostName's events", NamedTextColor.GRAY),
            ))) { p, _ -> GuiStyle.clickSound(p); openHistory(p, 0, host, hostName) }
            if (EventPermissions.canViewOtherHosts(player)) {
                gui.setItem(22, GuiStyle.item(Material.PLAYER_HEAD, Component.text("Other Hosts", NamedTextColor.YELLOW), listOf(
                    Component.text("Statistics of everyone who has hosted.", NamedTextColor.GRAY),
                ))) { p, _ -> GuiStyle.clickSound(p); openHosts(p) }
            }
            footer(gui) { backToMain(it) }
            plugin.guiManager.open(player, gui)
        }
    }

    private fun openHosts(player: Player) {
        if (!guard(player) || !EventPermissions.canViewOtherHosts(player)) { Msg.noPermission(player, category); return }
        results.async({ store.hosts(PAGE) }) { hosts ->
            if (!player.isOnline) return@async
            val gui = CustomGui(title("Event Hosts"), SIZE)
            hosts.forEachIndexed { i, host ->
                gui.setItem(i, GuiStyle.skull(Bukkit.getOfflinePlayer(host.uuid), Component.text(host.name, NamedTextColor.GOLD), listOf(
                    Component.text("${host.events} event(s) hosted", NamedTextColor.GRAY),
                    Component.empty(),
                    Component.text("Click to view statistics", NamedTextColor.YELLOW),
                ))) { p, _ -> GuiStyle.clickSound(p); openHostStats(p, host.uuid, host.name) }
            }
            if (hosts.isEmpty()) gui.setItem(22, GuiStyle.item(Material.BOOK, Component.text("Nobody has hosted yet", NamedTextColor.GRAY)))
            footer(gui, "Back to Your Stats") { openHostStats(it) }
            plugin.guiManager.open(player, gui)
        }
    }

    // ── Reward Presets ────────────────────────────────────

    fun openPresets(player: Player) {
        if (!guard(player)) return
        val gui = CustomGui(title("Event Rewards"), 27)
        EventType.entries.forEachIndexed { i, type ->
            val entries = results.presetFor(type)
            gui.setItem(11 + i, GuiStyle.item(type.icon, Component.text(type.displayName, NamedTextColor.GOLD),
                (if (entries.isEmpty()) listOf(Component.text("No rewards configured", NamedTextColor.GRAY))
                else entries.map { Component.text(results.describe(it), NamedTextColor.AQUA) }) +
                    listOf(Component.empty(), Component.text("Click to manage", NamedTextColor.YELLOW)),
            )) { p, _ -> GuiStyle.clickSound(p); openPreset(p, type) }
        }
        footer(gui) { backToMain(it) }
        plugin.guiManager.open(player, gui)
    }

    private fun openPreset(player: Player, type: EventType) {
        if (!guard(player)) return
        val gui = CustomGui(title("${type.displayName} Rewards"), SIZE)
        val canEdit = EventPermissions.canConfigureRewards(player)
        val entries = results.presetFor(type)
        entries.forEachIndexed { i, entry ->
            val problem = results.validity(entry)
            gui.setItem(i, GuiStyle.item(rewardMaterial(entry.kind), Component.text("${i + 1}. ${entry.placement.label}", NamedTextColor.GOLD), buildList {
                add(Component.text(entry.describe(results::crateName), NamedTextColor.AQUA))
                if (problem != null) add(GuiStyle.error("Invalid: $problem (skipped)"))
                if (canEdit) { add(Component.empty()); add(Component.text("Shift-click to remove", NamedTextColor.YELLOW)) }
            })) { p, event ->
                if (!guard(p) || !event.isShiftClick) return@setItem
                // Remove by content, not just position, so a menu opened before another edit can't remove the wrong line.
                val line = results.presetFor(type).indexOf(entry)
                if (line < 0) { GuiStyle.errorSound(p); openPreset(p, type); return@setItem }
                report(p, results.removePreset(p, type, line + 1))
                openPreset(p, type)
            }
        }
        if (entries.isEmpty()) gui.setItem(22, GuiStyle.item(Material.BARRIER, Component.text("No rewards configured", NamedTextColor.GRAY)))
        gui.setItem(SIZE - 4, GuiStyle.item(Material.WRITABLE_BOOK, Component.text("Add a Reward", NamedTextColor.GREEN), listOf(
            Component.text("/event rewards add ${type.id} <placement> <type> ...", NamedTextColor.WHITE),
            Component.text("Placements: first, second, third, participant", NamedTextColor.GRAY),
            Component.text("coins|credits <amount>", NamedTextColor.GRAY),
            Component.text("tokens <quantity>", NamedTextColor.GRAY),
            Component.text("cratekey <crate> <quantity>", NamedTextColor.GRAY),
            Component.empty(),
            Component.text("Events snapshot these when they start.", NamedTextColor.DARK_GRAY),
        )))
        footer(gui, "Back to Event Rewards") { openPresets(it) }
        plugin.guiManager.open(player, gui)
    }

    // ── Items ─────────────────────────────────────────────

    private fun recordItem(viewer: Player, record: EventRecord, players: List<RecordPlayer>, summary: RewardSummary?): ItemStack {
        val icon = EventType.fromId(record.type)?.icon ?: Material.BOOK
        val winners = players.filter { it.placement == Placement.FIRST }.map { it.name }
        return GuiStyle.item(icon, Component.text("${results.typeName(record.type)} #${record.id}", NamedTextColor.GOLD), buildList {
            add(line("Status", record.status.label, statusColor(record.status)))
            add(line("Result", record.outcome.label, NamedTextColor.WHITE))
            add(line(if (winners.size > 1) "Winners" else "Winner", winners.joinToString(", ").ifEmpty { "none" }, NamedTextColor.GOLD))
            record.summary?.let { add(Component.text(it, NamedTextColor.GRAY)) }
            add(Component.empty())
            add(line("Arena", record.arenaName ?: "none", NamedTextColor.WHITE))
            add(line("Host", record.hostName, NamedTextColor.WHITE))
            add(line("Participants", record.participantCount.toString(), NamedTextColor.WHITE))
            record.startedAt?.let { add(line("Started", date(viewer, it), NamedTextColor.WHITE)) }
            add(line("Ended", date(viewer, record.endedAt), NamedTextColor.WHITE))
            record.confirmedByName?.let { add(line("Confirmed by", it, NamedTextColor.WHITE)) }
            if (record.status == RecordStatus.CONFIRMED) add(line("Rewards", summary?.label() ?: "No rewards", NamedTextColor.WHITE))
        })
    }

    private fun rewardsItem(rewards: List<RewardEntry>, canSync: Boolean): ItemStack =
        GuiStyle.item(Material.GOLD_INGOT, Component.text("Rewards", NamedTextColor.GOLD), buildList {
            if (rewards.isEmpty()) add(Component.text("No rewards for this event", NamedTextColor.GRAY))
            rewards.forEach { add(Component.text(results.describe(it), NamedTextColor.AQUA)) }
            add(Component.empty())
            add(Component.text("Captured when the event started.", NamedTextColor.DARK_GRAY))
            if (canSync) add(Component.text("Click to use the current preset instead", NamedTextColor.YELLOW))
        })

    private fun playerItem(player: RecordPlayer, canOverride: Boolean): ItemStack {
        val color = when (player.placement) {
            Placement.FIRST -> NamedTextColor.GOLD
            Placement.SECOND -> NamedTextColor.WHITE
            Placement.THIRD -> NamedTextColor.RED
            else -> NamedTextColor.GRAY
        }
        return GuiStyle.skull(Bukkit.getOfflinePlayer(player.uuid), Component.text(player.name, color), buildList {
            add(line("Placement", player.placement?.label ?: "None", color))
            if (player.detected != player.placement) add(line("Detected", player.detected?.label ?: "None", NamedTextColor.DARK_GRAY))
            if (canOverride) {
                add(Component.empty())
                add(Component.text("Left-click: toggle winner", NamedTextColor.YELLOW))
                add(Component.text("Right-click: 2nd / 3rd / none", NamedTextColor.YELLOW))
            }
        })
    }

    private fun payoutItem(payout: Payout, canRecover: Boolean): ItemStack {
        val (material, color) = when (payout.state) {
            PayoutState.DELIVERED -> Material.LIME_DYE to NamedTextColor.GREEN
            PayoutState.PENDING -> Material.YELLOW_DYE to NamedTextColor.YELLOW
            PayoutState.PROCESSING -> Material.ORANGE_DYE to NamedTextColor.GOLD
            PayoutState.FAILED -> Material.RED_DYE to NamedTextColor.RED
            PayoutState.CANCELLED -> Material.GRAY_DYE to NamedTextColor.GRAY
        }
        return GuiStyle.item(material, Component.text(payout.recipientName, color), buildList {
            add(Component.text(payout.entry?.let { results.describe(it) } ?: "Unknown reward", NamedTextColor.AQUA))
            add(line("State", payout.state.label, color))
            add(line("Payout", "#${payout.id}" + (payout.deliveryId?.let { " (delivery #$it)" } ?: " (not queued)"), NamedTextColor.GRAY))
            payout.note?.let { add(Component.text(it.take(60), NamedTextColor.DARK_GRAY)) }
            if (payout.state == PayoutState.PENDING && payout.deliveryId != null && payout.entry?.kind?.isCurrency == false) {
                add(Component.text("Items arrive when they're online with room.", NamedTextColor.DARK_GRAY))
            }
            if (payout.state == PayoutState.FAILED && canRecover) {
                add(Component.empty())
                add(Component.text("Click to retry (shift-click if interrupted)", NamedTextColor.YELLOW))
            }
        })
    }

    private fun historyItem(viewer: Player, record: EventRecord, winners: List<String>, rewards: RewardSummary?): ItemStack {
        val icon = EventType.fromId(record.type)?.icon ?: Material.BOOK
        return GuiStyle.item(icon, Component.text("${results.typeName(record.type)} #${record.id}", NamedTextColor.GOLD), buildList {
            add(line("Date", date(viewer, record.endedAt), NamedTextColor.WHITE))
            add(line("Host", record.hostName, NamedTextColor.WHITE))
            add(line("Participants", record.participantCount.toString(), NamedTextColor.WHITE))
            add(line(if (winners.size > 1) "Winners" else "Winner", winners.joinToString(", ").ifEmpty { "none" }, NamedTextColor.GOLD))
            add(line("Result", record.outcome.label, NamedTextColor.WHITE))
            add(line("Status", record.status.label, statusColor(record.status)))
            if (record.status == RecordStatus.CONFIRMED) add(line("Rewards", rewards?.label() ?: "No rewards", NamedTextColor.WHITE))
            add(Component.empty())
            add(Component.text("Click for details", NamedTextColor.YELLOW))
        })
    }

    private fun rewardMaterial(kind: RewardKind): Material = when (kind) {
        RewardKind.COINS -> Material.GOLD_NUGGET
        RewardKind.CREDITS -> Material.DIAMOND
        RewardKind.TOKENS -> Material.SUNFLOWER
        RewardKind.CRATE_KEY -> Material.TRIPWIRE_HOOK
    }

    private fun statusColor(status: RecordStatus): NamedTextColor = when (status) {
        RecordStatus.AWAITING_CONFIRMATION -> NamedTextColor.YELLOW
        RecordStatus.CONFIRMED -> NamedTextColor.GREEN
        RecordStatus.CANCELLED -> NamedTextColor.RED
    }

    // ── Helpers ───────────────────────────────────────────

    private fun footer(gui: CustomGui, backLabel: String = "Back to Event Manager", back: (Player) -> Unit) {
        gui.setItem(GuiStyle.backSlot(gui.size), GuiStyle.back(backLabel)) { p, _ -> GuiStyle.clickSound(p); back(p) }
        gui.setItem(GuiStyle.closeSlot(gui.size), GuiStyle.close()) { p, _ -> p.closeInventory() }
        gui.fill(GuiStyle.filler())
    }

    private fun report(player: Player, action: EventResults.Action) {
        if (action.ok) { GuiStyle.successSound(player); Msg.success(player, action.message, category) }
        else { GuiStyle.errorSound(player); Msg.error(player, action.message, category) }
    }

    /** Permission check for opening every section and for every click. */
    private fun guard(player: Player): Boolean {
        if (EventPermissions.canHost(player)) return true
        player.closeInventory()
        Msg.noPermission(player, category)
        return false
    }

    fun date(viewer: Player, at: Long): String = DATE.format(Instant.ofEpochMilli(at).atZone(plugin.timezoneManager.zoneFor(viewer)))

    private fun title(text: String) = Component.text(text.take(32), NamedTextColor.DARK_GRAY)

    private fun line(label: String, value: String, color: NamedTextColor) =
        Component.text("$label: ", NamedTextColor.GRAY).append(Component.text(value, color))
}
