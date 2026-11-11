package com.liam.joshymc.lifesteal.hearts.monitor

import java.util.UUID

/**
 * Issue #1508 (audit finding C-2): monitoring of permanent heart transfers for possible
 * alternate-account heart farming. **Logging only**: nothing here can refuse, delay, reverse or
 * change a heart transfer, a cooldown, a rating or a player. Bukkit-free, so
 * `scripts/HeartTransferMonitorCheck.kt` runs it against real SQLite.
 *
 * `lifesteal.yml > heart-transfer-monitoring`, read separately from the rest of lifesteal.yml
 * (like the ranked monitoring), so a mistake here only turns monitoring off and never pauses
 * hearts. A missing key uses its default (a warning); a wrong value is severe and switches
 * monitoring off until it is fixed.
 */
data class HeartMonitorSettings(
    val enabled: Boolean = true,
    /** Hours every count looks back. */
    val windowHours: Int = 24,
    /** Signal A (and D): use AltManager's shared-connection evidence. */
    val sharedConnection: Boolean = true,
    /** Signal B: hearts moved between the same two players (either direction) inside the window, this one included; 0 = off. */
    val repeatedTransfers: Int = 4,
    /** Signal C: hearts one victim lost to one killer with none going back; only reported together with another signal; 0 = off. */
    val oneWayTransfers: Int = 3,
    /** Signal D: accounts with shared-connection evidence (with each other or the recipient) that gave one recipient hearts; 0 = off. */
    val associatedAccounts: Int = 3,
    /** Tell online staff with the monitor permission about new flags. */
    val notifyStaff: Boolean = true,
    /** At most one staff notification per pair of players in this many minutes (every flag is still recorded). */
    val notifyCooldownMinutes: Int = 60,
    /** Reviewed or dismissed flags older than this are deleted at startup/reload; open flags are kept. 0 = keep forever. */
    val retentionDays: Int = 180,
) {
    val windowMillis: Long get() = windowHours * 3_600_000L
    val notifyCooldownMillis: Long get() = notifyCooldownMinutes * 60_000L

    companion object {
        val DEFAULTS = HeartMonitorSettings()
    }
}

object HeartMonitorConfig {

    data class Problem(val severe: Boolean, val message: String)

    /** [settings] is null when monitoring is off because of a severe problem. */
    data class Result(val settings: HeartMonitorSettings?, val problems: List<Problem>)

    const val BASE = "heart-transfer-monitoring"

    /** [get] reads a key relative to [BASE] (`window-hours`), null when missing; `""` reads the section itself. */
    fun parse(get: (String) -> Any?): Result {
        val problems = mutableListOf<Problem>()
        val d = HeartMonitorSettings.DEFAULTS
        if (get("") == null) {
            problems += Problem(false, "$BASE is not set; using the default heart transfer monitoring settings.")
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
        val settings = HeartMonitorSettings(
            enabled = bool("enabled", d.enabled),
            windowHours = int("window-hours", d.windowHours, 1, 720, "hours the counts look back"),
            sharedConnection = bool("shared-connection", d.sharedConnection),
            repeatedTransfers = int("repeated-transfers", d.repeatedTransfers, 0, 1000, "hearts moved between the same two players; 0 = off"),
            oneWayTransfers = int("one-way-transfers", d.oneWayTransfers, 0, 1000, "hearts one victim lost to one killer, none back; 0 = off"),
            associatedAccounts = int("associated-accounts", d.associatedAccounts, 0, 100, "associated accounts giving one recipient hearts; 0 = off"),
            notifyStaff = bool("notify-staff", d.notifyStaff),
            notifyCooldownMinutes = int("notify-cooldown-minutes", d.notifyCooldownMinutes, 0, 10_080, "minutes between notifications about one pair"),
            retentionDays = int("retention-days", d.retentionDays, 0, 3650, "days reviewed flags are kept; 0 = forever"),
        )
        return Result(if (problems.any { it.severe }) null else settings, problems)
    }
}

object HeartMonitorRules {

