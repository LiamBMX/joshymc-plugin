"""Build the JoshyMC logo font glyphs. Run from resourcepack/:

    py -m art.logo

Minecraft stitches font glyphs into 256x256 pages, so the logo is cut into two
192x128 halves (assets/joshymc/textures/font/logo_left.png and logo_right.png).
assets/minecraft/font/default.json maps them to two sizes:

    \\uE001 \\uF801 \\uE002   tab list header: 36 px tall, hanging DOWN from its line
                         (the header keeps blank lines under it)
    \\uE003 \\uF801 \\uE004   sidebar title: 32 px tall, hanging DOWN from the title bar over
                         three blank lines, so the sidebar background covers all of it

\\uF801 is a -1 space that closes the 1 px gap Minecraft leaves after every glyph.
Each half is 192 texels wide so it lands on a whole number of GUI pixels at both
sizes (54 and 48), and the halves meet without a seam.

Bedrock players (Geyser) can't use Java fonts (issue #1333), so the same logo is also
cut into three square cells of a Bedrock glyph sheet,
src/main/resources/bedrock/font/glyph_E9.png (\\uE900 \\uE901 \\uE902), which
BedrockPackManager adds to the Geyser pack. Bedrock draws every glyph one text line
tall from a 16x16 grid of square cells, so the 3:1 logo needs three cells side by side.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageFilter

HERE = Path(__file__).resolve().parent
SOURCE = HERE / "logo" / "joshymc_logo.webp"
FONT_TEXTURES = HERE.parent / "assets" / "joshymc" / "textures" / "font"
FONT_JSON = HERE.parent / "assets" / "minecraft" / "font" / "default.json"
HALF_W, H = 192, 128
# (left char, right char, rendered height, ascent). Glyph top = 7 - ascent within its line.
SIZES = {"tab": ("\uE001", "\uE002", 36, 7), "sidebar": ("\uE003", "\uE004", 32, 6)}
NEG1 = "\uF801"
# Bedrock glyph sheet: a 16x16 grid, the logo in the first BEDROCK_CELLS cells of row 0.
BEDROCK_SHEET = HERE.parent.parent / "src" / "main" / "resources" / "bedrock" / "font" / "glyph_E9.png"
BEDROCK_CELL = 32
BEDROCK_CELLS = 3


def build() -> None:
    logo = Image.open(SOURCE).convert("RGBA")
    logo = logo.crop(logo.getchannel("A").point(lambda a: 255 if a >= 40 else 0).getbbox())
    # Resize premultiplied so the transparent background never bleeds dark fringes in.
    scale = min(2 * HALF_W / logo.width, H / logo.height)
    size = (round(logo.width * scale), round(logo.height * scale))
    small = logo.convert("RGBa").resize(size, Image.LANCZOS).convert("RGBA")
    small = small.filter(ImageFilter.UnsharpMask(radius=1.0, percent=60, threshold=2))
    alpha = small.getchannel("A").point(lambda a: 0 if a < 40 else (255 if a >= 200 else a))
    small.putalpha(alpha)
    sheet = Image.new("RGBA", (2 * HALF_W, H), (0, 0, 0, 0))
    sheet.alpha_composite(small, ((2 * HALF_W - size[0]) // 2, (H - size[1]) // 2))
    BEDROCK_SHEET.parent.mkdir(parents=True, exist_ok=True)
    bedrock_sheet(sheet).save(BEDROCK_SHEET, optimize=True)
    left = sheet.crop((0, 0, HALF_W, H))
    right = sheet.crop((HALF_W, 0, 2 * HALF_W, H))
    # Minecraft trims empty columns off a glyph's right edge; the left half must not
    # lose any, or a gap opens between the halves.
    if not any(left.getpixel((HALF_W - 1, y))[3] for y in range(H)):
        left.putpixel((HALF_W - 1, H // 2), (0, 0, 0, 1))
    FONT_TEXTURES.mkdir(parents=True, exist_ok=True)
    left.save(FONT_TEXTURES / "logo_left.png")
    right.save(FONT_TEXTURES / "logo_right.png")

    font = json.loads(FONT_JSON.read_text(encoding="utf-8"))
    ours = {"joshymc:font/logo_left.png", "joshymc:font/logo_right.png"}
    providers = [p for p in font["providers"]
                 if p.get("file") not in ours and not (p.get("type") == "space" and NEG1 in p.get("advances", {}))]
    for left_char, right_char, height, ascent in SIZES.values():
        providers.append({"type": "bitmap", "file": "joshymc:font/logo_left.png", "ascent": ascent,
                          "height": height, "chars": [left_char]})
        providers.append({"type": "bitmap", "file": "joshymc:font/logo_right.png", "ascent": ascent,
                          "height": height, "chars": [right_char]})
    providers.append({"type": "space", "advances": {NEG1: -1}})
    font["providers"] = providers
    FONT_JSON.write_text(json.dumps(font, indent=2) + "\n", encoding="utf-8")
    print(f"logo {size[0]}x{size[1]} -> {FONT_TEXTURES / 'logo_left.png'}, logo_right.png; {FONT_JSON}; {BEDROCK_SHEET}")


def bedrock_sheet(sheet: Image.Image) -> Image.Image:
    """The Bedrock glyph sheet from the 384x128 Java logo: three square cells in row 0."""
    width, height = BEDROCK_CELLS * BEDROCK_CELL, BEDROCK_CELL
    small = sheet.convert("RGBa").resize((width, height), Image.LANCZOS).convert("RGBA")
    alpha = small.getchannel("A").point(lambda a: 0 if a < 40 else (255 if a >= 200 else a))
    small.putalpha(alpha)
    out = Image.new("RGBA", (16 * BEDROCK_CELL, 16 * BEDROCK_CELL), (0, 0, 0, 0))
    for i in range(BEDROCK_CELLS):
        cell = small.crop((i * BEDROCK_CELL, 0, (i + 1) * BEDROCK_CELL, height))
        # Bedrock trims empty columns off a glyph's sides; keep every cell full width so
        # the three pieces line up (an alpha-1 texel is invisible).
        for x in (0, BEDROCK_CELL - 1):
            if not any(cell.getpixel((x, y))[3] for y in range(height)):
                cell.putpixel((x, height // 2), (0, 0, 0, 1))
        out.paste(cell, (i * BEDROCK_CELL, 0))
    return out


if __name__ == "__main__":
    build()
    sys.exit(0)
