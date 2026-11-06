package com.liam.joshymc.crate

/**
 * Custom crate chest models (issue #1389), the Bukkit-free part: the `crates.custom-models`
 * settings in config.yml and the rules that place a model over a crate block.
 *
 * A model never replaces the crate block. The block stays exactly as it is (so every click,
 * key check, preview, animation and permission path is untouched, and Bedrock / pack-less
 * players simply see it), and an ItemDisplay drawn over it shows the model to players who
 * can render it.
 */
object CrateModels {

    /** Bundled default: the model a crate id uses when its entry gives no `model`. */
    const val DEFAULT_NAMESPACE = "joshymc"

    /** Most a model may be scaled (bigger hides neighbouring blocks); least so it still covers a chest. */
    const val MIN_SCALE = 0.5
    const val MAX_SCALE = 2.0

    /** Furthest a model may be offset from its block, in blocks, on any axis. */
    const val MAX_OFFSET = 1.0

    private val MODEL_KEY = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")

    /** `rotation`: `auto` follows the crate block's facing; a number fixes the direction the model's front faces. */
    sealed interface Rotation {
        data object Auto : Rotation
        /** Facing in Minecraft yaw degrees: 0 = front faces south, 90 = west, 180 = north, 270 = east. */
        data class Fixed(val degrees: Float) : Rotation
    }

    data class Model(
        val crateId: String,
        val enabled: Boolean,
        /** Item model key, e.g. `joshymc:cabin_crate` (assets/joshymc/items/cabin_crate.json). */
        val model: String,
        val offsetX: Double,
        val offsetY: Double,
        val offsetZ: Double,
        val rotation: Rotation,
        val scale: Double,
    )

    data class Settings(
        val enabled: Boolean,
        /** Gamemode ids (ServerGamemode.id) whose worlds show models. Survival only by default. */
        val gamemodes: Set<String>,
        /** crate id (lower case) -> its model; only entries that parsed cleanly. */
        val models: Map<String, Model>,
        /** One line per entry or value that was refused; those crates keep the vanilla look. */
        val problems: List<String>,
    ) {
        /** The model to draw for [crateId], or null to keep the vanilla crate block. */
        fun modelFor(crateId: String): Model? =
            if (!enabled) null else models[crateId.lowercase()]?.takeIf { it.enabled }

        fun showsIn(gamemodeId: String): Boolean = enabled && gamemodeId.lowercase() in gamemodes

        companion object {
            val DISABLED = Settings(false, emptySet(), emptyMap(), emptyList())
        }
    }

    /**
     * A config section as plain values, so parsing needs no Bukkit: [keys] lists a section's
     * direct children ("" = the root), [value] reads a leaf (null when missing).
     */
    interface Source {
        fun keys(path: String): Set<String>
        fun value(path: String): Any?
    }

    /**
     * Reads `crates.custom-models` (passed as the [source] root). Anything it can't read is a
     * [Settings.problems] line and that crate (or the whole feature, for a bad master switch)
     * keeps the vanilla look: a bad value is never guessed.
     */
    fun parse(source: Source): Settings {
        val problems = mutableListOf<String>()
        val enabled = when (val v = source.value("enabled")) {
            null -> false
            is Boolean -> v
            else -> { problems += "enabled must be true or false (was '$v'); custom crate models are off"; false }
        }
        val gamemodes = when (val v = source.value("gamemodes")) {
            null -> setOf("survival")
            is List<*> -> v.mapNotNull { it?.toString()?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }.toSet()
            else -> { problems += "gamemodes must be a list (was '$v'); using [survival]"; setOf("survival") }
        }
        val models = mutableMapOf<String, Model>()
        for (rawId in source.keys("models").sorted()) {
            val crateId = rawId.lowercase()
            val path = "models.$rawId"
            val entryProblems = mutableListOf<String>()
            val model = parseModel(source, path, crateId, entryProblems)
            if (model == null || entryProblems.isNotEmpty()) {
                problems += entryProblems.map { "models.$rawId: $it (this crate keeps its vanilla look)" }
                continue
            }
            models[crateId] = model
        }
        return Settings(enabled, gamemodes, models, problems)
    }

