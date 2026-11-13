package com.liam.joshymc.lifesteal.ranked

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.ranked.RankedArenaGuard.Actor
import com.liam.joshymc.lifesteal.ranked.RankedArenaGuard.Pos
import com.liam.joshymc.lifesteal.ranked.RankedArenaGuard.Zone
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.data.Directional
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Hanging
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.block.SpongeAbsorbEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.entity.EntityTeleportEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.projectiles.BlockProjectileSource
import java.util.UUID

/**
 * Issue #1507 (audit finding F-6): nothing outside a Ranked Team Wars match changes the arena it
 * uses. The rules are the Bukkit-free [RankedArenaGuard]; this listener only reads events. It sits
 * next to [LifestealRankedListener] (registered with it, only where Lifesteal is hosted), which
 * already keeps participants and spectators from building and keeps outsiders from walking or
 * teleporting in.
 *
 * Protected: the stored boundary of every arena a match still holds (from PREPARING until the
 * cleanup releases it) or a queue proposal reserves. Every handler only cancels (or removes
 * blocks from a list), at LOW/HIGH with `ignoreCancelled`, so WorldGuard and claims still decide
 * first and nothing they refused is ever allowed. Idle arenas aren't protected here: that is
 * WorldGuard's job, and staff can maintain them between matches.
 *
 * Cost: the zone list is rebuilt at most once per server tick from in-memory state (no database,
 * no world access), and every handler returns at once while it is empty.
 */
class RankedArenaGuardListener(private val plugin: Joshymc) : Listener {

    private val ranked get() = plugin.lifestealRankedManager

    private var cachedTick = Int.MIN_VALUE
    private var cached: List<Zone> = emptyList()
    private val lastHint = HashMap<UUID, Long>()

    /** The arenas in use right now, rebuilt at most once per tick. */
    private fun zones(): List<Zone> {
        val tick = Bukkit.getCurrentTick()
        if (tick == cachedTick) return cached
        cachedTick = tick
        val reservedIds = try { plugin.lifestealRankedQueueManager.reservedArenas() } catch (_: Exception) { emptySet() }
        if (!ranked.anyMatch && reservedIds.isEmpty()) {
            cached = emptyList()
            return cached
        }
        val matches = ranked.openMatches().map { it.id to it.arena }
        val reserved = reservedIds.mapNotNull { ranked.arena(it)?.arena }
        cached = RankedArenaGuard.zones(matches, reserved)
        return cached
    }

    private fun Block.pos() = Pos(x, y, z)
    private fun BlockState.pos() = Pos(x, y, z)
    private fun Location.pos() = Pos(blockX, blockY, blockZ)

    private fun zoneOf(block: Block): Zone? {
        val zones = zones()
        if (zones.isEmpty()) return null
        return RankedArenaGuard.zoneAt(zones, block.world.name, block.x, block.y, block.z)
    }

    private fun zoneOf(loc: Location): Zone? {
        val zones = zones()
        if (zones.isEmpty()) return null
        val world = loc.world ?: return null
        return RankedArenaGuard.zoneAt(zones, world.name, loc.pos())
    }

    private fun participantOf(uuid: UUID, zone: Zone): Boolean = zone.matchId != null && ranked.matchOf(uuid)?.id == zone.matchId

    private fun refuse(player: Player?) {
        player ?: return
        val now = System.currentTimeMillis()
        val last = lastHint[player.uniqueId]
        if (last != null && now - last < 1500) return
        lastHint[player.uniqueId] = now
        plugin.commsManager.send(player, Component.text("[Ranked] ", NamedTextColor.GOLD).append(Component.text("A ranked match is using this arena.", NamedTextColor.RED)))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        lastHint.remove(event.player.uniqueId)
    }

    // ── Players changing blocks ─────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val zones = zones()
        if (zones.isEmpty()) return
        val world = event.block.world.name
        val touched = if (event is BlockMultiPlaceEvent) event.replacedBlockStates.map { it.pos() } + event.block.pos() else listOf(event.block.pos())
        if (touched.any { RankedArenaGuard.playerChangeRefused(zones, world, it) }) {
            event.isCancelled = true
            refuse(event.player)
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (zoneOf(event.block) == null) return
        event.isCancelled = true
        refuse(event.player)
    }

