"""Build the JoshyMC logo font glyphs. Run from resourcepack/:

    py -m art.logo

Minecraft stitches font glyphs into 256x256 pages, so the logo is cut into two
192x128 halves (assets/joshymc/textures/font/logo_left.png and logo_right.png).
assets/minecraft/font/default.json maps them to two sizes:

    \\uE001 \\uF801 \\uE002   tab list header: 36 px tall, hanging DOWN from its line
                         (the header keeps blank lines under it)
    \\uE003 \\uF801 \\uE004   sidebar title: 30 px tall, rising UP from the title bar

\\uF801 is a -1 space that closes the 1 px gap Minecraft leaves after every glyph.
Each half is 192 texels wide so it lands on a whole number of GUI pixels at both
sizes (54 and 45), and the halves meet without a seam.
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
SIZES = {"tab": ("\uE001", "\uE002", 36, 7), "sidebar": ("\uE003", "\uE004", 30, 29)}
NEG1 = "\uF801"


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
    print(f"logo {size[0]}x{size[1]} -> {FONT_TEXTURES / 'logo_left.png'}, logo_right.png; {FONT_JSON}")


if __name__ == "__main__":
    build()
    sys.exit(0)
