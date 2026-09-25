"""October Key: the blackened-iron crate key of Halloween (one of the 19 JoshyMC crate keys).

The bow is the set's stepped ring in blackened iron, framing a carved jack-o'-lantern: five
ribbed lobes of pumpkin (the middle three bulge out of the ring) with triangle eyes and a
toothy grin cut right through them, so the candle-lit inside of the pumpkin shows through.
Its nose is the key's heart gem, a trillion-cut fire opal clasped by three iron claws.
Candle-lit ember studs stud the ring, and a green pumpkin vine grows from under the stem
down both of its shoulders, with leaves and a curled tendril. An octagonal shaft with
pumpkin-orange enamel bands and a twisted-iron middle rises to a harvest moon cradled in an
iron crescent; a little iron spider drops on its thread from under the cap, and the bit on
the -X side is a bat's wing: iron finger bones over a membrane glowing like a wing held
against the moon, its scalloped finger tips standing in for the teeth.

Animation (one 3.2 s loop): the candle inside the pumpkin flickers and sways, lighting the
carved face, its cut rims and the ember studs; a warm glint sweeps up the iron from the bow
to the tip and the fire-opal nose flares as it passes; two tiny bats circle the moon,
flapping, passing in front of it and vanishing behind it.

---------------------------------------------------------------------------------------
KEY SET SKELETON: all 19 crate keys share these proportions (model units)
---------------------------------------------------------------------------------------
  frame   upright on x = 8, z = 8; front = +Z (the face the inventory shows)
  bow     a stepped ring centred at (8, BOW_Y = 3.5): outer radius 5.4 (10.8 across,
          y -1.9..8.9), inner radius 3.5; a recessed outer rim (0.5 wide, 2.0 deep), the
          main band (2.6 deep, z 6.7..9.3) and a raised inner lip (0.6 wide, 3.0 deep),
          20 segments each. Inside: a recessed window (the theme's animated scene), the
          theme's emblem, and at the heart a socket holding the theme's gem, which stands
          proudest of everything
  collar  two octagonal discs, y 8.4..10.2, joining the ring's top to the shaft
  shaft   octagonal, apothem 1.15 (2.3 thick), y 10..25.5, bands at y 13 and 17.6,
          a cap at 25.4..26.3; the theme may crown it with a finial (up to y ~29.5)
  bit     on the -X side of the upper shaft, y 18..25.2, plus the theme's teeth
  grip    (8, 10.6, 8): the fist closes on the shaft just above the bow
  size    SIZE = 0.64 holds it at about 1 block; carried upright like a sceptre
  gui     GUI_ROTATION tilts the diagonal key back a little so its faces catch the light

Animation approach (every loop is 64 ticks, so all the pieces stay in step)
  metal   ONE 64 px atlas textures every metal face. Its ROWS map to the key's height
          (row = 2 * (29 - y)); its COLUMNS are LANES: narrow bands painted with one
          material's cross-section. Every face samples its lane at the rows of its own
          height, so ONE horizontal shine() band sweeping up the atlas glints along the whole
          key in one piece, bow to tip. Columns 0-41 are the skeleton's metal lanes (painted
          from METAL), 42-63 the theme's. 32 frames x 2 ticks.
  gem     the heart gem, interpolated (16 frames x 4 ticks): it flares as the glint
          crosses the bow (sweep_time(BOW_Y)) and slowly dims
  window  the theme's scene inside the bow (32 frames x 2 ticks): here the candle-lit
          inside of the pumpkin, seen through its carved face

October replaces the round heart-gem socket with a triangular one (the jack-o'-lantern's
nose) and paints its own pumpkin, bat-wing, moon and bat textures in step with the atlas.
The bats fly on two cut-out planes round the moon (bats_near / bats_far), left out of the
icon's fit so the key keeps the set's inventory size.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, mix, model, place, prism, rgba, save, save_animation,
                     shine, sparkle, turn)

ID = "october_key"
NAME = "October Key"
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
    "iron": (42, 4), "twist": (46, 4), "ember": (50, 3), "gold": (53, 3), "stem": (56, 4),
    "silk": (60, 2), "spider": (62, 2),
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#fff2d8", width=8.0, strength=0.88, angle=-90.0, pause=0.4)   # bow -> tip
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


def skeleton() -> list[dict]:
    # October's candle-lit window glows at full strength; its heart-gem socket is the
    # triangular nose in section 3.
    return bow_ring() + bow_window(glow=15) + collar() + shaft()


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
# 2. THEME PALETTE: October, blackened iron and pumpkin orange (#FF7518)
# ======================================================================================
METAL = IRON = ["#0f0a16", "#1d1527", "#2f243b", "#463751", "#64506e", "#917a98", "#cfb9cc"]
PUMPKIN = ["#3b0d1c", "#6b1a17", "#a8340f", "#e2560f", "#ff7518", "#ff9a33", "#ffbb55", "#ffe08a"]
ENAMEL = ["#4a1210", "#8a2412", "#c8440f", "#f26a14", "#ff9a3a", "#ffcf7a"]
GLOW = ["#8a2208", "#cc420c", "#f47416", "#ffa22a", "#ffcc4a", "#ffee8a", "#fffbe0"]
FLESH = ["#a8481a", "#e0822a", "#ffba55", "#ffe89c"]
GEM = ["#2e0410", "#6e0c14", "#b01e12", "#e8440f", "#ff7a1c", "#ffac3a", "#ffe07a", "#ffffff"]
MOON = ["#8a3f16", "#c26a24", "#e8973a", "#f8bf5e", "#ffdc8e", "#fff2c8"]
WING = ["#12070f", "#2c0c16", "#521218", "#861f16", "#bd3a14", "#ec6418", "#ff9a3a", "#ffc766"]
STEM = ["#16200c", "#2c4416", "#46681e", "#66902c", "#97c24c"]
BACKDROP = ["#0e050c", "#1c0913", "#2e0f18", "#4a1a1a"]      # the dark inside of the pumpkin
BAT = "#140a19"
BAT_FAR = "#3a2a48"


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


def flicker(t: float) -> float:
    """0..1, the candle's flicker: a few sines with whole cycles per loop, so it is seamless."""
    v = (0.58 + 0.16 * math.sin(2 * math.pi * (2 * t + 0.13)) + 0.11 * math.sin(2 * math.pi * (5 * t + 0.61))
         + 0.07 * math.sin(2 * math.pi * (7 * t + 0.29)) + 0.05 * math.sin(2 * math.pi * (3 * t + 0.84)))
    return clamp(v)


