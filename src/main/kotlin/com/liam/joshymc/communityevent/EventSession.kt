package com.liam.joshymc.communityevent

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

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

    private val registered = LinkedHashSet<UUID>()
    val participants: Set<UUID> get() = registered.toSet()
    val participantCount: Int get() = registered.size

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
}
