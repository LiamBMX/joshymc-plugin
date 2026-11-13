// Checks the player rank security fixes from audit #1505 (issue #1521):
//   P1-1  holding joshymc.fly no longer exempts a player from the internal anticheat fly check;
//   P1-2  self-use /heal and /feed (Survival donor perks) are refused outside Survival in the
//         plugin itself, whatever LuckPerms grants.
// The Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/rank-security-check.init.gradle.kts rankSecurityCheck
//
// Paper's classes target Java 25 and this runs on the Java 21 toolchain, so it never loads a
// Bukkit class: the Bukkit-free rules are called directly, the wiring is checked by source text.
// Exits non-zero on the first failed check. Live-server behavior is listed in the PR.

import com.liam.joshymc.flight.FlightRestore
import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.gamemode.SurvivalSelfPerks
import com.liam.joshymc.gamemode.SurvivalSelfPerks.Decision
import com.liam.joshymc.manager.FlightCheckRules
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private fun state(allow: Boolean = false, flying: Boolean = false, gliding: Boolean = false, bypass: Boolean = false) =
    FlightCheckRules.State(allowFlight = allow, flying = flying, gliding = gliding, explicitBypass = bypass)

/** The body of `class <name>` up to the next top-level declaration. */
private fun classBody(src: String, name: String): String {
    val start = src.indexOf("class $name(")
    check("$name exists", start >= 0)
    val next = Regex("\n(class|object|private fun|fun) ").find(src, start + 1)?.range?.first ?: src.length
    return src.substring(start, next)
}

