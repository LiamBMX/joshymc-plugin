package com.liam.joshymc.manager

import kotlin.math.max

/**
 * Pure, stateless math for the price-level ("order book style") pricing model used by
 * server-owned stocks (e.g. JOSH).
 *
 * A fixed number of shares (`sharesPerLevel`) is available at each price level. Level L's
 * price is `basePrice + L * priceIncreasePerLevel`. A buy consumes shares from the current
 * level upward; once a level's shares are fully bought the market moves to the next (more
 * expensive) level. A sell reverses through the exact same levels, cheapest-last — once a
 * level's shares are fully unwound the market moves back down to the previous (cheaper)
 * level. Because both directions walk the identical ladder, an immediate buy-then-sell of
 * the same shares is reversible up to floating-point rounding (no free-money round trip).
 *
 * State is the pair `(level, sharesUsedInLevel)`, NOT a single circulating-supply number —
 * the two are almost equivalent (`supply = level * sharesPerLevel + sharesUsedInLevel`), but
 * at an exact level boundary a buy and a sell land on opposite sides of it: a buy that
 * exactly fills a level advances to the next level at 0 used, while a sell that exactly
 * unwinds a level drops to the previous level at `sharesPerLevel` used (see the "Level
 * Transition" / "Example Sell" cases in the originating issue). Both [computeBuy] and
 * [computeSell] accept and return a `(level, used)` pair for this reason; either function
 * can be safely re-entered with the other's output as its input (a fresh empty level with
 * `used = 0`, or a just-vacated level with `used = sharesPerLevel`) without special-casing
 * — both loops treat "nothing left to take from the current level" as a free pass-through
 * to the neighboring level rather than stopping early.
 *
 * Processes one price level per loop iteration (never one share at a time), so a lump-sum
 * trade prices each level it crosses individually instead of the whole order at the
 * starting level's price. [MAX_LEVELS_PER_TRADE] is a generous safety cap, not a realistic
 * ceiling: with the default config (1,000,000 shares/level, $1/level) even a $30T purchase
 * crosses under 10,000 levels.
 */
object PriceLevelPricingEngine {

    /** Absolute floor for the base (level-0) price — see [StockPricingEngine.MINIMUM_PRICE]. */
    private const val MIN_SANE_PRICE = StockPricingEngine.MINIMUM_PRICE
    private const val EPSILON_SHARES = 1e-6
    private const val EPSILON_DOLLARS = 1e-6
    private const val EPSILON_LEVEL = 1e-9

    /** Defensive iteration cap — see class doc. If ever hit, the trade stops early rather than hanging. */
    private const val MAX_LEVELS_PER_TRADE = 5_000_000

    data class BuyResult(
        val avgExecutionPrice: Double,
        val sharesMinted: Double,
        val newPrice: Double,
        val newLevel: Double,
        val newUsedInLevel: Double,
        val newSharesOutstanding: Double,
    )

    data class SellResult(
        val avgExecutionPrice: Double,
        val sharesRemoved: Double,
        val newPrice: Double,
        val newLevel: Double,
        val newUsedInLevel: Double,
        val newSharesOutstanding: Double,
        val realizedPL: Double,
        val costBasisRemoved: Double,
    )

    private fun safeSharesPerLevel(sharesPerLevel: Double): Double = sharesPerLevel.coerceAtLeast(1.0)
    private fun safeIncrease(increasePerLevel: Double): Double = increasePerLevel.coerceAtLeast(0.0)
    private fun safeBase(basePrice: Double): Double = basePrice.coerceAtLeast(MIN_SANE_PRICE)

    /** Price of price level [level] (0-indexed; always a whole number in practice). */
    fun priceForLevel(level: Double, basePrice: Double, increasePerLevel: Double): Double {
        val raw = safeBase(basePrice) + max(level, 0.0) * safeIncrease(increasePerLevel)
        return if (StockPricingEngine.isFiniteSafe(raw)) raw else safeBase(basePrice)
    }

    /** Current per-share price given the market's exact `(level, usedInLevel)` position. */
    fun currentPrice(level: Double, basePrice: Double, increasePerLevel: Double): Double =
        priceForLevel(level, basePrice, increasePerLevel)

    /**
     * Migration helper only — derives a `(level, usedInLevel)` pair from a legacy scalar
     * circulating-supply value, per `currentLevel = floor(supply / sharesPerLevel)` /
     * `sharesUsedInCurrentLevel = supply % sharesPerLevel`. Not used on the normal buy/sell
     * path (see class doc for why the pair, not the scalar, is the source of truth there).
     */
    fun levelAndUsedFromSupply(supply: Double, sharesPerLevel: Double): Pair<Double, Double> {
        val q = safeSharesPerLevel(sharesPerLevel)
        val s = max(supply, 0.0)
        val level = kotlin.math.floor(s / q)
        val used = (s - level * q).coerceIn(0.0, q)
        return level to used
    }

