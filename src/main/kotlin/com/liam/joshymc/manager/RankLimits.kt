package com.liam.joshymc.manager

/**
 * Per-rank count limits (`homes.limits-by-rank`, `warps.limits-by-rank`), issue #1381.
 * Bukkit-free, so the rule can be checked on its own.
 *
 * Purchasable donor tiers (Scout..Pioneer, [DonorTiers]) live in LuckPerms, not in `ranks.list`,
 * so [RankManager.getPlayerRankIds] never contains them and a `limits-by-rank.<tier>` entry used
 * to be ignored. The player's current donor tier is now looked up as well. It can only ever
 * *raise* the limit the old rule gave: nothing changes for a tier without an entry (the bundled
 * config has none), and a tier entry lower than the player's other ranks never lowers them.
 */
object RankLimits {

    const val UNLIMITED = -1

    /**
     * [rankIds] are the player's JoshyMC ranks (`default` is used when empty, as before),
     * [donorTier] their current donor tier (or null), [limitFor] the configured limit for a
     * rank id (null when it has no entry) and [fallback] the limit for players with no entry.
     * Returns [UNLIMITED] or the highest applicable limit.
     */
    fun resolve(rankIds: Collection<String>, donorTier: String?, limitFor: (String) -> Int?, fallback: Int): Int {
        val configured = rankIds.ifEmpty { listOf("default") }.mapNotNull(limitFor)
        val base = when {
            UNLIMITED in configured -> UNLIMITED
            configured.isNotEmpty() -> configured.max()
            else -> fallback
        }
        val tier = DonorTiers.normalize(donorTier) ?: return base
        val donor = limitFor(tier) ?: return base
        return if (base == UNLIMITED || donor == UNLIMITED) UNLIMITED else maxOf(base, donor)
    }
}
