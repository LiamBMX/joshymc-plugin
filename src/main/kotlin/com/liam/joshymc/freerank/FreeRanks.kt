package com.liam.joshymc.freerank

import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.DonorTiers

/**
 * The free starter rank of each gamemode and the `/freerank` eligibility rule (issue #1295).
 * Bukkit-free.
 *
 * Survival's starter rank is Scout, the lowest [DonorTiers] tier, so it shares the donor
 * hierarchy, grant path and "already have it or higher" check with vouchers and the Credit
 * Shop. Lifesteal's starter rank (Rogue) has no rank system yet: there is no Lifesteal tier
 * hierarchy to grant it through, so it is null and `/freerank` refuses on Lifesteal until
 * one exists.
 */
object FreeRanks {

    fun starterRank(gamemode: ServerGamemode): String? = when (gamemode) {
        ServerGamemode.SURVIVAL -> "scout"
        ServerGamemode.LIFESTEAL -> null
    }

    enum class Decision {
        /** Grant the starter rank. */
        CLAIM,
        /** The player already holds the starter rank or a higher tier. */
        ALREADY_OWNED,
        /** They claimed it before in this gamemode (and no longer hold it). */
        ALREADY_CLAIMED,
        /** An earlier claim is still waiting on LuckPerms. */
        PENDING,
    }

    /** [currentTier] is the player's donor tier (null = none), [claim] their claim row for this gamemode. */
    fun decide(starter: String, currentTier: String?, claim: FreeRankStore.Status?): Decision = when {
        DonorTiers.compare(currentTier, starter) != DonorTiers.Comparison.UPGRADE -> Decision.ALREADY_OWNED
        claim == FreeRankStore.Status.PENDING -> Decision.PENDING
        claim == FreeRankStore.Status.COMPLETED -> Decision.ALREADY_CLAIMED
        else -> Decision.CLAIM
    }
}
