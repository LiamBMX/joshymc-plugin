package com.liam.joshymc.manager

import com.liam.joshymc.command.AdminBalanceCommand
import java.math.BigDecimal
import java.util.Locale
import java.util.UUID

/**
 * The reward catalog for store purchase fulfillment (issue #1175) and the parsing rules
 * for `/purchase fulfill`. No Bukkit here: what exists on this server (crates, voucher
 * definitions, chat tags) comes in through [Catalog], so the rules can be checked on
 * their own.
 *
 * Every reward is a fixed, validated operation on an existing system. Nothing here
 * ever turns its arguments into a console command.
 */
object PurchaseRewards {

    /** How a reward reaches the player. */
    enum class Delivery {
        /** A balance update; works while the recipient is offline. */
        CURRENCY,
        /** Inventory items; queued until the recipient is online with room. */
        ITEM,
        /** A donor tier through LuckPerms; needs the recipient online to confirm. */
        RANK
    }

    enum class Kind(val id: String, val delivery: Delivery, val usage: String) {
        COINS("coins", Delivery.CURRENCY, "coins <amount>"),
        CREDITS("credits", Delivery.CURRENCY, "credits <amount>"),
        TOKENS("tokens", Delivery.ITEM, "tokens <quantity>"),
        CRATE_KEY("cratekey", Delivery.ITEM, "cratekey <crate> <quantity>"),
        CREDIT_VOUCHER("creditvoucher", Delivery.ITEM, "creditvoucher <credits> <quantity>"),
        RANK_VOUCHER("rankvoucher", Delivery.ITEM, "rankvoucher <tier> <quantity>"),
        MONEY_VOUCHER("moneyvoucher", Delivery.ITEM, "moneyvoucher <voucher-id> <quantity>"),
        CHAT_TAG_VOUCHER("chattagvoucher", Delivery.ITEM, "chattagvoucher <tag-id> <quantity>"),
        RANK("rank", Delivery.RANK, "rank <tier>");

        companion object {
            fun of(id: String): Kind? = entries.firstOrNull { it.id == id.lowercase(Locale.ROOT) }
        }
    }

    /**
     * One validated reward. [param] is the amount for currencies (plain decimal), the
     * crate/tier/voucher/tag id or Credit amount for items, the tier for ranks, and empty
     * for tokens. [quantity] is always 1 for currencies and ranks.
     */
    data class Spec(val kind: Kind, val param: String, val quantity: Int) {
        /** Stable text form, used in logs and the dedupe key. */
        fun describe(): String = buildString {
            append(kind.id)
            if (param.isNotEmpty()) append(' ').append(param)
            if (kind.delivery == Delivery.ITEM) append(" x").append(quantity)
        }
    }

