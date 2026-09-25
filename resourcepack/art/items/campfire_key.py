"""Campfire Key: the charred-wood and ember-orange crate key of the Campfire crate (one of the 19 JoshyMC crate keys).

The bow is the set's stepped ring, forged bronze at the rim and lip round a band of charred
wood split by glowing ember cracks, framing a recessed window of firelight. Inside it a
campfire burns: two bark logs cross behind the heart in an X, their burning ends poking out
through the ring's lower rim, three tongues of flame rise over the gem, a bed of glowing
coals lies below it, and an ember gem (a fire opal) sits clasped by six bronze claws at the
crossing; the logs stand dark against the window's blazing glow. Flames lick out of the
ring's shoulders, four ember studs are set in the band, a bronze collar
joins it to an ember-orange lacquered octagonal shaft, scorched black where it leaves the
fire, with bronze bands and a mid-shaft ring ringed with little flames; the bit on its -X side is a bronze rail carrying three stacked firewood logs
as its teeth (each with a burning end, the top one licked by a flame), and a bronze brazier
crowns the tip with a flame of its own.

Animation (one 3.2 s loop): a warm glint sweeps up the whole key from the bow to the tip;
the ember gem flares as it passes and smoulders on, flickering; every flame dances (heat
bands roll up each tongue, its tip flickering between orange and red, and sparks shoot up
it); the coals, cracks, embers in the soot and burning log ends breathe; and embers rise:
through the glowing window over the fire, and as sparks drifting up the shaft and the
ring's sides.

The skeleton and atlas machinery (section 1) are copied from january_key.py, so all 19 keys
share proportions, grip, display transforms and the bow-to-tip glint.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, box, canvas, display, mix, model, place, prism, rgba, save_animation, shine,
                     sparkle, turn)

ID = "campfire_key"
NAME = "Campfire Key"
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
BIT_X0 = 5.3                                        # the bit plate's outer edge (the log teeth run out of it)
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
    "log": (42, 4), "ember": (46, 4), "wood": (50, 4), "panel": (54, 10),
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#ffe6b8", width=8.0, strength=0.8, angle=-90.0, pause=0.4)   # bow -> tip
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
# 2. THEME PALETTE: Campfire, charred wood, forged bronze and ember orange (#E25822)
# ======================================================================================
BRONZE = ["#1e0a07", "#42180d", "#6d2c13", "#9b481b", "#c86d27", "#eb9a3e", "#ffd07a"]
CHAR = ["#0c0606", "#180c0a", "#26140f", "#361d14", "#4a291b", "#603722", "#7a4a2c"]
LOG = ["#140a07", "#28160e", "#3e2416", "#583520", "#76492b", "#95633a"]
EMBER = ["#3a0904", "#7c1706", "#b92f0b", "#e25822", "#ff8a2a", "#ffbf4a", "#ffe79a", "#fffbe2"]
FLAME = ["#4a0a05", "#851606", "#bb2b09", "#e25822", "#ff7f24", "#ffa935", "#ffd062", "#fff0a8", "#fffbea"]
GEM = ["#3a0a03", "#7a1a05", "#c2360b", "#f06418", "#ff9230", "#ffc04e", "#ffe38a", "#fffae0"]
LACQUER = ["#ffa45a", "#ec6c2a", "#c54a1a", "#8c2d11"]      # the shaft: ember-orange lacquer
WOOD = ["#2c190e", "#4a2e19", "#6c4526", "#8f5f35", "#b27d48", "#d09c63"]
GLOW = ["#2a0605", "#4a0c06", "#721607", "#9e2409", "#c8390d", "#e25822", "#f77a26", "#ff9e33"]

# Every skeleton lane: the ring's band fronts are charred wood (inner -> outer), the rims,
# lips, collar and bands forged bronze; the shaft is charred wood (lit edge -> shadow).
SKELETON_TONES = {
    "face0": (CHAR[3], CHAR[2], CHAR[2]), "face1": (CHAR[4], CHAR[3], CHAR[2]), "face2": (CHAR[5], CHAR[4], CHAR[3]),
    "rim0": (BRONZE[2], BRONZE[1]), "rim1": (BRONZE[4], BRONZE[3]), "rim2": (BRONZE[6], BRONZE[5]),
    "lip0": (BRONZE[6], BRONZE[5]), "lip1": (BRONZE[5], BRONZE[5]), "lip2": (BRONZE[4], BRONZE[5]),
    "rim_side": (BRONZE[5], BRONZE[4], BRONZE[3]), "lip_side": (BRONZE[3], BRONZE[2], BRONZE[1]),
    "step": (BRONZE[3], BRONZE[2]),
    "shaft": (LACQUER[0], LACQUER[1], LACQUER[2], LACQUER[3]), "band": (BRONZE[6], BRONZE[5], BRONZE[4], BRONZE[3]),
    "bezel": (BRONZE[1], BRONZE[3], BRONZE[2]), "cap": (BRONZE[5], BRONZE[5]),
}

# ======================================================================================
# 3. THEME PARTS: crossed logs, flames, coals, ember gem, firewood bit, brazier finial
# ======================================================================================
LOG_ANGLES = (215, 325)          # each log runs from its burning end outside the lower rim
LOG_R = 0.66                     # up through the heart to under the lip on the far side
LOG_OUT, LOG_IN = 7.0, 3.3
INNER_FLAMES = ((90, 1.3, 2.3, 1.7, 0.0), (56, 1.35, 1.95, 1.25, 34.0), (124, 1.35, 1.95, 1.25, -34.0))
OUTER_FLAMES = ((22, 2.9, 1.25, 38.0, 32.0), (48, 2.2, 1.0, 66.0, 18.0))   # (ring angle, length, width, heading, bend)
STUD_ANGLES = (0, 180, 252, 288)
COALS = ((270, 2.6, 0.78), (245, 2.75, 0.58), (295, 2.75, 0.58))
RAIL_X0, RAIL_Y0, RAIL_Y1 = BIT_X0, 19.3, 25.2   # the bronze rail the firewood rides on
TEETH = ((24.35, 3.4), (22.3, 2.3), (20.25, 2.9))       # (height, length) of each log tooth
TOOTH_R = 0.6
MID_RING = (14.7, 15.5)
SCORCH = (10.0, 12.6)            # soot on the shaft above the collar          # the flame-ringed band halfway up the shaft
BRAZIER_Y = 26.2


def blend(px, x: int, y: int, colour, k: float) -> None:
    if k > 0 and 0 <= x < ATLAS and 0 <= y < ATLAS:
        cur = "#%02x%02x%02x" % px[x, y][:3]
        px[x, y] = rgba(mix(cur, colour, min(1.0, k)))


def _hash(*v) -> float:
    return (math.sin(sum(x * m for x, m in zip(v, (12.9898, 78.233, 37.719, 4.581)))) * 43758.5453) % 1.0


# --- flames ----------------------------------------------------------------------------
# A flame tongue is stacked bars along a gently curving path, each narrower than the one
# below, capped by a turned-square tip. Every face shows the animated "flame" texture at the
# rows of its own place along the tongue (v 16 = the base, v 0 = the tip), so all tongues
# burn hot at the root and flicker red at the tip whatever their height or angle.
FLAME_SEGS = ((0.0, 0.34, 1.0), (0.3, 0.58, 0.84), (0.54, 0.8, 0.6), (0.76, 0.94, 0.36))


def path_point(c, ang: float, length: float, bend: float, f: float) -> tuple[float, float]:
    """The point a fraction f along a flame path from c, heading ang and turning by bend
    degrees over its whole length."""
    x, y = c
    n = 24
    for i in range(n):
        a = math.radians(ang + bend * (i + 0.5) / n * f)
        x += math.cos(a) * length * f / n
        y += math.sin(a) * length * f / n
    return x, y


def flame_faces(f0: float, f1: float) -> dict:
    v0, v1 = round((1 - f1) * 16, 4), round((1 - f0) * 16, 4)
    return {"south": ("flame", [0, v0, 16, v1]), "north": ("flame", [16, v0, 0, v1]),
            "east": ("flame", [12, v0, 16, v1]), "west": ("flame", [4, v0, 0, v1]),
            "up": ("flame", [6, v0, 10, min(16, v0 + 1)]), "down": ("flame", [6, max(0, v1 - 1), 10, v1])}


def flame_tongue(c, ang: float, length: float, width: float, depth: float, bend: float = 0.0, z: float = AZ,
                 glow: int = 14) -> list[dict]:
    parts = []
    for f0, f1, wk in FLAME_SEGS:
        px_, py_ = path_point(c, ang, length, bend, f0)
        heading = ang + bend * (f0 + f1) / 2
        w, d = width * wk, depth * (1 - 0.4 * f0)
        e = box((px_ - w / 2, py_, z - d / 2), (px_ + w / 2, py_ + (f1 - f0) * length * 1.06, z + d / 2), "flame",
                faces=flame_faces(f0, f1), glow=glow, shade=False)
        parts.append(turn(e, heading - 90, "z", (px_, py_, z)))
    s = width * FLAME_SEGS[-1][2] * 0.72
    tx, ty = path_point(c, ang, length, bend, 0.93)
    d = depth * 0.55
    e = box((tx - s / 2, ty - s / 2, z - d / 2), (tx + s / 2, ty + s / 2, z + d / 2), "flame",
            faces={side: ("flame", [5, 0, 11, 2]) for side in ("north", "south", "east", "west", "up", "down")},
            glow=glow, shade=False)
    parts.append(turn(e, ang + bend - 45, "z", (tx, ty, z)))
    return parts


# --- logs ------------------------------------------------------------------------------

def log_prism(c, ang: float, r0: float, r1: float, rad: float, lane: str, cap: str, z: float = AZ,
              **kw) -> list[dict]:
    """An octagonal log from radius r0 to r1 out of c at ang (front plane); its facets
    sample `lane` at their height and its ends show `cap`."""
    cx, cy = c
    a = math.radians(ang)
    y_hi, y_lo = cy + r1 * math.sin(a), cy + r0 * math.sin(a)
    side = lane_uv(lane, y_hi, y_lo)
    back = lane_uv(lane, y_hi, y_lo, True)
    uv = {"south": side, "north": back, "east": side, "west": back,
          "up": dot_uv(cap, y_hi, 0.3), "down": dot_uv(cap, y_lo, 0.3)}
    parts = prism((cx, cy + (r0 + r1) / 2, z), rad, r1 - r0, "metal", cap="metal", uv=uv, **kw)
    return turn(parts, ang - 90, "z", (cx, cy, z))


def crossed_logs() -> list[dict]:
    """Two bark logs crossed in an X behind the heart gem, their burning ends poking out
    through the ring's lower rim (the far ends tuck under the lip)."""
    parts = []
    c = (AX, BOW_Y)
    for k, ang in enumerate(LOG_ANGLES):
        z = AZ + (0.08 if k == 0 else -0.08)
        parts += log_prism(c, ang, -LOG_IN, LOG_OUT - 0.55, LOG_R, "log", "log", z=z)
        parts += log_prism(c, ang, BOW_OUT - 0.2, LOG_OUT - 0.5, LOG_R + 0.12, "wood", "wood", z=z)
        parts += log_prism(c, ang, LOG_OUT - 0.6, LOG_OUT, LOG_R + 0.16, "ember", "ember", z=z, glow=10)
    return parts


