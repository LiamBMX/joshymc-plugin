"""Campfire Crate (issue #1389): the placed crate chest of the Campfire crate, a fire-scorched chest.

The body and lid are fire-darkened oak boards, a few ember cracks glowing and breathing
along the base and the lid seam (a self-lit overlay, so only the cracks light up at
night), bound in forged copper-bronze, with a raised bronze frame on the lid's front: base, seam and lid bands, corner brackets, back hinges and a bronze lock plate
whose keyhole glows like a coal. On the lid a campfire burns inside a ring of
fieldstones: four split logs lean together in a teepee over a bed of glowing coals,
three crossed sheets of flame rise out of it, a roasting stick with a marshmallow leans
on the ring, and three ring-cut firewood logs lie stacked at the back left.

Animation (one loop): the flames dance (heat bands roll up each tongue, sparks shoot
up), the coals and the ember cracks breathe out of step with each other, the burning
log ends flicker, and the keyhole smoulders.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py so the three crate
chests share their footprint and hardware.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM, chunk, corner_brackets,  # noqa: F401
                                    hinges, keyhole_glow, lid_frame, lock, lock_plate, metal, only, planks, put, rims,
                                    round_log, shell)
from art.kit import animate, bar, box, canvas, display, model, prism, save, save_animation, turn

ID = "campfire_crate"
NAME = "Campfire Crate"

CHAR = ["#170f0b", "#271a12", "#38261a", "#4a3222", "#5c3f2b", "#704f37", "#866247"]
EMBER = ["#5a1206", "#9a2a08", "#d9520e", "#f7861e", "#ffb444", "#ffdc84", "#fff4cf"]
BRONZE = ["#1c0c05", "#3a1a0a", "#5a2c12", "#7c3e19", "#8c4a1f", "#b0662d", "#f0b26a"]
STONE = ["#2a2729", "#3f3b3c", "#565051", "#6f6868", "#8a8282", "#a59c9a"]
BARK = ["#1a100a", "#2b1b10", "#3f2a18", "#553a22", "#6c4c2e", "#86613c"]
WOOD = ["#6b4424", "#8c5e34", "#ad7c48", "#c79a62", "#ddb882"]
MALLOW = ["#c9b9a8", "#e6dccf", "#f7f1e8", "#ffffff"]
TOAST = ["#6b3a14", "#9a5a22", "#c4843a"]

# (x0, y0, x1, y1) of the glowing cracks, in 32 px texture space, per texture
CRACKS = {
    # body: v 0 is just under the seam, v 31 the base
    # short embers along the board seams (rows 7 and 23 of the 4-board texture)
    "body": [((3, 6), (5, 7), (7, 6)), ((20, 7), (23, 6)), ((8, 22), (10, 23), (12, 22)), ((24, 23), (27, 22))],
    # lid: v 31 is the seam
    "lid": [((4, 29), (6, 30), (8, 29)), ((22, 30), (25, 29))],
}

# ======================================================================================
# Textures
# ======================================================================================

def charred(seed: int) -> object:
    """32 px scorched boards: alligator-cracked char with dark seams and ashy highlights."""
    img = planks(CHAR, size=32, rows=4, seed=seed, knots=1)
    rng = random.Random(seed + 40)
    for y in range(32):
        for x in range(32):
            if (x * 7 + y * 3 + seed) % 23 == 0 and y % 8 not in (0, 7):
                put(img, x, y, CHAR[1])              # a few alligator checks in the char
            elif rng.random() < 0.04:
                put(img, x, y, CHAR[6])              # ash catching the light
    return img


def cracks(kind: str, t: float) -> object:
    """32 px overlay: only the ember cracks, glowing and breathing (the rest transparent)."""
    img = canvas(32)
    pulse = 0.5 + 0.5 * math.sin(2 * math.pi * (t + (0.37 if kind == "lid" else 0.0)))
    for i, path in enumerate(CRACKS[kind]):
        local = 0.5 + 0.5 * math.sin(2 * math.pi * (t + i * 0.23))
        heat = 0.35 + 0.4 * pulse + 0.25 * local
        for (x0, y0), (x1, y1) in zip(path, path[1:]):
            steps = max(abs(x1 - x0), abs(y1 - y0))
            for s in range(steps + 1):
                x = round(x0 + (x1 - x0) * s / steps)
                y = round(y0 + (y1 - y0) * s / steps)
                put(img, x, y, EMBER[min(6, int(2 + heat * 4))])
                put(img, x, y + 1, EMBER[min(6, int(1 + heat * 3))])
    return img


def fire(t: float) -> object:
    """32 px sheet of fire: three tongues of different heights, heat rolling up, sparks."""
    img = canvas(32)
    best: dict[tuple[int, int], int] = {}      # hottest tone painted at each pixel so far
    tongues = ((9, 0.95, 0.0, 6.5), (17, 1.0, 0.33, 8.0), (24, 0.8, 0.66, 6.0))
    for cx, height, phase, half in tongues:
        for y in range(32):
            fy = 1 - (y + 0.5) / 32                      # 0 bottom .. 1 top
            if fy > height:
                continue
            h = fy / height
            sway = 1.6 * math.sin(2 * math.pi * (t * 2 + phase + h * 0.9)) * h
            width = half * (1 - h ** 1.4) + 0.6
            for x in range(32):
                dx = abs(x + 0.5 - cx - sway)
                if dx > width:
                    continue
                core = 1 - dx / width
                band = 0.5 + 0.5 * math.sin(2 * math.pi * (h * 2.2 - t * 2 + phase))
                heat = core * (1.05 - h * 0.8) + 0.2 * band
                idx = max(0, min(6, int(heat * 6.2)))
                if idx > best.get((x, y), -1):
                    best[(x, y)] = idx
                    put(img, x, y, EMBER[idx])
    rng = random.Random(3)
    for k in range(6):
        ph = (t * 2 + k / 6) % 1
        sx = int(6 + rng.random() * 20 + math.sin(ph * 7 + k) * 2)
        sy = int(31 * (1 - ph) - 4)
        if 0 <= sy < 32:
            put(img, sx, sy, EMBER[5 if ph < 0.6 else 3])
    return img


def coals(t: float) -> object:
    """16 px bed of coals: black lumps with glowing seams, breathing."""
    img = canvas(16)
    rng = random.Random(9)
    pulse = 0.5 + 0.5 * math.sin(2 * math.pi * t)
    for y in range(16):
        for x in range(16):
            lump = ((x // 3 + y // 3) * 7 + (x // 3) * (y // 3)) % 5
            seam = x % 3 == 0 or y % 3 == 0
            if seam:
                local = 0.5 + 0.5 * math.sin(2 * math.pi * (t + lump * 0.2))
                heat = 0.4 * pulse + 0.6 * local
                put(img, x, y, EMBER[min(6, int(1 + heat * 4.5))])
            else:
                put(img, x, y, (CHAR[0], CHAR[1], CHAR[2], CHAR[1], CHAR[0])[lump] if rng.random() > 0.1 else EMBER[1])
    return img


def log_end(t: float) -> object:
    """16 px burning end of a log: rings charring to a glowing rim."""
    img = canvas(16)
    pulse = 0.5 + 0.5 * math.sin(2 * math.pi * (t + 0.5))
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if d > 6.6:
                c = EMBER[min(6, int(2 + pulse * 3))]
            elif d > 5.2:
                c = CHAR[1]
            else:
                c = WOOD[1 + (int(d * 1.3) % 2)] if d > 1 else WOOD[0]
            put(img, x, y, c)
    return img


def bark() -> object:
    img = canvas(16)
    rng = random.Random(2)
    for y in range(16):
        for x in range(16):
            tone = (3, 4, 4, 3, 2, 2, 1, 2)[y % 8]
            if rng.random() < 0.25:
                tone = max(0, tone - 1)
            put(img, x, y, BARK[tone])
    return img


def split_wood() -> object:
    """16 px split face of firewood: pale fibres along u."""
    img = canvas(16)
    rng = random.Random(5)
    for y in range(16):
        for x in range(16):
            put(img, x, y, WOOD[2 + (1 if rng.random() < 0.3 else 0) - (1 if y % 5 == 0 else 0)])
    return img


def wood_end() -> object:
    """16 px sawn end of a firewood log: pale rings in a bark rim."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if d > 6.8:
                c = BARK[2]
            elif d > 6.0:
                c = BARK[4]
            else:
                c = WOOD[2 + (int(d * 1.2) % 2)] if d > 1.1 else WOOD[0]
            put(img, x, y, c)
    return img


