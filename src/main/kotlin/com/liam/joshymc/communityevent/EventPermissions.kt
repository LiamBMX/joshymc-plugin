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

    /** [ADMIN] also passes, in case a permissions plugin doesn't apply plugin.yml children. */
    fun canHost(sender: CommandSender): Boolean = sender.hasPermission(HOST) || sender.hasPermission(ADMIN)
}
