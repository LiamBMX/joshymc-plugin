package com.liam.joshymc.lifesteal.teams

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.teams.TeamCombatRules.DamageDecision
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.event.entity.EntityCombustByEntityEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectTypeCategory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lifesteal friendly-fire protection (issue #1348). ListenerManager registers it only where the
 * server hosts Lifesteal, and every handler also requires the victim to be in a Lifesteal world,
 * so Survival damage, teams and health are never touched.
 *
 * Only damage the game credits to a player is judged: `damageSource.causingEntity` (the same
 * attribution the heart system uses: melee attacker, projectile shooter for arrows, tridents,
 * snowballs and fireworks, the igniter of primed TNT, the player who broke an End Crystal, the
 * thrower of a harming potion), with the Bukkit damager/shooter/TNT source as a fallback. Damage
 * with no player behind it (fall, lava, fire ticks, mobs, beds, anchors) is never touched, so
 * teammates get no immunity to anything else. Everything reads memory only (the team cache), no
 * database call per hit.
 *
 * HIGHEST, ignoreCancelled: earlier protections (claims, PvP toggles, spawn) still decide first, and
 * nothing that un-cancels at HIGH (arenas) can let a teammate's hit through. CombatListener tags at
 * MONITOR with ignoreCancelled, so a cancelled friendly hit creates no combat tag.
 */
class LifestealTeamCombatListener(private val plugin: Joshymc) : Listener {

    private val teams get() = plugin.lifestealTeamManager

    /** Last "teammate" hint per attacker, so a combo doesn't flood the action bar. Old entries are swept. */
    private val lastHint = ConcurrentHashMap<UUID, Long>()

    private fun inLifesteal(entity: Entity): Boolean = plugin.gamemodeLockManager.gamemodeOf(entity.world) == ServerGamemode.LIFESTEAL

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = attackerOf(event) ?: return
        if (attacker.uniqueId == victim.uniqueId || attacker.hasMetadata("NPC") || victim.hasMetadata("NPC")) return
        if (!inLifesteal(victim)) return
        val policy = teams.combatPolicy() ?: return
        if (policy.friendlyFire) return
        val relation = teams.combatRelation(attacker.uniqueId, victim.uniqueId)
        if (TeamCombatRules.damage(attacker.uniqueId, victim.uniqueId, relation, policy) == DamageDecision.BLOCK_FRIENDLY_FIRE) {
            event.isCancelled = true
            hint(attacker)
        }
    }

    /** Harmful splash potions thrown by a teammate don't reach that teammate (others are unaffected). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSplash(event: PotionSplashEvent) {
        val thrower = event.potion.shooter as? Player ?: return
        if (!anyHarmful(event.potion.effects)) return
        val policy = teams.combatPolicy() ?: return
        for (target in event.affectedEntities) {
            if (target !is Player || target.uniqueId == thrower.uniqueId || !inLifesteal(target)) continue
            if (TeamCombatRules.blocksPotion(true, teams.combatRelation(thrower.uniqueId, target.uniqueId), policy)) {
                event.setIntensity(target, 0.0)
            }
        }
    }

    /** Harmful lingering clouds don't apply to the thrower's teammates (the cloud remembers its thrower). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCloud(event: AreaEffectCloudApplyEvent) {
        val cloud = event.entity
        val thrower = cloud.source as? Player ?: return
        val effects = cloud.basePotionType?.potionEffects.orEmpty() + cloud.customEffects
        if (!anyHarmful(effects)) return
        val policy = teams.combatPolicy() ?: return
        event.affectedEntities.removeIf { target ->
            target is Player && target.uniqueId != thrower.uniqueId && inLifesteal(target) &&
                TeamCombatRules.blocksPotion(true, teams.combatRelation(thrower.uniqueId, target.uniqueId), policy)
        }
    }

    /**
     * A teammate's flaming arrow (or other combustion credited to them) doesn't set the victim on
     * fire: the later fire ticks have no attacker, so they couldn't be judged afterwards.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCombust(event: EntityCombustByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = playerOf(event.combuster) ?: return
        if (attacker.uniqueId == victim.uniqueId || !inLifesteal(victim)) return
        val policy = teams.combatPolicy() ?: return
        val relation = teams.combatRelation(attacker.uniqueId, victim.uniqueId)
        if (TeamCombatRules.damage(attacker.uniqueId, victim.uniqueId, relation, policy) == DamageDecision.BLOCK_FRIENDLY_FIRE) {
            event.isCancelled = true
        }
    }

    // ── Attribution ─────────────────────────────────────────

    private fun attackerOf(event: EntityDamageEvent): Player? {
        (event.damageSource.causingEntity as? Player)?.let { return it }
        if (event !is EntityDamageByEntityEvent) return null
        return playerOf(event.damager)
    }

    /** The player behind an entity or projectile source: the player, a projectile's shooter, a primed TNT's igniter. */
    private fun playerOf(source: Any?): Player? = when (source) {
        is Player -> source
        is Projectile -> source.shooter as? Player
        is TNTPrimed -> source.source as? Player
        else -> null
    }

    private fun anyHarmful(effects: Collection<PotionEffect>): Boolean =
        effects.any { it.type.category == PotionEffectTypeCategory.HARMFUL }

    private fun hint(attacker: Player) {
        val now = System.currentTimeMillis()
        val last = lastHint[attacker.uniqueId]
        if (last != null && now - last < HINT_INTERVAL_MS) return
        lastHint[attacker.uniqueId] = now
        if (lastHint.size > HINT_SWEEP_SIZE) lastHint.entries.removeIf { now - it.value >= HINT_INTERVAL_MS }
        plugin.commsManager.sendActionBar(attacker, Component.text("Friendly fire is off: that's your teammate.", NamedTextColor.RED))
    }

    companion object {
        private const val HINT_INTERVAL_MS = 2_000L
        private const val HINT_SWEEP_SIZE = 256
    }
}
