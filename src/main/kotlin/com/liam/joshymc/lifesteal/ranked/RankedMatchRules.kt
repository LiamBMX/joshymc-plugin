package com.liam.joshymc.lifesteal.ranked

import com.liam.joshymc.lifesteal.RankedForfeitHeartLoss
import com.liam.joshymc.lifesteal.combat.CombatLogRules.DisconnectKind
import com.liam.joshymc.lifesteal.hearts.HeartRules
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

/**
 * Why a participant stopped fighting. Only [DEATH] (the normal heart service) and [DISCONNECT] (the
 * issue #1465 disconnect forfeit, [RankedMatchRules.forfeitRecipient]) can ever move a heart.
 */
enum class DefeatReason(val label: String, val forfeit: Boolean = false) {
    DEATH("died"),
    /** Issue #1465: a counted disconnect from an ACTIVE match (quit, timeout, player-caused kick): a forfeit. */
    DISCONNECT("disconnected (forfeit)", forfeit = true),
    /** Issue #1465: kicked by staff/the server or a server error mid-fight: out of the match, never a heart. */
    DISCONNECT_EXEMPT("disconnected (staff kick or server error)"),
    /** Staff took them out of the match (`/lifesteal admin ranked remove`). */
    REMOVED("removed by staff"),
    /**
     * Issue #1498: found in a restricted state (God Mode, Vanish, Creative/Spectator, Mod Mode,
     * over-limit gear) during the fight: out as a forfeit, never a heart, and their side's win
     * can't be rated ([RankedIntegrity]).
     */
    INELIGIBLE("used a restricted ability (forfeit)", forfeit = true),
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

    /**
     * Issue #1371: whether [a] and [b] fight on the same side of this match (null = they aren't
     * both on the roster, or it is the same player). Defeated players keep their side, so a kill
     * is judged by the roster the match started with, never by later team changes.
     */
    fun sameSide(a: UUID, b: UUID): Boolean? {
        if (a == b) return null
        val pa = of(a) ?: return null
        val pb = of(b) ?: return null
        return pa.side == pb.side
    }

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
    fun decide(roster: MatchRoster, outThisTick: List<MatchParticipant> = emptyList()): Decision {
        val a = roster.active(Side.A).size
        val b = roster.active(Side.B).size
        return when {
            a == 0 && b == 0 -> simultaneous(outThisTick)
            a == 0 -> Decision.Winner(Side.B)
            b == 0 -> Decision.Winner(Side.A)
            else -> Decision.Continue
        }
    }

    /**
     * Issue #1465: both sides ran out in the same tick. If every player one side lost in that tick
     * left by a counted disconnect ([DefeatReason.DISCONNECT]) and the other side lost at least one
     * player some other way, the disconnecting side forfeits and the other side wins, so quitting in
     * the tick your opponent's last player falls can't turn a loss into a cancelled match. Anything
     * else (deaths on both sides, forfeits on both sides) stays [Decision.NoWinner].
     */
    private fun simultaneous(outThisTick: List<MatchParticipant>): Decision {
        fun forfeited(side: Side): Boolean {
            val outs = outThisTick.filter { it.side == side }
            // Issue #1498: going ineligible in that tick is a forfeit too, never a way out of a loss.
            return outs.isNotEmpty() && outs.all { it.defeatReason?.forfeit == true }
        }
        val a = forfeited(Side.A)
        val b = forfeited(Side.B)
        return when {
            a && !b -> Decision.Winner(Side.B)
            b && !a -> Decision.Winner(Side.A)
            else -> Decision.NoWinner
        }
    }

    // ── Disconnects (issue #1465) ───────────────────────────

    /** What a participant's disconnect does to their match. */
    enum class DisconnectAction {
        /** Not in a match any more (out already, or the match is over): nothing happens. */
        NONE,
        /** The fight hasn't started: the match is cancelled unrated, nobody's hearts change. */
        CANCEL_MATCH,
        /** In the fight: out of the match as a forfeit ([DefeatReason.DISCONNECT]), maybe a heart. */
        FORFEIT,
        /** In the fight, but the server or staff caused it: out of the match, never a heart. */
        LEAVE_EXEMPT,
        /** The server is stopping: left to shutdown, which cancels the match and restores everyone. */
        SHUTDOWN,
    }

    data class DisconnectPolicy(val action: DisconnectAction, val heartForfeit: Boolean)

