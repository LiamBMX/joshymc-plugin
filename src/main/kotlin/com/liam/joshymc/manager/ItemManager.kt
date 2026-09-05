package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.item.impl.*
import org.bukkit.inventory.ItemStack

class ItemManager(private val plugin: Joshymc) {

    private val items = mutableMapOf<String, CustomItem>()

    fun registerAll() {
        register(VoidDrill())
        register(VoidDrill5x5())
        register(VoidBore())
        register(VoidBore5x5())
        register(VoidBoreChunk())
        register(AfkKey())
        register(EasterEgg())
        register(ExplosiveEgg())
        register(FreezeEgg())
        register(BlindnessEgg())
        register(TeleportEgg())
        register(LevitationEgg())
        register(KnockbackEgg())
        register(SwapEgg())
        register(LightningEgg())
        register(CobwebEgg())
        register(ConfusionEgg())
        register(EnderEgg())
        register(CarrotSword())
        register(BunnyHelmet())
        register(BunnyChestplate())
        register(BunnyLeggings())
        register(BunnyBoots())
        register(BubbleButtLeggings())

        // Crafting materials
        register(VoidShard())
        register(SoulFragment())
        register(InfernoCore())
        register(CrystalEssence())
        register(AncientRune())
        register(EnchantedDust())

        // Custom weapons
        register(VoidBlade())
        register(SoulScythe())
        register(InfernoAxe())
        register(CrystalMace())
        register(CarrotLauncher())
        register(FlowerSpade())

        // Custom tools
        register(AutoMiner())
        register(FarmersSickle())
        register(LumberjacksAxe())
        register(Excavator())
        register(MagnetWand())

        // Armor sets
        register(VoidHelmet()); register(VoidChestplate()); register(VoidLeggings()); register(VoidBoots())
        register(InfernoHelmet()); register(InfernoChestplate()); register(InfernoLeggings()); register(InfernoBoots())
        register(CrystalHelmet()); register(CrystalChestplate()); register(CrystalLeggings()); register(CrystalBoots())
        register(SoulHelmet()); register(SoulChestplate()); register(SoulLeggings()); register(SoulBoots())
        register(FlowerHelmet()); register(FlowerChestplate()); register(FlowerLeggings()); register(FlowerBoots())

        // Consumables
        register(MoneyPouchSmall())
        register(MoneyPouchMedium())
        register(MoneyPouchLarge())
        register(XpTome())
        register(SpeedApple())
        register(StrengthApple())
        register(GiantsBrew())
        register(MinersBrew())
        register(WardensHeart())

        // Utility blocks
        register(FastHopper())

        // Wands
        register(SellWand())

        // Legendary items
        register(BlazeKingsCrown())
        register(PhantomCloak())
        register(PoseidonsTrident())
        register(ClaimBlockToken())
        register(SkillTomeMining())
        register(SkillTomeFarming())
        register(Token())

        // Moderator Mode hotbar tools
        register(ModModePunish(plugin))
        register(ModModeRandomTp(plugin))
        register(ModModeFreeze(plugin))
        register(ModModeTotemGuard(plugin))
        register(ModModeVanish(plugin))
        register(ModModeInvsee(plugin))
        register(ModModeSpectator(plugin))
        register(ModModeEcsee(plugin))
        register(ModModeVault(plugin))

        plugin.logger.info("Registered ${items.size} custom item(s).")
        validateModelIds()
    }

    private fun register(item: CustomItem) {
        items[item.id] = item
    }

    /**
     * Warns (without failing startup) if two different custom items resolve to the same
     * `minecraft:item_model` id, e.g. from copy-pasting an existing item class and forgetting
     * to change its model id. This can't verify the resourcepack assets themselves (those
     * aren't shipped inside the plugin jar), only that two in-code items don't collide.
     */
    private fun validateModelIds() {
        val ownerByModelId = mutableMapOf<String, String>()
        for (item in items.values) {
            val modelId = item.createItemStack().itemMeta?.itemModel?.key ?: continue
            val existingOwner = ownerByModelId.putIfAbsent(modelId, item.id)
            if (existingOwner != null && existingOwner != item.id) {
                plugin.logger.warning(
                    "Custom item model ID collision: '$modelId' is used by both '$existingOwner' and '${item.id}'."
                )
            }
        }
    }

    fun clear() {
        items.clear()
    }

    fun getItem(id: String): CustomItem? = items[id]

    fun getAllItems(): Collection<CustomItem> = items.values

    fun isCustomItem(itemStack: ItemStack?, id: String): Boolean {
        if (itemStack == null) return false
        val meta = itemStack.itemMeta ?: return false
        val container = meta.persistentDataContainer
        val key = org.bukkit.NamespacedKey(plugin, "custom_item_id")
        return container.has(key, org.bukkit.persistence.PersistentDataType.STRING)
                && container.get(key, org.bukkit.persistence.PersistentDataType.STRING) == id
    }

    fun getCustomItemId(itemStack: ItemStack?): String? {
        if (itemStack == null) return null
        val meta = itemStack.itemMeta ?: return null
        val container = meta.persistentDataContainer
        val key = org.bukkit.NamespacedKey(plugin, "custom_item_id")
        return container.get(key, org.bukkit.persistence.PersistentDataType.STRING)
    }
}
