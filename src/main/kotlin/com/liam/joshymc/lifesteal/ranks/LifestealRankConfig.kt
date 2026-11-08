package com.liam.joshymc.lifesteal.ranks

import java.util.Locale

/**
 * `lifesteal-ranks.yml` (issue #1428): the Lifesteal rank hierarchy, perks, rank kits and
 * progression requirements. Bukkit-free: the file is read through [Source], so every rule can be
 * checked on its own.
 *
 * Like `lifesteal.yml`, any error makes the whole file invalid ([Result.settings] null): rank
 * perks, rank kits and new grants then pause, and nothing a player owns is touched or guessed.
 */
object LifestealRankConfig {

    const val FILE = "lifesteal-ranks.yml"
    const val CONFIG_VERSION = 1

    data class Problem(val severe: Boolean, val message: String)

    /** Reads one value (`get(path)`) and the child keys of a section (`keys(path)`, null when it isn't one). */
    interface Source {
        fun get(path: String): Any?
        fun keys(path: String): List<String>?
    }

    /** How a rank can be obtained. Staff grants (`/lsranks admin grant`) always work for an enabled rank. */
    data class Acquisition(val free: Boolean, val progression: Boolean, val store: Boolean)

    enum class RequirementType(val key: String, val label: String, val unit: String) {
        /** Total time played on this server (`playtime` table), in hours. Always lifetime. */
        PLAYTIME_HOURS("playtime-hours", "Playtime", "h"),
        /** Lifesteal PvP kills that actually moved a heart (passed every anti-abuse rule of the heart system). */
        PVP_KILLS("pvp-kills", "PvP kills", ""),
        /** Different players among those kills, so farming one alt counts once. */
        UNIQUE_VICTIMS("unique-victims", "Different players killed", "");

        companion object {
            fun of(key: String): RequirementType? = entries.firstOrNull { it.key == key }
        }
    }

    enum class RequirementScope(val id: String) {
        /** Everything since the player first played here. */
        LIFETIME("lifetime"),
        /** PvP requirements only count since the current season's launch (playtime is always lifetime). */
        SEASON("season");

        companion object {
            fun of(id: String?): RequirementScope? = entries.firstOrNull { it.id == id?.lowercase(Locale.ROOT) }
        }
    }

    /** The configurable perk categories. Every one is a convenience/economy perk: none affects combat. */
    enum class Perk(val key: String, val label: String, val max: Int) {
        HOMES("homes", "Homes", 100),
        VAULTS("vaults", "Personal vaults", 54),
        SELL_BONUS_PERCENT("sell-bonus-percent", "Sell bonus (%)", 25),
        AUCTION_EXTRA_LISTINGS("auction-extra-listings", "Extra Auction House listings", 100),
        ORDER_EXTRA_ORDERS("order-extra-orders", "Extra Buy Orders", 100),
        KIT_COOLDOWN_REDUCTION_PERCENT("kit-cooldown-reduction-percent", "Rank kit cooldown reduction (%)", 50);

        companion object {
            fun of(key: String): Perk? = entries.firstOrNull { it.key == key }
        }
    }

    /**
     * Perk keys that would be pay-to-win in a competitive gamemode (issue #1428 section 4). They are
     * refused by name with a clear message, so nobody mistakes them for a typo of an allowed perk.
     */
    val PAY_TO_WIN_PERKS: Set<String> = setOf(
        "hearts", "extra-hearts", "starting-hearts", "max-hearts", "maximum-hearts", "heart-limit",
        "damage", "damage-bonus", "pvp-damage", "strength",
        "damage-reduction", "defense", "resistance", "pvp-defense",
        "invulnerability", "god", "god-mode",
        "illegal-enchants", "enchant-limit", "overenchant",
        "combat-tag-bypass", "combat-bypass", "no-combat-tag",
        "elo", "elo-bonus", "rating-bonus", "ranked-bonus", "ranked-advantage",
        "heart-steal-immunity", "heart-protection", "keep-hearts",
    )

    data class Cosmetics(val prefix: String, val chatColor: String, val icon: String, val unlocks: List<String>)

    data class RankDef(
        val id: String,
        val displayName: String,
        /** `#RRGGBB`. */
        val color: String,
        val order: Int,
        val description: String,
        val enabled: Boolean,
        /** The LuckPerms group JoshyMC adds (never replaces) for this rank. */
        val group: String,
        val acquisition: Acquisition,
        val requirements: Map<RequirementType, Int>,
        val requirementScope: RequirementScope,
        /** Nodes JoshyMC applies itself while the player is in a Lifesteal world (never written to LuckPerms). */
        val permissions: List<String>,
        val kits: List<String>,
        val perks: Map<Perk, Int>,
        val cosmetics: Cosmetics,
    )

