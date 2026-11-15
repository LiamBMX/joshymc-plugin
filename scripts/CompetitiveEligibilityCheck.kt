// Checks the competitive Lifesteal reward eligibility (issue #1487, audit finding D-H4): the
// Bukkit-free rules, the heart store against a real SQLite database (fresh temp directory,
// deleted afterwards), the bounty and coin-steal rules, /enchant's limits, and the wiring that
// puts the check at every reward boundary. The Gradle build has no test source set, so it runs
// through an init script, from the repo root:
//
//   ./gradlew -I scripts/competitive-eligibility-check.init.gradle.kts competitiveEligibilityCheck
//
// Issue #1551 (STF-01) adds enhanced movement (flight, modified walk/fly speed): see movement().
//
// Exits non-zero on the first failed check. Live gameplay is listed in docs/lifesteal-hearts.md
// ("Competitive eligibility", manual tests).

import com.liam.joshymc.lifesteal.bank.BankRules
import com.liam.joshymc.lifesteal.bank.CoinStealSettings
import com.liam.joshymc.lifesteal.bounty.BountyRules
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.EnchantLevel
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.EnchantRequest
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.Mode
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.Movement
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.Reason
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.State
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.Verdict
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.eligible
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.hearts.HeartRules.Decision
import com.liam.joshymc.lifesteal.hearts.HeartStore
import com.liam.joshymc.lifesteal.hearts.HeartStore.PvpResult
import com.liam.joshymc.lifesteal.progression.ProgressionRules
import com.liam.joshymc.lifesteal.ranked.RankedHeartSummary
import com.liam.joshymc.lifesteal.ranked.RankedIntegrity
import com.liam.joshymc.lifesteal.teams.TeamCombatRules
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

private val LIMITS = HeartRules.Limits(starting = 10, minimum = 1, maximum = 20)
private const val COOLDOWN = 30 * 60_000L
private const val T0 = 1_800_000_000_000L
private const val WINDOW = 15_000L

private val SURVIVAL = State(Mode.SURVIVAL)

// Issue #1551: enhanced-movement states.
private val FLYING = SURVIVAL.copy(movement = Movement(flying = true, canFly = true))
private val FAST_WALK = SURVIVAL.copy(movement = Movement(walkSpeed = 0.5f))
private val FAST_FLY = SURVIVAL.copy(movement = Movement(flySpeed = 0.4f, canFly = true))
private val MOVEMENT_STATES = listOf("abnormal walk speed" to FAST_WALK, "abnormal fly speed" to FAST_FLY, "active flight" to FLYING)
private fun verdictOf(state: State, recorded: Set<Reason> = emptySet()) = CompetitiveEligibility.verdict(CompetitiveEligibility.problems(state), recorded)

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

fun main() {
    rules()
    movement()
    hitMemory()
    heartDecisions()
    store()
    bounties()
    coins()
    enchants()
    wiring()
    println("Competitive eligibility checks passed ($checks checks).")
}

// ── Eligibility rule ─────────────────────────────────────────

