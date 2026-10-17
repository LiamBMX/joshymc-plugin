package com.liam.joshymc.communityevent.game

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Enemy
import org.bukkit.entity.EntityType
import org.bukkit.entity.Mob

/**
 * Armor (and a melee weapon for mobs that swing one) put on Mob Waves mobs (issue #1192).
 * Every piece has a 0% drop chance, so nothing is ever farmed from it.
 */
enum class MobGear(val id: String, val armor: List<Material>, val weapon: Material?) {
    NONE("none", emptyList(), null),
    LEATHER("leather", listOf(Material.LEATHER_BOOTS, Material.LEATHER_LEGGINGS, Material.LEATHER_CHESTPLATE, Material.LEATHER_HELMET), Material.WOODEN_SWORD),
    CHAINMAIL("chainmail", listOf(Material.CHAINMAIL_BOOTS, Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_CHESTPLATE, Material.CHAINMAIL_HELMET), Material.STONE_SWORD),
    GOLDEN("golden", listOf(Material.GOLDEN_BOOTS, Material.GOLDEN_LEGGINGS, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_HELMET), Material.GOLDEN_SWORD),
    IRON("iron", listOf(Material.IRON_BOOTS, Material.IRON_LEGGINGS, Material.IRON_CHESTPLATE, Material.IRON_HELMET), Material.IRON_SWORD),
    DIAMOND("diamond", listOf(Material.DIAMOND_BOOTS, Material.DIAMOND_LEGGINGS, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_HELMET), Material.DIAMOND_SWORD),
    NETHERITE("netherite", listOf(Material.NETHERITE_BOOTS, Material.NETHERITE_LEGGINGS, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_HELMET), Material.NETHERITE_SWORD);

