package com.liam.joshymc.manager

import kotlin.math.max
import kotlin.math.pow

/**
 * Pure, stateless math for server-owned stock pricing (e.g. JOSH).
 *
 * Unlike the player-driven bonding curve in [StockPricingEngine] (price impact derived
 * from `tanh(dollarAmount / liquidity)`), a server-owned stock's price is a direct,
 * nonlinear function of circulating supply (`shares_outstanding`):
 *
 *   price(supply) = floorPrice + curveStrength * (supply / SUPPLY_UNIT)^curveExponent
 *
 * clamped to `[minimumPrice, maximumPrice]`. Buys mint shares (increasing circulating
 * supply) and sells burn shares (decreasing it) — same "no counterparty, no order book"
 * model as the player market. Because price is a pure function of supply, the dollar
 * cost/proceeds of a trade is the definite integral of `price(s)` between the pre- and
 * post-trade supply, so a purchase or sale of any size prices every "share" along the
 * way at its own point on the curve without iterating per-share:
 *
 *   totalCost(s) = floorPrice * s + curveStrength/(SUPPLY_UNIT^e * (e+1)) * s^(e+1)   [unclamped region]
 *
 * Above the supply where the raw curve would exceed `maximumPrice`, the curve flattens
 * to a straight line at `maximumPrice` (both for safety at extreme supply and so the
 * integral stays a closed form). Solving `totalCost(s) = target` for `s` is closed-form
 * in the clamped (flat) region and bounded bisection (a fixed ~100 iterations,
 * independent of trade size) in the unclamped power-curve region.
 */
object ServerStockPricingEngine {

    private const val SUPPLY_UNIT = 1_000_000.0
    private const val MIN_SANE_PRICE = 0.01
    private const val BISECTION_ITERATIONS = 100
    private const val MAX_DOUBLING_STEPS = 200

    data class BuyResult(
        val avgExecutionPrice: Double,
        val sharesMinted: Double,
        val newPrice: Double,
        val newSharesOutstanding: Double,
    )

    data class SellResult(
        val avgExecutionPrice: Double,
        val sharesRemoved: Double,
        val newPrice: Double,
        val newSharesOutstanding: Double,
        val realizedPL: Double,
        val costBasisRemoved: Double,
    )

    private fun floorPrice(basePrice: Double, minimumPrice: Double): Double =
        max(basePrice.coerceAtLeast(MIN_SANE_PRICE), minimumPrice.coerceAtLeast(MIN_SANE_PRICE))

    private fun ceilingPrice(floor: Double, maximumPrice: Double): Double = max(maximumPrice, floor)

    /** Instantaneous per-share price at [supply] shares circulating. */
    fun priceAtSupply(
        supply: Double,
        basePrice: Double,
        curveStrength: Double,
        curveExponent: Double,
        minimumPrice: Double,
        maximumPrice: Double,
    ): Double {
        val floor = floorPrice(basePrice, minimumPrice)
        val cap = ceilingPrice(floor, maximumPrice)
        val s = max(supply, 0.0)
        val k = curveStrength.coerceAtLeast(0.0)
        val e = curveExponent.coerceAtLeast(0.01)
        val raw = floor + k * (s / SUPPLY_UNIT).pow(e)
        val safe = if (StockPricingEngine.isFiniteSafe(raw)) raw else cap
        return safe.coerceIn(floor, cap)
    }

    /** Supply at which the unclamped curve first reaches [cap]; Double.MAX_VALUE if it never does. */
    private fun capSupply(floor: Double, curveStrength: Double, curveExponent: Double, cap: Double): Double {
        val k = curveStrength.coerceAtLeast(0.0)
        if (k <= 0.0 || cap <= floor) return Double.MAX_VALUE
        val ratio = (cap - floor) / k
        if (!StockPricingEngine.isFiniteSafe(ratio) || ratio <= 0.0) return Double.MAX_VALUE
        val e = curveExponent.coerceAtLeast(0.01)
        val supply = ratio.pow(1.0 / e) * SUPPLY_UNIT
        return if (StockPricingEngine.isFiniteSafe(supply) && supply > 0.0) supply else Double.MAX_VALUE
    }

    /** Definite integral of the power term (price(s) minus its flat floor) from 0 to [supply]. */
    private fun powerIntegral(supply: Double, curveStrength: Double, curveExponent: Double): Double {
        val k = curveStrength.coerceAtLeast(0.0)
        if (k <= 0.0 || supply <= 0.0) return 0.0
        val e = curveExponent.coerceAtLeast(0.01)
        val units = supply / SUPPLY_UNIT
        val value = k * units.pow(e + 1.0) * SUPPLY_UNIT / (e + 1.0)
        return if (StockPricingEngine.isFiniteSafe(value)) value else Double.MAX_VALUE
    }

    /** Total dollar cost to go from zero circulating supply to [supply] shares, along the clamped curve. */
    private fun totalCost(supply: Double, floor: Double, curveStrength: Double, curveExponent: Double, cap: Double): Double {
        val s = max(supply, 0.0)
        val scap = capSupply(floor, curveStrength, curveExponent, cap)
        return if (s <= scap) {
            floor * s + powerIntegral(s, curveStrength, curveExponent)
        } else {
            val capCost = floor * scap + powerIntegral(scap, curveStrength, curveExponent)
            capCost + cap * (s - scap)
        }
    }