private fun rules() {
    check("1. survival attacker is eligible", verdictOf(SURVIVAL) == Verdict.Eligible)
    check("adventure attacker is eligible", verdictOf(State(Mode.ADVENTURE)) == Verdict.Eligible)
    val cases = mapOf(
        "2. creative" to (State(Mode.CREATIVE) to Reason.CREATIVE),
        "3. spectator" to (State(Mode.SPECTATOR) to Reason.SPECTATOR),
        "4. god mode" to (SURVIVAL.copy(godMode = true) to Reason.GOD_MODE),
        "5. vanished" to (SURVIVAL.copy(vanished = true) to Reason.VANISHED),
        "mod mode" to (SURVIVAL.copy(modMode = true) to Reason.MOD_MODE),
        "6. illegal enchantments" to (SURVIVAL.copy(unsafeEquipment = true) to Reason.UNSAFE_ENCHANT),
    )
    for ((name, case) in cases) {
        val v = verdictOf(case.first)
        check("$name attacker is ineligible", v is Verdict.Ineligible && v.reasons == setOf(case.second) && !v.duringCombat)
    }
    val many = verdictOf(State(Mode.CREATIVE, godMode = true, vanished = true)) as Verdict.Ineligible
    check("several reasons are all kept", many.reasons == setOf(Reason.CREATIVE, Reason.GOD_MODE, Reason.VANISHED))
    check("code is stable and sorted", many.code == "creative,god_mode,vanished")
    check("labels read naturally", many.labels == "Creative mode, God Mode and Vanish")
    // Ranks/permissions are not an input at all: the State has no permission field, so an Owner in
    // Survival with ordinary gear is judged exactly like any player.
    check("no permission input exists", State::class.java.declaredFields.none { it.name.contains("perm", ignoreCase = true) || it.name.contains("rank", ignoreCase = true) })

    // 15. switching back before the kill: the earlier hits still count.
    val switched = verdictOf(SURVIVAL, recorded = setOf(Reason.CREATIVE))
    check("15. hits made in creative count after switching to survival", switched is Verdict.Ineligible && switched.duringCombat && switched.code == "creative@combat")
    check("god mode turned off before the last hit still counts", verdictOf(SURVIVAL, setOf(Reason.GOD_MODE)) is Verdict.Ineligible)
    check("unvanishing before the last hit still counts", verdictOf(SURVIVAL, setOf(Reason.VANISHED)) is Verdict.Ineligible)
    check("an eligible verdict reports eligible", Verdict.Eligible.eligible && !switched.eligible)
    check("an empty ineligible verdict can't be built", runCatching { Verdict.Ineligible(emptySet(), false) }.isFailure)
    check("memory window is the combat tag, at least 30s", CompetitiveEligibility.memoryMillis(15_000) == 30_000L && CompetitiveEligibility.memoryMillis(60_000) == 60_000L)
}

// ── Enhanced movement (issue #1551, STF-01) ─────────────────

