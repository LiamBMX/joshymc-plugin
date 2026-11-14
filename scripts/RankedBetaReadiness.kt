// Ranked Team Wars closed-beta readiness summary (issue #1531). READ-ONLY, from the repo root:
//
//   ./gradlew -I scripts/ranked-beta-readiness.init.gradle.kts rankedBetaReadiness
//   ./gradlew -I scripts/ranked-beta-readiness.init.gradle.kts rankedBetaReadiness \
//       -PreadinessConfig=/path/to/test-server/plugins/Joshymc/lifesteal.yml \
//       -PreadinessDb=/path/to/copy-of/data.db
//
// What it does:
//   1. Checks that the QA documents exist and that every command, subcommand, config key,
//      permission, Gradle check task and docs/ link they mention exists in this repository.
//   2. Parses lifesteal.yml (the bundled one, or --config) with the plugin's own
//      LifestealConfigParser and reports the ranked settings, disabled switches and problems.
//   3. Optionally reads a COPY of a test server's data.db, opened read-only, and reports arenas
//      (validated with RankedArenaRules, the same rule the server uses, minus the world check),
//      open matches, snapshots waiting for a restore/review, failed ratings and the season.
//   4. Lists the automated check suites available under scripts/.
//
// It never writes a file or a database row, never grants hearts or Elo, never touches an
// inventory and needs no server credentials. It never says READY: the release decision is made
// by people, with evidence (docs/lifesteal-ranked-release-gate.md).
//
// Exit code: 1 only when the QA documents are missing or reference something that doesn't exist
// (so the docs can't silently drift from the code). Configuration and database findings are
// informational and never fail the run. Paper's classes target Java 25 and this runs on the
// Java 21 toolchain, so it never loads a Bukkit class.

import com.liam.joshymc.lifesteal.LifestealConfigParser
import com.liam.joshymc.lifesteal.ranked.Box
import com.liam.joshymc.lifesteal.ranked.Point
import com.liam.joshymc.lifesteal.ranked.RankedArena
import com.liam.joshymc.lifesteal.ranked.RankedArenaRules
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import kotlin.system.exitProcess

/** The QA documents this toolkit ships (issue #1531) plus the audit they build on. */
private val REQUIRED_DOCS = listOf(
    "docs/lifesteal-ranked-qa.md",
    "docs/lifesteal-ranked-qa-matrix.md",
    "docs/lifesteal-ranked-bug-report.md",
    "docs/lifesteal-ranked-release-gate.md",
    "docs/lifesteal-ranked-beta.md",
    "docs/lifesteal-beta.md",
    "docs/lifesteal-ranked.md",
    "docs/lifesteal-ranked-queue.md",
    "docs/lifesteal-ranked-elo.md",
    "docs/lifesteal-ranked-stats.md",
    "docs/lifesteal-hearts.md",
    "docs/lifesteal-teams.md",
    "docs/staff-permissions.md",
)

/** Documents whose backticked references are verified. */
private val VERIFIED_DOCS = REQUIRED_DOCS.take(4)

/** The ranked keys a test server must have (missing = the parser falls back to a default). */
private val REQUIRED_KEYS = listOf(
    "ranked-team-wars.enabled",
    "ranked-team-wars.team-sizes",
    "ranked-team-wars.countdown-seconds",
    "ranked-team-wars.max-match-minutes",
    "ranked-team-wars.disconnect-forfeit.heart-loss",
    "ranked-team-wars.matchmaking.enabled",
    "ranked-team-wars.matchmaking.consent-seconds",
    "ranked-team-wars.matchmaking.confirm-seconds",
    "ranked-team-wars.matchmaking.queue-timeout-minutes",
    "ranked-team-wars.matchmaking.requeue-cooldown-seconds",
    "ranked-team-wars.matchmaking.decline-cooldown-seconds",
    "ranked-team-wars.matchmaking.challenge-expire-seconds",
    "ranked-team-wars.matchmaking.challenge-cooldown-seconds",
    "ranked-team-wars.elo.initial-player-rating",
    "ranked-team-wars.elo.initial-team-rating",
    "ranked-team-wars.elo.minimum-rating",
    "ranked-team-wars.elo.maximum-rating",
    "ranked-team-wars.elo.k-factor",
    "ranked-team-wars.elo.rate-challenges",
    "ranked-team-wars.elo.same-opponent-limit",
    "ranked-team-wars.elo.same-opponent-window-hours",
    "ranked-team-wars.monitoring.enabled",
    "ranked-team-wars.leaderboards.minimum-player-matches",
    "ranked-team-wars.leaderboards.minimum-team-matches",
    "ranked-team-wars.divisions.enabled",
    "hearts.starting",
    "hearts.maximum",
    "hearts.minimum",
    "pvp.same-victim-cooldown-minutes",
    "teams.max-size",
    "progression.enabled",
    "progression.grace-period-hours",
    "progression.ranked-team-wars-unlock-after-hours",
    "heart-items.withdraw-eligibility.enabled",
    "combat-logging.count-timeouts",
)

