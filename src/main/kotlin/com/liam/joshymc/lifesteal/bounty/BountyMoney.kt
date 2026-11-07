package com.liam.joshymc.lifesteal.bounty

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Bounty money (issue #1401), Bukkit-free. Every bounty amount is a whole number of **cents** in a
 * `Long`: contributions, totals, payouts, refunds and statistics are added with exact integer
 * arithmetic (`Math.addExact`), never with floating point. The economy itself stores balances as a
 * `REAL` (EconomyManager), so the only conversion to `Double` is [toCoins] at the moment a balance is
 * changed; [MAX_CENTS] keeps that conversion exact to the cent (below 2^53 / 100 with room to spare).
 *
 * There is no gameplay maximum: [MAX_CENTS] ($10 trillion) is only the largest amount that can be
 * represented and moved safely, for one contribution and for one bounty's total.
 */
object BountyMoney {

    /** $10,000,000,000,000.00. A Double still represents every cent of every amount up to this exactly enough to round back. */
    const val MAX_CENTS: Long = 1_000_000_000_000_000L

    sealed interface Parsed {
        data class Ok(val cents: Long) : Parsed
        /** Not a number we accept (letters, NaN, Infinity, several dots, ...). */
        data object Invalid : Parsed
        data object Negative : Parsed
        data object Zero : Parsed
        /** More than two decimals (fractions of a cent). */
        data object TooPrecise : Parsed
        /** Above [MAX_CENTS]: can't be moved safely. */
        data object TooLarge : Parsed
    }

    private val SUFFIXES = linkedMapOf(
        "k" to BigDecimal("1000"),
        "m" to BigDecimal("1000000"),
        "b" to BigDecimal("1000000000"),
        "t" to BigDecimal("1000000000000"),
    )

    /** Plain digits with optional thousands separators and up to one decimal point. */
    private val NUMBER = Regex("^[0-9][0-9,]*(\\.[0-9]+)?$|^\\.[0-9]+$")

    /**
     * Parses a player-typed amount: `10000`, `10,000`, `$10000`, `25000.50`, `10k`, `1.5m`, `2b`, `1t`.
     * Exact decimal parsing (BigDecimal), so `0.1 + 0.2` problems and `1e400`/`NaN`/`Infinity` can't
     * reach a balance. Never rounds an amount up or down: fractions of a cent are refused.
     */
    fun parse(input: String?): Parsed {
        var text = input?.trim()?.lowercase() ?: return Parsed.Invalid
        if (text.startsWith("$")) text = text.substring(1).trim()
        if (text.startsWith("-")) {
            return if (parse(text.substring(1)) == Parsed.Invalid) Parsed.Invalid else Parsed.Negative
        }
        if (text.startsWith("+")) text = text.substring(1)
        if (text.isEmpty() || text.length > 40) return Parsed.Invalid
        val suffix = SUFFIXES.entries.firstOrNull { text.endsWith(it.key) }
        val digits = if (suffix != null) text.dropLast(suffix.key.length).trim() else text
        if (digits.isEmpty() || !NUMBER.matches(digits)) return Parsed.Invalid
        if (digits.contains(",") && !digits.substringBefore('.').matches(Regex("^[0-9]{1,3}(,[0-9]{3})*$"))) return Parsed.Invalid
        val value = try {
            BigDecimal(digits.replace(",", "")).multiply(suffix?.value ?: BigDecimal.ONE)
        } catch (_: NumberFormatException) {
            return Parsed.Invalid
        }
        return fromCoins(value)
    }

    /** Exact coins (any scale) to cents, refusing zero, negatives, sub-cent fractions and unsafe sizes. */
    fun fromCoins(value: BigDecimal): Parsed {
        if (value.signum() < 0) return Parsed.Negative
        if (value.signum() == 0) return Parsed.Zero
        val cents = value.movePointRight(2)
        if (cents.stripTrailingZeros().scale() > 0) return Parsed.TooPrecise
        if (cents > BigDecimal.valueOf(MAX_CENTS)) return Parsed.TooLarge
        return Parsed.Ok(cents.longValueExact())
    }

    /** A configured amount in coins (YAML number or string) to cents, or null when unusable. */
    fun configCents(raw: Any?): Long? {
        val decimal = when (raw) {
            is Int, is Long, is Short, is Byte -> BigDecimal.valueOf((raw as Number).toLong())
            is Double -> if (raw.isFinite()) BigDecimal.valueOf(raw) else return null
            is Float -> if (raw.isFinite()) BigDecimal.valueOf(raw.toDouble()) else return null
            is BigDecimal -> raw
            is java.math.BigInteger -> BigDecimal(raw)
            is String -> return (parse(raw) as? Parsed.Ok)?.cents
            else -> return null
        }
        return (fromCoins(decimal) as? Parsed.Ok)?.cents
    }

    /** Sum of two non-negative amounts; null instead of overflowing or passing [MAX_CENTS]. */
    fun add(a: Long, b: Long): Long? {
        if (a < 0 || b < 0) return null
        val sum = try { Math.addExact(a, b) } catch (_: ArithmeticException) { return null }
        return sum.takeIf { it <= MAX_CENTS }
    }

    /** Cents as the economy's `Double` coins, only at the moment a balance changes. */
    fun toCoins(cents: Long): Double {
        require(cents in 0..MAX_CENTS) { "amount out of range: $cents cents" }
        return BigDecimal.valueOf(cents).movePointLeft(2).toDouble()
    }

    /** `$10,000` for whole coins, `$10,000.50` otherwise. Exact (no floating point). */
    fun format(cents: Long): String {
        val negative = cents < 0
        val abs = BigDecimal.valueOf(cents).abs().movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY)
        val whole = abs.toBigInteger().toString().reversed().chunked(3).joinToString(",").reversed()
        val fraction = abs.remainder(BigDecimal.ONE).movePointRight(2).toInt()
        val body = if (fraction == 0) whole else "$whole.${fraction.toString().padStart(2, '0')}"
        return (if (negative) "-$" else "$") + body
    }
}
