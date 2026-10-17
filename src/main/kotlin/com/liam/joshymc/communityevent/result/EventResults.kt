package com.liam.joshymc.communityevent.result

import com.liam.joshymc.Joshymc
import com.liam.joshymc.communityevent.EventPermissions
import com.liam.joshymc.communityevent.EventSession
import com.liam.joshymc.communityevent.EventState
import com.liam.joshymc.communityevent.EventType
import com.liam.joshymc.communityevent.FinishCause
import com.liam.joshymc.communityevent.arena.EventArenas
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.PurchaseDeliveryStore
import com.liam.joshymc.manager.PurchaseFulfillmentManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.math.BigDecimal
import java.util.UUID

/**
 * Event results, rewards, history and statistics (issue #1199): the one reward service for
 * every hosted event type, independent of the individual games.
 *
 *  1. A game reports its verdict ([EventSession.reportResult]); when the session finishes it is
 *     written to [EventHistoryStore] as AWAITING_CONFIRMATION (or CANCELLED when the event was
 *     stopped before a result). Detected winners are preselected; nothing is paid yet.
 *  2. Staff review it, correct it if needed (`joshymc.event.result.override`, audited), and
 *     confirm. Confirming moves the status once and creates one payout row per recipient per
 *     reward line in the same transaction.
 *  3. Each payout is handed to the existing store-delivery queue
 *     ([PurchaseFulfillmentManager.enqueue]) with a reference unique to that payout, so a
 *     repeat can only find the existing delivery: Coins/Credits are deposited even while the
 *     player is offline, items (tokens, crate keys) arrive on join with their full metadata,
 *     and failures stay visible for `/event rewards retry`.
 */
