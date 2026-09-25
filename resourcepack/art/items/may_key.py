"""May Key: the green-bronze crate key of blossom time (one of the 19 JoshyMC crate keys).

The bow is the set's stepped ring, cast in green bronze (verdigris in the shadows, brass
on the highlights) and set with five pink studs. It frames a window of spring canopy, deep
leaf green where cherry petals drift past on the breeze, and floating in it a cherry
blossom: five cupped, notched petals, deep pink at the heart and near white at the lobes,
round a peridot held by five claws that each end in a gold bead, with gold-tipped stamens
between the petals. A vine curls out from under the ring's foot, climbs its left side past
two leaves and a small blossom, rounds the collar and winds one and a half turns up the
shaft (bound under the lower band), putting out leaves where it crosses the shaft's sides
and a blossom where it crosses the front. The bit is a bronze block with a green enamel
inlay holding a blossom medallion (it shows front and back), hung with three leaves for
teeth; a glowing cherry bud between two leaves crowns the cap.

Animation (one 3.2 s loop): a warm glint sweeps up the whole key from the bow to the tip,
the peridot swells with soft green light as the glint passes through it and slowly dims
while every blossom blushes brighter with it, cherry petals tumble across the window
behind the flower, and once the glint has gone a breeze ripples light up the leaves.

The skeleton (section 1) is copied from january_key.py, the set's pilot: see its docstring
for the shared proportions and the metal-atlas machinery. Petals and leaves are not atlas
lanes: local_boxes() builds them from stepped boxes in their own frame and projects a
painting over them ("bloom" and "leaf"), so every petal and leaf wears the whole painting
whichever way it points.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, mix, model, place, prism, rgba, save_animation, shine,
                     sparkle, turn)

ID = "may_key"
NAME = "May Key"
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
    # theme (42-63): pale and deep blossom pink (stamens, studs, the bud), sepal green, the
    # vine, and the bit's panel (whose spare rows at the bow's height hold the stamens' gold)
    "petal": (42, 3), "blush": (45, 3), "leaf": (48, 3), "vine": (51, 3), "panel": (54, 10),
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#fbffe4", width=8.0, strength=0.86, angle=-90.0, pause=0.4)   # bow -> tip
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


def skeleton(claw_angles=CLAW_ANGLES) -> list[dict]:
    return bow_ring() + bow_window() + gem_socket(claw_angles) + collar() + shaft()


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
# 2. THEME PALETTE: May, green bronze, cherry pink and peridot (#7CFC5A)
# ======================================================================================
# Green bronze: verdigris teal in the shadows, olive bronze in the body, brass highlights.
METAL = BRONZE = ["#10201c", "#1f3a2f", "#36583c", "#627e44", "#9aa150", "#cfc56a", "#f8eeb2"]
PETAL = ["#6a1a45", "#a33266", "#d65c90", "#f193b7", "#ffc2d6", "#ffe4ee", "#fff8fb"]
LEAF = ["#0e3419", "#185b23", "#25822b", "#3fab36", "#6fe04c", "#b4f985", "#e8ffd0"]
VINE = ["#173012", "#2a521c", "#3f7a26", "#5da335", "#86c848"]
GOLD = ["#b4751a", "#e9ae2e", "#ffd955", "#fff4b0"]
GEM = ["#0c3310", "#185a17", "#2b8a1f", "#55b72a", "#86de3c", "#bdf46c", "#e8ffb4", "#ffffff"]
CANOPY = ["#06201a", "#0a2d21", "#0f3c29", "#154d31", "#1d603a", "#277444", "#338a4e"]

# ======================================================================================
# 3. THEME PARTS: cherry blossom, peridot, vine and leaves, blossom bit, bud finial
# ======================================================================================
PETAL_ANGLES = tuple(90 + 72 * k for k in range(5))          # the blossom's five petals
STAMEN_ANGLES = tuple(126 + 72 * k for k in range(5))        # gold stamens between them
MAY_CLAWS = PETAL_ANGLES                                      # five claws, one down each petal's heart
GOLD_Y = 15.0                                                 # a height whose "panel" rows are painted gold
STUD_ANGLES = PETAL_ANGLES                                    # pink studs on the band, in line with the petals
# One petal of the bow's blossom in its own frame (pointing up out of the flower's heart):
# boxes (x0, x1, r0, r1, depth) fanning from a narrow claw to two lobes round the notch,
# deepest at the lobes so the flower cups toward the viewer. The "bloom" painting spans
# x -PETAL_HW..PETAL_HW and r PETAL_R0..PETAL_R1.
PETAL_HW, PETAL_R0, PETAL_R1 = 1.25, 1.2, 3.42
PETAL_BOXES = ((-0.55, 0.55, 1.2, 2.0, 1.5), (-0.95, 0.95, 1.85, 2.45, 1.56), (-1.25, 1.25, 2.3, 2.95, 1.62),
               (-1.2, -0.1, 2.85, 3.15, 1.68), (0.1, 1.2, 2.85, 3.15, 1.68),
               (-1.02, -0.3, 3.1, 3.42, 1.72), (0.3, 1.02, 3.1, 3.42, 1.72))
# A small blossom's petal, in units of its radius: a claw and a wide blade.
SMALL_PETAL = ((-0.34, 0.34, 0.0, 0.62), (-0.52, 0.52, 0.45, 1.0))
# A leaf in its own frame, in units of its length (along) and width (across): an ovate
# outline stepped from the stalk to the tip.
LEAF_PROFILE = ((0.00, 0.20, 0.09), (0.14, 0.36, 0.33), (0.30, 0.68, 0.50), (0.62, 0.86, 0.34), (0.82, 1.00, 0.14))
VINE_R = 5.7                                                  # the vine hugs the ring's outer edge
RING_VINE = (258.0, 116.0)                                    # climbing the left side, foot -> shoulder
RING_LEAVES = ((206, 2.3, 1.25), (160, 2.4, 1.3))             # (vine angle, length, width)
RING_BLOSSOM = 183.0                                          # a small blossom on the vine between them
HELIX_R, HELIX_Y0, HELIX_PITCH, HELIX_TURNS = 1.4, 10.6, 3.6, 1.5   # the vine round the shaft
SHAFT_LEAVES = ((1 / 3, 52, 2.2, 1.2), (2 / 3, 128, 2.3, 1.25), (1.0, 62, 1.9, 1.05))  # (helix f, angle, l, w)
PLATE_Y0, PLATE_Y1 = 21.0, 25.0                              # the bit plate (leaves hang below it)
LEAF_TEETH = ((4.65, 3.0, 1.2, 266, 0.8), (5.7, 2.0, 1.0, 271, 0.6), (6.7, 2.55, 1.1, 276, 0.7))  # x, l, w, ang, d


def offset(c, ang: float, dist: float):
    a = math.radians(ang)
    return (c[0] + dist * math.cos(a), c[1] + dist * math.sin(a))


def local_boxes(c, ang: float, boxes, tex: str, span, z: float = AZ, flip: bool = False, glow: int = 0):
    """Boxes in a local frame at c = (x, y) pointing along ang (degrees, front plane):
    each (x0, x1, r0, r1, d) is built pointing up, then turned. `tex` is projected over
    their fronts and backs, so its painting follows the part: span = (hw, r0, r1) means u
    0..16 covers x -hw..hw and v 0..16 covers r r1..r0. The sides take the painting's edge
    columns and the ends its rows there. flip mirrors the painting across the part."""
    cx, cy = c
    hw, s0, s1 = span
    parts = []
    for x0, x1, r0, r1, d in boxes:
        u0 = clamp((x0 + hw) / (2 * hw)) * 16
        u1 = clamp((x1 + hw) / (2 * hw)) * 16
        v0 = clamp((s1 - r1) / (s1 - s0)) * 16
        v1 = clamp((s1 - r0) / (s1 - s0)) * 16
        if flip:
            u0, u1 = 16 - u0, 16 - u1
        lo, hi = (u1, u0) if flip else (u0, u1)
        faces = {"south": (tex, [u0, v0, u1, v1]), "north": (tex, [u1, v0, u0, v1]),
                 "west": (tex, [lo, v0, min(16.0, lo + 0.5), v1]), "east": (tex, [max(0.0, hi - 0.5), v0, hi, v1]),
                 "up": (tex, [u0, v0, u1, min(16.0, v0 + 0.5)]), "down": (tex, [u0, max(0.0, v1 - 0.5), u1, v1])}
        e = box((cx + x0, cy + r0, z - d / 2), (cx + x1, cy + r1, z + d / 2), tex, faces=faces, glow=glow)
        parts.append(turn(e, ang - 90, "z", (cx, cy, z)))
    return parts


def leaf(p, ang: float, length: float, w: float, d: float = 0.32, z: float = AZ) -> list[dict]:
    """A leaf in the front plane from its stalk at p = (x, y) along ang: the stepped ovate
    outline with the leaf painting over it, its lit half turned toward the light."""
    a = math.radians(ang + 90)                       # where the painting's left (lit) half points
    flip = lit((math.cos(a), math.sin(a))) < 0
    boxes = [(-hf * w, hf * w, f0 * length, f1 * length, d) for f0, f1, hf in LEAF_PROFILE]
    return local_boxes(p, ang, boxes, "leaf", (w / 2, 0.0, length), z=z, flip=flip)


def blossom(c, r: float, z: float, d: float = 0.34, glow: int = 5) -> list[dict]:
    """A small five-petal cherry blossom facing the front (+Z), centred on c = (x, y), with
    a gold heart; the bloom painting (and its blush) follows every petal."""
    parts = []
    for ang in PETAL_ANGLES:
        boxes = [(x0 * r, x1 * r, r0 * r, r1 * r, d) for x0, x1, r0, r1 in SMALL_PETAL]
        parts += local_boxes(c, ang, boxes, "bloom", (0.52 * r, 0.0, r), z=z, glow=glow)
    parts.append(gold_heart(c, r * 0.5, d + 0.14, z, glow=glow + 3))
    return parts


def gold_heart(c, side: float, d: float, z: float, glow: int = 8) -> dict:
    """A small gold square turned into a diamond: a blossom's heart."""
    cx, cy = c
    faces = {s: ("metal", dot_uv("panel", GOLD_Y, k)) for s, k in
             (("south", 0.1), ("north", 0.1), ("east", 0.8), ("west", 0.5), ("up", 0.1), ("down", 0.8))}
    e = box((cx - side / 2, cy - side / 2, z - d / 2), (cx + side / 2, cy + side / 2, z + d / 2), "metal",
            faces=faces, glow=glow)
    return turn(e, 45, "z", (cx, cy, z))


