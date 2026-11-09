"""February Chest (issue #1464, design in #1407): the monthly crate chest for February, a
Valentine's Day treasure chest in red, pink and rose gold.

A glossy red lacquer body embossed with rows of small hearts, wrapped like a present: a
wide pink satin ribbon crosses the lid both ways and runs down every wall, and a large
pink bow (two big loops, two inner loops, a knot set with a heart jewel and two notched
tails trailing over the lid) sits on top of a red quilted pillow. Rose gold frames it:
bands round the base, the seam and the pillow, rose gold brackets up every corner, raised
panel frames on the front walls with a pink enamel heart in each, and small rose gold
hearts on the side walls either side of the ribbon. Red roses with green leaves bloom on
the four corners of the lid.

The emblem is a large 3D heart on the lid's front: a rose gold heart with a pulsing pink
enamel heart set into it; its point is the hasp, dropping onto a rose gold lock plate with
a heart-shaped keyhole surround and a glowing pink keyhole. Two rose gold hinges with heart
ends hold the lid at the back.

Animation (one loop): a satin sheen slides along the ribbon, the enamel hearts beat (two
quick pulses, then rest), the heart jewel twinkles and the keyhole glows pink.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block like the other crate chests. Deliberately unlike the
other crate chests: a gift-wrapped silhouette with a bow on top instead of a crown, spire
or roof.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM, corner_brackets,  # noqa: F401
                                    keyhole_glow, lock_plate, metal, put, rims, shell)
from art.kit import animate, arc, bar, box, canvas, display, model, move, prism, save, save_animation, shine, sparkle, \
    turn, wave

ID = "february_chest"
NAME = "February Chest"

RED = ["#26030a", "#430611", "#650b1b", "#8a1126", "#ae1a32", "#cf2740", "#ea4458", "#fb7584"]
PINK = ["#5a1637", "#86244f", "#b2386d", "#d6528c", "#ee74a8", "#f99cc4", "#ffc6de", "#ffeaf3"]
ROSEGOLD = ["#4d1f2c", "#84434f", "#b4676f", "#da8f8e", "#f0b2a8", "#fbd2c8", "#ffe9e2", "#fffaf7"]
ROSE = ["#3e0410", "#6e0818", "#9c0f24", "#c71a32", "#e8334a", "#ff5f6c", "#ff9a9c"]
LEAF = ["#0d2a10", "#16431a", "#215e25", "#2e7a31", "#43963f", "#66b256"]
ENAMEL = ["#4a0b22", "#7a1338", "#ac1f50", "#d8346b", "#f45a8a", "#ff8fb0", "#ffc9d9", "#fff0f5"]
GLOW = ["#2a0612", "#5a0f2a", "#902047", "#c93a68", "#f0679a", "#ffa4c6", "#fff0f6"]

PILLOW = LID_TOP + 0.9             # top of the quilted pillow on the lid
RIB = 2.2                          # ribbon width
RIB_P = 0.16                       # how far the ribbon stands proud


# ======================================================================================
# Textures
# ======================================================================================

HEART = [
    ".##.##.",
    "#######",
    "#######",
    ".#####.",
    "..###..",
    "...#...",
]


def lacquer(size: int = 32) -> object:
    """Glossy red lacquer with rows of embossed hearts (offset every other row) and a soft
    sheen toward the top."""
    img = canvas(size)
    rng = random.Random(14)
    for y in range(size):
        for x in range(size):
            tone = 5 if y < 6 else 4
            if (x + y) % 29 == 0:
                tone += 1
            put(img, x, y, RED[tone])
    for row, cy in enumerate((2, 18)):
        for cx in ((4, 20) if row == 0 else (12, 28)):
            for j, line in enumerate(HEART):
                for i, ch in enumerate(line):
                    if ch == "#":
                        put(img, (cx + i - 3) % size, cy + j, RED[3] if j >= 4 or i == 6 else RED[5])
            put(img, (cx - 2) % size, cy + 1, RED[6])
    return img


def quilt(size: int = 32) -> object:
    """The pillow: red quilted satin in diamond tufts with a rose gold heart button where
    the seams cross."""
    img = canvas(size)
    cell = 16
    for y in range(size):
        for x in range(size):
            a, b = (x + y) % cell, (x - y) % cell
            d = min(a, cell - a, b, cell - b)
            c = RED[2] if d == 0 else RED[3] if d == 1 else RED[5] if d >= 4 else RED[4]
            put(img, x, y, c)
    for y in range(0, size, cell):
        for x in range(0, size, cell):
            for px, py in ((x, y), (x + cell // 2, y + cell // 2)):
                for dx, dy in ((-1, 0), (1, 0), (0, 0), (0, 1)):
                    put(img, (px + dx) % size, (py + dy) % size, ROSEGOLD[5] if dy == 0 else ROSEGOLD[3])
    return img


def satin(t: float) -> object:
    """Pink satin ribbon: lit edges, a stitched hem and a sheen sliding along it."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            if x in (0, 15):
                c = PINK[3]
            elif x in (1, 14):
                c = PINK[6] if (y % 3) else PINK[4]
            else:
                tone = 6 if 3 <= x <= 7 else 5
                if (x + y) % 9 == 0:
                    tone -= 1
                c = PINK[tone]
            put(img, x, y, c)
    return shine(img, t, colour="#fff2f8", width=3.5, strength=0.75, pause=0.35)


