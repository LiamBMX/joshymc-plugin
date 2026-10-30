// Checks the Bukkit-free cross-server transfer rules (issue #1331). The Gradle build has no test
// source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/network-check.init.gradle.kts networkTransferCheck
//
// Exits non-zero on the first failed check. Paper's classes target Java 25 and this runs on the
// Java 21 toolchain, so it never loads a Bukkit class (YAML through snakeyaml).

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.network.NetworkIdentity
import com.liam.joshymc.network.NetworkIdentityConfig
import com.liam.joshymc.network.transfer.ProxyMessages
import com.liam.joshymc.network.transfer.ProxyType
import com.liam.joshymc.network.transfer.TransferBlocker
import com.liam.joshymc.network.transfer.TransferConfig
import com.liam.joshymc.network.transfer.TransferContext
import com.liam.joshymc.network.transfer.TransferDecision
import com.liam.joshymc.network.transfer.TransferDestination
import com.liam.joshymc.network.transfer.TransferDestination.LIFESTEAL
import com.liam.joshymc.network.transfer.TransferDestination.LOBBY
import com.liam.joshymc.network.transfer.TransferDestination.SURVIVAL
import com.liam.joshymc.network.transfer.TransferRefusal
import com.liam.joshymc.network.transfer.TransferRules
import com.liam.joshymc.network.transfer.TransferSettings
import com.liam.joshymc.network.transfer.TransferTracker
import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStreamReader
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

private fun resource(name: String): Map<String, Any?> {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(name)
        ?: run { System.err.println("FAIL: $name not on the classpath"); exitProcess(1) }
    return InputStreamReader(stream, Charsets.UTF_8).use { Yaml().load<Map<String, Any?>>(it) }
}

private fun Map<String, Any?>.path(path: String): Any? {
    var node: Any? = this
    for (part in path.split('.')) node = (node as? Map<*, *>)?.get(part)
    return node
}

private val DEFAULT_SERVERS = mapOf<String, Any?>("lobby" to "lobby", "survival" to "survival", "lifesteal" to "lifesteal")

private fun resolve(
    enabled: Any? = true,
    proxy: Any? = "velocity",
    transfers: Any? = true,
    cooldown: Any? = 5,
    timeout: Any? = 10,
    servers: Map<String, Any?>? = DEFAULT_SERVERS,
) = TransferConfig.resolve(enabled, proxy, transfers, cooldown, timeout, servers)

private val NETWORK = resolve().settings

private fun context(
    serverId: String = "survival",
    isPlayer: Boolean = true,
    permission: Boolean = true,
    shuttingDown: Boolean = false,
    proxyReady: Boolean = true,
    onProxy: Boolean = true,
    pending: Boolean = false,
    cooldown: Long = 0,
    blockers: List<TransferBlocker> = emptyList(),
) = TransferContext(isPlayer, permission, serverId, shuttingDown, proxyReady, onProxy, pending, cooldown, blockers)

private fun refusal(settings: TransferSettings, destination: TransferDestination, context: TransferContext): TransferRefusal? =
    (TransferRules.decide(settings, destination, context) as? TransferDecision.Refused)?.reason

private fun readConnect(bytes: ByteArray): Pair<String, String> =
    DataInputStream(ByteArrayInputStream(bytes)).use { it.readUTF() to it.readUTF() }

