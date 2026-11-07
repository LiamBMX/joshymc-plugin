// Checks the Lifesteal bounty system (issue #1401): exact money parsing and formatting (minimum,
// zero, negatives, NaN/Infinity, sub-cent amounts, overflow), the bounties section of lifesteal.yml,
// the claim eligibility rules (environment, self, NPCs, events, arenas, Ranked matches and arenas,
// teammates, unknown teams), menu layout and paging, and against a real SQLite database with the
// real heart and team tables and a fake wallet on the `economy` table: contributions (minimum,
// funds, self, teammates, offline and never-joined targets, stacking, duplicates, stale menus,
// overflow), claims (100% payout, duplicates, teammates, cooldown, expiration), payout and refund
// failures with retry, expiration and full refunds, a database restart mid-way, statistics,
// leaderboards, notifications, anonymity, money conservation under concurrent contributions,
// claims and expirations, and the wiring (Lifesteal only, Survival untouched). The Gradle build has
// no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-bounty-check.init.gradle.kts lifestealBountyCheck
//
// Exits non-zero on the first failed check. Live Paper behaviour (menus, chat prompts, real deaths)
// is NOT covered here; see docs/lifesteal-bounties.md, "Manual tests".

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.bounty.BountyConfig
import com.liam.joshymc.lifesteal.bounty.BountyMenuLayout
import com.liam.joshymc.lifesteal.bounty.BountyMoney
import com.liam.joshymc.lifesteal.bounty.BountyMoney.Parsed
import com.liam.joshymc.lifesteal.bounty.BountyRules
import com.liam.joshymc.lifesteal.bounty.BountyStore
import com.liam.joshymc.lifesteal.bounty.BountyStore.ClaimResult
import com.liam.joshymc.lifesteal.bounty.BountyStore.ContributeResult
import com.liam.joshymc.lifesteal.bounty.BountyStore.PayResult
import com.liam.joshymc.lifesteal.bounty.BountyStore.RefundResult
import com.liam.joshymc.lifesteal.hearts.HeartStore
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore
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
import java.util.concurrent.atomic.AtomicInteger
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

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR
private const val MIN = 1_000_000L // $10,000 in cents
private fun coins(c: Long) = c * 100 // whole coins -> cents

fun main() {
    money()
    config()
    rules()
    layout()
    database()
    concurrency()
    wiring()
    println("Lifesteal bounty checks passed ($checks checks).")
    exitProcess(0)
}

// ── Money ────────────────────────────────────────────────────

private fun money() {
    check("parse: exactly 10000", BountyMoney.parse("10000") == Parsed.Ok(MIN))
    check("parse: commas and \$", BountyMoney.parse("\$10,000") == Parsed.Ok(MIN))
    check("parse: suffixes", BountyMoney.parse("10k") == Parsed.Ok(MIN) && BountyMoney.parse("1.5m") == Parsed.Ok(coins(1_500_000)) &&
        BountyMoney.parse("2B") == Parsed.Ok(coins(2_000_000_000)) && BountyMoney.parse("1t") == Parsed.Ok(coins(1_000_000_000_000)))
    check("parse: cents", BountyMoney.parse("25000.50") == Parsed.Ok(2_500_050L))
    check("parse: zero", BountyMoney.parse("0") == Parsed.Zero && BountyMoney.parse("0.00") == Parsed.Zero && BountyMoney.parse("0k") == Parsed.Zero)
    check("parse: negative", BountyMoney.parse("-10000") == Parsed.Negative && BountyMoney.parse("-5k") == Parsed.Negative)
    for (bad in listOf("", " ", "abc", "NaN", "nan", "Infinity", "-Infinity", "1e400", "1e5", "10..0", "1,0000", "0x10", "10kk", "١٠٠٠٠", null)) {
        check("parse: invalid '$bad'", BountyMoney.parse(bad) == Parsed.Invalid)
    }
    check("parse: fractions of a cent", BountyMoney.parse("10000.001") == Parsed.TooPrecise)
    check("parse: overflow", BountyMoney.parse("10000000000001") == Parsed.TooLarge && BountyMoney.parse("99999999999999999999999999") == Parsed.TooLarge &&
        BountyMoney.parse("9223372036854775808t") == Parsed.TooLarge)
    check("parse: the largest safe amount", BountyMoney.parse("10t") == Parsed.Ok(BountyMoney.MAX_CENTS))
    check("format", BountyMoney.format(MIN) == "\$10,000" && BountyMoney.format(2_500_050L) == "\$25,000.50" && BountyMoney.format(5) == "\$0.05" &&
        BountyMoney.format(BountyMoney.MAX_CENTS) == "\$10,000,000,000,000")
    check("add: exact", BountyMoney.add(coins(25_000), coins(50_000)) == coins(75_000))
    check("add: no overflow past the safe maximum", BountyMoney.add(BountyMoney.MAX_CENTS, 1) == null && BountyMoney.add(Long.MAX_VALUE, Long.MAX_VALUE) == null && BountyMoney.add(-1, 5) == null)
    // Every cent amount converts to a Double that rounds back to the same cents.
    val rnd = java.util.Random(1401)
    var exact = true
    repeat(20_000) {
        val c = (rnd.nextDouble() * BountyMoney.MAX_CENTS).toLong()
        if (BigDecimal.valueOf(BountyMoney.toCoins(c)).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong() != c) exact = false
    }
    for (c in listOf(1L, 99L, MIN, BountyMoney.MAX_CENTS, BountyMoney.MAX_CENTS - 1)) {
        if (BigDecimal.valueOf(BountyMoney.toCoins(c)).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong() != c) exact = false
    }
    check("toCoins: exact to the cent up to the maximum", exact)
    check("toCoins: refuses out-of-range", throws { BountyMoney.toCoins(-1) } && throws { BountyMoney.toCoins(BountyMoney.MAX_CENTS + 1) })
    check("configCents", BountyMoney.configCents(10000) == MIN && BountyMoney.configCents(10000.0) == MIN && BountyMoney.configCents("10k") == MIN &&
        BountyMoney.configCents(Double.NaN) == null && BountyMoney.configCents(-1) == null && BountyMoney.configCents(0) == null && BountyMoney.configCents(true) == null)
    check("rules: amount problems", BountyRules.amountProblem(MIN, MIN) == null && BountyRules.amountProblem(MIN - 1, MIN) != null &&
        BountyRules.amountProblem(0, MIN) != null && BountyRules.amountProblem(-MIN, MIN) != null && BountyRules.amountProblem(coins(1_000_000_000), MIN) == null &&
        BountyRules.amountProblem(BountyMoney.MAX_CENTS + 1, MIN) != null)
}

