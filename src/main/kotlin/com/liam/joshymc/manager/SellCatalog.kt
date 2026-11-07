package com.liam.joshymc.manager

/**
 * The /sell price catalog rules (issue #1416), Bukkit-free so they can be checked without a
 * server (`./gradlew -I scripts/sell-price-check.init.gradle.kts sellPriceCheck`).
 *
 * `sell-prices.yml > prices:` is the ONLY place a sell price comes from. The `categories:` block
 * is organizational only: it never makes an item sellable, never changes a price, and a `sell:`
 * value left in it is reported (and ignored) when it differs from `prices:`.
 */
object SellCatalog {

    /** No single item may be worth more than this; larger values are rejected as typos/overflow. */
    const val MAX_UNIT_PRICE = 1_000_000.0

    /** How many examples one summary log line names before saying "and N more". */
    const val LOG_EXAMPLES = 6

    /** What the loader knows about a configured material name, supplied by the Bukkit side. */
    sealed interface Resolution {
        /** [name] is the canonical Material name (e.g. "KELP"). */
        data class Item(val name: String) : Resolution
        /** The name is no Material at all. */
        data object Unknown : Resolution
        /** A Material that can't exist as an item stack (e.g. WATER, a wall-only block). */
        data class NotAnItem(val name: String) : Resolution
    }

    enum class Kind(val label: String) {
        UNKNOWN_MATERIAL("unknown material"),
        NOT_AN_ITEM("not an obtainable item"),
        UNSUPPORTED("never sellable"),
        INVALID_PRICE("price is not a number"),
        NEGATIVE_PRICE("negative price"),
        NON_FINITE_PRICE("price is not finite"),
        PRICE_TOO_HIGH("price above the maximum"),
        DISABLED("disabled (price 0)"),
        DUPLICATE("defined more than once"),
        CATEGORY_CONFLICT("category sell value ignored"),
        CATEGORY_ONLY("only in categories, not sellable"),
        CATEGORY_UNKNOWN("unknown material in categories"),
    }

    data class Problem(val kind: Kind, val key: String, val detail: String)

    /** One `categories.<id>.items.<key>` entry as read from YAML. */
    data class CategoryEntry(val category: String, val key: String, val material: String?, val sell: Any?)

    data class Catalog(
        /** Canonical material name → effective unit price. Only valid, enabled, positive prices. */
        val prices: Map<String, Double>,
        /** Canonical material name → the `prices:` key that supplied the price. */
        val sourceKeys: Map<String, String>,
        /** Canonical material name → the first category that lists it (display only). */
        val categoryOf: Map<String, String>,
        /** Canonical material name → why a configured entry is NOT sellable. */
        val rejected: Map<String, Problem>,
        val problems: List<Problem>,
    ) {
        fun count(kind: Kind) = problems.count { it.kind == kind }
    }

    /**
     * Materials that are never sellable whatever sell-prices.yml says: containers that would
     * destroy their contents (issue #610), spawners/spawn eggs, weapons, armor, the main tools,
     * and admin-only/unobtainable blocks.
     */
    fun unsupportedReason(name: String): String? = when {
        name.endsWith("SHULKER_BOX") -> "shulker boxes would destroy their contents"
        name.endsWith("_SPAWN_EGG") || name == "SPAWNER" || name == "TRIAL_SPAWNER" || name == "VAULT" -> "spawners and spawn eggs"
        WEAPON_ARMOR_SUFFIXES.any { name.endsWith(it) } || name in WEAPON_ARMOR_NAMES -> "weapons, armor and tools"
        name.startsWith("INFESTED_") || name in ADMIN_OR_UNOBTAINABLE -> "admin-only or unobtainable"
        else -> null
    }

    private val WEAPON_ARMOR_SUFFIXES = listOf(
        "_SWORD", "_PICKAXE", "_AXE", "_SHOVEL", "_HOE", "_SPEAR",
        "_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS", "_HORSE_ARMOR", "_NAUTILUS_ARMOR",
    )
    private val WEAPON_ARMOR_NAMES = setOf("BOW", "CROSSBOW", "TRIDENT", "MACE", "SHIELD", "ELYTRA", "WOLF_ARMOR", "TURTLE_HELMET")
    private val ADMIN_OR_UNOBTAINABLE = setOf(
        "BEDROCK", "BARRIER", "LIGHT", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW",
        "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART",
        "DEBUG_STICK", "KNOWLEDGE_BOOK", "END_PORTAL_FRAME", "REINFORCED_DEEPSLATE", "BUDDING_AMETHYST",
        "PETRIFIED_OAK_SLAB", "PLAYER_HEAD", "FARMLAND", "DIRT_PATH", "FROGSPAWN",
        "TEST_BLOCK", "TEST_INSTANCE_BLOCK",
    )

