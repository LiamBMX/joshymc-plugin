package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class WarpCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        val TITLE = Component.text("         ")
            .append(Component.text("Server Warps", TextColor.color(0x55FF55)))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        private val BORDER = ItemStack(Material.GREEN_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.warp")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            return true
        }

        // If they pass a name, teleport directly
        if (args.isNotEmpty()) {
            val name = args[0].lowercase()
            val location = plugin.warpManager.getWarp(name)
            if (location == null) {
                plugin.commsManager.send(sender, Component.text("Warp '$name' not found.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
                return true
            }
            if (TeleportChecks.checkAndApply(sender, plugin)) return true
            sender.teleport(location)
            plugin.commsManager.send(sender, Component.text("Teleported to warp '$name'.", NamedTextColor.GREEN), CommunicationsManager.Category.WARP)
            return true
        }

        // Open GUI
        openWarpGui(sender)
        return true
    }

    fun openWarpGui(player: Player) {
        val warps = plugin.warpManager.getAllWarps()
        val size = 45 // 5 rows
        val gui = CustomGui(TITLE, size)

        // Fill
        for (i in 0 until size) gui.inventory.setItem(i, FILLER.clone())
        for (i in 0..8) { gui.inventory.setItem(i, BORDER.clone()); gui.inventory.setItem(36 + i, BORDER.clone()) }
        for (row in 1..3) { gui.inventory.setItem(row * 9, BORDER.clone()); gui.inventory.setItem(row * 9 + 8, BORDER.clone()) }

        // Place warps in middle area (rows 1-3, cols 1-7)
        val slots = mutableListOf<Int>()
        for (row in 1..3) for (col in 1..7) slots.add(row * 9 + col)

        for ((idx, warp) in warps.withIndex()) {
            if (idx >= slots.size) break
            val slot = slots[idx]
            val loc = warp.location

            val iconMat = try { Material.valueOf(warp.icon) } catch (_: Exception) { Material.ENDER_PEARL }
            val item = ItemStack(iconMat)
            item.editMeta { meta ->
                meta.displayName(
                    Component.text(warp.name, NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("  ${loc.world.name}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("  ${loc.blockX}, ${loc.blockY}, ${loc.blockZ}", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("  Click to teleport", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                ))
            }

            val warpName = warp.name
            gui.setItem(slot, item) { p, _ ->
                val location = plugin.warpManager.getWarp(warpName)
                if (location == null) {
                    plugin.commsManager.send(p, Component.text("Warp no longer exists.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
                    p.closeInventory()
                    return@setItem
                }

                if (TeleportChecks.checkAndApply(p, plugin)) {
                    p.closeInventory()
                    return@setItem
                }

                p.closeInventory()
                p.teleport(location)
                p.playSound(p.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.0f)
                plugin.commsManager.send(p, Component.text("Teleported to warp '$warpName'.", NamedTextColor.GREEN), CommunicationsManager.Category.WARP)
            }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase()
            return plugin.warpManager.getAllWarps().map { it.name }.filter { it.startsWith(prefix) }
        }
        return emptyList()
    }
}
