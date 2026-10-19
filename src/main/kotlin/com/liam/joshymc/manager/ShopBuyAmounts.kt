package com.liam.joshymc.manager

/**
 * Amount rules for the `/shop` buy flow (issue #1259), kept Bukkit-free so the screens and the
 * purchase always agree. The confirmation screen picks 1..[MAX_SINGLE]; anything bigger goes
 * through the stack screen, which only offers whole stacks, 1..[MAX_STACKS] of them.
 */
object ShopBuyAmounts {
    const val MIN = 1
    const val MAX_SINGLE = 64
    const val STACK_SIZE = 64
    /** 10 stacks = 640, the same ceiling the old quantity screen had. */
    const val MAX_STACKS = 10

    /** The confirmation screen's amount after a pane click, always within 1..64. */
    fun adjust(current: Int, delta: Int): Int = clampSingle(current + delta)

    fun clampSingle(amount: Int): Int = amount.coerceIn(MIN, MAX_SINGLE)

    /** Items bought for [stacks] full stacks, or null when that isn't an offered stack count. */
    fun stackTotal(stacks: Int): Int? = if (stacks in 1..MAX_STACKS) stacks * STACK_SIZE else null

    /** Every amount a purchase may be made for: 1..64 singly, or a whole number of stacks. */
    fun isValidPurchase(amount: Int): Boolean =
        amount in MIN..MAX_SINGLE || (amount % STACK_SIZE == 0 && stackTotal(amount / STACK_SIZE) != null)

    fun totalPrice(unitPrice: Double, amount: Int): Double = unitPrice * amount
}
