package com.liam.joshymc.gamemode

/**
 * Which game this instance runs, from `gamemode-lock.server-gamemode` (issue #1336), Bukkit-free.
 * [com.liam.joshymc.network.NetworkIdentityManager] resolves it once per config load and every
 * other system (the gamemode locks too) reads that one value.
 */
object ServerGamemodeConfig {

    data class Result(
        val gamemode: ServerGamemode,
        /** False when the configured value was not a gamemode and [gamemode] is a fallback. */
        val valid: Boolean,
        /** Severe problems for the console; empty when the value was fine (or left unset). */
        val problems: List<String>,
    )

    /**
     * [rawGamemode] is the value set in config.yml (null when missing), [rawServerId] the
     * `network.server-id` value.
     *
     *  - Missing or blank: Survival, the setting's original default, so old configs are unchanged.
     *  - A gamemode id (any case, trimmed): that gamemode.
     *  - Anything else is an error. When the server id names a gamemode (`lifesteal`) that one
     *    is used, so a Lifesteal server with a typo in this setting never starts as Survival;
     *    otherwise Survival (the old fallback), logged as severe and shown by `/jgamemode info`.
     */
    fun resolve(rawGamemode: Any?, rawServerId: Any?): Result {
        if (rawGamemode == null || (rawGamemode is String && rawGamemode.isBlank())) {
            return Result(ServerGamemode.SURVIVAL, valid = true, problems = emptyList())
        }
        val named = (rawGamemode as? String)?.let { ServerGamemode.fromId(it) }
        if (named != null) return Result(named, valid = true, problems = emptyList())

        val choices = ServerGamemode.entries.joinToString(" or ") { it.id }
        val fromId = (rawServerId as? String)?.let { ServerGamemode.fromId(it) }
        return if (fromId != null) {
            Result(
                fromId, valid = false,
                problems = listOf(
                    "gamemode-lock.server-gamemode '$rawGamemode' is not a gamemode ($choices); using ${fromId.id} because " +
                        "network.server-id is '${fromId.id}'. Fix gamemode-lock.server-gamemode in config.yml."
                )
            )
        } else {
            Result(
                ServerGamemode.SURVIVAL, valid = false,
                problems = listOf(
                    "gamemode-lock.server-gamemode '$rawGamemode' is not a gamemode ($choices); falling back to survival. " +
                        "If this is the Lifesteal server, set it to lifesteal in config.yml and restart."
                )
            )
        }
    }

    /** The `gamemode-lock.worlds` values that are gamemodes; the others are ignored (and logged by the lock manager). */
    fun worldGamemodes(rawValues: Collection<Any?>): Set<ServerGamemode> =
        rawValues.mapNotNull { raw -> (raw as? String)?.let { ServerGamemode.fromId(it) } }.toSet()
}

/**
 * Dedicated servers (issue #1336). Survival and Lifesteal are separate Paper instances. On an
 * instance whose server gamemode isn't Survival, every gamemode it runs in none of its worlds is
 * isolated automatically, as if `/jgamemode lock <it> confirm` had been run (and that can't be
 * undone with unlock), and its startup-only parts ([DedicatedServerFeatures]) never start.
 *
 * A Survival server (the default) never isolates anything automatically, so every existing
 * Survival install behaves exactly as before. A Lifesteal server that maps some worlds to
 * Survival (`gamemode-lock.worlds`) hosts Survival too, so nothing is isolated there either.
 */
object DedicatedServer {

    fun isolatedGamemodes(serverGamemode: ServerGamemode, worldGamemodes: Collection<ServerGamemode>): Set<ServerGamemode> {
        if (serverGamemode == ServerGamemode.SURVIVAL) return emptySet()
        val hosted = worldGamemodes.toSet() + serverGamemode
        return ServerGamemode.entries.filterTo(LinkedHashSet()) { it !in hosted }
    }
}

/**
 * `config.yml > features.*` flags that only start Survival gameplay (issue #1336). On a dedicated
 * server that isolates Survival ([DedicatedServer]) they count as off whatever config.yml says, so
 * those managers never start their timers, worlds or announcements there. Each matches SURVIVAL
 * commands of [GamemodeAssignments]. Flags not listed here behave exactly as configured.
 */
object DedicatedServerFeatures {

    /** Flag → what it starts (shown by `/jgamemode info` and the docs). */
    val SURVIVAL_ONLY: Map<String, String> = linkedMapOf(
        "talismans" to "/talisman",
        "custom-fishing" to "/fish and the custom fish collection",
        "quests" to "/quests (Daily, Weekly, Quest Master)",
        "event-quests" to "/eventquests",
        "market" to "/market and its price drift timer",
        "kill-effects" to "/killeffect",
        "resource-world" to "/resource and the regenerating resource world",
        "voting" to "/jvote and the Votifier listener",
        "arenas" to "/arena and the PvP arena zones",
        "coinflip" to "/coinflip and /ccoinflip",
        "kill-streaks" to "/killstreak",
        "login-streaks" to "/loginstreak and its rewards",
        "orders" to "/orders",
        "giveaways" to "/giveaway",
        "gift" to "/gift",
        "casino" to "/casino, /mines, /roulette, /crash and /towers",
        "candy-shop" to "/candy and its openings",
        "event-shop" to "/eventshop and its windows",
    )

    /** Whether `features.<flag>` is on, given its configured value and the gamemodes this instance isolates. */
    fun enabled(flag: String, configured: Boolean, isolated: Set<ServerGamemode>): Boolean =
        configured && !(ServerGamemode.SURVIVAL in isolated && flag in SURVIVAL_ONLY)

    /** Survival-only startup work outside the feature flags: creating/loading the `pvp` and `dungeon` worlds. */
    fun survivalWorldsAllowed(isolated: Set<ServerGamemode>): Boolean = ServerGamemode.SURVIVAL !in isolated
}
