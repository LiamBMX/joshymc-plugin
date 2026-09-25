"""Harvest Key: the farm-gold crate key of the harvest (one of the 19 JoshyMC crate keys).

Built on the january_key skeleton (see its docstring for the shared proportions and the
atlas / sweep machinery).
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, mix, model, place, prism, rgba, save, save_animation, shine,
                     sparkle, turn)

ID = "harvest_key"
NAME = "Harvest Key"
KIND = "sword"
COUNTERPART = "item/trial_key"

# ======================================================================================
# 1. SHARED KEY SKELETON (identical for every key in the set, copied from january_key)
# ======================================================================================
AX, AZ = 8.0, 8.0                 # the key's axis; front = +Z
BOW_Y = 3.5                       # centre of the bow ring
BOW_OUT, BOW_IN = 5.4, 3.5        # ring radii
BOW_D = 2.6                       # main band depth: z 6.7..9.3
RIM_W, RIM_D = 0.5, 2.0           # recessed outer rim
LIP_W, LIP_D = 0.6, 3.0           # raised inner lip (0.2 proud of the band front and back)
BAND_R = (BOW_OUT - RIM_W + BOW_IN + LIP_W) / 2     # centreline of the main band
BOW_SEGMENTS = 20
WINDOW_R = 3.55                   # the window reaches just under the lip
WINDOW_D = 0.5                    # recessed: z 7.75..8.25
SOCKET_R, GEM_R = 1.6, 1.15       # bezel and gem apothems
SHAFT_R = 1.15                    # octagonal shaft apothem (2.3 thick)
SHAFT_Y0, SHAFT_Y1 = 10.0, 25.5
COLLAR = ((8.4, 9.4, 2.05), (9.4, 10.2, 1.6))       # (y0, y1, apothem), bottom to top
BANDS = ((12.6, 13.4, 1.5), (17.2, 18.0, 1.55))     # rings round the shaft
CAP = (25.4, 26.3, 1.5)                             # the shaft's top cap
BIT_Y0, BIT_Y1 = 18.0, 25.2                         # the bit's span on the -X side
BIT_X0 = 4.0                                        # the bit plate's outer edge (themes may move it)
BIT_X1 = AX - SHAFT_R + 0.35                        # bit parts run into the shaft up to here
CLAW_ANGLES = tuple(90 + 60 * k for k in range(6))  # the six claws round the heart gem
GRIP = (8.0, 10.6, 8.0)
SIZE = 0.64
GUI_ROTATION = (-25, 20, -45)

# --- timing: every animation loops in 64 ticks ------------------------------------------
FRAMES, FRAMETIME = 32, 2          # atlas and window
GEM_FRAMES, GEM_FRAMETIME = 16, 4  # gem (interpolated)

# --- the metal atlas ----------------------------------------------------------------------
ATLAS = 64                        # px per frame side
TPU = ATLAS / 16                  # texels per uv unit
Y_TOP = 29.0                      # model height of atlas row 0
RPU = 2                           # atlas rows per model unit
LIGHT = (-0.99, 0.14)             # painted light: from the key's -X side = top-left in the GUI
LANES = {                         # name -> (first column, width) in texels
    # skeleton (0-41): the ring's band fronts, outer rim fronts and lip fronts in three light
    # variants each, the ring's sides, then shaft facets, bands, the gem bezel and caps
    "face0": (0, 3), "face1": (3, 3), "face2": (6, 3),
    "rim0": (9, 2), "rim1": (11, 2), "rim2": (13, 2),
    "lip0": (15, 2), "lip1": (17, 2), "lip2": (19, 2),
    "rim_side": (21, 3), "lip_side": (24, 3), "step": (27, 2),
    "shaft": (29, 4), "band": (33, 4), "bezel": (37, 3), "cap": (40, 2),
    # theme (42-63): two wheat-ear lanes that glint in turn, straw, twine, the bit's panel
    "ear": (42, 3), "ear_b": (45, 3), "straw": (48, 3), "twine": (51, 3), "panel": (54, 10),
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#fff3c6", width=8.0, strength=0.88, angle=-90.0, pause=0.4)   # bow -> tip
TRAIL = dict(colour="#ffffff", width=2.5, strength=0.7, angle=-90.0, pause=0.4)
TRAIL_LAG = 0.05


def clamp(v, lo=0.0, hi=1.0):
    return max(lo, min(hi, v))


def v_at(y: float) -> float:
    """The atlas v (0..16) of model height y."""
    return clamp((Y_TOP - y) * RPU / TPU, 0.0, 16.0)


def lane_uv(lane: str, y_hi: float, y_lo: float, flip: bool = False, part=(0.0, 1.0)) -> list[float]:
    """uv over a lane's columns, from height y_hi (the face's v0 edge) to y_lo (its v1
    edge); flip mirrors the lane across the face and part picks a slice of its width.
    Thin spans still get one whole row."""
    c, w = LANES[lane]
    ua, ub = (c + w * part[0]) / TPU, (c + w * part[1]) / TPU
    va, vb = v_at(y_hi), v_at(y_lo)
    if abs(vb - va) < 1 / TPU:
        m = clamp((va + vb) / 2, 0.5 / TPU, 16 - 0.5 / TPU)
        va, vb = m - 0.5 / TPU, m + 0.5 / TPU
    uv = [ub, va, ua, vb] if flip else [ua, va, ub, vb]
    return [round(x, 4) for x in uv]


def dot_uv(lane: str, y: float, k: float = 0.5) -> list[float]:
    """One texel of a lane at height y (k picks the column 0..1): a flat colour for caps."""
    c, w = LANES[lane]
    col = c + min(w - 1, int(w * k))
    r = int(clamp((Y_TOP - y) * RPU, 0, ATLAS - 1))
    return [round((col + 0.25) / TPU, 4), round((r + 0.25) / TPU, 4),
            round((col + 0.75) / TPU, 4), round((r + 0.75) / TPU, 4)]


def lane_faces(front: str, y_hi: float, y_lo: float, east: str | None = None, west: str | None = None,
               flip: bool = False, sides: dict | None = None, tex: str = "metal") -> dict:
    """Faces for a box whose local +Y end sits at height y_hi and -Y end at y_lo once it is
    turned: front/back sample `front`, the local +X/-X sides `east`/`west`, each at the rows
    of its own height, so the sweep reaches every face as it passes that height. `sides`
    overrides single faces with a uv (e.g. a dot_uv)."""
    east, west = east or front, west or front
    mid = (y_hi + y_lo) / 2
    faces = {"south": lane_uv(front, y_hi, y_lo, flip), "north": lane_uv(front, y_hi, y_lo, not flip),
             "east": lane_uv(east, y_hi, y_lo), "west": lane_uv(west, y_hi, y_lo, True),
             "up": dot_uv(front, mid), "down": dot_uv(front, mid)}
    faces.update(sides or {})
    return {side: (tex, uv) for side, uv in faces.items()}


def lit(direction) -> float:
    """How squarely a surface facing `direction` (x, y) faces the painted light (-1..1)."""
    n = math.hypot(*direction) or 1.0
    return (direction[0] * LIGHT[0] + direction[1] * LIGHT[1]) / (n * math.hypot(*LIGHT))


def radial(c, ang: float, r0: float, r1: float, w: float, d: float, lane: str, z: float = AZ,
           flip: bool | None = None, east=None, west=None, sides=None, **kw) -> dict:
    """A bar from radius r0 to r1 out of centre c = (x, y) at angle ang (degrees, in the
    front plane), w wide and d deep. Its lane's first column lands on the lit edge. Its
    local +X side (`east`) faces clockwise of ang: for ang = 180 that is the top."""
    cx, cy = c
    a = math.radians(ang)
    if flip is None:
        flip = lit((-math.sin(a), math.cos(a))) < 0
    e = box((cx - w / 2, cy + r0, z - d / 2), (cx + w / 2, cy + r1, z + d / 2), "metal",
            faces=lane_faces(lane, cy + r1 * math.sin(a), cy + r0 * math.sin(a), east, west, flip, sides), **kw)
    return turn(e, ang - 90, "z", (cx, cy, z))


def diamond(c, side: float, d: float, lane: str, ang: float = 45.0, z: float = AZ, **kw) -> dict:
    """A square turned by ang about Z (45 = corners up/down/left/right)."""
    cx, cy = c
    h = side * 0.35
    e = box((cx - side / 2, cy - side / 2, z - d / 2), (cx + side / 2, cy + side / 2, z + d / 2), "metal",
            faces=lane_faces(lane, cy + h, cy - h), **kw)
    return turn(e, ang, "z", (cx, cy, z))


def spike(c, ang: float, r0: float, r1: float, w: float, d: float, lane: str, z: float = AZ, **kw) -> list[dict]:
    """A pointed crystal from radius r0 to its point at r1: a bar capped by a turned square."""
    cx, cy = c
    a = math.radians(ang)
    end = r1 - w / 2
    tip = (cx + end * math.cos(a), cy + end * math.sin(a))
    return [radial(c, ang, r0, end, w, d, lane, z=z, **kw),
            diamond(tip, w / math.sqrt(2), d - 0.1, lane, ang - 45, z=z, **kw)]


def arc_segment(c, radius: float, a0: float, a1: float, w: float, d: float, lane, east: str, west: str,
                z: float = AZ, flip: bool = False, sides=None, **kw) -> dict:
    """One bar of a ring round c (angles in degrees, counter-clockwise; local +X points
    outward). lane is a lane name, or a tuple of lanes picked by how the outward
    direction faces the light (first = facing away)."""
    cx, cy = c
    mid = math.radians((a0 + a1) / 2)
    half_angle = math.radians(a1 - a0) / 2
    half = radius * math.sin(half_angle) * 1.08          # stretched so neighbours overlap
    r = radius * math.cos(half_angle)
    y_hi = cy + r * math.sin(mid) + half * math.cos(mid)
    y_lo = cy + r * math.sin(mid) - half * math.cos(mid)
    if not isinstance(lane, str):
        lane = lane[round((lit((math.cos(mid), math.sin(mid))) + 1) * (len(lane) - 1) / 2)]
    e = box((cx + r - w / 2, cy - half, z - d / 2), (cx + r + w / 2, cy + half, z + d / 2), "metal",
            faces=lane_faces(lane, y_hi, y_lo, east, west, flip, sides), **kw)
    return turn(e, math.degrees(mid), "z", (cx, cy, z))


def lane_prism(y0: float, y1: float, r: float, lane: str, cap: str = "cap", x: float = AX, z: float = AZ,
               **kw) -> list[dict]:
    """An octagonal rod along Y whose facets sample `lane` at their height."""
    side = lane_uv(lane, y1, y0)
    back = lane_uv(lane, y1, y0, True)
    uv = {"south": side, "north": back, "east": side, "west": back,
          "up": dot_uv(cap, y1), "down": dot_uv(cap, y0)}
    return prism((x, (y0 + y1) / 2, z), r, y1 - y0, "metal", cap="metal", uv=uv, **kw)


# --- skeleton parts ---------------------------------------------------------------------

def bow_ring() -> list[dict]:
    """The stepped ring: recessed outer rim, main band, raised inner lip."""
    step = 360 / BOW_SEGMENTS
    c = (AX, BOW_Y)
    rim_r = BOW_OUT - RIM_W / 2
    lip_r = BOW_IN + LIP_W / 2
    band_w = BOW_OUT - RIM_W - BOW_IN - LIP_W + 0.1
    parts = []
    for k in range(BOW_SEGMENTS):
        a0, a1 = 90 + (k - 0.5) * step, 90 + (k + 0.5) * step
        parts.append(arc_segment(c, rim_r, a0, a1, RIM_W, RIM_D, RIM_LANES, "rim_side", "step"))
        parts.append(arc_segment(c, BAND_R, a0, a1, band_w, BOW_D, FACE_LANES, "step", "step"))
        parts.append(arc_segment(c, lip_r, a0, a1, LIP_W, LIP_D, LIP_LANES, "step", "lip_side"))
    return parts


def bow_window(tex: str = "window", glow: int = 7) -> list[dict]:
    """The recessed window: three slabs that cover the ring's opening while staying inside
    its outer edge, one texture projected across them."""
    r = WINDOW_R
    h = r * 0.54
    xs = math.sqrt(r * r - h * h) + 0.06
    parts = []
    for x0, ya, x1, yb in ((-r, -h, r, h), (-xs, h, xs, r), (-xs, -r, xs, -h)):
        u0, u1 = (x0 + r) / (2 * r) * 16, (x1 + r) / (2 * r) * 16
        v0, v1 = (r - yb) / (2 * r) * 16, (r - ya) / (2 * r) * 16
        parts.append(box((AX + x0, BOW_Y + ya, AZ - WINDOW_D / 2), (AX + x1, BOW_Y + yb, AZ + WINDOW_D / 2), tex,
                         faces={"south": (tex, [u0, v0, u1, v1]), "north": (tex, [u1, v0, u0, v1])},
                         skip=("east", "west", "up", "down"), glow=glow))
    return parts


def gem_socket(claw_angles) -> list[dict]:
    """The bezel behind the heart gem and six claws over its rim."""
    uv = {s: dot_uv("bezel", BOW_Y, 0.9) for s in ("north", "south", "east", "west")}
    uv["up"] = uv["down"] = dot_uv("bezel", BOW_Y, 0.5)       # the front and back faces
    parts = prism((AX, BOW_Y, AZ), SOCKET_R, 2.2, "metal", axis="z", cap="metal", uv=uv)
    for ang in claw_angles:
        parts.append(radial((AX, BOW_Y), ang, GEM_R - 0.25, SOCKET_R + 0.15, 0.5, 0.6, "band", z=9.3))
    return parts


def collar() -> list[dict]:
    parts = []
    for y0, y1, r in COLLAR:
        parts += lane_prism(y0, y1, r, "band")
    return parts


def shaft() -> list[dict]:
    parts = lane_prism(SHAFT_Y0, SHAFT_Y1, SHAFT_R, "shaft")
    for y0, y1, r in BANDS + (CAP,):
        parts += lane_prism(y0, y1, r, "band")
    return parts


def panel_uv(x0: float, x1: float, y_hi: float, y_lo: float, flip: bool = False) -> list[float]:
    """uv of the "panel" lane for a front face spanning x0..x1: the lane's width is the
    bit from BIT_X0 to BIT_X1, so raised inlays can show their part of the painting."""
    span = BIT_X1 - BIT_X0
    return lane_uv("panel", y_hi, y_lo, flip, ((x0 - BIT_X0) / span, (x1 - BIT_X0) / span))


def bit_plate(x0: float, y0: float, y1: float, d: float) -> list[dict]:
    """The bit's plate, from x0 (normally BIT_X0) into the shaft; its front shows the
    "panel" lane painted at these rows."""
    faces = {"south": ("metal", panel_uv(x0, BIT_X1, y1, y0)),
             "north": ("metal", panel_uv(x0, BIT_X1, y1, y0, True)),
             "west": ("metal", lane_uv("rim_side", y1, y0, True)),
             "up": ("metal", dot_uv("band", y1, 0.2)), "down": ("metal", dot_uv("step", y0, 0.5))}
    return [box((x0, y0, AZ - d / 2), (BIT_X1, y1, AZ + d / 2), "metal", faces=faces, skip=("east",))]


def skeleton(claws=CLAW_ANGLES) -> list[dict]:
    return bow_ring() + bow_window() + gem_socket(claws) + collar() + shaft()


def transforms(parts) -> dict:
    d = display(KIND, parts, grip=GRIP, size=SIZE, gui_rotation=GUI_ROTATION)
    # Carried like a sceptre: raised forward and up, clear of the forearm, the bow hanging
    # by the fist with its face turned out.
    d["thirdperson_righthand"] = place({"y": (0.12, 0.74, 0.66), "z": (-0.8, -0.2, 0.3)}, GRIP, "fist",
                                       0.85 * SIZE)
    # First person: rising from the lower right, leaning in, the whole bow in view.
    d["firstperson_righthand"] = place({"y": (-0.36, 0.93, -0.12), "z": (0.3, 0.1, 1)}, (AX, BOW_Y, AZ),
                                       (0.58, -0.36, -0.9), 0.82 * SIZE, pose=None)
    return d


# The skeleton's metal lanes as indices into METAL (7 tones, dark -> light): across fronts
# inner -> outer, across sides front -> back.
METAL_TONES = {
    "face0": (4, 3, 3), "face1": (4, 4, 3), "face2": (5, 5, 4),
    "rim0": (2, 1), "rim1": (4, 3), "rim2": (6, 5),
    "lip0": (6, 5), "lip1": (5, 5), "lip2": (4, 5),
    "rim_side": (5, 4, 3), "lip_side": (3, 2, 1), "step": (3, 2),
    "shaft": (5, 4, 3, 3), "band": (6, 5, 4, 4), "bezel": (1, 3, 2), "cap": (5, 5),
}


def paint_skeleton_lanes(img, metal) -> None:
    for lane, tones in METAL_TONES.items():
        paint_lane(img, lane, [metal[i] for i in tones])


def paint_lane(img, lane: str, tones, rows=None) -> None:
    px = img.load()
    c, w = LANES[lane]
    for y in rows if rows is not None else range(ATLAS):
        for i in range(w):
            px[c + i, y] = rgba(tones[min(i, len(tones) - 1)])


def row_of(y: float) -> int:
    """The atlas row holding model height y."""
    return int((Y_TOP - y) * RPU)


def dither_band(f: float, x: int, y: int) -> int:
    """Round a fractional ramp index to a band, checkering the seam between two bands."""
    frac = f - math.floor(f)
    if 0.38 < frac < 0.62:
        return math.floor(f) + (x + y) % 2
    return round(f)


def atlas_frame(base, t: float):
    """One atlas frame: the glint and its thin trailing twin sweep up the key, then the
    theme adds its own touches (theme_atlas_frame)."""
    img = shine(base, t, **SWEEP)
    img = shine(img, (t - TRAIL_LAG) % 1.0, **TRAIL)
    theme_atlas_frame(img, t)
    return img


def sweep_time(y: float) -> float:
    """Loop phase (0..1) at which the glint's centre crosses model height y."""
    w, run = SWEEP["width"], 1 - SWEEP["pause"]
    centre = -((Y_TOP - y) * RPU + 0.5)
    return run * (centre + ATLAS + 2 * w) / (ATLAS + 4 * w)


