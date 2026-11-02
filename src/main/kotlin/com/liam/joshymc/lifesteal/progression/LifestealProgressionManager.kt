package com.liam.joshymc.lifesteal.progression

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.lifesteal.progression.ProgressionRules.Milestone
import com.liam.joshymc.lifesteal.progression.ProgressionRules.Phase
import com.liam.joshymc.lifesteal.progression.ProgressionRules.Progress
import com.liam.joshymc.manager.AnnouncementManager
import com.liam.joshymc.manager.CommunicationsManager
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask

/**
 * The one Lifesteal launch-progression service (issue #1352). Lifesteal only: on a server that
 * doesn't host Lifesteal it creates no tables, starts no task and its listener isn't registered,
 * so Survival PvP, End access and announcements are untouched.
 *
 * The season row in `data.db` ([ProgressionStore]) holds the one authoritative launch time (UTC);
 * every check derives the state from it and the clock through [ProgressionRules], from an in-memory
 * copy, so combat and teleport checks never touch the database. Milestones that have been reached
 * are recorded once per season (so a clock going back never relocks anything) and announced once.
 * One light task (every [TICK_SECONDS]s, cancelled once every milestone is recorded) does the
 * recording and the announcements; enforcement never waits for it.
 *
 * Whenever the state can't be established (invalid lifesteal.yml, database error, malformed season)
 * the phase is [Phase.UNKNOWN]: PvP stays protected, no heart moves and the End stays closed.
 */
class LifestealProgressionManager(private val plugin: Joshymc) {

    /**
     * What a future Ranked Team Wars system registers (Phase 3) so the progression can tell
     * "unlocked by schedule" from "playable". Nothing implements it yet.
     */
    fun interface RankedTeamWarsSystem {
        fun isAvailable(): Boolean
    }

    @Volatile var rankedTeamWarsSystem: RankedTeamWarsSystem? = null

    enum class Status(val label: String) {
        NOT_HOSTED("not used on this server"),
        DISABLED("disabled in lifesteal.yml (progression.enabled: false)"),
        READY("ready"),
        UNAVAILABLE("unavailable, see the console"),
    }

    private data class State(
        val status: Status,
        val settings: LifestealSettings.LaunchProgression? = null,
        val schedule: ProgressionRules.Schedule? = null,
        val season: ProgressionRules.Season? = null,
        val recorded: Set<Milestone> = emptySet(),
        val announced: Set<Milestone> = emptySet(),
        val problem: String? = null,
        /** A database problem (retried by the task); config problems wait for /joshymc reload. */
        val retry: Boolean = false,
    )

    private val store = ProgressionStore(plugin.databaseManager)
    @Volatile private var state = State(Status.NOT_HOSTED)
    private var task: BukkitTask? = null
    private var lastProblemLogged: String? = null
    private var lastSeenNow = 0L
    private var configDiscrepancyLogged: Long? = null

    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes
    val status: Status get() = state.status
    val problem: String? get() = state.problem
    val settings: LifestealSettings.LaunchProgression? get() = state.settings

    fun isLifesteal(world: World?): Boolean = plugin.gamemodeLockManager.gamemodeOf(world) == ServerGamemode.LIFESTEAL

    // ── Read API (cheap, memory only) ───────────────────────

    fun progress(now: Long = System.currentTimeMillis()): Progress {
        val s = state
        return when (s.status) {
            Status.NOT_HOSTED, Status.DISABLED -> ProgressionRules.disabled(now)
            Status.UNAVAILABLE -> ProgressionRules.unknown(now, s.season, s.schedule)
            Status.READY -> ProgressionRules.evaluate(s.season, s.schedule!!, now, s.recorded)
        }
    }

    /** For the heart service: what a PvP kill at [now] may do. */
    fun heartWindow(now: Long = System.currentTimeMillis()): ProgressionRules.HeartWindow = progress(now).heartWindow

    /** Hostile PvP between players in Lifesteal is blocked right now (before launch, grace, unknown). */
    fun pvpProtected(now: Long = System.currentTimeMillis()): Boolean = hosted && progress(now).pvpProtected

    fun endLocked(now: Long = System.currentTimeMillis()): Boolean = hosted && progress(now).endLocked

    /** Locked for this player: only an explicit [END_BYPASS_PERMISSION] grant gets in early. */
    fun endLockedFor(player: Player, now: Long = System.currentTimeMillis()): Boolean =
        endLocked(now) && !player.hasPermission(END_BYPASS_PERMISSION)

