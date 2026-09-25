"""Money Key: the gold-and-emerald crate key of the Money crate (one of the 19 JoshyMC crate keys).

The bow is a minted gold coin: the set's stepped ring, beaded round its band and reeded
round its edge, framing a window of deep emerald enamel with a sunburst field where gold
coins tumble down. Across the window stands a gold dollar sign whose S wraps round an
emerald set at the heart in a six-claw socket. The octagonal shaft is emerald lacquer with
gilt pinstripes and gold bands, and carries a banknote rolled round it between the bands; on its -X side the bit is three stacks of gold coins
standing on a gold bar inlaid with green enamel, and a gold coin stamped with a dollar sign
stands in a slot on top of the shaft.

Animation (one 3.2 s loop): a warm glint sweeps up the whole key from the bow to the tip,
the emerald flares as it passes through the heart and floods the dollar sign with light,
coins tumble through the window and flash as they turn to face you, the top coins of the
bit's stacks twinkle after the glint and again while the key rests, and the coin on top
catches the glint and twinkles.

---------------------------------------------------------------------------------------
KEY SET SKELETON (copied from january_key.py; all 19 crate keys share these proportions)
---------------------------------------------------------------------------------------
  frame   upright on x = 8, z = 8; front = +Z (the face the inventory shows)
  bow     a stepped ring centred at (8, BOW_Y = 3.5): outer radius 5.4, inner radius 3.5;
          a recessed outer rim, the main band and a raised inner lip, 20 segments each.
          Inside: a recessed window (the theme's animated scene), the theme's emblem, and
          at the heart a socket (bezel + six claws) holding the theme's gem
  collar  two octagonal discs, y 8.4..10.2, joining the ring's top to the shaft
  shaft   octagonal, apothem 1.15, y 10..25.5, bands at y 13 and 17.6, a cap at 25.4..26.3;
          the theme may crown it with a finial (up to y ~29.5)
  bit     on the -X side of the upper shaft, y 18..25.2
  grip    (8, 10.6, 8); SIZE = 0.64 holds it at about 1 block, carried like a sceptre
  metal   ONE 64 px atlas: rows map to height, columns are material lanes, so one shine()
          band sweeping up the atlas glints along the whole key, bow to tip
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, box, canvas, copy, display, mix, model, place, prism, rgba, save, save_animation,
                     shine, sparkle, turn)

ID = "money_key"
NAME = "Money Key"
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
BIT_X0 = 3.7                                        # the bit's outer edge (themes may move it)
BIT_X1 = AX - SHAFT_R + 0.35                        # bit parts run into the shaft up to here
CLAW_ANGLES = tuple(90 + 60 * k for k in range(6))  # the six claws round the heart gem
GRIP = (8.0, 10.6, 8.0)
SIZE = 0.64
GUI_ROTATION = (-25, 20, -45)

# --- timing: every animation loops in 64 ticks ------------------------------------------
FRAMES, FRAMETIME = 32, 2          # atlas, window and the finial coin
GEM_FRAMES, GEM_FRAMETIME = 16, 4  # gem (interpolated)

# --- the metal atlas ----------------------------------------------------------------------
ATLAS = 64                        # px per frame side
TPU = ATLAS / 16                  # texels per uv unit
Y_TOP = 29.0                      # model height of atlas row 0
RPU = 2                           # atlas rows per model unit
LIGHT = (-0.99, 0.14)             # painted light: from the key's -X side = top-left in the GUI
LANES = {                         # name -> (first column, width) in texels
    # skeleton (0-41)
    "face0": (0, 3), "face1": (3, 3), "face2": (6, 3),
    "rim0": (9, 2), "rim1": (11, 2), "rim2": (13, 2),
    "lip0": (15, 2), "lip1": (17, 2), "lip2": (19, 2),
    "rim_side": (21, 3), "lip_side": (24, 3), "step": (27, 2),
    "shaft": (29, 4), "band": (33, 4), "bezel": (37, 3), "cap": (40, 2),
    # theme (42-63)
    "emblem": (42, 4), "coin": (46, 4), "panel": (50, 11), "enamel": (61, 3),
}
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#fffbe6", width=8.0, strength=0.88, angle=-90.0, pause=0.4)   # bow -> tip
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


def bow_window(tex: str = "window", glow: int = 7, see_through: bool = True) -> list[dict]:
    """The recessed window: three slabs that cover the ring's opening while staying inside
    its outer edge, one texture projected across them. see_through=False mirrors the back
    across the key's axis, so an emblem that reads correctly from behind (the front one
    turned round) meets its painted shadow there too."""
    r = WINDOW_R
    h = r * 0.54
    xs = math.sqrt(r * r - h * h) + 0.06
    parts = []
    for x0, ya, x1, yb in ((-r, -h, r, h), (-xs, h, xs, r), (-xs, -r, xs, -h)):
        u0, u1 = (x0 + r) / (2 * r) * 16, (x1 + r) / (2 * r) * 16
        v0, v1 = (r - yb) / (2 * r) * 16, (r - ya) / (2 * r) * 16
        back = [u1, v0, u0, v1] if see_through else [u0, v0, u1, v1]
        parts.append(box((AX + x0, BOW_Y + ya, AZ - WINDOW_D / 2), (AX + x1, BOW_Y + yb, AZ + WINDOW_D / 2), tex,
                         faces={"south": (tex, [u0, v0, u1, v1]), "north": (tex, back)},
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
    return bow_ring() + bow_window(see_through=False) + gem_socket(CLAW_ANGLES) + collar() + shaft()


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
# 2. THEME PALETTE: Money, polished gold and emerald green (#2ECC71)
# ======================================================================================
METAL = GOLD = ["#3a1a12", "#6d3414", "#a3571c", "#cf8a27", "#ebb23b", "#fad865", "#fff5c4"]
BRIGHT = ["#fff3ad", "#ffd94f", "#f1aa2c", "#94440f"]          # the dollar sign's polished gold
EMERALD = ["#021d11", "#053b21", "#0a6333", "#128f49", "#2ecc71", "#6cf0a1", "#c6ffdd", "#ffffff"]
FIELD = ["#010d08", "#02170e", "#042415", "#07331e", "#0b4428", "#115733", "#1a6c40"]   # window enamel
NOTE = ["#0e2818", "#1d472b", "#356b42", "#5f9660", "#9cc788", "#d4eabf", "#f7fcec"]   # greenback paper

# ======================================================================================
# 3. THEME PARTS: the dollar sign, the emerald, the beaded coin rim, the banknote roll,
#    the coin-stack bit and the coin in its slot on top
# ======================================================================================
S_RX, S_RY = 2.3, 1.4       # the S's bowls: horizontal and vertical semi-axes (stroke centreline)
S_W = 0.95                  # stroke width
S_Z = (8.2, 8.95)           # the front S stands 0.7 proud of the window; the back one mirrors it
S_ARC = (30, 226)           # the upper bowl, degrees CCW from its terminal round to behind the socket
S_STEPS = 12
BALL = 1.05                 # the S's terminals (side of the turned square)
BAR_W, BAR_Z = 0.78, (6.9, 9.1)   # the upright runs through the window, crossing both S's
BAR_H = (1.2, 3.52)         # ...from inside the socket out to the lip, above and below the heart
EMBLEM_GLOW = 6
BEAD_ANGLES = tuple(90 + 22.5 * k for k in range(16))
ROLL = (13.4, 17.2, 1.38)   # the banknote rolled round the shaft: y0, y1, apothem
NOTE_V = (4.0, 12.0)        # the bill's rows in the note texture (uv)
INGOT = ((18.0, 19.4, BIT_X0, 2.5), (19.4, 20.5, BIT_X0 + 0.22, 2.1))   # gold bar steps: y0, y1, x0, depth
STACKS = ((4.55, 23.5, 7.8, 0.68), (5.62, 22.5, 8.32, 0.66), (6.72, 25.0, 7.85, 0.7))   # (x, top, z, apothem)
TIP_C = (AX, 27.55)         # the coin standing on top of the shaft
TIP_R, TIP_D = 1.8, 0.56
SLOT = (CAP[1], CAP[1] + 0.22)


def s_paths():
    """The S as two point lists, each running from its terminal to behind the socket: the
    upper bowl turns counter-clockwise over the top, the lower one is it turned 180."""
    upper = []
    for k in range(S_STEPS + 1):
        th = math.radians(S_ARC[0] + (S_ARC[1] - S_ARC[0]) * k / S_STEPS)
        upper.append((AX + S_RX * math.cos(th), BOW_Y + S_RY + S_RY * math.sin(th)))
    lower = [(2 * AX - x, 2 * BOW_Y - y) for x, y in upper]
    return upper, lower


def stroke(points, w: float, d: float, lane: str, **kw) -> list[dict]:
    """Bars joining the points, each lapping its neighbours; alternate bars sit a hair
    shallower so the laps never z-fight."""
    parts = []
    for k, (p0, p1) in enumerate(zip(points, points[1:])):
        length = math.hypot(p1[0] - p0[0], p1[1] - p0[1])
        ang = math.degrees(math.atan2(p1[1] - p0[1], p1[0] - p0[0]))
        ext = w * 0.3
        parts.append(radial(p0, ang, -ext, length + ext, w, d - 0.05 * (k % 2), lane, **kw))
    return parts


def stud(c, side: float, z: float, d: float, **kw) -> dict:
    """A square turned 45 degrees with a flat polished face and shadowed sides: the S's
    terminals."""
    x, y = c
    flat = dot_uv("emblem", y, 0.3)
    edge = lane_uv("step", y + 0.3, y - 0.3)
    faces = {"south": ("metal", flat), "north": ("metal", flat)}
    faces.update({s: ("metal", edge) for s in ("east", "west", "up", "down")})
    e = box((x - side / 2, y - side / 2, z - d / 2), (x + side / 2, y + side / 2, z + d / 2), "metal", faces=faces, **kw)
    return turn(e, 45, "z", (x, y, z))


def dollar_sign() -> list[dict]:
    """A gold dollar sign in relief on each face of the window: the S's bowls wrap round
    the socket (its spine runs behind the emerald) and end in ball terminals; the back S
    is the front one turned round, so it reads true from behind. The upright runs through
    the window, crossing both."""
    z, d = (S_Z[0] + S_Z[1]) / 2, S_Z[1] - S_Z[0]
    front = []
    for path in s_paths():
        front += stroke(path, S_W, d, "emblem", z=z, east="step", west="step", glow=EMBLEM_GLOW)
        front.append(stud(path[0], BALL, z, d + 0.08, glow=EMBLEM_GLOW))
    parts = front + turn(copy(front), 180, "y", (AX, BOW_Y, AZ))
    for y0, y1 in ((BOW_Y + BAR_H[0], BOW_Y + BAR_H[1]), (BOW_Y - BAR_H[1], BOW_Y - BAR_H[0])):
        parts.append(box((AX - BAR_W / 2, y0, BAR_Z[0]), (AX + BAR_W / 2, y1, BAR_Z[1]), "metal",
                         faces=lane_faces("emblem", y1, y0), glow=EMBLEM_GLOW))
    return parts


def emerald_gem() -> list[dict]:
    """The heart emerald: an octagonal body plus a painted step-cut front and back."""
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


def ring_beads() -> list[dict]:
    """The coin's beaded border: sixteen gold beads round the band."""
    parts = []
    for ang in BEAD_ANGLES:
        a = math.radians(ang)
        parts.append(diamond((AX + BAND_R * math.cos(a), BOW_Y + BAND_R * math.sin(a)), 0.46, BOW_D + 0.24,
                             "face2", ang - 45))
    return parts


