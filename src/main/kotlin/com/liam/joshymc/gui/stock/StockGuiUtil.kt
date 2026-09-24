package com.liam.joshymc.gui.stock

import com.liam.joshymc.manager.StockPricingEngine
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/** Small shared helpers for building the /invest stock market GUIs. */
object StockGuiUtil {

    fun item(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val stack = ItemStack(material)
        val meta = stack.itemMeta
        meta?.displayName(name.decoration(TextDecoration.ITALIC, false))
        if (lore.isNotEmpty()) {
            meta?.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
        }
        stack.itemMeta = meta
        return stack
    }

    fun filler(material: Material = Material.GRAY_STAINED_GLASS_PANE): ItemStack =
        item(material, Component.text(" "))

    fun trendColor(trend: StockPricingEngine.TrendLevel): NamedTextColor = when (trend) {
        StockPricingEngine.TrendLevel.GROWING -> NamedTextColor.GREEN
        StockPricingEngine.TrendLevel.DECAYING -> NamedTextColor.RED
        StockPricingEngine.TrendLevel.STABLE -> NamedTextColor.YELLOW
    }

    fun plColor(value: Double): NamedTextColor = when {
        value > 0.0 -> NamedTextColor.GREEN
        value < 0.0 -> NamedTextColor.RED
        else -> NamedTextColor.GRAY
    }

    fun pct(value: Double): String {
        val sign = if (value >= 0) "+" else ""
        return "$sign${"%.1f".format(value)}%"
    }

    /** "$"-prefixed compact magnitude, e.g. money(8_420_000.0) -> "$8.42M". */
    fun money(value: Double, formatShort: (Double) -> String): String = "$" + formatShort(value)

    /** Sign-prefixed "$"-magnitude for P/L-style deltas, e.g. moneyDelta(-600.0, ...) -> "-$600". */
    fun moneyDelta(value: Double, formatShort: (Double) -> String): String {
        val sign = if (value >= 0) "+" else "-"
        return sign + "$" + formatShort(kotlin.math.abs(value))
    }
}
