package com.liam.joshymc.crate

import java.util.UUID

/**
 * One crate action per player, per click kind, per server tick (issue #1451), Bukkit-free.
 *
 * A crate can be clicked through its block or, for oversized models, through the hitbox over
 * it. One physical click only ever reaches one of them, but if both ever report the same click
 * in the same tick (or an event fires twice), only the first runs. Keyed opens keep the crate
 * manager's own activation debounce on top of this, so a key is never spent twice.
 */
class CrateClickGate {

    /** player -> last tick a left click ran, and the last tick a right click ran. */
    private val last = HashMap<UUID, LongArray>()

    /** True (and recorded) when this is [player]'s first [leftClick] crate action in [tick]. */
    fun tryEnter(player: UUID, tick: Int, leftClick: Boolean): Boolean {
        val slots = last.getOrPut(player) { longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE) }
        val i = if (leftClick) 0 else 1
        if (slots[i] == tick.toLong()) return false
        slots[i] = tick.toLong()
        return true
    }

    /** True when [player] already ran a [leftClick] crate action in [tick]. */
    fun ranIn(player: UUID, tick: Int, leftClick: Boolean): Boolean =
        last[player]?.get(if (leftClick) 0 else 1) == tick.toLong()

    fun forget(player: UUID) {
        last.remove(player)
    }

    val size: Int get() = last.size
}
