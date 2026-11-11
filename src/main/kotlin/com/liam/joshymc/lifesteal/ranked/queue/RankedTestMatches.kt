package com.liam.joshymc.lifesteal.ranked.queue

import java.util.UUID

/**
 * Issue #1491 (audit finding F-3): staff TEST matches (`/lifesteal admin ranked testmatch`) used to
 * pull two teams' online players straight into a fight where real permanent hearts move, with no
 * consent, no confirmation and no hierarchy check. This is the Bukkit-free, memory-only state of
 * the replacement flow, kept next to the matchmaking [RankedQueueBook] and answered through the same
 * `/ranked accept|decline <token>`:
 *
 * 1. The staff member runs the command: a [TestMatchConfirmation] (`rx-` token) is stored, bound to
 *    their actor key and the exact [TestMatchPlan] (teams by id, size, arena, the players on each
 *    side). It expires, is single use, and a new request replaces their older one.
 * 2. They confirm with the token: the plan is rebuilt from live data and must be [TestMatchPlan.sameAs]
 *    the stored one, else the confirmation is spent and nothing happens. Then a [TestProposal]
 *    (`rt-` token) is created.
 * 3. Every participant accepts that exact proposal themselves (a token is required, so nobody accepts
 *    a match they weren't shown); one decline, one disconnect or the timeout ends it.
 * 4. [beginStart] is the single CONFIRMING → STARTING gate, so repeated accepts can't start twice;
 *    the match lifecycle then revalidates everything again before anything is touched.
 *
 * Nothing is persisted: a restart or reload ends every confirmation and proposal (nothing was
 * touched yet, so nothing needs undoing). Collections are capped.
 */
data class TestMatchPlan(
    val teamAId: String,
    val teamAName: String,
    val teamBId: String,
    val teamBName: String,
    val size: Int,
    /** The arena the staff member named, or null for "any available one". */
    val arenaId: String?,
    val sideA: List<UUID>,
    val sideB: List<UUID>,
) {
    val participants: List<UUID> get() = sideA + sideB

    /** Same match: same teams (by id, same sides), size, arena and exactly the same players on each side. */
    fun sameAs(other: TestMatchPlan): Boolean =
        teamAId == other.teamAId && teamBId == other.teamBId && size == other.size && arenaId == other.arenaId &&
            sideA.toSet() == other.sideA.toSet() && sideB.toSet() == other.sideB.toSet() &&
            sideA.size == other.sideA.size && sideB.size == other.sideB.size

    fun sideOf(uuid: UUID): String? = when (uuid) {
        in sideA -> teamAId
        in sideB -> teamBId
        else -> null
    }

    /** One line for logs and audit rows (ids only, never addresses). */
    fun describe(): String =
        "teams=$teamAName($teamAId) vs $teamBName($teamBId) size=${size}v$size arena=${arenaId ?: "any"} " +
            "sideA=${sideA.joinToString(",")} sideB=${sideB.joinToString(",")}"
}

/** A staff member's pending "are you sure", bound to them and to [plan]. */
class TestMatchConfirmation(
    val id: String,
    /** Player UUID as a string, or [TestMatchRules.CONSOLE_ACTOR]. */
    val actor: String,
    val plan: TestMatchPlan,
    val createdAt: Long,
    val expiresAt: Long,
)

enum class TestProposalState(val terminal: Boolean = false) {
    CONFIRMING, STARTING, STARTED(true), DECLINED(true), EXPIRED(true), CANCELLED(true)
}

/** A confirmed staff test match waiting for every participant's own acceptance. */
class TestProposal(
    val id: String,
    val actor: String,
    val actorName: String,
    val plan: TestMatchPlan,
    val createdAt: Long,
    val expiresAt: Long,
) {
    var state: TestProposalState = TestProposalState.CONFIRMING
        internal set
    private val yes = LinkedHashSet<UUID>()
    val accepted: Set<UUID> get() = yes
    val participants: List<UUID> get() = plan.participants
    val waitingFor: List<UUID> get() = participants.filter { it !in yes }
    val complete: Boolean get() = yes.size == participants.size

    internal fun accept(uuid: UUID): Boolean = uuid in participants && yes.add(uuid)
}

class TestMatchBook(val maxProposals: Int = 20, val maxConfirmations: Int = 50) {

    private val confirmations = LinkedHashMap<String, TestMatchConfirmation>()
    private val proposals = LinkedHashMap<String, TestProposal>()
    private val byPlayer = HashMap<UUID, String>()

