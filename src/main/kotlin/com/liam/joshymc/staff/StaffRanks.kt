package com.liam.joshymc.staff

import com.liam.joshymc.punish.PunishDurations
import com.liam.joshymc.punish.StaffLevel

/**
 * The twelve Minecraft staff ranks (issue #1383), lowest first. This order is the
 * organizational authority used for *protection* only: a staff member can never act on a
 * rank at or above their own. It is **not** technical permission inheritance. What a rank
 * may actually do still comes from its permission nodes (LuckPerms), so Staff Manager and
 * Community Manager sit above Lead Admin here without getting any administrative node.
 *
 * Bukkit-free. Discord-only roles (Partnership/Event/Giveaway/Build Manager) and the Media /
 * Discord player ranks are deliberately not here: they are not Minecraft staff.
 */
enum class StaffRank(val id: String, val displayName: String, val track: Track) {
    TRAINEE("trainee", "Trainee", Track.MODERATION),
    HELPER("helper", "Helper", Track.MODERATION),
    JR_MOD("jr_mod", "Jr Mod", Track.MODERATION),
    MOD("mod", "Mod", Track.MODERATION),
    LEAD_MOD("lead_mod", "Lead Mod", Track.MODERATION),
    JR_ADMIN("jr_admin", "Jr Admin", Track.ADMINISTRATION),
    ADMIN("admin", "Admin", Track.ADMINISTRATION),
    LEAD_ADMIN("lead_admin", "Lead Admin", Track.ADMINISTRATION),
    COMMUNITY_MANAGER("community_manager", "Community Manager", Track.MANAGEMENT),
    STAFF_MANAGER("staff_manager", "Staff Manager", Track.MANAGEMENT),
    HEAD_MANAGER("head_manager", "Head Manager", Track.MANAGEMENT),
    OWNER("owner", "Owner", Track.OWNER);

    /** Operational moderation, technical administration, in-game management, ownership. */
    enum class Track { MODERATION, ADMINISTRATION, MANAGEMENT, OWNER }

    fun above(other: StaffRank): Boolean = ordinal > other.ordinal

    /**
     * LuckPerms group names that mean this rank when nothing is configured: the id itself
     * and the same id without underscores (`jr_mod` and `jrmod`). These only match groups
     * that really exist on the server; nothing here creates a group.
     */
    val defaultGroups: List<String> get() = listOf(id, id.replace("_", "")).distinct()

    companion object {
        fun fromId(id: String?): StaffRank? {
            val key = id?.trim()?.lowercase()?.replace('-', '_') ?: return null
            return entries.firstOrNull { it.id == key || it.id.replace("_", "") == key }
        }

        fun highest(ranks: Iterable<StaffRank?>): StaffRank? = ranks.filterNotNull().maxOrNull()
    }
}

/**
 * Which LuckPerms groups (or JoshyMC `ranks.list` ids) stand for which [StaffRank]. Built from
 * `config.yml > staff-ranks.groups`; a rank with no entry uses [StaffRank.defaultGroups].
 * Group names are compared lower-case.
 */
class StaffRankGroups(configured: Map<StaffRank, List<String>> = emptyMap()) {
    private val byRank: Map<StaffRank, List<String>> = StaffRank.entries.associateWith { rank ->
        (configured[rank]?.takeIf { it.isNotEmpty() } ?: rank.defaultGroups)
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    }

    /** Problems found while building: a group mapped to two ranks keeps only the higher one. */
    val problems: List<String>
    private val byGroup: Map<String, StaffRank>

    init {
        val found = mutableListOf<String>()
        val map = HashMap<String, StaffRank>()
        for (rank in StaffRank.entries) {
            for (group in byRank.getValue(rank)) {
                val previous = map[group]
                if (previous != null && previous != rank) found += "group '$group' is mapped to both ${previous.id} and ${rank.id}; ${rank.id} wins"
                map[group] = rank
            }
        }
        problems = found
        byGroup = map
    }

    fun groupsFor(rank: StaffRank): List<String> = byRank.getValue(rank)

    /** The staff rank a LuckPerms group / JoshyMC rank id stands for, or null. */
    fun rankForGroup(group: String?): StaffRank? = group?.trim()?.lowercase()?.let { byGroup[it] }

    /** `group.<name>` permission nodes LuckPerms sets for every group a player inherits. */
    fun groupNodes(rank: StaffRank): List<String> = groupsFor(rank).map { "group.$it" }

    /**
     * The highest staff rank shown by a player's data. [explicit] = permission nodes explicitly
     * granted (effective permissions with value true), [recorded] = JoshyMC `player_ranks` ids.
     * Only these count: a bare `hasPermission("group.x")` is true for ops and `*` holders, so
     * it is never used here (ops are handled by the caller).
     */
    fun resolve(explicit: Set<String>, recorded: Collection<String>): StaffRank? {
        val lowered = explicit.mapTo(HashSet()) { it.lowercase() }
        val fromGroups = StaffRank.entries.filter { rank -> groupNodes(rank).any { it in lowered } }
        val fromRecords = recorded.mapNotNull { rankForGroup(it) }
        return StaffRank.highest(fromGroups + fromRecords)
    }
}

/** Who is acting: the console, or a player with their resolved staff rank (null = not staff). */
sealed interface StaffActor {
    data object Console : StaffActor
    data class Staff(val rank: StaffRank?) : StaffActor
}

/** Who is being acted on. [rank] null = not staff (or never seen as staff on this server). */
data class StaffTarget(val rank: StaffRank?, val self: Boolean)

