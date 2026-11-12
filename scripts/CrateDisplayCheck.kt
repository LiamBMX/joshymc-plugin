// Checks per-location crate display placement (issue #1517): the Bukkit-free rules in
// CrateDisplayTuning (fine rotation, offsets, scale, reset, stored columns), how they combine
// with the issue #1472 facing in CrateModels.placement for every bundled crate model (the five
// permanent ones and the twelve monthly ones), hitboxes of nearby crates, and the wiring in
// CrateManager / CrateModelDisplays / CrateCommand (persistence, permissions, no reward or key
// path touched, entities still non-persistent and hidden by default). The Gradle build has no
// test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/crate-display-check.init.gradle.kts crateDisplayCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.crate.CrateDisplayTuning
import com.liam.joshymc.crate.CrateDisplayTuning.Parsed
import com.liam.joshymc.crate.CrateDisplayTuning.Tuning
import com.liam.joshymc.crate.CrateFacing.Direction
import com.liam.joshymc.crate.CrateModelBounds
import com.liam.joshymc.crate.CrateModels
import com.liam.joshymc.crate.CrateModels.Rotation
import java.io.File
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private fun near(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-9
private fun nearF(a: Float, b: Float) = kotlin.math.abs(a - b) < 1e-4

private fun model(key: String = "joshymc:harvest_chest", rotation: Rotation = Rotation.Auto, scale: Double = 1.0,
                  ox: Double = 0.0, oy: Double = 0.0, oz: Double = 0.0) =
    CrateModels.Model("harvest", true, key, ox, oy, oz, rotation, scale, CrateModels.HitboxMode.AUTO)

/** The way CrateModelDisplays.placementFor draws a location. */
private fun place(m: CrateModels.Model, facing: Direction?, tuning: Tuning, blockFacing: String?,
                  bounds: CrateModels.ModelBounds = CrateModels.ModelBounds.FULL_BLOCK): CrateModels.Placement {
    val drawn = CrateDisplayTuning.apply(m, facing, tuning, blockFacing)
    return CrateModels.placement(drawn, bounds, blockFacing)
}

private fun boundsOf(path: String): CrateModels.ModelBounds? {
    val file = File("resourcepack/assets/joshymc/items/$path.json")
    if (!file.exists()) return null
    return CrateModelBounds.union(CrateModelBounds.modelRefs(file.readText()).map { ref ->
        CrateModelBounds.modelPath(ref)?.let { File("resourcepack/$it") }?.takeIf { it.exists() }?.readText()?.let(CrateModelBounds::elementBounds)
    })
}

fun main() {
    val none = CrateDisplayTuning.NONE

    // ── 1-4. North / south / east / west, alone and with a fine rotation ──
    for (d in Direction.entries) {
        val plain = place(model(), d, none, "NORTH")
        check("${d.id}: front faces ${d.id}", nearF(plain.yaw, CrateModels.displayYaw(Rotation.Fixed(d.yaw), null)))
        val tilted = place(model(), d, Tuning(yawAdjust = 15f), "NORTH")
        check("${d.id} + 15°: turned 15° from ${d.id}", nearF(((tilted.yaw - plain.yaw) % 360f + 360f) % 360f, 15f))
        check("${d.id}: turning never moves the model (pivot = block centre)",
            near(tilted.displayX, plain.displayX) && near(tilted.displayZ, plain.displayZ) && near(tilted.displayY, plain.displayY))
        check("${d.id}: still sits on its block", plain.problem == null && tilted.problem == null)
    }
    // Automatic facing + adjustment starts from the block's facing.
    check("auto + adjust uses the block facing", CrateDisplayTuning.adjust(Rotation.Auto, 10f, "EAST") == Rotation.Fixed(280f))
    check("auto + adjust without a block facing starts at south", CrateDisplayTuning.adjust(Rotation.Auto, -10f, null) == Rotation.Fixed(350f))
    check("no adjustment keeps auto as auto", CrateDisplayTuning.adjust(Rotation.Auto, 0f, "EAST") == Rotation.Auto)
    check("adjust wraps", CrateDisplayTuning.adjust(Rotation.Fixed(350f), 20f, null) == Rotation.Fixed(10f))
    check("type rotation + adjust", CrateDisplayTuning.apply(model(rotation = Rotation.Fixed(90f)), null, Tuning(yawAdjust = 5f), null).rotation == Rotation.Fixed(95f))
    check("own facing wins over the type rotation before adjusting",
        CrateDisplayTuning.apply(model(rotation = Rotation.Fixed(90f)), Direction.NORTH, Tuning(yawAdjust = -5f), null).rotation == Rotation.Fixed(175f))

    // ── 8-11. Offsets, scale, reset, inheritance ──
    val offset = CrateDisplayTuning.apply(model(ox = 0.1, oy = 0.2, oz = 0.3), null, Tuning(offsetX = -0.25), null)
    check("x override, y/z inherited", near(offset.offsetX, -0.25) && near(offset.offsetY, 0.2) && near(offset.offsetZ, 0.3))
    val shifted = place(model(), Direction.SOUTH, Tuning(offsetX = 0.25, offsetY = 0.1, offsetZ = -0.125), "SOUTH")
    check("offsets move the display along world axes", near(shifted.displayX, 0.75) && near(shifted.displayY, 0.6) && near(shifted.displayZ, 0.375))
    for (d in Direction.entries) {
        val p = place(model(), d, Tuning(offsetX = 0.25), null)
        check("offsets don't depend on facing (${d.id})", near(p.displayX, 0.75) && near(p.displayZ, 0.5))
    }
    val scaled = CrateDisplayTuning.apply(model(scale = 1.5), null, Tuning(scale = 0.75), null)
    check("scale override", near(scaled.scale, 0.75))
    check("scale inherited", near(CrateDisplayTuning.apply(model(scale = 1.5), null, none, null).scale, 1.5))
    val big = place(model(), Direction.SOUTH, Tuning(scale = 1.5), "SOUTH")
    check("scaled model keeps its floor on the block floor", near(big.visual.minY, 0.0) && near(big.lift, 0.25))
    for (d in Direction.entries) for (bf in listOf(null, "NORTH", "EAST")) {
        val m = model(rotation = Rotation.Fixed(45f), scale = 1.25, ox = 0.1)
        check("reset (NONE) draws exactly like no tuning (${d.id}, $bf)",
            CrateDisplayTuning.apply(m, d, none, bf) == m.copy(rotation = Rotation.Fixed(d.yaw)) &&
                CrateDisplayTuning.apply(m, null, none, bf) == m)
    }
    check("NONE is default", none.isDefault && !Tuning(scale = 1.0).isDefault && Tuning(offsetY = 0.1).hasOffset && !none.hasOffset)
    check("too far an offset is refused before saving", place(model(), null, Tuning(offsetY = 1.0, scale = 0.5), null).problem != null)

    // ── Command parsing ──
    check("rotate degrees", CrateDisplayTuning.parseYawAdjust("22.5") == Parsed.Ok(22.5f) && CrateDisplayTuning.parseYawAdjust("-180") == Parsed.Ok(-180f))
    check("rotate 0/default clears", CrateDisplayTuning.parseYawAdjust("0") == Parsed.Clear && CrateDisplayTuning.parseYawAdjust("default") == Parsed.Clear)
    check("rotate out of range / junk refused", CrateDisplayTuning.parseYawAdjust("181") is Parsed.Bad &&
        CrateDisplayTuning.parseYawAdjust("NaN") is Parsed.Bad && CrateDisplayTuning.parseYawAdjust("north") is Parsed.Bad)
    check("scale bounds", CrateDisplayTuning.parseScale("0.5") == Parsed.Ok(0.5) && CrateDisplayTuning.parseScale("2") == Parsed.Ok(2.0) &&
        CrateDisplayTuning.parseScale("0.49") is Parsed.Bad && CrateDisplayTuning.parseScale("2.01") is Parsed.Bad &&
        CrateDisplayTuning.parseScale("Infinity") is Parsed.Bad && CrateDisplayTuning.parseScale("default") == Parsed.Clear)
    check("offset bounds", CrateDisplayTuning.parseOffset("x", "-1") == Parsed.Ok(-1.0) && CrateDisplayTuning.parseOffset("x", "1.01") is Parsed.Bad)
    val withOff = CrateDisplayTuning.withOffsets(Tuning(offsetX = 0.3, scale = 1.2), "default", "0.1", "-0.2")
    check("offset per axis, default clears one axis, other settings kept",
        withOff == Parsed.Ok(Tuning(offsetX = null, offsetY = 0.1, offsetZ = -0.2, scale = 1.2)))
    check("one bad axis changes nothing", CrateDisplayTuning.withOffsets(none, "0.1", "5", "0") is Parsed.Bad)

    // ── 5-6, 12. Persistence: stored columns round-trip; bad rows are ignored, never rewritten ──
    val t = Tuning(yawAdjust = -12.5f, offsetX = 0.25, offsetY = null, offsetZ = -0.5, scale = 1.25)
    val problems = mutableListOf<String>()
    val back = CrateDisplayTuning.fromStored(CrateDisplayTuning.storedYaw(t), t.offsetX, t.offsetY, t.offsetZ, t.scale, problems)
    check("stored round-trip", back == t && problems.isEmpty())
    check("NULL columns = NONE (older rows)", CrateDisplayTuning.fromStored(null, null, null, null, null, problems) == none && problems.isEmpty())
    check("no yaw adjustment stores NULL", CrateDisplayTuning.storedYaw(none) == null)
    val bad = CrateDisplayTuning.fromStored(400.0, 3.0, 0.1, Double.NaN, 9.0, problems)
    check("out-of-range stored values fall back and are reported", bad == Tuning(offsetY = 0.1) && problems.size == 4)

    // ── Every bundled model (5 permanent + 12 monthly + 3 older) with every facing and tunings ──
    val permanent = listOf("harvest_chest", "stockpile_chest", "homestead_chest", "campfire_chest", "cabin_chest")
    val monthly = CrateModels.MONTH_IDS.map { "${it}_chest" }
    val older = listOf("cabin_crate", "homestead_crate", "campfire_crate")
    val tunings = listOf(none, Tuning(yawAdjust = 30f), Tuning(yawAdjust = -45f, scale = 0.75), Tuning(scale = 1.5),
        Tuning(offsetX = 0.25, offsetY = 0.125, offsetZ = -0.25), Tuning(yawAdjust = 180f, scale = 2.0))
    for (path in permanent + monthly + older) {
        val bounds = boundsOf(path)
        check("$path is in the pack with readable bounds", bounds != null)
        bounds!!
        val m = model("joshymc:$path")
        for (d in listOf<Direction?>(null) + Direction.entries) for (tu in tunings) {
            val p = place(m, d, tu, "SOUTH", bounds)
            check("$path ${d?.id ?: "auto"} $tu sits on its block", p.problem == null)
            check("$path ${d?.id ?: "auto"} $tu keeps its floor", near(p.visual.minY, (bounds.minY / 16.0 - 0.5) * (tu.scale ?: 1.0) + 0.5 + (tu.scale ?: 1.0).let { (it - 1) * 0.5 } + (tu.offsetY ?: 0.0)))
            p.hitbox?.let { h ->
                check("$path hitbox covers the visual box", h.box.minX <= p.visual.minX + 1e-9 && h.box.maxX >= p.visual.maxX - 1e-9 &&
                    h.box.minZ <= p.visual.minZ + 1e-9 && h.box.maxZ >= p.visual.maxZ - 1e-9 && near(h.box.minY, p.visual.minY))
                // The chunk sweep assumes a crate's entities stand within 2 blocks of its corner.
                check("$path hitbox centre stays in the chunk reach", h.x in -2.0..3.0 && h.z in -2.0..3.0)
            }
            check("$path display centre stays in the chunk reach", p.displayX in -2.0..3.0 && p.displayZ in -2.0..3.0)
        }
        // ── 14-15. Dense rows: at the default placement a crate's hitbox never covers its neighbour's centre ──
        for (d in Direction.entries) {
            val p = place(m, d, none, null, bounds)
            p.hitbox?.let { h ->
                val b = h.box
                for ((nx, nz) in listOf(1.5 to 0.5, -0.5 to 0.5, 0.5 to 1.5, 0.5 to -0.5)) {
                    check("$path ${d.id}: hitbox never covers the neighbour at $nx,$nz",
                        !(nx > b.minX && nx < b.maxX && nz > b.minZ && nz < b.maxZ))
                }
            }
        }
    }

    // ── Wiring ──
    val manager = File("src/main/kotlin/com/liam/joshymc/manager/CrateManager.kt").readText()
    for (c in listOf("yaw_adjust", "offset_x", "offset_y", "offset_z", "scale")) {
        check("column $c in the load query", Regex("SELECT world, x, y, z, crate_type, facing, [^\"]*\\b$c\\b").containsMatchIn(manager))
    }
    check("columns added when missing, as REAL", "listOf(\"yaw_adjust\", \"offset_x\", \"offset_y\", \"offset_z\", \"scale\")" in manager &&
        "ADD COLUMN \$column REAL" in manager)
    check("one crate location table (no second store)", Regex("CREATE TABLE IF NOT EXISTS crate_loc").findAll(manager).count() == 1)
    val setLoc = manager.substringAfter("fun setCrateLocation(").substringBefore("fun restartModels(")
    check("re-registering keeps the placement", "keptTuning" in setLoc && "keptTuning.scale" in setLoc)
    val setTuning = manager.substringAfter("fun setCrateTuning(").substringBefore("/** Issue #1517: redraws only")
    check("tuning lookup is exact", "crateLocations.indexOfFirst { it.isAt(block) }" in setTuning)
    check("tuning saved on that row only",
        "UPDATE crate_locations SET yaw_adjust = ?, offset_x = ?, offset_y = ?, offset_z = ?, scale = ? WHERE world = ? AND x = ? AND y = ? AND z = ?" in setTuning)
    check("tuning redraws only that location", "refreshModel(block)" in setTuning && "refreshAll" !in setTuning && "restartModels" !in setTuning)
    check("tuning never touches blocks, keys or rewards",
        listOf("setBlockData", "consumeOneKey", "giveKey", "pending", "reward", "openCrate").none { it in setTuning })
    check("invalid stored values only warn", "Fix it with /crate display." in manager)

    val displays = File("src/main/kotlin/com/liam/joshymc/crate/CrateModelDisplays.kt").readText()
    val spawn = displays.substringAfter("private fun spawn(").substringBefore("/** The crate block's horizontal facing")
    check("spawn draws the location's placement", "placementFor(loc, model, block)" in spawn && "drawn.scale" in spawn)
    check("entities stay non-persistent and hidden by default",
        Regex("isPersistent = false").findAll(spawn).count() == 2 && Regex("isVisibleByDefault = false").findAll(spawn).count() == 2)
    check("one display per location: spawn removes the old one first", "remove(key)\n        removeTaggedNear(" in spawn)
    val preview = displays.substringAfter("fun tuningProblem(").substringBefore("/** Issue #1517: `/crate display` lines")
    check("tuning preview never spawns or loads chunks", "spawn(" !in preview && "isChunkLoaded" in preview && "getChunkAt" !in preview)
    check("placementFor still applies the #1472 facing", "CrateFacing.effectiveRotation(model.rotation, loc.facing)" in displays &&
        "CrateDisplayTuning.apply(faced, null, tuning, facing)" in displays)
    check("removal still scoped to JoshyMC's tag + location key",
        "TAG in entity.scoreboardTags" in displays && "persistentDataContainer.has(locationKeyTag" in displays)

    // ── 25. Opening animation copies the live display's position, yaw and transformation ──
    val animator = File("src/main/kotlin/com/liam/joshymc/crate/CrateOpeningAnimator.kt").readText()
    check("animation copies the permanent display (so tuning carries over)",
        "perm.world.spawn(perm.location, ItemDisplay::class.java)" in animator && "val base = perm.transformation" in animator &&
            "d.setRotation(perm.location.yaw, perm.location.pitch)" in animator)
    check("animation never edits the permanent display's transformation", "perm.transformation =" !in animator)

    // ── 29. Commands ──
    val command = File("src/main/kotlin/com/liam/joshymc/command/CrateCommand.kt").readText()
    val display = command.substringAfter("private fun handleDisplay(").substringBefore("private fun sendUsage(")
    check("/crate display is wired", "\"display\" -> handleDisplay(sender, args)" in command)
    check("/crate display needs joshymc.crate.admin first", display.trimStart().lines().drop(1).first().contains("if (!sender.hasPermission(\"joshymc.crate.admin\"))"))
    check("/crate display targets an exact registered location", "getTargetBlockExact(ROTATE_RANGE)" in display && "crateManager.getLocationAt(block)" in display)
    check("facing goes through the existing /crate rotate", "handleRotate(sender, arrayOf(\"rotate\", dir))" in display)
    check("a placement beside the block is refused before saving",
        display.indexOf("tuningProblem(loc, next)") in 0 until display.indexOf("setCrateTuning(block, next)"))
    check("/crate display never touches keys or rewards", listOf("consumeOneKey", "giveKey", "openCrate", "beginOpening").none { it in display })
    check("display tab completion offered to admins only", command.contains("\"display\" ->\n                    if (sender.hasPermission(\"joshymc.crate.admin\"))"))
    check("joshymc.crate.admin is op-only", Regex("joshymc\\.crate\\.admin:\\s*\\n(\\s+\\w+:.*\\n)*?\\s+default: op").containsMatchIn(File("src/main/resources/plugin.yml").readText()))

    val docs = File("docs/crate-models.md").readText()
    check("docs cover /crate display", "/crate display" in docs && "display offset" in docs && "display reset" in docs)

    println("All $checks crate display checks passed.")
}
