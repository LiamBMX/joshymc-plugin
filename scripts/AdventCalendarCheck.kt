// Checks the Christmas Advent Calendar (issue #1424): the schedule rules (enabled / manual override /
// automatic precedence, midnight unlocks in America/New_York, December 25 -> 26, annual return), the
// day states and catch-up prices, the bundled advent-calendar.yml (every day of both gamemodes and
// the currency totals), the config validation, the claim store against a real SQLite database (one
// claim per day, Credits only on success, price changes refused, free passes, years and gamemodes
// kept apart, restarts, concurrent claims, the waiting/queued bookkeeping), the GUI layout and the
// command wiring. The Gradle build has no test source set, so it runs through an init script:
//
//   ./gradlew -I scripts/advent-calendar-check.init.gradle.kts adventCalendarCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.advent.AdventConfig
import com.liam.joshymc.advent.AdventLayout
import com.liam.joshymc.advent.AdventRules
import com.liam.joshymc.advent.AdventRules.Availability
import com.liam.joshymc.advent.AdventRules.ClosedReason
import com.liam.joshymc.advent.AdventRules.DayState
import com.liam.joshymc.advent.AdventRules.RewardLine
import com.liam.joshymc.advent.AdventSettings
import com.liam.joshymc.advent.AdventStore
import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.PurchaseRewards
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

private val NY: ZoneId = ZoneId.of("America/New_York")

private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Instant =
    ZonedDateTime.of(year, month, day, hour, minute, second, 0, NY).toInstant()

private val DECEMBER = AdventRules.Schedule(true, NY, 12, 1, 12, 25, overrideEnabled = false)

private fun source(yaml: YamlConfiguration) = object : AdventConfig.Source {
    override fun get(path: String): Any? = yaml.get(path, null)
    override fun keys(path: String): List<String>? = (yaml.get(path, null) as? ConfigurationSection)?.getKeys(false)?.toList()
}

private fun parse(text: String): AdventConfig.Result {
    val yaml = YamlConfiguration()
    yaml.loadFromString(text)
    return AdventConfig.parse(source(yaml))
}

private val BUNDLED = File("src/main/resources/advent-calendar.yml").readText()

/** The bundled file with [path] set to [value] (a YAML scalar). */
private fun bundledWith(path: String, value: Any?): AdventConfig.Result {
    val yaml = YamlConfiguration()
    yaml.loadFromString(BUNDLED)
    yaml.set(path, value)
    return AdventConfig.parse(source(yaml))
}

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }

private fun withDb(dir: File, block: (DatabaseManager) -> Unit) {
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        block(db)
    } finally {
        db.shutdown()
    }
}

/** What a line looks like, for comparing the bundled calendars with the issue's schedule. */
private fun text(line: RewardLine): String = when (line) {
    is RewardLine.Grant -> line.spec.describe()
    is RewardLine.Pending -> "pending ${line.placeholderId} x${line.quantity} ${line.label}"
}

private val SURVIVAL_EXPECTED = mapOf(
    1 to listOf("coins 50000"), 2 to listOf("credits 100"), 3 to listOf("cratekey cabin x1"), 4 to listOf("coins 75000"),
    5 to listOf("chattagvoucher snowflake x1"), 6 to listOf("credits 100"), 7 to listOf("cratekey campfire x1"),
    8 to listOf("coins 100000"), 9 to listOf("pending snow_globe x1 Snow Globe Collectible"), 10 to listOf("credits 150"),
    11 to listOf("cratekey homestead x1"), 12 to listOf("coins 125000"), 13 to listOf("pending winter_scarf x1 Winter Scarf Cosmetic"),
    14 to listOf("credits 150"), 15 to listOf("cratekey stockpile x1"), 16 to listOf("coins 150000"),
    17 to listOf("pending christmas_bell x1 Christmas Bell Collectible"), 18 to listOf("credits 200"), 19 to listOf("cratekey harvest x1"),
    20 to listOf("coins 200000"), 21 to listOf("chattagvoucher holly x1"), 22 to listOf("credits 200"),
    23 to listOf("cratekey campfire x2"), 24 to listOf("coins 250000"),
    25 to listOf(
        "coins 500000", "credits 500", "pending survival_premium_crate_key x3 Premium Crate Key", "chattagvoucher christmas x1",
        "pending survival_festive_cosmetic x1 Exclusive Festive Cosmetic", "pending christmas_{year}_collectible x1 Christmas {year} Collectible",
    ),
)

