// Checks Lifesteal combat logging (issue #1350): the combat-logging settings, the Bukkit-free combat
// sessions, disconnect classification and enforcement rules, the incident store against a real
// SQLite database (fresh temp directories, deleted afterwards), and the Lifesteal-only wiring. The
// Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-combat-log-check.init.gradle.kts lifestealCombatLogCheck
//
// Exits non-zero on the first failed check. Real disconnects, kicks, crashes, Velocity transfers and
// Bedrock clients are NOT covered here (see docs/lifesteal-combat.md, "Manual tests").

import com.liam.joshymc.lifesteal.CombatLogHeartPenalty
import com.liam.joshymc.lifesteal.LifestealConfigParser
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.lifesteal.combat.CombatLogRules
import com.liam.joshymc.lifesteal.combat.CombatLogRules.DisconnectKind
import com.liam.joshymc.lifesteal.combat.CombatLogRules.PenaltyStatus
import com.liam.joshymc.lifesteal.combat.CombatLogStore
import com.liam.joshymc.lifesteal.combat.CombatSessions
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
private fun resourceText(name: String): String = File("src/main/resources/$name").readText()

private const val T0 = 1_800_000_000_000L
private const val TAG = 15_000L

/** A Java (v4) and a Floodgate-style Bedrock UUID: both are plain UUIDs here. */
private val JAVA = UUID.randomUUID()
private val BEDROCK = UUID.fromString("00000000-0000-0000-0009-01f2a3b4c5d6")
private val ENEMY = UUID.randomUUID()
private val OTHER = UUID.randomUUID()

private val DEFAULTS = LifestealSettings.CombatLogging()

fun main() {
    settings()
    sessions()
    classification()
    enforcement()
    store()
    wiring()
    println("OK: $checks Lifesteal combat-log checks passed.")
}

// ── Settings ─────────────────────────────────────────────────

private fun bundledLifestealYml(): Map<String, Any?> {
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    return Yaml().load(text)
}

private fun parseWith(map: Map<String, Any?>) =
    LifestealConfigParser.parse { p -> p.split('.').fold<String, Any?>(map) { node, key -> (node as? Map<*, *>)?.get(key) } }

