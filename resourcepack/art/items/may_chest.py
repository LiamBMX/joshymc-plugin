"""May Chest (issue #1482, design in #1407): the monthly crate chest for May, a blooming
garden chest in leaf green, pink, yellow and cream.

A body of cream painted boards laid across, framed by leaf green corner posts, with a
green garden trellis on each side wall that a flowering vine climbs, and a white daisy
in each of the two front panels. The lid is a garden-inspired planter: its sides carry a
leafy vine run, and its top is a raised flower bed of dark soil fenced in by a cream
picket fence, planted with leafy hedge mounds, white daisies, pink tulips and yellow
buttercups, with two tall sunflowers standing at the back corners, their heads tipped
toward the front. Small butterflies (one pink, one yellow) rest on the chest.

The emblem is a big pink garden flower (eight petals round a golden heart) on a leafy
wreath on the lid's front; below it a cream lock plate with a scalloped petal rim and a
glowing golden keyhole. Two leaf-shaped green hinges hold the lid at the back.

Animation (one loop): the butterflies' wings shimmer, the sunflower hearts and the
emblem's heart twinkle, and the keyhole glows warm gold.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block. Unlike the other crate chests: a fenced flower bed with
two sunflowers rising out of it instead of a crown, spire, bow, clover or umbrella.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM,  # noqa: F401
                                    keyhole_glow, metal, only, planks, put, rims, shell)
from art.kit import animate, arc, bar, box, canvas, copy, display, model, prism, save, save_animation, shine, \
    sparkle, turn, wave

ID = "may_chest"
NAME = "May Chest"

CREAM = ["#8a7652", "#ad9a72", "#cdbb92", "#e2d4ae", "#efe6c8", "#f6efd9", "#fbf7ea", "#fffdf6"]
LEAF = ["#163810", "#214f18", "#2d6a21", "#3c852c", "#51a039", "#6cba4c", "#90d06a", "#bfe69a"]
PINK = ["#5c1d3a", "#8a2d56", "#b84375", "#dc6795", "#ef8fb4", "#f8b8cf", "#fde0ea", "#fff4f8"]
YELLOW = ["#6a3b06", "#9a5d0a", "#ca880f", "#e8b01d", "#f7cf3b", "#ffe76d", "#fff5b6", "#fffde8"]
SOIL = ["#24160d", "#352214", "#4a311d", "#5f4127", "#755433"]
SEED = ["#24130a", "#3b220f", "#563216", "#72451e", "#8c5a28"]
GLOW = ["#2a1604", "#553008", "#8a5410", "#c8861c", "#f2b73a", "#ffe07a", "#fffbe0"]

BED = LID_TOP + 0.45               # top of the soil bed
SUN_HEAD = 21.9                    # height of the sunflower hearts


# ======================================================================================
# Textures
# ======================================================================================

def petal_flower(petal, centre, n: int = 8, size: int = 16, inner: float = 2.6, outer: float = 7.6,
                 seed: int = 0) -> object:
    """A flower seen from the front on a clear background: n round-tipped petals."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            dx, dy = x + 0.5 - size / 2, y + 0.5 - size / 2
            d = math.hypot(dx, dy) * 16 / size
            a = math.atan2(dy, dx)
            lobe = 0.5 + 0.5 * math.cos(n * (a + seed))
            if d < inner:
                put(img, x, y, centre[2] if d > inner - 0.9 else centre[3])
            elif d < inner + (outer - inner) * (0.55 + 0.45 * lobe):
                tone = 2 if lobe < 0.25 else 3 if d > outer - 2 else 4
                put(img, x, y, petal[tone])
    return img


def daisy() -> object:
    return petal_flower(["#9aa7b9", "#c3ccd8", "#e4e9f0", "#f4f6fa", "#ffffff"], YELLOW[2:], n=10)


