// Checks the Ranked Team Wars match-integrity fixes (issue #1498, audit findings F-2, F-4, F-5):
// the Bukkit-free rules in RankedIntegrity, the result/Elo decision against a real SQLite
// database (fresh temp directory, deleted afterwards), and the wiring that puts each check at
// its boundary (entry, countdown, activation, the fight, the result, staff commands, delegated
// commands). The Gradle build has no test source set, so it runs through an init script, from
// the repo root:
//
//   ./gradlew -I scripts/ranked-integrity-check.init.gradle.kts rankedIntegrityCheck
//
// Exits non-zero on the first failed check. Live-server steps: docs/lifesteal-ranked-beta.md, section K.

import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.Mode
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.Reason
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.State
import com.liam.joshymc.lifesteal.ranked.Box
import com.liam.joshymc.lifesteal.ranked.DefeatReason
import com.liam.joshymc.lifesteal.ranked.MatchKind
import com.liam.joshymc.lifesteal.ranked.MatchParticipant
import com.liam.joshymc.lifesteal.ranked.MatchRoster
import com.liam.joshymc.lifesteal.ranked.MatchState
import com.liam.joshymc.lifesteal.ranked.ParticipantStatus
import com.liam.joshymc.lifesteal.ranked.Point
import com.liam.joshymc.lifesteal.ranked.RankedArena
import com.liam.joshymc.lifesteal.ranked.RankedIntegrity
import com.liam.joshymc.lifesteal.ranked.RankedIntegrity.Resolution
import com.liam.joshymc.lifesteal.ranked.RankedIntegrity.StaffAction
import com.liam.joshymc.lifesteal.ranked.RankedMatchRules
import com.liam.joshymc.lifesteal.ranked.RankedStore
import com.liam.joshymc.lifesteal.ranked.Side
import com.liam.joshymc.lifesteal.ranked.rating.RatingRules
import com.liam.joshymc.manager.DatabaseManager
import java.io.File
import java.nio.file.Files
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

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()
private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }

private val LIMITS = 1..20

/** A participant who passes every other entry rule, with [state] as their live competitive state. */
private fun facts(state: State, uuid: UUID = UUID(1L, 1L)): RankedMatchRules.ParticipantFacts {
    val reasons = CompetitiveEligibility.problems(state)
    return RankedMatchRules.ParticipantFacts(
        uuid = uuid, name = "P", side = Side.A, member = true, online = true, inLifesteal = true,
        sessionLoaded = true, hearts = 10, inOtherMatch = false, pendingRestore = false, transferring = false,
        combatTagged = false, dead = false, survivalMode = state.mode == Mode.SURVIVAL,
        restricted = RankedIntegrity.eligibilityProblem(reasons),
    )
}

fun main() {
    entry()
    midMatch()
    staffActions()
    delegated()
    results()
    interventions()
    store()
    wiring()
    println("Ranked integrity checks passed ($checks checks).")
}

// ── 1-8: entry ──────────────────────────────────────────────

