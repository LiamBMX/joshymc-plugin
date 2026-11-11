// Checks the Bukkit-free Mobs OFF rules (issues #1421, #1475). The Gradle build has no test
// source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/mob-visibility-check.init.gradle.kts mobVisibilityCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.mobvisibility.MobVisibilityRules
import com.liam.joshymc.mobvisibility.MobVisibilityRules.Kind
import com.liam.joshymc.mobvisibility.MobVisibilityRules.Party
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

/** Covered (hidden + harmless + unhittable) for a Mobs OFF player? */
private fun covered(type: String, enemy: Boolean = false, exempt: Boolean = false): Boolean =
    MobVisibilityRules.covers(MobVisibilityRules.kindOf(type, enemy), exempt)

fun main() {
    // Hostile: covered, also unlisted / future types
    val hostile = listOf("ZOMBIE", "ZOMBIE_VILLAGER", "HUSK", "DROWNED", "SKELETON", "STRAY", "BOGGED",
        "WITHER_SKELETON", "CREEPER", "SPIDER", "CAVE_SPIDER", "WITCH", "SLIME", "MAGMA_CUBE", "PHANTOM",
        "PILLAGER", "VINDICATOR", "EVOKER", "RAVAGER", "GUARDIAN", "ELDER_GUARDIAN", "BLAZE", "GHAST",
        "PIGLIN_BRUTE", "HOGLIN", "ZOGLIN", "SILVERFISH", "ENDERMITE", "SHULKER", "WARDEN", "BREEZE",
        "SOME_FUTURE_MOB")
    for (type in hostile) {
        check("$type is hostile", MobVisibilityRules.kindOf(type) == Kind.HOSTILE)
        check("$type covered", covered(type))
    }

    // Neutral (#1475): covered whether provoked or not (no aggression input exists any more)
    val neutral = listOf("ENDERMAN", "PIGLIN", "ZOMBIFIED_PIGLIN", "WOLF", "BEE", "IRON_GOLEM",
        "POLAR_BEAR", "LLAMA", "TRADER_LLAMA", "PANDA", "GOAT", "FOX", "DOLPHIN")
    for (type in neutral) {
        check("$type is neutral", MobVisibilityRules.kindOf(type) == Kind.NEUTRAL)
        check("never-provoked $type covered", covered(type))
        check("$type marked Enemy still neutral and covered", covered(type, enemy = true))
        check("tamed/exempt $type not covered", !covered(type, exempt = true))
    }

    // Passive: never covered
    for (type in listOf("COW", "SHEEP", "PIG", "CHICKEN", "VILLAGER", "AXOLOTL", "CAT", "HORSE",
            "SNOW_GOLEM", "COPPER_GOLEM", "HAPPY_GHAST", "ARMOR_STAND")) {
        check("$type is passive", MobVisibilityRules.kindOf(type) == Kind.PASSIVE)
        check("$type not covered", !covered(type))
    }
    check("Enemy interface wins over the passive list", MobVisibilityRules.kindOf("COW", isEnemy = true) == Kind.HOSTILE)
    check("no type is both passive and neutral",
        MobVisibilityRules.PASSIVE_TYPES.intersect(MobVisibilityRules.NEUTRAL_TYPES).isEmpty())
    check("exempt hostile (xray helper, event mob) not covered", !covered("SHULKER", exempt = true))

    // Targeting: covered mob never targets a Mobs OFF player; Mobs ON players unchanged
    check("covered mob can't target Mobs OFF player", !MobVisibilityRules.allowsTarget(true, true))
    check("covered mob targets Mobs ON player", MobVisibilityRules.allowsTarget(true, false))
    check("passive/exempt mob may target anyone", MobVisibilityRules.allowsTarget(false, true))

    // Damage both directions
    check("covered mob hit on Mobs OFF player blocked", MobVisibilityRules.blocksMobHittingPlayer(true, true))
    check("covered mob hit on Mobs ON player allowed", !MobVisibilityRules.blocksMobHittingPlayer(true, false))
    check("exempt mob hit on Mobs OFF player allowed", !MobVisibilityRules.blocksMobHittingPlayer(false, true))
    check("Mobs OFF player can't farm a hidden mob", MobVisibilityRules.blocksPlayerHittingMob(true, true))
    check("Mobs ON player hits mobs normally", !MobVisibilityRules.blocksPlayerHittingMob(false, true))
    check("Mobs OFF player hits passive mobs normally", !MobVisibilityRules.blocksPlayerHittingMob(true, false))

    // Two nearby players with opposite settings, same zombie
    val zombie = covered("ZOMBIE")
    check("zombie targets the Mobs ON player", MobVisibilityRules.allowsTarget(zombie, false))
    check("zombie can't target the Mobs OFF player", !MobVisibilityRules.allowsTarget(zombie, true))

    // Source-less mob effects (Elder Guardian / mob melee = ATTACK, Warden Darkness = WARDEN)
    check("warden darkness blocked for Mobs OFF", MobVisibilityRules.blocksEffect("WARDEN", true, false))
    check("elder guardian fatigue blocked for Mobs OFF", MobVisibilityRules.blocksEffect("ATTACK", true, false))
    check("effects kept for Mobs ON", !MobVisibilityRules.blocksEffect("WARDEN", false, false))
    check("effects kept in a hosted game", !MobVisibilityRules.blocksEffect("ATTACK", true, true))
    for (cause in listOf("POTION_DRINK", "POTION_SPLASH", "PLUGIN", "BEACON", "COMMAND", "FOOD", "AREA_EFFECT_CLOUD")) {
        check("$cause effect never blocked", !MobVisibilityRules.blocksEffect(cause, true, false))
    }

    // Issue #1481: bosses and the non-Enemy hostiles are named explicitly
    for (type in listOf("ENDER_DRAGON", "WITHER", "WARDEN", "ELDER_GUARDIAN", "PUFFERFISH", "CREAKING")) {
        check("$type explicitly hostile", MobVisibilityRules.kindOf(type, isEnemy = false) == Kind.HOSTILE)
        check("$type covered even without Enemy", covered(type))
    }
    check("no type is both hostile and passive",
        MobVisibilityRules.HOSTILE_TYPES.intersect(MobVisibilityRules.PASSIVE_TYPES).isEmpty())
    check("no type is both hostile and neutral",
        MobVisibilityRules.HOSTILE_TYPES.intersect(MobVisibilityRules.NEUTRAL_TYPES).isEmpty())

    // Issue #1481: the one harm rule shared by damage, projectiles, knockback, fire, potions, clouds
    val all = Party.values().toList()
    for (source in all) for (victim in all) {
        val expected = (source == Party.COVERED_MOB && victim == Party.PLAYER_MOBS_OFF) ||
            (source == Party.PLAYER_MOBS_OFF && victim == Party.COVERED_MOB)
        check("blocksHarm($source -> $victim) == $expected", MobVisibilityRules.blocksHarm(source, victim) == expected)
    }
    check("zombie / skeleton arrow / creeper blast / bee / wolf / boss vs Mobs OFF blocked",
        MobVisibilityRules.blocksHarm(Party.COVERED_MOB, Party.PLAYER_MOBS_OFF))
    check("same mob vs Mobs ON player allowed", !MobVisibilityRules.blocksHarm(Party.COVERED_MOB, Party.PLAYER_MOBS_ON))
    check("PvP unaffected (OFF -> OFF)", !MobVisibilityRules.blocksHarm(Party.PLAYER_MOBS_OFF, Party.PLAYER_MOBS_OFF))
    check("PvP unaffected (ON -> OFF)", !MobVisibilityRules.blocksHarm(Party.PLAYER_MOBS_ON, Party.PLAYER_MOBS_OFF))
    check("PvP unaffected (OFF -> ON)", !MobVisibilityRules.blocksHarm(Party.PLAYER_MOBS_OFF, Party.PLAYER_MOBS_ON))
    check("environment unaffected", !MobVisibilityRules.blocksHarm(Party.NONE, Party.PLAYER_MOBS_OFF))
    check("passive / tamed / event mob vs Mobs OFF allowed", !MobVisibilityRules.blocksHarm(Party.OTHER, Party.PLAYER_MOBS_OFF))
    check("Mobs OFF player hits passive / tamed normally", !MobVisibilityRules.blocksHarm(Party.PLAYER_MOBS_OFF, Party.OTHER))
    check("Mobs OFF player can't farm a hidden mob", MobVisibilityRules.blocksHarm(Party.PLAYER_MOBS_OFF, Party.COVERED_MOB))
    check("Mobs ON player farms normally", !MobVisibilityRules.blocksHarm(Party.PLAYER_MOBS_ON, Party.COVERED_MOB))
    check("mob vs mob untouched", !MobVisibilityRules.blocksHarm(Party.COVERED_MOB, Party.COVERED_MOB))

    println("Mobs OFF checks passed ($checks checks).")
}