def note_roll() -> list[dict]:
    """A banknote rolled round the shaft between its bands: an octagonal roll whose eight
    facets each show the next quarter-inch of the bill, its portrait on the front."""
    y0, y1, r = ROLL
    cx, cy, cz = AX, (y0 + y1) / 2, AZ
    half = r * math.tan(math.pi / 8)
    v0, v1 = NOTE_V
    end = [0.3, 0.3, 0.7, 0.7]               # a flat green texel for the roll's ends

    def f(u):
        return ("note", [u, v0, u + 2, v1])

    parts = []
    # u of each facet's slice of the bill (east, west, south, north), front facet = u 8..10
    for a, (e, w, s, n) in ((0, (12, 4, 8, 0)), (45, (14, 6, 10, 2))):
        s1 = box((cx - r, y0, cz - half), (cx + r, y1, cz + half), "note",
                 faces={"east": f(e), "west": f(w), "up": ("note", end), "down": ("note", end)},
                 skip=("north", "south"))
        s2 = box((cx - half, y0, cz - r), (cx + half, y1, cz + r), "note",
                 faces={"south": f(s), "north": f(n), "up": ("note", end), "down": ("note", end)},
                 skip=("east", "west"))
        if a:
            turn([s1, s2], a, "y", (cx, cy, cz))
        parts += [s1, s2]
    return parts


