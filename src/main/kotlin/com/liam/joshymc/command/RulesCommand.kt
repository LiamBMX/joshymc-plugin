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
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class RulesCommand(private val plugin: Joshymc) : CommandExecutor {

    private data class Rule(
        val icon: Material,
        val title: String,
        val color: TextColor,
        val lines: List<String>
    )

    private val rules = listOf(
        Rule(Material.BARRIER, "No Hacking or Cheating", TextColor.color(0xFF5555), listOf(
            "No hacked clients, x-ray, or exploit mods.",
            "Allowed: Optifine, minimaps, shaders,",
            "Fabric/Forge performance mods.",
            "Macro / autoclickers are not allowed."
        )),
        Rule(Material.HOPPER, "No Duping or Exploits", TextColor.color(0xAA00AA), listOf(
            "No duplication glitches of any kind.",
            "Includes vanilla and plugin exploits.",
            "Report any bugs or exploits to staff.",
            "Do not abuse unintended mechanics."
        )),
        Rule(Material.DIAMOND_SWORD, "No Toxic PvP Behavior", TextColor.color(0x5555FF), listOf(
            "No spawn killing or portal trapping.",
            "No repeatedly targeting the same player.",
            "PvP is only allowed in designated areas",
            "or when both players have PvP enabled."
        )),
        Rule(Material.GOLDEN_APPLE, "Be Honest & Play Fair", TextColor.color(0xFFAA00), listOf(
            "Honor all trades and agreements.",
            "No scamming, stealing, or deception.",
            "Do not use alt accounts to gain",
            "an unfair advantage."
        )),
        Rule(Material.PLAYER_HEAD, "Be Respectful", TextColor.color(0x55FF55), listOf(
            "No harassment, bullying, racism, sexism,",
            "homophobia, or any form of hate speech.",
            "Treat all players with respect.",
            "Keep all content appropriate."
        )),
        Rule(Material.WRITABLE_BOOK, "No Spam or Advertising", TextColor.color(0xFFFF55), listOf(
            "Do not spam chat, commands, or repeat messages.",
            "No advertising other servers, discords,",
            "or websites in any form."
        )),
        Rule(Material.REDSTONE, "No Lag Machines", TextColor.color(0xFF5555), listOf(
            "Do not build redstone contraptions or",
            "mob farms that cause excessive lag.",
            "Staff may remove builds that impact",
            "server performance without warning."
        )),
        Rule(Material.BRICKS, "Build With the Community in Mind", TextColor.color(0x55FFFF), listOf(
            "No inappropriate or offensive builds.",
            "Do not build too close to other players",
            "without permission. Respect shared spaces."
        )),
        Rule(Material.SHIELD, "Listen to Staff", TextColor.color(0x55FF55), listOf(
            "Staff have final say on all disputes.",
            "Follow instructions from moderators.",
            "Do not argue with staff decisions in chat.",
            "Appeal via Discord if you disagree."
        )),
        Rule(Material.ENDER_EYE, "Use Common Sense", TextColor.color(0xAA55FF), listOf(
            "If something feels wrong, it probably is.",
            "Loopholes do not make it allowed.",
            "Ignorance of the rules is not an excuse."
        )),
        Rule(Material.IRON_DOOR, "No Inside Raiding", TextColor.color(0xFF5555), listOf(
            "Do not betray teammates or allies.",
            "No stealing from team chests or bases",
            "that you were given access to.",
            "Leaving a team does not entitle you to loot."
        )),
        Rule(Material.LEATHER_BOOTS, "No Combat Logging", TextColor.color(0xFFAA00), listOf(
            "Do not disconnect during PvP combat.",
            "If you are tagged in combat, you must",
            "stay online until the fight is over.",
            "Combat logging will result in death."
        )),
        Rule(Material.GOLD_INGOT, "No Farming Bounties", TextColor.color(0xFFFF55), listOf(
            "Do not place bounties on friends or alts",
            "and have them collect the reward.",
            "No arranging kills to farm bounty money."
        ))
    )

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
