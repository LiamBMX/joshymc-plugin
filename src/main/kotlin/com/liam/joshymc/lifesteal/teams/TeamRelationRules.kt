package com.liam.joshymc.lifesteal.teams

import com.liam.joshymc.lifesteal.hearts.HeartRules
import java.util.UUID

/**
 * Bukkit-free rules of Lifesteal team alliances and rivalries (issue #1405) that the manager, the
 * GUI, `/ac` and `scripts/LifestealAllianceCheck.kt` share. The authoritative decisions about
 * changes live in [TeamRelationStore] (inside transactions); these are the read-side rules.
 */
object TeamRelationRules {

    /** A refusal in player words; `{time}` and `{team}` are filled in. */
    fun refusalText(refusal: TeamRelationStore.Refusal, until: Long?, now: Long, teamName: String?): String =
        refusal.message
            .replace("{time}", until?.let { HeartRules.formatDuration((it - now).coerceAtLeast(1)) } ?: "a while")
            .replace("{team}", teamName ?: "<team>")

    /**
     * Who receives an alliance chat message from a member of [senderTeam]: the members of the sender's
     * team and of its current ally, nobody else. Empty when the team has no alliance (the message is
     * refused). Read from the authoritative state at delivery time, so a former ally never receives it.
     */
    fun chatRecipients(senderTeam: String, allyOf: (String) -> String?, roster: (String) -> List<UUID>): List<UUID> {
        val ally = allyOf(senderTeam) ?: return emptyList()
        if (allyOf(ally) != senderTeam) return emptyList()
        return (roster(senderTeam) + roster(ally)).distinct()
    }

    /** Only the team's current leader manages its relationships (read live at every action). */
    fun canManage(actor: UUID, leader: UUID?): Boolean = leader != null && actor == leader

    /** What a team profile shows about relationships (all public: no requests or cooldowns of others). */
    data class Profile(
        val allyId: String?,
        /** The rival this team declared (one-sided unless the other team declared back). */
        val declaredRivalId: String?,
        /** Teams that declared this team their rival. */
        val rivalOfIds: List<String>,
    ) {
        /** Whether [declaredRivalId] also declared this team back (shown as mutual). */
        fun mutual(): Boolean = declaredRivalId != null && declaredRivalId in rivalOfIds
    }

    fun profile(snapshot: TeamRelationStore.Snapshot, teamId: String): Profile = Profile(
        allyId = snapshot.allyOf(teamId),
        declaredRivalId = snapshot.rivalOf(teamId)?.target,
        rivalOfIds = snapshot.rivalsAgainst(teamId).map { it.declarer },
    )

    /** Refusal for forming an alliance while a member of either team is combat-tagged. */
    const val COMBAT_MESSAGE = "You can't form an alliance while a member of either team is in combat."
}
