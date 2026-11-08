"""Harvest Chest (issue #1447): the placed crate chest of the Harvest crate, a rustic
farmhouse treasure chest.

A classic treasure chest: a warm brown plank body under a barrel-vaulted lid of curved
boards. Pewter-silver straps run up the front, over the vault and down the back, banded
round the base, the seam and the lid's edge; the vault's two ends carry silver arch rims.
Heavy silver corner caps wrap the four bottom corners and the vault's four corners, L
brackets reinforce every corner run, studs line the straps, and two barrel hinges join lid
and body at the back. A silver lock plate with a warm-lit keyhole and a hasp sit on the
seam. The emblem is a big silver medallion standing on the lid's front: a tied golden
wheat sheaf on a deep green field, ringed by a wreath of wheat ears. Crossed wheat sprigs
are nailed to both side walls.

Animation (one loop): a glint runs round the medallion's rim, light ripples up the wheat,
and the keyhole breathes like a lamp.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block like the other crate chests.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM, corner_brackets,  # noqa: F401
                                    keyhole_glow, lock, lock_plate, metal, planks, put, rims, shell)
from art.kit import animate, arc, bar, box, canvas, display, model, prism, save, save_animation, shine

ID = "harvest_chest"
NAME = "Harvest Chest"

WOOD = ["#2a160b", "#432410", "#5f3518", "#7a4722", "#94592b", "#ad6e37", "#c58a4b"]
VAULT = ["#2e180b", "#4a2912", "#683b1b", "#844e25", "#a0632f", "#b97a3c", "#d09650"]
SILVER = ["#25262c", "#3c3e46", "#585b64", "#7a7d86", "#9da0a8", "#c2c4ca", "#e6e7ea"]
FIELD = ["#16351d", "#1f4a27", "#2a5e32", "#36733d", "#46894a", "#5c9f5a"]
GOLD = ["#4a2e07", "#76500f", "#a47619", "#cc9c2a", "#e8bf45", "#f8de82", "#fff4c8"]
STALK = ["#5a3e10", "#82611d", "#a9852e", "#c9a443", "#e0c068"]
LEAF = ["#1a3510", "#2a5019", "#3d6c24", "#568a31", "#73a944"]
RIBBON = ["#3c0c0c", "#621614", "#8c2420", "#b3392d", "#d4573f"]
LAMP = ["#4a1d06", "#8c3f0a", "#cf7414", "#f3a12e", "#ffc95a", "#ffe594", "#fff6d6"]

# The vault: a circular segment over the lid, front edge to back edge.
RISE = 3.0
HALF = (L1 - L0) / 2
R = (HALF ** 2 + RISE ** 2) / (2 * RISE)
CY = LID_TOP + RISE - R                     # the vault's axis (runs along x through z = 8)
SPAN = math.degrees(math.asin(HALF / R))    # half the vault's angle
BOARDS = 8
STRAPS = (4.1, 11.9)                       # x centres of the two straps
STRAP_W = 1.3


# ======================================================================================
# Textures
# ======================================================================================

def pewter(size: int = 16, seed: int = 3) -> object:
    """Brushed pewter-silver: long horizontal brush strokes, a few hammer dents."""
    img = canvas(size)
    rng = random.Random(seed)
    for y in range(size):
        row = 4 if y < size // 2 else 3
        if rng.random() < 0.25:
            row += 1
        for x in range(size):
            tone = row if rng.random() > 0.12 else row + rng.choice((-1, 1))
            if y == 0:
                tone = 6
            elif y == 1:
                tone = 5
            elif y == size - 1:
                tone = 1
            elif y == size - 2:
                tone = 2
            put(img, x, y, SILVER[max(1, min(6, tone))])
    for _ in range(3):
        x, y = rng.randrange(1, size - 1), rng.randrange(3, size - 3)
        put(img, x, y, SILVER[3])
        put(img, x + 1, y + 1, SILVER[6])
    return img


def strap() -> object:
    """16 px strap seen face-on (runs along v): lit left edge, dark right edge, studs."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            if x <= 1:
                c = SILVER[5] if x == 0 else SILVER[4]
            elif x >= 14:
                c = SILVER[1] if x == 15 else SILVER[2]
            else:
                c = SILVER[3] if (x * 3 + y * 5) % 13 else SILVER[4]
            put(img, x, y, c)
    return img


