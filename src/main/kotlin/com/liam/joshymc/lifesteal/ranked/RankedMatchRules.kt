package com.liam.joshymc.lifesteal.ranked

import java.util.UUID

/**
 * Ranked Team Wars match lifecycle (issue #1361). Every transition is checked here
 * ([MatchState.canMoveTo]) and again by a conditional UPDATE in [RankedMatchStore], so no
 * scheduled task or in-memory flag can move a match into a state it can't be in.
 *
 * CREATED → VALIDATING → PREPARING → COUNTDOWN → ACTIVE → FINISHING → COMPLETED, and any
 * non-terminal state → CANCELLED (except FINISHING, which already has a winner and only completes).
 */
enum class MatchState(val terminal: Boolean = false) {
    CREATED,
    VALIDATING,
    PREPARING,
    COUNTDOWN,
    ACTIVE,
    FINISHING,
    COMPLETED(terminal = true),
    CANCELLED(terminal = true);

    fun canMoveTo(next: MatchState): Boolean = next in (TRANSITIONS[this] ?: emptySet())

    /** Participants are held by the match (inventory protected, teleports and commands restricted). */
    val holdsPlayers: Boolean get() = this == PREPARING || this == COUNTDOWN || this == ACTIVE || this == FINISHING

    companion object {
        private val TRANSITIONS: Map<MatchState, Set<MatchState>> = mapOf(
            CREATED to setOf(VALIDATING, CANCELLED),
            VALIDATING to setOf(PREPARING, CANCELLED),
            PREPARING to setOf(COUNTDOWN, CANCELLED),
            COUNTDOWN to setOf(ACTIVE, CANCELLED),
            ACTIVE to setOf(FINISHING, CANCELLED),
            FINISHING to setOf(COMPLETED),
            COMPLETED to emptySet(),
            CANCELLED to emptySet(),
        )

        val OPEN: List<MatchState> = entries.filter { !it.terminal }

        fun parse(raw: String?): MatchState? = entries.firstOrNull { it.name == raw }
    }
}

/** Where a participant stands inside the match. Only [ACTIVE] participants are still fighting. */
enum class ParticipantStatus { ACTIVE, DEFEATED }

/** Why a participant stopped fighting. None of these touch hearts (no ranked heart penalty exists). */
enum class DefeatReason(val label: String) {
    DEATH("died"),
    DISCONNECT("disconnected"),
    /** Staff took them out of the match (`/lifesteal admin ranked remove`). */
    REMOVED("removed by staff"),
}

/** How a match ended. Only a WIN can change Elo (issue #1365); there is no draw outcome. */
enum class MatchOutcome { WIN, CANCELLED }

/**
 * How a match came about, stored on every match record (issue #1363), so later systems can tell
 * them apart. Elo (issue #1365, `rating/RatingRules`): [PUBLIC] is rated, [TEST] never, [CHALLENGE]
 * only with `ranked-team-wars.elo.rate-challenges: true` (off by default, so friendly teams can't
 * farm each other). Players can't choose the kind: it comes from the code path that made the match.
 */
enum class MatchKind(val label: String) {
    /** Staff `/lifesteal admin ranked testmatch`. */
    TEST("test"),
    /** Public matchmaking (`/ranked queue`). */
    PUBLIC("ranked queue"),
    /** A direct team challenge (`/ranked challenge`). */
    CHALLENGE("team challenge");

    companion object {
        fun parse(raw: String?): MatchKind? = entries.firstOrNull { it.name == raw }
    }
}

/** Restoration state of all participant snapshots of a match. */
enum class RestorationStatus { PENDING, COMPLETE }

data class MatchParticipant(
    val uuid: UUID,
    val side: Side,
    val teamId: String,
    val status: ParticipantStatus = ParticipantStatus.ACTIVE,
    val defeatReason: DefeatReason? = null,
)

/** The two sides of a match and who is still fighting. Immutable: every change returns a new roster. */
data class MatchRoster(val participants: List<MatchParticipant>) {
    fun of(uuid: UUID): MatchParticipant? = participants.firstOrNull { it.uuid == uuid }
    fun side(side: Side): List<MatchParticipant> = participants.filter { it.side == side }
    fun active(side: Side): List<MatchParticipant> = side(side).filter { it.status == ParticipantStatus.ACTIVE }
    fun isActive(uuid: UUID): Boolean = of(uuid)?.status == ParticipantStatus.ACTIVE
    val uuids: List<UUID> get() = participants.map { it.uuid }

    /** Marks [uuid] defeated; a second defeat of the same player changes nothing (returns null). */
    fun defeat(uuid: UUID, reason: DefeatReason): MatchRoster? {
        val p = of(uuid) ?: return null
        if (p.status != ParticipantStatus.ACTIVE) return null
        return MatchRoster(participants.map { if (it.uuid == uuid) it.copy(status = ParticipantStatus.DEFEATED, defeatReason = reason) else it })
    }
}

object RankedMatchRules {

    /** What happens after the roster changed. */
    sealed interface Decision {
        data object Continue : Decision
        data class Winner(val side: Side) : Decision
        /** Both sides are out at the same time: no reliable winner, the match is cancelled. */
        data object NoWinner : Decision
    }

    /**
     * Ends the match when a side has nobody left fighting. Deaths of one tick are collected before
     * this runs (the manager decides on the next tick), so the last players of both sides dying
     * together is [Decision.NoWinner], never whichever event happened to fire first.
     */
    fun decide(roster: MatchRoster): Decision {
        val a = roster.active(Side.A).size
        val b = roster.active(Side.B).size
        return when {
            a == 0 && b == 0 -> Decision.NoWinner
            a == 0 -> Decision.Winner(Side.B)
            b == 0 -> Decision.Winner(Side.A)
            else -> Decision.Continue
        }
    }

