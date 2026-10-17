package com.liam.joshymc.communityevent

import com.liam.joshymc.communityevent.result.EventOutcome
import com.liam.joshymc.communityevent.result.RewardEntry
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * A finished game's own verdict (issue #1199): [winners] (several for Mob Waves survivors,
 * none for a draw/defeat), the detected runner-up and third place where the game knows them,
 * and a one-line [summary]. Staff still confirm it before anything is paid.
 */
data class DetectedResult(
    val outcome: EventOutcome,
    val winners: List<UUID>,
    val second: List<UUID> = emptyList(),
    val third: List<UUID> = emptyList(),
    val summary: String,
)

/** Lifecycle of a hosted event: CREATED -> OPEN -> RUNNING -> FINISHED. */
enum class EventState(val label: String, val color: NamedTextColor) {
    CREATED("Created", NamedTextColor.YELLOW),
    OPEN("Registration Open", NamedTextColor.GREEN),
    RUNNING("Running", NamedTextColor.AQUA),
    FINISHED("Finished", NamedTextColor.GRAY);

    /** CREATED, OPEN and RUNNING block a new session; FINISHED is only kept for /event status. */
    val isActive: Boolean get() = this != FINISHED

    /** Every allowed transition. Reset moves a session back to CREATED (never out of RUNNING). */
    fun canMoveTo(next: EventState): Boolean = when (this) {
        CREATED -> next == OPEN || next == FINISHED || next == CREATED
        OPEN -> next == RUNNING || next == FINISHED || next == CREATED
        RUNNING -> next == FINISHED
        FINISHED -> next == CREATED
    }
}

/**
 * One hosted event. Sessions are in memory only: a restart discards them (see
 * [EventSessions.shutdown]), so a RUNNING event can never come back as a phantom.
 * [hostId] is null when the console created it.
 */
class EventSession(
    val id: UUID,
    val type: EventType,
    val hostId: UUID?,
    val hostName: String,
    val createdAt: Long,
) {
    var state: EventState = EventState.CREATED
        internal set
    var openedAt: Long? = null
        internal set
    var startedAt: Long? = null
        internal set
    var endedAt: Long? = null
        internal set

    /** Id of the saved arena picked for this event (issue #1183), or null. Kept across reset. */
    var arenaId: String? = null
        internal set

    private val registered = LinkedHashSet<UUID>()
    val participants: Set<UUID> get() = registered.toSet()
    val participantCount: Int get() = registered.size

    /**
     * Everyone registered when the event started, with their name then (issue #1199). Unlike
     * [participants] it keeps players who left or were knocked out, so the result record lists
     * who actually took part. Cleared by reset.
     */
    var roster: Map<UUID, String> = emptyMap()
        internal set

    /** What the game detected when it finished (issue #1199), or null (stopped before a result). First report wins. */
    var detected: DetectedResult? = null
        internal set

    /** The reward preset captured when the event started (issue #1199), so mid-event edits don't change the prize. */
    var rewards: List<RewardEntry> = emptyList()
        internal set

    fun reportResult(result: DetectedResult): Boolean {
        if (detected != null || state != EventState.RUNNING) return false
        detected = result
        return true
    }

    /** Free-form per-session data for future event controllers (arena picked, round number, ...). */
    val metadata: MutableMap<String, Any> = HashMap()

    private val tasks = ArrayList<BukkitTask>()

    fun isRegistered(uuid: UUID): Boolean = uuid in registered
    internal fun register(uuid: UUID): Boolean = registered.add(uuid)
    internal fun unregister(uuid: UUID): Boolean = registered.remove(uuid)
    internal fun clearParticipants() = registered.clear()

    /** Ties a scheduler task to this session so stop/reset/shutdown cancel it. */
    fun track(task: BukkitTask): BukkitTask {
        tasks.add(task)
        return task
    }

    val activeTaskCount: Int get() = tasks.count { !it.isCancelled }

    internal fun cancelTasks() {
        tasks.forEach { runCatching { it.cancel() } }
        tasks.clear()
    }
}

/**
 * Extension point for event-specific gameplay (Phase 2: spleef floors, sumo queues, ...).
 * Register one per type with [EventSessions.registerController]. Hooks run on the main
 * thread after the state check passes; an exception from [onStart] rolls the session
 * back to OPEN. Schedule tasks through [EventSession.track] so the core cancels them.
 */
interface EventController {
    fun onOpen(session: EventSession) {}
    fun onStart(session: EventSession) {}
    fun onStop(session: EventSession) {}
    fun onReset(session: EventSession) {}

    /**
     * A registered player left a RUNNING event (issue #1187). Runs before they are
     * unregistered; afterwards they can't rejoin until the next event opens.
     */
    fun onParticipantLeave(session: EventSession, uuid: UUID, reason: LeaveReason) {}
}

/** Why a player left a running event. */
enum class LeaveReason(val label: String) {
    /** `/event leave`. */
    LEFT("left the event"),
    /** Logged off. */
    QUIT("disconnected"),
    /** Teleported or `/spawn`ed out of the event world. */
    LEFT_WORLD("left the event world"),
}
