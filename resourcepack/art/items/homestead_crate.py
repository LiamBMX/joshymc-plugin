"""Homestead Crate (issue #1389): the placed crate chest of the Homestead crate, a farmstead barn chest.

The body is weathered barn-red vertical boards framed in whitewashed trim boards, with white X
braces across the two front panels (either side of the lock) and the side walls, like
barn doors. Wrought-iron corner brackets, strap hinges and an iron lock plate with a
warm-lit keyhole hold it together. The lid is natural oak boards banded in white with a brass
lucky horseshoe (heels up) nailed either side of the hasp. On top sits the harvest: a
golden hay bale tied with twine at the back left, a tied sheaf of wheat standing at the
back right, and a ribbed pumpkin with a curling vine leaf at the front right.

An iron barn lantern stands on the lid's front-left corner.

Animation (one loop): the lantern flickers, a breeze of light ripples up the wheat heads, the horseshoes catch
a glint, and the keyhole breathes like a lamp in the barn.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py so the three crate
chests share their footprint and hardware.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM, chunk, corner_brackets,  # noqa: F401
                                    hinges, keyhole_glow, lock, lock_plate, metal, only, planks, put, rims, shell,
                                    top_frame)
from art.items.cabin_crate import lantern, lantern_glass
from art.kit import animate, arc, bar, box, canvas, display, model, prism, save, save_animation, shine, turn

ID = "homestead_crate"
NAME = "Homestead Crate"

RED = ["#33140f", "#4f1f17", "#6c2b1f", "#843a28", "#9a4c33", "#ad6243", "#c07d5c"]
OAK = ["#3a2412", "#583a1e", "#76522c", "#936a3a", "#ad8449", "#c49e5e", "#d9b878"]
WHITE = ["#857d71", "#aaa292", "#cbc4b3", "#e3ddcf", "#f3efe4", "#fffdf6"]
IRON = ["#100e10", "#1d1a1d", "#2d292d", "#403a3e", "#575056", "#716a70"]
BRASS = ["#3b2306", "#68420e", "#966618", "#c08e2c", "#dfb446", "#f4d87a", "#fff2bc"]
HAY = ["#5e420f", "#86631b", "#b08827", "#d0a93a", "#e6c557", "#f5de88"]
PUMPKIN = ["#4e1804", "#7f2b08", "#b5480e", "#d96616", "#f08a2a", "#ffae55"]
LEAF = ["#183110", "#28501a", "#3d7024", "#568f30", "#74ad42"]
TWINE = ["#4a3218", "#6e4c26", "#8f6936", "#ad8a52"]
LAMP = ["#4a1d06", "#8c3f0a", "#cf7414", "#f3a12e", "#ffc95a", "#ffe594", "#fff6d6"]


# ======================================================================================
# Textures
# ======================================================================================

def trim() -> object:
    """16 px whitewashed board: brushed paint with a few worn streaks showing grey wood."""
    img = canvas(16)
    rng = random.Random(6)
    for y in range(16):
        for x in range(16):
            tone = 3 if rng.random() > 0.25 else rng.choice((2, 4))
            if y == 0:
                tone = 5
            elif y == 15:
                tone = 1
            put(img, x, y, WHITE[tone])
    for _ in range(4):
        y = rng.randrange(2, 14)
        x = rng.randrange(16)
        for k in range(rng.randint(2, 5)):
            put(img, (x + k) % 16, y, WHITE[1])
    return img


def hay(seed: int = 2) -> object:
    """32 px baled straw: dense strands running along u, lit and shaded strand by strand."""
    img = canvas(32)
    rng = random.Random(seed)
    for y in range(32):
        for x in range(32):
            put(img, x, y, HAY[2 + ((y * 7 + x // 5) % 3 == 0)])
    for _ in range(140):
        y = rng.randrange(32)
        x = rng.randrange(32)
        tone = rng.choice((1, 3, 4, 4, 5))
        for k in range(rng.randint(3, 9)):
            put(img, (x + k) % 32, (y + (k // 4) * rng.choice((0, 0, 1))) % 32, HAY[tone])
    return img


def hay_end() -> object:
    """16 px cut end of the bale: short stubbly straw ends."""
    img = canvas(16)
    rng = random.Random(14)
    for y in range(16):
        for x in range(16):
            put(img, x, y, HAY[rng.choice((1, 2, 2, 3, 3, 4))])
    return img


def twine() -> object:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            twist = 2 if (x + y) % 4 < 2 else 1      # the strands' diagonal twist
            put(img, x, y, TWINE[min(3, twist + (1 if y < 3 else 0))])
    return img


def wheat(t: float) -> object:
    """16 px wheat ear: plump stacked kernels with awns, a light ripple rising through it."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            kernel = (x % 4 in (1, 2)) and (y % 3 != 2)
            tone = 4 if kernel else 2
            if x % 4 == 0:
                tone = 1
            put(img, x, y, HAY[tone])
    ripple = (t * 16) % 16
    for y in range(16):
        d = min(abs(y - ripple), 16 - abs(y - ripple))
        if d < 2.5:
            for x in range(16):
                if x % 4 in (1, 2) and y % 3 != 2:
                    put(img, x, y, HAY[5])
    return img