    /** The fluid's block and the clicked block (waterlogging), so a bucket can't reach over the boundary. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (zoneOf(event.block) == null && zoneOf(event.blockClicked) == null) return
        event.isCancelled = true
        refuse(event.player)
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (zoneOf(event.block) == null && zoneOf(event.blockClicked) == null) return
        event.isCancelled = true
        refuse(event.player)
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onSign(event: SignChangeEvent) {
        if (zoneOf(event.block) == null) return
        event.isCancelled = true
        refuse(event.player)
    }

    /**
     * Not ignoreCancelled (clicks can arrive cancelled). Someone outside the match can't use an
     * arena block from over the boundary (doors, trapdoors, levers, buttons, note blocks, the
     * dragon egg). Item effects on the block (flint and steel, bone meal, axes, hoes, shovels) are
     * refused by the ignite/fertilize/change-block handlers below. Participants keep the match
     * listener's own rule.
     */
    @EventHandler(priority = EventPriority.LOW)
    fun onInteract(event: PlayerInteractEvent) {
        // Issue #1524: an outsider standing in the arena (combat-tagged, so not moved out yet)
        // doesn't trigger its pressure plates or tripwires either.
        if (event.action == Action.PHYSICAL) {
            val block = event.clickedBlock ?: return
            val zone = zoneOf(block) ?: return
            if (!participantOf(event.player.uniqueId, zone)) event.setUseInteractedBlock(Event.Result.DENY)
            return
        }
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val zone = zoneOf(block) ?: return
        if (participantOf(event.player.uniqueId, zone)) return
        event.setUseInteractedBlock(Event.Result.DENY)
        if (event.action == Action.RIGHT_CLICK_BLOCK && block.type.isInteractable) refuse(event.player)
    }

