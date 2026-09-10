package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

// ──────────────────────────────────────────────
// /killeffect [remove]  (alias: /ke)
// ──────────────────────────────────────────────
class KillEffectCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }
        if (!sender.hasPermission("joshymc.killeffect")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isNotEmpty() && args[0].equals("remove", ignoreCase = true)) {
            plugin.killEffectManager.clearEquippedEffect(sender.uniqueId)
            plugin.commsManager.send(sender, Component.text("Kill effect removed.", NamedTextColor.GREEN))
            return true
        }

        plugin.killEffectManager.openEffectMenu(sender)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return listOf("remove").filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

// ──────────────────────────────────────────────
// /joineffect  (alias: /je)
// ──────────────────────────────────────────────
class JoinEffectCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }
        if (!sender.hasPermission("joshymc.joineffect")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        plugin.joinEffectManager.openGui(sender)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return emptyList()
    }
}

// ──────────────────────────────────────────────
// /cosmetics — hub GUI
// ──────────────────────────────────────────────
class CosmeticsCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }
        if (!sender.hasPermission("joshymc.cosmetics")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        openCosmeticsHub(sender)
        return true
    }

    private fun openCosmeticsHub(player: Player) {
        val title = plugin.commsManager.parseLegacy("&6&lCosmetics")
        val gui = CustomGui(title, 45)

        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        gui.fill(filler)

        // Centered row (slots 19-25): Kill Effects, Join Effects, Chat Colors, Chat Tags
        gui.setItem(19, buildIcon(Material.DIAMOND_SWORD, "Kill Effects", NamedTextColor.RED,
            "30 kill effects")) { p, _ ->
            plugin.killEffectManager.openEffectMenu(p)
        }

        gui.setItem(21, buildIcon(Material.FIREWORK_ROCKET, "Join Effects", NamedTextColor.YELLOW,
            "20 join effects + messages")) { p, _ ->
            plugin.joinEffectManager.openGui(p)
        }

        gui.setItem(23, buildIcon(Material.PINK_DYE, "Chat Colors", NamedTextColor.LIGHT_PURPLE,
            "22 chat message colors")) { p, _ ->
            p.closeInventory()
            p.performCommand("chatcolor")
        }

        gui.setItem(25, buildIcon(Material.NAME_TAG, "Chat Tags", NamedTextColor.GOLD,
            "Browse Chat Tag categories")) { p, _ ->
            p.closeInventory()
            plugin.chatTagManager.openCategoryMenu(p)
        }

        plugin.guiManager.open(player, gui)
    }

    private fun buildIcon(material: Material, name: String, color: NamedTextColor, description: String): ItemStack {
        return ItemStack(material).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text(name, color)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text(description, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                    Component.text("Click to browse", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
                ))
            }
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return emptyList()
    }
}
