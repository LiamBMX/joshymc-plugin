package com.liam.joshymc.listener

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import com.liam.joshymc.Joshymc
import com.liam.joshymc.mobvisibility.MobVisibilityRules
import org.bukkit.Bukkit
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Bee
import org.bukkit.entity.Enderman
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.PigZombie
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.entity.Villager
import org.bukkit.entity.Wolf
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityTameEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.UUID

/**
 * Per-player Mobs OFF setting (`mob_visibility`). **Visibility only** (issue #1421): a
 * player with Mobs OFF gets Paper's [Player.hideEntity] for hostile mobs and for neutral
 * mobs while they're aggressive; nothing else changes. Mobs still spawn, target, attack,
 * drop and despawn exactly as for everyone else, and other players are unaffected.
 *
 * Which entities hide is the Bukkit-free [MobVisibilityRules]:
 *  - hostile: always hidden;
 *  - neutral (Enderman, Piglin, Zombified Piglin, Wolf, Bee, Iron Golem, ...): hidden only
 *    while aggressive (targeting a player, or its own anger state);
 *  - passive, tamed, villagers, armor stands, xray helpers: always visible.
 *
 * Aggression changes are caught by events (target changes, damage to the mob) that put the
 * mob on a small watch list checked every [MobVisibilityRules.WATCH_INTERVAL_TICKS]; anger
 * that ends without an event (it expires) is seen there. A sweep every
 * [MobVisibilityRules.SWEEP_INTERVAL_TICKS] looks only near players with Mobs OFF.
 *
 * Paper keeps a player's hidden entities by UUID, so a hidden mob stays hidden when it
 * leaves and re-enters tracking range; join, world change and teleports re-apply.
 */
class MobVisibilityListener(private val plugin: Joshymc) : Listener {

    companion object {
        private const val SETTING_KEY = "mob_visibility"

        /** Watched neutral mob → consecutive calm checks. Main thread only. */
        private val watched = HashMap<UUID, Int>()

        private fun exempt(entity: LivingEntity): Boolean =
            entity is ArmorStand ||
                entity is Villager ||
                (entity is Tameable && entity.isTamed) ||
                entity.scoreboardTags.contains("joshymc_xray")

        private fun kind(entity: LivingEntity): MobVisibilityRules.Kind =
            MobVisibilityRules.kindOf(entity.type.name)

        /** The mob's current aggression: a live player target, or its own anger state. */
        private fun isAggressive(entity: LivingEntity): Boolean {
            val target = (entity as? Mob)?.target
            val targetIsPlayer = target is Player && target.isValid && !target.isDead
            val angry = when (entity) {
                is Wolf -> entity.isAngry
                is PigZombie -> entity.isAngry
                is Bee -> entity.anger > 0
                is Enderman -> entity.isScreaming
                else -> false
            }
            return MobVisibilityRules.isAggressive(targetIsPlayer, angry)
        }

        private fun shouldHide(entity: LivingEntity): Boolean {
            if (entity is Player) return false
            val kind = kind(entity)
            if (kind == MobVisibilityRules.Kind.PASSIVE) return false
            val exempt = exempt(entity)
            val aggressive = kind == MobVisibilityRules.Kind.NEUTRAL && !exempt && isAggressive(entity)
            return MobVisibilityRules.shouldHide(kind, exempt, aggressive)
        }

        private fun isNeutral(entity: LivingEntity): Boolean =
            kind(entity) == MobVisibilityRules.Kind.NEUTRAL && !exempt(entity)

        private fun mobsVisible(plugin: Joshymc, player: Player): Boolean =
            plugin.settingsManager.getSetting(player, SETTING_KEY)

        /**
         * Hide or show [entity] for [player] according to the rules. `showEntity(plugin, …)`
         * only lifts JoshyMC's own hide, so vanish or other plugins' hides stay in place.
         */
        private fun sync(plugin: Joshymc, player: Player, entity: LivingEntity, hide: Boolean) {
            if (hide) {
                if (player.canSee(entity)) player.hideEntity(plugin, entity)
            } else if (!player.canSee(entity)) {
                player.showEntity(plugin, entity)
            }
        }

        /** Re-evaluate [entity] for every Mobs OFF player in its world. */
        private fun syncForWorld(plugin: Joshymc, entity: LivingEntity) {
            if (!entity.isValid) return
            val hide = shouldHide(entity)
            for (online in entity.world.players) {
                if (!mobsVisible(plugin, online)) sync(plugin, online, entity, hide)
            }
        }

        private fun watch(entity: LivingEntity) {
            if (watched.size >= MobVisibilityRules.MAX_WATCHED && entity.uniqueId !in watched) return
            watched[entity.uniqueId] = 0
        }

        /**
         * Apply [player]'s setting to every loaded living entity in their world. Called by
         * the setting's onToggle and after join / world change / teleport. With Mobs ON
         * ([enabled]) every JoshyMC hide is lifted.
         */
        fun applyTo(plugin: Joshymc, player: Player, enabled: Boolean) {
            for (entity in player.world.livingEntities) {
                if (entity is Player) continue
                if (enabled) {
                    if (!player.canSee(entity)) player.showEntity(plugin, entity)
                    continue
                }
                if (isNeutral(entity) && isAggressive(entity)) watch(entity)
                sync(plugin, player, entity, shouldHide(entity))
            }
        }
    }

