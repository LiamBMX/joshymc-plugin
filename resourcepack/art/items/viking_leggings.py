"""Viking Leggings: the leg piece of the Viking set (issue #1294), built on the Viking
Chestplate (the set's pilot module, whose palette, painters and boss it imports).

Worn (humanoid_leggings layer, 4x): dark oxblood wool trousers. A lamellar steel tasset
(the chestplate's laced lames) hangs over the front and outside of each thigh from a
leather strap, and a round, riveted steel knee cop carries the set's boss with its
polished silver crown. From the knee down, brown leather leg wraps (winingas) criss-cross
over the wool to the ankle, held by a strap with a bronze rivet. The waistband is the
set's dark belt with its steel buckle; its last rows are the dark seam it shares with the
tops of the legs. With the full set on, the Boots cover the leg from just under the knee
cop (which sits high for that reason), so the tassets and the whole knee cop stay in view.

Everything is painted flat against the leg (no flaps), so nothing clips as the legs
swing.

Item: the pair in 3D wearing the same paintings, with the tassets (two stacked lames),
the knee cops and their bosses, the wrap straps, the belt and its buckle standing proud.
"""
from __future__ import annotations

import numpy as np

from art.items.snowveil_guard import C, _hash, blank, grid, pick, shadow, shift, to_image
from art.items.viking_chestplate import (BRONZE, LEATHER, STEEL, STRAP, WOOL, S, belt, boss, boss_3d, lamellar,
                                         leather, mask_rect, rivet, save_set_materials, steel, wool)
from art.kit import HUMANOID, box, display, mirror, model, place, region, save, save_layer, turn

ID = "viking_leggings"
NAME = "Viking Leggings"
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
TASSET = (4, 14)                # the lamellar tasset's rows (its strap is the first 2)
KNEE = (13, 25)                 # the knee cop's rows: all above the Boots' fur cuff (row 27)
KNEE_C, KNEE_R = (6, 19), 4     # its boss (worn: centred on the visible front, columns 0-11)
WRAPS = (25, 43)                # the leg wraps
WRAP_STRAP = 33                 # the strap holding the wraps (2 rows)
ANKLE = 44                      # the ankle band's top row (4 rows)
WAIST = 32                      # the waistband is the body's lower 4 units (rows 32-47)
WAIST_BELT = 34                 # its belt's top row (5 rows)
WAIST_SEAM = 44                 # its last rows: the seam shared with the legs


def even_strip(a: np.ndarray, c0: int, c1: int, src: int) -> None:
    """Texel columns c0..c1 one colour per row (from column src): the legs' shared strip."""
    a[:, c0:c1 + 1] = a[:, src:src + 1]


LINEN = ["#2f2924", "#463d34", "#5d5246", "#736758", "#897c6a", "#9f927e", "#b5a993"]