GEM_PEAK = sweep_time(BOW_Y) + 0.02


def gem_pulse(t: float) -> float:
    """0..1, the shared gem timing: a quick flare as the glint passes through the heart
    gem, then a slow fade. Themes drive the gem and the window's glow with it."""
    x = (t - GEM_PEAK) % 1.0
    rise, fall = 0.08, 0.3
    floor = math.exp(-(1 - rise) / fall)
    if x >= 1 - rise:
        k = (x - (1 - rise)) / rise
        return floor + (1 - floor) * (0.5 - 0.5 * math.cos(math.pi * k))
    return math.exp(-x / fall)


# ======================================================================================
# 2. THEME PALETTE: the harvest, farm gold and pumpkin orange (#E8971E)
# ======================================================================================
METAL = GOLD = ["#3b1608", "#6e2c0c", "#a24c12", "#d27a1a", "#eca332", "#f9cf64", "#fff2bd"]
WHEAT = ["#57300a", "#8a5512", "#bf861f", "#e4b030", "#f7d152", "#ffe98e", "#fff8d2"]
STRAW = ["#5a3f14", "#8a6424", "#b58c36", "#d6ae4c", "#ecc96c", "#f8e3a0"]
TWINE = ["#3d2816", "#603f24", "#876036", "#ae8750", "#cfab74", "#ead09e"]
GEM = ["#3a0a04", "#6c1805", "#a5300a", "#d9520f", "#f5761a", "#ff9e36", "#ffcb73", "#fff3d2"]
SKY = ["#141438", "#1b1b50", "#252563", "#312d76", "#423584", "#583c8c", "#77448c", "#a04f82", "#cc6070",
       "#ee845a"]
