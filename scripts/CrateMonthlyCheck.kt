// Checks the monthly crate rotation (issue #1520): month selection, the America/New_York
// calendar (month boundaries, DST, leap years), the admin override (persistence, expiry, reset),
// month -> crate mapping and its fallbacks, the bundled config, and the wiring that keeps keys,
// rewards, displays and menus consistent. The Gradle build has no test source set, so it runs
// through an init script, from the repo root:
//
//   ./gradlew -I scripts/crate-monthly-check.init.gradle.kts crateMonthlyCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.crate.CrateModels
import com.liam.joshymc.crate.CrateMonthlyOverrideStore
import com.liam.joshymc.crate.CrateMonthlyRotation
import com.liam.joshymc.crate.CrateMonthlyRotation.CrateSource
import com.liam.joshymc.crate.CrateMonthlyRotation.ExpiryParse
import com.liam.joshymc.crate.CrateMonthlyRotation.Override
import com.liam.joshymc.manager.DatabaseManager
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.Month
import java.time.ZoneId
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

private class MapSource(private val values: Map<String, Any?>) : CrateModels.Source {
    override fun keys(path: String): Set<String> {
        val prefix = if (path.isEmpty()) "" else "$path."
        return values.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('.') }.toSet()
    }

    override fun value(path: String): Any? = values[path]
}

private val NY = ZoneId.of("America/New_York")

/** Crates that "exist" in these checks; ids are matched like CrateManager.findCrateId (case-insensitive). */
private val CRATES = setOf("monthly", "cabin") + CrateModels.MONTH_IDS.map { "${it}_2027" }
private val find: (String) -> String? = { id -> CRATES.firstOrNull { it.equals(id, ignoreCase = true) } }
private val all: (String) -> Boolean = { true }

private fun settings(vararg extra: Pair<String, Any?>): CrateModels.Settings {
    val values = linkedMapOf<String, Any?>(
        "enabled" to true,
        "models.monthly.model" to "joshymc:harvest_chest",
        "models.monthly.scale" to 1.25,
        "models.cabin.model" to "joshymc:cabin_chest",
        "monthly.enabled" to true,
        "monthly.timezone" to "America/New_York",
        "monthly.crates" to listOf("monthly"),
    )
    for (id in CrateModels.MONTH_IDS) {
        values["monthly.models.$id"] = "joshymc:${id}_chest"
        values["monthly.crate-ids.$id"] = "${id}_2027"
    }
    values.putAll(extra)
    return CrateModels.parse(MapSource(values))
}

private fun et(local: String): Instant = java.time.LocalDateTime.parse(local).atZone(NY).toInstant()

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private fun body(text: String, start: String): String = text.substringAfter(start).substringBefore("\n    }\n")

fun main() {
    selection()
    calendar()
    overrides()
    store()
    fallbacks()
    config()
    wiring()
    println("All $checks monthly crate checks passed.")
}

// 1-12: every month selects its own crate and model.
private fun selection() {
    val s = settings()
    check("mapped config parses cleanly", s.problems.isEmpty() && s.monthly.active && s.monthly.crateIds.size == 12)
    for (month in Month.entries) {
        val id = CrateModels.monthId(month)
        val choice = CrateMonthlyRotation.crateFor(s, "monthly", month, find)
        check("${month.name}: opens the $id crate", choice.source == CrateSource.MONTHLY && choice.crateId == "${id}_2027")
        val model = CrateModels.resolve(s, "monthly", month, all)
        check("${month.name}: shows the $id model", model.source == CrateModels.ModelSource.MONTHLY && model.model?.model == "joshymc:${id}_chest")
        check("${month.name}: keeps the location's own placement", model.model?.scale == 1.25 && model.model?.crateId == "monthly")
        val at = et("2027-${"%02d".format(month.value)}-15T12:00:00")
        check("${month.name}: mid-month is ${month.name}", CrateMonthlyRotation.effectiveMonth(at, NY, null) == month)
    }
    check("a crate that doesn't rotate opens itself every month", Month.entries.all {
        CrateMonthlyRotation.crateFor(s, "cabin", it, find).let { c -> c.crateId == "cabin" && c.source == CrateSource.OWN } })
    check("mapped crate ids", CrateMonthlyRotation.mappedCrateIds(s, find) == CrateModels.MONTH_IDS.map { "${it}_2027" }.toSet())
    check("ids are matched case-insensitively", CrateMonthlyRotation.crateFor(settings("monthly.crate-ids.may" to "MAY_2027"), "monthly", Month.MAY, find).crateId == "may_2027")
    // 37. Concurrent openings: the choice is a pure function of the month, so every player
    // clicking a rotating location in one month gets the same crate.
    check("same month, same crate for everyone", (1..50).map { CrateMonthlyRotation.crateFor(s, "monthly", Month.JULY, find) }.toSet().size == 1)
}