def marshmallow() -> object:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            c = MALLOW[1 + (x + y) % 2] if y > 4 else TOAST[(x + y) % 3]
            if y in (5, 6) and x % 3 == 0:
                c = TOAST[2]
            put(img, x, y, c)
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(EMBER, t)


def textures() -> None:
    save(charred(3), "char")
    save(charred(17), "char_lid")
    save_animation(animate(lambda t: cracks("body", t), 12), "cracks", frametime=3, interpolate=True)
    save_animation(animate(lambda t: cracks("lid", t), 12), "cracks_lid", frametime=3, interpolate=True)
    save(metal(BRONZE), "bronze")
    save(lock_plate(BRONZE), "bronze_plate")
    save(chunk(STONE, seed=8, density=0.5), "stone")
    save(bark(), "bark")
    save(split_wood(), "split")
    save(wood_end(), "wood_end")
    save(marshmallow(), "mallow")
    save(chunk(TOAST, seed=4, density=0.4), "stick")
    save_animation(animate(fire, 16), "fire", frametime=2)
    save_animation(animate(coals, 12), "coals", frametime=3, interpolate=True)
    save_animation(animate(log_end, 12), "log_end", frametime=3, interpolate=True)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def ember_overlays() -> list[dict]:
    """Self-lit crack overlays a hair in front of every visible wall of the body and lid."""
    e = 0.03
    parts = []
    for (lo, hi, y0, y1, tex) in ((B0, B1, 1.0, SEAM - 0.9, "cracks"), (L0, L1, SEAM + 0.1, LID_TOP - 0.9, "cracks_lid")):
        for side in ("south", "north", "east", "west"):
            if side in ("south", "north"):
                z = hi + e if side == "south" else lo - e
                if side == "south":
                    # leave the lock plate's area to the plate
                    for u0, u1 in ((lo + 0.4, 5.9), (10.1, hi - 0.4)):
                        parts.append(only((u0, y0, z), (u1, y1, z), {side: (tex, [u0, 0, u1, 16])},
                                          glow=9, shade=False))
                    continue
                parts.append(only((lo + 0.4, y0, z), (hi - 0.4, y1, z), {side: (tex, [16, 0, 0, 16])},
                                  glow=9, shade=False))
            else:
                x = hi + e if side == "east" else lo - e
                flip = [0, 16, 16, 0] if side == "east" else [16, 16, 0, 0]     # each wall cracks differently
                parts.append(only((x, y0, lo + 0.4), (x, y1, hi - 0.4), {side: (tex, flip)},
                                  glow=9, shade=False))
    return parts


