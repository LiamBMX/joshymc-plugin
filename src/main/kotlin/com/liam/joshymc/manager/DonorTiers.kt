package com.liam.joshymc.manager

/**
 * The purchasable donor tiers (issue #1173) and their one hierarchy, shared by Rank Vouchers,
 * Credit Shop rank packages, `/rank` and [RankManager]'s donor-tier resolver so they always
 * agree on which tier a player owns. These tiers live in LuckPerms (a group per tier that
 * grants `joshymc.rankperk.<id>`, see `rank-perks` in config.yml), not in `ranks.list`.
 *
 * Pure Kotlin with no Bukkit types, so the decisions below can be exercised on their own.
 */
object DonorTiers {

    /** Lowest -> highest. Position in this list IS the hierarchy (moved from RankVoucherManager). */
    val ORDER: List<String> = listOf("scout", "pathfinder", "lumberjack", "trailblazer", "voyager", "ranger", "pioneer")

    fun normalize(id: String?): String? = id?.trim()?.lowercase()?.takeIf { it in ORDER }

    fun isTier(id: String?): Boolean = normalize(id) != null

    /** Index in [ORDER], -1 for null or a non-tier id. */
    fun index(id: String?): Int = normalize(id)?.let { ORDER.indexOf(it) } ?: -1

    fun displayName(id: String): String = id.lowercase().replaceFirstChar { it.uppercase() }

    /** Highest tier among [ids]; non-tier ids (staff ranks, `default`, ...) are ignored. */
    fun highest(ids: Iterable<String>): String? = ids.mapNotNull { normalize(it) }.maxByOrNull { ORDER.indexOf(it) }

    enum class Comparison { UPGRADE, SAME_TIER, LOWER_TIER }

    /** How granting [target] compares with the player's [current] tier (null = no tier). */
    fun compare(current: String?, target: String): Comparison {
        val targetIndex = index(target)
        require(targetIndex >= 0) { "Not a donor tier: $target" }
        val currentIndex = index(current)
        return when {
            targetIndex > currentIndex -> Comparison.UPGRADE
            targetIndex == currentIndex -> Comparison.SAME_TIER
            else -> Comparison.LOWER_TIER
        }
    }

    /** Tiers in [owned] strictly below [target]: the redundant ones an upgrade may clean up. */
    fun below(target: String, owned: Iterable<String>): List<String> {
        val targetIndex = index(target)
        return owned.mapNotNull { normalize(it) }.filter { ORDER.indexOf(it) < targetIndex }.distinct()
    }

    /** Permission nodes that show a player holds [tier]: the LuckPerms group node and the rank-perk node. */
    fun nodesFor(tier: String): List<String> = listOf(groupNode(tier), "joshymc.rankperk.$tier")

    fun groupNode(tier: String): String = "group.$tier"

    /**
     * Tiers a player holds according to their live permissions. [explicit] is the set of nodes
     * explicitly granted to them (true values of `getEffectivePermissions`), [hasPermission] the
     * live check. A player with a wildcard (`*`, `joshymc.*`) or op answers yes to every node,
     * so when [wildcard] is set only explicitly granted nodes count; otherwise staff holding
     * `*` would look like Pioneer.
     */
    fun liveTiers(explicit: Set<String>, wildcard: Boolean, hasPermission: (String) -> Boolean): Set<String> {
        val lowered = explicit.mapTo(HashSet()) { it.lowercase() }
        return ORDER.filterTo(LinkedHashSet()) { tier ->
            val nodes = nodesFor(tier)
            nodes.any { it in lowered } || (!wildcard && nodes.any(hasPermission))
        }
    }

    /** Whether the LuckPerms group for [tier] is visible on the player (used to confirm a grant). */
    fun groupConfirmed(tier: String, explicit: Set<String>, wildcard: Boolean, hasPermission: (String) -> Boolean): Boolean {
        val node = groupNode(tier)
        return explicit.any { it.equals(node, ignoreCase = true) } || (!wildcard && hasPermission(node))
    }

    private val RANK_COMMAND = Regex(
        """^/?(?:(?:lp|luckperms)\s+user\s+%player%\s+parent\s+(?:add|set)|rank\s+add\s+%player%)\s+([a-z0-9_]+)\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * The donor tier a legacy, unlinked Credit Shop reward command grants, or null. Only the
     * exact shapes `lp user %player% parent add|set <tier>` and `rank add %player% <tier>` are
     * recognized, so those packages go through the checked grant instead of a blind dispatch.
     */
    fun tierFromRewardCommand(command: String): String? =
        RANK_COMMAND.matchEntire(command.trim())?.groupValues?.get(1)?.let { normalize(it) }
}
