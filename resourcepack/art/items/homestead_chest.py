"""Homestead Chest (issue #1454): the placed crate chest of the Homestead crate, a royal
purple and gold treasure chest.

A rich royal-purple body covered in a damask of tiny fleurs, framed by bright polished gold:
gold bands round the base, the seam and the lid, gold L brackets up every corner and gold
ball feet tucked under the four bottom corners. Every wall carries raised gold panel frames;
the side panels hold a gold rosette set with an amethyst, the front panels a gold
fleur-de-lis. The lid is a reliquary lid: four quilted purple-velvet slopes (gold buttons
in every tuft) rise to a gold-rimmed plateau, with gold hip ribs and knob finials on the
corners. On the plateau sits a tasselled velvet cushion carrying a gold crown: a jewelled
band, eight points tipped with pearls and amethysts, a velvet cap and an orb and cross.
On the front, the royal crest (a purple heater shield with a raised gold fleur-de-lis
inside a gold rim) hangs from the lid and ends in the hasp of a large gold lock plate with
scroll flanges, a pendant and a glowing keyhole. Two gold barrel hinges hold the lid.

Animation (one loop): a glint sweeps round the crown band and the crest rim, the amethysts
twinkle, and the keyhole breathes with a warm violet-gold light.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block like the other crate chests. Deliberately unlike the
earlier barn-themed Homestead crate (homestead_crate, kept for rollback) and unlike the
Harvest (vault) and Stockpile (stepped lid) chests: a sloped reliquary lid under a crown.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM, corner_brackets,  # noqa: F401
                                    keyhole_glow, lock_plate, metal, put, rims, shell)
from art.kit import animate, arc, bar, box, canvas, display, lathe, model, prism, save, save_animation, shine, \
    sparkle, turn, wave

ID = "homestead_chest"
NAME = "Homestead Chest"

PURPLE = ["#12061f", "#1f0b33", "#2e1049", "#401761", "#53207b", "#692c95", "#8441b0", "#a35fcb"]
VELVET = ["#160723", "#250c3a", "#361253", "#4a1a6d", "#5f2588", "#7833a3", "#9448bd"]
GOLD = ["#3a1c04", "#6e3c07", "#a1620c", "#d08d16", "#ecb126", "#fad048", "#ffec8c", "#fffbe0"]
AMETHYST = ["#1d0633", "#3a0c5e", "#5e168f", "#8a2bbf", "#b45ae3", "#d897f5", "#f2d6ff", "#ffffff"]
PEARL = ["#8f7c6c", "#b9a796", "#dacbb9", "#f1e7d8", "#fffaf0"]
GLOW = ["#2a0a3a", "#5a1f62", "#934076", "#c86e6e", "#eaa65a", "#ffd47e", "#fff4cf"]

RISE = 2.4                         # height of the lid's slopes (45 degrees, so also their inset)
PLATEAU = LID_TOP + RISE           # the flat top the cushion and crown stand on
P0, P1 = L0 + RISE, L1 - RISE      # the plateau's x/z extent
CROWN_Y = PLATEAU + 0.85           # top of the cushion = foot of the crown


# ======================================================================================
# Textures
# ======================================================================================

FLEUR = [
    "...#...",
    "..###..",
    ".#.#.#.",
    "#..#..#",
    "#######",
    "...#...",
    "..#.#..",
]


def damask(size: int = 32) -> object:
    """Royal purple lacquer with a calm damask: a soft diagonal lattice and one large fleur
    in every diamond, lighter toward the top."""
    img = canvas(size)
    rng = random.Random(5)
    for y in range(size):
        for x in range(size):
            tone = 4 if y < size * 0.3 else 3
            if rng.random() < 0.03:
                tone -= 1
            if (x - y) % 16 == 0 or (x + y) % 16 == 0:
                tone = 3 if tone == 4 else 2
            put(img, x, y, PURPLE[tone])
    for cx, cy in ((8, 4), (24, 20), (24, 4), (8, 20)):
        for j, row in enumerate(FLEUR):
            for i, ch in enumerate(row):
                if ch == "#":
                    put(img, (cx + i - 3) % size, (cy + j) % size, PURPLE[6] if j < 3 else PURPLE[5])
    return img


def quilted(size: int = 32) -> object:
    """Quilted velvet: big diamond tufts lit from the middle out to soft seams, with a gold
    button where the seams cross."""
    img = canvas(size)
    cell = 16
    for y in range(size):
        for x in range(size):
            a, b = (x + y) % cell, (x - y) % cell
            d = min(a, cell - a, b, cell - b)
            if d == 0:
                c = VELVET[1]
            elif d == 1:
                c = VELVET[2]
            else:
                c = VELVET[4] if d >= 4 else VELVET[3]
                if a < cell / 2 and d >= 3:
                    c = VELVET[5] if d >= 5 else c
            put(img, x, y, c)
    for y in range(0, size, cell):
        for x in range(0, size, cell):
            for px, py in ((x, y), (x + cell // 2, y + cell // 2)):
                put(img, px % size, py % size, GOLD[5])
                put(img, (px + 1) % size, py % size, GOLD[3])
                put(img, px % size, (py + 1) % size, GOLD[3])
    return img


def velvet(size: int = 16) -> object:
    """Plain velvet with a soft sheen."""
    img = canvas(size)
    rng = random.Random(11)
    for y in range(size):
        for x in range(size):
            tone = 4 if y < 5 else 3 if y < 12 else 2
            if (x * 7 + y * 3) % 11 == 0:
                tone += 1
            if rng.random() < 0.08:
                tone -= 1
            put(img, x, y, VELVET[tone])
    return img


def polished(size: int = 16) -> object:
    """Polished gold: bright top rows, warm body, dark bottom edge and a few highlights."""
    img = canvas(size)
    rng = random.Random(3)
    for y in range(size):
        for x in range(size):
            tone = 5 if y == 0 else 4 if y < 6 else 3
            if y >= size - 2:
                tone = 2
            if rng.random() < 0.1:
                tone = max(2, tone - 1)
            put(img, x, y, GOLD[tone])
    for _ in range(4):
        x, y = rng.randrange(1, size - 1), rng.randrange(2, size - 3)
        put(img, x, y, GOLD[7])
    return img


def engraved(size: int = 16) -> object:
    """Gold trim strip with an engraved centre line and dots (for frames and ribs)."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            if x <= 1:
                c = GOLD[5] if x == 0 else GOLD[4]
            elif x >= size - 2:
                c = GOLD[1] if x == size - 1 else GOLD[2]
            elif x in (7, 8):
                c = GOLD[2] if y % 4 else GOLD[1]
            else:
                c = GOLD[3] if (x + y) % 9 else GOLD[4]
            put(img, x, y, c)
    return img