    /** What this server currently offers, so a purchase can't queue an item that can't be built. */
    interface Catalog {
        fun crateExists(id: String): Boolean
        fun moneyVoucherExists(id: String): Boolean
        fun chatTagVoucherExists(id: String): Boolean
    }

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Error(val message: String) : Result<Nothing>()
    }

    /** Most items one purchase line may queue: a full 36-slot inventory of 64-stacks. */
    const val MAX_ITEM_QUANTITY = 36 * 64

    /** Same cap as `/creditvoucher give`. */
    const val MAX_CREDIT_VOUCHER_AMOUNT = 1_000_000_000

    const val MAX_REFERENCE_LENGTH = 128

    private val REFERENCE_PATTERN = Regex("^[A-Za-z0-9._:#/-]{1,$MAX_REFERENCE_LENGTH}$")
    private val UNDASHED_UUID = Regex("^[0-9a-fA-F]{32}$")
    private val DASHED_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    data class Parsed(val spec: Spec, val reference: String?)

    /**
     * Parses `<type> <args...> [reference]` (everything after the recipient). Each type
     * takes a fixed number of arguments, so a single extra token is the reference and
     * anything beyond that is refused rather than guessed at.
     */
    fun parse(args: List<String>, catalog: Catalog): Result<Parsed> {
        val typeArg = args.firstOrNull() ?: return Result.Error("Missing reward type. Types: ${typeList()}.")
        val kind = Kind.of(typeArg) ?: return Result.Error("Unknown reward type '$typeArg'. Types: ${typeList()}.")
        val expected = kind.usage.split(' ').size - 1
        val rewardArgs = args.drop(1)
        if (rewardArgs.size < expected) return Result.Error("Usage: ${kind.usage} [reference]")
        if (rewardArgs.size > expected + 1) return Result.Error("Too many arguments. Usage: ${kind.usage} [reference]")

        val reference = rewardArgs.getOrNull(expected)?.let { raw ->
            when (val ref = parseReference(raw)) {
                is Result.Ok -> ref.value
                is Result.Error -> return ref
            }
        }

        val spec = when (val s = parseSpec(kind, rewardArgs.take(expected), catalog)) {
            is Result.Ok -> s.value
            is Result.Error -> return s
        }
        return Result.Ok(Parsed(spec, reference))
    }

    private fun parseSpec(kind: Kind, a: List<String>, catalog: Catalog): Result<Spec> = when (kind) {
        Kind.COINS -> currency(kind, a[0], AdminBalanceCommand.Currency.COINS)
        Kind.CREDITS -> currency(kind, a[0], AdminBalanceCommand.Currency.CREDITS)
        Kind.TOKENS -> quantity(a[0]).map { Spec(kind, "", it) }
        Kind.CRATE_KEY -> {
            val crate = a[0].lowercase(Locale.ROOT)
            if (!catalog.crateExists(crate)) Result.Error("Unknown crate '${a[0]}'.")
            else quantity(a[1]).map { Spec(kind, crate, it) }
        }
        Kind.CREDIT_VOUCHER -> {
            val credits = a[0].toIntOrNull()
            if (credits == null || credits <= 0 || credits > MAX_CREDIT_VOUCHER_AMOUNT) {
                Result.Error("Credit Voucher value must be a whole number from 1 to $MAX_CREDIT_VOUCHER_AMOUNT.")
            } else quantity(a[1]).map { Spec(kind, credits.toString(), it) }
        }
        Kind.RANK_VOUCHER -> {
            val tier = DonorTiers.normalize(a[0])
            if (tier == null) Result.Error("Unknown donor tier '${a[0]}'. Tiers: ${DonorTiers.ORDER.joinToString(", ")}.")
            else quantity(a[1]).map { Spec(kind, tier, it) }
        }
        Kind.MONEY_VOUCHER -> {
            val id = a[0].lowercase(Locale.ROOT)
            if (!catalog.moneyVoucherExists(id)) Result.Error("Unknown or disabled money voucher '${a[0]}'.")
            else quantity(a[1]).map { Spec(kind, id, it) }
        }
        Kind.CHAT_TAG_VOUCHER -> {
            val id = a[0].lowercase(Locale.ROOT)
            if (!catalog.chatTagVoucherExists(id)) Result.Error("Unknown chat tag voucher '${a[0]}'.")
            else quantity(a[1]).map { Spec(kind, id, it) }
        }
        Kind.RANK -> {
            val tier = DonorTiers.normalize(a[0])
            if (tier == null) Result.Error("Unknown donor tier '${a[0]}'. Tiers: ${DonorTiers.ORDER.joinToString(", ")}.")
            else Result.Ok(Spec(kind, tier, 1))
        }
    }

    /** Same exact parser and limits as the operator balance commands: finite, > 0, currency precision, capped. */
    private fun currency(kind: Kind, input: String, currency: AdminBalanceCommand.Currency): Result<Spec> {
        val amount = AdminBalanceCommand.parseAmount(input, currency, allowZero = false)
            ?: return Result.Error(
                "Invalid ${currency.label} amount '$input'. Use a number greater than 0 with at most " +
                    "${currency.scale} decimal place(s), no more than ${currency.max.toPlainString()}."
            )
        return Result.Ok(Spec(kind, BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString(), 1))
    }

    fun quantity(input: String): Result<Int> {
        val q = input.toIntOrNull()
        return if (q == null || q <= 0 || q > MAX_ITEM_QUANTITY) {
            Result.Error("Quantity must be a whole number from 1 to $MAX_ITEM_QUANTITY.")
        } else Result.Ok(q)
    }

    /**
     * An external reference (e.g. the store's transaction id). A value still containing
     * `{` or `}` is an unexpanded store placeholder: accepting it would make every purchase
     * share one reference and all but the first would be dropped as duplicates.
     */
    fun parseReference(raw: String): Result<String> {
        if (raw.contains('{') || raw.contains('}')) {
            return Result.Error("Reference '$raw' looks like an unexpanded store placeholder. Check the command in the store dashboard.")
        }
        if (!REFERENCE_PATTERN.matches(raw)) {
            return Result.Error("Invalid reference '$raw'. Use 1-$MAX_REFERENCE_LENGTH letters, digits or . _ : # / -")
        }
        return Result.Ok(raw)
    }

    /** A dashed or undashed UUID, or null if [input] isn't one (it's then treated as a name). */
    fun parseUuid(input: String): UUID? = when {
        DASHED_UUID.matches(input) -> UUID.fromString(input)
        UNDASHED_UUID.matches(input) -> UUID.fromString(
            "${input.substring(0, 8)}-${input.substring(8, 12)}-${input.substring(12, 16)}-${input.substring(16, 20)}-${input.substring(20)}"
        )
        else -> null
    }

    /**
     * Uniqueness key for a referenced purchase: the reference plus who gets what. A store
     * retry of the same command maps to the same key; different commands (or different
     * gift recipients) in one transaction don't collide. Null without a reference: then
     * nothing can tell a retry from a second, identical purchase, so nothing is deduped.
     */
    fun dedupeKey(reference: String?, recipientKey: String, spec: Spec): String? =
        reference?.let { "$it|${recipientKey.lowercase(Locale.ROOT)}|${spec.kind.id}|${spec.param}|${spec.quantity}" }

    fun typeList(): String = Kind.entries.joinToString(", ") { it.id }

    private inline fun <T, R> Result<T>.map(f: (T) -> R): Result<R> = when (this) {
        is Result.Ok -> Result.Ok(f(value))
        is Result.Error -> this
    }
}
