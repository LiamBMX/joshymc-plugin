// Checks the Bedrock scoreboard logo pack wiring (issue #1333). The Gradle build has no test
// source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/bedrock-logo-check.init.gradle.kts bedrockLogoCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.bedrock.BedrockLogo
import com.liam.joshymc.bedrock.BedrockPack
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private fun entries(pack: ByteArray): Map<String, ByteArray> {
    val out = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(pack)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            out[entry.name] = zip.readBytes()
        }
    }
    return out
}

private fun throws(block: () -> Unit): Boolean = try {
    block(); false
} catch (_: IllegalArgumentException) {
    true
}

fun main() {
    val icons = mapOf("joshymc:frostbite" to "joshymc/frostbite.png")
    val iconBytes = byteArrayOf(1, 2, 3)
    val sheet = File("src/main/resources/${BedrockLogo.SHEET_RESOURCE}")
    check("glyph sheet ${sheet.path} exists (run python3 -m art.logo)", sheet.isFile)
    val sheetBytes = sheet.readBytes()

    // The committed sheet is a 16x16 grid of square cells with the logo in row 0, cells 0..2.
    val image = ImageIO.read(sheet)
    check("sheet is square", image.width == image.height)
    check("sheet splits into 16 cells", image.width % 16 == 0)
    val cell = image.width / 16
    fun cellUsed(cx: Int, cy: Int): Boolean {
        for (x in cx * cell until (cx + 1) * cell) for (y in cy * cell until (cy + 1) * cell) {
            if ((image.getRGB(x, y) ushr 24) != 0) return true
        }
        return false
    }
    for (i in 0 until 3) check("logo cell $i is drawn", cellUsed(i, 0))
    for (cy in 0 until 16) for (cx in 0 until 16) {
        if (cy == 0 && cx < 3) continue
        check("cell $cx,$cy is empty", !cellUsed(cx, cy))
    }
    check("title is the three glyphs of the E9 page",
        BedrockLogo.SIDEBAR_TITLE.map { it.code } == listOf(0xE900, 0xE901, 0xE902))
    check("title glyphs live on the sheet's page",
        BedrockLogo.SIDEBAR_TITLE.all { "%02X".format(it.code shr 8) == BedrockLogo.SHEET_PACK_PATH.substringAfter("glyph_").substringBefore(".png") })

    // The pack carries the sheet unchanged next to the item icons.
    val without = BedrockPack.build(icons) { iconBytes }
    val with = BedrockPack.build(icons, mapOf(BedrockLogo.SHEET_PACK_PATH to sheetBytes)) { iconBytes }
    val files = entries(with)
    check("pack holds the glyph sheet", files[BedrockLogo.SHEET_PACK_PATH]?.contentEquals(sheetBytes) == true)
    check("pack keeps the item icon", files["textures/joshymc/frostbite.png"]?.contentEquals(iconBytes) == true)
    check("pack keeps item_texture.json", "textures/item_texture.json" in files)
    check("pack has a manifest", "manifest.json" in files)
    check("old packs had no sheet", BedrockLogo.SHEET_PACK_PATH !in entries(without))
    check("adding the sheet bumps the pack version",
        String(files.getValue("manifest.json")) != String(entries(without).getValue("manifest.json")))
    check("build is deterministic",
        with.contentEquals(BedrockPack.build(icons, mapOf(BedrockLogo.SHEET_PACK_PATH to sheetBytes)) { iconBytes }))

    // Extra files can't replace pack internals or icons.
    check("extra file can't replace the manifest", throws { BedrockPack.build(icons, mapOf("manifest.json" to sheetBytes)) { iconBytes } })
    check("extra file can't replace item_texture.json",
        throws { BedrockPack.build(icons, mapOf("textures/item_texture.json" to sheetBytes)) { iconBytes } })
    check("extra file can't replace an icon",
        throws { BedrockPack.build(icons, mapOf("textures/joshymc/frostbite.png" to sheetBytes)) { iconBytes } })

    println("Bedrock logo checks passed ($checks checks)")
}
