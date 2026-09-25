"""July Key: the festival-red and gold crate key of summer fireworks (one of the 19 JoshyMC crate keys).

The bow is the set's stepped ring, gilt at the rim and lip and enamelled festival red on its
band, framing a recessed window of night sky. A faceted gold eight-point star floats in the
window round a star-cut garnet held by the six claws (long points on the cardinals, short on
the diagonals, every point ridged into a lit and a shaded half and tipped with a spark), and
seven slender spark-tipped points carry the star's lines on out through the rim (none at the
top, where the collar is), so the bow's outline is a star-burst. A gilt collar joins the ring
to a red-lacquered octagonal shaft with gold bands, two gilt hairlines and a gold ring
riveted with a four-point twinkle; the bit on its -X side is a gilt launch rail carrying
three firework rockets as its teeth (red lacquered bodies with cream bands, gold rings,
stepped gold nose cones and swept fins), and a glowing eight-point sparkle crowns the tip.

Animation (one 3.2 s loop): a warm glint sweeps up the whole key from the bow to the tip;
as it crosses the bow the garnet flashes pink-white and throws a sparkle (a glint also runs
round its facets), and a moment later the garnet fires a ring of gold sparks out through the
whole window that sags, reddens and crackles out. Then little fireworks burst one by one in
the notches between the star's points (a white flash, a ring of sparks with trails, sag and
crackle), each lighting the smoke round it in its own colour; sparkles pop on the red band,
the spark tips twinkle at their own moments and the crown sparkle flares as the glint reaches
it.

The skeleton and atlas machinery (section 1) are copied from january_key.py, so all 19 keys
share proportions, grip, display transforms and the bow-to-tip glint.
"""
from __future__ import annotations

import math

from art.kit import (animate, bar, box, canvas, display, mix, model, move, place, prism, rgba, save_animation,
                     shine, sparkle, turn)

ID = "july_key"
NAME = "July Key"
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
BIT_X0 = 6.0                                        # the launch rail's outer edge (the rockets reach past it)
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
    # theme (42-63): the ridged gold of the star points, glowing spark tips, the rockets'
    # lacquer and cream bands, and the launch rail's front
    "star": (42, 4), "spark": (46, 3), "lacquer": (49, 4), "stripe": (53, 3), "panel": (56, 8),
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#fff3d2", width=8.0, strength=0.85, angle=-90.0, pause=0.4)   # bow -> tip
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
# 2. THEME PALETTE: July, festival red (#FF4D4D) and firework gold
# ======================================================================================
GOLD = ["#3b1307", "#71300c", "#a95711", "#d98d1f", "#f4bb3e", "#ffe077", "#fff7cf"]
RED = ["#2b0418", "#590a23", "#8c112b", "#bd1c32", "#e5323b", "#ff4d4d", "#ff9277", "#ffd3b3"]
GARNET = ["#1c000e", "#40001b", "#6e0529", "#a20d36", "#d31c43", "#ff4d59", "#ff9a9a", "#ffe2da", "#ffffff"]
CREAM = ["#fffaf0", "#f6e3c4", "#d9b996", "#a88062"]
SKY = ["#070619", "#0c0a27", "#130f37", "#1b1548", "#241a58", "#2f2066"]
BURSTS = {                         # firework colours, from white-hot to burnt out
    "gold": ["#ffffff", "#fff4b8", "#ffd54a", "#f59b1f", "#a4520f"],
    "red": ["#ffffff", "#ffd8c8", "#ff6d5e", "#e0263a", "#760c2a"],
    "white": ["#ffffff", "#fffbf0", "#ffecb8", "#d0a8c8", "#5e4a88"],
}
# The light each colour throws on the smoke round it: hues that stay rich over the night
# sky instead of greying it (coral for gold, crimson for red, lavender for white).
SMOKE = {"gold": "#e07a50", "red": "#b02848", "white": "#8a78d8", "grand": "#d2604a"}

