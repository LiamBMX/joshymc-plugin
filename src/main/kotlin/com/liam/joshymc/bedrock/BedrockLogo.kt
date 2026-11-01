package com.liam.joshymc.bedrock

/**
 * The JoshyMC logo for Bedrock players (issue #1333), Bukkit-free.
 *
 * Java players see the logo through Java font glyphs (`` in
 * `assets/minecraft/font/default.json`). Bedrock clients never read Java fonts; they draw
 * private-use characters from `font/glyph_<high byte>.png` sheets in a Bedrock resource
 * pack: a 16x16 grid of square cells, each glyph drawn one text line tall. So the 3:1 logo
 * is three cells side by side (``) of `font/glyph_E9.png`, which
 * `python3 -m art.logo` cuts from the same logo and [BedrockPack] ships to Geyser.
 *
 * Page E9 is used because vanilla Bedrock's own icon sheets live on the E0/E1 pages and a
 * resource pack replaces a whole sheet; E9 is ours alone.
 */
object BedrockLogo {

    /** Sheet path inside the JAR (written by `art.logo`). */
    const val SHEET_RESOURCE = "bedrock/font/glyph_E9.png"

    /** Sheet path inside the Bedrock pack. */
    const val SHEET_PACK_PATH = "font/glyph_E9.png"

    /** The logo, left to right; the three glyphs of row 0 on the E9 sheet. */
    const val SIDEBAR_TITLE = ""

    /** Plain title for Bedrock players when the logo sheet isn't in the Bedrock pack. */
    const val FALLBACK_TITLE = "JOSHYMC"
}
