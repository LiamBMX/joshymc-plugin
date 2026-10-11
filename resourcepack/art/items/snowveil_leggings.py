"""Snowveil Leggings: the leg piece of the Snowveil set, built on the Snowveil Guard (the
set's pilot module, whose palette and painters it imports).

Worn (humanoid_leggings layer, 4x): the Guard's quilted navy, with pale-blue piping down
the outer seam of each leg (silver studs along it) and framing the front of the thigh,
where a piped chevron echoes the V of the Guard's fur mantle. Over each knee sits a
frosted-silver plate holding the Snowveil sigil, and a snow-fur cuff closes each ankle
with frost crusting up out of it (both under the Skates when the full set is worn; the
thighs, the chevrons and the waistband stay in view). The waistband carries the set's
fur trim over a quilted belt with a silver buckle and a small sigil; its last rows are
the dark seam it shares with the tops of the legs.

The design stays flat against the leg (no flaps or tassels), so nothing clips as the legs
swing.

Item: the pair in 3D wearing the same paintings, with a fur roll round the waist, the
buckle and the knee plates standing proud with glowing sigil crystals, the piping as
raised cords, and fur cuffs round the ankles.
"""
from __future__ import annotations

import numpy as np

from art.items.snowveil_guard import (FROST, NAVY, PALE, S, C, band_mask, blank, clasp, frost_creep, fur, fur_roll,
                                      fur_tufts, grid, piping, quilt, save_set_materials, scallop, shift, sigil,
                                      to_image)
from art.kit import HUMANOID, box, display, mirror, model, place, region, save, save_layer, turn

ID = "snowveil_leggings"
NAME = "Snowveil Leggings"
KIND = "leggings"
COUNTERPART = "item/netherite_leggings"

# ======================================================================================
# Worn layer (humanoid_leggings, 256x128)
# ======================================================================================
# Leg faces are 16x48 texels. On the wearer's right leg: outer (left = back), front
# (left = outer), inner (left = front), back (left = inner); the left leg mirrors it.
# Shared planes (as on the Shark Leggings): the legs overlap by 1.2 units, so the inner 4
# texel columns of the front (12-15) and of the back (0-3) are painted one colour per row;
# the waistband's last unit and the legs' first unit share the front/back plane, so both
# are the same dark seam.

SEAM = 4                        # rows 0-3 of the legs: the seam under the waistband
CHEVRON = (7, 12)               # the thigh chevron's top row at the sides / at the point
KNEE_C, KNEE_R = (8, 23), 6     # the knee plate's sigil (texel centre on the front face)
CUFF = 41                       # the ankle cuff's base top edge
WAIST = 32                      # the waistband is the body's lower 4 units (rows 32-47)
WAIST_FUR = 36                  # its fur trim's base lower edge
WAIST_SEAM = 44                 # its last rows: the seam shared with the legs


def even_strip(a: np.ndarray, c0: int, c1: int, src: int) -> None:
    """Texel columns c0..c1 one colour per row (from column src): the legs' shared strip."""
    a[:, c0:c1 + 1] = a[:, src:src + 1]


def leg_cloth(seed: int, bias: float = 0.0) -> np.ndarray:
    w, h = 4 * S, 12 * S
    a = blank(w, h)
    X, Y = grid(h, w)
    quilt(a, seed=seed, bias=bias - 0.25 * (Y / h), phase=(seed % 8, 0))
    return a


def ankle_cuff(a: np.ndarray, seed: int) -> None:
    h, w = a.shape[:2]
    rows = [2 * CUFF - r for r in scallop(w, CUFF, seed, depth=1)]
    frost_creep(a, rows, seed + 3, lo=1, hi=3)
    fur(a, band_mask(h, w, top=rows), seed=seed, hang=False, lift=-1)
    a[h - 1, :, :3] *= 0.7


def chevron(a: np.ndarray, flip: bool) -> None:
    """The piped chevron across the front of the thigh, pointing down toward the knee; its
    point leans toward the outer side (flip mirrors it for faces read the other way)."""
    h, w = a.shape[:2]
    m = np.zeros((h, w), bool)
    top, point = CHEVRON
    for c in range(w):
        k = c if not flip else w - 1 - c       # 0 at the outer edge
        d = abs(k - 9) / 9                     # point a little toward the outside
        r = int(round(point - (point - top) * d))
        m[r:r + 2, c] = True
    piping(a, m, ao=0.45)


def knee_plate(a: np.ndarray) -> None:
    cx, cy = KNEE_C
    sigil(a, cx, cy, KNEE_R)
    # silver studs either side, holding the plate
    for x in (1, 13):
        clasp(a, x, cy - 1, w=2)


