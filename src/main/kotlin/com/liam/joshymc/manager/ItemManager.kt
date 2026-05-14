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

        // Legendary items
        register(BlazeKingsCrown())
        register(PhantomCloak())
        register(PoseidonsTrident())
        register(ClaimBlockToken())
        register(SkillTomeMining())
        register(SkillTomeFarming())

        plugin.logger.info("Registered ${items.size} custom item(s).")
    }

    private fun register(item: CustomItem) {
        items[item.id] = item
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