FIELD = ["#1a0c16", "#28111b", "#3d1b1e", "#56291f"]              # the backlit field, dark -> light
EARLIT = ["#6e3a18", "#a86424", "#d9973a", "#f6c86a", "#fff0c4"]   # its ears, rim-lit by the sunset

# ======================================================================================
# 3. THEME PARTS: a wheat sheaf tied with a twine bow, the pumpkin gem, seed studs, the hoe
#    bit, the twine grip, a wheat sprig and the wheat-ear finial
# ======================================================================================
CLAWS = (30, 150, 210, 270, 330)                 # five claws; a gold stem holds the gem's top
EARS = (((AX, 4.8), 90.0, 2.15, "ear"),          # (base, angle, length, lane): a bouquet of three
        ((AX - 0.75, 4.62), 130.0, 2.05, "ear_b"),
        ((AX + 0.75, 4.62), 50.0, 2.05, "ear_b"))
STALKS = ((7.3, 6.65), (7.65, 7.33), (8.0, 8.0), (8.35, 8.67), (8.7, 9.35))  # (top x, foot x) under the knot
STUD_ANGLES = tuple(120 + 60 * k for k in range(6))   # pumpkin seeds round the band
BOW_LOOP = ((1.45, 0.45), (3.02, 1.02), (3.02, -0.82), (1.45, -0.35))  # knot top, outer top, outer foot, knot foot
BIT_X0 = 2.3                                     # the hoe blade's outer edge
COL = (BIT_X1 - BIT_X0) / 10                     # one panel column, in units
NECK = (3.2, 23.5, 25.0)                         # the hoe's neck: outer x, foot y, top y
BLADE = (BIT_X0, 3.5, 20.3, 25.3, 6.3, 9.7)      # x0, x1, y0, y1, z0, z1: broad across the key
EDGE = (1.95, 3.75, 19.5, 20.45, 6.2, 9.8)       # the ground edge under it, flared
FLARE = (2.1, 3.62, 20.35, 21.3, 6.25, 9.75)     # the blade widening into its edge
GRIP_WRAP = (10.2, 12.6)                         # twine wound round the grip
SEED_UV = [7.0, 9.0, 8.0, 10.0]                  # the patch of the gem the seed studs show
WREATH_R = 5.55                                  # ears lying along the ring's shoulders
WREATH = ((170, 1, "ear"), (140, 1, "ear_b"), (10, -1, "ear"), (40, -1, "ear_b"))  # (from angle, climb, lane)
LEAF = ["#1f3812", "#35561a", "#527a22", "#76a02e", "#a4c850"]   # the pumpkin stem


