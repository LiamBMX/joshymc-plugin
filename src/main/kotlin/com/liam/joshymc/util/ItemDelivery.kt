package com.liam.joshymc.util

import com.liam.joshymc.Joshymc
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Delivers [item] to the inventory the player will actually keep: their saved
 * "normal" inventory while Moderator Mode or Trainee Mode is active (never the
 * temporary staff loadout), or their live inventory otherwise. Overflow is
 * dropped at the player's feet rather than discarded, matching the existing
 * addItem-then-drop pattern used elsewhere in the plugin.
 */
fun Joshymc.giveItemSafely(player: Player, item: ItemStack) {
    if (item.type == Material.AIR || item.amount <= 0) return

    val leftover: ItemStack? = when {
        modModeManager.isModMode(player) -> modModeManager.addItemToBackup(player.uniqueId, item)
        traineeModeManager.isTraineeMode(player) -> traineeModeManager.addItemToBackup(player.uniqueId, item)
        else -> player.inventory.addItem(item).values.firstOrNull()
    }

    if (leftover != null && leftover.amount > 0) {
        player.world.dropItemNaturally(player.location, leftover)
    }
}

/** [giveItemSafely] for each item in [items]. */
fun Joshymc.giveItemsSafely(player: Player, items: Collection<ItemStack>) {
    for (item in items) giveItemSafely(player, item)
}