// 13-17: the calendar in America/New_York.
private fun calendar() {
    // 13. Midnight Eastern on the 1st.
    val lastSecond = et("2027-01-31T23:59:59")
    check("Jan 31 23:59:59 ET is January", CrateMonthlyRotation.effectiveMonth(lastSecond, NY, null) == Month.JANUARY)
    check("Feb 1 00:00 ET is February", CrateMonthlyRotation.effectiveMonth(et("2027-02-01T00:00:00"), NY, null) == Month.FEBRUARY)
    check("05:00 UTC on Feb 1 is midnight in New York (EST)", et("2027-02-01T00:00:00") == Instant.parse("2027-02-01T05:00:00Z"))
    check("the check fires just past midnight", CrateMonthlyRotation.checkDelayMillis(lastSecond, NY, null) == 2000L)
    check("1 Feb 03:00 UTC is still January in New York", CrateMonthlyRotation.effectiveMonth(Instant.parse("2027-02-01T03:00:00Z"), NY, null) == Month.JANUARY)
    check("the rotation never waits more than an hour", CrateMonthlyRotation.checkDelayMillis(et("2027-01-10T00:00:00"), NY, null) == CrateModels.MAX_CHECK_DELAY_MILLIS)
    check("no fixed 30-day timer", source("crate/CrateMonthlyRotation.kt").let { "30L * 24" !in it && "plusDays(30)" !in it })
    // 14. EST -> EDT (14 March 2027): April starts at 04:00 UTC, March at 05:00 UTC.
    check("March 1 starts in EST", CrateModels.nextMonthStart(et("2027-02-15T12:00:00"), NY) == Instant.parse("2027-03-01T05:00:00Z"))
    check("April 1 starts in EDT", CrateMonthlyRotation.nextChange(et("2027-03-20T12:00:00"), NY, null) == Instant.parse("2027-04-01T04:00:00Z"))
    // 15. EDT -> EST (7 November 2027): November starts in EDT, December in EST.
    check("November 1 starts in EDT", CrateModels.nextMonthStart(et("2027-10-15T12:00:00"), NY) == Instant.parse("2027-11-01T04:00:00Z"))
    check("December 1 starts in EST", CrateModels.nextMonthStart(et("2027-11-15T12:00:00"), NY) == Instant.parse("2027-12-01T05:00:00Z"))
    check("the year end rolls into January", CrateModels.nextMonthStart(et("2027-12-31T23:00:00"), NY) == Instant.parse("2028-01-01T05:00:00Z"))
    // 16. Leap year.
    check("29 Feb 2028 is February", CrateMonthlyRotation.effectiveMonth(et("2028-02-29T23:59:00"), NY, null) == Month.FEBRUARY)
    check("March 2028 starts after the 29th", CrateModels.nextMonthStart(et("2028-02-28T12:00:00"), NY) == Instant.parse("2028-03-01T05:00:00Z"))
    check("March 2027 starts after the 28th", CrateModels.nextMonthStart(et("2027-02-28T12:00:00"), NY) == Instant.parse("2027-03-01T05:00:00Z"))
    // 17. Restart after a missed rotation: the month comes from the clock, never replayed.
    check("a start months later uses the current month", CrateMonthlyRotation.effectiveMonth(et("2027-09-03T08:00:00"), NY, null) == Month.SEPTEMBER)
}

