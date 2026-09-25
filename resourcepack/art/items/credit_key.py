"""Credit Key: the platinum and diamond-cyan premium store key (one of the 19 JoshyMC crate keys).

The bow is the set's stepped ring in platinum, its band inlaid with a cyan circuit trace
that links six glowing square nodes, and set round the outside with five diamond-cyan
crystal points held in platinum cups (the longest hanging at the foot). Inside, the window
is a holographic credit coin: rainbow foil engraved with guilloche rings inside a beaded
border, and raised over it a holographic platinum credit sign (a C open toward +X, crossed
top and bottom by the sign's vertical stroke) round a brilliant-cut
diamond-cyan crystal held by the six claws. A platinum collar joins the ring to a graphite
circuit-board shaft run with cyan traces, pads and vias; halfway up a platinum microchip
with a glowing die and legs grips it, a brilliant-cut crystal crowns the tip, and on its
-X side the bit is a circuit-board plate framed in platinum rails and set with a raised card
chip, with three platinum edge-connector prongs tipped with cyan crystal contacts.

Animation (one 3.2 s loop): a holographic sheen (a white glint with a pink leading fringe and
a violet trailing one) sweeps up the whole key from the bow to the tip; the crystal flares as
it crosses the bow and then slowly dims while the crystal points glow with it; a pulse of
light leaves the crystal and runs up the circuit traces, round the ring and on up the shaft; the credit sign shimmers
through rainbow hues and the coin's foil turns its rainbow, a bright diagonal sheen crossing
it as the glint passes.

The skeleton and atlas machinery (section 1) are copied from january_key.py, so all 19 keys
share proportions, grip, display transforms and the bow-to-tip glint.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, mix, model, place, prism, rgba, save_animation, shine,
                     sparkle, turn)

ID = "credit_key"
NAME = "Credit Key"
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
    "emblem": (42, 4), "crystal": (46, 4), "chip": (50, 4), "panel": (54, 10),
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#eefeff", width=8.0, strength=0.88, angle=-90.0, pause=0.4)   # bow -> tip
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
# 2. THEME PALETTE: Credit, platinum and diamond cyan (#55FFFF)
# ======================================================================================
METAL = PLATINUM = ["#211b33", "#3c3757", "#615e7f", "#8c8ba6", "#b6b8ca", "#dcdee8", "#fbfcfe"]
CYAN = ["#03293a", "#064e66", "#0a7f96", "#10b1c2", "#2cdfe6", "#55ffff", "#b4ffff", "#f0ffff"]
GEM = ["#021a30", "#043f66", "#0874a0", "#12abcc", "#33dcee", "#72fbff", "#ccffff", "#ffffff"]
BOARD = ["#060c1a", "#0b1829", "#11253a", "#18364d", "#214a63"]      # graphite circuit board
FOIL = ["#0c1f3c", "#123056", "#1a4470", "#245a88", "#3272a0"]       # the coin's dark foil
HOLO = ["#55ffff", "#7fc4ff", "#b09aff", "#ff94e8", "#ffe7a6", "#8fffd0"]   # rainbow round the wheel
PINK_FRINGE, VIOLET_FRINGE = "#ffb0f2", "#a9b4ff"

# ======================================================================================
# 3. THEME PARTS: the credit sign, the crystal, circuit nodes, crystal points, the
#    circuit-board bit, the microchip and the brilliant-cut crown
# ======================================================================================
PAD_ANGLES = (0, 60, 120, 180, 240, 300)            # circuit nodes on the band
CRYSTAL_POINTS = ((30, 6.85, 0.82), (150, 6.85, 0.82), (210, 6.85, 0.82), (330, 6.85, 0.82),
                  (270, 7.5, 0.96))                  # (angle, reach, width) round the rim
C_R, C_W, C_D = 2.55, 0.78, 1.3                      # the credit sign's C
C_OPEN = 52                                         # the C opens toward +X, from -40 to +40 degrees
PLATE_Y0, PLATE_Y1 = 21.0, 25.0                     # the bit's circuit-board plate
PRONGS = ((4.5, 3.0, 0.86), (5.55, 1.8, 0.8), (6.6, 2.5, 0.8))   # (x, length, width) under it
CHIP_Y0, CHIP_Y1, CHIP_R = 14.45, 16.35, 1.5        # the microchip round the shaft
CROWN = ((26.2, 0.3), (26.55, 0.6), (27.05, 1.05), (27.55, 1.35), (27.85, 1.35), (28.25, 1.0),
         (28.6, 0.6))                               # (height, radius) of the brilliant, bottom up


def credit_sign() -> list[dict]:
    """The platinum credit sign round the crystal: a C open toward +X, crossed top and
    bottom by the sign's vertical stroke."""
    c = (AX, BOW_Y)
    parts = []
    a, step = C_OPEN, (360 - 2 * C_OPEN) / 14
    for k in range(14):
        a0 = a + k * step
        parts.append(arc_segment(c, C_R, a0, a0 + step, C_W, C_D, "emblem", "emblem", "emblem", glow=4))
    for ang in (90, 270):
        parts.append(radial(c, ang, SOCKET_R - 0.1, WINDOW_R - 0.05, 0.56, C_D - 0.1, "emblem", glow=4))
    return parts


