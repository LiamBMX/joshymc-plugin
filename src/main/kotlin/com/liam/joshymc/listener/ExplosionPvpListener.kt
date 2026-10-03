package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID

/**
 * Closes the PvP-toggle bypass through player-caused explosions (issue #1003).
 *
 * - End Crystals: attributed to whoever hit them (melee or projectile), then
 *   gated on both players' PvP state when the crystal damages a player.
 * - Respawn Anchors / beds (Nether/End): BlockExplodeEvent carries no player,
 *   so the right-click that triggered it is recorded for a few ticks and
 *   matched against the explosion. The resulting BLOCK_EXPLOSION damage is
 *   gated per victim. These blocks only ever explode from player action, so
 *   when attribution fails we still protect PvP-off victims.
 *
 * TNT with a player source is already gated by CombatListener.onDamage.
 * Cancelling the damage event also cancels the knockback. Natural explosions
 * (creepers, ghasts, ...) are never touched. Arena fights between two players
 * in the same arena are left alone.
 */
class ExplosionPvpListener(private val plugin: Joshymc) : Listener {

    private companion object {
        const val CRYSTAL_ATTRIBUTION_TICKS = 200
        const val TRIGGER_TICKS = 5
        const val TRIGGER_RADIUS_SQ = 2.5 * 2.5
        const val BLAST_RADIUS_SQ = 16.0 * 16.0
    }

    private class Attribution(val player: UUID, val tick: Int)
    private class Trigger(val loc: Location, val player: UUID, val tick: Int)
    private class Blast(val loc: Location, val player: UUID?, val tick: Int)

    private val crystalHitters = HashMap<UUID, Attribution>()
    private val triggers = ArrayList<Trigger>()
    private val blasts = ArrayList<Blast>()

    private fun isExplodingBlock(type: Material) =
        type == Material.RESPAWN_ANCHOR || Tag.BEDS.isTagged(type)

    // ── Attribution ──────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCrystalHit(event: EntityDamageByEntityEvent) {
        val crystal = event.entity as? EnderCrystal ?: return
        val damager = event.damager
        val player = when {
            damager is Player -> damager
            damager is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return
        val now = Bukkit.getCurrentTick()
        if (crystalHitters.size > 256) crystalHitters.entries.removeIf { now - it.value.tick > CRYSTAL_ATTRIBUTION_TICKS }
        crystalHitters[crystal.uniqueId] = Attribution(player.uniqueId, now)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onTrigger(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (!isExplodingBlock(block.type)) return
        if (event.useInteractedBlock() == Event.Result.DENY) return
        val now = Bukkit.getCurrentTick()
        triggers.removeIf { now - it.tick > TRIGGER_TICKS }
        triggers.add(Trigger(block.location.add(0.5, 0.5, 0.5), event.player.uniqueId, now))
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val state = event.explodedBlockState
        if (!isExplodingBlock(state.type)) return
        val now = Bukkit.getCurrentTick()
        val origin = state.location.add(0.5, 0.5, 0.5)
        blasts.removeIf { now - it.tick > 1 }
        triggers.removeIf { now - it.tick > TRIGGER_TICKS }
        val trigger = triggers
            .filter { it.loc.world == origin.world && it.loc.distanceSquared(origin) <= TRIGGER_RADIUS_SQ }
            .minByOrNull { it.loc.distanceSquared(origin) }
        blasts.add(Blast(origin, trigger?.player, now))
    }

    // ── Enforcement ──────────────────────────────────────

    /** End Crystal damage to a player. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onCrystalDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val crystal = event.damager as? EnderCrystal ?: return
        val hitter = crystalHitters[crystal.uniqueId]
            ?.takeIf { Bukkit.getCurrentTick() - it.tick <= CRYSTAL_ATTRIBUTION_TICKS }
            ?.let { Bukkit.getPlayer(it.player) }
        if (blocked(victim, hitter)) event.isCancelled = true
    }

    /** Respawn Anchor / bed explosion damage to a player. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBlockExplosionDamage(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return
        val victim = event.entity as? Player ?: return
        val now = Bukkit.getCurrentTick()
        val blast = blasts
            .filter { it.tick == now && it.loc.world == victim.world && it.loc.distanceSquared(victim.location) <= BLAST_RADIUS_SQ }
            .minByOrNull { it.loc.distanceSquared(victim.location) }
            ?: return
        val attacker = blast.player?.let { Bukkit.getPlayer(it) }
        if (blocked(victim, attacker)) {
            event.isCancelled = true
            attacker?.takeIf { it != victim }?.let {
                plugin.commsManager.sendActionBar(it, Component.text(
                    if (!plugin.combatManager.canPvP(it)) "Your PvP is disabled. /pvp on" else "That player has PvP disabled.",
                    NamedTextColor.RED))
            }
        }
    }

    /**
     * True when the explosion must not hurt [victim]. [attacker] is null when
     * attribution failed, in which case only the victim's state can be judged.
     */
    private fun blocked(victim: Player, attacker: Player?): Boolean {
        if (attacker == victim) return false
        if (attacker != null && plugin.arenaManager.isInArena(attacker) && plugin.arenaManager.isInArena(victim)) return false
        val combat = plugin.combatManager
        return !combat.canPvP(victim) || (attacker != null && !combat.canPvP(attacker))
    }
}
