"""Stockpile Chest (issue #1447): the placed crate chest of the Stockpile crate, a refined
strongbox for minerals and gems.

Deep blue enamel walls embossed with a diamond lattice, framed by polished silver: heavy
square pillars with a plinth and a capital stand on all four corners of the body, posts
with stepped pyramid caps on the lid's corners, silver bands round the base, the seam and
the lid, and raised silver panel frames on every wall, each set with a small cut sapphire.
The lid is a stepped strongbox lid (two blue steps and a silver top plate) crowned by a
large cut sapphire held in a silver bezel with four claws. The emblem and lock are one: a
silver diamond plate across the seam carrying a faceted sapphire in an octagonal bezel,
with an icy keyhole below it. Two heavy barrel hinges hold the lid at the back, where
small clusters of pale blue crystal grow from the lid's back corners.

Animation (one loop): glints wander over the sapphires, the crystals pulse with a cold
light, and the keyhole breathes.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block like the other crate chests. Deliberately unlike the
Harvest Chest: flat stepped lid instead of a vault, pillars instead of straps, enamel
instead of planks.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM, keyhole_glow,  # noqa: F401
                                    keyhole_shape, metal, put, rims, shell)
from art.kit import animate, arc, bar, box, canvas, display, lathe, model, prism, save, save_animation, sparkle, \
    turn, wave

ID = "stockpile_chest"
NAME = "Stockpile Chest"

NAVY = ["#060a22", "#0c1336", "#131d4f", "#1b2a6a", "#243986", "#2f49a1", "#4361bd"]
SILVER = ["#191d28", "#2c3241", "#465062", "#677184", "#8e98aa", "#b8c0cd", "#e6ebf2"]
SAPPHIRE = ["#050f38", "#0a2069", "#11359f", "#1a52cf", "#3479ee", "#72aaff", "#c4dfff", "#ffffff"]
CRYSTAL = ["#0b2547", "#123f72", "#1b65a0", "#2f93c8", "#5fc4e6", "#a8ecfb", "#e6fbff"]
ICE = ["#08173a", "#0f2f66", "#1a559c", "#2f86cc", "#62b8ee", "#a9defc", "#eaf8ff"]

STEP1, STEP2 = 1.15, 3.4           # how far each lid step is inset from the lid's edge
STEP1_TOP = LID_TOP + 1.8
STEP2_TOP = STEP1_TOP + 0.5
GEM_C = (8.0, 9.2)                   # the front emblem's centre (x, y)


# ======================================================================================
# Textures
# ======================================================================================

def enamel(size: int = 32) -> object:
    """Deep blue enamel with an embossed diamond lattice: each ridge lit on one side,
    shadowed on the other, and a soft gloss toward the top."""
    img = canvas(size)
    rng = random.Random(12)
    cell = 8
    for y in range(size):
        for x in range(size):
            a, b = (x + y) % cell, (x - y) % cell
            tone = 3 if y < size * 0.4 else 2
            if rng.random() < 0.1:
                tone -= 1
            if a == 0:
                tone = 5
            elif a == 1:
                tone = 4
            if b == 0:
                tone = 1
            elif b == cell - 1 and a not in (0, 1):
                tone = 4
            if a == 0 and b == 0:
                tone = 6
            put(img, x, y, NAVY[max(0, min(6, tone))])
    return img


def enamel_plain() -> object:
    img = canvas(16)
    rng = random.Random(4)
    for y in range(16):
        for x in range(16):
            tone = 4 if y == 0 else 3 if y < 6 else 2
            if y == 15:
                tone = 1
            if rng.random() < 0.08:
                tone -= 1
            put(img, x, y, NAVY[tone])
    return img


def polished(size: int = 16) -> object:
    """Polished silver: bright with two diagonal reflection streaks."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            d = (x + y) % 16
            tone = 4 if y < 8 else 3
            if d == 3:
                tone = 6
            elif d in (2, 4):
                tone = 5
            elif d in (10, 11, 12):
                tone -= 1
            put(img, x, y, SILVER[tone])
    return img


