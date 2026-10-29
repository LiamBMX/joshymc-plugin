"""Snowman Leggings: the leg piece of the Snowman set (issue #1316), built on the Snowman
Chestplate (the set's pilot module, whose palette, painters and 3D parts it imports).

The snowman's bottom snowball, rolled into a pair of legs:

Worn (humanoid_leggings layer, 4x): the waistband is the set's red knit, worn as a sash
with two cream stripes along it, knotted at the front on the wearer's right. Each leg is
packed snow shaded round, with a snowball knee (a lighter rounded lump with a blue
shadow under it, high enough that the Boots' knit cuff, which starts just under it,
leaves it in view) and two coal pebbles down the outside of the thigh.

Everything is painted flat against the leg (no flaps), so nothing clips as the legs
swing.

Item: the pair in 3D wearing the same paintings, with the knee snowballs standing proud,
snow clumps on the thighs, the sash standing out from the hips with its knot and a short
fringed tail, and the coal pebbles.
"""
from __future__ import annotations

import numpy as np

from art.items.krampus_leggings import (HX0, HX1, HY1, LEG, LY1, LZ0, LZ1, even_strip, hip_faces, leg_faces,
                                        _y_leg)
from art.items.snowman_chestplate import (CREAM, CREAM_KNIT, RED, S, SNOW, coal, coal_lump, fringe_3d, knit,
                                          knit_roll, lumps, mask_rect, round_bias, save_set_materials, snow,
                                          snowball, stripes)
from art.items.snowveil_guard import C, blank, edges, grid, shadow, shift, to_image
from art.kit import HUMANOID, box, display, mirror, model, place, region, save, save_layer, turn

ID = "snowman_leggings"
NAME = "Snowman Leggings"
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
KNEE = (24, 33)                 # the snowball knee's rows (the Boots' cuff covers it from row 27)
KNEE_C = 6                      # the knee's centre column (worn: the visible front, columns 0-11)
PEBBLES = (6,)                  # the coal pebble's top row on the outside of the thigh
WAIST = 32                      # the waistband is the body's lower 4 units (rows 32-47)
SASH = (35, 43)                 # the sash's rows (the seam under it is its shaded foot)
LAYERS = (12,)                  # rows where one packed layer of snow sits on the next
GARTER = (20, 23)               # the red knit garter band half way down the leg, linking sash and cuffs
WAIST_SEAM = 44                 # its last rows: the seam shared with the legs


