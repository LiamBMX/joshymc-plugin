package com.liam.joshymc.network.transfer

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

/** Gameplay states that keep a player on this server (issue #1331), checked by the manager. */
enum class TransferBlocker(val message: String) {
    DEAD("You can't change servers while dead."),
    COMBAT_TAGGED("You can't change servers while in combat."),
    IN_EVENT("Leave the event first (/event leave or /spawn)."),
    FROZEN("You can't change servers while frozen."),
    TRADING("Finish or cancel your trade first."),
}

enum class TransferRefusal {
    PLAYERS_ONLY,
    NO_PERMISSION,
    NETWORK_DISABLED,
    TRANSFERS_DISABLED,
    PROXY_UNAVAILABLE,
    NOT_ON_PROXY,
    DESTINATION_UNAVAILABLE,
    ALREADY_CONNECTED,
    SHUTTING_DOWN,
    ALREADY_PENDING,
    COOLDOWN,
    BLOCKED,
}

sealed class TransferDecision {
    /** Send the player to the proxy server [proxyServer]. */
    data class Allowed(val destination: TransferDestination, val proxyServer: String) : TransferDecision()

    /** [remainingMillis] is set for [TransferRefusal.COOLDOWN], [blocker] for [TransferRefusal.BLOCKED]. */
    data class Refused(
        val reason: TransferRefusal,
        val remainingMillis: Long = 0,
        val blocker: TransferBlocker? = null,
    ) : TransferDecision()
}

/** What the manager knows about the request when it asks [TransferRules.decide]. */
data class TransferContext(
    val isPlayer: Boolean,
    val hasPermission: Boolean,
    /** `network.server-id` of this server. */
    val currentServerId: String,
    val shuttingDown: Boolean,
    /** The plugin-message channel is registered and the backend isn't known to be off the proxy. */
    val proxyReady: Boolean,
    /**
     * The player's connection lists the `bungeecord:main` channel, which Velocity registers on
     * every backend connection it makes. False for direct joins and Bedrock players joining
     * through a backend Geyser: a `Connect` message would go nowhere.
     */
    val playerOnProxy: Boolean,
    val pending: Boolean,
    val cooldownRemainingMillis: Long,
    val blockers: List<TransferBlocker>,
)

/** The transfer decision, Bukkit-free and in a fixed order so the first problem is the one reported. */
object TransferRules {

    fun decide(settings: TransferSettings, destination: TransferDestination, context: TransferContext): TransferDecision {
        fun refuse(reason: TransferRefusal) = TransferDecision.Refused(reason)

        if (!context.isPlayer) return refuse(TransferRefusal.PLAYERS_ONLY)
        if (!context.hasPermission) return refuse(TransferRefusal.NO_PERMISSION)
        if (!settings.networkEnabled) return refuse(TransferRefusal.NETWORK_DISABLED)
        if (!settings.transfersEnabled) return refuse(TransferRefusal.TRANSFERS_DISABLED)
        if (settings.proxy == null || !context.proxyReady) return refuse(TransferRefusal.PROXY_UNAVAILABLE)
        if (!context.playerOnProxy) return refuse(TransferRefusal.NOT_ON_PROXY)
        val proxyServer = settings.proxyServer(destination) ?: return refuse(TransferRefusal.DESTINATION_UNAVAILABLE)
        if (isCurrentServer(destination, proxyServer, context.currentServerId)) return refuse(TransferRefusal.ALREADY_CONNECTED)
        if (context.shuttingDown) return refuse(TransferRefusal.SHUTTING_DOWN)
        if (context.pending) return refuse(TransferRefusal.ALREADY_PENDING)
        if (context.cooldownRemainingMillis > 0) {
            return TransferDecision.Refused(TransferRefusal.COOLDOWN, remainingMillis = context.cooldownRemainingMillis)
        }
        context.blockers.firstOrNull()?.let { return TransferDecision.Refused(TransferRefusal.BLOCKED, blocker = it) }
        return TransferDecision.Allowed(destination, proxyServer)
    }

