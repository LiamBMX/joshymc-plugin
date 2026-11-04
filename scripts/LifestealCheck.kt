// Checks the dedicated Lifesteal server bootstrap (issue #1336): server gamemode resolution,
// automatic isolation on a dedicated server, the Survival-only feature flags, lifesteal.yml
// defaults and validation, and that each server's database is its own. The Gradle build has no
// test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-check.init.gradle.kts lifestealCheck
//
// Paper's classes target Java 25 and this runs on the Java 21 toolchain, so it never loads a
// Bukkit class: YAML through snakeyaml, the wiring in Joshymc.kt by source text.
// Exits non-zero on the first failed check.

import com.liam.joshymc.gamemode.DedicatedServer
import com.liam.joshymc.gamemode.DedicatedServerFeatures
import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeFeature
import com.liam.joshymc.gamemode.GamemodeIsolation
import com.liam.joshymc.gamemode.GamemodeLockStore
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode.LIFESTEAL
import com.liam.joshymc.gamemode.ServerGamemode.SURVIVAL
import com.liam.joshymc.gamemode.ServerGamemodeConfig
import com.liam.joshymc.lifesteal.LifestealConfigParser
import com.liam.joshymc.lifesteal.LifestealRules
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.network.NetworkIdentity
import com.liam.joshymc.network.NetworkIdentityConfig
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
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

private fun resource(name: String): Map<String, Any?> {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(name)
        ?: run { System.err.println("FAIL: $name not on the classpath"); exitProcess(1) }
    return InputStreamReader(stream, Charsets.UTF_8).use { Yaml().load<Map<String, Any?>>(it) ?: emptyMap() }
}

private fun Map<String, Any?>.path(path: String): Any? {
    var node: Any? = this
    for (part in path.split('.')) node = (node as? Map<*, *>)?.get(part)
    return node
}

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private fun parse(yaml: String) = Yaml().load<Map<String, Any?>>(yaml).orEmpty().let { map -> LifestealConfigParser.parse { map.path(it) } }

/** The bundled lifesteal.yml with one line replaced. */
private fun withLine(from: String, to: String): String {
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    check("lifesteal.yml has '$from'", from in text)
    return text.replace(from, to)
}

private fun invalid(name: String, from: String, to: String, mentions: String) {
    val result = parse(withLine(from, to))
    check("$name: refused", result.settings == null)
    check("$name: severe problem mentions $mentions", result.problems.any { it.severe && mentions in it.message })
}

private val SURVIVAL_ONLY_SCOPE_COMMANDS = GamemodeAssignments.COMMANDS.filterValues { it == GamemodeScope.SURVIVAL }.keys

fun main() {
    gamemodeResolution()
    isolation()
    featureFlags()
    lifestealConfig()
    rankedClosedByDefault()
    wiring()
    databases()
    println("OK: $checks Lifesteal bootstrap checks passed.")
}

// ── Ranked Team Wars master switch (issue #1369) ─────────────

private fun rankedClosedByDefault() {
    val line = "  enabled: false\n  # Supported team sizes"
    check("bundled lifesteal.yml keeps Ranked closed to players", parse(withLine(line, line)).settings?.rankedTeamWars?.enabled == false)
    parse(withLine(line, "  # Supported team sizes")).let {
        check("missing ranked-team-wars.enabled = closed", it.settings?.rankedTeamWars?.enabled == false)
        check("missing ranked-team-wars.enabled is only a warning", it.problems.any { p -> !p.severe && "ranked-team-wars.enabled" in p.message })
    }
    check("ranked-team-wars.enabled: true opens it", parse(withLine(line, "  enabled: true\n  # Supported team sizes")).settings?.rankedTeamWars?.enabled == true)
    invalid("ranked-team-wars.enabled not a boolean", line, "  enabled: maybe\n  # Supported team sizes", "ranked-team-wars.enabled")
    check("code default keeps Ranked closed", !LifestealSettings.DEFAULTS.rankedTeamWars.enabled)
    val queue = source("lifesteal/ranked/queue/LifestealRankedQueueManager.kt")
    check("systemReady requires the master switch", Regex("val systemReady: Boolean get\\(\\) = hosted && playerAccess").containsMatchIn(queue))
    check("queueing refuses while closed", "if (!playerAccess) return CLOSED_MESSAGE" in queue)
    val command = source("lifesteal/ranked/queue/RankedCommand.kt")
    check("/ranked refuses players while closed", "if (!queue.commandVisibleTo(player))" in command)
    check("/ranked tab completion is empty while closed", "if (!queue.commandVisibleTo(player) ||" in command)
    check("/ranked is hidden from the command list while closed", "commandVisibleTo(event.player)" in source("lifesteal/ranked/LifestealRankedListener.kt"))
}

