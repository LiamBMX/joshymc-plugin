"""Stockpile Key: the riveted steel crate key of industry (one of the 19 JoshyMC crate keys).

The bow is a stepped steel ring whose recessed rim is one piece with a ring of blued-iron
gear teeth, framing a riveted crate: a square blued-iron frame (turned so it stands square
in the inventory) with a cross brace, steel rivets and oak planks behind, a redstone wire
inlaid along each brace, and a redstone gem clasped by four claws at its heart. Behind the
crate a dark machine gear turns in a red glow. A flared collar joins the ring to a steel
shaft wrapped in yellow-and-black hazard stripes at the grip, with a redstone wire running
up its front and back and a spur gear round its middle; a caged redstone lamp on a hex nut
crowns it, and on its -X side the bit is three stone-bodied pistons with oak heads pushed
out to different lengths, each with a redstone lamp on its flank.

Animation (one 3.2 s loop): a glossy glint sweeps up the whole key from the bow to the tip;
the gear teeth tick round the bow eight times a loop (the teeth are cells switched on and
off in the "gear" atlas) while the machine gear in the window turns with them; the redstone
gem flares as the glint passes, lighting the brace wires and the plank seams, and then a
pulse of power climbs the shaft wire through the piston lamps to the lamp at the top.

---------------------------------------------------------------------------------------
KEY SET SKELETON: all 19 crate keys share these proportions (model units)
---------------------------------------------------------------------------------------
  frame   upright on x = 8, z = 8; front = +Z (the face the inventory shows)
  bow     a stepped ring centred at (8, BOW_Y = 3.5): outer radius 5.4 (10.8 across,
          y -1.9..8.9), inner radius 3.5; a recessed outer rim (0.5 wide, 2.0 deep), the
          main band (2.6 deep, z 6.7..9.3) and a raised inner lip (0.6 wide, 3.0 deep),
          20 segments each. Inside: a recessed window (the theme's animated scene), the
          theme's emblem, and at the heart a socket (bezel + claws) holding the theme's
          gem, which stands proudest of everything
  collar  two octagonal discs, y 8.4..10.2, joining the ring's top to the shaft
  shaft   octagonal, apothem 1.15 (2.3 thick), y 10..25.5, bands at y 13 and 17.6,
          a cap at 25.4..26.3; the theme may crown it with a finial (up to y ~29.5)
  bit     on the -X side of the upper shaft, y 18..25.2: the theme's bit and teeth
  grip    (8, 10.6, 8): the fist closes on the shaft just above the bow
  size    SIZE = 0.64 holds it at about 1 block; carried upright like a sceptre
  gui     GUI_ROTATION tilts the diagonal key back a little so its faces catch the light
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, box, canvas, display, mix, model, place, prism, rgba, save, save_animation, shine,
                     sparkle, turn)

ID = "stockpile_key"
NAME = "Stockpile Key"
KIND = "sword"
COUNTERPART = "item/trial_key"

# ======================================================================================
# 1. SHARED KEY SKELETON (copied from january_key.py; identical for every key in the set)
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
CLAW_ANGLES = tuple(45 + 90 * k for k in range(4))  # theme: four claws between the crate's braces
GRIP = (8.0, 10.6, 8.0)
SIZE = 0.64
GUI_ROTATION = (-25, 20, -45)

# --- timing: every animation loops in 64 ticks ------------------------------------------
FRAMES, FRAMETIME = 32, 2          # atlases and window
GEM_FRAMES, GEM_FRAMETIME = 16, 4  # gem and crate glow (interpolated)

# --- the metal atlases --------------------------------------------------------------------
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
    # theme (42-63): blued iron (crate frame, piston bodies), rivet heads, the redstone
    # signal, the piston bodies' fronts, the shaft cog and the piston rail
    "iron": (42, 3), "frame": (45, 4), "rivet": (49, 2), "signal": (51, 3), "piston": (54, 5),
    "cog": (59, 3), "panel": (62, 2),
}
# The second atlas, "gear", holds the bow's gear teeth: one lane pair per tick phase.
PHASES = 4                        # cells per tooth pitch: a tooth is two cells, a gap two
GEAR_LANES = {}
for _p in range(PHASES):
    GEAR_LANES[f"tooth{_p}"] = (_p * 4, 4)            # tooth fronts, root -> tip
    GEAR_LANES[f"tip{_p}"] = (16 + _p * 3, 3)         # tooth tips and flanks, front -> back
LANES.update(GEAR_LANES)
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#fffaf0", width=8.0, strength=0.85, angle=-90.0, pause=0.4)   # bow -> tip
TRAIL = dict(colour="#ffffff", width=2.5, strength=0.7, angle=-90.0, pause=0.4)
TRAIL_LAG = 0.05


def clamp(v, lo=0.0, hi=1.0):
    return max(lo, min(hi, v))


def tex_of(lane: str) -> str:
    """The atlas a lane lives in."""
    return "gear" if lane in GEAR_LANES else "metal"


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
               flip: bool = False, sides: dict | None = None, tex: str | None = None) -> dict:
    """Faces for a box whose local +Y end sits at height y_hi and -Y end at y_lo once it is
    turned: front/back sample `front`, the local +X/-X sides `east`/`west`, each at the rows
    of its own height, so the sweep reaches every face as it passes that height. `sides`
    overrides single faces with a uv (e.g. a dot_uv) or a (texture, uv) pair. Each face
    uses the atlas its lane lives in."""
    east, west = east or front, west or front
    mid = (y_hi + y_lo) / 2
    faces = {"south": (front, lane_uv(front, y_hi, y_lo, flip)), "north": (front, lane_uv(front, y_hi, y_lo, not flip)),
             "east": (east, lane_uv(east, y_hi, y_lo)), "west": (west, lane_uv(west, y_hi, y_lo, True)),
             "up": (front, dot_uv(front, mid)), "down": (front, dot_uv(front, mid))}
    out = {side: (tex or tex_of(lane), uv) for side, (lane, uv) in faces.items()}
    for side, spec in (sides or {}).items():
        out[side] = spec if isinstance(spec, tuple) else (tex or tex_of(front), spec)
    return out


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
    e = box((cx - w / 2, cy + r0, z - d / 2), (cx + w / 2, cy + r1, z + d / 2), tex_of(lane),
            faces=lane_faces(lane, cy + r1 * math.sin(a), cy + r0 * math.sin(a), east, west, flip, sides), **kw)
    return turn(e, ang - 90, "z", (cx, cy, z))


def diamond(c, side: float, d: float, lane: str, ang: float = 45.0, z: float = AZ, **kw) -> dict:
    """A square turned by ang about Z (45 = corners up/down/left/right)."""
    cx, cy = c
    h = side * 0.35
    e = box((cx - side / 2, cy - side / 2, z - d / 2), (cx + side / 2, cy + side / 2, z + d / 2), tex_of(lane),
            faces=lane_faces(lane, cy + h, cy - h), **kw)
    return turn(e, ang, "z", (cx, cy, z))


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
    e = box((cx + r - w / 2, cy - half, z - d / 2), (cx + r + w / 2, cy + half, z + d / 2), tex_of(lane),
            faces=lane_faces(lane, y_hi, y_lo, east, west, flip, sides), **kw)
    return turn(e, math.degrees(mid), "z", (cx, cy, z))


def lane_prism(y0: float, y1: float, r: float, lane: str, cap: str = "cap", x: float = AX, z: float = AZ,
               **kw) -> list[dict]:
    """An octagonal rod along Y whose facets sample `lane` at their height."""
    side = lane_uv(lane, y1, y0)
    back = lane_uv(lane, y1, y0, True)
    uv = {"south": side, "north": back, "east": side, "west": back,
          "up": dot_uv(cap, y1), "down": dot_uv(cap, y0)}
    return prism((x, (y0 + y1) / 2, z), r, y1 - y0, tex_of(lane), cap=tex_of(lane), uv=uv, **kw)


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
    """The bezel behind the heart gem and claws over its rim."""
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
    return bow_ring() + bow_window(glow=WINDOW_GLOW) + gem_socket(CLAW_ANGLES) + collar() + shaft()


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
# 2. THEME PALETTE: Stockpile, industrial steel (#A0A0A0), blued iron, redstone and oak
# ======================================================================================
METAL = STEEL = ["#15161b", "#30323a", "#52555d", "#85888d", "#b1b2b0", "#d7d7d0", "#f5f4ec"]
IRON = ["#0e0f15", "#1b1e27", "#2a2e39", "#3c414e", "#545a68", "#737a89", "#9ba2b0"]
STONE = ["#2b2c30", "#414348", "#5a5c61", "#727479", "#8d8f93"]            # the piston bodies
HAZARD = ["#16140f", "#e8b422", "#ffd75a"]                                   # the grip's warning wrap
RED = ["#220308", "#4c0610", "#840c12", "#bb1712", "#ea2a17", "#ff5b3a", "#ff9e82", "#ffe6dc"]
OAK = ["#3a220f", "#5c3a1b", "#835a2d", "#a87b43", "#c99c5f", "#e3c188", "#f4deb0"]

# ======================================================================================
# 3. THEME PARTS: gear teeth, crate emblem, redstone gem, redstone wire, shaft cog,
#    piston teeth and a caged redstone lamp
# ======================================================================================
WINDOW_GLOW = 6
TEETH = 10                                  # gear teeth round the bow
CELLS = TEETH * PHASES                      # the teeth ring is cut into cells that switch on/off
CELL = 360 / CELLS                          # 9 degrees
TOOTH_R0, TOOTH_R1 = 5.28, 6.4
TOOTH_D = 1.9                               # z 7.05..8.95, just behind the rim's front
HIDDEN_CELLS = {CELLS - 1, 0, 1}            # buried in the collar
STEPS = 8                                   # the gear ticks one cell forward 8 times a loop

CRATE_R = 3.45                              # the crate: a square turned 45 degrees, corners on the lip
FRAME_W, FRAME_D = 0.55, 2.0
BRACE_W, BRACE_D = 0.5, 1.9
PANEL_R, PANEL_D = 3.3, 1.1                 # the plank panel behind the frame (corners under it)

TRACE = ((13.35, 17.25),)                   # the redstone wire's run between the bands
HAZARD_Y = (10.3, 12.4)                     # the hazard-striped grip wrap
TRACE_W = 0.5
COG_Y = (14.85, 15.75)                      # a spur gear round the shaft

PISTONS = ((18.1, 20.2, 0.45), (20.45, 22.55, 1.35), (22.8, 24.9, 0.85))   # (y0, y1, extension)
BODY_X0, BODY_X1 = 5.0, 7.1
BODY_D = 2.2
HEAD_T, HEAD_PAD = 1.05, 0.12
ARM_W = 0.7
LAMP_Y = 27.95


def gear_teeth() -> list[dict]:
    """The bow's gear teeth: a ring of cells round the rim, each switched on or off by its
    phase lane in the "gear" atlas, so the teeth tick round in the texture."""
    parts = []
    c = (AX, BOW_Y)
    r = (TOOTH_R0 + TOOTH_R1) / 2
    w = TOOTH_R1 - TOOTH_R0
    for k in range(CELLS):
        if k in HIDDEN_CELLS:
            continue
        p = k % PHASES
        mid = 90 + k * CELL
        y = BOW_Y + r * math.sin(math.radians(mid))
        flank = dot_uv(f"tip{p}", y, 0.99)
        parts.append(arc_segment(c, r, mid - CELL / 2, mid + CELL / 2, w, TOOTH_D, f"tooth{p}", f"tip{p}",
                                 f"tip{p}", sides={"up": flank, "down": flank}, skip=("west",)))
    return parts


def crate_emblem() -> list[dict]:
    """A riveted crate framed by the bow: a square frame (turned 45 degrees, so it stands
    square in the inventory) with its corners clamped under the lip, a cross brace from
    corner to corner, rivets, and a plank panel behind."""
    parts = []
    c = (AX, BOW_Y)
    corners = [(AX + CRATE_R * math.cos(a), BOW_Y + CRATE_R * math.sin(a))
               for a in (0, math.pi / 2, math.pi, 1.5 * math.pi)]
    edge = CRATE_R * math.sqrt(2)
    for i in range(4):
        p0, p1 = corners[i], corners[(i + 1) % 4]
        ang = math.degrees(math.atan2(p1[1] - p0[1], p1[0] - p0[0]))
        parts.append(radial(p0, ang, -FRAME_W / 2, edge + FRAME_W / 2, FRAME_W, FRAME_D, "frame"))
    for ang in (0, 90, 180, 270):
        parts.append(radial(c, ang, 1.2, CRATE_R, BRACE_W, BRACE_D, "frame"))
        # a redstone wire inlaid along each brace: it lights the moment the gem flares
        parts.append(radial(c, ang, SOCKET_R - 0.1, CRATE_R - 0.35, 0.24, BRACE_D + 0.14, "signal", glow=9))
    for ang in (45, 135, 225, 315):                        # rivets at the frame's mid-edges
        a = math.radians(ang)
        rr = CRATE_R / math.sqrt(2)
        parts.append(diamond((AX + rr * math.cos(a), BOW_Y + rr * math.sin(a)), 0.5, FRAME_D + 0.3, "rivet"))
    for ang in (0, 90, 180, 270):                          # and along the braces
        a = math.radians(ang)
        parts.append(diamond((AX + 2.45 * math.cos(a), BOW_Y + 2.45 * math.sin(a)), 0.5, BRACE_D + 0.3,
                             "rivet"))
    s = PANEL_R / math.sqrt(2)
    panel = box((AX - s, BOW_Y - s, AZ - PANEL_D / 2), (AX + s, BOW_Y + s, AZ + PANEL_D / 2), "crate",
                faces={"south": ("crate", [0, 0, 16, 16]), "north": ("crate", [16, 0, 0, 16])},
                skip=("east", "west", "up", "down"))
    parts.append(turn(panel, 45, "z", (AX, BOW_Y, AZ)))
    return parts


def redstone_gem() -> list[dict]:
    """A glowing octagonal redstone gem: a body plus a painted front and back."""
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


def redstone_trace() -> list[dict]:
    """A redstone wire inlaid up the shaft's front and back facets, between the bands."""
    parts = []
    for y0, y1 in TRACE:
        for zc, hide in ((AZ + SHAFT_R, "north"), (AZ - SHAFT_R, "south")):
            parts.append(box((AX - TRACE_W / 2, y0, zc - 0.1), (AX + TRACE_W / 2, y1, zc + 0.1), "metal",
                             faces=lane_faces("signal", y1, y0), skip=(hide,), glow=9))
    return parts