def paint_leg_face(kind: str, seed: int, worn: bool = True) -> np.ndarray:
    """One face of the wearer's right leg: "outer", "front", "inner" or "back". worn=False
    is the item's version (its knee plates, piping and cuffs are 3D)."""
    w, h = 4 * S, 12 * S
    a = leg_cloth(seed, bias=-0.35 if kind == "inner" else 0.0)
    if kind == "outer":
        m = np.zeros((h, w), bool)
        m[SEAM:, 7:9] = True
        if worn:
            piping(a, m)
        for y in range(SEAM + 4, CUFF - 2, 8):
            clasp(a, 6, y, w=4)
    elif kind == "front":
        chevron(a, flip=False)
        if worn:
            for c in (1, 13):
                m = np.zeros((h, w), bool)
                m[SEAM:CUFF - 2, c] = True
                piping(a, m, ao=0.3)
            knee_plate(a)
    elif kind == "back":
        for c in (2, 13):
            m = np.zeros((h, w), bool)
            m[SEAM:CUFF - 2, c] = True
            piping(a, m, ao=0.3)
    if worn:
        ankle_cuff(a, seed + 7)
        if kind in ("front", "back"):
            a[0:SEAM] = C(NAVY[1])
    a[h - 1, :, :3] *= 0.8
    if kind == "front":
        even_strip(a, 12, 15, 12)
    elif kind == "back":
        even_strip(a, 0, 3, 3)
    return a


def paint_leg_top(seed: int = 71) -> np.ndarray:
    a = blank(4 * S, 4 * S)
    quilt(a, seed=seed, bias=-0.3)
    return a


def paint_sole() -> np.ndarray:
    a = blank(4 * S, 4 * S)
    a[:] = C(NAVY[1])
    return a


