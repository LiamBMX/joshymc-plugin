// Checks the Lifesteal Protected Bank and PvP coin stealing (issue #1427): money conversion, fee
// and percentage math, deposit/withdrawal planning (full balances, insufficient funds, invalid and
// zero amounts, capacity and a lowered capacity, fees that would eat the amount), the bank and
// pvp-coins sections of lifesteal.yml (bundled defaults, invalid values, enable/disable, the season
// policy), the access rules (combat tag, ranked matches, events, freezes, death), coin-steal
// eligibility (the heart system's attribution, events, ranked off by default, the launch window,
// teammates/allies/unknown teams, shared connections, independent of the 1-heart minimum), and
// against a real SQLite database with a fake wallet on the `economy` table: deposits, withdrawals,
// "all", fees as a sink, capacity, duplicate transaction ids, PvP steals (10% of the wallet, the
// bank untouched, duplicate death events, the same-victim cooldown, empty wallets, max per kill),
// rollback on economy failures, restart persistence, append-only history, admin adjustments, offline
// notices, coin conservation under concurrent deposits, withdrawals and steals, and the wiring
// (Lifesteal only, Survival economy untouched). The Gradle build has no test source set, so it runs
// through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-bank-check.init.gradle.kts lifestealBankCheck
//
// Exits non-zero on the first failed check. Live Paper behaviour (menus, chat prompts, real deaths)
// is NOT covered here; see docs/lifesteal-bank.md, "Manual tests".

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.bank.BankConfig
import com.liam.joshymc.lifesteal.bank.BankMenuLayout
import com.liam.joshymc.lifesteal.bank.BankRules
import com.liam.joshymc.lifesteal.bank.BankRules.Direction
import com.liam.joshymc.lifesteal.bank.BankRules.PlanResult
import com.liam.joshymc.lifesteal.bank.BankRules.Refusal
import com.liam.joshymc.lifesteal.bank.BankRules.StealRefusal
import com.liam.joshymc.lifesteal.bank.BankSettings
import com.liam.joshymc.lifesteal.bank.BankStore
import com.liam.joshymc.lifesteal.bank.BankStore.StealResult
import com.liam.joshymc.lifesteal.bank.BankStore.TransferResult
import com.liam.joshymc.lifesteal.bank.CoinStealSettings
import com.liam.joshymc.lifesteal.bounty.BountyMoney
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.progression.ProgressionRules
import com.liam.joshymc.lifesteal.teams.TeamCombatRules
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.tutorial.TutorialRules
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

private fun throws(block: () -> Unit): Boolean = try { block(); false } catch (_: Exception) { true }
private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }
private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private const val MIN = 60_000L
private fun coins(c: Long) = c * 100 // whole coins -> cents

fun main() {
    money()
    planning()
    config()
    access()
    eligibility()
    layout()
    database()
    concurrency()
    wiring()
    println("Lifesteal bank checks passed ($checks checks).")
    exitProcess(0)
}

// ── Money, fees, percentages ─────────────────────────────────

private fun money() {
    check("wallet: whole cents rounded down", BankRules.walletCents(100.999) == 10_099L && BankRules.walletCents(100.0) == 10_000L)
    check("wallet: NaN/Infinity/negative count as 0", BankRules.walletCents(Double.NaN) == 0L && BankRules.walletCents(Double.POSITIVE_INFINITY) == 0L && BankRules.walletCents(-5.0) == 0L)
    check("wallet: huge balances cap at the safe maximum", BankRules.walletCents(1e300) == BountyMoney.MAX_CENTS)
    check("percent: whole and two decimals", BankRules.percentToBps(10) == 1000 && BankRules.percentToBps(2.5) == 250 && BankRules.percentToBps("0.25") == 25 && BankRules.percentToBps("5%") == 500)
    check("percent: three decimals, negatives, NaN, text refused",
        BankRules.percentToBps(0.125) == null && BankRules.percentToBps(-1) == null && BankRules.percentToBps(Double.NaN) == null && BankRules.percentToBps("x") == null)
    check("fee: zero when not configured", BankRules.fee(coins(1000), 0) == 0L)
    check("fee: exact", BankRules.fee(coins(1000), 250) == coins(25))
    check("fee: rounded up, so splitting can't dodge it", BankRules.fee(1, 1) == 1L && BankRules.fee(99, 100) == 1L && BankRules.fee(101, 100) == 2L)
    check("fee: never more than the amount at <= 100%", (1L..500L).all { BankRules.fee(it, 5000) <= it })
    check("fee: largest amount doesn't overflow", BankRules.fee(BountyMoney.MAX_CENTS, BankConfig.MAX_FEE_BPS) == BountyMoney.MAX_CENTS / 2)
    check("percentOf: rounded down", BankRules.percentOf(999, 10) == 99L && BankRules.percentOf(0, 50) == 0L && BankRules.percentOf(coins(100), 25) == coins(25))
    check("percentOf: out of range refused", throws { BankRules.percentOf(100, 0) } && throws { BankRules.percentOf(100, 101) })
    check("formatBps", BankRules.formatBps(1000) == "10%" && BankRules.formatBps(250) == "2.5%" && BankRules.formatBps(0) == "0%")
    // Typed amounts reuse the bounty system's exact parser.
    check("parse: decimals, NaN, Infinity, negatives, overflow refused",
        BountyMoney.parse("1.005") == BountyMoney.Parsed.TooPrecise && BountyMoney.parse("NaN") == BountyMoney.Parsed.Invalid &&
            BountyMoney.parse("Infinity") == BountyMoney.Parsed.Invalid && BountyMoney.parse("-5") == BountyMoney.Parsed.Negative &&
            BountyMoney.parse("0") == BountyMoney.Parsed.Zero && BountyMoney.parse("1e400") == BountyMoney.Parsed.Invalid &&
            BountyMoney.parse("99999t") == BountyMoney.Parsed.TooLarge && BountyMoney.parse("1.5k") == BountyMoney.Parsed.Ok(150_000L))
    val s = CoinStealSettings.DEFAULTS
    check("steal: 10% of 100,000 is 10,000", BankRules.stealAmount(coins(100_000), s) == coins(10_000))
    check("steal: rounded down", BankRules.stealAmount(9, s) == 0L && BankRules.stealAmount(19, s) == 1L)
    check("steal: empty wallet steals nothing", BankRules.stealAmount(0, s) == 0L)
    check("steal: max per kill", BankRules.stealAmount(coins(100_000), s.copy(maxPerKillCents = coins(500))) == coins(500))
    check("steal: 0% moves nothing", BankRules.stealAmount(coins(100), s.copy(walletLossBps = 0)) == 0L)
    check("steal: 100% of the largest wallet doesn't overflow", BankRules.stealAmount(BountyMoney.MAX_CENTS, s.copy(walletLossBps = 10_000)) == BountyMoney.MAX_CENTS)
}

