"""February Key: the rose-gold crate key of love (one of the 19 JoshyMC crate keys).

The bow is a heart: the set's stepped ring (a recessed outer rim, a polished band and a raised
inner lip) bent into a heart of rose gold, a line of pink enamel inlaid all round its band and
set with pink pearls, a bead at its point, the shaft rising out of its cleft. Its window is a
little heart of dark plum velvet where tiny hearts twinkle, and at its centre a heart-cut ruby
sits in a heart-shaped bezel held by six claws. The collar, bands and cap carry pink enamel
stripes, two tiny ruby hearts are inlaid on the shaft and a pink satin ribbon is tied in a bow
round it. The bit is cupid's arrow shot through the shaft: its fletching on the -X side, two
feathered vanes (deep pink at the quill, white at the tips) whose stepped barbs are the teeth,
with a rose-gold nock, and its heart-shaped head poking out on +X. A small ruby heart crowns
the tip. In inventories the heart turns 45 degrees on the shaft so it stands upright in the
diagonal icon.

Animation (one 3.2 s loop): the glossy glint sweeps up the key from the bow to the tip as on
every key in the set; the ruby beats twice a loop (lub-dub), swelling and flaring on each beat,
sending a rosy ripple through the window and a pulse of light up the enamel stripes, while
tiny hearts rise and twinkle in the window and the pearls round the heart glint in turn.

The skeleton (section 1) is the set's, copied from january_key.py: the same stepped ring
profile, size and depth (here traced round a heart instead of a circle), collar, shaft, bands,
cap, bit span, grip, transforms and the 64-tick metal atlas with its bow-to-tip glint.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, mix, model, place, prism, rgba, save_animation, shine,
                     sparkle, turn)

ID = "february_key"
NAME = "February Key"
KIND = "sword"
COUNTERPART = "item/trial_key"

# ======================================================================================
# 1. SHARED KEY SKELETON (the set's proportions)
# ======================================================================================
AX, AZ = 8.0, 8.0                 # the key's axis; front = +Z
BOW_Y = 3.5                       # centre of the bow
BOW_OUT, BOW_IN = 5.4, 3.5        # the set's ring radii: the ring is 1.9 wide
BOW_D = 2.6                       # main band depth: z 6.7..9.3
RIM_W, RIM_D = 0.5, 2.0           # recessed outer rim
LIP_W, LIP_D = 0.6, 3.0           # raised inner lip (0.2 proud of the band front and back)
RING_W = BOW_OUT - BOW_IN
BAND_W = RING_W - RIM_W - LIP_W + 0.1
WINDOW_D = 0.5                    # recessed: z 7.75..8.25
SHAFT_R = 1.15                    # octagonal shaft apothem (2.3 thick)
SHAFT_Y0, SHAFT_Y1 = 10.0, 25.5
COLLAR = ((8.4, 9.4, 2.05), (9.4, 10.2, 1.6))       # (y0, y1, apothem), bottom to top
BANDS = ((12.6, 13.4, 1.5), (17.2, 18.0, 1.55))     # rings round the shaft
CAP = (25.4, 26.3, 1.5)                             # the shaft's top cap
BIT_Y0, BIT_Y1 = 18.0, 25.2                         # the bit's span on the -X side
BIT_X0 = 4.0                                        # the bit's outer edge
BIT_X1 = AX - SHAFT_R + 0.35                        # bit parts run into the shaft up to here
GRIP = (8.0, 10.6, 8.0)
SIZE = 0.64
GUI_ROTATION = (-25, 20, -45)

# --- timing: every animation loops in 64 ticks ------------------------------------------
FRAMES, FRAMETIME = 32, 2          # atlas and window
GEM_FRAMES, GEM_FRAMETIME = 32, 2  # gem (interpolated): a lub-dub needs 0.1 s steps

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
    "enamel": (42, 4), "pearl": (46, 3), "pearl2": (49, 3), "satin": (52, 3), "fletch": (55, 3),
    "fletch2": (58, 3),
}
FACE_LANES = ("face0", "face1", "face2")
RIM_LANES = ("rim0", "rim1", "rim2")
LIP_LANES = ("lip2", "lip1", "lip0")      # the lip's inner edge faces the opposite way
SWEEP = dict(colour="#fff3ec", width=8.0, strength=0.86, angle=-90.0, pause=0.4)   # bow -> tip
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
    of its own height, so the sweep reaches every face as it passes that height."""
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
    front plane), w wide and d deep. Its lane's first column lands on the lit edge."""
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


def lane_prism(y0: float, y1: float, r: float, lane: str, cap: str = "cap", x: float = AX, z: float = AZ,
               **kw) -> list[dict]:
    """An octagonal rod along Y whose facets sample `lane` at their height."""
    side = lane_uv(lane, y1, y0)
    back = lane_uv(lane, y1, y0, True)
    uv = {"south": side, "north": back, "east": side, "west": back,
          "up": dot_uv(cap, y1), "down": dot_uv(cap, y0)}
    return prism((x, (y0 + y1) / 2, z), r, y1 - y0, "metal", cap="metal", uv=uv, **kw)


