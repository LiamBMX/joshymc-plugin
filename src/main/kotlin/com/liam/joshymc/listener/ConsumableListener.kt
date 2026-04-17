package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.BlockUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.event.block.Action
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Snowball
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ConsumableListener(private val plugin: Joshymc) : Listener {

    private val cooldowns = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>>()

    private fun isOnCooldown(player: Player, key: String, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val playerCooldowns = cooldowns.getOrPut(player.uniqueId) { ConcurrentHashMap() }
        val last = playerCooldowns[key] ?: 0L
        if (now - last < cooldownMs) {
            val remaining = ((cooldownMs - (now - last)) / 1000.0).let { "%.1f".format(it) }
            player.sendMessage(
                Component.text("Cooldown: ", NamedTextColor.RED)
                    .append(Component.text("${remaining}s remaining", NamedTextColor.GRAY))
            )
            return true
        }
        playerCooldowns[key] = now
        return false
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val action = event.action
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        val item = player.inventory.itemInMainHand
        if (item.type.isAir) return

        val im = plugin.itemManager

        // ── Consumables ──────────────────────────────────
        when {
            im.isCustomItem(item, "money_pouch_small") -> {
                consumeItem(player)
                plugin.economyManager.deposit(player.uniqueId, 1_000.0)
                player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
                player.sendMessage(
                    Component.text("You opened a ", NamedTextColor.GREEN)
                        .append(Component.text("Small Money Pouch", TextColor.color(0x55FF55)))
                        .append(Component.text(" and received ", NamedTextColor.GREEN))
                        .append(Component.text("\$1,000", NamedTextColor.GOLD))
                        .append(Component.text("!", NamedTextColor.GREEN))
                )
                event.isCancelled = true
            }

            im.isCustomItem(item, "money_pouch_medium") -> {
                consumeItem(player)
                plugin.economyManager.deposit(player.uniqueId, 10_000.0)
                player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
                player.sendMessage(
                    Component.text("You opened a ", NamedTextColor.GREEN)
                        .append(Component.text("Medium Money Pouch", TextColor.color(0xFFFF55)))
                        .append(Component.text(" and received ", NamedTextColor.GREEN))
                        .append(Component.text("\$10,000", NamedTextColor.GOLD))
                        .append(Component.text("!", NamedTextColor.GREEN))
                )
                event.isCancelled = true
            }

            im.isCustomItem(item, "money_pouch_large") -> {
                consumeItem(player)
                plugin.economyManager.deposit(player.uniqueId, 100_000.0)
                player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f)
                player.sendMessage(
                    Component.text("You opened a ", NamedTextColor.GREEN)
                        .append(Component.text("Large Money Pouch", TextColor.color(0xFFAA00)))
                        .append(Component.text(" and received ", NamedTextColor.GREEN))
                        .append(Component.text("\$100,000", NamedTextColor.GOLD))
                        .append(Component.text("!", NamedTextColor.GREEN))
                )
                event.isCancelled = true
            }

            im.isCustomItem(item, "xp_tome") -> {
                consumeItem(player)
                player.giveExpLevels(30)
                player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
                player.sendMessage(
                    Component.text("You used an ", NamedTextColor.GREEN)
                        .append(Component.text("XP Tome", TextColor.color(0x55FF55)))
                        .append(Component.text(" and received ", NamedTextColor.GREEN))
                        .append(Component.text("30 levels", NamedTextColor.GOLD))
                        .append(Component.text("!", NamedTextColor.GREEN))
                )
                event.isCancelled = true
            }

            im.isCustomItem(item, "speed_apple") -> {
                consumeItem(player)
                player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 2400, 2, false, true, true))
                player.playSound(player.location, Sound.ENTITY_PLAYER_BURP, 1f, 1f)
                player.sendMessage(
                    Component.text("You ate a ", NamedTextColor.GREEN)
                        .append(Component.text("Speed Apple", TextColor.color(0x55FFFF)))
                        .append(Component.text("! Speed III for 2 minutes.", NamedTextColor.GREEN))
                )
                event.isCancelled = true
            }

            im.isCustomItem(item, "strength_apple") -> {
                consumeItem(player)
                player.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, 1200, 1, false, true, true))
                player.playSound(player.location, Sound.ENTITY_PLAYER_BURP, 1f, 0.8f)
                player.sendMessage(
                    Component.text("You ate a ", NamedTextColor.GREEN)
                        .append(Component.text("Strength Apple", TextColor.color(0xFF5555)))
                        .append(Component.text("! Strength II for 1 minute.", NamedTextColor.GREEN))
                )
                event.isCancelled = true
            }

            im.isCustomItem(item, "giants_brew") -> {
                consumeItem(player)
                player.addPotionEffect(PotionEffect(PotionEffectType.HEALTH_BOOST, 6000, 3, false, true, true))
                // Heal to full after adding extra hearts
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    player.health = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
                }, 1L)
                player.playSound(player.location, Sound.ENTITY_PLAYER_BURP, 1f, 0.6f)
                player.sendMessage(
                    Component.text("You drank a ", NamedTextColor.GREEN)
                        .append(Component.text("Giant's Brew", TextColor.color(0xFFAA00)))
                        .append(Component.text("! +4 hearts for 5 minutes.", NamedTextColor.GREEN))
                )
                event.isCancelled = true
            }

            im.isCustomItem(item, "miners_brew") -> {
                consumeItem(player)
                player.addPotionEffect(PotionEffect(PotionEffectType.HASTE, 12000, 1, false, true, true))
                player.addPotionEffect(PotionEffect(PotionEffectType.NIGHT_VISION, 12000, 0, false, true, true))
                player.playSound(player.location, Sound.ENTITY_PLAYER_BURP, 1f, 1.2f)
                player.sendMessage(
                    Component.text("You drank a ", NamedTextColor.GREEN)
                        .append(Component.text("Miner's Brew", TextColor.color(0x55FFFF)))
                        .append(Component.text("! Haste II + Night Vision for 10 minutes.", NamedTextColor.GREEN))
                )
                event.isCancelled = true
            }

            im.isCustomItem(item, "wardens_heart") -> {
                consumeItem(player)
                player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, 1200, 1, false, true, true))
                player.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, 1200, 1, false, true, true))
                player.playSound(player.location, Sound.ENTITY_WARDEN_ROAR, 1f, 1f)
                player.world.spawnParticle(Particle.SCULK_CHARGE_POP, player.location.add(0.0, 1.0, 0.0), 50, 0.5, 0.5, 0.5, 0.01)
                player.sendMessage(
                    Component.text("You consumed a ", NamedTextColor.GREEN)
                        .append(Component.text("Warden's Heart", TextColor.color(0xAA0000)))
                        .append(Component.text("! Resistance II + Strength II for 60 seconds.", NamedTextColor.GREEN))
                )
                event.isCancelled = true
            }

            // ── Carrot Launcher ──────────────────────────
            im.isCustomItem(item, "carrot_launcher") -> {
                if (isOnCooldown(player, "carrot_launcher", 3000L)) {
                    event.isCancelled = true
                    return
                }
                val snowball = player.launchProjectile(Snowball::class.java)
                snowball.velocity = player.location.direction.multiply(2.0)
                snowball.addScoreboardTag("carrot_projectile")
                player.playSound(player.location, Sound.ENTITY_SNOWBALL_THROW, 1f, 0.5f)
                event.isCancelled = true
            }

            // ── Magnet Wand ──────────────────────────────
            im.isCustomItem(item, "magnet_wand") -> {
                if (isOnCooldown(player, "magnet_wand", 5000L)) {
                    event.isCancelled = true
                    return
                }
                val items = player.world.getNearbyEntities(player.location, 10.0, 10.0, 10.0)
                    .filterIsInstance<org.bukkit.entity.Item>()
                var pulled = 0
                for (droppedItem in items) {
                    droppedItem.teleport(player.location)
                    pulled++
                }
                player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1f, 1f)
                player.sendMessage(
                    Component.text("Pulled ", NamedTextColor.GREEN)
                        .append(Component.text("$pulled items", NamedTextColor.GOLD))
                        .append(Component.text(" to you!", NamedTextColor.GREEN))
                )
                event.isCancelled = true
            }

            // ── Auto Miner ──────────────────────────────
            im.isCustomItem(item, "auto_miner") -> {
                if (isOnCooldown(player, "auto_miner", 30_000L)) {
                    event.isCancelled = true
                    return
                }
                val clickedBlock = event.clickedBlock
                if (clickedBlock == null) {
                    event.isCancelled = true
                    return
                }
                val direction = getCardinalDirection(player)
                startAutoMine(player, clickedBlock, direction)
                player.sendMessage(
                    Component.text("Auto Miner activated! Mining 10 blocks forward...", NamedTextColor.GREEN)
                )
                event.isCancelled = true
            }

            // ── Farmer's Sickle ──────────────────────────
            im.isCustomItem(item, "farmers_sickle") -> {
                val clickedBlock = event.clickedBlock ?: return
                if (clickedBlock.blockData !is Ageable) return

                if (isOnCooldown(player, "farmers_sickle", 5000L)) {
                    event.isCancelled = true
                    return
                }

                var harvested = 0
                val center = clickedBlock.location

                for (dx in -2..2) {
                    for (dz in -2..2) {
                        val block = clickedBlock.world.getBlockAt(
                            center.blockX + dx,
                            center.blockY,
                            center.blockZ + dz
                        )
                        val data = block.blockData
                        if (data is Ageable && data.age == data.maximumAge) {
                            // Drop the crop items
                            val drops = block.getDrops(item)
                            for (drop in drops) {
                                block.world.dropItemNaturally(block.location, drop)
                            }
                            // Replant: reset age to 0
                            data.age = 0
                            block.blockData = data
                            harvested++
                        }
                    }
                }

                player.playSound(player.location, Sound.ITEM_HOE_TILL, 1f, 1f)
                player.sendMessage(
                    Component.text("Harvested and replanted ", NamedTextColor.GREEN)
                        .append(Component.text("$harvested crops", NamedTextColor.GOLD))
                        .append(Component.text("!", NamedTextColor.GREEN))
                )
                event.isCancelled = true
            }
        }
    }

    @EventHandler
    fun onProjectileHit(event: ProjectileHitEvent) {
        val projectile = event.entity
        if (!projectile.scoreboardTags.contains("carrot_projectile")) return

        val loc = projectile.location
        loc.world.spawnParticle(Particle.EXPLOSION, loc, 3, 0.2, 0.2, 0.2, 0.0)
        loc.world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f)

        loc.world.getNearbyEntities(loc, 3.0, 3.0, 3.0)
            .filterIsInstance<LivingEntity>()
            .filter { it != projectile.shooter }
            .forEach { entity ->
                entity.damage(6.0, projectile.shooter as? Player)
            }

        projectile.remove()
    }

    /**
     * Consumes one item from the player's main hand.
     */
    private fun consumeItem(player: Player) {
        val item = player.inventory.itemInMainHand
        if (item.amount > 1) {
            item.amount -= 1
        } else {
            player.inventory.setItemInMainHand(null)
        }
    }

    /**
     * Gets the cardinal block face direction the player is looking.
     */
    private fun getCardinalDirection(player: Player): BlockFace {
        val yaw = player.location.yaw
        val pitch = player.location.pitch

        // If looking steeply up or down, use UP/DOWN
        if (pitch < -45) return BlockFace.UP
        if (pitch > 45) return BlockFace.DOWN

        val adjusted = ((yaw % 360) + 360) % 360
        return when {
            adjusted < 45 -> BlockFace.SOUTH
            adjusted < 135 -> BlockFace.WEST
            adjusted < 225 -> BlockFace.NORTH
            adjusted < 315 -> BlockFace.EAST
            else -> BlockFace.SOUTH
        }
    }

    /**
     * Starts a BukkitRunnable that mines 1 block per tick in the given direction, 10 blocks deep.
     */
    private fun startAutoMine(player: Player, startBlock: Block, direction: BlockFace) {
        val startLoc = startBlock.getRelative(direction).location

        object : BukkitRunnable() {
            var depth = 0

            override fun run() {
                if (depth >= 10 || !player.isOnline) {
                    cancel()
                    return
                }

                val block = startLoc.world!!.getBlockAt(
                    startLoc.blockX + direction.modX * depth,
                    startLoc.blockY + direction.modY * depth,
                    startLoc.blockZ + direction.modZ * depth
                )

                if (BlockUtil.isMineable(block.type)) {
                    block.breakNaturally(player.inventory.itemInMainHand)
                    block.world.playSound(block.location, Sound.BLOCK_STONE_BREAK, 0.5f, 1f)
                }

                depth++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }
}
