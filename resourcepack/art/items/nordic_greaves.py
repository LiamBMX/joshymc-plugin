"""Nordic Greaves: the leg piece of the Nordic set, built on the Nordic Plate (the set's
pilot module, whose palette, painters and boss it imports).

Worn (humanoid_leggings layer, 4x): woad-slate wool trousers. A leather strap with a
bronze stud crosses each thigh, and a riveted iron knee cop with a bronze rim carries the
Nordic boss and its amber stone. Below the knee, an iron greave with a knotwork collar and
a raised centre ridge guards the shin, strapped on over undyed wool leg wraps
(winingas) that criss-cross down to the ankle, where the woven band closes the leg. The
waistband is the set's leather belt with its bronze buckle over the woven band; its last
rows are the dark seam it shares with the tops of the legs. With the full set on, the
Treads cover the leg from just under the knee cop (which sits high for that reason), so
the thigh straps and the whole knee cop with its boss stay in view.

Everything is painted flat against the leg (no flaps or tassels), so nothing clips as
the legs swing.

Item: the pair in 3D wearing the same paintings, with the knee cops, the greaves, the
straps, the belt and its buckle standing proud, and the bosses' amber glowing.
"""
from __future__ import annotations

import numpy as np

from art.items.nordic_plate import (BRONZE, FUR, IRON, STRAP, WOOL, S, belt, boss, boss_3d, iron, knot_band,
                                    leather, mask_rect, rivet, save_set_materials, weave, wool)
from art.items.snowveil_guard import C, blank, grid, shadow, shift, to_image
from art.kit import HUMANOID, box, display, mirror, model, place, region, save, save_layer

ID = "nordic_greaves"
NAME = "Nordic Greaves"
KIND = "leggings"
COUNTERPART = "item/netherite_leggings"

# ======================================================================================
# Worn layer (humanoid_leggings, 256x128)
# ======================================================================================
# Leg faces are 16x48 texels. On the wearer's right leg: outer (left = back), front
# (left = outer), inner (left = front), back (left = inner); the left leg mirrors it.
# Shared planes (as on the Snowveil Leggings): the legs overlap, so the inner 4 texel
# columns of the front (12-15) and of the back (0-3) are painted one colour per row; the
# waistband's last unit and the legs' first unit share the front/back plane, so both are
# the same dark seam.

SEAM = 4                        # rows 0-3 of the legs: the seam under the waistband
THIGH_STRAP = 4                 # the thigh strap's top row (3 rows)
KNEE = (8, 18)                  # the knee cop's rows: all above the Treads' fur cuff (row 20)
KNEE_C, KNEE_R = (6, 13), 5     # its boss (worn: centred on the visible front, columns 0-11)
GREAVE = (20, 43)               # the greave's rows (front face)
GREAVE_COLS = (1, 11)           # ... and columns (worn)
ITEM_CX, ITEM_COLS = 8, (2, 13)  # the same, centred on the item's whole leg front
STRAPS = (26, 36)               # the greave's straps (top rows, 2 rows each)
ANKLE = 44                      # the woven ankle band's top row (4 rows)
WAIST = 32                      # the waistband is the body's lower 4 units (rows 32-47)
WAIST_BELT = 34                 # its belt's top row (5 rows)
WAIST_WEAVE = 39                # its woven band's top row (4 rows)
WAIST_SEAM = 44                 # its last rows: the seam shared with the legs


def even_strip(a: np.ndarray, c0: int, c1: int, src: int) -> None:
    """Texel columns c0..c1 one colour per row (from column src): the legs' shared strip."""
    a[:, c0:c1 + 1] = a[:, src:src + 1]


