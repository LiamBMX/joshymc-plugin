// Checks the Lifesteal economy security remediation (issue #1548): welcome rewards scoped to Survival
// (LS-ECO-01), combat-time coin shielding through bounties and the team bank (LS-ECO-02), Combat NPC
// bounty claims with one claim per NPC session (LS-ECO-03), ranked coin-stealing attribution taken at
// death time (LS-ECO-04), payout alt monitoring (LS-ECO-05, informational only), combat-tagged
// environmental death credit for coins and bounties (LS-ECO-07) and the bounty launch/grace guard
// (LS-ECO-08). Rules are tested without a server; the stores against a real SQLite database. The
// Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-economy-security-check.init.gradle.kts lifestealEconomySecurityCheck
//
// Exits non-zero on the first failed check. Live Paper behaviour (real deaths, Combat NPCs, menus)
// is NOT covered here; see the PR's manual test list.

import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.bank.BankRules
import com.liam.joshymc.lifesteal.bank.CoinStealSettings
import com.liam.joshymc.lifesteal.bounty.BountyMoney
import com.liam.joshymc.lifesteal.bounty.BountyMonitorRules
import com.liam.joshymc.lifesteal.bounty.BountyRules
import com.liam.joshymc.lifesteal.bounty.BountyStore
import com.liam.joshymc.lifesteal.bounty.BountyStore.ClaimResult
import com.liam.joshymc.lifesteal.bounty.BountyStore.ContributeResult
import com.liam.joshymc.lifesteal.bounty.BountyStore.PayResult
import com.liam.joshymc.lifesteal.bounty.LifestealBountyManager
import com.liam.joshymc.lifesteal.economy.EconomyDeathRules
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.hearts.HeartStore
import com.liam.joshymc.lifesteal.progression.ProgressionRules
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore
import com.liam.joshymc.lifesteal.teams.TeamCombatRules
import com.liam.joshymc.lifesteal.teams.progression.TeamProgressionSettings
import com.liam.joshymc.lifesteal.teams.progression.TeamProgressionStore
import com.liam.joshymc.listener.WelcomeRewardRules
import com.liam.joshymc.manager.DatabaseManager
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
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

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }
private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR
private const val MIN = 1_000_000L // $10,000 in cents
private const val T0 = 1_000_000_000_000L
private fun coins(c: Long) = c * 100
private fun uuid(n: Int) = UUID(0x1548L, n.toLong())

private val SURVIVAL = ServerGamemode.SURVIVAL
private val LIFESTEAL = ServerGamemode.LIFESTEAL

fun main() {
    welcome()
    shielding()
    deathAttribution()
    rankedCoins()
    bountyRules()
    monitorRules()
    database()
    wiring()
    println("Lifesteal economy security checks passed ($checks checks).")
    exitProcess(0)
}

// ── LS-ECO-01: welcome rewards ───────────────────────────────

private fun welcome() {
    val dedicated = setOf(LIFESTEAL)
    val survival = setOf(SURVIVAL)
    val mixed = setOf(SURVIVAL, LIFESTEAL)
    // 1/2: a brand-new player (or a network player new to this backend) on the dedicated Lifesteal server.
    check("1. dedicated Lifesteal: a first join opens no welcome window", ServerGamemode.entries.none { WelcomeRewardRules.opensWindow(dedicated, it) })
    // 3/4/5: nobody typing "welcome" there is paid, whatever world they stand in.
    check("3-5. dedicated Lifesteal: no welcomer is ever paid", ServerGamemode.entries.none { WelcomeRewardRules.pays(dedicated, it) })
    check("6. Survival: a first join opens the window", WelcomeRewardRules.opensWindow(survival, SURVIVAL))
    check("6. Survival: a Survival welcomer is paid", WelcomeRewardRules.pays(survival, SURVIVAL))
    check("7. mixed server: Survival newcomer still opens the window", WelcomeRewardRules.opensWindow(mixed, SURVIVAL))
    check("7. mixed server: a newcomer in a Lifesteal world opens none", !WelcomeRewardRules.opensWindow(mixed, LIFESTEAL))
    check("7. mixed server: a welcomer in a Lifesteal world isn't paid", !WelcomeRewardRules.pays(mixed, LIFESTEAL) && WelcomeRewardRules.pays(mixed, SURVIVAL))

    val listener = source("listener/WelcomeListener.kt")
    check("listener: window only when the rules allow it", listener.contains("WelcomeRewardRules.opensWindow(") && listener.contains("if (rewardWindow) {"))
    check("listener: no call-to-action without a window", listener.contains("if (firstJoinBroadcast && rewardWindow)"))
    check("listener: chat refuses early and the deposit re-checks on the main thread",
        listener.contains("if (recentNewPlayers.isEmpty() || !rewardAllowed(welcomer)) return") &&
            Regex("private fun grantWelcomeReward\\(welcomer: Player, newcomerName: String\\) \\{[\\s\\S]{0,300}if \\(!welcomer.isOnline \\|\\| !rewardAllowed\\(welcomer\\)\\) return")
                .containsMatchIn(listener))
    check("listener: Survival reward values unchanged ($10,000 + 1 Credit)",
        listener.contains("plugin.economyManager.deposit(welcomer.uniqueId, 10000.0)") && listener.contains("plugin.creditsManager.deposit(welcomer.uniqueId, 1.0)"))
    check("listener: per-welcomer anti-duplicate kept", listener.contains("if (entry.welcomers.add(welcomer.uniqueId))"))
}