// ── Planning ─────────────────────────────────────────────────

private fun plan(d: Direction, req: Long?, wallet: Long, bank: Long, cap: Long? = null, fee: Int = 0) = BankRules.plan(d, req, wallet, bank, cap, fee)
private fun ok(r: PlanResult) = (r as? PlanResult.Ok)?.plan
private fun refused(r: PlanResult) = (r as? PlanResult.Refused)?.reason

private fun planning() {
    check("deposit: exact", ok(plan(Direction.DEPOSIT, 500, 1000, 0)) == BankRules.Plan(Direction.DEPOSIT, 500, 0, 500))
    check("deposit: full balance", ok(plan(Direction.DEPOSIT, 1000, 1000, 0))?.netCents == 1000L)
    check("deposit: all", ok(plan(Direction.DEPOSIT, null, 1000, 5))?.amountCents == 1000L)
    check("deposit: more than the wallet", refused(plan(Direction.DEPOSIT, 1001, 1000, 0)) == Refusal.INSUFFICIENT_WALLET)
    check("deposit: zero", refused(plan(Direction.DEPOSIT, 0, 1000, 0)) == Refusal.ZERO)
    check("deposit: negative", refused(plan(Direction.DEPOSIT, -1, 1000, 0)) == Refusal.INVALID_AMOUNT)
    check("deposit: above the safe maximum", refused(plan(Direction.DEPOSIT, BountyMoney.MAX_CENTS + 1, Long.MAX_VALUE, 0)) == Refusal.INVALID_AMOUNT)
    check("deposit all: empty wallet", refused(plan(Direction.DEPOSIT, null, 0, 0)) == Refusal.ZERO)
    check("deposit: fee comes out of the amount", ok(plan(Direction.DEPOSIT, 1000, 1000, 0, fee = 250)) == BankRules.Plan(Direction.DEPOSIT, 1000, 25, 975))
    check("deposit: fee would eat everything", refused(plan(Direction.DEPOSIT, 1, 1000, 0, fee = 5000)) == Refusal.FEE_EXCEEDS_AMOUNT)
    check("deposit: capacity", refused(plan(Direction.DEPOSIT, 600, 1000, 500, cap = 1000)) == Refusal.CAPACITY)
    check("deposit: exactly fills capacity", ok(plan(Direction.DEPOSIT, 500, 1000, 500, cap = 1000))?.netCents == 500L)
    check("deposit all: only what fits", ok(plan(Direction.DEPOSIT, null, 1000, 700, cap = 1000))?.amountCents == 300L)
    check("deposit all: what fits after the fee", ok(plan(Direction.DEPOSIT, null, coins(1000), 0, cap = coins(100), fee = 1000)).let { it != null && it.netCents <= coins(100) && it.netCents >= coins(100) - 1 })
    check("deposit: full bank", refused(plan(Direction.DEPOSIT, null, 1000, 1000, cap = 1000)) == Refusal.CAPACITY)
    check("lowered capacity: over the limit keeps coins, refuses deposits, allows withdrawals",
        refused(plan(Direction.DEPOSIT, 1, 1000, 2000, cap = 1000)) == Refusal.CAPACITY && ok(plan(Direction.WITHDRAW, 2000, 0, 2000, cap = 1000))?.netCents == 2000L)
    check("maxDeposit: no room, nothing", BankRules.maxDeposit(10_000, 0, 333) == 0L)
    check("maxDeposit: monotonic and maximal", (1L..300L step 7).all { room ->
        val m = BankRules.maxDeposit(10_000, room, 333)
        m - BankRules.fee(m, 333) <= room && (m == 10_000L || (m + 1) - BankRules.fee(m + 1, 333) > room)
    })
    check("withdraw: exact", ok(plan(Direction.WITHDRAW, 300, 0, 1000)) == BankRules.Plan(Direction.WITHDRAW, 300, 0, 300))
    check("withdraw: all", ok(plan(Direction.WITHDRAW, null, 0, 1000))?.amountCents == 1000L)
    check("withdraw: more than the bank", refused(plan(Direction.WITHDRAW, 1001, 0, 1000)) == Refusal.INSUFFICIENT_BANK)
    check("withdraw all: empty bank", refused(plan(Direction.WITHDRAW, null, 0, 0)) == Refusal.ZERO)
    check("withdraw: fee", ok(plan(Direction.WITHDRAW, 1000, 0, 1000, fee = 100)) == BankRules.Plan(Direction.WITHDRAW, 1000, 10, 990))
    check("withdraw: wallet overflow", refused(plan(Direction.WITHDRAW, 10, BountyMoney.MAX_CENTS, 1000)) == Refusal.TOO_LARGE)
    check("amount = fee + net, never negative", listOf(1L, 7L, 999L, 123_456L).all { a ->
        listOf(0, 1, 250, 5000).all { f ->
            val r = ok(plan(Direction.DEPOSIT, a, a, 0, fee = f))
            r == null || (r.feeCents + r.netCents == r.amountCents && r.feeCents >= 0 && r.netCents > 0)
        }
    })
}

