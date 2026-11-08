package com.liam.joshymc.milestone

/**
 * One individual milestone objective (issue #1420), e.g. Farming → Sugar Cane. [matches] are the
 * activity tokens it counts (block/entity names, catch classes, movement kinds); an activity counts
 * for every objective of its category whose [matches] it shares a token with.
 */
data class ObjectiveDef(
    val id: String,
    val category: MilestoneCategory,
    val name: String,
    val icon: String,
    val unit: String,
    val matches: Set<String>,
    val base: Long,
    val scaling: MilestoneScaling,
    val coins: CoinScaling,
) {
    val key: String get() = "${category.id}:$id"
}

/** A category: its overall level (own requirement, own rewards) and its objectives. */
data class CategoryDef(
    val category: MilestoneCategory,
    val name: String,
    val icon: String,
    val levelBase: Long,
    val levelScaling: MilestoneScaling,
    val levelCoins: CoinScaling,
    val objectives: List<ObjectiveDef>,
)

/**
 * Everything players can progress, Bukkit-free. Track keys: `<category>:<objective>` for
 * objectives, `level:<category>` for category levels.
 */
class MilestoneCatalog(
    categories: List<CategoryDef>,
    val objectiveRewards: MilestoneRewards.Table,
    val levelRewards: MilestoneRewards.Table,
) {
    val categories: List<CategoryDef> = categories.sortedBy { it.category.ordinal }
    private val byCategory: Map<MilestoneCategory, CategoryDef> = this.categories.associateBy { it.category }
    private val objectivesByKey: Map<String, ObjectiveDef> = this.categories.flatMap { it.objectives }.associateBy { it.key }

    fun category(category: MilestoneCategory): CategoryDef? = byCategory[category]
    fun objective(key: String): ObjectiveDef? = objectivesByKey[key]
    fun isKnown(key: String): Boolean = key in objectivesByKey || levelCategory(key) != null

    /** The category whose level [key] is, or null. */
    fun levelCategory(key: String): MilestoneCategory? {
        if (!key.startsWith("level:")) return null
        val category = MilestoneCategory.fromId(key.removePrefix("level:")) ?: return null
        return category.takeIf { it in byCategory }
    }

    /** Objectives of [category] counting an activity with [tokens]. */
    fun matching(category: MilestoneCategory, tokens: Set<String>): List<ObjectiveDef> {
        val def = byCategory[category] ?: return emptyList()
        if (tokens.isEmpty()) return emptyList()
        return def.objectives.filter { objective -> objective.matches.any { it in tokens } }
    }

    /** Every token any objective of [category] counts (for cheap pre-checks in listeners). */
    fun tokens(category: MilestoneCategory): Set<String> =
        byCategory[category]?.objectives?.flatMapTo(HashSet()) { it.matches } ?: emptySet()

    /** Stored progress units tier [tier] of [key] needs; null for unknown keys. */
    fun requiredScaled(key: String, tier: Int): Long? {
        objectivesByKey[key]?.let { return MilestoneMath.requirementScaled(it.base, tier, it.scaling) }
        val category = levelCategory(key) ?: return null
        val def = byCategory.getValue(category)
        return MilestoneMath.requirementScaled(def.levelBase, tier, def.levelScaling)
    }

    /** What completing tier [tier] of [key] pays; null for unknown keys. */
    fun rewardsFor(key: String, tier: Int): MilestoneRewards.TierRewards? {
        objectivesByKey[key]?.let {
            return MilestoneRewards.TierRewards(MilestoneMath.coins(it.coins, tier), objectiveRewards.specialFor(tier))
        }
        val category = levelCategory(key) ?: return null
        val def = byCategory.getValue(category)
        return MilestoneRewards.TierRewards(MilestoneMath.coins(def.levelCoins, tier), levelRewards.specialFor(tier))
    }

    /** Readable name: "Sugar Cane" or "Farming Level". */
    fun displayName(key: String): String {
        objectivesByKey[key]?.let { return it.name }
        val category = levelCategory(key) ?: return key
        return "${byCategory.getValue(category).name} Level"
    }

    /** The category a track belongs to. */
    fun categoryOf(key: String): MilestoneCategory? = objectivesByKey[key]?.category ?: levelCategory(key)
}