// ── LS-ECO-02: coin shielding ────────────────────────────────

private val free = BankRules.AccessState(inLifesteal = true, dead = false, combatTagged = false, inRankedMatch = false, inEvent = false, frozen = false)

private fun shielding() {
    check("10. untagged player in Lifesteal may contribute/deposit", BankRules.shieldingBlock(free) == null)
    check("8/9. combat-tagged is refused", BankRules.shieldingBlock(free.copy(combatTagged = true)) == BankRules.Block.COMBAT)
    check("11. ranked-held is refused", BankRules.shieldingBlock(free.copy(inRankedMatch = true)) == BankRules.Block.RANKED)
    check("12. frozen is refused", BankRules.shieldingBlock(free.copy(frozen = true)) == BankRules.Block.FROZEN)
    check("13. event state is refused", BankRules.shieldingBlock(free.copy(inEvent = true)) == BankRules.Block.EVENT)
    check("dead is refused", BankRules.shieldingBlock(free.copy(dead = true)) == BankRules.Block.DEAD)
    check("outside Lifesteal is refused", BankRules.shieldingBlock(free.copy(inLifesteal = false)) == BankRules.Block.NOT_LIFESTEAL)
    check("same fixed order as the bank (combat before ranked/event/frozen)",
        BankRules.shieldingBlock(free.copy(combatTagged = true, inRankedMatch = true, inEvent = true, frozen = true)) == BankRules.Block.COMBAT)
    for (action in BankRules.ShieldAction.entries) for (block in BankRules.Block.entries) {
        check("message for $action/$block names the action", BankRules.shieldingMessage(block, action).contains(action.verb))
    }
}

// ── LS-ECO-07 + LS-ECO-04: death attribution ─────────────────

private val V = uuid(1)
private val A = uuid(2)
private val B = uuid(3)
private const val TICK = 500
private const val TAG = 15_000L

private fun capture(
    tick: Int = TICK, at: Long = T0, expires: Long? = T0 + 10_000, hits: List<HeartRules.RecentHit> = listOf(HeartRules.RecentHit(A, T0 - 2_000)),
    ranked: String? = null, logged: Boolean = false,
) = EconomyDeathRules.Capture(tick, at, expires, hits, ranked, logged)

private fun resolve(
    direct: HeartRules.Attribution = HeartRules.Attribution.ENVIRONMENT, directKiller: UUID? = null, cap: EconomyDeathRules.Capture? = capture(),
    ranked: String? = null, loggedNow: Boolean = false, damage: String? = "minecraft:lava", eligible: (UUID) -> Boolean = { true },
    reattribute: (UUID) -> HeartRules.Attribution = { HeartRules.Attribution.PVP_KILL },
) = EconomyDeathRules.resolve(V, direct, directKiller, cap, TICK, T0, TAG, ranked, loggedNow, damage, eligible, reattribute)

