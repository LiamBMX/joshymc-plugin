package com.liam.joshymc.gui.stats

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
 * "/killtop" leaderboard GUI (issue #541). Replaces the old chat dump of the
 * top 10 killers with a paginated, read-only player-skull leaderboard
 * covering every player with recorded kills (online or offline).
 */
object KillTopGui {

    private const val PAGE_SIZE = 36
    private const val FIRST_CONTENT_SLOT = 9

    fun open(plugin: Joshymc, player: Player, page: Int = 0) {
        plugin.guiManager.open(player, build(plugin, page))
    }

    private fun build(plugin: Joshymc, page: Int): CustomGui {
        val gui = CustomGui(Component.text("Kill Leaderboard", NamedTextColor.RED), 54)
        for (slot in 0..8) gui.setItem(slot, filler())
        for (slot in 45..53) gui.setItem(slot, filler())
        gui.setItem(4, item(Material.IRON_SWORD, Component.text("Top Killers", NamedTextColor.YELLOW)))

        // Every row in player_stats with recorded kills, highest first — same
        // source the old chat leaderboard used, just no longer capped at 10.
        val kills = plugin.databaseManager.query(
            "SELECT uuid, kills FROM player_stats WHERE kills > 0 ORDER BY kills DESC"
        ) { rs ->
            try { rs.getString("uuid") to rs.getInt("kills") } catch (_: Exception) { null }
        }.filterNotNull()

        if (kills.isEmpty()) {
            gui.setItem(
                22,
                item(
                    Material.BARRIER,
                    Component.text("No Kill Data Recorded", NamedTextColor.RED),
                    listOf(Component.empty(), Component.text("No kills have been recorded yet.", NamedTextColor.GRAY))
                )
            )
            gui.setItem(49, item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }
            return gui
        }

        val totalPages = maxOf(1, ceil(kills.size / PAGE_SIZE.toDouble()).toInt())
        val clampedPage = page.coerceIn(0, totalPages - 1)
        val pageKills = kills.drop(clampedPage * PAGE_SIZE).take(PAGE_SIZE)

        for ((index, entry) in pageKills.withIndex()) {
            val rank = clampedPage * PAGE_SIZE + index + 1
            gui.setItem(FIRST_CONTENT_SLOT + index, buildEntryIcon(entry, rank))
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

    private fun buildEntryIcon(entry: Pair<String, Int>, rank: Int): ItemStack {
        val (uuidStr, kills) = entry
        val owner = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr))
        val name = owner.name ?: "Unknown"

        val nameColor = when (rank) {
            1 -> NamedTextColor.GOLD
            2 -> NamedTextColor.GRAY
            3 -> TextColor.color(0xB87333)
            else -> NamedTextColor.RED
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
                    Component.text("Kills: ", NamedTextColor.GRAY).append(Component.text(kills, NamedTextColor.RED))
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