def fire_ring() -> list[dict]:
    cx, cz = 8.4, 8.6
    y0 = LID_TOP
    parts = []
    rng = random.Random(6)
    # stones round the fire
    for i in range(10):
        a = 2 * math.pi * i / 10
        r = 4.6
        sx, sz = cx + r * math.cos(a), cz + r * math.sin(a)
        w = 1.0 + rng.uniform(0.0, 0.35)
        h = 1.0 + rng.uniform(0.0, 0.6)
        s = box((sx - w, y0, sz - w * 0.8), (sx + w, y0 + h, sz + w * 0.8), "stone")
        turn(s, math.degrees(-a) + rng.uniform(-12, 12), "y", (sx, y0, sz))
        parts.append(s)
    # coal bed (self-lit)
    parts.append(box((cx - 3.3, y0, cz - 3.3), (cx + 3.3, y0 + 0.5, cz + 3.3), "coals", glow=12, shade=False,
                     uv="full"))
    parts.append(box((cx - 2.0, y0 + 0.5, cz - 2.0), (cx + 2.0, y0 + 0.9, cz + 2.0), "coals", glow=13, shade=False,
                     uv="full"))
    # four split logs leaning together in a teepee
    for i in range(4):
        a = math.radians(45 + 90 * i)
        foot = (cx + 3.4 * math.cos(a), y0 + 0.5, cz + 3.4 * math.sin(a))
        top = (cx + 0.5 * math.cos(a), y0 + 5.0, cz + 0.5 * math.sin(a))
        log = bar(foot, top, 1.1, 1.1, "bark", faces={"down": ("log_end", [0, 0, 16, 16]),
                                                     "up": ("log_end", [0, 0, 16, 16])})
        parts.append(log)
    # three crossed sheets of fire
    for k, ang in enumerate((0, 60, 120)):
        sheet = only((cx - 3.6, y0 + 0.4, cz), (cx + 3.6, y0 + 7.4, cz),
                     {"north": ("fire", [0, 0, 16, 16]), "south": ("fire", [0, 0, 16, 16])}, glow=15, shade=False)
        turn(sheet, ang + 15, "y", (cx, y0, cz))
        parts.append(sheet)
    return parts


