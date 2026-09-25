"""December Key: the crimson-and-gold Christmas crate key (one of the 19 JoshyMC crate keys).

The bow is the set's stepped gold ring wreathed in holly: glossy leaves layered round its
evergreen band, clusters of scarlet berries, a string of fairy lights and a crimson ribbon
bow tied at its foot. Inside it a crimson velvet window frames a faceted gold star (a raised
ridge down every point) with a ruby clasped by five claws at its heart. A candy-cane striped
collar and grip wind up from the ring, a crimson ribbon tied with a sprig of holly girds the
gold shaft, the bit is a wrapped present (crimson paper sprinkled with gold stars, a gold
ribbon and bow) hung with three gold teeth, and a small gold star crowns the key like the
top of a tree.

Animation (one 3.2 s loop): a glossy glint sweeps up the whole key from the bow to the tip,
the ruby flares as it passes and the star shines, rays of gold light pulsing out of the
velvet between its points. All the while a twinkle runs clockwise round the string of
fairy lights (warm gold and warm white, lighting one after another) twice a loop, and the
whole string brightens with the ruby's flare. The crowning star's jewel flashes as the
glint reaches it.

---------------------------------------------------------------------------------------
KEY SET SKELETON: all 19 crate keys share these proportions (model units)
---------------------------------------------------------------------------------------
  frame   upright on x = 8, z = 8; front = +Z (the face the inventory shows)
  bow     a stepped ring centred at (8, BOW_Y = 3.5): outer radius 5.4 (10.8 across,
          y -1.9..8.9), inner radius 3.5; a recessed outer rim (0.5 wide, 2.0 deep), the
          main band (2.6 deep, z 6.7..9.3) and a raised inner lip (0.6 wide, 3.0 deep),
          20 segments each. Inside: a recessed window (the theme's animated scene), the
          theme's emblem, and at the heart a socket (bezel + claws) holding the theme's gem
  collar  two octagonal discs, y 8.4..10.2, joining the ring's top to the shaft
  shaft   octagonal, apothem 1.15 (2.3 thick), y 10..25.5, bands at y 13 and 17.6,
          a cap at 25.4..26.3; the theme may crown it with a finial (up to y ~29.5)
  bit     on the -X side of the upper shaft, y 18..25.2: a plate plus the theme's teeth
  grip    (8, 10.6, 8): the fist closes on the shaft just above the bow
  size    SIZE = 0.64 holds it at about 1 block; carried upright like a sceptre
  gui     GUI_ROTATION tilts the diagonal key back a little so its faces catch the light

Animation approach (every loop is 64 ticks, so all the pieces stay in step)
  metal   ONE 64 px atlas textures every gold face. Its ROWS map to the key's height
          (row = 2 * (29 - y)); its COLUMNS are LANES, each painted with one material's
          cross-section, and every face samples its lane at the rows of its own height, so ONE
          shine() band sweeping up the atlas glints along the whole key in one piece.
          32 frames x 2 ticks.
  gem     the ruby, interpolated (16 frames x 4 ticks): it flares as the glint crosses the bow
  window  the velvet behind the star (32 frames x 2 ticks): rays breathing with the ruby
  lights  the fairy lights (a chase round the wreath, twice a loop) and the finial jewel,
          interpolated (32 frames x 2 ticks)
  candy   the candy-cane collar and grip (32 frames x 2 ticks): a helix of stripes that
          the glint crosses at the same moment it crosses that height of the atlas
  holly   (static) the holly leaves and berries, painted sprites with cut-out edges
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, mirror, mix, model, place, prism, rgba, save,
                     save_animation, shine, sparkle, turn)

ID = "december_key"
NAME = "December Key"
KIND = "sword"
COUNTERPART = "item/trial_key"

# ======================================================================================
# 1. SHARED KEY SKELETON (copied from the pilot, january_key.py)
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
BIT_X0 = 4.0                                        # the bit plate's outer edge
BIT_X1 = AX - SHAFT_R + 0.35                        # bit parts run into the shaft up to here
GRIP = (8.0, 10.6, 8.0)
SIZE = 0.64
GUI_ROTATION = (-25, 20, -45)

# --- timing: every animation loops in 64 ticks ------------------------------------------
FRAMES, FRAMETIME = 32, 2          # atlas, window, lights, candy
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
    # theme (42-63): the star's lit and shaded facets and its ridge, crimson enamel and
    # ribbon, the present's wrapping paper
    "star_hi": (42, 3), "star_lo": (45, 3), "spine": (48, 2), "enamel": (50, 4), "panel": (54, 10),
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
    """The bezel behind the heart gem and its claws over the rim."""
    uv = {s: dot_uv("bezel", BOW_Y, 0.9) for s in ("north", "south", "east", "west")}
    uv["up"] = uv["down"] = dot_uv("bezel", BOW_Y, 0.5)       # the front and back faces
    parts = prism((AX, BOW_Y, AZ), SOCKET_R, 2.2, "metal", axis="z", cap="metal", uv=uv)
    for ang in claw_angles:
        parts.append(radial((AX, BOW_Y), ang, GEM_R - 0.25, SOCKET_R + 0.15, 0.5, 0.6, "band", z=9.3))
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


def glint(y: float, t: float, params=None) -> float:
    """How strongly the atlas's sweep (and its trailing twin) lights model height y at loop
    phase t, 0..~1: the same band shine() paints, for textures outside the atlas."""
    total = 0.0
    for p, phase in ((params or SWEEP, t), (TRAIL, (t - TRAIL_LAG) % 1.0)):
        w, run = p["width"], 1 - p["pause"]
        if phase >= run:
            continue
        centre = -ATLAS - w * 2 + (ATLAS + w * 4) * (phase / run)
        d = abs(-(Y_TOP - y) * RPU - centre)
        total += max(0.0, 1 - d / (w / 2 + 0.5)) * p["strength"]
        if params is not None:
            break
    return min(1.0, total)


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


def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


# ======================================================================================
# 2. THEME PALETTE: December, crimson (#C41E3A) and gold
# ======================================================================================
METAL = GOLD = ["#3b1109", "#6c2a0f", "#a05316", "#d08a22", "#eeb83d", "#fbe07c", "#fff8d6"]
CRIMSON = ["#2b0214", "#560722", "#8a0f2e", "#c41e3a", "#e2434f", "#f47a70", "#ffc0a8"]
RUBY = ["#2a0010", "#5e0520", "#970c2e", "#c41e3a", "#ee3a4c", "#ff7a78", "#ffc6b8", "#ffffff"]
HOLLY = ["#061a10", "#0c2d19", "#144525", "#1e6030", "#2a7c38", "#3f9a40", "#69b04f", "#a8d474"]
BERRY = ["#3a0410", "#76091a", "#b81220", "#e3302a", "#ff7c5e", "#ffd8c4"]
VELVET = ["#2e0312", "#4c081d", "#6c0e27", "#8e1531", "#b01d3a", "#cc2c46"]
CANDY_RED = ["#7a0a22", "#ac132e", "#d42840", "#f45c68"]
CANDY_WHITE = ["#d9b2ba", "#efd6da", "#fff3f0", "#ffffff"]
BULB = ["#3e1c0a", "#6e3a14", "#b0701f", "#eab050", "#ffe08e", "#fff5d6", "#ffffff"]          # warm gold
BULB_CREAM = ["#35261a", "#644e34", "#a2875c", "#dcc897", "#fff0c6", "#fffaea", "#ffffff"]  # warm white
BULB_RAMPS = (BULB, BULB_CREAM)                # the string alternates gold and warm white

# ======================================================================================
# 3. THEME PARTS
# ======================================================================================
STAR_ANGLES = tuple(90 + 72 * k for k in range(5))       # the star's points (one straight up)
NOTCH_ANGLES = tuple(a + 36 for a in STAR_ANGLES)          # the gaps between them
CLAW_ANGLES = STAR_ANGLES                                  # five claws, one under each ridge
BOW_STAR = dict(r_out=3.45, r_in=1.82, strip_w=0.66, strip_d=1.2, spine_w=0.58, spine_d=1.5, roll=18.0)
FINIAL_Y = 27.95
FINIAL_STAR = dict(r_out=1.68, r_in=0.88, strip_w=0.42, strip_d=0.9, spine_w=0.38, spine_d=1.1, roll=18.0)


def star_point(c, ang: float, r_out: float, r_in: float, strip_w: float, strip_d: float, spine_w: float,
               spine_d: float, roll: float, z: float = AZ, glow: int = 0) -> list[dict]:
    """One faceted point of a five-pointed star: two strips laid along its edges (the lit
    and the shaded facet, each tilted down toward its edge), a raised ridge down its middle
    and a turned-square tip. The inner vertices sit at r_in, 36 degrees either side."""
    cx, cy = c
    a = math.radians(ang)
    u = (math.cos(a), math.sin(a))
    tip = (cx + r_out * u[0], cy + r_out * u[1])
    parts = []
    for side in (1, -1):
        b = (cx + r_in * math.cos(a + side * math.radians(36)), cy + r_in * math.sin(a + side * math.radians(36)))
        ex, ey = tip[0] - b[0], tip[1] - b[1]
        length = math.hypot(ex, ey)
        e = (ex / length, ey / length)
        n = (e[1], -e[0]) if side > 0 else (-e[1], e[0])          # inward, toward the ridge
        alpha = math.acos(clamp(e[0] * u[0] + e[1] * u[1], -1.0, 1.0))
        stop = strip_w / math.sin(2 * alpha) * 1.02                # before it crosses the other edge
        off = (n[0] * strip_w / 2, n[1] * strip_w / 2)
        p0 = (b[0] - e[0] * 0.3 + off[0], b[1] - e[1] * 0.3 + off[1], z)
        p1 = (b[0] + e[0] * (length - stop) + off[0], b[1] + e[1] * (length - stop) + off[1], z)
        lane = "star_hi" if lit((-n[0], -n[1])) > 0.0 else "star_lo"
        parts.append(bar(p0, p1, strip_w, strip_d, "metal", roll=-side * roll,
                         faces=lane_faces(lane, p1[1], p0[1], flip=side < 0), glow=glow))
    end = r_out - spine_w / 2
    s0 = (cx + u[0] * r_in * 0.75, cy + u[1] * r_in * 0.75, z)
    s1 = (cx + u[0] * end, cy + u[1] * end, z)
    parts.append(bar(s0, s1, spine_w, spine_d, "metal", faces=lane_faces("spine", s1[1], s0[1]), glow=glow))
    parts.append(diamond((s1[0], s1[1]), spine_w / math.sqrt(2), spine_d - 0.15, "spine", ang - 45, z=z, glow=glow))
    return parts


def bow_star() -> list[dict]:
    """The gold star framed by the wreath, a point straight up under the collar."""
    parts = []
    for ang in STAR_ANGLES:
        parts += star_point((AX, BOW_Y), ang, **BOW_STAR, glow=9)
    return parts


def ruby_gem() -> list[dict]:
    """A glowing octagonal ruby: a body plus a painted front and back."""
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


# --- the wreath -------------------------------------------------------------------------
# Holly sprites in the "holly" texture (32 px, 2 px per uv unit): (u0, v0, u1, v1). Each
# leaf shape comes lit on its upper half (even index) and on its lower half (odd index).
LEAF_UV = ([0, 0, 8, 4.5], [0, 4.5, 8, 9], [8, 0, 16, 4.5], [8, 4.5, 16, 9])
BERRY_UV = ([0, 9, 2, 11], [2, 9, 4, 11])
SOCKET_UV = [4.25, 9.25, 4.75, 9.75]
LEAF_L, LEAF_W, LEAF_T = 2.75, 1.55, 0.12
WREATH_Z = 9.72
LEAVES = [(108 + 36 * k, (k % 2) * 2 - 1) for k in range(10)]      # (angle, alternate +1/-1)
BERRY_CLUSTERS = (54, 126, 198, 342)                               # the ribbon bow sits at 270
BULB_ANGLES = tuple(72 - 36 * k for k in range(10))                 # clockwise from the top
BULB_W, BULB_L = 0.9, 1.2


def holly_leaf(c, z: float, ang: float, tilt: float, shape: int, length: float = LEAF_L,
               width: float = LEAF_W) -> dict:
    """A holly leaf: a thin plate showing a cut-out sprite front and back, tilted `tilt`
    degrees about its midrib, pointing along `ang`. The sprite is picked so its lit half
    faces the painted light."""
    cx, cy = c
    a = math.radians(ang)
    upper_lit = lit((-math.sin(a), math.cos(a))) >= 0
    u0, v0, u1, v1 = LEAF_UV[2 * shape + (0 if upper_lit else 1)]
    e = box((cx - length / 2, cy - width / 2, z - LEAF_T / 2), (cx + length / 2, cy + width / 2, z + LEAF_T / 2),
            "holly", faces={"south": ("holly", [u0, v0, u1, v1]), "north": ("holly", [u1, v0, u0, v1])},
            skip=("east", "west", "up", "down"))
    turn(e, origin=(cx, cy, z), x=tilt)
    turn(e, ang, "z", (cx, cy, z))
    return e


def berry(x: float, y: float, z: float, cell: int = 0, s: float = 0.62) -> dict:
    h = s / 2
    uv = BERRY_UV[cell]
    return box((x - h, y - h, z - h), (x + h, y + h, z + h), "holly", uv={side: uv for side in
                                                                           ("north", "south", "east", "west",
                                                                            "up", "down")})


def light_uv(cell: int, core: bool = False) -> list[float]:
    """uv of one 4x4 px cell of the lights texture (32 px, 8 cells a row); core picks its
    two opaque middle columns, for the sides of a bulb."""
    col, row = cell % 8, cell // 8
    if core:
        return [col * 2 + 0.5, row * 2, col * 2 + 1.5, row * 2 + 2]
    return [col * 2, row * 2, col * 2 + 2, row * 2 + 2]


def bulb(x: float, y: float, z: float, cell: int, ang: float) -> list[dict]:
    """A fairy light: a rounded glass bulb pointing along `ang` out of a dark socket. Its
    front and back show the round bulb of its cell of the animated lights texture, its
    sides the cell's solid core, so the glass never shows holes edge-on."""
    w, length, d = BULB_W, BULB_L, BULB_W * 0.8
    uv, core = light_uv(cell), light_uv(cell, True)
    top = light_uv(cell, True)
    top = [top[0], top[1] + 0.5, top[2], top[1] + 1.0]
    glass = box((x - w / 2, y - length / 2, z - d / 2), (x + w / 2, y + length / 2, z + d / 2), "lights",
                uv={"south": uv, "north": [uv[2], uv[1], uv[0], uv[3]], "east": core, "west": core,
                    "up": top, "down": top}, glow=15)
    s = 0.26
    socket = box((x - s, y - length / 2 - 0.26, z - s), (x + s, y - length / 2 + 0.12, z + s), "holly",
                 uv={side: SOCKET_UV for side in ("north", "south", "east", "west", "up", "down")})
    return turn([glass, socket], ang - 90, "z", (x, y, z))


