package com.liam.joshymc.mobvisibility

/**
 * Bukkit-free rules for the per-player Mobs OFF setting (`mob_visibility`, issues #1421, #1475,
 * #1481).
 *
 * Every non-player mob is one of three kinds, matched by `EntityType` name so a type missing
 * from the running API is simply never seen:
 *  - [Kind.PASSIVE]: never covered (always visible, normal interaction).
 *  - [Kind.NEUTRAL]: covered **whatever its aggression state** (issue #1475: provoked or not).
 *  - [Kind.HOSTILE]: covered. Anything implementing Bukkit's `Enemy` is hostile even if it
 *    were listed as passive, and anything not listed at all is hostile, so a mob added in a
 *    future version is covered rather than silently shown.
 *
 * "Covered" means, for a player with Mobs OFF: hidden from them, unable to target or hurt them,
 * and unable to be hurt by them. Tamed animals, villagers, armor stands, non-mob living entities
 * (Mannequin NPCs, combat-log NPCs), Citizens NPCs, xray helper shulkers and Mob Waves event
 * mobs are exempt: never covered, whatever their kind.
 */
object MobVisibilityRules {

    enum class Kind { PASSIVE, NEUTRAL, HOSTILE }

    /** Never covered (issue #1018 list, minus IRON_GOLEM and SULFUR_CUBE, which are neutral). */
    val PASSIVE_TYPES: Set<String> = setOf(
        "VILLAGER", "WANDERING_TRADER", "ALLAY", "ARMADILLO", "AXOLOTL", "BAT",
        "CAMEL", "CAT", "CHICKEN", "COD", "COW", "DONKEY", "FROG", "GLOW_SQUID",
        "HAPPY_GHAST", "HORSE", "MOOSHROOM", "MULE", "OCELOT", "PARROT", "PIG",
        "RABBIT", "SALMON", "SHEEP", "SNIFFER", "SNOW_GOLEM", "SQUID", "STRIDER",
        "TADPOLE", "TROPICAL_FISH", "TURTLE", "COPPER_GOLEM", "ARMOR_STAND"
    )

    /**
     * Neutral mobs that can retaliate or turn hostile. Covered exactly like hostile mobs;
     * the list only documents the policy (and keeps them covered if a future API stopped
     * marking one of them as `Enemy`).
     */
    val NEUTRAL_TYPES: Set<String> = setOf(
        "ENDERMAN", "PIGLIN", "ZOMBIFIED_PIGLIN", "WOLF", "BEE", "IRON_GOLEM",
        "POLAR_BEAR", "LLAMA", "TRADER_LLAMA", "PANDA", "GOAT", "FOX", "DOLPHIN",
        "NAUTILUS", "SULFUR_CUBE"
    )

    /**
     * Hostile types named explicitly (issue #1481) so the ambiguous ones are a decision, not an
     * accident: bosses, and mobs that hurt players without implementing `Enemy` (Pufferfish).
     * Everything else hostile is caught by `Enemy` or by being unlisted.
     */
    val HOSTILE_TYPES: Set<String> = setOf(
        "ENDER_DRAGON", "WITHER", "WARDEN", "ELDER_GUARDIAN", "PUFFERFISH", "SHULKER",
        "SLIME", "MAGMA_CUBE", "PHANTOM", "GHAST", "HOGLIN", "ZOGLIN", "CREAKING"
    )

    /** Classify a mob type. [isEnemy] is the entity implementing Bukkit's `Enemy` interface. */
    fun kindOf(typeName: String, isEnemy: Boolean = false): Kind = when {
        typeName in NEUTRAL_TYPES -> Kind.NEUTRAL
        typeName in HOSTILE_TYPES -> Kind.HOSTILE
        isEnemy -> Kind.HOSTILE
        typeName in PASSIVE_TYPES -> Kind.PASSIVE
        else -> Kind.HOSTILE
    }

    /** Whether a mob of this kind is covered by Mobs OFF (hidden, harmless, unhittable). */
    fun covers(kind: Kind, exempt: Boolean): Boolean = !exempt && kind != Kind.PASSIVE

    /**
     * Whether a hit is blocked. [victimMobsOff] / [attackerMobsOff]: the player side has
     * Mobs OFF; [mobCovered]: the mob side is covered. Exactly one side is a player and the
     * other a mob; a hit between two players or two mobs is never judged here.
     *  - mob → Mobs OFF player: blocked (melee, projectile, explosion, fangs, beams, ...);
     *  - Mobs OFF player → covered mob: blocked, so hidden mobs can't be farmed.
     */
    fun blocksMobHittingPlayer(mobCovered: Boolean, victimMobsOff: Boolean): Boolean =
        mobCovered && victimMobsOff

    fun blocksPlayerHittingMob(attackerMobsOff: Boolean, mobCovered: Boolean): Boolean =
        attackerMobsOff && mobCovered

    /**
     * One side of a hit (damage, projectile impact, knockback, ignition), after attribution to
     * the responsible entity (issue #1481). [NONE] = no attributable entity (fall, lava, fire
     * ticks, drowning, poison ticks), [OTHER] = anything that isn't a player or a covered mob.
     */
    enum class Party { NONE, PLAYER_MOBS_ON, PLAYER_MOBS_OFF, COVERED_MOB, OTHER }

    /**
     * The one harm rule every listener path uses: a covered mob never harms a Mobs OFF player,
     * and a Mobs OFF player never harms a covered mob. Everything else (PvP, players' TNT and
     * potions, the environment, mob vs mob, Mobs ON players) is left alone.
     */
    fun blocksHarm(source: Party, victim: Party): Boolean =
        (source == Party.COVERED_MOB && victim == Party.PLAYER_MOBS_OFF) ||
            (source == Party.PLAYER_MOBS_OFF && victim == Party.COVERED_MOB)

    /** Whether a mob may keep / acquire a player as its target. */
    fun allowsTarget(mobCovered: Boolean, targetMobsOff: Boolean): Boolean =
        !(mobCovered && targetMobsOff)

    /**
     * Potion-effect causes with no source entity that only a mob produces against a player
     * (Elder Guardian / mob melee effects use ATTACK, the Warden's Darkness uses WARDEN).
     * Blocked for Mobs OFF players outside hosted games.
     */
    val MOB_EFFECT_CAUSES: Set<String> = setOf("ATTACK", "WARDEN")

    fun blocksEffect(causeName: String, mobsOff: Boolean, inHostedGame: Boolean): Boolean =
        mobsOff && !inHostedGame && causeName in MOB_EFFECT_CAUSES

    /** Backstop sweep near Mobs OFF players: re-hides anything missed, drops stale targets. */
    const val SWEEP_INTERVAL_TICKS = 40L

    const val SWEEP_RADIUS = 64.0
}
