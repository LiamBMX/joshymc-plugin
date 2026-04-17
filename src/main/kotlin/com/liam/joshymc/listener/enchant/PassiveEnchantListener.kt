package com.liam.joshymc.listener.enchant

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Particle.DustOptions
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

class PassiveEnchantListener(private val plugin: Joshymc) : Listener {

    private var tickTask: BukkitTask? = null
    private val xrayTasks = mutableMapOf<UUID, BukkitTask>()
    private val overloadPlayers = mutableSetOf<UUID>()

    // Negative effects that clarity can shorten (but not fully block)
    private val clarityReduceEffects = setOf(
        PotionEffectType.POISON,
        PotionEffectType.WITHER,
        PotionEffectType.WEAKNESS,
        PotionEffectType.SLOWNESS,
        PotionEffectType.MINING_FATIGUE,
        PotionEffectType.HUNGER,
        PotionEffectType.LEVITATION,
        PotionEffectType.BAD_OMEN
    )

    // Negative effects that clarity fully blocks
    private val clarityBlockEffects = setOf(
        PotionEffectType.BLINDNESS,
        PotionEffectType.NAUSEA,
        PotionEffectType.DARKNESS
    )

    private val oreColors = mapOf(
        Material.DIAMOND_ORE to Color.fromRGB(0, 200, 255),
        Material.DEEPSLATE_DIAMOND_ORE to Color.fromRGB(0, 200, 255),
        Material.GOLD_ORE to Color.fromRGB(255, 215, 0),
        Material.DEEPSLATE_GOLD_ORE to Color.fromRGB(255, 215, 0),
        Material.IRON_ORE to Color.fromRGB(210, 180, 140),
        Material.DEEPSLATE_IRON_ORE to Color.fromRGB(210, 180, 140),
        Material.EMERALD_ORE to Color.fromRGB(0, 200, 0),
        Material.DEEPSLATE_EMERALD_ORE to Color.fromRGB(0, 200, 0),
        Material.LAPIS_ORE to Color.fromRGB(30, 30, 200),
        Material.DEEPSLATE_LAPIS_ORE to Color.fromRGB(30, 30, 200),
        Material.REDSTONE_ORE to Color.fromRGB(255, 0, 0),
        Material.DEEPSLATE_REDSTONE_ORE to Color.fromRGB(255, 0, 0),
        Material.COPPER_ORE to Color.fromRGB(200, 120, 50),
        Material.DEEPSLATE_COPPER_ORE to Color.fromRGB(200, 120, 50),
        Material.COAL_ORE to Color.fromRGB(50, 50, 50),
        Material.DEEPSLATE_COAL_ORE to Color.fromRGB(50, 50, 50),
        Material.ANCIENT_DEBRIS to Color.fromRGB(100, 60, 40),
        Material.NETHER_GOLD_ORE to Color.fromRGB(255, 215, 0),
        Material.NETHER_QUARTZ_ORE to Color.fromRGB(255, 255, 255)
    )

    private val defaultOreColor = Color.fromRGB(255, 255, 255)