def stud() -> object:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 6)
            tone = 7 if d < 2.5 else 6 if d < 5 else 5 if d < 8 else 4 if d < 11 else 3
            put(img, x, y, GOLD[tone])
    return img


def crest() -> object:
    """The shield's enamel field: deep purple with a fine diaper of lighter diamonds."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 3 if y < 6 else 2
            if (x + y) % 4 == 0 and (x - y) % 4 == 0:
                tone = 4
            put(img, x, y, PURPLE[tone + 1])
    return img


def gold_shine(t: float) -> object:
    """The crown band / crest rim: polished gold with a sweeping glint."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 5 if y < 3 else 4 if y < 8 else 3 if y < 13 else 2
            if (x * 2 + y) % 7 == 0:
                tone -= 1
            put(img, x, y, GOLD[tone])
    return shine(img, t, colour="#fffef0", width=4.0, strength=0.85, pause=0.45)


def amethyst(t: float) -> object:
    """A cut amethyst: dark rim, bright table, a twinkle that comes and goes."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = max(abs(x + 0.5 - 8), abs(y + 0.5 - 8))
            facet = (x < 8) != (y < 8)
            tone = 3 if d > 6 else 4 if d > 4 else 5 if d > 2 else 6
            if facet and d > 2:
                tone -= 1
            if d > 7:
                tone = 2
            put(img, x, y, AMETHYST[tone])
    sparkle(img, 5, 5, wave(t), colour="#ffffff", reach=3)
    sparkle(img, 11, 10, wave(t, 0.5) * 0.7, colour="#f6e6ff", reach=2)
    return img


def pearl() -> object:
    """A pearl: warm body, a bright highlight at the top left and a soft rim."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 7, y + 0.5 - 7)
            put(img, x, y, PEARL[3 if d < 4 else 2 if d < 9 else 1])
    for (x, y) in ((3, 3), (4, 3), (3, 4), (4, 4), (5, 4), (4, 5)):
        put(img, x, y, PEARL[4])
    put(img, 3, 3, "#ffffff")
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(GLOW, t)