private fun movement() {
    // 1-3, 7, 12: ordinary movement stays eligible.
    check("1. normal Survival movement is eligible", verdictOf(SURVIVAL) == Verdict.Eligible)
    check("2. normal Lifesteal movement (vanilla speeds, Adventure too) is eligible",
        verdictOf(State(Mode.ADVENTURE, movement = Movement(CompetitiveEligibility.DEFAULT_WALK_SPEED, CompetitiveEligibility.DEFAULT_FLY_SPEED))) == Verdict.Eligible)
    // Sprinting, Speed potions and Soul Speed are attribute modifiers: Player.walkSpeed stays 0.2.
    check("3. sprinting (walkSpeed untouched) is eligible", verdictOf(SURVIVAL.copy(movement = Movement(walkSpeed = 0.2f))) == Verdict.Eligible)
    check("float noise around the default isn't a modified speed", verdictOf(SURVIVAL.copy(movement = Movement(walkSpeed = 0.2000004f, flySpeed = 0.1000004f, canFly = true))) == Verdict.Eligible)
    check("slower than vanilla is no advantage", verdictOf(SURVIVAL.copy(movement = Movement(walkSpeed = 0.1f, flySpeed = 0.05f, canFly = true))) == Verdict.Eligible)
    check("7. flight allowed but not flying, normal speeds: eligible", verdictOf(SURVIVAL.copy(movement = Movement(canFly = true))) == Verdict.Eligible)
    check("7. a boosted fly speed nobody can use (no flight) is ignored", verdictOf(SURVIVAL.copy(movement = Movement(flySpeed = 1f))) == Verdict.Eligible)
    check("12. an ordinary player is eligible", verdictOf(State(Mode.SURVIVAL, movement = Movement())) == Verdict.Eligible)

    // 4-6: enhanced movement is ineligible.
    val walk = verdictOf(FAST_WALK)
    check("4. abnormal walk speed is ineligible", walk is Verdict.Ineligible && walk.reasons == setOf(Reason.WALK_SPEED) && walk.code == "walk_speed")
    val fly = verdictOf(FAST_FLY)
    check("5. abnormal fly speed (can fly) is ineligible", fly is Verdict.Ineligible && fly.reasons == setOf(Reason.FLY_SPEED))
    val flying = verdictOf(FLYING)
    check("6. active flight is ineligible", flying is Verdict.Ineligible && flying.reasons == setOf(Reason.FLYING) && flying.labels == "flight")
    val both = verdictOf(SURVIVAL.copy(movement = Movement(flySpeed = 1f, flying = true, canFly = true)))
    check("6. fast flight lists both reasons", both is Verdict.Ineligible && both.code == "flying,fly_speed")
    check("a non-number speed counts as modified", verdictOf(SURVIVAL.copy(movement = Movement(walkSpeed = Float.NaN))) is Verdict.Ineligible)
    check("creative flight reads as Creative only", (verdictOf(State(Mode.CREATIVE, movement = Movement(flySpeed = 1f, flying = true, canFly = true))) as Verdict.Ineligible).reasons == setOf(Reason.CREATIVE))
    check("creative with /speed walk still lists the walk speed", (verdictOf(State(Mode.CREATIVE, movement = Movement(walkSpeed = 1f))) as Verdict.Ineligible).reasons == setOf(Reason.CREATIVE, Reason.WALK_SPEED))
    check("existing exclusions are unchanged", verdictOf(SURVIVAL.copy(godMode = true, movement = Movement(walkSpeed = 0.6f))).let { it is Verdict.Ineligible && it.code == "god_mode,walk_speed" })
    check("old audit codes keep their order (new reasons appended)", Reason.entries.take(6).map { it.id } == listOf("creative", "spectator", "god_mode", "vanished", "mod_mode", "unsafe_enchant"))
    check("movement reasons are exactly the three new ones", CompetitiveEligibility.MOVEMENT_REASONS == setOf(Reason.FLYING, Reason.WALK_SPEED, Reason.FLY_SPEED))
    check("flying before the kill still counts after landing", verdictOf(SURVIVAL, setOf(Reason.FLYING)).let { it is Verdict.Ineligible && it.duringCombat })

    // 11. ranked: the same reasons refuse entry and are forfeits mid-fight (RankedIntegrity).
    for (reason in CompetitiveEligibility.MOVEMENT_REASONS) {
        check("11. ranked refuses ${reason.id}", RankedIntegrity.eligibilityProblem(setOf(reason)) != null)
    }
    check("11. ranked names /speed for a modified walk speed", RankedIntegrity.eligibilityProblem(setOf(Reason.WALK_SPEED))!!.contains("/speed 2 walk"))
    check("11. ranked allows normal movement", RankedIntegrity.eligibilityProblem(CompetitiveEligibility.problems(SURVIVAL.copy(movement = Movement(canFly = true)))) == null)
    check("11. enhanced movement found mid-fight is the existing forfeit", RankedIntegrity.resolve(com.liam.joshymc.lifesteal.ranked.MatchState.ACTIVE, stillFighting = true) == RankedIntegrity.Resolution.FORFEIT)
    // Flight is taken (not forfeited) before the reasons are read, so a held player is only ever
    // forfeited for a speed someone changed, never for flight the match itself turns off.
    val integrity = source("lifesteal/ranked/LifestealRankedManager.kt").substringAfter("fun checkIntegrity(").substringBefore("fun ", )
    check("11. checkIntegrity removes flight before reading the reasons", integrity.indexOf("player.allowFlight = false") in 0 until integrity.indexOf("competitiveReasons(player)"))
    check("11. entry uses the same reasons", source("lifesteal/ranked/LifestealRankedManager.kt").contains("else -> RankedIntegrity.eligibilityProblem(competitiveReasons(player))"))
}