    fun proposal(id: String): TestProposal? = proposals[id]
    fun proposalOf(uuid: UUID): TestProposal? = byPlayer[uuid]?.let { proposals[it] }
    fun allProposals(): List<TestProposal> = proposals.values.toList()
    fun confirmation(id: String): TestMatchConfirmation? = confirmations[id]
    val isEmpty: Boolean get() = proposals.isEmpty() && confirmations.isEmpty()

    // ── Staff confirmation ──────────────────────────────────

    /** Stores a confirmation for [actor] (replacing any older one of theirs). */
    fun request(actor: String, plan: TestMatchPlan, id: String, now: Long, ttlMillis: Long): TestMatchConfirmation {
        confirmations.values.removeIf { it.actor == actor || it.expiresAt <= now }
        while (confirmations.size >= maxConfirmations) confirmations.remove(confirmations.keys.first())
        val c = TestMatchConfirmation(id, actor, plan, now, now + ttlMillis)
        confirmations[id] = c
        return c
    }

    sealed interface ConfirmResult {
        data object NotFound : ConfirmResult
        /** Someone else's token: refused and left alone (it stays theirs). */
        data object NotYours : ConfirmResult
        data object Expired : ConfirmResult
        /** Teams, players, size or arena differ from what was shown: the confirmation is spent. */
        data object Changed : ConfirmResult
        data class Busy(val uuid: UUID) : ConfirmResult
        data object Full : ConfirmResult
        data class Confirmed(val proposal: TestProposal) : ConfirmResult
    }

    /**
     * [actor] confirms [token]. [current] is the plan rebuilt from live data right now (null = a
     * team is gone). Except for [ConfirmResult.NotYours], the token is spent whatever happens.
     */
    fun confirm(actor: String, actorName: String, token: String, current: TestMatchPlan?, proposalId: String, now: Long, consentMillis: Long): ConfirmResult {
        val c = confirmations[token] ?: return ConfirmResult.NotFound
        if (c.actor != actor) return ConfirmResult.NotYours
        confirmations.remove(token)
        if (now >= c.expiresAt) return ConfirmResult.Expired
        if (current == null || !c.plan.sameAs(current)) return ConfirmResult.Changed
        c.plan.participants.firstOrNull { byPlayer.containsKey(it) }?.let { return ConfirmResult.Busy(it) }
        if (proposals.size >= maxProposals || proposals.containsKey(proposalId)) return ConfirmResult.Full
        val p = TestProposal(proposalId, actor, actorName, c.plan, now, now + consentMillis)
        proposals[p.id] = p
        for (u in p.participants) byPlayer[u] = p.id
        return ConfirmResult.Confirmed(p)
    }

    // ── Participant consent ─────────────────────────────────

    sealed interface AcceptResult {
        data object NothingToAccept : AcceptResult
        /** No token given: the player must answer the exact proposal they were shown. */
        data class NeedsToken(val proposal: TestProposal) : AcceptResult
        data object Stale : AcceptResult
        data object Expired : AcceptResult
        data object Already : AcceptResult
        data class Accepted(val proposal: TestProposal) : AcceptResult
    }

    fun accept(uuid: UUID, token: String?, now: Long): AcceptResult {
        val p = proposalOf(uuid) ?: return if (token != null) AcceptResult.Stale else AcceptResult.NothingToAccept
        if (token == null) return AcceptResult.NeedsToken(p)
        if (token != p.id || p.state != TestProposalState.CONFIRMING) return AcceptResult.Stale
        if (now >= p.expiresAt) return AcceptResult.Expired
        if (!p.accept(uuid)) return AcceptResult.Already
        return AcceptResult.Accepted(p)
    }

    /** CONFIRMING → STARTING once, only when everyone accepted: the single gate against starting twice. */
    fun beginStart(id: String, now: Long): TestProposal? {
        val p = proposals[id] ?: return null
        if (p.state != TestProposalState.CONFIRMING || !p.complete || now >= p.expiresAt) return null
        p.state = TestProposalState.STARTING
        return p
    }

    /** The match lifecycle took it. */
    fun started(id: String): TestProposal? {
        val p = proposals[id]?.takeIf { it.state == TestProposalState.STARTING } ?: return null
        finish(p, TestProposalState.STARTED)
        return p
    }

    /** Ends [id] as [state] (DECLINED, EXPIRED or CANCELLED); its players are released. Null when it already ended. */
    fun end(id: String, state: TestProposalState): TestProposal? {
        require(state.terminal && state != TestProposalState.STARTED)
        val p = proposals[id]?.takeIf { !it.state.terminal } ?: return null
        finish(p, state)
        return p
    }

