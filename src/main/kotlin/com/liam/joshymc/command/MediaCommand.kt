package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class MediaCommand(private val plugin: Joshymc) : CommandExecutor {

    private data class MediaInfo(
        val enabled: Boolean,
        val title: String,
        val icon: Material,
        val requirements: List<String>,
        val platforms: List<String>,
        val applyLines: List<String>
    )

    private fun loadMediaInfo(): MediaInfo {
        val file = plugin.configFile("media.yml")
        if (!file.exists()) {
            try {
                plugin.saveResource("media.yml", false)
            } catch (_: IllegalArgumentException) {
                plugin.logger.warning("[MediaCommand] media.yml not found in jar.")
            }
        }

        val config = YamlConfiguration.loadConfiguration(file)
        val enabled = config.getBoolean("media.enabled", true)
        val title = config.getString("media.title") ?: "MEDIA REQUIREMENTS"
        val iconName = config.getString("media.icon") ?: "BOOK"
        val icon = runCatching { Material.valueOf(iconName.uppercase()) }.getOrElse {
            plugin.logger.warning("[MediaCommand] Unknown material '$iconName', using BOOK.")
            Material.BOOK
        }
        val requirements = config.getStringList("media.requirements")
        val platforms = config.getStringList("media.platforms")
        val applyLines = config.getStringList("media.apply-lines")

        return MediaInfo(enabled, title, icon, requirements, platforms, applyLines)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Players only.")
            return true
        }

        val info = loadMediaInfo()
        if (!info.enabled) {
            plugin.commsManager.send(sender, Component.text("Media requirements are not available right now.", NamedTextColor.RED))
            return true
        }

        openMediaGui(sender, info)
        return true
    }

    private fun openMediaGui(player: Player, info: MediaInfo) {
        val gui = CustomGui(
            title = Component.text(info.title, NamedTextColor.AQUA)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            size = 27
        )

        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.editMeta { it.displayName(Component.empty()) }
        gui.fill(filler)

        val border = ItemStack(Material.LIGHT_BLUE_STAINED_GLASS_PANE)
        border.editMeta { it.displayName(Component.empty()) }
        for (i in 0 until 9) {
            gui.setItem(i, border)
            gui.setItem(18 + i, border)
        }

        val item = ItemStack(info.icon)
        item.editMeta { meta ->
            meta.displayName(
                Component.text("Media Requirements", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )

            val lore = mutableListOf<Component>()
            lore.add(Component.empty())

            if (info.requirements.isNotEmpty()) {
                lore.add(Component.text("Requirements", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true))
                for (line in info.requirements) {
                    lore.add(Component.text("  • $line", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                }
                lore.add(Component.empty())
            }

            if (info.platforms.isNotEmpty()) {
                lore.add(Component.text("Platforms", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true))
                for (line in info.platforms) {
                    lore.add(Component.text("  • $line", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                }
                lore.add(Component.empty())
            }

            if (info.applyLines.isNotEmpty()) {
                lore.add(Component.text("How to Apply", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true))
                for (line in info.applyLines) {
                    lore.add(Component.text("  • $line", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                }
            }

            meta.lore(lore)
        }
        gui.setItem(13, item)

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.2f)
    }
}
