package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.link.LinkGui
import com.liam.joshymc.link.LinkSessions
import com.liam.joshymc.util.Msg
import net.dv8tion.jda.api.EmbedBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent

class LinkGuiListener(private val plugin: Joshymc) : Listener {

    /** The Link GUI is display-only: a drag into it would leave the item behind when it closes. */
    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (LinkGui.isLinkGui(event.view)) event.isCancelled = true
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (!LinkGui.isLinkGui(event.view)) return

        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return

        when (event.rawSlot) {
            LinkGui.CONFIRM_SLOT -> {
                // Same slot as the "Waiting..." clock: only the Confirm button, which appears
                // once the GUI shows the Discord account, can link anything.
                if (event.currentItem?.type != LinkGui.CONFIRM_MATERIAL) return
                when (val result = plugin.linkManager.confirmLink(player.uniqueId)) {
                    LinkSessions.ConfirmResult.NotClaimed -> return
                    LinkSessions.ConfirmResult.Expired -> {
                        player.closeInventory()
                        Msg.send(player, "&cLink expired. Try &b/link &cagain.")
                    }
                    is LinkSessions.ConfirmResult.Linked -> {
                        player.closeInventory()
                        // The Discord name is literal text, never parsed for & codes.
                        Msg.send(player, Component.text("Linked to ", NamedTextColor.GREEN)
                            .append(Component.text(result.claim.displayName, NamedTextColor.AQUA))
                            .append(Component.text("!", NamedTextColor.GREEN)))
                        if (result.replacedPlayer != null) {
                            plugin.logger.info("[Link] Discord ${result.claim.id} moved from ${result.replacedPlayer} to ${player.uniqueId}.")
                        }

                        // Send embed confirmation to Discord user
                        val pending = result.pending
                        plugin.discordManager.jda?.retrieveUserById(result.claim.id)?.queue { user ->
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
                    }
                }
            }

            LinkGui.CANCEL_SLOT -> {
                plugin.linkManager.cancel(player.uniqueId)
                player.closeInventory()
                Msg.send(player, "&7Link cancelled.")
            }
        }
    }
}