def blossom_emblem() -> list[dict]:
    """The cherry blossom floating in the bow's window: five cupped, notched petals, pale
    at the lobes and deep pink at the heart, and gold stamens fanned out between them."""
    parts = []
    c = (AX, BOW_Y)
    for ang in PETAL_ANGLES:
        parts += local_boxes(c, ang, PETAL_BOXES, "bloom", (PETAL_HW, PETAL_R0, PETAL_R1), glow=6)
    for ang in STAMEN_ANGLES:                         # stamens in the gaps
        parts.append(radial(c, ang, 1.5, 2.1, 0.2, 1.2, "petal", glow=8))
        parts.append(diamond(offset(c, ang, 2.15), 0.44, 1.5, "panel", ang - 45, glow=10))
    for ang in PETAL_ANGLES:                          # and gold beads where the claws meet the petals
        parts.append(diamond(offset(c, ang, 1.95), 0.4, 1.95, "panel", ang - 45, glow=10))
    return parts


def peridot() -> list[dict]:
    """A glowing octagonal peridot: a body plus a painted front and back."""
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


def ring_studs() -> list[dict]:
    parts = []
    for ang in STUD_ANGLES:
        parts.append(diamond(offset((AX, BOW_Y), ang, BAND_R), 0.56, BOW_D + 0.24, "blush", ang - 45, glow=4))
    return parts


