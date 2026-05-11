package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerTeleportEvent

/**
 * Per-player mob visibility toggle. The mob is still ONE server-side entity
 * — players sharing the world all see the same mob if their setting is on.
 * Players with the setting off use Paper's [Player.hideEntity] so the mob
 * is never sent to their client, AND we cancel mob targeting + damage in
 * both directions so the mob and the player are functionally invisible to
 * each other.
 *
 * Two players with the setting ON both see the same mob and can both hit
 * it / be targeted by it as normal.
 */
class MobVisibilityListener(private val plugin: Joshymc) : Listener {

    companion object {
        private const val SETTING_KEY = "mob_visibility"

        /**
         * Apply the current setting state to [player]. Iterates every loaded
         * living entity in their world; hides or reveals based on [enabled].
         * Called by:
         *   - the SettingDef.onToggle when the player flips the setting
         *   - the join handler when they connect
         */
        fun applyTo(plugin: Joshymc, player: Player, enabled: Boolean) {
            val world = player.world
            for (entity in world.livingEntities) {
                if (entity is Player) continue
                if (enabled) {
                    // Toggling on: re-show every mob the player had hidden.
                    if (!player.canSee(entity)) player.showEntity(plugin, entity)
                } else {
                    if (player.canSee(entity)) player.hideEntity(plugin, entity)
                    // ALSO drop any existing target this mob had on the
                    // player. Without this, a mob already locked onto the
                    // player at the moment they toggle off will keep
                    // chasing — EntityTargetEvent only fires on new
                    // acquisitions, not on mid-track refreshes.
                    if (entity is Mob && entity.target == player) {
                        entity.target = null
                    }
                }
            }
        }
    }

    /** Convenience accessor — true when the player wants mobs visible. */
    private fun visible(player: Player): Boolean =
        plugin.settingsManager.getSetting(player, SETTING_KEY)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        // Defer one tick — settings cache is loaded lazily on first read,
        // and the player's scoreboard / chunk visibility setup needs to
        // settle before we start sending entity-hide packets.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) applyTo(plugin, player, visible(player))
        }, 5L)
    }

    /**
     * Re-apply hide state when crossing worlds. Cross-world teleports reset
     * Paper's per-player entity tracking, so every mob in the new world
     * becomes visible again until we re-hide them. We fire at tick 1 to
     * intercept entity spawn packets before the entity tracker sends them,
     * then again at tick 40 to catch mobs in chunks that loaded after the
     * initial pass.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) applyTo(plugin, player, visible(player))
        }, 1L)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) applyTo(plugin, player, visible(player))
        }, 40L)
    }

    /**
     * Same-world long-distance teleports (e.g. /tpa, /warp inside the
     * overworld) can move the player into chunks that weren't tracked yet.
     * Re-apply on every teleport — applyTo is idempotent.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) applyTo(plugin, player, visible(player))
        }, 1L)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) applyTo(plugin, player, visible(player))
        }, 40L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSpawn(event: CreatureSpawnEvent) {
        val entity = event.entity
        // Hide newly-spawned mobs from anyone who has the setting off.
        for (online in entity.world.players) {
            if (!visible(online) && online.canSee(entity)) {
                online.hideEntity(plugin, entity)
            }
        }
    }

    /**
     * Cancel mob targeting onto a player who's hiding mobs. Without this,
     * the mob would still chase / attack-anim them server-side even though
     * the player can't see it — and the next damage tick would land.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onTarget(event: EntityTargetEvent) {
        val target = event.target as? Player ?: return
        if (!visible(target)) {
            event.target = null
            event.isCancelled = true
        }
    }

    /**
     * Mutual damage immunity. Covers:
     *   - direct mob hit (creeper explosion, zombie melee, …) → player
     *   - projectile fired by mob (skeleton arrow, ghast fireball, …) → player
     *   - player → mob (so a hidden mob can't be hit either)
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        val victim = event.entity

        if (victim is Player && !visible(victim)) {
            // Resolve the actual mob source — direct mob, or the mob shooter
            // behind a projectile / TNT. If there's a non-player living
            // source, this is a mob hit and we cancel.
            val mobSource: LivingEntity? = when {
                damager is Player -> null
                damager is LivingEntity -> damager
                damager is Projectile -> {
                    val shooter = damager.shooter
                    if (shooter is LivingEntity && shooter !is Player) shooter else null
                }
                else -> null
            }
            if (mobSource != null) {
                event.isCancelled = true
                return
            }
        }

        // Player hits Mob: skip if attacker has setting off.
        if (damager is Player && victim is LivingEntity && victim !is Player) {
            if (!visible(damager)) event.isCancelled = true
        }
    }

    /**
     * Catch explosion / non-attributed damage paths to a mob-hidden player.
     * Creeper blasts and TNT chains land via EntityDamageEvent with cause
     * ENTITY_EXPLOSION / BLOCK_EXPLOSION; not all of those fire through
     * EntityDamageByEntityEvent. If the player has mobs off, suppress the
     * explosion damage entirely — so a creeper that somehow primed near
     * them can never hit them.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onExplosionDamage(event: EntityDamageEvent) {
        if (event is EntityDamageByEntityEvent) return // handled by onDamage
        val player = event.entity as? Player ?: return
        if (visible(player)) return
        when (event.cause) {
            EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
            EntityDamageEvent.DamageCause.BLOCK_EXPLOSION,
            EntityDamageEvent.DamageCause.MAGIC,
            EntityDamageEvent.DamageCause.WITHER -> {
                event.isCancelled = true
            }
            else -> Unit
        }
    }
}
