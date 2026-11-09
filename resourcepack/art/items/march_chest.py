"""March Chest (issue #1464, design in #1407): the monthly crate chest for March, a lucky
spring treasure chest in green, mint and gold.

A body of fresh green painted boards (vertical on the body, across on the lid) with mint
trim, bound in lucky gold: gold bands round the base, the seam and the lid, gold brackets
up every corner and mint panel frames on every wall. Each side wall carries a gold
horseshoe hung open end up for luck, with its nail holes and a gold coin under it; the
front panels each hold a small gold three-leaf shamrock. Green ivy climbs the front corners
in leafy runs. The lid is grown over with spring turf: a mossy mat with bumps, grass tufts
and little white and yellow spring flowers, a stack of gold coins on two corners, and a
large four-leaf clover standing on a gold stem in the middle, its heart-shaped leaves
dotted with dew.

The emblem is a lucky medallion on the lid's front: a mint enamel disc ringed in gold with
a raised four-leaf clover; a gold hasp drops from it onto a gold lock plate with a green
keyhole. Spring rain hangs in drops from the lid's edge all round. Two gold hinges hold
the lid at the back.

Animation (one loop): dew glints wander over the clover leaves, light runs down the
raindrops, the gold coins and medallion ring catch a glint, and the keyhole glows green.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block like the other crate chests. Deliberately unlike the
other crate chests: a tall clover standing out of a grassy lid instead of a crown, spire,
bow or roof.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM, corner_brackets,  # noqa: F401
                                    keyhole_glow, lock_plate, metal, planks, put, rims, shell)
from art.kit import animate, arc, bar, box, canvas, display, model, move, prism, save, save_animation, shine, \
    sparkle, turn, wave

ID = "march_chest"
NAME = "March Chest"

GREEN = ["#1d4a32", "#286545", "#35805a", "#449a6c", "#56b27e", "#6fc792", "#93dcae", "#bfeccc"]
MINT = ["#1c4a3d", "#2a6a57", "#3e8d75", "#5aad93", "#7fcbb0", "#a9e3cb", "#d6f6e8"]
GOLD = ["#3d2203", "#6f4407", "#a3700e", "#d09f1c", "#eec033", "#fbdc5c", "#fff19c", "#fffbe2"]
CLOVER = ["#082a0d", "#0e4317", "#165f21", "#1f7e2c", "#2e9e3b", "#4dbd52", "#83db7a", "#c4f2b4"]
MOSS = ["#13300f", "#1c4516", "#275c1d", "#347426", "#438c30", "#58a33f", "#74bb55"]
RAIN = ["#183a5a", "#2b5f88", "#4b8ab6", "#7cb7dc", "#b5dcf3", "#e8f7ff", "#ffffff"]
GLOW = ["#06200c", "#0d3d18", "#1a6a2a", "#2f9a42", "#5fc767", "#a6eda0", "#effff0"]

TURF = LID_TOP + 0.55              # top of the moss mat on the lid
CLOVER_C = (8.0, LID_TOP + 5.6, 8.0)   # centre of the standing four-leaf clover


# ======================================================================================
# Textures
# ======================================================================================

def moss(size: int = 16) -> object:
    """Spring turf: a mossy mat with lighter tufts and darker hollows."""
    img = canvas(size)
    rng = random.Random(31)
    for y in range(size):
        for x in range(size):
            r = rng.random()
            tone = 4 if r > 0.45 else 3 if r > 0.15 else 5
            if (x * 3 + y * 7) % 19 == 0:
                tone = 6
            put(img, x, y, MOSS[tone])
    return img


def turf_side(size: int = 16) -> object:
    """The moss mat seen from the side: a grassy fringe hanging over a dark root line."""
    img = canvas(size)
    rng = random.Random(33)
    for y in range(size):
        for x in range(size):
            fringe = 6 + (3 if (x * 5) % 7 < 3 else 0) + rng.randint(0, 1)
            if y < fringe:
                tone = 5 if y < 3 else 4
            else:
                tone = 2
            put(img, x, y, MOSS[tone])
    return img


def blades(size: int = 16) -> object:
    """A grass tuft: blades of different heights on a clear background."""
    img = canvas(size)
    rng = random.Random(35)
    for x in range(1, size - 1):
        h = rng.randint(6, 15)
        lean = rng.choice((-1, 0, 0, 1))
        for i in range(h):
            yy = size - 1 - i
            xx = x + (lean if i > h * 0.6 else 0)
            put(img, xx, yy, MOSS[6] if i > h - 3 else MOSS[5] if i > h // 2 else MOSS[4])
    return img


def flower(size: int = 16, centre="#f7d43a", petal="#ffffff", shadow="#cfd8e6") -> object:
    img = canvas(size, fill=petal)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if d < 3.2:
                put(img, x, y, centre)
            elif (x + y) % 5 == 0 or d > 7:
                put(img, x, y, shadow)
    return img


def gold(size: int = 16) -> object:
    img = canvas(size)
    rng = random.Random(6)
    for y in range(size):
        for x in range(size):
            tone = 5 if y == 0 else 4 if y < 6 else 3
            if y >= size - 2:
                tone = 2
            if rng.random() < 0.1:
                tone = max(2, tone - 1)
            put(img, x, y, GOLD[tone])
    for _ in range(4):
        put(img, rng.randrange(1, size - 1), rng.randrange(1, 6), GOLD[7])
    return img


def mint_trim(size: int = 16) -> object:
    """Mint painted moulding with a lit edge and a fine groove."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            if x <= 1:
                c = MINT[6] if x == 0 else MINT[5]
            elif x >= size - 2:
                c = MINT[1] if x == size - 1 else MINT[2]
            elif x in (7, 8):
                c = MINT[3]
            else:
                c = MINT[4] if (x + y) % 9 else MINT[5]
            put(img, x, y, c)
    return img


