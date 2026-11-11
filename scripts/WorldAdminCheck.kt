// Checks the destructive world tool safeguards (issue #1353, audit findings D-M6 and P5-H1):
// /world delete (separate node, literal confirm, protected worlds, folder-safe names, audit)
// and /gencave (two-step confirmation, expiry, radius, claim overlap, block entities in the
// sphere, one cave at a time, audit). Run from the repo root:
//
//   ./gradlew -I scripts/world-admin-check.init.gradle.kts worldAdminCheck
//
// Paper's classes target Java 25 and this runs on the Java 21 toolchain, so it never loads a
// Bukkit class: rules are Bukkit-free, YAML goes through snakeyaml, wiring is checked by source
// text. Exits non-zero on the first failed check.

import com.liam.joshymc.staff.WorldAdminRules
import com.liam.joshymc.staff.WorldAdminRules.CaveProblem
import com.liam.joshymc.staff.WorldAdminRules.DeleteProblem
import org.yaml.snakeyaml.Yaml
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

private fun resource(name: String): Map<String, Any?> =
    File("src/main/resources/$name").reader(Charsets.UTF_8).use { Yaml().load<Map<String, Any?>>(it) }

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

fun main() {
    // ── /world delete ───────────────────────────────────────
    val protectedWorlds = setOf("world", "world_nether", "world_the_end", "spawn", "resource", "afk", "event", "pvp", "dungeon", "lifesteal_world")
    fun del(name: String, loaded: Boolean = true, confirmed: Boolean = true) = WorldAdminRules.deleteProblem(name, protectedWorlds, loaded, confirmed)
    check("deleting needs a literal confirm", del("buildworld", confirmed = false) == DeleteProblem.NEEDS_CONFIRM)
    check("a confirmed, unprotected, loaded world may be deleted", del("buildworld") == null)
    for (w in protectedWorlds) check("'$w' is protected", del(w) == DeleteProblem.PROTECTED)
    check("protection ignores case", del("Spawn") == DeleteProblem.PROTECTED && del("WORLD") == DeleteProblem.PROTECTED)
    check("protected beats unconfirmed (no hint to confirm a protected world)", del("spawn", confirmed = false) == DeleteProblem.PROTECTED)
    for (bad in listOf("..", "../world", "a/b", "a\\b", "", " ", "world.old", "x".repeat(65))) {
        check("'$bad' is not a valid world name", del(bad) == DeleteProblem.INVALID_NAME)
    }
    check("an unloaded world isn't deleted", del("buildworld", loaded = false) == DeleteProblem.NOT_LOADED)
    check("every delete problem has a message", DeleteProblem.entries.all { it.message.isNotBlank() })

    val worldCommand = source("command/WorldCommand.kt")
    val handleDelete = worldCommand.substringAfter("private fun handleDelete(").substringBefore("private fun handleList(")
    check("/world delete needs joshymc.world.delete", "WorldAdminRules.DELETE_PERMISSION" in handleDelete &&
        WorldAdminRules.DELETE_PERMISSION == "joshymc.world.delete")
    check("/world delete uses the rule with the protected set", "WorldAdminRules.deleteProblem(" in handleDelete && "protectedWorlds()" in handleDelete)
    check("/world delete only deletes the world's own folder in the container",
        "world.worldFolder.canonicalFile" in handleDelete && "Bukkit.getWorldContainer().canonicalFile" in handleDelete &&
            "File(Bukkit.getWorldContainer(), name)" !in handleDelete)
    check("/world delete is audited", "\"WORLD_DELETE\"" in handleDelete)
    val protectedFn = worldCommand.substringAfter("private fun protectedWorlds()").substringBefore("private fun handleDelete(")
    for (part in listOf("PROTECTED_WORLDS", "Bukkit.getWorlds().firstOrNull()", "\"spawn\"", "\"pvp\"", "\"dungeon\"", "resource-world.world-name",
            "afk.world", "eventManager.eventWorldName", "gamemode-lock.worlds", "world-management.protected-worlds")) {
        check("protected worlds include $part", part in protectedFn)
    }

    // ── /gencave ────────────────────────────────────────────
    check("radius 10..200", WorldAdminRules.radiusProblem(9) == CaveProblem.RADIUS && WorldAdminRules.radiusProblem(201) == CaveProblem.RADIUS &&
        WorldAdminRules.radiusProblem(10) == null && WorldAdminRules.radiusProblem(200) == null)
    val area = WorldAdminRules.area(100, 100, 50)
    check("the area covers the sphere plus the wall pass", area == WorldAdminRules.Area(49, 49, 151, 151))
    check("a claim inside the area overlaps", WorldAdminRules.overlaps(area, 90, 90, 110, 110))
    check("a claim touching the edge overlaps", WorldAdminRules.overlaps(area, 151, 151, 200, 200))
    check("claim corners in any order", WorldAdminRules.overlaps(area, 110, 110, 90, 90))
    check("a claim just outside doesn't overlap", !WorldAdminRules.overlaps(area, 152, 0, 300, 300) && !WorldAdminRules.overlaps(area, 0, 0, 48, 48))
    check("a claim surrounding the area overlaps", WorldAdminRules.overlaps(area, -1000, -1000, 1000, 1000))
    check("block entities inside the sphere count", WorldAdminRules.touches(0, 0, 0, 50) && WorldAdminRules.touches(51, 0, 0, 50))
    check("block entities outside the sphere don't", !WorldAdminRules.touches(52, 0, 0, 50) && !WorldAdminRules.touches(40, 40, 0, 50))
    check("confirmations expire after 60s", !WorldAdminRules.expired(0, 60_000) && WorldAdminRules.expired(0, 60_001))
    check("block estimate", WorldAdminRules.approximateBlocks(200) in 33_000_000L..34_000_000L)
    check("every cave problem has a message", CaveProblem.entries.all { it.message.isNotBlank() })

    val genCave = source("command/GenCaveCommand.kt")
    val onCommand = genCave.substringAfter("override fun onCommand(").substringBefore("private fun areaProblem(")
    check("/gencave <radius> only stores a pending request", "pending[sender.uniqueId] = Pending(" in onCommand && "generate(" !in onCommand)
    val confirm = genCave.substringAfter("private fun confirm(").substringBefore("private fun generate(")
    check("confirm is single use and expires", "pending.remove(player.uniqueId)" in confirm && "WorldAdminRules.expired(" in confirm)
    check("confirm re-checks the area", "areaProblem(player, request.center, request.radius)" in confirm)
    check("confirm refuses block entities in the sphere before generating",
        "getTileEntities(false)" in confirm && "WorldAdminRules.touches(" in confirm &&
            confirm.indexOf("blockEntities > 0") in 0 until confirm.indexOf("generate(online"))
    check("chunks are loaded asynchronously", "getChunkAtAsync(" in confirm)
    check("confirmed caves are audited before generating", confirm.indexOf("\"GENCAVE\"") in 0 until confirm.indexOf("generate(online"))
    check("one cave at a time", "@Volatile private var running" in genCave && genCave.split("running = false").size >= 5 && "running = true" in confirm)
    val areaFn = genCave.substringAfter("private fun areaProblem(").substringBefore("private fun confirm(")
    check("area check: blocked worlds, claims and WorldGuard (fail closed)",
        "gencave.blocked-worlds" in areaFn && "getClaimsInWorld(" in areaFn && "WorldAdminRules.overlaps(" in areaFn &&
            "WorldGuardBuild.Result.DENIED, WorldGuardBuild.Result.UNKNOWN" in areaFn)
    check("ClaimManager exposes a world's claims", "fun getClaimsInWorld(worldName: String)" in source("manager/ClaimManager.kt"))

    // ── plugin.yml / config.yml ─────────────────────────────
    val perms = resource("plugin.yml")["permissions"] as Map<*, *>
    check("joshymc.world.delete is declared op", (perms["joshymc.world.delete"] as? Map<*, *>)?.get("default") == "op")
    val config = resource("config.yml")
    check("config has world-management.protected-worlds", ((config["world-management"] as? Map<*, *>)?.get("protected-worlds") as? List<*>) != null)
    check("config has gencave.blocked-worlds", ((config["gencave"] as? Map<*, *>)?.get("blocked-worlds") as? List<*>) != null)

    println("OK: $checks world admin checks passed.")
}