/**
 * Where each command's next word is decided. A documented `/lifesteal admin ranked cancel` is
 * checked by finding the longest prefix here (`lifesteal admin ranked`) and requiring `cancel` to
 * be a string literal in one of its source files.
 */
private val SUBCOMMAND_SOURCES: Map<List<String>, List<String>> = mapOf(
    listOf("ranked") to listOf("lifesteal/ranked/queue/RankedCommand.kt"),
    listOf("ranked", "challenge") to listOf("lifesteal/ranked/queue/RankedCommand.kt"),
    listOf("lifesteal") to listOf("command/ServerTransferCommand.kt"),
    listOf("lifesteal", "admin") to listOf("command/ServerTransferCommand.kt", "lifesteal/ranked/LifestealRankedCommand.kt", "lifesteal/progression/LifestealProgressCommand.kt"),
    listOf("lifesteal", "admin", "ranked") to listOf("lifesteal/ranked/LifestealRankedCommand.kt"),
    listOf("lifesteal", "admin", "arena") to listOf("lifesteal/ranked/LifestealRankedCommand.kt"),
    listOf("lifesteal", "admin", "launch") to listOf("lifesteal/progression/LifestealProgressCommand.kt"),
    listOf("lifesteal", "admin", "ranked", "rating") to listOf("lifesteal/ranked/rating/RatingAdminCommand.kt"),
    listOf("lifesteal", "admin", "ranked", "stats") to listOf("lifesteal/ranked/stats/RankedStatsAdminCommand.kt"),
    listOf("lifesteal", "hearts") to listOf("lifesteal/hearts/LifestealHeartsCommand.kt", "lifesteal/hearts/HeartRules.kt"),
    listOf("lifesteal", "combat") to listOf("lifesteal/combat/LifestealCombatCommand.kt"),
    listOf("team") to listOf("lifesteal/teams/LifestealTeamCommand.kt"),
    listOf("team", "admin") to listOf("lifesteal/teams/LifestealTeamCommand.kt"),
    listOf("joshymc") to listOf("command/JoshyCommand.kt"),
)

/** Commands that aren't JoshyMC's (vanilla / LuckPerms) but are fine to mention. */
private val EXTERNAL_COMMANDS = setOf("stop", "tps", "kill", "kick", "ban", "lp", "op", "deop", "whitelist", "save-all", "minecraft:kill", "minecraft:tp")

private const val SRC = "src/main/kotlin/com/liam/joshymc/"

private val errors = mutableListOf<String>()
private val notes = mutableListOf<String>()

private fun section(title: String) = println("\n== $title ==")
private fun ok(msg: String) = println("  [ok]   $msg")
private fun warn(msg: String) { println("  [warn] $msg"); notes += msg }
private fun info(msg: String) = println("  [info] $msg")
private fun fail(msg: String) { println("  [FAIL] $msg"); errors += msg }

private fun loadYaml(file: File): Map<String, Any?>? = try {
    file.reader(Charsets.UTF_8).use { Yaml().load<Map<String, Any?>>(it) }.orEmpty()
} catch (e: Exception) {
    null
}

private fun Map<String, Any?>.path(path: String): Any? {
    var node: Any? = this
    for (part in path.split('.')) node = (node as? Map<*, *>)?.get(part) ?: return null
    return node
}

private fun Map<String, Any?>.has(path: String): Boolean {
    var node: Any? = this
    for (part in path.split('.')) {
        val map = node as? Map<*, *> ?: return false
        if (!map.containsKey(part)) return false
        node = map[part]
    }
    return true
}

private val LITERAL = Regex("\"([a-z][a-z0-9-]*)\"")

