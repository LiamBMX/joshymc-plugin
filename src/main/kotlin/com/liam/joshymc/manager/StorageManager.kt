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
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.Base64
import java.util.UUID

class StorageManager(private val plugin: Joshymc) : Listener {

    companion object {
        private const val VAULT_TITLE_PREFIX = "Player Vault #"
        private val SELECTOR_TITLE: Component = Component.text("         ")
            .append(Component.text("Your Vaults", TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        private val BORDER = ItemStack(Material.CYAN_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
    }

    /** Tracks which vault number each player currently has open (UUID -> vault number). */
    private val openVaults = mutableMapOf<UUID, Int>()


    private var maxVaults: Int = 10

    fun start() {
        maxVaults = plugin.config.getInt("vaults.max", 10)

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_vaults (
                uuid TEXT NOT NULL,
                vault_number INTEGER NOT NULL,
                slot INTEGER NOT NULL,
                item TEXT NOT NULL,
                PRIMARY KEY (uuid, vault_number, slot)
            )
        """.trimIndent())

        plugin.logger.info("[Storage] StorageManager started (max vaults: $maxVaults).")
    }

    /**
     * Save any currently open vaults. Called on server shutdown.
     */
    fun saveOpenVaults() {
        for ((uuid, vaultNumber) in openVaults.toMap()) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            val inv = player.openInventory.topInventory
            saveVault(player, vaultNumber, inv)
        }
        openVaults.clear()
    }

    // ---- Permission-based vault count ----

    fun getMaxVaults(player: Player): Int {
        for (n in maxVaults downTo 1) {
            if (player.hasPermission("joshymc.pv.$n")) return n
        }
        // Default: 1 vault if they have base permission
        return 1
    }

    // ---- Open vault ----

    fun openVault(player: Player, number: Int) {
        val allowed = getMaxVaults(player)
        if (number < 1 || number > allowed) {
            plugin.commsManager.send(
                player,
                Component.text("You don't have access to vault #$number.", NamedTextColor.RED),
                CommunicationsManager.Category.DEFAULT
            )
            return
        }

        val title = Component.text("$VAULT_TITLE_PREFIX$number")
            .decoration(TextDecoration.ITALIC, false)

        val inv = Bukkit.createInventory(null, 54, title)

        // Load items from DB
        val items = plugin.databaseManager.query(
            "SELECT slot, item FROM player_vaults WHERE uuid = ? AND vault_number = ?",
            player.uniqueId.toString(), number
        ) { rs ->
            val slot = rs.getInt("slot")
            val base64 = rs.getString("item")
            val bytes = Base64.getDecoder().decode(base64)
            val itemStack = ItemStack.deserializeBytes(bytes)
            slot to itemStack
        }

        for ((slot, itemStack) in items) {
            if (slot in 0 until 54) {
                inv.setItem(slot, itemStack)
            }
        }

        // Anti-dupe: clear DB immediately after loading into GUI.
        // The only copy of these items now lives in this inventory.
        // They get written back on close or quit.
        plugin.databaseManager.execute(
            "DELETE FROM player_vaults WHERE uuid = ? AND vault_number = ?",
            player.uniqueId.toString(), number
        )

        openVaults[player.uniqueId] = number
        player.openInventory(inv)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // ---- Save vault ----

    fun saveVault(player: Player, number: Int, inventory: Inventory) {
        val uuid = player.uniqueId.toString()

        plugin.databaseManager.transaction {
            // Delete existing entries for this vault
            plugin.databaseManager.execute(
                "DELETE FROM player_vaults WHERE uuid = ? AND vault_number = ?",
                uuid, number
            )

            // Insert current items (skip air/null)
            for (slot in 0 until inventory.size) {
                val item = inventory.getItem(slot)
                if (item != null && item.type != Material.AIR) {
                    val bytes = item.serializeAsBytes()
                    val base64 = Base64.getEncoder().encodeToString(bytes)
                    plugin.databaseManager.execute(
                        "INSERT INTO player_vaults (uuid, vault_number, slot, item) VALUES (?, ?, ?, ?)",
                        uuid, number, slot, base64
                    )
                }
            }
        }
    }

    // ---- Vault Selector GUI ----

    fun openVaultSelector(player: Player) {
        val allowed = getMaxVaults(player)
        val size = 45 // 5 rows
        val gui = CustomGui(SELECTOR_TITLE, size)

        // Fill with black glass
        for (i in 0 until size) gui.inventory.setItem(i, FILLER.clone())
        // Border with cyan glass (top and bottom rows)
        for (i in 0..8) { gui.inventory.setItem(i, BORDER.clone()); gui.inventory.setItem(36 + i, BORDER.clone()) }
        // Side borders (rows 1-3)
        for (row in 1..3) { gui.inventory.setItem(row * 9, BORDER.clone()); gui.inventory.setItem(row * 9 + 8, BORDER.clone()) }

        // Place vault icons in middle area (rows 1-3, cols 1-7)
        val slots = mutableListOf<Int>()
        for (row in 1..3) for (col in 1..7) slots.add(row * 9 + col)

        val uuid = player.uniqueId.toString()

        for (vaultNum in 1..maxVaults) {
            if (vaultNum - 1 >= slots.size) break
            val slot = slots[vaultNum - 1]

            val unlocked = vaultNum <= allowed

            if (unlocked) {
                // Check if vault has items
                val itemCount = plugin.databaseManager.queryFirst(
                    "SELECT COUNT(*) as cnt FROM player_vaults WHERE uuid = ? AND vault_number = ?",
                    uuid, vaultNum
                ) { rs -> rs.getInt("cnt") } ?: 0

                val item = ItemStack(Material.CHEST)
                item.editMeta { meta ->
                    meta.displayName(
                        Component.text("Vault #$vaultNum", NamedTextColor.AQUA)
                            .decoration(TextDecoration.ITALIC, false)
                            .decoration(TextDecoration.BOLD, true)
                    )
                    val lore = mutableListOf<Component>()
                    lore.add(Component.empty())
                    if (itemCount > 0) {
                        lore.add(
                            Component.text("  $itemCount item(s) stored", NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false)
                        )
                    } else {
                        lore.add(
                            Component.text("  Empty", NamedTextColor.DARK_GRAY)
                                .decoration(TextDecoration.ITALIC, false)
                        )
                    }
                    lore.add(Component.empty())
                    lore.add(
                        Component.text("  Click to open", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                    meta.lore(lore)
                }
                val capturedVaultNum = vaultNum
                gui.setItem(slot, item) { p, _ ->
                    p.closeInventory()
                    openVault(p, capturedVaultNum)
                }
            } else {
                val item = ItemStack(Material.BARRIER)
                item.editMeta { meta ->
                    meta.displayName(
                        Component.text("Locked", NamedTextColor.RED)
                            .decoration(TextDecoration.ITALIC, false)
                            .decoration(TextDecoration.BOLD, true)
                    )
                    val lore = mutableListOf<Component>()
                    lore.add(Component.empty())
                    lore.add(
                        Component.text("  Vault #$vaultNum", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                    meta.lore(lore)
                }
                gui.inventory.setItem(slot, item)
            }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // ---- Event Handlers ----

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return

        // Save vault if one is open
        val vaultNumber = openVaults.remove(player.uniqueId) ?: return

        // Verify the title matches
        val title = event.view.title()
        val expectedTitle = Component.text("$VAULT_TITLE_PREFIX$vaultNumber")
            .decoration(TextDecoration.ITALIC, false)
        if (title != expectedTitle) return

        saveVault(player, vaultNumber, event.inventory)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        val vaultNumber = openVaults.remove(player.uniqueId) ?: return

        // Force-save whatever is in the open inventory on disconnect
        val inv = player.openInventory.topInventory
        saveVault(player, vaultNumber, inv)
    }

}
