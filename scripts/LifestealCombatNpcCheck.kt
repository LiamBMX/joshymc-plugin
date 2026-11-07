// Checks the Lifesteal Combat NPC (issue #1402): the combat-logging.npc settings, the Bukkit-free
// rules (state machine, restart/join decisions, effect and tag timing, inventory/effect encoding,
// worn-equipment merge), the session store against a real SQLite database (fresh temp directories,
// deleted afterwards), and the Lifesteal-only wiring. The Gradle build has no test source set, so it
// runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-combat-npc-check.init.gradle.kts lifestealCombatNpcCheck
//
// Exits non-zero on the first failed check. Real disconnects, entities, damage, drops, restarts of a
// running server and Bedrock clients are NOT covered here (see docs/lifesteal-combat-npc.md, "Manual
// tests").

import com.liam.joshymc.lifesteal.LifestealConfigParser
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.lifesteal.combat.npc.CombatNpcRules
import com.liam.joshymc.lifesteal.combat.npc.CombatNpcRules.JoinAction
import com.liam.joshymc.lifesteal.combat.npc.CombatNpcRules.Outcome
import com.liam.joshymc.lifesteal.combat.npc.CombatNpcRules.StartupAction
import com.liam.joshymc.lifesteal.combat.npc.CombatNpcRules.State
import com.liam.joshymc.lifesteal.combat.npc.CombatNpcStore
import com.liam.joshymc.manager.DatabaseManager
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
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

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private const val T0 = 1_800_000_000_000L
private val OWNER = UUID.randomUUID()
private val OTHER = UUID.randomUUID()
private val KILLER = UUID.randomUUID()

fun main() {
    settings()
    rules()
    store()
    concurrency()
    wiring()
    println("OK: $checks Lifesteal Combat NPC checks passed.")
}

// ── Settings ─────────────────────────────────────────────────

private fun bundledLifestealYml(): Map<String, Any?> {
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    return Yaml().load(text)
}

private fun parseWith(map: Map<String, Any?>) =
    LifestealConfigParser.parse { p -> p.split('.').fold<String, Any?>(map) { node, key -> (node as? Map<*, *>)?.get(key) } }

