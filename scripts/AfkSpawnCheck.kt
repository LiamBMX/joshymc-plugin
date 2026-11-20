// Checks the AFK spawn (issue #1596): the Bukkit-free destination rules, the per-gamemode store
// against a real SQLite database (fresh temp directory, reopened to stand in for a restart), and
// the command/permission wiring. From the repo root:
//
//   ./gradlew -I scripts/afk-spawn-check.init.gradle.kts afkSpawnCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.afk.AfkSpawn
import com.liam.joshymc.afk.AfkSpawnRules
import com.liam.joshymc.afk.AfkSpawnRules.Destination
import com.liam.joshymc.afk.AfkSpawnStore
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.DatabaseManager
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }

private fun withDb(dir: File, block: (DatabaseManager) -> Unit) {
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        block(db)
    } finally {
        db.shutdown()
    }
}

fun main() {
    val survival = AfkSpawn(ServerGamemode.SURVIVAL, "afk", 0.5, 65.0, 0.5, 90f, 10f)
    val lifesteal = AfkSpawn(ServerGamemode.LIFESTEAL, "lifesteal_spawn", -120.25, 72.0, 33.75, -45f, 0f)

    // ── Destination rules ─────────────────────────────────
    check("no spawn = stay in place", AfkSpawnRules.destination(null, worldLoaded = false) == Destination.NOT_SET)
    check("no spawn wins over a loaded world", AfkSpawnRules.destination(null, worldLoaded = true) == Destination.NOT_SET)
    check("missing world = stay in place", AfkSpawnRules.destination(survival, worldLoaded = false) == Destination.WORLD_MISSING)
    check("usable spawn teleports", AfkSpawnRules.destination(survival, worldLoaded = true) == Destination.TELEPORT)
    check("NaN coordinates refused", AfkSpawnRules.destination(survival.copy(y = Double.NaN), worldLoaded = true) == Destination.INVALID)
    check("infinite coordinates refused", AfkSpawnRules.destination(survival.copy(x = Double.POSITIVE_INFINITY), worldLoaded = true) == Destination.INVALID)
    check("blank world refused", AfkSpawnRules.destination(survival.copy(world = " "), worldLoaded = true) == Destination.INVALID)
    check("pitch out of range refused", AfkSpawnRules.destination(survival.copy(pitch = 120f), worldLoaded = true) == Destination.INVALID)
    check("saved message names the gamemode and spot",
        AfkSpawnRules.formatSaved(survival) == "AFK spawn for Survival set to afk (0.50, 65.00, 0.50), yaw 90.00, pitch 10.00.")

    // ── Store: persistence and isolation ──────────────────
    val dir = Files.createTempDirectory("joshymc-afk-spawn-check").toFile()
    try {
        val staff = UUID.randomUUID()
        withDb(dir) { db ->
            val store = AfkSpawnStore(db)
            store.createTables()
            store.createTables() // idempotent
            check("nothing set at first (survival)", store.load(ServerGamemode.SURVIVAL) == null)
            check("nothing set at first (lifesteal)", store.load(ServerGamemode.LIFESTEAL) == null)
            store.save(survival, staff, "Staff", 1L)
            check("survival saved", store.load(ServerGamemode.SURVIVAL) == survival)
            check("lifesteal still unset", store.load(ServerGamemode.LIFESTEAL) == null)
            store.save(lifesteal, staff, "Staff", 2L)
            check("lifesteal saved", store.load(ServerGamemode.LIFESTEAL) == lifesteal)
            check("setting lifesteal kept survival", store.load(ServerGamemode.SURVIVAL) == survival)
        }
        withDb(dir) { db ->
            val store = AfkSpawnStore(db)
            store.createTables()
            check("survival survives a restart", store.load(ServerGamemode.SURVIVAL) == survival)
            check("lifesteal survives a restart", store.load(ServerGamemode.LIFESTEAL) == lifesteal)
            val moved = survival.copy(world = "spawn", x = 10.0, y = 80.0, z = -4.0, yaw = 0f, pitch = -30f)
            store.save(moved, null, "CONSOLE", 3L)
            check("re-setting replaces survival", store.load(ServerGamemode.SURVIVAL) == moved)
            check("re-setting survival kept lifesteal", store.load(ServerGamemode.LIFESTEAL) == lifesteal)
            check("one row per gamemode", db.queryFirst("SELECT COUNT(*) AS n FROM afk_spawns") { it.getInt("n") } == 2)
        }
    } finally {
        dir.deleteRecursively()
    }

    // ── Wiring ────────────────────────────────────────────
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("joshymc.afk.setspawn is op only",
        Regex("""(?m)^  joshymc\.afk\.setspawn:\n    description: .*\n    default: op$""").containsMatchIn(pluginYml))
    val command = File("src/main/kotlin/com/liam/joshymc/command/AFKCommand.kt").readText()
    check("setspawn checks its own permission", command.contains("const val SETSPAWN_PERMISSION = \"joshymc.afk.setspawn\""))
    val manager = File("src/main/kotlin/com/liam/joshymc/manager/AFKManager.kt").readText()
    check("entering AFK checks combat and ranked", manager.contains("CombatTeleportGuard.problem(plugin, player)"))
    check("the destination is safety-checked", manager.contains("SafeSpots.problem(target)"))
    check("the old hard-coded AFK world spot is gone", !manager.contains("Location(afkWorld, 0.5, 65.0, 0.5"))

    println("AFK spawn checks passed ($checks checks).")
}
