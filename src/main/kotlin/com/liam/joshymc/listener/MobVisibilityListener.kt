package com.liam.joshymc.listener

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent
import com.liam.joshymc.Joshymc
import com.liam.joshymc.mobvisibility.MobVisibilityRules
import com.liam.joshymc.mobvisibility.MobVisibilityRules.Party
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent
import org.bukkit.Bukkit
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.ComplexEntityPart
import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
import org.bukkit.entity.EvokerFangs
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
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent

/**
 * Per-player Mobs OFF setting (`mob_visibility`, issues #1475, #1481). For a player with Mobs
 * OFF, every *covered* mob ([MobVisibilityRules]: hostile, bosses, and neutral whether provoked
 * or not) is
 *  - hidden with Paper's [Player.hideEntity] (spawns, chunk loads, join, respawn, world change,
 *    teleport, toggle; a 2s sweep near Mobs OFF players is the backstop);
 *  - unable to target them ([EntityTargetEvent] cancelled at HIGHEST and re-asserted at
 *    MONITOR; existing targets and Warden anger are cleared on toggle, on every blocked hit and
 *    by the sweep);
 *  - unable to hurt them. Every path is attributed to the responsible entity (damage source's
 *    causing entity, else damager → projectile shooter / TNT source / Evoker Fangs owner /
 *    cloud source / dragon part's dragon) and judged by the one rule
 *    [MobVisibilityRules.blocksHarm]: damage (cancelled at HIGHEST, re-asserted at MONITOR so a
 *    later listener's un-cancel can't let it through), projectile impacts (the projectile
 *    passes through instead of bouncing or exploding on them), attack/explosion knockback
 *    (Breeze wind charges, Ravager roars), ignition, splash potions, lingering clouds and the
 *    source-less mob effect causes (Elder Guardian, Warden Darkness);
 *  - unable to be hurt or interacted with by them, so hidden mobs can't be farmed.
 *
 * Only harm attributed to a covered mob is judged: player TNT, players' potions,
 * fall/lava/fire and PvP are untouched. Nothing is spawned, removed or changed for other
 * players; mobs keep their normal AI toward Mobs ON players. Mob Waves event mobs are exempt.
 *
 * Damage protection never depends on visibility: it reads the stored setting on every hit.
 * Paper keeps a player's hidden entities by UUID for the session; the setting itself is
 * persisted by SettingsManager (playerdata.db, by UUID) and re-applied on join.
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
            val living = (if (entity is ComplexEntityPart) entity.parent else entity) as? LivingEntity
                ?: return false
            if (living is Player) return false
            val kind = MobVisibilityRules.kindOf(living.type.name, living is Enemy)
            return MobVisibilityRules.covers(kind, exempt(living))
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
         * Hide a covered [entity] from [player]. Always registers JoshyMC's own hide (Paper keeps
         * one per plugin, so repeating it is a no-op): skipping it when another plugin already
         * hid the entity would let it reappear the moment that plugin showed it again.
         */
        private fun hideFrom(plugin: Joshymc, player: Player, entity: Entity) {
            player.hideEntity(plugin, entity)
            clearAggression(entity, player)
        }

        /**
         * Apply [player]'s setting to every loaded living entity in their world. Called by the
         * setting's onToggle and after join / respawn / world change / teleport. Mobs ON
         * ([enabled]) lifts every JoshyMC hide and leaves AI alone (no target is forced).
         * `showEntity(plugin, …)` only lifts JoshyMC's own hide, so vanish and other plugins'
         * hides stay in place.
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

    /** Set once a later listener un-cancelled a blocked hit, so the log isn't flooded. */
    private var warnedLateUncancel = false

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

    /**
     * Right away (so nothing is ever sent to the client), then again once the settings cache
     * and the player's tracking have settled (5 ticks) and for chunks that loaded later (40).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        applyTo(plugin, player, mobsVisible(plugin, player))
        for (delay in longArrayOf(5L, 40L)) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (player.isOnline) applyTo(plugin, player, mobsVisible(plugin, player))
            }, delay)
        }
    }

    /** Re-apply after crossing worlds: tick 1 for what's loaded, tick 40 for chunks loaded after. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = reapplySoon(event.player)

    /** Long same-world teleports move the player into chunks that weren't tracked yet. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) = reapplySoon(event.player)

    /** A same-world respawn re-tracks everything around the respawn point. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) = reapplySoon(event.player)

    private fun reapplySoon(player: Player) {
        for (delay in longArrayOf(1L, 40L)) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (player.isOnline) applyTo(plugin, player, mobsVisible(plugin, player))
            }, delay)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSpawn(event: CreatureSpawnEvent) = hideNewEntity(event.entity)

    /** Every entity entering the world: chunk loads, transformations (zombie → drowned, …). */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onAddToWorld(event: EntityAddToWorldEvent) = hideNewEntity(event.entity)

    private fun hideNewEntity(entity: Entity) {
        if (entity !is LivingEntity || !covered(entity)) return
        for (online in entity.world.players) {
            if (off(online)) online.hideEntity(plugin, entity)
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

    private fun blocksTarget(event: EntityTargetEvent): Boolean {
        val target = event.target as? Player ?: return false
        return !MobVisibilityRules.allowsTarget(covered(event.entity), off(target))
    }

    /** A covered mob never acquires a Mobs OFF player, whatever another plugin decided earlier. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onTarget(event: EntityTargetEvent) {
        if (blocksTarget(event)) event.isCancelled = true
    }

    /** Last word: a later HIGHEST listener that un-cancelled the target is overruled. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onTargetFinal(event: EntityTargetEvent) {
        if (!event.isCancelled && blocksTarget(event)) event.isCancelled = true
    }

    // ── Damage ─────────────────────────────────────────

    /**
     * Both directions between a covered mob and a Mobs OFF player. HIGHEST so earlier
     * protections still decide first; a blocked mob also drops its target on the player.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDamage(event: EntityDamageEvent) {
        val source = sourceOf(event) ?: return
        if (!MobVisibilityRules.blocksHarm(party(source), party(event.entity))) return
        event.isCancelled = true
        (event.entity as? Player)?.let { clearAggression(source, it) }
    }

    /**
     * Last word on damage (issue #1481): if any listener running after [onDamage] (another
     * plugin's HIGHEST handler, or a MONITOR one) un-cancelled a blocked hit, cancel it again.
     * Without this, the protection depended on listener order between plugins.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onDamageFinal(event: EntityDamageEvent) {
        if (event.isCancelled) return
        val source = sourceOf(event) ?: return
        if (!MobVisibilityRules.blocksHarm(party(source), party(event.entity))) return
        event.isCancelled = true
        if (!warnedLateUncancel) {
            warnedLateUncancel = true
            plugin.logger.warning("[MobVisibility] Another listener un-cancelled a hit between a " +
                "covered mob and a Mobs OFF player (${source.type} -> ${event.entity.type}); " +
                "cancelled it again at MONITOR. Logged once per restart.")
        }
    }

    /**
     * A covered mob's arrow / trident / fireball / shulker bullet / wind charge / spit passes
     * through a Mobs OFF player instead of hitting (so it can't explode, apply effects or knock
     * them back on impact), and a Mobs OFF player's projectiles pass through hidden mobs.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val hit = event.hitEntity ?: return
        val shooter = ownerOf(event.entity) ?: return
        if (MobVisibilityRules.blocksHarm(party(shooter), party(hit))) event.isCancelled = true
    }

    /**
     * Knockback with no damage behind it: Breeze wind charges, Ravager roars, mob explosions.
     * Paper's [EntityPushedByEntityAttackEvent] names the pusher, which is attributed like damage.
     * Its melee subclass [EntityKnockbackByEntityEvent] has its own handler list, so both are
     * listened to (cancelling twice is harmless).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onKnockback(event: EntityPushedByEntityAttackEvent) = judgeKnockback(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMeleeKnockback(event: EntityKnockbackByEntityEvent) = judgeKnockback(event)

    private fun judgeKnockback(event: EntityPushedByEntityAttackEvent) {
        val pusher = ownerOf(event.pushedBy) ?: return
        if (MobVisibilityRules.blocksHarm(party(pusher), party(event.entity))) event.isCancelled = true
    }

    /** Fire from a covered mob (or its projectile) doesn't ignite a Mobs OFF player. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCombust(event: EntityCombustByEntityEvent) {
        val source = ownerOf(event.combuster) ?: return
        if (MobVisibilityRules.blocksHarm(party(source), party(event.entity))) event.isCancelled = true
    }

    /** A witch's (or any covered mob's) splash potion skips Mobs OFF players. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSplash(event: PotionSplashEvent) {
        val thrower = event.potion.shooter as? Entity ?: return
        val source = party(thrower)
        for (target in event.affectedEntities) {
            if (MobVisibilityRules.blocksHarm(source, party(target))) event.setIntensity(target, 0.0)
        }
    }

    /** A covered mob's lingering cloud (incl. dragon breath) doesn't apply to Mobs OFF players. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCloud(event: AreaEffectCloudApplyEvent) {
        val owner = event.entity.source as? Entity ?: return
        val source = party(owner)
        event.affectedEntities.removeIf { MobVisibilityRules.blocksHarm(source, party(it)) }
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

    /** Which side of [MobVisibilityRules.blocksHarm] [entity] is on. */
    private fun party(entity: Entity?): Party = when {
        entity == null -> Party.NONE
        entity is Player -> if (off(entity)) Party.PLAYER_MOBS_OFF else Party.PLAYER_MOBS_ON
        covered(entity) -> Party.COVERED_MOB
        else -> Party.OTHER
    }

    /** The entity the game credits with the damage: causing entity, else the damager's owner. */
    private fun sourceOf(event: EntityDamageEvent): Entity? {
        event.damageSource.causingEntity?.let { return ownerOf(it) }
        if (event !is EntityDamageByEntityEvent) return null
        return ownerOf(event.damager)
    }

    /**
     * Resolve an intermediate entity to whoever is responsible for it: a projectile's shooter,
     * a primed TNT's igniter, Evoker Fangs' evoker, a lingering cloud's thrower, a dragon
     * part's dragon. Falls back to the entity itself when there is no owner.
     */
    private fun ownerOf(entity: Entity?): Entity? = when (entity) {
        is Projectile -> entity.shooter as? Entity ?: entity
        is TNTPrimed -> entity.source ?: entity
        is EvokerFangs -> entity.owner ?: entity
        is AreaEffectCloud -> entity.source as? Entity ?: entity
        is ComplexEntityPart -> entity.parent
        else -> entity
    }
}