def enamel(size: int = 16) -> object:
    """The medallion's mint enamel, lighter in the middle."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            tone = 5 if d < 3 else 4 if d < 6 else 3
            if (x * 2 + y) % 11 == 0:
                tone += 1
            put(img, x, y, MINT[min(6, tone)])
    return img


def stem(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 5 if x < 5 else 4 if x < 11 else 3
            if y % 6 == 0:
                tone -= 1
            put(img, x, y, CLOVER[tone])
    return img


def ivy(size: int = 16) -> object:
    """An ivy leaf: dark green with a lighter vein."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 4 if x + y < 16 else 3
            if x == y or x + y == 15:
                tone = 5
            put(img, x, y, CLOVER[tone])
    return img


def coin(t: float) -> object:
    """A gold coin face with a shamrock stamp and a passing glint."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            tone = 3 if d > 7.4 else 2 if d > 6.4 else 5 if d < 3 else 4
            put(img, x, y, GOLD[tone])
    for (x, y) in ((7, 5), (8, 5), (5, 7), (5, 8), (10, 7), (10, 8), (7, 10), (8, 10), (7, 7), (8, 8), (7, 8), (8, 7)):
        put(img, x, y, GOLD[2])
    return shine(img, t, colour="#fffbe2", width=3.0, strength=0.8, pause=0.5)


def leaf(t: float) -> object:
    """A clover leaf: bright green with a pale chevron band and dew drops that glint."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 5 if y < 7 else 4
            if abs(abs(x + 0.5 - 8) - (y * 0.45)) < 0.8 and 3 < y < 12:
                tone = 7
            elif x in (0, 15) or y in (0, 15):
                tone -= 1
            put(img, x, y, CLOVER[tone])
    for (x, y, off) in ((4, 4, 0.0), (11, 9, 0.4), (6, 12, 0.7)):
        put(img, x, y, RAIN[4])
        put(img, x + 1, y + 1, RAIN[2])
        sparkle(img, x, y, wave(t, off), colour="#ffffff", reach=2)
    return img


