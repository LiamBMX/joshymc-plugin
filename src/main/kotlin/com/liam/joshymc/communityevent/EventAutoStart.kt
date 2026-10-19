package com.liam.joshymc.communityevent

import com.liam.joshymc.Joshymc
import com.liam.joshymc.communityevent.arena.EventArena
import com.liam.joshymc.communityevent.arena.EventArenas
import com.liam.joshymc.communityevent.game.HostedGames
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.scheduler.BukkitTask

/**
 * One-command hosting (issue #1248): `/event start <type>` runs the same steps staff used to
 * type by hand (create, arena select, open, start) on the one [EventSessions] lifecycle.
 *
 * Everything that can be checked before registration (event world, the arena named after the
 * type, its readiness, the game's own setup) is checked first, so a refused start leaves no
 * session behind. Registration then stays open for [RegistrationCountdown.REGISTRATION_SECONDS]
 * and the event starts through [EventSessions.start], with the same start checks as a manual start.
 * The countdown is one task tracked by the session, so `/event stop`, reset and shutdown cancel it.
 */
class EventAutoStart(
    private val plugin: Joshymc,
    private val sessions: EventSessions,
    private val arenas: EventArenas,
    private val games: HostedGames,
    private val eventWorld: () -> String,
    private val eventWorldProblem: () -> String?,
) {
    private val comms get() = plugin.commsManager
    private val category = CommunicationsManager.Category.EVENT

    fun start(type: EventType, host: CommandSender): EventResult {
        val name = type.displayName
        sessions.active?.let { return EventResult.Failure("Cannot start $name: ${conflict(it)}") }

        val world = eventWorld()
        if (Bukkit.getWorld(world) == null) {
            val why = eventWorldProblem()?.let { " ($it)" } ?: ""
            return EventResult.Failure("Cannot start $name: the event world '$world' isn't loaded$why.")
        }
        val arena = when (val match = findArena(type)) {
            is ArenaMatch.Found -> match.arena
            is ArenaMatch.Missing -> return EventResult.Failure(match.message)
        }
        val report = arenas.report(arena)
        if (!report.isReady) {
            return EventResult.Failure("Cannot start $name: arena '${arena.displayName}' isn't ready (${report.summary()}). Fix it with /event arena info ${arena.displayName}.")
        }
        games.gameFor(type)?.setupProblem(arena)?.let { return EventResult.Failure("Cannot start $name: $it") }

        val session = when (val created = sessions.create(type, host)) {
            is EventResult.Success -> created.session
            else -> return EventResult.Failure("Cannot start $name: ${created.message}")
        }
        val selected = arenas.select(arena, host)
        if (selected !is EventResult.Success) {
            sessions.abandon(session)
            return EventResult.Failure("Cannot start $name: ${selected.message}")
        }
        val opened = sessions.open(autoStartSeconds = RegistrationCountdown.REGISTRATION_SECONDS)
        if (opened !is EventResult.Success) {
            sessions.abandon(session)
            return EventResult.Failure("Cannot start $name: ${opened.message}")
        }
        scheduleCountdown(session)
        plugin.logger.info("[Event] ${session.hostName} started a $name event with arena '${arena.id}'; registration closes in ${RegistrationCountdown.REGISTRATION_SECONDS}s.")
        return EventResult.Success(
            session,
            "$name registration is open with arena '${arena.displayName}'. The event starts by itself in " +
                "${RegistrationCountdown.describe(RegistrationCountdown.REGISTRATION_SECONDS)}; cancel with /event stop.",
        )
    }

    // ── Arena lookup ──────────────────────────────────────

    private sealed interface ArenaMatch {
        data class Found(val arena: EventArena) : ArenaMatch
        data class Missing(val message: String) : ArenaMatch
    }

    /**
     * The saved arena of [type] named after it ("spleef", "Mob_Waves", "sharks-and-minnows", ...),
     * compared like [EventType.fromName]. Only arenas of that type count, so an unrelated arena
     * that happens to share the name is never picked. Nothing is created or changed.
     */
    private fun findArena(type: EventType): ArenaMatch {
        val own = arenas.ofType(type)
        for (wanted in type.names) {
            own.firstOrNull { EventType.normalize(it.displayName) == wanted }?.let { return ArenaMatch.Found(it) }
        }
        val start = "Cannot start ${type.displayName}: No configured ${type.displayName} arena was found."
        val wrongType = arenas.all().firstOrNull { it.type != type && EventType.normalize(it.displayName) in type.names }
        val hint = when {
            wrongType != null -> " '${wrongType.displayName}' is a ${wrongType.type.displayName} arena."
            own.isNotEmpty() -> " ${type.displayName} arenas exist (${own.joinToString(", ") { it.displayName }}), but none is named '${type.id}'."
            else -> ""
        }
        return ArenaMatch.Missing("$start$hint Create one named '${type.id}' with /event arena create ${type.id} ${type.id}.")
    }

    // ── Countdown ─────────────────────────────────────────

    private fun scheduleCountdown(session: EventSession) {
        var lastAnnounced = RegistrationCountdown.REGISTRATION_SECONDS
        var fired = false
        lateinit var task: BukkitTask
        task = session.track(Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (fired) return@Runnable
            val deadline = session.autoStartAtNanos
            // Stopped, reset, started early with /event start, or replaced: this countdown is over.
            if (deadline == null || sessions.active !== session || session.state != EventState.OPEN) {
                task.cancel()
                return@Runnable
            }
            val remaining = RegistrationCountdown.remainingSeconds(deadline, System.nanoTime())
            if (remaining <= 0) {
                fired = true
                task.cancel()
                closeRegistration(session)
                return@Runnable
            }
            RegistrationCountdown.due(remaining, lastAnnounced)?.let {
                lastAnnounced = it
                announce(session, it)
            }
        }, CHECK_TICKS, CHECK_TICKS))
    }

    private fun announce(session: EventSession, seconds: Long) {
        val name = session.type.displayName
        var line = Component.text("$name starts in ${RegistrationCountdown.describe(seconds)}!", NamedTextColor.YELLOW)
        if (seconds >= 60) {
            line = line.append(Component.text(" Use ", NamedTextColor.YELLOW)).append(
                Component.text("/event join", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/event join"))
                    .hoverEvent(HoverEvent.showText(Component.text("Run /event join", NamedTextColor.GRAY)))
            ).append(Component.text("!", NamedTextColor.YELLOW))
        }
        comms.broadcast(line, category)
    }

    /** Registration time is up: start through the normal lifecycle, or cancel without a result. */
    private fun closeRegistration(session: EventSession) {
        if (sessions.active !== session || session.state != EventState.OPEN) return
        val name = session.type.displayName
        val registered = session.participantCount
        val shortage = games.gameFor(session.type)?.playerProblem(registered)
            ?: if (registered == 0) "nobody registered" else null
        if (shortage != null) {
            val min = games.gameFor(session.type)?.minPlayers() ?: 1
            sessions.stop(cancelMessage = "$name registration ended without enough participants ($registered/$min). The event was cancelled.")
            tellHost(session, "$name was cancelled: $shortage")
            plugin.logger.info("[Event] $name auto-start cancelled: $shortage")
            return
        }
        when (val result = sessions.start()) {
            is EventResult.Success -> plugin.logger.info("[Event] $name started automatically: ${result.message}")
            else -> {
                plugin.logger.warning("[Event] $name auto-start failed: ${result.message}")
                if (sessions.active === session && session.state == EventState.OPEN) {
                    sessions.stop(cancelMessage = "$name couldn't start and was cancelled.")
                }
                tellHost(session, "$name couldn't start automatically and was cancelled: ${result.message}")
            }
        }
    }

    private fun tellHost(session: EventSession, message: String) {
        val host = session.hostId?.let { Bukkit.getPlayer(it) } ?: return
        comms.send(host, Component.text(message, NamedTextColor.RED), category)
    }

    /** Why a new event can't start while [current] is active. Nothing about it is changed. */
    private fun conflict(current: EventSession): String {
        val name = current.type.displayName
        return when (current.state) {
            EventState.RUNNING -> "$name is already running. End it with /event stop first."
            EventState.OPEN -> current.autoStartRemainingSeconds()?.let {
                "$name registration is already open and starts in ${RegistrationCountdown.describe(it)}. Cancel it with /event stop first."
            } ?: "a $name event already has registration open (${current.participantCount} registered). Start it with /event start or end it with /event stop."
            EventState.CREATED -> "a $name event was already created. Continue it with /event open, or end it with /event stop."
            EventState.FINISHED -> "the $name event is finishing."
        }
    }

    private companion object {
        /** Checked twice a second; the deadline itself is real time. */
        const val CHECK_TICKS = 10L
    }
}
