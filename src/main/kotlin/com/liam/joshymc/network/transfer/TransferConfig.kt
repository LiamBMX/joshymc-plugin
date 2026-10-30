package com.liam.joshymc.network.transfer

/**
 * The places a player can be sent on the network (issue #1331). Every transfer goes to one of
 * these; the proxy server each one maps to comes from `network.servers.<id>` and nothing a
 * player types ever becomes a server name.
 */
enum class TransferDestination(val id: String, val displayName: String, val command: String) {
    LOBBY("lobby", "the Lobby", "lobby"),
    SURVIVAL("survival", "Survival SMP", "survival"),
    LIFESTEAL("lifesteal", "Lifesteal SMP", "lifesteal");

    /** `joshymc.transfer.<id>`, default true. */
    val permission: String get() = "joshymc.transfer.$id"

    companion object {
        fun fromId(id: String?): TransferDestination? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }

        /** The destination a command name (plugin.yml name, not alias) sends to. */
        fun fromCommand(name: String?): TransferDestination? =
            entries.firstOrNull { it.command.equals(name?.trim(), ignoreCase = true) }
    }
}

/** Proxy software JoshyMC can hand transfers to. Only Velocity is supported. */
enum class ProxyType(val id: String) {
    VELOCITY("velocity");

    companion object {
        fun fromId(id: String?): ProxyType? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/**
 * The resolved `network.*` transfer settings. Everything defaults to off: with the bundled
 * config ([TransferConfig.STANDALONE]) no channel is registered and no transfer is attempted.
 *
 * [servers] only holds destinations with a valid, unique proxy server name; a missing entry
 * means that destination is unavailable.
 */
data class TransferSettings(
    val networkEnabled: Boolean,
    val proxy: ProxyType?,
    val transfersEnabled: Boolean,
    val cooldownMillis: Long,
    val timeoutMillis: Long,
    val servers: Map<TransferDestination, String>,
) {
    /** Whether the plugin-message channel should be registered and transfers may be sent at all. */
    val active: Boolean get() = networkEnabled && proxy != null && transfersEnabled

    fun proxyServer(destination: TransferDestination): String? = servers[destination]
}

/** Reads and validates the transfer part of `network.*`, Bukkit-free. */
object TransferConfig {

    const val DEFAULT_COOLDOWN_SECONDS = 5L
    const val MAX_COOLDOWN_SECONDS = 3600L
    const val DEFAULT_TIMEOUT_SECONDS = 10L
    const val MIN_TIMEOUT_SECONDS = 3L
    const val MAX_TIMEOUT_SECONDS = 60L
    const val MAX_SERVER_NAME_LENGTH = 64

    /**
     * Proxy server names as written in velocity.toml's `[servers]`: letters, digits, `-`, `_`.
     * No dots or colons, so an address or hostname can never be used as a destination.
     */
    private val SERVER_NAME = Regex("^[A-Za-z0-9_-]{1,$MAX_SERVER_NAME_LENGTH}$")

    val STANDALONE = TransferSettings(
        networkEnabled = false,
        proxy = ProxyType.VELOCITY,
        transfersEnabled = false,
        cooldownMillis = DEFAULT_COOLDOWN_SECONDS * 1000,
        timeoutMillis = DEFAULT_TIMEOUT_SECONDS * 1000,
        servers = emptyMap(),
    )

    data class Problem(val severe: Boolean, val message: String)

    data class Result(val settings: TransferSettings, val problems: List<Problem>)

    /** The trimmed name when it is a valid proxy server name, else null. */
    fun normalizeServerName(raw: String?): String? = raw?.trim()?.takeIf { SERVER_NAME.matches(it) }

    /**
     * Values as read from config.yml (null when the key is missing). [rawServers] is the
     * `network.servers` section as a map, or null when it's missing or not a section.
     *
     *  - `enabled`/`transfers.enabled` are only on when they are literally `true`.
     *  - An unknown `proxy.type` turns transfers off (unsupported environment).
     *  - A server name that is blank is "not configured" (normal for a gamemode that isn't
     *    deployed yet); one that isn't a valid proxy server name, or that two destinations share,
     *    makes those destinations unavailable. Nothing ever falls back to another destination.
     *  - Unknown keys under `servers` are reported and ignored: only the three destinations exist.
     */
    fun resolve(
        rawNetworkEnabled: Any?,
        rawProxyType: Any?,
        rawTransfersEnabled: Any?,
        rawCooldownSeconds: Any?,
        rawTimeoutSeconds: Any?,
        rawServers: Map<String, Any?>?,
    ): Result {
        val problems = mutableListOf<Problem>()

        fun flag(raw: Any?, key: String): Boolean = when (raw) {
            null -> false
            is Boolean -> raw
            else -> {
                problems += Problem(true, "$key '$raw' is not true or false; treating it as false.")
                false
            }
        }

        val networkEnabled = flag(rawNetworkEnabled, "network.enabled")
        val transfersEnabled = flag(rawTransfersEnabled, "network.transfers.enabled")

        val proxy = when (rawProxyType) {
            null -> ProxyType.VELOCITY
            else -> ProxyType.fromId(rawProxyType as? String) ?: run {
                problems += Problem(
                    true,
                    "network.proxy.type '$rawProxyType' is not supported (only 'velocity'); cross-server transfers are disabled."
                )
                null
            }
        }

        fun seconds(raw: Any?, key: String, default: Long, min: Long, max: Long): Long {
            if (raw == null) return default
            val value = when (raw) {
                is Int, is Long, is Short, is Byte -> (raw as Number).toLong()
                else -> null
            }
            if (value == null) {
                problems += Problem(false, "$key '$raw' is not a whole number of seconds; using $default.")
                return default
            }
            if (value < min || value > max) {
                val clamped = value.coerceIn(min, max)
                problems += Problem(false, "$key $value is outside $min-$max; using $clamped.")
                return clamped
            }
            return value
        }

        val cooldown = seconds(rawCooldownSeconds, "network.transfers.cooldown-seconds", DEFAULT_COOLDOWN_SECONDS, 0, MAX_COOLDOWN_SECONDS)
        val timeout = seconds(rawTimeoutSeconds, "network.transfers.timeout-seconds", DEFAULT_TIMEOUT_SECONDS, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)

        val named = linkedMapOf<TransferDestination, String>()
        if (rawServers == null) {
            if (networkEnabled) problems += Problem(true, "network.servers is missing; no destination is available.")
        } else {
            for ((key, raw) in rawServers) {
                val destination = TransferDestination.fromId(key)
                if (destination == null) {
                    problems += Problem(
                        false,
                        "network.servers.$key is not a destination (only ${TransferDestination.entries.joinToString { it.id }}); ignored."
                    )
                    continue
                }
                if (raw == null || (raw is String && raw.isBlank())) continue
                val name = normalizeServerName(raw as? String)
                if (name == null) {
                    problems += Problem(
                        true,
                        "network.servers.${destination.id} '$raw' is not a proxy server name (1-$MAX_SERVER_NAME_LENGTH letters, " +
                            "digits, '-' or '_'; never an address); ${destination.displayName} is unavailable."
                    )
                    continue
                }
                named[destination] = name
            }
        }

        // Two destinations on one proxy server would send players somewhere they didn't pick.
        val servers = linkedMapOf<TransferDestination, String>()
        for ((destination, name) in named) {
            val sharing = named.filter { it.value.equals(name, ignoreCase = true) }.keys
            if (sharing.size > 1) {
                if (destination == sharing.first()) {
                    problems += Problem(
                        true,
                        "network.servers: ${sharing.joinToString(" and ") { it.id }} all point at '$name'; those destinations are unavailable."
                    )
                }
                continue
            }
            servers[destination] = name
        }

        if (transfersEnabled && !networkEnabled) {
            problems += Problem(false, "network.transfers.enabled is true but network.enabled is false; no transfers are sent.")
        }
        if (networkEnabled && transfersEnabled && proxy != null && servers.isEmpty()) {
            problems += Problem(true, "Cross-server transfers are enabled but no destination is configured under network.servers.")
        }

        val settings = TransferSettings(
            networkEnabled = networkEnabled,
            proxy = proxy,
            transfersEnabled = transfersEnabled,
            cooldownMillis = cooldown * 1000,
            timeoutMillis = timeout * 1000,
            servers = servers,
        )
        return Result(settings, problems)
    }
}
