"""November Chest (issue #1497, design in #1407): the monthly crate chest for November, a
cozy autumn campfire chest in burnt orange, amber gold, rustic brown, dark maple red and
warm cream.

A body of rustic brown boards (laid flat) in warm copper: a plinth, a seam band, rounded
corner caps and two upright straps on the front with rows of rivets. The lid is a low
curved vault of boards with copper eaves, and a cream and maple red plaid blanket is
thrown over its right side, following the curve and hanging down front and back with a
tasselled fringe. A row of layered autumn leaves runs along the vault's ridge.

On the lid sit the evening's comforts: a copper lantern with a glowing candle in its
glass at the front left corner, ringed by acorns and pinecones, and at the back a small
campfire of crossed logs with a living flame and glowing embers drifting above it.

The emblem is a maple leaf in a copper ring on the body's front, under the lock: a golden
leaf-shaped plate across the seam with a warm glowing keyhole. Pinecone and acorn sprays
sit on both side walls, and two copper hinges with twig straps hold the lid at the back.

Animation (one loop): the lantern's candle and the campfire flicker, the embers glow and
fade, the keyhole breathes, and a warm glint runs over the copper.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block. Unlike September (a leaf mound with pumpkins and wheat)
and the Campfire Chest (charred planks, iron horns): a curved vault lid with a plaid
blanket, a lantern and a small campfire, a low cozy silhouette.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM,  # noqa: F401
                                    flame, keyhole_glow, keyhole_shape, planks, put, shell)
from art.kit import animate, arc, bar, box, canvas, copy, display, lathe, mirror, model, prism, save, \
    save_animation, shine, sparkle, turn, wave

ID = "november_chest"
NAME = "November Chest"

WOOD = ["#33200f", "#482d16", "#5e3b1d", "#734a25", "#88592c", "#9c6a36", "#b07d45", "#c79858"]
COPPER = ["#3a1508", "#5a220e", "#7e3418", "#a04722", "#bb5c2e", "#d0773e", "#e49a5c", "#f4c08a"]
MAPLE = ["#2e0806", "#4c0d0a", "#6c1410", "#8c1d14", "#a82a1a", "#c43e24", "#dc5c34"]
BURNT = ["#4a1a06", "#72290a", "#9a3c0e", "#bd5214", "#d86c1e", "#ea8a30", "#f6ac52"]
AMBER = ["#4c2e06", "#76480a", "#a0660e", "#c48416", "#dca226", "#ecbe48", "#f8da82"]
CREAM = ["#7a6a52", "#9c8a6c", "#bba884", "#d4c29c", "#e6d7b4", "#f3e8cc", "#fdf6e6"]
PINE = ["#2a170a", "#3c2210", "#523017", "#6a401f", "#835228", "#9c6634"]
GLOW = ["#2e1002", "#5e2204", "#94400a", "#cc6a14", "#f09a2c", "#ffc860", "#fff0c4"]
EMBER = ["#3a0a02", "#7a1c04", "#c2400a", "#f07418", "#ffb040", "#ffe6a0"]

RISE = 1.75                                  # how far the vault lid rises above LID_TOP
R = (7.65 ** 2 + RISE ** 2) / (2 * RISE)     # the vault's radius (it spans the lid front to back)
CY = LID_TOP + RISE - R                      # the vault's centre height
HALF = math.degrees(math.asin(7.65 / R))     # half the vault's angle
BLANKET = (9.6, 14.6)                        # x range of the plaid blanket
LANTERN = 0.86                               # the lantern's scale (it sets the silhouette height)


def vault_y(z: float) -> float:
    """Height of the vault's outer surface at z."""
    return CY + math.sqrt(max(0.0, R * R - (z - 8.0) ** 2))


# ======================================================================================
# Textures
# ======================================================================================

def boards(size: int = 32) -> object:
    return planks(WOOD[1:], size=size, rows=4, seed=311, vertical=False, knots=3, seam_dark=0)


def vault_boards(size: int = 16) -> object:
    """One vault slat: warm boards running along it, a lit edge and a dark seam."""
    img = canvas(size)
    rng = random.Random(313)
    for y in range(size):
        for x in range(size):
            tone = 6 if y == 0 else 1 if y == size - 1 else 4 if rng.random() > 0.3 else 3
            if (x * 7 + y * 3) % 17 == 0:
                tone = 2
            put(img, x, y, WOOD[tone])
    return img


