"""Cabin Key: the timber-and-brass crate key of the log cabin (one of the 19 JoshyMC crate keys).

The bow is the set's stepped ring with a brass rim and lip round a band of warm timber inlay,
framing a winter night: a little log cabin of round logs sits in the window, its snowy roof
pitched over a honey-amber gem set as the round lamp of its gable, two glowing windows and a
brass-hinged door below, a stone chimney with a brass cap on the right slope and a snowy pine
standing behind the left eave; brass nail heads stud the timber band. A brass collar joins the
ring to an octagonal brass shaft with a timber sleeve hooped in brass and a little brass
lantern hanging off its +X side; the bit on its -X side is a brass bracket holding three
stacked logs as its teeth (end grain showing, a brass hoop near each end, snow on the top
log), and a snow-dusted pine of stacked tiers crowns the tip. The window panes, the lantern
glass and the gem's face are self-lit (shade off) so they read as light even by day.

Animation (one 3.2 s loop): a warm glint sweeps up the whole key from the bow to the tip; as
it crosses the bow the amber gem swells with light and the firelight in the cabin surges
over the logs and snow, then settles. The cabin windows and the lantern flicker like a
hearth fire (interpolated, in step with the gem's flare), smoke curls up from the chimney
and drifts away over the roof, and snow falls through the night sky under a crescent moon.

The skeleton and atlas machinery (section 1) are copied from january_key.py, so all 19 keys
share proportions, grip, display transforms and the bow-to-tip glint.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, mix, model, place, prism, rgba, save, save_animation,
                     shine, sparkle, turn)

ID = "cabin_key"
NAME = "Cabin Key"
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
BIT_X0 = 5.6                                        # the bit plate's outer edge (themes may move it)
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
    "log": (42, 4), "roof": (46, 3), "pine": (49, 4), "snow": (53, 3), "stone": (56, 2), "panel": (58, 6),
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#fff1c8", width=8.0, strength=0.88, angle=-90.0, pause=0.4)   # bow -> tip
TRAIL = dict(colour="#fffbea", width=2.5, strength=0.7, angle=-90.0, pause=0.4)
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
# 2. THEME PALETTE: Cabin, warm timber and brass (#D4A24C)
# ======================================================================================
METAL = BRASS = ["#2b1204", "#5e2c09", "#955417", "#c8852c", "#e4aa46", "#f7d27a", "#fff5cf"]
TIMBER = ["#1f0e08", "#3a1a0e", "#5a2c16", "#7a4020", "#9a5829", "#b87337", "#d4944c"]
LOGS = ["#2a150b", "#4a2813", "#6d3f1d", "#8f5d2b", "#b07d3e", "#cc9c57"]
ROOF = ["#1c1210", "#33201a", "#4c3024", "#664231", "#7e5640"]
SNOW = ["#7d8fb4", "#a9bad9", "#d3def0", "#eef4ff", "#ffffff"]
PINE = ["#071d18", "#0e3024", "#16442f", "#205a3b", "#317248", "#4b8e57"]
STONE = ["#2c2220", "#4a3934", "#6c574d", "#8e786a", "#ae9a89"]
GEM = ["#2e1003", "#5c2404", "#8f3f06", "#c4660b", "#eb9118", "#ffba3a", "#ffdc80", "#fff6d8"]
LAMP = ["#6e2400", "#b84a00", "#ec7a06", "#ffa114", "#ffc93a", "#ffe98a"]
SKY = ["#080b1e", "#0d142c", "#131d3b", "#1a284b", "#23355c", "#2e436c"]
CHINK = ["#140a06", "#241309"]
SMOKE = ["#e6e2e4", "#bdbbc6", "#8e90a4"]

# Skeleton lanes: brass everywhere except the ring's band fronts, a timber inlay.
SKELETON_TONES = {lane: tuple(BRASS[i] for i in tones) for lane, tones in METAL_TONES.items()}
SKELETON_TONES.update({"face0": (TIMBER[3], TIMBER[2], TIMBER[2]), "face1": (TIMBER[4], TIMBER[3], TIMBER[3]),
                       "face2": (TIMBER[5], TIMBER[4], TIMBER[4])})

# ======================================================================================
# 3. THEME PARTS: the cabin, the amber gem, the log bit, the timber sleeve, the pine finial
# ======================================================================================
PEAK = (AX, 5.6)                   # the roof ridge (centre line of the two roof slabs)
ROOF_LEN, ROOF_W, ROOF_D = 4.7, 0.55, 2.1
COURSES = (0.5, 1.15, 1.8)         # log courses of the walls (centre heights)
LOG_R = 0.35
WINDOWS = ((5.7, 6.85), (9.15, 10.3))     # cabin windows (x spans), y 0.9 .. 2.0
WIN_Y0, WIN_Y1 = 0.9, 2.0
DOOR = (7.35, 8.65, 0.3, 1.7)
CHIMNEY = (9.7, 10.65, 3.0, 5.15)
BOW_PINE_X = 5.45
BOW_PINE = ((3.5, 4.3, 0.85), (4.15, 4.85, 0.65), (4.7, 5.35, 0.45), (5.2, 5.75, 0.25))   # (y0, y1, half width)
BIT_LOGS = ((24.0, 2.9), (22.05, 3.9), (20.1, 3.3))     # (height, tip x) of the three log teeth
BIT_LOG_R = 0.72
PLATE_Y0, PLATE_Y1 = 18.6, 25.0
SLEEVE = (14.1, 16.5)
LANTERN = (9.7, 10.9, 15.0, 16.5)   # x0, x1, y0, y1 of the lantern's glass
STUD_ANGLES = tuple(22.5 + 45 * k for k in range(8) if k not in (1, 2))   # brass nails round the band (none by the collar)
FINIAL = ((26.7, 27.5, 1.35), (27.35, 28.1, 1.0), (27.95, 28.65, 0.68), (28.5, 29.15, 0.38))


def proj(x0: float, x1: float, y0: float, y1: float, flip: bool = False) -> list[float]:
    """uv of the "cabin" texture for a front face spanning x0..x1, y0..y1: the texture
    covers the same square as the window, so the facade is painted in window space."""
    r = WINDOW_R
    u0, u1 = (x0 - AX + r) / (2 * r) * 16, (x1 - AX + r) / (2 * r) * 16
    v0, v1 = (BOW_Y + r - y1) / (2 * r) * 16, (BOW_Y + r - y0) / (2 * r) * 16
    uv = [u1, v0, u0, v1] if flip else [u0, v0, u1, v1]
    return [round(clamp(x, 0, 16), 4) for x in uv]


def facade(x0, y0, z0, x1, y1, z1, glow: int = 0, sides: str = "log", **kw) -> dict:
    """A box whose front and back show the cabin texture at its place; its other faces
    sample a lane."""
    faces = {"south": ("cabin", proj(x0, x1, y0, y1)), "north": ("cabin", proj(x0, x1, y0, y1, True)),
             "east": ("metal", lane_uv(sides, y1, y0)), "west": ("metal", lane_uv(sides, y1, y0, True)),
             "up": ("metal", dot_uv(sides, y1)), "down": ("metal", dot_uv(sides, y0))}
    return box((x0, y0, z0), (x1, y1, z1), "metal", faces=faces, glow=glow, **kw)


def log_x(x0: float, x1: float, y: float, r: float, lane: str = "log", reach: float = 1.0) -> list[dict]:
    """A round log along X at height y; its facets sample `lane` over y +- reach, so bark
    streaks run along it."""
    side = lane_uv(lane, y + reach, y - reach)
    uv = {"south": side, "north": side, "east": side, "west": side,
          "up": dot_uv(lane, y, 0.6), "down": dot_uv(lane, y, 0.6)}
    return prism(((x0 + x1) / 2, y, AZ), r, x1 - x0, "metal", axis="x", cap="metal", uv=uv)


def rod_y(y0: float, y1: float, r: float, lane: str, up: str, down: str | None = None) -> list[dict]:
    """An octagonal rod along Y whose facets sample `lane`, with its own top and bottom lanes."""
    side = lane_uv(lane, y1, y0)
    back = lane_uv(lane, y1, y0, True)
    uv = {"south": side, "north": back, "east": side, "west": back,
          "up": dot_uv(up, y1, 0.2), "down": dot_uv(down or lane, y0, 0.8)}
    return prism((AX, (y0 + y1) / 2, AZ), r, y1 - y0, "metal", cap="metal", uv=uv)


def flat(x0, y0, z0, x1, y1, z1, lane: str, up: str | None = None, **kw) -> dict:
    """A plain box sampling `lane` at its height (its top from `up`)."""
    sides = {"up": dot_uv(up, y1, 0.0)} if up else None
    return box((x0, y0, z0), (x1, y1, z1), "metal", faces=lane_faces(lane, y1, y0, sides=sides), **kw)


def cabin() -> list[dict]:
    """The log cabin in the bow: three courses of round logs, board gables either side of
    the gem, a snowy roof, glowing windows, a door, a chimney and a pine behind the eave."""
    parts = []
    for y in COURSES:                                  # log walls, their ends hidden under the lip
        half = math.sqrt(3.8 ** 2 - (y - BOW_Y) ** 2)
        parts += log_x(AX - half, AX + half, y, LOG_R, reach=0.6)
    for side in (-1, 1):                               # board gables beside the gem
        for xa, xb, top in ((5.1, 6.0, 3.4), (6.0, 6.9, 4.3)):
            x0, x1 = (xa, xb) if side < 0 else (2 * AX - xb, 2 * AX - xa)
            parts.append(facade(x0, 2.0, 7.65, x1, top, 8.3))
    for ang, normal in ((225, 135), (315, 45)):        # the roof, a snow layer on each slope
        parts.append(radial(PEAK, ang, -0.36, ROOF_LEN, ROOF_W, ROOF_D, "roof"))
        n = math.radians(normal)
        off = ROOF_W / 2 + 0.16
        c = (PEAK[0] + off * math.cos(n), PEAK[1] + off * math.sin(n))
        top = dot_uv("snow", 5.0, 0.0)
        parts.append(radial(c, ang, -0.2, ROOF_LEN + 0.05, 0.34, ROOF_D + 0.16, "snow",
                            sides={"east": top, "west": top, "up": top, "down": top}))
    parts.append(diamond((AX, PEAK[1] + 0.52), 0.62, ROOF_D + 0.22, "snow"))    # snow on the ridge
    # the door: boards and brass strap hinges painted on, a brass knob
    x0, x1, y0, y1 = DOOR
    parts.append(facade(x0, y0, 7.6, x1, y1, 8.55))
    parts.append(flat(x0 - 0.12, y1 - 0.02, 7.6, x1 + 0.12, y1 + 0.2, 8.65, "log", up="snow"))
    parts.append(flat(8.3, 0.92, 8.5, 8.48, 1.1, 8.72, "band"))
    # the windows: glowing panes in timber frames, snow on the sills
    for xa, xb in WINDOWS:
        parts.append(facade(xa, WIN_Y0, 7.9, xb, WIN_Y1, 8.5, glow=13, shade=False))     # self-lit
        parts.append(flat(xa - 0.1, WIN_Y1, 7.8, xb + 0.1, WIN_Y1 + 0.2, 8.66, "log", up="snow"))
        parts.append(flat(xa - 0.15, WIN_Y0 - 0.16, 7.8, xb + 0.15, WIN_Y0, 8.76, "log", up="snow"))
    # the chimney: stone, a brass cap, snow on top
    x0, x1, y0, y1 = CHIMNEY
    parts.append(flat(x0, y0, 7.3, x1, y1, 8.8, "stone"))
    parts.append(flat(x0 - 0.12, y1, 7.18, x1 + 0.12, y1 + 0.24, 8.92, "band"))
    parts.append(flat(x0 - 0.04, y1 + 0.24, 7.25, x1 + 0.04, y1 + 0.42, 8.85, "snow", up="snow"))
    # a pine behind the left eave: stacked tiers, each dusted with snow
    for y0, y1, hw in BOW_PINE:
        parts.append(flat(BOW_PINE_X - hw, y0, 7.75, BOW_PINE_X + hw, y1, 8.45, "pine"))
        parts.append(flat(BOW_PINE_X - hw + 0.08, y1 - 0.1, 7.7, BOW_PINE_X + hw - 0.08, y1 + 0.08, 8.5,
                          "snow", up="snow"))
    return parts


def ring_studs() -> list[dict]:
    """Brass nail heads driven into the timber band between the claws' spokes."""
    parts = []
    for ang in STUD_ANGLES:
        a = math.radians(ang)
        parts.append(diamond((AX + BAND_R * math.cos(a), BOW_Y + BAND_R * math.sin(a)), 0.5, BOW_D + 0.22,
                             "band", ang - 45))
    return parts