private val literalCache = HashMap<String, Set<String>>()
private fun literals(file: String): Set<String> = literalCache.getOrPut(file) {
    val f = File(SRC + file)
    if (!f.isFile) { fail("source file $SRC$file is missing (update SUBCOMMAND_SOURCES)"); return@getOrPut emptySet() }
    LITERAL.findAll(f.readText()).map { it.groupValues[1] }.toSet()
}

/** plugin.yml command names + aliases, and every permission it declares. */
private class PluginYml(map: Map<String, Any?>, val raw: String) {
    val commands: Set<String> = buildSet {
        @Suppress("UNCHECKED_CAST")
        val cmds = map["commands"] as? Map<String, Any?> ?: emptyMap()
        for ((name, body) in cmds) {
            add(name.lowercase())
            ((body as? Map<*, *>)?.get("aliases") as? List<*>)?.forEach { add(it.toString().lowercase()) }
        }
    }
    val permissions: Set<String> = (map["permissions"] as? Map<*, *>)?.keys?.map { it.toString() }?.toSet() ?: emptySet()
}

private fun gradleTasks(): Set<String> {
    val task = Regex("register(?:<[^>]*>)?\\(\"([A-Za-z]+)\"")
    return File("scripts").listFiles { f -> f.name.endsWith(".init.gradle.kts") }.orEmpty()
        .flatMap { f -> task.findAll(f.readText()).map { it.groupValues[1] } }
        .filterNot { it.endsWith("Sources") }.toSet()
}

private fun sourceMentions(text: String): Boolean = File(SRC.trimEnd('/')).walkTopDown()
    .filter { it.isFile && it.extension == "kt" }.any { text in it.readText() }

// ── 1. Documents ────────────────────────────────────────────

private fun checkDocs(lifesteal: Map<String, Any?>, config: Map<String, Any?>, plugin: PluginYml) {
    section("QA documents")
    for (doc in REQUIRED_DOCS) if (File(doc).isFile) ok(doc) else fail("$doc is missing")

    section("References in the QA documents")
    val tasks = gradleTasks()
    val span = Regex("`([^`\\n]+)`")
    val link = Regex("\\]\\(([^)#\\s]+\\.md)(?:#[^)]*)?\\)")
    val lifestealTop = lifesteal.keys
    val configTop = config.keys
    var commandsChecked = 0
    var keysChecked = 0
    var permsChecked = 0
    var tasksChecked = 0
    for (doc in VERIFIED_DOCS) {
        val file = File(doc)
        if (!file.isFile) continue
        val text = file.readText()
        for (m in link.findAll(text)) {
            val target = File(file.parentFile, m.groupValues[1]).normalize()
            if (!target.isFile) fail("$doc links to ${m.groupValues[1]}, which doesn't exist")
        }
        for (m in span.findAll(text)) {
            val s = m.groupValues[1].trim()
            when {
                s.startsWith("/") -> { commandsChecked++; checkCommand(doc, s, plugin) }
                s.startsWith("joshymc.") && Regex("^joshymc\\.[a-z0-9.*-]+$").matches(s) -> {
                    permsChecked++
                    if (s.removeSuffix(".*") !in plugin.permissions && !plugin.raw.contains(s) && !sourceMentions("\"$s\"")) {
                        fail("$doc: permission `$s` isn't declared in plugin.yml or used in the source")
                    }
                }
                Regex("^[a-z][a-zA-Z]+Check$").matches(s) || s == "rankedBetaReadiness" -> {
                    tasksChecked++
                    if (s !in tasks) fail("$doc: Gradle task `$s` isn't registered by any scripts/*.init.gradle.kts")
                }
                Regex("^(lifesteal\\.yml > |config\\.yml > )?[a-z][a-z0-9-]*(\\.[a-z0-9-]+)+$").matches(s) -> {
                    val inConfig = s.startsWith("config.yml > ")
                    val key = s.substringAfter(" > ")
                    val top = key.substringBefore('.')
                    when {
                        inConfig || (top in configTop && top !in lifestealTop) -> {
                            keysChecked++
                            if (!config.has(key)) fail("$doc: config.yml key `$key` doesn't exist in the bundled config.yml")
                        }
                        top in lifestealTop -> {
                            keysChecked++
                            if (!lifesteal.has(key)) fail("$doc: lifesteal.yml key `$key` doesn't exist in the bundled lifesteal.yml")
                        }
                        // Anything else (file names, table.column, ids) isn't a config key.
                    }
                }
            }
        }
    }
    info("checked $commandsChecked commands, $keysChecked config keys, $permsChecked permissions, $tasksChecked check tasks")
}

