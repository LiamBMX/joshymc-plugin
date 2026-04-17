package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerExpChangeEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

class CustomArmorListener(private val plugin: Joshymc) : Listener {

    private val armorSetCache = mutableMapOf<UUID, String?>()
    private var task: BukkitTask? = null
    private val pdcKey = NamespacedKey(plugin, "custom_item_id")

    private val ARMOR_SET_PREFIXES = setOf("void", "inferno", "crystal", "soul")

    /**
     * Start the repeating task that checks armor and applies set bonuses.
     */
    fun start() {
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            for (player in plugin.server.onlinePlayers) {
                val currentSet = getArmorSetId(player)
                val cachedSet = armorSetCache[player.uniqueId]

                if (currentSet != cachedSet) {
                    // Set changed — remove old effects if needed
                    if (cachedSet != null) {
                        removeSetEffects(player, cachedSet)
                    }
                    armorSetCache[player.uniqueId] = currentSet
                }

                if (currentSet != null) {
                    applySetEffects(player, currentSet)
                }
            }
        }, 0L, 20L) // every 1 second
    }

    /**
     * Stop the repeating task and clear all set bonus effects.
     */
    fun stop() {
        task?.cancel()
        task = null
        for (player in plugin.server.onlinePlayers) {
            val set = armorSetCache[player.uniqueId]
            if (set != null) {
                removeSetEffects(player, set)
            }
        }
        armorSetCache.clear()
    }

    /**
     * Returns the armor set id ("void", "inferno", "crystal", "soul") if the player
     * is wearing a full matching set, or null otherwise.
     */
    fun getArmorSetId(player: Player): String? {
        val equipment = player.inventory
        val slots = listOf(equipment.helmet, equipment.chestplate, equipment.leggings, equipment.boots)

        val ids = slots.map { item ->
            if (item == null) return null
            val meta = item.itemMeta ?: return null
            meta.persistentDataContainer.get(pdcKey, PersistentDataType.STRING) ?: return null
        }

        // Extract the prefix (everything before the last underscore)
        val prefixes = ids.map { id ->
            val lastUnderscore = id.lastIndexOf('_')
            if (lastUnderscore == -1) return null
            id.substring(0, lastUnderscore)
        }

        val prefix = prefixes.first()
        if (prefix !in ARMOR_SET_PREFIXES) return null
        if (!prefixes.all { it == prefix }) return null

        return prefix
    }

    private fun applySetEffects(player: Player, set: String) {
        // Effect duration well above the 20-tick refresh interval to avoid gaps.
        // 200 ticks (10 sec) gives plenty of buffer if a tick is missed.
        val duration = 200

        when (set) {
            "void" -> {
                // Slow falling + Speed II + Jump Boost
                player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, duration, 0, true, false, true), true)
                player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, duration, 1, true, false, true), true)
                player.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, duration, 1, true, false, true), true)
            }
            "inferno" -> {
                // Fire resistance + Strength I
                player.addPotionEffect(PotionEffect(PotionEffectType.FIRE_RESISTANCE, duration, 0, true, false, true), true)
                player.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, duration, 0, true, false, true), true)

                // Lava swimming: if in lava, make it feel like water
                val blockAt = player.location.block
                val blockEye = player.eyeLocation.block
                if (blockAt.type == org.bukkit.Material.LAVA || blockEye.type == org.bukkit.Material.LAVA) {
                    // Swim animation: set swimming state
                    player.isSwimming = true

                    // Move like water: apply direction-based velocity
                    val dir = player.location.direction
                    val vel = player.velocity

                    if (player.isSneaking) {
                        // Sink
                        player.velocity = org.bukkit.util.Vector(dir.x * 0.15, -0.3, dir.z * 0.15)
                    } else {
                        // Swim forward in look direction
                        val speed = 0.25
                        player.velocity = org.bukkit.util.Vector(
                            dir.x * speed,
                            dir.y * speed * 0.5 + 0.02, // slight upward to prevent sinking
                            dir.z * speed
                        )
                    }
                }
            }
            "crystal" -> {
                // Haste I + Luck
                player.addPotionEffect(PotionEffect(PotionEffectType.HASTE, duration, 0, true, false, true), true)
                player.addPotionEffect(PotionEffect(PotionEffectType.LUCK, duration, 1, true, false, true), true)
            }
            "soul" -> {
                // Night Vision + Resistance I
                player.addPotionEffect(PotionEffect(PotionEffectType.NIGHT_VISION, 400, 0, true, false, true), true)
                player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, duration, 0, true, false, true), true)
            }
        }
    }

    private fun removeSetEffects(player: Player, set: String) {
        when (set) {
            "void" -> {
                player.removePotionEffect(PotionEffectType.SLOW_FALLING)
                player.removePotionEffect(PotionEffectType.SPEED)
                player.removePotionEffect(PotionEffectType.JUMP_BOOST)
            }
            "inferno" -> {
                player.removePotionEffect(PotionEffectType.FIRE_RESISTANCE)
                player.removePotionEffect(PotionEffectType.STRENGTH)
            }
            "crystal" -> {
                player.removePotionEffect(PotionEffectType.HASTE)
                player.removePotionEffect(PotionEffectType.LUCK)
            }
            "soul" -> {
                player.removePotionEffect(PotionEffectType.NIGHT_VISION)
                player.removePotionEffect(PotionEffectType.RESISTANCE)
            }
        }
    }

    // ── Event Handlers ──────────────────────────────────────────────────────

    /**
     * Inferno set: cancel lava/fire damage.
     * Soul set: cancel wither damage.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val set = armorSetCache[player.uniqueId] ?: return

        when (set) {
            "inferno" -> {
                if (event.cause == EntityDamageEvent.DamageCause.LAVA
                    || event.cause == EntityDamageEvent.DamageCause.FIRE
                    || event.cause == EntityDamageEvent.DamageCause.FIRE_TICK
                    || event.cause == EntityDamageEvent.DamageCause.HOT_FLOOR
                ) {
                    event.isCancelled = true
                }
            }
            "soul" -> {
                if (event.cause == EntityDamageEvent.DamageCause.WITHER) {
                    event.isCancelled = true
                }
            }
        }
    }

    /**
     * Soul set: 3% lifesteal on melee damage dealt.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val attacker = event.damager as? Player ?: return
        val set = armorSetCache[attacker.uniqueId] ?: return

        if (set == "soul") {
            val heal = event.finalDamage * 0.03
            val newHealth = (attacker.health + heal).coerceAtMost(attacker.maxHealth)
            attacker.health = newHealth
        }
    }

    /**
     * Crystal set: +20% XP from all sources.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerExpChange(event: PlayerExpChangeEvent) {
        val set = armorSetCache[event.player.uniqueId] ?: return

        if (set == "crystal") {
            event.amount = (event.amount * 1.2).toInt()
        }
    }

    /**
     * Clean up cache when a player leaves.
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        armorSetCache.remove(event.player.uniqueId)
    }
}
