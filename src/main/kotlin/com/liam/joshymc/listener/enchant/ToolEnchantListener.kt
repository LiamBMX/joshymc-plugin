package com.liam.joshymc.listener.enchant

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.Sound
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import java.util.UUID

class ToolEnchantListener(private val plugin: Joshymc) : Listener {

    private val enchants get() = plugin.customEnchantManager

    /** Prevents recursive triggers for explosive and ground_pound. */
    private val explosiveProcessing = mutableSetOf<UUID>()
    private val groundPoundProcessing = mutableSetOf<UUID>()

    // ── Smelt map (autosmelt) ───────────────────────────

    private val smeltMap = mapOf(
        Material.RAW_IRON to Material.IRON_INGOT,
        Material.RAW_GOLD to Material.GOLD_INGOT,
        Material.RAW_COPPER to Material.COPPER_INGOT,
        Material.COBBLESTONE to Material.STONE,
        Material.COBBLED_DEEPSLATE to Material.DEEPSLATE,
        Material.IRON_ORE to Material.IRON_INGOT,
        Material.DEEPSLATE_IRON_ORE to Material.IRON_INGOT,
        Material.GOLD_ORE to Material.GOLD_INGOT,
        Material.DEEPSLATE_GOLD_ORE to Material.GOLD_INGOT,
        Material.COPPER_ORE to Material.COPPER_INGOT,
        Material.DEEPSLATE_COPPER_ORE to Material.COPPER_INGOT,
        Material.ANCIENT_DEBRIS to Material.NETHERITE_SCRAP,
        Material.WET_SPONGE to Material.SPONGE,
        Material.SAND to Material.GLASS,
    )

    // ── Condenser map ───────────────────────────────────

    private val condenserMap = mapOf(
        Material.RAW_IRON to Material.RAW_IRON_BLOCK,
        Material.RAW_GOLD to Material.RAW_GOLD_BLOCK,
        Material.RAW_COPPER to Material.RAW_COPPER_BLOCK,
        Material.DIAMOND to Material.DIAMOND_BLOCK,
        Material.EMERALD to Material.EMERALD_BLOCK,
        Material.LAPIS_LAZULI to Material.LAPIS_BLOCK,
        Material.REDSTONE to Material.REDSTONE_BLOCK,
        Material.COAL to Material.COAL_BLOCK,
        Material.IRON_INGOT to Material.IRON_BLOCK,
        Material.GOLD_INGOT to Material.GOLD_BLOCK,
        Material.COPPER_INGOT to Material.COPPER_BLOCK,
    )

    // ── Experience blocks ───────────────────────────────

    private val experienceBlocks = setOf(
        Material.STONE,
        Material.DEEPSLATE,
        Material.ANDESITE,
        Material.DIORITE,
        Material.GRANITE,
        Material.TUFF,
        Material.CALCITE,
        Material.NETHERRACK,
        Material.BASALT,
        Material.BLACKSTONE,
    )

    // ── Explosive unbreakable blocks ────────────────────

    private val explosiveBlacklist = setOf(
        Material.BEDROCK,
        Material.OBSIDIAN,
        Material.CRYING_OBSIDIAN,
        Material.REINFORCED_DEEPSLATE,
        Material.END_PORTAL_FRAME,
        Material.BARRIER,
        Material.COMMAND_BLOCK,
        Material.STRUCTURE_BLOCK,
        Material.AIR,
        Material.CAVE_AIR,
        Material.VOID_AIR,
    )

    // ── Crop types for hoe enchants ─────────────────────

    private val groundPoundCrops = setOf(
        Material.WHEAT,
        Material.CARROTS,
        Material.POTATOES,
        Material.BEETROOTS,
        Material.NETHER_WART,
        Material.COCOA,
        Material.SWEET_BERRY_BUSH,
        Material.MELON,
        Material.PUMPKIN,
    )

    private val replantableCrops = setOf(
        Material.WHEAT,
        Material.CARROTS,
        Material.POTATOES,
        Material.BEETROOTS,
        Material.NETHER_WART,
    )

    // Tillable blocks for ground_pound
    private val tillableBlocks = setOf(
        Material.GRASS_BLOCK, Material.DIRT, Material.DIRT_PATH, Material.COARSE_DIRT, Material.ROOTED_DIRT
    )

    // ═══════════════════════════════════════════════════════
    //  PlayerInteractEvent — ground_pound (right-click to till 3x3)
    // ═══════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        val item = player.inventory.itemInMainHand
        if (!isHoe(item.type)) return
        if (!enchants.hasEnchant(item, "ground_pound")) return

        val block = event.clickedBlock ?: return
        if (block.type !in tillableBlocks) return