// ── Config ───────────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun bundled(): Map<String, Any?> {
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    return Yaml().load(text)
}

private fun node(m: Map<String, Any?>, path: String): Any? = path.split('.').fold<String, Any?>(m) { n, key -> (n as? Map<*, *>)?.get(key) }

private fun parse(m: Map<String, Any?>) = BountyConfig.parse { node(m, "bounties.$it") }

@Suppress("UNCHECKED_CAST")
private fun edited(path: String, value: Any?): Map<String, Any?> {
    fun copy(x: Any?): Any? = if (x is Map<*, *>) LinkedHashMap((x as Map<String, Any?>).mapValues { copy(it.value) }) else x
    val m = copy(bundled()) as MutableMap<String, Any?>
    val keys = "bounties.$path".split('.')
    var cur = m
    for (k in keys.dropLast(1)) cur = cur[k] as MutableMap<String, Any?>
    if (value == null) cur.remove(keys.last()) else cur[keys.last()] = value
    return m
}

private fun config() {
    val result = parse(bundled())
    check("bundled bounties: valid, no problems", result.valid && result.problems.isEmpty())
    val s = result.settings!!
    check("bundled bounties: the issue's values", s.enabled && s.minimumContributionCents == MIN && s.expirationMillis == 7 * DAY && s.claimCooldownMillis == DAY)
    check("bundled bounties: presets", s.gui.presetCents == listOf(MIN, coins(25_000), coins(50_000), coins(100_000), coins(250_000), coins(1_000_000)))
    check("bundled bounties: announcements match the issue's wording",
        s.messages.created.contains("[BOUNTY]") && s.messages.created.contains("{amount}") && s.messages.created.contains("{target}") &&
            !s.messages.created.contains("{contributor}") && s.messages.claimed.contains("{hunter}"))
    check("missing key: warning + default", parse(edited("claim-cooldown-hours", null)).let { it.valid && it.settings!!.claimCooldownMillis == DAY && it.problems.any { p -> !p.severe } })
    for ((path, value) in listOf(
        "minimum-contribution" to 0, "minimum-contribution" to -5, "minimum-contribution" to "abc", "minimum-contribution" to 10000.001,
        "minimum-contribution" to "11t", "expiration-hours" to 0, "expiration-hours" to 9000, "expiration-hours" to "7d",
        "claim-cooldown-hours" to -1, "enabled" to "yes", "sweep-interval-seconds" to 1, "gui.contribution-presets" to listOf(10000, "x"),
        "gui.contribution-presets" to emptyList<Any>(), "anti-abuse.repeat-kill-threshold" to 1, "expiration-hours" to Long.MAX_VALUE,
    )) {
        check("invalid $path=$value: severe, section invalid", parse(edited(path, value)).let { !it.valid && it.problems.any { p -> p.severe } })
    }
    check("disabled is valid", parse(edited("enabled", false)).settings?.enabled == false)
    check("expiration range can't overflow a timestamp", BountyConfig.MAX_EXPIRATION_HOURS * HOUR < Long.MAX_VALUE / 4)
    check("no gameplay maximum setting", node(bundled(), "bounties.maximum") == null && node(bundled(), "bounties.maximum-contribution") == null)
}

// ── Claim rules ──────────────────────────────────────────────

private val V = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
private val K = UUID.fromString("00000000-0000-4000-8000-0000000000b2")

private fun rules() {
    val ok = BountyRules.Death(victim = V, causingPlayer = K)
    check("eligible: ordinary Lifesteal PvP", BountyRules.eligibility(ok) == BountyRules.Eligibility.Eligible(K))
    fun refused(d: BountyRules.Death) = (BountyRules.eligibility(d) as? BountyRules.Eligibility.Refused)?.reason
    check("environmental / mob death", refused(ok.copy(causingPlayer = null)) == BountyRules.Refusal.NOT_PLAYER_KILL)
    check("suicide / self-kill", refused(ok.copy(causingPlayer = V)) == BountyRules.Refusal.SELF)
    check("NPC killer", refused(ok.copy(killerIsNpc = true)) == BountyRules.Refusal.NPC)
    check("offline killer (manipulated attribution)", refused(ok.copy(killerOnline = false)) == BountyRules.Refusal.NOT_PLAYER_KILL)
    check("victim outside Lifesteal", refused(ok.copy(victimInLifesteal = false)) == BountyRules.Refusal.NOT_LIFESTEAL)
    check("killer outside Lifesteal", refused(ok.copy(killerInLifesteal = false)) == BountyRules.Refusal.NOT_LIFESTEAL)
    check("Ranked match", refused(ok.copy(inRankedMatch = true)) == BountyRules.Refusal.RANKED)
    check("Ranked arena without a match (normal world)", refused(ok.copy(inRankedArena = true)) == BountyRules.Refusal.RANKED)
    check("organized event", refused(ok.copy(inEvent = true)) == BountyRules.Refusal.EVENT)
    check("PvP arena", refused(ok.copy(inArena = true)) == BountyRules.Refusal.ARENA)
    check("teammate", refused(ok.copy(team = BountyRules.Team.TEAMMATES)) == BountyRules.Refusal.TEAMMATE)
    check("recent teammate", refused(ok.copy(team = BountyRules.Team.RECENT_TEAMMATES)) == BountyRules.Refusal.TEAMMATE)
    check("team data unknown never pays", refused(ok.copy(team = BountyRules.Team.UNKNOWN)) == BountyRules.Refusal.TEAM_UNKNOWN)
    check("ranked wins over everything else", refused(ok.copy(inRankedMatch = true, inEvent = true, team = BountyRules.Team.TEAMMATES)) == BountyRules.Refusal.RANKED)
    check("hearts never decide a claim (a 1-heart victim is judged normally)",
        BountyRules.Death::class.java.declaredFields.none { it.name.contains("heart", ignoreCase = true) })
    check("cooldown", BountyRules.cooldownRemaining(null, 0, DAY) == 0L && BountyRules.cooldownRemaining(1000, 1000 + HOUR, DAY) == 23 * HOUR &&
        BountyRules.cooldownRemaining(1000, 1000 + DAY, DAY) == 0L && BountyRules.cooldownRemaining(1000, 1001, 0) == 0L)
    check("repeated kills", !BountyRules.repeatedKills(0, 3) && !BountyRules.repeatedKills(1, 3) && BountyRules.repeatedKills(2, 3))
    check("time remaining", BountyRules.formatRemaining(7 * DAY) == "7d 0h" && BountyRules.formatRemaining(6 * DAY + 23 * HOUR + 59 * 60_000) == "6d 23h" &&
        BountyRules.formatRemaining(4 * HOUR + 10 * 60_000) == "4h 10m" && BountyRules.formatRemaining(30_000) == "< 1m" && BountyRules.formatRemaining(0) == "Expired")
}

