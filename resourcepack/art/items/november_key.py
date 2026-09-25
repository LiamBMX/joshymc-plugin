"""November Key: the copper crate key of the harvest feast (one of the 19 JoshyMC crate keys).

The bow is the set's stepped ring, cast in rose copper, studded with golden grains and
wreathed with ripe wheat: three ears climb each side of the ring from a crossed knot of
stalks at its foot. Inside it a golden wheat sheaf stands in a window of harvest-home
light, five plump ears fanning over the heart and its cut stalks splaying below, tied at
the waist by a crimson ribbon bow whose knot is a step-cut topaz held by six claws. A
flared collar joins the ring to the octagonal shaft, inlaid with a gold vine and bound
halfway up by a crimson ribbon; three ears of wheat crown it. On its -X side the bit is a
horn of plenty: a gold-ribbed cornucopia curling off the shaft, overflowing with wheat,
and spilling a bunch of grapes, a red apple and a nodding ear of wheat as its teeth.

Animation (one 3.2 s loop): a warm glint sweeps up the whole key from the bow to the tip,
the topaz breathes and flares as the glint passes through it, its amber glow swells and
ebbs in the plum-dusk window (warming the sheaf and the claws) while golden chaff drifts up
through it, glints hop from ear to ear of the wheat, and while the metal rests a golden
shimmer runs down all the wheat like wind over a field.

---------------------------------------------------------------------------------------
KEY SET SKELETON: all 19 crate keys share these proportions (model units)
---------------------------------------------------------------------------------------
  frame   upright on x = 8, z = 8; front = +Z (the face the inventory shows)
  bow     a stepped ring centred at (8, BOW_Y = 3.5): outer radius 5.4 (10.8 across,
          y -1.9..8.9), inner radius 3.5; a recessed outer rim (0.5 wide, 2.0 deep), the
          main band (2.6 deep, z 6.7..9.3) and a raised inner lip (0.6 wide, 3.0 deep),
          20 segments each. Inside: a recessed window (the theme's animated scene), the
          theme's emblem, and at the heart a socket (bezel + six claws) holding the
          theme's gem, which stands proudest of everything
  collar  two octagonal discs, y 8.4..10.2, joining the ring's top to the shaft
  shaft   octagonal, apothem 1.15 (2.3 thick), y 10..25.5, bands at y 13 and 17.6,
          a cap at 25.4..26.3; the theme may crown it with a finial (up to y ~29.5)
  bit     on the -X side of the upper shaft, y 18..25.2: a plate (bit_plate, painted by
          the "panel" lane) plus the theme's teeth
  grip    (8, 10.6, 8): the fist closes on the shaft just above the bow
  size    SIZE = 0.64 holds it at about 1 block; carried upright like a sceptre
  gui     GUI_ROTATION tilts the diagonal key back a little so its faces catch the light

Animation approach (every loop is 64 ticks, so all the pieces stay in step)
  metal   ONE 64 px atlas textures every metal, wheat, straw, ribbon and fruit face. Its ROWS map
          to the key's height (row = 2 * (29 - y)); its COLUMNS are LANES: narrow bands
          painted with one material's cross-section. Every face samples its lane at the
          rows of its own height (lane_uv / lane_faces), so ONE horizontal shine() band
          sweeping up the atlas glints along the whole key in one piece, bow to tip.
          Columns 0-41 are the skeleton's metal lanes (painted from METAL), 42-63 the
          theme's. 32 frames x 2 ticks.
  gem     the heart gem, interpolated (16 frames x 4 ticks)
  window  the theme's scene inside the bow (32 frames x 2 ticks)
"""
from __future__ import annotations

import math

from art.kit import (animate, bar, box, canvas, display, mix, model, place, prism, rgba,
                     save_animation, shine, sparkle, turn)