def polar(r: float, ang: float, c=(AX, BOW_Y)):
    a = math.radians(ang)
    return c[0] + r * math.cos(a), c[1] + r * math.sin(a)


def wreath_front() -> list[dict]:
    """Holly round the front of the band (each leaf following the ring clockwise, tipped
    alternately out over the rim and in over the lip), berries, and the fairy lights."""
    parts = []
    for k, (ang, alt) in enumerate(LEAVES):
        r = BAND_R + 0.18 * alt
        direction = ang - 90 + 20 * alt
        parts.append(holly_leaf(polar(r, ang), WREATH_Z + 0.05 * (k % 3), direction, 14 * alt, k % 2))
    for ang in BERRY_CLUSTERS:
        for dr, da, dz, cell in ((0.28, -6, 0.3, 0), (-0.3, 0, 0.36, 1), (0.28, 6, 0.42, 0)):
            x, y = polar(BAND_R - 0.1 + dr, ang + da)
            parts.append(berry(x, y, WREATH_Z + dz, cell))
    for k, ang in enumerate(BULB_ANGLES):
        x, y = polar(BOW_OUT - 0.55, ang + 5)
        parts += bulb(x, y, WREATH_Z + 0.36, k, ang + 5)
    return parts


def wreath_bow() -> list[dict]:
    """A crimson ribbon bow tied at the foot of the wreath: two loops, a knot and two
    tails hanging below the ring."""
    z = WREATH_Z + 0.35
    kx, ky = AX, BOW_Y - BAND_R - 0.1
    parts = []
    for side in (-1, 1):                                        # tails, behind the loops
        p0 = (kx + side * 0.25, ky - 0.1, z - 0.15)
        p1 = (kx + side * 0.95, ky - 1.95, z - 0.15)
        parts.append(bar(p0, p1, 0.72, 0.36, "metal", faces=lane_faces("enamel", p1[1], p0[1], flip=side > 0)))
    for side in (-1, 1):                                        # loops
        p0 = (kx + side * 0.2, ky - 0.05, z)
        p1 = (kx + side * 1.95, ky + 0.6, z)
        parts.append(bar(p0, p1, 1.05, 0.5, "metal", roll=side * 15,
                         faces=lane_faces("enamel", p1[1], p0[1], flip=side > 0)))
    parts.append(box((kx - 0.42, ky - 0.42, z - 0.32), (kx + 0.42, ky + 0.4, z + 0.36), "metal",
                     faces=lane_faces("enamel", ky + 0.4, ky - 0.42)))
    return parts