class EventResults(
    private val plugin: Joshymc,
    private val arenas: EventArenas,
) {
    val store = EventHistoryStore(plugin.databaseManager, { sql, params -> plugin.databaseManager.insertReturningId(sql, *params) })

    private val presets = HashMap<EventType, List<RewardEntry>>()
    private val comms get() = plugin.commsManager
    private val category = CommunicationsManager.Category.EVENT

    /** Refreshes open Event Manager menus; set by EventManager. */
    var onChange: (() -> Unit)? = null

    /** Outcome of a staff action, with the feedback for whoever asked. */
    data class Action(val ok: Boolean, val message: String)

    fun start() {
        store.createSchema()
        presets.clear()
        for ((type, entries) in store.loadPresets()) EventType.fromId(type)?.let { presets[it] = entries }
        refreshAwaiting()
        if (awaitingCount > 0) plugin.logger.info("[Event] $awaitingCount event result(s) are waiting for staff to confirm them (/event result).")
        // A crash between confirming and queueing leaves payouts without a delivery; the unique
        // reference means queueing them again finds anything already recorded instead of paying twice.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { resumeDistribution() }, RESUME_DELAY_TICKS)
    }

    // ── Settings ──────────────────────────────────────────

    fun limits(): RewardLimits {
        val c = plugin.config.getConfigurationSection("hosted-events.results.limits")
        val d = RewardLimits.DEFAULT
        return RewardLimits(
            maxCoins = decimal(c?.getString("max-coins"), d.maxCoins),
            maxCredits = decimal(c?.getString("max-credits"), d.maxCredits),
            maxItems = (c?.getInt("max-items", d.maxItems) ?: d.maxItems).coerceIn(1, 36 * 64),
            maxEntries = (c?.getInt("max-entries", d.maxEntries) ?: d.maxEntries).coerceIn(1, 20),
        )
    }

    private fun decimal(raw: String?, fallback: BigDecimal): BigDecimal =
        raw?.trim()?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: fallback

    private val announce: Boolean get() = plugin.config.getBoolean("hosted-events.results.announce", true)

    private fun crateExists(id: String): Boolean = plugin.crateManager.getCrate(plugin.crateManager.resolveCrateId(id)) != null

    fun crateName(id: String): String {
        val crate = plugin.crateManager.getCrate(plugin.crateManager.resolveCrateId(id)) ?: return "$id key"
        return PlainTextComponentSerializer.plainText().serialize(comms.parseLegacy(crate.keyName))
    }

    fun describe(entry: RewardEntry): String = "${entry.placement.label}: ${entry.describe(::crateName)}"

    fun validity(entry: RewardEntry): String? = EventRewards.problem(entry, limits(), ::crateExists)

    // ── Presets ───────────────────────────────────────────

    fun presetFor(type: EventType): List<RewardEntry> = presets[type] ?: emptyList()

    fun addPreset(sender: CommandSender, type: EventType, args: List<String>): Action {
        if (!EventPermissions.canConfigureRewards(sender)) return Action(false, "You don't have permission to configure event rewards.")
        val limits = limits()
        val current = presetFor(type)
        if (current.size >= limits.maxEntries) return Action(false, "${type.displayName} already has ${limits.maxEntries} reward lines (the most allowed).")
        val entry = when (val p = EventRewards.parse(args, limits, ::crateExists)) {
            is EventRewards.Parse.Ok -> p.entry
            is EventRewards.Parse.Error -> return Action(false, p.message)
        }
        return savePreset(sender, type, current + entry, "added ${describe(entry)}")
    }

    fun removePreset(sender: CommandSender, type: EventType, line: Int): Action {
        if (!EventPermissions.canConfigureRewards(sender)) return Action(false, "You don't have permission to configure event rewards.")
        val current = presetFor(type)
        val entry = current.getOrNull(line - 1) ?: return Action(false, "${type.displayName} has no reward line $line.")
        return savePreset(sender, type, current.filterIndexed { i, _ -> i != line - 1 }, "removed ${describe(entry)}")
    }

    fun clearPreset(sender: CommandSender, type: EventType): Action {
        if (!EventPermissions.canConfigureRewards(sender)) return Action(false, "You don't have permission to configure event rewards.")
        if (presetFor(type).isEmpty()) return Action(false, "${type.displayName} has no rewards configured.")
        return savePreset(sender, type, emptyList(), "cleared every reward")
    }

    private fun savePreset(sender: CommandSender, type: EventType, entries: List<RewardEntry>, what: String): Action {
        try {
            store.savePreset(type.id, entries)
        } catch (e: Exception) {
            plugin.logger.severe("[Event] Could not save the ${type.displayName} reward preset: ${e.message}")
            return Action(false, "Couldn't save the rewards (database error). Nothing changed.")
        }
        presets[type] = entries
        audit(null, sender, "PRESET", "${type.id}: $what")
        changed()
        return Action(true, "${type.displayName} rewards: $what.")
    }

    /** "Prizes: 1st Place: 100 Credits, ..." for the registration broadcast, or null without rewards. */
    fun prizeLine(session: EventSession): Component? {
        val entries = presetFor(session.type).filter { validity(it) == null }
        if (entries.isEmpty()) return null
        return Component.text("Prizes: ", NamedTextColor.GOLD)
            .append(Component.text(entries.joinToString(", ") { describe(it) }, NamedTextColor.YELLOW))
    }

    // ── Recording (session hooks) ─────────────────────────

    /** Start succeeded: the prize is what the preset says now, whatever is edited mid-event. */
    fun onStarted(session: EventSession) {
        session.rewards = presetFor(session.type).filter { validity(it) == null }
    }

    /**
     * Writes the finished session to history. Never-opened events aren't recorded (they were
     * never announced, so they can't inflate hosting statistics). A session stopped before the
     * game reported a result is CANCELLED with no winner; otherwise it waits for confirmation.
     */
    fun onFinished(session: EventSession, wasState: EventState, cause: FinishCause, registered: Set<UUID>) {
        if (wasState == EventState.CREATED) return
        val arena = arenas.selectedFor(session)
        val detected = session.detected.takeIf { wasState == EventState.RUNNING }
        val roster = if (wasState == EventState.RUNNING) session.roster else emptyMap()
        val cancelled = detected == null
        val summary = when {
            detected != null -> detected.summary
            wasState == EventState.OPEN && cause == FinishCause.SHUTDOWN -> "Server stopped during registration"
            wasState == EventState.OPEN -> "Cancelled during registration"
            cause == FinishCause.SHUTDOWN -> "Server stopped before a result"
            else -> "Stopped by staff before a result"
        }
        val players = roster.map { (uuid, name) ->
            val placement = when {
                detected == null -> null
                uuid in detected.winners -> Placement.FIRST
                uuid in detected.second -> Placement.SECOND
                uuid in detected.third -> Placement.THIRD
                else -> null
            }
            NewPlayer(uuid, name, placement)
        }
        val outcome = detected?.let { d -> if (players.any { it.detected == Placement.FIRST }) EventOutcome.WINNER else if (d.outcome == EventOutcome.WINNER) EventOutcome.NO_WINNER else d.outcome }
            ?: EventOutcome.CANCELLED
        val record = NewRecord(
            uid = UUID.randomUUID().toString(),
            sessionId = session.id.toString(),
            type = session.type.id,
            arenaId = session.arenaId,
            arenaName = arena?.displayName ?: session.arenaId,
            hostUuid = session.hostId,
            hostName = session.hostName,
            createdAt = session.createdAt,
            openedAt = session.openedAt,
            startedAt = session.startedAt,
            endedAt = session.endedAt ?: System.currentTimeMillis(),
            participantCount = if (wasState == EventState.RUNNING) roster.size else registered.size,
            outcome = outcome,
            status = if (cancelled) RecordStatus.CANCELLED else RecordStatus.AWAITING_CONFIRMATION,
            summary = summary,
        )
        val id = store.insert(record, players, if (cancelled) emptyList() else session.rewards)
        store.audit(id, null, "System", if (cancelled) "CANCELLED" else "RECORDED", "$summary (${outcome.name})")
        plugin.logger.info("[Event] Recorded ${session.type.displayName} #$id: ${outcome.label}, ${record.participantCount} participant(s), ${record.status.name}. $summary.")
        if (!cancelled) notifyReview(session.hostId, id, session.type)
        changed()
    }

    private fun notifyReview(hostId: UUID?, id: Int, type: EventType) {
        val host = hostId?.let { Bukkit.getPlayer(it) }?.takeIf { EventPermissions.canHost(it) } ?: return
        comms.send(
            host,
            Component.text("${type.displayName} #$id has ended. Review the result before rewards are given: ", NamedTextColor.YELLOW)
                .append(
                    Component.text("[Review]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/event result $id"))
                        .hoverEvent(HoverEvent.showText(Component.text("/event result $id", NamedTextColor.GRAY)))
                ),
            category,
        )
    }

    // ── Finding results ───────────────────────────────────

    /** "#12" / "12" picks that event; null picks the newest result awaiting confirmation. */
    fun resolve(arg: String?): EventRecord? {
        if (arg == null) return store.awaiting(1).firstOrNull()
        val id = arg.removePrefix("#").toIntOrNull() ?: return null
        return store.get(id)
    }

    fun typeName(type: String): String = EventType.fromId(type)?.displayName ?: type

    // ── Manual changes ────────────────────────────────────

    /**
     * Finds [name] among the event's participants: exact (case-insensitive) recorded name first,
     * then the exact current name through PlayerResolver, never a prefix.
     */
    fun findParticipant(record: EventRecord, name: String): RecordPlayer? {
        val players = store.players(record.id)
        players.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        val uuid = plugin.playerResolver.resolve(name)?.uniqueId ?: return null
        return players.firstOrNull { it.uuid == uuid }
    }

    fun setPlacement(sender: CommandSender, record: EventRecord, name: String, placement: Placement?, sole: Boolean = false): Action {
        if (!EventPermissions.canOverrideResults(sender)) return Action(false, "You don't have permission to change event results.")
        if (record.status != RecordStatus.AWAITING_CONFIRMATION) return Action(false, finalMessage(record))
        val player = findParticipant(record, name) ?: return Action(false, "No participant of ${typeName(record.type)} #${record.id} is named exactly '$name'.")
        return setPlacement(sender, record, player, placement, sole)
    }

    fun setPlacement(sender: CommandSender, record: EventRecord, player: RecordPlayer, placement: Placement?, sole: Boolean = false): Action {
        if (!EventPermissions.canOverrideResults(sender)) return Action(false, "You don't have permission to change event results.")
        if (placement == Placement.PARTICIPANT) return Action(false, "Participant isn't a placement.")
        val changed = if (sole && placement == Placement.FIRST) store.setSoleWinner(record.id, player.uuid) else store.setPlacement(record.id, player.uuid, placement)
        if (!changed) return Action(false, store.get(record.id)?.let { finalMessage(it) } ?: "That result no longer exists.")
        val what = when {
            placement == null -> "cleared ${player.name}'s placement"
            sole -> "set ${player.name} as the only winner"
            else -> "gave ${player.name} ${placement.label}"
        }
        audit(record.id, sender, "OVERRIDE", "$what (was ${player.placement?.label ?: "unplaced"}, detected ${player.detected?.label ?: "unplaced"})")
        changed()
        return Action(true, "${typeName(record.type)} #${record.id}: $what.")
    }

    fun clearPlacements(sender: CommandSender, record: EventRecord): Action {
        if (!EventPermissions.canOverrideResults(sender)) return Action(false, "You don't have permission to change event results.")
        if (!store.clearPlacements(record.id)) return Action(false, store.get(record.id)?.let { finalMessage(it) } ?: "That result no longer exists.")
        audit(record.id, sender, "OVERRIDE", "cleared every placement")
        changed()
        return Action(true, "${typeName(record.type)} #${record.id}: cleared every placement. Confirming now gives no placement rewards.")
    }

    /** Swaps an unconfirmed result's rewards for the event type's current preset. */
    fun syncRewards(sender: CommandSender, record: EventRecord): Action {
        if (!EventPermissions.canConfigureRewards(sender)) return Action(false, "You don't have permission to configure event rewards.")
        val type = EventType.fromId(record.type) ?: return Action(false, "Unknown event type '${record.type}'.")
        val entries = presetFor(type).filter { validity(it) == null }
        if (!store.replaceRewards(record.id, entries)) return Action(false, store.get(record.id)?.let { finalMessage(it) } ?: "That result no longer exists.")
        audit(record.id, sender, "REWARDS", "replaced with the current preset: ${entries.joinToString { describe(it) }.ifEmpty { "none" }}")
        changed()
        return Action(true, "${typeName(record.type)} #${record.id} now uses the current ${type.displayName} rewards (${entries.size} line(s)).")
    }

    private fun finalMessage(record: EventRecord): String = when (record.status) {
        RecordStatus.CONFIRMED -> "${typeName(record.type)} #${record.id} is already confirmed${record.confirmedByName?.let { " by $it" } ?: ""}; its result is final."
        RecordStatus.CANCELLED -> "${typeName(record.type)} #${record.id} was cancelled; cancelled events have no winners or rewards."
        RecordStatus.AWAITING_CONFIRMATION -> "${typeName(record.type)} #${record.id} couldn't be changed."
    }

    // ── Confirmation and distribution ─────────────────────

    /** Finalizes the result, creates its payouts once, queues them, and announces it once. Main thread. */
    fun confirm(sender: CommandSender, record: EventRecord): Action {
        if (!EventPermissions.canHost(sender)) return Action(false, "You don't have permission to confirm event results.")
        if (record.status != RecordStatus.AWAITING_CONFIRMATION) return Action(false, finalMessage(record))
        val actor = sender as? Player
        val result = try {
            store.confirm(record.id, actor?.uniqueId, actor?.name ?: "Console") { validity(it) }
        } catch (e: Exception) {
            plugin.logger.severe("[Event] Confirming #${record.id} failed: ${e.message}")
            return Action(false, "Couldn't confirm the result (database error). Nothing was confirmed or paid.")
        }
        val created = when (result) {
            is EventHistoryStore.Confirm.Confirmed -> result.payouts
            EventHistoryStore.Confirm.NotAwaiting -> return Action(false, store.get(record.id)?.let { finalMessage(it) } ?: "That result no longer exists.")
        }
        plugin.logger.info("[Event] ${actor?.name ?: "Console"} confirmed ${typeName(record.type)} #${record.id} (${created} payout(s)).")
        val queued = distribute(record.id)
        announce(record.id)
        changed()
        val failures = if (queued.errors > 0) " ${queued.errors} couldn't be queued yet; use Distribute Rewards to try again." else ""
        return Action(true, "Confirmed ${typeName(record.type)} #${record.id}. ${created} reward(s) sent to delivery.$failures")
    }

    data class Distribution(val queued: Int, val alreadyQueued: Int, val errors: Int)

    /**
     * Hands every payout of [eventId] that has no delivery yet to the store-delivery queue.
     * Safe to repeat: the per-payout reference is UNIQUE, so a payout that was queued before
     * (even if the link wasn't saved) is found and linked, never queued twice. Main thread.
     */
    fun distribute(eventId: Int): Distribution {
        var queued = 0; var existing = 0; var errors = 0
        for (payout in store.unlinkedPayouts(eventId, MAX_PAYOUTS_PER_PASS)) {
            when (enqueue(payout)) {
                true -> queued++
                false -> existing++
                null -> errors++
            }
        }
        return Distribution(queued, existing, errors)
    }

    /** True = newly queued, false = it was already queued (linked now), null = error. */
    private fun enqueue(payout: Payout): Boolean? {
        val entry = payout.entry ?: return null
        val result = try {
            plugin.purchaseFulfillmentManager.enqueue(
                payout.recipient, payout.recipientName, entry.spec,
                reference = "event:${payout.eventId}:${payout.id}",
                source = "${PurchaseFulfillmentManager.EVENT_SOURCE_PREFIX}${payout.eventId}",
            )
        } catch (e: Exception) {
            // A row left PROCESSING becomes INTERRUPTED at the next start; the next pass finds it by reference.
            plugin.logger.severe("[Event] Queueing payout #${payout.id} for ${payout.recipientName} threw: ${e.message}")
            return null
        }
        val (delivery, fresh) = when (result) {
            is PurchaseFulfillmentManager.FulfillResult.Recorded -> result.delivery to true
            is PurchaseFulfillmentManager.FulfillResult.Duplicate -> result.existing to false
            else -> {
                plugin.logger.warning("[Event] Payout #${payout.id} (${entry.describe(::crateName)} for ${payout.recipientName}) couldn't be queued: $result")
                return null
            }
        }
        try {
            store.link(payout.id, delivery.id)
        } catch (e: Exception) {
            // The delivery exists; the next pass finds it again through its reference.
            plugin.logger.warning("[Event] Payout #${payout.id} was queued as delivery #${delivery.id} but couldn't be linked: ${e.message}")
        }
        return fresh
    }

    /** Startup: queue any confirmed payout a crash left without a delivery. */
    private fun resumeDistribution() {
        val pending = try {
            store.unlinkedPayouts(null, MAX_PAYOUTS_PER_PASS)
        } catch (e: Exception) {
            plugin.logger.warning("[Event] Couldn't check for unqueued event rewards: ${e.message}")
            return
        }
        if (pending.isEmpty()) return
        var queued = 0
        for (payout in pending) if (enqueue(payout) != null) queued++
        plugin.logger.info("[Event] Queued $queued of ${pending.size} event reward(s) left unqueued by the last stop.")
    }

    /** Staff "Distribute Rewards": only for confirmed results. */
    fun distributeCommand(sender: CommandSender, record: EventRecord): Action {
        if (!EventPermissions.canHost(sender)) return Action(false, "You don't have permission to distribute event rewards.")
        if (record.status != RecordStatus.CONFIRMED) {
            return Action(false, if (record.status == RecordStatus.CANCELLED) finalMessage(record)
                else "Confirm ${typeName(record.type)} #${record.id} first; unconfirmed results are never paid.")
        }
        val d = distribute(record.id)
        if (d.queued + d.alreadyQueued + d.errors == 0) return Action(true, "Every reward of #${record.id} is already with the delivery queue. See /event rewards status ${record.id}.")
        if (d.queued + d.alreadyQueued > 0) audit(record.id, sender, "DISTRIBUTE", "${d.queued} queued, ${d.alreadyQueued} already queued, ${d.errors} error(s)")
        changed()
        return Action(d.errors == 0, "#${record.id}: ${d.queued} reward(s) queued, ${d.alreadyQueued} were already queued" + if (d.errors > 0) ", ${d.errors} failed (see console)." else ".")
    }

    /**
     * Retries one FAILED payout through the delivery queue's own retry (only what is still owed;
     * delivered payouts can't be retried). INTERRUPTED ones need [confirmed], since the items may
     * already have arrived.
     */
    fun retry(sender: CommandSender, payoutId: Int, confirmed: Boolean): Action {
        if (!EventPermissions.canRecoverRewards(sender)) return Action(false, "You don't have permission to retry event rewards.")
        val payout = store.payout(payoutId) ?: return Action(false, "No event payout #$payoutId.")
        val deliveryId = payout.deliveryId
        if (deliveryId == null) {
            val record = store.get(payout.eventId) ?: return Action(false, "Payout #$payoutId belongs to a missing event.")
            return distributeCommand(sender, record)
        }
        val delivery = plugin.purchaseFulfillmentManager.store.get(deliveryId) ?: return Action(false, "Delivery #$deliveryId is missing.")
        when (delivery.state) {
            PurchaseDeliveryStore.State.FAILED -> {}
            PurchaseDeliveryStore.State.INTERRUPTED -> if (!confirmed) {
                return Action(false, "Payout #$payoutId was interrupted mid-delivery: ${payout.recipientName} may already have some of it. " +
                    "Check their inventory, then run /event rewards retry $payoutId confirm.")
            }
            PurchaseDeliveryStore.State.COMPLETED -> return Action(false, "Payout #$payoutId was already delivered; it can't be paid again.")
            else -> return Action(false, "Payout #$payoutId is ${delivery.state.name.lowercase()}, not failed; nothing to retry.")
        }
        val after = plugin.purchaseFulfillmentManager.retry(deliveryId) ?: return Action(false, "Payout #$payoutId couldn't be retried (its state changed).")
        audit(payout.eventId, sender, "RETRY", "payout #$payoutId (delivery #$deliveryId) for ${payout.recipientName}: now ${after.state.name}")
        changed()
        return Action(true, "Payout #$payoutId retried: delivery #$deliveryId is now ${after.state.name}.")
    }

    // ── Announcements ─────────────────────────────────────

    private fun announce(eventId: Int) {
        if (!announce) return
        val record = store.get(eventId) ?: return
        if (!store.markAnnounced(eventId)) return
        val winners = store.winnerNames(listOf(eventId))[eventId].orEmpty()
        val type = EventType.fromId(record.type)
        val names = winners.joinToString(", ")
        val message = when {
            type == EventType.MOBS && winners.isNotEmpty() -> "Mob Waves completed! The survivors cleared all waves: $names!"
            type == EventType.MOBS -> "Mob Waves has ended. The waves won this time!"
            winners.isEmpty() -> "${typeName(record.type)} has ended with no winner."
            type == EventType.SUMO || type == EventType.CPVP -> "${typeName(record.type)} Tournament complete! Congratulations to $names!"
            else -> "${typeName(record.type)} has ended! Congratulations to $names!"
        }
        comms.broadcast(Component.text(message, NamedTextColor.GOLD), category)
    }

    // ── Helpers ───────────────────────────────────────────

    private fun audit(eventId: Int?, sender: CommandSender, action: String, detail: String) {
        val player = sender as? Player
        val name = player?.name ?: "Console"
        plugin.logger.info("[Event] $name $action${eventId?.let { " #$it" } ?: ""}: $detail")
        try {
            store.audit(eventId, player?.uniqueId, name, action, detail)
        } catch (e: Exception) {
            plugin.logger.warning("[Event] Couldn't write the audit entry: ${e.message}")
        }
    }

    /** Results awaiting confirmation, cached for the Event Manager's main menu (refreshed on every change). */
    var awaitingCount: Int = 0
        private set

    private fun refreshAwaiting() {
        awaitingCount = try {
            store.awaiting(MAX_AWAITING_LIST).size
        } catch (e: Exception) {
            awaitingCount
        }
    }

    private fun changed() {
        refreshAwaiting()
        runCatching { onChange?.invoke() }
    }

    /** Runs [load] off the main thread, then [then] on it. For history pages and statistics. */
    fun <T> async(load: () -> T, then: (T) -> Unit) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val value = try {
                load()
            } catch (e: Exception) {
                plugin.logger.warning("[Event] History query failed: ${e.message}")
                return@Runnable
            }
            Bukkit.getScheduler().runTask(plugin, Runnable { then(value) })
        })
    }

    companion object {
        private const val RESUME_DELAY_TICKS = 200L
        private const val MAX_PAYOUTS_PER_PASS = 1000
        const val MAX_AWAITING_LIST = 45
    }
}
