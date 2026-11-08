// Checks the Bukkit-free vanish visibility rules (issue #1467). The Gradle build has no test
// source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/vanish-check.init.gradle.kts vanishCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.vanish.VanishRules
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

private fun hides(self: Boolean = false, vanished: Boolean = false, staff: Boolean = false, hideStaff: Boolean = false) =
    VanishRules.hides(viewerIsTarget = self, targetVanished = vanished, viewerSeesVanished = staff, hiddenByHideStaff = hideStaff)

fun main() {
    // Visibility (in-world and TAB use the same hidePlayer registration)
    check("normal player can't see a vanished player", hides(vanished = true))
    check("staff with joshymc.vanish see vanished players", !hides(vanished = true, staff = true))
    check("nobody is hidden without a reason", !hides())
    check("staff see non-vanished players", !hides(staff = true))
    check("a vanished player always sees themselves", !hides(self = true, vanished = true))
    check("self is never hidden by /hidestaff", !hides(self = true, hideStaff = true))
    check("/hidestaff hides Mod Mode staff from a staff viewer", hides(staff = true, hideStaff = true))
    check("/hidestaff still hides after unvanish", hides(vanished = false, staff = true, hideStaff = true))
    check("vanish and /hidestaff together still hide", hides(vanished = true, hideStaff = true))
    check("unvanish restores a normal viewer", !hides(vanished = false))

    // Join persistence
    check("stored vanish kept for staff", VanishRules.keepOnJoin(stored = true, hasPermission = true))
    check("stored vanish dropped without the permission", !VanishRules.keepOnJoin(stored = true, hasPermission = false))
    check("no stored vanish stays visible", !VanishRules.keepOnJoin(stored = false, hasPermission = true))

    // Wiring: the one visibility path, applied at the right moments.
    val vanish = File("src/main/kotlin/com/liam/joshymc/command/VanishCommand.kt").readText()
    check("join handler runs at LOWEST", Regex("""priority = EventPriority.LOWEST\)\s*\n\s*fun onPlayerJoin""").containsMatchIn(vanish))
    check("world change re-syncs", vanish.contains("fun onWorldChange(event: PlayerChangedWorldEvent)"))
    check("vanish state is persisted", vanish.contains("vanished_players"))
    check("vanished metadata for TAB plugins", vanish.contains("VanishRules.METADATA_KEY"))
    check("unvanish goes through the combined rule", Regex("""fun unvanish[\s\S]*?resync\(player\)""").containsMatchIn(vanish))
    check("only VanishCommand calls hidePlayer/showPlayer", File("src/main/kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && it.name != "VanishCommand.kt" }
        .none { Regex("""\.(hidePlayer|showPlayer)\(""").containsMatchIn(it.readText()) })
    val hideStaff = File("src/main/kotlin/com/liam/joshymc/manager/HideStaffManager.kt").readText()
    check("/hidestaff delegates to the combined rule", hideStaff.contains("vanishCommand.applyVisibility"))
    val joshymc = File("src/main/kotlin/com/liam/joshymc/Joshymc.kt").readText()
    check("/hidestaff listener re-registered on reload", joshymc.contains("safe(\"hideStaffManager.start\")"))
    val scoreboard = File("src/main/kotlin/com/liam/joshymc/manager/ScoreboardManager.kt").readText()
    check("TAB header online count only counts visible players", scoreboard.contains("count { player.canSee(it) }"))

    println("Vanish checks passed ($checks).")
}