# Every skeleton lane: the ring's band fronts are red enamel (inner -> outer), everything
# else is gilt; the shaft is red lacquer (lit edge -> shadow).
SKELETON_TONES = {
    "face0": (RED[4], RED[3], RED[2]), "face1": (RED[5], RED[4], RED[3]), "face2": (RED[6], RED[5], RED[4]),
    "rim0": (GOLD[2], GOLD[1]), "rim1": (GOLD[4], GOLD[3]), "rim2": (GOLD[6], GOLD[5]),
    "lip0": (GOLD[6], GOLD[5]), "lip1": (GOLD[5], GOLD[5]), "lip2": (GOLD[4], GOLD[5]),
    "rim_side": (GOLD[5], GOLD[4], GOLD[3]), "lip_side": (GOLD[3], GOLD[2], GOLD[1]), "step": (GOLD[3], GOLD[2]),
    "shaft": (RED[6], RED[5], RED[4], RED[3]), "band": (GOLD[6], GOLD[5], GOLD[4], GOLD[4]),
    "bezel": (GOLD[1], GOLD[3], GOLD[2]), "cap": (GOLD[5], GOLD[5]),
}

# ======================================================================================
# 3. THEME PARTS: the star-burst, the star-cut garnet, the rocket bit and the spark stars
# ======================================================================================
STAR_ANGLES = tuple(45 * k for k in range(8))            # the burst's eight points
POINT_ANGLES = tuple(a for a in STAR_ANGLES if a != 90)  # points outside the ring (not into the collar)
# A star point is stacked bars (r0, r1, width, depth), each narrower, capped by a spark.
INNER_LONG = ((1.3, 2.2, 1.25, 1.5), (2.2, 2.75, 0.9, 1.3), (2.75, 3.0, 0.58, 1.1))
INNER_SHORT = ((1.3, 1.95, 1.0, 1.4), (1.95, 2.35, 0.72, 1.2), (2.35, 2.5, 0.46, 1.0))
OUTER_LONG = ((4.9, 6.1, 0.8, 1.6), (6.1, 6.75, 0.52, 1.3))
OUTER_SHORT = ((4.9, 5.65, 0.64, 1.5), (5.65, 5.95, 0.42, 1.2))
RAIL_X0, RAIL_Y0, RAIL_Y1 = BIT_X0, 18.1, 25.1          # the launch rail the rockets ride on
ROCKETS = ((24.3, 2.9), (21.7, 3.3), (19.1, 2.5))        # (height, length) of each rocket tooth
ROCKET_R = 0.6
MID_STAR_Y = 15.3                                        # the gold star riveted on the shaft
FINIAL_Y = 27.9                                          # centre of the spark star on the tip


def star_point(c, ang: float, segments, glow: int = 0, tip_glow: int = 0, lane: str = "star") -> list[dict]:
    """A tapering, ridged star point out of c at ang: stacked bars of shrinking width (lit
    half and shaded half either side of the spine) capped by a glowing spark tip."""
    cx, cy = c
    a = math.radians(ang)
    parts = [radial(c, ang, r0, r1, w, d, lane, glow=glow) for r0, r1, w, d in segments]
    r_end, w_end, d_end = segments[-1][1], segments[-1][2], segments[-1][3]
    tip = (cx + r_end * math.cos(a), cy + r_end * math.sin(a))
    parts.append(diamond(tip, w_end / math.sqrt(2), d_end - 0.1, "spark", ang - 45, glow=tip_glow))
    return parts


def star_emblem() -> list[dict]:
    """A gold eight-point star floating round the gem in the night-sky window: long points
    on the cardinals, short ones on the diagonals, every point tipped with a spark."""
    parts = []
    for ang in STAR_ANGLES:
        parts += star_point((AX, BOW_Y), ang, INNER_LONG if ang % 90 == 0 else INNER_SHORT, glow=9, tip_glow=12)
    return parts


def ring_points() -> list[dict]:
    """The star-burst round the ring: seven spark-tipped points in line with the star's
    (none at the top, where the collar is), each flaring from a gold foot on the rim."""
    parts = []
    c = (AX, BOW_Y)
    for ang in POINT_ANGLES:
        long_ = ang % 90 == 0
        a = math.radians(ang)
        parts += star_point(c, ang, OUTER_LONG if long_ else OUTER_SHORT, tip_glow=10)
        foot = (AX + BOW_OUT * math.cos(a), BOW_Y + BOW_OUT * math.sin(a))
        parts.append(diamond(foot, 0.74 if long_ else 0.62, 1.9, "band", ang - 45))
    return parts


