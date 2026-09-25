package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.Statistic
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Great Pumpkin Pie: placed like a block, eaten slice by slice.
 *
 * A placed pie is two entities on the block it was put on: an ItemDisplay showing the pie
 * model (no display transform, so the model's block-space floor sits on the ground) and an
 * Interaction hitbox that carries the state in its PDC. Right-click eats a slice (8 in all;
 * the model swaps to "bite_N" through custom model data), punching wobbles it, and
 * sneak-punching picks an untouched pie back up. Placement goes through the vanilla
 * BlockPlaceEvent, so claims and WorldGuard decide where pies may go.
 */
class GreatPumpkinPieListener(private val plugin: Joshymc) : Listener {

    companion object {
        const val ITEM_ID = "great_pumpkin_pie"
        private const val SLICES = 8
        private const val PIE_TAG = "joshymc_pie"
        private const val MODEL_TAG = "joshymc_pie_model"
        private const val FOOD_PER_SLICE = 3
        private const val SATURATION_PER_SLICE = 3.6f
        private const val EAT_COOLDOWN_MS = 350L
        private const val HITBOX_WIDTH = 1.0f
        private const val HITBOX_HEIGHT = 0.7f
        /** How far out from the centre a slice's crumbs fly from, in blocks. */
        private const val SLICE_RADIUS = 0.3
    }

    private data class BlockPos(val world: UUID, val x: Int, val y: Int, val z: Int)

    private val bitesKey = NamespacedKey(plugin, "pie_bites")
    private val ownerKey = NamespacedKey(plugin, "pie_owner")
    private val modelKey = NamespacedKey(plugin, "pie_model")

    /** Every pie in a loaded chunk: the block it sits on -> its hitbox. */
    private val pies = mutableMapOf<BlockPos, UUID>()
    private val lastBite = mutableMapOf<UUID, Long>()
    private var task: BukkitTask? = null

    fun start() {
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 10L, 10L)
        for (world in plugin.server.worlds) {
            world.getEntitiesByClass(Interaction::class.java).filter { PIE_TAG in it.scoreboardTags }.forEach(::track)
        }
    }

    fun stop() {
        task?.cancel()
        pies.clear()
    }

    // ── Placing ─────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        if (!plugin.itemManager.isCustomItem(event.itemInHand, ITEM_ID)) {
            // Nothing may be built into the space a pie takes up.
            if (pos(block) in pies) event.isCancelled = true
            return
        }
        // The pie is never a cake block; the vanilla placement only picks the spot and runs
        // the protection checks.
        event.isCancelled = true
        if (!event.canBuild() || pos(block) in pies) return
        val player = event.player
        spawnPie(block, player.location.yaw, player.uniqueId)
        if (player.gameMode != GameMode.CREATIVE) {
            val held = player.inventory.getItem(event.hand)
            held.amount -= 1
        }
        // Grass or snow the cake would have replaced comes back when the event is cancelled.
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!block.type.isAir && block.isReplaceable && !block.isLiquid) block.setType(Material.AIR, false)
        })
        block.world.playSound(block.location.add(0.5, 0.2, 0.5), Sound.BLOCK_HONEY_BLOCK_PLACE, 1f, 0.9f)
        block.world.playSound(block.location.add(0.5, 0.2, 0.5), Sound.BLOCK_CAKE_ADD_CANDLE, 0.8f, 1.2f)
    }

    @EventHandler(ignoreCancelled = true)
    fun onFlow(event: BlockFromToEvent) {
        if (pos(event.toBlock) in pies) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPiston(event: BlockPistonExtendEvent) {
        val into = event.blocks.map { it.getRelative(event.direction) } + event.block.getRelative(event.direction)
        if (into.any { pos(it) in pies }) event.isCancelled = true
    }

    private fun spawnPie(block: Block, playerYaw: Float, owner: UUID) {
        val world = block.world
        val base = block.location.add(0.5, 0.0, 0.5)
        // An ItemDisplay draws its item turned half a turn, so yaw = the placer's yaw puts
        // slice 1 (the model's +Z) toward them.
        val model = world.spawn(base.clone().add(0.0, 0.5, 0.0), ItemDisplay::class.java) { d ->
            d.setRotation(playerYaw, 0f)
            d.setItemStack(modelItem(0))
            d.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            d.shadowRadius = 0.5f
            d.shadowStrength = 0.7f
            d.addScoreboardTag(MODEL_TAG)
        }
        val hitbox = world.spawn(base, Interaction::class.java) { i ->
            i.setRotation(playerYaw, 0f) // remembered so a lost model can be restored facing the same way
            i.interactionWidth = HITBOX_WIDTH
            i.interactionHeight = HITBOX_HEIGHT
            i.isResponsive = true
            i.addScoreboardTag(PIE_TAG)
            i.persistentDataContainer.set(bitesKey, PersistentDataType.INTEGER, 0)
            i.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, owner.toString())
            i.persistentDataContainer.set(modelKey, PersistentDataType.STRING, model.uniqueId.toString())
        }
        track(hitbox)
        wobble(model, 1.08f, 0.9f)
        world.spawnParticle(Particle.HAPPY_VILLAGER, base.clone().add(0.0, 0.5, 0.0), 8, 0.35, 0.2, 0.35, 0.0)
    }

    /** The pie item as the display shows it: no glint, and the bite state as custom model data. */
    private fun modelItem(bites: Int): ItemStack {
        val item = plugin.itemManager.getItem(ITEM_ID)?.createItemStack() ?: ItemStack(Material.CAKE)
        item.editMeta { meta ->
            meta.setEnchantmentGlintOverride(false)
            if (bites > 0) {
                val data = meta.customModelDataComponent
                data.strings = listOf("bite_$bites")
                meta.setCustomModelDataComponent(data)
            }
        }
        return item
    }

    // ── Eating ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val hitbox = event.rightClicked as? Interaction ?: return
        if (PIE_TAG !in hitbox.scoreboardTags) return
        event.isCancelled = true
        eat(event.player, hitbox)
    }

    private fun eat(player: Player, hitbox: Interaction) {
        val now = System.currentTimeMillis()
        if (now - (lastBite[player.uniqueId] ?: 0L) < EAT_COOLDOWN_MS) return
        if (player.gameMode != GameMode.CREATIVE && player.foodLevel >= 20) {
            player.sendActionBar(Component.text("You're too full for another slice", NamedTextColor.GOLD))
            return
        }
        lastBite[player.uniqueId] = now
        val bites = (hitbox.persistentDataContainer.get(bitesKey, PersistentDataType.INTEGER) ?: 0) + 1
        feed(player)
        player.incrementStatistic(Statistic.CAKE_SLICES_EATEN)
        val model = modelOf(hitbox)
        val centre = hitbox.location
        val slice = sliceOffset(model?.location?.yaw ?: centre.yaw, bites)
        val crumbs = centre.clone().add(slice.x.toDouble(), 0.45, slice.z.toDouble())
        val world = hitbox.world
        world.spawnParticle(Particle.ITEM, crumbs, 14, 0.08, 0.06, 0.08, 0.06, ItemStack(Material.PUMPKIN_PIE))
        world.spawnParticle(Particle.DUST, crumbs, 6, 0.12, 0.08, 0.12, 0.0, Particle.DustOptions(Color.fromRGB(0xF4A340), 1.1f))
        world.playSound(centre, Sound.ENTITY_GENERIC_EAT, 1f, Random.nextDouble(0.85, 1.15).toFloat())
        // Each slice rings a little higher, so finishing the pie climbs a scale.
        world.playSound(centre, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.45f, 0.6f + bites * 0.12f)

        if (bites >= SLICES) {
            finish(player, hitbox, model)
            return
        }
        hitbox.persistentDataContainer.set(bitesKey, PersistentDataType.INTEGER, bites)
        if (model != null) {
            model.setItemStack(modelItem(bites))
            wobble(model, 1.1f, 0.86f)
        }
    }

    private fun feed(player: Player) {
        player.foodLevel = (player.foodLevel + FOOD_PER_SLICE).coerceAtMost(20)
        player.saturation = (player.saturation + SATURATION_PER_SLICE).coerceAtMost(player.foodLevel.toFloat())
    }

    private fun finish(player: Player, hitbox: Interaction, model: ItemDisplay?) {
        val centre = hitbox.location.add(0.0, 0.35, 0.0)
        val world = hitbox.world
        removePie(hitbox, model)
        world.spawnParticle(Particle.ITEM, centre, 40, 0.25, 0.15, 0.25, 0.12, ItemStack(Material.PUMPKIN_PIE))
        world.spawnParticle(Particle.DUST, centre, 24, 0.3, 0.2, 0.3, 0.0, Particle.DustOptions(Color.fromRGB(0xFFD27A), 1.3f))
        world.spawnParticle(Particle.HAPPY_VILLAGER, centre, 12, 0.4, 0.3, 0.4, 0.0)
        world.playSound(centre, Sound.ENTITY_PLAYER_BURP, 1f, 1f)
        world.playSound(centre, Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 1.5f)
        player.sendActionBar(Component.text("You finished the Great Pumpkin Pie!", NamedTextColor.GOLD))
    }

    /** Where slice n (1-8) sits from the centre, for a display with this yaw. */
    private fun sliceOffset(yaw: Float, slice: Int): Vector3f {
        val a = (slice - 1) * PI / 4
        val facing = Quaternionf().rotateY(Math.toRadians(-yaw.toDouble()).toFloat()).rotateY(PI.toFloat())
        return facing.transform(Vector3f(sin(a).toFloat(), 0f, cos(a).toFloat())).mul(SLICE_RADIUS.toFloat())
    }

    // ── Punching and picking up ─────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    fun onPunch(event: PrePlayerAttackEntityEvent) {
        val hitbox = event.attacked as? Interaction ?: return
        if (PIE_TAG !in hitbox.scoreboardTags) return
        event.isCancelled = true
        val player = event.player
        val model = modelOf(hitbox)
        if (!player.isSneaking) {
            model?.let { wobble(it, 1.06f, 0.92f) }
            hitbox.world.playSound(hitbox.location, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 1.4f)
            return
        }
        if (!canPickUp(player, hitbox)) {
            player.sendActionBar(Component.text("Only whoever placed this pie can pick it up", NamedTextColor.RED))
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.6f, 1.2f)
            return
        }
        val bites = hitbox.persistentDataContainer.get(bitesKey, PersistentDataType.INTEGER) ?: 0
        val centre = hitbox.location.add(0.0, 0.3, 0.0)
        removePie(hitbox, model)
        if (bites == 0) {
            val item = plugin.itemManager.getItem(ITEM_ID)?.createItemStack() ?: return
            player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
            hitbox.world.playSound(centre, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1f)
        } else {
            // Like a vanilla cake, a started pie breaks into crumbs.
            hitbox.world.spawnParticle(Particle.ITEM, centre, 30, 0.25, 0.12, 0.25, 0.08, ItemStack(Material.PUMPKIN_PIE))
            hitbox.world.playSound(centre, Sound.BLOCK_WOOL_BREAK, 1f, 0.8f)
        }
    }

    private fun canPickUp(player: Player, hitbox: Interaction): Boolean {
        if (player.hasPermission("joshymc.admin")) return true
        val owner = hitbox.persistentDataContainer.get(ownerKey, PersistentDataType.STRING)
        if (owner == player.uniqueId.toString()) return true
        return plugin.claimManager.getClaimAt(hitbox.location)?.ownerUuid == player.uniqueId
    }

    // ── Upkeep ──────────────────────────────────────────────────────────

    /** Pops pies whose support is gone, restores missing models and puffs a little steam. */
    private fun tick() {
        val unsupported = mutableListOf<Interaction>()
        val iterator = pies.values.iterator()
        while (iterator.hasNext()) {
            val hitbox = plugin.server.getEntity(iterator.next()) as? Interaction
            if (hitbox == null || !hitbox.isValid) {
                iterator.remove()
                continue
            }
            if (!hitbox.location.block.getRelative(BlockFace.DOWN).isSolid) {
                unsupported += hitbox
                continue
            }
            val model = modelOf(hitbox)
            if (model == null) {
                respawnModel(hitbox)
            } else if (Random.nextFloat() < 0.3f) {
                val top = hitbox.location.add(Random.nextDouble(-0.2, 0.2), 0.55, Random.nextDouble(-0.2, 0.2))
                hitbox.world.spawnParticle(Particle.WHITE_SMOKE, top, 0, 0.0, 0.04, 0.0, 1.0)
            }
        }
        for (hitbox in unsupported) {
            val bites = hitbox.persistentDataContainer.get(bitesKey, PersistentDataType.INTEGER) ?: 0
            val centre = hitbox.location.add(0.0, 0.3, 0.0)
            removePie(hitbox, modelOf(hitbox))
            if (bites == 0) plugin.itemManager.getItem(ITEM_ID)?.createItemStack()?.let { hitbox.world.dropItemNaturally(centre, it) }
            hitbox.world.spawnParticle(Particle.ITEM, centre, 20, 0.2, 0.1, 0.2, 0.06, ItemStack(Material.PUMPKIN_PIE))
        }
    }

    private fun respawnModel(hitbox: Interaction) {
        val bites = hitbox.persistentDataContainer.get(bitesKey, PersistentDataType.INTEGER) ?: 0
        val model = hitbox.world.spawn(hitbox.location.add(0.0, 0.5, 0.0), ItemDisplay::class.java) { d ->
            d.setRotation(hitbox.location.yaw, 0f)
            d.setItemStack(modelItem(bites))
            d.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            d.shadowRadius = 0.5f
            d.shadowStrength = 0.7f
            d.addScoreboardTag(MODEL_TAG)
        }
        hitbox.persistentDataContainer.set(modelKey, PersistentDataType.STRING, model.uniqueId.toString())
    }

    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        event.entities.filterIsInstance<Interaction>().filter { PIE_TAG in it.scoreboardTags }.forEach(::track)
    }

    @EventHandler
    fun onEntitiesUnload(event: EntitiesUnloadEvent) {
        for (entity in event.entities) {
            if (entity is Interaction && PIE_TAG in entity.scoreboardTags) pies.remove(pos(entity.location.block))
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun pos(block: Block) = BlockPos(block.world.uid, block.x, block.y, block.z)

    private fun track(hitbox: Interaction) {
        pies[pos(hitbox.location.block)] = hitbox.uniqueId
    }

    private fun modelOf(hitbox: Interaction): ItemDisplay? {
        val id = hitbox.persistentDataContainer.get(modelKey, PersistentDataType.STRING)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        (id?.let { plugin.server.getEntity(it) } as? ItemDisplay)?.takeIf { it.isValid }?.let { return it }
        return hitbox.world.getNearbyEntities(hitbox.location.add(0.0, 0.5, 0.0), 0.3, 0.3, 0.3)
            .filterIsInstance<ItemDisplay>().firstOrNull { MODEL_TAG in it.scoreboardTags }
    }

    private fun removePie(hitbox: Interaction, model: ItemDisplay?) {
        pies.remove(pos(hitbox.location.block))
        model?.remove()
        hitbox.remove()
    }

    /** Squash about the pie's base, spring back half as far the other way, then settle. */
    private fun wobble(model: ItemDisplay, squashXZ: Float, squashY: Float) {
        shape(model, squashXZ, squashY, 2)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            shape(model, 1f - (squashXZ - 1f) / 2, 1f + (1f - squashY) / 2, 3)
        }, 2L)
        plugin.server.scheduler.runTaskLater(plugin, Runnable { shape(model, 1f, 1f, 3) }, 5L)
    }

    private fun shape(model: ItemDisplay, xz: Float, y: Float, ticks: Int) {
        if (!model.isValid) return
        model.interpolationDelay = 0
        model.interpolationDuration = ticks
        // Model y = 0 (the pie's base) sits 0.5 below the display; keep it on the ground.
        model.transformation = Transformation(Vector3f(0f, -0.5f * (1f - y), 0f), Quaternionf(), Vector3f(xz, y, xz), Quaternionf())
    }
}
