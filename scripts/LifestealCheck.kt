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

import com.liam.joshymc.gamemode.ConfigIdentityBackfill
import com.liam.joshymc.gamemode.DedicatedServer
import com.liam.joshymc.gamemode.GamemodeLockEffect
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
    identityBackfill()
    lockEffect()
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
    // Issue #1552: the gate also lets a player answer a staff TEST match they were asked to play
    // (issue #1491), and nothing else. Everyone else is refused with CLOSED_MESSAGE before any subcommand runs.
    check("/ranked refuses players while closed",
        Regex("""if \(!queue\.commandVisibleTo\(player\) && !answeringTest\) \{\s*Msg\.error\(player, LifestealRankedQueueManager\.CLOSED_MESSAGE\)\s*return true\s*\}""").containsMatchIn(command))
    check("/ranked closed gate runs before any subcommand",
        command.indexOf("if (!queue.commandVisibleTo(player) && !answeringTest)").let { it >= 0 && it < command.indexOf("when (a.firstOrNull()?.lowercase())") })
    val answering = Regex("""val answeringTest = (.+)""").find(command)?.groupValues?.get(1)?.trim()
    check("/ranked closed bypass is only answering a staff test match",
        answering == "args.firstOrNull()?.lowercase() in setOf(\"accept\", \"decline\", \"deny\") && queue.tests.busy(player.uniqueId)")
    check("/ranked closed bypass is declared once", Regex("""answeringTest""").findAll(command).count() == 2)
    check("commandVisibleTo: closed to players without the admin node",
        Regex("""fun commandVisibleTo\(sender: org\.bukkit\.command\.CommandSender\): Boolean =\s*hosted && \(playerAccess \|\| sender\.hasPermission\(LifestealRankedManager\.ADMIN_PERMISSION\)\)""").containsMatchIn(queue))
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
    // Issue #1576: a missing key no longer turns an explicitly named Lifesteal server into Survival.
    ServerGamemodeConfig.resolve(null, "lifesteal").let {
        check("missing gamemode on the lifesteal server = lifesteal", it.gamemode == LIFESTEAL && it.valid && it.problems.isEmpty())
        check("... with a warning", it.warnings.single().contains("gamemode-lock.server-gamemode is not set"))
    }
    check("blank gamemode on the lifesteal server = lifesteal", ServerGamemodeConfig.resolve("  ", " Lifesteal ").gamemode == LIFESTEAL)
    ServerGamemodeConfig.resolve(null, "survival").let {
        check("missing gamemode on the survival server = survival, silently", it.gamemode == SURVIVAL && it.valid && it.warnings.isEmpty())
    }
    check("missing gamemode and server id = survival (old default)", ServerGamemodeConfig.resolve(null, null).let { it.gamemode == SURVIVAL && it.warnings.isEmpty() })
    check("missing gamemode, non-gamemode id = survival", ServerGamemodeConfig.resolve(null, "lobby").gamemode == SURVIVAL)
    check("blank gamemode = survival", ServerGamemodeConfig.resolve("  ", null).gamemode == SURVIVAL)
    check("explicit survival is never overridden by the id", ServerGamemodeConfig.resolve("survival", "lifesteal").gamemode == SURVIVAL)

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

// ── Config migration of the identity keys (issue #1576) ──────

/** What migrateConfig leaves for the three identity keys, given the on-disk values (null = missing). */
private fun migrated(gamemode: Any?, serverId: Any?, primary: Any?): Triple<Any?, Any?, Any?> {
    val values = mutableMapOf(
        ConfigIdentityBackfill.GAMEMODE_KEY to gamemode,
        ConfigIdentityBackfill.SERVER_ID_KEY to serverId,
        ConfigIdentityBackfill.PRIMARY_KEY to primary,
    )
    val fills = ConfigIdentityBackfill.fills(gamemode, serverId, primary)
    for (fill in fills) {
        check("fill ${fill.key} only when missing", values[fill.key] == null)
        values[fill.key] = fill.value
    }
    return Triple(values[ConfigIdentityBackfill.GAMEMODE_KEY], values[ConfigIdentityBackfill.SERVER_ID_KEY], values[ConfigIdentityBackfill.PRIMARY_KEY])
}

