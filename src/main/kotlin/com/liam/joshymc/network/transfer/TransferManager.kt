package com.liam.joshymc.network.transfer

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-server transfers through a Velocity proxy (issue #1331): `/lobby` (`/hub`), `/survival`
 * and `/lifesteal`, and the entry point a future lobby selector will call ([request]).
 *
 * A transfer is a BungeeCord `Connect` plugin message sent through the player's own connection
 * (Velocity implements that channel), naming one of the configured `network.servers`. Nothing is
 * moved between servers: the player simply leaves this backend, so every quit handler (combat
 * log, vault/overflow saves, trade cancel, AH/Orders sessions) runs exactly as for a logout, and
 * the destination loads its own data. This manager never touches the database.
 *
 * Off unless `network.enabled` and `network.transfers.enabled` are both true; then the channel is
 * registered and requests are checked against [TransferRules] plus the gameplay blockers below.
 * Velocity gives no reply to `Connect`, so a transfer that hasn't disconnected the player after
 * `timeout-seconds` is reported as failed.
 */
class TransferManager(private val plugin: Joshymc) : Listener {

    @Volatile var settings: TransferSettings = TransferConfig.STANDALONE
        private set
    private val tracker = TransferTracker()
    private var channelRegistered = false
    /** null = paper-global.yml couldn't be read; false = Velocity forwarding is off there. */
    private var velocityForwarding: Boolean? = null
    @Volatile private var stopping = false
    /** One rejection log line per player per window, so spamming a command can't flood the console. */
    private val lastRejectionLog = ConcurrentHashMap<UUID, Long>()

    private val proxyReady: Boolean get() = channelRegistered && velocityForwarding != false

    /** Reads the settings and registers the channel when transfers are on. Safe to call again on reload. */
    fun start() {
        stopping = false
        try {
            val cfg = plugin.config
            val servers = (cfg.get("network.servers", null) as? ConfigurationSection)?.let { section ->
                section.getKeys(false).associateWith { section.get(it) }
            }
            val result = TransferConfig.resolve(
                cfg.get("network.enabled", null),
                cfg.get("network.proxy.type", null),
                cfg.get("network.transfers.enabled", null),
                cfg.get("network.transfers.cooldown-seconds", null),
                cfg.get("network.transfers.timeout-seconds", null),
                servers,
            )
            for (problem in result.problems) {
                if (problem.severe) plugin.logger.severe("[Network] ${problem.message}")
                else plugin.logger.warning("[Network] ${problem.message}")
            }
            settings = result.settings
        } catch (e: Exception) {
            settings = TransferConfig.STANDALONE
            plugin.logger.severe("[Network] Could not read the transfer settings; cross-server transfers are disabled: ${e.message}")
        }

        val id = plugin.networkIdentityManager.serverId
        val current = settings
        if (!current.networkEnabled) {
            // Standalone (the default): nothing registered, nothing logged beyond this line.
            unregisterChannel()
            plugin.logger.info("[Network] Network mode is off (network.enabled: false); cross-server transfers are disabled on '$id'.")
            return
        }

        velocityForwarding = readVelocityForwarding()
        if (!current.active) {
            unregisterChannel()
            plugin.logger.info("[Network] Network mode is on for '$id' but cross-server transfers are disabled.")
            return
        }

        registerChannel()
        val routes = TransferDestination.entries.joinToString { d ->
            val server = current.proxyServer(d)
            when {
                server == null -> "${d.id}=unavailable"
                TransferRules.isCurrentServer(d, server, id) -> "${d.id}=$server (this server)"
                else -> "${d.id}=$server"
            }
        }
        plugin.logger.info(
            "[Network] Cross-server transfers enabled on '$id' via ${current.proxy?.id} " +
                "(cooldown ${current.cooldownMillis / 1000}s, timeout ${current.timeoutMillis / 1000}s): $routes"
        )
        when (velocityForwarding) {
            false -> plugin.logger.severe(
                "[Network] Velocity modern forwarding is off in config/paper-global.yml (proxies.velocity.enabled); " +
                    "transfers are refused until this backend is set up behind the proxy. See docs/network-transfers.md."
            )
            null -> plugin.logger.warning(
                "[Network] Could not read proxies.velocity.enabled from config/paper-global.yml; make sure Velocity modern " +
                    "forwarding is on (docs/network-transfers.md)."
            )
            true -> {}
        }
        if (plugin.server.pluginManager.getPlugin("Geyser-Spigot") != null) {
            plugin.logger.warning(
                "[Network] Geyser-Spigot runs on this backend: Bedrock players who join through it bypass the proxy and " +
                    "can't be transferred. On the network, Geyser belongs on the proxy (docs/network-transfers.md)."
            )
        }
        reportCommandConflicts()
    }

    /** Ends pending transfers and unregisters the channel (reload and shutdown). */
    fun stop() {
        stopping = true
        val cleared = tracker.clearPending()
        if (cleared > 0) plugin.logger.info("[Network] Cleared $cleared pending transfer(s).")
        unregisterChannel()
    }

    // ── Requests ─────────────────────────────────────────

    /**
     * Sends [sender] to [destination] if every check passes, telling them why not otherwise.
     * Returns true when the transfer message was sent (not that it arrived).
     */
    fun request(sender: CommandSender, destination: TransferDestination): Boolean {
        val player = sender as? Player
        val current = settings
        val serverId = plugin.networkIdentityManager.serverId
        val now = System.nanoTime() / 1_000_000
        val context = TransferContext(
            isPlayer = player != null,
            hasPermission = sender.hasPermission(destination.permission),
            currentServerId = serverId,
            shuttingDown = stopping || plugin.server.isStopping,
            proxyReady = proxyReady,
            playerOnProxy = player != null && ProxyMessages.announcedByProxy(player.listeningPluginChannels),
            pending = player != null && tracker.isPending(player.uniqueId),
            cooldownRemainingMillis = player?.let {
                if (it.hasPermission(BYPASS_COOLDOWN_PERMISSION)) 0 else tracker.cooldownRemaining(it.uniqueId, now, current.cooldownMillis)
            } ?: 0,
            blockers = player?.let { blockers(it) } ?: emptyList(),
        )

        when (val decision = TransferRules.decide(current, destination, context)) {
            is TransferDecision.Refused -> {
                tell(sender, decision, destination)
                if (player != null) logRejection(player, destination, decision, now)
                return false
            }
            is TransferDecision.Allowed -> {
                val p = player ?: return false
                return send(p, decision, now)
            }
        }
    }

    private fun send(player: Player, decision: TransferDecision.Allowed, now: Long): Boolean {
        val pending = tracker.begin(player.uniqueId, decision.destination, now) ?: run {
            tell(player, TransferDecision.Refused(TransferRefusal.ALREADY_PENDING), decision.destination)
            return false
        }
        // Settle any open GUI on this server first (vaults, overflow, shops save on close), exactly as
        // they would on a logout, instead of while the proxy is already switching the player over.
        player.closeInventory()
        try {
            player.sendPluginMessage(plugin, ProxyMessages.CHANNEL, ProxyMessages.connect(decision.proxyServer))
        } catch (e: Exception) {
            tracker.cancel(player.uniqueId, pending.token)
            plugin.logger.warning("[Network] Could not send ${player.name} to '${decision.proxyServer}' (${decision.destination.id}): ${e.message}")
            Msg.error(player, "Couldn't reach the network right now. Try again in a moment.")
            return false
        }
        plugin.logger.info("[Network] Sending ${player.name} (${player.uniqueId}) from '${plugin.networkIdentityManager.serverId}' to '${decision.proxyServer}' (${decision.destination.id}).")
        Msg.reply(player, Component.text("Sending you to ${decision.destination.displayName}...", NamedTextColor.GRAY))

        val timeoutTicks = (settings.timeoutMillis / 50).coerceAtLeast(20)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val expired = tracker.expire(player.uniqueId, pending.token, System.nanoTime() / 1_000_000, 0) ?: return@Runnable
            if (!player.isOnline) return@Runnable
            plugin.logger.warning(
                "[Network] ${player.name} is still on '${plugin.networkIdentityManager.serverId}' ${timeoutTicks / 20}s after the transfer to " +
                    "'${decision.proxyServer}' (${expired.destination.id}); the proxy didn't move them (server down or not in velocity.toml?)."
            )
            Msg.error(player, "${expired.destination.displayName} isn't reachable right now. Try again later.")
        }, timeoutTicks)
        return true
    }

    /** Gameplay states that keep a player here, from the systems that own them. */
    private fun blockers(player: Player): List<TransferBlocker> {
        val list = mutableListOf<TransferBlocker>()
        if (player.isDead) list += TransferBlocker.DEAD
        if (safe { plugin.combatManager.isTagged(player) }) list += TransferBlocker.COMBAT_TAGGED
        // The event world keeps a separate inventory that's only restored on leaving it.
        if (safe { plugin.eventManager.isInEventWorld(player) }) list += TransferBlocker.IN_EVENT
        if (safe { plugin.adminManager.isFrozen(player.uniqueId) }) list += TransferBlocker.FROZEN
        if (safe { plugin.tradeManager.isTrading(player) }) list += TransferBlocker.TRADING
        return list
    }

    /** A check that throws (manager not started) blocks the transfer rather than letting it through. */
    private inline fun safe(check: () -> Boolean): Boolean = try { check() } catch (_: Exception) { true }

    private fun tell(sender: CommandSender, decision: TransferDecision.Refused, destination: TransferDestination) {
        when (decision.reason) {
            TransferRefusal.PLAYERS_ONLY -> Msg.playersOnly(sender)
            TransferRefusal.NO_PERMISSION -> Msg.noPermission(sender)
            TransferRefusal.NETWORK_DISABLED, TransferRefusal.TRANSFERS_DISABLED ->
                Msg.error(sender, "Server switching isn't available on this server.")
            TransferRefusal.PROXY_UNAVAILABLE -> Msg.error(sender, "Couldn't reach the network right now. Try again later.")
            TransferRefusal.NOT_ON_PROXY -> Msg.error(sender, "Server switching only works when you join through the network address.")
            TransferRefusal.DESTINATION_UNAVAILABLE -> Msg.error(sender, "${capitalized(destination)} isn't available right now.")
            TransferRefusal.ALREADY_CONNECTED -> Msg.error(sender, "You're already on ${destination.displayName}.")
            TransferRefusal.SHUTTING_DOWN -> Msg.error(sender, "The server is restarting; try again once it's back.")
            TransferRefusal.ALREADY_PENDING -> Msg.error(sender, "You're already being sent to another server.")
            TransferRefusal.COOLDOWN ->
                Msg.error(sender, "Please wait ${TransferRules.secondsLeft(decision.remainingMillis)}s before switching servers again.")
            TransferRefusal.BLOCKED -> Msg.error(sender, decision.blocker?.message ?: "You can't change servers right now.")
        }
    }

    private fun capitalized(destination: TransferDestination): String =
        destination.displayName.replaceFirstChar { it.uppercase() }

    private fun logRejection(player: Player, destination: TransferDestination, decision: TransferDecision.Refused, now: Long) {
        // Standalone servers answer quietly; the message to the player is enough.
        if (decision.reason == TransferRefusal.NETWORK_DISABLED || decision.reason == TransferRefusal.NO_PERMISSION) return
        val last = lastRejectionLog[player.uniqueId]
        if (last != null && now - last < REJECTION_LOG_WINDOW_MS) return
        lastRejectionLog[player.uniqueId] = now
        val detail = when (decision.reason) {
            TransferRefusal.COOLDOWN -> "cooldown, ${TransferRules.secondsLeft(decision.remainingMillis)}s left"
            TransferRefusal.BLOCKED -> decision.blocker?.name?.lowercase() ?: "blocked"
            else -> decision.reason.name.lowercase()
        }
        plugin.logger.info("[Network] Refused transfer of ${player.name} from '${plugin.networkIdentityManager.serverId}' to ${destination.id}: $detail.")
    }

    // ── Lifecycle helpers ────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        val pending = tracker.pendingFor(id)
        tracker.forget(id)
        lastRejectionLog.remove(id)
        if (pending != null) {
            plugin.logger.info("[Network] ${event.player.name} left '${plugin.networkIdentityManager.serverId}' for ${pending.destination.id}.")
        }
    }

    private fun registerChannel() {
        if (channelRegistered) return
        try {
            plugin.server.messenger.registerOutgoingPluginChannel(plugin, ProxyMessages.CHANNEL)
            channelRegistered = true
        } catch (e: Exception) {
            plugin.logger.severe("[Network] Could not register the ${ProxyMessages.CHANNEL} plugin channel; transfers are refused: ${e.message}")
        }
    }

    private fun unregisterChannel() {
        if (!channelRegistered) return
        try {
            plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, ProxyMessages.CHANNEL)
        } catch (e: Exception) {
            plugin.logger.warning("[Network] Could not unregister the ${ProxyMessages.CHANNEL} plugin channel: ${e.message}")
        }
        channelRegistered = false
    }

    /**
     * `proxies.velocity.enabled` from Paper's global config (server root `config/`), never the
     * secret. Null when the file can't be read (custom settings directory, unusual layout).
     */
    private fun readVelocityForwarding(): Boolean? = try {
        val file = File("config", "paper-global.yml")
        if (!file.isFile) null
        else YamlConfiguration.loadConfiguration(file).let { yml ->
            if (yml.isBoolean("proxies.velocity.enabled")) yml.getBoolean("proxies.velocity.enabled") else null
        }
    } catch (_: Exception) {
        null
    }

    /** Another plugin (a proxy-side or lobby plugin) may already own /lobby, /hub, /survival or /lifesteal. */
    private fun reportCommandConflicts() {
        try {
            val known = plugin.server.commandMap.knownCommands
            for (label in listOf("lobby", "hub", "survival", "lifesteal")) {
                val owner = known[label] ?: continue
                val ours = owner is org.bukkit.command.PluginCommand && owner.plugin == plugin
                if (!ours) {
                    plugin.logger.warning("[Network] /$label belongs to another plugin ($owner); use /joshymc:$label for JoshyMC's transfer.")
                }
            }
        } catch (e: Exception) {
            plugin.logger.fine("[Network] Could not check transfer command owners: ${e.message}")
        }
    }

    companion object {
        const val BYPASS_COOLDOWN_PERMISSION = "joshymc.transfer.bypass-cooldown"
        private const val REJECTION_LOG_WINDOW_MS = 10_000L
    }
}
