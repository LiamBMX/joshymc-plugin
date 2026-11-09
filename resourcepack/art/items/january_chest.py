"""January Chest (issue #1464, design in #1407): the monthly crate chest for January, a
frozen winter treasure chest in icy blue, silver and white.

Walls of frosted ice bricks with frost ferns creeping up from the bottom, held in a frozen
silver frame: rime-crusted silver bands round the base, the seam and the lid, silver L
brackets up every corner, and raised silver panel frames on every wall. The front panels
each carry a small silver snowflake, the side panels a big blue ice crystal in a silver
bezel with four silver rays. The lid is buried under snow: a thick blanket that drapes
over every edge in lumps, a drift mounded on top, and snow caps heaped on all four lid
corners and piled round the foot of the body. Icicles of different lengths hang from the
lid's edge on every side. Out of the drift grows a cluster of blue crystals (one tall
spire and four leaning shards), and smaller crystals poke out of the corner caps.

The emblem is a large silver snowflake on a frosted ice medallion ringed in silver on the
lid's front; its lower arm drops as the hasp onto a silver lock plate with a cold, glowing
keyhole. Two silver barrel hinges hold the lid at the back.

Animation (one loop): a glint sweeps over the snowflakes and the silver ring, the blue
crystals twinkle, light runs down the icicles, and the keyhole breathes with a pale blue
light.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block like the other crate chests. Deliberately unlike the
permanent crate chests: a snow-buried lid with a crystal spire, icicle fringes and ice
walls instead of planks, enamel or velvet.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM, corner_brackets,  # noqa: F401
                                    keyhole_glow, lock_plate, metal, put, rims, shell)
from art.kit import animate, arc, bar, box, canvas, display, model, prism, save, save_animation, shine, \
    sparkle, turn, wave

ID = "january_chest"
NAME = "January Chest"

ICE = ["#0a2034", "#113656", "#1b5479", "#2a76a0", "#4799c4", "#6fbcdf", "#a3dcf2", "#d6f3ff"]
SILVER = ["#1c2430", "#303c4a", "#4b5967", "#6b7b8b", "#91a1b0", "#b8c6d2", "#dde7ef", "#ffffff"]
SNOW = ["#7f98b2", "#a6bdd3", "#c8d9e8", "#e2edf6", "#f3f8fc", "#ffffff"]
CRYSTAL = ["#051737", "#0a2a66", "#11479d", "#1d6fd0", "#3e98ee", "#7cc6fb", "#c3e9ff", "#ffffff"]
GLOW = ["#06162c", "#0d3156", "#1b5d8a", "#3a91c0", "#6fc3e6", "#b3e8fa", "#f0fcff"]

DRIFT_TOP = LID_TOP + 1.55        # top of the snow drift; the crystals grow out of it


# ======================================================================================
# Textures
# ======================================================================================

def ice_bricks(size: int = 32) -> object:
    """Frosted ice bricks: pale blue blocks with a lit top edge, dark cold mortar, faint
    inner cracks and white frost ferns growing up from the bottom."""
    img = canvas(size)
    rng = random.Random(7)
    bh = 8
    for y in range(size):
        row = y // bh
        shift = 8 if row % 2 else 0
        for x in range(size):
            k = y % bh
            bx = (x + shift) % 16
            if k == bh - 1 or bx == 15:
                c = ICE[2]
            elif k == 0 or bx == 0:
                c = ICE[6]
            else:
                tone = 6 if k == 1 else 5 if k < 4 else 4
                c = ICE[tone]
            put(img, x, y, c)
    # inner cracks
    for _ in range(3):
        x, y = rng.randrange(size), rng.randrange(size)
        for _ in range(rng.randint(3, 6)):
            if y % bh not in (0, bh - 1):
                put(img, x, y, ICE[3])
            x += rng.choice((-1, 1))
            y += 1
    # frost ferns: feathery white fronds from the bottom edge
    for fx in (3, 13, 22, 29):
        h = rng.randint(9, 14)
        for i in range(h):
            y = size - 1 - i
            x = fx + (1 if (i // 3) % 2 else 0)
            put(img, x, y, SNOW[4] if i < h - 3 else SNOW[3])
            if i % 2 == 0 and i < h - 2:
                reach = max(1, (h - i) // 4)
                for r in range(1, reach + 1):
                    put(img, x - r, y - r, SNOW[3])
                    put(img, x + r, y - r, SNOW[3])
    return img


def frost(size: int = 32) -> object:
    """The lid's walls: clearer ice with diagonal frost streaks and a rime crust at the top."""
    img = canvas(size)
    rng = random.Random(9)
    for y in range(size):
        for x in range(size):
            tone = 5 if y < 4 else 4 if y < 22 else 3
            if (x + y * 2) % 13 == 0:
                tone += 1
            if rng.random() < 0.08:
                tone -= 1
            c = ICE[tone]
            if y < 3 and rng.random() < 0.6:
                c = SNOW[3] if rng.random() < 0.6 else SNOW[4]
            put(img, x, y, c)
    for _ in range(9):
        x, y = rng.randrange(size), rng.randrange(4, size)
        for i in range(rng.randint(3, 7)):
            put(img, x + i, y - i, ICE[6])
    return img


