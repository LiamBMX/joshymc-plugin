package com.liam.joshymc.manager

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tanh

/**
 * Pure, stateless math for the player-driven stock market bonding curve.
 *
 * Deliberately free of any DB/Bukkit calls so the pricing model can be reasoned
 * about (and unit-tested) in isolation from persistence/game concerns.
 *
 * Model: this is a bonding curve (shares mint on buy / burn on sell), not an
 * order book — there's no counterparty, the "market" is the curve itself. A
 * trade of dollar value D against a stock with `price` and `sharesOutstanding`:
 *
 *   marketCap = price * sharesOutstanding
 *   liquidity = max(marketCap, MIN_LIQUIDITY_FLOOR)
 *   impactFraction = maxSingleTradeImpact * tanh(D / liquidity)
 *
 * tanh saturates below 1 as D grows, so impactFraction is always strictly less
 * than maxSingleTradeImpact — this is what bounds "maximum single trade impact"
 * while still giving diminishing (not linear) returns for huge trades.
 *
 * Buys execute (and mint shares) at the *average* of the pre- and post-trade
 * price, not the final price; the same holds for sells. Because the execution
 * price is always strictly worse than the theoretical "spot" price in the
 * direction of the trade, an immediate buy-then-sell of the same dollar amount
 * always nets a loss to the trader (classic AMM slippage) — this is what
 * prevents the free-money buy/sell round-trip exploit called out in the issue.
 */
object StockPricingEngine {

    /** Tiny floor to keep sharesOutstanding/holder shares from hitting exactly zero or negative. */
    const val EPSILON = 1e-6

    /**
     * Absolute floor for any stock's price, everywhere in the system (buy, sell, save, load,
     * display, admin actions). A stock hitting this price represents it being fully depleted —
     * it must never reach $0 or negative, which would let players buy shares for free and
     * dupe money on a later price recovery.
     */
    const val MINIMUM_PRICE = 10.0

    fun marketCap(price: Double, sharesOutstanding: Double): Double = price * sharesOutstanding

    fun liquidity(marketCap: Double, minLiquidityFloor: Double): Double = max(marketCap, minLiquidityFloor)

    /**
     * Fraction of price movement this trade causes, strictly in (0, maxSingleTradeImpact)
     * for any finite positive dollarAmount.
     */
    fun impactFraction(dollarAmount: Double, liquidity: Double, maxSingleTradeImpact: Double): Double {
        if (liquidity <= 0.0) return 0.0
        return maxSingleTradeImpact * tanh(dollarAmount / liquidity)
    }

    data class BuyResult(
        val avgExecutionPrice: Double,
        val sharesMinted: Double,
        val newPrice: Double,
        val newSharesOutstanding: Double,
        val impactFraction: Double,
    )

    data class SellResult(
        val avgExecutionPrice: Double,
        val sharesRemoved: Double,
        val newPrice: Double,
        val newSharesOutstanding: Double,
        val realizedPL: Double,
        val costBasisRemoved: Double,
        val impactFraction: Double,
    )

    /**
     * Compute a buy of dollar value [dollarAmount] against a stock currently at [price]
     * with [sharesOutstanding] shares in circulation.
     */
    fun computeBuy(
        price: Double,
        sharesOutstanding: Double,
        dollarAmount: Double,
        maxSingleTradeImpact: Double,
        minLiquidityFloor: Double,
    ): BuyResult {
        val marketCap = marketCap(price, sharesOutstanding)
        val liquidity = liquidity(marketCap, minLiquidityFloor)
        val impact = impactFraction(dollarAmount, liquidity, maxSingleTradeImpact)

        val avgExecutionPrice = (price * (1.0 + impact / 2.0)).coerceAtLeast(MINIMUM_PRICE)
        val sharesMinted = if (avgExecutionPrice > 0.0) dollarAmount / avgExecutionPrice else 0.0
        val newPrice = (price * (1.0 + impact)).coerceAtLeast(MINIMUM_PRICE)
        val newSharesOutstanding = sharesOutstanding + sharesMinted

        return BuyResult(
            avgExecutionPrice = avgExecutionPrice,
            sharesMinted = sharesMinted,
            newPrice = newPrice,
            newSharesOutstanding = newSharesOutstanding,
            impactFraction = impact,
        )
    }

