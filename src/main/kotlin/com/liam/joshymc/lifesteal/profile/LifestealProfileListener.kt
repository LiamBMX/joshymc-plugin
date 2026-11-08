package com.liam.joshymc.lifesteal.profile

import com.liam.joshymc.Joshymc
import com.liam.joshymc.milestone.FishingRules
import com.liam.joshymc.milestone.MiningRules
import com.liam.joshymc.milestone.PlacedBlockTracker
import com.liam.joshymc.milestone.RateWindow
import com.liam.joshymc.quest.HarvestRules
import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Enemy
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkUnloadEvent
import java.util.UUID

/**
 * Lifesteal profile activity (issue #1429): the gathering statistics no other Lifesteal system
 * counts. ListenerManager registers this only where Lifesteal is hosted, and every counter checks
 * the world is a Lifesteal world, so Survival activity never reaches a Lifesteal profile.
 *
 * Everything is read at MONITOR with ignoreCancelled (only things that really happened). Natural
 * ores and the crops that drop themselves use their own placed-block markers (chunk PDC key
 * `joshymc:lifesteal_profile_placed`, the Milestones tracker class), so place-and-break loops,
 * silk-touched ores, pistons and generators never count. Mobs count only for a direct player
 * kill of a hostile mob with a natural spawn reason (no spawners, eggs or event mobs), capped per
 * minute; fish at most one per [ProfileSettings.fishMinIntervalMillis]. PvP is not counted here at
 * all: it comes from the heart system's history.
 */
class LifestealProfileListener(private val plugin: Joshymc) : Listener {

    private val manager: LifestealProfileManager get() = plugin.lifestealProfileManager
    private val tracker = PlacedBlockTracker(plugin, "lifesteal_profile_placed")
    private val mobWindows = HashMap<UUID, RateWindow>()
    private val lastFish = HashMap<UUID, Long>()
    private val fishHooks = HashMap<UUID, Boolean>()

    private fun refreshTracked() {
        val ores = manager.settings?.ores ?: ProfileSettings.DEFAULT_ORES
        tracker.tracked = ores + HarvestRules.EVENT_ALWAYS_HARVESTABLE + MiningRules.GENERATED
    }

    init { refreshTracked() }

    private fun counts(player: org.bukkit.entity.Player): Boolean =
        manager.running && player.gameMode == GameMode.SURVIVAL && manager.isLifesteal(player.world)

    // ── Blocks ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        val player = event.player
        val s = manager.settings
        if (s != null && counts(player)) {
            val name = block.type.name
            val placed = tracker.isPlaced(block)
            if (name in s.ores) {
                if (MiningRules.countsBreak(placed, block.isPreferredTool(player.inventory.itemInMainHand))) manager.record(player, ProfileStat.ORES_MINED)
            } else {
                val ageable = block.blockData as? org.bukkit.block.data.Ageable
                if (HarvestRules.countsForEventQuest(name, ageable?.age, ageable?.maximumAge, placed)) manager.record(player, ProfileStat.CROPS_HARVESTED)
            }
        }
        tracker.unmark(block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (manager.isLifesteal(event.block.world)) tracker.mark(event.blockPlaced)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onForm(event: BlockFormEvent) {
        val name = event.newState.type.name
        if (name in MiningRules.GENERATED && manager.isLifesteal(event.block.world)) tracker.mark(event.block, name)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (!manager.isLifesteal(event.block.world)) return
        if (event.to.isAir) tracker.unmark(event.block) else tracker.mark(event.block, event.to.name)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) = moveMarkers(event.blocks, event.direction)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) = moveMarkers(event.blocks, event.direction)

    /** Moved blocks never count as natural afterwards. */
    private fun moveMarkers(blocks: List<Block>, direction: BlockFace) {
        if (blocks.isEmpty() || !manager.isLifesteal(blocks.first().world)) return
        val names = blocks.map { it.type.name }
        for (block in blocks) tracker.unmark(block)
        for ((i, block) in blocks.withIndex()) tracker.mark(block.getRelative(direction), names[i])
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        for (block in event.blockList()) tracker.unmark(block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        for (block in event.blockList()) tracker.unmark(block)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: ChunkUnloadEvent) = tracker.forget(event.chunk)

    // ── Mobs and fish ───────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity !is Enemy || entity is org.bukkit.entity.Player) return
        val killer = entity.killer ?: return
        val s = manager.settings ?: return
        if (!counts(killer) || EVENT_MOB_TAG in entity.scoreboardTags) return
        if (entity.entitySpawnReason?.name !in s.mobSpawnReasons) return
        val window = mobWindows.getOrPut(killer.uniqueId) { RateWindow(60_000L, s.mobsPerMinute) }
        if (!window.tryAcquire(System.currentTimeMillis())) return
        manager.record(killer, ProfileStat.MOBS_KILLED)
    }

    /** The vanilla catch, read before FishingManager swaps it for a custom fish. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onFishEarly(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val type = (event.caught as? Item)?.itemStack?.type?.name ?: return
        fishHooks[event.hook.uniqueId] = type in FishingRules.FISH
        if (fishHooks.size > 512) fishHooks.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val fish = fishHooks.remove(event.hook.uniqueId) ?: return
        val player = event.player
        val s = manager.settings ?: return
        if (!fish || !counts(player)) return
        val now = System.currentTimeMillis()
        if (!FishingRules.intervalOk(lastFish[player.uniqueId], now, s.fishMinIntervalMillis)) return
        lastFish[player.uniqueId] = now
        manager.record(player, ProfileStat.FISH_CAUGHT)
    }

    // ── Sessions ────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        refreshTracked()
        manager.onJoin(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = manager.recordDimension(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        mobWindows.remove(uuid)
        lastFish.remove(uuid)
        manager.onQuit(event.player)
    }

    companion object {
        /** Hosted-event mobs (issue #1192) never count. */
        const val EVENT_MOB_TAG = "joshymc_event_mob"
    }
}