ID = "november_key"
NAME = "November Key"
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
    "wheat": (42, 2), "wheat2": (44, 2), "straw": (46, 3), "gold": (49, 3), "ribbon": (52, 2),
    "horn": (54, 3), "grape": (57, 2), "apple": (59, 2), "hollow": (61, 2), "leaf": (63, 1),
    "panel": (54, 10),            # the bit's panel, painted over the window-only lanes at its rows
}
# Light variants, from "faces away from the light" to "faces it" (see arc_segment).
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#fff4d8", width=8.0, strength=0.88, angle=-90.0, pause=0.4)   # bow -> tip
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
# 2. THEME PALETTE: November, rose copper (#B87333), wheat gold, topaz and the feast
# ======================================================================================
METAL = COPPER = ["#230c17", "#461820", "#742a23", "#a2432b", "#c46e3a", "#e6956a", "#ffcfae"]
GOLD = ["#3f2206", "#6e420a", "#a56f12", "#d59f22", "#f2c94a", "#ffe98a", "#fffbe2"]
HORN_GOLD = ["#3b1a08", "#6c360c", "#a05a14", "#cf8a26", "#eeb548", "#ffdc86", "#fff4d0"]
STRAW = ["#5b3510", "#8c5a1a", "#bb8a32", "#e0b858", "#f6dc8e"]
TOPAZ = ["#4a1404", "#7e2c06", "#b8560c", "#e88418", "#ffae2e", "#ffd04e", "#fff0a8", "#ffffff"]
WINE = ["#12081e", "#1e0c2e", "#301444", "#4a1e52", "#71305a", "#a24a52"]   # plum dusk warming to rose
AMBER = ["#c8661a", "#ec9028", "#ffbb50", "#ffe096"]
CRIMSON = ["#35060f", "#5e0e1c", "#8e1c2a", "#c03a3c", "#e8736a"]
GRAPE = ["#1a0a2c", "#321852", "#552a80", "#7d4aae", "#ab7fd8"]
APPLE = ["#420a0e", "#7c1418", "#b82a22", "#e2573a", "#ff9670"]
LEAF = ["#2a300c", "#4a5a14", "#72862a", "#a2b44c", "#cfd878"]

# ======================================================================================
# 3. THEME PARTS: the wheat sheaf and its ribbon bow in the window, the topaz, a wheat
#    wreath and golden grains on the ring, the ribboned shaft, a cornucopia bit spilling
#    fruit as its teeth, and a crown of three ears of wheat
# ======================================================================================
STUD_ANGLES = tuple(120 + 60 * k for k in range(6))   # golden grains between the claws
SHEAF_Z = AZ + 0.3                                     # the sheaf stands in the window
SHEAF_EARS = ((42, 3.2, "wheat2"), (66, 3.4, "wheat"), (90, 3.5, "wheat2"), (114, 3.4, "wheat"),
              (138, 3.2, "wheat2"))                   # (angle, reach, lane)
SHEAF_STALKS = (240, 255, 270, 285, 300)
RIBBON_LOOPS = (0, 180)
WREATH_R = BOW_OUT + 0.3
WREATH = ((262, 222, "wheat"), (222, 182, "wheat2"), (182, 142, "wheat"))  # the left side, foot up
WREATH_Z = AZ + 0.45
# The cornucopia's centreline (x, y, width) from its curled tip against the shaft to its mouth.
HORN = ((6.1, 25.6, 0.4), (6.78, 25.35, 0.54), (7.0, 24.62, 0.7), (6.55, 23.92, 0.92), (5.82, 23.5, 1.18),
        (5.0, 23.3, 1.46), (4.22, 23.24, 1.74), (3.55, 23.3, 2.0))
HORN_RIBS = (3, 5)
GRAPES = ((3.25, 21.85), (3.82, 21.92), (4.4, 21.82), (3.5, 21.3), (4.08, 21.3), (3.35, 20.75), (3.92, 20.75),
          (3.65, 20.2), (3.78, 19.65))
APPLE_AT = (5.2, 21.85)
BIT_BAND = (23.5, 24.3, 1.42)          # the gold band the horn is fixed to
RIBBON_BAND = (14.75, 15.65, 1.34)     # the crimson ribbon tied round the shaft
ENGRAVED = (13.6, 17.0)                # a gold vine inlaid round the shaft between its bands