private val LIFESTEAL_EXPECTED = mapOf(
    1 to listOf("coins 10000"), 2 to listOf("credits 50"), 3 to listOf("pending lifesteal_standard_crate_key x1 Standard Lifesteal Crate Key"),
    4 to listOf("coins 15000"), 5 to listOf("chattagvoucher frostbite x1"), 6 to listOf("credits 50"),
    7 to listOf("pending lifesteal_standard_crate_key x1 Standard Lifesteal Crate Key"), 8 to listOf("coins 20000"),
    9 to listOf("pending snow_globe x1 Snow Globe Collectible"), 10 to listOf("credits 75"),
    11 to listOf("pending lifesteal_upgraded_crate_key x1 Upgraded Lifesteal Crate Key"), 12 to listOf("coins 25000"),
    13 to listOf("pending winter_trail x1 Winter Trail Cosmetic"), 14 to listOf("credits 75"),
    15 to listOf("pending lifesteal_upgraded_crate_key x1 Upgraded Lifesteal Crate Key"), 16 to listOf("coins 30000"),
    17 to listOf("pending christmas_bell x1 Christmas Bell Collectible"), 18 to listOf("credits 100"),
    19 to listOf("pending lifesteal_premium_crate_key x1 Premium Lifesteal Crate Key"), 20 to listOf("coins 40000"),
    21 to listOf("chattagvoucher blizzard x1"), 22 to listOf("credits 100"),
    23 to listOf("pending lifesteal_standard_crate_key x2 Standard Lifesteal Crate Key"), 24 to listOf("coins 50000"),
    25 to listOf(
        "coins 100000", "credits 250", "pending lifesteal_premium_crate_key x3 Premium Lifesteal Crate Key", "chattagvoucher christmas x1",
        "pending lifesteal_festive_cosmetic x1 Exclusive Festive Cosmetic", "pending christmas_{year}_collectible x1 Christmas {year} Collectible",
    ),
)

fun main() {
    scheduleChecks()
    dayStateChecks()
    val settings = configChecks()
    storeChecks()
    layoutAndWiringChecks(settings)
    println("Advent Calendar checks passed ($checks checks).")
}

private fun scheduleChecks() {
    fun open(a: Availability) = a as? Availability.Open
    // ── Automatic December schedule, midnight in New York ──
    check("Nov 30 23:59:59 ET is closed (not started)", (AdventRules.availability(true, DECEMBER, at(2026, 11, 30, 23, 59, 59)) as? Availability.Closed)?.reason == ClosedReason.NOT_STARTED)
    val notStarted = AdventRules.availability(true, DECEMBER, at(2026, 11, 30, 12)) as Availability.Closed
    check("before December it opens Dec 1 00:00 ET", notStarted.opensAt == ZonedDateTime.of(2026, 12, 1, 0, 0, 0, 0, NY))
    check("Dec 1 00:00 ET opens day 1", open(AdventRules.availability(true, DECEMBER, at(2026, 12, 1)))?.today == 1)
    check("Dec 1 is the 2026 calendar", open(AdventRules.availability(true, DECEMBER, at(2026, 12, 1)))?.year == 2026)
    // America/New_York in December is UTC-5: 04:59 UTC is still Nov 30 there, 05:00 UTC is Dec 1.
    check("Dec 1 04:59 UTC (Nov 30 in NY) is closed", AdventRules.availability(true, DECEMBER, Instant.parse("2026-12-01T04:59:59Z")) is Availability.Closed)
    check("Dec 1 05:00 UTC (midnight NY) is day 1", open(AdventRules.availability(true, DECEMBER, Instant.parse("2026-12-01T05:00:00Z")))?.today == 1)
    check("a fixed UTC offset would be wrong: Dec 1 00:00 UTC is still closed", AdventRules.availability(true, DECEMBER, Instant.parse("2026-12-01T00:00:00Z")) is Availability.Closed)
    check("Dec 7 23:59 is day 7", open(AdventRules.availability(true, DECEMBER, at(2026, 12, 7, 23, 59)))?.today == 7)
    check("Dec 8 00:00 is day 8 (date based, not 24h after a claim)", open(AdventRules.availability(true, DECEMBER, at(2026, 12, 8)))?.today == 8)
    val d24 = open(AdventRules.availability(true, DECEMBER, at(2026, 12, 24, 15)))!!
    check("Dec 24: next unlock is Dec 25 midnight", d24.nextUnlockAt == ZonedDateTime.of(2026, 12, 25, 0, 0, 0, 0, NY))
    val d25 = open(AdventRules.availability(true, DECEMBER, at(2026, 12, 25)))
    check("Dec 25 00:00 opens day 25", d25?.today == 25)
    check("Dec 25 has no next unlock", d25?.nextUnlockAt == null)
    check("Dec 25 23:59:59 is still day 25", open(AdventRules.availability(true, DECEMBER, at(2026, 12, 25, 23, 59, 59)))?.today == 25)
    val ended = AdventRules.availability(true, DECEMBER, at(2026, 12, 26)) as? Availability.Closed
    check("Dec 26 00:00 closes the calendar (no catch-up after the event)", ended?.reason == ClosedReason.ENDED)
    check("after Christmas it returns Dec 1 next year", ended?.opensAt == ZonedDateTime.of(2027, 12, 1, 0, 0, 0, 0, NY))
    check("January is closed", AdventRules.availability(true, DECEMBER, at(2027, 1, 15)) is Availability.Closed)
    check("next December reopens on its own as the 2027 calendar", open(AdventRules.availability(true, DECEMBER, at(2027, 12, 1)))?.year == 2027)

    // ── Precedence ──
    val override = DECEMBER.copy(overrideEnabled = true)
    check("enabled=false: disabled in December", AdventRules.availability(false, DECEMBER, at(2026, 12, 5)) == Availability.Disabled)
    check("enabled=false wins over the manual override", AdventRules.availability(false, override, at(2026, 7, 5)) == Availability.Disabled)
    check("enabled=false wins over override + automatic=false", AdventRules.availability(false, override.copy(automatic = false), at(2026, 12, 5)) == Availability.Disabled)
    val manualOnly = DECEMBER.copy(automatic = false)
    check("automatic=false without override stays closed in December", (AdventRules.availability(true, manualOnly, at(2026, 12, 5)) as? Availability.Closed)?.reason == ClosedReason.MANUAL_SCHEDULE)
    check("automatic=false has no opening date", (AdventRules.availability(true, manualOnly, at(2026, 12, 5)) as? Availability.Closed)?.opensAt == null)
    check("automatic=false + override opens it", AdventRules.availability(true, manualOnly.copy(overrideEnabled = true), at(2026, 12, 5)) is Availability.Open)

    // ── Manual override: date restriction only ──
    val july = open(AdventRules.availability(true, override, at(2026, 7, 5)))
    check("override in July opens the calendar", july != null && july.manual)
    check("override by date before December: nothing unlocked (future locks kept)", july?.today == 0)
    check("override after Christmas: every day is past (catch-up stays open)", open(AdventRules.availability(true, override, at(2026, 12, 28)))?.today == 26)
    check("override in December follows the date", open(AdventRules.availability(true, override, at(2026, 12, 9)))?.today == 9)
    val forced = open(AdventRules.availability(true, override.copy(overrideDay = 7, overrideYear = 2026), at(2027, 3, 1)))
    check("override current-day 7 / year 2026 in March 2027", forced?.today == 7 && forced.year == 2026)
    check("override with a fixed day has no midnight unlock", forced?.nextUnlockAt == null)
    check("override of last year in January: all 25 days past", open(AdventRules.availability(true, override.copy(overrideYear = 2026), at(2027, 1, 10)))?.today == 26)

    // ── Dates ──
    check("day 7 of 2026 is December 7", DECEMBER.dateOf(2026, 7) == java.time.LocalDate.of(2026, 12, 7))
    check("day 25 unlocks Dec 25 00:00 ET", DECEMBER.unlockAt(2026, 25) == ZonedDateTime.of(2026, 12, 25, 0, 0, 0, 0, NY))
}