def shaft_cog() -> list[dict]:
    """A spur gear round the middle of the shaft: a disc and eight teeth."""
    y0, y1 = COG_Y
    parts = lane_prism(y0, y1, 1.6, "cog", cap="cog")
    for ang in (0, 45, 90, 135):
        e = box((AX - 2.25, y0 + 0.1, AZ - 0.32), (AX + 2.25, y1 - 0.1, AZ + 0.32), "metal",
                faces=lane_faces("cog", y1 - 0.1, y0 + 0.1))
        parts.append(turn(e, ang, "y", (AX, (y0 + y1) / 2, AZ)))
    return parts


def piston(y0: float, y1: float, ext: float) -> list[dict]:
    """One piston tooth: a stone body bolted to the shaft with an iron strap round it, its
    oak-faced head pushed out on a steel arm, and a redstone lamp on its front that lights
    as the signal passes."""
    parts = []
    body = lane_faces("piston", y1, y0, east="iron", west="iron",
                      sides={"up": dot_uv("iron", y1, 0.0), "down": dot_uv("iron", y0, 0.9)})
    parts.append(box((BODY_X0, y0, AZ - BODY_D / 2), (BODY_X1, y1, AZ + BODY_D / 2), "metal", faces=body,
                     skip=("east",)))
    ym = (y0 + y1) / 2
    # an iron strap round the body's head end, proud of it all round
    sx0, sx1 = BODY_X0 - 0.05, BODY_X0 + 0.45
    parts.append(box((sx0, y0 - 0.08, AZ - BODY_D / 2 - 0.1), (sx1, y1 + 0.08, AZ + BODY_D / 2 + 0.1), "metal",
                     faces=lane_faces("frame", y1 + 0.08, y0 - 0.08,
                                      sides={"west": lane_uv("frame", y1, y0), "up": dot_uv("frame", y1, 0.0),
                                             "down": dot_uv("frame", y0, 0.9)}), skip=("east",)))
    hx1 = BODY_X0 - ext
    if ext > 0.05:
        parts.append(box((hx1 - 0.05, ym - ARM_W / 2, AZ - ARM_W / 2), (BODY_X0, ym + ARM_W / 2, AZ + ARM_W / 2),
                         "metal", faces=lane_faces("band", ym + ARM_W / 2, ym - ARM_W / 2), skip=("east", "west")))
    hx0 = hx1 - HEAD_T
    hz = BODY_D / 2 + HEAD_PAD
    side = ("piston_side", [0, 0, 16, 16])
    parts.append(box((hx0, y0 - HEAD_PAD, AZ - hz), (hx1, y1 + HEAD_PAD, AZ + hz),
                     "piston_face", faces={"west": ("piston_face", [0, 0, 16, 16]),
                                           "east": ("piston_face", [16, 0, 0, 16]),
                                           "south": side, "north": ("piston_side", [16, 0, 0, 16]),
                                           "up": ("piston_side", [0, 0, 16, 16], 90),
                                           "down": ("piston_side", [0, 0, 16, 16], 90)}))
    lx = (sx1 + BODY_X1) / 2 - 0.15
    parts.append(box((lx - 0.36, ym - 0.36, AZ - BODY_D / 2 - 0.14), (lx + 0.36, ym + 0.36, AZ + BODY_D / 2 + 0.14),
                     "metal", faces=lane_faces("signal", ym + 0.36, ym - 0.36), glow=10))
    return parts


