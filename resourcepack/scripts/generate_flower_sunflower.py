#!/usr/bin/env python3
"""
Generates sunflower-themed (green + yellow) textures for the flower armor set.
Overwrites existing flower_*.png item icons and equipment layer textures in-place.
Run from the repo root:
    python3 resourcepack/scripts/generate_flower_sunflower.py
"""
import struct
import zlib
import os

BASE = os.path.join(os.path.dirname(__file__), "..", "assets", "joshymc", "textures")
ITEM = os.path.join(BASE, "item")
EQ   = os.path.join(BASE, "entity", "equipment")

# ── Colour palette (R, G, B, A) ──────────────────────────────────────────────
T  = (  0,   0,   0,   0)   # transparent
DG = ( 28,  80,  18, 255)   # dark green    (outline / shadow)
MG = ( 55, 130,  35, 255)   # medium green  (main body)
LG = ( 95, 175,  55, 255)   # light green   (highlight)
DB = ( 55,  25,   5, 255)   # dark brown    (sunflower centre – seeds)
MB = ( 95,  55,  15, 255)   # medium brown  (sunflower centre)
DY = (200, 155,   0, 255)   # dark yellow   (petal shadow / inner)
MY = (245, 200,  10, 255)   # medium yellow (petal main)
LY = (255, 235,  95, 255)   # light yellow  (petal highlight / outer)

# ── PNG encode ────────────────────────────────────────────────────────────────
def _chunk(tag, data):
    body = tag + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)


def to_png(pixels, w, h):
    raw = b"".join(
        b"\x00" + bytes(b for px in pixels[r * w:(r + 1) * w] for b in px)
        for r in range(h)
    )
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n"
            + _chunk(b"IHDR", ihdr)
            + _chunk(b"IDAT", zlib.compress(raw, 9))
            + _chunk(b"IEND", b""))


def save(path, pixels, w, h):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(to_png(pixels, w, h))
    print(f"  {path}")


# ── Canvas helpers for equipment textures ────────────────────────────────────
def new_canvas(w, h):
    return [[0, 0, 0, 0] for _ in range(w * h)]


def put(c, w, x, y, col):
    if 0 <= x < w:
        h = len(c) // w
        if 0 <= y < h:
            c[y * w + x] = list(col)


def fill_rect(c, w, x1, y1, x2, y2, col):
    for y in range(y1, y2 + 1):
        for x in range(x1, x2 + 1):
            put(c, w, x, y, col)


def draw_border(c, w, x1, y1, x2, y2, col):
    for x in range(x1, x2 + 1):
        put(c, w, x, y1, col)
        put(c, w, x, y2, col)
    for y in range(y1 + 1, y2):
        put(c, w, x1, y, col)
        put(c, w, x2, y, col)


def flatten(c):
    return [tuple(px) for px in c]


# ── 16×16 item icon helpers ───────────────────────────────────────────────────
# Colour key for icon strings (each char = one pixel)
K = {
    '.': T,   # transparent
    'G': DG,  # dark green
    'g': MG,  # medium green
    'l': LG,  # light green
    'b': DB,  # dark brown
    'B': MB,  # brown
    'd': DY,  # dark yellow
    'y': MY,  # medium yellow
    'Y': LY,  # light yellow
}


def icon(rows):
    """Convert list of 16-char strings to a flat 16×16 pixel list."""
    assert len(rows) == 16, f"expected 16 rows, got {len(rows)}"
    for i, r in enumerate(rows):
        assert len(r) == 16, f"row {i} has {len(r)} chars, expected 16: {r!r}"
    return [K[c] for row in rows for c in row]


# ── 16×16 icon designs ────────────────────────────────────────────────────────
#
# Columns: 0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15
# Colour key (see K above):  . g G l  y Y d  b B

