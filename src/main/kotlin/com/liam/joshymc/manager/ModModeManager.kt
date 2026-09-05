package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.item.impl.spectatorLore
import com.liam.joshymc.item.impl.vanishLore
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.ItemStack
import java.util.Base64
import java.util.UUID

/**
 * Centralized "moderation loadout" — saves/restores a staff member's normal
 * state and swaps in the Moderator Mode hotbar. All 9 tools delegate to the
 * plugin's existing moderation systems (AdminManager, PunishmentManager,
 * VanishCommand, StorageManager, AntiCheatManager) rather than re-implementing
 * them.
 */
class ModModeManager(private val plugin: Joshymc) {

    companion object {
        const val PERM_BASE = "joshymc.modmode"
        const val PERM_EDIT = "joshymc.modmode.edit"

        val HOTBAR_ITEM_IDS = listOf(
            "modmode_punish",
            "modmode_rtp",
            "modmode_freeze",
            "modmode_totemguard",
            "modmode_vanish",
            "modmode_invsee",
            "modmode_spectator",
            "modmode_ecsee",
            "modmode_vault"
        )
    }

    /** Players currently in Moderator Mode this session. */
    private val active = mutableSetOf<UUID>()

    /** Players currently "peeking" spectator mode within Moderator Mode -> the gamemode to restore to. */
    private val spectating = mutableMapOf<UUID, GameMode>()

    fun start() {
        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS modmode_backups (
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
                was_vanished INTEGER NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )

        plugin.logger.info("[ModMode] Moderator Mode manager started.")
    }

    fun stop() {
        active.clear()
        spectating.clear()
    }

    // ---- State checks ----

    fun isModMode(player: Player): Boolean = active.contains(player.uniqueId)

    fun canEdit(player: Player): Boolean = player.hasPermission(PERM_EDIT)

    fun isModTool(item: ItemStack?): Boolean {
        val id = plugin.itemManager.getCustomItemId(item) ?: return false
        return id in HOTBAR_ITEM_IDS
    }

    private fun hasPendingBackup(uuid: UUID): Boolean {
        return plugin.databaseManager.queryFirst(
            "SELECT 1 FROM modmode_backups WHERE uuid = ?", uuid.toString()
        ) { true } ?: false
    }

    // ---- Enable / Disable / Toggle ----

    fun toggle(player: Player) {
        if (isModMode(player)) disable(player) else enable(player)
    }