def garnet() -> list[dict]:
    """The star-cut garnet: an octagonal body with a painted front and back."""
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


def rod_x(x0: float, x1: float, y: float, r: float, lane: str) -> list[dict]:
    """An octagonal rod along X at height y whose facets sample `lane` (lit edge first)."""
    side = lane_uv(lane, y + 0.3, y - 0.3)
    uv = {"south": side, "north": side, "east": side, "west": side,
          "up": dot_uv(lane, y, 0.3), "down": dot_uv(lane, y, 0.3)}
    return prism(((x0 + x1) / 2, y, AZ), r, x1 - x0, "metal", axis="x", cap="metal", uv=uv)


def rocket(y: float, length: float) -> list[dict]:
    """A firework rocket pointing -X off the launch rail: a red lacquered body with a cream
    band, a gold ring, a stepped gold nose cone and swept gold fins at its tail."""
    tail = RAIL_X0 - 0.05
    tip = tail - length
    nose = 1.2
    nb = tip + nose
    parts = rod_x(nb, tail + 0.4, y, ROCKET_R, "lacquer")
    m = nb + (tail - nb) * 0.5
    parts += rod_x(m - 0.2, m + 0.2, y, ROCKET_R + 0.05, "stripe")
    parts += rod_x(nb - 0.05, nb + 0.28, y, ROCKET_R + 0.1, "band")
    parts += rod_x(nb - 0.5, nb, y, 0.47, "star")
    e = box((nb - 0.85, y - 0.27, AZ - 0.27), (nb - 0.45, y + 0.27, AZ + 0.27), "metal",
            faces=lane_faces("star", y + 0.3, y - 0.3))
    parts.append(turn(e, 45, "x", (nb - 0.65, y, AZ)))
    parts.append(diamond((nb - 0.9, y), 0.3, 0.36, "spark", 45))
    for s in (1, -1):                                     # fins, swept back to the rail
        p0 = (tail - 1.0, y + s * 0.35)
        p1 = (tail - 0.12, y + s * 1.0)
        parts.append(bar((*p0, AZ), (*p1, AZ), 0.36, 0.24, "metal",
                         faces=lane_faces("band", max(p0[1], p1[1]), min(p0[1], p1[1]))))
    return parts


def rocket_bit() -> list[dict]:
    """The bit: a gilt launch rail on the shaft's -X side carrying three rocket teeth."""
    parts = bit_plate(RAIL_X0, RAIL_Y0, RAIL_Y1, 1.9)
    for y, length in ROCKETS:
        parts += rocket(y, length)
    parts += [box((RAIL_X0 - 0.2, RAIL_Y1 - 0.05, AZ - 1.1), (BIT_X1, RAIL_Y1 + 0.4, AZ + 1.1), "metal",
                  faces=lane_faces("band", RAIL_Y1 + 0.4, RAIL_Y1 - 0.05)),
              box((RAIL_X0 - 0.2, RAIL_Y0 - 0.4, AZ - 1.1), (BIT_X1, RAIL_Y0 + 0.05, AZ + 1.1), "metal",
                  faces=lane_faces("band", RAIL_Y0 + 0.05, RAIL_Y0 - 0.4))]
    return parts


def twinkle_star(c, arms, w: float, d: float, z: float = AZ, glow: int = 0) -> list[dict]:
    """A sparkle star in the front plane: a big diamond heart and points that taper in two
    steps to a spark tip. arms = ((angle, reach), ...); w is the width of a point at its root."""
    parts = [diamond(c, w * 1.75, d, "spark", glow=glow)]
    for ang, reach in arms:
        r0, r1 = w * 0.6, max(w * 0.9, reach * 0.52)
        parts += star_point(c, ang, ((r0, r1, w * 0.85, d * 0.9), (r1, reach - w * 0.24, w * 0.48, d * 0.8)),
                            glow=glow, tip_glow=glow, lane="spark")
    return move(parts, dz=z - AZ)


