"""September Key: the burnished-bronze crate key of the harvest (one of the 19 JoshyMC crate keys).

The bow is the set's stepped ring, cast in burnished bronze and set with six amber beads.
It frames a little window of dusk sky where autumn leaves tumble down past a far ridge, and
over the window lies an enamelled maple leaf with eleven points, orange at its heart and
turning crimson at its tips, rimmed in gilt so its points break over the frame, veined with
gold wire, with an amber gem clasped by six claws at its heart. Enamelled maple leaves have
fallen on the ring's shoulders and sides, and a pair of maple keys (samaras) hangs from its
foot like a charm. A flared collar joins the ring to the octagonal shaft; halfway up, a
girdle of red enamel between gilt beads holds a sprig of two leaves. On the -X side the bit
is a bronze plate inlaid with a gilt maple leaf on red enamel, with three acorns of
different sizes hanging from it as its teeth, and a big acorn between two oak leaves crowns
the key.

Animation (one 3.2 s loop): a warm glint sweeps up the whole key from the bow to the tip,
the amber flares as the glint passes through it and then slowly dims, its warm light
spreading through the maple leaf from the heart to the points, leaves drift, sway and flip
over as they fall through the window while the amber's halo breathes behind them, and the
leaves on the ring and shaft stir in the wind while the maple keys swing.

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
  metal   ONE 64 px atlas textures every metal face (and the acorns and the enamel). Its
          ROWS map to the key's height (row = 2 * (29 - y)); its COLUMNS are LANES: narrow
          bands painted with one material's cross-section (a bevel lit on the side facing
          the light, a facet, a gold wire...). Every face samples its lane at the rows of its
          own height (lane_uv / lane_faces), so ONE horizontal shine() band sweeping up the
          atlas glints along the whole key in one piece, bow to tip, even across rotated
          parts. Columns 0-41 are the skeleton's metal lanes (painted from METAL), 42-63 the
          theme's; a lane may also hold a small painting at the rows of the one face that
          uses it (the bit's panel). 32 frames x 2 ticks.
  gem     the heart gem, interpolated (16 frames x 4 ticks): it flares as the glint
          crosses the bow (sweep_time(BOW_Y)) and slowly dims
  window  the theme's scene inside the bow (32 frames x 2 ticks), here leaves falling at
          dusk, with the gem's halo breathing in step with the gem
  leaf    (this theme) the painted enamel: the maple-leaf emblem and the leaf sprites on
          cutout plates, 32 frames x 2 ticks. glint() replays the atlas sweep on them at
          their own heights, so they glint in step with the metal, and the amber's flare
          spreads through the emblem from its heart to its points; the sprites also sway
          (flutter) as if stirred by the wind
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, box, canvas, display, mix, model, place, prism, rgba, save_animation, shine,
                     sparkle, turn)

ID = "september_key"
NAME = "September Key"
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
BIT_X0 = 3.5                                        # the bit plate's outer edge (moved out for 3 acorns)
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
    # theme (42-63): gold wire (veins and gilt beads), glossy acorn shells, scaly acorn cups,
    # red enamel, the bit panel
    "vein": (42, 2), "nut": (44, 3), "husk": (47, 3), "enamel": (50, 3), "panel": (53, 11),
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#fff3d2", width=8.0, strength=0.88, angle=-90.0, pause=0.4)   # bow -> tip
TRAIL = dict(colour="#fffaf0", width=2.5, strength=0.7, angle=-90.0, pause=0.4)
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
# 2. THEME PALETTE: September, burnished bronze and harvest orange (#D2691E)
# ======================================================================================
METAL = BRONZE = ["#2a0f0c", "#4f1f14", "#7e3a1d", "#a95726", "#cc7a33", "#e8a452", "#fdd89a"]
AMBER = ["#3b1005", "#6e2407", "#a63f0c", "#d2691e", "#f29a2b", "#ffc550", "#ffe8a3", "#fffbea"]
GOLD = ["#6e4118", "#a36a28", "#d4953c", "#f0bb58", "#ffdf88", "#fff5cc"]       # gilt, dark -> light
CRIMSON = ["#4d0d10", "#7e1715", "#b02a1b", "#d8472a", "#f47a45"]
NUT = ["#2a1008", "#4f200f", "#7c3717", "#a85524", "#d9884a", "#ffd09a"]     # glossy chestnut shells
SKY = ["#150c29", "#211236", "#321741", "#481c45", "#652544", "#873341", "#ab4a3a"]  # dusk, top -> horizon
RIDGE = ["#12081a", "#1f0d22", "#2e132a"]                              # the far ridge
FALL = [  # tumbling leaves in the window: (blade, light side, dark side)
    ("#f0ae2e", "#ffe387", "#b8681c"),     # gold
    ("#e66f28", "#ffa45a", "#a8421a"),     # orange
    ("#c7301e", "#f26843", "#7a1714"),     # red
    ("#9a4b1f", "#d0803e", "#5e2a12"),     # russet
]
# Enamels, heart -> points: (ramp, vein, lit edge, mid edge, dark edge, outline)
ENAMELS = {
    "emblem": (["#ffa936", "#f47a2a", "#e25324", "#cc3520", "#b0241c", "#8e1818"],
               "#ffd070", "#fff0b0", "#f0bb58", "#a36a28", "#2a0806"),
    "red": (["#f7a33a", "#ec7a2c", "#dc5428", "#c63a22", "#a8281c", "#84191a"],
            "#ffc860", "#ffb070", "#9c2a1c", "#5a1010", "#2e0c0a"),
    "orange": (["#ffd466", "#fbb542", "#f39632", "#e8782c", "#d65a26", "#b8441f"],
               "#fff0a0", "#ffe7a0", "#c0561c", "#7a2c12", "#3a160a"),
    "gilt": ([GOLD[5], GOLD[4], GOLD[3], GOLD[2], GOLD[1]],
             GOLD[5], "#fffbe8", GOLD[1], GOLD[0], "#3a200c"),
    "russet": (["#e0943e", "#c87634", "#ac5a2a", "#8e4322", "#70301b"],
               "#f2b860", "#f0b068", "#6a2c16", "#44190e", "#260e08"),
}
SAMARA_WING = ["#b8502c", "#c9763c", "#dca060", "#ecc486", "#f8e0b0"]       # nutlet end -> wing tip
SAMARA_NUT = ["#3e160c", "#6e2a16", "#9c4424", "#d07a48"]

# ======================================================================================
# 3. THEME PARTS: enamel maple-leaf emblem, amber gem and beads, maple-key charm, acorn bit,
#    enamel girdle, acorn finial
# ======================================================================================
EMBLEM_GLOW = 10
EMBLEM_R = 4.25                    # the maple leaf's tip radius: its points break over the lip
EMBLEM_HALF = 4.7                  # half the side of the plate it is painted on
EMBLEM_Z = (6.38, 9.62)            # the plate's back and front faces, just proud of the lip
EMBLEM_PPU = 64 / (2 * EMBLEM_HALF)  # texels per unit on the plate
HOLE_R = 1.72                      # the leaf's heart is cut away round the gem's bezel and claws
# The maple leaf's outline, right half, from its tip clockwise to the stalk, in units of its
# tip radius (y up); the left half mirrors it. Its veins meet under the gem: a tall terminal
# lobe, laterals raised to 31 degrees and small basals, eleven points in all.
MAPLE = ((0.0, 1.0), (0.13, 0.76), (0.3, 0.82), (0.24, 0.52), (0.52, 0.68), (0.56, 0.52),
         (0.92, 0.56), (0.7, 0.34), (0.86, 0.22), (0.5, 0.0), (0.56, -0.08), (0.52, -0.36),
         (0.34, -0.3), (0.3, -0.44), (0.1, -0.38), (0.05, -0.5))
MAPLE_POLY = MAPLE + tuple((-x, y) for x, y in reversed(MAPLE))
MAPLE_TIPS = ((0.0, 1.0), (0.92, 0.56), (-0.92, 0.56), (0.52, -0.36), (-0.52, -0.36))
MAPLE_POINTS = ((0, 0.3, 0.82), (0, -0.3, 0.82), (1, 0.52, 0.68), (1, 0.86, 0.22),
                (2, -0.52, 0.68), (2, -0.86, 0.22), (3, 0.3, -0.44), (4, -0.3, -0.44))  # (lobe, x, y)
STUD_ANGLES = tuple(120 + 60 * k for k in range(6))   # amber beads round the band
# Leaf sprites in the "leaf" texture (16 px cells): cell -> (texel x, texel y, enamel, shape).
# Every sheet showing a cell hangs at the same height, so the cell glints in step with them.
CELLS = {
    "maple_gilt": (0, 0, "gilt", "maple"), "maple_amber": (16, 0, "orange", "maple"),
    "oak_russet": (32, 0, "russet", "oak"), "oak_red": (48, 0, "red", "oak"),
    "maple_red": (0, 16, "red", "maple"), "maple_orange": (16, 16, "orange", "maple"),
    "samara": (32, 16, None, "samara"),
}
# Where each sprite hangs: name -> (cell, centre x, centre y, size, pointing angle, z back,
# z front, mirrored). The ring's leaves lie over the rim just proud of the band.
SHEETS = {
    "samara": ("samara", AX, -3.4, 2.7, 90, 7.7, 8.3, False),          # maple keys from the foot
    "maple_gilt": ("maple_gilt", 5.3, 23.1, 2.2, 90, 6.72, 9.28, False),  # the bit panel's inlay
    "sprig_maple": ("maple_amber", 10.05, 16.3, 2.0, 38, 7.45, 8.55, False),  # sprig in the binding
    "sprig_oak": ("oak_russet", 9.9, 14.15, 1.8, -18, 7.5, 8.5, False),
    "crown_oak_r": ("oak_red", 9.8, 27.45, 2.0, 36, 7.6, 8.4, False),     # beside the acorn finial
    "crown_oak_l": ("oak_red", 6.2, 27.45, 2.0, 144, 7.6, 8.4, True),
    "ring_maple_r": ("maple_red", 12.25, 7.3, 3.0, 42, 6.6, 9.44, False),  # leaves fallen on the ring
    "ring_maple_l": ("maple_red", 3.75, 7.3, 3.0, 138, 6.6, 9.44, True),
    "ring_side_r": ("maple_orange", 13.55, 4.2, 2.6, 8, 6.66, 9.38, False),
    "ring_side_l": ("maple_orange", 2.45, 4.2, 2.6, 172, 6.66, 9.38, True),
}
PLATE_Y0, PLATE_Y1 = 21.2, 25.0    # the bit plate; the acorns hang below it
# Acorn teeth hanging from the plate: (x, tooth length, size)
ACORNS = ((4.2, 3.25, 1.12), (5.5, 2.05, 0.8), (6.55, 2.75, 0.8))
BIND = (14.3, 15.9)                # the enamel girdle round the middle of the shaft


def leaf_sheet(name: str, glow: int = 0) -> dict:
    """A cutout plate showing one leaf sprite on its front and, mirrored, on its back."""
    cell, x, y, size, pointing, z0, z1, mirrored = SHEETS[name]
    ox, oy = CELLS[cell][:2]
    u0, v0 = ox / TPU, oy / TPU
    s = 16 / TPU
    ua, ub = (u0 + s, u0) if mirrored else (u0, u0 + s)
    e = box((x - size / 2, y - size / 2, z0), (x + size / 2, y + size / 2, z1), "leaf",
            faces={"south": ("leaf", [ua, v0, ub, v0 + s]), "north": ("leaf", [ub, v0, ua, v0 + s])},
            skip=("east", "west", "up", "down"), glow=glow)
    return turn(e, pointing - 90, "z", (x, y, (z0 + z1) / 2))


def maple_emblem() -> list[dict]:
    """The enamel maple leaf: a cutout plate painted front and back, laid just proud of the
    lip so its points break over the frame, its heart cut away round the gem's setting."""
    h = EMBLEM_HALF
    return [box((AX - h, BOW_Y - h, EMBLEM_Z[0]), (AX + h, BOW_Y + h, EMBLEM_Z[1]), "emblem",
                faces={"south": ("emblem", [0, 0, 16, 16]), "north": ("emblem", [16, 0, 0, 16])},
                skip=("east", "west", "up", "down"), glow=EMBLEM_GLOW)]


