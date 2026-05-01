package com.liam.joshymc.recipe

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe

class CustomRecipes(private val plugin: Joshymc) {

    private val registeredKeys = mutableListOf<NamespacedKey>()

    fun registerAll() {
        registerWeaponRecipes()
        registerToolRecipes()
        registerArmorRecipes()
        registerConsumableRecipes()
        registerLegendaryRecipes()

        plugin.logger.info("Registered ${registeredKeys.size} custom recipe(s).")
    }

    fun clear() {
        for (key in registeredKeys) {
            Bukkit.removeRecipe(key)
        }
        registeredKeys.clear()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun item(id: String): RecipeChoice.ExactChoice =
        RecipeChoice.ExactChoice(plugin.itemManager.getItem(id)!!.createItemStack())

    private fun vanilla(material: Material): RecipeChoice.ExactChoice =
        RecipeChoice.ExactChoice(ItemStack(material))

    private fun addRecipe(name: String, result: ItemStack, configure: ShapedRecipe.() -> Unit) {
        val key = NamespacedKey(plugin, name)
        val recipe = ShapedRecipe(key, result)
        recipe.configure()
        Bukkit.addRecipe(recipe)
        registeredKeys.add(key)
    }

    private fun result(id: String): ItemStack =
        plugin.itemManager.getItem(id)!!.createItemStack()

    // ── Crafting Materials → Weapons ─────────────────────────────────────────

    private fun registerWeaponRecipes() {
        // Void Blade: sword shape with void shards
        addRecipe("void_blade", result("void_blade")) {
            shape("V", "V", "S")
            setIngredient('V', item("void_shard"))
            setIngredient('S', vanilla(Material.STICK))
        }

        // Soul Scythe: 3 soul fragments top + 1 middle-right + stick middle-center + stick bottom-center
        addRecipe("soul_scythe", result("soul_scythe")) {
            shape("FFF", " SF", " S ")
            setIngredient('F', item("soul_fragment"))
            setIngredient('S', vanilla(Material.STICK))
        }

        // Inferno Axe: axe shape with inferno cores
        addRecipe("inferno_axe", result("inferno_axe")) {
            shape("II", "IS", " S")
            setIngredient('I', item("inferno_core"))
            setIngredient('S', vanilla(Material.STICK))
        }

        // Crystal Mace: 1 crystal essence top + 1 middle + 1 breeze rod bottom
        addRecipe("crystal_mace", result("crystal_mace")) {
            shape("C", "C", "B")
            setIngredient('C', item("crystal_essence"))
            setIngredient('B', vanilla(Material.BREEZE_ROD))
        }
    }

    // ── Crafting Materials → Tools ───────────────────────────────────────────

    private fun registerToolRecipes() {
        // Farmer's Sickle: 3 crystal essence top + 1 diamond middle-right + 1 stick bottom-center
        addRecipe("farmers_sickle", result("farmers_sickle")) {
            shape("CCC", " D ", " S ")
            setIngredient('C', item("crystal_essence"))
            setIngredient('D', vanilla(Material.DIAMOND))
            setIngredient('S', vanilla(Material.STICK))
        }

        // Excavator: 3 void shards top + 1 diamond shovel middle-center
        addRecipe("excavator", result("excavator")) {
            shape("VVV", " D ", "   ")
            setIngredient('V', item("void_shard"))
            setIngredient('D', vanilla(Material.DIAMOND_SHOVEL))
        }

        // Magnet Wand: 1 iron block top + 1 crystal essence middle + 1 stick bottom
        addRecipe("magnet_wand", result("magnet_wand")) {
            shape("I", "C", "S")
            setIngredient('I', vanilla(Material.IRON_BLOCK))
            setIngredient('C', item("crystal_essence"))
            setIngredient('S', vanilla(Material.STICK))
        }
    }

    // ── Crafting Materials → Armor ───────────────────────────────────────────

    private fun registerArmorRecipes() {
        // Void Armor (Void Shards)
        registerArmorSet("void", item("void_shard"))
        // Inferno Armor (Inferno Cores)
        registerArmorSet("inferno", item("inferno_core"))
        // Crystal Armor (Crystal Essence)
        registerArmorSet("crystal", item("crystal_essence"))
        // Soul Armor (Soul Fragments)
        registerArmorSet("soul", item("soul_fragment"))
    }

    private fun registerArmorSet(prefix: String, mat: RecipeChoice.ExactChoice) {
        // Helmet: 3 top + 1 left + 1 right
        addRecipe("${prefix}_helmet", result("${prefix}_helmet")) {
            shape("MMM", "M M", "   ")
            setIngredient('M', mat)
        }

        // Chestplate: vanilla shape (M M / MMM / MMM) — 8 mats.
        // Was previously "M M / MMM / M M" (7 mats, no bottom row), which
        // didn't match the vanilla chestplate pattern at all and that's
        // why fragments couldn't craft chestplates.
        addRecipe("${prefix}_chestplate", result("${prefix}_chestplate")) {
            shape("M M", "MMM", "MMM")
            setIngredient('M', mat)
        }

        // Leggings: 3 top + 1 middle-left + 1 middle-right + 1 bottom-left + 1 bottom-right
        addRecipe("${prefix}_leggings", result("${prefix}_leggings")) {
            shape("MMM", "M M", "M M")
            setIngredient('M', mat)
        }

        // Boots: 1 middle-left + 1 middle-right + 1 bottom-left + 1 bottom-right
        addRecipe("${prefix}_boots", result("${prefix}_boots")) {
            shape("   ", "M M", "M M")
            setIngredient('M', mat)
        }
    }

    // ── Consumables ──────────────────────────────────────────────────────────

    private fun registerConsumableRecipes() {
        // Speed Apple: golden apple center + 4 sugar around it
        addRecipe("speed_apple", result("speed_apple")) {
            shape(" S ", "SAS", " S ")
            setIngredient('S', vanilla(Material.SUGAR))
            setIngredient('A', vanilla(Material.GOLDEN_APPLE))
        }

        // Strength Apple: golden apple center + 4 blaze powder around it
        addRecipe("strength_apple", result("strength_apple")) {
            shape(" B ", "BAB", " B ")
            setIngredient('B', vanilla(Material.BLAZE_POWDER))
            setIngredient('A', vanilla(Material.GOLDEN_APPLE))
        }

        // Giant's Brew: glass bottle + ancient rune + golden apple
        addRecipe("giants_brew", result("giants_brew")) {
            shape("G", "R", "A")
            setIngredient('G', vanilla(Material.GLASS_BOTTLE))
            setIngredient('R', item("ancient_rune"))
            setIngredient('A', vanilla(Material.GOLDEN_APPLE))
        }

        // Miner's Brew: glass bottle + crystal essence + redstone
        addRecipe("miners_brew", result("miners_brew")) {
            shape("G", "C", "R")
            setIngredient('G', vanilla(Material.GLASS_BOTTLE))
            setIngredient('C', item("crystal_essence"))
            setIngredient('R', vanilla(Material.REDSTONE))
        }
    }

    // ── Legendary Items ──────────────────────────────────────────────────────

    private fun registerLegendaryRecipes() {
        // Blaze King's Crown: inferno cores (helmet shape) + 1 ancient rune top center + 2 gold blocks sides
        addRecipe("blaze_kings_crown", result("blaze_kings_crown")) {
            shape("IRI", "GIG", "   ")
            setIngredient('I', item("inferno_core"))
            setIngredient('R', item("ancient_rune"))
            setIngredient('G', vanilla(Material.GOLD_BLOCK))
        }

        // Phantom Cloak: 8 phantom membrane (chestplate shape) + 1 ancient rune center
        addRecipe("phantom_cloak", result("phantom_cloak")) {
            shape("P P", "PAP", "PPP")
            setIngredient('P', vanilla(Material.PHANTOM_MEMBRANE))
            setIngredient('A', item("ancient_rune"))
        }

        // Poseidon's Trident: 1 trident + 3 ancient runes + 1 heart of the sea
        addRecipe("poseidons_trident", result("poseidons_trident")) {
            shape("RRR", " T ", " H ")
            setIngredient('R', item("ancient_rune"))
            setIngredient('T', vanilla(Material.TRIDENT))
            setIngredient('H', vanilla(Material.HEART_OF_THE_SEA))
        }
    }
}
