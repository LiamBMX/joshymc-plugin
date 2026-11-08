package com.liam.joshymc.lifesteal.ranks

import com.liam.joshymc.lifesteal.ranks.LifestealRankConfig.Perk
import com.liam.joshymc.lifesteal.ranks.LifestealRankConfig.RankDef
import com.liam.joshymc.lifesteal.ranks.LifestealRankConfig.RequirementType
import com.liam.joshymc.lifesteal.ranks.LifestealRankConfig.Settings

/**
 * The Lifesteal rank rules (issue #1428). Bukkit-free.
 *
 * Hierarchy: a player's **current rank** is the highest-order *enabled* rank they own. They get
 * the perks, permissions and kits of every enabled rank at or below it, so higher ranks always
 * include the lower ranks' benefits. Ownership itself is permanent and cumulative: an upgrade adds
 * the higher rank and keeps the lower ones, disabling a rank only stops it counting (nothing is
 * deleted), and a season never touches ownership.
 */
object LifestealRankRules {

    /** How a grant was asked for. */
    enum class Source(val id: String) {
        /** `/freerank` on Lifesteal: needs `acquisition.free`. */
        FREE("free"),
        /** Claimed from `/lsranks` once every requirement is met: needs `acquisition.progression`. */
        PROGRESSION("progression"),
        /** `/purchase fulfill <player> lsrank <rank>`: needs `acquisition.store`. */
        STORE("store"),
        /** `/lsranks admin grant`: any enabled rank. */
        ADMIN("admin");
    }

    enum class Decision(val message: String) {
        GRANT("Granted."),
        SYSTEM_DISABLED("Lifesteal ranks are turned off right now."),
        UNKNOWN_RANK("That Lifesteal rank doesn't exist."),
        RANK_DISABLED("That rank is turned off right now."),
        NOT_OFFERED("That rank can't be obtained that way."),
        ALREADY_OWNED("You already own that rank."),
        HIGHER_OWNED("You already have a higher rank."),
        PENDING("A rank change for this player is still being applied."),
        REQUIREMENTS_NOT_MET("Not every requirement for that rank is met yet."),
    }

    /** The highest-order enabled rank among [owned], or null. */
    fun currentRank(settings: Settings, owned: Collection<String>): RankDef? =
        settings.enabledRanks.filter { it.id in owned }.maxByOrNull { it.order }

    /** Every enabled rank at or below [current] (lowest first): what the player inherits. */
    fun inherited(settings: Settings, current: RankDef?): List<RankDef> =
        if (current == null) emptyList() else settings.enabledRanks.filter { it.order <= current.order }

    /** True when [owned] reaches [target] (owns it or a higher enabled rank) and [target] is enabled. */
    fun reaches(settings: Settings, owned: Collection<String>, target: RankDef): Boolean {
        if (!target.enabled) return false
        val current = currentRank(settings, owned) ?: return false
        return current.order >= target.order
    }

    /** Each perk's value: the largest of every inherited rank's value; disabled perks are 0. */
    fun perks(settings: Settings, owned: Collection<String>): Map<Perk, Int> {
        if (!settings.enabled) return emptyMap()
        val ranks = inherited(settings, currentRank(settings, owned))
        val out = mutableMapOf<Perk, Int>()
        for (perk in Perk.entries) {
            if (perk in settings.disabledPerks) continue
            val value = ranks.mapNotNull { it.perks[perk] }.maxOrNull() ?: continue
            out[perk] = value.coerceIn(0, perk.max)
        }
        return out
    }

    fun perk(settings: Settings, owned: Collection<String>, perk: Perk): Int = perks(settings, owned)[perk] ?: 0

    /** Every inherited rank's permissions, re-checked against [forbiddenNodeReason] (never trusted from the parser alone). */
    fun permissions(settings: Settings, owned: Collection<String>): Set<String> {
        if (!settings.enabled) return emptySet()
        return inherited(settings, currentRank(settings, owned))
            .flatMap { it.permissions }
            .filter { forbiddenNodeReason(it) == null }
            .toSortedSet()
    }

