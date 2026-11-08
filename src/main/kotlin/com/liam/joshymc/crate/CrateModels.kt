package com.liam.joshymc.crate

import java.time.DateTimeException
import java.time.Instant
import java.time.Month
import java.time.ZoneId

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

    /**
     * `hitbox` (issue #1451): whether an Interaction entity is added over the model so clicks
     * on the parts drawn outside the crate block still reach the crate.
     */
    enum class HitboxMode {
        /** Only when the model reaches more than [OVERSIZE_TOLERANCE] past its block. */
        AUTO,
        /** Always (even a model that fits its block). */
        ON,
        /** Never: only the crate block itself takes clicks. */
        OFF,
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
        val hitbox: HitboxMode = HitboxMode.AUTO,
    )

    data class Settings(
        val enabled: Boolean,
        /** Gamemode ids (ServerGamemode.id) whose worlds show models. Survival only by default. */
        val gamemodes: Set<String>,
        /** crate id (lower case) -> its model; only entries that parsed cleanly. */
        val models: Map<String, Model>,
        /** One line per entry or value that was refused; those crates keep the vanilla look. */
        val problems: List<String>,
        /** `monthly` (issue #1460): which crates swap their model each calendar month. */
        val monthly: Monthly = Monthly.DISABLED,
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
        val monthly = parseMonthly(source, problems)
        return Settings(enabled, gamemodes, models, problems, monthly)
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
        val hitbox = parseHitbox(source.value("$path.hitbox"), problems) ?: return null
        return Model(crateId, enabled, model, ox, oy, oz, rotation, scale, hitbox)
    }

    /** `hitbox`: auto (default), on/true, off/false. Anything else is refused, never guessed. */
    fun parseHitbox(value: Any?, problems: MutableList<String>): HitboxMode? {
        if (value == null) return HitboxMode.AUTO
        if (value is Boolean) return if (value) HitboxMode.ON else HitboxMode.OFF
        return when (value.toString().trim().lowercase()) {
            "auto" -> HitboxMode.AUTO
            "on", "true" -> HitboxMode.ON
            "off", "false" -> HitboxMode.OFF
            else -> { problems += "hitbox must be auto, on or off (was '$value')"; null }
        }
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

    // ── Placement and hitboxes (issue #1451) ───────────────────────────

    /** How far (blocks) a model may reach past its block before `hitbox: auto` adds a hitbox; lips and latches don't count. */
    const val OVERSIZE_TOLERANCE = 0.25

    /** Most a player's eye may be from a crate hitbox for a click to count (vanilla entity reach plus the server's slack). */
    const val MAX_REACH = 6.0

    /**
     * How far (blocks) from the crate block's minimum corner a display or hitbox entity can stand
     * at most: offsets are at most [MAX_OFFSET] from the centre, and a hitbox centre stays within
     * the model. Chunks this far around the block may hold one of its entities.
     */
    private const val ENTITY_REACH = 2.0

    /** A model's element bounds in model units (1/16 block; the block cell is 0..16 on every axis). */
    data class ModelBounds(val minX: Double, val minY: Double, val minZ: Double, val maxX: Double, val maxY: Double, val maxZ: Double) {
        companion object {
            /** Used when a model's elements can't be read: assume it fills its block exactly. */
            val FULL_BLOCK = ModelBounds(0.0, 0.0, 0.0, 16.0, 16.0, 16.0)
        }
    }

    /** An axis-aligned box in blocks, relative to the crate block's minimum corner (the block is 0..1 on each axis). */
    data class Box(val minX: Double, val minY: Double, val minZ: Double, val maxX: Double, val maxY: Double, val maxZ: Double) {
        /** True when the box shares some volume with the crate block. */
        fun overlapsBlock(): Boolean = minX < 1.0 && maxX > 0.0 && minY < 1.0 && maxY > 0.0 && minZ < 1.0 && maxZ > 0.0

        /** Furthest the box reaches past the crate block on any side, in blocks (0 when it fits). */
        fun overhang(): Double = maxOf(maxOf(-minX, -minY, -minZ), maxOf(maxX - 1.0, maxY - 1.0, maxZ - 1.0), 0.0)
    }

    /**
     * An Interaction hitbox, relative to the crate block's minimum corner. Interaction entities
     * are anchored at their bottom centre: [x]/[z] are the centre, [y] the bottom.
     */
    data class Hitbox(val x: Double, val y: Double, val z: Double, val width: Double, val height: Double) {
        val box: Box get() = Box(x - width / 2, y, z - width / 2, x + width / 2, y + height, z + width / 2)
    }

    /**
     * Where one crate's model goes, relative to the crate block's minimum corner: the display
     * position (the model scales about it), its yaw, the upward translation that keeps the
     * model's floor on the block floor, the box the model visibly fills, and the hitbox (if any).
     * [problem] set = the model would not sit on its block and is not drawn.
     */
    data class Placement(
        val displayX: Double,
        val displayY: Double,
        val displayZ: Double,
        val yaw: Float,
        val lift: Double,
        val visual: Box,
        val hitbox: Hitbox?,
        val problem: String?,
    )

    /**
     * Places [model] (whose elements fill [bounds]) on a crate block facing [blockFacing].
     * Mirrors how Paper draws the display: the item model is centred on the entity, turned half
     * a turn, scaled, lifted by [floorLift], then turned by the entity yaw.
     */
    fun placement(model: Model, bounds: ModelBounds, blockFacing: String?): Placement {
        val yaw = displayYaw(model.rotation, blockFacing)
        val lift = floorLift(model.scale)
        val dx = 0.5 + model.offsetX
        val dy = 0.5 + model.offsetY
        val dz = 0.5 + model.offsetZ
        val visual = visualBox(bounds, model.scale, dx, dy + lift, dz, yaw)
        val problem = if (!visual.overlapsBlock()) {
            "the model would be drawn beside its crate block, not on it (check offset and scale)"
        } else null
        val hitbox = if (problem == null && needsHitbox(model.hitbox, visual)) hitboxFor(visual) else null
        return Placement(dx, dy, dz, yaw, lift, visual, hitbox, problem)
    }

    /** True when [mode] asks for a hitbox over a model that visibly fills [visual]. */
    fun needsHitbox(mode: HitboxMode, visual: Box): Boolean = when (mode) {
        HitboxMode.OFF -> false
        HitboxMode.ON -> true
        HitboxMode.AUTO -> visual.overhang() > OVERSIZE_TOLERANCE + 1e-9
    }

    /** The smallest square-footed Interaction box around [visual] (Interaction boxes are always square). */
    fun hitboxFor(visual: Box): Hitbox {
        val width = maxOf(visual.maxX - visual.minX, visual.maxZ - visual.minZ)
        return Hitbox(
            x = (visual.minX + visual.maxX) / 2,
            y = visual.minY,
            z = (visual.minZ + visual.maxZ) / 2,
            width = width,
            height = visual.maxY - visual.minY,
        )
    }

    /**
     * The box (relative to the block corner) a model fills when its display stands at
     * ([cx], [cy], [cz]) with [yaw] and [scale]. Paper turns a display by `-yaw` and an item
     * display's item by another half turn, so the model's x/z are turned by `180 - yaw` degrees.
     */
    fun visualBox(bounds: ModelBounds, scale: Double, cx: Double, cy: Double, cz: Double, yaw: Float): Box {
        val theta = Math.toRadians(180.0 - yaw)
        val c = clean(kotlin.math.cos(theta))
        val s = clean(kotlin.math.sin(theta))
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minZ = Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (x in doubleArrayOf(bounds.minX, bounds.maxX)) for (z in doubleArrayOf(bounds.minZ, bounds.maxZ)) {
            val qx = x / 16.0 - 0.5
            val qz = z / 16.0 - 0.5
            val rx = c * qx + s * qz
            val rz = -s * qx + c * qz
            minX = minOf(minX, rx); maxX = maxOf(maxX, rx)
            minZ = minOf(minZ, rz); maxZ = maxOf(maxZ, rz)
        }
        return Box(
            cx + scale * minX, cy + scale * (bounds.minY / 16.0 - 0.5), cz + scale * minZ,
            cx + scale * maxX, cy + scale * (bounds.maxY / 16.0 - 0.5), cz + scale * maxZ,
        )
    }

    /** Snaps floating-point noise (cos 90 = 6e-17) to exact values so right angles stay exact. */
    private fun clean(v: Double): Double {
        val r = kotlin.math.round(v)
        return if (kotlin.math.abs(v - r) < 1e-12) r else v
    }

    /** The chunk coordinate of a point [relative] blocks from block coordinate [block]. */
    fun chunkOf(block: Int, relative: Double): Int = kotlin.math.floor(block + relative).toInt() shr 4

    /** One chunk as a single number, for sets. */
    fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)

    /**
     * Every chunk one placement's entities stand in (the crate block's own chunk, the display's
     * and the hitbox's). A model is only drawn while all of them are loaded, and removed when
     * any of them unloads, so an offset across a chunk border never loads or strands a chunk.
     */
    fun requiredChunks(blockX: Int, blockZ: Int, placement: Placement): Set<Long> {
        val out = linkedSetOf(chunkKey(blockX shr 4, blockZ shr 4))
        out += chunkKey(chunkOf(blockX, placement.displayX), chunkOf(blockZ, placement.displayZ))
        placement.hitbox?.let { out += chunkKey(chunkOf(blockX, it.x), chunkOf(blockZ, it.z)) }
        return out
    }

    /** True when chunk ([cx], [cz]) may hold an entity of the crate at block ([blockX], [blockZ]). */
    fun mayHoldEntities(blockX: Int, blockZ: Int, cx: Int, cz: Int): Boolean =
        cx in chunkOf(blockX, -ENTITY_REACH)..chunkOf(blockX, 1.0 + ENTITY_REACH) &&
            cz in chunkOf(blockZ, -ENTITY_REACH)..chunkOf(blockZ, 1.0 + ENTITY_REACH)

    /** Squared distance from a point to [box] (0 inside it); both in the same coordinates. */
    fun distanceSqToBox(px: Double, py: Double, pz: Double, box: Box): Double {
        val dx = maxOf(box.minX - px, 0.0, px - box.maxX)
        val dy = maxOf(box.minY - py, 0.0, py - box.maxY)
        val dz = maxOf(box.minZ - pz, 0.0, pz - box.maxZ)
        return dx * dx + dy * dy + dz * dz
    }

    // ── Monthly rotation (issue #1460) ─────────────────────────────────

    /** Timezone the calendar month is read in when `monthly.timezone` is missing. */
    const val DEFAULT_TIMEZONE = "America/New_York"

    /** The month ids `monthly.models` accepts: january .. december. */
    val MONTH_IDS: List<String> = Month.entries.map { it.name.lowercase() }

    /**
     * Longest the rotation waits between two month checks, so a lagging tick clock or a changed
     * system clock is still caught within the hour.
     */
    const val MAX_CHECK_DELAY_MILLIS = 60L * 60L * 1000L

    /**
     * `crates.custom-models.monthly`. Rotation only changes which item model a crate's display
     * holds: the crate id, keys, rewards and locations are never touched, and only the crates
     * listed in [crates] rotate.
     */
    data class Monthly(
        val enabled: Boolean,
        val zone: ZoneId,
        /** Crate ids (lower case, as written; aliases are resolved by the caller) that rotate. */
        val crates: Set<String>,
        /** Month -> item model key. A month without an entry falls back like a missing model. */
        val models: Map<Month, String>,
    ) {
        /** True when rotation is on and has at least one crate to rotate. */
        val active: Boolean get() = enabled && crates.isNotEmpty()

        fun modelFor(month: Month): String? = models[month]

        companion object {
            val DISABLED = Monthly(false, ZoneId.of(DEFAULT_TIMEZONE), emptySet(), emptyMap())
        }
    }

    /** Where a crate's drawn model came from. */
    enum class ModelSource {
        /** This month's monthly model. */
        MONTHLY,
        /** The crate's own `models.<id>.model` (a crate that doesn't rotate). */
        STATIC,
        /** A rotating crate whose month model is missing: its own static model instead. */
        STATIC_FALLBACK,
        /** Nothing to draw: the crate keeps the vanilla block. */
        NONE,
    }

    /** The model to draw for one crate in one month, with why. [model] is null for [ModelSource.NONE]. */
    data class Resolved(val model: Model?, val source: ModelSource, val note: String?)

    private fun parseMonthly(source: Source, problems: MutableList<String>): Monthly {
        val p = "monthly"
        var enabled = when (val v = source.value("$p.enabled")) {
            null -> false
            is Boolean -> v
            else -> { problems += "$p.enabled must be true or false (was '$v'); monthly rotation is off"; false }
        }
        val zoneText = source.value("$p.timezone")?.toString()?.trim().orEmpty()
        var zone = ZoneId.of(DEFAULT_TIMEZONE)
        if (zoneText.isNotEmpty()) {
            val parsed = parseZone(zoneText)
            if (parsed == null) {
                problems += "$p.timezone '$zoneText' is not a timezone like America/New_York; monthly rotation is off"
                enabled = false
            } else {
                zone = parsed
            }
        }
        val crates = when (val v = source.value("$p.crates")) {
            null -> emptySet()
            is List<*> -> v.mapNotNull { it?.toString()?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }.toSet()
            else -> { problems += "$p.crates must be a list of crate ids (was '$v'); no crate rotates"; emptySet() }
        }
        val models = sortedMapOf<Month, String>()
        for (raw in source.keys("$p.models")) {
            val month = monthOf(raw)
            if (month == null) {
                problems += "$p.models.$raw is not a month (use ${MONTH_IDS.first()} .. ${MONTH_IDS.last()}); ignored"
                continue
            }
            val text = source.value("$p.models.$raw")?.toString()?.trim().orEmpty()
            if (text.isEmpty()) continue
            val key = text.lowercase()
            if (!MODEL_KEY.matches(key)) {
                problems += "$p.models.$raw: model '$text' is not a namespaced key like joshymc:january_chest (that month uses the fallback)"
                continue
            }
            models[month] = key
        }
        return Monthly(enabled, zone, crates, models)
    }

    /** A month id (`january`, case-insensitive) or null. */
    fun monthOf(id: String): Month? = Month.entries.firstOrNull { it.name.equals(id.trim(), ignoreCase = true) }

    fun monthId(month: Month): String = month.name.lowercase()

    /** True for a well-formed namespaced item model key (`joshymc:cabin_chest`). */
    fun isModelKey(key: String): Boolean = MODEL_KEY.matches(key)

    /** An IANA zone id (`America/New_York`, `UTC`, `Europe/London`) or null; never guessed. */
    fun parseZone(text: String): ZoneId? = try {
        ZoneId.of(text.trim())
    } catch (e: DateTimeException) {
        null
    }

    /** The calendar month [now] falls in, read in [zone]. */
    fun monthAt(now: Instant, zone: ZoneId): Month = now.atZone(zone).month

    /** The first instant of the month after the one [now] falls in, in [zone] (handles DST and the year end). */
    fun nextMonthStart(now: Instant, zone: ZoneId): Instant =
        now.atZone(zone).toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant()

    /**
     * How long the rotation waits before checking the month again: until just past the next
     * month start, but never longer than [MAX_CHECK_DELAY_MILLIS] (and at least one second).
     */
    fun checkDelayMillis(now: Instant, zone: ZoneId): Long {
        val untilNext = nextMonthStart(now, zone).toEpochMilli() - now.toEpochMilli() + 1000L
        return untilNext.coerceIn(1000L, MAX_CHECK_DELAY_MILLIS)
    }

    /** Server ticks for [millis] (rounded up), for the scheduler. */
    fun ticksFor(millis: Long): Long = (millis + 49L) / 50L

    /** True when [crateId] rotates: rotation on and the id listed (directly, or through [canonical], which resolves aliases). */
    fun rotates(settings: Settings, crateId: String, canonical: (String) -> String = { it }): Boolean {
        if (!settings.monthly.active) return false
        val id = crateId.lowercase()
        return settings.monthly.crates.any { it == id || canonical(it).lowercase() == id }
    }

    /**
     * The model [crateId] should show in [month]. [available] says whether an item model key is
     * in the bundled pack; a key that isn't is never drawn.
     *
     * - Custom models off, or the crate's entry disabled: nothing (vanilla).
     * - A rotating crate: this month's model if it is set and in the pack, drawn with the crate's
     *   own entry's offset/rotation/scale/hitbox (defaults when it has none); otherwise its
     *   static model if that is in the pack; otherwise vanilla.
     * - Any other crate: exactly the static behaviour from before rotation existed.
     */
    fun resolve(
        settings: Settings,
        crateId: String,
        month: Month,
        available: (String) -> Boolean,
        canonical: (String) -> String = { it },
    ): Resolved {
        if (!settings.enabled) return Resolved(null, ModelSource.NONE, "custom crate models are off")
        val id = crateId.lowercase()
        val entry = settings.models[id]
        if (entry != null && !entry.enabled) return Resolved(null, ModelSource.NONE, "disabled")
        if (!rotates(settings, id, canonical)) {
            if (entry == null) return Resolved(null, ModelSource.NONE, "no model configured")
            if (!available(entry.model)) return Resolved(null, ModelSource.NONE, "${entry.model} is not in the resource pack")
            return Resolved(entry, ModelSource.STATIC, null)
        }
        val monthKey = settings.monthly.modelFor(month)
        if (monthKey != null && available(monthKey)) {
            val base = entry ?: Model(id, true, monthKey, 0.0, 0.0, 0.0, Rotation.Auto, 1.0, HitboxMode.AUTO)
            return Resolved(base.copy(model = monthKey), ModelSource.MONTHLY, null)
        }
        val why = if (monthKey == null) "no ${monthId(month)} model set" else "$monthKey is not in the resource pack"
        if (entry != null && available(entry.model)) return Resolved(entry, ModelSource.STATIC_FALLBACK, why)
        return Resolved(null, ModelSource.NONE, "$why and no static model to fall back to")
    }
}