def inner_flames() -> list[dict]:
    """Three tongues of fire rising over the gem from the crossing of the logs."""
    parts = []
    for ang, r0, length, width, bend in INNER_FLAMES:
        a = math.radians(ang)
        start = (AX + r0 * math.cos(a), BOW_Y + r0 * math.sin(a))
        heading = 90 + (ang - 90) * 0.55
        parts += flame_tongue(start, heading, length, width, 1.6, bend * 0.4)
    return parts


def outer_flames() -> list[dict]:
    """Flames licking up out of the ring's shoulders, curling in toward the collar."""
    parts = []
    for ang, length, width, heading, bend in OUTER_FLAMES:
        for sign in (1, -1):
            a = math.radians(90 + sign * (ang - 90))
            start = (AX + (BOW_OUT - 0.55) * math.cos(a), BOW_Y + (BOW_OUT - 0.55) * math.sin(a))
            h = 90 + sign * (heading - 90)
            parts += flame_tongue(start, h, length, width, 1.5, sign * bend)
    return parts


def coals_and_studs() -> list[dict]:
    """A bed of glowing coals under the gem, and ember studs set round the band."""
    parts = []
    for ang, r, s in COALS:
        a = math.radians(ang)
        parts.append(diamond((AX + r * math.cos(a), BOW_Y + r * math.sin(a)), s, 1.2, "ember", ang * 1.7, glow=11))
    for ang in STUD_ANGLES:
        a = math.radians(ang)
        parts.append(diamond((AX + BAND_R * math.cos(a), BOW_Y + BAND_R * math.sin(a)), 0.56, BOW_D + 0.24,
                             "ember", ang - 45, glow=7))
    return parts


