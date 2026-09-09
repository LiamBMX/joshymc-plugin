package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.UUID

class KitManager(private val plugin: Joshymc) {

    companion object {
        val KIT_GUI_TITLE: Component = Component.text("KITS", TextColor.color(0x55FFFF))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private const val CREATE_KIT_TITLE_PREFIX = "Creating Kit: "
        private const val EDIT_KIT_TITLE_PREFIX = "Editing Kit: "

        private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        private val BORDER = ItemStack(Material.CYAN_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }

        // /editkit GUI layout: content area mirrors the 36-slot /createkit inventory
        // (so slot keys stay identical), plus a bottom control row.
        const val EDIT_GUI_SIZE = 45
        val EDIT_CONTENT_SLOTS = 0..35
        val EDIT_CONTROL_SLOTS = 36..44
        const val EDIT_INFO_SLOT = 36
        const val EDIT_RELOAD_SLOT = 40
        const val EDIT_SAVE_SLOT = 42
        const val EDIT_CANCEL_SLOT = 44

        /**
         * Shape-based centering for the /kit content area (rows 1-3, cols 1-7 of the
         * 45-slot GUI), mirroring the compact-formation approach used by the crate
         * preview/pick-a-reward GUIs (see CrateManager.CrateLayout).
         */
        private object KitLayout {
            const val CONTENT_ROWS = 3
            const val CONTENT_COLS = 7
            const val CAPACITY = CONTENT_ROWS * CONTENT_COLS

            /** Column indices (0..6) that symmetrically center [rowLen] items within a 7-wide row. */
            private fun columnIndices(rowLen: Int): List<Int> = when (rowLen) {
                0 -> emptyList()
                1 -> listOf(3)
                2 -> listOf(2, 4)
                3 -> listOf(2, 3, 4)
                4 -> listOf(1, 2, 4, 5)
                5 -> listOf(1, 2, 3, 4, 5)
                6 -> listOf(0, 1, 2, 4, 5, 6)
                else -> (0 until CONTENT_COLS).toList()
            }

            /** Per-row item counts (top to bottom), balanced across as few rows as needed. */
            private fun rowCounts(count: Int): List<Int> {
                if (count <= 0) return emptyList()
                if (count <= CONTENT_COLS) return listOf(count)
                val capped = count.coerceAtMost(CAPACITY)
                val rows = ((capped - 1) / CONTENT_COLS + 1).coerceAtMost(CONTENT_ROWS)
                val base = capped / rows
                val remainder = capped % rows
                return (0 until rows).map { r -> if (r < remainder) base + 1 else base }
            }

            /** Absolute GUI slots (within the 45-slot inventory) for [count] kit icons, centered. */
            fun slots(count: Int): List<Int> {
                val rows = rowCounts(count)
                val verticalOffset = (CONTENT_ROWS - rows.size).coerceAtLeast(0) / 2
                val result = mutableListOf<Int>()
                for ((i, rowLen) in rows.withIndex()) {
                    val physicalRow = 1 + verticalOffset + i
                    for (col in columnIndices(rowLen)) {
                        result.add(physicalRow * 9 + (1 + col))
                    }
                }
                return result
            }
        }

        /** Capitalizes each word of a kit's internal id for display only (e.g. "trail_blazer" -> "Trail Blazer"). */
        fun displayName(kitName: String): String =
            kitName.split('_', ' ').filter { it.isNotEmpty() }
                .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
    }

    data class KitDef(
        val name: String,
        val icon: Material,
        val cooldownHours: Int,
        val permission: String,
        val items: Map<Int, ItemStack>
    )

    data class EditSession(
        val kitName: String,
        val editorId: UUID,
        val inventory: Inventory,
        val originalItems: Map<Int, ItemStack>,
        val overflowItems: Map<Int, ItemStack>
    )

    private val editSessions = mutableMapOf<UUID, EditSession>()
    private val editLocks = mutableMapOf<String, UUID>()

