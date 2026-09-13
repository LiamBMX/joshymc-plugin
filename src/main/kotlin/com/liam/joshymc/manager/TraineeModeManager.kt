package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.Base64
import java.util.UUID

/**
 * Limited "observation + investigation" staff mode for Trainees. Unlike
 * [ModModeManager] this never grants Creative, punishment tools, or the
 * ability to modify anyone's inventory — every tool is read-only or
 * self-only. Save/restore + reconnect/restart safety mirror ModModeManager's
 * DB-backed backup pattern (own table, so a stuck Trainee Mode session never
 * shares state with a stuck Moderator Mode session).
 */
class TraineeModeManager(private val plugin: Joshymc) {

    companion object {
        const val PERM_BASE = "joshymc.traineemode"

        val HOTBAR_ITEM_IDS = listOf(
            "trainee_inspector",
            "trainee_invsee",
            "trainee_tp",
            "trainee_history",
            "trainee_staffchat",
            "trainee_reports"
        )
    }

    /** Players currently in Trainee Mode this session. */
    private val active = mutableSetOf<UUID>()

    private val filler: ItemStack by lazy {
        ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
    }

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

        plugin.logger.info("[TraineeMode] Trainee Mode manager started.")
    }

    fun stop() {
        active.clear()
    }

    // ---- State checks ----

    fun isTraineeMode(player: Player): Boolean = active.contains(player.uniqueId)

    fun isTraineeTool(item: ItemStack?): Boolean {
        val id = plugin.itemManager.getCustomItemId(item) ?: return false
        return id in HOTBAR_ITEM_IDS
    }

    private fun hasPendingBackup(uuid: UUID): Boolean {
        return plugin.databaseManager.queryFirst(
            "SELECT 1 FROM traineemode_backups WHERE uuid = ?", uuid.toString()
        ) { true } ?: false
    }

    // ---- Enable / Disable / Toggle ----

    fun toggle(player: Player) {
        if (isTraineeMode(player)) disable(player) else enable(player)
    }

    fun enable(player: Player) {
        if (isTraineeMode(player)) {
            plugin.commsManager.send(player, Component.text("Trainee Mode is already enabled.", NamedTextColor.YELLOW), CommunicationsManager.Category.ADMIN)
            return
        }

        if (hasPendingBackup(player.uniqueId)) {
            // Never overwrite an unresolved backup — recover it instead.
            plugin.commsManager.send(
                player,
                Component.text("An unresolved Trainee Mode backup was found for your account — restoring it for safety. Run /tmode on again if you still want to enter.", NamedTextColor.RED),
                CommunicationsManager.Category.ADMIN
            )
            restoreFromBackup(player, silent = false)
            return
        }

        saveBackup(player)

        player.inventory.clear()
        player.inventory.setArmorContents(arrayOfNulls(4))
        player.inventory.setItemInOffHand(null)

        giveHotbar(player)

        // Adventure (not Creative) — no block break/place, no creative menu, no free items.
        player.gameMode = GameMode.ADVENTURE
        player.allowFlight = false
        player.isFlying = false

        active.add(player.uniqueId)

        plugin.commsManager.send(player, Component.text("Trainee Mode enabled.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.4f)
    }

    fun disable(player: Player) {
        if (!isTraineeMode(player) && !hasPendingBackup(player.uniqueId)) {
            plugin.commsManager.send(player, Component.text("Trainee Mode is already disabled.", NamedTextColor.YELLOW), CommunicationsManager.Category.ADMIN)
            return
        }

        active.remove(player.uniqueId)
        restoreFromBackup(player, silent = false)
    }

    /** Called on join to recover a Trainee who disconnected/crashed while still in Trainee Mode. */
    fun handleJoin(player: Player) {
        if (!hasPendingBackup(player.uniqueId)) return
        active.remove(player.uniqueId)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            restoreFromBackup(player, silent = true)
            plugin.commsManager.send(
                player,
                Component.text("Trainee Mode was still active from your last session — your inventory has been restored.", NamedTextColor.YELLOW),
                CommunicationsManager.Category.ADMIN
            )
        }, 5L)
    }

    fun handleDeath(event: PlayerDeathEvent) {
        val player = event.entity
        if (!isTraineeMode(player)) return
        // Trainee Mode tools must never drop or become obtainable on death; the
        // player's real inventory is already safely stored in the backup table.
        event.drops.clear()
        event.droppedExp = 0
        event.keepInventory = true
    }

    private fun restoreFromBackup(player: Player, silent: Boolean) {
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
        }

        // Strip Trainee Mode tools regardless of whether a backup exists.
        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            if (isTraineeTool(item)) player.inventory.setItem(i, null)
        }

        if (backup == null) {
            if (!silent) {
                plugin.commsManager.send(player, Component.text("Trainee Mode disabled.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
            }
            return
        }

        player.inventory.clear()
        deserializeInto(player, backup.inv)
        player.inventory.setArmorContents(deserializeArray(backup.armor, 4))
        player.inventory.setItemInOffHand(deserializeArray(backup.offhand, 1)[0])
        player.inventory.heldItemSlot = backup.slot.coerceIn(0, 8)

        player.level = backup.xpLevel
        player.exp = backup.xpProgress.coerceIn(0f, 0.999f)

        val gameMode = try {
            GameMode.valueOf(backup.gameMode)
        } catch (_: IllegalArgumentException) {
            GameMode.SURVIVAL
        }
        player.gameMode = gameMode
        player.allowFlight = backup.allowFlight
        player.isFlying = backup.wasFlying && backup.allowFlight

        plugin.databaseManager.execute("DELETE FROM traineemode_backups WHERE uuid = ?", uuid)

        if (!silent) {
            plugin.commsManager.send(
                player,
                Component.text("Trainee Mode disabled. Your inventory has been restored.", NamedTextColor.GREEN),
                CommunicationsManager.Category.ADMIN
            )
        }
        player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 0.8f)
    }

    private fun saveBackup(player: Player) {
        val invData = serializeArray(player.inventory.contents)
        val armorData = serializeArray(player.inventory.armorContents)
        val offhandData = serializeArray(arrayOf(player.inventory.itemInOffHand))

        plugin.databaseManager.execute(
            """INSERT INTO traineemode_backups
               (uuid, inventory_data, armor_data, offhand_data, selected_slot, xp_level, xp_progress,
                game_mode, allow_flight, was_flying, timestamp)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            player.uniqueId.toString(), invData, armorData, offhandData, player.inventory.heldItemSlot,
            player.level, player.exp, player.gameMode.name,
            if (player.allowFlight) 1 else 0, if (player.isFlying) 1 else 0,
            System.currentTimeMillis()
        )
    }

    // ---- Serialization helpers (same base64/index-pair pattern as ModModeManager) ----

    private fun serializeArray(items: Array<ItemStack?>): String {
        val parts = mutableListOf<String>()
        for (i in items.indices) {
            val item = items[i]
            if (item != null && item.type != Material.AIR) {
                parts.add("$i:${Base64.getEncoder().encodeToString(item.serializeAsBytes())}")
            }
        }
        return parts.joinToString(";")
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

    private fun deserializeInto(player: Player, data: String) {
        if (data.isBlank()) return
        for (entry in data.split(";")) {
            val colon = entry.indexOf(':')
            if (colon < 0) continue
            val slot = entry.substring(0, colon).toIntOrNull() ?: continue
            if (slot !in 0 until player.inventory.size) continue
            try {
                player.inventory.setItem(slot, ItemStack.deserializeBytes(Base64.getDecoder().decode(entry.substring(colon + 1))))
            } catch (_: Exception) {
            }
        }
    }

    // ---- Hotbar ----

    fun giveHotbar(player: Player) {
        for ((slot, id) in HOTBAR_ITEM_IDS.withIndex()) {
            val stack = plugin.itemManager.getItem(id)?.createItemStack() ?: ItemStack(Material.BARRIER)
            player.inventory.setItem(slot, stack)
        }
    }

    // ---- Shared GUI building blocks ----

    private fun infoLine(label: String, value: String): Component =
        Component.text("$label: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            .append(Component.text(value, NamedTextColor.WHITE))

    private fun simpleItem(material: Material, name: String, color: NamedTextColor): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(Component.text(name, color).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
        }
        return item
    }

    private fun closeItem(): ItemStack = simpleItem(Material.BARRIER, "Close", NamedTextColor.RED)

    private fun formatTimeAgo(millis: Long): String {
        val seconds = (System.currentTimeMillis() - millis) / 1000
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86400}d ago"
        }
    }

    // ---- Tool 1: Player Inspector (read-only info) ----

    fun openPlayerInspector(trainee: Player, target: Player) {
        val gui = CustomGui(
            Component.text("Inspect: ${target.name}", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            27
        )
        gui.fill(filler)

        val head = ItemStack(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) { meta ->
            meta.owningPlayer = target
            meta.displayName(Component.text(target.name, NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))

            val ban = plugin.punishmentManager.isBanned(target.uniqueId)
            val mute = plugin.punishmentManager.isMuted(target.uniqueId)
            val warnCount = plugin.punishmentManager.getActiveWarnings(target.uniqueId).size
            val team = plugin.teamManager.getPlayerTeam(target.uniqueId)
            val playtime = plugin.playtimeManager.formatPlaytime(plugin.playtimeManager.getPlaytime(target.uniqueId))

            val lore = mutableListOf<Component>()
            lore.add(Component.empty())
            lore.add(infoLine("UUID", target.uniqueId.toString()))
            lore.add(infoLine("World", target.world.name))
            lore.add(infoLine("Coords", "%.0f, %.0f, %.0f".format(target.location.x, target.location.y, target.location.z)))
            lore.add(infoLine("Gamemode", target.gameMode.name))
            lore.add(infoLine("Health", "%.1f / 20.0".format(target.health)))
            lore.add(infoLine("Ping", target.ping.toString()))
            lore.add(infoLine("Playtime", playtime))
            lore.add(infoLine("Team", team ?: "None"))
            lore.add(infoLine("Vanished", if (plugin.vanishCommand.isVanished(target)) "Yes" else "No"))
            lore.add(infoLine("Muted", if (mute != null) "Yes" else "No"))
            lore.add(infoLine("Banned", if (ban != null) "Yes" else "No"))
            lore.add(infoLine("Active Warnings", warnCount.toString()))
            meta.lore(lore)
        }
        gui.setItem(13, head)
        gui.setItem(22, closeItem()) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(trainee, gui)
    }

    // ---- Tool 2: Inventory Inspector (read-only clone, no click handlers) ----

    fun openInventoryInspector(trainee: Player, target: Player) {
        val gui = CustomGui(
            Component.text("Inv Inspect: ${target.name}", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        gui.fill(filler)

        // Same slot layout as AdminManager.openInvsee — clone only, no handlers, so
        // GuiManager's blanket top-inventory click cancellation makes this fully inert.
        val contents = target.inventory.contents
        for (i in contents.indices) {
            val item = contents[i] ?: continue
            if (i < 36) {
                val guiSlot = if (i < 9) i + 36 else i - 9
                if (guiSlot < 45) gui.inventory.setItem(guiSlot, item.clone())
            }
        }
        val armor = target.inventory.armorContents
        if (armor[3] != null) gui.inventory.setItem(0, armor[3]!!.clone())
        if (armor[2] != null) gui.inventory.setItem(1, armor[2]!!.clone())
        if (armor[1] != null) gui.inventory.setItem(2, armor[1]!!.clone())
        if (armor[0] != null) gui.inventory.setItem(3, armor[0]!!.clone())
        val offhand = target.inventory.itemInOffHand
        if (offhand.type != Material.AIR) gui.inventory.setItem(5, offhand.clone())

        gui.setItem(49, closeItem()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(trainee, gui)
    }

    // ---- Tool 3: Teleport to Player (self only) ----

    fun openTeleportMenu(trainee: Player) {
        val online = Bukkit.getOnlinePlayers().filter {
            it.uniqueId != trainee.uniqueId && !plugin.vanishCommand.isVanished(it)
        }

        val gui = CustomGui(
            Component.text("Teleport to Player", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        gui.fill(filler)

        for ((index, target) in online.withIndex()) {
            if (index >= 45) break
            val head = ItemStack(Material.PLAYER_HEAD)
            head.editMeta(SkullMeta::class.java) { meta ->
                meta.owningPlayer = target
                meta.displayName(Component.text(target.name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
                meta.lore(listOf(Component.text("Click to teleport yourself here.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)))
            }
            val targetUuid = target.uniqueId
            gui.setItem(index, head) { p, _ ->
                p.closeInventory()
                val current = Bukkit.getPlayer(targetUuid)
                if (current == null) {
                    plugin.commsManager.send(p, Component.text("That player is no longer online.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
                    return@setItem
                }
                // Trainees may only move themselves — this never touches the target's location/state.
                p.teleport(current.location)
                plugin.commsManager.send(p, Component.text("Teleported to ${current.name}.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
                p.playSound(p.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f)
            }
        }

        gui.setItem(49, closeItem()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(trainee, gui)
    }

    // ---- Tool 4: Punishment History (read-only) ----

    fun openPunishmentHistory(trainee: Player, target: OfflinePlayer, page: Int = 0) {
        val targetName = target.name ?: "Unknown"
        val gui = CustomGui(
            Component.text("History: $targetName", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        gui.fill(filler)

        val history = plugin.punishmentManager.getHistory(target.uniqueId)
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
                val stateLabel = if (record.active) " (Active)" else " (Inactive)"
                meta.displayName(
                    Component.text("${record.type}$stateLabel", color)
                        .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
                )
                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                lore.add(infoLine("By", record.punisherName))
                if (record.reason != null) lore.add(infoLine("Reason", record.reason))
                lore.add(infoLine("Date", formatTimeAgo(record.createdAt)))
                if (record.expiresAt != null) {
                    if (record.expiresAt > System.currentTimeMillis()) {
                        lore.add(infoLine("Expires", PunishmentManager.formatDuration(record.expiresAt - System.currentTimeMillis())))
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

        if (history.isEmpty()) {
            gui.setItem(22, simpleItem(Material.BARRIER, "No punishment history.", NamedTextColor.GRAY))
        }

        if (currentPage > 0) {
            gui.setItem(48, simpleItem(Material.ARROW, "Previous Page", NamedTextColor.GRAY)) { p, _ ->
                openPunishmentHistory(p, target, currentPage - 1)
            }
        }
        if (currentPage < totalPages) {
            gui.setItem(50, simpleItem(Material.ARROW, "Next Page", NamedTextColor.GRAY)) { p, _ ->
                openPunishmentHistory(p, target, currentPage + 1)
            }
        }
        gui.setItem(49, closeItem()) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(trainee, gui)
    }

    // ---- Tool 5: Staff Chat (delegates to the existing system) ----

    fun toggleStaffChat(player: Player) {
        plugin.staffChatManager.toggle(player)
    }

    // ---- Tool 6: Reports (view-only, teleport-to-reported-player allowed) ----

    private data class ReportEntry(
        val id: Int, val reporterName: String, val targetName: String,
        val reason: String, val createdAt: Long, val resolved: Boolean
    )

    fun openReports(trainee: Player) {
        val reports = plugin.databaseManager.query(
            "SELECT id, reporter_name, target_name, reason, created_at, resolved FROM reports ORDER BY created_at DESC LIMIT 45"
        ) { rs ->
            ReportEntry(
                rs.getInt("id"), rs.getString("reporter_name"), rs.getString("target_name"),
                rs.getString("reason"), rs.getLong("created_at"), rs.getInt("resolved") == 1
            )
        }

        val gui = CustomGui(
            Component.text("Reports", NamedTextColor.RED).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            54
        )
        gui.fill(filler)

        for ((index, report) in reports.withIndex()) {
            if (index >= 45) break
            val item = ItemStack(if (report.resolved) Material.LIME_DYE else Material.REDSTONE_TORCH)
            item.editMeta { meta ->
                meta.displayName(Component.text("#${report.id} ${report.targetName}", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                lore.add(infoLine("Reported by", report.reporterName))
                lore.add(infoLine("Reason", report.reason))
                lore.add(infoLine("Status", if (report.resolved) "Resolved" else "Open"))
                lore.add(infoLine("Date", formatTimeAgo(report.createdAt)))
                lore.add(Component.empty())
                lore.add(Component.text("Click to teleport yourself to ${report.targetName} if online.", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
                meta.lore(lore)
            }
            val targetName = report.targetName
            gui.setItem(index, item) { p, _ ->
                p.closeInventory()
                val online = Bukkit.getPlayer(targetName)
                if (online == null) {
                    plugin.commsManager.send(p, Component.text("$targetName is not online.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
                    return@setItem
                }
                p.teleport(online.location)
                plugin.commsManager.send(p, Component.text("Teleported to ${online.name}.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
            }
        }

        if (reports.isEmpty()) {
            gui.setItem(22, simpleItem(Material.BARRIER, "No reports found.", NamedTextColor.GRAY))
        }

        gui.setItem(49, closeItem()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(trainee, gui)
    }
}
