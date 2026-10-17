package com.liam.joshymc.communityevent.result

import com.liam.joshymc.command.AdminBalanceCommand
import com.liam.joshymc.manager.PurchaseRewards
import java.math.BigDecimal
import java.util.Locale

/**
 * Event reward definitions (issue #1199). Plain Kotlin with no Bukkit types: what exists on
 * the server (crates) comes in through [crateExists].
 *
 * Every reward is one of the store-delivery reward kinds ([PurchaseRewards.Kind]), so payouts
 * go through the same persistent, idempotent `purchase_deliveries` pipeline as store purchases
 * (offline currency, items on join, `/purchase retry` recovery). Nothing here ever becomes a
 * console command.
 */

/** Who an entry pays. [FIRST] is every winner (Mob Waves can have several survivors). */
enum class Placement(val id: String, val label: String) {
    FIRST("first", "1st Place"),
    SECOND("second", "2nd Place"),
    THIRD("third", "3rd Place"),
    PARTICIPANT("participant", "Every Participant");

    companion object {
        val ids: List<String> = entries.map { it.id }
        val placed: List<Placement> = listOf(FIRST, SECOND, THIRD)

        fun of(id: String?): Placement? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) }
    }
}

/** The reward kinds an event may pay out: a subset of the store kinds, without ranks or vouchers. */
enum class RewardKind(val id: String, val kind: PurchaseRewards.Kind) {
    COINS("coins", PurchaseRewards.Kind.COINS),
    CREDITS("credits", PurchaseRewards.Kind.CREDITS),
    TOKENS("tokens", PurchaseRewards.Kind.TOKENS),
    CRATE_KEY("cratekey", PurchaseRewards.Kind.CRATE_KEY);

    val isCurrency: Boolean get() = kind.delivery == PurchaseRewards.Delivery.CURRENCY

    companion object {
        val ids: List<String> = entries.map { it.id }

        fun of(id: String?): RewardKind? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
        fun ofStore(id: String?): RewardKind? = entries.firstOrNull { it.kind.id.equals(id, ignoreCase = true) }
    }
}

/**
 * One reward line. [param] is the plain-decimal amount for currencies and the crate id for
 * keys (empty for tokens); [quantity] is 1 for currencies.
 */
data class RewardEntry(val placement: Placement, val kind: RewardKind, val param: String, val quantity: Int) {
    val spec: PurchaseRewards.Spec get() = PurchaseRewards.Spec(kind.kind, param, quantity)

    fun describe(crateName: (String) -> String = { it }): String = when (kind) {
        RewardKind.COINS -> "$param Coins"
        RewardKind.CREDITS -> "$param Credits"
        RewardKind.TOKENS -> "${quantity}x Token"
        RewardKind.CRATE_KEY -> "${quantity}x ${crateName(param)}"
    }
}

/** Per-entry caps from `hosted-events.results.limits`, so a typo can't mint a fortune. */
data class RewardLimits(val maxCoins: BigDecimal, val maxCredits: BigDecimal, val maxItems: Int, val maxEntries: Int) {
    companion object {
        val DEFAULT = RewardLimits(BigDecimal("1000000"), BigDecimal("1000"), 64, 10)
    }
}

object EventRewards {

    sealed class Parse {
        data class Ok(val entry: RewardEntry) : Parse()
        data class Error(val message: String) : Parse()
    }

    const val USAGE = "<first|second|third|participant> <coins|credits> <amount> | <tokens> <quantity> | <cratekey> <crate> <quantity>"