# --- candy cane collar and grip -----------------------------------------------------------
CANDY_TOP = 12.8        # the candy texture's v = 0 is at this height
CANDY_K = 2.0           # uv units per model unit (4 px per unit on the 32 px texture)
CANDY_PERIOD = 4.0      # uv units: the stripes repeat every 8 px along u and v
CANDY_WHITE_UV = [1.4, 1.4, 1.6, 1.6]


def candy_prism(y0: float, y1: float, r: float) -> list[dict]:
    """An octagonal candy-cane section: every facet samples the stripes shifted by its
    place round the prism, so they wind round it in one continuous helix."""
    half = r * math.tan(math.pi / 8)
    wu = 2 * half * CANDY_K
    v0, v1 = (CANDY_TOP - y1) * CANDY_K, (CANDY_TOP - y0) * CANDY_K

    def uv(k):
        u0 = (k * wu) % CANDY_PERIOD
        return [round(u0, 4), round(v0, 4), round(u0 + wu, 4), round(v1, 4)]

    cy = (y0 + y1) / 2
    parts = []
    # facet order round the prism in the direction u runs, seam at the back
    for a, (s, e, n, w) in ((0, (4, 6, 0, 2)), (45, (5, 7, 1, 3))):
        s1 = box((AX - r, y0, AZ - half), (AX + r, y1, AZ + half), "candy",
                 faces={"east": ("candy", uv(e)), "west": ("candy", uv(w)), "up": ("candy", CANDY_WHITE_UV),
                        "down": ("candy", CANDY_WHITE_UV)}, skip=("north", "south"))
        s2 = box((AX - half, y0, AZ - r), (AX + half, y1, AZ + r), "candy",
                 faces={"south": ("candy", uv(s)), "north": ("candy", uv(n)), "up": ("candy", CANDY_WHITE_UV),
                        "down": ("candy", CANDY_WHITE_UV)}, skip=("east", "west"))
        if a:
            turn([s1, s2], a, "y", (AX, cy, AZ))
        parts += [s1, s2]
    return parts


