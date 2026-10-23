package com.liam.joshymc.item

import org.bukkit.Sound

/**
 * A custom item that is placed as decorative furniture (issue #1286). Any [CustomItem] that
 * implements this is handled by `FurnitureListener`: placing it puts an ItemDisplay of the
 * item (its 3D model in block space, see the art kit's "furniture" kind) and an Interaction
 * hitbox of this size on the block, and punching the hitbox gives the item back.
 */
interface FurnitureItem {
    /** Hitbox width and height in blocks (the hitbox is centred on its block). */
    val hitboxWidth: Float
    val hitboxHeight: Float

    val placeSound: Sound
    val breakSound: Sound
}
