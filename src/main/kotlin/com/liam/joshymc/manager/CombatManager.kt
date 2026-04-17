package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CombatManager(private val plugin: Joshymc) {

    companion object {
        const val PVP_SETTING_KEY = "pvp"
        const val NPC_TAG = "joshymc_combat_npc"
        const val NPC_DURATION_TICKS = 60 * 20L // 60 seconds
    }

    private val combatTags = ConcurrentHashMap<UUID, Long>()
    private var combatDurationMs: Long = 15_000
    private var tickTaskId: Int = -1

    // Combat log NPC data
    data class CombatNPC(
        val entityUuid: UUID,
        val playerUuid: UUID,
        val playerName: String,
        val inventory: Array<ItemStack?>,
        val armor: Array<ItemStack?>,
        val offhand: ItemStack?,
        val despawnTaskId: Int
    )

    private val activeNPCs = ConcurrentHashMap<UUID, CombatNPC>() // entity UUID -> NPC data
    /** Players who combat logged — their inventory must be cleared on rejoin */
    private val combatLoggedPlayers = ConcurrentHashMap.newKeySet<UUID>()

    fun start() {
        combatDurationMs = plugin.config.getLong("combat.tag-duration-seconds", 15) * 1000

        // Tick task — runs every tick (50ms) for smooth countdown
        tickTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            val now = System.currentTimeMillis()

            for ((uuid, expiry) in combatTags) {
                val player = plugin.server.getPlayer(uuid)
                if (player == null) {
                    combatTags.remove(uuid)
                    continue
                }

                if (now >= expiry) {
                    // Expired
                    combatTags.remove(uuid)
                    plugin.commsManager.sendActionBar(player,
                        Component.text("You are no longer in combat.", NamedTextColor.GREEN)
                    )
                } else {
                    // Show countdown
                    val remainingMs = expiry - now
                    val remainingSeconds = (remainingMs / 1000) + 1
                    plugin.commsManager.sendActionBar(player,
                        Component.text("In combat! ", NamedTextColor.RED)
                            .append(Component.text("${remainingSeconds}s", TextColor.color(0xFF5555)))
                    )
                }
            }
        }, 0L, 5L) // Every 5 ticks (0.25s) for responsive updates

        plugin.logger.info("[Combat] Manager started (${combatDurationMs / 1000}s tag duration).")
    }

    fun stop() {
        if (tickTaskId != -1) {
            plugin.server.scheduler.cancelTask(tickTaskId)
            tickTaskId = -1
        }
        combatTags.clear()
        // Remove all combat NPCs
        for ((_, npc) in activeNPCs) {
            plugin.server.scheduler.cancelTask(npc.despawnTaskId)
            val entity = Bukkit.getEntity(npc.entityUuid)
            entity?.remove()
        }
        activeNPCs.clear()
    }

    fun tag(player: Player) {
        val wasTagged = isTagged(player)
        combatTags[player.uniqueId] = System.currentTimeMillis() + combatDurationMs

        if (!wasTagged && player.gameMode == GameMode.SURVIVAL) {
            // First tag — disable flight and elytra (survival only)
            if (player.isFlying) {
                player.isFlying = false
                plugin.commsManager.send(player,
                    Component.text("Flight disabled — you are in combat!", NamedTextColor.RED),
                    CommunicationsManager.Category.COMBAT
                )
            }
            if (player.isGliding) {
                player.isGliding = false
                plugin.commsManager.send(player,
                    Component.text("Elytra disabled — you are in combat!", NamedTextColor.RED),
                    CommunicationsManager.Category.COMBAT
                )
            }
        }
    }

    fun isTagged(player: Player): Boolean {
        val expiry = combatTags[player.uniqueId] ?: return false
        if (System.currentTimeMillis() >= expiry) {
            combatTags.remove(player.uniqueId)
            return false
        }
        return true
    }

    fun untag(player: Player) {
        combatTags.remove(player.uniqueId)
    }

    fun canPvP(player: Player): Boolean {
        return plugin.settingsManager.getSetting(player, PVP_SETTING_KEY)
    }

    /**
     * Spawns a combat log NPC when a player quits while in combat.
     * The NPC holds the player's inventory and drops loot when killed.
     */
    fun spawnCombatLogNPC(player: Player) {
        val loc = player.location
        val world = loc.world

        // Store inventory before clearing
        val invContents = player.inventory.contents.map { it?.clone() }.toTypedArray()
        val armorContents = player.inventory.armorContents.map { it?.clone() }.toTypedArray()
        val offhand = player.inventory.itemInOffHand.clone()

        // Clear player inventory so items aren't duplicated
        player.inventory.clear()

        val npc = world.spawn(loc, Villager::class.java) { villager ->
            villager.customName(
                Component.text(player.name, NamedTextColor.RED)
                    .append(Component.text(" [Combat Log]", NamedTextColor.DARK_RED))
            )
            villager.isCustomNameVisible = true
            villager.setAI(false)
            villager.isSilent = true
            villager.addScoreboardTag(NPC_TAG)
            villager.addScoreboardTag("joshymc_combat_npc_${player.uniqueId}")
            villager.profession = Villager.Profession.NONE

            // Set health to player's health
            villager.health = player.health.coerceAtMost(villager.maxHealth)
        }

        // Schedule despawn after 60 seconds — drop all loot
        val despawnTaskId = plugin.server.scheduler.scheduleSyncDelayedTask(plugin, {
            val npcData = activeNPCs.remove(npc.uniqueId) ?: return@scheduleSyncDelayedTask
            if (!npc.isDead) {
                // Drop all items on the ground
                dropLoot(npc.location, npcData)
                npc.remove()
            }
        }, NPC_DURATION_TICKS)

        val npcData = CombatNPC(
            entityUuid = npc.uniqueId,
            playerUuid = player.uniqueId,
            playerName = player.name,
            inventory = invContents,
            armor = armorContents,
            offhand = offhand,
            despawnTaskId = despawnTaskId
        )
        activeNPCs[npc.uniqueId] = npcData

        // Track this player so we clear their inventory on rejoin
        combatLoggedPlayers.add(player.uniqueId)

        // Kill the player so they get a proper death (respawn at spawn, lose XP)
        player.health = 0.0

        // Broadcast combat log
        plugin.commsManager.broadcast(
            Component.text(player.name, NamedTextColor.RED)
                .append(Component.text(" combat logged!", NamedTextColor.DARK_RED)),
            CommunicationsManager.Category.COMBAT
        )
    }

    /**
     * Called when a combat-logged player rejoins. Clears their inventory
     * since their items are in the NPC or already dropped.
     */
    fun handleRejoin(player: Player) {
        if (combatLoggedPlayers.remove(player.uniqueId)) {
            player.inventory.clear()
            player.inventory.setArmorContents(arrayOfNulls(4))

            plugin.commsManager.send(player,
                Component.text("You combat logged! Your items were dropped where you left.", NamedTextColor.RED))
        }
    }

    /**
     * Called when a combat NPC is killed. Drops loot and removes tracking.
     */
    fun onNPCKilled(entityUuid: UUID) {
        val npcData = activeNPCs.remove(entityUuid) ?: return
        plugin.server.scheduler.cancelTask(npcData.despawnTaskId)

        val entity = Bukkit.getEntity(entityUuid)
        val loc = entity?.location ?: return

        dropLoot(loc, npcData)
    }

    fun isCombatNPC(entityUuid: UUID): Boolean = activeNPCs.containsKey(entityUuid)

    private fun dropLoot(loc: org.bukkit.Location, npc: CombatNPC) {
        val world = loc.world
        for (item in npc.inventory) {
            if (item != null && !item.type.isAir) {
                world.dropItemNaturally(loc, item)
            }
        }
        for (item in npc.armor) {
            if (item != null && !item.type.isAir) {
                world.dropItemNaturally(loc, item)
            }
        }
        if (npc.offhand != null && !npc.offhand.type.isAir) {
            world.dropItemNaturally(loc, npc.offhand)
        }
    }
}