def pillar() -> object:
    """A square pillar face (runs along v): bevelled edges and a fluted middle."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            if x <= 1:
                tone = 6 if x == 0 else 5
            elif x >= 14:
                tone = 1 if x == 15 else 2
            elif x in (5, 10):
                tone = 2
            elif x in (6, 11):
                tone = 5
            else:
                tone = 4 if (x + y * 3) % 11 else 3
            put(img, x, y, SILVER[tone])
    return img


def stud() -> object:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 6)
            tone = 6 if d < 3 else 5 if d < 6 else 4 if d < 9 else 3 if d < 12 else 2
            put(img, x, y, SILVER[tone])
    return img


def diamond_plate() -> object:
    """The emblem plate: a bevelled silver square (turned 45 degrees on the chest) with an
    engraved inner border."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            if x == 0 or y == 0:
                tone = 6
            elif x == 15 or y == 15:
                tone = 1
            elif x == 1 or y == 1:
                tone = 5
            elif x == 14 or y == 14:
                tone = 2
            elif x in (3, 12) and 3 <= y <= 12 or y in (3, 12) and 3 <= x <= 12:
                tone = 2
            elif x in (4, 13) and 4 <= y <= 13 or y in (4, 13) and 4 <= x <= 13:
                tone = 5
            else:
                tone = 4 if (x * 2 + y) % 9 else 3
            put(img, x, y, SILVER[tone])
    keyhole_shape(img, SILVER[0])
    return img


def sapphire(t: float, seed: int = 0) -> object:
    """A cut sapphire's face: facets as wedges round the centre in alternating depths,
    a bright table, and a twinkle that wanders between facets."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            r = max(abs(dx), abs(dy))
            a = (math.atan2(dy, dx) + math.pi) / (2 * math.pi)
            facet = int(a * 8 + 0.5) % 8
            if r < 3:
                tone = 5 if (x + y) % 5 else 6
            elif r < 6:
                tone = (5, 3, 5, 4, 4, 3, 5, 3)[facet]
            else:
                tone = (3, 2, 4, 2, 3, 1, 3, 2)[facet]
            if dy < -4 and dx < 0:
                tone = min(7, tone + 1)
            put(img, x, y, SAPPHIRE[tone])
    spots = ((5, 4), (11, 6), (9, 11), (4, 10))
    for k, (sx, sy) in enumerate(spots):
        amount = max(0.0, wave(t, k / len(spots) + seed * 0.13) * 1.6 - 0.6)
        sparkle(img, sx, sy, amount, colour="#ffffff", reach=2)
    return img


def crystal(t: float) -> object:
    """A crystal shard (runs along v): a bright edge, deep core and a cold pulse."""
    img = canvas(16)
    pulse = wave(t)
    for y in range(16):
        for x in range(16):
            # two facets: a lit one (left) and a shadowed one (right), with a bright ridge
            tone = (3, 3, 4, 4, 4, 3, 3, 5, 2, 1, 1, 1, 0, 0, 1, 0)[x]
            if y < 3:
                tone += 1
            if (x + y) % 9 == 0 and x < 7:
                tone += 1
            tone += round(pulse * 1.2)
            put(img, x, y, CRYSTAL[max(0, min(6, tone))])
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(ICE, t)


def textures() -> None:
    save(enamel(), "enamel")
    save(enamel_plain(), "enamel_plain")
    save(polished(), "silver")
    save(pillar(), "pillar")
    save(metal(SILVER, rivets=(3, 12)), "rail")
    save(stud(), "stud")
    save(diamond_plate(), "plate")
    save_animation(animate(lambda t: sapphire(t, 0), 16), "sapphire", frametime=3)
    save_animation(animate(lambda t: sapphire(t, 3), 16), "sapphire_small", frametime=3)
    save_animation(animate(crystal, 12), "crystal", frametime=3, interpolate=True)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def pillars() -> list[dict]:
    """Heavy square pillars on the body's corners (plinth, shaft, capital) and posts on the
    lid's corners with stepped caps."""
    parts = []
    for cx in (B0, B1):
        for cz in (B0, B1):
            sx = 1 if cx == B0 else -1
            sz = 1 if cz == B0 else -1

            def post(y0, y1, out, size, tex="pillar", skip=()):
                return box((cx - out * sx, y0, cz - out * sz), (cx + size * sx, y1, cz + size * sz), tex,
                           skip=skip)

            parts.append(post(0.0, 1.35, 0.55, 2.35, "silver", ("down",)))      # plinth
            parts.append(post(1.35, SEAM - 1.5, 0.38, 1.95))                     # shaft
            parts.append(post(SEAM - 1.5, SEAM - 0.75, 0.55, 2.35, "silver"))   # capital
    for cx in (L0, L1):
        for cz in (L0, L1):
            sx = 1 if cx == L0 else -1
            sz = 1 if cz == L0 else -1
            parts.append(box((cx - 0.22 * sx, SEAM, cz - 0.22 * sz), (cx + 1.95 * sx, LID_TOP + 0.1, cz + 1.95 * sz),
                             "pillar"))
            # stepped pyramid cap
            for k, (y0, y1, s) in enumerate(((LID_TOP + 0.1, LID_TOP + 0.55, 2.25), (LID_TOP + 0.55, LID_TOP + 0.95, 1.6),
                                             (LID_TOP + 0.95, LID_TOP + 1.25, 1.0))):
                mx, mz = cx + 0.865 * sx, cz + 0.865 * sz
                parts.append(box((mx - s / 2, y0, mz - s / 2), (mx + s / 2, y1, mz + s / 2), "silver",
                                 skip=("down",)))
            if cz == L1:
                # a small cut gem on each front cap
                mx, mz = cx + 0.865 * sx, cz + 0.865 * sz
                parts += lathe((mx, LID_TOP + 1.25, mz), [(0.0, 0.42), (0.25, 0.5), (0.55, 0.3)], "sapphire_small",
                               sides=8, cap="sapphire_small", glow=5)
    return parts


