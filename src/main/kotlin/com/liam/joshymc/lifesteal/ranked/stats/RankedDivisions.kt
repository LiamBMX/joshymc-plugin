package com.liam.joshymc.lifesteal.ranked.stats

import com.liam.joshymc.lifesteal.ranked.rating.RatingType

/**
 * Competitive divisions for Ranked Team Wars (issue #1367), Bukkit-free.
 *
 * Blueprint v1.1 names no divisions and no thresholds, so the table comes only from
 * `lifesteal.yml > ranked-team-wars.divisions` (the bundled file holds the issue's proposed
 * defaults, Bronze to Grandmaster; nothing here hard-codes a threshold). A division is a
 * label computed from the authoritative rating every time it is shown: nothing stores a division,
 * so changing thresholds never touches a rating and never needs a migration.
 *
 * Player Elo and Team Elo are classified independently: every division has a player minimum and a
 * team minimum (the team one defaults to the player one), and a Team Elo is only ever compared with
 * team minimums.
 */
data class Division(
    val id: String,
    val displayName: String,
    /** `#RRGGBB`, or an Adventure named colour (`gold`, `aqua`, ...). Validated by [DivisionConfig]. */
    val color: String,
    val playerMinimum: Int,
    val teamMinimum: Int,
    /** A Bukkit `Material` name, or null for the menu's default icon. */
    val icon: String?,
    val description: String,
) {
    fun minimum(type: RatingType): Int = if (type == RatingType.TEAM_ELO) teamMinimum else playerMinimum
}

/** Divisions lowest first; both minimum columns strictly increase (validated by [DivisionConfig]). */
class DivisionTable(val divisions: List<Division>) {

    init {
        require(divisions.isNotEmpty()) { "a division table needs at least one division" }
        for (type in RatingType.entries) {
            require(divisions.zipWithNext().all { (a, b) -> a.minimum(type) < b.minimum(type) }) { "division minimums must strictly increase" }
        }
    }

    /**
     * The division of [rating]: the highest one whose minimum is at most [rating]. A rating below
     * the lowest minimum (only possible when the Elo minimum was lowered after validation) is still
     * the lowest division, never "no division".
     */
    fun of(type: RatingType, rating: Int): Division = divisions.lastOrNull { it.minimum(type) <= rating } ?: divisions.first()

    fun next(type: RatingType, rating: Int): Division? = divisions.firstOrNull { it.minimum(type) > rating }

    fun indexOf(division: Division): Int = divisions.indexOfFirst { it.id == division.id }

    /** Progress toward the next division; [next] is null in the highest division. */
    data class Progress(val current: Division, val next: Division?, val rating: Int, val required: Int?, val remaining: Int?)

    fun progress(type: RatingType, rating: Int): Progress {
        val current = of(type, rating)
        val next = next(type, rating)
        val required = next?.minimum(type)
        return Progress(current, next, rating, required, required?.let { (it - rating).coerceAtLeast(0) })
    }

    sealed interface Change {
        val from: Division
        val to: Division
        data class Promotion(override val from: Division, override val to: Division) : Change
        data class Demotion(override val from: Division, override val to: Division) : Change
    }

    /** The division change of a committed rating change [before] -> [after], or null when it stays. */
    fun change(type: RatingType, before: Int, after: Int): Change? {
        val from = of(type, before)
        val to = of(type, after)
        if (from.id == to.id) return null
        return if (indexOf(to) > indexOf(from)) Change.Promotion(from, to) else Change.Demotion(from, to)
    }
}

/**
 * Reads `ranked-team-wars.divisions` and `ranked-team-wars.leaderboards` (issue #1367),
 * Bukkit-free. Unlike the rest of `lifesteal.yml`, a mistake here never pauses Lifesteal: it only
 * makes divisions (or leaderboards) unavailable, with the problem logged. Nothing is guessed: an
 * invalid table is never partially used.
 */
object DivisionConfig {

    /** Two rows of seven in the divisions menu ([RankedMenuLayout.divisionSlots]). */
    const val MAX_DIVISIONS = 14
    const val MAX_NAME_LENGTH = 24
    const val MAX_DESCRIPTION_LENGTH = 80
    /** Same ceiling as every Elo setting (`LifestealConfigParser.MAX_ELO_RATING`). */
    const val MAX_THRESHOLD = 100_000