def sway(t: float) -> float:
    """-1..1, the flame leaning left and right."""
    return 0.65 * math.sin(2 * math.pi * (t + 0.2)) + 0.35 * math.sin(2 * math.pi * (3 * t + 0.55))


# ======================================================================================
# 3. THEME PARTS: jack-o'-lantern, fire-opal nose, ember studs, bat-wing bit, twisted
#    iron, harvest-moon finial
# ======================================================================================
# --- the jack-o'-lantern ---------------------------------------------------------------
# A squashed sphere of five lobes (seams at these longitudes), each cut into bands that
# narrow toward the poles and sink back from the proud middle, so the pumpkin is round in
# every view. The carved sheet is projected on every front face (x, y -> texel), so the
# face lines up across the blocks, and right behind every carved block sits a thin plate
# of candle light (the "window" texture, projected the same way), so the holes glow from
# any angle. The skeleton's window behind it all is the dark inside.
PR = 3.75                          # the pumpkin sheets span AX +- PR, BOW_Y +- PR
SHEET = 32                         # px
TEX_U = 2 * PR / SHEET             # model units per sheet texel
PA, PB, PD = 3.65, 3.45, 1.75      # half width, half height and front bulge of the pumpkin
LOBES = ((-90.0, -54.0), (-54.0, -18.0), (-18.0, 18.0), (18.0, 54.0), (54.0, 90.0))
BAND_EDGES = (-3.45, -2.8, -1.0, 1.0, 2.8, 3.45)
SEAM_GAP = 0.0
LIGHT_DEPTH = 0.1                  # how far behind a carved face its candle light sits
STEM_BASE, STEM_TIP = (AX + 0.1, BOW_Y + 3.05, 8.55), (AX - 0.45, BOW_Y + 4.25, 9.95)


def squash(y: float) -> float:
    return math.sqrt(max(0.0, 1 - (y / PB) ** 2))


def su(x: float) -> float:
    return round(clamp((x - (AX - PR)) / (2 * PR) * 16, 0, 16), 4)


def sv(y: float) -> float:
    return round(clamp((BOW_Y + PR - y) / (2 * PR) * 16, 0, 16), 4)


def lobe_blocks() -> list[tuple]:
    """(x0, x1, y0, y1, front z) offsets of every block of the five lobes."""
    out = []
    for l0, l1 in LOBES:
        lm = math.radians((l0 + l1) / 2)
        for ya, yb in zip(BAND_EDGES, BAND_EDGES[1:]):
            c = squash((ya + yb) / 2)
            x0 = PA * math.sin(math.radians(l0)) * c + SEAM_GAP / 2
            x1 = PA * math.sin(math.radians(l1)) * c - SEAM_GAP / 2
            out.append((x0, x1, ya, yb, AZ + PD * math.cos(lm) * c))
    return out


def lobe(x0: float, x1: float, y0: float, y1: float, zf: float) -> dict:
    """One block of a lobe, front and back: the front shows the carved sheet and the back
    the plain one, both projected so every block lines up."""
    x0, x1, y0, y1 = AX + x0, AX + x1, BOW_Y + y0, BOW_Y + y1
    zb = 2 * AZ - zf
    depth = min(16.0, (zf - zb) * 2)
    faces = {"south": ("pumpkin", [su(x0), sv(y1), su(x1), sv(y0)]),
             "north": ("pumpkin_back", [su(x1), sv(y1), su(x0), sv(y0)]),
             "east": ("rind", [0, 0, depth, 16]), "west": ("rind", [depth, 0, 0, 16]),
             "up": ("rind", [12, 0, 16, 4]), "down": ("rind", [12, 4, 16, 8])}
    # unshaded: the painted sphere and the rind carry the form, and the pumpkin keeps its
    # orange instead of going brown in the GUI's flat front light
    return box((x0, y0, zb), (x1, y1, zf), "pumpkin", faces=faces, shade=False)


