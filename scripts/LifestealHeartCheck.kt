// Checks the Lifesteal heart system (issue #1341): the Bukkit-free rules, the heart store against
// a real SQLite database (fresh temp directories, deleted afterwards) including restarts,
// concurrency and rollback, and the Lifesteal-only wiring. The Gradle build has no test source
// set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-heart-check.init.gradle.kts lifestealHeartCheck
//
// Exits non-zero on the first failed check. Gameplay on a real Paper server is NOT covered here
// (see docs/lifesteal-hearts.md, "Manual tests").

import com.liam.joshymc.lifesteal.LifestealConfigParser
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.hearts.HeartRules.AdminDecision
import com.liam.joshymc.lifesteal.hearts.HeartRules.AdminOp
import com.liam.joshymc.lifesteal.hearts.HeartRules.Attribution
import com.liam.joshymc.lifesteal.hearts.HeartRules.Decision
import com.liam.joshymc.lifesteal.hearts.HeartRules.KillerAtMaximumPolicy
import com.liam.joshymc.lifesteal.hearts.HeartStore
import com.liam.joshymc.lifesteal.hearts.HeartStore.AdminResult
import com.liam.joshymc.lifesteal.hearts.HeartStore.PvpResult
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.SqliteDatabase
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.Collections
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

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }

private fun <T> withDb(dir: File, block: (DatabaseManager) -> T): T {
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        return block(db)
    } finally {
        db.shutdown()
    }
}

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private val LIMITS = HeartRules.Limits(starting = 10, minimum = 1, maximum = 20)
private const val COOLDOWN = 30 * 60_000L
private const val T0 = 1_800_000_000_000L

/** A Java (v4) and a Floodgate-style Bedrock UUID: both are plain UUIDs to the heart system. */
private val JAVA = UUID.randomUUID()
private val BEDROCK = UUID.fromString("00000000-0000-0000-0009-01f2a3b4c5d6")

private fun pvp(killer: UUID, victim: UUID, now: Long, key: String = "k:${UUID.randomUUID()}", limits: HeartRules.Limits = LIMITS, cooldown: Long = COOLDOWN) =
    HeartStore.PvpRequest(key, killer, victim, now, cooldown, limits)

fun main() {
    settings()
    rules()
    store()
    concurrency()
    rollback()
    wiring()
    heartItemSettings()
    heartItemRules()
    heartItemStore()
    heartItemConcurrency()
    heartItemRollback()
    heartItemWiring()
    heartItemConsumers()
    println("OK: $checks Lifesteal heart checks passed.")
}

// ── Heart items (issue #1344) ────────────────────────────────

private fun bundledLifestealYml(): Map<String, Any?> {
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    return Yaml().load(text)
}

private fun parseWith(map: Map<String, Any?>) =
    LifestealConfigParser.parse { p -> p.split('.').fold<String, Any?>(map) { node, key -> (node as? Map<*, *>)?.get(key) } }