    companion object {
        val ids: List<String> = entries.map { it.id }
        fun fromId(id: String): MobGear? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** One kind of mob in a wave: [count] of [type], with optional per-group modifiers. A [boss] gets a name, a glow and a boss bar line. */
data class MobGroup(
    val type: EntityType,
    val count: Int,
    val name: String?,
    val healthMultiplier: Double,
    val damageMultiplier: Double,
    /** Null = the wave's gear. */
    val gear: MobGear?,
    val boss: Boolean,
)

/** One configured wave. [spawnPoints] are 1-based Mob Spawn Point numbers; empty = all of them. */
data class WaveDef(
    val number: Int,
    val name: String?,
    val groups: List<MobGroup>,
    val spawnPoints: List<Int>,
    val healthMultiplier: Double,
    val damageMultiplier: Double,
    val speedMultiplier: Double,
    val gear: MobGear,
    val spawnIntervalTicks: Int,
    val batchSize: Int,
) {
    val total: Int get() = groups.sumOf { it.count }
    val hasBoss: Boolean get() = groups.any { it.boss }

    /** "Wave 3" or "Wave 5: Boss Wave". */
    fun title(): String = if (name.isNullOrBlank()) "Wave $number" else "Wave $number: $name"
}

/**
 * `hosted-events.mobs` in config.yml (issue #1192). Read when Mob Waves starts (and for
 * `/event mobs status`), never during combat. Every value is clamped to a safe range, and a
 * wave that names an unknown or disallowed mob, or asks for more mobs than the caps allow,
 * becomes a [problems] entry: Mob Waves refuses to start until the config is fixed, so a typo
 * can never turn into an unbounded spawn.
 */
data class MobWavesSettings(
    val countdownSeconds: Int,
    val intermissionSeconds: Int,
    val minPlayers: Int,
    /** Event mobs alive at once; a wave's remaining mobs wait until some die. */
    val maxActiveMobs: Int,
    val maxMobsPerWave: Int,
    val spawnBatchSize: Int,
    val spawnIntervalTicks: Int,
    /** Mobs never spawn closer than this to a living participant. */
    val minPlayerDistance: Double,
    /** Ordinary mob drops (equipment never drops). Experience never drops: event-world XP would carry into survival. */
    val dropItems: Boolean,
    val healBetweenWaves: Boolean,
    /** Extra health per wave after the first (0.05 = +5% per wave), on top of each wave's own multiplier. */
    val healthPerWave: Double,
    /** Play only the first N waves; 0 = all of them. */
    val wavesToPlay: Int,
    val allowed: Set<EntityType>,
    val waves: List<WaveDef>,
    val problems: List<String>,
) {
    val isValid: Boolean get() = problems.isEmpty() && waves.isNotEmpty()

    /** The waves this run plays. */
    val playable: List<WaveDef> get() = if (wavesToPlay in 1 until waves.size) waves.take(wavesToPlay) else waves

    /** Health multiplier for a mob of [group] in [wave], everything combined and clamped. */
    fun healthFor(wave: WaveDef, group: MobGroup): Double =
        (wave.healthMultiplier * group.healthMultiplier * (1.0 + healthPerWave * (wave.number - 1))).coerceIn(MIN_MULTIPLIER, MAX_HEALTH_MULTIPLIER)

    fun damageFor(wave: WaveDef, group: MobGroup): Double =
        (wave.damageMultiplier * group.damageMultiplier).coerceIn(MIN_MULTIPLIER, MAX_DAMAGE_MULTIPLIER)

    companion object {
        /** Hard caps no config can raise. */
        const val MAX_WAVES = 50
        const val MAX_ACTIVE_CAP = 200
        const val MAX_PER_WAVE_CAP = 1000
        const val MAX_BATCH = 20
        const val MIN_MULTIPLIER = 0.25
        const val MAX_HEALTH_MULTIPLIER = 20.0
        const val MAX_DAMAGE_MULTIPLIER = 5.0
        const val MAX_SPEED_MULTIPLIER = 2.0
        const val MIN_SPEED_MULTIPLIER = 0.5

        /** Never allowed, whatever allowed-entities says: they break arenas or ignore boundaries. */
        val NEVER_ALLOWED: Set<EntityType> = setOf(EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.GHAST, EntityType.PHANTOM, EntityType.ENDERMAN)

        val DEFAULT_ALLOWED = listOf(
            "zombie", "husk", "zombie_villager", "drowned", "skeleton", "stray", "bogged", "spider",
            "cave_spider", "creeper", "witch", "pillager", "vindicator", "wither_skeleton", "blaze",
        )

        /** A spawnable hostile mob, or null. Accepts `ZOMBIE`, `zombie` and `minecraft:zombie`. */
        fun entityType(name: String): EntityType? {
            val key = NamespacedKey.fromString(name.trim().lowercase()) ?: return null
            val type = Registry.ENTITY_TYPE.get(key) ?: return null
            val cls = type.entityClass ?: return null
            if (!type.isSpawnable || !type.isAlive) return null
            if (!Mob::class.java.isAssignableFrom(cls) || !Enemy::class.java.isAssignableFrom(cls)) return null
            return type
        }

        fun read(root: ConfigurationSection?): MobWavesSettings {
            val c = root?.getConfigurationSection("mobs")
            val problems = ArrayList<String>()

            val allowedNames = if (c != null && c.isList("allowed-entities")) c.getStringList("allowed-entities") else DEFAULT_ALLOWED
            val allowed = LinkedHashSet<EntityType>()
            for (name in allowedNames) {
                val type = entityType(name)
                when {
                    type == null -> problems += "allowed-entities: '$name' isn't a spawnable hostile mob"
                    type in NEVER_ALLOWED -> problems += "allowed-entities: ${type.key.key} can never be used in Mob Waves"
                    else -> allowed += type
                }
            }

            val maxActive = (c?.getInt("max-active-mobs", 30) ?: 30).coerceIn(1, MAX_ACTIVE_CAP)
            val maxPerWave = (c?.getInt("max-mobs-per-wave", 120) ?: 120).coerceIn(1, MAX_PER_WAVE_CAP)
            val batch = (c?.getInt("spawn-batch-size", 4) ?: 4).coerceIn(1, MAX_BATCH)
            val interval = (c?.getInt("spawn-interval-ticks", 20) ?: 20).coerceIn(5, 200)

            val waves = readWaves(c?.getConfigurationSection("waves"), allowed, maxPerWave, batch, interval, problems)
            if (waves.isEmpty() && problems.isEmpty()) problems += "No waves are configured under hosted-events.mobs.waves"

            return MobWavesSettings(
                countdownSeconds = (c?.getInt("countdown-seconds", 5) ?: 5).coerceIn(1, 30),
                intermissionSeconds = (c?.getInt("intermission-seconds", 15) ?: 15).coerceIn(3, 300),
                minPlayers = (c?.getInt("min-players", 1) ?: 1).coerceAtLeast(1),
                maxActiveMobs = maxActive,
                maxMobsPerWave = maxPerWave,
                spawnBatchSize = batch,
                spawnIntervalTicks = interval,
                minPlayerDistance = (c?.getDouble("min-player-distance", 4.0) ?: 4.0).coerceIn(0.0, 32.0),
                dropItems = c?.getBoolean("drop-items", false) ?: false,
                healBetweenWaves = c?.getBoolean("heal-between-waves", true) ?: true,
                healthPerWave = (c?.getDouble("scaling.health-per-wave", 0.05) ?: 0.05).coerceIn(0.0, 1.0),
                wavesToPlay = (c?.getInt("waves-to-play", 0) ?: 0).coerceIn(0, MAX_WAVES),
                allowed = allowed,
                waves = waves,
                problems = problems,
            )
        }

        private fun readWaves(
            section: ConfigurationSection?,
            allowed: Set<EntityType>,
            maxPerWave: Int,
            defaultBatch: Int,
            defaultInterval: Int,
            problems: MutableList<String>,
        ): List<WaveDef> {
            if (section == null) return emptyList()
            val keyed = section.getKeys(false).mapNotNull { key ->
                val n = key.toIntOrNull()
                if (n == null || n < 1) { problems += "waves: '$key' isn't a wave number (use 1, 2, 3, ...)"; null } else n to key
            }.sortedBy { it.first }
            if (keyed.size > MAX_WAVES) problems += "waves: ${keyed.size} waves are configured; the limit is $MAX_WAVES"
            keyed.forEachIndexed { i, (n, _) -> if (n != i + 1) { problems += "waves: wave numbers must run 1, 2, 3, ... without gaps (found $n where ${i + 1} was expected)"; return emptyList() } }

            val waves = ArrayList<WaveDef>()
            for ((n, key) in keyed.take(MAX_WAVES)) {
                val w = section.getConfigurationSection(key) ?: run { problems += "Wave $n isn't a section"; null } ?: continue
                val where = "Wave $n"
                val gear = w.getString("gear")?.let { id -> MobGear.fromId(id) ?: run { problems += "$where: gear '$id' isn't one of ${MobGear.ids.joinToString("/")}"; null } } ?: MobGear.NONE
                val health = multiplier(w, "health-multiplier", where, MAX_HEALTH_MULTIPLIER, problems)
                val damage = multiplier(w, "damage-multiplier", where, MAX_DAMAGE_MULTIPLIER, problems)
                val speed = w.getDouble("speed-multiplier", 1.0).let {
                    if (it < MIN_SPEED_MULTIPLIER || it > MAX_SPEED_MULTIPLIER) { problems += "$where: speed-multiplier $it must be $MIN_SPEED_MULTIPLIER to $MAX_SPEED_MULTIPLIER"; 1.0 } else it
                }
                val groups = ArrayList<MobGroup>()
                val mobs = w.getConfigurationSection("mobs")
                if (mobs == null && !w.isConfigurationSection("boss")) problems += "$where has no mobs"
                mobs?.getKeys(false)?.forEach { name -> readGroup(mobs, name, false, where, allowed, maxPerWave, problems)?.let { groups += it } }
                w.getConfigurationSection("boss")?.let { boss ->
                    val name = boss.getString("type") ?: run { problems += "$where: boss has no type"; null }
                    if (name != null) readGroup(boss, name, true, "$where boss", allowed, maxPerWave, problems)?.let { groups += it }
                }
                val def = WaveDef(
                    number = n,
                    name = w.getString("name")?.takeIf { it.isNotBlank() }?.take(32),
                    groups = groups,
                    spawnPoints = w.getIntegerList("spawn-points").distinct(),
                    healthMultiplier = health,
                    damageMultiplier = damage,
                    speedMultiplier = speed,
                    gear = gear,
                    spawnIntervalTicks = w.getInt("spawn-interval-ticks", defaultInterval).coerceIn(5, 200),
                    batchSize = w.getInt("spawn-batch-size", defaultBatch).coerceIn(1, MAX_BATCH),
                )
                def.spawnPoints.filter { it < 1 || it > com.liam.joshymc.communityevent.arena.ArenaValidator.MAX_MOB_SPAWNS }
                    .forEach { problems += "$where: spawn point $it doesn't exist (points are numbered 1 to ${com.liam.joshymc.communityevent.arena.ArenaValidator.MAX_MOB_SPAWNS})" }
                if (def.total > maxPerWave) problems += "$where has ${def.total} mobs; max-mobs-per-wave is $maxPerWave"
                if (groups.isNotEmpty()) waves += def else if (mobs != null) problems += "$where has no valid mobs"
            }
            return waves
        }

        /**
         * One group: `ZOMBIE: 4`, or a section `ZOMBIE: { count: 4, gear: iron, health-multiplier: 1.5, name: ... }`.
         * For a boss, [section] is the boss section itself and [name] its type.
         */
        private fun readGroup(
            section: ConfigurationSection,
            name: String,
            boss: Boolean,
            where: String,
            allowed: Set<EntityType>,
            maxPerWave: Int,
            problems: MutableList<String>,
        ): MobGroup? {
            val type = entityType(name)
            if (type == null) { problems += "$where: '$name' isn't a spawnable hostile mob"; return null }
            if (type !in allowed) { problems += "$where: ${type.key.key} isn't in allowed-entities"; return null }
            val s: ConfigurationSection? = if (boss) section else section.getConfigurationSection(name)
            val count = when {
                boss -> section.getInt("count", 1)
                s != null -> s.getInt("count", 0)
                section.isInt(name) -> section.getInt(name)
                else -> { problems += "$where: '$name' needs a number of mobs"; return null }
            }
            if (count < 1 || count > maxPerWave) { problems += "$where: $count ${type.key.key} must be 1 to $maxPerWave"; return null }
            val gear = s?.getString("gear")?.let { id -> MobGear.fromId(id) ?: run { problems += "$where: gear '$id' isn't one of ${MobGear.ids.joinToString("/")}"; null } }
            return MobGroup(
                type = type,
                count = count,
                name = s?.getString("name")?.takeIf { it.isNotBlank() }?.take(32),
                healthMultiplier = s?.let { multiplier(it, "health-multiplier", where, MAX_HEALTH_MULTIPLIER, problems) } ?: 1.0,
                damageMultiplier = s?.let { multiplier(it, "damage-multiplier", where, MAX_DAMAGE_MULTIPLIER, problems) } ?: 1.0,
                gear = gear,
                boss = boss,
            )
        }

        private fun multiplier(s: ConfigurationSection, key: String, where: String, max: Double, problems: MutableList<String>): Double {
            val v = s.getDouble(key, 1.0)
            if (!v.isFinite() || v < MIN_MULTIPLIER || v > max) {
                problems += "$where: $key $v must be $MIN_MULTIPLIER to $max"
                return 1.0
            }
            return v
        }
    }
}