private fun deathAttribution() {
    val direct = resolve(direct = HeartRules.Attribution.PVP_KILL, directKiller = B)
    check("direct PvP kill keeps its killer", direct.killer == B && !direct.viaCombatTag && direct.attribution == HeartRules.Attribution.PVP_KILL)
    for (cause in listOf("minecraft:lava", "minecraft:out_of_world", "minecraft:fall", "minecraft:in_fire", "minecraft:drown")) {
        val r = resolve(damage = cause)
        check("34-36. combat-tagged $cause death credits the last attacker", r.killer == A && r.viaCombatTag && r.attribution == HeartRules.Attribution.PVP_KILL)
    }
    check("self-inflicted death while tagged credits the attacker", resolve(direct = HeartRules.Attribution.SELF).killer == A)
    check("37. expired tag credits nobody", resolve(cap = capture(expires = T0 - 1)).killer == null)
    check("37. untagged credits nobody", resolve(cap = capture(expires = null)).killer == null)
    check("37. a hit older than one tag duration credits nobody", resolve(cap = capture(hits = listOf(HeartRules.RecentHit(A, T0 - TAG - 1)))).killer == null)
    check("38. no recorded attacker credits nobody", resolve(cap = capture(hits = emptyList())).killer == null)
    check("38. no capture credits nobody", resolve(cap = null).killer == null)
    check("38. a stale capture (earlier tick) credits nobody", resolve(cap = capture(tick = TICK - 1)).killer == null)
    check("38. ineligible attacker (staff abilities) credits nobody", resolve(eligible = { false }).killer == null)
    check("38. attacker no longer a valid Lifesteal killer credits nobody", resolve(reattribute = { HeartRules.Attribution.INVALID_KILLER }).killer == null)
    val two = capture(hits = listOf(HeartRules.RecentHit(A, T0 - 5_000), HeartRules.RecentHit(B, T0 - 1_000)))
    check("most recent eligible attacker wins", resolve(cap = two).killer == B && resolve(cap = two, eligible = { it == A }).killer == A)
    check("the victim is never their own killer", resolve(cap = capture(hits = listOf(HeartRules.RecentHit(V, T0 - 1_000)))).killer == null)
    check("41. /kill credits nobody", resolve(damage = EconomyDeathRules.GENERIC_KILL).killer == null)
    check("42. a ranked match death gets no combat-tag credit", resolve(ranked = "m1").killer == null)
    check("combat logger (now or at death) gets no credit", resolve(loggedNow = true).killer == null && resolve(cap = capture(logged = true)).killer == null)
    check("NOT_LIFESTEAL / INVALID_KILLER deaths are never replaced", resolve(direct = HeartRules.Attribution.NOT_LIFESTEAL).killer == null &&
        resolve(direct = HeartRules.Attribution.INVALID_KILLER).killer == null && resolve(direct = HeartRules.Attribution.NOT_LOADED).killer == null)
    check("43. same tick in every resolution (one dedupe identity per death)", resolve().tick == TICK && direct.tick == TICK)
}

private fun rankedCoins() {
    val c = capture(ranked = "m1")
    check("30. victim's LOWEST copy wins after the defeat removed them", EconomyDeathRules.rankedMatch(c, null, TICK, null, null) == "m1")
    check("stale victim copy never makes a later death ranked", EconomyDeathRules.rankedMatch(capture(tick = TICK - 1, ranked = "m1"), null, TICK, null, null) == null)
    check("live victim lookup still counts", EconomyDeathRules.rankedMatch(null, null, TICK, "m2", null) == "m2")
    check("28/29. killer defeated earlier in the same tick (projectile in flight)", EconomyDeathRules.rankedMatch(null, capture(ranked = "m3"), TICK, null, null) == "m3")
    check("live killer lookup counts", EconomyDeathRules.rankedMatch(null, null, TICK, null, "m4") == "m4")
    check("26. ordinary death is not ranked", EconomyDeathRules.rankedMatch(capture(), capture(), TICK, null, null) == null)
    check("27. ranked off: never moves coins", !EconomyDeathRules.rankedCoinsAllowed("m1", rankedEnabled = false))
    check("32. ranked on: allowed", EconomyDeathRules.rankedCoinsAllowed("m1", rankedEnabled = true) && EconomyDeathRules.rankedCoinsAllowed(null, false))
    val pvp = BankRules.Death(HeartRules.Attribution.PVP_KILL, false, false, ProgressionRules.HeartWindow.OPEN, TeamCombatRules.HeartCheck.ELIGIBLE,
        TeamCombatRules.Relation.NOT_TEAMMATES, false)
    val s = CoinStealSettings.DEFAULTS
    check("26. ordinary PvP is eligible for coins", BankRules.eligibility(pvp, s) == BankRules.Eligibility.Eligible)
    check("27. ranked death refused with ranked coins off (default)", !s.rankedEnabled &&
        (BankRules.eligibility(pvp.copy(inRankedMatch = true), s) as BankRules.Eligibility.Refused).reason == BankRules.StealRefusal.RANKED)
    check("32. ranked death eligible with ranked coins on", BankRules.eligibility(pvp.copy(inRankedMatch = true), s.copy(rankedEnabled = true)) == BankRules.Eligibility.Eligible)
    check("39. teammates/allies via the heart check refused", (BankRules.eligibility(pvp.copy(heartCheck = TeamCombatRules.HeartCheck.ALLIES), s) as BankRules.Eligibility.Refused).reason == BankRules.StealRefusal.TEAMMATES)
    check("40. launch grace refused", (BankRules.eligibility(pvp.copy(window = ProgressionRules.HeartWindow.GRACE_PERIOD), s) as BankRules.Eligibility.Refused).reason == BankRules.StealRefusal.GRACE_PERIOD)
    // 31: a disconnect forfeit is not a PlayerDeathEvent: coins only listen to deaths and Combat NPC deaths.
    val bank = source("lifesteal/bank/LifestealBankManager.kt")
    check("31. no forfeit path moves coins", !bank.contains("onRankedForfeit") && !bank.contains("Forfeit"))
}

