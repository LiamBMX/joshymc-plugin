"""August Key: the sandy-gold crate key of late summer at the beach (one of the 19 JoshyMC crate keys).

The bow is a stepped ring of sandy gold set with six pearls, framing a little window of
shallow turquoise sea. A coral scallop shell fans up out of the bow's heart, its ribs
radiating from a turquoise sea-glass pebble held in six gold claws. A flared collar joins
the ring to an octagonal shaft dusted with sand where it leaves the bow and tied halfway
up with a twist of rope where a coral starfish clings; a spiral turret shell crowns it,
and on its -X side the bit is a gold plate inlaid with sea, foam and sand, from which a
great wave cast in gold and sea glass climbs, crests and curls over to -X, white foam
claws hanging from its lip as the teeth.

Animation (one 3.2 s loop): a warm glint sweeps up the whole key from the bow to the tip,
the sea glass flares as the glint passes through it and slowly dims while water light
shimmers across it, caustics dance through the window, and grains of sand glitter along
the metal.

---------------------------------------------------------------------------------------
KEY SET SKELETON: copied from january_key.py (all 19 crate keys share it)
---------------------------------------------------------------------------------------
  frame   upright on x = 8, z = 8; front = +Z (the face the inventory shows)
  bow     a stepped ring centred at (8, BOW_Y = 3.5): outer radius 5.4, inner radius 3.5,
          a recessed outer rim, the main band and a raised inner lip, 20 segments each;
          inside a recessed window, the emblem, and the gem socket (bezel + six claws)
  collar  two octagonal discs, y 8.4..10.2
  shaft   octagonal, apothem 1.15, y 10..25.5, bands at y 13 and 17.6, cap 25.4..26.3
  bit     on the -X side of the upper shaft, y 18..25.2
  grip    (8, 10.6, 8); SIZE = 0.64; GUI_ROTATION tilts the diagonal key back a little
  atlas   ONE 64 px animated texture for every metal face: rows = height, columns = lanes,
          so one shine() band sweeping up the atlas glints along the whole key
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, mix, model, place, prism, rgba, save_animation, shine,
                     sparkle, turn)

ID = "august_key"
NAME = "August Key"
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
BIT_X0 = 3.0                                        # the bit plate's outer edge (themes may move it)
BIT_X1 = AX - SHAFT_R + 0.35                        # bit parts run into the shaft up to here
CLAW_ANGLES = tuple(90 + 60 * k for k in range(6))  # the six claws round the heart gem
GRIP = (8.0, 10.6, 8.0)
SIZE = 0.64
GUI_ROTATION = (-25, 20, -45)

# --- timing: every animation loops in 64 ticks ------------------------------------------
FRAMES, FRAMETIME = 32, 2          # atlas and window
GEM_FRAMES, GEM_FRAMETIME = 32, 2  # gem (interpolated; 32 short frames keep its moving caustics crisp)

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
    # theme (42-63): scallop ridges, pearls and water on every row; the panel
    # columns hold the bit's painting at the plate's rows and starfish, rope and foam elsewhere
    "rib": (42, 3), "pearl": (49, 2), "wave": (51, 3), "panel": (54, 10),
    "star": (54, 3), "rope": (57, 4), "foam": (61, 3),
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#fff6dc", width=8.0, strength=0.86, angle=-90.0, pause=0.4)   # bow -> tip
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


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


# ======================================================================================
# 2. THEME PALETTE: August, sandy gold (#E8A93D), coral shell and sea-glass turquoise
# ======================================================================================
METAL = GOLD = ["#2e1621", "#5a2b24", "#8e4d25", "#c27a2c", "#e8a93d", "#f7d36f", "#fff3c4"]
SHELL = ["#5e1f33", "#9a3a45", "#d2645a", "#f08e72", "#fbb690", "#ffd9bc", "#fff4e8"]
WHORL = ["#5a2a2a", "#94513f", "#c98a66", "#e9bd92", "#f9e2c4", "#fff7ec"]
PEARL = ["#8c6670", "#c79ea4", "#efd6d0", "#fff1ea", "#ffffff"]
SEA = ["#06304f", "#0a4d6e", "#0f7088", "#15969f", "#26b8b0", "#5bd8c6", "#a8f0e0", "#e6fff8"]
DEEP = ["#061a3d", "#0a2c5c", "#0d417a", "#115b92", "#1777a6", "#2394b4", "#3db3bf"]
GLASS = ["#11564f", "#1d7c6d", "#34a08a", "#5cc2a6", "#8edcc0", "#bdefdc", "#e4fbf2", "#ffffff"]
FOAM = ["#8fd3cf", "#c9f3ee", "#ffffff"]
STAR = ["#5c1624", "#9e2b2f", "#dc4b3a", "#f57a4f", "#ffab78", "#ffd7b0"]
ROPE = ["#4e3021", "#7d5433", "#ab8150", "#d4ae76", "#efd9a8"]
SAND = ["#8a6238", "#b68a55", "#d8b279", "#ecd29f", "#fbecc8"]

# ======================================================================================
# 3. THEME PARTS: scallop shell, sea-glass gem, pearls, rope and starfish, the wave bit,
#    turret-shell finial
# ======================================================================================
HINGE = (AX, BOW_Y - 0.2)                             # the scallop's ribs radiate from here
FAN_A = (12.0, 168.0)                                 # the fan's span (degrees from the hinge)
FAN_RIBS = 9
FAN_STEP = (FAN_A[1] - FAN_A[0]) / FAN_RIBS
FAN_R, FAN_LOBE = 3.02, 0.28                          # edge radius between ribs, extra at each rib
EAR = (HINGE[1] - 0.95, HINGE[1] + 0.32, 0.9, 2.8)    # the ears: y0, y1, inner and outer |x - 8|
SHELL_SLABS = ((-3.45, -1.25, 3.45, 1.6), (-2.95, 1.6, 2.95, 3.45))   # plate slabs round the bow centre
SHELL_Z = (7.5, 8.5)                                  # just proud of the window (7.75..8.25)
STUD_ANGLES = tuple(120 + 60 * k for k in range(6))   # pearls round the band
PLATE_Y0, PLATE_Y1 = 18.2, 20.4                       # the bit plate: the sea the wave rises from
ROPE_Y = (14.6, 15.6)
STAR_C = (AX, 15.1)
WHORLS = ((26.2, 27.1, 1.05), (27.1, 27.85, 0.86), (27.85, 28.5, 0.68), (28.5, 29.0, 0.51), (29.0, 29.4, 0.36))
APEX = (29.4, 29.8, 0.2)
TRINKET = 32                          # turret shell (left half) and pearl (top right) painting
TURRET_Y = (26.2, 29.8)               # heights the turret painting's rows span, bottom -> top

# The wave is painted as a sprite (the "wave" texture, 4 texels per unit) and extruded:
# every run of texels becomes a box whose front and back show the painting.
WAVE_TEX, WAVE_PPU = 32, 4
WAVE_X0, WAVE_Y1 = 2.4, 25.6          # model x of texel column 0's left edge, y of row 0's top
WAVE_C = (4.4, 23.0)                  # centre of the curl
WAVE_TH = (0.0, 262.0)                # the lip runs from the crest (0) over the top to its tip
WAVE_R, WAVE_W = (1.55, 0.95), (1.25, 0.58)     # lip radius and thickness, crest -> tip
WAVE_BODY = [(5.95, 23.2), (6.45, 22.95), (6.95, 22.5), (7.3, 21.9), (7.3, 20.2), (3.15, 20.2), (3.4, 20.5),
             (3.85, 20.95), (4.4, 21.3), (4.95, 21.55), (5.4, 21.95), (5.6, 22.45), (5.62, 23.0)]
WAVE_CLAWS = ((1, 12), (2, 12), (0, 13), (1, 13), (0, 14),          # foam claws hanging from the lip's
              (3, 14), (2, 15), (3, 15), (2, 16),                    # leading edge (texels): the teeth
              (5, 15), (6, 15), (5, 16), (5, 17))
WAVE_DEPTH = {"body": 1.8, "lip": 1.35, "claw": 0.8}
WAVE_STREAKS = ((20.9, 3.9, 5.6), (21.5, 5.2, 6.6), (22.3, 6.0, 7.0))   # foam lines on the face
WAVE_GLINTS = (((8, 3), 0.15), ((4, 5), 0.55), ((13, 5), 0.8))          # (texel, phase) sun on the foam


def seg(p0, p1, w: float, d: float, lane: str, z: float = AZ, **kw) -> dict:
    """A bar from point p0 to p1 in the front plane, a little longer so joints close."""
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    ext = w * 0.35
    return radial(p0, math.degrees(math.atan2(dy, dx)), -ext, math.hypot(dx, dy) + ext, w, d, lane, z=z, **kw)


def fan_edge(theta: float) -> float:
    """Radius of the scallop's edge at angle theta: a lobe at every rib."""
    phi = ((theta - FAN_A[0]) / FAN_STEP) % 1.0
    return FAN_R + FAN_LOBE * (0.5 - 0.5 * math.cos(2 * math.pi * phi))