def ember_gem() -> list[dict]:
    """The ember gem, a fire opal: an octagonal body plus a painted front and back."""
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


def rod_x(x0: float, x1: float, y: float, r: float, lane: str, cap: str, **kw) -> list[dict]:
    """An octagonal rod along X at height y whose facets sample `lane`, its ends `cap`."""
    side = lane_uv(lane, y + r, y - r)
    uv = {"south": side, "north": side, "east": side, "west": side,
          "up": dot_uv(cap, y, 0.3), "down": dot_uv(cap, y, 0.3)}
    return prism(((x0 + x1) / 2, y, AZ), r, x1 - x0, "metal", axis="x", cap="metal", uv=uv, **kw)


def firewood_bit() -> list[dict]:
    """The bit: a bronze rail on the shaft's -X side carrying three stacked firewood logs
    as its teeth, each with a burning end; a flame licks up off the top log."""
    parts = bit_plate(RAIL_X0, RAIL_Y0, RAIL_Y1, 1.9)
    for y, length in TEETH:
        tip = RAIL_X0 - length
        parts += rod_x(tip + 0.4, RAIL_X0 + 0.3, y, TOOTH_R, "wood", "wood")
        parts += rod_x(tip, tip + 0.45, y, TOOTH_R + 0.05, "ember", "ember", glow=9)
    for y0, y1 in ((RAIL_Y1 - 0.05, RAIL_Y1 + 0.4), (RAIL_Y0 - 0.4, RAIL_Y0 + 0.05)):
        parts.append(box((RAIL_X0 - 0.2, y0, AZ - 1.1), (BIT_X1, y1, AZ + 1.1), "metal",
                         faces=lane_faces("band", y1, y0)))
    parts += flame_tongue((3.0, TEETH[0][0] + 0.45), 95, 1.9, 0.8, 0.8, -12)
    return parts


