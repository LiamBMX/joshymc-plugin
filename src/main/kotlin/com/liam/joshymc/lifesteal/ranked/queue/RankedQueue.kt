package com.liam.joshymc.lifesteal.ranked.queue

import java.util.UUID

/**
 * Ranked Team Wars matchmaking state (issue #1363), Bukkit-free and main-thread only: the one
 * place that knows who is queued, who still has to agree, which matches were found and which team
 * challenges are open. [com.liam.joshymc.lifesteal.ranked.queue.LifestealRankedQueueManager] feeds
 * it the live facts (membership, combat tags, arenas) and does the messaging; once a found match
 * is accepted by everyone, the existing match lifecycle
 * ([com.liam.joshymc.lifesteal.ranked.LifestealRankedManager.createQueuedMatch]) takes over and the
 * entries leave the queue.
 *
 * Nothing here is persisted: a restart drops every queue entry, proposal and challenge (players
 * simply queue again), so no unsafe queue is ever resumed. Every collection is capped ([Limits]).
 *
 * Entry life: FORMING (selected players agreeing) → WAITING (in the queue / waiting for the
 * challenge) → MATCH_FOUND → CONFIRMING (everyone must accept) → PREPARING (the match is being
 * created) → MATCHED (handed to the match lifecycle). Any non-terminal state can become CANCELLED
 * or EXPIRED, and a found match that fails for reasons outside an entry's control puts it back to
 * WAITING with its original place.
 */
enum class QueueState(val terminal: Boolean = false, val label: String) {
    FORMING(label = "waiting for teammates to agree"),
    WAITING(label = "waiting for an opponent"),
    MATCH_FOUND(label = "opponent found"),
    CONFIRMING(label = "waiting for everyone to accept"),
    PREPARING(label = "preparing the match"),
    MATCHED(terminal = true, label = "match started"),
    CANCELLED(terminal = true, label = "cancelled"),
    EXPIRED(terminal = true, label = "expired");

    fun canMoveTo(next: QueueState): Boolean = next in (TRANSITIONS[this] ?: emptySet())

    companion object {
        private val TRANSITIONS: Map<QueueState, Set<QueueState>> = mapOf(
            FORMING to setOf(WAITING, CANCELLED, EXPIRED),
            WAITING to setOf(MATCH_FOUND, CANCELLED, EXPIRED),
            MATCH_FOUND to setOf(CONFIRMING, WAITING, CANCELLED),
            CONFIRMING to setOf(PREPARING, WAITING, CANCELLED, EXPIRED),
            PREPARING to setOf(MATCHED, WAITING, CANCELLED),
            MATCHED to emptySet(),
            CANCELLED to emptySet(),
            EXPIRED to emptySet(),
        )
    }
}

/** Public matchmaking or a direct team challenge (kept apart: challenges never enter the public pool). */
enum class EntryKind { PUBLIC, CHALLENGE }

/** One team's registration: the exact players who will play, never more or fewer than [size]. */
class QueueEntry(
    val id: String,
    val kind: EntryKind,
    val teamId: String,
    val teamName: String,
    val size: Int,
    /** The selected players, in selection order. Exactly [size], all distinct. */
    val participants: List<UUID>,
    /** Who registered it (the team leader). Not necessarily a participant. */
    val initiator: UUID,
    val createdAt: Long,
    val seasonId: String,
    val challengeId: String? = null,
) {
    var state: QueueState = QueueState.FORMING
        internal set
    var stateSince: Long = createdAt
        internal set
    /** Queue position: set when it first becomes WAITING and kept when a failed match returns it. */
    var waitingSince: Long? = null
        internal set
    var proposalId: String? = null
        internal set
    /** Why it can't be matched right now (combat tag, dead, ...), refreshed by the manager; null = eligible. */
    var problem: String? = null
    private val agreed = LinkedHashSet<UUID>()

    val consented: Set<UUID> get() = agreed
    val pendingConsent: List<UUID> get() = participants.filter { it !in agreed }
    val active: Boolean get() = !state.terminal

    internal fun agree(uuid: UUID): Boolean = uuid in participants && agreed.add(uuid)

    internal fun move(next: QueueState, now: Long) {
        check(state.canMoveTo(next)) { "queue entry $id: $state -> $next is not allowed" }
        state = next
        stateSince = now
        if (next == QueueState.WAITING && waitingSince == null) waitingSince = now
        if (next == QueueState.WAITING || next.terminal) proposalId = null
    }
}