# Helmet – green dome, sunflower disk on front face
HELMET = icon([
    "................",  # 0
    "....yyyyyyyy....",  # 1  top rim  (4+8+4=16)
    "...yggggggggy...",  # 2           (3+1+8+1+3=16)
    "..yggggggggggy..",  # 3           (2+1+10+1+2=16)
    "..gggYYYYYYggg..",  # 4  petal ring top   (2+3+6+3+2=16)
    "..ggYbbbbbbYgg..",  # 5  centre ring      (2+2+1+6+1+2+2=16)
    "..ggYbBBBBbYgg..",  # 6  seed centre      (2+2+1+1+4+1+1+2+2=16)
    "..ggYbBBBBbYgg..",  # 7
    "..ggYbbbbbbYgg..",  # 8
    "..gggYYYYYYggg..",  # 9  petal ring btm
    "..gggggggggggg..",  # 10 lower dome  (2+12+2=16)
    "..gg........gg..",  # 11 cheeks open (2+2+8+2+2=16)
    "..gg........gg..",  # 12
    "................",  # 13
    "................",  # 14
    "................",  # 15
])

# Chestplate – shoulder straps at top, sunflower chest piece
CHESTPLATE = icon([
    ".ggg........ggg.",  # 0  shoulder straps   (1+3+8+3+1=16)
    ".ggg........ggg.",  # 1
    ".gggyyyyyyyygg..",  # 2  neckline yellow   (1+3+8+2+2=16)
    "..gggggggggggg..",  # 3  body top          (2+12+2=16)
    "..gggYYYYYYggg..",  # 4  petal ring top   (2+3+6+3+2=16)
    "..ggYbbbbbbYgg..",  # 5
    "..ggYbBBBBbYgg..",  # 6
    "..ggYbBBBBbYgg..",  # 7
    "..ggYbbbbbbYgg..",  # 8
    "..gggYYYYYYggg..",  # 9
    "..gggggggggggg..",  # 10
    "..gggggggggggg..",  # 11
    "...gggggggggg...",  # 12 body taper (3+10+3=16)
    "................",  # 13
    "................",  # 14
    "................",  # 15
])

# Leggings – waistband with sunflower trim, two leg tubes
LEGGINGS = icon([
    "................",  # 0
    "..gggggggggggg..",  # 1  waistband         (2+12+2=16)
    "..ggYYYYYYYYgg..",  # 2  yellow strip      (2+2+8+2+2=16)
    "..ggYBBBBBBYgg..",  # 3  brown accent      (2+2+1+6+1+2+2=16)
    "..ggYYYYYYYYgg..",  # 4
    "..gggggggggggg..",  # 5
    "..gggg....gggg..",  # 6  leg tubes         (2+4+4+4+2=16)
    "..gggg....gggg..",  # 7
    "..gggg....gggg..",  # 8
    "..gggg....gggg..",  # 9
    "..gggg....gggg..",  # 10
    "..gggg....gggg..",  # 11
    "................",  # 12
    "................",  # 13
    "................",  # 14
    "................",  # 15
])

# Boots – two green boot shapes with yellow trim strip
BOOTS = icon([
    "................",  # 0
    ".ggggg..ggggg...",  # 1           (1+5+2+5+3=16)
    ".gYYYg..gYYYg...",  # 2           (1+1+3+1+2+1+3+1+3=16)
    ".gYYYg..gYYYg...",  # 3
    ".gYYYg..gYYYg...",  # 4
    ".ggggg..ggggg...",  # 5
    ".ggggg..ggggg...",  # 6
    ".ggggg..ggggg...",  # 7
    ".gggggg.gggggg..",  # 8  toe extends (1+6+1+6+2=16)
    "................",  # 9
    "................",  # 10
    "................",  # 11
    "................",  # 12
    "................",  # 13
    "................",  # 14
    "................",  # 15
])

# Flower Spade – sunflower bloom (petals + seed centre) atop a green stem
# The blade IS the sunflower; the stem is the handle.
SPADE = icon([
    "................",  # 0
    "...yYYYYYYy.....",  # 1  outer top petals  (3+1+6+1+5=16)
    "..yYbbbbbbYy....",  # 2  inner petals      (2+1+1+6+1+1+4=16)
    "..YybBBBBbyY....",  # 3  seed centre       (2+1+1+1+4+1+1+1+4=16)
    "..YybBBBBbyY....",  # 4
    "..yYbbbbbbYy....",  # 5
    "...yYYYYYYy.....",  # 6  outer btm petals
    "........g.......",  # 7  stem              (8+1+7=16)
    "........g.......",  # 8
    ".......gGg......",  # 9  leaf node         (7+1+1+1+6=16)
    "........g.......",  # 10
    ".......gGg......",  # 11
    "........g.......",  # 12
    "........g.......",  # 13
    "........g.......",  # 14
    "................",  # 15
])

