import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

// Procedural 32x32 pixel-art item texture generator.
// Run: ./gradlew -I scripts/generate_item_textures.init.gradle.kts generateItemTextures
// Debug previews (8x nearest-neighbor upscale) go to build/texture-preview/.

data class Theme(val highlight: Color, val base: Color, val shadow: Color, val outline: Color, val accent: Color)

fun hsb(h: Float, s: Float, b: Float): Color = Color(Color.HSBtoRGB(h, s, b))

fun theme(hue: Float, sat: Float = 0.65f, brightness: Float = 0.85f, accentHue: Float = hue): Theme = Theme(
    highlight = hsb(hue, sat * 0.6f, (brightness + 0.35f).coerceAtMost(1f)),
    base = hsb(hue, sat, brightness),
    shadow = hsb(hue, (sat + 0.15f).coerceAtMost(1f), (brightness - 0.45f).coerceAtLeast(0.08f)),
    outline = hsb(hue, (sat + 0.2f).coerceAtMost(1f), (brightness - 0.65f).coerceAtLeast(0.04f)),
    accent = hsb(accentHue, 0.55f, 1f)
)

val SIZE = 32

fun newCanvas(): BufferedImage {
    val img = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    g.color = Color(0, 0, 0, 0)
    g.fillRect(0, 0, SIZE, SIZE)
    g.dispose()
    return img
}

fun graphics(img: BufferedImage): Graphics2D {
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    g.color = Color.BLACK
    return g
}

fun Graphics2D.thickLine(x0: Int, y0: Int, x1: Int, y1: Int, width: Int) {
    stroke = BasicStroke(width.toFloat(), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER)
    drawLine(x0, y0, x1, y1)
}

fun Graphics2D.poly(vararg pts: Int) {
    val n = pts.size / 2
    val xs = IntArray(n) { pts[it * 2] }
    val ys = IntArray(n) { pts[it * 2 + 1] }
    fillPolygon(Polygon(xs, ys, n))
}

fun alphaAt(img: BufferedImage, x: Int, y: Int): Int {
    if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return 0
    return (img.getRGB(x, y) ushr 24) and 0xFF
}

// Drops fully isolated opaque pixels left behind by ellipse-subtraction rasterization.
fun despeckle(img: BufferedImage) {
    val toClear = ArrayList<Pair<Int, Int>>()
    for (y in 0 until SIZE) for (x in 0 until SIZE) {
        if (alphaAt(img, x, y) == 0) continue
        var neighbors = 0
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            if (alphaAt(img, x + dx, y + dy) != 0) neighbors++
        }
        if (neighbors == 0) toClear += x to y
    }
    for ((x, y) in toClear) img.setRGB(x, y, 0)
}

// Fills every opaque (mask) pixel with theme shading (diagonal light from top-left)
// then stamps a 1px outline on the mask border. Called after the pure-black silhouette is drawn.
fun shadeAndOutline(img: BufferedImage, t: Theme, lightAxis: (Int, Int) -> Float = { x, y -> (x + y).toFloat() / (2 * SIZE) }) {
    for (y in 0 until SIZE) for (x in 0 until SIZE) {
        if (alphaAt(img, x, y) == 0) continue
        val f = lightAxis(x, y)
        val c = when {
            f < 0.32f -> t.highlight
            f > 0.68f -> t.shadow
            else -> t.base
        }
        img.setRGB(x, y, c.rgb or (0xFF shl 24))
    }
    val edges = ArrayList<Pair<Int, Int>>()
    for (y in 0 until SIZE) for (x in 0 until SIZE) {
        if (alphaAt(img, x, y) == 0) continue
        if (alphaAt(img, x - 1, y) == 0 || alphaAt(img, x + 1, y) == 0 || alphaAt(img, x, y - 1) == 0 || alphaAt(img, x, y + 1) == 0) {
            edges += x to y
        }
    }
    for ((x, y) in edges) img.setRGB(x, y, t.outline.rgb or (0xFF shl 24))
}

fun dot(img: BufferedImage, x: Int, y: Int, c: Color, r: Int = 1) {
    val g = graphics(img)
    g.color = c
    g.fillOval(x - r, y - r, r * 2 + 1, r * 2 + 1)
    g.dispose()
}