def tulip(size: int = 16) -> object:
    """A pink tulip cup with a green stem, on a clear background."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            fx = x + 0.5 - 8
            if y < 9 and abs(fx) < 3.6 - (0 if y > 2 else (2 - y) * 0.6):
                if y < 2 and abs(fx) < 1.0:
                    continue
                tone = 5 if fx < -1 else 4 if fx < 1.5 else 3
                put(img, x, y, PINK[tone])
            elif y >= 9 and abs(fx) < 0.8:
                put(img, x, y, LEAF[4])
            elif y >= 11 and abs(fx + (y - 10) * 0.7 - 2.2) < 0.9:
                put(img, x, y, LEAF[5])
    return img


def cream_boards(size: int = 32) -> object:
    return planks(CREAM, size=size, rows=4, seed=37, vertical=False, knots=1, seam_dark=1)


def lid_boards(size: int = 32) -> object:
    """The planter's sides: cream boards with a painted leaf green stripe at the top."""
    img = planks(CREAM, size=size, rows=4, seed=41, vertical=True, knots=1, seam_dark=2)
    for x in range(size):
        for y in range(3):
            put(img, x, y, LEAF[4] if y else LEAF[6])
    return img


def soil(size: int = 16) -> object:
    img = canvas(size)
    rng = random.Random(43)
    for y in range(size):
        for x in range(size):
            r = rng.random()
            put(img, x, y, SOIL[3] if r > 0.6 else SOIL[2] if r > 0.2 else SOIL[4])
    return img


def hedge(size: int = 16) -> object:
    """Leafy foliage: clumps of light leaves over darker ones."""
    img = canvas(size)
    rng = random.Random(47)
    for y in range(size):
        for x in range(size):
            r = rng.random()
            tone = 4 if r > 0.55 else 3 if r > 0.2 else 5
            if (x * 5 + y * 3) % 11 == 0:
                tone = 6
            if (x * 7 + y * 2) % 13 == 0:
                tone = 2
            put(img, x, y, LEAF[tone])
    return img


def leaf_trim(size: int = 16) -> object:
    """Leaf green painted wood with a lit edge."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            if x <= 1:
                c = LEAF[6] if x == 0 else LEAF[5]
            elif x >= size - 2:
                c = LEAF[1] if x == size - 1 else LEAF[2]
            else:
                c = LEAF[4] if (x + y) % 9 else LEAF[5]
            put(img, x, y, c)
    return img


def picket(size: int = 16) -> object:
    """A cream fence picket with a pointed top (clear above the point)."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            fx = abs(x + 0.5 - 8)
            if y < 5 and fx > (y + 0.5) * 1.6:
                continue
            tone = 6 if x < 5 else 5 if x < 11 else 4
            if y >= 14:
                tone = 3
            put(img, x, y, CREAM[tone])
    return img