// ── Config ───────────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun bundled(): Map<String, Any?> {
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    return Yaml().load(text)
}

private fun node(m: Map<String, Any?>, path: String): Any? = path.split('.').fold<String, Any?>(m) { n, key -> (n as? Map<*, *>)?.get(key) }

@Suppress("UNCHECKED_CAST")
private fun edited(path: String, value: Any?): Map<String, Any?> {
    fun copy(x: Any?): Any? = if (x is Map<*, *>) LinkedHashMap((x as Map<String, Any?>).mapValues { copy(it.value) }) else x
    val m = copy(bundled()) as MutableMap<String, Any?>
    val keys = path.split('.')
    var cur = m
    for (k in keys.dropLast(1)) cur = cur[k] as MutableMap<String, Any?>
    if (value == null) cur.remove(keys.last()) else cur[keys.last()] = value
    return m
}

private fun bank(m: Map<String, Any?>) = BankConfig.parseBank { node(m, "bank.$it") }
private fun coinsCfg(m: Map<String, Any?>) = BankConfig.parseCoinSteal { node(m, "pvp-coins.$it") }

private fun config() {
    val b = bank(bundled())
    check("bundled bank: valid, no problems", b.valid && b.problems.isEmpty())
    check("bundled bank: the issue's defaults (unlimited, no fees, combat/ranked blocked, no reset)",
        b.settings == BankSettings.DEFAULTS && b.settings!!.maxCapacityCents == null && b.settings!!.depositFeeBps == 0 && b.settings!!.withdrawalFeeBps == 0 &&
            b.settings!!.blockDuringCombat && b.settings!!.blockDuringRanked && b.settings!!.seasonReset == BankSettings.SeasonReset.NONE)
    val c = coinsCfg(bundled())
    check("bundled pvp-coins: valid, no problems", c.valid && c.problems.isEmpty())
    check("bundled pvp-coins: 10%, ranked off, farming blocks on", c.settings == CoinStealSettings.DEFAULTS && c.settings!!.walletLossBps == 1000 &&
        !c.settings!!.rankedEnabled && c.settings!!.blockTeamFarming && c.settings!!.antiFarmingEnabled && c.settings!!.sameVictimCooldownMillis == 30 * MIN)
    check("missing key: warning + default", bank(edited("bank.deposit-fee-percent", null)).let { it.valid && it.settings!!.depositFeeBps == 0 && it.problems.any { p -> !p.severe } })
    check("capacity limit", bank(edited("bank.max-capacity", 5000)).settings?.maxCapacityCents == coins(5000))
    check("fees configurable independently", bank(edited("bank.withdrawal-fee-percent", 2.5)).settings.let { it?.withdrawalFeeBps == 250 && it.depositFeeBps == 0 })
    for ((path, value) in listOf(
        "bank.enabled" to "yes", "bank.max-capacity" to 0, "bank.max-capacity" to -2, "bank.max-capacity" to "lots",
        "bank.deposit-fee-percent" to 51, "bank.deposit-fee-percent" to -1, "bank.withdrawal-fee-percent" to 0.001, "bank.season-reset" to "wipe",
        "bank.admin-max-adjustment" to 0, "bank.block-during-combat" to 1,
    )) {
        check("invalid $path=$value: severe, bank section invalid", bank(edited(path, value)).let { !it.valid && it.problems.any { p -> p.severe } })
    }
    for ((path, value) in listOf(
        "pvp-coins.wallet-loss-percent" to 101, "pvp-coins.wallet-loss-percent" to "ten", "pvp-coins.max-per-kill" to 0,
        "pvp-coins.same-victim-cooldown-minutes" to -1, "pvp-coins.ranked-enabled" to "no", "pvp-coins.repeat-kill-threshold" to 1,
    )) {
        check("invalid $path=$value: severe, pvp-coins section invalid", coinsCfg(edited(path, value)).let { !it.valid && it.problems.any { p -> p.severe } })
    }
    check("an invalid bank section never invalidates pvp-coins", coinsCfg(edited("bank.enabled", "yes")).valid)
    check("an invalid pvp-coins section never invalidates bank", bank(edited("pvp-coins.wallet-loss-percent", 500)).valid)
    check("banking can be disabled", bank(edited("bank.enabled", false)).settings?.enabled == false)
    check("coin stealing can be disabled separately", coinsCfg(edited("pvp-coins.enabled", false)).settings?.enabled == false &&
        bank(edited("pvp-coins.enabled", false)).settings?.enabled == true)
    check("no automatic wipe policy exists", BankSettings.SeasonReset.entries.map { it.id } == listOf("none"))
    check("ranked coin loss configurable", coinsCfg(edited("pvp-coins.ranked-enabled", true)).settings?.rankedEnabled == true)
}

// ── Access ───────────────────────────────────────────────────

private val free = BankRules.AccessState(inLifesteal = true, dead = false, combatTagged = false, inRankedMatch = false, inEvent = false, frozen = false)

private fun access() {
    val s = BankSettings.DEFAULTS
    check("free player may bank", BankRules.accessBlock(free, s) == null)
    check("combat-tagged refused", BankRules.accessBlock(free.copy(combatTagged = true), s) == BankRules.Block.COMBAT)
    check("ranked match refused", BankRules.accessBlock(free.copy(inRankedMatch = true), s) == BankRules.Block.RANKED)
    check("event refused", BankRules.accessBlock(free.copy(inEvent = true), s) == BankRules.Block.EVENT)
    check("frozen refused", BankRules.accessBlock(free.copy(frozen = true), s) == BankRules.Block.FROZEN)
    check("dead refused", BankRules.accessBlock(free.copy(dead = true), s) == BankRules.Block.DEAD)
    check("outside Lifesteal refused", BankRules.accessBlock(free.copy(inLifesteal = false), s) == BankRules.Block.NOT_LIFESTEAL)
    check("combat block can be turned off", BankRules.accessBlock(free.copy(combatTagged = true), s.copy(blockDuringCombat = false)) == null)
    check("ranked block can be turned off", BankRules.accessBlock(free.copy(inRankedMatch = true), s.copy(blockDuringRanked = false)) == null)
    check("events always block", BankRules.accessBlock(free.copy(inEvent = true), s.copy(blockDuringCombat = false, blockDuringRanked = false)) == BankRules.Block.EVENT)
}

