package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.link.LinkGui
import com.liam.joshymc.util.Msg
import net.dv8tion.jda.api.EmbedBuilder
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.InventoryView

class LinkGuiListener(private val plugin: Joshymc) : Listener {

    private fun isLinkGui(view: InventoryView): Boolean =
        PlainTextComponentSerializer.plainText().serialize(view.title()).startsWith(LinkGui.GUI_TITLE_PREFIX)

    /** The Link GUI is display-only: a drag into it would leave the item behind when it closes. */
    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (isLinkGui(event.view)) event.isCancelled = true
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (!isLinkGui(event.view)) return

        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return

        when (event.rawSlot) {
            LinkGui.CONFIRM_SLOT -> {
                val pending = plugin.linkManager.pendingLinks.values
                    .firstOrNull { it.playerUuid == player.uniqueId && it.discordId != null }

                if (pending == null) {
                    player.closeInventory()
                    Msg.send(player, "&cLink expired. Try &b/link &cagain.")
                    return
                }

                val success = plugin.linkManager.confirmLink(pending.code)
                player.closeInventory()

                if (success) {
                    Msg.send(player, "&aLinked to &b${pending.discordName}&a!")

                    // Send embed confirmation to Discord user
                    plugin.discordManager.jda?.retrieveUserById(pending.discordId!!)?.queue { user ->
                        user.openPrivateChannel().queue { dm ->
                            val embed = EmbedBuilder()
                                .setColor(0x55FF55)
                                .setTitle("Account Linked")
                                .setDescription("You are now linked to **${pending.playerName}**.")
                                .setThumbnail("https://mc-heads.net/head/${pending.playerUuid}/128")
                                .setFooter("play.joshymc.net")
                                .setTimestamp(java.time.Instant.now())
                                .build()
                            dm.sendMessageEmbeds(embed).queue()
                        }
                    }
                } else {
                    Msg.send(player, "&cLink failed. Try &b/link &cagain.")
                }
            }

            LinkGui.CANCEL_SLOT -> {
                plugin.linkManager.pendingLinks.values.removeIf { it.playerUuid == player.uniqueId }
                player.closeInventory()
                Msg.send(player, "&7Link cancelled.")
            }
        }
    }
}