// ── Server gamemode and identity ─────────────────────────────

private fun gamemodeResolution() {
    // Default Survival config: unchanged.
    val config = resource("config.yml")
    check("bundled config stays survival", config.path("gamemode-lock.server-gamemode") == "survival")
    check("bundled server id stays survival", config.path("network.server-id") == "survival" && config.path("network.primary") == true)
    ServerGamemodeConfig.resolve(config.path("gamemode-lock.server-gamemode"), config.path("network.server-id")).let {
        check("bundled config resolves to survival", it.gamemode == SURVIVAL && it.valid && it.problems.isEmpty())
    }
    check("missing gamemode = survival (old default)", ServerGamemodeConfig.resolve(null, "lifesteal").let { it.gamemode == SURVIVAL && it.valid })
    check("blank gamemode = survival", ServerGamemodeConfig.resolve("  ", null).gamemode == SURVIVAL)

    // Lifesteal identification.
    ServerGamemodeConfig.resolve("lifesteal", "lifesteal").let {
        check("lifesteal recognized", it.gamemode == LIFESTEAL && it.valid && it.problems.isEmpty())
    }
    check("case/space tolerant", ServerGamemodeConfig.resolve(" LifeSteal ", null).gamemode == LIFESTEAL)
    NetworkIdentityConfig.resolve("lifesteal", false, LIFESTEAL).let {
        check("lifesteal identity", it.identity == NetworkIdentity("lifesteal", false) && it.problems.isEmpty())
    }

    // Invalid values: never silently Survival on an explicitly named Lifesteal server.
    ServerGamemodeConfig.resolve("lifestael", "lifesteal").let {
        check("typo on the lifesteal server stays lifesteal", it.gamemode == LIFESTEAL)
        check("typo is reported", !it.valid && it.problems.single().contains("lifestael"))
    }
    ServerGamemodeConfig.resolve("hardcore", "ls-1").let {
        check("unknown gamemode without a hint falls back to survival", it.gamemode == SURVIVAL)
        check("... and is reported, never silent", !it.valid && it.problems.single().contains("hardcore"))
    }
    check("non-string gamemode is invalid", !ServerGamemodeConfig.resolve(5, null).valid)
    check("world values: unknown ignored", ServerGamemodeConfig.worldGamemodes(listOf("lifesteal", "nope", 3, null)) == setOf(LIFESTEAL))
}

// ── Dedicated server isolation ───────────────────────────────

