package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
import com.liam.joshymc.vanish.VanishRules
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.metadata.FixedMetadataValue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class VanishCommand(private val plugin: Joshymc) : CommandExecutor, Listener, TabCompleter {

    companion object {
        const val SILENT_JOIN_PERMISSION = "joshymc.silentjoin"
        /** Hidden setting owned by `/sj`: whether silent-join staff see other silent joins/leaves. */
        const val SILENT_JOIN_NOTIFY_SETTING_KEY = "silentjoin_notify"
        /** Lets `/sj <on|off> <public|private>` change the sender's own join visibility. */
        const val SILENT_JOIN_VISIBILITY_PERMISSION = "joshymc.silentjoin.visibility"
        /** Hidden setting owned by `/sj`: true = own join/leave is private (the default). */
        const val SILENT_JOIN_PRIVATE_SETTING_KEY = "silentjoin_private"
    }

    private val vanished: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private var actionBarTaskId: Int = -1

    fun isVanished(player: Player): Boolean = vanished.contains(player.uniqueId)

    /**
     * The one check for whether [player]'s join/leave stays out of public chat
     * and Discord: vanished, or `joshymc.silentjoin` staff whose join visibility
     * is private (the default; `/sj ... public` makes it public).
     */
    fun hidesJoinLeave(player: Player): Boolean =
        isVanished(player) || (player.hasPermission(SILENT_JOIN_PERMISSION) &&
            plugin.settingsManager.getSetting(player, SILENT_JOIN_PRIVATE_SETTING_KEY))

    /** Staff with `joshymc.vanish` see vanished players (unchanged rule). */
    fun canSeeVanished(viewer: Player): Boolean = viewer.hasPermission(VanishRules.PERMISSION)

    /**
     * Whether [viewer] must not see [target], combining vanish with the viewer's
     * `/hidestaff` toggle so neither reason clobbers the other (issue #1467).
     */
    fun shouldHide(viewer: Player, target: Player): Boolean = VanishRules.hides(
        viewerIsTarget = viewer.uniqueId == target.uniqueId,
        targetVanished = isVanished(target),
        viewerSeesVanished = canSeeVanished(viewer),
        hiddenByHideStaff = plugin.hideStaffManager.hidesModModeStaff(viewer, target),
    )

    /**
     * Applies [shouldHide] for one pair. Paper's hidePlayer also removes the target's TAB
     * entry for that viewer, and the server's own player-list updates (joins, display names,
     * game modes) skip viewers that can't see the player, so they never bring it back.
     */
    fun applyVisibility(viewer: Player, target: Player) {
        if (viewer.uniqueId == target.uniqueId) return
        if (shouldHide(viewer, target)) viewer.hidePlayer(plugin, target) else viewer.showPlayer(plugin, target)
    }

    /** Re-syncs every online viewer's view of [target], and its "vanished" metadata. */
    fun resync(target: Player) {
        for (viewer in Bukkit.getOnlinePlayers()) applyVisibility(viewer, target)
        markMetadata(target)
    }

    /** Re-syncs what [viewer] sees of every vanished online player. */
    private fun resyncViewer(viewer: Player) {
        for (uuid in vanished) {
            val target = Bukkit.getPlayer(uuid) ?: continue
            applyVisibility(viewer, target)
        }
    }

    /**
     * The de-facto "vanished" metadata flag that TAB / TAB-Bridge and other plugins read, so a
     * separate TAB plugin (or a proxy-wide player list) also leaves vanished staff out.
     */
    private fun markMetadata(player: Player) {
        if (isVanished(player)) {
            player.setMetadata(VanishRules.METADATA_KEY, FixedMetadataValue(plugin, true))
        } else {
            player.removeMetadata(VanishRules.METADATA_KEY, plugin)
        }
    }

    fun start() {
        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS vanished_players (
                uuid TEXT PRIMARY KEY,
                since INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // Vanish survives /joshymc reload (which builds a new VanishCommand) and restarts.
        vanished.clear()
        plugin.databaseManager.query("SELECT uuid FROM vanished_players") { rs -> rs.getString("uuid") }
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            .forEach { vanished.add(it) }
        for (online in Bukkit.getOnlinePlayers()) {
            markMetadata(online)
            resyncViewer(online)
        }

        // Every 2 seconds: the action bar reminder, plus a visibility re-sync so a viewer's
        // permission change (LuckPerms) can never leave a vanished player shown.
        actionBarTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            val message = plugin.commsManager.parseLegacy("&c&lVANISHED")
            for (uuid in vanished) {
                val player = Bukkit.getPlayer(uuid) ?: continue
                player.sendActionBar(message)
                resync(player)
            }
        }, 40L, 40L)
    }

    fun stop() {
        if (actionBarTaskId != -1) {
            plugin.server.scheduler.cancelTask(actionBarTaskId)
            actionBarTaskId = -1
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }

        if (!sender.hasPermission(VanishRules.PERMISSION)) {
            Msg.noPermission(sender, CommunicationsManager.Category.ADMIN)
            return true
        }

        val target: Player = if (args.isNotEmpty()) {
            val found = Bukkit.getPlayer(args[0])
            if (found == null) {
                Msg.playerNotFound(sender, category = CommunicationsManager.Category.ADMIN)
                return true
            }
            found
        } else {
            sender
        }

        if (vanished.contains(target.uniqueId)) {
            unvanish(target)
            plugin.commsManager.send(sender, Component.text("${target.name} is now ", NamedTextColor.GRAY)
                .append(Component.text("visible", NamedTextColor.GREEN)), CommunicationsManager.Category.ADMIN)
        } else {
            vanish(target)
            plugin.commsManager.send(sender, Component.text("${target.name} is now ", NamedTextColor.GRAY)
                .append(Component.text("vanished", NamedTextColor.RED)), CommunicationsManager.Category.ADMIN)
        }

        return true
    }

    /** Public so other systems (e.g. Moderator Mode) can vanish/unvanish a player programmatically. */
    fun vanish(player: Player) {
        vanished.add(player.uniqueId)
        persist(player.uniqueId, true)
        refreshModModeTool(player)
        resync(player)
    }

    /** Keeps the Moderator Mode vanish dye in sync however vanish was toggled (tool, /vanish, ...). */
    private fun refreshModModeTool(player: Player) {
        if (plugin.modModeManager.isModMode(player)) plugin.modModeManager.refreshTool(player, "modmode_vanish")
    }

    fun unvanish(player: Player) {
        vanished.remove(player.uniqueId)
        persist(player.uniqueId, false)
        refreshModModeTool(player)
        // Same combined rule as vanish, so a viewer's /hidestaff still hides Mod Mode staff.
        resync(player)
    }

    private fun persist(uuid: UUID, isVanished: Boolean) {
        try {
            if (isVanished) {
                plugin.databaseManager.execute(
                    "INSERT OR IGNORE INTO vanished_players (uuid, since) VALUES (?, ?)",
                    uuid.toString(), System.currentTimeMillis()
                )
            } else {
                plugin.databaseManager.execute("DELETE FROM vanished_players WHERE uuid = ?", uuid.toString())
            }
        } catch (e: Exception) {
            plugin.logger.warning("[Vanish] Could not save vanish state for $uuid: ${e.message}")
        }
    }

    /**
     * LOWEST so the joiner is hidden (and its "vanished" metadata set) before the server sends
     * its TAB entry to the other players and before other plugins' join handlers run. The
     * join message itself is handled by WelcomeListener via hidesJoinLeave().
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val joiner = event.player

        // A stored vanish is dropped for players who can no longer vanish (e.g. demoted staff).
        if (vanished.contains(joiner.uniqueId) &&
            !VanishRules.keepOnJoin(stored = true, hasPermission = joiner.hasPermission(VanishRules.PERMISSION))) {
            vanished.remove(joiner.uniqueId)
            persist(joiner.uniqueId, false)
        }

        // Vanished joiner: hidden from everyone who can't see vanished players.
        resync(joiner)
        // Already-vanished players: hidden from the joiner unless they may see them.
        resyncViewer(joiner)
    }

    /** A world change must never reveal anyone: re-check both directions. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val player = event.player
        if (isVanished(player)) resync(player)
        resyncViewer(player)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1 && sender.hasPermission(VanishRules.PERMISSION)) {
            return Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}
