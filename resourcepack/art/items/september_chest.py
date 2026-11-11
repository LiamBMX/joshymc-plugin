"""September Chest (issue #1495, design in #1407): the monthly crate chest for September,
an early autumn chest in burnt orange, maple red, golden brown, dark oak and muted olive.

A body of dark oak boards (upright) reinforced with burnished copper: a plinth, a seam
band, the lid's edge and L brackets on every corner run, with copper rivets. The lid is a
stepped mound of fallen leaves: three tiers of calm leaf litter, with 3D maple leaves in
maple red, burnt orange, golden brown and olive shingled over every step's edge like roof
tiles and a few loose ones on the crown. Two golden orange pumpkins sit on the crown (a
big one and a small one, ribbed, with curled stems and a vine leaf), a few acorns lie in
the leaves, and a full sheaf of golden wheat, tied with an olive band, stands at the back
corner.

The emblem is a maple leaf on the lid's front: a red leaf over a burnt orange one on a
golden brown disc in a copper ring. Below it the lock is an acorn: a warm brown nut under
a crosshatched cap, standing out in relief from a dark copper plate across the seam, with
a glowing amber keyhole in the nut. Sprays of leaves and acorns sit on each side wall and
on the front's upper corners, and two copper maple-leaf hinges hold the lid at the back.

Animation (one loop): a warm glint passes over the copper, the keyhole glows like a
lantern, and the wheat ears catch the light in turn.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block. Unlike the other crate chests (and the Harvest Chest's
silver barrel vault): a stepped mound of fallen leaves with pumpkins and a standing wheat
sheaf, an early fall look with no Halloween faces.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM,  # noqa: F401
                                    corner_brackets, keyhole_glow, planks, put, shell)
from art.kit import animate, arc, bar, box, canvas, copy, display, lathe, model, prism, save, save_animation, \
    shine, turn, wave

ID = "september_chest"
NAME = "September Chest"

OAK = ["#2a1b0f", "#3d2a16", "#52391f", "#664829", "#795833", "#8c693e", "#a07e4e", "#b99862"]
COPPER = ["#2e1209", "#4d2010", "#723016", "#94401c", "#b05424", "#c86c32", "#de8c4a", "#f0b478"]
MAPLE = ["#3e0b08", "#64140d", "#8c1f12", "#b02e17", "#cc4220", "#e05f2c", "#f08a4a"]
BURNT = ["#4d1f06", "#7a320a", "#a3470f", "#c55f16", "#dd7a22", "#ee9a3c", "#f8bd66"]
GOLDB = ["#4a300c", "#6e4813", "#93641c", "#b38027", "#cc9c38", "#e0b858", "#efd388"]
OLIVE = ["#24260e", "#373a16", "#4b4f1f", "#60652a", "#777c38", "#8f944c", "#aaae68"]
PUMPKIN = ["#5a2c06", "#8a460c", "#b06212", "#cf7c1c", "#e2962a", "#efb04a", "#f8cc78"]
NUT = ["#4e2a12", "#6b3c1a", "#8a4e24", "#a8652e", "#c07e40", "#d69a58", "#e8b878"]
GLOW = ["#2a1002", "#552104", "#8c3a08", "#c95f12", "#ef8c26", "#ffbd5a", "#fff0c0"]

PILE = LID_TOP + 0.35      # top of the leaf litter
TIER2 = PILE + 0.9         # the mound's middle step
TIER3 = TIER2 + 0.9        # its crown

# a maple leaf, left half (column 7 is the midrib); mirrored to the right
_LEAF = [
    ".......#",
    "......##",
    "..#...##",
    "..##.###",
    "...#####",
    "#..#####",
    "##.#####",
    ".#######",
    "..######",
    ".#######",
    "########",
    "...#####",
    "....##.#",
    ".......#",
    ".......#",
    "........",
]


def leaf_mask(x: int, y: int) -> bool:
    if x > 14:
        return False
    col = x if x <= 7 else 14 - x
    return _LEAF[y][col] == "#"


# ======================================================================================
# Textures
# ======================================================================================

def boards(size: int = 32) -> object:
    return planks(OAK[1:], size=size, rows=4, seed=131, vertical=True, knots=3, seam_dark=0)


def lid_boards(size: int = 32) -> object:
    return planks(OAK[1:], size=size, rows=3, seed=137, vertical=False, knots=2, seam_dark=0)


def litter(size: int = 32) -> object:
    """Leaf litter: small overlapping leaves in every autumn colour over dark earth."""
    img = canvas(size, fill=OAK[3])
    rng = random.Random(139)
    for y in range(size):
        for x in range(size):
            if rng.random() < 0.15:
                put(img, x, y, OAK[2])
    pals = (MAPLE, BURNT, GOLDB, OLIVE, BURNT, MAPLE)
    for _ in range(24):
        pal = rng.choice(pals)
        cx, cy = rng.randrange(size), rng.randrange(size)
        r = rng.choice((2.6, 3.2, 3.8))
        a = rng.random() * math.pi
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                u = dx * math.cos(a) + dy * math.sin(a)
                v = -dx * math.sin(a) + dy * math.cos(a)
                if (u / r) ** 2 + (v / (r * 0.6)) ** 2 <= 1:
                    tone = 4 if v < 0 else 3
                    if abs(v) < 0.5:
                        tone = 2
                    put(img, (cx + dx) % size, (cy + dy) % size, pal[tone])
    return img


def dark_field(size: int = 16) -> object:
    """The emblem's dark oak backing disc."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            put(img, x, y, GOLDB[3] if d < 5 else GOLDB[2] if d < 7 else GOLDB[1])
    return img


