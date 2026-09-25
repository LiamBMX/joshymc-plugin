"""March Key: the lucky gold-and-emerald crate key (one of the 19 JoshyMC crate keys).

The bow is the set's stepped ring in polished gold, set with four emerald cabochons in
gold settings between the leaves, framing a window of deep green glade. In it floats a
four-leaf clover: four solid hearts of glossy green enamel (each folded along a pale
midrib with a glassy spot on its lit lobe) on a curling stem, and at its heart a lucky
gold coin (milled edge, raised border, an inlaid green enamel clover round a gold pip)
standing proud of the six claws of the set's socket, which peek out round it as prongs.
A milled gold collar joins the ring to an octagonal gold shaft enamelled green between
its bands and girdled there by a jewelled ring of four emeralds; on its -X side the bit is
a gold plate inlaid with green enamel and two gold clover studs, carrying on its outer
edge a three-leaf shamrock (gold-rimmed enamel hearts round an emerald boss) as its
teeth. A milled gold cup with four claws holds a pointed emerald at the tip.

Animation (one 3.2 s loop): a warm glint sweeps up the whole key from the bow to the tip;
the coin flares white-gold as it passes (its enamel clover glowing) and slowly dims while
its halo breathes in the glade. A shimmer then runs round the clover leaf by leaf, each
heart brightening as a sheen crosses it and throwing a twinkle off its lobe, and the
shamrock's leaves glint as the sweep reaches them. Lucky gold glints twinkle one after
another in the glade as gold dust rises through it, and star glints pop on the ring.

The skeleton and atlas machinery (section 1) are copied from january_key.py, the set's
pilot (see its docstring for the shared proportions); only BIT_X0 moves (a narrower plate,
the shamrock reaching past it). The clover's leaves are not atlas lanes: each carries a
plate painted with the whole leaf ("clover", one 16 px cell per leaf, lit for the way it
points), so every heart shows its enamel whichever way it faces.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, mix, model, place, prism, rgba, save_animation, shine,
                     sparkle, turn, wave)

ID = "march_key"
NAME = "March Key"
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
BIT_X0 = 5.0                                        # March: a narrow plate, the shamrock reaches past it
BIT_X1 = AX - SHAFT_R + 0.35                        # bit parts run into the shaft up to here
CLAW_ANGLES = tuple(90 + 60 * k for k in range(6))  # the six claws round the heart gem
GRIP = (8.0, 10.6, 8.0)
SIZE = 0.64
GUI_ROTATION = (-25, 20, -45)

# --- timing: every animation loops in 64 ticks ------------------------------------------
FRAMES, FRAMETIME = 32, 2          # atlas, window and clover
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
    "rim": (42, 3), "stem": (45, 3), "emerald": (48, 4), "leaf": (52, 2), "panel": (54, 10),
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#fff3bc", width=8.0, strength=0.88, angle=-90.0, pause=0.4)   # bow -> tip
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
# 2. THEME PALETTE: March, polished gold and clover emerald (#4CD964)
# ======================================================================================
METAL = GOLD = ["#3a1f0c", "#6e3f12", "#a86a1c", "#d49a2e", "#f0c64e", "#fbe68e", "#fff5c8"]
LEAF = ["#07301f", "#0e5431", "#17813f", "#2fb150", "#4cd964", "#9cf07e", "#e4ffc6"]
EMERALD = ["#03291b", "#085234", "#0f8a48", "#22c062", "#6cf29a", "#d2ffe0"]
COIN = ["#5c2e06", "#94540c", "#cc8a16", "#f0b92a", "#ffd84a", "#ffec8a", "#fff8d0", "#ffffff"]
GLADE = ["#081e12", "#0d2c1b", "#133c25", "#1a4d30", "#22603b", "#2d7447"]
GLINT = "#ffe27a"
SHEEN = "#f2ffcc"

# ======================================================================================
# 3. THEME PARTS: four-leaf clover, lucky coin, shamrock bit, emerald finial
# ======================================================================================
# A clover leaf in its own frame (s = 1): v runs from the heart's point outward, u across.
LEAF_DIAMOND = (0.9, 0.9)                 # (centre v, half-diagonal) of the heart's body
LEAF_LOBES = (0.668, 1.459, 0.78)         # (u offset, v, radius) of its two round lobes
LEAF_TOP = LEAF_LOBES[1] + LEAF_LOBES[2]
PLATE = 3.0                               # the painted front: a square round the whole leaf
PLATE_V = 1.12                            # its centre along v
ENAMEL_INSET = 0.2                        # the enamel sits inside a gold rim this wide
CELL = 16                                 # px per leaf painting in the 64 px clover texture
LEAF_ANGLES = (0, 90, 180, 270)           # the bow's leaves (cells 0-3): an X in the inventory
LEAF_R0 = 1.2                             # their points hide under the coin's bezel
LEAF_S = 1.0                              # the whole heart shows inside the ring's lip
LEAF_D = 1.6                              # z 7.2..8.8, below the ring's band and lip
STEM = ((1.5, 315), (2.5, 322), (3.75, 310))       # the clover's stem, (radius, angle) points
PLATE_Y0, PLATE_Y1 = 18.3, 25.1          # the bit plate
BOSS = (5.25, 21.7)                       # the shamrock's centre, on the plate's outer edge
BIT_LEAVES = ((138, 4), (180, 5), (222, 6))        # (angle, cell): up-out, out, down-out
BIT_S, BIT_R0, BIT_D = 0.95, 0.3, 2.1
BIT_RIM = True                            # the bit's leaves are set in gold
RING_GEMS = (45, 135, 225, 315)           # emerald cabochons in the band, between the leaves
LUCKY_RING = (14.95, 15.75)               # a jewelled ring round the enamel
ENAMEL_Y = (13.6, 17.0)                   # green enamel round the shaft between its bands
# cell -> (angle it is lit for, loop phase its shimmer starts, shimmer length, gold rim)
CELLS = {}


def _schedule() -> None:
    start = GEM_PEAK + 0.04
    for k, ang in enumerate((90, 180, 270, 0)):          # the shimmer runs round the clover
        CELLS[LEAF_ANGLES.index(ang)] = (ang, (start + 0.25 * k) % 1.0, 0.3, False)
    for ang, cell in BIT_LEAVES:                          # the bit's leaves glint with the sweep
        y = BOSS[1] + 1.5 * math.sin(math.radians(ang))
        CELLS[cell] = (ang, (sweep_time(y) - 0.06) % 1.0, 0.26, BIT_RIM)


_schedule()


def cell_uv(cell: int) -> list[float]:
    u, v = 4 * (cell % 4), 4 * (cell // 4)
    return [u, v, u + 4, v + 4]


def in_leaf(u: float, v: float, inset: float = 0.0) -> bool:
    dv, dh = LEAF_DIAMOND
    if abs(u) + abs(v - dv) <= dh - inset * 1.414:
        return True
    lu, lv, lr = LEAF_LOBES
    return any((u - sgn * lu) ** 2 + (v - lv) ** 2 <= (lr - inset) ** 2 for sgn in (-1, 1))


def leaf(c, ang: float, s: float, r0: float, d: float, cell: int, z: float = AZ, glow: int = 9,
         rim: bool = False) -> list[dict]:
    """A clover leaf pointing along ang (degrees) with its heart's point at radius r0 from
    c, s times the size of a bow leaf: a solid heart (a diamond and two round lobes) whose
    front and back carry a painted plate of green enamel (set in a gold rim if rim)."""
    lane = "rim" if rim else "leaf"
    cx, cy = c
    a = math.radians(ang)
    vx, vy = math.cos(a), math.sin(a)
    ux, uy = math.sin(a), -math.cos(a)

    def at(u, v):
        return (cx + (r0 + v * s) * vx + u * s * ux, cy + (r0 + v * s) * vy + u * s * uy)

    dv, dh = LEAF_DIAMOND
    x, y = at(0, dv)
    body = diamond((x, y), dh * math.sqrt(2) * s, d, lane, ang - 45, z=z)
    body["faces"]["south"]["uv"] = body["faces"]["north"]["uv"] = dot_uv(lane, y, 0.9)
    parts = [body]
    lu, lv, lr = LEAF_LOBES
    for sgn in (-1, 1):
        x, y = at(sgn * lu, lv)
        uv = {side: dot_uv(lane, y, 0.4) for side in ("north", "south", "east", "west")}
        uv["up"] = uv["down"] = dot_uv(lane, y, 0.9)
        parts += prism((x, y, z), lr * s * 0.97, d, "metal", axis="z", cap="metal", uv=uv)
    px, py = at(0, PLATE_V)
    h = PLATE * s / 2
    u0, v0, u1, v1 = cell_uv(cell)
    plate = box((px - h, py - h, z - d / 2 - 0.03), (px + h, py + h, z + d / 2 + 0.03), "clover",
                faces={"south": ("clover", [u0, v0, u1, v1]), "north": ("clover", [u1, v0, u0, v1])},
                skip=("east", "west", "up", "down"), glow=glow)
    parts.append(turn(plate, ang - 90, "z", (px, py, z)))
    return parts


def clover() -> list[dict]:
    """The four-leaf clover in the bow: four hearts round the coin and a curving stem."""
    parts = []
    for cell, ang in enumerate(LEAF_ANGLES):
        parts += leaf((AX, BOW_Y), ang, LEAF_S, LEAF_R0, LEAF_D, cell)
    pts = [(AX + r * math.cos(math.radians(a)), BOW_Y + r * math.sin(math.radians(a))) for r, a in STEM]
    for p0, p1 in zip(pts, pts[1:]):
        parts.append(bar((p0[0], p0[1], AZ), (p1[0], p1[1], AZ), 0.5, 0.9, "metal",
                         faces=lane_faces("stem", max(p0[1], p1[1]), min(p0[1], p1[1]))))
    return parts


def coin_gem() -> list[dict]:
    """The lucky coin: a round gold disc standing proud of the bezel and its claws (so its
    whole face shows, the claws peeking out round it as prongs), painted front and back."""
    side = {s: [1.2, 7.2, 1.8, 7.8] for s in ("north", "south", "east", "west")}
    side["up"] = side["down"] = [1.2, 7.2, 1.8, 7.8]
    parts = prism((AX, BOW_Y, AZ), GEM_R, 3.1, "gem", axis="z", sides=16, cap="gem", uv=side, glow=14)
    r = GEM_R + 0.05
    plates = [box((AX - r, BOW_Y - r, 9.5), (AX + r, BOW_Y + r, 9.75), "gem", uv="full",
                  skip=("north", "east", "west", "up", "down"), glow=14),
              box((AX - r, BOW_Y - r, 6.25), (AX + r, BOW_Y + r, 6.5), "gem",
                  faces={"north": ("gem", [16, 0, 0, 16])},
                  skip=("south", "east", "west", "up", "down"), glow=14)]
    return parts + turn(plates, COIN_TURN, "z", (AX, BOW_Y, AZ))


def cabochon(c, side: float, d: float, z: float = AZ, glow: int = 6) -> dict:
    """A small square emerald turned 45 degrees: a bright face, darker sides."""
    x, y = c
    e = diamond(c, side, d, "emerald", 45, z=z, glow=glow)
    for face in ("south", "north"):
        e["faces"][face]["uv"] = dot_uv("emerald", y, 0.3)
    for face in ("east", "west", "up", "down"):
        e["faces"][face]["uv"] = dot_uv("emerald", y, 0.9)
    return e


def ring_emeralds() -> list[dict]:
    """Emerald cabochons set in the band between the leaves."""
    parts = []
    for ang in RING_GEMS:
        a = math.radians(ang)
        c = (AX + BAND_R * math.cos(a), BOW_Y + BAND_R * math.sin(a))
        setting = diamond(c, 0.98, BOW_D + 0.22, "band", 45)
        for face in ("south", "north"):
            setting["faces"][face]["uv"] = dot_uv("band", c[1], 0.0)
        gem = cabochon(c, 0.7, BOW_D + 0.42, glow=8)
        for face, flip in (("south", False), ("north", True)):
            gem["faces"][face]["uv"] = lane_uv("emerald", c[1] + 0.25, c[1] - 0.25, flip, (0.0, 0.75))
        parts += turn([setting, gem], ang - 90, "z", (c[0], c[1], AZ))
    return parts


def shamrock_bit() -> list[dict]:
    """The bit: a gold plate inlaid with green enamel, and on its outer edge a three-leaf
    shamrock (the teeth) round a gold boss set with an emerald."""
    bx, by = BOSS
    parts = bit_plate(BIT_X0, PLATE_Y0, PLATE_Y1, 1.7)
    for ang, cell in BIT_LEAVES:
        parts += leaf(BOSS, ang, BIT_S, BIT_R0, BIT_D, cell, rim=BIT_RIM)
    uv = {s: dot_uv("band", by, 0.4) for s in ("north", "south", "east", "west")}
    uv["up"] = uv["down"] = dot_uv("band", by, 0.6)
    parts += prism((bx, by, AZ), 0.66, 2.3, "metal", axis="z", sides=16, cap="metal", uv=uv)
    parts.append(cabochon((bx, by), 0.64, 2.55, glow=8))
    for y in (PLATE_Y0 + 0.2, PLATE_Y1 - 0.2):                 # the plate's top and foot, beaded
        parts += lane_prism(y - 0.3, y + 0.3, 0.38, "band", x=BIT_X0 + 0.15)
    return parts


def lucky_ring() -> list[dict]:
    """A milled gold ring round the middle of the enamelled shaft, set with four emeralds
    (front, back and both sides)."""
    y0, y1 = LUCKY_RING
    y = (y0 + y1) / 2
    parts = lane_prism(y0, y1, 1.45, "band")
    for yaw in (0, 90, 180, 270):
        e = cabochon((AX, y), 0.66, 0.5, z=AZ + 1.5, glow=7)
        parts.append(turn(e, yaw, "y", (AX, y, AZ)) if yaw else e)
    return parts


def emerald_finial() -> list[dict]:
    """A milled gold neck and cup crowned with four claws round a pointed emerald."""
    parts = lane_prism(26.2, 26.75, 0.7, "band")
    parts += lane_prism(26.65, 27.15, 1.05, "band")
    parts += lane_prism(27.05, 28.05, 0.88, "emerald", cap="emerald", glow=9)
    top = 28.05
    parts.append(diamond((AX, top), 1.2, 1.4, "emerald", glow=9))
    e = box((AX - 0.78, top - 0.6, AZ - 0.6), (AX + 0.78, top + 0.6, AZ + 0.6), "metal",
            faces=lane_faces("emerald", top + 0.45, top - 0.45), glow=9)
    parts.append(turn(e, 45, "x", (AX, top, AZ)))
    for yaw in (45, 135, 225, 315):                     # the crown's claws
        a = math.radians(yaw)
        p0 = (AX + 0.98 * math.cos(a), 26.95, AZ + 0.98 * math.sin(a))
        p1 = (AX + 0.74 * math.cos(a), 27.95, AZ + 0.74 * math.sin(a))
        parts.append(bar(p0, p1, 0.34, 0.34, "metal", faces=lane_faces("band", p1[1], p0[1])))
    return parts


def theme() -> list[dict]:
    return clover() + coin_gem() + ring_emeralds() + shamrock_bit() + lucky_ring() + emerald_finial()


# ======================================================================================
# 4. TEXTURES
# ======================================================================================

MILLED = (6, 4, 5, 3)                     # the band lane: two ridges per facet


PANEL = [                      # the bit plate's front: 10 x 14 texels, x 5.0..7.2, y 25.1..18.3
    "6666666665",
    "65444443c2",
    "64aabbbbc2",
    "64abbcccd2",
    "64bb5ccdd2",
    "64b5o5cdd2",
    "64bc5cddd2",
    "64bccddde2",
    "64bc5cdde2",
    "64c5o5dde2",
    "64cc5ddee2",
    "64cddddee2",
    "6433333322",
    "4322222221",
]


def paint_panel(img) -> None:
    """The bit plate: a gold frame round green enamel, set with two little gold studs,
    each a coin with four leaves round it."""
    px = img.load()
    pal = {"a": LEAF[5], "b": LEAF[4], "c": LEAF[3], "d": LEAF[2], "e": LEAF[1], "o": COIN[6]}
    pal.update({str(i): GOLD[i] for i in range(7)})
    c0, _ = LANES["panel"]
    r0 = row_of(PLATE_Y1)
    for j, line in enumerate(PANEL):
        for i, ch in enumerate(line):
            px[c0 + i, r0 + j] = rgba(pal[ch])


def paint_theme_lanes(img) -> None:
    paint_lane(img, "band", [GOLD[i] for i in MILLED])
    paint_lane(img, "bezel", [LEAF[0], LEAF[1], GOLD[1]])     # the coin sits in dark green enamel
    paint_lane(img, "rim", [GOLD[5], GOLD[4], GOLD[3]])
    paint_lane(img, "leaf", [LEAF[3], LEAF[2], LEAF[2]])
    paint_lane(img, "stem", [LEAF[5], LEAF[4], LEAF[3]])
    paint_lane(img, "emerald", [EMERALD[5], EMERALD[4], EMERALD[3], EMERALD[2]])
    paint_panel(img)
    # green enamel round the shaft between the bands: gold borders, a gold stud per facet
    px = img.load()
    c0, w = LANES["shaft"]
    top, bottom = row_of(ENAMEL_Y[1]), row_of(ENAMEL_Y[0])
    mid = (top + bottom) // 2
    enamel = [LEAF[5], LEAF[4], LEAF[3], LEAF[2]]
    border = [GOLD[6], GOLD[5], GOLD[4], GOLD[3]]
    for r in range(top, bottom + 1):
        for i in range(w):
            if r in (top, bottom):
                c = border[i]
            else:
                c = enamel[i]
                if r == top + 1:
                    c = LEAF[2] if i else LEAF[3]               # shadow under the upper border
                if r == mid and i in (1, 2):
                    c = GOLD[6] if i == 1 else GOLD[4]          # a gold stud on every facet
            px[c0 + i, r] = rgba(c)


# Lucky glints: (ring part, angle on the ring, loop phase of its twinkle). Each ring segment
# samples its lane at the rows of its own height, so a star painted at one (lane, row)
# lights one spot on the ring, as long as it sits on the ring's sides (at the top and bottom
# the segments lie flat and mirror pairs share their rows). The darker right-hand side
# (lanes face0, rim0, lip2) shows them best.
RING_GLINTS = (("band", 20, 0.34), ("rim", 330, 0.5), ("lip", 45, 0.66), ("band", 300, 0.8),
               ("rim", 12, 0.95))
GLINT_LEN = 0.14


def ring_spot(part: str, ang: float) -> tuple[str, float]:
    """The lane and model height that paint the ring's `part` at angle `ang`."""
    radius, lanes = {"band": (BAND_R, FACE_LANES), "rim": (BOW_OUT - RIM_W / 2, RIM_LANES),
                     "lip": (BOW_IN + LIP_W / 2, LIP_LANES)}[part]
    step = 360 / BOW_SEGMENTS
    mid = 90 + round((ang - 90) / step) * step
    a = math.radians(mid)
    lane = lanes[round((lit((math.cos(a), math.sin(a))) + 1) * (len(lanes) - 1) / 2)]
    return lane, BOW_Y + radius * math.sin(math.radians(ang))


