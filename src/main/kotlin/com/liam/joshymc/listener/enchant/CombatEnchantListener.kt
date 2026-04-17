package com.liam.joshymc.listener.enchant

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerVelocityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

class CombatEnchantListener(private val plugin: Joshymc) : Listener {

    private val cem get() = plugin.customEnchantManager

    // ── Bleed tracking: victim UUID -> active bleed task ─────
    private val bleedTasks = mutableMapOf<UUID, BukkitTask>()

    // ── Adrenaline tracking: attacker UUID -> list of kill timestamps ─
    private val adrenalineKills = mutableMapOf<UUID, MutableList<Long>>()

    // ── Guardian cooldown: player UUID -> last proc timestamp ─
    private val guardianCooldowns = mutableMapOf<UUID, Long>()

    // ── Rockets cooldown: player UUID -> last proc timestamp ─
    private val rocketsCooldowns = mutableMapOf<UUID, Long>()

    // ── Recursion guard: prevents cleave/striker/bleed damage from re-triggering enchants ─
    private val processingDamage = mutableSetOf<UUID>()

    // ── Armor helpers ───────────────────────────────────────
    private fun getHelmet(player: Player): ItemStack? = player.inventory.helmet
    private fun getChestplate(player: Player): ItemStack? = player.inventory.chestplate
    private fun getLeggings(player: Player): ItemStack? = player.inventory.leggings
    private fun getBoots(player: Player): ItemStack? = player.inventory.boots

    private fun isAxe(item: ItemStack): Boolean {
        return item.type == Material.WOODEN_AXE
                || item.type == Material.STONE_AXE
                || item.type == Material.IRON_AXE
                || item.type == Material.GOLDEN_AXE
                || item.type == Material.DIAMOND_AXE
                || item.type == Material.NETHERITE_AXE
    }

    private fun isSword(item: ItemStack): Boolean {
        return item.type == Material.WOODEN_SWORD
                || item.type == Material.STONE_SWORD
                || item.type == Material.IRON_SWORD
                || item.type == Material.GOLDEN_SWORD
                || item.type == Material.DIAMOND_SWORD
                || item.type == Material.NETHERITE_SWORD
    }

    // ══════════════════════════════════════════════════════════
    //  ENTITY DAMAGE BY ENTITY — offensive + some defensive
    // ══════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val attacker = event.damager as? Player ?: return
        val victim = event.entity as? LivingEntity ?: return

        // Recursion guard: skip if this damage was caused by an enchant effect (cleave, striker, bleed)
        if (attacker.uniqueId in processingDamage) return

        val weapon = attacker.inventory.itemInMainHand

        // ── Sword enchants ──────────────────────────────────
        if (isSword(weapon)) {
            handleExecute(event, attacker, victim, weapon)
            handleAdrenaline(event, attacker, weapon)
            handleLifesteal(event, attacker, weapon)
            handleBleed(attacker, victim, weapon)
            handleStriker(attacker, victim, weapon)
        }

        // ── Axe enchants ────────────────────────────────────
        if (isAxe(weapon)) {
            handleCleave(event, attacker, victim, weapon)
            handleBerserk(attacker, weapon)
            handleParalysis(victim, weapon)
            handleBlizzard(victim, weapon)
        }

