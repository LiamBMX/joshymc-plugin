package com.liam.joshymc.potion

import com.liam.joshymc.gamemode.ServerGamemode

/**
 * Drinkable beacon potions (issue #1318), Bukkit-free.
 *
 * Of the six beacon effects, vanilla already brews Speed (Swiftness), Jump Boost (Leaping),
 * Strength and Regeneration, and those are left exactly as they are. Only the two beacon
 * effects with no drinkable vanilla potion get one here: Haste and Resistance. They are
 * plain `minecraft:potion` items (drinkable only), so drinking, stacking, the glass bottle
 * returned and the effect itself are all vanilla.
 */
enum class BeaconPotionEffect(
    val id: String,
    val displayName: String,
    /** The vanilla effect colour, used as the potion colour. */
    val color: Int,
    /** Bukkit `PotionEffectType` key. */
    val effectKey: String,
    /** Default brewing ingredient (a Material name) on top of an Awkward Potion. */
    val defaultIngredient: String,
) {
    HASTE("haste", "Haste", 0xD9C043, "haste", "AMETHYST_SHARD"),
    RESISTANCE("resistance", "Resistance", 0x9146F0, "resistance", "ARMADILLO_SCUTE");

    companion object {
        fun fromId(id: String?): BeaconPotionEffect? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** Normal, Redstone-extended and Glowstone-enhanced, like vanilla's plain / long_ / strong_ potions. */
enum class BeaconPotionVariant(val id: String, val namePrefix: String, val defaultSeconds: Int, val defaultLevel: Int) {
    NORMAL("normal", "", 180, 1),
    EXTENDED("extended", "Long ", 480, 1),
    ENHANCED("enhanced", "Strong ", 90, 2);

    companion object {
        fun fromId(id: String?): BeaconPotionVariant? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** One potion's strength: [level] is 1-based (Haste II = 2). */
data class PotionStrength(val durationSeconds: Int, val level: Int) {
    val durationTicks: Int get() = durationSeconds * 20
    val amplifier: Int get() = level - 1
}

/** The settings of one effect in one gamemode. */
data class BeaconEffectSettings(
    val enabled: Boolean,
    val ingredient: String,
    val strengths: Map<BeaconPotionVariant, PotionStrength>,
) {
    fun strength(variant: BeaconPotionVariant): PotionStrength =
        strengths[variant] ?: PotionStrength(variant.defaultSeconds, variant.defaultLevel)
}

/** Everything one gamemode configures under `beacon-potions.<gamemode>`. */
data class BeaconPotionSettings(
    val gamemode: ServerGamemode,
    val enabled: Boolean,
    val effects: Map<BeaconPotionEffect, BeaconEffectSettings>,
) {
    fun effect(effect: BeaconPotionEffect): BeaconEffectSettings =
        effects[effect] ?: BeaconPotionRules.defaultEffect(effect)

    fun isEnabled(effect: BeaconPotionEffect): Boolean = enabled && effect(effect).enabled
}

/** What a brewing stand slot holds, as far as beacon potions care. */
sealed interface BrewInput {
    data object Awkward : BrewInput
    data class Beacon(val effect: BeaconPotionEffect, val variant: BeaconPotionVariant, val gamemode: ServerGamemode?) : BrewInput
    data object Other : BrewInput
}

/** What a finished brew does to one slot. */
sealed interface BrewOutcome {
    /** Leave the slot unchanged: no splash/lingering, no wrong-gamemode or disabled potions. */
    data object Keep : BrewOutcome
    data class Make(val effect: BeaconPotionEffect, val variant: BeaconPotionVariant) : BrewOutcome
}

object BeaconPotionRules {

    const val EXTEND_INGREDIENT = "REDSTONE"
    const val ENHANCE_INGREDIENT = "GLOWSTONE_DUST"
    const val MAX_DURATION_SECONDS = 3600
    const val MAX_LEVEL = 5

    /**
     * Every vanilla brewing ingredient. A beacon potion ingredient may never be one of these,
     * so no vanilla recipe changes and no recipe matches twice.
     */
    val VANILLA_INGREDIENTS: Set<String> = setOf(
        "NETHER_WART", "REDSTONE", "GLOWSTONE_DUST", "FERMENTED_SPIDER_EYE", "GUNPOWDER",
        "DRAGON_BREATH", "SUGAR", "RABBIT_FOOT", "GLISTERING_MELON_SLICE", "SPIDER_EYE",
        "PUFFERFISH", "MAGMA_CREAM", "GOLDEN_CARROT", "BLAZE_POWDER", "GHAST_TEAR",
        "TURTLE_HELMET", "TURTLE_SCUTE", "PHANTOM_MEMBRANE", "BREEZE_ROD", "SLIME_BLOCK",
        "STONE", "COBWEB",
    )

    fun defaultEffect(effect: BeaconPotionEffect): BeaconEffectSettings = BeaconEffectSettings(
        enabled = true,
        ingredient = effect.defaultIngredient,
        strengths = BeaconPotionVariant.entries.associateWith { PotionStrength(it.defaultSeconds, it.defaultLevel) },
    )

    /** The custom item id of a potion, e.g. `haste_potion`, `long_haste_potion`, `strong_resistance_potion`. */
    fun itemId(effect: BeaconPotionEffect, variant: BeaconPotionVariant): String = when (variant) {
        BeaconPotionVariant.NORMAL -> "${effect.id}_potion"
        BeaconPotionVariant.EXTENDED -> "long_${effect.id}_potion"
        BeaconPotionVariant.ENHANCED -> "strong_${effect.id}_potion"
    }

    fun parseItemId(id: String?): Pair<BeaconPotionEffect, BeaconPotionVariant>? {
        if (id == null) return null
        for (effect in BeaconPotionEffect.entries) for (variant in BeaconPotionVariant.entries) {
            if (itemId(effect, variant) == id) return effect to variant
        }
        return null
    }

    fun displayName(effect: BeaconPotionEffect, variant: BeaconPotionVariant): String =
        "${variant.namePrefix}Potion of ${effect.displayName}"

    /** `3:00`, `1:30`, `12:05`. */
    fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    fun roman(level: Int): String = when (level) {
        1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; 5 -> "V"; else -> level.toString()
    }

    fun clampDuration(seconds: Int): Int = seconds.coerceIn(1, MAX_DURATION_SECONDS)

    fun clampLevel(level: Int): Int = level.coerceIn(1, MAX_LEVEL)

    /**
     * Why [ingredient] can't brew [effect] (null when it can): it must be a real item name,
     * not a vanilla brewing ingredient and not another enabled beacon potion's ingredient.
     */
    fun ingredientProblem(
        effect: BeaconPotionEffect,
        ingredient: String,
        others: Map<BeaconPotionEffect, String>,
    ): String? {
        if (ingredient.isBlank()) return "is empty"
        if (ingredient in VANILLA_INGREDIENTS) return "$ingredient is a vanilla brewing ingredient"
        val clash = others.entries.firstOrNull { it.key != effect && it.value == ingredient }
        if (clash != null) return "$ingredient is already the ${clash.key.id} ingredient"
        return null
    }

    /**
     * The result of one finished brew in one slot whose input or vanilla result is a beacon
     * potion. Awkward Potion + the effect's ingredient makes the normal potion, the normal
     * potion + Redstone the extended one, + Glowstone the enhanced one. Anything else that
     * touches a beacon potion (Gunpowder, Dragon's Breath, Fermented Spider Eye, a second
     * Redstone/Glowstone, a potion brewed for another gamemode, a disabled effect) keeps the
     * slot unchanged, so splash and lingering versions can never be brewed.
     */
    fun brew(input: BrewInput, ingredient: String, settings: BeaconPotionSettings): BrewOutcome = when (input) {
        BrewInput.Awkward -> {
            val effect = BeaconPotionEffect.entries.firstOrNull {
                settings.isEnabled(it) && settings.effect(it).ingredient == ingredient
            }
            if (effect == null) BrewOutcome.Keep else BrewOutcome.Make(effect, BeaconPotionVariant.NORMAL)
        }
        is BrewInput.Beacon -> when {
            input.gamemode != null && input.gamemode != settings.gamemode -> BrewOutcome.Keep
            !settings.isEnabled(input.effect) -> BrewOutcome.Keep
            input.variant != BeaconPotionVariant.NORMAL -> BrewOutcome.Keep
            ingredient == EXTEND_INGREDIENT -> BrewOutcome.Make(input.effect, BeaconPotionVariant.EXTENDED)
            ingredient == ENHANCE_INGREDIENT -> BrewOutcome.Make(input.effect, BeaconPotionVariant.ENHANCED)
            else -> BrewOutcome.Keep
        }
        BrewInput.Other -> BrewOutcome.Keep
    }

    /**
     * Why [potion] (brewed for [potionGamemode]) can't be drunk in [settings]' gamemode, or
     * null when it can.
     */
    fun drinkProblem(effect: BeaconPotionEffect, potionGamemode: ServerGamemode?, settings: BeaconPotionSettings): String? = when {
        potionGamemode != null && potionGamemode != settings.gamemode ->
            "This potion was brewed for ${potionGamemode.displayName} and can't be drunk on ${settings.gamemode.displayName}."
        !settings.isEnabled(effect) ->
            "Potions of ${effect.displayName} are disabled on ${settings.gamemode.displayName}."
        else -> null
    }
}