    /**
     * True when [destination] is this server: `network.server-id` equals the destination id
     * (`survival` on Survival) or its proxy server name (`smp` when velocity.toml calls it that).
     */
    fun isCurrentServer(destination: TransferDestination, proxyServer: String?, currentServerId: String): Boolean =
        destination.id.equals(currentServerId, ignoreCase = true) ||
            (proxyServer != null && proxyServer.equals(currentServerId, ignoreCase = true))

    /** Whole seconds left, rounded up, for "wait Ns" messages. */
    fun secondsLeft(millis: Long): Long = (millis + 999) / 1000
}

/**
 * Pending transfers and cooldowns per player, with the clock passed in (monotonic millis).
 * One pending transfer per player at a time; the cooldown starts when a transfer is sent.
 */
class TransferTracker {

    data class Pending(val token: Long, val destination: TransferDestination, val startedAt: Long)

    private val pending = HashMap<UUID, Pending>()
    private val lastSent = HashMap<UUID, Long>()
    private var nextToken = 1L

    @Synchronized
    fun isPending(player: UUID): Boolean = pending.containsKey(player)

    @Synchronized
    fun pendingFor(player: UUID): Pending? = pending[player]

    @Synchronized
    fun cooldownRemaining(player: UUID, now: Long, cooldownMillis: Long): Long {
        val sent = lastSent[player] ?: return 0
        return (sent + cooldownMillis - now).coerceAtLeast(0)
    }

    /** Marks a transfer as sent; null when one is already pending (the duplicate is dropped). */
    @Synchronized
    fun begin(player: UUID, destination: TransferDestination, now: Long): Pending? {
        if (pending.containsKey(player)) return null
        val entry = Pending(nextToken++, destination, now)
        pending[player] = entry
        lastSent[player] = now
        return entry
    }

    /** Ends the pending transfer [token] (or any, when null); returns what was ended. */
    @Synchronized
    fun cancel(player: UUID, token: Long? = null): Pending? {
        val current = pending[player] ?: return null
        if (token != null && current.token != token) return null
        pending.remove(player)
        return current
    }

    /** The pending transfer [token] if it is still pending and older than [timeoutMillis]; it is ended. */
    @Synchronized
    fun expire(player: UUID, token: Long, now: Long, timeoutMillis: Long): Pending? {
        val current = pending[player] ?: return null
        if (current.token != token || now - current.startedAt < timeoutMillis) return null
        pending.remove(player)
        return current
    }

    /** The player left this server: nothing about them is kept. */
    @Synchronized
    fun forget(player: UUID) {
        pending.remove(player)
        lastSent.remove(player)
    }

    /** Every pending transfer is ended (shutdown/reload); returns how many there were. */
    @Synchronized
    fun clearPending(): Int {
        val count = pending.size
        pending.clear()
        return count
    }

    @Synchronized
    fun pendingCount(): Int = pending.size
}

/**
 * The BungeeCord plugin-message format that Velocity implements (`bungee-plugin-message-channel`,
 * on by default): `Connect` + target server name, written as Java modified-UTF strings.
 */
object ProxyMessages {
    /** Bukkit maps the legacy `BungeeCord` name to `bungeecord:main`; Velocity listens on both. */
    const val CHANNEL = "BungeeCord"
    const val NAMESPACED_CHANNEL = "bungeecord:main"

    /** Whether a player's registered channels show they're connected through the proxy. */
    fun announcedByProxy(channels: Collection<String>): Boolean =
        channels.any { it.equals(NAMESPACED_CHANNEL, ignoreCase = true) || it.equals(CHANNEL, ignoreCase = true) }

    fun connect(proxyServer: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeUTF("Connect")
            it.writeUTF(proxyServer)
        }
        return bytes.toByteArray()
    }
}