enum class ProposalState(val terminal: Boolean = false) {
    CONFIRMING, STARTING, STARTED(true), CANCELLED(true), EXPIRED(true)
}

/** A found match between two entries of the same size, waiting for every participant to accept. */
class Proposal(
    val id: String,
    val kind: EntryKind,
    /** Side A (the longer-waiting entry, or the challenger). */
    val entryA: String,
    val entryB: String,
    val size: Int,
    val arenaId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val participants: List<UUID>,
    val challengeId: String? = null,
) {
    var state: ProposalState = ProposalState.CONFIRMING
        internal set
    private val yes = LinkedHashSet<UUID>()
    val accepted: Set<UUID> get() = yes
    val waitingFor: List<UUID> get() = participants.filter { it !in yes }
    val complete: Boolean get() = yes.size == participants.size

    internal fun accept(uuid: UUID): Boolean = uuid in participants && yes.add(uuid)
}

enum class ChallengeState(val terminal: Boolean = false) {
    /** Sent; the challenged team's leader hasn't answered. */
    PENDING,
    /** The challenged team registered its roster. */
    ACCEPTED,
    MATCHED(true), DECLINED(true), CANCELLED(true), EXPIRED(true)
}

class Challenge(
    val id: String,
    val fromTeamId: String,
    val fromTeamName: String,
    val toTeamId: String,
    val toTeamName: String,
    val size: Int,
    val fromEntryId: String,
    val createdAt: Long,
    /** When an unanswered (PENDING) challenge expires. */
    val expiresAt: Long,
) {
    var state: ChallengeState = ChallengeState.PENDING
        internal set
    var toEntryId: String? = null
        internal set
}

/**
 * Picks the next pair to propose among the eligible WAITING entries of one size. This is the seam
 * for later Elo-based matching (issue #1363 deliberately computes no Elo): a rating-aware selector
 * replaces [FirstInFirstOut] without touching the queue.
 */
fun interface OpponentSelector {
    /** [waiting] is oldest first; return the pair (side A, side B) to propose, or null for none. */
    fun select(waiting: List<QueueEntry>, compatible: (QueueEntry, QueueEntry) -> Boolean): Pair<QueueEntry, QueueEntry>?
}

/**
 * Predictable queue order: the longest-waiting eligible team gets the longest-waiting compatible
 * opponent. The oldest team is always considered first, so nobody starves behind newer teams.
 */
object FirstInFirstOut : OpponentSelector {
    override fun select(waiting: List<QueueEntry>, compatible: (QueueEntry, QueueEntry) -> Boolean): Pair<QueueEntry, QueueEntry>? {
        for (i in waiting.indices) {
            for (j in i + 1 until waiting.size) {
                if (compatible(waiting[i], waiting[j])) return waiting[i] to waiting[j]
            }
        }
        return null
    }
}

/** Expiring keys with a hard size cap (oldest dropped first), for cooldowns and spam limits. */
class BoundedCooldowns<K>(private val capacity: Int) {
    private val until = LinkedHashMap<K, Long>()

    fun start(key: K, now: Long, millis: Long) {
        if (millis <= 0) return
        until.remove(key)
        until[key] = now + millis
        while (until.size > capacity) until.remove(until.keys.first())
    }

    /** Milliseconds left, or 0. */
    fun remaining(key: K, now: Long): Long {
        val end = until[key] ?: return 0
        if (end <= now) { until.remove(key); return 0 }
        return end - now
    }

    fun sweep(now: Long) { until.values.removeIf { it <= now } }
    fun clear() = until.clear()
    val size: Int get() = until.size
}

class RankedQueueBook(val limits: Limits = Limits()) {

    data class Limits(
        /** Entries of every state together (one per team, so this is also the team cap). */
        val maxEntries: Int = 200,
        val maxProposals: Int = 100,
        val maxChallenges: Int = 200,
        /** Open challenges a single team can receive at once. */
        val maxIncomingPerTeam: Int = 5,
    )

    private val entries = LinkedHashMap<String, QueueEntry>()
    private val byPlayer = HashMap<UUID, String>()
    private val byTeam = HashMap<String, String>()
    private val proposals = LinkedHashMap<String, Proposal>()
    private val challenges = LinkedHashMap<String, Challenge>()

    // ── Reads ───────────────────────────────────────────────

