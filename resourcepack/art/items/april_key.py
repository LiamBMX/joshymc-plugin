"""April Key: the rain-silver crate key of spring showers (one of the 19 JoshyMC crate keys).

The bow is the set's stepped ring in rain silver, its band laid with pale-blue enamel and
set with six raindrop studs, three drops hanging from its foot. Inside, a recessed window
of rainy sky holds a sculpted, self-lit rain cloud (stepped puffs whose fronts the window
texture paints) with a pear-cut raindrop gem, point up, cradled by four claws beneath it.
A little yellow primrose sprouts from the collar. The octagonal shaft is enamelled between
its bands and girdled halfway up by an enamel ring beaded with hanging drops; a drip runs
off the cap and a glass raindrop in a silver cup crowns it. On the -X side the bit is a
small rain cloud over a rain-grey plate, its teeth three hanging raindrops.

Animation (one 3.2 s loop): the set's glossy glint sweeps up the key from bow to tip while
raindrops bead up and run down the shaft and the ring, the drips at the ring's foot
glistening as the water reaches them. Rain falls slantwise under the cloud in the window,
splashing on the lip, and the raindrop gem flares as the glint passes and sends rings of
light rippling out across its face and the window, the first leaving a faint rainbow.

Skeleton, atlas lanes and timing follow january_key.py (see its docstring).
"""
from __future__ import annotations

import math

from art.kit import (SIDES, animate, bar, box, canvas, display, mix, model, place, prism, rgba, save_animation,
                     shine, sparkle, turn)

ID = "april_key"
NAME = "April Key"
KIND = "sword"
COUNTERPART = "item/trial_key"

# ======================================================================================
# 1. SHARED KEY SKELETON (identical for every key in the set, copied from january_key.py)
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
GEM_FRAMES, GEM_FRAMETIME = 32, 2  # gem (interpolated; April's ripples want every frame)

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
    "cloud": (42, 4), "drop": (46, 4), "leaf": (50, 3), "petal": (53, 3), "panel": (56, 8),
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#f2fcff", width=8.0, strength=0.88, angle=-90.0, pause=0.4)   # bow -> tip
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


def gem_socket(claw_angles, claw_z: float = 9.3) -> list[dict]:
    """The bezel behind the heart gem and six claws over its rim."""
    uv = {s: dot_uv("bezel", BOW_Y, 0.9) for s in ("north", "south", "east", "west")}
    uv["up"] = uv["down"] = dot_uv("bezel", BOW_Y, 0.5)       # the front and back faces
    parts = prism((AX, BOW_Y, AZ), SOCKET_R, 2.2, "metal", axis="z", cap="metal", uv=uv)
    for ang in claw_angles:
        parts.append(radial((AX, BOW_Y), ang, GEM_R - 0.25, SOCKET_R + 0.15, 0.5, 0.6, "band", z=claw_z))
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


def skeleton(claws=CLAW_ANGLES, claw_z: float = 9.3) -> list[dict]:
    return bow_ring() + bow_window() + gem_socket(claws, claw_z) + collar() + shaft()


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
# 2. THEME PALETTE: April, rain silver and pale sky blue (#8FD1FF)
# ======================================================================================
METAL = SILVER = ["#223149", "#3b5372", "#5c7b9d", "#86a4c2", "#b2cadf", "#dae9f4", "#f8fcff"]
ENAMEL = ["#18497f", "#2566a8", "#3f89cc", "#62aaea", "#8fd1ff", "#bde5ff", "#e9f7ff"]
GEM = ["#082657", "#0d3f86", "#145eb6", "#2182db", "#45a8f1", "#8fd1ff", "#d3f0ff", "#ffffff"]
SKY = ["#2a4a70", "#335a84", "#3e6b97", "#4b7ca8", "#5a8db8", "#6c9ec7"]
CLOUD = ["#56698f", "#7a8eb4", "#a0b3d2", "#c6d4e9", "#e5edf8", "#ffffff"]
RAIN = ["#a7d3f5", "#d2ecff", "#f4fbff"]
LEAF = ["#1c4a30", "#296a3c", "#44903d", "#6fb947", "#a6dc66"]
PETAL = ["#b25a1a", "#dc8c24", "#f3bf3a", "#ffda66", "#fff2ad"]

# ======================================================================================
# 3. THEME PARTS: rain cloud, pear-cut raindrop gem, drips, primrose, rain-cloud bit
# ======================================================================================
CLAWS = (192, 244, 296, 348)     # a cradle of claws under the drop; the cloud hides the bezel's top
CLAW_Z = 9.5                     # the drop stands proud of the cloud, so its claws do too