def candy_collar() -> list[dict]:
    """The collar's two discs and a sleeve over the grip, striped like a candy cane."""
    parts = []
    for y0, y1, r in COLLAR:
        parts += candy_prism(y0, y1, r)
    parts += candy_prism(COLLAR[-1][1] - 0.05, BANDS[0][0] + 0.05, SHAFT_R + 0.09)
    return parts


# --- along the shaft ----------------------------------------------------------------------
RIBBON_Y = (14.7, 15.4)


def shaft_sprig() -> list[dict]:
    """A crimson ribbon tied round the shaft with a sprig of holly and three berries."""
    parts = lane_prism(RIBBON_Y[0], RIBBON_Y[1], 1.36, "enamel", cap="enamel")
    zf = AZ + SHAFT_R + 0.2
    parts.append(holly_leaf((9.85, 15.75), zf, 32, 14, 0, 2.4, 1.35))
    parts.append(holly_leaf((6.3, 15.9), zf - 0.05, 150, -14, 1, 2.0, 1.13))
    for x, y, dz, cell in ((7.72, 15.28, 0.3, 0), (8.34, 15.5, 0.36, 1), (8.1, 14.82, 0.42, 0)):
        parts.append(berry(x, y, zf + dz, cell, 0.56))
    return parts


# --- the bit: a wrapped present -----------------------------------------------------------
GIFT_Y0, GIFT_Y1, GIFT_D = 21.0, 24.4, 2.4
RIBBON_X = (5.3, 6.0)              # the ribbon running up the present
RIBBON_H = (22.35, 23.05)          # and the one running round it
TEETH = ((4.55, 2.7, 0.82), (5.65, 1.45, 0.7), (6.62, 2.1, 0.78))    # (x, length, width)


