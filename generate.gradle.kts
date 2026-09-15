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

// Run with: ./gradlew -b generate.gradle.kts generateEquipmentTextures
// Generates the worn/equipped textures for golden_crest, jack_o_lantern_mask
// (humanoid head-cube layer) and falling_leaf (elytra wings layer). Inventory
// icon textures under textures/item/ are untouched.
tasks.register("generateEquipmentTextures") {
    doLast {
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

        fun makePng(pixels: List<IntArray>, w: Int, h: Int): ByteArray {
            val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val ihdrData = int32BE(w) + int32BE(h) + byteArrayOf(8, 6, 0, 0, 0)
            val ihdr = pngChunk("IHDR", ihdrData)

            val raw = ByteArrayOutputStream(h * (1 + w * 4))
            for (y in 0 until h) {
                raw.write(0)
                for (x in 0 until w) {
                    val p = pixels[y * w + x]
                    raw.write(p[0]); raw.write(p[1]); raw.write(p[2]); raw.write(p[3])
                }
            }

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

        // 64x32 canvas, filled transparent, with a settable pixel helper.
        fun blankCanvas(w: Int, h: Int): Array<IntArray> {
            val trans = intArrayOf(0, 0, 0, 0)
            return Array(w * h) { trans }
        }

        fun fillRect(canvas: Array<IntArray>, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, color: IntArray) {
            for (y in y0 until y1) for (x in x0 until x1) canvas[y * w + x] = color
        }

        fun setPx(canvas: Array<IntArray>, w: Int, x: Int, y: Int, color: IntArray) {
            canvas[y * w + x] = color
        }

        fun writePng(canvas: Array<IntArray>, w: Int, h: Int, outFile: File) {
            outFile.parentFile.mkdirs()
            val pngBytes = makePng(canvas.toList(), w, h)
            outFile.writeBytes(pngBytes)
            println("  Wrote $outFile (${pngBytes.size} bytes)")
        }

        val W = 64
        val H = 32

        // ── Head-cube UV layout shared by every "humanoid" equipment layer ────
        // (identical to the classic 64x32 skin/armor-overlay layout):
        //   top    x  8..16 y 0..8      bottom x 16..24 y 0..8
        //   right  x  0..8  y 8..16     front  x  8..16 y 8..16
        //   left   x 16..24 y 8..16     back   x 24..32 y 8..16
        fun paintHead(
            canvas: Array<IntArray>,
            base: IntArray, light: IntArray, shadow: IntArray, border: IntArray,
            paintFront: (Array<IntArray>) -> Unit,
            paintTop: (Array<IntArray>) -> Unit
        ) {
            // Base fill for all 6 faces first.
            fillRect(canvas, W, 8, 0, 16, 8, base)    // top
            fillRect(canvas, W, 16, 0, 24, 8, shadow) // bottom
            fillRect(canvas, W, 0, 8, 8, 16, base)    // right
            fillRect(canvas, W, 8, 8, 16, 16, base)   // front
            fillRect(canvas, W, 16, 8, 24, 16, base)  // left
            fillRect(canvas, W, 24, 8, 32, 16, base)  // back

            // Side ridge shading (right/left/back) + border outline on every face.
            for (fx in intArrayOf(0, 16, 24)) {
                for (y in 8 until 16) {
                    canvas[y * W + fx] = border
                    canvas[y * W + (fx + 7)] = if (fx == 16) border else light
                }
            }
            paintTop(canvas)
            paintFront(canvas)

            // Border outline around front/top faces.
            for (x in 8 until 16) {
                canvas[8 * W + x] = border
                canvas[15 * W + x] = border
            }
            for (y in 0 until 8) {
                canvas[y * W + 8] = border
                canvas[y * W + 15] = border
            }
        }

        // ── golden_crest: gold crown-style helmet with amber crest accents ────
        run {
            val GOLD_BASE = intArrayOf(212, 175, 55, 255)
            val GOLD_LIGHT = intArrayOf(255, 223, 128, 255)
            val GOLD_SHADOW = intArrayOf(139, 105, 20, 255)
            val TRIM = intArrayOf(92, 64, 10, 255)
            val AMBER = intArrayOf(204, 85, 0, 255)

            val canvas = blankCanvas(W, H)
            paintHead(
                canvas, GOLD_BASE, GOLD_LIGHT, GOLD_SHADOW, TRIM,
                paintFront = { c ->
                    // Crown points along the brow (top row of the front face).
                    for (x in 8 until 16) setPx(c, W, x, 9, if ((x - 8) % 2 == 0) TRIM else GOLD_LIGHT)
                    // Amber crest bar down the center.
                    for (y in 10 until 15) { setPx(c, W, 11, y, AMBER); setPx(c, W, 12, y, AMBER) }
                },
                paintTop = { c ->
                    // Amber cross emblem on the crown.
                    for (x in 8 until 16) setPx(c, W, x, 3, AMBER)
                    for (y in 0 until 8) setPx(c, W, 11, y, AMBER)
                }
            )
            writePng(canvas, W, H, File(projectDir, "resourcepack/assets/joshymc/textures/entity/equipment/humanoid/golden_crest.png"))
        }

        // ── jack_o_lantern_mask: carved pumpkin helmet with a green stem ──────
        run {
            val PUMPKIN_BASE = intArrayOf(230, 115, 20, 255)
            val PUMPKIN_LIGHT = intArrayOf(255, 165, 60, 255)
            val PUMPKIN_SHADOW = intArrayOf(160, 75, 10, 255)
            val TRIM = intArrayOf(80, 40, 8, 255)
            val CARVED = intArrayOf(30, 16, 10, 255)
            val STEM = intArrayOf(86, 125, 46, 255)

            val canvas = blankCanvas(W, H)
            paintHead(
                canvas, PUMPKIN_BASE, PUMPKIN_LIGHT, PUMPKIN_SHADOW, TRIM,
                paintFront = { c ->
                    // Triangle-ish carved eyes.
                    setPx(c, W, 9, 10, CARVED); setPx(c, W, 10, 10, CARVED); setPx(c, W, 10, 11, CARVED)
                    setPx(c, W, 13, 10, CARVED); setPx(c, W, 14, 10, CARVED); setPx(c, W, 13, 11, CARVED)
                    // Jagged carved mouth.
                    for (x in 9 until 15) setPx(c, W, x, 13, CARVED)
                    setPx(c, W, 10, 14, CARVED); setPx(c, W, 12, 14, CARVED); setPx(c, W, 14, 14, CARVED)
                    // Vertical ridge shading.
                    for (y in 8 until 16) setPx(c, W, 9, y, PUMPKIN_SHADOW)
                },
                paintTop = { c ->
                    // Green stem poking out of the top-center.
                    setPx(c, W, 11, 2, STEM); setPx(c, W, 12, 2, STEM)
                    setPx(c, W, 11, 3, STEM); setPx(c, W, 12, 3, STEM)
                }
            )
            writePng(canvas, W, H, File(projectDir, "resourcepack/assets/joshymc/textures/entity/equipment/humanoid/jack_o_lantern_mask.png"))
        }

        // ── falling_leaf: autumn-leaf elytra wings ─────────────────────────────
        // Fills the full wing sheet with a warm autumn gradient + scattered leaf
        // flecks, since the exact UV crop the wings model samples is small
        // relative to the sheet — a full-canvas theme avoids blank/transparent
        // gaps regardless of which sub-region ends up visible on each wing.
        run {
            val AMBER = intArrayOf(222, 168, 44, 255)
            val ORANGE = intArrayOf(230, 126, 34, 255)
            val RED = intArrayOf(178, 34, 24, 255)
            val BROWN = intArrayOf(110, 66, 30, 255)
            val VEIN = intArrayOf(66, 40, 20, 255)

            val canvas = blankCanvas(W, H)
            for (y in 0 until H) {
                val rowColor = when {
                    y < H / 4 -> AMBER
                    y < H / 2 -> ORANGE
                    y < 3 * H / 4 -> RED
                    else -> BROWN
                }
                fillRect(canvas, W, 0, y, W, y + 1, rowColor)
            }
            // Leaf-vein flecks scattered across the sheet.
            for (x in 0 until W) {
                if (x % 5 == 0) {
                    for (y in 0 until H step 3) setPx(canvas, W, x, y, VEIN)
                }
            }
            // Dark border framing the whole sheet like a wing edge.
            for (x in 0 until W) { setPx(canvas, W, x, 0, VEIN); setPx(canvas, W, x, H - 1, VEIN) }
            for (y in 0 until H) { setPx(canvas, W, 0, y, VEIN); setPx(canvas, W, W - 1, y, VEIN) }

            writePng(canvas, W, H, File(projectDir, "resourcepack/assets/joshymc/textures/entity/equipment/wings/falling_leaf.png"))
        }

        println("\nDone — equipment textures written.")
    }
}