private fun layout() {
    check("paging", BountyRules.pageCount(0, 45) == 1 && BountyRules.pageCount(45, 45) == 1 && BountyRules.pageCount(46, 45) == 2 &&
        BountyRules.page((1..100).toList(), 2, 45) == (91..100).toList() && BountyRules.page((1..10).toList(), 9, 45) == (1..10).toList())
    check("preset slots centred in row two", BountyMenuLayout.presetSlots(7) == (10..16).toList() && BountyMenuLayout.presetSlots(1) == listOf(13) &&
        BountyMenuLayout.presetSlots(6).all { it in 10..16 } && BountyMenuLayout.presetSlots(99).size == 7)
    check("presets below the minimum hidden", BountyMenuLayout.presets(listOf(5_000L, MIN, coins(50_000)), MIN) == listOf(MIN, coins(50_000)) &&
        BountyMenuLayout.presets(listOf(5_000L), MIN) == listOf(MIN))
    val navigation = listOf(BountyMenuLayout.LEADERBOARDS_SLOT, BountyMenuLayout.STATS_SLOT, BountyMenuLayout.PREV_SLOT, BountyMenuLayout.CLOSE_SLOT,
        BountyMenuLayout.NEXT_SLOT, BountyMenuLayout.PLACE_SLOT, BountyMenuLayout.INFO_SLOT)
    check("list navigation in the bottom row, no overlap", navigation.all { it in 45..53 } && navigation.distinct().size == navigation.size && BountyMenuLayout.PER_PAGE == 45)
    check("GuiStyle slots", BountyMenuLayout.PREV_SLOT == 54 - 7 && BountyMenuLayout.CLOSE_SLOT == 54 - 5 && BountyMenuLayout.NEXT_SLOT == 54 - 3 &&
        BountyMenuLayout.CONTRIBUTE_BACK_SLOT == 36 - 9 && BountyMenuLayout.CONTRIBUTE_CLOSE_SLOT == 36 - 5 && BountyMenuLayout.CONFIRM_CLOSE_SLOT == 27 - 5)
    check("board selectors free", listOf(BountyMenuLayout.BOARD_BACK_SLOT, BountyMenuLayout.BOARD_EARNED_SLOT, BountyMenuLayout.BOARD_CLAIMS_SLOT,
        BountyMenuLayout.PREV_SLOT, BountyMenuLayout.CLOSE_SLOT, BountyMenuLayout.NEXT_SLOT).distinct().size == 6)
    val ids = listOf("b", "a", "c")
    val list = listOf(
        BountyStore.ActiveBounty(ids[0], V, "x", coins(50_000), 1, 2000, 0),
        BountyStore.ActiveBounty(ids[1], K, "y", coins(50_000), 1, 1000, 0),
        BountyStore.ActiveBounty(ids[2], UUID.randomUUID(), "z", coins(90_000), 1, 3000, 0),
    )
    check("highest first, ties by age then id", list.sortedWith(BountyRules.ORDER).map { it.id } == listOf("c", "a", "b"))
    check("public rows carry no contributor", BountyStore.ActiveBounty::class.java.declaredFields.none { it.name.contains("contributor", ignoreCase = true) })
}

// ── Database ─────────────────────────────────────────────────

/** The economy as the store sees it: the same `economy` table and statements as EconomyManager, on the same database. */
private class FakeWallet(private val db: DatabaseManager) : BountyStore.Wallet {
    @Volatile var failDeposits = false
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
    val store = BountyStore(db, wallet)
    init {
        db.createTable("CREATE TABLE IF NOT EXISTS economy (uuid TEXT PRIMARY KEY, balance REAL NOT NULL DEFAULT 0.0)")
        HeartStore(db).createTables()
        LifestealTeamStore(db).createTables()
        store.createTables()
    }
    fun player(uuid: UUID, coins: Long = 0, joined: Boolean = true) {
        db.execute("INSERT INTO economy (uuid, balance) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET balance = excluded.balance", uuid.toString(), coins.toDouble())
        if (joined) db.execute("INSERT OR IGNORE INTO lifesteal_hearts (uuid, hearts, created_at, updated_at) VALUES (?, 10, 0, 0)", uuid.toString())
    }
    fun cents(uuid: UUID): Long = BigDecimal.valueOf(db.queryFirst("SELECT balance FROM economy WHERE uuid = ?", uuid.toString()) { it.getDouble(1) } ?: 0.0)
        .setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
    fun team(id: String, vararg members: UUID) {
        db.execute("INSERT INTO lifesteal_teams (id, name, canonical_name, leader, status, created_at, created_by) VALUES (?, ?, ?, ?, 'ACTIVE', 0, 'x')", id, id, id, members[0].toString())
        members.forEach { db.execute("INSERT INTO lifesteal_team_members (uuid, team_id, joined_at) VALUES (?, ?, 0)", it.toString(), id) }
    }
    fun leaveTeam(uuid: UUID) = db.execute("DELETE FROM lifesteal_team_members WHERE uuid = ?", uuid.toString())
    fun contribute(from: UUID, to: UUID, cents: Long, now: Long, tx: String = UUID.randomUUID().toString(), expected: String? = null) =
        store.contribute(BountyStore.ContributeRequest(tx, from, "p${from.toString().takeLast(2)}", to, "t${to.toString().takeLast(2)}", cents, MIN, 7 * DAY, now, expected, "check",
            targetNotice = { total, at -> "notice ${BountyMoney.format(total)} $at" }))
    fun claim(killer: UUID, victim: UUID, at: Long, key: String = UUID.randomUUID().toString()) =
        store.claim(BountyStore.ClaimRequest(key, killer, "k", victim, "v", at, DAY))
    fun pay(id: String, now: Long) = store.pay(id, now, 300_000L) { "paid ${BountyMoney.format(it.amountCents)}" }
    fun refundAll(now: Long) = store.dueRefunds(now).map { store.refund(it, now, 300_000L) { c, t -> "refund ${c.amountCents} $t" } }
    fun close() = db.shutdown()
}