def gift_bit() -> list[dict]:
    """The bit: a present wrapped in crimson paper with a gold ribbon both ways, a bow on
    top and three gold teeth hanging below it."""
    y0, y1, d = GIFT_Y0, GIFT_Y1, GIFT_D
    faces = {"south": ("metal", panel_uv(BIT_X0, BIT_X1, y1, y0)),
             "north": ("metal", panel_uv(BIT_X0, BIT_X1, y1, y0, True)),
             "west": ("metal", lane_uv("enamel", y1, y0, True)),
             "up": ("metal", dot_uv("enamel", y1, 0.2)), "down": ("metal", dot_uv("enamel", y0, 0.9))}
    parts = [box((BIT_X0, y0, AZ - d / 2), (BIT_X1, y1, AZ + d / 2), "metal", faces=faces, skip=("east",))]
    x0, x1 = RIBBON_X
    parts.append(box((x0, y0 - 0.14, AZ - d / 2 - 0.14), (x1, y1 + 0.14, AZ + d / 2 + 0.14), "metal",
                     faces=lane_faces("band", y1 + 0.14, y0 - 0.14)))
    h0, h1 = RIBBON_H
    parts.append(box((BIT_X0 - 0.12, h0, AZ - d / 2 - 0.1), (BIT_X1, h1, AZ + d / 2 + 0.1), "metal",
                     faces=lane_faces("band", h1, h0), skip=("east",)))
    # the bow: two loops like butterfly wings, tipped up, and a knot
    kx, ky = (x0 + x1) / 2, y1 + 0.34
    for side in (-1, 1):
        parts.append(diamond((kx + side * 0.6, ky + 0.14), 0.8, 0.58, "band", 45 + side * 16))
    parts.append(box((kx - 0.28, ky - 0.24, AZ - 0.38), (kx + 0.28, ky + 0.28, AZ + 0.38), "metal",
                     faces=lane_faces("cap", ky + 0.28, ky - 0.24)))
    for x, length, w in TEETH:
        parts += gold_tooth(x, y0 + 0.05, length, w)
    return parts


def gold_tooth(x: float, y_top: float, length: float, w: float, d: float = 1.1) -> list[dict]:
    """A key tooth: a bevelled gold bar ending in a turned-square point."""
    end = y_top - length + w / 2
    parts = [box((x - w / 2, end, AZ - d / 2), (x + w / 2, y_top, AZ + d / 2), "metal",
                 faces=lane_faces("shaft", y_top, end))]
    parts.append(diamond((x, end), w / math.sqrt(2), d - 0.2, "band"))
    return parts


