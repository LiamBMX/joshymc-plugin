package com.liam.joshymc.util

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Material

/**
 * Centralized mapping of the 16 classic Minecraft chat colors (&0-&f) to
 * particle-safe RGB values and a representative dye icon for GUIs.
 * Any feature that lets a player pick one of the "16 MC colors" should
 * reuse this instead of hardcoding its own palette.
 */
object MinecraftColors {

    data class Entry(val id: String, val label: String, val textColor: NamedTextColor, val icon: Material) {
        val color: Color get() = Color.fromRGB(textColor.value())
    }

    val ALL: List<Entry> = listOf(
        Entry("BLACK", "Black", NamedTextColor.BLACK, Material.BLACK_DYE),
        Entry("DARK_BLUE", "Dark Blue", NamedTextColor.DARK_BLUE, Material.BLUE_DYE),
        Entry("DARK_GREEN", "Dark Green", NamedTextColor.DARK_GREEN, Material.GREEN_DYE),
        Entry("DARK_AQUA", "Dark Aqua", NamedTextColor.DARK_AQUA, Material.CYAN_DYE),
        Entry("DARK_RED", "Dark Red", NamedTextColor.DARK_RED, Material.RED_DYE),
        Entry("DARK_PURPLE", "Dark Purple", NamedTextColor.DARK_PURPLE, Material.PURPLE_DYE),
        Entry("GOLD", "Gold", NamedTextColor.GOLD, Material.ORANGE_DYE),
        Entry("GRAY", "Gray", NamedTextColor.GRAY, Material.LIGHT_GRAY_DYE),
        Entry("DARK_GRAY", "Dark Gray", NamedTextColor.DARK_GRAY, Material.GRAY_DYE),
        Entry("BLUE", "Blue", NamedTextColor.BLUE, Material.LIGHT_BLUE_DYE),
        Entry("GREEN", "Green", NamedTextColor.GREEN, Material.LIME_DYE),
        Entry("AQUA", "Aqua", NamedTextColor.AQUA, Material.CYAN_DYE),
        Entry("RED", "Red", NamedTextColor.RED, Material.RED_DYE),
        Entry("LIGHT_PURPLE", "Light Purple", NamedTextColor.LIGHT_PURPLE, Material.MAGENTA_DYE),
        Entry("YELLOW", "Yellow", NamedTextColor.YELLOW, Material.YELLOW_DYE),
        Entry("WHITE", "White", NamedTextColor.WHITE, Material.WHITE_DYE)
    )

    fun byId(id: String?): Entry? = id?.let { wanted -> ALL.firstOrNull { it.id == wanted } }
}