def vine_bar(p0, p1, w: float = 0.5, d: float = 0.5) -> dict:
    return bar(p0, p1, w, d, "metal", faces=lane_faces("vine", p1[1], p0[1]))


def helix_point(f: float):
    phi = math.radians(180 - 360 * HELIX_TURNS * f)
    y = HELIX_Y0 + HELIX_PITCH * HELIX_TURNS * f
    return (AX + HELIX_R * math.cos(phi), y, AZ + HELIX_R * math.sin(phi))


def vine() -> list[dict]:
    """The vine: out from under the ring's foot with a curl, up its left side putting out
    leaves, round the collar and one and a half turns up the shaft, ending in a leaf; a
    small blossom opens where it crosses the shaft's front."""
    c = (AX, BOW_Y)
    parts = []
    a0, a1 = RING_VINE
    n = 8
    for k in range(n):
        s0 = a0 + (a1 - a0) * k / n
        s1 = a0 + (a1 - a0) * (k + 1) / n
        mid = math.radians((s0 + s1) / 2)
        flip = lit((math.cos(mid), math.sin(mid))) > 0
        w = 0.64 - 0.1 * k / n
        parts.append(arc_segment(c, VINE_R, min(s0, s1), max(s0, s1), w, 0.7, "vine", "vine", "vine", flip=flip))
    # a tendril curling under the ring's foot
    foot = offset(c, a0, VINE_R)
    curl_c = (foot[0] + 0.5, foot[1] - 0.3)
    for k in range(4):
        b0, b1 = 190 - 72 * k, 190 - 72 * (k + 1)
        p0 = offset(curl_c, b0, 0.62 - 0.1 * k)
        p1 = offset(curl_c, b1, 0.62 - 0.1 * (k + 1))
        parts.append(vine_bar((p0[0], p0[1], AZ), (p1[0], p1[1], AZ), 0.32, 0.4))
    for ang, length, w in RING_LEAVES:
        parts += leaf(offset(c, ang, VINE_R + 0.2), ang - 68, length, w)
    parts += blossom(offset(c, RING_BLOSSOM, VINE_R + 0.05), 0.78, AZ + 0.5, glow=4)
    # round the collar and onto the shaft
    pa = offset(c, a1, VINE_R)
    pa = (pa[0], pa[1], AZ)
    pb = (5.7, 9.75, AZ)
    pc = helix_point(0.0)
    parts.append(vine_bar(pa, pb, 0.54, 0.6))
    parts.append(vine_bar(pb, pc, 0.52, 0.56))
    # the helix, 45 degrees a segment
    n = int(HELIX_TURNS * 8)
    pts = [helix_point(k / n) for k in range(n + 1)]
    for p0, p1 in zip(pts, pts[1:]):
        parts.append(vine_bar(p0, p1))
    for f, ang, length, w in SHAFT_LEAVES:
        p = helix_point(f)
        side = 1 if p[0] > AX else -1
        parts += leaf((p[0] + 0.12 * side, p[1]), ang, length, w)
    p = helix_point((HELIX_TURNS - 0.25) / HELIX_TURNS)
    parts += blossom((p[0], p[1]), 1.0, p[2] + 0.22, glow=4)
    return parts


