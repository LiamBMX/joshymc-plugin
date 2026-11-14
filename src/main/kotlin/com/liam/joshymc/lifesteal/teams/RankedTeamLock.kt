package com.liam.joshymc.lifesteal.teams

import java.util.UUID

/**
 * Issue #1530: which Lifesteal team changes Ranked Team Wars refuses, and why. Bukkit-free, so the
 * rules are checked without a server (`scripts/RankedTeamLockCheck.kt`).
 *
 * There is no stored lock. The team manager reads, from memory, how far the team is into ranked
 * play ([Stage], from the open matches, the public queue/challenges and staff test proposals) and
 * which involved players are on an open match roster or in a match being created ([Facts.rosterPlayers]).
 * A stage ends with the match / queue entry / proposal that causes it (completed, cancelled, expired,
 * or cleared on restart, since none of them is persisted), so a team is never left locked.
 *
 * Match records, Elo, heart and history attribution already come from the match's own roster
 * snapshot (team ids + participant UUIDs, written when the match is created), never from live
 * membership. These rules keep live membership in step with it while the match runs, so the team
 * shown, the leader answering for it and its members can't drift from what was registered.
 *
 * What is refused:
 * - [Change.TRANSFER] (leadership/ownership; player, GUI and `/team admin transfer` alike): at any
 *   stage. The registered team stays under the leader who registered it until the match or entry ends.
 * - [Change.DISBAND] / [Change.ACCEPT]: while the team has an open match or one being created
 *   ([Stage.locksRoster]). While only queued, disbanding ends the entry instead (the queue's own
 *   revalidation), so it stays allowed.
 * - [Change.LEAVE] / [Change.KICK]: while an involved player is on an open match roster (also after
 *   being defeated in it) or in a match being created. A queued or confirming player leaving the team
 *   still just cancels that entry, as before. Members who aren't playing stay free to come and go.
 *
 * Nothing here can be bypassed by permission; staff end the match or entry first
 * (`/lifesteal admin ranked cancel|cancelqueue|cancelproposal`).
 */
object RankedTeamLock {

    /** How far a team is into ranked play, most binding first. */
    enum class Stage(val locksRoster: Boolean) {
        /** An open match (CREATED..FINISHING, until it completes or is cancelled). */
        MATCH(true),
        /** A queue/challenge entry or staff test proposal whose match is being created right now. */
        PREPARING(true),
        /** A found match or staff test match waiting for everyone to accept (ready check). */
        CONFIRMING(false),
        /** In the public queue or a challenge (forming, waiting, opponent found). */
        QUEUED(false),
    }

    enum class Change { TRANSFER, DISBAND, ACCEPT, LEAVE, KICK }

    /**
     * @param team the stage of the team being changed (the joined team for [Change.ACCEPT]), or null.
     * @param matchId the open match, for the staff hint.
     * @param rosterPlayers involved players on an open match roster or in a match being created.
     */
    data class Facts(
        val team: Stage? = null,
        val matchId: String? = null,
        val rosterPlayers: Set<UUID> = emptySet(),
    )

    /** The most binding of [stages] (null when none). */
    fun strongest(vararg stages: Stage?): Stage? = stages.filterNotNull().minByOrNull { it.ordinal }

    /** The refusal for [change], or null when ranked play doesn't stand in its way. */
    fun refusal(change: Change, facts: Facts, admin: Boolean = false): String? {
        val stage = facts.team
        val refused = when (change) {
            Change.TRANSFER -> stage != null
            Change.DISBAND, Change.ACCEPT -> stage?.locksRoster == true
            Change.LEAVE, Change.KICK -> facts.rosterPlayers.isNotEmpty()
        }
        if (!refused) return null
        // Leaving/kicking is about the player's own match; the team's stage only shapes the hint.
        val shown = if (change == Change.LEAVE || change == Change.KICK) Stage.MATCH else stage ?: return null
        return message(change, shown, admin, facts.matchId)
    }

    fun message(change: Change, stage: Stage, admin: Boolean, matchId: String? = null): String {
        val inMatch = stage.locksRoster
        val base = when (change) {
            Change.TRANSFER ->
                if (inMatch) "Team ownership can't be changed during a ranked match."
                else "Team ownership can't be changed while the team is in a ranked queue, challenge or match confirmation."
            Change.DISBAND -> "A team can't be disbanded during its ranked match."
            Change.ACCEPT -> "You can't join a team during its ranked match."
            Change.LEAVE -> "You can't leave your team while you're registered in a ranked match."
            Change.KICK -> "You can't remove a member who is registered in a ranked match."
        }
        val hint = when {
            admin && stage == Stage.MATCH -> " End it first: /lifesteal admin ranked cancel ${matchId ?: "<match>"} confirm"
            admin && stage == Stage.PREPARING -> " The match is being created; cancel it once it exists (/lifesteal admin ranked queue)."
            admin -> " End it first: /lifesteal admin ranked cancelqueue|cancelproposal (see /lifesteal admin ranked queue)."
            inMatch -> " Wait until it's over."
            else -> " Leave it first with /ranked leave."
        }
        return base + hint
    }
}