def rib_angles():
    return [FAN_A[0] + (k + 0.5) * FAN_STEP for k in range(FAN_RIBS)]


def scallop() -> list[dict]:
    """The scallop: a painted plate (the "shell" texture, projected like the window) cut
    out to the shell's outline, with a raised ridge down every rib."""
    R = WINDOW_R
    parts = []
    for x0, y0, x1, y1 in SHELL_SLABS:
        u0, u1 = (x0 + R) / (2 * R) * 16, (x1 + R) / (2 * R) * 16
        v0, v1 = (R - y1) / (2 * R) * 16, (R - y0) / (2 * R) * 16
        parts.append(box((AX + x0, BOW_Y + y0, SHELL_Z[0]), (AX + x1, BOW_Y + y1, SHELL_Z[1]), "shell",
                         faces={"south": ("shell", [u0, v0, u1, v1]), "north": ("shell", [u1, v0, u0, v1])},
                         skip=("east", "west", "up", "down"), glow=6))
    for ang in rib_angles():
        parts.append(radial(HINGE, ang, 1.75, fan_edge(ang) - 0.22, 0.26, 1.36, "rib", glow=6))
    return parts


def sea_glass() -> list[dict]:
    """The heart gem: a glowing octagonal pebble of sea glass, a body plus a painted front
    and back."""
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