def coin_stack(x: float, z: float, r: float, y0: float, top: float, rng) -> list[dict]:
    """A stack of coins (half a unit each) piled by hand: runs of two to four coins, each
    run nudged and turned a little off the one below, and the top coin's stamped face."""
    parts = []
    y = y0
    while y < top - 1e-6:
        y1 = min(top, y + 0.5 * rng.choice((2, 3, 3, 4)))
        cx, cz = x + rng.uniform(-0.1, 0.1), z + rng.uniform(-0.1, 0.1)
        rr = r - rng.uniform(0.0, 0.05)
        run = lane_prism(y, y1, rr, "coin", x=cx, z=cz)
        parts += turn(run, rng.choice((0.0, 11.25, 22.5, 33.75)), "y", (cx, (y + y1) / 2, cz))
        y = y1
    parts.append(box((cx - rr, top, cz - rr), (cx + rr, top + 0.03, cz + rr), "coin",
                     faces={"up": ("coin", [0, 0, 16, 16])}, skip=("north", "south", "east", "west", "down")))
    return parts


def ingot_step(x0: float, y0: float, y1: float, d: float) -> dict:
    """One step of the gold ingot: its front shows the "panel" lane; its underside stays
    warm gold, since the inventory icon looks up at it."""
    faces = {"south": ("metal", panel_uv(x0, BIT_X1, y1, y0)),
             "north": ("metal", panel_uv(x0, BIT_X1, y1, y0, True)),
             "west": ("metal", lane_uv("rim_side", y1, y0, True)),
             "up": ("metal", dot_uv("band", y1, 0.2)), "down": ("metal", dot_uv("band", y0, 0.9))}
    return box((x0, y0, AZ - d / 2), (BIT_X1, y1, AZ + d / 2), "metal", faces=faces, skip=("east",))