def paint_waist(kind: str, seed: int) -> np.ndarray:
    """The waistband (the body's lower 4 units): kind "front", "back" or "side" (the
    wearer's right side; the left is its mirror). The set's fur trim over a quilted belt
    with a silver buckle and sigil at the front; the last rows are the shared dark seam."""
    w, h = (8 if kind in ("front", "back") else 4) * S, 12 * S
    a = blank(w, h)
    band = np.zeros((h, w), bool)
    band[WAIST:] = True
    quilt(a, band, seed=seed, bias=0.25)
    pipe = np.zeros((h, w), bool)
    pipe[WAIST_SEAM - 2:WAIST_SEAM] = True
    piping(a, pipe, ao=0)
    if kind == "front":
        cx = w // 2
        buckle = np.zeros((h, w), bool)
        buckle[WAIST + 2:WAIST_SEAM, cx - 6:cx + 6] = True
        a[buckle] = C(FROST[3])
        a[WAIST + 2, cx - 6:cx + 6] = C(FROST[5])
        a[WAIST_SEAM - 1, cx - 6:cx + 6] = C(FROST[1])
        a[WAIST + 2:WAIST_SEAM, cx - 6] = C(FROST[4])
        a[WAIST + 2:WAIST_SEAM, cx + 5] = C(FROST[2])
        sigil(a, cx, (WAIST + 2 + WAIST_SEAM) // 2, 3)
    rows = scallop(w, WAIST_FUR, seed + 1, depth=1, symmetric=w == 32)
    fur(a, band_mask(h, w, top=[WAIST] * w, bottom=rows), seed=seed + 2)
    a[WAIST_SEAM:] = C(NAVY[1])
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
# Guard). The worn paintings are projected onto the faces.
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
        face = paint_leg_face(kind, seed, worn=False)
        if kind == "inner":
            face[..., :3] *= 0.65
        a[0:48, 16 * i:16 * i + 16] = face
    a[48:64, 0:32] = paint_waist("front", 141)[32:48]
    a[48:64, 32:64] = paint_waist("back", 151)[32:48]
    return a


def textures() -> None:
    save_layer(to_image(paint_humanoid()), "humanoid_leggings")
    save(to_image(leg_atlas()), "legs")
    sides = blank(64, 64)
    side = paint_waist("side", 161)[32:48]
    sides[0:16, 0:16] = side
    sides[0:16, 16:32] = side[:, ::-1]
    sides[32:48, 0:16] = paint_sole()
    save(to_image(sides), "hips")
    save_set_materials(skip=("navy",))


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


def knee() -> list[dict]:
    """The knee plate on the right leg's front: a silver diamond setting with a stepped
    bezel, an ice field and a glowing crystal; studs either side."""
    cx = LEG[1] - (KNEE_C[0] + 0.5) / 16 * LW
    cy = _y_leg(KNEE_C[1] + 0.5)
    z = LZ0
    parts = [box((cx - 1.75, cy - 1.75, z - 0.3), (cx + 1.75, cy + 1.75, z), "silver"),
             box((cx - 1.3, cy - 1.3, z - 0.5), (cx + 1.3, cy + 1.3, z - 0.3), "silver"),
             box((cx - 0.95, cy - 0.95, z - 0.55), (cx + 0.95, cy + 0.95, z - 0.5), "ice", glow=8, shade=False)]
    turn(parts, 45, "z", (cx, cy, z))
    gem = box((cx - 0.6, cy - 0.6, z - 1.1), (cx + 0.6, cy + 0.6, z - 0.45), "crystal", glow=13, uv="full")
    parts.append(turn(gem, 45, "z", (cx, cy, z)))
    for sx in (LEG[0] + 0.25, LEG[1] - 0.75):
        parts.append(box((sx, cy - 0.25, z - 0.25), (sx + 0.5, cy + 0.25, z), "silver"))
    return parts


def cords() -> list[dict]:
    """The piping as raised cords: down the outer seam, framing the front, the chevron."""
    parts = [box((LEG[1], _y_leg(41), 7.85), (LEG[1] + 0.22, _y_leg(SEAM), 8.15), "pale")]
    for c in (1, 13):
        x = LEG[1] - (c + 0.5) / 16 * LW
        parts.append(box((x - 0.12, _y_leg(39), LZ0 - 0.2), (x + 0.12, _y_leg(SEAM), LZ0), "pale"))
    # the chevron: two slanted cords meeting near the outer side
    top, point = CHEVRON
    xo, xi = LEG[1] - 0.1, LEG[0] + 0.1
    xp = LEG[1] - 9.5 / 16 * LW
    yo, yp, yi = _y_leg(top + 1), _y_leg(point + 1), _y_leg(top + 1 + (point - top) * 0.2)
    for p0, p1 in (((xo, yo, LZ0 - 0.12), (xp, yp, LZ0 - 0.12)), ((xp, yp, LZ0 - 0.12), (xi, yi, LZ0 - 0.12))):
        from art.kit import bar
        parts.append(bar(p0, p1, 0.45, 0.24, "pale"))
    return parts


def right_leg() -> list[dict]:
    frm, to = (LEG[0], 1.4, LZ0), (LEG[1], LY1, LZ1)
    faces = leg_faces(frm, to)
    faces["down"] = ("hips", [0, 8, 4, 12])
    parts = [box(frm, to, "legs", faces=faces, skip=("up",))]
    parts += knee() + cords()
    # silver studs down the outer seam, either side of the cord
    for row in range(SEAM + 4, CUFF - 2, 8):
        y1, y0 = _y_leg(row), _y_leg(row + 2)
        for za, zb in ((7.2, 7.75), (8.25, 8.8)):
            parts.append(box((LEG[1], y0, za), (LEG[1] + 0.18, y1, zb), "silver"))
    # the fur cuff round the ankle
    parts += fur_roll((LEG[0] - 0.45, -0.1, LZ0 - 0.45), (LEG[1] + 0.45, 2.5, LZ1 + 0.45), r=0.35, seed=4)
    parts += fur_tufts(LEG[0] - 0.4, LEG[1] + 0.4, 2.5, LZ0 - 0.4, LZ0 + 0.2, seed=6, size=0.5, down=False,
                       step=0.9)
    return parts


def hips() -> list[dict]:
    frm, to = (HX0, LY1, LZ0), (HX1, HY1, LZ1)
    faces = hip_faces(frm, to)
    faces["up"] = "lining"
    faces["down"] = "lining"
    parts = [box(frm, to, "legs", faces=faces)]
    # the fur roll round the waist and the buckle
    parts += fur_roll((HX0 - 0.45, HY1 - 1.8, LZ0 - 0.45), (HX1 + 0.45, HY1 + 0.6, LZ1 + 0.45), r=0.4, seed=8)
    parts.append(box((HX0 + 0.5, HY1 + 0.35, LZ0 + 0.5), (HX1 - 0.5, HY1 + 0.4, LZ1 - 0.5), "lining",
                     skip=("north", "south", "east", "west", "down")))
    # tufts hanging from the roll front and back (clear of the buckle) and breaking its top
    for z0, z1, seed in ((LZ0 - 0.3, LZ0 + 0.2, 31), (LZ1 - 0.2, LZ1 + 0.3, 32)):
        for xa, xb in ((HX0 - 0.3, 6.2), (9.8, HX1 + 0.3)):
            parts += fur_tufts(xa, xb, HY1 - 1.8, z0 - 0.1, z1 + 0.1, seed=seed + int(xa), size=0.5, step=0.9)
    parts += fur_tufts(HX0 + 0.3, HX1 - 0.3, HY1 + 0.6, LZ0 - 0.3, LZ0 + 0.5, seed=33, size=0.3, down=False,
                       step=1.3)
    cx, cy, z = 8.0, LY1 + 0.6, LZ0
    parts.append(box((cx - 1.6, cy - 0.95, z - 0.3), (cx + 1.6, cy + 0.95, z), "silver"))
    gem = [box((cx - 0.55, cy - 0.55, z - 0.5), (cx + 0.55, cy + 0.55, z - 0.3), "silver"),
           box((cx - 0.35, cy - 0.35, z - 0.75), (cx + 0.35, cy + 0.35, z - 0.45), "crystal", glow=13, uv="full")]
    parts += turn(gem, 45, "z", (cx, cy, z))
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
