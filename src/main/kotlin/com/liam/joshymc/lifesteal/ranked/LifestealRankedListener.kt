package com.liam.joshymc.lifesteal.ranked

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Tameable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.event.entity.EntityCombustByEntityEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerPickupArrowEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Enforcement of Ranked Team Wars matches (issue #1361) on actual events. ListenerManager registers
 * it only where this server hosts Lifesteal; every rule keys off [LifestealRankedManager]'s
 * in-memory match state (no database access), so ordinary Lifesteal gameplay is untouched and
 * Survival never sees it.
 *
 * - **Held participants** (a match holds them from the snapshot until they are out or it ends):
 *   nothing leaves or enters their inventory for good (no dropping, picking up, containers, ender
 *   chest, entity/armor-stand/item-frame use, block placing/breaking, buckets, signs), commands are
 *   limited to private messages, teleports may only stay inside their arena, and walking out of the
 *   boundary is pushed back. No damage before the fight starts; in the fight only players of the
 *   same match can hurt them (environmental damage still applies).
 * - **Everyone else** can't walk or teleport into an arena a match is using, can't hurt its
 *   participants and isn't hurt by them.
 * - **Spectators** (staff) take and deal no damage to the match and can't pick items up.
 * - **Deaths** of participants drop nothing (their gear comes back from the snapshot) and no XP;
 *   the heart service still runs at MONITOR as for any Lifesteal kill. XP and potion effects come
 *   back from the snapshot too (issue #1490), so nothing gained in the match is kept.
 * - **Ominous Bottles** can't be drunk while held (issue #1490).
 */
class LifestealRankedListener(private val plugin: Joshymc) : Listener {

    private val ranked get() = plugin.lifestealRankedManager
    private val lastHint = ConcurrentHashMap<UUID, Long>()
    /** Issue #1377: the match an arrow/trident was fired in (see [RankedMatchRules.projectilePickup]). */
    private val firedInKey = NamespacedKey(plugin, "ranked_match_projectile")

    private fun hint(player: Player, text: String) {
        val now = System.currentTimeMillis()
        val last = lastHint[player.uniqueId]
        if (last != null && now - last < 1500) return
        lastHint[player.uniqueId] = now
        plugin.commsManager.send(player, Component.text("[Ranked] ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.RED)))
    }

    private fun inside(arena: RankedArena, loc: Location): Boolean =
        loc.world?.name == arena.world && arena.bounds?.contains(loc.x, loc.y, loc.z) == true

    /** Restricted while held by a match, or while spectating one. */
    private fun restricted(player: Player): Boolean = ranked.holds(player) || ranked.isSpectator(player.uniqueId)

    // ── Command list ────────────────────────────────────────

    /** Issue #1369: `/ranked` stays out of players' tab completion while Ranked Team Wars are closed. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onCommandSend(event: org.bukkit.event.player.PlayerCommandSendEvent) {
        if (plugin.lifestealRankedQueueManager.commandVisibleTo(event.player)) return
        event.commands.removeAll(setOf("ranked", "joshymc:ranked"))
    }

    // ── Damage ──────────────────────────────────────────────

    private fun attackerOf(event: EntityDamageEvent): Player? {
        (event.damageSource.causingEntity as? Player)?.let { return it }
        if (event !is EntityDamageByEntityEvent) return null
        return when (val source = event.damager) {
            is Player -> source
            is Projectile -> source.shooter as? Player
            is TNTPrimed -> source.source as? Player
            is Tameable -> source.owner as? Player
            else -> null
        }
    }

    /** Whether harm from [attacker] (null = no player behind it) to [victim] is refused. */
    private fun harmBlocked(attacker: Player?, victim: Player): Boolean {
        // Spectators (staff watching) are out of the game entirely while they watch.
        if (ranked.isSpectator(victim.uniqueId)) return true
        if (attacker != null && attacker.uniqueId != victim.uniqueId && ranked.isSpectator(attacker.uniqueId)) return true
        val victimMatch = ranked.matchOf(victim.uniqueId)
        if (victimMatch != null) {
            if (victimMatch.state != MatchState.ACTIVE) return true
            if (attacker == null || attacker.uniqueId == victim.uniqueId) return false
            return !ranked.isMatchFight(attacker, victim)
        }
        // A participant can't reach anyone outside their match.
        return attacker != null && attacker.uniqueId != victim.uniqueId && ranked.holds(attacker)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        if (!ranked.anyMatch) return
        if (harmBlocked(attackerOf(event), victim)) event.isCancelled = true
    }

    /**
     * Issue #1465: hits that landed (MONITOR, not cancelled) between opponents of an ACTIVE match,
     * remembered by the match as the only candidates for a disconnect forfeit's heart.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLandedHit(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        if (!ranked.anyMatch || event.finalDamage <= 0.0) return
        val attacker = attackerOf(event) ?: return
        if (attacker.uniqueId == victim.uniqueId) return
        ranked.recordHit(attacker, victim)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSplash(event: PotionSplashEvent) {
        if (!ranked.anyMatch) return
        val thrower = event.potion.shooter as? Player
        for (entity in event.affectedEntities) {
            val victim = entity as? Player ?: continue
            if (harmBlocked(thrower, victim)) event.setIntensity(victim, 0.0)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCloud(event: AreaEffectCloudApplyEvent) {
        if (!ranked.anyMatch) return
        val thrower = event.entity.source as? Player
        event.affectedEntities.removeIf { it is Player && harmBlocked(thrower, it) }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCombust(event: EntityCombustByEntityEvent) {
        val victim = event.entity as? Player ?: return
        if (!ranked.anyMatch) return
        val attacker = when (val c = event.combuster) {
            is Player -> c
            is Projectile -> c.shooter as? Player
            else -> null
        }
        if (attacker != null && harmBlocked(attacker, victim)) event.isCancelled = true
    }

    // ── Death, respawn, join, quit ──────────────────────────

    /** HIGHEST: after other plugins added drops (e.g. the 1% player head), before the heart service at MONITOR. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        if (!ranked.onDeath(event.player)) return
        event.drops.clear()
        event.droppedExp = 0
        event.keepInventory = false
        event.keepLevel = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        if (ranked.holds(player) || !ranked.hasPendingRestore(player.uniqueId)) return
        ranked.pendingReturnLocation(player.uniqueId)?.let { event.respawnLocation = it }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPostRespawn(event: PlayerPostRespawnEvent) {
        ranked.restoreIfPending(event.player, "respawned")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        ranked.onJoin(event.player)
        // Issue #1365: committed Elo results they haven't seen yet.
        plugin.lifestealRatingManager.onJoin(event.player)
    }

    /** Issue #1465: the kick cause of a kick that is going through, read by the quit that follows. */
    private val pendingKicks = HashMap<UUID, String>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onKick(event: PlayerKickEvent) {
        if (ranked.holds(event.player)) pendingKicks[event.player.uniqueId] = event.cause.name
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onQuit(event: PlayerQuitEvent) {
        lastHint.remove(event.player.uniqueId)
        val kickCause = pendingKicks.remove(event.player.uniqueId)
        ranked.onQuit(event.player, event.reason.name, kickCause)
        // Issue #1363: a queued/confirming player's team entry ends (a created match stays the match manager's).
        plugin.lifestealRankedQueueManager.onQuit(event.player)
        plugin.lifestealRatingManager.onQuit(event.player)
    }

    // ── Movement, teleports, boundaries ─────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (!ranked.anyMatch) return
        val player = event.player
        val to = event.to
        if (ranked.isExpectedTeleport(player.uniqueId, to)) return
        val match = ranked.matchOf(player.uniqueId)
        if (match != null) {
            // Inside their own arena is fine (ender pearls, chorus fruit); anything else stays refused.
            if (inside(match.arena, to)) return
            event.isCancelled = true
            hint(player, "You can't leave the arena during a ranked match.")
            return
        }
        val world = to.world ?: return
        if (ranked.heldArenasIn(world).any { inside(it.arena, to) }) {
            event.isCancelled = true
            hint(player, "A ranked match is using that arena.")
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!ranked.anyMatch) return
        val from = event.from
        val to = event.to
        if (from.x == to.x && from.y == to.y && from.z == to.z) return
        val player = event.player
        val match = ranked.matchOf(player.uniqueId)
        if (match != null) {
            if (inside(match.arena, from) && !inside(match.arena, to)) {
                event.setTo(from.clone().apply { yaw = to.yaw; pitch = to.pitch })
                hint(player, "That's the edge of the arena.")
            }
            return
        }
        val world = to.world ?: return
        if (ranked.heldArenasIn(world).any { !inside(it.arena, from) && inside(it.arena, to) }) {
            event.setTo(from.clone().apply { yaw = to.yaw; pitch = to.pitch })
            hint(player, "A ranked match is using this arena.")
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onVehicleEnter(event: VehicleEnterEvent) {
        val player = event.entered as? Player ?: return
        if (restricted(player)) event.isCancelled = true
    }

    // ── Commands ────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        if (!ranked.holds(player)) return
        // Issue #1498: the label is resolved like delegated commands are, so an allowed alias that
        // really belongs to another command is refused.
        if (RankedMatchRules.commandAllowed(event.message, com.liam.joshymc.staff.DelegatedCommands.canonicalName(event.message))) return
        // Staff in a test match can still run the ranked admin tools (to cancel it, for example).
        val label = event.message.removePrefix("/").trim().lowercase()
        if (player.hasPermission(LifestealRankedManager.ADMIN_PERMISSION) && (label.startsWith("lifesteal admin ranked") || label.startsWith("joshymc:lifesteal admin ranked"))) return
        event.isCancelled = true
        hint(player, "Only private messages work during a ranked match.")
    }

    // ── Restricted abilities (issue #1498) ──────────────────

    /**
     * A held participant stays in Survival, whoever asks (their own command, staff, console,
     * another plugin). Leaving the match (death respawn, cancel, removal) is never blocked: they
     * aren't held any more by then.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        val player = event.player
        val held = ranked.holds(player)
        if (RankedIntegrity.gameModeChangeAllowed(held, event.newGameMode == GameMode.SURVIVAL)) return
        event.isCancelled = true
        hint(player, "Your game mode can't change during a ranked match.")
        ranked.logBlockedAbility(player, "gamemode:${event.newGameMode.name.lowercase()}", event.cause.name)
    }

    /** Flight is taken at preparation; nothing turns it back on while held. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onToggleFlight(event: PlayerToggleFlightEvent) {
        if (!event.isFlying) return
        val player = event.player
        if (RankedIntegrity.flightAllowed(ranked.holds(player))) return
        event.isCancelled = true
        if (player.gameMode == GameMode.SURVIVAL) {
            player.allowFlight = false
        }
        hint(player, "You can't fly during a ranked match.")
        ranked.logBlockedAbility(player, "flight", "toggle")
    }

    // ── Inventory protection ────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (restricted(player)) event.isCancelled = true
    }

    /**
     * Issue #1377: arrows and tridents a participant fires are tagged with their match. The snapshot
     * gives the shooter those items back, so the projectile itself can't be kept by anyone outside
     * the match (an arrow shot over the boundary to a friend) or come back after the match (a Loyalty
     * trident returning into the restored inventory): both would be a second copy.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLaunch(event: ProjectileLaunchEvent) {
        val arrow = event.entity as? AbstractArrow ?: return
        val shooter = arrow.shooter as? Player ?: return
        val match = ranked.matchOf(shooter.uniqueId) ?: return
        arrow.persistentDataContainer.set(firedInKey, PersistentDataType.STRING, match.id)
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPickupArrow(event: PlayerPickupArrowEvent) {
        val arrow = event.arrow
        val firedIn = arrow.persistentDataContainer.get(firedInKey, PersistentDataType.STRING)
        val decision = RankedMatchRules.projectilePickup(firedIn, ranked.matchOf(event.player.uniqueId)?.id, firedIn != null && ranked.match(firedIn) != null)
        when (decision) {
            RankedMatchRules.ProjectilePickup.ALLOW -> Unit
            RankedMatchRules.ProjectilePickup.DENY -> event.isCancelled = true
            RankedMatchRules.ProjectilePickup.REMOVE -> {
                event.isCancelled = true
                arrow.remove()
            }
        }
    }

    /** Issue #1490: Ominous Bottles can't be drunk in a match (see [RankedPlayerState.REFUSED_CONSUMABLES]). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        if (!ranked.holds(event.player)) return
        if (!RankedPlayerState.consumeRefused(event.item.type.key.toString())) return
        event.isCancelled = true
        hint(event.player, "Ominous Bottles can't be used during a ranked match.")
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (!ranked.holds(event.player)) return
        event.isCancelled = true
        hint(event.player, "You can't drop items during a ranked match.")
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        if (!restricted(player)) return
        val type = event.inventory.type
        if (type == InventoryType.CRAFTING || type == InventoryType.PLAYER) return
        event.isCancelled = true
        hint(player, "Containers and menus are closed during a ranked match.")
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (restricted(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onArmorStand(event: PlayerArmorStandManipulateEvent) {
        if (restricted(event.player)) event.isCancelled = true
    }

    /** Not ignoreCancelled (right-clicking air arrives cancelled); only denies using the clicked block. */
    @EventHandler(priority = EventPriority.LOW)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (!restricted(event.player)) return
        val block = event.clickedBlock ?: return
        if (block.type.isInteractable) event.setUseInteractedBlock(Event.Result.DENY)
    }

    // ── World protection (arenas never change) ──────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (restricted(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (restricted(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (restricted(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (restricted(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onSign(event: SignChangeEvent) {
        if (restricted(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onHangingPlace(event: HangingPlaceEvent) {
        val player = event.player ?: return
        if (restricted(player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        val player = event.remover as? Player ?: return
        if (restricted(player)) event.isCancelled = true
    }

    /** End Crystals stay usable (a standard Lifesteal weapon, removed afterwards); boats, carts and armor stands don't. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onEntityPlace(event: EntityPlaceEvent) {
        val player = event.player ?: return
        if (restricted(player) && event.entity.type != EntityType.END_CRYSTAL) event.isCancelled = true
    }

    /** Explosions in an arena a match is using never break blocks (crystals, TNT, anchors, beds). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (!ranked.anyMatch) return
        val arenas = ranked.heldArenasIn(event.location.world ?: return)
        if (arenas.isEmpty()) return
        event.blockList().removeIf { b -> arenas.any { it.arena.bounds?.contains(b.x + 0.5, b.y + 0.5, b.z + 0.5) == true } }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (!ranked.anyMatch) return
        val arenas = ranked.heldArenasIn(event.block.world)
        if (arenas.isEmpty()) return
        event.blockList().removeIf { b -> arenas.any { it.arena.bounds?.contains(b.x + 0.5, b.y + 0.5, b.z + 0.5) == true } }
    }
}
