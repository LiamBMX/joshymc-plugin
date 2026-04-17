package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Egg
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.NamespacedKey
import java.util.UUID

class EasterEggListener(private val plugin: Joshymc) : Listener {

    companion object {
        private const val SPLASH_RADIUS = 3.0
    }

    private val prizeEggIds = listOf(
        "explosive_egg", "freeze_egg", "blindness_egg",
        "teleport_egg", "levitation_egg", "knockback_egg", "swap_egg",
        "lightning_egg", "cobweb_egg", "confusion_egg", "ender_egg"
    )

    // Track custom eggs that are in-flight so we can suppress chicken spawns
    private val customEggEntities = mutableSetOf<UUID>()

    private val freezeModifierKey = NamespacedKey(plugin, "freeze_egg_slow")

    // --- Easter Egg: right-click to open and receive a random prize egg ---

    @EventHandler
    fun onEasterEggUse(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        val item = player.inventory.itemInMainHand

        if (!plugin.itemManager.isCustomItem(item, "easter_egg")) return

        event.isCancelled = true

        // Consume one Easter Egg
        if (item.amount > 1) {
            item.amount -= 1
        } else {
            player.inventory.setItemInMainHand(null)
        }

        // Pick a random prize
        val prizeId = prizeEggIds.random()
        val prizeItem = plugin.itemManager.getItem(prizeId) ?: return
        val prizeStack = prizeItem.createItemStack()

        // Give to player
        val leftover = player.inventory.addItem(prizeStack)
        if (leftover.isNotEmpty()) {
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
        }

        // Effects
        val loc = player.location.add(0.0, 1.0, 0.0)
        player.world.spawnParticle(Particle.HAPPY_VILLAGER, loc, 15, 0.5, 0.5, 0.5, 0.1)
        player.world.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f)

