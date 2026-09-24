package com.liam.joshymc.manager

import kotlin.math.abs

/**
 * Pure, stateless math for the unified progressive percentage-based stock pricing model, used
 * by every stock — player-created and server-owned (JOSH) alike. There is a single source of
 * truth for how a stock's price moves; only the seed price and starting supply differ per stock.
 *
 * Price steps in one direction at a time:
 * - Below `percentage-pricing-threshold` (default $1.00): each step moves by a flat
 *   `low-price-tick` (default $0.01), clamped so it never overshoots the threshold.
 * - At/above the threshold: each step moves by `price-step-percent` (default 1%) —
 *   `nextPrice = price * (1 + priceStepPercent)`.
 * - A stock sitting exactly at [MINIMUM_PRICE] (the absolute floor, default $0.001) jumps
 *   straight to `low-price-tick` on its next buy step rather than crawling through
 *   microscopic in-between values. [previousSellPrice] is the exact inverse of [nextBuyPrice]
 *   (division instead of multiplication above the threshold, subtraction instead of addition
 *   below it), so an immediate buy-then-sell retraces the same steps and nets ~$0.
 *
 * A pricing step does NOT execute one individual share — `sharesPerPriceStep` shares are
 * available at the current price before the price takes its next step. This is what makes
 * a trade's price impact scale with its size: a trade smaller than the remaining capacity at
 * the current price doesn't move the price at all, while a trade that exhausts many steps'
 * worth of capacity walks the ladder progressively, exactly like an order book. `sharesUsedAtPrice`
 * (persisted per-stock alongside `price`) is how much of the *current* step's capacity has
 * already been consumed — carrying this across separate trades (not just within one trade) is
 * what prevents splitting a large order into many small ones to dodge price impact.
 *
 * [computeBuy]/[computeSell] process one whole price step per loop iteration (never one share
 * at a time), bounded by [MAX_PRICE_STEPS_PER_TRADE] as a defensive cap — not a realistic
 * ceiling. Because dollar depth per step (`sharesPerPriceStep * price`) grows geometrically
 * once in the percentage zone, even an astronomically large trade only crosses a small number
 * of steps before its budget or share limit is exhausted, so this is cheap regardless of trade
 * size (never a per-share loop over billions/trillions of shares).
 */
object StockPricingEngine {

    /** Tiny floor to keep sharesOutstanding/holder shares from hitting exactly zero or negative. */
    const val EPSILON = 1e-6

    /**
     * Absolute floor for any stock's price, everywhere in the system (buy, sell, save, load,
     * display, admin actions). A stock is allowed to crash all the way down to this price, but
     * never to exactly $0, negative, NaN, or Infinity. Configurable via
     * `stock-market.minimum-stock-price`; this is only the fallback default.
     */
    const val MINIMUM_PRICE = 0.001

    /** Defensive iteration cap for [computeBuy]/[computeSell] — see class doc; not a realistic ceiling. */
    const val MAX_PRICE_STEPS_PER_TRADE = 5_000_000

    private const val FLOOR_TOLERANCE = 1e-9
    private const val THRESHOLD_TOLERANCE = 1e-9
    private const val EPSILON_SHARES = 1e-6
    private const val EPSILON_DOLLARS = 1e-6

    fun marketCap(price: Double, sharesOutstanding: Double): Double = price * sharesOutstanding

    data class BuyResult(
        val avgExecutionPrice: Double,
        val sharesMinted: Double,
        val newPrice: Double,
        val newSharesUsedAtPrice: Double,
    )

    data class SellResult(
        val avgExecutionPrice: Double,
        val sharesRemoved: Double,
        val newPrice: Double,
        val newSharesUsedAtPrice: Double,
    )

    /**
     * The price one pricing step above [price] — see the class doc for the low-tick vs.
     * percentage-step rule. Always finite, always >= [minimumPrice].
     */
    fun nextBuyPrice(
        price: Double,
        minimumPrice: Double,
        lowPriceTick: Double,
        percentageThreshold: Double,
        priceStepPercent: Double,
    ): Double {
        val p = price.coerceAtLeast(minimumPrice)
        val next = when {
            p <= minimumPrice + FLOOR_TOLERANCE -> lowPriceTick
            p < percentageThreshold - THRESHOLD_TOLERANCE -> (p + lowPriceTick).coerceAtMost(percentageThreshold)
            else -> p * (1.0 + priceStepPercent)
        }
        return if (isFiniteSafe(next)) next.coerceAtLeast(minimumPrice) else minimumPrice
    }

    /**
     * Exact inverse of [nextBuyPrice] — division instead of multiplication above the
     * threshold, subtraction instead of addition below it. Always finite, always
     * >= [minimumPrice] (a sell can never push a stock below the absolute floor).
     */
    fun previousSellPrice(
        price: Double,
        minimumPrice: Double,
        lowPriceTick: Double,
        percentageThreshold: Double,
        priceStepPercent: Double,
    ): Double {
        val p = price.coerceAtLeast(minimumPrice)
        val prev = if (p > percentageThreshold + THRESHOLD_TOLERANCE) {
            p / (1.0 + priceStepPercent)
        } else {
            p - lowPriceTick
        }
        return if (isFiniteSafe(prev)) prev.coerceAtLeast(minimumPrice) else minimumPrice
    }