    private fun finish(p: TestProposal, state: TestProposalState) {
        p.state = state
        proposals.remove(p.id)
        for (u in p.participants) if (byPlayer[u] == p.id) byPlayer.remove(u)
    }

    /** Drops expired confirmations and ends every CONFIRMING proposal past its time (returned). */
    fun expire(now: Long): List<TestProposal> {
        confirmations.values.removeIf { it.expiresAt <= now }
        return proposals.values.filter { it.state == TestProposalState.CONFIRMING && now >= it.expiresAt }
            .mapNotNull { end(it.id, TestProposalState.EXPIRED) }
    }

    /** Shutdown / reload: everything ends; returns the proposals that were still open. */
    fun clear(): List<TestProposal> {
        confirmations.clear()
        val open = proposals.values.filter { !it.state.terminal }
        for (p in open) finish(p, TestProposalState.CANCELLED)
        proposals.clear()
        byPlayer.clear()
        return open
    }

    companion object {
        private val TOKEN = Regex("r[tx]-[0-9a-f]{10}")
        fun validToken(raw: String): Boolean = TOKEN.matches(raw)
        fun isProposalToken(raw: String?): Boolean = raw != null && raw.startsWith("rt-") && validToken(raw)
    }
}

/** The decisions of the staff test-match flow, Bukkit-free. */
object TestMatchRules {

    /** Actor key of the server console (no UUID). */
    const val CONSOLE_ACTOR = "CONSOLE"

    /** How long a staff confirmation stays valid. */
    const val CONFIRM_MILLIS = 60_000L

    /**
     * Every reason the staff member can't (still) run [plan] (empty = they can). Consent never
     * replaces any of these: [hasPermission] is the ranked admin node, [protection] the staff
     * hierarchy rule for each participant (self, equal and higher staff refused), [participant] the
     * normal ranked eligibility of each player, [name] only for messages.
     */
    fun refusals(
        plan: TestMatchPlan,
        hasPermission: Boolean,
        protection: (UUID) -> String?,
        participant: (UUID) -> String?,
        name: (UUID) -> String,
    ): List<String> {
        if (!hasPermission) return listOf("You don't have permission to run ranked test matches.")
        val out = mutableListOf<String>()
        if (plan.teamAId == plan.teamBId) out += "A team can't play itself."
        if (plan.sideA.size != plan.size) out += "${plan.teamAName} has ${plan.sideA.size} of ${plan.size} players online."
        if (plan.sideB.size != plan.size) out += "${plan.teamBName} has ${plan.sideB.size} of ${plan.size} players online."
        if (plan.participants.toSet().size != plan.participants.size) out += "A player is on both sides."
        for (uuid in plan.participants.distinct()) {
            protection(uuid)?.let { out += "${name(uuid)}: $it" }
            participant(uuid)?.let { out += "${name(uuid)} $it." }
        }
        return out
    }

    /**
     * What each participant reads before accepting: both teams, the size, that permanent hearts are
     * at risk under the normal heart rules, that no Elo is awarded, and that everyone must accept.
     */
    fun consentLines(plan: TestMatchPlan, staffName: String, heartRange: IntRange?, consentSeconds: Long): List<String> = listOf(
        "Staff TEST match: ${plan.teamAName} vs ${plan.teamBName} (${plan.size}v${plan.size}), set up by $staffName.",
        "PERMANENT HEARTS ARE AT RISK: normal Lifesteal heart stealing and cooldowns apply" +
            (heartRange?.let { " (${it.first}-${it.last} hearts; your last heart can't be lost)" } ?: "") + ".",
        "TEST matches are unrated: no Elo is won or lost. Your inventory and equipment are saved and given back afterwards.",
        "Every player has to accept for themselves within ${consentSeconds}s; one decline cancels it. You can say no.",
    )

    /** The staff member's preview before confirming. */
    fun previewLines(plan: TestMatchPlan, name: (UUID) -> String, consentSeconds: Long): List<String> = listOf(
        "TEST match preview: ${plan.teamAName} vs ${plan.teamBName}, ${plan.size}v${plan.size}, arena ${plan.arenaId ?: "any available"}.",
        "${plan.teamAName}: ${plan.sideA.joinToString { name(it) }}",
        "${plan.teamBName}: ${plan.sideB.joinToString { name(it) }}",
        "Permanent hearts are at risk (normal heart rules); TEST matches award no Elo. Each player must accept within ${consentSeconds}s.",
    )
}