    fun entry(id: String): QueueEntry? = entries[id]
    fun entryOf(uuid: UUID): QueueEntry? = byPlayer[uuid]?.let { entries[it] }
    fun entryOfTeam(teamId: String): QueueEntry? = byTeam[teamId]?.let { entries[it] }
    fun proposal(id: String): Proposal? = proposals[id]
    fun proposalOf(uuid: UUID): Proposal? = entryOf(uuid)?.proposalId?.let { proposals[it] }
    fun challenge(id: String): Challenge? = challenges[id]
    fun allEntries(): List<QueueEntry> = entries.values.toList()
    fun allProposals(): List<Proposal> = proposals.values.toList()
    fun allChallenges(): List<Challenge> = challenges.values.toList()
    val isEmpty: Boolean get() = entries.isEmpty() && challenges.isEmpty()

    /** Public WAITING entries of [size], oldest first (queue order). */
    fun waiting(size: Int): List<QueueEntry> = entries.values
        .filter { it.kind == EntryKind.PUBLIC && it.size == size && it.state == QueueState.WAITING }
        .sortedWith(compareBy<QueueEntry> { it.waitingSince ?: Long.MAX_VALUE }.thenBy { it.createdAt }.thenBy { it.id })

    /** Arenas that open proposals are waiting on: never offered to another proposal. */
    fun reservedArenas(): Set<String> = proposals.values.filter { !it.state.terminal }.map { it.arenaId }.toSet()

    fun outgoingChallenge(teamId: String): Challenge? = challenges.values.firstOrNull { it.fromTeamId == teamId && !it.state.terminal }
    fun incomingChallenges(teamId: String): List<Challenge> = challenges.values.filter { it.toTeamId == teamId && it.state == ChallengeState.PENDING }
    fun pendingChallenge(fromTeamId: String, toTeamId: String): Challenge? =
        challenges.values.firstOrNull { it.fromTeamId == fromTeamId && it.toTeamId == toTeamId && it.state == ChallengeState.PENDING }

    /** Different teams, no shared player, same size and season (the default compatibility rule). */
    fun compatible(a: QueueEntry, b: QueueEntry): Boolean =
        a.id != b.id && a.teamId != b.teamId && a.size == b.size && a.seasonId == b.seasonId &&
            a.participants.none { it in b.participants }

    // ── Registration ────────────────────────────────────────

    /**
     * Adds [entry] in FORMING with [agreed] already counted (the leader who queued and plays).
     * Returns why it was refused (and changes nothing), or null. Becomes WAITING right away when
     * nobody else has to agree.
     */
    fun register(entry: QueueEntry, agreed: Collection<UUID>, now: Long): String? {
        if (entry.state != QueueState.FORMING || entries.containsKey(entry.id)) return "That request already exists."
        if (entry.participants.size != entry.size) return "Exactly ${entry.size} players are needed (got ${entry.participants.size})."
        if (entry.participants.toSet().size != entry.participants.size) return "A player was selected twice."
        if (byTeam.containsKey(entry.teamId)) return "Your team is already in a ranked queue or challenge."
        entry.participants.firstOrNull { byPlayer.containsKey(it) }?.let { return "A selected player is already in a ranked queue or challenge." }
        if (entries.size >= limits.maxEntries) return "The ranked queue is full right now. Please try again in a moment."
        entries[entry.id] = entry
        byTeam[entry.teamId] = entry.id
        for (p in entry.participants) byPlayer[p] = entry.id
        for (u in agreed) entry.agree(u)
        if (entry.pendingConsent.isEmpty()) entry.move(QueueState.WAITING, now)
        return null
    }

    sealed interface ConsentResult {
        data object NotAsked : ConsentResult
        data object Already : ConsentResult
        data class Recorded(val entry: QueueEntry, val complete: Boolean) : ConsentResult
    }

    /** [uuid] agrees to play in their FORMING entry; the entry is WAITING once everyone agreed. */
    fun consent(uuid: UUID, now: Long): ConsentResult {
        val entry = entryOf(uuid) ?: return ConsentResult.NotAsked
        if (entry.state != QueueState.FORMING) return ConsentResult.NotAsked
        if (!entry.agree(uuid)) return ConsentResult.Already
        val complete = entry.pendingConsent.isEmpty()
        if (complete) entry.move(QueueState.WAITING, now)
        return ConsentResult.Recorded(entry, complete)
    }

    // ── Ending entries ──────────────────────────────────────

