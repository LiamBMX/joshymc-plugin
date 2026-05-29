package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.impl.AncientRune
import com.liam.joshymc.item.impl.CrystalEssence
import com.liam.joshymc.item.impl.EnchantedDust
import com.liam.joshymc.item.impl.InfernoCore
import com.liam.joshymc.item.impl.SoulFragment
import com.liam.joshymc.item.impl.VoidShard
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import kotlin.random.Random

class CustomDropListener(private val plugin: Joshymc) : Listener {

    private val voidShard = VoidShard()
    private val soulFragment = SoulFragment()
    private val infernoCore = InfernoCore()
    private val crystalEssence = CrystalEssence()
    private val ancientRune = AncientRune()
    private val enchantedDust = EnchantedDust()

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        if (killer.gameMode == GameMode.CREATIVE) return

        val location = event.entity.location
        val world = event.entity.world

        val cfg = plugin.config
        when (event.entity.type) {
            // Void Shard drops
            EntityType.ENDERMAN -> {
                if (Random.nextDouble() < cfg.getDouble("custom-drops.void-shard.enderman", 0.05)) {
                    world.dropItemNaturally(location, voidShard.createItemStack())
                }
            }
            EntityType.SHULKER -> {
                if (Random.nextDouble() < cfg.getDouble("custom-drops.void-shard.shulker", 0.05)) {
                    val amount = Random.nextInt(1, 3) // 1-2
                    world.dropItemNaturally(location, voidShard.createItemStack(amount))
                }
            }
            EntityType.ENDER_DRAGON -> {
                val amount = Random.nextInt(5, 11) // 5-10
                world.dropItemNaturally(location, voidShard.createItemStack(amount))
            }

            // Soul Fragment drops
            EntityType.WITHER_SKELETON -> {
                if (Random.nextDouble() < cfg.getDouble("custom-drops.soul-fragment.wither-skeleton", 0.03)) {
                    world.dropItemNaturally(location, soulFragment.createItemStack())
                }
            }
            EntityType.GHAST -> {
                if (Random.nextDouble() < cfg.getDouble("custom-drops.soul-fragment.ghast", 0.05)) {
                    world.dropItemNaturally(location, soulFragment.createItemStack())
                }
            }
            EntityType.WITHER -> {
                val amount = Random.nextInt(3, 7) // 3-6
                world.dropItemNaturally(location, soulFragment.createItemStack(amount))
            }

            // Inferno Core drops
            EntityType.BLAZE -> {
                if (Random.nextDouble() < cfg.getDouble("custom-drops.inferno-core.blaze", 0.05)) {
                    world.dropItemNaturally(location, infernoCore.createItemStack())
                }
            }
            EntityType.MAGMA_CUBE -> {
                if (Random.nextDouble() < cfg.getDouble("custom-drops.inferno-core.magma-cube", 0.05)) {
                    world.dropItemNaturally(location, infernoCore.createItemStack())
                }
            }

            // Ancient Rune drops
            EntityType.ELDER_GUARDIAN -> {
                if (Random.nextDouble() < cfg.getDouble("custom-drops.ancient-rune.elder-guardian", 0.05)) {
                    world.dropItemNaturally(location, ancientRune.createItemStack())
                }
            }
            EntityType.WARDEN -> {
                val amount = Random.nextInt(1, 3) // 1-2
                world.dropItemNaturally(location, ancientRune.createItemStack(amount))
            }

            else -> {}
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (player.gameMode == GameMode.CREATIVE) return

        when (event.block.type) {
            Material.DIAMOND_ORE,
            Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE,
            Material.DEEPSLATE_EMERALD_ORE -> {
                if (Random.nextDouble() < plugin.config.getDouble("custom-drops.crystal-essence.diamond-ore", 0.05)) {
                    event.block.world.dropItemNaturally(
                        event.block.location,
                        crystalEssence.createItemStack(),
                    )
                }
            }
            else -> {}
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onEnchantItem(event: EnchantItemEvent) {
        val player = event.enchanter
        if (player.gameMode == GameMode.CREATIVE) return

        if (Random.nextDouble() < plugin.config.getDouble("custom-drops.enchanted-dust.enchanting", 0.05)) {
            player.world.dropItemNaturally(player.location, enchantedDust.createItemStack())
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (player.gameMode == GameMode.CREATIVE) return

        val inventory = event.inventory
        if (inventory.type != InventoryType.GRINDSTONE) return

        // Result slot in grindstone is slot index 2
        if (event.rawSlot != 2) return

        // Only trigger if there's actually an item in the result slot
        val result = event.currentItem ?: return
        if (result.type.isAir) return

        if (Random.nextDouble() < plugin.config.getDouble("custom-drops.enchanted-dust.grindstone", 0.05)) {
            val amount = Random.nextInt(1, 3) // 1-2
            player.world.dropItemNaturally(player.location, enchantedDust.createItemStack(amount))
        }
    }
}
