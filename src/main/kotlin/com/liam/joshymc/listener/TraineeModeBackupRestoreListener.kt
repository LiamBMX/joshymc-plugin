package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import java.util.Base64

/**
 * Data-safety net left behind by the removal of Trainee Mode (issue #987). The
 * old `traineemode_backups` table held a player's real inventory while Trainee
 * Mode masked it with the tool hotbar; nothing else reads or writes that table
 * anymore. This restores any leftover backup the moment its owner is next
 * online, then deletes the row — so a player who was mid-session (or crashed
 * out of one) when the feature was removed doesn't lose the items it was
 * holding. Safe to delete once confirmed no rows remain in `traineemode_backups`.
 */
class TraineeModeBackupRestoreListener(private val plugin: Joshymc) : Listener {

    fun start() {
        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS traineemode_backups (
                uuid TEXT PRIMARY KEY,
                inventory_data TEXT NOT NULL,
                armor_data TEXT NOT NULL,
                offhand_data TEXT NOT NULL,
                selected_slot INTEGER NOT NULL,
                xp_level INTEGER NOT NULL,
                xp_progress REAL NOT NULL,
                game_mode TEXT NOT NULL,
                allow_flight INTEGER NOT NULL,
                was_flying INTEGER NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // Covers a plugin reload/update that doesn't restart the server —
        // players already online won't fire another PlayerJoinEvent.
        for (player in Bukkit.getOnlinePlayers()) restore(player)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) restore(player)
        }, 5L)
    }

    private fun restore(player: Player) {
        val uuid = player.uniqueId.toString()

        data class Backup(
            val inv: String, val armor: String, val offhand: String, val slot: Int,
            val xpLevel: Int, val xpProgress: Float, val gameMode: String,
            val allowFlight: Boolean, val wasFlying: Boolean
        )

        val backup = plugin.databaseManager.queryFirst(
            """SELECT inventory_data, armor_data, offhand_data, selected_slot, xp_level, xp_progress,
                      game_mode, allow_flight, was_flying
               FROM traineemode_backups WHERE uuid = ?""",
            uuid
        ) { rs ->
            Backup(
                rs.getString("inventory_data"), rs.getString("armor_data"), rs.getString("offhand_data"),
                rs.getInt("selected_slot"), rs.getInt("xp_level"), rs.getFloat("xp_progress"),
                rs.getString("game_mode"), rs.getInt("allow_flight") == 1, rs.getInt("was_flying") == 1
            )
        } ?: return

        player.inventory.clear()
        player.inventory.contents = deserializeArray(backup.inv, player.inventory.contents.size)
        player.inventory.setArmorContents(deserializeArray(backup.armor, 4))
        player.inventory.setItemInOffHand(deserializeArray(backup.offhand, 1)[0])
        player.inventory.heldItemSlot = backup.slot.coerceIn(0, 8)

        player.level = backup.xpLevel
        player.exp = backup.xpProgress.coerceIn(0f, 0.999f)

        player.gameMode = try {
            GameMode.valueOf(backup.gameMode)
        } catch (_: IllegalArgumentException) {
            GameMode.SURVIVAL
        }
        player.allowFlight = backup.allowFlight
        player.isFlying = backup.wasFlying && backup.allowFlight

        plugin.databaseManager.execute("DELETE FROM traineemode_backups WHERE uuid = ?", uuid)

        plugin.commsManager.send(
            player,
            Component.text(
                "Trainee Mode was removed from the server while you had a pending session — your inventory has been restored.",
                NamedTextColor.YELLOW
            ),
            CommunicationsManager.Category.ADMIN
        )
        plugin.logger.info("[TraineeModeRemoval] Restored legacy Trainee Mode backup for ${player.name}.")
    }

    private fun deserializeArray(data: String, size: Int): Array<ItemStack?> {
        val arr = arrayOfNulls<ItemStack>(size)
        if (data.isBlank()) return arr
        for (entry in data.split(";")) {
            val colon = entry.indexOf(':')
            if (colon < 0) continue
            val idx = entry.substring(0, colon).toIntOrNull() ?: continue
            if (idx !in 0 until size) continue
            try {
                arr[idx] = ItemStack.deserializeBytes(Base64.getDecoder().decode(entry.substring(colon + 1)))
            } catch (_: Exception) {
            }
        }
        return arr
    }
}
