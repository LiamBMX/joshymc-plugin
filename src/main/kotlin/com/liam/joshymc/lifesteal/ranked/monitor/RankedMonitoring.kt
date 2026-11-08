package com.liam.joshymc.lifesteal.ranked.monitor

import java.util.UUID

/**
 * Issue #1474: anti-farming monitoring for Ranked Team Wars. **Logging only**: nothing here can
 * refuse a match, change a heart decision, touch a rating or punish anyone. Bukkit-free.
 *
 * `lifesteal.yml > ranked-team-wars.monitoring`, read separately from the rest of lifesteal.yml
 * (like the bank and bounties), so a mistake here only turns monitoring off and never pauses
 * hearts or ranked matches. A missing key uses its default (a warning); a wrong value is severe
 * and switches monitoring off until it is fixed.
 */
data class RankedMonitorSettings(
    val enabled: Boolean = true,
    /** Hours every count looks back (and how long a repeated finding about the same subject is suppressed). */
    val windowHours: Int = 24,
    /** Matches between the same two teams (any order) inside the window, this one included; 0 = off. */
    val repeatedTeamMatches: Int = 4,
    /** Matches in which the same two players were on opposite sides inside the window, whatever their teams; 0 = off. */
    val repeatedPlayerMatches: Int = 6,
    /** Hearts the same killer took from the same victim inside the window (ranked and open world together); 0 = off. */
    val repeatedHeartTransfers: Int = 3,
    /** Ranked disconnect forfeits by one player inside the window; 0 = off. */
    val repeatedForfeits: Int = 3,
    /** Opponents AltManager saw on one connection (never proof of one owner: households share one). */
    val sharedConnections: Boolean = true,
) {
    val windowMillis: Long get() = windowHours * 3_600_000L

    companion object {
        val DEFAULTS = RankedMonitorSettings()
    }
}

object RankedMonitorConfig {

    data class Problem(val severe: Boolean, val message: String)

    /** [settings] is null when monitoring is off because of a severe problem. */
    data class Result(val settings: RankedMonitorSettings?, val problems: List<Problem>)

    const val BASE = "ranked-team-wars.monitoring"

    /** [get] reads a key relative to [BASE] (`window-hours`), null when missing; `""` reads the section itself. */
    fun parse(get: (String) -> Any?): Result {
        val problems = mutableListOf<Problem>()
        val d = RankedMonitorSettings.DEFAULTS
        if (get("") == null) {
            problems += Problem(false, "$BASE is not set; using the default monitoring settings.")
            return Result(d, problems)
        }
        fun bool(key: String, default: Boolean): Boolean = when (val raw = get(key)) {
            null -> { problems += Problem(false, "$BASE.$key is not set; using the default $default."); default }
            is Boolean -> raw
            else -> { problems += Problem(true, "$BASE.$key '$raw' must be true or false."); default }
        }
        fun int(key: String, default: Int, min: Int, max: Int, meaning: String): Int {
            val raw = get(key) ?: run { problems += Problem(false, "$BASE.$key is not set; using the default $default."); return default }
            val value = when (raw) {
                is Int -> raw.toLong()
                is Long -> raw
                is Number -> raw.toDouble().takeIf { it == Math.floor(it) && !it.isInfinite() }?.toLong()
                is String -> raw.trim().toLongOrNull()
                else -> null
            }
            if (value == null) { problems += Problem(true, "$BASE.$key '$raw' is not a whole number ($meaning)."); return default }
            if (value < min || value > max) { problems += Problem(true, "$BASE.$key is $value; it must be between $min and $max ($meaning)."); return default }
            return value.toInt()
        }
        val settings = RankedMonitorSettings(
            enabled = bool("enabled", d.enabled),
            windowHours = int("window-hours", d.windowHours, 1, 720, "hours the counts look back"),
            repeatedTeamMatches = int("repeated-team-matches", d.repeatedTeamMatches, 0, 1000, "matches between the same two teams; 0 = off"),
            repeatedPlayerMatches = int("repeated-player-matches", d.repeatedPlayerMatches, 0, 1000, "matches between the same two opponents; 0 = off"),
            repeatedHeartTransfers = int("repeated-heart-transfers", d.repeatedHeartTransfers, 0, 1000, "hearts one killer took from one victim; 0 = off"),
            repeatedForfeits = int("repeated-forfeits", d.repeatedForfeits, 0, 1000, "disconnect forfeits by one player; 0 = off"),
            sharedConnections = bool("shared-connections", d.sharedConnections),
        )
        return Result(if (problems.any { it.severe }) null else settings, problems)
    }
}

object RankedMonitorRules {

    enum class Kind(val label: String) {
        REPEATED_TEAM_MATCHUP("the same two teams keep meeting"),
        REPEATED_PLAYER_MATCHUP("the same two players keep meeting as opponents"),
        REPEATED_HEART_TRANSFERS("the same killer keeps taking hearts from the same victim"),
        SHARED_CONNECTION("opponents were seen on one connection"),
        REPEATED_FORFEITS("a player keeps forfeiting by disconnecting"),
    }