def rose_gold(size: int = 16) -> object:
    img = canvas(size)
    rng = random.Random(8)
    for y in range(size):
        for x in range(size):
            tone = 5 if y == 0 else 4 if y < 6 else 3
            if y >= size - 2:
                tone = 2
            if rng.random() < 0.08:
                tone = max(2, tone - 1)
            put(img, x, y, ROSEGOLD[tone])
    for _ in range(4):
        put(img, rng.randrange(1, size - 1), rng.randrange(1, 6), ROSEGOLD[7])
    return img


def trim(size: int = 16) -> object:
    """Rose gold trim with a row of tiny engraved hearts down the middle."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            if x <= 1:
                c = ROSEGOLD[6] if x == 0 else ROSEGOLD[5]
            elif x >= size - 2:
                c = ROSEGOLD[1] if x == size - 1 else ROSEGOLD[2]
            else:
                c = ROSEGOLD[4] if (x + y) % 7 else ROSEGOLD[5]
            put(img, x, y, c)
    for cy in range(1, size, 5):
        for (dx, dy) in ((-1, 0), (1, 0), (-1, 1), (0, 1), (1, 1), (0, 2)):
            put(img, 8 + dx, cy + dy, ROSEGOLD[2])
    return img


def stud(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 6)
            put(img, x, y, ROSEGOLD[7 if d < 2.5 else 6 if d < 5 else 5 if d < 8 else 4 if d < 11 else 3])
    return img


def petals(size: int = 16) -> object:
    """Rose petals: deep red with lighter curled edges."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 5 if y < 3 else 4 if y < 8 else 3 if y < 13 else 2
            if (x * 5 + y * 3) % 13 == 0:
                tone += 1
            if x in (0, 15):
                tone -= 1
            put(img, x, y, ROSE[tone])
    return img


