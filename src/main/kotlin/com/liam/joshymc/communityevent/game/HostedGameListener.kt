package com.liam.joshymc.communityevent.game

import com.liam.joshymc.communityevent.LeaveReason
import com.liam.joshymc.communityevent.arena.ArenaField
import com.liam.joshymc.communityevent.arena.ArenaPoint
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.block.Action

/**
 * Bukkit side of hosted Spleef/Sumo (issue #1187). Registered once by ListenerManager, so a
 * plugin reload never doubles it. Every handler returns at once unless a Spleef/Sumo session
 * is RUNNING, and eliminations are driven by movement events, not per-tick scans.
 *
 * Block breaking/placing is decided in EventManager's own handlers (see
 * [HostedGames.breakDecision]) so the event world's protections stay in one place.
 */
class HostedGameListener(private val games: HostedGames) : Listener {

    // ── Movement: countdown freeze and eliminations ───────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val game = games.running ?: return
        val player = event.player
        if (!game.isCompeting(player.uniqueId)) return
        val from = event.from
        val to = event.to
        when (game.round.state) {
            RoundState.COUNTDOWN -> {
                // Frozen in place until GO; looking around and falling are fine.
                if (from.x != to.x || from.z != to.z) {
                    event.setTo(org.bukkit.Location(from.world, from.x, to.y, from.z, to.yaw, to.pitch))
                }
            }
            RoundState.ACTIVE -> if (from.y != to.y || from.blockX != to.blockX || from.blockZ != to.blockZ) game.onActiveMove(player, to)
            else -> {}
        }
    }

    // ── Damage: nobody in a game is hurt; Sumo hits only knock back ──

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDamageByEntity(event: EntityDamageByEntityEvent) {
        val game = games.running ?: return
        val victim = event.entity as? Player
        val attacker = when (val d = event.damager) {
            is Player -> d
            is Projectile -> d.shooter as? Player
            else -> null
        }
        val victimIn = victim != null && game.isPlaying(victim)
        val attackerIn = attacker != null && game.isPlaying(attacker)
        if (!victimIn && !attackerIn) return
        // Cancelled first, so no damage, death, combat tag or PvP-toggle message follows.
        event.isCancelled = true
        if (game === games.sumo && attacker != null && victim != null && event.damager === attacker &&
            event.cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
        ) {
            games.sumo.knockback(attacker, victim)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDamage(event: EntityDamageEvent) {
        if (event is EntityDamageByEntityEvent) return
        val game = games.running ?: return
        val victim = event.entity as? Player ?: return
        if (!game.isPlaying(victim)) return
        event.isCancelled = true
        // Below the elimination height a competitor is already out; anyone else falling into the void is caught.
        if (event.cause == EntityDamageEvent.DamageCause.VOID && !game.isCompeting(victim.uniqueId)) {
            game.waitingLocation()?.let { victim.fallDistance = 0f; victim.teleport(it) }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHunger(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (games.running?.isPlaying(player) == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.player
        val game = games.running
        if (game != null && game.isPlaying(player)) {
            // Event-world inventory only: nothing drops into the arena.
            event.drops.clear()
            event.droppedExp = 0
            event.keepInventory = false
            game.died(player.uniqueId)
            pendingRespawn += player.uniqueId
        } else if (event.drops.any { games.gear.isGear(it) }) {
            event.drops.removeIf { games.gear.isGear(it) }
        }
    }

    private val pendingRespawn = HashSet<java.util.UUID>()

    @EventHandler(priority = EventPriority.HIGH)
    fun onRespawn(event: PlayerRespawnEvent) {
        if (!pendingRespawn.remove(event.player.uniqueId)) return
        val game = games.running ?: return
        game.waitingLocation()?.let { event.respawnLocation = it }
    }

    // ── Items: gear stays in the event world, nothing is picked up ──

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (games.gear.isGear(event.itemDrop.itemStack) || games.running?.isPlaying(event.player) == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (games.running?.isPlaying(player) == true) event.isCancelled = true
    }

    /** Gear can't be moved into a chest or any other non-player inventory. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val top = event.view.topInventory.type
        if (top == InventoryType.CRAFTING || top == InventoryType.CREATIVE) return
        val hotbar = if (event.hotbarButton >= 0) event.whoClicked.inventory.getItem(event.hotbarButton) else null
        if (games.gear.isGear(event.currentItem) || games.gear.isGear(event.cursor) || games.gear.isGear(hotbar)) {
            event.isCancelled = true
        }
    }

    /** Spleef players are in survival to break the floor; they don't get to use arena blocks. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.PHYSICAL) return
        if (games.running?.isPlaying(event.player) != true) return
        event.setUseInteractedBlock(Event.Result.DENY)
        if (event.action == Action.RIGHT_CLICK_BLOCK) event.setUseItemInHand(Event.Result.DENY)
    }

    // ── Leaving ───────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        pendingRespawn.remove(event.player.uniqueId)
        // EventSessions.onQuit (from EventManager) withdraws them; strip gear before EventManager restores the real inventory.
        games.gear.strip(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChangedWorld(event: PlayerChangedWorldEvent) {
        val player = event.player
        if (event.from.name == games.eventWorldName && player.world.name != games.eventWorldName) {
            games.sessions.withdraw(player.uniqueId, LeaveReason.LEFT_WORLD)
            // After EventManager restores the real inventory: no event gear may come along.
            Bukkit.getScheduler().runTask(games.plugin, Runnable { if (player.isOnline) games.gear.strip(player) })
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(games.plugin, Runnable {
            if (!player.isOnline) return@Runnable
            if (player.world.name != games.eventWorldName) { games.gear.strip(player); return@Runnable }
            // Logged off on the arena during a game: back to the waiting spot, never into a round.
            val game = games.running ?: return@Runnable
            if (!game.isCompeting(player.uniqueId)) game.waitingLocation()?.let { if (isOnArena(game, player)) player.teleport(it) }
        }, 3L)
    }

    private fun isOnArena(game: MinigameGame, player: Player): Boolean {
        val arena = games.arenas.selectedFor(games.sessions.active) ?: return false
        val loc = player.location
        val near = { p: ArenaPoint -> p.world == loc.world.name && (p.x - loc.x) * (p.x - loc.x) + (p.z - loc.z) * (p.z - loc.z) < 64 }
        val floor = arena.region(ArenaField.FLOOR)
        val onFloor = floor != null && loc.blockX in floor.minX..floor.maxX && loc.blockZ in floor.minZ..floor.maxZ
        val spawns = listOf(ArenaField.SPAWN, ArenaField.SPAWN_1, ArenaField.SPAWN_2)
            .mapNotNull { arena.point(it) }
        return game.isRunning && (onFloor || spawns.any(near))
    }
}