// ── Bounty rules: LS-ECO-08 progression, NPC facts ───────────

private fun bountyRules() {
    val ok = BountyRules.Death(victim = V, causingPlayer = A, window = ProgressionRules.HeartWindow.OPEN)
    fun refused(d: BountyRules.Death) = (BountyRules.eligibility(d) as? BountyRules.Eligibility.Refused)?.reason
    check("post-launch claim eligible", BountyRules.eligibility(ok) == BountyRules.Eligibility.Eligible(A))
    check("40. not launched refused", refused(ok.copy(window = ProgressionRules.HeartWindow.NOT_LAUNCHED)) == BountyRules.Refusal.NOT_LAUNCHED)
    check("40. grace refused", refused(ok.copy(window = ProgressionRules.HeartWindow.GRACE_PERIOD)) == BountyRules.Refusal.GRACE_PERIOD)
    check("progression unknown refused", refused(ok.copy(window = ProgressionRules.HeartWindow.UNKNOWN)) == BountyRules.Refusal.PROGRESSION_UNKNOWN)
    check("quest reuse (no window) is unchanged", BountyRules.eligibility(ok.copy(window = null)) == BountyRules.Eligibility.Eligible(A))
    check("18. ineligible killer refused", refused(ok.copy(attacker = CompetitiveEligibility.Verdict.Ineligible(setOf(CompetitiveEligibility.Reason.entries.first()), false))) ==
        BountyRules.Refusal.INELIGIBLE_ATTACKER)
    check("19. teammate refused", refused(ok.copy(team = BountyRules.Team.TEAMMATES)) == BountyRules.Refusal.TEAMMATE)
    check("19. recent teammate refused", refused(ok.copy(team = BountyRules.Team.RECENT_TEAMMATES)) == BountyRules.Refusal.TEAMMATE)
    check("team unknown refused", refused(ok.copy(team = BountyRules.Team.UNKNOWN)) == BountyRules.Refusal.TEAM_UNKNOWN)
    check("42. ranked refused", refused(ok.copy(inRankedMatch = true)) == BountyRules.Refusal.RANKED)
    check("event refused", refused(ok.copy(inEvent = true)) == BountyRules.Refusal.EVENT)
    check("NPC owner killing their own NPC refused", refused(ok.copy(causingPlayer = V)) == BountyRules.Refusal.SELF)
    check("NPC killer key is per session", LifestealBountyManager.combatNpcDedupeKey("abc") == "bounty:combatnpc:abc")
}

// ── LS-ECO-05: monitor rules ─────────────────────────────────

private fun monitorRules() {
    val c1 = uuid(10); val c2 = uuid(11)
    val none = BountyMonitorRules.Links(mapOf(c1 to 50L, c2 to 50L), emptySet(), emptySet(), false, 0)
    check("45. independent claim: no signal", BountyMonitorRules.signals(none, 3).isEmpty())
    check("45. concentration alone is not a signal", BountyMonitorRules.signals(none.copy(shares = mapOf(c1 to 100L)), 3).isEmpty())
    val hunterLink = BountyMonitorRules.signals(none.copy(linkedToHunter = setOf(c1)), 3)
    check("46. contributor/hunter shared", hunterLink.map { it.signal } == listOf(BountyMonitorRules.Signal.CONTRIBUTOR_HUNTER_SHARED) && hunterLink[0].contributors == listOf(c1))
    check("contributor/target shared", BountyMonitorRules.signals(none.copy(linkedToTarget = setOf(c2)), 3).single().signal == BountyMonitorRules.Signal.CONTRIBUTOR_TARGET_SHARED)
    check("47. hunter/target shared", BountyMonitorRules.signals(none.copy(hunterTargetLinked = true), 3).single().signal == BountyMonitorRules.Signal.HUNTER_TARGET_SHARED)
    check("48. repeated correlated claims", BountyMonitorRules.signals(none.copy(earlierCorrelatedPayouts = 2), 3).any { it.signal == BountyMonitorRules.Signal.REPEATED_GROUP })
    check("48. below the threshold no repeat", BountyMonitorRules.signals(none.copy(earlierCorrelatedPayouts = 1), 3).isEmpty())
    val concentrated = BountyMonitorRules.signals(none.copy(shares = mapOf(c1 to 90L, c2 to 10L), linkedToHunter = setOf(c1)), 3)
    check("concentrated with another signal names the top contributor", concentrated.any { it.signal == BountyMonitorRules.Signal.CONCENTRATED && it.contributors == listOf(c1) })
    val detail = BountyMonitorRules.detail(hunterLink[0], "pay-1", 12345)
    check("detail: ids, amount, signal; informational", detail.contains("signal=CONTRIBUTOR_HUNTER_SHARED") && detail.contains("payout=pay-1") &&
        detail.contains("amount=12345") && detail.contains(c1.toString()) && detail.contains("informational"))
}