def rod(p0, p1, w: float, d: float, lane: str, z: float = AZ, flip: bool | None = None, sides=None, **kw) -> dict:
    """A bar in the front plane from p0 to p1 (x, y) at depth z, w wide and d deep; its
    faces sample `lane` at their own height, the lane's first column on the lit edge."""
    ux, uy = p1[0] - p0[0], p1[1] - p0[1]
    if flip is None:
        flip = lit((-uy, ux)) < 0
    return bar((p0[0], p0[1], z), (p1[0], p1[1], z), w, d, "metal",
               faces=lane_faces(lane, p1[1], p0[1], flip=flip, sides=sides), **kw)


def stretch(p0, p1, e: float):
    """p0 and p1 pushed apart by e at each end (so rods meet in full corners)."""
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    n = math.hypot(dx, dy) or 1.0
    return (p0[0] - dx / n * e, p0[1] - dy / n * e), (p1[0] + dx / n * e, p1[1] + dy / n * e)


def along(base, ang: float, dist: float, off: float = 0.0):
    """The point dist along direction ang from base, shifted off to its left."""
    a = math.radians(ang)
    return (base[0] + math.cos(a) * dist - math.sin(a) * off, base[1] + math.sin(a) * dist + math.cos(a) * off)


def ear(base, ang: float, length: float, lane: str, width: float = 1.0, d: float = 1.0, z: float = AZ,
        rows: int = 3, **kw) -> list[dict]:
    """A plump ear of wheat from base along ang: a core that thins to a pointed tip, with
    kernels bulging out of both edges in alternate rows."""
    parts = [rod(base, along(base, ang, length * 0.76), width * 0.58, d, lane, z=z, **kw)]
    for k in range(rows):
        taper = 1 - 0.28 * k / max(1, rows - 1)
        for s in (1, -1):
            f = 0.08 + 0.6 * k / max(1, rows - 1) + (0.1 if s < 0 else 0.0)
            p = along(base, ang, length * f, s * width * 0.13)
            q = along(p, ang + s * 32, width * 0.52 * taper)
            parts.append(rod(p, q, width * 0.42 * taper, d - 0.16, lane, z=z, **kw))
    parts.append(diamond(along(base, ang, length * 0.86), width * 0.4, d - 0.2, lane, ang - 45, z=z, **kw))
    return parts