def crystal_gem() -> list[dict]:
    """The brilliant-cut crystal: an octagonal body plus a painted front and back."""
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


def ring_tech() -> list[dict]:
    """Square circuit nodes on the band's trace and crystal points in platinum cups round
    the rim."""
    c = (AX, BOW_Y)
    parts = []
    for ang in PAD_ANGLES:
        a = math.radians(ang)
        parts.append(diamond((AX + BAND_R * math.cos(a), BOW_Y + BAND_R * math.sin(a)), 0.62, BOW_D + 0.24,
                             "band", ang))
    for ang, reach, w in CRYSTAL_POINTS:
        parts.append(radial(c, ang, BOW_OUT - 0.35, BOW_OUT + 0.3, w + 0.36, 1.7, "band"))
        parts += spike(c, ang, BOW_OUT + 0.15, reach, w, 1.1, "crystal", glow=10)
    return parts


def circuit_bit() -> list[dict]:
    """The bit: a circuit-board plate framed by platinum rails, with three edge-connector
    prongs tipped with crystal contacts hanging from it."""
    parts = bit_plate(BIT_X0, PLATE_Y0, PLATE_Y1, 2.2)
    for y0, y1 in ((PLATE_Y1 - 0.1, PLATE_Y1 + 0.45), (PLATE_Y0 - 0.35, PLATE_Y0 + 0.1)):
        parts.append(radial((BIT_X1, (y0 + y1) / 2), 180, 0.0, BIT_X1 - BIT_X0 + 0.25, y1 - y0, 2.6, "band",
                            flip=True, sides={"up": dot_uv("band", y1, 0.1)}))
    x0, x1, y0, y1 = CHIP_INLAY                 # the raised card chip
    parts.append(box((x0, y0, AZ - 1.25), (x1, y1, AZ + 1.25), "metal",
                     faces={"south": ("metal", panel_uv(x0, x1, y1, y0)),
                            "north": ("metal", panel_uv(x0, x1, y1, y0, True)),
                            "east": ("metal", lane_uv("step", y1, y0)), "west": ("metal", lane_uv("step", y1, y0)),
                            "up": ("metal", dot_uv("band", y1)), "down": ("metal", dot_uv("step", y0))}))
    for x, length, w in PRONGS:                  # edge-connector prongs with crystal contacts
        y_top = PLATE_Y0 - 0.2
        y_bot = y_top - length
        parts.append(box((x - w / 2, y_bot + 0.55, AZ - 0.7), (x + w / 2, y_top, AZ + 0.7), "metal",
                         faces=lane_faces("band", y_top, y_bot + 0.55)))
        parts.append(diamond((x, y_bot + 0.6), w * 0.74, 1.7, "crystal", 45, glow=12))
    return parts


