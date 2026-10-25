"""Krampus Leggings: the leg piece of the Krampus set (issue #1306), built on the Krampus
Chestplate (the set's pilot module, whose palette, painters and 3D parts it imports).

Krampus walks on goat legs, so these are shaggy black goat-fur legs in iron:

Worn (humanoid_leggings layer, 4x): the waistband is crimson coat cloth under the set's
dark belt with its brass buckle. Each leg is shaggy black-brown goat fur from the hip
down, its locks hanging in shingles. A jagged blackened-iron tasset hangs over the front
and outside of each thigh from a leather strap, and an iron knee cop with a raised spike
and brass rivets covers the knee (high enough that the Boots' fetlock fur, which starts
just under it, leaves it in view). A leather strap with a brass rivet binds the shin.

Everything is painted flat against the leg (no flaps), so nothing clips as the legs
swing.

Item: the pair in 3D wearing the same paintings, with the tassets (two stacked jagged
lames), the knee cops and their spikes, shaggy locks hanging from the thighs, the belt
and buckle standing proud, and a short chain with a brass bell hanging from the hip.
"""
from __future__ import annotations

import numpy as np

from art.items.krampus_chestplate import (BRASS, CRIMSON, EMBER, FUR, IRON, S, STRAP, bell_3d, belt, chain_3d, cloth,
                                          fur, fur_tufts, iron, jagged_lame, leather, mask_rect, rivet,
                                          save_set_materials, spike, tilt_tufts)
from art.items.snowveil_guard import C, blank, fur_roll, grid, shadow, shift, to_image
from art.kit import HUMANOID, box, display, mirror, model, place, region, save, save_layer, turn

ID = "krampus_leggings"
NAME = "Krampus Leggings"
KIND = "leggings"
COUNTERPART = "item/netherite_leggings"

# ======================================================================================
# Worn layer (humanoid_leggings, 256x128)
# ======================================================================================
# Leg faces are 16x48 texels. On the wearer's right leg: outer (left = back), front
# (left = outer), inner (left = front), back (left = inner); the left leg mirrors it.
# The legs overlap, so the inner 4 texel columns of the front (12-15) and of the back
# (0-3) are painted one colour per row; the waistband's last unit and the legs' first
# unit share the front/back plane, so both are the same dark seam.

SEAM = 4                        # rows 0-3 of the legs: the seam under the waistband
TASSET = (4, 14)                # the jagged tasset's rows (its strap is the first 2)
KNEE = (14, 25)                 # the knee cop's rows: above the Boots' fetlock fur (row 27)
KNEE_C = 6                      # its centre column (worn: the visible front, columns 0-11)
SHIN_STRAP = 33                 # the strap binding the shin (2 rows)
WAIST = 32                      # the waistband is the body's lower 4 units (rows 32-47)
WAIST_BELT = 34                 # its belt's top row (5 rows)
WAIST_SEAM = 44                 # its last rows: the seam shared with the legs


def even_strip(a: np.ndarray, c0: int, c1: int, src: int) -> None:
    """Texel columns c0..c1 one colour per row (from column src): the legs' shared strip."""
    a[:, c0:c1 + 1] = a[:, src:src + 1]


def tasset(a: np.ndarray, seed: int, c0: int, c1: int, rows=TASSET) -> None:
    """The jagged iron tasset over the thigh (columns c0..c1): a leather strap on top and
    two stacked lames with sawtoothed feet."""
    h, w = a.shape[:2]
    y0, y1 = rows
    mid = (y0 + y1) // 2
    jagged_lame(a, mid, y1 - 2, seed=seed + 1, bias=0.0, period=4, depth=2, phase=1, c0=c0, c1=c1)
    jagged_lame(a, y0 + 1, mid, seed=seed, bias=0.25, period=4, depth=2, c0=c0, c1=c1)
    leather(a, mask_rect(h, w, c0, y0, c1, y0 + 1), seed=seed + 2, stitch=False, ao=0)
    for x in (c0 + 1, c1 - 2):
        rivet(a, x, y0 + 3)