def stud() -> object:
    """A domed rivet head: bright top-left, shadow bottom-right."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 6)
            tone = 6 if d < 3 else 5 if d < 6 else 4 if d < 9 else 3 if d < 12 else 2
            put(img, x, y, SILVER[tone])
    return img


def rim(t: float) -> object:
    """The medallion's polished rim with a glint sweeping round it."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 5 if y < 5 else 4 if y < 11 else 3
            if (x + y) % 7 == 0:
                tone += 1
            put(img, x, y, SILVER[min(6, tone)])
    return shine(img, t, colour="#ffffff", width=4.0, strength=0.8, pause=0.5)


def field() -> object:
    """The medallion's enamel field: deep green with a soft lit centre and speckle."""
    img = canvas(16)
    rng = random.Random(8)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 7, y + 0.5 - 6) / 10
            tone = 5 if d < 0.25 else 4 if d < 0.5 else 3 if d < 0.8 else 2
            if rng.random() < 0.12:
                tone = max(0, tone - 1)
            put(img, x, y, FIELD[tone])
    return img


def wheat(t: float) -> object:
    """16 px wheat ear: stacked plump kernels with dark gaps, light rippling up it."""
    img = canvas(16)
    ripple = 16 - (t * 20) % 20
    for y in range(16):
        for x in range(16):
            col = x % 4
            kernel = col in (1, 2) and y % 4 != 3
            tone = 4 if kernel else 2
            if col == 0:
                tone = 1
            if kernel and col == 1:
                tone = 5
            d = abs(y - ripple)
            if kernel and d < 2.5:
                tone = 6
            put(img, x, y, GOLD[tone])
    return img


def stalk() -> object:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            put(img, x, y, STALK[(1, 2, 4, 3)[x % 4]] if y % 6 else STALK[0])
    return img


def leaf() -> object:
    """A small leaf: lit upper half, a pale midrib."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 3 if y < 7 else 2
            if y in (7, 8):
                tone = 4
            if (x * 3 + y) % 11 == 0:
                tone -= 1
            put(img, x, y, LEAF[tone])
    return img


def ribbon() -> object:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 3 if y < 5 else 2 if y < 12 else 1
            if (x + y * 2) % 9 == 0:
                tone += 1
            put(img, x, y, RIBBON[tone])
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(LAMP, t)


def textures() -> None:
    save(planks(WOOD, size=32, rows=4, seed=21, knots=3), "wood")
    save(planks(VAULT, size=32, rows=4, seed=34, knots=4), "vault")
    save(planks(WOOD, size=32, rows=4, seed=9, vertical=True, knots=2), "end")
    save(pewter(), "pewter")
    save(strap(), "strap")
    save(stud(), "stud")
    save(metal(SILVER, rivets=(3, 12)), "band")
    save(lock_plate(SILVER), "plate")
    save(field(), "field")
    save(stalk(), "stalk")
    save(ribbon(), "ribbon")
    save(leaf(), "leaf")
    save_animation(animate(rim, 16), "rim", frametime=3)
    save_animation(animate(wheat, 10), "wheat", frametime=3)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def _vault_point(angle: float, radius: float) -> tuple[float, float]:
    """(z, y) on the vault's cross-section; angle 90 = the crown, 90 - SPAN = the front edge."""
    a = math.radians(angle)
    return 8.0 + radius * math.cos(a), CY + radius * math.sin(a)