// ── Database ─────────────────────────────────────────────────

private class FakeWallet(private val db: DatabaseManager) : BountyStore.Wallet {
    override fun withdraw(uuid: UUID, coins: Double): Boolean =
        db.executeUpdate("UPDATE economy SET balance = balance - ? WHERE uuid = ? AND balance >= ?", coins, uuid.toString(), coins) == 1
    override fun deposit(uuid: UUID, coins: Double): Boolean {
        db.execute("INSERT INTO economy (uuid, balance) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET balance = balance + excluded.balance", uuid.toString(), coins)
        return true
    }
}

private class World(val dir: File) {
    val db = DatabaseManager(dir, quietLogger()).also { it.start() }
    val store = BountyStore(db, FakeWallet(db))
    init {
        db.createTable("CREATE TABLE IF NOT EXISTS economy (uuid TEXT PRIMARY KEY, balance REAL NOT NULL DEFAULT 0.0)")
        db.createTable("CREATE TABLE IF NOT EXISTS player_connections (uuid TEXT NOT NULL, connection_hash TEXT NOT NULL, last_seen INTEGER NOT NULL, PRIMARY KEY (uuid, connection_hash))")
        HeartStore(db).createTables()
        LifestealTeamStore(db).createTables()
        store.createTables()
    }
    fun player(uuid: UUID, coins: Long = 0) {
        db.execute("INSERT INTO economy (uuid, balance) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET balance = excluded.balance", uuid.toString(), coins.toDouble())
        db.execute("INSERT OR IGNORE INTO lifesteal_hearts (uuid, hearts, created_at, updated_at) VALUES (?, 10, 0, 0)", uuid.toString())
    }
    fun connection(uuid: UUID, hash: String) = db.execute("INSERT OR IGNORE INTO player_connections (uuid, connection_hash, last_seen) VALUES (?, ?, 0)", uuid.toString(), hash)
    fun cents(uuid: UUID): Long = BigDecimal.valueOf(db.queryFirst("SELECT balance FROM economy WHERE uuid = ?", uuid.toString()) { it.getDouble(1) } ?: 0.0)
        .setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
    fun contribute(from: UUID, to: UUID, cents: Long, now: Long = T0, blocked: Boolean = false) =
        store.contribute(BountyStore.ContributeRequest(UUID.randomUUID().toString(), from, "c", to, "t", cents, MIN, 7 * DAY, now, liveBlock = { blocked }))
    fun claim(killer: UUID, victim: UUID, key: String, at: Long = T0 + HOUR) = store.claim(BountyStore.ClaimRequest(key, killer, "k", victim, "v", at, DAY))
    fun pay(id: String) = store.pay(id, T0 + HOUR, 300_000L) { "paid" }
    fun suspiciousRows(): List<String> = db.query("SELECT detail FROM lifesteal_bounty_audit WHERE action = 'SUSPICIOUS'") { it.getString(1) }
    fun close() = db.shutdown()
}

private fun database() {
    val root = Files.createTempDirectory("joshymc-economy-security-check").toFile()
    try {
        contributionGate(World(File(root, "a")))
        npcClaims(File(root, "b"))
        contributorClaims(World(File(root, "c")))
        payoutLinks(World(File(root, "d")))
        teamBankGate(File(root, "e"))
    } finally {
        root.deleteRecursively()
    }
}

private fun contributionGate(w: World) {
    val a = uuid(20); val t = uuid(21)
    w.player(a, 1_000_000); w.player(t)
    val refused = w.contribute(a, t, coins(50_000), blocked = true)
    check("8/14. tagged after queueing: refused inside the transaction", (refused as? ContributeResult.Refused)?.reason == BountyStore.Refusal.COMBAT)
    check("15/56. refused contribution takes nothing and creates no bounty", w.cents(a) == coins(1_000_000) && w.store.activeFor(t) == null)
    check("10. untagged contribution works normally", w.contribute(a, t, coins(50_000)) is ContributeResult.Contributed && w.cents(a) == coins(950_000))
    val before = w.store.activeFor(t)!!.totalCents
    check("8. a later tagged attempt leaves wallet and escrow unchanged", w.contribute(a, t, coins(50_000), blocked = true) is ContributeResult.Refused &&
        w.cents(a) == coins(950_000) && w.store.activeFor(t)!!.totalCents == before)
    w.close()
}