@Suppress("UNCHECKED_CAST")
private fun withNpc(edit: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> {
    val map = bundledLifestealYml().toMutableMap()
    val combat = (map["combat-logging"] as Map<String, Any?>).toMutableMap()
    val npc = (combat["npc"] as Map<String, Any?>).toMutableMap()
    edit(npc)
    combat["npc"] = npc
    map["combat-logging"] = combat
    return map
}

private fun settings() {
    val bundled = parseWith(bundledLifestealYml())
    check("bundled lifesteal.yml is valid", bundled.settings != null)
    check("bundled lifesteal.yml has no warnings", bundled.problems.isEmpty())
    val npc = bundled.settings!!.combatLogging.npc
    check("bundled npc section = defaults", npc == LifestealSettings.CombatNpc())
    check("default lifetime is 30 seconds", npc.lifetimeSeconds == 30)
    check("default label is the red [Combat Logger]", npc.label == "&c[Combat Logger]")
    check("username shown by default", npc.showUsername)
    check("all damage allowed by default", npc.allowPlayerDamage && npc.allowMobDamage && npc.allowEnvironmentalDamage)
    check("the leave-npc switch still exists (and is on)", bundled.settings!!.combatLogging.leaveNpc)

    @Suppress("UNCHECKED_CAST")
    val old = bundledLifestealYml().toMutableMap().also {
        val combat = (it["combat-logging"] as Map<String, Any?>).toMutableMap()
        combat.remove("npc")
        it["combat-logging"] = combat
    }
    val oldResult = parseWith(old)
    check("an older file without npc is still valid with the defaults", oldResult.settings?.combatLogging?.npc == LifestealSettings.CombatNpc())
    check("an older file gets exactly one warning", oldResult.problems.size == 1 && !oldResult.problems[0].severe)

    for (bad in listOf(0, 4, 301, -30, "thirty", 1.5)) {
        check("lifetime-seconds $bad is refused", parseWith(withNpc { it["lifetime-seconds"] = bad }).settings == null)
    }
    check("lifetime 5 ok", parseWith(withNpc { it["lifetime-seconds"] = 5 }).settings?.combatLogging?.npc?.lifetimeSeconds == 5)
    check("lifetime 300 ok", parseWith(withNpc { it["lifetime-seconds"] = 300 }).settings?.combatLogging?.npc?.lifetimeSeconds == 300)
    for (key in listOf("show-username", "allow-player-damage", "allow-mob-damage", "allow-environmental-damage")) {
        check("$key must be a boolean", parseWith(withNpc { it[key] = "yes" }).settings == null)
        val missing = parseWith(withNpc { it.remove(key) })
        check("$key missing = warning + default", missing.settings != null && missing.problems.any { !it.severe && key in it.message })
    }
    check("an empty label is refused", parseWith(withNpc { it["label"] = " " }).settings == null)
    check("no health display setting exists (health is never shown)", !File("src/main/resources/lifesteal.yml").readText().contains("show-health"))
}

// ── Rules ────────────────────────────────────────────────────

private fun rules() {
    check("deadline = disconnect + lifetime", CombatNpcRules.expiresAt(T0, 30) == T0 + 30_000)

    // Restart: never extends, never invents damage.
    check("active with time left respawns", CombatNpcRules.startupAction(State.ACTIVE, T0 + 20_000, false, true, T0) == StartupAction.RESPAWN)
    check("active past its deadline survived", CombatNpcRules.startupAction(State.ACTIVE, T0, false, true, T0 + 1) == StartupAction.SURVIVE)
    check("active with under a second left just survived", CombatNpcRules.startupAction(State.ACTIVE, T0 + 999, false, true, T0) == StartupAction.SURVIVE)
    check("active in an unloaded world survived", CombatNpcRules.startupAction(State.ACTIVE, T0 + 20_000, false, false, T0) == StartupAction.SURVIVE)
    check("dead with drops pending drops once", CombatNpcRules.startupAction(State.DEAD, T0, false, true, T0) == StartupAction.DROP)
    check("dead with drops done is kept (committed death stays)", CombatNpcRules.startupAction(State.DEAD, T0, true, true, T0) == StartupAction.KEEP)
    for (s in listOf(State.SURVIVED, State.RECOVERING, State.COMPLETE)) {
        check("$s is kept at startup", CombatNpcRules.startupAction(s, T0 + 20_000, false, true, T0) == StartupAction.KEEP)
    }

    // Join.
    check("no session: nothing", CombatNpcRules.joinAction(null, false) == JoinAction.NONE)
    check("complete: nothing", CombatNpcRules.joinAction(State.COMPLETE, true) == JoinAction.NONE)
    check("standing NPC: take over", CombatNpcRules.joinAction(State.ACTIVE, false) == JoinAction.TAKE_OVER)
    check("survived: restore", CombatNpcRules.joinAction(State.SURVIVED, false) == JoinAction.RESTORE)
    check("interrupted hand-over: restore again (a set, idempotent)", CombatNpcRules.joinAction(State.RECOVERING, false) == JoinAction.RESTORE_AGAIN)
    check("dead: death recovery, never a restore", CombatNpcRules.joinAction(State.DEAD, true) == JoinAction.DEATH)
    check("dead, drops pending: drop first", CombatNpcRules.joinAction(State.DEAD, false) == JoinAction.DROP_THEN_DEATH)

    // Effects use real elapsed time.
    check("effect keeps its remaining ticks at once", CombatNpcRules.remainingTicks(600, T0, T0) == 600)
    check("effect loses 20 ticks per second", CombatNpcRules.remainingTicks(600, T0, T0 + 10_000) == 400)
    check("expired effect doesn't return", CombatNpcRules.remainingTicks(600, T0, T0 + 30_000) == null)
    check("an effect that ran out exactly is gone", CombatNpcRules.remainingTicks(600, T0, T0 + 30_000 - 1) == 1 && CombatNpcRules.remainingTicks(20, T0, T0 + 1_000) == null)
    check("infinite effects stay infinite", CombatNpcRules.remainingTicks(CombatNpcRules.INFINITE_DURATION, T0, T0 + 999_999) == CombatNpcRules.INFINITE_DURATION)
    check("a clock going back never extends", CombatNpcRules.remainingTicks(600, T0, T0 - 50_000) == 600)
    val effects = listOf(
        CombatNpcRules.Effect("minecraft:regeneration", 1, 200, false, true, true),
        CombatNpcRules.Effect("minecraft:fire_resistance", 0, 6_000, false, true, true),
        CombatNpcRules.Effect("minecraft:night_vision", 0, -1, true, false, false),
    )
    val later = CombatNpcRules.effectsAt(effects, T0, T0 + 30_000)
    check("after 30s the short effect is gone, the others reduced/kept",
        later.size == 2 && later[0].durationTicks == 5_400 && later[1].durationTicks == -1)
    check("amplifier and flags are preserved", later[0].amplifier == 0 && later[1].ambient && !later[1].particles && !later[1].icon)
    check("effects round-trip", CombatNpcRules.decodeEffects(CombatNpcRules.encodeEffects(effects)) == effects)
    check("empty effects round-trip", CombatNpcRules.decodeEffects(CombatNpcRules.encodeEffects(emptyList())).isEmpty())
    check("malformed effects are skipped, never invented", CombatNpcRules.decodeEffects("minecraft:speed|x|1|0|0|0;bad;minecraft:haste|1|100|0|1|1").size == 1)

    // Combat tag on reconnect: the original expiry, never a fresh one.
    check("running tag resumes with its original expiry", CombatNpcRules.remainingTag(T0 + 5_000, T0) == T0 + 5_000)
    check("an expired tag stays expired", CombatNpcRules.remainingTag(T0 - 1, T0) == null && CombatNpcRules.remainingTag(null, T0) == null)

    check("health is the NPC's, never a full-health bonus", CombatNpcRules.restoredHealth(7.5, 20.0) == 7.5)
    check("health is capped at the current max", CombatNpcRules.restoredHealth(30.0, 18.0) == 18.0)
    check("XP drop: 7 per level", CombatNpcRules.deathXpDrop(5) == 35)
    check("XP drop: at most 100", CombatNpcRules.deathXpDrop(50) == 100 && CombatNpcRules.deathXpDrop(0) == 0)

    // Inventory encoding.
    val slots = List(CombatNpcRules.INVENTORY_SIZE) { i -> if (i % 3 == 0) "AAEC$i+/=" else null }
    check("inventory round-trips", CombatNpcRules.decodeInventory(CombatNpcRules.encodeInventory(slots)) == slots)
    check("an incomplete inventory is unreadable", CombatNpcRules.decodeInventory("a,b,c") == null && CombatNpcRules.decodeInventory(null) == null)
    check("a wrong slot count can't be stored", runCatching { CombatNpcRules.encodeInventory(listOf("x")) }.isFailure)

    // Worn equipment: durability loss and broken pieces carry over; nothing can be swapped in.
    val before = MutableList<String?>(CombatNpcRules.INVENTORY_SIZE) { null }.also {
        it[0] = "sword"; it[36] = "boots@0"; it[37] = "legs@0"; it[38] = "chest@0"; it[39] = "helm@0"; it[40] = "shield@0"
    }
    val same = { a: String, b: String -> a.substringBefore('@') == b.substringBefore('@') }
    val merged = CombatNpcRules.withWornEquipment(before, mapOf(36 to "boots@40", 37 to null, 38 to "diamond@0", 39 to "helm@0", 0 to "stolen", 40 to "shield@3"), same)
    check("worn-down boots replace the snapshot's", merged[36] == "boots@40")
    check("broken leggings are gone", merged[37] == null)
    check("a different chest item can't be swapped in", merged[38] == "chest@0")
    check("storage slots are never changed by the NPC", merged[0] == "sword")
    check("off hand durability carries over", merged[40] == "shield@3")
    val emptyBefore = MutableList<String?>(CombatNpcRules.INVENTORY_SIZE) { null }
    check("nothing can appear in an empty armor slot", CombatNpcRules.withWornEquipment(emptyBefore, mapOf(39 to "helm@0"), same)[39] == null)
}

// ── Store ────────────────────────────────────────────────────

private fun snapshot(health: Double = 13.0, world: String = "lifesteal", effectsAt: Long = T0) = CombatNpcStore.Snapshot(
    location = CombatNpcStore.Point(world, 10.5, 64.0, -3.25, 90f, 10f),
    health = health,
    maxHealthBase = 24.0,
    absorption = 4.0,
    fireTicks = 0,
    air = 300,
    inventory = List(CombatNpcRules.INVENTORY_SIZE) { i -> if (i == 0 || i == 39) "item$i" else null },
    heldSlot = 0,
    xpLevel = 12,
    xpProgress = 0.5f,
    xpTotal = 300,
    effects = listOf(CombatNpcRules.Effect("minecraft:regeneration", 1, 400, false, true, true)),
    effectsAt = effectsAt,
)

private fun withDb(name: String, block: (File, DatabaseManager) -> Unit) {
    val dir = Files.createTempDirectory("joshymc-combatnpc-$name").toFile()
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        block(dir, db)
    } finally {
        db.shutdown()
        dir.deleteRecursively()
    }
}

