package com.liam.joshymc.lifesteal.ranked.rating

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.ranked.Side
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The ranked rating service (issue #1365): Individual Player Elo and Team Elo for Ranked Team
 * Wars. Lifesteal only: where Lifesteal isn't hosted it creates no table, runs no task and every
 * read answers "unavailable", so Survival data is never touched.
 *
 * - A completed match is handed over by [LifestealRankedManager][com.liam.joshymc.lifesteal.ranked.LifestealRankedManager]
 *   ([onMatchCompleted]); the rating runs on the database thread ([com.liam.joshymc.manager.DatabaseManager.async]),
 *   never on the main thread, through [RatingStore.finalize] (one transaction, idempotent).
 * - A sweep (at startup and every [SWEEP_TICKS]) rates every completed match that has no final
 *   result: crashes between completion and rating, and FAILED attempts (database errors, retried
 *   up to [MAX_ATTEMPTS] times, then left for `/lifesteal admin ranked rating retry`).
 * - Players see their change once it is committed: right away when online, else on their next join
 *   (the history row's `notified` flag, claimed once).
 * - Commands and the menu read through [loadProfile] (async) and a small bounded cache.
 *
 * There is no command or API that sets a rating.
 */
class LifestealRatingManager(private val plugin: Joshymc) {

    private val store = RatingStore(plugin.databaseManager)

    @Volatile private var tablesReady = false
    private var task: BukkitTask? = null
    /** Matches queued for rating right now (so one match never has two rating tasks). */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Players whose pending result messages are being delivered (one delivery at a time each). */
    private val delivering: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Main thread only; bounded, evicted on quit. */
    private val profiles = object : LinkedHashMap<UUID, Profile>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Profile>?): Boolean = size > PROFILE_CACHE_SIZE
    }

    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes
    val active: Boolean get() = hosted && tablesReady && policy() != null
    /** The rating tables exist (issue #1367: the statistics read them). */
    val ready: Boolean get() = hosted && tablesReady

    /** The current settings as a calculation policy, or null while lifesteal.yml is invalid. */
    fun policy(): RatingStore.Policy? {
        val elo = plugin.lifestealConfigManager.settings?.rankedTeamWars?.elo ?: return null
        val params = try { EloRating.Params.of(elo) } catch (_: IllegalArgumentException) { return null }
        return RatingStore.Policy(params, elo.rateChallenges, elo.sameOpponentLimit, elo.sameOpponentWindowHours * 3_600_000L)
    }

    fun currentSeason(now: Long = System.currentTimeMillis()): String? =
        plugin.lifestealProgressionManager.progress(now).season?.id?.takeIf { RatingRules.validSeason(it) }

    fun unavailableReason(): String? = when {
        !hosted -> "Ranked ratings only exist on the Lifesteal server."
        !tablesReady -> "Ranked ratings are unavailable (database error); see the console."
        policy() == null -> "Ranked ratings are unavailable (lifesteal.yml invalid)."
        else -> null
    }

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload, after the ranked match manager (its recovery runs first). */
    fun refresh() {
        task?.cancel()
        task = null
        profiles.clear()
        if (!hosted) return
        if (!tablesReady) {
            try {
                store.createTables()
                tablesReady = true
            } catch (e: Exception) {
                plugin.logger.severe("[RankedElo] Could not create the rating tables; ranked matches won't be rated until this is fixed: ${e.message}")
                return
            }
        }
        val p = policy()
        if (p == null) plugin.logger.severe("[RankedElo] lifesteal.yml is invalid, so no match is rated until it is fixed (completed matches wait and are rated afterwards).")
        else plugin.logger.info("[RankedElo] Player Elo starts at ${p.params.initialPlayer}, Team Elo at ${p.params.initialTeam}, K ${p.params.kFactor}, bounds ${p.params.minimum}-${p.params.maximum}; challenges ${if (p.rateChallenges) "rated" else "unrated"}; same-opponent limit ${if (p.sameOpponentLimit == 0) "off" else "${p.sameOpponentLimit} per ${p.sameOpponentWindowMillis / 3_600_000L}h"}.")
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sweep() }, 200L, SWEEP_TICKS)
        for (player in Bukkit.getOnlinePlayers()) deliverNotices(player)
    }

    fun stop() {
        task?.cancel()
        task = null
        profiles.clear()
    }

    // ── Rating ──────────────────────────────────────────────

    /** A match was recorded COMPLETED: rate it (or record why it's unrated) off the main thread. */
    fun onMatchCompleted(matchId: String) {
        if (!hosted || !tablesReady) return
        queue(matchId, announce = true)
    }

    /** Rates every completed match without a final result (restart recovery and FAILED retries). */
    fun sweep() {
        if (!hosted || !tablesReady || policy() == null) return
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val ids = try { store.unprocessedCompleted(MAX_ATTEMPTS, SWEEP_BATCH) } catch (e: Exception) {
                plugin.logger.warning("[RankedElo] Couldn't look for unrated matches: ${e.message}")
                return@Runnable
            }
            if (ids.isEmpty()) return@Runnable
            onMain { for (id in ids) queue(id, announce = true) }
        })
    }

    sealed interface QueueResult {
        data object Queued : QueueResult
        data class Refused(val reason: String) : QueueResult
    }

    /** Staff retry of one match (only does anything for a match without a final result). */
    fun retry(matchId: String): QueueResult {
        unavailableReason()?.let { return QueueResult.Refused(it) }
        if (!queue(matchId, announce = true)) return QueueResult.Refused("Match $matchId is already being rated.")
        return QueueResult.Queued
    }

    private fun queue(matchId: String, announce: Boolean): Boolean {
        val policy = policy() ?: return false
        if (!inFlight.add(matchId)) return false
        plugin.databaseManager.async("ranked rating $matchId") {
            val now = System.currentTimeMillis()
            val result = try {
                store.finalize(matchId, policy, now)
            } catch (e: Exception) {
                // Nothing of the attempt was written (the transaction rolled back).
                plugin.logger.severe("[RankedElo] Rating match $matchId failed; nobody's rating changed and it will be retried: ${e.message}")
                try { store.recordFailure(matchId, e.message ?: e.javaClass.simpleName, now) } catch (f: Exception) {
                    plugin.logger.severe("[RankedElo] Couldn't even record the failure of $matchId (${f.message}); the sweep retries it anyway.")
                }
                null
            } finally {
                inFlight.remove(matchId)
            }
            if (result != null && announce) onMain { handle(result) }
        }
        return true
    }

    private fun handle(result: RatingStore.Finalized) {
        when (result) {
            is RatingStore.Finalized.Rated -> {
                plugin.logger.info("[RankedElo] Rated ${result.matchId} (${result.teamAName} vs ${result.teamBName}, ${result.size}v${result.size}): " +
                    result.teams.entries.joinToString { (id, c) -> "${if (id == result.teamAId) result.teamAName else result.teamBName} ${c.before}->${c.after}" } + ".")
                // Issue #1367: leaderboards never show a page read before this commit.
                plugin.lifestealRankedStatsManager.onRatingsCommitted()
                // Issue #1426: Team XP for both teams of a rated match (and the winner), once per match and team.
                plugin.teamProgression()?.onRankedRated(result)
                // Team Elo also changed for teammates who didn't play: drop their cached view too.
                for (teamId in result.teams.keys) plugin.lifestealTeamManager.team(teamId)?.members?.forEach { profiles.remove(it.uuid) }
                for (uuid in result.sides.keys) {
                    profiles.remove(uuid)
                    Bukkit.getPlayer(uuid)?.let { deliverNotices(it) }
                }
            }
            is RatingStore.Finalized.Unrated -> {
                plugin.logger.info("[RankedElo] Match ${result.matchId} is unrated: ${result.reason}.")
                // Issue #1499: every participant of both teams gets the same line; an unrated match is
                // never heart-safe, so the heart reminder goes with it.
                if (!result.test) {
                    val line = unratedNotice(result)
                    for (uuid in result.participants) Bukkit.getPlayer(uuid)?.let { plugin.commsManager.send(it, prefix().append(line)) }
                }
                // Issue #1499: a match the rating limit stopped is logged for staff review (logging only).
                if (result.limit != null) plugin.lifestealRankedManager.monitor.onRatingLimited(result)
            }
            is RatingStore.Finalized.Invalid -> plugin.logger.severe("[RankedElo] Match ${result.matchId} was NOT rated: its record is inconsistent (${result.reason}). Nothing changed; see /lifesteal admin ranked rating match ${result.matchId}.")
            is RatingStore.Finalized.NotReady -> plugin.logger.warning("[RankedElo] Match ${result.matchId} isn't over (${result.reason}); not rated.")
            is RatingStore.Finalized.Missing -> plugin.logger.warning("[RankedElo] Match ${result.matchId} has no record; not rated.")
            is RatingStore.Finalized.AlreadyProcessed -> Unit
        }
    }

    // ── Result messages ─────────────────────────────────────

    /** Issue #1499: why an unrated match changed no Elo, the same for both teams. */
    private fun unratedNotice(result: RatingStore.Finalized.Unrated): Component {
        val why = when (result.limit) {
            RatingRules.Limit.TEAM_PAIR -> "same-opponent rating limit reached: these two teams already played ${result.earlierCount} rated match(es) against each other recently"
            RatingRules.Limit.REPEATED_ROSTER -> "repeated opponent roster limit reached: mostly the same players already played ${result.earlierCount} rated match(es) against each other recently, whatever their teams"
            null -> result.reason
        }
        return Component.text("This match didn't change Elo ($why). ", NamedTextColor.GRAY)
            .append(Component.text("Hearts won or lost in it still count.", NamedTextColor.YELLOW))
    }

    /** Shows [player] every committed rating change they haven't seen (each once). */
    fun deliverNotices(player: Player) {
        if (!hosted || !tablesReady) return
        val uuid = player.uniqueId
        if (!delivering.add(uuid)) return
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val notices = try {
                store.unnotified(uuid, MAX_NOTICES).mapNotNull { row ->
                    if (!store.claimNotice(row.id)) return@mapNotNull null
                    val match = store.matchRecord(row.matchId)
                    Notice(row, match?.let { if (row.side == Side.A) it.teamAName else it.teamBName } ?: "?", match?.let { if (row.side == Side.A) it.teamBName else it.teamAName } ?: "?", match?.size ?: 0, store.teamChange(row.matchId, row.teamId))
                }
            } catch (e: Exception) {
                plugin.logger.warning("[RankedElo] Couldn't read ${player.name}'s rating results: ${e.message}")
                emptyList()
            } finally {
                delivering.remove(uuid)
            }
            if (notices.isEmpty()) return@Runnable
            onMain {
                val online = Bukkit.getPlayer(uuid)
                if (online == null || !online.isOnline) {
                    // Gone meanwhile: show them next time.
                    plugin.databaseManager.async("ranked rating notice unclaim") {
                        for (n in notices) store.unclaimNotice(n.row.id)
                    }
                    return@onMain
                }
                for (n in notices) sendNotice(online, n)
            }
        })
    }

    private data class Notice(val row: RatingStore.HistoryRow, val ownTeam: String, val otherTeam: String, val size: Int, val team: RatingStore.HistoryRow?)

    private fun sendNotice(player: Player, n: Notice) {
        val won = n.row.outcome == RatingOutcome.WIN
        val send = { c: Component -> plugin.commsManager.send(player, c) }
        send(Component.text("Ranked Team Wars | Match Complete", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
        send(Component.text("Winner: ", NamedTextColor.GRAY).append(Component.text(if (won) n.ownTeam else n.otherTeam, NamedTextColor.GREEN))
            .append(Component.text("  Loser: ", NamedTextColor.GRAY)).append(Component.text(if (won) n.otherTeam else n.ownTeam, NamedTextColor.RED))
            .append(Component.text("  (${n.size}v${n.size})", NamedTextColor.GRAY)))
        send(Component.text("Your Player Elo: ", NamedTextColor.GRAY).append(Component.text(n.row.new.toString(), NamedTextColor.WHITE)).append(Component.text(" (${signed(n.row.delta)})", deltaColor(n.row.delta))))
        n.team?.let { t ->
            send(Component.text("${n.ownTeam} Team Elo: ", NamedTextColor.GRAY).append(Component.text(t.new.toString(), NamedTextColor.WHITE)).append(Component.text(" (${signed(t.delta)})", deltaColor(t.delta))))
        }
        // Issue #1367: promotion / demotion lines, part of this once-only, committed-only notice.
        for (line in plugin.lifestealRankedStatsManager.changeLines(n.row.previous, n.row.new, n.ownTeam, n.team?.previous, n.team?.new)) send(line)
    }

    fun onJoin(player: Player) {
        if (!hosted || !tablesReady) return
        // A moment after joining, so the message isn't buried under the join messages.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { if (player.isOnline) deliverNotices(player) }, 60L)
    }

    fun onQuit(player: Player) {
        profiles.remove(player.uniqueId)
    }

    // ── Profiles (commands and the menu) ────────────────────

    /** One player's ratings in the current season. [team] = their current Lifesteal team, if any. */
    data class Profile(
        val uuid: UUID,
        val seasonId: String,
        val player: RatingStore.RatingRow?,
        val initialPlayer: Int,
        val teamId: String?,
        val teamName: String?,
        val team: RatingStore.RatingRow?,
        val initialTeam: Int,
        val recent: List<RatingStore.HistoryRow>,
        val loadedAt: Long,
    ) {
        val playerRating: Int get() = player?.rating ?: initialPlayer
        val teamRating: Int? get() = teamId?.let { team?.rating ?: initialTeam }
        val wins: Int get() = player?.wins ?: 0
        val losses: Int get() = player?.losses ?: 0
        val matches: Int get() = player?.matches ?: 0
        val winRate: String get() = if (matches == 0) "-" else "${Math.round(wins * 100.0 / matches)}%"
    }

    /** The cached profile, unless it is old or the player's team changed since it was read. */
    fun cachedProfile(uuid: UUID): Profile? {
        val p = profiles[uuid] ?: return null
        val stale = System.currentTimeMillis() - p.loadedAt > PROFILE_MAX_AGE_MILLIS || plugin.lifestealTeamManager.teamOf(uuid)?.id != p.teamId
        if (stale) profiles.remove(uuid)
        return if (stale) null else p
    }

    /** Loads [uuid]'s profile off the main thread, then calls [done] on the main thread (null + reason on failure). */
    fun loadProfile(uuid: UUID, recentLimit: Int = 5, done: (Profile?, String?) -> Unit) {
        unavailableReason()?.let { return done(null, it) }
        val season = currentSeason() ?: return done(null, "There is no active Lifesteal season, so there are no ratings yet.")
        val policy = policy() ?: return done(null, "Ranked ratings are unavailable (lifesteal.yml invalid).")
        val team = plugin.lifestealTeamManager.teamOf(uuid)
        val teamId = team?.id
        val teamName = team?.name
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val profile = try {
                Profile(
                    uuid, season,
                    store.rating(season, RatingType.PLAYER_ELO, uuid.toString()), policy.params.initialPlayer,
                    teamId, teamName, teamId?.let { store.rating(season, RatingType.TEAM_ELO, it) }, policy.params.initialTeam,
                    store.history(RatingType.PLAYER_ELO, uuid.toString(), season, recentLimit), System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                onMain { done(null, "Couldn't read the ratings: ${e.message}") }
                return@Runnable
            }
            onMain {
                if (Bukkit.getPlayer(uuid)?.isOnline == true) profiles[uuid] = profile
                done(profile, null)
            }
        })
    }

    // ── Diagnostics (staff, async) ──────────────────────────

    /** Runs [read] against the store off the main thread and hands the result to [done] on the main thread. */
    fun <T> read(read: (RatingStore) -> T, done: (T?, String?) -> Unit) {
        if (!hosted || !tablesReady) return done(null, unavailableReason())
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val value = try { read(store) } catch (e: Exception) {
                onMain { done(null, "Couldn't read the rating data: ${e.message}") }
                return@Runnable
            }
            onMain { done(value, null) }
        })
    }

    val inFlightCount: Int get() = inFlight.size

    private fun onMain(block: () -> Unit) {
        try {
            if (plugin.isEnabled) Bukkit.getScheduler().runTask(plugin, Runnable { block() })
        } catch (_: Exception) {
            // Shutting down: results are in the database; players see them on their next join.
        }
    }

    companion object {
        const val PERMISSION_STATS_OTHERS = "joshymc.lifesteal.ranked.stats.others"
        const val PROFILE_CACHE_SIZE = 256
        const val PROFILE_MAX_AGE_MILLIS = 5 * 60_000L
        const val MAX_ATTEMPTS = 5
        const val MAX_NOTICES = 5
        const val SWEEP_BATCH = 50
        /** Five minutes. */
        const val SWEEP_TICKS = 20L * 60L * 5L

        fun signed(n: Int): String = if (n >= 0) "+$n" else n.toString()
        fun deltaColor(n: Int): NamedTextColor = when {
            n > 0 -> NamedTextColor.GREEN
            n < 0 -> NamedTextColor.RED
            else -> NamedTextColor.GRAY
        }
        /** "5m ago", "3h ago", "2d ago" (no time zone needed). */
        fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
            val minutes = ((now - at).coerceAtLeast(0)) / 60_000
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "${minutes}m ago"
                minutes < 60 * 48 -> "${minutes / 60}h ago"
                else -> "${minutes / 1440}d ago"
            }
        }
        fun prefix(): Component = Component.text("[Ranked] ", NamedTextColor.GOLD)
    }
}
