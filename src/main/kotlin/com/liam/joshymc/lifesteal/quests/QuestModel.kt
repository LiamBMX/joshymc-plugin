package com.liam.joshymc.lifesteal.quests

import com.liam.joshymc.manager.PurchaseRewards
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * The data model of the Lifesteal Daily & Weekly Quests (issue #1425), Bukkit-free. Everything a
 * quest needs is in `lifesteal-quests.yml` (parsed by [QuestConfig]); the per-player state lives in
 * [LifestealQuestStore].
 */
enum class QuestPeriod(val id: String, val label: String) {
    DAILY("daily", "Daily"),
    WEEKLY("weekly", "Weekly");

    companion object {
        fun of(id: String?): QuestPeriod? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

enum class QuestCategory(val id: String, val label: String) {
    COMBAT("combat", "Combat"),
    SURVIVAL("survival", "Survival"),
    TEAMWORK("teamwork", "Teamwork");

    companion object {
        fun of(id: String?): QuestCategory? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/**
 * What a quest counts. [scale] = stored progress units per configured target unit (PvP damage is
 * configured in hearts and stored in tenths of a damage point; exploration in blocks, stored in
 * tenths), so fractional amounts add up exactly. [needsTeam] objectives only count while the
 * player is in a Lifesteal team with a teammate close by. [usesMatch] objectives can be narrowed
 * with `match:` (materials or entity types); [requiresMatch] ones must be.
 */
enum class QuestObjective(
    val id: String,
    val category: QuestCategory,
    val unit: String,
    val scale: Long = 1,
    val needsTeam: Boolean = false,
    val usesMatch: Boolean = false,
    val requiresMatch: Boolean = false,
) {
    PLAYER_KILL("player_kill", QuestCategory.COMBAT, "eliminations"),
    UNIQUE_PLAYER_KILL("unique_player_kill", QuestCategory.COMBAT, "different players"),
    PVP_DAMAGE("pvp_damage", QuestCategory.COMBAT, "hearts of damage", scale = 20),
    BOUNTY_CLAIM("bounty_claim", QuestCategory.COMBAT, "bounties"),
    PVP_VICTORY("pvp_victory", QuestCategory.COMBAT, "fights won"),
    MINE_ORE("mine_ore", QuestCategory.SURVIVAL, "ores", usesMatch = true),
    HARVEST_CROP("harvest_crop", QuestCategory.SURVIVAL, "crops", usesMatch = true),
    CATCH_FISH("catch_fish", QuestCategory.SURVIVAL, "catches", usesMatch = true),
    KILL_MOB("kill_mob", QuestCategory.SURVIVAL, "hostile mobs", usesMatch = true),
    EXPLORE("explore", QuestCategory.SURVIVAL, "blocks", scale = 10),
    GATHER_BLOCK("gather_block", QuestCategory.SURVIVAL, "blocks", usesMatch = true, requiresMatch = true),
    TEAM_ASSIST("team_assist", QuestCategory.TEAMWORK, "assists", needsTeam = true),
    TEAM_PVP_KILL("team_pvp_kill", QuestCategory.TEAMWORK, "eliminations", needsTeam = true),
    TEAM_MOB_KILL("team_mob_kill", QuestCategory.TEAMWORK, "hostile mobs", needsTeam = true, usesMatch = true),
    TEAM_MINE_ORE("team_mine_ore", QuestCategory.TEAMWORK, "ores", needsTeam = true, usesMatch = true),
    TEAM_EXPLORE("team_explore", QuestCategory.TEAMWORK, "blocks", scale = 10, needsTeam = true);

    companion object {
        fun of(id: String?): QuestObjective? = entries.firstOrNull { it.id.equals(id?.trim()?.replace('-', '_'), ignoreCase = true) }
    }
}

/** Who a template may be assigned to. */
enum class TeamRequirement(val id: String) {
    /** Everyone. */
    ANY("any"),
    /** Only players in a Lifesteal team (every teamwork objective). */
    REQUIRED("required"),
    /** Only players without a team: the solo alternatives to the team quests. */
    NONE("none");

    fun allows(hasTeam: Boolean): Boolean = when (this) {
        ANY -> true
        REQUIRED -> hasTeam
        NONE -> !hasTeam
    }

    companion object {
        fun of(id: String?): TeamRequirement? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/** Reward kinds a quest may give. Ranks, rank/credit/money vouchers and tokens are never quest rewards. */
object QuestRewardKinds {
    val ALLOWED: Set<PurchaseRewards.Kind> = setOf(
        PurchaseRewards.Kind.COINS,
        PurchaseRewards.Kind.CREDITS,
        PurchaseRewards.Kind.CRATE_KEY,
        PurchaseRewards.Kind.CHAT_TAG_VOUCHER,
    )
}

/** One configured quest. [target] is in configured units (see [QuestObjective.scale]). */
data class QuestTemplate(
    val id: String,
    val period: QuestPeriod,
    val name: String,
    val description: String,
    val category: QuestCategory,
    val objective: QuestObjective,
    val target: Long,
    /** Upper-case material / entity type names; empty = anything the objective counts. */
    val match: Set<String>,
    val rewards: List<PurchaseRewards.Spec>,
    val team: TeamRequirement,
    val enabled: Boolean,
    val weight: Int,
    /** Templates sharing a group are never assigned together in one cycle. */
    val group: String?,
) {
    /** The target in stored units. */
    val storedTarget: Long get() = target * objective.scale

    fun describe(): String = description.replace("{target}", target.toString())
}

data class RerollSettings(
    val freePerCycle: Int,
    /** Credits per paid reroll. */
    val creditCost: Double,
    /** Paid rerolls allowed per cycle (0 = none). */
    val maxPaidPerCycle: Int,
)

data class QuestAntiAbuse(
    val pvpSameVictimCooldownMillis: Long = 30 * 60_000L,
    val pvpMaxKillsPerHour: Int = 10,
    val pvpBlockSharedConnections: Boolean = true,
    /** Hearts of PvP damage one attacker can count against one victim per hour. */
    val pvpDamageCapHeartsPerVictimPerHour: Int = 40,
    val victoryWindowMillis: Long = 30_000L,
    val assistWindowMillis: Long = 15_000L,
    val teamRadius: Double = 48.0,
    val idleMillis: Long = 120_000L,
    val mobSpawnReasons: Set<String> = setOf("NATURAL", "DEFAULT", "REINFORCEMENTS", "JOCKEY", "PATROL", "RAID", "VILLAGE_INVASION"),
    val maxMobKillsPerMinute: Int = 40,
    val fishingMinIntervalMillis: Long = 3_000L,
    val requireCorrectTool: Boolean = true,
    val exploreMaxBlocksPerSecond: Double = 12.0,
)

data class QuestGuiText(
    val title: String = "&8Lifesteal Quests",
    val rerollTitle: String = "&8Confirm Reroll",
)

data class QuestMessages(
    val completed: String = "&a&lQUEST COMPLETE! &7{period} quest &f{quest}&7 is done. Claim it in &e/quests&7.",
    val claimed: String = "&aYou claimed &f{quest}&a: &6{rewards}&a.",
    val rerolled: String = "&aRerolled &f{old}&a into &f{new}&a{cost}.",
    val disabled: String = "&cLifesteal quests are turned off right now.",
)

data class QuestSettings(
    val enabled: Boolean,
    val zone: ZoneId,
    val dailyResetTime: LocalTime,
    val weeklyResetDay: DayOfWeek,
    val weeklyResetTime: LocalTime,
    val questsPerCycle: Map<QuestPeriod, Int>,
    val rerolls: Map<QuestPeriod, RerollSettings>,
    /** A team-only quest held by a player who no longer has a team can be rerolled for free. */
    val freeRerollWhenIneligible: Boolean,
    val antiAbuse: QuestAntiAbuse,
    val completionMessages: Boolean,
    val gui: QuestGuiText,
    val messages: QuestMessages,
    val pools: Map<QuestPeriod, List<QuestTemplate>>,
) {
    fun pool(period: QuestPeriod): List<QuestTemplate> = pools[period].orEmpty()

    fun template(period: QuestPeriod, id: String): QuestTemplate? = pool(period).firstOrNull { it.id == id }

    companion object {
        const val GAMEMODE = "lifesteal"
        val DEFAULT_ZONE: ZoneId = ZoneId.of("America/New_York")
    }
}

internal fun String.upperToken(): String = trim().uppercase(Locale.ROOT).replace(' ', '_').replace('-', '_')