# --- the heart ----------------------------------------------------------------------------
# Two lobes of radius HEART_R centred HEART_A either side of the axis at height HEART_YC,
# joined by edges tangent to them to a point at HEART_YP: 10.8 across and 11.2 tall, over the
# span of the set's ring. Each layer of the ring follows the outline offset inward by d
# (d = 0 is the silhouette), so the stepped profile runs unbroken round the heart.
HEART_A, HEART_R, HEART_YC, HEART_YP = 2.45, 2.95, 5.95, -2.3


def _heart_frame():
    dy = HEART_YC - HEART_YP
    phi = math.atan2(dy, HEART_A) - math.asin(HEART_R / math.hypot(HEART_A, dy))
    cleft = HEART_YC + math.sqrt(HEART_R ** 2 - HEART_A ** 2)
    return phi, phi - math.pi / 2, math.atan2(cleft - HEART_YC, -HEART_A), cleft


PHI, LOBE_A0, LOBE_A1, CLEFT_Y = _heart_frame()   # edge direction, lobe arc span, the cleft's height
T0 = (HEART_A + HEART_R * math.cos(LOBE_A0), HEART_YC + HEART_R * math.sin(LOBE_A0))
ARC_STEP = math.radians(20)       # the lobes are cut into bars of at most 20 degrees


def heart_half(d: float, step: float = 1.1) -> list[tuple[float, float]]:
    """The outline offset inward by d as points (x from the axis, y): from the point up the right
    edge and over the right lobe to the bottom of the cleft (both ends on the axis)."""
    tip = (0.0, HEART_YP + d / math.cos(PHI))
    r = HEART_R - d
    t0 = (HEART_A + r * math.cos(LOBE_A0), HEART_YC + r * math.sin(LOBE_A0))
    n = max(1, round(math.dist(tip, t0) / (1.6 * step)))
    pts = [(tip[0] + (t0[0] - tip[0]) * i / n, tip[1] + (t0[1] - tip[1]) * i / n) for i in range(n)]
    span = LOBE_A1 - LOBE_A0
    n = max(math.ceil(span / ARC_STEP), round(r * span / step))
    for i in range(n):
        a = LOBE_A0 + span * i / n
        pts.append((HEART_A + r * math.cos(a), HEART_YC + r * math.sin(a)))
    if d > 0.05:                      # the cleft's inside corner rounds off
        b0, b1 = LOBE_A1 - math.pi, -math.pi / 2
        n = max(1, math.ceil(abs(b1 - b0) / ARC_STEP))
        pts += [(d * math.cos(b0 + (b1 - b0) * i / n), CLEFT_Y + d * math.sin(b0 + (b1 - b0) * i / n))
                for i in range(n)]
    pts.append((0.0, CLEFT_Y - d))
    return pts


def heart_loop(d: float, step: float = 1.1) -> list[tuple[float, float]]:
    """The whole offset outline, counter-clockwise from the point back to it."""
    right = heart_half(d, step)
    return right + [(-x, y) for x, y in reversed(right)][1:]


def _seg_dist(p, a, b) -> float:
    vx, vy = b[0] - a[0], b[1] - a[1]
    k = clamp(((p[0] - a[0]) * vx + (p[1] - a[1]) * vy) / (vx * vx + vy * vy))
    return math.hypot(p[0] - a[0] - k * vx, p[1] - a[1] - k * vy)


def heart_inside(x: float, y: float) -> bool:
    """Is model point (x, y) inside the heart's silhouette?"""
    px = abs(x - AX)
    if (px - HEART_A) ** 2 + (y - HEART_YC) ** 2 <= HEART_R ** 2:
        return True
    if HEART_YP <= y <= T0[1]:
        return px <= (y - HEART_YP) / math.tan(PHI)
    return T0[1] < y <= HEART_YC and px <= HEART_A


def heart_depth(x: float, y: float) -> float:
    """How far model point (x, y) lies inside the silhouette (negative outside)."""
    best = 1e9
    for s in (1, -1):
        p = (s * (x - AX), y)
        best = min(best, _seg_dist(p, (0.0, HEART_YP), T0))
        ang = math.atan2(p[1] - HEART_YC, p[0] - HEART_A)
        while ang < LOBE_A0:
            ang += 2 * math.pi
        if ang <= LOBE_A1:
            best = min(best, abs(math.hypot(p[0] - HEART_A, p[1] - HEART_YC) - HEART_R))
        else:
            best = min(best, math.dist(p, T0), math.hypot(p[0], p[1] - CLEFT_Y))
    return best if heart_inside(x, y) else -best


