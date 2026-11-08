// Checks the Bukkit-free Mobs OFF rules (issue #1421). The Gradle build has no test source
// set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/mob-visibility-check.init.gradle.kts mobVisibilityCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.mobvisibility.MobVisibilityRules
import com.liam.joshymc.mobvisibility.MobVisibilityRules.Kind
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

/** Hidden from a Mobs OFF player? */
private fun hidden(type: String, targetIsPlayer: Boolean = false, angry: Boolean = false, exempt: Boolean = false): Boolean {
    val kind = MobVisibilityRules.kindOf(type)
    val aggressive = MobVisibilityRules.isAggressive(targetIsPlayer, angry)
    return MobVisibilityRules.shouldHide(kind, exempt, aggressive)
}

fun main() {
    // Hostile: always hidden, peaceful or not
    for (type in listOf("ZOMBIE", "SKELETON", "CREEPER", "SPIDER", "CAVE_SPIDER", "ENDERMITE",
            "PIGLIN_BRUTE", "HOGLIN", "WITCH", "GHAST", "WARDEN", "SOME_FUTURE_MOB")) {
        check("$type is hostile", MobVisibilityRules.kindOf(type) == Kind.HOSTILE)
        check("$type hidden while peaceful", hidden(type))
        check("$type hidden while aggressive", hidden(type, targetIsPlayer = true))
    }

    // Neutral: visible while peaceful, hidden while aggressive
    for (type in listOf("ENDERMAN", "PIGLIN", "ZOMBIFIED_PIGLIN", "WOLF", "BEE", "IRON_GOLEM",
            "POLAR_BEAR", "LLAMA", "TRADER_LLAMA", "PANDA", "GOAT", "FOX", "DOLPHIN")) {
        check("$type is neutral", MobVisibilityRules.kindOf(type) == Kind.NEUTRAL)
        check("peaceful $type visible", !hidden(type))
        check("$type targeting a player hidden", hidden(type, targetIsPlayer = true))
        check("angry $type hidden", hidden(type, angry = true))
        check("tamed/exempt aggressive $type visible", !hidden(type, targetIsPlayer = true, exempt = true))
    }

    // Passive: always visible
    for (type in listOf("COW", "SHEEP", "PIG", "CHICKEN", "VILLAGER", "AXOLOTL", "CAT", "HORSE",
            "SNOW_GOLEM", "COPPER_GOLEM", "HAPPY_GHAST", "ARMOR_STAND")) {
        check("$type is passive", MobVisibilityRules.kindOf(type) == Kind.PASSIVE)
        check("$type visible", !hidden(type))
        check("$type visible even with a target", !hidden(type, targetIsPlayer = true, angry = true))
    }
    check("no type is both passive and neutral",
        MobVisibilityRules.PASSIVE_TYPES.intersect(MobVisibilityRules.NEUTRAL_TYPES).isEmpty())
    check("exempt hostile (xray helper) visible", !hidden("SHULKER", exempt = true))

    // Watch list: kept while aggressive, dropped after enough calm checks
    check("aggressive resets the calm count", MobVisibilityRules.nextCalmCount(true, 3) == 0)
    var calm: Int? = 0
    var steps = 0
    while (calm != null) {
        calm = MobVisibilityRules.nextCalmCount(false, calm)
        steps++
    }
    check("calm mob unwatched after the configured checks", steps == MobVisibilityRules.CALM_CHECKS_BEFORE_UNWATCH)

    // Aggression lifecycle: provoked -> hidden, calms down -> visible again
    val states = listOf(false to false, true to false, true to true, false to true, false to false)
    val seen = states.map { (target, angry) -> hidden("ENDERMAN", target, angry) }
    check("enderman lifecycle visible, hidden, hidden, hidden, visible", seen == listOf(false, true, true, true, false))

    println("Mobs OFF visibility checks passed ($checks checks).")
}
