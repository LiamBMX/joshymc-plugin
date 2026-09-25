"""June Key: the midsummer-gold crate key of the summer sun (one of the 19 JoshyMC crate keys).

The bow is the summer sun. The set's stepped ring, in polished gold, frames a window of
bright summer sky, and in it a sun-star of twelve glowing rays (the long ones growing out
of the claws, all stopping short of the lip so a ring of sky shows round the sun) radiates
from an octagonal citrine. Outside the ring a corona of twenty-one slender gold rays bursts
out all round in three lengths, the longest in line with the claws, leaving the top to the
collar; six little citrines are set in the band where the long rays pierce it. Halfway up
the octagonal shaft a gold band is set with four citrines, the bit on its -X side is an
art-deco rising sun (a fan of wedge pleats, recessed by turns, spreading from a citrine
boss, its three bright pleats running on as pointed rays: the teeth), and a four-point gold
star with a citrine bead crowns the shaft.

Animation (one 3.2 s loop): a warm glint sweeps up the whole key from the bow to the tip,
rays included; the citrine flares as it passes (with every little citrine in step), a pulse
of light runs out along the rays, and the stone swells softly again half a loop later.
Three sunbeams wheel clockwise round the sun, lighting each ray of the corona and the
sun-star in turn and flaring its point, while the corona's points twinkle one by one; in
the window a sunburst of paler sky slowly turns and glints twinkle in the heat, and heat
glints climb the shaft.

Shared skeleton: section 1 is the set's skeleton copied from january_key.py (see its
docstring for the proportions and the atlas machinery); only the theme lanes (42-63) and
the glint colours differ. On top of the metal atlas, June adds a "rays" texture: one band
of columns per ray direction (rows = radius), so a ray can be lit by angle for the wheeling
beams while still catching the key's glint at its own height.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, box, canvas, display, mix, model, place, prism, rgba, save_animation, shine,
                     sparkle, turn, wave)

ID = "june_key"
NAME = "June Key"
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
    "pleat": (42, 3), "orn": (45, 3), "panel": (54, 10),
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#fff6d6", width=8.0, strength=0.85, angle=-90.0, pause=0.4)   # bow -> tip
TRAIL = dict(colour="#fffdf2", width=2.5, strength=0.7, angle=-90.0, pause=0.4)
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
# 2. THEME PALETTE: June, midsummer gold (#FFD93D), citrine and a summer sky
# ======================================================================================
METAL = GOLD = ["#3d1206", "#6e2a0c", "#a64d12", "#d4801c", "#efac2c", "#ffd93d", "#fff3ad"]
GEM = ["#4a1300", "#7d2800", "#b44600", "#e56d08", "#ff9418", "#ffbb33", "#ffe48a", "#fffbe8"]
SKY = ["#0f3a8c", "#1650ad", "#1f6bca", "#2c89df", "#43a6ee", "#66c2f7", "#9adcff"]
SUN = ["#ffd24a", "#ffe98a", "#fff7cf", "#fffdf2"]
HOT = "#fffbe6"                   # white-hot sunlight

# ======================================================================================
# 3. THEME PARTS: the sun (corona, sun-star, citrine), rising-sun bit, citrine band, star
# ======================================================================================
CORONA_ANGLES = tuple(a for a in range(0, 360, 15) if a not in (75, 90, 105))   # the top is the collar's
XL_ANGLES = {a % 360 for a in CLAW_ANGLES if a % 360 != 90}  # the longest rays line up with the claws
CORONA = {"xl": (7.55, 0.95, 3), "l": (6.85, 0.8, 2), "s": (6.15, 0.62, 1)}   # reach, width, blocks
STAR_ANGLES = tuple(range(0, 360, 30))                       # the sun-star inside the ring
HUB = (6.95, 21.6)                                           # the rising sun on the bit
FAN_R = 2.3                                                  # where the dark pleats end
PLEAT_ANGLES = tuple(90 + (i + 0.5) * 180 / 7 for i in range(7))   # bright pleats on odd i
TEETH = ((PLEAT_ANGLES[1], 3.35), (PLEAT_ANGLES[3], 4.0), (PLEAT_ANGLES[5], 3.35))   # angle, reach
STAR = (AX, 28.0)                                            # the four-point star on top
STAR_ARMS = ((90, 2.05, 2, 0.62), (0, 1.55, 2, 0.56), (180, 1.55, 2, 0.56))   # angle, reach, blocks, width
SUN_BAND = (14.5, 15.7, 1.42)                                # the citrine-studded band

# --- the rays texture: every ray samples its own band of columns -------------------------
# Columns: one 2-texel band per ray direction (lit half, shadowed half of its ridge). Rows:
# radius from the band's centre, RAY_R0 at row 0 counting inward, so the corona (outer rows)
# and the sun-star (inner rows) at the same angle share a band. Every texel knows its model
# height, so the key's glint crosses the rays exactly where it crosses the atlas, and every
# band knows its angle, so the sunbeams can wheel round the sun.
RAY_SIZE = 64
RAY_TPU = RAY_SIZE / 16
RAY_RPU = 8
RAY_R0 = 8.0
RAY_BAND = 2


def _bands() -> dict:
    out: dict = {}

    def add(name, c, ang, role):
        out[name] = dict(col=len(out) * RAY_BAND, cx=c[0], cy=c[1], ang=ang, role=role)

    for ang in range(0, 360, 15):
        add(f"bow{ang}", (AX, BOW_Y), ang, "bow")
    for i, (ang, _) in enumerate(TEETH):
        add(f"tooth{i}", HUB, ang, "tooth")
    for ang, *_ in STAR_ARMS:
        add(f"star{ang}", STAR, ang, "star")
    return out


RAYS = _bands()


def ray_v(r: float) -> float:
    return clamp((RAY_R0 - r) * RAY_RPU / RAY_TPU, 0.0, 16.0)


def ray_uv(name: str, r_hi: float, r_lo: float, cols=(0.0, 2.0), flip: bool = False) -> list[float]:
    c = RAYS[name]["col"]
    ua, ub = (c + cols[0]) / RAY_TPU, (c + cols[1]) / RAY_TPU
    va, vb = ray_v(r_hi), ray_v(r_lo)
    if abs(vb - va) < 1 / RAY_TPU:
        m = clamp((va + vb) / 2, 0.5 / RAY_TPU, 16 - 0.5 / RAY_TPU)
        va, vb = m - 0.5 / RAY_TPU, m + 0.5 / RAY_TPU
    uv = [ub, va, ua, vb] if flip else [ua, va, ub, vb]
    return [round(x, 4) for x in uv]


def ray_dot(name: str, col: int, r: float) -> list[float]:
    c = RAYS[name]["col"] + col
    row = int(clamp((RAY_R0 - r) * RAY_RPU, 0, RAY_SIZE - 1))
    return [round((c + 0.25) / RAY_TPU, 4), round((row + 0.25) / RAY_TPU, 4),
            round((c + 0.75) / RAY_TPU, 4), round((row + 0.75) / RAY_TPU, 4)]


def ray_block(name: str, r0: float, r1: float, w: float, d: float, z: float = AZ, glow: int = 0) -> dict:
    """A straight piece of a ray from radius r0 to r1 round its band's centre; the band's lit
    half lands on the side of the ridge that faces the light."""
    b = RAYS[name]
    cx, cy, ang = b["cx"], b["cy"], b["ang"]
    a = math.radians(ang)
    flip = lit((-math.sin(a), math.cos(a))) < 0
    east_lit = lit((math.sin(a), -math.cos(a))) > 0
    faces = {"south": ray_uv(name, r1, r0, flip=flip), "north": ray_uv(name, r1, r0, flip=not flip),
             "east": ray_uv(name, r1, r0, (0, 1) if east_lit else (1, 2)),
             "west": ray_uv(name, r1, r0, (1, 2) if east_lit else (0, 1)),
             "up": ray_dot(name, 0, r1), "down": ray_dot(name, 1, r0)}
    e = box((cx - w / 2, cy + r0, z - d / 2), (cx + w / 2, cy + r1, z + d / 2), "rays",
            faces={s: ("rays", uv) for s, uv in faces.items()}, glow=glow)
    return turn(e, ang - 90, "z", (cx, cy, z))


def ray_tip(name: str, r_c: float, diag: float, d: float, z: float = AZ, glow: int = 0) -> dict:
    """The point of a ray: a square turned corner-first, centred at radius r_c, in the
    lit colour."""
    b = RAYS[name]
    cx, cy, ang = b["cx"], b["cy"], b["ang"]
    a = math.radians(ang)
    x, y = cx + r_c * math.cos(a), cy + r_c * math.sin(a)
    s = diag / math.sqrt(2)
    uv = ray_uv(name, r_c + diag / 2, r_c - diag / 2, (0, 1))
    e = box((x - s / 2, y - s / 2, z - d / 2), (x + s / 2, y + s / 2, z + d / 2), "rays",
            faces={side: ("rays", uv) for side in ("north", "south", "east", "west", "up", "down")}, glow=glow)
    return turn(e, ang - 45, "z", (x, y, z))


TAPER = {1: ((0.0, 1.0),), 2: ((0.0, 0.62), (0.62, 1.0)), 3: ((0.0, 0.5), (0.5, 0.8), (0.8, 1.0))}


def sun_ray(name: str, r0: float, r1: float, w: float, d: float, blocks: int = 2, z: float = AZ,
            glow: int = 0) -> list[dict]:
    """A ray from radius r0 to its point at r1: blocks narrowing outward, then a point."""
    widths = [w * (1 - 0.32 * i) for i in range(blocks)]
    rt = r1 - widths[-1] / 2
    parts = []
    for i, (f0, f1) in enumerate(TAPER[blocks]):
        a0, a1 = r0 + (rt - r0) * f0, r0 + (rt - r0) * f1
        parts.append(ray_block(name, a0 - (0.04 if i else 0.0), a1, widths[i], d - 0.1 * i, z, glow))
    parts.append(ray_tip(name, rt, widths[-1], d - 0.1 * blocks, z, glow))
    return parts


def corona_size(ang: int) -> tuple[float, float, int]:
    """(reach, width, blocks) of the corona ray at angle ang."""
    return CORONA["xl" if ang in XL_ANGLES else ("l" if ang % 30 == 0 else "s")]


def corona() -> list[dict]:
    """Twenty-one gold rays bursting out of the ring in three lengths, the longest in line
    with the claws; the top is left to the collar."""
    parts = []
    for ang in CORONA_ANGLES:
        reach, w, blocks = corona_size(ang)
        parts += sun_ray(f"bow{ang}", 5.0, reach, w, 1.2, blocks)
    return parts


def ring_studs() -> list[dict]:
    """Six little citrines set in the band where the long rays pierce the ring."""
    parts = []
    for ang in CLAW_ANGLES:
        a = math.radians(ang)
        c = (AX + BAND_R * math.cos(a), BOW_Y + BAND_R * math.sin(a))
        parts.append(turn(gem_chip(c, 0.62, BOW_D + 0.22, glow=10, crop=3.5), ang - 45, "z", (c[0], c[1], AZ)))
    return parts


def sun_star() -> list[dict]:
    """The emblem: twelve glowing rays round the citrine, the long ones growing out of the
    claws, stopping short of the lip so a ring of sky shows round the sun."""
    parts = []
    for ang in STAR_ANGLES:
        if ang in {a % 360 for a in CLAW_ANGLES}:
            parts += sun_ray(f"bow{ang}", 1.45, 3.0, 0.66, 1.3, 2, glow=13)
        else:
            parts += sun_ray(f"bow{ang}", 1.45, 2.45, 0.5, 1.2, 1, glow=13)
    return parts


def citrine() -> list[dict]:
    """The heart gem: an octagonal citrine, a body plus a painted front and back."""
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


GEM_BODY = [5, 7, 6, 8]


def gem_chip(c, s: float, d: float, glow: int = 12, z: float = AZ, axis: str = "z", crop: float = 0.0) -> dict:
    """A small citrine: the gem's painted face on the two faces across `axis`, its body
    colour on the rest. crop trims the face's dark rim (texels from each edge) so a tiny
    chip shows the bright heart of the stone."""
    x, y = c
    fronts = {"z": ("south", "north"), "x": ("east", "west")}[axis]
    faces = {side: ("gem", GEM_BODY) for side in ("north", "south", "east", "west", "up", "down")}
    lo, hi = crop, 16 - crop
    faces[fronts[0]] = ("gem", [lo, lo, hi, hi])
    faces[fronts[1]] = ("gem", [hi, lo, lo, hi])
    if axis == "z":
        frm, to = (x - s / 2, y - s / 2, z - d / 2), (x + s / 2, y + s / 2, z + d / 2)
    else:
        frm, to = (x - d / 2, y - s / 2, z - s / 2), (x + d / 2, y + s / 2, z + s / 2)
    return box(frm, to, "gem", faces=faces, glow=glow)


def sunrise_bit() -> list[dict]:
    """The bit: an art-deco rising sun. A fan of wedge-shaped pleats spreads from a citrine
    on the shaft, dark and recessed by turns with bright raised ones, and the three bright
    pleats run on past the fan as pointed rays: the teeth."""
    parts = []
    for i, ang in enumerate(PLEAT_ANGLES):
        if i % 2 == 0:
            parts.append(radial(HUB, ang, 0.85, 1.5, 0.55, 1.45, "pleat"))
            parts.append(radial(HUB, ang, 1.45, FAN_R, 1.02, 1.45, "pleat"))
    for i, (ang, reach) in enumerate(TEETH):
        name = f"tooth{i}"
        parts.append(ray_block(name, 0.85, 1.5, 0.5, 1.8))
        parts.append(ray_block(name, 1.45, FAN_R + 0.05, 0.92, 1.8))
        parts += sun_ray(name, FAN_R, reach, 0.8 if i == 1 else 0.7, 1.7, 2)
    uv = {s: dot_uv("orn", HUB[1], 0.9) for s in ("north", "south", "east", "west")}
    uv["up"] = uv["down"] = dot_uv("orn", HUB[1], 0.5)
    parts += prism((HUB[0], HUB[1], AZ), 0.92, 2.2, "metal", axis="z", cap="metal", uv=uv)
    parts.append(gem_chip(HUB, 1.25, 2.5))
    return parts


def sun_band() -> list[dict]:
    """A gold band round the middle of the shaft set with four little citrines."""
    y0, y1, r = SUN_BAND
    parts = lane_prism(y0, y1, r, "orn")
    y = (y0 + y1) / 2
    for dz in (1, -1):
        parts.append(gem_chip((AX, y), 0.62, 0.4, glow=11, z=AZ + dz * (r + 0.08), crop=2.5))
    for dx in (1, -1):
        parts.append(gem_chip((AX + dx * (r + 0.08), y), 0.62, 0.4, glow=11, axis="x", crop=2.5))
    return parts


def star_finial() -> list[dict]:
    """A four-point star crowning the shaft, a citrine bead at its heart and small points
    on its diagonals."""
    parts = lane_prism(CAP[1] - 0.1, STAR[1] - 0.3, 0.5, "orn")
    for ang, reach, blocks, w in STAR_ARMS:
        parts += sun_ray(f"star{ang}", 0.2, reach, w, 0.85, blocks, glow=6)
    for ang in (45, 135):                                   # small points on the diagonals
        a = math.radians(ang)
        for sgn in (1, -1):
            c = (AX + sgn * 0.62 * math.cos(a), STAR[1] + sgn * 0.62 * math.sin(a))
            parts.append(diamond(c, 0.42, 0.5, "orn", ang - 45 + (180 if sgn < 0 else 0)))
    parts.append(gem_chip(STAR, 0.74, 1.15, glow=13))
    return parts


def theme() -> list[dict]:
    return corona() + ring_studs() + sun_star() + citrine() + sunrise_bit() + sun_band() + star_finial()


# ======================================================================================
# 4. TEXTURES
# ======================================================================================

def paint_theme_lanes(img) -> None:
    paint_lane(img, "pleat", [GOLD[4], GOLD[3], GOLD[3]])
    paint_lane(img, "orn", [GOLD[6], GOLD[5], GOLD[4]])
    paint_lane(img, "panel", [GOLD[4]])


HEAT_GLINTS = ((0.0, 1, 0), (0.3, 2, 1), (0.55, 1, 2), (0.8, 2, 0))   # phase, climbs per loop, column


def theme_atlas_frame(img, t: float) -> None:
    """Heat glints climbing the shaft: small bright flecks with faint tails, flickering as
    they rise."""
    px = img.load()
    c0, _ = LANES["shaft"]
    lo, hi = SHAFT_Y0 + 0.6, SHAFT_Y1 - 0.4
    for ph, speed, col in HEAT_GLINTS:
        s = (ph + speed * t) % 1.0
        fade = math.sin(math.pi * s) ** 0.6 * (0.8 + 0.2 * math.cos(2 * math.pi * (4 * t + ph)))
        row = row_of(lo + (hi - lo) * s)
        for dr, k in ((-1, 0.55), (0, 0.95), (1, 0.4), (2, 0.18)):
            rr = row + dr
            if 0 <= rr < ATLAS:
                cur = "#%02x%02x%02x" % px[c0 + col, rr][:3]
                px[c0 + col, rr] = rgba(mix(cur, HOT, k * fade))


def paint_atlas_base():
    img = canvas(ATLAS, fill=METAL[3])
    paint_skeleton_lanes(img, METAL)
    paint_theme_lanes(img)
    return img


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


def _band_k(y: float, t: float, s: dict) -> float:
    """How strongly one shine() band (as painted on the atlas) lights model height y."""
    run = 1 - s["pause"]
    if t >= run:
        return 0.0
    w = s["width"]
    centre = -ATLAS - w * 2 + (ATLAS + w * 4) * (t / run)
    d = abs(-(Y_TOP - y) * RPU - centre)
    return max(0.0, 1 - d / (w / 2 + 0.5)) * s["strength"]


def sweep_k(y: float, t: float) -> float:
    """The key's glint and its trailing twin at height y, as one blend amount."""
    a = _band_k(y, t, SWEEP)
    b = _band_k(y, (t - TRAIL_LAG) % 1.0, TRAIL)
    return 1 - (1 - a) * (1 - b)