def pearl(c, r: float = 0.42, d: float = BOW_D + 0.3) -> list[dict]:
    """A pearl set through the band: a round body, and on both faces a painted sphere
    (the trinket texture's pearl) with its glint and lustre."""
    x, y = c
    uv = {s: lane_uv("pearl", y + r, y - r) for s in ("north", "south", "east", "west")}
    uv["up"] = uv["down"] = dot_uv("pearl", y, 0.5)
    parts = prism((x, y, AZ), r, d, "metal", axis="z", cap="metal", uv=uv)
    h = r * 16 / 15.2                                    # the painted disc just fills the octagon
    front, back = AZ + d / 2, AZ - d / 2
    parts.append(box((x - h, y - h, front), (x + h, y + h, front + 0.03), "trinket",
                     faces={"south": ("trinket", [8, 0, 16, 8])}, skip=("north", "east", "west", "up", "down")))
    parts.append(box((x - h, y - h, back - 0.03), (x + h, y + h, back), "trinket",
                     faces={"north": ("trinket", [16, 0, 8, 8])}, skip=("south", "east", "west", "up", "down")))
    return parts


def ring_pearls() -> list[dict]:
    parts = []
    for ang in STUD_ANGLES:
        a = math.radians(ang)
        parts += pearl((AX + BAND_R * math.cos(a), BOW_Y + BAND_R * math.sin(a)))
    return parts


def starfish(c, rot: float, arm: float, z: float, d: float) -> list[dict]:
    """A five-armed starfish lying in the front plane: thick inner arms, tapering points
    and a raised centre."""
    parts = []
    for k in range(5):
        ang = rot + 72 * k
        parts.append(radial(c, ang, 0.0, arm * 0.62, arm * 0.42, d, "star", z=z))
        parts += spike(c, ang, arm * 0.5, arm, arm * 0.3, d - 0.1, "star", z=z)
    x, y = c
    uv = {s: lane_uv("star", y + 0.4, y - 0.4) for s in ("north", "south", "east", "west")}
    uv["up"] = uv["down"] = dot_uv("star", y, 0.0)
    parts += prism((x, y, z + 0.08), arm * 0.3, d + 0.16, "metal", axis="z", cap="metal", uv=uv)
    return parts


def rope_and_star() -> list[dict]:
    """A twist of rope tied round the middle of the shaft, a starfish clinging to its front."""
    parts = lane_prism(ROPE_Y[0], ROPE_Y[1], 1.45, "rope", cap="rope")
    parts += starfish(STAR_C, 90, 1.7, AZ + 1.62, 0.5)
    return parts


# --- the wave sprite: which texels are lip, body or foam claw ---------------------------------

def wave_xy(tx: int, ty: int) -> tuple[float, float]:
    """Model (x, y) at the centre of wave texel (tx, ty)."""
    return WAVE_X0 + (tx + 0.5) / WAVE_PPU, WAVE_Y1 - (ty + 0.5) / WAVE_PPU


def _inside(poly, x: float, y: float) -> bool:
    c = False
    for (x1, y1), (x2, y2) in zip(poly, poly[1:] + poly[:1]):
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            c = not c
    return c


def _lip(x: float, y: float):
    """(angle along the lip, side -1 inner .. +1 outer) of a point on the curling lip, or None."""
    best = None
    for i in range(int(WAVE_TH[1] - WAVE_TH[0]) * 2 + 1):
        th = WAVE_TH[0] + i / 2
        k = (th - WAVE_TH[0]) / (WAVE_TH[1] - WAVE_TH[0])
        r = WAVE_R[0] + (WAVE_R[1] - WAVE_R[0]) * k
        w = WAVE_W[0] + (WAVE_W[1] - WAVE_W[0]) * k
        a = math.radians(th)
        d = math.hypot(x - WAVE_C[0] - r * math.cos(a), y - WAVE_C[1] - r * math.sin(a))
        if d < w / 2 and (best is None or d < best[0]):
            best = (d, th, (math.hypot(x - WAVE_C[0], y - WAVE_C[1]) - r) / (w / 2))
    return None if best is None else best[1:]


def _wave_map() -> dict:
    out = {}
    for ty in range(WAVE_TEX):
        for tx in range(WAVE_TEX):
            x, y = wave_xy(tx, ty)
            lip = _lip(x, y)
            if lip:
                out[(tx, ty)] = ("lip",) + lip
            elif _inside(WAVE_BODY, x, y):
                out[(tx, ty)] = ("body", 0.0, 0.0)
            elif (tx, ty) in WAVE_CLAWS:
                out[(tx, ty)] = ("claw", 0.0, 0.0)
    return out