def coin_bit() -> list[dict]:
    """The bit: three stacks of gold coins (the key's teeth), tallest by the shaft, the
    middle one standing forward, on a stepped gold ingot inlaid with green enamel."""
    parts = [ingot_step(x0, y0, y1, d) for y0, y1, x0, d in INGOT]
    rng = random.Random(11)
    for x, top, z, r in STACKS:
        parts += coin_stack(x, z, r, INGOT[-1][1], top, rng)
    return parts


def coin_finial() -> list[dict]:
    """A gold coin standing in a slot on the cap: a reeded edge of twelve segments, and a
    painted face and back."""
    parts = []
    r = TIP_R * math.cos(math.radians(15)) - 0.16
    for k in range(12):
        a0, a1 = 90 + (k - 0.5) * 30, 90 + (k + 0.5) * 30
        parts.append(arc_segment(TIP_C, r / math.cos(math.radians(15)), a0, a1, 0.34, TIP_D, "coin", "coin", "step"))
    cx, cy = TIP_C
    zf = AZ + TIP_D / 2
    parts.append(box((cx - TIP_R, cy - TIP_R, zf), (cx + TIP_R, cy + TIP_R, zf + 0.04), "coin", uv="full",
                     skip=("north", "east", "west", "up", "down")))
    parts.append(box((cx - TIP_R, cy - TIP_R, AZ - TIP_D / 2 - 0.04), (cx + TIP_R, cy + TIP_R, AZ - TIP_D / 2),
                     "coin", faces={"north": ("coin", [0, 0, 16, 16])}, skip=("south", "east", "west", "up", "down")))
    # the slot's raised lips
    parts.append(box((AX - 1.6, SLOT[0], AZ - 0.62), (AX + 1.6, SLOT[1], AZ + 0.62), "metal",
                     faces=lane_faces("band", SLOT[1], SLOT[0], sides={"up": dot_uv("step", SLOT[1])})))
    return parts