        // Till the clicked block + 3x3 area
        event.isCancelled = true
        for (dx in -1..1) {
            for (dz in -1..1) {
                val target = block.getRelative(dx, 0, dz)
                if (target.type in tillableBlocks) {
                    // Only till if the block above is air (can't till under solid blocks)
                    if (target.getRelative(0, 1, 0).type.isAir) {
                        target.type = Material.FARMLAND
                    }
                }
            }
        }
        player.playSound(block.location, Sound.ITEM_HOE_TILL, 1.0f, 1.0f)
    }

    // ═══════════════════════════════════════════════════════
    //  BlockDamageEvent — glass_breaker
    // ═══════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockDamage(event: BlockDamageEvent) {
        val player = event.player
        val item = player.inventory.itemInMainHand
        if (item.type.isAir) return

        // glass_breaker — shovel only
        if (isShovel(item.type)
            && enchants.hasEnchant(item, "glass_breaker")
            && event.block.type.name.contains("GLASS")
        ) {
            event.instaBreak = true
        }
    }

    // ═══════════════════════════════════════════════════════
    //  BlockDropItemEvent — magnet, autosmelt
    // ═══════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockDropItem(event: BlockDropItemEvent) {
        val player = event.player
        val item = player.inventory.itemInMainHand
        if (item.type.isAir) return

        // magnet — all tools
        if (enchants.hasEnchant(item, "magnet")) {
            for (dropped in event.items) {
                dropped.teleport(player.location)
            }
        }

        // autosmelt — pickaxe only
        if (isPickaxe(item.type) && enchants.hasEnchant(item, "autosmelt")) {
            for (dropped in event.items) {
                val stack = dropped.itemStack
                val smelted = smeltMap[stack.type]
                if (smelted != null) {
                    stack.type = smelted
                    dropped.itemStack = stack
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  BlockBreakEvent — experience, condenser, explosive,
    //                     ground_pound, great_harvest, blessing
    // ═══════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val item = player.inventory.itemInMainHand
        if (item.type.isAir) return

        // ── Pickaxe enchants ────────────────────────────

        if (isPickaxe(item.type)) {
            // experience
            val expLevel = enchants.getLevel(item, "experience")
            if (expLevel > 0 && event.block.type in experienceBlocks) {
                player.giveExp(expLevel)
            }

            // condenser
            if (enchants.hasEnchant(item, "condenser")) {
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    condenseInventory(player.inventory)
                }, 1L)
            }

            // explosive
            val explosiveLevel = enchants.getLevel(item, "explosive")
            if (explosiveLevel > 0 && player.uniqueId !in explosiveProcessing) {
                val chance = 0.05 * explosiveLevel
                if (Math.random() < chance) {
                    explosiveProcessing.add(player.uniqueId)
                    try {
                        val center = event.block
                        val tool = player.inventory.itemInMainHand
                        for (dx in -1..1) {
                            for (dy in -1..1) {
                                for (dz in -1..1) {
                                    if (dx == 0 && dy == 0 && dz == 0) continue
                                    val target = center.getRelative(dx, dy, dz)
                                    if (target.type !in explosiveBlacklist) {
                                        target.breakNaturally(tool)
                                    }
                                }
                            }
                        }
                    } finally {
                        explosiveProcessing.remove(player.uniqueId)
                    }
                }
            }
        }

        // ── Hoe enchants ────────────────────────────────

        if (isHoe(item.type)) {
            val brokenType = event.block.type

            // great_harvest + blessing
            if (enchants.hasEnchant(item, "great_harvest") && brokenType in replantableCrops) {
                val blockLocation = event.block.location
                val blockBelow = event.block.getRelative(0, -1, 0).type
                val canReplant = when (brokenType) {
                    Material.NETHER_WART -> blockBelow == Material.SOUL_SAND
                    else -> blockBelow == Material.FARMLAND
                }

                if (canReplant) {
                    val hasBlessing = enchants.hasEnchant(item, "blessing")

                    // Replant at age 0 after 1 tick
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        val block = blockLocation.block
                        block.type = brokenType
                        val ageable = block.blockData as? Ageable ?: return@Runnable
                        ageable.age = 0
                        block.blockData = ageable

                        // blessing — grow to max age after 3 seconds
                        if (hasBlessing) {
                            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                                val cropBlock = blockLocation.block
                                if (cropBlock.type != brokenType) return@Runnable
                                val cropData = cropBlock.blockData as? Ageable ?: return@Runnable
                                cropData.age = cropData.maximumAge
                                cropBlock.blockData = cropData
                            }, 60L)
                        }
                    }, 1L)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════

    private fun condenseInventory(inventory: PlayerInventory) {
        for ((input, output) in condenserMap) {
            val count = countMaterial(inventory, input)
            if (count >= 9) {
                val batches = count / 9
                removeMaterial(inventory, input, batches * 9)
                inventory.addItem(ItemStack(output, batches))
            }
        }
    }

    private fun countMaterial(inventory: PlayerInventory, material: Material): Int {
        var count = 0
        for (stack in inventory.storageContents) {
            if (stack != null && stack.type == material) {
                count += stack.amount
            }
        }
        return count
    }

    private fun removeMaterial(inventory: PlayerInventory, material: Material, amount: Int) {
        var remaining = amount
        for (i in 0 until inventory.size) {
            if (remaining <= 0) break
            val stack = inventory.getItem(i) ?: continue
            if (stack.type != material) continue
            if (stack.amount <= remaining) {
                remaining -= stack.amount
                inventory.setItem(i, null)
            } else {
                stack.amount -= remaining
                remaining = 0
            }
        }
    }

    private fun isPickaxe(type: Material): Boolean =
        type.name.endsWith("_PICKAXE")

    private fun isShovel(type: Material): Boolean =
        type.name.endsWith("_SHOVEL")

    private fun isAxe(type: Material): Boolean =
        type.name.endsWith("_AXE")

    private fun isHoe(type: Material): Boolean =
        type.name.endsWith("_HOE")

    private fun isTool(type: Material): Boolean =
        isPickaxe(type) || isShovel(type) || isAxe(type) || isHoe(type)
}