def knee_cop(a: np.ndarray, seed: int, cx: float = KNEE_C, half: float = 5.6) -> None:
    """The iron knee cop centred on column cx: a rounded plate, a raised spike up its
    middle (lit on its left, dark on its right) and a brass rivet either side."""
    h, w = a.shape[:2]
    y0, y1 = KNEE
    X, Y = grid(h, w)
    cy = (y0 + y1 + 1) / 2
    d = np.sqrt(((X + 0.5 - cx) / half) ** 2 + ((Y + 0.5 - cy) / ((y1 - y0 + 1) / 2)) ** 2)
    m = d <= 1.0
    iron(a, m, seed=seed, bias=0.6, ao=0.5)
    rim = m & (d > 0.8)
    lit = (X + 0.5 - cx) + (Y + 0.5 - cy) < 0
    a[rim & lit] = C(IRON[7])
    a[rim & ~lit] = C(IRON[1])
    # the spike: a narrow diamond ridge, widest at the knee's centre
    dx = X + 0.5 - cx
    ridge = m & (np.abs(dx) <= 1.6 * (1 - np.abs(Y + 0.5 - cy) / ((y1 - y0) / 2)))
    a[ridge & (dx < 0)] = C(IRON[8])
    a[ridge & (dx >= 0)] = C(IRON[2])
    for x in (int(cx - half + 1.5), int(cx + half - 3.0)):
        rivet(a, x, int(cy) - 1)
    # an ember stud set in the spike's root
    ex, ey = int(cx) - 1, int(cy)
    a[ey, ex:ex + 2] = C(EMBER[5])
    a[ey + 1, ex:ex + 2] = C(EMBER[3])