def seg(p0, p1, w: float, d: float, lane: str, east=None, west=None, sides=None, **kw) -> dict:
    """A bar from p0 to p1 ((x, y) or (x, y, z)) whose faces sample `lane` at their own
    heights, the lane's lit column on the side facing the light."""
    p0 = (p0[0], p0[1], p0[2] if len(p0) > 2 else AZ)
    p1 = (p1[0], p1[1], p1[2] if len(p1) > 2 else AZ)
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    n = math.hypot(dx, dy) or 1.0
    flip = lit((-dy / n, dx / n)) < 0
    return bar(p0, p1, w, d, "metal", faces=lane_faces(lane, p1[1], p0[1], east, west, flip, sides), **kw)


def kernel(c, side: float, d: float, lane: str, ang: float, z: float = AZ, **kw) -> dict:
    """A square turned by ang about Z (like diamond()) with its lane lit on the side that
    faces the light."""
    cx, cy = c
    h = side * 0.35
    a = math.radians(ang)
    flip = lit((-math.cos(a), -math.sin(a))) < 0
    e = box((cx - side / 2, cy - side / 2, z - d / 2), (cx + side / 2, cy + side / 2, z + d / 2), "metal",
            faces=lane_faces(lane, cy + h, cy - h, flip=flip), **kw)
    return turn(e, ang, "z", (cx, cy, z))


def wheat_ear(c, ang: float, r0: float, r1: float, w: float, d: float, z: float = AZ, glow: int = 0,
              awns: float = 0.0, spread: float = 9.0, kernels: int = 3, lane: str = "wheat") -> list[dict]:
    """An ear of wheat along ang from its neck at radius r0 to its tip at r1: a slim core
    with `kernels` pairs of plump spikelets braided up its two sides, a pointed tip, and
    awns (length `awns`) fanning out beyond it."""
    length = r1 - r0
    a = math.radians(ang)
    ux, uy = math.cos(a), math.sin(a)
    nx, ny = -uy, ux
    top = 0.6 * w
    parts = [radial(c, ang, r0, r1 - 0.5 * top, 0.5 * w, 0.78 * d, lane, z=z, glow=glow)]
    start, stop = r0 + 0.22 * length, r1 - 0.62 * w
    step = (stop - start) / max(0.5, kernels - 0.5)
    s = 0.44 * w
    for k in range(kernels):
        for sgn, off in ((1, 0.0), (-1, 0.5)):
            r = start + (k + off) * step
            if r > stop + 1e-6:
                continue
            p = (c[0] + r * ux + sgn * 0.25 * w * nx, c[1] + r * uy + sgn * 0.25 * w * ny)
            parts.append(kernel(p, s, 0.94 * d, lane, ang - 45 + sgn * 14, z=z, glow=glow))
    end = r1 - top / 2
    parts.append(kernel((c[0] + end * ux, c[1] + end * uy), top / math.sqrt(2), 0.84 * d, lane, ang - 45,
                        z=z, glow=glow))
    if awns > 0:
        base = r1 - 0.3
        for off in (-spread, 0.0, spread):
            b = math.radians(ang + off * 0.6)
            p = (c[0] + base * math.cos(b), c[1] + base * math.sin(b))
            parts.append(radial(p, ang + off, 0.0, awns * (0.78 if off else 1.0), 0.18, 0.18, "straw", z=z,
                                glow=glow))
    return parts


def polar(ang: float, r: float, z: float = AZ):
    a = math.radians(ang)
    return (AX + r * math.cos(a), BOW_Y + r * math.sin(a), z)


def ear_between(p0, p1, w: float, d: float, z: float, **kw) -> list[dict]:
    """An ear of wheat from its neck at p0 to its tip at p1 (front-plane points)."""
    ang = math.degrees(math.atan2(p1[1] - p0[1], p1[0] - p0[0]))
    return wheat_ear(p0[:2], ang, 0.0, math.hypot(p1[0] - p0[0], p1[1] - p0[1]), w, d, z=z, **kw)


def fruit(c, size, lane: str, squash: float = 1.0, **kw) -> list[dict]:
    """A round fruit from three crossed boxes (a rounded cube), shaded by its lane."""
    x, y, z = c
    s = size / 2
    t = s * 0.72
    h = s * squash
    parts = []
    for (ax, ay, az) in ((s, h * 0.72, t), (t, h, t), (t, h * 0.72, s)):
        parts.append(box((x - ax, y - ay, z - az), (x + ax, y + ay, z + az), "metal",
                         faces=lane_faces(lane, y + ay * 0.7, y - ay * 0.7), **kw))
    return parts


