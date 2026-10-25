"""Viking Boots: the feet of the Viking set (issue #1294), built on the Viking Chestplate
(the set's pilot module, whose palette and painters it imports).

Heavy turnshoe-style war boots in oiled brown leather: a thick cuff of the set's brown
bear fur round the top, a riveted steel shin plate down the front, two dark straps with
steel buckles round the ankle, a steel toe cap and a dark, stitched sole.

Worn (humanoid layer, 4x): the boots cover the leg from row 27 down (layer row 107), just
under the Leggings' knee cop, so the cop stays in view with the full set on. The fur cuff
hangs over the top, then the leather with its shin plate (front), straps (all round,
buckled on the outside) and the toe cap; rows 45-47 are under the ground.

Item: a pair of boots in 3D, standing side by side as worn: hollow shafts, the fur cuff
as a rounded roll with tufts hanging from it, the shin plate and its rivets, the straps
and buckles standing proud, the foot reaching forward to a capped toe, a heel block and
the sole.
"""
from __future__ import annotations

import random

import numpy as np

from art.items.snowveil_guard import C, _hash, blank, fur_roll, grid, pick, shadow, to_image
from art.items.viking_chestplate import (LEATHER, STEEL, STRAP, S, boss_3d, fur, fur_tufts, leather, mask_rect,
                                         rivet, save_set_materials, smooth_noise, steel, swatch)
from art.kit import HUMANOID, bar, box, display, mirror, model, place, region, save, save_layer

ID = "viking_boots"
NAME = "Viking Boots"
KIND = "boots"
COUNTERPART = "item/netherite_boots"

FW, FH = 4 * S, 12 * S        # leg face size (texels)
TOP = 27                      # the boot's top edge (the fur cuff's top)
CUFF = 31                     # the fur cuff's base lower edge
SHIN = (32, 40)               # the steel shin plate's rows (front)
STRAPS = (35, 39)             # the two ankle straps' top rows (2 rows each)
TOE = (41, 44)                # the steel toe cap (front) and the sole line (row 44)
SEAM_FRONT = (12, 13)         # the gap between the boots (see sync_inner)
SEAM_BACK = (2, 3)


def soft_leather(a: np.ndarray, mask: np.ndarray, seed: int) -> None:
    """Soft, oiled boot leather: a smooth mottled field (no row-wise banding), short
    vertical wear creases, a few lit scuffs."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    n = smooth_noise(h, w, 6, seed=seed)
    idx = np.rint(3 + (n - 0.5) * 1.35).astype(int)
    crease = (_hash(X, Y // 3, seed + 1) > 0.9) & (_hash(X // 2, Y // 6, seed + 2) > 0.5)
    idx = idx - crease
    idx = idx + ((_hash(X, Y, seed + 4) > 0.96) & ~crease)
    a[mask] = pick(LEATHER, idx)[mask]


def boot_leather(a: np.ndarray, seed: int) -> None:
    """The boot's leather from the top down: soft mid-brown, a touch darker toward the
    sole."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    m = mask_rect(h, w, 0, TOP, w - 1, h - 1)
    soft_leather(a, m, seed)
    a[m & (Y > 40), :3] *= 0.88


