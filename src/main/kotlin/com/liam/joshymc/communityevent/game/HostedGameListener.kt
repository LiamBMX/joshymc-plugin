package com.liam.joshymc.communityevent.game

import com.liam.joshymc.communityevent.LeaveReason
import com.liam.joshymc.communityevent.arena.ArenaField
import com.liam.joshymc.communityevent.arena.ArenaPoint
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.TileState
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
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
 * Bukkit side of the hosted games (issues #1187, #1189). Registered once by ListenerManager, so
 * a plugin reload never doubles it. Every handler returns at once unless a hosted game session
 * is RUNNING, and eliminations are driven by movement and damage events, not per-tick scans.
 *
 * Block breaking/placing is decided in EventManager's own handlers (see
 * [HostedGames.breakDecision]/[HostedGames.placeDecision]) so the event world's protections
 * stay in one place. Every Crystal PvP exception (damage, crystals, blocks, explosions) is
 * scoped to the two competitors of the ACTIVE match and the arena's boundary.
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
            RoundState.ACTIVE -> if (from.y != to.y || from.blockX != to.blockX || from.blockZ != to.blockZ) {
                if (game === games.sharks) games.sharks.onMove(event) else game.onActiveMove(player, to)
            }
            else -> {}
        }
    }

    // ── Damage: nobody in a game is hurt (except Crystal PvP competitors); Sumo hits knock back, Shark hits catch ──

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDamageByEntity(event: EntityDamageByEntityEvent) {
        val game = games.running ?: return
        val attacker = when (val d = event.damager) {
            is Player -> d
            is Projectile -> d.shooter as? Player
            else -> null
        }
        if (game === games.cpvp) { crystalPvpDamage(event, attacker); return }
        val victim = event.entity as? Player
        val victimIn = victim != null && game.isPlaying(victim)
        val attackerIn = attacker != null && game.isPlaying(attacker)
        if (!victimIn && !attackerIn) return
        // Cancelled first, so no damage, death, combat tag or PvP-toggle message follows.
        event.isCancelled = true
        if (attacker == null || victim == null || event.damager !== attacker || event.cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return
        when {
            game === games.sumo -> games.sumo.knockback(attacker, victim)
            game === games.sharks -> games.sharks.hit(attacker, victim)
        }
    }

    /**
     * Crystal PvP: a competitor of the ACTIVE match may be hurt only by their opponent, by an End
     * Crystal or by themselves. Nobody else in the event (or standing near the arena) is hurt by
     * the match, and only the competitors can set off the arena's crystals.
     */
    private fun crystalPvpDamage(event: EntityDamageByEntityEvent, attacker: Player?) {
        val cpvp = games.cpvp
        val entity = event.entity
        if (entity is EnderCrystal) {
            if (attacker != null && !cpvp.isFighting(attacker.uniqueId) && (cpvp.isPlaying(attacker) || cpvp.nearArena(entity.location, 0))) {
                event.isCancelled = true
            }
            return
        }
        val victim = entity as? Player ?: return
        if (cpvp.isFighting(victim.uniqueId)) {
            val allowed = when {
                attacker != null -> attacker == victim || cpvp.isOpponent(attacker.uniqueId, victim.uniqueId)
                else -> event.damager is EnderCrystal
            }
            if (!allowed) event.isCancelled = true
            return
        }
        val involved = cpvp.isPlaying(victim) || (attacker != null && cpvp.isPlaying(attacker)) ||
            (event.damager is EnderCrystal && cpvp.nearArena(victim.location, BYSTANDER_MARGIN))
        if (involved) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDamage(event: EntityDamageEvent) {
        if (event is EntityDamageByEntityEvent) return
        val game = games.running ?: return
        val victim = event.entity as? Player ?: return
        if (game === games.cpvp) {
            // Competitors take fall, fire, explosion and other environmental damage as usual.
            if (games.cpvp.isFighting(victim.uniqueId)) return
            if (event.cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION && games.cpvp.nearArena(victim.location, BYSTANDER_MARGIN)) {
                event.isCancelled = true
                return
            }
        }
        if (!game.isPlaying(victim)) return
        event.isCancelled = true
        // Below the elimination height a competitor is already out; anyone else falling into the void is caught.
        if (event.cause == EntityDamageEvent.DamageCause.VOID && !game.isCompeting(victim.uniqueId)) {
            game.waitingLocation()?.let { victim.fallDistance = 0f; victim.teleport(it) }
        }
    }

    /**
     * Crystal PvP: damage that would kill a competitor ends their match instead, so nobody dies
     * in the event world (no death message, drops, kill rewards or respawn screen). A held
     * Totem of Undying is left to work as usual.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onLethalDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        val cpvp = games.cpvp
        if (!cpvp.isFighting(victim.uniqueId)) return
        if (event.finalDamage < victim.health) return
        val inv = victim.inventory
        if (inv.itemInMainHand.type == Material.TOTEM_OF_UNDYING || inv.itemInOffHand.type == Material.TOTEM_OF_UNDYING) return
        event.isCancelled = true
        cpvp.lethal(victim)
    }

    // ── Crystal PvP: crystals, explosions, buckets ──

    /** Entities placed by players of a game (End Crystals, boats, armor stands): only a competitor's crystal inside the arena. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityPlace(event: EntityPlaceEvent) {
        val player = event.player ?: return
        val game = games.running ?: return
        if (!game.isPlaying(player)) return
        val entity = event.entity
        if (game === games.cpvp && entity is EnderCrystal && games.cpvp.canPlaceCrystal(player, event.block)) {
            // Never saved with the chunk, so a crash can't leave one behind.
            entity.isPersistent = false
            return
        }
        event.isCancelled = true
    }

    /** Explosions of the arena's crystals only break blocks inside the boundary, never block entities, and drop nothing. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (event.entity !is EnderCrystal) return
        val cpvp = games.cpvp
        if (games.running !== cpvp || !cpvp.nearArena(event.location, 0)) return
        event.blockList().removeIf { !cpvp.inArena(it) || it.state is TileState }
        event.yield = 0f
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val cpvp = games.cpvp
        if (games.running !== cpvp || !cpvp.inArena(event.block)) return
        event.blockList().removeIf { !cpvp.inArena(it) || it.state is TileState }
        event.yield = 0f
    }

    /** No fire from match explosions outside the boundary. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onIgnite(event: BlockIgniteEvent) {
        val cpvp = games.cpvp
        if (games.running !== cpvp) return
        if (event.cause != BlockIgniteEvent.IgniteCause.EXPLOSION && event.cause != BlockIgniteEvent.IgniteCause.ENDER_CRYSTAL) return
        if (cpvp.nearArena(event.block.location, BYSTANDER_MARGIN) && !cpvp.inArena(event.block)) event.isCancelled = true
    }

    /** Players of a game never pour liquids: they'd flow out of what the arena restores. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBucket(event: PlayerBucketEmptyEvent) {
        if (games.running?.isPlaying(event.player) == true) event.isCancelled = true
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

    /**
     * Spleef and Crystal PvP players are in survival; they don't get to use arena blocks. Crystal
     * PvP competitors in an ACTIVE match keep item use on blocks (placing crystals and the kit's
     * blocks), which the place handlers here and in EventManager still limit to the arena.
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.PHYSICAL) return
        if (games.running?.isPlaying(event.player) != true) return
        event.setUseInteractedBlock(Event.Result.DENY)
        if (event.action == Action.RIGHT_CLICK_BLOCK && !games.cpvp.isFighting(event.player.uniqueId)) event.setUseItemInHand(Event.Result.DENY)
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

    private companion object {
        /** Blocks around the Crystal PvP boundary where bystanders are shielded from the match's explosions. */
        const val BYSTANDER_MARGIN = 12
    }

    private fun isOnArena(game: MinigameGame, player: Player): Boolean {
        val arena = games.arenas.selectedFor(games.sessions.active) ?: return false
        val loc = player.location
        val near = { p: ArenaPoint -> p.world == loc.world.name && (p.x - loc.x) * (p.x - loc.x) + (p.z - loc.z) * (p.z - loc.z) < 64 }
        val onFloor = listOf(ArenaField.FLOOR, ArenaField.REGION, ArenaField.FINISH).mapNotNull { arena.region(it) }
            .any { loc.blockX in it.minX..it.maxX && loc.blockZ in it.minZ..it.maxZ }
        val spawns = listOf(ArenaField.SPAWN, ArenaField.SPAWN_1, ArenaField.SPAWN_2, ArenaField.SHARK_SPAWN, ArenaField.MINNOW_SPAWN)
            .mapNotNull { arena.point(it) }
        return game.isRunning && (onFloor || spawns.any(near))
    }
}