def leaf(pal):
    """A maple leaf card: cut-out shape, a dark rim, a lit upper half, a darker midrib and
    side veins."""
    def paint(size: int = 16) -> object:
        img = canvas(size)
        for y in range(size):
            for x in range(size):
                if not leaf_mask(x, y):
                    continue
                edge = any(not leaf_mask(x + dx, y + dy) if 0 <= x + dx < 16 and 0 <= y + dy < 16 else True
                           for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                tone = 4 if y < 8 else 3
                if x < 7:
                    tone += 1
                if edge:
                    tone = 2
                if x == 7 and y > 1:
                    tone = 1
                if abs(abs(x - 7) - (10 - y)) < 1 and 4 < y < 11 and x != 7:
                    tone = 2
                put(img, x, y, pal[max(0, min(len(pal) - 1, tone))])
        return img
    return paint


def copper(t: float) -> object:
    """Burnished copper: lit top rows, shaded body, a warm glint crossing it."""
    img = canvas(16)
    rng = random.Random(149)
    for y in range(16):
        for x in range(16):
            tone = 6 if y == 0 else 5 if y < 3 else 4 if y < 9 else 3
            if y >= 14:
                tone = 1
            r = rng.random()
            if r < 0.1:
                tone = max(1, tone - 1)
            put(img, x, y, COPPER[tone])
            if r > 0.975 and 2 < y < 14:
                put(img, x, y, OLIVE[5] if r > 0.99 else "#5f8a6a")
    return shine(img, t, colour=COPPER[7], width=3.0, strength=0.55, pause=0.5)


def copper_leaf(size: int = 16) -> object:
    """A copper maple leaf for the hinges."""
    img = leaf(COPPER[1:])()
    return img


def pumpkin(size: int = 16) -> object:
    """Pumpkin skin: deep grooves between rounded ribs, a little yellow mottling."""
    img = canvas(size)
    rng = random.Random(151)
    for y in range(size):
        for x in range(size):
            k = x % 4
            tone = 1 if k == 0 else 4 if k == 2 else 3
            if y < 2 or y > 13:
                tone -= 1
            if rng.random() < 0.08:
                tone += 1
            put(img, x, y, PUMPKIN[max(0, min(6, tone))])
    return img


def pumpkin_top(size: int = 16) -> object:
    """A pumpkin seen from above: ribs running in to the stem."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            a = math.atan2(y + 0.5 - 8, x + 0.5 - 8)
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            k = (a / (2 * math.pi) * 8) % 1
            tone = 1 if k < 0.18 else 5 if k < 0.55 else 4
            if d < 2:
                tone = 2
            put(img, x, y, PUMPKIN[tone])
    return img


def stem(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 4 if x < 5 else 3 if x < 11 else 2
            if y % 5 == 0:
                tone -= 1
            put(img, x, y, OLIVE[tone])
    return img


def nut(size: int = 16) -> object:
    """Acorn nut: smooth golden brown, lit on the left, a pale tip."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 4 if x < 4 else 3 if x < 11 else 2
            if y > 13:
                tone = 5
            if x == 2 and 3 < y < 11:
                tone = 6
            put(img, x, y, NUT[tone])
    return img


def cap(size: int = 16) -> object:
    """Acorn cap: rows of overlapping olive brown scales."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            # crosshatched scales: two diagonal grooves
            groove = (x + y) % 4 == 0 or (x - y) % 4 == 0
            put(img, x, y, "#3a2010" if groove else "#8e5c30" if (x + y) % 4 == 1 and y % 2 == 0 else "#6b4423")
    return img


def wheat_stalk(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            put(img, x, y, GOLDB[5] if x < 6 else GOLDB[4] if x < 12 else GOLDB[3])
    return img


def wheat_ear(t: float) -> object:
    """A wheat ear: plump kernels in staggered pairs with light running up them."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            kernel = (y + (x // 8) * 2) % 4
            tone = 6 if kernel == 0 else 5 if kernel == 1 else 4 if kernel == 2 else 2
            if x >= 8:
                tone -= 1
            put(img, x, y, GOLDB[max(0, tone)])
    return shine(img, t, colour="#fff2c4", width=4.0, strength=0.45, angle=90, pause=0.5)


def ribbon(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            put(img, x, y, OLIVE[5] if y < 3 else OLIVE[3] if y < 12 else OLIVE[2])
    return img


def plate(size: int = 16) -> object:
    """The copper lock plate: bevelled, rivets in the corners."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            if x <= 1 or y <= 1:
                c = COPPER[6] if (x == 0 or y == 0) else COPPER[5]
            elif x >= size - 2 or y >= size - 2:
                c = COPPER[1] if (x == size - 1 or y == size - 1) else COPPER[2]
            else:
                c = COPPER[4] if (x + y) % 7 else COPPER[3]
            put(img, x, y, c)
    for rx, ry in ((3, 3), (11, 3), (3, 11), (11, 11)):
        put(img, rx, ry, COPPER[7])
        put(img, rx + 1, ry + 1, COPPER[1])
    return img


def rivet(size: int = 16) -> object:
    """A domed copper rivet head."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 6)
            put(img, x, y, COPPER[7] if d < 3 else COPPER[5] if d < 7 else COPPER[3] if d < 10 else COPPER[1])
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(GLOW, t)


def textures() -> None:
    save(boards(), "boards")
    save(lid_boards(), "lidboards")
    save(litter(), "litter")
    save(dark_field(), "litter_dark")
    save(leaf(MAPLE)(), "leaf_red")
    save(leaf(BURNT)(), "leaf_orange")
    save(leaf(GOLDB)(), "leaf_gold")
    save(leaf(OLIVE)(), "leaf_olive")
    save(copper_leaf(), "leaf_copper")
    save(pumpkin(), "pumpkin")
    save(pumpkin_top(), "pumpkin_top")
    save(stem(), "stem")
    save(nut(), "nut")
    save(cap(), "cap")
    save(wheat_stalk(), "stalk")
    save(ribbon(), "ribbon")
    save(plate(), "plate")
    save(rivet(), "rivet")
    save_animation(animate(copper, 12), "copper", frametime=3)
    save_animation(animate(wheat_ear, 12), "wheat", frametime=3)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

LEAVES = ("leaf_red", "leaf_orange", "leaf_gold", "leaf_olive")


def leaf_card(c, s: float, tex: str, yaw: float = 0.0, tilt: float = 0.0, roll: float = 0.0) -> dict:
    """A maple leaf lying flat at c (s across), spun by yaw, then tipped by tilt about X and
    roll about Z."""
    x, y, z = c
    e = box((x - s / 2, y, z - s / 2), (x + s / 2, y + 0.02, z + s / 2), tex, uv="full",
            skip=("north", "south", "east", "west"))
    turn(e, yaw, "y", c)
    if tilt:
        turn(e, tilt, "x", c)
    if roll:
        turn(e, roll, "z", c)
    return e


def standing_leaf(c, s: float, tex: str, spin: float = 0.0, depth: float = 0.02) -> dict:
    """A maple leaf card standing on a wall, facing +Z, spun about its own centre."""
    x, y, z = c
    e = box((x - s / 2, y - s / 2, z), (x + s / 2, y + s / 2, z + depth), tex, uv="full",
            skip=("north", "east", "west", "up", "down"))
    return turn(e, spin, "z", c)


def leaf_pile() -> list[dict]:
    """The leaf litter on the lid, loose leaves at every angle, and leaves spilling over
    the eaves on every side."""
    parts = [box((L0 - 0.05, LID_TOP - 0.01, L0 - 0.05), (L1 + 0.05, PILE, L1 + 0.05), "litter",
                 skip=("down",), faces={"north": "lidboards", "south": "lidboards", "east": "lidboards",
                                        "west": "lidboards"}, uv="full")]
    for (lo, hi, y0, y1) in ((2.0, 14.0, PILE - 0.02, TIER2), (4.0, 12.0, TIER2 - 0.02, TIER3)):
        parts.append(box((lo, y0, lo), (hi, y1, hi), "litter", uv="full", skip=("down",)))
    rng = random.Random(157)
    # leaves shingled over each step's edge, tipped outward like roof tiles
    edge = []
    for i, (x, s) in enumerate(((3.6, 2.4), (6.8, 2.2), (9.8, 2.5), (12.6, 2.1))):
        e = leaf_card((x, TIER2 + 0.03, 14.0), s, LEAVES[i % 4], yaw=rng.uniform(-30, 30), tilt=24)
        edge.append(e)
    for i, (x, s) in enumerate(((5.6, 2.3), (9.6, 2.2))):
        edge.append(leaf_card((x, TIER3 + 0.03, 11.8), s, LEAVES[(i + 2) % 4], yaw=rng.uniform(-30, 30), tilt=20))
    for yaw in (0, 90, 180, 270):
        parts += turn(copy(edge), yaw, "y", (8.0, 0.0, 8.0))
    # a few loose leaves on the crown
    for i, (x, z) in enumerate(((5.0, 5.0), (11.0, 6.5), (7.8, 11.0))):
        parts.append(leaf_card((x, TIER3 + 0.05, z), 2.3, LEAVES[(i + 1) % 4], yaw=rng.random() * 360,
                               tilt=rng.uniform(-8, 8), roll=rng.uniform(-8, 8)))
    # leaves hanging over the eaves: built on the front edge, turned to each side
    eave = []
    for i, (x, droop, s) in enumerate(((2.6, 74, 2.3), (6.2, 80, 2.0), (11.0, 76, 2.4), (13.6, 82, 1.9))):
        e = box((x - s / 2, PILE, L1 - s * 0.3), (x + s / 2, PILE + 0.02, L1 + s * 0.7), LEAVES[(i + 1) % 4],
                uv="full", skip=("north", "south", "east", "west"))
        turn(e, droop, "x", (x, PILE, L1 - 0.05))
        turn(e, (i - 1.5) * 4, "y", (x, PILE, L1))
        eave.append(e)
    for k, yaw in enumerate((0, 90, 180, 270)):
        ring = copy(eave)
        if k % 2:
            ring = [ring[1], ring[3]]
        parts += turn(ring, yaw, "y", (8.0, 0.0, 8.0))
    return parts


def pumpkin_model(c, r: float, h: float, lean: float = 0.0) -> list[dict]:
    """A ribbed pumpkin sitting on c, with a curled stem and a short vine."""
    x, y, z = c
    prof = [(0.0, r * 0.45), (h * 0.18, r * 0.88), (h * 0.45, r), (h * 0.75, r * 0.9), (h * 0.95, r * 0.5),
            (h, r * 0.25)]
    parts = lathe((x, y, z), prof, "pumpkin", sides=8, cap="pumpkin_top", uv="full")
    parts.append(bar((x, y + h - 0.1, z), (x + 0.25, y + h + 0.75, z + 0.1), 0.34, 0.34, "stem"))
    parts += arc((x + 0.55, y + h + 0.55, z + 0.1), 0.42, 180, 20, 3, 0.14, 0.14, "stem", plane="xy")
    # a little vine leaf by the stem
    parts.append(leaf_card((x - 0.6, y + h - 0.05, z + 0.3), 1.0, "leaf_olive", yaw=40, tilt=-20))
    return turn(parts, lean, "z", c)


def acorn_flat(c, s: float, normal: str = "y", yaw: float = 0.0) -> list[dict]:
    """A small acorn: a nut and a cap with a nub, lying on its side (normal y) or standing
    on a wall facing +Z."""
    x, y, z = c
    parts = lathe((x, y, z), [(0.0, 0.18 * s), (0.3 * s, 0.4 * s), (0.85 * s, 0.38 * s)], "nut",
                  sides=8, cap="nut", uv="full")
    parts += prism((x, y + 0.92 * s, z), 0.44 * s, 0.3 * s, "cap", sides=8, cap="cap", uv="full")
    parts.append(box((x - 0.06 * s, y + 1.12 * s, z - 0.06 * s), (x + 0.06 * s, y + 1.4 * s, z + 0.06 * s), "stem"))
    if normal == "y":
        turn(parts, 90, "z", (x, y + 0.4 * s, z))
        turn(parts, yaw, "y", (x, y + 0.4 * s, z))
    return parts


def lid_harvest() -> list[dict]:
    """Pumpkins, acorns and a wheat bundle in the leaves."""
    parts = pumpkin_model((6.0, TIER3 - 0.1, 8.2), 1.8, 2.2, lean=-4)
    parts += pumpkin_model((9.4, TIER3 - 0.1, 10.2), 1.25, 1.6, lean=6)
    for (x, z, yaw) in ((8.8, 6.4, 30), (13.0, 9.0, 120), (3.0, 12.6, 75)):
        parts += acorn_flat((x, mound(x, z) + 0.36, z), 0.9, yaw=yaw)
    # the wheat bundle standing at the back right corner
    bx, bz = 11.9, 4.4
    base = TIER2 - 0.1
    tie = (bx, base + 2.0, bz)
    stalks = ((-0.6, 0.0, 0.0), (0.0, -0.6, 0.3), (0.6, 0.1, 0.2), (-0.3, 0.5, 0.45), (0.35, 0.55, 0.55),
              (0.0, 0.0, 0.75), (-0.65, -0.4, 0.15), (0.5, -0.45, 0.35), (-0.15, -0.25, 0.6))
    for (dx, dz, extra) in stalks:
        foot = (bx + dx * 0.55, base, bz + dz * 0.55)
        top = (bx + dx * 2.2, base + 4.85 + extra, bz + dz * 2.2)
        parts.append(bar(foot, tie, 0.26, 0.26, "stalk"))
        parts.append(bar(tie, top, 0.26, 0.26, "stalk"))
        d = [top[i] - tie[i] for i in range(3)]
        ln = math.sqrt(sum(v * v for v in d))
        tip = tuple(top[i] + d[i] / ln * 1.7 for i in range(3))
        parts.append(bar(top, tip, 0.56, 0.46, "wheat", uv="full"))
    parts += prism(tie, 0.7, 0.6, "ribbon", sides=8, cap="ribbon")
    return parts


def mound(x: float, z: float) -> float:
    """Height of the leaf mound's top at (x, z)."""
    if 4.0 < x < 12.0 and 4.0 < z < 12.0:
        return TIER3
    if 2.0 < x < 14.0 and 2.0 < z < 14.0:
        return TIER2
    return PILE


def copper_frame() -> list[dict]:
    """Copper bands at the base, the seam and the lid's edge, brackets on every corner."""
    parts = [box((B0 - 0.2, 0.0, B0 - 0.2), (B1 + 0.2, 0.85, B1 + 0.2), "copper", skip=("down",)),
             box((B0 - 0.18, SEAM - 0.7, B0 - 0.18), (B1 + 0.18, SEAM, B1 + 0.18), "copper", skip=("up",)),
             box((L0 - 0.12, LID_TOP - 0.75, L0 - 0.12), (L1 + 0.12, LID_TOP - 0.05, L1 + 0.12), "copper",
                 skip=("up", "down"))]
    parts += corner_brackets("copper", e=0.24, arm=1.25)
    # copper rivets on the front and back brackets
    for x in (B0 + 0.65, B1 - 0.65):
        for z in (B1 + 0.26, B0 - 0.36):
            for y in (2.2, SEAM - 2.4):
                parts.append(box((x - 0.22, y - 0.22, z), (x + 0.22, y + 0.22, z + 0.1), "rivet", uv="full"))
    return parts


def maple_emblem() -> list[dict]:
    """A layered maple leaf in a copper ring on the lid's front."""
    cx, cy, z = 8.0, 12.15, L1
    parts = arc((cx, cy, z + 0.12), 2.15, 0, 360, 12, 0.34, 0.24, "copper", plane="xy")
    parts += prism((cx, cy, z + 0.05), 2.0, 0.1, "lidboards", axis="z", sides=16, cap="litter_dark", uv="full")
    parts.append(standing_leaf((cx + 0.35, cy + 0.05, z + 0.12), 4.3, "leaf_orange", spin=-20))
    parts.append(standing_leaf((cx - 0.05, cy + 0.15, z + 0.2), 4.6, "leaf_red", spin=2))
    return parts


def acorn_lock() -> list[dict]:
    """An acorn in relief on a copper plate across the seam, a glowing keyhole in the nut."""
    zf = 16.02
    zp = 15.84                  # the plate stands back so the acorn can stand out
    parts = [box((6.2, 5.9, B1 - 0.05), (9.8, 11.2, zp), "plate",
                 uv={"north": [0, 0, 4, 4], "east": [0, 0, 1, 4], "west": [0, 0, 1, 4], "up": [0, 0, 4, 1],
                     "down": [0, 0, 4, 1], "south": [0, 0, 16, 16]})]
    # nut: horizontal slices from the pointed tip up to the cap
    nut_prof = ((4.7, 0.15), (5.1, 0.55), (5.6, 0.95), (6.3, 1.25), (7.1, 1.38), (8.0, 1.4), (9.0, 1.3))
    for i, ((y0, w0), (y1, w1)) in enumerate(zip(nut_prof, nut_prof[1:])):
        w = (w0 + w1) / 2
        v0, v1 = 16 - (y1 - 4.7) / 4.3 * 16, 16 - (y0 - 4.7) / 4.3 * 16
        parts.append(box((8.0 - w, y0, zp - 0.02), (8.0 + w, y1, zf + 0.1 + 0.025 * min(i, 6 - i)), "nut",
                         uv={"south": [8 - 8 * w / 1.4, v0, 8 + 8 * w / 1.4, v1], "north": [0, 0, 16, 16],
                             "east": [0, v0, 2, v1], "west": [0, v0, 2, v1], "up": [0, 0, 16, 1],
                             "down": [0, 15, 16, 16]}))
    # cap: wider, scaled, with a nub
    cap_prof = ((8.9, 1.5), (9.6, 1.5), (10.2, 1.2), (10.6, 0.6))
    for (y0, w0), (y1, w1) in zip(cap_prof, cap_prof[1:]):
        w = (w0 + w1) / 2
        parts.append(box((8.0 - w, y0, zp - 0.02), (8.0 + w, y1, zf + 0.22), "cap", uv="full"))
    parts.append(bar((8.0, 10.5, zf + 0.08), (8.45, 11.5, zf + 0.08), 0.28, 0.24, "stem"))
    # keyhole in the nut
    parts += prism((8.0, 7.75, zf + 0.2), 0.42, 0.04, "keyhole", axis="z", sides=8, cap="keyhole", glow=11,
                   uv="full", shade=False)
    parts.append(box((7.8, 6.7, zf + 0.18), (8.2, 7.7, zf + 0.22), "keyhole", glow=11, shade=False, uv="full"))
    # copper hasp from the lid over the cap
    parts.append(box((7.3, SEAM + 1.0, zf - 0.06), (8.7, SEAM + 1.9, zf + 0.06), "copper"))
    return parts


def side_sprays() -> list[dict]:
    """A spray of three leaves and two acorns on each side wall."""
    wall = [standing_leaf((7.0, 5.6, B1 + 0.03), 3.0, "leaf_red", spin=24),
            standing_leaf((9.4, 5.2, B1 + 0.06), 2.6, "leaf_orange", spin=-30),
            standing_leaf((8.2, 3.6, B1 + 0.09), 2.4, "leaf_gold", spin=180)]
    wall += acorn_flat((7.6, 3.6, B1 + 0.42), 0.8, normal="z")
    wall += acorn_flat((8.9, 3.3, B1 + 0.42), 0.7, normal="z")
    out = turn(copy(wall), 90, "y", (8.0, 0.0, 8.0)) + turn(copy(wall), -90, "y", (8.0, 0.0, 8.0))
    # leaves fallen against the front's upper corners, just under the seam band
    for (x, spin, tex, s_) in ((2.7, 32, "leaf_orange", 2.4), (3.9, -18, "leaf_red", 1.9),
                               (13.3, -32, "leaf_gold", 2.4), (12.1, 18, "leaf_red", 1.9)):
        out.append(standing_leaf((x, SEAM - 1.9 - (0.4 if s_ < 2 else 0.0), B1 + (0.05 if s_ > 2 else 0.1)), s_, tex,
                                 spin=spin))
    return out


def back_hinges() -> list[dict]:
    """Two copper leaf hinges at the back: a knuckle with a leaf strap up the lid and a
    plain strap down the body."""
    parts = []
    for x in (4.0, 12.0):
        parts += prism((x, SEAM, L0 - 0.1), 0.5, 2.2, "copper", axis="x", sides=8, cap="plate")
        parts.append(box((x - 0.8, SEAM - 2.4, B0 - 0.2), (x + 0.8, SEAM - 0.3, B0), "copper"))
        lf = standing_leaf((x, SEAM + 1.9, L0 - 0.12), 3.0, "leaf_copper", depth=0.1)
        parts.append(turn(lf, 180, "y", (x, SEAM + 1.9, L0 - 0.07)))
    return parts


def models() -> dict:
    p = shell("boards", "lidboards", "litter")
    p += copper_frame()
    p += leaf_pile()
    p += lid_harvest()
    p += maple_emblem()
    p += acorn_lock()
    p += side_sprays()
    p += back_hinges()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
