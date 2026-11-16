package com.liam.joshymc.lifesteal.diagnostics

import com.liam.joshymc.lifesteal.diagnostics.LifestealDiagnosticsStore.Section
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Bukkit-free rules and text for `/lifesteal admin diag` (issue #1564): who may look, and how a
 * player / server-health report reads. The command only gathers data (memory on the main thread,
 * [LifestealDiagnosticsStore] on the database thread) and prints these lines.
 */
object LifestealDiagnostics {

    const val PERMISSION = "joshymc.lifesteal.diagnostics"

    enum class Tone { HEADER, OK, INFO, WARN, BAD }
    data class Line(val text: String, val tone: Tone)

    /**
     * Null = may use the command. Checked before anything is read. The diagnostics permission grants
     * only this command: no heart, inventory, Elo, coin, match or progression change is reachable
     * from it.
     */
    fun accessProblem(hasPermission: Boolean, hosted: Boolean): String? = when {
        !hasPermission -> "You don't have permission to do that."
        !hosted -> "Lifesteal diagnostics only exist on the Lifesteal server."
        else -> null
    }

    /**
     * Null = may look at [target]'s private state. Looking at yourself is always allowed (it changes
     * nothing); anyone else goes through the staff protection rule ([protection] =
     * `staffRankManager.protectionProblem`), so equal or higher staff are refused and console is always allowed.
     */
    fun targetProblem(self: Boolean, protection: String?): String? = if (self) null else protection

    /** What the server knows from memory (main thread). Plain values, never a Player reference. */
    data class Live(
        val uuid: UUID,
        val name: String,
        val online: Boolean,
        val bedrock: Boolean,
        val world: String?,
        val inLifestealWorld: Boolean?,
        /** Hearts applied from the heart cache (online Lifesteal players), or null. */
        val loadedHearts: Int?,
        val combatTagMillisLeft: Long?,
        val rankedMatch: MatchView?,
        val spectatingMatch: String?,
        val pendingRestoreFlag: Boolean?,
        val progressionPhase: String?,
        /** Services whose state couldn't be read from memory (not started, failed at startup). */
        val unavailable: List<String> = emptyList(),
    )

    data class MatchView(val id: String, val state: String, val kind: String, val terminal: Boolean)

    data class HealthLive(
        val heartsActive: Boolean?,
        val rankedActive: Boolean?,
        val openMatches: List<MatchView>?,
        val combatLoggingActive: Boolean?,
        val combatNpcsStanding: Int?,
        val bankReady: Boolean?,
        val bountiesProcessing: Boolean?,
        val configStatus: String?,
        val progressionStatus: String?,
        val progressionPhase: String?,
        val progressionProblem: String?,
        val databaseOpen: Boolean,
        val unavailable: List<String> = emptyList(),
    )

    fun playerLines(live: Live, stored: LifestealDiagnosticsStore.PlayerReport?, now: Long, databaseNote: String? = null): List<Line> = buildList {
        add(Line("Lifesteal diagnostics: ${live.name}${if (live.bedrock) " (Bedrock)" else ""}", Tone.HEADER))
        add(Line(" UUID: ${live.uuid}", Tone.INFO))
        add(
            if (live.online) Line(" Online in ${live.world ?: "?"}${when (live.inLifestealWorld) { true -> " (Lifesteal world)"; false -> " (not a Lifesteal world)"; null -> "" }}", Tone.INFO)
            else Line(" Offline (memory state below is empty; stored state still shown)", Tone.INFO)
        )
        live.loadedHearts?.let { add(Line(" Hearts applied now: $it", Tone.INFO)) }
        add(
            live.combatTagMillisLeft?.let { Line(" Combat tag: ${seconds(it)}s left", Tone.WARN) } ?: Line(" Combat tag: none", Tone.OK)
        )
        val match = live.rankedMatch
        add(
            if (match == null) Line(" Ranked: not held by a match", Tone.OK)
            else Line(" Ranked: held by match ${match.id} (${match.kind}, ${match.state})", if (match.terminal) Tone.WARN else Tone.INFO)
        )
        live.spectatingMatch?.let { add(Line(" Spectating match $it", Tone.INFO)) }
        if (live.pendingRestoreFlag == true) add(Line(" Ranked restore waiting in memory (runs on join/respawn)", Tone.WARN))
        live.progressionPhase?.let { add(Line(" Progression: $it", Tone.INFO)) }
        if (live.unavailable.isNotEmpty()) add(Line(" Not available from memory: ${live.unavailable.joinToString()}", Tone.WARN))

        if (stored == null) {
            add(Line(" Stored state: ${databaseNote ?: "database unavailable"}", Tone.BAD))
            return@buildList
        }
        stored.databaseProblem?.let {
            add(Line(" Stored state: database unavailable ($it)", Tone.BAD))
            return@buildList
        }

        section("Hearts", stored.hearts) { row ->
            if (row == null) add(Line(" Hearts: no record (never joined Lifesteal here)", Tone.INFO))
            else add(
                Line(
                    " Hearts: ${row.hearts ?: "UNREADABLE"} stored (v${row.version}, updated ${time(row.updatedAt)}, last tx ${row.lastTransaction?.take(8) ?: "none"})",
                    if (row.hearts == null) Tone.BAD else Tone.INFO,
                )
            )
        }
        section("Heart history", stored.heartAudit) { rows ->
            if (rows.isNotEmpty()) {
                add(Line(" Last ${rows.size} heart changes (newest first):", Tone.INFO))
                for (r in rows) add(Line("  ${time(r.createdAt)} ${r.operation} ${r.previous ?: "-"} -> ${r.next ?: "-"}${r.reason?.let { " ($it)" } ?: ""}", Tone.INFO))
            }
        }
        section("Heart items", stored.outstandingHeartItems) { n ->
            if (n > 0) add(Line(" Heart items issued and not redeemed: $n (/lifesteal hearts items)", Tone.INFO))
        }
        section("Ranked roster", stored.rankedOpen) { p ->
            if (p != null) add(Line(" Open ranked roster row: match ${p.matchId}, side ${p.side}, ${p.status}, match ${p.matchState ?: "missing"}", Tone.WARN))
        }
        section("Ranked snapshots", stored.rankedSnapshots) { rows ->
            if (rows.isEmpty()) add(Line(" Ranked inventory: nothing waiting", Tone.OK))
            for (s in rows) {
                val tone = if (s.status == "REVIEW") Tone.BAD else Tone.WARN
                val extra = when (s.status) {
                    "PENDING" -> "restores on join/respawn; online: /lifesteal admin ranked restore"
                    "RESTORING" -> "retried automatically, never applied twice; wait"
                    "REVIEW" -> "staff decision: /lifesteal admin ranked restore <player> apply|resolve confirm"
                    else -> ""
                }
                add(Line(" Ranked inventory ${s.status}: snapshot ${s.snapshotId.take(8)}, match ${s.matchId}, since ${time(s.createdAt)}${s.reviewReason?.let { ", reason: $it" } ?: ""}", tone))
                if (extra.isNotEmpty()) add(Line("  $extra", Tone.INFO))
            }
        }
        section("Combat NPC", stored.combatNpcs) { rows ->
            if (rows.isEmpty()) add(Line(" Combat NPC: no open session", Tone.OK))
            for (s in rows) {
                val left = s.expiresAt - now
                val timing = if (s.state == "ACTIVE") (if (left > 0) "${seconds(left)}s left" else "deadline passed ${seconds(-left)}s ago") else "settles on join"
                add(
                    Line(
                        " Combat NPC ${s.state}${s.outcome?.let { "/$it" } ?: ""}: session ${s.id.take(8)} at ${s.world} ${s.x.toInt()} ${s.y.toInt()} ${s.z.toInt()}, $timing",
                        if (s.state == "ACTIVE" && left > 0) Tone.WARN else Tone.BAD,
                    )
                )
            }
        }
        section("Event inventory", stored.eventInventory) { rows ->
            if (rows.isEmpty()) add(Line(" Event inventory: nothing unsettled", Tone.OK))
            for (r in rows) add(
                Line(" Event inventory ${r.status}: #${r.id} (${r.source}, ${time(r.createdAt)})${r.detail?.let { ", $it" } ?: ""}", if (r.status == "REVIEW") Tone.BAD else Tone.WARN)
            )
        }
        section("Bank", stored.bank) { bank ->
            if (bank == null) add(Line(" Bank: no account", Tone.INFO))
            else {
                add(Line(" Bank: ${bank.balanceCents?.let { coins(it) } ?: "no account"}", Tone.INFO))
                if (bank.failedUnreviewed > 0) add(Line(" Bank: ${bank.failedUnreviewed} failed (rolled back) transaction(s) not reviewed (/bank admin failed)", Tone.WARN))
            }
        }
        section("Bounties", stored.bounty) { b ->
            b.activeOnPlayerCents?.let { add(Line(" Active bounty on this player: ${coins(it)}", Tone.INFO)) }
            for (p in b.pendingPayouts) add(Line(" Bounty payout PENDING: ${coins(p.amountCents)}, ${p.attempts} attempt(s)${p.lastError?.let { ", last error: $it" } ?: ""} (/bounty admin pending)", Tone.WARN))
            if (b.pendingRefunds > 0) add(Line(" Bounty refunds PENDING: ${b.pendingRefunds} (/bounty admin pending)", Tone.WARN))
            if (b.activeOnPlayerCents == null && b.pendingPayouts.isEmpty() && b.pendingRefunds == 0) add(Line(" Bounties: nothing pending", Tone.OK))
        }
    }

    fun healthLines(live: HealthLive, stored: LifestealDiagnosticsStore.HealthReport?, databaseNote: String? = null): List<Line> = buildList {
        add(Line("Lifesteal server health", Tone.HEADER))
        add(Line(" lifesteal.yml: ${live.configStatus ?: "unknown"}", if (live.configStatus == null || live.configStatus.startsWith("invalid")) Tone.BAD else Tone.OK))
        add(state("Hearts", live.heartsActive, "active", "paused"))
        add(state("Ranked", live.rankedActive, "active", "not running"))
        live.openMatches?.let { matches ->
            add(Line(" Ranked matches in memory: ${matches.size}", Tone.INFO))
            for (m in matches.take(10)) add(Line("  ${m.id} ${m.kind} ${m.state}", if (m.terminal) Tone.WARN else Tone.INFO))
        }
        add(state("Combat logging", live.combatLoggingActive, "active", "off"))
        live.combatNpcsStanding?.let { add(Line(" Combat NPCs standing: $it", Tone.INFO)) }
        add(state("Bank", live.bankReady, "ready", "not ready"))
        add(state("Bounties", live.bountiesProcessing, "processing", "not processing"))
        add(
            Line(
                " Progression: ${live.progressionStatus ?: "unknown"}${live.progressionPhase?.let { ", $it" } ?: ""}${live.progressionProblem?.let { " ($it)" } ?: ""}",
                if (live.progressionStatus == null || live.progressionProblem != null) Tone.WARN else Tone.INFO,
            )
        )
        if (live.unavailable.isNotEmpty()) add(Line(" Not available from memory: ${live.unavailable.joinToString()}", Tone.WARN))

        if (!live.databaseOpen || stored == null) {
            add(Line(" Database: ${databaseNote ?: "closed or unavailable"}", Tone.BAD))
            return@buildList
        }
        stored.databaseProblem?.let {
            add(Line(" Database: not answering ($it)", Tone.BAD))
            return@buildList
        }
        add(Line(" Database: answering (${stored.latencyMillis ?: "?"} ms)", if ((stored.latencyMillis ?: 0) > SLOW_MILLIS) Tone.WARN else Tone.OK))
        section("Ranked snapshots", stored.rankedSnapshots) { counts ->
            add(countsLine(" Ranked inventories waiting", counts, bad = "REVIEW"))
        }
        section("Combat NPC sessions", stored.combatNpcsOpen) { counts ->
            add(countsLine(" Combat NPC sessions open", counts, bad = null))
        }
        section("Event inventories", stored.eventInventory) { counts ->
            add(countsLine(" Event inventories unsettled", counts, bad = "REVIEW"))
        }
        section("Bounty payouts", stored.bountyPayoutsPending) { n -> add(Line(" Bounty payouts pending: $n", if (n > 0) Tone.WARN else Tone.OK)) }
        section("Bounty refunds", stored.bountyRefundsPending) { n -> add(Line(" Bounty refunds pending: $n", if (n > 0) Tone.WARN else Tone.OK)) }
        section("Bank failures", stored.bankFailedUnreviewed) { n -> add(Line(" Bank failures not reviewed: $n", if (n > 0) Tone.WARN else Tone.OK)) }
    }

    private fun countsLine(label: String, counts: Map<String, Int>, bad: String?): Line {
        val total = counts.values.sum()
        if (total == 0) return Line("$label: none", Tone.OK)
        val tone = if (bad != null && (counts[bad] ?: 0) > 0) Tone.BAD else Tone.WARN
        return Line("$label: ${counts.entries.joinToString { "${it.value} ${it.key}" }}", tone)
    }

    private fun state(label: String, value: Boolean?, on: String, off: String): Line = when (value) {
        true -> Line(" $label: $on", Tone.OK)
        false -> Line(" $label: $off", Tone.WARN)
        null -> Line(" $label: unknown (service not started)", Tone.BAD)
    }

    private inline fun <T> MutableList<Line>.section(label: String, section: Section<T>, ok: (T) -> Unit) {
        when (section) {
            is Section.Ok -> ok(section.value)
            Section.NotCreated -> add(Line(" $label: not set up on this server", Tone.INFO))
            is Section.Failed -> add(Line(" $label: couldn't be read (${section.message})", Tone.BAD))
        }
    }

    private const val SLOW_MILLIS = 250L
    private val FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("America/New_York"))

    fun time(millis: Long): String = FORMAT.format(Instant.ofEpochMilli(millis))
    fun seconds(millis: Long): Long = (millis.coerceAtLeast(0) + 999) / 1000
    fun coins(cents: Long): String = "$" + "%,d.%02d".format(cents / 100, Math.floorMod(cents, 100L))
}
