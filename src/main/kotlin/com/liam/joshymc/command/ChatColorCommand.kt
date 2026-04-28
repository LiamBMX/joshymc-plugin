package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.util.UUID

class ChatColorCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        val CHAT_COLORS: Map<String, String> = mapOf(
            "white" to "&f",
            "gray" to "&7",
            "dark_gray" to "&8",
            "black" to "&0",
            "red" to "&c",
            "dark_red" to "&4",
            "gold" to "&6",
            "yellow" to "&e",
            "green" to "&a",
            "dark_green" to "&2",
            "aqua" to "&b",
            "dark_aqua" to "&3",
            "blue" to "&9",
            "dark_blue" to "&1",
            "light_purple" to "&d",
            "dark_purple" to "&5",
            // Special (permission-locked)
            "rainbow" to "RAINBOW",
            "gradient_fire" to "GRADIENT_FIRE",
            "gradient_ice" to "GRADIENT_ICE",
            "gradient_nature" to "GRADIENT_NATURE",
            "gradient_sunset" to "GRADIENT_SUNSET",
            "bold" to "&l",
        )

        private val specialColors = setOf(
            "rainbow", "gradient_fire", "gradient_ice", "gradient_nature", "gradient_sunset"
        )

        private val gradientColorLists = mapOf(
            "GRADIENT_FIRE" to listOf("&4", "&c", "&6", "&e"),
            "GRADIENT_ICE" to listOf("&f", "&b", "&3", "&9"),
            "GRADIENT_NATURE" to listOf("&2", "&a", "&e", "&a", "&2"),
            "GRADIENT_SUNSET" to listOf("&c", "&5", "&9", "&1"),
        )

        fun createTable(plugin: Joshymc) {
            plugin.databaseManager.createTable(
                "CREATE TABLE IF NOT EXISTS chat_colors (uuid TEXT PRIMARY KEY, color_id TEXT NOT NULL)"
            )
        }

        fun getPlayerColor(plugin: Joshymc, uuid: UUID): String? {
            return plugin.databaseManager.queryFirst(
                "SELECT color_id FROM chat_colors WHERE uuid = ?",
                uuid.toString()
            ) { rs -> rs.getString("color_id") }
        }

        fun setPlayerColor(plugin: Joshymc, uuid: UUID, colorId: String) {
            plugin.databaseManager.execute(
                "INSERT OR REPLACE INTO chat_colors (uuid, color_id) VALUES (?, ?)",
                uuid.toString(), colorId
            )
        }

        fun removePlayerColor(plugin: Joshymc, uuid: UUID) {
            plugin.databaseManager.execute(
                "DELETE FROM chat_colors WHERE uuid = ?",
                uuid.toString()
            )
        }

        fun applyColor(plugin: Joshymc, uuid: UUID, message: String): String {
            val colorId = getPlayerColor(plugin, uuid) ?: return message
            val entry = CHAT_COLORS[colorId]?.let { colorId to it } ?: return message
            val code = entry.second

            return when (code) {
                "RAINBOW" -> rainbowText(message)
                "GRADIENT_FIRE" -> gradientText(message, gradientColorLists["GRADIENT_FIRE"]!!)
                "GRADIENT_ICE" -> gradientText(message, gradientColorLists["GRADIENT_ICE"]!!)
                "GRADIENT_NATURE" -> gradientText(message, gradientColorLists["GRADIENT_NATURE"]!!)
                "GRADIENT_SUNSET" -> gradientText(message, gradientColorLists["GRADIENT_SUNSET"]!!)
                else -> "$code$message"
            }
        }

        fun rainbowText(text: String): String {
            val colors = listOf("&c", "&6", "&e", "&a", "&b", "&9", "&d")
            return text.mapIndexed { i, c ->
                if (c == ' ') " " else "${colors[i % colors.size]}$c"
            }.joinToString("")
        }

        fun gradientText(text: String, colors: List<String>): String {
            val visibleChars = text.count { it != ' ' }
            if (visibleChars <= 1) return "${colors[0]}$text"
            var charIndex = 0
            return text.map { c ->
                if (c == ' ') " "
                else {
                    val pos = charIndex.toDouble() / (visibleChars - 1) * (colors.size - 1)
                    val colorIdx = pos.toInt().coerceIn(0, colors.size - 1)
                    charIndex++
                    "${colors[colorIdx]}$c"
                }
            }.joinToString("")
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        // Reset is always allowed (lets a player who lost the perm clear their color).
        if (args.isNotEmpty() && args[0].lowercase() == "reset") {
            removePlayerColor(plugin, sender.uniqueId)
            plugin.commsManager.send(
                sender,
                Component.text("Chat color reset to default.", NamedTextColor.GREEN)
            )
            return true
        }

        if (!sender.hasPermission("joshymc.chatcolor")) {
            plugin.commsManager.send(
                sender,
                Component.text("You don't have permission to use chat colors.", NamedTextColor.RED)
            )
            return true
        }

        if (args.isEmpty()) {
            openGui(sender)
            return true
        }

        val colorName = args[0].lowercase()

        val entry = CHAT_COLORS[colorName]?.let { colorName to it }
        if (entry == null) {
            plugin.commsManager.send(
                sender,
                Component.text("Unknown color '$colorName'.", NamedTextColor.RED)
            )
            return true
        }

        if (specialColors.contains(colorName) && !sender.hasPermission("joshymc.chatcolor.special")) {
            plugin.commsManager.send(
                sender,
                Component.text("You don't have permission to use that color.", NamedTextColor.RED)
            )
            return true
        }

        setPlayerColor(plugin, sender.uniqueId, colorName)
        plugin.commsManager.send(
            sender,
            Component.text("Chat color set to ", NamedTextColor.GREEN)
                .append(plugin.commsManager.parseLegacy(formatPreview(colorName)))
        )
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase()
            val names = CHAT_COLORS.keys.toList() + "reset"
            return names.filter { it.startsWith(prefix) }
        }
        return emptyList()
    }

    // ---- GUI ----

    private fun openGui(player: Player) {
        val gui = CustomGui(
            Component.text("Chat Colors", NamedTextColor.DARK_GRAY),
            36
        )

        val currentColor = getPlayerColor(plugin, player.uniqueId)
        val hasSpecial = player.hasPermission("joshymc.chatcolor.special")

        val colorSlots = listOf(
            // Row 1 (slots 0-8): basic colors
            "white", "gray", "dark_gray", "black", "red", "dark_red", "gold", "yellow", "green",
            // Row 2 (slots 9-17): more basics + specials start
            "dark_green", "aqua", "dark_aqua", "blue", "dark_blue", "light_purple", "dark_purple", "bold", null,
            // Row 3 (slots 18-26): specials
            "rainbow", "gradient_fire", "gradient_ice", "gradient_nature", "gradient_sunset",
        )

        for ((index, colorName) in colorSlots.withIndex()) {
            if (colorName == null) continue

            val isSpecial = specialColors.contains(colorName)
            val isLocked = isSpecial && !hasSpecial
            val isSelected = colorName == currentColor

            val item = if (isLocked) {
                createLockedItem(colorName)
            } else {
                createColorItem(colorName, isSelected)
            }

            gui.setItem(index, item) { p, _ ->
                if (isLocked) {
                    plugin.commsManager.send(
                        p,
                        Component.text("You don't have permission to use that color.", NamedTextColor.RED)
                    )
                    return@setItem
                }

                setPlayerColor(plugin, p.uniqueId, colorName)
                plugin.commsManager.send(
                    p,
                    Component.text("Chat color set to ", NamedTextColor.GREEN)
                        .append(plugin.commsManager.parseLegacy(formatPreview(colorName)))
                )
                p.closeInventory()
            }
        }

        // Reset button at slot 31
        val resetItem = ItemStack(Material.BARRIER).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("Reset to Default", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(listOf(
                    Component.text("Click to remove your chat color", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                ))
            }
        }

        gui.setItem(31, resetItem) { p, _ ->
            removePlayerColor(plugin, p.uniqueId)
            plugin.commsManager.send(
                p,
                Component.text("Chat color reset to default.", NamedTextColor.GREEN)
            )
            p.closeInventory()
        }

        plugin.guiManager.open(player, gui)
    }

    private fun createColorItem(colorName: String, selected: Boolean): ItemStack {
        val material = colorMaterial(colorName)
        val item = ItemStack(material)
        item.editMeta { meta ->
            val displayName = colorName.replace("_", " ").replaceFirstChar { it.uppercase() }
            meta.displayName(
                plugin.commsManager.parseLegacy(formatPreview(colorName, displayName))
                    .decoration(TextDecoration.ITALIC, false)
            )

            val lore = mutableListOf<Component>()
            lore.add(
                plugin.commsManager.parseLegacy(formatPreview(colorName, "This is a preview!"))
                    .decoration(TextDecoration.ITALIC, false)
            )
            if (selected) {
                lore.add(Component.empty())
                lore.add(
                    Component.text("Currently selected", NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false)
                )
            }
            meta.lore(lore)

            if (selected) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true)
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
            }
        }
        return item
    }

    private fun createLockedItem(colorName: String): ItemStack {
        val item = ItemStack(Material.RED_STAINED_GLASS_PANE)
        item.editMeta { meta ->
            val displayName = colorName.replace("_", " ").replaceFirstChar { it.uppercase() }
            meta.displayName(
                Component.text(displayName, NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(listOf(
                Component.text("Locked", NamedTextColor.DARK_RED)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("Requires special permission", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ))
        }
        return item
    }

    private fun formatPreview(colorName: String, text: String = colorName.replace("_", " ")): String {
        val entry = CHAT_COLORS[colorName]?.let { colorName to it } ?: return text
        val code = entry.second

        return when (code) {
            "RAINBOW" -> rainbowText(text)
            "GRADIENT_FIRE" -> gradientText(text, gradientColorLists["GRADIENT_FIRE"]!!)
            "GRADIENT_ICE" -> gradientText(text, gradientColorLists["GRADIENT_ICE"]!!)
            "GRADIENT_NATURE" -> gradientText(text, gradientColorLists["GRADIENT_NATURE"]!!)
            "GRADIENT_SUNSET" -> gradientText(text, gradientColorLists["GRADIENT_SUNSET"]!!)
            else -> "$code$text"
        }
    }

    private fun colorMaterial(colorName: String): Material {
        return when (colorName) {
            "white" -> Material.WHITE_WOOL
            "gray" -> Material.LIGHT_GRAY_WOOL
            "dark_gray" -> Material.GRAY_WOOL
            "black" -> Material.BLACK_WOOL
            "red" -> Material.RED_WOOL
            "dark_red" -> Material.RED_TERRACOTTA
            "gold" -> Material.ORANGE_WOOL
            "yellow" -> Material.YELLOW_WOOL
            "green" -> Material.LIME_WOOL
            "dark_green" -> Material.GREEN_WOOL
            "aqua" -> Material.LIGHT_BLUE_WOOL
            "dark_aqua" -> Material.CYAN_WOOL
            "blue" -> Material.BLUE_WOOL
            "dark_blue" -> Material.BLUE_TERRACOTTA
            "light_purple" -> Material.MAGENTA_WOOL
            "dark_purple" -> Material.PURPLE_WOOL
            "bold" -> Material.IRON_BLOCK
            "rainbow" -> Material.PRISMARINE
            "gradient_fire" -> Material.ORANGE_STAINED_GLASS
            "gradient_ice" -> Material.LIGHT_BLUE_STAINED_GLASS
            "gradient_nature" -> Material.LIME_STAINED_GLASS
            "gradient_sunset" -> Material.MAGENTA_STAINED_GLASS
            else -> Material.PAPER
        }
    }
}