def piston_bit() -> list[dict]:
    """The bit: an iron backing plate on the shaft's -X side carrying three piston teeth
    pushed out to different lengths, like a key's bitting."""
    parts = bit_plate(6.4, 17.85, 25.2, 1.8)
    for y0, y1, ext in PISTONS:
        parts += piston(y0, y1, ext)
    return parts


def hex_nut(y0: float, y1: float, r: float, lane: str) -> list[dict]:
    """A hexagonal nut round the axis: three turned slabs."""
    parts = []
    h = r * math.sqrt(3) / 2
    for ang in (0, 60, 120):
        e = box((AX - r / 2, y0, AZ - h), (AX + r / 2, y1, AZ + h), "metal",
                faces=lane_faces(lane, y1, y0), skip=("east", "west"))
        parts.append(turn(e, ang, "y", (AX, (y0 + y1) / 2, AZ)))
    return parts


def lamp_finial() -> list[dict]:
    """A caged redstone lamp crowning the shaft: a hex nut on the cap, a glowing lamp
    block held by four iron corner posts between a base plate and a top plate, and a
    rivet on top."""
    parts = hex_nut(26.2, 26.95, 1.25, "band")
    y0, y1 = LAMP_Y - 0.8, LAMP_Y + 0.8
    s = 0.72
    parts.append(box((AX - s, y0, AZ - s), (AX + s, y1, AZ + s), "metal",
                     faces=lane_faces("signal", y1, y0), glow=13))
    for dx in (-1, 1):
        for dz in (-1, 1):
            x, z = AX + dx * (s + 0.02), AZ + dz * (s + 0.02)
            parts.append(box((x - 0.24, y0 - 0.05, z - 0.24), (x + 0.24, y1 + 0.05, z + 0.24), "metal",
                             faces=lane_faces("iron", y1, y0)))
    for ya, yb, r in ((26.9, y0 + 0.05, 1.05), (y1 - 0.05, y1 + 0.35, 1.1)):
        parts.append(box((AX - r, ya, AZ - r), (AX + r, yb, AZ + r), "metal",
                         faces=lane_faces("frame", yb, ya, sides={"up": dot_uv("frame", yb, 0.0)})))
    parts.append(diamond((AX, y1 + 0.55), 0.5, 0.5, "rivet"))
    return parts