def stalk() -> object:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            put(img, x, y, HAY[(2, 3, 4, 3)[x % 4]] if y % 7 else HAY[1])
    return img


def pumpkin() -> object:
    """16 px pumpkin rind: deep ribs and lit bulges."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            rib = x % 4
            tone = (1, 3, 4, 3)[rib]
            if y < 2:
                tone -= 1
            elif y > 13:
                tone -= 1
            elif 4 < y < 9 and rib == 2:
                tone = 5
            put(img, x, y, PUMPKIN[max(0, tone)])
    return img


def pumpkin_top() -> object:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            a = math.atan2(y + 0.5 - 8, x + 0.5 - 8)
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            rib = int((a + math.pi) / (2 * math.pi) * 8) % 2
            put(img, x, y, PUMPKIN[(3 + rib) if d > 3 else 2])
    return img


def leaf() -> object:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            vein = x == 8 or abs(x - 8) == abs(y - 9) // 2 and y > 4
            put(img, x, y, LEAF[1] if vein else LEAF[3 if (x + y) % 5 else 2])
    return img


def horseshoe(t: float) -> object:
    """16 px brass with a glint sweeping across it."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 4 if y < 6 else 3 if y < 11 else 2
            if (x + y) % 6 == 0:
                tone += 1
            put(img, x, y, BRASS[tone])
    shine(img, t, colour="#fff6cf", width=3.0, strength=0.75)
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(LAMP, t)