def sheaf_emblem() -> list[dict]:
    """A golden wheat sheaf in the window: three plump ears rising out of the knot as a
    bouquet, and the bound stalks fanning out below it to a cut end."""
    parts = []
    for base, ang, length, lane in EARS:
        parts.append(rod(along(base, ang, -0.6), along(base, ang, 0.3), 0.36, 0.8, "straw"))
        parts += ear(base, ang, length, lane, width=1.2, glow=3)
    for x_top, x_foot in STALKS:
        parts.append(rod((x_foot, -0.1), (x_top, 2.4), 0.36, 0.8, "straw"))
    return parts


def twine_bow() -> list[dict]:
    """The twine bow tying the sheaf: two loops out to either side of the heart gem, which
    sits on the knot."""
    parts = []
    for s in (1, -1):
        pts = [(AX + s * x, BOW_Y + y) for x, y in BOW_LOOP]
        for p0, p1 in zip(pts, pts[1:]):
            a, b = stretch(p0, p1, 0.2)
            parts.append(rod(a, b, 0.56, 1.1, "twine"))
    return parts


def pumpkin_gem() -> list[dict]:
    """The heart gem: a glowing pumpkin-cut orange gem, a body plus a painted front and back,
    held at the top by a curled gold stem."""
    side = {s: [5, 7, 6, 8] for s in ("north", "south", "east", "west")}
    side["up"] = side["down"] = [0.2, 7.5, 0.6, 8.0]
    parts = prism((AX, BOW_Y, AZ), GEM_R, 2.3, "gem", axis="z", cap="gem", uv=side, glow=14)
    r = GEM_R + 0.05
    parts.append(box((AX - r, BOW_Y - r, 9.15), (AX + r, BOW_Y + r, 9.5), "gem", uv="full",
                     skip=("north", "east", "west", "up", "down"), glow=14))
    parts.append(box((AX - r, BOW_Y - r, 6.5), (AX + r, BOW_Y + r, 6.85), "gem",
                     faces={"north": ("gem", [16, 0, 0, 16])},
                     skip=("south", "east", "west", "up", "down"), glow=14))
    for z in (9.3, 6.7):                                        # the green stem, front and back
        for p0, p1, w in (((AX - 0.05, BOW_Y + GEM_R - 0.3), (AX + 0.15, BOW_Y + SOCKET_R + 0.35), 0.52),
                          ((AX + 0.05, BOW_Y + SOCKET_R + 0.3), (AX + 0.8, BOW_Y + SOCKET_R + 0.12), 0.34)):
            parts.append(bar((p0[0], p0[1], z), (p1[0], p1[1], z), w, 0.6 if w > 0.4 else 0.5, "stem",
                             uv="full"))
    return parts