def ring_segment(p0, p1, w: float, depth: float, lanes, east: str, west: str, z: float = AZ,
                 stretch: float = 1.08, **kw) -> dict:
    """A bar of the ring from p0 to p1 (x from the axis). The loops run counter-clockwise, so
    its local +X side (east) faces out of the heart; lanes may be a tuple picked by light."""
    (x0, y0), (x1, y1) = p0, p1
    length = math.hypot(x1 - x0, y1 - y0)
    tx, ty = (x1 - x0) / length, (y1 - y0) / length
    lane = lanes if isinstance(lanes, str) else lanes[round((lit((ty, -tx)) + 1) * (len(lanes) - 1) / 2)]
    mx, my = AX + (x0 + x1) / 2, (y0 + y1) / 2
    half = length * stretch / 2
    e = box((mx - w / 2, my - half, z - depth / 2), (mx + w / 2, my + half, z + depth / 2), "metal",
            faces=lane_faces(lane, my + ty * half, my - ty * half, east, west), **kw)
    return turn(e, math.degrees(math.atan2(ty, tx)) - 90, "z", (mx, my, z))


RING_LAYERS = (   # (centre line's depth into the heart, width, depth, front lanes, outer side, inner side)
    (RIM_W / 2, RIM_W, RIM_D, RIM_LANES, "rim_side", "step"),
    ((RIM_W + RING_W - LIP_W) / 2, BAND_W, BOW_D, FACE_LANES, "step", "step"),
    (RING_W - LIP_W / 2, LIP_W, LIP_D, LIP_LANES, "step", "lip_side"),
)


def bow_ring() -> list[dict]:
    """The stepped ring bent into a heart: recessed outer rim, main band, raised inner lip."""
    parts = []
    for off, w, depth, lanes, east, west in RING_LAYERS:
        loop = heart_loop(off)
        parts += [ring_segment(p0, p1, w, depth, lanes, east, west) for p0, p1 in zip(loop, loop[1:])]
    return parts


# --- the heart window -----------------------------------------------------------------------
WIN_S, WIN_CY = 7.4, 3.9          # one square slab under the ring's opening (cut out to a heart)
WIN_EDGE = RING_W - 0.15          # the window's heart runs just under the lip


def bow_window(tex: str = "window", glow: int = 7) -> list[dict]:
    x0, x1 = AX - WIN_S / 2, AX + WIN_S / 2
    y0, y1 = WIN_CY - WIN_S / 2, WIN_CY + WIN_S / 2
    return [box((x0, y0, AZ - WINDOW_D / 2), (x1, y1, AZ + WINDOW_D / 2), tex,
                faces={"south": (tex, [0, 0, 16, 16]), "north": (tex, [16, 0, 0, 16])},
                skip=("east", "west", "up", "down"), glow=glow)]


def window_xy(i: int, j: int, n: int = 32) -> tuple[float, float]:
    """Model (x, y) of window texel (i, j)."""
    return (AX - WIN_S / 2 + (i + 0.5) * WIN_S / n, WIN_CY + WIN_S / 2 - (j + 0.5) * WIN_S / n)


# --- heart-shaped parts: the gem, its bezel, the bit and the finial -------------------------
# The unit heart: a square of half-diagonal 1 turned 45 degrees (point down) with round lobes
# on its two upper edges. A part of half-diagonal h scales it by h.
HEART_W2 = 0.5 + math.sqrt(0.5)   # half its width (and its height above the square's centre)
HEART_MID = (HEART_W2 - 1) / 2    # centre of its bounding box above the square's centre
GEM_SWELL = 0.14                  # painted hearts swell this much on each beat...
FACE_K = 1.03 * (1 + GEM_SWELL)   # ...so their plates leave room round the resting heart


def unit_heart(x: float, y: float) -> bool:
    return abs(x) + abs(y) <= 1 or (abs(x) - 0.5) ** 2 + (y - 0.5) ** 2 <= 0.5


def heart_solid(cx: float, cy: float, h: float, z0: float, z1: float, tex: str, uv: dict, glow: int = 0,
                lobe_k: float = 0.96) -> list[dict]:
    """A heart (point down) of half-diagonal h round (cx, cy): a turned square and two octagonal
    lobes, extruded from z0 to z1, every face using uv (a dict side -> uv)."""
    s = h * math.sqrt(2)
    zc, depth = (z0 + z1) / 2, z1 - z0
    sq = box((cx - s / 2, cy - s / 2, z0), (cx + s / 2, cy + s / 2, z1), tex, uv=uv, glow=glow)
    parts = [turn(sq, 45, "z", (cx, cy, zc))]
    for sx in (-1, 1):
        parts += prism((cx + sx * h / 2, cy + h / 2, zc), h / math.sqrt(2) * lobe_k, depth, tex, axis="z",
                       cap=tex, uv=uv, glow=glow)
    return parts


