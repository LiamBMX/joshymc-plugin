package com.liam.joshymc.lifesteal.profile

import com.liam.joshymc.manager.PurchaseRewards
import java.util.Locale

/** Achievement categories (issue #1429), in menu order. */
enum class AchievementCategory(val id: String, val label: String, val icon: String) {
    COMBAT("combat", "Combat", "IRON_SWORD"),
    SURVIVAL("survival", "Survival", "IRON_PICKAXE"),
    SOCIAL("social", "Social", "CAKE"),
    PROGRESSION("progression", "Progression", "CLOCK"),
    COMPETITIVE("competitive", "Competitive", "GOLDEN_HELMET");

    companion object {
        fun of(id: String?): AchievementCategory? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/** PERMANENT achievements are earned once ever; SEASONAL ones once per season, from that season's numbers. */
enum class AchievementPeriod(val id: String) {
    PERMANENT("permanent"), SEASONAL("seasonal");

    companion object {
        fun of(id: String?): AchievementPeriod? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/** VISIBLE = always listed; HIDDEN = shown as "???" until its first tier is completed. */
enum class AchievementVisibility(val id: String) {
    VISIBLE("visible"), HIDDEN("hidden");

    companion object {
        fun of(id: String?): AchievementVisibility? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/**
 * One reward of a tier. Store rewards are delivered by the existing PurchaseFulfillmentManager
 * queue (coins/credits offline, items when there is room, never dropped); titles and borders are
 * profile decorations owned by the claimed achievement row itself, so they need no delivery.
 */
sealed interface AchievementReward {
    fun describe(): String

    data class Store(val spec: PurchaseRewards.Spec) : AchievementReward {
        override fun describe(): String = when (spec.kind) {
            PurchaseRewards.Kind.COINS -> "${spec.param} Coins"
            PurchaseRewards.Kind.CREDITS -> "${spec.param} Credits"
            PurchaseRewards.Kind.TOKENS -> "${spec.quantity} Token(s)"
            PurchaseRewards.Kind.CRATE_KEY -> "${spec.quantity}x ${spec.param} Crate Key"
            PurchaseRewards.Kind.CHAT_TAG_VOUCHER -> "${spec.quantity}x ${spec.param} Chat Tag Voucher"
            else -> spec.describe()
        }
    }

    /** A profile title ([text] may use & colour codes). */
    data class Title(val text: String) : AchievementReward {
        override fun describe(): String = "Profile title: $text"
    }

    /** A profile border colour (one of [ProfileDecorations.BORDERS]). */
    data class Border(val color: String) : AchievementReward {
        override fun describe(): String = "Profile border: ${ProfileDecorations.borderLabel(color)}"
    }
}

data class AchievementTier(
    /** 1-based, stable: the tier's position in the list. Together with the achievement id it is the stable key. */
    val number: Int,
    val name: String?,
    val target: Long,
    val rewards: List<AchievementReward>,
)

data class AchievementDef(
    val id: String,
    val name: String,
    val description: String,
    val category: AchievementCategory,
    val stat: ProfileStat,
    val period: AchievementPeriod,
    val visibility: AchievementVisibility,
    val icon: String,
    val broadcast: Boolean,
    val tiers: List<AchievementTier>,
) {
    val single: Boolean get() = tiers.size == 1

    fun tier(number: Int): AchievementTier? = tiers.firstOrNull { it.number == number }

    /** "Slayer II (Silver)" style label for a tier. */
    fun tierLabel(tier: AchievementTier): String = when {
        single -> name
        tier.name != null -> "$name - ${tier.name}"
        else -> "$name ${tier.number}"
    }
}

/** Profile decorations that use only vanilla items (no resource-pack assets). */
object ProfileDecorations {
    /** Profile border colours: vanilla stained glass panes. */
    val BORDERS: List<String> = listOf(
        "gray", "white", "light_gray", "black", "red", "orange", "yellow", "lime", "green",
        "cyan", "light_blue", "blue", "purple", "magenta", "pink", "brown",
    )

    fun normalizeBorder(raw: String?): String? = raw?.trim()?.lowercase(Locale.ROOT)?.replace('-', '_')?.takeIf { it in BORDERS }

    fun borderMaterial(color: String): String = "${color.uppercase(Locale.ROOT)}_STAINED_GLASS_PANE"

    fun borderLabel(color: String): String = color.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    const val MAX_FEATURED = 3
    const val MAX_TITLE_LENGTH = 48
}

/**
 * Parses `lifesteal-profiles.yml > achievements`. Bukkit-free: the YAML arrives as plain maps and
 * lists. A broken achievement is reported in [Result.problems] and left out; the others still load
 * (one typo never removes every achievement). Rewards that could grant hearts, ranks or ranked
 * advantages can't even be written: only [ALLOWED_STORE_KINDS], titles and borders exist.
 */
object AchievementParser {

    val ALLOWED_STORE_KINDS: Set<PurchaseRewards.Kind> = setOf(
        PurchaseRewards.Kind.COINS, PurchaseRewards.Kind.CREDITS, PurchaseRewards.Kind.TOKENS,
        PurchaseRewards.Kind.CRATE_KEY, PurchaseRewards.Kind.CHAT_TAG_VOUCHER,
    )

    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
    const val MAX_TIERS = 10
    const val MAX_REWARDS_PER_TIER = 5
    const val MAX_ACHIEVEMENTS = 200

    data class Context(
        val catalog: PurchaseRewards.Catalog,
        /** Coins rewards only where this server's economy is Lifesteal's. */
        val coinsAllowed: Boolean,
        /** Division id -> 1-based index, or null when divisions are off. */
        val divisionIndex: ((String) -> Int?)?,
        /** Playtime-based achievements only where playtime is Lifesteal's. */
        val playtimeAllowed: Boolean,
    )

    data class Result(val achievements: List<AchievementDef>, val problems: List<String>)

    fun parse(section: Map<String, Any?>?, ctx: Context): Result {
        if (section == null) return Result(emptyList(), emptyList())
        val out = ArrayList<AchievementDef>()
        val problems = ArrayList<String>()
        for ((rawId, value) in section) {
            if (out.size >= MAX_ACHIEVEMENTS) {
                problems += "more than $MAX_ACHIEVEMENTS achievements; the rest are ignored"
                break
            }
            val map = asMap(value)
            if (map == null) {
                problems += "achievement '$rawId' is not a section"
                continue
            }
            when (val parsed = parseOne(rawId, map, ctx, problems)) {
                is Parsed.Ok -> out += parsed.def
                is Parsed.Error -> problems += "achievement '$rawId': ${parsed.message}"
            }
        }
        return Result(out, problems)
    }

    private sealed interface Parsed {
        data class Ok(val def: AchievementDef) : Parsed
        data class Error(val message: String) : Parsed
    }

    private fun parseOne(rawId: String, map: Map<String, Any?>, ctx: Context, warnings: MutableList<String>): Parsed {
        val id = rawId.trim().lowercase(Locale.ROOT)
        if (!ID.matches(id)) return Parsed.Error("the id must be 1-32 lower-case letters, digits or '-'")
        if (map["enabled"] == false) return Parsed.Error("disabled")
        val name = (map["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 48 } ?: return Parsed.Error("missing or too long name")
        val description = (map["description"] as? String)?.trim()?.take(120) ?: ""
        val category = AchievementCategory.of(map["category"] as? String) ?: return Parsed.Error("unknown category '${map["category"]}'")
        val stat = ProfileStat.of(map["stat"] as? String) ?: return Parsed.Error("unknown stat '${map["stat"]}'")
        val period = if (map["type"] == null) AchievementPeriod.PERMANENT else AchievementPeriod.of(map["type"] as? String) ?: return Parsed.Error("type must be permanent or seasonal")
        if (period == AchievementPeriod.SEASONAL && !stat.seasonal) return Parsed.Error("stat ${stat.id} has no seasonal value, so it can't be seasonal")
        if (stat == ProfileStat.PLAYTIME_HOURS && !ctx.playtimeAllowed) return Parsed.Error("this server's playtime isn't Lifesteal's (it also hosts Survival)")
        if (stat == ProfileStat.PVP_DEATHS) return Parsed.Error("achievements may not reward dying (pvp-deaths)")
        val visibility = if (map["visibility"] == null) AchievementVisibility.VISIBLE else AchievementVisibility.of(map["visibility"] as? String) ?: return Parsed.Error("visibility must be visible or hidden")
        val icon = (map["icon"] as? String)?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.matches(Regex("^[A-Z0-9_]{1,64}$")) } ?: category.icon
        val broadcast = map["broadcast"] == true
        val rawTiers = map["tiers"] as? List<*> ?: return Parsed.Error("missing tiers list")
        if (rawTiers.isEmpty() || rawTiers.size > MAX_TIERS) return Parsed.Error("needs 1-$MAX_TIERS tiers")
        val tiers = ArrayList<AchievementTier>()
        val division = stat == ProfileStat.PLAYER_DIVISION || stat == ProfileStat.TEAM_DIVISION
        for ((i, rawTier) in rawTiers.withIndex()) {
            val t = asMap(rawTier) ?: return Parsed.Error("tier ${i + 1} is not a section")
            val target: Long = if (division) {
                val lookup = ctx.divisionIndex ?: return Parsed.Error("ranked divisions are off, so division achievements can't load")
                val divisionId = (t["division"] as? String)?.trim()?.lowercase(Locale.ROOT) ?: return Parsed.Error("tier ${i + 1} needs 'division: <id>'")
                lookup(divisionId)?.toLong() ?: return Parsed.Error("tier ${i + 1}: unknown division '$divisionId'")
            } else {
                (t["target"] as? Number)?.toLong() ?: return Parsed.Error("tier ${i + 1} needs a whole-number target")
            }
            if (target < 1 || target > 1_000_000_000L) return Parsed.Error("tier ${i + 1}: target must be 1..1000000000")
            if (tiers.isNotEmpty() && target <= tiers.last().target) return Parsed.Error("tier targets must strictly increase")
            val tierName = (t["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(24)
            val rawRewards = (t["rewards"] as? List<*>) ?: emptyList<Any?>()
            if (rawRewards.size > MAX_REWARDS_PER_TIER) return Parsed.Error("tier ${i + 1}: at most $MAX_REWARDS_PER_TIER rewards")
            val rewards = ArrayList<AchievementReward>()
            for (raw in rawRewards) {
                when (val r = parseReward(raw?.toString(), ctx)) {
                    is RewardParse.Ok -> rewards += r.reward
                    is RewardParse.Skip -> warnings += "achievement '$id' tier ${i + 1}: ${r.message}"
                    is RewardParse.Error -> return Parsed.Error("tier ${i + 1}: ${r.message}")
                }
            }
            tiers += AchievementTier(i + 1, tierName, target, rewards)
        }
        return Parsed.Ok(AchievementDef(id, name, description, category, stat, period, visibility, icon, broadcast, tiers))
    }

    sealed interface RewardParse {
        data class Ok(val reward: AchievementReward) : RewardParse
        data class Error(val message: String) : RewardParse
        /** Left out on this server only (the achievement still loads), e.g. coins where the economy is Survival's. */
        data class Skip(val message: String) : RewardParse
    }

    /** One reward line: `coins 500`, `credits 5`, `tokens 3`, `cratekey <crate> 1`, `chattagvoucher <tag> 1`, `title <text>`, `border <colour>`. */
    fun parseReward(line: String?, ctx: Context): RewardParse {
        val text = line?.trim().orEmpty()
        if (text.isEmpty()) return RewardParse.Error("empty reward")
        val parts = text.split(Regex("\\s+"))
        val kind = parts[0].lowercase(Locale.ROOT)
        if (kind == "title") {
            val title = text.substring(parts[0].length).trim()
            if (title.isEmpty() || title.length > ProfileDecorations.MAX_TITLE_LENGTH) return RewardParse.Error("a title needs 1-${ProfileDecorations.MAX_TITLE_LENGTH} characters")
            return RewardParse.Ok(AchievementReward.Title(title))
        }
        if (kind == "border") {
            val color = ProfileDecorations.normalizeBorder(parts.getOrNull(1)) ?: return RewardParse.Error("unknown border colour '${parts.getOrNull(1)}'")
            return RewardParse.Ok(AchievementReward.Border(color))
        }
        val storeKind = PurchaseRewards.Kind.of(kind) ?: return RewardParse.Error("unknown reward '$kind'")
        if (storeKind !in ALLOWED_STORE_KINDS) return RewardParse.Error("'$kind' rewards are not allowed for achievements (no ranks, hearts or ranked advantages)")
        if (storeKind == PurchaseRewards.Kind.COINS && !ctx.coinsAllowed) return RewardParse.Skip("coins reward left out: this server's economy is Survival's (coins need a Lifesteal-only server)")
        return when (val p = PurchaseRewards.parse(parts, ctx.catalog)) {
            is PurchaseRewards.Result.Ok -> if (p.value.reference != null) RewardParse.Error("a reward line can't carry a reference") else RewardParse.Ok(AchievementReward.Store(p.value.spec))
            is PurchaseRewards.Result.Error -> RewardParse.Error(p.message)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(value: Any?): Map<String, Any?>? = (value as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
}

/** Progress, completion and claim rules. Bukkit-free. */
object AchievementRules {

    /** `lifesteal_achievements.period` for [def] in the current season ([seasonId] null = no season: seasonal ones can't progress). */
    fun period(def: AchievementDef, seasonId: String?): String? = when (def.period) {
        AchievementPeriod.PERMANENT -> Seasons.PERMANENT
        AchievementPeriod.SEASONAL -> seasonId
    }

    /** The value [def] is measured against: lifetime numbers, or the current season's. */
    fun value(def: AchievementDef, season: StatValues?, lifetime: StatValues): Long? = when (def.period) {
        AchievementPeriod.PERMANENT -> lifetime[def.stat]
        AchievementPeriod.SEASONAL -> season?.get(def.stat)
    }

    data class Key(val achievementId: String, val tier: Int, val period: String)

    /** Tiers reached but not yet recorded. Every reached tier is recorded, even when lower ones weren't yet (a jump of several tiers at once). */
    fun newlyReached(defs: List<AchievementDef>, season: StatValues?, lifetime: StatValues, seasonId: String?, recorded: Set<Key>): List<Pair<AchievementDef, AchievementTier>> {
        val out = ArrayList<Pair<AchievementDef, AchievementTier>>()
        for (def in defs) {
            val period = period(def, seasonId) ?: continue
            val value = value(def, season, lifetime) ?: continue
            for (tier in def.tiers) {
                if (value >= tier.target && Key(def.id, tier.number, period) !in recorded) out += def to tier
            }
        }
        return out
    }

    enum class State { LOCKED, IN_PROGRESS, CLAIMABLE, CLAIMING, CLAIMED }

    /** The tier a menu shows for [def]: the first one not yet claimed, else the last. */
    fun shownTier(def: AchievementDef, statuses: Map<Int, String>): AchievementTier =
        def.tiers.firstOrNull { statuses[it.number] != "CLAIMED" } ?: def.tiers.last()

    fun state(def: AchievementDef, tier: AchievementTier, value: Long?, status: String?): State = when (status) {
        "CLAIMED" -> State.CLAIMED
        "CLAIMING" -> State.CLAIMING
        "COMPLETED" -> State.CLAIMABLE
        else -> if (value == null || value <= 0L) State.LOCKED else if (value >= tier.target) State.CLAIMABLE else State.IN_PROGRESS
    }

    /** `[■■■■■□□□□□] 50%` style bar; never shows full before the target is reached. */
    fun bar(value: Long, target: Long, width: Int = 10): String {
        val v = value.coerceAtLeast(0)
        var filled = if (target <= 0) width else ((v * width) / target).toInt().coerceIn(0, width)
        if (v < target && filled >= width) filled = width - 1
        val percent = if (target <= 0) 100 else ((v * 100) / target).coerceIn(0, 100).let { if (v < target && it >= 100) 99 else it }
        return "■".repeat(filled) + "□".repeat(width - filled) + " $percent%"
    }

    enum class Sort(val id: String, val label: String) {
        DEFAULT("default", "Default"), CLAIMABLE_FIRST("claimable", "Claimable First"), PROGRESS("progress", "Closest to Done"), NAME("name", "Name");

        fun next(): Sort = entries[(ordinal + 1) % entries.size]
    }

    data class Row(val def: AchievementDef, val tier: AchievementTier, val value: Long?, val state: State)

    fun sort(rows: List<Row>, sort: Sort): List<Row> = when (sort) {
        Sort.DEFAULT -> rows
        Sort.NAME -> rows.sortedBy { it.def.name.lowercase(Locale.ROOT) }
        Sort.CLAIMABLE_FIRST -> rows.sortedBy { if (it.state == State.CLAIMABLE) 0 else if (it.state == State.CLAIMED) 2 else 1 }
        Sort.PROGRESS -> rows.sortedByDescending { r ->
            if (r.state == State.CLAIMED) -1.0 else (r.value ?: 0).toDouble() / r.tier.target.coerceAtLeast(1)
        }
    }

    /** Whether a hidden achievement may be shown with its real name. */
    fun revealed(def: AchievementDef, statuses: Map<Int, String>): Boolean =
        def.visibility == AchievementVisibility.VISIBLE || statuses.isNotEmpty()

    /** Purchase delivery reference for one reward of one claim (UNIQUE with the recipient, so a retry never pays twice). */
    fun rewardReference(key: Key, index: Int): String = "ach:${key.achievementId}:${key.tier}:${key.period}:$index"

    fun parseKey(raw: String?): Key? {
        val parts = raw?.split(':') ?: return null
        if (parts.size != 3) return null
        val tier = parts[1].toIntOrNull() ?: return null
        return Key(parts[0], tier, parts[2])
    }

    fun keyText(key: Key): String = "${key.achievementId}:${key.tier}:${key.period}"
}
