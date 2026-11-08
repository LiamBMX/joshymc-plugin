package com.liam.joshymc.lifesteal.bank

import com.liam.joshymc.lifesteal.bounty.BountyMoney

/**
 * `lifesteal.yml > bank` and `lifesteal.yml > pvp-coins` (issue #1427), Bukkit-free. Read
 * separately from the rest of lifesteal.yml (like the bounties), so a mistake here only turns
 * banking or coin stealing off and never pauses hearts, teams or anything else. A key left out
 * uses the default (a warning); a wrong value is severe and makes that section invalid.
 *
 * The two sections are independent: an invalid or disabled `bank` never changes coin stealing,
 * and an invalid or disabled `pvp-coins` never changes the bank or heart stealing.
 */
data class BankSettings(
    /** false = no new deposits. Stored bank balances are kept and can still be withdrawn (coins are never locked away). */
    val enabled: Boolean = true,
    /** The most coins one bank can hold, in cents; null = unlimited. Lowering it never removes coins, it only stops deposits. */
    val maxCapacityCents: Long? = null,
    /** Fees in basis points (1/100 of a percent): 250 = 2.5%. */
    val depositFeeBps: Int = 0,
    val withdrawalFeeBps: Int = 0,
    val blockDuringCombat: Boolean = true,
    val blockDuringRanked: Boolean = true,
    /** The only accepted policy is [SeasonReset.NONE]: the blueprint defines no personal bank reset. */
    val seasonReset: SeasonReset = SeasonReset.NONE,
    /** The most one `/bank admin add|remove` may move, in cents. */
    val adminMaxAdjustmentCents: Long = 100_000_000L,
    val gui: Gui = Gui(),
    val messages: Messages = Messages(),
) {
    enum class SeasonReset(val id: String) { NONE("none") }

    data class Gui(
        val title: String = "&8Protected Bank",
        val confirmTitle: String = "&8Confirm Bank Transfer",
    )

    data class Messages(
        val deposited: String = "&aYou deposited &6{amount} &ainto your bank.{fee} &7Wallet: &6{wallet} &8| &7Bank: &6{bank}",
        val withdrew: String = "&aYou withdrew &6{amount} &afrom your bank.{fee} &7Wallet: &6{wallet} &8| &7Bank: &6{bank}",
        val feeSuffix: String = " &7(fee &c{fee}&7)",
    )

    companion object {
        val DEFAULTS = BankSettings()
    }
}

data class CoinStealSettings(
    /** false = no coins move on any death. Heart stealing is unaffected. */
    val enabled: Boolean = true,
    /** Share of the victim's wallet that moves to the killer, in basis points (1000 = 10%). */
    val walletLossBps: Int = 1000,
    /** The most one kill can move, in cents; null = no limit. */
    val maxPerKillCents: Long? = null,
    /** Deaths inside a Ranked Team Wars match. Off until the policy is approved. */
    val rankedEnabled: Boolean = false,
    /** Teammates, recent teammates and allies never move coins, even if the team policy lets them move hearts. */
    val blockTeamFarming: Boolean = true,
    /** The same-victim cooldown and the suspicious-kill log. */
    val antiFarmingEnabled: Boolean = true,
    /** Killing the same victim again within this window moves no coins (0 = no cooldown). */
    val sameVictimCooldownMillis: Long = 30 * 60_000L,
    /** This many coin steals from the same victim by the same killer inside [repeatWindowMillis] are logged as suspicious. */
    val repeatKillThreshold: Int = 3,
    val repeatWindowMillis: Long = 24 * 3_600_000L,
    /** Killer and victim have connected from the same address: false = only logged, true = no coins move. */
    val blockSharedConnections: Boolean = false,
    val messages: Messages = Messages(),
) {
    data class Messages(
        val victim: String = "&cYou lost &6{amount} &cin combat to &f{killer}&c! &7Wallet: &6{wallet}",
        val killer: String = "&aYou stole &6{amount} &afrom &f{victim}&a! &7Wallet: &6{wallet}",
    )

    companion object {
        val DEFAULTS = CoinStealSettings()
    }
}

object BankConfig {

    data class Problem(val severe: Boolean, val message: String)

    data class Result<T>(val settings: T?, val problems: List<Problem>) {
        val valid: Boolean get() = settings != null
    }

    /** A fee or loss percentage may have at most two decimals; fees are capped at 50%, the PvP loss at 100%. */
    const val MAX_FEE_BPS = 5_000
    const val MAX_LOSS_BPS = 10_000

    private class Reader(private val section: String, private val get: (String) -> Any?) {
        val problems = mutableListOf<Problem>()

        private fun missing(path: String, default: Any) {
            problems += Problem(false, "$section.$path is missing; using $default.")
        }

        fun bool(path: String, default: Boolean): Boolean = when (val raw = get(path)) {
            null -> { missing(path, default); default }
            is Boolean -> raw
            else -> { problems += Problem(true, "$section.$path must be true or false (got '$raw')."); default }
        }

        fun int(path: String, default: Int, min: Int, max: Int): Int {
            val raw = get(path) ?: run { missing(path, default); return default }
            val value = when (raw) {
                is Int -> raw
                is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                is Short -> raw.toInt()
                else -> null
            }
            if (value == null || value !in min..max) {
                problems += Problem(true, "$section.$path must be a whole number from $min to $max (got '$raw').")
                return default
            }
            return value
        }