// 20-24: the override.
private fun overrides() {
    val now = et("2027-03-10T12:00:00")
    val forever = Override(Month.OCTOBER, now.toEpochMilli(), null, "Admin")
    check("20. an override picks its month", CrateMonthlyRotation.effectiveMonth(now, NY, forever) == Month.OCTOBER)
    check("20. it also outlasts the calendar month", CrateMonthlyRotation.effectiveMonth(et("2027-05-01T00:00:01"), NY, forever) == Month.OCTOBER)
    check("20. it maps the override month's crate", CrateMonthlyRotation.crateFor(settings(), "monthly",
        CrateMonthlyRotation.effectiveMonth(now, NY, forever), find).crateId == "october_2027")
    val week = Override(Month.DECEMBER, now.toEpochMilli(), now.plusSeconds(7 * 86400).toEpochMilli(), "Admin")
    check("22. active before its expiry", CrateMonthlyRotation.overrideActive(now.plusSeconds(86400), week))
    check("22. back to the calendar month at expiry", CrateMonthlyRotation.effectiveMonth(now.plusSeconds(7 * 86400), NY, week) == Month.MARCH &&
        CrateMonthlyRotation.overrideExpired(now.plusSeconds(7 * 86400), week))
    check("22. the next check is at the expiry when that comes first",
        CrateMonthlyRotation.nextChange(now, NY, week) == now.plusSeconds(7 * 86400))
    val late = Override(Month.DECEMBER, now.toEpochMilli(), et("2027-06-01T00:00:00").toEpochMilli(), "Admin")
    check("22. ... and at the month start otherwise", CrateMonthlyRotation.nextChange(now, NY, late) == Instant.parse("2027-04-01T04:00:00Z"))
    check("17. an override that expired while offline is not applied", CrateMonthlyRotation.effectiveMonth(et("2027-08-01T12:00:00"), NY, week) == Month.AUGUST)
    check("no override = calendar", !CrateMonthlyRotation.overrideActive(now, null) && !CrateMonthlyRotation.overrideExpired(now, null))

    // Expiry input.
    check("no expiry", CrateMonthlyRotation.parseExpiry(null, now, NY) == ExpiryParse.Ok(null) &&
        CrateMonthlyRotation.parseExpiry("never", now, NY) == ExpiryParse.Ok(null))
    check("7d", CrateMonthlyRotation.parseExpiry("7d", now, NY) == ExpiryParse.Ok(now.toEpochMilli() + 7 * 86_400_000L))
    check("1d12h", CrateMonthlyRotation.parseExpiry("1d12h", now, NY) == ExpiryParse.Ok(now.toEpochMilli() + 36 * 3_600_000L))
    check("90m", CrateMonthlyRotation.parseExpiry("90m", now, NY) == ExpiryParse.Ok(now.toEpochMilli() + 90 * 60_000L))
    check("month-end is the next midnight on the 1st, Eastern", CrateMonthlyRotation.parseExpiry("month-end", now, NY) ==
        ExpiryParse.Ok(Instant.parse("2027-04-01T04:00:00Z").toEpochMilli()))
    for (bad in listOf("7", "abc", "0m", "-1d", "12h7d", "400d", "1y", "30s")) {
        check("24. refuses expiry '$bad'", CrateMonthlyRotation.parseExpiry(bad, now, NY) is ExpiryParse.Invalid)
    }
    // 24. Invalid months.
    check("24. month names only", CrateModels.monthOf("smarch") == null && CrateModels.monthOf("13") == null &&
        CrateModels.monthOf("jan") == null && CrateModels.monthOf("JANUARY") == Month.JANUARY)
    check("admin times are Eastern", CrateMonthlyRotation.formatInstant(Instant.parse("2027-04-01T04:00:00Z"), NY) == "2027-04-01 00:00 EDT")
}