def lane_star(img, lane: str, y: float, amount: float, colour: str = "#ffffff") -> None:
    """A four-point twinkle inside one lane: long arms along the rows (along the ring),
    short ones across the lane."""
    if amount <= 0.02:
        return
    px = img.load()
    c0, w = LANES[lane]
    col = c0 + w // 2
    row = row_of(y)
    reach = round(3 * amount)

    def put(x, r, k):
        if c0 <= x < c0 + w and 0 <= r < ATLAS:
            cur = "#%02x%02x%02x" % px[x, r][:3]
            px[x, r] = rgba(mix(cur, colour, k))

    put(col, row, min(1.0, 0.55 + amount))
    for i in range(1, reach + 1):
        k = amount * (1 - (i - 1) / (reach + 1))
        put(col, row - i, k)
        put(col, row + i, k)
    for i in (1, 2):
        put(col - i, row, amount * (0.75 if i == 1 else 0.35))
        put(col + i, row, amount * (0.75 if i == 1 else 0.35))


def theme_atlas_frame(img, t: float) -> None:
    """Lucky glints twinkle on the ring, one after another, while the sweep rests."""
    for part, ang, phase in RING_GLINTS:
        x = ((t - phase) % 1.0) / GLINT_LEN
        if x < 1:
            lane, y = ring_spot(part, ang)
            lane_star(img, lane, y, math.sin(math.pi * x))


