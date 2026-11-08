package com.liam.joshymc.milestone

import java.util.ArrayDeque
import java.util.Locale

/**
 * Bukkit-free eligibility rules for Milestones (issue #1420). Listeners read the facts from the
 * game (names, ages, flags) and these decide; `scripts/MilestoneCheck.kt` checks them directly.
 */
object MilestoneWorlds {

    enum class Verdict { OK, NOT_SURVIVAL, EXCLUDED_WORLD, NOT_ALLOWED_WORLD, EVENT_WORLD, GAME_MODE, STAFF_MODE, FEATURE_LOCKED }

    /**
     * Whether activity by a player counts. [worldGamemodeSurvival] = the repository's gamemode lock
     * maps the world to Survival (Lifesteal worlds never count). [allowedWorlds] empty = every Survival
     * world except [excludedWorlds]. Creative and Spectator never count; neither do Mod Mode staff or
     * anyone in the event world.
     */
    fun check(
        worldName: String,
        worldGamemodeSurvival: Boolean,
        featureAllowed: Boolean,
        allowedWorlds: Set<String>,
        excludedWorlds: Set<String>,
        inEventWorld: Boolean,
        playerGameMode: String,
        staffMode: Boolean,
    ): Verdict {
        val name = worldName.lowercase(Locale.ROOT)
        return when {
            !worldGamemodeSurvival -> Verdict.NOT_SURVIVAL
            !featureAllowed -> Verdict.FEATURE_LOCKED
            inEventWorld -> Verdict.EVENT_WORLD
            name in excludedWorlds -> Verdict.EXCLUDED_WORLD
            allowedWorlds.isNotEmpty() && name !in allowedWorlds -> Verdict.NOT_ALLOWED_WORLD
            playerGameMode != "SURVIVAL" && playerGameMode != "ADVENTURE" -> Verdict.GAME_MODE
            staffMode -> Verdict.STAFF_MODE
            else -> Verdict.OK
        }
    }
}

object FarmingRules {

    enum class Kind {
        /** Counts only fully grown (age == max). Bone meal growth is fine. */
        AGE,
        /** Sugar cane, cactus, bamboo, kelp: every naturally grown segment counts, placed ones never. */
        COLUMN,
        /** Melons, pumpkins, chorus: counts unless the player placed that block. */
        BLOCK,
        /** Sweet berry bush: counts when it has berries (age 2+). */
        BERRY_BUSH,
        /** Cave vines: counts when it has glow berries. */
        CAVE_VINES,
    }