def wheat_wreath() -> list[dict]:
    """Ears of wheat lying along the ring's shoulders like a wreath, climbing toward the
    collar from either side."""
    parts = []
    for a0, climb, lane in WREATH:
        a = math.radians(a0)
        base = (AX + WREATH_R * math.cos(a), BOW_Y + WREATH_R * math.sin(a))
        parts += ear(base, a0 - climb * 103, 2.55, lane, width=0.85, d=1.8)
    return parts


def seed_studs() -> list[dict]:
    """Six pumpkin seeds set round the band; they show a patch of the gem, so they glow and
    flare with it."""
    parts = []
    for ang in STUD_ANGLES:
        a = math.radians(ang)
        e = diamond((AX + BAND_R * math.cos(a), BOW_Y + BAND_R * math.sin(a)), 0.6, BOW_D + 0.24, "band",
                    ang - 45, glow=6)
        for face in e["faces"].values():
            face["texture"], face["uv"] = "#gem", list(SEED_UV)
        parts.append(e)
    return parts


def hoe_bit() -> list[dict]:
    """The bit is a hoe head: a neck running out from the shaft to a blade that hangs from
    its end, broad across the key and ground to a bright edge at its foot."""
    x_out, n0, n1 = NECK
    parts = bit_plate(x_out, n0, n1, 1.8)
    x0, x1, y0, y1, z0, z1 = BLADE
    c0, w = LANES["panel"]
    face = [c0 / TPU, 0.0, (c0 + w) / TPU, len(BLADE_FACE) / TPU]      # the broad faces' own painting
    parts.append(box((x0, y0, z0), (x1, y1, z1), "metal",
                     faces={"south": ("metal", panel_uv(x0, x1, y1, y0)),
                            "north": ("metal", panel_uv(x0, x1, y1, y0, True)),
                            "west": ("metal", face), "east": ("metal", [face[2], face[1], face[0], face[3]]),
                            "up": ("metal", dot_uv("band", y1, 0.0)), "down": ("metal", dot_uv("step", y0))}))
    x0, x1, y0, y1, z0, z1 = FLARE
    parts.append(box((x0, y0, z0), (x1, y1, z1), "metal", faces=lane_faces("band", y1, y0)))
    x0, x1, y0, y1, z0, z1 = EDGE
    edge_uv = lane_uv("lip0", y1, y0)
    parts.append(box((x0, y0, z0), (x1, y1, z1), "metal",
                     faces={"south": ("metal", edge_uv), "north": ("metal", edge_uv), "west": ("metal", edge_uv),
                            "east": ("metal", edge_uv), "down": ("metal", dot_uv("lip0", y0, 0.0))}, skip=("up",)))
    return parts


def twine_grip() -> list[dict]:
    return lane_prism(GRIP_WRAP[0], GRIP_WRAP[1], 1.3, "twine", cap="twine")


def wheat_sprigs() -> list[dict]:
    """Two ears of wheat tucked into the lower band, leaning out to either side of the
    shaft like a harvest wreath."""
    parts = []
    for s in (1, -1):
        ang = 90 - s * 18
        base = (AX + s * 1.2, 14.3)
        parts.append(rod((AX + s * 0.35, 12.9), along(base, ang, 0.35), 0.4, 0.6, "straw"))
        parts += ear(base, ang, 2.8, "ear_b", width=1.15, d=0.9)
        tip = along(base, ang, 2.5)
        parts.append(rod(tip, along(tip, ang + s * 6, 1.1), 0.2, 0.2, "ear_b"))
    return parts


def wheat_finial() -> list[dict]:
    """A golden wheat ear crowning the shaft: kernels in four rows round a straw core, a
    pointed tip and long awns streaming up."""
    parts = lane_prism(26.2, 26.9, 0.62, "straw", cap="straw")
    parts.append(rod((AX, 26.7), (AX, 29.3), 0.7, 0.7, "ear"))
    lean = math.radians(30)
    for k in range(3):
        y = 26.85 + 0.72 * k
        size = 1.0 - 0.1 * k
        for s in (-1, 1):                                       # the rows seen from the front
            p0 = (AX + s * 0.16, y)
            p1 = (AX + s * (0.16 + 1.0 * size * math.sin(lean)), y + 1.0 * size * math.cos(lean))
            parts.append(rod(p0, p1, 0.74 * size, 0.66, "ear"))
        y2 = y + 0.36
        for s in (-1, 1):                                       # the rows facing front and back
            p0 = (AX, y2, AZ + s * 0.16)
            p1 = (AX, y2 + 1.0 * size * math.cos(lean), AZ + s * (0.16 + 1.0 * size * math.sin(lean)))
            parts.append(bar(p0, p1, 0.74 * size, 0.66, "metal", faces=lane_faces("ear_b", p1[1], p0[1])))
    parts.append(diamond((AX, 29.35), 0.66, 0.8, "ear"))
    for x0, x1, y1 in ((7.8, 7.15, 30.9), (8.2, 8.85, 30.9)):   # awns streaming off the tip
        parts.append(rod((x0, 29.0), (x1, y1), 0.24, 0.24, "ear"))
    return parts


