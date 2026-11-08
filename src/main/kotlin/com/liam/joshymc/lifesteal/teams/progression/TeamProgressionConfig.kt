package com.liam.joshymc.lifesteal.teams.progression

import com.liam.joshymc.lifesteal.bounty.BountyMoney
import java.time.ZoneId

/**
 * Where Team XP comes from (issue #1426). Each source has a configurable amount and daily cap.
 * Only verified events produce one (see [LifestealTeamProgressionManager]); there is no admin
 * "give XP" source.
 */
enum class XpSource(val id: String, val label: String) {
    /** A Lifesteal kill the heart system accepted as legitimate (not teammates, allies, cooldown or grace). */
    PVP_KILL("pvp-kill", "PvP eliminations"),
    /** A Lifesteal bounty that was claimed and paid. */
    BOUNTY_CLAIM("bounty-claim", "Bounty hunting"),
    /** Playing a rated Ranked Team Wars match (rated = public, not a test, same-opponent limit respected). */
    RANKED_MATCH("ranked-match", "Team Wars played"),
    /** Winning a rated Ranked Team Wars match (on top of [RANKED_MATCH]). */
    RANKED_WIN("ranked-win", "Team Wars won"),
    /** A naturally generated ore broken in a Lifesteal world (player-placed ores never count). */
    ORE_MINED("ore-mined", "Mining"),
    /** Every 100 blocks of real movement (no teleports, no AFK, no circling in place). */
    EXPLORATION("exploration", "Exploration"),
    /** A shared objective completed. Never capped (each objective completes once per cycle). */
    OBJECTIVE("objective", "Shared objectives");

    companion object {
        fun fromId(raw: String?): XpSource? = entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
        /** Sources a gameplay event can produce (and so have an amount + cap); [OBJECTIVE] comes from objective definitions. */
        val GAMEPLAY: List<XpSource> = entries.filter { it != OBJECTIVE }
    }
}

/**
 * What a shared objective counts (issue #1426). Every type is fed only by the same verified events
 * as the XP sources, so an event that is refused for XP (teammate kill, alt, too-new member, AFK)
 * never advances an objective either.
 */
enum class ObjectiveType(val id: String, val unit: String) {
    PVP_KILLS("pvp-kills", "eliminations"),
    /** Distinct victims per cycle (a repeat kill of the same player doesn't advance it). */
    UNIQUE_VICTIMS("unique-victims", "unique enemies"),
    BOUNTIES("bounties", "bounties"),
    RANKED_MATCHES("ranked-matches", "Team Wars"),
    RANKED_WINS("ranked-wins", "Team Wars won"),
    ORES("ores", "ores"),
    BLOCKS_TRAVELLED("blocks-travelled", "blocks"),
    /** Distinct members who earned Team XP this cycle. */
    UNIQUE_CONTRIBUTORS("unique-contributors", "members");