    private val KINDS: Map<String, Kind> = buildMap {
        for (n in listOf("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "COCOA")) put(n, Kind.AGE)
        for (n in listOf("SUGAR_CANE", "CACTUS", "BAMBOO", "KELP", "KELP_PLANT")) put(n, Kind.COLUMN)
        for (n in listOf("MELON", "PUMPKIN", "CHORUS_PLANT", "CHORUS_FLOWER")) put(n, Kind.BLOCK)
        put("SWEET_BERRY_BUSH", Kind.BERRY_BUSH)
        put("CAVE_VINES", Kind.CAVE_VINES)
        put("CAVE_VINES_PLANT", Kind.CAVE_VINES)
    }

    /** Most segments above a broken column block that count with it (they pop by physics, no break event). */
    const val COLUMN_MAX = 32

    fun kind(blockName: String): Kind? = KINDS[blockName]

    /** The token objectives are configured with. */
    fun token(blockName: String): String = when (blockName) {
        "KELP_PLANT" -> "KELP"
        "CHORUS_PLANT", "CHORUS_FLOWER" -> "CHORUS"
        "SWEET_BERRY_BUSH" -> "SWEET_BERRIES"
        "CAVE_VINES", "CAVE_VINES_PLANT" -> "GLOW_BERRIES"
        else -> blockName
    }

    /** Blocks whose player placement must be remembered (age crops can't be placed grown). */
    fun placementMatters(blockName: String): Boolean = when (kind(blockName)) {
        Kind.COLUMN, Kind.BLOCK -> true
        else -> false
    }

    /** Whether breaking this block counts as one harvest. */
    fun countsBreak(blockName: String, age: Int?, maxAge: Int?, berries: Boolean?, playerPlaced: Boolean): Boolean =
        when (kind(blockName)) {
            Kind.AGE -> age != null && maxAge != null && age >= maxAge
            Kind.COLUMN, Kind.BLOCK -> !playerPlaced
            Kind.BERRY_BUSH -> age != null && age >= 2
            Kind.CAVE_VINES -> berries == true
            null -> false
        }

    /** Whether a right-click harvest (PlayerHarvestBlockEvent) counts. */
    fun countsRightClickHarvest(blockName: String): Boolean =
        kind(blockName) == Kind.BERRY_BUSH || kind(blockName) == Kind.CAVE_VINES
}

object MiningRules {
    /** A mined block counts when it wasn't placed (or made by a generator) and was broken with the right tool. */
    fun countsBreak(playerPlaced: Boolean, correctTool: Boolean): Boolean = !playerPlaced && correctTool

    /** Blocks generators (lava + water / blue ice) make, remembered so they never count. */
    val GENERATED: Set<String> = setOf("STONE", "COBBLESTONE", "BASALT", "OBSIDIAN", "COBBLED_DEEPSLATE")
}

object FishingRules {

    enum class CatchClass(val token: String) { FISH("FISH"), TREASURE("TREASURE"), JUNK("JUNK") }

    val FISH: Set<String> = setOf("COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH")
    private val ALWAYS_TREASURE: Set<String> = setOf("ENCHANTED_BOOK", "NAME_TAG", "NAUTILUS_SHELL", "SADDLE", "BOW")

    /** Vanilla catch classes: fish, treasure (fishing rods only when enchanted), everything else junk. */
    fun classify(materialName: String, enchanted: Boolean): CatchClass = when {
        materialName in FISH -> CatchClass.FISH
        materialName in ALWAYS_TREASURE -> CatchClass.TREASURE
        materialName == "FISHING_ROD" && enchanted -> CatchClass.TREASURE
        else -> CatchClass.JUNK
    }

    fun biomeToken(biomeKey: String): String? {
        val key = biomeKey.lowercase(Locale.ROOT)
        return when {
            "ocean" in key -> "OCEAN"
            "river" in key -> "RIVER"
            "swamp" in key -> "SWAMP"
            else -> null
        }
    }

    fun isNight(worldTime: Long): Boolean = (worldTime % 24_000L) in 13_000L..22_999L

    /** Tokens of one catch: material, class, biome group, RAIN and NIGHT when they apply. */
    fun tokens(materialName: String, enchanted: Boolean, biomeKey: String, raining: Boolean, worldTime: Long): Set<String> {
        val out = linkedSetOf(materialName, classify(materialName, enchanted).token)
        biomeToken(biomeKey)?.let { out += it }
        if (raining) out += "RAIN"
        if (isNight(worldTime)) out += "NIGHT"
        return out
    }

    /** Minimum time between two counted catches of one player. */
    fun intervalOk(lastCountedAt: Long?, now: Long, minIntervalMillis: Long): Boolean =
        lastCountedAt == null || now - lastCountedAt >= minIntervalMillis
}

object CombatRules {

    enum class Weight { FULL, REDUCED, NONE }

    /** How a mob's spawn reason counts: configured full reasons, reduced (spawners), or not at all. */
    fun weight(spawnReason: String, full: Set<String>, reduced: Set<String>): Weight = when (spawnReason) {
        in reduced -> Weight.REDUCED
        in full -> Weight.FULL
        else -> Weight.NONE
    }

    enum class PvpDecision { COUNT, SELF, TEAMMATE, COOLDOWN, VICTIM_IDLE, HOURLY_CAP, SHARED_CONNECTION, UNKNOWN_CONNECTION }

    /** Order: self, teammate, idle victim, same-victim cooldown, hourly cap. The connection check runs after (async). */
    fun pvp(
        sameAccount: Boolean,
        teammates: Boolean,
        victimIdle: Boolean,
        lastKillOfVictimAt: Long?,
        now: Long,
        cooldownMillis: Long,
        killsInLastHour: Int,
        maxPerHour: Int,
    ): PvpDecision = when {
        sameAccount -> PvpDecision.SELF
        teammates -> PvpDecision.TEAMMATE
        victimIdle -> PvpDecision.VICTIM_IDLE
        lastKillOfVictimAt != null && now - lastKillOfVictimAt < cooldownMillis -> PvpDecision.COOLDOWN
        maxPerHour > 0 && killsInLastHour >= maxPerHour -> PvpDecision.HOURLY_CAP
        else -> PvpDecision.COUNT
    }

    /** Final step once the connection check answered: null = the check failed. */
    fun connection(shared: Boolean?): PvpDecision = when (shared) {
        null -> PvpDecision.UNKNOWN_CONNECTION
        true -> PvpDecision.SHARED_CONNECTION
        false -> PvpDecision.COUNT
    }
}

/** Sliding window of counted events (mob kills per minute, PvP kills per hour). Bounded by [max]. */
class RateWindow(private val windowMillis: Long, private val max: Int) {
    private val times = ArrayDeque<Long>()

    fun count(now: Long): Int {
        prune(now)
        return times.size
    }

    /** Records one event when below the limit; false (nothing recorded) when the window is full. */
    fun tryAcquire(now: Long): Boolean {
        prune(now)
        if (max <= 0) return true
        if (times.size >= max) return false
        times.addLast(now)
        return true
    }

    fun add(now: Long) {
        prune(now)
        times.addLast(now)
        while (times.size > MAX_KEPT) times.pollFirst()
    }

    private fun prune(now: Long) {
        while (times.isNotEmpty() && now - times.peekFirst() >= windowMillis) times.pollFirst()
    }

    private companion object {
        const val MAX_KEPT = 1_000
    }
}

/** Last real input per player (look changes and movement keys); idle players' activity doesn't count. */
class ActivityClock(private val idleMillis: Long) {
    var lastActiveAt: Long = Long.MIN_VALUE
        private set

    fun touch(now: Long) {
        lastActiveAt = now
    }

    fun isIdle(now: Long): Boolean = lastActiveAt == Long.MIN_VALUE || now - lastActiveAt >= idleMillis
}

object ExplorationRules {

    enum class Movement(val token: String) { WALK("WALK"), SPRINT("SPRINT"), SWIM("SWIM") }

    enum class Reason { COUNTED, NO_MOVEMENT, VEHICLE, GLIDING, FLYING, RIPTIDE, NO_INPUT, IDLE, TOO_FAST, LOOP }

    data class Sample(
        val dx: Double,
        val dz: Double,
        val seconds: Double,
        val inVehicle: Boolean,
        val gliding: Boolean,
        val flying: Boolean,
        val riptiding: Boolean,
        val swimming: Boolean,
        val inWater: Boolean,
        val sprinting: Boolean,
        /** A movement key was held at the sample or pressed since the last one. */
        val movementInput: Boolean,
        val idle: Boolean,
    )

    data class Verdict(val reason: Reason, val distance: Double, val movement: Movement?)

    /** Horizontal distance only (jumping, falling and elevators never count). */
    fun evaluate(sample: Sample, maxBlocksPerSecond: Double): Verdict {
        val distance = kotlin.math.sqrt(sample.dx * sample.dx + sample.dz * sample.dz)
        fun no(reason: Reason) = Verdict(reason, 0.0, null)
        return when {
            !distance.isFinite() || distance < 0.05 -> no(Reason.NO_MOVEMENT)
            sample.inVehicle -> no(Reason.VEHICLE)
            sample.gliding -> no(Reason.GLIDING)
            sample.flying -> no(Reason.FLYING)
            sample.riptiding -> no(Reason.RIPTIDE)
            sample.seconds <= 0.0 || distance > maxBlocksPerSecond * sample.seconds -> no(Reason.TOO_FAST)
            !sample.movementInput -> no(Reason.NO_INPUT)
            sample.idle -> no(Reason.IDLE)
            else -> Verdict(
                Reason.COUNTED,
                distance,
                when {
                    sample.swimming || sample.inWater -> Movement.SWIM
                    sample.sprinting -> Movement.SPRINT
                    else -> Movement.WALK
                }
            )
        }
    }
}

/**
 * Flags automated movement loops (a player kept running in a tiny circle by a machine or macro):
 * over the last [window] counted samples, more than [minDistance] blocks travelled inside a box
 * no wider than [maxSpan] blocks. Repeating a real route is fine: it leaves the box.
 */
class LoopDetector(private val window: Int = 60, private val minDistance: Double = 150.0, private val maxSpan: Double = 12.0) {
    private val xs = DoubleArray(window)
    private val zs = DoubleArray(window)
    private val ds = DoubleArray(window)
    private var size = 0
    private var next = 0

    /** Records a counted sample at (x, z) worth [distance]; true when the recent movement is a loop. */
    fun record(x: Double, z: Double, distance: Double): Boolean {
        xs[next] = x
        zs[next] = z
        ds[next] = distance
        next = (next + 1) % window
        if (size < window) size++
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        var total = 0.0
        for (i in 0 until size) {
            minX = minOf(minX, xs[i]); maxX = maxOf(maxX, xs[i])
            minZ = minOf(minZ, zs[i]); maxZ = maxOf(maxZ, zs[i])
            total += ds[i]
        }
        return total > minDistance && (maxX - minX) <= maxSpan && (maxZ - minZ) <= maxSpan
    }

    fun reset() {
        size = 0
        next = 0
    }
}

/** Packs chunk-relative block positions for the placed-block markers kept in chunk data. */
object PlacedBlockCodec {
    private const val Y_OFFSET = 2048

    fun pack(x: Int, y: Int, z: Int): Int = (x and 15) or ((z and 15) shl 4) or (((y + Y_OFFSET) and 0xFFF) shl 8)

    fun x(packed: Int): Int = packed and 15
    fun z(packed: Int): Int = (packed shr 4) and 15
    fun y(packed: Int): Int = ((packed shr 8) and 0xFFF) - Y_OFFSET

    /** Most markers one chunk keeps; past it the oldest go (never a crash or unbounded chunk data). */
    const val MAX_PER_CHUNK = 16_384
}