private fun dayStateChecks() {
    check("today unclaimed = AVAILABLE", AdventRules.dayState(5, 5, claimed = false, catchUpEnabled = true, freePass = false) == DayState.AVAILABLE)
    check("today claimed = CLAIMED", AdventRules.dayState(5, 5, claimed = true, catchUpEnabled = true, freePass = false) == DayState.CLAIMED)
    check("future = LOCKED", AdventRules.dayState(6, 5, claimed = false, catchUpEnabled = true, freePass = false) == DayState.LOCKED)
    check("future with a free pass is still LOCKED", AdventRules.dayState(6, 5, claimed = false, catchUpEnabled = true, freePass = true) == DayState.LOCKED)
    check("past unclaimed = MISSED", AdventRules.dayState(4, 5, claimed = false, catchUpEnabled = true, freePass = false) == DayState.MISSED)
    check("past claimed = CLAIMED", AdventRules.dayState(4, 5, claimed = true, catchUpEnabled = true, freePass = true) == DayState.CLAIMED)
    check("past with a free pass = MISSED_FREE", AdventRules.dayState(4, 5, claimed = false, catchUpEnabled = true, freePass = true) == DayState.MISSED_FREE)
    check("free pass works with catch-up off", AdventRules.dayState(4, 5, claimed = false, catchUpEnabled = false, freePass = true) == DayState.MISSED_FREE)
    check("catch-up off = MISSED_CLOSED", AdventRules.dayState(4, 5, claimed = false, catchUpEnabled = false, freePass = false) == DayState.MISSED_CLOSED)
    check("today 0: day 1 locked", AdventRules.dayState(1, 0, claimed = false, catchUpEnabled = true, freePass = false) == DayState.LOCKED)
    check("today 26: day 25 missed", AdventRules.dayState(25, 26, claimed = false, catchUpEnabled = true, freePass = false) == DayState.MISSED)

    check("today is free", AdventRules.price(DayState.AVAILABLE, 25.0) == 0.0)
    check("missed costs the configured Credits", AdventRules.price(DayState.MISSED, 25.0) == 25.0)
    check("free pass is free", AdventRules.price(DayState.MISSED_FREE, 25.0) == 0.0)
    for (s in listOf(DayState.CLAIMED, DayState.LOCKED, DayState.MISSED_CLOSED)) check("$s can't be bought", AdventRules.price(s, 25.0) == null)
    check("claim types", AdventRules.claimType(DayState.AVAILABLE) == AdventRules.ClaimType.TODAY &&
        AdventRules.claimType(DayState.MISSED) == AdventRules.ClaimType.CATCH_UP && AdventRules.claimType(DayState.MISSED_FREE) == AdventRules.ClaimType.FREE_PASS &&
        AdventRules.claimType(DayState.LOCKED) == null)

    // ── Reward lines ──
    fun ok(raw: String) = AdventRules.parseLine(raw) is AdventRules.Parsed.Ok
    check("coins line", (AdventRules.parseLine("coins 50000") as? AdventRules.Parsed.Ok)?.value == RewardLine.Grant(PurchaseRewards.Spec(PurchaseRewards.Kind.COINS, "50000", 1)))
    check("cratekey line", (AdventRules.parseLine("cratekey Campfire 2") as? AdventRules.Parsed.Ok)?.value == RewardLine.Grant(PurchaseRewards.Spec(PurchaseRewards.Kind.CRATE_KEY, "campfire", 2)))
    check("pending line keeps its label", (AdventRules.parseLine("pending snow_globe 1 Snow Globe Collectible") as? AdventRules.Parsed.Ok)?.value ==
        RewardLine.Pending("snow_globe", 1, "Snow Globe Collectible"))
    check("pending id may hold {year}", ok("pending christmas_{year}_collectible 1 Christmas {year} Collectible"))
    check("ranks are refused", !ok("rank champion"))
    check("rank vouchers are refused", !ok("rankvoucher champion 1"))
    check("money vouchers are refused", !ok("moneyvoucher big 1"))
    check("negative coins refused", !ok("coins -5"))
    check("NaN coins refused", !ok("coins NaN"))
    check("zero coins refused", !ok("coins 0"))
    check("a trailing extra word is refused (no store reference)", !ok("coins 100 extra"))
    check("pending without a label refused", !ok("pending snow_globe 1"))
    check("pending with a bad id refused", !ok("pending Snow-Globe 1 Snow Globe"))
    check("pending with zero quantity refused", !ok("pending snow_globe 0 Snow Globe"))
    check("unknown kind refused", !ok("diamonds 5"))
    check("placeholder -> crate key x3", (AdventRules.resolvePlaceholder("cratekey premium", 3) as? AdventRules.Parsed.Ok)?.value ==
        PurchaseRewards.Spec(PurchaseRewards.Kind.CRATE_KEY, "premium", 3))
    check("placeholder -> chat tag", AdventRules.resolvePlaceholder("chattagvoucher christmas", 1) is AdventRules.Parsed.Ok)
    check("placeholder -> tokens", (AdventRules.resolvePlaceholder("tokens", 4) as? AdventRules.Parsed.Ok)?.value?.quantity == 4)
    check("blank placeholder isn't set up", AdventRules.resolvePlaceholder("", 1) is AdventRules.Parsed.Error)
    check("placeholder can't become coins", AdventRules.resolvePlaceholder("coins", 1) is AdventRules.Parsed.Error)
    check("placeholder can't become a rank", AdventRules.resolvePlaceholder("rank champion", 1) is AdventRules.Parsed.Error)
    check("{year} expands", AdventRules.expandYear("christmas_{year}_collectible", 2027) == "christmas_2027_collectible")
}

