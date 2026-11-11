package com.liam.joshymc.cosmetic

/**
 * Bukkit-free rules for back cosmetics (issue #1516): when a wearer's back model is drawn,
 * who sees it, and where it sits on the body. [BackCosmeticManager] applies them.
 *
 * The model is an ItemDisplay that **follows** the wearer (teleported to them every tick with
 * client-side teleport interpolation). It never rides the player: Paper refuses to teleport a
 * player who has passengers unless the caller passes `RETAIN_PASSENGERS`, so a passenger would
 * break `/spawn`, `/home`, RTP and every other teleport for the wearer.
 */
object BackCosmeticRules {

    /** Scoreboard tag on every back cosmetic display (stray sweeps match it with [OWNER_KEY]). */
    const val TAG = "joshymc_back_cosmetic"

    /** PDC key (joshymc namespace) holding the wearer's UUID on the display. */
    const val OWNER_KEY = "back_cosmetic_owner"

    /** Poses the model is drawn in; gliding, swimming, sleeping, riptide and dying hide it. */
    val VISIBLE_POSES = setOf("STANDING", "SNEAKING")

    /** A display further than this (blocks) from its wearer is respawned instead of gliding there. */
    const val RESPAWN_DISTANCE = 6.0

    /** How many ticks the client smooths each move over (`teleport_duration`, vanilla max 59). */
    const val DEFAULT_SMOOTHING_TICKS = 2
    const val MAX_SMOOTHING_TICKS = 5

    /** Ticks a pose change (stand <-> sneak) blends over. */
    const val POSE_BLEND_TICKS = 3

    /**
     * The mount point on the back, in the wearer's frame (blocks from their feet: +Y up, -Z
     * behind), plus how far the model leans forward. Model point (8, 8, 8) lands here.
     * `art/render.py` (BACK_ANCHOR) previews the standing anchor; keep the two in step.
     */
    data class Anchor(val x: Double, val y: Double, val z: Double, val tiltDegrees: Double)

    /** Between the shoulder blades, just behind the chestplate layer. */
    val STANDING = Anchor(0.0, 1.35, -0.22, 0.0)

    /** Sneaking lowers the shoulders and tips the torso forward, so the wings follow. */
    val SNEAKING = Anchor(0.0, 1.1, -0.34, 24.0)

    data class Settings(
        val enabled: Boolean = true,
        val showToWearer: Boolean = true,
        val hideInCombat: Boolean = true,
        val smoothingTicks: Int = DEFAULT_SMOOTHING_TICKS,
    )

    /** Why a wearer's model isn't drawn right now; null = draw it. */
    enum class Hidden(val text: String) {
        DISABLED("back cosmetics are turned off"),
        NOT_HERE("the item's gamemode is locked away in this world"),
        DEAD("dead"),
        SPECTATOR("spectator mode"),
        INVISIBLE("invisible"),
        VANISHED("vanished"),
        MOD_MODE("in Mod Mode"),
        COMBAT("combat-tagged"),
        EVENT_WORLD("in the event world"),
        POSE("gliding, swimming, sleeping or riptiding"),
    }

    /** Everything about the wearer the decision needs. */
    data class Wearer(
        val allowedHere: Boolean = true,
        val dead: Boolean = false,
        val spectator: Boolean = false,
        val invisible: Boolean = false,
        val vanished: Boolean = false,
        val modMode: Boolean = false,
        val combatTagged: Boolean = false,
        val inEventWorld: Boolean = false,
        val pose: String = "STANDING",
    )

    /**
     * Hidden whenever drawing it could reveal someone who shouldn't be seen (vanish, Mod Mode,
     * invisibility, spectators), block anyone's view in a fight (combat tag, hosted events),
     * or float wrongly (any pose but standing/sneaking). The first matching reason wins.
     */
    fun hidden(settings: Settings, wearer: Wearer): Hidden? = when {
        !settings.enabled -> Hidden.DISABLED
        !wearer.allowedHere -> Hidden.NOT_HERE
        wearer.dead -> Hidden.DEAD
        wearer.spectator -> Hidden.SPECTATOR
        wearer.vanished -> Hidden.VANISHED
        wearer.modMode -> Hidden.MOD_MODE
        wearer.invisible -> Hidden.INVISIBLE
        settings.hideInCombat && wearer.combatTagged -> Hidden.COMBAT
        wearer.inEventWorld -> Hidden.EVENT_WORLD
        wearer.pose !in VISIBLE_POSES -> Hidden.POSE
        else -> null
    }

    /**
     * Whether [viewerIsWearer]/other viewer is shown the model: only Java players whose JoshyMC
     * resource pack loaded (anyone else would see a missing-model cube), only while they can see
     * the wearer at all, in the same world, and the wearer themself only with `show-to-wearer`.
     */
    fun viewerSees(
        settings: Settings,
        viewerIsWearer: Boolean,
        bedrock: Boolean,
        packLoaded: Boolean,
        canSeeWearer: Boolean,
        sameWorld: Boolean,
    ): Boolean = when {
        !sameWorld || bedrock || !packLoaded -> false
        viewerIsWearer -> settings.showToWearer
        else -> canSeeWearer
    }

    /** The anchor for the wearer's pose, scaled with their size (the `scale` attribute). */
    fun anchor(sneaking: Boolean, scale: Double): Anchor {
        val base = if (sneaking) SNEAKING else STANDING
        val s = safeScale(scale)
        return Anchor(base.x * s, base.y * s, base.z * s, base.tiltDegrees)
    }

    /** The model's scale for a wearer of size [playerScale] (non-finite or <= 0 counts as 1). */
    fun modelScale(itemScale: Double, playerScale: Double): Double = safeScale(itemScale) * safeScale(playerScale)

    private fun safeScale(v: Double): Double = if (v.isFinite() && v > 0.0) v else 1.0

    /** True when the display must be respawned next to its wearer (other world, or too far to glide). */
    fun mustRespawn(sameWorld: Boolean, dx: Double, dy: Double, dz: Double): Boolean =
        !sameWorld || dx * dx + dy * dy + dz * dz > RESPAWN_DISTANCE * RESPAWN_DISTANCE

    /** Config `smoothing-ticks`, clamped to 0..[MAX_SMOOTHING_TICKS]. */
    fun smoothing(configured: Int): Int = configured.coerceIn(0, MAX_SMOOTHING_TICKS)
}
