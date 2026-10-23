package com.liam.joshymc.gamemode

/**
 * Which gamemode each command belongs to (issue #1289). Commands are keyed by their
 * plugin.yml name (aliases and `joshymc:` forms resolve to it).
 *
 * New commands should be declared here: [GamemodeScope.SURVIVAL] / [GamemodeScope.LIFESTEAL]
 * for gamemode-specific ones, [GamemodeScope.NETWORK] for network-wide ones. Existing
 * commands are deliberately left unassigned so they keep working exactly as before.
 *
 * Features (listeners, GUIs, rewards) check `plugin.gamemodeLockManager.isAllowed(scope, world)`
 * with their own declared [GamemodeScope] instead.
 */
object GamemodeAssignments {

    val COMMANDS: Map<String, GamemodeScope> = mapOf(
        "jgamemode" to GamemodeScope.NETWORK,
    )
}
