// Checks the custom crate chest models (issue #1389): the Bukkit-free config parser and placement
// rules in CrateModels, the bundled config defaults, and that the crate manager wires the displays
// without touching the crate flow. The Gradle build has no test source set, so it runs through an
// init script, from the repo root:
//
//   ./gradlew -I scripts/crate-model-check.init.gradle.kts crateModelCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.crate.CrateClickGate
import com.liam.joshymc.crate.CrateModelBounds
import com.liam.joshymc.crate.CrateModels
import com.liam.joshymc.crate.CrateModels.Rotation
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

/** A config section as a flat map of dotted paths, like YamlConfiguration's getValues(true). */
private class MapSource(private val values: Map<String, Any?>) : CrateModels.Source {
    override fun keys(path: String): Set<String> {
        val prefix = if (path.isEmpty()) "" else "$path."
        return values.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('.') }.toSet()
    }

    override fun value(path: String): Any? = values[path]
}

private fun parse(vararg pairs: Pair<String, Any?>) = CrateModels.parse(MapSource(mapOf(*pairs)))

fun main() {
    // ── Parsing ─────────────────────────────────────────
    val full = parse(
        "enabled" to true,
        "gamemodes" to listOf("survival"),
        "models.cabin.enabled" to true,
        "models.cabin.model" to "joshymc:cabin_crate",
        "models.cabin.offset.x" to 0.0, "models.cabin.offset.y" to 0.1, "models.cabin.offset.z" to 0,
        "models.cabin.rotation" to "auto",
        "models.cabin.scale" to 1.0,
        "models.homestead.model" to "joshymc:homestead_crate",
        "models.campfire.rotation" to 90,
    )
    check("clean config has no problems", full.problems.isEmpty())
    check("three models parsed", full.models.keys == setOf("cabin", "homestead", "campfire"))
    check("cabin model key kept", full.modelFor("cabin")?.model == "joshymc:cabin_crate")
    check("offset y read", full.modelFor("cabin")?.offsetY == 0.1)
    check("integer offsets accepted", full.modelFor("cabin")?.offsetZ == 0.0)
    check("missing model key defaults to joshymc:<id>_crate", full.modelFor("campfire")?.model == "joshymc:campfire_crate")
    check("missing entry enabled defaults to true", full.modelFor("homestead")?.enabled == true)
    check("numeric rotation is fixed", full.modelFor("campfire")?.rotation == Rotation.Fixed(90f))
    check("auto rotation", full.modelFor("cabin")?.rotation == Rotation.Auto)
    check("crate ids are case-insensitive", full.modelFor("CABIN") != null)
    check("unknown crate has no model", full.modelFor("common") == null)

    check("missing master switch = off", !parse("models.cabin.model" to "joshymc:cabin_crate").enabled)
    val off = parse("enabled" to false, "models.cabin.model" to "joshymc:cabin_crate")
    check("disabled master switch hides every model", off.modelFor("cabin") == null && !off.showsIn("survival"))
    val badSwitch = parse("enabled" to "yes")
    check("non-boolean master switch is refused, not guessed", !badSwitch.enabled && badSwitch.problems.isNotEmpty())

    val entryOff = parse("enabled" to true, "models.cabin.enabled" to false)
    check("disabled entry keeps the vanilla look", entryOff.modelFor("cabin") == null)

    // Invalid values drop only that crate (it keeps the vanilla look), never guessed.
    val bad = parse(
        "enabled" to true,
        "models.cabin.scale" to 9.0,
        "models.homestead.model" to "not a key",
        "models.campfire.offset.y" to "up",
        "models.joshy.rotation" to "sideways",
        "models.rare.enabled" to "maybe",
        "models.common.model" to "joshymc:common_crate",
    )
    check("scale out of range refused", bad.modelFor("cabin") == null)
    check("bad model key refused", bad.modelFor("homestead") == null)
    check("non-numeric offset refused", bad.modelFor("campfire") == null)
    check("bad rotation refused", bad.modelFor("joshy") == null)
    check("non-boolean entry switch refused", bad.modelFor("rare") == null)
    check("a valid entry next to bad ones still loads", bad.modelFor("common") != null)
    check("every refusal is reported", bad.problems.size == 5)
    check("offset limit", parse("enabled" to true, "models.a.offset.x" to 1.5).modelFor("a") == null)
    check("scale limits inclusive", parse("enabled" to true, "models.a.scale" to 2.0).modelFor("a") != null &&
        parse("enabled" to true, "models.a.scale" to 0.5).modelFor("a") != null)

    // ── Gamemodes (Survival only by default; Lifesteal opt-in) ──
    check("default gamemodes = survival", full.showsIn("survival") && !full.showsIn("lifesteal"))
    check("missing gamemodes = survival", parse("enabled" to true).gamemodes == setOf("survival"))
    val both = parse("enabled" to true, "gamemodes" to listOf("Survival", "lifesteal"))
    check("lifesteal can be opted in later", both.showsIn("lifesteal") && both.showsIn("survival"))
    check("empty gamemode list shows nowhere", !parse("enabled" to true, "gamemodes" to emptyList<String>()).showsIn("survival"))

    // ── Rotation ────────────────────────────────────────
    check("facing yaws", CrateModels.facingYaw("SOUTH") == 0f && CrateModels.facingYaw("west") == 90f &&
        CrateModels.facingYaw("NORTH") == 180f && CrateModels.facingYaw("EAST") == 270f)
    check("vertical / missing facing has no yaw", CrateModels.facingYaw("UP") == null && CrateModels.facingYaw(null) == null)
    // ItemDisplays draw their item turned half a turn: a north-facing chest needs yaw 0.
    check("north-facing chest -> display yaw 0", CrateModels.displayYaw(Rotation.Auto, "NORTH") == 0f)
    check("south-facing chest -> display yaw 180", CrateModels.displayYaw(Rotation.Auto, "SOUTH") == 180f)
    check("east-facing chest -> display yaw 90", CrateModels.displayYaw(Rotation.Auto, "EAST") == 90f)
    check("west-facing chest -> display yaw 270", CrateModels.displayYaw(Rotation.Auto, "WEST") == 270f)
    check("no facing -> front faces south", CrateModels.displayYaw(Rotation.Auto, null) == 180f)
    check("fixed rotation ignores the block", CrateModels.displayYaw(Rotation.Fixed(180f), "SOUTH") == 0f)
    check("negative rotation normalized", parse("enabled" to true, "models.a.rotation" to -90).modelFor("a")?.rotation == Rotation.Fixed(270f))
    check("string rotation number", parse("enabled" to true, "models.a.rotation" to "450").modelFor("a")?.rotation == Rotation.Fixed(90f))

    // ── Placement ───────────────────────────────────────
    check("scale 1 needs no lift", CrateModels.floorLift(1.0) == 0.0)
    check("bigger models are lifted so their floor stays on the floor", CrateModels.floorLift(1.5) == 0.25)
    check("item definition path", CrateModels.itemDefinitionPath("joshymc:cabin_crate") == "assets/joshymc/items/cabin_crate.json")
    check("bad key has no path", CrateModels.itemDefinitionPath("cabin crate") == null)
    check("location key", CrateModels.locationKey("spawn", 1, 64, -3) == "spawn:1:64:-3")

    // ── Visibility ──────────────────────────────────────
    check("Java + pack loaded sees the model", CrateModels.canSee(bedrock = false, packLoaded = true))
    check("Java without the pack keeps the vanilla block", !CrateModels.canSee(bedrock = false, packLoaded = false))
    check("Bedrock never sees the display", !CrateModels.canSee(bedrock = true, packLoaded = true))

    // ── Bundled config ──────────────────────────────────
    val config = File("src/main/resources/config.yml").readText()
    val section = config.substringAfter("  custom-models:\n").substringBefore("\n\n")
    check("bundled config has the section", "enabled: true" in section && "gamemodes: [survival]" in section)
    // crate id -> item model path (the #1447 redesign models are named <id>_chest)
    val bundled = linkedMapOf(
        "cabin" to "cabin_crate", "homestead" to "homestead_crate", "campfire" to "campfire_crate",
        "harvest" to "harvest_chest", "stockpile" to "stockpile_chest",
    )
    for ((id, path) in bundled) {
        check("bundled config has $id", "      $id:\n" in section && "model: \"joshymc:$path\"" in section)
        check("resource pack source has $id", File("resourcepack/assets/joshymc/items/$path.json").exists())
        check("art module for $id", File("resourcepack/art/items/$path.py").exists())
    }
    val parsedBundled = CrateModels.parse(MapSource(mapOf(
        "enabled" to true,
        "models.harvest.model" to "joshymc:harvest_chest",
        "models.stockpile.model" to "joshymc:stockpile_chest",
    )))
    check("harvest model key kept", parsedBundled.modelFor("harvest")?.model == "joshymc:harvest_chest")
    check("stockpile model key kept", parsedBundled.modelFor("stockpile")?.model == "joshymc:stockpile_chest")
    // The crate ids are the ones the rest of the plugin hands out keys for.
    val quests = File("src/main/kotlin/com/liam/joshymc/manager/QuestCycleManager.kt").readText()
    check("bundled model crate ids are real crate ids", bundled.keys.all { "\"$it\"" in quests })
    val built = File("build/resources/main/resourcepack.zip")
    if (built.exists()) {
        ZipFile(built).use { zip ->
            for ((id, path) in bundled) {
                check("built pack has $id", zip.getEntry("assets/joshymc/items/$path.json") != null)
            }
        }
    }

    // ── Wiring: purely visual, the crate flow is untouched ──
    val manager = File("src/main/kotlin/com/liam/joshymc/manager/CrateManager.kt").readText()
    check("models start with the crates", "models.start()" in manager && "models.stop()" in manager)
    check("relocation redraws", manager.substringAfter("fun setCrateLocation(").substringBefore("fun getCrateTypeAt(").contains("refreshModel("))
    val displays = File("src/main/kotlin/com/liam/joshymc/crate/CrateModelDisplays.kt").readText()
    check("displays are never saved", "isPersistent = false" in displays)
    check("displays hidden by default", "isVisibleByDefault = false" in displays)
    check("no repeating task", "runTaskTimer" !in displays && "scheduleSyncRepeatingTask" !in displays)
    check("never changes blocks", ".setType(" !in displays && ".type = " !in displays && "setBlockData" !in displays)
    val listeners = File("src/main/kotlin/com/liam/joshymc/manager/ListenerManager.kt").readText()
    check("listener registered", "plugin.crateManager.models" in listeners)

    // ── Hitboxes (issue #1451) ──────────────────────────
    hitboxChecks(manager, displays)

    println("All $checks crate model checks passed.")
}

