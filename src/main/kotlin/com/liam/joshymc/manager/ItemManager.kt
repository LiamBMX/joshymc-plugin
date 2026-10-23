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
        register(BubbleButtLeggings())

        // Utility blocks
        register(FastHopper())

        // Wands
        register(SellWand())

        // Currency
        register(Token())

        // Candy
        register(PeanutButterCup())
        LinkPopColor.entries.forEach { register(LinkPop(it)) }
        register(JosheesBar())
        register(Rurus())
        register(CandyCorn())

        // Throwables
        register(Tomato())

        // Collectibles (looks only)
        register(Gun())

        // October Halloween Collection
        register(PhantomsGrasp())
        register(Gravedigger())
        register(JackOLanternMask())
        register(HauntedHollowChestplate())
        register(HauntedHollowLeggings())
        register(HauntedHollowBoots())
        register(BoneRattler())
        register(PumpkinPummel())
        register(GreatPumpkinPie())
        // September Autumn Collection
        register(AutumnsEdge())
        register(HarvestScythe())
        register(OrchardPickaxe())
        register(GoldenCrest())
        register(FallingLeaf())
        // Woodland Collection
        register(LumberjacksLegacy())
        register(WoodlandHunter())
        register(Maplefang())
        register(Hearthkeeper())
        // Viking Boomerang (trident with hidden built-in Loyalty III)
        register(VikingBoomerang())
        // Nordic set (November crate armor; the treads keep the autumn_wanderer id)
        register(NordicHelm())
        register(NordicPlate())
        register(NordicGreaves())
        register(NordicTreads())
        // Viking set (horned helmet with the orange beard, issue #1217)
        register(VikingHelmet())
        register(VikingChestplate())
        register(VikingLeggings())
        register(VikingBoots())
        // Winter Collection
        register(Frostbite())
        register(GlacierBreaker())
        register(WingsOfTheBlizzard())
        register(WintersWrath())
        // Snowveil set (Winter Collection armor; the skates keep the ice_skates id)
        register(SnowveilHood())
        register(SnowveilGuard())
        register(SnowveilLeggings())
        register(SnowveilSkates())
        // Shark Set
        register(SharkHelmet())
        register(SharkChestplate())
        register(SharkLeggings())
        register(SharkBoots())

        // Retextured Trial Keys
        register(JanuaryKey())
        register(FebruaryKey())
        register(MarchKey())
        register(AprilKey())
        register(MayKey())
        register(JuneKey())
        register(JulyKey())
        register(AugustKey())
        register(SeptemberKey())
        register(OctoberKey())
        register(NovemberKey())
        register(DecemberKey())
        register(MoneyKey())
        register(HarvestKey())
        register(StockpileKey())
        register(HomesteadKey())
        register(CampfireKey())
        register(CabinKey())
        register(CreditKey())

        // Moderator Mode hotbar tools
        register(ModModePunish(plugin))
        register(ModModeRandomTp(plugin))
        register(ModModeFreeze(plugin))
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