def frame(face: str, u0: float, u1: float, y0: float, y1: float, gem: bool = True) -> list[dict]:
    """A raised silver frame on one wall (u runs along the wall), with a gem-set stud in
    the middle of the panel."""
    t, d = 0.42, 0.22
    plane = {"south": B1, "north": B0, "east": B1, "west": B0}[face]
    out = 1 if face in ("south", "east") else -1
    lo, hi = sorted((plane, plane + d * out))

    def slab(a0, a1, b0, b1, depth_lo=lo, depth_hi=hi, tex="rail"):
        if face in ("south", "north"):
            return box((a0, b0, depth_lo), (a1, b1, depth_hi), tex)
        return box((depth_lo, b0, a0), (depth_hi, b1, a1), tex)

    parts = [slab(u0, u1, y0, y0 + t), slab(u0, u1, y1 - t, y1), slab(u0, u0 + t, y0 + t, y1 - t),
             slab(u1 - t, u1, y0 + t, y1 - t)]
    # the lattice panel inside the frame, a little proud of the plain enamel wall
    plo, phi = sorted((plane, plane + 0.1 * out))
    parts.append(slab(u0 + t, u1 - t, y0 + t, y1 - t, plo, phi, "enamel"))
    if gem:
        cu, cy = (u0 + u1) / 2, (y0 + y1) / 2
        # a small silver diamond with a sapphire in it
        if face in ("south", "north"):
            plate = box((cu - 0.85, cy - 0.85, lo), (cu + 0.85, cy + 0.85, hi + 0.05 if out > 0 else hi), "stud",
                        uv="full")
            if out < 0:
                plate = box((cu - 0.85, cy - 0.85, lo - 0.05), (cu + 0.85, cy + 0.85, hi), "stud", uv="full")
            turn(plate, 45, "z", (cu, cy, plane))
            gem_ = box((cu - 0.42, cy - 0.42, lo - 0.1 if out < 0 else hi), (cu + 0.42, cy + 0.42, lo if out < 0 else hi + 0.12),
                       "sapphire_small", uv="full", glow=4)
            turn(gem_, 45, "z", (cu, cy, plane))
        else:
            plate = box((lo - (0.05 if out < 0 else 0), cy - 0.85, cu - 0.85),
                        (hi + (0.05 if out > 0 else 0), cy + 0.85, cu + 0.85), "stud", uv="full")
            turn(plate, 45, "x", (plane, cy, cu))
            gem_ = box((lo - 0.1 if out < 0 else hi, cy - 0.42, cu - 0.42),
                       (lo if out < 0 else hi + 0.12, cy + 0.42, cu + 0.42), "sapphire_small", uv="full", glow=4)
            turn(gem_, 45, "x", (plane, cy, cu))
        parts += [plate, gem_]
    return parts