    /**
     * Compute a sell of dollar value [dollarAmount] (already clamped by the caller to the
     * holder's current market value) against a stock currently at [price].
     *
     * [holderShares] / [holderCostBasis] are the seller's PRE-trade position, used to compute
     * weighted-average-cost-basis realized P/L for a partial (or full) sale.
     */
    fun computeSell(
        price: Double,
        sharesOutstanding: Double,
        dollarAmount: Double,
        holderShares: Double,
        holderCostBasis: Double,
        maxSingleTradeImpact: Double,
        minLiquidityFloor: Double,
    ): SellResult {
        val marketCap = marketCap(price, sharesOutstanding)
        val liquidity = liquidity(marketCap, minLiquidityFloor)
        val impact = impactFraction(dollarAmount, liquidity, maxSingleTradeImpact)

        val avgExecutionPrice = (price * (1.0 - impact / 2.0)).coerceAtLeast(MINIMUM_PRICE)
        var sharesRemoved = if (avgExecutionPrice > 0.0) dollarAmount / avgExecutionPrice else 0.0
        // Floating point safety: never remove more than the holder actually owns.
        sharesRemoved = sharesRemoved.coerceAtMost(holderShares).coerceAtLeast(0.0)

        val newPrice = (price * (1.0 - impact)).coerceAtLeast(MINIMUM_PRICE)
        val newSharesOutstanding = max(sharesOutstanding - sharesRemoved, EPSILON)

        val proportion = if (holderShares > 0.0) (sharesRemoved / holderShares).coerceIn(0.0, 1.0) else 0.0
        val costBasisRemoved = holderCostBasis * proportion
        val realizedPL = (sharesRemoved * avgExecutionPrice) - costBasisRemoved

        return SellResult(
            avgExecutionPrice = avgExecutionPrice,
            sharesRemoved = sharesRemoved,
            newPrice = newPrice,
            newSharesOutstanding = newSharesOutstanding,
            realizedPL = realizedPL,
            costBasisRemoved = costBasisRemoved,
            impactFraction = impact,
        )
    }

    enum class ActivityLevel(val displayName: String) {
        VERY_HIGH("Very High"),
        HIGH("High"),
        MODERATE("Moderate"),
        LOW("Low"),
        INACTIVE("Inactive"),
    }

    /** Classifies 24h (or configured period) trading volume into an activity tier. */
    fun classifyActivity(
        volume: Double,
        veryHighThreshold: Double,
        highThreshold: Double,
        moderateThreshold: Double,
        lowThreshold: Double,
    ): ActivityLevel = when {
        volume >= veryHighThreshold -> ActivityLevel.VERY_HIGH
        volume >= highThreshold -> ActivityLevel.HIGH
        volume >= moderateThreshold -> ActivityLevel.MODERATE
        volume >= lowThreshold -> ActivityLevel.LOW
        else -> ActivityLevel.INACTIVE
    }

    enum class TrendLevel(val displayName: String, val arrow: String) {
        GROWING("Growing", "↑"),
        STABLE("Stable", "→"),
        DECAYING("Decaying", "↓"),
    }

    /** Classifies a % price change over the activity period into a trend tier. */
    fun classifyTrend(percentChange: Double, stableBandPercent: Double): TrendLevel = when {
        percentChange > stableBandPercent -> TrendLevel.GROWING
        percentChange < -stableBandPercent -> TrendLevel.DECAYING
        else -> TrendLevel.STABLE
    }

    /** True if [value] is safe to use in further math (not NaN/Infinite). */
    fun isFiniteSafe(value: Double): Boolean = !value.isNaN() && value.isFinite()

    /** True if [value] is a strictly positive, finite number below a sane overflow guard. */
    fun isSafePositiveAmount(value: Double, maxAllowed: Double = 1e15): Boolean {
        return isFiniteSafe(value) && value > 0.0 && value <= maxAllowed && abs(value) <= maxAllowed
    }
}