private fun near(a: Double, b: Double, eps: Double = 1e-6) = kotlin.math.abs(a - b) < eps

private fun model(
    scale: Double = 1.0, ox: Double = 0.0, oy: Double = 0.0, oz: Double = 0.0,
    rotation: Rotation = Rotation.Auto, hitbox: CrateModels.HitboxMode = CrateModels.HitboxMode.AUTO,
) = CrateModels.Model("test", true, "joshymc:test", ox, oy, oz, rotation, scale, hitbox)

private fun hitboxChecks(manager: String, displays: String) {
    // ── Config ──
    check("hitbox defaults to auto", parse("enabled" to true, "models.a.scale" to 1.0).modelFor("a")?.hitbox == CrateModels.HitboxMode.AUTO)
    check("hitbox on/off/true/false", parse("enabled" to true, "models.a.hitbox" to "on").modelFor("a")?.hitbox == CrateModels.HitboxMode.ON &&
        parse("enabled" to true, "models.a.hitbox" to "OFF").modelFor("a")?.hitbox == CrateModels.HitboxMode.OFF &&
        parse("enabled" to true, "models.a.hitbox" to true).modelFor("a")?.hitbox == CrateModels.HitboxMode.ON &&
        parse("enabled" to true, "models.a.hitbox" to false).modelFor("a")?.hitbox == CrateModels.HitboxMode.OFF)
    val badHitbox = parse("enabled" to true, "models.a.hitbox" to "huge")
    check("bad hitbox value refused, not guessed", badHitbox.modelFor("a") == null && badHitbox.problems.size == 1)

    // ── Placement: the model sits on its block ──
    val block = CrateModels.ModelBounds.FULL_BLOCK
    val flat = CrateModels.placement(model(), block, "SOUTH")
    check("display at the block centre", near(flat.displayX, 0.5) && near(flat.displayY, 0.5) && near(flat.displayZ, 0.5))
    check("full-block model fills exactly its block", flat.visual == CrateModels.Box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
    check("a model that fits needs no hitbox", flat.hitbox == null && flat.problem == null)
    check("hitbox: on adds one even when it fits", CrateModels.placement(model(hitbox = CrateModels.HitboxMode.ON), block, "SOUTH").hitbox != null)
    for (facing in listOf("NORTH", "SOUTH", "EAST", "WEST", null)) {
        val p = CrateModels.placement(model(), block, facing)
        check("full block stays aligned facing $facing", near(p.visual.minX, 0.0) && near(p.visual.maxX, 1.0) &&
            near(p.visual.minZ, 0.0) && near(p.visual.maxZ, 1.0) && near(p.visual.minY, 0.0))
    }
    val big = CrateModels.placement(model(scale = 2.0), block, "NORTH")
    check("scaled model keeps its floor on the block floor", near(big.visual.minY, 0.0) && near(big.visual.maxY, 2.0))
    check("scaled model is centred on its block", near(big.visual.minX, -0.5) && near(big.visual.maxX, 1.5))
    val bigBox = big.hitbox
    check("oversized model gets a hitbox", bigBox != null)
    check("hitbox follows the scale", bigBox != null && near(bigBox.width, 2.0) && near(bigBox.height, 2.0) &&
        near(bigBox.x, 0.5) && near(bigBox.z, 0.5) && near(bigBox.y, 0.0))
    check("hitbox: off never adds one", CrateModels.placement(model(scale = 2.0, hitbox = CrateModels.HitboxMode.OFF), block, "NORTH").hitbox == null)
    val shifted = CrateModels.placement(model(ox = 0.5, oy = 0.25), block, "SOUTH")
    check("offset moves the model in world axes", near(shifted.visual.minX, 0.5) && near(shifted.visual.maxX, 1.5) && near(shifted.visual.minY, 0.25))
    check("offset hitbox follows the model", shifted.hitbox != null && near(shifted.hitbox!!.x, 1.0) && near(shifted.hitbox!!.y, 0.25))
    check("small overhang (lips, latches) doesn't count",
        CrateModels.placement(model(), CrateModels.ModelBounds(0.0, 0.0, -0.2, 16.0, 19.5, 16.25), "SOUTH").hitbox == null)
    val beside = CrateModels.placement(model(scale = 0.5, ox = 1.0), block, "SOUTH")
    check("a model drawn beside its block is refused", beside.problem != null && beside.hitbox == null)

    // ── Rotation turns the footprint the way Paper draws it ──
    // A model that sticks out of its front (+z in model space) by 0.5 blocks.
    val front = CrateModels.ModelBounds(0.0, 0.0, 0.0, 16.0, 16.0, 24.0)
    val south = CrateModels.placement(model(), front, "SOUTH").visual
    val north = CrateModels.placement(model(), front, "NORTH").visual
    val east = CrateModels.placement(model(), front, "EAST").visual
    val west = CrateModels.placement(model(), front, "WEST").visual
    check("front faces south", near(south.maxZ, 1.5) && near(south.minZ, 0.0))
    check("front faces north", near(north.minZ, -0.5) && near(north.maxZ, 1.0))
    check("front faces east", near(east.maxX, 1.5) && near(east.minX, 0.0))
    check("front faces west", near(west.minX, -0.5) && near(west.maxX, 1.0))
    val fixed = CrateModels.placement(model(rotation = Rotation.Fixed(270f)), front, "SOUTH").visual
    check("fixed rotation turns the footprint too", near(fixed.maxX, 1.5))
    val diagonal = CrateModels.placement(model(rotation = Rotation.Fixed(45f)), block, null).visual
    check("a 45 degree turn widens the footprint", diagonal.maxX - diagonal.minX > 1.4)
    val wideHit = CrateModels.placement(model(), front, "EAST").hitbox!!
    check("hitbox is square around the footprint", near(wideHit.width, 1.5) && near(wideHit.x, 0.75) && near(wideHit.z, 0.5))

    // ── Chunks: never draws into an unloaded neighbour ──
    check("chunk of a point", CrateModels.chunkOf(15, 0.5) == 0 && CrateModels.chunkOf(15, 1.5) == 1 && CrateModels.chunkOf(0, -0.5) == -1)
    check("a centred crate needs only its own chunk", CrateModels.requiredChunks(5, 5, flat).size == 1)
    val edge = CrateModels.placement(model(ox = 1.0), block, "SOUTH")
    check("an offset across a chunk border needs both chunks", CrateModels.requiredChunks(15, 5, edge) ==
        setOf(CrateModels.chunkKey(0, 0), CrateModels.chunkKey(1, 0)))
    check("neighbour chunks may hold a crate's entities", CrateModels.mayHoldEntities(15, 5, 1, 0) && CrateModels.mayHoldEntities(0, 0, -1, -1))
    check("far chunks never do", !CrateModels.mayHoldEntities(8, 8, 1, 0) && !CrateModels.mayHoldEntities(15, 5, 2, 0))
    check("chunk keys keep negatives apart", CrateModels.chunkKey(-1, 0) != CrateModels.chunkKey(0, -1))

    // ── Reach ──
    val unit = CrateModels.Box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
    check("inside the box = no distance", CrateModels.distanceSqToBox(0.5, 0.5, 0.5, unit) == 0.0)
    check("distance to the nearest face", near(CrateModels.distanceSqToBox(4.0, 0.5, 0.5, unit), 9.0))

    // ── Model bounds from the real pack JSON ──
    val def = """{"model":{"type":"minecraft:select","cases":[{"model":{"type":"minecraft:model","model":"joshymc:item/a"}}],
        "fallback":{"type":"model","model":"joshymc:item/b"}}}"""
    check("item definition model refs", CrateModelBounds.modelRefs(def) == listOf("joshymc:item/a", "joshymc:item/b"))
    check("model path", CrateModelBounds.modelPath("joshymc:item/x/main") == "assets/joshymc/models/item/x/main.json" &&
        CrateModelBounds.modelPath("item/chest") == "assets/minecraft/models/item/chest.json" &&
        CrateModelBounds.modelPath("joshymc:../x") == null)
    val rotated = CrateModelBounds.elementBounds("""{"elements":[{"from":[0,0,0],"to":[16,2,16]},
        {"from":[6,0,6],"to":[10,20,10],"rotation":{"origin":[8,0,8],"axis":"z","angle":90}}]}""")!!
    check("rotated elements are measured after their rotation", near(rotated.minY, -2.0) && near(rotated.maxY, 2.0) && near(rotated.minX, -12.0) && near(rotated.maxX, 16.0))
    val euler = CrateModelBounds.elementBounds("""{"elements":[{"from":[0,0,0],"to":[16,16,16],"rotation":{"origin":[8,8,8],"x":0,"y":45,"z":0}}]}""")!!
    check("Euler rotations are measured", euler.maxX > 18.0 && near(euler.maxY, 16.0))
    check("no elements = unknown bounds", CrateModelBounds.elementBounds("""{"parent":"item/generated"}""") == null &&
        CrateModelBounds.elementBounds("not json") == null)

    // The bundled crate models: Harvest fits its block, the taller ones get a hitbox at scale 1.
    val expectHitbox = mapOf("cabin_crate" to true, "homestead_crate" to true, "campfire_crate" to true,
        "harvest_chest" to false, "stockpile_chest" to true)
    for ((path, wanted) in expectHitbox) {
        val itemJson = File("resourcepack/assets/joshymc/items/$path.json").readText()
        val bounds = CrateModelBounds.union(CrateModelBounds.modelRefs(itemJson).map { ref ->
            CrateModelBounds.modelPath(ref)?.let { File("resourcepack/$it") }?.takeIf { it.exists() }?.readText()?.let(CrateModelBounds::elementBounds)
        })
        check("$path bounds are readable", bounds != null)
        bounds!!
        check("$path stands on the floor", bounds.minY > -0.05)
        check("$path stays inside its block's sides", bounds.minX > -0.3 && bounds.maxX < 16.3 && bounds.minZ > -0.3 && bounds.maxZ < 16.3)
        for (facing in listOf("NORTH", "SOUTH", "EAST", "WEST")) {
            val p = CrateModels.placement(model(), bounds, facing)
            check("$path facing $facing sits on its block", p.problem == null && near(p.visual.minY, bounds.minY / 16.0))
            check("$path facing $facing hitbox = $wanted", (p.hitbox != null) == wanted)
            p.hitbox?.let { h ->
                check("$path hitbox covers the block", h.box.minX <= 0.0 + 0.02 && h.box.maxX >= 1.0 - 0.02 &&
                    h.box.minZ <= 0.02 && h.box.maxZ >= 0.98 && h.box.minY <= 0.0 && h.box.maxY >= bounds.maxY / 16.0 - 1e-9)
                check("$path hitbox stays close to the model", h.width < 1.1)
            }
        }
    }

    // ── One action per click ──
    val gate = CrateClickGate()
    val a = UUID.randomUUID()
    check("first click runs", gate.tryEnter(a, 100, leftClick = false))
    check("same click again in the same tick is dropped", !gate.tryEnter(a, 100, leftClick = false))
    check("the other click kind still runs", gate.tryEnter(a, 100, leftClick = true))
    check("next tick runs again", gate.tryEnter(a, 101, leftClick = false))
    check("ranIn reports it", gate.ranIn(a, 101, leftClick = false) && !gate.ranIn(a, 102, leftClick = false))
    check("other players are independent", gate.tryEnter(UUID.randomUUID(), 101, leftClick = false))
    gate.forget(a)
    check("forget drops the player", gate.size == 1)

    // ── Wiring ──
    check("hitboxes are never saved", displays.substringAfter("Interaction::class.java) { i ->").substringBefore("}").contains("isPersistent = false"))
    check("hitboxes hidden by default", displays.substringAfter("Interaction::class.java) { i ->").substringBefore("}").contains("isVisibleByDefault = false"))
    check("hitbox clicks go through the crate manager", "crates.handleModelClick(" in displays)
    check("hitbox right-clicks are hand-filtered", "event.hand != EquipmentSlot.HAND" in displays)
    check("left-clicks use Paper's attack event", "PrePlayerAttackEntityEvent" in displays)
    check("managed entities can't be damaged", "EntityDamageEvent" in displays)
    check("cleanup only matches tagged JoshyMC entities", "TAG in entity.scoreboardTags" in displays && "locationKeyTag" in displays)
    check("never loads chunks", "getChunkAtAsync" !in displays && "loadChunk" !in displays && ".load(" !in displays)
    val modelClick = manager.substringAfter("fun handleModelClick(").substringBefore("\n    }\n")
    check("model clicks reuse the crate click flow", "handleCrateClick(" in modelClick && "getCrateTypeAt(block)" in modelClick)
    check("model clicks spend no key themselves", "consumeOneKey" !in modelClick && "beginOpening" !in modelClick)
    check("block clicks share the click gate", manager.substringAfter("fun onInteract(event: PlayerInteractEvent)").substringBefore("fun handleModelClick(").contains("clickGate.tryEnter("))
    check("click gate cleared on quit", "clickGate.forget(" in manager)
}
