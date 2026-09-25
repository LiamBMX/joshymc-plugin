"""January Key: the frost-silver crate key of the new year (one of the 19 JoshyMC crate keys).

The bow is a stepped ring of frost silver (a recessed outer rim, a polished band set with
six frost studs, a raised inner lip) framing a little snow globe: a recessed window of
winter sky where snow drifts down, a glowing ice snowflake floating in it, and an octagonal
ice gem clasped by six claws at its heart. Snow lies along the ring's shoulders and icicles
hang from its foot. A flared collar joins the ring to an octagonal shaft, rimed white where
it leaves the bow, girdled halfway up by a ring of ice bristling with thorns and banded in
bright silver dripping icicles; a cluster of ice crystals crowns it, and on its -X side the
bit is a silver block with an inlaid ice snowflake, heaped with snow and hung with three
icicle teeth.

Animation (one 3.2 s loop): a glossy glint sweeps up the whole key from the bow to the
tip, the ice gem flares as the glint passes through it and then slowly dims, and snow
drifts down through the window while the gem's halo breathes in it.

---------------------------------------------------------------------------------------
KEY SET SKELETON: all 19 crate keys share these proportions (model units)
---------------------------------------------------------------------------------------
  frame   upright on x = 8, z = 8; front = +Z (the face the inventory shows)
  bow     a stepped ring centred at (8, BOW_Y = 3.5): outer radius 5.4 (10.8 across,
          y -1.9..8.9), inner radius 3.5; a recessed outer rim (0.5 wide, 2.0 deep), the
          main band (2.6 deep, z 6.7..9.3) and a raised inner lip (0.6 wide, 3.0 deep),
          20 segments each. Inside: a recessed window (the theme's animated scene), the
          theme's emblem, and at the heart a socket (bezel + six claws) holding the
          theme's gem, which stands proudest of everything
  collar  two octagonal discs, y 8.4..10.2, joining the ring's top to the shaft
  shaft   octagonal, apothem 1.15 (2.3 thick), y 10..25.5, bands at y 13 and 17.6,
          a cap at 25.4..26.3; the theme may crown it with a finial (up to y ~29.5)
  bit     on the -X side of the upper shaft, y 18..25.2: a plate (bit_plate, painted by
          the "panel" lane) plus the theme's teeth
  grip    (8, 10.6, 8): the fist closes on the shaft just above the bow
  size    SIZE = 0.64 holds it at about 1 block; carried upright like a sceptre
  gui     GUI_ROTATION tilts the diagonal key back a little so its faces catch the light

Animation approach (every loop is 64 ticks, so all the pieces stay in step)
  metal   ONE 64 px atlas textures every metal, frost, ice and snow face. Its ROWS map to
          the key's height (row = 2 * (29 - y)); its COLUMNS are LANES: narrow bands
          painted with one material's cross-section (a bevel lit on the side facing the
          light, a facet, an icicle...). Every face samples its lane at the rows of its own
          height (lane_uv / lane_faces), so ONE horizontal shine() band sweeping up the atlas
          glints along the whole key in one piece, bow to tip, even across rotated parts.
          Columns 0-41 are the skeleton's metal lanes (painted from METAL), 42-63 the
          theme's; a lane may also hold a small painting at the rows of the one face that
          uses it (the bit's panel, the frost on the shaft). 32 frames x 2 ticks.
  gem     the heart gem, interpolated (16 frames x 4 ticks): it flares as the glint
          crosses the bow (sweep_time(BOW_Y)) and slowly dims
  window  the theme's scene inside the bow (32 frames x 2 ticks), here snow drifting, with
          the gem's halo breathing in step with the gem

To make another key: keep section 1 (skeleton + atlas machinery) as it is, set METAL and the
palette in section 2, then replace the theme parts and painters in sections 3 and 4.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, mix, model, place, prism, rgba, save_animation, shine,
                     sparkle, turn)

ID = "january_key"
NAME = "January Key"
KIND = "sword"
COUNTERPART = "item/trial_key"

# ======================================================================================
# 1. SHARED KEY SKELETON (identical for every key in the set)
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
    # theme (42-63)
    "emblem": (42, 4), "ice": (46, 4), "snow": (50, 4), "panel": (54, 10),
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#f2fcff", width=8.0, strength=0.88, angle=-90.0, pause=0.4)   # bow -> tip
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


def skeleton() -> list[dict]:
    return bow_ring() + bow_window() + gem_socket(CLAW_ANGLES) + collar() + shaft()


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
# 2. THEME PALETTE: January, frost silver and ice blue (#A6E3FF)
# ======================================================================================
METAL = SILVER = ["#1d2346", "#353f73", "#56659b", "#8190bf", "#abbadc", "#d4e0f4", "#f5faff"]
ICE = ["#123a78", "#1c5ba8", "#2d84d0", "#56aeea", "#86cffa", "#a6e3ff", "#effbff"]
GEM = ["#0a2466", "#113f9e", "#1a6fd6", "#2ea3f5", "#63cfff", "#a6e3ff", "#e8fbff", "#ffffff"]
SKY = ["#12246a", "#18348a", "#2048a6", "#2a5ec0", "#3a78d4", "#5192e2"]
SNOW = ["#6f86c0", "#9fb3e0", "#cfdcf5", "#eef4ff", "#ffffff"]

# ======================================================================================
# 3. THEME PARTS: ice snowflake, ice gem, icicle bit, snow and frost, crystal finial
# ======================================================================================
EMBLEM_ANGLES = CLAW_ANGLES                           # the snowflake's arms grow from the claws
STUD_ANGLES = tuple(120 + 60 * k for k in range(6))   # frost studs between the arms
PLATE_Y0, PLATE_Y1 = 21.0, 25.0                       # the bit plate (icicles hang below it)
ICICLES = ((4.5, 3.1, 1.05), (5.6, 1.8, 0.8), (6.55, 2.6, 0.9))    # (x, length, top width)
RING_ICICLES = ((252, 1.4, 0.75), (270, 1.9, 0.9), (288, 1.4, 0.75))  # (angle, length, width)
ICE_RING = (14.8, 15.5)                                               # ice frozen round the shaft
BAND_ICICLES = ((9.2, CAP[0] + 0.12, 1.6, 0.62), (9.25, BANDS[1][0] + 0.12, 1.1, 0.55))  # off the +X side


def snowflake_emblem() -> list[dict]:
    """A stellar dendrite of glowing ice floating in the window: six arms with two pairs
    of side branches each, ending in points just short of the lip."""
    parts = []
    c = (AX, BOW_Y)
    for ang in EMBLEM_ANGLES:
        parts += spike(c, ang, 1.2, 3.3, 0.72, 1.5, "emblem", glow=9)
        a = math.radians(ang)
        for r, length, w in ((1.95, 0.85, 0.46), (2.6, 0.6, 0.4)):
            p = (AX + r * math.cos(a), BOW_Y + r * math.sin(a))
            for off in (-56, 56):
                parts.append(radial(p, ang + off, 0.0, length, w, 1.3, "emblem", glow=9))
    return parts


def ice_gem() -> list[dict]:
    """A glowing octagonal ice gem: a body plus a painted front and back."""
    side = {s: [5, 7, 6, 8] for s in ("north", "south", "east", "west")}
    side["up"] = side["down"] = [0.2, 7.5, 0.6, 8.0]
    parts = prism((AX, BOW_Y, AZ), GEM_R, 2.3, "gem", axis="z", cap="gem", uv=side, glow=14)
    r = GEM_R + 0.05
    parts.append(box((AX - r, BOW_Y - r, 9.15), (AX + r, BOW_Y + r, 9.5), "gem", uv="full",
                     skip=("north", "east", "west", "up", "down"), glow=14))
    parts.append(box((AX - r, BOW_Y - r, 6.5), (AX + r, BOW_Y + r, 6.85), "gem",
                     faces={"north": ("gem", [16, 0, 0, 16])},
                     skip=("south", "east", "west", "up", "down"), glow=14))
    return parts


def icicle(x: float, y_top: float, length: float, w: float, d: float = 1.0) -> list[dict]:
    """A stepped icicle hanging from y_top: two tapering blocks and a pointed tip."""
    y1 = y_top - length * 0.45
    y2 = y_top - length * 0.8
    w2 = w * 0.68
    parts = [box((x - w / 2, y1, AZ - d / 2), (x + w / 2, y_top, AZ + d / 2), "metal",
                 faces=lane_faces("ice", y_top, y1)),
             box((x - w2 / 2, y2, AZ - d / 2 + 0.1), (x + w2 / 2, y1, AZ + d / 2 - 0.1), "metal",
                 faces=lane_faces("ice", y1, y2))]
    parts.append(diamond((x, y_top - length + w2 / 2), w2 / math.sqrt(2), d - 0.3, "ice"))
    return parts


def ring_frost() -> list[dict]:
    """Snow lying along the ring's shoulders, frost studs round the band and icicles at
    its foot."""
    c = (AX, BOW_Y)
    parts = []
    for a0, a1 in ((26, 44), (44, 62), (62, 76), (104, 118), (118, 136), (136, 154)):
        mid = math.radians((a0 + a1) / 2)
        y = BOW_Y + (BOW_OUT + 0.2) * math.sin(mid)
        parts.append(arc_segment(c, BOW_OUT + 0.12, a0, a1, 0.42, BOW_D + 0.2, "snow", "snow", "snow",
                                 flip=True, sides={"east": dot_uv("snow", y, 0.0)}))
    for ang in STUD_ANGLES:
        a = math.radians(ang)
        parts.append(diamond((AX + BAND_R * math.cos(a), BOW_Y + BAND_R * math.sin(a)), 0.52, BOW_D + 0.24,
                             "emblem", ang - 45, glow=4))
    for ang, length, w in RING_ICICLES:
        a = math.radians(ang)
        parts += icicle(AX + (BOW_OUT - 0.3) * math.cos(a), BOW_Y + (BOW_OUT - 0.3) * math.sin(a) + 0.35,
                        length + 0.35, w, 1.1)
    return parts


def snow_slab(x0: float, x1: float, y0: float, y1: float, d: float) -> dict:
    """A horizontal layer of snow: its front shows the snow lane across its height (white
    on top), its top is white."""
    top = dot_uv("snow", (y0 + y1) / 2, 0.0)
    return radial((x1, (y0 + y1) / 2), 180, 0.0, x1 - x0, y1 - y0, d, "snow", flip=True,
                  sides={"east": top, "up": top, "down": top})


def icicle_bit() -> list[dict]:
    """The bit: a silver block with an inlaid ice snowflake panel, heaped with snow and
    hung with three icicle teeth."""
    parts = bit_plate(BIT_X0, PLATE_Y0, PLATE_Y1, 2.2)
    x0, x1, y0, y1 = 4.5, 6.75, 21.5, 24.5
    parts.append(box((x0, y0, AZ - 1.25), (x1, y1, AZ + 1.25), "metal",
                     faces={"south": ("metal", panel_uv(x0, x1, y1, y0)),
                            "north": ("metal", panel_uv(x0, x1, y1, y0, True)),
                            "east": ("metal", lane_uv("step", y1, y0)), "west": ("metal", lane_uv("step", y1, y0)),
                            "up": ("metal", dot_uv("step", y1)), "down": ("metal", dot_uv("step", y0))}))
    for x, length, w in ICICLES:
        parts += icicle(x, PLATE_Y0 + 0.1, length, w, 1.2)
    # snow heaped on the block: a layer proud of it all round, a drift on top, and two
    # lumps sliding over the front and back edges
    parts.append(snow_slab(BIT_X0 - 0.25, BIT_X1 + 0.1, PLATE_Y1 - 0.1, PLATE_Y1 + 0.5, 2.6))
    parts.append(snow_slab(4.3, 6.9, PLATE_Y1 + 0.45, PLATE_Y1 + 0.85, 2.0))
    parts.append(snow_slab(4.9, 6.2, PLATE_Y1 + 0.8, PLATE_Y1 + 1.05, 1.3))
    for z in (AZ + 1.3, AZ - 1.3):
        parts.append(box((4.6, PLATE_Y1 - 0.45, z - 0.18), (5.25, PLATE_Y1, z + 0.18), "metal",
                         faces=lane_faces("snow", PLATE_Y1, PLATE_Y1 - 0.45)))
    return parts


def frost() -> list[dict]:
    """A ring of ice frozen round the middle of the shaft, bristling with thorns in the
    front plane and toward the viewer."""
    parts = lane_prism(ICE_RING[0], ICE_RING[1], 1.4, "ice", cap="ice")
    y = (ICE_RING[0] + ICE_RING[1]) / 2
    for dy, ang, reach in ((0.2, 38, 1.55), (0.1, 144, 1.3), (-0.1, 328, 0.75), (-0.05, 208, 0.7)):
        parts += spike((AX, y + dy), ang, 1.0, 1.4 + reach, 0.55, 0.55, "ice")
    for yaw, reach in ((70, 1.2), (250, 1.0)):                 # thorns leaning out of the front/back
        p0 = (AX, y, AZ)
        a = math.radians(yaw)
        p1 = (AX + math.cos(a) * 0.5, y + 1.0, AZ + math.sin(a) * (1.4 + reach))
        parts.append(bar(p0, p1, 0.5, 0.5, "metal", faces=lane_faces("ice", p1[1], p0[1])))
    for x, y_top, length, w in BAND_ICICLES:                  # icicles hanging off the cap and band
        parts += icicle(x, y_top, length, w, 0.8)
    return parts


def ice_finial() -> list[dict]:
    """A cluster of ice crystals crowning the shaft: a slim central point, pointed from
    the front and the side, flanked by two splayed shards."""
    top = 28.35
    parts = lane_prism(26.2, top, 0.62, "ice")
    parts.append(diamond((AX, top), 0.877, 1.1, "ice"))
    e = box((AX - 0.55, top - 0.44, AZ - 0.44), (AX + 0.55, top + 0.44, AZ + 0.44), "metal",
            faces=lane_faces("ice", top + 0.3, top - 0.3))
    parts.append(turn(e, 45, "x", (AX, top, AZ)))
    for ang in (126, 54):
        parts += spike((AX, 26.35), ang, 0.3, 2.1, 0.52, 0.6, "ice")
    return parts


def theme() -> list[dict]:
    return snowflake_emblem() + ice_gem() + ring_frost() + icicle_bit() + frost() + ice_finial()


# ======================================================================================
# 4. TEXTURES
# ======================================================================================
PANEL = [                      # the bit plate's front: 10 x 8 texels, x 4.0..7.2, y 25..21
    "6666666665",
    "65aabwbcc2",
    "65awbwbwc2",
    "65abwWwcc2",
    "65bwbwbwc2",
    "65bbcwccd2",
    "65cccdddd2",
    "4322222221",
]
FROST_ROWS = (10.2, 12.4)      # the shaft is rimed white where it leaves the bow
RIME_ROWS = (13.9, 16.6)       # and frosted round the ice ring


def paint_theme_lanes(img) -> None:
    paint_lane(img, "emblem", ["#ffffff", ICE[6], ICE[5], ICE[4]])
    paint_lane(img, "ice", [ICE[6], ICE[5], ICE[4], ICE[2]])
    paint_lane(img, "snow", [SNOW[4], SNOW[3], SNOW[2], SNOW[1]])
    px = img.load()
    # the bit panel: a silver frame round an ice inlay engraved with a white snowflake
    pal = {"a": ICE[5], "b": ICE[4], "c": ICE[3], "d": ICE[2], "w": "#ffffff", "W": ICE[6]}
    pal.update({str(i): SILVER[i] for i in range(7)})
    c0, _ = LANES["panel"]
    r0 = row_of(PLATE_Y1)
    for j, line in enumerate(PANEL):
        for i, ch in enumerate(line):
            px[c0 + i, r0 + j] = rgba(pal[ch])
    # rime on the shaft: white at the collar, breaking up into frost as it climbs, and a
    # sprinkle of frost round the thorns
    c0, w = LANES["shaft"]
    rng = random.Random(5)
    lo, hi = row_of(FROST_ROWS[1]), row_of(FROST_ROWS[0])
    for r in range(lo, hi + 1):
        k = (r - lo) / max(1, hi - lo)          # 0 at the top of the frost, 1 at the collar
        for i in range(w):
            if rng.random() < 0.25 + 0.75 * k:
                px[c0 + i, r] = rgba(SNOW[4] if (i == 0 or rng.random() < 0.4) else SNOW[2])
    for r in range(row_of(RIME_ROWS[1]), row_of(RIME_ROWS[0]) + 1):
        for i in range(w):
            if rng.random() < 0.22:
                px[c0 + i, r] = rgba(SNOW[3] if rng.random() < 0.5 else ICE[5])


def theme_atlas_frame(img, t: float) -> None:
    """The gem's flare floods the snowflake and the frost studs with cold light: the
    emblem lane brightens round the bow's centre as the gem flares."""
    p = gem_pulse(t)
    if p < 0.03:
        return
    px = img.load()
    c0, w = LANES["emblem"]
    for r in range(ATLAS):
        y = Y_TOP - (r + 0.5) / RPU
        k = 0.55 * p * max(0.0, 1 - abs(y - BOW_Y) / 5.5)
        if k <= 0.01:
            continue
        for i in range(w):
            cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
            px[c0 + i, r] = rgba(mix(cur, "#e8fbff", k))


