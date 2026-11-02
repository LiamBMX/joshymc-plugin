// Checks the Lifesteal launch progression (issue #1352): the progression settings, the Bukkit-free
// ProgressionRules with fixed clocks (exact 24/72/168-hour boundaries), the heart-rule integration,
// the ProgressionStore against a real SQLite database (fresh temp directories, deleted afterwards,
// reopened to simulate restarts), and the Lifesteal-only wiring. The Gradle build has no test source
// set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-progression-check.init.gradle.kts lifestealProgressionCheck
//
// Exits non-zero on the first failed check. Real damage events, portals, teleports, Bedrock clients
// and announcements on a live server are NOT covered here (see docs/lifesteal-progression.md,
// "Manual tests").

import com.liam.joshymc.lifesteal.LifestealConfigParser
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.progression.ProgressionRules
import com.liam.joshymc.lifesteal.progression.ProgressionRules.HeartWindow
import com.liam.joshymc.lifesteal.progression.ProgressionRules.Milestone
import com.liam.joshymc.lifesteal.progression.ProgressionRules.Phase
import com.liam.joshymc.lifesteal.progression.ProgressionRules.RankedStatus
import com.liam.joshymc.lifesteal.progression.ProgressionRules.ScheduleDecision
import com.liam.joshymc.lifesteal.progression.ProgressionRules.Season
import com.liam.joshymc.lifesteal.progression.ProgressionStore
import com.liam.joshymc.lifesteal.teams.TeamCombatRules
import com.liam.joshymc.manager.DatabaseManager
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }
private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()
private fun resourceText(name: String): String = File("src/main/resources/$name").readText()

private const val H = 3_600_000L
/** 2026-12-01T18:00:00Z */
private val LAUNCH = ProgressionRules.parseTimestamp("2026-12-01T18:00:00Z")!!
private val SCHEDULE = ProgressionRules.Schedule(24, 72, 168)
private val SEASON = Season(ProgressionRules.FIRST_SEASON_ID, LAUNCH, 1)
private val LIMITS = HeartRules.Limits(10, 1, 20)

fun main() {
    settings()
    timestamps()
    rules()
    hearts()
    adminDecisions()
    store()
    wiring()
    println("OK: $checks Lifesteal progression checks passed.")
}

// ── Settings ─────────────────────────────────────────────────

private fun bundled(): Map<String, Any?> {
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    return Yaml().load(text)
}

private fun parseWith(map: Map<String, Any?>) =
    LifestealConfigParser.parse { p -> p.split('.').fold<String, Any?>(map) { node, key -> (node as? Map<*, *>)?.get(key) } }