    data class Settings(
        val enabled: Boolean,
        /** LuckPerms `server` context for the rank groups, or null for global nodes. */
        val serverContext: String?,
        val disabledPerks: Set<Perk>,
        val refuseKitsWhileCombatTagged: Boolean,
        /** Lowest order first. */
        val ranks: List<RankDef>,
    ) {
        private val byId = ranks.associateBy { it.id }
        fun rank(id: String?): RankDef? = id?.let { byId[it.lowercase(Locale.ROOT)] }
        val enabledRanks: List<RankDef> get() = ranks.filter { it.enabled }
        /** The rank whose kits include [kit] (lowest order first), or null when no rank lists it. */
        fun rankForKit(kit: String): RankDef? = ranks.firstOrNull { r -> r.kits.any { it.equals(kit, ignoreCase = true) } }
        /** The rank `/freerank` gives on Lifesteal: the lowest enabled rank marked `acquisition.free`. */
        val freeRank: RankDef? get() = enabledRanks.firstOrNull { it.acquisition.free }
    }

    data class Result(val settings: Settings?, val problems: List<Problem>)

    /**
     * Group names a Lifesteal rank may never use: staff groups, Survival donor tiers, JoshyMC's
     * display ranks and LuckPerms' `default`. Adding one of those through a rank grant would hand
     * out staff power or a Survival rank.
     */
    data class Reserved(val groups: Set<String>)

    private val ID = Regex("[a-z0-9_]{1,32}")
    private val GROUP = Regex("[a-z0-9_.-]{1,36}")
    private val HEX = Regex("#[0-9a-fA-F]{6}")
    private val CONTEXT = Regex("[A-Za-z0-9_.-]{1,64}")
    private val NODE = Regex("[a-z0-9_.*-]{1,128}")