# ── Equipment layer textures (64×32) ─────────────────────────────────────────
#
# Standard Minecraft humanoid UV layout (64×32):
#   HEAD:    top(8,0,15,7) btm(16,0,23,7)
#            right(0,8,7,15) face(8,8,15,15) left(16,8,23,15) back(24,8,31,15)
#   BODY:    top(20,16,27,19) btm(28,16,35,19)
#            right(16,20,19,31) front(20,20,27,31) left(28,20,31,31) back(32,20,39,31)
#   R.ARM:   top(44,16,47,19) btm(48,16,51,19)
#            right(40,20,43,31) front(44,20,47,31) left(48,20,51,31) back(52,20,55,31)
#   R.LEG:   top(4,16,7,19) btm(8,16,11,19)
#            right(0,20,3,31) front(4,20,7,31) left(8,20,11,31) back(12,20,15,31)
#
# humanoid_leggings UV (64×32):
#   R.LEG:   same coords as R.LEG above
#   L.LEG:   top(20,16,23,19) btm(24,16,27,19)
#             right(16,20,19,31) front(20,20,23,31) left(24,20,27,31) back(28,20,31,31)


def sunflower_disk(c, w, cx, cy, r_seed=1, r_centre=2, r_petal=3):
    """
    Draw a tiny sunflower disk centred at (cx, cy).
    r_seed: radius of dark seed area
    r_centre: radius of brown centre
    r_petal: radius including petals
    """
    for dy in range(-r_petal, r_petal + 1):
        for dx in range(-r_petal, r_petal + 1):
            d = (dx * dx + dy * dy) ** 0.5
            if d <= r_seed:
                col = DB
            elif d <= r_centre:
                col = MB
            elif d <= r_petal:
                # Only colour the cardinal / diagonal arms = petals
                if abs(dx) <= 0 or abs(dy) <= 0 or abs(dx) == abs(dy):
                    col = MY
                else:
                    continue
            else:
                continue
            put(c, w, cx + dx, cy + dy, col)


def make_humanoid_texture(use_yellow_tint=False):
    """
    Build a 64×32 humanoid equipment layer.
    use_yellow_tint: True → helmet/chest/boot layer (flower_yellow);
                     False → legging overlay layer (flower).
    """
    W, H = 64, 32
    c = new_canvas(W, H)

    base   = LY if use_yellow_tint else MG
    mid    = MY if use_yellow_tint else LG
    dark   = DY if use_yellow_tint else DG
    accent = MB

    # ── HEAD ─────────────────────────────────────────────────────────────────
    # top / bottom faces
    fill_rect(c, W,  8, 0, 15, 7, base)
    fill_rect(c, W, 16, 0, 23, 7, dark)
    # side / front / back faces
    fill_rect(c, W,  0, 8,  7, 15, mid)
    fill_rect(c, W,  8, 8, 15, 15, base)   # front face
    fill_rect(c, W, 16, 8, 23, 15, mid)
    fill_rect(c, W, 24, 8, 31, 15, mid)
    # sunflower on helmet front face (centred at 11,11 – mid of 8-15 x 8-15)
    sunflower_disk(c, W, 11, 11, r_seed=1, r_centre=2, r_petal=3)
    # dark outlines around head boxes
    draw_border(c, W,  8, 0, 15,  7, dark)
    draw_border(c, W,  0, 8, 31, 15, dark)

    # ── BODY ─────────────────────────────────────────────────────────────────
    fill_rect(c, W, 20, 16, 27, 19, mid)   # top
    fill_rect(c, W, 28, 16, 35, 19, dark)  # bottom
    fill_rect(c, W, 16, 20, 19, 31, mid)   # right side
    fill_rect(c, W, 20, 20, 27, 31, base)  # front
    fill_rect(c, W, 28, 20, 31, 31, mid)   # left side
    fill_rect(c, W, 32, 20, 39, 31, mid)   # back
    # yellow horizontal stripe on body front
    for x in range(20, 28):
        put(c, W, x, 23, MY)
        put(c, W, x, 27, MY)
    draw_border(c, W, 16, 20, 39, 31, dark)

    # ── RIGHT ARM ────────────────────────────────────────────────────────────
    fill_rect(c, W, 44, 16, 47, 19, mid)
    fill_rect(c, W, 48, 16, 51, 19, dark)
    fill_rect(c, W, 40, 20, 43, 31, mid)
    fill_rect(c, W, 44, 20, 47, 31, base)
    fill_rect(c, W, 48, 20, 51, 31, mid)
    fill_rect(c, W, 52, 20, 55, 31, mid)
    for y in range(23, 29):
        put(c, W, 45, y, MY)
        put(c, W, 46, y, MY)
    draw_border(c, W, 40, 20, 55, 31, dark)

    # ── RIGHT LEG (used for boots in humanoid layer) ─────────────────────────
    fill_rect(c, W,  4, 16,  7, 19, mid)
    fill_rect(c, W,  8, 16, 11, 19, dark)
    fill_rect(c, W,  0, 20,  3, 31, mid)
    fill_rect(c, W,  4, 20,  7, 31, base)
    fill_rect(c, W,  8, 20, 11, 31, mid)
    fill_rect(c, W, 12, 20, 15, 31, mid)
    for y in range(23, 29):
        put(c, W, 5, y, MY)
        put(c, W, 6, y, MY)
    draw_border(c, W, 0, 20, 15, 31, dark)

    return flatten(c), W, H


