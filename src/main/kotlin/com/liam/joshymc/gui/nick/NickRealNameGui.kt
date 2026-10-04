package com.liam.joshymc.gui.nick

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import kotlin.math.ceil

/**
 * "/nick realname" lookup GUI (issue #1046). Read-only list of online players
 * with an active nickname, showing real username (name) over nickname (lore).
 * Reads the existing `nicknames` table; never writes.
 */
object NickRealNameGui {

    private const val PAGE_SIZE = 45

    private class Entry(val player: Player, val nick: String)

    fun open(plugin: Joshymc, player: Player, page: Int = 0) {
        plugin.guiManager.open(player, build(plugin, page))
    }

    private fun build(plugin: Joshymc, page: Int): CustomGui {
        val gui = CustomGui(Component.text("Player Nicknames", NamedTextColor.GOLD, TextDecoration.BOLD), 54)

        // One query, filtered to online players (no per-player lookups, no Mojang requests).
        val nicks = plugin.databaseManager.query("SELECT uuid, nickname FROM nicknames") {
            it.getString("uuid") to it.getString("nickname")
        }.toMap()
        val entries = Bukkit.getOnlinePlayers()
            .mapNotNull { p -> nicks[p.uniqueId.toString()]?.takeIf { it.isNotBlank() }?.let { Entry(p, it) } }
            .sortedBy { it.player.name.lowercase() }

        for (slot in 45..53) gui.setItem(slot, filler())
        gui.setItem(49, button(Material.BARRIER, "Close", NamedTextColor.RED)) { p, _ -> p.closeInventory() }

        if (entries.isEmpty()) {
            gui.setItem(22, button(Material.BARRIER, "No online players are currently using nicknames.", NamedTextColor.RED))
            return gui
        }

        val totalPages = maxOf(1, ceil(entries.size / PAGE_SIZE.toDouble()).toInt())
        val current = page.coerceIn(0, totalPages - 1)

        for ((i, e) in entries.drop(current * PAGE_SIZE).take(PAGE_SIZE).withIndex()) {
            gui.setItem(i, headFor(plugin, e))
        }

        if (current > 0) {
            gui.setItem(45, button(Material.ARROW, "Previous Page", NamedTextColor.YELLOW)) { p, _ -> open(plugin, p, current - 1) }
        }
        if (totalPages > 1) {
            gui.setItem(47, button(Material.PAPER, "Page ${current + 1}/$totalPages", NamedTextColor.WHITE))
        }
        if (current < totalPages - 1) {
            gui.setItem(53, button(Material.ARROW, "Next Page", NamedTextColor.YELLOW)) { p, _ -> open(plugin, p, current + 1) }
        }
        return gui
    }

    private fun headFor(plugin: Joshymc, e: Entry): ItemStack {
        val target = e.player
        val core = plugin.commsManager.parseLegacy(e.nick)
        // Same "~" fake-name prefix rule as NickCommand.renderNick.
        val nick = if (target.isOp || target.hasPermission("joshymc.nick.noprefix")) core
        else Component.text("~", NamedTextColor.GRAY).append(core)

        val item = ItemStack(Material.PLAYER_HEAD)
        item.editMeta { meta ->
            // Online player's already-resolved profile — no Mojang lookup.
            if (meta is SkullMeta) meta.playerProfile = target.playerProfile
            meta.displayName(Component.text(target.name, NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(
                Component.text("Nickname: ", NamedTextColor.GRAY).append(nick).decoration(TextDecoration.ITALIC, false)
            ))
        }
        return item
    }

    private fun button(material: Material, name: String, color: NamedTextColor): ItemStack =
        ItemStack(material).also {
            it.editMeta { m -> m.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false)) }
        }

    private fun filler(): ItemStack = ItemStack(Material.GRAY_STAINED_GLASS_PANE).also {
        it.editMeta { m -> m.displayName(Component.text(" ")) }
    }
}