    private val ID = Regex("[a-z0-9_-]{1,32}")
    private val HEX = Regex("#[0-9a-fA-F]{6}")
    private val MATERIAL = Regex("[A-Za-z0-9_]{1,64}")
    /** Adventure's named colours (kept here so the parser doesn't need Adventure). */
    val NAMED_COLORS = setOf(
        "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
        "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
    )

    data class Problem(val severe: Boolean, val message: String)

    enum class Status(val label: String) {
        READY("ready"),
        DISABLED("disabled in lifesteal.yml"),
        INVALID("invalid, see the console"),
    }

    data class Result(val table: DivisionTable?, val status: Status, val problems: List<Problem>)

    /**
     * [get] returns the value at a dotted path (null = missing), [keys] the child keys of a section
     * in file order (null = not a section). [eloMinimum] is the configured `minimum-rating`: the
     * lowest division must start at or below it, so every possible rating has a division.
     */
    fun parse(get: (String) -> Any?, keys: (String) -> List<String>?, eloMinimum: Int, base: String = "ranked-team-wars.divisions"): Result {
        val problems = mutableListOf<Problem>()
        if (get(base) == null) return Result(null, Status.INVALID, listOf(Problem(true, "$base is not set.")))
        when (val enabled = get("$base.enabled")) {
            null -> problems += Problem(false, "$base.enabled is not set; divisions are shown.")
            false -> return Result(null, Status.DISABLED, problems)
            true -> Unit
            else -> return Result(null, Status.INVALID, problems + Problem(true, "$base.enabled '$enabled' must be true or false."))
        }
        val ids = keys("$base.tiers")
        if (ids.isNullOrEmpty()) return Result(null, Status.INVALID, problems + Problem(true, "$base.tiers lists no division."))
        if (ids.size > MAX_DIVISIONS) return Result(null, Status.INVALID, problems + Problem(true, "$base.tiers lists ${ids.size} divisions; at most $MAX_DIVISIONS."))

        val divisions = mutableListOf<Division>()
        val seenIds = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        for (id in ids) {
            val path = "$base.tiers.$id"
            if (!ID.matches(id)) { problems += Problem(true, "$path: the id must be lower-case letters, digits, '_' or '-'."); continue }
            if (!seenIds.add(id)) { problems += Problem(true, "$path is listed twice."); continue }
            val name = (get("$path.display-name") as? String)?.trim()
            when {
                name.isNullOrEmpty() -> { problems += Problem(true, "$path.display-name is missing."); continue }
                name.length > MAX_NAME_LENGTH -> { problems += Problem(true, "$path.display-name is longer than $MAX_NAME_LENGTH characters."); continue }
                name.contains('&') || name.contains('§') -> { problems += Problem(true, "$path.display-name must be plain text (set the colour with $path.color)."); continue }
                !seenNames.add(name.lowercase()) -> { problems += Problem(true, "$path.display-name '$name' is used by another division."); continue }
            }
            val color = (get("$path.color") as? String)?.trim()?.lowercase()
            if (color == null || !(HEX.matches(color) || color in NAMED_COLORS)) {
                problems += Problem(true, "$path.color '${get("$path.color")}' must be #RRGGBB or a colour name (${NAMED_COLORS.joinToString()}).")
                continue
            }
            val playerMin = threshold(get("$path.minimum-elo"), "$path.minimum-elo", problems) ?: continue
            val teamRaw = get("$path.team-minimum-elo")
            val teamMin = if (teamRaw == null) playerMin else threshold(teamRaw, "$path.team-minimum-elo", problems) ?: continue
            val icon = when (val raw = get("$path.icon")) {
                null -> null
                is String -> raw.trim().takeIf { it.isNotEmpty() }?.also { if (!MATERIAL.matches(it)) problems += Problem(true, "$path.icon '$it' is not an item name.") }
                else -> { problems += Problem(true, "$path.icon must be an item name."); null }
            }
            val description = (get("$path.description") as? String)?.trim().orEmpty()
            if (description.length > MAX_DESCRIPTION_LENGTH) { problems += Problem(true, "$path.description is longer than $MAX_DESCRIPTION_LENGTH characters."); continue }
            if (description.contains('&') || description.contains('§')) { problems += Problem(true, "$path.description must be plain text."); continue }
            divisions += Division(id, name, color, playerMin, teamMin, icon, description)
        }
        if (problems.any { it.severe }) return Result(null, Status.INVALID, problems)

        // Lowest first by the player minimum; the team minimums must follow the same order, or the
        // two classifications would disagree about which division is higher.
        val sorted = divisions.sortedBy { it.playerMinimum }
        for ((a, b) in sorted.zipWithNext()) {
            if (a.playerMinimum == b.playerMinimum) problems += Problem(true, "${a.id} and ${b.id} have the same minimum-elo (${a.playerMinimum}); every division needs its own threshold.")
            else if (a.teamMinimum >= b.teamMinimum) problems += Problem(true, "team-minimum-elo of ${b.id} (${b.teamMinimum}) must be above the one of ${a.id} (${a.teamMinimum}), the division below it.")
        }
        if (sorted.isNotEmpty()) {
            val lowest = sorted.first()
            if (lowest.playerMinimum > eloMinimum) problems += Problem(true, "the lowest division (${lowest.id}) starts at ${lowest.playerMinimum}, above the lowest possible Elo ($eloMinimum); set its minimum-elo to $eloMinimum or less.")
            if (lowest.teamMinimum > eloMinimum) problems += Problem(true, "the lowest division (${lowest.id}) has team-minimum-elo ${lowest.teamMinimum}, above the lowest possible Elo ($eloMinimum).")
        }
        if (ids != sorted.map { it.id } && problems.none { it.severe }) problems += Problem(false, "$base.tiers are not listed lowest first; they are used in threshold order.")
        if (problems.any { it.severe }) return Result(null, Status.INVALID, problems)
        return Result(DivisionTable(sorted), Status.READY, problems)
    }

