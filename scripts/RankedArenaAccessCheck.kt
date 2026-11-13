// Checks the Ranked Team Wars arena access and spectator integrity rules (issue #1524): the
// Bukkit-free rules in RankedArenaAccess, and the wiring in LifestealRankedManager,
// LifestealRankedListener and RankedArenaGuardListener that feeds them. The Gradle build has no
// test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/ranked-arena-access-check.init.gradle.kts rankedArenaAccessCheck
//
// Exits non-zero on the first failed check. Live-server steps: docs/lifesteal-ranked-beta.md, section M.

import com.liam.joshymc.lifesteal.ranked.MatchState
import com.liam.joshymc.lifesteal.ranked.RankedArenaAccess
import com.liam.joshymc.lifesteal.ranked.RankedArenaAccess.HarmFacts
import com.liam.joshymc.lifesteal.ranked.RankedArenaAccess.Occupant
import com.liam.joshymc.lifesteal.ranked.RankedArenaAccess.Relocation
import com.liam.joshymc.lifesteal.ranked.RankedArenaAccess.RelocationFacts
import com.liam.joshymc.lifesteal.ranked.RankedArenaAccess.Source
import com.liam.joshymc.lifesteal.ranked.RankedArenaAccess.Zone
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

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private val P1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
private val P2 = UUID.fromString("00000000-0000-0000-0000-000000000002")
private val OUT = UUID.fromString("00000000-0000-0000-0000-0000000000ff")

private fun harm(
    source: Source,
    victimSpectator: Boolean = false,
    attackerSpectator: Boolean = false,
    victimState: MatchState? = MatchState.ACTIVE,
    attackerHeld: Boolean = false,
) = RankedArenaAccess.harmBlocked(HarmFacts(source, victimSpectator, attackerSpectator, victimState, attackerHeld))

private fun reloc(
    occupant: Occupant = Occupant.OUTSIDER,
    dead: Boolean = false,
    beingMoved: Boolean = false,
    teleportProblem: String? = null,
    hasDestination: Boolean = true,
    lastAttemptAt: Long? = null,
    now: Long = 100_000L,
) = RankedArenaAccess.relocation(RelocationFacts(occupant, dead, beingMoved, teleportProblem, hasDestination, lastAttemptAt, now))

