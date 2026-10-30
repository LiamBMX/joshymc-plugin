package com.liam.joshymc.gamemode

import com.liam.joshymc.Joshymc
import org.bukkit.World
import org.bukkit.event.Event
import org.bukkit.event.EventException
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockEvent
import org.bukkit.event.entity.EntityEvent
import org.bukkit.event.hanging.HangingEvent
import org.bukkit.event.inventory.InventoryEvent
import org.bukkit.event.player.PlayerEvent
import org.bukkit.event.vehicle.VehicleEvent
import org.bukkit.event.world.WorldEvent
import org.bukkit.plugin.EventExecutor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Registers a gameplay listener under a [GamemodeFeature] (issue #1329): each of its
 * `@EventHandler`s runs only for events in a world where the feature is allowed
 * (`gamemodeLockManager.isAllowed(feature, world)`). While nothing is locked, or on the feature's
 * own gamemode, every handler runs exactly as with `registerEvents`.
 *
 * Only for listeners that apply effects. Cleanup events ([GamemodeEventGate.ALWAYS_RUN]) and events
 * with no world always run, so per-player state is still cleared.
 */
object GamemodeScopedListeners {

    fun register(plugin: Joshymc, listener: Listener, feature: GamemodeFeature) {
        val pm = plugin.server.pluginManager
        for (method in handlerMethods(listener.javaClass)) {
            val annotation = method.getAnnotation(EventHandler::class.java) ?: continue
            @Suppress("UNCHECKED_CAST")
            val eventClass = method.parameterTypes[0] as Class<out Event>
            method.isAccessible = true
            val executor = EventExecutor { l, event ->
                if (!eventClass.isInstance(event)) return@EventExecutor
                if (!plugin.gamemodeLockManager.anyLocked || allowed(plugin, feature, event)) {
                    try {
                        method.invoke(l, event)
                    } catch (e: InvocationTargetException) {
                        throw EventException(e.cause ?: e)
                    }
                }
            }
            pm.registerEvent(eventClass, listener, annotation.priority, executor, plugin, annotation.ignoreCancelled)
        }
    }

    private fun allowed(plugin: Joshymc, feature: GamemodeFeature, event: Event): Boolean {
        if (GamemodeEventGate.alwaysRuns(event.javaClass)) return true
        val world = worldOf(event) ?: return true
        return plugin.gamemodeLockManager.isAllowed(feature, world)
    }

    /** The world an event happens in, or null when it has none we can tell. */
    fun worldOf(event: Event): World? = when (event) {
        is PlayerEvent -> event.player.world
        is EntityEvent -> event.entity.world
        is BlockEvent -> event.block.world
        is InventoryEvent -> event.view.player.world
        is HangingEvent -> event.entity.world
        is VehicleEvent -> event.vehicle.world
        is WorldEvent -> event.world
        else -> null
    }

    /** Same discovery as Bukkit's registerEvents: public (also inherited) and declared methods. */
    private fun handlerMethods(type: Class<*>): Collection<Method> {
        val methods = LinkedHashSet<Method>()
        methods.addAll(type.methods)
        methods.addAll(type.declaredMethods)
        return methods.filter {
            !it.isBridge && !it.isSynthetic && it.parameterCount == 1 &&
                Event::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.isAnnotationPresent(EventHandler::class.java)
        }
    }
}

/** Which events a scoped listener always receives, Bukkit-free (class names). */
object GamemodeEventGate {

    /** Join/quit/kick/world-change handlers clear or rebuild per-player state, so they always run. */
    val ALWAYS_RUN: Set<String> = setOf(
        "org.bukkit.event.player.PlayerJoinEvent",
        "org.bukkit.event.player.PlayerQuitEvent",
        "org.bukkit.event.player.PlayerKickEvent",
        "org.bukkit.event.player.PlayerChangedWorldEvent",
    )

    fun alwaysRuns(type: Class<*>): Boolean = alwaysRuns(generateSequence(type) { it.superclass }.map { it.name })

    /** [hierarchy] = the event's class name followed by its superclasses' names. */
    fun alwaysRuns(hierarchy: Sequence<String>): Boolean = hierarchy.any { it in ALWAYS_RUN }
}