def mid_star() -> list[dict]:
    """A gold ring round the middle of the shaft with a four-point twinkle riveted on its
    front and back."""
    parts = lane_prism(MID_STAR_Y - 0.35, MID_STAR_Y + 0.35, 1.35, "band")
    arms = ((0, 1.75), (180, 1.75), (90, 1.3), (270, 1.3))
    for z in (AZ + 1.36, AZ - 1.36):
        parts += twinkle_star((AX, MID_STAR_Y), arms, 0.56, 0.3, z, glow=8)
    return parts


def spark_finial() -> list[dict]:
    """A gold neck on the cap crowned by a glowing eight-point spark star, its lowest point
    sunk into the neck."""
    parts = lane_prism(26.2, 26.9, 0.62, "band")
    c = (AX, FINIAL_Y)
    arms = ((90, 1.75), (0, 1.55), (180, 1.55), (270, 1.3), (45, 1.0), (135, 1.0), (225, 0.95), (315, 0.95))
    parts += twinkle_star(c, arms[:4], 0.62, 0.8, glow=13)
    for ang, reach in arms[4:]:
        parts += star_point(c, ang, ((0.35, reach - 0.12, 0.34, 0.6),), glow=12, tip_glow=12, lane="spark")
    return parts


def theme() -> list[dict]:
    return star_emblem() + ring_points() + garnet() + rocket_bit() + mid_star() + spark_finial()


# ======================================================================================
# 4. TEXTURES
# ======================================================================================
PANEL = [                      # the launch rail's front: 8 texels wide, 14 rows (y 25.1 .. 18.1)
    "65555554",
    "6abccba3",
    "65555543",
    "6abccba3",
    "6ab5cba3",
    "6abccba3",
    "6abccba3",
    "65555543",
    "6abccba3",
    "6ab5cba3",
    "6abccba3",
    "6abccba3",
    "65555543",
    "43333332",
]


PINSTRIPES = (14.2, 16.4)         # gilt hairlines framing the star on the red shaft


def paint_theme_lanes(img) -> None:
    paint_lane(img, "star", [GOLD[6], GOLD[5], GOLD[3], GOLD[2]])
    paint_lane(img, "spark", [GOLD[5], GOLD[4], GOLD[3]])
    paint_lane(img, "lacquer", [RED[6], RED[5], RED[4], RED[2]])
    paint_lane(img, "stripe", CREAM[:3])
    px = img.load()
    pal = {"a": RED[3], "b": RED[4], "c": RED[5]}
    pal.update({str(i): GOLD[i] for i in range(7)})
    c0, _ = LANES["panel"]
    r0 = row_of(RAIL_Y1)
    for j, line in enumerate(PANEL):
        for i, ch in enumerate(line):
            if 0 <= r0 + j < ATLAS:
                px[c0 + i, r0 + j] = rgba(pal[ch])
    for y in PINSTRIPES:
        paint_lane(img, "shaft", [GOLD[6], GOLD[5], GOLD[4], GOLD[3]], rows=[row_of(y)])


def _hash(*v) -> float:
    return (math.sin(sum(x * m for x, m in zip(v, (12.9898, 78.233, 37.719, 4.581)))) * 43758.5453) % 1.0


def twinkle(t: float, phase: float, width: float = 1 / 6) -> float:
    """0..1: a short sharp twinkle once per loop, peaking at `phase`."""
    d = (t - phase + 0.5) % 1.0 - 0.5
    return math.cos(math.pi * d / (2 * width)) ** 3 if abs(d) < width else 0.0


# Spark-lane heights that twinkle together: (y_lo, y_hi, phase). The finial star flares
# just after the glint reaches the tip; everything else twinkles at its own moment.
TWINKLE_ZONES = ((25.9, 30.0, 0.53), (13.5, 17.2, 0.82))
# Sparkles popping on the red band: (band lane, height, loop phase). face2 lanes are the
# ring's left side, face0 its right, so each pop lands on one band segment.
POPS = (("face2", 6.3, 0.30), ("face0", 1.1, 0.41), ("face2", 0.5, 0.56), ("face0", 6.7, 0.66),
        ("face2", 3.3, 0.79), ("face0", 4.1, 0.91))


