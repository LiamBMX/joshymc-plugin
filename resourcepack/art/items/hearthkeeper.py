"""Hearthkeeper: Woodland Limited Edition shield.

A cozy hearth shield. Vertical oak planks sit in a riveted, blackened-iron heater frame.
The front carries a raised stone fireplace (dressed sandstone jambs and arch with a
keystone, fieldstone spandrels, a slate hearth and a walnut mantel) with a fire glowing on
crossed logs between copper-knobbed andirons, ringed by a grapevine wreath of autumn leaves,
berries and acorns tied at the bottom with a buffalo-plaid bow. The back is braced like
a cabin door and carries a quilted plaid wool pad, a leather forearm strap with a brass
buckle, a copper-capped wrapped hand grip and a little brass bell hanging from the top
batten.

Frame: the board stands in the XY plane centred on x = 8, front (-Z) decorated.
"""
from __future__ import annotations

import math
import random

from art.kit import (arc, bar, box, canvas, display, display_for_state, fill, grain, mirror, mix, model, place,
                     prism, ramp, rgba, save, shade, speckle, turn)

ID = "hearthkeeper"
NAME = "Hearthkeeper"
KIND = "shield"

# --------------------------------------------------------------------------------------
# Palettes (dark -> light, hue shifted)
# --------------------------------------------------------------------------------------
OAK_BASES = ("#9c6d42", "#91633b", "#a47447", "#976840", "#a07145", "#8d5f39")
IRON = ramp("#35323b", 7, 0.62, 0.06)
BRASS = ramp("#c99a33", 7, 0.72, 0.1)
COPPER = ramp("#b8643a", 7, 0.7, 0.08)
WALNUT = ramp("#63402a", 6, 0.62, 0.08)
LEATHER = ramp("#80502e", 7, 0.7, 0.08)
SAND = ramp("#c4b193", 6, 0.62, 0.06)
PLAID_RED = ramp("#b3252b", 5, 0.55, 0.06)
PLAID_BLACK = ramp("#261d21", 4, 0.45, 0.05)
PINE = ramp("#357a47", 6, 0.7, 0.1)
MAPLE = ramp("#d0401f", 6, 0.72)
AMBER = ramp("#ec8a1f", 6, 0.7)
GOLD = ramp("#dfb035", 6, 0.66)
CRIMSON = ramp("#a3222a", 6, 0.62)
RUSSET = ramp("#9a5a2a", 6, 0.66)
VINE = ramp("#5a3b27", 5, 0.62, 0.08)
PALE = ramp("#f2d772", 6, 0.6)
FIRE = ("#6e1206", "#b8290c", "#e8540f", "#fb921d", "#ffcc45", "#fff4c2")
THREAD = "#efe0bb"
STONE_TONES = ("#8e877e", "#9c8a76", "#8a8580", "#8b8a76", "#a79e90", "#8c7b6d", "#968e84")

# --------------------------------------------------------------------------------------
# Board outline: a heater shield, straight sides that curve into a blunt point
# --------------------------------------------------------------------------------------
CX = 8.0
HALF = 11.0
BX0, BX1 = CX - HALF, CX + HALF
TOP = 21.0
SHOULDER = 9.0                     # the sides run straight down to here
TIP = -5.0
R_SIDE = (HALF ** 2 + (SHOULDER - TIP) ** 2) / (2 * HALF)
ARC_CX = CX - HALF + R_SIDE        # centre of the LEFT side's arc (it sits right of the middle)
FZ, BZ = 7.0, 9.0                  # board front / back faces
RIM_W = 2.0
RIM_Z0, RIM_Z1 = 6.3, 9.7
BOARD_W, BOARD_H = int(2 * (BX1 - BX0)), int(2 * (TOP - TIP))   # board sheet size in px (44 x 52)

FP_Y = 10.3                        # fireplace arch centre height
WREATH_C = (8.0, 10.3)
WREATH_R = 7.0
LEAF = 3.6
GRIP = (8.0, 9.0, 10.6)
GUI_ROTATION = (8, 165, -3)


def half_width(y: float) -> float:
    if y >= SHOULDER:
        return HALF
    d = SHOULDER - y
    return CX - (ARC_CX - math.sqrt(max(0.0, R_SIDE ** 2 - d * d)))


def edge_distance(x: float, y: float) -> float:
    """Distance in units from a board point to the outline (negative outside)."""
    d = TOP - y
    if y >= SHOULDER:
        return min(d, x - BX0, BX1 - x)
    dl = R_SIDE - math.hypot(x - ARC_CX, y - SHOULDER)
    dr = R_SIDE - math.hypot(x - (2 * CX - ARC_CX), y - SHOULDER)
    return min(d, dl, dr)


# --------------------------------------------------------------------------------------
# Texture helpers
# --------------------------------------------------------------------------------------

def _put(img, x, y, colour) -> None:
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), rgba(colour))


def _blend(img, x, y, colour, t: float) -> None:
    if 0 <= x < img.width and 0 <= y < img.height:
        c = img.getpixel((x, y))
        if c[3]:
            img.putpixel((x, y), rgba(mix(c, colour, t)))


# --------------------------------------------------------------------------------------
# Textures (2 texels per model unit throughout)
# --------------------------------------------------------------------------------------

PLANK_EDGES = (0, 8, 15, 22, 30, 37, 44)   # columns of the 44 x 52 board, front view