BEAMS, BEAM_TURNS, BEAM_W, BEAM_START = 3, 2 / 3, 30.0, 105.0


def beam(ang: float, t: float) -> float:
    """0..1: how brightly the three sunbeams wheeling clockwise light direction ang."""
    best = 0.0
    for j in range(BEAMS):
        phi = BEAM_START + j * 360 / BEAMS - 360 * BEAM_TURNS * t
        d = abs((ang - phi + 180) % 360 - 180)
        if d < BEAM_W:
            best = max(best, 0.5 + 0.5 * math.cos(math.pi * d / BEAM_W))
    return best


def flare_ring(r: float, t: float) -> float:
    """A pulse of light running out along the rays after the citrine flares."""
    x = (t - GEM_PEAK) % 1.0
    if x > 0.3:
        return 0.0
    front = 1.2 + 26.0 * x
    return math.exp(-((r - front) / 0.9) ** 2) * (1 - x / 0.3)


TWINKLE_PHASE = {a: random.Random(a * 7 + 3).random() for a in range(0, 360, 15)}


def twinkle(ang: int, t: float) -> float:
    """A quick twinkle at each corona point once a loop, at its own moment."""
    d = ((t - TWINKLE_PHASE[ang] + 0.5) % 1.0) - 0.5
    return 0.5 + 0.5 * math.cos(math.pi * d / 0.07) if abs(d) < 0.07 else 0.0