def snow_leg(a: np.ndarray, seed: int, kind: str) -> None:
    """Packed snow over the whole leg, shaded round across the face and darkening a
    little toward the foot."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    bias = round_bias(h, w, cx=w * 0.45, cy=h * 0.3, rx=w * 0.85, ry=h * 0.9, depth=0.6) - 0.08 * (Y / h)
    if kind == "inner":
        bias = bias - 0.35
    elif kind == "back":
        bias = bias - 0.12
    snow(a, seed=seed, bias=bias)


def knee_ball(a: np.ndarray, seed: int, cx: float = KNEE_C, half: float = 5.4) -> None:
    """The snowball knee centred on column cx: a lighter rounded lump packed onto the leg,
    brightest at its upper left and fading into the snow with no outline (an outline on
    two knees side by side reads as a face)."""
    h, w = a.shape[:2]
    y0, y1 = KNEE
    X, Y = grid(h, w)
    cy = (y0 + y1 + 1) / 2
    ry = (y1 - y0 + 1) / 2
    d = np.sqrt(((X + 0.5 - cx) / half) ** 2 + ((Y + 0.5 - cy) / ry) ** 2)
    m = d <= 1.0
    dl = np.sqrt(((X + 0.5 - cx + 1.5) / half) ** 2 + ((Y + 0.5 - cy + 2) / ry) ** 2)
    below = (np.abs(X + 0.5 - cx) < half * 0.9) & (Y > y1) & (Y <= y1 + 2)
    a[below, :3] *= 0.9
    snow(a, m, seed=seed, bias=0.55 - 0.65 * np.clip(dl, 0, 1.3))


def packed_layers(a: np.ndarray, seed: int) -> None:
    """Where one packed layer of snow sits on the next: a lumpy, slightly shaded seam."""
    h, w = a.shape[:2]
    for k, row in enumerate(LAYERS):
        lumps(a, [row] * w, seed + k)


def garter(a: np.ndarray, seed: int) -> None:
    """The red knit garter band round the thigh: the sash's knit with a cream stripe, a
    lit top row, a dark foot row and a soft shadow on the snow under it."""
    h, w = a.shape[:2]
    y0, y1 = GARTER
    m = mask_rect(h, w, 0, y0, w - 1, y1)
    shadow(a, m, 0.45, reach=1, down_only=True)
    knit(a, m, seed=seed, along="x", bias=0.15)
    stripes(a, m, (y0 + 2,), seed=seed + 1)
    a[y0, :] = C(RED[6])
    a[y1, :] = C(RED[1])


def paint_leg_face(kind: str, seed: int, worn: bool = True) -> np.ndarray:
    """One face of the wearer's right leg: "outer", "front", "inner" or "back". worn=False
    is the item's version (its knee is 3D)."""
    w, h = 4 * S, 12 * S
    a = blank(w, h)
    snow_leg(a, seed, kind)
    packed_layers(a, seed + 7)
    if kind == "front":
        # a cool shadow down the inner side of the leg, where it turns toward the other one
        X, Y = grid(h, w)
        a[..., :3] *= (1 - 0.16 * np.clip((X - 7) / 5, 0, 1))[..., None]
    if kind == "front" and worn:
        knee_ball(a, seed + 3)
    elif kind == "outer":
        for y in PEBBLES:
            coal(a, 5, y)
        if worn:
            knee_ball(a, seed + 4, cx=8, half=3.6)
    elif kind == "inner" and worn:
        knee_ball(a, seed + 5, cx=8, half=3.0)
    garter(a, seed + 11)
    if kind in ("front", "back"):
        a[0:SEAM] = C(RED[2])
    a[h - 1, :, :3] *= 0.8
    if kind == "front":
        even_strip(a, 12, 15, 12)
    elif kind == "back":
        even_strip(a, 0, 3, 3)
    return a


def paint_leg_top(seed: int = 71) -> np.ndarray:
    a = blank(4 * S, 4 * S)
    snow(a, seed=seed, bias=0.2)
    return a


def paint_sole() -> np.ndarray:
    a = blank(4 * S, 4 * S)
    a[:] = C(SNOW[2])
    return a


def sash(a: np.ndarray, seed: int, knot_x=None) -> None:
    """The red knit sash across the waistband (rows SASH), two cream stripes along it, a
    rolled lit top edge, a shadow under it; a puffed knot at column knot_x."""
    h, w = a.shape[:2]
    y0, y1 = SASH
    m = mask_rect(h, w, 0, y0, w - 1, y1)
    shadow(a, m, 0.5, reach=1, down_only=True)
    knit(a, m, seed=seed, along="x", bias=0.1)
    stripes(a, m, (y0 + 3, y0 + 4), seed=seed + 1)
    X, Y = grid(h, w)
    a[m & (Y == y0)] = C(RED[6])
    a[m & (Y == y1)] = C(RED[1])
    if knot_x is not None:
        knot = mask_rect(h, w, knot_x - 3, y0 - 1, knot_x + 3, y1 + 1)
        knot &= ~(((Y == y0 - 1) | (Y == y1 + 1)) & ((X == knot_x - 3) | (X == knot_x + 3)))
        shadow(a, knot, 0.55, reach=1)
        knit(a, knot, seed=seed + 2, bias=0.3)
        top, bottom, left, right = edges(knot)
        a[top | left] = C(RED[6])
        a[bottom | right] = C(RED[1])


