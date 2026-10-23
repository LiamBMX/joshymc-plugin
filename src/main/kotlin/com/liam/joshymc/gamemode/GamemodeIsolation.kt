package com.liam.joshymc.gamemode

/**
 * The JoshyMC network gamemodes (issue #1289). Not Bukkit's [org.bukkit.GameMode]:
 * this is which game a server (or world) runs, Survival or Lifesteal.
 */
enum class ServerGamemode(val id: String, val displayName: String) {
    SURVIVAL("survival", "Survival"),
    LIFESTEAL("lifesteal", "Lifesteal");

    companion object {
        fun fromId(id: String?): ServerGamemode? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/**
 * Where a command or feature belongs. Every new command/feature should declare one
 * (see [GamemodeAssignments]); anything never assigned keeps its old behavior.
 */
enum class GamemodeScope(val id: String, val gamemode: ServerGamemode?) {
    /** Works everywhere, never affected by a lock. */
    NETWORK("network", null),
    SURVIVAL("survival", ServerGamemode.SURVIVAL),
    LIFESTEAL("lifesteal", ServerGamemode.LIFESTEAL);

    companion object {
        fun fromId(id: String?): GamemodeScope? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/** The isolation rule, Bukkit-free. */
object GamemodeIsolation {

    /**
     * A [scope] is usable in [current] unless its gamemode is locked and [current] is a
     * different gamemode. A lock never restricts its own gamemode, and unlocked gamemodes
     * and [GamemodeScope.NETWORK] are never restricted.
     */
    fun allowed(scope: GamemodeScope, current: ServerGamemode, locked: Set<ServerGamemode>): Boolean {
        val owner = scope.gamemode ?: return true
        return owner !in locked || owner == current
    }
}