def tabard(a: np.ndarray, seed: int) -> None:
    """The crimson tabard hanging from the belt between the legs (the inner columns of
    the front face; with the mirrored left leg it makes one panel), cut to a point at the
    middle and edged in dark leather."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    ends = {10: 13, 11: 15}
    bottom = np.array([ends.get(c, 17) for c in range(w)])
    m = (X >= 10) & (Y <= bottom[None, :])
    shadow(a, m, 0.55, reach=1)
    cloth(a, m, seed=seed, bias=0.3)
    edge = m & ~shift(m, 0, -1)
    a[edge] = C(STRAP[0])
    a[m & (X == 10)] = C(STRAP[1])
    a[m & (X == 11) & (Y > 1)] = C(CRIMSON[6])
    rivet(a, 12, 1)


def strap_row(a: np.ndarray, y: int, rivet_at=None) -> None:
    h, w = a.shape[:2]
    leather(a, mask_rect(h, w, 0, y, w - 1, y + 1), stitch=False, ao=0.5)
    if rivet_at is not None:
        rivet(a, rivet_at, y)


def goat_fur(a: np.ndarray, seed: int, lift: int = 0) -> None:
    """Shaggy goat fur over the whole leg: one even field of long locks hanging down it,
    darkening a little toward the hoof."""
    h, w = a.shape[:2]
    fur(a, np.ones((h, w), bool), seed=seed, lift=lift, edge=False, long=3)
    X, Y = grid(h, w)
    a[..., :3] *= (1 - 0.18 * (Y / h))[..., None]


def paint_leg_face(kind: str, seed: int, worn: bool = True, lift: int = 0) -> np.ndarray:
    """One face of the wearer's right leg: "outer", "front", "inner" or "back". worn=False
    is the item's version (its tasset and knee cop are 3D); lift lightens the fur (the
    item's, so the inventory icon doesn't turn to mud)."""
    w, h = 4 * S, 12 * S
    a = blank(w, h)
    goat_fur(a, seed, lift=lift - (1 if kind == "inner" else 0))
    strap_row(a, SHIN_STRAP, rivet_at=7 if kind == "outer" else None)
    if worn:
        if kind == "front":
            tasset(a, seed + 4, 0, 11)
            knee_cop(a, seed + 3)
            tabard(a, seed + 8)
        elif kind == "outer":
            tasset(a, seed + 5, 4, 15)
            y0, y1 = KNEE
            side = mask_rect(h, w, 10, y0 + 2, 15, y1 - 2)
            iron(a, side, seed=seed + 9, bias=0.3, ao=0.5)
            rivet(a, 12, (y0 + y1) // 2)
        elif kind == "back":
            tasset(a, seed + 6, 4, 15, rows=(TASSET[0], TASSET[0] + 7))
        elif kind == "inner":
            y0, y1 = KNEE
            side = mask_rect(h, w, 0, y0 + 2, 5, y1 - 2)
            iron(a, side, seed=seed + 9, bias=0.1, ao=0.5)
        if kind in ("front", "back"):
            a[0:SEAM] = C(CRIMSON[1])
    a[h - 1, :, :3] *= 0.8
    if kind == "front":
        even_strip(a, 12, 15, 12)
    elif kind == "back":
        even_strip(a, 0, 3, 3)
    return a


def paint_leg_top(seed: int = 71) -> np.ndarray:
    a = blank(4 * S, 4 * S)
    fur(a, np.ones((4 * S, 4 * S), bool), seed=seed, hang=False, edge=False)
    return a


def paint_sole() -> np.ndarray:
    a = blank(4 * S, 4 * S)
    a[:] = C(FUR[1])
    return a


def paint_waist(kind: str, seed: int) -> np.ndarray:
    """The waistband (the body's lower 4 units): kind "front", "back" or "side" (the
    wearer's right side; the left is its mirror). Crimson cloth, the dark belt (a brass
    buckle at the front, brass studs at the back); the last rows are the shared seam."""
    w, h = (8 if kind in ("front", "back") else 4) * S, 12 * S
    a = blank(w, h)
    band = mask_rect(h, w, 0, WAIST, w - 1, h - 1)
    cloth(a, band, seed=seed, bias=0.1)
    belt(a, WAIST_BELT, 5, buckle_x=w // 2 if kind == "front" else None, seed=seed)
    if kind == "back":
        for x in (6, 25):
            rivet(a, x, WAIST_BELT + 1)
    a[WAIST_SEAM:] = C(CRIMSON[1])
    return a


def paint_humanoid() -> np.ndarray:
    out = blank(64 * S, 32 * S)

    def put(name, arr):
        x0, y0, x1, y1 = region(HUMANOID, name, S)
        out[y0:y1 + 1, x0:x1 + 1] = arr

    put("leg_outer", paint_leg_face("outer", 101))
    put("leg_front", paint_leg_face("front", 111))
    put("leg_inner", paint_leg_face("inner", 121))
    put("leg_back", paint_leg_face("back", 131))
    put("leg_top", paint_leg_top())
    put("leg_sole", paint_sole())
    put("body_front", paint_waist("front", 141))
    put("body_back", paint_waist("back", 151))
    side = paint_waist("side", 161)
    put("body_right", side)
    put("body_left", side[:, ::-1])
    return out


# ======================================================================================
# Item: a pair of leggings in 3D (front = north / -z, the wearer's right = +x, like the
# Chestplate). The worn paintings are projected onto the faces.
# ======================================================================================

HX0, HX1 = 2.8, 13.2          # hips; the legs' outer faces line up with them
LEG = (9.2, 13.2)             # the right leg's x range (the left leg is its mirror)
LZ0, LZ1 = 5.7, 10.3
LY1, HY1 = 13.2, 16.8         # top of the legs, top of the hips
LW, LD = LEG[1] - LEG[0], LZ1 - LZ0


def leg_atlas() -> np.ndarray:
    """The "legs" texture: the leg faces side by side on rows 0-47 (outer, front, inner,
    back), the hips' front and back on rows 48-63."""
    a = blank(64, 64)
    for i, (kind, seed) in enumerate((("outer", 101), ("front", 111), ("inner", 121), ("back", 131))):
        face = paint_leg_face(kind, seed, worn=False, lift=1)
        if kind == "inner":
            face[..., :3] *= 0.7
        a[0:48, 16 * i:16 * i + 16] = face
    a[48:64, 0:32] = paint_waist("front", 141)[32:48]
    a[48:64, 32:64] = paint_waist("back", 151)[32:48]
    return a


def item_plates() -> None:
    """The knee cop painting for its 3D plate (64x64, 4 texels per uv unit), from row 0."""
    a = blank(64, 64)
    face = blank(16, 48)
    fur(face, np.ones((48, 16), bool), seed=1, edge=False, lift=1)
    knee_cop(face, 114, cx=8, half=8.6)
    a[0:KNEE[1] - KNEE[0] + 1, 0:16] = face[KNEE[0]:KNEE[1] + 1]
    save(to_image(a), "plates")