def theme() -> list[dict]:
    return dollar_sign() + emerald_gem() + ring_beads() + note_roll() + coin_bit() + coin_finial()


# ======================================================================================
# 4. TEXTURES
# ======================================================================================
PANEL = [                      # the ingot's fronts: 11 x 5 texels, x 3.7..7.2, y 20.5..18
    "65555555554",
    "54444444443",
    "4abbbbbbba2",
    "3bbbbbbbbb2",
    "32222222221",
]


PINSTRIPES = (11.5, 24.6)        # gilt hairlines on the emerald shaft


def paint_theme_lanes(img) -> None:
    px = img.load()
    # the shaft is emerald lacquer (lit edge -> shadow) with gilt pinstripes
    paint_lane(img, "shaft", [EMERALD[5], EMERALD[4], EMERALD[3], EMERALD[2]])
    for y in PINSTRIPES:
        paint_lane(img, "shaft", [GOLD[6], GOLD[5], GOLD[4], GOLD[3]], rows=[row_of(y)])
    paint_lane(img, "emblem", BRIGHT)
    paint_lane(img, "enamel", [EMERALD[5], EMERALD[4], EMERALD[3]])
    # stacked coins: one coin per row (half a unit), alternately bright and deep so each
    # coin reads on its own
    c0, w = LANES["coin"]
    lit_coin = (GOLD[5], GOLD[4], GOLD[4], GOLD[3])
    deep_coin = (mix(GOLD[4], GOLD[5], 0.3), GOLD[3], mix(GOLD[3], GOLD[2], 0.4), GOLD[2])
    for r in range(ATLAS):
        tones = lit_coin if r % 2 == 0 else deep_coin
        for i in range(w):
            px[c0 + i, r] = rgba(tones[i])
    # the ring's outer edge is reeded like a coin's
    c0, w = LANES["rim_side"]
    for r in range(1, ATLAS, 2):
        for i in range(w):
            px[c0 + i, r] = rgba(GOLD[4 - i])
    # the gold bar: a bright top edge, a green enamel inlay, a dark foot
    pal = {str(i): GOLD[i] for i in range(7)}
    pal.update({"a": EMERALD[5], "b": EMERALD[3]})
    c0, _ = LANES["panel"]
    r0 = row_of(INGOT[-1][1])
    for j, line in enumerate(PANEL):
        for i, ch in enumerate(line):
            px[c0 + i, r0 + j] = rgba(pal[ch])


def theme_atlas_frame(img, t: float) -> None:
    """The emerald's flare floods the dollar sign and the beads with light: the emblem lane
    brightens round the bow's centre as the gem flares."""
    px = img.load()
    # the top coin of each stack catches the glint just after it passes, then twinkles
    # once more while the key rests
    c0, w = LANES["coin"]
    for k, (_, top, _, _) in enumerate(STACKS):
        a = max(bump(t, sweep_time(top) + 0.07, 0.05), 0.8 * bump(t, 0.7 + 0.09 * k, 0.04))
        if a < 0.05:
            continue
        for r in (row_of(top) , row_of(top) + 1):
            for i, kk in ((0, 1.0), (1, 0.55)):
                cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
                px[c0 + i, r] = rgba(mix(cur, "#ffffff", a * kk))
    p = gem_pulse(t)
    if p < 0.03:
        return
    c0, w = LANES["emblem"]
    for r in range(ATLAS):
        y = Y_TOP - (r + 0.5) / RPU
        k = 0.5 * p * max(0.0, 1 - abs(y - BOW_Y) / 5.5)
        if k <= 0.01:
            continue
        for i in range(w):
            cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
            px[c0 + i, r] = rgba(mix(cur, "#f4ffd8", k))