private fun hitMemory() {
    val a = UUID.randomUUID()
    val v = UUID.randomUUID()
    val other = UUID.randomUUID()
    val m = CompetitiveEligibility.HitMemory()
    m.record(a, v, emptySet(), T0, WINDOW)
    check("eligible hits aren't stored", m.size == 0)
    // 13. projectile: the launch stamp (creative) is what the hit records.
    m.record(a, v, setOf(Reason.CREATIVE), T0, WINDOW)
    check("13. a stamped projectile hit is remembered", m.recorded(a, v, T0 + 1_000, WINDOW) == setOf(Reason.CREATIVE))
    m.record(a, v, emptySet(), T0 + 2_000, WINDOW)
    check("a later eligible hit never erases it", m.recorded(a, v, T0 + 2_000, WINDOW) == setOf(Reason.CREATIVE))
    m.record(a, v, setOf(Reason.GOD_MODE), T0 + 3_000, WINDOW)
    check("reasons accumulate inside the window", m.recorded(a, v, T0 + 3_000, WINDOW) == setOf(Reason.CREATIVE, Reason.GOD_MODE))
    check("only this victim", m.recorded(a, other, T0 + 3_000, WINDOW).isEmpty())
    check("only this attacker (an unrelated player isn't punished)", m.recorded(other, v, T0 + 3_000, WINDOW).isEmpty())
    check("15. delayed damage inside the window still counts", m.recorded(a, v, T0 + 3_000 + WINDOW - 1, WINDOW).isNotEmpty())
    check("expires after the window", m.recorded(a, v, T0 + 3_000 + WINDOW, WINDOW).isEmpty())
    m.record(a, v, setOf(Reason.VANISHED), T0 + 100_000, WINDOW)
    check("expired reasons don't carry over", m.recorded(a, v, T0 + 100_000, WINDOW) == setOf(Reason.VANISHED))
    check("a clock going back never expires a hit early", m.recorded(a, v, T0 + 50_000, WINDOW) == setOf(Reason.VANISHED))
    m.record(a, a, setOf(Reason.CREATIVE), T0, WINDOW)
    check("self hits ignored", m.recorded(a, a, T0, WINDOW).isEmpty())

    val bounded = CompetitiveEligibility.HitMemory(maxEntries = 3)
    repeat(10) { bounded.record(UUID.randomUUID(), v, setOf(Reason.CREATIVE), T0, WINDOW) }
    check("memory is bounded", bounded.size == 3)

    val t = CompetitiveEligibility.Throttle(windowMillis = 1_000)
    check("throttle: first allowed", t.allow("k", T0))
    check("throttle: repeat suppressed", !t.allow("k", T0 + 500))
    check("throttle: other key allowed", t.allow("k2", T0 + 500))
    check("throttle: after the window allowed", t.allow("k", T0 + 1_000))
}

// ── Heart decisions ─────────────────────────────────────────

private fun heartDecisions() {
    val creative = verdictOf(State(Mode.CREATIVE))
    check("eligible kill still transfers", HeartRules.decide(10, 10, 0, LIMITS) == Decision.Transfer(9, 11))
    check("ineligible attacker moves nothing", HeartRules.decide(10, 10, 0, LIMITS, attacker = creative) == Decision.IneligibleAttacker("creative"))
    check("ineligibility is checked before the progression window", HeartRules.decide(10, 10, 0, LIMITS, window = ProgressionRules.HeartWindow.GRACE_PERIOD, attacker = creative) is Decision.IneligibleAttacker)
    check("ineligibility is checked before the team rules", HeartRules.decide(10, 10, 0, LIMITS, team = TeamCombatRules.HeartCheck.TEAMMATES, attacker = creative) is Decision.IneligibleAttacker)
    check("ineligible decision never changes hearts", with(HeartRules) { !HeartRules.decide(10, 10, 0, LIMITS, attacker = creative).changesHearts })
    // 24. limits untouched.
    check("24. last heart still kept", HeartRules.decide(1, 10, 0, LIMITS) == Decision.VictimAtMinimum)
    check("24. maximum still enforced (#1537: victim-only loss)", HeartRules.decide(10, 20, 0, LIMITS) == Decision.VictimLosesOnly(9))
    check("24. defaults are 10 / 1 / 20", LIMITS.starting == 10 && LIMITS.minimum == 1 && LIMITS.maximum == 20)
    check("24. withdrawing the last heart still refused", HeartRules.withdraw(1, LIMITS) == HeartRules.WithdrawDecision.AtMinimum)
    check("ranked summary shows it as no heart", RankedHeartSummary.Outcome.of(Decision.IneligibleAttacker("creative")) == RankedHeartSummary.Outcome.INELIGIBLE_ATTACKER && !RankedHeartSummary.Outcome.INELIGIBLE_ATTACKER.moved)

    // 14. Environmental PvP attribution: an ineligible most-recent attacker is never credited.
    val victim = UUID.randomUUID()
    val staff = UUID.randomUUID()
    val memory = CompetitiveEligibility.HitMemory()
    memory.record(staff, victim, setOf(Reason.GOD_MODE), T0, WINDOW)
    val credited = HeartRules.combatTagKiller(
        HeartRules.Attribution.ENVIRONMENT, victim, T0 + WINDOW, listOf(HeartRules.RecentHit(staff, T0)), T0 + 1_000, WINDOW, false,
    ) { attacker -> CompetitiveEligibility.verdict(emptySet(), memory.recorded(attacker, victim, T0 + 1_000, WINDOW)).eligible }
    check("14. environmental death isn't credited to an attacker who used god mode", credited == null)
}