def paint_atlas_base():
    img = canvas(ATLAS, fill=METAL[3])
    paint_skeleton_lanes(img, METAL)
    paint_theme_lanes(img)
    return img


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


# --- the clover's enamel: one painting per leaf, lit for its angle, with its own shimmer ---

def leaf_texel(i: int, j: int) -> tuple[float, float]:
    """Leaf-frame (u, v) at the centre of texel (i, j) of a leaf painting."""
    return (((i + 0.5) / CELL - 0.5) * PLATE, PLATE_V + (0.5 - (j + 0.5) / CELL) * PLATE)


def paint_leaf(img, cell: int, ang: float, prog, rim: bool) -> None:
    """Paint one leaf, lit from the key's light: a heart of glossy green enamel folded along
    a pale midrib with a glassy highlight on the lit lobe, set in a gold rim if rim; prog
    (0..1 or None) moves the shimmer through it from its point to its lobes."""
    px = img.load()
    ox, oy = CELL * (cell % 4), CELL * (cell // 4)
    a = math.radians(ang)
    lu = LIGHT[0] * math.sin(a) - LIGHT[1] * math.cos(a)
    lv = LIGHT[0] * math.cos(a) + LIGHT[1] * math.sin(a)
    n = math.hypot(lu, lv)
    lu, lv = lu / n, lv / n
    di, dj = round(lu), -round(lv)
    if di == 0 and dj == 0:
        di = 1 if lu > 0 else -1
    outer = [[in_leaf(*leaf_texel(i, j), inset=0.03) for i in range(CELL)] for j in range(CELL)]
    inner = [[in_leaf(*leaf_texel(i, j), inset=ENAMEL_INSET) for i in range(CELL)]
             for j in range(CELL)] if rim else outer

    def get(mask, i, j):
        return 0 <= i < CELL and 0 <= j < CELL and mask[j][i]

    lobe_u, lobe_v, lobe_r = LEAF_LOBES
    notch = lobe_v + math.sqrt(max(0.0, lobe_r ** 2 - lobe_u ** 2))
    glossy = [sgn for sgn in (-1, 1) if sgn * lu > -0.25]
    vein = 1 if lu >= -0.3 else -1                        # the midrib column on the lit side
    env = math.sin(math.pi * prog) if prog is not None else 0.0
    half = PLATE / CELL / 2
    for j in range(CELL):
        for i in range(CELL):
            if not outer[j][i]:
                continue
            u, v = leaf_texel(i, j)
            if not inner[j][i]:                                  # the gold rim
                if not get(outer, i + di, j + dj):
                    f = 5.4
                elif not get(outer, i - di, j - dj):
                    f = 2.2
                else:
                    f = 4.0 + 0.8 * (u * lu + (v - PLATE_V) * lv) / 1.5
                px[ox + i, oy + j] = rgba(ramp_at(GOLD, f + 0.6 * env))
                continue
            f = 4.0 + 0.35 * (u * lu + (v - PLATE_V) * lv) / 1.5
            if abs(lu) > 0.3:                                  # folded along the midrib
                f += 0.25 if u * lu > 0 else -0.25
            if 0 < u * vein < 2 * half and 0.45 < v < notch - 0.1:
                f += 0.6                                         # the pale midrib
            if rim:
                if not get(inner, i + di, j + dj):
                    f = min(f, 3.0)                              # in the rim's shadow
                elif not get(inner, i - di, j - dj):
                    f = max(f, 4.9)                              # the far wall catches the light
            else:
                if not get(outer, i + di, j + dj):
                    f = 5.2                                      # lit edge
                elif not get(outer, i - di, j - dj):
                    f = 3.0                                      # shadowed edge
            c = ramp_at(LEAF, f + 1.25 * env)
            for sgn in glossy:                                  # a glassy spot on the lit lobe
                gx, gy = sgn * lobe_u + 0.4 * lobe_r * lu, lobe_v + 0.4 * lobe_r * lv
                dd = math.hypot(u - gx, v - gy)
                if dd < 0.16:
                    c = LEAF[6]
                elif dd < 0.3:
                    c = mix(c, LEAF[5], 0.7)
            if prog is not None:                                # the shimmer: a sheen crossing it
                vb = -0.4 + 3.1 * prog
                k = 0.72 * max(0.0, 1 - abs(v - vb) / 0.5) * env ** 0.5
                if k > 0:
                    c = mix(c, SHEEN, k)
            px[ox + i, oy + j] = rgba(c)
    if prog is not None and 0.55 < prog < 0.95 and glossy:
        sgn = glossy[0]
        gx, gy = sgn * lobe_u + 0.4 * lobe_r * lu, lobe_v + 0.4 * lobe_r * lv
        ti = int((gx / PLATE + 0.5) * CELL)
        tj = int((0.5 - (gy - PLATE_V) / PLATE) * CELL)
        amount = math.sin(math.pi * (prog - 0.55) / 0.4)
        cell_star(img, ox, oy, ti, tj, amount)


def cell_star(img, ox: int, oy: int, i: int, j: int, amount: float, colour: str = "#fffbe0") -> None:
    """A small four-point twinkle at texel (i, j) of one leaf painting, kept inside its cell."""
    px = img.load()
    reach = round(2 * amount)
    for di, dj, k in [(0, 0, min(1.0, 0.5 + amount))] + [
            (dx * n, dy * n, amount * (1 - (n - 1) / (reach + 1)))
            for n in range(1, reach + 1) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]:
        x, y = i + di, j + dj
        if 0 <= x < CELL and 0 <= y < CELL:
            r, g, b, a = px[ox + x, oy + y]
            cur = "#%02x%02x%02x" % (r, g, b) if a else colour
            c = rgba(mix(cur, colour, k))
            px[ox + x, oy + y] = (c[0], c[1], c[2], max(a, round(255 * min(1.0, k))))


def clover_frame(t: float):
    img = canvas(64)
    for cell, (ang, t0, length, rim) in CELLS.items():
        x = ((t - t0) % 1.0) / length
        paint_leaf(img, cell, ang, x if x < 1 else None, rim)
    return img


# --- the lucky coin -------------------------------------------------------------------------

COIN_CLOVER = [               # the coin's embossed four-leaf clover (texture space)
    ".##...##.",
    "####.####",
    "####.####",
    ".###.###.",
    "....#....",
    ".###.###.",
    "####.####",
    "####.####",
    ".##...##.",
]
COIN_MOTIF = {(4 + i, 4 + j) for j, row in enumerate(COIN_CLOVER) for i, ch in enumerate(row) if ch == "#"}
COIN_TURN = 45                # the coin's plates turn so its clover matches the big one
COIN_LIGHT = (-0.6, -0.8)     # the key's light in the turned plate's texture space


def gem_frame(t: float):
    """The coin's face: a milled edge, a raised border, a dished gold field and an inlaid
    four-leaf clover of green enamel round a gold pip; at its flare the gold brightens,
    the enamel glows and a star is thrown off the border."""
    p = gem_pulse(t)
    lift = 0.15 + 1.1 * p
    img = canvas(16)
    px = img.load()
    lx, ly = COIN_LIGHT
    sx, sy = round(lx), round(ly)
    for ty in range(16):
        for tx in range(16):
            dx, dy = tx + 0.5 - 8, ty + 0.5 - 8
            r = math.hypot(dx, dy)
            if r > 8.0:
                continue
            ndx, ndy = dx / (r or 1), dy / (r or 1)
            facing = ndx * lx + ndy * ly
            if r > 7.0:                                          # milled edge
                ang = math.atan2(dy, dx)
                f = 2.2 + (0.9 if int((ang + math.pi) / (2 * math.pi) * 28) % 2 else 0) + 0.8 * facing
            elif r > 5.9:                                        # raised border, lit top-left
                f = 4.4 + 1.3 * facing
            elif (tx, ty) in COIN_MOTIF:                         # the enamel clover
                g = 3.9 + 1.4 * p
                if (tx - sx, ty - sy) not in COIN_MOTIF:
                    g = 5.0 + 0.8 * p                            # lit edge of the inlay
                elif (tx + sx, ty + sy) not in COIN_MOTIF:
                    g = 2.6 + 1.0 * p
                px[tx, ty] = rgba(ramp_at(LEAF, g))
                continue
            else:                                                # the field, dished
                f = 3.3 - 0.7 * facing * (r / 5.9)
                if (tx - sx, ty - sy) in COIN_MOTIF:
                    f = 2.4                                      # the inlay's sunk edge
            px[tx, ty] = rgba(ramp_at(COIN, f + lift))
    for x, y in ((7, 7), (8, 8)):                                # the gold pip
        px[x, y] = rgba(ramp_at(COIN, 5.4 + lift))
    px[8, 7] = px[7, 8] = rgba(ramp_at(COIN, 4.2 + lift))
    sparkle(img, 4, 4, clamp(p * 1.3 - 0.25), colour="#ffffff", reach=4)
    return img


# --- the window: a dark glade where lucky gold glints twinkle -------------------------------
GLINTS = ((24, 7, 0.1, 2), (7, 7, 0.35, 2), (7, 25, 0.6, 2), (25, 25, 0.85, 2), (29, 16, 0.5, 1),
          (16, 2, 0.22, 1), (2, 16, 0.72, 1), (16, 30, 0.97, 1))
_rng = random.Random(17)
# Gold dust rising through the glade: (x, starting y, sway phase); each mote climbs one
# window height per loop, so the loop is seamless.
MOTES = [(_rng.randrange(3, 29), _rng.randrange(32), _rng.random()) for _ in range(9)]
MOSS = {(x, y) for x, y in ((_rng.randrange(32), _rng.randrange(32)) for _ in range(70))}


def window_frame(t: float):
    """A deep green glade in the bow: brighter at the heart, mossy speckle, the coin's halo
    breathing, gold dust rising and lucky glints twinkling one after another."""
    p = gem_pulse(t)
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 16, (y + 0.5 - 16) / 16
            r = math.hypot(dx, dy)
            f = 4.6 - 3.6 * r + 0.5 * (y / 31 - 0.5)
            band = int(clamp(dither_band(f, x, y), 0, len(GLADE) - 1))
            if (x, y) in MOSS:
                band = max(0, band - 1) if (x * 7 + y) % 3 else min(len(GLADE) - 1, band + 1)
            c = GLADE[band]
            halo = (0.2 + 0.6 * p) * max(0.0, 1 - r / 0.6) ** 1.5
            if halo > 0.04:
                c = mix(c, COIN[4], min(0.7, halo))
            px[x, y] = rgba(c)
    for mx, my, ph in MOTES:
        fy = int((my - 32 * t) % 32)
        fx = int(round(mx + 0.9 * math.sin(2 * math.pi * (t + ph)))) % 32
        cur = "#%02x%02x%02x" % px[fx, fy][:3]
        px[fx, fy] = rgba(mix(cur, COIN[5], 0.65))
    for x, y, ph, reach in GLINTS:
        amount = clamp(1.6 * wave(t, 0.5 - ph) - 0.6)
        sparkle(img, x, y, amount, colour=GLINT, reach=reach)
    return img


def textures() -> None:
    base = paint_atlas_base()
    save_animation(animate(lambda t: atlas_frame(base, t), FRAMES), "metal", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(window_frame, FRAMES), "window", frametime=FRAMETIME)
    save_animation(animate(clover_frame, FRAMES), "clover", frametime=FRAMETIME)


# ======================================================================================
# 5. MODELS
# ======================================================================================

def models() -> dict:
    parts = skeleton() + theme()
    return {"main": model(parts, transforms(parts))}