def bud(size: int = 16) -> object:
    """The top of a rose: a dark spiral of furled petals."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            r = math.hypot(dx, dy)
            a = math.atan2(dy, dx)
            spiral = (r - a * 1.3) % 3.2
            tone = 3 if spiral < 0.9 else 6 if spiral < 1.8 else 5
            if r < 1.5:
                tone = 2
            put(img, x, y, ROSE[tone])
    return img


def leaf(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 4 if x < 8 else 3
            if x in (7, 8):
                tone = 5
            elif (x + y) % 5 == 0:
                tone -= 1
            put(img, x, y, LEAF[tone])
    return img


def enamel(t: float) -> object:
    """Pink enamel that beats: two quick pulses, then rest."""
    img = canvas(16)
    beat = max(math.exp(-((t - 0.1) / 0.06) ** 2), 0.7 * math.exp(-((t - 0.28) / 0.06) ** 2))
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 5.5)
            base = 5 if d < 3 else 4 if d < 7 else 3 if d < 11 else 2
            tone = min(7, base + int(round(beat * 2)))
            put(img, x, y, ENAMEL[tone])
    for (x, y) in ((4, 3), (5, 3), (4, 4)):
        put(img, x, y, ENAMEL[7])
    return img


def jewel(t: float) -> object:
    """A cut pink jewel with a twinkle."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = max(abs(x + 0.5 - 8), abs(y + 0.5 - 8))
            facet = (x < 8) != (y < 8)
            tone = 3 if d > 6 else 4 if d > 4 else 5 if d > 2 else 6
            if facet and d > 2:
                tone -= 1
            put(img, x, y, ENAMEL[tone])
    sparkle(img, 5, 5, wave(t), colour="#ffffff", reach=3)
    sparkle(img, 11, 10, wave(t, 0.5) * 0.7, colour="#ffe4ee", reach=2)
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(GLOW, t)


def textures() -> None:
    save(lacquer(), "lacquer")
    save(quilt(), "quilt")
    save(rose_gold(), "rosegold")
    save(trim(), "trim")
    save(stud(), "stud")
    save(petals(), "petals")
    save(bud(), "bud")
    save(leaf(), "leaf")
    save(metal(ROSEGOLD[:7], rivets=(3, 12)), "band")
    save(lock_plate(ROSEGOLD[:7], hole=RED[0]), "plate")
    save_animation(animate(satin, 12), "satin", frametime=3)
    save_animation(animate(enamel, 16), "enamel", frametime=2, interpolate=True)
    save_animation(animate(jewel, 12), "jewel", frametime=3, interpolate=True)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def heart(cx: float, cy: float, z: float, s: float, depth: float, tex: str, **kw) -> list[dict]:
    """A 3D heart facing +Z: two round lobes and a diamond point. s is its half width."""
    r = s * 0.53
    parts = prism((cx - s * 0.47, cy + s * 0.25, z), r, depth, tex, axis="z", sides=8, cap=tex, **kw)
    parts += prism((cx + s * 0.47, cy + s * 0.25, z), r, depth, tex, axis="z", sides=8, cap=tex, **kw)
    d = s * 0.66
    tip = box((cx - d, cy - d, z - depth / 2), (cx + d, cy + d, z + depth / 2), tex, **kw)
    turn(tip, 45, "z", (cx, cy - s * 0.06, z))
    parts.append(tip)
    return parts


def flat_heart(cx: float, cy: float, z: float, s: float, depth: float, tex: str, **kw) -> list[dict]:
    """A cheap heart (three boxes) for small studs: two square lobes and a diamond point."""
    d = s * 0.62
    lobe = s * 0.5
    parts = [box((cx - s, cy, z - depth / 2), (cx - s + 2 * lobe * 0.95, cy + lobe * 1.25, z + depth / 2), tex, **kw),
             box((cx + s - 2 * lobe * 0.95, cy, z - depth / 2), (cx + s, cy + lobe * 1.25, z + depth / 2), tex, **kw)]
    tip = box((cx - d, cy - d, z - depth / 2), (cx + d, cy + d, z + depth / 2), tex, **kw)
    turn(tip, 45, "z", (cx, cy - s * 0.02, z))
    parts.append(tip)
    return parts