def drop(t: float) -> object:
    """A raindrop: clear blue, a white highlight and light running down it."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 4 if x < 6 else 3 if x < 12 else 2
            put(img, x, y, RAIN[tone])
    for (x, y) in ((3, 3), (4, 3), (3, 4), (4, 4)):
        put(img, x, y, RAIN[6])
    p = (t * 20) % 20 - 2
    for y in range(16):
        if abs(y - p) < 1.5:
            for x in range(1, 7):
                put(img, x, y, RAIN[4])
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(GLOW, t)


def textures() -> None:
    save(planks(GREEN, size=32, rows=4, seed=17, vertical=True, knots=1, seam_dark=2), "boards")
    save(planks(MINT, size=32, rows=3, seed=19, vertical=False, knots=0, seam_dark=1), "lidboards")
    save(moss(), "moss")
    save(turf_side(), "turf")
    save(blades(), "blades")
    save(flower(), "daisy")
    save(flower(centre="#f6a019", petal="#fbe44a", shadow="#e2c02c"), "buttercup")
    save(gold(), "gold")
    save(mint_trim(), "mint")
    save(enamel(), "enamel")
    save(stem(), "stem")
    save(ivy(), "ivy")
    save(metal(GOLD[:7], rivets=(3, 12)), "band")
    save(lock_plate(GOLD[:7], hole=GREEN[0]), "plate")
    save_animation(animate(coin, 16), "coin", frametime=3)
    save_animation(animate(leaf, 12), "leaf", frametime=3, interpolate=True)
    save_animation(animate(drop, 10), "drop", frametime=3)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def heart_leaf(s: float, depth: float, tex: str, cheap: bool = False, **kw) -> list[dict]:
    """One clover leaf: a heart whose point sits at the origin and whose lobes reach up +Y,
    in the xy plane. cheap = square lobes (3 boxes) for the small clovers."""
    cy = 0.95 * s
    r = s * 0.53
    if cheap:
        parts = [box((sx * s * 0.47 - r * 0.9, cy + s * 0.25 - r * 0.9, -depth / 2),
                     (sx * s * 0.47 + r * 0.9, cy + s * 0.25 + r * 0.9, depth / 2), tex, uv="full", **kw)
                 for sx in (-1, 1)]
    else:
        parts = prism((-s * 0.47, cy + s * 0.25, 0.0), r, depth, tex, axis="z", sides=8, cap=tex, uv="full", **kw)
        parts += prism((s * 0.47, cy + s * 0.25, 0.0), r, depth, tex, axis="z", sides=8, cap=tex, uv="full", **kw)
    d = s * 0.66
    tip = box((-d, cy - d, -depth / 2), (d, cy + d, depth / 2), tex, uv="full", **kw)
    turn(tip, 45, "z", (0.0, cy - s * 0.06, 0.0))
    parts.append(tip)
    return parts


def clover(c, s: float, depth: float, tex: str, leaves: int = 4, start: float = 45.0, cheap: bool = False,
           **kw) -> list[dict]:
    """A clover facing +Z centred on c: `leaves` heart leaves pointing at the middle."""
    parts = []
    for i in range(leaves):
        lf = heart_leaf(s, depth, tex, cheap=cheap, **kw)
        turn(lf, start + i * 360.0 / leaves, "z", (0.0, 0.0, 0.0))
        parts += move(lf, c[0], c[1], c[2])
    return parts


def standing_clover() -> list[dict]:
    """The big four-leaf clover on a gold-ringed stem, standing on the turf and leaning back
    a little."""
    cx, cy, cz = CLOVER_C
    parts = [bar((cx, TURF - 0.2, cz - 0.3), (cx, cy - 0.5, cz), 0.8, 0.8, "stem")]
    parts += prism((cx, TURF + 0.6, cz - 0.27), 0.55, 0.35, "gold", sides=8, cap="gold")
    leaves = clover((cx, cy, cz), 2.1, 0.8, "leaf", glow=3)
    leaves += prism((cx, cy, cz), 0.7, 1.0, "gold", axis="z", sides=8, cap="coin")
    turn(leaves, 8, "x", (cx, cy, cz))
    turn(leaves, 20, "y", (cx, cy, cz))
    parts += leaves
    return parts


def turf() -> list[dict]:
    """The moss mat on the lid, bumps, grass tufts and spring flowers."""
    parts = []
    e = 0.12
    parts.append(box((L0 - e, LID_TOP, L0 - e), (L1 + e, TURF, L1 + e), "turf", faces={"up": "moss"},
                     skip=("down",)))
    for (x0, z0, x1, z1, h) in ((3.0, 2.6, 8.6, 7.0, 0.4), (8.4, 8.8, 13.2, 13.0, 0.35), (2.4, 9.4, 6.2, 12.6, 0.3),
                                (9.8, 2.8, 13.0, 6.0, 0.45), (5.4, 6.0, 10.8, 10.6, 0.6)):
        parts.append(box((x0, TURF - 0.02, z0), (x1, TURF + h, z1), "turf", faces={"up": "moss"}, skip=("down",)))
    rng = random.Random(41)
    for (x, z) in ((2.6, 4.2), (5.0, 12.8), (11.6, 11.4), (13.2, 7.4), (3.6, 7.6)):
        h = rng.uniform(0.9, 1.4)
        for ang in (45, -45):
            t = box((x - 0.7, TURF, z), (x + 0.7, TURF + h, z), "blades", uv="full", skip=("up", "down", "east", "west"))
            turn(t, ang, "y", (x, TURF, z))
            parts.append(t)
    for (x, z, tex) in ((4.4, 10.6, "daisy"), (11.4, 9.4, "buttercup"), (6.2, 4.6, "buttercup"), (12.0, 3.0, "daisy"),
                        (3.0, 13.2, "daisy"), (9.6, 12.6, "daisy"), (2.4, 6.2, "buttercup"), (13.4, 10.6, "daisy")):
        parts.append(bar((x, TURF, z), (x, TURF + 0.7, z), 0.12, 0.12, "stem"))
        parts.append(box((x - 0.36, TURF + 0.68, z - 0.36), (x + 0.36, TURF + 0.88, z + 0.36), tex, uv="full"))
    return parts


def coin_stacks() -> list[dict]:
    """Gold coins stacked on two back corners of the lid and one tipped against them."""
    parts = []
    for (x, z, n) in ((L0 + 1.4, L0 + 1.5, 4), (L1 - 1.4, L0 + 1.5, 3)):
        for k in range(n):
            dx = 0.12 * ((k * 3) % 3 - 1)
            parts += prism((x + dx, TURF + 0.12 + k * 0.24, z), 1.05, 0.22, "gold", sides=8, cap="coin")
    for x in (L0 + 2.6, L1 - 2.6):
        c = prism((x, TURF + 0.95, L0 + 1.45), 0.95, 0.2, "gold", axis="z", sides=8, cap="coin")
        turn(c, -22, "x", (x, TURF, L0 + 1.45))
        parts += c
    return parts


def ivy_run(x: float, z: float, out: float) -> list[dict]:
    """Ivy climbing a front corner: a zig-zag vine with leaves either side."""
    parts = []
    pts = [(x, 0.4), (x + 0.5 * out, 2.4), (x - 0.1 * out, 4.6), (x + 0.45 * out, 6.6), (x, 8.6), (x + 0.4 * out, 10.8)]
    for (ax, ay), (bx, by) in zip(pts, pts[1:]):
        parts.append(bar((ax, ay, z), (bx, by, z), 0.22, 0.2, "stem"))
    for k, (px, py) in enumerate(pts[1:]):
        side = 1 if k % 2 else -1
        lf = box((px - 0.5, py - 0.5, z - 0.07), (px + 0.5, py + 0.5, z + 0.07), "ivy", uv="full")
        turn(lf, 45, "z", (px, py, z))
        parts.append(move(lf, side * 0.45, 0.1, 0.02))
    return parts


def raindrops() -> list[dict]:
    """Spring rain hanging in drops from the lid's edge."""
    parts = []
    y = SEAM - 0.75

    def drip(x, z, ln):
        d = [box((x - 0.16, y - ln - 0.35, z - 0.16), (x + 0.16, y - ln + 0.1, z + 0.16), "drop", uv="full", glow=4),
             box((x - 0.32, y - ln - 1.0, z - 0.32), (x + 0.32, y - ln - 0.3, z + 0.32), "drop", uv="full", glow=4)]
        turn(d, 45, "y", (x, y, z))
        return d

    for x, ln in ((2.0, 0.5), (3.9, 1.5), (12.4, 1.1)):
        parts += drip(x, L1 + 0.05, ln)
    for x, ln in ((4.0, 0.8), (11.6, 1.3)):
        parts += drip(x, L0 - 0.05, ln)
    for zz, ln in ((4.2, 0.5), (11.4, 1.4)):
        parts += drip(L1 + 0.05, zz, ln)
        parts += drip(L0 - 0.05, 16.0 - zz, ln * 1.2)
    return parts