# --- the window grid: the window texture puts 32 texels across the window ---------------
WT = 32
WS = 2 * WINDOW_R / WT                       # model units per window texel


def wx(i: float) -> float:
    return AX - WINDOW_R + i * WS


def wy(j: float) -> float:
    return BOW_Y + WINDOW_R - j * WS


def window_uv(rect, back: bool = False) -> list[float]:
    i0, j0, i1, j1 = rect
    u0, u1, v0, v1 = i0 * 16 / WT, i1 * 16 / WT, j0 * 16 / WT, j1 * 16 / WT
    return [u1, v0, u0, v1] if back else [u0, v0, u1, v1]


# --- the cloud emblem: puffs of stepped blocks, fronts painted by the window texture -----
PUFFS = (   # (centre col, centre row, radius) in window texels, front z, shade steps below the crown
    (16.0, 6.6, 5.4, 9.34, 0),
    (9.1, 9.8, 4.2, 9.27, 1),
    (23.1, 9.3, 4.5, 9.27, 1),
    (4.7, 13.0, 2.7, 9.2, 1),
    (27.5, 13.2, 2.6, 9.2, 1),
)
BELLY = ((4, 11, 28, 15), 9.16)               # the cloud's flat underside: texel rect, front z
STEPS = ((0.42, 1.0), (0.72, 0.87), (0.9, 0.63), (1.0, 0.34))   # a pixel disc: (half height, half width)


def _round(v: float) -> int:
    return int(math.floor(v + 0.5))


def puff_rects(ci: float, cj: float, r: float) -> list[tuple]:
    out = []
    for h, w in STEPS:
        rect = (_round(ci - w * r), _round(cj - h * r), _round(ci + w * r), _round(cj + h * r))
        if rect[2] > rect[0] and rect[3] > rect[1] and rect not in out:
            out.append(rect)
    return out


def cloud_rects() -> list[tuple]:
    """Every block of the cloud as (texel rect, front z, puff index); the belly is last."""
    out = [(rect, fz, n) for n, (ci, cj, r, fz, _) in enumerate(PUFFS) for rect in puff_rects(ci, cj, r)]
    out.append((BELLY[0], BELLY[1], len(PUFFS)))
    return out


def front_of(n: int) -> float:
    return PUFFS[n][3] if n < len(PUFFS) else BELLY[1]


def cloud_owner() -> list[list[int]]:
    """Which puff is frontmost at each window texel (-1 where there is no cloud)."""
    owner = [[-1] * WT for _ in range(WT)]
    for (i0, j0, i1, j1), fz, n in cloud_rects():
        for j in range(max(0, j0), min(WT, j1)):
            for i in range(max(0, i0), min(WT, i1)):
                if owner[j][i] < 0 or fz > front_of(owner[j][i]) + 1e-6:
                    owner[j][i] = n
    return owner


CLOUD_OWNER = cloud_owner()


def cloud_block(rect, front: float) -> dict:
    i0, j0, i1, j1 = rect
    x0, x1, y_top, y_bot = wx(i0), wx(i1), wy(j0), wy(j1)
    d = front - AZ
    faces = {"south": ("window", window_uv(rect)), "north": ("window", window_uv(rect, True)),
             "east": ("metal", lane_uv("cloud", y_top, y_bot, part=(0.25, 0.5))),
             "west": ("metal", lane_uv("cloud", y_top, y_bot, True, part=(0.25, 0.5))),
             "up": ("metal", dot_uv("cloud", y_top, 0.0)), "down": ("metal", dot_uv("cloud", y_bot, 0.8))}
    return box((x0, y_bot, AZ - d), (x1, y_top, AZ + d), "window", faces=faces, glow=6, shade=False)


def cloud_emblem() -> list[dict]:
    return [cloud_block(rect, fz) for rect, fz, _ in cloud_rects()]


# --- the heart gem: a pear-cut raindrop, point up -----------------------------------------
GEM_TOP = 1.85                   # the gem painting spans x -1.6..1.6 and y 1.85..-1.15 round the
GEM_U = 0.2                      # bow's centre: 16 x 15 texels of GEM_U; row 15 holds swatches
POINT_YC, POINT_H = 1.13, 0.72   # the point: a square turned 45 degrees (centre height, half diagonal)
SIDE_UV = [1, 15, 2, 16]         # swatch texels in the gem texture's last row
CAP_UV = [5, 15, 6, 16]


GEM_Z = 1.45                     # the drop's body reaches z 8 +- 1.45, clear of the cloud


