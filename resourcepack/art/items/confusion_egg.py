"""Confusion Egg: a lime and purple psychedelic egg with a hypnotic spiral and googly eyes.

The shared egg body (the EGG_PROFILE lathe, 16 sides) is painted with a two-armed hypnotic
spiral that winds from a pole on the front of the egg round the shell to a pole on the
back. Its UVs follow the egg round (azimuth -> u, height -> v, two maps of half a turn
each), and every texel is painted from the 3D spiral field at its true spot on the model,
so the arms run on unbroken over the facets. The light is baked into the texture (the
shell is unshaded) so the lathe steps melt into one glossy round shell: each lime arm has
a lit leading edge and rolls into shadow, each purple band is inked behind the lime and
catches a rim light before the next arm, and a lacquer glint sits on the upper left.

Googly eyes, joke-shop style: a big one stuck over the front pole (the spiral pours into
it), one over the back pole, a tiny one stuck on crooked low at the back, and one popping
out of the upper right shoulder on a coiled steel spring. Each is a white disc under a
low clear dome (the big ones step in at the rim) with a loose black pupil on its face.

Animation (32 frames x 2 ticks = 3.2 s): the spiral turns once and its arms pour into the
front eye while a jelly wobble twists and untwists them; a violet-magenta and lime-
chartreuse tide swirls the other way through the bands; a glowing neon edge rides the
lime arms (dashes of brighter light running inward), and the pupils never agree: the two
front eyes roll round their rims in opposite directions, the back one rolls, the tiny
one swings.
"""
from __future__ import annotations

import math

from art.kit import (animate, bar, box, canvas, copy, display, fit, lathe, matrix_to_euler, mix, model,
                     place, prism, rgba, save, save_animation, turn)

ID = "confusion_egg"
NAME = "Confusion Egg"
KIND = "item"
COUNTERPART = "item/egg"

TAU = 2 * math.pi

# --------------------------------------------------------------------------------------
# Palettes, darkest -> lightest (lime shadows drift to teal, purple lights to orchid)
# --------------------------------------------------------------------------------------
LIME = ["#05261f", "#0a452b", "#106a28", "#2a9214", "#56bf02", "#88ff00", "#b3ff3a", "#dbff84", "#f6ffd4"]
PURP = ["#0b0320", "#19063a", "#2a0b58", "#3f117a", "#5617a0", "#6f21c2", "#8b35dc", "#a856ec", "#c585f7",
        "#e4c0ff"]
CHARTREUSE = ["#16290a", "#2f4a0c", "#566f0c", "#84970e", "#b2c006", "#d2ec00", "#e6ff3c", "#f2ff88", "#fcffd8"]
MAGENTA = ["#1c0224", "#340744", "#520c68", "#71118b", "#9116ab", "#b01fc6", "#c83ade", "#db5eec",
           "#ea8bf7", "#f7c4ff"]
INK = "#070116"
NEON = ["#c8ff4a", "#efffb0"]
WHITE = ["#4b3f6e", "#8a80a8", "#bdb6d4", "#dcd8ea", "#efedf6", "#ffffff"]
PUPIL = ["#0a0512"]
STEEL = ["#2a2840", "#4d4b6b", "#7a7a9c", "#a9acc8", "#d4d8ec", "#f4f6ff"]

# --------------------------------------------------------------------------------------
# The shared egg body
# --------------------------------------------------------------------------------------
EGG_PROFILE = [(0, 1.4), (0.7, 3.2), (1.8, 4.3), (3.3, 4.9), (5.0, 5.0), (6.8, 4.7), (8.4, 4.0),
               (9.8, 3.0), (10.9, 1.8), (11.5, 0.6)]
EGG_CENTER = (8.0, 2.2, 8.0)
CY = EGG_CENTER[1]
EGG_TOP = EGG_PROFILE[-1][0]
SLICES = [(h0, h1, (r0 + r1) / 2) for (h0, r0), (h1, r1) in zip(EGG_PROFILE, EGG_PROFILE[1:])]

