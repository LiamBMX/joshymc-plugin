// Checks custom crate model visibility across worlds, reloads and reconnects (issue #1540).
// The Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/crate-model-visibility-check.init.gradle.kts crateModelVisibilityCheck
//
// The live entity code can't run without a server, so this models Paper's visibility semantics
// (a hidden-by-default entity is drawn for a player only if that player's session holds a
// showEntity for it, the player is in the entity's world and its chunk is loaded) and drives it
// with the real Bukkit-free rules (CrateModels.spawnViewers / canSee), mirroring what
// CrateModelDisplays does at each lifecycle point. Source wiring checks then pin the live code to
// that behaviour. Exits non-zero on the first failed check.

import com.liam.joshymc.crate.CrateModels
import java.io.File
import java.util.UUID
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

/** One connection of a player. A reconnect is a new session (Paper's CraftPlayer is new). */
private class Session(val player: String, val bedrock: Boolean, var world: String) {
    val shown = mutableSetOf<UUID>()
}

private data class Entity(val id: UUID, val world: String, val chunk: Pair<Int, Int>, val location: String, val model: String, val hitbox: Boolean)

/** A mirror of CrateModelDisplays' visibility bookkeeping over the modelled server. */
private class Server {
    val online = linkedMapOf<String, Session>()
    val packLoaded = mutableSetOf<String>()
    val entities = mutableMapOf<UUID, Entity>()
    val loadedChunks = mutableSetOf<Pair<String, Pair<Int, Int>>>()

    /** location -> (display, hitbox?) */
    val spawned = mutableMapOf<String, Pair<UUID, UUID?>>()

    fun eligible(s: Session) = CrateModels.canSee(s.bedrock, s.player in packLoaded)

    fun spawn(location: String, world: String, chunk: Pair<Int, Int>, model: String, hitbox: Boolean) {
        if ((world to chunk) !in loadedChunks) return // never loads chunks
        remove(location)
        val display = Entity(UUID.randomUUID(), world, chunk, location, model, false)
        val box = if (hitbox) Entity(UUID.randomUUID(), world, chunk, location, model, true) else null
        entities[display.id] = display
        box?.let { entities[it.id] = it }
        spawned[location] = display.id to box?.id
        for (s in CrateModels.spawnViewers(online.values, { it.bedrock }, { it.player in packLoaded })) {
            s.shown += display.id
            box?.let { s.shown += it.id }
        }
    }

    fun remove(location: String) {
        val (d, h) = spawned.remove(location) ?: return
        entities.remove(d)
        h?.let { entities.remove(it) }
    }

    fun applyVisibility(s: Session) {
        val show = eligible(s)
        for ((d, h) in spawned.values) for (id in listOfNotNull(d, h)) if (show) s.shown += id else s.shown -= id
    }

    fun join(player: String, world: String, bedrock: Boolean = false): Session =
        Session(player, bedrock, world).also { online[player] = it }

    fun quit(player: String) {
        online.remove(player)
        packLoaded.remove(player)
    }

    fun pack(player: String, loaded: Boolean) {
        if (loaded) packLoaded += player else packLoaded -= player
        online[player]?.let { applyVisibility(it) }
    }

    fun unloadChunk(world: String, chunk: Pair<Int, Int>) {
        loadedChunks -= world to chunk
        for ((loc, ids) in spawned.entries.toList()) if (entities[ids.first]?.let { it.world == world && it.chunk == chunk } == true) remove(loc)
    }

    /** What [s]'s client draws: the models of the displays it is shown, in its world. */
    fun drawn(s: Session): List<String> = entities.values
        .filter { !it.hitbox && it.id in s.shown && it.world == s.world && (it.world to it.chunk) in loadedChunks }
        .map { "${it.location}=${it.model}" }.sorted()

    fun hitboxesFor(s: Session): Int = entities.values.count { it.hitbox && it.id in s.shown && it.world == s.world }
}