def paint_atlas_base():
    img = canvas(ATLAS, fill=METAL[3])
    paint_skeleton_lanes(img, METAL)
    paint_theme_lanes(img)
    return img


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


def bump(t: float, centre: float, width: float) -> float:
    """0..1, a smooth bump in loop phase t centred on `centre` (wrapping), `width` either side."""
    d = abs(((t - centre + 0.5) % 1.0) - 0.5)
    return 0.5 + 0.5 * math.cos(math.pi * d / width) if d < width else 0.0


def gem_frame(t: float):
    """The emerald's face: an octagonal step cut, concentric steps lit and shadowed in
    turn like a hall of mirrors round a flat table, brightening and throwing a star at
    its flare."""
    p = gem_pulse(t)
    lift = 0.15 + 1.6 * p
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
            ang = math.atan2(dy, dx)
            sector = round(ang / (math.pi / 4)) * (math.pi / 4)
            facing = math.cos(sector) * lx + math.sin(sector) * ly
            if o > 7.1:                                     # the girdle
                f = 0.7 + 0.5 * facing
            elif o > 5.6:                                   # outer step: lit toward the light
                f = 2.9 + 1.5 * facing
            elif o > 4.2:                                   # middle step: the mirror image
                f = 2.4 - 1.0 * facing
            elif o > 3.0:                                   # inner step
                f = 3.3 + 0.9 * facing
            else:                                           # the table, split by a reflection
                f = 3.7 if dx * lx + dy * ly > 0.6 else 3.1
            px[tx, ty] = rgba(ramp_at(EMERALD, f + lift * (1.0 if o <= 7.1 else 0.5)))
    for x, y in ((5, 4), (4, 5), (5, 5)):
        px[x, y] = rgba(EMERALD[7])
    px[10, 10] = rgba(ramp_at(EMERALD, 5 + lift))
    sparkle(img, 5, 5, clamp(p * 1.3 - 0.25), colour="#ffffff", reach=4)
    return img


# Tumbling coins in the window (texels): x, starting y, fall speed (window heights per
# loop), spin (turns per loop), phase. Placed by hand so they never clump.
COINS = [dict(x=x, y=y, speed=s, spin=n, phase=ph) for x, y, s, n, ph in (
    (5, 4, 1, 1, 0.0), (26, 10, 1, 2, 0.55), (9, 19, 2, 1, 0.3), (23, 25, 1, 1, 0.8),
    (16, 30, 2, 2, 0.15), (29, 20, 1, 1, 0.4), (3, 26, 1, 2, 0.7))]
RAYS = 12
COIN_FULL = [".lll.", "lhhmd", "lhmmd", "lmmdd", ".ddd."]
COIN_HALF = [".l.", "lhm", "lmd", "lmd", ".d."]
COIN_EDGE = ["l", "m", "m", "d", "e"]


