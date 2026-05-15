package com.liam.joshymc.listener.enchant

import com.liam.joshymc.Joshymc
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Arrow
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Trident
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.UUID

class WeaponEnchantListener(private val plugin: Joshymc) : Listener {

    private val cem get() = plugin.customEnchantManager

    // Crossbow arrow tracking: arrow UUID -> data
    private val trackedArrows = mutableMapOf<UUID, ArrowData>()

    // Trident tracking: trident UUID -> data
    private val trackedTridents = mutableMapOf<UUID, TridentData>()

    // Barbed hook DOT tasks keyed by victim UUID
    private val barbedHookTasks = mutableMapOf<UUID, BukkitTask>()

    // Tidal Leap cooldowns (ms timestamp)
    private val tidalLeapCooldowns = mutableMapOf<UUID, Long>()

    // Recursion guard for mace AOE
    private val processingDamage = mutableSetOf<UUID>()

    private data class ArrowData(val shooter: Player, val weapon: ItemStack, val launchLoc: Location)
    private data class TridentData(val shooter: Player, val weapon: ItemStack)

    // ── Crossbow tracking ────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShootBow(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val bow = event.bow ?: return
        if (bow.type != Material.CROSSBOW) return
        val arrow = event.projectile as? Arrow ?: return

        if (!cem.hasEnchant(bow, "shrapnel") && !cem.hasEnchant(bow, "sniper") && !cem.hasEnchant(bow, "hunter")) return