    fun rankedStatus(now: Long = System.currentTimeMillis()): ProgressionRules.RankedStatus {
        val system = rankedTeamWarsSystem
        val available = system != null && try { system.isAvailable() } catch (_: Exception) { false }
        return ProgressionRules.rankedStatus(progress(now), available)
    }

    /** What a future ranked queue must check before letting anyone in. */
    fun canEnterRankedQueue(now: Long = System.currentTimeMillis()): Boolean =
        hosted && rankedStatus(now) == ProgressionRules.RankedStatus.AVAILABLE

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload, after lifestealConfigManager.load(). */
    fun refresh() {
        cancelTask()
        lastProblemLogged = null
        if (!hosted) {
            state = State(Status.NOT_HOSTED)
            return
        }
        val settings = plugin.lifestealConfigManager.settings
        if (settings == null) {
            fail(State(Status.UNAVAILABLE, problem = "lifesteal.yml is invalid"),
                "lifesteal.yml is invalid, so the launch progression is unavailable: PvP stays protected, no heart is stolen and the End stays closed until it is fixed (then /joshymc reload).")
            return
        }
        val progression = settings.progression
        if (!progression.enabled) {
            state = State(Status.DISABLED, settings = progression)
            plugin.logger.warning("[Progression] progression.enabled is false: no launch schedule on this server (no grace period, the End is open, Ranked Team Wars are not held back).")
            return
        }
        load(progression, initial = true)
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, TICK_SECONDS * 20L, TICK_SECONDS * 20L)
    }

    fun stop() {
        cancelTask()
    }

    private fun cancelTask() {
        task?.cancel()
        task = null
    }

    private fun fail(next: State, message: String) {
        state = next
        if (lastProblemLogged != message) {
            lastProblemLogged = message
            plugin.logger.severe("[Progression] $message")
        }
    }

    /** Reads the active season (seeding it from config once), then records/announces what is due. */
    private fun load(progression: LifestealSettings.LaunchProgression, initial: Boolean) {
        val schedule = ProgressionRules.Schedule.of(progression)
        val now = System.currentTimeMillis()
        try {
            store.createTables()
            var season = store.activeSeason()
            val configured = progression.launchTimestamp
            if (season == null && configured != null) {
                if (store.createSeason(ProgressionRules.FIRST_SEASON_ID, configured, "config", "lifesteal.yml progression.launch-timestamp", now)) {
                    plugin.logger.info("[Progression] Started ${ProgressionRules.FIRST_SEASON_ID} from lifesteal.yml: launch ${ProgressionRules.formatUtc(configured)}.")
                }
                season = store.activeSeason()
            }
            if (season != null && configured != null && configured != season.launchAt && configDiscrepancyLogged != configured) {
                configDiscrepancyLogged = configured
                plugin.logger.warning(
                    "[Progression] lifesteal.yml progression.launch-timestamp (${ProgressionRules.formatUtc(configured)}) differs from the stored launch of " +
                        "${season.id} (${ProgressionRules.formatUtc(season.launchAt)}). The stored launch is authoritative; the config value is ignored. " +
                        "Use /lifesteal admin launch schedule to move a launch that hasn't happened yet."
                )
            }
            val rows = season?.let { store.milestones(it.id) }.orEmpty()
            state = State(
                Status.READY, progression, schedule, season,
                recorded = rows.keys, announced = rows.values.filter { it.announced }.map { it.milestone }.toSet(),
            )
            if (lastProblemLogged != null) plugin.logger.info("[Progression] The launch progression is readable again.")
            lastProblemLogged = null
        } catch (e: Exception) {
            fail(
                State(Status.UNAVAILABLE, progression, schedule, state.season, problem = "database: ${e.message}", retry = true),
                "Could not read the launch progression (${e.message}); PvP stays protected, no heart is stolen and the End stays closed until it can be read. Retrying every ${TICK_SECONDS}s."
            )
            return
        }
        if (initial) logState()
        processMilestones(now)
    }

    private fun logState() {
        val p = progress()
        val season = p.season
        if (season == null) {
            plugin.logger.info("[Progression] No launch is scheduled yet: PvP is protected and the End is closed until staff run /lifesteal admin launch schedule|activate.")
        } else {
            plugin.logger.info("[Progression] ${season.id}: launch ${ProgressionRules.formatUtc(season.launchAt)}, phase ${p.phase.label}.")
        }
    }

    private fun tick() {
        val s = state
        when (s.status) {
            Status.UNAVAILABLE -> if (s.retry && s.settings != null) load(s.settings, initial = false)
            Status.READY -> processMilestones(System.currentTimeMillis())
            else -> cancelTask()
        }
    }

    /** Records milestones that are due and announces the latest new one. Main thread. */
    private fun processMilestones(now: Long) {
        if (lastSeenNow != 0L && now < lastSeenNow - CLOCK_JUMP_MILLIS) {
            plugin.logger.warning("[Progression] The system clock went back by ${(lastSeenNow - now) / 1000}s. Milestones already reached stay reached.")
        }
        lastSeenNow = now
        val s = state
        if (s.status != Status.READY) return
        val season = s.season ?: return
        val schedule = s.schedule ?: return
        val settings = s.settings ?: return
        val progress = ProgressionRules.evaluate(season, schedule, now, s.recorded)
        val due = progress.reached.filter { it !in s.recorded }.associateWith { season.launchAt + schedule.offsetMillis(it) }
        var recorded = s.recorded
        var announced = s.announced
        try {
            if (due.isNotEmpty()) recorded = recorded + store.recordMilestones(season.id, due, now) + due.keys
            val target = ProgressionRules.announcementFor(recorded, announced)
            if (target != null) {
                val claimed = store.claimAnnouncement(season.id, target, recorded - announced - target)
                announced = announced + recorded
                if (claimed && settings.announcements.enabled) announce(target, settings.announcements)
            }
        } catch (e: Exception) {
            // Enforcement keeps using the time-derived state; the recording is retried next tick.
            if (lastProblemLogged != e.message) {
                lastProblemLogged = e.message
                plugin.logger.warning("[Progression] Could not record a reached milestone (${e.message}); retrying.")
            }
            return
        }
        state = s.copy(recorded = recorded, announced = announced)
        if (Milestone.entries.all { it in recorded && it in announced }) cancelTask()
    }

    private fun announce(milestone: Milestone, messages: LifestealSettings.ProgressionAnnouncements) {
        val text = when (milestone) {
            Milestone.LAUNCH -> messages.launch
            Milestone.GRACE_ENDED -> messages.graceEnded
            Milestone.RANKED_UNLOCKED ->
                if (rankedStatus() == ProgressionRules.RankedStatus.AVAILABLE) messages.rankedUnlocked else messages.rankedUnlockedUnavailable
            Milestone.END_UNLOCKED -> messages.endUnlocked
        }
        val body = AnnouncementManager.format(text, plugin.commsManager::parseLegacy) ?: return
        val component = AnnouncementManager.formatBroadcast(body)
        // Lifesteal players only (a mixed server's Survival worlds hear nothing), plus the console.
        for (player in Bukkit.getOnlinePlayers()) {
            if (isLifesteal(player.world)) player.sendMessage(component)
        }
        plugin.server.consoleSender.sendMessage(component)
    }

    // ── Players ─────────────────────────────────────────────

    /** One reminder line on join while PvP is still protected. */
    fun onJoin(player: Player) {
        val settings = state.settings ?: return
        if (!settings.joinReminder || state.status != Status.READY || !isLifesteal(player.world)) return
        val p = progress()
        val line = when (p.phase) {
            Phase.NOT_SCHEDULED -> "&eLifesteal hasn't launched yet. &7PvP is off until the grace period ends."
            Phase.PRE_LAUNCH -> "&eLifesteal launches in &f${ProgressionRules.formatRemaining(p.remaining(Milestone.LAUNCH) ?: 0)}&e. &7PvP is off until the grace period ends."
            Phase.GRACE_PERIOD -> "&eGrace period: &7PvP is off and no hearts can be stolen for another &f${ProgressionRules.formatRemaining(p.remaining(Milestone.GRACE_ENDED) ?: 0)}&7."
            else -> return
        }
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("$line &8(/lifesteal progress)"), CommunicationsManager.Category.DEFAULT)
    }

    // ── Admin ───────────────────────────────────────────────

    sealed interface AdminResult {
        data class Done(val message: String) : AdminResult
        data class Refused(val reason: String) : AdminResult
        /** Shows what would happen; the same command with `confirm` does it. */
        data class NeedsConfirmation(val preview: String) : AdminResult
    }

    fun scheduleLaunch(sender: CommandSender, launchAt: Long, confirmed: Boolean): AdminResult {
        val now = System.currentTimeMillis()
        return apply(sender, ProgressionRules.schedule(progress(now), launchAt, now), "SCHEDULE", confirmed, now)
    }

    fun activateLaunch(sender: CommandSender, confirmed: Boolean): AdminResult {
        val now = System.currentTimeMillis()
        return apply(sender, ProgressionRules.activate(progress(now), now), "ACTIVATE", confirmed, now)
    }

    private fun apply(sender: CommandSender, decision: ProgressionRules.ScheduleDecision, action: String, confirmed: Boolean, now: Long): AdminResult {
        val s = state
        val settings = s.settings
        if (s.status != Status.READY || settings == null) {
            return AdminResult.Refused(if (s.status == Status.DISABLED) "Launch progression is disabled in lifesteal.yml (progression.enabled)." else "The launch progression isn't available right now; see the console.")
        }
        val actor = actorOf(sender)
        val activating = action == "ACTIVATE"
        val graceText = "${settings.gracePeriodHours}h grace"
        when (decision) {
            is ProgressionRules.ScheduleDecision.Refused -> return AdminResult.Refused(decision.reason)
            is ProgressionRules.ScheduleDecision.Create -> {
                if (!confirmed) return AdminResult.NeedsConfirmation(
                    if (activating) "This launches ${ProgressionRules.FIRST_SEASON_ID} NOW ($graceText starts immediately). It can't be undone."
                    else "This schedules ${ProgressionRules.FIRST_SEASON_ID} to launch at ${ProgressionRules.formatUtc(decision.launchAt)} ($graceText from then)."
                )
                try {
                    if (!store.createSeason(ProgressionRules.FIRST_SEASON_ID, decision.launchAt, actor, "command:$action", now)) {
                        return AdminResult.Refused("A season already exists; nothing changed.")
                    }
                } catch (e: Exception) {
                    return AdminResult.Refused("The launch couldn't be saved (${e.message}); nothing changed.")
                }
            }
            is ProgressionRules.ScheduleDecision.Move -> {
                val season = s.season ?: return AdminResult.Refused("No season is loaded.")
                if (!confirmed) return AdminResult.NeedsConfirmation(
                    if (activating) "This launches ${season.id} NOW instead of at ${ProgressionRules.formatUtc(decision.from)} ($graceText starts immediately). It can't be undone."
                    else "This moves the launch of ${season.id} from ${ProgressionRules.formatUtc(decision.from)} to ${ProgressionRules.formatUtc(decision.to)}."
                )
                try {
                    store.moveLaunch(season.id, season.version, decision.from, decision.to, actor, action, now)
                } catch (e: ProgressionStore.StaleWriteException) {
                    load(settings, initial = false)
                    return AdminResult.Refused("The season changed or launched meanwhile; nothing changed. Check /lifesteal admin launch status.")
                } catch (e: Exception) {
                    return AdminResult.Refused("The launch couldn't be saved (${e.message}); nothing changed.")
                }
            }
        }
        plugin.logger.warning("[Progression] ${sender.name} ran launch $action: ${describe(decision)}.")
        load(settings, initial = false)
        if (task == null) task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, TICK_SECONDS * 20L, TICK_SECONDS * 20L)
        return AdminResult.Done(if (activating) "Lifesteal has launched." else "Launch scheduled for ${ProgressionRules.formatUtc(launchOf(decision))}.")
    }

    private fun launchOf(decision: ProgressionRules.ScheduleDecision): Long = when (decision) {
        is ProgressionRules.ScheduleDecision.Create -> decision.launchAt
        is ProgressionRules.ScheduleDecision.Move -> decision.to
        is ProgressionRules.ScheduleDecision.Refused -> 0L
    }

    private fun describe(decision: ProgressionRules.ScheduleDecision): String = when (decision) {
        is ProgressionRules.ScheduleDecision.Create -> "created ${ProgressionRules.FIRST_SEASON_ID} with launch ${ProgressionRules.formatUtc(decision.launchAt)}"
        is ProgressionRules.ScheduleDecision.Move -> "launch moved ${ProgressionRules.formatUtc(decision.from)} -> ${ProgressionRules.formatUtc(decision.to)}"
        is ProgressionRules.ScheduleDecision.Refused -> decision.reason
    }

    private fun actorOf(sender: CommandSender): String = if (sender is Player) "${sender.uniqueId} (${sender.name})" else sender.name

    fun recentAudit(limit: Int): List<ProgressionStore.AuditRow> = store.recentAudit(limit)

    companion object {
        /** Explicit grant only (default false): enter the End before it unlocks, for staff emergencies. */
        const val END_BYPASS_PERMISSION = "joshymc.lifesteal.progression.end-bypass"
        const val ADMIN_PERMISSION = "joshymc.lifesteal.progression.admin"
        const val PROGRESS_PERMISSION = "joshymc.lifesteal.progress"
        const val TICK_SECONDS = 20L
        private const val CLOCK_JUMP_MILLIS = 5 * 60_000L
    }
}