@Suppress("UNCHECKED_CAST")
private fun withHeartItems(edit: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> {
    val map = bundledLifestealYml().toMutableMap()
    val section = (map["heart-items"] as Map<String, Any?>).toMutableMap()
    section["messages"] = (section["messages"] as Map<String, Any?>).toMutableMap()
    edit(section)
    map["heart-items"] = section
    return map
}

private fun heartItemSettings() {
    val parsed = parseWith(bundledLifestealYml())
    val items = parsed.settings!!.heartItems
    check("bundled heart-items = defaults", items == LifestealSettings.HeartItems())
    check("bundled file has no problems", parsed.problems.isEmpty())
    check("withdraw and redeem on by default", items.withdrawEnabled && items.redeemEnabled)
    check("no item model by default", items.itemModel == null)
    check("issue's withdraw message", items.messages.withdrawn == "&aYou withdrew &c1 Heart&a!")
    check("issue's redeem message", items.messages.redeemed == "&aYou redeemed &c1 Heart&a!")
    check("issue's final heart message", items.messages.finalHeart == "&cYou cannot withdraw your final heart!")
    check("issue's maximum message", items.messages.maximum == "&cYou have already reached the maximum of {maximum} hearts!")
    check("issue's inventory message", items.messages.inventoryFull == "&cYou need inventory space to withdraw a heart!")
    check("issue's failure message", items.messages.redeemFailed == "&cUnable to redeem this heart. Please try again.")

    val old = bundledLifestealYml().toMutableMap().apply { remove("heart-items") }
    val oldParsed = parseWith(old)
    check("a lifesteal.yml from before heart items still loads", oldParsed.settings?.heartItems == LifestealSettings.HeartItems())
    check("missing section is one warning", oldParsed.problems.size == 1 && !oldParsed.problems[0].severe)

    check("item model accepted", parseWith(withHeartItems { it["item-model"] = "joshymc:lifesteal_heart" }).settings?.heartItems?.itemModel == "joshymc:lifesteal_heart")
    check("bad item model refused", parseWith(withHeartItems { it["item-model"] = "Not A Key" }).settings == null)
    check("non-boolean toggle refused", parseWith(withHeartItems { it["withdraw-enabled"] = "yes" }).settings == null)
    check("negative cooldown refused", parseWith(withHeartItems { it["action-cooldown-millis"] = -5 }).settings == null)
    check("huge cooldown refused", parseWith(withHeartItems { it["action-cooldown-millis"] = 3_600_000 }).settings == null)
    check("withdraw can be turned off", parseWith(withHeartItems { it["withdraw-enabled"] = false }).settings?.heartItems?.withdrawEnabled == false)
    @Suppress("UNCHECKED_CAST")
    check("empty message refused", parseWith(withHeartItems { (it["messages"] as MutableMap<String, Any?>)["redeemed"] = " " }).settings == null)
    // Nothing in heart-items touches the limits: the final heart stays protected whatever it says.
    check("no heart-items key can change the limits", parseWith(withHeartItems { it["minimum"] = 0; it["allow-final-heart"] = true }).settings!!.hearts == LifestealSettings.Hearts())
}

private fun heartItemRules() {
    check("10 hearts withdraw to 9 (18 health)", HeartRules.withdraw(10, LIMITS) == HeartRules.WithdrawDecision.Withdraw(9) && HeartRules.healthFor(9) == 18.0)
    check("2 hearts withdraw to 1", HeartRules.withdraw(2, LIMITS) == HeartRules.WithdrawDecision.Withdraw(1))
    check("1 heart can't withdraw", HeartRules.withdraw(1, LIMITS) == HeartRules.WithdrawDecision.AtMinimum)
    check("below-minimum row can't withdraw", HeartRules.withdraw(0, LIMITS) == HeartRules.WithdrawDecision.AtMinimum)
    check("above-maximum row withdraws from the maximum", HeartRules.withdraw(25, LIMITS) == HeartRules.WithdrawDecision.Withdraw(19))
    check("8 hearts redeem to 9 (18 health)", HeartRules.redeem(8, LIMITS) == HeartRules.RedeemDecision.Redeem(9))
    check("19 hearts redeem to 20", HeartRules.redeem(19, LIMITS) == HeartRules.RedeemDecision.Redeem(20))
    check("20 hearts can't redeem", HeartRules.redeem(20, LIMITS) == HeartRules.RedeemDecision.AtMaximum)
    check("above-maximum row can't redeem", HeartRules.redeem(30, LIMITS) == HeartRules.RedeemDecision.AtMaximum)
    val serial = UUID.randomUUID().toString()
    check("issued serial parses", HeartRules.parseSerial(serial) == serial)
    check("upper-case serial refused", HeartRules.parseSerial(serial.uppercase()) == null)
    check("short serial refused", HeartRules.parseSerial("1-1-1-1-1") == null)
    check("garbage serial refused", HeartRules.parseSerial("Heart") == null && HeartRules.parseSerial("") == null && HeartRules.parseSerial(null) == null)
    check("cooldown: first action allowed", HeartRules.cooldownPassed(null, T0, 500))
    check("cooldown: rapid repeat refused", !HeartRules.cooldownPassed(T0, T0 + 100, 500))
    check("cooldown: after the gap allowed", HeartRules.cooldownPassed(T0, T0 + 500, 500))
    check("cooldown: clock going back refused", !HeartRules.cooldownPassed(T0 + 1_000, T0, 500))
    check("cooldown 0 = off", HeartRules.cooldownPassed(T0, T0, 0))
}

private fun serial() = UUID.randomUUID().toString()

private fun heartItemStore() {
    val dir = Files.createTempDirectory("joshymc-heart-items").toFile()
    try {
        val alice = JAVA
        val bob = BEDROCK
        val withdrawn = mutableListOf<String>()
        withDb(dir) { db ->
            val store = HeartStore(db)
            store.createTables()
            store.ensure(alice, 10, T0)
            store.ensure(bob, 10, T0)

            // Withdrawal.
            val s1 = serial()
            val w1 = store.withdraw(alice, s1, LIMITS, T0)
            check("withdraw 10 -> 9", w1 is HeartStore.WithdrawResult.Withdrawn && w1.before == 10 && w1.after == 9 && store.load(alice)!!.hearts == 9)
            val item = store.loadItem(s1)!!
            check("withdrawn item is ISSUED to the player", item.status == HeartStore.ItemStatus.ISSUED && item.source == HeartStore.ItemSource.WITHDRAW && item.issuedTo == alice)
            val tx = (w1 as HeartStore.WithdrawResult.Withdrawn).transactionId
            check("withdraw audit row", store.audit(alice).any { it.operation == "WITHDRAW" && it.previous == 10 && it.next == 9 && it.itemQuantity == 1 && it.itemSerial == s1 && it.transactionId == tx })
            check("record points at the withdrawal", store.load(alice)!!.lastTransaction == tx)
            check("a serial can't be withdrawn twice", runCatching { store.withdraw(alice, s1, LIMITS, T0) }.isFailure && store.load(alice)!!.hearts == 9)
            check("non-serial refused before touching the database", runCatching { store.withdraw(alice, "not-a-serial", LIMITS, T0) }.isFailure)
            withdrawn += s1

            // Down to 2, then 1, then refused.
            store.adminChange(bob, AdminOp.SET, 2, LIMITS, "console", "Console", T0)
            val w2 = store.withdraw(bob, serial(), LIMITS, T0)
            check("withdraw 2 -> 1", w2 is HeartStore.WithdrawResult.Withdrawn && w2.after == 1 && store.load(bob)!!.hearts == 1)
            val atMin = serial()
            val w3 = store.withdraw(bob, atMin, LIMITS, T0)
            check("withdraw at 1 refused", w3 == HeartStore.WithdrawResult.AtMinimum(1) && store.load(bob)!!.hearts == 1)
            check("no item for a refused withdrawal", store.loadItem(atMin) == null)
            check("refused withdrawal audited", store.audit(bob).any { it.operation == "WITHDRAW_REJECTED" && it.reason == "FINAL_HEART" && it.itemQuantity == 0 })
            check("no record: refused", store.withdraw(UUID.randomUUID(), serial(), LIMITS, T0) is HeartStore.WithdrawResult.Unavailable)

            // Redemption, by another player (trading): the item isn't tied to who withdrew it.
            val r1 = store.redeem(bob, s1, LIMITS, T0 + 1)
            check("bob redeems alice's heart 1 -> 2", r1 is HeartStore.RedeemResult.Redeemed && r1.before == 1 && r1.after == 2 && store.load(bob)!!.hearts == 2)
            check("item now REDEEMED by bob", store.loadItem(s1)!!.let { it.status == HeartStore.ItemStatus.REDEEMED && it.closedBy == bob.toString() })
            check("redeem audit row", store.audit(bob).any { it.operation == "REDEEM" && it.previous == 1 && it.next == 2 && it.itemQuantity == 1 && it.itemSerial == s1 })
            check("alice unaffected by the trade", store.load(alice)!!.hearts == 9)

            // A copy of a redeemed item (dupe, creative clone, rollback) is refused.
            val again = store.redeem(bob, s1, LIMITS, T0 + 2)
            check("second redemption of one serial refused", again is HeartStore.RedeemResult.AlreadyRedeemed && store.load(bob)!!.hearts == 2)
            check("copy refused for others too", store.redeem(alice, s1, LIMITS, T0 + 2) is HeartStore.RedeemResult.AlreadyRedeemed && store.load(alice)!!.hearts == 9)
            check("duplicate attempt audited", store.audit(alice).any { it.operation == "REDEEM_REJECTED" && it.reason == "ALREADY_REDEEMED" })

            // Forged / invalid items: no row was ever issued.
            check("forged serial refused", store.redeem(alice, serial(), LIMITS, T0) == HeartStore.RedeemResult.UnknownItem && store.load(alice)!!.hearts == 9)
            check("garbage serial refused", store.redeem(alice, "renamed nether star", LIMITS, T0) == HeartStore.RedeemResult.UnknownItem)
            check("forgery audited", store.audit(alice).any { it.operation == "REDEEM_REJECTED" && it.reason == "UNKNOWN_SERIAL" })

            // Maximum.
            store.adminChange(alice, AdminOp.SET, 20, LIMITS, "console", "Console", T0)
            val held = serial().also { store.withdraw(bob, it, LIMITS, T0) } // bob 2 -> 1, holds one item
            val atMax = store.redeem(alice, held, LIMITS, T0)
            check("redeem at 20 refused", atMax == HeartStore.RedeemResult.AtMaximum(20) && store.load(alice)!!.hearts == 20)
            check("refused item stays valid", store.loadItem(held)!!.status == HeartStore.ItemStatus.ISSUED)
            check("refusal at maximum audited", store.audit(alice).any { it.operation == "REDEEM_REJECTED" && it.reason == "AT_MAXIMUM" })
            store.adminChange(alice, AdminOp.SET, 19, LIMITS, "console", "Console", T0)
            check("redeem at 19 -> 20", (store.redeem(alice, held, LIMITS, T0) as? HeartStore.RedeemResult.Redeemed)?.after == 20)

            // Several items, each exactly one heart.
            store.adminChange(alice, AdminOp.SET, 10, LIMITS, "console", "Console", T0)
            val three = List(3) { serial().also { s -> store.withdraw(alice, s, LIMITS, T0) } }
            check("three withdrawals -> 7", store.load(alice)!!.hearts == 7 && three.all { store.loadItem(it)!!.status == HeartStore.ItemStatus.ISSUED })
            for (s in three) store.redeem(alice, s, LIMITS, T0)
            check("three redemptions -> 10", store.load(alice)!!.hearts == 10 && three.all { store.loadItem(it)!!.status == HeartStore.ItemStatus.REDEEMED })
            withdrawn += three

            // Staff grants: recorded, hearts unchanged.
            val grantSerials = List(3) { serial() }
            val g = store.grantItems(bob, grantSerials, "console", "Console", T0)
            check("grant of 3", g is HeartStore.GrantResult.Granted && grantSerials.all { store.loadItem(it)!!.source == HeartStore.ItemSource.ADMIN })
            check("grant doesn't change hearts", store.load(bob)!!.hearts == 1)
            check("grant audited with quantity", store.audit(bob).any { it.operation == "ADMIN_GRANT_ITEM" && it.itemQuantity == 3 && it.actor == "console" })
            check("grant of 0 refused", store.grantItems(bob, emptyList(), "console", "Console", T0) is HeartStore.GrantResult.Invalid)
            check("grant above the cap refused", store.grantItems(bob, List(HeartRules.MAX_ADMIN_ITEM_GRANT + 1) { serial() }, "console", "Console", T0) is HeartStore.GrantResult.Invalid)
            check("grant needs a record", store.grantItems(UUID.randomUUID(), listOf(serial()), "console", "Console", T0) == HeartStore.GrantResult.NoRecord)
            check("granted item redeems", store.redeem(bob, grantSerials[0], LIMITS, T0) is HeartStore.RedeemResult.Redeemed && store.load(bob)!!.hearts == 2)

            // Recovery: void (with refund for a withdrawn item, never for a staff one).
            val lost = serial().also { store.withdraw(alice, it, LIMITS, T0) } // alice 10 -> 9
            check("outstanding list shows the lost item", store.outstandingItems(alice).any { it.serial == lost })
            check("staff item can't be refunded", store.voidItem(grantSerials[1], true, LIMITS, "console", "Console", T0) is HeartStore.VoidResult.Refused)
            val v = store.voidItem(lost.uppercase(), true, LIMITS, "console", "Console", T0)
            check("void + refund", v is HeartStore.VoidResult.Voided && v.refunded && v.after == 10 && store.load(alice)!!.hearts == 10)
            check("voided item can't be redeemed", store.redeem(alice, lost, LIMITS, T0) == HeartStore.RedeemResult.Voided && store.load(alice)!!.hearts == 10)
            check("void is once", store.voidItem(lost, false, LIMITS, "console", "Console", T0) is HeartStore.VoidResult.NotOutstanding)
            check("void audited", store.audit(alice).any { it.operation == "ITEM_VOID" } && store.audit(alice).any { it.operation == "ITEM_REFUND" && it.previous == 9 && it.next == 10 })
            check("void without refund", store.voidItem(grantSerials[1], false, LIMITS, "console", "Console", T0) is HeartStore.VoidResult.Voided)
            check("unknown void", store.voidItem(serial(), false, LIMITS, "console", "Console", T0) == HeartStore.VoidResult.UnknownItem)
            store.adminChange(alice, AdminOp.SET, 20, LIMITS, "console", "Console", T0)
            val maxed = serial().also { store.withdraw(alice, it, LIMITS, T0) }
            store.adminChange(alice, AdminOp.SET, 20, LIMITS, "console", "Console", T0)
            check("refund never passes the maximum", store.voidItem(maxed, true, LIMITS, "console", "Console", T0) is HeartStore.VoidResult.Refused &&
                store.loadItem(maxed)!!.status == HeartStore.ItemStatus.ISSUED && store.load(alice)!!.hearts == 20)
            withdrawn += maxed
        }

        // Restart: hearts, item states and dedupe all persist; an outstanding item still works.
        withDb(dir) { db ->
            val store = HeartStore(db)
            store.createTables()
            check("hearts persist across restarts", store.load(alice)!!.hearts == 20 && store.load(bob)!!.hearts == 2)
            check("redeemed stays redeemed after restart", store.loadItem(withdrawn[0])!!.status == HeartStore.ItemStatus.REDEEMED &&
                store.redeem(bob, withdrawn[0], LIMITS, T0) is HeartStore.RedeemResult.AlreadyRedeemed)
            store.adminChange(bob, AdminOp.SET, 5, LIMITS, "console", "Console", T0)
            check("outstanding item redeems after restart", store.redeem(bob, withdrawn.last(), LIMITS, T0) is HeartStore.RedeemResult.Redeemed && store.load(bob)!!.hearts == 6)
        }

        // An audit table from issue #1341 (no item columns) is migrated in place.
        val legacyDir = Files.createTempDirectory("joshymc-heart-items-legacy").toFile()
        try {
            withDb(legacyDir) { db ->
                db.createTable("CREATE TABLE lifesteal_heart_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, transaction_id TEXT, uuid TEXT NOT NULL, operation TEXT NOT NULL, previous_hearts INTEGER, new_hearts INTEGER, actor TEXT, reason TEXT, created_at INTEGER NOT NULL)")
                db.execute("INSERT INTO lifesteal_heart_audit (uuid, operation, created_at) VALUES (?, 'INITIAL', ?)", alice.toString(), T0)
                val store = HeartStore(db)
                store.createTables()
                store.createTables()
                store.ensure(alice, 10, T0)
                store.withdraw(alice, serial(), LIMITS, T0)
                check("legacy audit rows kept", store.audit(alice).first().let { it.operation == "INITIAL" && it.itemQuantity == 0 && it.itemSerial == null })
                check("migrated audit records items", store.audit(alice).any { it.operation == "WITHDRAW" && it.itemQuantity == 1 })
            }
        } finally {
            legacyDir.deleteRecursively()
        }
    } finally {
        dir.deleteRecursively()
    }
}

private fun heartItemConcurrency() {
    val dir = Files.createTempDirectory("joshymc-heart-items-concurrency").toFile()
    try {
        withDb(dir) { db ->
            val store = HeartStore(db)
            store.createTables()

            // 40 withdrawals at once from a 10-heart player: exactly 9 succeed, 9 items exist.
            val p = UUID.randomUUID().also { store.ensure(it, 10, T0) }
            val pool = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            val ok = AtomicInteger()
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val serials = Collections.synchronizedList(mutableListOf<String>())
            repeat(40) {
                pool.execute {
                    start.await()
                    try {
                        val r = store.withdraw(p, serial(), LIMITS, T0)
                        if (r is HeartStore.WithdrawResult.Withdrawn) { ok.incrementAndGet(); serials += r.serial }
                    } catch (e: Throwable) { errors += e }
                }
            }
            start.countDown()
            pool.shutdown()
            check("concurrent withdrawals finished", pool.awaitTermination(60, TimeUnit.SECONDS))
            check("no errors in concurrent withdrawals", errors.isEmpty())
            check("exactly 9 concurrent withdrawals", ok.get() == 9 && store.load(p)!!.hearts == 1)
            check("exactly 9 items issued", store.itemCount(HeartStore.ItemStatus.ISSUED) == 9)

            // The same item redeemed from 32 threads (spam clicks, two hands, two players with a duplicated copy): once.
            val target = serials.first()
            val redeemers = List(4) { UUID.randomUUID().also { u -> store.ensure(u, 10, T0) } }
            val pool2 = Executors.newFixedThreadPool(8)
            val redeemed = AtomicInteger()
            repeat(32) { i ->
                pool2.execute {
                    if (store.redeem(redeemers[i % 4], target, LIMITS, T0) is HeartStore.RedeemResult.Redeemed) redeemed.incrementAndGet()
                }
            }
            pool2.shutdown()
            check("same-item redemptions finished", pool2.awaitTermination(60, TimeUnit.SECONDS))
            check("one item, one heart", redeemed.get() == 1 && redeemers.sumOf { store.load(it)!!.hearts!! } == 41)

            // Hearts are conserved: every withdrawn heart is either still an item or back on a player.
            val all = (serials - target)
            val pool3 = Executors.newFixedThreadPool(8)
            for (s in all) pool3.execute { store.redeem(p, s, LIMITS, T0) }
            pool3.shutdown()
            check("remaining redemptions finished", pool3.awaitTermination(60, TimeUnit.SECONDS))
            check("withdrawer back to 9 from their own 8 items", store.load(p)!!.hearts == 9 && store.itemCount(HeartStore.ItemStatus.ISSUED) == 0)

            // A player at 19 redeeming 10 items at once only reaches 20; the other 9 stay valid.
            val near = UUID.randomUUID().also { store.ensure(it, 19, T0) }
            val grant = List(10) { serial() }
            store.grantItems(near, grant, "console", "Console", T0)
            val pool4 = Executors.newFixedThreadPool(8)
            for (s in grant) pool4.execute { store.redeem(near, s, LIMITS, T0) }
            pool4.shutdown()
            check("max race finished", pool4.awaitTermination(60, TimeUnit.SECONDS))
            check("never past the maximum under concurrency", store.load(near)!!.hearts == 20 && store.itemCount(HeartStore.ItemStatus.ISSUED) == 9)

            // Withdrawals racing PvP losses never take the final heart.
            val mixed = UUID.randomUUID().also { store.ensure(it, 10, T0) }
            val pool5 = Executors.newFixedThreadPool(8)
            repeat(20) {
                pool5.execute { store.withdraw(mixed, serial(), LIMITS, T0) }
                pool5.execute {
                    val k = UUID.randomUUID()
                    store.ensure(k, 10, T0)
                    store.transferPvp(pvp(k, mixed, T0))
                }
            }
            pool5.shutdown()
            check("withdraw/pvp race finished", pool5.awaitTermination(60, TimeUnit.SECONDS))
            check("final heart kept under withdraw/pvp race", store.load(mixed)!!.hearts == 1)
        }
    } finally {
        dir.deleteRecursively()
    }
}

private fun heartItemRollback() {
    val dir = Files.createTempDirectory("joshymc-heart-items-rollback").toFile()
    try {
        withDb(dir) { real ->
            val db = FailingDb(real)
            val store = HeartStore(db)
            store.createTables()
            val p = UUID.randomUUID().also { store.ensure(it, 10, T0) }

            // Database failure after the heart was written: nothing is left behind.
            val s = serial()
            db.failOn = "withdrew 1 heart"
            check("withdraw failure surfaces", runCatching { store.withdraw(p, s, LIMITS, T0) }.isFailure)
            db.failOn = null
            check("failed withdrawal took no heart", store.load(p)!!.hearts == 10 && store.load(p)!!.version == 0L)
            check("failed withdrawal issued no item", store.loadItem(s) == null)
            check("same withdrawal can be retried", store.withdraw(p, s, LIMITS, T0) is HeartStore.WithdrawResult.Withdrawn)

            // Interrupted redemption (another writer changes the row between read and write): rolled back.
            db.beforeHeartUpdate = {
                real.executeUpdate("UPDATE lifesteal_hearts SET version = version + 1 WHERE uuid = ?", p.toString())
            }
            val stale = runCatching { store.redeem(p, s, LIMITS, T0) }
            check("stale redemption refused", stale.exceptionOrNull() is HeartStore.StaleWriteException)
            check("stale redemption kept the item valid", store.loadItem(s)!!.status == HeartStore.ItemStatus.ISSUED && store.load(p)!!.hearts == 9)
            check("item redeems after the failed attempt", store.redeem(p, s, LIMITS, T0) is HeartStore.RedeemResult.Redeemed && store.load(p)!!.hearts == 10)

            // Failure while writing the redemption's audit row.
            val s2 = serial().also { store.withdraw(p, it, LIMITS, T0) }
            db.failOn = s2
            check("redeem failure surfaces", runCatching { store.redeem(p, s2, LIMITS, T0) }.isFailure)
            db.failOn = null
            check("failed redemption changed nothing", store.loadItem(s2)!!.status == HeartStore.ItemStatus.ISSUED && store.load(p)!!.hearts == 9)
        }

        // Shutdown: a closed database refuses withdrawals and redemptions instead of half-applying them.
        val closed = DatabaseManager(dir, quietLogger()).also { it.start(); it.shutdown() }
        check("closed database refuses withdrawals", runCatching { HeartStore(closed).withdraw(UUID.randomUUID(), serial(), LIMITS, T0) }.isFailure)
        check("closed database refuses redemptions", runCatching { HeartStore(closed).redeem(UUID.randomUUID(), serial(), LIMITS, T0) }.isFailure)
    } finally {
        dir.deleteRecursively()
    }
}

private fun heartItemWiring() {
    val manager = source("lifesteal/hearts/LifestealHeartManager.kt")
    val withdraw = manager.substringAfter("fun withdraw(player: Player)").substringBefore("private fun deliver(")
    check("withdraw checks the session first", "itemProblem(player)" in withdraw && "!loaded.containsKey(player.uniqueId) -> HeartMessages.NOT_LOADED" in manager)
    check("withdraw checks inventory space before the transaction", withdraw.indexOf("player.inventory.firstEmpty() < 0") in 0 until withdraw.indexOf("store.withdraw("))
    check("withdraw refuses re-entry", "if (!busy.add(uuid)) return" in withdraw)
    check("item handed over only after the commit", withdraw.indexOf("store.withdraw(") < withdraw.indexOf("deliver(player"))
    check("player data saved after a heart item change", "player.saveData()" in manager)
    val redeem = manager.substringAfter("fun redeem(player: Player, hand: EquipmentSlot)").substringBefore("private fun removeRedeemed(")
    check("one redemption attempt per tick", "lastRedeemTick.put(uuid, tick) == tick" in redeem)
    check("item taken only after the commit", redeem.indexOf("store.redeem(") < redeem.indexOf("removeRedeemed(player, hand, serial)"))
    check("failed redemption keeps the item", redeem.substringAfter("catch (e: Exception)").substringBefore("when (result)").let { "removeRedeemed" !in it })
    check("heart items only for Lifesteal players", "!hosted -> HeartMessages.NOT_LIFESTEAL_SERVER" in manager && "!isLifesteal(player) -> HeartMessages.NOT_IN_LIFESTEAL" in manager)
    check("quit clears the item safeguards", manager.substringAfter("fun onQuit(player: Player)").substringBefore("}").let { "lastRedeemTick.remove" in it && "lastWithdrawAt.remove" in it })

    val item = source("lifesteal/hearts/HeartItem.kt")
    check("identified by persistent data, not name or lore", "pdc.get(serialKey" in item && "displayName()" !in item && ".lore()" !in item)
    check("renamed Nether Stars are not hearts", "if (id != ITEM_ID && !hasSerial) return Read.NotHeart" in item)
    check("stacks refused", "if (stack.amount != 1) return Read.Malformed" in item)
    check("never stacks", "meta.setMaxStackSize(1)" in item)
    check("not a /joshymc give item (no serial-less hearts)", "HeartItem" !in source("manager/ItemManager.kt") && "lifesteal_heart" !in source("manager/ItemManager.kt"))

    val listener = source("lifesteal/hearts/LifestealHeartListener.kt")
    check("off hand skipped when the main hand holds a heart", "if (hand == EquipmentSlot.OFF_HAND && hearts.items.isHeart(player.inventory.itemInMainHand)) return" in listener)
    check("heart never used as a vanilla item", "event.setUseItemInHand(Event.Result.DENY)" in listener)
    check("hearts can't be crafted with", "PrepareItemCraftEvent" in listener)

    val command = source("lifesteal/hearts/WithdrawHeartCommand.kt")
    check("withdraw is player-only", "if (sender !is Player)" in command)
    check("withdraw permission", "joshymc.lifesteal.withdraw" in command)
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("withdraw permission default true", Regex("""(?m)^  joshymc\.lifesteal\.withdraw:\n    description: .*\n    default: true$""").containsMatchIn(pluginYml))
    check("give permission op-only", Regex("""(?m)^  joshymc\.lifesteal\.hearts\.give:\n    description: .*\n    default: op$""").containsMatchIn(pluginYml))
    check("withdrawheart registered", "plugin.getCommand(\"withdrawheart\")" in source("manager/CommandManager.kt"))
    check("withdrawheart is a Lifesteal command", com.liam.joshymc.gamemode.GamemodeAssignments.COMMANDS["withdrawheart"] == com.liam.joshymc.gamemode.GamemodeScope.LIFESTEAL)
    val admin = source("lifesteal/hearts/LifestealHeartsCommand.kt")
    check("give checks its own permission", Regex("""private fun give\(sender: CommandSender, args: List<String>\) \{\s+if \(!sender\.hasPermission\(GIVE_PERMISSION\)\)""").containsMatchIn(admin))
    check("items/void need the admin permission", admin.indexOf("if (!sender.hasPermission(PERMISSION))") < admin.indexOf("\"items\" -> return items(sender, args)"))
}

// ── Settings ─────────────────────────────────────────────────

private fun settings() {
    val d = LifestealSettings.DEFAULTS
    check("default starting hearts 10", d.hearts.starting == 10)
    check("default maximum hearts 20", d.hearts.maximum == 20)
    check("default minimum hearts 1", d.hearts.minimum == 1)
    check("default anti-farm cooldown 30 minutes", d.pvp.sameVictimCooldownMinutes == 30)

    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    val map = Yaml().load<Map<String, Any?>>(text)
    fun path(p: String): Any? = p.split('.').fold<String, Any?>(map) { node, key -> (node as? Map<*, *>)?.get(key) }
    val parsed = LifestealConfigParser.parse { path(it) }
    check("bundled lifesteal.yml parses", parsed.settings != null)
    check("bundled limits", HeartRules.Limits.of(parsed.settings!!.hearts) == LIMITS)

    // Validation (reused from the bootstrap parser): an invalid file gives no settings, so hearts pause.
    fun invalid(from: String, to: String) = LifestealConfigParser.parse { p -> path(p) }.let {
        val broken = Yaml().load<Map<String, Any?>>(text.replace(from, to))
        LifestealConfigParser.parse { p -> p.split('.').fold<String, Any?>(broken) { node, key -> (node as? Map<*, *>)?.get(key) } }.settings == null
    }
    check("starting 0 refused", invalid("starting: 10", "starting: 0"))
    check("starting above maximum refused", invalid("starting: 10", "starting: 25"))
    check("minimum 0 refused", invalid("minimum: 1", "minimum: 0"))
    check("maximum below starting refused", invalid("maximum: 20", "maximum: 5"))
    check("negative cooldown refused", invalid("same-victim-cooldown-minutes: 30", "same-victim-cooldown-minutes: -1"))
    check("non-number cooldown refused", invalid("same-victim-cooldown-minutes: 30", "same-victim-cooldown-minutes: soon"))

    check("Limits rejects minimum 0", runCatching { HeartRules.Limits(10, 0, 20) }.isFailure)
    check("Limits rejects starting above maximum", runCatching { HeartRules.Limits(21, 1, 20) }.isFailure)
}

// ── Rules ────────────────────────────────────────────────────

private fun death(
    victimInLifesteal: Boolean = true,
    victimLoaded: Boolean = true,
    causing: UUID? = JAVA,
    victim: UUID = BEDROCK,
    killerOnline: Boolean = true,
    killerInLifesteal: Boolean = true,
    killerLoaded: Boolean = true,
    npc: Boolean = false,
) = HeartRules.attribute(HeartRules.Death(victim, victimInLifesteal, victimLoaded, causing, killerOnline, killerInLifesteal, killerLoaded, npc))

private fun rules() {
    check("1 heart = 2 health", HeartRules.healthFor(1) == 2.0)
    check("10 hearts = 20 health", HeartRules.healthFor(10) == 20.0)
    check("20 hearts = 40 health", HeartRules.healthFor(20) == 40.0)
    check("clamp below minimum", LIMITS.clamp(0) == 1 && LIMITS.clamp(-5) == 1)
    check("clamp above maximum", LIMITS.clamp(21) == 20 && LIMITS.clamp(500) == 20)
    check("clamp inside", LIMITS.clamp(13) == 13)

    // Attribution.
    check("pvp kill (Java killer, Bedrock victim)", death() == Attribution.PVP_KILL)
    check("pvp kill (Bedrock killer, Java victim)", death(causing = BEDROCK, victim = JAVA) == Attribution.PVP_KILL)
    check("environmental death (no causing player)", death(causing = null) == Attribution.ENVIRONMENT)
    check("self kill", death(causing = BEDROCK, victim = BEDROCK) == Attribution.SELF)
    check("NPC killer", death(npc = true) == Attribution.INVALID_KILLER)
    check("disconnected killer", death(killerOnline = false) == Attribution.INVALID_KILLER)
    check("killer outside Lifesteal", death(killerInLifesteal = false) == Attribution.INVALID_KILLER)
    check("victim outside Lifesteal", death(victimInLifesteal = false) == Attribution.NOT_LIFESTEAL)
    check("victim outside Lifesteal even without a killer", death(victimInLifesteal = false, causing = null) == Attribution.NOT_LIFESTEAL)
    check("victim not loaded", death(victimLoaded = false) == Attribution.NOT_LOADED)
    check("killer not loaded", death(killerLoaded = false) == Attribution.NOT_LOADED)

    // Decisions.
    check("normal steal", HeartRules.decide(10, 10, 0, LIMITS) == Decision.Transfer(9, 11))
    check("victim reaches 1", HeartRules.decide(2, 10, 0, LIMITS) == Decision.Transfer(1, 11))
    check("victim at 1 keeps it", HeartRules.decide(1, 10, 0, LIMITS) == Decision.VictimAtMinimum)
    check("killer reaches 20", HeartRules.decide(10, 19, 0, LIMITS) == Decision.Transfer(9, 20))
    check("killer at 20: no transfer", HeartRules.decide(10, 20, 0, LIMITS) == Decision.KillerAtMaximum)
    check("fixed policy is NO_TRANSFER", HeartRules.KILLER_AT_MAXIMUM == KillerAtMaximumPolicy.NO_TRANSFER)
    check("alternative policy (unused): victim only", HeartRules.decide(10, 20, 0, LIMITS, KillerAtMaximumPolicy.VICTIM_LOSES_ONLY) == Decision.VictimLosesOnly(9))
    check("cooldown blocks", HeartRules.decide(10, 10, 5_000, LIMITS) == Decision.Cooldown(5_000))
    check("last heart wins over cooldown", HeartRules.decide(1, 10, 5_000, LIMITS) == Decision.VictimAtMinimum)
    check("cooldown wins over killer maximum", HeartRules.decide(10, 20, 5_000, LIMITS) is Decision.Cooldown)
    check("stored above maximum counts as maximum", HeartRules.decide(10, 25, 0, LIMITS) == Decision.KillerAtMaximum)
    check("stored below minimum counts as minimum", HeartRules.decide(0, 10, 0, LIMITS) == Decision.VictimAtMinimum)
    for (v in -3..30) for (k in -3..30) {
        when (val d = HeartRules.decide(v, k, 0, LIMITS)) {
            is Decision.Transfer -> check("transfer $v/$k stays in limits", d.victimAfter >= 1 && d.killerAfter <= 20 && d.victimAfter + d.killerAfter == LIMITS.clamp(v) + LIMITS.clamp(k))
            else -> {}
        }
    }

    // Cooldown arithmetic.
    check("no previous transfer", HeartRules.cooldownRemaining(null, T0, COOLDOWN) == 0L)
    check("just transferred", HeartRules.cooldownRemaining(T0, T0 + 1, COOLDOWN) == COOLDOWN - 1)
    check("10 minutes later", HeartRules.cooldownRemaining(T0, T0 + 10 * 60_000, COOLDOWN) == 20 * 60_000L)
    check("exactly expired", HeartRules.cooldownRemaining(T0, T0 + COOLDOWN, COOLDOWN) == 0L)
    check("long expired", HeartRules.cooldownRemaining(T0, T0 + 10 * COOLDOWN, COOLDOWN) == 0L)
    check("clock went back: full cooldown, never more", HeartRules.cooldownRemaining(T0 + 999_999_999, T0, COOLDOWN) == COOLDOWN)
    check("negative stored time is corrupt: no cooldown", HeartRules.cooldownRemaining(-5, T0, COOLDOWN) == 0L)
    check("cooldown 0 disables it", HeartRules.cooldownRemaining(T0, T0, 0) == 0L)
    check("cooldown minutes", HeartRules.cooldownMillis(30) == COOLDOWN && HeartRules.cooldownMillis(-1) == 0L)
    check("duration formatting", HeartRules.formatDuration(COOLDOWN) == "30m" &&
        HeartRules.formatDuration(90_500) == "1m 31s" && HeartRules.formatDuration(1) == "1s" &&
        HeartRules.formatDuration(2 * 3_600_000L) == "2h 0m")

    // Admin.
    check("set inside limits", HeartRules.adminChange(AdminOp.SET, 10, 15, LIMITS) == AdminDecision.Change(15, false))
    check("set 1 allowed", HeartRules.adminChange(AdminOp.SET, 10, 1, LIMITS) == AdminDecision.Change(1, false))
    check("set 0 refused", HeartRules.adminChange(AdminOp.SET, 10, 0, LIMITS) is AdminDecision.Invalid)
    check("set 21 refused", HeartRules.adminChange(AdminOp.SET, 10, 21, LIMITS) is AdminDecision.Invalid)
    check("set repairs an unreadable record", HeartRules.adminChange(AdminOp.SET, null, 10, LIMITS) == AdminDecision.Change(10, false))
    check("add", HeartRules.adminChange(AdminOp.ADD, 10, 3, LIMITS) == AdminDecision.Change(13, false))
    check("add stops at maximum", HeartRules.adminChange(AdminOp.ADD, 18, 5, LIMITS) == AdminDecision.Change(20, true))
    check("remove", HeartRules.adminChange(AdminOp.REMOVE, 10, 3, LIMITS) == AdminDecision.Change(7, false))
    check("remove never takes the last heart", HeartRules.adminChange(AdminOp.REMOVE, 3, 10, LIMITS) == AdminDecision.Change(1, true))
    check("add 0 refused", HeartRules.adminChange(AdminOp.ADD, 10, 0, LIMITS) is AdminDecision.Invalid)
    check("huge amount refused", HeartRules.adminChange(AdminOp.ADD, 10, HeartRules.MAX_ADMIN_AMOUNT + 1, LIMITS) is AdminDecision.Invalid)
    check("add on unreadable refused", HeartRules.adminChange(AdminOp.ADD, null, 1, LIMITS) is AdminDecision.Invalid)
    check("parse amount", HeartRules.parseAmount("5") == 5 && HeartRules.parseAmount(" 12 ") == 12)
    check("parse rejects junk", listOf("-1", "1.5", "abc", "", "99999999", "1e3").all { HeartRules.parseAmount(it) == null } && HeartRules.parseAmount(null) == null)
    check("admin ops", AdminOp.fromId("SET") == AdminOp.SET && AdminOp.fromId("remove") == AdminOp.REMOVE && AdminOp.fromId("get") == null)
}

// ── Store ────────────────────────────────────────────────────

private fun store() {
    val dir = Files.createTempDirectory("joshymc-hearts-check").toFile()
    try {
        val killer = JAVA
        val victim = BEDROCK
        val third = UUID.randomUUID()

        withDb(dir) { db ->
            val store = HeartStore(db)
            store.createTables()
            store.createTables() // idempotent

            check("no record before joining", store.load(killer) == null)
            val first = store.ensure(killer, 10, T0)
            check("new player starts with 10", first.hearts == 10 && first.initialGranted && first.version == 0L)
            val again = store.ensure(killer, 15, T0 + 1)
            check("second ensure keeps the record (no duplicate grant)", again.hearts == 10 && again.version == 0L)
            check("one INITIAL audit row", store.audit(killer).count { it.operation == "INITIAL" } == 1)
            store.ensure(victim, 10, T0)

            val r1 = store.transferPvp(pvp(killer, victim, T0, key = "death-1"))
            check("normal steal done", r1 is PvpResult.Done && r1.decision == Decision.Transfer(9, 11))
            check("victim 9", store.load(victim)!!.hearts == 9)
            check("killer 11", store.load(killer)!!.hearts == 11)
            check("versions raised", store.load(victim)!!.version == 1L && store.load(killer)!!.version == 1L)
            val txn = (r1 as PvpResult.Done).transactionId
            check("records point at the transaction", store.load(victim)!!.lastTransaction == txn && store.load(killer)!!.lastTransaction == txn)
            check("victim audit row", store.audit(victim).any { it.operation == "PVP_LOSS" && it.previous == 10 && it.next == 9 && it.transactionId == txn && it.actor == killer.toString() })
            check("killer audit row", store.audit(killer).any { it.operation == "PVP_GAIN" && it.previous == 10 && it.next == 11 && it.transactionId == txn })
            check("cooldown recorded", store.lastTransferAt(killer, victim) == T0)
            check("cooldown is per direction", store.lastTransferAt(victim, killer) == null)

            val dup = store.transferPvp(pvp(killer, victim, T0, key = "death-1"))
            check("repeated death event is a no-op", dup == PvpResult.Duplicate && store.load(victim)!!.hearts == 9 && store.load(killer)!!.hearts == 11)

            val cd = store.transferPvp(pvp(killer, victim, T0 + 60_000))
            check("same victim within 30 minutes: cooldown", cd is PvpResult.Done && cd.decision is Decision.Cooldown)
            check("cooldown changes nothing", store.load(victim)!!.hearts == 9 && store.load(killer)!!.hearts == 11)
            check("cooldown doesn't restart the timer", store.lastTransferAt(killer, victim) == T0)
            check("cooldown rejection is audited", store.audit(victim).any { it.operation == "PVP_REJECTED" && it.reason == "COOLDOWN" })

            val reverse = store.transferPvp(pvp(victim, killer, T0 + 60_000))
            check("revenge kill isn't on the other pair's cooldown", reverse is PvpResult.Done && reverse.decision == Decision.Transfer(10, 10))
            check("back to 10/10", store.load(victim)!!.hearts == 10 && store.load(killer)!!.hearts == 10)

            check("missing killer record: unavailable", store.transferPvp(pvp(third, victim, T0)) is PvpResult.Unavailable)
            check("missing victim record: unavailable", store.transferPvp(pvp(killer, third, T0)) is PvpResult.Unavailable)
            check("unavailable changes nothing", store.load(victim)!!.hearts == 10 && store.load(third) == null)
            check("self transfer refused", runCatching { store.transferPvp(pvp(killer, killer, T0)) }.isFailure)
        }

        // Restart: everything persisted, including the cooldown.
        withDb(dir) { db ->
            val store = HeartStore(db)
            store.createTables()
            check("hearts survive a restart", store.load(killer)!!.hearts == 10 && store.load(victim)!!.hearts == 10)
            check("cooldown survives a restart", store.lastTransferAt(killer, victim) == T0)
            val cd = store.transferPvp(pvp(killer, victim, T0 + 20 * 60_000))
            check("cooldown still enforced after restart (and logout)", cd is PvpResult.Done && cd.decision == Decision.Cooldown(10 * 60_000L))
            val ok = store.transferPvp(pvp(killer, victim, T0 + COOLDOWN))
            check("after 30 minutes the pair can transfer again", ok is PvpResult.Done && ok.decision == Decision.Transfer(9, 11))
            check("rejoin keeps saved hearts", store.ensure(killer, 10, T0 + COOLDOWN).hearts == 11)
            check("dedupe survives a restart", store.transferPvp(pvp(killer, victim, T0, key = "death-1")) == PvpResult.Duplicate)

            // Victim down to 1, then protected.
            val v = UUID.randomUUID()
            store.ensure(v, 2, T0)
            val k1 = UUID.randomUUID().also { store.ensure(it, 10, T0) }
            val k2 = UUID.randomUUID().also { store.ensure(it, 10, T0) }
            val down = store.transferPvp(pvp(k1, v, T0))
            check("victim reaches 1 heart", down is PvpResult.Done && store.load(v)!!.hearts == 1 && store.load(k1)!!.hearts == 11)
            val last = store.transferPvp(pvp(k2, v, T0))
            check("victim at 1: protected", last is PvpResult.Done && last.decision == Decision.VictimAtMinimum)
            check("victim stays at 1, killer gains nothing", store.load(v)!!.hearts == 1 && store.load(k2)!!.hearts == 10)
            check("no cooldown for a protected kill", store.lastTransferAt(k2, v) == null)

            // Killer at maximum.
            val maxed = UUID.randomUUID().also { store.ensure(it, 20, T0) }
            val target = UUID.randomUUID().also { store.ensure(it, 10, T0) }
            val atMax = store.transferPvp(pvp(maxed, target, T0))
            check("killer at 20: no transfer", atMax is PvpResult.Done && atMax.decision == Decision.KillerAtMaximum)
            check("killer at 20 stays 20, victim keeps hearts", store.load(maxed)!!.hearts == 20 && store.load(target)!!.hearts == 10)
            check("no cooldown when killer at max", store.lastTransferAt(maxed, target) == null)

            // A lowered maximum: stored 25 counts as 20 and the stored value is only replaced by a change.
            val over = UUID.randomUUID().also { store.ensure(it, 25, T0) }
            check("out-of-range row isn't rewritten by ensure", store.ensure(over, 10, T0).hearts == 25)
            val overKill = store.transferPvp(pvp(over, target, T0))
            check("out-of-range killer counts as maximum", overKill is PvpResult.Done && overKill.decision == Decision.KillerAtMaximum && store.load(over)!!.hearts == 25)

            // Malformed record: never reset, never used, repaired by set.
            val broken = UUID.randomUUID().also { store.ensure(it, 10, T0) }
            db.execute("UPDATE lifesteal_hearts SET hearts = 'lots' WHERE uuid = ?", broken.toString())
            check("malformed hearts read as null", store.load(broken)!!.hearts == null)
            check("ensure doesn't reset a malformed record", store.ensure(broken, 10, T0).hearts == null)
            check("malformed victim: unavailable", store.transferPvp(pvp(k2, broken, T0)) is PvpResult.Unavailable)
            check("malformed killer: unavailable", store.transferPvp(pvp(broken, target, T0)) is PvpResult.Unavailable)
            check("add on malformed refused", store.adminChange(broken, AdminOp.ADD, 1, LIMITS, "console", "Console", T0) is AdminResult.Invalid)
            val repaired = store.adminChange(broken, AdminOp.SET, 12, LIMITS, "console", "Console", T0)
            check("set repairs it", repaired is AdminResult.Changed && repaired.before == null && store.load(broken)!!.hearts == 12)

            // Admin.
            val p = UUID.randomUUID().also { store.ensure(it, 10, T0) }
            val staff = UUID.randomUUID().toString()
            val set = store.adminChange(p, AdminOp.SET, 15, LIMITS, staff, "Staffer", T0 + 5)
            check("admin set", set is AdminResult.Changed && set.before == 10 && set.after == 15 && !set.clamped && store.load(p)!!.hearts == 15)
            check("admin set audited", store.audit(p).any { it.operation == "ADMIN_SET" && it.previous == 10 && it.next == 15 && it.actor == staff && it.at == T0 + 5 })
            check("admin add clamps", store.adminChange(p, AdminOp.ADD, 10, LIMITS, staff, "Staffer", T0).let { it is AdminResult.Changed && it.after == 20 && it.clamped })
            check("admin add at max: unchanged", store.adminChange(p, AdminOp.ADD, 1, LIMITS, staff, "Staffer", T0) == AdminResult.Unchanged(20))
            check("admin remove clamps at 1", store.adminChange(p, AdminOp.REMOVE, 50, LIMITS, staff, "Staffer", T0).let { it is AdminResult.Changed && it.after == 1 })
            check("admin set out of range refused", store.adminChange(p, AdminOp.SET, 0, LIMITS, staff, "Staffer", T0) is AdminResult.Invalid && store.load(p)!!.hearts == 1)
            check("admin on a player with no record", store.adminChange(UUID.randomUUID(), AdminOp.SET, 5, LIMITS, staff, "Staffer", T0) == AdminResult.NoRecord)
            check("admin changes are transactions", db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_heart_transactions WHERE type = 'ADMIN' AND victim = ?", p.toString()) { it.getInt("n") } == 3)
        }

        // Survival: a separate database never gets heart tables (the manager never runs there).
        val survivalDir = Files.createTempDirectory("joshymc-hearts-survival").toFile()
        try {
            withDb(survivalDir) { db ->
                val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'lifesteal_%'") { it.getString("name") }
                check("survival database has no heart tables", tables.isEmpty())
            }
        } finally {
            survivalDir.deleteRecursively()
        }
    } finally {
        dir.deleteRecursively()
    }
}

// ── Concurrency ──────────────────────────────────────────────

private fun concurrency() {
    val dir = Files.createTempDirectory("joshymc-hearts-concurrency").toFile()
    try {
        withDb(dir) { db ->
            val store = HeartStore(db)
            store.createTables()
            val wide = HeartRules.Limits(starting = 10, minimum = 1, maximum = 100)

            // 40 different killers kill one 10-heart victim at the same moment: exactly 9 hearts move.
            val victim = UUID.randomUUID().also { store.ensure(it, 10, T0) }
            val killers = List(40) { UUID.randomUUID().also { k -> store.ensure(k, 10, T0) } }
            val pool = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            val transfers = AtomicInteger()
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            for (k in killers) pool.execute {
                start.await()
                try {
                    val r = store.transferPvp(pvp(k, victim, T0, limits = wide))
                    if (r is PvpResult.Done && r.decision is Decision.Transfer) transfers.incrementAndGet()
                } catch (e: Throwable) {
                    errors += e
                }
            }
            start.countDown()
            pool.shutdown()
            check("concurrent kills finished", pool.awaitTermination(60, TimeUnit.SECONDS))
            check("no concurrent errors", errors.isEmpty())
            check("exactly 9 transfers", transfers.get() == 9)
            check("victim ends at 1", store.load(victim)!!.hearts == 1)
            val total = killers.sumOf { store.load(it)!!.hearts!! } + store.load(victim)!!.hearts!!
            check("hearts conserved under concurrency", total == 41 * 10)
            check("victim version = transfers", store.load(victim)!!.version == 9L)

            // The same death delivered from many threads at once is processed once.
            val a = UUID.randomUUID().also { store.ensure(it, 10, T0) }
            val b = UUID.randomUUID().also { store.ensure(it, 10, T0) }
            val pool2 = Executors.newFixedThreadPool(8)
            val dupes = AtomicInteger()
            repeat(32) {
                pool2.execute {
                    if (store.transferPvp(pvp(a, b, T0, key = "same-death")) == PvpResult.Duplicate) dupes.incrementAndGet()
                }
            }
            pool2.shutdown()
            check("duplicate deliveries finished", pool2.awaitTermination(60, TimeUnit.SECONDS))
            check("one death, one transfer", dupes.get() == 31 && store.load(a)!!.hearts == 11 && store.load(b)!!.hearts == 9)

            // Rapid reconnects: concurrent ensure calls grant the starting hearts once.
            val joiner = UUID.randomUUID()
            val pool3 = Executors.newFixedThreadPool(8)
            repeat(32) { pool3.execute { store.ensure(joiner, 10, T0) } }
            pool3.shutdown()
            check("reconnect storm finished", pool3.awaitTermination(60, TimeUnit.SECONDS))
            check("one INITIAL grant under reconnect storm", store.audit(joiner).count { it.operation == "INITIAL" } == 1 && store.load(joiner)!!.hearts == 10)

            // Admin changes racing PvP never lose an update.
            val target = UUID.randomUUID().also { store.ensure(it, 50, T0) }
            val pool4 = Executors.newFixedThreadPool(8)
            val pvpDone = AtomicInteger()
            repeat(20) {
                pool4.execute { store.adminChange(target, AdminOp.ADD, 1, wide, "console", "Console", T0) }
                pool4.execute {
                    val k = UUID.randomUUID()
                    store.ensure(k, 10, T0)
                    val r = store.transferPvp(pvp(k, target, T0, limits = wide))
                    if (r is PvpResult.Done && r.decision is Decision.Transfer) pvpDone.incrementAndGet()
                }
            }
            pool4.shutdown()
            check("admin/pvp race finished", pool4.awaitTermination(60, TimeUnit.SECONDS))
            check("no lost updates (50 + 20 - 20)", pvpDone.get() == 20 && store.load(target)!!.hearts == 50)
        }
    } finally {
        dir.deleteRecursively()
    }
}

// ── Rollback ─────────────────────────────────────────────────

/** Delegates to the real database, failing on demand to prove transactions roll back. */
private class FailingDb(private val real: DatabaseManager) : SqliteDatabase by real {
    var failOn: String? = null
    /** Runs once, right before the first heart UPDATE: simulates another writer getting in first. */
    var beforeHeartUpdate: (() -> Unit)? = null

    override fun execute(sql: String, vararg params: Any?) {
        val fail = failOn
        if (fail != null && params.any { it == fail }) throw java.sql.SQLException("injected failure on $fail")
        real.execute(sql, *params)
    }

    override fun executeUpdate(sql: String, vararg params: Any?): Int {
        if (sql.startsWith("UPDATE lifesteal_hearts")) beforeHeartUpdate?.let { beforeHeartUpdate = null; it() }
        return real.executeUpdate(sql, *params)
    }
}

private fun rollback() {
    val dir = Files.createTempDirectory("joshymc-hearts-rollback").toFile()
    try {
        withDb(dir) { real ->
            val db = FailingDb(real)
            val store = HeartStore(db)
            store.createTables()
            val k = UUID.randomUUID().also { store.ensure(it, 10, T0) }
            val v = UUID.randomUUID().also { store.ensure(it, 10, T0) }

            // Fails after the victim's heart was already written in the transaction.
            db.failOn = "PVP_GAIN"
            val failed = runCatching { store.transferPvp(pvp(k, v, T0, key = "fail-1")) }
            check("database failure surfaces", failed.isFailure)
            db.failOn = null
            check("victim heart not subtracted", store.load(v)!!.hearts == 10 && store.load(v)!!.version == 0L)
            check("killer heart not awarded", store.load(k)!!.hearts == 10)
            check("no cooldown left behind", store.lastTransferAt(k, v) == null)
            check("no transaction row left behind", store.transactionCount() == 0)
            check("no audit rows left behind", store.audit(v).none { it.operation.startsWith("PVP") })
            val retry = store.transferPvp(pvp(k, v, T0, key = "fail-1"))
            check("the same death can be retried after a rollback", retry is PvpResult.Done && retry.decision is Decision.Transfer)

            // Stale write: the victim's row changes between the read and the conditional update.
            val k2 = UUID.randomUUID().also { store.ensure(it, 10, T0) }
            db.beforeHeartUpdate = {
                real.executeUpdate("UPDATE lifesteal_hearts SET hearts = 5, version = version + 1 WHERE uuid = ?", v.toString())
            }
            val stale = runCatching { store.transferPvp(pvp(k2, v, T0)) }
            check("stale write refused", stale.exceptionOrNull() is HeartStore.StaleWriteException)
            check("stale write rolled back entirely (even the competing write in the same transaction)", store.load(v)!!.hearts == 9 && store.load(k2)!!.hearts == 10)

            // Admin failure rolls back too.
            db.failOn = "ADMIN_SET"
            check("admin failure surfaces", runCatching { store.adminChange(k, AdminOp.SET, 3, LIMITS, "console", "Console", T0) }.isFailure)
            db.failOn = null
            check("admin failure changed nothing", store.load(k)!!.hearts == 11)
        }

        // A closed database (shutdown) refuses changes instead of half-applying them.
        val closed = DatabaseManager(dir, quietLogger()).also { it.start(); it.shutdown() }
        check("closed database refuses heart changes", runCatching { HeartStore(closed).ensure(UUID.randomUUID(), 10, T0) }.isFailure)
    } finally {
        dir.deleteRecursively()
    }
}

// ── Wiring (source) ──────────────────────────────────────────

private fun wiring() {
    val manager = source("lifesteal/hearts/LifestealHeartManager.kt")
    check("hosted = this server runs Lifesteal", "ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes" in manager)
    check("per-world check uses the gamemode framework", "plugin.gamemodeLockManager.gamemodeOf(player.world) == ServerGamemode.LIFESTEAL" in manager)
    check("refresh creates no tables unless hosted", Regex("""fun refresh\(\) \{\s+if \(!hosted\) \{[\s\S]*?return\s+\}""").containsMatchIn(manager) &&
        manager.indexOf("if (!hosted)") < manager.indexOf("store.createTables()"))
    check("invalid lifesteal.yml pauses before tables", manager.indexOf("if (settings == null)") < manager.indexOf("store.createTables()"))
    check("uses the max health attribute", "Attribute.MAX_HEALTH" in manager && "attribute.baseValue = base" in manager)
    check("vanilla max health only restored where it was changed", "if (!pdc.has(appliedKey, PersistentDataType.INTEGER)) return" in manager)
    check("killer from the damage source", "event.damageSource.causingEntity as? Player" in manager)
    check("dedupe key per boot, victim and tick", "pvp:\$bootId:\${victim.uniqueId}:\${Bukkit.getCurrentTick()}" in manager)
    check("no packet code", listOf("protocollib", "packetevents", "ProtocolManager").none { it in manager.lowercase() })

    val listeners = source("manager/ListenerManager.kt")
    check("listener only on Lifesteal servers", Regex("""if \(plugin\.lifestealHeartManager\.hosted\) \{\s+pm\.registerEvents\(com\.liam\.joshymc\.lifesteal\.hearts\.LifestealHeartListener""").containsMatchIn(listeners))
    val listener = source("lifesteal/hearts/LifestealHeartListener.kt")
    check("death at MONITOR, ignoring cancelled deaths", "@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)\n    fun onDeath(event: PlayerDeathEvent)" in listener)
    check("reapplied after respawn", "PlayerPostRespawnEvent" in listener)
    check("reapplied on join", "fun onJoin(event: PlayerJoinEvent) = hearts.sync(event.player)" in listener)
    check("reapplied on world change", "PlayerChangedWorldEvent" in listener)

    val main = source("Joshymc.kt")
    check("manager started", "lifestealHeartManager.refresh()" in main)
    check("refreshed on reload after the locks", "safe(\"gamemodeLockManager.start\") { gamemodeLockManager.start() }\n        // After the locks" in main &&
        "safe(\"lifestealHeartManager.refresh\") { lifestealHeartManager.refresh() }" in main)
    check("shutdown step", "shutdown.step(\"lifestealHeartManager.stop\", { ::lifestealHeartManager.isInitialized })" in main)

    val overload = source("listener/enchant/PassiveEnchantListener.kt")
    check("Overload leaves Lifesteal max health alone", "if (plugin.lifestealHeartManager.managesHealthIn(player.world))" in overload)

    val transfer = source("command/ServerTransferCommand.kt")
    check("/lifesteal hearts routed to the heart command", "args.firstOrNull()?.equals(\"hearts\", ignoreCase = true) == true" in transfer)
    check("plain /lifesteal still transfers", "plugin.transferManager.request(sender, destination)" in transfer)
    val command = source("lifesteal/hearts/LifestealHeartsCommand.kt")
    // Only `give` (issue #1344, its own permission) is routed before the admin permission check.
    check("admin command checks its permission first", Regex(
        """fun execute\(sender: CommandSender, args: List<String>\) \{\s+// .*\n\s+if \(args\.firstOrNull\(\)\?\.equals\("give", ignoreCase = true\) == true\) \{\s+give\(sender, args\)\s+return\s+\}\s+if \(!sender\.hasPermission\(PERMISSION\)\)"""
    ).containsMatchIn(command))
    check("admin command refuses off Lifesteal", "if (!hearts.hosted)" in command)
    check("offline players resolved without Mojang lookups", "plugin.playerResolver.resolve(" in command && "getOfflinePlayer(" !in command)
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("admin permission is op-only", Regex("""(?m)^  joshymc\.lifesteal\.hearts\.admin:\n    description: .*\n    default: op$""").containsMatchIn(pluginYml))

    val messages = source("lifesteal/hearts/HeartMessages.kt")
    check("killer message", "You stole 1 heart from &f{victim}&a!" in messages)
    check("victim message", "You lost 1 heart to &f{killer}&c!" in messages)

    val all = File("src/main/kotlin/com/liam/joshymc/lifesteal/hearts").listFiles()!!.joinToString("\n") { it.readText() }
    // Heart items exist since issue #1344, but still no crafting recipe, shop or elimination.
    check("no elimination, revival or recipes", listOf("ban(", "ShapedRecipe", "ShapelessRecipe", "revive").none { it in all })
}

// Issue #1369: a Heart item is never used up as a plain Nether Star.
private fun heartItemConsumers() {
    val listener = source("lifesteal/hearts/LifestealHeartListener.kt")
    check("crafting grids refuse Heart items", "fun onCraft(event: PrepareItemCraftEvent)" in listener)
    check("Crafter blocks refuse Heart items (no PrepareItemCraftEvent there)", "fun onCrafter(event: CrafterCraftEvent)" in listener && "hearts.items.isHeart(it) }) event.isCancelled = true" in listener)
    check("beacon payment slot refuses Heart items (click/shift/hotbar/offhand)", "fun onBeaconClick(event: InventoryClickEvent)" in listener && "InventoryType.BEACON" in listener && "ClickType.NUMBER_KEY" in listener && "ClickType.SWAP_OFFHAND" in listener)
    check("beacon payment slot refuses dragged Heart items", "fun onBeaconDrag(event: InventoryDragEvent)" in listener)

    // /lifesteal info: players can see their own hearts (from the cache, never a DB read on the command).
    val info = source("lifesteal/LifestealInfoCommand.kt")
    check("/lifesteal info shows the player's own hearts from the cache", "heartService.heartsOf(player.uniqueId)" in info && "storedRecord" !in info)
    check("/lifesteal info changes nothing", listOf("adminChange", "withdraw(", "redeem(", "grantItems").none { it in info })
    val transfer = source("command/ServerTransferCommand.kt")
    check("/lifesteal info|help is only taken where Lifesteal is hosted", "(first == \"info\" || first == \"help\") && args.size == 1 && info != null && plugin.lifestealHeartManager.hosted" in transfer)
}