def vault() -> list[dict]:
    """Curved boards over the lid (running along x), on a stepped core with plank ends."""
    parts = []
    lo, hi = 90 - SPAN, 90 + SPAN
    step = (hi - lo) / BOARDS
    for k in range(BOARDS):
        a0, a1 = lo + step * k, lo + step * (k + 1)
        z0, y0 = _vault_point(a0, R - 0.3)
        z1, y1 = _vault_point(a1, R - 0.3)
        mz, my = (z0 + z1) / 2, (y0 + y1) / 2
        row = (k * 3) % 4
        b = bar((8.0, y0 + (y0 - my) * 0.04, z0 + (z0 - mz) * 0.04),
                (8.0, y1 + (y1 - my) * 0.04, z1 + (z1 - mz) * 0.04), L1 - L0, 0.6, "vault",
                faces={"south": ("vault", [0, row * 4, 16, row * 4 + 4]),
                       "east": ("end", [0, 0, 0.6, 2]), "west": ("end", [0, 0, 0.6, 2])},
                skip=("north",))
        parts.append(b)
    # the core: thin slabs stepping up under the boards (their ends are the vault's end boards)
    n = 9
    for i in range(n):
        y0 = LID_TOP - 0.05 + (RISE - 0.3) * i / n
        y1 = LID_TOP - 0.05 + (RISE - 0.3) * (i + 1) / n
        half = math.sqrt(max(0.0, (R - 0.3) ** 2 - (y0 - CY) ** 2))
        parts.append(box((L0 + 0.12, y0, 8 - half), (L1 - 0.12, y1, 8 + half), "end",
                         faces={"up": "vault"}, skip=("down", "north", "south")))
    return parts


def vault_bands() -> list[dict]:
    """Silver arch rims on both vault ends and the straps carried over the crown."""
    parts = []
    lo, hi = 90 - SPAN - 1.5, 90 + SPAN + 1.5
    for x0, x1, depth, radius in ((L0 - 0.18, L0 + 0.75, 0.75, R + 0.12), (L1 - 0.75, L1 + 0.18, 0.75, R + 0.12)):
        parts += arc(((x0 + x1) / 2, CY, 8.0), radius - depth / 2 + 0.05, lo, hi, 8, x1 - x0, depth, "band",
                     plane="zy")
    for sx in STRAPS:
        parts += arc((sx, CY, 8.0), R + 0.12, lo, hi, 8, STRAP_W, 0.32, "strap", plane="zy")
    return parts


def straps() -> list[dict]:
    """The two straps on the front and back walls (body and lid), with studs."""
    parts = []
    for sx in STRAPS:
        x0, x1 = sx - STRAP_W / 2, sx + STRAP_W / 2
        # front and back, body then lid
        parts.append(box((x0, 0.0, B1), (x1, SEAM, B1 + 0.3), "strap", skip=("down",)))
        parts.append(box((x0, SEAM, L1), (x1, LID_TOP + 0.1, L1 + 0.3), "strap"))
        parts.append(box((x0, 0.0, B0 - 0.3), (x1, SEAM, B0), "strap", skip=("down",)))
        parts.append(box((x0, SEAM, L0 - 0.3), (x1, LID_TOP + 0.1, L0), "strap"))
        for y in (2.4, 5.6, SEAM + 2.6):
            z = L1 if y > SEAM else B1
            parts.append(box((sx - 0.32, y - 0.32, z + 0.3), (sx + 0.32, y + 0.32, z + 0.48), "stud", uv="full"))
    return parts


def mid_band() -> list[dict]:
    """A thinner silver band round the middle of the body."""
    e = 0.14
    return [box((B0 - e, 4.35, B0 - e), (B1 + e, 4.95, B1 + e), "band", skip=("up", "down"))]


def corner_caps() -> list[dict]:
    """Heavy silver caps over the four bottom corners and the vault's four corners."""
    parts = []
    s, e = 2.2, 0.3
    for cx in (B0, B1):
        for cz in (B0, B1):
            sx = 1 if cx == B0 else -1
            sz = 1 if cz == B0 else -1
            x0, x1 = cx - e * sx, cx + s * sx
            z0, z1 = cz - e * sz, cz + s * sz
            parts.append(box((x0, 0.0, z0), (x1, 2.0, z1), "pewter", skip=("down",)))
            # a domed stud on each outer face of the cap
            parts.append(box((cx + (0.4 if sx > 0 else -1.2), 0.6, cz - (e + 0.18) * sz),
                             (cx + (1.2 if sx > 0 else -0.4), 1.4, cz - e * sz), "stud", uv="full"))
            parts.append(box((cx - (e + 0.18) * sx, 0.6, cz + (0.4 if sz > 0 else -1.2)),
                             (cx - e * sx, 1.4, cz + (1.2 if sz > 0 else -0.4)), "stud", uv="full"))
    # the vault's corners: capped where the end rims meet the lid band
    for cx in (L0, L1):
        sx = 1 if cx == L0 else -1
        for cz in (L0, L1):
            sz = 1 if cz == L0 else -1
            parts.append(box((cx - 0.3 * sx, LID_TOP - 1.9, cz - 0.3 * sz), (cx + 1.9 * sx, LID_TOP + 0.45, cz + 1.9 * sz),
                             "pewter"))
    return parts