@Suppress("UNCHECKED_CAST")
private fun withProgression(edit: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> {
    val map = bundled().toMutableMap()
    val section = (map["progression"] as Map<String, Any?>).toMutableMap()
    edit(section)
    map["progression"] = section
    return map
}

private fun settings() {
    val result = parseWith(bundled())
    check("bundled lifesteal.yml is valid", result.settings != null)
    check("bundled lifesteal.yml has no warnings", result.problems.isEmpty())
    val p = result.settings!!.progression
    check("bundled progression = defaults", p == LifestealSettings.LaunchProgression())
    check("approved schedule: 24h grace", p.gracePeriodHours == 24)
    check("approved schedule: ranked +72h (Day 3)", p.rankedTeamWarsUnlockAfterHours == 72)
    check("approved schedule: End +168h (Day 7)", p.endUnlockAfterHours == 168)
    check("no launch time by default (never auto-starts)", p.launchTimestamp == null)
    check("progression enabled by default", p.enabled)

    val missing = parseWith(bundled().toMutableMap().also { it.remove("progression") })
    check("missing section: valid with defaults", missing.settings?.progression == LifestealSettings.LaunchProgression())
    check("missing section: one warning", missing.problems.size == 1 && !missing.problems[0].severe)

    val launch = parseWith(withProgression { it["launch-timestamp"] = "2026-12-01T13:00:00-05:00" })
    check("launch timestamp with offset parses as UTC", launch.settings?.progression?.launchTimestamp == LAUNCH)
    for (bad in listOf("2026-12-01 18:00", "2026-12-01T18:00:00", "tomorrow", "2026-13-01T00:00:00Z", "1999-01-01T00:00:00Z", 5)) {
        val r = parseWith(withProgression { it["launch-timestamp"] = bad })
        check("invalid launch timestamp '$bad' is refused (file invalid)", r.settings == null && r.problems.any { it.severe && "launch-timestamp" in it.message })
    }
    val date = parseWith(withProgression { it["launch-timestamp"] = java.util.Date(LAUNCH) })
    check("an unquoted YAML date is refused (time zone unknown)", date.settings == null && date.problems.any { "quotes" in it.message })
    check("empty launch timestamp = none", parseWith(withProgression { it["launch-timestamp"] = "" }).settings?.progression?.launchTimestamp == null)

    check("ranked before grace is refused", parseWith(withProgression { it["ranked-team-wars-unlock-after-hours"] = 12 }).settings == null)
    check("End before ranked is refused", parseWith(withProgression { it["end-unlock-after-hours"] = 48 }).settings == null)
    check("negative hours are refused", parseWith(withProgression { it["end-unlock-after-hours"] = -1 }).settings == null)
    check("fractional hours are refused", parseWith(withProgression { it["grace-period-hours"] = 1.5 }).settings == null)
    check("enabled must be a boolean", parseWith(withProgression { it["enabled"] = "yes" }).settings == null)
    check("disabled parses", parseWith(withProgression { it["enabled"] = false }).settings?.progression?.enabled == false)
    check("an empty announcement is refused", parseWith(withProgression {
        it["announcements"] = mapOf("enabled" to true, "launch" to " ", "grace-ended" to "a", "ranked-unlocked" to "a", "ranked-unlocked-unavailable" to "a", "end-unlocked" to "a")
    }).settings == null)

    // Old day-numbered keys (issue #1336, "day 1 = launch day") are no longer read.
    val legacyDefault = parseWith(withProgression {
        it.remove("ranked-team-wars-unlock-after-hours"); it.remove("end-unlock-after-hours")
        it["ranked-team-wars-unlock-day"] = 3; it["end-unlock-day"] = 7
    })
    check("legacy default days: valid, approved hours", legacyDefault.settings?.progression?.let { it.rankedTeamWarsUnlockAfterHours == 72 && it.endUnlockAfterHours == 168 } == true)
    check("legacy default days: warned", legacyDefault.problems.any { !it.severe && "ranked-team-wars-unlock-day" in it.message })
    val legacyCustom = parseWith(withProgression { it.remove("end-unlock-after-hours"); it["end-unlock-day"] = 5 })
    check("legacy custom day is refused, never reinterpreted", legacyCustom.settings == null && legacyCustom.problems.any { it.severe && "end-unlock-day" in it.message })
}

// ── Timestamps ───────────────────────────────────────────────

private fun timestamps() {
    check("Z timestamp", ProgressionRules.parseTimestamp("2026-12-01T18:00:00Z") == LAUNCH)
    check("offset timestamp", ProgressionRules.parseTimestamp("2026-12-02T03:00:00+09:00") == LAUNCH)
    check("no offset = ambiguous, refused", ProgressionRules.parseTimestamp("2026-12-01T18:00:00") == null)
    check("blank = none", ProgressionRules.parseTimestamp("  ") == null && ProgressionRules.parseTimestamp(null) == null)
    check("year 20266 refused", ProgressionRules.parseTimestamp("+20266-12-01T18:00:00Z") == null)
    check("formatted back as UTC", ProgressionRules.formatUtc(LAUNCH) == "2026-12-01T18:00:00Z")
    check("remaining: days", ProgressionRules.formatRemaining(6 * 24 * H + 23 * H) == "6d 23h")
    check("remaining: hours", ProgressionRules.formatRemaining(5 * H + 12 * 60_000) == "5h 12m")
    check("remaining: seconds", ProgressionRules.formatRemaining(45_000) == "45s")
    check("remaining never negative", ProgressionRules.formatRemaining(-5) == "0s")
    check("season ids", ProgressionRules.validSeasonId("season-1") && !ProgressionRules.validSeasonId("Season 1") && !ProgressionRules.validSeasonId(""))
}

// ── Rules (deterministic clocks) ─────────────────────────────

private fun at(offset: Long, recorded: Set<Milestone> = emptySet()) = ProgressionRules.evaluate(SEASON, SCHEDULE, LAUNCH + offset, recorded)

private fun rules() {
    val none = ProgressionRules.evaluate(null, SCHEDULE, LAUNCH, emptySet())
    check("no season: not scheduled", none.phase == Phase.NOT_SCHEDULED)
    check("no season: PvP protected", none.pvpProtected)
    check("no season: End locked", none.endLocked)
    check("no season: ranked locked", ProgressionRules.rankedStatus(none, true) == RankedStatus.LOCKED)
    check("no season: no heart moves", none.heartWindow == HeartWindow.NOT_LAUNCHED)
    check("no season: no remaining time", none.remaining(Milestone.GRACE_ENDED) == null)

    val pre = at(-1)
    check("1ms before launch: pre-launch", pre.phase == Phase.PRE_LAUNCH && !pre.launched)
    check("pre-launch: PvP protected, End locked", pre.pvpProtected && pre.endLocked)
    check("pre-launch: no heart moves", pre.heartWindow == HeartWindow.NOT_LAUNCHED)
    check("pre-launch: launch countdown", pre.remaining(Milestone.LAUNCH) == 1L)

    val launch = at(0)
    check("at launch: grace period", launch.phase == Phase.GRACE_PERIOD && launch.launched)
    check("grace: PvP protected", launch.pvpProtected)
    check("grace: no heart moves", launch.heartWindow == HeartWindow.GRACE_PERIOD)
    check("grace: 24h left", launch.remaining(Milestone.GRACE_ENDED) == 24 * H)

    val lastGraceMs = at(24 * H - 1)
    check("24h - 1ms: still grace", lastGraceMs.phase == Phase.GRACE_PERIOD && lastGraceMs.pvpProtected)
    val graceEnd = at(24 * H)
    check("exactly 24h: grace over", graceEnd.phase == Phase.NORMAL_GAMEPLAY && !graceEnd.pvpProtected)
    check("after grace: hearts move", graceEnd.heartWindow == HeartWindow.OPEN)
    check("after grace: End still locked", graceEnd.endLocked)
    check("after grace: ranked still locked", ProgressionRules.rankedStatus(graceEnd, true) == RankedStatus.LOCKED)

    check("72h - 1ms: ranked locked", ProgressionRules.rankedStatus(at(72 * H - 1), true) == RankedStatus.LOCKED)
    val ranked = at(72 * H)
    check("exactly 72h: ranked unlocked", ranked.phase == Phase.RANKED_UNLOCKED && ranked.rankedUnlocked)
    check("ranked unlocked but no ranked system: unavailable", ProgressionRules.rankedStatus(ranked, false) == RankedStatus.UNLOCKED_UNAVAILABLE)
    check("ranked unlocked + ranked system enabled: available", ProgressionRules.rankedStatus(ranked, true) == RankedStatus.AVAILABLE)
    check("72h: End still locked", ranked.endLocked)

    check("168h - 1ms: End locked", at(168 * H - 1).endLocked)
    val end = at(168 * H)
    check("exactly 168h: End unlocked", end.phase == Phase.END_UNLOCKED && !end.endLocked)
    check("168h: every milestone reached", end.reached == Milestone.entries.toSet())
    check("Day 3/7 are elapsed hours, not calendar dates (launch at 18:00 -> Day 3 at 18:00)",
        ProgressionRules.formatUtc(end.at(Milestone.RANKED_UNLOCKED)!!) == "2026-12-04T18:00:00Z" && ProgressionRules.formatUtc(end.at(Milestone.END_UNLOCKED)!!) == "2026-12-08T18:00:00Z")

    // Offline progression: a restart long after the launch is immediately correct, nothing paused.
    val later = at(30 * 24 * H)
    check("restart after a month offline: End unlocked", later.phase == Phase.END_UNLOCKED)

    // Clock changes: recorded milestones never relock.
    val clockBack = at(10 * H, recorded = setOf(Milestone.LAUNCH, Milestone.GRACE_ENDED))
    check("clock went back after grace ended: grace stays over", !clockBack.pvpProtected && clockBack.heartWindow == HeartWindow.OPEN)
    val clockBackEnd = at(-5 * H, recorded = setOf(Milestone.END_UNLOCKED))
    check("a recorded later milestone implies the earlier ones (no contradictions)", clockBackEnd.reached == Milestone.entries.toSet() && !clockBackEnd.endLocked && !clockBackEnd.pvpProtected)
    check("remaining is 0 once reached", clockBackEnd.remaining(Milestone.END_UNLOCKED) == 0L)

    // Unknown state denies restricted actions.
    val unknown = ProgressionRules.unknown(LAUNCH + 500 * H, SEASON, SCHEDULE)
    check("unknown: PvP protected", unknown.pvpProtected)
    check("unknown: End locked", unknown.endLocked)
    check("unknown: no heart moves", unknown.heartWindow == HeartWindow.UNKNOWN)
    check("unknown: ranked locked even with a ranked system", ProgressionRules.rankedStatus(unknown, true) == RankedStatus.LOCKED)

    val disabled = ProgressionRules.disabled(LAUNCH)
    check("disabled: nothing held back", !disabled.pvpProtected && !disabled.endLocked && disabled.heartWindow == HeartWindow.OPEN && disabled.rankedUnlocked)

    // Grace 0 = no grace, PvP from launch.
    val noGrace = ProgressionRules.evaluate(SEASON, ProgressionRules.Schedule(0, 72, 168), LAUNCH, emptySet())
    check("grace 0: PvP from launch", !noGrace.pvpProtected && noGrace.heartWindow == HeartWindow.OPEN)

    // Announcements: one per milestone, only the latest after downtime.
    check("nothing reached: no announcement", ProgressionRules.announcementFor(emptySet(), emptySet()) == null)
    check("launch reached: announce launch", ProgressionRules.announcementFor(setOf(Milestone.LAUNCH), emptySet()) == Milestone.LAUNCH)
    check("already announced: nothing", ProgressionRules.announcementFor(setOf(Milestone.LAUNCH), setOf(Milestone.LAUNCH)) == null)
    check("offline backlog: only the latest", ProgressionRules.announcementFor(Milestone.entries.toSet(), setOf(Milestone.LAUNCH)) == Milestone.END_UNLOCKED)
    check("next milestone time", ProgressionRules.nextMilestoneAt(at(25 * H)) == LAUNCH + 72 * H)
    check("no next milestone after the End", ProgressionRules.nextMilestoneAt(end) == null)
}

// ── Heart integration ────────────────────────────────────────

private fun hearts() {
    fun decide(window: HeartWindow, team: TeamCombatRules.HeartCheck = TeamCombatRules.HeartCheck.ELIGIBLE, victim: Int = 10, killer: Int = 10, cooldown: Long = 0) =
        HeartRules.decide(victim, killer, cooldown, LIMITS, team = team, window = window)

    check("grace: no transfer", decide(at(H).heartWindow) == HeartRules.Decision.GracePeriod)
    check("pre-launch: no transfer", decide(at(-H).heartWindow) == HeartRules.Decision.NotLaunched)
    check("unknown: no transfer", decide(HeartWindow.UNKNOWN) == HeartRules.Decision.ProgressionUnknown)
    check("after grace: normal transfer", decide(at(24 * H).heartWindow) == HeartRules.Decision.Transfer(9, 11))
    check("default window stays OPEN (existing callers unchanged)", HeartRules.decide(10, 10, 0, LIMITS) == HeartRules.Decision.Transfer(9, 11))
    check("after grace: 1-heart minimum kept", decide(HeartWindow.OPEN, victim = 1) == HeartRules.Decision.VictimAtMinimum)
    check("after grace: 20-heart maximum kept", decide(HeartWindow.OPEN, killer = 20) == HeartRules.Decision.KillerAtMaximum)
    check("after grace: 30-minute cooldown kept", decide(HeartWindow.OPEN, cooldown = 1_000) is HeartRules.Decision.Cooldown)
    check("grace wins over the team check", decide(HeartWindow.GRACE_PERIOD, team = TeamCombatRules.HeartCheck.TEAMMATES) == HeartRules.Decision.GracePeriod)
    check("after grace: teammates still blocked", decide(HeartWindow.OPEN, team = TeamCombatRules.HeartCheck.TEAMMATES) == HeartRules.Decision.Teammates)
    check("grace refusals don't change hearts", with(HeartRules) { !HeartRules.Decision.GracePeriod.changesHearts && !HeartRules.Decision.NotLaunched.changesHearts && !HeartRules.Decision.ProgressionUnknown.changesHearts })
    // A death at 23:59:59.999 is judged at that moment, however late the transaction runs.
    check("a death 1ms before grace ends moves no heart", decide(at(24 * H - 1).heartWindow) == HeartRules.Decision.GracePeriod)
}

// ── Admin decisions ──────────────────────────────────────────

private fun adminDecisions() {
    val now = LAUNCH - 48 * H
    val none = ProgressionRules.evaluate(null, SCHEDULE, now, emptySet())
    check("schedule with no season creates one", ProgressionRules.schedule(none, LAUNCH, now) == ScheduleDecision.Create(LAUNCH))
    check("schedule in the past is refused", ProgressionRules.schedule(none, now - 1, now) is ScheduleDecision.Refused)
    check("activate with no season launches now", ProgressionRules.activate(none, now) == ScheduleDecision.Create(now))
    val scheduled = ProgressionRules.evaluate(SEASON, SCHEDULE, now, emptySet())
    check("moving a future launch is allowed", ProgressionRules.schedule(scheduled, LAUNCH + H, now) == ScheduleDecision.Move(LAUNCH, LAUNCH + H))
    check("same time again is refused", ProgressionRules.schedule(scheduled, LAUNCH, now) is ScheduleDecision.Refused)
    check("activating early moves the launch to now", ProgressionRules.activate(scheduled, now) == ScheduleDecision.Move(LAUNCH, now))
    val launched = at(H)
    check("after launch: schedule refused (no reset)", ProgressionRules.schedule(launched, LAUNCH + 1000 * H, LAUNCH + H) is ScheduleDecision.Refused)
    check("after launch: activate refused (no duplicate activation)", ProgressionRules.activate(launched, LAUNCH + H) is ScheduleDecision.Refused)
    check("clock back after launch: still refused", ProgressionRules.activate(at(-H, setOf(Milestone.LAUNCH)), LAUNCH - H) is ScheduleDecision.Refused)
    check("unknown state: refused", ProgressionRules.activate(ProgressionRules.unknown(now), now) is ScheduleDecision.Refused)
    check("disabled: refused", ProgressionRules.activate(ProgressionRules.disabled(now), now) is ScheduleDecision.Refused)
}

// ── Store (real SQLite, restarts) ────────────────────────────

private fun withDir(name: String, block: (File) -> Unit) {
    val dir = Files.createTempDirectory("joshymc-progression-$name").toFile()
    try { block(dir) } finally { dir.deleteRecursively() }
}

private fun <T> open(dir: File, block: (ProgressionStore) -> T): T {
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        val store = ProgressionStore(db)
        store.createTables()
        return block(store)
    } finally {
        db.shutdown()
    }
}

