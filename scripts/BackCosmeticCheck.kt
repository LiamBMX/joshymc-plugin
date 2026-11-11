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
    check("standing anchor matches art/render.py BACK_ANCHOR", stand.y == 1.35 && stand.z == -0.22 && stand.tiltDegrees == 0.0)
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
    check("eagle wings are Survival scoped", wings.contains("gamemodeScope = GamemodeScope.SURVIVAL"))
    check("eagle wings are not unbreakable", !wings.contains("isUnbreakable"))
    val art = File("resourcepack/art/items/eagle_wings.py").readText()
    val scale = Regex("""BACK_SCALE = ([0-9.]+)""").find(art)?.groupValues?.get(1)?.toDouble()
    check("art BACK_SCALE matches the item backScale", scale != null && wings.contains("backScale = $scale"))
    check("item definition built", File("resourcepack/assets/joshymc/items/eagle_wings.json").isFile)
    check("equipment asset built", File("resourcepack/assets/joshymc/equipment/eagle_wings.json").isFile)

    println("Back cosmetic checks passed ($checks).")
}