def blossom_bit() -> list[dict]:
    """The bit: a bronze block with a green enamel inlay framing a blossom medallion that
    shows front and back, hung with three leaves for teeth."""
    parts = bit_plate(BIT_X0, PLATE_Y0, PLATE_Y1, 2.2)
    x0, x1, y0, y1 = 4.5, 6.75, 21.5, 24.5
    parts.append(box((x0, y0, AZ - 1.25), (x1, y1, AZ + 1.25), "metal",
                     faces={"south": ("metal", panel_uv(x0, x1, y1, y0)),
                            "north": ("metal", panel_uv(x0, x1, y1, y0, True)),
                            "east": ("metal", lane_uv("step", y1, y0)), "west": ("metal", lane_uv("step", y1, y0)),
                            "up": ("metal", dot_uv("step", y1)), "down": ("metal", dot_uv("step", y0))}))
    for x, length, w, ang, d in LEAF_TEETH:
        parts += leaf((x, PLATE_Y0 + 0.25), ang, length, w, d=d)
    parts += blossom(((x0 + x1) / 2, (y0 + y1) / 2), 1.05, AZ, d=2.8, glow=5)
    return parts


def bud_finial() -> list[dict]:
    """A cherry bud crowning the shaft: a green stem out of the cap, two sepals cupping a
    deep-pink base that swells into a pale pointed bud, and two leaves splaying out."""
    parts = lane_prism(26.2, 27.0, 0.4, "vine", cap="vine")
    parts += lane_prism(26.85, 27.6, 0.62, "blush", cap="blush", glow=3)
    parts += lane_prism(27.55, 28.35, 0.72, "petal", cap="petal", glow=3)
    parts += lane_prism(28.3, 28.75, 0.5, "petal", cap="petal", glow=3)
    top = 28.75
    parts.append(diamond((AX, top), 0.7, 0.72, "petal", glow=3))
    e = box((AX - 0.36, top - 0.35, AZ - 0.35), (AX + 0.36, top + 0.35, AZ + 0.35), "metal",
            faces=lane_faces("petal", top + 0.25, top - 0.25), glow=3)
    parts.append(turn(e, 45, "x", (AX, top, AZ)))
    for ang in (60, 120):
        parts += spike((AX, 26.95), ang, 0.2, 1.25, 0.4, 0.5, "leaf")
    parts += leaf((AX + 0.3, 26.5), 28, 2.1, 1.1)
    parts += leaf((AX - 0.3, 26.55), 154, 1.9, 1.0)
    return parts