# Shell maps: N x N frames; each half of the egg (8 facets of 22.5 degrees) spans the full
# width, 4 texels per facet; every slice gets a whole number of rows (row 0 is the tip).
N = 32
FRAMES, FRAMETIME = 32, 2
ROWS = (2, 3, 4, 5, 5, 4, 4, 3, 2)                        # rows per slice, bottom to top
ROW0 = [sum(ROWS[k + 1:]) for k in range(len(ROWS))]      # first row (from the top) of a slice
HALF = {"front": -101.25, "back": 78.75}                  # azimuth at u = 0; each half spans 180
TAN16 = math.tan(math.pi / 16)

# The spiral: poles on the front (+Z) and back of the egg at this height.
POLE_H = 5.4
POLE = (8.0, CY + POLE_H, 8.0)
ARMS = 2
TWIST = 8.0          # phase per radian away from the front pole
SPIN = 2             # phase turns per loop (the arms pour into the front eye)
WOBBLE = 0.6         # jelly wobble: the arms twist and untwist (radians of phase)

SIZE = 1.3
GRIP = (8.0, CY + 3.9, 8.0)
GUI_ROT = (10, -18, 0)


def clamp(v, lo=0.0, hi=1.0):
    return max(lo, min(hi, v))


def _norm(v):
    length = math.sqrt(sum(c * c for c in v))
    return tuple(c / length for c in v)


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def _add(a, b, k=1.0):
    return tuple(a[i] + b[i] * k for i in range(3))


def _sub(a, b):
    return tuple(a[i] - b[i] for i in range(3))


# --------------------------------------------------------------------------------------
# Shell geometry: texel -> spot on the model
# --------------------------------------------------------------------------------------

def slice_of_row(r: int) -> int:
    r = max(0, min(N - 1, r))
    return next(k for k in range(len(SLICES)) if ROW0[k] <= r < ROW0[k] + ROWS[k])


def profile_r(h: float) -> float:
    h = clamp(h, 0.0, EGG_TOP)
    for (h0, r0), (h1, r1) in zip(EGG_PROFILE, EGG_PROFILE[1:]):
        if h0 <= h <= h1:
            return r0 + (r1 - r0) * (h - h0) / (h1 - h0)
    return EGG_PROFILE[-1][1]


def profile_slope(h: float, window: float = 0.7) -> float:
    a, b = clamp(h - window, 0.0, EGG_TOP), clamp(h + window, 0.0, EGG_TOP)
    return (profile_r(b) - profile_r(a)) / (b - a)


def normal_at(phi: float, h: float):
    p = math.radians(phi)
    return _norm((math.sin(p), -profile_slope(h), math.cos(p)))


def texel_point(half: str, c: int, r: int):
    """(point, azimuth, height) of the centre of texel (c, r) of a half's map, on the flat
    facet it is painted on. c may run past the map (into the other half)."""
    r = max(0, min(N - 1, r))
    k = slice_of_row(r)
    h0, h1, rad = SLICES[k]
    h = h1 - (r - ROW0[k] + 0.5) / ROWS[k] * (h1 - h0)
    j, s = c // 4, (c % 4 + 0.5) / 4
    phi_f = HALF[half] + 11.25 + 22.5 * j
    a = math.radians(phi_f)
    w = (s - 0.5) * 2 * rad * TAN16
    point = (8 + rad * math.sin(a) + w * math.cos(a), CY + h, 8 + rad * math.cos(a) - w * math.sin(a))
    phi = HALF[half] + (c + 0.5) * 180.0 / N
    return point, phi, h


# --------------------------------------------------------------------------------------
# The spiral field
# --------------------------------------------------------------------------------------

def polar(p):
    """(rho, theta) of a point: angle from the front pole, and angle round it (as seen
    from the front: 0 = the viewer's right, counter-clockwise)."""
    d = _norm(_sub(p, POLE))
    return math.acos(clamp(d[2], -1.0, 1.0)), math.atan2(d[1], d[0])


