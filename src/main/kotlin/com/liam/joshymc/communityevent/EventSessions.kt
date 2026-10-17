package com.liam.joshymc.communityevent

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/** Outcome of a lifecycle action; [message] is the feedback for whoever asked. */
sealed interface EventResult {
    val message: String

    data class Success(val session: EventSession, override val message: String) : EventResult
    data class Failure(override val message: String) : EventResult
    /** The action would disrupt players; repeat it confirmed (`/event reset confirm` or the GUI prompt). */
    data class NeedsConfirmation(override val message: String) : EventResult
}

/**
 * The single hosted-event session (issue #1180) and its validated lifecycle.
 *
 * Only one session exists at a time. It never touches inventories, locations, PvP or blocks:
 * the event world's inventory separation and command rules stay in [com.liam.joshymc.manager.EventManager],
 * and gameplay belongs to the [EventController] registered for the type.
 *
 * Sessions live in memory only. [shutdown] cancels a live session's tasks, so after a restart
 * there is no session at all and staff create a new one.
 */
class EventSessions(
    private val plugin: Joshymc,
    /** True while one of the older automatic mini-games (TNT Run, ...) is running. */
    private val legacyGameRunning: () -> Boolean,
) {
    var current: EventSession? = null
        private set

    /** Called after every state or registration change, e.g. to refresh open Event Manager menus. */
    var onChange: (() -> Unit)? = null

    /**
     * Checked before a start; a non-null result is the reason the event can't start
     * (e.g. the selected arena is incomplete or its world is gone).
     */
    var startCheck: ((EventSession) -> String?)? = null

    private val controllers = HashMap<EventType, EventController>()

    private val comms get() = plugin.commsManager

    fun registerController(type: EventType, controller: EventController) {
        controllers[type] = controller
    }

    /** The live session, or null when there is none or the last one finished. */
    val active: EventSession? get() = current?.takeIf { it.state.isActive }

    // ── Lifecycle ─────────────────────────────────────────

    fun create(type: EventType, host: CommandSender): EventResult {
        active?.let {
            return EventResult.Failure("A ${it.type.displayName} event is already ${it.state.label.lowercase()}. Stop or finish it first.")
        }
        if (legacyGameRunning()) {
            return EventResult.Failure("An automatic event-world mini-game is running. Stop it with /event stop first.")
        }
        val session = EventSession(
            id = UUID.randomUUID(),
            type = type,
            hostId = (host as? Player)?.uniqueId,
            hostName = if (host is Player) host.name else "Console",
            createdAt = System.currentTimeMillis(),
        )
        current = session
        plugin.logger.info("[Event] ${session.hostName} created a ${type.displayName} event (${session.id}).")
        changed()
        return EventResult.Success(session, "Created a ${type.displayName} event. Open registration with /event open.")
    }

    fun open(): EventResult {
        val session = current ?: return noEvent()
        when (session.state) {
            EventState.OPEN -> return EventResult.Failure("Registration for ${session.type.displayName} is already open.")
            EventState.RUNNING -> return EventResult.Failure("${session.type.displayName} is already running; registration is closed.")
            EventState.FINISHED -> return EventResult.Failure("The ${session.type.displayName} event has finished. Create a new event or reset this one.")
            EventState.CREATED -> {}
        }
        if (!move(session, EventState.OPEN)) return invalid(session, EventState.OPEN)
        session.openedAt = System.currentTimeMillis()
        val failure = runHook(session, "open") { it.onOpen(session) }
        comms.broadcast(
            Component.text("Community Event: ", NamedTextColor.GOLD)
                .append(Component.text("${session.type.displayName} registration is now open! ", NamedTextColor.YELLOW))
                .append(
                    Component.text("[Click to join]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/event join"))
                        .hoverEvent(HoverEvent.showText(Component.text("Run /event join", NamedTextColor.GRAY)))
                ),
            CommunicationsManager.Category.EVENT,
        )
        changed()
        val note = if (failure != null) " (the ${session.type.displayName} handler reported an error: $failure)" else ""
        return EventResult.Success(session, "Registration is open.$note")
    }

    fun start(): EventResult {
        val session = current ?: return noEvent()
        when (session.state) {
            EventState.CREATED -> return EventResult.Failure("Open registration with /event open before starting.")
            EventState.RUNNING -> return EventResult.Failure("${session.type.displayName} is already running.")
            EventState.FINISHED -> return EventResult.Failure("The ${session.type.displayName} event has finished. Create a new event or reset this one.")
            EventState.OPEN -> {}
        }
        if (session.participantCount == 0) {
            return EventResult.Failure("Nobody has registered yet. Players join with /event join.")
        }
        startCheck?.invoke(session)?.let { return EventResult.Failure(it) }
        if (!move(session, EventState.RUNNING)) return invalid(session, EventState.RUNNING)
        session.startedAt = System.currentTimeMillis()
        val failure = runHook(session, "start") { it.onStart(session) }
        if (failure != null) {
            // Initialization failed: roll back so staff can retry, and don't announce a start.
            session.cancelTasks()
            session.state = EventState.OPEN
            session.startedAt = null
            changed()
            return EventResult.Failure("The ${session.type.displayName} event failed to start: $failure. Registration is still open.")
        }
        comms.broadcast(
            Component.text("The ${session.type.displayName} event has started!", NamedTextColor.GOLD),
            CommunicationsManager.Category.EVENT,
        )
        changed()
        return EventResult.Success(session, "${session.type.displayName} started with ${session.participantCount} participant(s).")
    }

    fun stop(): EventResult {
        val session = active ?: return if (current != null) {
            EventResult.Failure("The ${current!!.type.displayName} event has already finished.")
        } else noEvent()
        val wasState = session.state
        if (!move(session, EventState.FINISHED)) return invalid(session, EventState.FINISHED)
        val failure = finish(session)
        when (wasState) {
            EventState.RUNNING -> comms.broadcast(
                Component.text("The ${session.type.displayName} event has ended. Thanks for participating!", NamedTextColor.GOLD),
                CommunicationsManager.Category.EVENT,
            )
            EventState.OPEN -> comms.broadcast(
                Component.text("The ${session.type.displayName} event was cancelled. Registration is closed.", NamedTextColor.YELLOW),
                CommunicationsManager.Category.EVENT,
            )
            else -> {} // CREATED was never announced, so ending it stays quiet.
        }
        changed()
        return if (failure != null) {
            EventResult.Success(session, "Event ended, but cleanup reported an error: $failure. Check the console.")
        } else {
            EventResult.Success(session, "${session.type.displayName} event ended.")
        }
    }

    /** Whether [reset] would affect registered players or running tasks. */
    fun resetIsDisruptive(): Boolean {
        val session = current ?: return false
        return session.state == EventState.OPEN && session.participantCount > 0 || session.activeTaskCount > 0
    }

    /**
     * Returns the session to CREATED (same type and host) with no registrations or tasks.
     * Refused while RUNNING so nobody is stranded mid-event: stop it first.
     */
    fun reset(confirmed: Boolean): EventResult {
        val session = current ?: return noEvent()
        if (session.state == EventState.RUNNING) {
            return EventResult.Failure("${session.type.displayName} is running. Stop it with /event stop before resetting.")
        }
        if (!confirmed && resetIsDisruptive()) {
            return EventResult.NeedsConfirmation(
                "Resetting clears ${session.participantCount} registration(s) and cancels the event's tasks."
            )
        }
        if (!move(session, EventState.CREATED)) return invalid(session, EventState.CREATED)
        val notify = session.participants.mapNotNull { Bukkit.getPlayer(it) }
        session.cancelTasks()
        session.clearParticipants()
        session.openedAt = null
        session.startedAt = null
        session.endedAt = null
        session.metadata.clear()
        val failure = runHook(session, "reset") { it.onReset(session) }
        if (notify.isNotEmpty()) {
            notify.forEach {
                comms.send(it, Component.text("The ${session.type.displayName} event was reset by staff. Register again when it reopens.", NamedTextColor.YELLOW), CommunicationsManager.Category.EVENT)
            }
        }
        changed()
        val note = if (failure != null) " (the ${session.type.displayName} handler reported an error: $failure)" else ""
        return EventResult.Success(session, "${session.type.displayName} event reset to Created.$note")
    }

    // ── Arena (issue #1183) ───────────────────────────────

    /**
     * Picks a saved arena for the active event. Only an arena of the event's type, and only
     * before the event runs, so a running event never changes arena underneath its players.
     * Nothing is started, teleported or changed in the world.
     */
    fun assignArena(arenaId: String, arenaType: EventType, arenaName: String): EventResult {
        val session = active ?: return EventResult.Failure("There is no active event. Create one with /event create <type> first.")
        if (session.state == EventState.RUNNING) {
            return EventResult.Failure("${session.type.displayName} is running; its arena can't change until it ends.")
        }
        if (arenaType != session.type) {
            return EventResult.Failure("'$arenaName' is a ${arenaType.displayName} arena, but the current event is ${session.type.displayName}.")
        }
        if (session.arenaId == arenaId) return EventResult.Failure("'$arenaName' is already selected for this event.")
        session.arenaId = arenaId
        plugin.logger.info("[Event] Arena '$arenaId' selected for the ${session.type.displayName} event (${session.id}).")
        changed()
        return EventResult.Success(session, "Selected arena '$arenaName' for ${session.type.displayName}.")
    }

    fun clearArena(): EventResult {
        val session = active ?: return EventResult.Failure("There is no active event.")
        if (session.state == EventState.RUNNING) {
            return EventResult.Failure("${session.type.displayName} is running; its arena can't change until it ends.")
        }
        if (session.arenaId == null) return EventResult.Failure("No arena is selected for this event.")
        session.arenaId = null
        changed()
        return EventResult.Success(session, "Cleared the arena for ${session.type.displayName}.")
    }

    // ── Registration ──────────────────────────────────────

    fun join(player: Player): EventResult {
        val session = active ?: return EventResult.Failure("There is no event to join right now.")
        if (session.state != EventState.OPEN) {
            return EventResult.Failure(
                if (session.state == EventState.RUNNING) "${session.type.displayName} has already started."
                else "Registration for ${session.type.displayName} hasn't opened yet."
            )
        }
        if (!session.register(player.uniqueId)) return EventResult.Failure("You're already registered for ${session.type.displayName}.")
        changed()
        return EventResult.Success(session, "You're registered for ${session.type.displayName}! (${session.participantCount} registered)")
    }

    fun leave(player: Player): EventResult {
        val session = active ?: return EventResult.Failure("There is no event right now.")
        if (!session.isRegistered(player.uniqueId)) return EventResult.Failure("You aren't registered for ${session.type.displayName}.")
        if (session.state == EventState.RUNNING) {
            withdraw(player.uniqueId, LeaveReason.LEFT)
        } else {
            session.unregister(player.uniqueId)
            changed()
        }
        return EventResult.Success(session, "You left the ${session.type.displayName} event.")
    }

    /**
     * Players who log off drop out of registration. In a running event the controller is told
     * first (issue #1187), so a disconnect counts as leaving and the player can't come back as
     * an active competitor.
     */
    fun onQuit(uuid: UUID) {
        val session = active ?: return
        when (session.state) {
            EventState.OPEN -> if (session.unregister(uuid)) changed()
            // Types without gameplay yet keep their registrations, as before.
            EventState.RUNNING -> if (session.type in controllers) withdraw(uuid, LeaveReason.QUIT)
            else -> {}
        }
    }

    /** A registered player leaves the RUNNING event: the controller handles it, then they're unregistered. */
    fun withdraw(uuid: UUID, reason: LeaveReason) {
        val session = active ?: return
        if (session.state != EventState.RUNNING || !session.isRegistered(uuid)) return
        runHook(session, "leave") { it.onParticipantLeave(session, uuid, reason) }
        session.unregister(uuid)
        changed()
    }

    /** Server stop: end any live session quietly. Nothing is persisted, so nothing comes back after a restart. */
    fun shutdown() {
        val session = active ?: return
        plugin.logger.info("[Event] Discarding the ${session.type.displayName} event (${session.state.label}) on shutdown; sessions are not kept across restarts.")
        session.state = EventState.FINISHED
        finish(session)
        current = null
    }

    // ── Internals ─────────────────────────────────────────

    private fun move(session: EventSession, next: EventState): Boolean {
        if (!session.state.canMoveTo(next)) return false
        session.state = next
        return true
    }

    /** Cancels the session's tasks and runs the stop hook. Returns the cleanup error, if any. */
    private fun finish(session: EventSession): String? {
        session.endedAt = System.currentTimeMillis()
        val hookFailure = runHook(session, "stop") { it.onStop(session) }
        val taskFailure = runCatching { session.cancelTasks() }.exceptionOrNull()
        session.clearParticipants()
        if (taskFailure != null) plugin.logger.warning("[Event] Failed to cancel event tasks: ${taskFailure.message}")
        return hookFailure ?: taskFailure?.message
    }

    /** Runs the type's controller hook, returning the error message if it threw. */
    private fun runHook(session: EventSession, name: String, hook: (EventController) -> Unit): String? {
        val controller = controllers[session.type] ?: return null
        return try {
            hook(controller)
            null
        } catch (e: Exception) {
            plugin.logger.warning("[Event] ${session.type.id} $name hook failed: ${e.message}")
            e.printStackTrace()
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun changed() {
        runCatching { onChange?.invoke() }.onFailure {
            plugin.logger.warning("[Event] Failed to refresh event menus: ${it.message}")
        }
    }

    private fun noEvent() = EventResult.Failure("There is no event. Create one with /event create <type>.")

    private fun invalid(session: EventSession, next: EventState) =
        EventResult.Failure("Can't move the ${session.type.displayName} event from ${session.state.label} to ${next.label}.")
}
