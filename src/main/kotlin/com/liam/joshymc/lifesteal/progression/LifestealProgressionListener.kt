package com.liam.joshymc.lifesteal.progression

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.progression.ProgressionRules.Milestone
import com.liam.joshymc.lifesteal.progression.ProgressionRules.Phase
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.event.entity.EntityCombustByEntityEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectTypeCategory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Enforcement of the launch progression (issue #1352) on actual events. ListenerManager registers
 * it only where this server hosts Lifesteal, and every handler also requires a Lifesteal world, so
 * Survival is never touched. All checks read [LifestealProgressionManager]'s in-memory state.
 *
 * **Grace period** (also before launch and while the state is unknown): damage the game credits to
 * another player is cancelled: `damageSource.causingEntity` (melee, arrows, crossbow bolts,
 * tridents and their Channeling lightning, fireworks, End Crystals someone broke, primed TNT they
 * lit, harming potions), with the damager/shooter/TNT igniter and a tamed pet's owner as
 * fallbacks. Harmful splash potions and lingering clouds skip other players and flaming hits don't
 * set them on fire. Damage with no player behind it (fall, lava, fire ticks, drowning, mobs, beds,
 * anchors) is never touched, so nobody becomes invulnerable. HIGHEST, ignoreCancelled, like the
 * team friendly-fire listener: CombatListener tags at MONITOR with ignoreCancelled, so a blocked
 * hit never starts a combat tag.
 *
 * **The End** (until it unlocks): every way in is a real world change, so the checks sit on the
 * transitions, not on commands: End portals ([PlayerPortalEvent]), every teleport into an End world
 * ([PlayerTeleportEvent]: /tp, /execute, warps, other plugins' portals), vehicles with a player
 * aboard ([EntityPortalEvent]), and as the backstop any arrival ([PlayerChangedWorldEvent]) or join
 * inside a locked End, which moves the player out. Leaving the End is never blocked. Only an
 * explicit [LifestealProgressionManager.END_BYPASS_PERMISSION] grant gets in early.
 */
class LifestealProgressionListener(private val plugin: Joshymc) : Listener {

    private val progression get() = plugin.lifestealProgressionManager

    /** Last hint per player, so a combo or a portal stand doesn't flood them. */
    private val lastHint = ConcurrentHashMap<UUID, Long>()
    /** Players with a pending move out of an End portal / the End (one per player). */
    private val pendingMoves = ConcurrentHashMap.newKeySet<UUID>()

    private fun inLifesteal(entity: Entity): Boolean = progression.isLifesteal(entity.world)

    private fun isLockedEnd(world: World?): Boolean =
        world != null && world.environment == World.Environment.THE_END && progression.isLifesteal(world)

    // ── Grace period: player-versus-player damage ───────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = attackerOf(event) ?: return
        if (attacker.uniqueId == victim.uniqueId || attacker.hasMetadata("NPC") || victim.hasMetadata("NPC")) return
        if (!inLifesteal(victim) || !progression.pvpProtected()) return
        event.isCancelled = true
        if (attacker.isOnline) hint(attacker)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSplash(event: PotionSplashEvent) {
        val thrower = event.potion.shooter as? Player ?: return
        if (!anyHarmful(event.potion.effects) || !progression.pvpProtected()) return
        for (target in event.affectedEntities) {
            if (target is Player && target.uniqueId != thrower.uniqueId && inLifesteal(target)) event.setIntensity(target, 0.0)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCloud(event: AreaEffectCloudApplyEvent) {
        val cloud = event.entity
        val thrower = cloud.source as? Player ?: return
        val effects = cloud.basePotionType?.potionEffects.orEmpty() + cloud.customEffects
        if (!anyHarmful(effects) || !progression.pvpProtected()) return
        event.affectedEntities.removeIf { it is Player && it.uniqueId != thrower.uniqueId && inLifesteal(it) }
    }

    /** A flaming arrow/sword from another player doesn't ignite the victim (the later fire ticks have no attacker). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCombust(event: EntityCombustByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = playerOf(event.combuster) ?: return
        if (attacker.uniqueId == victim.uniqueId || !inLifesteal(victim) || !progression.pvpProtected()) return
        event.isCancelled = true
    }

    /** Tamed pets don't go after other players (their bites would be blocked anyway). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPetTarget(event: EntityTargetLivingEntityEvent) {
        val pet = event.entity as? Tameable ?: return
        val owner = pet.owner as? Player ?: return
        val target = event.target as? Player ?: return
        if (owner.uniqueId == target.uniqueId || !inLifesteal(target) || !progression.pvpProtected()) return
        event.isCancelled = true
    }

    private fun attackerOf(event: EntityDamageEvent): Player? {
        (event.damageSource.causingEntity as? Player)?.let { return it }
        if (event !is EntityDamageByEntityEvent) return null
        return playerOf(event.damager)
    }

    private fun playerOf(source: Any?): Player? = when (source) {
        is Player -> source
        is Projectile -> source.shooter as? Player
        is TNTPrimed -> source.source as? Player
        is Tameable -> source.owner as? Player
        else -> null
    }

    private fun anyHarmful(effects: Collection<PotionEffect>): Boolean =
        effects.any { it.type.category == PotionEffectTypeCategory.HARMFUL }

    private fun hint(player: Player) {
        if (!recentHint(player.uniqueId)) return
        val p = progression.progress()
        val text = when (p.phase) {
            Phase.GRACE_PERIOD -> "Grace period: PvP is off for another ${ProgressionRules.formatRemaining(p.remaining(Milestone.GRACE_ENDED) ?: 0)}."
            Phase.PRE_LAUNCH, Phase.NOT_SCHEDULED -> "PvP is off until Lifesteal launches and the grace period ends."
            else -> "PvP is unavailable right now."
        }
        plugin.commsManager.sendActionBar(player, Component.text(text, NamedTextColor.RED))
    }

    /** True at most once per [HINT_INTERVAL_MS] per player. */
    private fun recentHint(uuid: UUID): Boolean {
        val now = System.currentTimeMillis()
        val last = lastHint[uuid]
        if (last != null && now - last < HINT_INTERVAL_MS) return false
        lastHint[uuid] = now
        if (lastHint.size > HINT_SWEEP_SIZE) lastHint.entries.removeIf { now - it.value >= HINT_INTERVAL_MS }
        return true
    }

    // ── The End ─────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) {
        val player = event.player
        if (!entersLockedEnd(player, event.from.world, event.to.world)) return
        event.isCancelled = true
        endHint(player)
        // A refused End portal leaves the player inside it, usually over the stronghold's lava:
        // move them onto the portal frame (or to spawn) next tick.
        if (event.cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) moveOutOfPortal(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val player = event.player
        if (!entersLockedEnd(player, event.from.world, event.to.world)) return
        event.isCancelled = true
        endHint(player)
    }

    /** A boat, minecart or mount carrying a player can't take them through an End portal early. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityPortal(event: EntityPortalEvent) {
        val to = event.to?.world ?: return
        if (!isLockedEnd(to) || isLockedEnd(event.from.world) || !progression.endLocked()) return
        val riders = playersAboard(event.entity)
        if (riders.any { !it.hasPermission(LifestealProgressionManager.END_BYPASS_PERMISSION) }) event.isCancelled = true
    }

    /** Backstop: whatever got a player into a locked End (a path no event above saw), they're moved out. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onChangedWorld(event: PlayerChangedWorldEvent) {
        val player = event.player
        if (!isLockedEnd(player.world) || !progression.endLockedFor(player)) return
        plugin.logger.warning("[Progression] ${player.name} arrived in ${player.world.name} while the End is locked; moving them out.")
        evacuate(player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (isLockedEnd(player.world) && progression.endLockedFor(player)) evacuate(player)
        progression.onJoin(player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastHint.remove(event.player.uniqueId)
        pendingMoves.remove(event.player.uniqueId)
    }

    private fun entersLockedEnd(player: Player, from: World?, to: World?): Boolean {
        if (!isLockedEnd(to) || from == to) return false
        return progression.endLockedFor(player)
    }

    private fun playersAboard(entity: Entity): List<Player> {
        val found = mutableListOf<Player>()
        val queue = ArrayDeque(entity.passengers)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 32) {
            val next = queue.removeFirst()
            if (next is Player) found += next
            queue.addAll(next.passengers)
        }
        return found
    }

    private fun endHint(player: Player) {
        if (!recentHint(player.uniqueId)) return
        val p = progression.progress()
        val remaining = p.remaining(Milestone.END_UNLOCKED)
        val text = if (remaining != null && p.phase != Phase.UNKNOWN) "The End unlocks in ${ProgressionRules.formatRemaining(remaining)}."
        else "The End is closed until it unlocks."
        plugin.commsManager.sendActionBar(player, Component.text(text, NamedTextColor.LIGHT_PURPLE))
    }

    private fun moveOutOfPortal(player: Player) {
        if (!pendingMoves.add(player.uniqueId)) return
        val origin = player.location.clone()
        plugin.server.scheduler.runTask(plugin, Runnable {
            pendingMoves.remove(player.uniqueId)
            if (!player.isOnline) return@Runnable
            val target = frameTop(origin) ?: safeSpawn(player.world)
            player.fallDistance = 0f
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
        })
    }

    /** Out of a locked End: their respawn point if it isn't in an End world, else the Lifesteal overworld spawn. */
    private fun evacuate(player: Player) {
        if (!pendingMoves.add(player.uniqueId)) return
        plugin.server.scheduler.runTask(plugin, Runnable {
            pendingMoves.remove(player.uniqueId)
            if (!player.isOnline || !isLockedEnd(player.world) || !progression.endLockedFor(player)) return@Runnable
            val respawn = player.respawnLocation?.takeIf { it.world != null && it.world.environment != World.Environment.THE_END }
            val target = respawn ?: overworldSpawn() ?: return@Runnable
            player.fallDistance = 0f
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept { moved ->
                if (moved) plugin.commsManager.send(player, Component.text("The End is still closed, so you were moved out.", NamedTextColor.LIGHT_PURPLE))
            }
        })
    }

    private fun overworldSpawn(): Location? =
        plugin.server.worlds.firstOrNull { it.environment == World.Environment.NORMAL && progression.isLifesteal(it) }?.spawnLocation
            ?: plugin.server.worlds.firstOrNull { it.environment == World.Environment.NORMAL }?.spawnLocation

    private fun safeSpawn(world: World): Location = world.spawnLocation

    /** Standing spot on the nearest End portal frame around [origin] (two free blocks above it), or null. */
    private fun frameTop(origin: Location): Location? {
        val world = origin.world ?: return null
        var best: Location? = null
        var bestDistance = Double.MAX_VALUE
        for (dx in -FRAME_RADIUS..FRAME_RADIUS) for (dz in -FRAME_RADIUS..FRAME_RADIUS) for (dy in -2..1) {
            val block = world.getBlockAt(origin.blockX + dx, origin.blockY + dy, origin.blockZ + dz)
            if (block.type != Material.END_PORTAL_FRAME) continue
            if (!block.getRelative(0, 1, 0).isPassable || !block.getRelative(0, 2, 0).isPassable) continue
            if (block.getRelative(0, 1, 0).type == Material.END_PORTAL) continue
            val spot = block.location.add(0.5, 1.0, 0.5)
            val distance = spot.distanceSquared(origin)
            if (distance < bestDistance) {
                bestDistance = distance
                best = spot
            }
        }
        return best?.apply { yaw = origin.yaw; pitch = origin.pitch }
    }

    companion object {
        private const val HINT_INTERVAL_MS = 2_000L
        private const val HINT_SWEEP_SIZE = 256
        private const val FRAME_RADIUS = 3
    }
}
