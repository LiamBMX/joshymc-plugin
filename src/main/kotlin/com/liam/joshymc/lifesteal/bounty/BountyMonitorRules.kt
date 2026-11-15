package com.liam.joshymc.lifesteal.bounty

import java.util.UUID

/**
 * Informational monitoring of claimed bounties (issue #1548, LS-ECO-05), Bukkit-free.
 *
 * A bounty can move coins between accounts (an alt funds a bounty, the main account claims it),
 * so every claimed payout is checked against AltManager's hashed connection evidence and the
 * recent payout history. The result is **only** recorded for staff (`/bounty admin suspicious`)
 * and told to online staff (throttled): nothing here blocks, delays, reverses or punishes, and a
 * shared connection is evidence for a review, not proof of abuse.
 */
object BountyMonitorRules {

    enum class Signal(val label: String) {
        CONTRIBUTOR_HUNTER_SHARED("contributor and hunter have shared a connection"),
        CONTRIBUTOR_TARGET_SHARED("contributor and target have shared a connection"),
        HUNTER_TARGET_SHARED("hunter and target have shared a connection"),
        REPEATED_GROUP("hunter repeatedly claimed bounties funded by the same contributors"),
        CONCENTRATED("one contributor funded most of the payout"),
    }

    /** Facts about one claimed payout, read in a few bounded queries ([BountyStore.payoutLinks]). */
    data class Links(
        /** Total cents per contributor of this bounty. */
        val shares: Map<UUID, Long>,
        val linkedToHunter: Set<UUID>,
        val linkedToTarget: Set<UUID>,
        val hunterTargetLinked: Boolean,
        /** Earlier payouts to the same hunter, inside the window, on bounties funded by any of these contributors. */
        val earlierCorrelatedPayouts: Int,
    )

    data class Flag(val signal: Signal, val contributors: List<UUID>)

    /** A contributor funding at least this share (basis points) of a payout counts as concentrated. */
    const val CONCENTRATION_BPS = 8_000L

    /**
     * The signals of one payout. Concentration alone is ordinary (one generous player), so it is
     * only flagged together with another signal and names the top contributor. [repeatThreshold]
     * is the bounties' `anti-abuse.repeat-kill-threshold` (this payout included).
     */
    fun signals(links: Links, repeatThreshold: Int): List<Flag> {
        val flags = mutableListOf<Flag>()
        if (links.linkedToHunter.isNotEmpty()) flags += Flag(Signal.CONTRIBUTOR_HUNTER_SHARED, links.linkedToHunter.sorted())
        if (links.linkedToTarget.isNotEmpty()) flags += Flag(Signal.CONTRIBUTOR_TARGET_SHARED, links.linkedToTarget.sorted())
        if (links.hunterTargetLinked) flags += Flag(Signal.HUNTER_TARGET_SHARED, emptyList())
        if (repeatThreshold > 0 && links.earlierCorrelatedPayouts + 1 >= repeatThreshold && links.earlierCorrelatedPayouts > 0) {
            flags += Flag(Signal.REPEATED_GROUP, links.shares.keys.sorted())
        }
        if (flags.isNotEmpty()) {
            val total = links.shares.values.sum()
            val top = links.shares.maxWithOrNull(compareBy<Map.Entry<UUID, Long>> { it.value }.thenBy { it.key })
            if (top != null && total > 0 && top.value * 10_000L >= total * CONCENTRATION_BPS) {
                flags += Flag(Signal.CONCENTRATED, listOf(top.key))
            }
        }
        return flags
    }

    /** The stored audit detail of one flag (UUIDs and cents only; never a connection hash or address). */
    fun detail(flag: Flag, payoutId: String, amountCents: Long): String = buildString {
        append("payout-monitor signal=").append(flag.signal.name)
        append(" payout=").append(payoutId)
        append(" amount=").append(amountCents)
        if (flag.contributors.isNotEmpty()) append(" contributors=").append(flag.contributors.joinToString(","))
        append(" (").append(flag.signal.label).append("; informational, not proof)")
    }
}