def heart_face(cx: float, cy: float, h: float, z0: float, z1: float, tex: str = "gem", front: bool = True,
               glow: int = 14) -> dict:
    """A plate carrying a painted heart (cut out of a square texture) over a heart_solid."""
    half = h * HEART_W2 * FACE_K
    ym = cy + h * HEART_MID
    side = "south" if front else "north"
    return box((cx - half, ym - half, z0), (cx + half, ym + half, z1), tex,
               faces={side: (tex, [0, 0, 16, 16] if front else [16, 0, 0, 16])},
               skip=tuple(s for s in ("north", "south", "east", "west", "up", "down") if s != side), glow=glow)


def face_unit(i: int, j: int, n: int) -> tuple[float, float]:
    """Unit-heart coordinates of texel (i, j) of an n px heart face."""
    k = HEART_W2 * FACE_K
    return (-1 + (i + 0.5) * 2 / n) * k, HEART_MID + (1 - (j + 0.5) * 2 / n) * k


def face_texel(x: float, y: float, n: int) -> tuple[int, int]:
    """The texel of an n px heart face holding unit-heart point (x, y) (inverse of face_unit)."""
    k = HEART_W2 * FACE_K
    return int((x / k + 1) * n / 2), int((1 - (y - HEART_MID) / k) * n / 2)


def heart_norm(x: float, y: float, cx: float = 0.0, cy: float = 0.1) -> float:
    """The scale of the unit heart (about (cx, cy)) whose outline passes through (x, y):
    0 at the centre, 1 on the outline."""
    lo, hi = 0.0, 2.0
    for _ in range(16):
        mid = (lo + hi) / 2
        if unit_heart(cx + (x - cx) / mid, cy + (y - cy) / mid):
            hi = mid
        else:
            lo = mid
    return hi


def unit_point(ang: float) -> tuple[tuple[float, float], float]:
    """A point on the right lobe of the unit heart at angle ang (degrees round the lobe's centre)
    and its outward direction."""
    a = math.radians(ang)
    return (0.5 + math.sqrt(0.5) * math.cos(a), 0.5 + math.sqrt(0.5) * math.sin(a)), ang


# --- skeleton parts ---------------------------------------------------------------------

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


# ======================================================================================
# 2. THEME PALETTE: February, rose gold and pink (#FF6FA0)
# ======================================================================================
METAL = ROSE = ["#2b0a20", "#561936", "#882f48", "#b8515d", "#de8581", "#f6b5a6", "#ffe4d8"]
PINK = ["#5a0f35", "#8f1c52", "#c8306f", "#ef5a93", "#ff7fab", "#ffb0cb", "#ffe0ec"]
RUBY = ["#2a0112", "#520622", "#850c33", "#b8123e", "#e3244e", "#ff5a6f", "#ff9eab", "#fff0f0"]
VELVET = ["#15030f", "#28071f", "#3d0b2d", "#56103d", "#741a50", "#962766", "#bc3c7e"]
PEARL = ["#b46a8e", "#dea0ba", "#f6d4e2", "#ffffff"]
SATIN = ["#6e1238", "#a52258", "#d8407f", "#ff6fa0", "#ffb3cf", "#ffe6f0"]
FEATHER = ["#6e1238", "#a52258", "#d8407f", "#ff6fa0", "#ffa3c4", "#ffd6e5", "#fff6f9"]
GLOW = "#ff6f9a"

# ======================================================================================
# 3. THEME PARTS: ruby heart, pearls, ribbon, cupid's arrow, heart finial
# ======================================================================================
GEM_W = 3.1                                   # the heart-cut ruby, 3.1 across
GEM_H = GEM_W / (2 * HEART_W2)                # its square's half-diagonal
GEM_Y = 3.75                                  # its square's centre (its point is GEM_H below)
BEZEL_M = 0.38                                # the bezel shows this much round the gem
GEM_Z = (6.6, 9.4)                            # gem body; painted faces sit just outside it
CLAWS = (70, -15, 110, 195, "point", "cleft")  # lobe angles (the right lobe; 110/195 on the left)
BEATS = 2                                     # heartbeats per loop
ENAMEL_Y = (9.4, 13.0, 17.6, 25.85)           # pink enamel stripes: collar, bands, cap (the pulse's stops)


def gem_point(spec) -> tuple[tuple[float, float], float]:
    """A claw's spot on the gem's outline (model x, y) and its outward direction (degrees)."""
    if spec == "point":
        (ux, uy), ang = (0.0, -1.0), -90.0
    elif spec == "cleft":
        (ux, uy), ang = (0.0, 1.0), 90.0
    elif spec > 90:                            # left lobe: mirror of the right
        (ux, uy), ang = unit_point(180 - spec)
        ux, ang = -ux, 180 - ang
    else:
        (ux, uy), ang = unit_point(spec)
    return (AX + ux * GEM_H, GEM_Y + uy * GEM_H), ang


def flat(lane: str, y: float, k: float) -> dict:
    """uv for every face: one texel of a lane (a flat colour)."""
    return {s: dot_uv(lane, y, k) for s in ("north", "south", "east", "west", "up", "down")}


