// Checks Milestones (issue #1420): requirement and coin scaling, special/major/announced tiers, the
// per-player progress book (new players, tiers, pauses, independence, category levels, claims), the
// bundled milestones.yml through the real settings parser, the anti-exploit rules (placed blocks,
// generators, spawner mobs, PvP, fishing, exploration, worlds), the menu layouts, and the store
// against a real SQLite database (claims once ever, stale saves never undo a claim, pending rewards
// survive a restart, PvP cooldowns). No Bukkit class is loaded. From the repo root:
//
//   ./gradlew -I scripts/milestone-check.init.gradle.kts milestoneCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.gamemode.DedicatedServerFeatures
import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeFeature
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.milestone.ActivityClock
import com.liam.joshymc.milestone.CategoryDef
import com.liam.joshymc.milestone.CoinScaling
import com.liam.joshymc.milestone.CombatRules
import com.liam.joshymc.milestone.ExplorationRules
import com.liam.joshymc.milestone.FarmingRules
import com.liam.joshymc.milestone.FishingRules
import com.liam.joshymc.milestone.LoopDetector
import com.liam.joshymc.milestone.MilestoneBook
import com.liam.joshymc.milestone.MilestoneCatalog
import com.liam.joshymc.milestone.MilestoneCategory
import com.liam.joshymc.milestone.MilestoneLayout
import com.liam.joshymc.milestone.MilestoneMath
import com.liam.joshymc.milestone.MilestoneRewards
import com.liam.joshymc.milestone.MilestoneScaling
import com.liam.joshymc.milestone.MilestoneSettingsParser
import com.liam.joshymc.milestone.MilestoneSource
import com.liam.joshymc.milestone.MilestoneStore
import com.liam.joshymc.milestone.MilestoneWorlds
import com.liam.joshymc.milestone.MiningRules
import com.liam.joshymc.milestone.ObjectiveDef
import com.liam.joshymc.milestone.PlacedBlockCodec
import com.liam.joshymc.milestone.RateWindow
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

private const val S = MilestoneMath.SCALE

private val COINS = CoinScaling(10_000.0, 0.25, 0.6, 75_000.0)
private val LEVEL_COINS = CoinScaling(25_000.0, 0.25, 0.6, 200_000.0)

private fun objective(category: MilestoneCategory, id: String, base: Long, vararg matches: String) =
    ObjectiveDef(id, category, id, "PAPER", "", matches.toSet(), base, MilestoneScaling.DEFAULT, COINS)

private object AllExist : MilestoneRewards.Catalog {
    override fun crateExists(id: String) = id in setOf("common", "rare", "joshy")
    override fun chatTagExists(id: String) = id == "mapleborn"
    override fun customItemExists(id: String) = id == "token"
}

private fun testCatalog(): MilestoneCatalog {
    val spec = { s: String -> MilestoneRewards.parse(s, AllExist).getOrThrow() }
    val rewards = MilestoneRewards.Table(
        explicit = mapOf(5 to listOf(spec("coins 5000")), 10 to listOf(spec("cratekey common 1"))),
        every25 = listOf(spec("cratekey rare 1")),
        every100 = listOf(spec("cratekey joshy 1"), spec("credits 5")),
    )
    return MilestoneCatalog(
        listOf(
            CategoryDef(
                MilestoneCategory.FARMING, "Farming", "WHEAT", 50, MilestoneScaling(0.3, 0.6), LEVEL_COINS,
                listOf(objective(MilestoneCategory.FARMING, "wheat", 10, "WHEAT"), objective(MilestoneCategory.FARMING, "cane", 20, "SUGAR_CANE"))
            ),
            CategoryDef(
                MilestoneCategory.COMBAT, "Combat", "IRON_SWORD", 30, MilestoneScaling(0.3, 0.6), LEVEL_COINS,
                listOf(objective(MilestoneCategory.COMBAT, "zombies", 5, "ZOMBIE", "HUSK"), objective(MilestoneCategory.COMBAT, "players", 3, "PLAYER"))
            ),
            CategoryDef(
                MilestoneCategory.EXPLORATION, "Exploration", "COMPASS", 100, MilestoneScaling(0.3, 0.6), LEVEL_COINS,
                listOf(objective(MilestoneCategory.EXPLORATION, "walking", 50, "WALK"), objective(MilestoneCategory.EXPLORATION, "nether", 40, "NETHER"))
            ),
        ),
        rewards,
        rewards,
    )
}

/** MilestoneSource over snakeyaml maps, with the same fall-back-to-defaults rule as the plugin. */
private class MapSource(private val user: Map<*, *>, private val defaults: Map<*, *>?) : MilestoneSource {
    private fun lookup(root: Map<*, *>?, path: String): Any? {
        var node: Any? = root ?: return null
        for (part in path.split('.')) node = (node as? Map<*, *>)?.get(part) ?: return null
        return node
    }
    override fun isSet(path: String) = lookup(user, path) != null || lookup(defaults, path) != null
    override fun get(path: String): Any? = lookup(user, path) ?: lookup(defaults, path)
    override fun keys(path: String): List<String> =
        ((lookup(user, path) ?: lookup(defaults, path)) as? Map<*, *>)?.keys?.map { it.toString() } ?: emptyList()
}