    sealed interface Price {
        data class Valid(val value: Double) : Price
        data class Invalid(val kind: Kind, val detail: String) : Price
    }

    /** Parses one YAML value. 0 = deliberately disabled. */
    fun parsePrice(raw: Any?): Price {
        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull() ?: return Price.Invalid(Kind.INVALID_PRICE, "'$raw'")
            null -> return Price.Invalid(Kind.INVALID_PRICE, "empty")
            else -> return Price.Invalid(Kind.INVALID_PRICE, "'$raw'")
        }
        return when {
            !value.isFinite() -> Price.Invalid(Kind.NON_FINITE_PRICE, "$value")
            value < 0.0 -> Price.Invalid(Kind.NEGATIVE_PRICE, format(value))
            value == 0.0 -> Price.Invalid(Kind.DISABLED, "0")
            value > MAX_UNIT_PRICE -> Price.Invalid(Kind.PRICE_TOO_HIGH, "${format(value)} > ${format(MAX_UNIT_PRICE)}")
            else -> Price.Valid(value)
        }
    }

    /**
     * Builds the effective catalog. [prices] are the `prices:` entries in file order. When two
     * keys name the same material (e.g. `kelp` and `KELP`) the later one wins, exactly as the old
     * loader behaved, and the conflict is reported so the owner can delete one of them.
     */
    fun build(
        prices: List<Pair<String, Any?>>,
        categories: List<CategoryEntry>,
        resolve: (String) -> Resolution,
    ): Catalog {
        val effective = LinkedHashMap<String, Double>()
        val sourceKeys = HashMap<String, String>()
        val rejected = HashMap<String, Problem>()
        val problems = mutableListOf<Problem>()

        for ((key, raw) in prices) {
            val name = when (val r = resolve(key)) {
                is Resolution.Item -> r.name
                is Resolution.NotAnItem -> { problems += Problem(Kind.NOT_AN_ITEM, key, r.name); continue }
                Resolution.Unknown -> { problems += Problem(Kind.UNKNOWN_MATERIAL, key, "no such material"); continue }
            }
            unsupportedReason(name)?.let { reason ->
                val p = Problem(Kind.UNSUPPORTED, key, reason)
                problems += p; rejected[name] = p
                continue
            }
            val previousKey = sourceKeys[name] ?: rejected[name]?.key
            when (val price = parsePrice(raw)) {
                is Price.Invalid -> {
                    val p = Problem(price.kind, key, price.detail)
                    if (price.kind != Kind.DISABLED) problems += p
                    if (previousKey != null) problems += Problem(Kind.DUPLICATE, key, "$name is also defined as '$previousKey'; '$key' wins")
                    effective.remove(name); sourceKeys.remove(name)
                    rejected[name] = p
                }
                is Price.Valid -> {
                    if (previousKey != null) {
                        val before = effective[name]?.let(::format) ?: "not sellable"
                        problems += Problem(Kind.DUPLICATE, key,
                            "$name is also defined as '$previousKey' ($before); '$key' (${format(price.value)}) wins")
                    }
                    effective[name] = price.value
                    sourceKeys[name] = key
                    rejected.remove(name)
                }
            }
        }

        val categoryOf = HashMap<String, String>()
        for (entry in categories) {
            val lookup = entry.material ?: entry.key
            val name = (resolve(lookup) as? Resolution.Item)?.name
            if (name == null) {
                problems += Problem(Kind.CATEGORY_UNKNOWN, "${entry.category}.${entry.key}", lookup)
                continue
            }
            categoryOf.putIfAbsent(name, entry.category)
            val price = effective[name]
            if (price == null) {
                if (name !in rejected) problems += Problem(Kind.CATEGORY_ONLY, "${entry.category}.${entry.key}", name)
                continue
            }
            val categorySell = (parsePrice(entry.sell) as? Price.Valid)?.value ?: continue
            if (categorySell != price) {
                problems += Problem(Kind.CATEGORY_CONFLICT, "${entry.category}.${entry.key}",
                    "category ${format(categorySell)}, prices ${format(price)} (prices: is used)")
            }
        }

        return Catalog(effective, sourceKeys, categoryOf, rejected, problems)
    }

    /** One log line per problem kind with a few examples, so a big file never floods the console. */
    fun summaryLines(catalog: Catalog): List<String> =
        catalog.problems.groupBy { it.kind }.entries.sortedBy { it.key.ordinal }.map { (kind, list) ->
            val examples = list.take(LOG_EXAMPLES).joinToString("; ") { "${it.key}: ${it.detail}" }
            val more = if (list.size > LOG_EXAMPLES) "; and ${list.size - LOG_EXAMPLES} more" else ""
            "${list.size} ${kind.label}: $examples$more"
        }

    /** Default keys to merge into a user file: skipped when ANY user key already names that material. */
    fun missingDefaults(defaultKeys: List<String>, userKeys: List<String>, resolve: (String) -> Resolution): List<String> {
        fun canonical(k: String) = (resolve(k) as? Resolution.Item)?.name ?: k.uppercase()
        val present = userKeys.mapTo(HashSet()) { canonical(it) }
        return defaultKeys.filter { canonical(it) !in present }
    }

    fun format(value: Double): String =
        if (value == Math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString()
        else "%.2f".format(java.util.Locale.ROOT, value)

    /** Money is paid in cents: rounds half up to two decimals. */
    fun roundCents(value: Double): Double = Math.round(value * 100.0) / 100.0
}