def wheat_sheaf() -> list[dict]:
    """The emblem in the window: five golden ears fanning over the gem, three cut stalks
    splaying below it, and a crimson ribbon bow tied round the waist, its knot the topaz."""
    c = (AX, BOW_Y)
    parts = []
    for ang, reach, lane in SHEAF_EARS:
        parts += wheat_ear(c, ang, 1.45, reach, 0.86, 1.2, z=SHEAF_Z, glow=6, kernels=2, lane=lane)
    for ang in SHEAF_STALKS:
        parts.append(radial(c, ang, 1.4, 3.45, 0.36, 1.0, "straw", z=SHEAF_Z, glow=5))
    zr = SHEAF_Z + 0.15
    for ang in RIBBON_LOOPS:                  # each loop: two ribbon edges splaying out, joined at the end
        for off in (-17, 17):
            parts.append(radial(c, ang + off, 1.35, 2.72, 0.42, 0.9, "ribbon", z=zr, glow=5))
        a = math.radians(ang)
        end = (AX + 2.62 * math.cos(a), BOW_Y + 2.62 * math.sin(a))
        parts.append(radial(end, ang + 90, -0.78, 0.78, 0.4, 0.9, "ribbon", z=zr, glow=5))
    return parts


def topaz_gem() -> list[dict]:
    """A glowing octagonal topaz: a body plus a painted front and back."""
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


def ring_grains() -> list[dict]:
    """Plump golden grains set round the band between the claws."""
    return [kernel(polar(ang, BAND_R)[:2], 0.58, BOW_D + 0.24, "gold", ang - 45, glow=3) for ang in STUD_ANGLES]


def wheat_wreath() -> list[dict]:
    """Ripe ears climbing both sides of the ring from a crossed knot of stalks at its foot,
    the shoulder ears bearded with awns that point to the collar."""
    parts = []
    for side in (1, -1):
        for i, (a0, a1, lane) in enumerate(WREATH):
            if side < 0:
                a0, a1 = (540 - a0) % 360, (540 - a1) % 360
            last = i == len(WREATH) - 1
            parts += ear_between(polar(a0, WREATH_R - 0.15), polar(a1, WREATH_R + 0.3), 1.05, 1.9, WREATH_Z,
                                 kernels=2, awns=0.7 if last else 0.0, lane=lane)
        foot = 262 if side > 0 else 278
        p0, p1 = polar(foot, WREATH_R - 0.3), polar(270 + side * 22, WREATH_R + 0.6)
        parts.append(seg(p0[:2] + (WREATH_Z,), p1[:2] + (WREATH_Z,), 0.3, 1.2, "straw"))
    parts.append(kernel(polar(270, WREATH_R - 0.05)[:2], 0.62, 1.7, "gold", 225, z=WREATH_Z + 0.1))
    return parts


