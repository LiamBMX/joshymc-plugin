"""Cabin Chest (issue #1454): the placed crate chest of the Cabin crate, a pink and mint
cottage treasure chest.

A soft pink clapboard body on a mint plinth, with mint corner boards, a mint band under the
lid seam, and mint-framed panels on every wall: the front panels carry mint hearts, the side
panels a window box of pink, white and yellow flowers under a mint heart. The lid is a
cottage-roof lid hinged at the seam: a low gable of pink boards under two slopes of mint
scalloped shingles laid in overlapping courses, a cream ridge cap with a pink enamel
heart standing in its middle, cream bargeboards on the gable ends (each gable with a pink enamel
heart) and a scalloped mint valance hanging along the front and back eaves. The
emblem is a flower wreath on the lid's front: a ring of leaves set with blossoms round a
pink enamel heart on a cream plaque. A mint hasp drops from it onto a mint lock plate with
a cream border, a pink enamel heart and a warm keyhole. Mint strap hinges with heart ends
hold the lid at the back.

Animation (one loop): the flowers nod in a breeze, the enamel hearts catch a glint, and
the keyhole glows softly.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block like the other crate chests. Deliberately unlike the
earlier snowy log cabin crate (cabin_crate, kept for rollback): painted boards, no logs, no
snow, no chimney or attic window, and a low lid rather than a house roof.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM, keyhole_glow,  # noqa: F401
                                    metal, put, shell)
from art.kit import animate, arc, bar, box, canvas, display, model, prism, save, save_animation, shine, turn

ID = "cabin_chest"
NAME = "Cabin Chest"

PINK = ["#b04a74", "#d86a96", "#ec8bb0", "#ee97b8", "#f4a6c4", "#f7b9d0", "#ffe9f2"]
MINT = ["#17604c", "#25826a", "#3fa688", "#68c7a4", "#93e2c2", "#bff3dc", "#e8fff4"]
CREAM = ["#7a6553", "#a8927b", "#d0bca2", "#ebdcc5", "#f9f1e3", "#fffbf3"]
ROSE = ["#7d1d42", "#ad2f5c", "#d64d7c", "#ee77a0", "#fba9c6", "#ffdbe8"]
LEAF = ["#1c4a22", "#2a6a2f", "#3b8a3d", "#55aa50", "#7cc96a"]
PETALS = {"rose": ["#8d2147", "#c53e6c", "#ee6f96", "#ffa8c2", "#ffd9e5"],
          "white": ["#9a8f9e", "#c9c1cc", "#ebe6ee", "#fbf8fc", "#ffffff"],
          "yellow": ["#8a5a0c", "#c58e17", "#ecc02f", "#fde26a", "#fff4b8"]}
LAMP = ["#3b1d0e", "#7a3e12", "#c66f1d", "#f2a23a", "#ffcc6a", "#ffe7a6", "#fff8e0"]

RISE = 3.1                         # the lid roof's rise above the lid box
RIDGE = LID_TOP + RISE
EAVE_Z0, EAVE_Z1 = 0.08, 15.92     # front/back eaves
ROOF_X0, ROOF_X1 = 0.1, 15.9       # gable-end overhang
EAVE_Y = LID_TOP - 0.25
ROOF_T = 0.5                       # roof plate thickness
RUN = 8.0 - EAVE_Z0
PITCH = math.atan2(RIDGE - EAVE_Y, RUN)


def gable_half(y: float) -> float:
    """Half-depth (z) of the gable under the roof's underside at height y."""
    under = ROOF_T / math.cos(PITCH)
    return max(0.0, (RIDGE - under - y) / math.tan(PITCH))


# ======================================================================================
# Textures
# ======================================================================================

