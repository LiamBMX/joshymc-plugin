"""Snowman Boots: the feet of the Snowman set (issue #1316), built on the Snowman Chestplate
(the set's pilot module, whose palette, painters and 3D parts it imports).

Chunky snow boots for a snowman: a folded red knit cuff with a cream stripe and two
cream pom-poms on a cord, uppers of packed snow with a crust of snow clumps over the toe
and a coal toggle at the front, and thick coal-black soles with a treaded edge.

Worn (humanoid layer, 4x): the boots cover the leg from row 27 down (layer row 107), just
under the Leggings' snowball knee: the knit cuff (rows 27-33, cream stripe, a pom-pom on
the outside), the packed snow upper with a lumpy shaded foot, the coal toggle on the
front, and the coal sole with its tread; rows 45-47 are under the ground.

Item: a pair in 3D standing side by side as worn: rounded snowball toes and shafts, the
cuff rolled over the top with the stripe and the pom-poms hanging from it, the toggle,
the snow crust and the soles with their tread ribs.
"""
from __future__ import annotations

import random

import numpy as np

from art.items.snowman_chestplate import (COAL, CREAM, CREAM_KNIT, RED, S, SNOW, coal, coal_lump, knit, knit_roll,
                                          lumps, mask_rect, round_bias, save_set_materials, snow, snowball, stripes)
from art.items.snowveil_guard import C, _hash, blank, grid, shadow, to_image
from art.kit import HUMANOID, bar, box, display, mirror, model, move, place, region, save_layer, turn

ID = "snowman_boots"
NAME = "Snowman Boots"
KIND = "boots"
COUNTERPART = "item/netherite_boots"

FW, FH = 4 * S, 12 * S        # leg face size (texels)
TOP = 27                      # the boot's top edge
CUFF = (27, 33)               # the folded knit cuff (cream stripe on rows 29-30)
SOLE = 43                     # the sole's top row
GROUND = 45                   # rows below are under the ground


def pompom(a: np.ndarray, cx: int, cy: int, r: float = 2.6, pal=None) -> None:
    """A fluffy pom-pom (red knit by default) centred on (cx, cy): ragged round edge, lit
    upper left, a contact shadow."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    d = np.sqrt((X + 0.5 - cx) ** 2 + (Y + 0.5 - cy) ** 2)
    m = d <= r + (_hash(X, Y, 3) - 0.5) * 0.9
    shadow(a, m, 0.5, reach=1)
    dl = np.sqrt((X + 0.5 - cx + 1) ** 2 + (Y + 0.5 - cy + 1) ** 2) / (r + 1)
    pal = pal or RED[2:]
    top = len(pal) - 1
    idx = np.clip(np.rint(top - dl * (top - 0.5) * 1.1 + (_hash(X, Y, 4) - 0.5) * 1.2), 0, top).astype(int)
    stack = np.stack([C(c) for c in pal])
    a[m] = stack[idx][m]


def cuff(a: np.ndarray, seed: int) -> None:
    h, w = a.shape[:2]
    y0, y1 = CUFF
    m = mask_rect(h, w, 0, y0, w - 1, y1)
    knit(a, m, seed=seed, along="x", bias=0.15)
    stripes(a, m, (y0 + 3, y0 + 4), seed=seed + 1)
    a[y0, :] = C(RED[6])
    a[y1, :] = C(RED[1])
    shadow(a, m, 0.5, reach=2, down_only=True)


def sole(a: np.ndarray, seed: int) -> None:
    """The coal-black sole: a lit top rim, tread notches along its edge."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    m = Y >= SOLE
    a[m] = C(COAL[2])
    a[Y == SOLE] = C(COAL[5])
    a[Y == SOLE + 1] = C(COAL[3])
    a[(Y == SOLE + 1) & (X % 3 == 0)] = C(COAL[0])
    a[Y >= GROUND] = C(COAL[1])


def base_face(seed: int, kind: str) -> np.ndarray:
    a = blank(FW, FH)
    bias = round_bias(FH, FW, cx=FW * 0.45, cy=37, rx=FW * 0.8, ry=10, depth=0.6)
    if kind == "inner":
        bias = bias - 0.3
    snow(a, seed=seed, bias=bias)
    sole(a, seed)
    # the snow crust over the sole's rim: lumps hanging down onto it
    lumps(a, [SOLE] * FW, seed + 2)
    cuff(a, seed + 3)
    a[:TOP] = 0          # above the cuff is the Leggings' (or the skin's) business
    return a


