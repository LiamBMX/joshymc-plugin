package com.liam.joshymc.communityevent

import org.bukkit.command.CommandSender

/** Permission nodes for hosted events (issue #1180). Declared in plugin.yml. */
object EventPermissions {
    /** Full event-world admin: zone wand, pvp/build/break toggles, automatic mini-games, world bypass. Implies [HOST]. */
    const val ADMIN = "joshymc.event"

    /** Community Managers: the Event Manager GUI and create/open/start/stop/reset/status. Nothing else. */
    const val HOST = "joshymc.event.host"

    /** Players: /event join and /event leave. */
    const val JOIN = "joshymc.event.join"

    /** Arena setup (issue #1183): create, edit and delete saved arenas. A child of [HOST] and [ADMIN] in plugin.yml. */
    const val ARENA = "joshymc.event.arena"

    /** [ADMIN] also passes, in case a permissions plugin doesn't apply plugin.yml children. */
    fun canHost(sender: CommandSender): Boolean = sender.hasPermission(HOST) || sender.hasPermission(ADMIN)

    /**
     * Arena setup. An explicit [ARENA] grant or negation wins (so a host can be kept out of
     * arena editing); otherwise hosts and admins have it, as plugin.yml's children say.
     */
    fun canSetupArenas(sender: CommandSender): Boolean =
        if (sender.isPermissionSet(ARENA)) sender.hasPermission(ARENA) else canHost(sender)
}