WAVE_MAP = _wave_map()


def wave_bit() -> list[dict]:
    """The bit: a gold plate inlaid with the sea, and rising from it a great wave that
    climbs the shaft, crests and curls over to -X with foam claws on its lip (the teeth).
    The wave is its sprite extruded: runs of texels merged into boxes, lip thinner than the
    body, claws thinnest; the walls sample the atlas so the glint crosses them too."""
    parts = bit_plate(BIT_X0, PLATE_Y0, PLATE_Y1, 2.2)
    runs = {}
    for ty in range(WAVE_TEX):
        tx = 0
        while tx < WAVE_TEX:
            cell = WAVE_MAP.get((tx, ty))
            if cell is None:
                tx += 1
                continue
            t0 = tx
            while tx < WAVE_TEX and WAVE_MAP.get((tx, ty), ("",))[0] == cell[0]:
                tx += 1
            runs.setdefault((cell[0], t0, tx - 1), []).append(ty)
    for (label, t0, t1), rows in sorted(runs.items()):
        start = rows[0]
        for i, ty in enumerate(rows):
            if i + 1 < len(rows) and rows[i + 1] == ty + 1:
                continue
            x0, x1 = WAVE_X0 + t0 / WAVE_PPU, WAVE_X0 + (t1 + 1) / WAVE_PPU
            y1, y0 = WAVE_Y1 - start / WAVE_PPU, WAVE_Y1 - (ty + 1) / WAVE_PPU
            d = WAVE_DEPTH[label]
            faces = lane_faces("foam" if label == "claw" else "wave", y1, y0)
            u0, u1, v0, v1 = t0 / 2, (t1 + 1) / 2, start / 2, (ty + 1) / 2
            faces["south"] = ("wave", [u0, v0, u1, v1])
            faces["north"] = ("wave", [u1, v0, u0, v1])
            parts.append(box((x0, y0, AZ - d / 2), (x1, y1, AZ + d / 2), "metal", faces=faces))
            if i + 1 < len(rows):
                start = rows[i + 1]
    return parts


def turret_v(y: float) -> float:
    """v (0..16) of height y in the turret painting."""
    return round(clamp((TURRET_Y[1] - y) / (TURRET_Y[1] - TURRET_Y[0]) * 16, 0, 16), 4)


def turret_shell() -> list[dict]:
    """A spiral turret shell crowning the shaft: five whorls (each turned a little further)
    and a dark apex, painted by the trinket texture's left half."""
    parts = []
    cap = [8.25, 8.25, 8.75, 8.75]                       # a cream swatch in the trinket texture
    for i, (y0, y1, r) in enumerate(WHORLS + (APEX,)):
        side = [0, turret_v(y1), 8, turret_v(y0)]
        back = [8, turret_v(y1), 0, turret_v(y0)]
        uv = {"south": side, "north": back, "east": side, "west": back, "up": cap, "down": cap}
        parts += turn(prism((AX, (y0 + y1) / 2, AZ), r, y1 - y0, "trinket", cap="trinket", uv=uv),
                      11.25 * i, "y", (AX, y0, AZ))
    return parts


def theme() -> list[dict]:
    return scallop() + sea_glass() + ring_pearls() + rope_and_star() + wave_bit() + turret_shell()


# ======================================================================================
# 4. TEXTURES
# ======================================================================================
PANEL = [                      # the bit plate's front: 10 x 5 texels, x 3.0..7.2, y 20.4..18.2:
    "6666666665",              # the sea the wave rises from, foam lapping over the sand
    "65bAabaAb2",
    "65WfWWfWf2",
    "65sStsSst2",
    "4322222221",
]
SAND_ROWS = (10.0, 12.6)       # sand clinging to the shaft where it leaves the bow
SHAFT_OPEN = ((10.3, 12.4), (13.6, 14.4), (15.8, 17.0), (18.2, 25.2))   # shaft not hidden by bands or rope


