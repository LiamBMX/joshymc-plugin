// Checks the Ranked Team Wars arena and match infrastructure (issue #1361): arena rules and
// validation, the match state machine, participant validation (2v2-5v5, teams, combat tags, the
// seasonal unlock, hearts at 1 and 20, Java + Bedrock ids), victory decisions (incl. simultaneous
// deaths), the RankedStore against a real SQLite database (fresh temp directories, reopened to
// simulate restarts and crashes: reservations, snapshots, exactly-once restoration, recovery,
// records), heart-transfer integration through the normal HeartStore, the lifesteal.yml settings,
// and the Lifesteal-only wiring. The Gradle build has no test source set, so it runs through an
// init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-ranked-check.init.gradle.kts lifestealRankedCheck
//
// Exits non-zero on the first failed check. Real teleports, deaths, inventories (ItemStack
// serialization needs a running server), Bedrock clients and disconnects on a live server are NOT
// covered here (see docs/lifesteal-ranked.md, "Manual tests").

import com.liam.joshymc.lifesteal.LifestealConfigParser
import com.liam.joshymc.lifesteal.RankedForfeitHeartLoss
import com.liam.joshymc.lifesteal.combat.CombatLogRules
import com.liam.joshymc.lifesteal.combat.CombatLogRules.DisconnectKind
import com.liam.joshymc.lifesteal.ranked.RankedMatchRules.DisconnectAction
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.hearts.HeartRules.changesHearts
import com.liam.joshymc.lifesteal.hearts.HeartStore
import com.liam.joshymc.lifesteal.progression.ProgressionRules
import com.liam.joshymc.lifesteal.ranked.ArenaAvailability
import com.liam.joshymc.lifesteal.ranked.ArenaReadiness
import com.liam.joshymc.lifesteal.ranked.Box
import com.liam.joshymc.lifesteal.ranked.DefeatReason
import com.liam.joshymc.lifesteal.ranked.MatchParticipant
import com.liam.joshymc.lifesteal.ranked.MatchRoster
import com.liam.joshymc.lifesteal.ranked.MatchState
import com.liam.joshymc.lifesteal.ranked.ParticipantStatus
import com.liam.joshymc.lifesteal.ranked.Point
import com.liam.joshymc.lifesteal.ranked.RankedArena
import com.liam.joshymc.lifesteal.ranked.RankedArenaRules
import com.liam.joshymc.lifesteal.ranked.RankedMatchRules
import com.liam.joshymc.lifesteal.ranked.RankedMatchRules.Decision
import com.liam.joshymc.lifesteal.ranked.RankedStore
import com.liam.joshymc.lifesteal.ranked.RankedHeartSummary
import com.liam.joshymc.lifesteal.ranked.monitor.RankedMonitorConfig
import com.liam.joshymc.lifesteal.ranked.monitor.RankedMonitorRules
import com.liam.joshymc.lifesteal.ranked.monitor.RankedMonitorSettings
import com.liam.joshymc.lifesteal.ranked.monitor.RankedMonitorStore
import com.liam.joshymc.lifesteal.ranked.Side
import com.liam.joshymc.lifesteal.teams.TeamCombatRules
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.network.transfer.TransferBlocker
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
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

private inline fun throws(block: () -> Unit): Boolean = try { block(); false } catch (_: Exception) { true }

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }
private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()
private fun resourceText(name: String): String = File("src/main/resources/$name").readText()

private val LIMITS = HeartRules.Limits(10, 1, 20)
private val JAVA_A = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7")
/** Floodgate (Bedrock) ids are version-0 UUIDs built from the XUID; stable, and plain UUIDs everywhere. */
private val BEDROCK_A = UUID.fromString("00000000-0000-0000-0009-01f4ac2d1e3b")

fun main() {
    arenas()
    states()
    victory()
    validation()
    commands()
    settings()
    store()
    hearts()
    forfeits()
    deathDetection()
    heartFeedback()
    cooldownRegression()
    environmentalDeaths()
    monitoring()
    wiring()
    println("OK: $checks Ranked Team Wars checks passed.")
}

// ── Arenas ───────────────────────────────────────────────────

private val BOX = Box.of(0, 60, 0, 40, 80, 40)
private fun readyArena(id: String = "duel", world: String = "lifesteal", box: Box? = BOX) = RankedArena(
    id = id, displayName = "Duel Pit", world = world, bounds = box,
    teamA = listOf(Point(5.5, 64.0, 5.5)), teamB = listOf(Point(35.5, 64.0, 35.5, 180f, 0f)),
    spectators = listOf(Point(20.5, 90.0, 20.5)), enabled = true,
)

private fun arenas() {
    check("ids: lower case letters, digits, _ and -", RankedArenaRules.validId("duel_pit-2") && !RankedArenaRules.validId("Duel") && !RankedArenaRules.validId("a b") && !RankedArenaRules.validId("") && !RankedArenaRules.validId("x".repeat(33)))
    check("canonical id is trimmed lower case", RankedArenaRules.canonicalId("  Duel ") == "duel")
    check("names: section signs and control characters stripped", RankedArenaRules.cleanName("§cRed\u0007 Pit") == "cRed Pit")
    check("names: blank refused", RankedArenaRules.cleanName("   ") == null)
    check("names: too long refused", RankedArenaRules.cleanName("x".repeat(33)) == null)
    check("sizes: list", RankedArenaRules.parseSizes("2,3,5") == setOf(2, 3, 5))
    check("sizes: NvN and spaces", RankedArenaRules.parseSizes("2v2 4v4") == setOf(2, 4))
    check("sizes: all", RankedArenaRules.parseSizes("all") == setOf(2, 3, 4, 5))
    check("sizes: 1 and 6 refused", RankedArenaRules.parseSizes("1") == null && RankedArenaRules.parseSizes("2,6") == null)
    check("sizes: 2v3 refused", RankedArenaRules.parseSize("2v3") == null)
    check("sizes: garbage refused", RankedArenaRules.parseSizes("two") == null && RankedArenaRules.parseSizes("") == null)

    val p = Point(1.5, 64.0, -3.25, 90f, -10f)
    check("point round trip", Point.decode(p.encode()) == p)
    check("point list round trip", Point.decodeList(Point.encodeList(listOf(p, Point(0.0, 0.0, 0.0)))) == listOf(p, Point(0.0, 0.0, 0.0)))
    check("malformed point list is null (arena INVALID, never silently shortened)", Point.decodeList("1,2,3,4,5;oops") == null)
    check("non-finite point refused", Point.decode("NaN,1,1,0,0") == null)
    check("negative block coordinates floor", Point(-0.5, 64.0, -1.2).blockX == -1 && Point(-0.5, 64.0, -1.2).blockZ == -2)

    check("box contains its corner blocks", BOX.contains(0.0, 60.0, 0.0) && BOX.contains(40.99, 80.99, 40.99))
    check("box excludes outside", !BOX.contains(41.0, 70.0, 10.0) && !BOX.contains(-0.01, 70.0, 10.0) && !BOX.contains(10.0, 81.0, 10.0))
    check("box from swapped corners", Box.of(40, 80, 40, 0, 60, 0) == BOX)
    check("overlap detected", BOX.overlaps(Box.of(40, 60, 40, 60, 70, 60)) && !BOX.overlaps(Box.of(41, 60, 0, 60, 70, 10)))

    val sizes = listOf(2, 3, 4, 5)
    val ok = RankedArenaRules.validate(readyArena(), null, emptyList(), sizes)
    check("complete arena is READY", ok.readiness == ArenaReadiness.READY && ok.problems.isEmpty())
    check("no boundary: INCOMPLETE", RankedArenaRules.validate(readyArena(box = null).copy(teamA = emptyList(), teamB = emptyList(), spectators = emptyList()), null, emptyList(), sizes).readiness == ArenaReadiness.INCOMPLETE)
    check("no team B spawn: INCOMPLETE", RankedArenaRules.validate(readyArena().copy(teamB = emptyList()), null, emptyList(), sizes).readiness == ArenaReadiness.INCOMPLETE)
    check("spawn outside the boundary: INVALID", RankedArenaRules.validate(readyArena().copy(teamA = listOf(Point(100.0, 64.0, 5.0))), null, emptyList(), sizes).readiness == ArenaReadiness.INVALID)
    check("spectator inside the boundary: INVALID", RankedArenaRules.validate(readyArena().copy(spectators = listOf(Point(20.0, 64.0, 20.0))), null, emptyList(), sizes).readiness == ArenaReadiness.INVALID)
    check("spectators are optional", RankedArenaRules.validate(readyArena().copy(spectators = emptyList()), null, emptyList(), sizes).ready)
    check("world not loaded: INVALID with that reason", RankedArenaRules.validate(readyArena(), "the world 'x' isn't loaded", emptyList(), sizes).let { it.readiness == ArenaReadiness.INVALID && it.problems.any { p -> "isn't loaded" in p } })
    check("too small boundary: INVALID", RankedArenaRules.validate(readyArena(box = Box.of(0, 60, 0, 2, 70, 2)).copy(teamA = listOf(Point(1.0, 64.0, 1.0)), teamB = listOf(Point(1.0, 64.0, 1.0)), spectators = emptyList()), null, emptyList(), sizes).readiness == ArenaReadiness.INVALID)
    check("too large boundary: INVALID", RankedArenaRules.validate(readyArena(box = Box.of(0, 0, 0, 1000, 80, 40)), null, emptyList(), sizes).readiness == ArenaReadiness.INVALID)
    check("more than 5 spawns: INVALID", RankedArenaRules.validate(readyArena().copy(teamA = List(6) { Point(5.0 + it, 64.0, 5.0) }), null, emptyList(), sizes).readiness == ArenaReadiness.INVALID)
    check("sizes none allowed by lifesteal.yml: INVALID", RankedArenaRules.validate(readyArena().copy(sizes = setOf(5)), null, emptyList(), listOf(2, 3)).readiness == ArenaReadiness.INVALID)
    val other = readyArena(id = "other", box = Box.of(30, 60, 30, 70, 80, 70))
    check("overlapping an enabled arena in the same world: INVALID", RankedArenaRules.validate(readyArena(), null, listOf(other), sizes).let { it.readiness == ArenaReadiness.INVALID && it.problems.any { p -> "overlaps" in p } })
    check("overlapping a disabled arena is fine", RankedArenaRules.validate(readyArena(), null, listOf(other.copy(enabled = false)), sizes).ready)
    check("same box in another world is fine", RankedArenaRules.validate(readyArena(), null, listOf(other.copy(world = "lifesteal_arenas")), sizes).ready)
    check("an arena never clashes with itself", RankedArenaRules.validate(readyArena(), null, listOf(readyArena()), sizes).ready)
    check("spawns are shared round-robin", readyArena().spawnFor(Side.A, 3) == readyArena().teamA[0])

    val a = readyArena()
    check("availability: free and ready = AVAILABLE", RankedArenaRules.availability(a, true, null) == ArenaAvailability.AVAILABLE)
    check("availability: disabled", RankedArenaRules.availability(a.copy(enabled = false), true, null) == ArenaAvailability.DISABLED)
    check("availability: not ready = DISABLED", RankedArenaRules.availability(a, false, null) == ArenaAvailability.DISABLED)
    check("availability: reserved while created/validating/preparing", listOf(MatchState.CREATED, MatchState.VALIDATING, MatchState.PREPARING).all { RankedArenaRules.availability(a, true, it) == ArenaAvailability.RESERVED })
    check("availability: active while counting down/fighting", listOf(MatchState.COUNTDOWN, MatchState.ACTIVE).all { RankedArenaRules.availability(a, true, it) == ArenaAvailability.ACTIVE })
    check("availability: resetting while finishing", RankedArenaRules.availability(a, true, MatchState.FINISHING) == ArenaAvailability.RESETTING)
    check("availability: a held arena stays held even if disabled meanwhile", RankedArenaRules.availability(a.copy(enabled = false), true, MatchState.ACTIVE) == ArenaAvailability.ACTIVE)
    check("availability: ended match frees it", RankedArenaRules.availability(a, true, MatchState.COMPLETED) == ArenaAvailability.AVAILABLE)
}

// ── States ───────────────────────────────────────────────────

private fun states() {
    val path = listOf(MatchState.CREATED, MatchState.VALIDATING, MatchState.PREPARING, MatchState.COUNTDOWN, MatchState.ACTIVE, MatchState.FINISHING, MatchState.COMPLETED)
    for ((a, b) in path.zipWithNext()) check("$a -> $b allowed", a.canMoveTo(b))
    for (s in listOf(MatchState.CREATED, MatchState.VALIDATING, MatchState.PREPARING, MatchState.COUNTDOWN, MatchState.ACTIVE)) check("$s -> CANCELLED allowed", s.canMoveTo(MatchState.CANCELLED))
    check("FINISHING can't be cancelled (it has a winner)", !MatchState.FINISHING.canMoveTo(MatchState.CANCELLED))
    check("no skipping: CREATED -> ACTIVE refused", !MatchState.CREATED.canMoveTo(MatchState.ACTIVE))
    check("no skipping: PREPARING -> ACTIVE refused", !MatchState.PREPARING.canMoveTo(MatchState.ACTIVE))
    check("no going back: ACTIVE -> COUNTDOWN refused", !MatchState.ACTIVE.canMoveTo(MatchState.COUNTDOWN))
    check("terminal states go nowhere", MatchState.entries.none { MatchState.COMPLETED.canMoveTo(it) || MatchState.CANCELLED.canMoveTo(it) })
    check("only COMPLETED and CANCELLED are terminal", MatchState.entries.filter { it.terminal } == listOf(MatchState.COMPLETED, MatchState.CANCELLED))
    check("players are held from PREPARING to FINISHING", MatchState.entries.filter { it.holdsPlayers } == listOf(MatchState.PREPARING, MatchState.COUNTDOWN, MatchState.ACTIVE, MatchState.FINISHING))
    val id = RankedMatchRules.newMatchId()
    check("match ids are stable-format and unique", RankedMatchRules.validMatchId(id) && id != RankedMatchRules.newMatchId())
}

// ── Victory ──────────────────────────────────────────────────

private fun roster(a: Int, b: Int): MatchRoster = MatchRoster(
    List(a) { MatchParticipant(UUID(1L, it.toLong()), Side.A, "team-a") } + List(b) { MatchParticipant(UUID(2L, it.toLong()), Side.B, "team-b") }
)