    companion object {
        fun fromId(raw: String?): ObjectiveType? = entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** The pools that keep progression from depending on PvP only. Display grouping. */
enum class ObjectivePool(val id: String, val label: String) {
    COMBAT("combat", "Combat"),
    MINING("mining", "Mining"),
    EXPLORATION("exploration", "Exploration"),
    TEAMWORK("teamwork", "Teamwork");

    companion object {
        fun fromId(raw: String?): ObjectivePool? = entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** How long one round of objectives lasts before every objective can be completed again. */
enum class ObjectiveCycle(val id: String) {
    WEEKLY("weekly"),
    /** Once per Lifesteal season (the progression manager's season id). */
    SEASON("season"),
    /** Each objective completes once per team, ever. */
    NEVER("never");

    companion object {
        fun fromId(raw: String?): ObjectiveCycle? = entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

/**
 * Upgrade categories (issue #1426). [requiresSystem] names the existing system a category plugs
 * into; when that system doesn't exist for Lifesteal teams yet, the category is shown as "not
 * available yet" and can't be bought, so no team pays for a perk that does nothing.
 */
enum class UpgradeCategory(val id: String, val label: String, val requiresSystem: String?) {
    STORAGE("storage", "Team Storage", "Lifesteal team storage"),
    HOME("home", "Team Home", "Lifesteal team homes"),
    ECONOMY("economy", "Team Economy", null),
    PROGRESSION("progression", "Team Progression", null),
    COSMETICS("cosmetics", "Team Cosmetics", null),
    UTILITY("utility", "Team Utility", null);

    /** Whether the system this category upgrades exists. Storage and homes don't exist for Lifesteal teams yet. */
    val systemAvailable: Boolean get() = requiresSystem == null

    companion object {
        fun fromId(raw: String?): UpgradeCategory? = entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** Seasonal reset policy. Only [KEEP] exists: the blueprint defines no team-progression reset (see docs). */
enum class SeasonResetPolicy(val id: String) {
    KEEP("keep");

    companion object {
        fun fromId(raw: String?): SeasonResetPolicy? = entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** `lifesteal.yml > team-progression` (issue #1426), Bukkit-free. Money is whole cents ([BountyMoney]). */
data class TeamProgressionSettings(
    /** false = no XP, purchases, deposits or claims. Nothing stored is deleted or changed. */
    val enabled: Boolean = true,
    val levels: Levels = Levels(),
    val xp: Xp = Xp(),
    val bank: Bank = Bank(),
    val upgrades: Map<UpgradeCategory, Category> = DEFAULT_UPGRADES,
    val objectives: Objectives = Objectives(),
    val milestones: Milestones = Milestones(),
    val cosmetics: Cosmetics = Cosmetics(),
    val seasons: Seasons = Seasons(),
    val gui: Gui = Gui(),
) {
    /** XP for the next level = round([base] × level^[exponent]). */
    data class Levels(val base: Double = 1000.0, val exponent: Double = 1.5)

    data class Xp(
        val amounts: Map<XpSource, Long> = mapOf(
            XpSource.PVP_KILL to 40, XpSource.BOUNTY_CLAIM to 75, XpSource.RANKED_MATCH to 60, XpSource.RANKED_WIN to 120,
            XpSource.ORE_MINED to 1, XpSource.EXPLORATION to 2,
        ),
        /** Base XP one team can earn per day from each source (before the progression bonus). */
        val dailyCaps: Map<XpSource, Long> = mapOf(
            XpSource.PVP_KILL to 400, XpSource.BOUNTY_CLAIM to 450, XpSource.RANKED_MATCH to 480, XpSource.RANKED_WIN to 720,
            XpSource.ORE_MINED to 300, XpSource.EXPLORATION to 200,
        ),
        /** Base XP one team can earn per day from all gameplay sources together. */
        val teamDailyCap: Long = 2500,
        /** Base XP one member can earn for their team per day. */
        val memberDailyCap: Long = 800,
        /** A member earns XP for a team only after being in it this long (stops team hopping). */
        val minimumMembershipMillis: Long = 60L * 60_000L,
        /** The same killer team earns PvP XP for the same victim at most once per window. */
        val pvpSameVictimMillis: Long = 24L * 3_600_000L,
        /** Kills and bounties between players who have shared a connection (the /alts data) earn nothing. */
        val blockSharedConnections: Boolean = true,
        /** Days and weeks (caps, weekly objectives) roll over in this zone. */
        val timezone: ZoneId = ZoneId.of("America/New_York"),
        /** Ores that count for [XpSource.ORE_MINED] / [ObjectiveType.ORES] (material names). */
        val ores: Set<String> = DEFAULT_ORES,
    )

    data class Bank(
        /** false = no deposits (and so no purchases funded by new coins). The stored balance is kept. */
        val depositsEnabled: Boolean = true,
        val minimumDepositCents: Long = 100_000L,
        /** Most coins the bank holds before Team Economy upgrades. Milestone rewards may exceed it. */
        val baseCapacityCents: Long = 2_000_000_00L,
    )

    /** One tier: its price, the Team Level it needs, and what it gives ([value], meaning per category). */
    data class Tier(val costCents: Long, val requiredLevel: Int, val value: Int)

    data class Category(val enabled: Boolean, val tiers: List<Tier>) {
        val maxTier: Int get() = tiers.size
    }

    data class Objective(
        val id: String,
        val pool: ObjectivePool,
        val type: ObjectiveType,
        val target: Long,
        val xp: Long,
        val name: String,
    )

    data class Objectives(val cycle: ObjectiveCycle = ObjectiveCycle.WEEKLY, val list: List<Objective> = DEFAULT_OBJECTIVES)

    data class Reward(val coinsCents: Long = 0, val title: String? = null)

    data class Milestones(
        val levels: List<Int> = listOf(5, 10, 25, 50, 75, 100),
        /** After the last listed level, a milestone every this many levels (0 = none). */
        val everyAfter: Int = 25,
        val rewards: Map<Int, Reward> = DEFAULT_REWARDS,
        /** Reward for a milestone level without its own entry (the repeating ones). */
        val defaultReward: Reward = Reward(coinsCents = 250_000_00L),
    )

    /** Team colours a leader can pick, in unlock order; Team Cosmetics tier N unlocks the first `value` of them. */
    data class Cosmetics(val colors: List<String> = listOf("gold", "aqua", "green", "light_purple", "red", "yellow", "blue", "white"))

    data class Seasons(val resetPolicy: SeasonResetPolicy = SeasonResetPolicy.KEEP)

    data class Gui(
        val mainTitle: String = "Team Upgrades",
        val levelUp: String = "&6&lTEAM LEVEL UP! &e{team} &7reached &6Level {level}&7!",
        val objectiveDone: String = "&a&lOBJECTIVE &7Your team completed &f{objective} &7(+{xp} Team XP).",
        val milestoneReady: String = "&d&lMILESTONE &7Level {level} reward is ready. &e/team milestones",
    )

    fun category(category: UpgradeCategory): Category = upgrades[category] ?: Category(false, emptyList())

    companion object {
        /** Lazy: the default lists below must exist before the first settings object is built. */
        val DEFAULTS: TeamProgressionSettings by lazy { TeamProgressionSettings() }

        val DEFAULT_ORES: Set<String> = setOf(
            "COAL_ORE", "DEEPSLATE_COAL_ORE", "IRON_ORE", "DEEPSLATE_IRON_ORE", "COPPER_ORE", "DEEPSLATE_COPPER_ORE",
            "GOLD_ORE", "DEEPSLATE_GOLD_ORE", "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE", "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE",
            "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE", "NETHER_GOLD_ORE",
            "NETHER_QUARTZ_ORE", "ANCIENT_DEBRIS",
        )

        private val FIVE_COSTS = listOf(50_000L, 150_000L, 350_000L, 750_000L, 1_500_000L).map { it * 100 }
        private val FIVE_LEVELS = listOf(2, 4, 6, 8, 10)

        private fun five(values: List<Int>) = FIVE_COSTS.indices.map { Tier(FIVE_COSTS[it], FIVE_LEVELS[it], values[it]) }

        val DEFAULT_UPGRADES: Map<UpgradeCategory, Category> = linkedMapOf(
            UpgradeCategory.STORAGE to Category(false, five(listOf(9, 9, 9, 9, 9))),
            UpgradeCategory.HOME to Category(false, five(listOf(1, 1, 1, 1, 1))),
            UpgradeCategory.ECONOMY to Category(true, five(listOf(1_000_000, 1_000_000, 2_000_000, 2_000_000, 3_000_000))),
            UpgradeCategory.PROGRESSION to Category(true, five(listOf(2, 4, 6, 8, 10))),
            UpgradeCategory.COSMETICS to Category(true, listOf(
                Tier(50_000_00L, 2, 2), Tier(150_000_00L, 5, 5), Tier(350_000_00L, 10, 8),
            )),
            UpgradeCategory.UTILITY to Category(true, listOf(
                Tier(50_000_00L, 2, 1), Tier(150_000_00L, 4, 2), Tier(350_000_00L, 6, 3),
            )),
        )

        val DEFAULT_OBJECTIVES: List<Objective> = listOf(
            Objective("enemy-hunters", ObjectivePool.COMBAT, ObjectiveType.UNIQUE_VICTIMS, 25, 600, "Eliminate 25 unique enemies"),
            Objective("bounty-hunters", ObjectivePool.COMBAT, ObjectiveType.BOUNTIES, 10, 500, "Claim 10 bounties"),
            Objective("war-champions", ObjectivePool.COMBAT, ObjectiveType.RANKED_WINS, 5, 750, "Win 5 Team Wars"),
            Objective("deep-miners", ObjectivePool.MINING, ObjectiveType.ORES, 2500, 600, "Mine 2,500 ores"),
            Objective("trailblazers", ObjectivePool.EXPLORATION, ObjectiveType.BLOCKS_TRAVELLED, 50_000, 600, "Travel 50,000 blocks"),
            Objective("all-hands", ObjectivePool.TEAMWORK, ObjectiveType.UNIQUE_CONTRIBUTORS, 3, 400, "3 members earn Team XP"),
        )

        val DEFAULT_REWARDS: Map<Int, Reward> = mapOf(
            5 to Reward(25_000_00L, "Rising"),
            10 to Reward(50_000_00L, "Established"),
            25 to Reward(100_000_00L, "Veteran"),
            50 to Reward(150_000_00L, "Elite"),
            75 to Reward(200_000_00L, "Legendary"),
            100 to Reward(250_000_00L, "Mythic"),
        )
    }
}

/**
 * Parses `lifesteal.yml > team-progression` (issue #1426). Read on its own (like bounties), so a
 * mistake here only stops Team Upgrades: hearts, teams and everything else keep running. A key
 * left out uses its default (a warning); a wrong value is severe and makes the section invalid
 * (null settings = paused: no XP, purchases, deposits or claims; nothing stored changes).
 */
object TeamProgressionConfig {

    data class Problem(val severe: Boolean, val message: String)

    data class Result(val settings: TeamProgressionSettings?, val problems: List<Problem>) {
        val valid: Boolean get() = settings != null
    }

    /** Hard cap on the Team Progression XP bonus: the issue's "up to 10%" is the maximum, not a suggestion. */
    const val MAX_XP_BONUS_PERCENT = 10
    const val MAX_TIERS = 10
    const val MAX_LEVEL_SETTING = 100_000
    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,31}$")

    /** [get] reads a path below `team-progression.` (null when missing). */
    fun parse(get: (String) -> Any?): Result {
        val problems = mutableListOf<Problem>()
        val d = TeamProgressionSettings.DEFAULTS
        fun missing(path: String, default: Any) { problems += Problem(false, "team-progression.$path is missing; using $default.") }
        fun bad(path: String, raw: Any?, what: String) { problems += Problem(true, "team-progression.$path '$raw' $what.") }

        fun bool(path: String, default: Boolean): Boolean = when (val raw = get(path)) {
            null -> { missing(path, default); default }
            is Boolean -> raw
            else -> { bad(path, raw, "must be true or false"); default }
        }
        fun long(path: String, default: Long, min: Long, max: Long): Long {
            val raw = get(path) ?: run { missing(path, default); return default }
            val value = whole(raw)
            if (value == null || value < min || value > max) { bad(path, raw, "must be a whole number from $min to $max"); return default }
            return value
        }
        fun decimal(path: String, default: Double, min: Double, max: Double): Double {
            val raw = get(path) ?: run { missing(path, default); return default }
            val value = (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull()
            if (value == null || !value.isFinite() || value < min || value > max) { bad(path, raw, "must be a number from $min to $max"); return default }
            return value
        }
        fun coins(path: String, default: Long, min: Long): Long {
            val raw = get(path) ?: run { missing(path, BountyMoney.format(default)); return default }
            val cents = BountyMoney.configCents(raw)
            if (cents == null || cents < min) { bad(path, raw, "must be an amount of coins of at least ${BountyMoney.format(min)}"); return default }
            return cents
        }
        fun text(path: String, default: String): String = when (val raw = get(path)) {
            null -> { missing(path, "the default text"); default }
            is String -> raw
            else -> { bad(path, raw, "must be text"); default }
        }

        val enabled = bool("enabled", d.enabled)
        val levels = TeamProgressionSettings.Levels(
            base = decimal("levels.base", d.levels.base, 1.0, 1_000_000.0),
            exponent = decimal("levels.exponent", d.levels.exponent, 0.0, 4.0),
        )

        val amounts = linkedMapOf<XpSource, Long>()
        val caps = linkedMapOf<XpSource, Long>()
        for (source in XpSource.GAMEPLAY) {
            amounts[source] = long("xp.sources.${source.id}.amount", d.xp.amounts[source] ?: 0, 0, 100_000)
            caps[source] = long("xp.sources.${source.id}.daily-cap", d.xp.dailyCaps[source] ?: 0, 0, 10_000_000)
        }
        val zoneRaw = get("xp.timezone")
        val zone = when {
            zoneRaw == null -> { missing("xp.timezone", d.xp.timezone.id); d.xp.timezone }
            else -> runCatching { ZoneId.of(zoneRaw.toString()) }.getOrElse { bad("xp.timezone", zoneRaw, "must be a time zone such as America/New_York"); d.xp.timezone }
        }
        val ores = when (val raw = get("xp.ores")) {
            null -> { missing("xp.ores", "the default ore list"); d.xp.ores }
            is List<*> -> raw.mapNotNull { entry ->
                val name = entry?.toString()?.trim()?.uppercase()
                if (name.isNullOrEmpty() || !name.matches(Regex("^[A-Z0-9_]+$"))) { bad("xp.ores", entry, "entries must be block names"); null } else name
            }.toSet()
            else -> { bad("xp.ores", raw, "must be a list of block names"); d.xp.ores }
        }
        val xp = TeamProgressionSettings.Xp(
            amounts = amounts,
            dailyCaps = caps,
            teamDailyCap = long("xp.team-daily-cap", d.xp.teamDailyCap, 0, 100_000_000),
            memberDailyCap = long("xp.member-daily-cap", d.xp.memberDailyCap, 0, 100_000_000),
            minimumMembershipMillis = long("xp.anti-farm.minimum-membership-minutes", d.xp.minimumMembershipMillis / 60_000L, 0, 60L * 24 * 30) * 60_000L,
            pvpSameVictimMillis = long("xp.anti-farm.pvp-same-victim-hours", d.xp.pvpSameVictimMillis / 3_600_000L, 0, 24L * 365) * 3_600_000L,
            blockSharedConnections = bool("xp.anti-farm.block-shared-connections", d.xp.blockSharedConnections),
            timezone = zone,
            ores = ores,
        )

        val bank = TeamProgressionSettings.Bank(
            depositsEnabled = bool("bank.deposits-enabled", d.bank.depositsEnabled),
            minimumDepositCents = coins("bank.minimum-deposit", d.bank.minimumDepositCents, 1),
            baseCapacityCents = coins("bank.base-capacity", d.bank.baseCapacityCents, 1).coerceAtMost(BountyMoney.MAX_CENTS),
        )

        val upgrades = linkedMapOf<UpgradeCategory, TeamProgressionSettings.Category>()
        for (category in UpgradeCategory.entries) {
            val default = d.category(category)
            val base = "upgrades.${category.id}"
            val catEnabled = bool("$base.enabled", default.enabled)
            val tiers = when (val raw = get("$base.tiers")) {
                null -> { missing("$base.tiers", "the default tiers"); default.tiers }
                is List<*> -> parseTiers(base, raw, category, problems) ?: default.tiers
                else -> { bad("$base.tiers", raw, "must be a list of tiers"); default.tiers }
            }
            upgrades[category] = TeamProgressionSettings.Category(catEnabled, tiers)
        }
        val economyBonus = upgrades[UpgradeCategory.ECONOMY]?.tiers?.sumOf { it.value.toLong() * 100 } ?: 0
        if (bank.baseCapacityCents + economyBonus > BountyMoney.MAX_CENTS) {
            problems += Problem(true, "team-progression: bank.base-capacity plus every Team Economy tier is more than ${BountyMoney.format(BountyMoney.MAX_CENTS)}.")
        }

        val cycleRaw = get("objectives.cycle")
        val cycle = when {
            cycleRaw == null -> { missing("objectives.cycle", d.objectives.cycle.id); d.objectives.cycle }
            else -> ObjectiveCycle.fromId(cycleRaw.toString()) ?: run { bad("objectives.cycle", cycleRaw, "must be weekly, season or never"); d.objectives.cycle }
        }
        val objectives = when (val raw = get("objectives.list")) {
            null -> { missing("objectives.list", "the default objectives"); d.objectives.list }
            is List<*> -> parseObjectives(raw, problems)
            else -> { bad("objectives.list", raw, "must be a list of objectives"); d.objectives.list }
        }

        val milestoneLevels = when (val raw = get("milestones.levels")) {
            null -> { missing("milestones.levels", d.milestones.levels); d.milestones.levels }
            is List<*> -> raw.mapNotNull { entry ->
                val level = entry?.let { whole(it) }
                if (level == null || level < 2 || level > MAX_LEVEL_SETTING) { bad("milestones.levels", entry, "entries must be levels from 2 to $MAX_LEVEL_SETTING"); null } else level.toInt()
            }.let { list ->
                if (list != list.sorted() || list.toSet().size != list.size) problems += Problem(true, "team-progression.milestones.levels must be strictly increasing.")
                list
            }
            else -> { bad("milestones.levels", raw, "must be a list of levels"); d.milestones.levels }
        }
        val everyAfter = long("milestones.every-after", d.milestones.everyAfter.toLong(), 0, 10_000).toInt()
        val rewards = linkedMapOf<Int, TeamProgressionSettings.Reward>()
        when (val raw = get("milestones.rewards")) {
            null -> { missing("milestones.rewards", "the default rewards"); rewards.putAll(d.milestones.rewards) }
            is List<*> -> for ((index, entry) in raw.withIndex()) {
                val map = entry as? Map<*, *> ?: run { bad("milestones.rewards[$index]", entry, "must be a level with its reward"); continue }
                val level = map["level"]?.let { whole(it) }?.toInt()
                if (level == null || level < 2) { bad("milestones.rewards[$index].level", map["level"], "must be a level of at least 2"); continue }
                val reward = reward("milestones.rewards[$index]", map, problems) ?: continue
                if (rewards.put(level, reward) != null) problems += Problem(true, "team-progression.milestones.rewards lists level $level twice.")
            }
            else -> bad("milestones.rewards", raw, "must be a list of level rewards")
        }
        val defaultReward = when (val raw = get("milestones.default-reward")) {
            null -> { missing("milestones.default-reward", "the default"); d.milestones.defaultReward }
            is Map<*, *> -> reward("milestones.default-reward", raw, problems) ?: d.milestones.defaultReward
            else -> { bad("milestones.default-reward", raw, "must be a reward (coins, title)"); d.milestones.defaultReward }
        }
        val milestones = TeamProgressionSettings.Milestones(milestoneLevels, everyAfter, rewards, defaultReward)
        for (level in rewards.keys) {
            if (!TeamLevels.isMilestone(level, milestones)) problems += Problem(false, "team-progression.milestones.rewards has level $level, which isn't a milestone level; it is never given.")
        }

        val colors = when (val raw = get("cosmetics.colors")) {
            null -> { missing("cosmetics.colors", d.cosmetics.colors); d.cosmetics.colors }
            is List<*> -> raw.mapNotNull { entry ->
                val name = entry?.toString()?.trim()?.lowercase()
                if (name == null || name !in TeamStyle.COLOR_NAMES) { bad("cosmetics.colors", entry, "must be a colour name (${TeamStyle.COLOR_NAMES.joinToString()})"); null } else name
            }.distinct()
            else -> { bad("cosmetics.colors", raw, "must be a list of colour names"); d.cosmetics.colors }
        }
        val maxColors = upgrades[UpgradeCategory.COSMETICS]?.tiers?.maxOfOrNull { it.value } ?: 0
        if (maxColors > colors.size) problems += Problem(false, "team-progression: Team Cosmetics unlocks up to $maxColors colours but cosmetics.colors lists ${colors.size}.")

        val policyRaw = get("seasons.reset-policy")
        val policy = when {
            policyRaw == null -> { missing("seasons.reset-policy", "keep"); SeasonResetPolicy.KEEP }
            else -> SeasonResetPolicy.fromId(policyRaw.toString()) ?: run {
                bad("seasons.reset-policy", policyRaw, "is not supported: only 'keep' exists (no team-progression reset is approved yet, see docs/lifesteal-team-progression.md)")
                SeasonResetPolicy.KEEP
            }
        }

        val gui = TeamProgressionSettings.Gui(
            mainTitle = text("gui.main-title", d.gui.mainTitle).take(28),
            levelUp = text("messages.level-up", d.gui.levelUp),
            objectiveDone = text("messages.objective-completed", d.gui.objectiveDone),
            milestoneReady = text("messages.milestone-ready", d.gui.milestoneReady),
        )

        val settings = TeamProgressionSettings(
            enabled = enabled, levels = levels, xp = xp, bank = bank, upgrades = upgrades,
            objectives = TeamProgressionSettings.Objectives(cycle, objectives), milestones = milestones,
            cosmetics = TeamProgressionSettings.Cosmetics(colors), seasons = TeamProgressionSettings.Seasons(policy), gui = gui,
        )
        return Result(if (problems.any { it.severe }) null else settings, problems)
    }

    private fun parseTiers(base: String, raw: List<*>, category: UpgradeCategory, problems: MutableList<Problem>): List<TeamProgressionSettings.Tier>? {
        if (raw.size > MAX_TIERS) { problems += Problem(true, "team-progression.$base.tiers has ${raw.size} tiers; at most $MAX_TIERS."); return null }
        val tiers = mutableListOf<TeamProgressionSettings.Tier>()
        for ((index, entry) in raw.withIndex()) {
            val path = "team-progression.$base.tiers[${index + 1}]"
            val map = entry as? Map<*, *> ?: run { problems += Problem(true, "$path must have cost, level and value."); return null }
            val cost = BountyMoney.configCents(map["cost"])
            val level = map["level"]?.let { whole(it) }
            val value = map["value"]?.let { whole(it) }
            when {
                cost == null || cost <= 0 -> { problems += Problem(true, "$path.cost '${map["cost"]}' must be a positive amount of coins."); return null }
                level == null || level < 1 || level > MAX_LEVEL_SETTING -> { problems += Problem(true, "$path.level '${map["level"]}' must be a Team Level from 1 to $MAX_LEVEL_SETTING."); return null }
                value == null || value < 0 || value > 100_000_000 -> { problems += Problem(true, "$path.value '${map["value"]}' must be a whole number of at least 0."); return null }
            }
            tiers += TeamProgressionSettings.Tier(cost!!, level!!.toInt(), value!!.toInt())
        }
        for (i in 1 until tiers.size) {
            if (tiers[i].costCents < tiers[i - 1].costCents) problems += Problem(true, "team-progression.$base.tiers: tier ${i + 1} is cheaper than tier $i (costs must be progressive).")
            if (tiers[i].requiredLevel < tiers[i - 1].requiredLevel) problems += Problem(true, "team-progression.$base.tiers: tier ${i + 1} needs a lower Team Level than tier $i.")
        }
        // Progression and cosmetics/utility values are cumulative: they must not fall from one tier to the next.
        if (category == UpgradeCategory.PROGRESSION || category == UpgradeCategory.COSMETICS || category == UpgradeCategory.UTILITY) {
            for (i in 1 until tiers.size) if (tiers[i].value < tiers[i - 1].value) {
                problems += Problem(true, "team-progression.$base.tiers: tier ${i + 1}'s value is lower than tier $i's (it is a total, not an increase).")
            }
        }
        if (category == UpgradeCategory.PROGRESSION && tiers.any { it.value > MAX_XP_BONUS_PERCENT }) {
            problems += Problem(true, "team-progression.$base.tiers: the Team XP bonus is at most $MAX_XP_BONUS_PERCENT%.")
        }
        if (category == UpgradeCategory.UTILITY && tiers.any { it.value > TeamPerks.UtilityFeature.entries.size }) {
            problems += Problem(true, "team-progression.$base.tiers: Team Utility has ${TeamPerks.UtilityFeature.entries.size} features; a value can't be higher.")
        }
        return tiers
    }

    private fun parseObjectives(raw: List<*>, problems: MutableList<Problem>): List<TeamProgressionSettings.Objective> {
        val list = mutableListOf<TeamProgressionSettings.Objective>()
        for ((index, entry) in raw.withIndex()) {
            val path = "team-progression.objectives.list[${index + 1}]"
            val map = entry as? Map<*, *> ?: run { problems += Problem(true, "$path must be an objective (id, pool, type, target, xp, name)."); continue }
            val id = map["id"]?.toString()?.trim()?.lowercase()
            val pool = ObjectivePool.fromId(map["pool"]?.toString())
            val type = ObjectiveType.fromId(map["type"]?.toString())
            val target = map["target"]?.let { whole(it) }
            val xp = map["xp"]?.let { whole(it) }
            val name = map["name"]?.toString()?.trim()
            when {
                id == null || !ID.matches(id) -> problems += Problem(true, "$path.id '${map["id"]}' must be lower-case letters, digits and dashes.")
                list.any { it.id == id } -> problems += Problem(true, "$path.id '$id' is used twice.")
                pool == null -> problems += Problem(true, "$path.pool '${map["pool"]}' must be one of ${ObjectivePool.entries.joinToString { it.id }}.")
                type == null -> problems += Problem(true, "$path.type '${map["type"]}' must be one of ${ObjectiveType.entries.joinToString { it.id }}.")
                target == null || target < 1 || target > 1_000_000_000 -> problems += Problem(true, "$path.target '${map["target"]}' must be from 1 to 1000000000.")
                xp == null || xp < 0 || xp > 1_000_000 -> problems += Problem(true, "$path.xp '${map["xp"]}' must be from 0 to 1000000.")
                name.isNullOrEmpty() -> problems += Problem(true, "$path.name must be set.")
                else -> list += TeamProgressionSettings.Objective(id, pool, type, target, xp, name.take(40))
            }
        }
        val pools = list.map { it.pool }.toSet()
        if (list.isNotEmpty() && pools == setOf(ObjectivePool.COMBAT)) {
            problems += Problem(false, "team-progression.objectives.list only has combat objectives; teams that don't PvP can't complete any.")
        }
        return list
    }

    private fun reward(path: String, map: Map<*, *>, problems: MutableList<Problem>): TeamProgressionSettings.Reward? {
        val coins = map["coins"]?.let { BountyMoney.configCents(it) ?: if (whole(it) == 0L) 0L else null } ?: if (map["coins"] == null) 0L else null
        if (coins == null || coins < 0) { problems += Problem(true, "team-progression.$path.coins '${map["coins"]}' must be an amount of coins (0 for none)."); return null }
        val title = map["title"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (title != null && !TeamStyle.validTitle(title)) {
            problems += Problem(true, "team-progression.$path.title '$title' must be 1 to ${TeamStyle.MAX_TITLE_LENGTH} letters, digits or spaces.")
            return null
        }
        return TeamProgressionSettings.Reward(coins, title)
    }

    private fun whole(raw: Any): Long? = when (raw) {
        is Int -> raw.toLong()
        is Long -> raw
        is Short -> raw.toLong()
        is Byte -> raw.toLong()
        is Double -> if (raw.isFinite() && raw == Math.floor(raw) && Math.abs(raw) < 9e15) raw.toLong() else null
        is Float -> if (raw.isFinite() && raw.toDouble() == Math.floor(raw.toDouble())) raw.toLong() else null
        is String -> raw.trim().toLongOrNull()
        else -> null
    }
}
