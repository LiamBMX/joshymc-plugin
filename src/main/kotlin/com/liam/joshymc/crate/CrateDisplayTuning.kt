package com.liam.joshymc.crate

/**
 * Per-location crate display placement (issue #1517), the Bukkit-free part.
 *
 * Issue #1472 gave every `crate_locations` row its own facing. This adds the rest of what an
 * admin needs to line one physical crate's model up with its pedestal, without touching the
 * crate type's `crates.custom-models.models.<id>` entry (which every crate of that type shares):
 *
 * - [Tuning.yawAdjust]: a fine rotation in degrees added on top of the facing the crate already
 *   shows (its own facing, else the type's `rotation`, else the block's facing). The facing keeps
 *   deciding which way the front points; the adjustment only tilts it a little.
 * - [Tuning.offsetX]/[Tuning.offsetY]/[Tuning.offsetZ]: world-axis offsets in blocks (same axes
 *   as F3 coordinates, so turning the crate never moves it). Null = the type's offset.
 * - [Tuning.scale]: null = the type's scale.
 *
 * The model always turns about its own centre over the block (the display position), so changing
 * the facing or adjustment never shifts it sideways. Bounds are the same as the config's
 * ([CrateModels.MAX_OFFSET], [CrateModels.MIN_SCALE]..[CrateModels.MAX_SCALE]), so the chunk
 * reach and hitbox rules stay valid. Stored as nullable columns: NULL = inherit, so every older
 * row (and a reset) draws exactly as before.
 */
object CrateDisplayTuning {

    /** Largest fine rotation either way, in degrees. A bigger turn is a facing change. */
    const val MAX_YAW_ADJUST = 180f

    data class Tuning(
        val yawAdjust: Float = 0f,
        val offsetX: Double? = null,
        val offsetY: Double? = null,
        val offsetZ: Double? = null,
        val scale: Double? = null,
    ) {
        /** True when nothing is overridden (the location draws exactly like the crate type). */
        val isDefault: Boolean get() = this == NONE

        val hasOffset: Boolean get() = offsetX != null || offsetY != null || offsetZ != null
    }

    val NONE = Tuning()

    /**
     * The model a location draws: the type's [model] with the location's facing (issue #1472),
     * then its fine rotation, offsets and scale. [blockFacing] is the crate block's horizontal
     * facing, needed only to turn an automatic rotation into degrees before adjusting it.
     */
    fun apply(
        model: CrateModels.Model,
        facing: CrateFacing.Direction?,
        tuning: Tuning,
        blockFacing: String?,
    ): CrateModels.Model {
        val faced = CrateFacing.effectiveRotation(model.rotation, facing)
        val rotation = adjust(faced, tuning.yawAdjust, blockFacing)
        return model.copy(
            rotation = rotation,
            offsetX = tuning.offsetX ?: model.offsetX,
            offsetY = tuning.offsetY ?: model.offsetY,
            offsetZ = tuning.offsetZ ?: model.offsetZ,
            scale = tuning.scale ?: model.scale,
        )
    }

    /** [rotation] turned by [adjust] degrees; no adjustment keeps it exactly (auto stays auto). */
    fun adjust(rotation: CrateModels.Rotation, adjust: Float, blockFacing: String?): CrateModels.Rotation {
        if (adjust == 0f) return rotation
        val base = when (rotation) {
            is CrateModels.Rotation.Fixed -> rotation.degrees
            CrateModels.Rotation.Auto -> CrateModels.facingYaw(blockFacing) ?: 0f
        }
        return CrateModels.Rotation.Fixed(normalize(base + adjust))
    }

    private fun normalize(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

    // ── Command arguments ──────────────────────────────────────────────

    /** Result of reading one admin-typed value. */
    sealed interface Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>
        /** `default`/`reset`: clear the override. */
        data object Clear : Parsed<Nothing>
        data class Bad(val reason: String) : Parsed<Nothing>
    }

    private val CLEAR_WORDS = setOf("default", "reset", "auto", "inherit")

