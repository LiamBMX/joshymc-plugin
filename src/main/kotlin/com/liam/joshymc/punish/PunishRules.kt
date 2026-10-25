package com.liam.joshymc.punish

import com.liam.joshymc.gamemode.ServerGamemode

/**
 * The /punish rule model (issue #1298). Bukkit-free.
 *
 * Staff type `/punish <player> <keyword>`; the keyword resolves to a [PunishRule] with a stable
 * numeric [PunishRule.id] (history is keyed by it, so aliases and renames never split a
 * history) and a readable [PunishRule.displayName], which is the only thing players ever see.
 */

/** What one tier does. */
enum class PunishAction {
    WARN,
    MUTE,
    KICK,
    TEMPBAN,
    PERMBAN,
    /** Ban evasion: lengthen the player's active ban (never shortens it). */
    EXTEND_BAN;

    val isBan: Boolean get() = this == TEMPBAN || this == PERMBAN || this == EXTEND_BAN
}

/**
 * One punishment tier. [durationMs] is the length for MUTE / TEMPBAN, the amount added for
 * EXTEND_BAN (null = add the existing ban's own original length), and null otherwise.
 */
data class PunishTier(val action: PunishAction, val durationMs: Long? = null) {

    /** "Warning", "10-Minute Mute", "7-Day Ban", "Permanent Ban", "+14-Day Ban Extension"... */
    fun describe(): String = when (action) {
        PunishAction.WARN -> "Warning"
        PunishAction.KICK -> "Kick"
        PunishAction.PERMBAN -> "Permanent Ban"
        PunishAction.MUTE -> "${PunishDurations.label(durationMs!!)} Mute"
        PunishAction.TEMPBAN -> "${PunishDurations.label(durationMs!!)} Ban"
        PunishAction.EXTEND_BAN ->
            if (durationMs == null) "Ban Extension (original length)"
            else "+${PunishDurations.label(durationMs)} Ban Extension"
    }

    /** The config form: W, K, PB, M10m, B7d, +B14d, EXT. */
    fun code(): String = when (action) {
        PunishAction.WARN -> "W"
        PunishAction.KICK -> "K"
        PunishAction.PERMBAN -> "PB"
        PunishAction.MUTE -> "M${PunishDurations.code(durationMs!!)}"
        PunishAction.TEMPBAN -> "B${PunishDurations.code(durationMs!!)}"
        PunishAction.EXTEND_BAN -> if (durationMs == null) "EXT" else "+B${PunishDurations.code(durationMs)}"
    }

    companion object {
        /** Parses one tier code (case-insensitive). Null when malformed. */
        fun parse(raw: String): PunishTier? {
            val s = raw.trim()
            val upper = s.uppercase()
            return when {
                upper == "W" -> PunishTier(PunishAction.WARN)
                upper == "K" -> PunishTier(PunishAction.KICK)
                upper == "PB" -> PunishTier(PunishAction.PERMBAN)
                upper == "EXT" -> PunishTier(PunishAction.EXTEND_BAN)
                upper.startsWith("+B") -> PunishDurations.parse(s.substring(2))?.let { PunishTier(PunishAction.EXTEND_BAN, it) }
                upper.startsWith("M") -> PunishDurations.parse(s.substring(1))?.let { PunishTier(PunishAction.MUTE, it) }
                upper.startsWith("B") -> PunishDurations.parse(s.substring(1))?.let { PunishTier(PunishAction.TEMPBAN, it) }
                else -> null
            }
        }
    }
}

/**
 * A tier slot: the [primary] punishment plus an optional [alternate] staff may pick with
 * documented justification (attempted-exploit tier 1: a warning, or a 1-day ban). Written
 * `W|B1d` in config.
 */
data class TierSlot(val primary: PunishTier, val alternate: PunishTier? = null) {
    fun code(): String = primary.code() + (alternate?.let { "|${it.code()}" } ?: "")

    companion object {
        fun parse(raw: String): TierSlot? {
            val parts = raw.split('|')
            if (parts.size > 2) return null
            val primary = PunishTier.parse(parts[0]) ?: return null
            val alternate = if (parts.size == 2) PunishTier.parse(parts[1]) ?: return null else null
            return TierSlot(primary, alternate)
        }
    }
}