def theme() -> list[dict]:
    return blossom_emblem() + peridot() + ring_studs() + vine() + blossom_bit() + bud_finial()


# ======================================================================================
# 4. TEXTURES
# ======================================================================================
PANEL = [                      # the bit plate's front: 10 x 8 texels, x 4.0..7.2, y 25..21
    "5666666654",
    "65gGGGGgc2",
    "65GgggggG2",
    "65Ggggggc2",
    "65GgggggG2",
    "65Ggggggc2",
    "65gGGGGgc2",
    "4322222221",
]
PATINA_ROWS = ((10.0, 12.2), (13.4, 17.2))     # verdigris on the shaft where the vine binds it


def paint_theme_lanes(img) -> None:
    paint_lane(img, "petal", [PETAL[6], PETAL[5], PETAL[4]])
    paint_lane(img, "blush", [PETAL[4], PETAL[3], PETAL[2]])
    paint_lane(img, "leaf", [LEAF[5], LEAF[4], LEAF[2]])
    paint_lane(img, "vine", [VINE[4], VINE[3], VINE[1]])
    px = img.load()
    # the bit panel: a bronze frame round a green enamel inlay
    pal = {"g": CANOPY[3], "G": CANOPY[5], "c": METAL[4]}
    pal.update({str(i): METAL[i] for i in range(7)})
    c0, w = LANES["panel"]
    r0 = row_of(PLATE_Y1)
    for j, line in enumerate(PANEL):
        for i, ch in enumerate(line):
            px[c0 + i, r0 + j] = rgba(pal[ch])
    # gold for the blossoms' hearts and the stamens' anthers, at their rows of the same lane
    for y_hi, y_lo in ((8.5, -1.5), (17.0, 13.0)):
        for r in range(row_of(y_hi), row_of(y_lo) + 1):
            for i in range(w):
                px[c0 + i, r] = rgba(GOLD[3] if i < 2 else GOLD[2] if i < 7 else GOLD[1])
    # verdigris creeping over the shaft round the vine
    c0, w = LANES["shaft"]
    rng = random.Random(11)
    for y_lo, y_hi in PATINA_ROWS:
        for r in range(row_of(y_hi), row_of(y_lo) + 1):
            for i in range(w):
                if rng.random() < 0.16:
                    px[c0 + i, r] = rgba(METAL[2] if rng.random() < 0.6 else "#4f8a6a")


def theme_atlas_frame(img, t: float) -> None:
    """The peridot's glow warms the flower: the stamens' filaments and gold and the
    ring's pink studs brighten round the bow's centre as the gem swells."""
    p = gem_pulse(t)
    if p < 0.03:
        return
    px = img.load()
    for lane, target, k0 in (("petal", "#ffffff", 0.5), ("blush", PETAL[4], 0.55), ("panel", GOLD[3], 0.5)):
        c0, w = LANES[lane]
        for r in range(row_of(8.9), min(ATLAS, row_of(-2.0) + 1)):
            y = Y_TOP - (r + 0.5) / RPU
            k = k0 * p * max(0.0, 1 - abs(y - BOW_Y) / 5.5)
            if k <= 0.01:
                continue
            for i in range(w):
                cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
                px[c0 + i, r] = rgba(mix(cur, target, k))