    fun parse(source: Source, reserved: Reserved): Result {
        val problems = mutableListOf<Problem>()
        fun severe(msg: String) { problems += Problem(true, msg) }
        fun warn(msg: String) { problems += Problem(false, msg) }

        val version = source.get("config-version")
        when {
            version == null -> warn("config-version is missing; assuming $CONFIG_VERSION.")
            (version as? Number)?.toInt() == null -> severe("config-version must be a number.")
            (version as Number).toInt() > CONFIG_VERSION -> severe("config-version ${version.toInt()} is newer than this plugin understands ($CONFIG_VERSION).")
        }

        val enabled = bool(source, "enabled", true, ::severe)
        val contextRaw = (source.get("luckperms.server-context") ?: "").toString().trim()
        val context = when {
            contextRaw.isEmpty() -> null
            CONTEXT.matches(contextRaw) -> contextRaw
            else -> { severe("luckperms.server-context '$contextRaw' may only use letters, digits, '.', '_' and '-'."); null }
        }
        val disabledPerks = mutableSetOf<Perk>()
        for (raw in list(source, "disabled-perks", ::severe)) {
            val perk = Perk.of(raw.lowercase(Locale.ROOT))
            if (perk == null) severe("disabled-perks: '$raw' is not a perk (${Perk.entries.joinToString { it.key }}).") else disabledPerks += perk
        }
        val refuseCombat = bool(source, "kits.refuse-while-combat-tagged", true, ::severe)

        val ids = source.keys("ranks")
        if (ids == null) severe("ranks: section is missing.")
        val ranks = mutableListOf<RankDef>()
        val orders = mutableMapOf<Int, String>()
        val groups = mutableMapOf<String, String>()
        val kitOwners = mutableMapOf<String, String>()
        for (rawId in ids.orEmpty()) {
            val id = rawId.lowercase(Locale.ROOT)
            if (!ID.matches(id) || id != rawId) {
                severe("ranks.$rawId: rank ids must be 1-32 lower-case letters, digits or '_'.")
                continue
            }
            val p = "ranks.$id"
            val name = (source.get("$p.display-name") ?: "").toString().trim()
            if (name.isEmpty() || name.length > 32) severe("$p.display-name must be 1-32 characters.")
            val color = (source.get("$p.color") ?: "#FFFFFF").toString().trim()
            if (!HEX.matches(color)) severe("$p.color '$color' must be #RRGGBB.")
            val order = (source.get("$p.order") as? Number)?.toInt()
            when {
                order == null -> severe("$p.order must be a whole number.")
                order !in 1..10_000 -> severe("$p.order must be 1-10000.")
                orders.containsKey(order) -> severe("$p.order $order is already used by ${orders[order]}.")
                else -> orders[order] = id
            }
            val description = (source.get("$p.description") ?: "").toString()
            if (description.length > 200) severe("$p.description is longer than 200 characters.")
            val rankEnabled = bool(source, "$p.enabled", true, ::severe)
            val group = (source.get("$p.group") ?: id).toString().trim().lowercase(Locale.ROOT)
            when {
                !GROUP.matches(group) -> severe("$p.group '$group' is not a valid LuckPerms group name.")
                group in reserved.groups -> severe("$p.group '$group' is a staff, Survival or default group; a Lifesteal rank may never grant it.")
                groups.containsKey(group) -> severe("$p.group '$group' is already used by ${groups[group]}.")
                else -> groups[group] = id
            }
            val acquisition = Acquisition(
                free = bool(source, "$p.acquisition.free", false, ::severe),
                progression = bool(source, "$p.acquisition.progression", false, ::severe),
                store = bool(source, "$p.acquisition.store", false, ::severe),
            )

            val requirements = mutableMapOf<RequirementType, Int>()
            for (key in source.keys("$p.requirements").orEmpty()) {
                val type = RequirementType.of(key)
                val value = (source.get("$p.requirements.$key") as? Number)?.toInt()
                when {
                    type == null -> severe("$p.requirements.$key is not a requirement type (${RequirementType.entries.joinToString { it.key }}).")
                    value == null || value !in 1..1_000_000 -> severe("$p.requirements.$key must be a whole number from 1 to 1000000.")
                    else -> requirements[type] = value
                }
            }
            val scopeRaw = source.get("$p.requirements-scope")?.toString()
            val scope = if (scopeRaw == null) RequirementScope.LIFETIME else RequirementScope.of(scopeRaw)
                ?: RequirementScope.LIFETIME.also { severe("$p.requirements-scope '$scopeRaw' must be lifetime or season.") }
            if (acquisition.progression && requirements.isEmpty()) {
                severe("$p: acquisition.progression is on but there are no requirements; that would give the rank away for free.")
            }
            if (!acquisition.progression && requirements.isNotEmpty()) {
                warn("$p has requirements but acquisition.progression is off; they are only shown, never used to grant it.")
            }
            if (scope == RequirementScope.SEASON && RequirementType.PLAYTIME_HOURS in requirements) {
                warn("$p: playtime-hours always counts lifetime playtime (playtime isn't recorded per season).")
            }

            val permissions = mutableListOf<String>()
            for (node in list(source, "$p.permissions", ::severe)) {
                val n = node.trim().lowercase(Locale.ROOT)
                val reason = LifestealRankRules.forbiddenNodeReason(n)
                when {
                    !NODE.matches(n) -> severe("$p.permissions: '$node' is not a permission node.")
                    reason != null -> severe("$p.permissions: '$node' is refused ($reason).")
                    else -> permissions += n
                }
            }

            val kits = list(source, "$p.kits", ::severe).map { it.trim().lowercase(Locale.ROOT) }
            for (kit in kits) {
                if (!ID.matches(kit)) severe("$p.kits: '$kit' is not a kit name.")
                kitOwners[kit]?.let { severe("$p.kits: kit '$kit' is already the kit of rank $it.") }
                kitOwners[kit] = id
            }

            val perks = mutableMapOf<Perk, Int>()
            for (key in source.keys("$p.perks").orEmpty()) {
                val lower = key.lowercase(Locale.ROOT)
                if (lower in PAY_TO_WIN_PERKS) {
                    severe("$p.perks.$key is refused: Lifesteal ranks may not change hearts, damage, defense, combat tags, enchant limits or Elo.")
                    continue
                }
                val perk = Perk.of(lower)
                val value = (source.get("$p.perks.$key") as? Number)?.toInt()
                when {
                    perk == null -> severe("$p.perks.$key is not a perk (${Perk.entries.joinToString { it.key }}).")
                    value == null || value !in 0..perk.max -> severe("$p.perks.$key must be a whole number from 0 to ${perk.max}.")
                    else -> perks[perk] = value
                }
            }

            val icon = (source.get("$p.cosmetics.icon") ?: "NAME_TAG").toString().trim().uppercase(Locale.ROOT)
            val unlocks = list(source, "$p.cosmetics.unlocks", ::severe)
            val cosmetics = Cosmetics(
                prefix = (source.get("$p.cosmetics.prefix") ?: "").toString(),
                chatColor = (source.get("$p.cosmetics.chat-color") ?: "").toString(),
                icon = icon,
                unlocks = unlocks,
            )
            if (cosmetics.prefix.length > 48) severe("$p.cosmetics.prefix is longer than 48 characters.")

            ranks += RankDef(id, name, color.uppercase(Locale.ROOT), order ?: 0, description, rankEnabled, group, acquisition,
                requirements, scope, permissions, kits, perks, cosmetics)
        }
        if (ids != null && ranks.isEmpty()) severe("ranks: no rank is defined.")
        val sorted = ranks.sortedBy { it.order }
        if (sorted.count { it.acquisition.free } > 1) warn("More than one rank has acquisition.free; /freerank gives the lowest enabled one.")

        val settings = if (problems.any { it.severe }) null
        else Settings(enabled, context, disabledPerks, refuseCombat, sorted)
        return Result(settings, problems)
    }

    private fun bool(source: Source, path: String, default: Boolean, severe: (String) -> Unit): Boolean = when (val v = source.get(path)) {
        null -> default
        is Boolean -> v
        else -> { severe("$path must be true or false."); default }
    }

    private fun list(source: Source, path: String, severe: (String) -> Unit): List<String> = when (val v = source.get(path)) {
        null -> emptyList()
        is List<*> -> v.mapNotNull { it?.toString() }
        else -> { severe("$path must be a list."); emptyList() }
    }
}