def textures() -> None:
    save_layer(to_image(paint_humanoid()), "humanoid_leggings")
    save(to_image(leg_atlas()), "legs")
    sides = blank(64, 64)
    side = paint_waist("side", 161)[32:48]
    sides[0:16, 0:16] = side
    sides[0:16, 16:32] = side[:, ::-1]
    sides[32:48, 0:16] = paint_sole()
    save(to_image(sides), "hips")
    item_plates()
    save_set_materials(skip=("leather", "horn", "bone"))


def _v_leg(y):
    return (LY1 - y) * 12 / LY1


def _y_leg(row):
    """Model height of leg-painting texel row `row`."""
    return LY1 - row / 4 * LY1 / 12


def _v_hip(y):
    return 12 + (HY1 - y) * 4 / (HY1 - LY1)


def leg_faces(frm, to) -> dict:
    (xa, ya, za), (xb, yb, zb) = frm, to
    vt, vb = _v_leg(yb), _v_leg(ya)
    return {"north": ("legs", [4 + (LEG[1] - xb) * 4 / LW, vt, 4 + (LEG[1] - xa) * 4 / LW, vb]),
            "south": ("legs", [12 + (xa - LEG[0]) * 4 / LW, vt, 12 + (xb - LEG[0]) * 4 / LW, vb]),
            "east": ("legs", [(LZ1 - zb) * 4 / LD, vt, (LZ1 - za) * 4 / LD, vb]),
            "west": ("legs", [8 + (za - LZ0) * 4 / LD, vt, 8 + (zb - LZ0) * 4 / LD, vb])}


def hip_faces(frm, to) -> dict:
    (xa, ya, za), (xb, yb, zb) = frm, to
    vt, vb = _v_hip(yb), _v_hip(ya)
    wv = HX1 - HX0
    st, sb = (HY1 - yb) * 4 / (HY1 - LY1), (HY1 - ya) * 4 / (HY1 - LY1)
    return {"north": ("legs", [(HX1 - xb) * 8 / wv, vt, (HX1 - xa) * 8 / wv, vb]),
            "south": ("legs", [8 + (xa - HX0) * 8 / wv, vt, 8 + (xb - HX0) * 8 / wv, vb]),
            "east": ("hips", [(LZ1 - zb) * 4 / LD, st, (LZ1 - za) * 4 / LD, sb]),
            "west": ("hips", [4 + (za - LZ0) * 4 / LD, st, 4 + (zb - LZ0) * 4 / LD, sb])}


def teeth(x0, x1, y, z, step=0.95, size=0.62, facing="north") -> list[dict]:
    """A row of iron sawteeth hanging from height y along x0..x1 at depth z."""
    out = []
    x = x0 + step / 2
    while x < x1:
        t = box((x - size / 2, y - size / 2, z - 0.12), (x + size / 2, y + size / 2, z + 0.12), "iron", offset=(1, 2))
        turn(t, 45, "z", (x, y, z))
        out.append(t)
        x += step
    return out


def tasset_3d() -> list[dict]:
    """The tasset over the right thigh: a leather strap and two stacked jagged lames
    wrapping the front and the outside, the lower one a little proud and splayed so the
    plates read layered, sawteeth along each foot."""
    parts = []
    y_top, y_bot = _y_leg(TASSET[0]), _y_leg(TASSET[1] + 1)
    y_mid = (y_top + y_bot) / 2 - 0.1
    for k, (ya, yb, out) in enumerate(((y_mid, y_top - 0.45, 0.3), (y_bot, y_mid + 0.25, 0.55))):
        course = [box((LEG[0] + 0.05, ya, LZ0 - out), (LEG[1] + out, yb, LZ0 + 0.1), "iron",
                      offset=(k * 2, 0), skip=("south",)),
                  box((LEG[1] - 0.1, ya, LZ0 - out), (LEG[1] + out, yb, LZ1 - 0.6), "iron",
                      offset=(k * 3, 0), skip=("west",))]
        course += teeth(LEG[0] + 0.05, LEG[1] + out, ya, LZ0 - out + 0.1)
        side = teeth(LZ0 - out, LZ1 - 0.6, ya, 0)
        for e in side:
            (xa, y0_, za), (xb, y1_, zb) = e["from"], e["to"]
            e["from"], e["to"] = [LEG[1] + out - 0.12 + za + 0.12, y0_, xa], [LEG[1] + out + zb, y1_, xb]
            r = e["rotation"]
            o = r["origin"]
            e["rotation"] = {"origin": [LEG[1] + out, o[1], o[0]], "axis": "x", "angle": r["angle"]}
        course += side
        for x in (LEG[0] + 0.7, LEG[1] - 0.5):
            course.append(box((x - 0.2, yb - 0.8, LZ0 - out - 0.2), (x + 0.2, yb - 0.4, LZ0 - out), "brass"))
        turn(course, 6 * k + 3, "x", (8.0, yb, LZ0))
        parts += course
    parts.append(box((LEG[0] - 0.1, y_top - 0.55, LZ0 - 0.2), (LEG[1] + 0.2, y_top, LZ1 + 0.35), "strap",
                     skip=("up", "down")))
    parts.append(box((LEG[1] + 0.15, y_top - 0.5, 7.0), (LEG[1] + 0.35, y_top - 0.05, 7.6), "brass"))
    return parts