def ruby_heart() -> list[dict]:
    """The heart-cut ruby: a glowing heart body with painted faces front and back, in a rose-gold
    heart bezel gripped by six claws."""
    side_uv = {s: [10.25, 9.25, 10.75, 9.75] for s in ("north", "south", "east", "west", "up", "down")}  # a dark facet
    parts = heart_solid(AX, GEM_Y, GEM_H, GEM_Z[0], GEM_Z[1], "gem", side_uv, glow=14)
    parts.append(heart_face(AX, GEM_Y, GEM_H, GEM_Z[1], GEM_Z[1] + 0.35))
    parts.append(heart_face(AX, GEM_Y, GEM_H, GEM_Z[0] - 0.35, GEM_Z[0], front=False))
    hb = GEM_H + BEZEL_M / HEART_W2 * 1.1
    parts += heart_solid(AX, GEM_Y - (hb - GEM_H) * 0.55, hb, 6.9, 9.1, "metal", flat("band", BOW_Y, 0.6))
    for spec in CLAWS:
        p, ang = gem_point(spec)
        parts.append(radial(p, ang, -0.28, BEZEL_M + 0.02, 0.42, 0.55, "rim1", z=GEM_Z[1] + 0.12))
    return parts


def band_point(spec) -> tuple[float, float, float]:
    """A spot on the band's centre line: a lobe angle (degrees) or a fraction along the edge,
    on the right side; returns model x, y and the outward direction (degrees)."""
    d = RING_LAYERS[1][0]
    kind, v = spec
    if kind == "lobe":
        a = math.radians(v)
        return AX + HEART_A + (HEART_R - d) * math.cos(a), HEART_YC + (HEART_R - d) * math.sin(a), v
    tip = (0.0, HEART_YP + d / math.cos(PHI))
    r = HEART_R - d
    t0 = (HEART_A + r * math.cos(LOBE_A0), HEART_YC + r * math.sin(LOBE_A0))
    return AX + tip[0] + (t0[0] - tip[0]) * v, tip[1] + (t0[1] - tip[1]) * v, math.degrees(LOBE_A0)


PEARL_SPOTS = (("edge", 0.2), ("edge", 0.55), ("lobe", -8), ("lobe", 55))   # bottom to top, right side
PEARL_PHASES = ((0.0, 0.875), (0.125, 0.75), (0.25, 0.625), (0.375, 0.5))   # (right, left): a chase round the heart


def pearls() -> list[dict]:
    """Pink pearls set round the band, a bead at the heart's point and a neck filling the cleft."""
    parts = []
    for spot in PEARL_SPOTS:
        x, y, _ = band_point(spot)
        for sx, lane in ((1, "pearl"), (-1, "pearl2")):
            parts.append(diamond((AX + sx * (x - AX), y), 0.62, BOW_D + 0.3, lane, 45, glow=5))
    parts.append(diamond((AX, HEART_YP + 0.42), 0.78, 2.4, "band", 45))
    return parts


def neck() -> list[dict]:
    """A neck filling the heart's cleft under the collar."""
    return lane_prism(7.0, 8.6, 1.2, "band")


RIBBON_Y = 14.7


def ribbon() -> list[dict]:
    """A pink satin ribbon tied round the shaft: a wrap, a knot, two flared loops and two tails."""
    y = RIBBON_Y
    parts = lane_prism(y - 0.4, y + 0.4, 1.3, "satin", cap="satin")
    z = AZ + 1.35
    parts.append(box((AX - 0.46, y - 0.55, z - 0.3), (AX + 0.46, y + 0.55, z + 0.36), "metal",
                     faces=lane_faces("satin", y + 0.55, y - 0.55)))
    for sx in (-1, 1):
        for dx, dy, w in ((2.4, 1.0, 1.0), (2.25, 0.15, 0.95)):        # the loop: two fanned strips
            p0, p1 = (AX + sx * 0.3, y + 0.1, z), (AX + sx * dx, y + dy, z - 0.1)
            parts.append(bar(p0, p1, w, 0.42, "metal", faces=lane_faces("satin", p1[1], p0[1])))
        p0, p1 = (AX + sx * 0.2, y - 0.3, z + 0.05), (AX + sx * 1.15, y - 1.8, z - 0.05)
        parts.append(bar(p0, p1, 0.56, 0.32, "metal", faces=lane_faces("satin", p1[1], p0[1])))
    return parts


ARROW_Y = 21.8                     # cupid's arrow flies through the shaft here, heart first toward +X
NOCK_X, HEAD_X = 3.35, 10.1        # its rod runs from the nock to the head
# The fletching, the key's teeth: barbs (x where they leave the rod, length), tallest at the
# back, sweeping back toward the nock so each vane is a solid feather with a stepped edge.
FLETCH = ((4.15, 2.75), (4.8, 2.7), (5.45, 2.45), (6.1, 2.05), (6.7, 1.45))
FLETCH_ANGLE = 116                 # barbs sweep back toward the nock
FLETCH_W, FLETCH_D = 0.74, 0.62
HEAD_H = 0.85                      # the arrow's heart-shaped head (2.1 across)