def paint_theme_lanes(img) -> None:
    px = img.load()
    for r in range(ATLAS):
        y = Y_TOP - (r + 0.5) / RPU
        # the scallop's ridges: pale peach, warming toward the edge, lit edge first
        k = clamp((y - HINGE[1] - 1.4) / 1.8)
        c0, _ = LANES["rib"]
        for i, o in enumerate((1.3, 0.6, -0.2)):
            px[c0 + i, r] = rgba(SHELL[int(clamp(dither_band(3.7 + 1.3 * k + o, i, r), 0, 6))])
        # the wave's walls: the gold casting's edge, bevelled, lit edge first
        c0, _ = LANES["wave"]
        for i, tone in enumerate((6, 5, 3)):
            px[c0 + i, r] = rgba(GOLD[tone])
    paint_lane(img, "pearl", [PEARL[3], PEARL[2]])
    paint_lane(img, "star", [STAR[3], STAR[2], STAR[1]])
    c0, _ = LANES["star"]
    for r in range(ATLAS):                       # the starfish's bumpy skin
        if r % 2:
            px[c0, r] = rgba(STAR[4])
            px[c0 + 1, r] = rgba(STAR[3])
    paint_lane(img, "foam", [FOAM[2], FOAM[1], FOAM[0]])
    # rope: strands twisting across the band's rows
    c0, w = LANES["rope"]
    for r in range(ATLAS):
        for i in range(w):
            px[c0 + i, r] = rgba([ROPE[4], ROPE[3], ROPE[2], ROPE[1]][(i + r) % 4])
    # the bit panel: a gold frame round a turquoise sea over a strip of sand
    pal = {"W": "#ffffff", "f": FOAM[1], "s": SAND[3], "S": SAND[4], "t": SAND[2], "a": SEA[4], "A": SEA[5],
           "b": SEA[3]}
    pal.update({str(i): GOLD[i] for i in range(7)})
    c0, _ = LANES["panel"]
    r0 = row_of(PLATE_Y1)
    for j, line in enumerate(PANEL):
        for i, ch in enumerate(line):
            px[c0 + i, r0 + j] = rgba(pal[ch])
    # sand drifted against the shaft where it leaves the bow: packed at the collar, thinning
    # to loose grains as it climbs, lighter on the lit edge, over the gold
    c0, w = LANES["shaft"]
    rng = random.Random(8)
    lo, hi = row_of(SAND_ROWS[1]), row_of(SAND_ROWS[0])
    for r in range(lo, hi + 1):
        k = (r - lo) / max(1, hi - lo)          # 0 at the top of the drift, 1 at the collar
        for i in range(w):
            if rng.random() < 0.12 + 0.8 * k * k:
                px[c0 + i, r] = rgba(SAND[4 if i == 0 else 3] if rng.random() < 0.55 else SAND[2 if i < 2 else 1])


def _twinkles():
    """Grains of sand that glint: (lane, column, row, start phase), seeded. They sit only
    where a face really samples that lane: the middle of the ring's band segments (as
    bow_ring() lanes them, clear of the pearls), the open stretches of the shaft (four in
    the sand drift) and the collar and cap."""
    rng = random.Random(11)
    step = 360 / BOW_SEGMENTS
    ring = []
    for k in range(BOW_SEGMENTS):
        ang = 90 + k * step
        if all(abs((ang - a + 180) % 360 - 180) > 12 for a in STUD_ANGLES):
            mid = math.radians(ang)
            lane = FACE_LANES[round(lit((math.cos(mid), math.sin(mid))) + 1)]
            ring.append((lane, 1, BOW_Y + BAND_R * math.sin(mid) + rng.uniform(-0.3, 0.3)))
    spots = rng.sample(ring, 10)
    spots += [("shaft", rng.choice((1, 2)), rng.uniform(10.4, 11.8)) for _ in range(4)]
    for _ in range(7):
        lo, hi = rng.choice(SHAFT_OPEN[1:] + SHAFT_OPEN[3:])
        spots.append(("shaft", rng.choice((1, 2)), rng.uniform(lo, hi)))
    spots += [("band", 1, rng.uniform(8.5, 10.1)), ("band", 2, rng.uniform(8.5, 10.1)),
              ("band", 1, rng.uniform(25.5, 26.2)), ("panel", 4, PLATE_Y0 + 0.9), ("panel", 7, PLATE_Y0 + 0.9)]
    rng.shuffle(spots)
    n = len(spots)
    return [(lane, col, row_of(y), (i / n + rng.uniform(-0.015, 0.015)) % 1.0)
            for i, (lane, col, y) in enumerate(spots)]


TWINKLES = _twinkles()
TWINKLE_LEN = 0.14


def theme_atlas_frame(img, t: float) -> None:
    """The gem's flare warms the scallop's ridges, and grains of sand glint along the metal."""
    px = img.load()
    p = gem_pulse(t)
    if p > 0.03:
        c0, w = LANES["rib"]
        for r in range(row_of(BOW_Y + 4.5), row_of(BOW_Y - 1.0) + 1):
            y = Y_TOP - (r + 0.5) / RPU
            k = 0.45 * p * max(0.0, 1 - abs(y - (BOW_Y + 1.3)) / 3.4)
            for i in range(w):
                if k > 0.01:
                    px[c0 + i, r] = rgba(mix("#%02x%02x%02x" % px[c0 + i, r][:3], "#fff3ea", k))
    for lane, col, row, start in TWINKLES:
        x = (t - start) % 1.0
        if x >= TWINKLE_LEN:
            continue
        a = math.sin(math.pi * x / TWINKLE_LEN)
        c0, w = LANES[lane]
        for dx, dy, k in ((0, 0, 1.0), (0, -1, 0.75), (0, 1, 0.75), (0, -2, 0.3), (0, 2, 0.3), (-1, 0, 0.5),
                          (1, 0, 0.5)):
            xx, yy = c0 + col + dx, row + dy
            if c0 <= xx < c0 + w and 0 <= yy < ATLAS:
                px[xx, yy] = rgba(mix("#%02x%02x%02x" % px[xx, yy][:3], "#ffffff" if k > 0.9 else "#fff6d0",
                                      k * a))


