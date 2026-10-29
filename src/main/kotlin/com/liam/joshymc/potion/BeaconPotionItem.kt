package com.liam.joshymc.potion

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/**
 * One drinkable beacon potion (issue #1318), e.g. Strong Potion of Haste. A plain vanilla
 * POTION with no base potion type, the effect's colour and one custom effect, so drinking it
 * is vanilla (effect merge, glass bottle back). Registered so `/joshymc give <id>` works.
 *
 * The effect's duration and level come from the gamemode it's made for (`beacon-potions.<gamemode>`),
 * which is also stamped on the potion. [createItemStack] uses the server's gamemode; brewing
 * and `/joshymc give` build it for the stand's / player's gamemode via [BeaconPotionStacks].
 */
class BeaconPotionItem(val effect: BeaconPotionEffect, val variant: BeaconPotionVariant) : CustomItem() {

    override val id = BeaconPotionRules.itemId(effect, variant)
    override val material = Material.POTION

    override val displayName: Component = BeaconPotionStacks.name(effect, variant)

    override val lore: List<Component> =
        BeaconPotionStacks.lore(effect, BeaconPotionRules.defaultEffect(effect).strength(variant))

    override fun applyMeta(meta: ItemMeta) {
        val plugin = Joshymc.instance
        val gamemode = plugin.gamemodeLockManager.serverGamemode
        BeaconPotionStacks.applyPotion(meta, effect, variant, BeaconPotionConfig.read(plugin.config, gamemode))
    }
}

object BeaconPotionStacks {

    private val GAMEMODE_KEY by lazy { NamespacedKey(Joshymc.instance, "beacon_potion_gamemode") }
    private val CUSTOM_ITEM_KEY by lazy { NamespacedKey(Joshymc.instance, "custom_item_id") }

    fun name(effect: BeaconPotionEffect, variant: BeaconPotionVariant): Component =
        Component.text(BeaconPotionRules.displayName(effect, variant), TextColor.color(effect.color))
            .decoration(TextDecoration.ITALIC, false)

    fun lore(effect: BeaconPotionEffect, strength: PotionStrength): List<Component> = LoreBuilder.build(
        type = "Potion",
        description = listOf(
            "Grants ${effect.displayName} ${BeaconPotionRules.roman(strength.level)}",
            "for ${BeaconPotionRules.formatDuration(strength.durationSeconds)}.",
        ),
        usage = "Right-click to drink.",
    )

    /** A fresh potion of [effect]/[variant] made for [settings]' gamemode. */
    fun create(plugin: Joshymc, effect: BeaconPotionEffect, variant: BeaconPotionVariant, settings: BeaconPotionSettings, amount: Int = 1): ItemStack {
        val item = plugin.itemManager.getItem(BeaconPotionRules.itemId(effect, variant))?.createItemStack(amount)
            ?: ItemStack(Material.POTION, amount)
        item.editMeta { applyPotion(it, effect, variant, settings) }
        return item
    }

    fun applyPotion(meta: ItemMeta, effect: BeaconPotionEffect, variant: BeaconPotionVariant, settings: BeaconPotionSettings) {
        val potion = meta as? PotionMeta ?: return
        val strength = settings.effect(effect).strength(variant)
        val type = effectType(effect) ?: return
        potion.basePotionType = null
        potion.clearCustomEffects()
        potion.addCustomEffect(PotionEffect(type, strength.durationTicks, strength.amplifier), true)
        potion.color = Color.fromRGB(effect.color)
        potion.displayName(name(effect, variant))
        potion.lore(lore(effect, strength))
        potion.persistentDataContainer.set(CUSTOM_ITEM_KEY, PersistentDataType.STRING, BeaconPotionRules.itemId(effect, variant))
        potion.persistentDataContainer.set(GAMEMODE_KEY, PersistentDataType.STRING, settings.gamemode.id)
    }

    fun effectType(effect: BeaconPotionEffect): PotionEffectType? =
        Registry.EFFECT.get(NamespacedKey.minecraft(effect.effectKey))

    /** The beacon potion [stack] is, or null. Only drinkable POTION items count. */
    fun identify(stack: ItemStack?): BrewInput.Beacon? {
        if (stack == null || stack.type != Material.POTION) return null
        val pdc = stack.itemMeta?.persistentDataContainer ?: return null
        val (effect, variant) = BeaconPotionRules.parseItemId(pdc.get(CUSTOM_ITEM_KEY, PersistentDataType.STRING)) ?: return null
        val gamemode = ServerGamemode.fromId(pdc.get(GAMEMODE_KEY, PersistentDataType.STRING))
        return BrewInput.Beacon(effect, variant, gamemode)
    }

    /** Any item carrying a beacon potion id, whatever its material (a splash one would be a bug). */
    fun isTagged(stack: ItemStack?): Boolean {
        if (stack == null || stack.isEmpty) return false
        val id = stack.itemMeta?.persistentDataContainer?.get(CUSTOM_ITEM_KEY, PersistentDataType.STRING)
        return BeaconPotionRules.parseItemId(id) != null
    }

    /** A plain vanilla Awkward Potion (no custom effects, not any custom item). */
    fun isAwkward(stack: ItemStack?): Boolean {
        if (stack == null || stack.type != Material.POTION) return false
        val meta = stack.itemMeta as? PotionMeta ?: return false
        if (meta.persistentDataContainer.has(CUSTOM_ITEM_KEY, PersistentDataType.STRING)) return false
        return meta.basePotionType == org.bukkit.potion.PotionType.AWKWARD && !meta.hasCustomEffects()
    }
}

/** Reads `beacon-potions.<gamemode>` from config.yml, falling back to the defaults key by key. */
object BeaconPotionConfig {

    fun read(config: ConfigurationSection, gamemode: ServerGamemode, problems: MutableList<String>? = null): BeaconPotionSettings {
        val section = config.getConfigurationSection("beacon-potions.${gamemode.id}")
        val ingredients = mutableMapOf<BeaconPotionEffect, String>()
        val effects = BeaconPotionEffect.entries.associateWith { effect ->
            val s = section?.getConfigurationSection(effect.id)
            val strengths = BeaconPotionVariant.entries.associateWith { variant ->
                PotionStrength(
                    BeaconPotionRules.clampDuration(s?.getInt("${variant.id}.duration-seconds", variant.defaultSeconds) ?: variant.defaultSeconds),
                    BeaconPotionRules.clampLevel(s?.getInt("${variant.id}.level", variant.defaultLevel) ?: variant.defaultLevel),
                )
            }
            var enabled = s?.getBoolean("enabled", true) ?: true
            var ingredient = (s?.getString("ingredient") ?: effect.defaultIngredient).trim().uppercase()
            val problem = when {
                Material.matchMaterial(ingredient)?.isItem != true -> "$ingredient is not an item"
                else -> BeaconPotionRules.ingredientProblem(effect, ingredient, ingredients)
            }
            if (problem != null) {
                problems?.add("beacon-potions.${gamemode.id}.${effect.id}.ingredient: $problem, using ${effect.defaultIngredient}")
                ingredient = effect.defaultIngredient
                if (enabled && ingredient in ingredients.values) {
                    problems?.add("beacon-potions.${gamemode.id}.${effect.id}: no free ingredient, ${effect.id} potions can't be brewed")
                    enabled = false
                }
            }
            if (enabled) ingredients[effect] = ingredient
            BeaconEffectSettings(enabled, ingredient, strengths)
        }
        return BeaconPotionSettings(gamemode, section?.getBoolean("enabled", true) ?: true, effects)
    }
}