    fun start() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                handleNightVision(player)
                handleOverload(player)
                handleGears(player)
                handleSprings(player)
            }
        }, 0L, 20L)
    }

    fun stop() {
        tickTask?.cancel()
        tickTask = null

        // Cancel all xray tasks and remove glowing entities
        xrayTasks.values.forEach { it.cancel() }
        xrayTasks.clear()
        for ((_, entities) in activeXrayEntities) {
            for (entity in entities) { if (entity.isValid) entity.remove() }
        }
        activeXrayEntities.clear()

        // Reset overloaded players' max health
        for (uuid in overloadPlayers) {
            Bukkit.getPlayer(uuid)?.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 20.0
        }
        overloadPlayers.clear()
    }

    @EventHandler
    fun onPlayerQuitXray(event: org.bukkit.event.player.PlayerQuitEvent) {
        xrayTasks.remove(event.player.uniqueId)?.cancel()
        clearXrayEntities(event.player)
    }

    // ── Night Vision (helmet) ──────────────────────────────────────────

    private fun handleNightVision(player: Player) {
        val helmet = player.inventory.helmet
        if (helmet != null && plugin.customEnchantManager.hasEnchant(helmet, "night_vision")) {
            player.addPotionEffect(
                PotionEffect(PotionEffectType.NIGHT_VISION, 400, 0, true, false, false)
            )
        } else {
            val effect = player.getPotionEffect(PotionEffectType.NIGHT_VISION)
            if (effect != null && effect.duration <= 300 && effect.isAmbient) {
                player.removePotionEffect(PotionEffectType.NIGHT_VISION)
            }
        }
    }

    // ── Clarity (helmet) ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPotionEffect(event: EntityPotionEffectEvent) {
        val player = event.entity as? Player ?: return
        if (event.action != EntityPotionEffectEvent.Action.ADDED &&
            event.action != EntityPotionEffectEvent.Action.CHANGED
        ) return

        val newEffect = event.newEffect ?: return
        val helmet = player.inventory.helmet ?: return
        val level = plugin.customEnchantManager.getLevel(helmet, "clarity")
        if (level <= 0) return

        val effectType = newEffect.type

        // Fully block blindness, nausea, darkness
        if (effectType in clarityBlockEffects) {
            event.isCancelled = true
            return
        }

        // Reduce duration of other negative effects by 20% per level
        if (effectType in clarityReduceEffects) {
            val reductionFactor = (1.0 - (level * 0.2)).coerceAtLeast(0.2) // min 20% duration remains
            val reducedDuration = (newEffect.duration * reductionFactor).toInt().coerceAtLeast(20) // min 1 second

            if (reducedDuration < newEffect.duration) {
                event.isCancelled = true
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    player.addPotionEffect(
                        PotionEffect(
                            effectType,
                            reducedDuration,
                            newEffect.amplifier,
                            newEffect.isAmbient,
                            newEffect.hasParticles()
                        )
                    )
                }, 1L)
            }
        }
    }

    // ── X-Ray (helmet) ─────────────────────────────────────────────────

    @EventHandler
    fun onToggleSneak(event: PlayerToggleSneakEvent) {
        val player = event.player
        val uuid = player.uniqueId

        if (event.isSneaking) {
            val helmet = player.inventory.helmet ?: return
            val xrayLevel = plugin.customEnchantManager.getLevel(helmet, "xray")
            if (xrayLevel <= 0) return

            // Already scanning — don't double up
            if (xrayTasks.containsKey(uuid)) return

            val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                scanOres(player, xrayLevel)
            }, 0L, 10L)
            xrayTasks[uuid] = task
        } else {
            xrayTasks.remove(uuid)?.cancel()
            clearXrayEntities(player)
        }
    }

    /** Active glowing shulkers per player — cleaned up when sneaking stops */
    private val activeXrayEntities = mutableMapOf<java.util.UUID, MutableList<org.bukkit.entity.Shulker>>()

    /** Map ore type to scoreboard team color */
    private val oreTeamColors = mapOf(
        Material.DIAMOND_ORE to net.kyori.adventure.text.format.NamedTextColor.AQUA,
        Material.DEEPSLATE_DIAMOND_ORE to net.kyori.adventure.text.format.NamedTextColor.AQUA,
        Material.GOLD_ORE to net.kyori.adventure.text.format.NamedTextColor.GOLD,
        Material.DEEPSLATE_GOLD_ORE to net.kyori.adventure.text.format.NamedTextColor.GOLD,
        Material.NETHER_GOLD_ORE to net.kyori.adventure.text.format.NamedTextColor.GOLD,
        Material.IRON_ORE to net.kyori.adventure.text.format.NamedTextColor.WHITE,
        Material.DEEPSLATE_IRON_ORE to net.kyori.adventure.text.format.NamedTextColor.WHITE,
        Material.EMERALD_ORE to net.kyori.adventure.text.format.NamedTextColor.GREEN,
        Material.DEEPSLATE_EMERALD_ORE to net.kyori.adventure.text.format.NamedTextColor.GREEN,
        Material.LAPIS_ORE to net.kyori.adventure.text.format.NamedTextColor.BLUE,
        Material.DEEPSLATE_LAPIS_ORE to net.kyori.adventure.text.format.NamedTextColor.BLUE,
        Material.REDSTONE_ORE to net.kyori.adventure.text.format.NamedTextColor.RED,
        Material.DEEPSLATE_REDSTONE_ORE to net.kyori.adventure.text.format.NamedTextColor.RED,
        Material.COPPER_ORE to net.kyori.adventure.text.format.NamedTextColor.GOLD,
        Material.DEEPSLATE_COPPER_ORE to net.kyori.adventure.text.format.NamedTextColor.GOLD,
        Material.COAL_ORE to net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY,
        Material.DEEPSLATE_COAL_ORE to net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY,
        Material.ANCIENT_DEBRIS to net.kyori.adventure.text.format.NamedTextColor.DARK_RED,
        Material.NETHER_QUARTZ_ORE to net.kyori.adventure.text.format.NamedTextColor.WHITE
    )

    private fun scanOres(player: Player, level: Int = 1) {
        // Remove old glowing entities first
        clearXrayEntities(player)

        val center = player.location
        val world = center.world
        val radius = 3 + (level * 3).coerceAtMost(17)
        val board = player.scoreboard

        val bx = center.blockX
        val by = center.blockY
        val bz = center.blockZ

        val shulkers = mutableListOf<org.bukkit.entity.Shulker>()

        for (x in (bx - radius)..(bx + radius)) {
            for (y in (by - radius)..(by + radius)) {
                for (z in (bz - radius)..(bz + radius)) {
                    val block = world.getBlockAt(x, y, z)
                    val type = block.type

                    if (type.name.contains("_ORE") || type == Material.ANCIENT_DEBRIS) {
                        val teamColor = oreTeamColors[type] ?: net.kyori.adventure.text.format.NamedTextColor.WHITE

                        // Spawn invisible glowing shulker at the ore block
                        val shulker = world.spawn(block.location.add(0.5, 0.0, 0.5), org.bukkit.entity.Shulker::class.java) { s ->
                            s.isInvisible = true
                            s.isSilent = true
                            s.isAware = false
                            s.setAI(false)
                            s.setGravity(false)
                            s.isInvulnerable = true
                            s.isGlowing = true
                            s.isPersistent = false
                            s.addScoreboardTag("joshymc_xray")
                        }

                        // Set glow color via scoreboard team on the player's scoreboard
                        val teamName = "xray_${teamColor.toString().lowercase()}"
                        var team = board.getTeam(teamName)
                        if (team == null) {
                            team = board.registerNewTeam(teamName)
                            team.color(teamColor)
                        }
                        team.addEntity(shulker)

                        // Only the scanning player should see the shulker
                        // Hide from everyone else
                        for (other in Bukkit.getOnlinePlayers()) {
                            if (other != player) {
                                other.hideEntity(plugin, shulker)
                            }
                        }

                        shulkers.add(shulker)
                    }
                }
            }
        }

        activeXrayEntities[player.uniqueId] = shulkers
    }

    private fun clearXrayEntities(player: Player) {
        val entities = activeXrayEntities.remove(player.uniqueId) ?: return
        for (entity in entities) {
            if (entity.isValid) entity.remove()
        }
    }

    // ── Overload (chestplate) ──────────────────────────────────────────

    private fun handleOverload(player: Player) {
        val chestplate = player.inventory.chestplate
        val uuid = player.uniqueId

        if (chestplate != null) {
            val level = plugin.customEnchantManager.getLevel(chestplate, "overload")
            if (level > 0) {
                val newMax = 20.0 + (level * 4.0)
                val attribute = player.getAttribute(Attribute.MAX_HEALTH) ?: return
                if (attribute.baseValue != newMax) {
                    attribute.baseValue = newMax
                }
                overloadPlayers.add(uuid)
                return
            }
        }

        // Not wearing overload — clean up if needed
        if (uuid in overloadPlayers) {
            val attribute = player.getAttribute(Attribute.MAX_HEALTH) ?: return
            attribute.baseValue = 20.0
            if (player.health > 20.0) player.health = 20.0
            overloadPlayers.remove(uuid)
        }
    }

    // ── Gears (boots) ──────────────────────────────────────────────────

    private fun handleGears(player: Player) {
        val boots = player.inventory.boots

        if (boots != null) {
            val level = plugin.customEnchantManager.getLevel(boots, "gears")
            if (level > 0) {
                player.addPotionEffect(
                    PotionEffect(PotionEffectType.SPEED, 100, level - 1, true, false)
                )
                return
            }
        }

        val effect = player.getPotionEffect(PotionEffectType.SPEED)
        if (effect != null && effect.isAmbient) {
            player.removePotionEffect(PotionEffectType.SPEED)
        }
    }

    // ── Springs (boots) ────────────────────────────────────────────────

    private fun handleSprings(player: Player) {
        val boots = player.inventory.boots

        if (boots != null) {
            val level = plugin.customEnchantManager.getLevel(boots, "springs")
            if (level > 0) {
                player.addPotionEffect(
                    PotionEffect(PotionEffectType.JUMP_BOOST, 100, level - 1, true, false)
                )
                return
            }
        }

        val effect = player.getPotionEffect(PotionEffectType.JUMP_BOOST)
        if (effect != null && effect.isAmbient) {
            player.removePotionEffect(PotionEffectType.JUMP_BOOST)
        }
    }

    // ── Cleanup on quit ────────────────────────────────────────────────

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId

        // Cancel xray task
        xrayTasks.remove(uuid)?.cancel()

        // Reset overload
        if (uuid in overloadPlayers) {
            val attribute = event.player.getAttribute(Attribute.MAX_HEALTH)
            attribute?.baseValue = 20.0
            overloadPlayers.remove(uuid)
        }
    }
}
