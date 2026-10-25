"""Krampus Boots: the feet of the Krampus set (issue #1306), built on the Krampus Chestplate
(the set's pilot module, whose palette, painters and 3D parts it imports).

Krampus walks on cloven hooves, so the boots are a pair of goat hooves: glossy black
hooves split in two at the toe, shaggy black fetlock fur spilling over them, and an iron
shackle round each ankle, broken off with a few links of chain still hanging from it and
a brass bell hanging at the front, and crimson-lined straps crossing over the fur.

Worn (humanoid layer, 4x): the boots cover the leg from row 27 down (layer row 107),
just under the Leggings' knee cop. Shaggy fur from the top (its locks hanging in a
ragged edge over the hoof) over a crimson-lined strap, the iron shackle with brass
rivets and an ember stud across the ankle, a bell and a couple of chain links hanging
from it at the front, a bell on the outside, and the black hoof with its cloven split at
the front; rows 45-47 are under the ground.

Item: a pair in 3D standing side by side as worn: the cloven hooves with rounded toes
and dewclaws at the heel, the fur shaft and the fetlock flaring over the hoof, the
shackle, its broken chain and the bell standing proud.
"""
from __future__ import annotations

import random

import numpy as np

from art.items.krampus_chestplate import (EMBER, IRON, STRAP, S, bell, bell_3d, cloth, fur, fur_tufts, iron, mask_rect,
                                          rivet, save_set_materials, smooth_noise, swatch, tilt_tufts)
from art.items.snowveil_guard import C, _hash, blank, fur_roll, grid, pick, shadow, to_image
from art.kit import HUMANOID, bar, box, display, mirror, model, place, region, save_layer, turn

ID = "krampus_boots"
NAME = "Krampus Boots"
KIND = "boots"
COUNTERPART = "item/netherite_boots"

# glossy blue-black hoof, kept apart from the warm black-brown fur so the two never merge
HOOF_PAL = ["#060609", "#0d0d13", "#16161f", "#21212c", "#2f2f3d", "#444456", "#626277", "#8c8ca3", "#c3c3d6"]

FW, FH = 4 * S, 12 * S        # leg face size (texels)
TOP = 27                      # the boot's top edge
BAND = (30, 33)               # the crimson-lined strap under the fur cuff (edges dark leather)
SHACKLE = (35, 37)            # the iron shackle's rows
HOOF = 40                     # the hoof's top row (the coronet); the fur hangs over it
GROUND = 45                   # rows below are under the ground
SPLIT = (7, 8)                # the cloven split's columns on the front


def hoof(a: np.ndarray, mask: np.ndarray, seed: int) -> None:
    """Glossy black hoof: dark horn with fine vertical growth lines, a cool sheen band
    near the top and a lit coronet edge."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    n = smooth_noise(h, w, 4, seed=seed)
    idx = np.rint(2.5 + (n - 0.5) * 1.2 + (_hash(X, 0, seed + 1) > 0.7) * 1 - (_hash(X, 1, seed + 2) > 0.8)).astype(int)
    idx = idx - (Y >= GROUND - 2)
    shadow(a, mask, 0.5, reach=1)
    a[mask] = pick(HOOF_PAL, idx)[mask]
    a[mask & (Y == HOOF)] = C(HOOF_PAL[6])
    sheen = mask & (Y == HOOF + 2) & (X % 4 != 3)
    a[sheen] = C(HOOF_PAL[7])
    a[sheen & (X % 4 == 1)] = C(HOOF_PAL[8])


def fur_shaft(a: np.ndarray, seed: int) -> None:
    """Shaggy fur from the top down to a ragged fetlock edge hanging over the hoof."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    rows = [HOOF + 1 + (1 if _hash(c // 2, 0, seed) > 0.5 else 0) + (1 if c % 3 == 1 else 0) for c in range(w)]
    m = (Y >= TOP) & (Y <= np.asarray(rows)[None, :])
    fur(a, m, seed=seed, lift=1, long=2)
    # the top edge: a darker, denser roll where it meets the Leggings
    a[TOP, :] = np.where(a[TOP, :, 3:4] > 0, a[TOP, :] * np.array([0.7, 0.7, 0.7, 1]), a[TOP, :])