// ---------- Category silhouettes ----------
// Each draws a solid BLACK mask; shadeAndOutline() colors it afterwards.

fun drawSword(img: BufferedImage) {
    val g = graphics(img)
    g.thickLine(6, 29, 12, 23, 4)                      // grip
    g.fillOval(4, 27, 5, 5)                             // pommel
    g.thickLine(6, 21, 17, 26, 3)                       // crossguard
    g.thickLine(12, 22, 27, 5, 3)                       // blade
    g.poly(25, 6, 30, 1, 28, 8)                         // tip
    g.dispose()
}

fun drawAxe(img: BufferedImage) {
    val g = graphics(img)
    g.thickLine(6, 29, 17, 18, 4)                       // handle
    g.fillOval(11, 3, 21, 21)                           // outer blade disc
    val clear = img.createGraphics()
    clear.composite = java.awt.AlphaComposite.Clear
    clear.fillOval(18, 1, 17, 17)                       // bite out -> crescent bulging toward handle
    clear.dispose()
    g.poly(13, 19, 18, 22, 14, 24, 11, 20)              // beard hook back toward the handle
    g.dispose()
}

fun drawScythe(img: BufferedImage) {
    val g = graphics(img)
    g.thickLine(9, 30, 15, 15, 3)                       // handle
    g.fillOval(3, 1, 27, 27)                            // outer sweep
    val clear = img.createGraphics()
    clear.composite = java.awt.AlphaComposite.Clear
    clear.fillOval(6, 1, 24, 22)                        // thin the blade into a sliver
    clear.dispose()
    g.dispose()
}

fun drawShovel(img: BufferedImage) {
    val g = graphics(img)
    g.thickLine(10, 30, 16, 16, 3)                      // handle
    g.poly(13, 17, 20, 8, 27, 13, 24, 23, 16, 24)       // flat-edged spade blade
    g.dispose()
}

fun drawMace(img: BufferedImage) {
    val g = graphics(img)
    g.thickLine(15, 30, 15, 19, 4)                      // vertical grip
    g.fillOval(11, 27, 8, 5)                            // pommel cap
    g.fillRoundRect(9, 6, 14, 14, 4, 4)                 // blocky head
    // spikes radiating from the head
    g.poly(9, 8, 4, 6, 9, 12)
    g.poly(23, 8, 28, 6, 23, 12)
    g.poly(11, 6, 9, 1, 15, 6)
    g.poly(17, 6, 21, 1, 23, 6)
    g.poly(9, 16, 4, 20, 9, 18)
    g.poly(23, 16, 28, 20, 23, 18)
    g.poly(11, 20, 9, 25, 15, 20)
    g.poly(17, 20, 21, 25, 23, 20)
    g.dispose()
}

fun drawTrident(img: BufferedImage) {
    val g = graphics(img)
    g.thickLine(16, 30, 16, 14, 3)                      // shaft
    g.thickLine(16, 16, 16, 3, 3)                       // center prong
    g.thickLine(9, 18, 9, 7, 3)                         // left prong
    g.thickLine(23, 18, 23, 7, 3)                       // right prong
    g.poly(9, 4, 12, 8, 9, 8, 6, 8)                      // left prong tip
    g.poly(16, 1, 19, 6, 16, 6, 13, 6)                   // center prong tip
    g.poly(23, 4, 26, 8, 23, 8, 20, 8)                   // right prong tip
    g.thickLine(9, 15, 16, 17, 2)                        // crossbar left
    g.thickLine(23, 15, 16, 17, 2)                       // crossbar right
    g.dispose()
}

fun drawDrill(img: BufferedImage) {
    val g = graphics(img)
    g.thickLine(6, 29, 15, 20, 4)                       // handle
    g.fillRoundRect(12, 12, 10, 10, 3, 3)                // motor housing
    g.poly(19, 13, 30, 5, 30, 12, 21, 19)                // tapered drill bit
    g.dispose()
}