def paint_atlas_base():
    img = canvas(ATLAS, fill=METAL[3])
    paint_skeleton_lanes(img, METAL)
    paint_theme_lanes(img)
    return img


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


def gem_frame(t: float):
    """The ice gem's face: an octagon with a table facet, crown facets lit from the left,
    a white highlight, brightening and throwing a star at its flare."""
    p = gem_pulse(t)
    lift = 0.2 + 1.5 * p
    img = canvas(16)
    px = img.load()
    lx, ly = -0.93, -0.36
    for ty in range(16):
        for tx in range(16):
            dx, dy = tx + 0.5 - 8, ty + 0.5 - 8
            ax, ay = abs(dx), abs(dy)
            o = max(ax, ay, (ax + ay) / math.sqrt(2))       # octagon "radius"
            if o > 8:
                continue
            if o > 7.0:
                f = 0.4
            elif o <= 3.6:                                     # the table: a flat facet, split
                f = 3.9 if dx * lx + dy * ly > 0.4 else 3.3
            else:                                              # crown facets, by direction
                ang = math.atan2(dy, dx)
                sector = round(ang / (math.pi / 4)) * (math.pi / 4)
                f = 2.9 + 1.7 * (math.cos(sector) * lx + math.sin(sector) * ly)
            px[tx, ty] = rgba(ramp_at(GEM, f + lift * (1.0 if o <= 7.0 else 0.6)))
    for x, y in ((5, 5), (6, 5), (5, 6)):
        px[x, y] = rgba(GEM[7])
    px[10, 10] = rgba(ramp_at(GEM, 5 + lift))
    sparkle(img, 6, 6, clamp(p * 1.3 - 0.25), colour="#ffffff", reach=4)
    return img