    /** Inverse of [totalCost]: the supply at which cumulative cost equals [target]. */
    private fun invertTotalCost(target: Double, floor: Double, curveStrength: Double, curveExponent: Double, cap: Double): Double {
        if (target <= 0.0) return 0.0

        val scap = capSupply(floor, curveStrength, curveExponent, cap)
        val capCost = if (scap == Double.MAX_VALUE) Double.MAX_VALUE else totalCost(scap, floor, curveStrength, curveExponent, cap)

        if (scap == Double.MAX_VALUE || target <= capCost) {
            // Unclamped power-curve region — solve via bounded bisection (fixed iteration
            // count, independent of trade size; never loops per-share).
            var lo = 0.0
            var hi = if (scap == Double.MAX_VALUE) max(SUPPLY_UNIT, target / floor) else scap
            var guard = 0
            while (totalCost(hi, floor, curveStrength, curveExponent, cap) < target && guard < MAX_DOUBLING_STEPS) {
                hi *= 2.0
                guard++
            }
            repeat(BISECTION_ITERATIONS) {
                val mid = (lo + hi) / 2.0
                if (totalCost(mid, floor, curveStrength, curveExponent, cap) < target) lo = mid else hi = mid
            }
            return (lo + hi) / 2.0
        }

        // Beyond the cap the curve is flat at `cap` — exact closed form, no search needed.
        return scap + (target - capCost) / cap
    }

    /**
     * Compute a buy of dollar value [dollarAmount] against a server-owned stock currently
     * at [currentSupply] shares circulating.
     */
    fun computeBuy(
        currentSupply: Double,
        dollarAmount: Double,
        basePrice: Double,
        curveStrength: Double,
        curveExponent: Double,
        minimumPrice: Double,
        maximumPrice: Double,
    ): BuyResult {
        val floor = floorPrice(basePrice, minimumPrice)
        val cap = ceilingPrice(floor, maximumPrice)
        val s0 = max(currentSupply, 0.0)

        val startCost = totalCost(s0, floor, curveStrength, curveExponent, cap)
        val targetCost = startCost + max(dollarAmount, 0.0)
        val s1 = invertTotalCost(targetCost, floor, curveStrength, curveExponent, cap).coerceAtLeast(s0)

        val sharesMinted = s1 - s0
        val newSupply = s0 + sharesMinted
        val newPrice = priceAtSupply(newSupply, basePrice, curveStrength, curveExponent, minimumPrice, maximumPrice)
        val avgExecutionPrice = if (sharesMinted > StockPricingEngine.EPSILON) {
            dollarAmount / sharesMinted
        } else {
            priceAtSupply(s0, basePrice, curveStrength, curveExponent, minimumPrice, maximumPrice)
        }

        return BuyResult(avgExecutionPrice, sharesMinted, newPrice, newSupply)
    }

    /**
     * Compute a sell of dollar value [dollarAmount] (already clamped by the caller to the
     * holder's current market value) against a server-owned stock currently at [currentSupply].
     *
     * [holderShares] / [holderCostBasis] are the seller's PRE-trade position, used to compute
     * weighted-average-cost-basis realized P/L for a partial (or full) sale.
     */
    fun computeSell(
        currentSupply: Double,
        dollarAmount: Double,
        holderShares: Double,
        holderCostBasis: Double,
        basePrice: Double,
        curveStrength: Double,
        curveExponent: Double,
        minimumPrice: Double,
        maximumPrice: Double,
    ): SellResult {
        val floor = floorPrice(basePrice, minimumPrice)
        val cap = ceilingPrice(floor, maximumPrice)
        val s0 = max(currentSupply, 0.0)

        val startCost = totalCost(s0, floor, curveStrength, curveExponent, cap)
        val targetCost = (startCost - max(dollarAmount, 0.0)).coerceAtLeast(0.0)
        val rawS1 = invertTotalCost(targetCost, floor, curveStrength, curveExponent, cap).coerceIn(0.0, s0)

        var sharesRemoved = (s0 - rawS1).coerceAtLeast(0.0)
        // Floating point / curve-inversion safety: never remove more than the holder actually owns.
        sharesRemoved = sharesRemoved.coerceAtMost(holderShares)

        val newSupply = max(s0 - sharesRemoved, StockPricingEngine.EPSILON)
        val newPrice = priceAtSupply(newSupply, basePrice, curveStrength, curveExponent, minimumPrice, maximumPrice)
        val avgExecutionPrice = if (sharesRemoved > StockPricingEngine.EPSILON) {
            dollarAmount / sharesRemoved
        } else {
            priceAtSupply(s0, basePrice, curveStrength, curveExponent, minimumPrice, maximumPrice)
        }

        val proportion = if (holderShares > 0.0) (sharesRemoved / holderShares).coerceIn(0.0, 1.0) else 0.0
        val costBasisRemoved = holderCostBasis * proportion
        val realizedPL = (sharesRemoved * avgExecutionPrice) - costBasisRemoved

        return SellResult(avgExecutionPrice, sharesRemoved, newPrice, newSupply, realizedPL, costBasisRemoved)
    }
}