def make_leggings_texture():
    """
    Build a 64×32 humanoid_leggings equipment layer (green + yellow).
    """
    W, H = 64, 32
    c = new_canvas(W, H)

    # ── RIGHT LEG ────────────────────────────────────────────────────────────
    fill_rect(c, W,  4, 16,  7, 19, LG)
    fill_rect(c, W,  8, 16, 11, 19, DG)
    fill_rect(c, W,  0, 20,  3, 31, MG)
    fill_rect(c, W,  4, 20,  7, 31, LG)  # front
    fill_rect(c, W,  8, 20, 11, 31, MG)
    fill_rect(c, W, 12, 20, 15, 31, MG)
    # yellow stripe
    for y in range(22, 30):
        put(c, W, 5, y, MY)
        put(c, W, 6, y, MY)
    draw_border(c, W, 0, 20, 15, 31, DG)

    # ── LEFT LEG ─────────────────────────────────────────────────────────────
    fill_rect(c, W, 20, 16, 23, 19, LG)
    fill_rect(c, W, 24, 16, 27, 19, DG)
    fill_rect(c, W, 16, 20, 19, 31, MG)
    fill_rect(c, W, 20, 20, 23, 31, LG)  # front
    fill_rect(c, W, 24, 20, 27, 31, MG)
    fill_rect(c, W, 28, 20, 31, 31, MG)
    for y in range(22, 30):
        put(c, W, 21, y, MY)
        put(c, W, 22, y, MY)
    draw_border(c, W, 16, 20, 31, 31, DG)

    return flatten(c), W, H


# ── Write everything ──────────────────────────────────────────────────────────
print("Generating sunflower flower-armor textures...")

# Item icons (16×16)
save(os.path.join(ITEM, "flower_helmet.png"),     HELMET,     16, 16)
save(os.path.join(ITEM, "flower_chestplate.png"), CHESTPLATE, 16, 16)
save(os.path.join(ITEM, "flower_leggings.png"),   LEGGINGS,   16, 16)
save(os.path.join(ITEM, "flower_boots.png"),      BOOTS,      16, 16)
save(os.path.join(ITEM, "flower_spade.png"),      SPADE,      16, 16)

# Equipment layer textures (64×32)
pix, w, h = make_humanoid_texture(use_yellow_tint=False)
save(os.path.join(EQ, "humanoid", "flower.png"), pix, w, h)

pix, w, h = make_humanoid_texture(use_yellow_tint=True)
save(os.path.join(EQ, "humanoid", "flower_yellow.png"), pix, w, h)

pix, w, h = make_leggings_texture()
save(os.path.join(EQ, "humanoid_leggings", "flower.png"), pix, w, h)

print("Done.")