def theme() -> list[dict]:
    return (sheaf_emblem() + twine_bow() + pumpkin_gem() + seed_studs() + wheat_wreath() + hoe_bit()
            + twine_grip() + wheat_sprigs() + wheat_finial())


# ======================================================================================
# 4. TEXTURES
# ======================================================================================
# The bit, painted into the panel lane: 10 columns over x 2.3..7.2 (the blade is columns
# 0-2, the neck 2-9), one row per half unit from y 25.5 down. The blade's broad faces show
# its columns too. Digits are GOLD tones, e = the honed edge, . = unused.
PANEL = [
    "565.......",   # 25.0-25.5  the blade's heel
    "4543666665",   # 24.5-25.0  the neck's top edge
    "4643546463",   # 24.0-24.5  kernels engraved along the neck
    "4541212121",   # 23.5-24.0  the neck's shadowed foot
    "464.......",   # 23.0-23.5  the blade's face, a polish streak running down it
    "464.......",
    "454.......",
    "454.......",
    "454.......",   # 21.0-21.5
    "343.......",   # 20.5-21.0
    "232.......",   # 20.0-20.5  where the edge is ground
]
PANEL_TOP = 25.5
# The hoe blade's broad faces (4 x 5 units), painted into the panel lane's top rows, which
# no face uses by height: a lit heel, the domed rivet where the neck passes through, a
# polish streak, and the line where the edge is ground.
BLADE_FACE = [
    "5666666665",
    "4555225554",
    "4552662544",
    "4555225544",
    "4444554444",
    "3444445543",
    "2221222212",
]


def paint_theme_lanes(img) -> None:
    px = img.load()
    for lane in ("ear", "ear_b"):                       # plump kernels: crowns and creases
        c0, w = LANES[lane]
        for r in range(ATLAS):
            tones = (WHEAT[5], WHEAT[4], WHEAT[2]) if r % 2 == 0 else (WHEAT[4], WHEAT[3], WHEAT[1])
            for i in range(w):
                px[c0 + i, r] = rgba(tones[i])
    c0, w = LANES["straw"]                              # straw with a knotted node now and then
    for r in range(ATLAS):
        tones = (STRAW[3], STRAW[2], STRAW[0]) if r % 5 == 4 else (STRAW[5], STRAW[4], STRAW[2])
        for i in range(w):
            px[c0 + i, r] = rgba(tones[i])
    c0, w = LANES["twine"]                              # wound jute: plump turns with dark grooves
    turns = ((TWINE[5], TWINE[4], TWINE[3]), (TWINE[4], TWINE[3], TWINE[2]), (TWINE[1], TWINE[1], TWINE[0]))
    for r in range(ATLAS):
        for i in range(w):
            px[c0 + i, r] = rgba(turns[r % 3][i])
    pal = {str(i): GOLD[i] for i in range(7)}
    pal.update({"e": "#fffbe8", ".": GOLD[3]})
    c0, _ = LANES["panel"]
    r0 = row_of(PANEL_TOP)
    for j, line in enumerate(PANEL):
        for i, ch in enumerate(line):
            px[c0 + i, r0 + j] = rgba(pal[ch])
    for j, line in enumerate(BLADE_FACE):
        for i, ch in enumerate(line):
            px[c0 + i, j] = rgba(pal[ch])


def theme_atlas_frame(img, t: float) -> None:
    """The gem's flare warms the sheaf, the twine and the straw round it, and the wheat
    glints: the two ear lanes catch the light in turn, twice a loop."""
    px = img.load()
    p = gem_pulse(t)
    if p > 0.03:
        for lane in ("ear", "ear_b", "straw", "twine"):
            c0, w = LANES[lane]
            for r in range(ATLAS):
                y = Y_TOP - (r + 0.5) / RPU
                k = 0.4 * p * max(0.0, 1 - abs(y - BOW_Y) / 4.5)
                if k <= 0.01:
                    continue
                for i in range(w):
                    cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
                    px[c0 + i, r] = rgba(mix(cur, "#ffe39a", k))
    for lane, phase in (("ear", 0.0), ("ear_b", 0.25)):
        g = max(0.0, math.cos(2 * math.pi * (2 * t - phase))) ** 10
        if g < 0.02:
            continue
        c0, w = LANES[lane]
        for r in range(ATLAS):
            for i in range(w):
                cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
                px[c0 + i, r] = rgba(mix(cur, WHEAT[6], g * (0.65 if i == 0 else 0.25)))


def paint_atlas_base():
    img = canvas(ATLAS, fill=METAL[3])
    paint_skeleton_lanes(img, METAL)
    paint_theme_lanes(img)
    return img


def gem_frame(t: float):
    """The pumpkin gem's face: a cushion cut in three swelling lobes like a pumpkin, each
    lobe lit on its left, a white glint up-left and a dimple under the stem; it brightens
    and throws a star at its flare."""
    p = gem_pulse(t)
    lift = 0.2 + 1.5 * p
    img = canvas(16)
    px = img.load()
    for ty in range(16):
        for tx in range(16):
            dx, dy = tx + 0.5 - 8, ty + 0.5 - 8
            ax, ay = abs(dx), abs(dy)
            o = max(ax, ay, (ax + ay) / math.sqrt(2))
            if o > 8:
                continue
            light = -0.45 * dx / 7 - 0.6 * dy / 7
            if o > 7.0:
                f = 1.1 + 0.8 * light + 0.5 * lift                    # the girdle
            else:
                s = math.sqrt(max(0.2, 1 - (dy / 7.8) ** 2))
                lobe = (clamp(dx / (7.4 * s), -0.999, 0.999) + 1) * 1.5
                k = lobe - math.floor(lobe)
                if k < 0.1 or k > 0.93:
                    f = 1.9 + 0.8 * light + 0.6 * lift                # the grooves between lobes
                else:
                    f = 2.6 + 1.3 * math.sin(math.pi * k) + 0.8 * (0.5 - k) + 1.2 * light + lift
            px[tx, ty] = rgba(GEM[int(clamp(round(f), 0, len(GEM) - 1))])
    for x, y in ((4, 4), (5, 4), (4, 5), (9, 3)):
        px[x, y] = rgba(GEM[7])
    for x, y in ((7, 1), (8, 1), (7, 2)):
        px[x, y] = rgba(GEM[1])
    sparkle(img, 5, 5, clamp(p * 1.3 - 0.25), colour="#ffffff", reach=4)
    return img