def textures() -> None:
    save(damask(), "damask")
    save(quilted(), "quilted")
    save(velvet(), "velvet")
    save(polished(), "gold")
    save(engraved(), "trim")
    save(stud(), "stud")
    save(crest(), "crest")
    save(metal(GOLD[:7], rivets=(3, 12)), "band")
    save(lock_plate(GOLD[:7], hole=PURPLE[0]), "plate")
    save(pearl(), "pearl")
    save_animation(animate(gold_shine, 16), "shine", frametime=3)
    save_animation(animate(amethyst, 12), "amethyst", frametime=3, interpolate=True)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def gold_frame(face: str, a0: float, a1: float, y0: float, y1: float, t: float = 0.5, proud: float = 0.28,
               tex: str = "trim") -> list[dict]:
    """A raised gold rectangle on one wall. a0..a1 runs along the wall (x on front/back, z on
    the sides)."""
    parts = []
    if face in ("front", "back"):
        z0, z1 = (B1, B1 + proud) if face == "front" else (B0 - proud, B0)
        parts.append(box((a0, y1 - t, z0), (a1, y1, z1), tex))
        parts.append(box((a0, y0, z0), (a1, y0 + t, z1), tex))
        parts.append(box((a0, y0 + t, z0), (a0 + t, y1 - t, z1), tex))
        parts.append(box((a1 - t, y0 + t, z0), (a1, y1 - t, z1), tex))
    else:
        x0, x1 = (B1, B1 + proud) if face == "east" else (B0 - proud, B0)
        parts.append(box((x0, y1 - t, a0), (x1, y1, a1), tex))
        parts.append(box((x0, y0, a0), (x1, y0 + t, a1), tex))
        parts.append(box((x0, y0 + t, a0), (x1, y1 - t, a0 + t), tex))
        parts.append(box((x0, y0 + t, a1 - t), (x1, y1 - t, a1), tex))
    return parts


def fleur(cx: float, cy: float, z: float, s: float = 1.0, tex: str = "gold", depth: float = 0.2) -> list[dict]:
    """A raised fleur-de-lis facing +Z: tall pointed centre petal, two curled side petals,
    a binding band and a short split foot. s scales it (1.0 is about 2.6 units tall)."""
    parts = [
        bar((cx, cy - 0.2 * s, z), (cx, cy + 1.25 * s, z), 0.62 * s, depth, tex),
        box((cx - 0.3 * s, cy + 1.05 * s, z - depth / 2), (cx + 0.3 * s, cy + 1.65 * s, z + depth / 2), tex),
        box((cx - 0.85 * s, cy - 0.32 * s, z - depth / 2 + 0.02), (cx + 0.85 * s, cy + 0.06 * s, z + depth / 2 + 0.04),
            tex),
    ]
    tip = box((cx - 0.3 * s, cy + 1.4 * s, z - depth / 2), (cx + 0.3 * s, cy + 2.0 * s, z + depth / 2), tex)
    turn(tip, 45, "z", (cx, cy + 1.7 * s, z))
    parts.append(tip)
    for side in (-1, 1):
        parts += arc((cx + side * 0.95 * s, cy + 0.45 * s, z), 0.62 * s, 200 if side > 0 else -20,
                     20 if side > 0 else 160, 4, 0.34 * s, depth, tex, plane="xy")
        parts.append(bar((cx + side * 0.2 * s, cy - 0.2 * s, z), (cx + side * 0.75 * s, cy - 0.95 * s, z),
                         0.32 * s, depth, tex))
    return parts


