// Checks the shared GUI icons (issue #1355): the GuiIcon registry, the two item classes, their
// wiring, the resource pack assets (unique models, vanilla stick untouched) and the Bedrock
// icons Geyser maps them with. Building an ItemStack needs a running server, so stack creation
// (GuiStyle.icon) is covered by the visual test steps in docs/gui-icons.md instead.
// The Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/gui-icon-check.init.gradle.kts guiIconCheck
//
// Exits non-zero on the first failed check.

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.liam.joshymc.bedrock.BedrockIconIndex
import com.liam.joshymc.bedrock.GeyserMappings
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.gui.GuiIcon
import com.liam.joshymc.item.impl.DiscordIcon
import com.liam.joshymc.item.impl.YoutubeIcon
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
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

private fun json(file: File): JsonObject = JsonParser.parseString(file.readText()).asJsonObject

private const val PACK = "resourcepack/assets"

fun main() {
    // ── Registry ───────────────────────────────────────────
    check("two icons", GuiIcon.entries.size == 2)
    check("discord id", GuiIcon.DISCORD.itemId == "discord_icon")
    check("youtube id", GuiIcon.YOUTUBE.itemId == "youtube_icon")
    check("item ids unique", GuiIcon.entries.map { it.itemId }.toSet().size == GuiIcon.entries.size)
    check("model keys unique", GuiIcon.entries.map { it.modelKey }.toSet().size == GuiIcon.entries.size)
    check("discord model key", GuiIcon.DISCORD.modelKey == "joshymc:discord_icon")
    check("youtube model key", GuiIcon.YOUTUBE.modelKey == "joshymc:youtube_icon")
    check("lookup by id", GuiIcon.fromItemId("discord_icon") == GuiIcon.DISCORD && GuiIcon.fromItemId("YOUTUBE_ICON") == GuiIcon.YOUTUBE)
    check("unknown id", GuiIcon.fromItemId("stick") == null && GuiIcon.fromItemId("gun") == null)

    // ── Item classes ───────────────────────────────────────
    val plain = PlainTextComponentSerializer.plainText()
    val discord = DiscordIcon()
    val youtube = YoutubeIcon()
    for ((item, icon) in listOf(discord to GuiIcon.DISCORD, youtube to GuiIcon.YOUTUBE)) {
        check("${icon.itemId}: id", item.id == icon.itemId)
        check("${icon.itemId}: base material is STICK", item.material == Material.STICK)
        check("${icon.itemId}: shared by every gamemode (no scope)", item.gamemodeScope == null)
        check("${icon.itemId}: bold", item.displayName.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE)
        check("${icon.itemId}: not italic", item.displayName.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE)
        check("${icon.itemId}: no glint", !item.hasGlint)
    }
    // &9&lDiscord / &c&lYouTube
    check("discord name", plain.serialize(discord.displayName) == "Discord" && discord.displayName.color() == NamedTextColor.BLUE)
    check("youtube name", plain.serialize(youtube.displayName) == "YouTube" && youtube.displayName.color() == NamedTextColor.RED)
    // Both gamemodes: no scope means /joshymc give and every menu work on Survival and Lifesteal.
    check("Survival and Lifesteal both exist", ServerGamemode.entries.map { it.id }.containsAll(listOf("survival", "lifesteal")))

    // ── Wiring ─────────────────────────────────────────────
    val itemManager = File("src/main/kotlin/com/liam/joshymc/manager/ItemManager.kt").readText()
    check("ItemManager registers DiscordIcon", "register(DiscordIcon())" in itemManager)
    check("ItemManager registers YoutubeIcon", "register(YoutubeIcon())" in itemManager)
    val listeners = File("src/main/kotlin/com/liam/joshymc/manager/ListenerManager.kt").readText()
    check("ListenerManager registers GuiIconListener", "GuiIconListener(plugin)" in listeners)
    val style = File("src/main/kotlin/com/liam/joshymc/gui/GuiStyle.kt").readText()
    check("GuiStyle.icon exists", Regex("""fun icon\(icon: GuiIcon, name: Component\? = null, lore: List<Component> = emptyList\(\)\): ItemStack""").containsMatchIn(style))
    check("GuiStyle.icon builds a new stack from the item", "createItemStack()" in style)
    // No other Kotlin source claims these model ids.
    val sources = File("src/main/kotlin").walk().filter { it.isFile && it.extension == "kt" }.toList()
    for (icon in GuiIcon.entries) {
        val owners = sources.filter { "\"${icon.itemId}\"" in it.readText() }.map { it.name }
        check("${icon.itemId} is only named in GuiIcon.kt (found in $owners)", owners == listOf("GuiIcon.kt"))
    }

    // ── Resource pack ──────────────────────────────────────
    check("vanilla stick item definition untouched", !File("$PACK/minecraft/items/stick.json").exists())
    check("vanilla stick model untouched", !File("$PACK/minecraft/models/item/stick.json").exists())
    check("vanilla stick texture untouched", !File("$PACK/minecraft/textures/item/stick.png").exists())
    for (icon in GuiIcon.entries) {
        val id = icon.itemId
        val definition = File("$PACK/joshymc/items/$id.json")
        check("$id: item definition exists", definition.isFile)
        val tree = json(definition).getAsJsonObject("model")
        check("$id: definition points at its own model", tree["type"].asString == "minecraft:model" && tree["model"].asString == "joshymc:item/$id/main")
        val model = File("$PACK/joshymc/models/item/$id/main.json")
        check("$id: model exists", model.isFile)
        val m = json(model)
        check("$id: model is 3D (elements)", m.getAsJsonArray("elements").size() in 20..300)
        check("$id: model has a gui display", m.getAsJsonObject("display").has("gui"))
        val textures = m.getAsJsonObject("textures").entrySet().map { it.value.asString }
        check("$id: textures all live in its own folder", textures.all { it.startsWith("joshymc:item/$id/") })
        for (t in textures.toSet()) {
            val path = t.removePrefix("joshymc:")
            check("$id: texture $t exists", File("$PACK/joshymc/textures/$path.png").isFile)
        }
        check("$id: named texture $id.png", File("$PACK/joshymc/textures/item/$id/$id.png").isFile)
        check("$id: art module", File("resourcepack/art/items/$id.py").isFile)
    }
    val definitions = File("$PACK/joshymc/items").walk().filter { it.isFile }.map { it.relativeTo(File("$PACK/joshymc/items")).path }.toList()
    for (icon in GuiIcon.entries) {
        check("${icon.itemId}: exactly one item definition", definitions.count { it.removeSuffix(".json") == icon.itemId } == 1)
    }

    // ── Bedrock (Geyser) ───────────────────────────────────
    val index = BedrockIconIndex.parse(File("src/main/resources/bedrock/icons.json").readText())
    for (icon in GuiIcon.entries) {
        val entry = index.items[icon.modelKey]
        check("${icon.itemId}: Bedrock icon indexed", entry != null)
        check("${icon.itemId}: Bedrock icon file", File("src/main/resources/bedrock/icons/${entry!!.icon}").isFile)
    }
    val sourcesForGeyser = listOf(
        GeyserMappings.Source("minecraft:stick", GuiIcon.DISCORD.modelKey, "Discord", "item:discord_icon"),
        GeyserMappings.Source("minecraft:stick", GuiIcon.YOUTUBE.modelKey, "YouTube", "item:youtube_icon"),
    )
    val mapped = GeyserMappings.build(sourcesForGeyser, index, setOf("minecraft:shield", "minecraft:elytra"))
    check("Geyser maps both without problems (${mapped.problems})", mapped.problems.isEmpty() && mapped.entries.size == 2)
    check("Geyser ids", mapped.entries.map { it.bedrockIdentifier }.toSet() == setOf("joshymc:discord_icon", "joshymc:youtube_icon"))
    // Bedrock holds every Stick-based item upright like the vanilla stick; GUI slots are unaffected.
    check("Bedrock keeps the stick's held pose", mapped.entries.all { it.handheld })
    check("Bedrock icons are the indexed ones", mapped.entries.map { it.iconPath }.toSet() == setOf("joshymc/discord_icon.png", "joshymc/youtube_icon.png"))

    println("OK: $checks GUI icon checks passed")
}
