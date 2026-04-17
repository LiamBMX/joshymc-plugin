package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
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

class VoteCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        try {
            when (args.getOrNull(0)?.lowercase()) {
                "top" -> showTopVoters(sender)
                "streak" -> showStreak(sender)
                else -> openVoteGui(sender)
            }
        } catch (e: Exception) {
            plugin.commsManager.send(sender, Component.text("Error: ${e.message}", NamedTextColor.RED))
            plugin.logger.warning("[Vote] /vote error: ${e.message}")
            e.printStackTrace()
        }
        return true
    }

    // ── GUI ────────────────────────────────────────────────

    private fun openVoteGui(player: Player) {
        val links = plugin.voteManager.getVoteLinks()
        val streak = plugin.voteManager.getStreak(player.uniqueId)
        val totalVotes = plugin.voteManager.getTotalVotes(player.uniqueId)

        val gui = CustomGui(
            Component.text("Vote for the Server!", NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            45
        )

        // Background
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.editMeta { it.displayName(Component.empty()) }
        for (i in 0 until 45) gui.inventory.setItem(i, filler.clone())

        // Border
        val border = ItemStack(Material.GREEN_STAINED_GLASS_PANE)
        border.editMeta { it.displayName(Component.empty()) }
        gui.border(border)

        if (links.isEmpty()) {
            val noLinks = ItemStack(Material.BARRIER)
            noLinks.editMeta { meta ->
                meta.displayName(
                    Component.text("No vote links configured", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("  Set them in config.yml under voting.vote-links", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                    Component.empty()
                ))
            }
            gui.setItem(22, noLinks)
        } else {
            // Place each link in the center area (slots 10..16, 19..25)
            val slots = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25)
            for ((idx, link) in links.withIndex()) {
                if (idx >= slots.size) break
                val (name, url) = link
                val item = ItemStack(Material.PAPER)
                item.editMeta { meta ->
                    meta.displayName(
                        Component.text(name, TextColor.color(0x55FF55))
                            .decoration(TextDecoration.ITALIC, false)
                            .decoration(TextDecoration.BOLD, true)
                    )
                    meta.lore(listOf(
                        Component.empty(),
                        Component.text("  $url", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        Component.text("  Click to open in chat", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false),
                        Component.empty()
                    ))
                }
                val urlRef = url
                val nameRef = name
                gui.setItem(slots[idx], item) { p, _ ->
                    p.closeInventory()
                    p.sendMessage(
                        Component.text("\u25B6 ", NamedTextColor.GREEN)
                            .append(Component.text(nameRef, NamedTextColor.WHITE)
                                .decoration(TextDecoration.UNDERLINED, true)
                                .clickEvent(ClickEvent.openUrl(urlRef))
                                .hoverEvent(Component.text("Click to open $urlRef", NamedTextColor.GRAY)))
                    )
                    p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                }
            }
        }

        // Stats item at slot 40 (bottom center)
        val stats = ItemStack(Material.BOOK)
        stats.editMeta { meta ->
            meta.displayName(
                Component.text("Your Stats", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Current streak: ", NamedTextColor.GRAY)
                    .append(Component.text("$streak day${if (streak != 1) "s" else ""}", NamedTextColor.GOLD))
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Total votes: ", NamedTextColor.GRAY)
                    .append(Component.text("$totalVotes", NamedTextColor.GOLD))
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(40, stats)

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // ── Text fallback ──────────────────────────────────────

    private fun showStreak(player: Player) {
        val streak = plugin.voteManager.getStreak(player.uniqueId)
        val totalVotes = plugin.voteManager.getTotalVotes(player.uniqueId)

        player.sendMessage(Component.empty())
        plugin.commsManager.send(
            player,
            Component.text("Your Vote Stats", NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true),
            CommunicationsManager.Category.DEFAULT
        )
        player.sendMessage(
            Component.text("  Current streak: ", NamedTextColor.GRAY)
                .append(Component.text("$streak", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))
                .append(Component.text(" day(s)", NamedTextColor.GRAY))
        )
        player.sendMessage(
            Component.text("  Total votes: ", NamedTextColor.GRAY)
                .append(Component.text("$totalVotes", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))
        )
        player.sendMessage(Component.empty())
    }

    private fun showTopVoters(player: Player) {
        val topVoters = plugin.voteManager.getTopVoters(10)

        player.sendMessage(Component.empty())
        plugin.commsManager.send(
            player,
            Component.text("Top 10 Voters", NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true),
            CommunicationsManager.Category.DEFAULT
        )
        player.sendMessage(Component.empty())

        if (topVoters.isEmpty()) {
            player.sendMessage(Component.text("  No votes recorded yet.", NamedTextColor.GRAY))
        } else {
            for ((index, entry) in topVoters.withIndex()) {
                val (uuid, totalVotes, streak) = entry
                val name = plugin.voteManager.getLastUsername(uuid)
                val rank = index + 1

                val rankColor = when (rank) {
                    1 -> NamedTextColor.GOLD
                    2 -> NamedTextColor.GRAY
                    3 -> NamedTextColor.RED
                    else -> NamedTextColor.DARK_GRAY
                }

                player.sendMessage(
                    Component.text("  #$rank ", rankColor).decoration(TextDecoration.BOLD, true)
                        .append(Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
                        .append(Component.text(" - ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false))
                        .append(Component.text("$totalVotes votes", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, false))
                        .append(Component.text(" (streak: $streak)", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
                )
            }
        }

        player.sendMessage(Component.empty())
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("top", "streak").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}