def candle_light(x0: float, x1: float, y0: float, y1: float, zf: float) -> dict | None:
    """The candle light right behind a block's carved front, or None if nothing of the
    face is cut into that block. Unshaded, so it stays as bright as a flame."""
    mask = FACE_MASK
    pad = TEX_U / 2
    hit = any(mask[j][i] for j in range(SHEET) for i in range(SHEET)
              if x0 - pad <= -PR + (i + 0.5) * TEX_U <= x1 + pad and y0 - pad <= PR - (j + 0.5) * TEX_U <= y1 + pad)
    if not hit:
        return None
    x0, x1, y0, y1 = AX + x0, AX + x1, BOW_Y + y0, BOW_Y + y1
    z = zf - LIGHT_DEPTH
    return box((x0, y0, z - 0.02), (x1, y1, z), "window", faces={"south": ("window", [su(x0), sv(y1), su(x1), sv(y0)])},
               skip=("north", "east", "west", "up", "down"), glow=15, shade=False)


def pumpkin() -> list[dict]:
    blocks = lobe_blocks()
    parts = [lobe(*b) for b in blocks]
    parts += [p for p in (candle_light(*b) for b in blocks) if p]
    # a short curled stem leaning out over the lip below the collar
    parts.append(bar(STEM_BASE, STEM_TIP, 0.8, 0.8, "metal",
                     faces=lane_faces("stem", STEM_TIP[1], STEM_BASE[1], sides={"up": dot_uv("stem", 29, 0.3)})))
    tip = (STEM_TIP[0] - 0.25, STEM_TIP[1] + 0.05, STEM_TIP[2] + 0.1)
    parts.append(bar(STEM_TIP, tip, 0.55, 0.55, "metal", faces=lane_faces("stem", tip[1], STEM_TIP[1])))
    return parts


# --- the fire-opal nose (the heart gem) ------------------------------------------------
GEM_SIDE = 2.2
GEM_H = GEM_SIDE * math.sqrt(3) / 2
GEM_DY = -0.1                                  # centroid just below the bow centre
GEM_Z = (9.2, 10.3)                            # body back, painted front
NOSE_MARGIN = 0.9                              # sheet texels of candle light round the gem


def nose_gem() -> list[dict]:
    """A trillion-cut fire opal set point-up as the jack-o'-lantern's nose: a painted
    front over a stepped body, held at its three corners by small iron claws."""
    cx, cy = AX, BOW_Y + GEM_DY
    base, apex = cy - GEM_H / 3, cy + 2 * GEM_H / 3
    zb, zf = GEM_Z
    parts = [box((cx - GEM_SIDE / 2, base, zf - 0.05), (cx + GEM_SIDE / 2, apex, zf), "gem", uv="full",
                 skip=("north", "east", "west", "up", "down"), glow=14, shade=False)]
    side = [7.25, 14.5, 8.75, 15.5]
    for k in range(3):
        ya = base + GEM_H * 0.92 * k / 3
        yb = base + GEM_H * 0.92 * (k + 1) / 3
        hw = GEM_SIDE / 2 * (1 - (yb - base) / GEM_H) - 0.04
        parts.append(box((cx - hw, ya, zb), (cx + hw, yb, zf - 0.05), "gem",
                         faces={s: ("gem", side) for s in ("north", "east", "west", "up", "down")},
                         skip=("south",), glow=14, shade=False))
    rv = GEM_SIDE / math.sqrt(3)
    for ang in (90, 210, 330):
        parts.append(radial((cx, cy), ang, rv - 0.14, rv + 0.3, 0.34, 0.42, "iron", z=zf + 0.02))
    return parts


# --- ember studs round the ring ---------------------------------------------------------
STUD_ANGLES = tuple(60 * k for k in range(6))


def ring_studs() -> list[dict]:
    parts = []
    for ang in STUD_ANGLES:
        a = math.radians(ang)
        parts.append(diamond((AX + BAND_R * math.cos(a), BOW_Y + BAND_R * math.sin(a)), 0.55, BOW_D + 0.24,
                             "ember", ang - 45, glow=6))
    return parts


# --- the bat-wing bit -------------------------------------------------------------------
SHOULDER = (7.0, 24.1)
WRIST = (4.3, 25.75)
THUMB = (3.8, 26.3)
TIPS = ((2.3, 22.9, 0.44), (3.05, 19.9, 0.42), (4.95, 18.35, 0.4))   # finger tips: x, y, bone width
BODY = (7.2, 20.4)                                                   # where the membrane meets the shaft
W_X0, W_X1, W_Y0, W_Y1 = 2.2, 7.2, 17.95, 25.95                      # the membrane plate (4 px per unit)
W_U = (W_X1 - W_X0) * 2                                              # its width in uv units
SAGS = (0.8, 0.7, 0.65)                                              # how deep each scallop dips


def bone(p0, p1, w: float, d: float) -> dict:
    return bar((p0[0], p0[1], AZ), (p1[0], p1[1], AZ), w, d, "metal", faces=lane_faces("iron", p1[1], p0[1]))


def finger(p0, p1, w: float, d: float) -> list[dict]:
    """A bone from p0 ending in a point at p1 (built round its middle, so long bones never
    leave the model range before they are turned)."""
    ang = math.degrees(math.atan2(p1[1] - p0[1], p1[0] - p0[0]))
    length = math.hypot(p1[0] - p0[0], p1[1] - p0[1])
    k = (length - w / 2) / length
    end = (p0[0] + (p1[0] - p0[0]) * k, p0[1] + (p1[1] - p0[1]) * k)
    return [bone(p0, end, w, d), diamond(end, w / math.sqrt(2), d - 0.1, "iron", ang - 45)]