// ── Coin steal eligibility ───────────────────────────────────

private val pvp = BankRules.Death(
    attribution = HeartRules.Attribution.PVP_KILL, inEvent = false, inRankedMatch = false, window = ProgressionRules.HeartWindow.OPEN,
    heartCheck = TeamCombatRules.HeartCheck.ELIGIBLE, relation = TeamCombatRules.Relation.NOT_TEAMMATES, sharedConnection = false,
)

private fun refusal(d: BankRules.Death, s: CoinStealSettings = CoinStealSettings.DEFAULTS) = (BankRules.eligibility(d, s) as? BankRules.Eligibility.Refused)?.reason

private fun eligibility() {
    check("legitimate PvP kill is eligible", BankRules.eligibility(pvp, CoinStealSettings.DEFAULTS) == BankRules.Eligibility.Eligible)
    check("disabled", refusal(pvp, CoinStealSettings.DEFAULTS.copy(enabled = false)) == StealRefusal.DISABLED)
    for (a in HeartRules.Attribution.entries.filter { it != HeartRules.Attribution.PVP_KILL }) {
        check("attribution $a never steals", refusal(pvp.copy(attribution = a)) == StealRefusal.NOT_PVP)
    }
    // The same attribution as the heart system: environment, self and NPC kills.
    val base = HeartRules.Death(UUID(1, 1), true, true, UUID(1, 2), true, true, true, false)
    check("heart attribution: environment", HeartRules.attribute(base.copy(causingPlayer = null)) == HeartRules.Attribution.ENVIRONMENT)
    check("heart attribution: suicide", HeartRules.attribute(base.copy(causingPlayer = base.victim)) == HeartRules.Attribution.SELF)
    check("heart attribution: NPC killer", HeartRules.attribute(base.copy(killerIsNpc = true)) == HeartRules.Attribution.INVALID_KILLER)
    check("events", refusal(pvp.copy(inEvent = true)) == StealRefusal.EVENT)
    check("ranked off by default", refusal(pvp.copy(inRankedMatch = true)) == StealRefusal.RANKED)
    check("ranked when enabled", BankRules.eligibility(pvp.copy(inRankedMatch = true), CoinStealSettings.DEFAULTS.copy(rankedEnabled = true)) == BankRules.Eligibility.Eligible)
    check("not launched", refusal(pvp.copy(window = ProgressionRules.HeartWindow.NOT_LAUNCHED)) == StealRefusal.NOT_LAUNCHED)
    check("grace", refusal(pvp.copy(window = ProgressionRules.HeartWindow.GRACE_PERIOD)) == StealRefusal.GRACE_PERIOD)
    check("progression unknown", refusal(pvp.copy(window = ProgressionRules.HeartWindow.UNKNOWN)) == StealRefusal.PROGRESSION_UNKNOWN)
    check("teammates (heart verdict)", refusal(pvp.copy(heartCheck = TeamCombatRules.HeartCheck.TEAMMATES, relation = TeamCombatRules.Relation.TEAMMATES)) == StealRefusal.TEAMMATES)
    check("allies (heart verdict)", refusal(pvp.copy(heartCheck = TeamCombatRules.HeartCheck.ALLIES, relation = TeamCombatRules.Relation.ALLIES)) == StealRefusal.TEAMMATES)
    check("unknown teams", refusal(pvp.copy(heartCheck = TeamCombatRules.HeartCheck.UNKNOWN)) == StealRefusal.TEAM_UNKNOWN &&
        refusal(pvp.copy(relation = TeamCombatRules.Relation.UNKNOWN)) == StealRefusal.TEAM_UNKNOWN)
    check("team farming blocked even when the team policy lets hearts move",
        refusal(pvp.copy(heartCheck = TeamCombatRules.HeartCheck.ELIGIBLE, relation = TeamCombatRules.Relation.RECENT_TEAMMATES)) == StealRefusal.TEAMMATES)
    check("team farming block can be turned off",
        BankRules.eligibility(pvp.copy(relation = TeamCombatRules.Relation.TEAMMATES), CoinStealSettings.DEFAULTS.copy(blockTeamFarming = false)) == BankRules.Eligibility.Eligible)
    check("shared connection only logged by default", BankRules.eligibility(pvp.copy(sharedConnection = true), CoinStealSettings.DEFAULTS) == BankRules.Eligibility.Eligible)
    check("shared connection blocked when configured",
        refusal(pvp.copy(sharedConnection = true), CoinStealSettings.DEFAULTS.copy(blockSharedConnections = true)) == StealRefusal.SHARED_CONNECTION)
    // The permanent one-heart minimum is untouched and independent: a 1-heart victim keeps their heart, coins still move.
    val limits = HeartRules.Limits(10, 1, 20)
    check("1-heart victim: no heart moves", HeartRules.decide(1, 10, 0, limits) == HeartRules.Decision.VictimAtMinimum)
    check("1-heart victim: coins are decided on their own", BankRules.eligibility(pvp, CoinStealSettings.DEFAULTS) == BankRules.Eligibility.Eligible)
    check("rules take no heart input", BankRules.Death::class.java.declaredFields.none { it.name.contains("heart", ignoreCase = true) && it.name != "heartCheck" })
}