    fun start() {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { checkWatched() },
            MobVisibilityRules.WATCH_INTERVAL_TICKS, MobVisibilityRules.WATCH_INTERVAL_TICKS)
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sweep() },
            MobVisibilityRules.SWEEP_INTERVAL_TICKS, MobVisibilityRules.SWEEP_INTERVAL_TICKS)
    }

    /** Re-check watched neutral mobs; drops gone ones and ones calm for long enough. */
    private fun checkWatched() {
        if (watched.isEmpty()) return
        val iterator = watched.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val entity = Bukkit.getEntity(entry.key) as? LivingEntity
            if (entity == null || !entity.isValid) {
                iterator.remove()
                continue
            }
            val aggressive = isNeutral(entity) && isAggressive(entity)
            syncForWorld(plugin, entity)
            val next = MobVisibilityRules.nextCalmCount(aggressive, entry.value)
            if (next == null) iterator.remove() else entry.setValue(next)
        }
    }

    /**
     * Backstop near Mobs OFF players only: re-hides hostiles that slipped through and
     * catches neutral aggression that no event reported (e.g. brain-driven targeting).
     */
    private fun sweep() {
        val r = MobVisibilityRules.SWEEP_RADIUS
        for (player in Bukkit.getOnlinePlayers()) {
            if (mobsVisible(plugin, player)) continue
            for (entity in player.getNearbyEntities(r, r, r)) {
                if (entity !is LivingEntity || entity is Player) continue
                val hide = shouldHide(entity)
                if (hide && isNeutral(entity)) watch(entity)
                sync(plugin, player, entity, hide)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        // Defer — the settings cache and the player's tracking setup need to settle first.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) applyTo(plugin, player, mobsVisible(plugin, player))
        }, 5L)
    }

    /**
     * Re-apply when crossing worlds: tick 1 for what's already loaded, tick 40 for mobs in
     * chunks that loaded after the first pass.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = reapplySoon(event.player)

    /** Long same-world teleports move the player into chunks that weren't tracked yet. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) = reapplySoon(event.player)

    private fun reapplySoon(player: Player) {
        for (delay in longArrayOf(1L, 40L)) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (player.isOnline) applyTo(plugin, player, mobsVisible(plugin, player))
            }, delay)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSpawn(event: CreatureSpawnEvent) = hideNewEntity(event.entity)

    /** Every entity entering the world, including mobs loaded with a chunk. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onAddToWorld(event: EntityAddToWorldEvent) {
        val entity = event.entity as? LivingEntity ?: return
        hideNewEntity(entity)
    }

    private fun hideNewEntity(entity: LivingEntity) {
        if (entity is Player) return
        val kind = kind(entity)
        if (kind == MobVisibilityRules.Kind.PASSIVE) return
        val players = entity.world.players
        if (players.none { !mobsVisible(plugin, it) }) return
        if (!shouldHide(entity)) return
        if (kind == MobVisibilityRules.Kind.NEUTRAL) watch(entity)
        for (online in players) {
            if (!mobsVisible(plugin, online) && online.canSee(entity)) online.hideEntity(plugin, entity)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRemoveFromWorld(event: EntityRemoveFromWorldEvent) {
        watched.remove(event.entity.uniqueId)
    }

    /**
     * Target acquired or dropped. The new target is only set after the event, so the mob
     * is checked one tick later (and stays watched, so dropping it later shows it again).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onTarget(event: EntityTargetEvent) {
        val entity = event.entity as? LivingEntity ?: return
        if (!isNeutral(entity)) return
        watch(entity)
        Bukkit.getScheduler().runTask(plugin, Runnable { syncForWorld(plugin, entity) })
    }

    /** A hit can anger a neutral mob (and its group, which then fire their own target events). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamaged(event: EntityDamageEvent) {
        val entity = event.entity as? LivingEntity ?: return
        if (!isNeutral(entity)) return
        watch(entity)
        Bukkit.getScheduler().runTask(plugin, Runnable { syncForWorld(plugin, entity) })
    }

    /** A wild animal tamed while someone has Mobs OFF shows up for them right away. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTame(event: EntityTameEvent) {
        val entity = event.entity
        watched.remove(entity.uniqueId)
        for (online in entity.world.players) {
            if (!online.canSee(entity)) online.showEntity(plugin, entity)
        }
    }
}
