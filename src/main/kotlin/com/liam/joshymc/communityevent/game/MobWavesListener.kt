package com.liam.joshymc.communityevent.game

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityCombustByBlockEvent
import org.bukkit.event.entity.EntityCombustByEntityEvent
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.event.entity.SlimeSplitEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.world.EntitiesLoadEvent

/**
 * Entity side of hosted Mob Waves (issue #1192). Registered once by ListenerManager. Every
 * handler is a cheap in-memory check ([MobWavesGame.isEventMob] is a set lookup) and does
 * nothing for ordinary mobs or outside a running Mob Waves event: wave progress is driven by
 * these events, never by scanning the world. Damage between players and event mobs is decided
 * in [HostedGameListener] with the other hosted games' damage rules.
 */
class MobWavesListener(private val games: HostedGames) : Listener {

    private val mobs get() = games.mobs

    /** An event mob, or a projectile/fireball one of them fired. */
    private fun fromEventMob(entity: Entity?): Boolean = when {
        entity == null -> false
        mobs.isEventMob(entity) -> true
        entity is Projectile -> mobs.isEventMob(entity.shooter as? Entity)
        else -> false
    }

    // ── Wave tracking ─────────────────────────────────────

    /** No experience ever (event-world XP would carry into survival); item drops only if configured. Equipment never drops. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeathDrops(event: EntityDeathEvent) {
        if (event.entity is Player || !mobs.isEventMob(event.entity)) return
        event.droppedExp = 0
        if (!mobs.dropsItems) event.drops.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: EntityDeathEvent) {
        if (event.entity is Player) return
        mobs.mobDied(event.entity.uniqueId)
    }

    /** Despawned, unloaded or removed by a command: resolved so the wave can still end. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRemove(event: EntityRemoveFromWorldEvent) {
        if (event.entity is Player) return
        mobs.mobRemoved(event.entity.uniqueId)
    }

    /** A leftover event mob from a crash or an earlier event never comes back with its chunk. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        if (event.world.name != games.eventWorldName) return
        for (entity in event.entities) if (entity !is Player && mobs.isStale(entity)) entity.remove()
    }

    /**
     * While Mob Waves runs, nothing else spawns inside its arena: no natural spawns, jockeys,
     * zombie reinforcements or spawn eggs, so only tracked wave mobs fight there. Spawns by
     * plugins (including the waves themselves) are left alone.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSpawn(event: CreatureSpawnEvent) {
        if (event.spawnReason == CreatureSpawnEvent.SpawnReason.CUSTOM) return
        if (games.running !== mobs) return
        if (mobs.inArena(event.location, 2)) event.isCancelled = true
    }

    // ── Behaviour ─────────────────────────────────────────

    /** Event mobs only go after living participants: never spectators, staff or bystanders. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTarget(event: EntityTargetLivingEntityEvent) {
        if (!mobs.isEventMob(event.entity)) return
        val target = event.target ?: return
        if (target !is Player || !mobs.isCompeting(target.uniqueId)) event.isCancelled = true
    }

    /** No burning in daylight (fire from blocks and burning weapons still applies). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCombust(event: EntityCombustEvent) {
        if (event is EntityCombustByEntityEvent || event is EntityCombustByBlockEvent) return
        if (mobs.isEventMob(event.entity)) event.isCancelled = true
    }

    /** Zombies turning into drowned, skeletons into strays, ...: the tracked mob would vanish into an untracked one. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTransform(event: EntityTransformEvent) {
        if (mobs.isEventMob(event.entity)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSlimeSplit(event: SlimeSplitEvent) {
        if (mobs.isEventMob(event.entity)) event.isCancelled = true
    }

    /** A witch's potion only affects living participants. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPotion(event: PotionSplashEvent) {
        if (!mobs.isEventMob(event.potion.shooter as? Entity)) return
        for (hit in event.affectedEntities) {
            if (hit !is Player || !mobs.isCompeting(hit.uniqueId)) event.setIntensity(hit, 0.0)
        }
    }

    // ── Arena protection ──────────────────────────────────

    /** Event mobs never change blocks: no door breaking, trampling, block pickup or ravager grief. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onChangeBlock(event: EntityChangeBlockEvent) {
        if (mobs.isEventMob(event.entity)) event.isCancelled = true
    }

    /** Creepers and mob fireballs still hurt participants but never break a block or drop anything. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onExplode(event: EntityExplodeEvent) {
        if (!fromEventMob(event.entity)) return
        event.blockList().clear()
        event.yield = 0f
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onIgnite(event: BlockIgniteEvent) {
        if (fromEventMob(event.ignitingEntity)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        if (fromEventMob(event.remover)) event.isCancelled = true
    }
}