fun main() {
    // ── Bundled config: standalone, no change for the existing Survival server ──
    val config = resource("config.yml")
    @Suppress("UNCHECKED_CAST")
    val bundled = TransferConfig.resolve(
        config.path("network.enabled"),
        config.path("network.proxy.type"),
        config.path("network.transfers.enabled"),
        config.path("network.transfers.cooldown-seconds"),
        config.path("network.transfers.timeout-seconds"),
        config.path("network.servers") as? Map<String, Any?>,
    )
    check("bundled network.enabled is false", config.path("network.enabled") == false)
    check("bundled transfers.enabled is false", config.path("network.transfers.enabled") == false)
    check("bundled config is not active", !bundled.settings.active)
    check("bundled config has no problems", bundled.problems.isEmpty())
    check("bundled destinations resolve", bundled.settings.servers == mapOf(LOBBY to "lobby", SURVIVAL to "survival", LIFESTEAL to "lifesteal"))
    check("bundled cooldown 5s", bundled.settings.cooldownMillis == 5_000L)
    // Server identity is untouched by the new keys.
    val identity = NetworkIdentityConfig.resolve(config.path("network.server-id"), config.path("network.primary"), ServerGamemode.SURVIVAL)
    check("identity still survival/primary", identity.identity == NetworkIdentity("survival", true) && identity.problems.isEmpty())
    check("lifesteal identity unchanged", NetworkIdentityConfig.resolve("lifesteal", false, ServerGamemode.LIFESTEAL).identity == NetworkIdentity("lifesteal", false))

    // ── Network disabled ──
    check("missing keys = standalone", !resolve(null, null, null, null, null, null).settings.active)
    check("missing keys have no problems", resolve(null, null, null, null, null, null).problems.isEmpty())
    check("STANDALONE is not active", !TransferConfig.STANDALONE.active)
    val standalone = resolve(enabled = false).settings
    for (d in TransferDestination.entries) {
        check("disabled network refuses ${d.id}", refusal(standalone, d, context()) == TransferRefusal.NETWORK_DISABLED)
    }
    check("transfers off refuses", refusal(resolve(transfers = false).settings, LOBBY, context()) == TransferRefusal.TRANSFERS_DISABLED)
    check("transfers on without network warns", resolve(enabled = false).problems.any { !it.severe })
    check("non-boolean enabled is off + severe", resolve(enabled = "yes").let { !it.settings.networkEnabled && it.problems.any { p -> p.severe } })
    check("STANDALONE refuses", refusal(TransferConfig.STANDALONE, LOBBY, context()) == TransferRefusal.NETWORK_DISABLED)

    // ── Destination resolution ──
    check("network config active", NETWORK.active)
    check("lobby allowed from survival", TransferRules.decide(NETWORK, LOBBY, context()) == TransferDecision.Allowed(LOBBY, "lobby"))
    check("lifesteal allowed from survival", TransferRules.decide(NETWORK, LIFESTEAL, context()) == TransferDecision.Allowed(LIFESTEAL, "lifesteal"))
    resolve(servers = mapOf("lobby" to " hub-1 ", "survival" to "smp", "lifesteal" to "Lifesteal_S1")).settings.let {
        check("names trimmed", it.proxyServer(LOBBY) == "hub-1")
        check("custom names kept", it.proxyServer(SURVIVAL) == "smp" && it.proxyServer(LIFESTEAL) == "Lifesteal_S1")
    }
    check("destination ids", TransferDestination.fromId(" Lobby ") == LOBBY && TransferDestination.fromId("hub") == null)
    check("commands map to destinations", TransferDestination.entries.all { TransferDestination.fromCommand(it.command) == it })
    check("permission nodes", LOBBY.permission == "joshymc.transfer.lobby" && LIFESTEAL.permission == "joshymc.transfer.lifesteal")

    // ── Invalid / unsupported destinations ──
    for (bad in listOf("127.0.0.1", "play.example.com", "lobby:25565", "lobby server", "a".repeat(65), "lobby/1", "über")) {
        check("'$bad' is not a server name", TransferConfig.normalizeServerName(bad) == null)
        resolve(servers = DEFAULT_SERVERS + ("lobby" to bad)).let {
            check("'$bad' leaves lobby unavailable", it.settings.proxyServer(LOBBY) == null && it.problems.any { p -> p.severe })
            check("'$bad' never falls back", refusal(it.settings, LOBBY, context()) == TransferRefusal.DESTINATION_UNAVAILABLE)
            check("'$bad' leaves others working", it.settings.proxyServer(LIFESTEAL) == "lifesteal")
        }
    }
    check("64 chars allowed", TransferConfig.normalizeServerName("a".repeat(64)) != null)
    check("non-string name rejected", resolve(servers = DEFAULT_SERVERS + ("lobby" to 5)).settings.proxyServer(LOBBY) == null)
    resolve(servers = DEFAULT_SERVERS + ("lifesteal" to "")).let {
        check("blank = not configured, no problem", it.settings.proxyServer(LIFESTEAL) == null && it.problems.isEmpty())
        check("unconfigured lifesteal refused", refusal(it.settings, LIFESTEAL, context()) == TransferRefusal.DESTINATION_UNAVAILABLE)
    }
    resolve(servers = DEFAULT_SERVERS + ("creative" to "creative")).let {
        check("unknown destination key ignored", it.settings.servers.size == 3 && it.problems.any { p -> !p.severe })
    }
    resolve(servers = mapOf("lobby" to "main", "survival" to "Main", "lifesteal" to "lifesteal")).let {
        check("shared proxy name disables both", it.settings.proxyServer(LOBBY) == null && it.settings.proxyServer(SURVIVAL) == null)
        check("shared name reported once", it.problems.count { p -> p.severe } == 1)
        check("third destination kept", it.settings.proxyServer(LIFESTEAL) == "lifesteal")
    }
    resolve(servers = null).let {
        check("missing servers section: nothing available", it.settings.servers.isEmpty() && it.problems.any { p -> p.severe })
    }
    resolve(servers = emptyMap()).let { check("no destinations is severe", it.problems.any { p -> p.severe }) }

    // ── Missing / unsupported proxy ──
    resolve(proxy = "bungeecord").let {
        check("bungeecord unsupported", it.settings.proxy == null && !it.settings.active && it.problems.any { p -> p.severe })
        check("unsupported proxy refused", refusal(it.settings, LOBBY, context()) == TransferRefusal.PROXY_UNAVAILABLE)
    }
    check("proxy type case-insensitive", resolve(proxy = " Velocity ").settings.proxy == ProxyType.VELOCITY)
    check("missing proxy type = velocity", resolve(proxy = null).settings.proxy == ProxyType.VELOCITY)
    check("proxy not ready refused", refusal(NETWORK, LOBBY, context(proxyReady = false)) == TransferRefusal.PROXY_UNAVAILABLE)
    check("direct join refused", refusal(NETWORK, LOBBY, context(onProxy = false)) == TransferRefusal.NOT_ON_PROXY)
    check("velocity channel detected", ProxyMessages.announcedByProxy(setOf("minecraft:brand", "bungeecord:main")))
    check("legacy channel name detected", ProxyMessages.announcedByProxy(setOf("BungeeCord")))
    check("no proxy channel", !ProxyMessages.announcedByProxy(setOf("minecraft:brand", "floodgate:form")))

    // ── Current-server detection ──
    check("survival -> /survival refused", refusal(NETWORK, SURVIVAL, context("survival")) == TransferRefusal.ALREADY_CONNECTED)
    check("lifesteal -> /lifesteal refused", refusal(NETWORK, LIFESTEAL, context("lifesteal")) == TransferRefusal.ALREADY_CONNECTED)
    check("lobby -> /lobby refused", refusal(NETWORK, LOBBY, context("lobby")) == TransferRefusal.ALREADY_CONNECTED)
    check("lobby -> /survival allowed", TransferRules.decide(NETWORK, SURVIVAL, context("lobby")) is TransferDecision.Allowed)
    resolve(servers = mapOf("lobby" to "hub", "survival" to "smp", "lifesteal" to "ls")).settings.let {
        check("server id matching proxy name is current", refusal(it, SURVIVAL, context("smp")) == TransferRefusal.ALREADY_CONNECTED)
        check("server id matching destination id is current", refusal(it, SURVIVAL, context("survival")) == TransferRefusal.ALREADY_CONNECTED)
        check("other server not current", TransferRules.decide(it, SURVIVAL, context("survival-2")) == TransferDecision.Allowed(SURVIVAL, "smp"))
    }

    // ── Permission and player-only ──
    check("console refused first", refusal(NETWORK, LOBBY, context(isPlayer = false)) == TransferRefusal.PLAYERS_ONLY)
    check("console refused even standalone", refusal(standalone, LOBBY, context(isPlayer = false)) == TransferRefusal.PLAYERS_ONLY)
    check("no permission refused", refusal(NETWORK, LOBBY, context(permission = false)) == TransferRefusal.NO_PERMISSION)
    check("no permission before disabled", refusal(standalone, LOBBY, context(permission = false)) == TransferRefusal.NO_PERMISSION)

    // ── Safety, shutdown, duplicates, cooldown ──
    check("shutdown refused", refusal(NETWORK, LOBBY, context(shuttingDown = true)) == TransferRefusal.SHUTTING_DOWN)
    check("pending refused", refusal(NETWORK, LOBBY, context(pending = true)) == TransferRefusal.ALREADY_PENDING)
    (TransferRules.decide(NETWORK, LOBBY, context(cooldown = 2_100)) as TransferDecision.Refused).let {
        check("cooldown refused", it.reason == TransferRefusal.COOLDOWN && it.remainingMillis == 2_100L)
        check("cooldown seconds round up", TransferRules.secondsLeft(it.remainingMillis) == 3L)
    }
    for (blocker in TransferBlocker.entries) {
        val decision = TransferRules.decide(NETWORK, LOBBY, context(blockers = listOf(blocker))) as? TransferDecision.Refused
        check("${blocker.name} blocks", decision?.reason == TransferRefusal.BLOCKED && decision.blocker == blocker)
    }
    check("first blocker reported", (TransferRules.decide(NETWORK, LOBBY,
        context(blockers = listOf(TransferBlocker.COMBAT_TAGGED, TransferBlocker.TRADING))) as TransferDecision.Refused).blocker == TransferBlocker.COMBAT_TAGGED)

    // ── Tracker: cooldowns, duplicates, cancellation ──
    val tracker = TransferTracker()
    val a = UUID.randomUUID()
    val b = UUID.randomUUID()
    check("no cooldown at first", tracker.cooldownRemaining(a, 1_000, 5_000) == 0L)
    val first = tracker.begin(a, LOBBY, 1_000)
    check("begin returns pending", first != null && tracker.isPending(a))
    check("duplicate begin dropped", tracker.begin(a, LIFESTEAL, 1_001) == null && tracker.pendingFor(a)?.destination == LOBBY)
    check("other player independent", tracker.begin(b, LOBBY, 1_001) != null)
    check("cooldown running", tracker.cooldownRemaining(a, 3_000, 5_000) == 3_000L)
    check("cooldown over", tracker.cooldownRemaining(a, 6_000, 5_000) == 0L)
    check("zero cooldown", tracker.cooldownRemaining(a, 1_000, 0) == 0L)
    check("not expired early", tracker.expire(a, first!!.token, 5_000, 10_000) == null && tracker.isPending(a))
    check("wrong token can't expire", tracker.expire(a, first.token + 100, 20_000, 10_000) == null)
    check("expires after timeout", tracker.expire(a, first.token, 11_000, 10_000) != null && !tracker.isPending(a))
    check("cooldown survives timeout", tracker.cooldownRemaining(a, 4_000, 5_000) == 2_000L)
    val second = tracker.begin(a, LIFESTEAL, 7_000)!!
    check("new token", second.token != first.token)
    check("stale token can't cancel", tracker.cancel(a, first.token) == null && tracker.isPending(a))
    check("cancel by token", tracker.cancel(a, second.token)?.destination == LIFESTEAL && !tracker.isPending(a))
    check("old timeout can't end a new transfer", tracker.begin(a, LOBBY, 8_000)!!.let { tracker.expire(a, second.token, 99_000, 0) == null && tracker.isPending(a) })
    tracker.forget(a)
    check("quit forgets pending + cooldown", !tracker.isPending(a) && tracker.cooldownRemaining(a, 8_001, 5_000) == 0L)
    check("clearPending counts", tracker.clearPending() == 1 && tracker.pendingCount() == 0)
    check("cleared player can transfer again", tracker.begin(b, LOBBY, 2_000_000) != null)

    // ── Cooldown/timeout config ──
    check("cooldown 0 allowed", resolve(cooldown = 0).settings.cooldownMillis == 0L)
    check("cooldown clamped", resolve(cooldown = 99_999).settings.cooldownMillis == TransferConfig.MAX_COOLDOWN_SECONDS * 1000)
    check("negative cooldown clamped", resolve(cooldown = -3).settings.cooldownMillis == 0L)
    check("text cooldown = default", resolve(cooldown = "5s").let { it.settings.cooldownMillis == 5_000L && it.problems.isNotEmpty() })
    check("fractional cooldown = default", resolve(cooldown = 2.5).settings.cooldownMillis == 5_000L)
    check("timeout clamped low", resolve(timeout = 1).settings.timeoutMillis == TransferConfig.MIN_TIMEOUT_SECONDS * 1000)

    // ── Velocity message format ──
    check("Connect message", readConnect(ProxyMessages.connect("lifesteal")) == ("Connect" to "lifesteal"))
    check("BungeeCord channel", ProxyMessages.CHANNEL == "BungeeCord")

    // ── Commands: plugin.yml + scopes; existing commands untouched ──
    val pluginYml = resource("plugin.yml")
    @Suppress("UNCHECKED_CAST")
    val commands = pluginYml["commands"] as Map<String, Map<String, Any?>?>
    for (d in TransferDestination.entries) {
        check("/${d.command} in plugin.yml", commands.containsKey(d.command))
        check("/${d.command} permission", commands[d.command]?.get("permission") == d.permission)
        check("/${d.command} is NETWORK scope", GamemodeAssignments.COMMANDS[d.command] == GamemodeScope.NETWORK)
    }
    check("/hub is the /lobby alias", (commands["lobby"]?.get("aliases") as? List<*>)?.contains("hub") == true)
    val otherLabels = commands.filterKeys { TransferDestination.fromCommand(it) == null }.flatMap { (name, def) ->
        listOf(name) + ((def?.get("aliases") as? List<*>)?.map { it.toString() } ?: emptyList())
    }
    for (label in listOf("lobby", "hub", "survival", "lifesteal")) {
        check("/$label not used by another JoshyMC command", label !in otherLabels)
    }
    for (existing in listOf("spawn", "warp", "home", "rtp", "tpa", "back")) {
        check("/$existing still registered", commands.containsKey(existing))
    }
    @Suppress("UNCHECKED_CAST")
    val permissions = pluginYml["permissions"] as Map<String, Map<String, Any?>>
    for (d in TransferDestination.entries) {
        check("${d.permission} default true", permissions[d.permission]?.get("default") == true)
    }
    check("bypass is op only", permissions["joshymc.transfer.bypass-cooldown"]?.get("default") == "op")

    println("Transfer checks passed ($checks checks).")
}