// 21, 23, 24: persistence.
private fun store() {
    val dir = Files.createTempDirectory("joshymc-crate-monthly").toFile()
    try {
        val o = Override(Month.JULY, 1_000L, 2_000L, "Admin")
        DatabaseManager(dir, quietLogger()).let { db ->
            db.start()
            try {
                val s = CrateMonthlyOverrideStore(db)
                s.createTables()
                check("empty store", s.read() == CrateMonthlyOverrideStore.Loaded.None)
                s.save(o)
            } finally {
                db.shutdown()
            }
        }
        // 21. A new database connection (a restart) reads the same override back.
        DatabaseManager(dir, quietLogger()).let { db ->
            db.start()
            try {
                val s = CrateMonthlyOverrideStore(db)
                s.createTables()
                check("21. the override survives a restart", s.read() == CrateMonthlyOverrideStore.Loaded.Found(o))
                val forever = Override(Month.MAY, 3_000L, null, "Owner")
                s.save(forever)
                check("one override at a time; no expiry stored as NULL", s.read() == CrateMonthlyOverrideStore.Loaded.Found(forever) &&
                    db.queryFirst("SELECT COUNT(*) AS n FROM crate_monthly_override") { it.getInt("n") } == 1)
                check("an expiry clean-up never removes a newer override", !s.clearIf(o) && s.read() is CrateMonthlyOverrideStore.Loaded.Found)
                check("expiry clean-up removes its own override", s.clearIf(forever) && s.read() == CrateMonthlyOverrideStore.Loaded.None)
                s.save(o)
                check("23. reset removes it", s.clear() && s.read() == CrateMonthlyOverrideStore.Loaded.None && !s.clear())
                db.execute("INSERT INTO crate_monthly_override (id, month, set_at, expires_at, set_by) VALUES (1, 'smarch', 1, NULL, 'x')")
                check("24. an unreadable month is reported, not applied or deleted", s.read() == CrateMonthlyOverrideStore.Loaded.Unreadable("smarch") &&
                    db.queryFirst("SELECT COUNT(*) AS n FROM crate_monthly_override") { it.getInt("n") } == 1)
                check("only one row can exist", runCatching {
                    db.execute("INSERT INTO crate_monthly_override (id, month, set_at, expires_at, set_by) VALUES (2, 'may', 1, NULL, 'x')")
                }.isFailure)
            } finally {
                db.shutdown()
            }
        }
    } finally {
        dir.deleteRecursively()
    }
}

// 19, 25, 26: disabled rotation and fallbacks.
private fun fallbacks() {
    val off = settings("monthly.enabled" to false)
    check("19. disabled rotation opens the location's own crate", CrateMonthlyRotation.crateFor(off, "monthly", Month.MAY, find).let {
        it.crateId == "monthly" && it.source == CrateSource.OWN })
    check("19. disabled rotation maps nothing", CrateMonthlyRotation.mappedCrateIds(off, find).isEmpty())
    check("19. overrides are refused while rotation is off", CrateMonthlyRotation.overrideProblem(off, Month.MAY, all, find) != null)
    check("no rotating crate = refused", CrateMonthlyRotation.overrideProblem(settings("monthly.crates" to emptyList<String>()), Month.MAY, all, find) != null)
    check("rotating crate missing = refused", CrateMonthlyRotation.overrideProblem(settings("monthly.crates" to listOf("ghost")), Month.MAY, all, find) != null)
    check("a valid month is accepted", CrateMonthlyRotation.overrideProblem(settings(), Month.MAY, all, find) == null)
    // 25. Missing model.
    check("25. a month whose model isn't in the pack can't be forced", CrateMonthlyRotation.overrideProblem(settings(), Month.MAY,
        { it != "joshymc:may_chest" }, find)?.contains("resource pack") == true)
    check("25. a month without a model can't be forced", CrateMonthlyRotation.overrideProblem(settings("monthly.models.may" to ""), Month.MAY, all, find) != null)
    check("25. the display falls back to the static model", CrateModels.resolve(settings(), "monthly", Month.MAY, { it != "joshymc:may_chest" }).let {
        it.source == CrateModels.ModelSource.STATIC_FALLBACK && it.model?.model == "joshymc:harvest_chest" })
    // 26. Missing crate.
    val ghost = settings("monthly.crate-ids.june" to "ghost_crate")
    check("26. a mapped crate that doesn't exist falls back to the location's crate", CrateMonthlyRotation.crateFor(ghost, "monthly", Month.JUNE, find).let {
        it.crateId == "monthly" && it.source == CrateSource.FALLBACK && it.note != null })
    check("26. ... and can't be forced", CrateMonthlyRotation.overrideProblem(ghost, Month.JUNE, all, find) != null)
    check("26. other months are unaffected", CrateMonthlyRotation.crateFor(ghost, "monthly", Month.JULY, find).crateId == "july_2027")
    // Unmapped month: look-only rotation, exactly the #1460 behaviour.
    val partial = settings("monthly.crate-ids.august" to "")
    check("an unmapped month opens the location's own crate", CrateMonthlyRotation.crateFor(partial, "monthly", Month.AUGUST, find).let {
        it.crateId == "monthly" && it.source == CrateSource.OWN })
    check("bad crate id is reported, that month unmapped", settings("monthly.crate-ids.may" to "not a crate").let {
        it.problems.size == 1 && it.monthly.crateIdFor(Month.MAY) == null })
    check("bad month key is reported", settings("monthly.crate-ids.smarch" to "x").let { it.problems.size == 1 && it.monthly.crateIds.size == 12 })
}