def snow(size: int = 16, seed: int = 3) -> object:
    """Packed snow: white with soft blue shadows and a few glittering flecks."""
    img = canvas(size)
    rng = random.Random(seed)
    for y in range(size):
        for x in range(size):
            r = rng.random()
            tone = 4 if r > 0.3 else 3 if r > 0.08 else 2
            if (x * 3 + y * 5) % 17 == 0:
                tone = 2
            put(img, x, y, SNOW[tone])
    for _ in range(5):
        put(img, rng.randrange(size), rng.randrange(size), SNOW[5])
    return img


def snow_side(size: int = 16) -> object:
    """Snow seen from the side: bright crust on top, cooler shadowed base."""
    img = canvas(size)
    rng = random.Random(12)
    for y in range(size):
        for x in range(size):
            tone = 5 if y < 2 else 4 if y < 7 else 3 if y < 12 else 2
            if rng.random() < 0.1:
                tone = max(1, tone - 1)
            put(img, x, y, SNOW[tone])
    return img


def frozen_silver(size: int = 16) -> object:
    """Polished cold silver with a crust of rime along the bottom."""
    img = canvas(size)
    rng = random.Random(4)
    for y in range(size):
        for x in range(size):
            tone = 6 if y == 0 else 5 if y < 5 else 4
            if y >= size - 3:
                tone = 3
            if (x * 2 + y) % 9 == 0:
                tone -= 1
            c = SILVER[tone]
            if y >= size - 3 and rng.random() < 0.45:
                c = SNOW[3]
            put(img, x, y, c)
    for _ in range(3):
        put(img, rng.randrange(1, size - 1), rng.randrange(1, 6), SILVER[7])
    return img


def trim(size: int = 16) -> object:
    """Silver trim strip with an engraved frost line."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            if x <= 1:
                c = SILVER[6] if x == 0 else SILVER[5]
            elif x >= size - 2:
                c = SILVER[1] if x == size - 1 else SILVER[2]
            elif x in (7, 8):
                c = SILVER[3] if (y // 2) % 2 else ICE[5]
            else:
                c = SILVER[4] if (x + y) % 7 else SILVER[5]
            put(img, x, y, c)
    return img


def disc(size: int = 16) -> object:
    """The medallion: deep frosted ice, lighter to the middle, with fine frost spokes."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            tone = 4 if d < 3 else 3 if d < 6 else 2
            a = math.degrees(math.atan2(y + 0.5 - 8, x + 0.5 - 8)) % 60
            if abs(a - 30) < 4 and d > 2:
                tone += 1
            put(img, x, y, ICE[tone])
    return img


def stud(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 6)
            put(img, x, y, SILVER[7 if d < 2.5 else 6 if d < 5 else 5 if d < 8 else 4 if d < 11 else 3])
    return img


def flake_shine(t: float) -> object:
    """The snowflakes and the medallion ring: bright silver-white with a sweeping glint."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 7 if y < 2 else 6 if y < 8 else 5 if y < 13 else 4
            if (x * 3 + y) % 8 == 0:
                tone -= 1
            put(img, x, y, SILVER[tone])
    return shine(img, t, colour="#e8fbff", width=4.0, strength=0.9, pause=0.4)


def crystal(t: float) -> object:
    """A blue ice crystal: dark edges, a lit facet down one side and twinkles."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            edge = min(x, 15 - x)
            tone = 2 if edge == 0 else 3 if edge < 3 else 5 if x < 8 else 4
            if (x + y) % 6 == 0 and edge > 1:
                tone += 1
            if y < 3:
                tone = min(7, tone + 1)
            put(img, x, y, CRYSTAL[tone])
    sparkle(img, 5, 4, wave(t), colour="#ffffff", reach=3)
    sparkle(img, 10, 11, wave(t, 0.45) * 0.8, colour="#dff4ff", reach=2)
    return img


