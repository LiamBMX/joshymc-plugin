package com.liam.joshymc.manager

/**
 * When the internal anticheat skips its flight check (issue #1521, audit #1505 P1-1).
 *
 * Only server-side flight state counts: `allowFlight` / `isFlying` (set by the server through
 * `/fly`, Fly Voucher flight, Creative/Spectator, ModMode, spawn flight, claim-flight talismans,
 * and cleared again by FlightTimeManager when timed flight runs out), elytra gliding, and the
 * explicit staff node [BYPASS_PERMISSION]. Merely holding `joshymc.fly` (permission to *turn on*
 * `/fly`, which donor ranks and Fly Vouchers grant) is deliberately not an input: a player who
 * has the permission but hasn't enabled flight is checked like everyone else.
 *
 * Bukkit-free.
 */
object FlightCheckRules {

    /** Explicit anticheat fly bypass (op by default). Never a donor node. */
    const val BYPASS_PERMISSION = "joshymc.anticheat.fly.bypass"

    data class State(
        val allowFlight: Boolean,
        val flying: Boolean,
        val gliding: Boolean,
        val explicitBypass: Boolean,
    )

    fun exempt(state: State): Boolean =
        state.allowFlight || state.flying || state.gliding || state.explicitBypass
}
