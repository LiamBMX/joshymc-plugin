package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Wither
import org.bukkit.entity.WitherSkull
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import kotlin.random.Random

class MutationsManager(private val plugin: Joshymc) : Listener {

    enum class MutationEvent(val displayName: String) {
        CATALYST("Catalyst")
    }

    private val catalystKey = NamespacedKey(plugin, "catalyst_mutation")

    private var activeEvent: MutationEvent? = null
    private var endTask: BukkitTask? = null

    // Blocks that trigger the Catalyst mutation on their emerald ore drops
    private val EMERALD_ORE_BLOCKS = setOf(Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE)

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        plugin.logger.info("[Mutations] Started")
    }

    fun stop() {
        stopEvent(announce = false)
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun isEventActive(): Boolean = activeEvent != null
    fun getActiveEvent(): MutationEvent? = activeEvent

    fun startEvent(event: MutationEvent, durationSeconds: Int) {
        if (activeEvent != null) stopEvent(announce = false)
        activeEvent = event

        val timeText = formatDuration(durationSeconds)
        broadcast(
            Component.text("[Mutations] ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text("${event.displayName} Event", NamedTextColor.GOLD)
                    .decoration(TextDecoration.BOLD, true))
                .append(Component.text(" has started! ($timeText)", NamedTextColor.YELLOW))
        )

        endTask = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            stopEvent(announce = true)
        }, durationSeconds * 20L)
    }

    fun stopEvent(announce: Boolean) {
        val event = activeEvent ?: return
        activeEvent = null
        endTask?.cancel()
        endTask = null

        if (announce) {
            broadcast(
                Component.text("[Mutations] ", NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text("${event.displayName} Event", NamedTextColor.GOLD))
                    .append(Component.text(" has ended.", NamedTextColor.GRAY))
            )
        }
    }

    fun hasCatalyst(stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return false
        return meta.persistentDataContainer.has(catalystKey, PersistentDataType.BYTE)
    }

    /** Returns 1.5 if the item has the Catalyst mutation, otherwise 1.0. */
    fun getMutationMultiplier(stack: ItemStack): Double = if (hasCatalyst(stack)) 1.5 else 1.0

    // ── Internal ──────────────────────────────────────────────────────────

    private fun applyMutation(stack: ItemStack) {
        val meta = stack.itemMeta ?: return
        meta.persistentDataContainer.set(catalystKey, PersistentDataType.BYTE, 1)
        val lore = (meta.lore() ?: mutableListOf()).toMutableList()
        if (lore.isNotEmpty()) lore.add(Component.empty())
        lore.add(
            Component.text("✶ Catalyst", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false)
        )
        lore.add(
            Component.text("  +50% sell value", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        )
        meta.lore(lore)
        stack.itemMeta = meta
    }

    private fun broadcast(msg: Component) {
        Bukkit.getOnlinePlayers().forEach { plugin.commsManager.send(it, msg) }
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    // ── Listeners ─────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockDrop(event: BlockDropItemEvent) {
        if (activeEvent != MutationEvent.CATALYST) return
        val blockType = event.blockState.type

        val chance = when (blockType) {
            in EMERALD_ORE_BLOCKS -> 0.01   // 1% for emerald ore
            Material.BEETROOTS -> 0.005      // 0.5% for beetroot
            Material.WITHER_ROSE -> 0.005    // 0.5% for wither rose (block break)
            else -> return
        }

        for (item in event.items) {
            val stack = item.itemStack
            if (stack.type == Material.AIR) continue
            if (Random.nextDouble() < chance) {
                val mutated = stack.clone()
                applyMutation(mutated)
                item.itemStack = mutated
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        if (activeEvent != MutationEvent.CATALYST) return

        // Wither roses drop when an entity is slain by a wither or wither skull
        val cause = event.entity.lastDamageCause
        val isWitherKill = cause is EntityDamageByEntityEvent &&
            (cause.damager is Wither || cause.damager is WitherSkull)
        if (!isWitherKill) return

        val toAdd = mutableListOf<ItemStack>()
        val iter = event.drops.iterator()
        while (iter.hasNext()) {
            val drop = iter.next()
            if (drop.type == Material.WITHER_ROSE && Random.nextDouble() < 0.005) {
                iter.remove()
                val mutated = drop.clone()
                applyMutation(mutated)
                toAdd.add(mutated)
            }
        }
        event.drops.addAll(toAdd)
    }
}