private fun isolation() {
    check("survival server isolates nothing", DedicatedServer.isolatedGamemodes(SURVIVAL, emptySet()).isEmpty())
    check("survival server with lifesteal worlds isolates nothing", DedicatedServer.isolatedGamemodes(SURVIVAL, setOf(LIFESTEAL)).isEmpty())
    check("dedicated lifesteal isolates survival", DedicatedServer.isolatedGamemodes(LIFESTEAL, emptySet()) == setOf(SURVIVAL))
    check("lifesteal hosting survival worlds isolates nothing", DedicatedServer.isolatedGamemodes(LIFESTEAL, setOf(SURVIVAL)).isEmpty())

    val implied = DedicatedServer.isolatedGamemodes(LIFESTEAL, emptySet())
    // GamemodeScope enforcement on the dedicated Lifesteal server, with no /jgamemode lock at all.
    check("there are survival commands to check", SURVIVAL_ONLY_SCOPE_COMMANDS.size > 50)
    for (name in SURVIVAL_ONLY_SCOPE_COMMANDS) {
        check("/$name refused on dedicated lifesteal", !GamemodeIsolation.allowed(GamemodeScope.SURVIVAL, LIFESTEAL, implied))
    }
    for ((name, scope) in GamemodeAssignments.COMMANDS) {
        if (scope == GamemodeScope.NETWORK) check("/$name works on dedicated lifesteal", GamemodeIsolation.allowed(scope, LIFESTEAL, implied))
    }
    for (feature in GamemodeFeature.entries.filter { it.scope == GamemodeScope.SURVIVAL }) {
        check("${feature.id} off on dedicated lifesteal", !GamemodeIsolation.allowed(feature.scope, LIFESTEAL, implied))
    }
    check("lifesteal scope works on lifesteal", GamemodeIsolation.allowed(GamemodeScope.LIFESTEAL, LIFESTEAL, implied))
    // Survival server: still nothing restricted.
    check("survival scope on survival", GamemodeIsolation.allowed(GamemodeScope.SURVIVAL, SURVIVAL, DedicatedServer.isolatedGamemodes(SURVIVAL, emptySet())))
}

// ── features.* flags ─────────────────────────────────────────

private fun featureFlags() {
    val features = resource("config.yml").path("features") as Map<*, *>
    val none = DedicatedServer.isolatedGamemodes(SURVIVAL, emptySet())
    val dedicated = DedicatedServer.isolatedGamemodes(LIFESTEAL, emptySet())
    for (flag in DedicatedServerFeatures.SURVIVAL_ONLY.keys) {
        check("features.$flag exists in config.yml", features.containsKey(flag))
        check("features.$flag as configured on survival", DedicatedServerFeatures.enabled(flag, true, none) && !DedicatedServerFeatures.enabled(flag, false, none))
        check("features.$flag off on dedicated lifesteal", !DedicatedServerFeatures.enabled(flag, true, dedicated))
    }
    for (flag in features.keys.map { it.toString() }.filter { it !in DedicatedServerFeatures.SURVIVAL_ONLY }) {
        check("features.$flag untouched on dedicated lifesteal", DedicatedServerFeatures.enabled(flag, true, dedicated))
        check("features.$flag can still be turned off", !DedicatedServerFeatures.enabled(flag, false, dedicated))
    }
    // Infrastructure that must keep running on Lifesteal.
    for (flag in listOf("anticheat", "stuck-recovery", "bedrock-pack", "combat-log-npc")) {
        check("$flag is not Survival-only", flag !in DedicatedServerFeatures.SURVIVAL_ONLY)
    }
    check("survival worlds on survival", DedicatedServerFeatures.survivalWorldsAllowed(none))
    check("no survival worlds on dedicated lifesteal", !DedicatedServerFeatures.survivalWorldsAllowed(dedicated))
}

// ── lifesteal.yml ────────────────────────────────────────────