private fun checkCommand(doc: String, raw: String, plugin: PluginYml) {
    val words = raw.removePrefix("/").split(Regex("\\s+")).filter { it.isNotEmpty() }
    val root = words.firstOrNull()?.lowercase() ?: return
    if (root in EXTERNAL_COMMANDS || root.startsWith("minecraft:")) return
    if (root !in plugin.commands) return fail("$doc: `/$root` isn't a command in plugin.yml (from `$raw`)")
    // Walk the literal words (stop at the first placeholder) and check each one that has a source.
    val literal = words.takeWhile { w -> !w.startsWith("<") && !w.startsWith("[") && !w.contains('|') && !w.contains("...") }
        .map { it.lowercase() }
    for (i in 1 until literal.size) {
        val prefix = literal.subList(0, i)
        val files = SUBCOMMAND_SOURCES[prefix] ?: continue
        val word = literal[i]
        if (word == "confirm" || word.toIntOrNull() != null || word.matches(Regex("[2-5]v[2-5]"))) continue
        // A player/team/match name in the doc's examples (capitalised or an id) isn't a subcommand.
        if (word != word.lowercase() || words[i].first().isUpperCase()) continue
        if (files.none { word in literals(it) }) {
            fail("$doc: `/${prefix.joinToString(" ")} $word` isn't handled by ${files.joinToString()} (from `$raw`)")
            return
        }
    }
}

// ── 2. Configuration ────────────────────────────────────────

private fun checkConfig(file: File, bundled: Boolean) {
    section("lifesteal.yml (${if (bundled) "bundled default" else file.path})")
    val map = loadYaml(file) ?: return warn("${file.path} couldn't be read as YAML; Lifesteal would refuse to start its systems")
    for (key in REQUIRED_KEYS) if (!map.has(key)) warn("missing key `$key` (the server uses its default; add it so testers see the value)")
    val result = LifestealConfigParser.parse { map.path(it) }
    for (p in result.problems) if (p.severe) warn("SEVERE: ${p.message}") else info("note: ${p.message}")
    val s = result.settings ?: return warn("the file is INVALID: hearts, teams and ranked pause on a server using it (fix the SEVERE lines first)")
    val r = s.rankedTeamWars
    ok("parsed with LifestealConfigParser (the server's own parser)")
    info("hearts: start ${s.hearts.starting}, max ${s.hearts.maximum}, min ${s.hearts.minimum}; same-victim cooldown ${s.pvp.sameVictimCooldownMinutes} min")
    if (s.hearts.starting != 10 || s.hearts.maximum != 20 || s.hearts.minimum != 1) warn("hearts differ from the approved 10 / 20 / 1; the QA matrix assumes the approved values")
    info("ranked: sizes ${r.teamSizes.joinToString { "${it}v$it" }}, countdown ${r.countdownSeconds}s, max match ${r.maxMatchMinutes} min, forfeit hearts ${r.disconnectHeartLoss.id}")
    info("matchmaking: consent ${r.matchmaking.consentSeconds}s, confirm ${r.matchmaking.confirmSeconds}s, queue timeout ${r.matchmaking.queueTimeoutMinutes} min, decline cooldown ${r.matchmaking.declineCooldownSeconds}s")
    info("elo: initial ${r.elo.initialPlayerRating}/${r.elo.initialTeamRating}, range ${r.elo.minimumRating}..${r.elo.maximumRating}, K ${r.elo.kFactor}, challenges rated ${r.elo.rateChallenges}, same-opponent limit ${r.elo.sameOpponentLimit} per ${r.elo.sameOpponentWindowHours}h")
    if (!r.enabled) warn("ranked-team-wars.enabled is false: players can't use /ranked (staff test matches still work)")
    else warn("ranked-team-wars.enabled is TRUE: players can queue. Only on a dedicated test server, or after the release gate decision")
    if (!r.matchmaking.enabled) warn("ranked-team-wars.matchmaking.enabled is false: /ranked refuses new queues and challenges")
    if (r.teamSizes.toSet() != setOf(2, 3, 4, 5)) warn("team-sizes isn't 2..5: the matrix's size tests for the missing sizes can't run")
    if (r.elo.rateChallenges) warn("elo.rate-challenges is true: challenge tests in the matrix expect false")
    val p = s.progression
    if (!p.enabled) warn("progression.enabled is false: no launch schedule, nothing is held back (the Day 3 ranked unlock tests don't apply)")
    else info("progression: grace ${p.gracePeriodHours}h, ranked unlock +${p.rankedTeamWarsUnlockAfterHours}h, End +${p.endUnlockAfterHours}h")
    if (!s.heartItems.withdrawEligibility.enabled) warn("heart-items.withdraw-eligibility.enabled is false: withdraw-eligibility tests (HT-12) can't run")
    if (!s.teams.enabled) warn("teams.enabled is false: no team can queue")
    if (map.path("ranked-team-wars.monitoring.enabled") == false) warn("ranked-team-wars.monitoring.enabled is false: review-log tests can't run")
}