def panels() -> list[dict]:
    """Raised gold frames on every wall, a fleur in each front panel and a jewelled rosette
    on each side."""
    parts = []
    y0, y1 = 1.55, SEAM - 1.5
    for a0, a1 in ((1.7, 5.1), (10.9, 14.3)):
        parts += gold_frame("front", a0, a1, y0, y1)
        parts += fleur((a0 + a1) / 2, 3.1, B1 + 0.14, s=1.0)
    for face, x, out in (("east", B1, 1), ("west", B0, -1)):
        parts += gold_frame(face, 2.1, 13.9, y0, y1)
        xr = x + 0.15 * out
        parts += prism((xr, (y0 + y1) / 2, 8.0), 1.35, 0.3, "gold", axis="x", sides=8, cap="stud")
        parts += arc((xr + 0.1 * out, (y0 + y1) / 2, 8.0), 1.55, 22.5, 382.5, 8, 0.36, 0.32, "trim", plane="zy")
        parts += prism((xr + 0.24 * out, (y0 + y1) / 2, 8.0), 0.8, 0.22, "amethyst", axis="x", sides=8,
                       cap="amethyst", glow=5, uv="full")
        # petals of the rosette reaching to the frame
        for dz in (-1, 1):
            parts.append(bar((xr, (y0 + y1) / 2, 8.0 + dz * 1.55), (xr, (y0 + y1) / 2, 8.0 + dz * 5.1), 0.24, 0.32,
                             "trim"))
    return parts


def plinth() -> list[dict]:
    """A layered base: a proud gold plinth with a purple step above it and heavy gold corner
    blocks, each with a stud."""
    parts = []
    e = 0.4
    parts.append(box((B0 - e, 0.0, B0 - e), (B1 + e, 1.2, B1 + e), "band", skip=("down",)))
    parts.append(box((B0 - 0.2, 1.2, B0 - 0.2), (B1 + 0.2, 1.55, B1 + 0.2), "trim", skip=("down",)))
    for cx in (B0, B1):
        for cz in (B0, B1):
            sx = 1 if cx == B0 else -1
            sz = 1 if cz == B0 else -1
            x0, x1 = cx - 0.45 * sx, cx + 1.7 * sx
            z0, z1 = cz - 0.45 * sz, cz + 1.7 * sz
            parts.append(box((x0, 0.0, z0), (x1, 2.0, z1), "gold", skip=("down",)))
            parts.append(box((cx + 0.35 * sx, 0.6, cz - 0.5 * sz), (cx + 1.05 * sx, 1.3, cz - 0.45 * sz), "stud",
                             uv="full"))
            parts.append(box((cx - 0.5 * sx, 0.6, cz + 0.35 * sz), (cx - 0.45 * sx, 1.3, cz + 1.05 * sz), "stud",
                             uv="full"))
    return parts