def icicle(t: float) -> object:
    """Clear ice running from white at the root to blue at the tip, a light running down."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 7 if y < 5 else 6 if y < 11 else 5
            if x in (4, 5):
                tone = 7
            elif x >= 12:
                tone -= 2
            c = ICE[tone]
            if y < 3:
                c = SNOW[4] if x < 12 else SNOW[3]
            put(img, x, y, c)
    p = (t * 20) % 20 - 2
    for y in range(16):
        if abs(y - p) < 1.5:
            for x in range(2, 9):
                put(img, x, y, SNOW[5])
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(GLOW, t)


def textures() -> None:
    save(ice_bricks(), "ice")
    save(frost(), "frost")
    save(snow(), "snow")
    save(snow_side(), "drift")
    save(frozen_silver(), "silver")
    save(trim(), "trim")
    save(disc(), "disc")
    save(stud(), "stud")
    save(metal(SILVER[:7], rivets=(3, 12)), "band")
    save(lock_plate(SILVER[:7], hole=ICE[0]), "plate")
    save_animation(animate(flake_shine, 16), "flake", frametime=3)
    save_animation(animate(crystal, 12), "crystal", frametime=3, interpolate=True)
    save_animation(animate(icicle, 10), "icicle", frametime=3)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def silver_frame(face: str, a0: float, a1: float, y0: float, y1: float, t: float = 0.5,
                 proud: float = 0.26) -> list[dict]:
    """A raised silver rectangle on one wall (a0..a1 along the wall)."""
    if face in ("front", "back"):
        z0, z1 = (B1, B1 + proud) if face == "front" else (B0 - proud, B0)
        return [box((a0, y1 - t, z0), (a1, y1, z1), "trim"), box((a0, y0, z0), (a1, y0 + t, z1), "trim"),
                box((a0, y0 + t, z0), (a0 + t, y1 - t, z1), "trim"), box((a1 - t, y0 + t, z0), (a1, y1 - t, z1), "trim")]
    x0, x1 = (B1, B1 + proud) if face == "east" else (B0 - proud, B0)
    return [box((x0, y1 - t, a0), (x1, y1, a1), "trim"), box((x0, y0, a0), (x1, y0 + t, a1), "trim"),
            box((x0, y0 + t, a0), (x1, y1 - t, a0 + t), "trim"), box((x0, y0 + t, a1 - t), (x1, y1 - t, a1), "trim")]


def snowflake(cx: float, cy: float, z: float, r: float, depth: float = 0.2, branches: int = 2,
              arm_w: float = 0.42, skip_down: bool = False) -> list[dict]:
    """A six-armed snowflake in the xy plane facing +Z: a hub, six arms, V branches along
    every arm and a diamond at each tip."""
    if r >= 1.5:
        parts = prism((cx, cy, z), r * 0.24, depth + 0.04, "flake", axis="z", sides=8, cap="flake")
    else:
        hub = box((cx - 0.38, cy - 0.38, z - depth / 2 - 0.02), (cx + 0.38, cy + 0.38, z + depth / 2 + 0.02), "flake")
        parts = [turn(hub, 45, "z", (cx, cy, z))]
    for i in range(6):
        a = math.radians(90 + 60 * i)
        dx, dy = math.cos(a), math.sin(a)
        if skip_down and i == 3:
            continue
        tip = (cx + dx * r, cy + dy * r, z)
        parts.append(bar((cx, cy, z), tip, arm_w, depth, "flake"))
        for k in range(branches):
            f = 0.42 + 0.3 * k if branches > 1 else 0.55  # noqa: E501
            base = (cx + dx * r * f, cy + dy * r * f, z)
            blen = r * (0.34 - 0.1 * k)
            for side in (-1, 1):
                b = a + side * math.radians(55)
                end = (base[0] + math.cos(b) * blen, base[1] + math.sin(b) * blen, z)
                parts.append(bar(base, end, arm_w * 0.72, depth, "flake"))
        if r < 1.5:
            continue
        d = r * 0.11 + 0.12
        gem = box((tip[0] - d, tip[1] - d, z - depth / 2 - 0.02), (tip[0] + d, tip[1] + d, z + depth / 2 + 0.02), "flake")
        turn(gem, 45, "z", (tip[0], tip[1], z))
        parts.append(gem)
    return parts


def shard(base, lean_x: float, lean_z: float, length: float, width: float, glow: int = 6) -> list[dict]:
    """A blue crystal: a diamond-section column with a two-step point, leaning by
    (lean_x, lean_z) degrees."""
    bx, by, bz = base
    parts = [
        box((bx - width / 2, by, bz - width / 2), (bx + width / 2, by + length, bz + width / 2), "crystal", uv="full",
            glow=glow),
        box((bx - width * 0.33, by + length, bz - width * 0.33), (bx + width * 0.33, by + length + width * 0.6,
                                                                   bz + width * 0.33), "crystal", uv="full", glow=glow),
        box((bx - width * 0.15, by + length + width * 0.6, bz - width * 0.15),
            (bx + width * 0.15, by + length + width * 1.05, bz + width * 0.15), "crystal", uv="full", glow=glow),
    ]
    turn(parts, 45, "y", (bx, by, bz))
    if lean_x or lean_z:
        turn(parts, x=lean_z, z=-lean_x, origin=(bx, by, bz))
    return parts


def hang_icicle(x: float, y: float, z: float, length: float, w: float = 0.7) -> list[dict]:
    """An icicle hanging from (x, y, z): two diamond-section pieces, thinner going down."""
    parts = []
    top = y
    for frac, wf in ((0.55, 1.0), (0.45, 0.48)):
        h = length * frac
        ww = w * wf
        parts.append(box((x - ww / 2, top - h, z - ww / 2), (x + ww / 2, top + 0.02, z + ww / 2), "icicle", uv="full"))
        top -= h
    turn(parts, 45, "y", (x, y, z))
    return parts


def icicles() -> list[dict]:
    """Fringes of icicles under the lid's edge on every side (the front leaves the lock and
    medallion clear)."""
    parts = []
    y = SEAM - 0.7
    front = ((1.5, 2.6), (2.7, 3.8), (3.9, 1.9), (12.1, 2.2), (13.3, 3.6), (14.5, 2.4))
    for x, ln in front:
        parts += hang_icicle(x, y, L1 + 0.05, ln)
    back = ((2.4, 2.4), (5.6, 3.4), (7.3, 1.8), (9.2, 2.9), (13.8, 2.1))
    for x, ln in back:
        parts += hang_icicle(x, y, L0 - 0.05, ln)
    for zz, ln in ((2.2, 2.0), (4.4, 3.4), (6.4, 1.7), (9.8, 2.7), (12.0, 3.6), (14.0, 1.9)):
        parts += hang_icicle(L1 + 0.05, y, zz, ln)
        parts += hang_icicle(L0 - 0.05, y, 16.0 - zz, ln * 0.9)
    return parts


def snow_lid() -> list[dict]:
    """A blanket of snow on the lid that drapes over its edges, a drift mounded on top and
    heaped caps on the four corners."""
    parts = []
    e = 0.18
    parts.append(box((L0 - e, LID_TOP, L0 - e), (L1 + e, LID_TOP + 0.6, L1 + e), "drift",
                     faces={"up": "snow"}, skip=("down",)))
    # lumps of snow sagging over the edges
    rng = random.Random(21)
    for side in ("n", "s", "e", "w"):
        for a in (2.6, 6.0, 12.4):
            a += rng.uniform(-0.5, 0.5)
            w = rng.uniform(1.4, 2.2)
            drop = rng.uniform(0.5, 1.0)
            if side in ("n", "s"):
                z0 = L0 - e - 0.06 if side == "n" else L1 + e - 0.12
                parts.append(box((a - w / 2, LID_TOP - drop, z0), (a + w / 2, LID_TOP + 0.4, z0 + 0.18), "drift",
                                 skip=("up",)))
            else:
                x0 = L1 + e - 0.12 if side == "e" else L0 - e - 0.06
                parts.append(box((x0, LID_TOP - drop, a - w / 2), (x0 + 0.18, LID_TOP + 0.4, a + w / 2), "drift",
                                 skip=("up",)))
    # the drift: soft overlapping mounds
    for (x0, z0, x1, z1, h) in ((2.2, 3.4, 13.6, 13.0, 0.75), (3.6, 2.4, 12.4, 11.2, 0.95),
                                (4.4, 4.6, 11.0, 12.2, 1.3), (5.6, 5.0, 10.8, 10.6, 1.55),
                                (1.6, 9.0, 6.0, 14.0, 0.5), (10.4, 2.0, 14.4, 6.4, 0.45)):
        parts.append(box((x0, LID_TOP + 0.58, z0), (x1, LID_TOP + 0.6 + h, z1), "drift", faces={"up": "snow"},
                         skip=("down",)))
    # heaped caps on the four corners
    for cx in (L0 + 1.0, L1 - 1.0):
        for cz in (L0 + 1.0, L1 - 1.0):
            parts.append(box((cx - 1.45, LID_TOP + 0.5, cz - 0.95), (cx + 1.45, LID_TOP + 1.35, cz + 0.95), "drift",
                             faces={"up": "snow"}, skip=("down",)))
            parts.append(box((cx - 0.95, LID_TOP + 0.5, cz - 1.45), (cx + 0.95, LID_TOP + 1.35, cz + 1.45), "drift",
                             faces={"up": "snow"}, skip=("down",)))
            parts.append(box((cx - 0.75, LID_TOP + 1.3, cz - 0.75), (cx + 0.75, LID_TOP + 1.85, cz + 0.75), "drift",
                             faces={"up": "snow"}, skip=("down",)))
    return parts


def crystals() -> list[dict]:
    """The crystal cluster growing out of the drift, and small crystals in the corner caps."""
    parts = []
    y = DRIFT_TOP + 0.4
    parts += shard((8.0, y - 0.6, 8.0), 0, 0, 4.4, 2.0, glow=7)
    for (dx, dz, lx, lz, ln, w) in ((-1.7, 0.5, -26, 8, 2.9, 1.3), (1.7, 0.1, 28, -6, 3.2, 1.35),
                                    (0.3, 1.8, 6, 30, 2.1, 1.1), (-0.3, -1.6, -8, -28, 2.5, 1.15),
                                    (1.4, 1.5, 30, 30, 1.4, 0.85), (-1.5, -1.3, -30, -26, 1.5, 0.85)):
        parts += shard((8.0 + dx, y - 0.7, 8.2 + dz), lx, lz, ln, w, glow=6)
    for cx in (L0 + 1.0, L1 - 1.0):
        for cz in (L0 + 1.0, L1 - 1.0):
            # lean toward the middle so they stay inside the block
            lx = 14 if cx < 8 else -14
            lz = 14 if cz < 8 else -14
            parts += shard((cx, LID_TOP + 1.8, cz), lx, lz, 1.0, 0.6, glow=5)[:1]
    return parts


def base() -> list[dict]:
    """A rime-crusted silver plinth with snow piled round the four foot corners."""
    parts = [box((B0 - 0.3, 0.0, B0 - 0.3), (B1 + 0.3, 1.0, B1 + 0.3), "band", skip=("down",)),
             box((B0 - 0.12, 1.0, B0 - 0.12), (B1 + 0.12, 1.3, B1 + 0.12), "trim", skip=("down",))]
    for cx in (B0, B1):
        for cz in (B0, B1):
            sx = 1 if cx == B0 else -1
            sz = 1 if cz == B0 else -1
            parts.append(box((cx - 0.2 * sx, 0.0, cz - 0.2 * sz), (cx + 2.4 * sx, 1.4, cz + 1.2 * sz), "drift",
                             faces={"up": "snow"}, skip=("down",)))
            parts.append(box((cx - 0.2 * sx, 0.0, cz - 0.2 * sz), (cx + 1.2 * sx, 1.9, cz + 2.4 * sz), "drift",
                             faces={"up": "snow"}, skip=("down",)))
    return parts


def wall_panels() -> list[dict]:
    """Silver panel frames on every wall: snowflakes in front, crystal bezels on the sides."""
    parts = []
    y0, y1 = 1.7, SEAM - 1.45
    for a0, a1 in ((1.8, 5.4), (10.6, 14.2)):
        parts += silver_frame("front", a0, a1, y0, y1)
        parts += snowflake((a0 + a1) / 2, (y0 + y1) / 2, B1 + 0.24, 1.5, depth=0.2, branches=1, arm_w=0.36)
    for face, x, out in (("east", B1, 1), ("west", B0, -1)):
        parts += silver_frame(face, 2.0, 14.0, y0, y1)
        yc = (y0 + y1) / 2
        xr = x + 0.14 * out
        parts += arc((xr, yc, 8.0), 1.5, 22.5, 382.5, 8, 0.4, 0.3, "trim", plane="zy")
        gem = box((xr - 0.24, yc - 1.0, 7.0), (xr + 0.24, yc + 1.0, 9.0), "crystal",
                  uv="full", glow=6)
        turn(gem, 45, "x", (xr, yc, 8.0))
        parts.append(gem)
    return parts


def medallion() -> list[dict]:
    """The emblem on the lid's front: a frosted ice disc ringed in silver carrying the big
    snowflake, whose lower arm is the hasp."""
    cx, cy, z = 8.0, 11.95, L1
    parts = prism((cx, cy, z + 0.1), 1.75, 0.2, "disc", axis="z", sides=16, cap="disc")
    parts += arc((cx, cy, z + 0.14), 1.85, 0, 360, 12, 0.26, 0.24, "trim", plane="xy")
    parts += snowflake(cx, cy, z + 0.42, 2.6, depth=0.24, branches=2, arm_w=0.46, skip_down=True)
    # the lower arm, longer, becomes the hasp over the lock plate
    parts.append(bar((cx, cy, z + 0.42), (cx, 8.75, z + 0.42), 0.55, 0.24, "flake"))
    parts.append(box((7.35, 8.3, B1 + 0.6), (8.65, 9.2, z + 0.5), "silver"))
    for x in (3.2, 12.8):
        parts += snowflake(x, 11.9, z + 0.1, 0.95, depth=0.14, branches=0, arm_w=0.3)
    return parts


def lock() -> list[dict]:
    """A silver lock plate across the vanilla latch with a cold keyhole and icy studs."""
    parts = []
    z0, z1 = B1 - 0.05, 16.02
    parts.append(box((5.3, 4.9, z0), (10.7, 8.4, z1), "plate",
                     uv={"north": [0, 0, 4, 4], "east": [0, 0, 1, 4], "west": [0, 0, 1, 4], "up": [0, 0, 4, 1],
                         "down": [0, 0, 4, 1], "south": [0, 0, 16, 16]}))
    parts.append(box((5.1, 8.25, z1 - 0.1), (10.9, 8.6, z1 + 0.12), "trim"))
    parts.append(box((5.1, 4.7, z1 - 0.1), (10.9, 5.05, z1 + 0.12), "trim"))
    for x in (5.1, 10.55):
        parts.append(box((x, 5.05, z1 - 0.1), (x + 0.35, 8.25, z1 + 0.12), "trim"))
    parts.append(box((7.45, 6.6, z1 - 0.03), (8.55, 7.7, z1 + 0.03), "keyhole", glow=11, shade=False, uv="full"))
    parts.append(box((7.72, 5.3, z1 - 0.03), (8.28, 6.75, z1 + 0.03), "keyhole", glow=11, shade=False, uv="full"))
    for x in (6.1, 9.9):
        gem = box((x - 0.35, 6.25, z1 - 0.05), (x + 0.35, 6.95, z1 + 0.16), "crystal", uv="full", glow=5)
        turn(gem, 45, "z", (x, 6.6, z1))
        parts.append(gem)
    return parts


def back_hinges() -> list[dict]:
    parts = []
    for x in (4.0, 12.0):
        parts += prism((x, SEAM, L0 + 0.1), 0.55, 2.4, "silver", axis="x", sides=8, cap="stud")
        parts.append(box((x - 0.9, SEAM - 2.6, B0 - 0.26), (x + 0.9, SEAM - 0.35, B0), "trim"))
        parts.append(box((x - 0.9, SEAM + 0.35, L0 - 0.26), (x + 0.9, SEAM + 3.1, L0), "trim"))
        for y in (SEAM - 2.0, SEAM + 2.4):
            z = (B0 if y < SEAM else L0) - 0.38
            parts.append(box((x - 0.3, y - 0.3, z), (x + 0.3, y + 0.3, z + 0.14), "stud", uv="full"))
    return parts


def models() -> dict:
    p = shell("ice", "frost", "snow")
    p += rims("band", e=0.22)[1:]
    p += base()
    p += corner_brackets("silver", e=0.36, arm=1.5)
    p += wall_panels()
    p += snow_lid()
    p += crystals()
    p += icicles()
    p += medallion()
    p += lock()
    p += back_hinges()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