// ── 3. Test server database (read-only copy) ────────────────

private fun connect(file: File): Connection {
    val props = Properties()
    props["open_mode"] = "1" // SQLITE_OPEN_READONLY: the driver can't write, create or migrate
    return DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", props)
}

private fun Connection.tableExists(name: String): Boolean =
    prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { st ->
        st.setString(1, name); st.executeQuery().use { it.next() }
    }

private fun Connection.count(sql: String): Int = createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getInt(1) else 0 } }

private fun checkDatabase(file: File, sizes: Set<Int>) {
    section("Test server database (${file.path}, read-only)")
    if (!file.isFile) return warn("${file.path} doesn't exist")
    warn("use a COPY taken while the test server was stopped; never point this at production")
    try {
        connect(file).use { db ->
            if (!db.tableExists("lifesteal_ranked_arenas")) return warn("no ranked tables: this server never ran with Lifesteal hosted")
            val arenas = mutableListOf<RankedArena>()
            db.createStatement().use { st ->
                st.executeQuery("SELECT arena_id, display_name, world, min_x, min_y, min_z, max_x, max_y, max_z, team_a, team_b, spectators, sizes, enabled FROM lifesteal_ranked_arenas WHERE deleted = 0").use { rs ->
                    while (rs.next()) {
                        val box = if (rs.getObject("min_x") == null || rs.getObject("max_x") == null) null
                        else Box(rs.getInt("min_x"), rs.getInt("min_y"), rs.getInt("min_z"), rs.getInt("max_x"), rs.getInt("max_y"), rs.getInt("max_z"))
                        val id = rs.getString("arena_id")
                        val a = Point.decodeList(rs.getString("team_a"))
                        val b = Point.decodeList(rs.getString("team_b"))
                        val spec = Point.decodeList(rs.getString("spectators"))
                        if (a == null || b == null || spec == null) { warn("arena $id has unreadable spawn data"); continue }
                        val arenaSizes = rs.getString("sizes").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
                        arenas += RankedArena(id, rs.getString("display_name"), rs.getString("world"), box, a, b, spec, arenaSizes, rs.getInt("enabled") == 1)
                    }
                }
            }
            if (arenas.isEmpty()) warn("no ranked arenas: create one per size (docs/lifesteal-ranked-qa.md, section 6)")
            for (arena in arenas) {
                val v = RankedArenaRules.validate(arena, null, arenas, sizes)
                val line = "arena ${arena.id} (${arena.world}, ${arena.sizes.sorted().joinToString("/") { "${it}v$it" }}, ${if (arena.enabled) "enabled" else "disabled"}): ${v.readiness}" +
                    if (v.problems.isEmpty()) "" else " - " + v.problems.joinToString("; ")
                if (v.ready && arena.enabled) ok(line) else warn(line)
                if (arena.spectators.isEmpty()) warn("arena ${arena.id} has no spectator point: spectate and outsider relocation fall back (matrix AS tests need one)")
            }
            info("world loading isn't checked offline; /lifesteal admin arena info <id> on the server does")
            for (size in sizes.sorted()) {
                val hosts = arenas.count { it.enabled && size in it.sizes && RankedArenaRules.validate(it, null, arenas, sizes).ready }
                if (hosts == 0) warn("no enabled READY arena hosts ${size}v$size") else ok("${size}v$size: $hosts enabled READY arena(s)")
                if (hosts == 1) info("${size}v$size has one arena: the 'multiple independent matches' tests need two")
            }
            if (db.tableExists("lifesteal_ranked_matches")) {
                val open = db.count("SELECT COUNT(*) FROM lifesteal_ranked_matches WHERE state NOT IN ('COMPLETED', 'CANCELLED')")
                if (open > 0) warn("$open match(es) not COMPLETED/CANCELLED (normal only while the server runs; after a stop, startup recovery settles them)") else ok("no open matches")
            }
            if (db.tableExists("lifesteal_ranked_snapshots")) {
                for (status in listOf("PENDING", "RESTORING", "REVIEW")) {
                    val n = db.count("SELECT COUNT(*) FROM lifesteal_ranked_snapshots WHERE status = '$status'")
                    if (n > 0) warn("$n inventory snapshot(s) $status (see /lifesteal admin ranked restores)") else ok("no $status snapshots")
                }
            }
            if (db.tableExists("lifesteal_ranked_rating_results")) {
                val failed = db.count("SELECT COUNT(*) FROM lifesteal_ranked_rating_results WHERE status = 'FAILED'")
                if (failed > 0) warn("$failed rating result(s) FAILED (see /lifesteal admin ranked rating failed)") else ok("no FAILED rating results")
            }
            if (db.tableExists("lifesteal_seasons")) {
                val active = db.count("SELECT COUNT(*) FROM lifesteal_seasons WHERE status = 'ACTIVE'")
                if (active == 0) warn("no ACTIVE season: ratings belong to a season and ranked needs the launch (/lifesteal admin launch)") else ok("an ACTIVE season exists")
            }
            if (db.tableExists("lifesteal_teams")) {
                val teams = db.count("SELECT COUNT(*) FROM lifesteal_teams WHERE status = 'ACTIVE'")
                if (teams < 4) warn("$teams ACTIVE Lifesteal team(s): the matrix needs at least 4 (two matches at once)") else ok("$teams ACTIVE Lifesteal teams")
            }
        }
    } catch (e: Exception) {
        warn("couldn't read ${file.path} read-only: ${e.message} (copy data.db, data.db-wal and data.db-shm while the server is stopped)")
    }
}

