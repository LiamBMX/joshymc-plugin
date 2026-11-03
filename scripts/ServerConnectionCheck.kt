// Checks the connection info shown by /ip and the rotating ServerBar (issue #1359): defaults,
// independent Java/Bedrock ports, placeholder filling, and that the bundled config.yml reads
// both ports from server-info instead of hardcoding them in the ServerBar.
// The Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/server-connection-check.init.gradle.kts serverConnectionCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.util.ServerConnectionInfo
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

fun main() {
    // Defaults: Java stays on 25565, Bedrock is 8160.
    val defaults = ServerConnectionInfo.of(null, null, null)
    check("default ip", defaults.ip == "play.joshymc.net")
    check("default java port", defaults.javaPort == 25565)
    check("default bedrock port", defaults.bedrockPort == 8160)

    // The ports are independent.
    val custom = ServerConnectionInfo.of("mc.example.net", 30000, 19132)
    check("custom java port kept", custom.javaPort == 30000)
    check("custom bedrock port kept", custom.bedrockPort == 19132)
    check("blank ip falls back", ServerConnectionInfo.of("  ", 1, 2).ip == "play.joshymc.net")
    check("out-of-range java port falls back", ServerConnectionInfo.of(null, 70000, null).javaPort == 25565)
    check("zero bedrock port falls back", ServerConnectionInfo.of(null, null, 0).bedrockPort == 8160)

    // Placeholders.
    check(
        "fill replaces every placeholder",
        custom.fill("{ip}|{java_port}|{bedrock_port}|{ip}") == "mc.example.net|30000|19132|mc.example.net",
    )
    check("fill leaves other text", custom.fill("&fPort: &e{port}") == "&fPort: &e{port}")

    // Bundled config.yml, read as text (Paper's YamlConfiguration needs Java 25 to load).
    val lines = File("src/main/resources/config.yml").readLines()
    fun section(name: String): List<String> {
        val start = lines.indexOf("$name:")
        check("section $name exists", start >= 0)
        return lines.drop(start + 1).takeWhile { it.isBlank() || it.startsWith(" ") }
    }
    fun value(section: List<String>, key: String): String? =
        section.firstOrNull { it.startsWith("  $key:") }?.substringAfter(":")?.trim()?.removeSurrounding("\"")

    val serverInfo = section("server-info")
    val bundled = ServerConnectionInfo.of(
        value(serverInfo, "ip"),
        value(serverInfo, "port")?.toIntOrNull(),
        value(serverInfo, "bedrock-port")?.toIntOrNull(),
    )
    check("bundled ip", value(serverInfo, "ip") == "play.joshymc.net")
    check("bundled java port unchanged", value(serverInfo, "port") == "25565")
    check("bundled bedrock port", value(serverInfo, "bedrock-port") == "8160" && bundled.bedrockPort == 8160)

    val messages = section("rotating-bossbar")
        .dropWhile { it.trim() != "messages:" }.drop(1)
        .takeWhile { it.trimStart().startsWith("- ") }
        .map { it.trimStart().removePrefix("- ").trim().removeSurrounding("\"") }
    check("ServerBar messages found", messages.isNotEmpty())
    val invite = messages.singleOrNull { it.contains("INVITE YOUR FRIENDS") }
    check("invite ServerBar line exists", invite != null)
    check("invite line uses placeholders", invite!!.contains("{ip}") && invite.contains("{bedrock_port}"))
    check(
        "invite line renders the Bedrock port",
        bundled.fill(invite) == "&a&lINVITE YOUR FRIENDS &8» &fIP: &eplay.joshymc.net &7| &fBedrock Port: &e8160",
    )
    check("no ServerBar line hardcodes a port", messages.none { it.contains("25565") || it.contains("8160") })

    // The migration constants in Joshymc.kt replace exactly the old default line.
    val main = File("src/main/kotlin/com/liam/joshymc/Joshymc.kt").readText()
    check(
        "migration knows the old line",
        main.contains("\"&a&lINVITE YOUR FRIENDS &8» &fIP: &eplay.joshymc.net &7| &fPort: &e25565\""),
    )
    check("migration writes the bundled line", main.contains("\"${invite.replace("\\", "\\\\")}\""))

    println("ServerConnectionCheck: all $checks checks passed.")
}
