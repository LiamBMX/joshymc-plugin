package com.liam.joshymc.gui.economy

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID
import kotlin.math.ceil

/**
 * "/baltop" leaderboard GUI (issue #521). Replaces the old chat dump of the
 * top 10 balances with a paginated, read-only player-skull leaderboard
 * covering every player in the economy table (online or offline).
 */
object BalTopGui {

    private const val PAGE_SIZE = 36
    private const val FIRST_CONTENT_SLOT = 9

    fun open(plugin: Joshymc, player: Player, page: Int = 0) {
        plugin.guiManager.open(player, build(plugin, page))
    }

    private fun build(plugin: Joshymc, page: Int): CustomGui {
        val gui = CustomGui(Component.text("Balance Leaderboard", NamedTextColor.GOLD), 54)
        for (slot in 0..8) gui.setItem(slot, filler())
        for (slot in 45..53) gui.setItem(slot, filler())
        gui.setItem(4, item(Material.GOLD_INGOT, Component.text("Top Balances", NamedTextColor.YELLOW)))

        // Every row in the economy table, highest balance first — same source
        // the old chat leaderboard used, just no longer capped at 10.
        val balances = plugin.economyManager.getTopBalances(Int.MAX_VALUE)

        if (balances.isEmpty()) {
            gui.setItem(
                22,
                item(
                    Material.BARRIER,
                    Component.text("No Balances Recorded", NamedTextColor.RED),
                    listOf(Component.empty(), Component.text("No balances have been recorded yet.", NamedTextColor.GRAY))
                )
            )
            gui.setItem(49, item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }
            return gui
        }

        val totalPages = maxOf(1, ceil(balances.size / PAGE_SIZE.toDouble()).toInt())
        val clampedPage = page.coerceIn(0, totalPages - 1)
        val pageBalances = balances.drop(clampedPage * PAGE_SIZE).take(PAGE_SIZE)

        for ((index, entry) in pageBalances.withIndex()) {
            val rank = clampedPage * PAGE_SIZE + index + 1
            gui.setItem(FIRST_CONTENT_SLOT + index, buildEntryIcon(plugin, entry, rank))
        }

        if (clampedPage > 0) {
            gui.setItem(45, item(Material.ARROW, Component.text("Previous Page", NamedTextColor.YELLOW))) { p, _ ->
                open(plugin, p, clampedPage - 1)
            }
        }

        gui.setItem(47, item(Material.PAPER, Component.text("Page ${clampedPage + 1}/$totalPages", NamedTextColor.WHITE)))
        gui.setItem(49, item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }

        if (clampedPage < totalPages - 1) {
            gui.setItem(53, item(Material.ARROW, Component.text("Next Page", NamedTextColor.YELLOW))) { p, _ ->
                open(plugin, p, clampedPage + 1)
            }
        }

        return gui
    }

    private fun buildEntryIcon(plugin: Joshymc, entry: Pair<String, Double>, rank: Int): ItemStack {
        val (uuidStr, balance) = entry
        val owner = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr))
        val name = owner.name ?: "Unknown"

        val nameColor = when (rank) {
            1 -> NamedTextColor.GOLD
            2 -> NamedTextColor.GRAY
            3 -> TextColor.color(0xB87333)
            else -> NamedTextColor.GREEN
        }

        val stack = ItemStack(Material.PLAYER_HEAD)
        stack.editMeta { meta ->
            if (meta is SkullMeta) meta.owningPlayer = owner
            meta.displayName(
                Component.text("#$rank ", NamedTextColor.YELLOW)
                    .append(Component.text(name, nameColor))
                    .decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(
                listOf(
                    Component.empty(),
                    Component.text("Balance: ", NamedTextColor.GRAY).append(Component.text(plugin.economyManager.format(balance), NamedTextColor.GREEN))
                ).map { it.decoration(TextDecoration.ITALIC, false) }
            )
        }
        return stack
    }

    private fun item(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val stack = ItemStack(material)
        stack.editMeta { meta ->
            meta.displayName(name.decoration(TextDecoration.ITALIC, false))
            if (lore.isNotEmpty()) {
                meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
            }
        }
        return stack
    }

    private fun filler(material: Material = Material.GRAY_STAINED_GLASS_PANE): ItemStack =
        item(material, Component.text(" "))
}