private fun entry() {
    val ok = facts(State(Mode.SURVIVAL))
    check("6. an eligible Survival player can queue", RankedMatchRules.participantProblem(ok, LIMITS) == null)
    val god = RankedMatchRules.participantProblem(facts(State(Mode.SURVIVAL, godMode = true)), LIMITS)
    check("1. God Mode blocks queue entry, with a clear reason", god != null && god.contains("God Mode") && god.contains("/god"))
    val vanish = RankedMatchRules.participantProblem(facts(State(Mode.SURVIVAL, vanished = true)), LIMITS)
    check("2. Vanish blocks queue entry", vanish != null && vanish.contains("vanish"))
    check("3. Creative blocks queue entry", RankedMatchRules.participantProblem(facts(State(Mode.CREATIVE)), LIMITS) != null)
    check("4. Spectator blocks queue entry", RankedMatchRules.participantProblem(facts(State(Mode.SPECTATOR)), LIMITS) != null)
    val mod = RankedMatchRules.participantProblem(facts(State(Mode.SURVIVAL, modMode = true)), LIMITS)
    check("5. Mod Mode blocks queue entry", mod != null && mod.contains("Mod Mode"))
    check("over-limit worn gear blocks queue entry (the #1487 rule)", RankedMatchRules.participantProblem(facts(State(Mode.SURVIVAL, unsafeEquipment = true)), LIMITS) != null)
    check("Creative/Spectator explain themselves in the integrity message too",
        RankedIntegrity.eligibilityProblem(setOf(Reason.CREATIVE))!!.contains("Creative") && RankedIntegrity.eligibilityProblem(setOf(Reason.SPECTATOR))!!.contains("Spectator"))
    check("no reasons = no integrity problem", RankedIntegrity.eligibilityProblem(emptySet()) == null)
    check("several reasons give one stable message (lowest reason first)",
        RankedIntegrity.eligibilityProblem(setOf(Reason.VANISHED, Reason.GOD_MODE)) == RankedIntegrity.eligibilityProblem(setOf(Reason.GOD_MODE)))
    check("frozen players are still refused (existing rule kept)", RankedMatchRules.participantProblem(ok.copy(restricted = "is frozen"), LIMITS) == "is frozen")

    val queue = source("lifesteal/ranked/queue/LifestealRankedQueueManager.kt")
    val manager = source("lifesteal/ranked/LifestealRankedManager.kt")
    check("entry facts use the #1487 state rule", manager.contains("else -> RankedIntegrity.eligibilityProblem(competitiveReasons(player))") &&
        manager.contains("plugin.competitiveEligibility.currentReasons(player)"))
    check("a tracker failure refuses instead of letting players in", Regex("""catch \(_: Exception\) \{\s*setOf\(CompetitiveEligibility\.Reason\.MOD_MODE\)""").containsMatchIn(manager))
    check("queue registration, challenges and acceptance all use participantProblem", Regex("""ranked\.participantProblem\(""").findAll(queue).count() >= 4)
    check("7. match acceptance re-checks eligibility", Regex("""private fun acceptMatch[\s\S]{0,700}ranked\.participantProblem\(player, team\)""").containsMatchIn(queue))
    check("match creation validates every participant (facts -> validate)", manager.contains("val problems = RankedMatchRules.validate(request)"))
    check("preparation re-checks before the countdown", Regex("""checkIntegrity\(match, "preparation"\)\) return@Runnable\s*\n\s*startCountdown\(match\)""").containsMatchIn(manager))
    check("every countdown second re-checks", manager.contains("if (!checkIntegrity(match, \"countdown\")) return@Runnable"))
    check("8. activation re-checks immediately before ACTIVE",
        Regex("""private fun begin\(match: Match\) \{[\s\S]{0,120}checkIntegrity\(match, "activation"\)\) return\s*\n\s*if \(!move\(match, MatchState\.ACTIVE\)\)""").containsMatchIn(manager))
}

// ── 9-12: mid-match ─────────────────────────────────────────

private fun midMatch() {
    check("11/12. held participants can't leave Survival", !RankedIntegrity.gameModeChangeAllowed(held = true, toSurvival = false))
    check("held participants may be put back into Survival", RankedIntegrity.gameModeChangeAllowed(held = true, toSurvival = true))
    check("game mode changes outside matches are untouched", RankedIntegrity.gameModeChangeAllowed(held = false, toSurvival = false))
    check("flight never comes back while held", !RankedIntegrity.flightAllowed(true) && RankedIntegrity.flightAllowed(false))
    check("9. God Mode can't be switched on by a participant", RankedIntegrity.staffActionRefusal(StaffAction.GOD, "P", held = true, pendingRestore = false, self = true) != null)
    check("10. Vanish can't be switched on by a participant", RankedIntegrity.staffActionRefusal(StaffAction.VANISH, "P", true, false, true) != null)
    check("Mod Mode can't be entered during a match", RankedIntegrity.staffActionRefusal(StaffAction.MOD_MODE, "P", true, false, true) != null)

    check("detected before the fight: the match is cancelled (nothing at stake)",
        listOf(MatchState.CREATED, MatchState.VALIDATING, MatchState.PREPARING, MatchState.COUNTDOWN).all { RankedIntegrity.resolve(it, true) == Resolution.CANCEL_MATCH })
    check("detected in the fight: the player forfeits", RankedIntegrity.resolve(MatchState.ACTIVE, true) == Resolution.FORFEIT)
    check("already out or match over: nothing happens",
        RankedIntegrity.resolve(MatchState.ACTIVE, false) == Resolution.NONE && RankedIntegrity.resolve(MatchState.FINISHING, true) == Resolution.NONE &&
            RankedIntegrity.resolve(MatchState.COMPLETED, true) == Resolution.NONE && RankedIntegrity.resolve(null, true) == Resolution.NONE)
    check("an ineligible defeat is a forfeit (like a counted disconnect)", DefeatReason.INELIGIBLE.forfeit && DefeatReason.DISCONNECT.forfeit &&
        !DefeatReason.DEATH.forfeit && !DefeatReason.REMOVED.forfeit && !DefeatReason.DISCONNECT_EXEMPT.forfeit)
}

