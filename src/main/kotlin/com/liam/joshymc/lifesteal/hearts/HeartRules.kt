package com.liam.joshymc.lifesteal.hearts

import com.liam.joshymc.lifesteal.LifestealSettings
import java.util.UUID

/**
 * Lifesteal heart rules (issue #1341), Bukkit-free. [LifestealHeartManager] feeds them live data,
 * [HeartStore] applies their decisions inside one database transaction, and
 * `scripts/LifestealHeartCheck.kt` tests them without a server.
 *
 * Hearts are whole hearts; 1 heart = 2 health points ([healthFor]).
 */
object HeartRules {

    /** Health points per heart. */
    const val HEALTH_PER_HEART = 2.0

    /** Hearts moved by one PvP kill. */
    const val HEARTS_PER_KILL = 1

    /**
     * What happens when the killer already has the maximum. The approved blueprint v1.1 doesn't
     * say (issue #1341, section 6, an open gameplay decision), so the conservative choice is
     * fixed here in code, not in lifesteal.yml: [KillerAtMaximumPolicy.NO_TRANSFER], nobody's
     * hearts change. It never drops a heart item or gives any other reward.
     */
    val KILLER_AT_MAXIMUM: KillerAtMaximumPolicy = KillerAtMaximumPolicy.NO_TRANSFER

    fun healthFor(hearts: Int): Double = hearts * HEALTH_PER_HEART

    // ── Limits ──────────────────────────────────────────────

    /** The configured limits. Every heart value the plugin applies or writes is inside them. */
    data class Limits(val starting: Int, val minimum: Int, val maximum: Int) {
        init {
            require(minimum >= 1) { "hearts.minimum must be at least 1" }
            require(minimum <= starting && starting <= maximum) { "hearts must satisfy minimum <= starting <= maximum" }
        }

        fun clamp(hearts: Int): Int = hearts.coerceIn(minimum, maximum)

        companion object {
            fun of(hearts: LifestealSettings.Hearts) = Limits(hearts.starting, hearts.minimum, hearts.maximum)
        }
    }

    // ── Kill attribution ────────────────────────────────────

    /**
     * Who the game says caused the death. [causingPlayer] is the damage source's causing entity
     * when that is a player (the attacker for melee, the shooter for projectiles), else null.
     */
    data class Death(
        val victim: UUID,
        val victimInLifesteal: Boolean,
        val victimLoaded: Boolean,
        val causingPlayer: UUID?,
        val killerOnline: Boolean,
        val killerInLifesteal: Boolean,
        val killerLoaded: Boolean,
        val killerIsNpc: Boolean,
    )

    enum class Attribution {
        /** A Lifesteal player killed another Lifesteal player: evaluate a heart transfer. */
        PVP_KILL,
        /** The victim isn't playing Lifesteal: nothing happens. */
        NOT_LIFESTEAL,
        /** No player caused the final damage (fall, lava, void, mobs, ...): vanilla death, hearts kept. */
        ENVIRONMENT,
        /** The victim killed themselves (own arrow, own TNT, ...). */
        SELF,
        /** The causing player isn't a real, online Lifesteal player (NPC, left, another gamemode). */
        INVALID_KILLER,
        /** One of the two has no loaded heart record yet, so nothing can be transferred safely. */
        NOT_LOADED,
    }

    /**
     * Only the final damage counts: a death whose last damage had no causing player is
     * [Attribution.ENVIRONMENT], even right after a fight. The blueprint doesn't define kill credit
     * for environmental deaths after PvP (knocked into lava or the void), so none is invented.
     */
    fun attribute(death: Death): Attribution = when {
        !death.victimInLifesteal -> Attribution.NOT_LIFESTEAL
        death.causingPlayer == null -> Attribution.ENVIRONMENT
        death.causingPlayer == death.victim -> Attribution.SELF
        death.killerIsNpc || !death.killerOnline || !death.killerInLifesteal -> Attribution.INVALID_KILLER
        !death.victimLoaded || !death.killerLoaded -> Attribution.NOT_LOADED
        else -> Attribution.PVP_KILL
    }

    // ── Transfers ───────────────────────────────────────────

    enum class KillerAtMaximumPolicy {
        /** Nobody's hearts change and no cooldown starts (the fixed choice, see [KILLER_AT_MAXIMUM]). */
        NO_TRANSFER,
        /**
         * Kept only as the documented alternative for when the blueprint decides it: the victim
         * still loses a heart, the killer stays at the maximum. Not used.
         */
        VICTIM_LOSES_ONLY,
    }

    sealed interface Decision {
        /** Victim loses a heart, killer gains it. */
        data class Transfer(val victimAfter: Int, val killerAfter: Int) : Decision
        /** Killer at the maximum with [KillerAtMaximumPolicy.VICTIM_LOSES_ONLY]. */
        data class VictimLosesOnly(val victimAfter: Int) : Decision
        /** The victim is at the minimum (their last heart): nothing changes, nothing drops. */
        data object VictimAtMinimum : Decision
        /** Killer at the maximum with [KillerAtMaximumPolicy.NO_TRANSFER]. */
        data object KillerAtMaximum : Decision
        /** This killer took a heart from this victim too recently. */
        data class Cooldown(val remainingMillis: Long) : Decision
    }

