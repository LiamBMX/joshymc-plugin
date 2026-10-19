package com.liam.joshymc.manager

import java.util.Locale
import java.util.UUID

/**
 * Repeated-message detector behind the automatic spam mute (issue #1268). Bukkit-free:
 * callers pass the current server tick, so the window counts ticks, not wall time.
 *
 * Each player keeps only the messages from the last [Settings.windowTicks] ticks (and at
 * most [MAX_ENTRIES] of them). A message triggers when it and the earlier copies of the
 * same normalized text inside that rolling window number [Settings.repeatThreshold]. A
 * trigger clears that player's history, so one burst can only trigger once.
 *
 * Thread-safe: chat events run off the main thread.
 */
class ChatSpamDetector(private val settings: Settings) {

    data class Settings(
        val enabled: Boolean = true,
        val repeatThreshold: Int = DEFAULT_THRESHOLD,
        val windowTicks: Int = DEFAULT_WINDOW_TICKS,
        val muteDurationSeconds: Long = DEFAULT_MUTE_SECONDS,
        val muteReason: String = DEFAULT_REASON
    ) {
        val muteDurationMs: Long get() = muteDurationSeconds * 1000L
    }

    private class Entry(val text: String, val tick: Int)

    private val history = HashMap<UUID, ArrayDeque<Entry>>()

    /**
     * Records [message] for [player] at [tick]. Returns true when this message is the one that
     * reaches the threshold (the caller blocks it and mutes the player).
     */
    fun record(player: UUID, message: String, tick: Int): Boolean {
        val text = normalize(message)
        if (text.isEmpty()) return false
        synchronized(history) {
            val entries = history.getOrPut(player) { ArrayDeque() }
            // Rolling window: keep messages sent fewer than windowTicks ticks ago.
            while (entries.isNotEmpty() && tick - entries.first().tick >= settings.windowTicks) entries.removeFirst()
            entries.addLast(Entry(text, tick))
            while (entries.size > MAX_ENTRIES) entries.removeFirst()

            val matches = entries.count { it.text == text }
            if (matches >= settings.repeatThreshold) {
                history.remove(player)
                return true
            }
            return false
        }
    }

    fun forget(player: UUID) {
        synchronized(history) { history.remove(player) }
    }

    companion object {
        const val DEFAULT_THRESHOLD = 6
        const val DEFAULT_WINDOW_TICKS = 30
        const val DEFAULT_MUTE_SECONDS = 3600L
        const val DEFAULT_REASON = "Spam"

        /** Messages kept per player; only reachable by a flood far faster than any threshold needs. */
        private const val MAX_ENTRIES = 64

        private val WHITESPACE = Regex("\\s+")

        /** Case-insensitive, trimmed, internal whitespace collapsed. Nothing fuzzier. */
        fun normalize(message: String): String =
            message.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)

        /**
         * Builds settings from raw config values, falling back to the default for each invalid one.
         * [warn] receives one line per value it replaced.
         */
        fun settingsOf(
            enabled: Boolean,
            repeatThreshold: Int,
            windowTicks: Int,
            muteDurationSeconds: Long,
            muteReason: String?,
            warn: (String) -> Unit
        ): Settings {
            val threshold = if (repeatThreshold in 2..MAX_ENTRIES) repeatThreshold else {
                warn("repeat-threshold must be between 2 and $MAX_ENTRIES (got $repeatThreshold), using $DEFAULT_THRESHOLD")
                DEFAULT_THRESHOLD
            }
            val window = if (windowTicks >= 1) windowTicks else {
                warn("window-ticks must be at least 1 (got $windowTicks), using $DEFAULT_WINDOW_TICKS")
                DEFAULT_WINDOW_TICKS
            }
            val duration = if (muteDurationSeconds in 1..MAX_MUTE_SECONDS) muteDurationSeconds else {
                warn("mute-duration-seconds must be between 1 and $MAX_MUTE_SECONDS (got $muteDurationSeconds), using $DEFAULT_MUTE_SECONDS")
                DEFAULT_MUTE_SECONDS
            }
            val reason = muteReason?.trim()?.takeIf { it.isNotEmpty() } ?: run {
                warn("mute-reason is blank, using \"$DEFAULT_REASON\"")
                DEFAULT_REASON
            }
            return Settings(enabled, threshold, window, duration, reason)
        }

        /** One year: auto-mutes are meant to be short; anything longer is a config typo. */
        private const val MAX_MUTE_SECONDS = 365L * 86_400L
    }
}