private fun configChecks(): AdventSettings {
    val result = parse(BUNDLED)
    check("bundled advent-calendar.yml has no problems: ${result.problems}", result.problems.isEmpty() && result.settings != null)
    val s = result.settings!!
    check("enabled by default", s.enabled)
    check("automatic schedule by default", s.schedule.automatic && !s.schedule.overrideEnabled)
    check("America/New_York by default", s.schedule.zone == NY)
    check("Dec 1 - Dec 25", s.schedule.startMonth == 12 && s.schedule.startDay == 1 && s.schedule.endMonth == 12 && s.schedule.endDay == 25)
    check("missed days on, 25 Credits", s.catchUpEnabled && s.catchUpCost == 25.0)

    for ((gm, expected) in listOf(ServerGamemode.SURVIVAL to SURVIVAL_EXPECTED, ServerGamemode.LIFESTEAL to LIFESTEAL_EXPECTED)) {
        val cal = s.calendar(gm)
        check("${gm.id} calendar exists and is enabled", cal != null && cal.enabled)
        val days = cal!!.rewardsFor(2026)!!
        check("${gm.id}: 25 days", days.keys == (1..25).toSet())
        for (day in 1..25) {
            check("${gm.id} day $day matches the issue: ${days[day]?.map { text(it) }}", days[day]?.map { text(it) } == expected[day])
        }
        check("${gm.id}: the same calendar returns in 2027 (no edits needed)", cal.rewardsFor(2027) == days)
        check("${gm.id}: Christmas Day has the whole bundle (6 rewards)", days[25]!!.size == 6)
    }
    val (sCoins, sCredits) = AdventRules.currencyTotals(s.calendar(ServerGamemode.SURVIVAL)!!.rewardsFor(2026)!!)
    check("Survival total 1,450,000 Coins (got $sCoins)", sCoins == 1_450_000.0)
    check("Survival total 1,400 Credits (got $sCredits)", sCredits == 1_400.0)
    val (lCoins, lCredits) = AdventRules.currencyTotals(s.calendar(ServerGamemode.LIFESTEAL)!!.rewardsFor(2026)!!)
    check("Lifesteal total 290,000 Coins (got $lCoins)", lCoins == 290_000.0)
    check("Lifesteal total 700 Credits (got $lCredits)", lCredits == 700.0)

    // Every placeholder a calendar uses is listed (and none is set up: nothing invented).
    val used = s.calendars.values.flatMap { it.rewardsFor(2026)!!.values.flatten() }.filterIsInstance<RewardLine.Pending>()
        .map { AdventRules.expandYear(it.placeholderId, 2026) }.toSet()
    check("every placeholder used in 2026 is listed: ${used - s.placeholders.keys}", s.placeholders.keys.containsAll(used))
    check("no placeholder is mapped to a guessed reward", s.placeholders.values.all { it.isBlank() })

    // Chat tags the calendars hand out exist in the bundled tags.yml voucher category.
    val tags = YamlConfiguration.loadConfiguration(File("src/main/resources/tags.yml"))
    for (tag in listOf("snowflake", "holly", "frostbite", "blizzard", "christmas")) {
        check("tags.yml voucher tag $tag exists", tags.getString("tags.voucher.$tag.permission") == "joshymc.tag.voucher.$tag")
    }

    // ── Validation: mistakes close the calendar, never guess ──
    check("24-day schedule refused", bundledWith("schedule.end-day", 24).settings == null)
    check("26-day schedule refused", bundledWith("schedule.end-day", 26).settings == null)
    check("a 25-day November schedule is fine", bundledWith("schedule.start-month", 11).let { r ->
        val y = YamlConfiguration(); y.loadFromString(BUNDLED); y.set("schedule.start-month", 11); y.set("schedule.end-month", 11)
        AdventConfig.parse(source(y)).settings != null && r.settings == null
    })
    check("bad timezone refused", bundledWith("schedule.timezone", "Mars/Olympus").settings == null)
    check("enabled must be a boolean", bundledWith("enabled", "maybe").settings == null)
    check("enabled: false parses (and keeps the calendars)", bundledWith("enabled", false).settings?.let { !it.enabled && it.calendars.size == 2 } == true)
    check("negative catch-up price refused", bundledWith("missed-days.cost-credits", -1).settings == null)
    check("catch-up price can change", bundledWith("missed-days.cost-credits", 40).settings?.catchUpCost == 40.0)
    check("catch-up can be turned off", bundledWith("missed-days.enabled", false).settings?.catchUpEnabled == false)
    check("a missing day refuses the calendar", bundledWith("rewards.survival.default.13", null).settings == null)
    check("a bad reward line refuses the calendar", bundledWith("rewards.survival.default.2", listOf("credits lots")).settings == null)
    check("day 26 refused", bundledWith("rewards.survival.default.26", listOf("coins 5")).settings == null)
    check("override day out of range refused", bundledWith("manual-override.current-day", 30).settings == null)
    check("override year 1999 refused", bundledWith("manual-override.year", 1999).settings == null)
    check("bad placeholder mapping refused", bundledWith("placeholders.snow_globe", "rank champion").settings == null)
    check("a mapped placeholder is accepted", bundledWith("placeholders.snow_globe", "cratekey premium").settings?.placeholders?.get("snow_globe") == "cratekey premium")
    check("override switch parses", bundledWith("manual-override.enabled", true).settings?.schedule?.overrideEnabled == true)
    check("a gamemode can be switched off on its own", bundledWith("rewards.lifesteal.enabled", false).settings?.calendar(ServerGamemode.LIFESTEAL)?.enabled == false)

    // A future December configured on its own; 2026 keeps the default.
    val y = YamlConfiguration()
    y.loadFromString(BUNDLED)
    for (day in 1..25) y.set("rewards.survival.years.2027.$day", listOf("coins ${day * 10}"))
    val withYear = AdventConfig.parse(source(y)).settings
    check("years.2027 parses", withYear != null)
    check("2027 uses its own calendar", withYear!!.calendar(ServerGamemode.SURVIVAL)!!.rewardsFor(2027)!![3]!!.map { text(it) } == listOf("coins 30"))
    check("2026 still uses the default", withYear.calendar(ServerGamemode.SURVIVAL)!!.rewardsFor(2026)!![3]!!.map { text(it) } == listOf("cratekey cabin x1"))
    y.set("rewards.survival.years.2028.1", listOf("coins 1"))
    check("an incomplete year is refused", AdventConfig.parse(source(y)).settings == null)
    return s
}

