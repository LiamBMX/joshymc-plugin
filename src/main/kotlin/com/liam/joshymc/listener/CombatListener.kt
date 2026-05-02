package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CombatManager
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Villager
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleFlightEvent

class CombatListener(private val plugin: Joshymc) : Listener {

    companion object {
        // Commands that let a player escape or fully heal — banned while
        // combat-tagged. Includes JoshyMC's own commands and the obvious
        // vanilla equivalents (joshymc:fly, minecraft:tp, etc.).
        private val BLOCKED_COMBAT_COMMANDS = setOf(
            "fly", "heal", "feed", "god", "repair", "fix",
            "tp", "tphere", "tpa", "tpahere", "tpaccept", "tpyes",
            "spawn", "home", "homes", "warp", "warps", "pwarp",
            "rtp", "wild", "back", "vanish", "v",
            "kit", "kits", "ec", "echest", "enderchest",
            // /pvp already self-blocks; /settings blocked here as a
            // fallback because the GUI also has a per-toggle check.
            "settings", "pvp",
        )
        // Same list with `joshymc:` and `minecraft:` namespacing in case
        // players try to bypass via the namespaced alias.
        private val BLOCKED_COMBAT_PREFIXED = BLOCKED_COMBAT_COMMANDS.flatMap {
            listOf("joshymc:$it", "minecraft:$it", "essentials:$it", "bukkit:$it")
        }.toSet()
    }

    /**
     * Core PvP handler — resolves the attacking player from direct hits,
     * projectiles, and TNT, then checks PvP toggle and applies combat tags.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = resolvePlayerSource(event) ?: return

        if (attacker == victim) return

        val combat = plugin.combatManager

        // PvP toggle check — both must have PvP enabled
        if (!combat.canPvP(attacker) || !combat.canPvP(victim)) {
            event.isCancelled = true

            if (!combat.canPvP(attacker)) {
                plugin.commsManager.sendActionBar(attacker, Component.text("Your PvP is disabled. /pvp on", NamedTextColor.RED))
            } else {
                plugin.commsManager.sendActionBar(attacker, Component.text("That player has PvP disabled.", NamedTextColor.RED))
            }
            return
        }

        // Both have PvP on — apply combat tags
        combat.tag(attacker)
        combat.tag(victim)
    }

    /**
     * Block flight while combat tagged — survival mode only.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onToggleFlight(event: PlayerToggleFlightEvent) {
        if (!event.isFlying) return
        if (event.player.gameMode != GameMode.SURVIVAL) return
        if (plugin.combatManager.isTagged(event.player)) {
            event.isCancelled = true
            plugin.commsManager.send(event.player,
                Component.text("You cannot fly while in combat!", NamedTextColor.RED),
                CommunicationsManager.Category.COMBAT
            )
        }
    }

    /**
     * Block elytra while combat tagged — survival mode only.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onToggleGlide(event: org.bukkit.event.entity.EntityToggleGlideEvent) {
        val player = event.entity as? Player ?: return
        if (!event.isGliding) return
        if (player.gameMode != GameMode.SURVIVAL) return
        if (plugin.combatManager.isTagged(player)) {
            event.isCancelled = true
            plugin.commsManager.send(player,
                Component.text("You cannot use elytra while in combat!", NamedTextColor.RED),
                CommunicationsManager.Category.COMBAT
            )
        }
    }

    /**
     * Block ender pearl + chorus fruit launches while combat tagged.
     * Pearls let players escape combat instantly otherwise.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPearlLaunch(event: ProjectileLaunchEvent) {
        val shooter = event.entity.shooter as? Player ?: return
        if (!plugin.combatManager.isTagged(shooter)) return
        val type = event.entity.type.name
        if (type == "ENDER_PEARL" || type == "CHORUS_FRUIT") {
            event.isCancelled = true
            plugin.commsManager.sendActionBar(shooter,
                Component.text("You can't pearl while in combat!", NamedTextColor.RED)
            )
        }
    }

    /**
     * Block escape commands while combat tagged: /fly, /heal, /repair, /tp,
     * /tpa, /spawn, /home, /warp, /rtp, /back. Ops still get the command —
     * we want to lock everyone equally during a fight to keep PvP fair.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onCombatCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        if (!plugin.combatManager.isTagged(player)) return
        val cmd = event.message.removePrefix("/").substringBefore(' ').lowercase()
        if (cmd in BLOCKED_COMBAT_COMMANDS || cmd in BLOCKED_COMBAT_PREFIXED) {
            event.isCancelled = true
            plugin.commsManager.send(player,
                Component.text("You can't use /$cmd while in combat!", NamedTextColor.RED),
                CommunicationsManager.Category.COMBAT
            )
        }
    }

    /**
     * Clear combat tag on death.
     */
    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        plugin.combatManager.untag(event.player)
    }

    /**
     * On rejoin — clear inventory if they combat logged.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        // Delay 1 tick to ensure player is fully loaded
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (event.player.isOnline) {
                plugin.combatManager.handleRejoin(event.player)
            }
        }, 1L)
    }

    /**
     * Combat log — spawn NPC if player quits while tagged.
     */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        if (plugin.combatManager.isTagged(player)) {
            plugin.combatManager.spawnCombatLogNPC(player)
        }
        plugin.combatManager.untag(player)
    }

    /**
     * Handle combat NPC death — drop loot.
     */
    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity is Villager && entity.scoreboardTags.contains(CombatManager.NPC_TAG)) {
            // Cancel default drops (we handle it manually)
            event.drops.clear()
            event.droppedExp = 0
            plugin.combatManager.onNPCKilled(entity.uniqueId)
        }
    }

    /**
     * Prevent combat NPCs from being damaged by non-players (so only players can kill them).
     * Also make sure the NPC can actually take damage from players.
     */
    @EventHandler(priority = EventPriority.LOW)
    fun onNPCDamage(event: EntityDamageByEntityEvent) {
        val entity = event.entity
        if (entity is Villager && entity.scoreboardTags.contains(CombatManager.NPC_TAG)) {
            // Allow damage from players, cancel everything else
            val source = resolvePlayerSource(event)
            if (source == null) {
                event.isCancelled = true
            }
        }
    }

    /**
     * Traces back to the originating Player from direct hits, projectiles, and TNT.
     */
    private fun resolvePlayerSource(event: EntityDamageByEntityEvent): Player? {
        val damager = event.damager

        if (damager is Player) return damager

        if (damager is Projectile) {
            val shooter = damager.shooter
            if (shooter is Player) return shooter
        }

        if (damager is TNTPrimed) {
            val source = damager.source
            if (source is Player) return source
        }

        return null
    }
}