// ── 13-18: staff actions on participants ────────────────────

private fun staffActions() {
    for (a in StaffAction.entries) {
        val r = a.let { RankedIntegrity.staffActionRefusal(it, "Target", held = true, pendingRestore = false, self = false) }
        check("${a.id}: refused on a held participant, pointing at cancel/remove", r != null && r.contains("Target") && r.contains("/lifesteal admin ranked cancel|remove"))
        check("${a.id}: normal outside ranked", RankedIntegrity.staffActionRefusal(a, "Target", held = false, pendingRestore = false, self = false) == null)
    }
    check("teleports and /sudo wait for a pending inventory restore",
        listOf(StaffAction.TELEPORT, StaffAction.TELEPORT_HERE, StaffAction.SUDO).all { RankedIntegrity.staffActionRefusal(it, "T", false, true, false) != null })
    check("heal/feed/god don't block a player waiting for a restore (nothing at stake)",
        listOf(StaffAction.HEAL, StaffAction.FEED, StaffAction.GOD).all { RankedIntegrity.staffActionRefusal(it, "T", false, true, false) == null })

    val ess = source("command/EssentialsCommands.kt")
    fun guarded(action: String) = ess.contains("refusesStaffAction(sender, target, com.liam.joshymc.lifesteal.ranked.RankedIntegrity.StaffAction.$action)")
    check("13. /god <participant> is refused server side", guarded("GOD"))
    check("14. /heal <participant> is refused server side", guarded("HEAL"))
    check("15. /feed <participant> is refused server side", guarded("FEED"))
    check("16. /gamemode <mode> <participant> is refused server side", guarded("GAMEMODE"))
    check("17. /fly <participant> is refused server side", guarded("FLY"))
    check("18. /tp <participant>, /tp <a> <b> and /tphere <participant> are refused", guarded("TELEPORT") && guarded("TELEPORT_HERE") &&
        ess.contains("refusesStaffAction(sender, p1, com.liam.joshymc.lifesteal.ranked.RankedIntegrity.StaffAction.TELEPORT)") &&
        ess.contains("refusesStaffAction(sender, p2, com.liam.joshymc.lifesteal.ranked.RankedIntegrity.StaffAction.TELEPORT)"))
    check("/smite <participant> is refused", guarded("SMITE"))
    check("/vanish <participant> is refused (only turning it on)", Regex("""\} else \{\s*\n\s*// Issue #1498[^\n]*\n\s*if \(plugin\.lifestealRankedManager\.refusesStaffAction\(sender, target, [\w.]+\.VANISH\)\) return true\s*\n\s*vanish\(target\)""")
        .containsMatchIn(source("command/VanishCommand.kt")))
    val mod = source("manager/ModModeManager.kt")
    check("Mod Mode refuses before touching the inventory", Regex("""fun enable\(player: Player\) \{[\s\S]{0,400}StaffAction\.MOD_MODE\)\) return[\s\S]*saveBackup\(player\)""").containsMatchIn(mod))
    check("freeze is refused on participants (/admin freeze, the /admin panel, the Mod Mode tool)",
        source("command/AdminCommand.kt").contains("StaffAction.FREEZE") && source("manager/AdminManager.kt").contains("StaffAction.FREEZE") && mod.contains("StaffAction.FREEZE"))
    val manager = source("lifesteal/ranked/LifestealRankedManager.kt")
    check("blocked staff actions are written to admin_logs (AdminAudit), throttled", manager.contains("\"RANKED_STAFF_ACTION_BLOCKED\"") && manager.contains("integrityThrottle.allow(RankedIntegrity.throttleKey(actor"))
    val listener = source("lifesteal/ranked/LifestealRankedListener.kt")
    check("any game mode change of a held player is cancelled, whoever caused it", listener.contains("fun onGameModeChange(event: PlayerGameModeChangeEvent)") &&
        listener.contains("RankedIntegrity.gameModeChangeAllowed(held, event.newGameMode == GameMode.SURVIVAL)"))
    check("flight toggles of held players are cancelled", listener.contains("fun onToggleFlight(event: PlayerToggleFlightEvent)"))
}

