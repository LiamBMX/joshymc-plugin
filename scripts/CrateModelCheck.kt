// Checks the custom crate chest models (issue #1389): the Bukkit-free config parser and placement
// rules in CrateModels, the bundled config defaults, and that the crate manager wires the displays
// without touching the crate flow. The Gradle build has no test source set, so it runs through an
// init script, from the repo root:
//
//   ./gradlew -I scripts/crate-model-check.init.gradle.kts crateModelCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.crate.CrateModels
import com.liam.joshymc.crate.CrateModels.Rotation
import java.io.File
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
    for (id in listOf("cabin", "homestead", "campfire")) {
        check("bundled config has $id", "      $id:\n" in section && "model: \"joshymc:${id}_crate\"" in section)
        check("resource pack source has $id", File("resourcepack/assets/joshymc/items/${id}_crate.json").exists())
        check("art module for $id", File("resourcepack/art/items/${id}_crate.py").exists())
    }
    // The crate ids are the ones the rest of the plugin hands out keys for.
    val quests = File("src/main/kotlin/com/liam/joshymc/manager/QuestCycleManager.kt").readText()
    check("cabin/homestead/campfire are real crate ids", listOf("\"cabin\"", "\"homestead\"", "\"campfire\"").all { it in quests })
    val built = File("build/resources/main/resourcepack.zip")
    if (built.exists()) {
        ZipFile(built).use { zip ->
            for (id in listOf("cabin", "homestead", "campfire")) {
                check("built pack has $id", zip.getEntry("assets/joshymc/items/${id}_crate.json") != null)
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
    check("no Interaction hitbox (clicks reach the real block)", "Interaction" !in displays)
    check("never changes blocks", ".setType(" !in displays && ".type = " !in displays && "setBlockData" !in displays)
    val listeners = File("src/main/kotlin/com/liam/joshymc/manager/ListenerManager.kt").readText()
    check("listener registered", "plugin.crateManager.models" in listeners)

    println("All $checks crate model checks passed.")
}