fun main() {
    // ── Entry (1-4, 21, 27) ─────────────────────────────────
    val matchZone = Zone("a", "m1")
    val otherZone = Zone("b", "m2")
    val reserved = Zone("c", null, setOf(P1, P2))
    check("1: a participant of the match is a participant of its arena", RankedArenaAccess.occupant(matchZone, P1, "m1") == Occupant.PARTICIPANT)
    check("1: participants may enter", !RankedArenaAccess.entryRefused(Occupant.PARTICIPANT))
    check("27: a participant of another match is an outsider here (simultaneous matches)", RankedArenaAccess.occupant(otherZone, P1, "m1") == Occupant.OUTSIDER)
    check("3: anyone else is an outsider and may not enter", RankedArenaAccess.occupant(matchZone, OUT, null) == Occupant.OUTSIDER && RankedArenaAccess.entryRefused(Occupant.OUTSIDER))
    check("2: spectators watch from the spectator point (outside the boundary), so inside they are outsiders",
        RankedArenaAccess.occupant(matchZone, OUT, null) == Occupant.OUTSIDER)
    check("21: a reserved arena admits its proposal's players", RankedArenaAccess.occupant(reserved, P2, null) == Occupant.RESERVED_PLAYER && !RankedArenaAccess.entryRefused(Occupant.RESERVED_PLAYER))
    check("21: a reserved arena refuses everybody else", RankedArenaAccess.occupant(reserved, OUT, null) == Occupant.OUTSIDER)

    // ── Relocation sweep (3, 4, no loops) ───────────────────
    check("authorized players are never moved", reloc(occupant = Occupant.PARTICIPANT) == Relocation.STAY && reloc(occupant = Occupant.RESERVED_PLAYER) == Relocation.STAY)
    check("3: an outsider inside is moved out", reloc() == Relocation.MOVE)
    check("4: a combat-tagged outsider isn't moved (no combat escape); interference stays refused", reloc(teleportProblem = "in combat") == Relocation.DENY_ONLY)
    check("4: combat wins over everything after the authorization check", reloc(teleportProblem = "in combat", hasDestination = false, lastAttemptAt = 99_000L) == Relocation.DENY_ONLY)
    check("no safe destination = deny only (never a teleport into danger)", reloc(hasDestination = false) == Relocation.DENY_ONLY)
    check("dead players wait for their respawn", reloc(dead = true) == Relocation.STAY)
    check("players this system is moving (restore, spectate, teleport) are left alone", reloc(beingMoved = true) == Relocation.STAY)
    check("no teleport loop: a retry waits RETRY_MILLIS", reloc(lastAttemptAt = 100_000L - RankedArenaAccess.RETRY_MILLIS + 1) == Relocation.WAIT)
    check("after RETRY_MILLIS the move is tried again", reloc(lastAttemptAt = 100_000L - RankedArenaAccess.RETRY_MILLIS) == Relocation.MOVE)
    check("a destination inside a protected arena is never used", !RankedArenaAccess.destinationUsable(insideProtectedArena = true, safetyProblem = null))
    check("an unsafe destination is never used", !RankedArenaAccess.destinationUsable(false, "no floor"))
    check("a safe destination outside every arena is used", RankedArenaAccess.destinationUsable(false, null))

    // ── External damage (5-12, 19, 20) ──────────────────────
    check("5/6/7/8/9: outsider melee, projectiles, potions, clouds, TNT on an ACTIVE participant are refused", harm(Source.OTHER_PLAYER))
    check("10: an outsider's tamed mob (owner online) is an outsider", harm(Source.OTHER_PLAYER))
    check("10: a tamed mob whose owner is offline is refused, not treated as environment", harm(Source.ABSENT_OWNER))
    check("11: participant-to-participant damage in the fight stays", !harm(Source.SAME_FIGHT, attackerHeld = true))
    check("12: participant projectiles/pets in the fight stay (same source kind)", !harm(Source.SAME_FIGHT, attackerHeld = true))
    check("environmental damage in the fight stays", !harm(Source.NONE))
    check("self damage (own TNT/potion) stays", !harm(Source.SELF, attackerHeld = true))
    check("no damage at all before the fight (preparation, countdown)", harm(Source.NONE, victimState = MatchState.COUNTDOWN) && harm(Source.SAME_FIGHT, victimState = MatchState.PREPARING, attackerHeld = true))
    check("no damage while the match is finishing", harm(Source.SAME_FIGHT, victimState = MatchState.FINISHING, attackerHeld = true))
    check("19: spectators take no damage, whatever the source", Source.entries.all { harm(it, victimSpectator = true, victimState = null) })
    check("19/20: spectators deal no damage to participants (so they never enter heart attribution)", harm(Source.OTHER_PLAYER, attackerSpectator = true))
    check("19: spectators deal no damage to ordinary players either while they watch", harm(Source.OTHER_PLAYER, attackerSpectator = true, victimState = null))
    check("a held participant can't hurt players outside their match", harm(Source.OTHER_PLAYER, attackerHeld = true, victimState = null))
    check("ordinary Lifesteal PvP outside any match is untouched", !harm(Source.OTHER_PLAYER, victimState = null))
    check("ordinary pets of absent owners outside any match are untouched", !harm(Source.ABSENT_OWNER, victimState = null))

    // ── Pets ────────────────────────────────────────────────
    check("10: an outsider's pet can't target a participant", RankedArenaAccess.petTargetRefused(victimHeld = true, victimSpectator = false, ownerInFight = false))
    check("a participant's own pet in the fight may", !RankedArenaAccess.petTargetRefused(true, false, ownerInFight = true))
    check("no pet targets a spectator", RankedArenaAccess.petTargetRefused(false, victimSpectator = true, ownerInFight = false))
    check("pets outside any match are untouched", !RankedArenaAccess.petTargetRefused(false, false, false))

    // ── Entities in the arena (16, 9) ───────────────────────
    check("16: spectators can't hurt any entity", RankedArenaAccess.entityHarmRefused(Source.OTHER_PLAYER, attackerSpectator = true, attackerParticipantOfZone = false, victimInZone = false))
    check("9: an outsider can't detonate a crystal in the arena from outside", RankedArenaAccess.entityHarmRefused(Source.OTHER_PLAYER, false, attackerParticipantOfZone = false, victimInZone = true))
    check("an absent owner's pet can't hurt entities in the arena", RankedArenaAccess.entityHarmRefused(Source.ABSENT_OWNER, false, false, true))
    check("participants still hit crystals in their arena", !RankedArenaAccess.entityHarmRefused(Source.OTHER_PLAYER, false, attackerParticipantOfZone = true, victimInZone = true))
    check("entities outside every arena are untouched for non-spectators", !RankedArenaAccess.entityHarmRefused(Source.OTHER_PLAYER, false, false, victimInZone = false))
    check("damage with no player behind it is left to the match", !RankedArenaAccess.entityHarmRefused(Source.NONE, true, false, true))

    check("spawn eggs in an arena in use are refused", RankedArenaAccess.spawnRefused("SPAWNER_EGG", inZone = true))
    check("dispensed eggs, thrown eggs, mob buckets, built golems are refused", listOf("DISPENSE_EGG", "EGG", "BUCKET", "BUILD_IRONGOLEM", "BUILD_WITHER").all { RankedArenaAccess.spawnRefused(it, true) })
    check("natural spawns are never refused (legitimate mechanics)", listOf("NATURAL", "SPAWNER", "TRIAL_SPAWNER", "CUSTOM", "REINFORCEMENTS").none { RankedArenaAccess.spawnRefused(it, true) })
    check("nothing is refused outside an arena in use", !RankedArenaAccess.spawnRefused("SPAWNER_EGG", inZone = false))
    check("entities can't teleport or portal in from outside", RankedArenaAccess.entityArrivalRefused(fromInZone = false, toInZone = true))
    check("entities already inside move as usual", !RankedArenaAccess.entityArrivalRefused(true, true))
    check("entities may always leave", !RankedArenaAccess.entityArrivalRefused(true, false))

    // ── Manager wiring (21-27) ──────────────────────────────
    val manager = source("lifesteal/ranked/LifestealRankedManager.kt")
    check("22/23/24: zones are matches that hold players (PREPARING..FINISHING)", Regex("""m\.state\.holdsPlayers""").containsMatchIn(manager))
    check("21: zones include open queue reservations with their players", Regex("""proposals\(\)\.filter \{ !it\.state\.terminal \}[\s\S]{0,700}p\.participants""").containsMatchIn(manager))
    check("24/25/26: zones are rebuilt from memory (no table), so completion, cancel and restart release them",
        Regex("""private fun buildAccessZones\(\)[\s\S]{0,1200}return out""").find(manager)?.value?.let { "store." !in it && "databaseManager" !in it } == true)
    check("the sweep is started at refresh and cancelled at stop", "startAccessSweep()" in manager && Regex("""fun stop\(\)[\s\S]{0,900}accessTask\?\.cancel\(\)""").containsMatchIn(manager))
    check("4: the sweep asks CombatTeleportGuard (never its own combat check)", "teleportProblem = com.liam.joshymc.teleport.CombatTeleportGuard.problem(plugin, player)" in manager)
    check("the sweep moves through the system's expected teleport (listener lets it through)", Regex("""Relocation\.MOVE -> \{[\s\S]{0,200}teleport\(player, target\)""").containsMatchIn(manager))
    check("relocation never loads chunks synchronously", Regex("""fun relocationTarget[\s\S]{0,900}getChunkAtAsync""").containsMatchIn(manager))
    check("per-player relocation state is cleared on quit", Regex("""fun onAccessQuit[\s\S]{0,120}relocationAttempts\.remove""").containsMatchIn(manager))
    check("spectators who leave the arena world stop spectating (no stale registration)", Regex("""fun onWorldChange\(player: Player\)[\s\S]{0,300}spectating\.remove""").containsMatchIn(manager))

    // ── Listener wiring ─────────────────────────────────────
    val listener = source("lifesteal/ranked/LifestealRankedListener.kt")
    val guard = source("lifesteal/ranked/RankedArenaGuardListener.kt")
    val sources = source("lifesteal/ranked/RankedDamageSources.kt")
    check("player damage goes through RankedArenaAccess.harmBlocked", "RankedArenaAccess.harmBlocked(" in listener)
    check("splash, cloud and combust resolve the thrower through the shared resolver",
        "RankedDamageSources.of(event.potion)" in listener && "RankedDamageSources.of(event.entity)" in listener && "RankedDamageSources.of(event.combuster)" in listener)
    check("the resolver follows projectiles, TNT, clouds, fangs and pets", listOf("is Projectile", "is TNTPrimed", "is AreaEffectCloud", "is EvokerFangs", "is Tameable").all { it in sources })
    check("an offline owner is reported, never dropped as environment", "Resolved(null, true)" in sources)
    check("entry checks include reserved arenas", Regex("""fun onMove[\s\S]{0,120}anyReservation""").containsMatchIn(listener) && Regex("""fun onTeleport[\s\S]{0,120}anyReservation""").containsMatchIn(listener))
    check("riders are stopped at the boundary", Regex("""fun onVehicleMove\(event: VehicleMoveEvent\)""").containsMatchIn(listener))
    check("18: spectators can't drop items", Regex("""fun onDrop[\s\S]{0,160}restricted\(event\.player\)""").containsMatchIn(listener))
    check("17: spectators can't pick items up", Regex("""fun onPickup\(event: EntityPickupItemEvent\)[\s\S]{0,120}restricted\(player\)""").containsMatchIn(listener))
    check("13/14: spectators can't place or break", Regex("""fun onPlace\(event: BlockPlaceEvent\)[\s\S]{0,80}restricted""").containsMatchIn(listener) && Regex("""fun onBreak\(event: BlockBreakEvent\)[\s\S]{0,80}restricted""").containsMatchIn(listener))
    check("15: spectators can't open containers or participant inventories", Regex("""fun onOpen\(event: InventoryOpenEvent\)[\s\S]{0,160}restricted\(player\)""").containsMatchIn(listener))
    check("spectators don't trigger pressure plates", Regex("""Action\.PHYSICAL[\s\S]{0,120}isSpectator""").containsMatchIn(listener))
    check("16: spectators can't hit entities", Regex("""fun onSpectatorHitsEntity[\s\S]{0,300}isSpectator""").containsMatchIn(listener))
    check("outsiders can't use a participant (leads, mounting)", Regex("""fun onInteractEntity[\s\S]{0,500}ranked\.holds\(target\)""").containsMatchIn(listener))
    check("pets are kept off participants", "fun onPetTarget(event: EntityTargetLivingEntityEvent)" in listener)
    check("world changes end stale spectating", "ranked.onWorldChange(event.player)" in listener)
    check("quit clears access state", "ranked.onAccessQuit(event.player.uniqueId)" in listener)
    for (event in listOf("CreatureSpawnEvent", "EntityTeleportEvent", "EntityPortalEvent", "EntityPickupItemEvent")) {
        check("guard handles $event", Regex("""fun \w+\(event: $event\)""").containsMatchIn(guard))
    }
    check("guard refuses outsiders' hits on entities in the arena", "RankedArenaAccess.entityHarmRefused(" in guard)
    check("guard refuses outsiders' pressure plates in the arena", Regex("""Action\.PHYSICAL[\s\S]{0,250}participantOf""").containsMatchIn(guard))
    check("handlers never un-cancel", listOf(listener, guard).none { "isCancelled = false" in it || "Event.Result.ALLOW" in it })

    // ── Untouched systems (28-30) ───────────────────────────
    val access = source("lifesteal/ranked/RankedArenaAccess.kt")
    for (forbidden in listOf("transferPvp", "EloRating", "RatingStore", "serializeAsBytes", "HeartStore")) {
        check("28/29/30: arena access doesn't touch $forbidden", forbidden !in access && forbidden !in sources)
    }
    check("29: ranked death attribution still copies the match at LOWEST in the heart service (unchanged)", "DeathCapture" in source("lifesteal/ranked/RankedMatchRules.kt"))

    println("Ranked arena access checks passed ($checks checks).")
}