    /** Whether a grant of [target] via [source] may go ahead for someone owning [owned]. Requirements are checked separately. */
    fun decideGrant(settings: Settings, owned: Collection<String>, targetId: String, source: Source, pending: Boolean): Decision {
        if (!settings.enabled) return Decision.SYSTEM_DISABLED
        val target = settings.rank(targetId) ?: return Decision.UNKNOWN_RANK
        if (!target.enabled) return Decision.RANK_DISABLED
        val offered = when (source) {
            Source.FREE -> target.acquisition.free
            Source.PROGRESSION -> target.acquisition.progression && target.requirements.isNotEmpty()
            Source.STORE -> target.acquisition.store
            Source.ADMIN -> true
        }
        if (!offered) return Decision.NOT_OFFERED
        if (target.id in owned) return Decision.ALREADY_OWNED
        val current = currentRank(settings, owned)
        // Never "downgrade": a lower rank adds nothing to someone who already inherits it.
        if (current != null && current.order > target.order) return Decision.HIGHER_OWNED
        if (pending) return Decision.PENDING
        return Decision.GRANT
    }

    // ── Progression ─────────────────────────────────────────────────────

    /** A player's counted statistics. A null value means that statistic couldn't be read: its requirement is never met. */
    data class Stats(val values: Map<RequirementType, Long?>)

    data class RequirementProgress(val type: RequirementType, val required: Int, val current: Long?) {
        val met: Boolean get() = current != null && current >= required
    }

    fun progress(rank: RankDef, stats: Stats): List<RequirementProgress> =
        rank.requirements.entries.sortedBy { it.key.ordinal }.map { (type, required) ->
            RequirementProgress(type, required, stats.values[type])
        }

    /** Every requirement met (and at least one exists: a rank with none is never earned by progression). */
    fun requirementsMet(rank: RankDef, stats: Stats): Boolean {
        val rows = progress(rank, stats)
        return rows.isNotEmpty() && rows.all { it.met }
    }

    /** The next enabled rank above [current] that is earned by progression, or null. */
    fun nextProgressionRank(settings: Settings, current: RankDef?): RankDef? =
        settings.enabledRanks.firstOrNull { it.acquisition.progression && (current == null || it.order > current.order) }

    // ── Kits ────────────────────────────────────────────────────────────

    enum class KitAccess { NOT_A_RANK_KIT, ALLOWED, LOCKED, RANK_DISABLED, SYSTEM_DISABLED }

    /**
     * Whether [owned] may claim a rank kit. A kit no rank lists is [KitAccess.NOT_A_RANK_KIT] (only
     * its kits.yml permission decides). A rank kit is claimable by its rank and every higher rank.
     */
    fun kitAccess(settings: Settings, owned: Collection<String>, kit: String): KitAccess {
        val rank = settings.rankForKit(kit) ?: return KitAccess.NOT_A_RANK_KIT
        if (!settings.enabled) return KitAccess.SYSTEM_DISABLED
        if (!rank.enabled) return KitAccess.RANK_DISABLED
        return if (reaches(settings, owned, rank)) KitAccess.ALLOWED else KitAccess.LOCKED
    }

    /** The rank kits in hierarchy order (the `/kit` Rank Kits order when `kits.rank-order.lifesteal` is empty). */
    fun kitOrder(settings: Settings): List<String> = settings.ranks.flatMap { it.kits }

    /** A kit cooldown after the player's reduction perk (0-50%), never below one minute unless the base is 0. */
    fun kitCooldownMillis(baseHours: Int, reductionPercent: Int): Long {
        if (baseHours <= 0) return 0L
        val base = baseHours.toLong() * 3_600_000L
        val pct = reductionPercent.coerceIn(0, Perk.KIT_COOLDOWN_REDUCTION_PERCENT.max)
        return (base * (100 - pct) / 100).coerceAtLeast(60_000L)
    }

