"""Shared parts for the placeable treasure decorations (issue #1286): gold coins, coin
stacks, heaped coin mounds and four cut gemstones, plus their hand-painted textures.

Every treasure module (small_gold_pile, large_gold_pile, gemstone_pile,
mixed_treasure_pile, overflowing_treasure_chest) builds in the furniture frame: block
space, y = 0 is the floor, centred on x = z = 8, the front facing +Z.

Coins are real 3D octagonal discs: four crossed slabs give the reeded edge and a round,
transparent-cornered face plane sits just above them with the struck crown emblem. Mounds
are stacked tiers of a square and a 45-degree square (the second a coin's thickness lower,
so their tops never z-fight and read as layered coins) wrapped in a tiling painting of
overlapping coins that twinkles. The four stones each have their own cut and palette so
they stay apart at a glance: a round brilliant diamond (pale cyan), a rectangular step-cut
emerald, an octahedral ruby and a domed star sapphire.
"""
from __future__ import annotations

import math
import random

from art.kit import SIDES, animate, box, canvas, mix, move, rgba, save, save_animation, shine, sparkle, turn, wave

KIND = "furniture"
COUNTERPART = "item/gold_nugget"

# --------------------------------------------------------------------------------------
# Palettes (darkest -> lightest), hue-shifted: shadows lean red-brown, lights lean lemon
# --------------------------------------------------------------------------------------
GOLD = ["#2e1206", "#5a2a0c", "#8a4a12", "#b8701a", "#dc9a24", "#f2bf34", "#ffdc5c", "#fff09c", "#fffbe0"]
DIAMOND = ["#0b2440", "#14507e", "#1f8cbc", "#45c4e4", "#8ee8f6", "#d2faff", "#ffffff"]
EMERALD = ["#02200f", "#064424", "#0b6e36", "#14984a", "#2fc866", "#86eea0", "#dcffe2"]
RUBY = ["#26020c", "#52061a", "#86102a", "#bc1a3a", "#e83a56", "#ff8494", "#ffd6dc"]
SAPPHIRE = ["#05072e", "#0b1764", "#152ea2", "#2450d8", "#4c80fa", "#9cbcff", "#e6eeff"]
PEARL = ["#5e4a52", "#8a7479", "#b9a7a8", "#e2d4cf", "#f7eee6", "#ffffff"]
WOOD = ["#1e0e08", "#341a0e", "#4e2a16", "#6a3c1e", "#875128", "#a46a36", "#c08a4e"]
IRON = ["#121018", "#22202c", "#363442", "#4e4c5c", "#6c6a7c", "#9290a2"]
VELVET = ["#12041e", "#240a38", "#3a1258", "#561c7c", "#7430a0", "#9450c0", "#b67ede"]
GEMS = {"diamond": DIAMOND, "emerald": EMERALD, "ruby": RUBY, "sapphire": SAPPHIRE}
GEM_GLOW = 6          # gems catch a little light in the dark; gold never glows

CX, CZ = 8.0, 8.0
TAN22 = math.tan(math.radians(22.5))
COIN_R, COIN_T = 1.35, 0.45


