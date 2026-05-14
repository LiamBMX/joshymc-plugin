"""
Generates 16x16 enchant scroll textures for the JoshyMC resource pack.
Each scroll uses a scroll silhouette with a parchment color themed to the enchant category.
Requires only Python stdlib — no Pillow needed.
"""

import struct
import zlib
import os

OUT = "assets/joshymc/textures/item"

# ── PNG helpers ─────────────────────────────────────────────────────────────

def _chunk(tag: bytes, data: bytes) -> bytes:
    crc = zlib.crc32(tag + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)


def make_png(pixels: list, w: int = 16, h: int = 16) -> bytes:
    """Encode list of (R,G,B,A) tuples as a valid PNG byte string."""
    raw = bytearray()
    for y in range(h):
        raw.append(0)  # filter type None
        for x in range(w):
            raw.extend(pixels[y * w + x])

    ihdr = _chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    idat = _chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    iend = _chunk(b"IEND", b"")
    return b"\x89PNG\r\n\x1a\n" + ihdr + idat + iend


# ── Scroll shape ─────────────────────────────────────────────────────────────
#
# 16×16 layout:
#  Row 0,15   : transparent
#  Row 1      : roll top highlight
#  Row 2      : roll top mid-tone
#  Row 3      : roll top shadow
#  Row 4–11   : parchment body (alternating plain/text rows)
#  Row 12     : roll bottom shadow
#  Row 13     : roll bottom mid-tone
#  Row 14     : roll bottom highlight
#
# Columns 0–1 and 14–15 are transparent; column 2 and 13 are the border trim.

T, H, M, S, B, P, L = 0, 1, 2, 3, 4, 5, 6  # cell type codes

GRID = [
    [T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T],  # row  0
    [T, T, H, H, H, H, H, H, H, H, H, H, H, H, T, T],  # row  1
    [T, T, M, M, M, M, M, M, M, M, M, M, M, M, T, T],  # row  2
    [T, T, S, S, S, S, S, S, S, S, S, S, S, S, T, T],  # row  3
    [T, T, B, P, P, P, P, P, P, P, P, P, P, B, T, T],  # row  4
    [T, T, B, P, L, L, L, L, L, L, L, L, P, B, T, T],  # row  5
    [T, T, B, P, P, P, P, P, P, P, P, P, P, B, T, T],  # row  6
    [T, T, B, P, L, L, L, L, L, L, L, L, P, B, T, T],  # row  7
    [T, T, B, P, P, P, P, P, P, P, P, P, P, B, T, T],  # row  8
    [T, T, B, P, L, L, L, L, L, L, L, L, P, B, T, T],  # row  9
    [T, T, B, P, P, P, P, P, P, P, P, P, P, B, T, T],  # row 10
    [T, T, B, P, P, P, P, P, P, P, P, P, P, B, T, T],  # row 11
    [T, T, S, S, S, S, S, S, S, S, S, S, S, S, T, T],  # row 12
    [T, T, M, M, M, M, M, M, M, M, M, M, M, M, T, T],  # row 13
    [T, T, H, H, H, H, H, H, H, H, H, H, H, H, T, T],  # row 14
    [T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T],  # row 15
]

# Roll colours (wood-brown scroll rods)
ROLL_H = (210, 165, 100, 255)  # highlight
ROLL_M = (168, 118, 58, 255)   # mid-tone
ROLL_S = (112, 72, 32, 255)    # shadow
BORDER = (148, 102, 48, 255)   # side trim
TRANS  = (0, 0, 0, 0)


def make_scroll(parchment: tuple, text_line: tuple) -> bytes:
    """Build a 16×16 scroll PNG using the provided parchment and text colours."""
    colour_map = {
        T: TRANS,
        H: ROLL_H,
        M: ROLL_M,
        S: ROLL_S,
        B: BORDER,
        P: parchment,
        L: text_line,
    }
    pixels = []
    for row in GRID:
        for code in row:
            pixels.append(colour_map[code])
    return make_png(pixels)


# ── Per-enchant parchment colours ────────────────────────────────────────────
#
# (parchment_base, text_line) pairs — one per EnchantTarget category.
# Keep the values readable and visually distinct from each other.

SWORD      = ((235, 185, 160, 255), (180, 95, 85, 255))   # warm red
AXE        = ((240, 205, 155, 255), (195, 120, 55, 255))   # orange
HELMET     = ((175, 220, 215, 255), (75, 155, 160, 255))   # cyan/teal
CHESTPLATE = ((175, 200, 240, 255), (75, 110, 185, 255))   # blue
LEGGINGS   = ((215, 180, 240, 255), (135, 85, 185, 255))   # purple
BOOTS      = ((180, 235, 190, 255), (75, 160, 100, 255))   # green
SHOVEL     = ((240, 230, 150, 255), (180, 160, 60, 255))   # yellow
PICKAXE    = ((185, 210, 230, 255), (80, 120, 170, 255))   # steel blue
HOE        = ((245, 210, 125, 255), (185, 140, 48, 255))   # gold
ALL_TOOLS  = ((210, 205, 200, 255), (125, 120, 115, 255))  # neutral grey
BASE       = ((240, 220, 165, 255), (160, 128, 75, 255))   # plain parchment

ENCHANT_COLORS = {
    # Sword
    "lifesteal":    SWORD,
    "execute":      SWORD,
    "bleed":        SWORD,
    "adrenaline":   SWORD,
    "striker":      SWORD,
    # Axe
    "cleave":       AXE,
    "berserk":      AXE,
    "paralysis":    AXE,
    "blizzard":     AXE,
    # Helmet
    "night_vision": HELMET,
    "clarity":      HELMET,
    "focus":        HELMET,
    "xray":         HELMET,
    # Chestplate
    "overload":     CHESTPLATE,
    "dodge":        CHESTPLATE,
    "guardian":     CHESTPLATE,
    # Leggings
    "shockwave":    LEGGINGS,
    "valor":        LEGGINGS,
    "curse_swap":   LEGGINGS,
    # Boots
    "gears":        BOOTS,
    "springs":      BOOTS,
    "featherweight": BOOTS,
    "rockets":      BOOTS,
    # Shovel
    "glass_breaker": SHOVEL,
    # All tools
    "magnet":       ALL_TOOLS,
    # Pickaxe
    "autosmelt":    PICKAXE,
    "experience":   PICKAXE,
    "condenser":    PICKAXE,
    "explosive":    PICKAXE,
    # Hoe
    "ground_pound": HOE,
    "great_harvest": HOE,
    "blessing":     HOE,
}


def main():
    os.makedirs(OUT, exist_ok=True)

    # Base (un-enchanted) scroll
    data = make_scroll(*BASE)
    path = f"{OUT}/enchant_scroll.png"
    with open(path, "wb") as f:
        f.write(data)
    print(f"Wrote {path}")

    # Per-enchant variants
    for enchant_id, colours in ENCHANT_COLORS.items():
        data = make_scroll(*colours)
        path = f"{OUT}/enchant_scroll_{enchant_id}.png"
        with open(path, "wb") as f:
            f.write(data)
        print(f"Wrote {path}")

    print(f"\nDone — {1 + len(ENCHANT_COLORS)} scroll textures generated.")


if __name__ == "__main__":
    main()