def ribbons() -> list[dict]:
    """The pink ribbon: across the top both ways and down every wall."""
    h, p = RIB / 2, RIB_P
    parts = [
        # over the pillow and lid top, x and z
        box((L0 - p, PILLOW, 8.0 - h), (L1 + p, PILLOW + p, 8.0 + h), "satin", skip=("down",)),
        box((8.0 - h, PILLOW, L0 - p), (8.0 + h, PILLOW + p, L1 + p), "satin", skip=("down",)),
    ]
    # down the walls (lid and body); the front run stops at the emblem
    for side in (-1, 1):
        x0 = L1 if side > 0 else L0 - p
        parts.append(box((x0, SEAM - 0.9, 8.0 - h), (x0 + p, PILLOW + p, 8.0 + h), "satin", skip=("up", "down")))
        xb = B1 if side > 0 else B0 - p
        parts.append(box((xb, 0.0, 8.0 - h), (xb + p, SEAM, 8.0 + h), "satin", skip=("up", "down")))
    parts.append(box((8.0 - h, SEAM - 0.9, L0 - p), (8.0 + h, PILLOW + p, L0), "satin", skip=("up", "down")))
    parts.append(box((8.0 - h, 0.0, B0 - p), (8.0 + h, SEAM, B0), "satin", skip=("up", "down")))
    parts.append(box((8.0 - h, 13.4, L1), (8.0 + h, PILLOW + p, L1 + p), "satin", skip=("up", "down")))
    parts.append(box((8.0 - h, 0.0, B1), (8.0 + h, 4.6, B1 + p), "satin", skip=("up", "down")))
    # the pillow's sides under the ribbon wrap
    for side in (-1, 1):
        parts.append(box((8.0 - h, LID_TOP, 8.0 + side * 6.6 - p / 2), (8.0 + h, PILLOW, 8.0 + side * 6.6 + p / 2),
                         "satin", skip=("up", "down")))
    return parts


def bow() -> list[dict]:
    """The bow on top: two big loops, two inner loops, a knot with a heart jewel and two
    notched tails trailing toward the front corners."""
    parts = []
    y = PILLOW + RIB_P
    kc = (8.0, y + 1.0, 8.0)
    # big loops (in the xy plane, 2.1 deep), leaning a little back
    for side in (-1, 1):
        c = (8.0 + side * 2.4, y + 2.2, 8.0)
        a0, a1 = (-165, 165) if side > 0 else (15, 345)
        loop = arc(c, 2.05, a0, a1, 10, 0.42, 3.0, "satin", plane="xy")
        turn(loop, -12, "x", (8.0, y, 8.0))
        turn(loop, side * 12, "y", (8.0, y, 8.0))
        parts += loop
    # the knot
    parts.append(box((kc[0] - 1.0, kc[1] - 0.95, kc[2] - 1.5), (kc[0] + 1.0, kc[1] + 0.95, kc[2] + 1.5), "satin"))
    parts += flat_heart(8.0, kc[1] - 0.05, kc[2] + 1.6, 0.7, 0.24, "jewel", glow=6)
    # two roses tucked in front of the knot
    for side in (-1, 1):
        parts += rose(8.0 + side * 1.55, y, 10.1, s=1.0, turn_y=side * 30, rings=1)
    # the tails: notched ends trailing over the pillow toward the front corners
    for side in (-1, 1):
        a = (8.0 + side * 0.6, 8.9)
        b = (8.0 + side * 3.7, 13.1)
        ln = math.hypot(b[0] - a[0], b[1] - a[1])
        ang = math.degrees(math.atan2(b[0] - a[0], b[1] - a[1]))
        mx, mz = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
        tail = box((mx - 0.75, y, mz - ln / 2), (mx + 0.75, y + 0.2, mz + ln / 2), "satin")
        turn(tail, -3, "x", (mx, y, mz))
        turn(tail, ang, "y", (mx, y, mz))
        parts.append(tail)
        for k in (-1, 1):
            tip = box((-0.42, y, -0.42), (0.42, y + 0.2, 0.42), "satin")
            turn(tip, 45, "y", (0.0, y, 0.0))
            ox = b[0] + math.cos(math.radians(ang)) * 0.38 * k
            oz = b[1] - math.sin(math.radians(ang)) * 0.38 * k
            parts.append(move(tip, ox, 0.0, oz))
    return parts