def panels() -> list[dict]:
    parts = []
    y0, y1 = 1.75, SEAM - 1.75
    parts += frame("south", 2.55, 6.05, y0, y1)
    parts += frame("south", 9.95, 13.45, y0, y1)
    for face in ("north", "east", "west"):
        parts += frame(face, 2.55, 13.45, y0, y1)
    # the lid's front panel frame (no gem: the emblem rises into it)
    z0, z1 = L1, L1 + 0.22
    for (a, b, c, d) in ((2.4, 13.6, SEAM + 0.55, SEAM + 0.95), (2.4, 13.6, LID_TOP - 1.35, LID_TOP - 0.95),
                         (2.4, 2.8, SEAM + 0.95, LID_TOP - 1.35), (13.2, 13.6, SEAM + 0.95, LID_TOP - 1.35)):
        parts.append(box((a, c, z0), (b, d, z1), "rail"))
    parts.append(box((2.8, SEAM + 0.95, L1), (13.2, LID_TOP - 1.35, L1 + 0.1), "enamel"))
    # lattice panels on the lid's sides and back, between the corner posts, under a rail
    y0, y1 = SEAM + 0.55, LID_TOP - 0.95
    parts.append(box((2.4, y0, L0 - 0.1), (13.6, y1, L0), "enamel", skip=("south",)))
    parts.append(box((L1, y0, 2.4), (L1 + 0.1, y1, 13.6), "enamel", skip=("west",)))
    parts.append(box((L0 - 0.1, y0, 2.4), (L0, y1, 13.6), "enamel", skip=("east",)))
    parts.append(box((L1, y0 - 0.4, 2.4), (L1 + 0.2, y0, 13.6), "rail", skip=("west",)))
    parts.append(box((L0 - 0.2, y0 - 0.4, 2.4), (L0, y0, 13.6), "rail", skip=("east",)))
    for x in (4.0, 12.0):
        parts.append(box((x - 0.3, SEAM + 2.15, z1), (x + 0.3, SEAM + 2.75, z1 + 0.14), "stud", uv="full"))
    return parts