def pear_gem() -> list[dict]:
    side = {s: SIDE_UV for s in ("north", "south", "east", "west")}
    side["up"] = side["down"] = CAP_UV
    parts = prism((AX, BOW_Y, AZ), GEM_R, 2 * GEM_Z, "gem", axis="z", cap="gem", uv=side, glow=14)
    s = POINT_H * math.sqrt(2)
    yc = BOW_Y + POINT_YC
    uv = {"south": CAP_UV, "north": CAP_UV, "east": SIDE_UV, "west": SIDE_UV, "up": SIDE_UV, "down": SIDE_UV}
    e = box((AX - s / 2, yc - s / 2, AZ - GEM_Z), (AX + s / 2, yc + s / 2, AZ + GEM_Z), "gem", uv=uv, glow=14)
    parts.append(turn(e, 45, "z", (AX, yc, AZ)))
    y0, y1 = BOW_Y + GEM_TOP - 15 * GEM_U, BOW_Y + GEM_TOP
    zf, zb = AZ + GEM_Z, AZ - GEM_Z
    parts.append(box((AX - 1.6, y0, zf), (AX + 1.6, y1, zf + 0.12), "gem", faces={"south": ("gem", [0, 0, 16, 15])},
                     skip=("north", "east", "west", "up", "down"), glow=14, shade=False))
    parts.append(box((AX - 1.6, y0, zb - 0.12), (AX + 1.6, y1, zb), "gem", faces={"north": ("gem", [16, 0, 0, 15])},
                     skip=("south", "east", "west", "up", "down"), glow=14, shade=False))
    return parts


# --- raindrops: studs in the band, drips off the ring, the shaft and the bit ------------------
STUDS = (0, 40, 140, 180, 220, 320)
DRIPS = ((270, 1.55, 0.62), (247, 0.95, 0.42), (293, 0.95, 0.42))   # (angle, length, bulb radius)


def teardrop(x: float, y_tip: float, length: float, r: float, d: float, glow: int = 6, z: float = AZ) -> list[dict]:
    """A hanging drop, `length` long: its point at y_tip, its round bulb (radius r) below."""
    yb = y_tip - length + r
    parts = [box((x - r, yb - 0.58 * r, z - d / 2), (x + r, yb + 0.58 * r, z + d / 2), "metal",
                 faces=lane_faces("drop", yb + 0.58 * r, yb - 0.58 * r), glow=glow),
             box((x - 0.58 * r, yb - r, z - d / 2 + 0.05), (x + 0.58 * r, yb + r, z + d / 2 - 0.05), "metal",
                 faces=lane_faces("drop", yb + r, yb - r), glow=glow)]
    neck_top = y_tip - 0.45 * r
    if neck_top > yb + 0.3 * r:
        parts.append(box((x - 0.4 * r, yb, z - d / 2 + 0.12), (x + 0.4 * r, neck_top, z + d / 2 - 0.12), "metal",
                         faces=lane_faces("drop", neck_top, yb), glow=glow))
    parts.append(diamond((x, y_tip - 0.42 * r), 0.6 * r, d - 0.25, "drop", z=z, glow=glow))
    return parts


def ring_rain() -> list[dict]:
    parts = []
    for ang in STUDS:
        a = math.radians(ang)
        parts.append(diamond((AX + BAND_R * math.cos(a), BOW_Y + BAND_R * math.sin(a)), 0.5, BOW_D + 0.24,
                             "drop", ang - 45, glow=5))
    for ang, length, r in DRIPS:
        a = math.radians(ang)
        x = AX + (BOW_OUT - 0.2) * math.cos(a)
        y = BOW_Y + (BOW_OUT - 0.2) * math.sin(a)
        parts += teardrop(x, y + 0.1, length + 0.3, r, 1.3 if r > 0.5 else 1.0)
    return parts


# --- the primrose sprouting from the collar ------------------------------------------------
STEM = ((AX + 1.2, 9.35, AZ + 0.55), (AX + 1.9, 10.2, AZ + 0.8), (AX + 2.4, 11.15, AZ + 0.95),
        (AX + 2.65, 12.0, AZ + 1.05))
BLOSSOM = (AX + 2.7, 12.6, AZ + 1.15)


