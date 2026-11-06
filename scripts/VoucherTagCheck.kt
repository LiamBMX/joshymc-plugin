// Checks the bundled voucher Chat Tags (issue #1391): all 15 load from src/main/resources/tags.yml
// into the existing "voucher" category with their own joshymc.tag.voucher.<id> node, ids and names
// don't clash with any other tag, each display parses through the same legacy (&#RRGGBB) serializer
// CommunicationsManager uses into the exact "[symbol Name] " text with its own gradient, nothing
// leaks into the player name, and the bundled-defaults merge keeps admin edits and deletions.
// The Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/voucher-tag-check.init.gradle.kts voucherTagCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.manager.ChatTagDefaults
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
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

/** The same serializer CommunicationsManager.parseLegacy uses. */
private val legacy = LegacyComponentSerializer.builder().character('&').hexCharacter('#').hexColors().build()

private data class Expected(val id: String, val symbol: String?, val name: String, val start: String, val end: String)

private val EXPECTED = listOf(
    Expected("mapleborn", "❦", "Mapleborn", "E8571E", "F2B33D"),
    Expected("hearthbound", "⌂", "Hearthbound", "FFB627", "B8693D"),
    Expected("moonmoss", "☾", "Moonmoss", "A3B58F", "4F9A9B"),
    Expected("lanternlit", "✦", "Lanternlit", "FFD23F", "FF9550"),
    Expected("starforged", "✧", "Starforged", "C9B0FF", "5B8DEF"),
    Expected("honeyglow", "⬢", "Honeyglow", "E3A018", "FFF3A8"),
    Expected("wildroot", "❀", "Wildroot", "2F8F3A", "9BE08A"),
    Expected("nightbloom", "✿", "Nightbloom", "8A3FD1", "D7B8FF"),
    Expected("sunwoven", "☀", "Sunwoven", "FFCBA4", "F5A01B"),
    Expected("cloudbound", "☁", "Cloudbound", "6EC1FF", "F2F8FF"),
    Expected("emberkeeper", "✹", "Emberkeeper", "E82C1E", "FF9A3C"),
    Expected("driftwood", null, "Driftwood", "9A7752", "E6D3AC"),
    Expected("glowkeeper", "✴", "Glowkeeper", "7DF2B5", "1FE3F7"),
    Expected("twilightkin", "☽", "Twilightkin", "8C63C4", "F7A8C8"),
    Expected("trailmarked", "⚒", "Trailmarked", "A3A23C", "5E9E4C"),
)

/** One rendered code point with the color and decorations it ends up with. */
private data class Glyph(val text: String, val color: TextColor?, val decorations: Set<TextDecoration>)

private fun flatten(
    c: Component,
    color: TextColor? = null,
    decorations: Set<TextDecoration> = emptySet(),
    out: MutableList<Glyph> = mutableListOf(),
): List<Glyph> {
    val myColor = c.color() ?: color
    val myDecorations = decorations.toMutableSet()
    for (d in TextDecoration.entries) {
        when (c.decoration(d)) {
            TextDecoration.State.TRUE -> myDecorations += d
            TextDecoration.State.FALSE -> myDecorations -= d
            else -> {}
        }
    }
    if (c is TextComponent) {
        c.content().codePoints().forEach { out += Glyph(String(Character.toChars(it)), myColor, myDecorations) }
    }
    for (child in c.children()) flatten(child, myColor, myDecorations, out)
    return out
}

private fun hex(s: String) = TextColor.fromHexString("#$s")!!

private fun plain(display: String) = PlainTextComponentSerializer.plainText().serialize(legacy.deserialize(display))