def phase(rho: float, theta: float, t: float) -> float:
    """Spiral phase 0..1: lime for [0, 0.5), purple for [0.5, 1)."""
    s = ARMS * theta + TWIST * rho + WOBBLE * math.sin(TAU * t + 1.6 * rho) + TAU * SPIN * t
    return (s / TAU) % 1.0


def wrapdiff(a: float, b: float) -> float:
    return ((a - b + 0.5) % 1.0) - 0.5


# --------------------------------------------------------------------------------------
# Light baked into the glossy shell
# --------------------------------------------------------------------------------------
BAYER = (0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
KEY = _norm((-0.5, 0.75, 0.62))         # key light: upper left, in front
BACKLIGHT = _norm((0.5, 0.5, -0.7))     # a softer light over the back
BOUNCE = _norm((0.7, -0.45, 0.2))       # bounce light low on the right
SPEC = _norm((-0.55, 0.6, 0.58))        # where the glossy highlight sits


def threshold(c: int, r: int) -> float:
    return (BAYER[(r % 4) * 4 + c % 4] + 0.5) / 16.0 - 0.5


def light(n) -> float:
    key = clamp((_dot(n, KEY) + 0.3) / 1.3)
    back = clamp((_dot(n, BACKLIGHT) + 0.1) / 1.1)
    bounce = max(0.0, _dot(n, BOUNCE))
    return clamp(0.3 + 0.6 * key + 0.2 * back * (1 - key) + 0.15 * bounce)


def gloss(n) -> float:
    g = _dot(n, SPEC)
    return 3.0 if g > 0.994 else 2.0 if g > 0.982 else 1.0 if g > 0.955 else 0.0


# --------------------------------------------------------------------------------------
# Googly eyes: where they sit, how big, and how their pupils move
# --------------------------------------------------------------------------------------
# phi/h: spot on the shell; R: disc radius; T: how far it stands proud; roll: twist of the
# disc; tilt: degrees it is stuck on crooked (toward its right, up); dome: the face's share
# of R where a big eye steps in to its dome (None: a plain wall); cell: (x, y, size) of its
# face in the eye atlas; pupil: pupil diameter (texels); glint: the dome's reflection shows
# over the pupil; path: ("roll", turns per loop (+ counter-clockwise), start) rolls round
# the rim, ("swing", swings per loop, start) hangs and swings; spring: pops the eye out of
# the shell on a coil (length, coil radius, turns, bend of its axis, wire, facing).
EYES = [
    {"phi": 0.0, "h": POLE_H, "R": 2.1, "T": 0.75, "roll": 0.0, "tilt": (0.0, 0.0), "dome": 0.9,
     "cell": (0, 0, 11), "pupil": 5, "glint": True, "path": ("roll", -1, 0.62)},
    {"phi": 180.0, "h": POLE_H, "R": 1.8, "T": 0.65, "roll": 0.0, "tilt": (0.0, 0.0), "dome": 0.9,
     "cell": (12, 0, 9), "pupil": 4, "glint": True, "path": ("roll", 1, 0.1)},
    {"phi": 50.0, "h": 8.7, "R": 1.55, "T": 0.45, "roll": -8.0, "tilt": (0.0, 0.0), "dome": None,
     "cell": (22, 0, 10), "pupil": 4, "path": ("roll", 1, 0.3),
     "spring": {"length": 2.1, "radius": 0.62, "turns": 3, "bend": (0.2, 0.45), "wire": 0.36,
                "face": (0.32, 0.18, 1.0)}},
    {"phi": -118.0, "h": 3.0, "R": 1.15, "T": 0.4, "roll": 16.0, "tilt": (-12.0, 8.0), "dome": None,
     "cell": (0, 12, 6), "pupil": 2, "path": ("swing", 2, 0.35)},
]


def eye_frame_axes(eye: dict):
    """(base point on the shell, normal, up, right) of an eye."""
    phi, h = eye["phi"], eye["h"]
    n = normal_at(phi, h)
    rs = profile_r(h)
    p = math.radians(phi)
    base = (8 + rs * math.sin(p), CY + h, 8 + rs * math.cos(p))
    up = _norm(_add((0, 1, 0), n, -n[1]))
    right = _cross(up, n)
    a = math.radians(eye["roll"])
    up, right = (_norm(_add(_add((0, 0, 0), up, math.cos(a)), right, math.sin(a))),
                 _norm(_add(_add((0, 0, 0), right, math.cos(a)), up, -math.sin(a))))
    # Stuck on crooked: tip the disc off the shell's normal (degrees toward its right, up).
    tr, tu = (math.radians(v) for v in eye["tilt"])
    n2 = _norm(_add(_add(n, right, math.tan(tr)), up, math.tan(tu)))
    up = _norm(_add(up, n2, -_dot(up, n2)))
    right = _cross(up, n2)
    return base, n2, up, right


EYE_AXES = [eye_frame_axes(e) for e in EYES]
COLLAR_R = 0.72


def footprint(eye: dict) -> float:
    """Radius of what an eye leaves on the shell: its disc, or the collar of its spring."""
    return COLLAR_R + 0.05 if eye.get("spring") else eye["R"]


def eye_cover(p) -> float:
    """How close a shell point is to the nearest eye's rim: <= 0 under an eye, else the
    gap in units (for the contact shadow round each eye)."""
    best = 99.0
    for eye, (base, n, up, right) in zip(EYES, EYE_AXES):
        d = _sub(p, base)
        if _dot(d, n) < -2.5:
            continue
        x, y = _dot(d, right), _dot(d, up)
        best = min(best, math.hypot(x, y) - footprint(eye))
    return best


def eye_shadow(p) -> float:
    """Contact shadow strength (0..1) round the eyes, heavier below and to the right."""
    s = 0.0
    for eye, (base, n, up, right) in zip(EYES, EYE_AXES):
        d = _sub(p, base)
        if _dot(d, n) < -2.5:
            continue
        x, y = _dot(d, right) - 0.25, _dot(d, up) + 0.3
        gap = math.hypot(x, y) - footprint(eye)
        if gap < 0.55:
            s = max(s, 1.0 if gap < 0.25 else 0.5)
    return s


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

class Texel:
    __slots__ = ("c", "r", "rho", "theta", "nbrs", "lit", "gloss", "covered", "shadow")


def _texels(half: str) -> list:
    out = []
    for r in range(N):
        for c in range(N):
            p, phi, h = texel_point(half, c, r)
            tx = Texel()
            tx.c, tx.r = c, r
            tx.rho, tx.theta = polar(p)
            right, _, _ = texel_point(half, c + 1, r)
            below, _, _ = texel_point(half, c, r + 1 if r < N - 1 else r - 1)
            tx.nbrs = (polar(right), polar(below))
            n = normal_at(phi, h)
            tx.lit = light(n) + threshold(c, r) * 0.06
            tx.gloss = gloss(n)
            tx.covered = eye_cover(p) < -0.05
            tx.shadow = eye_shadow(p)
            out.append(tx)
    return out


TEXELS = {half: _texels(half) for half in ("front", "back")}


def band(tx, t: float):
    """(spiral phase, its change per texel) at a texel: phase / change is how many texels
    the texel lies from the lime arm's leading edge, so edges come out one texel wide."""
    f = phase(tx.rho, tx.theta, t)
    fr = phase(*tx.nbrs[0], t)
    fb = phase(*tx.nbrs[1], t)
    grad = max(1e-4, math.hypot(wrapdiff(fr, f), wrapdiff(fb, f)))
    return f, grad


def shell_colour(tx, t: float) -> str:
    f, grad = band(tx, t)
    lit = tx.lit
    if f < 0.5:
        lead, trail = f / grad, (0.5 - f) / grad
        i = round(2.2 + 4.3 * lit) + tx.gloss
        if lead < 0.95:
            i += 2                                          # the lit leading edge
        elif trail < 0.95:
            i -= 2                                          # the arm rolls into shadow
        i = int(clamp(i - round(2 * tx.shadow), 0, len(LIME) - 1))
        return mix(LIME[i], CHARTREUSE[i], 0.55 * hue_wobble(tx, t))
    start, end = (f - 0.5) / grad, (1.0 - f) / grad
    if start < 0.95:
        return INK                                          # inked edge behind each lime arm
    i = round(1.3 + 5.2 * lit) + tx.gloss
    if end < 0.95:
        i += 1                                              # rim light before the next arm
    i = int(clamp(i - round(2 * tx.shadow), 0, len(PURP) - 1))
    return mix(PURP[i], MAGENTA[i], hue_wobble(tx, t))


def hue_wobble(tx, t: float) -> float:
    """0 = violet, 1 = magenta: a loose counter-turning swirl of colour through the purple."""
    w = 0.5 + 0.5 * math.sin(2.0 * tx.rho - tx.theta + TAU * t)
    return 0.8 * w * w


def shell_frame(half: str, t: float):
    img = canvas(N)
    px = img.load()
    for tx in TEXELS[half]:
        px[tx.c, tx.r] = rgba(shell_colour(tx, t))
    return img


def neon_frame(half: str, t: float):
    """The glowing skin: only the lime arms' leading edges, so the spiral glows at night."""
    img = canvas(N)
    px = img.load()
    for tx in TEXELS[half]:
        if tx.covered:
            continue
        f, grad = band(tx, t)
        if f < 0.5 and f / grad < 0.95:
            pulse = math.cos(4.0 * tx.rho + TAU * 2 * t) > 0.2
            px[tx.c, tx.r] = rgba(NEON[1] if pulse else NEON[0])
    return img


def paint_shell() -> None:
    for half in ("front", "back"):
        save_animation(animate(lambda t, half=half: shell_frame(half, t), FRAMES), "shell_" + half,
                       frametime=FRAMETIME)
        save_animation(animate(lambda t, half=half: neon_frame(half, t), FRAMES), "neon_" + half,
                       frametime=FRAMETIME)


# Ledges between slices: one flat colour per slice (every slab of a slice shows the same
# texel), lighter toward the top where the steps catch the light.
CAP_UP = {4: 4, 5: 5, 6: 5, 7: 6, 8: 7}          # slice -> purple ramp index of its top
CAP_DOWN = {0: 2, 1: 2, 2: 3, 3: 3}               # slice -> purple ramp index of its underside


def cap_uv(k: int, up: bool) -> list[float]:
    """UV of a single texel of the cap swatch map: row 0 for tops, row 1 for undersides."""
    x, y = k, 0 if up else 1
    return [x + 0.25, y + 0.25, x + 0.75, y + 0.75]


def paint_caps() -> None:
    img = canvas(32, fill=PURP[3])
    for k, i in CAP_UP.items():
        for dx in (0, 1):
            for dy in (0, 1):
                img.putpixel((2 * k + dx, dy), rgba(PURP[i]))
    for k, i in CAP_DOWN.items():
        for dx in (0, 1):
            for dy in (0, 1):
                img.putpixel((2 * k + dx, 2 + dy), rgba(PURP[i]))
    save(img, "caps")


STAMPS = {
    2: ["##", "##"],
    3: ["###", "###", "###"],
    4: [".##.", "####", "####", ".##."],
    5: [".###.", "#####", "#####", "#####", ".###."],
}


def disc_mask(s: int) -> set:
    rad = s / 2 - 0.18
    return {(x, y) for y in range(s) for x in range(s) if math.hypot(x + 0.5 - s / 2, y + 0.5 - s / 2) <= rad}


def pupil_offset(eye: dict, t: float):
    """Pupil centre (texels from the disc centre, y down) at phase t."""
    s, dp = eye["cell"][2], eye["pupil"]
    reach = s / 2 - 1 - dp / 2
    kind, count, start = eye["path"]
    if kind == "roll":
        a = TAU * (start + count * t)
        return reach * math.cos(a), -reach * math.sin(a)
    a = -math.pi / 2 + 1.0 * math.sin(TAU * (start + count * t))
    return reach * math.cos(a), -reach * math.sin(a)


EYE_LIGHT = _norm((-0.45, -0.55, 0.7))      # texture space (y down): upper left, in front


def paint_eye(img, eye: dict, t: float) -> None:
    """A googly eye's face: a white disc under a clear dome (shaded like a low dome, lit
    from the upper left, with the dome's rim round it), and the loose black pupil."""
    x0, y0, s = eye["cell"]
    dp = eye["pupil"]
    disc = disc_mask(s)
    inner = {(x, y) for (x, y) in disc if all((x + dx, y + dy) in disc for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))}
    px = img.load()
    half = s / 2
    for (x, y) in disc:
        cx, cy = (x + 0.5 - half) / half, (y + 0.5 - half) / half
        if (x, y) not in inner:
            col = WHITE[1] if cx + cy > 0.35 else WHITE[2] if cx + cy > -0.6 else WHITE[3]   # the dome's rim
        else:
            nz = math.sqrt(max(0.0, 1.0 - 0.55 * (cx * cx + cy * cy)))
            n = _norm((cx * 0.55, cy * 0.55, nz))
            lit = _dot(n, EYE_LIGHT)
            col = WHITE[5] if lit > 0.95 else WHITE[4] if lit > 0.66 else WHITE[3] if lit > 0.45 else WHITE[2]
        px[x0 + x, y0 + y] = rgba(col)
    # The loose pupil, snapped so its stamp keeps its shape as it rolls.
    ox, oy = pupil_offset(eye, t)
    half_dp = dp / 2
    stamp = STAMPS[dp]

    def place_at(ox_, oy_):
        if dp % 2:
            ox_, oy_ = math.floor(ox_) + 0.5, math.floor(oy_) + 0.5
        else:
            ox_, oy_ = round(ox_), round(oy_)
        left_, top_ = round(half + ox_ - half_dp), round(half + oy_ - half_dp)
        return left_, top_, [(left_ + i, top_ + j) for j, row in enumerate(stamp) for i, ch in enumerate(row)
                             if ch == "#"]

    for step in range(5):
        left, top, cells = place_at(ox * (1 - step / 4), oy * (1 - step / 4))
        if all(c in inner for c in cells):
            break
    for (x, y) in cells:
        px[x0 + x, y0 + y] = rgba(PUPIL[0])
    if eye.get("glint"):
        # The dome's reflection: fixed on the plastic, so it only shows when the matte pupil
        # rolls in under it.
        g = round(half - 0.3 * s)
        for (x, y) in ((g, g), (g + 1, g)):
            if (x, y) in cells:
                px[x0 + x, y0 + y] = rgba(WHITE[5])