def amber_gem() -> list[dict]:
    """A glowing octagonal amber standing proud through the leaf: a body plus a painted
    front and back."""
    side = {s: [5, 7, 6, 8] for s in ("north", "south", "east", "west")}
    side["up"] = side["down"] = [0.2, 7.5, 0.6, 8.0]
    parts = prism((AX, BOW_Y, AZ), GEM_R, 3.5, "gem", axis="z", cap="gem", uv=side, glow=14)
    r = GEM_R + 0.05
    parts.append(box((AX - r, BOW_Y - r, 9.7), (AX + r, BOW_Y + r, 9.92), "gem", uv="full",
                     skip=("north", "east", "west", "up", "down"), glow=14))
    parts.append(box((AX - r, BOW_Y - r, 6.08), (AX + r, BOW_Y + r, 6.3), "gem",
                     faces={"north": ("gem", [16, 0, 0, 16])},
                     skip=("south", "east", "west", "up", "down"), glow=14))
    return parts


def bead(c, side: float, d: float, glow: int = 5) -> dict:
    """A little amber bead: a turned square showing the whole (pulsing) gem face."""
    cx, cy = c
    e = box((cx - side / 2, cy - side / 2, AZ - d / 2), (cx + side / 2, cy + side / 2, AZ + d / 2), "gem",
            uv="full", glow=glow)
    return turn(e, 45, "z", (cx, cy, AZ))