def cupid_arrow() -> list[dict]:
    """The bit: cupid's arrow shot through the shaft. Its fletching on the -X side is the key's
    bit, two feathered vanes (pink at the quill, white at the tips) whose barbs step like
    teeth, a rose-gold nock behind them and a quill rib between; its heart head pokes out on +X."""
    y = ARROW_Y
    parts = [radial((AX, y), 180, -(HEAD_X - AX), AX - NOCK_X, 0.5, 0.5, "band"),
             radial((AX, y), 180, AX - NOCK_X - 0.1, AX - NOCK_X + 0.55, 0.95, 0.8, "band"),     # the nock
             radial((AX, y), 180, AX - FLETCH[-1][0] - 0.4, AX - FLETCH[0][0] + 0.2, 0.62, 0.72, "rim2")]  # quill
    for k, (x, length) in enumerate(FLETCH):
        lane = ("fletch", "fletch2")[k % 2]
        for ang in (FLETCH_ANGLE, 360 - FLETCH_ANGLE):
            parts.append(radial((x, y), ang, 0.1, length, FLETCH_W, FLETCH_D, lane))
    cx = HEAD_X + HEAD_H * HEART_W2 - 0.3
    head = heart_solid(cx, y, HEAD_H, AZ - 0.55, AZ + 0.55, "metal", flat("band", y, 0.6))
    head.append(heart_face(cx, y, HEAD_H * 0.8, AZ + 0.55, AZ + 0.75, glow=12))
    head.append(heart_face(cx, y, HEAD_H * 0.8, AZ - 0.75, AZ - 0.55, front=False, glow=12))
    return parts + turn(head, 90, "z", (cx, y, AZ))


FINIAL_H = 0.95                               # the crowning heart: half-diagonal (2.3 across)


def heart_finial() -> list[dict]:
    cy = CAP[1] - 0.1 + FINIAL_H
    parts = heart_solid(AX, cy, FINIAL_H, AZ - 0.8, AZ + 0.8, "metal", flat("band", cy, 0.6))
    parts.append(heart_face(AX, cy, FINIAL_H * 0.78, AZ + 0.8, AZ + 1.0, glow=12))
    parts.append(heart_face(AX, cy, FINIAL_H * 0.78, AZ - 1.0, AZ - 0.8, front=False, glow=12))
    return parts


INLAY_Y = (11.5, 19.35)                      # tiny ruby hearts inlaid on the shaft, front and back
INLAY_H = 0.42


def inlays() -> list[dict]:
    parts = []
    for y in INLAY_Y:
        z = AZ + SHAFT_R
        parts.append(heart_face(AX, y, INLAY_H, z - 0.05, z + 0.12, glow=10))
        parts.append(heart_face(AX, y, INLAY_H, AZ - SHAFT_R - 0.12, AZ - SHAFT_R + 0.05, front=False, glow=10))
    return parts


def bow() -> list[dict]:
    """Everything that makes the heart: ring, window, ruby, pearls."""
    return bow_ring() + bow_window() + ruby_heart() + pearls()


def stem() -> list[dict]:
    """Everything above the heart: collar, shaft, ribbon, arrow, finial, inlays."""
    return collar() + shaft() + ribbon() + cupid_arrow() + heart_finial() + inlays()


# ======================================================================================
# 4. TEXTURES
# ======================================================================================

def ramp_at(ramp, f: float) -> str:
    f = clamp(f, 0, len(ramp) - 1)
    i = min(int(f), len(ramp) - 2)
    return mix(ramp[i], ramp[i + 1], f - i)


def _blip(x: float, at: float, rise: float, fall: float) -> float:
    d = ((x - at + 0.5) % 1.0) - 0.5
    return math.exp(-(d / (rise if d < 0 else fall)) ** 2)


GEM_PEAK = round(sweep_time(GEM_Y) * FRAMES + 1) / FRAMES   # the first lub lands as the glint passes


def beat_phase(t: float) -> float:
    """0..1 through the current heartbeat (0 = the lub)."""
    return ((t - GEM_PEAK) * BEATS) % 1.0


def heartbeat(t: float) -> float:
    """0..1: a lub-dub twice a loop, the first as the glint crosses the ruby."""
    x = beat_phase(t)
    return max(_blip(x, 0.0, 0.04, 0.06), 0.72 * _blip(x, 0.13, 0.04, 0.1))


def twinkle(t: float, phase: float, life: float = 0.2) -> float:
    """0..1: a short twinkle once a loop, starting at `phase`."""
    x = (t - phase) % 1.0
    return math.sin(math.pi * x / life) ** 2 if x < life else 0.0


