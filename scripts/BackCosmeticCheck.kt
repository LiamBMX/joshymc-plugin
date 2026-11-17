// Checks the Bukkit-free back cosmetic rules and the Eagle Wings wiring (issue #1516). The
// Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/back-cosmetic-check.init.gradle.kts backCosmeticCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.cosmetic.BackCosmeticRules
import com.liam.joshymc.cosmetic.BackCosmeticRules.Hidden
import com.liam.joshymc.cosmetic.BackCosmeticRules.Settings
import com.liam.joshymc.cosmetic.BackCosmeticRules.Wearer
import com.google.gson.JsonParser
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

private val on = Settings()

private fun sees(
    self: Boolean = false, bedrock: Boolean = false, pack: Boolean = true, canSee: Boolean = true,
    sameWorld: Boolean = true, settings: Settings = on,
) = BackCosmeticRules.viewerSees(settings, self, bedrock, pack, canSee, sameWorld)

/**
 * Model-space bounds (minX, maxX, minY, maxY, minZ, maxZ) of a built item model, with each
 * element's rotation applied (Minecraft order: X, then Y, then Z, about its origin).
 */
private fun modelBounds(file: File, unrotatedOnly: Boolean = false): DoubleArray {
    val b = doubleArrayOf(1e9, -1e9, 1e9, -1e9, 1e9, -1e9)
    val root = JsonParser.parseString(file.readText()).asJsonObject
    for (el in root.getAsJsonArray("elements")) {
        val e = el.asJsonObject
        if (unrotatedOnly && e.has("rotation")) continue
        val from = e.getAsJsonArray("from").map { it.asDouble }
        val to = e.getAsJsonArray("to").map { it.asDouble }
        val rot = e.getAsJsonObject("rotation")
        val origin = rot?.getAsJsonArray("origin")?.map { it.asDouble }
        fun angle(axis: String): Double = when {
            rot == null -> 0.0
            rot.has("axis") -> if (rot.get("axis").asString == axis) rot.get("angle").asDouble else 0.0
            else -> rot.get(axis)?.asDouble ?: 0.0
        }
        for (x in listOf(from[0], to[0])) for (y in listOf(from[1], to[1])) for (z in listOf(from[2], to[2])) {
            var p = doubleArrayOf(x, y, z)
            if (origin != null) {
                p = DoubleArray(3) { p[it] - origin[it] }
                p = rotate(rotate(rotate(p, 0, angle("x")), 1, angle("y")), 2, angle("z"))
                p = DoubleArray(3) { p[it] + origin[it] }
            }
            for (i in 0..2) {
                b[2 * i] = minOf(b[2 * i], p[i])
                b[2 * i + 1] = maxOf(b[2 * i + 1], p[i])
            }
        }
    }
    return b
}

private fun rotate(p: DoubleArray, axis: Int, degrees: Double): DoubleArray {
    if (degrees == 0.0) return p
    val r = Math.toRadians(degrees)
    val c = Math.cos(r)
    val s = Math.sin(r)
    return when (axis) {
        0 -> doubleArrayOf(p[0], c * p[1] - s * p[2], s * p[1] + c * p[2])
        1 -> doubleArrayOf(c * p[0] + s * p[2], p[1], -s * p[0] + c * p[2])
        else -> doubleArrayOf(c * p[0] - s * p[1], s * p[0] + c * p[1], p[2])
    }
}