@Suppress("UNCHECKED_CAST")
private fun withCombat(edit: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> {
    val map = bundledLifestealYml().toMutableMap()
    val section = (map["combat-logging"] as Map<String, Any?>).toMutableMap()
    edit(section)
    map["combat-logging"] = section
    return map
}

private fun settings() {
    val bundled = parseWith(bundledLifestealYml())
    check("bundled lifesteal.yml is valid", bundled.settings != null)
    check("bundled lifesteal.yml has no warnings", bundled.problems.isEmpty())
    val c = bundled.settings!!.combatLogging
    check("bundled combat-logging = defaults", c == DEFAULTS)
    check("default: enabled", c.enabled)
    check("default: heart penalty none", c.heartPenalty == CombatLogHeartPenalty.NONE)
    check("default: existing NPC kept", c.leaveNpc)
    check("default: issue's tagged message", c.messages.tagged == "&cYou are now in combat! Do not disconnect.")
    check("default: issue's expired message", c.messages.expired == "&aYou are no longer in combat.")
    check("only one heart penalty exists (none)", CombatLogHeartPenalty.entries.toList() == listOf(CombatLogHeartPenalty.NONE))

    val old = bundledLifestealYml().toMutableMap().also { it.remove("combat-logging") }
    val oldResult = parseWith(old)
    check("an older file without combat-logging is still valid", oldResult.settings?.combatLogging == DEFAULTS)
    check("an older file gets exactly one warning", oldResult.problems.size == 1 && !oldResult.problems[0].severe)

    for (bad in listOf("hearts", "1", "lose-heart", "", "kill")) {
        val r = parseWith(withCombat { it["heart-penalty"] = bad })
        check("heart-penalty '$bad' is refused (file invalid)", r.settings == null && r.problems.any { it.severe && "heart-penalty" in it.message })
    }
    check("heart-penalty NONE (any case) is accepted", parseWith(withCombat { it["heart-penalty"] = "NONE" }).settings != null)
    for (bad in listOf(0, -1, 3651, "ten", 1.5)) {
        check("retention-days $bad is refused", parseWith(withCombat { it["retention-days"] = bad }).settings == null)
    }
    check("retention-days 1 ok", parseWith(withCombat { it["retention-days"] = 1 }).settings?.combatLogging?.retentionDays == 1)
    check("retention-days 3650 ok", parseWith(withCombat { it["retention-days"] = 3650 }).settings?.combatLogging?.retentionDays == 3650)
    for (key in listOf("enabled", "leave-npc", "count-timeouts", "notifications", "diagnostic-logging")) {
        check("$key must be a boolean", parseWith(withCombat { it[key] = "yes" }).settings == null)
        val missing = parseWith(withCombat { it.remove(key) })
        check("$key missing = warning + default", missing.settings != null && missing.problems.any { !it.severe && key in it.message })
    }
    @Suppress("UNCHECKED_CAST")
    val emptyMessage = parseWith(withCombat { it["messages"] = mapOf("tagged" to " ", "expired" to "&aok") })
    check("an empty message is refused", emptyMessage.settings == null)
    check("disabled section parses", parseWith(withCombat { it["enabled"] = false }).settings?.combatLogging?.enabled == false)
    check("combat-tag duration is not a lifesteal.yml key (config.yml combat.tag-duration-seconds stays the one value)",
        !Regex("""(?m)^\s*tag-duration""").containsMatchIn(resourceText("lifesteal.yml")))
}

// ── Combat sessions (tag activation, refresh, expiry, opponents) ─────────────

private fun sessions() {
    val s = CombatSessions()
    val first = s.hit(JAVA, ENEMY, T0, T0 + TAG)
    check("first hit starts both sessions (one 'now in combat' line each)", first == setOf(JAVA, ENEMY))
    val id = s.get(JAVA)!!.id
    check("session records start time", s.get(JAVA)!!.startedAt == T0)
    check("session records the opponent", s.get(JAVA)!!.opponents.keys.toList() == listOf(ENEMY))
    check("victim records the attacker", s.get(ENEMY)!!.opponents.keys.toList() == listOf(JAVA))

    val refresh = s.hit(ENEMY, JAVA, T0 + 5_000, T0 + 5_000 + TAG)
    check("a refresh hit starts nothing (no chat spam)", refresh.isEmpty())
    check("refresh keeps the session id", s.get(JAVA)!!.id == id)
    check("refresh keeps the start time", s.get(JAVA)!!.startedAt == T0)
    check("refresh moves the expiry", s.get(JAVA)!!.expiresAt == T0 + 5_000 + TAG)
    check("refresh moves the last hit", s.get(JAVA)!!.lastHitAt == T0 + 5_000)
    check("a later expiry never moves back", s.hit(JAVA, ENEMY, T0 + 6_000, T0 + 1_000).isEmpty() && s.get(JAVA)!!.expiresAt == T0 + 5_000 + TAG)

    s.hit(JAVA, BEDROCK, T0 + 7_000, T0 + 7_000 + TAG)
    check("a second opponent (Bedrock UUID) is added", s.get(JAVA)!!.opponents.keys.toList() == listOf(ENEMY, BEDROCK))
    s.hit(ENEMY, JAVA, T0 + 8_000, T0 + 8_000 + TAG)
    check("most recent opponent is last", s.get(JAVA)!!.opponents.keys.last() == ENEMY)

    check("self-damage starts nothing", s.hit(OTHER, OTHER, T0, T0 + TAG).isEmpty() && s.get(OTHER) == null)

    val many = CombatSessions()
    repeat(50) { many.hit(JAVA, UUID.randomUUID(), T0 + it, T0 + it + TAG) }
    check("opponents are bounded", many.get(JAVA)!!.opponents.size == CombatSessions.MAX_OPPONENTS)

    val ended = s.end(JAVA)
    check("ending returns a snapshot", ended != null && ended.id == id && ended.opponents.contains(BEDROCK))
    check("an ended session is gone (duplicate quit / death then quit sees nothing)", s.end(JAVA) == null && s.get(JAVA) == null)
    check("the other player's session is untouched", s.get(ENEMY) != null)

    val again = s.hit(JAVA, ENEMY, T0 + 9_000, T0 + 9_000 + TAG)
    check("after the session ended, a new hit starts a new session for that player only", again == setOf(JAVA))
    check("the new session has a new id", s.get(JAVA)!!.id != id)

    val expired = CombatSessions()
    expired.hit(JAVA, ENEMY, T0, T0 + TAG)
    val oldId = expired.get(JAVA)!!.id
    check("a hit after expiry starts a new session", expired.hit(JAVA, ENEMY, T0 + TAG + 1, T0 + 2 * TAG + 1) == setOf(JAVA, ENEMY))
    check("expired session replaced", expired.get(JAVA)!!.id != oldId && expired.get(JAVA)!!.startedAt == T0 + TAG + 1)

    val sweep = CombatSessions()
    sweep.hit(JAVA, ENEMY, T0, T0 + TAG)
    check("sweep keeps live sessions", sweep.sweep(T0 + TAG, 5_000) == 0 && sweep.size == 2)
    check("sweep drops stale sessions (missed callback can't leak)", sweep.sweep(T0 + TAG + 5_001, 5_000) == 2 && sweep.size == 0)

    val capped = CombatSessions()
    repeat(CombatSessions.MAX_SESSIONS + 100) { capped.hit(UUID.randomUUID(), UUID.randomUUID(), T0 + it, T0 + it + TAG) }
    check("sessions are bounded", capped.size <= CombatSessions.MAX_SESSIONS)

    val cleared = CombatSessions()
    cleared.hit(JAVA, ENEMY, T0, T0 + TAG)
    cleared.clear()
    check("clear (reload/shutdown) empties everything", cleared.size == 0)
}

// ── Disconnect classification ─────────────────────────────────

private fun classification() {
    fun c(reason: String?, kick: String? = null, stopping: Boolean = false, transfer: Boolean = false) =
        CombatLogRules.classify(reason, kick, stopping, transfer)
    check("normal disconnect / client crash = QUIT", c("DISCONNECTED") == DisconnectKind.QUIT)
    check("unknown quit reason = QUIT", c(null) == DisconnectKind.QUIT)
    check("timeout = TIMED_OUT", c("TIMED_OUT") == DisconnectKind.TIMED_OUT)
    check("server error = SERVER_ERROR", c("ERRONEOUS_STATE") == DisconnectKind.SERVER_ERROR)
    check("/kick = STAFF_KICK", c("KICKED", "KICK_COMMAND") == DisconnectKind.STAFF_KICK)
    check("ban = STAFF_KICK", c("KICKED", "BANNED") == DisconnectKind.STAFF_KICK)
    check("ip ban = STAFF_KICK", c("KICKED", "IP_BANNED") == DisconnectKind.STAFF_KICK)
    check("whitelist = STAFF_KICK", c("KICKED", "WHITELIST") == DisconnectKind.STAFF_KICK)
    check("plugin kick (punishments) = STAFF_KICK", c("KICKED", "PLUGIN") == DisconnectKind.STAFF_KICK)
    for (cause in listOf("FLYING_PLAYER", "SPAM", "INVALID_PAYLOAD", "IDLING", "DUPLICATE_LOGIN", "RESOURCE_PACK_REJECTION", "TIMEOUT", "UNKNOWN", "ILLEGAL_ACTION")) {
        check("player-provokable kick $cause = PLAYER_KICK (can't be used to spoof a staff kick)", c("KICKED", cause) == DisconnectKind.PLAYER_KICK)
    }
    check("KICKED with no kick event = PLAYER_KICK (unverified)", c("KICKED") == DisconnectKind.PLAYER_KICK)
    // Issue #1369: the anticheat's kicks are provoked by the player, so they must not look like staff kicks.
    check("anticheat kicks use ILLEGAL_ACTION (a tagged player can't escape by tripping a check)",
        "PlayerKickEvent.Cause.ILLEGAL_ACTION" in source("manager/AntiCheatManager.kt"))
    check("ILLEGAL_ACTION is never a staff cause", "ILLEGAL_ACTION" !in CombatLogRules.STAFF_KICK_CAUSES)
    check("restart command = SERVER_SHUTDOWN", c("KICKED", "RESTART_COMMAND") == DisconnectKind.SERVER_SHUTDOWN)
    for (reason in listOf("DISCONNECTED", "TIMED_OUT", "KICKED", "ERRONEOUS_STATE")) {
        check("server stopping wins over $reason", c(reason, "FLYING_PLAYER", stopping = true) == DisconnectKind.SERVER_SHUTDOWN)
    }
    check("pending transfer = TRANSFER", c("DISCONNECTED", transfer = true) == DisconnectKind.TRANSFER)
    check("shutdown wins over a pending transfer", c("DISCONNECTED", stopping = true, transfer = true) == DisconnectKind.SERVER_SHUTDOWN)
    check("a pending transfer wins over a staff kick", c("KICKED", "KICK_COMMAND", transfer = true) == DisconnectKind.TRANSFER)
}

// ── Enforcement ─────────────────────────────────────────────

private fun enforcement() {
    for (kind in DisconnectKind.entries) {
        val e = CombatLogRules.enforcement(kind, DEFAULTS)
        check("$kind removes no heart (no penalty approved)", e.heartsToRemove == 0)
    }
    for (kind in listOf(DisconnectKind.QUIT, DisconnectKind.TIMED_OUT, DisconnectKind.PLAYER_KICK, DisconnectKind.TRANSFER)) {
        val e = CombatLogRules.enforcement(kind, DEFAULTS)
        check("$kind counts and keeps the existing NPC", e.counted && e.spawnNpc && e.status == PenaltyStatus.COUNTED_NO_HEART_PENALTY)
    }
    for (kind in listOf(DisconnectKind.STAFF_KICK, DisconnectKind.SERVER_SHUTDOWN, DisconnectKind.SERVER_ERROR)) {
        val e = CombatLogRules.enforcement(kind, DEFAULTS)
        check("$kind is exempt: not counted, no NPC (no death, no item drop)", !e.counted && !e.spawnNpc && e.status == PenaltyStatus.EXEMPT_SERVER)
    }
    val noTimeouts = DEFAULTS.copy(countTimeouts = false)
    val t = CombatLogRules.enforcement(DisconnectKind.TIMED_OUT, noTimeouts)
    check("count-timeouts false: a timeout (e.g. Bedrock network drop) is recorded, not counted, no NPC", !t.counted && !t.spawnNpc && t.status == PenaltyStatus.NOT_COUNTED_TIMEOUT)
    check("count-timeouts false: a normal quit still counts", CombatLogRules.enforcement(DisconnectKind.QUIT, noTimeouts).counted)
    val noNpc = CombatLogRules.enforcement(DisconnectKind.QUIT, DEFAULTS.copy(leaveNpc = false))
    check("leave-npc false: counted, but no NPC", noNpc.counted && !noNpc.spawnNpc)

    check("final heart is never removed (1 stays 1)", CombatLogRules.heartsAfterPenalty(1, 1, 1) == 1)
    check("final heart is never removed (big penalty)", CombatLogRules.heartsAfterPenalty(1, 50, 1) == 1)
    check("penalty stops at the minimum", CombatLogRules.heartsAfterPenalty(3, 5, 1) == 1)
    check("penalty stops at a higher minimum", CombatLogRules.heartsAfterPenalty(5, 5, 2) == 2)
    check("negative penalty never adds hearts", CombatLogRules.heartsAfterPenalty(10, -3, 1) == 10)
    check("below-minimum (malformed) count is left alone", CombatLogRules.heartsAfterPenalty(0, 1, 1) == 0)
    check("ordinary penalty arithmetic", CombatLogRules.heartsAfterPenalty(10, 1, 1) == 9)

    val k1 = CombatLogRules.dedupeKey("boot1", JAVA, 7)
    check("dedupe key is stable", k1 == CombatLogRules.dedupeKey("boot1", JAVA, 7))
    check("dedupe key differs per boot (restart)", k1 != CombatLogRules.dedupeKey("boot2", JAVA, 7))
    check("dedupe key differs per session", k1 != CombatLogRules.dedupeKey("boot1", JAVA, 8))
    check("dedupe key differs per player", k1 != CombatLogRules.dedupeKey("boot1", BEDROCK, 7))
}

// ── Store (real SQLite) ─────────────────────────────────────

private fun incident(player: UUID, key: String, at: Long, kind: DisconnectKind = DisconnectKind.QUIT, opponents: List<UUID> = listOf(ENEMY)): CombatLogStore.Incident {
    val e = CombatLogRules.enforcement(kind, DEFAULTS)
    return CombatLogStore.Incident(
        id = UUID.randomUUID().toString(), dedupeKey = key, player = player, opponents = opponents, serverId = "lifesteal",
        combatStartedAt = at - 10_000, lastHitAt = at - 1_000, tagExpiresAt = at + 14_000, disconnectedAt = at, kind = kind,
        quitReason = "DISCONNECTED", kickCause = null, counted = e.counted, status = e.status, npcSpawned = e.spawnNpc, heartTransactionId = null,
    )
}

private fun withDb(name: String, block: (File, DatabaseManager) -> Unit) {
    val dir = Files.createTempDirectory("joshymc-combatlog-$name").toFile()
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        block(dir, db)
    } finally {
        db.shutdown()
        dir.deleteRecursively()
    }
}