def flower() -> list[dict]:
    """A little primrose sprouting from the collar: a curved stem, two leaves and a
    five-petal blossom turned to the front."""
    parts = []
    for p0, p1 in zip(STEM, STEM[1:]):
        parts.append(bar(p0, p1, 0.38, 0.38, "metal", faces=lane_faces("leaf", p1[1], p0[1])))
    for p0, p1, w in (((AX + 1.5, 9.75, AZ + 0.75), (AX + 2.55, 9.95, AZ + 1.2), 0.66),
                      ((AX + 2.45, 9.94, AZ + 1.18), (AX + 3.15, 10.35, AZ + 1.35), 0.42),
                      ((AX + 1.95, 10.35, AZ + 0.85), (AX + 1.45, 11.3, AZ + 1.2), 0.56),
                      ((AX + 1.47, 11.25, AZ + 1.19), (AX + 1.2, 11.9, AZ + 1.3), 0.34)):
        parts.append(bar(p0, p1, w, 0.18, "metal", faces=lane_faces("leaf", p1[1], p0[1])))
    cx, cy, cz = BLOSSOM
    for k in range(5):
        parts.append(radial((cx, cy), 90 + 72 * k, 0.12, 1.0, 0.74, 0.28, "petal", z=cz, shade=False))
    heart = {s: ("metal", dot_uv("petal", cy, 1.0)) for s in SIDES}
    parts.append(box((cx - 0.3, cy - 0.3, cz - 0.2), (cx + 0.3, cy + 0.3, cz + 0.28), "metal", faces=heart,
                     shade=False))
    return parts


# --- the bit: a little rain cloud with raindrop teeth ----------------------------------------
BIT_PLATE = (4.25, 21.4, 22.95)             # x0, y0, y1 of the cloud's rain-grey silver underside
# (x, y, apothem, depth, face tone): face tones pick the cloud lane's columns (0 = white)
BIT_PUFFS = ((6.1, 23.55, 1.3, 2.5, 0.55), (4.7, 23.05, 0.98, 2.3, 0.55), (3.9, 22.2, 0.64, 2.0, 0.8))
TEETH = ((4.55, 2.3, 0.55), (5.55, 1.45, 0.45), (6.45, 1.95, 0.42))           # (x, length, bulb radius)


def puff(x: float, y: float, r: float, d: float, tone: float) -> list[dict]:
    """A round puff of cloud facing the front: an octagonal disc, self-lit like the emblem's
    cloud, white on its upper facets and grey underneath; tone picks its face (0 = white)."""
    uv = {"north": dot_uv("cloud", y + r, 0.0), "east": dot_uv("cloud", y, 0.3), "west": dot_uv("cloud", y, 0.3),
          "south": dot_uv("cloud", y - r, 0.8), "up": dot_uv("cloud", y, tone), "down": dot_uv("cloud", y, tone)}
    return prism((x, y, AZ), r, d, "metal", axis="z", cap="metal", uv=uv, shade=False, glow=4)


def rain_bit() -> list[dict]:
    x0, y0, y1 = BIT_PLATE
    parts = bit_plate(x0, y0, y1, 2.1)
    for x, y, r, d, tone in BIT_PUFFS:
        parts += puff(x, y, r, d, tone)
        if r > 0.8:        # a sunlit crown on the big puffs, proud of the grey
            parts += puff(x - 0.17 * r, y + 0.17 * r, 0.76 * r, d + 0.16, 0.0)
    for x, length, r in TEETH:
        parts += teardrop(x, y0 + 0.15, length, r, 1.1)
    return parts


# --- rain on the shaft, and a raindrop crowning it --------------------------------------------
ENAMEL_ROWS = (BANDS[0][1], BANDS[1][0])        # the shaft is pale-blue enamel between its bands
SHAFT_DRIPS = ((9.3, CAP[0] + 0.1, 1.3, 0.4),)
BEAD_RING = (14.9, 15.8, 1.72)                   # a pale-blue enamel ring beaded with rain
# (x, z, length, bulb radius) of the drops hanging round the bead ring
RING_BEADS = ((AX - 1.45, AZ, 1.5, 0.5), (AX + 1.45, AZ, 1.1, 0.42), (AX + 0.15, AZ + 1.45, 1.3, 0.45),
              (AX - 0.15, AZ - 1.45, 1.3, 0.45))


def wet_shaft() -> list[dict]:
    """Rain on the shaft: an enamel ring halfway up, hung with drops on all four sides,
    and a drip running off the cap."""
    y0, y1, r = BEAD_RING
    parts = lane_prism(y0 - 0.2, y0 + 0.05, r - 0.15, "band") + lane_prism(y0, y1, r, "face2", cap="face2")
    parts += lane_prism(y1 - 0.05, y1 + 0.2, r - 0.15, "band")
    for x, z, length, br in RING_BEADS:
        parts += teardrop(x, y0 + 0.05, length, br, 0.75, z=z)
    for x, y_top, length, br in SHAFT_DRIPS:
        parts += teardrop(x, y_top, length, br, 0.8)
    return parts