def paint_atlas_base():
    img = canvas(ATLAS, fill=METAL[3])
    paint_skeleton_lanes(img, METAL)
    paint_theme_lanes(img)
    return img


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


def band_of(ramp, f: float, x: int, y: int) -> str:
    """A ramp colour for fractional index f, crisp bands with checkered seams."""
    return ramp[int(clamp(dither_band(f, x, y), 0, len(ramp) - 1))]


def bloom_frame(t: float):
    """The cherry petal painting, pointing up (lobes at the top, the flower's heart at the
    bottom): deep pink at the claw brightening through pink to a pale blade and near-white
    lobes, three faint veins from the claw, a deeper rim up the sides and into the notch,
    a white lip on the lobes. It blushes brighter from the heart outward as the peridot
    swells."""
    p = gem_pulse(t)
    img = canvas(32)
    px = img.load()
    for ty in range(32):
        for tx in range(32):
            fx = (tx + 0.5) / 16 - 1                   # -1..1 across the petal
            fr = 1 - (ty + 0.5) / 32                   # 0 at the claw, 1 at the lobes
            f = 1.8 + 3.0 * fr ** 0.85 + 0.25 * (1 - abs(fx))
            if abs(fx) > 0.88:
                f -= 0.5                               # the rim
            if fr > 0.82 and abs(fx) < 0.3:
                f -= 0.7                               # shadow into the notch
            elif fr > 0.92 and abs(fx) < 0.84:
                f += 0.6                               # the lobes' white lip
            for va in (-0.55, 0.55):                   # two faint veins
                if 0.14 < fr < 0.46 and abs(fx - va * fr * 1.3) < 0.07:
                    f -= 0.3
            f += p * (1.1 + 0.7 * (1 - fr))
            px[tx, ty] = rgba(ramp_at(PETAL, round(f * 3) / 3))
    return img


BREEZE = (0.6, 0.95)            # the part of the loop in which the breeze ripples up the leaves


def breeze(t: float, fr: float) -> float:
    """0..1: a soft band of light running up a leaf from stalk (fr 0) to tip (fr 1) as the
    breeze turns it to the sun, once a loop, while the metal's glint rests."""
    a, b = BREEZE
    if not a <= t < b:
        return 0.0
    front = -0.3 + 1.6 * (t - a) / (b - a)
    return max(0.0, 1 - abs(fr - front) / 0.3) * math.sin(math.pi * (t - a) / (b - a))


def leaf_frame(t: float):
    """The leaf painting, pointing up (stalk at the bottom): a pale midrib, the left half
    lit and the right in shade as if folded along it, side veins angling up to the edge,
    darker at the rim and brightening toward the tip, and the breeze's ripple."""
    img = canvas(16)
    px = img.load()
    for ty in range(16):
        for tx in range(16):
            fx = (tx + 0.5) / 8 - 1                    # -1 (lit edge) .. 1 (shaded edge)
            fr = 1 - (ty + 0.5) / 16                   # 0 at the stalk, 1 at the tip
            if abs(fx) < 0.13:
                f = 4.2 + 0.5 * fr                     # midrib
            else:
                f = (3.5 if fx < 0 else 2.3) + 0.6 * fr
                vein = (fr * 1.1 - abs(fx) * 0.5) * 4.0
                if fr > 0.1 and abs(vein - round(vein)) < 0.13:
                    f += 0.6
                if abs(fx) > 0.84:
                    f -= 0.6
            f += 1.3 * breeze(t, fr)
            px[tx, ty] = rgba(ramp_at(LEAF, round(f * 2) / 2))
    return img


def gem_frame(t: float):
    """The peridot's face: an octagon with a table facet, crown facets lit from the left
    and a highlight, swelling with soft green light at its pulse."""
    p = gem_pulse(t)
    lift = 0.3 + 1.15 * p
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
    sparkle(img, 6, 6, clamp(p * 1.15 - 0.3), colour="#f6ffe0", reach=3)
    return img