private fun uuid(n: Int) = UUID(0x1401L, n.toLong())

private fun database() {
    val root = Files.createTempDirectory("joshymc-bounty-check").toFile()
    try {
        contributions(World(File(root, "a")))
        claims(World(File(root, "b")))
        expiration(File(root, "c"))
        failures(File(root, "d"))
        statistics(World(File(root, "e")))
    } finally {
        root.deleteRecursively()
    }
}

private fun contributions(w: World) {
    val a = uuid(1); val b = uuid(2); val c = uuid(3); val target = uuid(4); val never = uuid(5); val mate = uuid(6)
    listOf(a, b, c, mate).forEach { w.player(it, coins = 1_000_000) }
    w.player(target, coins = 0)
    w.player(never, coins = 0, joined = false)
    val t0 = 1_000_000_000_000L

    check("below minimum refused", (w.contribute(a, target, MIN - 1, t0) as ContributeResult.Refused).reason == BountyStore.Refusal.INVALID_AMOUNT && w.cents(a) == coins(1_000_000))
    check("zero / negative refused", w.contribute(a, target, 0, t0) is ContributeResult.Refused && w.contribute(a, target, -MIN, t0) is ContributeResult.Refused)
    check("self refused", (w.contribute(a, a, MIN, t0) as ContributeResult.Refused).reason == BountyStore.Refusal.SELF)
    check("never joined Lifesteal refused", (w.contribute(a, never, MIN, t0) as ContributeResult.Refused).reason == BountyStore.Refusal.NEVER_JOINED && w.cents(a) == coins(1_000_000))
    w.team("crew", mate, target)
    check("teammate (stored membership) refused", (w.contribute(mate, target, MIN, t0) as ContributeResult.Refused).reason == BountyStore.Refusal.TEAMMATE && w.cents(mate) == coins(1_000_000))
    check("insufficient funds refused, balance untouched", (w.contribute(a, target, coins(1_000_001), t0) as ContributeResult.Refused).reason == BountyStore.Refusal.INSUFFICIENT_FUNDS &&
        w.cents(a) == coins(1_000_000) && w.store.activeFor(target) == null)

    val first = w.contribute(a, target, coins(25_000), t0) as ContributeResult.Contributed
    check("first contribution creates the bounty (offline target)", first.created && first.totalCents == coins(25_000) && first.expiresAt == t0 + 7 * DAY && w.cents(a) == coins(975_000))
    val second = w.contribute(b, target, coins(50_000), t0 + 2 * DAY) as ContributeResult.Contributed
    val third = w.contribute(c, target, coins(100_000), t0 + 3 * DAY) as ContributeResult.Contributed
    check("stacking: one bounty, combined value", !second.created && !third.created && second.bountyId == first.bountyId && third.bountyId == first.bountyId &&
        third.totalCents == coins(175_000))
    check("later contributions keep the original expiration", second.expiresAt == t0 + 7 * DAY && third.expiresAt == t0 + 7 * DAY &&
        w.store.activeFor(target)!!.expiresAt == t0 + 7 * DAY)
    check("exactly the minimum accepted", w.contribute(a, target, MIN, t0 + 3 * DAY) is ContributeResult.Contributed)
    check("public list: total and count only", w.store.activeBounties(t0 + 3 * DAY).single().let { it.totalCents == coins(185_000) && it.contributions == 4 })
    check("target notified once (on creation only)", w.store.pendingNotifications(target).size == 1 && w.store.pendingNotifications(target).single().message.contains("\$25,000"))
    check("notice text names no contributor", w.store.pendingNotifications(target).none { n ->
        listOf(a, b, c).any { n.message.contains(it.toString()) || n.message.contains("p" + it.toString().takeLast(2)) }
    })

    val tx = "fixed-tx"
    val once = w.contribute(b, target, MIN, t0 + 3 * DAY, tx)
    val again = w.contribute(b, target, MIN, t0 + 3 * DAY, tx)
    check("duplicate transaction id takes the coins once", once is ContributeResult.Contributed && again is ContributeResult.Duplicate &&
        w.cents(b) == coins(1_000_000 - 50_000 - 10_000))
    check("stale menu (expected another bounty) refused, nothing taken",
        (w.contribute(b, target, MIN, t0 + 3 * DAY, expected = "gone") as ContributeResult.Refused).reason == BountyStore.Refusal.STALE && w.cents(b) == coins(940_000))
    // Overflow: a total past the safe maximum is refused without taking anything.
    val rich = uuid(7); val other = uuid(8)
    w.player(rich, coins = 20_000_000_000_000); w.player(other)
    check("maximum single contribution accepted", w.contribute(rich, other, BountyMoney.MAX_CENTS, t0) is ContributeResult.Contributed)
    check("total past the safe maximum refused", (w.contribute(rich, other, MIN, t0) as ContributeResult.Refused).reason == BountyStore.Refusal.TOO_LARGE &&
        w.cents(rich) == coins(10_000_000_000_000))
    check("triggers: amounts can't be edited", throws { w.db.execute("UPDATE lifesteal_bounty_contributions SET amount_cents = 1") })
    check("triggers: totals can't decrease", throws { w.db.execute("UPDATE lifesteal_bounties SET total_cents = 1 WHERE target = ?", target.toString()) })
    check("one active bounty per target (unique index)", throws {
        w.db.execute("INSERT INTO lifesteal_bounties (id, target, target_name, state, total_cents, contributions, created_at, expires_at) VALUES ('x', ?, 'x', 'ACTIVE', 1, 1, 0, 1)", target.toString())
    })
    // Contributions stay valid when team membership changes later.
    val friend = uuid(9)
    w.player(friend, coins = 100_000)
    w.contribute(friend, target, MIN, t0 + 4 * DAY)
    w.db.execute("INSERT INTO lifesteal_team_members (uuid, team_id, joined_at) VALUES (?, 'crew', 0)", friend.toString())
    check("contribution kept after joining the target's team", w.store.contributionsOf(first.bountyId).any { it.contributor == friend } && w.store.consistencyProblems().isEmpty())
    w.close()
}