fun main() {
    // ── Scaling ─────────────────────────────────────────────
    val scaling = MilestoneScaling.DEFAULT
    check("tier 1 needs exactly the base", MilestoneMath.requirement(100, 1, scaling) == 100L)
    var previous = 0L
    var previousStep = Long.MAX_VALUE
    var diminishing = true
    for (tier in 1..2000) {
        val r = MilestoneMath.requirement(1000, tier, scaling)
        check("tier $tier never decreases", r >= previous)
        check("tier $tier never zero", r > 0)
        if (tier > 2) {
            val step = r - previous
            if (step > previousStep + 1) diminishing = false
            previousStep = step
        } else if (tier == 2) previousStep = r - previous
        previous = r
    }
    check("growth slows (each step adds no more than the last, +1 rounding)", diminishing)
    check("requirements keep rising", MilestoneMath.requirement(1000, 2000, scaling) > MilestoneMath.requirement(1000, 100, scaling))
    check("tier 1000 stays achievable (< 25x base)", MilestoneMath.requirement(1000, 1000, scaling) < 25_000L)
    check("base 0 is treated as 1 (never zero)", MilestoneMath.requirement(0, 1, scaling) == 1L)
    check("huge base clamps", MilestoneMath.requirement(Long.MAX_VALUE, MilestoneMath.MAX_TIER, MilestoneScaling(10.0, 1.0)) == MilestoneMath.MAX_REQUIREMENT)
    check("tier past the max clamps", MilestoneMath.requirement(10, Int.MAX_VALUE, scaling) == MilestoneMath.requirement(10, MilestoneMath.MAX_TIER, scaling))
    check("scaled requirement fits in a Long", MilestoneMath.MAX_REQUIREMENT * S > 0 && MilestoneMath.MAX_REQUIREMENT * S < Long.MAX_VALUE)
    check("invalid exponent refused", MilestoneScaling.orNull(0.25, 1.5) == null && MilestoneScaling.orNull(0.25, 0.0) == null)
    check("invalid growth refused", MilestoneScaling.orNull(-1.0, 0.5) == null && MilestoneScaling.orNull(Double.NaN, 0.5) == null)

    // ── Coins ───────────────────────────────────────────────
    check("tier 1 coins = base", MilestoneMath.coins(COINS, 1) == 10_000.0)
    var lastCoins = 0.0
    for (tier in 1..5000) {
        val c = MilestoneMath.coins(COINS, tier)
        check("coins never decrease ($tier)", c >= lastCoins)
        check("coins never above the cap ($tier)", c <= COINS.cap)
        lastCoins = c
    }
    check("coins reach the cap eventually", MilestoneMath.coins(COINS, 5000) == COINS.cap)
    check("cap below base refused", CoinScaling.orNull(100.0, 0.25, 0.6, 50.0) == null)

    // ── Special / major / announced tiers ───────────────────
    val specials = (1..300).filter { MilestoneMath.isSpecial(it) }
    check("special tiers: 5, 10, 25, 50, 75, 100, then every 25", specials.take(9) == listOf(5, 10, 25, 50, 75, 100, 125, 150, 175))
    check("no special tiers besides those", specials.all { it == 5 || it == 10 || it % 25 == 0 })
    check("major tiers are every 100", (1..1000).filter { MilestoneMath.isMajor(it) } == listOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 1000))
    val announced = (1..500).filter { MilestoneMath.isAnnounced(it) }
    check("announced: 25, 50, 100, 200, 300, ...", announced == listOf(25, 50, 100, 200, 300, 400, 500))
    check("ordinary tiers aren't announced", !MilestoneMath.isAnnounced(1) && !MilestoneMath.isAnnounced(75) && !MilestoneMath.isAnnounced(150))
    check("next special tiers after 7", MilestoneMath.nextSpecialTiers(7, 4) == listOf(10, 25, 50, 75))
    check("next special tiers after 100", MilestoneMath.nextSpecialTiers(100, 3) == listOf(125, 150, 175))

    // ── Rewards ─────────────────────────────────────────────
    check("coins reward parses", MilestoneRewards.parse("coins 5000", AllExist).getOrNull()?.amount == 5000.0)
    check("cratekey parses with quantity", MilestoneRewards.parse("cratekey rare 2", AllExist).getOrNull()?.quantity == 2)
    check("unknown crate refused (never faked)", MilestoneRewards.parse("cratekey legendary 1", AllExist).isFailure)
    check("unknown chat tag refused", MilestoneRewards.parse("chattag nope", AllExist).isFailure)
    check("unknown item refused", MilestoneRewards.parse("item nope 1", AllExist).isFailure)
    check("unknown reward type refused", MilestoneRewards.parse("rank vip", AllExist).isFailure)
    check("negative coins refused", MilestoneRewards.parse("coins -5", AllExist).isFailure)
    check("too many credits refused", MilestoneRewards.parse("credits 5000", AllExist).isFailure)
    check("quantity above 64 refused", MilestoneRewards.parse("cratekey common 65", AllExist).isFailure)
    val catalog = testCatalog()
    check("tier 5 gets its explicit reward", catalog.rewardsFor("farming:wheat", 5)!!.totalCoins == MilestoneMath.coins(COINS, 5) + 5000.0)
    check("tier 7 has no special reward", catalog.rewardsFor("farming:wheat", 7)!!.specials.isEmpty())
    check("tier 125 gets every-25", catalog.rewardsFor("farming:wheat", 125)!!.physical.single().param == "rare")
    check("tier 200 gets the major reward", catalog.rewardsFor("farming:wheat", 200)!!.totalCredits == 5.0)
    check("category levels have their own coins", catalog.rewardsFor("level:farming", 1)!!.coins == 25_000.0)

    // ── Book: new players, tiers, pauses ────────────────────
    val book = MilestoneBook(catalog)
    check("new player: tier 1", book.tier("farming:wheat") == 1 && book.tier("level:farming") == 1)
    check("new player: zero progress", book.progress("farming:wheat") == 0L && !book.isStarted("farming:wheat"))
    check("new player: nothing claimable", book.claimable(MilestoneCategory.FARMING).isEmpty())
    var r = book.record(MilestoneCategory.FARMING, setOf("WHEAT"), 9 * S)
    check("activity counts", r.counted && r.completed.isEmpty() && book.progress("farming:wheat") == 9 * S)
    check("level gets the same activity", book.progress("level:farming") == 9 * S)
    check("other objectives untouched", book.progress("farming:cane") == 0L)
    r = book.record(MilestoneCategory.FARMING, setOf("WHEAT"), 5 * S)
    check("completion reported once", r.completed == listOf("farming:wheat"))
    check("progress stops at the requirement", book.progress("farming:wheat") == 10 * S && book.isClaimable("farming:wheat"))
    r = book.record(MilestoneCategory.FARMING, setOf("WHEAT"), 3 * S)
    check("paused objective doesn't move", book.progress("farming:wheat") == 10 * S && r.completed.isEmpty())
    check("level still counts while the objective waits", book.progress("level:farming") == 17 * S)
    book.record(MilestoneCategory.FARMING, setOf("SUGAR_CANE"), 4 * S)
    check("other objectives keep tracking", book.progress("farming:cane") == 4 * S)
    check("tier 2 not active before the claim", book.tier("farming:wheat") == 1)
    check("unmatched tokens count nowhere", !book.record(MilestoneCategory.FARMING, setOf("DIRT"), S).counted && book.progress("level:farming") == 21 * S)
    check("disabled categories count nowhere", !book.record(MilestoneCategory.MINING, setOf("STONE"), S).counted)
    check("zero/negative amounts ignored", !book.record(MilestoneCategory.FARMING, setOf("WHEAT"), 0).counted && !book.record(MilestoneCategory.FARMING, setOf("WHEAT"), -5).counted)
    // Level completes and pauses on its own.
    book.record(MilestoneCategory.FARMING, setOf("SUGAR_CANE"), 100 * S)
    check("level completes at its own requirement", book.isClaimable("level:farming") && book.progress("level:farming") == 50 * S)
    book.record(MilestoneCategory.FARMING, setOf("SUGAR_CANE"), 5 * S)
    check("paused level doesn't move", book.progress("level:farming") == 50 * S)
    check("claimable: level first", book.claimable(MilestoneCategory.FARMING) == listOf("level:farming", "farming:wheat", "farming:cane"))
    // Claim: next tier, progress 0, higher requirement.
    check("claim applies to the claimed tier", book.applyClaim("farming:wheat", 1))
    check("claim → tier 2", book.tier("farming:wheat") == 2 && book.progress("farming:wheat") == 0L)
    check("tier 2 needs more", book.required("farming:wheat") > 10 * S)
    check("stale claim refused", !book.applyClaim("farming:wheat", 1))
    // Infinite advancement.
    for (tier in 2..300) {
        book.record(MilestoneCategory.FARMING, setOf("WHEAT"), book.required("farming:wheat"))
        check("tier $tier completes", book.isClaimable("farming:wheat"))
        check("tier $tier claims", book.applyClaim("farming:wheat", tier))
    }
    check("advanced to tier 301", book.tier("farming:wheat") == 301)
    // Sort order: claimable first, then started by %, then unstarted.
    val sortBook = MilestoneBook(catalog)
    sortBook.record(MilestoneCategory.COMBAT, setOf("PLAYER"), 3 * S) // players complete
    sortBook.record(MilestoneCategory.COMBAT, setOf("ZOMBIE"), 1 * S) // zombies 20%
    check("sort: complete first", sortBook.sortedObjectives(MilestoneCategory.COMBAT).map { it.id } == listOf("players", "zombies"))
    val sortBook2 = MilestoneBook(catalog)
    sortBook2.record(MilestoneCategory.EXPLORATION, setOf("NETHER"), 10 * S)
    check("sort: started before unstarted", sortBook2.sortedObjectives(MilestoneCategory.EXPLORATION).map { it.id } == listOf("nether", "walking"))
    // Exploration: one movement credits several objectives and the level once.
    val explore = MilestoneBook(catalog)
    explore.record(MilestoneCategory.EXPLORATION, setOf("WALK", "NETHER"), 7 * S)
    check("several objectives per activity", explore.progress("exploration:walking") == 7 * S && explore.progress("exploration:nether") == 7 * S)
    check("level credited once", explore.progress("level:exploration") == 7 * S)
    // Corrupted/foreign rows load gracefully.
    val loaded = MilestoneBook(catalog)
    loaded.load(mapOf("farming:wheat" to (0 to -50L), "farming:gone" to (3 to 10L)))
    check("bad tier clamps to 1", loaded.tier("farming:wheat") == 1 && loaded.progress("farming:wheat") == 0L)
    check("unknown key never claimable", !loaded.isClaimable("farming:gone"))
    // Dirty tracking.
    val dirtyBook = MilestoneBook(catalog)
    dirtyBook.record(MilestoneCategory.FARMING, setOf("WHEAT"), S)
    val dirty = dirtyBook.drainDirty()
    check("dirty rows: objective + level", dirty.map { it.key }.toSet() == setOf("farming:wheat", "level:farming"))
    check("drain clears", !dirtyBook.hasDirty)

    // ── Spawner mobs: exact fractions ───────────────────────
    for (n in 1..16) check("1/$n kill is exact", MilestoneMath.spawnerKillScaled(n) * n == S)
    val spawner = MilestoneBook(catalog)
    repeat(4) { spawner.record(MilestoneCategory.COMBAT, setOf("ZOMBIE"), MilestoneMath.spawnerKillScaled(5)) }
    check("4 spawner kills < 1 kill", MilestoneMath.units(spawner.progress("combat:zombies")) == 0L)
    spawner.record(MilestoneCategory.COMBAT, setOf("ZOMBIE"), MilestoneMath.spawnerKillScaled(5))
    check("5 spawner kills = 1 kill exactly", spawner.progress("combat:zombies") == S)
    check("spawn reasons", CombatRules.weight("NATURAL", setOf("NATURAL"), setOf("SPAWNER")) == CombatRules.Weight.FULL &&
        CombatRules.weight("SPAWNER", setOf("NATURAL"), setOf("SPAWNER")) == CombatRules.Weight.REDUCED &&
        CombatRules.weight("SPAWNER_EGG", setOf("NATURAL"), setOf("SPAWNER")) == CombatRules.Weight.NONE &&
        CombatRules.weight("COMMAND", setOf("NATURAL"), setOf("SPAWNER")) == CombatRules.Weight.NONE)

    // ── PvP ─────────────────────────────────────────────────
    fun pvp(same: Boolean = false, team: Boolean = false, idle: Boolean = false, last: Long? = null, now: Long = 10_000_000L, hour: Int = 0) =
        CombatRules.pvp(same, team, idle, last, now, 30 * 60_000L, hour, 10)
    check("fresh kill counts", pvp() == CombatRules.PvpDecision.COUNT)
    check("self never", pvp(same = true) == CombatRules.PvpDecision.SELF)
    check("teammate never", pvp(team = true) == CombatRules.PvpDecision.TEAMMATE)
    check("idle victim never", pvp(idle = true) == CombatRules.PvpDecision.VICTIM_IDLE)
    check("same victim within the cooldown", pvp(last = 10_000_000L - 60_000L) == CombatRules.PvpDecision.COOLDOWN)
    check("same victim after the cooldown", pvp(last = 10_000_000L - 31 * 60_000L) == CombatRules.PvpDecision.COUNT)
    check("hourly cap", pvp(hour = 10) == CombatRules.PvpDecision.HOURLY_CAP)
    check("shared connection never", CombatRules.connection(true) == CombatRules.PvpDecision.SHARED_CONNECTION)
    check("unknown connection never", CombatRules.connection(null) == CombatRules.PvpDecision.UNKNOWN_CONNECTION)
    check("clean connection counts", CombatRules.connection(false) == CombatRules.PvpDecision.COUNT)
    val window = RateWindow(60_000L, 3)
    check("rate window allows up to max", window.tryAcquire(0) && window.tryAcquire(1) && window.tryAcquire(2))
    check("rate window refuses past max", !window.tryAcquire(3))
    check("rate window frees after the window", window.tryAcquire(60_001))

    // ── Farming / mining rules ──────────────────────────────
    check("wheat counts only grown", FarmingRules.countsBreak("WHEAT", 7, 7, null, true) && !FarmingRules.countsBreak("WHEAT", 6, 7, null, false))
    check("placed seeds then broken never count", !FarmingRules.countsBreak("CARROTS", 0, 7, null, true))
    check("cane: natural counts, placed never", FarmingRules.countsBreak("SUGAR_CANE", 0, 15, null, false) && !FarmingRules.countsBreak("SUGAR_CANE", 0, 15, null, true))
    check("melon: placed never", !FarmingRules.countsBreak("MELON", null, null, null, true) && FarmingRules.countsBreak("MELON", null, null, null, false))
    check("berry bush needs berries", FarmingRules.countsBreak("SWEET_BERRY_BUSH", 2, 3, null, true) && !FarmingRules.countsBreak("SWEET_BERRY_BUSH", 1, 3, null, false))
    check("cave vines need berries", FarmingRules.countsBreak("CAVE_VINES_PLANT", null, null, true, false) && !FarmingRules.countsBreak("CAVE_VINES", 3, 25, false, false))
    check("right-click harvests", FarmingRules.countsRightClickHarvest("SWEET_BERRY_BUSH") && FarmingRules.countsRightClickHarvest("CAVE_VINES") && !FarmingRules.countsRightClickHarvest("WHEAT"))
    check("tokens fold plant halves", FarmingRules.token("KELP_PLANT") == "KELP" && FarmingRules.token("CHORUS_FLOWER") == "CHORUS" && FarmingRules.token("CAVE_VINES_PLANT") == "GLOW_BERRIES")
    check("non-crops never count", !FarmingRules.countsBreak("DIRT", null, null, null, false))
    check("placement matters for column/block crops only", FarmingRules.placementMatters("CACTUS") && FarmingRules.placementMatters("PUMPKIN") && !FarmingRules.placementMatters("WHEAT"))
    check("natural ore + right tool counts", MiningRules.countsBreak(playerPlaced = false, correctTool = true))
    check("placed ore never counts", !MiningRules.countsBreak(playerPlaced = true, correctTool = true))
    check("wrong tool never counts", !MiningRules.countsBreak(playerPlaced = false, correctTool = false))
    check("generator blocks are remembered", MiningRules.GENERATED.containsAll(listOf("STONE", "COBBLESTONE", "BASALT")))
    for ((x, y, z) in listOf(Triple(0, -64, 0), Triple(15, 319, 15), Triple(7, 0, 3), Triple(-1, 100, -16), Triple(31, -2048, 47))) {
        val p = PlacedBlockCodec.pack(x, y, z)
        check("codec round trip ($x,$y,$z)", PlacedBlockCodec.x(p) == (x and 15) && PlacedBlockCodec.z(p) == (z and 15) && PlacedBlockCodec.y(p) == y)
    }
    check("codec separates heights", PlacedBlockCodec.pack(1, 64, 1) != PlacedBlockCodec.pack(1, 65, 1))

    // ── Fishing rules ───────────────────────────────────────
    check("fish class", FishingRules.classify("COD", false) == FishingRules.CatchClass.FISH && FishingRules.classify("PUFFERFISH", false) == FishingRules.CatchClass.FISH)
    check("treasure class", FishingRules.classify("ENCHANTED_BOOK", true) == FishingRules.CatchClass.TREASURE &&
        FishingRules.classify("NAME_TAG", false) == FishingRules.CatchClass.TREASURE && FishingRules.classify("FISHING_ROD", true) == FishingRules.CatchClass.TREASURE)
    check("junk class", FishingRules.classify("FISHING_ROD", false) == FishingRules.CatchClass.JUNK && FishingRules.classify("BOWL", false) == FishingRules.CatchClass.JUNK)
    val tokens = FishingRules.tokens("SALMON", false, "frozen_river", raining = true, worldTime = 18_000)
    check("catch tokens", tokens == setOf("SALMON", "FISH", "RIVER", "RAIN", "NIGHT"))
    check("day catch has no NIGHT", "NIGHT" !in FishingRules.tokens("COD", false, "plains", false, 6_000))
    check("catch interval", FishingRules.intervalOk(null, 0, 2500) && !FishingRules.intervalOk(1000, 3000, 2500) && FishingRules.intervalOk(1000, 3500, 2500))
    val clock = ActivityClock(180_000L)
    check("never-active player is idle", clock.isIdle(0))
    clock.touch(1_000)
    check("active right after input", !clock.isIdle(100_000))
    check("idle after the timeout", clock.isIdle(181_000))

    // ── Exploration rules ───────────────────────────────────
    fun sample(dx: Double = 4.0, dz: Double = 0.0, sec: Double = 1.0, vehicle: Boolean = false, glide: Boolean = false, fly: Boolean = false,
               rip: Boolean = false, swim: Boolean = false, water: Boolean = false, sprint: Boolean = false, input: Boolean = true, idle: Boolean = false) =
        ExplorationRules.evaluate(ExplorationRules.Sample(dx, dz, sec, vehicle, glide, fly, rip, swim, water, sprint, input, idle), 10.0)
    check("walking counts", sample().reason == ExplorationRules.Reason.COUNTED && sample().movement == ExplorationRules.Movement.WALK && sample().distance == 4.0)
    check("sprinting counts as sprint", sample(sprint = true).movement == ExplorationRules.Movement.SPRINT)
    check("swimming counts as swim", sample(swim = true).movement == ExplorationRules.Movement.SWIM && sample(water = true, sprint = true).movement == ExplorationRules.Movement.SWIM)
    check("boats/minecarts/horses never", sample(vehicle = true).reason == ExplorationRules.Reason.VEHICLE)
    check("elytra never", sample(glide = true).reason == ExplorationRules.Reason.GLIDING)
    check("flight never", sample(fly = true).reason == ExplorationRules.Reason.FLYING)
    check("riptide never", sample(rip = true).reason == ExplorationRules.Reason.RIPTIDE)
    check("teleport-sized jumps never", sample(dx = 60.0).reason == ExplorationRules.Reason.TOO_FAST)
    check("pushed without input never", sample(input = false).reason == ExplorationRules.Reason.NO_INPUT)
    check("idle never", sample(idle = true).reason == ExplorationRules.Reason.IDLE)
    check("standing still is nothing", sample(dx = 0.0).reason == ExplorationRules.Reason.NO_MOVEMENT)
    check("only horizontal distance", sample(dx = 3.0, dz = 4.0).distance == 5.0)
    val loop = LoopDetector(window = 60, minDistance = 150.0, maxSpan = 12.0)
    var flagged = false
    for (i in 0 until 60) {
        val angle = i * Math.PI / 4
        if (loop.record(5 * Math.cos(angle), 5 * Math.sin(angle), 4.0)) flagged = true
    }
    check("tight circles are a loop", flagged)
    val route = LoopDetector(window = 60, minDistance = 150.0, maxSpan = 12.0)
    var routeFlagged = false
    for (i in 0 until 120) if (route.record(i * 4.0, 0.0, 4.0)) routeFlagged = true
    check("a real route (even repeated) isn't a loop", !routeFlagged)

    // ── Worlds ──────────────────────────────────────────────
    fun world(name: String = "world", survival: Boolean = true, feature: Boolean = true, allowed: Set<String> = emptySet(),
              excluded: Set<String> = setOf("spawn", "afk"), event: Boolean = false, mode: String = "SURVIVAL", staff: Boolean = false) =
        MilestoneWorlds.check(name, survival, feature, allowed, excluded, event, mode, staff)
    for (name in listOf("world", "world_nether", "world_the_end", "resource")) check("$name counts", world(name) == MilestoneWorlds.Verdict.OK)
    check("Lifesteal worlds never", world("lifesteal", survival = false) == MilestoneWorlds.Verdict.NOT_SURVIVAL)
    check("locked feature never", world(feature = false) == MilestoneWorlds.Verdict.FEATURE_LOCKED)
    check("excluded worlds never", world("Spawn") == MilestoneWorlds.Verdict.EXCLUDED_WORLD)
    check("allow-list", world("world", allowed = setOf("resource")) == MilestoneWorlds.Verdict.NOT_ALLOWED_WORLD && world("resource", allowed = setOf("resource")) == MilestoneWorlds.Verdict.OK)
    check("event world never", world(event = true) == MilestoneWorlds.Verdict.EVENT_WORLD)
    check("creative never", world(mode = "CREATIVE") == MilestoneWorlds.Verdict.GAME_MODE && world(mode = "SPECTATOR") == MilestoneWorlds.Verdict.GAME_MODE)
    check("adventure counts", world(mode = "ADVENTURE") == MilestoneWorlds.Verdict.OK)
    check("Mod Mode never", world(staff = true) == MilestoneWorlds.Verdict.STAFF_MODE)

    // ── Layouts ─────────────────────────────────────────────
    check("main menu: five categories in the middle row", MilestoneLayout.MAIN_CATEGORIES == listOf(11, 12, 13, 14, 15))
    val p15 = MilestoneLayout.categoryPage(15, 0)
    check("15 objectives: 3 rows + header + bottom = 45", p15.size == 45 && p15.slots.size == 15 && p15.pages == 1)
    check("last row centered", p15.slots.takeLast(1) == listOf(31))
    check("objective slots avoid edges and bottom row", p15.slots.all { it % 9 in 1..7 && it in 9 until p15.size - 9 })
    val p40 = MilestoneLayout.categoryPage(40, 1)
    check("pagination", p40.pages == 2 && p40.from == 28 && p40.to == 40 && p40.slots.size == 12)
    check("requested page clamps", MilestoneLayout.categoryPage(5, 9).page == 0)
    check("bottom-row slots", MilestoneLayout.backSlot(45) == 36 && MilestoneLayout.closeSlot(45) == 40 && MilestoneLayout.actionSlot(45) == 44)
    check("pending size", MilestoneLayout.pendingSize(0) == 18 && MilestoneLayout.pendingSize(9) == 18 && MilestoneLayout.pendingSize(10) == 27 && MilestoneLayout.pendingSize(45) == 54)
    check("percent never 100 before completion", MilestoneMath.percent(999, 1000) == 99 && MilestoneMath.percent(1000, 1000) == 100)
    check("bar never full before completion", MilestoneMath.barCells(999, 1000, 20) == 19 && MilestoneMath.barCells(1000, 1000, 20) == 20)

    // ── Bundled milestones.yml through the real parser ──────
    @Suppress("UNCHECKED_CAST")
    val bundled = Yaml().load<Any>(File("src/main/resources/milestones.yml").readText()) as Map<*, *>
    val parsed = MilestoneSettingsParser.parse(MapSource(bundled, bundled), AllExist)
    check("bundled file parses cleanly: ${parsed.problems}", parsed.problems.isEmpty())
    check("five categories", parsed.catalog.categories.map { it.category } == MilestoneCategory.entries.toList())
    for (def in parsed.catalog.categories) {
        val n = def.objectives.size
        check("${def.category.id}: 10-20 objectives ($n)", n in 10..20 || (def.category == MilestoneCategory.EXPLORATION && n >= 8))
    }
    val farming = parsed.catalog.category(MilestoneCategory.FARMING)!!.objectives.flatMap { it.matches }.toSet()
    for (t in listOf("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "SUGAR_CANE", "MELON", "PUMPKIN", "COCOA", "NETHER_WART", "CACTUS", "BAMBOO", "SWEET_BERRIES", "GLOW_BERRIES")) {
        check("farming tracks $t", t in farming)
        check("$t is a token the farming rules emit", FarmingRules.token(t) == t || FarmingRules.kind(t) != null || t in setOf("SWEET_BERRIES", "GLOW_BERRIES", "CHORUS"))
    }
    val mining = parsed.catalog.tokens(MilestoneCategory.MINING)
    for (t in listOf("STONE", "DEEPSLATE", "COAL_ORE", "IRON_ORE", "DEEPSLATE_IRON_ORE", "COPPER_ORE", "GOLD_ORE", "REDSTONE_ORE", "LAPIS_ORE", "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "EMERALD_ORE", "NETHER_QUARTZ_ORE", "NETHER_GOLD_ORE", "ANCIENT_DEBRIS")) {
        check("mining tracks $t", t in mining)
    }
    check("cobblestone never a mining objective", "COBBLESTONE" !in mining)
    val ore = parsed.catalog.matching(MilestoneCategory.MINING, setOf("DEEPSLATE_IRON_ORE"))
    check("ore variants fold into one objective (no double progress)", ore.map { it.id } == listOf("iron_ore"))
    val fishing = parsed.catalog.tokens(MilestoneCategory.FISHING)
    check("fishing classes", fishing.containsAll(listOf("COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH", "TREASURE", "JUNK")))
    check("every vanilla catch counts for the Fishing Level", listOf("COD", "BOWL", "NAME_TAG").all { m ->
        parsed.catalog.matching(MilestoneCategory.FISHING, FishingRules.tokens(m, false, "plains", false, 6000)).isNotEmpty()
    })
    check("combat tracks players", "PLAYER" in parsed.catalog.tokens(MilestoneCategory.COMBAT))
    check("exploration movements", parsed.catalog.tokens(MilestoneCategory.EXPLORATION).containsAll(listOf("WALK", "SPRINT", "SWIM")))
    check("default spawner ratio 5", parsed.spawnerKillsPerUnit == 5)
    check("default PvP cooldown 30 min", parsed.pvpCooldownMillis == 30 * 60_000L)
    check("default excluded worlds", parsed.excludedWorlds == setOf("spawn", "afk"))
    check("tier 1 sugar cane", parsed.catalog.requiredScaled("farming:sugar_cane", 1) == 600 * S)
    check("default special rewards loaded", parsed.catalog.rewardsFor("farming:wheat", 100)!!.physical.single().param == "joshy")
    check("default credits stay rare (objectives: none before tier 100)", (1..99).all { parsed.catalog.rewardsFor("farming:wheat", it)!!.totalCredits == 0.0 })
    val noCrates = object : MilestoneRewards.Catalog {
        override fun crateExists(id: String) = false
        override fun chatTagExists(id: String) = false
        override fun customItemExists(id: String) = false
    }
    val missing = MilestoneSettingsParser.parse(MapSource(bundled, bundled), noCrates)
    check("missing crates are reported, not faked", missing.problems.any { "cratekey" in it } && missing.catalog.rewardsFor("farming:wheat", 10)!!.physical.isEmpty())
    val broken = mapOf("categories" to mapOf("farming" to mapOf("objectives" to mapOf("bad" to mapOf("base" to -4, "matches" to listOf("WHEAT"))))), "combat" to mapOf("spawner-kills-per-progress" to 99))
    val brokenParsed = MilestoneSettingsParser.parse(MapSource(broken, null), AllExist)
    check("bad values are problems, never a crash", brokenParsed.problems.isNotEmpty() && brokenParsed.spawnerKillsPerUnit == 5)
    check("an invalid objective is skipped", brokenParsed.catalog.category(MilestoneCategory.FARMING)?.objectives?.isEmpty() == true)

    // ── Wiring ──────────────────────────────────────────────
    check("/milestones is SURVIVAL scope", GamemodeAssignments.COMMANDS["milestones"] == GamemodeScope.SURVIVAL)
    check("milestones feature is SURVIVAL", GamemodeFeature.fromId("milestones")?.scope == GamemodeScope.SURVIVAL)
    check("milestones is Survival-only on dedicated servers", "milestones" in DedicatedServerFeatures.SURVIVAL_ONLY)
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("plugin.yml declares /milestones", Regex("""(?m)^  milestones:\n(    .*\n)*    permission: joshymc\.milestones\.use$""").containsMatchIn(pluginYml))
    check("joshymc.milestones.use defaults to true", Regex("""(?m)^  joshymc\.milestones\.use:\n    description: .*\n    default: true$""").containsMatchIn(pluginYml))
    check("features.milestones in config.yml", Regex("""(?m)^  milestones: true$""").containsMatchIn(File("src/main/resources/config.yml").readText()))

    // ── Store against SQLite ────────────────────────────────
    val dir = Files.createTempDirectory("joshymc-milestone-check").toFile()
    try {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        withDb(dir) { db ->
            val store = MilestoneStore(db)
            store.createTables()
            store.createTables() // idempotent
            check("new player has no rows", store.load(alice).isEmpty())

            // Save + load.
            store.saveProgress(alice, listOf(MilestoneStore.Row("farming:wheat", 1, 10 * S), MilestoneStore.Row("level:farming", 1, 12 * S)), 1)
            check("progress persisted", store.load(alice)["farming:wheat"] == (1 to 10 * S))

            // Claim once, pay once.
            var paid = 0
            val keys = listOf(MilestoneStore.PendingItem("Common Key", "AAAA", 2))
            val first = store.claim(alice, "farming:wheat", 1, 10_000.0, 0.0, "x", keys, 5) { paid++ }
            check("first claim succeeds", first is MilestoneStore.ClaimOutcome.Claimed && (first as MilestoneStore.ClaimOutcome.Claimed).pendingIds.size == 1)
            check("paid once", paid == 1)
            check("stored tier moves to 2 with 0 progress", store.load(alice)["farming:wheat"] == (2 to 0L))
            val again = store.claim(alice, "farming:wheat", 1, 10_000.0, 0.0, "x", keys, 6) { paid++ }
            check("same tier never twice", again == MilestoneStore.ClaimOutcome.AlreadyClaimed && paid == 1)
            check("no extra pending rows", store.pendingCount(alice) == 1)
            check("claim history", store.hasClaimed(alice, "farming:wheat", 1) && !store.hasClaimed(alice, "farming:wheat", 2))

            // A late save of the old tier never undoes the claim.
            store.saveProgress(alice, listOf(MilestoneStore.Row("farming:wheat", 1, 10 * S)), 7)
            check("stale save ignored", store.load(alice)["farming:wheat"] == (2 to 0L))
            store.saveProgress(alice, listOf(MilestoneStore.Row("farming:wheat", 2, 3 * S)), 8)
            check("current-tier save lands", store.load(alice)["farming:wheat"] == (2 to 3 * S))

            // Stale claim (stored tier moved on) pays nothing and leaves no claim row.
            store.saveProgress(alice, listOf(MilestoneStore.Row("level:farming", 1, 50 * S)), 9)
            db.execute("UPDATE milestone_progress SET tier = 4 WHERE uuid = ? AND track_key = 'level:farming'", alice.toString())
            val stale = store.claim(alice, "level:farming", 1, 25_000.0, 2.0, "x", emptyList(), 10) { paid++ }
            check("stale claim refused", stale == MilestoneStore.ClaimOutcome.Stale && paid == 1)
            check("stale claim rolled back its claim row", !store.hasClaimed(alice, "level:farming", 1))

            // A failing payment rolls the whole claim back.
            store.saveProgress(alice, listOf(MilestoneStore.Row("combat:zombies", 1, 5 * S)), 11)
            val failed = runCatching { store.claim(alice, "combat:zombies", 1, 1.0, 0.0, "x", keys, 12) { error("deposit failed") } }
            check("payment failure throws", failed.isFailure)
            check("payment failure: no claim row", !store.hasClaimed(alice, "combat:zombies", 1))
            check("payment failure: tier unchanged", store.load(alice)["combat:zombies"] == (1 to 5 * S))
            check("payment failure: no pending rows", store.pendingCount(alice) == 1)
            check("retry after failure works", store.claim(alice, "combat:zombies", 1, 1.0, 0.0, "x", emptyList(), 13) { } is MilestoneStore.ClaimOutcome.Claimed)

            // Claim All = several independent claims.
            store.saveProgress(bob, listOf(MilestoneStore.Row("farming:wheat", 1, 10 * S), MilestoneStore.Row("farming:cane", 1, 20 * S), MilestoneStore.Row("level:farming", 1, 50 * S)), 14)
            val all = listOf("level:farming", "farming:wheat", "farming:cane").map { store.claim(bob, it, 1, 1.0, 0.0, "x", emptyList(), 15) { } }
            check("claim all claims each once", all.all { it is MilestoneStore.ClaimOutcome.Claimed } && store.claimCount(bob) == 3)
            check("claim all moves each to tier 2", store.load(bob).values.all { it == (2 to 0L) })

            // Pending: taken once.
            val id = store.listPending(alice, 0, 45).single().id
            check("pending row readable", store.pending(alice, id)?.amount == 2)
            check("other players can't take it", store.takePending(bob, id) == null)
            val taken = store.takePending(alice, id)
            check("taken once", taken != null && store.takePending(alice, id) == null && store.pendingCount(alice) == 0)
            store.addPending(alice, "farming:wheat", 1, MilestoneStore.PendingItem("Common Key", "BBBB", 1), 16)
            store.addPending(alice, "level:farming", 5, MilestoneStore.PendingItem("Rare Key", "CCCC", 3), 17)

            // PvP cooldowns.
            store.recordPvpKill(alice, bob, 1_000)
            store.recordPvpKill(alice, bob, 2_000)
            check("one row per pair, latest time", store.pvpKillsSince(0) == listOf(Triple(alice, bob, 2_000L)))
            check("prune", store.prunePvpKills(5_000) == 1 && store.pvpKillsSince(0).isEmpty())
            check("connection check without AltManager's table is unknown", store.sharesConnection(alice, bob) == null)
            db.createTable("CREATE TABLE IF NOT EXISTS player_connections (uuid TEXT NOT NULL, connection_hash TEXT NOT NULL, last_seen INTEGER NOT NULL, PRIMARY KEY (uuid, connection_hash))")
            check("no shared connection", store.sharesConnection(alice, bob) == false)
            db.execute("INSERT INTO player_connections VALUES (?, 'h1', 1)", alice.toString())
            db.execute("INSERT INTO player_connections VALUES (?, 'h1', 1)", bob.toString())
            check("shared connection found", store.sharesConnection(alice, bob) == true)
        }

        // Restart: everything is still there.
        withDb(dir) { db ->
            val store = MilestoneStore(db)
            store.createTables()
            check("progress survives a restart", store.load(alice)["farming:wheat"] == (2 to 3 * S))
            check("claims survive a restart", store.hasClaimed(alice, "farming:wheat", 1))
            check("pending rewards survive a restart", store.pendingCount(alice) == 2 && store.listPending(alice, 0, 45).map { it.data } == listOf("BBBB", "CCCC"))
            check("still claim-once after a restart", store.claim(alice, "farming:wheat", 1, 1.0, 0.0, "x", emptyList(), 20) { } == MilestoneStore.ClaimOutcome.AlreadyClaimed)
            // Reload behavior: a book rebuilt from the store continues exactly where it was.
            val reloaded = MilestoneBook(catalog).also { it.load(store.load(alice)) }
            check("reloaded book tier", reloaded.tier("farming:wheat") == 2 && reloaded.progress("farming:wheat") == 3 * S)
            // Resource world resets never touch milestone rows (they hold no world at all).
            check("milestone rows are world-independent", db.query("PRAGMA table_info(milestone_progress)") { it.getString("name") }.none { it.contains("world") })
        }
    } finally {
        dir.deleteRecursively()
    }

    println("Milestone checks passed ($checks checks).")
}
