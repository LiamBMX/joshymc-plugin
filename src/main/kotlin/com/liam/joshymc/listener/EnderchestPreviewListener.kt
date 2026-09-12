package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.regex.Pattern

/**
 * Turns `[enderchest]` / `[ec]` in chat into a clickable preview of the
 * sender's ender chest. Clicking runs the hidden `/jmc-ecview` bridge
 * command (see [com.liam.joshymc.command.EnderchestPreviewCommand]), which
 * resolves the owner's UUID at click-time and opens a read-only [CustomGui]
 * clone — never the sender's live inventory.
 */
class EnderchestPreviewListener(private val plugin: Joshymc) : Listener {

    private val pattern = Pattern.compile("\\[(?:enderchest|ec)]", Pattern.CASE_INSENSITIVE)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!player.hasPermission("joshymc.enderchestpreview")) return

        val plain = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (!pattern.matcher(plain).find()) return

        val display = Component.text("[", NamedTextColor.GRAY)
            .append(Component.text("Ender Chest", NamedTextColor.LIGHT_PURPLE))
            .append(Component.text("]", NamedTextColor.GRAY))
            .decoration(TextDecoration.ITALIC, false)
            .hoverEvent(Component.text("Click to view ${player.name}'s Ender Chest", NamedTextColor.GRAY))
            .clickEvent(ClickEvent.runCommand("/jmc-ecview ${player.uniqueId}"))

        val replacement = TextReplacementConfig.builder()
            .match(pattern)
            .replacement(display)
            .build()

        event.message(event.message().replaceText(replacement))
    }
}
