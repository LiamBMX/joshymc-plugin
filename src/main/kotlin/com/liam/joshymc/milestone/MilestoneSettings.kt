package com.liam.joshymc.milestone

import java.util.Locale

/** Every Milestones setting (issue #1420) after validation; [problems] lists what fell back to a default. */
data class MilestoneSettings(
    val enabled: Boolean,
    val catalog: MilestoneCatalog,
    val allowedWorlds: Set<String>,
    val excludedWorlds: Set<String>,
    val idleMillis: Long,
    val requireCorrectTool: Boolean,
    val fishingMinIntervalMillis: Long,
    val spawnerKillsPerUnit: Int,
    val fullSpawnReasons: Set<String>,
    val reducedSpawnReasons: Set<String>,
    val maxMobKillsPerMinute: Int,
    val pvpEnabled: Boolean,
    val pvpCooldownMillis: Long,
    val pvpMaxPerHour: Int,
    val pvpBlockSharedConnections: Boolean,
    val sampleSeconds: Int,
    val maxBlocksPerSecond: Double,
    val loopWindow: Int,
    val loopMinDistance: Double,
    val loopMaxSpan: Double,
    val announcementsEnabled: Boolean,
    val broadcastMessage: String,
    val completedMessage: String,
    val claimedMessage: String,
    val mainTitle: String,
    val categoryTitle: String,
    val pendingTitle: String,
    val infoLines: List<String>,
    val problems: List<String>,
)

/**
 * Plain view of the YAML file, so [MilestoneSettingsParser] stays Bukkit-free (the manager wraps
 * a YamlConfiguration; the checks use a map).
 */
interface MilestoneSource {
    fun isSet(path: String): Boolean
    fun get(path: String): Any?
    /** Direct child keys of the section at [path] (empty when it isn't a section). */
    fun keys(path: String): List<String>
}

object MilestoneSettingsParser {

    private val ID = Regex("^[a-z0-9_]{1,32}$")