private class FakeWallet(start: Double) : AdventStore.Wallet {
    @Volatile var balance = start
    val withdrawals = AtomicInteger()
    override fun withdraw(uuid: UUID, amount: Double): Boolean = synchronized(this) {
        if (balance < amount) return false
        balance -= amount
        withdrawals.incrementAndGet()
        true
    }
}

private fun reward(kind: String, param: String, qty: Int = 1, state: AdventStore.RewardState = AdventStore.RewardState.READY, why: String? = null) =
    AdventStore.NewReward(kind, param, qty, "$kind $param", state, why)

private fun request(uuid: UUID, day: Int, isToday: Boolean, price: Double, gm: String = "survival", year: Int = 2026,
                    catchUp: Boolean = true, cost: Double = 25.0, rewards: List<AdventStore.NewReward> = listOf(reward("coins", "50000"))) =
    AdventStore.ClaimRequest(gm, year, day, uuid, "P-${uuid.toString().take(4)}", isToday, catchUp, cost, price, rewards, 1000L + day)

private fun storeChecks() {
    val dir = Files.createTempDirectory("joshymc-advent-check").toFile()
    val alice = UUID.randomUUID()
    val bob = UUID.randomUUID()
    try {
        withDb(dir) { db ->
            val wallet = FakeWallet(60.0)
            val store = AdventStore(db, wallet)
            store.createSchema()
            store.createSchema() // idempotent

            // Today's present: free, once.
            val first = store.claim(request(alice, 5, isToday = true, price = 0.0))
            check("today's present is claimed", first is AdventStore.ClaimResult.Claimed)
            first as AdventStore.ClaimResult.Claimed
            check("claim type TODAY, nothing paid", first.claim.type == AdventRules.ClaimType.TODAY && first.claim.creditsPaid == 0.0)
            check("the reward snapshot is stored", store.rewardsOf(first.claim.id).map { it.kind to it.param } == listOf("coins" to "50000"))
            check("no Credits taken for today", wallet.withdrawals.get() == 0)
            check("second claim of the same day refused", store.claim(request(alice, 5, isToday = true, price = 0.0)) == AdventStore.ClaimResult.AlreadyClaimed)
            check("claimed days", store.claimedDays("survival", 2026, alice) == setOf(5))

            // Catch-up for Credits.
            check("price mismatch refused (menu said free, it costs 25)", store.claim(request(alice, 3, isToday = false, price = 0.0)) == AdventStore.ClaimResult.PriceChanged(25.0))
            check("refused price took nothing", wallet.withdrawals.get() == 0 && store.claimedDays("survival", 2026, alice) == setOf(5))
            val paid = store.claim(request(alice, 3, isToday = false, price = 25.0))
            check("missed day recovered for 25 Credits", (paid as? AdventStore.ClaimResult.Claimed)?.claim?.let { it.type == AdventRules.ClaimType.CATCH_UP && it.creditsPaid == 25.0 } == true)
            check("Credits taken exactly once", wallet.withdrawals.get() == 1 && wallet.balance == 35.0)
            check("recovering it again refused, nothing taken", store.claim(request(alice, 3, isToday = false, price = 25.0)) == AdventStore.ClaimResult.AlreadyClaimed && wallet.balance == 35.0)
            store.claim(request(alice, 2, isToday = false, price = 25.0))
            check("balance 10 after two recoveries", wallet.balance == 10.0)
            check("insufficient Credits refused", store.claim(request(alice, 1, isToday = false, price = 25.0)) == AdventStore.ClaimResult.InsufficientCredits(25.0))
            check("insufficient Credits: no claim row, no reward rows", 1 !in store.claimedDays("survival", 2026, alice) &&
                store.claims("survival", alice, 2026).size == 3)
            check("catch-up off: refused", store.claim(request(bob, 1, isToday = false, price = 25.0, catchUp = false)) == AdventStore.ClaimResult.NotRecoverable)
            check("changed price refused (menu said 25, config now 40)", store.claim(request(bob, 1, isToday = false, price = 25.0, cost = 40.0)) == AdventStore.ClaimResult.PriceChanged(40.0))

            // Free passes (outage recovery).
            check("grant free pass to everyone for day 1", store.grantFreePass("survival", 2026, 1, null, "Staff", "outage", 1L))
            check("granting it twice does nothing", !store.grantFreePass("survival", 2026, 1, null, "Staff", "outage", 2L))
            check("free pass shows for alice", 1 in store.freePassDays("survival", 2026, alice))
            check("free pass: charging is refused (price is 0 now)", store.claim(request(alice, 1, isToday = false, price = 25.0)) == AdventStore.ClaimResult.PriceChanged(0.0))
            val free = store.claim(request(alice, 1, isToday = false, price = 0.0))
            check("free pass claim is free", (free as? AdventStore.ClaimResult.Claimed)?.claim?.type == AdventRules.ClaimType.FREE_PASS && wallet.balance == 10.0)
            check("free pass works with catch-up off", store.claim(request(bob, 1, isToday = false, price = 0.0, catchUp = false)) is AdventStore.ClaimResult.Claimed)
            check("free pass never gives a second claim", store.claim(request(alice, 1, isToday = false, price = 0.0)) == AdventStore.ClaimResult.AlreadyClaimed)
            check("a personal free pass", store.grantFreePass("survival", 2026, 4, bob, "Staff", null, 3L) && 4 in store.freePassDays("survival", 2026, bob) && 4 !in store.freePassDays("survival", 2026, alice))
            check("revoke a free pass", store.revokeFreePass("survival", 2026, 4, bob, "Staff", 4L) && 4 !in store.freePassDays("survival", 2026, bob))
            check("free passes are per gamemode", 1 !in store.freePassDays("lifesteal", 2026, alice))
            check("free passes are per year", 1 !in store.freePassDays("survival", 2027, alice))

            // Years and gamemodes are separate claims.
            check("2027 day 5 is a new claim", store.claim(request(alice, 5, isToday = true, price = 0.0, year = 2027)) is AdventStore.ClaimResult.Claimed)
            check("Lifesteal day 5 is a separate claim", store.claim(request(alice, 5, isToday = true, price = 0.0, gm = "lifesteal")) is AdventStore.ClaimResult.Claimed)
            check("Survival 2026 untouched by the others", store.claimedDays("survival", 2026, alice) == setOf(1, 2, 3, 5))
            check("Lifesteal claims separate", store.claimedDays("lifesteal", 2026, alice) == setOf(5))

            // Waiting / ready / queued bookkeeping (Christmas bundle with placeholders).
            val bundle = store.claim(request(bob, 25, isToday = true, price = 0.0, rewards = listOf(
                reward("coins", "500000"), reward("credits", "500"),
                reward(AdventStore.PENDING_KIND, "survival_premium_crate_key", 3, AdventStore.RewardState.WAITING, "not set up"),
                reward("chattagvoucher", "christmas"),
            ))) as AdventStore.ClaimResult.Claimed
            check("the whole bundle is one claim with 4 rows", bundle.rewards.size == 4 && store.rewardsOf(bundle.claim.id).size == 4)
            val ready = store.rewardsIn("survival", AdventStore.RewardState.READY, bob).filter { it.claimId == bundle.claim.id }
            check("bundle: 3 rows ready to deliver", ready.size == 3)
            check("bob's earlier free-pass reward is a separate ready row", store.rewardsIn("survival", AdventStore.RewardState.READY, bob).size == 4)
            val coinsRow = ready.first { it.kind == "coins" }
            check("READY -> QUEUED once", store.markQueued(coinsRow.id, 77L, 5L))
            check("a second mark is refused (no second delivery)", !store.markQueued(coinsRow.id, 78L, 6L))
            check("queued row keeps its first delivery id", store.reward(coinsRow.id)?.deliveryId == 77L)
            val waiting = store.rewardsIn("survival", AdventStore.RewardState.WAITING, null)
            check("one waiting placeholder", waiting.size == 1 && waiting[0].param == "survival_premium_crate_key" && waiting[0].quantity == 3)
            val spec = PurchaseRewards.Spec(PurchaseRewards.Kind.CRATE_KEY, "joshy", 3)
            check("waiting -> ready once mapped", store.resolveWaiting(waiting[0].id, spec, 7L))
            check("resolving twice does nothing", !store.resolveWaiting(waiting[0].id, spec, 8L))
            check("resolved row now carries the crate key", store.reward(waiting[0].id)?.spec == spec)
            check("a ready row can go back to waiting (crate deleted)", store.backToWaiting(waiting[0].id, "crate gone", 9L))
            check("queued rows never go back", !store.backToWaiting(coinsRow.id, "x", 10L))
            check("state counts", store.stateCounts("survival").let { it[AdventStore.RewardState.QUEUED] == 1 && it[AdventStore.RewardState.WAITING] == 1 })
            check("failed list is empty without the delivery table", store.failedDeliveries("survival").isEmpty())

            // Concurrent claims of one day: exactly one wins, Credits taken once.
            val carol = UUID.randomUUID()
            val rich = FakeWallet(1000.0)
            val raceStore = AdventStore(db, rich)
            val pool = Executors.newFixedThreadPool(8)
            val go = CountDownLatch(1)
            val results = Collections.synchronizedList(mutableListOf<AdventStore.ClaimResult>())
            repeat(16) { pool.submit { go.await(); results += raceStore.claim(request(carol, 2, isToday = false, price = 25.0)) } }
            go.countDown()
            pool.shutdown()
            check("race finished", pool.awaitTermination(30, TimeUnit.SECONDS))
            check("exactly one concurrent claim wins", results.count { it is AdventStore.ClaimResult.Claimed } == 1)
            check("the rest are AlreadyClaimed", results.count { it == AdventStore.ClaimResult.AlreadyClaimed } == 15)
            check("Credits taken exactly once in the race", rich.withdrawals.get() == 1 && rich.balance == 975.0)
        }

        // Restart: everything persisted.
        withDb(dir) { db ->
            val store = AdventStore(db, FakeWallet(0.0))
            store.createSchema()
            check("claims survive a restart", store.claimedDays("survival", 2026, alice) == setOf(1, 2, 3, 5))
            check("no second claim after a restart", store.claim(request(alice, 5, isToday = true, price = 0.0)) == AdventStore.ClaimResult.AlreadyClaimed)
            check("waiting rows survive a restart", store.rewardsIn("survival", AdventStore.RewardState.WAITING, null).size == 1)
            check("queued rows keep their delivery after a restart", store.rewardsIn("survival", AdventStore.RewardState.QUEUED, null).single().deliveryId == 77L)
            check("history lists every year", store.claims("survival", alice, null).map { it.year }.toSet() == setOf(2026, 2027))
            check("audit rows written", (db.queryFirst("SELECT COUNT(*) AS n FROM advent_audit") { it.getInt("n") } ?: 0) >= 10)
        }
    } finally {
        dir.deleteRecursively()
    }
}