def walls() -> list[dict]:
    """Mint panel frames, shamrocks on the front, horseshoes on the sides."""
    parts = []
    y0, y1 = 1.6, SEAM - 1.45
    t = 0.45

    def frame(face, a0, a1):
        if face == "front":
            z0, z1 = B1, B1 + 0.26
            return [box((a0, y1 - t, z0), (a1, y1, z1), "mint"), box((a0, y0, z0), (a1, y0 + t, z1), "mint"),
                    box((a0, y0 + t, z0), (a0 + t, y1 - t, z1), "mint"), box((a1 - t, y0 + t, z0), (a1, y1 - t, z1), "mint")]
        x0, x1 = (B1, B1 + 0.26) if face == "east" else (B0 - 0.26, B0)
        return [box((x0, y1 - t, a0), (x1, y1, a1), "mint"), box((x0, y0, a0), (x1, y0 + t, a1), "mint"),
                box((x0, y0 + t, a0), (x1, y1 - t, a0 + t), "mint"), box((x0, y0 + t, a1 - t), (x1, y1 - t, a1), "mint")]

    for a0, a1 in ((1.8, 5.6), (10.4, 14.2)):
        parts += frame("front", a0, a1)
        cx, cy = (a0 + a1) / 2, (y0 + y1) / 2 + 0.2
        parts += clover((cx, cy, B1 + 0.2), 0.66, 0.16, "gold", leaves=3, start=0.0, cheap=True)
        parts.append(bar((cx, cy, B1 + 0.2), (cx + 0.5, cy - 1.6, B1 + 0.2), 0.2, 0.16, "gold"))
    for face, x, out in (("east", B1, 1), ("west", B0, -1)):
        parts += frame(face, 2.0, 14.0)
        yc = (y0 + y1) / 2 + 0.4
        xs = x + 0.2 * out
        # a horseshoe hung open end up: a U of bars with nail holes, and calkins at the ends
        shoe = arc((xs, yc, 8.0), 1.75, 125, 415, 9, 0.62, 0.3, "gold", plane="zy")
        parts += shoe
        for dz in (-1, 1):
            a = math.radians(125 if dz < 0 else 55)
            pz = 8.0 + math.cos(a) * 1.75
            py = yc + math.sin(a) * 1.75
            parts.append(box((xs - 0.2, py - 0.1, pz - 0.36), (xs + 0.2, py + 0.4, pz + 0.36), "gold"))
        for k in range(5):
            a = math.radians(160 + k * 55)
            pz = 8.0 + math.cos(a) * 1.75
            py = yc + math.sin(a) * 1.75
            parts.append(box((xs - 0.24, py - 0.12, pz - 0.12), (xs + 0.24, py + 0.12, pz + 0.12), "band"))
        parts += prism((x + 0.1 * out, yc - 2.55, 8.0), 0.55, 0.22, "gold", axis="x", sides=8, cap="coin")
    return parts