    /**
     * The one disconnect policy. It never guesses intent: Paper can't tell alt-F4 from a crash or a
     * pulled cable, so every disconnect of the same kind is treated the same way. [state] is the
     * match's state (null = not in a match), [stillFighting] whether the player is an ACTIVE
     * participant, [kind] from `CombatLogRules.classify`. Timeouts count when [countTimeouts]
     * (`combat-logging.count-timeouts`, the same switch as ordinary combat logs).
     */
    fun disconnect(state: MatchState?, stillFighting: Boolean, kind: DisconnectKind, countTimeouts: Boolean, heartLoss: RankedForfeitHeartLoss): DisconnectPolicy {
        val none = DisconnectPolicy(DisconnectAction.NONE, false)
        if (state == null || state.terminal) return none
        if (kind == DisconnectKind.SERVER_SHUTDOWN) return DisconnectPolicy(DisconnectAction.SHUTDOWN, false)
        return when (state) {
            MatchState.CREATED, MatchState.VALIDATING, MatchState.PREPARING, MatchState.COUNTDOWN -> DisconnectPolicy(DisconnectAction.CANCEL_MATCH, false)
            MatchState.ACTIVE -> when {
                !stillFighting -> none
                !counts(kind, countTimeouts) -> DisconnectPolicy(DisconnectAction.LEAVE_EXEMPT, false)
                else -> DisconnectPolicy(DisconnectAction.FORFEIT, heartLoss == RankedForfeitHeartLoss.LAST_OPPONENT)
            }
            else -> none
        }
    }

    /** Same counting as ordinary Lifesteal combat logs (`CombatLogRules.enforcement`). */
    fun counts(kind: DisconnectKind, countTimeouts: Boolean): Boolean = when (kind) {
        DisconnectKind.QUIT, DisconnectKind.PLAYER_KICK, DisconnectKind.TRANSFER -> true
        DisconnectKind.TIMED_OUT -> countTimeouts
        DisconnectKind.STAFF_KICK, DisconnectKind.SERVER_SHUTDOWN, DisconnectKind.SERVER_ERROR -> false
    }

    /** Opponent hits kept per participant (only the most recent ones can matter). */
    const val MAX_RECENT_HITS = 8

    /**
     * Who may receive the heart of a disconnect forfeit (null = nobody, no heart moves). Only hits
     * the match itself recorded count: an opponent's damage on [victim] during the ACTIVE fight, no
     * older than one combat-tag duration ([windowMillis], the same window as the #1461 combat-tag
     * credit) and not "in the future". Of those, the most recent hit whose attacker passes
     * [eligible] wins: the manager requires them to be on the other roster side, still fighting in
     * this match and online, and the heart service then applies its own checks (loaded, Lifesteal,
     * not an NPC). Never a teammate, never a random opponent, never one who didn't hit the leaver.
     */
    fun forfeitRecipient(victim: UUID, hits: List<HeartRules.RecentHit>, now: Long, windowMillis: Long, eligible: (UUID) -> Boolean): UUID? {
        if (windowMillis <= 0) return null
        return hits
            .filter { it.attacker != victim && it.at <= now && now - it.at < windowMillis }
            .sortedByDescending { it.at }
            .firstOrNull { eligible(it.attacker) }
            ?.attacker
    }

    /** Appends a hit, keeping at most [MAX_RECENT_HITS] (oldest dropped). */
    fun recordHit(hits: List<HeartRules.RecentHit>, hit: HeartRules.RecentHit): List<HeartRules.RecentHit> =
        (hits + hit).takeLast(MAX_RECENT_HITS)

    /** The dedupe key of [victim]'s disconnect forfeit in [matchId]: one heart decision per player per match. */
    fun forfeitDedupeKey(matchId: String, victim: UUID): String = "rankedforfeit:$matchId:$victim"

    /** Audit note on a forfeit transfer (`HeartStore.PvpRequest.note`). */
    fun forfeitNote(matchId: String, kind: DisconnectKind): String = "ranked disconnect forfeit, match $matchId (${kind.label})"

    // ── Ranked deaths (issue #1474) ─────────────────────────

    /**
     * The match a dying player was held by, copied at LOWEST of their PlayerDeathEvent, before the
     * ranked death handler (HIGHEST) takes them out of the match. [tick] is the server tick of
     * that death, so a copy is only ever used by the death it was taken for.
     */
    data class DeathCapture(
        val matchId: String,
        val tick: Int,
        /**
         * Issue #1478: the opponent hits the match had recorded on the victim (`Match.recentHits`,
         * landed hits between opposite roster sides only), copied with the match id because the
         * defeat at HIGHEST drops them. Used only by [environmentalKiller] for this one death.
         */
        val hits: List<HeartRules.RecentHit> = emptyList(),
        /** Wall-clock time of the copy, the "now" the hits' ages are measured against. */
        val at: Long = 0,
    )

    /**
     * The ranked match a death belongs to, or null for an ordinary death. [captured] is the copy
     * taken at LOWEST (valid only in its own [currentTick]); [stillHeld] is the match that holds
     * the victim right now (null once the ranked death handler has defeated them). Either one is
     * enough: the copy keeps the answer after the defeat, the live lookup covers a death whose
     * LOWEST copy was missed. A stale copy (an earlier tick) never makes a later death ranked.
     */
    fun deathMatch(captured: DeathCapture?, currentTick: Int, stillHeld: String?): String? =
        captured?.takeIf { it.tick == currentTick }?.matchId ?: stillHeld

    // ── Environmental ranked deaths (issue #1478) ───────────

