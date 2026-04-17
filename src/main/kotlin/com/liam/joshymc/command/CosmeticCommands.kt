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
// /trail [remove]
// ──────────────────────────────────────────────
class TrailCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }
        if (!sender.hasPermission("joshymc.trail")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isNotEmpty() && args[0].equals("remove", ignoreCase = true)) {
            plugin.trailManager.removeTrail(sender.uniqueId)
            plugin.commsManager.send(sender, Component.text("Trail removed.", NamedTextColor.GREEN))
            return true
        }

        plugin.trailManager.openTrailMenu(sender)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return listOf("remove").filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

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
// /emote <name> | /emote list
// ──────────────────────────────────────────────
/** EmoteCommand delegates to EmoteManager which implements CommandExecutor + TabCompleter */
class EmoteCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        return plugin.emoteManager.onCommand(sender, command, label, args)
    }
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return plugin.emoteManager.onTabComplete(sender, command, alias, args)
    }
}

// ──────────────────────────────────────────────
// /glow [remove]
// ──────────────────────────────────────────────
class GlowCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }
        if (!sender.hasPermission("joshymc.glow")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isNotEmpty() && args[0].equals("remove", ignoreCase = true)) {
            plugin.glowManager.evictCache(sender.uniqueId)
            sender.removePotionEffect(org.bukkit.potion.PotionEffectType.GLOWING)
            plugin.commsManager.send(sender, Component.text("Glow removed.", NamedTextColor.GREEN))
            return true
        }

        plugin.glowManager.openGui(sender)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return listOf("remove").filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

// ──────────────────────────────────────────────
// /gadget
// ──────────────────────────────────────────────
class GadgetCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }
        if (!sender.hasPermission("joshymc.gadget")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        plugin.gadgetManager.openGadgetMenu(sender)
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
        val gui = CustomGui(title, 54)

        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        gui.fill(filler)

        // Row 1 (slots 10-16): Trails, Kill Effects, Join Effects, Emotes
        gui.setItem(10, buildIcon(Material.BLAZE_POWDER, "Trails", NamedTextColor.GOLD,
            "50 particle trails")) { p, _ ->
            plugin.trailManager.openTrailMenu(p, fromHub = true)
        }

        gui.setItem(12, buildIcon(Material.DIAMOND_SWORD, "Kill Effects", NamedTextColor.RED,
            "30 kill effects")) { p, _ ->
            plugin.killEffectManager.openEffectMenu(p)
        }

        gui.setItem(14, buildIcon(Material.FIREWORK_ROCKET, "Join Effects", NamedTextColor.YELLOW,
            "20 join effects + messages")) { p, _ ->
            plugin.joinEffectManager.openGui(p)
        }

        gui.setItem(16, buildIcon(Material.NOTE_BLOCK, "Emotes", NamedTextColor.GREEN,
            "25 particle emotes")) { p, _ ->
            p.closeInventory()
            p.performCommand("emote list")
        }

        // Row 3 (slots 28-34): Glow, Gadgets, Chat Colors, Chat Tags
        gui.setItem(28, buildIcon(Material.SEA_LANTERN, "Glow", NamedTextColor.AQUA,
            "15 glow outline colors")) { p, _ ->
            plugin.glowManager.openGui(p)
        }

        gui.setItem(30, buildIcon(Material.BLAZE_ROD, "Gadgets", NamedTextColor.LIGHT_PURPLE,
            "18 fun cosmetic gadgets")) { p, _ ->
            plugin.gadgetManager.openGadgetMenu(p, fromCosmetics = true)
        }

        gui.setItem(32, buildIcon(Material.PINK_DYE, "Chat Colors", NamedTextColor.LIGHT_PURPLE,
            "22 chat message colors")) { p, _ ->
            p.closeInventory()
            p.performCommand("chatcolor")
        }

        gui.setItem(34, buildIcon(Material.NAME_TAG, "Chat Tags", NamedTextColor.GOLD,
            "800+ chat tags")) { p, _ ->
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
