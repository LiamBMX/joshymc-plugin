package com.liam.joshymc.milestone

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/** The five Milestone categories (issue #1420). [id] is the config/database id. */
enum class MilestoneCategory(val id: String, val defaultName: String) {
    FARMING("farming", "Farming"),
    MINING("mining", "Mining"),
    FISHING("fishing", "Fishing"),
    COMBAT("combat", "Combat"),
    EXPLORATION("exploration", "Exploration");

    /** Database key of this category's overall level. */
    val levelKey: String get() = "level:$id"

    companion object {
        fun fromId(id: String?): MilestoneCategory? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/**
 * Requirement growth for one track: tier `t` needs `base × (1 + growth × (t − 1)^exponent)`, rounded up.
 * With `0 < exponent < 1` every tier needs more than the last, but each step adds less than the one
 * before (diminishing growth), so requirements rise forever without running away.
 */
data class MilestoneScaling(val growth: Double, val exponent: Double) {
    init {
        require(growth.isFinite() && growth in 0.0..MAX_GROWTH) { "growth must be between 0 and $MAX_GROWTH" }
        require(exponent.isFinite() && exponent > 0.0 && exponent <= 1.0) { "exponent must be above 0 and at most 1" }
    }

    companion object {
        const val MAX_GROWTH = 10.0
        val DEFAULT = MilestoneScaling(growth = 0.25, exponent = 0.6)

        /** Null for values [MilestoneScaling] refuses, so a config mistake falls back instead of throwing. */
        fun orNull(growth: Double, exponent: Double): MilestoneScaling? =
            runCatching { MilestoneScaling(growth, exponent) }.getOrNull()
    }
}

/** Coin payouts per tier: like [MilestoneScaling] on [base], capped at [cap] (issue #1420 economy guard). */
data class CoinScaling(val base: Double, val growth: Double, val exponent: Double, val cap: Double) {
    init {
        require(base.isFinite() && base >= 0.0 && base <= MilestoneMath.MAX_COINS) { "coin base must be between 0 and ${MilestoneMath.MAX_COINS}" }
        require(growth.isFinite() && growth in 0.0..MilestoneScaling.MAX_GROWTH) { "coin growth must be between 0 and ${MilestoneScaling.MAX_GROWTH}" }
        require(exponent.isFinite() && exponent > 0.0 && exponent <= 1.0) { "coin exponent must be above 0 and at most 1" }
        require(cap.isFinite() && cap >= base && cap <= MilestoneMath.MAX_COINS) { "coin cap must be at least the base and at most ${MilestoneMath.MAX_COINS}" }
    }

    companion object {
        fun orNull(base: Double, growth: Double, exponent: Double, cap: Double): CoinScaling? =
            runCatching { CoinScaling(base, growth, exponent, cap) }.getOrNull()
    }
}

/**
 * Bukkit-free numbers for Milestones (issue #1420): requirements, coin payouts, the special,
 * major and announced tiers, and the progress display. Everything is overflow safe: tiers are
 * clamped to [MAX_TIER], requirements to [MAX_REQUIREMENT] and progress is stored in
 * [SCALE]-ths of a unit, so `MAX_REQUIREMENT × SCALE` still fits in a Long.
 */
object MilestoneMath {

    /**
     * Stored progress per unit (one harvest, one block, one kill, one block travelled). 720720 is
     * divisible by every whole number from 1 to 16, so a spawner kill worth 1/N of a kill (N up to
     * 16) is stored exactly and no remainder is ever lost between kills, sessions or restarts.
     */
    const val SCALE: Long = 720_720L

    const val MAX_TIER: Int = 1_000_000
    const val MAX_REQUIREMENT: Long = 1_000_000_000_000L
    const val MAX_COINS: Double = 1_000_000_000.0

    /** Largest amount one activity may add (a guard against a bad caller, not a gameplay limit). */
    const val MAX_ACTIVITY_SCALED: Long = 100_000L * SCALE

    fun clampTier(tier: Int): Int = tier.coerceIn(1, MAX_TIER)

    private fun multiplier(tier: Int, growth: Double, exponent: Double): Double {
        val steps = (clampTier(tier) - 1).toDouble()
        return 1.0 + growth * steps.pow(exponent)
    }

    /** Units tier [tier] needs. Never below [base] (so never zero), never decreasing, at most [MAX_REQUIREMENT]. */
    fun requirement(base: Long, tier: Int, scaling: MilestoneScaling): Long {
        val b = base.coerceIn(1L, MAX_REQUIREMENT)
        val raw = b.toDouble() * multiplier(tier, scaling.growth, scaling.exponent)
        if (!raw.isFinite() || raw >= MAX_REQUIREMENT.toDouble()) return MAX_REQUIREMENT
        // A hair under the exact value so floating error can't push a whole number up by one.
        return ceil(raw - 1e-9).toLong().coerceIn(b, MAX_REQUIREMENT)
    }

    /** [requirement] in stored progress units. */
    fun requirementScaled(base: Long, tier: Int, scaling: MilestoneScaling): Long = requirement(base, tier, scaling) * SCALE

    /** Coins for completing tier [tier]: whole coins, rising with the tier, never above the cap. */
    fun coins(scaling: CoinScaling, tier: Int): Double {
        if (scaling.base <= 0.0) return 0.0
        val raw = scaling.base * multiplier(tier, scaling.growth, scaling.exponent)
        if (!raw.isFinite()) return scaling.cap
        return floor(raw.coerceAtMost(scaling.cap))
    }

    /** Special reward tiers: 5, 10, 25, 50, 75, 100, then every 25 tiers. */
    fun isSpecial(tier: Int): Boolean = tier == 5 || tier == 10 || (tier >= 25 && tier % 25 == 0)

    /** Major special rewards: every 100 tiers. */
    fun isMajor(tier: Int): Boolean = tier >= 100 && tier % 100 == 0

    /** Server-wide announcements: 25, 50, then every multiple of 100 (100, 200, 300, ...). */
    fun isAnnounced(tier: Int): Boolean = tier == 25 || tier == 50 || isMajor(tier)

    /** The next [count] special tiers after [tier] (exclusive). */
    fun nextSpecialTiers(tier: Int, count: Int): List<Int> {
        val out = ArrayList<Int>(count)
        var t = clampTier(tier)
        while (out.size < count && t < MAX_TIER) {
            t++
            if (isSpecial(t)) out += t
        }
        return out
    }

    /** Whole units for display. */
    fun units(scaled: Long): Long = scaled / SCALE

    /** Whole percent, 100 only when [progress] really reached [required]. */
    fun percent(progress: Long, required: Long): Int {
        if (required <= 0L || progress >= required) return 100
        if (progress <= 0L) return 0
        return ((progress.toDouble() / required.toDouble()) * 100.0).toInt().coerceIn(0, 99)
    }

    /** Filled cells of a [width]-cell bar; full only when complete. */
    fun barCells(progress: Long, required: Long, width: Int): Int {
        if (required <= 0L || progress >= required) return width
        if (progress <= 0L) return 0
        return ((progress.toDouble() / required.toDouble()) * width).toInt().coerceIn(0, width - 1)
    }

    /** Progress one spawner mob kill is worth when [killsPerUnit] of them make one kill (1..16, exact). */
    fun spawnerKillScaled(killsPerUnit: Int): Long = SCALE / killsPerUnit.coerceIn(1, 16)
}
