package com.liam.joshymc.lifesteal.bank

import com.liam.joshymc.lifesteal.bounty.BountyMoney
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.progression.ProgressionRules
import com.liam.joshymc.lifesteal.teams.TeamCombatRules
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The Lifesteal Protected Bank and PvP coin stealing rules (issue #1427), Bukkit-free.
 * [LifestealBankManager] feeds them live data, [BankStore] applies them inside one database
 * transaction, and `scripts/LifestealBankCheck.kt` tests them without a server.
 *
 * Money is always whole **cents** in a `Long` ([BountyMoney]); the existing economy's `Double`
 * balance is only converted at its edge ([walletCents], [BountyMoney.toCoins]).
 */
object BankRules {

    // ── Money helpers ───────────────────────────────────────

    /**
     * The economy's `Double` wallet as whole cents, rounded **down** (sub-cent dust from other
     * systems is never counted or moved). NaN, Infinity and negatives count as 0, values above
     * [BountyMoney.MAX_CENTS] as that maximum.
     */
    fun walletCents(balance: Double): Long {
        if (!balance.isFinite() || balance <= 0.0) return 0L
        val cents = BigDecimal.valueOf(balance).movePointRight(2).setScale(0, RoundingMode.FLOOR)
        return if (cents > BigDecimal.valueOf(BountyMoney.MAX_CENTS)) BountyMoney.MAX_CENTS else cents.longValueExact()
    }

    /** A configured percentage (`10`, `2.5`, `"0.25"`) as basis points, or null (not a number, more than two decimals, negative). */
    fun percentToBps(raw: Any?): Int? {
        val decimal = when (raw) {
            is Int, is Long, is Short, is Byte -> BigDecimal.valueOf((raw as Number).toLong())
            is Double -> if (raw.isFinite()) BigDecimal.valueOf(raw) else return null
            is Float -> if (raw.isFinite()) BigDecimal.valueOf(raw.toDouble()) else return null
            is BigDecimal -> raw
            is String -> raw.trim().removeSuffix("%").trim().toBigDecimalOrNull() ?: return null
            else -> return null
        }
        if (decimal.signum() < 0) return null
        val bps = decimal.movePointRight(2)
        if (bps.stripTrailingZeros().scale() > 0) return null
        if (bps > BigDecimal.valueOf(1_000_000)) return null
        return bps.intValueExact()
    }

    /** `10%`, `2.5%`, `0.25%`. */
    fun formatBps(bps: Int): String = BigDecimal.valueOf(bps.toLong()).movePointLeft(2).stripTrailingZeros().toPlainString() + "%"

    /**
     * The fee on moving [amountCents], rounded **up** to the next cent whenever a fee is
     * configured, so splitting a transfer into many tiny ones can never dodge it. 0 when no fee.
     */
    fun fee(amountCents: Long, bps: Int): Long {
        require(amountCents >= 0 && bps >= 0) { "negative amount or fee" }
        if (bps == 0 || amountCents == 0L) return 0L
        // amount <= 1e15 and bps <= 1e4 keep the product far below Long.MAX_VALUE.
        val product = Math.multiplyExact(amountCents, bps.toLong())
        return (product + 9_999L) / 10_000L
    }

    /** [percent] (1..100) of [sourceCents], rounded down. */
    fun percentOf(sourceCents: Long, percent: Int): Long {
        require(percent in 1..100) { "percent out of range" }
        if (sourceCents <= 0) return 0L
        return Math.multiplyExact(sourceCents, percent.toLong()) / 100L
    }

    // ── Deposits and withdrawals ────────────────────────────

    enum class Direction(val id: String) { DEPOSIT("deposit"), WITHDRAW("withdraw") }

    enum class Refusal(val message: String) {
        INVALID_AMOUNT("That amount can't be moved."),
        ZERO("There's nothing to move."),
        FEE_EXCEEDS_AMOUNT("That amount is too small to cover the fee."),
        INSUFFICIENT_WALLET("You don't have that many coins in your wallet."),
        INSUFFICIENT_BANK("You don't have that many coins in your bank."),
        CAPACITY("Your bank is full."),
        TOO_LARGE("That would make your balance too large to process."),
    }

    /** What one transfer does: [amountCents] leaves the source, [feeCents] is the sink, [netCents] arrives. */
    data class Plan(val direction: Direction, val amountCents: Long, val feeCents: Long, val netCents: Long)

    sealed interface PlanResult {
        data class Ok(val plan: Plan) : PlanResult
        data class Refused(val reason: Refusal) : PlanResult
    }

    /**
     * Plans moving [requestedCents] (null = everything that can move) in [direction], from the
     * balances read inside the transaction. [capacityCents] null = unlimited; a bank already above
     * a lowered capacity keeps its coins (nothing is removed) but takes no deposits.
     */
    fun plan(
        direction: Direction,
        requestedCents: Long?,
        walletCents: Long,
        bankCents: Long,
        capacityCents: Long?,
        feeBps: Int,
    ): PlanResult {
        if (requestedCents != null && (requestedCents < 0 || requestedCents > BountyMoney.MAX_CENTS)) return PlanResult.Refused(Refusal.INVALID_AMOUNT)
        if (requestedCents == 0L) return PlanResult.Refused(Refusal.ZERO)
        val wallet = walletCents.coerceAtLeast(0)
        val bank = bankCents.coerceAtLeast(0)
        return when (direction) {
            Direction.DEPOSIT -> {
                val room = capacityCents?.let { (it - bank).coerceAtLeast(0) }
                val amount = requestedCents ?: maxDeposit(wallet, room, feeBps)
                if (amount <= 0) return PlanResult.Refused(if (room == 0L && wallet > 0) Refusal.CAPACITY else Refusal.ZERO)
                if (amount > wallet) return PlanResult.Refused(Refusal.INSUFFICIENT_WALLET)
                val fee = fee(amount, feeBps)
                val net = amount - fee
                if (net <= 0) return PlanResult.Refused(Refusal.FEE_EXCEEDS_AMOUNT)
                if (room != null && net > room) return PlanResult.Refused(Refusal.CAPACITY)
                if (BountyMoney.add(bank, net) == null) return PlanResult.Refused(Refusal.TOO_LARGE)
                PlanResult.Ok(Plan(direction, amount, fee, net))
            }
            Direction.WITHDRAW -> {
                val amount = requestedCents ?: bank
                if (amount <= 0) return PlanResult.Refused(Refusal.ZERO)
                if (amount > bank) return PlanResult.Refused(Refusal.INSUFFICIENT_BANK)
                val fee = fee(amount, feeBps)
                val net = amount - fee
                if (net <= 0) return PlanResult.Refused(Refusal.FEE_EXCEEDS_AMOUNT)
                if (BountyMoney.add(wallet, net) == null) return PlanResult.Refused(Refusal.TOO_LARGE)
                PlanResult.Ok(Plan(direction, amount, fee, net))
            }
        }
    }

    /**
     * The largest deposit from [wallet] whose net (after the fee) fits [room] (null = unlimited).
     * `amount - fee(amount)` never decreases as the amount grows, so a binary search finds it.
     */
    fun maxDeposit(wallet: Long, room: Long?, feeBps: Int): Long {
        if (wallet <= 0) return 0
        if (room == null || wallet - fee(wallet, feeBps) <= room) return wallet
        if (room <= 0) return 0
        var low = 0L
        var high = wallet
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (mid - fee(mid, feeBps) <= room) low = mid else high = mid - 1
        }
        return low
    }

    // ── Who may bank right now ──────────────────────────────

    /** Live state of a player at the moment a bank transfer runs (always re-read then, never trusted from a GUI). */
    data class AccessState(
        val inLifesteal: Boolean,
        val dead: Boolean,
        val combatTagged: Boolean,
        val inRankedMatch: Boolean,
        val inEvent: Boolean,
        val frozen: Boolean,
    )

    enum class Block(val message: String) {
        NOT_LIFESTEAL("The bank can only be used in Lifesteal."),
        DEAD("You can't use the bank while dead."),
        COMBAT("You can't use the bank while in combat."),
        RANKED("You can't use the bank during a Ranked Team Wars match."),
        EVENT("You can't use the bank during an event."),
        FROZEN("You can't use the bank while frozen."),
    }

    /** Fixed order; null = allowed. Events, freezes and death always block; combat and ranked follow the settings. */
    fun accessBlock(state: AccessState, settings: BankSettings): Block? = when {
        !state.inLifesteal -> Block.NOT_LIFESTEAL
        state.dead -> Block.DEAD
        settings.blockDuringCombat && state.combatTagged -> Block.COMBAT
        settings.blockDuringRanked && state.inRankedMatch -> Block.RANKED
        state.inEvent -> Block.EVENT
        state.frozen -> Block.FROZEN
        else -> null
    }

    // ── PvP coin stealing ───────────────────────────────────

    /** Everything about one death that coin stealing looks at, captured at the moment of death. */
    data class Death(
        /** The same attribution the heart system uses ([HeartRules.attribute]); heart records aren't needed for coins. */
        val attribution: HeartRules.Attribution,
        val inEvent: Boolean,
        val inRankedMatch: Boolean,
        /** The launch progression at death time ([ProgressionRules.HeartWindow]), shared with hearts. */
        val window: ProgressionRules.HeartWindow,
        /** The heart system's team verdict for this kill ([TeamCombatRules.heartCheck]). */
        val heartCheck: TeamCombatRules.HeartCheck,
        /** The raw team relation, for [CoinStealSettings.blockTeamFarming]. */
        val relation: TeamCombatRules.Relation,
        val sharedConnection: Boolean,
        /** Issue #1487 (D-H4): the killer's competitive eligibility; anything but Eligible moves no coins. */
        val attacker: com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.Verdict =
            com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.Verdict.Eligible,
    )

    enum class StealRefusal(val log: String, val killerMessage: String?) {
        DISABLED("coin stealing is disabled", null),
        NOT_PVP("not a player kill", null),
        INELIGIBLE_ATTACKER("the killer used staff abilities or over-limit enchantments", null),
        EVENT("event deaths never move coins", null),
        RANKED("ranked match deaths don't move coins (pvp-coins.ranked-enabled is false)", null),
        NOT_LAUNCHED("the season hasn't launched", null),
        GRACE_PERIOD("grace period", null),
        PROGRESSION_UNKNOWN("launch progression unknown", null),
        TEAMMATES("teammates or allies", "&7Teammates and allies don't steal coins from each other."),
        TEAM_UNKNOWN("team membership unknown", null),
        SHARED_CONNECTION("killer and victim share a connection (pvp-coins.block-shared-connections)", null),
        COOLDOWN("same victim cooldown", "&7You stole coins from this player recently, so this kill moved none."),
        NOTHING("the victim's wallet had nothing to steal", null),
    }

    sealed interface Eligibility {
        data object Eligible : Eligibility
        data class Refused(val reason: StealRefusal) : Eligibility
    }

    /**
     * Fixed order: disabled, attribution (only [HeartRules.Attribution.PVP_KILL]), events, ranked
     * matches, the launch progression (same window as hearts), teams (the heart system's verdict
     * first, so the rules never disagree, then the farming block for teammates/allies the team
     * policy would let move hearts), shared connections. The heart outcome (a victim on their last
     * heart, a killer at the maximum, a heart cooldown) is deliberately **not** an input: coins are
     * decided on their own. The pair cooldown and the amount are checked in the store.
     */
    fun eligibility(death: Death, settings: CoinStealSettings): Eligibility {
        fun refuse(reason: StealRefusal) = Eligibility.Refused(reason)
        if (!settings.enabled) return refuse(StealRefusal.DISABLED)
        if (death.attribution != HeartRules.Attribution.PVP_KILL) return refuse(StealRefusal.NOT_PVP)
        if (death.attacker is com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.Verdict.Ineligible) return refuse(StealRefusal.INELIGIBLE_ATTACKER)
        if (death.inEvent) return refuse(StealRefusal.EVENT)
        if (death.inRankedMatch && !settings.rankedEnabled) return refuse(StealRefusal.RANKED)
        when (death.window) {
            ProgressionRules.HeartWindow.NOT_LAUNCHED -> return refuse(StealRefusal.NOT_LAUNCHED)
            ProgressionRules.HeartWindow.GRACE_PERIOD -> return refuse(StealRefusal.GRACE_PERIOD)
            ProgressionRules.HeartWindow.UNKNOWN -> return refuse(StealRefusal.PROGRESSION_UNKNOWN)
            ProgressionRules.HeartWindow.OPEN -> {}
        }
        when (death.heartCheck) {
            TeamCombatRules.HeartCheck.TEAMMATES, TeamCombatRules.HeartCheck.ALLIES -> return refuse(StealRefusal.TEAMMATES)
            TeamCombatRules.HeartCheck.UNKNOWN -> return refuse(StealRefusal.TEAM_UNKNOWN)
            TeamCombatRules.HeartCheck.ELIGIBLE -> {}
        }
        if (death.relation == TeamCombatRules.Relation.UNKNOWN) return refuse(StealRefusal.TEAM_UNKNOWN)
        if (settings.blockTeamFarming && death.relation != TeamCombatRules.Relation.NOT_TEAMMATES) return refuse(StealRefusal.TEAMMATES)
        if (settings.blockSharedConnections && death.sharedConnection) return refuse(StealRefusal.SHARED_CONNECTION)
        return Eligibility.Eligible
    }

    /** What one kill moves from a wallet of [victimWalletCents]: the configured share, rounded down, capped by `max-per-kill`. */
    fun stealAmount(victimWalletCents: Long, settings: CoinStealSettings): Long {
        if (victimWalletCents <= 0 || settings.walletLossBps <= 0) return 0
        // Split so the product never overflows (wallet up to 1e15 cents x 10,000 bps); still an exact floor.
        val bps = settings.walletLossBps.toLong()
        val share = (victimWalletCents / 10_000L) * bps + (victimWalletCents % 10_000L) * bps / 10_000L
        return settings.maxPerKillCents?.let { minOf(share, it) } ?: share
    }

    // ── Text ────────────────────────────────────────────────

    fun fill(template: String, values: Map<String, String>): String =
        values.entries.fold(template) { text, (key, value) -> text.replace("{$key}", value) }
}