def microchip() -> list[dict]:
    """A square platinum-rimmed chip gripping the shaft between its bands: a glowing die on
    the front and back and three legs out of each side."""
    y0, y1, r = CHIP_Y0, CHIP_Y1, CHIP_R
    parts = [box((AX - r, y0, AZ - r), (AX + r, y1, AZ + r), "metal",
                 faces=lane_faces("chip", y1, y0, sides={"up": dot_uv("band", y1), "down": dot_uv("band", y0)}))]
    yc = (y0 + y1) / 2
    for sx in (-1, 1):
        for dy in (-0.55, 0.0, 0.55):
            xa, xb = sorted((AX + sx * (r - 0.05), AX + sx * (r + 0.42)))
            parts.append(box((xa, yc + dy - 0.14, AZ - 0.95), (xb, yc + dy + 0.14, AZ + 0.95), "metal",
                             faces=lane_faces("band", yc + dy + 0.14, yc + dy - 0.14)))
    h = 0.62
    for z0, z1 in ((AZ + r - 0.05, AZ + r + 0.14), (AZ - r - 0.14, AZ - r + 0.05)):
        parts.append(box((AX - h, yc - h, z0), (AX + h, yc + h, z1), "metal",
                         faces=lane_faces("crystal", yc + h, yc - h), glow=12))
    return parts


def brilliant_crown() -> list[dict]:
    """A brilliant-cut crystal crowning the shaft: a pointed pavilion rising from the cap to
    a wide girdle, then the crown facets and a flat table."""
    parts = []
    for (h0, r0), (h1, r1) in zip(CROWN, CROWN[1:]):
        parts += lane_prism(h0, h1, (r0 + r1) / 2, "crystal", cap="crystal", glow=11)
    return parts


def theme() -> list[dict]:
    return credit_sign() + crystal_gem() + ring_tech() + circuit_bit() + microchip() + brilliant_crown()


# ======================================================================================
# 4. TEXTURES
# ======================================================================================
SKELETON_TONES = {                 # platinum (see METAL_TONES) except the band: cyan crystal (inner -> outer,
    "face0": (CYAN[3], CYAN[1], CYAN[2]),     # a trace grooved down its middle), and the graphite shaft
    "face1": (CYAN[4], CYAN[1], CYAN[3]),
    "face2": (CYAN[5], CYAN[1], CYAN[4]),
    "shaft": (BOARD[4], BOARD[3], BOARD[2], BOARD[1]),
}
PANEL = [                          # the bit plate's front: 10 x 8 texels, x 4.0..7.2, y 25..21: a card chip
    "5555555554",                  # (platinum contacts split by dark dividers) wired into the board
    "5.tt.....2",
    "5t.65a56.2",
    "5t.aaaaa.2",
    "5..65a56t2",
    "5P.....tt2",
    "5ttttttP.2",
    "3222222221",
]
CHIP_INLAY = (4.96, 6.56, 22.5, 24.0)   # x0, x1, y0, y1 of the raised card chip on the bit plate
TRACE_PX: list[tuple[int, int]] = []     # every atlas pixel of a circuit trace (the pulses run along them)
PAD_PX: list[tuple[int, int]] = []       # the pads and vias (brighter, and they flash as a pulse passes)


def paint_traces(img) -> None:
    """Cyan traces: one down the middle of the ring's band (linking its nodes), and a
    board of traces, bridges and vias up the graphite shaft."""
    px = img.load()
    TRACE_PX.clear()
    PAD_PX.clear()
    for lane in FACE_LANES:
        c, _ = LANES[lane]
        for r in range(ATLAS):
            TRACE_PX.append((c + 1, r))
    c0, w = LANES["shaft"]
    top, bottom = row_of(SHAFT_Y1), row_of(SHAFT_Y0 + 0.2)
    for r in range(top, bottom + 1):
        TRACE_PX.append((c0 + 1, r))
        if r not in (13, 14, 30):
            TRACE_PX.append((c0 + 3, r))
    for r in (11, 19, 35):
        TRACE_PX.append((c0 + 2, r))
    for x, r in ((1, 9), (3, 16), (1, 24), (3, 34), (0, 19), (2, 12), (0, 36)):
        PAD_PX.append((c0 + x, r))
    c0, _ = LANES["panel"]
    r0 = row_of(PLATE_Y1)
    for j, line in enumerate(PANEL):
        for i, ch in enumerate(line):
            if ch == "t":
                TRACE_PX.append((c0 + i, r0 + j))
            elif ch == "P":
                PAD_PX.append((c0 + i, r0 + j))
    ring = {LANES[lane][0] + 1 for lane in FACE_LANES}
    for x, r in TRACE_PX:
        px[x, r] = rgba(CYAN[1] if x in ring else CYAN[3])
    for x, r in PAD_PX:
        px[x, r] = rgba(CYAN[4])