enum class RuleCategory(val displayName: String) {
    GENERAL("General Gameplay"),
    ACCOUNT("Account Rules"),
    ECONOMY("Economy"),
    CREDITS("Credits, Ranks & Vouchers"),
    CRATES("Crates"),
    SPAWNERS("Spawners"),
    CLAIMS("Claims & Building"),
    REGIONS("Regions"),
    PVP("PvP"),
    TEAMS("Teams"),
    KITS("Kits"),
    QUESTS("Quests"),
    STOCKS("Stock Market"),
    AUCTIONS("Auctions & Buy Orders"),
    CHAT("Chat"),
    COMMANDS("Commands & GUIs"),
    ITEMS("Items"),
    PERFORMANCE("Server Performance"),
    STAFF("Staff & Enforcement"),
    LOOPHOLES("Loopholes");

    /** Lower-case, hyphenated id used by `/punish rules <category>`. */
    val id: String get() = name.lowercase()

    companion object {
        fun fromId(raw: String): RuleCategory? {
            val key = PunishRules.normalize(raw)
            return entries.firstOrNull { it.id == key || PunishRules.normalize(it.displayName) == key }
        }
    }
}

/** How long an offense keeps counting towards escalation. */
enum class EscalationWindow { MINOR, SERIOUS }

data class PunishRule(
    val id: Int,
    val keyword: String,
    val displayName: String,
    val category: RuleCategory,
    /** Exactly five tiers, or null for an informational rule that can never be punished. */
    val tiers: List<TierSlot>?,
    /** Gamemodes the rule applies in. */
    val gamemodes: Set<ServerGamemode>,
    /** Lowest staff level that may punish this rule. Null for informational rules. */
    val minLevel: StaffLevel?,
    val window: EscalationWindow,
) {
    val informational: Boolean get() = tiers == null

    /** The tier slot for offense number [offense] (1-based); offenses past five keep tier five. */
    fun slotFor(offense: Int): TierSlot? = tiers?.let { it[(offense.coerceAtLeast(1) - 1).coerceAtMost(it.size - 1)] }
}

/**
 * The eight staff punishment levels, lowest first. A higher level holds every lower level's
 * rules (enforced here and by plugin.yml children), and a level's [StaffLevel.allows] decides
 * how strong a punishment it may issue.
 */
enum class StaffLevel(val id: String, val displayName: String) {
    TRAINEE("trainee", "Trainee"),
    HELPER("helper", "Helper"),
    JRMOD("jrmod", "Jr Mod"),
    MOD("mod", "Mod"),
    LEADMOD("leadmod", "Lead Mod"),
    JRADMIN("jradmin", "Jr Admin"),
    ADMIN("admin", "Admin"),
    SRADMIN("sradmin", "Sr Admin");

    val permission: String get() = "joshymc.punish.$id"

    fun atLeast(other: StaffLevel): Boolean = ordinal >= other.ordinal

    /** Longest temporary ban (or ban extension) this level may issue; null = any length. 0 = none. */
    val maxBanMs: Long?
        get() = when (this) {
            TRAINEE, HELPER, JRMOD -> 0L
            MOD -> 7 * PunishDurations.DAY
            LEADMOD, JRADMIN -> 30 * PunishDurations.DAY
            ADMIN, SRADMIN -> null
        }

    /**
     * Whether this level may issue [tier]. [extensionMs] is the real amount an EXT tier adds
     * (null when the ban being extended is permanent, so nothing is added).
     */
    fun allows(tier: PunishTier, extensionMs: Long? = tier.durationMs): Boolean = when (tier.action) {
        PunishAction.WARN -> true
        PunishAction.MUTE -> atLeast(HELPER)
        PunishAction.KICK -> atLeast(MOD)
        PunishAction.PERMBAN -> atLeast(ADMIN)
        PunishAction.TEMPBAN -> withinBanCap(tier.durationMs!!)
        PunishAction.EXTEND_BAN -> atLeast(MOD) && (extensionMs == null || withinBanCap(extensionMs))
    }

    private fun withinBanCap(ms: Long): Boolean {
        val cap = maxBanMs ?: return true
        return cap > 0 && ms <= cap
    }

    /** May overturn an incident of [action]: warnings from Jr Mod up, everything else Sr Admin. */
    fun mayOverturn(action: PunishAction): Boolean =
        if (action == PunishAction.WARN) atLeast(JRMOD) else this == SRADMIN

    /** Sr Admin only: tier overrides, emergency-suspension reviews, audit checks. */
    val isSenior: Boolean get() = this == SRADMIN