    /**
     * Parses `<placement> <kind> <args...>`. Currency amounts use the operator balance parser
     * (finite, positive, currency precision, no NaN/Infinity), then the event caps.
     */
    fun parse(args: List<String>, limits: RewardLimits, crateExists: (String) -> Boolean): Parse {
        val placement = Placement.of(args.getOrNull(0))
            ?: return Parse.Error("Unknown placement '${args.getOrNull(0) ?: ""}'. Use ${Placement.ids.joinToString(", ")}.")
        val kind = RewardKind.of(args.getOrNull(1))
            ?: return Parse.Error("Unknown reward type '${args.getOrNull(1) ?: ""}'. Use ${RewardKind.ids.joinToString(", ")}.")
        val rest = args.drop(2)
        return when (kind) {
            RewardKind.COINS, RewardKind.CREDITS -> {
                if (rest.size != 1) return Parse.Error("Usage: ${placement.id} ${kind.id} <amount>")
                val currency = if (kind == RewardKind.COINS) AdminBalanceCommand.Currency.COINS else AdminBalanceCommand.Currency.CREDITS
                val cap = if (kind == RewardKind.COINS) limits.maxCoins else limits.maxCredits
                currency(rest[0], currency, cap)?.let { Parse.Ok(RewardEntry(placement, kind, it, 1)) }
                    ?: Parse.Error("Invalid ${currency.label} amount '${rest[0]}'. Use a number above 0 with at most ${currency.scale} decimal place(s), no more than ${cap.toPlainString()}.")
            }
            RewardKind.TOKENS -> {
                if (rest.size != 1) return Parse.Error("Usage: ${placement.id} tokens <quantity>")
                quantity(rest[0], limits)?.let { Parse.Ok(RewardEntry(placement, kind, "", it)) }
                    ?: Parse.Error("Quantity must be a whole number from 1 to ${limits.maxItems}.")
            }
            RewardKind.CRATE_KEY -> {
                if (rest.size != 2) return Parse.Error("Usage: ${placement.id} cratekey <crate> <quantity>")
                val crate = rest[0].lowercase(Locale.ROOT)
                if (!crateExists(crate)) return Parse.Error("Unknown crate '${rest[0]}'.")
                quantity(rest[1], limits)?.let { Parse.Ok(RewardEntry(placement, kind, crate, it)) }
                    ?: Parse.Error("Quantity must be a whole number from 1 to ${limits.maxItems}.")
            }
        }
    }

    /**
     * Re-checks a stored entry (preset rows, snapshots) against today's rules, so a hand-edited
     * database row or a lowered cap can never pay out something invalid. Null when it's fine.
     */
    fun problem(entry: RewardEntry, limits: RewardLimits, crateExists: (String) -> Boolean): String? = when (entry.kind) {
        RewardKind.COINS, RewardKind.CREDITS -> {
            val currency = if (entry.kind == RewardKind.COINS) AdminBalanceCommand.Currency.COINS else AdminBalanceCommand.Currency.CREDITS
            val cap = if (entry.kind == RewardKind.COINS) limits.maxCoins else limits.maxCredits
            if (entry.quantity != 1 || currency(entry.param, currency, cap) == null) "invalid ${currency.label} amount '${entry.param}'" else null
        }
        RewardKind.TOKENS -> if (entry.quantity !in 1..limits.maxItems) "invalid token quantity ${entry.quantity}" else null
        RewardKind.CRATE_KEY -> when {
            entry.quantity !in 1..limits.maxItems -> "invalid key quantity ${entry.quantity}"
            !crateExists(entry.param) -> "crate '${entry.param}' no longer exists"
            else -> null
        }
    }

    /** The canonical plain-decimal amount, or null when invalid or above [cap]. */
    private fun currency(input: String, currency: AdminBalanceCommand.Currency, cap: BigDecimal): String? {
        val amount = AdminBalanceCommand.parseAmount(input, currency, allowZero = false) ?: return null
        if (!amount.isFinite()) return null
        val exact = BigDecimal.valueOf(amount).stripTrailingZeros()
        if (exact > cap) return null
        return exact.toPlainString()
    }

    private fun quantity(input: String, limits: RewardLimits): Int? =
        input.toIntOrNull()?.takeIf { it in 1..limits.maxItems }
}