private fun claims(w: World) {
    val a = uuid(11); val b = uuid(12); val killer = uuid(13); val target = uuid(14); val mate = uuid(15)
    listOf(a, b, killer, mate).forEach { w.player(it, coins = 1_000_000) }
    w.player(target)
    val t0 = 2_000_000_000_000L
    w.contribute(a, target, coins(100_000), t0)
    w.contribute(b, target, coins(150_000), t0 + HOUR)

    check("no bounty: nothing to claim", w.claim(killer, uuid(99), t0 + 2 * HOUR) == ClaimResult.NoBounty)
    check("self claim never pays", w.claim(target, target, t0 + 2 * HOUR) == ClaimResult.NoBounty)
    w.team("pair", mate, target)
    check("teammate claim refused, bounty kept", w.claim(mate, target, t0 + 2 * HOUR) == ClaimResult.Teammates && w.store.activeFor(target)!!.totalCents == coins(250_000))

    val key = "death-1"
    val claimed = w.claim(killer, target, t0 + 2 * HOUR, key) as ClaimResult.Claimed
    check("claim: the whole bounty", claimed.amountCents == coins(250_000) && w.store.activeFor(target) == null && w.store.bounty(claimed.bountyId)!!.state == BountyStore.State.CLAIMED)
    check("repeated death event: duplicate", w.claim(killer, target, t0 + 2 * HOUR, key) == ClaimResult.Duplicate)
    check("second claim: nothing left", w.claim(uuid(16), target, t0 + 2 * HOUR) == ClaimResult.NoBounty)
    val paid = w.pay(claimed.payoutId, t0 + 2 * HOUR) as PayResult.Paid
    check("winner receives 100%, no tax", w.cents(killer) == coins(1_000_000 + 250_000) && paid.payout.amountCents == coins(250_000))
    check("paying again does nothing", w.pay(claimed.payoutId, t0 + 3 * HOUR) == PayResult.AlreadyPaid && w.cents(killer) == coins(1_250_000))
    check("announced once", w.store.markAnnounced(claimed.payoutId) && !w.store.markAnnounced(claimed.payoutId))
    check("hunter notified with the recorded amount", w.store.pendingNotifications(killer).single().message == "paid \$250,000")
    check("hunter stats once", w.store.stats(killer).let { it.claims == 1L && it.earnedCents == coins(250_000) })
    check("claimed bounty can't expire", w.store.expireDue(t0 + 30 * DAY).isEmpty() && w.store.bounty(claimed.bountyId)!!.state == BountyStore.State.CLAIMED)
    check("claimed bounty never changes (trigger)", throws { w.db.execute("UPDATE lifesteal_bounties SET state = 'EXPIRED' WHERE id = ?", claimed.bountyId) })

    // Cooldown: a new bounty on the same target, the same killer within 24h.
    w.contribute(a, target, MIN, t0 + 3 * HOUR)
    val cd = w.claim(killer, target, t0 + 4 * HOUR)
    check("24h killer-target cooldown, bounty kept", cd is ClaimResult.Cooldown && cd.remainingMillis == DAY - 2 * HOUR && w.store.activeFor(target) != null)
    check("another hunter isn't on cooldown", w.claim(uuid(17), target, t0 + 4 * HOUR) is ClaimResult.Claimed)
    w.contribute(a, target, MIN, t0 + 5 * HOUR)
    check("cooldown over after 24h", w.claim(killer, target, t0 + 2 * HOUR + DAY) is ClaimResult.Claimed)
    // Team membership is checked when the kill happens.
    w.contribute(a, target, MIN, t0 + 2 * DAY)
    w.leaveTeam(mate)
    check("former teammate can claim once they're no longer teammates", w.claim(mate, target, t0 + 2 * DAY) is ClaimResult.Claimed)
    check("refused claims were audited", w.store.auditLog("CLAIM_REFUSED", 0, 10).size >= 2)
    w.close()
}

