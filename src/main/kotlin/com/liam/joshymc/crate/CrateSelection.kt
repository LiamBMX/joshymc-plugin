package com.liam.joshymc.crate

/**
 * Pick rules for the Select / Select 3 GUIs (issue #1514), kept Bukkit-free.
 *
 * The GUIs only show state; every confirm re-checks the key, the crate and the picks on the
 * server. These rules say which picks are allowed and make sure one confirmation screen can
 * pay out at most once.
 */
object CrateSelection {

    const val SELECT_3_PICKS = 3

    sealed interface Toggle {
        data class Changed(val selected: Set<Int>) : Toggle
        /** Already at the limit: nothing changes. */
        data object Full : Toggle
        /** Not a reward of this crate (stale or forged slot). */
        data object Invalid : Toggle
    }

    /** Clicking reward [index]: deselects it when picked, picks it while there is room. */
    fun toggle(selected: Set<Int>, index: Int, rewardCount: Int, max: Int = SELECT_3_PICKS): Toggle {
        if (index !in 0 until rewardCount) return Toggle.Invalid
        if (index in selected) return Toggle.Changed(selected - index)
        if (selected.size >= max) return Toggle.Full
        return Toggle.Changed(selected + index)
    }

    /** True when [selected] is exactly [required] distinct, existing rewards. */
    fun complete(selected: Collection<Int>, rewardCount: Int, required: Int = SELECT_3_PICKS): Boolean =
        selected.size == required && selected.toSet().size == required && selected.all { it in 0 until rewardCount }

    /** A confirmation screen's single payout: the first [take] wins, every later one is refused. */
    class OneShot {
        private var taken = false
        fun take(): Boolean {
            if (taken) return false
            taken = true
            return true
        }
        val used: Boolean get() = taken
    }
}