        // ── Curse (requires both attacker and victim to be players) ─
        if (victim is Player) {
            handleCurseSwap(event, attacker, victim)
        }
    }

    // ══════════════════════════════════════════════════════════
    //  ENTITY DAMAGE — ALL damage to players (defense enchants)
    //  This fires for PvP, PvE, fall, fire, explosions — everything
    // ══════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return

        // Featherweight — cancel fall damage
        if (event.cause == EntityDamageEvent.DamageCause.FALL) {
            val boots = getBoots(player)
            if (boots != null && cem.hasEnchant(boots, "featherweight")) {
                event.isCancelled = true
                return
            }
        }

        // Valor — reduce explosion damage
        if (event.cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
            || event.cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
        ) {
            val leggings = getLeggings(player)
            if (leggings != null) {
                val level = cem.getLevel(leggings, "valor")
                if (level > 0) {
                    event.damage *= (1.0 - 0.10 * level)
                }
            }
        }

        // Focus — reduce axe damage (check if attacker is holding an axe)
        if (event is EntityDamageByEntityEvent) {
            val damager = event.damager
            if (damager is Player) {
                val weapon = damager.inventory.itemInMainHand
                if (isAxe(weapon)) {
                    handleFocus(event, player, weapon)
                }
            }
        }

        // Dodge — chance to negate any entity damage
        if (event is EntityDamageByEntityEvent) {
            handleDodge(event, player)
        }

        // Shockwave — reduce knockback from any entity hit
        if (event is EntityDamageByEntityEvent) {
            handleShockwave(player)
        }

        // Guardian — regen when health is low (works for ALL damage types)
        handleGuardian(player)

        // Rockets — levitation when low health
        handleRockets(event, player)
    }

    // ══════════════════════════════════════════════════════════
    //  ENTITY DEATH — adrenaline kill tracking
    // ══════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return

        // Track the kill regardless of current weapon — adrenaline checks
        // the weapon at hit time, this just records that a kill happened
        val kills = adrenalineKills.getOrPut(killer.uniqueId) { mutableListOf() }
        kills.add(System.currentTimeMillis())
    }

    // ══════════════════════════════════════════════════════════
    //  SWORD ENCHANT HANDLERS
    // ══════════════════════════════════════════════════════════

    // ── Lifesteal ───────────────────────────────────────────
    private fun handleLifesteal(event: EntityDamageByEntityEvent, attacker: Player, weapon: ItemStack) {
        val level = cem.getLevel(weapon, "lifesteal")
        if (level <= 0) return

        val healAmount = event.finalDamage * (0.05 * level)
        val newHealth = (attacker.health + healAmount).coerceAtMost(attacker.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0)
        attacker.health = newHealth
    }

    // ── Execute ─────────────────────────────────────────────
    private fun handleExecute(event: EntityDamageByEntityEvent, attacker: Player, victim: LivingEntity, weapon: ItemStack) {
        val level = cem.getLevel(weapon, "execute")
        if (level <= 0) return

        val maxHealth = victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
        val healthPercent = victim.health / maxHealth

        // Only activates below 50% HP
        if (healthPercent >= 0.5) return

        // Scale linearly: at 50% = full bonus, at 0% = full bonus (scales from 50% down)
        // bonusMultiplier goes from 0.0 at 50% to 1.0 at 0%
        val bonusMultiplier = 1.0 - (healthPercent / 0.5)
        val bonusDamage = event.damage * (0.10 * level) * bonusMultiplier

        event.damage += bonusDamage
    }

    // ── Bleed ───────────────────────────────────────────────
    private fun handleBleed(attacker: Player, victim: LivingEntity, weapon: ItemStack) {
        val level = cem.getLevel(weapon, "bleed")
        if (level <= 0) return

        val victimId = victim.uniqueId

        // Cancel existing bleed from this mechanic
        bleedTasks[victimId]?.cancel()

        var ticksRemaining = level // 1 damage per second for (level) seconds
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (victim.isDead || !victim.isValid || ticksRemaining <= 0 || (victim is Player && !victim.isOnline)) {
                bleedTasks[victimId]?.cancel()
                bleedTasks.remove(victimId)
                return@Runnable
            }
            victim.damage(1.0)
            ticksRemaining--
            if (ticksRemaining <= 0) {
                bleedTasks[victimId]?.cancel()
                bleedTasks.remove(victimId)
            }
        }, 20L, 20L) // every 20 ticks = 1 second

        bleedTasks[victimId] = task
    }

    // ── Adrenaline ──────────────────────────────────────────
    private fun handleAdrenaline(event: EntityDamageByEntityEvent, attacker: Player, weapon: ItemStack) {
        val level = cem.getLevel(weapon, "adrenaline")
        if (level <= 0) return

        val kills = adrenalineKills[attacker.uniqueId] ?: return
        val now = System.currentTimeMillis()
        val windowMs = 30_000L

        // Prune old kills
        kills.removeAll { now - it > windowMs }

        if (kills.isEmpty()) {
            adrenalineKills.remove(attacker.uniqueId)
            return
        }

        // +10% damage per kill per level
        val bonusPercent = 0.10 * level * kills.size
        event.damage *= (1.0 + bonusPercent)

        // Show the player their adrenaline bonus
        val bonusDisplay = "${(bonusPercent * 100).toInt()}%"
        attacker.sendActionBar(
            net.kyori.adventure.text.Component.text("\u2620 Adrenaline +$bonusDisplay ", net.kyori.adventure.text.format.NamedTextColor.RED)
                .append(net.kyori.adventure.text.Component.text("(${kills.size} kills)", net.kyori.adventure.text.format.NamedTextColor.GRAY))
        )
    }

    // ── Striker ─────────────────────────────────────────────
    private fun handleStriker(attacker: Player, victim: LivingEntity, weapon: ItemStack) {
        val level = cem.getLevel(weapon, "striker")
        if (level <= 0) return

        val chance = 0.02 * level
        if (Math.random() > chance) return

        // Visual lightning effect only (no vanilla lightning damage)
        victim.world.strikeLightningEffect(victim.location)

        // Manually deal 5 damage ignoring armor
        processingDamage.add(attacker.uniqueId)
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, Runnable {
            if (!victim.isDead && victim.isValid) {
                victim.damage(5.0)
            }
            processingDamage.remove(attacker.uniqueId)
        }, 1L)
    }

    // ══════════════════════════════════════════════════════════
    //  AXE ENCHANT HANDLERS
    // ══════════════════════════════════════════════════════════

    // ── Cleave ──────────────────────────────────────────────
    private fun handleCleave(event: EntityDamageByEntityEvent, attacker: Player, victim: LivingEntity, weapon: ItemStack) {
        val level = cem.getLevel(weapon, "cleave")
        if (level <= 0) return

        val damagePercent = 0.25 * level
        val cleaveAmount = event.damage * damagePercent

        processingDamage.add(attacker.uniqueId)
        try {
            victim.getNearbyEntities(2.0, 2.0, 2.0)
                .filterIsInstance<LivingEntity>()
                .filter { it != attacker && it != victim }
                .forEach { entity ->
                    entity.damage(cleaveAmount, attacker)
                }
        } finally {
            processingDamage.remove(attacker.uniqueId)
        }
    }

    // ── Berserk ─────────────────────────────────────────────
    private fun handleBerserk(attacker: Player, weapon: ItemStack) {
        val level = cem.getLevel(weapon, "berserk")
        if (level <= 0) return

        when (level) {
            1 -> {
                attacker.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, 100, 0, false, true, true))    // 5s
                attacker.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 60, 0, false, true, true))     // 3s
            }
            2 -> {
                attacker.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, 100, 1, false, true, true))    // 5s, level II
                attacker.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 60, 0, false, true, true))     // 3s
            }
            3 -> {
                attacker.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, 140, 1, false, true, true))    // 7s, level II
                attacker.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 60, 1, false, true, true))     // 3s, level II
            }
        }
    }

    // ── Paralysis ───────────────────────────────────────────
    private fun handleParalysis(victim: LivingEntity, weapon: ItemStack) {
        val level = cem.getLevel(weapon, "paralysis")
        if (level <= 0) return

        val chance = 0.10 * level // 10% per level
        if (Math.random() > chance) return

        val duration = 40 + (level * 10) // 2.5s - 3.5s
        victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, duration, 4, false, true, true))
        victim.addPotionEffect(PotionEffect(PotionEffectType.MINING_FATIGUE, duration, 3, false, true, true))
        victim.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 20, 0, false, true, true))
        if (victim is Player) {
            victim.freezeTicks = victim.maxFreezeTicks
            victim.playSound(victim.location, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.5f)
        }
    }

    // ── Blizzard ────────────────────────────────────────────
    private fun handleBlizzard(victim: LivingEntity, weapon: ItemStack) {
        val level = cem.getLevel(weapon, "blizzard")
        if (level <= 0) return

        victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 60, level - 1, false, true, true)) // 3s, Slowness (level)
        victim.freezeTicks = victim.freezeTicks + (40 * level)
    }

    // ══════════════════════════════════════════════════════════
    //  ARMOR DEFENSE ENCHANT HANDLERS
    // ══════════════════════════════════════════════════════════

    // ── Focus (helmet) ──────────────────────────────────────
    private fun handleFocus(event: EntityDamageByEntityEvent, victim: Player, weapon: ItemStack) {
        // Only reduces axe damage
        if (!isAxe(weapon)) return

        val helmet = getHelmet(victim) ?: return
        val level = cem.getLevel(helmet, "focus")
        if (level <= 0) return

        val reduction = 0.08 * level
        event.damage *= (1.0 - reduction)
    }

    // ── Dodge (chestplate) ──────────────────────────────────
    private fun handleDodge(event: EntityDamageByEntityEvent, victim: Player) {
        val chestplate = getChestplate(victim) ?: return
        val level = cem.getLevel(chestplate, "dodge")
        if (level <= 0) return

        val chance = 0.08 * level // 8% per level (24% at III)
        if (Math.random() > chance) return

        event.isCancelled = true
        event.damage = 0.0
        // Visual feedback so player knows they dodged
        victim.playSound(victim.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.5f)
        victim.world.spawnParticle(Particle.CLOUD, victim.location.add(0.0, 1.0, 0.0), 8, 0.3, 0.3, 0.3, 0.02)
    }

    // ── Guardian (chestplate) ───────────────────────────────
    private fun handleGuardian(victim: Player) {
        val chestplate = getChestplate(victim) ?: return
        val level = cem.getLevel(chestplate, "guardian")
        if (level <= 0) return

        // 10 second cooldown
        val now = System.currentTimeMillis()
        val lastProc = guardianCooldowns[victim.uniqueId] ?: 0L
        if (now - lastProc < 10_000L) return

        // Check health AFTER damage is fully applied (2 tick delay for safety)
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, Runnable {
            if (victim.isDead || !victim.isOnline) return@Runnable
            if (victim.health > 10.0) return@Runnable // Above 5 hearts, don't proc

            guardianCooldowns[victim.uniqueId] = System.currentTimeMillis()
            victim.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 80, level - 1, false, true, true))
            victim.playSound(victim.location, Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.5f)
            victim.world.spawnParticle(Particle.HEART, victim.location.add(0.0, 2.0, 0.0), 5, 0.3, 0.2, 0.3, 0.0)
        }, 2L)
    }

    // ── Shockwave (leggings) — handled via PlayerVelocityEvent ─
    // Mark players who were just hit so we know to reduce their next velocity change
    private val shockwaveActive = mutableMapOf<UUID, Int>() // UUID -> enchant level

    private fun handleShockwave(victim: Player) {
        val leggings = getLeggings(victim) ?: return
        val level = cem.getLevel(leggings, "shockwave")
        if (level <= 0) return
        // Flag this player — the next velocity event will be reduced
        shockwaveActive[victim.uniqueId] = level
        // Clean up flag after 2 ticks in case no velocity event fires
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, Runnable {
            shockwaveActive.remove(victim.uniqueId)
        }, 2L)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onVelocity(event: PlayerVelocityEvent) {
        val level = shockwaveActive.remove(event.player.uniqueId) ?: return
        val multiplier = 1.0 - (0.30 * level) // 30% reduction per level
        event.velocity = event.velocity.multiply(multiplier)
    }

    // ── Curse Swap (leggings) ───────────────────────────────
    private fun handleCurseSwap(event: EntityDamageByEntityEvent, attacker: Player, victim: Player) {
        val leggings = getLeggings(victim) ?: return
        val level = cem.getLevel(leggings, "curse_swap")
        if (level <= 0) return

        val chance = 0.07 * level // 7% per level (21% at III)
        if (Math.random() > chance) return

        val victimHealth = victim.health
        val attackerHealth = attacker.health

        val victimMax = victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
        val attackerMax = attacker.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0

        // Clamp swapped health to each player's max
        victim.health = attackerHealth.coerceAtMost(victimMax)
        attacker.health = victimHealth.coerceAtMost(attackerMax)

        // Feedback
        victim.playSound(victim.location, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 1.0f)
        attacker.playSound(attacker.location, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 1.0f)
        victim.world.spawnParticle(Particle.WITCH, victim.location.add(0.0, 1.0, 0.0), 15, 0.3, 0.5, 0.3, 0.0)
        attacker.world.spawnParticle(Particle.WITCH, attacker.location.add(0.0, 1.0, 0.0), 15, 0.3, 0.5, 0.3, 0.0)
    }

    // ── Rockets (boots) ─────────────────────────────────────
    private fun handleRockets(event: EntityDamageEvent, player: Player) {
        val boots = getBoots(player) ?: return
        if (!cem.hasEnchant(boots, "rockets")) return

        // 30 second cooldown
        val now = System.currentTimeMillis()
        val lastProc = rocketsCooldowns[player.uniqueId] ?: 0L
        if (now - lastProc < 30_000L) return

        // Check ACTUAL health after damage is applied (2 tick delay)
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, Runnable {
            if (player.isDead || !player.isOnline) return@Runnable
            // Trigger at 2 hearts (4 HP) or below
            if (player.health > 4.0) return@Runnable

            rocketsCooldowns[player.uniqueId] = System.currentTimeMillis()
            player.addPotionEffect(PotionEffect(PotionEffectType.LEVITATION, 40, 1, false, true, true))
            player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, false, true, true))
            player.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.5f)
        }, 2L)
    }
}