def pillow() -> list[dict]:
    """A red quilted pillow on the lid with a rose gold band at its foot."""
    lo, hi = L0 + 0.95, L1 - 0.95
    parts = [box((lo, LID_TOP, lo), (hi, PILLOW, hi), "quilt", skip=("down",))]
    parts.append(box((lo - 0.12, LID_TOP, lo - 0.12), (hi + 0.12, LID_TOP + 0.3, hi + 0.12), "trim",
                     skip=("up", "down")))
    return parts


def rose(cx: float, cy: float, cz: float, s: float = 1.0, turn_y: float = 0.0, rings: int = 2) -> list[dict]:
    """A red rose: a furled bud in rings of outward-curling petals, with two leaves."""
    parts = []
    for k, (r, h, tilt) in enumerate(((0.95, 0.75, 28), (0.62, 0.95, 14))[2 - rings:]):
        for i in range(4):
            ang = i * 90 + k * 45 + turn_y
            p = box((cx - r * s, cy, cz + r * s * 0.55), (cx + r * s, cy + h * s, cz + r * s * 0.55 + 0.22 * s),
                    "petals")
            turn(p, -tilt, "x", (cx, cy, cz + r * s * 0.55))
            turn(p, ang, "y", (cx, cy, cz))
            parts.append(p)
    b = box((cx - 0.45 * s, cy + 0.1, cz - 0.45 * s), (cx + 0.45 * s, cy + 1.05 * s, cz + 0.45 * s), "petals",
            faces={"up": "bud"})
    turn(b, 45 + turn_y, "y", (cx, cy, cz))
    parts.append(b)
    for side in (-1, 1):
        lf = box((cx - 0.45 * s, cy + 0.02, cz), (cx + 0.45 * s, cy + 0.14, cz + 1.7 * s), "leaf")
        turn(lf, 12, "x", (cx, cy, cz))
        turn(lf, turn_y + side * 40, "y", (cx, cy, cz))
        parts.append(lf)
    return parts


def corners() -> list[dict]:
    """Rose gold caps on the lid's corners, each carrying a rose."""
    parts = []
    for cx in (L0 + 0.85, L1 - 0.85):
        for cz in (L0 + 0.85, L1 - 0.85):
            parts.append(box((cx - 1.0, LID_TOP - 0.05, cz - 1.0), (cx + 1.0, LID_TOP + 0.45, cz + 1.0), "rosegold"))
            rx, rz = cx + (0.45 if cx < 8 else -0.45), cz + (0.45 if cz < 8 else -0.45)
            ang = math.degrees(math.atan2(rx - 8.0, rz - 8.0))
            parts += rose(rx, LID_TOP + 0.45, rz, s=1.35, turn_y=ang + 180)
    return parts


def wall_hearts() -> list[dict]:
    """Rose gold frames with enamel hearts on the front, rose gold hearts on the sides."""
    parts = []
    y0, y1 = 1.6, SEAM - 1.4
    for a0, a1 in ((1.8, 5.6), (10.4, 14.2)):
        z0, z1 = B1, B1 + 0.26
        t = 0.45
        parts += [box((a0, y1 - t, z0), (a1, y1, z1), "trim"), box((a0, y0, z0), (a1, y0 + t, z1), "trim"),
                  box((a0, y0 + t, z0), (a0 + t, y1 - t, z1), "trim"), box((a1 - t, y0 + t, z0), (a1, y1 - t, z1), "trim")]
        cx, cy = (a0 + a1) / 2, (y0 + y1) / 2 - 0.1
        parts += heart(cx, cy, B1 + 0.28, 1.35, 0.2, "rosegold")
        parts += heart(cx, cy + 0.05, B1 + 0.42, 0.95, 0.16, "enamel", glow=5)
    for x, out in ((B1, 1), (B0, -1)):
        for zc in (3.9, 12.1):
            hz = heart(0.0, 4.4, 0.0, 1.45, 0.26, "rosegold")
            hz += heart(0.0, 4.45, 0.24, 1.05, 0.18, "enamel", glow=5)
            # stand the hearts on the side wall facing out
            turn(hz, 90 * out, "y", (0.0, 0.0, 0.0))
            parts += move(hz, x + 0.13 * out, 0.0, zc)
    return parts