    private fun parseModel(source: Source, path: String, crateId: String, problems: MutableList<String>): Model? {
        val enabled = when (val v = source.value("$path.enabled")) {
            null -> true
            is Boolean -> v
            else -> { problems += "enabled must be true or false (was '$v')"; return null }
        }
        val modelRaw = source.value("$path.model")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "$DEFAULT_NAMESPACE:${crateId}_crate"
        val model = modelRaw.lowercase()
        if (!MODEL_KEY.matches(model)) problems += "model '$modelRaw' is not a namespaced key like joshymc:cabin_crate"

        fun number(key: String, default: Double, min: Double, max: Double): Double {
            val v = source.value("$path.$key") ?: return default
            val d = (v as? Number)?.toDouble() ?: v.toString().trim().toDoubleOrNull()
            if (d == null || d.isNaN() || d.isInfinite()) { problems += "$key must be a number (was '$v')"; return default }
            if (d < min || d > max) { problems += "$key must be between $min and $max (was $d)"; return default }
            return d
        }
        val ox = number("offset.x", 0.0, -MAX_OFFSET, MAX_OFFSET)
        val oy = number("offset.y", 0.0, -MAX_OFFSET, MAX_OFFSET)
        val oz = number("offset.z", 0.0, -MAX_OFFSET, MAX_OFFSET)
        val scale = number("scale", 1.0, MIN_SCALE, MAX_SCALE)
        val rotation = parseRotation(source.value("$path.rotation"), problems) ?: return null
        return Model(crateId, enabled, model, ox, oy, oz, rotation, scale)
    }

    fun parseRotation(value: Any?, problems: MutableList<String>): Rotation? {
        if (value == null) return Rotation.Auto
        if (value is Number) return Rotation.Fixed(normalize(value.toFloat()))
        val text = value.toString().trim().lowercase()
        if (text == "auto") return Rotation.Auto
        val degrees = text.toFloatOrNull()
        if (degrees == null || degrees.isNaN() || degrees.isInfinite()) {
            problems += "rotation must be 'auto' or a number of degrees (was '$value')"
            return null
        }
        return Rotation.Fixed(normalize(degrees))
    }

    private fun normalize(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

    /** Minecraft yaw a front facing [face] (NORTH/SOUTH/EAST/WEST) looks along, or null for anything else. */
    fun facingYaw(face: String?): Float? = when (face?.uppercase()) {
        "SOUTH" -> 0f
        "WEST" -> 90f
        "NORTH" -> 180f
        "EAST" -> 270f
        else -> null
    }

    /**
     * The ItemDisplay yaw that turns the model's front (+Z) to face [rotation] (or the block's
     * [blockFacing] when auto; south when the block has no horizontal facing). An ItemDisplay
     * draws its item turned half a turn, hence the extra 180 degrees.
     */
    fun displayYaw(rotation: Rotation, blockFacing: String?): Float {
        val facing = when (rotation) {
            is Rotation.Fixed -> rotation.degrees
            Rotation.Auto -> facingYaw(blockFacing) ?: 0f
        }
        return normalize(facing + 180f)
    }

    /**
     * Upward translation that keeps a scaled model's floor on the block's floor (the display
     * sits at the block's centre and scales about it).
     */
    fun floorLift(scale: Double): Double = (scale - 1.0) * 0.5

    /** The bundled pack path an item model key needs, e.g. assets/joshymc/items/cabin_crate.json. */
    fun itemDefinitionPath(model: String): String? {
        if (!MODEL_KEY.matches(model)) return null
        val (ns, path) = model.split(":", limit = 2)
        return "assets/$ns/items/$path.json"
    }

    /** Stable per-location key ("world:x:y:z") stored on each display so stray copies can be matched. */
    fun locationKey(world: String, x: Int, y: Int, z: Int): String = "$world:$x:$y:$z"

    /**
     * Who may see a model: never Bedrock players (Geyser doesn't draw these displays and their
     * pack can't hold Java models, so they keep the real chest), and Java players only once
     * JoshyMC's resource pack has loaded for them, so nobody sees a missing-model cube.
     */
    fun canSee(bedrock: Boolean, packLoaded: Boolean): Boolean = !bedrock && packLoaded
}