    fun enable(player: Player) {
        if (isModMode(player)) {
            plugin.commsManager.send(player, Component.text("Moderator Mode is already enabled.", NamedTextColor.YELLOW), CommunicationsManager.Category.ADMIN)
            return
        }

        if (hasPendingBackup(player.uniqueId)) {
            // Never overwrite an unresolved backup — recover it instead.
            plugin.commsManager.send(
                player,
                Component.text("An unresolved Moderator Mode backup was found for your account — restoring it for safety. Run /modmode on again if you still want to enter.", NamedTextColor.RED),
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

        active.add(player.uniqueId)

        if (plugin.config.getBoolean("modmode.auto-vanish", true) && !plugin.vanishCommand.isVanished(player)) {
            plugin.vanishCommand.vanish(player)
        }

        plugin.commsManager.send(player, Component.text("🛡 Moderator Mode enabled.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.4f)
    }

    fun disable(player: Player) {
        if (!isModMode(player) && !hasPendingBackup(player.uniqueId)) {
            plugin.commsManager.send(player, Component.text("Moderator Mode is already disabled.", NamedTextColor.YELLOW), CommunicationsManager.Category.ADMIN)
            return
        }

        spectating.remove(player.uniqueId)
        active.remove(player.uniqueId)
        restoreFromBackup(player, silent = false)
    }

    /** Called on join to recover a moderator who disconnected/crashed while still in Moderator Mode. */
    fun handleJoin(player: Player) {
        if (!hasPendingBackup(player.uniqueId)) return
        active.remove(player.uniqueId)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            restoreFromBackup(player, silent = true)
            plugin.commsManager.send(
                player,
                Component.text("Moderator Mode was still active from your last session — your inventory has been restored.", NamedTextColor.YELLOW),
                CommunicationsManager.Category.ADMIN
            )
        }, 5L)
    }

    fun handleDeath(event: PlayerDeathEvent) {
        val player = event.entity
        if (!isModMode(player)) return
        // Moderator Mode tools must never drop or become obtainable on death;
        // the player's real inventory is already safely stored in the backup table.
        event.drops.clear()
        event.droppedExp = 0
        event.keepInventory = true
    }

    private fun restoreFromBackup(player: Player, silent: Boolean) {
        val uuid = player.uniqueId.toString()

        data class Backup(
            val inv: String, val armor: String, val offhand: String, val slot: Int,
            val xpLevel: Int, val xpProgress: Float, val gameMode: String,
            val allowFlight: Boolean, val wasFlying: Boolean, val wasVanished: Boolean
        )

        val backup = plugin.databaseManager.queryFirst(
            """SELECT inventory_data, armor_data, offhand_data, selected_slot, xp_level, xp_progress,
                      game_mode, allow_flight, was_flying, was_vanished
               FROM modmode_backups WHERE uuid = ?""",
            uuid
        ) { rs ->
            Backup(
                rs.getString("inventory_data"), rs.getString("armor_data"), rs.getString("offhand_data"),
                rs.getInt("selected_slot"), rs.getInt("xp_level"), rs.getFloat("xp_progress"),
                rs.getString("game_mode"), rs.getInt("allow_flight") == 1, rs.getInt("was_flying") == 1,
                rs.getInt("was_vanished") == 1
            )
        }

        // Strip Moderator Mode tools regardless of whether a backup exists.
        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            if (isModTool(item)) player.inventory.setItem(i, null)
        }

        if (backup == null) {
            if (!silent) {
                plugin.commsManager.send(player, Component.text("Moderator Mode disabled.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
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

        if (backup.wasVanished) {
            if (!plugin.vanishCommand.isVanished(player)) plugin.vanishCommand.vanish(player)
        } else {
            if (plugin.vanishCommand.isVanished(player)) plugin.vanishCommand.unvanish(player)
        }

        plugin.databaseManager.execute("DELETE FROM modmode_backups WHERE uuid = ?", uuid)

        if (!silent) {
            plugin.commsManager.send(
                player,
                Component.text("🛡 Moderator Mode disabled. Your inventory has been restored.", NamedTextColor.GREEN),
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
            """INSERT INTO modmode_backups
               (uuid, inventory_data, armor_data, offhand_data, selected_slot, xp_level, xp_progress,
                game_mode, allow_flight, was_flying, was_vanished, timestamp)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            player.uniqueId.toString(), invData, armorData, offhandData, player.inventory.heldItemSlot,
            player.level, player.exp, player.gameMode.name,
            if (player.allowFlight) 1 else 0, if (player.isFlying) 1 else 0,
            if (plugin.vanishCommand.isVanished(player)) 1 else 0,
            System.currentTimeMillis()
        )
    }

    // ---- Serialization helpers (same base64/index-pair pattern as StorageManager/AdminManager) ----

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
            player.inventory.setItem(slot, buildToolStack(player, id))
        }
    }

    fun refreshTool(player: Player, id: String) {
        val slot = HOTBAR_ITEM_IDS.indexOf(id)
        if (slot < 0) return
        player.inventory.setItem(slot, buildToolStack(player, id))
    }

    private fun buildToolStack(player: Player, id: String): ItemStack {
        val stack = plugin.itemManager.getItem(id)?.createItemStack() ?: return ItemStack(Material.BARRIER)
        when (id) {
            "modmode_vanish" -> stack.editMeta { it.lore(vanishLore(plugin.vanishCommand.isVanished(player))) }
            "modmode_spectator" -> stack.editMeta { it.lore(spectatorLore(spectating.containsKey(player.uniqueId))) }
        }
        return stack
    }

    // ---- Tool 2: Random Teleport ----

    fun randomTeleport(moderator: Player) {
        val excludeVanished = plugin.config.getBoolean("modmode.rtp.exclude-vanished", true)
        val excludeStaff = plugin.config.getBoolean("modmode.rtp.exclude-modmode-staff", true)

        val eligible = Bukkit.getOnlinePlayers().filter { candidate ->
            candidate.uniqueId != moderator.uniqueId &&
                candidate.gameMode != GameMode.SPECTATOR &&
                (!excludeStaff || !isModMode(candidate)) &&
                (!excludeVanished || !plugin.vanishCommand.isVanished(candidate))
        }

        if (eligible.isEmpty()) {
            plugin.commsManager.send(moderator, Component.text("No eligible players to teleport to right now.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val target = eligible.random()
        moderator.teleport(target.location)
        plugin.commsManager.send(moderator, Component.text("Teleported to ${target.name}.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        moderator.playSound(moderator.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f)
    }

    // ---- Tool 3: Freeze ----

    fun toggleFreeze(moderator: Player, target: Player) {
        if (!moderator.hasPermission(AdminManager.PERM_MODERATE) && !moderator.hasPermission(AdminManager.PERM_ADMIN)) {
            plugin.commsManager.send(moderator, Component.text("You don't have permission to freeze players.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val nowFrozen = plugin.adminManager.toggleFreeze(target)
        plugin.adminManager.logAction(moderator, if (nowFrozen) "FREEZE" else "UNFREEZE", target)

        if (nowFrozen) {
            plugin.commsManager.send(moderator, Component.text("Froze ${target.name}.", NamedTextColor.AQUA), CommunicationsManager.Category.ADMIN)
            plugin.commsManager.send(target, Component.text("You have been frozen by a staff member!", NamedTextColor.AQUA), CommunicationsManager.Category.ADMIN)
        } else {
            plugin.commsManager.send(moderator, Component.text("Unfroze ${target.name}.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
            plugin.commsManager.send(target, Component.text("You have been unfrozen.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        }
        moderator.playSound(moderator.location, Sound.BLOCK_GLASS_BREAK, 0.5f, 1.5f)
    }

    // ---- Tool 4: Totem Guard ----
    // No dedicated "Totem Guard" anti-cheat check exists in the codebase yet, so this
    // integrates with the closest existing reusable system — AntiCheatManager's
    // per-player violation data — surfacing anything relevant (illegal items,
    // inventory manipulation, kill aura, etc.) plus what the target is currently holding.

    fun showTotemGuard(moderator: Player, target: Player) {
        plugin.commsManager.send(moderator, Component.text("── Totem Guard: ${target.name} ──", NamedTextColor.GOLD), CommunicationsManager.Category.ADMIN)

        val violations = plugin.antiCheatManager.getPlayerViolations(target.uniqueId)
        if (violations.isEmpty()) {
            plugin.commsManager.send(moderator, Component.text("No active anticheat flags.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        } else {
            for ((check, vl) in violations) {
                plugin.commsManager.send(
                    moderator,
                    Component.text("${check.displayName}: ", NamedTextColor.GRAY).append(Component.text("%.1f".format(vl), NamedTextColor.RED)),
                    CommunicationsManager.Category.ADMIN
                )
            }
        }

        plugin.commsManager.send(
            moderator,
            Component.text("Main hand: ", NamedTextColor.GRAY).append(Component.text(target.inventory.itemInMainHand.type.name, NamedTextColor.WHITE)),
            CommunicationsManager.Category.ADMIN
        )
        plugin.commsManager.send(
            moderator,
            Component.text("Off hand: ", NamedTextColor.GRAY).append(Component.text(target.inventory.itemInOffHand.type.name, NamedTextColor.WHITE)),
            CommunicationsManager.Category.ADMIN
        )
    }

    // ---- Tool 5: Vanish ----

    fun toggleVanish(player: Player) {
        if (plugin.vanishCommand.isVanished(player)) {
            plugin.vanishCommand.unvanish(player)
            plugin.commsManager.send(player, Component.text("Vanish disabled.", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
        } else {
            plugin.vanishCommand.vanish(player)
            plugin.commsManager.send(player, Component.text("Vanish enabled.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        }
        refreshTool(player, "modmode_vanish")
        player.playSound(player.location, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 1.2f)
    }

    // ---- Tool 6: Invsee ----

    fun openInvsee(moderator: Player, target: Player) {
        if (canEdit(moderator)) {
            moderator.openInventory(target.inventory)
        } else {
            // AdminManager.openInvsee clones items into a CustomGui with no click
            // handlers — GuiManager cancels every click on it, so this is read-only.
            plugin.adminManager.openInvsee(moderator, target)
        }
        plugin.adminManager.logAction(moderator, "MODMODE_INVSEE", target)
    }

    // ---- Tool 7: Spectator ----

    fun toggleSpectator(player: Player) {
        val uuid = player.uniqueId
        val saved = spectating.remove(uuid)
        if (saved != null) {
            player.gameMode = saved
            plugin.commsManager.send(player, Component.text("Spectator mode disabled.", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
        } else {
            spectating[uuid] = player.gameMode
            player.gameMode = GameMode.SPECTATOR
            plugin.commsManager.send(player, Component.text("Spectator mode enabled.", NamedTextColor.AQUA), CommunicationsManager.Category.ADMIN)
        }
        refreshTool(player, "modmode_spectator")
        player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.5f)
    }

    // ---- Tool 8: ECSee ----

    fun openEnderChest(moderator: Player, target: Player) {
        if (canEdit(moderator)) {
            moderator.openInventory(target.enderChest)
            plugin.adminManager.logAction(moderator, "MODMODE_ECSEE_EDIT", target)
            return
        }

        val gui = CustomGui(
            Component.text("EnderChest: ${target.name}", NamedTextColor.DARK_PURPLE).decoration(TextDecoration.ITALIC, false),
            27
        )
        val contents = target.enderChest.contents
        for (i in contents.indices) {
            val item = contents[i] ?: continue
            if (i < 27) gui.inventory.setItem(i, item.clone())
        }
        plugin.guiManager.open(moderator, gui)
        plugin.adminManager.logAction(moderator, "MODMODE_ECSEE_VIEW", target)
    }

    // ---- Tool 9: Player Vault ----

    fun openVault(moderator: Player, target: Player) {
        val maxVaults = plugin.storageManager.getMaxVaults(target)
        if (maxVaults <= 1) {
            openVaultNumber(moderator, target, 1)
            return
        }

        val gui = CustomGui(
            Component.text("Vaults: ${target.name}", TextColor.color(0x55FFFF)).decoration(TextDecoration.ITALIC, false),
            27
        )
        for (n in 1..maxVaults.coerceAtMost(27)) {
            val item = ItemStack(Material.CHEST)
            item.editMeta { meta ->
                meta.displayName(Component.text("Vault #$n", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
            }
            val num = n
            gui.setItem(n - 1, item) { p, _ -> openVaultNumber(p, target, num) }
        }
        plugin.guiManager.open(moderator, gui)
    }

    private fun openVaultNumber(moderator: Player, target: Player, number: Int) {
        if (canEdit(moderator)) {
            plugin.storageManager.openVaultAsAdmin(moderator, target.uniqueId, target.name, number)
            plugin.adminManager.logAction(moderator, "MODMODE_VAULT_EDIT", target, "#$number")
            return
        }

        val gui = CustomGui(
            Component.text("Vault #$number: ${target.name}", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false),
            54
        )
        for ((slot, item) in plugin.storageManager.getVaultSnapshot(target.uniqueId, number)) {
            if (slot in 0 until 54) gui.inventory.setItem(slot, item.clone())
        }
        plugin.guiManager.open(moderator, gui)
        plugin.adminManager.logAction(moderator, "MODMODE_VAULT_VIEW", target, "#$number")
    }

    // ---- Tool 1: Punish ----

    fun openPunish(moderator: Player, target: Player) {
        // Reuses AdminManager's existing punishment panel wholesale — every button
        // (kick/ban/mute/warn/freeze) is already permission-gated there by
        // PERM_ADMIN/PERM_MODERATE/PERM_HELPER, so Moderator Mode never grants
        // any authority beyond what the staff member's existing rank permissions allow.
        plugin.adminManager.openPlayerPanel(moderator, target)
    }
}