def leg_wraps(a: np.ndarray, y0: int, y1: int, flip: bool = False) -> None:
    """Undyed wool leg wraps between rows y0 and y1: bands 3 texels wide wound on a slant,
    each lit along its upper edge, with a shadowed gap between the turns."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    k = (Y + (w - 1 - X if flip else X) // 2) % 4
    m = (Y >= y0) & (Y <= y1)
    pal = np.stack([C(c) for c in (FUR[2], FUR[5], FUR[4], FUR[4])])
    a[m] = pal[k][m]
    a[m & (k == 1) & ((X + Y) % 5 == 0)] = C(FUR[6])
    a[y0, :] = C(FUR[6])
    a[y1, :] = C(FUR[2])


def knee_cop(a: np.ndarray, seed: int, cx: int = KNEE_C[0]) -> None:
    """The riveted iron knee cop with its bronze rim and the boss, centred on column cx."""
    h, w = a.shape[:2]
    y0, y1 = KNEE
    xa, xb = cx - 6, cx + 5
    m = mask_rect(h, w, xa, y0, xb, y1)
    m[y0, max(0, xa)] = m[y0, min(w - 1, xb)] = m[y1, max(0, xa)] = m[y1, min(w - 1, xb)] = False
    iron(a, m, seed=seed, bias=0.6, ao=0.6)
    rim = m & ~(shift(m, 1, 0) & shift(m, -1, 0) & shift(m, 0, 1) & shift(m, 0, -1))
    a[rim] = C(BRONZE[3])
    a[rim & ~shift(m, 0, 1)] = C(BRONZE[5])
    a[rim & ~shift(m, 0, -1)] = C(BRONZE[1])
    for x, y in ((xa + 1, y0 + 2), (xb - 2, y0 + 2), (xa + 1, y1 - 3), (xb - 2, y1 - 3)):
        rivet(a, x, y)
    boss(a, cx, KNEE_C[1] + 0.5, KNEE_R)


def greave(a: np.ndarray, seed: int, cols=GREAVE_COLS) -> None:
    """The shin greave: smooth iron with a raised centre ridge, a knotwork collar and a
    few rivets down its edges."""
    h, w = a.shape[:2]
    y0, y1 = GREAVE
    c0, c1 = cols
    m = mask_rect(h, w, c0, y0, c1, y1)
    m[y1, c0] = m[y1, c1] = False
    X, _ = grid(h, w)
    iron(a, m, seed=seed, bias=0.6, ao=0.6)
    inner = m & shift(m, 1, 0) & shift(m, -1, 0) & shift(m, 0, 1) & shift(m, 0, -1)
    a[inner] = C(IRON[5])
    a[inner & (X <= (c0 + c1) // 2 - 2)] = C(IRON[6])
    mid = (c0 + c1) // 2
    a[m & (X == mid)] = C(IRON[7])
    a[m & (X == mid + 1)] = C(IRON[4])
    right = m & (X > mid + 1)
    a[right, :3] *= 0.86
    # a bronze border down the sides and along the foot, under the knotwork collar
    edge = m & ~(shift(m, 1, 0) & shift(m, -1, 0) & shift(m, 0, -1))
    a[edge] = C(BRONZE[3])
    a[edge & (X <= mid)] = C(BRONZE[4])
    a[m & ~shift(m, 0, -1)] = C(BRONZE[1])
    knot_band(a, c0, c1, y0, 5, ao=0)
    for y in (y0 + 8, y1 - 4):
        rivet(a, c0 + 1, y)
        rivet(a, c1 - 2, y)


def strap_row(a: np.ndarray, y: int, buckle_at=None) -> None:
    h, w = a.shape[:2]
    leather(a, mask_rect(h, w, 0, y, w - 1, y + 1), stitch=False, ao=0.5)
    if buckle_at is not None:
        x = buckle_at
        bm = mask_rect(h, w, x, y - 1, x + 3, y + 2)
        a[bm] = C(BRONZE[3])
        a[y - 1, x:x + 4] = C(BRONZE[5])
        a[y + 2, x:x + 4] = C(BRONZE[1])
        a[y:y + 2, x + 1:x + 3] = C(STRAP[1])


def paint_leg_face(kind: str, seed: int, worn: bool = True, lift: float = 0.0) -> np.ndarray:
    """One face of the wearer's right leg: "outer", "front", "inner" or "back". worn=False
    is the item's version (its knee cop, greave and straps are 3D); lift lightens the
    wool (the item's, so the inventory icon doesn't turn to mud)."""
    w, h = 4 * S, 12 * S
    a = blank(w, h)
    X, Y = grid(h, w)
    wool(a, seed=seed, bias=lift + (-0.35 if kind == "inner" else 0.0) - 0.15 * (Y / h))
    leg_wraps(a, KNEE[1] - 1, ANKLE - 1, flip=kind in ("inner", "back"))
    shadow(a, mask_rect(h, w, 0, 0, w - 1, KNEE[1] - 2), 0.4, reach=1, down_only=True)
    weave(a, 0, w - 1, ANKLE, 4)
    # the thigh strap with a bronze stud on the outside
    leather(a, mask_rect(h, w, 0, THIGH_STRAP, w - 1, THIGH_STRAP + 2), stitch=False, ao=0.5)
    if kind == "outer":
        rivet(a, 7, THIGH_STRAP, BRONZE)
    if worn:
        for y in STRAPS:
            strap_row(a, y, buckle_at=6 if kind == "outer" else None)
        if kind == "front":
            greave(a, seed + 4)
        if kind in ("front", "outer", "inner"):
            knee_cop(a, seed + 3) if kind == "front" else None
        if kind in ("outer", "inner"):
            # the knee cop wraps round the side of the knee
            y0, y1 = KNEE
            side = mask_rect(h, w, 0, y0 + 1, 5, y1 - 1) if kind == "inner" else mask_rect(h, w, 10, y0 + 1,
                                                                                            15, y1 - 1)
            iron(a, side, seed=seed + 9, bias=0.35, ao=0.5)
            a[y1 - 1, :][side[y1 - 1]] = C(BRONZE[1])
            a[y0 + 1, :][side[y0 + 1]] = C(BRONZE[5])
        if kind in ("front", "back"):
            a[0:SEAM] = C(WOOL[1])
    a[h - 1, :, :3] *= 0.8
    if kind == "front":
        even_strip(a, 12, 15, 12)
    elif kind == "back":
        even_strip(a, 0, 3, 3)
    return a


def paint_leg_top(seed: int = 71) -> np.ndarray:
    a = blank(4 * S, 4 * S)
    wool(a, seed=seed, bias=-0.3)
    return a


def paint_sole() -> np.ndarray:
    a = blank(4 * S, 4 * S)
    a[:] = C(WOOL[1])
    return a


def paint_waist(kind: str, seed: int) -> np.ndarray:
    """The waistband (the body's lower 4 units): kind "front", "back" or "side" (the
    wearer's right side; the left is its mirror). Wool, the leather belt (a bronze buckle
    at the front), the woven band; the last rows are the shared dark seam."""
    w, h = (8 if kind in ("front", "back") else 4) * S, 12 * S
    a = blank(w, h)
    band = mask_rect(h, w, 0, WAIST, w - 1, h - 1)
    wool(a, band, seed=seed, bias=0.1)
    weave(a, 0, w - 1, WAIST_WEAVE, 4)
    belt(a, WAIST_BELT, 4, buckle_x=w // 2 if kind == "front" else None, seed=seed)
    if kind == "back":
        for x in (6, 25):
            rivet(a, x, WAIST_BELT + 1, BRONZE)
    a[WAIST_SEAM:] = C(WOOL[1])
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
# Plate). The worn paintings are projected onto the faces.
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
        face = paint_leg_face(kind, seed, worn=False, lift=0.8)
        if kind == "inner":
            face[..., :3] *= 0.65
        a[0:48, 16 * i:16 * i + 16] = face
    a[48:64, 0:32] = paint_waist("front", 141)[32:48]
    a[48:64, 32:64] = paint_waist("back", 151)[32:48]
    return a


def item_plates() -> None:
    """The knee cop and greave paintings for their 3D plates (64x64, 4 texels per uv unit):
    the cop from row 0, the greave from row 16."""
    a = blank(64, 64)
    face = blank(16, 48)
    wool(face, seed=1, bias=0.5)
    knee_cop(face, 114, cx=ITEM_CX)
    greave(face, 115, cols=ITEM_COLS)
    a[0:KNEE[1] - KNEE[0] + 1, 0:16] = face[KNEE[0]:KNEE[1] + 1]
    a[16:16 + GREAVE[1] - GREAVE[0] + 1, 0:16] = face[GREAVE[0]:GREAVE[1] + 1]
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
    save_set_materials(skip=("mail", "fur", "weave"))


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
    """The knee cop standing proud of the right knee, wrapping round its sides, with the
    boss and its amber stone."""
    y1, y0 = _y_leg(KNEE[0]), _y_leg(KNEE[1] + 1)
    z = LZ0 - 0.35
    parts = [box((LEG[0] + 0.1, y0, z), (LEG[1] - 0.1, y1, LZ0 + 1.6), "iron",
                 faces={"north": ("plates", [0, 0, 4, (KNEE[1] - KNEE[0] + 1) / 4])}, skip=("south",)),
             box((LEG[0] - 0.2, y0 + 0.4, z + 0.5), (LEG[1] + 0.2, y1 - 0.4, LZ0 + 2.4), "iron", skip=("south",)),
             box((LEG[0], y1 - 0.25, z - 0.12), (LEG[1], y1 + 0.05, LZ0 + 1.6), "bronze"),
             box((LEG[0], y0 - 0.05, z - 0.12), (LEG[1], y0 + 0.25, LZ0 + 1.6), "bronze")]
    cx = LEG[1] - ITEM_CX / 16 * LW
    cy = _y_leg(KNEE_C[1] + 0.5)
    parts += boss_3d(cx, cy, z, 1.25)
    return parts


def shin() -> list[dict]:
    """The greave on the right shin: a two-faced iron plate (a ridge down its middle), a
    knotwork collar, and two straps round the leg."""
    y1, y0 = _y_leg(GREAVE[0]), _y_leg(GREAVE[1] + 1)
    c0, c1 = ITEM_COLS
    xa, xb = LEG[1] - (c1 + 1) / 16 * LW, LEG[1] - c0 / 16 * LW
    z = LZ0 - 0.3
    u0, u1 = c0 / 4, (c1 + 1) / 4
    parts = [box((xa, y0, z), (xb, y1, LZ0), "iron",
                 faces={"north": ("plates", [u0, 4, u1, 4 + (GREAVE[1] - GREAVE[0] + 1) / 4])}, skip=("south",))]
    mid = (xa + xb) / 2
    parts.append(box((mid - 0.2, y0 + 0.3, z - 0.18), (mid + 0.2, y1 - 1.3, z), "iron", offset=(0, 0)))
    for row in STRAPS:
        ya, yb = _y_leg(row + 2), _y_leg(row)
        parts.append(box((LEG[0] - 0.12, ya, LZ0 - 0.12), (LEG[1] + 0.12, yb, LZ1 + 0.12), "leather",
                         faces={"north": ("leather", [0, 0, 4, 0.7])}, skip=("up", "down")))
        parts.append(box((LEG[1] + 0.1, ya - 0.2, 7.2), (LEG[1] + 0.3, yb + 0.2, 8.2), "bronze"))
    # the thigh strap
    ya, yb = _y_leg(THIGH_STRAP + 3), _y_leg(THIGH_STRAP)
    parts.append(box((LEG[0] - 0.1, ya, LZ0 - 0.1), (LEG[1] + 0.1, yb, LZ1 + 0.1), "leather",
                     faces={"north": ("leather", [0, 0, 4, 0.75])}, skip=("up", "down")))
    parts.append(box((LEG[1] + 0.08, ya + 0.1, 7.75), (LEG[1] + 0.3, yb - 0.1, 8.25), "bronze"))
    return parts


def right_leg() -> list[dict]:
    frm, to = (LEG[0], 0.0, LZ0), (LEG[1], LY1, LZ1)
    faces = leg_faces(frm, to)
    faces["down"] = ("hips", [0, 8, 4, 12])
    parts = [box(frm, to, "legs", faces=faces, skip=("up",))]
    return parts + knee() + shin()


def hips() -> list[dict]:
    frm, to = (HX0, LY1, LZ0), (HX1, HY1, LZ1)
    faces = hip_faces(frm, to)
    faces["up"] = "lining"
    faces["down"] = "lining"
    parts = [box(frm, to, "legs", faces=faces)]
    # the belt standing proud, with its buckle
    yb, yt = HY1 - (WAIST_BELT - WAIST + 4) / 4 * (HY1 - LY1) / 4, HY1 - (WAIST_BELT - WAIST) / 4 * (HY1 - LY1) / 4
    parts.append(box((HX0 - 0.15, yb, LZ0 - 0.15), (HX1 + 0.15, yt, LZ1 + 0.15), "leather",
                     faces={"up": "leather", "down": "lining"}))
    parts.append(box((7.0, yb - 0.3, LZ0 - 0.4), (9.0, yt + 0.3, LZ0 - 0.15), "bronze"))
    parts.append(box((7.5, yb, LZ0 - 0.5), (8.5, yt, LZ0 - 0.35), "leather"))
    parts.append(box((7.85, yb - 0.1, LZ0 - 0.6), (8.15, yt + 0.1, LZ0 - 0.45), "bronze"))
    # a lip of wool round the top
    parts.append(box((HX0 - 0.05, HY1 - 0.3, LZ0 - 0.05), (HX1 + 0.05, HY1 + 0.15, LZ1 + 0.05), "lining",
                     faces={"north": ("legs", [0, 12, 8, 12.4]), "south": ("legs", [8, 12, 16, 12.4])}))
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
