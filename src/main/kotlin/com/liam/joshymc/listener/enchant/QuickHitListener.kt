package com.liam.joshymc.listener.enchant

import com.liam.joshymc.Joshymc
import com.liam.joshymc.enchant.QuickHit
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * Quick Hit (issue #1209): while the main-hand item has Quick Hit, the player carries one
 * transient ATTACK_SPEED modifier `joshymc:quick_hit` (ADD_MULTIPLIED_TOTAL, see [QuickHit]).
 * Vanilla still runs the whole attack: the cooldown indicator, damage scaling by charge,
 * crits and sweeps all read the attribute, so only the recharge gets shorter.
 *
 * Event driven, no polling: Paper's EntityEquipmentChangedEvent fires whenever vanilla notices
 * a different main-hand stack (hotbar scroll, clicks, drags, drops, pickups, death, plugins
 * editing the held item), and join/respawn/world change re-apply on the new player state.
 * Only our own key is ever added or removed, so other modifiers and the base value are untouched.
 */
class QuickHitListener(private val plugin: Joshymc) : Listener {

    private val modifierKey = NamespacedKey(plugin, "quick_hit")

    /** Modifier amount currently on each player, so unchanged equipment never touches the attribute. */
    private val applied = HashMap<UUID, Double>()

    fun refresh(player: Player, mainHand: ItemStack? = player.inventory.itemInMainHand, force: Boolean = false) {
        val amount = amountFor(mainHand)
        val uuid = player.uniqueId
        if (!force && (applied[uuid] ?: 0.0) == amount) return

        val attribute = player.getAttribute(Attribute.ATTACK_SPEED) ?: return
        attribute.removeModifier(modifierKey)
        if (amount > 0.0) {
            attribute.addTransientModifier(
                AttributeModifier(modifierKey, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY)
            )
            applied[uuid] = amount
        } else {
            applied.remove(uuid)
        }
    }

    /** Re-checks every online player, e.g. after `/joshymc reload` changed the config. */
    fun refreshAll() {
        for (player in Bukkit.getOnlinePlayers()) refresh(player, force = true)
    }

    /** Removes the modifier from everyone (plugin disable). */
    fun stop() {
        for (player in Bukkit.getOnlinePlayers()) player.getAttribute(Attribute.ATTACK_SPEED)?.removeModifier(modifierKey)
        applied.clear()
    }

    private fun amountFor(item: ItemStack?): Double {
        if (item == null || item.isEmpty) return 0.0
        val cem = plugin.customEnchantManager
        val settings = cem.quickHit
        if (!settings.enabled || cem.getEnchant(QuickHit.ID) == null || !settings.canApplyTo(item)) return 0.0
        val reduction = settings.reductionFor(cem.getLevel(item, QuickHit.ID))
        return if (reduction > 0.0) QuickHit.modifierAmount(reduction) else 0.0
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEquipmentChanged(event: EntityEquipmentChangedEvent) {
        val player = event.entity as? Player ?: return
        if (EquipmentSlot.HAND !in event.equipmentChanges) return
        refresh(player)
    }

    /** Applied right away for the new slot; the equipment event confirms it on the next tick. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHeld(event: PlayerItemHeldEvent) {
        refresh(event.player, event.player.inventory.getItem(event.newSlot))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        refresh(event.player, force = true)
    }

    /** The respawned player gets fresh attributes, so re-apply once it exists. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        applied.remove(player.uniqueId)
        Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) refresh(player, force = true) })
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        refresh(event.player, force = true)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        event.player.getAttribute(Attribute.ATTACK_SPEED)?.removeModifier(modifierKey)
        applied.remove(event.player.uniqueId)
    }
}