private fun lifestealConfig() {
    val bundled = resource("lifesteal.yml")
    val result = LifestealConfigParser.parse { bundled.path(it) }
    check("bundled lifesteal.yml has no problems", result.problems.isEmpty())
    check("bundled lifesteal.yml = defaults", result.settings == LifestealSettings.DEFAULTS)
    check("config version", bundled["config-version"] == LifestealConfigParser.CONFIG_VERSION)

    // Approved blueprint values.
    val d = LifestealSettings.DEFAULTS
    check("starting hearts 10", d.hearts.starting == 10)
    check("max hearts 20", d.hearts.maximum == 20)
    check("min hearts 1", d.hearts.minimum == 1)
    check("same-victim cooldown 30 min", d.pvp.sameVictimCooldownMinutes == 30)
    check("team size 5", d.teams.maxSize == 5)
    check("ranked 2v2-5v5", d.rankedTeamWars.teamSizes == listOf(2, 3, 4, 5))
    check("season 3 months", d.seasons.lengthMonths == 3)
    check("grace 24h", d.progression.gracePeriodHours == 24)
    check("ranked +72h (Day 3)", d.progression.rankedTeamWarsUnlockAfterHours == 72)
    check("end +168h (Day 7)", d.progression.endUnlockAfterHours == 168)
    check("no launch time by default", d.progression.launchTimestamp == null && d.progression.enabled)
    check("no elimination", !LifestealRules.PERMANENT_ELIMINATION && !LifestealRules.REVIVAL)
    check("final heart kept", !LifestealRules.CAN_LOSE_FINAL_HEART && !LifestealRules.ONE_HEART_VICTIM_GIVES_HEART)
    check("ranked rules", LifestealRules.SEPARATE_INDIVIDUAL_AND_TEAM_ELO && LifestealRules.RANKED_USES_OWN_EQUIPMENT &&
        LifestealRules.RANKED_RESTORES_INVENTORY && LifestealRules.RANKED_HEARTS_AT_RISK)

    // Fresh/empty file: defaults, with warnings only.
    LifestealConfigParser.parse { null }.let {
        check("empty file uses defaults", it.settings == d)
        check("empty file only warns", it.problems.isNotEmpty() && it.problems.none { p -> p.severe })
    }
    parse(withLine("  maximum: 20\n", "")).let {
        check("missing key uses default", it.settings?.hearts?.maximum == 20)
        check("missing key warns", it.problems.single().let { p -> !p.severe && "hearts.maximum" in p.message })
    }

    // Validation.
    invalid("starting above maximum", "  starting: 10", "  starting: 25", "hearts.starting (25) is more than hearts.maximum")
    invalid("minimum above starting", "  minimum: 1", "  minimum: 12", "hearts.minimum (12) is more than hearts.starting")
    invalid("minimum zero", "  minimum: 1", "  minimum: 0", "hearts.minimum")
    invalid("negative minimum", "  minimum: 1", "  minimum: -1", "hearts.minimum")
    invalid("absurd maximum", "  maximum: 20", "  maximum: 5000", "hearts.maximum")
    invalid("fractional hearts", "  starting: 10", "  starting: 10.5", "not a whole number")
    invalid("text hearts", "  starting: 10", "  starting: ten", "not a whole number")
    invalid("negative cooldown", "same-victim-cooldown-minutes: 30", "same-victim-cooldown-minutes: -5", "pvp.same-victim-cooldown-minutes")
    invalid("team size zero", "  max-size: 5", "  max-size: 0", "teams.max-size")
    invalid("ranked size above team size", "  max-size: 5", "  max-size: 4", "between 2 and teams.max-size (4)")
    invalid("1v1 is not a team war", "team-sizes: [2, 3, 4, 5]", "team-sizes: [1, 2]", "entry 1")
    invalid("duplicate ranked size", "team-sizes: [2, 3, 4, 5]", "team-sizes: [2, 2]", "twice")
    invalid("ranked sizes not a list", "team-sizes: [2, 3, 4, 5]", "team-sizes: 5", "must be a list")
    invalid("empty ranked sizes", "team-sizes: [2, 3, 4, 5]", "team-sizes: []", "must be a list")
    invalid("zero season", "  length-months: 3", "  length-months: 0", "seasons.length-months")
    invalid("negative grace", "  grace-period-hours: 24", "  grace-period-hours: -1", "progression.grace-period-hours")
    invalid("negative unlock hours", "  end-unlock-after-hours: 168", "  end-unlock-after-hours: -1", "progression.end-unlock-after-hours")
    invalid("unlock after the season", "  ranked-team-wars-unlock-after-hours: 72", "  ranked-team-wars-unlock-after-hours: 9000", "progression.ranked-team-wars-unlock-after-hours")
    invalid("newer config version", "config-version: 1", "config-version: 2", "newer than this JoshyMC build")
    check("0 grace is allowed", parse(withLine("  grace-period-hours: 24", "  grace-period-hours: 0")).settings?.progression?.gracePeriodHours == 0)
    check("0 cooldown is allowed", parse(withLine("same-victim-cooldown-minutes: 30", "same-victim-cooldown-minutes: 0")).settings != null)
    check("sizes are sorted", parse(withLine("team-sizes: [2, 3, 4, 5]", "team-sizes: [5, 2]")).settings?.rankedTeamWars?.teamSizes == listOf(2, 5))
}