def paint_atlas_base():
    img = canvas(ATLAS, fill=METAL[3])
    paint_skeleton_lanes(img, METAL)
    paint_theme_lanes(img)
    return img


def sweep_amount(y: float, t: float, spec: dict) -> float:
    """How strongly the atlas's shine() band (SWEEP or TRAIL) covers model height y at
    phase t: the same maths, so textures outside the atlas glint in step with it."""
    run = 1.0 - spec["pause"]
    if t >= run:
        return 0.0
    width = spec["width"]
    centre = -ATLAS - width * 2 + (ATLAS + width * 4) * (t / run)
    d = abs(-(Y_TOP - y) * RPU - centre)
    return max(0.0, 1.0 - d / (width / 2 + 0.5)) * spec["strength"]


def swept(c: str, y: float, t: float) -> str:
    """Colour c with the metal's glint (and its trailing twin) applied at model height y."""
    for spec, phase in ((SWEEP, t), (TRAIL, (t - TRAIL_LAG) % 1.0)):
        s = sweep_amount(y, phase, spec)
        if s > 0:
            c = mix(c, spec["colour"], s)
    return c


def caustic(x: float, y: float, t: float, size: float = 32.0) -> float:
    """0..1: the bright web of light that shimmering water throws, at texel (x, y) of a
    size-px texture, loop phase t (every term turns a whole number of times per loop)."""
    u, v = x / size, y / size
    tau = 2 * math.pi
    a = math.sin(tau * (1.3 * u + 0.35 * math.sin(tau * (v + t)) + t))
    b = math.sin(tau * (1.1 * v + 0.35 * math.sin(tau * (u - t)) - t))
    c = math.sin(tau * (0.8 * (u + v) + 2 * t))
    s = a + b + 0.7 * c
    return max(0.0, 1 - abs(s) / 0.55) ** 1.5


def gem_frame(t: float):
    """The sea glass: a frosted, softly domed pebble lit from the upper left, water light
    shimmering across it, brightening and throwing a star at its flare."""
    p = gem_pulse(t)
    lift = 0.2 + 1.4 * p
    img = canvas(16)
    px = img.load()
    rng = random.Random(3)
    frost = [[rng.random() for _ in range(16)] for _ in range(16)]
    for ty in range(16):
        for tx in range(16):
            dx, dy = tx + 0.5 - 8, ty + 0.5 - 8
            ax, ay = abs(dx), abs(dy)
            o = max(ax, ay, (ax + ay) / math.sqrt(2))
            if o > 8:
                continue
            n = o / 8
            f = 3.2 - 1.3 * (dx * 0.93 + dy * 0.36) / 8 - 2.0 * n ** 3
            f += (frost[ty][tx] - 0.5) * 0.45
            f += 1.8 * caustic(tx * 2, ty * 2, t)
            px[tx, ty] = rgba(ramp_at(GLASS, f + lift * (1.0 if o <= 7.0 else 0.6)))
    for x, y in ((4, 4), (5, 4), (4, 5)):
        px[x, y] = rgba(GLASS[7])
    sparkle(img, 5, 5, clamp(p * 1.3 - 0.25), colour="#ffffff", reach=4)
    return img


def window_frame(t: float):
    """The sea in the bow: deep blue under the shell, turquoise shallows below, darker at
    the rim, a web of caustic light shimmering through it and the gem's halo breathing."""
    p = gem_pulse(t)
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 16, (y + 0.5 - 16) / 16
            r = math.hypot(dx, dy)
            f = 1.0 + 4.2 * (y / 31)
            if r > 0.78:
                f -= (r - 0.78) * 8
            c = DEEP[int(clamp(dither_band(f, x, y), 0, len(DEEP) - 1))]
            k = caustic(x, y, t)
            if k > 0.05:
                c = mix(c, SEA[6], min(0.85, k * (0.45 + 0.4 * (y / 31))))
            halo = (0.35 + 0.65 * p) * max(0.0, 1 - r / 0.6) ** 1.6
            if halo > 0.05:
                c = mix(c, GLASS[5], min(0.8, halo))
            px[x, y] = rgba(c)
    return img


# --- the scallop's own texture, projected across the bow like the window ------------------
SHELL_TEX = 32


def shell_xy(tx: int, ty: int) -> tuple[float, float]:
    """Model (x, y) at the centre of shell texel (tx, ty)."""
    R = WINDOW_R
    return AX - R + (tx + 0.5) / SHELL_TEX * 2 * R, BOW_Y + R - (ty + 0.5) / SHELL_TEX * 2 * R