private fun layout() {
    check("menu layout: slots distinct and inside the menus", BankMenuLayout.valid())
    check("menu: 10/25/50%, All, Custom", BankMenuLayout.PERCENTS == listOf(10, 25, 50))
}

// ── Database ─────────────────────────────────────────────────

private class FakeWallet(private val db: DatabaseManager) : BankStore.Wallet {
    @Volatile var failDeposits = false
    override fun balance(uuid: UUID): Double = db.queryFirst("SELECT balance FROM economy WHERE uuid = ?", uuid.toString()) { it.getDouble(1) } ?: 0.0
    override fun withdraw(uuid: UUID, coins: Double): Boolean {
        if (!coins.isFinite() || coins < 0) return false
        return db.executeUpdate("UPDATE economy SET balance = balance - ? WHERE uuid = ? AND balance >= ?", coins, uuid.toString(), coins) == 1
    }
    override fun deposit(uuid: UUID, coins: Double): Boolean {
        if (!coins.isFinite() || coins < 0) return false
        if (failDeposits) throw java.sql.SQLException("simulated economy failure")
        db.execute("INSERT INTO economy (uuid, balance) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET balance = balance + excluded.balance", uuid.toString(), coins)
        return true
    }
}

private class World(val dir: File) {
    val db = DatabaseManager(dir, quietLogger()).also { it.start() }
    val wallet = FakeWallet(db)
    val store = BankStore(db, wallet)
    init {
        db.createTable("CREATE TABLE IF NOT EXISTS economy (uuid TEXT PRIMARY KEY, balance REAL NOT NULL DEFAULT 0.0)")
        store.createTables()
    }
    fun player(uuid: UUID, coins: Long) {
        db.execute("INSERT INTO economy (uuid, balance) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET balance = excluded.balance", uuid.toString(), coins.toDouble())
    }
    fun wallet(uuid: UUID): Long = BigDecimal.valueOf(db.queryFirst("SELECT balance FROM economy WHERE uuid = ?", uuid.toString()) { it.getDouble(1) } ?: 0.0)
        .setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
    fun bank(uuid: UUID) = store.bankCents(uuid)
    fun move(uuid: UUID, d: Direction, cents: Long?, fee: Int = 0, cap: Long? = null, tx: String = UUID.randomUUID().toString()) =
        store.transfer(BankStore.TransferRequest(tx, uuid, d, cents, cap, fee, 1_000L, "season-1", "check"))
    fun steal(killer: UUID, victim: UUID, key: String = UUID.randomUUID().toString(), now: Long = 1_000_000L, s: CoinStealSettings = CoinStealSettings.DEFAULTS) =
        store.steal(BankStore.StealRequest(key, killer, victim, s, now, "season-1", false, "check"))
    fun close() = db.shutdown()
}

private fun uuid(n: Int) = UUID(0x1427L, n.toLong())

private fun database() {
    val root = Files.createTempDirectory("joshymc-bank-check").toFile()
    try {
        transfers(World(File(root, "a")))
        steals(World(File(root, "b")))
        failures(World(File(root, "c")))
        restart(File(root, "d"))
        admin(World(File(root, "e")))
    } finally {
        root.deleteRecursively()
    }
}

private fun transfers(w: World) {
    val a = uuid(1)
    w.player(a, 100_000)
    val d = w.move(a, Direction.DEPOSIT, coins(40_000)) as? TransferResult.Done
    check("deposit: wallet -> bank", d != null && w.wallet(a) == coins(60_000) && w.bank(a) == coins(40_000) && d.bankAfterCents == coins(40_000) && d.walletAfterCents == coins(60_000))
    check("deposit: more than the wallet refused, nothing moved",
        (w.move(a, Direction.DEPOSIT, coins(60_001)) as? TransferResult.Refused)?.reason == Refusal.INSUFFICIENT_WALLET && w.wallet(a) == coins(60_000) && w.bank(a) == coins(40_000))
    check("withdraw: bank -> wallet", w.move(a, Direction.WITHDRAW, coins(10_000)) is TransferResult.Done && w.wallet(a) == coins(70_000) && w.bank(a) == coins(30_000))
    check("withdraw: more than the bank refused", (w.move(a, Direction.WITHDRAW, coins(30_001)) as? TransferResult.Refused)?.reason == Refusal.INSUFFICIENT_BANK && w.bank(a) == coins(30_000))
    check("deposit all", w.move(a, Direction.DEPOSIT, null) is TransferResult.Done && w.wallet(a) == 0L && w.bank(a) == coins(100_000))
    check("withdraw all", w.move(a, Direction.WITHDRAW, null) is TransferResult.Done && w.wallet(a) == coins(100_000) && w.bank(a) == 0L)
    check("zero refused", (w.move(a, Direction.DEPOSIT, 0) as? TransferResult.Refused)?.reason == Refusal.ZERO)
    // A double click / retried request with the same id completes once.
    val tx = "gui-tx-1"
    check("same transaction id: first completes", w.move(a, Direction.DEPOSIT, coins(1_000), tx = tx) is TransferResult.Done)
    check("same transaction id: repeat is a duplicate, nothing moved twice", w.move(a, Direction.DEPOSIT, coins(1_000), tx = tx) == TransferResult.Duplicate && w.bank(a) == coins(1_000))
    // Fees are a sink: amount leaves, net arrives.
    val f = w.move(a, Direction.DEPOSIT, coins(10_000), fee = 250) as? TransferResult.Done
    check("deposit fee: net arrives, fee recorded", f != null && f.plan.feeCents == coins(250) && w.bank(a) == coins(1_000) + coins(9_750) && w.wallet(a) == coins(89_000))
    val g = w.move(a, Direction.WITHDRAW, coins(1_000), fee = 1000) as? TransferResult.Done
    check("withdraw fee", g != null && g.plan.netCents == coins(900) && w.wallet(a) == coins(89_900) && w.bank(a) == coins(9_750))
    val hist = w.store.history(a, 0, 50)
    check("history records fees", hist.any { it.type == BankStore.EntryType.DEPOSIT && it.feeCents == coins(250) } && hist.any { it.type == BankStore.EntryType.WITHDRAW && it.feeCents == coins(100) })
    check("history: season, tx id, state, balances", hist.all { it.season == "season-1" && it.state == "COMPLETED" && it.bankAfterCents != null } && hist.any { it.txId == tx })
    // Capacity
    val b = uuid(2)
    w.player(b, 10_000)
    check("capacity: refused above", (w.move(b, Direction.DEPOSIT, coins(6_000), cap = coins(5_000)) as? TransferResult.Refused)?.reason == Refusal.CAPACITY)
    check("capacity: all fills to the limit", w.move(b, Direction.DEPOSIT, null, cap = coins(5_000)) is TransferResult.Done && w.bank(b) == coins(5_000) && w.wallet(b) == coins(5_000))
    check("capacity lowered: coins stay, deposits refused, withdrawals work",
        w.move(b, Direction.DEPOSIT, coins(1), cap = coins(1_000)) is TransferResult.Refused && w.bank(b) == coins(5_000) &&
            w.move(b, Direction.WITHDRAW, coins(4_500), cap = coins(1_000)) is TransferResult.Done && w.bank(b) == coins(500))
    // Append-only history and undeletable accounts.
    check("history rows can't be deleted", throws { w.db.execute("DELETE FROM lifesteal_bank_ledger") })
    check("history amounts can't be edited", throws { w.db.execute("UPDATE lifesteal_bank_ledger SET amount_cents = 1") })
    check("bank accounts can't be deleted", throws { w.db.execute("DELETE FROM lifesteal_bank_accounts") })
    check("bank balances can't go negative", throws { w.db.execute("UPDATE lifesteal_bank_accounts SET balance_cents = -1 WHERE uuid = ?", b.toString()) })
    w.close()
}

