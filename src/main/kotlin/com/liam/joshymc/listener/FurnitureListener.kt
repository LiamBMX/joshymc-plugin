package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.FurnitureItem
import com.liam.joshymc.util.WorldGuardBuild
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Placeable decorative furniture (issue #1286): every custom item that implements
 * [FurnitureItem] (the treasure decorations).
 *
 * A placed piece is two entities on its block, both saved with the chunk, so it survives
 * restarts and chunk unloads with nothing in the database: an ItemDisplay showing the item's
 * model (no display transform, so the model's block-space floor sits on the ground; yaw
 * snapped to 45 degrees so its front faces the placer) and an Interaction hitbox holding the
 * state in its PDC (item id, owner, model UUID). Nothing ticks per piece; one cheap check a
 * second only looks at the block under each piece in loaded chunks.
 *
 * Placement goes through the item's vanilla BlockPlaceEvent (the CAKE base needs a solid
 * block under it), so claims, WorldGuard and every other place rule decide where it may go.
 * Punching it gives the item back and sneak + right-click turns it, both only for players who
 * may build there (claim access + WorldGuard build) or the owner/admins. It only ever gives
 * back the decoration item itself, removing the entities first so it can't be taken twice.
 */
class FurnitureListener(private val plugin: Joshymc) : Listener {

    companion object {
        private const val HITBOX_TAG = "joshymc_furniture"
        private const val MODEL_TAG = "joshymc_furniture_model"
        private const val ROTATE_STEP = 45f
        private const val ACTION_COOLDOWN_MS = 250L
    }

    private data class BlockPos(val world: UUID, val x: Int, val y: Int, val z: Int)

    private val idKey = NamespacedKey(plugin, "furniture_id")
    private val ownerKey = NamespacedKey(plugin, "furniture_owner")
    private val modelKey = NamespacedKey(plugin, "furniture_model")
    private val hitboxKey = NamespacedKey(plugin, "furniture_hitbox")

    /** Every piece in a loaded chunk: the block it stands in -> its hitbox. */
    private val placed = mutableMapOf<BlockPos, UUID>()
    private val lastAction = mutableMapOf<UUID, Long>()
    private var task: BukkitTask? = null