def shell_tone(x: float, y: float):
    """The scallop's ramp index (a float into SHELL) at model point (x, y), or None where
    there is no shell: ribs lit on their -X side, grooves between them, growth lines, a
    pale growth edge scalloped by the rib lobes, and two ribbed ears at the hinge."""
    dx, dy = x - HINGE[0], y - HINGE[1]
    rho = math.hypot(dx, dy)
    theta = math.degrees(math.atan2(dy, dx))
    if FAN_A[0] <= theta <= FAN_A[1]:
        edge = fan_edge(theta)
        if rho <= edge:
            k = rho / edge
            phi = ((theta - FAN_A[0]) / FAN_STEP) % 1.0
            f = 1.4 + 2.7 * k
            if 0.24 < phi < 0.76:
                f += 0.8 + (0.45 if phi > 0.5 else -0.15)
            elif phi < 0.07 or phi > 0.93:
                f -= 0.8
            if rho > edge - 0.3:
                f += 0.9
            elif any(abs(k - g) < 0.03 for g in (0.52, 0.74)):
                f -= 0.55
            return f
    y0, y1, ax0, ax1 = EAR
    ax = abs(dx)
    if y0 <= y <= y1 and ax0 <= ax <= ax1 - 0.55 * (y1 - y) / (y1 - y0):
        f = 2.0 + 0.9 * (ax - ax0) / (ax1 - ax0)
        if int((math.degrees(math.atan2(dy + 0.2, ax)) + 60) / 12) % 2:
            f += 0.55
        if y > y1 - 0.25:
            f += 0.8
        return f
    return None


def paint_shell_base():
    img = canvas(SHELL_TEX)
    px = img.load()
    for ty in range(SHELL_TEX):
        for tx in range(SHELL_TEX):
            f = shell_tone(*shell_xy(tx, ty))
            if f is not None:
                px[tx, ty] = rgba(SHELL[int(clamp(dither_band(f, tx, ty), 0, 6))])
    return img


def shell_frame(base, t: float):
    """The scallop in one frame: the metal's glint crossing it at the same moment, and the
    gem's flare warming it from the hinge out."""
    img = base.copy()
    px = img.load()
    p = gem_pulse(t)
    for ty in range(SHELL_TEX):
        for tx in range(SHELL_TEX):
            r, g, b, a = px[tx, ty]
            if not a:
                continue
            x, y = shell_xy(tx, ty)
            c = "#%02x%02x%02x" % (r, g, b)
            k = 0.4 * p * max(0.0, 1 - math.hypot(x - HINGE[0], y - HINGE[1]) / 3.6)
            if k > 0.01:
                c = mix(c, "#fff3ea", k)
            px[tx, ty] = rgba(swept(c, y, t))
    return img


# --- the wave's painting ------------------------------------------------------------------

def wave_edge(tx: int, ty: int):
    """The outward normal (texel space, +y down) of a wave texel on the sprite's outline,
    or None inside it. The barrel's hole counts as inside (it is shadow, not rim), and so
    does the body's right end, which runs into the shaft."""
    if WAVE_MAP.get((tx, ty), ("",))[0] == "claw":
        return None
    nx = ny = 0
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        q = (tx + dx, ty + dy)
        if q in WAVE_MAP:
            continue
        x, y = wave_xy(*q)
        if math.hypot(x - WAVE_C[0], y - WAVE_C[1]) < WAVE_R[0] - 0.35 or x > 7.1:
            continue
        nx, ny = nx + dx, ny + dy
    return None if nx == ny == 0 else (nx, ny)


def wave_gold(n) -> str:
    """Gold for a rim texel facing n (texel space): bright toward the upper left light."""
    k = (-n[0] * 0.7 - n[1] * 0.7) / (math.hypot(*n) or 1)
    return GOLD[int(clamp(round(4.3 + 1.2 * k), 3, 5))]


def wave_tone(tx: int, ty: int, t: float):
    """Colour of wave texel (tx, ty) at phase t: the wave is a gold casting inlaid with sea
    glass. A bevelled gold rim runs round its whole outline; inside it the body is deep
    turquoise brightening toward the crest with foam streaks sliding down its face, the lip
    pale with a white foam band under the rim, shadowed over the barrel, with a surge of
    light racing along it; the foam claws hanging off the lip (the teeth) are white."""
    cell = WAVE_MAP.get((tx, ty))
    if cell is None:
        return None
    label, th, side = cell
    x, y = wave_xy(tx, ty)
    if label == "claw":
        return FOAM[2] if (tx + ty) % 3 else FOAM[1]
    n = wave_edge(tx, ty)
    if n is not None:
        return wave_gold(n)
    if label == "lip":
        k = (th - WAVE_TH[0]) / (WAVE_TH[1] - WAVE_TH[0])
        if side > 0.05 and 30 < th < 225:
            return FOAM[2] if th < 150 else FOAM[1]
        if side < -0.45:
            return SEA[2] if th > 60 else SEA[3]
        f = 5.3 - 1.6 * k + (0.5 if side > 0 else 0.0)
        surge = -60 + 380 * t                       # the surge runs crest -> tip once a loop
        f += 1.3 * max(0.0, 1 - abs(th - surge) / 38)
        return SEA[int(clamp(round(f), 0, 7))]
    f = 2.2 + 2.9 * (y - 20.2) / 3.0
    if math.hypot(x - WAVE_C[0], y - WAVE_C[1]) < 1.35 and x < 5.9:
        f -= 0.9                                    # the wall of the barrel, in shadow
    for sy, sx0, sx1 in WAVE_STREAKS:
        along = (x - sx0) / (sx1 - sx0)
        if sx0 - 0.4 <= x <= sx1 + 0.4 and abs(y - sy - 0.35 * along) < 0.13:
            dash = ((x - sx0) / 1.1 + t) % 1.0         # dashes of foam sliding toward the trough
            if dash < 0.62:
                f += 1.6
    return SEA[int(clamp(round(f), 0, 7))]


