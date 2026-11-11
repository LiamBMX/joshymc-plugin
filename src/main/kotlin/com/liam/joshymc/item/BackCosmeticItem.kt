package com.liam.joshymc.item

import org.bukkit.NamespacedKey

/**
 * A custom chest item that also draws a large 3D model on the wearer's back (issue #1516), like
 * the Eagle Wings. Any [CustomItem] that implements this is handled by `BackCosmeticManager`:
 * while it is in the chest slot, a non-persistent ItemDisplay of [backModel] follows the wearer
 * (see `BackCosmeticRules`). The item itself stays an ordinary chest item; nothing about its
 * stats, durability or equip behaviour changes.
 *
 * New wings or capes need only the item class and an art module: build the model in the
 * art kit's back frame (model (8, 8, 8) = the mount point between the shoulder blades,
 * +Z = away from the back) and set the module's `BACK_SCALE` to [backScale].
 */
interface BackCosmeticItem {
    /** Item model drawn on the back, untransformed (`ItemDisplayTransform.NONE`). */
    val backModel: NamespacedKey

    /** Model scale (1.0 = one model unit is 1/16 block). */
    val backScale: Double
}