FINIAL = ((26.45, 26.8, 0.95), (26.8, 27.95, 1.3), (27.95, 28.45, 1.0), (28.45, 28.9, 0.66))   # (y0, y1, r)


def drop_finial() -> list[dict]:
    """A silver cup on the cap holding a glass raindrop, point up."""
    parts = lane_prism(26.15, 26.65, 1.15, "band")
    for y0, y1, r in FINIAL:
        parts += lane_prism(y0, y1, r, "drop", cap="drop", glow=8)
    top = 29.6
    parts.append(diamond((AX, top - 0.45), 0.9, 0.9, "drop", glow=8))
    e = box((AX - 0.45, top - 0.9, AZ - 0.45), (AX + 0.45, top, AZ + 0.45), "metal",
            faces=lane_faces("drop", top, top - 0.9), glow=8)
    parts.append(turn(e, 45, "x", (AX, top - 0.45, AZ)))
    return parts


def theme() -> list[dict]:
    return cloud_emblem() + pear_gem() + ring_rain() + flower() + rain_bit() + wet_shaft() + drop_finial()


# ======================================================================================
# 4. TEXTURES
# ======================================================================================
PANEL = [                      # the bit plate's front: 8 texels x 4 rows (x 4.0..7.2, y 23.0..21.0)
    "54444443",
    "43b33b32",
    "3bw23bw2",
    "22222221",
]
PANEL_PAL = {"b": GEM[4], "w": GEM[6]}


def paint_theme_lanes(img) -> None:
    # the ring's band is pale-blue enamel set between the silver rim and lip
    paint_lane(img, "face0", [ENAMEL[3], ENAMEL[2], ENAMEL[1]])
    paint_lane(img, "face1", [ENAMEL[4], ENAMEL[3], ENAMEL[2]])
    paint_lane(img, "face2", [ENAMEL[5], ENAMEL[4], ENAMEL[3]])
    paint_lane(img, "cloud", [CLOUD[5], CLOUD[4], CLOUD[3], CLOUD[2]])
    paint_lane(img, "drop", [GEM[6], GEM[5], GEM[4], GEM[3]])
    paint_lane(img, "leaf", [LEAF[4], LEAF[3], LEAF[1]])
    paint_lane(img, "petal", [PETAL[4], PETAL[3], PETAL[1]])
    px = img.load()
    # the shaft's enamel middle, engraved with little raindrops
    c0, w = LANES["shaft"]
    r_top, r_bot = row_of(ENAMEL_ROWS[1]), row_of(ENAMEL_ROWS[0])
    for r in range(r_top, r_bot + 1):
        for i, c in enumerate((ENAMEL[5], ENAMEL[4], ENAMEL[3], ENAMEL[3])):
            px[c0 + i, r] = rgba(c)
    for i, r in ((1, r_top + 1), (2, r_top + 4), (1, r_top + 7)):
        px[c0 + i, r] = rgba(ENAMEL[6])
        px[c0 + i, r + 1] = rgba(ENAMEL[2])
    # the bit plate: rain-grey silver with little inlaid raindrops
    pal = dict(PANEL_PAL)
    pal.update({str(i): SILVER[i] for i in range(7)})
    c0, _ = LANES["panel"]
    r0 = row_of(BIT_PLATE[2])
    for j, line in enumerate(PANEL):
        for i, ch in enumerate(line):
            px[c0 + i, r0 + j] = rgba(pal[ch])


# Raindrops running down the metal: (lanes, columns, from height, to height, start phase, duration).
# Each one beads up, slides down faster and faster leaving a glistening trail, and runs off.
RUNS = (
    (("shaft",), (1, 2), 25.0, 10.4, 0.00, 0.34),
    (("shaft",), (0, 1), 23.0, 10.4, 0.27, 0.30),
    (("shaft",), (2, 3), 20.5, 10.4, 0.53, 0.27),
    (("shaft",), (1, 2), 24.0, 12.0, 0.76, 0.28),
    (FACE_LANES, (1, 2), 7.6, -1.4, 0.10, 0.30),
    (FACE_LANES, (0, 1), 6.8, -1.4, 0.44, 0.30),
    (FACE_LANES, (1, 2), 5.6, -1.4, 0.72, 0.26),
    (RIM_LANES, (0, 1), 8.2, -1.8, 0.60, 0.34),
    (RIM_LANES, (0, 1), 7.2, -1.8, 0.92, 0.30),
)
BEAD = 0.07                  # how long a drop sits still before it starts to slide (loop fraction)
DROP_ROWS = ((-5, ENAMEL[5], 0.1), (-4, ENAMEL[5], 0.18), (-3, ENAMEL[6], 0.26), (-2, ENAMEL[6], 0.38),
             (-1, GEM[6], 0.85), (0, "#ffffff", 1.0), (1, GEM[4], 0.85), (2, "#10203a", 0.5))