// ── 19-22: delegated commands ───────────────────────────────

private fun delegated() {
    check("19. /sudo of a non-chat command is refused for a held player", !RankedIntegrity.delegatedAllowed(true, "spawn", "spawn") && !RankedIntegrity.delegatedAllowed(true, "god", "god"))
    check("private messages still run as a held player", RankedIntegrity.delegatedAllowed(true, "msg Bob hi", "msg") && RankedIntegrity.delegatedAllowed(true, "r hi", "reply"))
    check("players outside a match aren't affected", RankedIntegrity.delegatedAllowed(false, "spawn", "spawn"))
    check("22. an allowed alias that resolves to another command is refused", !RankedMatchRules.commandAllowed("/m hi", "mail") && !RankedMatchRules.commandAllowed("/w x", "warp"))
    check("22. aliases of /msg are still allowed", RankedMatchRules.commandAllowed("/tell Bob hi", "msg") && RankedMatchRules.commandAllowed("/whisper Bob hi", "msg"))
    check("22. namespaced names are resolved too", RankedMatchRules.commandAllowed("/minecraft:tell Bob hi", "tell") && RankedMatchRules.commandAllowed("/joshymc:msg Bob hi", "msg") &&
        !RankedMatchRules.commandAllowed("/joshymc:spawn", "spawn") && !RankedMatchRules.commandAllowed("/essentials:msg Bob", "mail"))
    check("unknown commands keep the label rule", RankedMatchRules.commandAllowed("/msg Bob hi") && !RankedMatchRules.commandAllowed("/spawn"))

    val dc = source("staff/DelegatedCommands.kt")
    check("the delegated boundary checks the ranked allowlist first", Regex("""fun allowRun\([^)]*\): Boolean \{\s*\n\s*if \(rankedProblem\(plugin, player, commandLine, source\) != null\)""").containsMatchIn(dc))
    check("it resolves aliases through the command map", dc.contains("RankedIntegrity.delegatedAllowed(held, commandLine, canonicalName(commandLine))") && dc.contains("Bukkit.getCommandMap().getCommand(typed)?.name"))
    check("blocked delegated commands are logged", dc.contains("ranked.logBlockedDelegated(player, source, ") && source("lifesteal/ranked/LifestealRankedManager.kt").contains("\"RANKED_DELEGATED_COMMAND_BLOCKED\""))
    val ess = source("command/EssentialsCommands.kt")
    check("19. /sudo checks it for every sender (console too), before running", Regex("""DelegatedCommands\.rankedProblem\(plugin, target, cmd, [^\n]*\n[\s\S]{0,400}target\.performCommand\(cmd\)""").containsMatchIn(ess) &&
        !Regex("""if \(sender is Player\) \{[^}]*rankedProblem""").containsMatchIn(ess))
    check("20. NPC commands go through allowRun", source("manager/NPCManager.kt").contains("DelegatedCommands.allowRun(plugin, player, resolved"))
    check("21. portal commands go through allowRun", source("manager/PortalManager.kt").contains("DelegatedCommands.allowRun(plugin, player, cmd"))
    check("typed commands use the same alias resolution", source("lifesteal/ranked/LifestealRankedListener.kt").contains("commandAllowed(event.message, com.liam.joshymc.staff.DelegatedCommands.canonicalName(event.message))"))
}

// ── 23-24, 27: result and Elo ───────────────────────────────