def rays_frame(t: float):
    img = canvas(RAY_SIZE)
    px = img.load()
    for b in RAYS.values():
        a = math.radians(b["ang"])
        wheel = beam(b["ang"], t) if b["role"] == "bow" else 0.0
        for row in range(RAY_SIZE):
            r = RAY_R0 - (row + 0.5) / RAY_RPU
            y = b["cy"] + r * math.sin(a)
            ramp = GOLD + [HOT]
            if b["role"] == "bow" and r < 4.0:          # the sun-star: sunlit, brightest at the heart
                f = 6.0 - (r - 1.4) * 0.4
            elif b["role"] == "bow":                    # the corona: brighter toward the points
                f = 3.8 + (r - 5.2) * 0.5
            elif b["role"] == "tooth":
                f = 4.3 + (r - 2.2) * 0.35
            else:                                       # the star: gold at the heart, pale points
                f = 3.9 + r * 0.65
            flare = flare_ring(r, t) if b["role"] == "bow" else 0.0
            tip = 0.0
            if b["role"] == "bow" and r > 4.0 and b["ang"] in CORONA_ANGLES:
                reach = corona_size(b["ang"])[0]
                near = clamp((r - (reach - 0.7)) / 0.5)
                tip = near * max(wheel, twinkle(b["ang"], t))
            k = 1 - (1 - sweep_k(y, t)) * (1 - 0.8 * wheel) * (1 - 0.55 * flare) * (1 - 0.9 * tip)
            for i, off in enumerate((0.75, -0.85)):
                px[b["col"] + i, row] = rgba(mix(ramp_at(ramp, f + off), HOT, k))
    return img


