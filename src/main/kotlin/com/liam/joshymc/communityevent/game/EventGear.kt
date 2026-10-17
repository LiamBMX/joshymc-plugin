package com.liam.joshymc.communityevent.game

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.persistence.PersistentDataType

/**
 * Temporary hosted-event equipment (issues #1187, #1189): Spleef shovels, Sumo loadouts,
 * Sharks and Minnows role shirts and Crystal PvP kit copies. Every piece carries the `joshymc:event_gear`
 * tag so it can be found and removed wherever it ends up; it never leaves the event world
 * (see [com.liam.joshymc.communityevent.game.HostedGameListener]).
 */
class EventGear(plugin: Joshymc) {

    private val key = NamespacedKey(plugin, "event_gear")

    fun isGear(item: ItemStack?): Boolean =
        item != null && !item.type.isAir && item.persistentDataContainer.has(key, PersistentDataType.BYTE)

    fun shovel(settings: SpleefSettings): ItemStack = tagged(settings.shovel, "Spleef Shovel", "Break the floor under your opponents.") { item ->
        if (settings.efficiency > 0) item.addUnsafeEnchantment(Enchantment.EFFICIENCY, settings.efficiency)
    }

    fun loadout(settings: SumoSettings): List<ItemStack> =
        settings.loadout.map { tagged(it, null, "Sumo equipment.") {} }

    /** A dyed, tagged leather chestplate marking a Sharks and Minnows role (issue #1189). */
    fun roleShirt(name: String, color: Color, nameColor: NamedTextColor): ItemStack {
        val item = tagged(Material.LEATHER_CHESTPLATE, null, "Sharks and Minnows role.") {}
        item.editMeta(LeatherArmorMeta::class.java) { meta ->
            meta.setColor(color)
            meta.displayName(Component.text(name, nameColor).decoration(TextDecoration.ITALIC, false))
        }
        return item
    }

    /**
     * A copy of a saved Crystal PvP kit item carrying the gear tag (issue #1189), so it is
     * stripped wherever it ends up. Its enchantments, metadata and amount are kept as saved.
     */
    fun tagCopy(item: ItemStack): ItemStack {
        val copy = item.clone()
        copy.editMeta { it.persistentDataContainer.set(key, PersistentDataType.BYTE, 1) }
        return copy
    }

    /** A copy without the gear tag, for saving a kit built from items that came from an old kit. */
    fun untagCopy(item: ItemStack): ItemStack {
        val copy = item.clone()
        if (isGear(copy)) copy.editMeta { it.persistentDataContainer.remove(key) }
        return copy
    }

    /** Removes every gear item from [player]'s inventory, armor, off-hand and cursor. Returns how many stacks went. */
    fun strip(player: Player): Int {
        var removed = 0
        val inv = player.inventory
        for (slot in 0 until inv.size) {
            if (isGear(inv.getItem(slot))) { inv.setItem(slot, null); removed++ }
        }
        if (isGear(player.itemOnCursor)) { player.setItemOnCursor(null); removed++ }
        return removed
    }

    private fun tagged(material: Material, name: String?, usage: String, extra: (ItemStack) -> Unit): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            if (name != null) {
                meta.displayName(Component.text(name, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
            }
            meta.lore(listOf(
                Component.text("Event item", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(usage, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            ))
            meta.isUnbreakable = true
            meta.persistentDataContainer.set(key, PersistentDataType.BYTE, 1)
        }
        extra(item)
        return item
    }
}
