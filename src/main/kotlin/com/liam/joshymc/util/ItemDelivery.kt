package com.liam.joshymc.util

import com.liam.joshymc.Joshymc
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Deposits [item] into the storage the player will actually keep: their saved
 * "normal" inventory while Moderator Mode or Trainee Mode is active (never the
 * temporary staff loadout), or their live inventory otherwise. Returns whatever
 * didn't fit (or null if everything was delivered) instead of dropping it, so
 * callers that need to keep undelivered prizes parked rather than dropped on
 * the ground (e.g. GiveawayManager.deliverPending) can reuse this exact same
 * staff-mode-aware routing. This is the one authoritative delivery path —
 * do not reimplement the mod-mode/trainee-mode branching elsewhere.
 */
fun Joshymc.depositItemSafely(player: Player, item: ItemStack): ItemStack? {
    if (item.type == Material.AIR || item.amount <= 0) return null

    return when {
        modModeManager.isModMode(player) -> modModeManager.addItemToBackup(player.uniqueId, item)
        traineeModeManager.isTraineeMode(player) -> traineeModeManager.addItemToBackup(player.uniqueId, item)
        else -> player.inventory.addItem(item).values.firstOrNull()
    }
}

/**
 * [depositItemSafely], dropping any leftover at the player's feet rather than
 * discarding it, matching the existing addItem-then-drop pattern used elsewhere
 * in the plugin.
 */
fun Joshymc.giveItemSafely(player: Player, item: ItemStack) {
    val leftover = depositItemSafely(player, item) ?: return
    if (leftover.amount > 0) {
        player.world.dropItemNaturally(player.location, leftover)
    }
}

/** [giveItemSafely] for each item in [items]. */
fun Joshymc.giveItemsSafely(player: Player, items: Collection<ItemStack>) {
    for (item in items) giveItemSafely(player, item)
}