    /** Cooldown left: [claimedAt] null = never claimed. */
    fun cooldownRemaining(claimedAt: Long?, cooldownMillis: Long, now: Long): Long {
        if (claimedAt == null) return 0L
        return (claimedAt + cooldownMillis - now).coerceAtLeast(0L)
    }

    // ── Permission safety ───────────────────────────────────────────────

    private val FORBIDDEN_EXACT = setOf(
        "op", "*", "joshymc", "joshymc.eco", "joshymc.give", "joshymc.vouchers", "joshymc.rank", "joshymc.rankvoucher",
        "joshymc.creditvoucher", "joshymc.enchant", "joshymc.customenchant", "joshymc.god", "joshymc.heal", "joshymc.feed",
        "joshymc.fly", "joshymc.speed", "joshymc.vanish", "joshymc.invsee", "joshymc.sudo", "joshymc.smite", "joshymc.tp",
        "joshymc.tphere", "joshymc.modmode", "joshymc.freeze", "joshymc.createkit", "joshymc.editkit", "joshymc.deletekit",
        "joshymc.jgm.lock", "joshymc.jgm.unlock", "joshymc.playerdata.reset", "joshymc.gamemode", "joshymc.gmc",
        "joshymc.gms", "joshymc.gma", "joshymc.gmsp", "joshymc.reload",
    )

    private val FORBIDDEN_PREFIXES = listOf(
        "luckperms.", "minecraft.", "bukkit.", "paper.", "worldguard.", "worldedit.", "grim.", "essentials.",
        "joshymc.punish", "joshymc.staff", "joshymc.eco.", "joshymc.credits.", "joshymc.lifesteal.hearts",
        "joshymc.lifesteal.ranked.", "joshymc.lifesteal.ranks.", "joshymc.lifesteal.progression", "joshymc.lifesteal.combat",
        "joshymc.lifesteal.bounty.admin", "joshymc.lifesteal.team.admin", "joshymc.gamemode.", "joshymc.rank.",
        "joshymc.rankperk.", "joshymc.internal.", "joshymc.kit.", "joshymc.jgm", "joshymc.ban", "joshymc.tempban",
        "joshymc.unban", "joshymc.mute", "joshymc.tempmute", "joshymc.unmute", "joshymc.warn", "joshymc.unwarn",
        "joshymc.pkick", "joshymc.history", "joshymc.alts", "joshymc.reports", "joshymc.fly.", "joshymc.heal.",
        "joshymc.feed.", "joshymc.event.",
    )

    private val FORBIDDEN_WORDS = listOf("admin", "others", "bypass", "staff", "punish", "operator", "override", "recover")

    /**
     * Why [node] may never be part of a Lifesteal rank, or null when it may. Refuses wildcards,
     * other plugins' nodes, every admin/staff/punishment/economy-admin/heart/ranked/gamemode node,
     * kit nodes (rank kits are decided by the hierarchy, and `joshymc.kit.*` also grants the kit
     * cooldown bypasses), and the combat-relevant self-help commands (heal, feed, god, fly, speed).
     */
    fun forbiddenNodeReason(node: String): String? {
        val n = node.trim().lowercase()
        if (n.isEmpty()) return "empty"
        if (n.startsWith("-")) return "negated nodes aren't supported"
        if ('*' in n) return "wildcards are never granted"
        if (n in FORBIDDEN_EXACT) return "administrative or combat-relevant command"
        FORBIDDEN_PREFIXES.firstOrNull { n == it.trimEnd('.') || n.startsWith(it) }?.let { return "administrative, staff, economy-admin, heart, ranked or kit node ($it)" }
        if (!n.startsWith("joshymc.")) return "only JoshyMC player nodes may be granted"
        FORBIDDEN_WORDS.firstOrNull { word -> n.split('.', '-', '_').any { it == word } }?.let { return "'$it' nodes are never granted by a rank" }
        return null
    }
}
