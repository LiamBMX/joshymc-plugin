package com.liam.joshymc.milestone

import java.util.Locale

/**
 * Special milestone rewards (issue #1420), Bukkit-free. Every reward is a fixed operation on an
 * existing system: Coins (EconomyManager), Credits (CreditsManager), crate keys
 * (CrateManager.createKeyStack), Chat Tag vouchers (ChatTagVoucherManager) and registered custom
 * items (ItemManager). What exists on this server is checked through [Catalog] when the config
 * loads; anything that doesn't is dropped with a logged problem, never faked.
 */
object MilestoneRewards {

    enum class Kind(val id: String, val physical: Boolean, val usage: String) {
        COINS("coins", false, "coins <amount>"),
        CREDITS("credits", false, "credits <amount>"),
        CRATE_KEY("cratekey", true, "cratekey <crate> [quantity]"),
        CHAT_TAG("chattag", true, "chattag <tag-id>"),
        ITEM("item", true, "item <custom-item-id> [quantity]");

        companion object {
            fun of(id: String): Kind? = entries.firstOrNull { it.id == id.lowercase(Locale.ROOT) }
        }
    }

    /** One reward. [amount] is the currency amount, or the item quantity for physical rewards. */
    data class Spec(val kind: Kind, val param: String, val amount: Double) {
        val quantity: Int get() = amount.toInt()
        fun describe(): String = when (kind) {
            Kind.COINS, Kind.CREDITS -> "${kind.id} ${trim(amount)}"
            Kind.CHAT_TAG -> "${kind.id} $param"
            else -> "${kind.id} $param $quantity"
        }
    }

    /** What exists on this server. */
    interface Catalog {
        fun crateExists(id: String): Boolean
        fun chatTagExists(id: String): Boolean
        fun customItemExists(id: String): Boolean
    }

    const val MAX_CREDITS = 1_000.0
    const val MAX_QUANTITY = 64

    private val ID = Regex("^[a-z0-9_:-]{1,64}$")

    /** Parses "coins 5000", "credits 2", "cratekey common 1", "chattag mapleborn", "item token 3". */
    fun parse(text: String, catalog: Catalog): Result<Spec> {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Result.failure(IllegalArgumentException("empty reward"))
        val kind = Kind.of(parts[0]) ?: return Result.failure(
            IllegalArgumentException("unknown reward type '${parts[0]}' (use ${Kind.entries.joinToString { it.id }})")
        )
        return when (kind) {
            Kind.COINS, Kind.CREDITS -> {
                if (parts.size != 2) return usage(kind)
                val amount = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                    ?: return Result.failure(IllegalArgumentException("'${parts[1]}' isn't a positive amount"))
                val max = if (kind == Kind.COINS) MilestoneMath.MAX_COINS else MAX_CREDITS
                if (amount > max) return Result.failure(IllegalArgumentException("${kind.id} amount above the limit of ${trim(max)}"))
                Result.success(Spec(kind, "", amount))
            }
            Kind.CHAT_TAG -> {
                if (parts.size != 2) return usage(kind)
                val id = parts[1].lowercase(Locale.ROOT)
                if (!ID.matches(id)) return Result.failure(IllegalArgumentException("invalid chat tag id '${parts[1]}'"))
                if (!catalog.chatTagExists(id)) return Result.failure(IllegalArgumentException("no Chat Tag voucher tag '$id' exists"))
                Result.success(Spec(kind, id, 1.0))
            }
            Kind.CRATE_KEY, Kind.ITEM -> {
                if (parts.size !in 2..3) return usage(kind)
                val id = parts[1].lowercase(Locale.ROOT)
                if (!ID.matches(id)) return Result.failure(IllegalArgumentException("invalid id '${parts[1]}'"))
                val quantity = if (parts.size == 3) parts[2].toIntOrNull() else 1
                if (quantity == null || quantity !in 1..MAX_QUANTITY) {
                    return Result.failure(IllegalArgumentException("quantity must be 1-$MAX_QUANTITY"))
                }
                val exists = if (kind == Kind.CRATE_KEY) catalog.crateExists(id) else catalog.customItemExists(id)
                if (!exists) {
                    return Result.failure(IllegalArgumentException(if (kind == Kind.CRATE_KEY) "no crate '$id' exists" else "no custom item '$id' exists"))
                }
                Result.success(Spec(kind, id, quantity.toDouble()))
            }
        }
    }

    private fun usage(kind: Kind): Result<Spec> = Result.failure(IllegalArgumentException("usage: ${kind.usage}"))

    private fun trim(value: Double): String =
        if (value == floor(value)) value.toLong().toString() else value.toString()

    private fun floor(value: Double): Double = kotlin.math.floor(value)

    /**
     * Guaranteed special rewards for one kind of track (objectives or category levels).
     * [explicit] tiers (5, 10, 25, 50, 75, ... any tier) win; otherwise every 100 tiers gets
     * [every100] (major) and every other special tier above 100 gets [every25].
     */
    data class Table(
        val explicit: Map<Int, List<Spec>>,
        val every25: List<Spec>,
        val every100: List<Spec>,
    ) {
        fun specialFor(tier: Int): List<Spec> {
            explicit[tier]?.let { return it }
            return when {
                MilestoneMath.isMajor(tier) -> every100
                tier > 100 && MilestoneMath.isSpecial(tier) -> every25
                else -> emptyList()
            }
        }

        companion object {
            val EMPTY = Table(emptyMap(), emptyList(), emptyList())
        }
    }

    /** What one completed tier pays: its coins plus that tier's special rewards. */
    data class TierRewards(val coins: Double, val specials: List<Spec>) {
        val totalCoins: Double get() = coins + specials.filter { it.kind == Kind.COINS }.sumOf { it.amount }
        val totalCredits: Double get() = specials.filter { it.kind == Kind.CREDITS }.sumOf { it.amount }
        val physical: List<Spec> get() = specials.filter { it.kind.physical }
    }
}