fun main() {
    val file = File("src/main/resources/tags.yml")
    val config = YamlConfiguration.loadConfiguration(file)
    val tags = config.getConfigurationSection("tags")
    check("tags.yml has a tags section", tags != null)
    tags!!

    // ── Ids and names across every category ────────────────
    val idCategories = mutableMapOf<String, MutableList<String>>()
    val namesById = mutableMapOf<String, String>()
    for (category in tags.getKeys(false)) {
        val section = tags.getConfigurationSection(category) ?: continue
        for (id in section.getKeys(false)) {
            idCategories.getOrPut(id) { mutableListOf() } += category
            namesById[id] = plain(section.getString("$id.display") ?: "")
                .trim().removePrefix("[").removeSuffix("]")
                .replace(Regex("^[^\\p{L}\\p{N}]+\\s"), "").lowercase()
        }
    }
    for ((id, categories) in idCategories) check("tag id $id is in exactly one category (found $categories)", categories.size == 1)

    val voucher = tags.getConfigurationSection(ChatTagDefaults.VOUCHER_CATEGORY)
    check("bundled voucher category exists", voucher != null)
    voucher!!
    check("the voucher category has exactly the 15 issue #1391 tags", voucher.getKeys(false) == EXPECTED.map { it.id }.toSet())
    check("15 distinct ids", EXPECTED.map { it.id }.toSet().size == 15)

    for (e in EXPECTED) {
        val others = namesById.filterKeys { it != e.id }
        check("${e.id}: display name '${e.name}' is not used by another tag", e.name.lowercase() !in others.values)
    }

    // ── Palettes ───────────────────────────────────────────
    check("every tag has its own start color", EXPECTED.map { it.start }.toSet().size == 15)
    check("every tag has its own end color", EXPECTED.map { it.end }.toSet().size == 15)
    check("every tag has its own palette", EXPECTED.map { it.start to it.end }.toSet().size == 15)

    // ── Each tag ───────────────────────────────────────────
    for (e in EXPECTED) {
        val display = voucher.getString("${e.id}.display")
        val permission = voucher.getString("${e.id}.permission")
        check("${e.id}: has a display", display != null)
        display!!
        check("${e.id}: permission is joshymc.tag.voucher.${e.id}", permission == "joshymc.tag.voucher.${e.id}")
        check("${e.id}: permission uses the shared voucher prefix", permission == ChatTagDefaults.VOUCHER_PERMISSION_PREFIX + e.id)
        check("${e.id}: no legacy bold/obfuscated/italic/underline/strike codes", !Regex("&[klmnoKLMNO]").containsMatchIn(display))
        check("${e.id}: no MiniMessage tags (tags.yml is legacy &-format)", !display.contains('<'))
        check("${e.id}: display ends with a reset and the usual trailing space", display.endsWith("&r "))

        val shown = "[" + (e.symbol?.let { "$it " } ?: "") + e.name + "] "
        check("${e.id}: renders as '$shown'", plain(display) == shown)
        e.symbol?.let { sym ->
            check("${e.id}: symbol is one code point", sym.codePointCount(0, sym.length) == 1)
            check("${e.id}: symbol is in the Basic Multilingual Plane (no surrogate pair to split)", sym.codePointAt(0) <= 0xFFFF)
        }
        check("${e.id}: no surrogate pairs anywhere in the display", display.none { it.isSurrogate() })

        val glyphs = flatten(legacy.deserialize(display))
        check("${e.id}: glyph count matches", glyphs.joinToString("") { it.text } == shown)
        check("${e.id}: nothing is bold/obfuscated/italic", glyphs.all { it.decorations.isEmpty() })

        val start = hex(e.start)
        val end = hex(e.end)
        check("${e.id}: opening bracket in the start color", glyphs.first().text == "[" && glyphs.first().color == start)
        val nameStart = if (e.symbol != null) 3 else 1
        if (e.symbol != null) {
            check("${e.id}: symbol in the start color", glyphs[1].text == e.symbol && glyphs[1].color == start)
        }
        val nameGlyphs = glyphs.subList(nameStart, nameStart + e.name.length)
        check("${e.id}: name glyphs spell the name", nameGlyphs.joinToString("") { it.text } == e.name)
        check("${e.id}: every name letter is hex colored", nameGlyphs.all { it.color != null && it.color !is NamedTextColor })
        check("${e.id}: gradient starts at #${e.start}", nameGlyphs.first().color == start)
        check("${e.id}: gradient ends at #${e.end}", nameGlyphs.last().color == end)
        check("${e.id}: gradient is a real gradient (>= 5 distinct colors)", nameGlyphs.map { it.color }.toSet().size >= 5)
        val close = glyphs[nameStart + e.name.length]
        check("${e.id}: closing bracket in the end color", close.text == "]" && close.color == end)
        val trailing = glyphs.last()
        check("${e.id}: trailing space is reset (no color, no decoration)", trailing.text == " " && trailing.color == null && trailing.decorations.isEmpty())

        // Monotonic per channel: each step moves the same way as the overall gradient.
        val rgb = nameGlyphs.map { it.color!! }
        for (channel in 0..2) {
            fun v(c: TextColor) = when (channel) { 0 -> c.red(); 1 -> c.green(); else -> c.blue() }
            val dir = v(end).compareTo(v(start))
            check("${e.id}: channel $channel moves one way", rgb.zipWithNext().all { (a, b) -> v(b).compareTo(v(a)) * dir >= 0 })
        }

        // ── No bleed into the name or message ──────────────
        // The default chat format: {prefix}{tag}&f{player}{suffix} &8» &f{message}
        val line = flatten(legacy.deserialize("&7[Member] ${display}&fSteve &8» &f"))
        val steve = line.joinToString("") { it.text }.indexOf("Steve")
        check("${e.id}: name follows the tag", steve > 0)
        check("${e.id}: name is white with no decorations", line.subList(steve, steve + 5).all { it.color == NamedTextColor.WHITE && it.decorations.isEmpty() })
        // Even a format without a color before {player} gets an unstyled name.
        val bare = flatten(legacy.deserialize("${display}Steve"))
        check("${e.id}: name after the tag alone is unstyled", bare.takeLast(5).all { it.color == null && it.decorations.isEmpty() })
    }

    // ── Bundled-defaults merge ─────────────────────────────
    val defaults = YamlConfiguration.loadConfiguration(file)

    // A server whose tags.yml has no voucher category yet gets all 15.
    val fresh = YamlConfiguration()
    fresh.set("tags.vibe.chill.display", "&d[Chill] ")
    val r1 = ChatTagDefaults.merge(defaults, fresh)
    check("merge adds the voucher category", r1.categoriesAdded >= 1 && fresh.getConfigurationSection("tags.voucher") != null)
    check("merge adds all 15 voucher tags", EXPECTED.all { fresh.getString("tags.voucher.${it.id}.permission") == "joshymc.tag.voucher.${it.id}" })
    check("merge keeps an existing tag's display", fresh.getString("tags.vibe.chill.display") == "&d[Chill] ")
    check("merge reports a change", r1.changed)
    check("second merge is a no-op", !ChatTagDefaults.merge(defaults, fresh).changed)

    // A server with its own voucher tags (e.g. /voucher create joshymc) keeps them and its edits.
    val existing = YamlConfiguration()
    existing.set("tags.voucher.joshymc.display", "&6[JoshyMC] ")
    existing.set("tags.voucher.joshymc.permission", "joshymc.tag.voucher.joshymc")
    existing.set("tags.voucher.mapleborn.display", "&c[Custom Maple] ")
    existing.set("tags.voucher.mapleborn.permission", "joshymc.tag.voucher.mapleborn")
    val r2 = ChatTagDefaults.merge(defaults, existing)
    val normalCategories = defaults.getConfigurationSection("tags")!!.getKeys(false).size - 1
    check("existing voucher category isn't re-added", r2.categoriesAdded == normalCategories)
    check("voucher category now holds the admin's tag plus the 15", existing.getConfigurationSection("tags.voucher")!!.getKeys(false).size == 16)
    check("admin-created voucher tag kept", existing.getString("tags.voucher.joshymc.display") == "&6[JoshyMC] ")
    check("admin edit of a bundled voucher tag kept", existing.getString("tags.voucher.mapleborn.display") == "&c[Custom Maple] ")
    check("the other 14 are added", EXPECTED.filter { it.id != "mapleborn" }.all { existing.contains("tags.voucher.${it.id}") })
    check("existing category order: voucher tags appended after admin's", existing.getConfigurationSection("tags.voucher")!!.getKeys(false).first() == "joshymc")

    // A bundled voucher tag deleted with /voucher tag delete stays deleted.
    val deleted = YamlConfiguration()
    ChatTagDefaults.merge(defaults, deleted)
    deleted.set("tags.voucher.moonmoss", null)
    ChatTagDefaults.markVoucherRemoved(deleted, "moonmoss")
    ChatTagDefaults.markVoucherRemoved(deleted, "moonmoss")
    check("removed list holds the id once", deleted.getStringList(ChatTagDefaults.REMOVED_VOUCHER_DEFAULTS) == listOf("moonmoss"))
    check("re-merge doesn't bring a deleted voucher tag back", !ChatTagDefaults.merge(defaults, deleted).changed && !deleted.contains("tags.voucher.moonmoss"))
    // Re-created with /voucher create: off the list again.
    ChatTagDefaults.clearVoucherRemoved(deleted, "moonmoss")
    check("clearing the removed list drops the key when empty", !deleted.contains(ChatTagDefaults.REMOVED_VOUCHER_DEFAULTS))
    check("after clearing, merge restores it", ChatTagDefaults.merge(defaults, deleted).tagsAdded == 1)

    // All voucher tags deleted and the section gone: only the non-removed ones come back.
    val allGone = YamlConfiguration()
    allGone.set(ChatTagDefaults.REMOVED_VOUCHER_DEFAULTS, EXPECTED.map { it.id })
    ChatTagDefaults.merge(defaults, allGone)
    check("a fully removed voucher category isn't recreated", allGone.getConfigurationSection("tags.voucher") == null)

    // The removed list only applies to the voucher category.
    val normal = YamlConfiguration()
    normal.set(ChatTagDefaults.REMOVED_VOUCHER_DEFAULTS, listOf("chill"))
    ChatTagDefaults.merge(defaults, normal)
    check("removed list doesn't affect normal categories", normal.contains("tags.vibe.chill"))

    // ── No shared/wildcard unlock in plugin.yml ────────────
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("plugin.yml declares no joshymc.tag.voucher node (they are registered per tag, default false)", !pluginYml.contains("joshymc.tag.voucher"))

    println("VoucherTagCheck: all $checks checks passed.")
}