    companion object {
        /** The highest level among [held] (permission checks already resolved), or null. */
        fun highest(held: (StaffLevel) -> Boolean): StaffLevel? = entries.lastOrNull(held)

        fun fromId(id: String?): StaffLevel? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/** Duration parsing/formatting for tiers: s, m, h, d, w, mo (30 days). Bukkit-free. */
object PunishDurations {
    const val SECOND = 1_000L
    const val MINUTE = 60 * SECOND
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR
    const val WEEK = 7 * DAY
    const val MONTH = 30 * DAY

    private val TOKEN = Regex("(\\d+)(mo|[smhdw])")

    fun parse(raw: String): Long? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        val matches = TOKEN.findAll(s).toList()
        if (matches.isEmpty() || matches.sumOf { it.value.length } != s.length) return null
        var total = 0L
        try {
            for (m in matches) {
                val unit = when (m.groupValues[2]) {
                    "mo" -> MONTH; "w" -> WEEK; "d" -> DAY; "h" -> HOUR; "m" -> MINUTE; else -> SECOND
                }
                total = Math.addExact(total, Math.multiplyExact(m.groupValues[1].toLong(), unit))
            }
        } catch (_: ArithmeticException) {
            return null
        } catch (_: NumberFormatException) {
            return null
        }
        return total.takeIf { it > 0 && it <= 100L * 365 * DAY }
    }

    /** "10-Minute", "1-Hour", "7-Day"; mixed lengths fall back to "1d 6h". */
    fun label(ms: Long): String {
        val (amount, unit) = when {
            ms % DAY == 0L -> ms / DAY to "Day"
            ms % HOUR == 0L -> ms / HOUR to "Hour"
            ms % MINUTE == 0L -> ms / MINUTE to "Minute"
            ms % SECOND == 0L -> ms / SECOND to "Second"
            else -> return "${ms}ms"
        }
        return "$amount-$unit"
    }

    /** "10 minutes", "1 hour", "7 days" for player messages. */
    fun words(ms: Long): String {
        val label = label(ms)
        val amount = label.substringBefore('-')
        val unit = label.substringAfter('-', "").lowercase()
        if (unit.isEmpty()) return label
        return "$amount $unit" + if (amount == "1") "" else "s"
    }

    /** The shortest config code for [ms]: 30m, 6h, 7d. */
    fun code(ms: Long): String = when {
        ms % DAY == 0L -> "${ms / DAY}d"
        ms % HOUR == 0L -> "${ms / HOUR}h"
        ms % MINUTE == 0L -> "${ms / MINUTE}m"
        else -> "${ms / SECOND}s"
    }
}

/**
 * The built-in 73 rules: ids, canonical hyphenated keywords, readable names, categories, the
 * lowest staff level that may use each, and the default five tiers. Tiers, names, gamemodes and
 * windows can be overridden per keyword in punish-rules.yml; ids and keywords cannot.
 */
object PunishRules {

    /** Lower-case, spaces/underscores turned into hyphens, doubled hyphens collapsed. */
    fun normalize(raw: String): String =
        raw.trim().lowercase().replace(Regex("[\\s_]+"), "-").replace(Regex("-+"), "-").trim('-')

    /** Same as [normalize] with every hyphen removed: the alias form ("creditvoucherabuse"). */
    fun squash(raw: String): String = normalize(raw).replace("-", "")

    private class Def(val id: Int, val keyword: String, val name: String, val category: RuleCategory, val level: StaffLevel?, val tiers: String?)

    private val T = StaffLevel.TRAINEE
    private val H = StaffLevel.HELPER
    private val M = StaffLevel.MOD
    private val LM = StaffLevel.LEADMOD
    private val JA = StaffLevel.JRADMIN
    private val A = StaffLevel.ADMIN