def bat_wing_bit() -> list[dict]:
    """A bat's wing spread from the shaft: the membrane (a painted cut-out) under an iron
    arm, a hooked thumb and three finger bones whose points are the key's teeth."""
    parts = [box((W_X0, W_Y0, AZ - 0.25), (W_X1, W_Y1, AZ + 0.25), "wing",
                 faces={"south": ("wing", [0, 0, W_U, 16]), "north": ("wing", [W_U, 0, 0, 16])},
                 skip=("east", "west", "up", "down"), glow=9, shade=False)]
    parts.append(bone(SHOULDER, WRIST, 0.6, 0.95))
    for x, y, w in TIPS:
        parts += finger(WRIST, (x, y), w, 0.8)
    parts += finger(WRIST, THUMB, 0.42, 0.7)
    parts.append(diamond(WRIST, 0.72, 1.05, "iron"))
    parts.append(diamond(SHOULDER, 0.85, 1.1, "iron"))
    return parts


# --- twisted iron and the harvest moon -------------------------------------------------
TWIST = (13.55, 17.05)
MOON_C = (AX, 27.8)
MOON_R = 1.6                       # the painted disc
MOON_DR = 1.7                      # half the decal square


def twist() -> list[dict]:
    return lane_prism(TWIST[0], TWIST[1], SHAFT_R + 0.08, "twist", cap="twist")


def moon_finial() -> list[dict]:
    """A harvest moon crowning the shaft: a gold disc faced front and back by the painted
    moon (where the bats flit), cradled from the cap by a tapering iron crescent."""
    cx, cy = MOON_C
    uv = {s: dot_uv("gold", cy, 0.5) for s in ("north", "south", "east", "west")}
    uv["up"] = uv["down"] = dot_uv("gold", cy, 0.0)
    parts = prism((cx, cy, AZ), MOON_R - 0.1, 0.7, "metal", axis="z", sides=16, cap="metal", uv=uv, glow=10)
    d = MOON_DR
    parts.append(box((cx - d, cy - d, AZ + 0.37), (cx + d, cy + d, AZ + 0.4), "moon", uv="full",
                     skip=("north", "east", "west", "up", "down"), glow=12, shade=False))
    parts.append(box((cx - d, cy - d, AZ - 0.4), (cx + d, cy + d, AZ - 0.37), "moon",
                     faces={"north": ("moon", [16, 0, 0, 16])}, skip=("south", "east", "west", "up", "down"),
                     glow=12, shade=False))
    steps, a0, a1 = 9, -115.0, 60.0
    for k in range(steps):
        tm = (k + 0.5) / steps
        w = 0.18 + 0.6 * math.sin(math.pi * tm) ** 0.8
        parts.append(arc_segment((cx, cy), MOON_R + w / 2 - 0.05, a0 + (a1 - a0) * k / steps,
                                 a0 + (a1 - a0) * (k + 1) / steps, w, 0.9, "iron", "iron", "iron"))
    return parts


# --- bats circling the moon ------------------------------------------------------------
# Two cut-out planes, one in front of the moon and one behind it, each with its own
# animated texture. A bat flies on the near plane for the low, near half of its orbit and
# on the far plane for the high half, so the moon itself hides it as it goes round the
# back. The planes are squares turned 45 degrees about the key's axis, so in the diagonal
# inventory icon they sit square to the slot, inside the icon's bounds, and the bats fly
# upright there.
BATS_C = (AX, 27.8)                      # the moon's centre
BATS_H = 2.8                             # half the planes' side
BATS_Z = 0.95                            # the planes' distance in front of / behind the moon
BAT_PX = 32 / (2 * BATS_H)               # texels per unit
ORBIT_RX, ORBIT_RY = 2.7, 0.65


def bat_planes() -> list[dict]:
    parts = []
    cx, cy = BATS_C
    h = BATS_H
    for z, tex in ((AZ + BATS_Z, "bats_near"), (AZ - BATS_Z, "bats_far")):
        e = box((cx - h, cy - h, z - 0.01), (cx + h, cy + h, z + 0.01), tex,
                faces={"south": (tex, [0, 0, 16, 16]), "north": (tex, [16, 0, 0, 16])},
                skip=("east", "west", "up", "down"))
        parts.append(turn(e, 45, "z", (cx, cy, z)))
    return parts


# --- a spider hanging from the cap --------------------------------------------------------
SPIDER_X = 10.5
SPIDER_Y = 21.25                          # where the abdomen meets the head


def spider() -> list[dict]:
    """A little iron spider dropping on a silk thread from a hook under the cap, legs
    splayed, two ember eyes."""
    x, y = SPIDER_X, SPIDER_Y
    parts = [box((9.2, 25.5, AZ - 0.16), (x + 0.16, 25.8, AZ + 0.16), "metal", faces=lane_faces("iron", 25.8, 25.5)),
             box((x - 0.05, y + 0.9, AZ - 0.05), (x + 0.05, 25.55, AZ + 0.05), "metal",
                 faces=lane_faces("silk", 25.55, y + 0.9)),
             box((x - 0.5, y, AZ - 0.45), (x + 0.5, y + 1.0, AZ + 0.45), "metal", faces=lane_faces("spider", y + 1.0, y)),
             box((x - 0.32, y - 0.55, AZ - 0.3), (x + 0.32, y + 0.05, AZ + 0.3), "metal",
                 faces=lane_faces("spider", y + 0.05, y - 0.55))]
    for side in (-1, 1):
        for k, (dy, reach, rise, drop) in enumerate(((0.3, 0.75, 0.45, 0.2), (0.05, 0.9, 0.35, -0.35),
                                                     (-0.2, 0.9, 0.25, -0.75), (-0.42, 0.7, 0.15, -1.0))):
            r = reach * (0.8 if side < 0 else 1.0)          # the shaft side stays clear of the shaft
            p0 = (x + side * 0.3, y + dy, AZ)
            knee = (x + side * (0.3 + r * 0.6), y + dy + rise, AZ + 0.05 * (k - 1.5))
            foot = (x + side * (0.3 + r), y + dy + drop, AZ + 0.1 * (k - 1.5))
            parts.append(bar(p0, knee, 0.14, 0.14, "metal", faces=lane_faces("iron", knee[1], p0[1])))
            parts.append(bar(knee, foot, 0.12, 0.12, "metal", faces=lane_faces("iron", knee[1], foot[1])))
    for ex in (-0.13, 0.13):
        parts.append(box((x + ex - 0.07, y - 0.3, AZ + 0.29), (x + ex + 0.07, y - 0.16, AZ + 0.33), "metal",
                         faces=lane_faces("ember", y - 0.16, y - 0.3), skip=("north",), glow=12))
    return parts