FOOT = (-3.8, -1.2)          # the drips at the ring's foot glisten as the water reaches them


def theme_atlas_frame(img, t: float) -> None:
    px = img.load()
    glisten = 0.0
    for lanes, _, _, _, start, dur in RUNS:
        if lanes is not FACE_LANES and lanes is not RIM_LANES:
            continue
        x = (t - start - dur + 0.02) % 1.0
        if x < 0.12:
            glisten = max(glisten, math.sin(math.pi * x / 0.12))
    if glisten > 0.02:
        c0, w = LANES["drop"]
        for r in range(row_of(FOOT[1]), min(ATLAS, row_of(FOOT[0]) + 1)):
            for i in range(w):
                cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
                px[c0 + i, r] = rgba(mix(cur, "#ffffff", 0.6 * glisten))
    for lanes, cols, ya, yb, start, dur in RUNS:
        tau = (t - start) % 1.0
        if tau >= dur:
            continue
        k = max(0.0, (tau - BEAD) / (dur - BEAD)) ** 1.7
        head = (Y_TOP - ya) * RPU + ((ya - yb) * RPU) * k
        fade = min(1.0, tau / 0.03, (dur - tau) / 0.03)
        trail = min(1.0, (tau - BEAD) / 0.05) if tau > BEAD else 0.0
        hr = int(head)
        for lane in lanes:
            for col in cols:
                x = LANES[lane][0] + col
                for dy, target, amount in DROP_ROWS:
                    r = hr + dy
                    if dy < -1:
                        amount *= trail
                    if 0 <= r < ATLAS and amount > 0:
                        cur = "#%02x%02x%02x" % px[x, r][:3]
                        px[x, r] = rgba(mix(cur, target, amount * fade))


def paint_atlas_base():
    img = canvas(ATLAS, fill=METAL[3])
    paint_skeleton_lanes(img, METAL)
    paint_theme_lanes(img)
    return img


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


# --- the glint, as the atlas paints it, for the other textures -------------------------------

def glint_mix(colour: str, y: float, t: float) -> str:
    """colour lit by the atlas glint (and its trailing twin) at model height y, phase t."""
    row = (Y_TOP - y) * RPU
    for spec, tt in ((SWEEP, t), (TRAIL, (t - TRAIL_LAG) % 1.0)):
        run = 1.0 - spec["pause"]
        if tt >= run:
            continue
        w = spec["width"]
        centre = -ATLAS - w * 2 + (ATLAS + w * 4) * (tt / run)
        k = max(0.0, 1.0 - abs(-row - centre) / (w / 2 + 0.5)) * spec["strength"]
        if k > 0:
            colour = mix(colour, spec["colour"], k)
    return colour


# --- ripples: rings of light spreading from the gem --------------------------------------------
# (birth phase, strength, rainbow): the flare's ring leaves a faint rainbow in the rain
RIPPLE_BIRTHS = ((GEM_PEAK - 0.03, 1.0, True), (GEM_PEAK + 0.3, 0.5, False), (GEM_PEAK + 0.63, 0.5, False))
RIPPLE_SPEED = 8.0                # model units per loop
RIPPLE_REACH = 3.7
# The rainbow's bands by distance outside (+) or inside (-) the ring, in window texels.
BOW_BANDS = ((1.0, 2.0, "#ff8f8f", 0.6), (0.0, 1.0, "#ffe68a", 0.85), (-1.0, 0.0, "#9ff0b4", 0.8),
             (-2.0, -1.0, "#8ccaff", 0.7), (-3.0, -2.0, "#c0a3ff", 0.5))
RING_BANDS = ((-0.55, 0.55, RAIN[2], 0.9), (-1.25, 1.25, RAIN[2], 0.35))


def ripples(t: float) -> list[tuple[float, float, bool]]:
    """(radius in model units, strength, rainbow) of every ring alive at phase t."""
    out = []
    for born, strength, rainbow in RIPPLE_BIRTHS:
        rad = ((t - born) % 1.0) * RIPPLE_SPEED
        if rad < RIPPLE_REACH:
            out.append((rad, strength * (1 - rad / RIPPLE_REACH) ** 0.7, rainbow))
    return out


# --- the gem -------------------------------------------------------------------------------