    /** What ending an entry did to everything around it (for messages and the audit log). */
    data class Ended(
        val entry: QueueEntry,
        /** A proposal it was in, now cancelled/expired. */
        val proposal: Proposal? = null,
        /** The opponent entry of that proposal, back to WAITING with its place kept. */
        val returned: QueueEntry? = null,
        /** The challenge it belonged to, now ended, and that challenge's other entry, ended too. */
        val challenge: Challenge? = null,
        val alsoEnded: QueueEntry? = null,
    )

    /**
     * Ends entry [id] as [state] (CANCELLED or EXPIRED). Its players are released at once. If it
     * was in a found match, the match proposal ends and a public opponent goes back to WAITING (an
     * opponent in a challenge ends with the challenge). Null when it wasn't active.
     */
    fun end(id: String, state: QueueState, now: Long): Ended? {
        require(state == QueueState.CANCELLED || state == QueueState.EXPIRED)
        val entry = entries[id]?.takeIf { it.active } ?: return null
        var proposal: Proposal? = null
        var returned: QueueEntry? = null
        entry.proposalId?.let { pid ->
            proposals[pid]?.takeIf { !it.state.terminal }?.let { p ->
                p.state = if (state == QueueState.EXPIRED) ProposalState.EXPIRED else ProposalState.CANCELLED
                proposal = p
                proposals.remove(pid)
                val otherId = if (p.entryA == id) p.entryB else p.entryA
                val other = entries[otherId]?.takeIf { it.active }
                if (other != null && other.kind == EntryKind.PUBLIC && other.state.canMoveTo(QueueState.WAITING)) {
                    other.move(QueueState.WAITING, now)
                    returned = other
                }
            }
        }
        remove(entry, state, now)
        var challenge: Challenge? = null
        var alsoEnded: QueueEntry? = null
        entry.challengeId?.let { cid ->
            challenges[cid]?.takeIf { !it.state.terminal }?.let { c ->
                c.state = if (state == QueueState.EXPIRED) ChallengeState.EXPIRED else ChallengeState.CANCELLED
                challenge = c
                challenges.remove(cid)
                val otherId = if (c.fromEntryId == id) c.toEntryId else c.fromEntryId
                otherId?.let { entries[it] }?.takeIf { it.active }?.let { other ->
                    // Its proposal (if any) was this one's and is already gone.
                    remove(other, QueueState.CANCELLED, now)
                    alsoEnded = other
                }
            }
        }
        return Ended(entry, proposal, returned, challenge, alsoEnded)
    }

    private fun remove(entry: QueueEntry, state: QueueState, now: Long) {
        if (entry.state.canMoveTo(state)) entry.move(state, now) else {
            entry.state = state
            entry.stateSince = now
            entry.proposalId = null
        }
        entries.remove(entry.id)
        if (byTeam[entry.teamId] == entry.id) byTeam.remove(entry.teamId)
        for (p in entry.participants) if (byPlayer[p] == entry.id) byPlayer.remove(p)
    }

    // ── Proposals ───────────────────────────────────────────

    /**
     * Creates a found match between WAITING entries [a] and [b] in [arenaId] (not reserved by
     * another proposal). Returns null (and changes nothing) when they can't be paired.
     */
    fun propose(a: QueueEntry, b: QueueEntry, arenaId: String, id: String, now: Long, confirmMillis: Long): Proposal? {
        if (entries[a.id] !== a || entries[b.id] !== b) return null
        if (a.state != QueueState.WAITING || b.state != QueueState.WAITING) return null
        if (!compatible(a, b) || a.kind != b.kind) return null
        if (a.kind == EntryKind.CHALLENGE && (a.challengeId == null || a.challengeId != b.challengeId)) return null
        if (arenaId in reservedArenas() || proposals.containsKey(id) || proposals.size >= limits.maxProposals) return null
        val proposal = Proposal(id, a.kind, a.id, b.id, a.size, arenaId, now, now + confirmMillis, a.participants + b.participants, a.challengeId)
        for (e in listOf(a, b)) {
            e.move(QueueState.MATCH_FOUND, now)
            e.move(QueueState.CONFIRMING, now)
            e.proposalId = id
        }
        proposals[id] = proposal
        return proposal
    }

    sealed interface AcceptResult {
        data object NothingToAccept : AcceptResult
        /** The token belongs to another (older) request: stale button or message. */
        data object Stale : AcceptResult
        data object Expired : AcceptResult
        data object Already : AcceptResult
        data class Accepted(val proposal: Proposal) : AcceptResult
    }