# --- the pumpkin vine round the ring ---------------------------------------------------
VINES = ((72.0, 12.0), (108.0, 150.0))                 # (from, to) angles along the outer rim
LEAVES = ((47.0, 1.4), (22.0, 1.05), (132.0, 1.25))    # (angle, size)
VINE_R, VINE_Z = BOW_OUT + 0.12, AZ + 0.95


def vine() -> list[dict]:
    """A green pumpkin vine growing out from under the stem and hugging the ring's outer
    rim down both shoulders, with three leaves and a curled tendril at its end."""
    c = (AX, BOW_Y)
    parts = []
    for a0, a1 in VINES:
        n = max(2, round(abs(a1 - a0) / 14))
        for k in range(n):
            b0 = a0 + (a1 - a0) * k / n
            b1 = a0 + (a1 - a0) * (k + 1) / n
            w = 0.42 - 0.12 * k / n
            parts.append(arc_segment(c, VINE_R, min(b0, b1), max(b0, b1), w, 0.45, "stem", "stem", "stem",
                                     z=VINE_Z))
    for ang, size in LEAVES:
        a = math.radians(ang)
        r = VINE_R + size * 0.45
        p = (AX + r * math.cos(a), BOW_Y + r * math.sin(a))
        parts.append(diamond(p, size, 0.22, "stem", ang - 45 + 12, z=VINE_Z + 0.25))
        parts.append(radial(p, ang + 180, -size * 0.55, size * 0.5, 0.14, 0.3, "stem", z=VINE_Z + 0.3))
    # the tendril: a little curl hanging off the right-hand vine's end
    end = math.radians(VINES[0][1])
    cc = (AX + (VINE_R + 0.45) * math.cos(end), BOW_Y + (VINE_R + 0.45) * math.sin(end))
    for k in range(4):
        parts.append(arc_segment(cc, 0.42, 120 - 70 * (k + 1), 120 - 70 * k, 0.14, 0.2, "stem", "stem", "stem",
                                 z=VINE_Z))
    return parts


def theme() -> list[dict]:
    return (pumpkin() + nose_gem() + ring_studs() + vine() + bat_wing_bit() + twist() + moon_finial()
            + spider())


# ======================================================================================
# 4. TEXTURES
# ======================================================================================

def paint_theme_lanes(img) -> None:
    # pumpkin-orange enamel on the collar, bands and cap
    paint_lane(img, "band", [ENAMEL[4], ENAMEL[3], ENAMEL[2], ENAMEL[1]])
    paint_lane(img, "iron", [METAL[6], METAL[5], METAL[3], METAL[2]])
    paint_lane(img, "ember", [GLOW[5], GLOW[4], GLOW[3]])
    paint_lane(img, "gold", [MOON[4], MOON[3], MOON[2]])
    paint_lane(img, "stem", [STEM[4], STEM[3], STEM[2], STEM[1]])
    paint_lane(img, "silk", ["#e8e0f0", "#a89ab8"])
    paint_lane(img, "spider", [METAL[4], METAL[2]])
    # the ring's inner side faces the pumpkin: warm with its light
    paint_lane(img, "lip_side", [mix(METAL[4], GLOW[2], 0.55), mix(METAL[3], GLOW[1], 0.45),
                                 mix(METAL[2], GLOW[0], 0.35)])
    # twisted iron: diagonal ridges that read as a spiral round the eight facets
    px = img.load()
    c0, w = LANES["twist"]
    for r in range(ATLAS):
        for i in range(w):
            px[c0 + i, r] = rgba((METAL[5], METAL[4], METAL[2], METAL[1])[(i + r) % 4])


def theme_atlas_frame(img, t: float) -> None:
    """The candle's flicker reaches the ember studs."""
    f = flicker(t)
    px = img.load()
    c0, w = LANES["ember"]
    for r in range(ATLAS):
        for i in range(w):
            cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
            px[c0 + i, r] = rgba(mix(cur, GLOW[6] if f > 0.5 else GLOW[1], abs(f - 0.5) * 0.9))


def paint_atlas_base():
    img = canvas(ATLAS, fill=METAL[3])
    paint_skeleton_lanes(img, METAL)
    paint_theme_lanes(img)
    return img


# --- the fire-opal nose -----------------------------------------------------------------

