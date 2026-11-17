// Checks the Lifesteal Starter Kit and Lifesteal supply kits (issue #1577): the bundled kit, the
// one-time claim, cooldowns and restart persistence against a real SQLite database, the claim
// refusals (combat, Ranked, restores, event inventory), Lifesteal rank access, Survival isolation and
// the claim's ordering (refuse → fit check → reserve → give). The Gradle build has no test source
// set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-kit-check.init.gradle.kts lifestealKitCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.kits.LifestealKitRules
import com.liam.joshymc.lifesteal.kits.LifestealKitRules.Refusal
import com.liam.joshymc.lifesteal.kits.LifestealKitRules.State
import com.liam.joshymc.lifesteal.ranks.LifestealRankRules
import com.liam.joshymc.lifesteal.ranks.LifestealRankRules.KitDecision
import com.liam.joshymc.lifesteal.ranks.LifestealRankStore
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.KitCatalog
import com.liam.joshymc.manager.KitCatalog.Category
import com.liam.joshymc.manager.KitCatalog.Entry
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files
import java.util.UUID
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

private fun <T> withDb(dir: File, block: (DatabaseManager) -> T): T {
    val db = DatabaseManager(dir, Logger.getAnonymousLogger().apply { useParentHandlers = false })
    db.start()
    try {
        return block(db)
    } finally {
        db.shutdown()
    }
}

private val CLEAR = State(
    dead = false, modMode = false, rankedHeld = false, rankedSpectating = false, rankedRestorePending = false,
    inEventWorld = false, eventInventoryUnsettled = false, combatTagged = false, refuseWhileCombatTagged = true,
)

private val STARTER_ITEMS = listOf(
    "iron_helmet" to 1, "iron_chestplate" to 1, "iron_leggings" to 1, "iron_boots" to 1,
    "iron_sword" to 1, "iron_pickaxe" to 1, "iron_axe" to 1, "iron_shovel" to 1, "shield" to 1,
    "cooked_beef" to 32, "oak_log" to 32, "torch" to 32, "water_bucket" to 1,
)