def back_hinges() -> list[dict]:
    """Barrel hinges where the straps cross the seam at the back."""
    parts = []
    for sx in STRAPS:
        # the knuckle and its pin; the straps behind it are the hinge leaves
        parts += prism((sx, SEAM, L0 - 0.08), 0.5, 2.0, "pewter", axis="x", sides=8, cap="stud")
        parts += prism((sx, SEAM, L0 - 0.08), 0.22, 2.5, "stud", axis="x", sides=8, cap="stud")
        for dy in (-1.4, 1.6):
            parts.append(box((sx - 0.3, SEAM + dy - 0.3, L0 - 0.42), (sx + 0.3, SEAM + dy + 0.3, L0 - 0.3), "stud",
                             uv="full"))
    return parts


def ear(base, direction, length: float = 1.5, width: float = 0.62, on_side: bool = False) -> list[dict]:
    """A wheat ear (and its awn) growing from base along direction (unit vector). On a side
    wall (direction in the y/z plane) the flat face turns to face the wall's normal."""
    tip = tuple(base[i] + direction[i] * length for i in range(3))
    awn = tuple(tip[i] + direction[i] * 0.7 for i in range(3))
    w, d = (0.36, width) if on_side else (width, 0.36)
    return [bar(base, tip, w, d, "wheat", uv="full"), bar(tip, awn, 0.12, 0.12, "stalk")]


def medallion() -> list[dict]:
    """The emblem: a silver medallion on the lid's front with a tied wheat sheaf and a wreath."""
    cx, cy, z = 8.0, 14.7, L1 - 0.45
    parts = []
    # a backing disc set into the vault, and the enamel field in front of the vault's first board
    parts += prism((cx, cy, z + 0.25), 2.6, 0.5, "pewter", axis="z", sides=16)
    parts += prism((cx, cy, z + 0.6), 2.6, 0.2, "field", axis="z", sides=16)
    # the rim: a thick polished ring proud of the field
    parts += arc((cx, cy, z + 0.55), 2.85, 0, 360, 16, 0.65, 0.6, "rim", plane="xy")
    for a in (45, 135, 225, 315):
        r = math.radians(a)
        px, py = cx + 2.85 * math.cos(r), cy + 2.85 * math.sin(r)
        parts.append(box((px - 0.28, py - 0.28, z + 0.84), (px + 0.28, py + 0.28, z + 0.98), "stud", uv="full"))
    # the sheaf: five stalks from a tied waist, fanned at both ends, ears at the top
    zs = z + 0.8
    waist = cy - 0.7
    for i, lean in enumerate((-0.52, -0.26, 0.0, 0.26, 0.52)):
        foot = (cx + lean * 1.8, cy - 2.05, zs)
        top = (cx + lean * 2.1, cy + 0.35 + (0.25 if i == 2 else 0.0) - abs(lean) * 0.4, zs)
        mid = (cx + lean * 0.4, waist, zs)
        parts.append(bar(foot, mid, 0.26, 0.2, "stalk"))
        parts.append(bar(mid, top, 0.26, 0.2, "stalk"))
        d = (lean * 0.9, 1.0, 0.0)
        n = math.hypot(d[0], d[1])
        parts += ear((top[0], top[1], zs + 0.05), (d[0] / n, d[1] / n, 0.0), 1.6, 0.76)
    # the tie: a ribbon knot with two tails
    parts.append(box((cx - 0.75, waist - 0.28, zs - 0.04), (cx + 0.75, waist + 0.28, zs + 0.18), "ribbon"))
    parts.append(bar((cx - 0.1, waist - 0.1, zs + 0.12), (cx - 0.65, waist - 1.0, zs + 0.12), 0.32, 0.08, "ribbon"))
    parts.append(bar((cx + 0.1, waist - 0.1, zs + 0.12), (cx + 0.65, waist - 1.0, zs + 0.12), 0.32, 0.08, "ribbon"))
    # the wreath: wheat ears laid round the outside of the rim, from the bottom up both sides
    rr = 3.6
    for side in (-1, 1):
        for k, a in enumerate((-58, -28, 2, 32, 60)):
            ang = math.radians(a) if side > 0 else math.radians(180 - a)
            bx, by = cx + rr * math.cos(ang), cy + rr * math.sin(ang)
            # each ear lies along the ring, pointing upwards round it
            tx, ty = (-math.sin(ang), math.cos(ang)) if side > 0 else (math.sin(ang), -math.cos(ang))
            parts += ear((bx, by, z + 0.62), (tx, ty, 0.0), 1.15, 0.6)
            if k in (0, 2):
                # a leaf tucked in between the ears, pointing outwards
                lx, ly = cx + (rr + 0.2) * math.cos(ang), cy + (rr + 0.2) * math.sin(ang)
                parts.append(bar((lx, ly, z + 0.55), (lx + 1.1 * math.cos(ang + side * 0.5),
                                                     ly + 1.1 * math.sin(ang + side * 0.5), z + 0.55),
                                 0.55, 0.12, "leaf"))
            if k < 4:
                parts.append(bar((bx, by, z + 0.58), (cx + (rr - 0.6) * math.cos(ang),
                                                      cy + (rr - 0.6) * math.sin(ang), z + 0.58), 0.2, 0.12, "stalk"))
    return parts