# The window: harvest dusk over a wheat field.
HORIZON = 23                     # the row where the field meets the sky
FIELD_EARS = ((3, 22, 0.05), (6, 20, None), (9, 22, 0.55), (23, 22, None), (26, 20, 0.3), (29, 22, 0.8))
EAR_HEAD = ((4, 3), (3, 2), (3, 2), (2, 1))           # EARLIT tones of a swaying head, top down
MOTES = ((3, 7, 0.0), (11, 4, 0.4), (19, 9, 0.75), (26, 5, 0.2), (30, 12, 0.55))


def sway(x: float, t: float, amp: float) -> float:
    """The wind: a wave rolling left to right through the field, twice a loop."""
    return amp * math.sin(2 * math.pi * (2 * t - x / 24))


def window_frame(t: float):
    """Harvest dusk in the bow: an indigo sky warming to a sunset glow at the horizon, the
    gem's halo breathing, a field of wheat swaying in the wind below with its ears glinting,
    and golden chaff drifting on the breeze."""
    p = gem_pulse(t)
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 16, (y + 0.5 - 16) / 16
            r = math.hypot(dx, dy)
            f = (len(SKY) - 1) * clamp(y / HORIZON) ** 1.5
            if r > 0.78:
                f -= (r - 0.78) * 9
            c = SKY[int(clamp(dither_band(f, x, y), 0, len(SKY) - 1))]
            halo = (0.3 + 0.7 * p) * max(0.0, 1 - r / 0.62) ** 1.6
            if halo > 0.05:
                c = mix(c, GEM[5], min(0.85, halo))
            px[x, y] = rgba(c)
    for x in range(32):                                   # the field, backlit and dark
        edge = HORIZON + (1 if (x * 5) % 7 < 3 else 0)
        for y in range(edge, 32):
            k = (y - edge) / (31 - edge)
            px[x, y] = rgba(FIELD[int(clamp(dither_band(2.6 - 3.2 * k, x, y), 0, 3))])
        crest = round(sway(x, t, 1.0))                    # a rim of light rippling along the crest
        px[x, edge] = rgba(EARLIT[2] if (x - crest) % 4 == 0 else EARLIT[1])
    for x, top, glint in FIELD_EARS:                      # tall ears nodding in the wind
        s = sway(x, t, 1.6)
        hx = round(x + s)
        for yy in range(top + 4, 32):                     # the stalk bends more toward the head
            k = (yy - (top + 4)) / max(1, 31 - top - 4)
            px[round(x + s * (1 - k) ** 2) % 32, yy] = rgba(FIELD[3] if yy > top + 6 else EARLIT[0])
        for j, (a, b) in enumerate(EAR_HEAD):
            px[hx % 32, top + j] = rgba(EARLIT[a])
            px[(hx + 1) % 32, top + j] = rgba(EARLIT[b])
        px[(hx + (1 if s > 0.3 else 0)) % 32, top - 1] = rgba(EARLIT[2])   # the awn, blown ahead
        if glint is not None:
            g = max(0.0, math.cos(2 * math.pi * (t - glint))) ** 14
            sparkle(img, hx % 32, top, g, colour="#fff6d8", reach=2)
    for x0, y0, ph in MOTES:                              # chaff drifting on the breeze
        fx = (x0 + 32 * t) % 32
        fy = y0 + 1.4 * math.sin(2 * math.pi * (t + ph))
        a = 0.3 + 0.6 * (0.5 - 0.5 * math.cos(2 * math.pi * (2 * t + ph)))
        xx, yy = int(fx) % 32, int(round(fy))
        if 0 <= yy < 32:
            cur = "#%02x%02x%02x" % px[xx, yy][:3]
            px[xx, yy] = rgba(mix(cur, WHEAT[5], a))
    return img


def paint_stem():
    """The pumpkin stem: ridged green, lit down its left side."""
    img = canvas(16)
    px = img.load()
    for y in range(16):
        for x in range(16):
            ridge = (0, 1, 1, 2, 3, 3, 2, 2, 3, 3, 2, 1, 2, 2, 1, 0)[x]
            px[x, y] = rgba(LEAF[min(4, ridge + (1 if x < 5 and y < 8 else 0))])
    return img


def textures() -> None:
    base = paint_atlas_base()
    save_animation(animate(lambda t: atlas_frame(base, t), FRAMES), "metal", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(window_frame, FRAMES), "window", frametime=FRAMETIME)
    save(paint_stem(), "stem")


# ======================================================================================
# 5. MODELS
# ======================================================================================

def models() -> dict:
    parts = skeleton(CLAWS) + theme()
    return {"main": model(parts, transforms(parts))}