private fun startsAs(gamemode: Any?, serverId: Any?, primary: Any?, worlds: Set<com.liam.joshymc.gamemode.ServerGamemode> = emptySet()) =
    ServerGamemodeConfig.resolve(gamemode, serverId).gamemode.let { gm ->
        Triple(gm, NetworkIdentityConfig.resolve(serverId, primary, gm), DedicatedServer.isolatedGamemodes(gm, worlds))
    }

private fun identityBackfill() {
    check("identity keys are the three", ConfigIdentityBackfill.KEYS == setOf("gamemode-lock.server-gamemode", "network.server-id", "network.primary"))

    // Fresh Lifesteal configuration (the expected configuration in the issue).
    check("fresh lifesteal: nothing filled", ConfigIdentityBackfill.fills("lifesteal", "lifesteal", false).isEmpty())
    startsAs("lifesteal", "lifesteal", false).let { (gm, id, isolated) ->
        check("fresh lifesteal starts as lifesteal", gm == LIFESTEAL)
        check("fresh lifesteal identity is clean", id.identity == NetworkIdentity("lifesteal", false) && id.problems.isEmpty())
        check("fresh lifesteal isolates survival with no lock", isolated == setOf(SURVIVAL))
    }

    // Fresh Survival configuration (the bundled file): unchanged.
    val config = resource("config.yml")
    val bundled = Triple(config.path("gamemode-lock.server-gamemode"), config.path("network.server-id"), config.path("network.primary"))
    check("fresh survival: nothing filled", ConfigIdentityBackfill.fills(bundled.first, bundled.second, bundled.third).isEmpty())
    startsAs(bundled.first, bundled.second, bundled.third).let { (gm, id, isolated) ->
        check("fresh survival starts as survival, primary", gm == SURVIVAL && id.identity == NetworkIdentity("survival", true) && id.problems.isEmpty())
        check("fresh survival isolates nothing", isolated.isEmpty())
    }

    // Legacy config from before #1323/#1289 (no network or gamemode-lock section): the old Survival defaults.
    check("legacy config gets the old survival defaults", migrated(null, null, null) == Triple("survival", "survival", true))
    migrated(null, null, null).let { (g, s, p) ->
        check("legacy config still starts as the primary survival server", startsAs(g, s, p).let { it.first == SURVIVAL && it.second.identity.primary && it.third.isEmpty() })
    }

    // The reported bug: a Lifesteal config missing server-gamemode was backfilled with survival.
    migrated(null, "lifesteal", false).let { (g, s, p) ->
        check("missing server-gamemode on the lifesteal server is filled with lifesteal", g == "lifesteal" && s == "lifesteal" && p == false)
        check("... and it starts as a dedicated lifesteal server", startsAs(g, s, p).let { it.first == LIFESTEAL && it.third == setOf(SURVIVAL) })
    }
    check("missing server-gamemode + primary on lifesteal: never a second primary", migrated(null, "lifesteal", null) == Triple("lifesteal", "lifesteal", false))
    check("missing server-id on lifesteal: filled with lifesteal, not survival", migrated("lifesteal", null, null) == Triple("lifesteal", "lifesteal", false))
    check("missing server-id on survival: old default", migrated("survival", null, null) == Triple("survival", "survival", true))
    check("administrator values are never changed", migrated("lifesteal", "ls-1", true) == Triple("lifesteal", "ls-1", true))
    check("non-gamemode id, missing gamemode: survival as before", migrated(null, "lobby", true) == Triple("survival", "lobby", true))
    check("explicit primary false on survival kept", migrated("survival", "survival", false).third == false)

    // Conflicting network identity: reported, primary refused, but neither value rewritten.
    check("conflict: nothing filled", ConfigIdentityBackfill.fills("survival", "lifesteal", true).isEmpty())
    startsAs("survival", "lifesteal", true).let { (gm, id, _) ->
        check("conflict keeps the configured gamemode (never guessed)", gm == SURVIVAL)
        check("conflict refuses primary", !id.identity.primary)
        check("conflict is severe and names the fix", id.problems.any { it.severe && "gamemode-lock.server-gamemode: lifesteal" in it.message })
    }
    check("conflict detector", NetworkIdentityConfig.conflict("lifesteal", SURVIVAL) == LIFESTEAL && NetworkIdentityConfig.conflict("survival", LIFESTEAL) == SURVIVAL)
    check("no conflict when they agree or the id is not a gamemode",
        NetworkIdentityConfig.conflict("lifesteal", LIFESTEAL) == null && NetworkIdentityConfig.conflict("lobby", LIFESTEAL) == null)

    // migrateConfig wiring: identity keys filled from the file first, never from the bundled values.
    val main = source("Joshymc.kt")
    val migrate = main.substring(main.indexOf("private fun migrateConfig()"))
    check("migrateConfig fills identity keys", "ConfigIdentityBackfill.fills(" in migrate)
    check("migrateConfig skips identity keys in the generic backfill", "if (key in com.liam.joshymc.gamemode.ConfigIdentityBackfill.KEYS) continue" in migrate)
    check("identity fill runs before the generic backfill", migrate.indexOf("ConfigIdentityBackfill.fills(") < migrate.indexOf("for (key in defaults.getKeys(true))"))
    check("migrateConfig runs before the network identity loads", main.indexOf("migrateConfig()\n") < main.indexOf("networkIdentityManager.load()"))
}