def shackle(a: np.ndarray, seed: int, rivets=(2, 12)) -> None:
    h, w = a.shape[:2]
    m = mask_rect(h, w, 0, SHACKLE[0], w - 1, SHACKLE[1])
    iron(a, m, seed=seed, bias=0.35, ao=0.6)
    for x in rivets:
        rivet(a, x, SHACKLE[0] + 1)


def hanging_chain(a: np.ndarray, x: int, y: int, links: int = 3) -> None:
    """A broken chain of `links` links hanging from (x, y): face-on rings (3 wide) and
    edge-on links (1 wide) alternating down."""
    h, w = a.shape[:2]
    m = np.zeros((h, w), bool)
    cy = y
    for k in range(links):
        if k % 2 == 0:
            m[cy:cy + 3, x:x + 3] = True
            cy += 3
        else:
            m[cy:cy + 2, x + 1] = True
            cy += 2
    shadow(a, m, 0.55, reach=1)
    cy = y
    for k in range(links):
        if k % 2 == 0:
            a[cy, x:x + 3] = C(IRON[8])
            a[cy + 1, x] = C(IRON[7])
            a[cy + 1, x + 1] = C(IRON[0])
            a[cy + 1, x + 2] = C(IRON[3])
            a[cy + 2, x:x + 3] = C(IRON[2])
            cy += 3
        else:
            a[cy, x + 1] = C(IRON[7])
            a[cy + 1, x + 1] = C(IRON[4])
            cy += 2