def ring_dressing() -> list[dict]:
    """Amber beads round the band, enamel maple and oak leaves fallen on the ring's shoulders
    and a pair of maple keys hung from its foot on a little bronze link."""
    parts = []
    for ang in STUD_ANGLES:
        a = math.radians(ang)
        parts.append(bead((AX + BAND_R * math.cos(a), BOW_Y + BAND_R * math.sin(a)), 0.55, BOW_D + 0.24))
    foot = BOW_Y - BOW_OUT
    parts.append(box((AX - 0.16, foot - 0.55, AZ - 0.16), (AX + 0.16, foot + 0.2, AZ + 0.16), "metal",
                     faces=lane_faces("band", foot + 0.2, foot - 0.55)))
    parts.append(diamond((AX, foot - 0.62), 0.42, 0.42, "band"))
    parts.append(leaf_sheet("samara"))
    parts += [leaf_sheet(n) for n in ("ring_side_r", "ring_side_l", "ring_maple_r", "ring_maple_l")]
    return parts


def acorn(x: float, y_top: float, length: float, s: float) -> list[dict]:
    """An acorn hanging from y_top on a stalk: a scaly cup with a little crown, then a
    glossy shell swelling out of it and tapering to a nib."""
    body = 2.08 * s
    y = y_top - (length - body)                       # where the stalk meets the cup
    parts = [box((x - 0.12 * s, y - 0.1, AZ - 0.12 * s), (x + 0.12 * s, y_top + 0.05, AZ + 0.12 * s), "metal",
                 faces=lane_faces("step", y_top, y - 0.1))]
    parts.append(box((x - 0.36 * s, y - 0.2 * s, AZ - 0.36 * s), (x + 0.36 * s, y + 0.02, AZ + 0.36 * s), "metal",
                     faces=lane_faces("husk", y + 0.02, y - 0.2 * s, sides={"up": dot_uv("husk", y, 0.0)})))
    y_c = y - 0.14 * s
    parts += lane_prism(y_c - 0.52 * s, y_c, 0.68 * s, "husk", cap="husk", x=x)
    y_n = y_c - 0.52 * s                              # the cup's rim
    parts += lane_prism(y_n - 0.72 * s, y_n + 0.14 * s, 0.56 * s, "nut", cap="nut", x=x)
    parts += lane_prism(y_n - 1.08 * s, y_n - 0.68 * s, 0.42 * s, "nut", cap="nut", x=x)
    parts.append(box((x - 0.24 * s, y_n - 1.26 * s, AZ - 0.24 * s), (x + 0.24 * s, y_n - 1.04 * s, AZ + 0.24 * s),
                     "metal", faces=lane_faces("nut", y_n - 1.04 * s, y_n - 1.26 * s)))
    parts.append(box((x - 0.09 * s, y_n - 1.44 * s, AZ - 0.09 * s), (x + 0.09 * s, y_n - 1.22 * s, AZ + 0.09 * s),
                     "metal", faces=lane_faces("husk", y_n - 1.22 * s, y_n - 1.44 * s)))
    return parts


def acorn_bit() -> list[dict]:
    """The bit: a bronze plate with a raised inlay of red enamel under a gilt maple leaf,
    and three acorns hanging from it as its teeth."""
    parts = bit_plate(BIT_X0, PLATE_Y0, PLATE_Y1, 2.2)
    x0, x1, y0, y1 = 4.1, 6.55, 21.6, 24.6
    parts.append(box((x0, y0, AZ - 1.25), (x1, y1, AZ + 1.25), "metal",
                     faces={"south": ("metal", panel_uv(x0, x1, y1, y0)),
                            "north": ("metal", panel_uv(x0, x1, y1, y0, True)),
                            "east": ("metal", lane_uv("step", y1, y0)), "west": ("metal", lane_uv("step", y1, y0)),
                            "up": ("metal", dot_uv("step", y1)), "down": ("metal", dot_uv("step", y0))}))
    parts.append(leaf_sheet("maple_gilt"))
    for x, length, s in ACORNS:
        parts += acorn(x, PLATE_Y0 + 0.05, length, s)
    parts.append(box((BIT_X0 - 0.2, PLATE_Y1 - 0.05, AZ - 1.3), (BIT_X1, PLATE_Y1 + 0.4, AZ + 1.3), "metal",
                     faces=lane_faces("band", PLATE_Y1 + 0.4, PLATE_Y1 - 0.05)))   # a cornice along its top
    return parts


