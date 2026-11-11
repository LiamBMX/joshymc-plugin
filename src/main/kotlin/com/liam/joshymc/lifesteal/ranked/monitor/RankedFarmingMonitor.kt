package com.liam.joshymc.lifesteal.ranked.monitor

import com.liam.joshymc.Joshymc
import java.util.UUID

/**
 * Issue #1474: runs [RankedMonitorRules] once per finished ranked match and writes what it finds
 * to the staff review log (`lifesteal_ranked_reviews` + one `admin_logs` row, shown in /admin).
 * **Logging only**: it runs after the match result, heart decisions and restorations are done,
 * off the main thread (`databaseManager.async`), and any failure is only logged, so it can never
 * delay or change a heart transfer, a rating, a match result or a player. Nothing is told to the
 * players involved. Owned by [com.liam.joshymc.lifesteal.ranked.LifestealRankedManager].
 */
class RankedFarmingMonitor(private val plugin: Joshymc) {

    private val store = RankedMonitorStore(plugin.databaseManager)

    @Volatile private var ready = false
    @Volatile var settings: RankedMonitorSettings? = null
        private set

    /** Startup and every reload, after the ranked tables exist. */
    fun refresh() {
        settings = plugin.lifestealConfigManager.rankedMonitoring.settings
        ready = try {
            store.createTables()
            true
        } catch (e: Exception) {
            plugin.logger.warning("[RankedMonitor] Couldn't create the review table, so ranked monitoring is off: ${e.message}")
            false
        }
        val s = settings
        if (ready && s != null && s.enabled) {
            plugin.logger.info(
                "[RankedMonitor] Logging-only ranked monitoring on (window ${s.windowHours}h: team matchups ${off(s.repeatedTeamMatches)}, " +
                    "player matchups ${off(s.repeatedPlayerMatches)}, heart transfers ${off(s.repeatedHeartTransfers)}, forfeits ${off(s.repeatedForfeits)}, " +
                    "shared connections ${if (s.sharedConnections) "on" else "off"})."
            )
        } else if (s == null) {
            plugin.logger.warning("[RankedMonitor] ranked-team-wars.monitoring is invalid, so ranked monitoring is off (matches and hearts are unaffected).")
        }
    }

    private fun off(n: Int): String = if (n == 0) "off" else n.toString()

    val active: Boolean get() = ready && settings?.enabled == true

    /**
     * Queues the check of one finished match. [names] are the participants' names, taken on the
     * main thread for the log lines. Issue #1491: staff TEST matches are checked too (their real
     * hearts can be farmed); they are counted against other TEST matches only, so staff testing
     * never inflates the public counts, and their findings name the staff member who started them.
     */
    fun onMatchEnded(facts: RankedMonitorRules.MatchFacts, names: Map<UUID, String>) {
        val s = settings ?: return
        if (!ready || !s.enabled) return
        val now = System.currentTimeMillis()
        plugin.databaseManager.async("ranked monitoring ${facts.matchId}") {
            try {
                val findings = RankedMonitorRules.evaluate(facts, store.counts(facts, s, now), s)
                for (f in findings) {
                    if (!store.record(f, facts.matchId, now, s.windowMillis)) continue
                    report(f, facts.matchId, names)
                }
            } catch (e: Exception) {
                plugin.logger.warning("[RankedMonitor] Couldn't check match ${facts.matchId}: ${e.message} (nothing about the match changed).")
            }
        }
    }

    /**
     * Issue #1499: [result] was made unrated by a same-opponent rating limit. Logs it for staff
     * review (deduplicated per roster and window). Logging only: the rating decision is already
     * committed, and nothing here blocks, punishes or changes anything.
     */
    fun onRatingLimited(result: com.liam.joshymc.lifesteal.ranked.rating.RatingStore.Finalized.Unrated) {
        val s = settings ?: return
        val limit = result.limit ?: return
        if (!ready || !s.enabled) return
        val sideA = result.sides.filterValues { it == com.liam.joshymc.lifesteal.ranked.Side.A }.keys.toList()
        val sideB = result.sides.filterValues { it == com.liam.joshymc.lifesteal.ranked.Side.B }.keys.toList()
        val finding = RankedMonitorRules.ratingLimitFinding(limit.name, sideA, sideB, result.teamIds, result.earlierCount, result.earlierOtherTeams, s) ?: return
        val names = result.participants.associateWith { plugin.lifestealTeamManager.nameOf(it) }
        val now = System.currentTimeMillis()
        plugin.databaseManager.async("ranked rating-limit review ${result.matchId}") {
            try {
                if (store.record(finding, result.matchId, now, s.windowMillis)) report(finding, result.matchId, names)
            } catch (e: Exception) {
                plugin.logger.warning("[RankedMonitor] Couldn't log the rating limit of match ${result.matchId}: ${e.message} (nothing about the match changed).")
            }
        }
    }

    private fun report(f: RankedMonitorRules.Finding, matchId: String, names: Map<UUID, String>) {
        val who = f.players.joinToString(", ") { "${names[it] ?: "?"} ($it)" }
        plugin.logger.warning(
            "[RankedMonitor] Review: ${f.kind.label} in match $matchId: $who. ${f.reason}. Logged for staff review " +
                "(/lifesteal admin ranked reviews); nothing was blocked or changed."
        )
        try {
            val target = f.players.firstOrNull()
            plugin.adminManager.logAction(
                null, SYSTEM_ACTOR, "RANKED_REVIEW_${f.kind.name}", target, target?.let { names[it] },
                "match=$matchId players=${f.players.joinToString(",")} teams=${f.teams.joinToString(",")}${f.initiator?.let { " test=1 initiator=$it" } ?: ""} reason=${f.reason}".take(500),
            )
        } catch (e: Exception) {
            plugin.logger.warning("[RankedMonitor] Couldn't write the staff log for match $matchId: ${e.message}")
        }
    }

    /** `/lifesteal admin ranked reviews [player]`: newest first. */
    fun reviews(player: UUID?, limit: Int = 15): List<RankedMonitorStore.Review> = store.reviews(player, limit)

    companion object {
        /** Name the monitor's own `admin_logs` rows carry. */
        const val SYSTEM_ACTOR = "Lifesteal Ranked"
    }
}