def theme() -> list[dict]:
    return (gear_teeth() + crate_emblem() + redstone_gem() + redstone_trace() + shaft_cog() + piston_bit()
            + lamp_finial())


# ======================================================================================
# 4. TEXTURES
# ======================================================================================
SIGNAL_T0 = GEM_PEAK + 0.06                  # the redstone signal leaves the collar...
SIGNAL_Y0, SIGNAL_Y1 = 9.5, LAMP_Y           # ...climbs the shaft...
SIGNAL_SPEED = 62.0                          # ...at this many units per loop
SIGNAL_TAU = 0.1                             # how fast the wire dims behind the pulse
LAMP_TAU = 0.28


def gear_step(t: float) -> int:
    return int(t * STEPS) % STEPS


def signal_level(y: float, t: float) -> float:
    """0..1: how strongly the redstone at height y is powered at loop phase t (a pulse
    climbs from the collar to the lamp and each part dims behind it)."""
    y = clamp(y, SIGNAL_Y0, SIGNAL_Y1)
    arrive = SIGNAL_T0 + (y - SIGNAL_Y0) / SIGNAL_SPEED
    dt = (t - arrive) % 1.0
    tau = LAMP_TAU if y > LAMP_Y - 1.2 else SIGNAL_TAU
    return math.exp(-dt / tau)