def emblem() -> list[dict]:
    """The big heart on the lid's front and its hasp."""
    z = L1
    parts = heart(8.0, 12.0, z + 0.2, 2.45, 0.36, "rosegold")
    parts += heart(8.0, 12.08, z + 0.42, 1.85, 0.2, "enamel", glow=7)
    parts.append(box((7.4, 8.2, B1 + 0.5), (8.6, 10.0, z + 0.36), "rosegold"))
    parts.append(box((7.65, 8.5, z + 0.36), (8.35, 9.2, z + 0.44), "stud", uv="full"))
    return parts


def lock() -> list[dict]:
    """A rose gold lock plate across the vanilla latch with a heart keyhole surround."""
    parts = []
    z0, z1 = B1 - 0.05, 16.02
    parts.append(box((5.4, 4.6, z0), (10.6, 8.3, z1), "plate",
                     uv={"north": [0, 0, 4, 4], "east": [0, 0, 1, 4], "west": [0, 0, 1, 4], "up": [0, 0, 4, 1],
                         "down": [0, 0, 4, 1], "south": [0, 0, 16, 16]}))
    parts.append(box((5.2, 8.15, z1 - 0.1), (10.8, 8.5, z1 + 0.1), "trim"))
    parts.append(box((5.2, 4.4, z1 - 0.1), (10.8, 4.75, z1 + 0.1), "trim"))
    parts += flat_heart(8.0, 6.35, z1 + 0.02, 1.25, 0.12, "rosegold")
    parts.append(box((7.5, 6.35, z1 + 0.06), (8.5, 7.25, z1 + 0.12), "keyhole", glow=11, shade=False, uv="full"))
    parts.append(box((7.75, 5.25, z1 + 0.06), (8.25, 6.5, z1 + 0.12), "keyhole", glow=11, shade=False, uv="full"))
    for x in (6.1, 9.9):
        parts.append(box((x - 0.3, 7.25, z1), (x + 0.3, 7.85, z1 + 0.14), "stud", uv="full"))
    return parts


def back_hinges() -> list[dict]:
    parts = []
    for x in (4.0, 12.0):
        parts += prism((x, SEAM, L0 + 0.1), 0.55, 2.4, "rosegold", axis="x", sides=8, cap="stud")
        parts.append(box((x - 0.8, SEAM - 2.4, B0 - 0.24), (x + 0.8, SEAM - 0.35, B0), "trim"))
        parts.append(box((x - 0.3, SEAM - 2.0, B0 - 0.36), (x + 0.3, SEAM - 1.4, B0 - 0.22), "stud", uv="full"))
        parts.append(box((x - 0.8, SEAM + 0.35, L0 - 0.24), (x + 0.8, SEAM + 2.5, L0), "trim"))
        hz = heart(x, SEAM + 3.0, L0 - 0.14, 1.0, 0.22, "rosegold")
        turn(hz, 180, "y", (x, SEAM, L0 - 0.14))
        parts += hz
    return parts


def base() -> list[dict]:
    parts = [box((B0 - 0.3, 0.0, B0 - 0.3), (B1 + 0.3, 1.0, B1 + 0.3), "band", skip=("down",))]
    for cx in (B0 + 0.6, B1 - 0.6):
        for cz in (B0 + 0.6, B1 - 0.6):
            foot = box((cx - 0.75, 0.0, cz - 0.75), (cx + 0.75, 1.15, cz + 0.75), "rosegold", faces={"up": "stud"})
            parts.append(turn(foot, 45, "y", (cx, 0.0, cz)))
    return parts


def models() -> dict:
    p = shell("lacquer", "lacquer", "quilt")
    p += rims("band", e=0.22)[1:]
    p += base()
    p += corner_brackets("rosegold", e=0.34, arm=1.5)
    p += pillow()
    p += ribbons()
    p += bow()
    p += corners()
    p += wall_hearts()
    p += emblem()
    p += lock()
    p += back_hinges()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