// 39: configuration compatibility.
private fun config() {
    val legacy = CrateModels.parse(MapSource(buildMap {
        put("enabled", true); put("monthly.enabled", true); put("monthly.crates", listOf("monthly"))
        for (id in CrateModels.MONTH_IDS) put("monthly.models.$id", "joshymc:${id}_chest")
    }))
    check("39. a config without crate-ids parses cleanly", legacy.problems.isEmpty() && legacy.monthly.crateIds.isEmpty())
    check("39. without crate-ids every rotating location opens its own crate", Month.entries.all {
        CrateMonthlyRotation.crateFor(legacy, "monthly", it, find).crateId == "monthly" })
    check("39. the old Monthly constructor still works", CrateModels.Monthly(true, NY, setOf("x"), emptyMap()).crateIds.isEmpty())
    val bundled = File("src/main/resources/config.yml").readText()
    val monthly = bundled.substringAfter("    monthly:\n").substringBefore("  # Reward rarity labels")
    check("bundled config maps no live crate ids", "      crate-ids: {}" in monthly && "      crates: []" in monthly)
    check("bundled timezone is America/New_York", "      timezone: America/New_York" in monthly)
    for (id in CrateModels.MONTH_IDS) check("bundled $id model", "        $id: \"joshymc:${id}_chest\"" in monthly)
    for (id in CrateModels.MONTH_IDS) {
        check("the $id model is in the resource pack", File("resourcepack/assets/joshymc/items/${id}_chest.json").exists())
    }
}

