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

    /** Reward presets (issue #1199): add, remove and clear the prizes per event type. A child of [HOST] and [ADMIN]. */
    const val REWARDS = "joshymc.event.rewards"

    /** Manual result changes before confirmation (issue #1199): set/add/remove winners and places. A child of [HOST] and [ADMIN]. */
    const val RESULT_OVERRIDE = "joshymc.event.result.override"

    /** Reward recovery (issue #1199): list failed payouts and retry them. A child of [ADMIN] only. */
    const val RECOVER = "joshymc.event.rewards.recover"

    /** Hosting statistics of other staff (issue #1199). A child of [ADMIN] only. */
    const val STATS_OTHERS = "joshymc.event.stats.others"

    /** [ADMIN] also passes, in case a permissions plugin doesn't apply plugin.yml children. */
    fun canHost(sender: CommandSender): Boolean = sender.hasPermission(HOST) || sender.hasPermission(ADMIN)

    /**
     * Arena setup. An explicit [ARENA] grant or negation wins (so a host can be kept out of
     * arena editing); otherwise hosts and admins have it, as plugin.yml's children say.
     */
    fun canSetupArenas(sender: CommandSender): Boolean =
        if (sender.isPermissionSet(ARENA)) sender.hasPermission(ARENA) else canHost(sender)

    /** Same rule as [canSetupArenas]: an explicit grant or negation wins, otherwise hosts have it. */
    fun canConfigureRewards(sender: CommandSender): Boolean =
        if (sender.isPermissionSet(REWARDS)) sender.hasPermission(REWARDS) else canHost(sender)

    fun canOverrideResults(sender: CommandSender): Boolean =
        if (sender.isPermissionSet(RESULT_OVERRIDE)) sender.hasPermission(RESULT_OVERRIDE) else canHost(sender)

    /** Never implied by hosting: only an explicit grant, [ADMIN], or the console. */
    fun canRecoverRewards(sender: CommandSender): Boolean =
        sender !is org.bukkit.entity.Player || sender.hasPermission(RECOVER) || sender.hasPermission(ADMIN)

    fun canViewOtherHosts(sender: CommandSender): Boolean =
        sender !is org.bukkit.entity.Player || sender.hasPermission(STATS_OTHERS) || sender.hasPermission(ADMIN)
}