def slopes() -> list[dict]:
    """The reliquary lid: a stepped core under four quilted velvet slopes (each three strips,
    so the slopes taper honestly), gold hip ribs, finials and the plateau."""
    parts = []
    steps = 4
    for k in range(steps):
        inset = RISE * (k + 1) / steps + 0.25
        y0 = LID_TOP - 0.02 + RISE * k / steps
        y1 = LID_TOP + RISE * (k + 1) / steps
        parts.append(box((L0 + inset, y0, L0 + inset), (L1 - inset, y1, L1 - inset), "velvet", skip=("down",)))
    edge = L1 - L0
    strips = 3
    depth = 0.42
    n = 1 / math.sqrt(2)
    for k in range(strips):
        h0, h1 = RISE * k / strips, RISE * (k + 1) / strips
        span = edge - (h0 + h1) + 0.35
        off = (0, 5 * k)
        # front (+z) and back (-z): the strip's centre line sits depth/2 under the slope
        for sgn in (1, -1):
            zc = 8.0 + sgn * (edge / 2)
            p0 = (8.0, LID_TOP + h0 - depth / 2 * n, zc - sgn * (h0 + depth / 2 * n))
            p1 = (8.0, LID_TOP + h1 - depth / 2 * n, zc - sgn * (h1 + depth / 2 * n))
            parts.append(bar(p0, p1, span, depth, "quilted", offset=off))
        for sgn in (1, -1):
            xc = 8.0 + sgn * (edge / 2)
            p0 = (xc - sgn * (h0 + depth / 2 * n), LID_TOP + h0 - depth / 2 * n, 8.0)
            p1 = (xc - sgn * (h1 + depth / 2 * n), LID_TOP + h1 - depth / 2 * n, 8.0)
            parts.append(bar(p0, p1, depth, span, "quilted", offset=off))
    # hip ribs and finials
    for cx in (L0, L1):
        for cz in (L0, L1):
            sx = 1 if cx == L0 else -1
            sz = 1 if cz == L0 else -1
            a = (cx + 0.05 * sx, LID_TOP - 0.1, cz + 0.05 * sz)
            b = (cx + RISE * sx, PLATEAU + 0.05, cz + RISE * sz)
            parts.append(bar(a, b, 0.75, 0.75, "trim", roll=45))
            parts += prism((b[0], PLATEAU + 0.3, b[2]), 0.5, 0.6, "gold", sides=8, cap="stud")
            parts.append(box((b[0] - 0.22, PLATEAU + 0.6, b[2] - 0.22), (b[0] + 0.22, PLATEAU + 1.05, b[2] + 0.22),
                             "stud", uv="full"))
    # the plateau: a gold-rimmed velvet top
    parts.append(box((P0 - 0.1, PLATEAU - 0.35, P0 - 0.1), (P1 + 0.1, PLATEAU + 0.05, P1 + 0.1), "trim",
                     faces={"up": "velvet"}))
    # a gold stud in the middle of every slope
    for (x, z, ry) in ((8.0, L1 - RISE / 2, 0), (8.0, L0 + RISE / 2, 180), (L1 - RISE / 2, 8.0, 90),
                       (L0 + RISE / 2, 8.0, 270)):
        s = box((x - 0.5, LID_TOP + RISE / 2 - 0.5, z - 0.2), (x + 0.5, LID_TOP + RISE / 2 + 0.5, z + 0.25), "stud",
                uv="full")
        turn(s, 45, "x", (x, LID_TOP + RISE / 2, z))
        turn(s, ry, "y", (x, LID_TOP + RISE / 2, z))
        parts.append(s)
    return parts


def cushion() -> list[dict]:
    """A velvet cushion with gold piping and four tassels, the crown's seat."""
    lo, hi = 4.3, 11.7
    y0, y1 = PLATEAU, CROWN_Y
    parts = [box((lo, y0, lo), (hi, y1, hi), "velvet", skip=("down",))]
    e = 0.12
    parts.append(box((lo - e, y1 - 0.22, lo - e), (hi + e, y1 + 0.02, hi + e), "trim", skip=("up", "down")))
    parts.append(box((lo - e, y0 + 0.02, lo - e), (hi + e, y0 + 0.24, hi + e), "trim", skip=("up", "down")))
    for cx in (lo, hi):
        for cz in (lo, hi):
            parts.append(box((cx - 0.25, y0 - 0.05, cz - 0.25), (cx + 0.25, y1 + 0.05, cz + 0.25), "gold"))
            dx = -0.55 if cx == lo else 0.55
            dz = -0.55 if cz == lo else 0.55
            parts.append(bar((cx, y0 + 0.5, cz), (cx + dx, y0 + 0.05, cz + dz), 0.32, 0.32, "trim"))
    return parts