def leg_wraps(a: np.ndarray, y0: int, y1: int, flip: bool = False) -> None:
    """Winingas between rows y0 and y1: a long band of undyed grey-brown linen wound in a
    spiral down the shin, every turn sloping the same way, lit along its upper edge and
    separated from the next turn by a thin shadow line, with a little weave texture."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    xx = (w - 1 - X) if flip else X
    m = (Y >= y0) & (Y <= y1)
    d = (Y + xx // 3) % 5
    idx = np.choose(d, [5, 4, 4, 3, 1])
    idx = idx - ((d == 2) & ((X + Y) % 4 == 0)) + ((d == 1) & (_hash(X, Y, 7) > 0.85))
    a[m] = pick(LINEN, idx)[m]
    a[y0, :] = C(LINEN[1])
    a[y1, :] = C(LINEN[0])


def tasset(a: np.ndarray, seed: int, c0: int, c1: int, rows=TASSET) -> None:
    """The lamellar tasset over the thigh (columns c0..c1, rows `rows`): a leather strap
    on top, courses of laced steel lames, a dark lacing line under them."""
    h, w = a.shape[:2]
    y0, y1 = rows
    m = mask_rect(h, w, c0, y0, c1, y1)
    lamellar(a, m, seed=seed, bias=0.2, ao=0.55)
    a[y1, c0:c1 + 1] = C(STEEL[1])
    leather(a, mask_rect(h, w, c0, y0, c1, y0 + 1), seed=seed + 1, stitch=False, ao=0)


def knee_cop(a: np.ndarray, seed: int, cx: int = KNEE_C[0], half: float = 5.6) -> None:
    """The round steel knee cop with its boss, centred on column cx, `half` texels wide
    either side."""
    h, w = a.shape[:2]
    y0, y1 = KNEE
    X, Y = grid(h, w)
    cy = (y0 + y1 + 1) / 2
    d = np.sqrt(((X + 0.5 - cx) / half) ** 2 + ((Y + 0.5 - cy) / ((y1 - y0 + 1) / 2)) ** 2)
    m = d <= 1.0
    steel(a, m, seed=seed, bias=0.9, ao=0.35)
    rim = m & (d > 0.82)
    lit = (X + 0.5 - cx) + (Y + 0.5 - cy) < 0
    a[rim & lit] = C(STEEL[7])
    a[rim & ~lit] = C(STEEL[2])
    boss(a, cx, KNEE_C[1] + 0.5, KNEE_R)


def strap_row(a: np.ndarray, y: int, rivet_at=None) -> None:
    h, w = a.shape[:2]
    leather(a, mask_rect(h, w, 0, y, w - 1, y + 1), stitch=False, ao=0.5)
    if rivet_at is not None:
        rivet(a, rivet_at, y)


def paint_leg_face(kind: str, seed: int, worn: bool = True, lift: float = 0.0) -> np.ndarray:
    """One face of the wearer's right leg: "outer", "front", "inner" or "back". worn=False
    is the item's version (its tasset and knee cop are 3D); lift lightens the wool (the
    item's, so the inventory icon doesn't turn to mud)."""
    w, h = 4 * S, 12 * S
    a = blank(w, h)
    X, Y = grid(h, w)
    # the wool darkens a little toward the inner thigh so the plane has form
    toward_inner = {"front": X / (w - 1), "back": 1 - X / (w - 1)}.get(kind, 0.0)
    wool(a, seed=seed, bias=lift + (-0.35 if kind == "inner" else 0.0) - 0.12 * (Y / h) - 0.3 * toward_inner)
    leg_wraps(a, WRAPS[0], WRAPS[1], flip=kind in ("inner", "back"))
    shadow(a, mask_rect(h, w, 0, 0, w - 1, WRAPS[0] - 1), 0.4, reach=1, down_only=True)
    strap_row(a, WRAP_STRAP, rivet_at=7 if kind == "outer" else None)
    # the ankle band: dark leather
    leather(a, mask_rect(h, w, 0, ANKLE, w - 1, h - 1), stitch=True, ao=0.3)
    if worn:
        if kind == "front":
            tasset(a, seed + 4, 0, 11)
            knee_cop(a, seed + 3)
        elif kind == "outer":
            tasset(a, seed + 5, 4, 15)
            # the knee cop wraps round the side of the knee
            y0, y1 = KNEE
            side = mask_rect(h, w, 10, y0 + 2, 15, y1 - 2)
            steel(a, side, seed=seed + 9, bias=0.3, ao=0.5)
            rivet(a, 12, (y0 + y1) // 2)
        elif kind == "back":
            # a short lamellar course over the back of the thigh, hung from the strap
            tasset(a, seed + 6, 4, 15, rows=(TASSET[0], TASSET[0] + 7))
        elif kind == "inner":
            y0, y1 = KNEE
            side = mask_rect(h, w, 0, y0 + 2, 5, y1 - 2)
            steel(a, side, seed=seed + 9, bias=0.1, ao=0.5)
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
    wearer's right side; the left is its mirror). Wool, the dark belt (a steel buckle at
    the front, steel studs at the back); the last rows are the shared dark seam."""
    w, h = (8 if kind in ("front", "back") else 4) * S, 12 * S
    a = blank(w, h)
    band = mask_rect(h, w, 0, WAIST, w - 1, h - 1)
    wool(a, band, seed=seed, bias=0.1)
    belt(a, WAIST_BELT, 5, buckle_x=w // 2 if kind == "front" else None, seed=seed)
    if kind == "back":
        for x in (6, 25):
            rivet(a, x, WAIST_BELT + 1, STEEL[3:])
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
        face = paint_leg_face(kind, seed, worn=False, lift=0.6)
        if kind == "inner":
            face[..., :3] *= 0.65
        a[0:48, 16 * i:16 * i + 16] = face
    a[48:64, 0:32] = paint_waist("front", 141)[32:48]
    a[48:64, 32:64] = paint_waist("back", 151)[32:48]
    return a


def item_plates() -> None:
    """The knee cop painting for its 3D plate (64x64, 4 texels per uv unit), from row 0."""
    a = blank(64, 64)
    face = blank(16, 48)
    wool(face, seed=1, bias=0.5)
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
    save_set_materials(skip=("fur", "wool", "leather"))


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


def tasset_3d() -> list[dict]:
    """The tasset over the right thigh: a leather strap and two stacked courses of laced
    lames wrapping the front and the outside, the lower course a little proud and
    splayed so the plates read layered."""
    parts = []
    y_top, y_bot = _y_leg(TASSET[0]), _y_leg(TASSET[1] + 1)
    y_mid = (y_top + y_bot) / 2 - 0.1
    for k, (ya, yb, out) in enumerate(((y_mid, y_top - 0.45, 0.25), (y_bot - 0.3, y_mid + 0.25, 0.5))):
        course = [box((LEG[0] + 0.05, ya, LZ0 - out), (LEG[1] + out, yb, LZ0 + 0.1), "lamellar",
                      offset=(k * 2, k * 1.75), skip=("south",)),
                  box((LEG[1] - 0.1, ya, LZ0 - out), (LEG[1] + out, yb, LZ1 - 0.6), "lamellar",
                      offset=(k * 3, k * 1.75), skip=("west",))]
        turn(course, 6 * k + 3, "x", (8.0, yb, LZ0))
        parts += course
    back = box((LEG[0] + 0.05, y_mid + 0.1, LZ1 - 0.1), (LEG[1] + 0.25, y_top - 0.45, LZ1 + 0.3), "lamellar",
               offset=(1, 0), skip=("north",))
    turn(back, -4, "x", (8.0, y_top, LZ1))
    parts.append(back)
    parts.append(box((LEG[0] - 0.1, y_top - 0.55, LZ0 - 0.2), (LEG[1] + 0.2, y_top, LZ1 + 0.35), "strap",
                     skip=("up", "down")))
    parts.append(box((LEG[1] + 0.15, y_top - 0.5, 7.0), (LEG[1] + 0.35, y_top - 0.05, 7.6), "bronze"))
    return parts


def knee() -> list[dict]:
    """The knee cop standing proud of the right knee (a rounded plate in three steps),
    wrapping round its sides, with the boss."""
    y1, y0 = _y_leg(KNEE[0]), _y_leg(KNEE[1] + 1)
    z = LZ0 - 0.35
    u = (KNEE[1] - KNEE[0] + 1) / 4
    parts = [box((LEG[0] + 0.2, y0 + 0.35, z), (LEG[1] - 0.2, y1 - 0.35, LZ0 + 1.6), "steel",
                 faces={"north": ("plates", [0.25, 0.35, 3.75, u - 0.35])}, skip=("south",)),
             box((LEG[0] + 0.6, y0, z + 0.1), (LEG[1] - 0.6, y1, LZ0 + 1.6), "steel", skip=("south",)),
             box((LEG[0] - 0.15, y0 + 0.9, z + 0.4), (LEG[1] + 0.15, y1 - 0.9, LZ0 + 2.6), "steel",
                 skip=("south",))]
    cx = LEG[1] - 8 / 16 * LW
    cy = _y_leg(KNEE_C[1] + 0.5)
    parts += boss_3d(cx, cy, z, 1.15)
    return parts


def wraps_3d() -> list[dict]:
    """The strap round the wraps and the ankle band, a little proud of the leg."""
    parts = []
    ya, yb = _y_leg(WRAP_STRAP + 2), _y_leg(WRAP_STRAP)
    parts.append(box((LEG[0] - 0.12, ya, LZ0 - 0.12), (LEG[1] + 0.12, yb, LZ1 + 0.12), "strap",
                     skip=("up", "down")))
    parts.append(box((LEG[1] + 0.1, ya - 0.1, 7.75), (LEG[1] + 0.3, yb + 0.1, 8.25), "bronze"))
    ya, yb = 0.0, _y_leg(ANKLE)
    parts.append(box((LEG[0] - 0.1, ya, LZ0 - 0.1), (LEG[1] + 0.1, yb, LZ1 + 0.1), "strap", skip=("up",)))
    return parts


def right_leg() -> list[dict]:
    frm, to = (LEG[0], 0.0, LZ0), (LEG[1], LY1, LZ1)
    faces = leg_faces(frm, to)
    faces["down"] = ("hips", [0, 8, 4, 12])
    parts = [box(frm, to, "legs", faces=faces, skip=("up",))]
    return parts + tasset_3d() + knee() + wraps_3d()


def hips() -> list[dict]:
    frm, to = (HX0, LY1, LZ0), (HX1, HY1, LZ1)
    faces = hip_faces(frm, to)
    faces["up"] = "lining"
    faces["down"] = "lining"
    parts = [box(frm, to, "legs", faces=faces)]
    # the belt standing proud, with its buckle
    yb = HY1 - (WAIST_BELT - WAIST + 5) / 4 * (HY1 - LY1) / 4
    yt = HY1 - (WAIST_BELT - WAIST) / 4 * (HY1 - LY1) / 4
    parts.append(box((HX0 - 0.15, yb, LZ0 - 0.15), (HX1 + 0.15, yt, LZ1 + 0.15), "strap",
                     faces={"up": "strap", "down": "lining"}))
    parts.append(box((7.0, yb - 0.3, LZ0 - 0.4), (9.0, yt + 0.3, LZ0 - 0.15), "steel"))
    parts.append(box((7.45, yb, LZ0 - 0.5), (8.55, yt, LZ0 - 0.35), "strap"))
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