    val Decision.changesHearts: Boolean get() = this is Decision.Transfer || this is Decision.VictimLosesOnly

    /**
     * Fixed order: the victim's last heart first (it is never at risk), then the anti-farming
     * cooldown, then the killer's maximum. [victimHearts] and [killerHearts] are the stored values;
     * they are clamped into [limits] first, so a lowered maximum never lets anyone exceed it.
     */
    fun decide(
        victimHearts: Int,
        killerHearts: Int,
        cooldownRemainingMillis: Long,
        limits: Limits,
        policy: KillerAtMaximumPolicy = KILLER_AT_MAXIMUM,
    ): Decision {
        val victim = limits.clamp(victimHearts)
        val killer = limits.clamp(killerHearts)
        if (victim - HEARTS_PER_KILL < limits.minimum) return Decision.VictimAtMinimum
        if (cooldownRemainingMillis > 0) return Decision.Cooldown(cooldownRemainingMillis)
        if (killer + HEARTS_PER_KILL > limits.maximum) {
            return when (policy) {
                KillerAtMaximumPolicy.NO_TRANSFER -> Decision.KillerAtMaximum
                KillerAtMaximumPolicy.VICTIM_LOSES_ONLY -> Decision.VictimLosesOnly(victim - HEARTS_PER_KILL)
            }
        }
        return Decision.Transfer(victim - HEARTS_PER_KILL, killer + HEARTS_PER_KILL)
    }

    // ── Anti-farming cooldown ───────────────────────────────

    fun cooldownMillis(minutes: Int): Long = minutes.coerceAtLeast(0) * 60_000L

    /**
     * Time left on a killer-victim cooldown, never negative and never more than [cooldownMillis].
     * A stored time in the future (the clock went back, or a corrupted row) counts as "just
     * happened", so a clock change can lengthen one cooldown but never skip it. A negative
     * stored time is corrupt and counts as no previous transfer.
     */
    fun cooldownRemaining(lastTransferAt: Long?, now: Long, cooldownMillis: Long): Long {
        if (lastTransferAt == null || lastTransferAt < 0 || cooldownMillis <= 0) return 0
        if (lastTransferAt >= now) return cooldownMillis
        val elapsed = now - lastTransferAt
        return (cooldownMillis - elapsed).coerceIn(0, cooldownMillis)
    }

    // ── Administrative changes ──────────────────────────────

    enum class AdminOp(val id: String) {
        SET("set"), ADD("add"), REMOVE("remove");

        companion object {
            fun fromId(id: String?): AdminOp? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
        }
    }

    sealed interface AdminDecision {
        /** [clamped] = the request went past a limit and stopped there. */
        data class Change(val after: Int, val clamped: Boolean) : AdminDecision
        data class Invalid(val reason: String) : AdminDecision
    }

    /** The largest amount one add/remove accepts. */
    const val MAX_ADMIN_AMOUNT = 1_000

    /**
     * Admin set/add/remove. `set` outside the limits is refused (the admin asked for a value that
     * can't exist); add/remove stop at the limit and say so. [current] is the stored value, so an
     * out-of-range or unreadable record is repaired by `set`.
     */
    fun adminChange(op: AdminOp, current: Int?, amount: Int, limits: Limits): AdminDecision = when (op) {
        AdminOp.SET ->
            if (amount < limits.minimum || amount > limits.maximum)
                AdminDecision.Invalid("Hearts must be between ${limits.minimum} and ${limits.maximum}.")
            else AdminDecision.Change(amount, clamped = false)
        AdminOp.ADD, AdminOp.REMOVE -> when {
            current == null -> AdminDecision.Invalid("This player's heart record is unreadable; repair it with set.")
            amount < 1 || amount > MAX_ADMIN_AMOUNT -> AdminDecision.Invalid("The amount must be between 1 and $MAX_ADMIN_AMOUNT.")
            else -> {
                val start = limits.clamp(current)
                val wanted = if (op == AdminOp.ADD) start.toLong() + amount else start.toLong() - amount
                val after = wanted.coerceIn(limits.minimum.toLong(), limits.maximum.toLong()).toInt()
                AdminDecision.Change(after, clamped = after.toLong() != wanted)
            }
        }
    }

    /** Remaining cooldown for players: `30m`, `1m 31s`, `45s`, `2h 0m`. */
    fun formatDuration(millis: Long): String {
        val totalSeconds = ((millis + 999) / 1000).coerceAtLeast(1)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return when {
            minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
            minutes > 0 -> if (seconds > 0) "${minutes}m ${seconds}s" else "${minutes}m"
            else -> "${seconds}s"
        }
    }

    /** A whole, positive-or-zero number typed by an admin, or null. */
    fun parseAmount(raw: String?): Int? = raw?.trim()?.takeIf { it.matches(Regex("\\d{1,7}")) }?.toInt()
}