/**
 * Whether one item stack may be sold (issue #1416). Only an ordinary vanilla stack sells for its
 * material's price: a JoshyMC custom item, anything carrying plugin data (crate keys, vouchers,
 * Fly Vouchers, Resurge keys, ...), a custom model, or a container with items inside is refused,
 * even when its base material has a price. Renames, signatures and the Catalyst mutation are not
 * plugin "value" and keep selling as before.
 */
object SellItemRules {

    /** PDC keys that never make an otherwise vanilla stack valuable. */
    val HARMLESS_KEYS = setOf(
        "joshymc:catalyst_mutation", // Mutations: +50% sell value, meant to be sold
        "joshymc:death_drop",        // anti-dupe tag on every death drop
        "joshymc:signed", "joshymc:signer_uuid", "joshymc:signer_name", "joshymc:signed_at", // /signitem
    )

    /** JoshyMC fish (FishingManager) on a plain fish base keep selling as that fish. */
    val FISH_KEYS = setOf("joshymc:fish_id", "joshymc:fish_weight", "joshymc:fish_rarity")
    val FISH_BASES = setOf("COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH")

    data class Stack(
        val material: String,
        val amount: Int,
        val hasPrice: Boolean,
        val customItemId: String? = null,
        val dataKeys: Set<String> = emptySet(),
        /** The item_model component, "namespace:path", or null for the vanilla model. */
        val itemModel: String? = null,
        val hasCustomModelData: Boolean = false,
        val hasNestedItems: Boolean = false,
    )

    /** Null = sellable; otherwise the reason it is refused. */
    fun rejection(stack: Stack): String? {
        if (stack.amount <= 0 || stack.material == "AIR") return "empty"
        SellCatalog.unsupportedReason(stack.material)?.let { return "never sellable ($it)" }
        if (!stack.hasPrice) return "no sell price"
        if (stack.customItemId != null) return "JoshyMC custom item (${stack.customItemId})"
        if (stack.hasNestedItems) return "it still holds items"

        val fish = stack.material in FISH_BASES && "joshymc:fish_id" in stack.dataKeys
        val allowedKeys = if (fish) HARMLESS_KEYS + FISH_KEYS else HARMLESS_KEYS
        stack.dataKeys.firstOrNull { it !in allowedKeys }?.let { return "carries plugin data ($it)" }
        if (stack.hasCustomModelData) return "custom model data"
        val model = stack.itemModel
        if (model != null && model != "minecraft:${stack.material.lowercase()}" &&
            !(fish && model.startsWith("joshymc:fish/"))) return "custom item model ($model)"
        return null
    }
}
