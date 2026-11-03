package com.liam.joshymc.gui

/**
 * Shared GUI icons (issue #1355): custom items that exist to be menu buttons, available to
 * every gamemode's GUIs (Survival and Lifesteal alike; they are visual assets, not player
 * data). Each entry names the registered custom item that draws it, so the look lives in
 * one place: the item class + its resource pack model. Build one with [GuiStyle.icon].
 *
 * Bukkit-free on purpose, so the ids and model keys can be checked without a server
 * (`./gradlew -I scripts/gui-icon-check.init.gradle.kts guiIconCheck`).
 */
enum class GuiIcon(
    /** The registered custom item id (ItemManager, PDC `joshymc:custom_item_id`). */
    val itemId: String,
) {
    DISCORD("discord_icon"),
    YOUTUBE("youtube_icon"),
    ;

    /** The `minecraft:item_model` the item uses, `joshymc:<itemId>`. */
    val modelKey: String get() = "$NAMESPACE:$itemId"

    companion object {
        const val NAMESPACE = "joshymc"

        /** The icon whose custom item id is [itemId] (case-insensitive), or null. */
        fun fromItemId(itemId: String): GuiIcon? = entries.firstOrNull { it.itemId.equals(itemId, ignoreCase = true) }
    }
}