def binding() -> list[dict]:
    """A girdle of red enamel between two gilt beads round the middle of the shaft, holding
    a sprig of two leaves."""
    y0, y1 = BIND
    parts = lane_prism(y0 + 0.25, y1 - 0.25, 1.34, "enamel", cap="enamel")
    parts += lane_prism(y0, y0 + 0.32, 1.46, "vein", cap="vein")
    parts += lane_prism(y1 - 0.32, y1, 1.46, "vein", cap="vein")
    parts += [leaf_sheet("sprig_maple"), leaf_sheet("sprig_oak")]
    return parts


def acorn_finial() -> list[dict]:
    """A big acorn crowning the shaft, cup down and nib up, between two oak leaves."""
    parts = lane_prism(26.15, 26.55, 0.95, "husk", cap="husk")
    parts += lane_prism(26.5, 27.3, 1.3, "husk", cap="husk")
    parts += lane_prism(27.2, 28.35, 1.04, "nut", cap="nut")
    parts += lane_prism(28.3, 28.95, 0.76, "nut", cap="nut")
    parts += lane_prism(28.9, 29.3, 0.42, "nut", cap="nut")
    parts.append(box((AX - 0.13, 29.25, AZ - 0.13), (AX + 0.13, 29.6, AZ + 0.13), "metal",
                     faces=lane_faces("husk", 29.6, 29.25)))
    parts += [leaf_sheet("crown_oak_r"), leaf_sheet("crown_oak_l")]
    return parts


def theme() -> list[dict]:
    return maple_emblem() + amber_gem() + ring_dressing() + acorn_bit() + binding() + acorn_finial()


# ======================================================================================
# 4. TEXTURES
# ======================================================================================
PANEL = [                      # the bit plate's front: 11 x 8 texels, x 3.5..7.2, y 25..21
    "66666666665",
    "65qqqqqqqq2",
    "65qrrRrrrq2",
    "65qrrrrrrq2",
    "65qrrrrrrq2",
    "65qrrrrrRq2",
    "65qqqqqqqq2",
    "43222222221",
]


def paint_theme_lanes(img) -> None:
    paint_lane(img, "vein", [GOLD[4], GOLD[2]])
    paint_lane(img, "nut", [NUT[4], NUT[3], NUT[2]])
    px = img.load()
    # glossy shells: the teeth hang nib down, bright just under their cups and dark at the
    # nib; the crowning acorn stands nib up, dark in its cup's shadow, bright at its shoulder
    c0, w = LANES["nut"]
    bright, mid, dark = [NUT[5], NUT[4], NUT[3]], [NUT[4], NUT[3], NUT[2]], [NUT[3], NUT[2], NUT[1]]
    for y_hi, y_lo, bands in ((20.5, 18.1, (bright, mid, dark)), (29.35, 27.15, (mid, bright, bright, mid, dark))):
        for r in range(row_of(y_hi), row_of(y_lo) + 1):
            k = (r - row_of(y_hi)) / max(1, row_of(y_lo) - row_of(y_hi) + 1)
            tones = bands[min(len(bands) - 1, int(k * len(bands)))]
            for i in range(w):
                px[c0 + i, r] = rgba(tones[i])
    # acorn cups: knobbly scales, lit knobs in a staggered checker over dark hollows
    c0, w = LANES["husk"]
    for r in range(ATLAS):
        for i in range(w):
            on = (i + r) % 2 == 0
            px[c0 + i, r] = rgba((BRONZE[4] if i == 0 else BRONZE[3]) if on else BRONZE[1])
    # the girdle's red enamel, lit on its left
    paint_lane(img, "enamel", [CRIMSON[3], CRIMSON[2], CRIMSON[1]])
    # the bit panel: a bronze frame round red enamel (the gilt leaf sits on top)
    pal = {"r": CRIMSON[2], "R": CRIMSON[3], "q": CRIMSON[1]}
    pal.update({str(i): BRONZE[i] for i in range(7)})
    c0, _ = LANES["panel"]
    r0 = row_of(PLATE_Y1)
    for j, line in enumerate(PANEL):
        for i, ch in enumerate(line):
            px[c0 + i, r0 + j] = rgba(pal[ch])


def theme_atlas_frame(img, t: float) -> None:
    """The amber's flare warms the gold veins on the emblem."""
    p = gem_pulse(t)
    if p < 0.03:
        return
    px = img.load()
    c0, w = LANES["vein"]
    for r in range(ATLAS):
        y = Y_TOP - (r + 0.5) / RPU
        k = 0.6 * p * max(0.0, 1 - abs(y - BOW_Y) / 4.0)
        if k <= 0.01:
            continue
        for i in range(w):
            cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
            px[c0 + i, r] = rgba(mix(cur, "#fff6d0", k))


def paint_atlas_base():
    img = canvas(ATLAS, fill=METAL[3])
    paint_skeleton_lanes(img, METAL)
    paint_theme_lanes(img)
    return img


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


def glint(y: float, t: float) -> float:
    """How strongly the sweep and its trail light model height y at loop phase t: the
    same bands shine() draws on the atlas, so painted parts glint in step with the metal."""
    d0 = -(Y_TOP - y) * RPU
    k = 0.0
    for spec, tt in ((SWEEP, t), (TRAIL, (t - TRAIL_LAG) % 1.0)):
        run = 1.0 - spec["pause"]
        if tt >= run:
            continue
        w = spec["width"]
        centre = -ATLAS - 2 * w + (ATLAS + 4 * w) * (tt / run)
        k = max(k, max(0.0, 1.0 - abs(d0 - centre) / (w / 2 + 0.5)) * spec["strength"])
    return k


# --- painted leaves -----------------------------------------------------------------------