def medallion() -> list[dict]:
    """The lucky medallion on the lid's front and its hasp."""
    cx, cy, z = 8.0, 11.95, L1
    parts = prism((cx, cy, z + 0.12), 2.1, 0.26, "enamel", axis="z", sides=16, cap="enamel")
    parts += arc((cx, cy, z + 0.2), 2.2, 0, 360, 12, 0.3, 0.28, "gold", plane="xy")
    parts += clover((cx, cy, z + 0.38), 1.08, 0.22, "leaf", cheap=True, glow=3)
    parts += prism((cx, cy, z + 0.44), 0.3, 0.2, "gold", axis="z", sides=8, cap="gold")
    parts.append(box((7.45, 8.3, B1 + 0.55), (8.55, 9.8, z + 0.36), "gold"))
    parts.append(box((7.7, 8.55, z + 0.36), (8.3, 9.15, z + 0.42), "coin", uv="full"))
    return parts


def lock() -> list[dict]:
    parts = []
    z0, z1 = B1 - 0.05, 16.02
    parts.append(box((5.4, 4.7, z0), (10.6, 8.35, z1), "plate",
                     uv={"north": [0, 0, 4, 4], "east": [0, 0, 1, 4], "west": [0, 0, 1, 4], "up": [0, 0, 4, 1],
                         "down": [0, 0, 4, 1], "south": [0, 0, 16, 16]}))
    parts.append(box((5.2, 8.2, z1 - 0.1), (10.8, 8.55, z1 + 0.1), "mint"))
    parts.append(box((5.2, 4.5, z1 - 0.1), (10.8, 4.85, z1 + 0.1), "mint"))
    parts.append(box((7.45, 6.55, z1 - 0.03), (8.55, 7.65, z1 + 0.03), "keyhole", glow=11, shade=False, uv="full"))
    parts.append(box((7.72, 5.2, z1 - 0.03), (8.28, 6.7, z1 + 0.03), "keyhole", glow=11, shade=False, uv="full"))
    for x in (6.2, 9.8):
        parts += prism((x, 6.4, z1 + 0.04), 0.42, 0.16, "gold", axis="z", sides=8, cap="coin")
    return parts