def paint_planks(front: bool):
    """The whole board face as one 64 px sheet (44 x 52 px used)."""
    img = canvas(64)
    rng = random.Random(31 if front else 47)
    last = BOARD_W - 1
    for i in range(6):
        a, b = PLANK_EDGES[i], PLANK_EDGES[i + 1] - 1
        if not front:
            a, b = last - b, last - a
        base = OAK_BASES[i] if front else shade(OAK_BASES[i], -0.16)
        pal = ramp(base, 6, 0.6, 0.08)
        fill(img, (a, 0, b, BOARD_H - 1), pal[3])
        grain(img, (a + 1, 0, b - 1, BOARD_H - 1), [pal[2], pal[2], pal[4]], axis="y", seed=rng.randrange(999),
              min_len=4, max_len=12, density=0.55)
        grain(img, (a + 1, 0, b - 1, BOARD_H - 1), [pal[1]], axis="y", seed=rng.randrange(999),
              min_len=2, max_len=5, density=0.16)
        fill(img, (a, 0, a, BOARD_H - 1), pal[4])
        fill(img, (b, 0, b, BOARD_H - 1), pal[0])
        for _ in range(rng.choice((1, 1, 2))):
            kx, ky = rng.randint(a + 2, b - 2), rng.randint(4, BOARD_H - 5)
            for dy in (-2, 2):
                _put(img, kx, ky + dy, pal[2])
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                _put(img, kx + dx, ky + dy, pal[1])
            _put(img, kx, ky, pal[0])
            _put(img, kx - 1, ky - 1, pal[5])
        if rng.random() < 0.5:
            jy = rng.randint(12, 40)
            fill(img, (a + 1, jy, b - 1, jy), pal[0])
            fill(img, (a + 1, jy + 1, b - 1, jy + 1), pal[4])
        if front:
            mid = (a + b) // 2
            for ny in (8, 37):          # square nails into the battens behind
                _put(img, mid, ny, IRON[0])
                _put(img, mid, ny - 1, pal[5])
        else:
            for sy in range(3, BOARD_H, 7):   # faint saw marks on the unfinished back
                for x in range(a + 1, b):
                    if rng.random() < 0.5:
                        _blend(img, x, sy, pal[1], 0.35)
    # shadow cast by the iron rim onto the planks
    for py in range(BOARD_H):
        for px in range(BOARD_W):
            x = (BX1 - (px + 0.5) / 2) if front else (BX0 + (px + 0.5) / 2)
            d = edge_distance(x, TOP - (py + 0.5) / 2)
            if RIM_W <= d < RIM_W + 0.55:
                _blend(img, px, py, "#24140f", 0.5)
            elif RIM_W + 0.55 <= d < RIM_W + 1.05:
                _blend(img, px, py, "#24140f", 0.2)
    return img


def paint_rim():
    """Half-round hammered iron band: the profile runs across u, 4 px = 2 units."""
    img = canvas(32)
    prof = (IRON[1], IRON[3], IRON[4], IRON[2])
    for x in range(32):
        fill(img, (x, 0, x, 31), prof[x % 4])
    rng = random.Random(3)
    for y in range(32):
        for x in range(32):
            c, r = x % 4, rng.random()
            if c in (1, 2) and r < 0.14:
                _put(img, x, y, IRON[2])
            elif c == 2 and r < 0.2:
                _put(img, x, y, IRON[5])
            elif c == 1 and r < 0.18:
                _put(img, x, y, IRON[4])
    return img


def paint_iron():
    """Forged iron: a few soft hammer marks, no speckle."""
    img = canvas(32, fill=IRON[2])
    rng = random.Random(5)
    for _ in range(80):
        x, y = rng.randrange(32), rng.randrange(32)
        c = rng.choice((IRON[1], IRON[3], IRON[3], IRON[1], IRON[4]))
        _put(img, x, y, c)
        _put(img, x + 1, y, c)
    return img


def paint_rivets():
    """Every 2 x 2 cell is one domed brass rivet head."""
    img = canvas(32)
    for y in range(0, 32, 2):
        for x in range(0, 32, 2):
            _put(img, x, y, BRASS[6])
            _put(img, x + 1, y, BRASS[4])
            _put(img, x, y + 1, BRASS[3])
            _put(img, x + 1, y + 1, BRASS[1])
    return img


def paint_fieldstone():
    """Rounded fieldstones in mortar (a wrapped Voronoi pattern, lit from the top left)."""
    size, rng = 32, random.Random(17)
    seeds = [(rng.uniform(0, size), rng.uniform(0, size), ramp(rng.choice(STONE_TONES), 5, 0.52, 0.06))
             for _ in range(15)]
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            ranked = []
            for sx, sy, pal in seeds:
                dx = (x + 0.5 - sx + size / 2) % size - size / 2
                dy = (y + 0.5 - sy + size / 2) % size - size / 2
                ranked.append((math.sqrt(dx * dx + 1.6 * dy * dy), dx, dy, pal))
            ranked.sort(key=lambda t: t[0])
            d1, dx, dy, pal = ranked[0]
            gap = ranked[1][0] - d1
            if gap < 0.9:
                c = "#3b332f" if dy > 0 else "#4b423c"
            else:
                lit = -(dx + dy) / (math.hypot(dx, dy) + 1e-6) / 1.414
                if gap < 2.2 and lit > 0.25:
                    c = pal[4]
                elif gap < 2.2 and lit < -0.25:
                    c = pal[1]
                elif lit > 0.35:
                    c = pal[3]
                else:
                    c = pal[2]
            img.putpixel((x, y), rgba(c))
    return img


def paint_ashlar():
    """Dressed sandstone blocks, 3 x 4 px (1.5 x 2 units) each; the keystone sits at (0, 28)."""
    img = canvas(32)
    rng = random.Random(19)
    for cy in range(0, 28, 4):
        for cx in range(0, 30, 3):
            base = SAND[3] if rng.random() < 0.7 else mix(SAND[3], SAND[2], 0.5)
            fill(img, (cx, cy, cx + 2, cy + 3), base)
            fill(img, (cx, cy, cx + 1, cy), SAND[4])
            _put(img, cx, cy + 1, SAND[4])
            _put(img, cx, cy, SAND[5])
            fill(img, (cx + 2, cy, cx + 2, cy + 3), SAND[1])
            fill(img, (cx, cy + 3, cx + 2, cy + 3), SAND[1])
            if rng.random() < 0.5:
                _put(img, cx + 1, cy + rng.randint(1, 2), SAND[2])
    # keystone: brighter, with a tiny gilded flame carved in
    fill(img, (0, 28, 2, 31), SAND[4])
    fill(img, (0, 28, 1, 28), SAND[5])
    fill(img, (2, 28, 2, 31), SAND[2])
    _put(img, 1, 29, BRASS[5])
    _put(img, 1, 30, BRASS[3])
    fill(img, (0, 31, 2, 31), SAND[1])
    return img


