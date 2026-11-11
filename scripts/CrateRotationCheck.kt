// Checks per-location crate facing (issue #1472): the Bukkit-free rules in CrateFacing, how a
// facing turns a crate's model and hitbox through CrateModels.placement, the bundled models'
// front direction, the bundled config, and the wiring in CrateManager / CrateModelDisplays /
// CrateCommand (persistence, permissions, the normal crate flow left alone). The Gradle build
// has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/crate-rotation-check.init.gradle.kts crateRotationCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.crate.CrateFacing
import com.liam.joshymc.crate.CrateFacing.BlockAlign
import com.liam.joshymc.crate.CrateFacing.Choice
import com.liam.joshymc.crate.CrateFacing.Direction
import com.liam.joshymc.crate.CrateFacing.Stored
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

private fun model(rotation: Rotation = Rotation.Auto, scale: Double = 1.0, ox: Double = 0.0, oy: Double = 0.0, oz: Double = 0.0,
                  hitbox: CrateModels.HitboxMode = CrateModels.HitboxMode.AUTO) =
    CrateModels.Model("harvest", true, "joshymc:harvest_chest", ox, oy, oz, rotation, scale, hitbox)

/** Placement of [m] at a location with [facing] (null = automatic), the way CrateModelDisplays.spawn does it. */
private fun place(m: CrateModels.Model, facing: Direction?, blockFacing: String?, bounds: CrateModels.ModelBounds = CrateModels.ModelBounds.FULL_BLOCK) =
    CrateModels.placement(m.copy(rotation = CrateFacing.effectiveRotation(m.rotation, facing)), bounds, blockFacing)