def paint_theme_lanes(img) -> None:
    paint_lane(img, "enamel", [PINK[5], PINK[4], PINK[3], PINK[2]])
    paint_lane(img, "pearl", [PEARL[2], PEARL[1], PEARL[0]])
    paint_lane(img, "pearl2", [PEARL[2], PEARL[1], PEARL[0]])
    paint_lane(img, "satin", [SATIN[4], SATIN[3], SATIN[2]])
    for r in range(ATLAS):             # the feathers: deep pink at the quill, white at the tips
        d = abs(Y_TOP - (r + 0.5) / RPU - ARROW_Y)
        f = 1.3 + 5.0 * clamp((d - 0.35) / 2.0) ** 1.3
        paint_lane(img, "fletch", [ramp_at(FEATHER, f + 0.6), ramp_at(FEATHER, f), ramp_at(FEATHER, f - 0.8)], rows=[r])
        paint_lane(img, "fletch2", [ramp_at(FEATHER, f + 0.2), ramp_at(FEATHER, f - 0.4), ramp_at(FEATHER, f - 1.2)],
                   rows=[r])
    px = img.load()                     # a line of pink enamel inlaid all round the heart's band
    for lane, tone in zip(FACE_LANES, (PINK[2], PINK[3], PINK[4])):
        c0, _ = LANES[lane]
        for r in range(ATLAS):
            px[c0 + 1, r] = rgba(tone)
    for y in ENAMEL_Y:                  # pink enamel inlaid round the collar, the bands and the cap
        paint_lane(img, "band", [PINK[4], PINK[3], PINK[2], PINK[2]], rows=[row_of(y)])


def theme_atlas_frame(img, t: float) -> None:
    """Each pearl glints once a loop, in turn round the heart; after every lub a pulse runs up
    the shaft, lighting the enamel stripes one after another."""
    px = img.load()
    for spot, phases in zip(PEARL_SPOTS, PEARL_PHASES):
        _, y, _ = band_point(spot)
        for lane, phase in zip(("pearl", "pearl2"), phases):
            k = twinkle(t, phase)
            if k < 0.03:
                continue
            c0, w = LANES[lane]
            for r in range(row_of(y) - 1, row_of(y) + 2):
                for i in range(w):
                    cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
                    px[c0 + i, r] = rgba(mix(cur, "#ffffff", 0.9 * k))
    x = beat_phase(t)
    front = GEM_Y + 40 * x                     # the pulse climbs from the ruby to the tip
    c0, w = LANES["band"]
    for y in ENAMEL_Y:
        k = math.exp(-((front - y) / 2.6) ** 2) * (1 - x)
        if k < 0.05:
            continue
        r = row_of(y)
        for i in range(w):
            cur = "#%02x%02x%02x" % px[c0 + i, r][:3]
            px[c0 + i, r] = rgba(mix(cur, PINK[6], 0.85 * k))


def paint_atlas_base():
    img = canvas(ATLAS, fill=METAL[3])
    paint_skeleton_lanes(img, METAL)
    paint_theme_lanes(img)
    return img


GLINTS = (((-0.55, 0.64), 7), ((-0.4, 0.64), 7), ((-0.55, 0.49), 7), ((0.55, 0.5), 6))   # unit-heart spots


def gem_frame(t: float):
    """The ruby's face, a heart cut like a brilliant: a bright heart table, a ring of kite
    facets alternating light and dark, a girdle lit top left and deep bottom right, white
    glints on the left lobe. On the lub and the dub it swells and flares."""
    p = heartbeat(t)
    sw = 1 + GEM_SWELL * p
    lift = 0.35 + 1.55 * p
    img = canvas(16)
    px = img.load()
    la = math.radians(125)                                  # light from the top left
    for j in range(16):
        for i in range(16):
            fx, fy = face_unit(i, j, 16)
            ux, uy = fx / sw, HEART_MID + (fy - HEART_MID) / sw
            o = heart_norm(ux, uy, 0.0, 0.12)
            if o > 1.0:
                continue
            ang = math.atan2(uy - 0.12, ux)
            if o > 0.83:                                    # the girdle
                f = 2.1 + 1.9 * math.cos(ang - la)
            elif o <= 0.4:                                  # the table
                f = 4.5 + 0.45 * (-0.7 * ux + 0.7 * (uy - 0.12))
            else:                                           # kite facets
                k = math.floor((ang + math.pi / 8) / (math.pi / 4))
                f = 3.0 + 1.35 * math.cos(k * math.pi / 4 - la) + (0.45 if k % 2 == 0 else -0.25)
                if o > 0.66:
                    f -= 0.35
            px[i, j] = rgba(ramp_at(RUBY, f + lift * (1.0 if o <= 0.83 else 0.75)))
    for (gx, gy), tone in GLINTS:
        i, j = face_texel(gx * sw, HEART_MID + (gy - HEART_MID) * sw, 16)
        px[i, j] = rgba(RUBY[tone])
    i, j = face_texel(0.2 * sw, HEART_MID + (-0.3 - HEART_MID) * sw, 16)
    px[i, j] = rgba(ramp_at(RUBY, 6 + lift))
    i, j = face_texel(-0.5 * sw, HEART_MID + (0.58 - HEART_MID) * sw, 16)
    sparkle(img, i, j, clamp(p * 1.3 - 0.3), colour="#ffffff", reach=3)
    return img