private fun npcClaims(dir: File) {
    val c = uuid(30); val owner = uuid(31); val hunter = uuid(32); val mate = uuid(33)
    val session = "npc-session-1"
    val key = LifestealBountyManager.combatNpcDedupeKey(session)
    var w = World(dir)
    listOf(c, owner, hunter, mate).forEach { w.player(it, if (it == c) 1_000_000 else 0) }
    check("21. no active bounty: nothing to claim", w.claim(hunter, owner, "bounty:combatnpc:none") == ClaimResult.NoBounty)
    w.contribute(c, owner, coins(100_000))
    w.db.execute("INSERT INTO lifesteal_teams (id, name, canonical_name, leader, status, created_at, created_by) VALUES ('crew', 'crew', 'crew', ?, 'ACTIVE', 0, 'x')", mate.toString())
    w.db.execute("INSERT INTO lifesteal_team_members (uuid, team_id, joined_at) VALUES (?, 'crew', 0)", mate.toString())
    w.db.execute("INSERT INTO lifesteal_team_members (uuid, team_id, joined_at) VALUES (?, 'crew', 0)", owner.toString())
    check("19. teammate's NPC kill refused (stored membership)", w.claim(mate, owner, "bounty:combatnpc:mate") == ClaimResult.Teammates)
    w.db.execute("DELETE FROM lifesteal_team_members WHERE uuid = ?", mate.toString())
    val claimed = w.claim(hunter, owner, key)
    check("16. eligible NPC kill claims the owner's bounty", claimed is ClaimResult.Claimed && (claimed as ClaimResult.Claimed).amountCents == coins(100_000))
    check("17/25. duplicate NPC death callback claims nothing", w.claim(hunter, owner, key) == ClaimResult.Duplicate)
    // 24/55: restart with the payout still pending, then the recovery pays it exactly once.
    w.close()
    w = World(dir)
    check("24. restart: the claim stays deduped", w.claim(hunter, owner, key) == ClaimResult.Duplicate)
    val payoutId = (claimed as ClaimResult.Claimed).payoutId
    check("53. pending payout recovered after restart", w.store.duePayouts(T0 + 2 * HOUR, 10).contains(payoutId))
    check("53. paid once", w.pay(payoutId) is PayResult.Paid && w.cents(hunter) == coins(100_000))
    check("25. a second payout attempt pays nothing", w.pay(payoutId) == PayResult.AlreadyPaid && w.cents(hunter) == coins(100_000))
    w.contribute(c, owner, coins(20_000), now = T0 + 2 * HOUR)
    val cooldown = w.claim(hunter, owner, LifestealBountyManager.combatNpcDedupeKey("npc-session-2"), at = T0 + 3 * HOUR)
    check("22. pair cooldown applies to NPC kills too", cooldown is ClaimResult.Cooldown)
    check("unique dedupe key enforced by the schema", runCatching {
        w.db.execute("INSERT INTO lifesteal_bounty_payouts (id, bounty_id, dedupe_key, killer, killer_name, target, target_name, amount_cents, state, created_at) " +
            "SELECT 'dup', bounty_id, dedupe_key, killer, killer_name, target, target_name, amount_cents, 'PENDING', created_at FROM lifesteal_bounty_payouts WHERE id = ?", payoutId)
    }.isFailure)
    w.close()
}

private fun contributorClaims(w: World) {
    val c = uuid(40); val other = uuid(41); val t = uuid(42)
    w.player(c, 1_000_000); w.player(other, 1_000_000); w.player(t)
    w.contribute(c, t, coins(60_000)); w.contribute(other, t, coins(40_000))
    val claim = w.claim(c, t, "bounty:combatnpc:contrib")
    check("23. a contributor may claim the bounty", claim is ClaimResult.Claimed && (claim as ClaimResult.Claimed).amountCents == coins(100_000))
    w.pay((claim as ClaimResult.Claimed).payoutId)
    check("23. full funded payout, own contribution included", w.cents(c) == coins(1_000_000 - 60_000 + 100_000))
    w.close()
}