private fun store() {
    withDb("basic") { dir, db ->
        val store = CombatLogStore(db)
        store.createTables()
        store.createTables()
        check("createTables is idempotent", true)

        val a = incident(JAVA, "k1", T0, opponents = listOf(ENEMY, BEDROCK))
        check("first incident is recorded", store.record(a))
        check("duplicate disconnect event (same key) is ignored", !store.record(a.copy(id = UUID.randomUUID().toString())))
        val read = store.recent(10, JAVA)
        check("exactly one incident after duplicates", read.size == 1)
        val r = read.first()
        check("incident round-trips", r == a)
        check("opponents (Java + Bedrock UUIDs) round-trip", r.opponents == listOf(ENEMY, BEDROCK))
        check("server id stored", r.serverId == "lifesteal")
        check("no heart transaction", r.heartTransactionId == null)

        check("Bedrock player incident", store.record(incident(BEDROCK, "k2", T0 + 1, DisconnectKind.TIMED_OUT)))
        check("staff kick recorded as not counted", store.record(incident(JAVA, "k3", T0 + 2, DisconnectKind.STAFF_KICK)))
        check("shutdown recorded as not counted", store.record(incident(JAVA, "k4", T0 + 3, DisconnectKind.SERVER_SHUTDOWN)))
        check("recent is newest first", store.recent(10).map { it.dedupeKey } == listOf("k4", "k3", "k2", "k1"))
        check("per player filter", store.recent(10, BEDROCK).map { it.player } == listOf(BEDROCK))
        check("counted only counts counted incidents", store.countFor(JAVA, 0) == 1)
        check("counted since cutoff", store.countFor(JAVA, T0 + 1) == 0)
        check("recent limit", store.recent(2).size == 2)

        check("short id lookup", store.byIdPrefix(a.id.substring(0, 8)).map { it.id } == listOf(a.id))
        check("too-short id refused", store.byIdPrefix("ab").isEmpty())
        check("sql-ish id input is cleaned", store.byIdPrefix("%' OR 1=1 --").isEmpty())

        // Restart: a new connection on the same folder still has everything, and the same key is still refused.
        db.shutdown()
        val db2 = DatabaseManager(dir, quietLogger())
        db2.start()
        try {
            val store2 = CombatLogStore(db2)
            store2.createTables()
            check("incidents survive a restart", store2.recent(10).size == 4)
            check("dedupe survives a restart", !store2.record(a.copy(id = UUID.randomUUID().toString())))
            check("retention purge deletes old rows", store2.purgeBefore(T0 + 2) == 2 && store2.recent(10).size == 2)

            db2.execute("INSERT INTO lifesteal_combat_logs (id, dedupe_key, player, opponents, server_id, combat_started_at, last_hit_at, tag_expires_at, disconnected_at, disconnect_kind, counted, penalty_status, npc_spawned) VALUES ('bad-row', 'bad', 'not-a-uuid', '', 'x', 0, 0, 0, ${T0 + 10}, 'NOPE', 1, 'NOPE', 0)")
            check("a malformed row is skipped, never crashes the listing", store2.recent(10).size == 2)
        } finally {
            db2.shutdown()
        }
    }

    withDb("concurrent") { _, db ->
        val store = CombatLogStore(db)
        store.createTables()
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val inserted = AtomicInteger()
        repeat(32) {
            pool.submit {
                go.await()
                if (store.record(incident(JAVA, "same-key", T0))) inserted.incrementAndGet()
            }
        }
        go.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        check("32 racing writes of one incident store exactly one", inserted.get() == 1 && store.recent(10).size == 1)
    }

    // Database failure: a closed database throws (the manager records the failure and logs it).
    val dir = Files.createTempDirectory("joshymc-combatlog-closed").toFile()
    try {
        val closed = DatabaseManager(dir, quietLogger()).also { it.start(); it.shutdown() }
        val failed = try {
            CombatLogStore(closed).record(incident(JAVA, "x", T0)); false
        } catch (_: Exception) {
            true
        }
        check("a closed database fails loudly instead of pretending to save", failed)
    } finally {
        dir.deleteRecursively()
    }
}