// ── /jgamemode lock effect (issue #1576) ─────────────────────

private fun lockEffect() {
    val dedicated = DedicatedServer.isolatedGamemodes(LIFESTEAL, emptySet())
    val lifestealOnly = setOf(LIFESTEAL)
    check("dedicated lifesteal: locking survival is automatic", GamemodeLockEffect.of(SURVIVAL, lifestealOnly, dedicated) == GamemodeLockEffect.Effect.AUTOMATIC)
    check("dedicated lifesteal: locking lifesteal has no effect", GamemodeLockEffect.of(LIFESTEAL, lifestealOnly, dedicated) == GamemodeLockEffect.Effect.NO_EFFECT)
    check("survival server: locking survival has no effect", GamemodeLockEffect.of(SURVIVAL, setOf(SURVIVAL), emptySet()) == GamemodeLockEffect.Effect.NO_EFFECT)
    check("survival server: locking lifesteal restricts", GamemodeLockEffect.of(LIFESTEAL, setOf(SURVIVAL), emptySet()) == GamemodeLockEffect.Effect.RESTRICTS)

    // Valid mixed-world overrides keep working: nothing automatic, both locks restrict.
    val mixedIsolated = DedicatedServer.isolatedGamemodes(LIFESTEAL, setOf(SURVIVAL))
    check("mixed lifesteal + survival worlds isolates nothing", mixedIsolated.isEmpty())
    for (gm in listOf(SURVIVAL, LIFESTEAL)) {
        check("mixed worlds: locking ${gm.id} restricts", GamemodeLockEffect.of(gm, setOf(LIFESTEAL, SURVIVAL), mixedIsolated) == GamemodeLockEffect.Effect.RESTRICTS)
    }
    check("survival server with lifesteal worlds: locking survival restricts",
        GamemodeLockEffect.of(SURVIVAL, setOf(SURVIVAL, LIFESTEAL), emptySet()) == GamemodeLockEffect.Effect.RESTRICTS)

    // Messages: warnings exactly when the command changes nothing.
    check("no note for a restricting lock", GamemodeLockEffect.note(LIFESTEAL, true, GamemodeLockEffect.Effect.RESTRICTS, SURVIVAL) == null)
    check("no note for a lifting unlock", GamemodeLockEffect.note(LIFESTEAL, false, GamemodeLockEffect.Effect.RESTRICTS, SURVIVAL) == null)
    check("own-gamemode lock warns", GamemodeLockEffect.note(LIFESTEAL, true, GamemodeLockEffect.Effect.NO_EFFECT, LIFESTEAL)!!.startsWith("Locking Lifesteal has no effect here"))
    check("automatic lock explains", "already isolated automatically" in GamemodeLockEffect.note(SURVIVAL, true, GamemodeLockEffect.Effect.AUTOMATIC, LIFESTEAL)!!)
    check("automatic unlock explains", "stays isolated anyway" in GamemodeLockEffect.note(SURVIVAL, false, GamemodeLockEffect.Effect.AUTOMATIC, LIFESTEAL)!!)
    val command = source("command/JGamemodeCommand.kt")
    check("/jgamemode uses the lock effect", "locks.lockEffect(gamemode)" in command && "GamemodeLockEffect.note(" in command)
    check("/jgamemode info shows identity conflicts", "network.identityConflict" in command)
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