fun main() {
    // When the wings are drawn at all.
    check("standing wearer shows", BackCosmeticRules.hidden(on, Wearer()) == null)
    check("sneaking wearer shows", BackCosmeticRules.hidden(on, Wearer(pose = "SNEAKING")) == null)
    check("disabled feature hides", BackCosmeticRules.hidden(Settings(enabled = false), Wearer()) == Hidden.DISABLED)
    check("other gamemode hides", BackCosmeticRules.hidden(on, Wearer(allowedHere = false)) == Hidden.NOT_HERE)
    check("dead hides", BackCosmeticRules.hidden(on, Wearer(dead = true)) == Hidden.DEAD)
    check("spectator hides", BackCosmeticRules.hidden(on, Wearer(spectator = true)) == Hidden.SPECTATOR)
    check("vanish hides", BackCosmeticRules.hidden(on, Wearer(vanished = true)) == Hidden.VANISHED)
    check("mod mode hides", BackCosmeticRules.hidden(on, Wearer(modMode = true)) == Hidden.MOD_MODE)
    check("invisibility hides", BackCosmeticRules.hidden(on, Wearer(invisible = true)) == Hidden.INVISIBLE)
    check("combat tag hides", BackCosmeticRules.hidden(on, Wearer(combatTagged = true)) == Hidden.COMBAT)
    check("combat tag shows when hide-in-combat is off",
        BackCosmeticRules.hidden(Settings(hideInCombat = false), Wearer(combatTagged = true)) == null)
    check("ranked match hides", BackCosmeticRules.hidden(on, Wearer(inRanked = true)) == Hidden.RANKED)
    check("ranked match hides even with hide-in-combat off (ranked hits never combat-tag)",
        BackCosmeticRules.hidden(Settings(hideInCombat = false), Wearer(inRanked = true)) == Hidden.RANKED)
    check("event world hides", BackCosmeticRules.hidden(on, Wearer(inEventWorld = true)) == Hidden.EVENT_WORLD)
    for (pose in listOf("FALL_FLYING", "SWIMMING", "SLEEPING", "SPIN_ATTACK", "DYING")) {
        check("$pose hides", BackCosmeticRules.hidden(on, Wearer(pose = pose)) == Hidden.POSE)
    }
    check("vanish wins over combat (never reveal a vanished wearer)",
        BackCosmeticRules.hidden(Settings(hideInCombat = false), Wearer(vanished = true, combatTagged = true)) == Hidden.VANISHED)

    // Who sees them.
    check("java viewer with the pack sees", sees())
    check("bedrock viewer never sees", !sees(bedrock = true))
    check("viewer without the pack never sees (no missing-model cube)", !sees(pack = false))
    check("viewer who can't see the wearer doesn't see the wings", !sees(canSee = false))
    check("other world never sees", !sees(sameWorld = false))
    check("wearer sees own wings by default", sees(self = true))
    check("show-to-wearer false hides from the wearer", !sees(self = true, settings = Settings(showToWearer = false)))
    check("wearer without the pack doesn't see them", !sees(self = true, pack = false))

    // Placement.
    val stand = BackCosmeticRules.anchor(false, 1.0)
    val previewAnchor = Regex("""BACK_ANCHOR = \(([-0-9.]+), ([-0-9.]+), ([-0-9.]+)\)""")
        .find(File("resourcepack/art/render.py").readText())?.groupValues?.drop(1)?.map { it.toDouble() }
    check("standing anchor matches art/render.py BACK_ANCHOR",
        previewAnchor == listOf(stand.x, stand.y, stand.z) && stand.tiltDegrees == 0.0)
    check("standing anchor is between the shoulder blades", stand.y == 1.35 && stand.z == -0.25)
    check("anchor is behind the wearer", stand.z < 0 && BackCosmeticRules.anchor(true, 1.0).z < 0)
    check("sneaking lowers and tilts forward", BackCosmeticRules.SNEAKING.y < stand.y && BackCosmeticRules.SNEAKING.tiltDegrees > 0)
    check("anchor scales with player size", BackCosmeticRules.anchor(false, 2.0).y == 2.7)
    check("bad scale falls back to 1", BackCosmeticRules.anchor(false, Double.NaN) == stand && BackCosmeticRules.modelScale(1.25, -1.0) == 1.25)
    check("model scale multiplies", BackCosmeticRules.modelScale(1.25, 0.5) == 0.625)
    check("other world respawns", BackCosmeticRules.mustRespawn(false, 0.0, 0.0, 0.0))
    check("small step follows", !BackCosmeticRules.mustRespawn(true, 1.0, 0.5, 1.0))
    check("long teleport respawns", BackCosmeticRules.mustRespawn(true, 10.0, 0.0, 0.0))
    check("smoothing clamped", BackCosmeticRules.smoothing(-3) == 0 && BackCosmeticRules.smoothing(40) == BackCosmeticRules.MAX_SMOOTHING_TICKS)

    // Wiring.
    val root = "src/main/kotlin/com/liam/joshymc"
    val manager = File("$root/cosmetic/BackCosmeticManager.kt").readText()
    check("display is non-persistent", manager.contains("d.isPersistent = false"))
    check("display hidden by default", manager.contains("d.isVisibleByDefault = false"))
    check("display never rides the player (teleports would fail)", !Regex("""\.addPassenger\(""").containsMatchIn(manager))
    check("removed on quit", Regex("""fun onQuit[\s\S]*?detach\(""").containsMatchIn(manager))
    check("strays swept on entity load", manager.contains("fun onEntitiesLoad"))
    val joshymc = File("$root/Joshymc.kt").readText()
    check("stopped on shutdown", joshymc.contains("shutdown.step(\"backCosmeticManager.stop\""))
    check("stopped and started on reload",
        joshymc.contains("safe(\"backCosmeticManager.stop\")") && joshymc.contains("safe(\"backCosmeticManager.start\")"))
    check("eagle wings registered", File("$root/manager/ItemManager.kt").readText().contains("register(EagleWings())"))
    val wings = File("$root/item/impl/EagleWings.kt").readText()
    // Issue #1591: shared by Survival and Lifesteal (stat-for-stat a vanilla Netherite Chestplate).
    check("eagle wings are shared (no gamemode scope)", wings.contains("override val gamemodeScope: GamemodeScope? = null"))
    check("eagle wings stay a plain Netherite Chestplate",
        wings.contains("Material.NETHERITE_CHESTPLATE") && !wings.contains("addAttributeModifier") &&
            !wings.contains("glider") && !wings.contains("addEnchant"))
    check("manager hides the wings for ranked participants and spectators",
        manager.contains("lifestealRankedManager.holds(player.uniqueId)") && manager.contains("lifestealRankedManager.isSpectator(player.uniqueId)"))
    // Items with gameplay beyond looks stay Survival only (issue #1591 exclusions).
    for (file in listOf("BatWings", "ChristmasTreeElytra", "VikingSet", "KrampusSet", "SnowmanSet", "PeppermintPickaxe")) {
        check("$file stays Survival scoped", File("$root/item/impl/$file.kt").readText().contains("gamemodeScope = GamemodeScope.SURVIVAL"))
    }
    check("eagle wings are not unbreakable", !wings.contains("isUnbreakable"))
    val art = File("resourcepack/art/items/eagle_wings.py").readText()
    val scale = Regex("""BACK_SCALE = ([0-9.]+)""").find(art)?.groupValues?.get(1)?.toDouble()
    check("art BACK_SCALE matches the item backScale", scale != null && wings.contains("backScale = $scale"))
    check("item definition built", File("resourcepack/assets/joshymc/items/eagle_wings.json").isFile)

    // Worn size and placement (issue #1571): at the worn scale the built model must read as
    // wings on the player's back, not a sheet stretched far past them.
    val b = modelBounds(File("resourcepack/assets/joshymc/models/item/eagle_wings/main.json"))
    val k = (scale ?: 1.0) / 16.0
    val span = (b[1] - b[0]) * k
    val top = stand.y + (b[3] - 8.0) * k
    val bottom = stand.y + (b[2] - 8.0) * k
    val reach = (b[5] - 8.0) * k
    // The back plate is the unrotated boxes (the straps over the shoulders reach further
    // forward on purpose); item displays turn the model 180 degrees, so -Z becomes front.
    val plate = modelBounds(File("resourcepack/assets/joshymc/models/item/eagle_wings/main.json"), unrotatedOnly = true)
    val plateFront = stand.z + (8.0 - plate[4]) * k
    check("worn wingspan is natural (1.5..2.25 blocks), got $span", span in 1.5..2.25)
    check("wing tips stay near the head (<= 2.3 blocks up), got $top", top <= 2.3)
    check("wings don't hang below the hips (>= 0.6 blocks up), got $bottom", bottom >= 0.6)
    check("wings stay close behind the back (<= 0.6 blocks), got $reach", reach <= 0.6)
    check("back plate rests on the chestplate, not inside the torso, got $plateFront",
        plateFront in -0.21..-0.16)
    check("equipment asset built", File("resourcepack/assets/joshymc/equipment/eagle_wings.json").isFile)

    println("Back cosmetic checks passed ($checks).")
}