    /** [uuid] accepts their found match [proposalId] (null = whichever they're in). */
    fun accept(uuid: UUID, proposalId: String?, now: Long): AcceptResult {
        val p = proposalOf(uuid) ?: return if (proposalId != null) AcceptResult.Stale else AcceptResult.NothingToAccept
        if (proposalId != null && proposalId != p.id) return AcceptResult.Stale
        if (p.state != ProposalState.CONFIRMING) return AcceptResult.Stale
        if (now >= p.expiresAt) return AcceptResult.Expired
        if (!p.accept(uuid)) return AcceptResult.Already
        return AcceptResult.Accepted(p)
    }

    /** CONFIRMING → STARTING once: the single gate against creating a match twice. */
    fun beginStart(proposalId: String, now: Long): Proposal? {
        val p = proposals[proposalId] ?: return null
        if (p.state != ProposalState.CONFIRMING || !p.complete) return null
        val a = entries[p.entryA]
        val b = entries[p.entryB]
        if (a?.state != QueueState.CONFIRMING || b?.state != QueueState.CONFIRMING) return null
        p.state = ProposalState.STARTING
        a.move(QueueState.PREPARING, now)
        b.move(QueueState.PREPARING, now)
        return p
    }

    /** The match lifecycle took the match: both entries (and the challenge) are done and released. */
    fun matched(proposalId: String, now: Long): Proposal? {
        val p = proposals[proposalId]?.takeIf { it.state == ProposalState.STARTING } ?: return null
        p.state = ProposalState.STARTED
        proposals.remove(proposalId)
        for (id in listOf(p.entryA, p.entryB)) entries[id]?.let { remove(it, QueueState.MATCHED, now) }
        p.challengeId?.let { challenges.remove(it)?.state = ChallengeState.MATCHED }
        return p
    }

    data class Failed(val proposal: Proposal, val ended: List<QueueEntry>, val returned: List<QueueEntry>, val challenge: Challenge?)

    /**
     * A STARTING proposal couldn't become a match. Entries in [faulty] end (CANCELLED); the others
     * go back to WAITING with their place (a challenge waits for another try only if neither side
     * is at fault).
     */
    fun startFailed(proposalId: String, faulty: Set<String>, now: Long): Failed? {
        val p = proposals[proposalId]?.takeIf { it.state == ProposalState.STARTING || it.state == ProposalState.CONFIRMING } ?: return null
        p.state = ProposalState.CANCELLED
        proposals.remove(proposalId)
        val ended = mutableListOf<QueueEntry>()
        val returned = mutableListOf<QueueEntry>()
        var challenge: Challenge? = null
        val endAll = p.kind == EntryKind.CHALLENGE && faulty.isNotEmpty()
        for (id in listOf(p.entryA, p.entryB)) {
            val e = entries[id]?.takeIf { it.active } ?: continue
            if (id in faulty || endAll) {
                remove(e, QueueState.CANCELLED, now)
                ended += e
            } else if (e.state.canMoveTo(QueueState.WAITING)) {
                e.move(QueueState.WAITING, now)
                returned += e
            }
        }
        if (endAll) p.challengeId?.let { cid -> challenges.remove(cid)?.let { it.state = ChallengeState.CANCELLED; challenge = it } }
        return Failed(p, ended, returned, challenge)
    }

    // ── Challenges ──────────────────────────────────────────

    /** Registers [challenge] with the challenger's [entry] (kind CHALLENGE). Null = done, else why not. */
    fun challenge(challenge: Challenge, entry: QueueEntry, agreed: Collection<UUID>, now: Long): String? {
        if (challenge.fromTeamId == challenge.toTeamId) return "A team can't challenge itself."
        if (entry.kind != EntryKind.CHALLENGE || entry.challengeId != challenge.id || entry.id != challenge.fromEntryId || entry.teamId != challenge.fromTeamId) return "That challenge doesn't match its roster."
        if (outgoingChallenge(challenge.fromTeamId) != null) return "Your team already has an open challenge."
        if (incomingChallenges(challenge.toTeamId).size >= limits.maxIncomingPerTeam) return "${challenge.toTeamName} has too many open challenges right now."
        if (challenges.size >= limits.maxChallenges) return "Too many challenges are open right now. Please try again in a moment."
        register(entry, agreed, now)?.let { return it }
        challenges[challenge.id] = challenge
        return null
    }