def signal_colour(level: float, x: int = 0, y: int = 0) -> str:
    """Unpowered redstone is a dull brick red; powered it runs scarlet to white-hot."""
    f = 2.0 + 5.0 * level
    return RED[int(clamp(dither_band(f, x, y), 0, len(RED) - 1))]


def paint_theme_lanes(img) -> None:
    # the gear's outer rim is blued iron, one piece with the teeth
    for lane, tones in (("rim0", (3, 2)), ("rim1", (4, 4)), ("rim2", (6, 5)), ("rim_side", (5, 4, 3))):
        paint_lane(img, lane, [IRON[i] for i in tones])
    paint_lane(img, "iron", [IRON[5], IRON[4], IRON[3]])
    paint_lane(img, "frame", [IRON[6], IRON[5], IRON[4], IRON[3]])
    paint_lane(img, "rivet", [STEEL[6], STEEL[4]])
    paint_lane(img, "signal", [RED[2], RED[2], RED[1]])
    paint_lane(img, "piston", [STEEL[4], STEEL[3], STEEL[3], STEEL[3], STEEL[2]])
    paint_lane(img, "cog", [STEEL[5], STEEL[4], STEEL[3]])
    paint_lane(img, "panel", [STEEL[3], STEEL[2]])
    px = img.load()
    # the piston bodies' fronts: cobbled stone, lit along the top, shadowed underneath
    c0, w = LANES["piston"]
    rng = random.Random(17)
    for y0, y1, _ in PISTONS:
        r0, r1 = row_of(y1), row_of(y0)
        for r in range(r0, r1 + 1):
            for i in range(w):
                f = 2.6 - 0.35 * i + rng.uniform(-0.9, 0.9)
                if r == r0:
                    f += 1.2
                elif r == r1:
                    f -= 1.3
                px[c0 + i, r] = rgba(STONE[int(clamp(round(f), 0, len(STONE) - 1))])
    # a hazard wrap round the grip: diagonal warning stripes on the shaft above the collar
    c0, w = LANES["shaft"]
    for r in range(row_of(HAZARD_Y[1]), row_of(HAZARD_Y[0]) + 1):
        for i in range(w):
            on = (i + r) % 5 < 3
            px[c0 + i, r] = rgba((HAZARD[2] if i == 0 else HAZARD[1]) if on else HAZARD[0])