    // ── Validation ──────────────────────────────────────────

    /** The facts about one chosen participant, gathered on the main thread before validating. */
    data class ParticipantFacts(
        val uuid: UUID,
        val name: String,
        val side: Side,
        /** Member of the team they represent, by the Lifesteal team membership cache. */
        val member: Boolean,
        val online: Boolean,
        val inLifesteal: Boolean,
        /** Heart record loaded this session (the Lifesteal player session). */
        val sessionLoaded: Boolean,
        val hearts: Int?,
        val inOtherMatch: Boolean,
        /** An earlier ranked inventory snapshot hasn't been restored yet. */
        val pendingRestore: Boolean,
        val transferring: Boolean,
        val combatTagged: Boolean,
        val dead: Boolean,
        val survivalMode: Boolean,
        /** Mod Mode, vanished, frozen or similar staff/admin states. */
        val restricted: String? = null,
    )

    data class TeamFacts(val id: String, val name: String, val exists: Boolean, val onlineMembers: Int)

    data class Request(
        val teamA: TeamFacts,
        val teamB: TeamFacts,
        val size: Int,
        val allowedSizes: Collection<Int>,
        val participants: List<ParticipantFacts>,
        val heartLimits: IntRange?,
        val rankedUnlocked: Boolean,
        val teamsAvailable: Boolean,
        val heartsAvailable: Boolean,
        val arenaProblem: String?,
    )

    /**
     * Every reason [request] can't become a match (empty = valid). Nothing in a player's inventory,
     * position or hearts is touched before this list is empty.
     */
    fun validate(r: Request): List<String> {
        val problems = mutableListOf<String>()
        if (!r.rankedUnlocked) problems += "Ranked Team Wars haven't unlocked for this season yet."
        if (!r.teamsAvailable) problems += "Lifesteal teams aren't available right now."
        if (!r.heartsAvailable || r.heartLimits == null) problems += "The heart system isn't running, so hearts couldn't be at stake."
        if (r.size !in RankedArenaRules.MIN_SIZE..RankedArenaRules.MAX_SIZE) problems += "Matches are 2v2 to 5v5 (got ${r.size})."
        else if (r.size !in r.allowedSizes) problems += "${r.size}v${r.size} isn't an allowed size (lifesteal.yml ranked-team-wars.team-sizes: ${r.allowedSizes.sorted().joinToString()})."
        if (!r.teamA.exists) problems += "Team '${r.teamA.name}' doesn't exist."
        if (!r.teamB.exists) problems += "Team '${r.teamB.name}' doesn't exist."
        if (r.teamA.exists && r.teamB.exists && r.teamA.id == r.teamB.id) problems += "A team can't play against itself."
        r.arenaProblem?.let { problems += it }
        if (problems.isNotEmpty()) return problems

        for (side in Side.entries) {
            val team = if (side == Side.A) r.teamA else r.teamB
            val chosen = r.participants.filter { it.side == side }
            if (chosen.size != r.size) problems += "${team.name} needs ${r.size} online players (has ${team.onlineMembers})."
        }
        val seen = HashSet<UUID>()
        for (p in r.participants) {
            if (!seen.add(p.uuid)) problems += "${p.name} is on both sides."
            participantProblem(p, r.heartLimits)?.let { problems += "${p.name}: $it" }
        }
        return problems
    }

    /** Why one participant can't play (null = can), the same rule a match start uses. Also used by the ranked queue. */
    fun participantProblem(p: ParticipantFacts, limits: IntRange?): String? = when {
        !p.member -> "isn't a member of the team they'd represent"
        !p.online -> "isn't online"
        !p.inLifesteal -> "isn't in a Lifesteal world"
        !p.sessionLoaded -> "has no loaded Lifesteal session (hearts not loaded)"
        p.hearts == null || limits == null || p.hearts !in limits -> "has no valid heart record"
        p.inOtherMatch -> "is already in a ranked match"
        p.pendingRestore -> "still has a ranked inventory waiting to be restored"
        p.transferring -> "is switching servers"
        p.combatTagged -> "is in combat"
        p.dead -> "is dead"
        !p.survivalMode -> "isn't in Survival game mode"
        p.restricted != null -> p.restricted
        else -> null
    }

    // ── Commands while held by a match ──────────────────────

    /**
     * The only commands a participant may type while a match holds them: chat to other players.
     * Everything else (teleports, /team home, /spawn, /home, /tpa, /rtp, /lobby, /hub, server
     * transfers, vaults, ender chest, trades, shops, kits, /withdrawheart ...) is refused, so no
     * item, location or heart can leave the match through a command.
     */
    val ALLOWED_COMMANDS: Set<String> = setOf("msg", "tell", "w", "whisper", "m", "r", "reply", "message")

    fun commandAllowed(message: String): Boolean {
        val label = message.removePrefix("/").substringBefore(' ').lowercase().substringAfter(':')
        return label in ALLOWED_COMMANDS
    }

    // ── Ids ─────────────────────────────────────────────────

    private val MATCH_ID = Regex("rtw-[0-9a-f]{12}")

    fun newMatchId(random: UUID = UUID.randomUUID()): String = "rtw-" + random.toString().replace("-", "").take(12)

    fun validMatchId(id: String): Boolean = MATCH_ID.matches(id)
}