def side_sprigs() -> list[dict]:
    """Crossed wheat sprigs nailed to both side walls, under a silver nail."""
    parts = []
    for x, out in ((B1 + 0.05, 1), (B0 - 0.05, -1)):
        xs = x + 0.32 * out
        for z0, z1 in ((4.4, 11.6), (11.6, 4.4)):
            base = (xs, 1.6, z0)
            top = (xs, 6.4, 8.0 + (z1 - 8.0) * 0.55)
            parts.append(bar(base, top, 0.3, 0.5, "stalk"))
            d = (0.0, top[1] - base[1], top[2] - base[2])
            n = math.hypot(d[1], d[2])
            parts += ear((xs + 0.02 * out, top[1], top[2]), (0.0, d[1] / n, d[2] / n), 2.1, 1.0, on_side=True)
            # a leaf half way up
            parts.append(bar((xs, 3.6, base[2] + (top[2] - base[2]) * 0.45),
                             (xs, 4.6, base[2] + (top[2] - base[2]) * 0.45 + (1.1 if z0 < 8 else -1.1)), 0.2, 0.5,
                             "leaf"))
        parts.append(box((x - 0.05 * out if out > 0 else x - 0.45, 3.3, 7.6), (x + 0.45 if out > 0 else x + 0.05, 4.1, 8.4),
                         "stud", uv="full"))
        parts.append(box((x if out > 0 else x - 0.3, 3.15, 7.2), (x + 0.3 if out > 0 else x, 4.25, 8.8), "ribbon"))
        # the bow: two loops and two tails standing off the wall
        xb = x + 0.45 * out
        for side in (-1, 1):
            parts.append(bar((xb, 3.7, 8.0), (xb, 4.5, 8.0 + side * 1.3), 0.12, 0.6, "ribbon"))
            parts.append(bar((xb, 3.6, 8.0), (xb, 2.3, 8.0 + side * 0.8), 0.1, 0.42, "ribbon"))
    return parts


def models() -> dict:
    p = shell("wood", "wood", "vault", faces_body={"east": "end", "west": "end"},
              faces_lid={"east": "end", "west": "end"})
    p += rims("band")
    p += mid_band()
    p += vault()
    p += vault_bands()
    p += straps()
    p += corner_brackets("pewter")
    p += corner_caps()
    p += lock("plate", "keyhole", "pewter", width=5.0)
    # a hasp tongue dropping from the lid onto the plate, riveted at its foot
    p.append(box((7.35, SEAM - 0.85, 16.02), (8.65, SEAM + 0.2, 16.16), "pewter"))
    p.append(box((7.7, SEAM - 0.7, 16.16), (8.3, SEAM - 0.15, 16.24), "stud", uv="full"))
    p += back_hinges()
    p += medallion()
    p += side_sprigs()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(26, 20, 0), gui_span=16.5))}