private fun steals(w: World) {
    val killer = uuid(10)
    val victim = uuid(11)
    w.player(killer, 1_000)
    w.player(victim, 600_000)
    check("victim protects 500,000", w.move(victim, Direction.DEPOSIT, coins(500_000)) is TransferResult.Done && w.wallet(victim) == coins(100_000))
    val r = w.steal(killer, victim, key = "death-1") as? StealResult.Stolen
    check("steal: 10% of the wallet", r != null && r.amountCents == coins(10_000))
    check("steal: victim keeps 90,000 in the wallet", w.wallet(victim) == coins(90_000) && r!!.victimWalletAfterCents == coins(90_000))
    check("steal: killer receives it", w.wallet(killer) == coins(11_000) && r!!.killerWalletAfterCents == coins(11_000))
    check("steal: protected bank untouched", w.bank(victim) == coins(500_000) && w.bank(killer) == 0L)
    check("steal: duplicate death event moves nothing", w.steal(killer, victim, key = "death-1") == StealResult.Duplicate && w.wallet(victim) == coins(90_000))
    check("steal: same victim within the cooldown moves nothing",
        (w.steal(killer, victim, key = "death-2", now = 1_000_000L + 10 * MIN) as? StealResult.Cooldown)?.remainingMillis == 20 * MIN && w.wallet(victim) == coins(90_000))
    check("steal: after the cooldown", w.steal(killer, victim, key = "death-3", now = 1_000_000L + 31 * MIN) is StealResult.Stolen && w.wallet(victim) == coins(81_000))
    check("steal: cooldown off with anti-farming disabled",
        w.steal(killer, victim, key = "death-4", now = 1_000_000L + 32 * MIN, s = CoinStealSettings.DEFAULTS.copy(antiFarmingEnabled = false)) is StealResult.Stolen)
    val other = uuid(12)
    w.player(other, 0)
    check("steal: empty wallet moves nothing", w.steal(killer, other, key = "death-5") == StealResult.Empty && w.wallet(other) == 0L)
    val capped = uuid(13)
    w.player(capped, 1_000_000)
    check("steal: max per kill", (w.steal(killer, capped, key = "death-6", s = CoinStealSettings.DEFAULTS.copy(maxPerKillCents = coins(50))) as? StealResult.Stolen)?.amountCents == coins(50))
    check("steal: self refused", throws { w.steal(killer, killer) })
    val rows = w.store.steals(victim, 10)
    check("steal records: killer, victim, amount, season", rows.size == 3 && rows.all { it.killer == killer && it.victim == victim && it.season == "season-1" })
    val ledger = w.store.history(victim, 0, 50).filter { it.type == BankStore.EntryType.PVP_LOSS }
    check("ledger: PvP loss with the opposing player", ledger.size == 3 && ledger.all { it.counterparty == killer })
    check("ledger: PvP gain for the killer", w.store.history(killer, 0, 50).count { it.type == BankStore.EntryType.PVP_GAIN } == 4)
    check("ledger: steal rows never show the victim's bank", ledger.all { it.bankAfterCents == null })
    check("steal records can't be deleted or edited", throws { w.db.execute("DELETE FROM lifesteal_coin_steals") } && throws { w.db.execute("UPDATE lifesteal_coin_steals SET amount_cents = 1") })
    // Repeat counter for the suspicious log.
    val farmer = uuid(14)
    val alt = uuid(15)
    w.player(farmer, 0)
    w.player(alt, 1_000_000)
    val noCd = CoinStealSettings.DEFAULTS.copy(sameVictimCooldownMillis = 0)
    val counts = (1..3).map { (w.steal(farmer, alt, key = "farm-$it", now = 5_000_000L + it, s = noCd) as StealResult.Stolen).earlierInWindow }
    check("repeat counter for the suspicious log", counts == listOf(0, 1, 2))
    w.store.recordSuspicious(1L, farmer, alt, null, "test")
    check("suspicious log readable", w.store.suspicious(5).any { it.killer == farmer && it.detail == "test" })
    // Offline notice (Combat NPC deaths)
    val off = uuid(16)
    w.player(off, 10_000)
    w.store.steal(BankStore.StealRequest("npc-1", killer, off, CoinStealSettings.DEFAULTS, 9_000_000L, null, false, "combat-npc") { a, wallet -> "lost $a left $wallet" })
    val notes = w.store.takeNotices(off, 1L)
    check("offline victim notice saved once", notes.map { it.message } == listOf("lost ${coins(1_000)} left ${coins(9_000)}") && w.store.takeNotices(off, 2L).isEmpty())
    w.close()
}