def cornucopia_bit() -> list[dict]:
    """The bit: a golden horn of plenty ribbed with copper, curling off a gold band on the
    shaft, wheat spilling up out of its mouth and a bunch of grapes, a red apple and a
    nodding ear of wheat hanging under it as its teeth."""
    parts = lane_prism(BIT_BAND[0], BIT_BAND[1], BIT_BAND[2], "gold", cap="gold")
    pts = HORN
    for i, (a, b) in enumerate(zip(pts, pts[1:])):
        w = (a[2] + b[2]) / 2
        dx, dy = b[0] - a[0], b[1] - a[1]
        n = math.hypot(dx, dy)
        e0 = (0.0 if i == 0 else 0.1) / n
        e1 = 0.1 / n
        parts.append(seg((a[0] - dx * e0, a[1] - dy * e0), (b[0] + dx * e1, b[1] + dy * e1), w, w * 1.1, "horn"))
    for i in HORN_RIBS:
        (xa, ya, _), (x, y, w), (xb, yb, _) = pts[i - 1], pts[i], pts[i + 1]
        dx, dy = xb - xa, yb - ya
        n = math.hypot(dx, dy)
        u = (dx / n * 0.13, dy / n * 0.13)
        parts.append(seg((x - u[0], y - u[1]), (x + u[0], y + u[1]), w + 0.2, w * 1.1 + 0.2, "band"))
    # the gilt lip of the mouth, its hollow dark
    (xa, ya, _), (xm, ym, wm) = pts[-2], pts[-1]
    dx, dy = xm - xa, ym - ya
    n = math.hypot(dx, dy)
    u = (dx / n, dy / n)
    parts.append(seg((xm - u[0] * 0.22, ym - u[1] * 0.22), (xm + u[0] * 0.1, ym + u[1] * 0.1), wm + 0.3,
                     wm * 1.1 + 0.3, "gold", sides={"up": dot_uv("hollow", ym)}))
    # the harvest: wheat bursting up out of the mouth ...
    parts += wheat_ear((3.5, 24.1), 110, 0.0, 1.95, 0.64, 0.68, awns=0.45, kernels=2, lane="wheat2", z=AZ + 0.25)
    parts += wheat_ear((3.55, 23.6), 152, 0.0, 1.35, 0.56, 0.58, kernels=1, lane="wheat", z=AZ - 0.3)
    # ... and hanging under it: grapes, an apple on its stalk with a leaf, and a nodding ear
    for k, (gx, gy) in enumerate(GRAPES):
        s = 0.62
        gz = AZ + (0.2 if k % 2 else -0.15)
        parts.append(box((gx - s / 2, gy - s / 2, gz - s / 2), (gx + s / 2, gy + s / 2, gz + s / 2), "metal",
                         faces=lane_faces("grape", gy + 0.2, gy - 0.2)))
    parts.append(kernel((4.45, 22.3), 0.8, 0.2, "leaf", 20, z=AZ + 0.62))             # vine leaves
    parts.append(kernel((2.9, 22.05), 0.7, 0.2, "leaf", -25, z=AZ + 0.3))
    x, y = APPLE_AT
    parts += fruit((x, y, AZ + 0.1), 1.15, "apple")
    parts.append(seg((x, y + 0.4, AZ + 0.1), (x + 0.12, y + 0.8, AZ + 0.1), 0.14, 0.14, "leaf"))
    parts.append(kernel((x - 0.34, y + 0.5), 0.4, 0.16, "leaf", 60, z=AZ + 0.62))
    parts += wheat_ear((6.35, 23.1), 264, 0.0, 3.0, 0.68, 0.68, awns=0.5, kernels=2, lane="wheat")
    return parts


def ribbon_band() -> list[dict]:
    """A crimson ribbon tied round the shaft, its knot and two short tails on the front."""
    y0, y1, r = RIBBON_BAND
    parts = lane_prism(y0, y1, r, "ribbon", cap="ribbon")
    ym = (y0 + y1) / 2
    zf = AZ + r + 0.05
    parts.append(box((AX - 0.3, ym - 0.3, zf - 0.2), (AX + 0.3, ym + 0.3, zf + 0.22), "metal",
                     faces=lane_faces("ribbon", ym + 0.3, ym - 0.3)))
    for ang in (244, 296):
        parts.append(radial((AX, ym - 0.1), ang, 0.1, 1.05, 0.26, 0.2, "ribbon", z=zf + 0.02))
    return parts


def wheat_crown() -> list[dict]:
    """Three ears of wheat crowning the shaft from a gilt cup tied with crimson."""
    base = CAP[1]
    parts = lane_prism(base - 0.1, base + 0.5, 0.78, "gold", cap="gold")
    c = (AX, base + 0.45)
    parts += wheat_ear(c, 90, 0.1, 2.6, 0.72, 0.72, awns=0.42, kernels=2, lane="wheat")
    for ang in (60, 120):
        parts += wheat_ear(c, ang, 0.1, 2.1, 0.62, 0.62, kernels=2, lane="wheat2")
    return parts


def theme() -> list[dict]:
    return (wheat_sheaf() + topaz_gem() + ring_grains() + wheat_wreath() + cornucopia_bit() + ribbon_band()
            + wheat_crown())


# ======================================================================================
# 4. TEXTURES
# ======================================================================================