def amber_gem() -> list[dict]:
    """A glowing honey-amber gem: an octagonal body plus a painted front and back."""
    side = {s: [5, 7, 6, 8] for s in ("north", "south", "east", "west")}
    side["up"] = side["down"] = [0.2, 7.5, 0.6, 8.0]
    parts = prism((AX, BOW_Y, AZ), GEM_R, 2.3, "gem", axis="z", cap="gem", uv=side, glow=14)
    r = GEM_R + 0.05
    parts.append(box((AX - r, BOW_Y - r, 9.15), (AX + r, BOW_Y + r, 9.5), "gem", uv="full",
                     skip=("north", "east", "west", "up", "down"), glow=14, shade=False))
    parts.append(box((AX - r, BOW_Y - r, 6.5), (AX + r, BOW_Y + r, 6.85), "gem",
                     faces={"north": ("gem", [16, 0, 0, 16])},
                     skip=("south", "east", "west", "up", "down"), glow=14, shade=False))
    return parts


def log_bit() -> list[dict]:
    """The bit: a brass bracket holding three stacked logs as teeth, end grain out, a brass
    hoop near each end and snow along the top log."""
    parts = bit_plate(BIT_X0, PLATE_Y0, PLATE_Y1, 1.8)
    r = BIT_LOG_R
    for y, tip in BIT_LOGS:
        parts += log_x(tip, BIT_X1 - 0.1, y, r)
        parts.append(box((tip - 0.03, y - r, AZ - r), (tip, y + r, AZ + r), "logend",
                         faces={"west": ("logend", [0, 0, 16, 16])},
                         skip=("north", "south", "east", "up", "down")))
        hoop = {s: lane_uv("band", y + 0.4, y - 0.4) for s in ("north", "south", "east", "west")}
        hoop["up"] = hoop["down"] = dot_uv("band", y, 0.5)
        parts += prism((tip + 0.62, y, AZ), r + 0.09, 0.3, "metal", axis="x", cap="metal", uv=hoop)
    y, tip = BIT_LOGS[0]
    parts.append(flat(tip + 0.1, y + r - 0.12, AZ - 0.42, BIT_X0 + 0.1, y + r + 0.22, AZ + 0.42, "snow", up="snow"))
    parts.append(flat(tip + 0.9, y + r + 0.2, AZ - 0.28, tip + 1.7, y + r + 0.36, AZ + 0.28, "snow", up="snow"))
    parts.append(flat(BIT_X0 - 0.12, PLATE_Y1, AZ - 1.0, BIT_X1, PLATE_Y1 + 0.25, AZ + 1.0, "snow", up="snow"))
    return parts


