// Checks the Ranked Team Wars arena interference protection (issue #1507, audit finding F-6):
// the Bukkit-free rules in RankedArenaGuard, and the wiring in RankedArenaGuardListener that
// feeds them from events. The Gradle build has no test source set, so it runs through an init
// script, from the repo root:
//
//   ./gradlew -I scripts/ranked-arena-guard-check.init.gradle.kts rankedArenaGuardCheck
//
// Exits non-zero on the first failed check. Live-server steps: docs/lifesteal-ranked-beta.md, section L.

import com.liam.joshymc.lifesteal.ranked.Box
import com.liam.joshymc.lifesteal.ranked.RankedArena
import com.liam.joshymc.lifesteal.ranked.RankedArenaGuard
import com.liam.joshymc.lifesteal.ranked.RankedArenaGuard.Actor
import com.liam.joshymc.lifesteal.ranked.RankedArenaGuard.Pos
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

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private const val W = "lifesteal"

private fun arena(id: String, box: Box?, world: String = W) = RankedArena(id = id, displayName = id, world = world, bounds = box, enabled = true)

private val A = arena("a", Box(0, 60, 0, 20, 70, 20))
private val B = arena("b", Box(100, 60, 0, 120, 70, 20))
private val C = arena("c", Box(0, 60, 100, 20, 70, 120))