@Suppress("UNCHECKED_CAST")
fun main() {
    // ══ P1-1: flight anticheat ═════════════════════════════════
    val anticheat = source("manager/AntiCheatManager.kt")
    val flightFn = anticheat.substringAfter("private fun checkFlight(").substringBefore("// ── Speed")

    // 1. No flight permission, not flying → checked.
    check("1. no flight: checked", !FlightCheckRules.exempt(state()))
    // 2/14. joshymc.fly (donor rank, Fly Voucher) but flight not enabled → checked. The permission
    // isn't an input of the rule, and the check never reads it.
    check("2. joshymc.fly is not read by the fly check", "joshymc.fly\"" !in flightFn)
    check("2. joshymc.fly is not read anywhere in the anticheat", "\"joshymc.fly\"" !in anticheat)
    check("2. the rule has no fly-permission input",
        FlightCheckRules.State::class.java.declaredFields.none { it.name.contains("permission", ignoreCase = true) })
    // 3/4. /fly (permanent or Fly Voucher flight) sets allowFlight → exempt while enabled.
    check("3. legitimate /fly flight (allowFlight) exempt", FlightCheckRules.exempt(state(allow = true)))
    check("3. flying exempt", FlightCheckRules.exempt(state(allow = true, flying = true)))
    check("4. /fly sets allowFlight server side", "target.allowFlight = !target.allowFlight" in source("command/EssentialsCommands.kt"))
    // 5. Voucher expiry: FlightTimeManager clears allowFlight after the grace → checked again.
    val flightTime = source("flight/FlightTimeManager.kt")
    val finish = flightTime.substringAfter("private fun finish(").substringBefore("private fun markEnded(")
    check("5. expired flight time clears allowFlight", "player.allowFlight = false" in finish)
    check("5. after expiry: checked", !FlightCheckRules.exempt(state()))
    // 6/8. Without /fly access flight is never restored on join, world change or gamemode switch.
    val restoreBlock = flightTime.substringAfter("fun restoreBlock(").substringBefore("private fun gapBelow(")
    check("6/8. no permission and no timed flight blocks restore",
        "!player.hasPermission(\"joshymc.fly\") && !hasTemporaryFlight(player)) return FlightRestore.Block.NO_PERMISSION" in restoreBlock)
    check("6/8. a blocked restore never turns flight on",
        FlightRestore.decide(FlightRestore.Input(true, false, false, FlightRestore.Block.NO_PERMISSION, 1)) !is FlightRestore.Decision.Restore)
    // 7. Reconnect with legitimate flight: restored (allowFlight set again) → exempt.
    check("7. reconnect with access restores flight",
        FlightRestore.decide(FlightRestore.Input(true, false, false, null, 1)) == FlightRestore.Decision.Restore(flying = true))
    // 9/10. Survival flight never applies outside Survival (gamemode-locked restore, strip on arrival).
    check("9. Survival flight stripped outside Survival", "fun stripOutsideSurvival(" in flightTime && "if (inSurvival(player.world)) return" in flightTime)
    check("9/10. restore refused where Survival is locked",
        "!plugin.gamemodeLockManager.isAllowed(GamemodeScope.SURVIVAL, player.world)) return FlightRestore.Block.GAMEMODE_LOCKED" in restoreBlock)
    // 11/12. Creative and Spectator: the server sets allowFlight for them.
    check("11/12. Creative/Spectator (allowFlight) exempt", FlightCheckRules.exempt(state(allow = true)))
    check("elytra gliding exempt", FlightCheckRules.exempt(state(gliding = true)))
    // 13. Explicit staff bypass.
    check("13. explicit bypass exempt", FlightCheckRules.exempt(state(bypass = true)))
    check("13. the bypass node is joshymc.anticheat.fly.bypass", FlightCheckRules.BYPASS_PERMISSION == "joshymc.anticheat.fly.bypass")
    check("13. the fly check reads the bypass node", "player.hasPermission(FlightCheckRules.BYPASS_PERMISSION)" in flightFn)
    check("the fly check uses the rule", "FlightCheckRules.exempt(" in flightFn)
    // 14. Donor ranks can't reach the bypass: op by default, no children, not implied by joshymc.fly.
    val pluginYml = File("src/main/resources/plugin.yml").reader(Charsets.UTF_8).use { Yaml().load<Map<String, Any?>>(it) }
    val perms = pluginYml["permissions"] as Map<String, Any?>
    val bypass = perms["joshymc.anticheat.fly.bypass"] as? Map<String, Any?>
    check("14. bypass node declared", bypass != null)
    check("14. bypass node is op only", bypass?.get("default")?.toString() == "op")
    check("14. bypass node no longer documented as implied by joshymc.fly", bypass?.get("description")?.toString()?.contains("implicitly") != true)
    for ((node, value) in perms) {
        val children = (value as? Map<String, Any?>)?.get("children") as? Map<String, Any?> ?: continue
        if (children["joshymc.anticheat.fly.bypass"] == true) {
            check("14. $node granting the fly bypass is op/false", (value["default"]?.toString() ?: "op") in setOf("op", "false"))
        }
    }
    val flyNode = perms["joshymc.fly"] as? Map<String, Any?>
    check("14. joshymc.fly has no anticheat child", (flyNode?.get("children") as? Map<*, *>)?.keys?.none { it.toString().startsWith("joshymc.anticheat") } ?: true)
    // 15. Unauthorized flight (no server flight state, no bypass) is still checked and flagged.
    check("15. unauthorized flight checked", !FlightCheckRules.exempt(state()))
    check("15. hovering is still flagged", "flag(player, CheckType.FLIGHT, 2.0, \"air=" in flightFn)
    check("15. ascending is still flagged", "flag(player, CheckType.FLIGHT, 2.0, \"ascend air=" in flightFn)

    // ══ P1-2: Survival self-use perks ══════════════════════════
    // 16/17/30. Survival self-use works.
    check("16/17. self-use allowed on Survival", SurvivalSelfPerks.decide(ServerGamemode.SURVIVAL, selfUse = true) == Decision.ALLOWED)
    // 19/20/21. Refused on Lifesteal: no permission is an input, so global groups/wildcards can't help.
    check("19/20. self-use refused on Lifesteal", SurvivalSelfPerks.decide(ServerGamemode.LIFESTEAL, selfUse = true) == Decision.SURVIVAL_ONLY)
    check("21. every non-Survival gamemode refuses self-use",
        ServerGamemode.entries.filter { it != ServerGamemode.SURVIVAL }.all { SurvivalSelfPerks.decide(it, true) == Decision.SURVIVAL_ONLY })
    check("21. the rule takes no permission input",
        SurvivalSelfPerks::class.java.declaredMethods.filter { it.name == "decide" }.all { m -> m.parameterTypes.none { it == java.util.Set::class.java || it == String::class.java } })
    // 25. Staff acting on another player (needs .others) isn't the self perk.
    check("25. healing another player isn't the self perk (Lifesteal)", SurvivalSelfPerks.decide(ServerGamemode.LIFESTEAL, selfUse = false) == Decision.ALLOWED)
    check("25. healing another player isn't the self perk (Survival)", SurvivalSelfPerks.decide(ServerGamemode.SURVIVAL, selfUse = false) == Decision.ALLOWED)
    check("8. message names the gamemode, not a permission",
        SurvivalSelfPerks.SURVIVAL_ONLY_MESSAGE == "This command is only available on Survival." && "permission" !in SurvivalSelfPerks.SURVIVAL_ONLY_MESSAGE.lowercase())

    val essentials = source("command/EssentialsCommands.kt")
    val helper = essentials.substringAfter("private fun refusesOutsideSurvival(").substringBefore("\n}\n")
    check("the gate reads the target's gamemode", "SurvivalSelfPerks.decide(plugin.gamemodeLockManager.gamemodeOf(target), selfUse)" in helper)
    check("self-use = the sender is the target", "val selfUse = sender is Player && sender == target" in helper)
    check("the gate replies with the gamemode message", "Msg.error(sender, SurvivalSelfPerks.SURVIVAL_ONLY_MESSAGE)" in helper)
    for (name in listOf("HealCommand", "FeedCommand")) {
        val body = classBody(essentials, name)
        val gate = body.indexOf("if (refusesOutsideSurvival(plugin, sender, target)) return true")
        check("$name runs the Survival gate", gate >= 0)
        check("$name gates after resolving the target", gate > body.indexOf("val target = resolveTarget(sender, args) ?: return true"))
        // 18. The gate runs before the cooldown is touched, and the cooldown is unchanged.
        val cooldown = body.indexOf("cooldowns[sender.uniqueId] = now")
        check("18. $name gate runs before the cooldown is used", cooldown > gate)
        check("18. $name cooldown is still 3 minutes", "const val COOLDOWN_MS = 3 * 60 * 1000L" in body)
        // 24. Ranked and combat protection still run.
        check("24. $name still refuses ranked participants", "plugin.lifestealRankedManager.refusesStaffAction(sender, target" in body)
        // 25. Other players still need the .others node.
        val node = if (name == "HealCommand") "heal" else "feed"
        check("25. $name others still need joshymc.$node.others", "!sender.hasPermission(\"joshymc.$node.others\") && !isSelf(sender, args[0])" in body)
    }
    check("24. /heal still refuses combat-tagged targets", "if (plugin.combatManager.isTagged(target))" in classBody(essentials, "HealCommand"))

    // 22/23. Aliases, `joshymc:` names, /sudo, NPC and portal commands all run the one executor,
    // and nothing else heals/feeds through another path.
    val commands = source("manager/CommandManager.kt")
    check("22. /heal has one executor", "plugin.getCommand(\"heal\")?.let { it.setExecutor(healCmd)" in commands)
    check("22. /feed has one executor", "plugin.getCommand(\"feed\")?.let { it.setExecutor(feedCmd)" in commands)
    val kotlinFiles = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
    for (file in kotlinFiles) {
        val text = file.readText()
        check("23. ${file.name} doesn't construct HealCommand elsewhere", file.name in setOf("EssentialsCommands.kt", "CommandManager.kt") || "HealCommand(" !in text)
        check("23. ${file.name} doesn't construct FeedCommand elsewhere", file.name in setOf("EssentialsCommands.kt", "CommandManager.kt") || "FeedCommand(" !in text)
    }
    // Still NETWORK (staff use them on every server); the self perk is gated in the executor.
    check("/heal stays NETWORK", GamemodeAssignments.COMMANDS["heal"] == GamemodeScope.NETWORK)
    check("/feed stays NETWORK", GamemodeAssignments.COMMANDS["feed"] == GamemodeScope.NETWORK)
    // 26. Normal food and regeneration: no listener touches them because of this change.
    check("26. the gate is only in the commands", kotlinFiles.filter { "SurvivalSelfPerks" in it.readText() }.map { it.name }.toSet() ==
        setOf("SurvivalSelfPerks.kt", "EssentialsCommands.kt"))

    // 27/28. Rank perks and Fly Vouchers untouched: /fly and /flytime stay Survival.
    check("27. /fly stays a Survival perk", GamemodeAssignments.COMMANDS["fly"] == GamemodeScope.SURVIVAL)
    check("28. /flytime stays a Survival perk", GamemodeAssignments.COMMANDS["flytime"] == GamemodeScope.SURVIVAL)

    println("OK: $checks rank security checks passed.")
}
