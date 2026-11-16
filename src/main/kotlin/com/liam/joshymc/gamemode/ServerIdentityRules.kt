package com.liam.joshymc.gamemode

/**
 * What `migrateConfig` writes for the three server identity keys when config.yml doesn't have
 * them (issue #1576), Bukkit-free.
 *
 * The generic backfill copies bundled values, and the bundled config is the Survival server's
 * (`server-gamemode: survival`, `server-id: survival`, `primary: true`). On a server whose config
 * names it `lifesteal` but lacks `gamemode-lock.server-gamemode` that wrote `survival` into the
 * file, so the Lifesteal server started (and kept starting) as Survival with nothing isolated.
 * These keys are therefore filled from what the file already says:
 *
 *  - `gamemode-lock.server-gamemode`: what [ServerGamemodeConfig.resolve] picks (the gamemode the
 *    server id names, else Survival as before).
 *  - `network.server-id`: that gamemode's id, never `survival` on a Lifesteal server.
 *  - `network.primary`: true only on a Survival server (the old default there); false elsewhere,
 *    so a Lifesteal server never becomes a second primary.
 *
 * Only missing keys are filled; a value the administrator set is never changed, even when it
 * looks wrong (those are reported at startup instead, see [com.liam.joshymc.network.NetworkIdentityConfig]).
 */
object ConfigIdentityBackfill {

    const val GAMEMODE_KEY = "gamemode-lock.server-gamemode"
    const val SERVER_ID_KEY = "network.server-id"
    const val PRIMARY_KEY = "network.primary"

    /** Never copied from the bundled config by the generic backfill. */
    val KEYS: Set<String> = setOf(GAMEMODE_KEY, SERVER_ID_KEY, PRIMARY_KEY)

    data class Fill(val key: String, val value: Any, val reason: String)

    /** Each argument is the value in config.yml, null when the key is missing (bundled defaults never count). */
    fun fills(rawGamemode: Any?, rawServerId: Any?, rawPrimary: Any?): List<Fill> {
        val gamemode = ServerGamemodeConfig.resolve(rawGamemode, rawServerId).gamemode
        val namedById = (rawServerId as? String)?.let { ServerGamemode.fromId(it) }
        val out = mutableListOf<Fill>()
        if (rawGamemode == null) {
            out += Fill(
                GAMEMODE_KEY, gamemode.id,
                if (namedById != null) "network.server-id is '${namedById.id}'" else "the default"
            )
        }
        if (rawServerId == null) {
            out += Fill(SERVER_ID_KEY, gamemode.id, "this server runs ${gamemode.id}")
        }
        if (rawPrimary == null) {
            val primary = gamemode == ServerGamemode.SURVIVAL
            out += Fill(
                PRIMARY_KEY, primary,
                if (primary) "the default for the Survival server" else "only the Survival server is primary by default"
            )
        }
        return out
    }
}

/**
 * What a `/jgamemode lock <gamemode>` actually changes on this server (issue #1576), Bukkit-free.
 * A lock only refuses [gamemode]'s commands and features in worlds running another gamemode
 * ([GamemodeIsolation.allowed]), so it does something only where such a world exists.
 */
object GamemodeLockEffect {

    enum class Effect {
        /** Some world runs another gamemode: there the lock refuses [gamemode]'s commands and features. */
        RESTRICTS,
        /** This dedicated server already isolates [gamemode] ([DedicatedServer]); the stored lock adds nothing. */
        AUTOMATIC,
        /** Every world here runs [gamemode] (usually the server's own gamemode): the lock changes nothing. */
        NO_EFFECT,
    }

    /** [hosted] = the server gamemode plus every `gamemode-lock.worlds` gamemode; [implied] = automatic isolation. */
    fun of(gamemode: ServerGamemode, hosted: Set<ServerGamemode>, implied: Set<ServerGamemode>): Effect = when {
        gamemode in implied -> Effect.AUTOMATIC
        (hosted - gamemode).isEmpty() -> Effect.NO_EFFECT
        else -> Effect.RESTRICTS
    }

    /**
     * The warning `/jgamemode lock|unlock` shows next to its preview and result, or null when the
     * command does what it says (a lock that restricts, an unlock that lifts the restriction).
     */
    fun note(gamemode: ServerGamemode, lock: Boolean, effect: Effect, serverGamemode: ServerGamemode): String? {
        val name = gamemode.displayName
        return when {
            effect == Effect.AUTOMATIC && lock ->
                "$name is already isolated automatically: this dedicated ${serverGamemode.displayName} server doesn't run it, " +
                    "so a $name lock changes nothing here."
            effect == Effect.AUTOMATIC ->
                "$name stays isolated anyway: this dedicated ${serverGamemode.displayName} server doesn't run it " +
                    "(gamemode-lock.server-gamemode / gamemode-lock.worlds in config.yml, restart to change)."
            effect == Effect.NO_EFFECT && lock ->
                "Locking $name has no effect here: every world on this server runs $name, so its commands and features keep " +
                    "working everywhere. A lock only restricts $name in worlds that run another gamemode."
            effect == Effect.NO_EFFECT ->
                "Unlocking $name changes nothing here either: every world on this server runs $name."
            else -> null
        }
    }
}
