package com.liam.joshymc.network

import com.liam.joshymc.gamemode.ServerGamemode

/**
 * Who this JoshyMC installation is on the network (issue #1323). Every Paper server runs its
 * own copy of the plugin with its own config and SQLite database; [serverId] names it and
 * [primary] says whether it runs the network-wide jobs (see [NetworkIdentityManager]).
 */
data class NetworkIdentity(val serverId: String, val primary: Boolean)

/** Reads and validates `network.*`, Bukkit-free. */
object NetworkIdentityConfig {

    const val MAX_ID_LENGTH = 32

    /** Lower-case letters and digits, with `-`/`_` inside: `survival`, `lifesteal`, `lobby`, `survival-2`. */
    private val SERVER_ID = Regex("^[a-z0-9](?:[a-z0-9_-]{0,${MAX_ID_LENGTH - 2}}[a-z0-9])?$")

    data class Problem(val severe: Boolean, val message: String)

    data class Result(val identity: NetworkIdentity, val problems: List<Problem>)

    /** Trimmed and lower-cased, or null when that still isn't a valid server id. */
    fun normalize(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { SERVER_ID.matches(it) }

    /**
     * [rawServerId]/[rawPrimary] are the values set in config.yml (null when the key is
     * missing; bundled defaults never count). [gamemode] is the server's
     * `gamemode-lock.server-gamemode`.
     *
     *  - A missing or invalid server id falls back to the gamemode's id, never to a random one.
     *  - A missing or non-boolean primary flag means secondary, so a broken config can't start
     *    a second copy of the network-wide jobs.
     *  - A server id that names a different gamemode (e.g. `survival` on a Lifesteal server,
     *    the usual sign of a copied config) is also refused primary for the same reason.
     *    Ids that aren't a gamemode (`lobby`, ...) are fine on any gamemode.
     */
    fun resolve(rawServerId: Any?, rawPrimary: Any?, gamemode: ServerGamemode): Result {
        val problems = mutableListOf<Problem>()

        val serverId = when {
            rawServerId == null || (rawServerId is String && rawServerId.isBlank()) -> {
                problems += Problem(false, "network.server-id is not set; using '${gamemode.id}' (the server gamemode). Set it in config.yml.")
                gamemode.id
            }
            else -> normalize(rawServerId as? String) ?: run {
                problems += Problem(
                    true,
                    "network.server-id '$rawServerId' is not a valid server id (1-$MAX_ID_LENGTH lower-case letters, digits, '-' or '_', " +
                        "starting and ending with a letter or digit); using '${gamemode.id}' (the server gamemode)."
                )
                gamemode.id
            }
        }

        var primary = when (rawPrimary) {
            is Boolean -> rawPrimary
            null -> {
                problems += Problem(true, "network.primary is not set; running as a secondary instance. Set it to true on exactly one server.")
                false
            }
            else -> {
                problems += Problem(true, "network.primary '$rawPrimary' is not true or false; running as a secondary instance.")
                false
            }
        }

        val namedGamemode = ServerGamemode.fromId(serverId)
        if (namedGamemode != null && namedGamemode != gamemode) {
            problems += Problem(
                true,
                "network.server-id '$serverId' is the id of ${namedGamemode.displayName}, but gamemode-lock.server-gamemode is " +
                    "${gamemode.id}. Was config.yml copied from another server? Fix one of them" +
                    (if (primary) "; until then this server runs as a secondary instance." else ".")
            )
            primary = false
        }

        return Result(NetworkIdentity(serverId, primary), problems)
    }
}