    fun start() {
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { checkSupport() }, 20L, 20L)
        for (world in plugin.server.worlds) loaded(world.entities)
    }

    fun stop() {
        task?.cancel()
        placed.clear()
        lastAction.clear()
    }

    /** True when furniture stands in [block], so nothing else may be put there. */
    fun occupies(block: Block): Boolean = pos(block) in placed

    private fun furnitureOf(item: ItemStack?): Pair<String, FurnitureItem>? {
        val id = plugin.itemManager.getCustomItemId(item) ?: return null
        val furniture = plugin.itemManager.getItem(id) as? FurnitureItem ?: return null
        return id to furniture
    }

    // ── Placing ─────────────────────────────────────────────────────────

    /** Nothing may be built into a piece's space (this also stops a pie or another piece). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlaceIntoFurniture(event: BlockPlaceEvent) {
        if (pos(event.blockPlaced) in placed) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val (id, furniture) = furnitureOf(event.itemInHand) ?: return
        // Never a real block: the vanilla placement only picks the spot and runs the protection checks.
        event.isCancelled = true
        val block = event.blockPlaced
        val player = event.player
        if (!event.canBuild() || !block.getRelative(0, -1, 0).isSolid) return
        if (plugin.listenerManager.greatPumpkinPieListener?.occupies(block) == true) return
        val held = player.inventory.getItem(event.hand)
        if (!plugin.itemManager.isCustomItem(held, id)) return
        if (player.gameMode != GameMode.CREATIVE) held.amount -= 1

        val yaw = snap(player.location.yaw)
        spawn(block, id, furniture, yaw, player.uniqueId)
        // Grass or snow the cake would have replaced comes back when the event is cancelled.
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!block.type.isAir && block.isReplaceable && !block.isLiquid) block.setType(Material.AIR, false)
        })
        block.world.playSound(block.location.add(0.5, 0.2, 0.5), furniture.placeSound, 1f, 1f)
    }

    private fun spawn(block: Block, id: String, furniture: FurnitureItem, yaw: Float, owner: UUID) {
        val base = block.location.add(0.5, 0.0, 0.5)
        val hitbox = block.world.spawn(base, Interaction::class.java) { i ->
            i.setRotation(yaw, 0f) // remembered so a lost model can be restored facing the same way
            i.interactionWidth = furniture.hitboxWidth
            i.interactionHeight = furniture.hitboxHeight
            i.isResponsive = true
            i.addScoreboardTag(HITBOX_TAG)
            i.persistentDataContainer.set(idKey, PersistentDataType.STRING, id)
            i.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, owner.toString())
        }
        spawnModel(hitbox, id, furniture, yaw)
        placed[pos(block)] = hitbox.uniqueId
        block.world.spawnParticle(Particle.WAX_OFF, base.clone().add(0.0, 0.3, 0.0), 6, 0.3, 0.15, 0.3, 0.0)
    }

    private fun spawnModel(hitbox: Interaction, id: String, furniture: FurnitureItem, yaw: Float): ItemDisplay {
        // An ItemDisplay draws its item turned half a turn, so yaw = the placer's yaw puts the
        // model's +Z (its front) toward them.
        val model = hitbox.world.spawn(hitbox.location.add(0.0, 0.5, 0.0), ItemDisplay::class.java) { d ->
            d.setRotation(yaw, 0f)
            d.setItemStack(modelItem(id))
            d.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            d.shadowRadius = furniture.hitboxWidth * 0.55f
            d.shadowStrength = 0.6f
            d.addScoreboardTag(MODEL_TAG)
            d.persistentDataContainer.set(hitboxKey, PersistentDataType.STRING, hitbox.uniqueId.toString())
        }
        hitbox.persistentDataContainer.set(modelKey, PersistentDataType.STRING, model.uniqueId.toString())
        return model
    }

    /** The item as the display shows it: one, without the glint. */
    private fun modelItem(id: String): ItemStack {
        val item = plugin.itemManager.getItem(id)?.createItemStack() ?: ItemStack(Material.CAKE)
        item.editMeta { it.setEnchantmentGlintOverride(false) }
        return item
    }

    // ── Punching (pick up) and sneak + right-click (turn) ───────────────

    @EventHandler(ignoreCancelled = true)
    fun onPunch(event: PrePlayerAttackEntityEvent) {
        val hitbox = event.attacked as? Interaction ?: return
        if (HITBOX_TAG !in hitbox.scoreboardTags) return
        event.isCancelled = true
        val player = event.player
        if (!cooledDown(player)) return
        if (!canModify(player, hitbox)) return deny(player)
        val id = hitbox.persistentDataContainer.get(idKey, PersistentDataType.STRING)
        val sound = (id?.let { plugin.itemManager.getItem(it) } as? FurnitureItem)?.breakSound ?: Sound.BLOCK_CHAIN_BREAK
        val centre = hitbox.location.add(0.0, 0.3, 0.0)
        if (!remove(hitbox)) return
        if (player.gameMode != GameMode.CREATIVE && id != null) {
            plugin.itemManager.getItem(id)?.createItemStack()?.let { item ->
                player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
            }
        }
        hitbox.world.playSound(centre, sound, 1f, 1f)
        hitbox.world.playSound(centre, Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.1f)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val hitbox = event.rightClicked as? Interaction ?: return
        if (HITBOX_TAG !in hitbox.scoreboardTags) return
        event.isCancelled = true
        val player = event.player
        if (!player.isSneaking || !cooledDown(player)) return
        if (!canModify(player, hitbox)) return deny(player)
        val yaw = snap(hitbox.location.yaw + ROTATE_STEP)
        hitbox.setRotation(yaw, 0f)
        modelOf(hitbox)?.setRotation(yaw, 0f)
        hitbox.world.playSound(hitbox.location, Sound.BLOCK_CHAIN_STEP, 0.8f, 1.3f)
    }

    private fun cooledDown(player: Player): Boolean {
        val now = System.currentTimeMillis()
        if (now - (lastAction[player.uniqueId] ?: 0L) < ACTION_COOLDOWN_MS) return false
        lastAction[player.uniqueId] = now
        return true
    }

    /** The owner and admins, or anyone who may build there (claim access and WorldGuard). */
    private fun canModify(player: Player, hitbox: Interaction): Boolean {
        if (player.hasPermission("joshymc.admin")) return true
        val location = hitbox.location
        val owner = hitbox.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) == player.uniqueId.toString()
        if (!plugin.claimManager.canAccess(player, location)) return false
        return when (WorldGuardBuild.canBuild(player, location, plugin.logger)) {
            WorldGuardBuild.Result.ALLOWED, WorldGuardBuild.Result.ABSENT -> true
            WorldGuardBuild.Result.DENIED -> false
            WorldGuardBuild.Result.UNKNOWN -> owner
        }
    }

    private fun deny(player: Player) {
        plugin.commsManager.sendActionBar(player, Component.text("You can't change decorations here", NamedTextColor.RED))
        player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.6f, 1.2f)
    }

    // ── Keeping its space and support ───────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    fun onFlow(event: BlockFromToEvent) {
        if (pos(event.toBlock) in placed) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBucket(event: PlayerBucketEmptyEvent) {
        if (pos(event.block) in placed) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (!event.to.isAir && pos(event.block) in placed) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        val into = event.blocks.map { it.getRelative(event.direction) } + event.block.getRelative(event.direction)
        if (into.any { pos(it) in placed }) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        // Either way along the piston's axis, so a pulled block can never land in a piece.
        val into = event.blocks.flatMap { listOf(it.getRelative(event.direction), it.getRelative(event.direction.oppositeFace)) }
        if (into.any { pos(it) in placed }) event.isCancelled = true
    }

    /** Pops pieces whose supporting block is gone, dropping the decoration item. */
    private fun checkSupport() {
        if (placed.isEmpty()) return
        val unsupported = mutableListOf<UUID>()
        for ((p, hitboxId) in placed) {
            val world = plugin.server.getWorld(p.world) ?: continue
            if (!world.isChunkLoaded(p.x shr 4, p.z shr 4)) continue
            if (!world.getBlockAt(p.x, p.y - 1, p.z).isSolid) unsupported += hitboxId
        }
        for (hitboxId in unsupported) {
            val hitbox = plugin.server.getEntity(hitboxId) as? Interaction
            if (hitbox == null) {
                placed.values.remove(hitboxId)
                continue
            }
            val id = hitbox.persistentDataContainer.get(idKey, PersistentDataType.STRING)
            val centre = hitbox.location.add(0.0, 0.3, 0.0)
            if (!remove(hitbox)) continue
            id?.let { plugin.itemManager.getItem(it)?.createItemStack() }?.let { hitbox.world.dropItemNaturally(centre, it) }
        }
    }

    // ── Chunk loading ───────────────────────────────────────────────────

    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) = loaded(event.entities)

    @EventHandler
    fun onEntitiesUnload(event: EntitiesUnloadEvent) {
        for (entity in event.entities) {
            if (entity is Interaction && HITBOX_TAG in entity.scoreboardTags) placed.remove(pos(entity.location.block))
        }
    }

    /**
     * Tracks the hitboxes in [entities], restores a model that went missing and removes a
     * model whose hitbox is gone (a piece's two entities always share a chunk).
     */
    private fun loaded(entities: List<Entity>) {
        val hitboxes = entities.filterIsInstance<Interaction>().filter { HITBOX_TAG in it.scoreboardTags }
        val models = entities.filterIsInstance<ItemDisplay>().filter { MODEL_TAG in it.scoreboardTags }
        val hitboxIds = hitboxes.mapTo(HashSet()) { it.uniqueId.toString() }
        for (model in models) {
            val owner = model.persistentDataContainer.get(hitboxKey, PersistentDataType.STRING)
            if (owner !in hitboxIds && owner?.let { runCatching { plugin.server.getEntity(UUID.fromString(it)) }.getOrNull() } == null) {
                model.remove()
            }
        }
        val modelIds = models.mapTo(HashSet()) { it.uniqueId.toString() }
        for (hitbox in hitboxes) {
            placed[pos(hitbox.location.block)] = hitbox.uniqueId
            val modelId = hitbox.persistentDataContainer.get(modelKey, PersistentDataType.STRING)
            if (modelId !in modelIds && modelOf(hitbox) == null) {
                val id = hitbox.persistentDataContainer.get(idKey, PersistentDataType.STRING) ?: continue
                val furniture = plugin.itemManager.getItem(id) as? FurnitureItem ?: continue
                spawnModel(hitbox, id, furniture, hitbox.location.yaw)
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun pos(block: Block) = BlockPos(block.world.uid, block.x, block.y, block.z)

    private fun snap(yaw: Float): Float {
        val snapped = (yaw / ROTATE_STEP).roundToInt() * ROTATE_STEP
        return ((snapped % 360f) + 540f) % 360f - 180f
    }

    private fun modelOf(hitbox: Interaction): ItemDisplay? {
        val id = hitbox.persistentDataContainer.get(modelKey, PersistentDataType.STRING)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        return (plugin.server.getEntity(id) as? ItemDisplay)?.takeIf { it.isValid }
    }

    /** Removes both entities; false if the hitbox was already gone (so nothing is given twice). */
    private fun remove(hitbox: Interaction): Boolean {
        if (!hitbox.isValid) return false
        placed.remove(pos(hitbox.location.block))
        modelOf(hitbox)?.remove()
        hitbox.remove()
        return true
    }
}
