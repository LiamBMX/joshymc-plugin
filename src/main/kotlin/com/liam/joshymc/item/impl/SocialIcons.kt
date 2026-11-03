package com.liam.joshymc.item.impl

import com.liam.joshymc.gui.GuiIcon
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.meta.ItemMeta

/**
 * Social GUI icons (issue #1355): retextured Sticks whose item model (resourcepack/art/items/
 * <id>.py) replaces the stick entirely, so only the logo shows. The vanilla stick is not
 * touched: only stacks carrying `minecraft:item_model = joshymc:<id>` look different.
 *
 * They are menu icons, not gameplay items: menus build them through `GuiStyle.icon(GuiIcon.X, …)`,
 * and [GuiIconListener][com.liam.joshymc.listener.GuiIconListener] keeps a copy that reaches
 * an inventory (e.g. via `/joshymc give`) from being crafted or burned as a stick. Shared by
 * every gamemode, so no [gamemodeScope].
 */
abstract class SocialIcon(icon: GuiIcon) : CustomItem() {

    override val id = icon.itemId
    override val material = Material.STICK

    private val modelKey = NamespacedKey(GuiIcon.NAMESPACE, icon.itemId)

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(modelKey)
    }

    protected fun name(text: String, color: TextColor): Component =
        Component.text(text, color)
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.BOLD, true)
}

/** `&9&lDiscord`: the Discord logo in blurple. */
class DiscordIcon : SocialIcon(GuiIcon.DISCORD) {
    override val displayName = name("Discord", NamedTextColor.BLUE)
    override val lore: List<Component> = LoreBuilder.build(
        type = "GUI Icon",
        description = listOf("The JoshyMC Discord community."),
        usage = "Used as a menu icon.",
    )
}

/** `&c&lYouTube`: the YouTube play button. */
class YoutubeIcon : SocialIcon(GuiIcon.YOUTUBE) {
    override val displayName = name("YouTube", NamedTextColor.RED)
    override val lore: List<Component> = LoreBuilder.build(
        type = "GUI Icon",
        description = listOf("JoshyMC on YouTube."),
        usage = "Used as a menu icon.",
    )
}