def seg_dist(p, a, b) -> float:
    dx, dy = b[0] - a[0], b[1] - a[1]
    k = clamp(((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy or 1.0))
    return math.hypot(p[0] - a[0] - k * dx, p[1] - a[1] - k * dy)


def emblem_edge(x: float, y: float) -> float:
    """Signed distance (model units, negative inside) from (x, y) in the front plane to the
    outline of the front dollar sign and the socket."""
    d = math.hypot(x - AX, y - BOW_Y) - (SOCKET_R + 0.05)
    for path in s_paths():
        d = min(d, min(seg_dist((x, y), a, b) for a, b in zip(path, path[1:])) - S_W / 2)
        d = min(d, abs(x - path[0][0]) + abs(y - path[0][1]) - BALL / math.sqrt(2))
    if abs(y - BOW_Y) >= BAR_H[0]:
        d = min(d, max(abs(x - AX) - BAR_W / 2, abs(y - BOW_Y) - BAR_H[1]))
    return d


_SHADE: dict = {}


def emblem_shade() -> dict:
    """The window texels the relief touches: "under" it (hidden), its dark "contour", and
    the "shadow" it casts down and to the right."""
    if not _SHADE:
        _SHADE.update(under=set(), contour=set(), shadow=set())
        r = WINDOW_R
        for ty in range(32):
            for tx in range(32):
                x, y = AX - r + (tx + 0.5) * r / 16, BOW_Y + r - (ty + 0.5) * r / 16
                e = emblem_edge(x, y)
                if e < 0:
                    _SHADE["under"].add((tx, ty))
                elif e < 0.24:
                    _SHADE["contour"].add((tx, ty))
                elif emblem_edge(x - 0.3, y + 0.3) < 0:
                    _SHADE["shadow"].add((tx, ty))
    return _SHADE


def darken(img, texels, k: float) -> None:
    px = img.load()
    for x, y in texels:
        cur = "#%02x%02x%02x" % px[x, y][:3]
        px[x, y] = rgba(mix(cur, FIELD[0], k))


def window_frame(t: float):
    """Emerald enamel in the bow: a sunburst field, brighter toward the heart, darker at
    the rim, the gem's halo breathing, and gold coins tumbling down behind the dollar sign
    (each falls one or two whole window heights per loop and turns whole spins, so the loop
    is seamless). The relief's contour and shadow are painted on last."""
    p = gem_pulse(t)
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 16, (y + 0.5 - 16) / 16
            r = math.hypot(dx, dy)
            f = 3.0 + 2.4 * (1 - r)
            if math.cos(RAYS * math.atan2(dy, dx)) > 0.2:
                f += 0.8
            if r > 0.8:
                f -= (r - 0.8) * 12
            c = FIELD[int(clamp(dither_band(f, x, y), 0, len(FIELD) - 1))]
            halo = (0.3 + 0.6 * p) * max(0.0, 1 - r / 0.66) ** 1.5
            if halo > 0.05:
                c = mix(c, EMERALD[5], min(0.8, halo))
            px[x, y] = rgba(c)
    shade = emblem_shade()
    darken(img, shade["shadow"], 0.4)
    hidden = shade["under"] | shade["contour"]
    for cn in COINS:
        cy = (cn["y"] + cn["speed"] * 32 * t) % 32
        phi = 2 * math.pi * (cn["spin"] * t + cn["phase"])
        for oy in (0, -32):
            draw_coin(img, cn["x"], round(cy) + oy, phi, hidden)
    darken(img, shade["contour"], 0.8)
    return img


def draw_coin(img, x: int, y: int, phi: float, hidden=frozenset()) -> None:
    """A tumbling coin centred on texel (x, y): face on, half turned or edge on by the
    spin angle phi, its back a shade deeper than its face, and a twinkle when it faces
    you square. It passes behind the relief: texels in `hidden` stay untouched."""
    px = img.load()
    c = math.cos(phi)
    a = abs(c)
    stamp = COIN_FULL if a > 0.8 else COIN_HALF if a > 0.35 else COIN_EDGE
    back = c < 0
    pal = {"h": GOLD[6], "l": GOLD[5], "m": GOLD[4], "d": GOLD[3], "e": GOLD[2]}
    if back:
        pal = {"h": GOLD[5], "l": GOLD[4], "m": GOLD[3], "d": GOLD[2], "e": GOLD[1]}
    w = len(stamp[0])
    for j, line in enumerate(stamp):
        for i, ch in enumerate(line):
            xx, yy = x + i - w // 2, y + j - 2
            if ch == "." or not (0 <= xx < 32 and 0 <= yy < 32) or (xx, yy) in hidden:
                continue
            px[xx, yy] = rgba(pal[ch])
    if a > 0.9 and 0 <= y < 32 and (x - 1, y - 1) not in hidden:
        sparkle(img, x - 1, y - 1, (a - 0.9) / 0.1, colour="#fffbe6", reach=2)


DOLLAR = ["..h..", ".hhhh", "h.h..", ".hhh.", "..h.h", "hhhh.", "..h.."]


def coin_face(t: float):
    """The coin on top: a raised rim lit from the upper left, a sunken field and a dollar
    sign in relief. The shaft's glint reaches it and crosses its face, then it twinkles."""
    img = canvas(16)
    px = img.load()
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            d = math.hypot(dx, dy)
            if d > 8.0:
                continue
            facing = -(dx + dy) / (max(d, 0.01) * math.sqrt(2))
            if d > 6.7:
                f = 4.3 + 1.5 * facing
            elif d > 5.8:
                f = 2.6 - 1.0 * facing
            else:
                f = 3.9 - 0.12 * (dx + dy)
            px[x, y] = rgba(ramp_at(GOLD, f))
    for j, line in enumerate(DOLLAR):                  # drop shadow, then the relief
        for i, ch in enumerate(line):
            if ch == "h":
                px[6 + i + 1, 4 + j + 1] = rgba(GOLD[2])
    for j, line in enumerate(DOLLAR):
        for i, ch in enumerate(line):
            if ch == "h":
                px[6 + i, 4 + j] = rgba(GOLD[6] if (i + j) % 3 else GOLD[5])
    tc = sweep_time(TIP_C[1])
    img = shine(img, (t - tc + 0.08) % 1.0, colour="#fffdf0", width=3.0, strength=0.8, angle=35.0, pause=0.82)
    sparkle(img, 4, 4, bump(t, tc + 0.1, 0.1), colour="#ffffff", reach=3)
    sparkle(img, 11, 11, bump(t, tc + 0.5, 0.08) * 0.8, colour="#fffbe6", reach=2)
    return img


def note_texture():
    """The banknote (32 x 16 texels in rows 8-23): paper margins, a printed frame, a fine
    engraved lattice, the portrait medallion at the front and dollar signs round the
    sides."""
    img = canvas(32, fill=NOTE[2])
    px = img.load()
    for y in range(16):
        for x in range(32):
            if y in (0, 15):
                c = NOTE[6]
            elif y in (1, 14):
                c = NOTE[2]
            elif y in (2, 13):
                c = NOTE[5]
            else:
                c = NOTE[5] if (x + 2 * y) % 5 else NOTE[4]
            px[x, 8 + y] = rgba(c)
    oval = ["..oooo..", ".ollllo.", "ollhhllo", "olhhhhlo", "olhhhhlo", "ohhhhhho", "ohhhhhho",
            ".ohhhho.", "..oooo.."]
    for j, line in enumerate(oval):
        for i, ch in enumerate(line):
            if ch != ".":
                px[14 + i, 8 + 3 + j] = rgba({"o": NOTE[1], "l": NOTE[6], "h": NOTE[3]}[ch])
    mark = [".s.", "sss", "s..", "sss", "..s", "sss", ".s."]
    for x0 in (8, 24):
        for j, line in enumerate(mark):
            for i, ch in enumerate(line):
                if ch == "s":
                    px[x0 + i, 8 + 4 + j] = rgba(NOTE[2])
    return img


def textures() -> None:
    base = paint_atlas_base()
    save_animation(animate(lambda t: atlas_frame(base, t), FRAMES), "metal", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(window_frame, FRAMES), "window", frametime=FRAMETIME)
    save_animation(animate(coin_face, FRAMES), "coin", frametime=FRAMETIME)
    save(note_texture(), "note")


# ======================================================================================
# 5. MODELS
# ======================================================================================

def models() -> dict:
    parts = skeleton() + theme()
    return {"main": model(parts, transforms(parts))}