    /**
     * Buy [dollarAmount] worth of shares against a stock currently at [price], with
     * [sharesUsedAtPrice] of the current step's [sharesPerPriceStep] capacity already consumed.
     * Fills the current price's remaining capacity first, then walks up one whole step at a
     * time (never per-share) until the budget runs out or [MAX_PRICE_STEPS_PER_TRADE] is hit.
     */
    fun computeBuy(
        price: Double,
        sharesUsedAtPrice: Double,
        dollarAmount: Double,
        sharesPerPriceStep: Double,
        minimumPrice: Double,
        lowPriceTick: Double,
        percentageThreshold: Double,
        priceStepPercent: Double,
    ): BuyResult {
        val stepSize = sharesPerPriceStep.coerceAtLeast(EPSILON_SHARES)
        var p = price.coerceAtLeast(minimumPrice)
        var used = sharesUsedAtPrice.coerceIn(0.0, stepSize)
        var remaining = dollarAmount.coerceAtLeast(0.0)
        var sharesMinted = 0.0
        var iterations = 0

        while (remaining > EPSILON_DOLLARS && iterations < MAX_PRICE_STEPS_PER_TRADE) {
            if (p <= 0.0) break
            val capacity = (stepSize - used).coerceAtLeast(0.0)
            if (capacity <= EPSILON_SHARES) {
                // This step is already fully bought — free pass-through to the next one.
                p = nextBuyPrice(p, minimumPrice, lowPriceTick, percentageThreshold, priceStepPercent)
                used = 0.0
                iterations++
                continue
            }

            val costToFill = capacity * p
            if (remaining < costToFill) {
                val afford = (remaining / p).coerceIn(0.0, capacity)
                used += afford
                sharesMinted += afford
                remaining -= afford * p
                break
            }

            sharesMinted += capacity
            remaining -= costToFill
            p = nextBuyPrice(p, minimumPrice, lowPriceTick, percentageThreshold, priceStepPercent)
            used = 0.0
            iterations++
        }

        val actualSpent = (dollarAmount - remaining).coerceAtLeast(0.0)
        val avgExecutionPrice = if (sharesMinted > EPSILON_SHARES) actualSpent / sharesMinted else p
        return BuyResult(avgExecutionPrice, sharesMinted, p, used)
    }

    /**
     * Sell [dollarAmount] worth of shares (already clamped by the caller to the holder's
     * current market value) against a stock currently at [price] / [sharesUsedAtPrice]. Unwinds
     * the current price's used capacity first, then walks down one whole step at a time until
     * the target payout is reached, the seller's [holderShares] runs out, the market hits the
     * absolute floor, or [MAX_PRICE_STEPS_PER_TRADE] is hit.
     */
    fun computeSell(
        price: Double,
        sharesUsedAtPrice: Double,
        dollarAmount: Double,
        holderShares: Double,
        sharesPerPriceStep: Double,
        minimumPrice: Double,
        lowPriceTick: Double,
        percentageThreshold: Double,
        priceStepPercent: Double,
    ): SellResult {
        val stepSize = sharesPerPriceStep.coerceAtLeast(EPSILON_SHARES)
        var p = price.coerceAtLeast(minimumPrice)
        var used = sharesUsedAtPrice.coerceIn(0.0, stepSize)
        var remaining = dollarAmount.coerceAtLeast(0.0)
        var sharesRemoved = 0.0
        var iterations = 0
        val shareLimit = holderShares.coerceAtLeast(0.0)

        // Bounding the loop itself by `shareLimit` (rather than clamping `sharesRemoved` after
        // the fact) keeps the resulting market state consistent: if the seller runs out of
        // shares mid-step, the step is only partially unwound, not unwound as if the full
        // (unclamped) amount had actually left circulation.
        while (remaining > EPSILON_DOLLARS && sharesRemoved < shareLimit - EPSILON_SHARES && iterations < MAX_PRICE_STEPS_PER_TRADE) {
            if (used <= EPSILON_SHARES) {
                if (p <= minimumPrice + FLOOR_TOLERANCE) break // already at the absolute floor
                p = previousSellPrice(p, minimumPrice, lowPriceTick, percentageThreshold, priceStepPercent)
                used = stepSize
                iterations++
                continue
            }
            if (p <= 0.0) break

            val shareCapRemaining = (shareLimit - sharesRemoved).coerceAtLeast(0.0)
            val affordableByBudget = remaining / p
            val sharesHere = minOf(used, shareCapRemaining, affordableByBudget).coerceAtLeast(0.0)

            sharesRemoved += sharesHere
            remaining -= sharesHere * p
            used -= sharesHere
            iterations++
            // Deliberately no eager drop here even if `used` just hit exactly 0 — the next
            // iteration's top-of-loop check handles that lazily. Dropping eagerly would fire
            // even when the loop is about to terminate (budget/share-limit exhausted exactly on
            // a step boundary), overshooting one extra step past where the trade actually
            // stopped and breaking exact buy-then-sell reversibility.
        }

        val actualProceeds = (dollarAmount - remaining).coerceAtLeast(0.0)
        val avgExecutionPrice = if (sharesRemoved > EPSILON_SHARES) actualProceeds / sharesRemoved else p
        return SellResult(avgExecutionPrice, sharesRemoved, p, used)
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