private fun layoutAndWiringChecks(settings: AdventSettings) {
    val slots = (1..25).map { AdventLayout.slotOf(it) }
    check("25 distinct present slots", slots.toSet().size == 25)
    check("presents fit the 54-slot menu", slots.all { it in 0 until AdventLayout.SIZE })
    check("Christmas Day is bottom right of the grid (slot 51)", AdventLayout.slotOf(25) == 51)
    check("day 1 is top left of the grid (slot 11)", AdventLayout.slotOf(1) == 11)
    val decor = AdventLayout.BORDER_SLOTS + AdventLayout.SAPLING_SLOTS + AdventLayout.INFO_SLOT + AdventLayout.CLOSE_SLOT
    check("decorations never cover a present", decor.none { it in slots })
    check("decorations are few (no full filler)", decor.size <= 13)

    check("advent is NETWORK scope", GamemodeAssignments.COMMANDS["advent"] == GamemodeScope.NETWORK)
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("plugin.yml declares /advent with aliases", Regex("""(?m)^  advent:\n(    .*\n)*    aliases: \[adventcalendar, christmas\]\n    permission: joshymc\.advent\.use$""").containsMatchIn(pluginYml))
    check("joshymc.advent.use defaults to true", Regex("""(?m)^  joshymc\.advent\.use:\n    description: .*\n    default: true$""").containsMatchIn(pluginYml))
    check("joshymc.advent.admin is op", Regex("""(?m)^  joshymc\.advent\.admin:\n    description: .*\n    default: op$""").containsMatchIn(pluginYml))
    check("joshymc.advent.admin.recover is op", Regex("""(?m)^  joshymc\.advent\.admin\.recover:\n    description: .*\n    default: op$""").containsMatchIn(pluginYml))
    val commandManager = File("src/main/kotlin/com/liam/joshymc/manager/CommandManager.kt").readText()
    check("CommandManager wires /advent", commandManager.contains("getCommand(\"advent\")") && commandManager.contains("AdventCommand(plugin)"))
    val main = File("src/main/kotlin/com/liam/joshymc/Joshymc.kt").readText()
    check("Joshymc starts, reloads and stops the manager", main.contains("adventCalendarManager.start()") && main.contains("adventCalendarManager.reload()") &&
        main.contains("shutdown.step(\"adventCalendarManager.stop\", { ::adventCalendarManager.isInitialized })"))
    val npc = File("src/main/kotlin/com/liam/joshymc/manager/NPCManager.kt").readText()
    check("NPC clicks run the command as the player (no console path)", npc.contains("player.performCommand(resolved)") && !npc.contains("dispatchCommand(Bukkit.getConsoleSender()"))
    val command = File("src/main/kotlin/com/liam/joshymc/advent/AdventCommand.kt").readText()
    check("setnpc sets the NPC's command to advent", command.contains("plugin.npcManager.setCommand(id, \"advent\")"))
    check("settings were parsed for the wiring checks", settings.calendars.size == 2)
}
