package com.liam.joshymc.lifesteal.quests

import com.liam.joshymc.Joshymc
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
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
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent

/**
 * Event side of the Lifesteal quests (issue #1425). Registered once where Lifesteal is hosted
 * (ListenerManager); every handler does nothing unless [LifestealQuestManager.running] and the
 * activity happened in a Lifesteal world. Progress handlers are MONITOR with ignoreCancelled: they
 * only read what really happened and never change any other system's outcome (PvP, protection,
 * drops, hearts).
 */
class LifestealQuestListener(private val plugin: Joshymc) : Listener {

    private val manager get() = plugin.lifestealQuestManager

    private fun lifesteal(block: Block): Boolean = manager.isLifesteal(block.world)

    // ── Players ─────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = manager.onJoin(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = manager.onQuit(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInput(event: PlayerInputEvent) {
        if (!manager.running) return
        manager.touch(event.player)
        val input = event.input
        if (input.isForward || input.isBackward || input.isLeft || input.isRight) {
            manager.explorationState(event.player).lastInputAt = System.currentTimeMillis()
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) = manager.invalidateExploration(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        manager.invalidateExploration(event.player)
        // Boards are only loaded for players who joined in, or moved into, Lifesteal.
        if (manager.running && manager.book(event.player) == null) manager.load(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) = manager.invalidateExploration(event.player)

    // ── Placed-block markers (Lifesteal worlds only, also while paused) ────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (manager.tracking && lifesteal(event.blockPlaced)) manager.tracker.mark(event.blockPlaced)
    }

    /** Cobblestone/stone/basalt generators: made blocks never count as natural. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onForm(event: BlockFormEvent) {
        if (!manager.tracking || !lifesteal(event.block)) return
        val name = event.newState.type.name
        if (name in QuestBlocks.GENERATED) manager.tracker.mark(event.block, name)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGrow(event: BlockGrowEvent) {
        if (manager.tracking && lifesteal(event.block)) manager.tracker.unmark(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpread(event: BlockSpreadEvent) {
        if (manager.tracking && lifesteal(event.block)) manager.tracker.unmark(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (!manager.tracking || !lifesteal(event.block)) return
        if (event.to.isAir) manager.tracker.unmark(event.block) else manager.tracker.mark(event.block, event.to.name)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) = moveMarkers(event.blocks, event.direction)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) = moveMarkers(event.blocks, event.direction)

    /** Moved blocks lose their natural status. */
    private fun moveMarkers(blocks: List<Block>, direction: BlockFace) {
        if (!manager.tracking || blocks.isEmpty() || !lifesteal(blocks.first())) return
        val names = blocks.map { it.type.name }
        for (block in blocks) manager.tracker.unmark(block)
        for ((i, block) in blocks.withIndex()) manager.tracker.mark(block.getRelative(direction), names[i])
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (manager.tracking) for (block in event.blockList()) if (lifesteal(block)) manager.tracker.unmark(block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (manager.tracking) for (block in event.blockList()) if (lifesteal(block)) manager.tracker.unmark(block)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: ChunkUnloadEvent) = manager.tracker.forget(event.chunk)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldUnload(event: WorldUnloadEvent) = manager.tracker.forgetWorld(event.world.uid)

    // ── Survival activity ───────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (!lifesteal(event.block)) return
        if (manager.running) manager.onBlockBreak(event.player, event.block)
        else if (manager.tracking) manager.tracker.unmark(event.block)
    }

    /** Vanilla catch per hook, read before FishingManager (HIGHEST) could swap in a custom fish. */
    private val hookCatches = object : LinkedHashMap<java.util.UUID, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<java.util.UUID, String>?) = size > 256
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFishCapture(event: PlayerFishEvent) {
        if (!manager.running || event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val stack = (event.caught as? Item)?.itemStack ?: return
        hookCatches[event.hook.uniqueId] = stack.type.name
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val captured = hookCatches.remove(event.hook.uniqueId)
        if (event.isCancelled || !manager.running) return
        val name = captured ?: (event.caught as? Item)?.itemStack?.type?.name
        manager.onFishCatch(event.player, event.hook.uniqueId, name)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        if (!manager.running) return
        val entity = event.entity
        if (entity is Player || entity !is Enemy) return
        if (skip(entity)) return
        val killer = entity.killer ?: return
        // Only the player's own hit (melee or their projectile): traps, lava and fall damage don't count.
        if (directKiller(event) != killer) return
        manager.onMobKill(killer, entity.type.name, entity.entitySpawnReason?.name ?: "DEFAULT")
    }

    // ── PvP ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (!manager.running) return
        val victim = event.entity as? Player ?: return
        val attacker = event.damageSource.causingEntity as? Player ?: return
        if (attacker.uniqueId == victim.uniqueId) return
        val amount = minOf(event.finalDamage, victim.health)
        manager.onPvpDamage(attacker, victim, amount)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        if (!manager.running) return
        val victim = event.player
        val killer = event.damageSource.causingEntity as? Player ?: return
        if (killer.uniqueId == victim.uniqueId) return
        manager.onPvpKill(killer, victim)
    }

    private fun directKiller(event: EntityDeathEvent): Entity? {
        val source = event.damageSource
        val causing = source.causingEntity ?: return null
        val direct = source.directEntity
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