# Tiny hearts in the window (texels): x, y, twinkle phase, big.
HEARTS = ((6, 6, 0.0, True), (25, 6, 0.5, True), (4, 11, 0.62, False), (28, 11, 0.12, False),
          (10, 4, 0.3, False), (21, 4, 0.8, False), (16, 26, 0.72, False), (8, 15, 0.2, False),
          (24, 16, 0.4, False))
SPECKS = ((3, 8, 0.05), (29, 8, 0.55), (13, 2, 0.35), (19, 2, 0.85), (6, 13, 0.7), (26, 13, 0.2),
          (15, 28, 0.45), (12, 23, 0.95), (20, 23, 0.6))
HEART_SMALL = ["a.a", "aWa", ".a."]
HEART_BIG = [".a.a.", "aWaWa", "aWWWa", ".aWa.", "..a.."]


def window_frame(t: float):
    """Dark rose velvet cut to a heart: darker under the lip, a rosy glow round the ruby that
    swells with each beat and sends a ripple outward, and tiny hearts twinkling."""
    p = heartbeat(t)
    x = beat_phase(t)
    ripple_r = 1.9 + 3.0 * (x / 0.45)
    ripple_k = 0.75 * (1 - x / 0.45) ** 1.3 if x < 0.45 else 0.0
    gy = GEM_Y + GEM_H * HEART_MID
    img = canvas(32)
    px = img.load()
    for j in range(32):
        for i in range(32):
            mx, my = window_xy(i, j)
            e = heart_depth(mx, my) - WIN_EDGE
            if e < 0:
                continue
            r = math.hypot(mx - AX, my - gy)
            f = 1.1 + 2.4 * max(0.0, 1 - r / 4.2) ** 1.4 + 0.5 * clamp(e / 1.0)    # lit from the ruby
            if e < 0.32:
                f -= (0.32 - e) * 6.0                                       # shadow under the lip
            c = VELVET[int(clamp(dither_band(f, i, j), 0, len(VELVET) - 1))]
            halo = (0.06 + 0.9 * p) * max(0.0, 1 - r / 4.2) ** 1.2
            if halo > 0.04:
                c = mix(c, GLOW, min(0.85, halo))
            if ripple_k > 0.02 and abs(r - ripple_r) < 0.45:
                c = mix(c, "#ffc4d8", ripple_k * (1 - abs(r - ripple_r) / 0.45))
            px[i, j] = rgba(c)
    for sx, sy, phase in SPECKS:                   # far-off glints
        k = twinkle(t, phase, 0.25)
        if k > 0.1 and px[sx, sy][3]:
            cur = "#%02x%02x%02x" % px[sx, sy][:3]
            px[sx, sy] = rgba(mix(cur, "#ffe0ea", k))
    for hx, hy, phase, big in HEARTS:
        k = twinkle(t, phase, 0.34)
        if k < 0.05:
            continue
        rise = int(3 * ((t - phase) % 1.0) / 0.34)
        draw_heart(img, hx, hy - rise, big, k)
    return img


def draw_heart(img, x: int, y: int, big: bool, k: float) -> None:
    px = img.load()
    stamp = HEART_BIG if big else HEART_SMALL
    o = len(stamp[0]) // 2
    for j, line in enumerate(stamp):
        for i, ch in enumerate(line):
            xx, yy = x + i - o, y + j - o
            if ch == "." or not (0 <= xx < 32 and 0 <= yy < 32) or px[xx, yy][3] == 0:
                continue
            cur = "#%02x%02x%02x" % px[xx, yy][:3]
            px[xx, yy] = rgba(mix(cur, "#ffffff" if ch == "W" else "#ffa6c6", k))
    if big and k > 0.75:
        sparkle(img, x, y, (k - 0.75) * 4, colour="#ffffff", reach=2)


def textures() -> None:
    base = paint_atlas_base()
    save_animation(animate(lambda t: atlas_frame(base, t), FRAMES), "metal", frametime=FRAMETIME)
    save_animation(animate(gem_frame, GEM_FRAMES), "gem", frametime=GEM_FRAMETIME, interpolate=True)
    save_animation(animate(window_frame, FRAMES), "window", frametime=FRAMETIME)


# ======================================================================================
# 5. MODELS
# ======================================================================================

GUI_TILT = 45.0      # in inventories the heart turns this much on the shaft so it stands upright


def models() -> dict:
    parts = bow() + neck() + stem()
    icon = turn(bow(), GUI_TILT, "z", (AX, BOW_Y, AZ)) + stem()
    return {"main": model(parts, transforms(parts)), "gui": model(icon, transforms(icon))}