// ── 4. Automated suites ─────────────────────────────────────

private fun listSuites() {
    section("Automated check suites (run each with ./gradlew -I scripts/<file> <task>)")
    val task = Regex("register(?:<[^>]*>)?\\(\"([A-Za-z]+Check|[A-Za-z]+Readiness)\"")
    val relevant = Regex("(?i)ranked|lifesteal|heart|team|staff|combat|shutdown|competitive|vanish|gamemode|network|rank-security")
    for (f in File("scripts").listFiles { f -> f.name.endsWith(".init.gradle.kts") }.orEmpty().sortedBy { it.name }) {
        if (!relevant.containsMatchIn(f.name)) continue
        for (m in task.findAll(f.readText())) info("${m.groupValues[1]}  (scripts/${f.name})")
    }
}

fun main(args: Array<String>) {
    if (!File("scripts/RankedBetaReadiness.kt").isFile) {
        System.err.println("Run from the repository root (the Gradle task does this).")
        exitProcess(2)
    }
    val configArg = args.firstOrNull { it.startsWith("--config=") }?.substringAfter('=')
    val dbArg = args.firstOrNull { it.startsWith("--db=") }?.substringAfter('=')
    println("Ranked Team Wars closed-beta readiness (read-only). This summary never decides READY.")

    val bundledLifesteal = loadYaml(File("src/main/resources/lifesteal.yml")) ?: emptyMap()
    val bundledConfig = loadYaml(File("src/main/resources/config.yml")) ?: emptyMap()
    val pluginFile = File("src/main/resources/plugin.yml")
    val plugin = PluginYml(loadYaml(pluginFile) ?: emptyMap(), pluginFile.readText())

    checkDocs(bundledLifesteal, bundledConfig, plugin)
    val configFile = configArg?.let(::File) ?: File("src/main/resources/lifesteal.yml")
    checkConfig(configFile, configArg == null)
    val sizes = (loadYaml(configFile)?.path("ranked-team-wars.team-sizes") as? List<*>)
        ?.mapNotNull { (it as? Number)?.toInt() }?.toSet() ?: setOf(2, 3, 4, 5)
    if (dbArg != null) checkDatabase(File(dbArg), sizes)
    else { section("Test server database"); info("not checked (pass -PreadinessDb=<copy of data.db> to report arenas, snapshots and ratings)") }
    listSuites()

    section("Summary")
    println("  ${errors.size} documentation error(s), ${notes.size} warning(s).")
    println("  Release decision: NOT DECIDED BY THIS TOOL. Use docs/lifesteal-ranked-release-gate.md with live evidence.")
    if (errors.isNotEmpty()) exitProcess(1)
}