fun main() {
    val crateWorld = "spawn"
    val other = "world"
    val c0 = 0 to 0

    fun server(): Server = Server().apply { loadedChunks += crateWorld to c0 }

    // 1. Player in the crate world when the display spawns.
    run {
        val sv = server()
        val a = sv.join("a", crateWorld); sv.pack("a", true)
        sv.spawn("L1", crateWorld, c0, "joshymc:cabin_crate", hitbox = true)
        check("1 player in the crate world sees the model", sv.drawn(a) == listOf("L1=joshymc:cabin_crate"))
        check("21 that player gets the hitbox", sv.hitboxesFor(a) == 1)
    }

    // 2/3/4. Player in another world when it spawns, arrives later, leaves and comes back.
    run {
        val sv = server()
        val b = sv.join("b", other); sv.pack("b", true)
        sv.spawn("L1", crateWorld, c0, "joshymc:harvest_chest", hitbox = false)
        check("2 player elsewhere draws nothing there", sv.drawn(b).isEmpty())
        b.world = crateWorld // teleport / portal / respawn: same session
        check("3 the player arriving later sees the model (CR-P1-02)", sv.drawn(b) == listOf("L1=joshymc:harvest_chest"))
        b.world = other; b.world = crateWorld
        check("4 leaving and returning keeps it", sv.drawn(b) == listOf("L1=joshymc:harvest_chest"))
    }

    // 5. Reconnect: the new session starts with nothing shown until the pack loads again.
    run {
        val sv = server()
        sv.join("c", other); sv.pack("c", true)
        sv.spawn("L1", crateWorld, c0, "joshymc:stockpile_chest", hitbox = false)
        sv.quit("c")
        check("5 quit forgets the pack state", "c" !in sv.packLoaded)
        val c2 = sv.join("c", crateWorld)
        check("5 before the pack reloads the client keeps the vanilla chest", sv.drawn(c2).isEmpty())
        sv.pack("c", true)
        check("5 after the pack reloads the reconnected player sees it", sv.drawn(c2) == listOf("L1=joshymc:stockpile_chest"))
    }

    // 6. Respawn in the crate world from elsewhere (same session).
    run {
        val sv = server()
        val d = sv.join("d", "world_nether"); sv.pack("d", true)
        sv.spawn("L1", crateWorld, c0, "joshymc:homestead_crate", hitbox = false)
        d.world = crateWorld
        check("6 respawning into the crate world shows it", sv.drawn(d).size == 1)
    }

    // 7/8/9. /joshymc reload, restart and /crate models refresh redraw everything (new entity ids).
    run {
        val sv = server()
        val e = sv.join("e", other); sv.pack("e", true)
        val f = sv.join("f", crateWorld); sv.pack("f", true)
        sv.spawn("L1", crateWorld, c0, "joshymc:campfire_crate", hitbox = true)
        // refreshAll / stop+start: everything removed and spawned again, pack state kept by the instance.
        for (loc in sv.spawned.keys.toList()) sv.remove(loc)
        sv.spawn("L1", crateWorld, c0, "joshymc:campfire_crate", hitbox = true)
        check("7/9 player already there still sees it after a refresh", sv.drawn(f).size == 1 && sv.hitboxesFor(f) == 1)
        e.world = crateWorld
        check("7/9 player elsewhere during a reload sees it on arrival", sv.drawn(e).size == 1 && sv.hitboxesFor(e) == 1)
        check("19 refresh leaves one display per location", sv.entities.values.count { !it.hitbox } == 1)
        check("20 refresh leaves no orphan hitbox", sv.entities.values.count { it.hitbox } == 1)
        // 8. Restart: everyone reconnects; drawn once their pack loads.
        val restarted = server()
        restarted.spawn("L1", crateWorld, c0, "joshymc:campfire_crate", hitbox = true)
        val e2 = restarted.join("e", crateWorld); restarted.pack("e", true)
        check("8 after a restart the model shows once the pack loads", restarted.drawn(e2).size == 1)
    }

    // 10. Chunk unload/reload.
    run {
        val sv = server()
        val g = sv.join("g", other); sv.pack("g", true)
        sv.spawn("L1", crateWorld, c0, "joshymc:cabin_crate", hitbox = true)
        sv.unloadChunk(crateWorld, c0)
        check("10 unloading removes display and hitbox", sv.entities.isEmpty() && sv.spawned.isEmpty())
        sv.spawn("L1", crateWorld, c0, "joshymc:cabin_crate", hitbox = true)
        check("10 never spawns into an unloaded chunk", sv.entities.isEmpty())
        sv.loadedChunks += crateWorld to c0
        sv.spawn("L1", crateWorld, c0, "joshymc:cabin_crate", hitbox = true)
        g.world = crateWorld
        check("10 after reload the player arriving from elsewhere sees it", sv.drawn(g).size == 1 && sv.hitboxesFor(g) == 1)
    }

    // 11/12/23. Monthly rotation, admin override and /crate models set redraw one location.
    run {
        val sv = server()
        val h = sv.join("h", other); sv.pack("h", true)
        val i = sv.join("i", crateWorld); sv.pack("i", true)
        sv.spawn("L1", crateWorld, c0, "joshymc:november_crate", hitbox = true)
        sv.spawn("L1", crateWorld, c0, "joshymc:december_crate", hitbox = true) // refresh(world, x, y, z)
        check("11 player there sees the new month's model only", sv.drawn(i) == listOf("L1=joshymc:december_crate"))
        h.world = crateWorld
        check("11 player arriving later sees the new month's model", sv.drawn(h) == listOf("L1=joshymc:december_crate"))
        check("11 the old month's display is gone", sv.entities.values.none { it.model == "joshymc:november_crate" })
        sv.spawn("L1", crateWorld, c0, "joshymc:june_crate", hitbox = true) // override
        check("12 override uses the same visibility", sv.drawn(h) == listOf("L1=joshymc:june_crate") && sv.drawn(i) == sv.drawn(h))
        sv.spawn("L1", crateWorld, c0, "joshymc:harvest_chest", hitbox = false) // reassignment
        check("23 reassigned model visible to both", sv.drawn(h) == listOf("L1=joshymc:harvest_chest") && sv.drawn(i) == sv.drawn(h))
        check("21 a model without a hitbox leaves none", sv.entities.values.none { it.hitbox })
    }

    // 13/14/15/16/17. Pack states and Bedrock, several players at once, in different worlds.
    run {
        val sv = server()
        val ok = sv.join("ok", other); sv.pack("ok", true)
        val declined = sv.join("declined", other); sv.pack("declined", false)
        val failed = sv.join("failed", crateWorld); sv.pack("failed", false)
        val pending = sv.join("pending", other)
        val bedrock = sv.join("bedrock", other, bedrock = true); sv.pack("bedrock", true)
        sv.spawn("L1", crateWorld, c0, "joshymc:cabin_crate", hitbox = true)
        for (s in sv.online.values) s.world = crateWorld
        check("13 accepted pack sees the model", sv.drawn(ok).size == 1)
        check("14 declined pack keeps the vanilla chest", sv.drawn(declined).isEmpty() && sv.hitboxesFor(declined) == 0)
        check("15 failed download keeps the vanilla chest", sv.drawn(failed).isEmpty())
        check("17 no status yet keeps the vanilla chest", sv.drawn(pending).isEmpty())
        check("16 Bedrock never sees the display or hitbox", sv.drawn(bedrock).isEmpty() && sv.hitboxesFor(bedrock) == 0)
        sv.pack("pending", true)
        check("13 pack loading later shows existing displays", sv.drawn(pending).size == 1)
        sv.pack("ok", false)
        check("14 a pack discarded later hides them again", sv.drawn(ok).isEmpty())
    }

    // 18/19. Several crates near one another.
    run {
        val sv = server()
        val j = sv.join("j", other); sv.pack("j", true)
        val models = listOf("joshymc:harvest_chest", "joshymc:stockpile_chest", "joshymc:homestead_crate", "joshymc:campfire_crate", "joshymc:cabin_crate")
        models.forEachIndexed { n, m -> sv.spawn("L$n", crateWorld, c0, m, hitbox = n % 2 == 0) }
        models.forEachIndexed { n, m -> sv.spawn("L$n", crateWorld, c0, m, hitbox = n % 2 == 0) } // redrawn twice
        j.world = crateWorld
        check("18 every nearby crate shows its own model", sv.drawn(j) == models.mapIndexed { n, m -> "L$n=$m" }.sorted())
        check("19 no duplicate displays", sv.entities.values.count { !it.hitbox } == models.size)
        check("20 no orphan hitboxes", sv.entities.values.count { it.hitbox } == 3)
    }

    // All 17 model ids go through the same rule (world never matters).
    val ids = listOf("harvest", "stockpile").map { "joshymc:${it}_chest" } +
        listOf("homestead", "campfire", "cabin").map { "joshymc:${it}_crate" } +
        java.time.Month.entries.map { "joshymc:${it.name.lowercase()}_crate" }
    check("17 model ids listed", ids.size == 17)
    run {
        val sv = server()
        val k = sv.join("k", other); sv.pack("k", true)
        ids.forEachIndexed { n, m -> sv.spawn("M$n", crateWorld, c0, m, hitbox = true) }
        k.world = crateWorld
        check("all 17 models visible after arriving", sv.drawn(k).size == 17)
    }

    // ── The rule itself ──
    val viewers = CrateModels.spawnViewers(listOf("java", "bedrock", "nopack"), { it == "bedrock" }, { it != "nopack" })
    check("spawnViewers keeps only eligible players", viewers == listOf("java"))
    check("spawnViewers on nobody is empty", CrateModels.spawnViewers(emptyList<String>(), { false }, { true }).isEmpty())

    // ── Wiring in the live code ──
    val displays = File("src/main/kotlin/com/liam/joshymc/crate/CrateModelDisplays.kt").readText()
    val spawnBody = displays.substringAfter("private fun spawn(loc: CrateManager.CrateLocation)").substringBefore("\n    }\n")
    check("spawn shows to every online eligible player", "CrateModels.spawnViewers(Bukkit.getOnlinePlayers()" in spawnBody)
    check("spawn no longer limits showing to the crate world", "world.players" !in spawnBody)
    check("display and hitbox both shown", "player.showEntity(plugin, display)" in spawnBody && "player.showEntity(plugin, it)" in spawnBody)
    check("display stays hidden by default (pack/Bedrock fallback)", spawnBody.substringAfter("ItemDisplay::class.java) { d ->").contains("isVisibleByDefault = false"))
    check("hitbox stays hidden by default", spawnBody.substringAfter("Interaction::class.java) { i ->").contains("isVisibleByDefault = false"))
    check("entities are never saved", spawnBody.contains("d.isPersistent = false") && spawnBody.contains("i.isPersistent = false"))
    check("old entities removed before a redraw", spawnBody.indexOf("remove(key)") in 0 until spawnBody.indexOf("world.spawn("))
    val apply = displays.substringAfter("private fun applyVisibility(player: Player)").substringBefore("\n    }\n")
    check("pack status applies to every drawn crate, any world", "spawned.values" in apply && "world" !in apply)
    check("eligibility is still canSee(bedrock, pack)", "CrateModels.canSee(BedrockUtil.isBedrockPlayer(player), player.uniqueId in packLoaded)" in displays)
    check("quit drops pack state (no lingering player refs)", displays.substringAfter("fun onQuit(event: PlayerQuitEvent)").substringBefore("}").contains("packLoaded.remove("))
    check("pack state holds UUIDs only", "mutableSetOf<UUID>()" in displays && "mutableSetOf<Player>" !in displays)
    check("no repeating task", "runTaskTimer" !in displays)
    check("never loads chunks", "getChunkAtAsync" !in displays && "loadChunk" !in displays)

    println("All $checks crate model visibility checks passed.")
}
