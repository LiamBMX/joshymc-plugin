package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.ClaimManager.PublicFlag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.Tag
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.AbstractVillager
import org.bukkit.entity.Boat
import org.bukkit.entity.ChestBoat
import org.bukkit.entity.Egg
import org.bukkit.entity.Entity
import org.bukkit.entity.Firework
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Snowball
import org.bukkit.entity.Tameable
import org.bukkit.entity.Trident
import org.bukkit.entity.WindCharge
import org.bukkit.entity.minecart.RideableMinecart
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.event.vehicle.VehicleDestroyEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ClaimProtectionListener(private val plugin: Joshymc) : Listener {

    private val messageCooldowns = ConcurrentHashMap<UUID, Long>()

    private val denyMessage = Component.text("You cannot do that in this claim.", NamedTextColor.RED)
    private val pvpDenyMessage = Component.text("PvP is disabled in this claim.", NamedTextColor.RED)
    private val projectileDenyMessage = Component.text("Projectiles are disabled in this claim.", NamedTextColor.RED)
    private val flyDenyMessage = Component.text("Flying is disabled in this claim.", NamedTextColor.RED)

    private fun denyWithMessage(player: Player, message: Component = denyMessage) {
        val now = System.currentTimeMillis()
        val last = messageCooldowns[player.uniqueId] ?: 0L
        if (now - last >= 1000L) {
            messageCooldowns[player.uniqueId] = now
            plugin.commsManager.send(player, message)
        }
    }

    // 1. Block breaking
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (!plugin.claimManager.canPerform(player, event.block.location, PublicFlag.BREAK)) {
            event.isCancelled = true
            denyWithMessage(player)
        }
    }

    // 2. Block placing
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (!plugin.claimManager.canPerform(player, event.block.location, PublicFlag.BUILD)) {
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

        // Ender chests are personal storage — every player sees only their own
        // inventory, so blocking them via claim protection serves no purpose.
        if (block.type == Material.ENDER_CHEST) return

        if (plugin.claimManager.canAccess(player, block.location)) return

        // Untrusted: the clicked block and the held item are judged separately
        // against the claim's public flags. An allowed item use still goes
        // through its own event (block place, bucket, projectile launch), which
        // checks the exact location again.
        val blockFlag = interactFlagFor(block)
        val useBlock = blockFlag != null && plugin.claimManager.canPerform(player, block.location, blockFlag)
        val itemFlag = event.item?.let { itemFlagFor(it) }
        val useItem = itemFlag != null && plugin.claimManager.canPerform(player, block.location, itemFlag)
        if (!useBlock) event.setUseInteractedBlock(Event.Result.DENY)
        if (!useItem) event.setUseItemInHand(Event.Result.DENY)
        if (!useBlock && !useItem) denyWithMessage(player)
    }

    /** The public flag that covers right-clicking this block, or null if only full access may use it. */
    private fun interactFlagFor(block: Block): PublicFlag? {
        val type = block.type
        return when {
            Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type) -> PublicFlag.DOORS
            Tag.BUTTONS.isTagged(type) || type == Material.LEVER -> PublicFlag.SWITCHES
            block.getState(false) is org.bukkit.block.Container -> PublicFlag.CONTAINERS
            else -> null
        }
    }

    /** The public flag that covers using this held item on a block, or null if only full access may use it. */
    private fun itemFlagFor(item: ItemStack): PublicFlag? {
        val type = item.type
        return when {
            type.isBlock -> PublicFlag.BUILD
            type.name.endsWith("BUCKET") -> PublicFlag.BUCKETS
            type in PROJECTILE_ITEMS -> PublicFlag.PROJECTILES
            else -> null
        }
    }

    // 4. Entity damage by entity — PvP and animal/villager protection
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val attacker = getPlayerAttacker(event.damager) ?: return
        val victim = event.entity

        if (victim is Player) {
            // Self-inflicted (e.g. a player's own thrown wind charge knocking
            // them back) isn't PvP — don't let claim PvP-toggle protection
            // cancel the vanilla knockback/damage a player deals to themselves.
            if (attacker == victim) return
            val claim = plugin.claimManager.getClaimAt(victim.location) ?: return
            // Owner opted in to PvP — allow it. This only lifts the claim's own
            // block; global PvP rules (arenas, combat, PvP toggles) still apply.
            if (claim.pvpEnabled) return
            // Victim's claim has PvP disabled — block all PvP damage to them,
            // regardless of where the attacker is standing.
            event.isCancelled = true
            denyWithMessage(attacker, pvpDenyMessage)
        } else {
            // Non-player entities (animals, villagers, etc.). MOB_COMBAT only
            // opens up ordinary mobs: villagers, tamed pets, Citizens NPCs and
            // non-mob entities (armor stands, frames, displays) stay protected.
            val allowed = if (isPublicMobTarget(victim)) {
                plugin.claimManager.canPerform(attacker, victim.location, PublicFlag.MOB_COMBAT)
            } else {
                plugin.claimManager.canAccess(attacker, victim.location)
            }
            if (!allowed) {
                event.isCancelled = true
                denyWithMessage(attacker)
            }
        }
    }

    private fun isPublicMobTarget(entity: Entity): Boolean {
        if (entity !is Mob) return false
        if (entity is AbstractVillager) return false
        if (entity is Tameable && entity.isTamed) return false
        if (entity.hasMetadata("NPC")) return false
        return true
    }

    // 5. Bucket empty
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        val player = event.player
        if (!plugin.claimManager.canPerform(player, event.block.location, PublicFlag.BUCKETS)) {
            event.isCancelled = true
            denyWithMessage(player)
        }
    }

    // 6. Bucket fill
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        val player = event.player
        if (!plugin.claimManager.canPerform(player, event.block.location, PublicFlag.BUCKETS)) {
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

    // 10. Entity explosion — protect claimed blocks; honour per-claim TNT toggle for primed TNT
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (event.entity is TNTPrimed) {
            val sourceClaim = plugin.claimManager.getClaimAt(event.entity.location)
            if (sourceClaim != null && sourceClaim.tntEnabled) {
                // TNT placed inside a claim that has TNT enabled: allow damage within
                // the same claim, but still protect blocks belonging to other claims.
                event.blockList().removeIf { block ->
                    val target = plugin.claimManager.getClaimAt(block.location)
                    target != null && target.id != sourceClaim.id
                }
            } else {
                // TNT outside any claim, or in a claim with TNT disabled: protect all claimed blocks.
                event.blockList().removeIf { plugin.claimManager.getClaimAt(it.location) != null }
            }
        } else {
            event.blockList().removeIf { plugin.claimManager.getClaimAt(it.location) != null }
        }
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
        } else if (entity is FallingBlock) {
            // Gravity blocks (sand, gravel, concrete powder, etc.) landing inside a
            // claim is normal physics — don't block it.
            return
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
        val entity = event.rightClicked
        val flag = when {
            entity is AbstractVillager -> PublicFlag.VILLAGER_TRADING
            entity is Boat && entity !is ChestBoat -> PublicFlag.VEHICLES
            entity is RideableMinecart -> PublicFlag.VEHICLES
            else -> null
        }
        val allowed = if (flag != null) {
            plugin.claimManager.canPerform(player, entity.location, flag)
        } else {
            plugin.claimManager.canAccess(player, entity.location)
        }
        if (!allowed) {
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

    // Redstone signals are deliberately NOT restricted by claims (#1042): dust,
    // observers, etc. work normally and may cross claim boundaries. Griefing is
    // covered by the break/place/interact checks and the piston boundary checks.

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

    // 18. Fire spread — block fire spreading into (or within) a claimed area.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFireSpread(event: BlockSpreadEvent) {
        if (event.newState.type != Material.FIRE && event.newState.type != Material.SOUL_FIRE) return
        if (plugin.claimManager.getClaimAt(event.block.location) != null) {
            event.isCancelled = true
        }
    }

    // 19. Block burn — prevent blocks inside a claim from being consumed by fire.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        if (plugin.claimManager.getClaimAt(event.block.location) != null) {
            event.isCancelled = true
        }
    }

    // 20. Fishing rod — prevent hooking lit TNT to stop it being reeled into claims.
    //     PlayerFishEvent fires when the hook makes contact; cancelling it releases the entity.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFishEntity(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_ENTITY) return
        val caught = event.caught
        if (caught is TNTPrimed) {
            event.isCancelled = true
            return
        }
        // Hooking a player in a PvP-disabled claim: cancelling the event stops
        // the pull, so rods can't drag them out of the claim.
        if (caught is Player && caught != event.player) {
            val claim = plugin.claimManager.getClaimAt(caught.location) ?: return
            if (claim.pvpEnabled) return
            event.isCancelled = true
            denyWithMessage(event.player, Component.text("You cannot pull players from a PvP-protected claim.", NamedTextColor.RED))
        }
    }

    // 21. Player movement — block denied players from entering a claim.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        // Only react on block-boundary crossings to avoid firing every frame.
        if (from.blockX == to.blockX && from.blockZ == to.blockZ) return
        val player = event.player
        if (!plugin.claimManager.isDenied(player, to)) return
        event.isCancelled = true
        val now = System.currentTimeMillis()
        val last = messageCooldowns[player.uniqueId] ?: 0L
        if (now - last >= 1000L) {
            messageCooldowns[player.uniqueId] = now
            plugin.commsManager.send(player, Component.text("You have been banned from this claim.", NamedTextColor.RED))
        }
    }

    // 22. Projectile launch — untrusted players need PROJECTILES where they fire from.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val projectile = event.entity
        if (!isGatedProjectile(projectile)) return
        val player = projectile.shooter as? Player ?: return
        if (!plugin.claimManager.canPerform(player, projectile.location, PublicFlag.PROJECTILES)) {
            event.isCancelled = true
            denyWithMessage(player, projectileDenyMessage)
        }
    }

    // 23. Projectile block impact — a shot fired from outside still can't hit
    //     buttons, targets or doors (wind charges) in a claim that refuses
    //     projectiles. Hits on entities are judged by the PvP/mob combat rules.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val block = event.hitBlock ?: return
        val projectile = event.entity
        // Tridents are skipped: removing one would delete the player's item.
        if (!isGatedProjectile(projectile) || projectile is Trident) return
        val player = projectile.shooter as? Player ?: return
        if (!plugin.claimManager.canPerform(player, block.location, PublicFlag.PROJECTILES)) {
            event.isCancelled = true
            projectile.remove()
        }
    }

    private fun isGatedProjectile(projectile: Projectile): Boolean = when (projectile) {
        is AbstractArrow, is Snowball, is Egg, is WindCharge -> true
        // Crossbow fireworks only; elytra boost rockets are not weapons.
        is Firework -> projectile.isShotAtAngle
        else -> false
    }

    // 24. Flight — FLY never grants flight; it only decides whether a player who
    //     can already fly may do so here. Taking off is refused, and flying in
    //     from outside is stopped at the border instead of dropping them.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onToggleFlight(event: PlayerToggleFlightEvent) {
        if (!event.isFlying) return
        val player = event.player
        if (!isFlightRestricted(player)) return
        if (!plugin.claimManager.canPerform(player, player.location, PublicFlag.FLY)) {
            event.isCancelled = true
            denyWithMessage(player, flyDenyMessage)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFlyingMove(event: PlayerMoveEvent) {
        val player = event.player
        if (!player.isFlying || !isFlightRestricted(player)) return
        val from = event.from
        val to = event.to
        if (from.blockX == to.blockX && from.blockZ == to.blockZ) return
        if (plugin.claimManager.canPerform(player, to, PublicFlag.FLY)) return
        if (plugin.claimManager.canPerform(player, from, PublicFlag.FLY)) {
            event.isCancelled = true
        } else {
            // Already inside (teleported in, or the owner just turned FLY off).
            player.isFlying = false
        }
        denyWithMessage(player, flyDenyMessage)
    }

    private fun isFlightRestricted(player: Player): Boolean =
        player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE

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

    companion object {
        private val PROJECTILE_ITEMS = setOf(
            Material.BOW, Material.CROSSBOW, Material.TRIDENT, Material.SNOWBALL,
            Material.EGG, Material.BLUE_EGG, Material.BROWN_EGG, Material.WIND_CHARGE
        )
    }
}