def paint_waist(kind: str, seed: int) -> np.ndarray:
    """The waistband (the body's lower 4 units): kind "front", "back" or "side" (the
    wearer's right side; the left is its mirror). Snow over the hips, the red sash (its
    knot at the front on the wearer's right); the last rows are the shared seam."""
    w, h = (8 if kind in ("front", "back") else 4) * S, 12 * S
    a = blank(w, h)
    band = mask_rect(h, w, 0, WAIST, w - 1, h - 1)
    snow(a, band, seed=seed, bias=0.0)
    sash(a, seed, knot_x=9 if kind == "front" else None)
    a[WAIST_SEAM:] = C(RED[2])
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

FRAME = ((HX0 - 2, -1, LZ0 - 3), (HX1 + 2, HY1 + 1, LZ1 + 3))


def leg_atlas() -> np.ndarray:
    """The "legs" texture: the leg faces side by side on rows 0-47 (outer, front, inner,
    back), the hips' front and back on rows 48-63."""
    a = blank(64, 64)
    for i, (kind, seed) in enumerate((("outer", 101), ("front", 111), ("inner", 121), ("back", 131))):
        face = paint_leg_face(kind, seed, worn=False)
        if kind == "inner":
            face[..., :3] *= 0.8
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
    save_set_materials(skip=("twig", "green", "berry", "carrot", "tail"))


def knee_3d() -> list[dict]:
    """The snowball knee standing proud of the right knee, wrapping round its sides."""
    y1, y0 = _y_leg(KNEE[0]), _y_leg(KNEE[1] + 1)
    cy = (y0 + y1) / 2
    p = snowball((LEG[0] - 0.15, y0 - 0.2, LZ0 - 1.9), (LEG[1] + 0.15, y1 + 0.2, LZ0 + 1.4), 1.5, steps=2,
                 frame=FRAME, rim="snow_rim")
    p += snowball((LEG[0] - 0.35, cy - 0.9, LZ0 - 0.5), (LEG[1] + 0.35, cy + 1.1, LZ1 - 1.0), 0.8, steps=1,
                  frame=FRAME, rim="snow_rim")
    return p


def clumps_3d() -> list[dict]:
    """Snow clumps packed on the thigh and the shin, the coal pebbles on the outer thigh."""
    p = []
    for x, y, z, s in ((LEG[0] + 1.0, _y_leg(8), LZ0 - 0.2, 0.75), (LEG[1] - 0.6, _y_leg(36), LZ0 - 0.1, 0.6),
                       (LEG[1] + 0.1, _y_leg(30), 8.9, 0.6), (LEG[0] + 2.2, _y_leg(5), LZ1 + 0.1, 0.7)):
        p += snowball((x - s, y - s, z - s), (x + s, y + s, z + s), s * 0.5, steps=1, frame=FRAME)
    # the coal pebbles on the outside of the thigh (the face's column 7.5 sits at z 8.0),
    # seated in the snow of the thigh's bulge
    for row in PEBBLES:
        y, z = _y_leg(row + 3), 8.0
        p += [box((LEG[1] + 0.2, y - 0.45, z - 0.6), (LEG[1] + 0.75, y + 0.5, z + 0.6), "coal", offset=(1, 0)),
              box((LEG[1] + 0.2, y - 0.6, z - 0.42), (LEG[1] + 0.85, y + 0.6, z + 0.45), "coal", offset=(3, 0))]
    return p


def thigh_3d() -> list[dict]:
    """The thigh rolled fat with snow like a snowman's bottom ball: a rounded bulge round
    the upper leg, narrowing again toward the knee, so the leg tapers to the ankle."""
    y_top, y_bot = LY1 - 0.4, _y_leg(KNEE[0]) - 0.3
    p = snowball((LEG[0] - 0.4, y_bot, LZ0 - 0.6), (LEG[1] + 0.55, y_top, LZ1 + 0.6), 1.1, steps=2,
                 frame=FRAME, rim="snow_rim")
    # the garter band standing proud round the thigh
    g1, g0 = _y_leg(GARTER[0]), _y_leg(GARTER[1] + 1)
    p += knit_roll((LEG[0] - 0.6, g0, LZ0 - 0.8), (LEG[1] + 0.75, g1, LZ1 + 0.8), tex="knit_x", r=0.25)
    p.append(box((LEG[0] - 0.65, g0 + 0.45, LZ0 - 0.85), (LEG[1] + 0.8, g0 + 0.7, LZ1 + 0.85), "cream",
                 skip=("up", "down")))
    return p