def in_poly(x: float, y: float, poly) -> bool:
    """Even-odd point-in-polygon test."""
    inside = False
    n = len(poly)
    for i in range(n):
        (x0, y0), (x1, y1) = poly[i], poly[(i + 1) % n]
        if (y0 > y) != (y1 > y) and x < x0 + (y - y0) * (x1 - x0) / (y1 - y0):
            inside = not inside
    return inside


def seg_dist(p, a, b) -> float:
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    L = dx * dx + dy * dy
    k = clamp(((p[0] - ax) * dx + (p[1] - ay) * dy) / L) if L else 0.0
    return math.hypot(p[0] - ax - k * dx, p[1] - ay - k * dy)


def maple_shape(j, R, stem_to: float | None = None, hole: float = 0.0):
    """inside(), rel() and veins of a maple leaf with its veins meeting at texel point j and
    tip radius R texels (y down), a stalk down to texel row stem_to, and its heart cut away
    to radius `hole` texels."""
    def to_px(x, y):
        return (j[0] + x * R, j[1] - y * R)

    poly = [to_px(x, y) for x, y in MAPLE_POLY]
    stem_top = j[1] + 0.42 * R

    def inside(x, y):
        if math.hypot(x - j[0], y - j[1]) < hole:
            return False
        if stem_to is not None and abs(x - j[0]) < 1.05 and stem_top <= y <= stem_to:
            return True
        return in_poly(x, y, poly)

    def rel(x, y):
        return math.hypot(x - j[0], y - j[1]) / R

    veins = [(j, to_px(x * 0.88, y * 0.88), 0.55) for x, y in MAPLE_TIPS]
    for lobe, x, y in MAPLE_POINTS:
        tx, ty = MAPLE_TIPS[lobe]
        veins.append((to_px(tx * 0.52, ty * 0.52), to_px(x * 0.88, y * 0.88), 0.35))
    return inside, rel, veins


MAPLE16 = [                    # a maple leaf sprite, 16 x 16, midrib on column 7
    ".......#........",
    "......###.......",
    "...#..###..#....",
    "...##.###.##....",
    "#..#########..#.",
    "##.#########.##.",
    ".#############..",
    "..###########...",
    "###############.",
    ".#############..",
    "..#.#######.#...",
    "....#######.....",
    "......###.......",
    ".......#........",
    ".......#........",
    "........#.......",
]
MAPLE16_VEINS = [((7.5, 13.0), (7.5, 1.2), 0.7), ((7.5, 9.0), (1.2, 4.6), 0.45), ((7.5, 9.0), (13.8, 4.6), 0.45),
                 ((7.5, 9.5), (1.0, 8.5), 0.3), ((7.5, 9.5), (14.0, 8.5), 0.3),
                 ((7.5, 10.0), (2.8, 10.6), 0.3), ((7.5, 10.0), (12.2, 10.6), 0.3)]
OAK16 = [                      # an oak leaf sprite: four rounded lobes a side
    "......###.......",
    ".....#####......",
    "...#.#####.#....",
    "...#########....",
    "....#######.....",
    "..#.#######.#...",
    "..###########...",
    "...#########....",
    ".#.#########.#..",
    ".#############..",
    "..###########...",
    "....#######.....",
    "...#.#####.#....",
    "......###.......",
    ".......#........",
    "........#.......",
]
OAK16_VEINS = [((7.5, 14.5), (7.5, 0.8), 0.7)] + [
    ((7.5, y0), (7.5 + sgn * reach, y1), 0.3)
    for y0, y1, reach in ((4.0, 2.6, 4.0), (7.0, 5.6, 5.0), (10.3, 8.8, 6.0), (12.8, 12.4, 4.0)) for sgn in (-1, 1)]


def mask_shape(mask, veins, centre):
    """inside(), rel() and veins of a hand-drawn 16 px leaf; rel grows toward the edges."""
    def inside(x, y):
        tx, ty = int(x), int(y)
        return 0 <= tx < 16 and 0 <= ty < 16 and mask[ty][tx] == "#"

    def rel(x, y):
        return clamp(math.hypot((x - centre[0]) / 7.0, (y - centre[1]) / 8.0))

    return inside, rel, veins