private fun store() = withDir("store") { dir ->
    open(dir) { s ->
        s.createTables()
        check("fresh install: no season (never auto-starts)", s.activeSeason() == null && s.seasonCount() == 0)
        check("season created", s.createSeason("season-1", LAUNCH, "staff", "command:SCHEDULE", LAUNCH - 48 * H))
        check("duplicate initialization refused", !s.createSeason("season-1", LAUNCH + H, "staff", "command:SCHEDULE", LAUNCH - 47 * H))
        check("a second active season is refused", !s.createSeason("season-2", LAUNCH + H, "staff", "command:SCHEDULE", LAUNCH - 47 * H))
        check("launch stored in UTC millis", s.activeSeason() == Season("season-1", LAUNCH, 1))
        s.moveLaunch("season-1", 1, LAUNCH, LAUNCH + H, "staff", "SCHEDULE", LAUNCH - 40 * H)
        check("future launch moved, version bumped", s.activeSeason() == Season("season-1", LAUNCH + H, 2))
        var stale = false
        try { s.moveLaunch("season-1", 1, LAUNCH + H, LAUNCH + 2 * H, "staff", "SCHEDULE", LAUNCH - 39 * H) } catch (_: ProgressionStore.StaleWriteException) { stale = true }
        check("stale version refused", stale && s.activeSeason()!!.launchAt == LAUNCH + H)
        var launched = false
        try { s.moveLaunch("season-1", 2, LAUNCH + H, LAUNCH + 9 * H, "staff", "SCHEDULE", LAUNCH + 2 * H) } catch (_: ProgressionStore.StaleWriteException) { launched = true }
        check("a launched season can't be moved (checked in the UPDATE)", launched && s.activeSeason()!!.launchAt == LAUNCH + H)
    }
    // Restart: same file, new connection.
    open(dir) { s ->
        val season = s.activeSeason()
        check("restart: season and launch preserved", season == Season("season-1", LAUNCH + H, 2))
        val first = s.recordMilestones("season-1", mapOf(Milestone.LAUNCH to LAUNCH + H), LAUNCH + 2 * H)
        check("milestone recorded once", first == setOf(Milestone.LAUNCH))
        check("recording again is a no-op", s.recordMilestones("season-1", mapOf(Milestone.LAUNCH to LAUNCH + H), LAUNCH + 3 * H).isEmpty())
        check("announcement claimed once", s.claimAnnouncement("season-1", Milestone.LAUNCH, emptyList()))
        check("second claim refused (no duplicate announcement)", !s.claimAnnouncement("season-1", Milestone.LAUNCH, emptyList()))
    }
    open(dir) { s ->
        val rows = s.milestones("season-1")
        check("restart during grace: launch recorded and announced", rows[Milestone.LAUNCH]?.announced == true && rows.size == 1)
        val p = ProgressionRules.evaluate(s.activeSeason(), SCHEDULE, LAUNCH + 5 * H, rows.keys)
        check("restart during grace: still grace, not restarted", p.phase == Phase.GRACE_PERIOD && p.remaining(Milestone.GRACE_ENDED) == 20 * H)
        // Offline for 10 days: everything due is recorded at once, only the latest announced.
        val now = LAUNCH + 240 * H
        val reached = ProgressionRules.evaluate(s.activeSeason(), SCHEDULE, now, rows.keys).reached
        val added = s.recordMilestones("season-1", reached.filter { it !in rows.keys }.associateWith { LAUNCH + H + SCHEDULE.offsetMillis(it) }, now)
        check("offline milestones recorded", added == setOf(Milestone.GRACE_ENDED, Milestone.RANKED_UNLOCKED, Milestone.END_UNLOCKED))
        val announced = s.milestones("season-1").values.filter { it.announced }.map { it.milestone }.toSet()
        val target = ProgressionRules.announcementFor(reached, announced)
        check("offline: only the End is announced", target == Milestone.END_UNLOCKED)
        check("offline: claim the latest, mark the rest", s.claimAnnouncement("season-1", target!!, listOf(Milestone.GRACE_ENDED, Milestone.RANKED_UNLOCKED)))
        check("offline: nothing left to announce", s.milestones("season-1").values.all { it.announced })
    }
    open(dir) { s ->
        val rows = s.milestones("season-1")
        // Restart after unlocks with the clock set back before launch: nothing relocks.
        val p = ProgressionRules.evaluate(s.activeSeason(), SCHEDULE, LAUNCH - 100 * H, rows.keys)
        check("restart after unlocks (clock back): nothing relocks", p.phase == Phase.END_UNLOCKED && !p.endLocked && !p.pvpProtected)
        check("another season's id sees none of these milestones", s.milestones("season-2").isEmpty())
        check("audit trail kept", s.recentAudit(50).map { it.action }.containsAll(listOf("CREATE", "SCHEDULE", "MILESTONE")))
        check("audit never stores a failed (stale) change", s.recentAudit(50).count { it.action == "SCHEDULE" } == 1)
    }
}

