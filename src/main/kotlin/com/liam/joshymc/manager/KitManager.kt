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
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.io.File

class KitManager(private val plugin: Joshymc) {

    companion object {
        val KIT_GUI_TITLE: Component = Component.text("         ")
            .append(Component.text("Kits", TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private const val CREATE_KIT_TITLE_PREFIX = "Creating Kit: "

        private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        private val BORDER = ItemStack(Material.CYAN_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
    }

    data class KitDef(
        val name: String,
        val icon: Material,
        val cooldownHours: Int,
        val permission: String,
        val items: Map<Int, ItemStack>
    )

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

        // Place kits in middle area (rows 1-3, cols 1-7)
        val slots = mutableListOf<Int>()
        for (row in 1..3) for (col in 1..7) slots.add(row * 9 + col)

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

        val displayMat = if (onCooldown) Material.RED_STAINED_GLASS_PANE else kitDef.icon
        val item = ItemStack(displayMat)

        item.editMeta { meta ->
                val nameColor = if (onCooldown) NamedTextColor.RED else NamedTextColor.AQUA
                meta.displayName(
                    Component.text(kitDef.name, nameColor)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )

                val lore = mutableListOf<Component>()
                lore.add(Component.empty())

                // Cooldown info
                val cooldownText = "${kitDef.cooldownHours}h cooldown"
                lore.add(Component.text("  $cooldownText", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))

                // Status
                if (!hasPermission) {
                    lore.add(Component.text("  No Permission", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false))
                } else if (onCooldown) {
                    val remaining = getCooldownRemaining(player, kitDef.name)
                    val formatted = formatCooldown(remaining)
                    lore.add(Component.text("  $formatted remaining", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                } else {
                    lore.add(Component.text("  Ready", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
                }

                // Items preview
                lore.add(Component.empty())
                lore.add(Component.text("  Contents:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                for ((_, kitItem) in kitDef.items) {
                    val itemName = kitItem.type.name.lowercase().replace("_", " ")
                    val amount = if (kitItem.amount > 1) " x${kitItem.amount}" else ""
                    lore.add(Component.text("  - $itemName$amount", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
                }

                lore.add(Component.empty())
                if (hasPermission && !onCooldown) {
                    lore.add(Component.text("  Click to claim", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
                }

                meta.lore(lore)

                if (onCooldown) {
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true)
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
                }
            }

        val kitName = kitDef.name
        gui.setItem(slot, item) { p, _ ->
            p.closeInventory()
            claimKit(p, kitName)
        }
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

    private fun formatCooldown(millis: Long): String {
        if (millis <= 0) return "Ready"
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}