def paint_slate():
    """Slate hearthstone: a lit top arris, then dark strata."""
    img = canvas(32, fill="#4a474d")
    for y in range(32):
        if y % 3 == 2:
            fill(img, (0, y, 31, y), "#3b383e")
    speckle(img, (0, 0, 31, 31), ["#57535a", "#413e44"], 0.18, seed=9)
    fill(img, (0, 0, 31, 0), "#8a8590")
    fill(img, (0, 1, 31, 1), "#625e66")
    return img


def paint_soot():
    img = canvas(32, fill="#2a2222")
    speckle(img, (0, 0, 31, 31), ["#1a1414", "#3a2c28", "#4a2a1c"], 0.35, seed=8)
    return img


def paint_firebox():
    """Soot-black brick back wall, lit orange from below by the fire (9 x 12 px used)."""
    img = canvas(32, fill="#1b1414")
    for y in range(32):
        if y % 2 == 0:
            fill(img, (0, y, 31, y), "#120d0e")
        else:
            fill(img, (0, y, 31, y), "#2b201d")
            for x in range(((y // 2) % 2) * 2, 32, 4):
                _put(img, x, y, "#120d0e")
    glow = ("#4a1c0e", "#6e2a10", "#983c14", "#c4521a")
    for y in range(4, 13):
        t = (y - 3) / 9
        for x in range(32):
            _blend(img, x, y, glow[min(3, int(t * 4))], 0.3 + 0.55 * t)
    return img


FLAMES = {  # name: (x, y, w, h in px, tongues (centre px, height px, half width px), palette)
    "back": (0, 0, 9, 11, ((2.0, 7.5, 2.3), (4.6, 11.0, 2.9), (7.1, 7.0, 2.1)),
             (FIRE[0], FIRE[1], FIRE[2], FIRE[2], FIRE[3])),
    "mid": (10, 0, 7, 9, ((2.1, 6.0, 2.0), (3.8, 9.0, 2.4), (5.4, 5.5, 1.7)),
            (FIRE[1], FIRE[2], FIRE[3], FIRE[4])),
    "core": (18, 0, 5, 6, ((1.9, 4.5, 1.7), (3.2, 6.0, 1.9)), (FIRE[3], FIRE[4], FIRE[5])),
}
EMBER_UV = (0, 8, 16, 16)


def _flame(img, ox, oy, w, h, tongues, pal) -> None:
    heights = []
    for x in range(w):
        best = 0.0
        for cx, th, hw in tongues:
            d = abs(x + 0.5 - cx) / hw
            if d < 1:
                best = max(best, th * (1 - d ** 1.5))
        heights.append(best)
    inside = [[(h - y) <= heights[x] + 0.35 for x in range(w)] for y in range(h)]
    big = 99
    dist = [[big if inside[y][x] else 0 for x in range(w)] for y in range(h)]
    for _ in range(w + h):
        for y in range(h):
            for x in range(w):
                if not inside[y][x]:
                    continue
                best = big
                for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                    nx, ny = x + dx, y + dy
                    if ny >= h:
                        continue
                    best = 0 if (nx < 0 or nx >= w or ny < 0) else min(best, dist[ny][nx])
                    if best == 0:
                        break
                dist[y][x] = min(dist[y][x], best + 1)
    r, g, b, _ = rgba(pal[0])
    for y in range(h):
        for x in range(w):
            if inside[y][x]:
                _put(img, ox + x, oy + y, pal[min(len(pal) - 1, dist[y][x] - 1)])
            elif any(0 <= x + dx < w and 0 <= y + dy < h and inside[y + dy][x + dx]
                     for dx, dy in ((-1, 0), (1, 0), (0, 1))):
                _put(img, ox + x, oy + y, (r, g, b, 120))


def paint_fire():
    img = canvas(32)
    for x, y, w, h, tongues, pal in FLAMES.values():
        _flame(img, x, y, w, h, tongues, pal)
    fill(img, (0, 16, 31, 31), "#3a0f08")
    speckle(img, (0, 16, 31, 31), [FIRE[1], FIRE[2]], 0.5, seed=41)
    speckle(img, (0, 16, 31, 31), [FIRE[3], FIRE[4]], 0.22, seed=42)
    return img


def paint_log():
    """Charred bark (grain along v) split by glowing cracks."""
    img = canvas(32, fill="#2e211b")
    grain(img, (0, 0, 31, 31), ["#1e1612", "#3d2b21", "#4a3326"], axis="y", seed=12, min_len=2, max_len=6,
          density=0.7)
    rng = random.Random(13)
    for _ in range(46):
        x, y = rng.randrange(32), rng.randrange(32)
        for i in range(rng.randint(1, 2)):
            _put(img, x + i, y, rng.choice((FIRE[2], FIRE[3], FIRE[2], FIRE[1])))
    return img


def paint_log_end():
    img = canvas(32)
    for y in range(0, 32, 2):
        for x in range(0, 32, 2):
            _put(img, x, y, "#9a6a3a")
            _put(img, x + 1, y, "#7a5030")
            _put(img, x, y + 1, "#6a4228")
            _put(img, x + 1, y + 1, FIRE[2])
    return img


def paint_walnut(axis: str):
    img = canvas(32, fill=WALNUT[3])
    grain(img, (0, 0, 31, 31), [WALNUT[2], WALNUT[4], WALNUT[2]], axis=axis, seed=21, min_len=4, max_len=12,
          density=0.6)
    grain(img, (0, 0, 31, 31), [WALNUT[1]], axis=axis, seed=22, min_len=2, max_len=5, density=0.15)
    return img


def paint_mantel():
    """Walnut with the grain along u and a bright bevel on row 0 (the top front edge)."""
    img = paint_walnut("x")
    fill(img, (0, 0, 31, 0), WALNUT[5])
    grain(img, (0, 0, 31, 0), [WALNUT[4]], axis="x", seed=23, min_len=2, max_len=4, density=0.3)
    return img


def paint_batten():
    """The top batten, with the maker's hearth brand burnt into its middle."""
    img = paint_walnut("x")
    fill(img, (0, 0, 31, 0), WALNUT[4])
    fill(img, (0, 4, 31, 4), WALNUT[1])
    brand = ("..#..",
             ".##..",
             ".###.",
             "##.##",
             "#####")
    for y, row in enumerate(brand):
        for x, ch in enumerate(row):
            if ch == "#":
                _put(img, 16 + x, y, "#24130c")
    return img


LEAF_MASKS = {
    "maple": ("....#....",
              ".#..#..#.",
              ".#.###.#.",
              "#########",
              ".#######.",
              "..#####..",
              ".#######.",
              "...###...",
              "....#...."),
    "aspen": ("....#....",
              "...###...",
              "..#####..",
              ".#######.",
              ".#######.",
              ".#######.",
              "..#####..",
              "...###...",
              "....#...."),
    "oak": ("....#....",
            "...###...",
            "..#####..",
            "...###...",
            "..#####..",
            ".#######.",
            "..#####..",
            "...###...",
            "....#...."),
    "pine": ("....#....",
             ".#..#..#.",
             "..#.#.#..",
             "#..###..#",
             ".#..#..#.",
             "..#.#.#..",
             "...###...",
             "....#....",
             "....#...."),
}
# Atlas cells (9 x 9 px at a 10 px stride in a 64 px sheet): (mask, palette)
LEAF_CELLS = (("maple", MAPLE, AMBER), ("maple", AMBER, GOLD), ("maple", CRIMSON, MAPLE), ("maple", GOLD, PALE),
              ("aspen", GOLD, PALE), ("aspen", AMBER, GOLD), ("oak", RUSSET, AMBER), ("oak", MAPLE, AMBER),
              ("pine", PINE, PINE), ("pine", ramp("#2c6040", 6, 0.7), PINE), ("aspen", MAPLE, GOLD),
              ("maple", RUSSET, AMBER))


def _leaf(img, ox, oy, mask, outer, inner) -> None:
    """Deep, shadowed rim fading into a warm heart, lit from the upper left, pale vein."""
    h, w = len(mask), len(mask[0])

    def on(x, y):
        return 0 <= x < w and 0 <= y < h and mask[y][x] != "."

    dist = {(x, y): 99 for y in range(h) for x in range(w) if on(x, y)}
    changed = True
    while changed:
        changed = False
        for (x, y), d in dist.items():
            nd = min(dist.get((x + dx, y + dy), 0) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))) + 1
            if nd < d:
                dist[(x, y)] = nd
                changed = True
    pine = mask is LEAF_MASKS["pine"]
    mid = w // 2
    for (x, y), d in dist.items():
        if y == h - 1:
            c = WALNUT[1]
        elif pine:
            c = outer[1] if x == mid else (outer[4] if (x + y) % 2 else outer[3])
        elif d == 1:
            c = outer[1] if (not on(x + 1, y) or not on(x, y + 1)) else outer[3]
        elif x == mid:
            c = inner[4]
        elif d == 2:
            c = outer[2] if x > mid else outer[3]
        else:
            c = inner[2] if x > mid else inner[3]
        _put(img, ox + x, oy + y, c)


def paint_leaves():
    img = canvas(64)
    for i, (mask, outer, inner) in enumerate(LEAF_CELLS):
        _leaf(img, (i % 6) * 10, (i // 6) * 10, LEAF_MASKS[mask], outer, inner)
    return img


def paint_twig():
    """Twisted grapevine: diagonal strands of dark bark with pale ridges."""
    img = canvas(32)
    for y in range(32):
        for x in range(32):
            _put(img, x, y, (VINE[0], VINE[2], VINE[3])[(x + y) % 3])
    speckle(img, (0, 0, 31, 31), [VINE[1], VINE[4]], 0.12, seed=24)
    return img


def paint_berry():
    img = canvas(32)
    for y in range(0, 32, 2):
        for x in range(0, 32, 2):
            _put(img, x, y, "#f6765e")
            _put(img, x + 1, y, "#cc2429")
            _put(img, x, y + 1, "#a8151f")
            _put(img, x + 1, y + 1, "#6a0c18")
    return img


def paint_acorn():
    """Rows 0-15: glossy nut domes (2 x 2 px each); rows 16-31: scaly cap."""
    img = canvas(32)
    nut = ramp("#9a6230", 5, 0.62)
    cap = ramp("#6b4a2a", 4, 0.6)
    for y in range(0, 16, 2):
        for x in range(0, 32, 2):
            _put(img, x, y, nut[4])
            _put(img, x + 1, y, nut[2])
            _put(img, x, y + 1, nut[2])
            _put(img, x + 1, y + 1, nut[1])
    for y in range(16, 32):
        for x in range(32):
            _put(img, x, y, cap[2] if (x + y) % 2 else cap[1])
    return img


def paint_plaid(check: int = 4):
    """Red and black buffalo check with a twill where one black band crosses the red."""
    img = canvas(32)
    for y in range(32):
        for x in range(32):
            bx, by = (x // check) % 2, (y // check) % 2
            if bx and by:
                c = PLAID_BLACK[1]
            elif bx or by:
                c = PLAID_BLACK[2] if (x + y) % 2 else PLAID_RED[1]
            else:
                c = PLAID_RED[2]
            img.putpixel((x, y), rgba(c))
    speckle(img, (0, 0, 31, 31), [PLAID_RED[3], PLAID_BLACK[3]], 0.05, seed=26)
    return img


def paint_bowloop():
    """A ribbon loop seen from the front: plaid band round a shadowed opening (6 x 4 px)."""
    img = paint_plaid()
    fill(img, (1, 1, 4, 2), "#4a0c12")
    _put(img, 1, 1, "#2e070b")
    _put(img, 4, 2, "#6e1a1e")
    return img


def paint_ribbon():
    img = paint_plaid()
    for x in range(1, 32, 3):     # a V-notch at the free end of each 3 px ribbon tail
        _put(img, x, 0, (0, 0, 0, 0))
    return img


def paint_lining():
    """Quilted plaid wool: sunken diagonal seams, puffed diamonds, brass tufts (17 x 17 used)."""
    img = paint_plaid(3)
    for y in range(17):
        for x in range(17):
            c = img.getpixel((x, y))
            u, v = (x + y + 4) % 8, (x - y + 4) % 8
            if u == 0 or v == 0:
                img.putpixel((x, y), rgba(shade(c, -0.38)))
            else:
                puff = min(u, 8 - u) + min(v, 8 - v)      # 2 near a seam .. 8 at the centre
                img.putpixel((x, y), rgba(shade(c, (puff - 4) * 0.04)))
    for y in range(17):
        for x in range(17):
            if (x + y + 4) % 8 == 0 and (x - y + 4) % 8 == 0:
                _put(img, x, y, BRASS[5])
    for i in range(17):           # the cushion rounds over at its edges
        for x, y in ((i, 0), (0, i)):
            _blend(img, x, y, "#fff4e0", 0.15)
        for x, y in ((i, 16), (16, i)):
            _blend(img, x, y, "#140a0c", 0.45)
    return img


def paint_strap():
    """Leather strap along u: every 4 rows is one 2-unit-wide strap."""
    img = canvas(32, fill=LEATHER[3])
    grain(img, (0, 0, 31, 31), [LEATHER[2], LEATHER[4]], axis="x", seed=27, min_len=2, max_len=6, density=0.3)
    for y in range(0, 32, 4):
        fill(img, (0, y, 31, y), LEATHER[1])
        fill(img, (0, y + 3, 31, y + 3), LEATHER[0])
        for x in range(0, 32, 2):
            _put(img, x, y + 1, THREAD)
        fill(img, (0, y + 2, 31, y + 2), LEATHER[4])
    return img


def paint_piping():
    img = canvas(32, fill=LEATHER[2])
    grain(img, (0, 0, 31, 31), [LEATHER[1], LEATHER[3]], axis="x", seed=28, density=0.35)
    return img


def paint_grip():
    """Leather wrapped diagonally round the grip, 3 px per turn with a dark overlap line."""
    img = canvas(32)
    for y in range(32):
        for x in range(32):
            k = (x + y) % 3
            _put(img, x, y, (LEATHER[1], LEATHER[4], LEATHER[3])[k])
    return img


def paint_metal(pal):
    """Polished metal with brightness bands down v, so parts pick a band with offset=(0, row / 2)."""
    img = canvas(32)
    bands = (pal[6], pal[6], pal[5], pal[5], pal[4], pal[4], pal[3], pal[3],
             pal[3], pal[2], pal[2], pal[2], pal[1], pal[1], pal[0], pal[0])
    for y in range(32):
        fill(img, (0, y, 31, y), bands[y % 16])
    for x in range(1, 32, 4):
        for y in range(32):
            _blend(img, x, y, "#fff6d0", 0.35)
    return img


def paint_buckle():
    img = canvas(32, fill=BRASS[3])
    for oy in range(0, 30, 5):
        for ox in range(0, 30, 3):
            fill(img, (ox, oy, ox + 2, oy + 4), BRASS[4])
            _put(img, ox, oy, BRASS[6])
            _put(img, ox + 1, oy + 1, LEATHER[1])
            _put(img, ox + 1, oy + 2, IRON[3])
            _put(img, ox + 1, oy + 3, LEATHER[1])
            fill(img, (ox + 2, oy, ox + 2, oy + 4), BRASS[2])
    return img


def textures() -> None:
    save(paint_planks(True), "board_front")
    save(paint_planks(False), "board_back")
    save(paint_rim(), "rim")
    save(paint_iron(), "iron")
    save(paint_rivets(), "rivet")
    save(paint_fieldstone(), "stone")
    save(paint_ashlar(), "ashlar")
    save(paint_soot(), "soot")
    save(paint_firebox(), "firebox")
    save(paint_fire(), "fire")
    save(paint_log(), "log")
    save(paint_log_end(), "log_end")
    save(paint_walnut("x"), "walnut_h")
    save(paint_walnut("y"), "walnut_v")
    save(paint_mantel(), "mantel")
    save(paint_batten(), "batten")
    save(paint_leaves(), "leaves")
    save(paint_twig(), "twig")
    save(paint_berry(), "berry")
    save(paint_acorn(), "acorn")
    save(paint_plaid(), "plaid")
    save(paint_ribbon(), "ribbon")
    save(paint_bowloop(), "bowloop")
    save(paint_slate(), "slate")
    save(paint_lining(), "lining")
    save(paint_strap(), "strap")
    save(paint_piping(), "piping")
    save(paint_grip(), "grip")
    save(paint_metal(BRASS), "brass")
    save(paint_metal(COPPER), "copper")
    save(paint_buckle(), "buckle")


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def front_uv(x0, y0, x1, y1):
    """UV on the 64 px board sheet for a north (front) face; u grows toward -x."""
    return [(BX1 - x1) / 2, (TOP - y1) / 2, (BX1 - x0) / 2, (TOP - y0) / 2]


def back_uv(x0, y0, x1, y1):
    return [(x0 - BX0) / 2, (TOP - y1) / 2, (x1 - BX0) / 2, (TOP - y0) / 2]


def panel(x0, y0, x1, y1) -> dict:
    return box((x0, y0, FZ), (x1, y1, BZ), "walnut_v",
               faces={"north": ("board_front", front_uv(x0, y0, x1, y1)),
                      "south": ("board_back", back_uv(x0, y0, x1, y1))},
               skip=("east", "west", "up", "down"))


def board() -> list[dict]:
    """The plank board: one panel for the straight top, then slabs stepping in toward the
    point (their stair steps hide under the rim)."""
    parts = [panel(BX0 + 0.1, SHOULDER, BX1 - 0.1, TOP - 0.1)]
    y1 = SHOULDER
    while True:
        hw1 = half_width(y1)
        y0 = y1
        while y0 - 0.05 > TIP and hw1 - half_width(y0 - 0.05) <= 1.1 and y1 - y0 < 4.0:
            y0 -= 0.05
        hw0 = half_width(y0)
        if hw0 < 2.6:
            y0 = TIP + 0.8
            hw0 = half_width(y0)
            parts.append(panel(CX - hw0 + 0.1, y0, CX + hw0 - 0.1, y1))
            break
        parts.append(panel(CX - hw0 + 0.1, y0, CX + hw0 - 0.1, y1))
        y1 = y0
    return parts


RIM_FACES = {"north": "rim", "south": "rim"}


def rim() -> list[dict]:
    parts = []
    for x0, x1 in ((BX0, CX), (CX, BX1)):
        parts.append(box((x0, TOP - RIM_W, RIM_Z0), (x1, TOP, RIM_Z1), "iron",
                         faces={"north": ("rim", [0, 0, RIM_W, x1 - x0], 90),
                                "south": ("rim", [0, 0, RIM_W, x1 - x0], 90)}))
    side = [box((BX0, SHOULDER, RIM_Z0), (BX0 + RIM_W, TOP - RIM_W, RIM_Z1), "iron", faces=RIM_FACES)]
    th_tip = math.degrees(math.atan2(TIP - SHOULDER, CX - ARC_CX)) % 360
    lower = arc((ARC_CX, SHOULDER, (RIM_Z0 + RIM_Z1) / 2), R_SIDE - RIM_W / 2, 180, th_tip - 1.5, 6,
                RIM_W, RIM_Z1 - RIM_Z0, "iron", faces=RIM_FACES)
    for i, e in enumerate(lower):     # alternate depths so overlapping joints never z-fight
        if i % 2:
            e["from"][2] -= 0.03
            e["to"][2] += 0.03
    side += lower
    parts += side + mirror(side, "x", CX)
    # iron tip cap over the joint at the point
    cap = box((CX - 1.25, TIP + 0.3, RIM_Z0 - 0.25), (CX + 1.25, TIP + 2.8, RIM_Z1 + 0.25), "iron",
              faces={"north": ("rim", [0, 0, 2.5, 2.5]), "south": ("rim", [0, 0, 2.5, 2.5])})
    parts.append(turn(cap, 45, "z", (CX, TIP + 1.55, 8)))
    return parts


def rivet(x, y, z_front=RIM_Z0, size=0.9, depth=0.35) -> dict:
    h = size / 2
    return box((x - h, y - h, z_front - depth), (x + h, y + h, z_front), "rivet", skip=("south",))


def rivets() -> list[dict]:
    pts = [(CX - 9.8, TOP - 1.0), (CX - 4.9, TOP - 1.0), (CX, TOP - 1.0), (CX + 4.9, TOP - 1.0),
           (CX + 9.8, TOP - 1.0)]
    for y in (14.8, 10.2):
        pts += [(BX0 + 1.0, y), (BX1 - 1.0, y)]
    r = R_SIDE - RIM_W / 2
    for th in (197, 219, 239):
        t = math.radians(th)
        x, y = ARC_CX + r * math.cos(t), SHOULDER + r * math.sin(t)
        pts += [(x, y), (2 * CX - x, y)]
    parts = [rivet(x, y) for x, y in pts]
    parts.append(rivet(CX, TIP + 1.55, RIM_Z0 - 0.25, 1.0, 0.4))
    for x, y in pts:                  # the clinched ends show on the back of the rim
        parts.append(box((x - 0.35, y - 0.35, RIM_Z1), (x + 0.35, y + 0.35, RIM_Z1 + 0.2), "iron",
                         skip=("north",)))
    return parts


def fireplace() -> list[dict]:
    """Compact enough to sit inside the wreath, so the ring reads all the way round."""
    y = FP_Y
    parts = [
        box((3.9, y - 4.9, 3.6), (12.1, y - 4.0, FZ), "slate"),                           # hearthstone
        box((4.25, y - 4.0, 4.3), (5.75, y, FZ), "ashlar", offset=(0, 0), faces={"east": "soot"}),
        box((10.25, y - 4.0, 4.3), (11.75, y, FZ), "ashlar", offset=(3, 4), faces={"west": "soot"}),
        box((4.25, y, 4.6), (5.75, y + 3.75, FZ), "stone", offset=(9, 5)),
        box((10.25, y, 4.6), (11.75, y + 3.75, FZ), "stone", offset=(3, 7)),
        box((5.75, y + 2.25, 4.6), (10.25, y + 3.75, FZ), "stone", offset=(5, 11)),
        box((5.75, y - 4.0, 6.2), (10.25, y + 2.25, FZ), "firebox", glow=8, skip=("south",)),
    ]
    # dressed-stone arch with a proud keystone
    vous = arc((CX, y, 5.65), 3.0, 0, 180, 5, 1.5, 2.7, "ashlar", faces={"west": "soot"})
    parts += vous[:2] + vous[3:]
    parts.append(box((7.25, y + 1.95, 3.95), (8.75, y + 4.05, FZ), "ashlar", offset=(0, 14)))
    # walnut mantel beam and shelf (the shelf's front edge catches the light)
    parts.append(box((3.8, y + 3.75, 3.9), (12.2, y + 4.55, FZ), "mantel", offset=(0, 1.5)))
    parts.append(box((3.4, y + 4.55, 3.5), (12.6, y + 4.95, FZ), "mantel"))
    parts += fire(y - 4.0)
    return parts


def fire(floor: float) -> list[dict]:
    parts = []
    # glowing ember bed on the hearth floor
    parts.append(box((5.8, floor, 4.4), (10.2, floor + 0.2, 6.15), "fire", glow=12,
                     faces={"up": ("fire", list(EMBER_UV)), "north": ("fire", list(EMBER_UV))},
                     skip=("south", "down", "east", "west")))
    # crossed logs
    log = {"faces": {"up": "log_end", "down": "log_end"}, "glow": 6}
    parts.append(bar((5.85, floor + 0.45, 5.75), (10.15, floor + 0.45, 5.65), 0.85, 0.85, "log", **log))
    parts.append(bar((6.2, floor + 0.42, 4.8), (9.8, floor + 0.9, 5.85), 0.8, 0.8, "log", **log))
    parts.append(bar((9.8, floor + 0.45, 4.7), (6.4, floor + 1.3, 5.6), 0.75, 0.75, "log", **log))
    # layered flames: deep red at the back, orange, then a white-hot core in front
    for name, z, lift in (("back", 6.0, 0.3), ("mid", 5.45, 0.4), ("core", 4.95, 0.5)):
        x, y, w, h, _, _ = FLAMES[name]
        wu, hu = w / 2, h / 2
        uv = [x / 2, y / 2, (x + w) / 2, (y + h) / 2]
        parts.append(box((CX - wu / 2, floor + lift, z), (CX + wu / 2, floor + lift + hu, z), "fire", glow=15,
                         shade=False, faces={"north": ("fire", uv)},
                         skip=("south", "east", "west", "up", "down")))
    # andirons with copper knobs
    post = [box((5.95, floor, 4.4), (6.4, floor + 1.25, 4.85), "iron"),
            box((5.85, floor + 1.25, 4.3), (6.5, floor + 1.9, 4.95), "copper", offset=(0, 3))]
    parts += post + mirror(post, "x", CX)
    return parts


def leaf(cell: int, stem, angle: float, tilt: float, z: float, size: float = LEAF) -> dict:
    """A cut-out leaf plane, stem at `stem`, tip rotated `angle` from +Y, lifted by `tilt`."""
    sx, sy = stem
    u, v = (cell % 6) * 2.5, (cell // 6) * 2.5
    e = box((sx - size / 2, sy, z), (sx + size / 2, sy + size, z), "leaves",
            faces={"north": ("leaves", [u, v, u + 2.25, v + 2.25]),
                   "south": ("leaves", [u + 2.25, v, u, v + 2.25])},
            skip=("east", "west", "up", "down"))
    return turn(e, x=tilt, z=angle, origin=(sx, sy, z))


def acorn(x, y, z, angle) -> list[dict]:
    parts = [box((x - 0.45, y - 0.55, z - 0.45), (x + 0.45, y + 0.55, z + 0.45), "acorn"),
             box((x - 0.58, y + 0.35, z - 0.58), (x + 0.58, y + 0.85, z + 0.58), "acorn", offset=(0, 8)),
             box((x - 0.12, y + 0.85, z - 0.12), (x + 0.12, y + 1.2, z + 0.12), "acorn", offset=(0, 10))]
    return turn(parts, angle, "z", (x, y, z))


BRANCH_STATIONS = (252, 230, 208, 186, 164, 142, 120)
# cells: 0 red maple, 1 amber maple, 2 crimson maple, 3 gold maple, 4 gold aspen, 5 amber aspen,
# 6 russet oak, 7 red oak, 8/9 pine, 10 red aspen, 11 russet maple
CENTRE_CELLS = ((0, 1, 3, 2, 1, 0, 3), (1, 3, 0, 1, 2, 0, 1))
SIDE_CELLS = ((4, 5, 10, 8, 4, 5, 7, 4, 9, 5, 4, 10, 5, 4),
              (5, 4, 8, 10, 5, 4, 10, 6, 4, 5, 9, 4, 5, 10))


def branch(side: int, seed: int) -> list[dict]:
    """Half of the wreath climbing the vine from the bow to the top (side -1 = left, +1 =
    right). Each station is a fan: two rounder leaves splayed behind a showy maple."""
    cxw, cyw = WREATH_C
    rng = random.Random(seed)
    which = 0 if side < 0 else 1
    parts = []

    def put(cell, x, y, ang, tilt, z, size):
        if side > 0:
            x, ang = 2 * CX - x, -ang
        parts.append(leaf(cell, (x, y), ang, tilt, z, size))

    for i, th in enumerate(BRANCH_STATIONS):
        th += rng.uniform(-2.0, 2.0)
        t = math.radians(th)
        for j, (dr, spread) in enumerate(((0.25, 1), (-0.25, -1))):
            r = WREATH_R + dr
            put(SIDE_CELLS[which][2 * i + j], CX + r * math.cos(t), cyw + r * math.sin(t),
                th - 180 + spread * rng.uniform(24, 30), -rng.uniform(6, 10), 5.85 - 0.1 * j, 3.3)
        t2 = math.radians(th - 3)
        put(CENTRE_CELLS[which][i], CX + WREATH_R * math.cos(t2), cyw + WREATH_R * math.sin(t2),
            th - 180 + rng.uniform(-6, 6), -rng.uniform(13, 18), 5.55, 3.8)
    # berry clusters tucked between the fans, on the outside of the vine
    for th in (241, 197, 153):
        t = math.radians(th)
        bx, by = cxw + (WREATH_R + 0.35) * math.cos(t), cyw + (WREATH_R + 0.35) * math.sin(t)
        for dx, dy in ((0, 0), (-0.55, 0.6), (0.1, -0.75)):
            x = bx + dx if side < 0 else 2 * CX - (bx + dx)
            parts.append(box((x - 0.38, by + dy - 0.38, 4.95), (x + 0.38, by + dy + 0.38, 5.7), "berry",
                             skip=("south",)))
    return parts


def wreath() -> list[dict]:
    cxw, cyw = WREATH_C
    parts = arc((cxw, cyw, 6.35), WREATH_R, 0, 360, 18, 1.4, 1.1, "twig")
    parts += branch(-1, 8) + branch(1, 9)
    top = cyw + WREATH_R
    parts += acorn(CX - 0.75, top - 0.3, 4.6, 30) + acorn(CX + 0.75, top - 0.3, 4.6, -30)
    return parts


def bow() -> list[dict]:
    """Buffalo-plaid bow tying the bottom of the wreath."""
    by = WREATH_C[1] - WREATH_R
    parts = [box((CX - 0.65, by - 0.65, 4.7), (CX + 0.65, by + 0.65, 5.7), "plaid")]
    loop = box((CX - 3.3, by - 0.7, 4.95), (CX - 0.4, by + 1.1, 5.55), "plaid",
               faces={"north": ("bowloop", [0, 0, 2.9, 1.8])})
    turn(loop, -18, "z", (CX - 0.5, by, 5.2))
    tail = bar((CX - 0.3, by - 0.2, 5.25), (CX - 2.1, by - 4.2, 5.25), 1.5, 0.0, "ribbon",
               skip=("east", "west", "up", "down"))
    parts += [loop, tail] + mirror([loop, tail], "x", CX)
    return parts


def bell(x, y, z) -> list[dict]:
    """A little brass bell hanging from (x, y, z), its crown loop: domed shoulder, a waist
    that widens and a flared lip, with an iron clapper peeping out."""
    parts = [box((x - 0.2, y - 0.4, z - 0.3), (x + 0.2, y + 0.1, z + 0.3), "brass", offset=(0, 1))]
    parts += prism((x, y - 0.62, z), 0.55, 0.45, "brass", sides=8, offset=(0, 0))
    parts += prism((x, y - 1.15, z), 0.76, 0.62, "brass", sides=8, offset=(0, 1))
    parts += prism((x, y - 1.77, z), 0.95, 0.64, "brass", sides=8, offset=(0, 2.5))
    parts += prism((x, y - 2.22, z), 1.2, 0.28, "brass", sides=8, offset=(0, 4))
    parts.append(box((x - 0.3, y - 2.85, z - 0.3), (x + 0.3, y - 2.3, z + 0.3), "iron"))
    return parts


def back() -> list[dict]:
    parts = []
    # cabin-door battens and a diagonal brace
    parts.append(box((BX0 + RIM_W, 15.4, BZ), (BX1 - RIM_W, 17.6, BZ + 0.9), "batten"))
    hw = half_width(1.2) - RIM_W - 0.3
    parts.append(box((CX - hw, 1.2, BZ), (CX + hw, 3.4, BZ + 0.9), "walnut_h"))
    parts.append(bar((CX - 4.8, 3.0, BZ + 0.4), (CX + 6.4, 15.8, BZ + 0.4), 1.8, 0.8, "walnut_v"))
    # quilted plaid wool pad
    parts.append(box((3.2, 4.8, BZ), (12.8, 14.4, BZ + 1.25), "piping"))
    parts.append(box((3.75, 5.35, BZ + 1.25), (12.25, 13.85, BZ + 1.6), "piping",
                     faces={"south": ("lining", [0, 0, 8.5, 8.5])}))
    zt = BZ + 1.6
    # forearm strap over the pad, wrapped round its edges, with a brass buckle
    parts.append(box((2.95, 10.8, zt), (13.05, 12.8, zt + 0.35), "strap"))
    for x0 in (2.95, 12.7):
        parts.append(box((x0, 10.8, BZ), (x0 + 0.35, 12.8, zt + 0.35), "strap"))
    parts.append(box((4.9, 10.55, zt + 0.35), (6.4, 13.05, zt + 0.7), "buckle"))
    # hand grip on iron stand-offs, capped in copper
    for x0 in (4.3, 10.7):
        parts.append(box((x0, 7.0, zt - 0.2), (x0 + 1.0, 8.0, zt + 1.0), "iron"))
    parts.append(box((4.1, 6.7, zt + 0.9), (11.9, 8.3, zt + 1.9), "grip"))
    for x0 in (3.75, 11.9):
        parts.append(box((x0, 6.55, zt + 0.75), (x0 + 0.35, 8.45, zt + 2.05), "copper", offset=(0, 2)))
    # a brass bell on a leather thong from the top batten, beside the pad
    bx = 14.6
    parts.append(box((bx - 0.35, 15.0, BZ + 0.9), (bx + 0.35, 15.7, BZ + 1.3), "brass", offset=(0, 2)))
    parts.append(bar((bx, 15.3, BZ + 1.15), (bx, 14.4, BZ + 2.05), 0.3, 0.3, "strap"))
    parts += bell(bx, 14.5, BZ + 2.1)
    return parts


def build() -> list[dict]:
    return board() + rim() + rivets() + fireplace() + wreath() + bow() + back()


def models() -> dict:
    parts = build()
    main = display("shield", parts, grip=GRIP, gui_rotation=GUI_ROTATION, gui_span=15.5)
    blocking = display_for_state("shield", "blocking", parts, grip=GRIP)
    blocking["gui"] = main["gui"]
    # keep the crosshair clear while blocking in first person
    blocking["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, 1)}, (8, 8, 8), (0.5, -0.6, -0.82),
                                              0.68, pose=None)
    return {"main": model(parts, main), "blocking": model(parts, blocking)}