def paint_theme_lanes(img) -> None:
    for lane, tones in SKELETON_TONES.items():
        paint_lane(img, lane, tones)
    paint_lane(img, "emblem", [PLATINUM[6], PLATINUM[5], PLATINUM[4], PLATINUM[3]])
    paint_lane(img, "crystal", [CYAN[7], CYAN[6], CYAN[5], CYAN[3]])
    paint_lane(img, "chip", [PLATINUM[4], BOARD[3], BOARD[2], PLATINUM[2]])
    px = img.load()
    c0, w = LANES["chip"]
    for r in (row_of(CHIP_Y1), row_of(CHIP_Y0) - 0):
        for i in range(w):
            px[c0 + i, min(ATLAS - 1, r)] = rgba(PLATINUM[5] if r == row_of(CHIP_Y1) else PLATINUM[2])
    # the bit panel: a platinum frame round a graphite board (traces are added below)
    pal = {"b": BOARD[2], ".": BOARD[2], "t": BOARD[2], "P": BOARD[2], "a": PLATINUM[2]}
    pal.update({str(i): PLATINUM[i] for i in range(7)})
    c0, _ = LANES["panel"]
    r0 = row_of(PLATE_Y1)
    for j, line in enumerate(PANEL):
        for i, ch in enumerate(line):
            px[c0 + i, r0 + j] = rgba(pal[ch])
    paint_traces(img)


PULSE_SPAN = (-2.5, 29.0)          # the pulses run from the bow's foot to the crown
PULSES = 1                         # pulses per loop
PULSE_T0 = GEM_PEAK - (BOW_Y - PULSE_SPAN[0]) / (PULSE_SPAN[1] - PULSE_SPAN[0]) / PULSES   # one leaves the gem as it flares


def blend(px, x: int, y: int, colour, k: float) -> None:
    cur = "#%02x%02x%02x" % px[x, y][:3]
    px[x, y] = rgba(mix(cur, colour, clamp(k)))


def height_of(r: int) -> float:
    return Y_TOP - (r + 0.5) / RPU


def holo_at(f: float) -> str:
    """The rainbow wheel, f in turns (wraps)."""
    f = (f % 1.0) * len(HOLO)
    i = int(f)
    return mix(HOLO[i % len(HOLO)], HOLO[(i + 1) % len(HOLO)], f - i)


def theme_atlas_frame(img, t: float) -> None:
    """The holographic fringes on the glint, pulses running up the circuit traces, the
    credit sign shimmering through the rainbow and the crystals glowing with the gem."""
    lead = shine(img, (t + 0.03) % 1.0, colour=PINK_FRINGE, width=3.0, strength=0.45, angle=-90.0, pause=0.4)
    lead = shine(lead, (t - 0.028) % 1.0, colour=VIOLET_FRINGE, width=3.0, strength=0.4, angle=-90.0, pause=0.4)
    img.paste(lead)
    px = img.load()
    # circuit pulses: a bright head with a fading tail below it, climbing the key
    span = PULSE_SPAN[1] - PULSE_SPAN[0]
    heads = [PULSE_SPAN[0] + (((t - PULSE_T0) * PULSES) % 1.0) * span]
    ring = {LANES[lane][0] + 1 for lane in FACE_LANES}
    for x, r in TRACE_PX + PAD_PX:
        y = height_of(r)
        k = 0.0
        for hy in heads:
            d = hy - y
            if -0.6 <= d <= 10.0:
                k = max(k, math.exp(-max(0.0, d) / 4.0))
        if k > 0.03:
            blend(px, x, r, "#f4ffff" if k > 0.6 else CYAN[6], 0.3 + 0.7 * k)
            if x in ring:                         # the lit trace spills light onto the crystal band
                for nx in (x - 1, x + 1):
                    blend(px, nx, r, CYAN[6], 0.55 * k)
    # the credit sign's holographic shimmer: rainbow bands flowing up its rows
    c0, w = LANES["emblem"]
    for r in range(ATLAS):
        tint = holo_at(r / 22.0 - t)
        for i in range(w):
            blend(px, c0 + i, r, tint, 0.5 - 0.06 * i)
    # the gem's flare floods the crystal points and nodes round the bow with light
    p = gem_pulse(t)
    c0, w = LANES["crystal"]
    for r in range(ATLAS):
        k = 0.6 * p * max(0.0, 1 - abs(height_of(r) - BOW_Y) / 6.5)
        if k > 0.01:
            for i in range(w):
                blend(px, c0 + i, r, "#f4ffff", k)


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
    """The crystal's face: an octagon with a table facet and crown facets lit from the
    left, a white highlight and flecks of rainbow fire, brightening and throwing a star at
    its flare."""
    p = gem_pulse(t)
    lift = 0.2 + 1.5 * p
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
                f = 0.4
            elif o <= 3.6:
                f = 3.9 if dx * lx + dy * ly > 0.4 else 3.3
            else:
                ang = math.atan2(dy, dx)
                sector = round(ang / (math.pi / 4)) * (math.pi / 4)
                f = 2.9 + 1.7 * (math.cos(sector) * lx + math.sin(sector) * ly)
                if (round(ang / (math.pi / 8)) % 2) and o < 5.5:      # star facets between the crown's
                    f += 0.45
            px[tx, ty] = rgba(ramp_at(GEM, f + lift * (1.0 if o <= 7.0 else 0.6)))
    for x, y in ((5, 5), (6, 5), (5, 6)):
        px[x, y] = rgba(GEM[7])
    px[10, 10] = rgba(ramp_at(GEM, 5 + lift))
    # rainbow fire: flecks that come and go round the facets
    for i, (x, y) in enumerate(((11, 4), (3, 10), (12, 11), (8, 2), (4, 13))):
        k = 0.5 + 0.5 * math.cos(2 * math.pi * (t * 2 + i / 5))
        if k > 0.55:
            cur = "#%02x%02x%02x" % px[x, y][:3]
            px[x, y] = rgba(mix(cur, HOLO[(i * 2 + 3) % len(HOLO)], 0.85 * k))
    sparkle(img, 6, 6, clamp(p * 1.3 - 0.25), colour="#ffffff", reach=4)
    return img