def paint_leg_front(seed: int = 101) -> np.ndarray:
    a = base_face(seed, "front")
    X, Y = grid(FH, FW)
    # a soft highlight across the rounded toe
    toe = (Y >= SOLE - 4) & (Y < SOLE - 1) & (X >= 3) & (X <= 12)
    a[toe] = a[toe] * 0.4 + C(SNOW[8]) * 0.6
    # red laces crossing down the front between two rows of coal eyelets
    for k in range(3):
        y0 = CUFF[1] + 1 + k * 2
        for i in range(6):
            for x, y in ((5 + i, y0 + i // 3), (10 - i, y0 + i // 3)):
                if y < SOLE - 4:
                    a[y, x] = C(RED[5] if i % 2 == 0 else RED[3])
    for y in range(CUFF[1] + 1, SOLE - 4, 2):
        a[y, 4] = C(COAL[2])
        a[y, 11] = C(COAL[2])
    return a


def paint_leg_back(seed: int = 131) -> np.ndarray:
    a = base_face(seed, "back")
    # a pull tab at the heel
    a[CUFF[1] + 1:CUFF[1] + 3, 6:10] = C(RED[2])
    a[CUFF[1] + 1, 6:10] = C(RED[5])
    return a


def paint_leg_side(outer: bool, seed: int) -> np.ndarray:
    """The outer face (left = back, right = front) carries the pom-poms; the inner face is
    the plain mirror image (left = front)."""
    a = base_face(seed, "outer" if outer else "inner")
    if outer:
        a[CUFF[0] + 3:CUFF[1] + 4, 11] = C(CREAM[2])
        pompom(a, 10.5, CUFF[1] + 5.5, 2.7)
        pompom(a, 13.6, CUFF[1] + 3.6, 2.3)
    return a if outer else a[:, ::-1].copy()


def paint_leg_sole(seed: int = 151) -> np.ndarray:
    a = blank(FW, FW)
    a[:] = C(COAL[2])
    X, Y = grid(FW, FW)
    a[(Y % 3 == 0)] = C(COAL[0])
    return a


def paint_humanoid():
    out = blank(64 * S, 32 * S)

    def put(name, arr):
        x0, y0, x1, y1 = region(HUMANOID, name, S)
        out[y0:y1 + 1, x0:x1 + 1] = arr

    put("leg_front", paint_leg_front())
    put("leg_back", paint_leg_back())
    put("leg_outer", paint_leg_side(True, 111))
    put("leg_inner", paint_leg_side(False, 121))
    put("leg_sole", paint_leg_sole())
    return to_image(out)


# --------------------------------------------------------------------------------------
# Item: the right boot (+x, toe toward -z like the set's other pieces), mirrored about
# x = 8 for the left one.
# --------------------------------------------------------------------------------------

BX0, BX1 = 8.7, 14.5          # boot width (x)
TZ0, SZ0, SZ1 = -0.8, 5.6, 11.8   # toe front, shaft front, shaft back (z)
SOLE_H, H = 1.5, 10.0         # sole height, shaft top (the cuff's top)
FRAME = ((7.0, -0.5, -2.0), (16.0, 11.5, 13.5))


def textures() -> None:
    save_layer(paint_humanoid(), "humanoid")
    save_set_materials(skip=("twig", "green", "berry", "carrot", "tail", "lining"))


def sole_3d() -> list[dict]:
    """The thick coal sole: a rounded toe in three steps, a raised heel block, and tread
    ribs round its edge."""
    p = [box((BX0 - 0.3, 0.0, TZ0 + 1.4), (BX1 + 0.3, SOLE_H, SZ1 + 0.3), "coal", offset=(0, 0)),
         box((BX0 + 0.4, 0.0, TZ0 + 0.3), (BX1 - 0.4, SOLE_H, TZ0 + 1.8), "coal", offset=(1, 0)),
         box((BX0 + 1.2, 0.0, TZ0 - 0.3), (BX1 - 1.2, SOLE_H - 0.1, TZ0 + 0.5), "coal", offset=(2, 0))]
    # the heel: a step of sole under the back of the boot
    p.append(box((BX0 - 0.2, -0.6, SZ0 + 1.2), (BX1 + 0.2, 0.05, SZ1 + 0.2), "coal", offset=(0, 2)))
    z = TZ0 + 1.6
    while z < SZ1 - 0.2:
        for x0, x1 in ((BX0 - 0.45, BX0 - 0.25), (BX1 + 0.25, BX1 + 0.45)):
            p.append(box((x0, 0.15, z), (x1, 0.75, z + 0.5), "coal", offset=(3, 2)))
        z += 1.1
    return p


def upper_3d() -> list[dict]:
    """The packed snow upper: a tall rounded toe cap, a rounded shaft, a crust of snow
    clumps over the sole's rim, and red laces crossing up the front to a coal toggle."""
    p = snowball((BX0, SOLE_H - 0.3, TZ0 + 0.2), (BX1, 5.6, SZ1 - 1.2), 2.5, steps=4, frame=FRAME, rim="snow_rim")
    p += snowball((BX0 + 0.1, SOLE_H - 0.3, SZ0 - 0.8), (BX1 - 0.1, H - 1.6, SZ1), 1.3, steps=2, frame=FRAME,
                  rim="snow_rim")
    rng = random.Random(5)
    for k in range(5):
        x = BX0 + 0.9 + k * (BX1 - BX0 - 1.8) / 4
        z = TZ0 + 0.5 + rng.uniform(0.0, 0.7)
        s = rng.uniform(0.55, 0.8)
        p += snowball((x - s, SOLE_H - 0.1, z - s), (x + s, SOLE_H + s * 1.4, z + s), s * 0.5, steps=1, frame=FRAME)
    # the laces: red cord crossing up the front of the shaft
    mid = (BX0 + BX1) / 2
    zf = SZ0 - 0.95
    for k, y in enumerate((5.4, 6.6, 7.8)):
        for sgn in (-1, 1):
            p.append(bar((mid - 1.5 * sgn, y, zf), (mid + 1.5 * sgn, y + 1.1, zf), 0.32, 0.25, "knit_x",
                         offset=(k, 1)))
    for sgn in (-1, 1):
        for y in (5.4, 6.6, 7.8, 9.0):
            p.append(box((mid + sgn * 1.55 - 0.22, y - 0.22, zf + 0.05), (mid + sgn * 1.55 + 0.22, y + 0.22, zf + 0.3),
                          "coal", offset=(0, 0)))
    p += coal_lump(mid, 4.6, zf - 0.05, 0.6, 0.5)
    return p


def cuff_3d() -> list[dict]:
    """The thick knit cuff folded over the top of the shaft: a fat rolled band with a
    bevelled lip, its cream stripe, snow filling the opening, and two red knit pom-poms
    hanging from a cord on the outside."""
    p = knit_roll((BX0 - 0.7, H - 3.0, SZ0 - 1.5), (BX1 + 0.7, H, SZ1 + 0.7), tex="knit_x", r=0.6)
    p += knit_roll((BX0 - 0.45, H - 0.2, SZ0 - 1.25), (BX1 + 0.45, H + 0.35, SZ1 + 0.45), tex="knit_x", r=0.25)
    p.append(box((BX0 - 0.78, H - 1.9, SZ0 - 1.58), (BX1 + 0.78, H - 1.45, SZ1 + 0.78), "cream", skip=("up", "down")))
    # snow heaped in the opening
    p += snowball((BX0 + 0.4, H - 0.9, SZ0 - 0.5), (BX1 - 0.4, H + 0.15, SZ1 - 0.2), 0.5, steps=2, frame=FRAME,
                  rim="snow_rim")
    # the cord and its pom-poms on the outer front corner
    cx, cz = BX1 + 0.75, SZ0 - 0.8
    p.append(box((cx - 0.13, H - 4.2, cz - 0.13), (cx + 0.13, H - 1.0, cz + 0.13), "cream"))
    for dx, dy, dz, s in ((0.2, -4.9, -0.3, 1.1), (0.45, -3.8, 1.3, 0.95)):
        x, y, z = cx + dx, H + dy, cz + dz
        p += snowball((x - s, y - s, z - s), (x + s, y + s, z + s), s * 0.55, steps=2, tex="knit",
                      frame=((x - 1.2, y - 1.2, z - 1.2), (x + 1.2, y + 1.2, z + 1.2)))
    return p


def build() -> list[dict]:
    right = sole_3d() + upper_3d() + cuff_3d()
    # the left boot stands a step behind and turned out a little, so the pair doesn't hide
    # one another in the icon
    left = turn(move(mirror(right, "x", 8.0), dz=3.2), 20, "y", (4.4, 0.0, 7.0))
    return right + left


def models() -> dict:
    parts = build()
    d = display(KIND, parts, gui_rotation=(22, 145, 0), gui_span=15.5)
    centre = (8.0, 5.0, 7.0)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, centre, "fist", 0.42)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.4, 0, -1)}, centre, (0.52, -0.38, -0.95), 0.5,
                                       pose=None)
    return {"main": model(parts, d)}