def gem_frame(t: float):
    """The citrine's face: an octagon with a table facet, crown facets lit from the left,
    a white highlight, brightening and throwing a star at its flare, and swelling softly
    again half a loop later so the heart of the sun never goes dull."""
    p = gem_pulse(t)
    lift = 0.7 + 1.3 * p + 0.3 * wave(t, -GEM_PEAK)
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


TWINKLES = ((5, 10, 0.1), (26, 6, 0.45), (27, 23, 0.8), (6, 24, 0.3))


def window_frame(t: float):
    """Summer sky in the bow: deep blue at the rim, bright round the sun, a sunburst of
    paler sky slowly turning, the three sunbeams wheeling through it and glints twinkling
    in the heat."""
    p = gem_pulse(t)
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 16, (y + 0.5 - 16) / 16
            r = math.hypot(dx, dy)
            ang = math.degrees(math.atan2(-dy, dx)) % 360
            f = 6.0 - 5.6 * r
            if r > 0.8:
                f -= (r - 0.8) * 10
            wedge = math.cos(math.radians(12 * (ang + 30 * t)))
            f += 1.1 * max(0.0, wedge) * clamp((r - 0.3) * 3)
            c = SKY[int(clamp(dither_band(f, x, y), 0, len(SKY) - 1))]
            b = beam(ang, t) * clamp((r - 0.45) * 3) * clamp(1.25 - r)
            if b > 0.02:
                c = mix(c, SUN[2], 0.35 * b)
            halo = (0.5 + 0.5 * p) * max(0.0, 1 - r / 0.5) ** 1.3
            if halo > 0.03:
                c = mix(c, SUN[1], min(0.9, halo))
            px[x, y] = rgba(c)
    for x, y, ph in TWINKLES:
        d = ((t - ph + 0.5) % 1.0) - 0.5
        amt = 0.5 + 0.5 * math.cos(math.pi * d / 0.16) if abs(d) < 0.16 else 0.0
        sparkle(img, x, y, amt, colour=HOT, reach=2)
    return img


def textures() -> None:
    base = paint_atlas_base()
    save_animation(animate(lambda t: atlas_frame(base, t), FRAMES), "metal", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(window_frame, FRAMES), "window", frametime=FRAMETIME, interpolate=True)
    save_animation(animate(rays_frame, FRAMES), "rays", frametime=FRAMETIME, interpolate=True)


# ======================================================================================
# 5. MODELS
# ======================================================================================

def models() -> dict:
    parts = skeleton() + theme()
    return {"main": model(parts, transforms(parts))}
