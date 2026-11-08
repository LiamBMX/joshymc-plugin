package com.liam.joshymc.milestone

import com.liam.joshymc.Joshymc
import org.bukkit.block.BlockFace
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerHarvestBlockEvent
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import java.util.UUID

/**
 * Event side of Milestones (issue #1420). Registered through GamemodeScopedListeners under
 * [com.liam.joshymc.gamemode.GamemodeFeature.MILESTONES]. Every progress handler is MONITOR with
 * ignoreCancelled, so it only reads what really happened and never changes other systems' outcome
 * (PvP, protection, drops). The only per-movement work is none: exploration is sampled once a second
 * by the manager.
 */
class MilestoneListener(private val plugin: Joshymc, private val manager: MilestoneManager) : Listener {

    /** Vanilla catch per hook, read before FishingManager (HIGHEST) swaps in a custom fish. */
    private val hookCatches = object : LinkedHashMap<UUID, Pair<String, Boolean>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Pair<String, Boolean>>?) = size > 256
    }

    // ── Players ─────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = manager.onJoin(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = manager.onQuit(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInput(event: PlayerInputEvent) {
        manager.touch(event.player)
        val input = event.input
        if (input.isForward || input.isBackward || input.isLeft || input.isRight) {
            manager.explorationState(event.player).lastInputAt = System.currentTimeMillis()
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) = manager.invalidateExploration(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = manager.invalidateExploration(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) = manager.invalidateExploration(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGlide(event: EntityToggleGlideEvent) {
        (event.entity as? Player)?.let { manager.invalidateExploration(it) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDismount(event: EntityDismountEvent) {
        (event.entity as? Player)?.let { manager.invalidateExploration(it) }
    }

    // ── Placed-block markers ────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) = manager.tracker.mark(event.blockPlaced)

    /** Cobblestone/stone/basalt generators: made blocks never count as natural. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onForm(event: BlockFormEvent) {
        val name = event.newState.type.name
        if (name in MiningRules.GENERATED) manager.tracker.mark(event.block, name)
    }

    /** Natural growth into a spot clears an old marker there (e.g. one left by a block that popped). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGrow(event: BlockGrowEvent) = manager.tracker.unmark(event.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpread(event: BlockSpreadEvent) = manager.tracker.unmark(event.block)

    /** Endermen and other entities setting a tracked block count as placed. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (event.to.isAir) manager.tracker.unmark(event.block) else manager.tracker.mark(event.block, event.to.name)
    }

    /** Moved blocks lose their natural status (their markers move with them, and the moved block is marked). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) = moveMarkers(event.blocks, event.direction)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) = moveMarkers(event.blocks, event.direction)

    private fun moveMarkers(blocks: List<org.bukkit.block.Block>, direction: BlockFace) {
        if (blocks.isEmpty()) return
        val names = blocks.map { it.type.name }
        for (block in blocks) manager.tracker.unmark(block)
        for ((i, block) in blocks.withIndex()) manager.tracker.mark(block.getRelative(direction), names[i])
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        for (block in event.blockList()) manager.tracker.unmark(block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        for (block in event.blockList()) manager.tracker.unmark(block)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: ChunkUnloadEvent) = manager.tracker.forget(event.chunk)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldUnload(event: WorldUnloadEvent) = manager.tracker.forgetWorld(event.world.uid)

    // ── Farming / Mining ────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) = manager.processBreak(event.player, event.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHarvest(event: PlayerHarvestBlockEvent) = manager.processHarvest(event.player, event.harvestedBlock)

    // ── Fishing ─────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFishCapture(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val stack = (event.caught as? Item)?.itemStack ?: return
        hookCatches[event.hook.uniqueId] = stack.type.name to stack.enchantments.isNotEmpty()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val captured = hookCatches.remove(event.hook.uniqueId)
        if (event.isCancelled) return
        val fallback = (event.caught as? Item)?.itemStack
        val name = captured?.first ?: fallback?.type?.name
        val enchanted = captured?.second ?: (fallback?.enchantments?.isNotEmpty() == true)
        manager.processCatch(event.player, event.hook.uniqueId, event.hook.location, name, enchanted)
    }

    // ── Combat ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity is Player) return
        if (skip(entity)) return
        val killer = entity.killer ?: return
        // Only the player's own hit (melee or their projectile) counts; traps, fall damage, lava
        // and other automated finishes don't, even when vanilla still credits the player.
        if (directKiller(event) != killer) return
        manager.processMobKill(killer, entity.type.name, entity.entitySpawnReason?.name ?: "DEFAULT")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val victim = event.player
        val killer = directKiller(event) as? Player ?: return
        if (killer == victim) return
        manager.processPlayerKill(killer, victim)
    }

    private fun directKiller(event: EntityDeathEvent): org.bukkit.entity.Entity? {
        val source = event.damageSource
        val causing = source.causingEntity ?: return null
        val direct = source.directEntity
        // Melee: the causing entity hit directly. Projectiles: one the causing entity shot.
        return when {
            direct == null || direct == causing -> causing
            direct is Projectile && direct.shooter == causing -> causing
            else -> null
        }
    }

    private fun skip(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return "joshymc_combat_npc" in tags || "NPC" in tags ||
            com.liam.joshymc.communityevent.game.MobWavesGame.EVENT_MOB_TAG in tags
    }
}