def sleeve() -> list[dict]:
    """A timber sleeve round the middle of the shaft, hooped in brass at both ends."""
    y0, y1 = SLEEVE
    parts = rod_y(y0, y1, 1.35, "log", "log")
    for ya, yb in ((y0 - 0.2, y0 + 0.16), (y1 - 0.16, y1 + 0.2)):
        parts += lane_prism(ya, yb, 1.47, "band")
    return parts


def lantern() -> list[dict]:
    """A brass lantern hanging off the shaft's +X side from the upper band, its glass
    lit by the same flickering fire as the cabin windows."""
    x0, x1, y0, y1 = LANTERN
    xm = (x0 + x1) / 2
    top = BANDS[1][0] + 0.35
    glass = proj(*WINDOWS[0], WIN_Y0 + 0.15, WIN_Y1 - 0.15)
    faces = {s: ("cabin", glass) for s in ("north", "south", "east", "west")}
    faces["up"] = faces["down"] = ("metal", dot_uv("band", y1))
    parts = [bar((AX + SHAFT_R - 0.2, top, AZ), (xm + 0.1, top, AZ), 0.22, 0.26, "metal",
                 faces=lane_faces("band", top + 0.1, top - 0.1)),                          # the arm
             flat(xm - 0.08, y1 + 0.2, AZ - 0.08, xm + 0.08, top, AZ + 0.08, "band"),       # the hook
             box((x0 + 0.08, y0, AZ - 0.38), (x1 - 0.08, y1, AZ + 0.38), "metal", faces=faces,
                 glow=13, shade=False),                                                    # the glass
             flat(x0 - 0.04, y1, AZ - 0.5, x1 + 0.04, y1 + 0.2, AZ + 0.5, "band", up="snow"),  # the hood
             flat(xm - 0.24, y1 + 0.2, AZ - 0.24, xm + 0.24, y1 + 0.34, AZ + 0.24, "band"),
             flat(x0, y0 - 0.2, AZ - 0.46, x1, y0, AZ + 0.46, "band")]                     # the base
    for xp in (x0, x1 - 0.12):                                                              # corner posts
        parts.append(flat(xp, y0, AZ + 0.3, xp + 0.12, y1, AZ + 0.46, "band"))
    return parts


