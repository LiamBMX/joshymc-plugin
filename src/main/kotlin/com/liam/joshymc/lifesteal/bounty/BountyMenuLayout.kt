package com.liam.joshymc.lifesteal.bounty

/**
 * Slots of the Lifesteal bounty menus (issue #1401), Bukkit-free. Bottom-row navigation follows
 * GuiStyle (`prev = size - 7`, `close = size - 5`, `next = size - 3`, back = `size - 9`).
 */
object BountyMenuLayout {
    /** Active bounty list and leaderboards: 54 slots, entries in the top five rows. */
    const val LIST_SIZE = 54
    const val PER_PAGE = 45
    const val EMPTY_SLOT = 22
    const val LEADERBOARDS_SLOT = 45
    const val STATS_SLOT = 46
    const val PREV_SLOT = 47
    const val CLOSE_SLOT = 49
    const val NEXT_SLOT = 51
    const val PLACE_SLOT = 52
    const val INFO_SLOT = 53

    /** Leaderboard: Back 45, Previous 47, Richest 48, Close 49, Most Successful 50, Next 51. */
    const val BOARD_BACK_SLOT = 45
    const val BOARD_EARNED_SLOT = 48
    const val BOARD_CLAIMS_SLOT = 50

    /** Contribution amount screen: 36 slots. */
    const val CONTRIBUTE_SIZE = 36
    const val TARGET_SLOT = 4
    const val CUSTOM_SLOT = 22
    const val CONTRIBUTE_BACK_SLOT = 27
    const val CONTRIBUTE_CLOSE_SLOT = 31

    /** Confirmation screen: 27 slots. */
    const val CONFIRM_SIZE = 27
    const val CONFIRM_SLOT = 11
    const val SUMMARY_SLOT = 13
    const val CANCEL_SLOT = 15
    const val CONFIRM_CLOSE_SLOT = 22

    const val MAX_PRESETS = 7

    /** Preset buttons centred in the second row (slots 10..16). */
    fun presetSlots(count: Int): List<Int> {
        val n = count.coerceIn(0, MAX_PRESETS)
        val start = 10 + (MAX_PRESETS - n) / 2
        return List(n) { start + it }
    }

    /** The presets a player is offered: at least the minimum, at most [MAX_PRESETS], smallest first. */
    fun presets(configured: List<Long>, minimumCents: Long): List<Long> {
        val usable = configured.filter { it >= minimumCents && it <= BountyMoney.MAX_CENTS }.distinct().sorted()
        return (if (usable.isEmpty()) listOf(minimumCents) else usable).take(MAX_PRESETS)
    }
}