def spark_phase(r: int) -> float:
    y = Y_TOP - (r + 0.5) / RPU
    for lo, hi, phase in TWINKLE_ZONES:
        if lo <= y <= hi:
            return phase
    return _hash(r // 2, 7)


def blend(px, x: int, y: int, colour, k: float) -> None:
    if k > 0 and 0 <= y < ATLAS:
        cur = "#%02x%02x%02x" % px[x, y][:3]
        px[x, y] = rgba(mix(cur, colour, k))


def theme_atlas_frame(img, t: float) -> None:
    """The spark tips twinkle white, each height at its own moment; sparkles pop on the red
    band; and the gem's flash floods the star round it with warm light."""
    px = img.load()
    c0, w = LANES["spark"]
    for r in range(ATLAS):
        k = twinkle(t, spark_phase(r))
        for i in range(w):
            blend(px, c0 + i, r, "#ffffff", 0.9 * k)
    frame = round(t * FRAMES) % FRAMES
    for lane, y, phase in POPS:
        age = (frame - round(phase * FRAMES)) % FRAMES
        if age > 2:
            continue
        c, _ = LANES[lane]
        r = row_of(y)
        core, arm = (("#ffe9a0", 0.9), ("#ffd35a", 0.35)) if age == 0 else             ((("#ffffff", 1.0), ("#fff1b8", 0.85)) if age == 1 else (("#ffd35a", 0.6), (GOLD[4], 0.3)))
        blend(px, c + 1, r, core[0], core[1])
        for x, yy in ((c, r), (c + 2, r), (c + 1, r - 1), (c + 1, r + 1)):
            blend(px, x, yy, arm[0], arm[1])
        if age == 1:
            for x, yy in ((c, r - 1), (c + 2, r - 1), (c, r + 1), (c + 2, r + 1)):
                blend(px, x, yy, GOLD[5], 0.25)
    p = gem_pulse(t)
    if p < 0.03:
        return
    c0, w = LANES["star"]
    for r in range(ATLAS):
        y = Y_TOP - (r + 0.5) / RPU
        k = 0.5 * p * max(0.0, 1 - abs(y - BOW_Y) / 5.0)
        if k <= 0.01:
            continue
        for i in range(w):
            blend(px, c0 + i, r, "#fff1c8", k)


def paint_atlas_base():
    img = canvas(ATLAS, fill=GOLD[3])
    for lane, tones in SKELETON_TONES.items():
        paint_lane(img, lane, tones)
    paint_theme_lanes(img)
    return img


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


def gem_frame(t: float):
    """The star-cut garnet: an octagonal table ringed by an eight-point star of facets and
    kite facets out to the girdle, lit from the upper left; a glint travels round the
    facets, and at the flash the whole stone blazes pink-white and throws a sparkle."""
    p = gem_pulse(t)
    lift = 0.25 + 1.7 * p
    img = canvas(16)
    px = img.load()
    light = math.radians(150)
    glint = (t * 8) % 8
    for ty in range(16):
        for tx in range(16):
            dx, dy = tx + 0.5 - 8, 8 - (ty + 0.5)
            ax, ay = abs(dx), abs(dy)
            o = max(ax, ay, (ax + ay) / math.sqrt(2))
            if o > 8:
                continue
            ang = math.atan2(dy, dx)
            s = ang / (math.pi / 4)
            k = round(s)
            frac = s - k                                     # -0.5..0.5 within a star point's sector
            r = math.hypot(dx, dy)
            star_r = 3.3 + 2.8 * (1 - abs(frac) * 2)
            if o > 7.2:
                f = 0.6                                      # girdle
            elif o <= 3.1:
                f = 4.1 if dx * math.cos(light) + dy * math.sin(light) > 0.3 else 3.5   # the table
            elif r <= star_r:                                # star facets, split down the middle
                n = k * math.pi / 4 + (0.12 if frac > 0 else -0.12)
                f = 3.0 + 1.6 * math.cos(n - light) + (0.35 if frac > 0 else -0.2)
            else:                                            # kite facets between the star points
                n = (k + (0.5 if frac > 0 else -0.5)) * math.pi / 4
                f = 2.2 + 1.5 * math.cos(n - light)
            idx = (k if r <= star_r else k + (0.5 if frac > 0 else -0.5)) % 8
            g = max(0.0, 1 - min(abs(idx - glint), 8 - abs(idx - glint)) / 0.9)
            f += 1.1 * g * (1 - p)
            px[tx, ty] = rgba(ramp_at(GARNET, f + lift * (1.0 if o <= 7.2 else 0.5)))
    for x, y in ((5, 5), (6, 5), (5, 6)):
        px[x, y] = rgba(GARNET[8])
    px[10, 10] = rgba(ramp_at(GARNET, 5.5 + lift))
    sparkle(img, 6, 6, clamp(p * 1.3 - 0.25), colour="#ffffff", reach=4)
    return img


# The window's show (texels, frames). Right after the garnet flashes it fires a ring of
# sparks out through the whole window (GRAND); then little fireworks burst one by one in the
# notches between the star's points. SHOWS: (notch, launch frame, colours, sparks, radius)
GRAND = (round(GEM_PEAK * FRAMES) + 2) % FRAMES
GRAND_LIFE = 12
GRAND_PAL = ["#ffffff", "#fff4b8", "#ffd54a", "#ff9a3a", "#e0263a", "#760c2a"]
SHOWS = [(0, 15, "gold", 10, 4.6), (4, 19, "red", 10, 4.4), (2, 23, "white", 10, 4.4), (6, 27, "gold", 9, 4.2),
         (3, 31, "red", 10, 4.6), (7, 3, "white", 9, 4.2)]
BURST_LIFE = 12               # frames a burst lasts
BURST_R = 12.4                # texels from the window's centre to each burst
STARS = ((4, 9), (27, 7), (9, 26), (24, 27), (15, 3), (30, 17), (2, 18), (20, 12), (12, 29), (29, 24))


def burst_centre(notch: int) -> tuple[float, float]:
    a = math.radians(22.5 + 45 * notch)
    return 16 + BURST_R * math.cos(a), 16 - BURST_R * math.sin(a)


def window_frame(t: float):
    """Night sky in the bow: deep indigo, darker at the rim, far stars twinkling, and
    fireworks blooming in the notches between the star's points: a white flash, a ring of
    sparks spreading with trails, sagging and crackling out, each lighting the smoke round
    it in its own colour as it goes."""
    frame = round(t * FRAMES) % FRAMES
    img = canvas(32)
    px = img.load()
    lights = []
    grand = (frame - GRAND) % FRAMES
    ring = grand_radius(grand) if grand < GRAND_LIFE else None
    for notch, launch, colours, _, radius in SHOWS:
        age = (frame - launch) % FRAMES
        if age < BURST_LIFE:
            lights.append((*burst_centre(notch), age, radius, SMOKE[colours]))
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 16, (y + 0.5 - 16) / 16
            r = math.hypot(dx, dy)
            f = 1.0 + 3.8 * (y / 31)
            if r > 0.8:
                f -= (r - 0.8) * 9
            c = SKY[int(clamp(round(f), 0, len(SKY) - 1))]
            for bx, by, age, radius, tint in lights:
                d = math.hypot(x + 0.5 - bx, y + 0.5 - by)
                fade = 1.0 if age <= 2 else max(0.0, 1 - (age - 2) / 7)
                k = max(0.0, 1 - d / (radius + 5.0)) * fade * 0.7
                if k > 0.02:
                    c = mix(c, tint, k)
            if ring is not None:                  # the grand burst lights the smoke behind it
                d = math.hypot(x + 0.5 - 16, y + 0.5 - 16)
                fade = 1.0 if grand <= 2 else max(0.0, 1 - (grand - 2) / 7)
                k = max(0.0, 1 - abs(d - ring) / 4.5) * fade * 0.7
                if k > 0.02:
                    c = mix(c, SMOKE["grand"], k)
            px[x, y] = rgba(c)
    for i, (sx, sy) in enumerate(STARS):
        k = 0.5 + 0.5 * math.sin(2 * math.pi * (t * (1 + i % 2) + _hash(i, 3)))
        cur = "#%02x%02x%02x" % px[sx, sy][:3]
        px[sx, sy] = rgba(mix(cur, "#ffe9c0", 0.2 + 0.6 * k))
    for n, (notch, launch, colours, sparks, radius) in enumerate(SHOWS):
        age = (frame - launch) % FRAMES
        if age < BURST_LIFE:
            draw_burst(img, *burst_centre(notch), age, BURSTS[colours], sparks, radius, n, frame)
    if ring is not None:
        draw_grand(img, grand, frame)
    return img


def grand_radius(age: int) -> float:
    """How far (texels from the centre) the grand burst's sparks have flown at `age`."""
    return 8.0 + 7.0 * (1 - (1 - min(1.0, age / 5.0)) ** 2)


def draw_grand(img, age: int, frame: int) -> None:
    """The garnet's ring burst: a flash round the star, then sixteen sparks with trails
    flying out to the lip (half of them straight down the notches), sagging, reddening and
    crackling out."""
    if age == 0:
        for i in range(40):
            th = 2 * math.pi * i / 40
            put(img, 16 + 8.6 * math.cos(th), 16 + 8.6 * math.sin(th), GRAND_PAL[1], 0.85)
        return
    r = grand_radius(age)
    drop = 0.07 * max(0, age - 5) ** 2
    ci = min(len(GRAND_PAL) - 1, 1 + int(age / GRAND_LIFE * (len(GRAND_PAL) - 1.2)))
    for i in range(16):
        th = 2 * math.pi * i / 16
        if age > 6 and _hash(i, frame, 5) < 0.3 + 0.08 * (age - 6):
            continue                                                 # crackling out
        put(img, 16 + r * math.cos(th), 16 + r * math.sin(th) + drop, GRAND_PAL[ci])
        if age <= 6:
            for back, k in ((1.2, 0.8), (2.4, 0.5)):
                q = r - back * (1.4 if age <= 3 else 1.0)
                put(img, 16 + q * math.cos(th), 16 + q * math.sin(th) + drop, GRAND_PAL[min(ci + 1, 5)], k)


def put(img, x: float, y: float, colour, k: float = 1.0) -> None:
    xi, yi = int(math.floor(x)), int(math.floor(y))
    if 0 <= xi < 32 and 0 <= yi < 32:
        px = img.load()
        cur = "#%02x%02x%02x" % px[xi, yi][:3]
        px[xi, yi] = rgba(mix(cur, colour, k))


def draw_burst(img, cx: float, cy: float, age: int, pal, sparks: int, radius: float, seed: int, frame: int) -> None:
    """One firework at `age` frames: flash, bloom with trails, sag and crackle out."""
    tau = age / BURST_LIFE
    if age == 0:                                                     # the shell bursts: a white flash
        for ox, oy, k in ((0, 0, 1.0), (1, 0, 0.85), (-1, 0, 0.85), (0, 1, 0.85), (0, -1, 0.85),
                          (1, 1, 0.4), (-1, 1, 0.4), (1, -1, 0.4), (-1, -1, 0.4)):
            put(img, cx + ox, cy + oy, pal[0], k)
        return
    spread = 1 - (1 - min(1.0, (age - 0.3) / 5.0)) ** 2               # eases out to the full radius
    r = radius * spread
    drop = 0.08 * max(0, age - 4) ** 2                               # gravity once the bloom slows
    ci = min(len(pal) - 1, int(tau * (len(pal) - 0.3)))
    spin = _hash(seed, 1) * math.pi
    for i in range(sparks):
        th = spin + 2 * math.pi * i / sparks
        sx, sy = cx + r * math.cos(th), cy + r * math.sin(th) + drop
        if tau > 0.5 and _hash(seed, i, frame) < 0.35 + 0.6 * (tau - 0.5):
            continue                                                 # crackling out
        put(img, sx, sy, pal[ci])
        if age <= 6:                                                 # trails back toward the centre
            for f_, k in ((0.72, 0.75), (0.45, 0.45)):
                put(img, cx + r * f_ * math.cos(th), cy + r * f_ * math.sin(th) + drop * f_,
                    pal[min(len(pal) - 1, ci + 1)], k)
    if age <= 2:
        put(img, cx, cy, pal[1], 0.9)


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