def back_hinges() -> list[dict]:
    parts = []
    for x in (4.0, 12.0):
        parts += prism((x, SEAM, L0 + 0.1), 0.55, 2.4, "gold", axis="x", sides=8, cap="coin")
        parts.append(box((x - 0.85, SEAM - 2.5, B0 - 0.24), (x + 0.85, SEAM - 0.35, B0), "band"))
        parts.append(box((x - 0.85, SEAM + 0.35, L0 - 0.24), (x + 0.85, SEAM + 2.9, L0), "band"))
    return parts


def base() -> list[dict]:
    parts = [box((B0 - 0.3, 0.0, B0 - 0.3), (B1 + 0.3, 1.0, B1 + 0.3), "band", skip=("down",)),
             box((B0 - 0.12, 1.0, B0 - 0.12), (B1 + 0.12, 1.3, B1 + 0.12), "mint", skip=("down",))]
    for (x, z) in ((B0 + 0.4, B0 + 2.6), (B1 - 2.4, B1 - 0.2), (B1 - 0.2, B0 + 3.8), (B0 + 3.0, B0 - 0.1)):
        for ang in (45, -45):
            t = box((x - 0.6, 0.0, z), (x + 0.6, 1.3, z), "blades", uv="full", skip=("up", "down", "east", "west"))
            turn(t, ang, "y", (x, 0.0, z))
            parts.append(t)
    return parts


def models() -> dict:
    p = shell("boards", "lidboards", "moss")
    p += rims("band", e=0.22)[1:]
    p += base()
    p += corner_brackets("gold", e=0.34, arm=1.5)
    p += walls()
    p += ivy_run(B0 + 0.9, B1 + 0.42, 1)
    p += ivy_run(B1 - 0.9, B1 + 0.42, -1)
    p += turf()
    p += coin_stacks()
    p += standing_clover()
    p += raindrops()
    p += medallion()
    p += lock()
    p += back_hinges()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
