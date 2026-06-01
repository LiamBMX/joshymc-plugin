package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.NamespacedKey
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Boss
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.NPC
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.persistence.PersistentDataType

class MobStackManager(private val plugin: Joshymc) : Listener {

    val stackKey = NamespacedKey(plugin, "mob_stack_count")

    private val stackRadius: Double
        get() = plugin.config.getDouble("mob-stacking.radius", 5.0)

    private val maxStackSize: Int
        get() = plugin.config.getInt("mob-stacking.max-stack-size", 50)

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (!plugin.isFeatureEnabled("mob-stacking")) return
        val entity = event.entity
        if (!isStackable(entity)) return

        val r = stackRadius
        val target = entity.location.world
            ?.getNearbyEntities(entity.location, r, r, r)
            ?.filterIsInstance<LivingEntity>()
            ?.filter { it.uniqueId != entity.uniqueId && it.type == entity.type && isStackable(it) }
            ?.maxByOrNull { getCount(it) }
            ?: return

        val newCount = getCount(target) + 1
        if (newCount > maxStackSize) return

        setCount(target, newCount)
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        if (!plugin.isFeatureEnabled("mob-stacking")) return
        val entity = event.entity
        val count = getCount(entity)
        if (count <= 1) return

        val originalDrops = event.drops.map { it.clone() }
        event.drops.clear()
        for (drop in originalDrops) {
            var remaining = drop.amount * count
            while (remaining > 0) {
                val batch = minOf(remaining, drop.maxStackSize)
                val item = drop.clone()
                item.amount = batch
                event.drops.add(item)
                remaining -= batch
            }
        }
        event.droppedExp *= count
    }

    fun getCount(entity: LivingEntity): Int =
        entity.persistentDataContainer.getOrDefault(stackKey, PersistentDataType.INTEGER, 1)

    private fun setCount(entity: LivingEntity, count: Int) {
        entity.persistentDataContainer.set(stackKey, PersistentDataType.INTEGER, count)
        updateNametag(entity, count)
    }

    private fun updateNametag(entity: LivingEntity, count: Int) {
        if (count > 1) {
            val label = entity.type.name.lowercase()
                .replace('_', ' ')
                .split(' ')
                .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }
            entity.customName(
                Component.text(label, NamedTextColor.WHITE)
                    .append(Component.text(" x$count", NamedTextColor.YELLOW))
            )
            entity.isCustomNameVisible = true
        } else {
            entity.customName(null)
            entity.isCustomNameVisible = false
        }
    }

    private fun isStackable(entity: LivingEntity): Boolean {
        if (entity is Player) return false
        if (entity is NPC) return false
        if (entity is ArmorStand) return false
        if (entity is Boss) return false
        if (entity is Tameable && (entity as Tameable).isTamed) return false
        if (entity.scoreboardTags.any { it.startsWith("joshymc") }) return false
        // Don't stack entities with an admin-set custom name (our nametags are tracked via PDC)
        val hasStackKey = entity.persistentDataContainer.has(stackKey, PersistentDataType.INTEGER)
        if (!hasStackKey && entity.customName() != null) return false
        return true
    }
}
