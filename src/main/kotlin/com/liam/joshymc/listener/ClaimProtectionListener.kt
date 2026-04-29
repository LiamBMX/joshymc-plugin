package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ClaimProtectionListener(private val plugin: Joshymc) : Listener {

    private val messageCooldowns = ConcurrentHashMap<UUID, Long>()

    private val denyMessage = Component.text("You cannot do that in this claim.", NamedTextColor.RED)

    private fun denyWithMessage(player: Player) {
        val now = System.currentTimeMillis()
        val last = messageCooldowns[player.uniqueId] ?: 0L
        if (now - last >= 1000L) {
            messageCooldowns[player.uniqueId] = now
            plugin.commsManager.send(player, denyMessage)
        }
    }

    // 1. Block breaking
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (!plugin.claimManager.canAccess(player, event.block.location)) {
            event.isCancelled = true
            denyWithMessage(player)
        }
    }

    // 2. Block placing
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (!plugin.claimManager.canAccess(player, event.block.location)) {
            event.isCancelled = true
            denyWithMessage(player)
        }
    }

    // 3. Player interact — only block right-clicking blocks (not air)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val player = event.player

        // Server-managed interactables (crates) bypass claim protection so
        // anyone with a key can use them even at spawn.
        if (plugin.crateManager.getCrateTypeAt(block) != null) return

        if (!plugin.claimManager.canAccess(player, block.location)) {
            event.isCancelled = true
            denyWithMessage(player)
        }
    }

    // 4. Entity damage by entity — PvP and animal/villager protection
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val attacker = getPlayerAttacker(event.damager) ?: return
        val victim = event.entity

        if (victim is Player) {
            // PvP — cancel if victim is in a claim and attacker can't access
            val claim = plugin.claimManager.getClaimAt(victim.location) ?: return
            val attackerClaim = plugin.claimManager.getClaimAt(attacker.location)
            // Allow PvP if both are in the same claim (same chunk claim)
            if (attackerClaim != null && attackerClaim == claim) return
            event.isCancelled = true
            denyWithMessage(attacker)
        } else {
            // Non-player entities (animals, villagers, etc.)
            if (!plugin.claimManager.canAccess(attacker, victim.location)) {
                event.isCancelled = true
                denyWithMessage(attacker)
            }
        }
    }

    // 5. Bucket empty
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        val player = event.player
        if (!plugin.claimManager.canAccess(player, event.block.location)) {
            event.isCancelled = true
            denyWithMessage(player)
        }
    }

    // 6. Bucket fill
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        val player = event.player
        if (!plugin.claimManager.canAccess(player, event.block.location)) {
            event.isCancelled = true
            denyWithMessage(player)
        }
    }

    // 7. Hanging break by entity (paintings, item frames broken by player)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        val player = event.remover as? Player ?: return
        if (!plugin.claimManager.canAccess(player, event.entity.location)) {
            event.isCancelled = true
            denyWithMessage(player)
        }
    }

    // 8. Hanging place
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHangingPlace(event: HangingPlaceEvent) {
        val player = event.player ?: return
        if (!plugin.claimManager.canAccess(player, event.entity.location)) {
            event.isCancelled = true
            denyWithMessage(player)
        }
    }

    // 9. Armor stand manipulation
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onArmorStandManipulate(event: PlayerArmorStandManipulateEvent) {
        val player = event.player
        if (!plugin.claimManager.canAccess(player, event.rightClicked.location)) {
            event.isCancelled = true
            denyWithMessage(player)
        }
    }

    // 10. Entity explosion — remove claimed blocks from explosion
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { plugin.claimManager.getClaimAt(it.location) != null }
    }

    // 11. Block explosion — remove claimed blocks from explosion
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { plugin.claimManager.getClaimAt(it.location) != null }
    }

    // 12. Entity change block (enderman griefing, etc.)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        val entity = event.entity
        if (entity is Player) {
            if (!plugin.claimManager.canAccess(entity, event.block.location)) {
                event.isCancelled = true
                denyWithMessage(entity)
            }
        } else {
            // Non-player entity (enderman, wither, etc.) — block if in a claim
            if (plugin.claimManager.getClaimAt(event.block.location) != null) {
                event.isCancelled = true
            }
        }
    }

    // 13. Player interact entity (right-clicking villagers, item frames, etc.)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        val player = event.player
        if (!plugin.claimManager.canAccess(player, event.rightClicked.location)) {
            event.isCancelled = true
            denyWithMessage(player)
        }
    }

    /**
     * Resolves the player responsible for damage, handling projectiles and other indirect sources.
     */
    private fun getPlayerAttacker(damager: org.bukkit.entity.Entity): Player? {
        if (damager is Player) return damager
        if (damager is org.bukkit.entity.Projectile) {
            return damager.shooter as? Player
        }
        return null
    }
}