def mid_ring() -> list[dict]:
    """A bronze ring round the middle of the shaft with four little flames rising off it."""
    y0, y1 = MID_RING
    parts = lane_prism(y0, y1, 1.42, "band")
    parts += lane_prism(y0 + 0.2, y1 - 0.2, 1.52, "ember", cap="ember", glow=6)
    for sx in (-1, 1):
        parts += flame_tongue((AX + sx * 1.15, y1 - 0.2), 90 - sx * 8, 1.45, 0.62, 0.55, sx * 16)
    for sz in (-1, 1):
        parts += flame_tongue((AX, y1 - 0.2), 90, 1.25, 0.58, 0.5, 0.0, z=AZ + sz * 1.1)
    return parts


def brazier_finial() -> list[dict]:
    """A bronze brazier on the cap holding a flame: a tall tongue crossed with a second
    turned a quarter round, and two splayed side tongues."""
    y = BRAZIER_Y
    profile = [(0.0, 0.62), (0.45, 0.7), (0.85, 1.15), (1.25, 1.3)]
    parts = []
    for (h0, r0), (h1, r1) in zip(profile, profile[1:]):
        parts += lane_prism(y + h0, y + h1, (r0 + r1) / 2, "band")
    top = y + 1.25
    parts += lane_prism(top - 0.12, top + 0.02, 1.05, "ember", cap="ember", glow=10)   # coals in the bowl
    main = flame_tongue((AX, top - 0.3), 90, 3.0, 1.45, 1.1, 0.0)
    cross = turn(flame_tongue((AX, top - 0.3), 90, 2.4, 1.2, 1.0, 0.0), 90, "y", (AX, top, AZ))
    parts += main + cross
    for sign in (1, -1):
        parts += flame_tongue((AX + sign * 0.55, top - 0.25), 90 - sign * 28, 1.45, 0.72, 0.8, sign * 22)
    return parts


