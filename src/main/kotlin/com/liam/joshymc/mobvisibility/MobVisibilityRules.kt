package com.liam.joshymc.mobvisibility

/**
 * Bukkit-free rules for the per-player Mobs OFF setting (`mob_visibility`, issue #1421).
 *
 * Every non-player living entity is one of three kinds, matched by `EntityType` name so a
 * type missing from the running API is simply never seen:
 *  - [Kind.PASSIVE]: always visible (the issue #1018 passive list; unchanged).
 *  - [Kind.NEUTRAL]: visible while peaceful, hidden only while aggressive.
 *  - [Kind.HOSTILE]: always hidden. Anything not listed is hostile, so a mob added in a
 *    future version is hidden like before rather than silently shown.
 *
 * Tamed animals, armor stands, villagers and helper entities (xray shulkers) are exempt:
 * always visible, whatever their kind.
 */
object MobVisibilityRules {

    enum class Kind { PASSIVE, NEUTRAL, HOSTILE }

    /** Always visible (issue #1018 list, minus IRON_GOLEM and SULFUR_CUBE, which are neutral now). */
    val PASSIVE_TYPES: Set<String> = setOf(
        "VILLAGER", "WANDERING_TRADER", "ALLAY", "ARMADILLO", "AXOLOTL", "BAT",
        "CAMEL", "CAT", "CHICKEN", "COD", "COW", "DONKEY", "FROG", "GLOW_SQUID",
        "HAPPY_GHAST", "HORSE", "MOOSHROOM", "MULE", "OCELOT", "PARROT", "PIG",
        "RABBIT", "SALMON", "SHEEP", "SNIFFER", "SNOW_GOLEM", "SQUID", "STRIDER",
        "TADPOLE", "TROPICAL_FISH", "TURTLE", "COPPER_GOLEM", "ARMOR_STAND"
    )

    /**
     * Visible while peaceful, hidden while aggressive. Spiders, cave spiders and endermites
     * are deliberately absent: they're treated as hostile (always hidden).
     */
    val NEUTRAL_TYPES: Set<String> = setOf(
        "ENDERMAN", "PIGLIN", "ZOMBIFIED_PIGLIN", "WOLF", "BEE", "IRON_GOLEM",
        "POLAR_BEAR", "LLAMA", "TRADER_LLAMA", "PANDA", "GOAT", "FOX", "DOLPHIN",
        "NAUTILUS", "SULFUR_CUBE"
    )

    fun kindOf(typeName: String): Kind = when (typeName) {
        in PASSIVE_TYPES -> Kind.PASSIVE
        in NEUTRAL_TYPES -> Kind.NEUTRAL
        else -> Kind.HOSTILE
    }

    /**
     * Whether a mob with these facts is currently aggressive. [targetIsPlayer] is the mob's
     * live target being a player; [angry] is the type's own anger state (Wolf/Zombified
     * Piglin `isAngry`, Bee anger > 0, a screaming Enderman). Hunting other mobs (a wolf
     * chasing sheep, an iron golem fighting zombies) is not aggression for this setting.
     */
    fun isAggressive(targetIsPlayer: Boolean, angry: Boolean): Boolean = targetIsPlayer || angry

    /** Whether the entity should be hidden from a player who has Mobs OFF. */
    fun shouldHide(kind: Kind, exempt: Boolean, aggressive: Boolean): Boolean = when {
        exempt -> false
        kind == Kind.PASSIVE -> false
        kind == Kind.NEUTRAL -> aggressive
        else -> true
    }

    /** Checks of the watch task (every [WATCH_INTERVAL_TICKS]) a calm neutral mob stays watched. */
    const val CALM_CHECKS_BEFORE_UNWATCH = 6

    const val WATCH_INTERVAL_TICKS = 10L

    /** Backstop sweep near Mobs OFF players, catching aggression no event reported. */
    const val SWEEP_INTERVAL_TICKS = 40L

    const val SWEEP_RADIUS = 64.0

    /** Hard cap on watched mobs so a mob farm can't grow the set without bound. */
    const val MAX_WATCHED = 4096

    /**
     * After a check of a watched neutral mob: keep watching while it's aggressive, and for
     * [CALM_CHECKS_BEFORE_UNWATCH] calm checks after (anger that starts a moment after a
     * hit or a target event). Returns the new calm count, or null to stop watching.
     */
    fun nextCalmCount(aggressive: Boolean, calmChecks: Int): Int? = when {
        aggressive -> 0
        calmChecks + 1 >= CALM_CHECKS_BEFORE_UNWATCH -> null
        else -> calmChecks + 1
    }
}