def paint_theme_lanes(img) -> None:
    px = img.load()
    # wheat: plump kernels braided up the ear, lit from the first column; two lanes so
    # neighbouring ears can glint in turn
    for lane, shift in (("wheat", 0), ("wheat2", 2)):
        c0, w = LANES[lane]
        for r in range(ATLAS):
            tones = ((GOLD[5], GOLD[3]), (GOLD[4], GOLD[2]), (GOLD[5], GOLD[4]), (GOLD[3], GOLD[1]))[(r + shift) % 4]
            for i in range(w):
                px[c0 + i, r] = rgba(tones[min(i, 1)])
    paint_lane(img, "straw", [STRAW[4], STRAW[3], STRAW[1]])
    paint_lane(img, "horn", [HORN_GOLD[5], HORN_GOLD[4], HORN_GOLD[2]])
    paint_lane(img, "gold", [GOLD[6], GOLD[5], GOLD[3]])
    paint_lane(img, "ribbon", ["#d84646", CRIMSON[2]])
    paint_lane(img, "grape", [GRAPE[4], GRAPE[2]])
    paint_lane(img, "apple", [APPLE[4], APPLE[2]])
    paint_lane(img, "hollow", [WINE[0], WINE[1]])
    paint_lane(img, "leaf", [LEAF[3]])
    # the shaft between its bands: a gold vine inlaid round it in a double spiral
    c0, w = LANES["shaft"]
    for r in range(row_of(ENGRAVED[1]), row_of(ENGRAVED[0]) + 1):
        for i in range(w):
            if (i + r) % 4 == 0:
                px[c0 + i, r] = rgba(GOLD[4] if i < 2 else GOLD[3])
            elif (i + r) % 4 == 1:
                px[c0 + i, r] = rgba(COPPER[2])


GLINTS = (  # (lane, height, phase): golden glints hopping from ear to ear, bow and crown
    ("wheat2", 6.1, 0.03), ("wheat", 27.6, 0.15), ("wheat", 1.2, 0.27), ("wheat", 21.2, 0.39),
    ("wheat2", 7.6, 0.51), ("wheat", -0.9, 0.63), ("wheat2", 28.4, 0.75), ("wheat2", 4.6, 0.87))
WIND = (0.6, 0.98)            # when the shimmer runs down the wheat (the sweep rests from 0.6)
WARMED = ("wheat", "wheat2", "straw", "ribbon", "band", "gold")   # lanes the topaz lights in the bow


def glint(t: float, phase: float, width: float = 0.05) -> float:
    d = abs(((t - phase + 0.5) % 1.0) - 0.5)
    return max(0.0, 1 - d / width)


def gem_glow(t: float) -> float:
    """0..1: the topaz breathes once per loop, peaking (with a flare) as the glint passes."""
    breathe = 0.5 + 0.5 * math.cos(2 * math.pi * (t - GEM_PEAK))
    return clamp(0.62 * breathe + 0.38 * gem_pulse(t))