def gem_frame(t: float):
    """A trillion cut, point up: three crown facets lit from the upper left round a split
    table, a dark girdle, white glints, brightening and throwing a star at its flare."""
    p = gem_pulse(t)
    lift = 0.15 + 1.5 * p + 0.25 * flicker(t)
    img = canvas(16)
    px = img.load()
    for ty in range(16):
        for tx in range(16):
            x, y = tx + 0.5, ty + 0.5
            dl = (x - 8) * 0.894 + y * 0.447
            dr = -(x - 8) * 0.894 + y * 0.447
            db = 16 - y
            d = min(dl, dr, db)
            if d < 0:
                continue
            if d < 0.9:
                f = 0.8
            elif d > 2.9:
                f = 4.4 if x < 8 else 3.8
            elif d == dl:
                f = 5.0
            elif d == dr:
                f = 3.2
            else:
                f = 2.3
            px[tx, ty] = rgba(ramp_at(GEM, f + lift * (1.0 if d >= 0.9 else 0.6)))
    for x, y in ((6, 6), (7, 5), (6, 7)):
        px[x, y] = rgba(GEM[7])
    sparkle(img, 7, 7, clamp(p * 1.3 - 0.25), colour="#ffffff", reach=4)
    return img


# --- the carved face ----------------------------------------------------------------------
EYE_L = ((9.4, 4.3), (4.6, 11.3), (14.2, 11.3))      # sheet texels: apex, base left, base right


def in_tri(x: float, y: float, a, b, c) -> bool:
    def s(p, q, r):
        return (p[0] - r[0]) * (q[1] - r[1]) - (q[0] - r[0]) * (p[1] - r[1])
    d1, d2, d3 = s((x, y), a, b), s((x, y), b, c), s((x, y), c, a)
    return not ((d1 < 0 or d2 < 0 or d3 < 0) and (d1 > 0 or d2 > 0 or d3 > 0))


def in_mouth(x: float, y: float) -> bool:
    dx = (x - 16) / 11.2
    if abs(dx) >= 1:
        return False
    top = 21.0 - 2.4 * dx * dx
    bot = 27.0 - 6.6 * dx * dx
    if not top < y < bot:
        return False
    for t0, t1 in ((10.6, 12.8), (19.2, 21.4)):          # two teeth hanging from the lip
        if t0 < x < t1 and y < top + 2.2:
            return False
    return not (14.9 < x < 17.1 and y > bot - 1.9)       # and one standing below


def nose_tri():
    """The gem's triangle in sheet texels, grown by NOSE_MARGIN."""
    cx, cy = 16.0, 16.0 - GEM_DY / TEX_U
    s, h = GEM_SIDE / TEX_U, GEM_H / TEX_U
    k = 1 + NOSE_MARGIN / (h / 3)
    pts = ((cx, cy - 2 * h / 3), (cx - s / 2, cy + h / 3), (cx + s / 2, cy + h / 3))
    return tuple((cx + (px_ - cx) * k, cy + (py_ - cy) * k) for px_, py_ in pts)


def face_mask() -> list[list[bool]]:
    eye_r = tuple((32 - x, y) for x, y in EYE_L)
    nose = nose_tri()
    mask = [[False] * SHEET for _ in range(SHEET)]
    for j in range(SHEET):
        for i in range(SHEET):
            x, y = i + 0.5, j + 0.5
            mask[j][i] = in_tri(x, y, *EYE_L) or in_tri(x, y, *eye_r) or in_tri(x, y, *nose) or in_mouth(x, y)
    return mask


FACE_MASK = face_mask()


def hole_distance(mask) -> list[list[float]]:
    """Distance (texels) from every sheet texel to the nearest carved one."""
    holes = [(i, j) for j in range(SHEET) for i in range(SHEET) if mask[j][i]]
    return [[min(math.hypot(i - a, j - b) for a, b in holes) for i in range(SHEET)] for j in range(SHEET)]


def skin_colour(i: int, j: int) -> str:
    """The pumpkin's skin: lobes lit from the upper left on a lit sphere, dark creases
    along the seams, the space round it (block corners) in shadow."""
    x, y = -PR + (i + 0.5) * TEX_U, PR - (j + 0.5) * TEX_U
    c = squash(y)
    if c < 0.05 or abs(x) >= PA * c:
        return BACKDROP[2]
    lon = math.degrees(math.asin(x / (PA * c)))
    for l0, l1 in LOBES:
        if lon <= l1:
            break
    for seam in (-54.0, -18.0, 18.0, 54.0):
        sx = PA * c * math.sin(math.radians(seam))
        if abs(x - sx) < TEX_U * 0.6:
            return PUMPKIN[1]
        if 0 < sx - x < TEX_U * 1.5:
            return PUMPKIN[2]              # the lobe's shadowed edge before a crease
    s = (lon - l0) / (l1 - l0)
    nx, ny = x / PA, y / PB
    nz = math.sqrt(max(0.0, 1 - nx * nx - ny * ny))
    sph = -0.45 * nx + 0.35 * ny + 0.8 * nz
    lobe_ = math.sin(math.pi * s) ** 0.55
    # kept in the deep oranges (never the candle's yellows) so the carved face reads
    f = 1.7 + 1.9 * sph + 1.1 * lobe_ * (1.2 - 0.6 * s)
    if abs(s - 0.3) < 0.06 and 0.3 < y < 2.6:
        f += 0.8                           # a glossy streak down the lit side of each lobe
    return PUMPKIN[int(clamp(dither_band(f, i, j), 1, 4))]