def theme_atlas_frame(img, t: float) -> None:
    """The redstone signal climbs the wire, the piston lamps and the lamp crystal; the
    gem's flare throws red light over the crate's steel."""
    px = img.load()
    c0, w = LANES["signal"]
    for r in range(ATLAS):
        y = Y_TOP - (r + 0.5) / RPU
        level = signal_level(y, t)
        for i in range(w):
            px[c0 + i, r] = rgba(signal_colour(level * (1.0 if i < w - 1 else 0.7), i, r))
    p = gem_pulse(t)
    if p < 0.03:
        return
    for lane in ("frame", "rivet"):
        c0, w = LANES[lane]
        for r in range(ATLAS):
            y = Y_TOP - (r + 0.5) / RPU
            k = 0.35 * p * max(0.0, 1 - abs(y - BOW_Y) / 4.5)
            if k <= 0.01:
                continue
            for i in range(w):
                cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
                px[c0 + i, r] = rgba(mix(cur, RED[5], k))


def paint_atlas_base():
    img = canvas(ATLAS, fill=METAL[3])
    paint_skeleton_lanes(img, METAL)
    paint_theme_lanes(img)
    return img


def paint_gear_base():
    """The teeth: dark blued iron fronts with a bright tip edge, tips lit at the front."""
    img = canvas(ATLAS)
    for p in range(PHASES):
        paint_lane(img, f"tooth{p}", [IRON[4], IRON[4], IRON[5], IRON[6]])
        paint_lane(img, f"tip{p}", [IRON[6], IRON[5], IRON[4]])
    return img