def eye_frame(t: float):
    img = canvas(32)
    for eye in EYES:
        paint_eye(img, eye, t)
    return img


def paint_eyes() -> None:
    save_animation(animate(eye_frame, FRAMES), "eyes", frametime=FRAMETIME)
    side = canvas(16)
    for y in range(16):
        col = WHITE[3] if y < 3 else WHITE[2] if y < 6 else WHITE[1] if y < 9 else WHITE[0]
        for x in range(8):
            side.putpixel((x, y), rgba(col))
    for y in range(4):
        for x in range(8, 16):
            side.putpixel((x, y), rgba(WHITE[4]))        # the dome step's rim, seen from the front
    save(side, "eye_side")
    # The spring: round steel wire, lit along one side.
    wire = canvas(16)
    for x in range(16):
        col = STEEL[(1, 3, 4, 5, 4, 3, 2, 1)[x // 2]]
        for y in range(16):
            wire.putpixel((x, y), rgba(col))
    save(wire, "spring")
    collar = canvas(16)
    for y in range(16):
        col = PURP[6] if y < 3 else PURP[4] if y < 8 else PURP[2]
        for x in range(8):
            collar.putpixel((x, y), rgba(col))
    for y in range(4):
        for x in range(8, 16):
            collar.putpixel((x, y), rgba(PURP[7]))
    save(collar, "collar")


def textures() -> None:
    paint_shell()
    paint_caps()
    paint_eyes()


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------
OVERLAY_EPS = 0.05
OVERLAY_SEAM = round(OVERLAY_EPS * math.tan(math.pi / 16) + 0.004, 4)


def _uv(vals):
    return [round(clamp(v, 0.0, 16.0), 4) for v in vals]


def wrap(phi: float) -> float:
    return (phi + 101.25) % 360.0 - 101.25


def half_of(phi: float) -> str:
    return "front" if wrap(phi) < 78.75 else "back"


def slice_of(element: dict) -> int:
    h0 = element["from"][1] - CY
    return min(range(len(SLICES)), key=lambda k: abs(SLICES[k][0] - h0))


def egg_body():
    """The shared lathe egg, re-mapped so the painted maps wrap it, plus its glowing twin."""
    slabs = lathe(EGG_CENTER, EGG_PROFILE, "shell_front", sides=16, shade=False)
    shell, neon = [], []
    for e in slabs:
        k = slice_of(e)
        a = e.get("rotation", {}).get("angle", 0.0)
        faces, glow_faces = {}, {}
        for side, base in (("south", 0.0), ("east", 90.0), ("north", 180.0), ("west", 270.0)):
            if side not in e["faces"]:
                continue
            phi = wrap(base + a)
            half = half_of(phi)
            u0 = (phi - 11.25 - HALF[half]) / 180.0 * 16.0
            uv = _uv([u0, ROW0[k] / 2, u0 + 2.0, (ROW0[k] + ROWS[k]) / 2])
            faces[side] = {"uv": uv, "texture": "#shell_" + half}
            glow_faces[side] = {"uv": uv, "texture": "#neon_" + half}
        if k >= 4:
            faces["up"] = {"uv": cap_uv(k, True), "texture": "#caps"}
        if k <= 3:
            faces["down"] = {"uv": cap_uv(k, False), "texture": "#caps"}
        e["faces"] = faces
        shell.append(e)
        g = copy(e)
        axis, across = (0, 2) if "east" in glow_faces else (2, 0)
        g["from"][axis] = round(g["from"][axis] - OVERLAY_EPS, 4)
        g["to"][axis] = round(g["to"][axis] + OVERLAY_EPS, 4)
        g["from"][across] = round(g["from"][across] - OVERLAY_SEAM, 4)
        g["to"][across] = round(g["to"][across] + OVERLAY_SEAM, 4)
        g["faces"] = glow_faces
        g["light_emission"] = 15
        g["shade"] = False
        neon.append(g)
    return shell, neon


def frame_euler(x_axis, y_axis):
    z_axis = _cross(x_axis, y_axis)
    m = tuple((x_axis[i], y_axis[i], z_axis[i]) for i in range(3))
    return matrix_to_euler(m)


def ring_of(centre, n, right, apothem: float, length: float, sides: int, caps: bool, back: bool = False,
            tex: str = "eye_side") -> list[dict]:
    """A short prism along the eye's normal n, centred on `centre`."""
    parts = prism(centre, apothem, length, tex, sides=sides, shade=False)
    for e in parts:
        for side in list(e["faces"]):
            if side in ("up", "down"):
                if (caps and side == "up") or (back and side == "down"):
                    e["faces"][side] = {"uv": [12.25, 0.25, 12.75, 0.75], "texture": "#" + tex}
                else:
                    e["faces"].pop(side)
            else:
                e["faces"][side]["uv"] = [0, 0, 8, 16]
    ex, ey, ez = frame_euler(right, n)
    return turn(parts, x=ex, y=ey, z=ez, origin=centre)


def spring(eye: dict, axes):
    """A coiled steel spring popping the eye out of the shell, from a purple collar:
    (parts, the axes the eye sits on at the spring's end)."""
    base, n, up, right = axes
    sp = eye["spring"]
    a = _norm(_add(_add(n, right, sp["bend"][0]), up, sp["bend"][1]))
    u = _norm(_cross(a, (0, 1, 0)) if abs(a[1]) < 0.95 else _cross(a, (1, 0, 0)))
    v = _cross(a, u)
    start = _add(base, n, -0.35)
    length = sp["length"] + 0.35
    count = round(sp["turns"] * 8)
    pts = []
    for i in range(count + 1):
        f = i / count
        ang = TAU * sp["turns"] * f
        r = sp["radius"] * (0.8 if i in (0, count) else 1.0)
        pts.append(_add(_add(_add(start, a, length * f), u, r * math.cos(ang)), v, r * math.sin(ang)))
    parts = [bar(p0, p1, sp["wire"], sp["wire"], "spring", uv="full") for p0, p1 in zip(pts, pts[1:])]
    # a purple collar where the spring leaves the shell
    parts += ring_of(_add(base, n, 0.0), n, right, COLLAR_R, 0.5, 8, True, tex="collar")
    tip = _add(start, a, length)
    n2 = _norm(_add(sp["face"], a, 0.45))
    up2 = _norm(_add((0, 1, 0), n2, -n2[1]))            # the eye sits upright on its spring...
    right2 = _cross(up2, n2)
    r = math.radians(eye["roll"])                       # ...give or take a cheeky tilt
    up2, right2 = (_norm(_add(_add((0, 0, 0), up2, math.cos(r)), right2, math.sin(r))),
                   _norm(_add(_add((0, 0, 0), right2, math.cos(r)), up2, -math.sin(r))))
    return parts, (tip, n2, up2, right2)


def googly(eye: dict, axes) -> list[dict]:
    """One googly eye: a short plastic wall sunk into the shell (the big ones step in to a
    clear dome) and a flat face on top that carries the white and the rolling pupil."""
    extra = []
    if eye.get("spring"):
        extra, axes = spring(eye, axes)
    base, n, up, right = axes
    R, T = eye["R"], eye["T"]
    sides = 16 if R >= 1.5 else 8
    lift = R * max(abs(math.sin(math.radians(v))) for v in eye["tilt"])
    sink = R * R / (2 * max(2.0, profile_r(eye["h"]))) + 0.3 + lift
    if eye.get("spring"):
        sink = 0.05                                      # it floats on the spring: close its back
    parts = []
    if eye["dome"]:
        t1 = T * 0.5
        parts += ring_of(_add(base, n, (t1 - sink) / 2), n, right, R * 0.985, t1 + sink, sides, True)
        r_face = R * eye["dome"]
        parts += ring_of(_add(base, n, (t1 + T) / 2 - 0.03), n, right, r_face * 0.985, T - t1 + 0.06, sides, False)
    else:
        r_face = R
        parts += ring_of(_add(base, n, (T - sink) / 2), n, right, R * (0.985 if sides == 16 else 0.93), T + sink,
                         sides, False, back=bool(eye.get("spring")))
    x0, y0, s = eye["cell"]
    top = _add(base, n, T)
    face = box((top[0] - r_face, top[1] - r_face, top[2] - 0.01), (top[0] + r_face, top[1] + r_face, top[2] + 0.01),
               "eyes", faces={"south": ("eyes", [x0 / 2, y0 / 2, (x0 + s) / 2, (y0 + s) / 2])},
               skip=("north", "east", "west", "up", "down"), glow=10, shade=False)
    ex, ey, ez = frame_euler(right, up)
    turn(face, x=ex, y=ey, z=ez, origin=top)
    return extra + parts + [face]


def build():
    shell, neon = egg_body()
    eyes = []
    for eye, axes in zip(EYES, EYE_AXES):
        eyes += googly(eye, axes)
    return shell + eyes + neon


def transforms(parts) -> dict:
    d = display(KIND, parts, grip=GRIP, size=SIZE, gui_rotation=GUI_ROT, gui_span=15.4)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, 1)}, GRIP, "fist", 0.45 * SIZE)
    # First person: the big eye and its spiral turned toward the camera, held low on the right.
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.3, 0.12, 1)}, GRIP, (0.47, -0.36, -0.88),
                                       0.5 * SIZE, pose=None)
    # A thrown egg is drawn through "ground", model +Z toward the viewer: the eye leads.
    d["ground"] = fit(parts, (0, 0, 0), 7.5, lift=2.0)
    return d


def models() -> dict:
    parts = build()
    return {"main": model(parts, transforms(parts))}
