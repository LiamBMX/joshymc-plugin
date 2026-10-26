package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * Redeems Fly Vouchers (issue #1304) on right-click, air or block, from the main hand only.
 * Filtered to [EquipmentSlot.HAND] since [PlayerInteractEvent] fires once per hand, so an
 * off-hand voucher can never redeem alongside (or instead of) the main-hand click.
 */
class FlyVoucherListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val action = event.action
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        val item = player.inventory.itemInMainHand
        if (!plugin.flyVoucherManager.isFlyVoucher(item)) return

        event.isCancelled = true

        plugin.flyVoucherManager.redeem(player, item) {
            val hand = player.inventory.itemInMainHand
            if (hand.amount > 1) {
                hand.amount -= 1
            } else {
                player.inventory.setItemInMainHand(null)
            }
        }
    }
}