    // ── Entities that are part of the arena ─────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onHangingPlace(event: HangingPlaceEvent) {
        if (zoneOf(event.block) == null && zoneOf(event.entity.location) == null) return
        event.isCancelled = true
        refuse(event.player)
    }

    /** Item frames and paintings in the arena stay, whatever breaks them (players, explosions, physics). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakEvent) {
        if (zoneOf(event.entity.location) == null) return
        event.isCancelled = true
    }

    /** Boats, minecarts, armor stands and End Crystals placed into the arena by anyone outside the match. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onEntityPlace(event: EntityPlaceEvent) {
        val zone = zoneOf(event.entity.location) ?: return
        val player = event.player
        if (player != null && participantOf(player.uniqueId, zone)) return
        event.isCancelled = true
        refuse(player)
    }

    /**
     * Item frames and armor stands in an arena in use stay as they are for everyone. Issue #1524:
     * nobody outside the match uses any other entity in it either (mounts, boats, minecarts,
     * leads, name tags, villagers); participants are refused by the match listener already.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        val entity = event.rightClicked
        val zone = zoneOf(entity.location) ?: return
        if (entity is Hanging || entity is ArmorStand || !participantOf(event.player.uniqueId, zone)) event.isCancelled = true
    }

    /** Issue #1524: outsiders standing in an arena in use don't pick anything up in it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val zone = zoneOf(event.item.location) ?: return
        if (!participantOf(player.uniqueId, zone)) event.isCancelled = true
    }

    // ── Entities sent into the arena (issue #1524) ──────────

    /** Spawn eggs, thrown eggs, mob buckets, built golems/withers, breeding: no player-made mob appears in an arena in use. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        val inZone = zoneOf(event.location) != null
        if (RankedArenaAccess.spawnRefused(event.spawnReason.name, inZone)) event.isCancelled = true
    }

    private fun arrivalRefused(from: Location, to: Location?): Boolean {
        to ?: return false
        val target = zoneOf(to) ?: return false
        val fromWorld = from.world?.name
        val fromInZone = fromWorld != null && target.contains(fromWorld, from.blockX, from.blockY, from.blockZ)
        return RankedArenaAccess.entityArrivalRefused(fromInZone, true)
    }

    /** Pets following an outsider, endermen and shulkers hopping in, mobs moved by other plugins: nothing arrives from outside. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onEntityTeleport(event: EntityTeleportEvent) {
        if (event.entity is Player) return
        if (arrivalRefused(event.from, event.to)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onEntityPortal(event: EntityPortalEvent) {
        if (event.entity is Player) return
        if (arrivalRefused(event.from, event.to)) event.isCancelled = true
    }

    /**
     * Issue #1524: End Crystals, mounts, mobs and anything else in an arena in use can't be hurt
     * by anyone outside its match (an outsider shooting a participant's crystal from over the
     * boundary would aim its explosion at the fight) or by an outsider's pet
     * ([RankedArenaAccess.entityHarmRefused]). Players are the match listener's.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityHarm(event: EntityDamageEvent) {
        val victim = event.entity
        if (victim is Player) return
        val zone = zoneOf(victim.location) ?: return
        val resolved = RankedDamageSources.of(event)
        val attacker = resolved.player
        val source = RankedDamageSources.sourceFor(resolved, victim) { false }
        val refused = RankedArenaAccess.entityHarmRefused(
            source = source,
            attackerSpectator = attacker != null && ranked.isSpectator(attacker.uniqueId),
            attackerParticipantOfZone = attacker != null && participantOf(attacker.uniqueId, zone),
            victimInZone = true,
        )
        if (refused) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onArmorStand(event: PlayerArmorStandManipulateEvent) {
        if (zoneOf(event.rightClicked.location) == null) return
        event.isCancelled = true
    }

    // ── Fluids, fire, growth ────────────────────────────────

    /** Water and lava (and a teleporting dragon egg) can't enter an arena from outside; inside it they flow normally. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onFlow(event: BlockFromToEvent) {
        val zones = zones()
        if (zones.isEmpty()) return
        if (RankedArenaGuard.crossesInto(zones, event.toBlock.world.name, event.block.pos(), event.toBlock.pos())) event.isCancelled = true
    }

    /** Fire, vines, sculk, mushrooms and grass spreading in from outside. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onSpread(event: BlockSpreadEvent) {
        val zones = zones()
        if (zones.isEmpty()) return
        if (RankedArenaGuard.crossesInto(zones, event.block.world.name, event.source.pos(), event.block.pos())) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onIgnite(event: BlockIgniteEvent) {
        val zone = zoneOf(event.block) ?: return
        val source = event.ignitingBlock
        val sourceInZone = source != null && zone.contains(source.world.name, source.x, source.y, source.z)
        val entity = event.ignitingEntity
        val player = when (entity) {
            is Player -> entity
            is Projectile -> entity.shooter as? Player
            else -> null
        }
        val actor = when {
            player == null -> Actor.NONE
            participantOf(player.uniqueId, zone) -> Actor.PARTICIPANT
            else -> Actor.OUTSIDER
        }
        if (!RankedArenaGuard.ignitionRefused(actor, sourceInZone, entity is EnderCrystal)) return
        event.isCancelled = true
        refuse(event.player)
    }

    /** Fire never destroys an arena block while it is in use. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        if (zoneOf(event.block) != null) event.isCancelled = true
    }

    /** Trees and huge mushrooms grown from outside don't grow into the arena. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onGrow(event: StructureGrowEvent) {
        val zones = zones()
        if (zones.isEmpty()) return
        val world = event.world.name
        val origin = event.location.pos()
        event.blocks.removeIf { RankedArenaGuard.crossesInto(zones, world, origin, it.pos()) }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onFertilize(event: BlockFertilizeEvent) {
        val zones = zones()
        if (zones.isEmpty()) return
        val world = event.block.world.name
        val origin = event.block.pos()
        event.blocks.removeIf { RankedArenaGuard.crossesInto(zones, world, origin, it.pos()) }
    }

    /** A sponge outside can't dry out water that belongs to the arena. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onSponge(event: SpongeAbsorbEvent) {
        val zones = zones()
        if (zones.isEmpty()) return
        val world = event.block.world.name
        val origin = event.block.pos()
        event.blocks.removeIf { RankedArenaGuard.crossesInto(zones, world, origin, it.pos()) }
    }

    // ── Pistons ─────────────────────────────────────────────

    private fun pistonRefused(piston: Block, moved: List<Block>, direction: org.bukkit.block.BlockFace): Boolean {
        val zones = zones()
        if (zones.isEmpty()) return false
        val facing = (piston.blockData as? Directional)?.facing ?: direction
        val head = piston.getRelative(facing)
        return RankedArenaGuard.pistonRefused(
            zones, piston.world.name, piston.pos(), head.pos(), moved.map { it.pos() },
            direction.modX, direction.modY, direction.modZ,
        )
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (pistonRefused(event.block, event.blocks, event.direction)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (pistonRefused(event.block, event.blocks, event.direction)) event.isCancelled = true
    }

    // ── Dispensers, explosions, entity block changes ────────

    /**
     * A dispenser or dropper outside the arena can't act on the block in front of it inside the
     * arena: buckets, fire, TNT, bone meal, shulker boxes, armor, projectiles and items.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDispense(event: BlockDispenseEvent) {
        val zones = zones()
        if (zones.isEmpty()) return
        val block = event.block
        val facing = (block.blockData as? Directional)?.facing ?: return
        if (RankedArenaGuard.crossesInto(zones, block.world.name, block.pos(), block.getRelative(facing).pos())) event.isCancelled = true
    }

    /**
     * Falling blocks landing in (sand, gravel, anvils, dripstone), Endermen, Withers, Ravagers,
     * players' axes/hoes/shovels/honeycomb, projectiles breaking pots or chorus flowers: no
     * entity changes an arena block while it is in use.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (zoneOf(event.block) == null) return
        event.isCancelled = true
    }

    /** Explosions keep the knockback and damage rules of the match listener, but no arena block breaks (also in reserved arenas). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        val zones = zones()
        if (zones.isEmpty()) return
        val world = event.location.world?.name ?: return
        event.blockList().removeIf { RankedArenaGuard.zoneAt(zones, world, it.x, it.y, it.z) != null }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val zones = zones()
        if (zones.isEmpty()) return
        val world = event.block.world.name
        event.blockList().removeIf { RankedArenaGuard.zoneAt(zones, world, it.x, it.y, it.z) != null }
    }

    // ── Hazards sent in from outside ────────────────────────

    /** Item frames and armor stands in the arena can't be popped or broken while it is in use. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDecorationDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (entity !is Hanging && entity !is ArmorStand) return
        if (zoneOf(entity.location) != null) event.isCancelled = true
    }

    private fun playerBehind(event: EntityDamageEvent): Player? = RankedDamageSources.of(event).player

    /**
     * A participant of an ACTIVE match isn't hurt by hazards nobody in the fight could have made:
     * TNT cannons and dispensed TNT, dispenser projectiles from outside the arena, falling blocks
     * dropped from outside it ([RankedArenaGuard.outsideHazard]). Hits with a player behind them
     * stay with the match listener's rules.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHazard(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        if (!ranked.anyMatch) return
        val match = ranked.matchOf(victim.uniqueId) ?: return
        if (match.state != MatchState.ACTIVE) return
        if (playerBehind(event) != null) return
        val damager: Entity? = (event as? EntityDamageByEntityEvent)?.damager
        val box = match.arena.bounds
        fun outside(loc: Location?): Boolean =
            loc == null || box == null || loc.world?.name != match.arena.world || !box.contains(loc.blockX + 0.5, loc.blockY + 0.5, loc.blockZ + 0.5)
        val sourceOutside = when (damager) {
            is Projectile -> (damager.shooter as? BlockProjectileSource)?.let { outside(it.block.location) } ?: false
            is FallingBlock -> outside(damager.origin)
            else -> false
        }
        if (RankedArenaGuard.outsideHazard(event.cause.name, damager is EnderCrystal, sourceOutside)) event.isCancelled = true
    }
}