private fun insert(store: CombatNpcStore, id: String, owner: UUID = OWNER, s: CombatNpcStore.Snapshot = snapshot()) =
    store.insertActive(id, owner, "Owner", T0, T0 + 30_000, T0 + 15_000, "tex", "sig", s)

private fun store() {
    withDb("store") { dir, db ->
        val store = CombatNpcStore(db)
        store.createTables()
        store.createTables() // Idempotent.

        check("first session inserts", insert(store, "a"))
        check("a second open session for the same player is refused", !insert(store, "b"))
        check("the same id can't be inserted twice", !insert(store, "a", OTHER))
        check("another player can have their own", insert(store, "c", OTHER))
        val a = store.open(OWNER)!!
        check("stored as ACTIVE", a.state == State.ACTIVE && a.outcome == null && !a.dropsDone)
        check("deadline, tag and skin stored", a.expiresAt == T0 + 30_000 && a.tagExpiresAt == T0 + 15_000 && a.textures == "tex" && a.texturesSignature == "sig")
        check("snapshot round-trips", a.snapshot == snapshot())

        // Live saves only while ACTIVE.
        check("live save while active", store.saveLive("a", snapshot(health = 9.0), T0 + 5_000))
        check("live save stored", store.open(OWNER)!!.snapshot.health == 9.0)

        // Death: once.
        check("death commits", store.die("a", snapshot(health = 0.0), KILLER, "Killer", "minecraft:player_attack", "Owner was slain by Killer", T0 + 6_000))
        check("a second death event finds it dead", !store.die("a", snapshot(health = 0.0), KILLER, "Killer", null, null, T0 + 6_001))
        check("a reconnect can't cancel a committed death", !store.endAlive("a", snapshot(), Outcome.RECONNECTED, State.RECOVERING, T0 + 6_002))
        check("an expiry can't cancel a committed death", !store.endAlive("a", snapshot(), Outcome.SURVIVED, State.SURVIVED, T0 + 6_003))
        check("no live save after death", !store.saveLive("a", snapshot(health = 20.0), T0 + 6_004))
        val dead = store.open(OWNER)!!
        check("death stored with its killer and message", dead.state == State.DEAD && dead.outcome == Outcome.DEAD && dead.killer == KILLER &&
            dead.killerName == "Killer" && dead.deathMessage == "Owner was slain by Killer" && dead.deathCause == "minecraft:player_attack")
        check("a dead session can't complete before its drops", !store.complete("a", T0 + 7_000))
        check("drops confirmed once", store.markDropsDone("a", T0 + 6_100) && !store.markDropsDone("a", T0 + 6_200))
        check("a dead session can't be restored", !store.beginRestore("a", T0 + 7_000))
        check("dead + dropped completes", store.complete("a", T0 + 8_000))
        check("completed: no open session", store.open(OWNER) == null)
        check("completed twice is a no-op", !store.complete("a", T0 + 8_001))
        check("after completion a new session can start", insert(store, "d"))

        // Survival -> restore -> complete.
        check("expiry: ACTIVE -> SURVIVED", store.endAlive("d", snapshot(health = 6.0), Outcome.SURVIVED, State.SURVIVED, T0 + 30_000))
        check("expired twice is a no-op", !store.endAlive("d", snapshot(), Outcome.SURVIVED, State.SURVIVED, T0 + 30_001))
        check("a survived NPC can't die afterwards", !store.die("d", snapshot(), KILLER, "Killer", null, null, T0 + 30_002))
        val survived = store.open(OWNER)!!
        check("survived state kept (damaged health, not restored to full)", survived.state == State.SURVIVED && survived.snapshot.health == 6.0 && survived.endedAt == T0 + 30_000)
        check("a survived session can't complete without a hand-over", !store.complete("d", T0 + 40_000))
        check("hand-over begins once", store.beginRestore("d", T0 + 40_000) && !store.beginRestore("d", T0 + 40_001))
        check("recovering completes", store.complete("d", T0 + 40_002))

        // Reconnect while standing.
        check("new session", insert(store, "e"))
        check("reconnect: ACTIVE -> RECOVERING", store.endAlive("e", snapshot(health = 3.0), Outcome.RECONNECTED, State.RECOVERING, T0 + 10_000))
        check("a death after the reconnect is refused", !store.die("e", snapshot(), KILLER, "Killer", null, null, T0 + 10_001))
        check("reconnect stores RECONNECTED", store.open(OWNER)!!.outcome == Outcome.RECONNECTED)

        // Restart: a fresh connection on the same folder still has everything.
        db.shutdown()
        val db2 = DatabaseManager(dir, quietLogger())
        db2.start()
        try {
            val store2 = CombatNpcStore(db2)
            store2.createTables()
            check("an interrupted hand-over survives a restart", store2.open(OWNER)?.state == State.RECOVERING)
            check("the other player's standing session survives a restart", store2.open(OTHER)?.state == State.ACTIVE)
            check("open sessions are listed for recovery", store2.allOpen().map { it.id }.toSet() == setOf("e", "c"))
            check("the one-open-session rule survives a restart", !insert(store2, "f"))
            check("retention never deletes open sessions", store2.purgeCompletedBefore(Long.MAX_VALUE) == 2 && store2.allOpen().size == 2)

            // An unreadable row is never mistaken for "no session".
            db2.execute("UPDATE lifesteal_combat_npcs SET inventory = 'broken' WHERE id = 'c'")
            check("an unreadable open session throws instead of reading as none", runCatching { store2.open(OTHER) }.isFailure)
            check("it is reported", store2.unreadableOpen() == listOf("c"))
            check("and skipped by the recovery list", store2.allOpen().map { it.id } == listOf("e"))
        } finally {
            db2.shutdown()
        }
    }

    // A closed database throws (the manager keeps the player's items and logs it).
    val dir = Files.createTempDirectory("joshymc-combatnpc-closed").toFile()
    try {
        val closed = DatabaseManager(dir, quietLogger()).also { it.start(); it.shutdown() }
        check("a closed database refuses the insert", runCatching { insert(CombatNpcStore(closed), "z") }.isFailure)
    } finally {
        dir.deleteRecursively()
    }
}

