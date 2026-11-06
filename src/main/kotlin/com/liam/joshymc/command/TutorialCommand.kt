package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.util.Msg
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

class TutorialCommand(private val plugin: Joshymc) : CommandExecutor, org.bukkit.command.TabCompleter {

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

    /**
     * `/tutorial [start|skip|restart|continue|guide|reload]` (issue #1387). Every subcommand acts on
     * the sender's current gamemode only; the walkthrough itself lives in
     * [com.liam.joshymc.tutorial.TutorialManager]. `guide` is the old read-only Survival
     * getting-started page (tutorial.yml), which no longer opens by itself on first join.
     */
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.firstOrNull()?.lowercase()
        if (sub == "reload") {
            if (!sender.hasPermission("joshymc.tutorial.reload")) {
                Msg.noPermission(sender)
                return true
            }
            loadSteps()
            plugin.tutorialManager.reloadTexts()
            Msg.success(sender, "Tutorial reloaded.")
            return true
        }
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }
        val tutorial = plugin.tutorialManager
        when (sub) {
            null, "status" -> tutorial.status(sender)
            "start", "resume" -> tutorial.startCommand(sender)
            "skip" -> tutorial.skip(sender)
            "restart", "replay" -> tutorial.restart(sender)
            "continue" -> tutorial.continueCommand(sender)
            "guide" -> {
                if (plugin.gamemodeLockManager.gamemodeOf(sender) != com.liam.joshymc.gamemode.ServerGamemode.SURVIVAL) {
                    Msg.error(sender, "The getting-started guide is only available in Survival.")
                } else {
                    openTutorialGui(sender, 0)
                }
            }
            else -> Msg.usage(sender, "/tutorial [start|skip|restart|guide]")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size != 1) return emptyList()
        val options = mutableListOf("start", "skip", "restart", "guide")
        if (sender.hasPermission("joshymc.tutorial.reload")) options.add("reload")
        return options.filter { it.startsWith(args[0].lowercase()) }
    }

    private fun openTutorialGui(player: Player, page: Int) {
        val totalPages = maxOf(1, (steps.size + STEPS_PER_PAGE - 1) / STEPS_PER_PAGE)
        val clampedPage = page.coerceIn(0, totalPages - 1)

        val titleText = if (totalPages > 1) "Getting Started (Page ${clampedPage + 1}/$totalPages)" else "Getting Started"
        val gui = CustomGui(
            title = Component.text(titleText, NamedTextColor.GREEN)
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

        val pageSteps = steps.drop(clampedPage * STEPS_PER_PAGE).take(STEPS_PER_PAGE)
        val startCol = (STEPS_PER_PAGE - pageSteps.size) / 2

        for ((idx, step) in pageSteps.withIndex()) {
            val slot = 10 + startCol + idx

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

        if (clampedPage > 0) {
            val prev = ItemStack(Material.ARROW)
            prev.editMeta { it.displayName(Component.text("Previous Page", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)) }
            gui.setItem(18, prev) { p, _ -> openTutorialGui(p, clampedPage - 1) }
        }
        if (clampedPage < totalPages - 1) {
            val next = ItemStack(Material.ARROW)
            next.editMeta { it.displayName(Component.text("Next Page", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)) }
            gui.setItem(26, next) { p, _ -> openTutorialGui(p, clampedPage + 1) }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.2f)
    }

    companion object {
        private const val STEPS_PER_PAGE = 7
    }
}
