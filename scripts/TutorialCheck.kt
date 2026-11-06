// Checks the guided tutorial walkthrough (issue #1387): the Bukkit-free step/objective/reward rules,
// the progress + reward store against a real SQLite database (in a fresh temp directory, deleted
// afterwards), and the wiring of every success callback. The Gradle build has no test source set,
// so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/tutorial-check.init.gradle.kts tutorialCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.tutorial.TutorialRules
import com.liam.joshymc.tutorial.TutorialRules.JoinAction
import com.liam.joshymc.tutorial.TutorialSignal
import com.liam.joshymc.tutorial.TutorialState
import com.liam.joshymc.tutorial.TutorialStep
import com.liam.joshymc.tutorial.TutorialStore
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
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

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }

private val S = ServerGamemode.SURVIVAL
private val L = ServerGamemode.LIFESTEAL

fun main() {
    rules()
    store()
    wiring()
    println("Tutorial checks passed ($checks checks).")
}

private fun rules() {
    // ── Steps ──────────────────────────────────────────────
    check("survival has 7 steps", TutorialRules.stepsFor(S).size == 7)
    check("lifesteal has 7 steps", TutorialRules.stepsFor(L).size == 7)
    check(
        "survival order",
        TutorialRules.stepsFor(S) == listOf(
            TutorialStep.WALK, TutorialStep.STARTER_KIT, TutorialStep.RTP, TutorialStep.CLAIM,
            TutorialStep.EARN, TutorialStep.QUESTS, TutorialStep.DISCORD,
        )
    )
    check(
        "lifesteal order",
        TutorialRules.stepsFor(L) == listOf(
            TutorialStep.WALK, TutorialStep.STARTER_KIT, TutorialStep.RTP, TutorialStep.HEARTS,
            TutorialStep.TEAMS, TutorialStep.COMBAT, TutorialStep.DISCORD,
        )
    )
    check("step out of range", TutorialRules.stepAt(S, 7) == null && TutorialRules.stepAt(S, -1) == null)

    // ── First join / resume ────────────────────────────────
    val installed = 1_000_000L
    check("new player starts", TutorialRules.onEnter(true, null, installed + 5, installed) == JoinAction.START)
    check("new player at install time starts", TutorialRules.onEnter(true, null, installed, installed) == JoinAction.START)
    check("established player isn't interrupted", TutorialRules.onEnter(true, null, installed - 1, installed) == JoinAction.NONE)
    check("returning player resumes", TutorialRules.onEnter(true, TutorialState.ACTIVE, installed - 1, installed) == JoinAction.RESUME)
    check("completed never auto-restarts", TutorialRules.onEnter(true, TutorialState.COMPLETED, installed + 5, installed) == JoinAction.NONE)
    check("skipped never auto-restarts", TutorialRules.onEnter(true, TutorialState.SKIPPED, installed + 5, installed) == JoinAction.NONE)
    check("disabled gamemode never starts", TutorialRules.onEnter(false, null, installed + 5, installed) == JoinAction.NONE)
    check("disabled gamemode never resumes", TutorialRules.onEnter(false, TutorialState.ACTIVE, installed + 5, installed) == JoinAction.NONE)

    // ── Objectives ─────────────────────────────────────────
    fun applies(step: TutorialStep, signal: TutorialSignal, progress: Double = 0.0) = TutorialRules.apply(step, progress, signal, 100.0)
    check("starter kit claim counts", applies(TutorialStep.STARTER_KIT, TutorialSignal.StarterKitClaimed).completed)
    check("kit claim doesn't count on other steps", !applies(TutorialStep.RTP, TutorialSignal.StarterKitClaimed).changed)
    check("rtp counts", applies(TutorialStep.RTP, TutorialSignal.RandomTeleported).completed)
    check("rtp doesn't count for the claim step", !applies(TutorialStep.CLAIM, TutorialSignal.RandomTeleported).changed)
    check("claim counts", applies(TutorialStep.CLAIM, TutorialSignal.ClaimCreated).completed)
    check("hearts info counts", applies(TutorialStep.HEARTS, TutorialSignal.HeartsInfoOpened).completed)
    check("teams menu counts", applies(TutorialStep.TEAMS, TutorialSignal.TeamsMenuOpened).completed)
    check("combat info counts", applies(TutorialStep.COMBAT, TutorialSignal.CombatInfoOpened).completed)
    check("discord counts", applies(TutorialStep.DISCORD, TutorialSignal.DiscordInfoShown).completed)
    check("discord doesn't count on the hearts step", !applies(TutorialStep.HEARTS, TutorialSignal.DiscordInfoShown).changed)
    val allSignals = listOf(
        TutorialSignal.StarterKitClaimed, TutorialSignal.RandomTeleported, TutorialSignal.ClaimCreated, TutorialSignal.Earned(500.0),
        TutorialSignal.DailyQuestsOpened, TutorialSignal.WeeklyQuestsOpened, TutorialSignal.HeartsInfoOpened,
        TutorialSignal.TeamsMenuOpened, TutorialSignal.CombatInfoOpened, TutorialSignal.DiscordInfoShown,
    )
    check("no signal completes walking (teleports and commands never walk)", allSignals.none { applies(TutorialStep.WALK, it).changed })
    for (step in TutorialStep.entries.filter { it != TutorialStep.WALK }) {
        check("${step.id}: exactly one kind of signal counts", allSignals.count { applies(step, it).changed } == if (step == TutorialStep.QUESTS) 2 else 1)
    }

    // Earnings: only positive sale money after the step began; the balance is never read.
    var earned = applies(TutorialStep.EARN, TutorialSignal.Earned(65.0))
    check("earnings accumulate", earned.changed && !earned.completed && earned.progress == 65.0)
    earned = TutorialRules.apply(TutorialStep.EARN, earned.progress, TutorialSignal.Earned(35.0), 100.0)
    check("earnings reach the target", earned.completed && earned.progress == 100.0)
    check("negative earnings ignored", !applies(TutorialStep.EARN, TutorialSignal.Earned(-50.0)).changed)
    check("NaN earnings ignored", !applies(TutorialStep.EARN, TutorialSignal.Earned(Double.NaN)).changed)
    check("infinite earnings ignored", !applies(TutorialStep.EARN, TutorialSignal.Earned(Double.POSITIVE_INFINITY)).changed)
    check("zero earnings ignored", !applies(TutorialStep.EARN, TutorialSignal.Earned(0.0)).changed)
    check("earnings don't count on other steps", !applies(TutorialStep.QUESTS, TutorialSignal.Earned(500.0)).changed)
    check("earnings shown never read as done early", TutorialRules.shownEarnings(99.999, 100.0) < 100.0)
    check("earnings shown at target", TutorialRules.shownEarnings(150.0, 100.0) == 100.0)

    // Quests: both pages, any order.
    val daily = applies(TutorialStep.QUESTS, TutorialSignal.DailyQuestsOpened)
    check("daily alone isn't enough", daily.changed && !daily.completed)
    check("daily twice changes nothing", !TutorialRules.apply(TutorialStep.QUESTS, daily.progress, TutorialSignal.DailyQuestsOpened, 100.0).changed)
    check("daily then weekly completes", TutorialRules.apply(TutorialStep.QUESTS, daily.progress, TutorialSignal.WeeklyQuestsOpened, 100.0).completed)
    val weekly = applies(TutorialStep.QUESTS, TutorialSignal.WeeklyQuestsOpened)
    check("weekly then daily completes", !weekly.completed && TutorialRules.apply(TutorialStep.QUESTS, weekly.progress, TutorialSignal.DailyQuestsOpened, 100.0).completed)

    // Walking.
    check("walked 10 blocks", TutorialRules.walked("world", 0.0, 0.0, "world", 6.0, 8.0, 10.0))
    check("9 blocks isn't enough", !TutorialRules.walked("world", 0.0, 0.0, "world", 9.0, 0.0, 10.0))
    check("other world never counts", !TutorialRules.walked("world", 0.0, 0.0, "world_nether", 100.0, 0.0, 10.0))
    check("vertical movement doesn't count", !TutorialRules.walked("world", 0.0, 0.0, "world", 0.0, 0.0, 10.0))

    // ── Rewards ────────────────────────────────────────────
    check("survival server pays survival", TutorialRules.economyBelongsTo(S, setOf(S)))
    check("survival server never pays lifesteal", !TutorialRules.economyBelongsTo(L, setOf(S)))
    check("lifesteal server pays lifesteal", TutorialRules.economyBelongsTo(L, setOf(L)))
    check("lifesteal server never pays survival", !TutorialRules.economyBelongsTo(S, setOf(L)))
    check("shared economy never pays lifesteal", !TutorialRules.economyBelongsTo(L, setOf(S, L)))
    check("reward default for invalid", TutorialRules.rewardAmount(-1.0, 50_000.0) == 50_000.0 && TutorialRules.rewardAmount(Double.NaN, 25_000.0) == 25_000.0)
    check("reward keeps a valid value", TutorialRules.rewardAmount(1234.567, 1.0) == 1234.57)

    // ── Presentation ───────────────────────────────────────
    check("money whole", TutorialRules.money(50_000.0) == "$50,000")
    check("money cents", TutorialRules.money(65.5) == "$65.50")
    check("fill", TutorialRules.fill("Earn Money: {earned} / {earn}", mapOf("earned" to "$65", "earn" to "$100")) == "Earn Money: $65 / $100")
}