def theme() -> list[dict]:
    return (crossed_logs() + inner_flames() + outer_flames() + coals_and_studs() + ember_gem() + firewood_bit()
            + mid_ring() + brazier_finial())


# ======================================================================================
# 4. TEXTURES
# ======================================================================================
FRAME_ROWS = (RAIL_Y1, RAIL_Y0)   # the rail's front: a bronze frame round a charred board


def paint_theme_lanes(img) -> list[tuple]:
    """Paints the theme lanes and the ember cracks; returns the cracks (column, row, phase)
    so the frames can make them breathe."""
    rng = random.Random(33)
    px = img.load()
    cracks = []
    # bark: lit edge to shadow, grooved with dark streaks and a few ember-lit fissures
    paint_lane(img, "log", [LOG[4], LOG[3], LOG[2], LOG[1]])
    c0, w = LANES["log"]
    for r in range(ATLAS):
        for i in range(w):
            q = rng.random()
            if q < 0.18:
                px[c0 + i, r] = rgba(LOG[max(0, 2 - i // 2)])
            elif q < 0.26:
                px[c0 + i, r] = rgba(LOG[5] if i < 2 else LOG[3])
        if rng.random() < 0.12:
            i = rng.randrange(w)
            px[c0 + i, r] = rgba(EMBER[3])
            cracks.append((c0 + i, r, rng.random()))
    paint_lane(img, "ember", [EMBER[5], EMBER[4], EMBER[3], EMBER[2]])
    # split firewood for the bit: paler, fresher bark, grooved
    paint_lane(img, "wood", [WOOD[5], WOOD[4], WOOD[3], WOOD[1]])
    c0, w = LANES["wood"]
    for r in range(ATLAS):
        for i in range(w):
            q = rng.random()
            if q < 0.2:
                px[c0 + i, r] = rgba(WOOD[max(0, 3 - i)])
            elif q < 0.27:
                px[c0 + i, r] = rgba(WOOD[5] if i < 2 else WOOD[2])
    # the rail: bronze frame, charred board with an ember seam between the logs
    c0, w = LANES["panel"]
    r0, r1 = row_of(FRAME_ROWS[0]), row_of(FRAME_ROWS[1])
    for r in range(r0, r1 + 1):
        for i in range(w):
            edge = r in (r0, r1) or i in (0, w - 1)
            if edge:
                col = BRONZE[5] if (r == r0 or i == 0) else BRONZE[2]
            else:
                col = CHAR[4 - min(3, i // 3)]
            px[c0 + i, r] = rgba(col)
    for y in (23.3, 21.25):
        r = row_of(y)
        for i in range(1, w - 1):
            px[c0 + i, r] = rgba(EMBER[3] if i % 3 else EMBER[4])
    # the band is charred wood split by a network of glowing cracks: a vein wanders across
    # the lane, breaking and resuming, brightest where it runs deepest
    for lane in ("face0", "face1", "face2"):
        c0, w = LANES[lane]
        i = rng.randrange(w)
        for r in range(row_of(9.0), row_of(-2.0) + 1):
            if rng.random() < 0.3:
                i = max(0, min(w - 1, i + rng.choice((-1, 1))))
            if rng.random() < 0.55:
                px[c0 + i, r] = rgba(EMBER[4] if rng.random() < 0.6 else EMBER[5])
                cracks.append((c0 + i, r, rng.random()))
                if rng.random() < 0.25:
                    j = max(0, min(w - 1, i + rng.choice((-1, 1))))
                    px[c0 + j, r] = rgba(EMBER[2])
    # the lacquered shaft is scorched black where it leaves the fire, the soot thinning
    # into flecks as it climbs, and a hairline of bronze frames the mid-shaft ring
    c0, w = LANES["shaft"]
    lo, hi = row_of(SCORCH[1]), row_of(SCORCH[0])
    for r in range(lo, hi + 1):
        k = (r - lo) / max(1, hi - lo)          # 0 at the top of the scorch, 1 at the collar
        for i in range(w):
            if rng.random() < 0.15 + 0.8 * k:
                px[c0 + i, r] = rgba(CHAR[2] if rng.random() < 0.55 else CHAR[4])
            elif k > 0.5 and rng.random() < 0.25:
                px[c0 + i, r] = rgba(EMBER[5])
                cracks.append((c0 + i, r, rng.random()))
    for r in range(row_of(25.5), lo):
        if rng.random() < 0.07:
            px[c0 + rng.randrange(w), r] = rgba(LACQUER[3] if rng.random() < 0.5 else CHAR[4])
    for y in (MID_RING[0] - 0.9, MID_RING[1] + 1.0):
        paint_lane(img, "shaft", [BRONZE[6], BRONZE[5], BRONZE[4], BRONZE[3]], rows=[row_of(y)])
    return cracks


# Sparks rising up the key: (lane, column offset, start height, rise, loop phase, life).
# Each lives part of the loop, climbing and cooling from yellow-white to a dull red.
SPARKS = (("shaft", 0, 10.6, 9.0, 0.05, 0.42), ("shaft", 2, 11.0, 12.0, 0.36, 0.5),
          ("shaft", 1, 12.5, 8.0, 0.66, 0.38), ("shaft", 3, 10.4, 10.0, 0.86, 0.45),
          ("face2", 1, -0.5, 7.5, 0.18, 0.4), ("face0", 1, 0.0, 7.0, 0.52, 0.4),
          ("face1", 0, 0.5, 6.5, 0.78, 0.36), ("rim_side", 1, 1.0, 6.5, 0.3, 0.4))
SPARK_RAMP = ["#fffbe2", "#ffe07a", "#ffab3a", "#f06a22", "#b83010", "#6e1a0c"]


def theme_atlas_frame(img, t: float, cracks=()) -> None:
    px = img.load()
    # cracks and embers breathe, each at its own moment (whole cycles per loop: seamless)
    for x, r, ph in cracks:
        k = 0.5 + 0.5 * math.sin(2 * math.pi * (t + ph))
        blend(px, x, r, EMBER[5], 0.75 * k ** 2)
    c0, w = LANES["ember"]
    for r in range(ATLAS):
        k = 0.5 + 0.5 * math.sin(2 * math.pi * (2 * t + _hash(r // 2, 3)))
        for i in range(w):
            blend(px, c0 + i, r, EMBER[6], 0.55 * k * (1 - i / (w + 1)))
            blend(px, c0 + i, r, EMBER[2], 0.3 * (1 - k) * (i / w))
    # sparks climbing the shaft and the ring's sides
    for lane, off, y0, rise, phase, life in SPARKS:
        age = (t - phase) % 1.0
        if age >= life:
            continue
        a = age / life
        y = y0 + rise * a
        c, w = LANES[lane]
        x = c + min(w - 1, off)
        r = row_of(y)
        col = SPARK_RAMP[min(len(SPARK_RAMP) - 1, int(a * len(SPARK_RAMP)))]
        blend(px, x, r, col, 1.0 - 0.3 * a)
        blend(px, x, r + 1, col, 0.45 * (1 - a))
    # the gem's flare lights the logs round the heart
    p = gem_pulse(t)
    if p > 0.03:
        c0, w = LANES["log"]
        for r in range(ATLAS):
            y = Y_TOP - (r + 0.5) / RPU
            k = 0.45 * p * max(0.0, 1 - abs(y - BOW_Y) / 4.5)
            for i in range(w):
                blend(px, c0 + i, r, "#ff9a3a", k)


def paint_atlas_base():
    img = canvas(ATLAS, fill=BRONZE[3])
    for lane, tones in SKELETON_TONES.items():
        paint_lane(img, lane, tones)
    cracks = paint_theme_lanes(img)
    return img, cracks


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


def flicker(t: float, seed: float = 0.0) -> float:
    """-1..1, a restless flicker made of whole cycles per loop (so it loops)."""
    return (0.5 * math.sin(2 * math.pi * (3 * t + seed)) + 0.3 * math.sin(2 * math.pi * (5 * t + 2.3 * seed + 0.4))
            + 0.2 * math.sin(2 * math.pi * (7 * t + 1.7 * seed + 0.1)))


FLAME_SPARKS = ((9, 2, 0.0), (21, 1, 0.35), (15, 2, 0.6))   # (x, trips per loop, phase)


def flame_frame(t: float):
    """The flame texture: across = a tongue's width (hot core, red edges), down = its length
    from the tip (v 0) to the root (v 16); heat bands roll up toward the tip."""
    img = canvas(32)
    px = img.load()
    surge = flicker(t, 0.55)
    for y in range(32):
        v = (y + 0.5) / 32
        for x in range(32):
            u = (x + 0.5) / 32
            core = 1 - abs(2 * u - 1) ** 1.6
            n = (0.6 * math.sin(2 * math.pi * (2 * v + 2 * t) + 2.4 * math.sin(2 * math.pi * u + 0.5))
                 + 0.4 * math.sin(2 * math.pi * (3 * v + 3 * t) - 5.0 * u))
            heat = 0.7 * v + 0.38 * core - 0.05 + 0.22 * n + 0.07 * surge
            f = heat * 7.5
            if x in (0, 31):
                f -= 1.2
            c = FLAME[int(clamp(dither_band(f, x, y), 0, len(FLAME) - 1))]
            px[x, y] = rgba(c)
    # sparks shooting up each tongue and out of its tip (whole trips per loop: seamless)
    for sx, speed, phase in FLAME_SPARKS:
        v = (1.0 - speed * t - phase) % 1.0
        x = int(sx + 2.5 * math.sin(2 * math.pi * (t + phase))) % 32
        y = int(v * 32)
        blend(px, x, y, "#fffbe2", 0.9)
        blend(px, x, min(31, y + 1), "#ffe07a", 0.55)
        blend(px, x, min(31, y + 2), "#ffab3a", 0.25)
    return img


def gem_frame(t: float):
    """The ember gem's face: an octagon with a table facet, crown facets lit from the left
    and from the fire below, flaring with the glint and flickering like a banked coal."""
    p = gem_pulse(t)
    lift = 0.55 + 1.2 * p + 0.28 * flicker(t, 0.3)
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
                f = 0.5
            elif o <= 3.6:
                f = 3.8 if dx * lx + dy * ly > 0.4 else 3.2
            else:
                ang = math.atan2(dy, dx)
                sector = round(ang / (math.pi / 4)) * (math.pi / 4)
                f = 2.7 + 1.5 * (math.cos(sector) * lx + math.sin(sector) * ly)
            f += 0.5 * max(0.0, dy / 8)                          # fire-lit from below
            px[tx, ty] = rgba(ramp_at(GEM, f + lift * (1.0 if o <= 7.0 else 0.6)))
    # a tiny flame glinting in the table
    for x, y, k in ((8, 6, 0.9), (7, 7, 0.8), (8, 7, 1.0), (8, 8, 1.0), (9, 8, 0.8), (7, 8, 0.7), (8, 9, 0.7)):
        cur = "#%02x%02x%02x" % px[x, y][:3]
        px[x, y] = rgba(mix(cur, GEM[7], (0.35 + 0.35 * p) * k))
    for x, y in ((5, 5), (6, 5), (5, 6)):
        px[x, y] = rgba(GEM[7])
    sparkle(img, 6, 6, clamp(p * 1.3 - 0.25), colour="#fffbe2", reach=4)
    return img


# Embers rising through the window (texels): x, start y, speed (window heights per loop),
# sway amplitude, sway cycles, phase, big.
EMBERS = [dict(x=x, y=y, speed=s, amp=a, freq=f, phase=ph, big=b) for x, y, s, a, f, ph, b in (
    (7, 4, 1, 1.4, 1, 0.0, True), (12, 21, 2, 1.0, 2, 0.3, False), (16, 11, 1, 1.6, 1, 0.55, False),
    (20, 27, 1, 1.2, 1, 0.15, True), (25, 15, 2, 1.5, 1, 0.8, False), (4, 18, 1, 1.0, 2, 0.45, False),
    (28, 6, 1, 1.3, 1, 0.7, False), (10, 30, 2, 1.1, 1, 0.9, False), (23, 1, 1, 1.0, 2, 0.25, True))]
TONGUES = ((16, 0.0, 14.0, 5.0), (10.5, 0.37, 9.0, 3.2), (21.5, 0.71, 9.5, 3.2))   # painted flames: centre x, phase, height, half-width


def window_frame(t: float):
    """The fire in the bow: a glow blazing orange over the coals and deepening to red at the
    rim (the logs stand out against it in silhouette), flames dancing up from the coals, the
    gem's halo breathing, and embers drifting up (each rises one or two whole window heights
    per loop, so the loop is seamless)."""
    p = gem_pulse(t)
    img = canvas(32)
    px = img.load()
    base = 24.0
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 16, (y + 0.5 - 16) / 16
            r = math.hypot(dx, dy)
            g = math.hypot((x + 0.5 - 16) / 16, (y + 0.5 - 23) / 13)
            f = 1.0 + 6.2 * max(0.0, 1 - g) ** 0.9 + 0.4 * flicker(t, 0.8)
            if r > 0.8:
                f -= (r - 0.8) * 10
            c = GLOW[int(clamp(dither_band(f, x, y), 0, len(GLOW) - 1))]
            halo = (0.3 + 0.6 * p) * max(0.0, 1 - r / 0.55) ** 1.6
            if halo > 0.05:
                c = mix(c, GEM[5], min(0.8, halo))
            px[x, y] = rgba(c)
    # flames dancing up from the coals
    for cx, ph, h, hw in TONGUES:
        height = h * (1 + 0.14 * flicker(t, ph))
        for y in range(32):
            rel = (base - (y + 0.5)) / height
            if not 0 <= rel <= 1:
                continue
            sway = 1.6 * math.sin(2 * math.pi * (2 * t + ph)) * rel ** 1.4
            half = hw * (1 - rel) ** 0.75
            for x in range(32):
                d = abs(x + 0.5 - (cx + sway)) / max(half, 0.01)
                if d > 1:
                    continue
                f = 3.0 + 5.5 * (1 - rel) * (1 - 0.5 * d)
                cur = "#%02x%02x%02x" % px[x, y][:3]
                col = FLAME[int(clamp(dither_band(f, x, y), 0, len(FLAME) - 1))]
                px[x, y] = rgba(mix(cur, col, 0.85 if d < 0.8 else 0.45))
    # coals glowing along the bottom
    for x in range(32):
        for y in range(int(base), 30):
            k = _hash(x, y)
            if k < 0.45:
                b = 0.5 + 0.5 * math.sin(2 * math.pi * (t + _hash(y, x, 2)))
                col = ramp_at(EMBER, 2.0 + 3.5 * b * (1 - (y - base) / 7))
                cur = "#%02x%02x%02x" % px[x, y][:3]
                px[x, y] = rgba(mix(cur, col, 0.8))
    # embers rising
    for em in EMBERS:
        fy = (em["y"] - em["speed"] * 32 * t) % 32
        fx = em["x"] + em["amp"] * math.sin(2 * math.pi * (em["freq"] * t + em["phase"]))
        heat = clamp(fy / 30)                     # hotter low down, cooling as it climbs
        col = ramp_at(["#ffab3a", "#ffe07a", "#fff4c0", "#fffbe2"], 3.0 - 2.5 * heat)
        x, y = round(fx) % 32, int(fy)
        cur = "#%02x%02x%02x" % px[x, y][:3]
        px[x, y] = rgba(mix(cur, col, 0.95))
        if y + 1 < 32:
            cur = "#%02x%02x%02x" % px[x, y + 1][:3]
            px[x, y + 1] = rgba(mix(cur, col, 0.4))
        if em["big"]:
            for ox, oy in ((1, 0), (0, -1) if y > 0 else (1, 0)):
                xx, yy = (x + ox) % 32, y + oy
                cur = "#%02x%02x%02x" % px[xx, yy][:3]
                px[xx, yy] = rgba(mix(cur, col, 0.6))
    return img


def textures() -> None:
    base, cracks = paint_atlas_base()

    def metal_frame(t):
        img = shine(base, t, **SWEEP)
        img = shine(img, (t - TRAIL_LAG) % 1.0, **TRAIL)
        theme_atlas_frame(img, t, cracks)
        return img

    save_animation(animate(metal_frame, FRAMES), "metal", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(window_frame, FRAMES), "window", frametime=FRAMETIME)
    save_animation(animate(flame_frame, FRAMES), "flame", frametime=FRAMETIME, interpolate=True)


# ======================================================================================
# 5. MODELS
# ======================================================================================

def models() -> dict:
    parts = skeleton() + theme()
    return {"main": model(parts, transforms(parts))}
