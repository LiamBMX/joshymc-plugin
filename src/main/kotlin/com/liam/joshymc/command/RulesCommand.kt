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

class RulesCommand(private val plugin: Joshymc) : CommandExecutor {

    private data class Rule(
        val icon: Material,
        val title: String,
        val color: TextColor,
        val lines: List<String>
    )

    private val rules = mutableListOf<Rule>()

    init {
        loadRules()
    }

    private fun loadRules() {
        rules.clear()

        val file = plugin.configFile("rules.yml")
        if (!file.exists()) {
            try {
                plugin.saveResource("rules.yml", false)
            } catch (_: IllegalArgumentException) {
                plugin.logger.warning("[RulesCommand] rules.yml not found in jar.")
                return
            }
        }

        val config = YamlConfiguration.loadConfiguration(file)
        val section = config.getMapList("rules")
        for (entry in section) {
            @Suppress("UNCHECKED_CAST")
            val map = entry as? Map<String, Any> ?: continue
            val iconName = (map["icon"] as? String) ?: continue
            val title = (map["title"] as? String) ?: continue
            val colorHex = (map["color"] as? String) ?: "#FFFFFF"
            @Suppress("UNCHECKED_CAST")
            val lines = (map["lines"] as? List<String>) ?: emptyList()

            val icon = runCatching { Material.valueOf(iconName.uppercase()) }.getOrElse {
                plugin.logger.warning("[RulesCommand] Unknown material '$iconName' for rule '$title', skipping.")
                null
            } ?: continue

            val color = runCatching {
                val hex = colorHex.trimStart('#')
                TextColor.color(Integer.parseInt(hex, 16))
            }.getOrElse {
                plugin.logger.warning("[RulesCommand] Invalid color '$colorHex' for rule '$title', using white.")
                TextColor.color(0xFFFFFF)
            }

            rules.add(Rule(icon, title, color, lines))
        }

        plugin.logger.info("[RulesCommand] Loaded ${rules.size} rule(s) from rules.yml.")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Players only.")
            return true
        }
        openRulesGui(sender)
        return true
    }

    private fun openRulesGui(player: Player) {
        val gui = CustomGui(
            title = Component.text("Server Rules", NamedTextColor.RED)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            size = 54
        )

        // Fill with dark glass
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.editMeta { it.displayName(Component.empty()) }
        gui.fill(filler)

        // Red glass border on top and bottom rows
        val border = ItemStack(Material.RED_STAINED_GLASS_PANE)
        border.editMeta { it.displayName(Component.empty()) }
        for (i in 0 until 9) {
            gui.setItem(i, border)
            gui.setItem(45 + i, border)
        }

        // Place rules in the center area (row 1: 7 rules, row 2: 6 rules centered)
        val slots = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25)

        for ((idx, rule) in rules.withIndex()) {
            if (idx >= slots.size) break
            val slot = slots[idx]

            val item = ItemStack(rule.icon)
            item.editMeta { meta ->
                meta.displayName(
                    Component.text("${idx + 1}. ", NamedTextColor.DARK_RED)
                        .append(Component.text(rule.title, rule.color))
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )

                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                for (line in rule.lines) {
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

        // Info item in the center of the bottom area
        val info = ItemStack(Material.PAPER)
        info.editMeta { meta ->
            meta.displayName(
                Component.text("Breaking rules may result in:", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Warning", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                Component.text("  Temporary Mute / Ban", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                Component.text("  Permanent Ban", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  Staff decisions are final.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(49, info)

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.2f)
    }
}
