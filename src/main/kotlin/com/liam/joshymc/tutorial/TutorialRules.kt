package com.liam.joshymc.tutorial

import com.liam.joshymc.gamemode.ServerGamemode

/**
 * The guided tutorial walkthrough (issue #1387): the two fixed seven-step sequences and every
 * decision about them. Bukkit-free, so `scripts/TutorialCheck.kt` can check it without a server.
 *
 * Steps never change order and none can be added from config (only their texts can change).
 */
enum class TutorialStep(val id: String) {
    WALK("walk"),
    STARTER_KIT("starter-kit"),
    RTP("rtp"),
    CLAIM("claim"),
    EARN("earn"),
    QUESTS("quests"),
    HEARTS("hearts"),
    TEAMS("teams"),
    COMBAT("combat"),
    DISCORD("discord");
}

/** Persistent tutorial state of one player in one gamemode. A missing row means NOT_STARTED. */
enum class TutorialState { NOT_STARTED, ACTIVE, SKIPPED, COMPLETED;

    companion object {
        fun fromName(raw: String?): TutorialState? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * Something a gameplay system confirmed. Only the system that did the action sends it (a
 * successful kit claim, a finished random teleport, ...), never a typed command.
 */
sealed class TutorialSignal {
    data object StarterKitClaimed : TutorialSignal()
    data object RandomTeleported : TutorialSignal()
    data object ClaimCreated : TutorialSignal()
    /** Money paid for items actually sold (SaleManager). Nothing else counts as earnings. */
    data class Earned(val amount: Double) : TutorialSignal()
    data object DailyQuestsOpened : TutorialSignal()
    data object WeeklyQuestsOpened : TutorialSignal()
    data object HeartsInfoOpened : TutorialSignal()
    data object TeamsMenuOpened : TutorialSignal()
    data object CombatInfoOpened : TutorialSignal()
    data object DiscordInfoShown : TutorialSignal()
}

object TutorialRules {

    const val STEP_COUNT = 7

    val SURVIVAL_STEPS: List<TutorialStep> = listOf(
        TutorialStep.WALK, TutorialStep.STARTER_KIT, TutorialStep.RTP, TutorialStep.CLAIM,
        TutorialStep.EARN, TutorialStep.QUESTS, TutorialStep.DISCORD,
    )

    val LIFESTEAL_STEPS: List<TutorialStep> = listOf(
        TutorialStep.WALK, TutorialStep.STARTER_KIT, TutorialStep.RTP, TutorialStep.HEARTS,
        TutorialStep.TEAMS, TutorialStep.COMBAT, TutorialStep.DISCORD,
    )

    fun stepsFor(gamemode: ServerGamemode): List<TutorialStep> = when (gamemode) {
        ServerGamemode.SURVIVAL -> SURVIVAL_STEPS
        ServerGamemode.LIFESTEAL -> LIFESTEAL_STEPS
    }

    /** The step at [index] (0-based) of [gamemode]'s tutorial, or null when out of range. */
    fun stepAt(gamemode: ServerGamemode, index: Int): TutorialStep? = stepsFor(gamemode).getOrNull(index)

    // ── First join / resume ─────────────────────────────────

    enum class JoinAction { START, RESUME, NONE }

    /**
     * What happens when a player is (re)entering [gamemode]'s tutorial context (join or world
     * change). [state] is the stored row's state (null = no row). A player with no row only starts
     * automatically when they first played on this server at or after [installedAt] (the moment the
     * walkthrough first ran here), so established players are never interrupted; they can still
     * start it with /tutorial. Completed and skipped tutorials never restart on their own.
     */
    fun onEnter(enabled: Boolean, state: TutorialState?, firstPlayed: Long, installedAt: Long): JoinAction {
        if (!enabled) return JoinAction.NONE
        return when (state) {
            null, TutorialState.NOT_STARTED -> if (firstPlayed in 1..Long.MAX_VALUE && firstPlayed >= installedAt) JoinAction.START else JoinAction.NONE
            TutorialState.ACTIVE -> JoinAction.RESUME
            TutorialState.SKIPPED, TutorialState.COMPLETED -> JoinAction.NONE
        }
    }

    // ── Objectives ──────────────────────────────────────────

    /** Quest step progress bits: both must be set. */
    const val QUEST_DAILY = 1
    const val QUEST_WEEKLY = 2
    const val QUEST_BOTH = QUEST_DAILY or QUEST_WEEKLY

    /** Result of a signal on the current step: new step progress and whether the step is done. */
    data class Outcome(val progress: Double, val completed: Boolean, val changed: Boolean)

    /**
     * Applies [signal] to [step] (with its stored [progress]). Signals for any other step change
     * nothing, so an action done early, late or in the wrong order never advances anything.
     * Earnings only count positive, finite amounts.
     */
    fun apply(step: TutorialStep, progress: Double, signal: TutorialSignal, requiredEarnings: Double): Outcome {
        val unchanged = Outcome(progress, completed = false, changed = false)
        return when (step) {
            TutorialStep.WALK -> unchanged // movement goes through [walked]
            TutorialStep.STARTER_KIT -> done(signal is TutorialSignal.StarterKitClaimed, progress)
            TutorialStep.RTP -> done(signal is TutorialSignal.RandomTeleported, progress)
            TutorialStep.CLAIM -> done(signal is TutorialSignal.ClaimCreated, progress)
            TutorialStep.EARN -> {
                if (signal !is TutorialSignal.Earned) return unchanged
                val amount = signal.amount
                if (!amount.isFinite() || amount <= 0.0) return unchanged
                val total = (progress.coerceAtLeast(0.0) + amount)
                Outcome(total, completed = total >= requiredEarnings, changed = true)
            }
            TutorialStep.QUESTS -> {
                val bit = when (signal) {
                    TutorialSignal.DailyQuestsOpened -> QUEST_DAILY
                    TutorialSignal.WeeklyQuestsOpened -> QUEST_WEEKLY
                    else -> return unchanged
                }
                val before = progress.toInt() and QUEST_BOTH
                val after = before or bit
                Outcome(after.toDouble(), completed = after == QUEST_BOTH, changed = after != before)
            }
            TutorialStep.HEARTS -> done(signal is TutorialSignal.HeartsInfoOpened, progress)
            TutorialStep.TEAMS -> done(signal is TutorialSignal.TeamsMenuOpened, progress)
            TutorialStep.COMBAT -> done(signal is TutorialSignal.CombatInfoOpened, progress)
            TutorialStep.DISCORD -> done(signal is TutorialSignal.DiscordInfoShown, progress)
        }
    }

    private fun done(matches: Boolean, progress: Double) = Outcome(progress, completed = matches, changed = matches)

    /**
     * Walking: horizontal distance from the anchor (where the step began, or where the player last
     * arrived by teleport, respawn, world change or leaving a vehicle). Different worlds never count.
     */
    fun walked(anchorWorld: String, ax: Double, az: Double, world: String, x: Double, z: Double, required: Double): Boolean {
        if (anchorWorld != world) return false
        val dx = x - ax
        val dz = z - az
        return dx * dx + dz * dz >= required * required
    }

    fun walkedDistance(ax: Double, az: Double, x: Double, z: Double): Double {
        val dx = x - ax
        val dz = z - az
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }

    // ── Rewards ─────────────────────────────────────────────

    /**
     * Whether this server's Money balance belongs to [gamemode]. Each network server has its own
     * database; on a server that also maps worlds to the other gamemode the one `economy` table is
     * Survival's, so a Lifesteal reward is only paid on a server that hosts nothing but Lifesteal.
     * Anywhere else the entitlement stays pending (never paid into the wrong gamemode's balance).
     */
    fun economyBelongsTo(gamemode: ServerGamemode, hosted: Set<ServerGamemode>): Boolean = when (gamemode) {
        ServerGamemode.SURVIVAL -> ServerGamemode.SURVIVAL in hosted
        ServerGamemode.LIFESTEAL -> hosted == setOf(ServerGamemode.LIFESTEAL)
    }

    /** A configured reward amount: finite, not negative, rounded to cents; anything else is the default. */
    fun rewardAmount(raw: Double?, default: Double): Double {
        if (raw == null || !raw.isFinite() || raw < 0.0) return default
        return Math.round(raw * 100.0) / 100.0
    }

    // ── Presentation ────────────────────────────────────────

    /** "$65" / "$100" style amounts for the action bar (no cents for whole numbers). */
    fun money(amount: Double): String {
        val cents = Math.round(amount.coerceAtLeast(0.0) * 100.0)
        val whole = cents / 100
        val rest = cents % 100
        val grouped = String.format(java.util.Locale.US, "%,d", whole)
        return if (rest == 0L) "$$grouped" else "$$grouped.${rest.toString().padStart(2, '0')}"
    }

    /** Earnings shown while the EARN step runs: never shows the target before it is reached. */
    fun shownEarnings(progress: Double, required: Double): Double {
        val p = progress.coerceAtLeast(0.0)
        return if (p >= required) required else kotlin.math.min(p, kotlin.math.max(0.0, required - 0.01))
    }

    /** Fills `{key}` placeholders. Unknown keys are left as they are. */
    fun fill(template: String, values: Map<String, String>): String {
        var out = template
        for ((key, value) in values) out = out.replace("{$key}", value)
        return out
    }
}