def base_face(seed: int) -> np.ndarray:
    a = blank(FW, FH)
    hoof(a, mask_rect(FH, FW, 0, HOOF, FW - 1, FH - 1), seed)
    fur_shaft(a, seed + 3)
    # the crimson-lined strap under the cuff: dark leather edges, red cloth between
    band = mask_rect(FH, FW, 0, BAND[0], FW - 1, BAND[1])
    shadow(a, band, 0.5, reach=1, down_only=True)
    cloth(a, band, seed=seed + 7, bias=0.35)
    a[BAND[0], :] = C(STRAP[4])
    a[BAND[1], :] = C(STRAP[0])
    # the fur cuff spilling over the top of the strap
    X, Y = grid(FH, FW)
    rows = [BAND[0] + (1 if _hash(c // 2, 3, seed) > 0.55 else 0) for c in range(FW)]
    cuff = (Y >= TOP) & (Y <= np.asarray(rows)[None, :])
    fur(a, cuff, seed=seed + 9, lift=2, long=0)
    return a


def paint_leg_front(seed: int = 101) -> np.ndarray:
    a = base_face(seed)
    shackle(a, seed + 5)
    # the ember stud on the shackle and the bell hanging from it at the front
    a[SHACKLE[0] + 1, 7:9] = C(EMBER[5])
    a[SHACKLE[0] + 2, 7:9] = C(EMBER[3])
    bell(a, 1, SHACKLE[1] + 1)
    hanging_chain(a, 11, SHACKLE[1] + 1, 3)
    # the cloven split down the front of the hoof
    h, w = a.shape[:2]
    a[HOOF + 1:GROUND, SPLIT[0]] = C(HOOF_PAL[0])
    a[HOOF + 1:GROUND, SPLIT[1]] = C(HOOF_PAL[1])
    a[HOOF + 1:GROUND, SPLIT[0] - 1] = C(HOOF_PAL[5])
    a[HOOF + 1:GROUND, SPLIT[1] + 1] = C(HOOF_PAL[2])
    return a


def paint_leg_back(seed: int = 131) -> np.ndarray:
    a = base_face(seed)
    shackle(a, seed + 5, rivets=(7,))
    # dewclaws: two small dark nubs above the heel
    for x in (4, 10):
        a[HOOF - 1:HOOF + 1, x:x + 2] = C(HOOF_PAL[1])
        a[HOOF - 1, x] = C(HOOF_PAL[6])
    return a


def paint_leg_side(outer: bool, seed: int) -> np.ndarray:
    """The outer face (left = back, right = front) carries the bell; the inner face is the
    plain mirror image (left = front)."""
    a = base_face(seed)
    shackle(a, seed + 5, rivets=(3, 11) if outer else (7,))
    if outer:
        bell(a, 5, SHACKLE[1] + 1)
    return a if outer else a[:, ::-1].copy()


def paint_leg_sole(seed: int = 151) -> np.ndarray:
    """The underside: two hoof pads with the split between them."""
    a = blank(FW, FW)
    hoof(a, np.ones((FW, FW), bool), seed)
    a[:, SPLIT[0]:SPLIT[1] + 1] = C(HOOF_PAL[0])
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
TZ0, SZ0, SZ1 = -0.6, 6.0, 11.6  # toe front, shaft front, shaft back (z)
HOOF_H, H = 3.8, 9.2          # hoof height (the coronet), shaft height
GAP = 0.3                     # the cloven split's width
FZ = 3.2                      # where the fetlock fur starts over the toes (z); the tips run out in front of it


def _hoof_swatch(a):
    """Glossy black hoof for the 3D shell: growth lines running down (v), a sheen band
    near the top, a lit top row."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    n = smooth_noise(h, w, 8, seed=3)
    idx = np.rint(2.5 + (n - 0.5) * 1.2 + (_hash(X, 0, 4) > 0.7) - (_hash(X, 1, 5) > 0.8)).astype(int)
    a[:] = pick(HOOF_PAL, idx)
    a[3:5] = pick(HOOF_PAL, idx[3:5] + 4)
    a[4, ::4] = C(HOOF_PAL[8])
    a[0] = C(HOOF_PAL[7])
    a[1] = C(HOOF_PAL[5])


def textures() -> None:
    save_layer(paint_humanoid(), "humanoid")
    swatch("hoof", _hoof_swatch)
    save_set_materials(skip=("leather", "horn", "bone"))


def hoof_3d() -> list[dict]:
    """The cloven hoof: two toes split down the middle, each narrowing to a rounded point
    in steps and turned in a little so the tips meet, a full heel behind them flaring at
    the sole, and dewclaws at the back."""
    p = []
    mid = (BX0 + BX1) / 2
    for sgn, (xa, xb) in ((-1, (BX0, mid - GAP / 2)), (1, (mid + GAP / 2, BX1))):
        toe = [box((xa, 0.0, TZ0 + 1.6), (xb, HOOF_H, SZ0 + 0.5), "hoof", offset=(int(xa) % 6, 0)),
               box((xa + 0.25, 0.0, TZ0 + 0.6), (xb - 0.25, HOOF_H - 0.7, TZ0 + 1.7), "hoof", offset=(2, 1)),
               box((xa + 0.6, 0.0, TZ0 - 0.2), (xb - 0.6, HOOF_H - 1.7, TZ0 + 0.7), "hoof", offset=(3, 2)),
               box((xa + 0.95, 0.0, TZ0 - 0.7), (xb - 0.95, HOOF_H - 2.6, TZ0 - 0.1), "hoof", offset=(4, 3))]
        # the outer toe wall is turned in toward the split, so the pair reads cloven
        turn(toe, sgn * 5, "y", ((xa + xb) / 2, 0.0, SZ0))
        p += toe
    # the split's dark inside
    p.append(box((mid - GAP / 2, 0.05, TZ0 + 0.6), (mid + GAP / 2, HOOF_H - 0.3, SZ0), "lining"))
    # the heel, flaring at the sole
    p.append(box((BX0, 0.0, SZ0), (BX1, HOOF_H - 0.2, SZ1), "hoof", offset=(0, 0)))
    p.append(box((BX0 - 0.25, 0.0, SZ0 + 0.3), (BX1 + 0.25, 0.7, SZ1 + 0.25), "hoof", offset=(0, 3)))
    # the sole: a dark rim under it all
    p.append(box((BX0 - 0.2, -0.25, TZ0 + 0.2), (BX1 + 0.2, 0.05, SZ1 + 0.3), "dark_iron", offset=(0, 4)))
    # dewclaws at the back of the fetlock
    for x in (BX0 + 1.2, BX1 - 1.2):
        p.append(bar((x, HOOF_H + 0.9, SZ1 + 0.1), (x, HOOF_H - 0.3, SZ1 + 0.8), 0.8, 0.6, "hoof", offset=(0, 0)))
    return p


def shaft_3d() -> list[dict]:
    """The furry shaft above the hoof, the fetlock flaring out over the coronet, locks
    hanging down over the hoof all round and shaggy tufts breaking the top."""
    p = [box((BX0 + 0.1, HOOF_H - 0.3, SZ0 - 1.0), (BX1 - 0.1, H, SZ1 - 0.1), "fur", offset=(1, 2),
             faces={"up": "lining"})]
    # the fur reaching forward over the top of the hoof
    p.append(box((BX0 + 0.2, HOOF_H - 0.4, FZ + 0.4), (BX1 - 0.2, HOOF_H + 0.9, SZ0), "fur", offset=(4, 3)))
    # the fetlock: a rounded roll over the coronet
    p += fur_roll((BX0 - 0.6, HOOF_H - 0.6, FZ), (BX1 + 0.6, HOOF_H + 1.8, SZ1 + 0.6), r=0.5, seed=2)
    hang = fur_tufts(BX0 - 0.5, BX1 + 0.5, HOOF_H + 0.4, FZ - 0.2, FZ + 0.4, seed=5, size=0.55, step=0.8)
    hang += fur_tufts(BX0 - 0.5, BX1 + 0.5, HOOF_H + 0.4, SZ1 + 0.1, SZ1 + 0.7, seed=6, size=0.55, step=0.85)
    for xa, xb, seed in ((BX1 + 0.05, BX1 + 0.65, 7), (BX0 - 0.65, BX0 - 0.05, 8)):
        side = fur_tufts(FZ, SZ1 + 0.4, HOOF_H + 0.4, 0, 1, seed=seed, size=0.55, step=0.9)
        for e in side:
            (a0, y0, _), (a1, y1, _) = e["from"], e["to"]
            e["from"], e["to"] = [xa, y0, a0], [xb, y1, a1]
        hang += side
    # shingled rows of locks round the shaft so it reads shaggy, not boxy
    for y, seed in ((8.9, 21),):
        hang += fur_tufts(BX0 - 0.1, BX1 + 0.1, y, SZ0 - 1.35, SZ0 - 0.9, seed=seed, size=0.8, step=0.9)
        hang += fur_tufts(BX0 - 0.1, BX1 + 0.1, y, SZ1 - 0.15, SZ1 + 0.3, seed=seed + 5, size=0.8, step=0.9)
        for xa, xb in ((BX1 - 0.2, BX1 + 0.25), (BX0 - 0.25, BX0 + 0.2)):
            side = fur_tufts(SZ0 - 1.0, SZ1 - 0.1, y, 0, 1, seed=seed + int(xa), size=0.8, step=0.95)
            for e in side:
                (a0, y0, _), (a1, y1, _) = e["from"], e["to"]
                e["from"], e["to"] = [xa, y0, a0], [xb, y1, a1]
            hang += side
    tilt_tufts(hang, 3, True, 10)
    # a shaggy roll round the top and tufts standing on it
    p += fur_roll((BX0 - 0.4, H - 1.4, SZ0 - 1.4), (BX1 + 0.4, H + 0.6, SZ1 + 0.4), r=0.45, seed=4)
    rng = random.Random(9)
    tops = []
    for k in range(5):
        x = BX0 + 0.6 + rng.uniform(0, BX1 - BX0 - 1.2)
        z = SZ0 - 0.8 + rng.uniform(0, SZ1 - SZ0)
        tops.append(box((x - 0.45, H + 0.3, z - 0.45), (x + 0.45, H + 0.3 + rng.uniform(0.4, 0.9), z + 0.45), "fur",
                        offset=(rng.randrange(12), rng.randrange(12))))
    return p + hang + tilt_tufts(tops, 11, False, 18)


def shackle_3d() -> list[dict]:
    """The iron shackle round the ankle with brass rivets, a broken chain hanging from its
    outer front corner and a brass bell hanging from its middle over the toes."""
    y0, y1 = 5.6, 7.0
    p = [box((BX0 - 0.25, y0, SZ0 - 1.25), (BX1 + 0.25, y1, SZ1 + 0.25), "iron", skip=("up", "down"),
             offset=(0, 0))]
    p.append(box((BX0 - 0.1, y0 + 0.45, SZ0 - 1.35), (BX1 + 0.1, y0 + 0.95, SZ0 - 1.2), "dark_iron"))
    for x in (BX0 + 0.8, BX1 - 0.8):
        p.append(box((x - 0.22, y0 + 0.5, SZ0 - 1.5), (x + 0.22, y0 + 0.95, SZ0 - 1.25), "brass"))
    # the broken chain: three links hanging from the outer front corner
    pts = [(BX1 - 0.6, y0 + 0.1, SZ0 - 1.45), (BX1 - 0.45, y0 - 1.0, SZ0 - 1.75), (BX1 - 0.35, y0 - 2.1, SZ0 - 1.95),
           (BX1 - 0.3, y0 - 3.0, SZ0 - 2.05)]
    for k in range(3):
        a, b = pts[k], pts[k + 1]
        if k % 2 == 0:
            p.append(bar(a, b, 0.7, 0.22, "iron", offset=(k, 0)))
        else:
            p.append(bar(a, b, 0.22, 0.7, "iron", offset=(k, 1)))
    # the front bell, hanging from the middle of the shackle over the toes
    mid = (BX0 + BX1) / 2
    p.append(box((mid - 0.3, y0 - 0.2, SZ0 - 1.55), (mid + 0.3, y0 + 0.5, SZ0 - 1.3), "iron"))
    p += bell_3d(mid, y0 - 0.1, SZ0 - 2.2, 1.0)
    return p


def straps_3d() -> list[dict]:
    """Two crimson-lined leather straps crossing in an X over the front of the fur shaft,
    an ember stud in a brass setting where they cross."""
    p = []
    mid = (BX0 + BX1) / 2
    z = SZ0 - 1.55
    for sgn in (1, -1):
        a, b = (BX0 - 0.1, 8.0 + sgn * 0.9, z), (BX1 + 0.1, 8.0 - sgn * 0.9, z)
        p.append(bar(a, b, 0.95, 0.22, "strap", offset=(0, 1)))
        p.append(bar((a[0], a[1], z - 0.12), (b[0], b[1], z - 0.12), 0.5, 0.12, "crimson", offset=(0, 2)))
    p.append(box((mid - 0.45, 7.55, z - 0.35), (mid + 0.45, 8.45, z - 0.1), "brass"))
    p.append(box((mid - 0.25, 7.75, z - 0.45), (mid + 0.25, 8.25, z - 0.3), "ember", glow=10, uv="full", shade=False))
    return p


def build() -> list[dict]:
    right = hoof_3d() + shaft_3d() + shackle_3d() + straps_3d()
    return right + mirror(right, "x", 8.0)


def models() -> dict:
    parts = build()
    d = display(KIND, parts, gui_rotation=(25, 140, 0), gui_span=15.5)
    centre = (8.0, 6.0, 7.0)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, centre, "fist", 0.42)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.4, 0, -1)}, centre, (0.52, -0.38, -0.95), 0.5,
                                       pose=None)
    return {"main": model(parts, d)}