def stepped_lid() -> list[dict]:
    """Two enamel steps with silver edges and a silver top plate."""
    a0, a1 = L0 + STEP1, L1 - STEP1
    b0, b1 = L0 + STEP2, L1 - STEP2
    parts = [box((a0, LID_TOP, a0), (a1, STEP1_TOP, a1), "enamel_plain", faces={"up": "enamel"}, skip=("down",)),
             box((b0, STEP1_TOP, b0), (b1, STEP2_TOP, b1), "silver", skip=("down",))]
    # a sloped chamfer from the lid's edge up onto the first step, each side one turned slab
    lo, hi = L0 + 0.35, L1 - 0.35
    for side in range(4):
        slab = box((lo, LID_TOP - 0.2, a1 - 0.6), (hi, LID_TOP + 0.35, hi + 0.15), "enamel_plain",
                   faces={"up": "rail"}, skip=("down",))
        turn(slab, -32, "x", (8.0, LID_TOP, hi))
        turn(slab, 90 * side, "y", (8.0, 8.0, 8.0))
        parts.append(slab)
    e = 0.12
    # silver edging round the first step's top
    for (x0, z0, x1, z1) in ((a0 - e, a0 - e, a1 + e, a0 + 0.45), (a0 - e, a1 - 0.45, a1 + e, a1 + e),
                             (a0 - e, a0 + 0.45, a0 + 0.45, a1 - 0.45), (a1 - 0.45, a0 + 0.45, a1 + e, a1 - 0.45)):
        parts.append(box((x0, STEP1_TOP - 0.4, z0), (x1, STEP1_TOP + 0.06, z1), "rail", skip=("down",)))
    # studs round the top plate
    for k in range(4):
        u = b0 + 0.9 + k * (b1 - b0 - 1.8) / 3
        for (x, z) in ((u, b0 + 0.6), (u, b1 - 0.6), (b0 + 0.6, u), (b1 - 0.6, u)):
            if k in (0, 3) and (x, z) in ((b0 + 0.6, u), (b1 - 0.6, u)):
                continue
            parts.append(box((x - 0.25, STEP2_TOP, z - 0.25), (x + 0.25, STEP2_TOP + 0.14, z + 0.25), "stud", uv="full"))
    return parts


def crown_gem() -> list[dict]:
    """The big cut sapphire on the lid, in an octagonal bezel held by four claws."""
    cx, cz = 8.0, 8.0
    y = STEP2_TOP
    parts = []
    parts += prism((cx, y + 0.4, cz), 2.6, 0.8, "silver", sides=8, cap="silver")
    parts += prism((cx, y + 1.0, cz), 2.25, 0.4, "rail", sides=8, cap="silver")
    # the gem: pavilion into the bezel, girdle, crown and table
    parts += lathe((cx, y + 1.0, cz), [(0.0, 0.9), (0.9, 2.0), (1.4, 2.3), (2.2, 1.9), (2.8, 1.15)],
                   "sapphire", sides=8, cap="sapphire", glow=6)
    for k in range(4):
        a = math.radians(45 + 90 * k)
        ox, oz = math.cos(a), math.sin(a)
        p0 = (cx + 2.55 * ox, y + 0.7, cz + 2.55 * oz)
        p1 = (cx + 2.5 * ox, y + 2.4, cz + 2.5 * oz)
        p2 = (cx + 2.05 * ox, y + 2.85, cz + 2.05 * oz)
        parts.append(bar(p0, p1, 0.55, 0.5, "silver"))
        parts.append(bar(p1, p2, 0.45, 0.45, "silver"))
        parts.append(box((p2[0] - 0.22, p2[1] - 0.1, p2[2] - 0.22), (p2[0] + 0.22, p2[1] + 0.25, p2[2] + 0.22), "stud",
                         uv="full"))
    return parts


