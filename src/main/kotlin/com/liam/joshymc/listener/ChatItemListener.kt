package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import java.util.regex.Pattern

class ChatItemListener(private val plugin: Joshymc) : Listener {

    private val pattern = Pattern.compile("\\[(?:i|item)]", Pattern.CASE_INSENSITIVE)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!player.hasPermission("joshymc.chatitem")) return

        val item = player.inventory.itemInMainHand
        if (item.type.isAir) return

        val display = buildItemComponent(item)

        val replacement = TextReplacementConfig.builder()
            .match(pattern)
            .replacement(display)
            .times(1)
            .build()

        event.message(event.message().replaceText(replacement))
    }

    private fun buildItemComponent(item: ItemStack): Component {
        val name = if (item.hasItemMeta() && item.itemMeta!!.hasDisplayName()) {
            item.itemMeta!!.displayName()!!
        } else {
            Component.translatable(item.translationKey(), NamedTextColor.WHITE)
        }

        val amount = if (item.amount > 1) " x${item.amount}" else ""

        return Component.text("[", NamedTextColor.GRAY)
            .append(name)
            .append(Component.text("$amount]", NamedTextColor.GRAY))
            .decoration(TextDecoration.ITALIC, false)
            .hoverEvent(item.asHoverEvent())
    }
}