def textures() -> None:
    save(planks(RED, size=32, rows=4, seed=5, vertical=True, knots=2), "barn")
    save(planks(OAK, size=32, rows=4, seed=13, vertical=False, knots=3), "barn_lid")
    save(trim(), "trim")
    save(metal(IRON, rivets=(3, 12)), "iron")
    save(lock_plate(IRON), "iron_plate")
    save(hay(), "hay")
    save(hay_end(), "hay_end")
    save(twine(), "twine")
    save(stalk(), "stalk")
    save(pumpkin(), "pumpkin")
    save(pumpkin_top(), "pumpkin_top")
    save(leaf(), "leaf")
    save(chunk(LEAF, seed=3, density=0.4), "vine")
    save_animation(animate(wheat, 12), "wheat", frametime=3)
    save_animation(animate(horseshoe, 16), "horseshoe", frametime=3)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)
    save_animation(animate(lantern_glass, 12), "lantern_glass", frametime=2, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def x_panel(face: str, u0: float, u1: float, y0: float, y1: float, plane: float) -> list[dict]:
    """A whitewashed frame with an X brace on one wall. u runs along the wall (x for
    north/south walls, z for east/west), plane is the wall's outer coordinate."""
    t, d = 0.55, 0.26          # frame front stays clear of the corner brackets (no z-fighting)
    out_sign = {"south": 1, "north": -1, "east": 1, "west": -1}[face]
    p0, p1 = plane, plane + d * out_sign
    lo, hi = min(p0, p1), max(p0, p1)

    def slab(a0, a1, b0, b1, deeper=0.0):
        hi2 = hi + deeper if out_sign > 0 else hi
        lo2 = lo - deeper if out_sign < 0 else lo
        if face in ("south", "north"):
            return box((a0, b0, lo2), (a1, b1, hi2), "trim")
        return box((lo2, b0, a0), (hi2, b1, a1), "trim")

    parts = [slab(u0, u1, y0, y0 + t), slab(u0, u1, y1 - t, y1), slab(u0, u0 + t, y0, y1), slab(u1 - t, u1, y0, y1)]
    # the X: two diagonal boards a little prouder than the frame
    mid = plane + (d + 0.1) * out_sign
    for a, b in (((u0 + t, y0 + t), (u1 - t, y1 - t)), ((u0 + t, y1 - t), (u1 - t, y0 + t))):
        if face in ("south", "north"):
            parts.append(bar((a[0], a[1], mid), (b[0], b[1], mid), 0.55, 0.3, "trim"))
        else:
            parts.append(bar((mid, a[1], a[0]), (mid, b[1], b[0]), 0.3, 0.55, "trim"))
    return parts


def walls() -> list[dict]:
    parts = []
    y0, y1 = 0.9, SEAM - 0.8
    parts += x_panel("south", 1.0, 6.0, y0, y1, B1)
    parts += x_panel("south", 10.0, 15.0, y0, y1, B1)
    parts += x_panel("north", 1.0, 15.0, y0, y1, B0)
    parts += x_panel("east", 1.0, 15.0, y0, y1, B1)
    parts += x_panel("west", 1.0, 15.0, y0, y1, B0)
    return parts


def horseshoes() -> list[dict]:
    parts = []
    z = L1 + 0.05
    for cx in (4.3, 11.7):
        cy = SEAM + 2.7
        parts += arc((cx, cy, z + 0.2), 1.35, 150, 390, 7, 0.6, 0.35, "horseshoe", plane="xy")
        # heels, and nail heads
        for hx in (cx - 1.25, cx + 1.25):
            parts.append(box((hx - 0.38, cy + 0.55, z), (hx + 0.38, cy + 0.95, z + 0.45), "horseshoe"))
        for a in (200, 240, 300, 340):
            r = math.radians(a)
            nx, ny = cx + 1.35 * math.cos(r), cy + 1.35 * math.sin(r)
            parts.append(box((nx - 0.13, ny - 0.13, z + 0.35), (nx + 0.13, ny + 0.13, z + 0.47), "iron"))
    return parts


def hay_bale() -> list[dict]:
    x0, x1 = 1.4, 8.6
    z0, z1 = 1.4, 5.8
    y0, y1 = LID_TOP, LID_TOP + 3.8
    parts = [box((x0, y0, z0), (x1, y1, z1), "hay", faces={"east": "hay_end", "west": "hay_end"})]
    # a few strands sticking out of the top and the ends
    rng = random.Random(4)
    for _ in range(6):
        x = rng.uniform(x0 + 0.5, x1 - 0.5)
        z = rng.uniform(z0 + 0.3, z1 - 0.3)
        parts.append(bar((x, y1 - 0.2, z), (x + rng.uniform(-0.8, 0.8), y1 + 0.7, z + rng.uniform(-0.6, 0.6)),
                         0.18, 0.18, "stalk"))
    for tx in (3.3, 6.7):
        parts.append(box((tx - 0.25, y0 - 0.01, z0 - 0.08), (tx + 0.25, y1 + 0.08, z1 + 0.08), "twine",
                         skip=("down",)))
    return parts


def sheaf() -> list[dict]:
    """A tied sheaf of wheat standing on the back-right of the lid, stalks fanned out."""
    cx, cz = 12.0, 4.0
    base = LID_TOP
    parts = []
    rng = random.Random(11)
    # a solid core of stalks so the bundle reads as one sheaf, flared at the foot
    parts += prism((cx, base + 1.2, cz), 1.25, 2.4, "stalk", sides=8, cap="hay_end")
    parts += prism((cx, base + 3.4, cz), 0.85, 2.2, "stalk", sides=8, cap="hay_end")
    for ring, (count, r_top, rise) in enumerate(((10, 1.8, 4.5), (6, 0.8, 5.2))):
        for i in range(count):
            a = 2 * math.pi * (i + ring * 0.5) / count + rng.uniform(-0.15, 0.15)
            r_bot = 1.0 if ring == 0 else 0.4
            top_y = base + rise + rng.uniform(-0.4, 0.4)
            bot = (cx + r_bot * math.cos(a), base, cz + r_bot * math.sin(a))
            waist = (cx + 0.6 * math.cos(a) * (1 - ring * 0.6), base + 2.4, cz + 0.6 * math.sin(a) * (1 - ring * 0.6))
            top = (cx + r_top * math.cos(a), top_y, cz + r_top * math.sin(a))
            if ring == 0:
                parts.append(bar(bot, waist, 0.38, 0.38, "stalk"))
            parts.append(bar(waist, top, 0.36, 0.36, "stalk"))
            # the ear, leaning out from the top of its stalk, with two awns
            lean = 0.7 if ring == 0 else 0.25
            tip = (top[0] + lean * math.cos(a), top[1] + 2.3, top[2] + lean * math.sin(a))
            parts.append(bar(top, tip, 0.85, 0.85, "wheat", uv="full"))
            awn = (tip[0] + 0.5 * lean * math.cos(a), tip[1] + 1.0, tip[2] + 0.5 * lean * math.sin(a))
            parts.append(bar(tip, awn, 0.14, 0.14, "stalk"))
    # the twine knot round the waist
    parts += prism((cx, base + 2.4, cz), 0.95, 0.7, "twine", sides=8)
    return parts


def pumpkin_on_lid() -> list[dict]:
    cx, cz = 9.6, 11.0          # front, between the bale and the sheaf behind it
    y0 = LID_TOP
    parts = []
    # a squat, ribbed pumpkin built from turned slabs
    for (r, h0, h1) in ((2.3, 0.2, 2.6), (2.6, 0.5, 2.3), (1.9, 0.0, 2.9)):
        parts += prism((cx, y0 + (h0 + h1) / 2, cz), r, h1 - h0, "pumpkin", sides=8, cap="pumpkin_top")
    # the stem, curling a little, and a vine leaf draped down its side
    parts.append(bar((cx, y0 + 2.8, cz), (cx + 0.3, y0 + 3.8, cz - 0.2), 0.5, 0.5, "vine"))
    parts.append(bar((cx + 0.3, y0 + 3.8, cz - 0.2), (cx + 0.9, y0 + 4.1, cz - 0.3), 0.4, 0.4, "vine"))
    leaf_ = box((cx - 1.0, y0 + 2.4, cz + 0.2), (cx + 1.0, y0 + 2.5, cz + 2.0), "leaf", uv="full")
    turn(leaf_, 28, "x", (cx, y0 + 2.6, cz + 0.4))
    turn(leaf_, -35, "y", (cx, y0 + 2.6, cz + 0.4))
    parts.append(leaf_)
    return parts


def models() -> dict:
    p = shell("barn", "barn_lid", "barn_lid")
    p += walls()
    p += rims("trim")
    p += top_frame("trim")
    p += corner_brackets("iron")
    p += lock("iron_plate", "keyhole", "iron")
    p += hinges("iron")
    p += horseshoes()
    p += hay_bale()
    p += sheaf()
    p += pumpkin_on_lid()
    p += lantern(3.0, 12.4, LID_TOP)    # the barn lantern stands on the lid's front-left corner
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.5))}