# Snowflakes (window texels): x, starting y, speed (window heights per loop), sway
# amplitude, sway cycles per loop, sway phase, big. Placed by hand so they never clump.
FLAKES = [dict(x=x, y=y, speed=s, amp=a, freq=f, phase=ph, big=b) for x, y, s, a, f, ph, b in (
    (6, 3, 1, 1.2, 1, 0.0, False), (11, 20, 2, 1.6, 1, 0.3, True), (15, 9, 1, 1.0, 2, 0.6, False),
    (20, 26, 1, 1.4, 1, 0.15, False), (24, 14, 2, 1.8, 1, 0.8, True), (27, 5, 1, 1.1, 2, 0.45, False),
    (9, 12, 1, 1.3, 1, 0.9, False))]
SPECKS = ((3, 10, 0.2), (8, 27, 0.7), (13, 2, 0.45), (18, 18, 0.1), (22, 7, 0.6), (26, 22, 0.35),
          (30, 15, 0.85), (16, 30, 0.5))
FLAKE_SMALL = [".a.", "aWa", ".a."]
FLAKE_BIG = [".aa.", "aWWa", "aWWa", ".aa."]


def window_frame(t: float):
    """Winter sky in the bow: deep blue above, brighter below, darker at the rim, the gem's
    halo breathing, and snowflakes drifting down and swaying (each falls one or two whole
    window heights per loop, so the loop is seamless)."""
    p = gem_pulse(t)
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 16, (y + 0.5 - 16) / 16
            r = math.hypot(dx, dy)
            f = 0.5 + 4.5 * (y / 31)
            if r > 0.78:
                f -= (r - 0.78) * 9
            c = SKY[int(clamp(dither_band(f, x, y), 0, len(SKY) - 1))]
            halo = (0.35 + 0.65 * p) * max(0.0, 1 - r / 0.62) ** 1.6
            if halo > 0.05:
                c = mix(c, GEM[4], min(0.85, halo))
            px[x, y] = rgba(c)
    for sx, sy, ph in SPECKS:                          # far-off snow, drifting slowly
        fy = int((sy + 32 * t) % 32)
        fx = int(round(sx + 0.8 * math.sin(2 * math.pi * (t + ph)))) % 32
        cur = "#%02x%02x%02x" % px[fx, fy][:3]
        px[fx, fy] = rgba(mix(cur, SNOW[3], 0.55))
    for fl in FLAKES:
        fy = (fl["y"] + fl["speed"] * 32 * t) % 32
        fx = fl["x"] + fl["amp"] * math.sin(2 * math.pi * (fl["freq"] * t + fl["phase"]))
        for oy in (0, -32):
            draw_flake(img, round(fx), round(fy) + oy, fl["big"])
    return img


def draw_flake(img, x: int, y: int, big: bool) -> None:
    px = img.load()
    stamp = FLAKE_BIG if big else FLAKE_SMALL
    o = len(stamp) // 2
    for j, line in enumerate(stamp):
        for i, ch in enumerate(line):
            xx, yy = x + i - o, y + j - o
            if ch == "." or not (0 <= xx < 32 and 0 <= yy < 32):
                continue
            cur = "#%02x%02x%02x" % px[xx, yy][:3]
            px[xx, yy] = rgba("#ffffff" if ch == "W" else mix(cur, SNOW[3], 0.7))


def textures() -> None:
    base = paint_atlas_base()
    save_animation(animate(lambda t: atlas_frame(base, t), FRAMES), "metal", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(window_frame, FRAMES), "window", frametime=FRAMETIME)


# ======================================================================================
# 5. MODELS
# ======================================================================================

def models() -> dict:
    parts = skeleton() + theme()
    return {"main": model(parts, transforms(parts))}
