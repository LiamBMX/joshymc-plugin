package com.liam.joshymc.lifesteal.hearts

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.LifestealSettings
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * The physical Lifesteal Heart item (issue #1344): one permanent heart.
 *
 * A Heart item is a Nether Star carrying, in its persistent data, the custom item id
 * `lifesteal_heart`, a format version and a **serial** (a random UUID) that [HeartStore] issued in
 * the same transaction that took the heart. Only the serial counts: the name, lore and look are
 * decoration, so a renamed Nether Star is never a heart, and a forged serial has no row in
 * `lifesteal_heart_items`. Each serial is unique, so Heart items never stack (max stack size 1),
 * and each one is redeemed at most once.
 *
 * It is deliberately not registered in the ItemManager: `/joshymc give` would make items without
 * a serial. Staff make real ones with `/lifesteal hearts give`, which records them.
 */
class HeartItem(private val plugin: Joshymc) {

    sealed interface Read {
        /** Not a Heart item at all (any other item, including renamed Nether Stars). */
        data object NotHeart : Read
        /** Marked as a Heart item but its data is broken, or it's a stack: never redeemable. */
        data class Malformed(val reason: String) : Read
        data class Heart(val serial: String) : Read
    }

    fun create(serial: String, look: LifestealSettings.HeartItems): ItemStack {
        require(HeartRules.parseSerial(serial) == serial) { "not a Heart item serial: $serial" }
        val stack = ItemStack(Material.NETHER_STAR, 1)
        stack.editMeta { meta ->
            meta.displayName(plugin.commsManager.parseLegacy(look.displayName).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
            meta.lore(
                listOf(
                    Component.text("Lifesteal Heart", NamedTextColor.DARK_GRAY),
                    Component.empty(),
                    Component.text("Holds 1 permanent heart.", NamedTextColor.GRAY),
                    Component.text("Right-click to redeem it.", NamedTextColor.GREEN),
                ).map { it.decoration(TextDecoration.ITALIC, false) }
            )
            meta.setMaxStackSize(1)
            meta.setEnchantmentGlintOverride(look.glint)
            look.itemModel?.let { NamespacedKey.fromString(it) }?.let { meta.setItemModel(it) }
            val pdc = meta.persistentDataContainer
            pdc.set(customItemKey, PersistentDataType.STRING, ITEM_ID)
            pdc.set(versionKey, PersistentDataType.INTEGER, FORMAT_VERSION)
            pdc.set(serialKey, PersistentDataType.STRING, serial)
        }
        return stack
    }

    /** What [stack] is. Anything carrying either Heart key is treated as a (possibly broken) heart. */
    fun read(stack: ItemStack?): Read {
        if (stack == null || stack.type.isAir || !stack.hasItemMeta()) return Read.NotHeart
        val pdc = stack.itemMeta?.persistentDataContainer ?: return Read.NotHeart
        val id = runCatching { pdc.get(customItemKey, PersistentDataType.STRING) }.getOrNull()
        val hasSerial = pdc.has(serialKey)
        if (id != ITEM_ID && !hasSerial) return Read.NotHeart
        if (id != ITEM_ID) return Read.Malformed("serial without the Heart item id")
        if (stack.type != Material.NETHER_STAR) return Read.Malformed("wrong base item ${stack.type}")
        val version = runCatching { pdc.get(versionKey, PersistentDataType.INTEGER) }.getOrNull()
        if (version != FORMAT_VERSION) return Read.Malformed("unknown format version $version")
        val serial = HeartRules.parseSerial(runCatching { pdc.get(serialKey, PersistentDataType.STRING) }.getOrNull())
            ?: return Read.Malformed("missing or invalid serial")
        if (stack.amount != 1) return Read.Malformed("a stack of ${stack.amount}")
        return Read.Heart(serial)
    }

    fun isHeart(stack: ItemStack?): Boolean = read(stack) != Read.NotHeart

    companion object {
        const val ITEM_ID = "lifesteal_heart"
        const val FORMAT_VERSION = 1

        /** The shared custom item id key (same as [com.liam.joshymc.item.CustomItem]). */
        val customItemKey = NamespacedKey("joshymc", "custom_item_id")
        val versionKey = NamespacedKey("joshymc", "lifesteal_heart_version")
        val serialKey = NamespacedKey("joshymc", "lifesteal_heart_serial")
    }
}
