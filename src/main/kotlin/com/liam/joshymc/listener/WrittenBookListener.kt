package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerEditBookEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.meta.BookMeta

private const val PAGE_LIMIT = 10

class WrittenBookListener(private val plugin: Joshymc) : Listener {

    @EventHandler
    fun onBookEdit(event: PlayerEditBookEvent) {
        if (!event.isSigning) return
        if (event.newBookMeta.pageCount <= PAGE_LIMIT) return

        event.isCancelled = true
        plugin.commsManager.send(
            event.player,
            Component.text("Written books cannot have more than $PAGE_LIMIT pages.", NamedTextColor.RED)
        )
    }

    @EventHandler
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val item = event.item.itemStack
        if (item.type != Material.WRITTEN_BOOK) return
        val meta = item.itemMeta as? BookMeta ?: return
        if (meta.pageCount <= PAGE_LIMIT) return

        event.isCancelled = true
        event.item.remove()
        plugin.commsManager.send(
            player,
            Component.text("A written book with more than $PAGE_LIMIT pages was removed.", NamedTextColor.RED)
        )
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val inventory = player.inventory
        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i) ?: continue
            if (item.type != Material.WRITTEN_BOOK) continue
            val meta = item.itemMeta as? BookMeta ?: continue
            if (meta.pageCount <= PAGE_LIMIT) continue

            inventory.setItem(i, null)
        }
    }
}