    /**
     * Who gets the kill when a participant of ranked match [matchId] dies with no other player behind
     * the final damage ([HeartRules.Attribution.ENVIRONMENT]: knocked into lava or the void, a fatal
     * fall, fire, drowning, ... or [HeartRules.Attribution.SELF]: jumping into lava, their own TNT).
     * Any other [direct] attribution returns null: a real PvP kill keeps its killer, and an invalid
     * killer or unloaded record is never replaced by somebody else.
     *
     * Only the copy taken for this death counts ([captured]: same tick and same match, else nothing
     * is credited). Its hits are the match's own record of opponent hits that landed in the ACTIVE
     * fight, so they can't come from another match. They are chosen exactly like a disconnect forfeit's
     * ([forfeitRecipient]): the most recent hit younger than [windowMillis] (one combat-tag duration)
     * whose attacker passes [eligible] (the manager: on the other roster side of this ACTIVE match and
     * a valid heart killer). [excluded] (`/kill`) turns it off. Null = no heart moves; the death
     * still counts for the match.
     */
    fun environmentalKiller(
        direct: HeartRules.Attribution,
        victim: UUID,
        matchId: String?,
        captured: DeathCapture?,
        currentTick: Int,
        windowMillis: Long,
        excluded: Boolean,
        eligible: (UUID) -> Boolean,
    ): UUID? {
        if (direct != HeartRules.Attribution.ENVIRONMENT && direct != HeartRules.Attribution.SELF) return null
        if (excluded || matchId == null || captured == null) return null
        if (captured.tick != currentTick || captured.matchId != matchId) return null
        return forfeitRecipient(victim, captured.hits, captured.at, windowMillis, eligible)
    }

    /** Audit note on a ranked environmental-death transfer (`HeartStore.PvpRequest.note`); [cause] is the damage type. */
    fun environmentalNote(matchId: String, cause: String): String = "ranked environmental credit, match $matchId, died to $cause"

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
        /**
         * Issue #1499 (A-1): ids of teams already in a match that isn't over. A team plays one ranked
         * match at a time, whichever members it would send.
         */
        val busyTeams: Set<String> = emptySet(),
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
        for (team in listOf(r.teamA, r.teamB).distinctBy { it.id }) {
            if (team.exists && team.id in r.busyTeams) problems += teamBusyProblem(team.name)
        }
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

    /** Issue #1499 (A-1): the refusal for a team that is already in a ranked match. */
    fun teamBusyProblem(teamName: String): String = "$teamName is already playing a ranked match (a team plays one match at a time)."

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

    /**
     * [canonical] (issue #1498) is the real name of the command the label resolves to in the
     * server's command map (null = unknown or not resolved): an allowed label that is really an
     * alias of some other command is refused, so `plugin:` names and re-registered aliases can't
     * get past the list.
     */
    fun commandAllowed(message: String, canonical: String? = null): Boolean {
        val label = message.trim().removePrefix("/").substringBefore(' ').lowercase().substringAfter(':')
        if (label !in ALLOWED_COMMANDS) return false
        val real = canonical?.lowercase()?.substringAfter(':') ?: return true
        return real in ALLOWED_COMMANDS || real in ALLOWED_CANONICAL
    }

    /** Real command names the allowed labels resolve to (JoshyMC's `/msg` and `/reply`, vanilla `tell`). */
    val ALLOWED_CANONICAL: Set<String> = setOf("msg", "reply", "tell")

    // ── Match projectiles (issue #1377) ─────────────────────

    enum class ProjectilePickup {
        /** Not a match projectile, or picked up by a participant of the match that fired it. */
        ALLOW,
        /** Fired in a match that is still open, picked up by someone else: refused. */
        DENY,
        /** Fired in a match that is over: its owner already got it back from the snapshot. */
        REMOVE,
    }

    /**
     * Arrows and tridents fired by a held participant carry their match id ([firedIn]). The shooter's
     * snapshot gives those items back after the match, so the projectile itself must never become a
     * second copy: an arrow shot over the boundary picked up by a friend outside, or a Loyalty trident
     * landing back in the shooter's restored inventory. [pickerMatch] is the match holding the player
     * who touches it; [firedInOpen] whether the match it was fired in is still running.
     */
    fun projectilePickup(firedIn: String?, pickerMatch: String?, firedInOpen: Boolean): ProjectilePickup = when {
        firedIn == null -> ProjectilePickup.ALLOW
        !firedInOpen -> ProjectilePickup.REMOVE
        pickerMatch == firedIn -> ProjectilePickup.ALLOW
        else -> ProjectilePickup.DENY
    }

    // ── Ids ─────────────────────────────────────────────────

    private val MATCH_ID = Regex("rtw-[0-9a-f]{12}")

    fun newMatchId(random: UUID = UUID.randomUUID()): String = "rtw-" + random.toString().replace("-", "").take(12)

    fun validMatchId(id: String): Boolean = MATCH_ID.matches(id)
}