def crown() -> list[dict]:
    """The gold crown on the cushion: a jewelled band, eight pearl- and amethyst-tipped
    points, a velvet cap, an orb and a cross."""
    cx, cz, y0 = 8.0, 8.0, CROWN_Y
    parts = []
    band_h, r = 1.6, 2.85
    parts += prism((cx, y0 + band_h / 2, cz), r, band_h, "shine", sides=8, cap="gold")
    # rings at the band's top and bottom
    parts += prism((cx, y0 + 0.15, cz), r + 0.2, 0.3, "gold", sides=8, cap="gold")
    parts += prism((cx, y0 + band_h - 0.12, cz), r + 0.14, 0.24, "gold", sides=8, cap="gold")
    # the cap
    parts += lathe((cx, y0 + band_h - 0.1, cz), [(0.0, 2.55), (0.9, 2.2), (1.6, 1.4), (2.1, 0.0)], "velvet",
                   sides=8, cap="velvet")
    # the points: tall ones on the axes and diagonals alternate with short ones
    for i in range(8):
        a = math.radians(i * 45)
        tall = i % 2 == 0
        rx, rz = math.sin(a), math.cos(a)
        base = (cx + rx * (r - 0.2), y0 + band_h - 0.2, cz + rz * (r - 0.2))
        h = 2.5 if tall else 1.5
        tip = (cx + rx * (r + 0.3), base[1] + h, cz + rz * (r + 0.3))
        parts.append(bar(base, tip, 0.62 if tall else 0.5, 0.36, "gold", roll=-math.degrees(a)))
        if tall:
            parts.append(box((tip[0] - 0.36, tip[1] - 0.05, tip[2] - 0.36), (tip[0] + 0.36, tip[1] + 0.66, tip[2] + 0.36),
                             "pearl", uv="full"))
        else:
            gem = box((tip[0] - 0.32, tip[1] - 0.1, tip[2] - 0.32), (tip[0] + 0.32, tip[1] + 0.54, tip[2] + 0.32),
                      "amethyst", uv="full", glow=6)
            turn(gem, 45, "y", (tip[0], tip[1], tip[2]))
            parts.append(gem)
    # a big jewel under every tall point
    for i in range(4):
        a = math.radians(i * 90)
        rx, rz = math.sin(a), math.cos(a)
        c = (cx + rx * (r + 0.02), y0 + band_h / 2, cz + rz * (r + 0.02))
        gem = box((c[0] - 0.5, c[1] - 0.5, c[2] - 0.14), (c[0] + 0.5, c[1] + 0.5, c[2] + 0.14), "amethyst",
                  uv="full", glow=6)
        turn(gem, 45, "z", c)
        turn(gem, math.degrees(a), "y", c)
        parts.append(gem)
    # orb and cross on the cap
    top = y0 + band_h - 0.1 + 2.1
    parts += lathe((cx, top - 0.15, cz), [(0.0, 0.35), (0.45, 0.68), (0.95, 0.35)], "shine", sides=8, cap="gold")
    parts.append(box((cx - 0.18, top + 0.75, cz - 0.18), (cx + 0.18, top + 2.0, cz + 0.18), "gold"))
    parts.append(box((cx - 0.6, top + 1.3, cz - 0.18), (cx + 0.6, top + 1.65, cz + 0.18), "gold"))
    return parts