private fun near(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-9

fun main() {
    // ── 1. Every cardinal direction ─────────────────────
    check("four directions", Direction.entries.map { it.name } == listOf("NORTH", "EAST", "SOUTH", "WEST"))
    check("yaws use the rotation: convention", Direction.SOUTH.yaw == 0f && Direction.WEST.yaw == 90f &&
        Direction.NORTH.yaw == 180f && Direction.EAST.yaw == 270f)
    for (d in Direction.entries) {
        check("parse ${d.id}", CrateFacing.parseChoice(d.id) == Choice.Face(d))
        check("parse ${d.name}", CrateFacing.parseChoice(d.name) == Choice.Face(d))
        check("parse ${d.id.first()}", CrateFacing.parseChoice(d.id.take(1)) == Choice.Face(d))
        check("${d.id} is what auto would draw for a block facing ${d.id}",
            CrateModels.displayYaw(CrateFacing.effectiveRotation(Rotation.Auto, d), "NORTH") == CrateModels.displayYaw(Rotation.Auto, d.name))
        check("target ${d.id}", CrateFacing.target(Choice.Face(d), Direction.NORTH) == d)
    }
    check("unknown words refused", CrateFacing.parseChoice("up") == null && CrateFacing.parseChoice("") == null &&
        CrateFacing.parseChoice("northeast") == null && CrateFacing.parseChoice("90") == null)
    check("choices for tab completion", CrateFacing.CHOICES == listOf("north", "east", "south", "west", "auto", "next"))

    // ── 2. Automatic mode = exactly the old behaviour ──
    check("parse auto", CrateFacing.parseChoice("AUTO") == Choice.Auto)
    check("auto clears the facing", CrateFacing.target(Choice.Auto, Direction.EAST) == null)
    check("no facing keeps auto rotation", CrateFacing.effectiveRotation(Rotation.Auto, null) == Rotation.Auto)
    check("no facing keeps fixed config rotation", CrateFacing.effectiveRotation(Rotation.Fixed(90f), null) == Rotation.Fixed(90f))
    for (bf in listOf("NORTH", "SOUTH", "EAST", "WEST", null)) {
        check("auto placement unchanged for block $bf", place(model(), null, bf) == CrateModels.placement(model(), CrateModels.ModelBounds.FULL_BLOCK, bf))
    }
    check("a facing wins over the config's fixed rotation", CrateFacing.effectiveRotation(Rotation.Fixed(90f), Direction.NORTH) == Rotation.Fixed(180f))
    check("a facing wins over the block's facing", place(model(), Direction.EAST, "NORTH").yaw == CrateModels.displayYaw(Rotation.Auto, "EAST"))

    // ── 3. Rotating an existing crate: current direction and quick-rotate order ──
    check("quick-rotate order", Direction.NORTH.next() == Direction.EAST && Direction.EAST.next() == Direction.SOUTH &&
        Direction.SOUTH.next() == Direction.WEST && Direction.WEST.next() == Direction.NORTH)
    check("next from an auto crate starts at what it shows", CrateFacing.target(Choice.Next, CrateFacing.currentDirection(null, Rotation.Auto, "WEST")) == Direction.NORTH)
    check("current = stored facing first", CrateFacing.currentDirection(Direction.EAST, Rotation.Fixed(0f), "NORTH") == Direction.EAST)
    check("current = config fixed rotation next", CrateFacing.currentDirection(null, Rotation.Fixed(180f), "SOUTH") == Direction.NORTH)
    check("current = block facing for auto", CrateFacing.currentDirection(null, Rotation.Auto, "east") == Direction.EAST)
    check("current = south for blocks without a facing", CrateFacing.currentDirection(null, Rotation.Auto, "UP") == Direction.SOUTH &&
        CrateFacing.currentDirection(null, null, null) == Direction.SOUTH)
    check("odd config degrees snap to the nearest side", CrateFacing.nearest(100f) == Direction.WEST && CrateFacing.nearest(359f) == Direction.SOUTH &&
        CrateFacing.nearest(-90f) == Direction.EAST && CrateFacing.nearest(250f) == Direction.EAST)

    // ── 4-8. Persistence and migration (stored column values) ──
    check("NULL = automatic (every pre-#1472 row)", CrateFacing.parseStored(null) == Stored.Auto)
    check("blank = automatic", CrateFacing.parseStored("  ") == Stored.Auto)
    for (d in Direction.entries) {
        check("${d.name} round-trips through the column", CrateFacing.parseStored(CrateFacing.storedValue(d)) == Stored.Face(d))
        check("${d.name} stored in any case", CrateFacing.parseStored(d.id) == Stored.Face(d))
    }
    check("automatic is stored as NULL", CrateFacing.storedValue(null) == null)
    check("invalid stored values are reported, never guessed", CrateFacing.parseStored("UP") == Stored.Invalid("UP") &&
        CrateFacing.parseStored("sideways") is Stored.Invalid)

    // ── 9. Model alignment: the front turns exactly, nothing mirrored, scale/offset kept ──
    val asym = CrateModels.ModelBounds(1.0, 0.0, 1.0, 15.0, 14.0, 22.0) // reaches 6/16 past the front (+z)
    for (d in Direction.entries) {
        val p = place(model(), d, null, asym)
        val v = p.visual
        val front = when (d) {
            Direction.SOUTH -> near(v.maxZ, 1.375) && near(v.minZ, 1.0 / 16)
            Direction.NORTH -> near(v.minZ, -0.375) && near(v.maxZ, 15.0 / 16)
            Direction.EAST -> near(v.maxX, 1.375) && near(v.minX, 1.0 / 16)
            Direction.WEST -> near(v.minX, -0.375) && near(v.maxX, 15.0 / 16)
        }
        check("front sticks out to the ${d.id}", front)
        check("${d.id}: right way up (floor on the floor, height kept)", near(v.minY, 0.0) && near(v.maxY, 14.0 / 16))
        check("${d.id}: centred on the block", near(p.displayX, 0.5) && near(p.displayZ, 0.5) && near(p.displayY, 0.5))
        val scaled = place(model(scale = 1.5, ox = 0.25, oy = 0.1, oz = -0.25), d, "NORTH")
        check("${d.id}: offset kept", near(scaled.displayX, 0.75) && near(scaled.displayY, 0.6) && near(scaled.displayZ, 0.25))
        check("${d.id}: scale and floor lift kept", near(scaled.lift, 0.25) && near(scaled.visual.maxY - scaled.visual.minY, 1.5))
        check("${d.id}: yaw is a right angle", p.yaw % 90f == 0f)
    }
    check("four directions give four different yaws", Direction.entries.map { place(model(), it, null).yaw }.toSet().size == 4)

    // ── 10. Hitbox alignment follows the facing ──
    for (d in Direction.entries) {
        val p = place(model(hitbox = CrateModels.HitboxMode.AUTO), d, null, asym)
        val h = p.hitbox
        check("${d.id}: oversized front gets a hitbox", h != null)
        h!!
        val (cx, cz) = (p.visual.minX + p.visual.maxX) / 2 to (p.visual.minZ + p.visual.maxZ) / 2
        check("${d.id}: hitbox centred on the turned model", near(h.x, cx) && near(h.z, cz))
        check("${d.id}: hitbox covers the turned front", h.box.minX <= p.visual.minX + 1e-9 && h.box.maxX >= p.visual.maxX - 1e-9 &&
            h.box.minZ <= p.visual.minZ + 1e-9 && h.box.maxZ >= p.visual.maxZ - 1e-9)
    }
    check("hitbox off stays off when turned", place(model(hitbox = CrateModels.HitboxMode.OFF), Direction.WEST, null, asym).hitbox == null)
    check("a fitting model gets no hitbox in any direction", Direction.entries.all { place(model(), it, null).hitbox == null })

    // ── Vanilla block alignment ─────────────────────────
    val horizontal = setOf("NORTH", "SOUTH", "EAST", "WEST")
    check("single chest turns", CrateFacing.blockAlign(Direction.EAST, true, horizontal, "NORTH", "SINGLE") == BlockAlign.ROTATED)
    check("double chest halves never turn", CrateFacing.blockAlign(Direction.EAST, true, horizontal, "NORTH", "LEFT") == BlockAlign.DOUBLE_CHEST &&
        CrateFacing.blockAlign(Direction.EAST, true, horizontal, "NORTH", "RIGHT") == BlockAlign.DOUBLE_CHEST)
    check("already facing = nothing to do", CrateFacing.blockAlign(Direction.NORTH, true, horizontal, "NORTH", "SINGLE") == BlockAlign.ALREADY)
    check("rotate-block off = model only", CrateFacing.blockAlign(Direction.EAST, false, horizontal, "NORTH", null) == BlockAlign.DISABLED)
    check("non-directional block = model only", CrateFacing.blockAlign(Direction.EAST, true, emptySet(), null, null) == BlockAlign.NO_FACING)
    check("barrel (six faces) turns", CrateFacing.blockAlign(Direction.WEST, true, horizontal + setOf("UP", "DOWN"), "UP", null) == BlockAlign.ROTATED)
    check("auto never touches the block", CrateFacing.blockAlign(null, true, horizontal, "NORTH", "SINGLE") == BlockAlign.NOT_REQUESTED)

    // ── 11-12. Quick rotation: admins in rotate mode only, never the normal flow ──
    fun quick(feature: Boolean = true, admin: Boolean = true, mode: Boolean = true, sneak: Boolean = true, right: Boolean = true, empty: Boolean = true) =
        CrateFacing.isQuickRotate(feature, admin, mode, sneak, right, empty)
    check("admin in rotate mode, sneaking, empty hand = rotate", quick())
    check("non-admins never rotate", !quick(admin = false))
    check("admins outside rotate mode keep the preview", !quick(mode = false))
    check("not sneaking = normal click", !quick(sneak = false))
    check("holding a key (or anything) = normal flow", !quick(empty = false))
    check("left-click = preview", !quick(right = false))
    check("quick-rotate can be turned off", !quick(feature = false))

    // ── 16. Every bundled crate model faces +Z (the convention displayYaw assumes) ──
    val config = File("src/main/resources/config.yml").readText()
    val section = config.substringAfter("  custom-models:")
    val keys = Regex("model: \"joshymc:([a-z0-9_]+)\"").findAll(section.substringBefore("monthly:")).map { it.groupValues[1] }.toSet()
    check("bundled config lists crate models", keys.isNotEmpty())
    val shared = File("resourcepack/art/items/_crate_chest.py").readText()
    check("shared crate chest: front (lock) faces +Z", "the front (lock) faces +Z" in shared)
    val art = File("resourcepack/art/items").listFiles { f -> f.name.endsWith("_chest.py") || f.name.endsWith("_crate.py") }!!
        .filter { !it.name.startsWith("_") && it.name != "overflowing_treasure_chest.py" }
    check("crate model art modules found", art.size >= keys.size)
    for (f in art) check("${f.name} is built on _crate_chest (front +Z)", "_crate_chest" in f.readText())
    for (k in keys) check("configured model $k has a crate art module", art.any { it.name == "$k.py" })

    // ── Bundled config ──────────────────────────────────
    check("config: rotation section", "\n  rotation:\n" in config && "    rotate-block: true" in config && "    quick-rotate: true" in config)

    // ── Wiring ──────────────────────────────────────────
    val manager = File("src/main/kotlin/com/liam/joshymc/manager/CrateManager.kt").readText()
    check("migration adds the facing column only when missing",
        "PRAGMA table_info(crate_locations)" in manager && "if (\"facing\" !in locationColumns)" in manager &&
            "ALTER TABLE crate_locations ADD COLUMN facing TEXT" in manager)
    check("one crate location table (no second store)", Regex("CREATE TABLE IF NOT EXISTS crate_loc").findAll(manager).count() == 1)
    check("locations load their facing", "SELECT world, x, y, z, crate_type, facing, " in manager)
    val setFacing = manager.substringAfter("fun setCrateFacing(").substringBefore("private fun alignBlock(")
    check("facing saved on that row only", "UPDATE crate_locations SET facing = ? WHERE world = ? AND x = ? AND y = ? AND z = ?" in setFacing)
    check("facing change redraws only that location", "refreshModel(block)" in setFacing && "refreshAll" !in setFacing && "restartModels" !in setFacing)
    check("facing lookup is exact (never the neighbour fallback)", "crateLocations.indexOfFirst { it.isAt(block) }" in setFacing)
    check("re-registering keeps the facing", manager.substringAfter("fun setCrateLocation(").substringBefore("fun restartModels(").contains("keptFacing"))
    val align = manager.substringAfter("private fun alignBlock(").substringBefore("/** Admins who turned on")
    check("block turned without physics", "block.setBlockData(turned, false)" in align)
    check("block keeps its type (clone of its own data)", "data.clone()" in align && "setType" !in align)
    val click = manager.substringAfter("private fun handleCrateClick(").substringBefore("private fun debounced(")
    check("quick rotate only on right-click, before the normal flow", click.trimStart().lines().take(4).joinToString("\n").contains("if (!leftClick && tryQuickRotate(player, block)) return"))
    val quickFn = manager.substringAfter("private fun tryQuickRotate(").substringBefore("/** Snapshot of every")
    check("quick rotate needs the admin permission", "hasPermission(\"joshymc.crate.admin\")" in quickFn)
    check("quick rotate only for players in rotate mode", quickFn.trimStart().startsWith("player: Player, block: Block): Boolean {\n        if (player.uniqueId !in rotateMode) return false"))
    check("quick rotate never touches keys or rewards", listOf("consumeOneKey", "beginOpening", "massOpen", "openPreview", "giveKey").none { it in quickFn })
    check("rotate mode cleared on quit", "rotateMode.remove(event.player.uniqueId)" in manager)
    check("crate key and reward paths untouched", "if (player.isSneaking && isKey(itemInHand, crateType)) {" in click && "consumeOneKey(player)" in click)

    val displays = File("src/main/kotlin/com/liam/joshymc/crate/CrateModelDisplays.kt").readText()
    check("displays draw the location's facing", "CrateFacing.effectiveRotation(model.rotation, loc.facing)" in displays)
    check("displays still never change blocks", "setBlockData" !in displays && ".setType(" !in displays)
    check("one display per location: spawn removes the old one first", displays.substringAfter("private fun spawn(").contains("remove(key)\n        removeTaggedNear("))
    check("facing comes from the location list read on every spawn (chunk load, restart, refresh, monthly)",
        displays.substringAfter("fun onChunkLoad(").contains("spawn(loc)") && displays.substringAfter("fun refreshAll(").contains("spawn(loc)"))

    val command = File("src/main/kotlin/com/liam/joshymc/command/CrateCommand.kt").readText()
    val rotate = command.substringAfter("private fun handleRotate(").substringBefore("private fun sendUsage(")
    check("/crate rotate is wired", "\"rotate\" -> handleRotate(sender, args)" in command)
    check("/crate rotate needs joshymc.crate.admin first", rotate.trimStart().lines().drop(1).first().contains("if (!sender.hasPermission(\"joshymc.crate.admin\"))"))
    check("/crate rotate targets an exact registered location in range", "getTargetBlockExact(ROTATE_RANGE)" in rotate && "crateManager.getLocationAt(block)" in rotate)
    check("rotate range is reasonable", Regex("ROTATE_RANGE = (\\d+)").find(command)!!.groupValues[1].toInt() in 4..8)
    check("tab completion offered to admins only", command.contains("\"rotate\" ->\n                    if (sender.hasPermission(\"joshymc.crate.admin\"))"))

    val docs = File("docs/crate-models.md").readText()
    check("docs cover /crate rotate", "/crate rotate" in docs && "rotate mode" in docs && "rotate-block" in docs)

    println("All $checks crate rotation checks passed.")
}
