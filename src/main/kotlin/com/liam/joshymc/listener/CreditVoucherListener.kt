package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * Redeems physical Credit Vouchers (issue #595) on right-click, air or block.
 * Filtered to [EquipmentSlot.HAND] since [PlayerInteractEvent] fires once per
 * hand — without the filter, an off-hand voucher would double-redeem
 * alongside the main-hand event for the same physical click.
 */
class CreditVoucherListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val action = event.action
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        val item = player.inventory.itemInMainHand
        if (!plugin.creditVoucherManager.isCreditVoucher(item)) return

        event.isCancelled = true

        plugin.creditVoucherManager.redeem(player, item) {
            val hand = player.inventory.itemInMainHand
            if (hand.amount > 1) {
                hand.amount -= 1
            } else {
                player.inventory.setItemInMainHand(null)
            }
        }
    }
}