# Petals drifting through the window (texels). Every petal travels whole windows per loop
# down and to the right (the breeze), so the loop is seamless, swaying as it goes and
# tumbling through TUMBLE. x, y at t = 0, windows travelled per loop, sway amplitude, sway
# cycles per loop, sway phase, tumbles per loop, tumble phase, turned sideways.
PETALS = [dict(x=x, y=y, speed=s, amp=a, freq=f, phase=ph, tumble=tb, tphase=tp, turned=tr)
          for x, y, s, a, f, ph, tb, tp, tr in (
              (4, 20, 1, 1.4, 1, 0.0, 2, 0.0, False), (12, 3, 1, 1.8, 2, 0.35, 2, 0.4, True),
              (19, 25, 2, 1.2, 1, 0.7, 4, 0.2, False), (26, 11, 1, 1.6, 1, 0.15, 2, 0.7, True),
              (9, 14, 2, 1.0, 2, 0.55, 4, 0.55, False), (29, 29, 1, 1.3, 1, 0.85, 2, 0.1, False))]
FAR = ((2, 7, 0.1), (7, 29, 0.6), (15, 17, 0.3), (21, 5, 0.8), (27, 21, 0.45), (31, 13, 0.2), (16, 0, 0.9))
TUMBLE = (["a.a", "aWa", "aWa", ".a."],     # face on, notch up
          ["aWa", ".a."],                   # tipping away
          ["aWa"],                          # edge on
          [".b.", "bab"],                   # tipping over, the paler back showing
          [".b.", "bab", "bab", "b.b"],     # back on
          [".b.", "bab"],
          ["bab"],
          ["aWa", ".a."])


def window_frame(t: float):
    """Spring canopy in the bow: deep leaf green, lit from above, darker at the rim, the
    peridot's halo breathing, and cherry petals drifting down on the breeze."""
    p = gem_pulse(t)
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 16, (y + 0.5 - 16) / 16
            r = math.hypot(dx, dy)
            f = 5.4 - 4.6 * (y / 31)
            if r > 0.78:
                f -= (r - 0.78) * 10
            c = band_of(CANOPY, f, x, y)
            halo = (0.3 + 0.7 * p) * max(0.0, 1 - r / 0.8) ** 1.4
            if halo > 0.04:
                c = mix(c, GEM[5], min(0.75, halo))
            px[x, y] = rgba(c)
    for sx, sy, ph in FAR:                      # far-off petals, small and dim
        fy = int((sy + 32 * t) % 32)
        fx = int(round(sx + 32 * t + 0.8 * math.sin(2 * math.pi * (t + ph)))) % 32
        cur = "#%02x%02x%02x" % px[fx, fy][:3]
        px[fx, fy] = rgba(mix(cur, PETAL[3], 0.6))
    for pt in PETALS:
        fy = (pt["y"] + pt["speed"] * 32 * t) % 32
        fx = (pt["x"] + pt["speed"] * 32 * t + pt["amp"] * math.sin(2 * math.pi * (pt["freq"] * t + pt["phase"]))) % 32
        state = TUMBLE[int((pt["tumble"] * t + pt["tphase"]) * len(TUMBLE)) % len(TUMBLE)]
        for ox in (0, -32):
            for oy in (0, -32):
                draw_petal(img, round(fx) + ox, round(fy) + oy, state, pt["turned"])
    return img


def draw_petal(img, x: int, y: int, stamp, turned: bool) -> None:
    px = img.load()
    rows = ["".join(line[j] for line in stamp) for j in range(len(stamp[0]))] if turned else stamp
    cols = {"a": PETAL[4], "W": PETAL[6], "b": PETAL[3]}
    oy, ox = len(rows) // 2, len(rows[0]) // 2
    for j, line in enumerate(rows):
        for i, ch in enumerate(line):
            xx, yy = x + i - ox, y + j - oy
            if ch == "." or not (0 <= xx < 32 and 0 <= yy < 32):
                continue
            px[xx, yy] = rgba(cols[ch])


def textures() -> None:
    base = paint_atlas_base()
    save_animation(animate(lambda t: atlas_frame(base, t), FRAMES), "metal", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(bloom_frame, GEM_FRAMES), "bloom", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(window_frame, FRAMES), "window", frametime=FRAMETIME)
    save_animation(animate(leaf_frame, GEM_FRAMES), "leaf", frametime=GEM_FRAMETIME, interpolate=True)


# ======================================================================================
# 5. MODELS
# ======================================================================================

def models() -> dict:
    parts = skeleton(MAY_CLAWS) + theme()
    return {"main": model(parts, transforms(parts))}