        player.sendMessage(
            Component.text("You opened an Easter Egg and got: ", TextColor.color(0xFFD700))
                .append(prizeItem.displayName)
        )
    }

    // --- Track custom egg projectiles to prevent chicken spawns ---

    @EventHandler
    fun onEggLaunch(event: org.bukkit.event.entity.ProjectileLaunchEvent) {
        val projectile = event.entity
        if (projectile !is Egg) return

        val thrownItem = projectile.item
        val itemId = plugin.itemManager.getCustomItemId(thrownItem) ?: return

        if (itemId in prizeEggIds) {
            customEggEntities.add(projectile.uniqueId)
        }
    }

    @EventHandler
    fun onEntitySpawn(event: EntitySpawnEvent) {
        if (event.entity is org.bukkit.entity.Chicken) {
            val loc = event.location
            val nearbyEggs = loc.world.getNearbyEntities(loc, 2.0, 2.0, 2.0)
                .filterIsInstance<Egg>()
                .filter { it.uniqueId in customEggEntities }
            if (nearbyEggs.isNotEmpty()) {
                event.isCancelled = true
            }
        }
    }

    // --- Prize Eggs: splash-style AoE on impact ---

    @EventHandler
    fun onEggHit(event: ProjectileHitEvent) {
        val projectile = event.entity
        if (projectile !is Egg) return

        val thrownItem = projectile.item
        val itemId = plugin.itemManager.getCustomItemId(thrownItem) ?: return

        customEggEntities.remove(projectile.uniqueId)
        event.isCancelled = true

        val impactLoc = projectile.location
        val shooter = projectile.shooter as? Player
        val nearby = impactLoc.world.getNearbyEntities(impactLoc, SPLASH_RADIUS, SPLASH_RADIUS, SPLASH_RADIUS)
            .filterIsInstance<LivingEntity>()
            .filter { it != shooter }

        when (itemId) {
            "explosive_egg" -> {
                impactLoc.world.spawnParticle(Particle.EXPLOSION, impactLoc, 5, 0.5, 0.5, 0.5, 0.01)
                impactLoc.world.playSound(impactLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f)

                for (entity in nearby) {
                    entity.damage(6.0, shooter)
                }
            }

            "freeze_egg" -> {
                impactLoc.world.spawnParticle(Particle.SNOWFLAKE, impactLoc, 40, SPLASH_RADIUS, 1.0, SPLASH_RADIUS, 0.02)
                impactLoc.world.playSound(impactLoc, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.5f)

                for (entity in nearby) {
                    applyFreeze(entity)
                }
            }

            "blindness_egg" -> {
                impactLoc.world.spawnParticle(Particle.SMOKE, impactLoc, 50, SPLASH_RADIUS, 1.0, SPLASH_RADIUS, 0.02)
                impactLoc.world.spawnParticle(Particle.LARGE_SMOKE, impactLoc, 15, SPLASH_RADIUS * 0.5, 0.5, SPLASH_RADIUS * 0.5, 0.01)
                impactLoc.world.playSound(impactLoc, Sound.ENTITY_WITHER_AMBIENT, 0.6f, 1.5f)

                for (entity in nearby) {
                    entity.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 10 * 20, 0)) // 10 seconds
                }
            }

            "teleport_egg" -> {
                val target = nearby.minByOrNull { it.location.distanceSquared(impactLoc) } ?: return
                val shooterLoc = shooter?.location ?: return
                val targetLoc = target.location

                shooter.teleport(targetLoc)
                target.teleport(shooterLoc)

                impactLoc.world.spawnParticle(Particle.PORTAL, shooterLoc, 30, 0.5, 1.0, 0.5, 0.5)
                impactLoc.world.spawnParticle(Particle.PORTAL, targetLoc, 30, 0.5, 1.0, 0.5, 0.5)
                impactLoc.world.playSound(impactLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f)
            }

            "levitation_egg" -> {
                impactLoc.world.spawnParticle(Particle.END_ROD, impactLoc, 30, 0.5, 1.0, 0.5, 0.1)
                impactLoc.world.playSound(impactLoc, Sound.ENTITY_SHULKER_SHOOT, 1.0f, 1.0f)

                for (entity in nearby) {
                    entity.addPotionEffect(PotionEffect(PotionEffectType.LEVITATION, 60, 1)) // 3 seconds, level II
                }
            }

            "knockback_egg" -> {
                impactLoc.world.spawnParticle(Particle.EXPLOSION, impactLoc, 5, 0.5, 0.5, 0.5, 0.01)
                impactLoc.world.playSound(impactLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.5f)

                for (entity in nearby) {
                    val direction = entity.location.toVector().subtract(impactLoc.toVector()).normalize().multiply(3.0)
                    direction.y = 0.8
                    entity.velocity = direction
                }
            }

            "swap_egg" -> {
                impactLoc.world.spawnParticle(Particle.ENCHANT, impactLoc, 30, 0.5, 1.0, 0.5, 0.5)
                impactLoc.world.playSound(impactLoc, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.0f)

                for (entity in nearby) {
                    if (entity !is Player) continue
                    val items = (0..8).map { entity.inventory.getItem(it) }.toMutableList()
                    items.shuffle()
                    for (i in 0..8) {
                        entity.inventory.setItem(i, items[i])
                    }
                }
            }

            "lightning_egg" -> {
                impactLoc.world.strikeLightning(impactLoc)
                impactLoc.world.spawnParticle(Particle.ELECTRIC_SPARK, impactLoc, 30, 0.5, 1.0, 0.5, 0.1)

                // Extinguish fire in 2-block radius and clear fire ticks on entities
                val center = impactLoc.block
                for (x in -2..2) {
                    for (y in -2..2) {
                        for (z in -2..2) {
                            val block = center.getRelative(x, y, z)
                            if (block.type == Material.FIRE || block.type == Material.SOUL_FIRE) {
                                block.type = Material.AIR
                            }
                        }
                    }
                }

                val nearbyAll = impactLoc.world.getNearbyEntities(impactLoc, SPLASH_RADIUS, SPLASH_RADIUS, SPLASH_RADIUS)
                    .filterIsInstance<LivingEntity>()
                for (entity in nearbyAll) {
                    entity.fireTicks = 0
                }
            }

            "cobweb_egg" -> {
                val cobwebLocations = mutableListOf<org.bukkit.block.Block>()
                val center = impactLoc.block

                for (x in -1..1) {
                    for (y in -1..1) {
                        for (z in -1..1) {
                            val block = center.getRelative(x, y, z)
                            if (block.type == Material.AIR) {
                                block.type = Material.COBWEB
                                cobwebLocations.add(block)
                            }
                        }
                    }
                }

                impactLoc.world.spawnParticle(Particle.BLOCK, impactLoc, 20, 0.5, 0.5, 0.5, 0.1, Material.COBWEB.createBlockData())
                impactLoc.world.playSound(impactLoc, Sound.ENTITY_SPIDER_AMBIENT, 1.0f, 1.0f)

                object : BukkitRunnable() {
                    override fun run() {
                        for (block in cobwebLocations) {
                            if (block.type == Material.COBWEB) {
                                block.type = Material.AIR
                            }
                        }
                    }
                }.runTaskLater(plugin, 100L) // 5 seconds
            }

            "confusion_egg" -> {
                impactLoc.world.spawnParticle(Particle.WITCH, impactLoc, 30, 0.5, 1.0, 0.5, 0.1)
                impactLoc.world.playSound(impactLoc, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 1.0f, 1.0f)

                for (entity in nearby) {
                    entity.addPotionEffect(PotionEffect(PotionEffectType.NAUSEA, 160, 1)) // 8 seconds, level II
                }
            }

            "ender_egg" -> {
                val player = shooter ?: return
                val oldLoc = player.location

                player.teleport(impactLoc)

                impactLoc.world.spawnParticle(Particle.PORTAL, oldLoc, 50, 0.5, 1.0, 0.5, 0.5)
                impactLoc.world.spawnParticle(Particle.PORTAL, impactLoc, 50, 0.5, 1.0, 0.5, 0.5)
                impactLoc.world.playSound(oldLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f)
                impactLoc.world.playSound(impactLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f)
            }
        }
    }

    /**
     * Applies freeze: Slowness 6 + no jumping via attribute modifier.
     * No Jump Boost (that was causing the sky-launch bug).
     */
    private fun applyFreeze(entity: LivingEntity) {
        val durationTicks = 5 * 20

        // Heavy slowness
        entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, durationTicks, 5, false, true, true))

        // Kill movement speed via attribute — this actually stops them
        val speedAttr = entity.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        val modifier = AttributeModifier(freezeModifierKey, -0.07, AttributeModifier.Operation.ADD_NUMBER)

        speedAttr.removeModifier(modifier)
        speedAttr.addModifier(modifier)

        // Kill jump height via attribute instead of potion (no sky-launch)
        val jumpAttr = entity.getAttribute(Attribute.JUMP_STRENGTH)
        var jumpModifier: AttributeModifier? = null
        if (jumpAttr != null) {
            val jumpKey = NamespacedKey(plugin, "freeze_egg_jump")
            jumpModifier = AttributeModifier(jumpKey, -0.42, AttributeModifier.Operation.ADD_NUMBER) // default jump is 0.42
            jumpAttr.removeModifier(jumpModifier)
            jumpAttr.addModifier(jumpModifier)
        }

        object : BukkitRunnable() {
            override fun run() {
                speedAttr.removeModifier(modifier)
                if (jumpAttr != null && jumpModifier != null) {
                    jumpAttr.removeModifier(jumpModifier)
                }
            }
        }.runTaskLater(plugin, durationTicks.toLong())
    }
}