// ── Wiring (source) ──────────────────────────────────────────

private fun wiring() {
    val manager = source("lifesteal/progression/LifestealProgressionManager.kt")
    val listener = source("lifesteal/progression/LifestealProgressionListener.kt")
    val heartManager = source("lifesteal/hearts/LifestealHeartManager.kt")
    val heartStore = source("lifesteal/hearts/HeartStore.kt")
    val listeners = source("manager/ListenerManager.kt")
    val main = source("Joshymc.kt")
    val combatListener = source("listener/CombatListener.kt")
    val transfer = source("command/ServerTransferCommand.kt")
    val plugin = resourceText("plugin.yml")

    // Survival isolation.
    check("listener only registered where Lifesteal is hosted", Regex("""if \(plugin\.lifestealProgressionManager\.hosted\) \{\s*\n\s*pm\.registerEvents\(com\.liam\.joshymc\.lifesteal\.progression\.LifestealProgressionListener""").containsMatchIn(listeners))
    check("manager does nothing (no tables, no task) where Lifesteal isn't hosted", Regex("""if \(!hosted\) \{\s*\n\s*state = State\(Status\.NOT_HOSTED\)\s*\n\s*return""").containsMatchIn(manager))
    check("PvP protection requires hosting", manager.contains("fun pvpProtected(now: Long = System.currentTimeMillis()): Boolean = hosted &&"))
    check("End lock requires hosting", manager.contains("fun endLocked(now: Long = System.currentTimeMillis()): Boolean = hosted &&"))
    check("damage is judged only for victims in Lifesteal worlds", listener.contains("if (!inLifesteal(victim) || !progression.pvpProtected()) return"))
    check("only End worlds that are Lifesteal worlds are locked", listener.contains("world.environment == World.Environment.THE_END && progression.isLifesteal(world)"))
    check("announcements only reach Lifesteal players", manager.contains("if (isLifesteal(player.world)) player.sendMessage(component)"))

    // Grace PvP: real damage events, attributable sources only, no combat tag from cancelled hits.
    check("grace handlers run at HIGHEST + ignoreCancelled", Regex("""HIGHEST, ignoreCancelled = true\)\s*\n\s*fun onDamage\(event: EntityDamageEvent\)""").containsMatchIn(listener))
    check("combat tag stays MONITOR + ignoreCancelled", Regex("""MONITOR, ignoreCancelled = true\)\s*\n\s*fun onDamageTag""").containsMatchIn(combatListener))
    check("attribution: damage source causing entity (melee, projectiles, tridents, crystals)", listener.contains("event.damageSource.causingEntity as? Player"))
    check("attribution: projectile shooter fallback (bows, crossbows, tridents)", listener.contains("is Projectile -> source.shooter as? Player"))
    check("attribution: TNT igniter and pet owner", listener.contains("is TNTPrimed -> source.source as? Player") && listener.contains("is Tameable -> source.owner as? Player"))
    check("environmental damage untouched (no attacker -> return)", listener.contains("val attacker = attackerOf(event) ?: return"))
    check("harmful splash/lingering potions and combustion covered", listener.contains("fun onSplash") && listener.contains("fun onCloud") && listener.contains("fun onCombust"))
    check("self-damage untouched", listener.contains("attacker.uniqueId == victim.uniqueId"))

    // Hearts: inside the heart service, at death time.
    check("heart request carries the progression window", heartStore.contains("request.policy, request.team, request.window)"))
    check("window taken at death time", heartManager.contains("window = plugin.lifestealProgressionManager.heartWindow(now)"))

    // End: actual transitions, not commands.
    for (event in listOf("PlayerPortalEvent", "PlayerTeleportEvent", "EntityPortalEvent", "PlayerChangedWorldEvent", "PlayerJoinEvent")) {
        check("End lock handles $event", listener.contains("(event: $event)"))
    }
    check("leaving the End is never blocked (only entering from another world)", listener.contains("if (!isLockedEnd(to) || from == to) return false"))
    check("End bypass is an explicit permission, default false", Regex("""joshymc\.lifesteal\.progression\.end-bypass:\s*\n\s*description: [^\n]+\n\s*default: false""").containsMatchIn(plugin))
    check("refused End portals move the player to safety", listener.contains("moveOutOfPortal(player)"))

    // Lifecycle + commands.
    check("refresh on startup", main.contains("lifestealProgressionManager.refresh()"))
    check("refresh on reload", main.contains("safe(\"lifestealProgressionManager.refresh\") { lifestealProgressionManager.refresh() }"))
    check("task cancelled on shutdown", main.contains("shutdown.step(\"lifestealProgressionManager.stop\""))
    check("one repeating task, not per tick", manager.contains("TICK_SECONDS * 20L, TICK_SECONDS * 20L") && manager.contains("const val TICK_SECONDS = 20L"))
    check("/lifesteal progress and admin wired into the existing executor", transfer.contains("progression.progress(sender)") && transfer.contains("progression.admin(sender, args.drop(1))"))
    check("admin permission op only", Regex("""joshymc\.lifesteal\.progression\.admin:\s*\n\s*description: [^\n]+\n\s*default: op""").containsMatchIn(plugin))
    check("no bypass/reset subcommand exists", !source("lifesteal/progression/LifestealProgressCommand.kt").contains("\"reset\""))
    // Java and Bedrock: nothing edition-specific; Floodgate UUIDs are plain UUIDs everywhere.
    check("no packet-level or Geyser code", !listener.contains("Geyser") && !listener.contains("packet") && !manager.contains("Floodgate"))
}