def emblem_lock() -> list[dict]:
    """The silver diamond plate across the seam with a faceted sapphire and the keyhole."""
    gx, gy = GEM_C
    z0, z1 = B1 - 0.05, 16.02
    parts = []
    plate = box((gx - 2.3, gy - 2.3, z0), (gx + 2.3, gy + 2.3, z1 - 0.06), "plate", uv="full",
                faces={"south": ("plate", [0, 0, 16, 16])})
    turn(plate, 45, "z", (gx, gy, z0))
    parts.append(plate)
    # small silver wings either side of the diamond
    for side in (-1, 1):
        w = box((gx + side * 2.7 - 0.9, gy - 0.4, z0), (gx + side * 2.7 + 0.9, gy + 0.4, z1 - 0.1), "rail")
        parts.append(w)
        tip = box((gx + side * 3.7 - 0.35, gy - 0.35, z0), (gx + side * 3.7 + 0.35, gy + 0.35, z1 - 0.1), "silver")
        turn(tip, 45, "z", (gx + side * 3.7, gy, z0))
        parts.append(tip)
    # the octagonal bezel and the gem
    parts += arc((gx, gy + 0.55, z1 + 0.06), 1.45, 22.5, 382.5, 8, 0.42, 0.24, "silver", plane="xy")
    parts += prism((gx, gy + 0.55, z1 + 0.07), 1.25, 0.22, "sapphire", axis="z", sides=8, cap="sapphire", glow=6)
    parts += prism((gx, gy + 0.55, z1 + 0.2), 0.7, 0.06, "sapphire", axis="z", sides=8, cap="sapphire", glow=7)
    # the keyhole under the gem
    parts.append(box((gx - 0.3, gy - 2.15, z1 - 0.06), (gx + 0.3, gy - 1.55, z1 - 0.02), "keyhole", glow=10,
                     shade=False, uv="full"))
    parts.append(box((gx - 0.16, gy - 1.6, z1 - 0.06), (gx + 0.16, gy - 1.0, z1 - 0.02), "keyhole", glow=10,
                     shade=False, uv="full"))
    return parts


def back_hinges() -> list[dict]:
    parts = []
    for x in (4.2, 11.8):
        parts += prism((x, SEAM, L0 - 0.02), 0.52, 2.6, "silver", axis="x", sides=8, cap="stud")
        parts += prism((x, SEAM, L0 - 0.02), 0.24, 3.1, "stud", axis="x", sides=8, cap="stud")
        parts.append(box((x - 1.0, SEAM - 2.4, B0 - 0.28), (x + 1.0, SEAM - 0.3, B0), "rail"))
        parts.append(box((x - 1.0, SEAM + 0.3, L0 - 0.28), (x + 1.0, SEAM + 3.0, L0), "rail"))
    return parts


def crystals() -> list[dict]:
    """Pale blue crystal clusters growing from the lid's two back corners: one big upright
    shard and two shorter ones, each with a pointed tip. They stay inside the block."""
    parts = []
    shards = ((2.6, 1.4, 0.15, 0.12, 0), (1.8, 1.15, 0.38, 0.1, 35), (1.5, 1.05, 0.08, 0.36, -30))
    for cx in (L0 + 1.85, L1 - 1.85):
        out = -1 if cx < 8 else 1
        bx, by, bz = cx, LID_TOP + 0.6, L0 + 1.85
        for (h, w, lx, lz, roll) in shards:
            dx, dz = out * lx, -lz
            n = math.sqrt(dx * dx + 1 + dz * dz)
            base = (bx + dx * 1.6, by, bz + dz * 1.6)
            tip = (base[0] + dx / n * h, base[1] + h / n, base[2] + dz / n * h)
            parts.append(bar(base, tip, w, w, "crystal", roll=45 + roll, glow=7))
            tip2 = (tip[0] + dx / n * w * 0.6, tip[1] + w * 0.6 / n, tip[2] + dz / n * w * 0.6)
            parts.append(bar(tip, tip2, w * 0.55, w * 0.55, "crystal", roll=45 + roll, glow=7))
            parts.append(bar(tip2, (tip2[0] + dx / n * w * 0.4, tip2[1] + w * 0.45 / n, tip2[2] + dz / n * w * 0.4),
                             w * 0.22, w * 0.22, "crystal", roll=45 + roll, glow=7))
    return parts


def models() -> dict:
    p = shell("enamel_plain", "enamel_plain", "enamel_plain")
    p += rims("rail")
    p += [box((B0 - 0.3, SEAM - 1.05, B0 - 0.3), (B1 + 0.3, SEAM - 0.15, B1 + 0.3), "rail", skip=("up", "down"))]
    p += pillars()
    p += panels()
    p += stepped_lid()
    p += crown_gem()
    p += emblem_lock()
    p += back_hinges()
    p += crystals()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.5))}