/** Racing transitions on one session: exactly one wins. */
private fun concurrency() {
    withDb("race") { _, db ->
        val store = CombatNpcStore(db)
        store.createTables()
        repeat(20) { round ->
            val id = "race$round"
            check("race session $round inserts", store.insertActive(id, UUID.randomUUID(), "P", T0, T0 + 30_000, null, null, null, snapshot()))
            val wins = AtomicInteger()
            val deaths = AtomicInteger()
            val pool = Executors.newFixedThreadPool(6)
            val start = CountDownLatch(1)
            repeat(6) { t ->
                pool.execute {
                    start.await()
                    val won = when (t % 3) {
                        0 -> store.die(id, snapshot(), KILLER, "K", null, null, T0 + 1).also { if (it) deaths.incrementAndGet() }
                        1 -> store.endAlive(id, snapshot(), Outcome.RECONNECTED, State.RECOVERING, T0 + 1)
                        else -> store.endAlive(id, snapshot(), Outcome.SURVIVED, State.SURVIVED, T0 + 1)
                    }
                    if (won) wins.incrementAndGet()
                }
            }
            start.countDown()
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
            check("round $round: exactly one transition won", wins.get() == 1)
            check("round $round: at most one death", deaths.get() <= 1)
        }
        val dupOwner = UUID.randomUUID()
        val inserted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(8) { t ->
            pool.execute {
                start.await()
                if (store.insertActive("dup$t", dupOwner, "P", T0, T0 + 30_000, null, null, null, snapshot())) inserted.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        check("concurrent quits create exactly one session", inserted.get() == 1)
    }
}

// ── Wiring ───────────────────────────────────────────────────

private fun wiring() {
    val manager = source("lifesteal/combat/npc/LifestealCombatNpcManager.kt")
    val listener = source("lifesteal/combat/npc/LifestealCombatNpcListener.kt")
    val combatListener = source("listener/CombatListener.kt")
    val combatLog = source("lifesteal/combat/LifestealCombatManager.kt")
    val listeners = source("manager/ListenerManager.kt")
    val main = source("Joshymc.kt")
    val hearts = source("lifesteal/hearts/LifestealHeartManager.kt")
    val scoreboard = source("manager/ScoreboardManager.kt")
    val streaks = source("manager/KillStreakManager.kt")
    val combatManager = source("manager/CombatManager.kt")

    // Survival stays on the old NPC; Lifesteal asks first.
    check("quit asks the Lifesteal NPC before the old villager",
        Regex("""if \(!plugin\.lifestealCombatNpcManager\.takeOver\(player\)\) \{\s*\n\s*plugin\.combatManager\.spawnCombatLogNPC\(player\)""").containsMatchIn(combatListener))
    check("the combat-log decision (shouldSpawnCombatLogNpc) still comes first", combatListener.indexOf("shouldSpawnCombatLogNpc(player, event.reason)") < combatListener.indexOf("takeOver(player)"))
    check("takeOver refuses outside a hosted Lifesteal server", manager.contains("if (!hosted || !tablesReady || stopping) return false"))
    check("takeOver refuses outside a Lifesteal world", manager.contains("if (!plugin.lifestealCombatManager.isLifesteal(player)) return false"))
    check("leave-npc false: no NPC at all", manager.contains("if (!combat.leaveNpc) return true"))
    check("listener only where Lifesteal is hosted",
        Regex("""if \(plugin\.lifestealCombatNpcManager\.hosted\) \{\s*\n\s*pm\.registerEvents\(com\.liam\.joshymc\.lifesteal\.combat\.npc\.LifestealCombatNpcListener""").containsMatchIn(listeners))
    check("tables only after the hosted check", manager.indexOf("if (!hosted) return") < manager.indexOf("store.createTables()"))
    check("the #1350 combat-log manager still never touches inventories or hearts", !combatLog.contains("inventory") && !combatLog.contains("lifestealHeartManager"))

    // Inventory integrity.
    check("the player's items are only cleared after the row is committed", manager.indexOf("store.insertActive(") < manager.indexOf("clearOwnedState(player)\n        val live"))
    check("a failed insert keeps the player's items", Regex("""Couldn't save \$\{player\.name\}'s Combat NPC session.*they keep their items""").containsMatchIn(manager))
    check("armor and off hand are cleared too (contents = all 41 slots)", manager.contains("player.inventory.contents = arrayOfNulls(player.inventory.contents.size)"))
    check("the NPC entity is never saved with the chunk", manager.contains("m.isPersistent = false"))
    check("the NPC can't pick up items", manager.contains("m.canPickupItems = false") && listener.contains("EntityPickupItemEvent"))
    check("the NPC's own equipment never drops", manager.contains("event.drops.clear()") && manager.contains("event.droppedExp = 0") && manager.contains("setHelmetDropChance(0f)"))
    check("death is committed before anything drops", manager.indexOf("store.die(") < manager.indexOf("dropItems(final"))
    check("a death that can't be recorded keeps the NPC alive", manager.contains("event.isCancelled = true") && manager.contains("event.reviveHealth = 1.0"))
    check("restores set the inventory, never add", manager.contains("inventory.contents = items.toTypedArray()"))
    check("join settles the session at LOWEST", Regex("""LOWEST\)\s*\n\s*fun onJoin""").containsMatchIn(listener))
    check("an unreadable session disconnects instead of an empty inventory", manager.contains("kickLater(player)"))
    check("interactions with the NPC are cancelled", listener.contains("PlayerInteractEntityEvent") && listener.contains("PlayerInteractAtEntityEvent"))
    check("portals can't move the NPC", listener.contains("EntityPortalEvent") && listener.contains("EntityTeleportEvent"))
    check("ghost NPC entities are removed on load", listener.contains("EntitiesLoadEvent") && manager.contains("fun removeIfGhost"))

    // Death outcomes reuse the existing systems.
    check("hearts: the existing store with the session as dedupe key", hearts.contains("dedupeKey = \"combatnpc:\$sessionId\"") && hearts.contains("store.transferPvp(request)"))
    check("hearts: the same team and progression checks", Regex("""fun onCombatNpcDeath[\s\S]*heartCheck\(killer\.uniqueId, victim\)[\s\S]*heartWindow\(now\)""").containsMatchIn(hearts))
    check("hearts: only the final damage's causing player counts", manager.contains("event.damageSource.causingEntity as? Player"))
    check("kill stats through the existing scoreboard counter", scoreboard.contains("fun recordDeath(victim: UUID, killer: Player?)") && manager.contains("plugin.scoreboardManager.recordDeath(live.owner, killer)"))
    check("PlayerDeathEvent still records through the same counter", scoreboard.contains("recordDeath(victim.uniqueId, victim.killer)"))
    check("kill streaks through the existing manager", streaks.contains("fun recordDeath(victimUuid: UUID, victimName: String, killer: Player?)") && streaks.contains("recordDeath(victim.uniqueId, victim.name, victim.killer)"))
    check("the death message is vanilla's (combat tracker)", manager.contains("entity.combatTracker.deathMessage"))
    check("a bounty hook exists, fired once after the death", manager.contains("callEvent(LifestealCombatNpcDeathEvent("))

    // Safe zones: the exception is only for this NPC's damage.
    check("allowed hits are un-cancelled at HIGHEST", Regex("""HIGHEST\)\s*\n\s*fun onPlayerHit""").containsMatchIn(listener) && listener.contains("HitDecision.ALLOW -> event.isCancelled = false"))
    check("only standing NPCs get the exception", listener.contains("HitDecision.NOT_NPC -> return") && manager.contains("val id = sessionOf(entity) ?: return HitDecision.NOT_NPC"))
    check("Lifesteal friendly fire applies to the owner's NPC", manager.contains("TeamCombatRules.DamageDecision.BLOCK_FRIENDLY_FIRE"))

    // Tag and lifecycle.
    check("a reconnect resumes the original tag, never a fresh one", combatManager.contains("fun resumeTag(player: Player, expiresAt: Long)") && manager.contains("plugin.combatManager.resumeTag(player, it)"))
    check("shutdown saves standing NPCs before the database closes",
        main.indexOf("lifestealCombatNpcManager.stop()") in 0 until main.indexOf("databaseManager.shutdown()"))
    check("refresh runs at startup and on reload", Regex("""lifestealCombatNpcManager\.refresh\(\)""").findAll(main).count() == 2)
    check("one repeating task, started once", manager.contains("if (tickTask == -1)"))
}