// ── Wiring (source checks) ──────────────────────────────────

private fun wiring() {
    val manager = source("lifesteal/combat/LifestealCombatManager.kt")
    val listener = source("lifesteal/combat/LifestealCombatListener.kt")
    val combatManager = source("manager/CombatManager.kt")
    val combatListener = source("listener/CombatListener.kt")
    val listeners = source("manager/ListenerManager.kt")
    val main = source("Joshymc.kt")
    val transfer = source("network/transfer/TransferManager.kt")
    val essentials = source("command/EssentialsCommands.kt")
    val teamCombat = source("lifesteal/teams/LifestealTeamCombatListener.kt")
    val command = source("lifesteal/combat/LifestealCombatCommand.kt")
    val plugin = resourceText("plugin.yml")

    // One combat-tag framework: the observer is optional and null by default (Survival unchanged).
    check("CombatManager observer defaults to null", combatManager.contains("@Volatile var tagObserver: TagObserver? = null"))
    check("without an observer the NPC is always spawned (Survival)", combatManager.contains("val observer = tagObserver ?: return true"))
    check("observer failures are isolated", combatManager.contains("Tag observer failed"))
    check("PvP hits tag through tagPvp", combatListener.contains("plugin.combatManager.tagPvp(attacker, victim)"))
    check("tagging stays MONITOR + ignoreCancelled (cancelled friendly fire never tags)",
        Regex("""MONITOR, ignoreCancelled = true\)\s*\n\s*fun onDamageTag""").containsMatchIn(combatListener))
    check("friendly fire is cancelled at HIGHEST, before tagging", teamCombat.contains("EventPriority.HIGHEST"))
    check("environmental damage never tags (needs a resolved player source)", combatListener.contains("val attacker = resolvePlayerSource(event) ?: return"))
    check("projectile attribution: shooter is resolved", combatListener.contains("val shooter = damager.shooter"))
    check("quit asks the observer before the NPC", combatListener.contains("shouldSpawnCombatLogNpc(player, event.reason)"))
    check("death clears the tag (death then disconnect is no combat log)", Regex("""fun onDeath\(event: PlayerDeathEvent\) \{\s*\n\s*val player = event.player\s*\n\s*plugin.combatManager.untag\(player\)""").containsMatchIn(combatListener))
    check("untag tells the observer", Regex("""fun untag\(player: Player\) \{\s*\n\s*combatTags.remove\(player.uniqueId\)\s*\n\s*observe""").containsMatchIn(combatManager))
    check("expiry tells the observer", combatManager.contains("observe(\"expiry\") { it.onTagExpired(player) }"))
    check("offline sweep tells the observer", combatManager.contains("observe(\"offline sweep\") { it.onTagCleared(uuid) }"))

    // Lifesteal only.
    check("manager only attaches where Lifesteal is hosted", manager.contains("if (!hosted) return") && manager.contains("plugin.combatManager.tagObserver = this"))
    check("every quit decision checks the Lifesteal world", manager.contains("if (!isLifesteal(player)) return true"))
    check("tags only count between Lifesteal players", manager.contains("!isLifesteal(victim) || !isLifesteal(attacker)"))
    check("listener registered only where Lifesteal is hosted", Regex("""if \(plugin.lifestealCombatManager.hosted\) \{\s*\n\s*pm.registerEvents\(com.liam.joshymc.lifesteal.combat.LifestealCombatListener""").containsMatchIn(listeners))
    check("store is created only after the hosted check", manager.indexOf("if (!hosted) return") < manager.indexOf("store.createTables()"))
    check("manager never touches hearts or health", !manager.contains("lifestealHeartManager") && !manager.contains("MAX_HEALTH") && !manager.contains(".health"))
    check("manager never touches inventories", !manager.contains("inventory"))
    check("incidents are written off the main thread", manager.contains("plugin.databaseManager.async(\"lifesteal combat-log incident\")"))
    check("write failures are kept for diagnostics", manager.contains("failures.addLast(failure)") && manager.contains("MAX_FAILURES"))
    check("shutdown counts as server-controlled", manager.contains("stopping || Bukkit.isStopping()"))
    check("kick cause comes from the real kick event (MONITOR, not cancelled)", listener.contains("MONITOR, ignoreCancelled = true") && listener.contains("event.cause.name"))
    check("kick causes are cleared on quit (bounded)", manager.contains("pendingKicks.remove(uuid)"))
    check("state is keyed by UUID, never Player", !Regex("""Map<Player""").containsMatchIn(manager) && !Regex("""Set<Player""").containsMatchIn(manager))
    check("one sweep task, cancelled before a new one", manager.contains("detach()") && manager.contains("cancelTask(sweepTask)"))
    check("team changes never end a combat session", !source("lifesteal/teams/LifestealTeamManager.kt").contains("lifestealCombatManager"))
    check("transfer pending is read from the TransferManager", manager.contains("plugin.transferManager.isTransferPending(uuid)"))

    // Lifecycle.
    check("refresh at startup", main.contains("        lifestealCombatManager.refresh()\n"))
    check("refresh on reload after combatManager.start", main.indexOf("safe(\"combatManager.start\")") < main.indexOf("safe(\"lifestealCombatManager.refresh\")"))
    check("shutdown step registered with an isInitialized guard", main.contains("shutdown.step(\"lifestealCombatManager.stop\", { ::lifestealCombatManager.isInitialized })"))
    check("shutdown step runs before the combat manager stops", main.indexOf("\"lifestealCombatManager.stop\"") < main.indexOf("\"combatManager.stop\""))

    // Transfers and teleports: the existing server-side gates (not only command cancellation).
    check("transfers refuse combat-tagged players (all of /lobby /hub /survival /lifesteal and future selectors)", transfer.contains("plugin.combatManager.isTagged(player) }) list += TransferBlocker.COMBAT_TAGGED"))
    // Since issue #1356 the messages live in teleport/CombatTeleportRules and every path goes through CombatTeleportGuard.
    check("TeleportChecks refuses combat-tagged players", essentials.contains("if (CombatTeleportGuard.blocks(plugin, player)) return true"))
    check("teleport warmups are cancelled on entering combat", essentials.contains("CombatTeleportRules.WarmupStep.CANCEL_COMBAT -> CombatTeleportGuard.notifyCancelled(plugin, player)"))
    for (cmd in listOf("spawn", "home", "tpa", "tpaccept", "warp", "rtp", "back")) {
        check("/$cmd is blocked in combat", Regex("\"$cmd\"").containsMatchIn(combatListener.substringBefore("BLOCKED_COMBAT_PREFIXED")))
    }
    for (file in listOf("command/HomeCommand.kt", "command/SpawnCommand.kt", "command/WarpCommand.kt", "command/RtpCommand.kt", "command/TpaCommand.kt")) {
        check("$file uses TeleportChecks", source(file).contains("TeleportChecks.checkAndApply"))
    }

    // Diagnostics.
    check("diagnostics permission is op", Regex("""joshymc.lifesteal.combat.admin:\s*\n\s*description: [^\n]+\n\s*default: op""").containsMatchIn(plugin))
    check("diagnostics check the permission first", command.indexOf("hasPermission(PERMISSION)") < command.indexOf("when (args.firstOrNull()"))
    check("no penalty command exists", !Regex("\"(punish|penalty|apply|remove|clear)\"").containsMatchIn(command))
    check("/lifesteal routes combat", source("command/ServerTransferCommand.kt").contains("combat.execute(sender, args.drop(1))"))

    // Survival untouched.
    check("Survival config untouched (no combat-logging in config.yml)", !resourceText("config.yml").contains("combat-logging"))
}