    /** What one finished match looked like (taken on the main thread when it ended). */
    data class MatchFacts(
        val matchId: String,
        val teamAId: String,
        val teamAName: String,
        val teamBId: String,
        val teamBName: String,
        val sideA: List<UUID>,
        val sideB: List<UUID>,
        /** Players who forfeited this match by a counted disconnect. */
        val forfeited: List<UUID>,
        /** Killer → victim pairs whose heart decision in this match actually moved a heart. */
        val transfers: List<Pair<UUID, UUID>>,
    )

    /** Counts read from existing history inside the window (this match included). */
    data class Counts(
        val teamMatches: Int = 0,
        /** By [pairKey] of two opponents. */
        val playerPairMatches: Map<String, Int> = emptyMap(),
        /** By "killer>victim". */
        val pairTransfers: Map<String, Int> = emptyMap(),
        val forfeits: Map<UUID, Int> = emptyMap(),
        /** [pairKey]s of opponents AltManager saw on one connection. */
        val sharedPairs: Set<String> = emptySet(),
    )

    data class Finding(
        val kind: Kind,
        /** What the finding is about, stable across matches (team pair, player pair, directed pair, player). */
        val subject: String,
        val players: List<UUID>,
        val teams: List<String>,
        val reason: String,
    ) {
        /** One row per finding per match: re-processing the same match never adds another. */
        fun dedupeKey(matchId: String): String = "${kind.name}:$subject:$matchId"
    }

    /** Order-independent key of two ids. */
    fun pairKey(a: String, b: String): String = if (a <= b) "$a|$b" else "$b|$a"
    fun pairKey(a: UUID, b: UUID): String = pairKey(a.toString(), b.toString())
    fun directedKey(killer: UUID, victim: UUID): String = "$killer>$victim"

    /** Every opponent pair of the match (A side × B side), at most 25. */
    fun opponentPairs(facts: MatchFacts): List<Pair<UUID, UUID>> =
        facts.sideA.flatMap { a -> facts.sideB.filter { it != a }.map { b -> a to b } }

    /** The findings for [facts], given [counts]; empty when monitoring is off or nothing is unusual. */
    fun evaluate(facts: MatchFacts, counts: Counts, settings: RankedMonitorSettings?): List<Finding> {
        if (settings == null || !settings.enabled) return emptyList()
        val hours = settings.windowHours
        val out = mutableListOf<Finding>()
        val teams = listOf(facts.teamAId, facts.teamBId)
        if (settings.repeatedTeamMatches > 0 && counts.teamMatches >= settings.repeatedTeamMatches) {
            out += Finding(
                Kind.REPEATED_TEAM_MATCHUP, pairKey(facts.teamAId, facts.teamBId), facts.sideA + facts.sideB, teams,
                "${facts.teamAName} and ${facts.teamBName} played ${counts.teamMatches} ranked matches against each other in ${hours}h (threshold ${settings.repeatedTeamMatches})",
            )
        }
        for ((a, b) in opponentPairs(facts)) {
            val key = pairKey(a, b)
            val n = counts.playerPairMatches[key] ?: 0
            if (settings.repeatedPlayerMatches > 0 && n >= settings.repeatedPlayerMatches) {
                out += Finding(Kind.REPEATED_PLAYER_MATCHUP, key, listOf(a, b), teams,
                    "these two players were opponents in $n ranked matches in ${hours}h, across any teams (threshold ${settings.repeatedPlayerMatches})")
            }
            if (settings.sharedConnections && key in counts.sharedPairs) {
                val moved = facts.transfers.any { (k, v) -> (k == a && v == b) || (k == b && v == a) }
                out += Finding(Kind.SHARED_CONNECTION, key, listOf(a, b), teams,
                    "opponents AltManager has seen on one connection (not proof of one owner)${if (moved) "; a heart moved between them in this match" else ""}")
            }
        }
        if (settings.repeatedHeartTransfers > 0) {
            for ((killer, victim) in facts.transfers.distinct()) {
                val n = counts.pairTransfers[directedKey(killer, victim)] ?: 0
                if (n >= settings.repeatedHeartTransfers) {
                    out += Finding(Kind.REPEATED_HEART_TRANSFERS, directedKey(killer, victim), listOf(killer, victim), teams,
                        "this killer took $n hearts from this victim in ${hours}h, ranked and open world together (threshold ${settings.repeatedHeartTransfers})")
                }
            }
        }
        if (settings.repeatedForfeits > 0) {
            for (uuid in facts.forfeited.distinct()) {
                val n = counts.forfeits[uuid] ?: 0
                if (n >= settings.repeatedForfeits) {
                    out += Finding(Kind.REPEATED_FORFEITS, uuid.toString(), listOf(uuid), teams,
                        "this player forfeited $n ranked matches by disconnecting in ${hours}h (threshold ${settings.repeatedForfeits})")
                }
            }
        }
        return out
    }
}