def paint_enamel(img, ox: int, oy: int, size: int, shape, enamel, rel_lo: float = 0.0, seed: int = 0,
                 rel_out=None) -> None:
    """Paint an enamel leaf into the size x size cell at (ox, oy): a ramp from heart to
    points, gold veins, a lit bevel along the edges facing the upper left, a darker one on
    the far side, a few deliberate blotches, and an optional dark outline."""
    inside, rel, veins = shape
    ramp, vein, lit_edge, mid_edge, dark_edge, outline = ENAMELS[enamel]
    ss = 4
    mask = [[sum(inside(tx + (i + 0.5) / ss, ty + (k + 0.5) / ss) for i in range(ss) for k in range(ss)) * 2 >= ss * ss
             for tx in range(size)] for ty in range(size)]

    def solid(x, y):
        return 0 <= x < size and 0 <= y < size and mask[y][x]

    def normal(tx, ty, reach):
        nx = ny = 0.0
        for dy in range(-reach, reach + 1):
            for dx in range(-reach, reach + 1):
                if (dx or dy) and not solid(tx + dx, ty + dy):
                    nx += dx
                    ny += dy
        n = math.hypot(nx, ny)
        return (nx / n, ny / n) if n else (0.0, 0.0)

    rng = random.Random(seed)
    px = img.load()
    kinds = {}
    for ty in range(size):
        for tx in range(size):
            if not mask[ty][tx]:
                continue
            cx, cy = tx + 0.5, ty + 0.5
            q = clamp((rel(cx, cy) - rel_lo) / (1 - rel_lo))
            if rel_out is not None:
                rel_out[ty][tx] = q
            c = ramp[int(clamp(dither_band(q * (len(ramp) - 1), tx, ty), 0, len(ramp) - 1))]
            kind = "blade"
            k = max((kv for a, b, kv in veins if seg_dist((cx, cy), a, b) < 0.5), default=0.0)
            if k:
                c, kind = mix(c, vein, k), "vein"
            edge = any(not solid(tx + dx, ty + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                nx, ny = normal(tx, ty, 1)
                light = -(nx + ny) * 0.7071
                c = lit_edge if light > 0.3 else (dark_edge if light < -0.3 else mid_edge)
                kind = "edge"
            elif kind == "blade":
                nx, ny = normal(tx, ty, 2)
                if (nx or ny) and -(nx + ny) * 0.7071 > 0.5:
                    c = mix(c, lit_edge, 0.35)          # the bevel's second, softer step
            kinds[(tx, ty)] = kind
            px[ox + tx, oy + ty] = rgba(c)
    blades = [p for p, k in kinds.items() if k == "blade"]
    for _ in range(len(blades) // 28):                  # enamel flecks: little darker clusters
        tx, ty = rng.choice(blades)
        for dx, dy in ((0, 0), (1, 0), (0, 1))[:rng.randint(1, 3)]:
            if kinds.get((tx + dx, ty + dy)) == "blade":
                cur = "#%02x%02x%02x" % px[ox + tx + dx, oy + ty + dy][:3]
                px[ox + tx + dx, oy + ty + dy] = rgba(mix(cur, dark_edge, 0.22))
    if outline:
        for ty in range(size):
            for tx in range(size):
                if not mask[ty][tx] and any(solid(tx + dx, ty + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    px[ox + tx, oy + ty] = rgba(outline)


def paint_samara(img, ox: int, oy: int) -> None:
    """A pair of maple keys hanging from their joined stalk: two glossy nutlets and two
    veined wings sweeping down and out, reddish at the seed and pale at the tips."""
    size, ss = 16, 4
    top = (7.5, 1.2)
    nuts = ((5.9, 3.6), (9.1, 3.6))
    wings = []
    for n, sgn in zip(nuts, (-1, 1)):
        a = math.radians(24)
        wings.append((n, (n[0] + sgn * math.sin(a) * 10.5, n[1] + math.cos(a) * 10.5), sgn))

    def in_wing(x, y, w):
        (ax, ay), (bx, by), sgn = w
        dx, dy = bx - ax, by - ay
        L = math.hypot(dx, dy)
        u = ((x - ax) * dx + (y - ay) * dy) / (L * L)
        side = ((x - ax) * dy - (y - ay) * dx) / L * -sgn     # > 0 on the outer side
        if not 0 <= u <= 1.02:
            return None
        hw_out = 0.6 + 2.5 * math.sin(math.pi * min(1.0, u * 0.62 + 0.08)) * (1 - max(0.0, u - 0.86) * 4)
        hw_in = 0.55 + 0.6 * u
        if -hw_in <= side <= hw_out:
            return u
        return None

    px = img.load()
    mask = {}
    for ty in range(size):
        for tx in range(size):
            hits = []
            for k in range(ss):
                for i in range(ss):
                    x, y = tx + (i + 0.5) / ss, ty + (k + 0.5) / ss
                    if abs(x - top[0]) < 0.5 and top[1] - 1.2 <= y <= nuts[0][1]:
                        hits.append(("stalk", 0))
                    elif any(math.hypot(x - n[0], y - n[1]) <= 1.45 for n in nuts):
                        hits.append(("nut", 0))
                    else:
                        for w in wings:
                            u = in_wing(x, y, w)
                            if u is not None:
                                hits.append(("wing", u))
                                break
            if len(hits) * 2 >= ss * ss:
                kinds = [h[0] for h in hits]
                kind = max(set(kinds), key=kinds.count)
                us = [h[1] for h in hits if h[0] == "wing"]
                mask[(tx, ty)] = (kind, sum(us) / len(us) if us else 0.0)
    for (tx, ty), (kind, u) in mask.items():
        cx, cy = tx + 0.5, ty + 0.5
        if kind == "stalk":
            c = SAMARA_NUT[1]
        elif kind == "nut":
            n = min(nuts, key=lambda n: math.hypot(cx - n[0], cy - n[1]))
            dx, dy = cx - n[0], cy - n[1]
            c = SAMARA_NUT[3] if dx + dy < -1.0 else (SAMARA_NUT[1] if dx + dy > 0.9 else SAMARA_NUT[2])
        else:
            c = ramp_at(SAMARA_WING, u * (len(SAMARA_WING) - 1))
            for (ax, ay), (bx, by), sgn in wings:        # veins fanning along each wing
                for spread in (-0.9, 0.9):
                    ex, ey = bx + sgn * spread * 1.4, by
                    if seg_dist((cx, cy), (ax, ay), (ex, ey)) < 0.45 and u > 0.15:
                        c = mix(c, SAMARA_NUT[2], 0.45)
            edge = any((tx + dx, ty + dy) not in mask for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                c = mix(c, "#fff4d8", 0.4) if (cx < 7.5) == (cy < 9) else mix(c, SAMARA_NUT[1], 0.35)
        px[ox + tx, oy + ty] = rgba(c)
    for ty in range(size):
        for tx in range(size):
            if (tx, ty) not in mask and any((tx + dx, ty + dy) in mask for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[ox + tx, oy + ty] = rgba("#2a1008")


EMBLEM_Q: list = []            # the emblem's colour position per texel (0 heart .. 1 points)


def paint_emblem():
    """The enamel maple leaf, 64 px over the emblem plate: orange at the heart turning red
    toward its eleven points, gold veins, a lit bevel on the edges facing the upper left, a
    dark outline where it lies over the bronze, a gilt collar round its cut-away heart and a
    darker stalk running down to the lip."""
    img = canvas(64)
    EMBLEM_Q.clear()
    EMBLEM_Q.extend([[0.0] * 64 for _ in range(64)])
    j = (32.0, 32.0)
    R = EMBLEM_R * EMBLEM_PPU
    hole = HOLE_R * EMBLEM_PPU
    stem_to = 32 + (BOW_IN - 0.05) * EMBLEM_PPU
    shape = maple_shape(j, R, stem_to=stem_to, hole=hole)
    paint_enamel(img, 0, 0, 64, shape, "emblem", rel_lo=HOLE_R / EMBLEM_R, seed=3, rel_out=EMBLEM_Q)
    px = img.load()
    # the stalk: darker, lit on its left
    poly = [(j[0] + x * R, j[1] - y * R) for x, y in MAPLE_POLY]
    for ty in range(64):
        for tx in range(64):
            if px[tx, ty][3] and abs(tx + 0.5 - j[0]) < 1.05 and not in_poly(tx + 0.5, ty + 0.5, poly):
                px[tx, ty] = rgba("#c4562c" if tx + 0.5 < j[0] else "#7a2216")
    # a gilt collar round the cut-away heart, lit on the upper left
    for ty in range(64):
        for tx in range(64):
            if not px[tx, ty][3]:
                continue
            dx, dy = tx + 0.5 - j[0], ty + 0.5 - j[1]
            r = math.hypot(dx, dy)
            if r < hole + 1.25:
                light = -(dx + dy) / (r * math.sqrt(2)) if r else 0.0
                px[tx, ty] = rgba(GOLD[4] if light > 0.35 else (GOLD[1] if light < -0.35 else GOLD[2]))
    return img


def paint_leaf_base():
    """The leaf sprites, one per 16 px cell of the "leaf" texture."""
    img = canvas(64)
    for n, (name, (ox, oy, enamel, shape)) in enumerate(CELLS.items()):
        if shape == "maple":
            paint_enamel(img, ox, oy, 16, mask_shape(MAPLE16, MAPLE16_VEINS, (7.5, 8.5)), enamel, seed=11 + n)
        elif shape == "oak":
            paint_enamel(img, ox, oy, 16, mask_shape(OAK16, OAK16_VEINS, (7.5, 7.5)), enamel, seed=11 + n)
        else:
            paint_samara(img, ox, oy)
    return img


def emblem_frame(base, t: float):
    """The emblem through the loop: the amber's warm light spreads through the leaf from
    its heart to its points as the gem flares, and it glints as the sweep passes each row."""
    img = base.copy()
    px = img.load()
    for ty in range(64):
        kg = glint(BOW_Y + (32 - (ty + 0.5)) / EMBLEM_PPU, t)
        for tx in range(64):
            r, g, b, a = px[tx, ty]
            if not a:
                continue
            q = EMBLEM_Q[ty][tx]
            c = (r, g, b)
            flush = 0.5 * gem_pulse(t - 0.1 * q) * (1.0 - 0.4 * q)
            if flush > 0.01:
                c = mix(c, "#fff0b8", flush)
            if kg > 0.01:
                c = mix(c, SWEEP["colour"], 0.75 * kg)
            px[tx, ty] = rgba(c)
    return img


# Leaves stirring in the wind: cell -> (sway in texels at the free end, cycles per loop,
# phase, pivot). Leaves are held at the stalk (bottom row) and their tips sway; the maple
# keys hang from the top and swing like a pendulum.
FLUTTER = {"maple_red": (1.4, 2, 0.0, "bottom"), "maple_orange": (1.3, 2, 0.35, "bottom"),
           "maple_amber": (1.2, 2, 0.6, "bottom"), "oak_russet": (1.1, 2, 0.85, "bottom"),
           "oak_red": (1.2, 2, 0.2, "bottom"), "samara": (1.6, 1, 0.1, "top")}


def flutter(img, base, cell: str, t: float) -> None:
    """Shear one sprite cell sideways, most at its free end, so the leaf stirs."""
    amp, cycles, phase, pivot = FLUTTER[cell]
    ox, oy = CELLS[cell][:2]
    src, px = base.load(), img.load()
    sway = amp * math.sin(2 * math.pi * (cycles * t + phase))
    for ty in range(16):
        k = (15 - ty) / 15 if pivot == "bottom" else ty / 15
        dx = round(sway * k ** 1.3)
        if not dx:
            continue
        for tx in range(16):
            px[ox + tx, oy + ty] = (0, 0, 0, 0)
        for tx in range(16):
            nx = tx + dx
            if 0 <= nx < 16:
                px[ox + nx, oy + ty] = src[ox + tx, oy + ty]


def leaf_frame(base, t: float):
    """The leaf sprites stir in the wind and glint as the sweep passes the height they
    hang at."""
    img = base.copy()
    for cell in FLUTTER:
        flutter(img, base, cell, t)
    px = img.load()
    heights = {sheet[0]: sheet[2] for sheet in SHEETS.values()}
    for cell, (ox, oy, _, _) in CELLS.items():
        if cell not in heights:
            continue
        kg = glint(heights[cell], t)
        if kg <= 0.01:
            continue
        for ty in range(oy, oy + 16):
            for tx in range(ox, ox + 16):
                r, g, b, a = px[tx, ty]
                if a:
                    px[tx, ty] = rgba(mix((r, g, b), SWEEP["colour"], 0.65 * kg))
    return img


def gem_frame(t: float):
    """The amber's face: an octagon with a table facet and crown facets lit from the upper
    left, honey light pooling on the far side where it shines through, a white highlight,
    and a star at its flare."""
    p = gem_pulse(t)
    lift = 0.1 + 1.4 * p
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
                f = 0.6
            elif o <= 3.6:                                     # the table: a flat facet, split
                f = 3.6 if dx * lx + dy * ly > 0.4 else 3.1
            else:                                              # crown facets, by direction
                ang = math.atan2(dy, dx)
                sector = round(ang / (math.pi / 4)) * (math.pi / 4)
                f = 2.7 + 1.5 * (math.cos(sector) * lx + math.sin(sector) * ly)
            # light passing through the amber pools on the side away from the light
            through = max(0.0, -(dx * lx + dy * ly) / 8) * (0.9 + 1.1 * p)
            if o <= 7.0:
                f += through
            px[tx, ty] = rgba(ramp_at(AMBER, f + lift * (1.0 if o <= 7.0 else 0.6)))
    for x, y in ((5, 5), (6, 5), (5, 6)):
        px[x, y] = rgba(AMBER[7])
    px[10, 10] = rgba(ramp_at(AMBER, 5.4 + lift))
    sparkle(img, 6, 6, clamp(p * 1.3 - 0.25), colour="#ffffff", reach=4)
    return img


# Falling leaves (window texels): x, starting y, speed (window heights per loop), sway
# amplitude, sway cycles per loop, phase, colour, big. Placed by hand so they never clump.
LEAVES = [dict(x=x, y=y, speed=s, amp=a, freq=f, phase=ph, pal=FALL[c], big=b) for x, y, s, a, f, ph, c, b in (
    (4, 3, 1, 1.6, 1, 0.0, 0, True), (27, 19, 1, 1.7, 1, 0.55, 2, True), (9, 22, 2, 1.3, 2, 0.3, 1, False),
    (22, 9, 1, 1.4, 1, 0.8, 3, False), (15, 13, 2, 2.0, 1, 0.15, 1, True), (29, 2, 2, 1.0, 2, 0.65, 0, False),
    (6, 14, 1, 1.2, 2, 0.4, 2, False), (18, 27, 1, 1.5, 1, 0.9, 0, False))]
SPECKS = ((2, 11, 0.2), (11, 5, 0.7), (13, 28, 0.45), (20, 19, 0.1), (24, 29, 0.6), (26, 12, 0.35),
          (30, 23, 0.85), (8, 30, 0.5))
# Leaf stamps: F = face on, T = turned, E = edge on. B blade, L light side / midrib, D dark
# side, S stalk.
STAMPS = {
    (True, "F"): ["..L..", "B.L.B", "BBLBB", ".BLB.", "..S.."],
    (True, "T"): [".L.", "BLB", "BLD", ".B.", ".S."],
    (True, "E"): ["..L", ".L.", "D.."],
    (False, "F"): [".L.", "BLB", ".S."],
    (False, "T"): ["L", "B", "S"],
    (False, "E"): [".L", "D."],
}


def ridge_y(x: int) -> float:
    """Height (window texels) of the far ridge's skyline at column x."""
    return 25.2 + 1.6 * math.sin(x * 0.33 + 0.6) + 0.9 * math.sin(x * 0.81 + 2.1)


def window_frame(t: float):
    """A harvest dusk in the bow: violet sky above, an ember glow along a far ridge, the
    amber's halo breathing, and leaves tumbling down, swaying and flipping over (each falls
    one or two whole window heights per loop, so the loop is seamless)."""
    p = gem_pulse(t)
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 16, (y + 0.5 - 16) / 16
            r = math.hypot(dx, dy)
            f = 0.2 + 5.8 * (y / 31) ** 1.3
            if r > 0.78:
                f -= (r - 0.78) * 9
            c = SKY[int(clamp(dither_band(f, x, y), 0, len(SKY) - 1))]
            ry = ridge_y(x)
            if y + 0.5 > ry:
                c = RIDGE[1] if y + 0.5 - ry > 1.2 else RIDGE[2]
                if r > 0.85:
                    c = RIDGE[0]
            halo = (0.35 + 0.65 * p) * max(0.0, 1 - r / 0.62) ** 1.6
            if halo > 0.05:
                c = mix(c, AMBER[5], min(0.8, halo))
            px[x, y] = rgba(c)
    for sx, sy, ph in SPECKS:                          # far-off leaves, drifting slowly
        fy = int((sy + 32 * t) % 32)
        fx = int(round(sx + 0.8 * math.sin(2 * math.pi * (t + ph)))) % 32
        cur = "#%02x%02x%02x" % px[fx, fy][:3]
        px[fx, fy] = rgba(mix(cur, FALL[(sx + sy) % 4][0], 0.5))
    for lf in LEAVES:
        phase = 2 * math.pi * (lf["freq"] * t + lf["phase"])
        swing = math.cos(phase)                         # 1 at the middle of each swing
        fy = (lf["y"] + lf["speed"] * 32 * t) % 32 - 0.8 * abs(math.sin(phase))   # glides up at each end
        fx = lf["x"] + lf["amp"] * math.sin(phase)
        pose = "F" if abs(swing) > 0.55 else ("T" if abs(swing) > 0.2 else "E")
        for oy in (0, -32):
            draw_leaf(img, round(fx), round(fy) + oy, STAMPS[(lf["big"], pose)], lf["pal"],
                      mirror=math.sin(phase) < 0, back=swing < 0)
    return img


def draw_leaf(img, x: int, y: int, stamp, pal, mirror: bool = False, back: bool = False) -> None:
    px = img.load()
    blade, light, dark = pal
    if back:                                            # the paler, duller underside
        blade, light = mix(blade, light, 0.25), mix(light, blade, 0.35)
    w, h = len(stamp[0]), len(stamp)
    for j, line in enumerate(stamp):
        for i, ch in enumerate(line[::-1] if mirror else line):
            xx, yy = x + i - w // 2, y + j - h // 2
            if ch == "." or not (0 <= xx < 32 and 0 <= yy < 32):
                continue
            px[xx, yy] = rgba({"B": blade, "L": light, "D": dark, "S": dark}[ch])


def textures() -> None:
    base = paint_atlas_base()
    save_animation(animate(lambda t: atlas_frame(base, t), FRAMES), "metal", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(window_frame, FRAMES), "window", frametime=FRAMETIME)
    emblem = paint_emblem()
    save_animation(animate(lambda t: emblem_frame(emblem, t), FRAMES), "emblem", frametime=FRAMETIME)
    leaves = paint_leaf_base()
    save_animation(animate(lambda t: leaf_frame(leaves, t), FRAMES), "leaf", frametime=FRAMETIME)


# ======================================================================================
# 5. MODELS
# ======================================================================================

def models() -> dict:
    parts = skeleton() + theme()
    return {"main": model(parts, transforms(parts))}