# --- the crown: a small gold star on the tip ------------------------------------------
def star_finial() -> list[dict]:
    """A small star crowning the shaft like the top of a tree, a jewel at its heart."""
    parts = lane_prism(CAP[1] - 0.1, FINIAL_Y, 0.55, "shaft")
    for ang in STAR_ANGLES:
        parts += star_point((AX, FINIAL_Y), ang, **FINIAL_STAR, glow=4)
    uv = {s: dot_uv("bezel", FINIAL_Y, 0.9) for s in ("north", "south", "east", "west")}
    uv["up"] = uv["down"] = dot_uv("bezel", FINIAL_Y, 0.5)
    parts += prism((AX, FINIAL_Y, AZ), 0.66, 1.3, "metal", axis="z", cap="metal", uv=uv)
    j = 0.42
    parts.append(box((AX - j, FINIAL_Y - j, AZ - 0.78), (AX + j, FINIAL_Y + j, AZ + 0.78), "lights",
                     uv={s: light_uv(FINIAL_CELL) for s in ("north", "south", "east", "west", "up", "down")},
                     glow=15))
    return parts


FINIAL_CELL = 10


def skeleton() -> list[dict]:
    """The set's ring, window, socket and shaft (the collar is the candy one below)."""
    return bow_ring() + bow_window() + gem_socket(CLAW_ANGLES) + shaft()


def theme() -> list[dict]:
    front = wreath_front()
    back = mirror(front, "z", AZ)
    return (bow_star() + ruby_gem() + front + back + wreath_bow() + candy_collar() + shaft_sprig()
            + gift_bit() + star_finial())


# ======================================================================================
# 4. TEXTURES
# ======================================================================================

def paint_theme_lanes(img) -> None:
    # the band fronts become the wreath's evergreen bed, in three light variants
    for lane, tones in (("face0", (1, 2, 1)), ("face1", (2, 3, 2)), ("face2", (3, 4, 2))):
        paint_lane(img, lane, [HOLLY[i] for i in tones])
    px = img.load()
    rng = random.Random(12)
    for lane in FACE_LANES:
        c, w = LANES[lane]
        for r in range(ATLAS):
            for i in range(w):
                roll = rng.random()
                if roll < 0.1:
                    px[c + i, r] = rgba(HOLLY[4])
                elif roll < 0.15:
                    px[c + i, r] = rgba(HOLLY[0])
    paint_lane(img, "star_hi", [GOLD[5], GOLD[5], GOLD[4]])
    paint_lane(img, "star_lo", [GOLD[2], GOLD[3], GOLD[3]])
    paint_lane(img, "spine", [GOLD[6], GOLD[5]])
    paint_lane(img, "enamel", [CRIMSON[5], CRIMSON[4], CRIMSON[3], CRIMSON[2]])
    # the present's wrapping paper: crimson lit from the -X side and along its top edge,
    # darker at its foot and far edge, sprinkled with little gold stars clear of the ribbons
    c0, w = LANES["panel"]
    r0, r1 = row_of(GIFT_Y1), row_of(GIFT_Y0) - 1
    for r in range(r0, r1 + 1):
        for i in range(w):
            f = 3.0 + (0.45 if i < 2 else 0.0) - (0.5 if i >= w - 2 else 0.0)
            if r == r0:
                f += 0.9
            elif r == r1:
                f -= 0.9
            px[c0 + i, r] = rgba(CRIMSON[int(clamp(dither_band(f, i, r), 0, len(CRIMSON) - 1))])
    for i, j in ((1, 1), (8, 1), (2, 5), (8, 5), (0, 3)):
        if r0 + j <= r1:
            px[c0 + i, r0 + j] = rgba(GOLD[5])
    for i, j in ((1, 2), (8, 6)):
        if r0 + j <= r1:
            px[c0 + i, r0 + j] = rgba(mix(CRIMSON[3], GOLD[3], 0.5))


def theme_atlas_frame(img, t: float) -> None:
    """The star shines: as the ruby flares, the star's facets flood with light from the
    heart outward."""
    p = gem_pulse(t)
    if p < 0.03:
        return
    px = img.load()
    for lane in ("star_hi", "star_lo", "spine"):
        c0, w = LANES[lane]
        for r in range(ATLAS):
            y = Y_TOP - (r + 0.5) / RPU
            k = 0.5 * p * max(0.0, 1 - abs(y - BOW_Y) / 4.0)
            if k <= 0.01:
                continue
            for i in range(w):
                cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
                px[c0 + i, r] = rgba(mix(cur, "#fff8d6", k))