def gem_xy(i: int, j: int) -> tuple[float, float]:
    return -1.6 + (i + 0.5) * GEM_U, GEM_TOP - (j + 0.5) * GEM_U


def pear_inside(x: float, y: float) -> bool:
    ax, ay = abs(x), abs(y)
    a = GEM_R + 0.04
    if ax <= a and ay <= a and ax + ay <= a * math.sqrt(2):
        return True
    return ax + abs(y - POINT_YC) <= POINT_H + 0.05


PEAR_C = (0.0, 0.15)


def pear_scale(x: float, y: float) -> float:
    """How far (x, y) sits from the pear's heart: 0 there, 1 on its outline, 2 outside."""
    if not pear_inside(x, y):
        return 2.0
    cx, cy = PEAR_C
    lo, hi = 0.0, 1.0
    for _ in range(20):
        mid = (lo + hi) / 2
        if mid > 1e-6 and pear_inside(cx + (x - cx) / mid, cy + (y - cy) / mid):
            hi = mid
        else:
            lo = mid
    return hi


PEAR = [[pear_scale(*gem_xy(i, j)) for i in range(16)] for j in range(15)]


def gem_frame(t: float):
    """The pear's face: a table facet, crown facets lit from the upper left, a white glint and
    a caustic in its belly; ripples of light spread across it and it flares with the glint."""
    p = gem_pulse(t)
    lift = 0.15 + 0.95 * p
    rings = ripples(t)
    img = canvas(16)
    px = img.load()
    lx, ly = -0.6, 0.8
    for j in range(15):
        for i in range(16):
            s = PEAR[j][i]
            if s > 1.0:
                continue
            x, y = gem_xy(i, j)
            if s > 0.86:
                f = 0.8
            elif s <= 0.5:
                f = 3.5 if (x * lx + (y - PEAR_C[1]) * ly) > 0.05 else 3.0
            else:
                ang = math.atan2(y - PEAR_C[1], x - PEAR_C[0])
                sector = round(ang / (math.pi / 4)) * (math.pi / 4)
                f = 2.7 + 1.5 * (math.cos(sector) * lx + math.sin(sector) * ly)
            if s <= 0.86 and y < -0.3:
                f += 0.6 * (-0.3 - y)                     # light gathers in the drop's belly
            d = math.hypot(x, y)
            for rad, k, _ in rings:
                g = abs(d - rad)
                if g < 0.12:
                    f += 2.4 * k
                elif g < 0.3:
                    f += 0.8 * k
            px[i, j] = rgba(ramp_at(GEM, f + lift * (1.0 if s <= 0.86 else 0.5)))
    for x, y in ((5, 6), (6, 6), (5, 7)):
        px[x, y] = rgba(GEM[7])
    px[10, 12] = rgba(ramp_at(GEM, 5 + lift))
    px[1, 15] = rgba(ramp_at(GEM, 2.2 + lift * 0.7))
    px[5, 15] = rgba(ramp_at(GEM, 0.8 + lift * 0.4))
    sparkle(img, 6, 7, clamp(p * 1.3 - 0.25), colour="#ffffff", reach=4)
    return img


# --- the window: rain under the cloud, ripples round the gem, the cloud itself -------------------
STREAKS = (  # rain: (column at row 0, start phase, falls per loop, length)
    (4, 0.00, 2, 3), (7, 0.55, 3, 4), (10, 0.25, 2, 4), (13, 0.80, 3, 3), (16, 0.40, 2, 3), (19, 0.10, 3, 4),
    (22, 0.65, 2, 4), (25, 0.30, 3, 3), (28, 0.90, 2, 3), (31, 0.50, 3, 4), (9, 0.05, 3, 3), (15, 0.70, 2, 4),
    (21, 0.35, 3, 3), (27, 0.15, 2, 4), (12, 0.45, 2, 3), (24, 0.95, 3, 3))
SLANT = -0.3                     # columns per row: the rain drifts left as it falls


def in_window(x: int, y: int) -> bool:
    return math.hypot(x + 0.5 - 16, y + 0.5 - 16) <= 15.3


def landing(col0: int):
    """Where a streak falling from col0 last shows before it meets the lip (below the cloud)."""
    last = None
    for y in range(16, WT):
        x = int(round(col0 + SLANT * y)) % WT
        if not in_window(x, y):
            break
        last = (x, y)
    return last


LANDING = {col0: landing(col0) for col0, *_ in STREAKS}


