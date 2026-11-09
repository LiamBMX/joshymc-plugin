package com.liam.joshymc.listener

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.liam.joshymc.Joshymc
import com.liam.joshymc.mobvisibility.MobVisibilityRules
import org.bukkit.Bukkit
import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Tameable
import org.bukkit.entity.Villager
import org.bukkit.entity.Warden
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityCombustByEntityEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.event.entity.EntityTameEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerTeleportEvent

/**
 * Per-player Mobs OFF setting (`mob_visibility`, issue #1475). For a player with Mobs OFF,
 * every *covered* mob ([MobVisibilityRules]: hostile, and neutral whether provoked or not) is
 *  - hidden with Paper's [Player.hideEntity] (spawns, chunk loads, join, world change,
 *    teleport, toggle; a 2s sweep near Mobs OFF players is the backstop);
 *  - unable to target them ([EntityTargetEvent] cancelled at HIGHEST, so another plugin's
 *    earlier un-cancel can't let it through; existing targets and Warden anger are cleared
 *    on toggle and by the sweep);
 *  - unable to hurt them: damage whose `damageSource.causingEntity` (or damager / shooter /
 *    TNT source) is a covered mob is cancelled (melee, arrows, fireballs, creeper blasts,
 *    fangs, guardian beams, ...), as are a covered mob's splash potions, lingering clouds,
 *    combustion and the source-less mob effect causes (Elder Guardian, Warden Darkness);
 *  - unable to be hurt or interacted with by them, so hidden mobs can't be farmed.
 *
 * Only mob-caused harm is judged: player TNT, players' potions, fall/lava/fire and PvP are
 * untouched (the #1421 regression was a blanket explosion/magic cancel). Nothing is spawned,
 * removed or changed for other players; mobs keep their normal AI toward Mobs ON players.
 * Mob Waves event mobs are exempt, so hosted events play normally.
 *
 * Paper keeps a player's hidden entities by UUID for the session, so a hidden mob stays
 * hidden when it leaves and re-enters tracking range; the setting itself is persisted by
 * SettingsManager and re-applied on join. No per-player state is kept here.
 */
class MobVisibilityListener(private val plugin: Joshymc) : Listener {

    companion object {
        private const val SETTING_KEY = "mob_visibility"
        private const val EVENT_MOB_TAG = "joshymc_event_mob"
        private const val XRAY_TAG = "joshymc_xray"

        private fun exempt(entity: LivingEntity): Boolean =
            entity !is Mob ||
                entity is Villager ||
                (entity is Tameable && entity.isTamed) ||
                entity.hasMetadata("NPC") ||
                entity.scoreboardTags.contains(XRAY_TAG) ||
                entity.scoreboardTags.contains(EVENT_MOB_TAG)

        /** Whether [entity] is hidden from / harmless to / unhittable by Mobs OFF players. */
        fun covered(entity: Entity?): Boolean {
            if (entity !is LivingEntity || entity is Player) return false
            val kind = MobVisibilityRules.kindOf(entity.type.name, entity is Enemy)
            return MobVisibilityRules.covers(kind, exempt(entity))
        }

        /** True when [player] has Mobs ON (the setting's stored value; default true). */
        private fun mobsVisible(plugin: Joshymc, player: Player): Boolean =
            plugin.settingsManager.getSetting(player, SETTING_KEY)

        fun mobsOff(plugin: Joshymc, player: Player): Boolean = !mobsVisible(plugin, player)

        /** Drop [mob]'s hold on [player]: its target and, for a Warden, its anger. */
        private fun clearAggression(mob: Entity, player: Player) {
            if (mob is Mob && mob.target?.uniqueId == player.uniqueId) mob.target = null
            if (mob is Warden) mob.clearAnger(player)
        }

        /**
         * Hide or show a covered [entity] for [player]. `showEntity(plugin, …)` only lifts
         * JoshyMC's own hide, so vanish or other plugins' hides stay in place.
         */
        private fun hideFrom(plugin: Joshymc, player: Player, entity: Entity) {
            if (player.canSee(entity)) player.hideEntity(plugin, entity)
            clearAggression(entity, player)
        }

        /**
         * Apply [player]'s setting to every loaded living entity in their world. Called by the
         * setting's onToggle and after join / world change / teleport. Mobs ON ([enabled])
         * lifts every JoshyMC hide and leaves AI alone (no target is forced).
         */
        fun applyTo(plugin: Joshymc, player: Player, enabled: Boolean) {
            for (entity in player.world.livingEntities) {
                if (entity is Player) continue
                if (enabled || !covered(entity)) {
                    if (!player.canSee(entity)) player.showEntity(plugin, entity)
                } else {
                    hideFrom(plugin, player, entity)
                }
            }
        }
    }

    private fun off(player: Player): Boolean = mobsOff(plugin, player)