def stem(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 5 if x < 5 else 4 if x < 11 else 3
            if y % 7 == 0:
                tone -= 1
            put(img, x, y, LEAF[tone])
    return img


def leaf(size: int = 16) -> object:
    """A pointed leaf on a clear background (tip up), with a pale midrib."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            fx = x + 0.5 - 8
            w = 6.5 * math.sin(math.pi * (y + 0.5) / size)
            if abs(fx) < w:
                tone = 6 if abs(fx) < 0.6 else 5 if fx < 0 else 4
                put(img, x, y, LEAF[tone])
    return img


def seeds(t: float) -> object:
    """A sunflower heart: a spiral of seeds, darker at the rim, with a twinkle."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            a = math.atan2(y + 0.5 - 8, x + 0.5 - 8)
            spiral = (math.sin(a * 5 + d * 1.6) > 0.2)
            tone = 1 if d > 6.6 else 3 if spiral else 2
            if d < 2.2:
                tone = 4 if spiral else 3
            put(img, x, y, SEED[tone])
    sparkle(img, 6, 6, wave(t), colour=YELLOW[6], reach=2)
    return img


def ray(size: int = 16) -> object:
    """A sunflower ray petal (tip up) on a clear background."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            fx = x + 0.5 - 8
            w = 6.0 * min(1.0, (size - y) / 6) * (0.75 + 0.25 * math.sin(math.pi * y / size))
            if abs(fx) < w:
                tone = 6 if y < 3 else 5 if fx < 0 else 4
                if abs(fx) < 0.6 and y > 4:
                    tone = 3
                put(img, x, y, YELLOW[tone])
    return img


def bloom(t: float) -> object:
    """The emblem's petals: pink, lighter at the tips, with a soft glint."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 6 if y < 3 else 5 if y < 8 else 4 if y < 13 else 3
            if x in (0, 15):
                tone -= 1
            put(img, x, y, PINK[tone])
    return shine(img, t, colour="#ffffff", width=3.0, strength=0.45, pause=0.5)


def heart(t: float) -> object:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            tone = 2 if d > 7 else 3 if d > 5 else 4 if (x + y) % 3 else 5
            put(img, x, y, YELLOW[tone])
    sparkle(img, 5, 5, wave(t, 0.3), colour="#ffffff", reach=3)
    return img


def wing(pal):
    def paint(t: float) -> object:
        """A butterfly wing (body at the left edge): a rounded wing with a dark rim, pale
        spots and a vein, on a clear background; a shimmer passes over it."""
        img = canvas(16)
        for y in range(16):
            for x in range(16):
                # a rounded, slightly swept wing: widest at the outer tip
                d = math.hypot((x + 0.5) / 15.5, (y + 0.5 - 8 + x * 0.25) / (3.2 + x * 0.32))
                if d > 1.0:
                    continue
                tone = 0 if d > 0.86 else 5 if d < 0.4 else 4
                if abs(y + 0.5 - 8 + x * 0.25) < 0.5 and x < 11:
                    tone = 2
                put(img, x, y, pal[tone])
        for (x, y) in ((11, 3), (12, 5), (8, 4)):
            put(img, x, y, "#ffffff")
        return shine(img, t, colour="#ffffff", width=4.0, strength=0.5, pause=0.35)
    return paint


def plate(size: int = 16) -> object:
    """The cream lock plate: bevelled, with green corner leaves."""
    img = canvas(size)
    n = len(CREAM)
    for y in range(size):
        for x in range(size):
            if x <= 1 or y <= 1:
                c = CREAM[n - 1] if (x == 0 or y == 0) else CREAM[n - 2]
            elif x >= size - 2 or y >= size - 2:
                c = CREAM[2] if (x == size - 1 or y == size - 1) else CREAM[3]
            else:
                c = CREAM[5] if (x + y) % 7 else CREAM[4]
            put(img, x, y, c)
    for rx, ry in ((3, 3), (11, 3), (3, 11), (11, 11)):
        put(img, rx, ry, LEAF[5])
        put(img, rx + 1, ry, LEAF[4])
        put(img, rx, ry + 1, LEAF[3])
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(GLOW, t)


def textures() -> None:
    save(cream_boards(), "boards")
    save(lid_boards(), "lidboards")
    save(soil(), "soil")
    save(hedge(), "hedge")
    save(leaf_trim(), "leaftrim")
    save(picket(), "picket")
    save(stem(), "stem")
    save(leaf(), "leaf")
    save(daisy(), "daisy")
    save(petal_flower(YELLOW[1:], ["#5a3a10", "#7a4f16", "#9a6a20", "#b8862c"], n=5, inner=2.2), "buttercup")
    save(tulip(), "tulip")
    save(ray(), "ray")
    save(plate(), "plate")
    save(metal(LEAF[1:], rivets=(3, 12)), "band")
    save(metal(CREAM[:7], rivets=(4, 11)), "creamband")
    save(metal(YELLOW[:7], rivets=(4, 11)), "gold")
    save_animation(animate(seeds, 10), "seeds", frametime=3, interpolate=True)
    save_animation(animate(bloom, 12), "bloom", frametime=3)
    save_animation(animate(heart, 10), "heart", frametime=3, interpolate=True)
    save_animation(animate(wing(PINK), 12), "wing_pink", frametime=2)
    save_animation(animate(wing(YELLOW), 12), "wing_yellow", frametime=2)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def card(c, w: float, h: float, tex: str, yaw: float = 0.0) -> list[dict]:
    """A crossed pair of upright cutout cards (a plant seen from any side), standing on c."""
    x, y, z = c
    out = []
    for a in (45 + yaw, -45 + yaw):
        e = box((x - w / 2, y, z), (x + w / 2, y + h, z), tex, uv="full", skip=("up", "down", "east", "west"))
        turn(e, a, "y", (x, y, z))
        out.append(e)
    return out


def flat(c, s: float, tex: str, spin: float = 0.0) -> dict:
    """A flower lying face up (one cutout face)."""
    x, y, z = c
    e = only((x - s / 2, y, z - s / 2), (x + s / 2, y + 0.04, z + s / 2), {"up": (tex, [0, 0, 16, 16])})
    if spin:
        turn(e, spin, "y", (x, y, z))
    return e


def facing(c, s: float, tex: str, depth: float = 0.06, **kw) -> dict:
    """A flower facing +Z (one cutout face, plus the back)."""
    x, y, z = c
    return only((x - s / 2, y - s / 2, z), (x + s / 2, y + s / 2, z + depth),
                {"south": (tex, [0, 0, 16, 16]), "north": (tex, [16, 0, 0, 16])}, **kw)


def butterfly(c, pal: str, yaw: float = 0.0, open_: float = 35.0, s: float = 1.0) -> list[dict]:
    """A small butterfly resting with its wings half open (body along Z)."""
    x, y, z = c
    k = s
    parts = [bar((x, y, z - 0.5 * k), (x, y, z + 0.5 * k), 0.18, 0.18, "seeds")]
    for sx in (-1, 1):
        for (dz, s) in ((-0.15 * k, 0.62 * k), (0.36 * k, 0.45 * k)):
            w = only((x, y, z + dz - s), (x + sx * s * 1.3, y + 0.02, z + dz + s),
                     {"up": (pal, [0, 0, 16, 16] if sx > 0 else [16, 0, 0, 16]),
                      "down": (pal, [0, 0, 16, 16] if sx > 0 else [16, 0, 0, 16])})
            turn(w, open_ * sx, "z", (x, y, z))
            parts.append(w)
    turn(parts, yaw, "y", (x, y, z))
    return parts


def sunflower(x: float, z: float, lean: float, yaw: float) -> list[dict]:
    """A tall sunflower on a leafy stem, its head tipped toward the front."""
    hy = SUN_HEAD
    parts = [bar((x, BED - 0.2, z), (x, hy - 0.4, z - 0.3), 0.5, 0.5, "stem")]
    for (ly, side) in ((BED + 1.8, 1), (BED + 3.4, -1)):
        lf = box((x - 0.9, ly, z - 0.04), (x + 0.9, ly + 2.4, z + 0.04), "leaf", uv="full")
        turn(lf, -55 * side, "z", (x, ly, z))
        turn(lf, yaw + 90, "y", (x, ly, z))
        parts.append(lf)
    head = []
    head += prism((x, hy, z + 0.2), 1.45, 0.46, "seeds", axis="z", sides=16, cap="seeds", uv="full")
    head += prism((x, hy, z - 0.12), 1.2, 0.3, "stem", axis="z", sides=8, cap="hedge")
    for k in range(14):
        r = only((x - 0.62, hy + 1.15, z + 0.06), (x + 0.62, hy + 2.85, z + 0.13),
                 {"south": ("ray", [0, 0, 16, 16]), "north": ("ray", [16, 0, 0, 16])})
        turn(r, k * 360 / 14, "z", (x, hy, z))
        head.append(r)
    for k in range(14):
        r = only((x - 0.5, hy + 1.0, z - 0.02), (x + 0.5, hy + 2.25, z + 0.05),
                 {"south": ("ray", [0, 0, 16, 16]), "north": ("ray", [16, 0, 0, 16])})
        turn(r, k * 360 / 14 + 180 / 14, "z", (x, hy, z))
        head.append(r)
    turn(head, lean, "x", (x, hy, z))
    turn(head, yaw, "y", (x, hy, z))
    parts += head
    return parts


def planter() -> list[dict]:
    """The flower bed on the lid: soil, a picket fence, hedges and flowers."""
    parts = []
    e = 0.1
    parts.append(box((L0 - e, LID_TOP - 0.01, L0 - e), (L1 + e, BED, L1 + e), "soil", skip=("down",),
                     faces={"north": "leaftrim", "south": "leaftrim", "east": "leaftrim", "west": "leaftrim"}))
    # picket fence round the bed: two rails and pickets
    lo, hi = L0 + 0.15, L1 - 0.15
    for y0 in (BED + 0.35, BED + 1.0):
        for (a, b) in (((lo, lo), (hi, lo + 0.2)), ((lo, hi - 0.2), (hi, hi)), ((lo, lo), (lo + 0.2, hi)),
                       ((hi - 0.2, lo), (hi, hi))):
            parts.append(box((a[0], y0, a[1]), (b[0], y0 + 0.3, b[1]), "creamband"))
    n = 7
    for i in range(n):
        u = lo + 0.5 + i * (hi - lo - 1.0) / (n - 1)
        for (px, pz, ax) in ((u, lo + 0.1, "x"), (u, hi - 0.1, "x"), (lo + 0.1, u, "z"), (hi - 0.1, u, "z")):
            if ax == "x":
                parts.append(only((px - 0.32, BED - 0.05, pz - 0.1), (px + 0.32, BED + 1.75, pz + 0.1),
                                  {"north": ("picket", [0, 0, 16, 16]), "south": ("picket", [0, 0, 16, 16]),
                                   "east": ("picket", [0, 6, 4, 16]), "west": ("picket", [0, 6, 4, 16])}))
            elif i not in (0, n - 1):
                parts.append(only((px - 0.1, BED - 0.05, pz - 0.32), (px + 0.1, BED + 1.75, pz + 0.32),
                                  {"east": ("picket", [0, 0, 16, 16]), "west": ("picket", [0, 0, 16, 16]),
                                   "north": ("picket", [0, 6, 4, 16]), "south": ("picket", [0, 6, 4, 16])}))
    # hedge mounds
    for (x0, z0, x1, z1, h) in ((1.2, 1.2, 6.0, 3.6, 1.2), (10.0, 1.2, 14.8, 3.6, 1.1), (5.6, 1.4, 10.4, 3.0, 0.8),
                                (1.2, 3.4, 3.2, 9.0, 0.8), (12.8, 3.4, 14.8, 9.5, 0.9), (6.4, 6.6, 9.6, 9.4, 1.0),
                                (7.0, 7.2, 9.0, 8.8, 1.5), (1.2, 12.6, 3.4, 14.8, 0.7), (12.6, 12.6, 14.8, 14.8, 0.7),
                                (4.0, 5.0, 5.4, 6.6, 0.5), (10.4, 9.6, 12.0, 11.0, 0.55), (5.6, 11.6, 7.2, 12.8, 0.45),
                                (9.6, 4.0, 11.4, 5.0, 0.4)):
        parts.append(box((x0, BED - 0.02, z0), (x1, BED + h, z1), "hedge", skip=("down",)))
    # flowers: tulips and daisies standing in the bed, buttercups lying in it
    for (x, z) in ((4.6, 5.4), (5.4, 9.4), (10.6, 5.2), (11.4, 9.6), (8.0, 11.6), (4.0, 12.2), (12.0, 12.4),
                   (9.6, 13.4), (6.4, 13.2)):
        parts += card((x, BED, z), 1.5, 1.9, "tulip", yaw=x * 11)
    for (x, z, s) in ((7.6, 5.4, 1.2), (4.0, 10.4, 1.1), (10.4, 11.0, 1.1), (10.6, 8.0, 1.0), (2.6, 10.6, 0.9),
                      (13.4, 10.8, 0.9), (8.0, 13.6, 1.0)):
        parts.append(flat((x, BED + 0.5, z), s, "daisy", spin=x * 23))
        parts.append(bar((x, BED, z), (x, BED + 0.5, z), 0.1, 0.1, "stem"))
    for (x, z) in ((6.0, 12.6), (8.6, 4.2), (12.0, 7.0), (3.8, 7.6)):
        parts.append(flat((x, BED + 0.08, z), 0.8, "buttercup", spin=z * 31))
    parts += sunflower(3.3, 2.6, -18, 12)
    parts += sunflower(12.7, 2.4, -14, -14)
    return parts


def emblem() -> list[dict]:
    """The garden flower on a leafy wreath, on the lid's front, over the lock plate."""
    cx, cy, z = 8.0, 12.05, 16.02
    parts = []
    for k in range(8):
        p = box((cx - 0.6, cy + 0.3, z - 0.02), (cx + 0.6, cy + 2.05, z + 0.1), "bloom", uv="full")
        turn(p, k * 45, "z", (cx, cy, z))
        parts.append(p)
    parts += prism((cx, cy, z + 0.12), 0.72, 0.16, "heart", axis="z", sides=8, cap="heart", glow=5, uv="full")
    # leafy wreath behind it
    for k in range(10):
        a = k * 36 + 18
        lf = only((cx - 0.4, cy + 1.7, L1 + 0.02), (cx + 0.4, cy + 2.7, L1 + 0.08),
                  {"south": ("leaf", [0, 0, 16, 16]), "north": ("leaf", [16, 0, 0, 16])})
        turn(lf, a, "z", (cx, cy, L1))
        parts.append(lf)
    return parts


def lock() -> list[dict]:
    """A cream lock plate across the seam with a scalloped petal rim and a gold keyhole."""
    z0, zf = B1 - 0.05, 16.02
    parts = [box((5.8, 5.0, z0), (10.2, 11.2, zf), "plate",
                 uv={"north": [0, 0, 4, 4], "east": [0, 0, 1, 4], "west": [0, 0, 1, 4], "up": [0, 0, 4, 1],
                     "down": [0, 0, 4, 1], "south": [0, 0, 16, 16]})]
    for (x, y) in ((5.8, 5.6), (5.8, 7.4), (10.2, 5.6), (10.2, 7.4), (7.0, 5.0), (9.0, 5.0)):
        parts += prism((x, y, (z0 + zf) / 2), 0.55, zf - z0 - 0.04, "creamband", axis="z", sides=8, cap="plate")
    parts.append(box((7.35, 7.05, zf - 0.03), (8.65, 8.35, zf + 0.04), "keyhole", glow=11, shade=False, uv="full"))
    parts.append(box((7.68, 6.0, zf - 0.03), (8.32, 7.25, zf + 0.04), "keyhole", glow=11, shade=False, uv="full"))
    parts += arc((8.0, 7.25, zf + 0.06), 1.15, 0, 360, 10, 0.3, 0.14, "gold", plane="xy")
    return parts


def walls() -> list[dict]:
    """Green frames, daisies on the front panels, trellis and a flowering vine on the sides."""
    parts = []
    y0, y1 = 1.4, SEAM - 1.3
    t = 0.42

    def frame(a0, a1, z):
        return [box((a0, y1 - t, z), (a1, y1, z + 0.26), "leaftrim"), box((a0, y0, z), (a1, y0 + t, z + 0.26), "leaftrim"),
                box((a0, y0 + t, z), (a0 + t, y1 - t, z + 0.26), "leaftrim"),
                box((a1 - t, y0 + t, z), (a1, y1 - t, z + 0.26), "leaftrim")]

    for a0, a1 in ((1.8, 5.2), (10.8, 14.2)):
        parts += frame(a0, a1, B1)
        cx, cy = (a0 + a1) / 2, (y0 + y1) / 2
        parts.append(facing((cx, cy + 0.5, B1 + 0.3), 2.2, "daisy"))
        parts.append(bar((cx, y0 + t, B1 + 0.3), (cx, cy - 0.4, B1 + 0.3), 0.16, 0.08, "stem"))
        lf = facing((cx + 0.5, cy - 1.3, B1 + 0.28), 0.9, "leaf")
        turn(lf, -40, "z", (cx + 0.5, cy - 1.3, B1 + 0.3))
        parts.append(lf)
    side = frame(2.0, 14.0, B1)
    # trellis: crossing laths
    for k in range(4):
        a = 2.4 + k * 3.2
        side.append(bar((a, y0 + t, B1 + 0.12), (a + 3.6, y1 - t, B1 + 0.12), 0.22, 0.1, "creamband"))
        side.append(bar((a + 3.6, y0 + t, B1 + 0.18), (a, y1 - t, B1 + 0.18), 0.22, 0.1, "creamband"))
    # a flowering vine winding over it
    pts = [(2.4, 1.9), (4.0, 4.8), (6.0, 3.4), (8.0, 6.4), (10.0, 4.2), (12.0, 6.6), (13.6, 5.0)]
    for (ax, ay), (bx, by) in zip(pts, pts[1:]):
        side.append(bar((ax, ay, B1 + 0.3), (bx, by, B1 + 0.3), 0.2, 0.14, "stem"))
    for i, (px, py) in enumerate(pts[1:]):
        if i % 2:
            side.append(facing((px, py, B1 + 0.36), 1.2, "tulip" if i == 3 else "buttercup"))
        else:
            lf = facing((px, py + 0.5, B1 + 0.33), 1.0, "leaf")
            turn(lf, 30 if i % 4 else -30, "z", (px, py, B1 + 0.33))
            side.append(lf)
    for ang in (90, -90):
        parts += turn(copy(side), ang, "y", (8.0, 0.0, 8.0))
    # leaf green corner posts
    for x in (B0, B1):
        for z in (B0, B1):
            parts.append(box((x - 0.55, 0.0, z - 0.55), (x + 0.55, SEAM, z + 0.55), "leaftrim", skip=("down",)))
            parts.append(box((x - 0.7, SEAM - 0.5, z - 0.7), (x + 0.7, SEAM, z + 0.7), "creamband"))
    return parts


def lid_vines() -> list[dict]:
    """A leafy vine run along the lid's front and sides, with little blossoms."""
    parts = []
    y = SEAM + 0.9
    for (z, flip) in ((L1 + 0.02, 1),):
        pts = [(0.6, y + 0.4), (2.6, y + 1.4), (4.4, y + 0.5)]
        pts2 = [(15.4, y + 0.4), (13.4, y + 1.4), (11.6, y + 0.5)]
        for run in (pts, pts2):
            for (ax, ay), (bx, by) in zip(run, run[1:]):
                parts.append(bar((ax, ay, z + 0.08), (bx, by, z + 0.08), 0.2, 0.12, "stem"))
            for i, (px, py) in enumerate(run):
                parts.append(facing((px, py + 0.2, z + 0.12), 1.0, "leaf" if i != 1 else "tulip"))
    return parts


def back_hinges() -> list[dict]:
    """Two leaf-shaped green hinges: a knuckle on the seam and a leaf plate up and down."""
    parts = []
    for x in (4.0, 12.0):
        parts += prism((x, SEAM, L0 - 0.05), 0.48, 2.2, "band", axis="x", sides=8, cap="creamband")
        for (y, a) in ((SEAM + 1.7, 0), (SEAM - 1.7, 180)):
            lf = box((x - 1.0, y - 1.6, B0 - 0.26), (x + 1.0, y + 1.6, B0 - 0.1), "leaf", uv="full",
                     skip=("east", "west", "up", "down"))
            turn(lf, a, "z", (x, y, B0))
            parts.append(lf)
    return parts


def base() -> list[dict]:
    return [box((B0 - 0.28, 0.0, B0 - 0.28), (B1 + 0.28, 0.85, B1 + 0.28), "band", skip=("down",)),
            box((B0 - 0.1, 0.85, B0 - 0.1), (B1 + 0.1, 1.15, B1 + 0.1), "creamband", skip=("down",))]


def models() -> dict:
    p = shell("boards", "lidboards", "soil")
    p += rims("creamband", e=0.2)[1:2]
    p += base()
    p += walls()
    p += lid_vines()
    p += planter()
    p += emblem()
    p += lock()
    p += back_hinges()
    p += butterfly((8.0, BED + 1.6, 8.0), "wing_pink", yaw=35, open_=50, s=1.5)
    p += butterfly((12.6, BED + 2.9, L1 - 0.3), "wing_yellow", yaw=90, open_=55, s=1.2)
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
