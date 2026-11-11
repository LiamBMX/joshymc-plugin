package com.liam.joshymc.crate

import java.util.Locale

/**
 * How a reward's configured weight is shown as a chance (issue #1514), kept Bukkit-free.
 *
 * The chance is exactly `weight / total weight` of the crate, the same numbers the weighted draw
 * uses; nothing here is ever fed back into a draw. Formatting uses [Locale.ROOT] so a server
 * locale can't turn "2.5%" into "2,5%".
 */
object CrateOdds {

    /** The chance in percent, or null when it can't be computed (no or negative weights). */
    fun percent(weight: Int, totalWeight: Int): Double? =
        if (totalWeight <= 0 || weight < 0) null else weight * 100.0 / totalWeight

    /** "25%", "12.5%", "0.25%", "<0.01%". */
    fun formatPercent(percent: Double): String = when {
        percent <= 0.0 -> "0%"
        percent >= 100.0 -> "100%"
        percent < 0.01 -> "<0.01%"
        percent < 1.0 -> trim(String.format(Locale.ROOT, "%.2f", percent)) + "%"
        else -> trim(String.format(Locale.ROOT, "%.1f", percent)) + "%"
    }

    /** "1 in 40" for chances below 10%, null otherwise (a big chance reads fine as a percent). */
    fun oneIn(weight: Int, totalWeight: Int): String? {
        if (weight <= 0 || totalWeight <= 0 || weight.toLong() * 10 > totalWeight.toLong()) return null
        val n = totalWeight.toDouble() / weight
        val text = if (totalWeight % weight == 0) (totalWeight / weight).toString()
        else trim(String.format(Locale.ROOT, "%.1f", n))
        return "1 in $text"
    }

    /** "2.5% (1 in 40)", or "Unknown" when the weights can't give a chance. */
    fun describe(weight: Int, totalWeight: Int): String {
        val p = percent(weight, totalWeight) ?: return "Unknown"
        val odds = oneIn(weight, totalWeight)
        return formatPercent(p) + (odds?.let { " ($it)" } ?: "")
    }

    private fun trim(number: String): String =
        if ('.' !in number) number else number.trimEnd('0').trimEnd('.')
}