private fun failures(w: World) {
    val a = uuid(20)
    val k = uuid(21)
    w.player(a, 10_000)
    w.player(k, 0)
    w.move(a, Direction.DEPOSIT, coins(5_000))
    w.wallet.failDeposits = true
    check("withdraw: economy failure throws", throws { w.move(a, Direction.WITHDRAW, coins(1_000)) })
    check("withdraw: rolled back, nothing destroyed", w.bank(a) == coins(5_000) && w.wallet(a) == coins(5_000))
    check("steal: economy failure throws", throws { w.steal(k, a, key = "fail-1") })
    check("steal: rolled back, victim keeps coins, nothing recorded", w.wallet(a) == coins(5_000) && w.wallet(k) == 0L && w.store.steals(a, 5).isEmpty())
    w.wallet.failDeposits = false
    check("steal: the same death can still be processed after a rollback", w.steal(k, a, key = "fail-1") is StealResult.Stolen)
    w.store.recordFailure("tx-f", a, BankStore.EntryType.WITHDRAW, coins(1_000), null, 5L, "season-1", "command", "simulated")
    val failed = w.store.failed(10)
    check("failure recorded for staff", failed.size == 1 && failed[0].state == "FAILED" && failed[0].txId == "tx-f")
    check("failed rows don't block a retry with the same id", w.move(a, Direction.WITHDRAW, coins(100), tx = "tx-f") is TransferResult.Done)
    check("review marks it once", w.store.review(failed[0].id, "staff", "checked", 6L) && !w.store.review(failed[0].id, "staff", "again", 7L) && w.store.failed(10).isEmpty())
    w.close()
}

private fun restart(dir: File) {
    var w = World(dir)
    val a = uuid(30)
    w.player(a, 50_000)
    w.move(a, Direction.DEPOSIT, coins(20_000), tx = "persist-1")
    w.steal(uuid(31), a, key = "persist-death")
    w.close()
    w = World(dir)
    check("restart: bank balance survives", w.bank(a) == coins(20_000))
    check("restart: completed transaction stays a duplicate", w.move(a, Direction.DEPOSIT, coins(20_000), tx = "persist-1") == TransferResult.Duplicate)
    check("restart: processed death stays processed", w.steal(uuid(31), a, key = "persist-death") == StealResult.Duplicate)
    check("restart: history kept", w.store.history(a, 0, 10).size == 2)
    w.close()
}

private fun admin(w: World) {
    val a = uuid(40)
    w.player(a, 0)
    fun adj(op: BankStore.AdminOp, cents: Long, reason: String = "refund ticket 12", max: Long = coins(1_000_000)) =
        w.store.adjust(BankStore.AdminRequest(UUID.randomUUID().toString(), a, op, cents, max, "CONSOLE", reason, 1L, "season-1"))
    check("admin add", (adj(BankStore.AdminOp.ADD, coins(500)) as? BankStore.AdminResult.Done)?.bankAfterCents == coins(500))
    check("admin add capped per adjustment", adj(BankStore.AdminOp.ADD, coins(2_000_000)) is BankStore.AdminResult.Refused && w.bank(a) == coins(500))
    check("admin reason required", adj(BankStore.AdminOp.ADD, coins(1), reason = " ") is BankStore.AdminResult.Refused)
    check("admin remove never goes negative", adj(BankStore.AdminOp.REMOVE, coins(501)) is BankStore.AdminResult.Refused && w.bank(a) == coins(500))
    check("admin remove", (adj(BankStore.AdminOp.REMOVE, coins(200)) as? BankStore.AdminResult.Done)?.bankAfterCents == coins(300))
    val rows = w.store.history(a, 0, 10)
    check("admin adjustments audited with actor and reason", rows.size == 2 && rows.all { it.actor == "CONSOLE" && it.detail == "refund ticket 12" && it.source == "admin" })
    check("admin never touches the wallet", w.wallet(a) == 0L)
    w.close()
}

// ── Concurrency ──────────────────────────────────────────────

private fun concurrency() {
    val root = Files.createTempDirectory("joshymc-bank-conc").toFile()
    try {
        val w = World(root)
        val players = (0 until 6).map { uuid(100 + it) }
        players.forEach { w.player(it, 10_000) }
        val start = players.sumOf { w.wallet(it) + w.bank(it) }
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val random = java.util.Random(1427)
        repeat(400) { i ->
            val p = players[random.nextInt(players.size)]
            val q = players[random.nextInt(players.size)]
            val kind = random.nextInt(3)
            val amount = coins(1L + random.nextInt(3_000))
            pool.submit {
                go.await()
                try {
                    when (kind) {
                        0 -> w.move(p, Direction.DEPOSIT, amount)
                        1 -> w.move(p, Direction.WITHDRAW, amount)
                        else -> if (p != q) w.steal(p, q, key = "c-$i", now = i.toLong(), s = CoinStealSettings.DEFAULTS.copy(sameVictimCooldownMillis = 0))
                    }
                } catch (_: Exception) {
                }
            }
        }
        // The same transaction id from many threads completes once.
        val same = players[0]
        repeat(20) { pool.submit { go.await(); try { w.move(same, Direction.DEPOSIT, coins(1), tx = "race") } catch (_: Exception) {} } }
        go.countDown()
        pool.shutdown()
        check("concurrent work finished", pool.awaitTermination(120, TimeUnit.SECONDS))
        val end = players.sumOf { w.wallet(it) + w.bank(it) }
        check("coins conserved under concurrent deposits, withdrawals and steals (no fees)", start == end)
        check("no negative balance", players.all { w.wallet(it) >= 0 && w.bank(it) >= 0 })
        check("racing duplicate id completed once", w.store.history(same, 0, 1000).count { it.txId == "race" } == 1)
        w.close()
    } finally {
        root.deleteRecursively()
    }
}

