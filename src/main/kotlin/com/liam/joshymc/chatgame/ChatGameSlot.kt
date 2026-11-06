package com.liam.joshymc.chatgame

import java.util.Random
import java.util.concurrent.atomic.AtomicReference

/**
 * The one running chat game (Bukkit-free, issue #1395). Chat answers arrive on
 * the async chat threads while the scheduler starts and expires games on the
 * main thread, so every transition is a compare-and-set on the same reference:
 * only one game runs at a time, and a round ends exactly once, by one winner,
 * its timeout or a cancel, whichever comes first.
 */
class ChatGameSlot<G : Any> {

    private val current = AtomicReference<G?>(null)

    fun active(): G? = current.get()

    /** Starts [game]; false when another game is already running. */
    fun tryStart(game: G): Boolean = current.compareAndSet(null, game)

    /**
     * Ends [game] for the caller if it is still the running one. True for
     * exactly one caller per game, so the winner (or the timeout) is processed
     * once even when several correct answers arrive at the same moment.
     */
    fun end(game: G): Boolean = current.compareAndSet(game, null)

    /** Ends whatever game is running (plugin stop); its later timeout does nothing. */
    fun cancel(): G? = current.getAndSet(null)
}

/** How the next chat game type is chosen (Bukkit-free, issue #1395). */
object ChatGameRotation {

    /**
     * A uniformly random type from [available]. When the last game was one of
     * [noRepeat] and another type is available, that type sits this one out,
     * so Unscramble never runs twice in a row while other games exist; every
     * other type is chosen exactly as before.
     */
    fun <T> choose(available: List<T>, last: T?, noRepeat: Set<T>, random: Random): T? {
        if (available.isEmpty()) return null
        val pool = if (last != null && last in noRepeat && available.any { it != last }) {
            available.filter { it != last }
        } else {
            available
        }
        return pool[random.nextInt(pool.size)]
    }
}