fun main() {
    // ── Zones: which arenas are protected (lifecycle) ───────
    val none = RankedArenaGuard.zones(emptyList(), emptyList())
    check("no match and no reservation = nothing protected (restart, after cancel/completion)", none.isEmpty())

    val zones = RankedArenaGuard.zones(listOf("m1" to A, "m2" to B), listOf(C))
    check("two matches + one reservation = three zones (multiple simultaneous matches)", zones.size == 3)
    check("a match's zone carries its match id", RankedArenaGuard.zoneAt(zones, W, 5, 65, 5)?.matchId == "m1")
    check("the second match's zone is its own", RankedArenaGuard.zoneAt(zones, W, 110, 65, 5)?.matchId == "m2")
    check("a queue reservation is protected without a match (arena reservation)", RankedArenaGuard.zoneAt(zones, W, 5, 65, 110)?.let { it.arenaId == "c" && it.matchId == null } == true)
    val dup = RankedArenaGuard.zones(listOf("m1" to A), listOf(A))
    check("an arena both matched and reserved is one zone, the match's", dup.size == 1 && dup[0].matchId == "m1")
    check("an arena without a boundary is never a zone", RankedArenaGuard.zones(listOf("m" to arena("x", null)), emptyList()).isEmpty())
    check("same coordinates in another world aren't protected", RankedArenaGuard.zoneAt(zones, "world", 5, 65, 5) == null)
    check("box corners are inclusive", RankedArenaGuard.zoneAt(zones, W, 20, 70, 20) != null && RankedArenaGuard.zoneAt(zones, W, 0, 60, 0) != null)
    check("one block past the corner is outside", RankedArenaGuard.zoneAt(zones, W, 21, 65, 5) == null && RankedArenaGuard.zoneAt(zones, W, 5, 59, 5) == null)

    // ── Player block changes (1-3, 6) ───────────────────────
    check("1: placing a block inside an arena in use is refused", RankedArenaGuard.playerChangeRefused(zones, W, Pos(20, 65, 10)))
    check("2: breaking an arena block is refused", RankedArenaGuard.playerChangeRefused(zones, W, Pos(10, 60, 10)))
    check("3: placing right outside the boundary is allowed", !RankedArenaGuard.playerChangeRefused(zones, W, Pos(21, 65, 10)))
    check("3: placing next to an idle arena is allowed (nothing protected)", !RankedArenaGuard.playerChangeRefused(none, W, Pos(10, 65, 10)))

    // ── Fluids (4-6, 12) ────────────────────────────────────
    check("4: water flowing from outside into the arena is refused", RankedArenaGuard.crossesInto(zones, W, Pos(21, 65, 10), Pos(20, 65, 10)))
    check("5: lava flowing down over the boundary is refused", RankedArenaGuard.crossesInto(zones, W, Pos(10, 71, 10), Pos(10, 70, 10)))
    check("fluids already inside the arena keep flowing inside", !RankedArenaGuard.crossesInto(zones, W, Pos(10, 65, 10), Pos(11, 65, 10)))
    check("fluid leaving the arena isn't refused (the arena doesn't change)", !RankedArenaGuard.crossesInto(zones, W, Pos(20, 65, 10), Pos(21, 65, 10)))
    check("22: fluids outside every arena are untouched", !RankedArenaGuard.crossesInto(zones, W, Pos(50, 65, 50), Pos(51, 65, 50)))
    check("fluid from one arena into another is refused", RankedArenaGuard.crossesInto(listOf(RankedArenaGuard.Zone("a", "m1", W, Box(0, 60, 0, 20, 70, 20)), RankedArenaGuard.Zone("d", "m3", W, Box(21, 60, 0, 40, 70, 20))), W, Pos(20, 65, 5), Pos(21, 65, 5)))
    check("12: a dispenser outside facing into the arena is refused", RankedArenaGuard.crossesInto(zones, W, Pos(-1, 65, 10), Pos(0, 65, 10)))
    check("12: a dispenser inside facing inside is allowed", !RankedArenaGuard.crossesInto(zones, W, Pos(5, 65, 10), Pos(6, 65, 10)))
    check("a change with no known source into the arena is refused", RankedArenaGuard.crossesInto(zones, W, null, Pos(5, 65, 5)))

    // ── Pistons (7-10) ──────────────────────────────────────
    // A piston at x=-2 facing +x: head at x=-1, pushing the block at x=-1... into x=0.
    check("7: a piston pushing a block into the arena is refused",
        RankedArenaGuard.pistonRefused(zones, W, Pos(-2, 65, 10), Pos(-1, 65, 10), listOf(Pos(-1, 65, 10)), 1, 0, 0))
    // A sticky piston at x=-2 facing +x retracting, pulling the block at x=0 back to x=-1.
    check("8: a sticky piston pulling an arena block out is refused",
        RankedArenaGuard.pistonRefused(zones, W, Pos(-2, 65, 10), Pos(-1, 65, 10), listOf(Pos(0, 65, 10)), -1, 0, 0))
    // A slime block outside drags a neighbour that sits inside the arena.
    check("9: slime blocks dragging an arena block across are refused",
        RankedArenaGuard.pistonRefused(zones, W, Pos(-3, 65, 10), Pos(-2, 65, 10), listOf(Pos(-2, 65, 10), Pos(-2, 65, 11), Pos(0, 66, 9)), 0, 0, 1))
    check("10: honey blocks moving along the boundary into it are refused",
        RankedArenaGuard.pistonRefused(zones, W, Pos(0, 61, 23), Pos(0, 61, 22), listOf(Pos(0, 61, 22), Pos(1, 61, 21)), 0, 0, -1))
    check("a piston head reaching into the arena is refused",
        RankedArenaGuard.pistonRefused(zones, W, Pos(-1, 65, 10), Pos(0, 65, 10), emptyList(), 1, 0, 0))
    check("a piston inside the arena pushing out is refused",
        RankedArenaGuard.pistonRefused(zones, W, Pos(19, 65, 10), Pos(20, 65, 10), listOf(Pos(20, 65, 10)), 1, 0, 0))
    check("a piston moving blocks entirely inside one arena is allowed",
        !RankedArenaGuard.pistonRefused(zones, W, Pos(5, 65, 10), Pos(6, 65, 10), listOf(Pos(6, 65, 10), Pos(7, 65, 10)), 1, 0, 0))
    check("22: pistons away from arenas are untouched",
        !RankedArenaGuard.pistonRefused(zones, W, Pos(50, 65, 50), Pos(51, 65, 50), listOf(Pos(51, 65, 50)), 1, 0, 0))
    check("22: no zones = no piston check",
        !RankedArenaGuard.pistonRefused(none, W, Pos(-2, 65, 10), Pos(-1, 65, 10), listOf(Pos(-1, 65, 10)), 1, 0, 0))

    // ── Fire (13) ───────────────────────────────────────────
    check("13: fire spreading in from outside is refused", RankedArenaGuard.crossesInto(zones, W, Pos(-1, 61, 5), Pos(0, 61, 5)))
    check("13: an outsider's flint and steel is refused", RankedArenaGuard.ignitionRefused(Actor.OUTSIDER, sourceInZone = false, crystal = false))
    check("13: lightning / unattributed fire from outside is refused", RankedArenaGuard.ignitionRefused(Actor.NONE, sourceInZone = false, crystal = false))
    check("a participant's flint and steel stays (combat unchanged)", !RankedArenaGuard.ignitionRefused(Actor.PARTICIPANT, sourceInZone = false, crystal = false))
    check("fire already in the arena may spread inside it", !RankedArenaGuard.ignitionRefused(Actor.NONE, sourceInZone = true, crystal = false))
    check("End Crystal fire stays", !RankedArenaGuard.ignitionRefused(Actor.NONE, sourceInZone = false, crystal = true))

    // ── Hazards sent in (11, 12, 14) ────────────────────────
    check("11: an unattributed explosion (TNT cannon) doesn't hurt participants", RankedArenaGuard.outsideHazard("ENTITY_EXPLOSION", crystal = false, sourceOutsideArena = true))
    check("11: a block explosion (bed/anchor) doesn't hurt participants", RankedArenaGuard.outsideHazard("BLOCK_EXPLOSION", crystal = false, sourceOutsideArena = true))
    check("End Crystal explosions still hurt (combat unchanged)", !RankedArenaGuard.outsideHazard("ENTITY_EXPLOSION", crystal = true, sourceOutsideArena = false))
    check("12: dispenser arrows from outside don't hurt participants", RankedArenaGuard.outsideHazard("PROJECTILE", crystal = false, sourceOutsideArena = true))
    check("14: falling blocks dropped from outside don't hurt participants", RankedArenaGuard.outsideHazard("FALLING_BLOCK", crystal = false, sourceOutsideArena = true))
    check("falling blocks from inside the arena are left alone", !RankedArenaGuard.outsideHazard("FALLING_BLOCK", crystal = false, sourceOutsideArena = false))
    check("fall damage / lava / drowning are never touched", listOf("FALL", "LAVA", "DROWNING", "FIRE_TICK", "VOID").none { RankedArenaGuard.outsideHazard(it, false, true) })

    // ── Listener wiring ─────────────────────────────────────
    val listener = source("lifesteal/ranked/RankedArenaGuardListener.kt")
    val listeners = source("manager/ListenerManager.kt")
    check("registered with the ranked listener, only where Lifesteal is hosted",
        Regex("""lifestealRankedManager\.hosted\) \{[\s\S]{0,400}RankedArenaGuardListener\(plugin\)""").containsMatchIn(listeners))
    for (event in listOf(
        "BlockPlaceEvent", "BlockBreakEvent", "PlayerBucketEmptyEvent", "PlayerBucketFillEvent", "SignChangeEvent",
        "PlayerInteractEvent", "HangingPlaceEvent", "HangingBreakEvent", "EntityPlaceEvent", "BlockFromToEvent",
        "BlockSpreadEvent", "BlockIgniteEvent", "BlockBurnEvent", "StructureGrowEvent", "BlockFertilizeEvent",
        "SpongeAbsorbEvent", "BlockPistonExtendEvent", "BlockPistonRetractEvent", "BlockDispenseEvent",
        "EntityChangeBlockEvent", "EntityExplodeEvent", "BlockExplodeEvent",
    )) check("listener handles $event", Regex("""fun \w+\(event: $event\)""").containsMatchIn(listener))
    check("multi-block placements (beds, doors) check every block", "BlockMultiPlaceEvent" in listener && "replacedBlockStates" in listener)
    check("23: handlers never un-cancel (WorldGuard/claims decide first)", "isCancelled = false" !in listener && "Event.Result.ALLOW" !in listener)
    val handlers = Regex("""@EventHandler\(([^)]*)\)\s*fun (\w+)\(event: (\w+)\)""").findAll(listener).toList()
    check("handlers were found", handlers.size >= 25)
    for (h in handlers) {
        val (args, name, type) = h.destructured
        if (type == "PlayerInteractEvent" || type == "PlayerQuitEvent") continue
        check("$name ignores already-cancelled events", "ignoreCancelled = true" in args)
    }
    check("24: zones rebuilt at most once per tick, from memory only", "Bukkit.getCurrentTick()" in listener && "databaseManager" !in listener)
    check("24: zones come from open matches and queue reservations", "ranked.openMatches()" in listener && "reservedArenas()" in listener)
    check("hint throttle is cleared on quit (no leak)", Regex("""fun onQuit[\s\S]{0,120}lastHint\.remove""").containsMatchIn(listener))
    check("hazard rule only applies to ACTIVE matches with no player behind the hit",
        Regex("""fun onHazard[\s\S]*?MatchState\.ACTIVE[\s\S]*?playerBehind\(event\) != null\) return""").containsMatchIn(listener))

    // ── Lifecycle (15-20) through the manager ───────────────
    val manager = source("lifesteal/ranked/LifestealRankedManager.kt")
    check("15/16/17: a match enters openMatches at PREPARING and stays through countdown/fight/finishing",
        Regex("""match\.state = MatchState\.PREPARING\s*\n\s*matches\[id\] = match""").containsMatchIn(manager))
    check("18/19: release (after cancel or completion cleanup) drops the match, so its arena stops being protected",
        Regex("""private fun release\(match: Match\) \{[\s\S]*?matches\.remove\(match\.id\)""").containsMatchIn(manager))
    check("20: matches are in memory only (cleared at shutdown, nothing restored as protected)", "matches.clear()" in manager)
    check("the queue's reservations are proposals still open", Regex("""fun reservedArenas\(\): Set<String> = proposals\.values\.filter \{ !it\.state\.terminal \}""").containsMatchIn(source("lifesteal/ranked/queue/RankedQueue.kt")))

    // ── Untouched systems ───────────────────────────────────
    for (forbidden in listOf("transferPvp", "EloRating", "restore(", "snapshot", "RatingStore")) {
        check("the guard doesn't touch $forbidden", forbidden !in listener && forbidden !in source("lifesteal/ranked/RankedArenaGuard.kt"))
    }

    println("Ranked arena guard checks passed ($checks checks).")
}