private fun payoutLinks(w: World) {
    val alt = uuid(50); val main = uuid(51); val target = uuid(52); val stranger = uuid(53)
    listOf(alt, main, target, stranger).forEach { w.player(it, 1_000_000) }
    val since = T0 - DAY
    w.contribute(stranger, target, coins(20_000))
    var bounty = w.store.activeFor(target)!!.id
    val independent = w.store.payoutLinks(bounty, main, target, since)
    check("45. independent: no links", independent.linkedToHunter.isEmpty() && independent.linkedToTarget.isEmpty() && !independent.hunterTargetLinked &&
        BountyMonitorRules.signals(independent, 3).isEmpty())
    w.connection(alt, "h1"); w.connection(main, "h1")
    w.contribute(alt, target, coins(80_000))
    val linked = w.store.payoutLinks(bounty, main, target, since)
    check("46. contributor/hunter shared found (one query)", linked.linkedToHunter == setOf(alt) && linked.shares[alt] == coins(80_000))
    check("46. concentrated alt funding flagged with it", BountyMonitorRules.signals(linked, 3).map { it.signal }.toSet() ==
        setOf(BountyMonitorRules.Signal.CONTRIBUTOR_HUNTER_SHARED, BountyMonitorRules.Signal.CONCENTRATED))
    w.connection(target, "h2"); w.connection(main, "h2")
    check("47. hunter/target shared found", w.store.payoutLinks(bounty, main, target, since).hunterTargetLinked)
    // 48: the same contributor funds bounties the same hunter keeps claiming.
    check("claim 1", w.claim(main, target, "k1", at = T0 + HOUR) is ClaimResult.Claimed)
    val t2 = uuid(54); w.player(t2)
    w.contribute(alt, t2, coins(30_000), now = T0 + HOUR)
    check("claim 2", w.claim(main, t2, "k2", at = T0 + 2 * HOUR) is ClaimResult.Claimed)
    val t3 = uuid(55); w.player(t3)
    w.contribute(alt, t3, coins(30_000), now = T0 + 2 * HOUR)
    bounty = w.store.activeFor(t3)!!.id
    val repeated = w.store.payoutLinks(bounty, main, t3, since)
    check("48. repeated correlated payouts counted", repeated.earlierCorrelatedPayouts == 2 &&
        BountyMonitorRules.signals(repeated, 3).any { it.signal == BountyMonitorRules.Signal.REPEATED_GROUP })
    check("48. outside the window nothing repeats", w.store.payoutLinks(bounty, main, t3, T0 + 3 * HOUR).earlierCorrelatedPayouts == 0)
    check("50. monitoring changed no claim: both claims stand", w.db.queryFirst("SELECT COUNT(*) FROM lifesteal_bounty_payouts WHERE killer = ?", main.toString()) { it.getInt(1) } == 2)
    check("no raw address in any stored detail", w.suspiciousRows().none { it.contains("h1") || it.contains("h2") })
    w.close()
    val bare = DatabaseManager(File(w.dir, "bare"), quietLogger()).also { it.start() }
    try {
        bare.createTable("CREATE TABLE IF NOT EXISTS economy (uuid TEXT PRIMARY KEY, balance REAL NOT NULL DEFAULT 0.0)")
        HeartStore(bare).createTables(); LifestealTeamStore(bare).createTables()
        val s = BountyStore(bare, FakeWallet(bare)).also { it.createTables() }
        check("no player_connections table: no connection signals, no failure", s.payoutLinks("none", main, target, since).let { !it.hunterTargetLinked && it.linkedToHunter.isEmpty() })
    } finally {
        bare.shutdown()
    }
}

private class TeamWallet(private val db: DatabaseManager) : TeamProgressionStore.Wallet {
    override fun withdraw(uuid: UUID, coins: Double): Boolean =
        db.executeUpdate("UPDATE economy SET balance = balance - ? WHERE uuid = ? AND balance >= ?", coins, uuid.toString(), coins) == 1
}

private fun teamBankGate(dir: File) {
    val db = DatabaseManager(dir, quietLogger()).also { it.start() }
    try {
        db.createTable("CREATE TABLE IF NOT EXISTS economy (uuid TEXT PRIMARY KEY, balance REAL NOT NULL DEFAULT 0.0)")
        val teams = LifestealTeamStore(db).also { it.createTables() }
        val store = TeamProgressionStore(db, TeamWallet(db)).also { it.createTables() }
        val leader = uuid(60)
        db.execute("INSERT INTO economy (uuid, balance) VALUES (?, 500000)", leader.toString())
        val team = (teams.create(UUID.randomUUID().toString(), "Vault", leader, "L", T0 - DAY) as LifestealTeamStore.CreateResult.Created).team.id
        val s = TeamProgressionSettings.DEFAULTS
        fun wallet() = db.queryFirst("SELECT balance FROM economy WHERE uuid = ?", leader.toString()) { it.getDouble(1) }
        val blocked = store.deposit(TeamProgressionStore.DepositRequest("r1", leader, "L", coins(100_000), T0, liveBlock = { true }), s)
        check("9/14. tagged team bank deposit refused inside the transaction", blocked == TeamProgressionStore.DepositResult.Blocked)
        check("15. refused deposit leaves wallet and team bank unchanged", wallet() == 500_000.0 && store.bankBalance(team) == 0L)
        check("refused reference stays usable (nothing recorded)", store.deposit(TeamProgressionStore.DepositRequest("r1", leader, "L", coins(100_000), T0), s) is TeamProgressionStore.DepositResult.Deposited)
        check("untagged deposit moves the coins", wallet() == 400_000.0 && store.bankBalance(team) == coins(100_000))
    } finally {
        db.shutdown()
    }
}