def gear_frame(base, t: float):
    """One gear frame: the phases whose cells are gaps at this tick go transparent, then
    the same glint as the metal atlas sweeps over what is left."""
    img = base.copy()
    px = img.load()
    s = gear_step(t)
    for p in range(PHASES):
        if (p + s) % PHASES < PHASES // 2:
            continue
        for lane in (f"tooth{p}", f"tip{p}"):
            c, w = LANES[lane]
            for y in range(ATLAS):
                for i in range(w):
                    px[c + i, y] = (0, 0, 0, 0)
    img = shine(img, t, **SWEEP)
    return shine(img, (t - TRAIL_LAG) % 1.0, **TRAIL)


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


def gem_frame(t: float):
    """The redstone gem's face: an octagon with a table facet, crown facets lit from the
    left and a white highlight; dull brick red at rest, blazing scarlet at its flare."""
    p = gem_pulse(t)
    lift = -1.2 + 2.8 * p
    img = canvas(16)
    px = img.load()
    lx, ly = -0.93, -0.36
    for ty in range(16):
        for tx in range(16):
            dx, dy = tx + 0.5 - 8, ty + 0.5 - 8
            ax, ay = abs(dx), abs(dy)
            o = max(ax, ay, (ax + ay) / math.sqrt(2))
            if o > 8:
                continue
            if o > 7.0:
                f = 0.6
            elif o <= 3.6:
                f = 4.2 if dx * lx + dy * ly > 0.4 else 3.5
            else:
                ang = math.atan2(dy, dx)
                sector = round(ang / (math.pi / 4)) * (math.pi / 4)
                f = 3.0 + 1.7 * (math.cos(sector) * lx + math.sin(sector) * ly)
            px[tx, ty] = rgba(ramp_at(RED, f + lift * (1.0 if o <= 7.0 else 0.6)))
    for x, y in ((5, 5), (6, 5), (5, 6)):
        px[x, y] = rgba(mix(RED[6], RED[7], clamp(p * 1.5)))
    px[10, 10] = rgba(ramp_at(RED, 4.5 + lift))
    sparkle(img, 6, 6, clamp(p * 1.3 - 0.25), colour="#fff2ea", reach=4)
    return img


INNER_TEETH = 12