def roasting_stick() -> list[dict]:
    """A green stick leaning on the ring at the front right, a toasted marshmallow on its tip."""
    base = (14.6, LID_TOP + 0.2, 14.4)
    tip = (10.6, LID_TOP + 3.3, 10.4)
    parts = [bar(base, tip, 0.32, 0.32, "stick")]
    d = [tip[i] - base[i] for i in range(3)]
    n = math.sqrt(sum(v * v for v in d))
    u = [v / n for v in d]
    c = [tip[i] - u[i] * 0.4 for i in range(3)]
    parts.append(bar([c[i] - u[i] * 0.75 for i in range(3)], [c[i] + u[i] * 0.75 for i in range(3)],
                     1.2, 1.2, "mallow", uv="full"))
    return parts


def firewood() -> list[dict]:
    """Three split logs stacked on the back left of the lid, ring-cut ends showing."""
    parts = []
    for (y, z) in ((LID_TOP + 0.85, 2.0), (LID_TOP + 0.85, 3.9), (LID_TOP + 2.35, 2.95)):
        parts += round_log((3.2, y, z), 0.9, 4.6, "x", "bark", "wood_end")
    # the split face of the top log, pale fibres catching the firelight
    parts.append(box((1.0, LID_TOP + 2.9, 2.4), (5.4, LID_TOP + 3.12, 3.5), "split", skip=("down",)))
    return parts


def emblems() -> list[dict]:
    """A forged bronze flame either side of the hasp with an ember-lit heart (the set's twin lid emblems)."""
    z = L1
    parts = []
    for cx in (4.4, 11.6):
        y = SEAM + 1.1
        for k, (w, h, dx) in enumerate(((1.5, 0.9, 0.0), (1.15, 0.8, 0.15), (0.8, 0.75, -0.1), (0.45, 0.7, 0.2),
                                         (0.2, 0.55, 0.05))):
            parts.append(box((cx + dx - w, y, z), (cx + dx + w, y + h, z + 0.35 + k * 0.05), "bronze"))
            y += h - 0.05
        parts.append(box((cx - 0.45, SEAM + 1.5, z + 0.36), (cx + 0.45, SEAM + 2.5, z + 0.5), "keyhole", glow=12,
                         shade=False, uv="full"))
    return parts


def sparks() -> list[dict]:
    """Embers hanging in the air over the fire."""
    out = []
    for (x, y, z, s) in ((7.1, 7.9, 8.0, 0.3), (9.6, 8.6, 9.4, 0.25), (8.8, 9.3, 7.6, 0.2)):
        out.append(box((x - s, LID_TOP + y - s, z - s), (x + s, LID_TOP + y + s, z + s), "keyhole", glow=15,
                       shade=False, uv="full"))
    return out


def models() -> dict:
    p = shell("char", "char_lid", "char_lid")
    p += ember_overlays()
    p += rims("bronze")
    p += corner_brackets("bronze")
    p += lock("bronze_plate", "keyhole", "bronze", glow=12)
    p += hinges("bronze")
    p += lid_frame("bronze")
    p += emblems()
    p += fire_ring()
    p += sparks()
    p += roasting_stick()
    p += firewood()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.5))}