BEAD_R = 13.6                      # the coin's beaded border (window texels from the centre)
COIN_SHEEN = sweep_time(BOW_Y)     # the diagonal sheen crosses the coin with the glint


def window_frame(t: float):
    """The holographic credit coin: dark foil engraved with guilloche rings, tinted by a
    rainbow that turns round it, a beaded platinum border, the crystal's halo breathing
    at its heart and a bright diagonal sheen crossing it as the glint passes."""
    p = gem_pulse(t)
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            dx, dy = x + 0.5 - 16, y + 0.5 - 16
            r = math.hypot(dx, dy)
            ang = math.atan2(dy, dx)
            # dark foil lit from the upper left, engraved with guilloche rosette lines that
            # catch the rainbow as it turns round the coin
            f = 1.3 - 1.6 * (dx + dy) / 32
            if r > BEAD_R + 1.2:
                f = 0.2
            c = FOIL[int(clamp(dither_band(f, x, y), 0, len(FOIL) - 1))]
            g = math.sin(r * 1.55 + 1.1 * math.sin(ang * 8))
            if r <= BEAD_R - 0.8 and g > 0.5:
                tint = holo_at(ang / (2 * math.pi) + r / 36 - t)
                c = mix(FOIL[4], tint, 0.74 if g > 0.8 else 0.5)
            halo = (0.35 + 0.65 * p) * max(0.0, 1 - r / 9.5) ** 1.6
            if halo > 0.05:
                c = mix(c, GEM[4], min(0.8, halo))
            px[x, y] = rgba(c)
    # the beaded border
    for i in range(24):
        a = 2 * math.pi * i / 24
        bx, by = 16 + BEAD_R * math.cos(a), 16 + BEAD_R * math.sin(a)
        xi, yi = int(bx), int(by)
        lit_ = (-(bx - 16) - (by - 16)) / (2 * BEAD_R)
        px[xi, yi] = rgba(PLATINUM[5] if lit_ > -0.2 else PLATINUM[3])
    # the diagonal holographic sheen
    run = 0.6
    s = ((t - COIN_SHEEN + 0.12) % 1.0) / run
    if s < 1.0:
        centre = -8 + s * 80
        for y in range(32):
            for x in range(32):
                d = abs((x + y) - centre)
                if d < 5:
                    k = (1 - d / 5) * 0.55
                    blend(px, x, y, holo_at((x - y) / 48 + 0.1) if d > 2 else "#f4ffff", k)
    return img


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