def gable(size: int = 16) -> object:
    """The vault's end boards (upright)."""
    return planks(WOOD[2:], size=size, rows=4, seed=317, vertical=True, knots=1, seam_dark=0, highlight=4)


def copper(t: float) -> object:
    """Warm hammered copper: dimpled, lit at the top, a glint running across."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 6 if y == 0 else 5 if y < 3 else 4
            if y >= 13:
                tone = 2 if y < 15 else 1
            if (x % 4 == 1 and y % 4 == 2) or (x % 4 == 3 and y % 4 == 0 and 2 < y < 13):
                tone += 1
            if (x % 4 == 2 and y % 4 == 3):
                tone -= 1
            put(img, x, y, COPPER[max(0, min(7, tone))])
    return shine(img, t, colour=AMBER[6], width=3.0, strength=0.5, pause=0.5)


def rivet(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 6)
            put(img, x, y, AMBER[6] if d < 3 else COPPER[6] if d < 6 else COPPER[4] if d < 9 else COPPER[2])
    return img


def plaid(size: int = 32) -> object:
    """A cream and maple red tartan: wide red bands, thin amber and brown lines, a woven
    twill shading."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            def band(v):
                k = v % 16
                if k < 6:
                    return 2                       # red band
                if k in (8, 13):
                    return 1                       # amber thread
                if k == 10:
                    return 3                       # brown thread
                return 0                           # cream
            a, b = band(x), band(y)
            pick = a if (x + y) % 2 else b
            if a == 2 and b == 2:
                c = MAPLE[3]
            elif pick == 2:
                c = MAPLE[5]
            elif pick == 1:
                c = AMBER[4]
            elif pick == 3:
                c = WOOD[3]
            else:
                c = CREAM[4] if (x + y) % 4 else CREAM[3]
            put(img, x, y, c)
    return img


def fringe(size: int = 16) -> object:
    """The blanket's tassels: cream strands with a red knot band."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            if y < 4:
                put(img, x, y, MAPLE[4] if y < 3 else MAPLE[2])
            elif x % 3 != 2 and y < 15 - (x * 7 % 3):
                put(img, x, y, CREAM[5] if x % 3 == 0 else CREAM[3])
    return img


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
    if not (0 <= x <= 14 and 0 <= y < 16):
        return False
    col = x if x <= 7 else 14 - x
    return _LEAF[y][col] == "#"


def leaf(pal, vein_shift: int = 0):
    """A maple leaf card: a lit left half, a dark rim, a midrib and two side veins."""
    def paint(size: int = 16) -> object:
        img = canvas(size)
        for y in range(size):
            for x in range(size):
                if not leaf_mask(x, y):
                    continue
                edge = any(not leaf_mask(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                tone = 4 if x < 7 else 3
                if y > 10:
                    tone -= 1
                if edge:
                    tone = 1
                if x == 7 and y > 2:
                    tone = 2 + vein_shift
                if abs(abs(x - 7) - (11 - y) * 0.9) < 0.7 and 3 < y < 11 and x != 7:
                    tone = 2
                put(img, x, y, pal[max(0, min(len(pal) - 1, tone))])
        return img
    return paint


def gold_leaf(size: int = 16) -> object:
    """The lock plate: a golden maple leaf with a cut keyhole."""
    img = leaf(AMBER[1:], vein_shift=1)()
    keyhole_shape(img, "#2a1404")
    return img


def ring_field(size: int = 16) -> object:
    """The emblem's backing: warm cream, darker toward the rim."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            put(img, x, y, CREAM[4] if d < 4 else CREAM[3] if d < 6 else CREAM[2])
    return img


def pinecone(size: int = 16) -> object:
    """Pinecone scales: staggered rows of brown scales with pale tips."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            row = y // 3
            k = (x + (row % 2) * 2) % 4
            dy = y % 3
            tone = 4 if dy == 0 else 2 if dy == 1 else 1
            if k == 3:
                tone = 0
            if dy == 0 and k == 1:
                tone = 5
            put(img, x, y, PINE[tone])
    return img


def nut(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 5 if x < 4 else 4 if x < 11 else 3
            if x == 2 and 3 < y < 11:
                tone = 6
            put(img, x, y, AMBER[tone] if y > 2 else BURNT[3])
    return img


def acorn_cap(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            groove = (x + 2 * y) % 5 == 0
            put(img, x, y, PINE[0] if groove else PINE[3] if (x + y) % 3 else PINE[2])
    return img


def bark(size: int = 16) -> object:
    """Log bark for the campfire: dark ridges, a warm undertone."""
    img = canvas(size)
    rng = random.Random(331)
    for y in range(size):
        for x in range(size):
            tone = 1 if x % 4 == 0 else 3 if x % 4 == 2 else 2
            if rng.random() < 0.15:
                tone += 1
            put(img, x, y, WOOD[tone])
    return img


def log_end(size: int = 16) -> object:
    """A cut log end: rings, a scorched rim."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            c = WOOD[1] if d > 6.8 else CREAM[1] if int(d) % 2 else CREAM[2]
            put(img, x, y, c)
    return img


