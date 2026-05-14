// Standalone Gradle script — generates enchant scroll textures.
// Run with: ./gradlew -b generate.gradle.kts generateScrollTextures
// Does NOT modify the main build; safe to delete after use.

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

tasks.register("generateScrollTextures") {
    doLast {
        val outputDir = File(projectDir, "resourcepack/assets/joshymc/textures/item")
        outputDir.mkdirs()

        // ── PNG helpers ──────────────────────────────────────────────────────

        fun int32BE(v: Int): ByteArray = byteArrayOf(
            (v ushr 24).toByte(), (v ushr 16).toByte(),
            (v ushr 8).toByte(),  v.toByte()
        )

        fun pngChunk(tag: String, data: ByteArray): ByteArray {
            val tagBytes = tag.toByteArray(Charsets.US_ASCII)
            val crcInput = tagBytes + data
            val crc = CRC32().also { it.update(crcInput) }.value.toInt()
            return int32BE(data.size) + tagBytes + data + int32BE(crc)
        }

        fun makePng(pixels: List<IntArray>, w: Int = 16, h: Int = 16): ByteArray {
            val sig = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
            )
            // IHDR: 13 bytes
            val ihdrData = int32BE(w) + int32BE(h) +
                byteArrayOf(8, 6, 0, 0, 0) // bit_depth=8, colorType=RGBA, comp/filter/interlace=0
            val ihdr = pngChunk("IHDR", ihdrData)

            // Raw scanlines: each row starts with filter byte 0 (None)
            val raw = ByteArrayOutputStream(h * (1 + w * 4))
            for (y in 0 until h) {
                raw.write(0)
                for (x in 0 until w) {
                    val p = pixels[y * w + x]
                    raw.write(p[0]); raw.write(p[1]); raw.write(p[2]); raw.write(p[3])
                }
            }

            // Deflate
            val deflater = Deflater(9)
            deflater.setInput(raw.toByteArray())
            deflater.finish()
            val compressed = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                compressed.write(buf, 0, n)
            }
            deflater.end()

            val idat = pngChunk("IDAT", compressed.toByteArray())
            val iend = pngChunk("IEND", ByteArray(0))
            return sig + ihdr + idat + iend
        }

        // ── Scroll shape ─────────────────────────────────────────────────────
        //
        // Cell codes:
        //  0=transparent  1=roll-highlight  2=roll-mid  3=roll-shadow
        //  4=border       5=parchment       6=text-line

        val grid = arrayOf(
            intArrayOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            intArrayOf(0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0),
            intArrayOf(0,0,2,2,2,2,2,2,2,2,2,2,2,2,0,0),
            intArrayOf(0,0,3,3,3,3,3,3,3,3,3,3,3,3,0,0),
            intArrayOf(0,0,4,5,5,5,5,5,5,5,5,5,5,4,0,0),
            intArrayOf(0,0,4,5,6,6,6,6,6,6,6,6,5,4,0,0),
            intArrayOf(0,0,4,5,5,5,5,5,5,5,5,5,5,4,0,0),
            intArrayOf(0,0,4,5,6,6,6,6,6,6,6,6,5,4,0,0),
            intArrayOf(0,0,4,5,5,5,5,5,5,5,5,5,5,4,0,0),
            intArrayOf(0,0,4,5,6,6,6,6,6,6,6,6,5,4,0,0),
            intArrayOf(0,0,4,5,5,5,5,5,5,5,5,5,5,4,0,0),
            intArrayOf(0,0,4,5,5,5,5,5,5,5,5,5,5,4,0,0),
            intArrayOf(0,0,3,3,3,3,3,3,3,3,3,3,3,3,0,0),
            intArrayOf(0,0,2,2,2,2,2,2,2,2,2,2,2,2,0,0),
            intArrayOf(0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0),
            intArrayOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
        )

        val ROLL_H  = intArrayOf(210, 165, 100, 255)
        val ROLL_M  = intArrayOf(168, 118,  58, 255)
        val ROLL_S  = intArrayOf(112,  72,  32, 255)
        val BORDER  = intArrayOf(148, 102,  48, 255)
        val TRANS   = intArrayOf(  0,   0,   0,   0)

        fun makeScroll(parchment: IntArray, textLine: IntArray): ByteArray {
            val pixels = ArrayList<IntArray>(256)
            for (row in grid) for (code in row) pixels.add(when (code) {
                1 -> ROLL_H; 2 -> ROLL_M; 3 -> ROLL_S; 4 -> BORDER
                5 -> parchment; 6 -> textLine; else -> TRANS
            })
            return makePng(pixels)
        }

        // ── Per-enchant colours ──────────────────────────────────────────────

        data class ScrollColor(val parchment: IntArray, val text: IntArray)

        val SWORD      = ScrollColor(intArrayOf(235,185,160,255), intArrayOf(180, 95, 85,255))
        val AXE        = ScrollColor(intArrayOf(240,205,155,255), intArrayOf(195,120, 55,255))
        val HELMET     = ScrollColor(intArrayOf(175,220,215,255), intArrayOf( 75,155,160,255))
        val CHESTPLATE = ScrollColor(intArrayOf(175,200,240,255), intArrayOf( 75,110,185,255))
        val LEGGINGS   = ScrollColor(intArrayOf(215,180,240,255), intArrayOf(135, 85,185,255))
        val BOOTS      = ScrollColor(intArrayOf(180,235,190,255), intArrayOf( 75,160,100,255))
        val SHOVEL     = ScrollColor(intArrayOf(240,230,150,255), intArrayOf(180,160, 60,255))
        val PICKAXE    = ScrollColor(intArrayOf(185,210,230,255), intArrayOf( 80,120,170,255))
        val HOE        = ScrollColor(intArrayOf(245,210,125,255), intArrayOf(185,140, 48,255))
        val ALL_TOOLS  = ScrollColor(intArrayOf(210,205,200,255), intArrayOf(125,120,115,255))
        val BASE       = ScrollColor(intArrayOf(240,220,165,255), intArrayOf(160,128, 75,255))

        val enchantColors = mapOf(
            "enchant_scroll"              to BASE,
            "enchant_scroll_lifesteal"    to SWORD,
            "enchant_scroll_execute"      to SWORD,
            "enchant_scroll_bleed"        to SWORD,
            "enchant_scroll_adrenaline"   to SWORD,
            "enchant_scroll_striker"      to SWORD,
            "enchant_scroll_cleave"       to AXE,
            "enchant_scroll_berserk"      to AXE,
            "enchant_scroll_paralysis"    to AXE,
            "enchant_scroll_blizzard"     to AXE,
            "enchant_scroll_night_vision" to HELMET,
            "enchant_scroll_clarity"      to HELMET,
            "enchant_scroll_focus"        to HELMET,
            "enchant_scroll_xray"         to HELMET,
            "enchant_scroll_overload"     to CHESTPLATE,
            "enchant_scroll_dodge"        to CHESTPLATE,
            "enchant_scroll_guardian"     to CHESTPLATE,
            "enchant_scroll_shockwave"    to LEGGINGS,
            "enchant_scroll_valor"        to LEGGINGS,
            "enchant_scroll_curse_swap"   to LEGGINGS,
            "enchant_scroll_gears"        to BOOTS,
            "enchant_scroll_springs"      to BOOTS,
            "enchant_scroll_featherweight" to BOOTS,
            "enchant_scroll_rockets"      to BOOTS,
            "enchant_scroll_glass_breaker" to SHOVEL,
            "enchant_scroll_magnet"       to ALL_TOOLS,
            "enchant_scroll_autosmelt"    to PICKAXE,
            "enchant_scroll_experience"   to PICKAXE,
            "enchant_scroll_condenser"    to PICKAXE,
            "enchant_scroll_explosive"    to PICKAXE,
            "enchant_scroll_ground_pound" to HOE,
            "enchant_scroll_great_harvest" to HOE,
            "enchant_scroll_blessing"     to HOE,
        )

        var written = 0
        for ((name, sc) in enchantColors) {
            val pngBytes = makeScroll(sc.parchment, sc.text)
            val file = File(outputDir, "$name.png")
            file.writeBytes(pngBytes)
            println("  Wrote $file (${pngBytes.size} bytes)")
            written++
        }
        println("\nDone — $written scroll textures written to $outputDir")
    }
}