    private fun number(text: String): Double? =
        text.trim().toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }

    /** `/crate display rotate <degrees>`: -180..180; `0`/`default` clears it. */
    fun parseYawAdjust(text: String): Parsed<Float> {
        if (text.trim().lowercase() in CLEAR_WORDS) return Parsed.Clear
        val d = number(text) ?: return Parsed.Bad("'$text' is not a number of degrees")
        if (d < -MAX_YAW_ADJUST || d > MAX_YAW_ADJUST) {
            return Parsed.Bad("fine rotation must be between -${MAX_YAW_ADJUST.toInt()} and ${MAX_YAW_ADJUST.toInt()} degrees (use /crate display facing to turn it further)")
        }
        return if (d == 0.0) Parsed.Clear else Parsed.Ok(d.toFloat())
    }

    /** One `/crate display offset` axis: -1..1 blocks, or `default` for the type's value. */
    fun parseOffset(axis: String, text: String): Parsed<Double> {
        if (text.trim().lowercase() in CLEAR_WORDS) return Parsed.Clear
        val d = number(text) ?: return Parsed.Bad("$axis offset '$text' is not a number")
        if (d < -CrateModels.MAX_OFFSET || d > CrateModels.MAX_OFFSET) {
            return Parsed.Bad("$axis offset must be between -${CrateModels.MAX_OFFSET} and ${CrateModels.MAX_OFFSET} blocks")
        }
        return Parsed.Ok(d)
    }

    /** `/crate display scale <value>`: 0.5..2, or `default` for the type's value. */
    fun parseScale(text: String): Parsed<Double> {
        if (text.trim().lowercase() in CLEAR_WORDS) return Parsed.Clear
        val d = number(text) ?: return Parsed.Bad("scale '$text' is not a number")
        if (d < CrateModels.MIN_SCALE || d > CrateModels.MAX_SCALE) {
            return Parsed.Bad("scale must be between ${CrateModels.MIN_SCALE} and ${CrateModels.MAX_SCALE}")
        }
        return Parsed.Ok(d)
    }

    /**
     * `/crate display offset <x> <y> <z>` on top of [current]. Every axis must parse before
     * anything changes; `default` clears that one axis.
     */
    fun withOffsets(current: Tuning, x: String, y: String, z: String): Parsed<Tuning> {
        var out = current
        for ((axis, text) in listOf("x" to x, "y" to y, "z" to z)) {
            val v: Double? = when (val p = parseOffset(axis, text)) {
                is Parsed.Bad -> return p
                Parsed.Clear -> null
                is Parsed.Ok -> p.value
            }
            out = when (axis) {
                "x" -> out.copy(offsetX = v)
                "y" -> out.copy(offsetY = v)
                else -> out.copy(offsetZ = v)
            }
        }
        return Parsed.Ok(out)
    }

    // ── Stored columns ─────────────────────────────────────────────────

    /**
     * Reads the stored columns (null = inherit). A value outside the allowed range (a hand-edited
     * row) is dropped from the result and named in [problems]; the row itself is never changed
     * here, so nothing is silently deleted.
     */
    fun fromStored(
        yawAdjust: Double?,
        offsetX: Double?,
        offsetY: Double?,
        offsetZ: Double?,
        scale: Double?,
        problems: MutableList<String>,
    ): Tuning {
        fun ok(name: String, v: Double?, min: Double, max: Double): Double? {
            if (v == null) return null
            if (v.isNaN() || v.isInfinite() || v < min || v > max) {
                problems += "$name $v is outside $min..$max"
                return null
            }
            return v
        }
        val m = CrateModels.MAX_OFFSET
        return Tuning(
            yawAdjust = ok("rotation adjustment", yawAdjust, -MAX_YAW_ADJUST.toDouble(), MAX_YAW_ADJUST.toDouble())?.toFloat() ?: 0f,
            offsetX = ok("offset x", offsetX, -m, m),
            offsetY = ok("offset y", offsetY, -m, m),
            offsetZ = ok("offset z", offsetZ, -m, m),
            scale = ok("scale", scale, CrateModels.MIN_SCALE, CrateModels.MAX_SCALE),
        )
    }

    /** The yaw-adjust column value (NULL when there is none). */
    fun storedYaw(tuning: Tuning): Double? = tuning.yawAdjust.takeIf { it != 0f }?.toDouble()

    // ── Text for /crate display ────────────────────────────────────────

    fun fmt(v: Double): String = if (v == kotlin.math.floor(v)) v.toLong().toString() else "%.3f".format(v).trimEnd('0').trimEnd('.')

    /** One line per setting: the location's own value or what it inherits from [model]. */
    fun describe(tuning: Tuning, model: CrateModels.Model?): List<String> {
        fun src(own: Double?, inherited: Double?): String = when {
            own != null -> "${fmt(own)} (this crate)"
            inherited != null -> "${fmt(inherited)} (crate type)"
            else -> "0 (crate type)"
        }
        return listOf(
            "Fine rotation: " + if (tuning.yawAdjust == 0f) "none" else "${fmt(tuning.yawAdjust.toDouble())}°",
            "Offset x: ${src(tuning.offsetX, model?.offsetX)}, y: ${src(tuning.offsetY, model?.offsetY)}, z: ${src(tuning.offsetZ, model?.offsetZ)}",
            "Scale: " + when {
                tuning.scale != null -> "${fmt(tuning.scale)} (this crate)"
                model != null -> "${fmt(model.scale)} (crate type)"
                else -> "1 (crate type)"
            },
        )
    }
}