def pumpkin_sheet():
    """The skin colours, the carved mask and the rims round every hole: the lit cut below
    a hole ("floor") and a dark outline everywhere else."""
    mask = FACE_MASK
    skin = [[skin_colour(i, j) for i in range(SHEET)] for j in range(SHEET)]
    rims = {}

    def hole(i, j):
        return 0 <= i < SHEET and 0 <= j < SHEET and mask[j][i]

    for j in range(SHEET):
        for i in range(SHEET):
            if mask[j][i]:
                continue
            if hole(i, j - 1):
                rims[(i, j)] = "floor"
            elif hole(i - 1, j) or hole(i + 1, j) or hole(i, j + 1):
                rims[(i, j)] = "edge"
    return skin, mask, rims, hole_distance(mask)


def pumpkin_frame(sheet, t: float):
    skin, mask, rims, _ = sheet
    k = clamp(0.2 + 0.8 * flicker(t) + 0.3 * gem_pulse(t))
    img = canvas(SHEET)
    px = img.load()
    for j in range(SHEET):
        for i in range(SHEET):
            if mask[j][i]:
                continue
            kind = rims.get((i, j))
            if kind == "floor":
                c = mix(FLESH[1], FLESH[3], k)
            elif kind == "edge":
                c = mix(PUMPKIN[0], PUMPKIN[2], k * 0.5)
            else:
                c = skin[j][i]
            px[i, j] = rgba(c)
    return img


def window_frame(sheet, t: float):
    """The inside of the pumpkin, in sheet space: dark, except where the candle light
    pours out of the carved face. The light flickers and is hottest low down, where the
    flame itself dances behind the grin."""
    dist = sheet[3]
    f = flicker(t)
    p = gem_pulse(t)
    lean = 0.35 * sway(t)
    img = canvas(SHEET)
    px = img.load()
    fx, fy, fh = lean * 0.8, -2.45, 1.25 + 0.3 * f
    for j in range(SHEET):
        for i in range(SHEET):
            x = -PR + (i + 0.5) * TEX_U
            y = PR - (j + 0.5) * TEX_U
            k = clamp(1.4 - dist[j][i] / 2.0)
            dark = BACKDROP[1] if math.hypot(x, y) > 2.6 else BACKDROP[2]
            g = 3.7 + 2.4 * f - 0.4 * (y + 1.0) + 0.6 * p - 0.25 * abs(x - lean)
            u = (y - fy) / fh
            if -0.22 < u < 1.0:
                hw = 0.55 * (math.sqrt(max(0.0, 1 - (u / 0.22) ** 2)) if u < 0 else (1 - u) ** 0.8)
                off = abs(x - fx - lean * 0.6 * max(0.0, u))
                if off < hw:
                    g = max(g, 6.0 if off < hw * 0.5 and u < 0.7 else 5.4)
            px[i, j] = rgba(mix(dark, ramp_at(GLOW, clamp(g, 0, 6)), k))
    return img


