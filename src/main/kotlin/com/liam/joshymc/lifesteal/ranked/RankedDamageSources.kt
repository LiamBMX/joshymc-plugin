package com.liam.joshymc.lifesteal.ranked

import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.Entity
import org.bukkit.entity.EvokerFangs
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Tameable
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent

/**
 * Issue #1524: the player behind damage to or near a Ranked Team Wars match, shared by
 * [LifestealRankedListener] and [RankedArenaGuardListener]. Follows the chain a player can hide
 * behind (projectile → shooter, TNT → igniter, cloud → thrower, fangs → evoker, tamed mob →
 * owner, a tamed llama's spit → the llama → its owner), a few steps deep.
 *
 * [Resolved.absentOwner] marks a tamed mob whose owner isn't online: no player can be checked, so
 * the match treats it as an outsider ([RankedArenaAccess.Source.ABSENT_OWNER]), never as
 * environmental damage.
 */
object RankedDamageSources {

    data class Resolved(val player: Player?, val absentOwner: Boolean) {
        companion object {
            val NONE = Resolved(null, false)
        }
    }

    private const val MAX_DEPTH = 4

    /** The player behind [entity] (itself, its shooter, igniter, owner ...). */
    fun of(entity: Any?, depth: Int = 0): Resolved {
        if (entity == null || depth > MAX_DEPTH) return Resolved.NONE
        return when (entity) {
            is Player -> Resolved(entity, false)
            is Projectile -> of(entity.shooter, depth + 1)
            is TNTPrimed -> of(entity.source, depth + 1)
            is AreaEffectCloud -> of(entity.source, depth + 1)
            is EvokerFangs -> of(entity.owner, depth + 1)
            is Tameable -> {
                val owner = entity.owner ?: return Resolved.NONE
                val online = owner as? Player
                if (online != null && online.isOnline) Resolved(online, false) else Resolved(null, true)
            }
            else -> Resolved.NONE
        }
    }

    /** The player behind [event]: the damage source's causing entity first, then the damager. */
    fun of(event: EntityDamageEvent): Resolved {
        val causing: Entity? = event.damageSource.causingEntity
        val fromCause = of(causing)
        if (fromCause.player != null || fromCause.absentOwner) return fromCause
        if (event !is EntityDamageByEntityEvent) return Resolved.NONE
        return of(event.damager)
    }

    /** The [RankedArenaAccess.Source] of [resolved] for [victim] ([sameFight] = both in the same ACTIVE match). */
    fun sourceFor(resolved: Resolved, victim: Entity, sameFight: (Player) -> Boolean): RankedArenaAccess.Source {
        val player = resolved.player
        return when {
            player != null && player.uniqueId == victim.uniqueId -> RankedArenaAccess.Source.SELF
            player != null && victim is Player && sameFight(player) -> RankedArenaAccess.Source.SAME_FIGHT
            player != null -> RankedArenaAccess.Source.OTHER_PLAYER
            resolved.absentOwner -> RankedArenaAccess.Source.ABSENT_OWNER
            else -> RankedArenaAccess.Source.NONE
        }
    }
}