fun drawBow(img: BufferedImage) {
    val g = graphics(img)
    g.poly(11, 2, 15, 2, 19, 7, 21, 16, 19, 25, 15, 30, 11, 30, 15, 27, 17, 16, 15, 5)
    g.thickLine(13, 4, 13, 28, 1)                        // string
    g.thickLine(13, 16, 27, 16, 2)                       // nocked arrow shaft
    g.poly(27, 13, 31, 16, 27, 19)                       // arrowhead
    g.dispose()
}

fun drawWand(img: BufferedImage) {
    val g = graphics(img)
    g.thickLine(12, 30, 18, 21, 2)                       // slim shaft
    g.fillOval(14, 8, 11, 11)                            // orb
    g.poly(19, 3, 21, 8, 17, 8)                          // star point above the orb
    g.dispose()
}

fun drawHelmet(img: BufferedImage) {
    val g = graphics(img)
    g.fillRoundRect(7, 6, 18, 14, 8, 10)
    g.fillRect(7, 14, 18, 6)
    g.fillRect(12, 16, 4, 4)                             // eye slit cutout marker (recolored transparent later)
    g.dispose()
    // punch out the eye slit as transparent
    val g2 = img.createGraphics()
    g2.composite = java.awt.AlphaComposite.Clear
    g2.fillRect(12, 17, 5, 3)
    g2.dispose()
}

fun drawChestplate(img: BufferedImage) {
    val g = graphics(img)
    g.fillRoundRect(9, 4, 14, 6, 3, 3)                   // collar
    g.poly(6, 8, 11, 6, 11, 26, 6, 28)                   // left shoulder+arm
    g.poly(26, 8, 21, 6, 21, 26, 26, 28)                 // right shoulder+arm
    g.fillRoundRect(10, 8, 12, 20, 3, 3)                 // torso
    g.dispose()
}

fun drawLeggings(img: BufferedImage) {
    val g = graphics(img)
    g.fillRoundRect(8, 4, 16, 9, 3, 3)                   // waistband
    g.fillRect(8, 10, 7, 18)                             // left leg
    g.fillRect(17, 10, 7, 18)                            // right leg
    g.dispose()
}

fun drawBoots(img: BufferedImage) {
    val g = graphics(img)
    g.fillRoundRect(9, 4, 10, 16, 3, 3)                  // ankle/shin cuff
    g.poly(9, 18, 19, 18, 24, 24, 24, 28, 9, 28)          // foot + sole
    g.dispose()
}

// ---------- Item registry ----------

data class Item(val file: String, val theme: Theme, val draw: (BufferedImage) -> Unit)