def clapboard(size: int = 32) -> object:
    """Soft pink painted clapboard: overlapping boards, each lit along its top edge and
    shadowed under the next, fine brush grain, a few chips showing cream undercoat."""
    img = canvas(size)
    rng = random.Random(4)
    board = 8
    for y in range(size):
        k = y % board
        for x in range(size):
            if k == 0:
                c = PINK[6]
            elif k == board - 1:
                c = PINK[1]
            elif k == board - 2:
                c = PINK[3]
            else:
                c = PINK[5] if k < 3 else PINK[4]
                if rng.random() < 0.1:
                    c = PINK[3] if k > 2 else PINK[4]
            put(img, x, y, c)
    for _ in range(4):
        put(img, rng.randrange(size), rng.randrange(size), CREAM[4])
    return img


def boards() -> object:
    """Vertical pink boards for the gable ends."""
    img = canvas(32)
    for y in range(32):
        for x in range(32):
            k = x % 6
            c = PINK[2] if k == 5 else PINK[6] if k == 0 else PINK[5] if k < 3 else PINK[4]
            if (y * 7 + x * 3) % 17 == 0 and k not in (0, 5):
                c = PINK[3]
            put(img, x, y, c)
    return img


def shingles(size: int = 32) -> object:
    """Mint fish-scale shingles: rows of round-bottomed tiles, every row offset by half a
    tile; each tile is lit on its upper left, outlined along its curved lower edge, and the
    lit top of the tile beneath shows in the corners."""
    img = canvas(size)
    row_h, w = 6, 6
    for y in range(size):
        r = y // row_h
        k = y % row_h
        shift = (w // 2) if r % 2 else 0
        for x in range(size):
            u = (x + shift) % w
            dx = (u + 0.5 - w / 2) / (w / 2)                 # -1 .. 1 across the tile
            edge = row_h - 1 - round(2.2 * dx * dx)
            if k > edge:
                c = MINT[4]                                     # the tile beneath
            elif k == edge:
                c = MINT[2]
            elif k == 0:
                c = MINT[5]
            else:
                c = MINT[4] if dx < -0.2 and k < 3 else MINT[3] if dx < 0.4 else MINT[2]
            put(img, x, y, c)
    return img


def mint_board(size: int = 16) -> object:
    """Mint-painted trim board: lit edge, shadow edge, soft brush streaks."""
    img = canvas(size)
    rng = random.Random(6)
    for y in range(size):
        for x in range(size):
            if x <= 1:
                c = MINT[5] if x == 0 else MINT[4]
            elif x >= size - 2:
                c = MINT[1] if x == size - 1 else MINT[2]
            else:
                c = MINT[3] if rng.random() > 0.2 else MINT[4]
            put(img, x, y, c)
    return img


def mint_plain(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 4 if y < 4 else 3
            if (x * 5 + y * 3) % 13 == 0:
                tone += 1
            put(img, x, y, MINT[tone])
    return img


def cream(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 4 if y < 3 else 3 if y < 12 else 2
            if (x + y * 2) % 11 == 0:
                tone -= 1
            put(img, x, y, CREAM[tone])
    return img


def leaf() -> object:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 3 if (x // 4 + y // 4) % 2 else 2
            if (x * 3 + y) % 7 == 0:
                tone += 1
            put(img, x, y, LEAF[tone])
    return img


def enamel(t: float) -> object:
    """Pink enamel with a soft gloss, a glint sweeping across now and then."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 5) / 11
            tone = 4 if d < 0.25 else 3 if d < 0.6 else 2
            put(img, x, y, ROSE[tone])
    return shine(img, t, colour="#ffffff", width=3.0, strength=0.75, pause=0.55)


def blossom(colours, t: float, phase: float) -> object:
    """A five-petal flower seen face-on with a golden heart; it nods (brightens and dims a
    touch) as the breeze passes."""
    img = canvas(16)
    nod = 0.5 + 0.5 * math.sin(2 * math.pi * (t + phase))
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            r = math.hypot(dx, dy) / 8
            a = math.atan2(dy, dx)
            petal = 0.72 + 0.28 * math.cos(5 * a)
            if r > petal:
                c = colours[1]
            else:
                tone = 2 + (1 if r < petal * 0.7 else 0) + (1 if nod > 0.5 and dy < 0 else 0)
                c = colours[min(4, tone)]
            if r < 0.24:
                c = "#f7c443" if r > 0.1 else "#c7861a"
            put(img, x, y, c)
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(LAMP, t)


def textures() -> None:
    save(clapboard(), "clapboard")
    save(boards(), "gable")
    save(shingles(), "shingles")
    save(mint_board(), "mint")
    save(mint_plain(), "mint_plain")
    save(cream(), "cream")
    save(leaf(), "leaf")
    save(metal(MINT, rivets=(3, 12)), "band")
    for name, colours, phase in (("rose", PETALS["rose"], 0.0), ("white", PETALS["white"], 0.33),
                                 ("yellow", PETALS["yellow"], 0.66)):
        save_animation(animate(lambda t, c=colours, p=phase: blossom(c, t, p), 8), name, frametime=5,
                       interpolate=True)
    save_animation(animate(enamel, 12), "enamel", frametime=3)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def heart(cx: float, cy: float, z: float, s: float, tex: str, depth: float = 0.25, **kw) -> list[dict]:
    """A heart facing +Z: two round lobes and a point; s is about the lobe radius."""
    parts = []
    for side in (-1, 1):
        parts += prism((cx + side * s * 0.72, cy + s * 0.15, z), s, depth, tex, axis="z", sides=8, cap=tex, **kw)
    point = box((cx - s * 1.05, cy - s * 1.05, z - depth / 2), (cx + s * 1.05, cy + s * 1.05, z + depth / 2), tex, **kw)
    turn(point, 45, "z", (cx, cy - s * 0.25, z))
    parts.append(point)
    return parts


def side_heart(x: float, cy: float, cz: float, s: float, tex: str, out: int, depth: float = 0.2, **kw) -> list[dict]:
    """A heart on a side wall (facing +X when out = 1, -X when out = -1)."""
    h = heart(cz, cy, 0.0, s, tex, depth=depth, **kw)
    # build it facing +Z at the origin of z, then swing it round to face the wall's normal
    turn(h, 90 * out, "y", (cz, cy, 0.0))
    dx, dz = x - cz, cz
    for e in h:
        for k in ("from", "to"):
            e[k] = [round(e[k][0] + dx, 4), e[k][1], round(e[k][2] + dz, 4)]
        if "rotation" in e:
            o = e["rotation"]["origin"]
            e["rotation"]["origin"] = [round(o[0] + dx, 4), o[1], round(o[2] + dz, 4)]
    return h


def frame(face: str, a0: float, a1: float, y0: float, y1: float, t: float = 0.45, proud: float = 0.24) -> list[dict]:
    """A raised mint rectangle on one wall (a runs along the wall)."""
    parts = []
    if face in ("front", "back"):
        z0, z1 = (B1, B1 + proud) if face == "front" else (B0 - proud, B0)
        for (p0, p1) in (((a0, y1 - t), (a1, y1)), ((a0, y0), (a1, y0 + t)), ((a0, y0 + t), (a0 + t, y1 - t)),
                         ((a1 - t, y0 + t), (a1, y1 - t))):
            parts.append(box((p0[0], p0[1], z0), (p1[0], p1[1], z1), "mint"))
    else:
        x0, x1 = (B1, B1 + proud) if face == "east" else (B0 - proud, B0)
        for (p0, p1) in (((a0, y1 - t), (a1, y1)), ((a0, y0), (a1, y0 + t)), ((a0, y0 + t), (a0 + t, y1 - t)),
                         ((a1 - t, y0 + t), (a1, y1 - t))):
            parts.append(box((x0, p0[1], p0[0]), (x1, p1[1], p1[0]), "mint"))
    return parts


def base_and_trim() -> list[dict]:
    """A mint plinth with a cream lip, the mint seam band and mint corner boards."""
    parts = [box((B0 - 0.3, 0.0, B0 - 0.3), (B1 + 0.3, 1.1, B1 + 0.3), "band", skip=("down",)),
             box((B0 - 0.18, 1.1, B0 - 0.18), (B1 + 0.18, 1.35, B1 + 0.18), "cream", skip=("down",)),
             box((B0 - 0.2, SEAM - 0.8, B0 - 0.2), (B1 + 0.2, SEAM, B1 + 0.2), "band", skip=("up",))]
    t, w = 0.24, 1.2
    for (lo, hi, y0, y1) in ((B0, B1, 1.35, SEAM - 0.8), (L0, L1, SEAM, LID_TOP)):
        for cx in (lo, hi):
            for cz in (lo, hi):
                sx = 1 if cx == lo else -1
                sz = 1 if cz == lo else -1
                parts.append(box((cx - t * sx, y0, cz - t * sz), (cx + w * sx, y1, cz), "mint"))
                parts.append(box((cx - t * sx, y0, cz), (cx, y1, cz + w * sz), "mint"))
    return parts


def attic() -> list[dict]:
    """The low gable: stacked slabs of vertical pink boards filling the space under the lid
    roof (their stepped ends hide inside the roof plates)."""
    parts = []
    step = 0.45
    y = LID_TOP - 0.02
    while y < RIDGE - 0.55:
        half = gable_half(y) + 0.05
        top = min(y + step, RIDGE - 0.45)
        if half > 0.2:
            u0, u1 = max(0.0, 8.0 - half), min(16.0, 8.0 + half)
            v1 = min(16.0, 16.0 - (y - LID_TOP) * 3.0)
            v0 = max(0.0, v1 - (top - y) * 3.0)
            parts.append(box((L0 + 0.02, y, 8.0 - half), (L1 - 0.02, top, 8.0 + half), "gable",
                             faces={"east": ("gable", [u1, v0, u0, v1]), "west": ("gable", [u0, v0, u1, v1])},
                             skip=("down", "up", "north", "south")))
        y = top
    return parts


def roof() -> list[dict]:
    """Two mint shingle slopes in three overlapping courses each, the cream ridge cap with a
    pink enamel heart standing in its middle, cream bargeboards on the gable ends with an
    enamel heart, and a scalloped mint valance along both eaves."""
    parts = []
    length = math.hypot(RUN, RIDGE - EAVE_Y)
    courses = 3
    nz, ny = math.sin(PITCH), math.cos(PITCH)
    for side in (1, -1):
        for k in range(courses):
            s0 = length * k / courses - (0.3 if k else 0.0)
            s1 = length * (k + 1) / courses
            lift = 0.12 * (courses - 1 - k)

            def at(s, lift=lift):
                z = EAVE_Z1 - s * math.cos(PITCH) if side > 0 else EAVE_Z0 + s * math.cos(PITCH)
                y = EAVE_Y + s * math.sin(PITCH)
                return (z - side * nz * (ROOF_T / 2 - lift), y - ny * (ROOF_T / 2 - lift))
            (z0, y0), (z1, y1) = at(s0), at(s1)
            parts.append(bar((8.0, y0, z0), (8.0, y1, z1), ROOF_X1 - ROOF_X0, ROOF_T, "shingles",
                             offset=(0, 6 * k % 16)))
    # ridge cap and its hearts
    cap = box((ROOF_X0 - 0.05, RIDGE - 0.6, 8.0 - 0.6), (ROOF_X1 + 0.05, RIDGE + 0.05, 8.0 + 0.6), "cream")
    turn(cap, 45, "x", (8.0, RIDGE - 0.28, 8.0))
    parts.append(cap)
    parts += heart(8.0, RIDGE + 0.95, 8.0, 0.74, "enamel", depth=0.3, uv="full")
    # bargeboards on the gable ends
    under = ROOF_T / math.cos(PITCH)
    for x0, x1 in ((ROOF_X0 + 0.04, ROOF_X0 + 0.3), (ROOF_X1 - 0.3, ROOF_X1 - 0.04)):
        for side in (1, -1):
            ez = EAVE_Z1 - 0.2 if side > 0 else EAVE_Z0 + 0.2
            a = ((x0 + x1) / 2, EAVE_Y - under + 0.04 + 0.2 * math.tan(PITCH), ez)
            b = ((x0 + x1) / 2, RIDGE - under + 0.04, 8.0)
            parts.append(bar(a, b, x1 - x0, 0.7, "cream"))
    for x, out in ((L1, 1), (L0, -1)):
        parts += side_heart(x + 0.05 * out, LID_TOP + 1.1, 8.0, 0.6, "enamel", out, depth=0.16, uv="full")
    # the valance: a mint fascia under each eave with a row of scallops hanging from it
    for side in (1, -1):
        ez = EAVE_Z1 - 0.12 if side > 0 else EAVE_Z0 + 0.12
        z0, z1 = (ez - 0.2, ez) if side > 0 else (ez, ez + 0.2)
        parts.append(box((ROOF_X0 + 0.1, EAVE_Y - 0.62, z0), (ROOF_X1 - 0.1, EAVE_Y - 0.12, z1), "mint"))
        for i in range(9):
            x = 1.25 + i * (13.5 / 8)
            d = box((x - 0.42, EAVE_Y - 0.95, z0 + 0.02), (x + 0.42, EAVE_Y - 0.11, z1 - 0.02), "mint_plain")
            turn(d, 45, "z", (x, EAVE_Y - 0.6, (z0 + z1) / 2))
            parts.append(d)
    return parts


def flowers(spots, y: float, depth_at: float, facing: str) -> list[dict]:
    """A row of flowers in a window box: leaves, then a blossom on a short stem facing out.
    spots: [(position along the box, petal colour)]; facing "z" (front) or "x" (sides)."""
    parts = []
    for i, (a, colour) in enumerate(spots):
        h = 0.95 + 0.25 * (i % 2)
        if facing == "z":
            parts.append(box((a - 0.42, y, depth_at - 0.3), (a + 0.42, y + 0.45, depth_at + 0.3), "leaf"))
            parts.append(box((a - 0.08, y + 0.4, depth_at - 0.08), (a + 0.08, y + h - 0.2, depth_at + 0.08), "leaf"))
            parts.append(box((a - 0.36, y + h - 0.36, depth_at + 0.05), (a + 0.36, y + h + 0.36, depth_at + 0.25),
                             colour, uv="full"))
        else:
            parts.append(box((depth_at - 0.3, y, a - 0.42), (depth_at + 0.3, y + 0.45, a + 0.42), "leaf"))
            parts.append(box((depth_at - 0.08, y + 0.4, a - 0.08), (depth_at + 0.08, y + h - 0.2, a + 0.08), "leaf"))
            out = 1 if depth_at > 8 else -1
            x0 = depth_at + 0.05 * out
            x1 = depth_at + 0.25 * out
            parts.append(box((min(x0, x1), y + h - 0.36, a - 0.36), (max(x0, x1), y + h + 0.36, a + 0.36), colour,
                             uv="full"))
    return parts


def walls() -> list[dict]:
    """Mint panels on every wall: rose hearts on the front, flower boxes under a rose heart
    on the sides, plain panels on the back between the hinges."""
    parts = []
    y0, y1 = 1.75, SEAM - 1.25
    for a0, a1 in ((1.95, 5.05), (10.95, 14.05)):
        parts += frame("front", a0, a1, y0, y1)
        parts += heart((a0 + a1) / 2, 4.75, B1 + 0.1, 0.78, "enamel", depth=0.2, uv="full")
    for a0, a1 in ((1.95, 3.3), (5.2, 10.8), (12.7, 14.05)):
        parts += frame("back", a0, a1, y0, y1)
    for face, x, out in (("east", B1, 1), ("west", B0, -1)):
        parts += frame(face, 2.1, 13.9, y0, y1)
        bx0, bx1 = (x, x + 0.62) if out > 0 else (x - 0.62, x)
        parts.append(box((bx0, 2.2, 3.6), (bx1, 3.25, 12.4), "mint", faces={"up": "leaf"}))
        parts.append(box((bx0, 3.2, 3.45), (bx1, 3.4, 12.55), "cream"))
        parts += flowers([(4.4, "rose"), (5.7, "white"), (7.0, "yellow"), (8.3, "rose"), (9.6, "white"),
                          (10.9, "yellow"), (11.9, "rose")], 3.4, x + 0.31 * out, "x")
        parts += side_heart(x + 0.12 * out, 6.55, 8.0, 0.66, "enamel", out, uv="full")
    return parts


def wreath_and_lock() -> list[dict]:
    """The emblem (a flower wreath round a pink enamel heart on the lid's front), its hasp,
    and the mint lock plate across the seam (covers the vanilla latch)."""
    parts = []
    # --- the wreath
    cx, cy, z = 8.0, 11.75, L1
    parts += prism((cx, cy, z + 0.06), 1.35, 0.12, "cream", axis="z", sides=8, cap="cream", uv="full")
    parts += arc((cx, cy, z + 0.12), 1.62, 22.5, 382.5, 8, 0.6, 0.3, "leaf", plane="xy")
    for i, colour in enumerate(("rose", "white", "yellow", "rose", "white", "yellow")):
        a = math.radians(90 + i * 60)
        px, py = cx + 1.62 * math.cos(a), cy + 1.62 * math.sin(a)
        parts.append(box((px - 0.36, py - 0.36, z + 0.28), (px + 0.36, py + 0.36, z + 0.42), colour, uv="full"))
    parts += heart(cx, cy - 0.05, z + 0.2, 0.6, "enamel", depth=0.12, uv="full")
    # the hasp from the wreath's foot down onto the lock plate
    z1 = 16.02
    parts.append(box((7.3, 9.0, z1 - 0.06), (8.7, 10.35, z1 + 0.1), "mint"))
    parts.append(box((7.7, 9.35, z1 + 0.1), (8.3, 9.85, z1 + 0.18), "cream"))
    # --- the lock plate
    z0 = B1 - 0.05
    parts.append(box((5.4, 4.9, z0), (10.6, 10.0, z1 - 0.06), "mint_plain"))
    for (a, b) in (((5.25, 9.6), (10.75, 10.1)), ((5.25, 4.75), (10.75, 5.25))):
        parts.append(box((a[0], a[1], z1 - 0.16), (b[0], b[1], z1 + 0.04), "cream"))
    for x in (5.25, 10.25):
        parts.append(box((x, 5.25, z1 - 0.16), (x + 0.5, 9.6, z1 + 0.04), "cream"))
    parts += heart(8.0, 7.6, z1 - 0.02, 1.08, "enamel", depth=0.12, uv="full")
    parts.append(box((7.62, 7.35, z1 + 0.04), (8.38, 8.1, z1 + 0.1), "keyhole", glow=10, shade=False, uv="full"))
    parts.append(box((7.82, 6.35, z1 + 0.04), (8.18, 7.45, z1 + 0.1), "keyhole", glow=10, shade=False, uv="full"))
    return parts


def back_hinges() -> list[dict]:
    parts = []
    for x in (4.2, 11.8):
        parts += prism((x, SEAM, L0 + 0.08), 0.48, 2.2, "mint_plain", axis="x", sides=8, cap="cream")
        parts.append(box((x - 0.55, SEAM - 3.0, B0 - 0.24), (x + 0.55, SEAM - 0.3, B0), "mint"))
        parts.append(box((x - 0.55, SEAM + 0.3, L0 - 0.18), (x + 0.55, SEAM + 3.0, L0), "mint"))
        hz = B0 - 0.14
        for e in heart(x, SEAM - 3.45, hz, 0.42, "mint_plain", depth=0.18):
            parts.append(e)
    return parts


def models() -> dict:
    p = shell("clapboard", "clapboard", "gable")
    p += base_and_trim()
    p += walls()
    p += attic()
    p += roof()
    p += wreath_and_lock()
    p += back_hinges()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