    private val DEFS: List<Def> = listOf(
        // General gameplay
        Def(1, "hacking", "Hacking or Cheating", RuleCategory.GENERAL, JA, "B7d, B14d, B30d, B90d, PB"),
        Def(2, "automation", "Unauthorized Gameplay Automation", RuleCategory.GENERAL, JA, "W, B1d, B7d, B30d, PB"),
        Def(3, "duping", "Duplication or Exploitation", RuleCategory.GENERAL, JA, "B7d, B30d, B60d, B90d, PB"),
        Def(4, "exploit-concealment", "Concealing Serious Exploits", RuleCategory.GENERAL, JA, "B3d, B7d, B30d, B90d, PB"),
        Def(5, "aiding-cheaters", "Aiding Cheaters or Exploiters", RuleCategory.GENERAL, LM, "B1d, B7d, B14d, B30d, PB"),
        // Accounts
        Def(6, "account-limit", "Exceeding Account Limit", RuleCategory.ACCOUNT, LM, "W, B1d, B3d, B7d, B30d"),
        Def(7, "alt-abuse", "Alt Account Abuse", RuleCategory.ACCOUNT, LM, "W, B3d, B7d, B30d, PB"),
        Def(8, "ban-evasion", "Punishment Evasion", RuleCategory.ACCOUNT, LM, "EXT, +B7d, +B14d, +B30d, PB"),
        // Economy
        Def(9, "economy-abuse", "Economy Abuse", RuleCategory.ECONOMY, JA, "B3d, B7d, B30d, B90d, PB"),
        Def(10, "market-manipulation", "Artificial Market Manipulation", RuleCategory.ECONOMY, JA, "W, B3d, B7d, B30d, PB"),
        Def(11, "auction-abuse", "Auction House Abuse", RuleCategory.ECONOMY, JA, "B1d, B3d, B7d, B30d, PB"),
        Def(12, "buy-order-abuse", "Buy Order Abuse", RuleCategory.ECONOMY, JA, "B1d, B3d, B7d, B30d, PB"),
        Def(13, "selling-abuse", "Selling System Abuse", RuleCategory.ECONOMY, JA, "W, B1d, B7d, B30d, PB"),
        Def(14, "irl-trading", "Unauthorized IRL Trading", RuleCategory.ECONOMY, JA, "B7d, B14d, B30d, B90d, PB"),
        // Credits, ranks & vouchers
        Def(15, "credit-abuse", "Credit Abuse", RuleCategory.CREDITS, JA, "B3d, B7d, B30d, B90d, PB"),
        Def(16, "credit-voucher-abuse", "Credit Voucher Abuse", RuleCategory.CREDITS, JA, "B7d, B14d, B30d, B90d, PB"),
        Def(17, "rank-voucher-abuse", "Rank Voucher Abuse", RuleCategory.CREDITS, JA, "B7d, B14d, B30d, B90d, PB"),
        Def(18, "tag-voucher-abuse", "Chat Tag Voucher Abuse", RuleCategory.CREDITS, JA, "W, B1d, B7d, B30d, PB"),
        // Crates
        Def(19, "crate-abuse", "Crate Abuse", RuleCategory.CRATES, JA, "B3d, B7d, B14d, B30d, PB"),
        Def(20, "fake-crate-keys", "Forged or Duplicated Crate Keys", RuleCategory.CRATES, JA, "B3d, B7d, B30d, B90d, PB"),
        Def(21, "crate-selection-abuse", "Crate Selection Abuse", RuleCategory.CRATES, JA, "B1d, B3d, B7d, B30d, PB"),
        // Spawners
        Def(22, "spawner-info", "Informational Only", RuleCategory.SPAWNERS, null, null),
        Def(23, "spawner-abuse", "Spawner Abuse", RuleCategory.SPAWNERS, JA, "B1d, B3d, B7d, B30d, PB"),
        Def(24, "spawner-stealing", "Bypassing Spawner Ownership", RuleCategory.SPAWNERS, LM, "W, B1d, B3d, B14d, B30d"),
        // Claims & building
        Def(25, "claim-abuse", "Claim Abuse", RuleCategory.CLAIMS, M, "W, B1d, B3d, B14d, B30d"),
        Def(26, "protected-griefing", "Griefing Protected Areas", RuleCategory.CLAIMS, M, "B1d, B3d, B7d, B30d, PB"),
        Def(27, "block-protection-abuse", "Block Protection Abuse", RuleCategory.CLAIMS, M, "W, B1d, B3d, B14d, B30d"),
        Def(28, "inappropriate-build", "Inappropriate Builds", RuleCategory.CLAIMS, M, "W, B1d, B3d, B14d, B30d"),
        // Regions
        Def(29, "region-info", "Informational Only", RuleCategory.REGIONS, null, null),
        Def(30, "region-abuse", "Region Boundary Abuse", RuleCategory.REGIONS, M, "W, B1d, B3d, B14d, B30d"),
        Def(31, "safe-zone-abuse", "Safe Zone Abuse", RuleCategory.REGIONS, M, "B1d, B3d, B7d, B30d, PB"),
        Def(32, "arena-info", "Informational Only", RuleCategory.REGIONS, null, null),
        // PvP
        Def(33, "combat-logging", "Combat Logging", RuleCategory.PVP, M, "W, B1h, B6h, B1d, B3d"),
        Def(34, "stat-boosting", "Stat Boosting", RuleCategory.PVP, M, "W, B1d, B3d, B7d, B30d"),
        Def(35, "bounty-farming", "Bounty Farming", RuleCategory.PVP, M, "W, B1d, B3d, B7d, B30d"),
        // Teams
        Def(36, "team-abuse", "Team System Abuse", RuleCategory.TEAMS, LM, "W, B1d, B3d, B14d, B30d"),
        Def(37, "team-bank-abuse", "Team Bank Abuse", RuleCategory.TEAMS, JA, "B3d, B7d, B14d, B30d, PB"),
        Def(38, "team-boosting", "Team Kill Boosting", RuleCategory.TEAMS, M, "W, B1d, B3d, B7d, B30d"),
        // Kits
        Def(39, "kit-abuse", "Kit Cooldown Abuse", RuleCategory.KITS, M, "W, B1d, B3d, B7d, B30d"),
        Def(40, "kit-duping", "Kit Duplication", RuleCategory.KITS, JA, "B3d, B7d, B14d, B30d, PB"),
        Def(41, "staff-kit-abuse", "Unauthorized Staff Kit Editing", RuleCategory.KITS, JA, "B7d, B14d, B30d, B90d, PB"),
        // Quests
        Def(42, "quest-abuse", "Quest Exploitation", RuleCategory.QUESTS, JA, "W, B1d, B3d, B7d, B30d"),
        Def(43, "streak-abuse", "Login Streak Abuse", RuleCategory.QUESTS, M, "W, B1d, B3d, B7d, B30d"),
        Def(44, "quest-master-abuse", "Quest Master Reward Abuse", RuleCategory.QUESTS, JA, "B1d, B3d, B7d, B30d, PB"),
        // Stock market
        Def(45, "stock-info", "Informational Only", RuleCategory.STOCKS, null, null),
        Def(46, "stock-abuse", "Stock Market Exploitation", RuleCategory.STOCKS, JA, "B3d, B7d, B14d, B30d, PB"),
        Def(47, "stock-alt-abuse", "Stock Alt Account Abuse", RuleCategory.STOCKS, JA, "W, B1d, B3d, B14d, B30d"),
        // Auctions & buy orders
        Def(48, "illegal-listing", "Illegal Auction Listings", RuleCategory.AUCTIONS, LM, "W, B1d, B3d, B14d, B30d"),
        Def(49, "escrow-abuse", "Escrow or Refund Abuse", RuleCategory.AUCTIONS, JA, "B3d, B7d, B14d, B30d, PB"),
        Def(50, "rank-limit-abuse", "Bypassing Rank Limits", RuleCategory.AUCTIONS, LM, "W, B1d, B3d, B7d, B30d"),
        // Chat
        Def(51, "harassment", "Harassment or Disrespect", RuleCategory.CHAT, T, "W, M30m, M2h, M12h, M3d"),
        Def(52, "spam", "Spamming", RuleCategory.CHAT, T, "W, M10m, M30m, M1h, M6h"),
        Def(53, "advertising", "Unauthorized Advertising", RuleCategory.CHAT, T, "W, M1h, M6h, M1d, M7d"),
        Def(54, "filter-bypass", "Chat Filter Bypassing", RuleCategory.CHAT, T, "W, M10m, M30m, M2h, M12h"),
        Def(55, "inappropriate-content", "Inappropriate Content", RuleCategory.CHAT, T, "W, M1h, M6h, B1d, B7d"),
        // Commands & GUIs
        Def(56, "command-abuse", "Command Abuse", RuleCategory.COMMANDS, M, "W, B30m, B3h, B1d, B7d"),
        Def(57, "gui-abuse", "GUI Exploitation", RuleCategory.COMMANDS, JA, "B1d, B3d, B7d, B30d, PB"),
        Def(58, "command-exploit", "Command Exploitation", RuleCategory.COMMANDS, JA, "W, B1d, B3d, B14d, B30d"),
        // Items
        Def(59, "illegal-items", "Illegal Items", RuleCategory.ITEMS, LM, "W, B1d, B7d, B30d, PB"),
        Def(60, "item-data-abuse", "Custom Item Data Manipulation", RuleCategory.ITEMS, JA, "B3d, B7d, B14d, B30d, PB"),
        Def(61, "hidden-illegal-items", "Concealing Illegal Items", RuleCategory.ITEMS, LM, "W, B1d, B3d, B14d, B30d"),
        // Server performance
        Def(62, "lag-machine", "Intentional Lag Machines", RuleCategory.PERFORMANCE, JA, "B1d, B3d, B7d, B30d, PB"),
        Def(63, "laggy-farm", "Excessively Laggy Farms", RuleCategory.PERFORMANCE, M, "W, B1d, B3d, B7d, B30d"),
        Def(64, "server-crashing", "Intentional Server Crashing", RuleCategory.PERFORMANCE, A, "PB, PB, PB, PB, PB"),
        // Staff & enforcement
        Def(65, "staff-disrespect", "Refusing Staff Instructions", RuleCategory.STAFF, M, "W, K, B1h, B1d, B7d"),
        Def(66, "false-evidence", "False Evidence During Investigations", RuleCategory.STAFF, M, "W, B1d, B3d, B14d, B30d"),
        Def(67, "staff-interference", "Interfering With Moderation", RuleCategory.STAFF, M, "W, K, B1d, B3d, B14d"),
        Def(68, "appeal-info", "Informational Only", RuleCategory.STAFF, null, null),
        // Loopholes
        Def(69, "rule-intent", "Informational Only", RuleCategory.LOOPHOLES, null, null),
        Def(70, "loophole-info", "Informational Only", RuleCategory.LOOPHOLES, null, null),
        Def(71, "attempted-exploit", "Attempted Exploitation", RuleCategory.LOOPHOLES, JA, "W|B1d, B3d, B7d, B30d, PB"),
        Def(72, "encouraging-violations", "Encouraging Rule Breaking", RuleCategory.LOOPHOLES, H, "W, M1h, M1d, B3d, B30d"),
        Def(73, "reversal-guideline", "Informational Only", RuleCategory.LOOPHOLES, null, null),
    )

