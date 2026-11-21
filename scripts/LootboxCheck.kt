// Checks the Lootbox system (issue #1602): lootboxes.yml parsing and validation, weighted draws,
// the 5-of-27 selection, the animation plan, the opening refusals, the durable opening store
// against a real SQLite database (in a fresh temp directory, deleted afterwards) and the wiring.
// The Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lootbox-check.init.gradle.kts lootboxCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lootbox.LootboxAnimationPlan
import com.liam.joshymc.lootbox.LootboxAnimationSettings
import com.liam.joshymc.lootbox.LootboxConfig
import com.liam.joshymc.lootbox.LootboxDraw
import com.liam.joshymc.lootbox.LootboxLayout
import com.liam.joshymc.lootbox.LootboxRewardType
import com.liam.joshymc.lootbox.LootboxRules
import com.liam.joshymc.lootbox.LootboxSelection
import com.liam.joshymc.lootbox.LootboxStore
import com.liam.joshymc.lootbox.LootboxStore.OpeningState
import com.liam.joshymc.lootbox.LootboxStore.RewardState
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.PurchaseRewards
import com.liam.joshymc.staff.DelegatedCommandRules
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.UUID
import java.util.logging.Logger
import kotlin.math.abs
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

private fun withDb(dir: File, block: (DatabaseManager) -> Unit) {
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        block(db)
    } finally {
        db.shutdown()
    }
}

/** Accepts everything except the names the checks use to provoke problems. */
private object FakeResolver : LootboxConfig.Resolver {
    override fun itemMaterial(name: String) = if (name == "NOT_A_MATERIAL") "'$name' isn't an item material." else null
    override fun guiMaterial(name: String) = itemMaterial(name)
    override fun enchantment(material: String, key: String, level: Int) = if (key == "bogus") "unknown enchantment '$key'." else null
    override fun customEnchantment(material: String, id: String, level: Int) = if (id == "bogus") "bad custom enchant" else null
    override fun customItem(id: String) = if (id == "missing_item") "unknown custom item '$id'." else null
    override fun snapshot(base64: String) = if (base64 == "broken") "item-base64 can't be decoded on this server." else null
    override fun crate(id: String) = if (id == "nocrate") "unknown crate '$id'." else null
    override fun chatTag(id: String) = if (id == "notag") "not a voucher tag" else null
    override fun sound(key: String) = if (key == "NOT_A_SOUND") "unknown sound '$key'." else null
}

private fun parse(text: String): LootboxConfig.Result {
    val yaml = YamlConfiguration()
    yaml.loadFromString(text)
    return LootboxConfig.parse(object : LootboxConfig.Source {
        override fun get(path: String): Any? = yaml.get(path, null)?.takeUnless { it is ConfigurationSection }
        override fun keys(path: String): List<String>? = (yaml.get(path, null) as? ConfigurationSection)?.getKeys(false)?.toList()
    }, FakeResolver)
}

private val BUNDLED = File("src/main/resources/lootboxes.yml").readText()

/** The bundled file with level 2's empty pool replaced by [rewards] (YAML, indented under `rewards:`). */
private fun withLevel2(rewards: String): LootboxConfig.Result {
    val match = Regex("""(  2:\n(?:    .*\n)*?)    rewards: \{\}\n""").find(BUNDLED)
    val replaced = if (match == null) BUNDLED else BUNDLED.substring(0, match.range.first) + match.groupValues[1] +
        "    rewards:\n" + rewards.prependIndent("      ") + "\n" + BUNDLED.substring(match.range.last + 1)
    check("level 2 pool was replaced", replaced != BUNDLED)
    return parse(replaced)
}

private fun prize(id: String, type: LootboxRewardType = LootboxRewardType.ITEM, amount: Int = 1) =
    LootboxStore.Prize(id, "&f$id", type, "payload-$id", amount, "icon-$id")