// ── Wiring ───────────────────────────────────────────────────

private fun wiring() {
    val bankListener = source("lifesteal/bank/LifestealBankListener.kt")
    check("capture at LOWEST of the death", bankListener.contains("@EventHandler(priority = EventPriority.LOWEST)\n    fun onDeathCapture(event: PlayerDeathEvent) = manager.deaths.capture(event)"))
    val bank = source("lifesteal/bank/LifestealBankManager.kt")
    check("coins use the shared resolution", bank.contains("val resolution = deaths.resolve(event)") && bank.contains("resolution.rankedMatch != null"))
    check("33/43. coin dedupe: victim + tick only (same key for direct and combat-tag credit)", bank.contains("\"coins:pvp:\$bootId:\${victim.uniqueId}:\${resolution.tick}\""))
    check("44. Combat NPC coins: direct killer only, no environmental credit", bank.contains("if (!stealing || killer == null) return") && bank.contains("\"coins:combatnpc:\$sessionId\""))
    check("shielding helpers exist", bank.contains("fun shieldingRefusal(player: Player, action: BankRules.ShieldAction)") && bank.contains("fun combatTaggedNow(uuid: UUID)"))
    val bounty = source("lifesteal/bounty/LifestealBountyManager.kt")
    check("bounty claims use the shared resolution", bounty.contains("plugin.lifestealBankManager.deaths.resolve(event)"))
    check("43. bounty dedupe: victim + tick only", bounty.contains("\"bounty:\$bootId:\${victim.uniqueId}:\${resolution?.tick ?: Bukkit.getCurrentTick()}\""))
    check("LS-ECO-08: progression window on every claim path", bounty.contains("window = progressionWindow(now)") && bounty.split("progressionWindow(now)").size >= 3)
    check("LS-ECO-02: contribution gate before queueing and in the transaction", bounty.contains("shieldingRefusal(player)?.let { return refuse(it) }") &&
        bounty.contains("liveBlock = { contributorTagged(contributorId) }"))
    check("LS-ECO-03: Combat NPC claim uses the NPC session key and a direct killer only",
        bounty.contains("val killer = event.killer ?: return") && bounty.contains("combatNpcDedupeKey(event.sessionId)"))
    check("LS-ECO-05: monitoring runs after the claim and never refuses", bounty.contains("monitorPayout(request, claim, s)") &&
        !Regex("fun monitorPayout[\\s\\S]*?\\n    }\\n").find(bounty)!!.value.let { it.contains("store.claim") || it.contains("withdraw") || it.contains("ban") || it.contains("freeze") })
    check("LS-ECO-05: staff notices use the bounty admin permission, throttled", bounty.contains("LifestealBountyCommand.ADMIN_PERMISSION") && bounty.contains("monitorThrottle.allow("))
    val bountyListener = source("lifesteal/bounty/LifestealBountyListener.kt")
    check("bounty listener handles Combat NPC deaths", bountyListener.contains("fun onCombatNpcDeath(event: com.liam.joshymc.lifesteal.combat.npc.LifestealCombatNpcDeathEvent) = manager.onCombatNpcDeath(event)"))
    val team = source("lifesteal/teams/progression/LifestealTeamProgressionManager.kt")
    check("team bank deposit gate before queueing and in the transaction", team.contains("shieldingRefusal(player)?.let { return Msg.error(player, it) }") &&
        team.contains("liveBlock = { memberTagged(uuid) }"))
    check("44. heart Combat NPC path still uses its own direct-killer rule (untouched)", source("lifesteal/hearts/LifestealHeartManager.kt").contains("dedupeKey = \"combatnpc:\$sessionId\""))
    check("economy attribution reuses the heart rules read-only", source("lifesteal/economy/EconomyDeathRules.kt").contains("HeartRules.combatTagKiller(") &&
        !source("lifesteal/economy/EconomyDeathAttribution.kt").contains("lifestealHeartManager"))
    check("bounty money formatting unchanged", BountyMoney.format(coins(10_000)) == "\$10,000")
}
