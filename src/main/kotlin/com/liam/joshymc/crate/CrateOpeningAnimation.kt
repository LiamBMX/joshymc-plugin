package com.liam.joshymc.crate

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Physical crate opening animation (issue #1503), the Bukkit-free part: the
 * `crates.opening-animation` settings, the timeline (activation → anticipation → opening →
 * reveal → closing), the pose the opener's temporary model copy takes on each tick, the
 * particle/sound cues and which effect profile a crate uses.
 *
 * The animation is **cosmetic only**. It never draws, stores or delivers a reward and never
 * touches a key: [com.liam.joshymc.manager.CrateManager] takes the key and (for random crates)
 * stores the drawn reward in `crate_pending_rewards` *before* the animation starts, then runs
 * the unchanged reward GUI at the reveal. Anything that interrupts the animation only skips
 * ahead to that GUI (or, on quit/shutdown, leaves the stored reward to the existing recovery).
 *
 * The models are single item models with no separate lid, so the "opening" is a pulse, wobble,
 * lift and tilt of the whole model plus particles and sounds; there is no articulated lid.
 */
object CrateOpeningAnimation {

    const val MIN_DURATION_TICKS = 10
    const val MAX_DURATION_TICKS = 80
    const val DEFAULT_DURATION_TICKS = 30
    const val MAX_CLOSE_TICKS = 40
    const val DEFAULT_CLOSE_TICKS = 8
    const val MAX_INTENSITY = 2.0
    const val DEFAULT_MAX_DISTANCE = 16.0

    /** Fractions of the duration where each stage starts. */
    const val ANTICIPATION_AT = 0.2
    const val OPENING_AT = 0.7

    /** Hard caps on particles per spawn call, whatever the intensity. */
    const val MAX_AMBIENT_PER_SPAWN = 6
    const val MAX_BURST = 40

    /** Ticks between transformation updates (also the client interpolation time). */
    const val POSE_INTERVAL = 2

    /** Extra ticks a session may live past its end before the tick loop force-finishes it. */
    const val STUCK_GRACE_TICKS = 40

    /** The profile used when nothing else matches and the config has none. */
    const val BUILTIN_PROFILE = "default"

    private val SOUND_KEY = Regex("^([a-z0-9_.-]+:)?[a-z0-9_./-]+$")
    private val PARTICLE_NAME = Regex("^[A-Za-z0-9_]+$")
    private val HEX = Regex("^#?[0-9A-Fa-f]{6}$")

    enum class MassOpen { BURST, NONE }

    enum class Stage { ACTIVATION, ANTICIPATION, OPENING, CLOSING, DONE }

    /** One effect profile: particle names (Bukkit enum names), an accent colour and sound keys. */
    data class Profile(
        val id: String,
        /** Soft particles around the crate during activation/anticipation. */
        val ambient: String,
        /** The lock/emblem glow at the front of the crate. */
        val accent: String,
        /** The burst when the crate opens. */
        val burst: String,
        /** RGB used by colourable particles (DUST, TINTED_LEAVES, ...) and the model's glow outline. */
        val color: Int,
        val activateSound: String?,
        val buildSound: String?,
        val openSound: String?,
        val revealSound: String?,
        val closeSound: String?,
    )

    /** What one crate's animation does after the global values and its override are merged. */
    data class Effective(
        val enabled: Boolean,
        val durationTicks: Int,
        val closeTicks: Int,
        val particleIntensity: Double,
        val soundVolume: Double,
        val movementIntensity: Double,
        /** Null = this crate opens with no profile override (resolved by [resolveProfile]). */
        val profileOverride: String?,
    )

    data class Override(
        val enabled: Boolean? = null,
        val profile: String? = null,
        val durationTicks: Int? = null,
        val closeTicks: Int? = null,
        val particleIntensity: Double? = null,
        val soundVolume: Double? = null,
        val movementIntensity: Double? = null,
    )

    data class Settings(
        val enabled: Boolean,
        val durationTicks: Int,
        val closeTicks: Int,
        val particleIntensity: Double,
        val soundVolume: Double,
        val movementIntensity: Double,
        val maxDistance: Double,
        val massOpen: MassOpen,
        val defaultProfile: String,
        val monthlyThemes: Boolean,
        /** crate id (lower case) -> its overrides. */
        val overrides: Map<String, Override>,
        /** profile id (lower case) -> profile; only entries that parsed cleanly. */
        val profiles: Map<String, Profile>,
        val problems: List<String>,
    ) {
        fun effective(crateId: String): Effective {
            val o = overrides[crateId.lowercase()] ?: Override()
            return Effective(
                enabled = enabled && (o.enabled ?: true),
                durationTicks = o.durationTicks ?: durationTicks,
                closeTicks = o.closeTicks ?: closeTicks,
                particleIntensity = o.particleIntensity ?: particleIntensity,
                soundVolume = o.soundVolume ?: soundVolume,
                movementIntensity = o.movementIntensity ?: movementIntensity,
                profileOverride = o.profile,
            )
        }

        fun profile(id: String): Profile? = profiles[id.lowercase()]
    }

    /** The profile every setting falls back to; also what a missing `profiles` section gives. */
    val BUILTIN = Profile(
        id = BUILTIN_PROFILE, ambient = "END_ROD", accent = "DUST", burst = "FIREWORK", color = 0xFFD27F,
        activateSound = "block.amethyst_block.chime", buildSound = "block.note_block.chime",
        openSound = "block.chest.open", revealSound = "entity.player.levelup", closeSound = "block.chest.close",
    )

    /** Safe defaults: on, 1.5 s, no overrides, only the built-in profile. */
    val DEFAULTS = Settings(
        enabled = true, durationTicks = DEFAULT_DURATION_TICKS, closeTicks = DEFAULT_CLOSE_TICKS,
        particleIntensity = 1.0, soundVolume = 0.8, movementIntensity = 1.0, maxDistance = DEFAULT_MAX_DISTANCE,
        massOpen = MassOpen.BURST, defaultProfile = BUILTIN_PROFILE, monthlyThemes = true,
        overrides = emptyMap(), profiles = mapOf(BUILTIN_PROFILE to BUILTIN), problems = emptyList(),
    )

    // ── Parsing ────────────────────────────────────────────────────────

    /**
     * Reads `crates.opening-animation` (passed as the [source] root). A missing section gives
     * [DEFAULTS]. A bad value is a [Settings.problems] line and uses its default (a bad
     * override value or profile is dropped); it never turns into a guess and never stops a
     * crate from opening.
     */
    fun parse(source: CrateModels.Source): Settings {
        val problems = mutableListOf<String>()
        val d = DEFAULTS

        fun bool(path: String, default: Boolean): Boolean = when (val v = source.value(path)) {
            null -> default
            is Boolean -> v
            else -> { problems += "$path must be true or false (was '$v'); using $default"; default }
        }
        fun int(path: String, default: Int, min: Int, max: Int): Int {
            val v = source.value(path) ?: return default
            val n = (v as? Number)?.toDouble() ?: v.toString().trim().toDoubleOrNull()
            if (n == null || n != kotlin.math.floor(n) || n < min || n > max) {
                problems += "$path must be a whole number $min..$max (was '$v'); using $default"
                return default
            }
            return n.toInt()
        }
        fun dbl(path: String, default: Double, min: Double, max: Double): Double {
            val v = source.value(path) ?: return default
            val n = (v as? Number)?.toDouble() ?: v.toString().trim().toDoubleOrNull()
            if (n == null || n.isNaN() || n < min || n > max) {
                problems += "$path must be a number $min..$max (was '$v'); using $default"
                return default
            }
            return n
        }

        val enabled = bool("enabled", d.enabled)
        val duration = int("duration-ticks", d.durationTicks, MIN_DURATION_TICKS, MAX_DURATION_TICKS)
        val close = int("close-ticks", d.closeTicks, 0, MAX_CLOSE_TICKS)
        val particles = dbl("particle-intensity", d.particleIntensity, 0.0, MAX_INTENSITY)
        val volume = dbl("sound-volume", d.soundVolume, 0.0, 1.0)
        val movement = dbl("movement-intensity", d.movementIntensity, 0.0, MAX_INTENSITY)
        val distance = dbl("max-distance", d.maxDistance, 4.0, 64.0)
        val massOpen = when (val v = source.value("mass-open")) {
            null -> d.massOpen
            else -> MassOpen.entries.firstOrNull { it.name.equals(v.toString().trim(), ignoreCase = true) }
                ?: run { problems += "mass-open must be burst or none (was '$v'); using burst"; MassOpen.BURST }
        }
        val monthlyThemes = bool("monthly-themes", d.monthlyThemes)

        val profiles = linkedMapOf(BUILTIN_PROFILE to BUILTIN)
        for (raw in source.keys("profiles").sorted()) {
            val profile = parseProfile(source, "profiles.$raw", raw.lowercase(), problems) ?: continue
            profiles[profile.id] = profile
        }

        val defaultProfileRaw = source.value("default-profile")?.toString()?.trim()?.lowercase()
        val defaultProfile = when {
            defaultProfileRaw.isNullOrEmpty() -> BUILTIN_PROFILE
            defaultProfileRaw in profiles -> defaultProfileRaw
            else -> { problems += "default-profile '$defaultProfileRaw' is not a profile; using $BUILTIN_PROFILE"; BUILTIN_PROFILE }
        }

        val overrides = mutableMapOf<String, Override>()
        for (raw in source.keys("crates").sorted()) {
            val p = "crates.$raw"
            fun optBool(key: String): Boolean? = when (val v = source.value("$p.$key")) {
                null -> null
                is Boolean -> v
                else -> { problems += "$p.$key must be true or false (was '$v'); ignored"; null }
            }
            fun optInt(key: String, min: Int, max: Int): Int? {
                val v = source.value("$p.$key") ?: return null
                val n = (v as? Number)?.toDouble() ?: v.toString().trim().toDoubleOrNull()
                if (n == null || n != kotlin.math.floor(n) || n < min || n > max) {
                    problems += "$p.$key must be a whole number $min..$max (was '$v'); ignored"
                    return null
                }
                return n.toInt()
            }
            fun optDbl(key: String, min: Double, max: Double): Double? {
                val v = source.value("$p.$key") ?: return null
                val n = (v as? Number)?.toDouble() ?: v.toString().trim().toDoubleOrNull()
                if (n == null || n.isNaN() || n < min || n > max) {
                    problems += "$p.$key must be a number $min..$max (was '$v'); ignored"
                    return null
                }
                return n
            }
            val profile = source.value("$p.profile")?.toString()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
                if (it in profiles) it else { problems += "$p.profile '$it' is not a profile; ignored"; null }
            }
            overrides[raw.lowercase()] = Override(
                enabled = optBool("enabled"),
                profile = profile,
                durationTicks = optInt("duration-ticks", MIN_DURATION_TICKS, MAX_DURATION_TICKS),
                closeTicks = optInt("close-ticks", 0, MAX_CLOSE_TICKS),
                particleIntensity = optDbl("particle-intensity", 0.0, MAX_INTENSITY),
                soundVolume = optDbl("sound-volume", 0.0, 1.0),
                movementIntensity = optDbl("movement-intensity", 0.0, MAX_INTENSITY),
            )
        }

        return Settings(enabled, duration, close, particles, volume, movement, distance, massOpen,
            defaultProfile, monthlyThemes, overrides, profiles, problems)
    }

    private fun parseProfile(source: CrateModels.Source, path: String, id: String, problems: MutableList<String>): Profile? {
        fun particle(key: String, default: String): String {
            val v = source.value("$path.$key")?.toString()?.trim() ?: return default
            if (!PARTICLE_NAME.matches(v)) { problems += "$path.$key '$v' is not a particle name; using $default"; return default }
            return v.uppercase()
        }
        fun sound(key: String, default: String?): String? {
            val v = source.value("$path.$key")?.toString()?.trim()?.lowercase() ?: return default
            if (v.isEmpty() || v == "none") return null
            if (!SOUND_KEY.matches(v)) { problems += "$path.$key '$v' is not a sound key like block.chest.open; using the default"; return default }
            return v
        }
        val color = when (val v = source.value("$path.color")) {
            null -> BUILTIN.color
            else -> parseColor(v.toString()) ?: run { problems += "$path.color '$v' is not a #RRGGBB colour; using the default"; BUILTIN.color }
        }
        val profile = Profile(
            id = id,
            ambient = particle("ambient", BUILTIN.ambient),
            accent = particle("accent", BUILTIN.accent),
            burst = particle("burst", BUILTIN.burst),
            color = color,
            activateSound = sound("activate-sound", BUILTIN.activateSound),
            buildSound = sound("build-sound", BUILTIN.buildSound),
            openSound = sound("open-sound", BUILTIN.openSound),
            revealSound = sound("reveal-sound", BUILTIN.revealSound),
            closeSound = sound("close-sound", BUILTIN.closeSound),
        )
        // A profile with a bad value still loads, with that one value defaulted (logged above).
        return profile
    }

    fun parseColor(raw: String): Int? {
        val s = raw.trim()
        if (!HEX.matches(s)) return null
        return s.removePrefix("#").toInt(16)
    }

    // ── Profiles ───────────────────────────────────────────────────────

    /**
     * Which profile a crate opens with: its own override, then this month's theme (only while
     * the crate is showing a monthly model and `monthly-themes` is on), then a profile named
     * after the crate id, then `default-profile`, then the built-in profile.
     */
    fun resolveProfile(settings: Settings, crateId: String, monthId: String?): Profile {
        val e = settings.effective(crateId)
        e.profileOverride?.let { settings.profile(it) }?.let { return it }
        if (settings.monthlyThemes && monthId != null) settings.profile(monthId)?.let { return it }
        settings.profile(crateId)?.let { return it }
        return settings.profile(settings.defaultProfile) ?: BUILTIN
    }

    // ── Timeline ───────────────────────────────────────────────────────

    /** Where tick [tick] of an animation with [duration] opening ticks and [close] closing ticks falls. */
    fun stageAt(tick: Int, duration: Int, close: Int): Stage = when {
        tick < 0 -> Stage.ACTIVATION
        tick < (duration * ANTICIPATION_AT).toInt() -> Stage.ACTIVATION
        tick < (duration * OPENING_AT).toInt() -> Stage.ANTICIPATION
        tick < duration -> Stage.OPENING
        tick < duration + close -> Stage.CLOSING
        else -> Stage.DONE
    }

    enum class Cue { ACTIVATE, BUILD, OPEN, REVEAL, CLOSE, DONE }

    /** What starts on [tick]. The reveal (reward GUI) is exactly at [duration]; DONE removes everything. */
    fun cues(tick: Int, duration: Int, close: Int): List<Cue> {
        val out = mutableListOf<Cue>()
        val anticipation = (duration * ANTICIPATION_AT).toInt()
        val opening = (duration * OPENING_AT).toInt()
        if (tick == 0) out += Cue.ACTIVATE
        if (tick in anticipation until opening && (tick - anticipation) % 4 == 0) out += Cue.BUILD
        if (tick == opening) out += Cue.OPEN
        if (tick == duration) out += Cue.REVEAL
        if (close > 0 && tick == duration + close / 2) out += Cue.CLOSE
        if (tick == duration + close) out += Cue.DONE
        return out
    }

    /** Last tick a session can ever reach before the tick loop must finish it. */
    fun deadline(duration: Int, close: Int): Int = duration + close + STUCK_GRACE_TICKS

    /** Ambient particles on [tick]: a few at first, growing through anticipation, none after opening. */
    fun ambientCount(tick: Int, duration: Int, intensity: Double): Int {
        if (tick % 2 != 0 || tick >= (duration * OPENING_AT).toInt()) return 0
        val t = tick.toDouble() / duration
        val base = 1.0 + 4.0 * (t / OPENING_AT)
        return (base * intensity).toInt().coerceIn(0, MAX_AMBIENT_PER_SPAWN)
    }

    /** Accent (lock / emblem) particles on [tick]: a steady glow through anticipation and opening. */
    fun accentCount(tick: Int, duration: Int, intensity: Double): Int {
        if (tick % 3 != 0 || tick < (duration * ANTICIPATION_AT).toInt() || tick >= duration) return 0
        return (2.0 * intensity).toInt().coerceIn(0, MAX_AMBIENT_PER_SPAWN)
    }

    fun burstCount(intensity: Double): Int = (24.0 * intensity).toInt().coerceIn(0, MAX_BURST)

    /** Rising pitch of the anticipation sound. */
    fun buildPitch(tick: Int, duration: Int): Float {
        val anticipation = (duration * ANTICIPATION_AT)
        val span = (duration * OPENING_AT - anticipation).coerceAtLeast(1.0)
        val u = ((tick - anticipation) / span).coerceIn(0.0, 1.0)
        return (0.8 + 0.8 * u).toFloat()
    }

    // ── Pose ───────────────────────────────────────────────────────────

    /**
     * The model's movement relative to its resting placement. Angles in degrees around the
     * model's own axes (after its yaw, so facing is respected), [scale] multiplies the
     * configured scale, [lift] is in blocks and already includes the clearance that keeps the
     * bottom of a scaled/tilted model out of the floor.
     */
    data class Pose(val lift: Double, val scale: Double, val pitch: Double, val roll: Double, val glow: Boolean) {
        val isRest: Boolean get() = lift == 0.0 && scale == 1.0 && pitch == 0.0 && roll == 0.0
    }

    val REST = Pose(0.0, 1.0, 0.0, 0.0, false)

    private fun smooth(x: Double): Double { val c = x.coerceIn(0.0, 1.0); return c * c * (3 - 2 * c) }

    /**
     * Pose on [tick]. [baseScale] is the model's configured scale (the clearance grows with it);
     * [movement] scales every motion (0 = the model never moves).
     */
    fun pose(tick: Int, duration: Int, close: Int, movement: Double, baseScale: Double): Pose {
        if (movement <= 0.0) return REST.copy(glow = glowAt(tick, duration))
        val anticipation = (duration * ANTICIPATION_AT).toInt()
        val opening = (duration * OPENING_AT).toInt()
        var hop = 0.0
        var scale = 1.0
        var pitch = 0.0
        var roll = 0.0
        when {
            tick < anticipation -> {
                val u = tick.toDouble() / anticipation.coerceAtLeast(1)
                scale = 1.0 + 0.05 * movement * sin(PI * u)
            }
            tick < opening -> {
                val u = (tick - anticipation).toDouble() / (opening - anticipation).coerceAtLeast(1)
                roll = 4.0 * movement * u * sin(u * 6 * PI)
            }
            tick < duration -> {
                val v = smooth((tick - opening).toDouble() / (duration - opening).coerceAtLeast(1))
                hop = 0.12 * movement * v
                scale = 1.0 + 0.08 * movement * v
                pitch = -6.0 * movement * v
            }
            tick < duration + close -> {
                val back = 1.0 - smooth((tick - duration).toDouble() / close.coerceAtLeast(1))
                hop = 0.12 * movement * back
                scale = 1.0 + 0.08 * movement * back
                pitch = -6.0 * movement * back
            }
        }
        val tilt = Math.toRadians(maxOf(abs(pitch), abs(roll)))
        // Scaling and tilting happen about the model's centre: lift the bottom back above the floor.
        val clearance = 0.5 * baseScale * (scale - 1.0) + 0.5 * baseScale * scale * sin(tilt)
        return Pose(hop + clearance.coerceAtLeast(0.0), scale, pitch, roll, glowAt(tick, duration))
    }

    /** Glow outline: a flash at activation and from the opening to the reveal. */
    fun glowAt(tick: Int, duration: Int): Boolean =
        tick in 0 until (duration * ANTICIPATION_AT).toInt().coerceAtLeast(1) ||
            tick in (duration * OPENING_AT).toInt() until duration

    // ── Sessions ───────────────────────────────────────────────────────

    /**
     * Running animations, one per player (a new one for the same player replaces the old, which
     * must be cleaned up first). Any number of players may animate the same crate: each has its
     * own entry and its own temporary entity, so nobody can break anyone else's animation.
     */
    class Book<S : Any> {
        private val byPlayer = LinkedHashMap<String, Entry<S>>()

        data class Entry<S>(val player: String, val locationKey: String, val session: S)

        /** Adds [session] for [player]; returns the one it replaced (caller cleans it up), if any. */
        fun begin(player: String, locationKey: String, session: S): S? =
            byPlayer.put(player, Entry(player, locationKey, session))?.session

        fun of(player: String): S? = byPlayer[player]?.session

        /** Removes [player]'s entry only if it is still [session] (a stale finish can't end a newer one). */
        fun end(player: String, session: S): Boolean {
            val e = byPlayer[player] ?: return false
            if (e.session !== session) return false
            byPlayer.remove(player)
            return true
        }

        fun at(locationKey: String): List<S> = byPlayer.values.filter { it.locationKey == locationKey }.map { it.session }

        fun all(): List<S> = byPlayer.values.map { it.session }

        fun clear(): List<S> { val out = all(); byPlayer.clear(); return out }

        val size: Int get() = byPlayer.size
    }

    /**
     * Why a running animation must skip to its reveal right now, or null to keep going.
     * Checked every tick; anything here only shortens the animation (the reward flow continues).
     */
    fun interruption(sameWorld: Boolean, distanceSq: Double, maxDistance: Double, chunkLoaded: Boolean, tick: Int, deadline: Int): String? = when {
        !sameWorld -> "left the world"
        distanceSq > maxDistance * maxDistance -> "moved away"
        !chunkLoaded -> "chunk unloaded"
        tick > deadline -> "timed out"
        else -> null
    }
}