    private var kitsFile: File = plugin.configFile("kits.yml")
    private var kitsConfig: YamlConfiguration = YamlConfiguration()
    private val kits = mutableMapOf<String, KitDef>()

    fun start() {
        // Create cooldown table
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS kit_cooldowns (
                uuid TEXT NOT NULL,
                kit TEXT NOT NULL,
                claimed_at INTEGER NOT NULL,
                PRIMARY KEY (uuid, kit)
            )
        """.trimIndent())

        // Load kits from YAML
        loadKits()

        plugin.logger.info("[KitManager] Started with ${kits.size} kit(s).")
    }

    /** Force-closes any open /editkit sessions and releases their locks. Called on disable/reload. */
    fun stop() {
        for (session in editSessions.values.toList()) {
            val editor = Bukkit.getPlayer(session.editorId) ?: continue
            if (editor.openInventory.topInventory == session.inventory) {
                plugin.commsManager.send(editor, Component.text("Kit editor closed (server reloading). No changes were saved.", NamedTextColor.YELLOW))
                editor.closeInventory()
            }
        }
        editSessions.clear()
        editLocks.clear()
    }

    private fun loadKits() {
        kits.clear()

        // First-run: extract the bundled kits.yml from the jar.
        if (!kitsFile.exists()) {
            try {
                plugin.saveResource("kits.yml", false)
            } catch (_: IllegalArgumentException) {
                kitsFile.parentFile.mkdirs()
                kitsFile.createNewFile()
            }
            kitsFile = plugin.configFile("kits.yml")
        }
        parseKitsFile()

        // Self-heal: if the on-disk file is missing a `kits:` section or contains
        // zero kits (empty file, corrupted edit, legacy install from before we
        // shipped defaults), overwrite it from the bundled resource and retry.
        if (kits.isEmpty()) {
            plugin.logger.warning(
                "[KitManager] No kits parsed from ${kitsFile.path} (size=${kitsFile.length()}). " +
                "Re-extracting bundled kits.yml from the jar."
            )
            try {
                plugin.saveResource("kits.yml", true)
                kitsFile = plugin.configFile("kits.yml")
                parseKitsFile()
            } catch (e: Exception) {
                plugin.logger.warning("[KitManager] Failed to re-extract kits.yml: ${e.message}")
            }
        }
    }

    private fun parseKitsFile() {
        kits.clear()
        kitsConfig = YamlConfiguration.loadConfiguration(kitsFile)

        val kitsSection = kitsConfig.getConfigurationSection("kits")
        if (kitsSection == null) {
            plugin.logger.warning("[KitManager] kits.yml has no 'kits:' root section (path=${kitsFile.path}).")
            return
        }
        for (name in kitsSection.getKeys(false)) {
            val section = kitsSection.getConfigurationSection(name) ?: continue
            val iconStr = section.getString("icon", "CHEST") ?: "CHEST"
            val icon = try { Material.valueOf(iconStr) } catch (_: Exception) { Material.CHEST }
            val cooldownHours = section.getInt("cooldown-hours", 72)
            val permission = section.getString("permission", "joshymc.kit.$name") ?: "joshymc.kit.$name"

            val items = mutableMapOf<Int, ItemStack>()
            val itemsSection = section.getConfigurationSection("items")
            if (itemsSection != null) {
                for (slotKey in itemsSection.getKeys(false)) {
                    val slot = slotKey.toIntOrNull() ?: continue
                    val itemStack = try {
                        itemsSection.getItemStack(slotKey)
                    } catch (e: Exception) {
                        plugin.logger.warning("[KitManager] Failed to deserialize $name slot $slotKey: ${e.message}")
                        null
                    } ?: continue
                    items[slot] = itemStack
                }
            }

            kits[name] = KitDef(name, icon, cooldownHours, permission, items)
        }
    }

    fun saveKit(name: String, icon: Material, cooldownHours: Int, permission: String, items: Map<Int, ItemStack>) {
        val path = "kits.$name"
        kitsConfig.set("$path.icon", icon.name)
        kitsConfig.set("$path.cooldown-hours", cooldownHours)
        kitsConfig.set("$path.permission", permission)

        // Clear old items
        kitsConfig.set("$path.items", null)
        for ((slot, item) in items) {
            kitsConfig.set("$path.items.$slot", item)
        }

        kitsConfig.save(kitsFile)
        kits[name] = KitDef(name, icon, cooldownHours, permission, items)
    }

    fun deleteKit(name: String): Boolean {
        if (!kits.containsKey(name)) return false
        kitsConfig.set("kits.$name", null)
        kitsConfig.save(kitsFile)
        kits.remove(name)
        return true
    }

    fun getKitNames(): List<String> = kits.keys.toList()

    fun getKit(name: String): KitDef? = kits[name]

    fun claimKit(player: Player, name: String): Boolean {
        val kit = kits[name] ?: return false

        if (!player.hasPermission(kit.permission)) {
            plugin.commsManager.send(player, Component.text("You don't have permission to use this kit.", NamedTextColor.RED))
            return false
        }

        if (!canClaim(player, name)) {
            val remaining = getCooldownRemaining(player, name)
            val formatted = formatCooldown(remaining)
            plugin.commsManager.send(player, Component.text("Kit '$name' is on cooldown: $formatted remaining.", NamedTextColor.RED))
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
            return false
        }

        // Give items
        for ((slot, item) in kit.items) {
            val clone = item.clone()
            if (slot < player.inventory.size && player.inventory.getItem(slot) == null) {
                player.inventory.setItem(slot, clone)
            } else {
                val leftover = player.inventory.addItem(clone)
                for ((_, remaining) in leftover) {
                    player.world.dropItemNaturally(player.location, remaining)
                }
            }
        }

        // Set cooldown
        val now = System.currentTimeMillis()
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO kit_cooldowns (uuid, kit, claimed_at) VALUES (?, ?, ?)",
            player.uniqueId.toString(), name, now
        )

        plugin.commsManager.send(player, Component.text("Kit '$name' claimed!", NamedTextColor.GREEN))
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f)
        return true
    }

    fun getCooldownRemaining(player: Player, name: String): Long {
        // Bypass permissions: global or per-kit
        if (player.hasPermission("joshymc.kit.bypass.*") ||
            player.hasPermission("joshymc.kit.bypass.$name") ||
            player.hasPermission("joshymc.kit.cooldown.bypass")) {
            return 0L
        }

        val kit = kits[name] ?: return 0L
        val claimedAt = plugin.databaseManager.queryFirst(
            "SELECT claimed_at FROM kit_cooldowns WHERE uuid = ? AND kit = ?",
            player.uniqueId.toString(), name
        ) { rs -> rs.getLong("claimed_at") } ?: return 0L

        val cooldownMs = kit.cooldownHours.toLong() * 3600_000L
        val elapsed = System.currentTimeMillis() - claimedAt
        val remaining = cooldownMs - elapsed
        return if (remaining > 0) remaining else 0L
    }

    fun canClaim(player: Player, name: String): Boolean {
        return getCooldownRemaining(player, name) <= 0L
    }

    /** True if the player has any kit cooldown bypass permission for this kit. */
    fun hasBypass(player: Player, name: String): Boolean {
        return player.hasPermission("joshymc.kit.bypass.*") ||
                player.hasPermission("joshymc.kit.bypass.$name") ||
                player.hasPermission("joshymc.kit.cooldown.bypass")
    }

    fun openKitGui(player: Player) {
        if (kits.isEmpty()) {
            // Likely saveResource never ran (file existed but empty, e.g. legacy install).
            // Attempt one more load pass so `/kit` can self-recover without /joshymc reload.
            plugin.logger.warning("[KitManager] openKitGui: no kits in memory; forcing reload.")
            loadKits()
        }
        plugin.logger.info(
            "[KitManager] openKitGui for ${player.name}: ${kits.size} kit(s) known, " +
            "items/kit=${kits.values.joinToString(",") { "${it.name}=${it.items.size}" }}"
        )
        val size = 45 // 5 rows
        val gui = CustomGui(KIT_GUI_TITLE, size)

        // Fill with black glass
        for (i in 0 until size) gui.inventory.setItem(i, FILLER.clone())
        // Border with cyan glass (top and bottom rows)
        for (i in 0..8) { gui.inventory.setItem(i, BORDER.clone()); gui.inventory.setItem(36 + i, BORDER.clone()) }
        // Side borders (rows 1-3)
        for (row in 1..3) { gui.inventory.setItem(row * 9, BORDER.clone()); gui.inventory.setItem(row * 9 + 8, BORDER.clone()) }

        // Close button, centered on the bottom control row.
        val close = ItemStack(Material.BARRIER)
        close.editMeta { it.displayName(Component.text("Close", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)) }
        gui.setItem(40, close) { p, _ -> p.closeInventory() }

        // Centered, shape-based icon placement (see KitLayout) so the layout stays
        // balanced whether there are 1 or 21 kits, instead of a fixed grid with gaps.
        val slots = KitLayout.slots(kits.size)

        var rendered = 0
        for ((idx, kitDef) in kits.values.withIndex()) {
            if (idx >= slots.size) break
            val slot = slots[idx]

            try {
                renderKitIcon(gui, slot, player, kitDef)
                rendered++
            } catch (e: Exception) {
                plugin.logger.warning("[KitManager] Failed to render kit '${kitDef.name}' at slot $slot: ${e.message}")
                e.printStackTrace()
            }
        }
        if (rendered < kits.size) {
            plugin.logger.warning("[KitManager] Rendered $rendered/${kits.size} kits in GUI (others failed — see stack traces above).")
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun renderKitIcon(gui: CustomGui, slot: Int, player: Player, kitDef: KitDef) {
        val onCooldown = !canClaim(player, kitDef.name)
        val hasPermission = player.hasPermission(kitDef.permission)
        val ready = hasPermission && !onCooldown
        val locked = !hasPermission

        val item = ItemStack(kitDef.icon)

        item.editMeta { meta ->
                val nameColor = when {
                    locked -> NamedTextColor.GRAY
                    onCooldown -> NamedTextColor.GOLD
                    else -> NamedTextColor.AQUA
                }
                meta.displayName(
                    Component.text(displayName(kitDef.name), nameColor)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )

                val lore = mutableListOf<Component>()
                lore.add(Component.empty())

                lore.add(
                    Component.text("Cooldown: ", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("${kitDef.cooldownHours}h", NamedTextColor.WHITE))
                )

                val statusValue = when {
                    locked -> Component.text("Locked", NamedTextColor.RED)
                    onCooldown -> Component.text("${formatCooldown(getCooldownRemaining(player, kitDef.name))} remaining", NamedTextColor.GOLD)
                    else -> Component.text("Ready", NamedTextColor.GREEN)
                }
                lore.add(
                    Component.text("Status: ", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                        .append(statusValue.decoration(TextDecoration.ITALIC, false))
                )

                lore.add(Component.empty())
                if (ready) {
                    lore.add(Component.text("Left-Click to claim", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
                }
                lore.add(Component.text("Right-Click to preview", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))

                meta.lore(lore)

                // Subtle glint on ready kits only — no glint while on cooldown or locked.
                if (ready) {
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true)
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
                }
            }

        val kitName = kitDef.name
        gui.setItem(slot, item) { p, event ->
            if (event.click.isRightClick) {
                openKitPreviewGui(p, kitDef)
            } else {
                p.closeInventory()
                claimKit(p, kitName)
            }
        }
    }

    /** Read-only preview of a kit's contents — items can be hovered for full tooltips (enchants, attributes, etc.) but not taken. */
    fun openKitPreviewGui(player: Player, kitDef: KitDef) {
        val sortedItems = kitDef.items.toSortedMap().values.toList()
        val rows = (((sortedItems.size - 1) / 9) + 1).coerceIn(1, 6)
        val size = rows * 9

        val title = Component.text("Preview: ")
            .append(Component.text(displayName(kitDef.name), TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)
        val gui = CustomGui(title, size)

        for ((index, kitItem) in sortedItems.withIndex()) {
            if (index >= size) break
            gui.inventory.setItem(index, kitItem.clone())
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.4f, 1.4f)
    }

    fun openCreateKitGui(player: Player, kitName: String) {
        val title = Component.text(CREATE_KIT_TITLE_PREFIX)
            .append(Component.text(kitName, NamedTextColor.AQUA))
            .decoration(TextDecoration.ITALIC, false)

        // Create kit GUI uses a plain inventory (not GuiManager) so players can freely place items.
        // We use a Bukkit inventory directly and handle the close via a scheduled task.
        val inv = Bukkit.createInventory(null, 36, title)
        player.openInventory(inv)
        plugin.commsManager.send(player, Component.text("Place items in the chest, then close it to save the kit.", NamedTextColor.YELLOW))

        // Schedule a repeating check for when they close the inventory
        val capturedKitName = kitName
        val taskRef = arrayOfNulls<org.bukkit.scheduler.BukkitTask>(1)
        taskRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            // Check if the player has closed the inventory
            if (player.openInventory.topInventory !== inv || !player.isOnline) {
                taskRef[0]?.cancel()

                // Collect items from the inventory
                val items = mutableMapOf<Int, ItemStack>()
                for (slot in 0 until inv.size) {
                    val item = inv.getItem(slot)
                    if (item != null && item.type != Material.AIR) {
                        items[slot] = item.clone()
                    }
                }

                if (items.isEmpty()) {
                    plugin.commsManager.send(player, Component.text("Kit creation cancelled (no items placed).", NamedTextColor.RED))
                    return@Runnable
                }

                saveKit(capturedKitName, items.values.first().type, 72, "joshymc.kit.$capturedKitName", items)
                plugin.commsManager.send(player, Component.text("Kit '$capturedKitName' created with ${items.size} item(s)!", NamedTextColor.GREEN))
                player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f)
            }
        }, 5L, 5L)
    }

    fun getEditSession(player: Player): EditSession? = editSessions[player.uniqueId]

    /** Opens (or re-opens) the /editkit GUI for `name`. Handles the "already being edited" lock. */
    fun openEditKitGui(player: Player, name: String) {
        val kit = kits[name]
        if (kit == null) {
            plugin.commsManager.send(player, Component.text("Kit '$name' does not exist.", NamedTextColor.RED))
            return
        }

        val lockHolder = editLocks[name]
        if (lockHolder != null && lockHolder != player.uniqueId) {
            val holder = Bukkit.getPlayer(lockHolder)
            if (holder != null && holder.isOnline) {
                plugin.commsManager.send(
                    player,
                    Component.text("That kit is currently being edited by ${holder.name}.", NamedTextColor.RED)
                )
                return
            }
            // Holder went offline without their session getting cleaned up — release the stale lock.
            editLocks.remove(name)
            editSessions.remove(lockHolder)
        }

        // Editing a second kit replaces any edit session this player already had open (discarded, not saved).
        editSessions[player.uniqueId]?.let { discardEditSession(player, closeInventory = true) }

        val inv = Bukkit.createInventory(null, EDIT_GUI_SIZE, editGuiTitle(name))
        for ((slot, item) in kit.items) {
            if (slot in EDIT_CONTENT_SLOTS) inv.setItem(slot, item.clone())
        }
        renderEditControlRow(inv, name)

        val overflow = kit.items.filterKeys { it !in EDIT_CONTENT_SLOTS }
        editSessions[player.uniqueId] = EditSession(name, player.uniqueId, inv, kit.items, overflow)
        editLocks[name] = player.uniqueId

        player.openInventory(inv)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    fun handleEditSave(player: Player) {
        val session = editSessions[player.uniqueId] ?: return
        val kit = kits[session.kitName]
        if (kit == null) {
            plugin.commsManager.send(player, Component.text("Kit '${session.kitName}' no longer exists.", NamedTextColor.RED))
            discardEditSession(player, closeInventory = true)
            return
        }

        val items = mutableMapOf<Int, ItemStack>()
        for (slot in EDIT_CONTENT_SLOTS) {
            val item = session.inventory.getItem(slot)
            if (item != null && item.type != Material.AIR) items[slot] = item.clone()
        }
        // Slots outside the editable area (shouldn't normally exist) are preserved untouched.
        items.putAll(session.overflowItems)

        saveKit(session.kitName, kit.icon, kit.cooldownHours, kit.permission, items)

        editSessions.remove(player.uniqueId)
        editLocks.remove(session.kitName)

        plugin.commsManager.send(player, Component.text("Kit '${session.kitName}' has been updated.", NamedTextColor.GREEN))
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f)
        player.closeInventory()
    }

    fun handleEditCancel(player: Player) {
        if (!editSessions.containsKey(player.uniqueId)) return
        plugin.commsManager.send(player, Component.text("Kit edit cancelled.", NamedTextColor.YELLOW))
        discardEditSession(player, closeInventory = true)
    }

    fun handleEditReload(player: Player) {
        val session = editSessions[player.uniqueId] ?: return
        for (slot in EDIT_CONTENT_SLOTS) session.inventory.setItem(slot, null)
        for ((slot, item) in session.originalItems) {
            if (slot in EDIT_CONTENT_SLOTS) session.inventory.setItem(slot, item.clone())
        }
        plugin.commsManager.send(player, Component.text("Reloaded original kit contents.", NamedTextColor.YELLOW))
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
    }

    /** Ends a player's edit session without saving. Used for close-without-save, quit, cancel, and reload/shutdown. */
    fun discardEditSession(player: Player, closeInventory: Boolean) {
        val session = editSessions.remove(player.uniqueId) ?: return
        if (editLocks[session.kitName] == player.uniqueId) editLocks.remove(session.kitName)
        if (closeInventory && player.isOnline && player.openInventory.topInventory == session.inventory) {
            player.closeInventory()
        }
    }

    private fun renderEditControlRow(inv: Inventory, kitName: String) {
        for (slot in EDIT_CONTROL_SLOTS) inv.setItem(slot, FILLER.clone())

        val info = ItemStack(Material.PAPER)
        info.editMeta { meta ->
            meta.displayName(Component.text("Editing: $kitName", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(Component.text("Move items to edit this kit's contents.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)))
        }
        inv.setItem(EDIT_INFO_SLOT, info)

        val reload = ItemStack(Material.ORANGE_DYE)
        reload.editMeta { it.displayName(Component.text("Reload Original", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)) }
        inv.setItem(EDIT_RELOAD_SLOT, reload)

        val save = ItemStack(Material.LIME_DYE)
        save.editMeta { it.displayName(Component.text("Save Changes", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)) }
        inv.setItem(EDIT_SAVE_SLOT, save)

        val cancel = ItemStack(Material.RED_DYE)
        cancel.editMeta { it.displayName(Component.text("Cancel", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)) }
        inv.setItem(EDIT_CANCEL_SLOT, cancel)
    }

    private fun editGuiTitle(name: String): Component =
        Component.text(EDIT_KIT_TITLE_PREFIX)
            .append(Component.text(name, NamedTextColor.AQUA))
            .decoration(TextDecoration.ITALIC, false)

    private fun formatCooldown(millis: Long): String {
        if (millis <= 0) return "Ready"
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}