private fun expiration(dir: File) {
    var w = World(dir)
    val a = uuid(21); val b = uuid(22); val target = uuid(23); val killer = uuid(24)
    listOf(a, b, killer).forEach { w.player(it, coins = 100_000) }
    w.player(target)
    val monday = 3_000_000_000_000L
    val first = w.contribute(a, target, coins(50_000), monday) as ContributeResult.Contributed
    w.contribute(b, target, coins(25_000), monday + 2 * DAY) // Wednesday
    check("not expired one minute early", w.store.expireDue(monday + 7 * DAY - 60_000).isEmpty() && w.store.activeFor(target) != null)
    check("a death after the expiration can't claim", w.claim(killer, target, monday + 7 * DAY) == ClaimResult.Expired && w.store.activeFor(target) != null)
    check("expires seven days after creation", w.store.expireDue(monday + 7 * DAY) == listOf(first.bountyId))
    check("expired bounty leaves the list", w.store.activeBounties(monday + 7 * DAY).isEmpty() && w.store.activeFor(target) == null)
    check("expired bounty can't be claimed", w.claim(killer, target, monday + 7 * DAY + 1) == ClaimResult.NoBounty)
    check("refunds pending, nothing paid yet", w.store.pendingRefunds().size == 2 && w.cents(a) == coins(50_000))

    // Restart before the refunds run: nothing is lost or paid twice.
    w.close()
    w = World(dir)
    val results = w.refundAll(monday + 7 * DAY + 1)
    check("every contributor refunded exactly (offline too)", results.all { it is RefundResult.Refunded } && w.cents(a) == coins(100_000) && w.cents(b) == coins(100_000))
    check("refunds never run twice", w.refundAll(monday + 8 * DAY).isEmpty() &&
        w.store.pendingRefunds().isEmpty() && w.cents(a) == coins(100_000))
    val id = w.store.contributionsOf(first.bountyId).first().id
    check("a direct second refund is refused", w.store.refund(id, monday + 9 * DAY, 1000) { _, _ -> "" } == RefundResult.AlreadyRefunded && w.cents(a) == coins(100_000))
    check("refund notices, private", w.store.pendingNotifications(a).any { it.message.startsWith("refund 5000000") } && w.store.pendingNotifications(b).any { it.message.startsWith("refund 2500000") })
    check("lifetime contributions kept after expiry", w.store.stats(a).contributedCents == coins(50_000) && w.store.stats(a).refundedCents == coins(50_000) &&
        w.store.activeContributionsBy(a).isEmpty())
    check("expiration happens once", w.store.expireDue(monday + 30 * DAY).isEmpty() && w.store.auditLog("EXPIRE", 0, 10).size == 1)
    // Recreated after expiry: a new bounty with a new id and timer.
    val again = w.contribute(a, target, MIN, monday + 10 * DAY) as ContributeResult.Contributed
    check("recreated bounty is a new lifecycle", again.created && again.bountyId != first.bountyId && again.expiresAt == monday + 17 * DAY)
    // A contribution to a bounty that ran out but wasn't swept yet expires it and starts a new one.
    val late = w.contribute(b, target, MIN, monday + 17 * DAY) as ContributeResult.Contributed
    check("no contribution enters an expired bounty", late.created && late.bountyId != again.bountyId && late.expired == listOf(again.bountyId) &&
        w.store.bounty(again.bountyId)!!.state == BountyStore.State.EXPIRED && w.store.pendingRefunds().size == 1)
    check("consistent", w.store.consistencyProblems().isEmpty())
    w.close()
}

private fun failures(dir: File) {
    var w = World(dir)
    val a = uuid(31); val killer = uuid(32); val target = uuid(33)
    w.player(a, coins = 100_000); w.player(killer); w.player(target)
    val t0 = 4_000_000_000_000L
    w.contribute(a, target, coins(40_000), t0)
    val claimed = w.claim(killer, target, t0 + HOUR) as ClaimResult.Claimed
    w.wallet.failDeposits = true
    val failed = w.pay(claimed.payoutId, t0 + HOUR)
    check("payout failure: stays pending, nothing paid, no stats", failed is PayResult.Failed && w.cents(killer) == 0L && w.store.payout(claimed.payoutId)!!.state == "PENDING" &&
        w.store.stats(killer).claims == 0L && w.store.pendingNotifications(killer).isEmpty())
    check("payout failure recorded for staff", w.store.payout(claimed.payoutId)!!.attempts == 1 && w.store.auditLog("PAYOUT_FAILED", 0, 5).size == 1)
    check("retry waits for the delay", w.store.duePayouts(t0 + HOUR + 1000).isEmpty() && w.store.duePayouts(t0 + HOUR + 300_000) == listOf(claimed.payoutId))
    check("the escrow is still accounted for", w.store.escrowCents() == coins(40_000))
    // Restart with the payout pending.
    w.close()
    w = World(dir)
    check("pending payout survives a restart", w.store.duePayouts(t0 + 2 * HOUR) == listOf(claimed.payoutId))
    check("recovered payout pays once", w.pay(claimed.payoutId, t0 + 2 * HOUR) is PayResult.Paid && w.pay(claimed.payoutId, t0 + 2 * HOUR) == PayResult.AlreadyPaid &&
        w.cents(killer) == coins(40_000) && w.store.stats(killer).claims == 1L && w.store.escrowCents() == 0L)

    // Refund failure, then recovery.
    w.contribute(a, target, coins(20_000), t0 + DAY)
    w.store.expireDue(t0 + 9 * DAY)
    w.wallet.failDeposits = true
    check("refund failure: stays pending", w.refundAll(t0 + 9 * DAY).single() is RefundResult.Failed && w.cents(a) == coins(40_000) && w.store.pendingRefunds().size == 1)
    w.wallet.failDeposits = false
    check("refund retried after the delay", w.refundAll(t0 + 9 * DAY + 1000).isEmpty() && w.refundAll(t0 + 9 * DAY + 300_000).single() is RefundResult.Refunded &&
        w.cents(a) == coins(60_000))
    check("consistent after failures", w.store.consistencyProblems().isEmpty() && w.store.escrowCents() == 0L)
    w.close()
}