private fun victory() {
    for (size in 2..5) {
        var r = roster(size, size)
        check("${size}v$size starts undecided", RankedMatchRules.decide(r) == Decision.Continue)
        for (i in 0 until size - 1) r = r.defeat(UUID(2L, i.toLong()), DefeatReason.DEATH)!!
        check("${size}v$size: one B left = continue", RankedMatchRules.decide(r) == Decision.Continue)
        r = r.defeat(UUID(2L, (size - 1).toLong()), DefeatReason.DEATH)!!
        check("${size}v$size: B has no one left = A wins", RankedMatchRules.decide(r) == Decision.Winner(Side.A))
    }
    var r = roster(2, 2)
    r = r.defeat(UUID(1L, 0), DefeatReason.DISCONNECT)!!.defeat(UUID(1L, 1), DefeatReason.DEATH)!!
    check("disconnect counts as out (match forfeit only)", RankedMatchRules.decide(r) == Decision.Winner(Side.B))
    // Last players of both sides die in the same tick: decided together on the next tick.
    var s = roster(2, 2).defeat(UUID(1L, 0), DefeatReason.DEATH)!!.defeat(UUID(2L, 0), DefeatReason.DEATH)!!
    s = s.defeat(UUID(1L, 1), DefeatReason.DEATH)!!.defeat(UUID(2L, 1), DefeatReason.DEATH)!!
    check("simultaneous elimination = no winner (cancelled)", RankedMatchRules.decide(s) == Decision.NoWinner)
    val once = roster(2, 2).defeat(UUID(1L, 0), DefeatReason.DEATH)!!
    check("a second defeat of the same player changes nothing", once.defeat(UUID(1L, 0), DefeatReason.DEATH) == null)
    check("defeating someone not in the match changes nothing", once.defeat(UUID(9L, 9L), DefeatReason.DEATH) == null)
    check("defeat reason kept", once.of(UUID(1L, 0))?.defeatReason == DefeatReason.DEATH && once.of(UUID(1L, 0))?.status == ParticipantStatus.DEFEATED)
    check("defeated players aren't active (can't rejoin combat)", !once.isActive(UUID(1L, 0)) && once.isActive(UUID(1L, 1)))
}

// ── Participant validation ───────────────────────────────────

private fun facts(uuid: UUID, side: Side, name: String = uuid.toString().take(6)) = RankedMatchRules.ParticipantFacts(
    uuid = uuid, name = name, side = side, member = true, online = true, inLifesteal = true, sessionLoaded = true, hearts = 10,
    inOtherMatch = false, pendingRestore = false, transferring = false, combatTagged = false, dead = false, survivalMode = true,
)

private fun request(size: Int, edit: (MutableList<RankedMatchRules.ParticipantFacts>) -> Unit = {}, rankedUnlocked: Boolean = true, sameTeam: Boolean = false): RankedMatchRules.Request {
    val list = (List(size) { facts(UUID(1L, it.toLong()), Side.A) } + List(size) { facts(UUID(2L, it.toLong()), Side.B) }).toMutableList()
    edit(list)
    return RankedMatchRules.Request(
        teamA = RankedMatchRules.TeamFacts("team-a", "Alpha", true, size),
        teamB = RankedMatchRules.TeamFacts(if (sameTeam) "team-a" else "team-b", "Bravo", true, size),
        size = size, allowedSizes = listOf(2, 3, 4, 5), participants = list, heartLimits = 1..20,
        rankedUnlocked = rankedUnlocked, teamsAvailable = true, heartsAvailable = true, arenaProblem = null,
    )
}

private fun edited(size: Int, index: Int, change: (RankedMatchRules.ParticipantFacts) -> RankedMatchRules.ParticipantFacts) =
    RankedMatchRules.validate(request(size, { it[index] = change(it[index]) }))

private fun validation() {
    for (size in 2..5) check("${size}v$size valid", RankedMatchRules.validate(request(size)).isEmpty())
    check("1v1 refused", RankedMatchRules.validate(request(1)).isNotEmpty())
    check("6v6 refused", RankedMatchRules.validate(request(6)).isNotEmpty())
    check("size not in lifesteal.yml refused", RankedMatchRules.validate(request(4).copy(allowedSizes = listOf(2, 3))).any { "isn't an allowed size" in it })
    check("ranked locked for the season refused", RankedMatchRules.validate(request(2, rankedUnlocked = false)).any { "unlocked" in it })
    check("a team can't play itself", RankedMatchRules.validate(request(2, sameTeam = true)).any { "itself" in it })
    check("missing team refused", RankedMatchRules.validate(request(2).copy(teamB = RankedMatchRules.TeamFacts("", "Ghost", false, 0))).any { "doesn't exist" in it })
    check("missing arena reported", RankedMatchRules.validate(request(2).copy(arenaProblem = "No available arena")).contains("No available arena"))
    check("teams unavailable refused", RankedMatchRules.validate(request(2).copy(teamsAvailable = false)).isNotEmpty())
    check("hearts paused refused (hearts must be at stake)", RankedMatchRules.validate(request(2).copy(heartsAvailable = false)).isNotEmpty())
    check("not enough players on a side refused", RankedMatchRules.validate(request(3, { it.removeAt(0) })).any { "needs 3" in it })
    check("too many players on a side refused", RankedMatchRules.validate(request(2, { it += facts(UUID(1L, 99), Side.A) })).any { "needs 2" in it })
    check("invalid team membership refused", edited(2, 0) { it.copy(member = false) }.any { "member" in it })
    check("offline refused", edited(2, 0) { it.copy(online = false) }.any { "online" in it })
    check("outside Lifesteal refused", edited(2, 1) { it.copy(inLifesteal = false) }.any { "Lifesteal world" in it })
    check("no loaded session refused", edited(2, 2) { it.copy(sessionLoaded = false) }.any { "session" in it })
    check("combat-tagged refused", edited(2, 0) { it.copy(combatTagged = true) }.any { "in combat" in it })
    check("already in a match refused", edited(2, 3) { it.copy(inOtherMatch = true) }.any { "already in a ranked match" in it })
    check("pending restoration refused", edited(2, 0) { it.copy(pendingRestore = true) }.any { "waiting to be restored" in it })
    check("transferring refused", edited(2, 0) { it.copy(transferring = true) }.any { "switching servers" in it })
    check("dead refused", edited(2, 0) { it.copy(dead = true) }.any { "dead" in it })
    check("not Survival game mode refused", edited(2, 0) { it.copy(survivalMode = false) }.any { "Survival" in it })
    check("staff states refused", edited(2, 0) { it.copy(restricted = "is in Mod Mode") }.any { "Mod Mode" in it })
    check("players at 1 heart are valid Lifesteal players", edited(2, 0) { it.copy(hearts = 1) }.isEmpty())
    check("players at 20 hearts are valid", edited(2, 0) { it.copy(hearts = 20) }.isEmpty())
    check("no heart record refused", edited(2, 0) { it.copy(hearts = null) }.any { "heart record" in it })
    check("hearts outside the limits refused", edited(2, 0) { it.copy(hearts = 21) }.any { "heart record" in it })
    check("the same player on both sides refused", RankedMatchRules.validate(request(2, { it[2] = facts(UUID(1L, 0), Side.B) })).any { "both sides" in it })
    // Java + Bedrock (Floodgate) players, mixed in one team: only their UUIDs matter.
    val mixed = RankedMatchRules.validate(request(2, { it[0] = facts(JAVA_A, Side.A, "JavaPlayer"); it[1] = facts(BEDROCK_A, Side.A, ".BedrockPlayer") }))
    check("mixed Java + Bedrock team valid (Floodgate ids are plain UUIDs)", mixed.isEmpty())
    check("Java and Floodgate ids never collide", JAVA_A != BEDROCK_A && BEDROCK_A.version() == 0)
}

// ── Commands while held ──────────────────────────────────────

private fun commands() {
    for (c in listOf("/msg Bob hi", "/tell Bob hi", "/r ok", "/minecraft:msg Bob x", "/W Bob hi")) check("allowed while held: $c", RankedMatchRules.commandAllowed(c))
    for (c in listOf("/spawn", "/home", "/homes", "/tpa Bob", "/tpaccept", "/rtp", "/team home", "/team", "/lobby", "/hub", "/survival", "/lifesteal", "/ec", "/pv 1",
        "/trade Bob", "/withdrawheart", "/sell all", "/ah", "/kit pvp", "/warp shop", "/back", "/essentials:spawn", "/joshymc:team home", "/fly", "/heal")) {
        check("refused while held: $c", !RankedMatchRules.commandAllowed(c))
    }
}

// ── Settings ─────────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun settings() {
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    val map: Map<String, Any?> = Yaml().load(text)
    fun parse(m: Map<String, Any?>) = LifestealConfigParser.parse { p -> p.split('.').fold<String, Any?>(m) { node, key -> (node as? Map<*, *>)?.get(key) } }
    val bundled = parse(map)
    check("bundled lifesteal.yml has no problems", bundled.problems.isEmpty())
    val r = bundled.settings!!.rankedTeamWars
    check("approved sizes 2v2..5v5", r.teamSizes == listOf(2, 3, 4, 5))
    check("countdown default 10s", r.countdownSeconds == 10)
    check("time limit default 20 min", r.maxMatchMinutes == 20)
    fun withRanked(edit: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> {
        val m = map.toMutableMap()
        val section = (m["ranked-team-wars"] as Map<String, Any?>).toMutableMap()
        edit(section)
        m["ranked-team-wars"] = section
        return m
    }
    check("countdown 2 refused", parse(withRanked { it["countdown-seconds"] = 2 }).settings == null)
    check("countdown 61 refused", parse(withRanked { it["countdown-seconds"] = 61 }).settings == null)
    check("time limit 0 refused", parse(withRanked { it["max-match-minutes"] = 0 }).settings == null)
    check("time limit 'soon' refused", parse(withRanked { it["max-match-minutes"] = "soon" }).settings == null)
    check("countdown 30 accepted", parse(withRanked { it["countdown-seconds"] = 30 }).settings?.rankedTeamWars?.countdownSeconds == 30)
    val old = parse(withRanked { it.remove("countdown-seconds"); it.remove("max-match-minutes") })
    check("a lifesteal.yml from before #1361 still loads with defaults (warnings only)", old.settings?.rankedTeamWars?.countdownSeconds == 10 && old.problems.none { it.severe } && old.problems.size == 2)
}

// ── Store (real SQLite) ──────────────────────────────────────

private fun <T> withDb(dir: File, block: (RankedStore, DatabaseManager) -> T): T {
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        val s = RankedStore(db)
        s.createTables()
        return block(s, db)
    } finally {
        db.shutdown()
    }
}

