package com.liam.joshymc.util

import com.liam.joshymc.Joshymc
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Deposits [item] into the storage the player will actually keep: their saved
 * "normal" inventory while Moderator Mode is active (never the temporary staff
 * loadout), or their live inventory otherwise. Returns whatever didn't fit (or
 * null if everything was delivered) instead of dropping it, so callers that
 * need to keep undelivered prizes parked rather than dropped on the ground
 * (e.g. GiveawayManager.deliverPending) can reuse this exact same staff-mode-
 * aware routing. This is the one authoritative delivery path — do not
 * reimplement the mod-mode branching elsewhere.
 *
 * Crate keys given while the player is in the Event World (issue #1178) go to
 * the Crate Keys section of /overflow instead: the event inventory is wiped
 * when they leave, and keys must not be usable inside an event.
 */
fun Joshymc.depositItemSafely(player: Player, item: ItemStack): ItemStack? {
    if (item.type == Material.AIR || item.amount <= 0) return null

    return when {
        eventManager.isInEventWorld(player) && crateManager.isKey(item) ->
            overflowManager.storeEventCrateKey(player, item)
        modModeManager.isModMode(player) -> modModeManager.addItemToBackup(player.uniqueId, item)
        else -> player.inventory.addItem(item).values.firstOrNull()
    }
}

/**
 * [depositItemSafely], dropping any leftover at the player's feet rather than
 * discarding it, matching the existing addItem-then-drop pattern used elsewhere
 * in the plugin. Crate keys left over in the Event World (their /overflow
 * Crate Keys section is full) are held as a pending crate reward instead, which
 * is retried once the player is out of the event. Dropped leftovers are split
 * into normal-sized stacks, so e.g. 64 Tomatoes (max 16) never land as one
 * oversized stack.
 */
fun Joshymc.giveItemSafely(player: Player, item: ItemStack) {
    val leftover = depositItemSafely(player, item) ?: return
    if (leftover.amount <= 0) return
    if (eventManager.isInEventWorld(player) && crateManager.isKey(leftover)) {
        crateManager.holdPendingReward(player, leftover)
    } else {
        val max = leftover.maxStackSize.coerceAtLeast(1)
        var remaining = leftover.amount
        while (remaining > 0) {
            val n = minOf(max, remaining)
            player.world.dropItemNaturally(player.location, leftover.clone().apply { amount = n })
            remaining -= n
        }
    }
}

/** [giveItemSafely] for each item in [items]. */
fun Joshymc.giveItemsSafely(player: Player, items: Collection<ItemStack>) {
    for (item in items) giveItemSafely(player, item)
}
