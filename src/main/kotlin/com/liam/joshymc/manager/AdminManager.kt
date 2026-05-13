package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AdminManager(private val plugin: Joshymc) : Listener {

    // ── Data classes ────────────────────────────────────

    data class AdminLog(
        val id: Int,
        val adminName: String,
        val action: String,
        val targetName: String?,
        val details: String?,
        val timestamp: Long
    )

    data class InventorySnapshot(
        val id: Int,
        val playerName: String,
        val type: String,
        val timestamp: Long
    )

    // ── Pending Actions (chat-based reason input) ──────

    data class PendingAction(
        val type: String,
        val targetUuid: UUID,
        val targetName: String,
        val duration: Long? = null,
        val createdAt: Long = System.currentTimeMillis()
    )

    val pendingActions = ConcurrentHashMap<UUID, PendingAction>()
    private var pendingExpiryTask: BukkitTask? = null

    // ── Anticheat alert toggles ────────────────────────

    private val acAlertToggles = mutableSetOf<UUID>()

    // ── State ───────────────────────────────────────────

    val frozenPlayers = mutableSetOf<UUID>()

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    private val PANEL_TITLE = Component.text("Admin Panel", TextColor.color(0xFF5555))
        .decoration(TextDecoration.BOLD, true)
        .decoration(TextDecoration.ITALIC, false)

    // ── Lifecycle ───────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS admin_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                admin_uuid TEXT NOT NULL,
                admin_name TEXT NOT NULL,
                action TEXT NOT NULL,
                target_uuid TEXT,
                target_name TEXT,
                details TEXT,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS inventory_snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                snapshot_type TEXT NOT NULL,
                inventory_data TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS ac_alert_toggles (
                player_uuid TEXT PRIMARY KEY
            )
        """.trimIndent())

        plugin.databaseManager.query(
            "SELECT player_uuid FROM ac_alert_toggles"
        ) { rs -> UUID.fromString(rs.getString("player_uuid")) }.forEach { acAlertToggles.add(it) }

        // Expire pending actions after 30 seconds
        pendingExpiryTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable {
            val now = System.currentTimeMillis()
            val iter = pendingActions.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (now - entry.value.createdAt > 30_000) {
                    iter.remove()
                    val admin = Bukkit.getPlayer(entry.key)
                    if (admin != null) {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            plugin.commsManager.send(admin, Component.text("Punishment action timed out.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
                        })
                    }
                }
            }
        }, 20L, 20L)

        plugin.logger.info("[Admin] Admin manager started.")
    }

    fun stop() {
        pendingExpiryTask?.cancel()
        pendingExpiryTask = null
        pendingActions.clear()
        frozenPlayers.clear()
    }

    // ── Action Logging ──────────────────────────────────

    fun logAction(admin: Player, action: String, target: OfflinePlayer? = null, details: String? = null) {
        val now = System.currentTimeMillis()
        plugin.databaseManager.execute(
            "INSERT INTO admin_logs (admin_uuid, admin_name, action, target_uuid, target_name, details, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)",
            admin.uniqueId.toString(),
            admin.name,
            action,
            target?.uniqueId?.toString(),
            target?.name,
            details,
            now
        )
    }

    fun getRecentLogs(limit: Int = 50): List<AdminLog> {
        return plugin.databaseManager.query(
            "SELECT id, admin_name, action, target_name, details, timestamp FROM admin_logs ORDER BY timestamp DESC LIMIT ?",
            limit
        ) { rs -> mapLog(rs) }
    }

    fun getLogsForPlayer(uuid: UUID, limit: Int = 20): List<AdminLog> {
        return plugin.databaseManager.query(
            "SELECT id, admin_name, action, target_name, details, timestamp FROM admin_logs WHERE target_uuid = ? ORDER BY timestamp DESC LIMIT ?",
            uuid.toString(), limit
        ) { rs -> mapLog(rs) }
    }

    fun getLogsForAdmin(uuid: UUID, limit: Int = 20): List<AdminLog> {
        return plugin.databaseManager.query(
            "SELECT id, admin_name, action, target_name, details, timestamp FROM admin_logs WHERE admin_uuid = ? ORDER BY timestamp DESC LIMIT ?",
            uuid.toString(), limit
        ) { rs -> mapLog(rs) }
    }

    private fun mapLog(rs: java.sql.ResultSet): AdminLog {
        return AdminLog(
            id = rs.getInt("id"),
            adminName = rs.getString("admin_name"),
            action = rs.getString("action"),
            targetName = rs.getString("target_name"),
            details = rs.getString("details"),
            timestamp = rs.getLong("timestamp")
        )
    }

    // ── Inventory Snapshots ─────────────────────────────

    fun saveSnapshot(player: Player, type: String) {
        val now = System.currentTimeMillis()
        val data = serializeInventory(player)
        plugin.databaseManager.execute(
            "INSERT INTO inventory_snapshots (player_uuid, player_name, snapshot_type, inventory_data, timestamp) VALUES (?, ?, ?, ?, ?)",
            player.uniqueId.toString(),
            player.name,
            type,
            data,
            now
        )
    }

    fun getSnapshots(uuid: UUID, limit: Int = 10): List<InventorySnapshot> {
        return plugin.databaseManager.query(
            "SELECT id, player_name, snapshot_type, timestamp FROM inventory_snapshots WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT ?",
            uuid.toString(), limit
        ) { rs ->
            InventorySnapshot(
                id = rs.getInt("id"),
                playerName = rs.getString("player_name"),
                type = rs.getString("snapshot_type"),
                timestamp = rs.getLong("timestamp")
            )
        }
    }

    fun previewSnapshot(admin: Player, snapshotId: Int, targetUuid: UUID, targetName: String) {
        data class SnapshotData(val inventoryData: String, val snapshotType: String)
        val snapshot = plugin.databaseManager.queryFirst(
            "SELECT inventory_data, snapshot_type FROM inventory_snapshots WHERE id = ?",
            snapshotId
        ) { rs -> SnapshotData(rs.getString("inventory_data"), rs.getString("snapshot_type")) } ?: run {
            plugin.commsManager.send(admin, Component.text("Snapshot not found.", NamedTextColor.RED))
            return
        }
        val data = snapshot.inventoryData
        val snapshotType = snapshot.snapshotType

        val gui = CustomGui(
            Component.text("Preview: $targetName #$snapshotId", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        gui.fill(FILLER)

        // Deserialize items into the preview GUI
        if (data.isNotBlank()) {
            for (entry in data.split(";")) {
                val colonIdx = entry.indexOf(':')
                if (colonIdx < 0) continue
                val slot = entry.substring(0, colonIdx).toIntOrNull() ?: continue
                val encoded = entry.substring(colonIdx + 1)
                try {
                    val bytes = Base64.getDecoder().decode(encoded)
                    val item = ItemStack.deserializeBytes(bytes)
                    if (slot < 54) gui.inventory.setItem(slot, item)
                } catch (_: Exception) {}
            }
        }

        // Restore button at slot 49
        val restoreBtn = ItemStack(Material.LIME_WOOL)
        restoreBtn.editMeta { meta ->
            meta.displayName(Component.text("Restore This Snapshot", NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(
                Component.empty(),
                Component.text("Click to restore this inventory to the player", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ))
        }
        gui.setItem(49, restoreBtn) { p, _ ->
            val targetPlayer = Bukkit.getPlayer(targetUuid)
            if (targetPlayer == null) {
                plugin.commsManager.send(p, Component.text("Player is offline.", NamedTextColor.RED))
                return@setItem
            }

            // Warn if player has items that will be replaced
            if (targetPlayer.inventory.contents.any { it != null && !it.type.isAir }) {
                plugin.commsManager.send(p, Component.text("Warning: ${targetName} has items in inventory. They will be replaced.", NamedTextColor.YELLOW))
            }

            saveSnapshot(targetPlayer, "rollback_source")
            val success = restoreSnapshot(targetPlayer, snapshotId)
            if (success) {
                logAction(p, "RESTORE_INVENTORY", Bukkit.getOfflinePlayer(targetUuid), "Snapshot #$snapshotId")
                plugin.commsManager.send(p, Component.text("Restored ${targetName}'s inventory from snapshot #$snapshotId", NamedTextColor.GREEN))
                p.playSound(p.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.0f)

                // If restoring a death snapshot, clean up tagged ground items to prevent duping
                if (snapshotType == "death") {
                    val deathKey = NamespacedKey(plugin, "death_drop")
                    var cleaned = 0
                    for (world in Bukkit.getWorlds()) {
                        for (entity in world.entities) {
                            if (entity is org.bukkit.entity.Item) {
                                val itemMeta = entity.itemStack.itemMeta ?: continue
                                val tag = itemMeta.persistentDataContainer.get(deathKey, PersistentDataType.STRING)
                                if (tag == targetUuid.toString()) {
                                    entity.remove()
                                    cleaned++
                                }
                            }
                        }
                    }
                    if (cleaned > 0) {
                        plugin.commsManager.send(p, Component.text("Cleaned up $cleaned death drop items from the ground.", NamedTextColor.GRAY))
                    }
                }
            } else {
                plugin.commsManager.send(p, Component.text("Failed to restore!", NamedTextColor.RED))
            }
            p.closeInventory()
        }

        // Back button at slot 45
        val backBtn = ItemStack(Material.ARROW)
        backBtn.editMeta { it.displayName(Component.text("Back", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)) }
        gui.setItem(45, backBtn) { p, _ -> openSnapshotList(p, targetUuid, targetName, 0) }

        plugin.guiManager.open(admin, gui)
    }

    fun restoreSnapshot(player: Player, snapshotId: Int): Boolean {
        val data = plugin.databaseManager.queryFirst(
            "SELECT inventory_data FROM inventory_snapshots WHERE id = ?",
            snapshotId
        ) { rs -> rs.getString("inventory_data") } ?: return false

        player.inventory.clear()
        deserializeInventory(player, data)
        return true
    }

    private fun serializeInventory(player: Player): String {
        val contents = player.inventory.contents
        val parts = mutableListOf<String>()
        for (i in contents.indices) {
            val item = contents[i]
            if (item != null && item.type != Material.AIR) {
                val bytes = item.serializeAsBytes()
                val encoded = Base64.getEncoder().encodeToString(bytes)
                parts.add("$i:$encoded")
            }
        }
        return parts.joinToString(";")
    }

    private fun deserializeInventory(player: Player, data: String) {
        if (data.isBlank()) return
        for (entry in data.split(";")) {
            val colonIdx = entry.indexOf(':')
            if (colonIdx < 0) continue
            val slot = entry.substring(0, colonIdx).toIntOrNull() ?: continue
            val encoded = entry.substring(colonIdx + 1)
            try {
                val bytes = Base64.getDecoder().decode(encoded)
                val item = ItemStack.deserializeBytes(bytes)
                if (slot in 0 until player.inventory.size) {
                    player.inventory.setItem(slot, item)
                }
            } catch (_: Exception) {
                // Skip corrupted slots
            }
        }
    }

    // ── Freeze System ───────────────────────────────────

    fun toggleFreeze(target: Player): Boolean {
        return if (frozenPlayers.contains(target.uniqueId)) {
            frozenPlayers.remove(target.uniqueId)
            false
        } else {
            frozenPlayers.add(target.uniqueId)
            true
        }
    }

    fun isFrozen(uuid: UUID): Boolean = frozenPlayers.contains(uuid)

    // ── Events ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    fun onMove(event: PlayerMoveEvent) {
        if (!frozenPlayers.contains(event.player.uniqueId)) return
        val from = event.from
        val to = event.to
        // Allow head rotation, block position changes
        if (from.blockX != to.blockX || from.blockY != to.blockY || from.blockZ != to.blockZ) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        // Keep frozen status — don't remove from frozenPlayers on quit
        // Keep acAlertToggles — preference is persisted in DB and should survive reconnects
        pendingActions.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onJoinFrozen(event: org.bukkit.event.player.PlayerJoinEvent) {
        if (frozenPlayers.contains(event.player.uniqueId)) {
            plugin.commsManager.send(event.player, net.kyori.adventure.text.Component.text(
                "You are frozen by a staff member. Do not move.", net.kyori.adventure.text.format.NamedTextColor.RED
            ))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        try {
            saveSnapshot(player, "death")
        } catch (e: Exception) {
            plugin.logger.warning("[Admin] Failed to save death snapshot for ${player.name}: ${e.message}")
        }

        // Tag all death drops with the player's UUID for anti-dupe cleanup
        val deathKey = NamespacedKey(plugin, "death_drop")
        for (drop in event.drops) {
            val meta = drop.itemMeta ?: continue
            meta.persistentDataContainer.set(deathKey, PersistentDataType.STRING, player.uniqueId.toString())
            drop.itemMeta = meta
        }
    }

    // ── Permission Helpers ────────────────────────────

    companion object {
        const val PERM_ADMIN = "joshymc.admin"
        const val PERM_MODERATE = "joshymc.admin.moderate"
        const val PERM_HELPER = "joshymc.admin.helper"
    }

    /** Returns true if player has any admin tier permission */
    fun hasAnyAdminPermission(player: Player): Boolean {
        return player.hasPermission(PERM_ADMIN)
                || player.hasPermission(PERM_MODERATE)
                || player.hasPermission(PERM_HELPER)
    }

    // ── Chat-based Reason Input ────────────────────────

    private fun promptForReason(admin: Player, type: String, targetUuid: UUID, targetName: String, duration: Long? = null) {
        pendingActions[admin.uniqueId] = PendingAction(type, targetUuid, targetName, duration)
        admin.closeInventory()
        plugin.commsManager.send(admin, Component.text("Type the reason in chat (or 'cancel' to abort):", NamedTextColor.YELLOW), CommunicationsManager.Category.ADMIN)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onAdminChat(event: AsyncChatEvent) {
        val admin = event.player
        val pending = pendingActions.remove(admin.uniqueId) ?: return

        event.isCancelled = true
        val message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()

        if (message.equals("cancel", ignoreCase = true)) {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                plugin.commsManager.send(admin, Component.text("Action cancelled.", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
            })
            return
        }

        val reason = message
        val targetUuid = pending.targetUuid
        val targetName = pending.targetName

        Bukkit.getScheduler().runTask(plugin, Runnable {
            when (pending.type) {
                "ban" -> {
                    if (pending.duration != null) {
                        plugin.punishmentManager.tempban(targetUuid, targetName, admin.name, admin.uniqueId, reason, pending.duration)
                        val durationStr = PunishmentManager.formatDuration(pending.duration)
                        logAction(admin, "BAN", Bukkit.getOfflinePlayer(targetUuid), "$durationStr - $reason")
                        plugin.commsManager.send(admin, Component.text("Banned $targetName for $durationStr - $reason", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
                    } else {
                        plugin.punishmentManager.ban(targetUuid, targetName, admin.name, admin.uniqueId, reason)
                        logAction(admin, "BAN", Bukkit.getOfflinePlayer(targetUuid), "Permanent - $reason")
                        plugin.commsManager.send(admin, Component.text("Permanently banned $targetName - $reason", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
                    }
                    Bukkit.getPlayer(targetUuid)?.kick(Component.text("You have been banned! Reason: $reason", NamedTextColor.RED))
                }
                "mute" -> {
                    if (pending.duration != null) {
                        plugin.punishmentManager.tempmute(targetUuid, targetName, admin.name, admin.uniqueId, reason, pending.duration)
                        val durationStr = PunishmentManager.formatDuration(pending.duration)
                        logAction(admin, "MUTE", Bukkit.getOfflinePlayer(targetUuid), "$durationStr - $reason")
                        plugin.commsManager.send(admin, Component.text("Muted $targetName for $durationStr - $reason", NamedTextColor.LIGHT_PURPLE), CommunicationsManager.Category.ADMIN)
                    } else {
                        plugin.punishmentManager.mute(targetUuid, targetName, admin.name, admin.uniqueId, reason)
                        logAction(admin, "MUTE", Bukkit.getOfflinePlayer(targetUuid), "Permanent - $reason")
                        plugin.commsManager.send(admin, Component.text("Permanently muted $targetName - $reason", NamedTextColor.LIGHT_PURPLE), CommunicationsManager.Category.ADMIN)
                    }
                    Bukkit.getPlayer(targetUuid)?.let { tp ->
                        plugin.commsManager.send(tp, Component.text("You have been muted! Reason: $reason", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
                    }
                }
                "kick" -> {
                    val tp = Bukkit.getPlayer(targetUuid)
                    if (tp != null) {
                        plugin.punishmentManager.kick(targetUuid, targetName, admin.name, admin.uniqueId, reason)
                        tp.kick(Component.text("Kicked by ${admin.name}: $reason", NamedTextColor.RED))
                        logAction(admin, "KICK", Bukkit.getOfflinePlayer(targetUuid), reason)
                        plugin.commsManager.send(admin, Component.text("Kicked $targetName - $reason", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
                    } else {
                        plugin.commsManager.send(admin, Component.text("Player is not online!", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
                    }
                }
                "warn" -> {
                    plugin.punishmentManager.warn(targetUuid, targetName, admin.name, admin.uniqueId, reason)
                    logAction(admin, "WARN", Bukkit.getOfflinePlayer(targetUuid), reason)
                    plugin.commsManager.send(admin, Component.text("Warned $targetName - $reason", NamedTextColor.YELLOW), CommunicationsManager.Category.ADMIN)
                    Bukkit.getPlayer(targetUuid)?.let { tp ->
                        plugin.commsManager.send(tp, Component.text("You have been warned! Reason: $reason", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
                        tp.playSound(tp.location, Sound.ENTITY_WITHER_HURT, 0.5f, 1.0f)
                    }
                }
            }
            admin.playSound(admin.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.0f)
        })
    }

    // ── GUIs ────────────────────────────────────────────

    fun openMainPanel(admin: Player) {
        val gui = CustomGui(PANEL_TITLE, 54)
        gui.fill(FILLER)

        // Row 1 center (slot 4): Online Players — visible to all tiers
        gui.setItem(4, buildItem(Material.PLAYER_HEAD, "Online Players", NamedTextColor.GREEN,
            "View and manage online players")) { p, _ ->
            openPlayerList(p)
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        // Row 2 (slots 10-16): Action Logs, Inventory Rollbacks, Ban List, Mute List
        // Action Logs — helper+
        if (admin.hasPermission(PERM_HELPER) || admin.hasPermission(PERM_MODERATE) || admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(11, buildItem(Material.BOOK, "Action Logs", NamedTextColor.YELLOW,
                "View recent admin actions")) { p, _ ->
                openLogViewer(p, 0)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        // Inventory Rollbacks — admin only
        if (admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(12, buildItem(Material.CHEST, "Inventory Rollbacks", NamedTextColor.GOLD,
                "Restore player inventories")) { p, _ ->
                openPlayerListForAction(p, "rollback")
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        // Ban List — admin only
        if (admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(14, buildItem(Material.BARRIER, "Ban List", NamedTextColor.RED,
                "View and manage active bans")) { p, _ ->
                openBanList(p, 0)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        // Mute List — moderate+
        if (admin.hasPermission(PERM_MODERATE) || admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(15, buildItem(Material.BELL, "Mute List", NamedTextColor.LIGHT_PURPLE,
                "View and manage active mutes")) { p, _ ->
                openMuteList(p, 0)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        // Anticheat — admin only
        if (admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(13, buildItem(Material.IRON_SWORD, "Anticheat", NamedTextColor.AQUA,
                "Manage anticheat checks")) { p, _ ->
                openAnticheatPanel(p)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        // Row 3 center (slot 22): Reports — gets its own row so it doesn't
        // crowd the 5-button row above (which is symmetric around slot 13).
        if (admin.hasPermission("joshymc.reports.view")) {
            gui.setItem(22, buildItem(Material.WRITABLE_BOOK, "Reports", NamedTextColor.GOLD,
                "View and resolve player reports")) { p, _ ->
                openReportsList(p, 0, null)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        // Row 4 center (slot 31): Server Info
        gui.setItem(31, buildServerInfoItem()) { p, _ ->
            // Refresh server info
            openMainPanel(p)
        }

        plugin.guiManager.open(admin, gui)
        admin.playSound(admin.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    fun openPlayerList(admin: Player) {
        val gui = CustomGui(
            Component.text("Online Players", NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        gui.fill(FILLER)

        val players = Bukkit.getOnlinePlayers().toList()
        val maxSlots = 45 // 5 rows of items
        for ((index, target) in players.withIndex()) {
            if (index >= maxSlots) break
            val head = buildPlayerHead(target)
            gui.setItem(index, head) { p, _ ->
                openPlayerPanel(p, target)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        // Back button slot 49
        gui.setItem(49, buildItem(Material.ARROW, "Back", NamedTextColor.GRAY, "Return to admin panel")) { p, _ ->
            openMainPanel(p)
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        plugin.guiManager.open(admin, gui)
    }

    private fun openPlayerListForAction(admin: Player, action: String) {
        val gui = CustomGui(
            Component.text("Select Player", NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        gui.fill(FILLER)

        val players = Bukkit.getOnlinePlayers().toList()
        for ((index, target) in players.withIndex()) {
            if (index >= 45) break
            val head = buildPlayerHead(target)
            gui.setItem(index, head) { p, _ ->
                when (action) {
                    "rollback" -> openSnapshotList(p, target.uniqueId, target.name, 0)
                }
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        gui.setItem(49, buildItem(Material.ARROW, "Back", NamedTextColor.GRAY, "Return to admin panel")) { p, _ ->
            openMainPanel(p)
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        plugin.guiManager.open(admin, gui)
    }

    fun openPlayerPanel(admin: Player, target: OfflinePlayer) {
        val targetName = target.name ?: "Unknown"
        val gui = CustomGui(
            Component.text("Admin: $targetName", TextColor.color(0xFF5555))
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        gui.fill(FILLER)

        // Slot 4: Player info head
        val infoHead = ItemStack(Material.PLAYER_HEAD)
        infoHead.editMeta(SkullMeta::class.java) { meta ->
            meta.owningPlayer = target
            meta.displayName(
                Component.text(targetName, NamedTextColor.AQUA)
                    .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
            )
            val lore = mutableListOf<Component>()
            lore.add(Component.empty())

            // UUID
            lore.add(Component.text("UUID: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(target.uniqueId.toString().substring(0, 8) + "...", NamedTextColor.WHITE)))

            // Online status
            val online = target.isOnline
            val onlinePlayer = if (online) target.player else null
            lore.add(Component.text("Status: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(if (online) Component.text("Online", NamedTextColor.GREEN) else Component.text("Offline", NamedTextColor.RED)))

            // Rank
            val rank = if (onlinePlayer != null) plugin.rankManager.getPlayerRank(onlinePlayer) else plugin.rankManager.getPlayerRankById(target.uniqueId)
            if (rank != null) {
                lore.add(Component.text("Rank: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(plugin.commsManager.parseLegacy(rank.displayTag)))
            }

            // Team
            val team = plugin.teamManager.getPlayerTeam(target.uniqueId)
            if (team != null) {
                lore.add(Component.text("Team: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(team, NamedTextColor.WHITE)))
            }

            // Balance
            val balance = plugin.economyManager.getBalance(target.uniqueId)
            lore.add(Component.text("Balance: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(plugin.economyManager.format(balance), NamedTextColor.GOLD)))

            // Playtime
            val playtime = plugin.playtimeManager.getPlaytime(target.uniqueId)
            lore.add(Component.text("Playtime: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(plugin.playtimeManager.formatPlaytime(playtime), NamedTextColor.WHITE)))

            // Claims
            val claims = plugin.claimManager.getClaimsByPlayer(target.uniqueId)
            val usedBlocks = plugin.claimManager.getUsedBlocks(target.uniqueId)
            val totalBlocks = plugin.claimManager.getTotalBlocks(target.uniqueId)
            lore.add(Component.text("Claims: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("${claims.size} ($usedBlocks/$totalBlocks blocks)", NamedTextColor.WHITE)))

            // IP if online
            if (onlinePlayer != null) {
                val ip = onlinePlayer.address?.address?.hostAddress ?: "Unknown"
                lore.add(Component.text("IP: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(ip, NamedTextColor.WHITE)))
            }

            // Frozen status
            if (isFrozen(target.uniqueId)) {
                lore.add(Component.empty())
                lore.add(Component.text("FROZEN", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false))
            }

            meta.lore(lore)
        }
        gui.setItem(4, infoHead)

        // ── Row 2: Action buttons (permission-gated) ────

        // Heal (slot 10) — admin only
        if (admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(10, buildItem(Material.GREEN_WOOL, "Heal", NamedTextColor.GREEN, "Restore full health and hunger")) { p, _ ->
                val tp = target.player
                if (tp == null) { notOnline(p); return@setItem }
                tp.health = tp.maxHealth
                tp.foodLevel = 20
                tp.saturation = 20f
                tp.fireTicks = 0
                plugin.commsManager.send(p, Component.text("Healed ${tp.name}", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
                logAction(p, "HEAL", target)
                p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f)
            }
        }

        // Teleport To (slot 11) — admin only
        if (admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(11, buildItem(Material.YELLOW_WOOL, "Teleport To", NamedTextColor.YELLOW, "Teleport to this player")) { p, _ ->
                val tp = target.player
                if (tp == null) { notOnline(p); return@setItem }
                p.teleport(tp.location)
                plugin.commsManager.send(p, Component.text("Teleported to ${tp.name}", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
                logAction(p, "TELEPORT_TO", target)
                p.closeInventory()
            }
        }

        // Teleport Here (slot 12) — admin only
        if (admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(12, buildItem(Material.ORANGE_WOOL, "Teleport Here", NamedTextColor.GOLD, "Teleport this player to you")) { p, _ ->
                val tp = target.player
                if (tp == null) { notOnline(p); return@setItem }
                tp.teleport(p.location)
                plugin.commsManager.send(p, Component.text("Teleported ${tp.name} to you", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
                logAction(p, "TELEPORT_HERE", target)
                p.closeInventory()
            }
        }

        // Kick (slot 13) — moderate+
        if (admin.hasPermission(PERM_MODERATE) || admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(13, buildItem(Material.RED_WOOL, "Kick", NamedTextColor.RED, "Kick this player")) { p, _ ->
                val tp = target.player
                if (tp == null) { notOnline(p); return@setItem }
                promptForReason(p, "kick", target.uniqueId, targetName)
            }
        }

        // Ban (slot 14) — admin only
        if (admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(14, buildItem(Material.RED_CONCRETE, "Ban", NamedTextColor.DARK_RED, "Ban this player")) { p, _ ->
                openDurationSelector(p, target.uniqueId, targetName, "ban")
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        // Mute (slot 15) — moderate+
        if (admin.hasPermission(PERM_MODERATE) || admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(15, buildItem(Material.PURPLE_WOOL, "Mute", NamedTextColor.LIGHT_PURPLE, "Mute this player")) { p, _ ->
                openDurationSelector(p, target.uniqueId, targetName, "mute")
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        // Warn (slot 16) — moderate+ and helper+
        if (admin.hasPermission(PERM_HELPER) || admin.hasPermission(PERM_MODERATE) || admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(16, buildItem(Material.PAPER, "Warn", NamedTextColor.WHITE, "Warn this player")) { p, _ ->
                promptForReason(p, "warn", target.uniqueId, targetName)
            }
        }

        // ── Row 3: More actions (permission-gated) ─────

        // Set Gamemode (slot 19) — admin only
        if (admin.hasPermission(PERM_ADMIN)) {
            val currentGm = target.player?.gameMode ?: GameMode.SURVIVAL
            gui.setItem(19, buildItem(Material.DIAMOND_SWORD, "Set Gamemode", NamedTextColor.AQUA,
                "Current: ${currentGm.name}", "Click to cycle gamemodes")) { p, _ ->
                val tp = target.player
                if (tp == null) { notOnline(p); return@setItem }
                val next = when (tp.gameMode) {
                    GameMode.SURVIVAL -> GameMode.CREATIVE
                    GameMode.CREATIVE -> GameMode.ADVENTURE
                    GameMode.ADVENTURE -> GameMode.SPECTATOR
                    GameMode.SPECTATOR -> GameMode.SURVIVAL
                }
                tp.gameMode = next
                logAction(p, "GAMEMODE", target, next.name)
                plugin.commsManager.send(p, Component.text("Set ${tp.name}'s gamemode to ${next.name}", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                openPlayerPanel(p, target)
            }
        }

        // Set Rank (slot 20) — admin only
        if (admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(20, buildItem(Material.GOLD_INGOT, "Set Rank", NamedTextColor.GOLD, "Change this player's rank")) { p, _ ->
                openRankSelector(p, target.uniqueId, targetName)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        // View Inventory / Invsee (slot 21) — admin only
        if (admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(21, buildItem(Material.ENDER_CHEST, "View Inventory", NamedTextColor.DARK_PURPLE, "View this player's inventory")) { p, _ ->
                val tp = target.player
                if (tp == null) { notOnline(p); return@setItem }
                openInvsee(p, tp)
                logAction(p, "INVSEE", target)
            }
        }

        // Set Balance (slot 22) — admin only
        if (admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(22, buildItem(Material.EXPERIENCE_BOTTLE, "Set Balance", NamedTextColor.GOLD,
                "Set preset balance amounts")) { p, _ ->
                openBalanceSelector(p, target.uniqueId, targetName)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        // Punishment History (slot 23) — moderate+
        if (admin.hasPermission(PERM_MODERATE) || admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(23, buildItem(Material.CLOCK, "Punishment History", NamedTextColor.YELLOW,
                "View punishment history")) { p, _ ->
                openPunishmentHistory(p, target.uniqueId, targetName, 0)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        // Inventory Snapshots (slot 24) — admin only
        if (admin.hasPermission(PERM_ADMIN)) {
            gui.setItem(24, buildItem(Material.CHEST, "Inventory Snapshots", NamedTextColor.GOLD,
                "View and restore inventory snapshots")) { p, _ ->
                openSnapshotList(p, target.uniqueId, targetName, 0)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        // Reports against this player — anyone with view perm
        if (admin.hasPermission("joshymc.reports.view")) {
            val reportCount = plugin.databaseManager.queryFirst(
                "SELECT COUNT(*) AS n FROM reports WHERE target_uuid = ?",
                target.uniqueId.toString()
            ) { rs -> rs.getInt("n") } ?: 0
            val openCount = plugin.databaseManager.queryFirst(
                "SELECT COUNT(*) AS n FROM reports WHERE target_uuid = ? AND resolved = 0",
                target.uniqueId.toString()
            ) { rs -> rs.getInt("n") } ?: 0
            val title = if (openCount > 0) "Reports ($openCount open)" else "Reports ($reportCount total)"
            val color = if (openCount > 0) NamedTextColor.RED else NamedTextColor.GOLD
            gui.setItem(16, buildItem(Material.WRITABLE_BOOK, title, color,
                "Reports filed against ${targetName}")) { p, _ ->
                openReportsList(p, 0, target.uniqueId)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
        }

        // Freeze (slot 25) — moderate+
        if (admin.hasPermission(PERM_MODERATE) || admin.hasPermission(PERM_ADMIN)) {
            val frozen = isFrozen(target.uniqueId)
            val freezeLabel = if (frozen) "Unfreeze" else "Freeze"
            val freezeColor = if (frozen) NamedTextColor.GREEN else NamedTextColor.AQUA
            gui.setItem(25, buildItem(Material.BARRIER, freezeLabel, freezeColor,
                if (frozen) "Click to unfreeze" else "Click to freeze")) { p, _ ->
                val tp = target.player
                if (tp == null) { notOnline(p); return@setItem }
                val nowFrozen = toggleFreeze(tp)
                logAction(p, if (nowFrozen) "FREEZE" else "UNFREEZE", target)
                if (nowFrozen) {
                    plugin.commsManager.send(p, Component.text("Froze ${tp.name}", NamedTextColor.AQUA), CommunicationsManager.Category.ADMIN)
                    plugin.commsManager.send(tp, Component.text("You have been frozen by an administrator!", NamedTextColor.AQUA), CommunicationsManager.Category.ADMIN)
                } else {
                    plugin.commsManager.send(p, Component.text("Unfroze ${tp.name}", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
                    plugin.commsManager.send(tp, Component.text("You have been unfrozen.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
                }
                p.playSound(p.location, Sound.BLOCK_GLASS_BREAK, 0.5f, 1.5f)
                openPlayerPanel(p, target)
            }
        }

        // Back button (slot 49)
        gui.setItem(49, buildItem(Material.ARROW, "Back", NamedTextColor.GRAY, "Return to player list")) { p, _ ->
            openPlayerList(p)
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        plugin.guiManager.open(admin, gui)
    }

    fun openBanList(admin: Player, page: Int) {
        val gui = CustomGui(
            Component.text("Ban List", NamedTextColor.RED)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        gui.fill(FILLER)

        val bans = plugin.databaseManager.query(
            "SELECT target_uuid, target_name, reason, punisher_name, expires_at FROM punishments WHERE type IN ('BAN', 'TEMPBAN') AND active = 1 ORDER BY created_at DESC"
        ) { rs ->
            BanEntry(
                uuid = UUID.fromString(rs.getString("target_uuid")),
                name = rs.getString("target_name"),
                reason = rs.getString("reason"),
                punisherName = rs.getString("punisher_name"),
                expiresAt = rs.getLong("expires_at").takeIf { !rs.wasNull() }
            )
        }

        val itemsPerPage = 45
        val totalPages = ((bans.size - 1) / itemsPerPage).coerceAtLeast(0)
        val currentPage = page.coerceIn(0, totalPages)
        val start = currentPage * itemsPerPage
        val pageBans = bans.drop(start).take(itemsPerPage)

        for ((index, ban) in pageBans.withIndex()) {
            val head = ItemStack(Material.PLAYER_HEAD)
            head.editMeta(SkullMeta::class.java) { meta ->
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(ban.uuid))
                meta.displayName(
                    Component.text(ban.name, NamedTextColor.RED)
                        .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
                )
                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                lore.add(Component.text("Banned by: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(ban.punisherName, NamedTextColor.WHITE)))
                if (ban.reason != null) {
                    lore.add(Component.text("Reason: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(ban.reason, NamedTextColor.WHITE)))
                }
                if (ban.expiresAt != null) {
                    val remaining = ban.expiresAt - System.currentTimeMillis()
                    lore.add(Component.text("Expires: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(PunishmentManager.formatDuration(remaining), NamedTextColor.WHITE)))
                } else {
                    lore.add(Component.text("Duration: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("Permanent", NamedTextColor.RED)))
                }
                lore.add(Component.empty())
                lore.add(Component.text("Click to unban", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
                meta.lore(lore)
            }
            gui.setItem(index, head) { p, _ ->
                plugin.punishmentManager.unban(ban.uuid)
                logAction(p, "UNBAN", Bukkit.getOfflinePlayer(ban.uuid))
                plugin.commsManager.send(p, Component.text("Unbanned ${ban.name}", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
                p.playSound(p.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.0f)
                openBanList(p, currentPage)
            }
        }

        // Pagination
        if (currentPage > 0) {
            gui.setItem(48, buildItem(Material.ARROW, "Previous Page", NamedTextColor.GRAY, "Page $currentPage")) { p, _ ->
                openBanList(p, currentPage - 1)
            }
        }
        if (currentPage < totalPages) {
            gui.setItem(50, buildItem(Material.ARROW, "Next Page", NamedTextColor.GRAY, "Page ${currentPage + 2}")) { p, _ ->
                openBanList(p, currentPage + 1)
            }
        }

        gui.setItem(49, buildItem(Material.BARRIER, "Back", NamedTextColor.GRAY, "Return to admin panel")) { p, _ ->
            openMainPanel(p)
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        plugin.guiManager.open(admin, gui)
    }

    fun openMuteList(admin: Player, page: Int) {
        val gui = CustomGui(
            Component.text("Mute List", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        gui.fill(FILLER)

        val mutes = plugin.databaseManager.query(
            "SELECT target_uuid, target_name, reason, punisher_name, expires_at FROM punishments WHERE type IN ('MUTE', 'TEMPMUTE') AND active = 1 ORDER BY created_at DESC"
        ) { rs ->
            BanEntry(
                uuid = UUID.fromString(rs.getString("target_uuid")),
                name = rs.getString("target_name"),
                reason = rs.getString("reason"),
                punisherName = rs.getString("punisher_name"),
                expiresAt = rs.getLong("expires_at").takeIf { !rs.wasNull() }
            )
        }

        val itemsPerPage = 45
        val totalPages = ((mutes.size - 1) / itemsPerPage).coerceAtLeast(0)
        val currentPage = page.coerceIn(0, totalPages)
        val start = currentPage * itemsPerPage
        val pageMutes = mutes.drop(start).take(itemsPerPage)

        for ((index, mute) in pageMutes.withIndex()) {
            val head = ItemStack(Material.PLAYER_HEAD)
            head.editMeta(SkullMeta::class.java) { meta ->
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(mute.uuid))
                meta.displayName(
                    Component.text(mute.name, NamedTextColor.LIGHT_PURPLE)
                        .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
                )
                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                lore.add(Component.text("Muted by: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(mute.punisherName, NamedTextColor.WHITE)))
                if (mute.reason != null) {
                    lore.add(Component.text("Reason: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(mute.reason, NamedTextColor.WHITE)))
                }
                if (mute.expiresAt != null) {
                    val remaining = mute.expiresAt - System.currentTimeMillis()
                    lore.add(Component.text("Expires: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(PunishmentManager.formatDuration(remaining), NamedTextColor.WHITE)))
                } else {
                    lore.add(Component.text("Duration: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("Permanent", NamedTextColor.RED)))
                }
                lore.add(Component.empty())
                lore.add(Component.text("Click to unmute", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
                meta.lore(lore)
            }
            gui.setItem(index, head) { p, _ ->
                plugin.punishmentManager.unmute(mute.uuid)
                logAction(p, "UNMUTE", Bukkit.getOfflinePlayer(mute.uuid))
                plugin.commsManager.send(p, Component.text("Unmuted ${mute.name}", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
                p.playSound(p.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.0f)
                openMuteList(p, currentPage)
            }
        }

        // Pagination
        if (currentPage > 0) {
            gui.setItem(48, buildItem(Material.ARROW, "Previous Page", NamedTextColor.GRAY, "Page $currentPage")) { p, _ ->
                openMuteList(p, currentPage - 1)
            }
        }
        if (currentPage < totalPages) {
            gui.setItem(50, buildItem(Material.ARROW, "Next Page", NamedTextColor.GRAY, "Page ${currentPage + 2}")) { p, _ ->
                openMuteList(p, currentPage + 1)
            }
        }

        gui.setItem(49, buildItem(Material.BARRIER, "Back", NamedTextColor.GRAY, "Return to admin panel")) { p, _ ->
            openMainPanel(p)
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        plugin.guiManager.open(admin, gui)
    }

    /**
     * Reports GUI. Pass [filterTargetUuid] = null for the global view (all
     * reports across all players, used from the main admin panel) or pass a
     * specific player UUID to scope to reports against that player (used
     * from the per-player admin panel).
     *
     * Layout: open reports first sorted newest-first, then resolved reports
     * after. Each entry is a paper item with reporter / target / reason /
     * age in the lore. Left-click toggles resolved state. The remove
     * button on the right of every line deletes the report (admin-only).
     */
    fun openReportsList(admin: Player, page: Int, filterTargetUuid: UUID?) {
        val title = if (filterTargetUuid == null) {
            Component.text("Reports", NamedTextColor.GOLD)
        } else {
            val targetName = Bukkit.getOfflinePlayer(filterTargetUuid).name ?: "Unknown"
            Component.text("Reports against $targetName", NamedTextColor.GOLD)
        }.decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)

        val gui = CustomGui(title, 54)
        gui.fill(FILLER)

        val reports: List<ReportRow> = if (filterTargetUuid == null) {
            plugin.databaseManager.query(
                "SELECT id, reporter_name, target_name, target_uuid, reason, created_at, resolved FROM reports ORDER BY resolved ASC, created_at DESC LIMIT 500"
            ) { rs -> rs.toReportRow() }
        } else {
            plugin.databaseManager.query(
                "SELECT id, reporter_name, target_name, target_uuid, reason, created_at, resolved FROM reports WHERE target_uuid = ? ORDER BY resolved ASC, created_at DESC LIMIT 500",
                filterTargetUuid.toString()
            ) { rs -> rs.toReportRow() }
        }

        val itemsPerPage = 45
        val totalPages = ((reports.size - 1) / itemsPerPage).coerceAtLeast(0)
        val currentPage = page.coerceIn(0, totalPages)
        val pageReports = reports.drop(currentPage * itemsPerPage).take(itemsPerPage)

        if (reports.isEmpty()) {
            gui.setItem(22, buildItem(
                Material.PAPER,
                if (filterTargetUuid == null) "No reports yet" else "No reports against this player",
                NamedTextColor.GRAY,
                "All clear."
            ))
        }

        for ((index, report) in pageReports.withIndex()) {
            gui.setItem(index, buildReportItem(report)) { p, event ->
                if (event.isShiftClick && p.hasPermission(PERM_ADMIN)) {
                    // Shift-click = delete
                    plugin.databaseManager.execute("DELETE FROM reports WHERE id = ?", report.id)
                    p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.4f, 0.7f)
                    openReportsList(p, currentPage, filterTargetUuid)
                } else {
                    // Click = toggle resolved
                    val newResolved = if (report.resolved) 0 else 1
                    plugin.databaseManager.execute(
                        "UPDATE reports SET resolved = ? WHERE id = ?",
                        newResolved, report.id
                    )
                    p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f)
                    openReportsList(p, currentPage, filterTargetUuid)
                }
            }
        }

        if (currentPage > 0) {
            gui.setItem(48, buildItem(Material.ARROW, "Previous Page", NamedTextColor.GRAY, "Page $currentPage")) { p, _ ->
                openReportsList(p, currentPage - 1, filterTargetUuid)
            }
        }
        if (currentPage < totalPages) {
            gui.setItem(50, buildItem(Material.ARROW, "Next Page", NamedTextColor.GRAY, "Page ${currentPage + 2}")) { p, _ ->
                openReportsList(p, currentPage + 1, filterTargetUuid)
            }
        }

        gui.setItem(49, buildItem(Material.BARRIER, "Back", NamedTextColor.GRAY,
            if (filterTargetUuid == null) "Return to admin panel" else "Return to player panel")
        ) { p, _ ->
            if (filterTargetUuid == null) {
                openMainPanel(p)
            } else {
                openPlayerPanel(p, Bukkit.getOfflinePlayer(filterTargetUuid))
            }
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        plugin.guiManager.open(admin, gui)
    }

    private data class ReportRow(
        val id: Int,
        val reporterName: String,
        val targetName: String,
        val targetUuid: UUID,
        val reason: String,
        val createdAt: Long,
        val resolved: Boolean,
    )

    private fun java.sql.ResultSet.toReportRow(): ReportRow = ReportRow(
        id = getInt("id"),
        reporterName = getString("reporter_name"),
        targetName = getString("target_name"),
        targetUuid = UUID.fromString(getString("target_uuid")),
        reason = getString("reason"),
        createdAt = getLong("created_at"),
        resolved = getInt("resolved") == 1,
    )

    private fun buildReportItem(report: ReportRow): ItemStack {
        val mat = if (report.resolved) Material.LIME_DYE else Material.PAPER
        val item = ItemStack(mat)
        item.editMeta { meta ->
            val statusColor = if (report.resolved) NamedTextColor.GREEN else NamedTextColor.RED
            val statusText = if (report.resolved) "RESOLVED" else "OPEN"

            meta.displayName(
                Component.text("#${report.id} ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(report.targetName, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
                    .decoration(TextDecoration.ITALIC, false)
            )

            val ago = formatTimeAgo(System.currentTimeMillis() - report.createdAt)
            val lore = mutableListOf<Component>()
            lore.add(Component.empty())
            lore.add(Component.text("Status: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(statusText, statusColor).decoration(TextDecoration.BOLD, true)))
            lore.add(Component.text("Reporter: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(report.reporterName, NamedTextColor.WHITE)))
            lore.add(Component.text("Target: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(report.targetName, NamedTextColor.WHITE)))
            lore.add(Component.text("When: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(ago, NamedTextColor.WHITE)))
            lore.add(Component.empty())

            // Reason can be long — wrap into multiple lore lines.
            lore.add(Component.text("Reason:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            for (chunk in wrapText(report.reason, 40)) {
                lore.add(Component.text("  $chunk", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            }
            lore.add(Component.empty())
            val toggleHint = if (report.resolved) "Click to mark unresolved" else "Click to mark resolved"
            lore.add(Component.text(toggleHint, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
            lore.add(Component.text("Shift-click to delete", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))

            meta.lore(lore)
        }
        return item
    }

    /** Soft-wrap a string at word boundaries to fit roughly [maxChars] per line. */
    private fun wrapText(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val words = text.split(' ')
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (w in words) {
            if (cur.isNotEmpty() && cur.length + 1 + w.length > maxChars) {
                out.add(cur.toString())
                cur.clear()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    fun openLogViewer(admin: Player, page: Int) {
        val gui = CustomGui(
            Component.text("Action Logs", NamedTextColor.YELLOW)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        gui.fill(FILLER)

        val logs = getRecentLogs(500)
        val itemsPerPage = 45
        val totalPages = ((logs.size - 1) / itemsPerPage).coerceAtLeast(0)
        val currentPage = page.coerceIn(0, totalPages)
        val start = currentPage * itemsPerPage
        val pageLogs = logs.drop(start).take(itemsPerPage)

        for ((index, log) in pageLogs.withIndex()) {
            val item = ItemStack(Material.PAPER)
            item.editMeta { meta ->
                meta.displayName(
                    Component.text(log.action, NamedTextColor.YELLOW)
                        .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
                )
                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                lore.add(Component.text("Admin: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(log.adminName, NamedTextColor.WHITE)))
                if (log.targetName != null) {
                    lore.add(Component.text("Target: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(log.targetName, NamedTextColor.WHITE)))
                }
                if (log.details != null) {
                    lore.add(Component.text("Details: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(log.details, NamedTextColor.WHITE)))
                }
                lore.add(Component.text("Time: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(formatTimeAgo(log.timestamp), NamedTextColor.WHITE)))
                meta.lore(lore)
            }
            gui.setItem(index, item)
        }

        // Pagination
        if (currentPage > 0) {
            gui.setItem(48, buildItem(Material.ARROW, "Previous Page", NamedTextColor.GRAY, "Page $currentPage")) { p, _ ->
                openLogViewer(p, currentPage - 1)
            }
        }
        if (currentPage < totalPages) {
            gui.setItem(50, buildItem(Material.ARROW, "Next Page", NamedTextColor.GRAY, "Page ${currentPage + 2}")) { p, _ ->
                openLogViewer(p, currentPage + 1)
            }
        }

        gui.setItem(49, buildItem(Material.BARRIER, "Back", NamedTextColor.GRAY, "Return to admin panel")) { p, _ ->
            openMainPanel(p)
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        plugin.guiManager.open(admin, gui)
    }

    fun openDurationSelector(admin: Player, targetUuid: UUID, targetName: String, type: String) {
        val title = if (type == "ban") "Ban Duration" else "Mute Duration"
        val titleColor = if (type == "ban") NamedTextColor.RED else NamedTextColor.LIGHT_PURPLE
        val gui = CustomGui(
            Component.text("$title: $targetName", titleColor)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            27
        )
        gui.fill(FILLER)

        data class DurationOption(val label: String, val ms: Long?, val material: Material, val color: NamedTextColor)

        val durations = listOf(
            DurationOption("1 Hour", 3_600_000L, Material.LIME_WOOL, NamedTextColor.GREEN),
            DurationOption("6 Hours", 21_600_000L, Material.GREEN_WOOL, NamedTextColor.GREEN),
            DurationOption("12 Hours", 43_200_000L, Material.YELLOW_WOOL, NamedTextColor.YELLOW),
            DurationOption("1 Day", 86_400_000L, Material.GOLD_BLOCK, NamedTextColor.GOLD),
            DurationOption("3 Days", 259_200_000L, Material.ORANGE_WOOL, NamedTextColor.GOLD),
            DurationOption("7 Days", 604_800_000L, Material.RED_WOOL, NamedTextColor.RED),
            DurationOption("14 Days", 1_209_600_000L, Material.RED_CONCRETE, NamedTextColor.RED),
            DurationOption("30 Days", 2_592_000_000L, Material.MAGENTA_WOOL, NamedTextColor.DARK_RED),
            DurationOption("Permanent", null, Material.BEDROCK, NamedTextColor.DARK_RED),
        )

        // Center the 9 options in the middle row (slots 9-17)
        for ((index, option) in durations.withIndex()) {
            val slot = 9 + index
            gui.setItem(slot, buildItem(option.material, option.label, option.color,
                "Click to ${type} for ${option.label.lowercase()}")) { p, _ ->
                // After picking duration, prompt for reason via chat
                promptForReason(p, type, targetUuid, targetName, option.ms)
            }
        }

        // Back button
        gui.setItem(22, buildItem(Material.ARROW, "Back", NamedTextColor.GRAY, "Return to player panel")) { p, _ ->
            openPlayerPanel(p, Bukkit.getOfflinePlayer(targetUuid))
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        plugin.guiManager.open(admin, gui)
    }

    private fun openRankSelector(admin: Player, targetUuid: UUID, targetName: String) {
        val ranks = plugin.rankManager.getAllRanks().toList()
        val rows = ((ranks.size + 8) / 9).coerceIn(1, 6)
        val gui = CustomGui(
            Component.text("Set Rank: $targetName", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            rows * 9
        )
        gui.fill(FILLER)

        for ((index, rank) in ranks.withIndex()) {
            if (index >= rows * 9 - 9) break
            val item = ItemStack(Material.NAME_TAG)
            item.editMeta { meta ->
                meta.displayName(plugin.commsManager.parseLegacy(rank.displayTag)
                    .decoration(TextDecoration.ITALIC, false))
                meta.lore(listOf(
                    Component.text("ID: ${rank.id}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Weight: ${rank.weight}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Click to assign", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
                ))
            }
            gui.setItem(index, item) { p, _ ->
                plugin.rankManager.setPlayerRank(targetUuid, rank.id)
                logAction(p, "SET_RANK", Bukkit.getOfflinePlayer(targetUuid), rank.id)
                plugin.commsManager.send(p, Component.text("Set $targetName's rank to ", NamedTextColor.GREEN)
                    .append(plugin.commsManager.parseLegacy(rank.displayTag)), CommunicationsManager.Category.ADMIN)
                p.playSound(p.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.0f)
                openPlayerPanel(p, Bukkit.getOfflinePlayer(targetUuid))
            }
        }

        // Remove rank option
        gui.setItem(rows * 9 - 5, buildItem(Material.BARRIER, "Remove Rank", NamedTextColor.RED, "Reset to default rank")) { p, _ ->
            plugin.rankManager.setPlayerRank(targetUuid, null)
            logAction(p, "REMOVE_RANK", Bukkit.getOfflinePlayer(targetUuid))
            plugin.commsManager.send(p, Component.text("Removed $targetName's rank", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
            p.playSound(p.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.0f)
            openPlayerPanel(p, Bukkit.getOfflinePlayer(targetUuid))
        }

        // Back button
        gui.setItem(rows * 9 - 1, buildItem(Material.ARROW, "Back", NamedTextColor.GRAY, "Return to player panel")) { p, _ ->
            openPlayerPanel(p, Bukkit.getOfflinePlayer(targetUuid))
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        plugin.guiManager.open(admin, gui)
    }

    private fun openBalanceSelector(admin: Player, targetUuid: UUID, targetName: String) {
        val gui = CustomGui(
            Component.text("Set Balance: $targetName", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            27
        )
        gui.fill(FILLER)

        val currentBalance = plugin.economyManager.getBalance(targetUuid)

        // Show current balance
        val infoItem = ItemStack(Material.GOLD_INGOT)
        infoItem.editMeta { meta ->
            meta.displayName(Component.text("Current Balance", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(
                Component.text(plugin.economyManager.format(currentBalance), NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false)
            ))
        }
        gui.setItem(4, infoItem)

        data class BalanceOption(val label: String, val amount: Double, val material: Material)

        val options = listOf(
            BalanceOption("$0", 0.0, Material.COAL),
            BalanceOption("$100", 100.0, Material.IRON_INGOT),
            BalanceOption("$1,000", 1_000.0, Material.GOLD_INGOT),
            BalanceOption("$10,000", 10_000.0, Material.DIAMOND),
            BalanceOption("$50,000", 50_000.0, Material.EMERALD),
            BalanceOption("$100,000", 100_000.0, Material.NETHERITE_INGOT),
            BalanceOption("$500,000", 500_000.0, Material.NETHER_STAR),
            BalanceOption("$1,000,000", 1_000_000.0, Material.BEACON),
        )

        for ((index, option) in options.withIndex()) {
            val slot = 9 + index
            gui.setItem(slot, buildItem(option.material, option.label, NamedTextColor.GOLD,
                "Click to set balance")) { p, _ ->
                plugin.economyManager.setBalance(targetUuid, option.amount)
                logAction(p, "SET_BALANCE", Bukkit.getOfflinePlayer(targetUuid), option.label)
                plugin.commsManager.send(p, Component.text("Set $targetName's balance to ${option.label}", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
                p.playSound(p.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.0f)
                openPlayerPanel(p, Bukkit.getOfflinePlayer(targetUuid))
            }
        }

        // Back button
        gui.setItem(22, buildItem(Material.ARROW, "Back", NamedTextColor.GRAY, "Return to player panel")) { p, _ ->
            openPlayerPanel(p, Bukkit.getOfflinePlayer(targetUuid))
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        plugin.guiManager.open(admin, gui)
    }

    private fun openPunishmentHistory(admin: Player, targetUuid: UUID, targetName: String, page: Int) {
        val gui = CustomGui(
            Component.text("History: $targetName", NamedTextColor.YELLOW)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        gui.fill(FILLER)

        val history = plugin.punishmentManager.getHistory(targetUuid)
        val itemsPerPage = 45
        val totalPages = ((history.size - 1) / itemsPerPage).coerceAtLeast(0)
        val currentPage = page.coerceIn(0, totalPages)
        val start = currentPage * itemsPerPage
        val pageHistory = history.drop(start).take(itemsPerPage)

        for ((index, record) in pageHistory.withIndex()) {
            val material = when (record.type) {
                "BAN", "TEMPBAN" -> Material.RED_CONCRETE
                "MUTE", "TEMPMUTE" -> Material.PURPLE_WOOL
                "WARN" -> Material.PAPER
                "KICK" -> Material.RED_WOOL
                else -> Material.GRAY_WOOL
            }
            val color = when (record.type) {
                "BAN", "TEMPBAN" -> NamedTextColor.RED
                "MUTE", "TEMPMUTE" -> NamedTextColor.LIGHT_PURPLE
                "WARN" -> NamedTextColor.YELLOW
                "KICK" -> NamedTextColor.GOLD
                else -> NamedTextColor.GRAY
            }
            val item = ItemStack(material)
            item.editMeta { meta ->
                val activeLabel = if (record.active) " (Active)" else ""
                meta.displayName(
                    Component.text("${record.type}$activeLabel", color)
                        .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
                )
                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                lore.add(Component.text("By: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(record.punisherName, NamedTextColor.WHITE)))
                if (record.reason != null) {
                    lore.add(Component.text("Reason: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(record.reason, NamedTextColor.WHITE)))
                }
                lore.add(Component.text("Date: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(formatTimeAgo(record.createdAt), NamedTextColor.WHITE)))
                if (record.expiresAt != null) {
                    if (record.expiresAt > System.currentTimeMillis()) {
                        val remaining = record.expiresAt - System.currentTimeMillis()
                        lore.add(Component.text("Expires: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                            .append(Component.text(PunishmentManager.formatDuration(remaining), NamedTextColor.WHITE)))
                    } else {
                        lore.add(Component.text("Expired", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
                    }
                }
                if (record.active && record.type in listOf("BAN", "TEMPBAN", "MUTE", "TEMPMUTE")) {
                    meta.setEnchantmentGlintOverride(true)
                }
                meta.lore(lore)
            }
            gui.setItem(index, item)
        }

        // Pagination
        if (currentPage > 0) {
            gui.setItem(48, buildItem(Material.ARROW, "Previous Page", NamedTextColor.GRAY, "Page $currentPage")) { p, _ ->
                openPunishmentHistory(p, targetUuid, targetName, currentPage - 1)
            }
        }
        if (currentPage < totalPages) {
            gui.setItem(50, buildItem(Material.ARROW, "Next Page", NamedTextColor.GRAY, "Page ${currentPage + 2}")) { p, _ ->
                openPunishmentHistory(p, targetUuid, targetName, currentPage + 1)
            }
        }

        gui.setItem(49, buildItem(Material.BARRIER, "Back", NamedTextColor.GRAY, "Return to player panel")) { p, _ ->
            openPlayerPanel(p, Bukkit.getOfflinePlayer(targetUuid))
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        plugin.guiManager.open(admin, gui)
    }

    fun openInvsee(admin: Player, target: Player) {
        val invGui = CustomGui(
            Component.text("Inventory: ${target.name}", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        invGui.fill(FILLER)

        // Copy the target's inventory into the GUI (slots 0-35 = main inventory, 36-39 = armor, 40 = offhand)
        val contents = target.inventory.contents
        for (i in contents.indices) {
            val item = contents[i] ?: continue
            if (i < 36) {
                // Main inventory: remap to GUI slots (player inv slot 0-8 = hotbar, 9-35 = main)
                // Show hotbar at bottom row (slots 36-44), main inv above (slots 9-35 → GUI 0-26)
                val guiSlot = if (i < 9) i + 36 else i - 9
                if (guiSlot < 45) invGui.inventory.setItem(guiSlot, item.clone())
            }
        }

        // Armor in top row
        val armor = target.inventory.armorContents
        if (armor[3] != null) invGui.inventory.setItem(0, armor[3]!!.clone()) // helmet
        if (armor[2] != null) invGui.inventory.setItem(1, armor[2]!!.clone()) // chestplate
        if (armor[1] != null) invGui.inventory.setItem(2, armor[1]!!.clone()) // leggings
        if (armor[0] != null) invGui.inventory.setItem(3, armor[0]!!.clone()) // boots

        // Offhand
        val offhand = target.inventory.itemInOffHand
        if (offhand.type != Material.AIR) invGui.inventory.setItem(5, offhand.clone())

        // Back button
        invGui.setItem(49, buildItem(Material.ARROW, "Back", NamedTextColor.WHITE, "Return to player panel")) { p, _ ->
            openPlayerPanel(p, target)
        }

        plugin.guiManager.open(admin, invGui)
    }

    fun openSnapshotList(admin: Player, targetUuid: UUID, targetName: String, page: Int) {
        val gui = CustomGui(
            Component.text("Snapshots: $targetName", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        gui.fill(FILLER)

        val snapshots = getSnapshots(targetUuid, 200)
        val itemsPerPage = 45
        val totalPages = ((snapshots.size - 1) / itemsPerPage).coerceAtLeast(0)
        val currentPage = page.coerceIn(0, totalPages)
        val start = currentPage * itemsPerPage
        val pageSnapshots = snapshots.drop(start).take(itemsPerPage)

        for ((index, snapshot) in pageSnapshots.withIndex()) {
            val material = when (snapshot.type) {
                "death" -> Material.SKELETON_SKULL
                "manual" -> Material.CHEST
                "rollback_source" -> Material.ENDER_CHEST
                else -> Material.BARREL
            }
            val item = ItemStack(material)
            item.editMeta { meta ->
                meta.displayName(
                    Component.text("#${snapshot.id} - ${snapshot.type.uppercase()}", NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("Time: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(formatTimeAgo(snapshot.timestamp), NamedTextColor.WHITE)),
                    Component.empty(),
                    Component.text("Click to preview", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
                ))
            }
            val capturedId = snapshot.id
            gui.setItem(index, item) { p, _ ->
                previewSnapshot(p, capturedId, targetUuid, targetName)
            }
        }

        // Pagination
        if (currentPage > 0) {
            gui.setItem(48, buildItem(Material.ARROW, "Previous Page", NamedTextColor.GRAY, "Page $currentPage")) { p, _ ->
                openSnapshotList(p, targetUuid, targetName, currentPage - 1)
            }
        }
        if (currentPage < totalPages) {
            gui.setItem(50, buildItem(Material.ARROW, "Next Page", NamedTextColor.GRAY, "Page ${currentPage + 2}")) { p, _ ->
                openSnapshotList(p, targetUuid, targetName, currentPage + 1)
            }
        }

        gui.setItem(49, buildItem(Material.BARRIER, "Back", NamedTextColor.GRAY, "Return to admin panel")) { p, _ ->
            openMainPanel(p)
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        plugin.guiManager.open(admin, gui)
    }

    // ── Anticheat Panel ────────────────────────────────

    fun openAnticheatPanel(admin: Player) {
        val gui = CustomGui(
            Component.text("Anticheat Panel", NamedTextColor.AQUA)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        gui.fill(FILLER)

        val checks = AntiCheatManager.CheckType.entries
        for ((index, check) in checks.withIndex()) {
            if (index >= 45) break
            val enabled = plugin.antiCheatManager.isCheckEnabled(check)
            val material = if (enabled) Material.GREEN_WOOL else Material.RED_WOOL
            val color = if (enabled) NamedTextColor.GREEN else NamedTextColor.RED
            val statusText = if (enabled) "Enabled" else "Disabled"

            val item = ItemStack(material)
            item.editMeta { meta ->
                meta.displayName(
                    Component.text(check.displayName, color)
                        .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(listOf(
                    Component.text("Status: $statusText", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Click to toggle", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                ))
            }
            gui.setItem(index, item) { p, _ ->
                val newState = plugin.antiCheatManager.toggleCheck(check)
                logAction(p, "AC_TOGGLE", details = "${check.displayName} -> ${if (newState) "enabled" else "disabled"}")
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                openAnticheatPanel(p)
            }
        }

        // Bottom row: View Violations (slot 47)
        gui.setItem(47, buildItem(Material.PAPER, "View Violations", NamedTextColor.YELLOW,
            "Top 10 players by VL")) { p, _ ->
            openViolationsList(p)
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        // Alert Toggle (slot 49)
        val alertsOn = acAlertToggles.contains(admin.uniqueId)
        val alertLabel = if (alertsOn) "Alerts: OFF" else "Alerts: ON"
        val alertColor = if (alertsOn) NamedTextColor.RED else NamedTextColor.GREEN
        gui.setItem(49, buildItem(Material.REDSTONE, alertLabel, alertColor,
            "Toggle anticheat alert notifications")) { p, _ ->
            if (acAlertToggles.contains(p.uniqueId)) {
                acAlertToggles.remove(p.uniqueId)
                plugin.databaseManager.execute("DELETE FROM ac_alert_toggles WHERE player_uuid = ?", p.uniqueId.toString())
                plugin.commsManager.send(p, Component.text("AC alerts enabled", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
            } else {
                acAlertToggles.add(p.uniqueId)
                plugin.databaseManager.execute("INSERT OR REPLACE INTO ac_alert_toggles (player_uuid) VALUES (?)", p.uniqueId.toString())
                plugin.commsManager.send(p, Component.text("AC alerts disabled", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            }
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            openAnticheatPanel(p)
        }

        // Back (slot 53)
        gui.setItem(53, buildItem(Material.BARRIER, "Back", NamedTextColor.GRAY, "Return to admin panel")) { p, _ ->
            openMainPanel(p)
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        plugin.guiManager.open(admin, gui)
    }

    private fun openViolationsList(admin: Player) {
        val gui = CustomGui(
            Component.text("Top Violations", NamedTextColor.YELLOW)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            27
        )
        gui.fill(FILLER)

        val top = plugin.antiCheatManager.getTopViolators(10)
        for ((index, entry) in top.withIndex()) {
            if (index >= 18) break
            val item = ItemStack(Material.PLAYER_HEAD)
            item.editMeta { meta ->
                meta.displayName(
                    Component.text(entry.first, NamedTextColor.WHITE)
                        .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(listOf(
                    Component.text("Total VL: ${"%.0f".format(entry.second)}", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)
                ))
            }
            gui.setItem(index, item)
        }

        if (top.isEmpty()) {
            gui.setItem(4, buildItem(Material.LIME_WOOL, "No Violations", NamedTextColor.GREEN, "All clear!"))
        }

        // Back
        gui.setItem(22, buildItem(Material.BARRIER, "Back", NamedTextColor.GRAY, "Return to anticheat panel")) { p, _ ->
            openAnticheatPanel(p)
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
        }

        plugin.guiManager.open(admin, gui)
    }

    /** Check if this admin has AC alerts suppressed */
    fun isAcAlertSuppressed(uuid: UUID): Boolean = acAlertToggles.contains(uuid)

    // ── Helpers ──────────────────────────────────────────

    private data class BanEntry(
        val uuid: UUID,
        val name: String,
        val reason: String?,
        val punisherName: String,
        val expiresAt: Long?
    )

    private fun buildItem(material: Material, name: String, color: NamedTextColor, vararg loreLines: String): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(
                Component.text(name, color)
                    .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
            )
            if (loreLines.isNotEmpty()) {
                meta.lore(loreLines.map { line ->
                    Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                })
            }
        }
        return item
    }

    private fun buildPlayerHead(player: Player): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) { meta ->
            meta.owningPlayer = player
            meta.displayName(
                Component.text(player.name, NamedTextColor.WHITE)
                    .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
            )
            val rank = plugin.rankManager.getPlayerRank(player)
            val lore = mutableListOf<Component>()
            if (rank != null) {
                lore.add(plugin.commsManager.parseLegacy(rank.displayTag).decoration(TextDecoration.ITALIC, false))
            }
            lore.add(Component.empty())
            lore.add(Component.text("Click to manage", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)
        }
        return head
    }

    private fun buildServerInfoItem(): ItemStack {
        val item = ItemStack(Material.COMMAND_BLOCK)
        item.editMeta { meta ->
            meta.displayName(
                Component.text("Server Info", NamedTextColor.AQUA)
                    .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
            )

            val runtime = Runtime.getRuntime()
            val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMem = runtime.maxMemory() / 1024 / 1024
            val tps = Bukkit.getTPS()
            val tps1 = String.format("%.1f", tps[0].coerceAtMost(20.0))
            val tps5 = String.format("%.1f", tps[1].coerceAtMost(20.0))
            val onlineCount = Bukkit.getOnlinePlayers().size
            val maxPlayers = Bukkit.getMaxPlayers()

            val lore = mutableListOf<Component>()
            lore.add(Component.empty())
            lore.add(Component.text("TPS (1m/5m): ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("$tps1 / $tps5", tpsColor(tps[0]))))
            lore.add(Component.text("Players: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("$onlineCount/$maxPlayers", NamedTextColor.WHITE)))
            lore.add(Component.text("Memory: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("${usedMem}MB / ${maxMem}MB", NamedTextColor.WHITE)))
            lore.add(Component.text("Worlds: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("${Bukkit.getWorlds().size}", NamedTextColor.WHITE)))
            lore.add(Component.empty())
            lore.add(Component.text("Click to refresh", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))

            meta.lore(lore)
        }
        return item
    }

    private fun tpsColor(tps: Double): TextColor {
        return when {
            tps >= 19.5 -> NamedTextColor.GREEN
            tps >= 17.0 -> NamedTextColor.YELLOW
            tps >= 14.0 -> NamedTextColor.GOLD
            else -> NamedTextColor.RED
        }
    }

    private fun formatTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        if (diff < 0) return "just now"
        val seconds = diff / 1000
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86400}d ago"
        }
    }

    private fun notOnline(admin: Player) {
        plugin.commsManager.send(admin, Component.text("Player is not online!", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
        admin.playSound(admin.location, Sound.ENTITY_VILLAGER_NO, 0.5f, 1.0f)
    }
}
