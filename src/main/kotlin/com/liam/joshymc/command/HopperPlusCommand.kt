package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class HopperPlusCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("Usage: /hopper <info|reset|filter>", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)
            return true
        }

        when (args[0].lowercase()) {
            "info" -> handleInfo(sender)
            "reset" -> handleReset(sender)
            "filter" -> handleFilter(sender, args)
            else -> plugin.commsManager.send(sender, Component.text("Usage: /hopper <info|reset|filter>", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)
        }

        return true
    }

    private fun handleFilter(player: Player, args: Array<out String>) {
        if (!player.hasPermission("joshymc.hopper.upgrade")) {
            plugin.commsManager.send(player, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val block = player.getTargetBlockExact(5)
        if (block == null || block.type != Material.HOPPER) {
            plugin.commsManager.send(player, Component.text("Look at a hopper to set its filter.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val loc = block.location

        // No arg / "clear" / "none" → clear filter
        val arg = args.getOrNull(1)?.lowercase()
        if (arg == null || arg == "clear" || arg == "none") {
            plugin.hopperPlusManager.setFilter(loc.world.name, loc.blockX, loc.blockY, loc.blockZ, null)
            plugin.commsManager.send(player, Component.text("Hopper filter cleared.", NamedTextColor.GREEN), CommunicationsManager.Category.DEFAULT)
            return
        }

        // "hand" → use the material in the player's main hand
        val material: Material? = if (arg == "hand") {
            val held = player.inventory.itemInMainHand
            if (held.type == Material.AIR) {
                plugin.commsManager.send(player, Component.text("Hold an item to use as the filter, or pass a material name.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return
            }
            held.type
        } else {
            Material.matchMaterial(arg.uppercase())
        }

        if (material == null) {
            plugin.commsManager.send(player, Component.text("Unknown material: $arg", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        plugin.hopperPlusManager.setFilter(loc.world.name, loc.blockX, loc.blockY, loc.blockZ, material)
        plugin.commsManager.send(
            player,
            Component.text("Hopper filter set to ", NamedTextColor.GREEN)
                .append(Component.text(material.name.lowercase().replace('_', ' '), NamedTextColor.YELLOW))
                .append(Component.text(".", NamedTextColor.GREEN)),
            CommunicationsManager.Category.DEFAULT
        )
    }

    private fun handleInfo(player: Player) {
        if (!player.hasPermission("joshymc.hopper")) {
            plugin.commsManager.send(player, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val block = player.getTargetBlockExact(5)
        if (block == null || block.type != Material.HOPPER) {
            plugin.commsManager.send(player, Component.text("Look at a hopper to check its info.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val loc = block.location
        val data = plugin.hopperPlusManager.getHopperData(loc.world.name, loc.blockX, loc.blockY, loc.blockZ)

        if (data == null) {
            plugin.commsManager.send(player, Component.text("This is a normal hopper (Level 1, no filter).", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)
            return
        }

        val speedLabel = when (data.speed) {
            2 -> "2x"
            3 -> "4x"
            4 -> "8x"
            5 -> "Full Stack"
            else -> "Vanilla"
        }

        val filterLabel = if (data.filterItem != null) {
            data.filterItem!!.lowercase().replace('_', ' ')
        } else {
            "none"
        }

        plugin.commsManager.send(
            player,
            Component.text("Hopper Info", NamedTextColor.AQUA)
                .append(Component.text(" | Level: ", NamedTextColor.GRAY))
                .append(Component.text("${data.speed}", NamedTextColor.GREEN))
                .append(Component.text(" ($speedLabel)", NamedTextColor.DARK_GRAY))
                .append(Component.text(" | Filter: ", NamedTextColor.GRAY))
                .append(Component.text(filterLabel, NamedTextColor.YELLOW)),
            CommunicationsManager.Category.DEFAULT
        )
    }

    private fun handleReset(player: Player) {
        if (!player.hasPermission("joshymc.hopper.admin")) {
            plugin.commsManager.send(player, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val block = player.getTargetBlockExact(5)
        if (block == null || block.type != Material.HOPPER) {
            plugin.commsManager.send(player, Component.text("Look at a hopper to reset it.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val loc = block.location
        val data = plugin.hopperPlusManager.getHopperData(loc.world.name, loc.blockX, loc.blockY, loc.blockZ)

        if (data == null) {
            plugin.commsManager.send(player, Component.text("This hopper is already vanilla.", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)
            return
        }

        plugin.hopperPlusManager.resetHopper(loc.world.name, loc.blockX, loc.blockY, loc.blockZ)
        plugin.commsManager.send(player, Component.text("Hopper reset to vanilla.", NamedTextColor.GREEN), CommunicationsManager.Category.DEFAULT)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase()
            return listOf("info", "reset", "filter").filter { it.startsWith(prefix) }
        }
        if (args.size == 2 && args[0].equals("filter", ignoreCase = true)) {
            val prefix = args[1].lowercase()
            return (listOf("hand", "clear", "none") + Material.entries.map { it.name.lowercase() })
                .filter { it.startsWith(prefix) }
                .take(30)
        }
        return emptyList()
    }
}