private fun store() {
    val dir = Files.createTempDirectory("joshymc-tutorial-check").toFile()
    try {
        val db = DatabaseManager(dir, quietLogger())
        db.start()
        try {
            val store = TutorialStore(db)
            store.createTables()
            store.createTables() // idempotent
            db.createTable("CREATE TABLE IF NOT EXISTS economy (uuid TEXT PRIMARY KEY, balance REAL NOT NULL DEFAULT 0.0)")
            fun deposit(uuid: UUID, amount: Double): Boolean {
                db.execute(
                    "INSERT INTO economy (uuid, balance) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET balance = balance + excluded.balance",
                    uuid.toString(), amount
                )
                return true
            }
            fun balance(uuid: UUID): Double = db.queryFirst("SELECT balance FROM economy WHERE uuid = ?", uuid.toString()) { it.getDouble(1) } ?: 0.0

            val installed = store.installedAt(1000L)
            check("installed_at recorded", installed == 1000L)
            check("installed_at never moves", store.installedAt(5000L) == 1000L)

            val alice = UUID.randomUUID()
            check("no row = not started", store.load(alice, S) == null)
            check("start", store.start(alice, S, 10L))
            check("no duplicate session", !store.start(alice, S, 11L))
            check("started active at step 0", store.load(alice, S)?.let { it.state == TutorialState.ACTIVE && it.step == 0 } == true)
            check("lifesteal independent", store.load(alice, L) == null)

            // Advance exactly once per step.
            check("advance 0", store.advance(alice, S, 0, 20L))
            check("double advance refused", !store.advance(alice, S, 0, 21L))
            check("progress persisted", store.load(alice, S)?.step == 1)
            store.advance(alice, S, 1, 22L)
            store.advance(alice, S, 2, 23L)
            store.advance(alice, S, 3, 24L)
            check("earnings saved", store.saveProgress(alice, S, 4, 65.0, 25L))
            check("earnings for a different step refused", !store.saveProgress(alice, S, 3, 999.0, 25L))
            check("earnings persisted", store.load(alice, S)?.progress == 65.0)
            store.advance(alice, S, 4, 26L)
            check("step progress resets on advance", store.load(alice, S)?.progress == 0.0)
            store.advance(alice, S, 5, 27L)

            // Completing early is refused.
            val bob = UUID.randomUUID()
            store.start(bob, S, 10L)
            check("complete before the last step refused", store.complete(bob, S, 6, 50_000.0, 30L) == TutorialStore.Completion.NOT_COMPLETED)
            check("no reward for an unfinished tutorial", store.reward(bob, S) == null)

            // Complete: once, concurrently.
            val wins = AtomicInteger()
            val gate = CountDownLatch(1)
            val threads = (1..4).map {
                Thread {
                    gate.await()
                    if (store.complete(alice, S, 6, 50_000.0, 40L) == TutorialStore.Completion.NEW_REWARD) wins.incrementAndGet()
                }.also { it.start() }
            }
            gate.countDown()
            threads.forEach { it.join() }
            check("concurrent completion happens once", wins.get() == 1)
            check("completed", store.load(alice, S)?.state == TutorialState.COMPLETED)
            check("reward pending after completion", store.reward(alice, S)?.status == TutorialStore.RewardStatus.PENDING)

            // Failed payment keeps the entitlement.
            check("refused deposit fails", store.pay(alice, S, 50L) { false } == TutorialStore.Payout.FAILED)
            check("failed payment not marked paid", store.reward(alice, S)?.status == TutorialStore.RewardStatus.PENDING)
            check("throwing deposit fails", store.pay(alice, S, 51L) { throw java.sql.SQLException("disk I/O") } == TutorialStore.Payout.FAILED)
            check("still pending after a database error", store.reward(alice, S)?.status == TutorialStore.RewardStatus.PENDING)
            check("no money moved by failures", balance(alice) == 0.0)

            // Pays once.
            check("survival pays", store.pay(alice, S, 60L) { deposit(alice, it) } == TutorialStore.Payout.PAID)
            check("survival paid 50,000", balance(alice) == 50_000.0)
            check("second payment refused", store.pay(alice, S, 61L) { deposit(alice, it) } == TutorialStore.Payout.ALREADY_PAID)
            check("still 50,000", balance(alice) == 50_000.0)

            // Restart never resets the reward; replay completion never pays again.
            store.restart(alice, S, 70L)
            check("restart from objective 1", store.load(alice, S)?.let { it.state == TutorialState.ACTIVE && it.step == 0 && it.progress == 0.0 } == true)
            check("restart keeps the reward", store.reward(alice, S)?.status == TutorialStore.RewardStatus.PAID)
            for (i in 0..5) store.advance(alice, S, i, 71L)
            check("replay completion finds the old reward", store.complete(alice, S, 6, 50_000.0, 80L) == TutorialStore.Completion.REWARD_EXISTS)
            check("replay pays nothing", store.pay(alice, S, 81L) { deposit(alice, it) } == TutorialStore.Payout.ALREADY_PAID && balance(alice) == 50_000.0)

            // Skip: persisted, no reward, replay earns the unclaimed one.
            val carol = UUID.randomUUID()
            store.start(carol, L, 10L)
            check("skip active", store.skip(carol, L, 11L))
            check("skip persists", store.load(carol, L)?.state == TutorialState.SKIPPED)
            check("skip again does nothing", !store.skip(carol, L, 12L))
            check("skipping grants no reward", store.reward(carol, L) == null)
            check("skip doesn't touch survival", store.load(carol, S) == null)
            val dave = UUID.randomUUID()
            check("skip without a row", store.skip(dave, S, 10L) && store.load(dave, S)?.state == TutorialState.SKIPPED)
            store.restart(carol, L, 20L)
            for (i in 0..5) check("lifesteal advance $i", store.advance(carol, L, i, 21L))
            check("skipped player can still earn it", store.complete(carol, L, 6, 25_000.0, 30L) == TutorialStore.Completion.NEW_REWARD)
            check("lifesteal pays 25,000", store.pay(carol, L, 31L) { deposit(carol, it) } == TutorialStore.Payout.PAID && balance(carol) == 25_000.0)
            check("lifesteal reward is its own", store.reward(carol, S) == null)
            check("completed tutorial can't be skipped", !store.skip(carol, L, 40L))

            // Gamemodes never share a reward.
            store.restart(carol, S, 50L)
            for (i in 0..5) store.advance(carol, S, i, 51L)
            check("survival reward separate from lifesteal", store.complete(carol, S, 6, 50_000.0, 52L) == TutorialStore.Completion.NEW_REWARD)
            check("survival reward amount", store.reward(carol, S)?.amount == 50_000.0)

            // A pending reward is the only thing a retry pays.
            check("no entitlement, nothing paid", store.pay(dave, S, 60L) { deposit(dave, it) } == TutorialStore.Payout.NO_ENTITLEMENT && balance(dave) == 0.0)
        } finally {
            db.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }
}

private fun wiring() {
    check("/tutorial is NETWORK", GamemodeAssignments.COMMANDS["tutorial"] == GamemodeScope.NETWORK)
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("plugin.yml declares /tutorial", Regex("""(?m)^  tutorial:\n    description: .*\n    usage: /tutorial \[start\|skip\|restart""").containsMatchIn(pluginYml))

    val yml = File("src/main/resources/tutorial-walkthrough.yml").readText()
    for (gm in ServerGamemode.entries) {
        for (step in TutorialRules.stepsFor(gm)) {
            check("${gm.id}.${step.id} texts", Regex("""(?m)^${gm.id}:\n(?:.*\n)*?  ${Regex.escape(step.id)}:\n    action-bar: .+\n    lines:\n""").containsMatchIn(yml))
        }
    }
    check("survival reward 50000", Regex("""(?m)^  survival:\n    enabled: true\n    reward: 50000$""").containsMatchIn(yml))
    check("lifesteal reward 25000", Regex("""(?m)^  lifesteal:\n    enabled: true\n    reward: 25000$""").containsMatchIn(yml))
    check("walk distance 10", yml.contains("\n  walk-distance: 10\n"))
    check("earn amount 100", yml.contains("\n  earn-amount: 100\n"))

    fun source(path: String) = File("src/main/kotlin/com/liam/joshymc/$path").readText()
    check("kit claim hook", source("manager/KitManager.kt").contains("TutorialSignal.StarterKitClaimed"))
    check("rtp hook after the teleport", Regex("""!player\.teleport\(destination\)\) return[\s\S]{0,400}TutorialSignal\.RandomTeleported""").containsMatchIn(source("command/RtpCommand.kt")))
    check("claim hook", source("manager/ClaimManager.kt").contains("TutorialSignal.ClaimCreated"))
    check("sale hook", source("manager/SaleManager.kt").contains("TutorialSignal.Earned"))
    check("quest pages hook", source("manager/QuestCycleManager.kt").let { it.contains("TutorialSignal.DailyQuestsOpened") && it.contains("TutorialSignal.WeeklyQuestsOpened") })
    check("discord hook", source("command/DiscordCommand.kt").contains("TutorialSignal.DiscordInfoShown"))
    check("hearts hook", source("lifesteal/LifestealInfoCommand.kt").contains("TutorialSignal.HeartsInfoOpened"))
    check("combat info hook", source("lifesteal/LifestealPvpInfoCommand.kt").contains("TutorialSignal.CombatInfoOpened"))
    check("teams hook", source("lifesteal/teams/LifestealTeamMenu.kt").contains("TutorialSignal.TeamsMenuOpened"))
    check("economy never pays the tutorial except through the store", !source("tutorial/TutorialManager.kt").contains("economyManager.setBalance"))
    // Every sell path reports the money it paid.
    for (path in listOf("listener/SellWandListener.kt", "manager/ServerShopManager.kt", "manager/MarketManager.kt", "command/SellCommand.kt")) {
        val text = source(path)
        val calls = Regex("""saleManager\.recordSales?\(""").findAll(text).count()
        val withEarnings = Regex("""saleManager\.recordSales?\([^\n]*earned = totalEarned\)""").findAll(text).count()
        check("$path reports earnings on every sale", calls > 0 && calls == withEarnings)
    }
    check("old GUI no longer auto-opens on first join", !source("listener/WelcomeListener.kt").contains("openForPlayer"))
}