def stone(size: int = 16) -> object:
    """Fire ring stones: grey with warm soot."""
    img = canvas(size)
    rng = random.Random(341)
    for y in range(size):
        for x in range(size):
            r = rng.random()
            c = "#6e6660" if r > 0.4 else "#58504c" if r > 0.15 else "#8a827a"
            if y < 2:
                c = "#9a9088"
            if y > 12:
                c = "#3c302a"
            put(img, x, y, c)
    return img


def twine(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            put(img, x, y, CREAM[2] if (x + y) % 3 else CREAM[0])
    return img


def twig(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 6 if x < 5 else 5 if x < 11 else 3
            if y % 6 == 0:
                tone -= 2
            put(img, x, y, WOOD[tone])
    return img


def glass(t: float) -> object:
    """The lantern's glass: a warm candle glow inside a pane, flickering."""
    img = canvas(16)
    f = 0.75 + 0.15 * math.sin(2 * math.pi * t) + 0.1 * math.sin(2 * math.pi * 3 * t + 0.7)
    n = len(GLOW)
    for y in range(16):
        for x in range(16):
            d = math.hypot((x + 0.5 - 8) / 8, (y + 0.5 - 9.5) / 9)
            heat = (1.1 - d) * f
            c = GLOW[max(1, min(n - 1, int(heat * n)))]
            if x in (0, 15) or y in (0, 15):
                c = GLOW[2]
            put(img, x, y, c)
    return img


def campfire(t: float) -> object:
    return flame(t, seed=7, palette=["#5a1404", "#9a2a08", "#d4500e", "#f2801c", "#ffb03c", "#ffdc80", "#fff6d4"])


def ember(t: float) -> object:
    """A drifting ember: a hot core breathing in and out (each ember picks its own phase)."""
    img = canvas(16)
    k = wave(t)
    n = len(EMBER)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8) / 8
            heat = (1.0 - d) * (0.45 + 0.55 * k)
            put(img, x, y, EMBER[max(1, min(n - 1, int(heat * n + 0.8)))])
    sparkle(img, 8, 8, k, colour=EMBER[5], reach=3)
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(GLOW, t)


def textures() -> None:
    save(boards(), "boards")
    save(vault_boards(), "slat")
    save(gable(), "gable")
    save(rivet(), "rivet")
    save(plaid(), "plaid")
    save(fringe(), "fringe")
    save(leaf(MAPLE)(), "leaf_red")
    save(leaf(BURNT)(), "leaf_orange")
    save(leaf(AMBER)(), "leaf_amber")
    save(gold_leaf(), "gold_leaf")
    save(ring_field(), "field")
    save(pinecone(), "pinecone")
    save(nut(), "nut")
    save(acorn_cap(), "acorn_cap")
    save(bark(), "bark")
    save(log_end(), "log_end")
    save(twig(), "twig")
    save(stone(), "stone")
    save(twine(), "twine")
    save_animation(animate(copper, 12), "copper", frametime=3)
    save_animation(animate(glass, 12), "glass", frametime=2, interpolate=True)
    save_animation(animate(campfire, 12), "fire", frametime=2)
    save_animation(animate(ember, 8), "ember", frametime=3, interpolate=True)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

LEAVES = ("leaf_red", "leaf_orange", "leaf_amber")


def flat_card(c, s: float, tex: str, yaw: float = 0.0, tilt: float = 0.0, roll: float = 0.0) -> dict:
    """A flat card (leaf) lying at c, spun by yaw, then tipped about X and Z."""
    x, y, z = c
    e = box((x - s / 2, y, z - s / 2), (x + s / 2, y + 0.02, z + s / 2), tex, uv="full",
            skip=("north", "south", "east", "west"))
    turn(e, yaw, "y", c)
    if tilt:
        turn(e, tilt, "x", c)
    if roll:
        turn(e, roll, "z", c)
    return e


def wall_card(c, s: float, tex: str, spin: float = 0.0, depth: float = 0.02) -> dict:
    """A card standing on a wall facing +Z, spun about its centre."""
    x, y, z = c
    e = box((x - s / 2, y - s / 2, z), (x + s / 2, y + s / 2, z + depth), tex, uv="full",
            skip=("north", "east", "west", "up", "down"))
    return turn(e, spin, "z", c)


def vault() -> list[dict]:
    """The curved lid: slats on an arc from front to back, stepped end boards closing both
    ends, copper eaves along the front and back."""
    parts = arc((8.0, CY, 8.0), R - 0.3, 90 - HALF - 0.6, 90 + HALF + 0.6, 9, 15.3, 0.6, "slat", plane="zy",
                uv="full")
    # end boards: steps following the curve on both ends
    steps = 7
    for i in range(steps):
        z0 = L0 + (L1 - L0) * i / steps
        z1 = L0 + (L1 - L0) * (i + 1) / steps
        top = vault_y((z0 + z1) / 2) - 0.25
        for x0, x1 in ((L0 - 0.02, L0 + 0.4), (L1 - 0.4, L1 + 0.02)):
            parts.append(box((x0, LID_TOP - 0.02, z0), (x1, top, z1), "gable", skip=("down",)))
    # copper eaves front and back, and a copper ridge cap
    for z in (L1, L0):
        sz = 1 if z == L1 else -1
        parts.append(box((L0 - 0.12, LID_TOP - 0.7, z - 0.05 * sz), (L1 + 0.12, LID_TOP + 0.25, z + 0.22 * sz),
                         "copper"))
    return parts


def blanket() -> list[dict]:
    """The plaid blanket thrown over the vault's right side, hanging down front and back
    with a fringe."""
    x0, x1 = BLANKET
    parts = arc((8.0, CY, 8.0), R + 0.02, 90 - HALF - 1.4, 90 + HALF + 1.4, 9, x1 - x0, 0.14, "plaid",
                plane="zy", uv="full")
    for p in parts:
        dx = (x0 + x1) / 2 - 8.0
        p["from"][0] += dx
        p["to"][0] += dx
        if "rotation" in p:
            p["rotation"]["origin"][0] += dx
    for (z, sz) in ((L1 + 0.24, 1), (L0 - 0.24, -1)):
        zz = (z, z + 0.14 * sz)
        drop = 2.8 if sz > 0 else 3.4
        parts.append(box((x0, LID_TOP + 0.2 - drop, min(zz)), (x1, LID_TOP + 0.35, max(zz)), "plaid",
                         uv={"south": [0, 0, 16, 9], "north": [0, 0, 16, 9], "east": [0, 0, 1, 9],
                             "west": [0, 0, 1, 9], "up": [0, 0, 16, 1], "down": [0, 0, 16, 1]}))
        parts.append(box((x0 + 0.1, LID_TOP + 0.2 - drop - 0.9, min(zz) + 0.03), (x1 - 0.1, LID_TOP + 0.2 - drop,
                         max(zz) - 0.03), "fringe", uv="full", skip=("up", "down", "east", "west")))
    # a fold where it drapes over the right end, so it reads as cloth
    parts.append(box((x1 - 0.05, LID_TOP + 0.05, 3.0), (x1 + 0.16, vault_y(8.0) - 0.15, 13.0), "plaid",
                     uv="full", skip=("down",)))
    return parts


def garland() -> list[dict]:
    """A twine garland of maple leaves sagging across the lid's front, left of the blanket,
    with an acorn hanging at each end."""
    z = L1 + 0.26
    x0, x1, top, sag = 1.0, 9.3, LID_TOP - 0.35, 1.5
    pts = []
    for i in range(9):
        u = i / 8
        pts.append((x0 + (x1 - x0) * u, top - sag * 4 * u * (1 - u), z))
    parts = [bar(a, b, 0.14, 0.14, "twine") for a, b in zip(pts, pts[1:])]
    for i, (x, y, _) in enumerate(pts[1:-1]):
        parts.append(wall_card((x, y - 0.75, z + 0.02 + 0.02 * (i % 2)), 1.9, LEAVES[i % 3],
                               spin=(i % 3 - 1) * 18 + 180, depth=0.02))
    for x in (x0, x1):
        a = acorn((x, top - 1.3, z - 0.05), 0.65)
        parts += turn(a, 180, "z", (x, top - 0.7, z - 0.05))
    return parts


def end_trims() -> list[dict]:
    """Copper strips following the vault's curve on both ends, so the curve reads."""
    out = []
    for x in (L0 - 0.08, L1 + 0.08):
        out += arc((x, CY, 8.0), R - 0.15, 90 - HALF - 0.5, 90 + HALF + 0.5, 5, 0.22, 0.5, "copper", plane="zy")
    return out


def end_leaves() -> list[dict]:
    """A fan of three layered leaves with a copper stud on each end of the vault lid."""
    z = L1 + 0.1
    fan = [wall_card((6.7, SEAM + 2.5, z), 2.6, "leaf_amber", spin=40),
           wall_card((9.3, SEAM + 2.5, z + 0.03), 2.6, "leaf_orange", spin=-40),
           wall_card((8.0, SEAM + 2.9, z + 0.06), 3.0, "leaf_red", spin=0),
           box((7.75, SEAM + 1.45, z + 0.06), (8.25, SEAM + 1.95, z + 0.18), "rivet", uv="full")]
    return turn(copy(fan), 90, "y", (8.0, 0.0, 8.0)) + turn(copy(fan), -90, "y", (8.0, 0.0, 8.0))


def ridge_leaves() -> list[dict]:
    """Layered leaves along the vault's ridge, left of the blanket, each tipped to the curve."""
    rng = random.Random(337)
    parts = []
    for i, x in enumerate((2.2, 4.4, 6.6, 8.7)):
        for (dz, s, lift) in ((-1.0, 2.3, 0.0), (0.9, 2.1, 0.05), (0.0, 2.5, 0.1)):
            z = 8.0 + dz + rng.uniform(-0.3, 0.3)
            tilt = math.degrees(math.asin((z - 8.0) / R))
            parts.append(flat_card((x + rng.uniform(-0.3, 0.3), vault_y(z) + 0.03 + lift, z), s,
                                   LEAVES[(i + int(dz * 2)) % 3], yaw=rng.uniform(-40, 40) + (0 if dz > 0 else 180),
                                   tilt=tilt))
    return parts


def lantern(c, k: float = 1.0) -> list[dict]:
    """A copper lantern: a footed base, a glowing glass, four corner posts, a pointed cap
    and a hoop handle (k scales it)."""
    x, y, z = c
    w, gw, h = 1.15 * k, 0.88 * k, 1.55 * k
    parts = [box((x - w, y, z - w), (x + w, y + 0.35 * k, z + w), "copper"),
             box((x - gw, y + 0.35 * k, z - gw), (x + gw, y + 0.35 * k + h, z + gw), "glass", glow=12, shade=False,
                 uv="full")]
    top = y + 0.35 * k + h
    for dx in (-gw, gw):
        for dz in (-gw, gw):
            parts.append(box((x + dx - 0.14 * k, y + 0.35 * k, z + dz - 0.14 * k),
                             (x + dx + 0.14 * k, top, z + dz + 0.14 * k), "copper"))
    parts.append(box((x - w * 0.95, top, z - w * 0.95), (x + w * 0.95, top + 0.24 * k, z + w * 0.95), "copper"))
    parts += lathe((x, top + 0.24 * k, z), [(0.0, 0.85 * k), (0.32 * k, 0.42 * k), (0.5 * k, 0.14 * k)], "copper",
                   sides=8, cap="copper")
    parts += arc((x, top + 0.7 * k, z), 0.45 * k, 0, 180, 4, 0.13, 0.13, "copper", plane="xy")
    return parts


def pinecone_model(c, s: float, lie: float = 0.0, yaw: float = 0.0) -> list[dict]:
    """A pinecone: a scaly lathe, fat in the middle and pointed at both ends."""
    x, y, z = c
    parts = lathe((x, y, z), [(0.0, 0.15 * s), (0.25 * s, 0.42 * s), (0.6 * s, 0.5 * s), (0.95 * s, 0.38 * s),
                              (1.25 * s, 0.12 * s)], "pinecone", sides=8, cap="pinecone", uv="full")
    if lie:
        turn(parts, lie, "z", (x, y + 0.45 * s, z))
        turn(parts, yaw, "y", (x, y + 0.45 * s, z))
    return parts


def acorn(c, s: float, lie: float = 0.0, yaw: float = 0.0) -> list[dict]:
    x, y, z = c
    parts = lathe((x, y, z), [(0.0, 0.15 * s), (0.3 * s, 0.4 * s), (0.85 * s, 0.38 * s)], "nut", sides=8,
                  cap="nut", uv="full")
    parts += prism((x, y + 0.92 * s, z), 0.45 * s, 0.3 * s, "acorn_cap", sides=8, cap="acorn_cap", uv="full")
    parts.append(box((x - 0.06 * s, y + 1.07 * s, z - 0.06 * s), (x + 0.06 * s, y + 1.35 * s, z + 0.06 * s), "twig"))
    if lie:
        turn(parts, lie, "z", (x, y + 0.4 * s, z))
        turn(parts, yaw, "y", (x, y + 0.4 * s, z))
    return parts


def lid_comforts() -> list[dict]:
    """The lantern at the front left with acorns and pinecones round it, and the small
    campfire at the back with embers above it."""
    lx, lz = 2.9, 13.2
    base = vault_y(lz) - 0.15
    parts = lantern((lx, base, lz), LANTERN)
    parts += pinecone_model((5.0, vault_y(12.6) + 0.42, 12.6), 1.4, lie=90, yaw=20)
    parts += pinecone_model((1.6, vault_y(9.6) + 0.38, 9.6), 1.2, lie=90, yaw=105)
    parts += acorn((4.6, vault_y(10.4) + 0.34, 10.4), 0.85, lie=90, yaw=150)
    parts += acorn((2.0, vault_y(13.9) + 0.3, 13.9), 0.75, lie=90, yaw=60)
    # the campfire: a stone ring, three logs leaning together, a flame and embers
    fx, fz = 6.2, 4.6
    fy = vault_y(fz) - 0.05
    for k in range(6):
        a = math.radians(k * 60 + 10)
        cx, cz = fx + 1.5 * math.cos(a), fz + 1.5 * math.sin(a)
        parts.append(box((cx - 0.36, fy - 0.1, cz - 0.36), (cx + 0.36, fy + 0.42, cz + 0.36), "stone"))
    for k in range(3):
        a = math.radians(k * 120 + 30)
        foot = (fx + 1.15 * math.cos(a), fy + 0.1, fz + 1.15 * math.sin(a))
        top = (fx + 0.15 * math.cos(a), fy + 1.2, fz + 0.15 * math.sin(a))
        parts.append(bar(foot, top, 0.44, 0.44, "bark", faces={"up": "log_end", "down": "log_end"}))
    for yaw in (0, 60, 120):
        e = box((fx - 0.75, fy + 0.15, fz - 0.01), (fx + 0.75, fy + 1.55, fz + 0.01), "fire", uv="full", glow=14,
                shade=False, skip=("up", "down", "east", "west"))
        parts.append(turn(e, yaw + 15, "y", (fx, fy, fz)))
    for (dx, dy, dz, s) in ((0.45, 1.6, 0.3, 0.17), (-0.55, 1.75, -0.2, 0.14), (0.15, 1.9, -0.45, 0.12), (-0.2, 1.5, 0.6, 0.11)):
        parts.append(box((fx + dx - s, fy + dy - s, fz + dz - s), (fx + dx + s, fy + dy + s, fz + dz + s), "ember",
                         uv="full", glow=13, shade=False))
    return parts


def frame() -> list[dict]:
    """Copper round the body: plinth, seam band, rounded corner caps and two front straps
    with rivets."""
    parts = [box((B0 - 0.2, 0.0, B0 - 0.2), (B1 + 0.2, 0.9, B1 + 0.2), "copper", skip=("down",)),
             box((B0 - 0.18, SEAM - 0.75, B0 - 0.18), (B1 + 0.18, SEAM, B1 + 0.18), "copper", skip=("up",))]
    for cx in (B0, B1):
        for cz in (B0, B1):
            sx, sz = (1 if cx == B0 else -1), (1 if cz == B0 else -1)
            parts.append(box((cx - 0.3 * sx, 0.9, cz - 0.3 * sz), (cx + 0.9 * sx, SEAM - 0.75, cz + 0.9 * sz), "copper"))
    for x in (3.4, 12.6):
        parts.append(box((x - 0.55, 0.9, B1 - 0.02), (x + 0.55, SEAM - 0.75, B1 + 0.2), "copper"))
        for y in (2.0, 4.0, 6.0):
            parts.append(box((x - 0.2, y - 0.2, B1 + 0.2), (x + 0.2, y + 0.2, B1 + 0.3), "rivet", uv="full"))
    # the same straps on both side walls and the back
    side = [box((8.0 - 0.55, 0.9, B1 - 0.02), (8.0 + 0.55, SEAM - 0.75, B1 + 0.2), "copper")]
    for yaw in (90, -90, 180):
        parts += turn(copy(side), yaw, "y", (8.0, 0.0, 8.0))
    return parts


def emblem() -> list[dict]:
    """A maple leaf in a copper ring on the body's front, under the lock."""
    cx, cy, z = 8.0, 3.3, B1
    parts = arc((cx, cy, z + 0.12), 1.95, 0, 360, 12, 0.32, 0.24, "copper", plane="xy")
    parts += prism((cx, cy, z + 0.05), 1.8, 0.1, "slat", axis="z", sides=16, cap="field", uv="full")
    parts.append(wall_card((cx, cy + 0.1, z + 0.14), 3.6, "leaf_red", spin=0, depth=0.04))
    parts.append(wall_card((cx + 0.2, cy, z + 0.11), 3.3, "leaf_orange", spin=-24))
    return parts


def leaf_lock() -> list[dict]:
    """A golden maple leaf plate across the seam with a glowing keyhole, on a copper backing
    plate that hides the vanilla latch, and a copper hasp from the lid."""
    zf = 16.02
    parts = [box((6.6, 5.8, B1 - 0.05), (9.4, SEAM + 0.6, zf - 0.04), "copper"),
             wall_card((8.0, 8.15, zf - 0.04), 4.8, "gold_leaf", depth=0.08)]
    parts.append(box((7.55, 7.55, zf + 0.04), (8.45, 8.5, zf + 0.07), "keyhole", glow=12, shade=False, uv="full"))
    parts.append(box((7.75, 6.6, zf + 0.04), (8.25, 7.6, zf + 0.07), "keyhole", glow=12, shade=False, uv="full"))
    parts.append(box((7.3, SEAM + 0.3, zf - 0.04), (8.7, SEAM + 2.4, zf + 0.1), "copper"))
    return parts


def side_sprays() -> list[dict]:
    """A pinecone under three leaves on each side wall."""
    z = B1 + 0.03
    wall = [wall_card((7.0, 5.4, z), 2.8, "leaf_orange", spin=28),
            wall_card((9.2, 5.6, z + 0.03), 2.6, "leaf_red", spin=-24),
            wall_card((8.1, 6.6, z + 0.06), 2.3, "leaf_amber", spin=4)]
    cone = pinecone_model((8.1, 2.0, z + 0.12), 1.5)
    wall += cone
    return turn(copy(wall), 90, "y", (8.0, 0.0, 8.0)) + turn(copy(wall), -90, "y", (8.0, 0.0, 8.0))


def hinges() -> list[dict]:
    """Two copper barrel hinges at the back with twig straps up the vault and down the body."""
    parts = []
    for x in (3.0, 7.6):
        parts += prism((x, SEAM, L0 - 0.08), 0.45, 2.0, "copper", axis="x", sides=8, cap="rivet")
        parts.append(bar((x, SEAM - 2.6, B0 - 0.14), (x + 0.25, SEAM - 0.3, B0 - 0.14), 0.4, 0.22, "twig"))
        parts.append(bar((x, SEAM + 0.3, L0 - 0.1), (x - 0.3, LID_TOP - 0.7, L0 - 0.1), 0.4, 0.2, "twig"))
        parts.append(bar((x - 0.15, SEAM + 2.4, L0 - 0.1), (x - 1.1, SEAM + 3.3, L0 - 0.1), 0.26, 0.18, "twig"))
    return parts


def models() -> dict:
    p = shell("boards", "slat", "slat", faces_lid={"north": "gable", "south": "gable", "east": "gable",
                                                     "west": "gable"})
    p += frame()
    p += vault()
    p += blanket()
    p += ridge_leaves()
    p += garland()
    p += end_trims()
    p += end_leaves()
    p += lid_comforts()
    p += emblem()
    p += leaf_lock()
    p += side_sprays()
    p += hinges()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