    /**
     * Buy [dollarAmount] worth of shares starting from `(currentLevel, currentUsedInLevel)`.
     * Clears the current level's remaining shares first, then walks up one full level at a
     * time (never per-share) until the budget runs out or [MAX_LEVELS_PER_TRADE] is hit.
     */
    fun computeBuy(
        currentLevel: Double,
        currentUsedInLevel: Double,
        dollarAmount: Double,
        basePrice: Double,
        sharesPerLevel: Double,
        increasePerLevel: Double,
    ): BuyResult {
        val q = safeSharesPerLevel(sharesPerLevel)
        val b = basePrice
        val i = increasePerLevel

        var level = max(currentLevel, 0.0)
        var used = currentUsedInLevel.coerceIn(0.0, q)
        var remaining = max(dollarAmount, 0.0)
        var sharesMinted = 0.0
        var iterations = 0

        while (remaining > EPSILON_DOLLARS && iterations < MAX_LEVELS_PER_TRADE) {
            val price = priceForLevel(level, b, i)
            if (price <= 0.0) break

            val remainInLevel = (q - used).coerceAtLeast(0.0)
            if (remainInLevel <= EPSILON_SHARES) {
                // This level is already fully bought — free pass-through to the next one.
                level += 1.0
                used = 0.0
                iterations++
                continue
            }

            val costToClear = remainInLevel * price
            if (remaining < costToClear) {
                val afford = (remaining / price).coerceIn(0.0, remainInLevel)
                used += afford
                sharesMinted += afford
                remaining -= afford * price
                break
            }

            sharesMinted += remainInLevel
            remaining -= costToClear
            level += 1.0
            used = 0.0
            iterations++
        }

        val newPrice = priceForLevel(level, b, i)
        val actualSpent = (dollarAmount - remaining).coerceAtLeast(0.0)
        val avgExecutionPrice = if (sharesMinted > EPSILON_SHARES) actualSpent / sharesMinted else newPrice
        val newSupply = level * q + used

        return BuyResult(avgExecutionPrice, sharesMinted, newPrice, level, used, newSupply)
    }

    /**
     * Sell [dollarAmount] worth of shares (already clamped by the caller to the holder's
     * position) starting from `(currentLevel, currentUsedInLevel)`. Unwinds the current
     * level's used shares first, then walks down one full level at a time until the target
     * payout is reached, the market hits level 0, or [MAX_LEVELS_PER_TRADE] is hit.
     *
     * [holderShares] / [holderCostBasis] are the seller's PRE-trade position, used to compute
     * weighted-average-cost-basis realized P/L for a partial (or full) sale.
     */
    fun computeSell(
        currentLevel: Double,
        currentUsedInLevel: Double,
        dollarAmount: Double,
        holderShares: Double,
        holderCostBasis: Double,
        basePrice: Double,
        sharesPerLevel: Double,
        increasePerLevel: Double,
    ): SellResult {
        val q = safeSharesPerLevel(sharesPerLevel)
        val b = basePrice
        val i = increasePerLevel

        var level = max(currentLevel, 0.0)
        var used = currentUsedInLevel.coerceIn(0.0, q)
        var remaining = max(dollarAmount, 0.0)
        var sharesRemoved = 0.0
        var iterations = 0
        val shareLimit = max(holderShares, 0.0)

        // Bounding the loop itself by `shareLimit` (rather than clamping `sharesRemoved`
        // after the fact) keeps the resulting market state consistent: if the holder runs
        // out of shares mid-level, the level is only partially unwound, not unwound as if
        // the full (unclamped) amount had actually been sold out of circulation.
        while (remaining > EPSILON_DOLLARS && sharesRemoved < shareLimit - EPSILON_SHARES && iterations < MAX_LEVELS_PER_TRADE) {
            if (used <= EPSILON_SHARES) {
                // Nothing left to sell in this level — drop to the previous (cheaper) one,
                // or stop if we're already at the floor.
                if (level <= EPSILON_LEVEL) break
                level -= 1.0
                used = q
                iterations++
                continue
            }

            val price = priceForLevel(level, b, i)
            if (price <= 0.0) break

            val shareCapRemaining = (shareLimit - sharesRemoved).coerceAtLeast(0.0)
            val affordableByBudget = remaining / price
            val sharesHere = minOf(used, shareCapRemaining, affordableByBudget).coerceAtLeast(0.0)

            sharesRemoved += sharesHere
            remaining -= sharesHere * price
            used -= sharesHere
            iterations++

            if (used <= EPSILON_SHARES && level > EPSILON_LEVEL) {
                // Fully unwound this level — eagerly drop to the previous (cheaper) one so
                // the resulting state is correct even if this was also the final step (a
                // sell landing exactly on a boundary prefers the "level below, fully
                // filled" representation — see class doc).
                level -= 1.0
                used = q
            }
        }

        val newPrice = priceForLevel(level, b, i)
        val actualProceeds = (dollarAmount - remaining).coerceAtLeast(0.0)
        val avgExecutionPrice = if (sharesRemoved > EPSILON_SHARES) actualProceeds / sharesRemoved else newPrice
        val newSupply = level * q + used

        val proportion = if (holderShares > 0.0) (sharesRemoved / holderShares).coerceIn(0.0, 1.0) else 0.0
        val costBasisRemoved = holderCostBasis * proportion
        val realizedPL = (sharesRemoved * avgExecutionPrice) - costBasisRemoved

        return SellResult(
            avgExecutionPrice, sharesRemoved, newPrice, level, used, newSupply, realizedPL, costBasisRemoved,
        )
    }
}