// ── Heart store (real SQLite) ───────────────────────────────

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }

private fun store() {
    val dir = Files.createTempDirectory("joshymc-eligibility").toFile()
    try {
        val db = DatabaseManager(dir, quietLogger())
        db.start()
        try {
            val store = HeartStore(db)
            store.createTables()
            fun player() = UUID.randomUUID().also { store.ensure(it, 10, T0) }
            fun request(killer: UUID, victim: UUID, verdict: Verdict, key: String = "pvp:${UUID.randomUUID()}", now: Long = T0) =
                HeartStore.PvpRequest(key, killer, victim, now, COOLDOWN, LIMITS, attacker = verdict)

            val killer = player()
            val victim = player()
            val ok = store.transferPvp(request(killer, victim, Verdict.Eligible))
            check("1. eligible kill transfers a heart", ok is PvpResult.Done && ok.decision == Decision.Transfer(9, 11) && store.load(victim)!!.hearts == 9 && store.load(killer)!!.hearts == 11)

            val states = listOf(
                "2. creative" to State(Mode.CREATIVE), "3. spectator" to State(Mode.SPECTATOR),
                "4. god mode" to SURVIVAL.copy(godMode = true), "5. vanished" to SURVIVAL.copy(vanished = true),
                "6. illegal enchantment" to SURVIVAL.copy(unsafeEquipment = true),
            ) + MOVEMENT_STATES.map { (n, st) -> "#1551 8. $n" to st }
            for ((name, state) in states) {
                val k = player()
                val v = player()
                val r = store.transferPvp(request(k, v, verdictOf(state)))
                check("$name attacker gets no heart", r is PvpResult.Done && r.decision is Decision.IneligibleAttacker && store.load(k)!!.hearts == 10)
                check("16. $name kill keeps the victim's hearts", store.load(v)!!.hearts == 10)
                check("$name is audited with its reason", store.audit(v).any { it.operation == "PVP_REJECTED" && it.reason?.startsWith("INELIGIBLE_ATTACKER") == true })
                check("$name creates no PVP_GAIN row", store.audit(k).none { it.operation == "PVP_GAIN" })
            }

            // 7-9: Combat NPC kills use the same request (dedupe key per NPC session) and the same verdict.
            for ((name, state) in listOf("7. creative" to State(Mode.CREATIVE), "8. god mode" to SURVIVAL.copy(godMode = true), "9. vanished" to SURVIVAL.copy(vanished = true))) {
                val k = player()
                val owner = player()
                val session = UUID.randomUUID().toString()
                val r = store.transferPvp(request(k, owner, verdictOf(state), key = "combatnpc:$session"))
                check("$name attacker can't collect a Combat NPC heart", r is PvpResult.Done && r.decision is Decision.IneligibleAttacker && store.load(k)!!.hearts == 10 && store.load(owner)!!.hearts == 10)
                check("$name NPC death is processed once", store.transferPvp(request(k, owner, Verdict.Eligible, key = "combatnpc:$session")) == PvpResult.Duplicate && store.load(owner)!!.hearts == 10)
            }

            // 17. no duplicate transactions, no cooldown from a rejected kill.
            val k = player()
            val v = player()
            val key = "pvp:dup"
            store.transferPvp(request(k, v, verdictOf(State(Mode.CREATIVE)), key = key))
            check("17. the same death never runs twice", store.transferPvp(request(k, v, Verdict.Eligible, key = key)) == PvpResult.Duplicate)
            val rows = db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_heart_transactions WHERE dedupe_key = ?", key) { it.getInt("n") }
            check("17. exactly one transaction row", rows == 1)
            check("a rejected kill starts no cooldown", HeartRules.cooldownRemaining(lastCooldown(db, k, v), T0, COOLDOWN) == 0L)
            val later = store.transferPvp(request(k, v, Verdict.Eligible, now = T0 + 1_000))
            check("a later legitimate kill by the same player still works", later is PvpResult.Done && later.decision == Decision.Transfer(9, 11))
            check("16. a 1-heart victim stays at 1 against an ineligible killer", run {
                val low = UUID.randomUUID().also { store.ensure(it, 1, T0) }
                store.transferPvp(request(player(), low, verdictOf(State(Mode.CREATIVE))))
                store.load(low)!!.hearts == 1
            })
        } finally {
            db.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }
}

private fun lastCooldown(db: DatabaseManager, killer: UUID, victim: UUID): Long? =
    db.queryFirst("SELECT last_transfer_at FROM lifesteal_heart_cooldowns WHERE killer = ? AND victim = ?", killer.toString(), victim.toString()) { it.getLong("last_transfer_at") }

// ── Bounties ────────────────────────────────────────────────

private fun bounties() {
    val victim = UUID.randomUUID()
    val killer = UUID.randomUUID()
    val ok = BountyRules.Death(victim = victim, causingPlayer = killer)
    check("12. eligible players can still claim", BountyRules.eligibility(ok) == BountyRules.Eligibility.Eligible(killer))
    for (state in listOf(State(Mode.CREATIVE), State(Mode.SPECTATOR), SURVIVAL.copy(godMode = true), SURVIVAL.copy(vanished = true), SURVIVAL.copy(unsafeEquipment = true)) + MOVEMENT_STATES.map { it.second }) {
        val r = BountyRules.eligibility(ok.copy(attacker = verdictOf(state)))
        check("10. ${state} gets no bounty", r is BountyRules.Eligibility.Refused && r.reason == BountyRules.Refusal.INELIGIBLE_ATTACKER)
    }
    check("10. hits made earlier in creative refuse the claim too", BountyRules.eligibility(ok.copy(attacker = verdictOf(SURVIVAL, setOf(Reason.CREATIVE)))) is BountyRules.Eligibility.Refused)
    check("an ineligible refusal names the killer (for the audit), not anybody else", (BountyRules.eligibility(ok.copy(attacker = verdictOf(State(Mode.CREATIVE)))) as BountyRules.Eligibility.Refused).killer == killer)
    // 11. a refusal is decided before the claim transaction, so the bounty stays ACTIVE.
    val manager = source("lifesteal/bounty/LifestealBountyManager.kt")
    val onDeath = manager.substringAfter("fun onDeath(event: PlayerDeathEvent)")
    check("11. refusals return before any claim (bounty not consumed)", onDeath.indexOf("is BountyRules.Eligibility.Refused") in 0 until onDeath.indexOf("processClaim("))
    check("pvpFacts carries the attacker verdict (also gates PvP quests)", manager.contains("attacker = killer?.let { plugin.competitiveEligibility.verdict(it, victim.uniqueId) }"))
}

// ── Coins ───────────────────────────────────────────────────

private fun coins() {
    val pvp = BankRules.Death(
        attribution = HeartRules.Attribution.PVP_KILL, inEvent = false, inRankedMatch = false, window = ProgressionRules.HeartWindow.OPEN,
        heartCheck = TeamCombatRules.HeartCheck.ELIGIBLE, relation = TeamCombatRules.Relation.NOT_TEAMMATES, sharedConnection = false,
    )
    check("eligible kill can still steal coins", BankRules.eligibility(pvp, CoinStealSettings.DEFAULTS) == BankRules.Eligibility.Eligible)
    val refused = BankRules.eligibility(pvp.copy(attacker = verdictOf(SURVIVAL.copy(godMode = true))), CoinStealSettings.DEFAULTS)
    check("god mode kill steals no coins", refused is BankRules.Eligibility.Refused && refused.reason == BankRules.StealRefusal.INELIGIBLE_ATTACKER)
    for ((name, state) in MOVEMENT_STATES) {
        val r = BankRules.eligibility(pvp.copy(attacker = verdictOf(state)), CoinStealSettings.DEFAULTS)
        check("#1551 10. $name kill steals no coins", r is BankRules.Eligibility.Refused && r.reason == BankRules.StealRefusal.INELIGIBLE_ATTACKER)
    }
    check("#1551 flight allowed but unused still steals coins", BankRules.eligibility(pvp.copy(attacker = verdictOf(SURVIVAL.copy(movement = Movement(canFly = true)))), CoinStealSettings.DEFAULTS) == BankRules.Eligibility.Eligible)
}

// ── Enchantments ────────────────────────────────────────────

private fun enchants() {
    check("21. above max refused by default", EnchantRequest.Refused("Sharpness only goes up to level 5.") == CompetitiveEligibility.enchantRequest("Sharpness", 10, 5, true, null, false))
    check("21. wrong item refused by default", CompetitiveEligibility.enchantRequest("Sharpness", 1, 5, false, null, false) is EnchantRequest.Refused)
    check("21. conflicting enchantment refused by default", CompetitiveEligibility.enchantRequest("Smite", 1, 5, true, "Sharpness", false) is EnchantRequest.Refused)
    check("normal enchanting still works", CompetitiveEligibility.enchantRequest("Sharpness", 5, 5, true, null, false) == EnchantRequest.Allowed(unsafe = false))
    check("level 0 / 256 always refused", CompetitiveEligibility.enchantRequest("Sharpness", 0, 5, true, null, true) is EnchantRequest.Refused &&
        CompetitiveEligibility.enchantRequest("Sharpness", 256, 5, true, null, true) is EnchantRequest.Refused)
    check("22. unsafe only with the permission, and marked unsafe", CompetitiveEligibility.enchantRequest("Sharpness", 10, 5, true, null, true) == EnchantRequest.Allowed(unsafe = true))
    check("a vanilla request stays safe even with the permission", CompetitiveEligibility.enchantRequest("Sharpness", 3, 5, true, null, true) == EnchantRequest.Allowed(unsafe = false))

    // 23. over-limit equipment.
    val sharp10 = EnchantLevel("minecraft:sharpness", 10, 5)
    check("23. sharpness 10 is over the limit", CompetitiveEligibility.overLimit(listOf(EnchantLevel("minecraft:unbreaking", 3, 3), sharp10)) == sharp10)
    check("vanilla levels are fine", CompetitiveEligibility.overLimit(listOf(EnchantLevel("minecraft:sharpness", 5, 5))) == null)
    val token = listOf(EnchantLevel("minecraft:mending", 10, 1))
    check("a custom item's own build level is allowed (Token's Mending 10)", CompetitiveEligibility.overLimit(token, mapOf("minecraft:mending" to 10)) == null)
    check("anything above the custom build still counts", CompetitiveEligibility.overLimit(listOf(EnchantLevel("minecraft:mending", 11, 1)), mapOf("minecraft:mending" to 10)) != null)
}

// ── Wiring ──────────────────────────────────────────────────

private fun wiring() {
    val hearts = source("lifesteal/hearts/LifestealHeartManager.kt")
    check("normal kills, NPC kills and ranked forfeits all settle the verdict", Regex("attacker = plugin\\.competitiveEligibility\\.verdict\\(").findAll(hearts).count() == 3)
    check("combat-tag and ranked environmental credit skip ineligible attackers", Regex("competitiveEligibility\\.verdict\\(candidate, victim\\.uniqueId\\)\\.eligible").findAll(hearts).count() == 2)
    check("the store passes the verdict into decide", source("lifesteal/hearts/HeartStore.kt").contains("request.window, request.attacker)"))
    check("blocked hearts are reported", hearts.contains("reportBlocked(killer, victimId, victimName, \"HEART\""))
    check("bank coin stealing settles the verdict", source("lifesteal/bank/LifestealBankManager.kt").contains("attacker = plugin.competitiveEligibility.verdict(killer, victim)"))
    val npc = source("lifesteal/combat/npc/LifestealCombatNpcManager.kt")
    check("Combat NPC hits refused for staff abilities", npc.substringAfter("fun playerHit(").substringBefore("fun otherDamageAllowed").contains("competitiveEligibility.currentReasons(attacker)"))
    val listener = source("lifesteal/eligibility/CompetitiveEligibilityListener.kt")
    check("hits are recorded at MONITOR by causing entity", listener.contains("@EventHandler(priority = EventPriority.MONITOR)\n    fun onDamage(") && listener.contains("damageSource.causingEntity"))
    val monitor = listener.substringAfter("fun onDamage(").substringBefore("fun onLaunch(")
    check("cancelled hits are still never recorded", monitor.indexOf("if (event.isCancelled) return") in 0 until monitor.indexOf("tracker.onHit("))
    check("#1551 movement is read at LOWEST, before the combat tag takes flight", listener.contains("@EventHandler(priority = EventPriority.LOWEST)\n    fun onDamageStart(") &&
        listener.contains("tracker.movementReasons(attacker)") && monitor.contains("movementAtHit.remove(event)") && monitor.contains("tracker.onHit(attacker, victim, direct, before)"))
    val tracker = source("lifesteal/eligibility/CompetitiveEligibilityTracker.kt")
    check("#1551 live movement comes from walkSpeed/flySpeed/isFlying/allowFlight", tracker.contains("walkSpeed = player.walkSpeed") && tracker.contains("flySpeed = player.flySpeed") &&
        tracker.contains("flying = player.isFlying") && tracker.contains("canFly = player.allowFlight") && tracker.contains("movement = movementOf(player)"))
    check("#1551 Combat NPC hits aren't refused for movement (the kill just earns nothing)", npc.contains("CompetitiveEligibility.MOVEMENT_REASONS"))
    check("#1551 the blocked-reward message explains movement", tracker.contains("Land and reset /speed to fight for rewards."))
    check("#1551 /speed itself is unchanged for Survival", source("command/EssentialsCommands.kt").substringAfter("class SpeedCommand").substringBefore("override fun onTabComplete").contains("sender.walkSpeed = normalized"))
    check("Combat NPC hits count for the owner", listener.contains("lifestealCombatNpcManager.ownerOf(entity)"))
    check("projectiles are stamped at launch", listener.contains("ProjectileLaunchEvent") && listener.contains("stampProjectile"))
    check("listener registered with the heart listener", File("src/main/kotlin/com/liam/joshymc/manager/ListenerManager.kt").readText().contains("CompetitiveEligibilityListener(plugin)"))

    // 20. /god others.
    val essentials = source("command/EssentialsCommands.kt")
    val god = essentials.substringAfter("class GodCommand").substringBefore("class SpeedCommand")
    check("20. /god <other> needs joshymc.god.others", god.contains("const val OTHERS_PERMISSION = \"joshymc.god.others\"") &&
        god.contains("if (others && !sender.hasPermission(OTHERS_PERMISSION))"))
    check("20. the others check runs before the toggle", god.indexOf("OTHERS_PERMISSION)") < god.indexOf("godPlayers.add"))
    check("god toggles are audited", god.contains("AdminAudit.log") && god.contains("GOD_MODE_OTHER"))
    val enchant = source("command/EnchantCommand.kt")
    check("unsafe /enchant is audited", enchant.contains("\"ENCHANT_UNSAFE\""))
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("joshymc.god.others is op only", Regex("joshymc\\.god\\.others:\\n\\s+description:[^\\n]*\\n\\s+default: op").containsMatchIn(pluginYml))
    check("joshymc.enchant.unsafe is never granted by default", Regex("joshymc\\.enchant\\.unsafe:\\n\\s+description:[^\\n]*\\n\\s+default: false").containsMatchIn(pluginYml))
}