def paint_atlas_base():
    img = canvas(ATLAS, fill=METAL[3])
    paint_skeleton_lanes(img, METAL)
    paint_theme_lanes(img)
    return img


def gem_frame(t: float):
    """The ruby's face: an octagon with a table facet, crown facets lit from the left,
    a white highlight, brightening and throwing a star at its flare."""
    p = gem_pulse(t)
    lift = 0.2 + 1.5 * p
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
            px[tx, ty] = rgba(ramp_at(RUBY, f + lift * (1.0 if o <= 7.0 else 0.6)))
    for x, y in ((5, 5), (6, 5), (5, 6)):
        px[x, y] = rgba(RUBY[7])
    px[10, 10] = rgba(ramp_at(RUBY, 5 + lift))
    sparkle(img, 6, 6, clamp(p * 1.3 - 0.25), colour="#ffffff", reach=4)
    return img


def window_frame(t: float):
    """Crimson velvet behind the star: a vignette, a warm halo round the ruby breathing
    with it, and rays of gold light out of the gaps between the points, pulsing outward;
    a few gold motes twinkle."""
    p = gem_pulse(t)
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 16, (16 - y - 0.5) / 16
            r = math.hypot(dx, dy)
            ang = math.degrees(math.atan2(dy, dx))
            f = 3.6 - 3.2 * r ** 1.4
            c = VELVET[int(clamp(dither_band(f, x, y), 0, len(VELVET) - 1))]
            ray = 0.0
            for na in NOTCH_ANGLES:
                d = abs((ang - na + 180) % 360 - 180)
                width = 7 + 9 * r
                if d < width:
                    ray = max(ray, (1 - d / width) ** 1.3)
            if ray > 0:
                wave_out = 0.55 + 0.45 * math.cos(2 * math.pi * (2.5 * r - 2 * t))
                k = ray * clamp(1.15 - r) * (0.1 + 0.9 * p) * (0.6 + 0.4 * wave_out)
                if k > 0.04:
                    c = mix(c, GOLD[5], min(0.9, k * 1.5))
            halo = (0.2 + 0.8 * p) * max(0.0, 1 - r / 0.62) ** 1.5
            if halo > 0.05:
                c = mix(c, GOLD[4], min(0.8, halo))
            px[x, y] = rgba(c)
    for i, na in enumerate(NOTCH_ANGLES):             # a gold mote in each gap, in turn
        a = math.radians(na)
        sx, sy = 16 + 11.5 * math.cos(a), 16 - 11.5 * math.sin(a)
        amount = clamp(1.4 * (0.5 - 0.5 * math.cos(2 * math.pi * (t - i / 5))) - 0.4)
        sparkle(img, int(sx), int(sy), amount, colour=GOLD[6], reach=1)
    return img


# --- fairy lights -------------------------------------------------------------------------
CHASES = 2                        # the twinkle runs round the wreath twice a loop
DIM = 0.22                        # an unlit bulb still glows faintly in its colour


def bulb_level(k: int, t: float) -> float:
    """0..1: fairy light k's brightness at loop phase t. A twinkle runs clockwise round
    the wreath (bulbs switch on one after another, each fading as the next lights), twice
    a loop, over a faint steady glow; as the ruby flares the whole string brightens."""
    n = len(BULB_ANGLES)
    x = (CHASES * t - k / n) % 1.0                 # time since the twinkle reached bulb k
    rise = 0.06
    if x > 1 - rise:
        head = 0.5 - 0.5 * math.cos(math.pi * (x - (1 - rise)) / rise)
    else:
        head = math.exp(-x / 0.16)
    return clamp(DIM + (1 - DIM) * head + 0.25 * gem_pulse(t))


def finial_level(t: float) -> float:
    """The crowning star's jewel: a flash as the glint reaches it, then a slow glow down."""
    x = (t - sweep_time(FINIAL_Y) - 0.01) % 1.0
    if x > 0.96:
        return 0.35 + 0.65 * (x - 0.96) / 0.04
    return 0.35 + 0.65 * math.exp(-x / 0.12)


def paint_bulb(img, cell: int, level: float, ramp=BULB) -> None:
    """A round 4x4 bulb: a glint top-left, body, shade bottom-right. Unlit, it is dark
    tinted glass with a pale glint; lit, it runs up its colour to a white-hot core."""
    px = img.load()
    col, row = cell % 8, cell // 8
    f = 1.0 + level * (len(ramp) - 2.4)
    hi, mid, lo = ramp_at(ramp, f + 2.2), ramp_at(ramp, f), ramp_at(ramp, f - 0.9)
    stamp = [".hm.", "hmml", "mmll", ".ll."]
    for j, line in enumerate(stamp):
        for i, ch in enumerate(line):
            if ch != ".":
                px[col * 4 + i, row * 4 + j] = rgba({"h": hi, "m": mid, "l": lo}[ch])