val items = listOf(
    // Swords
    Item("carrot_sword", theme(0.10f, 0.75f, 0.95f), ::drawSword),
    Item("void_blade", theme(0.78f, 0.65f, 0.55f), ::drawSword),
    // Axes
    Item("inferno_axe", theme(0.03f, 0.9f, 0.95f), ::drawAxe),
    Item("lumberjacks_axe", theme(0.09f, 0.55f, 0.65f), ::drawAxe),
    // Hoe/Scythe family
    Item("soul_scythe", theme(0.50f, 0.55f, 0.55f), ::drawScythe),
    Item("farmers_sickle", theme(0.22f, 0.7f, 0.75f), ::drawScythe),
    // Shovel
    Item("flower_spade", theme(0.33f, 0.55f, 0.85f), ::drawShovel),
    // Mace
    Item("crystal_mace", theme(0.58f, 0.35f, 0.95f), ::drawMace),
    // Trident
    Item("poseidons_trident", theme(0.55f, 0.7f, 0.85f), ::drawTrident),
    // Drill / bore / mining family
    Item("void_drill", theme(0.78f, 0.65f, 0.55f), ::drawDrill),
    Item("void_drill_5x5", theme(0.76f, 0.7f, 0.5f), ::drawDrill),
    Item("void_bore", theme(0.80f, 0.6f, 0.6f), ::drawDrill),
    Item("void_bore_5x5", theme(0.82f, 0.65f, 0.5f), ::drawDrill),
    Item("void_bore_chunk", theme(0.74f, 0.55f, 0.65f), ::drawDrill),
    Item("excavator", theme(0.11f, 0.5f, 0.7f), ::drawDrill),
    Item("auto_miner", theme(0.14f, 0.4f, 0.75f), ::drawDrill),
    // Bow/launcher
    Item("carrot_launcher", theme(0.09f, 0.7f, 0.9f), ::drawBow),
    // Wand
    Item("magnet_wand", theme(0.0f, 0.0f, 0.75f, accentHue = 0.6f), ::drawWand),

    // Armor sets: bunny, crystal, flower, inferno, soul, void
    Item("bunny_helmet", theme(0.92f, 0.4f, 0.95f), ::drawHelmet),
    Item("bunny_chestplate", theme(0.92f, 0.4f, 0.95f), ::drawChestplate),
    Item("bunny_leggings", theme(0.92f, 0.4f, 0.95f), ::drawLeggings),
    Item("bunny_boots", theme(0.92f, 0.4f, 0.95f), ::drawBoots),

    Item("crystal_helmet", theme(0.58f, 0.35f, 0.95f), ::drawHelmet),
    Item("crystal_chestplate", theme(0.58f, 0.35f, 0.95f), ::drawChestplate),
    Item("crystal_leggings", theme(0.58f, 0.35f, 0.95f), ::drawLeggings),
    Item("crystal_boots", theme(0.58f, 0.35f, 0.95f), ::drawBoots),

    Item("flower_helmet", theme(0.33f, 0.55f, 0.85f), ::drawHelmet),
    Item("flower_chestplate", theme(0.33f, 0.55f, 0.85f), ::drawChestplate),
    Item("flower_leggings", theme(0.33f, 0.55f, 0.85f), ::drawLeggings),
    Item("flower_boots", theme(0.33f, 0.55f, 0.85f), ::drawBoots),

    Item("inferno_helmet", theme(0.03f, 0.9f, 0.95f), ::drawHelmet),
    Item("inferno_chestplate", theme(0.03f, 0.9f, 0.95f), ::drawChestplate),
    Item("inferno_leggings", theme(0.03f, 0.9f, 0.95f), ::drawLeggings),
    Item("inferno_boots", theme(0.03f, 0.9f, 0.95f), ::drawBoots),

    Item("soul_helmet", theme(0.50f, 0.55f, 0.55f), ::drawHelmet),
    Item("soul_chestplate", theme(0.50f, 0.55f, 0.55f), ::drawChestplate),
    Item("soul_leggings", theme(0.50f, 0.55f, 0.55f), ::drawLeggings),
    Item("soul_boots", theme(0.50f, 0.55f, 0.55f), ::drawBoots),

    Item("void_helmet", theme(0.78f, 0.65f, 0.55f), ::drawHelmet),
    Item("void_chestplate", theme(0.78f, 0.65f, 0.55f), ::drawChestplate),
    Item("void_leggings", theme(0.78f, 0.65f, 0.55f), ::drawLeggings),
    Item("void_boots", theme(0.78f, 0.65f, 0.55f), ::drawBoots),
)

fun upscale(img: BufferedImage, factor: Int): BufferedImage {
    val out = BufferedImage(img.width * factor, img.height * factor, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until img.height) for (x in 0 until img.width) {
        val c = img.getRGB(x, y)
        for (dy in 0 until factor) for (dx in 0 until factor) out.setRGB(x * factor + dx, y * factor + dy, c)
    }
    return out
}

gradle.rootProject {
    tasks.register("generateItemTextures") {
        doLast {
            System.setProperty("java.awt.headless", "true")
            val outDir = File(rootDir, "resourcepack/assets/joshymc/textures/item")
            val previewDir = File(rootDir, "build/texture-preview")
            previewDir.mkdirs()
            for (item in items) {
                val img = newCanvas()
                item.draw(img)
                despeckle(img)
                shadeAndOutline(img, item.theme)
                ImageIO.write(img, "PNG", File(outDir, "${item.file}.png"))
                ImageIO.write(upscale(img, 8), "PNG", File(previewDir, "${item.file}.png"))
            }
            println("Generated ${items.size} item textures.")
        }
    }
}