    /** The four review signals. Never proof of abuse: households, schools and VPNs share connections. */
    enum class Signal(val label: String) {
        /** A: AltManager saw the killer and the victim on one (hashed) connection. */
        SHARED_CONNECTION("Shared connection"),
        /** B: the pair moved [HeartMonitorSettings.repeatedTransfers] hearts between them inside the window. */
        REPEATED_TRANSFERS("Repeated transfers"),
        /** C: the victim lost [HeartMonitorSettings.oneWayTransfers] hearts to the killer and got none back. Only reported with another signal. */
        ONE_WAY("One-way transfers"),
        /** D: several accounts with shared-connection evidence gave the killer hearts inside the window. */
        ASSOCIATED_ACCOUNTS("Associated accounts");

        companion object {
            fun parse(raw: String?): List<Signal> =
                raw.orEmpty().split(',').mapNotNull { code -> entries.firstOrNull { it.name == code.trim() } }
        }
    }

    /** Which heart path moved the heart. Display only. */
    enum class Source(val label: String) {
        KILL("PvP kill"),
        COMBAT_TAG("Combat-tag credit"),
        COMBAT_NPC("Combat NPC kill"),
        RANKED_KILL("Ranked kill"),
        RANKED_ENVIRONMENT("Ranked environmental credit"),
        RANKED_FORFEIT("Ranked disconnect forfeit");

        companion object {
            fun parse(raw: String?): Source? = entries.firstOrNull { it.name == raw }
        }
    }

    /**
     * One committed heart transfer (`HeartRules.Decision.Transfer`), as the heart store returned it.
     * [transactionId] is the heart transaction's own id and [dedupeKey] its dedupe key.
     */
    data class Transfer(
        val transactionId: String,
        val dedupeKey: String,
        val source: Source,
        val killer: UUID,
        val victim: UUID,
        val at: Long,
        val killerBefore: Int,
        val killerAfter: Int,
        val victimBefore: Int,
        val victimAfter: Int,
        val matchId: String? = null,
    ) {
        init { require(killer != victim) { "a transfer needs two players" } }
    }

    /** What the store counted inside the window ending at the transfer, this transfer included. */
    data class Counts(
        val sharedConnection: Boolean,
        /** Hearts the killer took from the victim. */
        val forward: Int,
        /** Hearts the victim took from the killer. */
        val reverse: Int,
        /** Distinct accounts that gave the killer hearts and share a connection with the killer or another of them. */
        val associatedAccounts: Int,
    )

    /** The signals [counts] raise under [settings], in a fixed order; empty = nothing to flag. */
    fun evaluate(counts: Counts, settings: HeartMonitorSettings): List<Signal> {
        if (!settings.enabled) return emptyList()
        val shared = settings.sharedConnection && counts.sharedConnection
        val repeated = settings.repeatedTransfers > 0 && counts.forward + counts.reverse >= settings.repeatedTransfers
        val associated = settings.sharedConnection && settings.associatedAccounts > 0 && counts.associatedAccounts >= settings.associatedAccounts
        // C on its own is ordinary for a strong player against a weaker one, so it only adds to another signal.
        val oneWay = (shared || repeated || associated) &&
            settings.oneWayTransfers > 0 && counts.forward >= settings.oneWayTransfers && counts.reverse == 0
        return buildList {
            if (shared) add(Signal.SHARED_CONNECTION)
            if (repeated) add(Signal.REPEATED_TRANSFERS)
            if (oneWay) add(Signal.ONE_WAY)
            if (associated) add(Signal.ASSOCIATED_ACCOUNTS)
        }
    }

    /** "Shared connection + Repeated transfers". */
    fun reason(signals: List<Signal>): String = signals.joinToString(" + ") { it.label }

    /** Signals that rely on connection evidence, shown only to staff with the monitor permission. */
    fun usesConnectionEvidence(signal: Signal): Boolean = signal == Signal.SHARED_CONNECTION || signal == Signal.ASSOCIATED_ACCOUNTS

    /** The match id inside a ranked forfeit dedupe key (`rankedforfeit:<match>:<victim>`), or null. */
    fun forfeitMatchId(dedupeKey: String): String? =
        dedupeKey.takeIf { it.startsWith("rankedforfeit:") }?.removePrefix("rankedforfeit:")?.substringBeforeLast(':', "")?.takeIf { it.isNotEmpty() }

    /** The same key for (a, b) and (b, a). */
    fun pairKey(a: UUID, b: UUID): String {
        val x = a.toString()
        val y = b.toString()
        return if (x <= y) "$x:$y" else "$y:$x"
    }
}