private val A1 = UUID(10L, 1)
private val A2 = UUID(10L, 2)
private val B1 = UUID(20L, 1)
private val B2 = UUID(20L, 2)

private fun record(winner: Side = Side.A, ineligible: Set<Side> = emptySet(), kind: MatchKind = MatchKind.PUBLIC) =
    RankedStore.MatchRecord("rtw-0000000000aa", "season-1", "duel", "ta", "A", "tb", "B", 2, kind == MatchKind.TEST, MatchState.COMPLETED,
        "queue:x", 0, 1, 2, winner, if (winner == Side.A) "ta" else "tb", "WIN", null, "COMPLETE", kind, null, ineligibleSides = ineligible)

private fun parts(aReason: DefeatReason? = null, bReason: DefeatReason? = DefeatReason.DEATH) = listOf(
    RankedStore.ParticipantRecord(A1, Side.A, "ta", if (aReason == null) ParticipantStatus.ACTIVE else ParticipantStatus.DEFEATED, aReason, false),
    RankedStore.ParticipantRecord(A2, Side.A, "ta", ParticipantStatus.ACTIVE, null, false),
    RankedStore.ParticipantRecord(B1, Side.B, "tb", ParticipantStatus.DEFEATED, bReason, false),
    RankedStore.ParticipantRecord(B2, Side.B, "tb", ParticipantStatus.DEFEATED, DefeatReason.DEATH, false),
)

private fun decide(r: RankedStore.MatchRecord, p: List<RankedStore.ParticipantRecord>) = RatingRules.decide(r, p, rateChallenges = false, sameOpponentLimit = 0, ratedBetweenPair = 0)

private fun results() {
    check("24. a clean match is rated normally", decide(record(), parts()) is RatingRules.Verdict.Rated)
    val tainted = decide(record(ineligible = setOf(Side.A)), parts())
    check("23. a win by a side that used a restricted ability is never rated", tainted is RatingRules.Verdict.Unrated && tainted.reason == RankedIntegrity.UNRATED_REASON)
    check("23. a winner taken out for it also blocks the rating", decide(record(), parts(aReason = DefeatReason.INELIGIBLE)) is RatingRules.Verdict.Unrated)
    val lost = decide(record(ineligible = setOf(Side.B)), parts(bReason = DefeatReason.INELIGIBLE))
    check("27. going ineligible can't escape a loss: the loss stays rated", lost is RatingRules.Verdict.Rated && lost.winner == Side.A && lost.sideB.containsAll(listOf(B1, B2)))
    check("both sides ineligible: the win is unrated", decide(record(ineligible = setOf(Side.A, Side.B)), parts()) is RatingRules.Verdict.Unrated)
    check("TEST matches stay unrated for their own reason", (decide(record(kind = MatchKind.TEST, ineligible = setOf(Side.A)), parts()) as? RatingRules.Verdict.Unrated)?.reason == "staff test matches are never rated")
    check("ratingBlocked only looks at the winner", !RankedIntegrity.ratingBlocked(Side.A, setOf(Side.B), listOf(null, DefeatReason.DEATH)) &&
        RankedIntegrity.ratingBlocked(Side.B, setOf(Side.B), emptyList()))
    check("sides encode/decode", RankedIntegrity.encodeSides(setOf(Side.B, Side.A)) == "A,B" && RankedIntegrity.encodeSides(emptySet()) == null &&
        RankedIntegrity.decodeSides("A,B") == setOf(Side.A, Side.B) && RankedIntegrity.decodeSides(null).isEmpty() && RankedIntegrity.decodeSides("X").isEmpty())

    // Same-tick tie-break: going ineligible as your opponent's last player dies is a forfeit, not a free cancel.
    val roster = MatchRoster(listOf(MatchParticipant(A1, Side.A, "ta"), MatchParticipant(B1, Side.B, "tb")))
    val afterA = roster.defeat(A1, DefeatReason.DEATH)!!
    val both = afterA.defeat(B1, DefeatReason.INELIGIBLE)!!
    check("27. ineligible in the same tick as the opponent's death: the other side wins",
        RankedMatchRules.decide(both, listOf(both.of(A1)!!, both.of(B1)!!)) == RankedMatchRules.Decision.Winner(Side.A))
    val deaths = afterA.defeat(B1, DefeatReason.DEATH)!!
    check("two real deaths in one tick are still no winner", RankedMatchRules.decide(deaths, listOf(deaths.of(A1)!!, deaths.of(B1)!!)) == RankedMatchRules.Decision.NoWinner)
    check("28. an ineligible defeat can't be applied twice", both.defeat(B1, DefeatReason.INELIGIBLE) == null)
}