def pine_finial() -> list[dict]:
    """A snow-dusted pine crowning the shaft: a short trunk and four stacked tiers."""
    parts = rod_y(CAP[1] - 0.1, FINIAL[0][0] + 0.2, 0.42, "log", "log")
    for y0, y1, r in FINIAL:
        parts += rod_y(y0, y1, r, "pine", "snow", "pine")
    parts.append(flat(AX - 0.16, FINIAL[-1][1] - 0.05, AZ - 0.16, AX + 0.16, FINIAL[-1][1] + 0.3, AZ + 0.16,
                      "snow", up="snow"))
    return parts


def theme() -> list[dict]:
    return cabin() + ring_studs() + amber_gem() + log_bit() + sleeve() + lantern() + pine_finial()


# ======================================================================================
# 4. TEXTURES
# ======================================================================================

def blend(px, x: int, y: int, colour, k: float, size: int = ATLAS) -> None:
    if k > 0 and 0 <= x < size and 0 <= y < size:
        cur = "#%02x%02x%02x" % px[x, y][:3]
        px[x, y] = rgba(mix(cur, colour, min(1.0, k)))


def paint_theme_lanes(img) -> None:
    px = img.load()
    rng = random.Random(21)
    # bark: lit edge to shadow, streaked along its length, with a few knots
    c0, w = LANES["log"]
    for r in range(ATLAS):
        shift = rng.choice((0, 0, 0, 1, -1))
        for i in range(w):
            k = clamp(4 - i + shift * (1 if i > 0 else 0), 1, 5)
            px[c0 + i, r] = rgba(LOGS[int(k)])
        if rng.random() < 0.12:
            px[c0 + rng.randrange(1, w), r] = rgba(LOGS[1])
    # shingles: alternate courses a shade apart
    c0, w = LANES["roof"]
    for r in range(ATLAS):
        tones = (ROOF[4], ROOF[3], ROOF[2]) if r % 2 == 0 else (ROOF[3], ROOF[2], ROOF[1])
        for i in range(w):
            px[c0 + i, r] = rgba(tones[i])
    # pine needles, snow on the top row of every tier
    paint_lane(img, "pine", [PINE[4], PINE[3], PINE[2], PINE[1]])
    c0, w = LANES["pine"]
    for y in [t[1] for t in FINIAL] + [t[1] for t in BOW_PINE]:
        r = row_of(y - 0.01)
        for i, col in enumerate((SNOW[3], SNOW[2], PINE[5], PINE[4])):
            px[c0 + i, r] = rgba(col)
    for r in range(ATLAS):
        if rng.random() < 0.3:
            px[c0 + rng.randrange(1, w), r] = rgba(PINE[5] if rng.random() < 0.5 else PINE[0])
    paint_lane(img, "snow", [SNOW[4], SNOW[3], SNOW[2]])
    # fieldstone: courses of two stones with mortar between
    c0, _ = LANES["stone"]
    for r in range(ATLAS):
        if r % 3 == 2:
            tones = (STONE[1], STONE[0])
        else:
            tones = (STONE[4], STONE[2]) if (r // 3) % 2 else (STONE[3], STONE[3])
        for i in range(2):
            px[c0 + i, r] = rgba(tones[i])
    # the bit bracket: bevelled brass with a rivet between each pair of logs
    c0, w = LANES["panel"]
    ra, rb = row_of(PLATE_Y1), row_of(PLATE_Y0)
    for r in range(ra, rb + 1):
        for i, col in enumerate((BRASS[6], BRASS[5], BRASS[4], BRASS[4], BRASS[3], BRASS[2])):
            if r == ra:
                col = BRASS[6] if i < 5 else BRASS[4]
            elif r == rb:
                col = BRASS[2] if i else BRASS[3]
            px[c0 + i, r] = rgba(col)
    for y in ((BIT_LOGS[0][0] + BIT_LOGS[1][0]) / 2, (BIT_LOGS[1][0] + BIT_LOGS[2][0]) / 2, PLATE_Y0 + 0.6):
        r = row_of(y)
        px[c0 + 2, r] = rgba(BRASS[6])
        px[c0 + 3, r] = rgba(BRASS[5])
        px[c0 + 3, r + 1] = rgba(BRASS[1])
        px[c0 + 2, r + 1] = rgba(BRASS[2])
    # timber grain on the ring's band: a few darker streaks and knots
    for lane in FACE_LANES:
        c0, w = LANES[lane]
        for r in range(ATLAS):
            if rng.random() < 0.18:
                i = rng.randrange(w)
                cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
                px[c0 + i, r] = rgba(mix(cur, TIMBER[1], 0.5))


def theme_atlas_frame(img, t: float) -> None:
    """The gem's flare throws firelight over the cabin: the logs, roof, snow and pine near
    the bow warm up as the gem swells, then cool again."""
    p = gem_pulse(t)
    if p < 0.03:
        return
    px = img.load()
    for lane, strength in (("log", 0.4), ("roof", 0.3), ("snow", 0.35), ("pine", 0.2), ("stone", 0.3),
                           ("face0", 0.12), ("face1", 0.12), ("face2", 0.12)):
        c0, w = LANES[lane]
        for r in range(row_of(7.0), ATLAS):
            y = Y_TOP - (r + 0.5) / RPU
            k = strength * p * max(0.0, 1 - abs(y - 2.5) / 4.5)
            for i in range(w):
                blend(px, c0 + i, r, LAMP[4], k)


def paint_atlas_base():
    img = canvas(ATLAS, fill=BRASS[3])
    for lane, tones in SKELETON_TONES.items():
        paint_lane(img, lane, tones)
    paint_theme_lanes(img)
    return img


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


INCLUSIONS = ((9, 5, 2.6), (10, 6, 2.9), (5, 10, 2.8), (11, 10, 5.6), (7, 11, 5.3))   # (x, y, ramp index)


def gem_frame(t: float):
    """The amber gem's face: an octagonal cabochon, domed and lit from the upper left,
    honey-bright at its heart, with a few flecks trapped inside; it swells with light and
    throws a star at its flare."""
    p = gem_pulse(t)
    lift = 0.15 + 1.4 * p
    img = canvas(16)
    px = img.load()
    lx, ly = -0.7, -0.7
    for ty in range(16):
        for tx in range(16):
            dx, dy = tx + 0.5 - 8, ty + 0.5 - 8
            ax, ay = abs(dx), abs(dy)
            o = max(ax, ay, (ax + ay) / math.sqrt(2))
            if o > 8:
                continue
            if o > 7.0:
                f = 2.0 + 0.5 * lift
            else:
                d = o / 7.0
                dome = math.sqrt(max(0.0, 1 - d * d))
                f = 3.0 + 2.0 * dome
                f += 0.9 * (-(dx * lx + dy * ly) / 7.0)      # light through the amber pools at the lower right
                f += lift * (0.6 + 0.8 * dome)
            px[tx, ty] = rgba(ramp_at(GEM, f))
    for x, y, f in INCLUSIONS:
        px[x, y] = rgba(ramp_at(GEM, f + 0.5 * lift))
    for x, y in ((4, 5), (5, 4), (6, 4), (4, 6)):
        px[x, y] = rgba(GEM[7])
    px[5, 5] = rgba(mix(GEM[6], GEM[7], 0.5))
    px[11, 12] = rgba(ramp_at(GEM, 5.5 + lift))
    sparkle(img, 5, 5, clamp(p * 1.3 - 0.25), colour="#ffffff", reach=4)
    return img


# --- the cabin facade (window space, 32 px): boards, door, glowing panes ------------------
CAB = 32 / (2 * WINDOW_R)          # texels per model unit in window space


def to_tex(x: float, y: float) -> tuple[float, float]:
    return (x - AX + WINDOW_R) * CAB, (BOW_Y + WINDOW_R - y) * CAB


def rect_px(x0, x1, y0, y1):
    """The texel box (inclusive) of a model-space rectangle."""
    u0, v0 = to_tex(x0, y1)
    u1, v1 = to_tex(x1, y0)
    return int(math.floor(u0 + 0.2)), int(math.floor(v0 + 0.2)), int(math.ceil(u1 - 0.2)) - 1, \
        int(math.ceil(v1 - 0.2)) - 1


def flicker(t: float, phase: float) -> float:
    """0..1: a hearth-fire flicker (whole cycles per loop, so it loops), lifted by the gem."""
    f = 0.55 + 0.2 * math.sin(2 * math.pi * (2 * t + phase)) + 0.13 * math.sin(2 * math.pi * (3 * t + 0.37 + phase)) \
        + 0.08 * math.sin(2 * math.pi * (5 * t + 0.71 + 2 * phase))
    return clamp(f + 0.3 * gem_pulse(t))


def cabin_frame(t: float):
    img = canvas(32, fill=CHINK[0])
    px = img.load()
    rng = random.Random(8)
    # vertical boards (the gables) over the whole sheet
    for x in range(32):
        seam = x % 3 == 0
        for y in range(32):
            c = LOGS[1] if seam else (LOGS[3] if (x // 3) % 2 else LOGS[2])
            if not seam and rng.random() < 0.1:
                c = LOGS[2] if c == LOGS[3] else LOGS[1]
            px[x, y] = rgba(c)
    # the door: darker planks, brass strap hinges, a lit edge
    u0, v0, u1, v1 = rect_px(*DOOR[:2], *DOOR[2:])
    for y in range(v0, v1 + 1):
        for x in range(u0, u1 + 1):
            c = TIMBER[2] if (x - u0) % 2 else TIMBER[3]
            if x == u0:
                c = TIMBER[4]
            if x == u1 or y == v0:
                c = TIMBER[1]
            px[x, y] = rgba(c)
    for y in (v0 + 1, v1 - 1):
        for x in range(u0, u1 - 1):
            px[x, y] = rgba(BRASS[4] if x < u0 + 2 else BRASS[3])
    # the window panes: warm light, brighter up top, a dark cross of glazing bars
    for n, (xa, xb) in enumerate(WINDOWS):
        fl = flicker(t, 0.0 if n == 0 else 0.43)
        u0, v0, u1, v1 = rect_px(xa, xb, WIN_Y0, WIN_Y1)
        um, vm = (u0 + u1 + 1) // 2, (v0 + v1 + 1) // 2
        for y in range(v0, v1 + 1):
            for x in range(u0, u1 + 1):
                k = 1 - (y - v0) / max(1, v1 - v0)
                f = 3.1 + 1.5 * fl + 0.7 * k
                if x in (u0, u1) or y == v0:
                    c = TIMBER[2] if x != u0 else TIMBER[4]
                elif x == um or y == vm:
                    c = mix(LAMP[1], LAMP[3], 0.6 * fl)
                else:
                    c = ramp_at(LAMP, f)
                px[x, y] = rgba(c)
    return img


# --- the night sky in the bow (window, 32 px) ----------------------------------------------
CHIMNEY_TOP = to_tex((CHIMNEY[0] + CHIMNEY[1]) / 2, CHIMNEY[3] + 0.45)
PUFFS = 7                          # smoke puffs in flight at once
MOON = (10.5, 4.5)
STARS = ((6, 6, 0.1), (21, 2, 0.35), (27, 9, 0.6), (13, 2, 0.8), (3, 12, 0.55), (29, 14, 0.2))
FLAKES = ((5, 3, 1, 1.0, 1, 0.0), (9, 20, 2, 1.3, 1, 0.3), (14, 9, 1, 0.8, 2, 0.6), (19, 26, 1, 1.2, 1, 0.15),
          (23, 14, 2, 1.5, 1, 0.8), (28, 5, 1, 0.9, 2, 0.45), (12, 13, 1, 1.1, 1, 0.9), (26, 22, 1, 1.0, 1, 0.5),
          (3, 24, 2, 0.8, 1, 0.7), (17, 1, 1, 1.2, 2, 0.25))    # x, y, speed, sway, sway cycles, phase


def smoke_puff(a: float) -> tuple[float, float, float, float]:
    """Where a puff is at age a (0..1): it rises from the chimney, curls and drifts left
    over the roof, growing and thinning. Returns (u, v, radius, opacity)."""
    u0, v0 = CHIMNEY_TOP
    u = u0 - 9.5 * a + 0.9 * math.sin(2 * math.pi * 1.3 * a)
    v = v0 - 3.8 * (1 - (1 - a) ** 2) - 0.6 * math.sin(math.pi * a)
    rad = 0.9 + 1.5 * a
    op = min(1.0, a * 10) * (1 - a) ** 0.9
    return u, v, rad, op


def window_frame(t: float):
    """Winter night in the bow: dark sky brightening toward the treeline, a crescent moon,
    stars, the gem's warm halo, chimney smoke curling away and snow falling (each flake
    falls one or two whole window heights per loop, so the loop is seamless)."""
    p = gem_pulse(t)
    img = canvas(32)
    px = img.load()
    wall = to_tex(AX, COURSES[-1] + LOG_R)[1]
    for y in range(32):
        for x in range(32):
            if y >= wall:                                   # chinking behind the logs
                px[x, y] = rgba(CHINK[(x + y) % 2])
                continue
            dx, dy = (x + 0.5 - 16) / 16, (y + 0.5 - 16) / 16
            r = math.hypot(dx, dy)
            f = 0.4 + 4.6 * (y / wall)
            if r > 0.8:
                f -= (r - 0.8) * 8
            c = SKY[int(clamp(dither_band(f, x, y), 0, len(SKY) - 1))]
            halo = (0.25 + 0.75 * p) * max(0.0, 1 - r / 0.75) ** 1.4
            if halo > 0.04:
                c = mix(c, GEM[4], min(0.7, halo))
            px[x, y] = rgba(c)
    # distant snowy pines low on the horizon, both sides
    for base_x, h in ((2, 7), (5, 9), (28, 8), (30, 6), (25, 6)):
        top = wall - h
        for yy in range(int(top), int(wall)):
            half = (yy - top) * 0.45
            for xx in range(int(base_x - half), int(base_x + half) + 1):
                if 0 <= xx < 32:
                    c = PINE[1] if (yy - int(top)) % 3 else SNOW[0]
                    px[xx, yy] = rgba(mix(c, SKY[2], 0.35))
    for sx, sy, ph in STARS:
        k = 0.5 + 0.5 * math.cos(2 * math.pi * (t + ph))
        blend(px, sx, sy, "#fff6d8", 0.35 + 0.5 * k, 32)
    mx, my = MOON                                           # crescent moon
    for yy in range(int(my) - 3, int(my) + 3):
        for xx in range(int(mx) - 3, int(mx) + 3):
            d1 = math.hypot(xx + 0.5 - mx, yy + 0.5 - my)
            d2 = math.hypot(xx + 0.5 - mx - 1.3, yy + 0.5 - my + 0.9)
            if d1 <= 2.4 and d2 > 2.1:
                px[xx, yy] = rgba("#fff3cf" if d1 < 1.9 else "#e4d3a6")
    for k in range(PUFFS):                                  # chimney smoke
        u, v, rad, op = smoke_puff((t + k / PUFFS) % 1.0)
        for yy in range(int(v - rad - 1), int(v + rad + 2)):
            for xx in range(int(u - rad - 1), int(u + rad + 2)):
                if not (0 <= xx < 32 and 0 <= yy < wall):
                    continue
                d = math.hypot(xx + 0.5 - u, yy + 0.5 - v) / rad
                if d > 1:
                    continue
                col = SMOKE[0] if yy + 0.5 < v - rad * 0.2 else (SMOKE[1] if d < 0.7 else SMOKE[2])
                blend(px, xx, yy, col, op * (0.95 if d < 0.7 else 0.6), 32)
    for fx0, fy0, speed, amp, freq, ph in FLAKES:          # falling snow
        fy = (fy0 + speed * 32 * t) % 32
        fx = fx0 + amp * math.sin(2 * math.pi * (freq * t + ph))
        for oy in (0, -32):
            x, y = round(fx), round(fy) + oy
            if 0 <= y < wall:
                blend(px, x, y, "#ffffff", 0.95, 32)
                for ddx, ddy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    if 0 <= y + ddy < wall and speed == 2:
                        blend(px, x + ddx, y + ddy, SNOW[3], 0.45, 32)
    return img


def logend() -> object:
    """End grain of a log: bark round the rim, pale sapwood, growth rings, a dark pith."""
    img = canvas(16)
    px = img.load()
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            d = math.hypot(dx, dy)
            if d > 8:
                continue
            if d > 6.9:
                c = LOGS[1] if (x + y) % 3 else LOGS[0]
            elif d > 6.0:
                c = LOGS[5]
            else:
                ring = int(d + 0.35 * math.sin(math.atan2(dy, dx) * 3))
                c = LOGS[4] if ring % 2 else LOGS[5]
                if d < 1.2:
                    c = LOGS[2]
                if dx + dy > 4:
                    c = mix(c, LOGS[2], 0.35)                   # the side away from the light
            px[x, y] = rgba(c)
    px[6, 9] = rgba(LOGS[2])
    px[10, 5] = rgba(LOGS[3])
    return img


def textures() -> None:
    base = paint_atlas_base()
    save_animation(animate(lambda t: atlas_frame(base, t), FRAMES), "metal", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(window_frame, FRAMES), "window", frametime=FRAMETIME)
    save_animation(animate(cabin_frame, GEM_FRAMES), "cabin", frametime=GEM_FRAMETIME, interpolate=True)
    save(logend(), "logend")


# ======================================================================================
# 5. MODELS
# ======================================================================================

def models() -> dict:
    parts = skeleton() + theme()
    return {"main": model(parts, transforms(parts))}
