package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Admin+ per-viewer toggle (`/hidestaff`) that hides staff members currently
 * in active Moderator Mode from the toggling viewer only. Independent of
 * /vanish — [refreshVisibility] recomputes each viewer/target pair from both
 * reasons combined, so flipping one off never clobbers a still-active hide
 * from the other (both ultimately go through the same per-plugin
 * hidePlayer/showPlayer registration).
 */
class HideStaffManager(private val plugin: Joshymc) : Listener {

    companion object {
        const val PERM = "joshymc.hidestaff"
    }

    /** Viewers who currently have /hidestaff enabled. */
    private val hiding: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun start() {
        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS hidestaff_settings (
                uuid TEXT PRIMARY KEY,
                enabled INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun stop() {
        hiding.clear()
    }

    fun isHiding(viewer: Player): Boolean = hiding.contains(viewer.uniqueId)

    /** Toggles [viewer]'s preference and immediately re-syncs visibility of every online Mod Mode staff member. */
    fun toggle(viewer: Player): Boolean {
        val newValue = !hiding.contains(viewer.uniqueId)
        if (newValue) hiding.add(viewer.uniqueId) else hiding.remove(viewer.uniqueId)

        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO hidestaff_settings (uuid, enabled) VALUES (?, ?)",
            viewer.uniqueId.toString(), if (newValue) 1 else 0
        )

        for (target in Bukkit.getOnlinePlayers()) {
            if (target == viewer) continue
            if (plugin.modModeManager.isModMode(target)) refreshVisibility(viewer, target)
        }
        return newValue
    }

    /** Called by ModModeManager whenever [target]'s Moderator Mode state flips, to re-sync every viewer. */
    fun onModModeChanged(target: Player) {
        for (viewer in Bukkit.getOnlinePlayers()) {
            if (viewer == target) continue
            refreshVisibility(viewer, target)
        }
    }

    /** Recomputes whether [viewer] should see [target], honoring both /hidestaff and /vanish together. */
    private fun refreshVisibility(viewer: Player, target: Player) {
        if (viewer == target) return
        val hiddenByModMode = hiding.contains(viewer.uniqueId) && plugin.modModeManager.isModMode(target)
        val hiddenByVanish = plugin.vanishCommand.isVanished(target) && !viewer.hasPermission("joshymc.vanish")

        if (hiddenByModMode || hiddenByVanish) {
            viewer.hidePlayer(plugin, target)
        } else {
            viewer.showPlayer(plugin, target)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val joiner = event.player
        val enabled = plugin.databaseManager.queryFirst(
            "SELECT enabled FROM hidestaff_settings WHERE uuid = ?", joiner.uniqueId.toString()
        ) { rs -> rs.getInt("enabled") == 1 } ?: false

        if (!enabled || !joiner.hasPermission(PERM)) return

        hiding.add(joiner.uniqueId)
        for (target in Bukkit.getOnlinePlayers()) {
            if (target == joiner) continue
            if (plugin.modModeManager.isModMode(target)) refreshVisibility(joiner, target)
        }
    }
}