    private fun threshold(raw: Any?, path: String, problems: MutableList<Problem>): Int? {
        val value = when (raw) {
            null -> { problems += Problem(true, "$path is missing."); return null }
            is Int -> raw.toLong()
            is Long -> raw
            is Short -> raw.toLong()
            is Byte -> raw.toLong()
            else -> { problems += Problem(true, "$path '$raw' is not a whole number."); return null }
        }
        if (value < 0 || value > MAX_THRESHOLD) { problems += Problem(true, "$path is $value; it must be between 0 and $MAX_THRESHOLD."); return null }
        return value.toInt()
    }

    // ── Leaderboards ────────────────────────────────────────

    /**
     * `ranked-team-wars.leaderboards`: who is listed (completed rated matches this season) and how
     * long a read page may be reused. Ratings below the minimum are never hidden or changed; those
     * players just aren't listed yet.
     */
    data class Leaderboards(val minimumPlayerMatches: Int = 5, val minimumTeamMatches: Int = 5, val cacheSeconds: Int = 30)

    data class LeaderboardResult(val settings: Leaderboards?, val problems: List<Problem>)

    const val MAX_MINIMUM_MATCHES = 1000
    const val MAX_CACHE_SECONDS = 600

    fun parseLeaderboards(get: (String) -> Any?, base: String = "ranked-team-wars.leaderboards"): LeaderboardResult {
        val problems = mutableListOf<Problem>()
        val d = Leaderboards()
        if (get(base) == null) return LeaderboardResult(null, listOf(Problem(true, "$base is not set.")))
        fun int(key: String, default: Int, min: Int, max: Int): Int {
            val path = "$base.$key"
            val raw = get(path) ?: run { problems += Problem(false, "$path is not set; using $default."); return default }
            val v = (raw as? Number)?.takeIf { it is Int || it is Long || it is Short || it is Byte }?.toLong()
            if (v == null || v < min || v > max) { problems += Problem(true, "$path '$raw' must be a whole number from $min to $max."); return default }
            return v.toInt()
        }
        val players = int("minimum-player-matches", d.minimumPlayerMatches, 0, MAX_MINIMUM_MATCHES)
        val teams = int("minimum-team-matches", d.minimumTeamMatches, 0, MAX_MINIMUM_MATCHES)
        val cache = int("cache-seconds", d.cacheSeconds, 0, MAX_CACHE_SECONDS)
        if (problems.any { it.severe }) return LeaderboardResult(null, problems)
        return LeaderboardResult(Leaderboards(players, teams, cache), problems)
    }
}