def wave_frame(t: float):
    img = canvas(WAVE_TEX)
    px = img.load()
    for (tx, ty) in WAVE_MAP:
        c = wave_tone(tx, ty, t)
        px[tx, ty] = rgba(swept(c, wave_xy(tx, ty)[1], t))
    for (sx, sy), phase in WAVE_GLINTS:                 # the sun catching the foam
        x = (t - phase) % 1.0
        if x < 0.2:
            sparkle(img, sx, sy, math.sin(math.pi * x / 0.2), colour="#ffffff", reach=1)
    return img


# --- the trinket texture: the turret shell's painting and a pearl ----------------------------

def trinket_frame(t: float):
    """Left half: the turret shell (rows = its height, columns = across a facet, lit edge
    first): cream whorls swelling in the middle, a dark suture under each, rows of
    coral-brown spots and a dark apex, glinting with the metal. Top right: a pearl, lit
    from the upper left with a warm bounce below and a lustre drifting across it."""
    img = canvas(TRINKET)
    px = img.load()
    span = TURRET_Y[1] - TURRET_Y[0]
    for ty in range(TRINKET):
        y = TURRET_Y[1] - (ty + 0.5) / TRINKET * span
        whorl, k = None, 0.0
        for i, (y0, y1, _) in enumerate(WHORLS):
            if y0 <= y < y1:
                whorl, k = i, (y - y0) / (y1 - y0)
        for tx in range(16):
            s = tx / 15
            if whorl is None:
                f = 1.7 - 0.9 * s
            elif k < 0.14:
                f = 1.1 - 0.6 * s
            else:
                f = 3.6 + 0.9 * math.sin(math.pi * k) - 1.7 * s
                if (tx + 2 * whorl) % 5 == 1 and 0.4 < k < 0.75:
                    f -= 1.2
            c = WHORL[int(clamp(dither_band(f, tx, ty), 0, 5))]
            px[tx, ty] = rgba(swept(c, y, t))
    for tx in (16, 17):
        for ty in (16, 17):
            px[tx, ty] = rgba(WHORL[4])
    for ty in range(16):
        for tx in range(16, 32):
            dx, dy = (tx + 0.5 - 24) / 7.6, (ty + 0.5 - 8) / 7.6
            rr = dx * dx + dy * dy
            if rr > 1:
                continue
            lam = max(0.0, (-0.5 * dx - 0.6 * dy + 0.62 * math.sqrt(1 - rr)) / 0.997)
            c = ramp_at(PEARL, 0.9 + 2.7 * lam)
            if dx + dy > 0.2:
                c = mix(c, "#f4c4c0", min(0.35, 0.22 * (dx + dy)))
            band = dx - dy * 0.3 - (-2.2 + 4.4 * t)          # an orient of colour drifting across
            for off, tint in ((0.0, "#ffe6b0"), (0.45, "#d9f0ff")):
                sheen = max(0.0, 1 - abs(band - off) / 0.35)
                if sheen > 0:
                    c = mix(c, tint, 0.55 * sheen)
            if math.hypot(dx + 0.36, dy + 0.4) < 0.2:
                c = "#ffffff"
            px[tx, ty] = rgba(c)
    return img


def textures() -> None:
    base = paint_atlas_base()
    save_animation(animate(lambda t: atlas_frame(base, t), FRAMES), "metal", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(window_frame, FRAMES), "window", frametime=FRAMETIME)
    shell = paint_shell_base()
    save_animation(animate(lambda t: shell_frame(shell, t), FRAMES), "shell", frametime=FRAMETIME)
    save_animation(animate(wave_frame, FRAMES), "wave", frametime=FRAMETIME)
    save_animation(animate(trinket_frame, FRAMES), "trinket", frametime=FRAMETIME)


# ======================================================================================
# 5. MODELS
# ======================================================================================

def models() -> dict:
    parts = skeleton() + theme()
    return {"main": model(parts, transforms(parts))}