// ── Wiring ───────────────────────────────────────────────────

private fun wiring() {
    check("/bank is LIFESTEAL", GamemodeAssignments.COMMANDS["bank"] == GamemodeScope.LIFESTEAL)
    val commands = source("manager/CommandManager.kt")
    check("/bank registered only where Lifesteal is hosted", commands.contains("if (plugin.lifestealBankManager.hosted) {\n            plugin.getCommand(\"bank\")") &&
        commands.contains("unregisterCommand(\"bank\")"))
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("permissions declared", pluginYml.contains("joshymc.lifesteal.bank.use:\n    description") &&
        Regex("joshymc\\.lifesteal\\.bank\\.admin:\\n[^\\n]*\\n    default: op").containsMatchIn(pluginYml) &&
        Regex("joshymc\\.lifesteal\\.bank\\.admin\\.adjust:\\n[^\\n]*\\n    default: op").containsMatchIn(pluginYml))
    check("Survival/Lifesteal isolation: only a Lifesteal-only server's economy is Lifesteal's",
        TutorialRules.economyBelongsTo(ServerGamemode.LIFESTEAL, setOf(ServerGamemode.LIFESTEAL)) &&
            !TutorialRules.economyBelongsTo(ServerGamemode.LIFESTEAL, setOf(ServerGamemode.SURVIVAL, ServerGamemode.LIFESTEAL)) &&
            !TutorialRules.economyBelongsTo(ServerGamemode.LIFESTEAL, setOf(ServerGamemode.SURVIVAL)))
    val manager = source("lifesteal/bank/LifestealBankManager.kt")
    check("manager: gated on the Lifesteal economy", manager.contains("economyBelongsTo(ServerGamemode.LIFESTEAL") && manager.contains("val ready: Boolean get() = hosted && economyIsLifesteal && tablesReady"))
    check("manager: never touches hearts", !manager.contains("lifestealHeartManager") && !source("lifesteal/bank/BankStore.kt").contains("lifesteal_hearts"))
    check("manager: reuses the heart attribution, team verdict and launch window",
        manager.contains("HeartRules.attribute(") && manager.contains("lifestealTeamManager.heartCheck(") && manager.contains("lifestealProgressionManager.heartWindow("))
    check("manager: combat, ranked, event and freeze re-read on every transfer",
        manager.contains("accessBlock(player)?.let { return refuse(it.message) }") && manager.contains("combatManager.isTagged(player)") &&
            manager.contains("ranked.holds(player)") && manager.contains("isInEventWorld(player)") && manager.contains("adminManager.isFrozen"))
    check("manager: dedupe keys per death and per Combat NPC", manager.contains("\"coins:pvp:\$bootId:") && manager.contains("\"coins:combatnpc:\$sessionId\""))
    val listener = source("lifesteal/bank/LifestealBankListener.kt")
    check("listener: deaths read at MONITOR, ignoreCancelled", listener.contains("@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)\n    fun onDeath"))
    check("listener: Combat NPC deaths", listener.contains("LifestealCombatNpcDeathEvent"))
    check("listener: registered only where Lifesteal is hosted",
        source("manager/ListenerManager.kt").contains("if (plugin.lifestealBankManager.hosted) {\n            pm.registerEvents(com.liam.joshymc.lifesteal.bank.LifestealBankListener"))
    val menu = source("lifesteal/bank/LifestealBankMenu.kt")
    check("menu: confirms through the shared transfer path, one id per screen, single use",
        menu.contains("manager.transfer(p, direction, cents, txId, \"gui\")") && menu.contains("val txId = UUID.randomUUID().toString()") && menu.contains("if (used) return@setItem"))
    check("menu: never moves coins itself", !menu.contains("economyManager") && !menu.contains("store."))
    val command = source("lifesteal/bank/LifestealBankCommand.kt")
    check("command: transfers through the manager", command.contains("manager.transfer(player, direction, cents"))
    check("command: adjustments need their own permission, staff protection and an audit",
        command.contains("ADJUST_PERMISSION") && command.contains("staffRankManager.protectionProblem(sender, uuid)") && command.contains("AdminAudit.log(plugin, sender, \"BANK_"))
    check("command: exact names only", command.contains("plugin.playerResolver.resolve(name)") && !command.contains("Bukkit.getOfflinePlayer("))
    val main = source("Joshymc.kt")
    check("lifecycle: shutdown step + reload refresh", main.contains("shutdown.step(\"lifestealBankManager.stop\", { ::lifestealBankManager.isInitialized })") &&
        main.contains("safe(\"lifestealBankManager.refresh\")"))
    check("config: read from lifesteal.yml's own sections", source("lifesteal/LifestealConfigManager.kt").let {
        it.contains("BankConfig.parseBank { path -> b.get(\"bank.\$path\", null) }") && it.contains("BankConfig.parseCoinSteal { path -> c.get(\"pvp-coins.\$path\", null) }")
    })
    check("Survival economy untouched (no link to the Lifesteal bank)",
        listOf("manager/EconomyManager.kt", "manager/TeamManager.kt", "command/TeamCommand.kt", "gui/team/TeamBankGui.kt")
            .none { source(it).contains("lifesteal.bank") || source(it).contains("lifestealBankManager") || source(it).contains("lifesteal_bank") })
    check("team banks independent: no team table in the bank store", !source("lifesteal/bank/BankStore.kt").contains("team"))
}