def knee() -> list[dict]:
    """The knee cop standing proud of the right knee (a rounded plate in three steps),
    wrapping round its sides, with a spike jutting forward."""
    y1, y0 = _y_leg(KNEE[0]), _y_leg(KNEE[1] + 1)
    z = LZ0 - 0.35
    u = (KNEE[1] - KNEE[0] + 1) / 4
    parts = [box((LEG[0] + 0.2, y0 + 0.35, z), (LEG[1] - 0.2, y1 - 0.35, LZ0 + 1.6), "iron",
                 faces={"north": ("plates", [0.25, 0.35, 3.75, u - 0.35])}, skip=("south",)),
             box((LEG[0] + 0.6, y0, z + 0.1), (LEG[1] - 0.6, y1, LZ0 + 1.6), "iron", skip=("south",)),
             box((LEG[0] - 0.15, y0 + 0.9, z + 0.4), (LEG[1] + 0.15, y1 - 0.9, LZ0 + 2.6), "iron",
                 skip=("south",))]
    cx, cy = (LEG[0] + LEG[1]) / 2, (y0 + y1) / 2
    parts += spike((cx, cy, z + 0.2), (cx, cy + 0.8, z - 1.9), 0.9, steps=3)
    # the ember stud at the spike's root, in a brass setting
    parts.append(box((cx - 0.55, cy - 1.25, z - 0.2), (cx + 0.55, cy - 0.35, z + 0.1), "brass"))
    parts.append(box((cx - 0.32, cy - 1.05, z - 0.3), (cx + 0.32, cy - 0.55, z - 0.15), "ember", glow=10, uv="full",
                     shade=False))
    return parts


def fur_locks() -> list[dict]:
    """Shaggy locks hanging round the thigh under the tasset and round the shin, so the
    goat leg reads furry in 3D."""
    parts = []
    for y, seed in ((_y_leg(TASSET[1] + 1) - 0.1, 31), (_y_leg(KNEE[1] + 1) - 0.2, 32), (_y_leg(40), 33)):
        front = fur_tufts(LEG[0] + 0.1, LEG[1] - 0.1, y, LZ0 - 0.5, LZ0 + 0.1, seed=seed, size=0.8, step=0.8)
        back = fur_tufts(LEG[0] + 0.1, LEG[1] - 0.1, y, LZ1 - 0.1, LZ1 + 0.5, seed=seed + 5, size=0.8, step=0.8)
        side = fur_tufts(LZ0 + 0.1, LZ1 - 0.1, y, 0, 1, seed=seed + 9, size=0.8, step=0.85)
        for e in side:
            (a0, y0, _), (a1, y1, _) = e["from"], e["to"]
            e["from"], e["to"] = [LEG[1] - 0.1, y0, a0], [LEG[1] + 0.5, y1, a1]
        parts += tilt_tufts(front + back + side, seed, True, 9)
    return parts


def shin_3d() -> list[dict]:
    """The strap binding the shin, a little proud of the fur, and the ankle band."""
    parts = []
    ya, yb = _y_leg(SHIN_STRAP + 2), _y_leg(SHIN_STRAP)
    parts.append(box((LEG[0] - 0.15, ya, LZ0 - 0.15), (LEG[1] + 0.15, yb, LZ1 + 0.15), "strap",
                     skip=("up", "down")))
    parts.append(box((LEG[1] + 0.1, ya - 0.1, 7.75), (LEG[1] + 0.35, yb + 0.1, 8.25), "brass"))
    return parts