def crest_and_lock() -> list[dict]:
    """The large gold lock plate across the seam and the royal crest that hangs from the lid
    into it as the hasp."""
    parts = []
    # --- the lock plate (covers the vanilla latch: x 7..9, y 7..11, z to 16)
    z0, z1 = B1 - 0.05, 16.02
    parts.append(box((5.0, 5.0, z0), (11.0, 10.1, z1), "plate",
                     uv={"north": [0, 0, 4, 4], "east": [0, 0, 1, 4], "west": [0, 0, 1, 4], "up": [0, 0, 4, 1],
                         "down": [0, 0, 4, 1], "south": [0, 0, 16, 16]}))
    # bevelled gold border standing proud of the plate
    for (a, b) in (((4.8, 9.85), (11.2, 10.35)), ((4.8, 4.8), (11.2, 5.3))):
        parts.append(box((a[0], a[1], z1 - 0.1), (b[0], b[1], z1 + 0.12), "trim"))
    for x in (4.8, 10.7):
        parts.append(box((x, 5.3, z1 - 0.1), (x + 0.5, 9.85, z1 + 0.12), "trim"))
    # scroll flanges either side: an outward curl at the top, an inward curl below
    for side in (-1, 1):
        cx = 8.0 + side * 4.0
        out0, out1 = (-90, 90) if side > 0 else (90, 270)
        parts += arc((cx, 8.8, z1 - 0.1), 1.1, out0, out1, 4, 0.5, 0.24, "gold", plane="xy")
        parts += arc((cx, 6.15, z1 - 0.1), 0.85, out1, out1 + 180, 4, 0.44, 0.24, "gold", plane="xy")
        parts.append(box((cx - 0.25, 7.35, z1 - 0.12), (cx + 0.25, 7.85, z1 + 0.06), "stud", uv="full"))
    # the keyhole: a big lit escutcheon slot
    zk = z1 + 0.0
    parts.append(box((7.45, 7.55, zk - 0.03), (8.55, 8.65, zk + 0.03), "keyhole", glow=11, shade=False, uv="full"))
    parts.append(box((7.7, 6.0, zk - 0.03), (8.3, 7.7, zk + 0.03), "keyhole", glow=11, shade=False, uv="full"))
    parts += arc((8.0, 7.55, zk + 0.04), 1.05, -60, 240, 5, 0.26, 0.12, "gold", plane="xy")
    # a pendant drop under the plate
    drop = box((7.55, 4.0, z1 - 0.2), (8.45, 4.9, z1 + 0.04), "gold")
    turn(drop, 45, "z", (8.0, 4.45, z1))
    parts.append(drop)
    parts.append(box((7.75, 4.25, z1 + 0.04), (8.25, 4.75, z1 + 0.12), "amethyst", uv="full", glow=6))
    # --- the crest: a heater shield hanging from the lid, its point is the hasp
    zs = L1
    parts.append(box((6.05, 11.55, zs - 0.05), (9.95, 14.75, zs + 0.38), "crest",
                     faces={"south": ("crest", [0, 0, 16, 13])}))
    point = box((6.68, 9.18, zs - 0.05), (9.32, 11.82, zs + 0.34), "crest", faces={"south": ("crest", [2, 2, 14, 14])})
    turn(point, 45, "z", (8.0, 10.5, zs))
    parts.append(point)
    # the shield's gold rim (shines)
    zr = zs + 0.38
    parts.append(box((5.8, 14.6, zs - 0.08), (10.2, 15.15, zr + 0.14), "shine"))
    for side in (-1, 1):
        x = 8.0 + side * 2.08
        parts.append(box((x - 0.26, 11.45, zs - 0.08), (x + 0.26, 14.65, zr + 0.14), "shine"))
        parts.append(bar((x, 11.6, zr + 0.03), (8.0 + side * 0.05, 9.35, zr + 0.03), 0.48, 0.26, "shine"))
    # the hasp tongue under the point, riveted to the lock plate
    parts.append(box((7.55, 9.1, z1), (8.45, 9.9, zr + 0.06), "gold"))
    parts.append(box((7.75, 9.3, zr + 0.06), (8.25, 9.75, zr + 0.14), "stud", uv="full"))
    # the raised gold fleur-de-lis on the shield
    parts += fleur(8.0, 12.25, zr + 0.06, s=0.95, depth=0.18)
    # little gold wings either side of the crest's top
    for side in (-1, 1):
        parts += arc((8.0 + side * 2.7, 14.4, zs + 0.1), 0.75, 90 if side > 0 else -90, 270 if side > 0 else 90, 4,
                     0.3, 0.22, "gold", plane="xy")
    return parts


def back_hinges() -> list[dict]:
    parts = []
    for x in (4.0, 12.0):
        parts += prism((x, SEAM, L0 + 0.1), 0.55, 2.4, "gold", axis="x", sides=8, cap="stud")
        parts += prism((x, SEAM, L0 + 0.1), 0.24, 2.9, "stud", axis="x", sides=8, cap="stud")
        parts.append(box((x - 0.9, SEAM - 2.6, B0 - 0.26), (x + 0.9, SEAM - 0.35, B0), "trim"))
        parts.append(box((x - 0.9, SEAM + 0.35, L0 - 0.26), (x + 0.9, SEAM + 3.1, L0), "trim"))
        for y in (SEAM - 2.0, SEAM + 2.4):
            z = (B0 if y < SEAM else L0) - 0.38
            parts.append(box((x - 0.3, y - 0.3, z), (x + 0.3, y + 0.3, z + 0.14), "stud", uv="full"))
    return parts


def models() -> dict:
    p = shell("damask", "damask", "velvet")
    p += rims("band", e=0.26)[1:]
    p += plinth()
    p += corner_brackets("trim", e=0.4, arm=2.0)
    p += panels()
    p += slopes()
    p += cushion()
    p += crown()
    p += crest_and_lock()
    p += back_hinges()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