    fun parse(source: MilestoneSource, catalog: MilestoneRewards.Catalog): MilestoneSettings {
        val problems = ArrayList<String>()

        fun bool(path: String, def: Boolean): Boolean = when (val v = source.get(path)) {
            null -> def
            is Boolean -> v
            else -> { problems += "$path: '$v' isn't true/false, using $def"; def }
        }
        fun num(path: String, def: Double, min: Double, max: Double): Double {
            val v = source.get(path) ?: return def
            val d = (v as? Number)?.toDouble() ?: v.toString().toDoubleOrNull()
            if (d == null || !d.isFinite() || d < min || d > max) {
                problems += "$path: '$v' must be a number from $min to $max, using $def"
                return def
            }
            return d
        }
        fun int(path: String, def: Int, min: Int, max: Int): Int = num(path, def.toDouble(), min.toDouble(), max.toDouble()).toInt()
        fun str(path: String, def: String): String = source.get(path)?.toString() ?: def
        fun list(path: String): List<String>? = (source.get(path) as? List<*>)?.mapNotNull { it?.toString() }
        fun names(path: String, def: Set<String>): Set<String> =
            list(path)?.map { it.trim().uppercase(Locale.ROOT) }?.filter { it.isNotEmpty() }?.toSet() ?: def

        fun scaling(path: String, def: MilestoneScaling): MilestoneScaling {
            if (!source.isSet(path)) return def
            val growth = num("$path.growth", def.growth, 0.0, MilestoneScaling.MAX_GROWTH)
            val exponent = num("$path.exponent", def.exponent, 0.0001, 1.0)
            return MilestoneScaling.orNull(growth, exponent) ?: def.also { problems += "$path: invalid scaling, using the default" }
        }
        fun coins(path: String, def: CoinScaling): CoinScaling {
            val base = num("$path.base", def.base, 0.0, MilestoneMath.MAX_COINS)
            val growth = num("$path.growth", def.growth, 0.0, MilestoneScaling.MAX_GROWTH)
            val exponent = num("$path.exponent", def.exponent, 0.0001, 1.0)
            val cap = num("$path.cap", def.cap, 0.0, MilestoneMath.MAX_COINS)
            return CoinScaling.orNull(base, growth, exponent, cap)
                ?: def.also { problems += "$path: the cap must be at least the base, using the defaults" }
        }
        fun rewardList(path: String): List<MilestoneRewards.Spec> {
            val raw = source.get(path) ?: return emptyList()
            val lines = (raw as? List<*>)?.mapNotNull { it?.toString() } ?: listOf(raw.toString())
            return lines.mapNotNull { line ->
                MilestoneRewards.parse(line, catalog).getOrElse { e ->
                    problems += "$path: '$line' skipped (${e.message})"
                    null
                }
            }
        }
        fun table(path: String): MilestoneRewards.Table {
            val explicit = HashMap<Int, List<MilestoneRewards.Spec>>()
            var every25 = emptyList<MilestoneRewards.Spec>()
            var every100 = emptyList<MilestoneRewards.Spec>()
            for (key in source.keys(path)) {
                when (key) {
                    "every-25" -> every25 = rewardList("$path.$key")
                    "every-100" -> every100 = rewardList("$path.$key")
                    else -> {
                        val tier = key.toIntOrNull()
                        if (tier == null || tier !in 1..MilestoneMath.MAX_TIER) {
                            problems += "$path.$key: not a tier number (or every-25 / every-100), skipped"
                        } else {
                            explicit[tier] = rewardList("$path.$key")
                        }
                    }
                }
            }
            return MilestoneRewards.Table(explicit, every25, every100)
        }

        val objectiveCoins = coins("rewards.objective-coins", CoinScaling(10_000.0, 0.25, 0.6, 75_000.0))
        val levelCoins = coins("rewards.level-coins", CoinScaling(25_000.0, 0.25, 0.6, 200_000.0))

        val categories = ArrayList<CategoryDef>()
        for (category in MilestoneCategory.entries) {
            val path = "categories.${category.id}"
            if (!source.isSet(path)) {
                problems += "$path missing: category disabled"
                continue
            }
            if (!bool("$path.enabled", true)) continue
            val catScaling = scaling("$path.scaling", MilestoneScaling.DEFAULT)
            val levelScaling = scaling("$path.level", MilestoneScaling(0.3, 0.6))
            val levelBase = num("$path.level.base", 1000.0, 1.0, MilestoneMath.MAX_REQUIREMENT.toDouble()).toLong()
            val objectives = ArrayList<ObjectiveDef>()
            for (id in source.keys("$path.objectives")) {
                val op = "$path.objectives.$id"
                if (!ID.matches(id)) {
                    problems += "$op: ids are lower-case letters, digits and _ (skipped)"
                    continue
                }
                if (!bool("$op.enabled", true)) continue
                val matches = names("$op.matches", emptySet())
                if (matches.isEmpty()) {
                    problems += "$op: no matches, skipped"
                    continue
                }
                val baseRaw = source.get("$op.base")
                val base = (baseRaw as? Number)?.toLong() ?: baseRaw?.toString()?.toLongOrNull()
                if (base == null || base < 1 || base > MilestoneMath.MAX_REQUIREMENT) {
                    problems += "$op.base: '$baseRaw' must be a whole number from 1 to ${MilestoneMath.MAX_REQUIREMENT}, skipped"
                    continue
                }
                val objScaling = if (source.isSet("$op.growth") || source.isSet("$op.exponent")) {
                    MilestoneScaling.orNull(
                        num("$op.growth", catScaling.growth, 0.0, MilestoneScaling.MAX_GROWTH),
                        num("$op.exponent", catScaling.exponent, 0.0001, 1.0)
                    ) ?: catScaling
                } else catScaling
                val objCoins = if (source.isSet("$op.coins-base")) {
                    val b = num("$op.coins-base", objectiveCoins.base, 0.0, MilestoneMath.MAX_COINS)
                    CoinScaling.orNull(b, objectiveCoins.growth, objectiveCoins.exponent, maxOf(objectiveCoins.cap, b)) ?: objectiveCoins
                } else objectiveCoins
                objectives += ObjectiveDef(
                    id = id,
                    category = category,
                    name = str("$op.name", id),
                    icon = str("$op.icon", "PAPER").uppercase(Locale.ROOT),
                    unit = str("$path.unit", ""),
                    matches = matches,
                    base = base,
                    scaling = objScaling,
                    coins = objCoins,
                )
            }
            categories += CategoryDef(
                category = category,
                name = str("$path.name", category.defaultName),
                icon = str("$path.icon", "PAPER").uppercase(Locale.ROOT),
                levelBase = levelBase,
                levelScaling = levelScaling,
                levelCoins = levelCoins,
                objectives = objectives,
            )
        }

        val catalogOut = MilestoneCatalog(categories, table("rewards.objective-special"), table("rewards.level-special"))

        val spawnerKills = int("combat.spawner-kills-per-progress", 5, 1, 16)
        return MilestoneSettings(
            enabled = bool("enabled", true),
            catalog = catalogOut,
            allowedWorlds = list("worlds.allowed")?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
            excludedWorlds = list("worlds.excluded")?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.isNotEmpty() }?.toSet()
                ?: setOf("spawn", "afk"),
            idleMillis = (num("anti-afk.idle-seconds", 180.0, 10.0, 3600.0) * 1000).toLong(),
            requireCorrectTool = bool("mining.require-correct-tool", true),
            fishingMinIntervalMillis = (num("fishing.min-seconds-between-catches", 2.5, 0.0, 60.0) * 1000).toLong(),
            spawnerKillsPerUnit = spawnerKills,
            fullSpawnReasons = names(
                "combat.full-spawn-reasons",
                setOf("NATURAL", "CHUNK_GEN", "JOCKEY", "MOUNT", "PATROL", "RAID", "REINFORCEMENTS", "VILLAGE_INVASION", "SLIME_SPLIT", "SILVERFISH_BLOCK")
            ),
            reducedSpawnReasons = names("combat.reduced-spawn-reasons", setOf("SPAWNER", "TRIAL_SPAWNER")),
            maxMobKillsPerMinute = int("combat.max-mob-kills-per-minute", 90, 0, 10_000),
            pvpEnabled = bool("combat.pvp.enabled", true),
            pvpCooldownMillis = (num("combat.pvp.same-victim-cooldown-minutes", 30.0, 0.0, 10_080.0) * 60_000).toLong(),
            pvpMaxPerHour = int("combat.pvp.max-kills-per-hour", 10, 0, 1_000),
            pvpBlockSharedConnections = bool("combat.pvp.block-shared-connections", true),
            sampleSeconds = int("exploration.sample-seconds", 1, 1, 5),
            maxBlocksPerSecond = num("exploration.max-blocks-per-second", 10.0, 1.0, 50.0),
            loopWindow = int("exploration.loop-detection.window-seconds", 60, 10, 600),
            loopMinDistance = num("exploration.loop-detection.min-distance", 150.0, 1.0, 100_000.0),
            loopMaxSpan = num("exploration.loop-detection.max-span", 12.0, 0.0, 1_000.0),
            announcementsEnabled = bool("announcements.enabled", true),
            broadcastMessage = str("announcements.broadcast", "&6&l★ MILESTONE &e{player} &7reached &6{milestone} &7Tier &6{tier}&7!"),
            completedMessage = str("announcements.completed", "&aMilestone complete: &f{milestone} &aTier {tier}! &7Claim it in &e/milestones&7."),
            claimedMessage = str("announcements.claimed", "&aClaimed &f{milestone} &aTier {tier}: &6{rewards}"),
            mainTitle = str("gui.main-title", "Milestones"),
            categoryTitle = str("gui.category-title", "{category} Milestones"),
            pendingTitle = str("gui.pending-title", "Pending Rewards"),
            infoLines = list("gui.info") ?: emptyList(),
            problems = problems,
        )
    }
}
