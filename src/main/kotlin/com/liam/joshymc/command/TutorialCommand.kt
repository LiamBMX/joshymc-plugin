package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class TutorialCommand(private val plugin: Joshymc) : CommandExecutor {

    private data class Step(
        val icon: Material,
        val title: String,
        val color: TextColor,
        val lines: List<String>
    )

    private val steps = mutableListOf<Step>()

    init {
        loadSteps()
    }

    private fun loadSteps() {
        steps.clear()

        val file = plugin.configFile("tutorial.yml")
        if (!file.exists()) {
            try {
                plugin.saveResource("tutorial.yml", false)
            } catch (_: IllegalArgumentException) {
                plugin.logger.warning("[TutorialCommand] tutorial.yml not found in jar.")
                return
            }
        }

        val config = YamlConfiguration.loadConfiguration(file)
        val section = config.getMapList("steps")
        for (entry in section) {
            @Suppress("UNCHECKED_CAST")
            val map = entry as? Map<String, Any> ?: continue
            val iconName = (map["icon"] as? String) ?: continue
            val title = (map["title"] as? String) ?: continue
            val colorHex = (map["color"] as? String) ?: "#FFFFFF"
            @Suppress("UNCHECKED_CAST")
            val lines = (map["lines"] as? List<String>) ?: emptyList()

            val icon = runCatching { Material.valueOf(iconName.uppercase()) }.getOrElse {
                plugin.logger.warning("[TutorialCommand] Unknown material '$iconName' for step '$title', skipping.")
                null
            } ?: continue

            val color = runCatching {
                val hex = colorHex.trimStart('#')
                TextColor.color(Integer.parseInt(hex, 16))
            }.getOrElse {
                plugin.logger.warning("[TutorialCommand] Invalid color '$colorHex' for step '$title', using white.")
                TextColor.color(0xFFFFFF)
            }

            steps.add(Step(icon, title, color, lines))
        }

        plugin.logger.info("[TutorialCommand] Loaded ${steps.size} tutorial step(s) from tutorial.yml.")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Players only.")
            return true
        }
        if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
            if (!sender.hasPermission("joshymc.tutorial.reload")) {
                plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
                return true
            }
            loadSteps()
            plugin.commsManager.send(sender, Component.text("Tutorial reloaded.", NamedTextColor.GREEN))
            return true
        }
        openTutorialGui(sender)
        return true
    }

    private fun openTutorialGui(player: Player) {
        val gui = CustomGui(
            title = Component.text("Getting Started", NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            size = 27
        )

        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.editMeta { it.displayName(Component.empty()) }
        gui.fill(filler)

        val border = ItemStack(Material.LIME_STAINED_GLASS_PANE)
        border.editMeta { it.displayName(Component.empty()) }
        for (i in 0 until 9) {
            gui.setItem(i, border)
            gui.setItem(18 + i, border)
        }

        val slots = listOf(10, 11, 12, 13, 14)

        for ((idx, step) in steps.withIndex()) {
            if (idx >= slots.size) break
            val slot = slots[idx]

            val item = ItemStack(step.icon)
            item.editMeta { meta ->
                meta.displayName(
                    Component.text(step.title, step.color)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )

                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                for (line in step.lines) {
                    lore.add(
                        Component.text("  $line", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                }
                lore.add(Component.empty())
                meta.lore(lore)
            }

            gui.setItem(slot, item)
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.2f)
    }
}