/**
 * The one rank-protection rule (issue #1383). Used by `/punish`, the legacy punishment
 * commands, the `/admin` panel, `/rank`, `/sudo` and snapshot restores. Bukkit-free.
 */
object StaffProtection {
    enum class Decision(val message: String?) {
        ALLOWED(null),
        SELF("You cannot do that to yourself."),
        HIGHER_RANK("You cannot do that to a staff member ranked above you."),
        EQUAL_RANK("You cannot do that to a staff member of your own rank."),
        NOT_STAFF("You cannot do that to a staff member.");

        val allowed: Boolean get() = this == ALLOWED
    }

    /**
     * Console: always allowed. Self: refused. A non-staff target: allowed. A staff target:
     * only when the actor's rank is strictly above it ([allowEqual] = the configured
     * `staff-ranks.allow-equal-rank-actions`, default false, also allows equal rank).
     */
    fun decide(actor: StaffActor, target: StaffTarget, allowEqual: Boolean = false): Decision {
        if (actor is StaffActor.Console) return Decision.ALLOWED
        if (target.self) return Decision.SELF
        val targetRank = target.rank ?: return Decision.ALLOWED
        val actorRank = (actor as StaffActor.Staff).rank ?: return Decision.NOT_STAFF
        return when {
            actorRank.above(targetRank) -> Decision.ALLOWED
            actorRank == targetRank -> if (allowEqual) Decision.ALLOWED else Decision.EQUAL_RANK
            else -> Decision.HIGHER_RANK
        }
    }
}

/**
 * Who may add or remove a rank (issue #1383): `/rank add|remove|promote|demote` and the
 * `/admin` rank selector. Bukkit-free.
 *
 * - The console may do anything (LuckPerms console stays the Owner's tool).
 * - Players never change their own ranks.
 * - The target must pass [StaffProtection] (no acting on equal or higher staff).
 * - A staff rank may only be given or taken when it is strictly below the actor's own rank,
 *   and Owner is never assignable by a player.
 * - Non-staff ranks (donor tiers, vip, member, default) are unaffected, so legitimate donor
 *   management keeps working.
 */
object RankAssignmentRules {
    enum class Decision(val message: String?) {
        ALLOWED(null),
        SELF("You cannot change your own ranks."),
        TARGET_PROTECTED("You cannot change the ranks of a staff member at or above your rank."),
        RANK_TOO_HIGH("You can only give or remove staff ranks below your own."),
        OWNER("The Owner rank can only be changed from the server console."),
        NOT_STAFF("Only staff can give or remove staff ranks.");

        val allowed: Boolean get() = this == ALLOWED
    }

    fun decide(actor: StaffActor, target: StaffTarget, changedRank: StaffRank?, allowEqual: Boolean = false): Decision {
        if (actor is StaffActor.Console) return Decision.ALLOWED
        if (target.self) return Decision.SELF
        val protection = StaffProtection.decide(actor, target, allowEqual)
        if (!protection.allowed) return Decision.TARGET_PROTECTED
        if (changedRank == null) return Decision.ALLOWED
        if (changedRank == StaffRank.OWNER) return Decision.OWNER
        val actorRank = (actor as StaffActor.Staff).rank ?: return Decision.NOT_STAFF
        return if (actorRank.above(changedRank)) Decision.ALLOWED else Decision.RANK_TOO_HIGH
    }
}

/**
 * Authority caps for the legacy punishment paths (`/ban`, `/tempban`, `/mute`, `/tempmute`,
 * `/warn`, `/kick`, `/unban`, `/unmute`, `/unwarn`, `/punish <player> <type>` and the `/admin`
 * panel), issue #1383. They use the same `joshymc.punish.<level>` levels as the rule system,
 * so holding `joshymc.ban` no longer lets a Jr Mod permanently ban. Bukkit-free.
 */
object LegacyPunishAuthority {
    enum class Action(val label: String) {
        WARN("warn"), TEMPMUTE("temporarily mute"), MUTE("permanently mute"), KICK("kick"),
        TEMPBAN("temporarily ban"), BAN("permanently ban"),
        UNWARN("remove warnings"), UNMUTE("unmute"), UNBAN("unban");
    }

    /** The lowest level allowed to take [action] at all (temporary bans are also capped by length). */
    fun minimumLevel(action: Action): StaffLevel = when (action) {
        Action.WARN -> StaffLevel.TRAINEE
        Action.TEMPMUTE -> StaffLevel.HELPER
        Action.MUTE -> StaffLevel.MOD
        Action.KICK -> StaffLevel.MOD
        Action.TEMPBAN -> StaffLevel.MOD
        Action.BAN -> StaffLevel.ADMIN
        Action.UNWARN -> StaffLevel.JRMOD
        Action.UNMUTE -> StaffLevel.HELPER
        Action.UNBAN -> StaffLevel.MOD
    }

    /**
     * Null when [level] may take [action] ([durationMs] = the temporary ban's length), else
     * the reason. The console (and ops, which the caller maps to [StaffLevel.SRADMIN]) passes.
     */
    fun problem(level: StaffLevel?, action: Action, durationMs: Long? = null): String? {
        val needed = minimumLevel(action)
        if (level == null || !level.atLeast(needed)) {
            return "You need punishment level ${needed.displayName} or higher to ${action.label} (joshymc.punish.${needed.id})."
        }
        if (action == Action.TEMPBAN && durationMs != null) {
            val cap = level.maxBanMs
            if (cap != null && durationMs > cap) {
                return "${level.displayName} may ban for at most ${PunishDurations.label(cap)}."
            }
        }
        return null
    }
}