def lights_frame(t: float):
    img = canvas(32)
    for k in range(len(BULB_ANGLES)):
        paint_bulb(img, k, bulb_level(k, t), BULB_RAMPS[k % len(BULB_RAMPS)])
    paint_bulb(img, FINIAL_CELL, finial_level(t))
    return img


# --- candy cane ---------------------------------------------------------------------------

def candy_frame(t: float):
    """Diagonal red and white stripes (they wind round each candy prism as a helix), the
    glint crossing them as it passes their height."""
    img = canvas(32)
    px = img.load()
    for py in range(32):
        vv = (py + 0.5) / 2
        g = glint(CANDY_TOP - vv / CANDY_K, t)
        for pxx in range(32):
            uu = (pxx + 0.5) / 2
            f = ((uu + vv) / CANDY_PERIOD) % 1.0
            if f < 0.5:
                c = CANDY_RED[3] if f < 0.1 else (CANDY_RED[1] if f > 0.4 else CANDY_RED[2])
            else:
                c = CANDY_WHITE[3] if f < 0.6 else (CANDY_WHITE[1] if f > 0.9 else CANDY_WHITE[2])
            if g > 0.02:
                c = mix(c, "#ffffff", g * 0.8)
            px[pxx, py] = rgba(c)
    return img


# --- holly --------------------------------------------------------------------------------
# Leaves are 16 x 9 px, stem on the left, tip on the right: two deep-scalloped spiny lobes
# a side and a spine at the tip. The upper half catches the light, the lower half is in
# shade, a paler midrib runs down the middle; each shape is also painted flipped (lit
# from below) for leaves whose upper side faces away from the light.
LEAF_SPRITES = (
    ["....s....s......",
     "...lhL..lhL.....",
     "..lLLL.lLLLL.s..",
     ".lLLLLlLLLLLlLl.",
     "kMMMMMMMMMMMmmms",
     ".dddddddddddddd.",
     "..dDDo.dDDDo.o..",
     "...Doo..DDo.....",
     "....o....o......"],
    ["...s.....s......",
     "..lhL...lhL...s.",
     ".lLLLl.lLLLLl.l.",
     ".lLLLLLLLLLLLLl.",
     "kMMMMMMMMMMMmmms",
     ".dddddddddddddd.",
     ".dDDDo.oDDDDo.o.",
     "..oDo...oDo...o.",
     "...o.....o......"],
)
LEAF_PAL = {"k": HOLLY[0], "o": HOLLY[1], "D": HOLLY[2], "d": HOLLY[3], "l": HOLLY[4], "L": HOLLY[5],
            "h": "#55a146", "M": "#4f9140", "m": "#3d7836", "s": "#76b152"}
BERRY_SPRITES = (
    [".bb.", "bhab", "baad", ".dd."],
    [".bb.", "bhbb", "bbad", ".dd."],
)
BERRY_PAL = {"h": BERRY[5], "a": BERRY[3], "b": BERRY[2], "d": BERRY[1]}


def paint_holly():
    img = canvas(32)
    px = img.load()
    for k, (u0, v0, _u1, _v1) in enumerate(LEAF_UV):
        rows = LEAF_SPRITES[k // 2]
        if k % 2:                                      # lit from below: flipped top to bottom
            rows = list(reversed(rows))
        for j, line in enumerate(rows):
            for i, ch in enumerate(line):
                if ch != ".":
                    px[int(u0 * 2) + i, int(v0 * 2) + j] = rgba(LEAF_PAL[ch])
    for k, (u0, v0, _u1, _v1) in enumerate(BERRY_UV):
        for j, line in enumerate(BERRY_SPRITES[k]):
            for i, ch in enumerate(line):
                if ch != ".":
                    px[int(u0 * 2) + i, int(v0 * 2) + j] = rgba(BERRY_PAL[ch])
    sx, sy = int(SOCKET_UV[0] * 2), int(SOCKET_UV[1] * 2)       # the fairy lights' sockets
    for (i, j), c in (((0, 0), HOLLY[3]), ((1, 0), HOLLY[2]), ((0, 1), HOLLY[2]), ((1, 1), HOLLY[1])):
        px[sx + i, sy + j] = rgba(c)
    return img


def textures() -> None:
    base = paint_atlas_base()
    save_animation(animate(lambda t: atlas_frame(base, t), FRAMES), "metal", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(window_frame, FRAMES), "window", frametime=FRAMETIME)
    save_animation(animate(lights_frame, FRAMES), "lights", frametime=FRAMETIME, interpolate=True)
    save_animation(animate(candy_frame, FRAMES), "candy", frametime=FRAMETIME)
    save(paint_holly(), "holly")


# ======================================================================================
# 5. MODELS
# ======================================================================================

def models() -> dict:
    parts = skeleton() + theme()
    return {"main": model(parts, transforms(parts))}