def theme_atlas_frame(img, t: float) -> None:
    """The glow of the topaz warms the sheaf, the ribbon and the claws in the bow, and
    glints hop over the wheat."""
    p = gem_glow(t)
    px = img.load()
    for lane in WARMED:
        c0, w = LANES[lane]
        for r in range(ATLAS):
            y = Y_TOP - (r + 0.5) / RPU
            k = 0.4 * p * max(0.0, 1 - abs(y - BOW_Y) / 3.6)
            if k <= 0.01:
                continue
            for i in range(w):
                cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
                px[c0 + i, r] = rgba(mix(cur, AMBER[3], k))
    # while the metal rests, a golden shimmer runs down the wheat like wind over a field,
    # reaching the bow just as the next glint starts up the key
    if WIND[0] <= t < WIND[1]:
        centre = -6 + (t - WIND[0]) / (WIND[1] - WIND[0]) * (ATLAS + 8)
        for lane in ("wheat", "wheat2", "straw"):
            c0, w = LANES[lane]
            for r in range(max(0, int(centre) - 5), min(ATLAS, int(centre) + 6)):
                k = 0.9 * max(0.0, 1 - abs(r - centre) / 5.0) * (0.6 if lane == "straw" else 1.0)
                for i in range(w):
                    cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
                    px[c0 + i, r] = rgba(mix(cur, "#fff6c8", k * (1.0 if i == 0 else 0.8)))
    for lane, y, phase in GLINTS:
        amount = glint(t, phase)
        if amount <= 0:
            continue
        c0, w = LANES[lane]
        r = row_of(y)
        for dr, k in ((0, 0.95), (-1, 0.6), (1, 0.6), (-2, 0.25), (2, 0.25)):
            rr = r + dr
            if 0 <= rr < ATLAS:
                for i in range(w):
                    cur = "#%02x%02x%02x" % px[c0 + i, rr][:3]
                    px[c0 + i, rr] = rgba(mix(cur, "#fffbe8", amount * k * (1.0 if i == 0 else 0.75)))


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
    """The face of the topaz: a step cut (an octagonal table inside two rings of step
    facets), lit from the upper left, breathing brighter and throwing a star at its flare."""
    p = gem_glow(t)
    lift = 0.1 + 1.45 * p
    img = canvas(16)
    px = img.load()
    lx, ly = -0.8, -0.6
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
            if o > 7.0:                                        # girdle
                f = 0.6 + 0.5 * facing
            elif o > 5.2:                                      # outer steps
                f = 2.1 + 1.3 * facing
            elif o > 3.4:                                      # inner steps
                f = 3.1 - 1.0 * facing                         # steps catch the light on the far side
            else:                                              # the table
                f = 3.8 if dx * lx + dy * ly > 0.6 else 3.3
            px[tx, ty] = rgba(ramp_at(TOPAZ, f + lift * (1.0 if o <= 7.0 else 0.5)))
    for x, y in ((5, 5), (6, 5), (5, 6), (7, 4)):
        px[x, y] = rgba(TOPAZ[7])
    px[10, 11] = rgba(ramp_at(TOPAZ, 5.2 + lift))
    sparkle(img, 6, 6, clamp(gem_pulse(t) * 1.3 - 0.3), colour="#ffffff", reach=4)
    return img


# Golden chaff (window texels): x, starting y, speed (window heights per loop, rising),
# sway amplitude, sway phase, twinkle phase. Placed by hand so they never clump.
CHAFF = ((5, 26, 1, 1.0, 0.0, 0.1), (9, 8, 1, 1.4, 0.4, 0.55), (13, 18, 2, 0.8, 0.7, 0.3),
         (19, 3, 1, 1.2, 0.2, 0.8), (23, 22, 2, 1.0, 0.55, 0.05), (27, 12, 1, 1.3, 0.85, 0.45),
         (16, 29, 1, 0.9, 0.3, 0.65), (8, 15, 2, 1.1, 0.1, 0.9))


def window_frame(t: float):
    """Harvest-home light in the bow: deep wine at the rim warming to amber round the gem,
    the glow swelling and ebbing with the breath of the topaz, and golden chaff drifting up
    through it (each mote rises one or two whole window heights per loop)."""
    p = gem_glow(t)
    img = canvas(32)
    px = img.load()
    reach = 0.46 + 0.26 * p
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 16, (y + 0.5 - 16) / 16
            r = math.hypot(dx, dy)
            f = 5.6 * (1 - r) + 0.8 * (1 - (y / 31)) + 0.3     # warmer at the heart, a touch warmer on top
            c = WINE[int(clamp(dither_band(f, x, y), 0, len(WINE) - 1))]
            halo = (0.25 + 0.75 * p) * max(0.0, 1 - r / reach) ** 1.1
            if halo > 0.04:
                c = mix(c, AMBER[1] if halo < 0.45 else AMBER[2], min(0.92, halo * 1.3))
            px[x, y] = rgba(c)
    for x0, y0, speed, amp, sway, tw in CHAFF:
        fy = round((y0 - speed * 32 * t) % 32) % 32
        fx = round(x0 + amp * math.sin(2 * math.pi * (t + sway))) % 32
        bright = 0.5 + 0.5 * math.cos(2 * math.pi * (t - tw))
        cur = "#%02x%02x%02x" % px[fx, fy][:3]
        px[fx, fy] = rgba(mix(cur, GOLD[5], 0.45 + 0.5 * bright))
        if bright > 0.75:
            sparkle(img, fx, fy, (bright - 0.75) * 3, colour=GOLD[4], reach=1)
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