@Suppress("UNCHECKED_CAST")
fun main() {
    // ── Bundled Lifesteal Starter Kit ───────────────────
    val kitsYml = Yaml().load<Map<String, Any?>>(File("src/main/resources/kits.yml").readText())
    val kits = kitsYml["kits"] as Map<String, Map<String, Any?>>
    val starter = kits["lifesteal_starter"]
    check("lifesteal_starter is bundled", starter != null)
    starter!!
    check("starter is a Lifesteal kit", KitCatalog.gamemodeOf(starter["gamemode"] as String?) == ServerGamemode.LIFESTEAL)
    check("starter is in the Starter category", starter["category"] == "starter")
    check("starter is one-time", starter["one-time"] == true)
    check("starter is open to everyone (joshymc.kit, default true)", starter["permission"] == "joshymc.kit")
    val items = (starter["items"] as Map<String, Map<String, Any?>>).toSortedMap(compareBy { it.toInt() }).values.toList()
    check("starter has exactly the issue's contents",
        items.map { (it["id"] as String).removePrefix("minecraft:") to it["count"] as Int } == STARTER_ITEMS)
    check("starter gear is unenchanted (no components)", items.none { it.containsKey("components") })
    check("starter fits an empty inventory", items.size <= 36)
    val lifestealKits = kits.filterValues { KitCatalog.gamemodeOf(it["gamemode"] as String?) == ServerGamemode.LIFESTEAL }.keys
    check("only the starter ships for Lifesteal (no rank kit rewards invented, no Survival kits copied)", lifestealKits == setOf("lifesteal_starter"))
    check("no Survival kit name is reused on Lifesteal", kits.keys.count { it == "lifesteal_starter" } == 1 && "starter" in kits)
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("joshymc.kit defaults to true",
        Regex("""\n  joshymc\.kit:\n\s+description:[^\n]*\n\s+default: true""").containsMatchIn(pluginYml))

    // ── Survival isolation / GUI ────────────────────────
    val entries = kits.map { (name, def) -> Entry(name, def["gamemode"] as String?, def["category"] as String?) }
    val lsStarters = KitCatalog.kitsFor(Category.STARTER, entries, ServerGamemode.LIFESTEAL, emptyList())
    check("Lifesteal /kit shows only the Lifesteal starter", lsStarters == listOf("lifesteal_starter"))
    check("Lifesteal /kit shows no Survival rank/special kits",
        KitCatalog.kitsFor(Category.RANK, entries, ServerGamemode.LIFESTEAL, emptyList()).isEmpty() &&
            KitCatalog.kitsFor(Category.SPECIAL, entries, ServerGamemode.LIFESTEAL, emptyList()).isEmpty())
    check("Survival /kit never shows the Lifesteal starter",
        Category.entries.none { "lifesteal_starter" in KitCatalog.kitsFor(it, entries, ServerGamemode.SURVIVAL, KitCatalog.DEFAULT_SURVIVAL_RANK_ORDER) })
    check("Survival starter unchanged",
        KitCatalog.kitsFor(Category.STARTER, entries, ServerGamemode.SURVIVAL, KitCatalog.DEFAULT_SURVIVAL_RANK_ORDER) == listOf("starter"))

    // ── Refusals ────────────────────────────────────────
    check("clear state = claim allowed", LifestealKitRules.refusal(CLEAR) == null)
    check("dead refused", LifestealKitRules.refusal(CLEAR.copy(dead = true)) == Refusal.DEAD)
    check("Mod Mode refused", LifestealKitRules.refusal(CLEAR.copy(modMode = true)) == Refusal.MOD_MODE)
    check("ranked match refused", LifestealKitRules.refusal(CLEAR.copy(rankedHeld = true)) == Refusal.RANKED_MATCH)
    check("ranked spectating refused", LifestealKitRules.refusal(CLEAR.copy(rankedSpectating = true)) == Refusal.RANKED_SPECTATING)
    check("pending ranked restore refused", LifestealKitRules.refusal(CLEAR.copy(rankedRestorePending = true)) == Refusal.RANKED_RESTORE)
    check("event world refused", LifestealKitRules.refusal(CLEAR.copy(inEventWorld = true)) == Refusal.EVENT_WORLD)
    check("unsettled event inventory refused", LifestealKitRules.refusal(CLEAR.copy(eventInventoryUnsettled = true)) == Refusal.EVENT_INVENTORY)
    check("combat tag refused", LifestealKitRules.refusal(CLEAR.copy(combatTagged = true)) == Refusal.COMBAT)
    check("combat tag allowed only when configured off",
        LifestealKitRules.refusal(CLEAR.copy(combatTagged = true, refuseWhileCombatTagged = false)) == null)
    check("ranked beats combat (first match wins)",
        LifestealKitRules.refusal(CLEAR.copy(rankedHeld = true, combatTagged = true)) == Refusal.RANKED_MATCH)
    check("every refusal has a message", Refusal.entries.all { it.message.isNotBlank() })

    // ── Cooldowns / one-time ────────────────────────────
    check("one-time ignores bypass", LifestealKitRules.reservedCooldown(oneTime = true, bypass = true, cooldownMillis = 0) == LifestealKitRules.ONE_TIME)
    check("bypass = no cooldown for normal kits", LifestealKitRules.reservedCooldown(false, true, 5_000) == 0L)
    check("normal kit keeps its cooldown", LifestealKitRules.reservedCooldown(false, false, 5_000) == 5_000L)
    check("never claimed = ready", LifestealKitRules.remaining(null, true, 0, 1) == 0L)
    check("claimed one-time = never ready", LifestealKitRules.remaining(1, true, 0, Long.MAX_VALUE / 2) > 0)
    check("cooldown counts down", LifestealKitRules.remaining(1_000, false, 5_000, 3_000) == 3_000L)
    check("cooldown over", LifestealKitRules.remaining(1_000, false, 5_000, 6_000) == 0L)

    // ── Seeding bundled kits into an existing kits.yml ──
    check("missing kit seeded", LifestealKitRules.seedable(listOf("lifesteal_starter"), listOf("starter"), emptyList()) == listOf("lifesteal_starter"))
    check("existing kit never overwritten", LifestealKitRules.seedable(listOf("lifesteal_starter"), listOf("Lifesteal_Starter"), emptyList()).isEmpty())
    check("deleted kit never re-added", LifestealKitRules.seedable(listOf("lifesteal_starter"), emptyList(), listOf("lifesteal_starter")).isEmpty())

    // ── Store: one claim, cooldown, restart, other kits ─
    val dir = Files.createTempDirectory("lifesteal-kit-check").toFile()
    val alice = UUID.randomUUID()
    val bob = UUID.randomUUID()
    val once = LifestealKitRules.ONE_TIME
    try {
        withDb(dir) { db ->
            db.createTable("CREATE TABLE IF NOT EXISTS kit_cooldowns (uuid TEXT NOT NULL, kit TEXT NOT NULL, claimed_at INTEGER NOT NULL, PRIMARY KEY (uuid, kit))")
            val store = LifestealRankStore(db)
            check("starter: first claim reserved", store.reserveKitClaim(alice, "lifesteal_starter", 1_000, once))
            check("starter: second claim refused", !store.reserveKitClaim(alice, "lifesteal_starter", 2_000, once))
            check("starter: refused even years later", !store.reserveKitClaim(alice, "lifesteal_starter", 1_000 + 3_650L * 86_400_000L, once))
            check("starter: refused row unchanged", store.kitClaimedAt(alice, "lifesteal_starter") == 1_000L)
            check("starter: other players unaffected", store.reserveKitClaim(bob, "lifesteal_starter", 2_000, once))
            check("Survival starter history is separate", store.reserveKitClaim(alice, "starter", 2_000, 5_000))
            check("supply kit: first claim", store.reserveKitClaim(alice, "supply", 10_000, 5_000))
            check("supply kit: inside cooldown refused", !store.reserveKitClaim(alice, "supply", 14_999, 5_000))
            check("supply kit: after cooldown", store.reserveKitClaim(alice, "supply", 15_000, 5_000))
        }
        withDb(dir) { db ->
            val store = LifestealRankStore(db)
            check("starter claim survives a restart", !store.reserveKitClaim(alice, "lifesteal_starter", 5_000, once))
            check("supply cooldown survives a restart", !store.reserveKitClaim(alice, "supply", 16_000, 5_000))
        }
    } finally {
        dir.deleteRecursively()
    }

    // ── Rank permissions (Lifesteal rank kits) ──────────
    check("no rank file: Starter keeps its permission", LifestealRankRules.kitDecision(null, emptySet(), "lifesteal_starter", possibleRankKit = false) == KitDecision.PERMISSION)
    check("no rank file: possible rank kits fail closed", LifestealRankRules.kitDecision(null, emptySet(), "supply", possibleRankKit = true) == KitDecision.UNVERIFIED)
    check("explicit starter category is never a rank kit", KitCatalog.categoryOf(Entry("lifesteal_starter", "lifesteal", "starter"), listOf("lifesteal_starter")) == Category.STARTER)

    // ── Other gamemode's kits (issue #1593) ─────────────
    val survivalKit = Entry("vip", null, null)
    val lifestealKit = Entry("lifesteal_starter", "lifesteal", "starter")
    check("Survival kit on Lifesteal: says where it belongs",
        KitCatalog.otherGamemodeMessage(survivalKit, ServerGamemode.LIFESTEAL) == "Kit 'vip' is a Survival kit and is unavailable on Lifesteal. Use /kit to see the kits here.")
    check("Lifesteal kit on Survival: says where it belongs", KitCatalog.otherGamemodeMessage(lifestealKit, ServerGamemode.SURVIVAL)?.contains("is a Lifesteal kit and is unavailable on Survival") == true)
    check("a kit of the current gamemode has no such message", KitCatalog.otherGamemodeMessage(lifestealKit, ServerGamemode.LIFESTEAL) == null)
    check("a kit with an invalid gamemode stays 'not found'", KitCatalog.otherGamemodeMessage(Entry("odd", "skyblock", null), ServerGamemode.LIFESTEAL) == null)

    // ── Claim ordering (source) ─────────────────────────
    val manager = File("src/main/kotlin/com/liam/joshymc/manager/KitManager.kt").readText()
    val claim = manager.substringAfter("private fun claimLifestealKit(").substringBefore("private fun cooldownMessage(")
    val refuseAt = claim.indexOf("LifestealKitRules.refusal(state)")
    val fitAt = claim.indexOf("probe.addItem(")
    val reserveAt = claim.indexOf("reserveKitClaim(")
    val giveAt = claim.indexOf("player.inventory.addItem(")
    check("claim: refusals → fit check → reserve → give", refuseAt in 0 until fitAt && fitAt < reserveAt && reserveAt < giveAt)
    check("claim: full inventory refused before anything is used", claim.contains("Your inventory is full"))
    check("claim: vouchers get fresh copies", claim.contains("freshVoucherCopy"))
    check("claim: one-time uses the rules' cooldown", claim.contains("LifestealKitRules.reservedCooldown(kit.isOneTime"))
    check("claim: player saved after giving", claim.indexOf("player.saveData()") > giveAt)
    check("claim checks every restore state",
        listOf("hasPendingRestore", "isSpectator", "inventories.unsettled", "isInEventWorld", "isTagged", "holds(player)").all { it in claim })
    check("one-time kits ignore the cooldown bypass in the menu too",
        manager.substringAfter("fun getCooldownRemaining(").substringBefore("hasBypass(player, name)").contains("kit.isOneTime"))
    val notFound = manager.substringAfter("fun claimKit(").substringBefore("if (!canUse(player, kit))")
    check("claim: other gamemode's kit is named, disabled kits stay hidden", notFound.contains("KitCatalog.otherGamemodeMessage(") && notFound.contains("takeIf { it.available }"))
    check("/kit <name> lets claimKit word the refusal", !File("src/main/kotlin/com/liam/joshymc/command/KitCommand.kt").readText().contains("not found"))
    check("Survival kits are never one-time", manager.contains("val isOneTime: Boolean get() = isLifesteal && oneTime"))

    println("Lifesteal kit checks passed ($checks checks).")
}