    /** The challenged team registers its roster [entry]. Null = done, else why not. */
    fun acceptChallenge(challengeId: String, entry: QueueEntry, agreed: Collection<UUID>, now: Long): String? {
        val c = challenges[challengeId] ?: return "That challenge no longer exists."
        if (c.state != ChallengeState.PENDING) return "That challenge was already answered."
        if (now >= c.expiresAt) return "That challenge expired."
        if (entry.kind != EntryKind.CHALLENGE || entry.challengeId != c.id || entry.teamId != c.toTeamId || entry.size != c.size) return "That roster doesn't fit the challenge."
        if (entries[c.fromEntryId]?.active != true) return "The challenging team withdrew."
        register(entry, agreed, now)?.let { return it }
        c.toEntryId = entry.id
        c.state = ChallengeState.ACCEPTED
        return null
    }

    /** The challenged team says no: the challenge and the challenger's roster end. */
    fun declineChallenge(challengeId: String, now: Long): Challenge? {
        val c = challenges[challengeId]?.takeIf { it.state == ChallengeState.PENDING } ?: return null
        end(c.fromEntryId, QueueState.CANCELLED, now)
        c.state = ChallengeState.DECLINED
        challenges.remove(challengeId)
        return c
    }

    /** Accepted challenges whose two rosters are both WAITING (ready to be proposed). */
    fun readyChallenges(): List<Pair<QueueEntry, QueueEntry>> = challenges.values
        .filter { it.state == ChallengeState.ACCEPTED }
        .mapNotNull { c ->
            val a = entries[c.fromEntryId]
            val b = c.toEntryId?.let { entries[it] }
            if (a?.state == QueueState.WAITING && b?.state == QueueState.WAITING) a to b else null
        }

    // ── Timeouts ────────────────────────────────────────────

    data class Expiry(
        val ended: List<Ended>,
        /** Players who didn't accept an expired found match (they get the decline cooldown). */
        val ignoredBy: List<UUID>,
    )

    /**
     * Ends everything past its time: FORMING after [consentMillis], WAITING after
     * [queueTimeoutMillis], unanswered challenges at their expiry, and found matches past their
     * expiry. In an expired found match, an entry whose players all accepted goes back to WAITING;
     * the other one expires.
     */
    fun expire(now: Long, consentMillis: Long, queueTimeoutMillis: Long): Expiry {
        val ended = mutableListOf<Ended>()
        val ignored = mutableListOf<UUID>()
        for (p in proposals.values.toList()) {
            if (p.state != ProposalState.CONFIRMING || now < p.expiresAt) continue
            ignored += p.waitingFor
            val guilty = listOf(p.entryA, p.entryB).filter { id -> entries[id]?.participants?.any { it !in p.accepted } == true }
            for (id in guilty) end(id, QueueState.EXPIRED, now)?.let { ended += it }
            // Nobody at fault left in it (can't happen with an incomplete proposal, but never leave it hanging).
            proposals[p.id]?.let { startFailed(it.id, emptySet(), now) }
        }
        for (c in challenges.values.toList()) {
            if (c.state == ChallengeState.PENDING && now >= c.expiresAt) end(c.fromEntryId, QueueState.EXPIRED, now)?.let { ended += it }
        }
        for (e in entries.values.toList()) {
            val limit = when (e.state) {
                QueueState.FORMING -> consentMillis
                QueueState.WAITING -> queueTimeoutMillis
                else -> continue
            }
            val since = if (e.state == QueueState.WAITING) e.waitingSince ?: e.stateSince else e.stateSince
            if (now - since >= limit) end(e.id, QueueState.EXPIRED, now)?.let { ended += it }
        }
        return Expiry(ended, ignored)
    }

    /** Shutdown / reload: every entry, proposal and challenge ends. */
    fun clear(now: Long): List<QueueEntry> {
        val all = entries.values.toList()
        for (e in all) remove(e, QueueState.CANCELLED, now)
        proposals.clear()
        challenges.clear()
        return all
    }

    companion object {
        fun newId(prefix: String, random: UUID = UUID.randomUUID()): String = prefix + "-" + random.toString().replace("-", "").take(10)
        private val TOKEN = Regex("r[qpc]-[0-9a-f]{10}")
        fun validToken(raw: String): Boolean = TOKEN.matches(raw)

        /** `2v2`, `2`, `2V2` → 2; anything else (or `2v3`) → null. */
        fun parseSize(raw: String): Int? {
            val t = raw.trim().lowercase()
            val n = t.substringBefore('v')
            if ('v' in t && t.substringAfter('v') != n) return null
            return n.toIntOrNull()
        }
    }
}