def put(img, x: int, y: int, colour) -> None:
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), rgba(colour))


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def coin_face(t: float | None = None):
    """The struck face of one coin, 16 px, round with transparent corners: a raised rim lit
    from the upper left, a recessed field and a little crown with a gem dot."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            d = math.hypot(dx, dy)
            if d > 8.05:
                continue
            lit = -(dx + dy) / ((d or 1) * math.sqrt(2))       # 1 = upper left
            if d > 6.6:                                          # rim
                c = GOLD[5 + round(lit * 1.4)] if lit > -0.2 else GOLD[3 + round((lit + 1) * 1.2)]
            elif d > 5.6:                                        # inner bevel, lit the other way
                c = GOLD[3] if lit > 0 else GOLD[6]
            else:                                                # field
                c = GOLD[5] if (x + y) % 5 else GOLD[4]
            put(img, x, y, c)
    crown = ["..o.o.o..",
             ".o6o6o6o.",
             ".o66466o.",
             "..o666o..",
             "..77777..",
             "..44444.."]
    pal = {"o": GOLD[2], "6": GOLD[7], "4": GOLD[3], "7": GOLD[6]}
    for j, row in enumerate(crown):
        for i, ch in enumerate(row):
            if ch != ".":
                put(img, 4 + i, 5 + j, pal[ch])
    put(img, 8, 6, RUBY[4])
    if t is not None:
        img = shine(img, t, colour=GOLD[8], width=3.0, strength=0.75, angle=40.0, pause=0.4)
    return img


def coin_edge():
    """Reeded coin edge: fine vertical ridges."""
    img = canvas(16)
    for x in range(16):
        c = (GOLD[6], GOLD[7], GOLD[5], GOLD[4])[x % 4]
        for y in range(16):
            put(img, x, y, c)
    return img


def coin_stack_side():
    """32 px: a stack of coins seen side on, one coin per texel row (0.5 units), each with a
    lit top lip, reeded body and a dark seam."""
    img = canvas(32)
    rng = random.Random(7)
    for y in range(32):
        shift = rng.randrange(4)
        for x in range(32):
            ridge = (x + shift) % 4
            c = (GOLD[6], GOLD[5], GOLD[4], GOLD[3])[ridge]
            if y % 3 == 2:
                c = GOLD[2] if ridge else GOLD[3]
            put(img, x, y, c)
    return img


def _heap_coin(img, cx: float, cy: float, r: float, tone: int, squash: float = 1.2) -> None:
    """One coin in the heap painting, wrapped round the 32 px tile so it tiles: a dark
    seam on its shaded lower right, a bright lit rim on the upper left, a struck centre."""
    for y in range(-7, 8):
        for x in range(-7, 8):
            px, py = math.floor(cx) + x, math.floor(cy) + y
            dx, dy = px + 0.5 - cx, (py + 0.5 - cy) * squash  # coins seen a little (or fully) edge-on
            d = math.hypot(dx, dy)
            if d > r + 0.5:
                continue
            lit = -(dx + dy) / ((d or 1) * math.sqrt(2))
            if d > r - 0.5:
                c = GOLD[2] if lit < 0 else GOLD[min(7, tone + 1)]
            elif d > r - 1.4:
                c = GOLD[min(7, tone + 1)] if lit > 0.3 else GOLD[max(3, tone - 1)]
            elif d < 1.2 and squash < 2:
                c = GOLD[max(3, tone - 1)]
            else:
                c = GOLD[tone]
            img.putpixel((px % 32, py % 32), rgba(c))


HEAP_COINS = []     # (x, y, radius, tone), back to front
_rng = random.Random(1286)
for _row in range(5):
    for _col in range(4):
        HEAP_COINS.append((_col * 8.0 + (_row % 2) * 4.0 + _rng.uniform(-1.0, 1.0),
                           _row * 6.4 + _rng.uniform(-0.8, 0.8),
                           _rng.uniform(3.6, 4.6), _rng.choice((4, 4, 4, 5, 5, 6))))
_rng.shuffle(HEAP_COINS)
HEAP_SPARKLES = [(5, 6, 0.0), (20, 3, 0.33), (13, 17, 0.66), (27, 21, 0.15), (6, 26, 0.5), (22, 12, 0.82)]
HEAP_FRAMES, HEAP_FRAMETIME = 12, 3


def heap_frame(t: float):
    """32 px tiling painting of overlapping coins seen from above, with deep gaps between
    them (so the real 3D coins lying on a mound stand out) and twinkling glints."""
    img = canvas(32, fill=GOLD[2])
    for x, y, r, tone in HEAP_COINS:
        _heap_coin(img, x, y, r, tone - 1)
    for x, y, phase in HEAP_SPARKLES:
        sparkle(img, x, y, max(0.0, wave(t, phase) * 1.6 - 0.6), colour=GOLD[8], reach=2)
    return img


HEAP_SIDE = []      # coins seen edge-on: rows of flat lenses, staggered like a stack
_rng = random.Random(2286)
for _row in range(11):
    for _col in range(4):
        HEAP_SIDE.append((_col * 8.0 + (_row % 2) * 4.0 + _rng.uniform(-1.5, 1.5), _row * 3.0 + _rng.uniform(-0.4, 0.4),
                          _rng.uniform(3.6, 4.6), _rng.choice((4, 5, 5, 6))))


def heap_side_frame(t: float):
    """32 px: the flank of a mound, coins lying in rough layers seen edge-on."""
    img = canvas(32, fill=GOLD[2])
    for x, y, r, tone in HEAP_SIDE:
        _heap_coin(img, x, y, r, tone, squash=2.4)
    for x, y, phase in HEAP_SPARKLES[::2]:
        sparkle(img, (x + 9) % 32, min(y, 22), max(0.0, wave(t, phase + 0.2) * 1.6 - 0.6), colour=GOLD[7], reach=1)
    return img


def _facet_gem(pal, t: float, kind: str):
    """16 px stone texture painted per cut: radial kite facets for a brilliant, nested
    steps for a step cut, triangles for the octahedron, a soft dome with a six-ray star
    for the cabochon. A glint sweeps across and a twinkle flares on the lit corner."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            if kind == "step":
                ring = int(max(abs(dx), abs(dy)) // 2)       # 0 centre .. 3 outer step
                lit = 1 if (dy < 0 and abs(dy) >= abs(dx)) or (dx < 0 and abs(dx) > abs(dy)) else -1
                tone = 3 + (1 if ring % 2 else 0) + (1 if lit > 0 else -1)
                if ring == 0:
                    tone = 3
            elif kind == "brilliant":
                ang = (math.degrees(math.atan2(dy, dx)) + 360) % 360
                sector = int(ang // 45)
                d = math.hypot(dx, dy)
                tone = (2, 3, 4, 5, 5, 4, 2, 1)[sector] + (1 if d < 3 else 0)
                if (int(ang) % 45) < 6:
                    tone += 1
            elif kind == "octa":
                tone = 4 if dx + dy < 0 else 2
                if abs(dx) < 1 or abs(dy) < 1:
                    tone += 1
                if dx - dy > 4:
                    tone -= 1
            else:  # dome
                d = math.hypot(dx + 1.5, dy + 1.5)
                tone = max(1, 5 - int(d / 2.4))
            put(img, x, y, pal[max(0, min(len(pal) - 2, tone))])
    if kind == "dome":
        for a in range(0, 180, 60):
            for s in range(-6, 7):
                k = 1 - abs(s) / 7
                ax, ay = 8 + s * math.cos(math.radians(a)), 7 + s * math.sin(math.radians(a))
                px = img.getpixel((int(ax), int(ay)))
                img.putpixel((int(ax), int(ay)), rgba(mix(px, pal[-1], 0.25 + 0.5 * k * (0.6 + 0.4 * wave(t)))))
    img = shine(img, t, colour=pal[-1], width=2.5, strength=0.65, angle=45.0, pause=0.5)
    sparkle(img, 4, 4, max(0.0, wave(t, 0.6) * 1.5 - 0.5), colour="#ffffff", reach=2)
    return img


GEM_CUT = {"diamond": "brilliant", "emerald": "step", "ruby": "octa", "sapphire": "dome"}
GEM_FRAMES, GEM_FRAMETIME = 10, 3


def pavilion(pal):
    """16 px lower half of a stone: long converging facets, darker, lit from within."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            lane = (x + (y // 4)) // 4
            tone = (3, 2, 4, 2)[lane % 4] if y > 1 else 4
            if y > 12:
                tone = max(1, tone - 1)
            put(img, x, y, pal[tone])
    for y in range(0, 16, 4):
        put(img, (y * 3) % 16, y, pal[5])
    return img


def pearl():
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 6)
            put(img, x, y, PEARL[max(0, 5 - int(d / 2.6))])
            if 11.5 < d < 13.5:
                put(img, x, y, "#d9aab4")         # the pink nacre rim
    put(img, 5, 5, "#ffffff")
    return img


def wood():
    """32 px dark oak planks running along u, with grain and plank seams."""
    img = canvas(32, fill=WOOD[3])
    rng = random.Random(11)
    for y in range(32):
        seam = y % 8 == 7
        for x in range(32):
            if seam:
                c = WOOD[1]
            elif y % 8 == 0:
                c = WOOD[5]
            else:
                c = WOOD[3] if rng.random() > 0.3 else WOOD[rng.choice((2, 4))]
            put(img, x, y, c)
    for y in range(32):
        if y % 8 in (0, 7):
            continue
        x = rng.randrange(32)
        while x < 32:
            length = rng.randint(3, 9)
            for k in range(length):
                put(img, (x + k) % 32, y, WOOD[2] if rng.random() < 0.6 else WOOD[4])
            x += length + rng.randint(4, 12)
    return img


def band():
    """16 px gilded strap with rivets."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            c = GOLD[5] if y in (1, 2) else GOLD[3] if y in (13, 14) else GOLD[4]
            if y in (0, 15):
                c = GOLD[2]
            put(img, x, y, c)
    for x in (2, 10):
        for (ox, oy, c) in ((0, 0, GOLD[7]), (1, 0, GOLD[6]), (0, 1, GOLD[6]), (1, 1, GOLD[3])):
            for y0 in (6,):
                put(img, x + ox, y0 + oy, c)
    return img


def velvet():
    """32 px crushed velvet: soft diagonal sheen bands over deep purple."""
    img = canvas(32)
    rng = random.Random(3)
    for y in range(32):
        for x in range(32):
            v = 0.5 + 0.5 * math.sin((x + y * 0.6) / 4.2) * math.cos((x * 0.4 - y) / 6.0)
            tone = 2 + round(v * 2.4) + (1 if rng.random() < 0.08 else 0)
            put(img, x, y, VELVET[min(6, tone)])
    return img


def fringe():
    """16 px gold fringe: hanging threads."""
    img = canvas(16)
    for x in range(16):
        for y in range(16):
            if y < 4:
                c = GOLD[5] if y < 2 else GOLD[3]
            else:
                c = (GOLD[4], GOLD[6], GOLD[3], GOLD[5])[x % 4] if (x + y // 6) % 3 else GOLD[2]
            put(img, x, y, c)
    return img


def iron():
    img = canvas(16, fill=IRON[2])
    for y in range(16):
        for x in range(16):
            put(img, x, y, IRON[3] if (x + y) % 7 else IRON[1])
            if x == 0 or y == 0:
                put(img, x, y, IRON[4])
    return img


def keyhole():
    """16 px gilded lock plate with a bevelled edge, corner rivets and a dark keyhole."""
    img = canvas(16, fill=GOLD[5])
    for y in range(16):
        for x in range(16):
            if x in (0, 1) or y in (0, 1):
                put(img, x, y, GOLD[7])
            elif x in (14, 15) or y in (14, 15):
                put(img, x, y, GOLD[3])
            elif (x + y) % 6 == 0:
                put(img, x, y, GOLD[4])
    for rx, ry in ((3, 3), (12, 3), (3, 12), (12, 12)):
        put(img, rx, ry, GOLD[8])
        put(img, rx + 1, ry + 1, GOLD[2])
    for (x, y) in ((7, 4), (8, 4), (6, 5), (7, 5), (8, 5), (9, 5), (6, 6), (7, 6), (8, 6), (9, 6), (7, 7), (8, 7),
                   (7, 8), (8, 8), (6, 9), (7, 9), (8, 9), (9, 9), (6, 10), (7, 10), (8, 10), (9, 10)):
        put(img, x, y, IRON[0])
    for (x, y) in ((6, 4), (5, 5), (5, 6), (5, 9), (5, 10), (6, 11), (7, 11), (8, 11), (9, 11)):
        put(img, x, y, GOLD[3])
    return img


def polished():
    """16 px polished gold for goblets: vertical light bands like a turned metal body."""
    img = canvas(16)
    tones = (3, 4, 6, 7, 6, 5, 4, 4, 3, 3, 4, 5, 4, 3, 2, 3)
    for x in range(16):
        for y in range(16):
            put(img, x, y, GOLD[tones[x]] if y % 5 else GOLD[max(2, tones[x] - 1)])
    return img


def plain(colour):
    img = canvas(16, fill=colour)
    return img


PAINTERS = {
    "coin": lambda: save_animation(animate(coin_face, 8), "coin", frametime=3),
    "coin_edge": lambda: save(coin_edge(), "coin_edge"),
    "coin_rim": lambda: save(plain(GOLD[4]), "coin_rim"),
    "coin_stack": lambda: save(coin_stack_side(), "coin_stack"),
    "heap": lambda: save_animation(animate(heap_frame, HEAP_FRAMES), "heap", frametime=HEAP_FRAMETIME),
    "heap_side": lambda: save_animation(animate(heap_side_frame, HEAP_FRAMES), "heap_side", frametime=HEAP_FRAMETIME),
    "pearl": lambda: save(pearl(), "pearl"),
    "wood": lambda: save(wood(), "wood"),
    "band": lambda: save(band(), "band"),
    "velvet": lambda: save(velvet(), "velvet"),
    "fringe": lambda: save(fringe(), "fringe"),
    "iron": lambda: save(iron(), "iron"),
    "keyhole": lambda: save(keyhole(), "keyhole"),
    "gold": lambda: save(plain(GOLD[5]), "gold"),
    "polished": lambda: save(polished(), "polished"),
}
for _name, _pal in GEMS.items():
    PAINTERS[_name] = (lambda n=_name, p=_pal: save_animation(
        animate(lambda t: _facet_gem(p, t, GEM_CUT[n]), GEM_FRAMES), n, frametime=GEM_FRAMETIME))
    PAINTERS[_name + "_pavilion"] = (lambda n=_name, p=_pal: save(pavilion(p), n + "_pavilion"))


def paint(*names: str) -> None:
    for name in names:
        PAINTERS[name]()


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def only(frm, to, faces: dict, **kw) -> dict:
    """A box that draws only the listed faces."""
    first = next(iter(faces.values()))
    tex = first if isinstance(first, str) else first[0]
    return box(frm, to, tex, faces=faces, skip=tuple(s for s in SIDES if s not in faces), **kw)


def place(parts, at, tilt: float = 0.0, toward: float = 0.0, spin: float = 0.0, roll: float = 0.0):
    """Pose a part built round `at`: spin about its own up axis, tip it `tilt` degrees
    (about X, so +tilt leans its top toward +Z) and `roll` (about Z), then turn the whole
    lean to face `toward` degrees round Y."""
    if spin:
        turn(parts, spin, "y", at)
    if tilt or roll:
        turn(parts, x=tilt, z=roll, origin=at)
    if toward:
        turn(parts, toward, "y", at)
    return parts


def coin(x: float, y: float, z: float, r: float = COIN_R, t: float = COIN_T, tilt: float = 0.0,
         toward: float = 0.0, spin: float | None = None, roll: float = 0.0, edge: bool = True) -> list[dict]:
    """One gold coin lying with its bottom centre at (x, y, z). edge=False drops the two
    diagonal edge slabs for coins half buried in a heap."""
    half = r * TAN22
    parts = []
    angles = (0, 45) if edge else (0,)
    for a in angles:
        s1 = box((x - r, y, z - half), (x + r, y + t, z + half), "coin_rim",
                 faces={"east": "coin_edge", "west": "coin_edge"}, skip=("north", "south"))
        s2 = box((x - half, y, z - r), (x + half, y + t, z + r), "coin_rim",
                 faces={"north": "coin_edge", "south": "coin_edge"}, skip=("east", "west"))
        if a:
            turn([s1, s2], a, "y", (x, y, z))
        parts += [s1, s2]
    c = r * 1.06
    parts.append(only((x - c, y + t + 0.02, z - c), (x + c, y + t + 0.02, z + c), {"up": ("coin", [0, 0, 16, 16])}))
    if edge:
        parts.append(only((x - c, y - 0.02, z - c), (x + c, y - 0.02, z + c), {"down": ("coin", [16, 0, 0, 16])}))
    if spin is None:
        spin = (x * 37 + z * 53) % 90
    return place(parts, (x, y, z), tilt, toward, spin, roll)


def octo(cx: float, y0: float, y1: float, cz: float, r: float, tex: str, side: str | None = None,
         top: bool = True, bottom: bool = False, lift: float = 0.02, offset=(0, 0), **kw) -> list[dict]:
    """An octagonal slab (apothem r) of four crossed boxes. Their tops step up by `lift`
    each, so the overlapping caps never z-fight. `side` textures the eight walls."""
    half = r * TAN22
    side = side or tex
    parts = []
    for i, (a, along_x) in enumerate(((0, True), (0, False), (45, True), (45, False))):
        hi = y1 + lift * i if top else y1
        lo = y0 - lift * i if bottom else y0
        if along_x:
            frm, to, walls, drop = (cx - r, lo, cz - half), (cx + r, hi, cz + half), ("east", "west"), ("north", "south")
        else:
            frm, to, walls, drop = (cx - half, lo, cz - r), (cx + half, hi, cz + r), ("north", "south"), ("east", "west")
        skip = drop + (() if top else ("up",)) + (() if bottom else ("down",))
        e = box(frm, to, tex, faces={w: side for w in walls}, skip=skip,
                offset=((offset[0] + i * 2.3) % 8, offset[1]), **kw)
        if a:
            turn(e, a, "y", (cx, 0, cz))
        parts.append(e)
    return parts


def stack(x: float, y: float, z: float, count: int, r: float = COIN_R, step: float = 0.5) -> list[dict]:
    """A column of `count` coins (0.5 units each), built in two slightly offset, slightly
    turned halves so it reads as a hand-stacked pile rather than a cylinder, with a
    struck coin on top."""
    lo = max(1, count // 2)
    parts = octo(x, y, y + lo * step, z, r, "coin_rim", side="coin_stack", lift=0.0)
    ox, oz = 0.2, -0.15
    if count > lo:
        upper = octo(x + ox, y + lo * step, y + count * step, z + oz, r, "coin_rim", side="coin_stack", lift=0.0,
                     offset=(5, 3))
        parts += turn(upper, 14, "y", (x + ox, 0, z + oz))
    else:
        ox, oz = 0.0, 0.0
    top = y + count * step
    c = r * 1.06
    parts.append(turn(only((x + ox - c, top + 0.02, z + oz - c), (x + ox + c, top + 0.02, z + oz + c),
                           {"up": ("coin", [0, 0, 16, 16])}), 30, "y", (x + ox, top, z + oz)))
    return parts


def tier(r: float, y0: float, y1: float, yaw: float = 0.0, cx: float = CX, cz: float = CZ,
         drop: float = 0.05) -> list[dict]:
    """One layer of a coin mound: a round 16-sided slab of eight crossed boxes (like
    kit.prism), each top `drop` lower than the last so the caps never z-fight and read as
    coins lying in layers, the flanks painted as coins seen edge-on."""
    half = r * math.tan(math.pi / 16)
    parts = []
    for i in range(8):
        along_x = i % 2 == 0
        off = ((cx * 3 + y0 * 5 + i * 3.7) % 10, (cz * 7 + y1 * 3 + i * 5.3) % 10)
        top = y1 - drop * i
        if along_x:
            e = box((cx - r, y0, cz - half), (cx + r, top, cz + half), "heap_side", faces={"up": "heap"},
                    skip=("down", "north", "south"), offset=off)
        else:
            e = box((cx - half, y0, cz - r), (cx + half, top, cz + r), "heap_side", faces={"up": "heap"},
                    skip=("down", "east", "west"), offset=off)
        parts.append(turn(e, yaw + 22.5 * (i // 2), "y", (cx, 0, cz)))
    return parts


def mound(layers, cx: float = CX, cz: float = CZ, y0: float = 0.0, twist: float = 22.0) -> list[dict]:
    """A heap from [(half_size, top_y), ...], widest first; each layer is twisted a little
    further so the outline looks round."""
    parts = []
    base = y0
    for i, (r, top) in enumerate(layers):
        parts += tier(r, base, top, yaw=i * twist, cx=cx, cz=cz)
        base = top - 0.45
    return parts


def surface(layers, d: float, y0: float = 0.0) -> float:
    """Height of a mound's top at distance d from its centre (inscribed radius per layer)."""
    h = y0
    for r, top in layers:
        if d <= r * 0.98:
            h = top
    return h


# --- Gems -----------------------------------------------------------------------------

def turned(x: float, y: float, z: float, profile, tex: str, lower: int = 0, lower_tex: str | None = None) -> list[dict]:
    """A round solid of octo() slices from [(height, apothem), ...] (bottom to top); the
    first `lower` slices wear `lower_tex` on their walls (a gem's pavilion)."""
    parts = []
    for i, ((h0, r0), (h1, r1)) in enumerate(zip(profile, profile[1:])):
        r = (r0 + r1) / 2
        if r <= 0.05 or h1 - h0 <= 1e-4:
            continue
        side = lower_tex if (lower_tex and i < lower) else tex
        parts += octo(x, y + h0, y + h1, z, r, tex, side=side, bottom=i == 0, uv="full", glow=GEM_GLOW, lift=0.015)
    return parts


def brilliant(x: float, y: float, z: float, r: float, tex: str = "diamond", tilt: float = 0.0,
              toward: float = 0.0) -> list[dict]:
    """A round brilliant standing table-up with its culet at (x, y, z): a steep stepped
    pavilion, the girdle and a low crown with a flat table."""
    profile = [(0.0, r * 0.12), (r * 0.35, r * 0.42), (r * 0.68, r * 0.78), (r * 0.9, r), (r * 1.02, r),
               (r * 1.2, r * 0.76), (r * 1.34, r * 0.52)]
    return place(turned(x, y, z, profile, tex, lower=3, lower_tex=tex + "_pavilion"), (x, y, z), tilt, toward)


def step_cut(x: float, y: float, z: float, w: float, l: float, h: float, tex: str = "emerald",
             tilt: float = 0.0, toward: float = 0.0) -> list[dict]:
    """An emerald cut lying with its base centre at (x, y, z): a long girdle with clipped
    corners, a stepped crown and a smaller table, long along Z."""
    c = min(w, l) * 0.22
    kw = dict(uv="full", glow=GEM_GLOW)
    parts = [
        box((x - w / 2, y + h * 0.2, z - l / 2 + c), (x + w / 2, y + h * 0.62, z + l / 2 - c), tex, **kw),
        box((x - w / 2 + c, y + h * 0.2, z - l / 2), (x + w / 2 - c, y + h * 0.6, z + l / 2), tex, **kw),
        box((x - w / 2 + c * 1.2, y, z - l / 2 + c * 1.2), (x + w / 2 - c * 1.2, y + h * 0.2, z + l / 2 - c * 1.2),
            tex, **kw),
        box((x - w / 2 + c * 0.8, y + h * 0.62, z - l / 2 + c * 0.8),
            (x + w / 2 - c * 0.8, y + h * 0.84, z + l / 2 - c * 0.8), tex, **kw),
        box((x - w / 2 + c * 1.7, y + h * 0.84, z - l / 2 + c * 1.7),
            (x + w / 2 - c * 1.7, y + h, z + l / 2 - c * 1.7), tex, **kw),
    ]
    return place(parts, (x, y, z), tilt, toward)


def _on_point(x: float, y: float, z: float, s: float, tex: str, yaw: float = 0.0, sink: float = 0.0) -> dict:
    """A cube of edge s stood on one corner (its body diagonal vertical), bottom point at y."""
    a = s / 2
    e = box((x - a, y - a, z - a), (x + a, y + a, z + a), tex, uv="full", glow=GEM_GLOW)
    turn(e, x=45, origin=(x, y, z))
    turn(e, z=35.264, origin=(x, y, z))
    if yaw:
        turn(e, yaw, "y", (x, y, z))
    move(e, dy=a * math.sqrt(3) - sink)
    return e


def octahedron(x: float, y: float, z: float, s: float, tex: str = "ruby", tilt: float = 0.0,
               toward: float = 0.0) -> list[dict]:
    """A ruby: a cube stood on its point with a second, smaller one turned 60 degrees
    inside it, so its outline sparkles with extra facets. Bottom point at (x, y, z)."""
    big = _on_point(x, y, z, s, tex)
    small = _on_point(x, y, z, s * 0.86, tex, yaw=60, sink=-s * 0.06)
    return place([big, small], (x, y, z), tilt, toward)


def cabochon(x: float, y: float, z: float, r: float, tex: str = "sapphire", tilt: float = 0.0,
             toward: float = 0.0) -> list[dict]:
    """A domed star sapphire with its flat base at (x, y, z)."""
    profile = [(0.0, r), (r * 0.32, r * 0.95), (r * 0.58, r * 0.76), (r * 0.78, r * 0.45)]
    return place(turned(x, y, z, profile, tex), (x, y, z), tilt, toward)


def chip(x: float, y: float, z: float, s: float, tex: str, yaw: float = 0.0) -> list[dict]:
    """A small loose gem: one cube stood on a corner, a little sunk into what it lies on."""
    return [_on_point(x, y, z, s, tex, yaw=yaw, sink=s * 0.35)]


GEM_MAKERS = {"diamond": lambda x, y, z, s, **k: brilliant(x, y, z, s * 0.8, "diamond", **k),
              "emerald": lambda x, y, z, s, **k: step_cut(x, y, z, s * 1.2, s * 1.7, s * 0.8, "emerald", **k),
              "ruby": lambda x, y, z, s, **k: octahedron(x, y, z, s * 1.1, "ruby", **k),
              "sapphire": lambda x, y, z, s, **k: cabochon(x, y, z, s * 0.85, "sapphire", **k)}


def gem(kind: str, x: float, y: float, z: float, s: float, **kw) -> list[dict]:
    return GEM_MAKERS[kind](x, y, z, s, **kw)


def scatter_coins(rng, count: int, inner: float, outer: float, cx: float = CX, cz: float = CZ,
                  y: float = 0.0, standing: int = 0) -> list[dict]:
    """Loose coins round a heap: mostly flat, some leaning on each other, `standing` of
    them on edge (rolled away). Kept inside the block (2..14)."""
    parts = []
    placed: list[tuple[float, float]] = []
    tries = 0
    while len(placed) < count and tries < 400:
        tries += 1
        a = rng.uniform(0, 2 * math.pi)
        d = rng.uniform(inner, outer)
        x, z = cx + d * math.cos(a), cz + d * math.sin(a)
        if not (2.0 <= x <= 14.0 and 2.0 <= z <= 14.0):
            continue
        if any(math.hypot(x - px, z - pz) < COIN_R * 1.7 for px, pz in placed):
            continue
        placed.append((x, z))
        toward = math.degrees(math.atan2(x - cx, z - cz))
        if len(placed) <= standing:
            parts += coin(x, y + COIN_R * 0.93, z, tilt=72, toward=toward + 90, spin=0)
        else:
            parts += coin(x, y, z, tilt=rng.choice((0, 0, 8, 14, 22)), toward=toward)
    return parts


def slope_at(layers, d: float, y0: float = 0.0) -> tuple[float, float]:
    """(height, slope angle in degrees) of the smooth surface through the mound's terrace
    edges at distance d from its centre."""
    pts = [(0.0, layers[-1][1] + 0.15)] + [(r, top) for r, top in reversed(layers)] + [(layers[0][0] + 1.2, y0)]
    for (d0, h0), (d1, h1) in zip(pts, pts[1:]):
        if d0 <= d <= d1:
            k = (d - d0) / (d1 - d0) if d1 > d0 else 0.0
            return h0 + (h1 - h0) * k, math.degrees(math.atan2(h0 - h1, d1 - d0))
    return y0, 0.0


def mound_coins(rng, layers, count: int, cx: float = CX, cz: float = CZ, y0: float = 0.0,
                max_d: float | None = None) -> list[dict]:
    """Coins nestled into the mound, lying along its slope (half sunk into the terraces,
    like coins poured onto a heap)."""
    parts = []
    outer = max_d or layers[0][0] * 0.95
    placed: list[tuple[float, float]] = []
    tries = 0
    while len(placed) < count and tries < 600:
        tries += 1
        a = rng.uniform(0, 2 * math.pi)
        d = math.sqrt(rng.uniform(0.0, 1.0)) * outer
        x, z = cx + d * math.cos(a), cz + d * math.sin(a)
        if any(math.hypot(x - px, z - pz) < COIN_R * 1.55 for px, pz in placed):
            continue
        placed.append((x, z))
        h, slope = slope_at(layers, d, y0)
        toward = math.degrees(math.atan2(x - cx, z - cz))
        parts += coin(x, h - 0.05, z, tilt=min(45.0, slope * rng.uniform(0.75, 1.0)) if d > 0.8 else rng.uniform(0, 8),
                      toward=toward, edge=rng.random() < 0.5)
    return parts


def goblet(x: float, y: float, z: float, s: float = 1.0, tilt: float = 0.0, toward: float = 0.0) -> list[dict]:
    """A polished gold goblet heaped with coins, its bowl set with a ruby, a sapphire and
    an emerald: a round foot, a slim stem with a knop and a flared bowl."""
    def sl(h0, h1, r, top=True, side="polished", tex="polished"):
        return octo(x, y + h0 * s, y + h1 * s, z, r * s, tex, side=side, top=top, lift=0.015)
    parts = sl(0.0, 0.35, 1.7) + sl(0.35, 0.7, 1.1) + sl(0.7, 2.6, 0.42, top=False) + sl(1.5, 2.1, 0.75)
    parts += sl(2.6, 3.0, 0.95) + sl(3.0, 3.6, 1.45) + sl(3.6, 4.6, 1.85, top=False)
    parts += octo(x, y + 4.6 * s, y + 4.9 * s, z, 2.0 * s, "polished", top=False, lift=0.0)
    parts += octo(x, y + 4.0 * s, y + 4.75 * s, z, 1.7 * s, "heap", side="polished", lift=0.0)
    for i, kind in enumerate(("ruby", "sapphire", "emerald", "ruby")):
        a = math.radians(90 * i + 22.5)
        gx, gz = x + math.sin(a) * 1.82 * s, z + math.cos(a) * 1.82 * s
        parts.append(_on_point(gx, y + 3.55 * s, gz, 0.6 * s, kind, yaw=90 * i))
    for i in range(5):
        a = math.radians(70 * i + 10)
        parts += coin(x + math.sin(a) * 0.9 * s, y + 4.75 * s, z + math.cos(a) * 0.9 * s, r=0.95 * s, t=0.35,
                      tilt=18 + 6 * i, toward=70 * i, edge=False)
    return place(parts, (x, y, z), tilt, toward)


def necklace(points, size: float = 0.6, pendant: str | None = None) -> list[dict]:
    """A pearl strand through the given points (pearls spaced along the polyline), with an
    optional gem pendant hanging from its lowest pearl."""
    parts = []
    beads = []
    for (x0, y0, z0), (x1, y1, z1) in zip(points, points[1:]):
        length = math.dist((x0, y0, z0), (x1, y1, z1))
        n = max(1, round(length / (size * 1.25)))
        for k in range(n):
            t = k / n
            beads.append((x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t))
    beads.append(points[-1])
    for i, (bx, by, bz) in enumerate(beads):
        a = size / 2
        e = box((bx - a, by - a, bz - a), (bx + a, by + a, bz + a), "pearl", uv="full")
        parts.append(turn(e, x=35, y=45 + i * 17, origin=(bx, by, bz)))
    if pendant:
        lx, ly, lz = min(beads, key=lambda b: b[1])
        parts.append(_on_point(lx, ly - size * 2.4, lz, size * 1.5, pendant, yaw=45))
    return parts
