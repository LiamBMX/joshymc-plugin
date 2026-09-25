package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Trident
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Minecraft draws every thrown trident with the stock trident model, whatever item was thrown.
 * For custom tridents (Maplefang) the real projectile is hidden from everyone and an ItemDisplay
 * of the thrown item flies with it, so the custom 3D model shows in flight and stuck in blocks.
 * Normal tridents are left alone.
 */
class ThrownTridentVisualListener(private val plugin: Joshymc) : Listener {

    companion object {
        /** Custom item id -> how far its prong tips reach above the model centre, in blocks. */
        private val CUSTOM_TRIDENTS = mapOf("maplefang" to 1.46f)
        private const val VISUAL_TAG = "joshymc_trident_visual"
        /** In-flight size relative to the raw model (Maplefang is ~2.9 blocks long unscaled). */
        private const val SCALE = 0.65f
        /** How far the tips reach past the trident's position, so they sink into what it hits. */
        private const val EMBED = 0.25f
    }

    /** Thrown trident UUID -> the ItemDisplay flying with it. */
    private val visuals = mutableMapOf<UUID, UUID>()
    private var task: BukkitTask? = null

    fun start() {
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
        // Tridents already stuck in loaded chunks (spawn chunks load before plugins enable).
        // A /joshymc reload drops the old listener without stop(), so clear its followers first.
        for (world in plugin.server.worlds) {
            for (display in world.getEntitiesByClass(ItemDisplay::class.java)) {
                if (VISUAL_TAG in display.scoreboardTags) display.remove()
            }
            for (trident in world.getEntitiesByClass(Trident::class.java)) {
                if (customId(trident) in CUSTOM_TRIDENTS) attach(trident)
            }
        }
    }

    fun stop() {
        task?.cancel()
        for ((tridentId, displayId) in visuals) {
            plugin.server.getEntity(displayId)?.remove()
            plugin.server.getEntity(tridentId)?.isVisibleByDefault = true
        }
        visuals.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLaunch(event: ProjectileLaunchEvent) {
        val trident = event.entity as? Trident ?: return
        if (customId(trident) in CUSTOM_TRIDENTS) attach(trident)
    }

    /** Tridents stuck in a chunk that reloads get their display back. */
    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        for (entity in event.entities) {
            if (VISUAL_TAG in entity.scoreboardTags) {
                entity.remove()
                continue
            }
            val trident = entity as? Trident ?: continue
            if (trident.uniqueId !in visuals && customId(trident) in CUSTOM_TRIDENTS) attach(trident)
        }
    }

    private fun customId(trident: Trident): String? = plugin.itemManager.getCustomItemId(trident.itemStack)

    private fun attach(trident: Trident) {
        val tip = CUSTOM_TRIDENTS[customId(trident)] ?: return
        // Never send the stock trident model to clients; the display stands in for it.
        trident.isVisibleByDefault = false
        val display = trident.world.spawn(trident.location, ItemDisplay::class.java) { d ->
            d.setItemStack(trident.itemStack.clone())
            d.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            d.isPersistent = false
            d.addScoreboardTag(VISUAL_TAG)
            // Smooth the per-tick moves between server ticks.
            d.teleportDuration = 1
            // The model is built upright (prongs +Y); turn it so the prongs lead along the
            // display's facing (+Z), which tick() points along the flight path, and pull it
            // back so the tips sit just ahead of the trident's position like the vanilla one.
            d.transformation = Transformation(
                Vector3f(0f, 0f, EMBED - tip * SCALE),
                Quaternionf().rotateX(Math.toRadians(90.0).toFloat()),
                Vector3f(SCALE, SCALE, SCALE),
                Quaternionf(),
            )
            val (yaw, pitch) = facing(trident) ?: (trident.location.yaw to 0f)
            d.setRotation(yaw, pitch)
        }
        visuals[trident.uniqueId] = display.uniqueId
    }

    private fun tick() {
        val iterator = visuals.entries.iterator()
        while (iterator.hasNext()) {
            val (tridentId, displayId) = iterator.next()
            val trident = plugin.server.getEntity(tridentId) as? Trident
            val display = plugin.server.getEntity(displayId) as? ItemDisplay
            if (trident == null || !trident.isValid || display == null || !display.isValid) {
                display?.remove()
                iterator.remove()
                continue
            }
            val target = trident.location
            // Keep the last heading once it sticks in a block (velocity drops to zero).
            val (yaw, pitch) = facing(trident) ?: (display.location.yaw to display.location.pitch)
            target.yaw = yaw
            target.pitch = pitch
            display.teleport(target)
        }
    }

    /** Yaw and pitch (entity convention) pointing along the trident's velocity. */
    private fun facing(trident: Trident): Pair<Float, Float>? {
        val v = trident.velocity
        val length = v.length()
        if (length < 0.01) return null
        val yaw = Math.toDegrees(atan2(-v.x, v.z)).toFloat()
        val pitch = Math.toDegrees(asin(-v.y / length)).toFloat()
        return yaw to pitch
    }
}