def right_leg() -> list[dict]:
    frm, to = (LEG[0], 0.0, LZ0), (LEG[1], LY1, LZ1)
    faces = leg_faces(frm, to)
    faces["down"] = ("hips", [0, 8, 4, 12])
    parts = [box(frm, to, "legs", faces=faces, skip=("up",))]
    return parts + thigh_3d() + knee_3d() + clumps_3d()


HIP_LO = (HX0 - 0.7, LY1 - 2.4, LZ0 - 0.8)     # the rounded snow hips (the bottom snowball)
HIP_HI = (HX1 + 0.7, HY1, LZ1 + 0.8)
SASH_Y = (HY1 - 2.3, HY1 + 0.15)                # the 3D sash's foot and top


def hips() -> list[dict]:
    """The snowman's bottom snowball worn as hips: rounded snow over the tops of the legs,
    a rounded seat behind, the red sash round the waist with a cream stripe, and a coal
    button on the front under it."""
    p = snowball(HIP_LO, HIP_HI, 1.7, steps=3, frame=FRAME, rim="snow_rim")
    p += snowball((HX0 + 0.6, LY1 - 2.6, LZ1 - 1.0), (HX1 - 0.6, HY1 - 1.2, LZ1 + 1.7), 1.3, steps=2, frame=FRAME)
    y0, y1 = SASH_Y
    (x0, _, z0), (x1, _, z1) = HIP_LO, HIP_HI
    p += knit_roll((x0 - 0.3, y0, z0 - 0.3), (x1 + 0.3, y1 - 0.3, z1 + 0.3), tex="knit_x", r=0.35)
    p.append(box((x0, y1 - 0.35, z0), (x1, y1, z1), "knit", faces={"up": "lining"}, offset=(0, 2)))
    p.append(box((x0 - 0.38, y0 + 0.75, z0 - 0.38), (x1 + 0.38, y0 + 1.1, z1 + 0.38), "cream", skip=("up", "down")))
    p += coal_lump(8.0, y0 - 1.6, z0 + 0.25, 0.7, 0.5)
    return p


def hip_knot() -> list[dict]:
    """The sash's knot at the front on the wearer's right, with two short fringed tails
    hanging from it over the thigh."""
    parts = []
    kx, ky = 10.4, sum(SASH_Y) / 2
    kz = HIP_LO[2] - 0.5
    parts += [box((kx - 1.2, ky - 1.25, kz - 0.5), (kx + 1.2, ky + 1.25, kz + 1.0), "knit_x", offset=(1, 1)),
              box((kx - 0.95, ky - 1.55, kz - 0.75), (kx + 0.95, ky + 1.55, kz + 0.8), "knit_x", offset=(2, 0))]
    for k, (dx, ang, ln) in enumerate(((-0.55, 9, 2.6), (0.65, -15, 2.0))):
        y0 = ky - 1.1
        tail = [box((kx + dx - 0.65, y0 - ln, kz - 0.3), (kx + dx + 0.65, y0, kz + 0.05), "knit", offset=(k * 2, 1))]
        tail += fringe_3d(kx + dx - 0.6, kx + dx + 0.6, y0 - ln + 0.05, kz - 0.27, kz + 0.02, seed=7 + k,
                          length=0.9)
        turn(tail, ang, "z", (kx + dx, y0, kz - 0.1))
        turn(tail, -8, "x", (kx + dx, y0, kz - 0.1))
        parts += tail
    return parts


def build() -> list[dict]:
    right = right_leg()
    return hips() + hip_knot() + right + mirror(right, "x", 8.0)


def models() -> dict:
    parts = build()
    d = display(KIND, parts, gui_rotation=(3, 152, 0), gui_span=15.5)
    centre = (8.0, 9.0, 8.0)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, centre, "fist", 0.42)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.4, 0, -1)}, centre, (0.52, -0.38, -0.95), 0.5,
                                       pose=None)
    return {"main": model(parts, d)}