        trackedArrows[arrow.uniqueId] = ArrowData(player, bow.clone(), player.location.clone())
    }

    // ── Trident tracking ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val trident = event.entity as? Trident ?: return
        val player = trident.shooter as? Player ?: return
        val weapon = player.inventory.itemInMainHand.takeIf { it.type == Material.TRIDENT }
            ?: player.inventory.itemInOffHand.takeIf { it.type == Material.TRIDENT }
            ?: return

        if (!cem.hasEnchant(weapon, "harpoon")) return

        trackedTridents[trident.uniqueId] = TridentData(player, weapon.clone())
    }

    // ── Projectile hit ───────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        // Shrapnel — fires for block AND entity hits
        val arrow = event.entity as? Arrow ?: run {
            // Also clean up tridents on block hit (entity hit is handled in EDBE)
            val trident = event.entity as? Trident ?: return
            if (event.hitEntity == null) trackedTridents.remove(trident.uniqueId)
            return
        }

        val data = trackedArrows[arrow.uniqueId] ?: return

        handleShrapnel(data, arrow)

        // Clean up on block hits; entity hits are cleaned up in EDBE after damage mods
        if (event.hitEntity == null) trackedArrows.remove(arrow.uniqueId)
    }

    // ── Main damage handler ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? LivingEntity ?: return

        // Mace enchants
        val attacker = event.damager as? Player
        if (attacker != null && attacker.uniqueId !in processingDamage) {
            val weapon = attacker.inventory.itemInMainHand
            if (weapon.type == Material.MACE && attacker.fallDistance >= 1.5f) {
                handleMeteor(event, attacker, victim, weapon)
                handleCrater(attacker, victim, weapon)
            }
        }

        // Crossbow arrow enchants (sniper + hunter)
        val arrow = event.damager as? Arrow
        if (arrow != null) {
            val data = trackedArrows.remove(arrow.uniqueId)
            if (data != null) {
                handleSniper(event, data, arrow)
                handleHunter(event, data, victim)
            }
        }

        // Trident enchants
        val trident = event.damager as? Trident
        if (trident != null) {
            val data = trackedTridents.remove(trident.uniqueId)
            if (data != null) {
                handleHarpoon(data, victim)
            }
        }
    }

    // ── Fishing rod ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        val player = event.player
        val rod = player.inventory.itemInMainHand.takeIf { it.type == Material.FISHING_ROD }
            ?: player.inventory.itemInOffHand.takeIf { it.type == Material.FISHING_ROD }
            ?: return

        when (event.state) {
            PlayerFishEvent.State.CAUGHT_ENTITY -> handleBarbedHook(event, player, rod)
            PlayerFishEvent.State.CAUGHT_FISH   -> handleNetcaster(event, player, rod)
            else -> {}
        }
    }

    // ── Tidal Leap ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        handleTidalLeap(event)
    }

    // ════════════════════════════════════════════════════════════════════
    //  MACE HANDLERS
    // ════════════════════════════════════════════════════════════════════

    // Meteor: smash radius scales with fall height
    private fun handleMeteor(
        event: EntityDamageByEntityEvent,
        attacker: Player,
        primaryVictim: LivingEntity,
        weapon: ItemStack
    ) {
        if (!cem.hasEnchant(weapon, "meteor")) return

        val fallDist = attacker.fallDistance
        val radius = (2.0 + fallDist * 0.2).coerceAtMost(8.0)

        processingDamage.add(attacker.uniqueId)
        try {
            primaryVictim.getNearbyEntities(radius, radius, radius)
                .filterIsInstance<LivingEntity>()
                .filter { it != attacker && it != primaryVictim }
                .forEach { nearby ->
                    nearby.damage(event.damage * 0.5, attacker)
                }
        } finally {
            processingDamage.remove(attacker.uniqueId)
        }

        // Visual shockwave
        val center = primaryVictim.location
        center.world.spawnParticle(Particle.EXPLOSION, center.add(0.0, 0.5, 0.0), 1)
        center.world.spawnParticle(Particle.CLOUD, center, 12, radius / 2, 0.3, radius / 2, 0.05)
        center.world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.4f)
    }

    // Crater: smash attack stuns nearby enemies
    private fun handleCrater(attacker: Player, primaryVictim: LivingEntity, weapon: ItemStack) {
        val level = cem.getLevel(weapon, "crater")
        if (level <= 0) return

        val radius = 3.0 + level * 0.5
        val stunTicks = 30 + level * 10   // 2s / 2.5s / 3s

        val nearby = primaryVictim.getNearbyEntities(radius, radius, radius)
            .filterIsInstance<LivingEntity>()
            .filter { it != attacker }

        for (entity in nearby) {
            entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, stunTicks, level + 1, false, true, true))
            entity.addPotionEffect(PotionEffect(PotionEffectType.MINING_FATIGUE, stunTicks, level - 1, false, true, true))
        }

        if (nearby.isNotEmpty()) {
            primaryVictim.world.spawnParticle(Particle.DUST_PLUME, primaryVictim.location.add(0.0, 0.3, 0.0), 20, radius / 2, 0.2, radius / 2)
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  FISHING ROD HANDLERS
    // ════════════════════════════════════════════════════════════════════

    // Barbed Hook: hooked entity takes DOT
    private fun handleBarbedHook(event: PlayerFishEvent, player: Player, rod: ItemStack) {
        val level = cem.getLevel(rod, "barbed_hook")
        if (level <= 0) return

        val hooked = event.caught as? LivingEntity ?: return
        val victimId = hooked.uniqueId

        barbedHookTasks[victimId]?.cancel()

        var ticksLeft = level  // 1 tick per level (each tick = 1 second)
        val damagePerTick = 0.5 * level

        val task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (hooked.isDead || !hooked.isValid || ticksLeft <= 0) {
                barbedHookTasks.remove(victimId)?.cancel()
                return@Runnable
            }
            hooked.damage(damagePerTick)
            hooked.world.spawnParticle(Particle.DAMAGE_INDICATOR, hooked.location.add(0.0, 1.0, 0.0), 3, 0.2, 0.2, 0.2, 0.0)
            ticksLeft--
            if (ticksLeft <= 0) barbedHookTasks.remove(victimId)?.cancel()
        }, 20L, 20L)

        barbedHookTasks[victimId] = task
    }

    // Netcaster: chance to catch extra fish
    private fun handleNetcaster(event: PlayerFishEvent, player: Player, rod: ItemStack) {
        val level = cem.getLevel(rod, "netcaster")
        if (level <= 0) return

        val caught = event.caught as? Item ?: return
        val stack = caught.itemStack.clone()

        // Each level gives an independent 10% chance for one extra fish
        var extras = 0
        repeat(level) { if (Math.random() < 0.10) extras++ }
        if (extras <= 0) return

        stack.amount = extras
        val overflow = player.inventory.addItem(stack)
        overflow.values.forEach { leftover ->
            player.world.dropItemNaturally(player.location, leftover)
        }

        player.playSound(player.location, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.7f, 1.2f)
    }

    // ════════════════════════════════════════════════════════════════════
    //  CROSSBOW HANDLERS
    // ════════════════════════════════════════════════════════════════════

    // Shrapnel: arrow explodes on impact (no block damage)
    private fun handleShrapnel(data: ArrowData, arrow: Arrow) {
        val level = cem.getLevel(data.weapon, "shrapnel")
        if (level <= 0) return

        val loc = arrow.location
        val radius = 1.5 + level * 0.5
        val damage = 1.5 + level * 0.5

        loc.world.spawnParticle(Particle.EXPLOSION, loc, 1)
        loc.world.spawnParticle(Particle.SMOKE, loc, 8, 0.3, 0.3, 0.3, 0.05)
        loc.world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.5f)

        processingDamage.add(data.shooter.uniqueId)
        try {
            loc.world.getNearbyEntities(loc, radius, radius, radius)
                .filterIsInstance<LivingEntity>()
                .filter { it != data.shooter }
                .forEach { it.damage(damage, data.shooter) }
        } finally {
            processingDamage.remove(data.shooter.uniqueId)
        }
    }

    // Sniper: bonus damage per block of distance
    private fun handleSniper(event: EntityDamageByEntityEvent, data: ArrowData, arrow: Arrow) {
        if (!cem.hasEnchant(data.weapon, "sniper")) return

        val dist = data.launchLoc.distance(arrow.location)
        // 5% bonus per block, capped at 100% bonus
        val bonus = (dist * 0.05).coerceAtMost(1.0)
        event.damage *= (1.0 + bonus)
    }

    // Hunter: bonus damage to flying targets
    private fun handleHunter(event: EntityDamageByEntityEvent, data: ArrowData, victim: LivingEntity) {
        val level = cem.getLevel(data.weapon, "hunter")
        if (level <= 0) return

        val isFlying = when (victim) {
            is Player -> victim.isGliding
            else -> victim.isInWater.not() && victim.isOnGround.not() && victim.velocity.y > 0.05
        }
        if (!isFlying) return

        val bonus = 0.10 * level  // 10% per level, up to 50% at V
        event.damage *= (1.0 + bonus)
    }

    // ════════════════════════════════════════════════════════════════════
    //  TRIDENT HANDLERS
    // ════════════════════════════════════════════════════════════════════

    // Harpoon: pull hit entity toward the thrower
    private fun handleHarpoon(data: TridentData, victim: LivingEntity) {
        if (!cem.hasEnchant(data.weapon, "harpoon")) return
        if (!data.shooter.isOnline) return

        val shooterLoc = data.shooter.location.toVector()
        val victimLoc = victim.location.toVector()
        val dir = shooterLoc.subtract(victimLoc).normalize()

        victim.velocity = dir.multiply(1.4)
        victim.world.spawnParticle(Particle.BUBBLE_POP, victim.location.add(0.0, 1.0, 0.0), 10, 0.3, 0.3, 0.3, 0.1)
        victim.world.playSound(victim.location, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 0.8f)
    }

    // Tidal Leap: riptide propulsion even when not raining
    private fun handleTidalLeap(event: PlayerInteractEvent) {
        val player = event.player
        val item = player.inventory.itemInMainHand
        if (item.type != Material.TRIDENT) return
        if (!cem.hasEnchant(item, "tidal_leap")) return

        val riptideLevel = item.getEnchantmentLevel(Enchantment.RIPTIDE)
        if (riptideLevel <= 0) return

        // Vanilla handles riptide in rain/water; only intercept when it wouldn't fire
        if (player.isInWater) return
        if (player.world.hasStorm() && player.world.environment == org.bukkit.World.Environment.NORMAL) return

        // Cooldown: 8 seconds
        val now = System.currentTimeMillis()
        val lastUse = tidalLeapCooldowns[player.uniqueId] ?: 0L
        if (now - lastUse < 8_000L) return

        tidalLeapCooldowns[player.uniqueId] = now
        event.isCancelled = true

        // Launch the player in the direction they're facing, scaled by riptide level
        val dir = player.location.direction
        val speed = 1.5 + riptideLevel * 0.5
        val boost = dir.normalize().multiply(speed).add(Vector(0.0, 0.4, 0.0))
        player.velocity = boost

        val riptideSound = when (riptideLevel) {
            1 -> Sound.ITEM_TRIDENT_RIPTIDE_1
            2 -> Sound.ITEM_TRIDENT_RIPTIDE_2
            else -> Sound.ITEM_TRIDENT_RIPTIDE_3
        }
        player.playSound(player.location, riptideSound, 1.0f, 1.0f)
        player.world.spawnParticle(Particle.CLOUD, player.location, 20, 0.3, 0.5, 0.3, 0.1)
    }

    // ════════════════════════════════════════════════════════════════════
    //  CLEANUP
    // ════════════════════════════════════════════════════════════════════

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        barbedHookTasks.remove(event.entity.uniqueId)?.cancel()
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        barbedHookTasks.remove(uuid)?.cancel()
        tidalLeapCooldowns.remove(uuid)
        trackedArrows.entries.removeIf { it.value.shooter.uniqueId == uuid }
        trackedTridents.entries.removeIf { it.value.shooter.uniqueId == uuid }
    }
}
