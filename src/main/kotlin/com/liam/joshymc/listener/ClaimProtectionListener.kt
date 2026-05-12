package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockRedstoneEvent
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
import org.bukkit.event.vehicle.VehicleDestroyEvent
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

    // 14. Liquid flow / dragon egg teleport — block flow that crosses INTO a
    // claim from outside, or from one claim into a different claim. Movement
    // entirely within a single claim (or entirely outside any claim) is fine.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLiquidFlow(event: BlockFromToEvent) {
        val from = plugin.claimManager.getClaimAt(event.block.location)
        val to = plugin.claimManager.getClaimAt(event.toBlock.location)
        if (to == null) return                 // flowing into unclaimed land — fine
        if (from?.id == to.id) return          // entirely within one claim — fine
        // Crossing into a claim (from unclaimed or from a different claim) — block.
        event.isCancelled = true
    }

    // 15. Piston extend — refuse to push blocks INTO a claim from outside, or
    // to push a claim's blocks out from under their owner.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        val pistonClaim = plugin.claimManager.getClaimAt(event.block.location)
        for (block in event.blocks) {
            val srcClaim = plugin.claimManager.getClaimAt(block.location)
            val dest = block.getRelative(event.direction)
            val destClaim = plugin.claimManager.getClaimAt(dest.location)
            // Cancel if the source or destination crosses any claim boundary
            // we don't already own (i.e. piston is outside the affected claim).
            if (srcClaim != null && srcClaim.id != pistonClaim?.id) { event.isCancelled = true; return }
            if (destClaim != null && destClaim.id != pistonClaim?.id) { event.isCancelled = true; return }
        }
    }

    // 16a. Redstone activation — refuse to let an outside source power a
    //      block inside a claim. Lets internal redstone work normally; blocks
    //      "redstone tunnels" or wires laid up to the boundary by an attacker.
    //
    //      BlockRedstoneEvent isn't Cancellable; you suppress the change by
    //      setting newCurrent back to oldCurrent so vanilla treats it as a
    //      no-op tick.
    //
    //      Intrinsic sources (comparators, daylight sensors, pressure plates,
    //      buttons, levers, observers, etc.) derive their power from player
    //      interaction, environment, or entity detection — not from neighboring
    //      redstone wire. If such a block is inside the claim it is an internal
    //      mechanism and its power change is always allowed. The cross-boundary
    //      guard still fires on the downstream wires those blocks power.
    @EventHandler(priority = EventPriority.HIGH)
    fun onRedstone(event: BlockRedstoneEvent) {
        if (event.newCurrent <= event.oldCurrent) return  // only inspect rising edges
        val targetClaim = plugin.claimManager.getClaimAt(event.block.location) ?: return

        // Allow intrinsic sources that live inside the claim.
        if (isIntrinsicRedstoneSource(event.block)) return

        // If at least one powered neighbor is in the SAME claim, the signal
        // has an in-claim source and we let it through. If every powered
        // neighbor is outside this claim, the signal is sneaking in across
        // the boundary — neutralize it.
        val faces = arrayOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)
        val hasInClaimSource = faces.any { face ->
            val n = event.block.getRelative(face)
            if (n.blockPower <= 0) return@any false
            val nClaim = plugin.claimManager.getClaimAt(n.location)
            nClaim != null && nClaim.id == targetClaim.id
        }
        if (!hasInClaimSource) event.newCurrent = event.oldCurrent
    }

    // Blocks whose rising-edge power change is driven by their own internal state
    // (player interaction, light level, entity detection, block-state observation)
    // rather than by adjacent redstone signal. These should always function normally
    // when placed inside a claim; the boundary guard is enforced on the wires they
    // power, not on the sources themselves.
    private fun isIntrinsicRedstoneSource(block: Block): Boolean {
        val t = block.type
        return Tag.BUTTONS.isTagged(t)
            || Tag.PRESSURE_PLATES.isTagged(t)
            || t == Material.LEVER
            || t == Material.COMPARATOR
            || t == Material.DAYLIGHT_DETECTOR
            || t == Material.OBSERVER
            || t == Material.TRIPWIRE_HOOK
            || t == Material.TARGET
            || t == Material.SCULK_SENSOR
            || t == Material.CALIBRATED_SCULK_SENSOR
            || t == Material.LIGHTNING_ROD
            || t == Material.REDSTONE_TORCH
            || t == Material.REDSTONE_WALL_TORCH
    }

    // 16. Piston retract (sticky) — same boundary rule as extend.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        val pistonClaim = plugin.claimManager.getClaimAt(event.block.location)
        for (block in event.blocks) {
            val srcClaim = plugin.claimManager.getClaimAt(block.location)
            val dest = block.getRelative(event.direction)
            val destClaim = plugin.claimManager.getClaimAt(dest.location)
            if (srcClaim != null && srcClaim.id != pistonClaim?.id) { event.isCancelled = true; return }
            if (destClaim != null && destClaim.id != pistonClaim?.id) { event.isCancelled = true; return }
        }
    }

    // 17. Vehicle destruction (boats, minecarts) — safety net that fires after the
    //     entity-damage chain; ensures a vehicle in a claim cannot be destroyed by
    //     an unauthorized player even if an earlier event slipped through.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onVehicleDestroy(event: VehicleDestroyEvent) {
        val attacker = event.attacker ?: return
        val player = getPlayerAttacker(attacker) ?: return
        if (!plugin.claimManager.canAccess(player, event.vehicle.location)) {
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