// 18, 27-40: wiring.
private fun wiring() {
    val displays = source("crate/CrateModelDisplays.kt")
    val manager = source("manager/CrateManager.kt")
    val browser = source("crate/CrateBrowserMenu.kt")
    val command = source("command/CrateCommand.kt")
    val rotation = source("crate/CrateMonthlyRotation.kt") + source("crate/CrateMonthlyOverrideStore.kt")

    // 18. Reload / restart: the override is re-read, the task cancelled and rescheduled.
    val start = body(displays, "    fun start() {")
    check("18. start reads the stored override before the month", start.indexOf("loadOverride()") in 0 until start.indexOf("activeMonth = effectiveMonth()"))
    check("18. stop cancels the month check", body(displays, "    fun stop() {").contains("rotationTask?.cancel()"))
    check("one delayed check that uses the override's expiry", "CrateMonthlyRotation.checkDelayMillis(clock.instant(), settings.monthly.zone, override)" in displays &&
        "runTaskTimer" !in displays)
    // 22. Expiry is applied in the one month check.
    val checkMonth = body(displays, "    fun checkMonth(): Int {")
    check("22. the month check clears an expired override first", checkMonth.indexOf("expireOverride()") in 0 until checkMonth.indexOf("effectiveMonth()"))
    check("no redraw or menu refresh while the month is unchanged", checkMonth.indexOf("if (now == activeMonth) return 0") in 0 until checkMonth.indexOf("refreshRotating()"))
    check("30. menus are refreshed after a month change", "crates.onMonthlyRotation(previous, now)" in checkMonth && "browser.refreshOpen()" in manager)
    // 21. Set: saved before applied; a failed write applies nothing.
    val set = body(displays, "    fun setOverride(")
    check("21. set validates first", set.indexOf("overrideProblem(") in 0 until set.indexOf("overrideStore.save("))
    check("21. set saves before applying", set.indexOf("overrideStore.save(") in 0 until set.indexOf("override = o"))
    check("21. a failed save returns before applying", set.substringAfter("overrideStore.save(").substringBefore("override = o").contains("return"))
    val reset = body(displays, "    fun resetOverride(")
    check("23. reset clears the stored row, then goes back to the calendar", reset.indexOf("overrideStore.clear()") in 0 until reset.indexOf("checkMonth()"))
    check("an expiry never deletes a newer override", "overrideStore.clearIf(o)" in displays)

    // 27-29, 38. Displays: the same refresh path, the location's own facing/tuning are untouched.
    val refreshRotating = body(displays, "    private fun refreshRotating(): Int {")
    check("27/38. month changes redraw through refresh (old entity removed first)", "refresh(world, loc.x, loc.y, loc.z)" in refreshRotating && "if (want == have) continue" in refreshRotating)
    check("28/29. rotation never writes facing or placement", "setCrateFacing" !in rotation && "setCrateTuning" !in rotation &&
        "setCrateFacing" !in checkMonth && "setCrateTuning" !in set)
    check("28/29. placement still comes from the location", "fun placementFor(" in displays)
    check("never touches location rows", "crate_locations" !in displays && "crate_locations" !in rotation)

    // 31-36. Opening: the crate is resolved per click, keys are checked against that crate, stale picks refused.
    val typeAt = body(manager, "    fun getCrateTypeAt(block: Block): String? {")
    check("clicks resolve the crate through the rotation", "models.crateFor(registered).crateId" in typeAt)
    val click = body(manager, "    private fun handleCrateClick(")
    check("32. the key is checked against the crate this location opens now", "isKey(itemInHand, crateType)" in click)
    check("31. the click records where it came from", click.indexOf("pickOrigins[player.uniqueId] = registered to crateType") in 0 until click.indexOf("consumeOneKey(player)"))
    val stale = body(manager, "    private fun pickStale(")
    check("31. stale monthly picks are refused", "models.crateFor(origin.first).crateId != crate.id" in stale && "No key was used" in stale)
    check("36. pick confirms check staleness before the key is taken",
        manager.substringAfter("private fun openSelectConfirm(").substringBefore("private fun pickFrame(").let {
            it.indexOf("pickStale(p, crate)") in 0 until it.indexOf("consumeOneKey(") })
    check("34/35. a random open stores its winner before the animation", body(manager, "    private fun beginAnimatedOpening(").let {
        it.indexOf("registerPendingReward(") in 0 until it.indexOf("opening.play(player, block, crate.id) { revealRandom") })
    val onRotation = body(manager, "    fun onMonthlyRotation(")
    check("34. a month change never touches keys or pending rewards", listOf("consumeOneKey", "inFlightRewards", "crate_pending_rewards", "select3Sessions", "giveKey")
        .none { it in onRotation })
    check("pick origins are forgotten on quit", body(manager, "    fun onPlayerQuit(").contains("pickOrigins.remove("))
    // 33. Keys are never converted.
    for (word in listOf("crateKeyKey", "createKeyStack", "setItemMeta", "crate_key")) {
        check("33. rotation never touches keys ($word)", word !in rotation && word !in onRotation)
    }
    check("33. an old month's key still matches its own crate (key type resolution unchanged)",
        body(manager, "    fun getKeyType(item: ItemStack): String? {").contains("resolveCrateId("))

    // 30. Browser.
    check("30. the browser shows the crate each rotating crate opens now", "crates.models.crateFor(base.id).crateId" in browser)
    check("30. other months' mapped crates are not listed as permanent", "it.id in mapped" in browser)
    check("31. a stale browser click redraws instead of previewing last month", "crates.models.crateFor(baseId).crateId != crateId" in browser)
    check("browser refresh only reopens the exact tracked GUI", "plugin.guiManager.getOpenGui(player) !== view.first" in browser)
    check("browser viewers are forgotten on quit", "browser.forget(" in manager)
    check("availability counts rotating locations for this month's crate", "models.crateFor(it.crateType).crateId == crateId" in manager)

    // 40. Commands: admin only, audited.
    val monthlyCmd = body(command, "    private fun handleMonthly(")
    check("40. /crate monthly checks joshymc.crate.admin first", monthlyCmd.indexOf("hasPermission(\"joshymc.crate.admin\")") in 0 until monthlyCmd.indexOf("\"set\" ->"))
    check("40. set and reset are audited", "CRATE_MONTHLY_OVERRIDE_SET" in monthlyCmd && "CRATE_MONTHLY_OVERRIDE_RESET" in monthlyCmd)
    check("expiry is audited", "CRATE_MONTHLY_OVERRIDE_EXPIRED" in displays)
    check("40. tab completion is admin only", command.substringAfter("override fun onTabComplete(").split("\"monthly\" ->").drop(1).all {
        it.substringBefore("else emptyList()").contains("hasPermission(\"joshymc.crate.admin\")") })
    check("no new permission node", "joshymc.crate.monthly" !in command)
}
