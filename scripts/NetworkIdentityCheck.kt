// Checks the Bukkit-free network identity rules (issue #1323). The Gradle build has no test
// source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/network-check.init.gradle.kts networkIdentityCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.gamemode.ServerGamemode.LIFESTEAL
import com.liam.joshymc.gamemode.ServerGamemode.SURVIVAL
import com.liam.joshymc.network.NetworkIdentity
import com.liam.joshymc.network.NetworkIdentityConfig
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private fun resolve(id: Any?, primary: Any?, gamemode: ServerGamemode) =
    NetworkIdentityConfig.resolve(id, primary, gamemode)

fun main() {
    // Bundled defaults on Survival: unchanged behavior, no warnings.
    resolve("survival", true, SURVIVAL).let {
        check("survival default", it.identity == NetworkIdentity("survival", true))
        check("survival default has no problems", it.problems.isEmpty())
    }
    // Recommended Lifesteal config.
    resolve("lifesteal", false, LIFESTEAL).let {
        check("lifesteal config", it.identity == NetworkIdentity("lifesteal", false))
        check("lifesteal config has no problems", it.problems.isEmpty())
    }
    // Other ids work on any gamemode (future lobby, second survival...).
    resolve("lobby", false, SURVIVAL).let {
        check("lobby id allowed", it.identity == NetworkIdentity("lobby", false) && it.problems.isEmpty())
    }
    check("other id may be primary", resolve("survival-2", true, SURVIVAL).identity == NetworkIdentity("survival-2", true))

    // Normalization.
    check("trim + lower-case", NetworkIdentityConfig.normalize("  LifeSteal ") == "lifesteal")
    check("normalized in resolve", resolve(" Survival ", true, SURVIVAL).identity.serverId == "survival")
    check("digits/underscore/dash", NetworkIdentityConfig.normalize("eu_survival-2") == "eu_survival-2")

    // Validation.
    for (bad in listOf("survival smp", "-survival", "survival_", "surv!val", "a".repeat(33), "über")) {
        check("'$bad' rejected", NetworkIdentityConfig.normalize(bad) == null)
    }
    check("32 chars allowed", NetworkIdentityConfig.normalize("a".repeat(32)) != null)
    check("single char allowed", NetworkIdentityConfig.normalize("s") != null)
    resolve("Survival SMP", true, SURVIVAL).let {
        check("invalid id falls back to gamemode id", it.identity.serverId == "survival")
        check("invalid id keeps primary", it.identity.primary)
        check("invalid id is severe", it.problems.any { p -> p.severe })
    }
    check("non-string id falls back", resolve(42, false, LIFESTEAL).identity.serverId == "lifesteal")

    // Missing values.
    resolve(null, true, LIFESTEAL).let {
        check("missing id uses gamemode id", it.identity == NetworkIdentity("lifesteal", true))
        check("missing id is a warning", it.problems.size == 1 && !it.problems[0].severe)
    }
    check("blank id uses gamemode id", resolve("  ", false, SURVIVAL).identity.serverId == "survival")
    resolve("survival", null, SURVIVAL).let {
        check("missing primary = secondary", !it.identity.primary)
        check("missing primary is severe", it.problems.any { p -> p.severe })
    }
    check("non-boolean primary = secondary", !resolve("survival", "yes", SURVIVAL).identity.primary)

    // Id naming another gamemode: refused primary.
    resolve("survival", true, LIFESTEAL).let {
        check("copied survival config on lifesteal is not primary", it.identity == NetworkIdentity("survival", false))
        check("conflict is severe", it.problems.any { p -> p.severe })
    }
    resolve("lifesteal", true, SURVIVAL).let {
        check("lifesteal id on survival is not primary", !it.identity.primary)
    }
    // A Lifesteal server that is deliberately primary is allowed.
    check("lifesteal may be primary", resolve("lifesteal", true, LIFESTEAL).identity.primary)

    println("OK: $checks network identity checks passed.")
}