private fun statistics(w: World) {
    val payer = uuid(41)
    w.player(payer, coins = 10_000_000)
    val hunters = (0 until 5).map { uuid(50 + it) }
    val targets = (0 until 12).map { uuid(70 + it) }
    (hunters + targets).forEach { w.player(it) }
    val t0 = 5_000_000_000_000L
    // Hunter 0: 3 claims of 10k; hunter 1: 1 claim of 100k; hunter 2: 2 claims of 20k; hunter 3: 1 claim of 10k; hunter 4: none.
    val plan = listOf(0 to 10_000L, 0 to 10_000L, 0 to 10_000L, 1 to 100_000L, 2 to 20_000L, 2 to 20_000L, 3 to 10_000L)
    plan.forEachIndexed { i, (h, amount) ->
        w.contribute(payer, targets[i], coins(amount), t0)
        val c = w.claim(hunters[h], targets[i], t0 + HOUR) as ClaimResult.Claimed
        w.pay(c.payoutId, t0 + HOUR)
        w.pay(c.payoutId, t0 + HOUR) // A retried payout never inflates the boards.
    }
    val earned = w.store.leaderboard(BountyStore.Board.EARNED, 0, 10)
    check("richest hunters", earned.map { it.uuid } == listOf(hunters[1], hunters[2], hunters[0], hunters[3]) && earned.map { it.position } == listOf(1, 2, 3, 4) &&
        earned[0].earnedCents == coins(100_000))
    val claimsBoard = w.store.leaderboard(BountyStore.Board.CLAIMS, 0, 10)
    check("most successful hunters", claimsBoard.map { it.uuid } == listOf(hunters[0], hunters[2], hunters[1], hunters[3]) && claimsBoard[0].claims == 3L)
    check("hunters without claims aren't listed", w.store.leaderboardSize() == 4 && earned.none { it.uuid == hunters[4] })
    check("leaderboard pages", w.store.leaderboard(BountyStore.Board.EARNED, 2, 2).map { it.position } == listOf(3, 4))
    check("payer's lifetime contributions", w.store.stats(payer).let { it.contributedCents == coins(180_000) && it.contributions == 7L && it.claims == 0L })
    w.contribute(payer, targets[10], coins(30_000), t0 + 2 * HOUR)
    w.contribute(payer, targets[11], coins(15_000), t0 + 3 * HOUR)
    val own = w.store.activeContributionsBy(payer)
    check("own active contributions with expirations", own.map { it.amountCents } == listOf(coins(30_000), coins(15_000)) &&
        own.map { it.expiresAt } == listOf(t0 + 2 * HOUR + 7 * DAY, t0 + 3 * HOUR + 7 * DAY))
    check("active list ordered highest first", w.store.activeBounties(t0 + 4 * HOUR).map { it.totalCents } == listOf(coins(30_000), coins(15_000)))
    check("statistics consistent", w.store.consistencyProblems().isEmpty())
    w.close()
}

// ── Concurrency ──────────────────────────────────────────────

private fun concurrency() {
    val root = Files.createTempDirectory("joshymc-bounty-race").toFile()
    try {
        val w = World(root)
        val payers = (0 until 8).map { uuid(100 + it) }
        val killers = (0 until 4).map { uuid(200 + it) }
        val targets = (0 until 6).map { uuid(300 + it) }
        payers.forEach { w.player(it, coins = 5_000_000) }
        (killers + targets).forEach { w.player(it) }
        val start = 6_000_000_000_000L
        val totalBefore = (payers + killers + targets).sumOf { w.cents(it) }
        val pool = Executors.newFixedThreadPool(12)
        val gate = CountDownLatch(1)
        val claimedCount = AtomicInteger()
        val rnd = java.util.Random(7)
        repeat(400) { i ->
            val kind = i % 5
            val payer = payers[i % payers.size]
            val target = targets[(i / 3) % targets.size]
            val killer = killers[i % killers.size]
            val amount = MIN + rnd.nextInt(50) * 100_000L
            val time = start + (i / 40) * 2 * DAY // Some bounties run out while others are claimed.
            pool.submit {
                gate.await()
                try {
                    when (kind) {
                        0, 1, 2 -> w.contribute(payer, target, amount, time)
                        3 -> (w.claim(killer, target, time, "race-$i") as? ClaimResult.Claimed)?.let { claimedCount.incrementAndGet(); w.pay(it.payoutId, time) }
                        else -> { w.store.expireDue(time); w.refundAll(time) }
                    }
                } catch (e: Exception) {
                    System.err.println("race task failed: $e")
                }
            }
        }
        gate.countDown()
        pool.shutdown()
        check("race: finished", pool.awaitTermination(120, TimeUnit.SECONDS))
        // Settle what's left: expire everything, refund and pay what's pending.
        val end = start + 100 * DAY
        w.store.expireDue(end)
        w.refundAll(end)
        w.store.duePayouts(end).forEach { w.pay(it, end) }
        val totalAfter = (payers + killers + targets).sumOf { w.cents(it) }
        check("race: no coins created or lost", totalBefore == totalAfter && w.store.escrowCents() == 0L)
        check("race: consistent", w.store.consistencyProblems().isEmpty())
        check("race: every claim paid exactly once", (w.db.queryFirst("SELECT COUNT(*) FROM lifesteal_bounty_payouts WHERE state = 'PAID'") { it.getInt(1) } ?: -1) == claimedCount.get())
        check("race: at most one active bounty per target", (w.db.queryFirst("SELECT COUNT(*) FROM lifesteal_bounties WHERE state = 'ACTIVE'") { it.getInt(1) } ?: -1) == 0)
        val earned = w.db.queryFirst("SELECT COALESCE(SUM(earned_cents), 0) FROM lifesteal_bounty_stats") { it.getLong(1) } ?: -1
        val paidOut = w.db.queryFirst("SELECT COALESCE(SUM(amount_cents), 0) FROM lifesteal_bounty_payouts WHERE state = 'PAID'") { it.getLong(1) } ?: -2
        check("race: statistics match the payouts", earned == paidOut)

        // Simultaneous claims of one bounty by different deaths: exactly one wins.
        val t = uuid(400)
        w.player(t)
        w.contribute(payers[0], t, coins(77_000), end + 1)
        val claimers = Executors.newFixedThreadPool(8)
        val wins = AtomicInteger()
        val go = CountDownLatch(1)
        (0 until 16).forEach { i -> claimers.submit { go.await(); if (w.claim(uuid(500 + i), t, end + 2, "k$i") is ClaimResult.Claimed) wins.incrementAndGet() } }
        go.countDown(); claimers.shutdown(); claimers.awaitTermination(60, TimeUnit.SECONDS)
        check("double-claim race: exactly one claim", wins.get() == 1)
        // A claim racing the expiration: exactly one terminal state.
        val u = uuid(401)
        w.player(u)
        w.contribute(payers[1], u, MIN, end + 3)
        val expiry = end + 3 + 7 * DAY
        val both = Executors.newFixedThreadPool(2)
        val go2 = CountDownLatch(1)
        val claim = both.submit<ClaimResult> { go2.await(); w.claim(killers[0], u, expiry - 1, "edge") }
        val expired = both.submit<List<String>> { go2.await(); w.store.expireDue(expiry) }
        go2.countDown()
        val claimResult = claim.get(30, TimeUnit.SECONDS)
        val expiredIds = expired.get(30, TimeUnit.SECONDS)
        both.shutdown()
        check("claim vs expiration: exactly one wins", (claimResult is ClaimResult.Claimed) != expiredIds.isNotEmpty())
        check("claim vs expiration: consistent", w.store.consistencyProblems().isEmpty())
        w.close()
    } finally {
        root.deleteRecursively()
    }
}

