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

class PlayerWarpCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        val TITLE = Component.text("        ")
            .append(Component.text("Player Warps", TextColor.color(0xFFAA00)))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val NAME_REGEX = Regex("^[a-zA-Z0-9_]{1,20}$")

        private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        private val BORDER = ItemStack(Material.ORANGE_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.pwarp")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            return true
        }

        if (args.isEmpty()) {
            openPlayerWarpGui(sender)
            return true
        }

        when (args[0].lowercase()) {
            "set" -> handleSet(sender, args)
            "delete", "del", "remove" -> handleDelete(sender, args)
            else -> {
                // Treat as a warp name
                val name = args[0].lowercase()
                val location = plugin.warpManager.getPlayerWarp(name)
                if (location == null) {
                    plugin.commsManager.send(sender, Component.text("Player warp '$name' not found.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
                    return true
                }
                if (TeleportChecks.checkAndApply(sender, plugin)) return true
                sender.teleport(location)
                plugin.commsManager.send(sender, Component.text("Teleported to player warp '$name'.", NamedTextColor.GREEN), CommunicationsManager.Category.WARP)
            }
        }

        return true
    }

    private fun handleSet(sender: Player, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.pwarp.set")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            return
        }

        if (args.size < 2) {
            plugin.commsManager.send(sender, Component.text("Usage: /pwarp set <name>", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            return
        }

        val name = args[1].lowercase()

        if (!NAME_REGEX.matches(name)) {
            plugin.commsManager.send(sender, Component.text("Warp names must be alphanumeric/underscores, max 20 characters.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            return
        }

        // Check if name is taken by another player
        val existingOwner = plugin.warpManager.getPlayerWarpOwnerName(name)
        val existingWarps = plugin.warpManager.getAllPlayerWarps()
        val existingWarp = existingWarps.find { it.name == name }

        if (existingWarp != null && existingWarp.ownerUuid != sender.uniqueId.toString()) {
            plugin.commsManager.send(sender, Component.text("That warp name is already taken.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            return
        }

        if (!sender.hasPermission("joshymc.pwarp.set.unlimited")) {
            val max = plugin.config.getInt("warps.player-max-per-player", 3)
            val current = plugin.warpManager.getPlayerWarpCount(sender.uniqueId.toString())
            val isOverwrite = existingWarp != null && existingWarp.ownerUuid == sender.uniqueId.toString()
            if (current >= max && !isOverwrite) {
                plugin.commsManager.send(sender, Component.text("You have reached the player warp limit ($max).", NamedTextColor.RED), CommunicationsManager.Category.WARP)
                return
            }
        }

        plugin.warpManager.setPlayerWarp(name, sender.uniqueId.toString(), sender.name, sender.location)
        plugin.commsManager.send(sender, Component.text("Player warp '$name' created.", NamedTextColor.GREEN), CommunicationsManager.Category.WARP)
    }

    private fun handleDelete(sender: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(sender, Component.text("Usage: /pwarp delete <name>", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            return
        }

        val name = args[1].lowercase()

        if (sender.hasPermission("joshymc.pwarp.delete.others")) {
            if (plugin.warpManager.forceDeletePlayerWarp(name)) {
                plugin.commsManager.send(sender, Component.text("Player warp '$name' deleted.", NamedTextColor.GREEN), CommunicationsManager.Category.WARP)
            } else {
                plugin.commsManager.send(sender, Component.text("Player warp '$name' not found.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            }
        } else {
            if (plugin.warpManager.deletePlayerWarp(name, sender.uniqueId.toString())) {
                plugin.commsManager.send(sender, Component.text("Player warp '$name' deleted.", NamedTextColor.GREEN), CommunicationsManager.Category.WARP)
            } else {
                plugin.commsManager.send(sender, Component.text("Player warp '$name' not found or you don't own it.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            }
        }
    }

    fun openPlayerWarpGui(player: Player) {
        val warps = plugin.warpManager.getAllPlayerWarps()
        val size = 54 // 6 rows for more space
        val gui = CustomGui(TITLE, size)

        // Fill
        for (i in 0 until size) gui.inventory.setItem(i, FILLER.clone())
        for (i in 0..8) { gui.inventory.setItem(i, BORDER.clone()); gui.inventory.setItem(45 + i, BORDER.clone()) }
        for (row in 1..4) { gui.inventory.setItem(row * 9, BORDER.clone()); gui.inventory.setItem(row * 9 + 8, BORDER.clone()) }

        // Place warps in middle area (rows 1-4, cols 1-7)
        val slots = mutableListOf<Int>()
        for (row in 1..4) for (col in 1..7) slots.add(row * 9 + col)

        for ((idx, warp) in warps.withIndex()) {
            if (idx >= slots.size) break
            val slot = slots[idx]
            val loc = warp.location
            val ownerName = plugin.warpManager.getPlayerWarpOwnerName(warp.name) ?: "Unknown"

            val item = ItemStack(Material.OAK_SIGN)
            item.editMeta { meta ->
                meta.displayName(
                    Component.text(warp.name, TextColor.color(0xFFAA00))
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("  By ", NamedTextColor.GRAY)
                        .append(Component.text(ownerName, NamedTextColor.WHITE))
                        .decoration(TextDecoration.ITALIC, false),
                    Component.text("  ${loc.world.name}", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("  ${loc.blockX}, ${loc.blockY}, ${loc.blockZ}", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("  Click to teleport", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                ))
            }

            val warpName = warp.name
            gui.setItem(slot, item) { p, _ ->
                val location = plugin.warpManager.getPlayerWarp(warpName)
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
                plugin.commsManager.send(p, Component.text("Teleported to player warp '$warpName'.", NamedTextColor.GREEN), CommunicationsManager.Category.WARP)
            }
        }

        if (warps.isEmpty()) {
            val noWarps = ItemStack(Material.BARRIER)
            noWarps.editMeta { meta ->
                meta.displayName(
                    Component.text("No player warps yet", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(listOf(
                    Component.text("Use /pwarp set <name> to create one!", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                ))
            }
            gui.inventory.setItem(22, noWarps)
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase()
            val subs = listOf("set", "delete")
            val warpNames = plugin.warpManager.getAllPlayerWarps().map { it.name }
            return (subs + warpNames).filter { it.startsWith(prefix) }
        }
        if (args.size == 2) {
            val prefix = args[1].lowercase()
            return when (args[0].lowercase()) {
                "delete", "del", "remove" -> {
                    val warps = if (sender is Player && sender.hasPermission("joshymc.pwarp.delete.others")) {
                        plugin.warpManager.getAllPlayerWarps()
                    } else if (sender is Player) {
                        plugin.warpManager.getPlayerWarpsByOwner(sender.uniqueId.toString())
                    } else emptyList()
                    warps.map { it.name }.filter { it.startsWith(prefix) }
                }
                else -> emptyList()
            }
        }
        return emptyList()
    }
}