def window_frame(t: float):
    """The machine behind the crate: a dark iron gear ticking round in step with the bow's
    teeth, redstone light glowing between its teeth, flaring with the gem."""
    p = gem_pulse(t)
    rot = -gear_step(t) * (360 / INNER_TEETH) * 2 / STEPS      # two pitches a loop, like the teeth
    pitch = 360 / INNER_TEETH
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            mx = ((x + 0.5) / 16 - 1) * WINDOW_R
            my = (1 - (y + 0.5) / 16) * WINDOW_R
            r = math.hypot(mx, my)
            ang = math.degrees(math.atan2(my, mx))
            local = ((ang - rot) % pitch) / pitch                 # 0..1 across one pitch
            half = 0.23 + 0.12 * clamp((3.4 - r) / 0.6)           # teeth taper to the tip
            in_tooth = abs(local - 0.5) < half and r < 3.35
            if r < 2.75 or in_tooth:
                lit_edge = in_tooth and abs(local - 0.5) > half - 0.09 and local < 0.5
                c = IRON[3] if lit_edge else (IRON[1] if r < 2.75 else IRON[2])
                if r < 2.75 and abs(r - 2.45) < 0.12:
                    c = IRON[3]
            else:
                glow = (0.45 + 0.55 * p) * clamp(1 - (r - 2.8) / 0.9)
                c = mix(RED[1], RED[5], glow)
            px[x, y] = rgba(c)
    return img


def crate_frame(t: float):
    """The crate's plank panel (turned with the panel, so the planks run level in the
    inventory): oak planks with nail heads, redstone light leaking through the seams."""
    p = gem_pulse(t)
    img = canvas(32)
    px = img.load()
    rng = random.Random(11)
    grain = [[rng.random() for _ in range(32)] for _ in range(32)]
    for y in range(32):
        plank, j = divmod(y, 8)
        for x in range(32):
            if j == 7:
                c = mix(OAK[0], RED[4], 0.25 + 0.6 * p)
            else:
                f = 3.5 - (plank % 2) * 0.6 - (0.8 if j == 6 else 0) + (0.6 if j == 0 else 0)
                if grain[y][x] < 0.18:
                    f -= 0.7
                c = OAK[int(clamp(round(f), 0, len(OAK) - 1))]
            px[x, y] = rgba(c)
    return img


def paint_piston_textures() -> None:
    """The piston heads: oak planks framed in iron (the face) and their sides, which run
    from the steel-bound outer edge through the oak to a dark iron back."""
    face = canvas(16)
    px = face.load()
    rng = random.Random(3)
    for y in range(16):
        for x in range(16):
            edge = min(x, y, 15 - x, 15 - y)
            if edge == 0:
                c = STEEL[5] if (x == 0 or y == 0) else STEEL[2]
            elif edge == 1:
                c = STEEL[3]
            else:
                plank, j = divmod(y - 2, 4)
                c = OAK[4] if plank % 2 == 0 else OAK[3]
                if j == 3:
                    c = OAK[1]
                elif j == 0:
                    c = mix(c, OAK[5], 0.5)
                elif rng.random() < 0.14:
                    c = OAK[2]
            px[x, y] = rgba(c)
    for x, y in ((3, 3), (12, 3), (3, 12), (12, 12)):          # nail heads
        px[x, y] = rgba(STEEL[1])
    save(face, "piston_face")
    side = canvas(16)
    px = side.load()
    for y in range(16):
        for x in range(16):
            if x < 3:
                c = STEEL[6] if x == 0 else STEEL[4]
            elif x < 12:
                c = OAK[5] if x == 3 else (OAK[4] if (y // 4) % 2 == 0 else OAK[3])
                if y % 4 == 3:
                    c = OAK[2]
            else:
                c = IRON[4] if x == 12 else IRON[2]
            if y in (0, 15):
                c = mix(c, STEEL[1], 0.6)
            px[x, y] = rgba(c)
    save(side, "piston_side")


def textures() -> None:
    base = paint_atlas_base()
    save_animation(animate(lambda t: atlas_frame(base, t), FRAMES), "metal", frametime=FRAMETIME)
    gear = paint_gear_base()
    save_animation(animate(lambda t: gear_frame(gear, t), FRAMES), "gear", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(window_frame, FRAMES), "window", frametime=FRAMETIME)
    save_animation(animate(crate_frame, GEM_FRAMES), "crate", frametime=GEM_FRAMETIME, interpolate=True)
    paint_piston_textures()


# ======================================================================================
# 5. MODELS
# ======================================================================================

def models() -> dict:
    parts = skeleton() + theme()
    return {"main": model(parts, transforms(parts))}