def right_leg() -> list[dict]:
    frm, to = (LEG[0], 0.0, LZ0), (LEG[1], LY1, LZ1)
    faces = leg_faces(frm, to)
    faces["down"] = ("hips", [0, 8, 4, 12])
    parts = [box(frm, to, "legs", faces=faces, skip=("up",))]
    return parts + tasset_3d() + knee() + fur_locks() + shin_3d()


def hips() -> list[dict]:
    frm, to = (HX0, LY1, LZ0), (HX1, HY1, LZ1)
    faces = hip_faces(frm, to)
    faces["up"] = "lining"
    faces["down"] = "lining"
    parts = [box(frm, to, "legs", faces=faces)]
    yb = HY1 - (WAIST_BELT - WAIST + 5) / 4 * (HY1 - LY1) / 4
    yt = HY1 - (WAIST_BELT - WAIST) / 4 * (HY1 - LY1) / 4
    parts.append(box((HX0 - 0.15, yb, LZ0 - 0.15), (HX1 + 0.15, yt, LZ1 + 0.15), "strap",
                     faces={"up": "strap", "down": "lining"}))
    parts.append(box((7.0, yb - 0.3, LZ0 - 0.4), (9.0, yt + 0.3, LZ0 - 0.15), "brass"))
    parts.append(box((7.45, yb, LZ0 - 0.5), (8.55, yt, LZ0 - 0.35), "strap"))
    parts.append(box((7.85, yb - 0.1, LZ0 - 0.6), (8.15, yt + 0.1, LZ0 - 0.45), "iron"))
    # a fur roll along the top of the waistband
    parts += fur_roll((HX0 - 0.35, HY1 - 0.5, LZ0 - 0.35), (HX1 + 0.35, HY1 + 0.5, LZ1 + 0.35), r=0.35, seed=6)
    # a jagged lame over the seat at the back
    seat = [box((HX0 + 0.3, LY1 - 1.6, LZ1 - 0.1), (HX1 - 0.3, yb, LZ1 + 0.3), "iron", offset=(2, 0),
                skip=("north",))]
    seat += teeth(HX0 + 0.3, HX1 - 0.3, LY1 - 1.6, LZ1 + 0.2, step=1.05)
    for x in (HX0 + 1.0, HX1 - 1.0):
        seat.append(box((x - 0.2, yb - 0.75, LZ1 + 0.3), (x + 0.2, yb - 0.35, LZ1 + 0.5), "brass"))
    turn(seat, -6, "x", (8.0, yb, LZ1))
    parts += seat
    # a crimson tabard flap hanging from the belt at the front, cut to a point, black fur
    # along its foot (the chest skirt's colours)
    flap = [box((6.2, 8.2, LZ0 - 0.45), (9.8, yb, LZ0 - 0.2), "crimson", offset=(2, 1)),
            box((6.9, 7.2, LZ0 - 0.45), (9.1, 8.3, LZ0 - 0.2), "crimson", offset=(3, 6))]
    tip = box((7.35, 6.65, LZ0 - 0.45), (8.65, 7.95, LZ0 - 0.2), "crimson", offset=(4, 8))
    turn(tip, 45, "z", (8.0, 7.3, LZ0 - 0.3))
    flap.append(tip)
    flap += fur_tufts(6.1, 9.9, 8.5, LZ0 - 0.6, LZ0 - 0.15, seed=41, size=0.55, step=0.65)
    turn(flap, 4, "x", (8.0, yb, LZ0))
    parts += flap
    # a short chain looping from the belt on the wearer's left hip, a bell at its foot
    links, pts = chain_3d((HX0 + 0.4, yb, LZ0 - 0.35), (HX0 + 2.6, yb, LZ0 - 0.35), 2.2, link=0.6, thick=0.18)
    parts += links
    x, y, z = pts[len(pts) // 2]
    parts += bell_3d(x, y - 0.05, z - 0.1, 0.6)
    return parts


def build() -> list[dict]:
    right = right_leg()
    return hips() + right + mirror(right, "x", 8.0)


def models() -> dict:
    parts = build()
    d = display(KIND, parts, gui_rotation=(18, 150, 0), gui_span=15.5)
    centre = (8.0, 9.0, 8.0)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, centre, "fist", 0.42)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.4, 0, -1)}, centre, (0.52, -0.38, -0.95), 0.5,
                                       pose=None)
    return {"main": model(parts, d)}