fun main() {
    // ── Bundled file ───────────────────────────────────────
    val bundled = parse(BUNDLED)
    check("bundled lootboxes.yml has no problems: ${bundled.problems}", bundled.problems.isEmpty())
    val settings = checkNotNull(bundled.settings)
    check("bundled is enabled", settings.enabled)
    check("ten levels", settings.levels.keys == (1..10).toSet())
    for (n in 1..10) {
        val level = checkNotNull(settings.level(n))
        check("level $n default name", level.name.replace(Regex("&[0-9a-fk-or]"), "") == "Level $n Lootbox")
        check("level $n opens in both gamemodes", level.gamemodes == setOf(ServerGamemode.SURVIVAL, ServerGamemode.LIFESTEAL))
        check("level $n ships no live rewards", level.activeRewards.isEmpty())
        check("level $n can't be opened until rewards are added", LootboxConfig.levelProblem(settings, n)?.contains("no enabled rewards") == true)
        check("level $n uses its Lucky Block model", level.itemModel == "joshymc:lootbox_level_$n" && level.itemModel == LootboxConfig.defaultItemModel(n))
        check("level $n model ships in the resource pack", File("resourcepack/art/items/lootbox_level_$n.py").isFile)
    }
    // Issue #1603: a file written before the art existed (no item-model key) still gets the model; `none` turns it off.
    run {
        val noKeys = parse(BUNDLED.lines().filterNot { it.trimStart().startsWith("item-model:") }.joinToString("\n"))
        check("missing item-model keys parse: ${noKeys.problems}", noKeys.problems.isEmpty())
        check("missing item-model = the bundled model", (1..10).all { checkNotNull(noKeys.settings).level(it)?.itemModel == "joshymc:lootbox_level_$it" })
        val off = parse(BUNDLED.replace("item-model: \"joshymc:lootbox_level_3\"", "item-model: none"))
        check("item-model none = no model", checkNotNull(off.settings).level(3)?.itemModel == null && off.settings!!.level(4)?.itemModel != null)
        val custom = parse(BUNDLED.replace("item-model: \"joshymc:lootbox_level_3\"", "item-model: \"Joshymc:Custom/Box\""))
        check("custom item-model kept (lower case)", checkNotNull(custom.settings).level(3)?.itemModel == "joshymc:custom/box")
        val bad = parse(BUNDLED.replace("item-model: \"joshymc:lootbox_level_3\"", "item-model: \"no colon\""))
        check("malformed item-model is a problem", bad.problems.any { it.startsWith("levels.3:") && it.contains("item-model") })
    }
    val examples = checkNotNull(settings.level(1)).rewards
    check("level 1 has disabled examples of every reward type", examples.map { it.type }.toSet() == LootboxRewardType.entries.toSet())
    check("every example is disabled", examples.none { it.enabled })
    check("level 11 doesn't exist", LootboxConfig.levelProblem(settings, 11) != null && LootboxConfig.levelProblem(settings, 0) != null)
    check("null settings explain themselves", LootboxConfig.levelProblem(null, 1)?.contains("invalid") == true)
    check("default sounds all parse", settings.sounds.keys == LootboxConfig.DEFAULT_SOUNDS.keys && settings.sounds.values.all { it != null })
    check("message override applies", settings.message("opened") == "&aYou opened a {name}&a!")
    check("unset message uses its default", settings.message("busy") == LootboxConfig.DEFAULT_MESSAGES["busy"])

    // ── Level pools and validation ─────────────────────────
    run {
        val r = withLevel2("diamonds:\n  type: item\n  material: DIAMOND\n  amount: 3\n  weight: 10\ncoins:\n  type: coins\n  amount: 250.555\n  weight: 5")
        check("valid pool has no problems: ${r.problems}", r.problems.isEmpty())
        val s = checkNotNull(r.settings)
        check("level 2 now opens", LootboxConfig.levelProblem(s, 2) == null)
        check("level 3 still doesn't", LootboxConfig.levelProblem(s, 3) != null)
        val pool = checkNotNull(s.level(2)).activeRewards
        check("level 2 pool is its own", pool.map { it.id } == listOf("diamonds", "coins") && checkNotNull(s.level(1)).activeRewards.isEmpty())
        check("item amount", pool[0].amount == 3 && pool[0].reference == "DIAMOND")
        check("coins rounded to cents", pool[1].money == 250.56 && pool[1].type == LootboxRewardType.COINS)
    }
    for ((label, weight) in listOf("negative" to "-1", "zero" to "0", "NaN" to ".nan", "infinite" to ".inf", "text" to "lots", "too high" to "1e12")) {
        val r = withLevel2("bad:\n  type: item\n  material: DIAMOND\n  weight: $weight")
        check("$label weight is a problem", r.problems.any { it.startsWith("levels.2:") && it.contains("weight") })
        val s = checkNotNull(r.settings) { "a level problem must not close the whole file" }
        check("$label weight closes level 2", LootboxConfig.levelProblem(s, 2)?.contains("configuration problems") == true)
    }
    check("missing weight is a problem", withLevel2("bad:\n  type: item\n  material: DIAMOND").problems.any { it.contains("'weight' is required") })
    check("fractional weights are fine", withLevel2("a:\n  type: item\n  material: DIAMOND\n  weight: 0.25").problems.isEmpty())
    check("unknown type", withLevel2("bad:\n  type: hearts\n  weight: 1").problems.any { it.contains("type 'hearts'") })
    check("unknown material", withLevel2("bad:\n  type: item\n  material: NOT_A_MATERIAL\n  weight: 1").problems.any { it.contains("NOT_A_MATERIAL") })
    check("unknown enchantment", withLevel2("bad:\n  type: item\n  material: DIAMOND_SWORD\n  enchantments: { bogus: 1 }\n  weight: 1").problems.any { it.contains("bogus") })
    check("enchant level range", withLevel2("bad:\n  type: item\n  material: DIAMOND_SWORD\n  enchantments: { sharpness: 0 }\n  weight: 1").problems.isNotEmpty())
    check("missing custom item", withLevel2("bad:\n  type: custom-item\n  item: missing_item\n  weight: 1").problems.any { it.contains("missing_item") })
    check("custom item needs an id", withLevel2("bad:\n  type: custom-item\n  weight: 1").problems.any { it.contains("'item' is required") })
    check("broken snapshot", withLevel2("bad:\n  type: item-base64\n  item-base64: broken\n  weight: 1").problems.isNotEmpty())
    check("unknown crate", withLevel2("bad:\n  type: crate-key\n  crate: nocrate\n  weight: 1").problems.isNotEmpty())
    check("unknown chat tag", withLevel2("bad:\n  type: chat-tag\n  tag: notag\n  weight: 1").problems.isNotEmpty())
    check("chat-tag unlock has no amount", withLevel2("bad:\n  type: chat-tag\n  tag: x\n  amount: 2\n  weight: 1").problems.any { it.contains("no amount") })
    check("negative coins", withLevel2("bad:\n  type: coins\n  amount: -5\n  weight: 1").problems.isNotEmpty())
    check("command needs commands", withLevel2("bad:\n  type: command\n  weight: 1").problems.any { it.contains("commands") })
    check("commands only on command rewards", withLevel2("bad:\n  type: item\n  material: DIAMOND\n  commands: [say hi]\n  weight: 1").problems.isNotEmpty())
    check("enchantments only on items", withLevel2("bad:\n  type: custom-item\n  item: token\n  enchantments: { sharpness: 1 }\n  weight: 1").problems.isNotEmpty())
    check("item amount capped", withLevel2("bad:\n  type: item\n  material: DIAMOND\n  amount: 5000\n  weight: 1").problems.isNotEmpty())
    check("reward gamemodes inside the level's",
        withLevel2("bad:\n  type: item\n  material: DIAMOND\n  weight: 1\n  gamemodes: [creative]").problems.any { it.contains("creative") })
    check("disabled rewards aren't resolved", withLevel2("off:\n  enabled: false\n  type: custom-item\n  item: missing_item\n  weight: 1").problems.isEmpty())
    check("case-duplicate ids refused", withLevel2("Dup:\n  type: item\n  material: DIAMOND\n  weight: 1\ndup:\n  type: item\n  material: DIAMOND\n  weight: 1")
        .problems.any { it.contains("used twice") })
    run {
        val four = (1..4).joinToString("\n") { "u$it:\n  type: item\n  material: DIAMOND\n  weight: 1\n  unique: true" }
        val s = checkNotNull(withLevel2(four).settings)
        check("four unique rewards can't fill five prizes", LootboxConfig.levelProblem(s, 2)?.contains("unique") == true)
        val five = four + "\nu5:\n  type: item\n  material: DIAMOND\n  weight: 1\n  unique: true"
        check("five unique rewards can", LootboxConfig.levelProblem(checkNotNull(withLevel2(five).settings), 2) == null)
    }
    check("bad sound closes the file", parse(BUNDLED.replace("sound: BLOCK_CHEST_OPEN", "sound: NOT_A_SOUND")).settings == null)
    check("end interval below start closes the file", parse(BUNDLED.replace("end-interval-ticks: 8", "end-interval-ticks: 1")).settings == null)
    check("missing levels closes the file", parse("enabled: true\n").settings == null)
    check("level 11 is a problem", parse(BUNDLED + "  11:\n    name: x\n").settings == null)
    check("unknown message key is a problem", parse(BUNDLED.replace("  opened: ", "  nonsense: \"x\"\n  opened: ")).settings == null)
    check("disabled file", LootboxConfig.levelProblem(checkNotNull(parse(BUNDLED.replace("\nenabled: true", "\nenabled: false")).settings), 1)?.contains("disabled") == true)

    // ── Weighted draws ─────────────────────────────────────
    val pool = listOf(LootboxDraw.Entry("a", 1.0, false), LootboxDraw.Entry("b", 3.0, false), LootboxDraw.Entry("c", 6.0, false))
    val rng = Random(1602)
    val counts = HashMap<String, Int>()
    val openings = 40_000
    var duplicates = 0
    repeat(openings) {
        val drawn = checkNotNull(LootboxDraw.draw(pool, LootboxDraw.PRIZES) { rng.nextDouble() })
        check("five prizes per opening", drawn.size == 5)
        if (drawn.toSet().size < drawn.size) duplicates++
        drawn.forEach { counts.merge(it, 1, Int::plus) }
    }
    val total = openings * 5.0
    for ((id, expected) in listOf("a" to 0.1, "b" to 0.3, "c" to 0.6)) {
        val share = (counts[id] ?: 0) / total
        check("draw share of $id ~ $expected (got $share)", abs(share - expected) < 0.01)
    }
    check("draws are with replacement (duplicates happen)", duplicates > 0)
    check("chances", LootboxDraw.chances(pool).map { it.second } == listOf(0.1, 0.3, 0.6))
    run {
        val mixed = listOf(LootboxDraw.Entry("u", 1000.0, true), LootboxDraw.Entry("x", 1.0, false))
        repeat(2000) {
            val drawn = checkNotNull(LootboxDraw.draw(mixed, 5) { rng.nextDouble() })
            check("a unique reward comes at most once", drawn.count { it == "u" } <= 1)
        }
        val allUnique = (1..5).map { LootboxDraw.Entry("u$it", it.toDouble(), true) }
        repeat(500) {
            val drawn = checkNotNull(LootboxDraw.draw(allUnique, 5) { rng.nextDouble() })
            check("five unique rewards = each exactly once", drawn.sorted() == (1..5).map { "u$it" })
        }
        check("four unique can't fill", !LootboxDraw.canFill(allUnique.take(4), 5) && LootboxDraw.draw(allUnique.take(4), 5) { 0.5 } == null)
        check("empty pool can't fill", !LootboxDraw.canFill(emptyList<LootboxDraw.Entry<String>>(), 5))
        check("invalid weights never drawn", LootboxDraw.draw(listOf(LootboxDraw.Entry("nan", Double.NaN, false), LootboxDraw.Entry("ok", 1.0, false)), 5) { rng.nextDouble() }
            ?.all { it == "ok" } == true)
        check("random 1.0 stays in range", LootboxDraw.draw(pool, 1) { 1.0 } == listOf("c"))
        check("random 0 picks the first", LootboxDraw.draw(pool, 1) { 0.0 } == listOf("a"))
    }

    // ── Selection ──────────────────────────────────────────
    run {
        val sel = LootboxSelection()
        check("27 selectable slots", sel.slots == 27 && LootboxLayout.MYSTERY_SLOTS.count() == 27)
        check("starts empty", sel.count == 0 && !sel.ready)
        check("lock refused below five", !sel.lock())
        for (slot in listOf(0, 5, 13, 20, 26)) check("select $slot", sel.toggle(slot) == LootboxSelection.Toggle.SELECTED)
        check("ready at five", sel.ready && sel.count == 5)
        check("sixth refused", sel.toggle(7) == LootboxSelection.Toggle.FULL && sel.count == 5 && !sel.isPicked(7))
        check("deselect", sel.toggle(13) == LootboxSelection.Toggle.DESELECTED && sel.count == 4 && !sel.ready)
        check("reselect another", sel.toggle(7) == LootboxSelection.Toggle.SELECTED && sel.ready)
        check("out of range", sel.toggle(27) == LootboxSelection.Toggle.INVALID && sel.toggle(-1) == LootboxSelection.Toggle.INVALID)
        check("picks sorted", sel.picks == listOf(0, 5, 7, 20, 26))
        check("lock once", sel.lock() && !sel.lock())
        check("locked selection can't change", sel.toggle(0) == LootboxSelection.Toggle.LOCKED && sel.count == 5)
    }

    // ── Layout and animation ───────────────────────────────
    check("54 slots", LootboxLayout.SIZE == 54)
    check("cancel 45, confirm 49", LootboxLayout.CANCEL_SLOT == 45 && LootboxLayout.CONFIRM_SLOT == 49 && LootboxLayout.CLAIM_SLOT == 49)
    check("info in the 27-44 band", LootboxLayout.INFO_SLOT in LootboxLayout.INFO_BAND)
    check("reward slots 20-24", LootboxLayout.REWARD_SLOTS == listOf(20, 21, 22, 23, 24))
    check("indicators never cover a prize", (0..4).flatMap { LootboxLayout.indicatorSlots(it) }.none { it in LootboxLayout.REWARD_SLOTS || it !in 0 until 54 })
    run {
        val anim = LootboxAnimationSettings(2, 8, 40, 12, 30)
        val plan = LootboxAnimationPlan.plan(5, anim)
        check("five reels", plan.reels == 5)
        check("reels stop one after another", plan.stopTicks == listOf(40, 52, 64, 76, 88))
        check("summary after the last stop", plan.endTick == 88 + 30)
        for (reel in 0 until 5) {
            val ticks = plan.changeTicks[reel]
            check("reel $reel rolls", ticks.size >= 5 && ticks.first() == 0)
            check("reel $reel changes before it stops", ticks.all { it < plan.stopTicks[reel] })
            val gaps = ticks.zipWithNext { a, b -> b - a }
            check("reel $reel slows down", gaps.zipWithNext().all { (a, b) -> b >= a } && gaps.first() <= gaps.last())
            check("reel $reel interval within bounds", gaps.all { it in 1..8 })
        }
        val zero = LootboxAnimationPlan.plan(5, LootboxAnimationSettings(1, 1, 10, 0, 0))
        check("no stagger = all stop together", zero.stopTicks.toSet() == setOf(10))
    }

    // ── Refusals and commands ──────────────────────────────
    val ok = LootboxRules.State(false, false, false, false, false, false, false, false, false)
    check("ordinary player may open", LootboxRules.refusal(ok) == null)
    check("ranked match", LootboxRules.refusal(ok.copy(rankedHeld = true)) == LootboxRules.Refusal.RANKED_MATCH)
    check("ranked spectating", LootboxRules.refusal(ok.copy(rankedSpectating = true)) == LootboxRules.Refusal.RANKED_SPECTATING)
    check("ranked restore pending", LootboxRules.refusal(ok.copy(rankedRestorePending = true)) == LootboxRules.Refusal.RANKED_RESTORE)
    check("combat", LootboxRules.refusal(ok.copy(combatTagged = true)) == LootboxRules.Refusal.COMBAT)
    check("event world", LootboxRules.refusal(ok.copy(inEventWorld = true)) == LootboxRules.Refusal.EVENT_WORLD)
    check("event inventory", LootboxRules.refusal(ok.copy(eventInventoryUnsettled = true)) == LootboxRules.Refusal.EVENT_INVENTORY)
    check("mod mode", LootboxRules.refusal(ok.copy(modMode = true)) == LootboxRules.Refusal.MOD_MODE)
    check("dead", LootboxRules.refusal(ok.copy(dead = true)) == LootboxRules.Refusal.DEAD)
    check("spectator", LootboxRules.refusal(ok.copy(spectatorMode = true)) == LootboxRules.Refusal.SPECTATOR)
    check("signature covers level and version", LootboxRules.signaturePayload(1, 3) != LootboxRules.signaturePayload(1, 4) &&
        LootboxRules.signaturePayload(1, 3) != LootboxRules.signaturePayload(2, 3))
    check("command placeholders", LootboxRules.command("give {player} diamond", "Steve_1", "u") == "give Steve_1 diamond")
    check("floodgate names accepted", LootboxRules.command("x {player}", ".Bedrock_1", "u") == "x .Bedrock_1")
    check("odd names refused", LootboxRules.command("x {player}", "a; stop", "u") == null)

    // ── Store ──────────────────────────────────────────────
    val dir = Files.createTempDirectory("joshymc-lootbox-check").toFile()
    try {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        var firstId = 0
        var reviewId = 0
        var interruptedId = 0
        withDb(dir) { db ->
            val store = LootboxStore(db, { sql, params -> db.insertReturningId(sql, *params) })
            store.createSchema()
            store.createSchema() // idempotent
            val secret = store.secret("aa")
            check("secret is stored once", store.secret("bb") == secret && secret == "aa")

            var threw = false
            try { store.reserve(alice, "Alice", 1, "survival", listOf(1, 2, 3, 4, 5), listOf(prize("x"))) } catch (_: IllegalArgumentException) { threw = true }
            check("an opening needs exactly five prizes", threw)

            val prizes = listOf(prize("a", amount = 100), prize("b"), prize("c", LootboxRewardType.COINS), prize("d", LootboxRewardType.CHAT_TAG), prize("e", LootboxRewardType.COMMAND))
            val id = store.reserve(alice, "Alice", 3, "survival", listOf(0, 4, 9, 13, 26), prizes)
            firstId = id
            check("reserved = CONFIRMED", store.get(id)?.state == OpeningState.CONFIRMED)
            check("five prize rows", store.rewards(id).size == 5 && store.rewards(id).all { it.state == RewardState.PENDING })
            check("nothing owed before commit", store.pendingFor(alice).isEmpty() && store.claim(id, 0, alice) == null)
            check("commit", store.commit(id) && store.get(id)?.state == OpeningState.REWARDS_COMMITTED)
            check("commit only once", !store.commit(id))
            check("owed after commit", store.pendingFor(alice).size == 5 && store.pendingPlayers() == listOf(alice))
            check("slots recorded", store.get(id)?.slots == "0,4,9,13,26")

            check("someone else can't claim it", store.claim(id, 0, bob) == null)
            val claimed = checkNotNull(store.claim(id, 0, alice))
            check("claimed = DELIVERING", claimed.state == RewardState.DELIVERING && claimed.remaining == 100)
            check("no second claim", store.claim(id, 0, alice) == null)
            store.finish(id, 0, 64, RewardState.PENDING, null)
            check("partial delivery keeps the rest", store.reward(id, 0)?.let { it.state == RewardState.PENDING && it.remaining == 36 } == true)
            check("opening now DELIVERING", store.get(id)?.state == OpeningState.DELIVERING)
            checkNotNull(store.claim(id, 0, alice))
            store.finish(id, 0, 36, RewardState.DELIVERED, null)
            check("delivered", store.reward(id, 0)?.let { it.state == RewardState.DELIVERED && it.remaining == 0 } == true)

            var ran = 0
            check("currency once", store.completeWith(id, 2, alice, null) { ran++ })
            check("currency never twice", !store.completeWith(id, 2, alice, null) { ran++ } && ran == 1)
            check("other player's currency refused", !store.completeWith(id, 3, bob, null) { ran++ } && ran == 1)
            threw = false
            try { store.completeWith(id, 3, alice, null) { error("deposit failed") } } catch (_: IllegalStateException) { threw = true }
            check("a failed action rolls the prize back", threw && store.reward(id, 3)?.state == RewardState.PENDING)
            check("unlock after the failure", store.completeWith(id, 3, alice, "already owned") {})
            check("note kept", store.reward(id, 3)?.note == "already owned")

            threw = false
            try { db.execute("UPDATE lootbox_opening_rewards SET state = 'PENDING' WHERE opening_id = ? AND idx = 0", id) } catch (_: Exception) { threw = true }
            check("delivered prizes stay delivered", threw && store.reward(id, 0)?.state == RewardState.DELIVERED)
            threw = false
            try { db.execute("DELETE FROM lootbox_opening_rewards WHERE opening_id = ?", id) } catch (_: Exception) { threw = true }
            check("prizes are never deleted", threw && store.rewards(id).size == 5)

            check("fail", store.fail(id, 1, "gone"))
            check("failed isn't pending", store.pendingFor(alice).map { it.index } == listOf(4))
            check("resend a failed prize", store.resend(id, 1, "staff") && store.reward(id, 1)?.state == RewardState.PENDING)
            check("resend only once", !store.resend(id, 1, "staff"))
            checkNotNull(store.claim(id, 1, alice))
            store.finish(id, 1, 1, RewardState.DELIVERED, null)
            check("not all final yet", store.get(id)?.state == OpeningState.DELIVERING)
            check("command prize", store.completeWith(id, 4, alice, "commands dispatched") {})
            check("all five final = DELIVERED", store.get(id)?.state == OpeningState.DELIVERED && store.pendingFor(alice).isEmpty())
            threw = false
            try { db.execute("UPDATE lootbox_openings SET state = 'DELIVERING' WHERE id = ?", id) } catch (_: Exception) { threw = true }
            check("delivered openings stay delivered", threw)

            // Voided before anything was consumed.
            val voided = store.reserve(bob, "Bob", 1, "lifesteal", listOf(1, 2, 3, 4, 5), (1..5).map { prize("v$it") })
            check("void a reserved opening", store.void(voided, OpeningState.CONFIRMED, "save failed"))
            check("voided prizes", store.rewards(voided).all { it.state == RewardState.VOIDED } && store.get(voided)?.state == OpeningState.VOIDED)
            check("voided owes nothing", store.claim(voided, 0, bob) == null && store.pendingFor(bob).isEmpty() && !store.commit(voided))

            // Left for the restart below: one CONFIRMED (server stopped before the save), one prize mid-delivery.
            reviewId = store.reserve(bob, "Bob", 10, "lifesteal", listOf(1, 2, 3, 4, 5), (1..5).map { prize("r$it") })
            interruptedId = store.reserve(alice, "Alice", 2, "survival", listOf(1, 2, 3, 4, 5), (1..5).map { prize("i$it", amount = 10) })
            store.commit(interruptedId)
            checkNotNull(store.claim(interruptedId, 0, alice))
        }

        // ── Restart ────────────────────────────────────────
        withDb(dir) { db ->
            val store = LootboxStore(db, { sql, params -> db.insertReturningId(sql, *params) })
            store.createSchema()
            check("finished opening survives the restart", store.get(firstId)?.state == OpeningState.DELIVERED)
            val recovery = store.recoverAtStartup()
            check("CONFIRMED → REVIEW", recovery.review.map { it.id } == listOf(reviewId) && store.get(reviewId)?.state == OpeningState.REVIEW)
            check("DELIVERING → INTERRUPTED", recovery.interrupted.map { it.openingId to it.index } == listOf(interruptedId to 0) &&
                store.reward(interruptedId, 0)?.state == RewardState.INTERRUPTED)
            check("recovery is idempotent", store.recoverAtStartup().let { it.review.isEmpty() && it.interrupted.isEmpty() })
            check("REVIEW owes nothing", store.claim(reviewId, 0, bob) == null && store.pendingFor(bob).isEmpty())
            check("INTERRUPTED is never resent on its own", store.pendingFor(alice).map { it.index } == listOf(1, 2, 3, 4))
            check("rerolling is impossible: prizes unchanged", store.rewards(interruptedId).map { it.rewardId } == (1..5).map { "i$it" })
            check("staff grant a review", store.grantReviewed(reviewId, "staff") && store.pendingFor(bob).size == 5)
            check("grant only once", !store.grantReviewed(reviewId, "staff") && !store.void(reviewId, OpeningState.REVIEW, "x"))
            check("staff: interrupted was delivered", store.markDelivered(interruptedId, 0, "checked") && store.reward(interruptedId, 0)?.state == RewardState.DELIVERED)
            check("resolve only once", !store.markDelivered(interruptedId, 0, "again") && !store.resend(interruptedId, 0, "again"))
            check("attention lists open openings", store.attention(20).map { it.id }.containsAll(listOf(reviewId, interruptedId)))
            check("history per player", store.recentFor(alice, 10).map { it.id } == listOf(interruptedId, firstId))
        }
    } finally {
        dir.deleteRecursively()
    }

    // ── Wiring ─────────────────────────────────────────────
    check("/lootbox is NETWORK", GamemodeAssignments.COMMANDS["lootbox"] == GamemodeScope.NETWORK)
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("plugin.yml declares /lootbox", Regex("""(?m)^  lootbox:\n(    .*\n)*    permission: joshymc\.lootbox\.use$""").containsMatchIn(pluginYml))
    check("use defaults to true", Regex("""(?m)^  joshymc\.lootbox\.use:\n    description: .*\n    default: true$""").containsMatchIn(pluginYml))
    for (node in listOf("admin", "give", "reload", "recovery")) {
        check("$node is op only", Regex("""(?m)^  joshymc\.lootbox\.$node:\n    description: .*\n    default: op$""").containsMatchIn(pluginYml))
    }
    check("config.yml feature flag", Regex("""(?m)^  lootboxes: true$""").containsMatchIn(File("src/main/resources/config.yml").readText()))
    check("delegated /lootbox help allowed", DelegatedCommandRules.decide("lootbox help", "lootbox") == DelegatedCommandRules.Decision.ALLOWED)
    for (sub in listOf("give Steve 1", "giveall 1", "list", "reload", "admin pending")) {
        check("delegated /lootbox $sub refused", DelegatedCommandRules.decide("lootbox $sub", "lootbox") != DelegatedCommandRules.Decision.ALLOWED)
    }
    val yes = object : PurchaseRewards.Catalog {
        override fun crateExists(id: String) = false
        override fun moneyVoucherExists(id: String) = false
        override fun chatTagVoucherExists(id: String) = false
        override fun lootboxLevelUsable(level: Int) = level == 3
    }
    val parsed = PurchaseRewards.parse(listOf("lootbox", "3", "2", "ref-1"), yes)
    check("store can deliver Lootboxes", parsed is PurchaseRewards.Result.Ok && parsed.value.spec == PurchaseRewards.Spec(PurchaseRewards.Kind.LOOTBOX, "3", 2))
    check("unusable level refused", PurchaseRewards.parse(listOf("lootbox", "4", "1"), yes) is PurchaseRewards.Result.Error)
    check("level 11 refused", PurchaseRewards.parse(listOf("lootbox", "11", "1"), yes) is PurchaseRewards.Result.Error)
    check("bad quantity refused", PurchaseRewards.parse(listOf("lootbox", "3", "0"), yes) is PurchaseRewards.Result.Error)
    check("lootbox is an item delivery", PurchaseRewards.Kind.LOOTBOX.delivery == PurchaseRewards.Delivery.ITEM)

    println("Lootbox checks passed: $checks")
}