        fun string(path: String, default: String): String = when (val raw = get(path)) {
            null -> { missing(path, "the default text"); default }
            is String -> raw
            else -> { problems += Problem(true, "$section.$path must be text (got '$raw')."); default }
        }

        /** A percentage with at most two decimals, as basis points, from 0 to [maxBps]. */
        fun percent(path: String, defaultBps: Int, maxBps: Int): Int {
            val raw = get(path) ?: run { missing(path, "${defaultBps / 100.0}%"); return defaultBps }
            val bps = BankRules.percentToBps(raw)
            if (bps == null || bps !in 0..maxBps) {
                problems += Problem(true, "$section.$path must be a percentage from 0 to ${maxBps / 100} with at most two decimals (got '$raw').")
                return defaultBps
            }
            return bps
        }

        /** -1 = unlimited (null); otherwise a positive amount of coins as cents. */
        fun limit(path: String, default: Long?): Long? {
            val raw = get(path) ?: run { missing(path, default?.let { BountyMoney.format(it) } ?: "-1 (unlimited)"); return default }
            if (raw == -1 || raw == -1L || raw == "-1") return null
            return BountyMoney.configCents(raw) ?: run {
                problems += Problem(true, "$section.$path must be -1 (unlimited) or a positive amount of at most ${BountyMoney.format(BountyMoney.MAX_CENTS)} with at most two decimals (got '$raw').")
                default
            }
        }

        fun amount(path: String, default: Long): Long {
            val raw = get(path) ?: run { missing(path, BountyMoney.format(default)); return default }
            return BountyMoney.configCents(raw) ?: run {
                problems += Problem(true, "$section.$path must be a positive amount with at most two decimals (got '$raw').")
                default
            }
        }
    }

    /** [get] reads a path below `bank.` (null when missing). */
    fun parseBank(get: (String) -> Any?): Result<BankSettings> {
        val r = Reader("bank", get)
        val d = BankSettings.DEFAULTS
        val seasonRaw = r.string("season-reset", d.seasonReset.id)
        val season = BankSettings.SeasonReset.entries.firstOrNull { it.id.equals(seasonRaw.trim(), ignoreCase = true) } ?: run {
            r.problems += Problem(true, "bank.season-reset must be 'none' (got '$seasonRaw'). No personal bank reset policy is approved, so banks are never wiped.")
            d.seasonReset
        }
        val settings = BankSettings(
            enabled = r.bool("enabled", d.enabled),
            maxCapacityCents = r.limit("max-capacity", d.maxCapacityCents),
            depositFeeBps = r.percent("deposit-fee-percent", d.depositFeeBps, MAX_FEE_BPS),
            withdrawalFeeBps = r.percent("withdrawal-fee-percent", d.withdrawalFeeBps, MAX_FEE_BPS),
            blockDuringCombat = r.bool("block-during-combat", d.blockDuringCombat),
            blockDuringRanked = r.bool("block-during-ranked", d.blockDuringRanked),
            seasonReset = season,
            adminMaxAdjustmentCents = r.amount("admin-max-adjustment", d.adminMaxAdjustmentCents),
            gui = BankSettings.Gui(
                title = r.string("gui.title", d.gui.title),
                confirmTitle = r.string("gui.confirm-title", d.gui.confirmTitle),
            ),
            messages = BankSettings.Messages(
                deposited = r.string("messages.deposited", d.messages.deposited),
                withdrew = r.string("messages.withdrew", d.messages.withdrew),
                feeSuffix = r.string("messages.fee-suffix", d.messages.feeSuffix),
            ),
        )
        return Result(if (r.problems.any { it.severe }) null else settings, r.problems)
    }

    /** [get] reads a path below `pvp-coins.` (null when missing). */
    fun parseCoinSteal(get: (String) -> Any?): Result<CoinStealSettings> {
        val r = Reader("pvp-coins", get)
        val d = CoinStealSettings.DEFAULTS
        val settings = CoinStealSettings(
            enabled = r.bool("enabled", d.enabled),
            walletLossBps = r.percent("wallet-loss-percent", d.walletLossBps, MAX_LOSS_BPS),
            maxPerKillCents = r.limit("max-per-kill", d.maxPerKillCents),
            rankedEnabled = r.bool("ranked-enabled", d.rankedEnabled),
            blockTeamFarming = r.bool("block-team-farming", d.blockTeamFarming),
            antiFarmingEnabled = r.bool("anti-farming-enabled", d.antiFarmingEnabled),
            sameVictimCooldownMillis = r.int("same-victim-cooldown-minutes", 30, 0, 24 * 60 * 30) * 60_000L,
            repeatKillThreshold = r.int("repeat-kill-threshold", d.repeatKillThreshold, 2, 1000),
            repeatWindowMillis = r.int("repeat-kill-window-hours", 24, 1, 24 * 30) * 3_600_000L,
            blockSharedConnections = r.bool("block-shared-connections", d.blockSharedConnections),
            messages = CoinStealSettings.Messages(
                victim = r.string("messages.victim", d.messages.victim),
                killer = r.string("messages.killer", d.messages.killer),
            ),
        )
        return Result(if (r.problems.any { it.severe }) null else settings, r.problems)
    }
}
