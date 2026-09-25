"""Homestead Key: the wrought-iron and green-wood crate key of the farmstead (one of the 19 JoshyMC crate keys).

The bow is the set's stepped ring: a band of green-stained wood set with brass rivets,
bound by a wrought-iron rim and lip, with leaf sprigs growing off its shoulders and foot.
It frames a little farmhouse that fills the window against a twilight sky: cream clapboard
walls, a red shingled gable roof trimmed with whitewashed barge boards, a fieldstone
chimney, green shutters, a plank door with iron straps, and two lamp-lit windows standing
proud of the wall; the leaf-green gem sits in the gable like a round attic window in a
whitewashed frame. An iron collar joins the ring to an octagonal shaft of green-stained
wood with iron bands and a brass lucky horseshoe (heels up) nailed to its front and back.
The bit on its -X side is a little white picket fence (three pointed pickets of different
heights on two riveted iron rails), and an iron weathervane with a brass ball crowns the tip.

Animation (one 3.2 s loop): a warm glint sweeps up the whole key from the bow to the tip,
the gem flares as it passes and slowly dims, lamplight flickers warmly in each window on
its own (washing the siding round it), a breeze of light ripples up through the leaves,
and in the sky stars come out and twinkle while leaves drift down behind the house.

The skeleton and atlas machinery (section 1) are copied from january_key.py, so all 19 keys
share proportions, grip, display transforms and the bow-to-tip glint.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, mix, model, place, prism, rgba, save_animation, shine,
                     sparkle, turn)

ID = "homestead_key"
NAME = "Homestead Key"
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
BIT_X0 = 3.45                                       # the picket fence's outer edge
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
    # theme (42-63): leaves, whitewashed pickets, chimney stone, brass rivets, the fence's iron
    # rails, the horseshoe and the weathervane
    "leaf": (42, 4), "picket": (46, 3), "stone": (49, 2), "brass": (51, 3), "rail": (54, 4),
    "shoe": (58, 3), "vane": (61, 3),
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#fff4dc", width=8.0, strength=0.85, angle=-90.0, pause=0.4)   # bow -> tip
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
# 2. THEME PALETTE: Homestead, wrought iron and green-stained wood (#7CA35C)
# ======================================================================================
IRON = ["#141819", "#222a2b", "#343e3e", "#4c5856", "#6b7a73", "#96a69b", "#cad8c7"]
WOOD = ["#132411", "#1d3718", "#2b4f22", "#3f6a2f", "#58873e", "#7ca35c", "#a4c67c", "#cde4a4"]
LEAF = ["#163a18", "#235626", "#377634", "#579541", "#7ca35c", "#a6cd6c", "#d8f09a", "#f6ffdc"]
CREAM = ["#fffaee", "#f1e4c4", "#dac69e", "#b39e7a", "#85705a", "#5a4838"]
ROOF = ["#2a0d0c", "#4a1712", "#6e2419", "#963421", "#b8482a", "#d4683c", "#eb925c"]
STONE = ["#3a3431", "#5a514a", "#7d7266", "#a39684", "#c8bca6"]
BRASS = ["#3a2408", "#6b4514", "#a3722a", "#d4a64a", "#f2d27e", "#fff2c2"]
AMBER = ["#4a1f06", "#8a3e0c", "#cf7416", "#f4a82e", "#ffd36a", "#fff0b0", "#fffdf2"]
GEM = ["#07200c", "#0e3a16", "#185c20", "#2a822c", "#4ea83c", "#7ccb58", "#b8ec8c", "#effed8"]
SKY = ["#27336e", "#34408a", "#4b4f9e", "#6a5ca8", "#9068a6", "#b87a98", "#dc9486", "#f2b27c"]

# Every skeleton lane: the ring's band is green-stained wood (inner -> outer), the rim, lip,
# bands, collar and caps are wrought iron, and the shaft is green-stained wood.
SKELETON_TONES = {
    "face0": (WOOD[3], WOOD[2], WOOD[2]), "face1": (WOOD[4], WOOD[3], WOOD[3]), "face2": (WOOD[5], WOOD[5], WOOD[4]),
    "rim0": (IRON[2], IRON[1]), "rim1": (IRON[4], IRON[3]), "rim2": (IRON[6], IRON[5]),
    "lip0": (IRON[6], IRON[5]), "lip1": (IRON[5], IRON[5]), "lip2": (IRON[4], IRON[5]),
    "rim_side": (IRON[5], IRON[4], IRON[3]), "lip_side": (IRON[4], IRON[3], IRON[2]), "step": (IRON[3], IRON[2]),
    "shaft": (WOOD[6], WOOD[5], WOOD[4], WOOD[3]), "band": (IRON[6], IRON[5], IRON[4], IRON[3]),
    "bezel": (CREAM[3], CREAM[1], CREAM[2]), "cap": (IRON[5], IRON[5]),
}

# ======================================================================================
# 3. THEME PARTS: the farmhouse, the leaf gem, the picket-fence bit, horseshoe, weathervane
# ======================================================================================
HR = WINDOW_R                     # the house texture covers the window's square: x, y in -HR..HR
EAVE_Y, PEAK_Y = -0.6, 3.2        # the roof (bow coordinates, relative to the bow's centre)
ROOF_COURSES = 6
WALL = (-2.95, 2.95, -3.55, -0.5)  # x0, x1, y0, y1
WALL_Z = (7.3, 8.95)
ROOF_Z = (7.15, 9.0)
# Texel boxes (col0, row0, col1, row1, inclusive) of the lit windows in the 32 px house texture.
WINDOWS = ((7, 22, 11, 26), (20, 22, 24, 26))
DOOR = (14, 24, 17, 31)
CHIMNEY = (1.45, 2.2, 1.0, 2.62)   # x0, x1, y0, y1 on the right slope
RIVET_ANGLES = (0, 45, 135, 180, 225, 315)
SPRIGS = ((38, (-32, 4, 40)), (142, (-40, -4, 32)), (270, (-38, 0, 38)))   # (angle, leaf offsets)
FENCE_X0 = 3.45
PICKETS = ((4.0, 24.0), (5.1, 25.25), (6.2, 24.55))       # (x, top) of each picket tooth
PICKET_W, PICKET_BOTTOM = 0.74, 18.2
RAILS = ((18.85, 19.5), (22.35, 23.0))
SHOE_Y = 15.3
VANE_Y = 28.15


def hx(x: float) -> float:
    """u (0..16) of bow-relative x in the house/window square."""
    return round(clamp((x + HR) / (2 * HR), 0, 1) * 16, 4)


def hy(y: float) -> float:
    return round(clamp((HR - y) / (2 * HR), 0, 1) * 16, 4)


def house_box(x0, x1, y0, y1, z0, z1, glow: int = 0) -> dict:
    """A box of the house whose front and back show the house texture projected onto it."""
    return box((AX + x0, BOW_Y + y0, z0), (AX + x1, BOW_Y + y1, z1), "house",
               faces={"south": ("house", [hx(x0), hy(y1), hx(x1), hy(y0)]),
                      "north": ("house", [hx(x1), hy(y1), hx(x0), hy(y0)]),
                      "east": ("house", [hx(x1) - 0.3, hy(y1), hx(x1), hy(y0)]),
                      "west": ("house", [hx(x0), hy(y1), hx(x0) + 0.3, hy(y0)]),
                      "up": ("house", [hx(x0), hy(y1), hx(x1), hy(y1) + 0.3]),
                      "down": ("house", [hx(x0), hy(y0) - 0.3, hx(x1), hy(y0)])}, glow=glow)


def texel_x(col: float) -> float:
    return col / 32 * 2 * HR - HR


def texel_y(row: float) -> float:
    return HR - row / 32 * 2 * HR


def farmhouse() -> list[dict]:
    """The emblem: a clapboard farmhouse filling the window, a shingled gable roof trimmed
    with iron barge boards, a brick chimney, two lit windows (glowing, proud of the wall)
    and a door; the gem sits in the gable like a round attic window."""
    x0, x1, y0, y1 = WALL
    parts = [house_box(x0, x1, y0, y1, *WALL_Z)]
    h = (PEAK_Y - EAVE_Y) / ROOF_COURSES
    for k in range(ROOF_COURSES):
        yb = EAVE_Y + k * h
        hw = PEAK_Y - yb - h / 2
        parts.append(house_box(-hw, hw, yb, yb + h + 0.02, *ROOF_Z))
    for s in (-1, 1):                                   # iron barge boards along both slopes
        eave = (AX + s * (PEAK_Y - EAVE_Y + 0.35), BOW_Y + EAVE_Y - 0.35, AZ)
        peak = (AX, BOW_Y + PEAK_Y + 0.05, AZ)
        parts.append(bar(eave, peak, 0.62, 2.3, "metal", faces=lane_faces("picket", peak[1], eave[1], flip=s > 0)))
    cx0, cx1, cy0, cy1 = CHIMNEY
    parts.append(box((AX + cx0, BOW_Y + cy0, 7.55), (AX + cx1, BOW_Y + cy1, 8.45), "metal",
                     faces=lane_faces("stone", BOW_Y + cy1, BOW_Y + cy0)))
    parts.append(box((AX + cx0 - 0.12, BOW_Y + cy1 - 0.1, 7.45), (AX + cx1 + 0.12, BOW_Y + cy1 + 0.18, 8.55),
                     "metal", faces=lane_faces("band", BOW_Y + cy1 + 0.18, BOW_Y + cy1 - 0.1)))
    for c0, r0, c1, r1 in WINDOWS:                      # glowing panes and an iron sill under each
        wx0, wx1 = texel_x(c0), texel_x(c1 + 1)
        wy0, wy1 = texel_y(r1 + 1), texel_y(r0)
        parts.append(house_box(wx0, wx1, wy0, wy1, WALL_Z[0] - 0.1, WALL_Z[1] + 0.1, glow=15))
        parts.append(box((AX + wx0 - 0.15, BOW_Y + wy0 - 0.22, WALL_Z[1] - 0.2),
                         (AX + wx1 + 0.15, BOW_Y + wy0, WALL_Z[1] + 0.22), "metal",
                         faces=lane_faces("picket", BOW_Y + wy0, BOW_Y + wy0 - 0.22)))
    return parts


def leaf_gem() -> list[dict]:
    """The leaf-green heart gem: an octagonal body with a painted front and back."""
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


def leaf(c, ang: float, length: float, w: float, d: float = 0.5, z: float = AZ) -> list[dict]:
    """A leaf growing out of c at ang: a slim stalk, a broad blade and a pointed tip."""
    cx, cy = c
    a = math.radians(ang)
    tip_r = length - w * 0.5
    tip = (cx + tip_r * math.cos(a), cy + tip_r * math.sin(a))
    return [radial(c, ang, 0.0, length * 0.3, w * 0.45, d * 0.8, "leaf", z=z),
            radial(c, ang, length * 0.25, tip_r, w, d, "leaf", z=z),
            diamond(tip, w / math.sqrt(2), d - 0.1, "leaf", ang - 45, z=z)]


def ring_trim() -> list[dict]:
    """Brass rivets round the wooden band and leaf sprigs growing off the iron rim."""
    parts = []
    for ang in RIVET_ANGLES:
        a = math.radians(ang)
        parts.append(diamond((AX + BAND_R * math.cos(a), BOW_Y + BAND_R * math.sin(a)), 0.5, BOW_D + 0.24,
                             "brass", ang - 45))
    for ang, offsets in SPRIGS:
        a = math.radians(ang)
        c = (AX + (BOW_OUT - 0.25) * math.cos(a), BOW_Y + (BOW_OUT - 0.25) * math.sin(a))
        for off in offsets:
            parts += leaf(c, ang + off, 1.95 if off == offsets[1] else 1.6, 0.74, 0.55)
    return parts


def picket_bit() -> list[dict]:
    """The bit: a little white picket fence, three pointed pickets of different heights
    bound to the shaft by two riveted iron rails."""
    parts = []
    for x, top in PICKETS:
        body_top = top - PICKET_W / 2
        parts.append(box((x - PICKET_W / 2, PICKET_BOTTOM, AZ - 0.62), (x + PICKET_W / 2, body_top, AZ + 0.62),
                         "metal", faces=lane_faces("picket", body_top, PICKET_BOTTOM)))
        parts.append(diamond((x, body_top), PICKET_W / math.sqrt(2), 1.14, "picket", 45))
    for y0, y1 in RAILS:
        parts.append(box((FENCE_X0, y0, AZ - 0.82), (BIT_X1, y1, AZ + 0.82), "metal",
                         faces=lane_faces("rail", y1, y0)))
        for x, _ in PICKETS:
            ym = (y0 + y1) / 2
            parts.append(box((x - 0.16, ym - 0.16, AZ + 0.8), (x + 0.16, ym + 0.16, AZ + 0.95), "metal",
                             faces=lane_faces("brass", ym + 0.16, ym - 0.16)))
    return parts


def horseshoe() -> list[dict]:
    """A lucky brass horseshoe, heels up, nailed to the front and back of the shaft."""
    parts = []
    c = (AX, SHOE_Y)
    for z in (AZ + SHAFT_R + 0.17, AZ - SHAFT_R - 0.17):
        for k in range(7):
            a0 = 150 + k * (240 / 7)
            parts.append(arc_segment(c, 0.8, a0, a0 + 240 / 7, 0.42, 0.34, "shoe", "shoe", "shoe", z=z))
        for ang in (150, 30):
            a = math.radians(ang)
            parts.append(box((AX + 0.8 * math.cos(a) - 0.24, SHOE_Y + 0.8 * math.sin(a) - 0.05, z - 0.2),
                             (AX + 0.8 * math.cos(a) + 0.24, SHOE_Y + 0.8 * math.sin(a) + 0.22, z + 0.2), "metal",
                             faces=lane_faces("shoe", SHOE_Y + 0.8, SHOE_Y + 0.5)))
    return parts


def weathervane() -> list[dict]:
    """The finial: an iron weathervane, a brass ball on a spire carrying an arrow with a
    pointed head and a fletched tail."""
    parts = lane_prism(26.2, VANE_Y + 0.9, 0.3, "vane")
    parts += lane_prism(26.55, 27.35, 0.52, "brass", cap="brass")
    parts.append(box((5.9, VANE_Y - 0.16, AZ - 0.16), (10.0, VANE_Y + 0.16, AZ + 0.16), "metal",
                     faces=lane_faces("vane", VANE_Y + 0.16, VANE_Y - 0.16)))
    parts.append(diamond((10.05, VANE_Y), 0.72, 0.36, "vane", 45))
    for s in (1, -1):
        for x in (6.0, 6.55):
            p0 = (x + 0.25, VANE_Y + s * 0.08, AZ)
            p1 = (x - 0.35, VANE_Y + s * 0.62, AZ)
            parts.append(bar(p0, p1, 0.34, 0.24, "metal",
                             faces=lane_faces("vane", max(p0[1], p1[1]), min(p0[1], p1[1]))))
    parts.append(diamond((AX, VANE_Y + 0.95), 0.4, 0.4, "brass", 45))
    return parts


def theme() -> list[dict]:
    return (farmhouse() + leaf_gem() + ring_trim() + picket_bit() + horseshoe() + weathervane())


# ======================================================================================
# 4. TEXTURES
# ======================================================================================

def _hash(*v) -> float:
    return (math.sin(sum(x * m for x, m in zip(v, (12.9898, 78.233, 37.719, 4.581)))) * 43758.5453) % 1.0


def blend(px, x: int, y: int, colour, k: float, size: int = ATLAS) -> None:
    if k > 0 and 0 <= x < size and 0 <= y < size:
        cur = "#%02x%02x%02x" % px[x, y][:3]
        px[x, y] = rgba(mix(cur, colour, min(1.0, k)))


def paint_theme_lanes(img) -> None:
    paint_lane(img, "leaf", [LEAF[6], LEAF[5], LEAF[4], LEAF[3]])
    paint_lane(img, "picket", [CREAM[0], CREAM[1], CREAM[2]])
    paint_lane(img, "stone", [STONE[3], STONE[2]])
    paint_lane(img, "brass", [BRASS[5], BRASS[4], BRASS[3]])
    paint_lane(img, "rail", [IRON[5], IRON[4], IRON[3], IRON[2]])
    paint_lane(img, "shoe", [BRASS[5], BRASS[4], BRASS[3]])
    paint_lane(img, "vane", [IRON[5], IRON[4], IRON[2]])
    px = img.load()
    rng = random.Random(11)
    # wood grain: the shaft's facets get dark streaks and a knot or two, the band a few knots
    c0, w = LANES["shaft"]
    for r in range(ATLAS):
        for i in range(w):
            if rng.random() < 0.13:
                blend(px, c0 + i, r, WOOD[1], 0.45)
    for r in (row_of(21.3), row_of(11.8)):
        blend(px, c0 + 1, r, WOOD[1], 0.8)
        blend(px, c0 + 2, r, WOOD[2], 0.6)
    for lane in ("face0", "face1", "face2"):
        c, w = LANES[lane]
        for r in range(ATLAS):
            if rng.random() < 0.18:
                blend(px, c + rng.randrange(w), r, WOOD[1], 0.35)
    # weathered pickets: a little grey grain
    c0, w = LANES["picket"]
    for r in range(ATLAS):
        if rng.random() < 0.2:
            blend(px, c0 + rng.randrange(w), r, CREAM[3], 0.35)
    # fieldstone: dark joints between the stones
    c0, w = LANES["stone"]
    for r in range(ATLAS):
        for i in range(w):
            if (r % 3 == 0) or (r // 3 + i) % 2 == 0 and r % 3 == 1 and i == 0:
                blend(px, c0 + i, r, STONE[0], 0.55)


def twinkle(t: float, phase: float, width: float = 1 / 7) -> float:
    d = (t - phase + 0.5) % 1.0 - 0.5
    return math.cos(math.pi * d / (2 * width)) ** 2 if abs(d) < width else 0.0


def theme_atlas_frame(img, t: float) -> None:
    """Leaves shimmer: once the glint has passed, a breeze of light ripples up through the
    leaves, and the gem's flare warms the leaves and rivets round the bow."""
    px = img.load()
    c0, w = LANES["leaf"]
    p = gem_pulse(t)
    for r in range(ATLAS):
        y = Y_TOP - (r + 0.5) / RPU
        k = 0.75 * twinkle(t, 0.58 + (y - BOW_Y) / 45 + 0.05 * _hash(r // 2, 3))
        k += 0.35 * p * max(0.0, 1 - abs(y - BOW_Y) / 6.5)
        for i in range(w):
            blend(px, c0 + i, r, LEAF[7], k * (1.0 if i < 2 else 0.7))


def paint_atlas_base():
    img = canvas(ATLAS, fill=IRON[3])
    for lane, tones in SKELETON_TONES.items():
        paint_lane(img, lane, tones)
    paint_theme_lanes(img)
    return img


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


def gem_frame(t: float):
    """The leaf gem's face: an octagon with a table facet split by a leaf vein, crown
    facets lit from the upper left and a white highlight, blazing at its flare."""
    p = gem_pulse(t)
    lift = 0.25 + 1.6 * p
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
            elif o <= 3.8:
                f = 4.0 if dx * lx + dy * ly > 0.3 else 3.3
                if abs(dx + dy) < 0.8:                         # the leaf's midrib across the table
                    f += 0.9
            else:
                ang = math.atan2(dy, dx)
                sector = round(ang / (math.pi / 4)) * (math.pi / 4)
                f = 2.8 + 1.7 * (math.cos(sector) * lx + math.sin(sector) * ly)
            px[tx, ty] = rgba(ramp_at(GEM, f + lift * (1.0 if o <= 7.0 else 0.6)))
    for x, y in ((5, 5), (6, 5), (5, 6)):
        px[x, y] = rgba(GEM[7])
    px[10, 10] = rgba(ramp_at(GEM, 5 + lift))
    sparkle(img, 6, 6, clamp(p * 1.3 - 0.25), colour="#ffffff", reach=4)
    return img


def flicker(t: float, phase: float) -> float:
    """0..1: warm lamplight, a gentle uneven flicker (three slow waves, never a strobe)."""
    v = (0.62 + 0.16 * math.sin(2 * math.pi * (2 * t + phase)) + 0.12 * math.sin(2 * math.pi * (5 * t + 1.7 * phase))
         + 0.07 * math.sin(2 * math.pi * (7 * t + 2.3 * phase)))
    return clamp(v)


def paint_house_base():
    """The farmhouse front in the window's square: shingles above, clapboard below, the
    window frames, shutters and the door."""
    img = canvas(32)
    px = img.load()
    rng = random.Random(7)
    roof_row = int((HR - EAVE_Y) / (2 * HR) * 32)          # first wall row under the eaves
    for y in range(32):
        for x in range(32):
            if y < roof_row:                                 # shingles: courses of 3 rows, staggered joints
                course, sub = divmod(y + 1, 3)
                f = 4.3 - 2.2 * x / 31 - (1.4 if sub == 2 else 0) + (0.5 if sub == 0 else 0)
                if (x + course * 2) % 4 == 0 and sub != 2:
                    f -= 0.8
                if rng.random() < 0.08:
                    f -= 0.6
                c = ramp_at(ROOF, f)
            else:                                            # clapboard siding lit from the left
                sub = (y - roof_row) % 3
                f = 1.3 + 1.2 * x / 31 + (1.1 if sub == 2 else 0) - (0.6 if sub == 0 else 0)
                if y < roof_row + 2:
                    f += 1.3                                 # shadow under the eaves
                c = ramp_at(CREAM, f)
            px[x, y] = rgba(c)
    for c0, r0, c1, r1 in WINDOWS:
        for x in range(c0 - 1, c1 + 2):                      # trim round the window, sill below
            for y in (r0 - 1, r1 + 1):
                px[x, y] = rgba(CREAM[0] if y == r1 + 1 else CREAM[1])
        for y in range(r0, r1 + 1):
            px[c0 - 1, y] = rgba(CREAM[1])
            px[c1 + 1, y] = rgba(CREAM[2])
        side = c0 - 3 if c0 < 16 else c1 + 2                 # a green shutter on the outer side
        for y in range(r0, r1 + 1):
            for x in (side, side + 1):
                px[x, y] = rgba(WOOD[4] if (x == side) == (c0 < 16) else WOOD[3])
            if y in (r0 + 1, r1 - 1):
                px[side, y] = rgba(WOOD[2])
                px[side + 1, y] = rgba(WOOD[2])
    d0, r0, d1, r1 = DOOR
    for x in range(d0 - 1, d1 + 2):
        px[x, r0 - 1] = rgba(CREAM[0])
    for y in range(r0, 32):
        px[d0 - 1, y] = rgba(CREAM[1])
        px[d1 + 1, y] = rgba(CREAM[3])
        for x in range(d0, d1 + 1):
            f = 4.2 - (x - d0) * 0.6 - (1.2 if (x - d0) == 2 else 0)
            px[x, y] = rgba(ramp_at(WOOD, f))
    for y in (r0 + 1, r0 + 5):                               # iron strap hinges
        for x in range(d0, d1 + 1):
            px[x, y] = rgba(IRON[3] if x < d1 else IRON[2])
    px[d1 - 1, r0 + 3] = rgba(BRASS[4])                      # the knob
    return img


def house_frame(base, t: float):
    """The lamplight in each window flickers on its own, glowing through the panes and
    washing the siding round it with warm light."""
    img = base.copy()
    px = img.load()
    for n, (c0, r0, c1, r1) in enumerate(WINDOWS):
        lv = flicker(t, 0.37 * n + 0.1)
        cx, cy = (c0 + c1) / 2, (r0 + r1) / 2
        for y in range(r0 - 4, r1 + 5):                     # warm spill on the siding
            for x in range(c0 - 4, c1 + 5):
                if c0 - 1 <= x <= c1 + 1 and r0 - 1 <= y <= r1 + 1:
                    continue
                d = math.hypot(x - cx, (y - cy) * 1.2)
                k = (0.1 + 0.5 * lv) * max(0.0, 1 - (d - 2.2) / 3.4)
                if k > 0.02 and not (DOOR[0] - 1 <= x <= DOOR[2] + 1 and y >= DOOR[1] - 1):
                    blend(px, x, y, AMBER[4], k, 32)
        for y in range(r0, r1 + 1):
            for x in range(c0, c1 + 1):
                mull = x == (c0 + c1) // 2 or y == (r0 + r1) // 2
                if mull:
                    px[x, y] = rgba(mix(IRON[2], AMBER[1], 0.3 * lv))
                    continue
                d = math.hypot(x - cx + 0.6, y - cy - 0.4)
                f = 2.1 + 3.6 * lv - 0.35 * d
                px[x, y] = rgba(ramp_at(AMBER, f))
        blend(px, c0, r0, AMBER[6], 0.3 + 0.5 * lv, 32)      # glint on the glass
    return img


# Drifting leaves in the dusk sky: start x, start y, fall (window heights per loop), sway,
# phase, drift (texels to the left per loop, a multiple of 32 so the loop is seamless).
DRIFT = ((4, 2, 1, 1.6, 0.0, 32), (26, 12, 1, 1.4, 0.35, 32), (12, 20, 1, 1.8, 0.7, 32),
         (20, 27, 1, 1.2, 0.15, 0), (29, 5, 2, 1.0, 0.55, 32))
STARS = ((5, 6), (9, 3), (14, 1), (22, 2), (27, 7), (3, 11), (29, 12), (18, 4))


def window_frame(t: float):
    """Dusk over the farm in the bow: indigo overhead warming to peach at the horizon,
    stars coming out and twinkling, and leaves drifting down on the breeze."""
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 16, (y + 0.5 - 16) / 16
            r = math.hypot(dx, dy)
            f = 1.0 + 6.4 * (y / 31) ** 1.2
            if r > 0.86:
                f -= (r - 0.86) * 5
            c = SKY[int(clamp(dither_band(f, x, y), 0, len(SKY) - 1))]
            px[x, y] = rgba(c)
    for i, (sx, sy) in enumerate(STARS):
        k = 0.5 + 0.5 * math.sin(2 * math.pi * (t * (1 + i % 3) + _hash(i, 3)))
        blend(px, sx, sy, "#fff4d8", 0.25 + 0.65 * k, 32)
    for i, (x0, y0, fall, amp, ph, drift) in enumerate(DRIFT):
        fy = (y0 + fall * 32 * t) % 32
        fx = (x0 - drift * t + amp * math.sin(2 * math.pi * (2 * t + ph))) % 32
        flip = math.sin(2 * math.pi * (2 * t + ph)) > 0
        for oy in (0, -32):
            for ox in (0, -32, 32):
                draw_leaf(px, round(fx) + ox, round(fy) + oy, flip, 0.5 + 0.5 * math.cos(2 * math.pi * (2 * t + ph)))
    return img


def draw_leaf(px, x: int, y: int, flip: bool, lit: float) -> None:
    """A 3-texel falling leaf, tumbling (its lit side flips as it sways)."""
    cells = ((0, 0), (1, 0), (1, 1)) if flip else ((1, 0), (0, 0), (0, 1))
    tones = (LEAF[5 + round(lit)], LEAF[4], LEAF[3])
    for (ox, oy), tone in zip(cells, tones):
        if 0 <= x + ox < 32 and 0 <= y + oy < 32:
            px[x + ox, y + oy] = rgba(tone)


def dither_band(f: float, x: int, y: int) -> int:
    frac = f - math.floor(f)
    if 0.38 < frac < 0.62:
        return math.floor(f) + (x + y) % 2
    return round(f)


def textures() -> None:
    base = paint_atlas_base()
    save_animation(animate(lambda t: atlas_frame(base, t), FRAMES), "metal", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(window_frame, FRAMES), "window", frametime=FRAMETIME)
    house = paint_house_base()
    save_animation(animate(lambda t: house_frame(house, t), FRAMES), "house", frametime=FRAMETIME,
                   interpolate=True)


# ======================================================================================
# 5. MODELS
# ======================================================================================

def models() -> dict:
    parts = skeleton() + theme()
    return {"main": model(parts, transforms(parts))}