/** Stand-ins for serialized item stacks: opaque Base64, as Paper's serializeAsBytes output is. */
private fun item(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

private val P1 = UUID(10L, 1L)
private val P2 = UUID(10L, 2L)
private val P3 = UUID(20L, 1L)
private val P4 = UUID(20L, 2L)

private fun newMatch(id: String, arena: String = "duel", now: Long = 1L) =
    RankedStore.NewMatch(id, "season-1", arena, "team-a", "Alpha", "team-b", "Bravo", 2, true, "console", now)

private fun participants(vararg ids: Pair<UUID, Side>) = ids.map { (u, s) -> MatchParticipant(u, s, if (s == Side.A) "team-a" else "team-b") }

private fun snap(uuid: UUID, vararg slots: String?): RankedStore.SnapshotData {
    val list = MutableList<String?>(41) { null }
    for ((i, s) in slots.withIndex()) list[i] = s
    return RankedStore.SnapshotData(uuid, list, 3, "lifesteal", Point(100.5, 70.0, -20.5, 45f, 10f))
}

private fun store() {
    val dir = Files.createTempDirectory("ranked-check").toFile()
    try {
        // A custom item (PDC id, custom enchant, durability) as Paper would serialize it, byte for byte.
        val customSword = item("minecraft:netherite_sword{Damage:37,PublicBukkitValues:{joshymc:custom_item_id:autumns_edge,joshymc:ce_lifesteal:3}}")
        val heartItem = item("minecraft:nether_star{PublicBukkitValues:{joshymc:custom_item_id:lifesteal_heart,joshymc:heart_serial:abc}}")

        withDb(dir) { s, _ ->
            // Arenas.
            check("create arena", s.createArena(readyArena(), 1))
            check("duplicate arena id refused", !s.createArena(readyArena(), 2))
            val loaded = s.arena("duel")!!
            check("new arena starts disabled with no geometry", !loaded.arena.enabled && loaded.arena.bounds == null && loaded.arena.teamA.isEmpty())
            val full = readyArena().copy(version = loaded.arena.version)
            val v2 = s.updateArena(full, 3)
            val back = s.arena("duel")!!.arena
            check("arena saved and read back exactly", back.copy(createdAt = 0, updatedAt = 0, version = 0) == full.copy(createdAt = 0, updatedAt = 0, version = 0) && back.version == v2)
            check("stale arena edit refused", throws { s.updateArena(full, 4) })
            check("second arena", s.createArena(readyArena(id = "other"), 5))

            // Match creation: arena, participants and snapshots in one transaction.
            s.insertMatch(newMatch("rtw-000000000001"), participants(P1 to Side.A, P2 to Side.A, P3 to Side.B, P4 to Side.B),
                listOf(snap(P1, customSword, null, heartItem), snap(P2, item("bow")), snap(P3), snap(P4, item("shield"))))
            check("match recorded as PREPARING", s.match("rtw-000000000001")?.state == MatchState.PREPARING)
            check("participants recorded", s.participants("rtw-000000000001").map { it.uuid }.toSet() == setOf(P1, P2, P3, P4))
            check("deleting an arena used by an open match refused", s.deleteArena("duel", 6) == RankedStore.DeleteResult.IN_USE)

            // Duplicate reservations.
            check("the same arena can't host two open matches", throws {
                s.insertMatch(newMatch("rtw-000000000002"), participants(UUID(30L, 1) to Side.A, UUID(30L, 2) to Side.B), listOf(snap(UUID(30L, 1)), snap(UUID(30L, 2))))
            })
            check("a player can't be in two open matches", throws {
                s.insertMatch(newMatch("rtw-000000000003", arena = "other"), participants(P1 to Side.A, UUID(30L, 2) to Side.B), listOf(snap(P1), snap(UUID(30L, 2))))
            })
            check("a failed creation writes nothing", s.match("rtw-000000000002") == null && s.match("rtw-000000000003") == null && s.snapshotsOf("rtw-000000000003").isEmpty())
            check("a match needs exactly one snapshot per participant", throws {
                s.insertMatch(newMatch("rtw-000000000004", arena = "other"), participants(UUID(31L, 1) to Side.A), emptyList())
            })

            // Snapshots: exact, never overwritten.
            val p1 = s.pendingSnapshot(P1)!!
            check("snapshot keeps every slot exactly (custom item ids, enchants, durability, Heart serials)", p1.slots[0] == customSword && p1.slots[1] == null && p1.slots[2] == heartItem && p1.slots.size == 41)
            check("snapshot keeps the held slot", p1.heldSlot == 3)
            check("snapshot keeps the authoritative return location", p1.returnWorld == "lifesteal" && p1.returnPoint == Point(100.5, 70.0, -20.5, 45f, 10f))
            check("pending owners", s.pendingSnapshotOwners() == setOf(P1, P2, P3, P4))

            // Lifecycle.
            check("PREPARING -> COUNTDOWN", s.moveState("rtw-000000000001", MatchState.PREPARING, MatchState.COUNTDOWN, 10))
            check("a stale transition does nothing", !s.moveState("rtw-000000000001", MatchState.PREPARING, MatchState.COUNTDOWN, 11))
            check("an invalid transition is refused before the database", throws { s.moveState("rtw-000000000001", MatchState.COUNTDOWN, MatchState.FINISHING, 12) })
            check("COUNTDOWN -> ACTIVE", s.moveState("rtw-000000000001", MatchState.COUNTDOWN, MatchState.ACTIVE, 20))
            check("start time recorded", s.match("rtw-000000000001")?.startedAt == 20L)
            check("death recorded once", s.defeat("rtw-000000000001", P3, DefeatReason.DEATH, 30) && !s.defeat("rtw-000000000001", P3, DefeatReason.DEATH, 31))
            check("disconnect recorded", s.defeat("rtw-000000000001", P4, DefeatReason.DISCONNECT, 32))
            check("winner recorded once", s.finish("rtw-000000000001", Side.A, "team-a", 40) && !s.finish("rtw-000000000001", Side.B, "team-b", 41))
            check("a finishing match can't be cancelled", !s.cancel("rtw-000000000001", "late", 42))
            check("completed once", s.complete("rtw-000000000001", 50) && !s.complete("rtw-000000000001", 51))
            val rec = s.match("rtw-000000000001")!!
            check("record: outcome, winner, timestamps", rec.outcome == "WIN" && rec.winner == Side.A && rec.winnerTeamId == "team-a" && rec.endedAt == 50L && rec.state == MatchState.COMPLETED)
            check("record: season, arena, teams, size, test flag", rec.seasonId == "season-1" && rec.arenaId == "duel" && rec.teamAId == "team-a" && rec.teamBId == "team-b" && rec.size == 2 && rec.test)
            check("record: restoration still pending", rec.restoration == "PENDING")
            check("participants released when the match ended", s.participants("rtw-000000000001").none { it.open })
            check("participant statuses kept", s.participants("rtw-000000000001").first { it.uuid == P4 }.defeatReason == DefeatReason.DISCONNECT)

            // Exactly-once restoration.
            check("restore marks once", s.markRestored(p1.id, "match completed", 60) && !s.markRestored(p1.id, "again", 61))
            check("no longer pending after restore", s.pendingSnapshot(P1) == null)
            for (u in listOf(P2, P3)) check("restore $u", s.markRestored(s.pendingSnapshot(u)!!.id, "respawned", 62))
            check("restoration still pending while one player is offline", s.match("rtw-000000000001")?.restoration == "PENDING")
            check("an arena with only finished matches can be deleted", s.deleteArena("other", 63) == RankedStore.DeleteResult.DELETED)
            check("deleted arenas are gone from the list", s.arenas().none { it.arena.id == "other" })
            check("a deleted id can be reused", s.createArena(readyArena(id = "other"), 64) && s.arena("other")?.arena?.bounds == null)
        }

        // Restart: the offline player's snapshot waits; arenas persist.
        withDb(dir) { s, _ ->
            check("arenas survive a restart", s.arena("duel")?.arena?.teamA == readyArena().teamA)
            val p4 = s.pendingSnapshot(P4)
            check("a disconnected player's snapshot survives a restart", p4 != null && p4.slots[0] == item("shield"))
            check("reconnect restores once", s.markRestored(p4!!.id, "rejoined", 70) && !s.markRestored(p4.id, "rejoined", 71))
            check("match restoration COMPLETE once everyone is restored", s.match("rtw-000000000001")?.restoration == "COMPLETE")
            check("events recorded", s.events("rtw-000000000001", 50).map { it.kind }.containsAll(listOf("CREATED", "STATE", "DEFEAT", "RESTORED")))
            check("a player who finished a match can join a new one", run {
                s.insertMatch(newMatch("rtw-000000000005", now = 80), participants(P1 to Side.A, P2 to Side.A, P3 to Side.B, P4 to Side.B), listOf(snap(P1, customSword), snap(P2), snap(P3), snap(P4)))
                true
            })
            check("a new snapshot never overwrites an old one", s.snapshot(s.snapshotId("rtw-000000000001", P1))?.status == "RESTORED" && s.pendingSnapshot(P1)?.matchId == "rtw-000000000005")
            check("ACTIVE", s.moveState("rtw-000000000005", MatchState.PREPARING, MatchState.COUNTDOWN, 81) && s.moveState("rtw-000000000005", MatchState.COUNTDOWN, MatchState.ACTIVE, 82))
            // Crash here: no shutdown cleanup.
        }

        // Crash recovery: interrupted matches are cancelled, inventories still pending, players and arena released.
        withDb(dir) { s, _ ->
            check("open match found after the crash", s.openMatchIds() == listOf("rtw-000000000005"))
            val recovered = s.recoverInterrupted(100)
            check("an interrupted ACTIVE match is cancelled", recovered == listOf(RankedStore.Recovered("rtw-000000000005", MatchState.ACTIVE, MatchState.CANCELLED)))
            val rec = s.match("rtw-000000000005")!!
            check("cancelled with the reason", rec.state == MatchState.CANCELLED && rec.outcome == "CANCELLED" && rec.cancelReason?.contains("restarted") == true && rec.winner == null)
            check("recovery runs once (nothing open afterwards)", s.recoverInterrupted(101).isEmpty())
            check("snapshots still pending after the crash (restored on join)", s.pendingSnapshotOwners() == setOf(P1, P2, P3, P4))
            check("crash snapshot still holds the real gear", s.pendingSnapshot(P1)?.slots?.get(0) == customSword)
            check("players released after recovery", s.participants("rtw-000000000005").none { it.open })
            val fresh = listOf(UUID(35L, 1), UUID(35L, 2), UUID(36L, 1), UUID(36L, 2))
            check("arena released after recovery (a new match can use it)", !throws {
                s.insertMatch(newMatch("rtw-000000000008", now = 90), participants(fresh[0] to Side.A, fresh[1] to Side.A, fresh[2] to Side.B, fresh[3] to Side.B), fresh.map { snap(it) })
            })
            check("cancelled cleanly", s.cancel("rtw-000000000008", "test cleanup", 91))
            for (u in fresh) s.markRestored(s.pendingSnapshot(u)!!.id, "test cleanup", 92)
            check("cancel after recovery does nothing", !s.cancel("rtw-000000000005", "again", 103))
            for (u in listOf(P1, P2, P3, P4)) s.markRestored(s.pendingSnapshot(u)!!.id, "rejoined", 104)

            // A FINISHING match at a crash keeps its winner.
            s.createArena(readyArena(id = "duel2"), 105)
            val q = listOf(UUID(40L, 1), UUID(40L, 2), UUID(41L, 1), UUID(41L, 2))
            s.insertMatch(newMatch("rtw-000000000006", arena = "duel2", now = 106), participants(q[0] to Side.A, q[1] to Side.A, q[2] to Side.B, q[3] to Side.B), q.map { snap(it) })
            s.moveState("rtw-000000000006", MatchState.PREPARING, MatchState.COUNTDOWN, 107)
            s.moveState("rtw-000000000006", MatchState.COUNTDOWN, MatchState.ACTIVE, 108)
            s.finish("rtw-000000000006", Side.B, "team-b", 109)
            // A PREPARING match at a crash is cancelled.
            s.createArena(readyArena(id = "duel3"), 110)
            val w = listOf(UUID(50L, 1), UUID(50L, 2))
            s.insertMatch(newMatch("rtw-000000000007", arena = "duel3", now = 111), participants(w[0] to Side.A, w[1] to Side.B), w.map { snap(it) })
        }
        withDb(dir) { s, _ ->
            val recovered = s.recoverInterrupted(200).associateBy { it.matchId }
            check("FINISHING at a crash completes with its winner kept", recovered["rtw-000000000006"]?.to == MatchState.COMPLETED && s.match("rtw-000000000006")?.winner == Side.B && s.match("rtw-000000000006")?.outcome == "WIN")
            check("PREPARING at a crash is cancelled", recovered["rtw-000000000007"]?.to == MatchState.CANCELLED)
            check("cancelled match records no winner", s.match("rtw-000000000007")?.winner == null)
            check("recent records newest first", s.recentMatches(3).first().id == "rtw-000000000007")
        }
    } finally {
        dir.deleteRecursively()
    }
}

// ── Hearts (normal heart service) ────────────────────────────

private fun hearts() {
    // Ranked kills go through the normal rules; nothing ranked-specific exists.
    check("ranked kill: normal transfer", HeartRules.decide(10, 10, 0, LIMITS) == HeartRules.Decision.Transfer(9, 11))
    check("victim at 1 heart keeps it (no elimination)", HeartRules.decide(1, 10, 0, LIMITS) == HeartRules.Decision.VictimAtMinimum)
    check("killer at 20 hearts: approved max rule", HeartRules.decide(10, 20, 0, LIMITS) == HeartRules.Decision.KillerAtMaximum)
    check("anti-farming cooldown applies in ranked too", HeartRules.decide(10, 10, 60_000, LIMITS) is HeartRules.Decision.Cooldown)
    check("teammates never transfer", HeartRules.decide(10, 10, 0, LIMITS, team = TeamCombatRules.HeartCheck.TEAMMATES) == HeartRules.Decision.Teammates)
    check("grace window never transfers", HeartRules.decide(10, 10, 0, LIMITS, window = ProgressionRules.HeartWindow.GRACE_PERIOD) == HeartRules.Decision.GracePeriod)

    val dir = Files.createTempDirectory("ranked-hearts").toFile()
    try {
        val db = DatabaseManager(dir, quietLogger())
        db.start()
        try {
            val hs = HeartStore(db)
            hs.createTables()
            val killer = UUID(60L, 1)
            val victim = UUID(60L, 2)
            hs.ensure(killer, 10, 1)
            hs.ensure(victim, 10, 1)
            val req = HeartStore.PvpRequest("pvp:boot:$victim:100", killer, victim, 1_000, 0, LIMITS)
            val first = hs.transferPvp(req)
            check("ranked kill moves one heart through HeartStore", first is HeartStore.PvpResult.Done && hs.load(victim)?.hearts == 9 && hs.load(killer)?.hearts == 11)
            check("the same death never transfers twice", hs.transferPvp(req) == HeartStore.PvpResult.Duplicate && hs.load(victim)?.hearts == 9)
            // Restoring an inventory has no heart rollback: the store is only changed by kills/admins.
            check("hearts stay where the kill left them", hs.load(victim)?.hearts == 9)
        } finally {
            db.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }
}

// ── Disconnect forfeits (issue #1465) ────────────────────────

private fun forfeits() {
    val last = RankedForfeitHeartLoss.LAST_OPPONENT
    fun policy(state: MatchState?, fighting: Boolean = true, kind: DisconnectKind = DisconnectKind.QUIT, timeouts: Boolean = true, loss: RankedForfeitHeartLoss = last) =
        RankedMatchRules.disconnect(state, fighting, kind, timeouts, loss)

    // 1. Before the start: cancelled, unrated, no heart.
    for (state in listOf(MatchState.CREATED, MatchState.VALIDATING, MatchState.PREPARING, MatchState.COUNTDOWN)) {
        val p = policy(state)
        check("#1465 disconnect in $state cancels the match without a heart", p.action == DisconnectAction.CANCEL_MATCH && !p.heartForfeit)
    }
    // 2. In the fight: a forfeit with a heart decision, whatever the kind of counted disconnect.
    for (kind in listOf(DisconnectKind.QUIT, DisconnectKind.TIMED_OUT, DisconnectKind.PLAYER_KICK, DisconnectKind.TRANSFER)) {
        val p = policy(MatchState.ACTIVE, kind = kind)
        check("#1465 ${kind.name} during the fight is a forfeit with a heart decision", p.action == DisconnectAction.FORFEIT && p.heartForfeit)
    }
    check("#1465 timeouts not counted when combat-logging.count-timeouts is false", policy(MatchState.ACTIVE, kind = DisconnectKind.TIMED_OUT, timeouts = false).let { it.action == DisconnectAction.LEAVE_EXEMPT && !it.heartForfeit })
    check("#1465 heart-loss none = match forfeit only", policy(MatchState.ACTIVE, loss = RankedForfeitHeartLoss.NONE).let { it.action == DisconnectAction.FORFEIT && !it.heartForfeit })
    for (kind in listOf(DisconnectKind.STAFF_KICK, DisconnectKind.SERVER_ERROR)) {
        val p = policy(MatchState.ACTIVE, kind = kind)
        check("#1465 ${kind.name} takes them out but never costs a heart", p.action == DisconnectAction.LEAVE_EXEMPT && !p.heartForfeit)
    }
    // 3. Already out, or the match is over: nothing.
    check("#1465 disconnect after being knocked out does nothing", policy(MatchState.ACTIVE, fighting = false).action == DisconnectAction.NONE)
    for (state in listOf(MatchState.FINISHING, MatchState.COMPLETED, MatchState.CANCELLED, null)) {
        check("#1465 disconnect after the match ($state) does nothing", policy(state).let { it.action == DisconnectAction.NONE && !it.heartForfeit })
    }
    // 4. Shutdown: left to shutdown (cancel + restore), never a forfeit or heart, in any state.
    for (state in MatchState.OPEN) {
        val p = policy(state, kind = DisconnectKind.SERVER_SHUTDOWN)
        check("#1465 shutdown in $state is never a forfeit", p.action == DisconnectAction.SHUTDOWN && !p.heartForfeit)
    }
    check("#1465 classification: stopping server wins over everything", CombatLogRules.classify("DISCONNECTED", "KICK_COMMAND", true, true) == DisconnectKind.SERVER_SHUTDOWN)
    check("#1465 classification: restart kick is a shutdown", CombatLogRules.classify("KICKED", "RESTART_COMMAND", false, false) == DisconnectKind.SERVER_SHUTDOWN)
    check("#1465 classification: /kick is staff", CombatLogRules.classify("KICKED", "KICK_COMMAND", false, false) == DisconnectKind.STAFF_KICK)
    check("#1465 classification: a plain quit counts", CombatLogRules.classify("DISCONNECTED", null, false, false) == DisconnectKind.QUIT)

    // 5. Match results for 2v2..5v5: the forfeit is an ordinary "out", so Elo comes from the normal win.
    for (size in 2..5) {
        var r = roster(size, size)
        r = r.defeat(UUID(2L, 0), DefeatReason.DISCONNECT)!!
        check("#1465 ${size}v$size: one forfeit leaves the match running", RankedMatchRules.decide(r, listOf(r.of(UUID(2L, 0))!!)) == Decision.Continue)
        check("#1465 ${size}v$size: the leaver can't fight again", !r.isActive(UUID(2L, 0)) && r.defeat(UUID(2L, 0), DefeatReason.DISCONNECT) == null)
        // Multiple teammates disconnecting (all of side B, some in the same tick).
        val outs = mutableListOf<MatchParticipant>()
        for (i in 1 until size) {
            r = r.defeat(UUID(2L, i.toLong()), DefeatReason.DISCONNECT)!!
            outs += r.of(UUID(2L, i.toLong()))!!
        }
        check("#1465 ${size}v$size: a whole side disconnecting = the other side wins", RankedMatchRules.decide(r, outs) == Decision.Winner(Side.A))
    }
    // 6. Same tick: disconnect + death.
    fun lastTick(aReason: DefeatReason, bReason: DefeatReason): Decision {
        var r = roster(2, 2).defeat(UUID(1L, 0), DefeatReason.DEATH)!!.defeat(UUID(2L, 0), DefeatReason.DEATH)!!
        r = r.defeat(UUID(1L, 1), aReason)!!.defeat(UUID(2L, 1), bReason)!!
        return RankedMatchRules.decide(r, listOf(r.of(UUID(1L, 1))!!, r.of(UUID(2L, 1))!!))
    }
    check("#1465 quitting in the tick the opponent's last player dies loses (no free cancel)", lastTick(DefeatReason.DEATH, DefeatReason.DISCONNECT) == Decision.Winner(Side.A))
    check("#1465 ... from either side", lastTick(DefeatReason.DISCONNECT, DefeatReason.DEATH) == Decision.Winner(Side.B))
    check("#1465 both sides dying in one tick is still no winner", lastTick(DefeatReason.DEATH, DefeatReason.DEATH) == Decision.NoWinner)
    check("#1465 both sides forfeiting in one tick is no winner", lastTick(DefeatReason.DISCONNECT, DefeatReason.DISCONNECT) == Decision.NoWinner)
    check("#1465 a staff kick in that tick isn't a forfeit", lastTick(DefeatReason.DEATH, DefeatReason.DISCONNECT_EXEMPT) == Decision.NoWinner)
    check("#1465 decide without tick info keeps the old behaviour", RankedMatchRules.decide(roster(1, 1).defeat(UUID(1L, 0), DefeatReason.DEATH)!!.defeat(UUID(2L, 0), DefeatReason.DISCONNECT)!!) == Decision.NoWinner)

    // 7. Recipient: the most recent opposing hit inside the window, never a teammate or a guess.
    val victim = UUID(70L, 1)
    val foeA = UUID(71L, 1)
    val foeB = UUID(71L, 2)
    val mate = UUID(70L, 2)
    val window = 15_000L
    val now = 100_000L
    val hits = listOf(HeartRules.RecentHit(foeA, now - 9_000), HeartRules.RecentHit(foeB, now - 2_000))
    val opponents = setOf(foeA, foeB)
    check("#1465 recipient = most recent opposing hitter", RankedMatchRules.forfeitRecipient(victim, hits, now, window) { it in opponents } == foeB)
    check("#1465 an ineligible latest hitter falls back to the previous one", RankedMatchRules.forfeitRecipient(victim, hits, now, window) { it == foeA } == foeA)
    check("#1465 no hits = no heart", RankedMatchRules.forfeitRecipient(victim, emptyList(), now, window) { true } == null)
    check("#1465 hits older than the window don't count", RankedMatchRules.forfeitRecipient(victim, listOf(HeartRules.RecentHit(foeA, now - window)), now, window) { true } == null)
    check("#1465 hits in the future don't count", RankedMatchRules.forfeitRecipient(victim, listOf(HeartRules.RecentHit(foeA, now + 1)), now, window) { true } == null)
    check("#1465 never the leaver themselves", RankedMatchRules.forfeitRecipient(victim, listOf(HeartRules.RecentHit(victim, now - 1)), now, window) { true } == null)
    check("#1465 teammates never receive it (eligibility refuses them)", RankedMatchRules.forfeitRecipient(victim, listOf(HeartRules.RecentHit(mate, now - 1)), now, window) { it in opponents } == null)
    check("#1465 window 0 = nobody", RankedMatchRules.forfeitRecipient(victim, hits, now, 0) { true } == null)
    var bounded = emptyList<HeartRules.RecentHit>()
    for (i in 0 until 50) bounded = RankedMatchRules.recordHit(bounded, HeartRules.RecentHit(foeA, i.toLong()))
    check("#1465 recorded hits are bounded, newest kept", bounded.size == RankedMatchRules.MAX_RECENT_HITS && bounded.last().at == 49L)
    check("#1465 one dedupe key per player per match", RankedMatchRules.forfeitDedupeKey("rtw-000000000001", victim) == RankedMatchRules.forfeitDedupeKey("rtw-000000000001", victim) &&
        RankedMatchRules.forfeitDedupeKey("rtw-000000000001", victim) != RankedMatchRules.forfeitDedupeKey("rtw-000000000002", victim) &&
        !RankedMatchRules.forfeitDedupeKey("rtw-000000000001", victim).startsWith("pvp:"))

    // 8. Hearts through the real HeartStore (minimum, maximum, cooldown, dedupe, teammates).
    val dir = Files.createTempDirectory("ranked-forfeit").toFile()
    try {
        val db = DatabaseManager(dir, quietLogger())
        db.start()
        try {
            val hs = HeartStore(db)
            hs.createTables()
            fun player(n: Long, hearts: Int): UUID {
                val u = UUID(80L, n)
                hs.ensure(u, 10, 1)
                if (hearts != 10) hs.adminChange(u, HeartRules.AdminOp.SET, hearts, LIMITS, "test", "test", 1)
                return u
            }
            fun forfeit(match: String, v: UUID, k: UUID, at: Long, team: TeamCombatRules.HeartCheck = TeamCombatRules.HeartCheck.ELIGIBLE) =
                hs.transferPvp(HeartStore.PvpRequest(RankedMatchRules.forfeitDedupeKey(match, v), k, v, at, 30 * 60_000L, LIMITS, team = team,
                    note = RankedMatchRules.forfeitNote(match, DisconnectKind.QUIT)))
            val v1 = player(1, 10)
            val k1 = player(2, 10)
            val first = forfeit("rtw-00000000000a", v1, k1, 1_000)
            check("#1465 a forfeit moves exactly one heart", first is HeartStore.PvpResult.Done && first.decision is HeartRules.Decision.Transfer && hs.load(v1)?.hearts == 9 && hs.load(k1)?.hearts == 11)
            check("#1465 the same forfeit never moves a second heart", forfeit("rtw-00000000000a", v1, k1, 1_001) == HeartStore.PvpResult.Duplicate && hs.load(v1)?.hearts == 9)
            val again = forfeit("rtw-00000000000b", v1, k1, 2_000)
            check("#1465 same-victim cooldown applies to forfeits", again is HeartStore.PvpResult.Done && again.decision is HeartRules.Decision.Cooldown && hs.load(v1)?.hearts == 9)
            val kill = hs.transferPvp(HeartStore.PvpRequest("pvp:boot:$v1:500", k1, v1, 3_000, 30 * 60_000L, LIMITS))
            check("#1465 a death after a forfeit in the cooldown moves nothing more", kill is HeartStore.PvpResult.Done && !kill.decision.changesHearts && hs.load(v1)?.hearts == 9)
            val low = player(3, 1)
            val lowKiller = player(4, 10)
            val atMin = forfeit("rtw-00000000000c", low, lowKiller, 1_000)
            check("#1465 a leaver at 1 heart keeps it and the opponent gains nothing", atMin is HeartStore.PvpResult.Done && atMin.decision == HeartRules.Decision.VictimAtMinimum && hs.load(low)?.hearts == 1 && hs.load(lowKiller)?.hearts == 10)
            val v5 = player(5, 10)
            val full = player(6, 20)
            val atMax = forfeit("rtw-00000000000d", v5, full, 1_000)
            check("#1465 an opponent at 20 hearts gains nothing (approved maximum rule)", atMax is HeartStore.PvpResult.Done && atMax.decision == HeartRules.Decision.KillerAtMaximum && hs.load(full)?.hearts == 20 && hs.load(v5)?.hearts == 10)
            val v7 = player(7, 10)
            val k8 = player(8, 10)
            val mates = forfeit("rtw-00000000000e", v7, k8, 1_000, TeamCombatRules.HeartCheck.TEAMMATES)
            check("#1465 teammates never receive a forfeit heart", mates is HeartStore.PvpResult.Done && mates.decision == HeartRules.Decision.Teammates && hs.load(v7)?.hearts == 10 && hs.load(k8)?.hearts == 10)
            val audit = db.query("SELECT reason FROM lifesteal_heart_audit WHERE uuid = ? AND operation = 'PVP_LOSS'", v1.toString()) { it.getString("reason") }
            check("#1465 the forfeit is audited as one, with its match", audit.size == 1 && audit[0].contains("ranked disconnect forfeit, match rtw-00000000000a"))
            val total = db.queryFirst("SELECT SUM(hearts) AS s FROM lifesteal_hearts") { it.getInt("s") }
            check("#1465 forfeits never create or destroy hearts", total == 9 + 11 + 1 + 10 + 10 + 20 + 10 + 10)
        } finally {
            db.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }

    // 9. Snapshot restoration for a forfeit: survives reconnects and restarts, applied once.
    val sdir = Files.createTempDirectory("ranked-forfeit-snap").toFile()
    try {
        val leaver = UUID(90L, 1)
        val others = listOf(UUID(90L, 2), UUID(91L, 1), UUID(91L, 2))
        withDb(sdir) { s, _ ->
            s.createArena(readyArena(), 1)
            s.insertMatch(newMatch("rtw-0000000000f1"), participants(leaver to Side.A, others[0] to Side.A, others[1] to Side.B, others[2] to Side.B),
                listOf(snap(leaver, item("netherite_sword")), snap(others[0]), snap(others[1]), snap(others[2])))
            s.moveState("rtw-0000000000f1", MatchState.PREPARING, MatchState.COUNTDOWN, 2)
            s.moveState("rtw-0000000000f1", MatchState.COUNTDOWN, MatchState.ACTIVE, 3)
            check("#1465 the forfeit is recorded once", s.defeat("rtw-0000000000f1", leaver, DefeatReason.DISCONNECT, 4) && !s.defeat("rtw-0000000000f1", leaver, DefeatReason.DISCONNECT, 5))
            check("#1465 the leaver's snapshot waits while they're offline", s.pendingSnapshot(leaver)?.slots?.get(0) == item("netherite_sword"))
            s.defeat("rtw-0000000000f1", others[0], DefeatReason.DEATH, 6)
            check("#1465 a forfeit-decided match records an ordinary WIN (rated like any other)", s.finish("rtw-0000000000f1", Side.B, "team-b", 7) && s.complete("rtw-0000000000f1", 8) &&
                s.match("rtw-0000000000f1")?.let { it.outcome == "WIN" && it.winner == Side.B } == true)
            check("#1465 no second result for the same match", !s.finish("rtw-0000000000f1", Side.A, "team-a", 9) && !s.cancel("rtw-0000000000f1", "late", 9))
            check("#1465 the forfeit reason is kept", s.participants("rtw-0000000000f1").first { it.uuid == leaver }.defeatReason == DefeatReason.DISCONNECT)
            check("#1465 a new reason value reads back (staff kick)", run {
                s.createArena(readyArena(id = "other"), 10)
                val q = listOf(UUID(92L, 1), UUID(92L, 2))
                s.insertMatch(newMatch("rtw-0000000000f2", arena = "other", now = 11), participants(q[0] to Side.A, q[1] to Side.B), q.map { snap(it) })
                s.moveState("rtw-0000000000f2", MatchState.PREPARING, MatchState.COUNTDOWN, 12)
                s.moveState("rtw-0000000000f2", MatchState.COUNTDOWN, MatchState.ACTIVE, 13)
                s.defeat("rtw-0000000000f2", q[0], DefeatReason.DISCONNECT_EXEMPT, 14)
                s.participants("rtw-0000000000f2").first { it.uuid == q[0] }.defeatReason == DefeatReason.DISCONNECT_EXEMPT
            })
        }
        withDb(sdir) { s, _ ->
            val pending = s.pendingSnapshot(leaver)
            check("#1465 the leaver's snapshot survives a restart", pending != null && pending.slots[0] == item("netherite_sword"))
            check("#1465 rejoining restores exactly once (rapid reconnects can't apply it twice)", s.markRestored(pending!!.id, "rejoined", 20) && !s.markRestored(pending.id, "rejoined", 21) && s.pendingSnapshot(leaver) == null)
        }
    } finally {
        sdir.deleteRecursively()
    }

    // 10. Settings.
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    @Suppress("UNCHECKED_CAST")
    val map: Map<String, Any?> = Yaml().load(text)
    fun parse(m: Map<String, Any?>) = LifestealConfigParser.parse { p -> p.split('.').fold<String, Any?>(m) { node, key -> (node as? Map<*, *>)?.get(key) } }
    @Suppress("UNCHECKED_CAST")
    fun withForfeit(value: Any?): Map<String, Any?> {
        val m = map.toMutableMap()
        val section = (m["ranked-team-wars"] as Map<String, Any?>).toMutableMap()
        if (value == null) section.remove("disconnect-forfeit") else section["disconnect-forfeit"] = mapOf("heart-loss" to value)
        m["ranked-team-wars"] = section
        return m
    }
    check("#1465 bundled heart-loss is last-opponent", parse(map).settings?.rankedTeamWars?.disconnectHeartLoss == RankedForfeitHeartLoss.LAST_OPPONENT)
    check("#1465 heart-loss none accepted", parse(withForfeit("none")).settings?.rankedTeamWars?.disconnectHeartLoss == RankedForfeitHeartLoss.NONE)
    check("#1465 an unknown heart-loss value is refused (never guessed)", parse(withForfeit("everyone")).settings == null)
    val missing = parse(withForfeit(null))
    check("#1465 an older lifesteal.yml without the section still loads (warning only)", missing.settings?.rankedTeamWars?.disconnectHeartLoss == RankedForfeitHeartLoss.LAST_OPPONENT && missing.problems.none { it.severe })

    // 11. Wiring.
    val manager = source("lifesteal/ranked/LifestealRankedManager.kt")
    val listener = source("lifesteal/ranked/LifestealRankedListener.kt")
    val heartManager = source("lifesteal/hearts/LifestealHeartManager.kt")
    check("#1465 quits go through the one policy", manager.contains("RankedMatchRules.disconnect(") && manager.contains("CombatLogRules.classify(quitReason, kickCause, Bukkit.isStopping(), transferPending)"))
    check("#1465 the quit passes Paper's quit reason and kick cause", listener.contains("ranked.onQuit(event.player, event.reason.name, kickCause)") && listener.contains("fun onKick(event: PlayerKickEvent)"))
    check("#1465 the recipient is chosen before the defeat clears the hits", manager.indexOf("val recipient = if (policy.heartForfeit) forfeitRecipient(") in 0 until manager.indexOf("defeat(match, uuid, DefeatReason.DISCONNECT)"))
    check("#1465 only landed hits between opponents are recorded", Regex("""MONITOR, ignoreCancelled = true\)\s*\n\s*fun onLandedHit""").containsMatchIn(listener) && manager.contains("match.roster.sameSide(attacker.uniqueId, victim.uniqueId) != false"))
    check("#1465 recipient must still be fighting on the other side", manager.contains("match.roster.isActive(attacker) && match.roster.sameSide(attacker, victim) == false"))
    check("#1465 the forfeit heart uses the normal transferPvp with the team and progression checks", Regex("""fun onRankedForfeit[\s\S]*?heartCheck\(killer\.uniqueId, victim\.uniqueId\)[\s\S]*?heartWindow\(now\)[\s\S]*?store\.transferPvp\(request\)""").containsMatchIn(heartManager))
    check("#1465 a forfeit never makes a Heart item or Team XP", Regex("""fun onRankedForfeit[\s\S]*?\n    }\n""").find(heartManager)?.value?.let { !it.contains("issue(") && !it.contains("onPvpKill") && !it.contains("HeartItem") } == true)
    check("#1465 shutdown disconnects decide nothing (stop() cancels and restores)", manager.contains("RankedMatchRules.DisconnectAction.SHUTDOWN -> Unit"))
    check("#1465 the decision gets this tick's outs", manager.contains("RankedMatchRules.decide(match.roster, outs)"))
    check("#1465 Elo only from a completed match (never from the forfeit itself)", manager.split("lifestealRatingManager.onMatchCompleted(").size == 2)
}

// ── Issue #1474: ranked death detection, heart outcomes, cooldown regression ──

private fun deathDetection() {
    val m = "rtw-0000000014a1"
    // 1. Still identifiable after the ranked death handler (HIGHEST) took the victim out of `held`.
    check("#1474 a ranked death stays ranked after the defeat (LOWEST copy)", RankedMatchRules.deathMatch(RankedMatchRules.DeathCapture(m, 50), 50, stillHeld = null) == m)
    // 2. Every processing stage gives the same answer.
    check("#1474 LOWEST (still held, nothing copied yet) = ranked", RankedMatchRules.deathMatch(null, 50, stillHeld = m) == m)
    check("#1474 MONITOR (copied, defeated) = ranked", RankedMatchRules.deathMatch(RankedMatchRules.DeathCapture(m, 50), 50, null) == m)
    check("#1474 copy and live lookup agree", RankedMatchRules.deathMatch(RankedMatchRules.DeathCapture(m, 50), 50, m) == m)
    // 3. Ordinary deaths stay ordinary.
    check("#1474 an ordinary death isn't ranked", RankedMatchRules.deathMatch(null, 50, null) == null)
    check("#1474 a stale copy from another tick never makes a later death ranked", RankedMatchRules.deathMatch(RankedMatchRules.DeathCapture(m, 49), 50, null) == null)
    // 4. A ranked environmental death never uses the open-world combat-tag credit (#1461).
    val victim = UUID(140L, 1)
    val foe = UUID(140L, 2)
    fun credit(ranked: String?) = HeartRules.combatTagKiller(
        HeartRules.Attribution.ENVIRONMENT, victim, tagExpiresAt = 10_000, hits = listOf(HeartRules.RecentHit(foe, 1_000)), now = 2_000,
        tagMillis = 15_000, excluded = ranked != null,
    ) { true }
    check("#1474 ordinary environmental death while tagged is still credited (unchanged #1461 rule)", credit(RankedMatchRules.deathMatch(null, 50, null)) == foe)
    check("#1474 ranked environmental death is never credited through the tag, even after the defeat", credit(RankedMatchRules.deathMatch(RankedMatchRules.DeathCapture(m, 50), 50, null)) == null)
    // 5. Simultaneous deaths: each victim keeps their own copy; the next-tick decision is unchanged.
    val a = UUID(141L, 1)
    val b = UUID(141L, 2)
    val captures = mapOf(a to RankedMatchRules.DeathCapture(m, 70), b to RankedMatchRules.DeathCapture(m, 70))
    check("#1474 simultaneous deaths are both ranked", captures.values.all { RankedMatchRules.deathMatch(it, 70, null) == m })
    val roster = MatchRoster(listOf(MatchParticipant(a, Side.A, "team-a"), MatchParticipant(b, Side.B, "team-b")))
    val after = roster.defeat(a, DefeatReason.DEATH)!!.defeat(b, DefeatReason.DEATH)!!
    check("#1474 simultaneous last deaths are still a no-winner cancel", RankedMatchRules.decide(after, after.participants) == Decision.NoWinner)

    // Wiring.
    val heart = source("lifesteal/hearts/LifestealHeartManager.kt")
    val heartListener = source("lifesteal/hearts/LifestealHeartListener.kt")
    val rankedListener = source("lifesteal/ranked/LifestealRankedListener.kt")
    check("#1474 the ranked match is copied at LOWEST, before the HIGHEST defeat", heartListener.contains("@EventHandler(priority = EventPriority.LOWEST)\n    fun onDeathCaptureTag(event: PlayerDeathEvent) = hearts.captureCombatTag(event)") &&
        heart.contains("rankedDeaths[uuid] = RankedMatchRules.DeathCapture(it.id, Bukkit.getCurrentTick(),") &&
        rankedListener.contains("@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)\n    fun onDeath(event: PlayerDeathEvent)"))
    check("#1474 the heart death handler decides it once through deathMatch", heart.contains("val rankedCapture = rankedDeaths.remove(event.player.uniqueId)") &&
        heart.contains("val rankedMatch = RankedMatchRules.deathMatch(rankedCapture, tick,"))
    check("#1474 the combat-tag exclusion no longer depends on `held`", heart.contains("rankedMatch != null ||") && !heart.contains("lifestealRankedManager.matchOf(victim.uniqueId) != null"))
    check("#1474 the copy is dropped on quit", heart.contains("rankedDeaths.remove(player.uniqueId)"))
    check("#1474 still one transferPvp per heart path (no second transfer for the summary)", Regex("store\\.transferPvp\\(").findAll(heart).count() == 3)
}

private fun heartFeedback() {
    val summary = source("lifesteal/ranked/RankedHeartSummary.kt")
    check("#1474 the summary never touches the heart service", !summary.contains("import com.liam.joshymc.lifesteal.hearts.HeartStore") && !summary.contains("transferPvp(") && !summary.contains("adminChange("))
    // Every decision maps, and only the two that change hearts count as moved.
    val all = listOf(
        HeartRules.Decision.Transfer(9, 11), HeartRules.Decision.VictimLosesOnly(9), HeartRules.Decision.VictimAtMinimum, HeartRules.Decision.KillerAtMaximum,
        HeartRules.Decision.Cooldown(60_000), HeartRules.Decision.Teammates, HeartRules.Decision.Allies, HeartRules.Decision.TeamUnknown,
        HeartRules.Decision.GracePeriod, HeartRules.Decision.NotLaunched, HeartRules.Decision.ProgressionUnknown,
    )
    check("#1474 moved == the heart service's changesHearts, for every decision", all.all { RankedHeartSummary.Outcome.of(it).moved == it.changesHearts })
    check("#1474 a refused transfer is never worded as a steal", RankedHeartSummary.Outcome.entries.filter { !it.moved }.all { it.label.startsWith("no heart") && !it.label.contains("stolen") })

    val dir = Files.createTempDirectory("ranked-feedback").toFile()
    try {
        val db = DatabaseManager(dir, quietLogger())
        db.start()
        try {
            val hs = HeartStore(db)
            hs.createTables()
            fun player(n: Long, hearts: Int): UUID {
                val u = UUID(142L, n)
                hs.ensure(u, 10, 1)
                if (hearts != 10) hs.adminChange(u, HeartRules.AdminOp.SET, hearts, LIMITS, "test", "test", 1)
                return u
            }
            val cooldown = 30 * 60_000L
            fun kill(k: UUID, v: UUID, at: Long, tick: Int) = hs.transferPvp(HeartStore.PvpRequest("pvp:boot:$v:$tick", k, v, at, cooldown, LIMITS)) as HeartStore.PvpResult.Done
            fun entry(k: UUID, v: UUID, d: HeartStore.PvpResult.Done) =
                RankedHeartSummary.Entry(k, "K${k.leastSignificantBits}", v, "V${v.leastSignificantBits}", RankedHeartSummary.Outcome.of(d.decision))
            val k1 = player(1, 10)
            val v1 = player(2, 10)
            val low = player(3, 1)
            val full = player(4, 20)
            val v4 = player(5, 10)
            val first = kill(k1, v1, 1_000_000, 1)
            val again = kill(k1, v1, 1_060_000, 2)
            val lastHeart = kill(k1, low, 1_000_000, 3)
            val atMax = kill(full, v4, 1_000_000, 4)
            // 6-9.
            check("#1474 a successful transfer shows as stolen", RankedHeartSummary.line(entry(k1, v1, first)).let { it.moved && it.text == "K1 killed V2: heart stolen" })
            check("#1474 a cooldown refusal shows as the cooldown", RankedHeartSummary.Outcome.of(again.decision) == RankedHeartSummary.Outcome.COOLDOWN && !RankedHeartSummary.line(entry(k1, v1, again)).moved)
            check("#1474 a 1-heart victim shows as last heart", RankedHeartSummary.Outcome.of(lastHeart.decision) == RankedHeartSummary.Outcome.LAST_HEART && hs.load(low)?.hearts == 1)
            check("#1474 a killer at 20 shows as at the maximum", RankedHeartSummary.Outcome.of(atMax.decision) == RankedHeartSummary.Outcome.KILLER_AT_MAXIMUM && hs.load(full)?.hearts == 20)
            // 10-12.
            var entries = emptyList<RankedHeartSummary.Entry>()
            for ((k, v, d) in listOf(Triple(k1, v1, first), Triple(k1, low, lastHeart), Triple(full, v4, atMax))) entries = RankedHeartSummary.add(entries, entry(k, v, d))
            val transactionsBefore = hs.transactionCount()
            val heartsBefore = db.queryFirst("SELECT SUM(hearts) AS s FROM lifesteal_hearts") { it.getInt("s") }
            val lines = RankedHeartSummary.lines(entries)
            check("#1474 refused transfers never appear as successful", lines.drop(1).count { it.moved } == 1 && lines.drop(1).filter { it.text.contains("heart stolen") }.size == 1)
            check(
                "#1474 several kills are summarized in order with a correct total",
                lines.size == 4 && lines[0].text == "Hearts this match: 1 of 3 moved a heart." &&
                    lines[1].text.startsWith("K1 killed V2") && lines[2].text.startsWith("K1 killed V3") && lines[3].text.startsWith("K4 killed V5")
            )
            check(
                "#1474 building the summary makes no heart transaction and moves no heart",
                hs.transactionCount() == transactionsBefore && db.queryFirst("SELECT SUM(hearts) AS s FROM lifesteal_hearts") { it.getInt("s") } == heartsBefore
            )
            check("#1474 the same kill reported twice is listed once", RankedHeartSummary.add(entries, entries[0]) === entries)
            val forfeit = RankedHeartSummary.Entry(k1, "Ann", v1, "Bob", RankedHeartSummary.Outcome.COOLDOWN, RankedHeartSummary.Source.FORFEIT)
            check(
                "#1474 a forfeit has its own wording and doesn't collide with a kill",
                RankedHeartSummary.add(entries, forfeit).size == 4 && RankedHeartSummary.line(forfeit).text == "Bob forfeited to Ann: no heart, same-victim cooldown"
            )
            check("#1474 no heart decision = no summary section", RankedHeartSummary.lines(emptyList()).isEmpty())
        } finally {
            db.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }

    // 2v2 .. 5v5: at most 2 x size - 1 deaths, every one listed, no fold.
    for (size in 2..5) {
        val n = 2 * size - 1
        val e = (1..n).fold(emptyList<RankedHeartSummary.Entry>()) { acc, i ->
            RankedHeartSummary.add(acc, RankedHeartSummary.Entry(UUID(143L, i.toLong()), "Killer$i", UUID(144L, i.toLong()), "Victim$i", RankedHeartSummary.Outcome.STOLEN))
        }
        check("#1474 ${size}v$size summary lists all $n kills plus one header", RankedHeartSummary.lines(e).size == n + 1 && RankedHeartSummary.lines(e).none { it.text.startsWith("...") })
    }
    val many = (1..30).fold(emptyList<RankedHeartSummary.Entry>()) { acc, i ->
        RankedHeartSummary.add(acc, RankedHeartSummary.Entry(UUID(145L, i.toLong()), "K", UUID(146L, i.toLong()), "V", RankedHeartSummary.Outcome.COOLDOWN))
    }
    check("#1474 the per-match list is bounded", many.size == RankedHeartSummary.MAX_ENTRIES)
    check(
        "#1474 the chat section is bounded (no spam)",
        RankedHeartSummary.lines(many).size == RankedHeartSummary.MAX_LINES + 2 &&
            RankedHeartSummary.lines(many).last().text == "... and ${RankedHeartSummary.MAX_ENTRIES - RankedHeartSummary.MAX_LINES} more."
    )

    val manager = source("lifesteal/ranked/LifestealRankedManager.kt")
    check(
        "#1474 the summary follows the win and the cancel broadcasts",
        Regex("""won the ranked match!", NamedTextColor\.GOLD\)\)\s*\n\s*heartSummary\(match\)""").containsMatchIn(manager) &&
            Regex("""No winner\.", NamedTextColor\.RED\)\)[\s\S]{0,200}heartSummary\(match\)""").containsMatchIn(manager)
    )
    check("#1474 the summary goes only to the match (roster + spectators)", manager.contains("private fun heartSummary(match: Match)") && manager.contains("for (uuid in match.roster.uuids + match.spectators)"))
    check("#1474 forfeits are summarized from their stored decision", manager.contains("RankedHeartSummary.Outcome.of(it.decision)") && manager.contains("RankedHeartSummary.Source.FORFEIT"))
    check("#1474 outcomes live on the match (released with it)", manager.contains("var heartOutcomes: List<RankedHeartSummary.Entry> = emptyList()"))
    check("#1474 the summary carries no connection or review information", RankedHeartSummary.Outcome.entries.none { o -> listOf("connection", "alt", "review", "suspicious").any { o.label.contains(it, ignoreCase = true) } })
}

private fun cooldownRegression() {
    val dir = Files.createTempDirectory("ranked-cooldown").toFile()
    val cooldown = 30 * 60_000L
    val killer = UUID(150L, 1)
    val victim = UUID(150L, 2)
    val other = UUID(150L, 3)
    try {
        fun <T> heartsDb(block: (HeartStore) -> T): T {
            val db = DatabaseManager(dir, quietLogger())
            db.start()
            try {
                val hs = HeartStore(db)
                hs.createTables()
                return block(hs)
            } finally {
                db.shutdown()
            }
        }
        fun kill(hs: HeartStore, k: UUID, v: UUID, at: Long, key: String) = hs.transferPvp(HeartStore.PvpRequest(key, k, v, at, cooldown, LIMITS)) as HeartStore.PvpResult.Done
        val t0 = 10_000_000L
        heartsDb { hs ->
            for (u in listOf(killer, victim, other)) hs.ensure(u, 10, 1)
            // 13. Ranked kill with the real 30-minute cooldown.
            check("#1474 a ranked kill moves a heart", kill(hs, killer, victim, t0, "pvp:boot:$victim:100").decision is HeartRules.Decision.Transfer)
            // 14. Back-to-back match, same pair, two minutes later: refused, nothing restarted.
            val rematch = kill(hs, killer, victim, t0 + 120_000, "pvp:boot:$victim:2500")
            check("#1474 a back-to-back match can't bypass the cooldown", rematch.decision is HeartRules.Decision.Cooldown && hs.lastTransferAt(killer, victim) == t0)
            check("#1474 a new pairing in the rematch still moves a heart", kill(hs, other, victim, t0 + 120_000, "pvp:boot:$victim:2501").decision is HeartRules.Decision.Transfer)
            // 15. Ranked -> open world (same table).
            check("#1474 ranked and open-world PvP share the cooldown", kill(hs, killer, victim, t0 + 300_000, "pvp:boot:$victim:6000").decision is HeartRules.Decision.Cooldown)
            // 16. A forfeit inside the cooldown moves nothing either.
            check("#1474 ranked forfeits respect the same cooldown", kill(hs, killer, victim, t0 + 400_000, RankedMatchRules.forfeitDedupeKey("rtw-0000000014c1", victim)).decision is HeartRules.Decision.Cooldown)
            check("#1474 the cooldown is directional (revenge allowed)", kill(hs, victim, killer, t0 + 400_000, "pvp:boot:$killer:8000").decision is HeartRules.Decision.Transfer)
        }
        // 17. After a restart: still running, then over after 30 minutes.
        heartsDb { hs ->
            check("#1474 the cooldown survives a restart", kill(hs, killer, victim, t0 + 600_000, "pvp:boot2:$victim:10").decision is HeartRules.Decision.Cooldown)
            check("#1474 the cooldown ends after 30 minutes (never longer)", kill(hs, killer, victim, t0 + cooldown + 1, "pvp:boot2:$victim:40000").decision is HeartRules.Decision.Transfer)
        }
    } finally {
        dir.deleteRecursively()
    }
    // 18. Elo never reads hearts or heart outcomes.
    val ratingDir = File("src/main/kotlin/com/liam/joshymc/lifesteal/ranked/rating")
    val rating = ratingDir.listFiles()!!.filter { it.name.endsWith(".kt") }.map { it.readText() }
    check(
        "#1474 Elo is independent of heart transfers",
        rating.isNotEmpty() && rating.none { it.contains("lifesteal_heart") || it.contains("HeartStore") || it.contains("heartOutcomes") || it.contains("RankedHeartSummary") }
    )
    check("#1474 the shared cooldown default stays 30 minutes", resourceText("lifesteal.yml").contains("same-victim-cooldown-minutes: 30"))
}

// ── Issue #1478: environmental deaths in ranked matches ──────

private fun environmentalDeaths() {
    val m = "rtw-0000000014e1"
    val tick = 300
    val window = 15_000L // combat.tag-duration-seconds: 15
    val now = 5_000_000L
    // A 3v3: victim + two teammates on A, three opponents on B.
    val victim = UUID(1L, 0)
    val mate = UUID(1L, 1)
    val foe1 = UUID(2L, 0)
    val foe2 = UUID(2L, 1)
    val r = roster(3, 3)
    val opposite: (UUID) -> Boolean = { r.sameSide(it, victim) == false }
    fun cap(vararg hits: HeartRules.RecentHit, match: String = m, at: Long = now, t: Int = tick) = RankedMatchRules.DeathCapture(match, t, hits.toList(), at)
    fun credit(
        capture: RankedMatchRules.DeathCapture?,
        direct: HeartRules.Attribution = HeartRules.Attribution.ENVIRONMENT,
        match: String? = m,
        currentTick: Int = tick,
        excluded: Boolean = false,
        eligible: (UUID) -> Boolean = opposite,
    ) = RankedMatchRules.environmentalKiller(direct, victim, match, capture, currentTick, window, excluded, eligible)
    val hit = cap(HeartRules.RecentHit(foe1, now - 3_000))

    // 1. A direct ranked kill keeps its killer (this rule never replaces it).
    check("#1478 a direct ranked PvP kill is left alone", credit(hit, HeartRules.Attribution.PVP_KILL) == null)
    check("#1478 an invalid or unloaded killer is never replaced", credit(hit, HeartRules.Attribution.INVALID_KILLER) == null && credit(hit, HeartRules.Attribution.NOT_LOADED) == null)
    // 2-4. Knocked into lava / the void / a fatal fall: the final damage has no player behind it.
    check("#1478 knocked into lava, void or a fatal fall credits the opponent", credit(hit) == foe1)
    check(
        "#1478 the audit note names the match and the cause",
        listOf("lava", "out_of_world", "fall").all { RankedMatchRules.environmentalNote(m, it) == "ranked environmental credit, match $m, died to $it" }
    )
    // 5. Jumping into lava (or own TNT) after an opponent's hit.
    check("#1478 a self-inflicted death after a recent hit still credits the opponent", credit(hit, HeartRules.Attribution.SELF) == foe1)
    // 6-7. No recent opponent: nobody, never a guess.
    check("#1478 no recorded hit = no heart", credit(cap()) == null)
    check("#1478 a hit one window old has expired", credit(cap(HeartRules.RecentHit(foe1, now - window))) == null)
    check("#1478 a hit just inside the window counts", credit(cap(HeartRules.RecentHit(foe1, now - window + 1))) == foe1)
    check("#1478 a hit 'in the future' doesn't count", credit(cap(HeartRules.RecentHit(foe1, now + 1))) == null)
    // 8. Several attackers: the most recent eligible one.
    val both = cap(HeartRules.RecentHit(foe1, now - 8_000), HeartRules.RecentHit(foe2, now - 1_000))
    check("#1478 the most recent attacker is credited", credit(both) == foe2)
    check("#1478 an ineligible latest attacker falls back to the previous one", credit(both, eligible = { it == foe1 }) == foe1)
    // 9-10. Teammates and the victim themselves.
    check("#1478 teammate damage is never credited", credit(cap(HeartRules.RecentHit(mate, now - 500))) == null)
    check("#1478 a teammate's later hit doesn't hide the opponent's", credit(cap(HeartRules.RecentHit(foe1, now - 5_000), HeartRules.RecentHit(mate, now - 500))) == foe1)
    check("#1478 self-inflicted damage is never credited", credit(cap(HeartRules.RecentHit(victim, now - 500))) == null)
    // 14-18. One copy, one death, one match.
    check("#1478 /kill is excluded", credit(hit, excluded = true) == null)
    check("#1478 an ordinary (non-ranked) death never uses the ranked rule", credit(hit, match = null) == null)
    check("#1478 a copy from another tick (an earlier death, a restart) never counts", credit(hit, currentTick = tick + 1) == null)
    check("#1478 hits never leak from another match", credit(cap(HeartRules.RecentHit(foe1, now - 1_000), match = "rtw-0000000014e2")) == null)
    check("#1478 no copy = nothing", credit(null) == null)
    check("#1478 a cancelled/finished match (manager says not ACTIVE) credits nobody", credit(hit, eligible = { false }) == null)
    check("#1478 a second defeat of the same player is refused (no double-counted defeat)", r.defeat(victim, DefeatReason.DEATH)!!.defeat(victim, DefeatReason.DISCONNECT) == null)
    check("#1478 a defeated attacker keeps their side, so their hit still counts", r.defeat(foe1, DefeatReason.DEATH)!!.sameSide(foe1, victim) == false)
    // 19. 2v2 .. 5v5: always the opposite side, never one's own.
    for (size in 2..5) {
        val rs = roster(size, size)
        val v = UUID(1L, 0)
        val hits = (0 until size).map { HeartRules.RecentHit(UUID(2L, it.toLong()), now - 10_000 + it) } +
            (1 until size).map { HeartRules.RecentHit(UUID(1L, it.toLong()), now - 100 + it) }
        val got = RankedMatchRules.environmentalKiller(
            HeartRules.Attribution.ENVIRONMENT, v, m, RankedMatchRules.DeathCapture(m, tick, hits, now), tick, window, false
        ) { rs.sameSide(it, v) == false }
        check("#1478 ${size}v$size credits the latest opponent, never a teammate", got == UUID(2L, (size - 1).toLong()))
    }

    // 11-14. Hearts through the real HeartStore: same limits, cooldown and dedupe as any kill.
    val dir = Files.createTempDirectory("ranked-env").toFile()
    try {
        val db = DatabaseManager(dir, quietLogger())
        db.start()
        try {
            val hs = HeartStore(db)
            hs.createTables()
            fun player(n: Long, hearts: Int): UUID {
                val u = UUID(147L, n)
                hs.ensure(u, 10, 1)
                if (hearts != 10) hs.adminChange(u, HeartRules.AdminOp.SET, hearts, LIMITS, "test", "test", 1)
                return u
            }
            val cooldown = 30 * 60_000L
            fun env(k: UUID, v: UUID, at: Long, t: Int) = hs.transferPvp(
                HeartStore.PvpRequest("pvp:boot:$v:$t", k, v, at, cooldown, LIMITS, note = RankedMatchRules.environmentalNote(m, "lava"))
            )
            val v1 = player(1, 10)
            val k1 = player(2, 10)
            val first = env(k1, v1, 1_000_000, 1)
            check(
                "#1478 a credited environmental death moves exactly one heart",
                first is HeartStore.PvpResult.Done && first.decision is HeartRules.Decision.Transfer && hs.load(v1)?.hearts == 9 && hs.load(k1)?.hearts == 11
            )
            check("#1478 the same death processed twice moves nothing more", env(k1, v1, 1_000_001, 1) == HeartStore.PvpResult.Duplicate && hs.load(v1)?.hearts == 9)
            val again = env(k1, v1, 1_060_000, 2)
            check("#1478 the shared 30-minute cooldown applies", again is HeartStore.PvpResult.Done && again.decision is HeartRules.Decision.Cooldown && hs.load(v1)?.hearts == 9)
            val openWorld = hs.transferPvp(HeartStore.PvpRequest("pvp:boot:$v1:3", k1, v1, 1_100_000, cooldown, LIMITS)) as HeartStore.PvpResult.Done
            check("#1478 ... and it is the same cooldown as open-world kills", openWorld.decision is HeartRules.Decision.Cooldown)
            val low = player(3, 1)
            val k3 = player(4, 10)
            check(
                "#1478 a 1-heart victim keeps it",
                (env(k3, low, 1_000_000, 4) as HeartStore.PvpResult.Done).decision == HeartRules.Decision.VictimAtMinimum && hs.load(low)?.hearts == 1 && hs.load(k3)?.hearts == 10
            )
            val v5 = player(5, 10)
            val full = player(6, 20)
            check(
                "#1478 a killer at 20 gains nothing",
                (env(full, v5, 1_000_000, 5) as HeartStore.PvpResult.Done).decision == HeartRules.Decision.KillerAtMaximum && hs.load(full)?.hearts == 20 && hs.load(v5)?.hearts == 10
            )
            val audit = db.query("SELECT reason FROM lifesteal_heart_audit WHERE uuid = ? AND operation = 'PVP_LOSS'", v1.toString()) { it.getString("reason") }
            check("#1478 the transfer is audited once, with its match and cause", audit.size == 1 && audit[0].contains("ranked environmental credit, match $m, died to lava"))
            val total = db.queryFirst("SELECT SUM(hearts) AS s FROM lifesteal_hearts") { it.getInt("s") }
            check("#1478 hearts are never created or destroyed", total == 9 + 11 + 1 + 10 + 10 + 20)
        } finally {
            db.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }

    // Summary wording.
    val e = RankedHeartSummary.Entry(foe1, "Ann", victim, "Bob", RankedHeartSummary.Outcome.STOLEN, RankedHeartSummary.Source.ENVIRONMENT)
    check("#1478 the summary names the credited opponent", RankedHeartSummary.line(e).let { it.moved && it.text == "Bob died after a hit from Ann: heart stolen" })
    check("#1478 kill wording is unchanged", RankedHeartSummary.line(e.copy(source = RankedHeartSummary.Source.KILL)).text == "Ann killed Bob: heart stolen")

    // Wiring.
    val heart = source("lifesteal/hearts/LifestealHeartManager.kt")
    val manager = source("lifesteal/ranked/LifestealRankedManager.kt")
    check(
        "#1478 the hits are copied at LOWEST with the match, before the defeat drops them",
        heart.contains("RankedMatchRules.DeathCapture(it.id, Bukkit.getCurrentTick(), plugin.lifestealRankedManager.opponentHitsOn(uuid), System.currentTimeMillis())")
    )
    check("#1478 the death handler uses the ranked rule with the combat-tag window", heart.contains("RankedMatchRules.environmentalKiller(") && heart.contains("windowMillis = plugin.combatManager.tagDurationMillis"))
    check(
        "#1478 the credited opponent must be on the other side of this ACTIVE match and a valid killer",
        heart.contains("plugin.lifestealRankedManager.isCreditableOpponent(rankedMatch, attacker, victim.uniqueId) &&") &&
            manager.contains("match.state == MatchState.ACTIVE && match.roster.sameSide(attacker, victim) == false")
    )
    val onDeath = Regex("""fun onDeath\(event: PlayerDeathEvent\)[\s\S]*?\n    }\n""").find(heart)!!.value
    check("#1478 only after the Lifesteal/active gate (Survival untouched)", onDeath.indexOf("if (!active) return") in 0 until onDeath.indexOf("RankedMatchRules.environmentalKiller("))
    check(
        "#1478 it feeds the one transferPvp with the per-death dedupe key",
        onDeath.indexOf("RankedMatchRules.environmentalKiller(") < onDeath.indexOf("store.transferPvp(request)") &&
            onDeath.contains("dedupeKey = \"pvp:\$bootId:\${victim.uniqueId}:\${Bukkit.getCurrentTick()}\"") &&
            Regex("store\\.transferPvp\\(").findAll(onDeath).count() == 1
    )
    check("#1478 the heart path never touches ranked inventories", !onDeath.contains("restore") && !onDeath.contains("snapshot", ignoreCase = true))
    check("#1478 only landed opponent hits of the ACTIVE fight are recorded", manager.contains("if (match.state != MatchState.ACTIVE || !isMatchFight(attacker, victim)) return"))
}

// ── Issue #1474: anti-farming monitoring (logging only) ──────

private fun monitoring() {
    val defaults = RankedMonitorSettings.DEFAULTS
    // Settings from the bundled lifesteal.yml.
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    @Suppress("UNCHECKED_CAST")
    val map: Map<String, Any?> = Yaml().load(text)
    fun at(m: Map<String, Any?>, path: String): Any? = path.split('.').fold<String, Any?>(m) { node, key -> (node as? Map<*, *>)?.get(key) }
    fun parse(m: Map<String, Any?>) = RankedMonitorConfig.parse { p -> at(m, if (p.isEmpty()) RankedMonitorConfig.BASE else "${RankedMonitorConfig.BASE}.$p") }
    val bundled = parse(map)
    check("#1474 the bundled monitoring settings are the documented defaults", bundled.settings == defaults && bundled.problems.isEmpty())
    check("#1474 a lifesteal.yml without the section uses the defaults (warning only)", parse(emptyMap()).let { it.settings == defaults && it.problems.none { p -> p.severe } })
    @Suppress("UNCHECKED_CAST")
    fun withMonitoring(key: String, value: Any?): Map<String, Any?> {
        val m = map.toMutableMap()
        val ranked = (m["ranked-team-wars"] as Map<String, Any?>).toMutableMap()
        val mon = (ranked["monitoring"] as Map<String, Any?>).toMutableMap()
        mon[key] = value
        ranked["monitoring"] = mon
        m["ranked-team-wars"] = ranked
        return m
    }
    check("#1474 a bad monitoring value only turns monitoring off", parse(withMonitoring("window-hours", "soon")).settings == null)
    check("#1474 a bad monitoring value never invalidates lifesteal.yml", LifestealConfigParser.parse { p -> at(withMonitoring("window-hours", "soon"), p) }.settings != null)
    check("#1474 thresholds are bounded", parse(withMonitoring("repeated-team-matches", -1)).settings == null && parse(withMonitoring("repeated-team-matches", 0)).settings?.repeatedTeamMatches == 0)

    val dir = Files.createTempDirectory("ranked-monitor").toFile()
    try {
        withDb(dir) { s, db ->
            val hs = HeartStore(db)
            hs.createTables()
            val ms = RankedMonitorStore(db)
            ms.createTables()
            s.createArena(readyArena(), 1)
            val a1 = UUID(160L, 1)
            val a2 = UUID(160L, 2)
            val b1 = UUID(161L, 1)
            val b2 = UUID(161L, 2)
            for (u in listOf(a1, a2, b1, b2)) hs.ensure(u, 10, 1)
            val base = 50_000_000L
            var n = 0
            fun playMatch(at: Long, test: Boolean = false, teamB: String = "team-b", forfeit: UUID? = null): String {
                val id = "rtw-%012d".format(++n)
                s.insertMatch(
                    RankedStore.NewMatch(id, "season-1", "duel", "team-a", "Alpha", teamB, "Bravo", 2, test, "queue:x", at),
                    listOf(MatchParticipant(a1, Side.A, "team-a"), MatchParticipant(a2, Side.A, "team-a"), MatchParticipant(b1, Side.B, teamB), MatchParticipant(b2, Side.B, teamB)),
                    listOf(snap(a1), snap(a2), snap(b1), snap(b2)),
                )
                s.moveState(id, MatchState.PREPARING, MatchState.COUNTDOWN, at + 1)
                s.moveState(id, MatchState.COUNTDOWN, MatchState.ACTIVE, at + 2)
                if (forfeit != null) s.defeat(id, forfeit, DefeatReason.DISCONNECT, at + 3)
                for (u in listOf(b1, b2)) if (u != forfeit) s.defeat(id, u, DefeatReason.DEATH, at + 4)
                s.finish(id, Side.A, "team-a", at + 6)
                s.complete(id, at + 7)
                for (u in listOf(a1, a2, b1, b2)) s.markRestored(s.snapshotId(id, u), "test", at + 8)
                return id
            }
            fun facts(id: String, teamB: String = "team-b", forfeited: List<UUID> = emptyList(), transfers: List<Pair<UUID, UUID>> = emptyList()) =
                RankedMonitorRules.MatchFacts(id, "team-a", "Alpha", teamB, "Bravo", listOf(a1, a2), listOf(b1, b2), forfeited, transfers)
            fun findings(f: RankedMonitorRules.MatchFacts, now: Long, settings: RankedMonitorSettings = defaults) =
                RankedMonitorRules.evaluate(f, ms.counts(f, settings, now), settings)
            val heartsBefore = db.queryFirst("SELECT SUM(hearts) AS s FROM lifesteal_hearts") { it.getInt("s") }

            // 21. A normal match: nothing.
            val m1 = playMatch(base)
            check("#1474 a normal match logs nothing", findings(facts(m1), base + 10).isEmpty())
            // 19. Repeated team matchups (4 in 24h by default).
            playMatch(base + 60_000)
            val m3 = playMatch(base + 120_000)
            check("#1474 three matchups stay below the threshold", findings(facts(m3), base + 130_000).none { it.kind == RankedMonitorRules.Kind.REPEATED_TEAM_MATCHUP })
            val m4 = playMatch(base + 180_000)
            val f4 = findings(facts(m4), base + 190_000)
            check(
                "#1474 the fourth matchup in the window is flagged",
                f4.any { it.kind == RankedMonitorRules.Kind.REPEATED_TEAM_MATCHUP && it.subject == RankedMonitorRules.pairKey("team-a", "team-b") && it.reason.contains("played 4 ranked matches") }
            )
            check("#1474 staff test matches don't count as matchups", run { playMatch(base + 200_000, test = true); ms.teamMatchCount("team-a", "team-b", base) == 4 })
            check("#1474 matches outside the window don't count", ms.teamMatchCount("team-a", "team-b", base + 100_000) == 2)
            val teamFinding = f4.first { it.kind == RankedMonitorRules.Kind.REPEATED_TEAM_MATCHUP }
            // 24. No duplicates.
            check("#1474 a finding is recorded once", ms.record(teamFinding, m4, base + 190_000, defaults.windowMillis))
            check("#1474 processing the same match again adds nothing", !ms.record(teamFinding, m4, base + 191_000, defaults.windowMillis) && ms.reviewCount() == 1)
            val m5 = playMatch(base + 240_000)
            val f5 = findings(facts(m5), base + 250_000).first { it.kind == RankedMonitorRules.Kind.REPEATED_TEAM_MATCHUP }
            check("#1474 the same pair in the same window is one entry, not one per match", !ms.record(f5, m5, base + 250_000, defaults.windowMillis) && ms.reviewCount() == 1)
            check("#1474 after the window it can be logged again", ms.record(f5, m5, base + 250_000 + defaults.windowMillis, defaults.windowMillis) && ms.reviewCount() == 2)
            // Player pairs across teams (coordinated rosters under a new team id). 5 non-test matches so far + this one = 6.
            val mx = playMatch(base + 300_000, teamB = "team-c")
            val fx = findings(facts(mx, teamB = "team-c"), base + 310_000)
            check(
                "#1474 the same opponents under a different team are tracked per player",
                fx.any { it.kind == RankedMonitorRules.Kind.REPEATED_PLAYER_MATCHUP && it.players.toSet() == setOf(a1, b1) && it.reason.contains("in 6 ranked matches") }
            )
            check("#1474 a new team pair isn't a team matchup finding yet", fx.none { it.kind == RankedMonitorRules.Kind.REPEATED_TEAM_MATCHUP })
            // 20. Shared connection: a review entry, nothing else.
            db.createTable("CREATE TABLE IF NOT EXISTS player_connections (uuid TEXT NOT NULL, connection_hash TEXT NOT NULL, last_seen INTEGER NOT NULL, PRIMARY KEY (uuid, connection_hash))")
            db.execute("INSERT INTO player_connections (uuid, connection_hash, last_seen) VALUES (?, 'hash-xyz', 1), (?, 'hash-xyz', 1)", a2.toString(), b2.toString())
            val ms6 = playMatch(base + 400_000)
            val shared = findings(facts(ms6, transfers = listOf(a2 to b2)), base + 410_000).filter { it.kind == RankedMonitorRules.Kind.SHARED_CONNECTION }
            check("#1474 shared-connection opponents get one review entry", shared.size == 1 && shared[0].players.toSet() == setOf(a2, b2) && shared[0].reason.contains("not proof") && shared[0].reason.contains("a heart moved"))
            check(
                "#1474 the shared-connection entry is stored without any connection data",
                ms.record(shared[0], ms6, base + 410_000, defaults.windowMillis) &&
                    ms.reviews(a2).single { it.kind == "SHARED_CONNECTION" }.let { r -> r.kind == "SHARED_CONNECTION" && !r.reason.contains("hash") && !r.subject.contains("hash") && r.matchId == ms6 }
            )
            check("#1474 shared-connection checking can be turned off", findings(facts(ms6), base + 410_000, defaults.copy(sharedConnections = false)).none { it.kind == RankedMonitorRules.Kind.SHARED_CONNECTION })
            // Repeated heart transfers (ranked + open world) and repeated forfeits.
            for (i in 0 until 3) hs.transferPvp(HeartStore.PvpRequest("pvp:boot:$b1:${9000 + i}", a1, b1, base + 500_000 + i * 31 * 60_000L, 30 * 60_000L, LIMITS))
            val transfersAt = base + 500_000 + 3 * 31 * 60_000L
            val mt = playMatch(transfersAt)
            check(
                "#1474 repeated transfers between one pair are flagged",
                findings(facts(mt, transfers = listOf(a1 to b1)), transfersAt + 10).any { it.kind == RankedMonitorRules.Kind.REPEATED_HEART_TRANSFERS && it.subject == RankedMonitorRules.directedKey(a1, b1) }
            )
            check("#1474 transfers in the other direction are counted separately", ms.transferCount(b1, a1, base) == 0 && ms.transferCount(a1, b1, base) == 3)
            var lastForfeit = ""
            for (i in 0 until 3) lastForfeit = playMatch(transfersAt + 60_000L * (i + 1), forfeit = b2)
            check(
                "#1474 repeated forfeits are flagged",
                findings(facts(lastForfeit, forfeited = listOf(b2)), transfersAt + 300_000).any { it.kind == RankedMonitorRules.Kind.REPEATED_FORFEITS && it.players == listOf(b2) }
            )
            check("#1474 monitoring off = no findings", findings(facts(lastForfeit, forfeited = listOf(b2)), transfersAt + 300_000, defaults.copy(enabled = false)).isEmpty())
            // 22. Hearts untouched by counting and recording (the 3 transfers above were made by the heart store itself).
            val heartsAfter = db.queryFirst("SELECT SUM(hearts) AS s FROM lifesteal_hearts") { it.getInt("s") }
            check("#1474 monitoring never creates or destroys hearts", heartsAfter == heartsBefore && hs.load(a1)?.hearts == 13 && hs.load(b1)?.hearts == 7)
            check("#1474 no punishment rows are written", db.queryFirst("SELECT 1 AS hit FROM sqlite_master WHERE type = 'table' AND name = 'punishments'") { true } == null)
        }
    } finally {
        dir.deleteRecursively()
    }

    // 23 + wiring: logging only.
    val monitorDir = File("src/main/kotlin/com/liam/joshymc/lifesteal/ranked/monitor")
    val monitorSources = monitorDir.listFiles()!!.filter { it.name.endsWith(".kt") }.map { it.readText() }
    check(
        "#1474 monitoring never changes Elo, hearts, matches or punishments",
        monitorSources.isNotEmpty() && monitorSources.none {
            it.contains("lifesteal_ranked_ratings") || it.contains("transferPvp") || it.contains("adminChange") || it.contains("punishmentManager") ||
                Regex("""UPDATE lifesteal_|DELETE FROM lifesteal_|INSERT INTO lifesteal_(?!ranked_reviews)""").containsMatchIn(it)
        }
    )
    check("#1474 monitoring never reads an address", monitorSources.none { it.contains("getAddress") || it.contains("connection_hash") || it.contains(".address") })
    val manager = source("lifesteal/ranked/LifestealRankedManager.kt")
    val farming = source("lifesteal/ranked/monitor/RankedFarmingMonitor.kt")
    check(
        "#1474 the check runs after the result is recorded, never at shutdown",
        Regex("""complete\(match, shutdown = false\)\s*\n\s*monitorEnded\(match\)""").containsMatchIn(manager) && manager.contains("if (fought && !shutdown) monitorEnded(match)")
    )
    check("#1474 the check runs off the main thread and failures stay logged", farming.contains("plugin.databaseManager.async(\"ranked monitoring") && farming.contains("catch (e: Exception)"))
    check("#1474 a review entry also reaches the /admin logs", farming.contains("plugin.adminManager.logAction(") && farming.contains("RANKED_REVIEW_"))
    check("#1474 staff can read the reviews", source("lifesteal/ranked/LifestealRankedCommand.kt").contains("\"reviews\" -> reviews(sender, a.getOrNull(0))"))
    check("#1474 players are never told about reviews", !farming.contains("commsManager"))
}

// ── Wiring (source) ──────────────────────────────────────────

private fun wiring() {
    val manager = source("lifesteal/ranked/LifestealRankedManager.kt")
    val listener = source("lifesteal/ranked/LifestealRankedListener.kt")
    val command = source("lifesteal/ranked/LifestealRankedCommand.kt")
    val rankedSources = listOf(manager, listener, command, source("lifesteal/ranked/RankedStore.kt"), source("lifesteal/ranked/RankedMatchRules.kt"), source("lifesteal/ranked/RankedArena.kt"))
    val listeners = source("manager/ListenerManager.kt")
    val main = source("Joshymc.kt")
    val combatListener = source("listener/CombatListener.kt")
    val combatManager = source("manager/CombatManager.kt")
    val guard = source("teleport/CombatTeleportGuard.kt")
    val transfer = source("network/transfer/TransferManager.kt")
    val heartManager = source("lifesteal/hearts/LifestealHeartManager.kt")
    val heartListener = source("lifesteal/hearts/LifestealHeartListener.kt")
    val fulfillment = source("manager/PurchaseFulfillmentManager.kt")
    val executor = source("command/ServerTransferCommand.kt")
    val plugin = resourceText("plugin.yml")

    // Survival isolation.
    check("listener only registered where Lifesteal is hosted", Regex("""if \(plugin\.lifestealRankedManager\.hosted\) \{\s*\n\s*pm\.registerEvents\(com\.liam\.joshymc\.lifesteal\.ranked\.LifestealRankedListener""").containsMatchIn(listeners))
    check("manager does nothing (no tables) where Lifesteal isn't hosted", Regex("""fun refresh\(\) \{\s*\n\s*if \(!hosted\) \{\s*\n\s*settings = null\s*\n\s*return""").containsMatchIn(manager))
    check("every action refuses off Lifesteal", manager.contains("!hosted -> \"Ranked Team Wars only exist on the Lifesteal server.\""))
    check("arenas must be in Lifesteal worlds", manager.contains("isn't a Lifesteal world") && manager.contains("if (!isLifesteal(world)) return ArenaResult.Refused"))
    check("participants must be in a Lifesteal world", manager.contains("inLifesteal = isLifesteal(player.world)"))
    check("Survival teams never used (Lifesteal team manager only)", rankedSources.none { it.contains("plugin.teamManager") })
    check("/lifesteal admin arena|ranked routed in the existing executor", executor.contains("ranked.execute(sender, args.drop(1))") && executor.contains("progression.admin(sender, args.drop(1))"))
    check("ranked commands only exist on the /lifesteal executor of the Lifesteal destination", executor.contains("private val ranked = if (destination == TransferDestination.LIFESTEAL) LifestealRankedCommand(plugin) else null"))
    // Staff tools stay subcommands of /lifesteal; /ranked (issue #1363) is the player queue command.
    check("staff tools stay under /lifesteal admin", Regex("""(?m)^  ranked:\s*\n(\s{4}.*\n)*?\s{4}permission: joshymc\.lifesteal\.ranked\.use""").containsMatchIn(plugin))
    check("admin permission op only", Regex("""joshymc\.lifesteal\.ranked\.admin:\s*\n\s*description: [^\n]+\n\s*default: op""").containsMatchIn(plugin))
    check("spectate permission op only", Regex("""joshymc\.lifesteal\.ranked\.spectate:\s*\n\s*description: [^\n]+\n\s*default: op""").containsMatchIn(plugin))

    // Lifecycle.
    check("refresh on startup", main.contains("lifestealRankedManager.refresh()"))
    check("refresh on reload", main.contains("safe(\"lifestealRankedManager.refresh\") { lifestealRankedManager.refresh() }"))
    check("shutdown step registered", main.contains("shutdown.step(\"lifestealRankedManager.stop\", { ::lifestealRankedManager.isInitialized })"))
    check("shutdown restores before the database closes", main.indexOf("\"lifestealRankedManager.stop\"") < main.indexOf("\"databaseManager.shutdown\""))
    check("shutdown restores before combat/teams/hearts stop", main.indexOf("\"lifestealRankedManager.stop\"") < main.indexOf("\"lifestealHeartManager.stop\"") && main.indexOf("\"lifestealRankedManager.stop\"") < main.indexOf("\"combatManager.stop\""))
    check("crash recovery once per boot", manager.contains("if (!recoveredThisBoot) {") && manager.contains("store.recoverInterrupted("))
    check("match tasks are tracked and cancelled", manager.contains("match.track(") && manager.contains("fun cancelTasks()"))
    check("no stale Player references in the match model", !Regex("""val \w+: (Mutable)?(List|Set|Map)<[^>]*Player[,>]""").containsMatchIn(manager) && !manager.contains("HashMap<UUID, Player>"))

    // Inventory: snapshot before anything, set (not add) on restore, exactly once.
    check("snapshot is the whole inventory (storage, armor, off-hand)", manager.contains("inventory.contents.map"))
    check("snapshot uses Paper's lossless item serialization", manager.contains("serializeAsBytes()") && manager.contains("ItemStack.deserializeBytes("))
    check("snapshot stored before any teleport", manager.indexOf("store.insertMatch(") in 0 until manager.indexOf("prepare(match)\n        return CreateResult.Started"))
    check("restore sets the inventory to the snapshot", manager.contains("inventory.contents = contents"))
    check("restore discards cursor and crafting grid (no extraction)", manager.contains("player.setItemOnCursor(null)") && manager.contains("top.clear()"))
    check("restore saves the player then marks it restored", manager.indexOf("player.saveData()") < manager.indexOf("store.markRestored("))
    check("restore never touches hearts", !manager.contains("adminChange(") && !manager.contains("HeartStore(") && !listener.contains("lifestealHeartManager"))
    check("no kits are handed out", rankedSources.none { it.contains("applyKit") || it.contains("EventKit") || it.contains("kitManager") })
    check("deaths drop nothing and no XP (gear comes back from the snapshot)", listener.contains("event.drops.clear()") && listener.contains("event.droppedExp = 0") && listener.contains("event.keepInventory = false"))
    check("death handler runs at HIGHEST, before the heart service at MONITOR", Regex("""HIGHEST, ignoreCancelled = true\)\s*\n\s*fun onDeath\(event: PlayerDeathEvent\)""").containsMatchIn(listener) && Regex("""MONITOR, ignoreCancelled = true\)\s*\n\s*fun onDeath""").containsMatchIn(heartListener))
    check("heart service unchanged: kills still go through transferPvp", heartManager.contains("store.transferPvp(request)"))
    check("respawn goes to the return location, then restores", listener.contains("event.respawnLocation = it") && listener.contains("ranked.restoreIfPending(event.player, \"respawned\")"))
    check("rejoin restores", manager.contains("restoreIfPending(player, \"rejoined\")"))
    check("item drops, pickups and containers blocked while held", listener.contains("fun onDrop(event: PlayerDropItemEvent)") && listener.contains("fun onPickup(event: EntityPickupItemEvent)") && listener.contains("fun onOpen(event: InventoryOpenEvent)"))
    check("Heart items can't be redeemed or withdrawn in a match", heartManager.contains("plugin.lifestealRankedManager.holds(player) -> HeartMessages.RANKED_MATCH"))
    for (v in listOf("CreditVoucherListener", "RankVoucherListener", "PhysicalVoucherListener", "ChatTagVoucherListener", "FlyVoucherListener")) {
        check("$v refuses inside a ranked match", source("listener/$v.kt").contains("if (plugin.lifestealRankedManager.holds(event.player)) return"))
    }
    // Issue #1377: a projectile fired in a match is never a second copy of the snapshot's item.
    val pickup = RankedMatchRules::projectilePickup
    check("#1377: ordinary arrows are untouched", pickup(null, null, false) == RankedMatchRules.ProjectilePickup.ALLOW && pickup(null, "rtw-000000000001", true) == RankedMatchRules.ProjectilePickup.ALLOW)
    check("#1377: players of the same match may pick their arrows up", pickup("rtw-000000000001", "rtw-000000000001", true) == RankedMatchRules.ProjectilePickup.ALLOW)
    check("#1377: an outsider can't take an arrow/trident shot over the boundary", pickup("rtw-000000000001", null, true) == RankedMatchRules.ProjectilePickup.DENY)
    check("#1377: a player of another match can't take it either", pickup("rtw-000000000001", "rtw-000000000002", true) == RankedMatchRules.ProjectilePickup.DENY)
    check("#1377: after the match it is removed (the owner got it back from the snapshot)", pickup("rtw-000000000001", null, false) == RankedMatchRules.ProjectilePickup.REMOVE && pickup("rtw-000000000001", "rtw-000000000001", false) == RankedMatchRules.ProjectilePickup.REMOVE)
    check("#1377: arrows/tridents fired while held are tagged with their match", listener.contains("fun onLaunch(event: ProjectileLaunchEvent)") && listener.contains("arrow.persistentDataContainer.set(firedInKey, PersistentDataType.STRING, match.id)"))
    check("#1377: arrow pickups go through the rule", listener.contains("fun onPickupArrow(event: PlayerPickupArrowEvent)") && listener.contains("RankedMatchRules.projectilePickup(") && listener.contains("arrow.remove()"))
    check("store deliveries wait until the match is over", fulfillment.contains("if (plugin.lifestealRankedManager.holds(player)) return"))

    // Combat, teleports, transfers.
    check("ranked fights never combat-tag (no combat-log NPC dropping real gear)", combatListener.contains("if (plugin.lifestealRankedManager.isMatchFight(attacker, victim)) return"))
    check("ranked fighters pass the PvP toggle", combatManager.contains("if (plugin.lifestealRankedManager.inActiveFight(player)) return true"))
    check("validation refuses combat-tagged players", manager.contains("combatTagged = plugin.combatManager.isTagged(player)"))
    check("shared teleport guard blocks held players (team home, GUIs, warmups)", guard.contains("if (plugin.lifestealRankedManager.holds(player)) {"))
    check("every teleport out of the arena is refused (all causes)", listener.contains("fun onTeleport(event: PlayerTeleportEvent)") && listener.contains("if (inside(match.arena, to)) return"))
    check("commands limited while held", listener.contains("RankedMatchRules.commandAllowed(event.message)"))
    check("transfers refused while held or still waiting for a restore", transfer.contains("list += TransferBlocker.RANKED_MATCH") && TransferBlocker.entries.contains(TransferBlocker.RANKED_MATCH))
    check("validation refuses players switching servers", manager.contains("transferring = safe { plugin.transferManager.isTransferPending(uuid) }"))
    check("boundary: walking out pushed back, walking in refused", listener.contains("if (inside(match.arena, from) && !inside(match.arena, to))") && listener.contains("!inside(it.arena, from) && inside(it.arena, to)"))
    check("no block changes in arenas (place, break, buckets, explosions)", listener.contains("fun onPlace(event: BlockPlaceEvent)") && listener.contains("fun onBreak(event: BlockBreakEvent)") && listener.contains("fun onEntityExplode"))
    check("cleanup removes only loose leftovers, never blocks", manager.contains("e is Item || e is AbstractArrow || e is EnderCrystal || e is ExperienceOrb") && !manager.contains("setType(") && !manager.contains(".type = "))
    // Issue #1465: the only heart consequence of a disconnect is the normal transferPvp; never an admin change.
    check("no admin heart changes for disconnects/boundaries", !manager.contains("HeartRules.AdminOp") && !manager.contains("adminChange("))
    check("spectators can't affect combat", listener.contains("if (ranked.isSpectator(victim.uniqueId)) return true") && listener.contains("ranked.isSpectator(attacker.uniqueId)"))

    // Scope limits of this issue.
    check("no Elo or rewards", rankedSources.none { Regex("""(fun|val|var|class) (\w*(Elo|Reward)|(elo|reward))""").containsMatchIn(it) })
    // Issue #1363: matchmaking lives in lifesteal/ranked/queue/ (scripts/LifestealRankedQueueCheck.kt); it registers the ranked system.
    check("the match manager itself doesn't register the ranked system (the queue does)", !manager.contains("rankedTeamWarsSystem ="))
    check("no packet-level or Geyser code", rankedSources.none { it.contains("Geyser") || it.contains("packet") || it.contains("Floodgate") })
    check("database work during combat is queued, not synchronous", manager.contains("plugin.databaseManager.async(\"ranked defeat"))
}