// ── Startup wiring (source) ──────────────────────────────────

private fun wiring() {
    val main = source("Joshymc.kt")
    for (flag in DedicatedServerFeatures.SURVIVAL_ONLY.keys) {
        check("Joshymc gates $flag through isFeatureEnabled", "isFeatureEnabled(\"$flag\")" in main)
    }
    check("isFeatureEnabled applies the dedicated-server rule", "networkIdentityManager.featureEnabled(feature, configured)" in main)
    check("lifesteal.yml loaded at startup", Regex("""networkIdentityManager\.load\(\)\s+//[^\n]*\n\s+lifestealConfigManager = """).containsMatchIn(main))
    check("lifesteal.yml reloaded after the identity", "safe(\"networkIdentityManager.load\") { networkIdentityManager.load() }\n        safe(\"lifestealConfigManager.load\")" in main)
    check("pvp world gated", "if (survivalWorldsAllowed()) WorldCommand.ensurePvpWorld(this)" in main)
    check("dungeon world gated", "if (survivalWorldsAllowed()) WorldCommand.ensureDungeonWorld(this)" in main)

    val manager = source("lifesteal/LifestealConfigManager.kt")
    check("lifesteal.yml only on lifesteal servers", "ServerGamemode.LIFESTEAL !in plugin.networkIdentityManager.hostedGamemodes" in manager)
    // The bootstrap's own files (config only). The heart tables (issue #1341) live in lifesteal/hearts/.
    val lifestealSources = File("src/main/kotlin/com/liam/joshymc/lifesteal").listFiles()!!.filter { it.isFile }.joinToString("\n") { it.readText() }
    check("no tables in the lifesteal config bootstrap", "createTable" !in lifestealSources && "databaseManager" !in lifestealSources)

    val locks = source("gamemode/GamemodeLockManager.kt")
    check("locks read the resolved gamemode", "serverGamemode = plugin.networkIdentityManager.gamemode" in locks)
    check("implied locks always in effect", "locked = storedLocks + implied" in locks)
}

// ── Databases ────────────────────────────────────────────────

private fun databases() {
    val root = Files.createTempDirectory("joshymc-lifesteal-check").toFile()
    val log = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }
    try {
        val survivalDir = File(root, "survival/plugins/Joshymc")
        val lifestealDir = File(root, "lifesteal/plugins/Joshymc")

        // Survival has a lock row of its own; the Lifesteal server starts from nothing.
        DatabaseManager(survivalDir, log).run {
            start()
            GamemodeLockStore(this).also { it.createTables() }.setLocked(SURVIVAL, true, "test", null, 1L)
            shutdown()
        }
        val survivalBytes = File(survivalDir, "data.db").readBytes()

        check("fresh lifesteal folder has no database", !File(lifestealDir, "data.db").exists())
        DatabaseManager(lifestealDir, log).run {
            start()
            check("fresh database opens", isOpen)
            val store = GamemodeLockStore(this).also { it.createTables() }
            check("fresh lifesteal database has no locks (nothing copied from survival)", store.lockedGamemodes().isEmpty())
            store.setLocked(LIFESTEAL, true, "test", null, 2L)
            shutdown()
            check("closed after shutdown", !isOpen)
        }
        check("lifesteal database is its own file", File(lifestealDir, "data.db").exists())
        check("survival database untouched", File(survivalDir, "data.db").readBytes().contentEquals(survivalBytes))

        // Restart: the Lifesteal state is still there, the Survival one never was.
        DatabaseManager(lifestealDir, log).run {
            start()
            check("lifesteal lock survives a restart", GamemodeLockStore(this).lockedGamemodes() == setOf(LIFESTEAL))
            shutdown()
        }
        DatabaseManager(survivalDir, log).run {
            start()
            check("survival lock unaffected", GamemodeLockStore(this).lockedGamemodes() == setOf(SURVIVAL))
            shutdown()
        }
    } finally {
        root.deleteRecursively()
    }
}