def paint_cloud_base() -> list[list[str | None]]:
    """The cloud's colours in window texels, cartoon style: each puff in three crisp bands lit
    from the upper left (the back puffs a step darker), a sunlit rim along the top, a contour
    where a puff tucks behind a prouder one, and a grey underside with a dark lower edge."""
    owner = CLOUD_OWNER

    def own(i, j):
        return owner[j][i] if 0 <= i < WT and 0 <= j < WT else -1

    out = [[None] * WT for _ in range(WT)]
    for j in range(WT):
        for i in range(WT):
            n = owner[j][i]
            if n < 0:
                continue
            if n < len(PUFFS):
                ci, cj, r, _, step = PUFFS[n]
                nx, ny = (i + 0.5 - ci) / r, (j + 0.5 - cj) / r
                light = -(nx * 0.55 + ny * 0.84)
                c = (5 if light > 0.45 else 4 if light > -0.4 else 3) - step
            else:
                c = 3
            me = front_of(n)
            if any(own(i + di, j + dj) >= 0 and front_of(own(i + di, j + dj)) > me + 1e-6
                   for di, dj in ((-1, 0), (1, 0), (0, -1))):
                c = min(c, 2)
            if own(i, j - 1) < 0:
                c = max(c, 4 if n < len(PUFFS) and PUFFS[n][4] == 0 else 3)
            if own(i, j + 1) < 0:
                c = 1
            elif own(i, j + 2) < 0:
                c = min(c, 2)
            out[j][i] = CLOUD[max(0, min(len(CLOUD) - 1, c))]
    return out


CLOUD_BASE = paint_cloud_base()


def window_frame(t: float):
    p = gem_pulse(t)
    img = canvas(WT)
    px = img.load()
    owner = CLOUD_OWNER
    for y in range(WT):
        for x in range(WT):
            dx, dy = (x + 0.5 - 16) / 16, (y + 0.5 - 16) / 16
            r = math.hypot(dx, dy)
            f = 0.6 + 4.4 * (y / 31)
            if r > 0.8:
                f -= (r - 0.8) * 9
            c = SKY[int(clamp(dither_band(f, x, y), 0, len(SKY) - 1))]
            halo = (0.3 + 0.7 * p) * max(0.0, 1 - r / 0.6) ** 1.6
            if halo > 0.05:
                c = mix(c, GEM[4], min(0.8, halo))
            px[x, y] = rgba(c)
    for rad, k, rainbow in ripples(t):
        rt = rad / WS
        if rt < 6.5:
            continue
        bands = BOW_BANDS if rainbow else RING_BANDS
        for y in range(WT):
            for x in range(WT):
                if owner[y][x] >= 0:
                    continue
                g = math.hypot(x + 0.5 - 16, y + 0.5 - 16) - rt
                for lo, hi, colour, a in bands:
                    if lo <= g < hi:
                        cur = "#%02x%02x%02x" % px[x, y][:3]
                        px[x, y] = rgba(mix(cur, colour, a * k))
                        break
    for col0, ph, sp, length in STREAKS:
        head = ((ph + sp * t) % 1.0) * (WT + length) - 1
        for k in range(length):
            yy = int(head) - k
            if not 0 <= yy < WT:
                continue
            xx = int(round(col0 + SLANT * yy)) % WT
            if owner[yy][xx] >= 0 or not in_window(xx, yy):
                continue
            cur = "#%02x%02x%02x" % px[xx, yy][:3]
            px[xx, yy] = rgba(mix(cur, RAIN[2 - min(2, k)], (0.95, 0.7, 0.45, 0.25)[min(3, k)]))
        # a splash where the drop hits the lip
        land = LANDING[col0]
        if land is not None and land[1] <= head < land[1] + 3.2:
            lx, ly = land
            for dx, dy, a in ((-1, -1, 0.75), (1, -1, 0.75), (-2, -2, 0.35), (2, -2, 0.35), (0, 0, 0.5)):
                x, y = (lx + dx) % WT, ly + dy
                if 0 <= y < WT and owner[y][x] < 0 and in_window(x, y):
                    cur = "#%02x%02x%02x" % px[x, y][:3]
                    px[x, y] = rgba(mix(cur, RAIN[2], a))
    for y in range(WT):
        for x in range(WT):
            c = CLOUD_BASE[y][x]
            if c is None:
                continue
            my = wy(y + 0.5)
            g = max(0.0, 1 - math.hypot(wx(x + 0.5) - AX, my - BOW_Y) / 3.0)
            if g > 0:
                c = mix(c, GEM[5], 0.55 * p * g)
            px[x, y] = rgba(glint_mix(c, my, t))
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
    parts = skeleton(CLAWS, CLAW_Z) + theme()
    return {"main": model(parts, transforms(parts))}