    /** Parses "W, M10m, ..." into exactly five slots, or null. */
    fun parseTiers(raw: List<String>): List<TierSlot>? {
        if (raw.size != 5) return null
        return raw.map { TierSlot.parse(it) ?: return null }
    }

    /** Minor offenses start with a warning; rules whose first tier is already a ban are serious. */
    private fun defaultWindow(tiers: List<TierSlot>?): EscalationWindow =
        if (tiers != null && tiers.first().primary.action.isBan) EscalationWindow.SERIOUS else EscalationWindow.MINOR

    /** The built-in rules, in id order. */
    val DEFAULTS: List<PunishRule> = DEFS.map { d ->
        val tiers = d.tiers?.let { parseTiers(it.split(',')) ?: error("Bad built-in tiers for ${d.keyword}") }
        PunishRule(
            id = d.id,
            keyword = d.keyword,
            displayName = d.name,
            category = d.category,
            tiers = tiers,
            gamemodes = ServerGamemode.entries.toSet(),
            minLevel = d.level,
            window = defaultWindow(tiers),
        )
    }
}

/**
 * The loaded rules plus lookup. Built from [PunishRules.DEFAULTS] with per-keyword overrides
 * (see `PunishRuleConfig`). Keywords resolve case-insensitively, with spaces/underscores read
 * as hyphens, and the hyphen-less form accepted as an alias; every form maps to the same id.
 */
class PunishRuleRegistry(val rules: List<PunishRule>) {

    private val byKeyword: Map<String, PunishRule> = rules.associateBy { it.keyword }
    private val bySquashed: Map<String, PunishRule> = rules.associateBy { PunishRules.squash(it.keyword) }
    private val byId: Map<Int, PunishRule> = rules.associateBy { it.id }

    fun byId(id: Int): PunishRule? = byId[id]

    fun find(input: String): PunishRule? {
        val key = PunishRules.normalize(input)
        return byKeyword[key] ?: bySquashed[PunishRules.squash(key)]
    }

    fun inCategory(category: RuleCategory): List<PunishRule> = rules.filter { it.category == category }

    /** Enforceable rules [level] may use, for tab completion and the rule picker. */
    fun usableBy(level: StaffLevel): List<PunishRule> =
        rules.filter { !it.informational && it.minLevel != null && level.atLeast(it.minLevel) }

    companion object {
        val DEFAULT = PunishRuleRegistry(PunishRules.DEFAULTS)
    }
}