def paint_pumpkin() -> None:
    sheet = pumpkin_sheet()
    save_animation(animate(lambda t: pumpkin_frame(sheet, t), FRAMES), "pumpkin", frametime=FRAMETIME)
    save_animation(animate(lambda t: window_frame(sheet, t), FRAMES), "window", frametime=FRAMETIME)
    back = canvas(SHEET)
    for j in range(SHEET):
        for i in range(SHEET):
            back.putpixel((i, j), rgba(sheet[0][j][i]))
    save(back, "pumpkin_back")
    rind = canvas(16)
    for j in range(16):
        for i in range(16):
            c = (PUMPKIN[3], PUMPKIN[2], PUMPKIN[2], PUMPKIN[1])[min(3, i // 2)]
            if i >= 12:
                c = PUMPKIN[4] if j < 4 else PUMPKIN[1]
            rind.putpixel((i, j), rgba(c))
    save(rind, "rind")


# --- the bat's wing ---------------------------------------------------------------------

def seg_dist(p, a, b) -> float:
    ax, ay = b[0] - a[0], b[1] - a[1]
    t = clamp(((p[0] - a[0]) * ax + (p[1] - a[1]) * ay) / (ax * ax + ay * ay))
    return math.hypot(p[0] - a[0] - t * ax, p[1] - a[1] - t * ay)


def in_poly(x: float, y: float, poly) -> bool:
    inside = False
    for (x0, y0), (x1, y1) in zip(poly, poly[1:] + poly[:1]):
        if (y0 > y) != (y1 > y) and x < x0 + (y - y0) * (x1 - x0) / (y1 - y0):
            inside = not inside
    return inside


def wing_outline() -> list[tuple[float, float]]:
    """The membrane's outline: along the arm to the wrist, down the first finger, then a
    scallop curving in toward the wrist between each finger tip and the next, and one
    last scallop back to the shaft."""
    pts = [(W_X1, SHOULDER[1] + 0.2), WRIST]
    chain = [t[:2] for t in TIPS] + [BODY]
    for (a, b), sag in zip(zip(chain, chain[1:]), SAGS):
        mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
        length = math.dist(a, b)
        nx, ny = -(b[1] - a[1]) / length, (b[0] - a[0]) / length
        if nx * (WRIST[0] - mx) + ny * (WRIST[1] - my) < 0:
            nx, ny = -nx, -ny
        c = (mx + nx * sag * 2, my + ny * sag * 2)         # a quadratic curve dipping `sag`
        pts.append(a)
        for k in range(1, 8):
            t = k / 8
            pts.append(((1 - t) ** 2 * a[0] + 2 * (1 - t) * t * c[0] + t * t * b[0],
                        (1 - t) ** 2 * a[1] + 2 * (1 - t) * t * c[1] + t * t * b[1]))
    pts.append(BODY)
    return pts


def paint_wing() -> None:
    """The membrane, glowing like a wing held against the moon: dark along the bones,
    ember-orange across the middle of each panel, with a dark hem along the scallops."""
    img = canvas(32)
    poly = wing_outline()
    trailing = poly[poly.index(TIPS[0][:2]):]
    bones = [(SHOULDER, WRIST), (WRIST, TIPS[0][:2]), (WRIST, TIPS[1][:2]), (WRIST, TIPS[2][:2]),
             ((W_X1 - 0.3, SHOULDER[1]), (W_X1 - 0.3, BODY[1]))]
    for j in range(32):
        for i in range(round((W_X1 - W_X0) * 4)):
            x, y = W_X0 + (i + 0.5) / 4, W_Y1 - (j + 0.5) / 4
            if not in_poly(x, y, poly):
                continue
            db = min(seg_dist((x, y), a, b) for a, b in bones)
            de = min(seg_dist((x, y), a, b) for a, b in zip(trailing, trailing[1:]))
            f = 1.3 + 5.4 * clamp((min(db, de * 1.3) - 0.1) / 0.8) ** 0.8
            if de < 0.3:
                f = min(f, 2.0)
            img.putpixel((i, j), rgba(ramp_at(WING, f)))
    save(img, "wing")


# --- the harvest moon and its bats ------------------------------------------------------
CRATERS = ((21.5, 10.5, 1.9), (10.5, 19.5, 2.3), (17.5, 23.0, 1.3), (12.5, 9.5, 1.1), (23.5, 19.0, 1.0))


def moon_base():
    img = canvas(32)
    s = 32 / (2 * MOON_DR)
    R = MOON_R * s
    px = img.load()
    for j in range(32):
        for i in range(32):
            dx, dy = i + 0.5 - 16, j + 0.5 - 16
            d = math.hypot(dx, dy)
            if d > R:
                continue
            nz = math.sqrt(max(0.0, 1 - (d / R) ** 2))
            f = 1.9 + 2.2 * nz + 0.9 * (-dx - dy) / R
            for cx, cy, cr in CRATERS:
                ex, ey = i + 0.5 - cx, j + 0.5 - cy
                e = math.hypot(ex, ey)
                if e < cr:
                    f -= 0.55 if ex + ey < 0 else 0.25
                elif e < cr + 0.9 and ex + ey > 0:
                    f += 0.35
            px[i, j] = rgba(MOON[int(clamp(dither_band(f, i, j), 0, len(MOON) - 1))])
    return img


BAT_UP = ["X.......X", "XX.X.X.XX", ".XXXXXXX.", "...XXX..."]
BAT_DOWN = ["...X.X...", ".XXXXXXX.", "XX.XXX.XX", "X.......X"]
BAT_FAR_UP = ["X.....X", "XX.X.XX", ".XXXXX."]
BAT_FAR_DOWN = ["..X.X..", "XXXXXXX", "X.....X"]
BATS = ((0.0, 0.0), (0.5, 0.37))          # (orbit phase, bob phase) of each bat


def bats_frame(t: float, near_plane: bool):
    """Two bats circling the moon, flapping, drawn on the plane they are on: the near one
    for the low half of the orbit, the far one (smaller, hazier) for the high half."""
    img = canvas(32)
    px = img.load()
    frame = round(t * FRAMES)
    c45 = math.sqrt(0.5)
    for n, (phase, bob) in enumerate(BATS):
        th = 2 * math.pi * (t + phase)
        dx = ORBIT_RX * math.cos(th)
        dy = ORBIT_RY * math.sin(th) + 0.18 * math.sin(2 * math.pi * (3 * t + bob))
        near = math.sin(th) < 0
        if near != near_plane:
            continue
        up = (frame // 2 + n) % 2 == 0
        stamp = (BAT_UP if up else BAT_DOWN) if near else (BAT_FAR_UP if up else BAT_FAR_DOWN)
        lx, ly = (dx + dy) * c45, (dy - dx) * c45          # into the turned plane
        u, v = (lx + BATS_H) * BAT_PX, (BATS_H - ly) * BAT_PX
        x0, y0 = round(u - len(stamp[0]) / 2), round(v - len(stamp) / 2)
        for j, line in enumerate(stamp):
            for i, ch in enumerate(line):
                xx, yy = x0 + i, y0 + j
                if ch == "X" and 0 <= xx < 32 and 0 <= yy < 32:
                    px[xx, yy] = rgba(BAT if near else BAT_FAR)
    return img


def paint_moon() -> None:
    save(moon_base(), "moon")
    save_animation(animate(lambda t: bats_frame(t, True), FRAMES), "bats_near", frametime=FRAMETIME)
    save_animation(animate(lambda t: bats_frame(t, False), FRAMES), "bats_far", frametime=FRAMETIME)


def textures() -> None:
    base = paint_atlas_base()
    save_animation(animate(lambda t: atlas_frame(base, t), FRAMES), "metal", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    paint_pumpkin()
    paint_wing()
    paint_moon()


# ======================================================================================
# 5. MODELS
# ======================================================================================

def models() -> dict:
    parts = skeleton() + theme()
    # the bat planes are mostly empty, so they stay out of the icon's fit
    return {"main": model(parts + bat_planes(), transforms(parts))}