// ── 27: emergency interventions ─────────────────────────────

private fun interventions() {
    check("27. a player of a PUBLIC/CHALLENGE match can't cancel it", RankedIntegrity.interventionRefusal(actorOnRoster = true, actorOnTeam = true, test = false) != null)
    check("27. a member of a playing team can't cancel it either", RankedIntegrity.interventionRefusal(false, true, false) != null)
    check("uninvolved staff can step in", RankedIntegrity.interventionRefusal(false, false, false) == null)
    check("staff in their own TEST match may still stop it", RankedIntegrity.interventionRefusal(true, true, true) == null)
    val cmd = source("lifesteal/ranked/LifestealRankedCommand.kt")
    check("cancel and remove check the intervention rule and are audited", cmd.contains("ranked.interventionProblem(sender, match)?.let") &&
        cmd.contains("ranked.interventionProblem(sender, m)?.let") && cmd.contains("\"RANKED_MATCH_CANCEL\"") && cmd.contains("\"RANKED_PARTICIPANT_REMOVE\""))
}

// ── 23, 26, 28: store (real SQLite) ─────────────────────────

private fun store() {
    val dir = Files.createTempDirectory("ranked-integrity").toFile()
    try {
        val db = DatabaseManager(dir, quietLogger())
        db.start()
        try {
            val s = RankedStore(db)
            s.createTables()
            s.createArena(RankedArena(id = "duel", displayName = "Duel", world = "lifesteal", bounds = Box.of(0, 60, 0, 40, 80, 40),
                teamA = listOf(Point(5.5, 64.0, 5.5)), teamB = listOf(Point(35.5, 64.0, 35.5)), spectators = listOf(Point(20.5, 90.0, 20.5)), enabled = true), 1)
            fun snap(u: UUID) = RankedStore.SnapshotData(u, MutableList<String?>(41) { null }.also { it[0] = "c3dvcmQ=" }, 0, "lifesteal", Point(1.0, 70.0, 1.0))
            val id = "rtw-0000000000b1"
            s.insertMatch(RankedStore.NewMatch(id, "season-1", "duel", "ta", "A", "tb", "B", 2, false, "queue:x", 1, MatchKind.PUBLIC, "rq-1"),
                listOf(MatchParticipant(A1, Side.A, "ta"), MatchParticipant(A2, Side.A, "ta"), MatchParticipant(B1, Side.B, "tb"), MatchParticipant(B2, Side.B, "tb")),
                listOf(snap(A1), snap(A2), snap(B1), snap(B2)))
            s.moveState(id, MatchState.PREPARING, MatchState.COUNTDOWN, 2)
            s.moveState(id, MatchState.COUNTDOWN, MatchState.ACTIVE, 3)
            check("28. an ineligible forfeit is recorded once", s.defeat(id, A1, DefeatReason.INELIGIBLE, 4) && !s.defeat(id, A1, DefeatReason.INELIGIBLE, 5))
            s.defeat(id, B1, DefeatReason.DEATH, 6)
            s.defeat(id, B2, DefeatReason.DEATH, 7)
            check("the ineligible side is stored with the winner", s.finish(id, Side.A, "ta", 8, setOf(Side.A)) && s.match(id)?.ineligibleSides == setOf(Side.A))
            check("28. no second result for the same match", !s.finish(id, Side.B, "tb", 9) && !s.cancel(id, "late", 9) && s.match(id)?.ineligibleSides == setOf(Side.A))
            check("completing keeps the stored sides", s.complete(id, 10) && !s.complete(id, 11) && s.match(id)?.state == MatchState.COMPLETED)
            val verdict = RatingRules.decide(s.match(id)!!, s.participants(id), false, 0, 0)
            check("23. the stored record decides: unrated", verdict is RatingRules.Verdict.Unrated && verdict.reason == RankedIntegrity.UNRATED_REASON)
            check("the forfeit reason reads back", s.participants(id).first { it.uuid == A1 }.defeatReason == DefeatReason.INELIGIBLE)

            // A match cancelled for a restricted state before the fight: unrated, and every snapshot comes back once.
            s.createArena(RankedArena(id = "duel2", displayName = "Duel 2", world = "lifesteal", bounds = Box.of(100, 60, 100, 140, 80, 140),
                teamA = listOf(Point(105.5, 64.0, 105.5)), teamB = listOf(Point(135.5, 64.0, 135.5)), spectators = listOf(Point(120.5, 90.0, 120.5)), enabled = true), 12)
            val c = listOf(UUID(30L, 1), UUID(30L, 2))
            val cid = "rtw-0000000000b2"
            s.insertMatch(RankedStore.NewMatch(cid, "season-1", "duel2", "tc", "C", "td", "D", 1, false, "queue:y", 13, MatchKind.PUBLIC, "rq-2"),
                listOf(MatchParticipant(c[0], Side.A, "tc"), MatchParticipant(c[1], Side.B, "td")), c.map { snap(it) })
            s.moveState(cid, MatchState.PREPARING, MatchState.COUNTDOWN, 14)
            check("26. cancelling for an ineligible player before the fight is recorded once", s.cancel(cid, "P is vanished before the fight", 15) && !s.cancel(cid, "again", 16))
            check("26. a cancelled match is unrated", RatingRules.decide(s.match(cid)!!, s.participants(cid), false, 0, 0) is RatingRules.Verdict.Unrated)
            val pending = s.pendingSnapshot(c[0])
            check("26. inventories stay safe after the cancel: restored exactly once", pending != null && s.markRestored(pending.id, "cancel", 17) && !s.markRestored(pending.id, "cancel", 18))
        } finally {
            db.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }
}

// ── 25, wiring ──────────────────────────────────────────────

private fun wiring() {
    val manager = source("lifesteal/ranked/LifestealRankedManager.kt")
    val body = Regex("""fun checkIntegrity\(match: Match, stage: String\): Boolean \{[\s\S]*?\n    \}""").find(manager)?.value ?: ""
    check("the integrity check exists", body.isNotEmpty())
    check("25. an ineligible forfeit never moves a heart (no forfeitHeart, no transferPvp)", !body.contains("forfeitHeart") && !body.contains("transferPvp"))
    check("the forfeit marks the side, defeats as INELIGIBLE and restores the snapshot", body.contains("match.ineligibleSides += p.side") &&
        body.contains("defeat(match, p.uuid, DefeatReason.INELIGIBLE)") && body.contains("restoreIfPending(player,"))
    check("flight is taken away again if it came back", body.contains("player.allowFlight = false"))
    check("the fight is re-checked every second", manager.contains("if (match.state == MatchState.ACTIVE) checkIntegrity(match, \"fight\")") && manager.contains("const val INTEGRITY_CHECK_TICKS = 20L"))
    check("the result re-checks the winners and stores the ineligible sides", manager.contains("store.finish(match.id, side, team.id, System.currentTimeMillis(), match.ineligibleSides.toSet())") &&
        manager.contains("\"RANKED_INELIGIBLE_AT_RESULT\""))
    check("suppressed results and invalidations are audited", manager.contains("\"RANKED_RESULT_SUPPRESSED\"") && manager.contains("\"RANKED_MATCH_INVALIDATED\"") &&
        manager.contains("plugin.adminManager.logAction(null, RankedFarmingMonitor.SYSTEM_ACTOR, action, uuid, name, full)"))
    check("Elo still comes only from the stored record", manager.contains("if (ok && !shutdown) plugin.lifestealRatingManager.onMatchCompleted(match.id)"))
    check("the rating rule reads the stored sides", source("lifesteal/ranked/rating/RatingRules.kt").contains("RankedIntegrity.ratingBlocked(winner, match.ineligibleSides"))
    check("the column is added to older databases", source("lifesteal/ranked/RankedStore.kt").contains("\"ineligible_sides TEXT\""))
}