    fun start() {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sweep() },
            MobVisibilityRules.SWEEP_INTERVAL_TICKS, MobVisibilityRules.SWEEP_INTERVAL_TICKS)
    }

    /**
     * Backstop near Mobs OFF players only (no chunk loads, nothing for Mobs ON players):
     * re-hides anything a missed event let through and clears a target or Warden anger a
     * brain-driven AI set without firing a target event.
     */
    private fun sweep() {
        val r = MobVisibilityRules.SWEEP_RADIUS
        for (player in Bukkit.getOnlinePlayers()) {
            if (!off(player)) continue
            for (entity in player.getNearbyEntities(r, r, r)) {
                if (covered(entity)) hideFrom(plugin, player, entity)
            }
        }
    }

    // ── Visibility ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        // Defer — the settings cache and the player's tracking setup need to settle first.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) applyTo(plugin, player, mobsVisible(plugin, player))
        }, 5L)
    }

    /** Re-apply after crossing worlds: tick 1 for what's loaded, tick 40 for chunks loaded after. */
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
    fun onAddToWorld(event: EntityAddToWorldEvent) = hideNewEntity(event.entity)

    private fun hideNewEntity(entity: Entity) {
        if (!covered(entity)) return
        for (online in entity.world.players) {
            if (off(online) && online.canSee(entity)) online.hideEntity(plugin, entity)
        }
    }

    /** A wild animal tamed while someone has Mobs OFF shows up for them right away. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTame(event: EntityTameEvent) {
        val entity = event.entity
        for (online in entity.world.players) {
            if (!online.canSee(entity)) online.showEntity(plugin, entity)
        }
    }

    // ── Targeting ─────────────────────────────────────────

    /** A covered mob never acquires a Mobs OFF player, whatever another plugin decided earlier. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onTarget(event: EntityTargetEvent) {
        val target = event.target as? Player ?: return
        if (MobVisibilityRules.allowsTarget(covered(event.entity), off(target))) return
        event.isCancelled = true
    }

    // ── Damage ─────────────────────────────────────────

    /**
     * Both directions between a covered mob and a Mobs OFF player. HIGHEST so earlier
     * protections still decide first and nothing un-cancelling at HIGH lets a hit through.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity
        val source = sourceOf(event) ?: return
        if (victim is Player) {
            if (source is Player || !covered(source)) return
            if (MobVisibilityRules.blocksMobHittingPlayer(true, off(victim))) {
                event.isCancelled = true
            }
        } else if (source is Player) {
            if (!off(source) || !covered(victim)) return
            if (MobVisibilityRules.blocksPlayerHittingMob(true, true)) event.isCancelled = true
        }
    }

    /** Fire from a covered mob (or its projectile) doesn't ignite a Mobs OFF player. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCombust(event: EntityCombustByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val source = ownerOf(event.combuster) ?: return
        if (source !is Player && covered(source) && off(victim)) event.isCancelled = true
    }

    /** A witch's (or any covered mob's) splash potion skips Mobs OFF players. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSplash(event: PotionSplashEvent) {
        val thrower = event.potion.shooter as? Entity ?: return
        if (!covered(thrower)) return
        for (target in event.affectedEntities) {
            if (target is Player && off(target)) event.setIntensity(target, 0.0)
        }
    }

    /** A covered mob's lingering cloud doesn't apply to Mobs OFF players. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCloud(event: AreaEffectCloudApplyEvent) {
        val source = event.entity.source as? Entity ?: return
        if (!covered(source)) return
        event.affectedEntities.removeIf { it is Player && off(it) }
    }

    /**
     * Effects with no source entity that only mobs give players: the Elder Guardian's Mining
     * Fatigue and mob melee effects (ATTACK), the Warden's Darkness (WARDEN). Hosted games
     * (Mob Waves) keep them.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEffect(event: EntityPotionEffectEvent) {
        val player = event.entity as? Player ?: return
        if (event.newEffect == null) return
        if (!MobVisibilityRules.blocksEffect(event.cause.name, off(player), inHostedGame(player))) return
        event.isCancelled = true
    }

    private fun inHostedGame(player: Player): Boolean =
        runCatching { plugin.eventManager.games.running?.isPlaying(player) == true }.getOrDefault(false)

    // ── Interaction ─────────────────────────────────────────

    /** Hidden mobs can't be leashed, named, bred, traded or otherwise used by a Mobs OFF player. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEntityEvent) {
        if (off(event.player) && covered(event.rightClicked)) event.isCancelled = true
    }

    // ── Attribution ─────────────────────────────────────────

    /** The entity the game credits with the damage: causing entity, else damager / shooter / TNT source. */
    private fun sourceOf(event: EntityDamageEvent): Entity? {
        event.damageSource.causingEntity?.let { return it }
        if (event !is EntityDamageByEntityEvent) return null
        return ownerOf(event.damager)
    }

    private fun ownerOf(entity: Entity?): Entity? = when (entity) {
        is Projectile -> entity.shooter as? Entity ?: entity
        is TNTPrimed -> entity.source ?: entity
        else -> entity
    }
}