// ── Wiring ───────────────────────────────────────────────────

private fun wiring() {
    check("/bounty is NETWORK (routed per gamemode)", GamemodeAssignments.COMMANDS["bounty"] == GamemodeScope.NETWORK)
    val router = source("command/BountyCommandRouter.kt")
    check("router: Lifesteal senders on a Lifesteal host only", router.contains("lifestealBountyManager.hosted") && router.contains("gamemodeOf(sender) == ServerGamemode.LIFESTEAL"))
    check("router: Survival keeps its SURVIVAL scope and joshymc.bounty", router.contains("isAllowed(GamemodeScope.SURVIVAL, sender)") && router.contains("\"joshymc.bounty\"") &&
        router.contains("survival.onCommand"))
    val commands = source("manager/CommandManager.kt")
    check("command registered through the router", commands.contains("BountyCommandRouter(") && commands.contains("BountyCommandRouter.COMMAND_PERMISSION"))
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("permissions declared", pluginYml.contains("joshymc.lifesteal.bounty.use:\n    description") && pluginYml.contains("joshymc.lifesteal.bounty.admin:") &&
        Regex("joshymc\\.lifesteal\\.bounty\\.admin:\\n[^\\n]*\\n    default: op").containsMatchIn(pluginYml))
    check("economy: only a Lifesteal-only server's balances are Lifesteal's",
        TutorialRules.economyBelongsTo(ServerGamemode.LIFESTEAL, setOf(ServerGamemode.LIFESTEAL)) &&
            !TutorialRules.economyBelongsTo(ServerGamemode.LIFESTEAL, setOf(ServerGamemode.SURVIVAL, ServerGamemode.LIFESTEAL)) &&
            !TutorialRules.economyBelongsTo(ServerGamemode.LIFESTEAL, setOf(ServerGamemode.SURVIVAL)))
    val manager = source("lifesteal/bounty/LifestealBountyManager.kt")
    check("manager: gated on the Lifesteal economy", manager.contains("economyBelongsTo(ServerGamemode.LIFESTEAL"))
    check("manager: announcements to Lifesteal players only", manager.contains("if (isLifesteal(player)) plugin.commsManager.sendRaw"))
    check("manager: never touches hearts", !manager.contains("lifestealHeartManager") && !source("lifesteal/bounty/BountyStore.kt").contains("UPDATE lifesteal_hearts"))
    check("manager: database work off the main thread", manager.contains("plugin.databaseManager.async(\"bounty: "))
    check("manager: ranked, event and arena state at death", manager.contains("lifestealRankedManager.holds(uuid)") && manager.contains("isInEventWorld(player)") &&
        manager.contains("EventState.RUNNING") && manager.contains("arenaManager.isInArena") && manager.contains("arena.bounds?.contains"))
    val listener = source("lifesteal/bounty/LifestealBountyListener.kt")
    check("listener: deaths read at MONITOR, ignoreCancelled", listener.contains("@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)\n    fun onDeath"))
    check("listener: registered only where Lifesteal is hosted", source("manager/ListenerManager.kt").contains("if (plugin.lifestealBountyManager.hosted) {\n            pm.registerEvents(com.liam.joshymc.lifesteal.bounty.LifestealBountyListener"))
    val menu = source("lifesteal/bounty/LifestealBountyMenu.kt")
    check("menus: never read contributor identities", !menu.contains("contributorName") && !menu.contains("contributionsOf") && !menu.contains(".contributor"))
    check("menus: confirm goes through the shared contribute path", menu.contains("manager.contribute(p, target, name, cents, transactionId, expectedBountyId, \"gui\")"))
    check("menus: one transaction id per confirmation, single use", menu.contains("val transactionId = UUID.randomUUID().toString()") && menu.contains("if (used) return@setItem"))
    val command = source("lifesteal/bounty/LifestealBountyCommand.kt")
    check("command: exact names only (PlayerResolver)", command.contains("plugin.playerResolver.resolve(name)") && !command.contains("Bukkit.getOfflinePlayer(name") &&
        !command.contains("getPlayer(args"))
    check("command: tab completion offers online names only", command.contains("Bukkit.getOnlinePlayers().filter { manager.isLifesteal(it) }.map { it.name }"))
    check("command: admin retry only runs the sweep", command.contains("manager.sweep {") && !command.contains("economyManager.deposit") && !command.contains("setBalance"))
    val main = source("Joshymc.kt")
    check("lifecycle: shutdown step + reload refresh", main.contains("shutdown.step(\"lifestealBountyManager.stop\", { ::lifestealBountyManager.isInitialized })") &&
        main.contains("safe(\"lifestealBountyManager.refresh\")"))
    check("config: read from lifesteal.yml's own section", source("lifesteal/LifestealConfigManager.kt").contains("BountyConfig.parse { path -> source.get(\"bounties.\$path\", null) }"))
    check("Survival bounties untouched (no link to the Lifesteal bounty system)",
        listOf("manager/TeamManager.kt", "command/BountyCommand.kt", "gui/bounty/BountyMainGui.kt", "gui/bounty/BountyPlaceGui.kt")
            .none { source(it).contains("lifesteal.bounty") || source(it).contains("lifestealBountyManager") || source(it).contains("lifesteal_bount") })
    check("Lifesteal bounty tables are separate from Survival's `bounties`",
        listOf("FROM bounties", "INTO bounties", "UPDATE bounties", "JOIN bounties").none { source("lifesteal/bounty/BountyStore.kt").contains(it) })
}