def cross_straps(a: np.ndarray, seed: int, y0: int = 33, y1: int = 40, buckle_at=None) -> None:
    """Two dark leather straps crossing in an X over the shaft (rows y0..y1), like the
    Leggings' wraps, each lit along its upper edge, with a steel buckle when buckle_at."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    cy = (y0 + y1) / 2
    k = (w - 1) / (y1 - y0)
    for sgn, tone in ((1, 0), (-1, 1)):
        d = (X + 0.5 - w / 2) - sgn * (Y + 0.5 - cy) * k
        m = (np.abs(d) <= 1.3) & (Y >= y0) & (Y <= y1)
        shadow(a, m, 0.4, reach=1)
        a[m] = C(STRAP[3 - tone])
        a[m & (d < -0.5)] = C(STRAP[5 - tone])
        a[m & (d > 0.5)] = C(STRAP[1])
    if buckle_at is not None:
        b = mask_rect(h, w, buckle_at, int(cy) - 2, buckle_at + 3, int(cy) + 1)
        steel(a, b, seed=seed, bias=0.6, ao=0.4)
        a[int(cy) - 1:int(cy) + 1, buckle_at + 1:buckle_at + 3] = C(STRAP[1])


def cuff(a: np.ndarray, seed: int) -> None:
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    rows = [CUFF + (1 if _hash(c // 3, 0, seed) > 0.5 else 0) + (1 if c % 3 == 1 else 0) for c in range(w)]
    m = (Y >= TOP) & (Y <= np.asarray(rows)[None, :])
    fur(a, m, seed=seed, lift=1)


def strap(a: np.ndarray, y: int, buckle_at=None) -> None:
    h, w = a.shape[:2]
    leather(a, mask_rect(h, w, 0, y, w - 1, y + 1), seed=y, stitch=False, ao=0.5)
    if buckle_at is not None:
        b = mask_rect(h, w, buckle_at, y - 1, buckle_at + 3, y + 2)
        steel(a, b, seed=y, bias=0.8, ao=0.4)
        a[y:y + 2, buckle_at + 1:buckle_at + 3] = C(STRAP[1])


def shin_plate(a: np.ndarray, seed: int, c0: int = 2, c1: int = 11) -> None:
    h, w = a.shape[:2]
    m = mask_rect(h, w, c0, SHIN[0], c1, SHIN[1])
    m[SHIN[1], c0] = m[SHIN[1], c1] = False
    steel(a, m, seed=seed, bias=-0.1, ao=0.6)
    mid = (c0 + c1) // 2
    a[SHIN[0] + 1:SHIN[1], mid] = C(STEEL[7])
    a[SHIN[0] + 1:SHIN[1], mid + 1] = C(STEEL[3])
    rivet(a, c0 + 1, SHIN[0] + 1)
    rivet(a, c1 - 2, SHIN[0] + 1)


def toe_cap(a: np.ndarray, seed: int, c0: int, c1: int) -> None:
    h, w = a.shape[:2]
    m = mask_rect(h, w, c0, TOE[0], c1, TOE[1] - 1)
    steel(a, m, seed=seed, bias=0.5, ao=0.5)
    sole(a)


def sole(a: np.ndarray) -> None:
    a[TOE[1], :] = C(STRAP[0])
    a[TOE[1] - 1, :] = np.where(a[TOE[1] - 1, :, 3:4] > 0, a[TOE[1] - 1, :], C(STRAP[1]))
    a[TOE[1] + 1:, :] = C(STRAP[0])


def sync_inner(a: np.ndarray, inner: str) -> None:
    """The two boot cubes overlap at the inner edge and z-fight there while the wearer
    stands still; mirroring that strip about its centre makes both legs agree (as the
    Shark Boots do). inner: "right" (front, sole) or "left" (back)."""
    pairs = ((10, 15), (11, 14), (12, 13)) if inner == "right" else ((5, 0), (4, 1), (3, 2))
    for src, dst in pairs:
        a[:, dst] = a[:, src]


def paint_leg_front(seed: int = 101) -> np.ndarray:
    a = blank(FW, FH)
    boot_leather(a, seed)
    shin_plate(a, seed + 3, 1, 10)
    cross_straps(a, seed + 3, 33, 40)
    strap(a, STRAPS[1] + 1)
    toe_cap(a, seed + 5, 0, 11)
    cuff(a, seed + 7)
    sync_inner(a, "right")
    return a


def paint_leg_back(seed: int = 131) -> np.ndarray:
    a = blank(FW, FH)
    boot_leather(a, seed)
    cross_straps(a, seed + 3, 32, 39)
    strap(a, STRAPS[1] + 1)
    # a heel counter: a darker stitched leather patch
    m = mask_rect(FH, FW, 3, 40, 12, TOE[1] - 1)
    leather(a, m, seed=seed + 2, stitch=True, ao=0.4)
    sole(a)
    cuff(a, seed + 7)
    sync_inner(a, "left")
    return a


def paint_leg_side(outer: bool, seed: int) -> np.ndarray:
    """The outer face (left = back, right = front) carries the buckles; the inner face is
    its mirror image without them (left = front)."""
    a = blank(FW, FH)
    boot_leather(a, seed)
    cross_straps(a, seed + 3, 32, 39, buckle_at=6 if outer else None)
    strap(a, STRAPS[1] + 1)
    # the toe cap wraps a little way round the side at the front
    m = mask_rect(FH, FW, 11, TOE[0] + 1, FW - 1, TOE[1] - 1)
    steel(a, m, seed=seed + 5, bias=0.3, ao=0.4)
    # a vertical seam with stitches down the side
    for y in range(CUFF + 2, TOE[0], 2):
        a[y, 6] = C(LEATHER[5])
    a[CUFF + 1:TOE[0], 7] = C(LEATHER[1])
    sole(a)
    cuff(a, seed + 7)
    if outer:
        rivet(a, 4, 41)
    return a if outer else a[:, ::-1].copy()


def paint_leg_sole(seed: int = 151) -> np.ndarray:
    a = blank(FW, FW)
    a[:] = C(STRAP[1])
    for y in range(2, FW, 3):
        a[y, 1:FW - 1] = C(STRAP[0])
    a[0:2] = C(STEEL[3])
    sync_inner(a, "right")
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

BX0, BX1 = 8.9, 14.3          # boot width (x)
TZ0, SZ0, SZ1 = 1.4, 6.0, 11.6  # toe front, shaft front, shaft back (z)
SOLE_Y, FOOT_H, H = 0.9, 4.4, 10.6   # sole top, foot (instep) height, shaft height


def _boot_swatch(a):
    """The boots' leather for the 3D shell: the worn layer's soft mottled leather, a
    stitched seam every 16 texels, a lit top row."""
    soft_leather(a, np.ones(a.shape[:2], bool), seed=12)
    X, Y = grid(64, 64)
    a[(X % 16 == 15)] = C(LEATHER[1])
    a[(X % 16 == 14) & (Y % 2 == 0)] = C(LEATHER[5])
    a[0] = C(LEATHER[5])


def textures() -> None:
    save_layer(paint_humanoid(), "humanoid")
    swatch("boot", _boot_swatch)
    save_set_materials(skip=("lamellar", "wool", "leather"))


def boot() -> list[dict]:
    p = []
    # the sole and heel
    p.append(box((BX0 - 0.2, 0.0, TZ0 - 0.25), (BX1 + 0.2, SOLE_Y, SZ1 + 0.3), "strap", offset=(0, 2)))
    p.append(box((BX0 + 0.3, -0.35, SZ1 - 2.6), (BX1 - 0.3, 0.05, SZ1 + 0.2), "strap", offset=(1, 3)))
    # the foot reaching forward and the shaft (hollow at the top)
    p.append(box((BX0, SOLE_Y, TZ0 + 0.4), (BX1, FOOT_H, SZ0 + 0.1), "boot", offset=(1, 4)))
    p.append(box((BX0 + 0.4, FOOT_H - 0.3, TZ0 + 1.2), (BX1 - 0.4, FOOT_H + 0.5, SZ0 + 0.1), "boot",
                 offset=(3, 1)))
    w = 0.6
    shell = [((BX0, SOLE_Y, SZ0), (BX1, H, SZ0 + w)), ((BX0, SOLE_Y, SZ1 - w), (BX1, H, SZ1)),
             ((BX0, SOLE_Y, SZ0), (BX0 + w, H, SZ1)), ((BX1 - w, SOLE_Y, SZ0), (BX1, H, SZ1))]
    for i, (a, b) in enumerate(shell):
        p.append(box(a, b, "boot", offset=(i * 4 % 12, 0)))
    p.append(box((BX0 + w, H - 2.0, SZ0 + w), (BX1 - w, H - 1.9, SZ1 - w), "lining",
                 skip=("north", "south", "east", "west", "down")))
    # the steel toe cap, a little proud of the foot, rounded at the front in two steps
    p.append(box((BX0 - 0.15, SOLE_Y, TZ0 + 0.6), (BX1 + 0.15, FOOT_H - 1.0, TZ0 + 2.3), "steel", offset=(0, 0)))
    p.append(box((BX0 + 0.35, SOLE_Y, TZ0 + 0.1), (BX1 - 0.35, FOOT_H - 1.3, TZ0 + 0.7), "steel", offset=(1, 0)))
    p.append(box((BX0 + 0.25, FOOT_H - 1.1, TZ0 + 0.9), (BX1 - 0.25, FOOT_H - 0.45, TZ0 + 2.6), "steel",
                 offset=(2, 0)))
    for x in (BX0 + 0.9, BX1 - 0.9):
        p.append(box((x - 0.2, 2.0, TZ0 + 0.4), (x + 0.2, 2.4, TZ0 + 0.6), "bronze"))
    # the shin plate down the front of the shaft in darker, weathered steel, with a lit
    # ridge and two rivets
    p.append(box((BX0 + 0.7, 4.6, SZ0 - 0.3), (BX1 - 0.7, 8.6, SZ0), "dark_steel", offset=(1, 0)))
    mid = (BX0 + BX1) / 2
    p.append(box((mid - 0.2, 4.8, SZ0 - 0.45), (mid + 0.2, 8.4, SZ0 - 0.3), "steel", offset=(0, 0)))
    for x in (BX0 + 1.2, BX1 - 1.2):
        p.append(box((x - 0.2, 7.9, SZ0 - 0.45), (x + 0.2, 8.3, SZ0 - 0.3), "bronze"))
    # two dark straps crossing in an X over the shin plate (front and back), like the
    # Leggings' wraps, an ankle strap round the foot, a steel buckle on the outside
    for z, sz in ((SZ0 - 0.55, 1), (SZ1 + 0.3, -1)):
        for sgn in (1, -1):
            p.append(bar((BX0 - 0.05, 6.9 + sgn * 1.4, z), (BX1 + 0.05, 6.9 - sgn * 1.4, z), 0.75, 0.25, "strap",
                         offset=(0, 1)))
    p.append(box((BX0 - 0.15, 3.9, TZ0 + 2.6), (BX1 + 0.15, 4.6, SZ1 + 0.15), "strap", skip=("up", "down"),
                 offset=(0, 1)))
    p.append(box((BX1 + 0.1, 3.7, 8.0), (BX1 + 0.35, 4.8, 9.3), "steel", offset=(0, 0)))
    p.append(box((BX1 + 0.3, 4.0, 8.3), (BX1 + 0.4, 4.5, 9.0), "strap"))
    # the set's boss on the outside of the shaft, under the cuff
    p += boss_3d(BX1, 7.4, (SZ0 + SZ1) / 2 + 0.6, 0.85, facing="east")
    # the bear-fur cuff: a rounded roll round the top, tufts hanging from it
    p += fur_roll((BX0 - 0.6, H - 1.6, SZ0 - 0.6), (BX1 + 0.6, H + 0.9, SZ1 + 0.6), r=0.45, seed=4)
    rng = random.Random(3)
    hang = fur_tufts(BX0 - 0.5, BX1 + 0.5, H - 1.4, SZ0 - 0.65, SZ0 - 0.05, seed=5, size=0.7, step=0.9)
    hang += fur_tufts(BX0 - 0.5, BX1 + 0.5, H - 1.4, SZ1 + 0.05, SZ1 + 0.65, seed=6, size=0.7, step=0.9)
    side = fur_tufts(SZ0 - 0.4, SZ1 + 0.4, H - 1.4, 0, 0.6, seed=7, size=0.65, step=1.0)
    for e in side:
        (xa, ya, za), (xb, yb, zb) = e["from"], e["to"]
        e["from"], e["to"] = [BX1 + 0.05 + za, ya, xa], [BX1 + 0.05 + zb, yb, xb]
    for e in hang:
        e["to"][1] = round(e["to"][1] - rng.choice((0.0, 0.1)), 4)
    return p + hang + side


def build() -> list[dict]:
    right = boot()
    return right + mirror(right, "x", 8.0)


def models() -> dict:
    parts = build()
    d = display(KIND, parts, gui_rotation=(25, 140, 0), gui_span=15.5)
    centre = (8.0, 6.0, 7.0)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, centre, "fist", 0.42)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.4, 0, -1)}, centre, (0.52, -0.38, -0.95), 0.5,
                                       pose=None)
    return {"main": model(parts, d)}
