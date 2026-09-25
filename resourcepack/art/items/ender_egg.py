"""Ender Egg: a deep-teal ender-pearl egg with an Eye of Ender glaring from its face (Egg set).

The shared egg shell (lathe of EGG_PROFILE, 16 sides) is a glossy teal pearl. Its UVs follow
the egg round (azimuth -> u, height -> v, four texels per facet), so one animated map per
half wraps it seamlessly: dark teal marbling winds round a whirlpool on the back of the
egg, each arm trailed by an opal sheen, under pale end-stone speckles that stay put. A
hair-thin unshaded twin of the shell carries the light the pearl throws back (a glossy
spot on the upper-left shoulder, a rim of light, the sheen riding the arms and pinpoint
twinkles), so it reads bright even where the faces are dimmed.

On the front a big almond eye glows from a dark socket: five facets just proud of the
shell carry a front-projected yellow-green iris with a slit pupil, two stacked discs bulge
a cornea over it, and an end-stone bezel frames it like the End portal frame: a heavy,
hooded upper lid with a raised crest, a thinner lower lid that catches the eye's light,
and pointed corners. Violet ender particles twinkle in the empty corners of the slot.

Animation (one 3.2 s loop, every texture in step): the marbling swirls half a turn round
the whirlpool while the sheen and twinkles ride it; the eye breathes, its slit narrowing
as the glow peaks, while its fibres turn and a glint sweeps round the iris; the particles
flare and drift up in turn.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, box, canvas, copy, display, display_matrix, fit, lathe, matrix_to_euler, model, place,
                     rgba, save, save_animation, turn, wave)

ID = "ender_egg"
NAME = "Ender Egg"
KIND = "item"
COUNTERPART = "item/egg"

# --------------------------------------------------------------------------------------
# Palettes, darkest -> lightest (shadows drift to blue, lights to mint)
# --------------------------------------------------------------------------------------
SHELL = ["#041620", "#062a36", "#073c48", "#07515a", "#05686e", "#028184", "#009a9a", "#00b3b0", "#00c9c4",
         "#1adcd2", "#4ae9dd", "#80f3e7", "#b4fbf1", "#e2fffa", "#ffffff"]
NACRE = ["#1f8fb0", "#3fb2d4", "#6fd0ee", "#a4e6fb", "#d6f6ff"]      # the opal sheen trailing each swirl
END = ["#4e4a30", "#77734a", "#a39f6c", "#c5be8b", "#d5da94", "#e6eea8", "#f6fabd"]
IRIS = ["#06201a", "#0c3a26", "#15592f", "#227a33", "#3d9a33", "#63b834", "#8fd33a", "#bde84e", "#e2f76e",
        "#f8ffb8", "#ffffff"]
PUPIL = ["#000604", "#03120c", "#082418"]
SCLERA = ["#010d0c", "#03201c", "#063328", "#0b4737", "#146149", "#1f7d58"]
LID = ["#000908", "#021413", "#04201f", "#062e2c", "#0a3f3b", "#11534c", "#1c6b61", "#2f8878", "#4fa894"]
RIM = ["#16503a", "#2a7340", "#48983f", "#79bd46", "#b6e060"]
PORTAL = ["#4a1070", "#7a24b0", "#b04ae8", "#dd8cff", "#f6d6ff", "#ffffff"]   # ender particles

# --------------------------------------------------------------------------------------
# The shared egg body
# --------------------------------------------------------------------------------------
EGG_PROFILE = [(0, 1.4), (0.7, 3.2), (1.8, 4.3), (3.3, 4.9), (5.0, 5.0), (6.8, 4.7), (8.4, 4.0),
               (9.8, 3.0), (10.9, 1.8), (11.5, 0.6)]
EGG_CENTER = (8.0, 2.2, 8.0)
Y0 = EGG_CENTER[1]
EGG_TOP = EGG_PROFILE[-1][0]
SLICES = [(h0, h1, (r0 + r1) / 2) for (h0, r0), (h1, r1) in zip(EGG_PROFILE, EGG_PROFILE[1:])]
RADII = [r for _, _, r in SLICES]
TAN = math.tan(math.pi / 16)

# The shell map: two halves (8 facets of 22.5 degrees each, 4 texels per facet), every
# slice a whole number of rows, so the painted pattern runs on across the facets.
N = 32
FRAMES, FRAMETIME = 32, 2
ROWS = (2, 3, 4, 5, 5, 4, 4, 3, 2)                        # rows per slice, bottom to top
ROW0 = [sum(ROWS[k + 1:]) for k in range(len(ROWS))]      # first row (from the top) of a slice
HALF = {"front": -101.25, "back": 78.75}                  # azimuth at u = 0; each half spans 180
K_LEDGE = 1.6                                             # radial ledge map: uv units per model unit

# --------------------------------------------------------------------------------------
# The eye (front, azimuth 0). Coordinates in the eye plane: x right, y up, units,
# measured from the eye's centre and projected straight onto the egg from the front.
# --------------------------------------------------------------------------------------
EYE_H = 5.9                                     # egg height of the eye's centre
EYE_A, EYE_UP, EYE_LO, EYE_LIFT, EYE_P = 3.15, 2.0, 1.72, 0.18, 0.72
IRIS_R = 1.95
PUPIL_H, PUPIL_W = 1.8, (0.22, 0.6)             # slit half-height, half-width (narrow, wide)
MARGIN = 0.22                                   # the eye paint runs this far under the lids
R_V = SLICES[3][2] + 0.12                       # the eye surface: facets just proud of the shell
V_LO, V_HI = -2.45, 2.45                        # its height range round the centre
EYE_DENSITY = 3.0                               # eye texels per unit
LENSES = ((1.0, 0.95, 0.16, (0, 0)), (0.6667, 0.56, 0.3, (8, 0)))  # half-size, radius, rise, atlas texel

SIZE = 1.4
GRIP = (8, 5.0, 8)
GUI_ROT = (8, 12, 0)


# --------------------------------------------------------------------------------------
# Small helpers
# --------------------------------------------------------------------------------------

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
    return tuple(a[i] + b[i] * k for i in range(len(a)))


def _uv(vals):
    return [round(clamp(v, 0.0, 16.0), 4) for v in vals]


BAYER = (0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)


def threshold(c: int, r: int) -> float:
    return (BAYER[(r % 4) * 4 + c % 4] + 0.5) / 16.0 - 0.5


def blip(t: float, phase: float, width: float) -> float:
    d = abs(t - phase) % 1.0
    d = min(d, 1.0 - d)
    return max(0.0, 1.0 - d / width)


# --------------------------------------------------------------------------------------
# Shell geometry
# --------------------------------------------------------------------------------------

def wrap(phi: float) -> float:
    return (phi + 101.25) % 360.0 - 101.25


def half_of(phi: float) -> str:
    return "front" if wrap(phi) < 78.75 else "back"


def row_slice(r: int) -> int:
    return next(k for k in range(len(SLICES)) if ROW0[k] <= r < ROW0[k] + ROWS[k])


def texel_geo(half: str, c: int, r: int):
    """(azimuth, height, x, z) of the centre of shell texel (c, r), on its flat facet;
    x and z are measured from the egg's axis."""
    phi_f = HALF[half] + 11.25 + 22.5 * (c // 4)
    k = row_slice(r)
    h0, h1, rad = SLICES[k]
    h = h1 - (r - ROW0[k] + 0.5) / ROWS[k] * (h1 - h0)
    off = (((c % 4) + 0.5) / 2 - 1) * rad * TAN
    p = math.radians(phi_f)
    x = rad * math.sin(p) + off * math.cos(p)
    z = rad * math.cos(p) - off * math.sin(p)
    return math.degrees(math.atan2(x, z)), h, x, z


def profile_r(h: float) -> float:
    h = clamp(h, 0.0, EGG_TOP)
    for (h0, r0), (h1, r1) in zip(EGG_PROFILE, EGG_PROFILE[1:]):
        if h0 <= h <= h1:
            return r0 + (r1 - r0) * (h - h0) / (h1 - h0)
    return EGG_PROFILE[-1][1]


def profile_slope(h: float, window: float = 0.7) -> float:
    a, b = clamp(h - window, 0.0, EGG_TOP), clamp(h + window, 0.0, EGG_TOP)
    return (profile_r(b) - profile_r(a)) / (b - a)


_MIDS = [((h0 + h1) / 2, r) for h0, h1, r in SLICES]


def face_r(h: float) -> float:
    """Radius of the stepped shell's facets, smoothed through the middle of each slice."""
    if h <= _MIDS[0][0]:
        return _MIDS[0][1]
    for (h0, r0), (h1, r1) in zip(_MIDS, _MIDS[1:]):
        if h0 <= h <= h1:
            return r0 + (r1 - r0) * (h - h0) / (h1 - h0)
    return _MIDS[-1][1]


def normal_at(phi: float, h: float):
    p = math.radians(phi)
    return _norm((math.sin(p), -profile_slope(h), math.cos(p)))


def shell_point(x: float, y: float):
    """The point on the (smoothed) shell seen at eye-plane (x, y), and its normal."""
    h = EYE_H + y
    rad = face_r(h)
    phi = math.degrees(math.asin(clamp(x / rad, -0.999, 0.999)))
    p = math.radians(phi)
    return (8 + rad * math.sin(p), Y0 + h, 8 + rad * math.cos(p)), normal_at(phi, h)


# --------------------------------------------------------------------------------------
# The eye outline and the lid bands (2D, eye plane)
# --------------------------------------------------------------------------------------

def almond(x: float):
    """(upper y, lower y) of the eye opening at x."""
    q = max(0.0, 1.0 - (x / EYE_A) ** 2)
    lift = EYE_LIFT * (x / EYE_A) ** 2
    return lift + EYE_UP * q ** EYE_P, lift - EYE_LO * q ** EYE_P


def _outline():
    xs = [EYE_A * math.sin(math.pi * (i / 60 - 0.5)) for i in range(61)]   # dense near the corners
    upper = [(x, almond(x)[0]) for x in xs]
    lower = [(x, almond(x)[1]) for x in reversed(xs)]
    return upper + lower[1:]


OUTLINE = _outline()


def _seg_dist(p, a, b) -> float:
    ax, ay = b[0] - a[0], b[1] - a[1]
    L = ax * ax + ay * ay
    s = 0.0 if L == 0 else clamp(((p[0] - a[0]) * ax + (p[1] - a[1]) * ay) / L)
    return math.hypot(p[0] - a[0] - ax * s, p[1] - a[1] - ay * s)


def open_dist(x: float, y: float) -> float:
    """Signed distance to the eye opening's outline (negative inside)."""
    d = min(_seg_dist((x, y), a, b) for a, b in zip(OUTLINE, OUTLINE[1:]))
    inside = abs(x) < EYE_A and almond(x)[1] < y < almond(x)[0]
    return -d if inside else d


def lid_width(upper: bool, x: float):
    """(overlap onto the eye, reach away from it) of a lid at x."""
    q = max(0.0, 1.0 - (x / EYE_A) ** 2)
    if upper:
        return 0.34 + 0.06 * q, 0.5 + 0.55 * q
    return 0.24, 0.4 + 0.22 * q


def _lid_bands(upper: bool, count: int):
    """Equal-length pieces of a lid along the outline: 2D centre, tangent, outward normal,
    half-length, half-width."""
    xs = [EYE_A * math.sin(math.pi * (i / 400 - 0.5)) for i in range(401)]
    pts = [(x, almond(x)[0 if upper else 1]) for x in xs]
    acc = [0.0]
    for a, b in zip(pts, pts[1:]):
        acc.append(acc[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    total = acc[-1]

    def at(s):
        for i in range(len(acc) - 1):
            if acc[i] <= s <= acc[i + 1]:
                f = (s - acc[i]) / max(1e-9, acc[i + 1] - acc[i])
                return (pts[i][0] + (pts[i + 1][0] - pts[i][0]) * f, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * f)
        return pts[-1]

    bands = []
    for j in range(count):
        a, b, m = at(total * j / count), at(total * (j + 1) / count), at(total * (j + 0.5) / count)
        t2 = _norm((b[0] - a[0], b[1] - a[1]))
        n2 = (-t2[1], t2[0])
        if (n2[1] < 0) == upper:
            n2 = (-n2[0], -n2[1])
        inner, outer = lid_width(upper, m[0])
        c2 = _add(m, n2, (outer - inner) / 2)
        bands.append((c2, t2, n2, math.hypot(b[0] - a[0], b[1] - a[1]) / 2, (inner + outer) / 2))
    return bands


UPPER = _lid_bands(True, 16)
LOWER = _lid_bands(False, 14)
BANDS = UPPER + LOWER


def band_dist(x: float, y: float) -> float:
    """How far (x, y) lies outside the nearest lid band (<= 0 under a lid)."""
    best = 99.0
    for c2, t2, n2, hl, hw in BANDS:
        dx, dy = x - c2[0], y - c2[1]
        a = abs(dx * t2[0] + dy * t2[1]) - hl
        b = abs(dx * n2[0] + dy * n2[1]) - hw
        best = min(best, max(a, b))
    return best


# --------------------------------------------------------------------------------------
# Light baked onto the pearl, and the marbling
# --------------------------------------------------------------------------------------
KEY = _norm((-0.55, 0.75, 0.6))         # key light: upper left, in front
BACK = _norm((0.5, 0.5, -0.7))          # a softer light over the back
FILL = _norm((0.75, -0.3, 0.2))         # bounce light low on the right
SPEC = _norm((-0.6, 0.6, 0.55))         # the glossy spot on the upper-left shoulder


def _gui_view():
    """The model direction that faces the inventory camera (display rotation Rx*Ry*Rz)."""
    m = display_matrix(GUI_ROT)
    return _norm(tuple(m[2][i] for i in range(3)))


VIEW = _gui_view()                      # where the inventory camera looks from


def pearl(phi: float, h: float) -> float:
    """Baked light as a SHELL ramp position."""
    n = normal_at(phi, h)
    key = clamp((_dot(n, KEY) + 0.3) / 1.3)
    back = clamp((_dot(n, BACK) + 0.25) / 1.25)
    bounce = max(0.0, _dot(n, FILL))
    rim = clamp((0.5 - _dot(n, VIEW)) / 0.5) * clamp((_dot(n, KEY) + 0.35) / 0.8)
    g = _dot(n, SPEC)
    spec = 2.2 if g > 0.975 else 1.4 if g > 0.945 else 0.7 if g > 0.9 else 0.0
    return 4.0 + 4.6 * key + 1.4 * back * (1 - key) + 1.2 * bounce + 2.2 * rim + spec


TAU = 2 * math.pi
SWIRL = {"arms": 2, "wind": 0.6, "h": 5.8, "spin": 1}   # a whirlpool centred on the back of the egg
WARP = ((0.7, 2, 1.1, 1, 0.5), (0.35, -3, 2.0, -1, 2.0))


def marble(phi: float, h: float, t: float) -> float:
    """Phase of the marbling: spiral arms wound round a whirlpool on the back of the egg,
    turning half a turn a loop, loosened by a gentle warp. Every term is periodic in the
    azimuth (the arm count is whole) and in t, so the map wraps and the loop is seamless."""
    dphi = math.radians(((phi - 180.0) + 180.0) % 360.0 - 180.0)
    x, y = face_r(h) * dphi, h - SWIRL["h"]
    s, v = phi / 360.0, h / EGG_TOP
    warp = sum(a * math.sin(TAU * (k * s + l * v + m * t) + p) for a, k, l, m, p in WARP)
    return SWIRL["arms"] * math.atan2(y, x) + SWIRL["wind"] * math.hypot(x, y) - TAU * SWIRL["spin"] * t + warp


def marbling(p: float):
    """(ramp offset, is sheen, wrapped phase) across one arm: a dark core with soft edges,
    then a pale opal sheen trailing it."""
    d = (p + math.pi) % TAU - math.pi
    if abs(d) < 0.55:
        return -3.0, False, d
    if abs(d) < 1.0:
        return -1.5, False, d
    if -1.6 < d < -1.15:
        return 0.0, True, d
    return 0.0, False, d


# --------------------------------------------------------------------------------------
# Static per-texel data
# --------------------------------------------------------------------------------------

def _shell_geo():
    geo = {}
    for half in ("front", "back"):
        rows = []
        for r in range(N):
            row = []
            for c in range(N):
                phi, h, x, z = texel_geo(half, c, r)
                state, shadow = 0, 0.0
                if z > 0.5:
                    y = h - EYE_H
                    if open_dist(x, y) < MARGIN or band_dist(x, y) <= 0.0:
                        state = 1
                    else:
                        bd = band_dist(x, y)
                        if bd < 0.45:
                            shadow = 1.8 * (1 - bd / 0.45)
                n = normal_at(phi, h)
                rim = clamp((0.5 - _dot(n, VIEW)) / 0.5) * clamp((_dot(n, KEY) + 0.35) / 0.8)
                row.append((phi, h, pearl(phi, h) - shadow, state, _dot(n, SPEC), rim))
            rows.append(row)
        geo[half] = rows
    return geo


def _speckles(geo):
    """End-stone flecks: sparse pale specks in ones and twos, clear of the eye."""
    out = {}
    for half, seed in (("front", 17), ("back", 23)):
        rng = random.Random(seed)
        taken = {}
        tries = 0
        while len(taken) < 16 and tries < 600:
            tries += 1
            c, r = rng.randrange(N), rng.randrange(1, N - 1)
            if geo[half][r][c][3] or any(abs(c - a) < 3 and abs(r - b) < 3 for a, b in taken):
                continue
            phi, h, x, z = texel_geo(half, c, r)
            if half == "front" and z > 0.5 and band_dist(x, h - EYE_H) < 0.8:
                continue
            cells = [(c, r)]
            roll = rng.random()
            if roll < 0.3 and c + 1 < N:
                cells.append((c + 1, r))
            elif roll < 0.42:
                cells.append((c, r + 1))
            for cell in cells:
                taken[cell] = cell == (c, r)
        out[half] = taken
    return out


def _eye_geo():
    geo = []
    for j in range(32):
        row = []
        for i in range(32):
            x = (i + 0.5 - 16) / EYE_DENSITY
            y = (16 - j - 0.5) / EYE_DENSITY
            if abs(x) > EYE_A + 0.6 or abs(y) > 2.6:
                row.append(None)
                continue
            d = open_dist(x, y)
            row.append(None if d > MARGIN else (x, y, d, math.hypot(x, y), math.atan2(y, x)))
        geo.append(row)
    return geo


SHELL_GEO = _shell_geo()
SPECKS = _speckles(SHELL_GEO)
EYE_GEO = _eye_geo()


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def shell_frame(half: str, t: float):
    img = canvas(N)
    px = img.load()
    specks = SPECKS[half]
    for r in range(N):
        for c in range(N):
            phi, h, light, state = SHELL_GEO[half][r][c][:4]
            if state:
                px[c, r] = rgba(LID[1])
                continue
            if (c, r) in specks:
                k = int(clamp(round((light - 4.0) / 7.0 * 3.0) + 3 + (1 if specks[(c, r)] else 0), 2, 6))
                px[c, r] = rgba(END[k])
                continue
            offset, sheen, _ = marbling(marble(phi, h, t))
            pos = light + offset * (1 + 0.12 * max(0.0, light - 8.0)) + threshold(c, r) * 0.3
            if sheen:
                px[c, r] = rgba(NACRE[int(clamp((pos - 6.0) / 1.6, 0, len(NACRE) - 1))])
                continue
            px[c, r] = rgba(SHELL[int(clamp(pos, 1, len(SHELL) - 1))])
    return img


def _twinkles():
    """Points on the pearl that catch the light in turn: (half, column, row, phase)."""
    rng = random.Random(31)
    out = []
    for half in ("front", "back"):
        n = 0
        while n < 5:
            c, r = rng.randrange(1, N - 1), rng.randrange(3, N - 4)
            g = SHELL_GEO[half][r][c]
            if g[3] or g[2] < 7.0 or (c, r) in SPECKS[half]:
                continue
            _, h, x, z = texel_geo(half, c, r)
            if half == "front" and z > 0.5 and band_dist(x, h - EYE_H) < 1.0:
                continue
            out.append((half, c, r, ((len(out) * 0.382) + 0.05) % 1.0))
            n += 1
    return out


TWINKLES = _twinkles()
WHITE = (255, 255, 255)
MINT = (190, 255, 240)
SKY = (170, 230, 255)


def gloss_frame(half: str, t: float):
    """The unshaded gloss skin over the shell: clear except the light the pearl throws
    back, so it reads bright even where the shell's faces are dimmed: the glossy spot on
    the upper-left shoulder, a rim of light round the lit side, the opal sheen riding
    each swirl arm, and pinpoint twinkles."""
    img = canvas(N)
    px = img.load()
    for r in range(N):
        for c in range(N):
            phi, h, light, state, g, rim = SHELL_GEO[half][r][c]
            if state or (c, r) in SPECKS[half]:
                continue
            a, col = 0, MINT
            if g > 0.993:
                a, col = 235, WHITE
            elif g > 0.982:
                a, col = 140, WHITE
            elif g > 0.955:
                a = 55
            if rim > 0.8:
                a = max(a, round(40 + 70 * (rim - 0.8) / 0.2))
            d = marbling(marble(phi, h, t))[2]
            if -1.52 < d < -1.22 and light > 6.0:
                a, col = max(a, round(60 + 12 * (light - 6.0))), SKY
            if a:
                px[c, r] = (*col, min(255, a))
    for th, c, r, phase in TWINKLES:
        if th != half:
            continue
        b = blip(t, phase, 0.08)
        if b <= 0.05:
            continue
        px[c, r] = (*WHITE, round(120 + 135 * b))
        if b > 0.45:
            for dc, dr in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if 0 <= c + dc < N and 0 <= r + dr < N:
                    old = px[c + dc, r + dr]
                    px[c + dc, r + dr] = (*WHITE, max(old[3], round(150 * (b - 0.35))))
    return img


def eye_colour(x, y, d, ri, th, t, i, j):
    thr = threshold(i, j)
    pulse = wave(t)
    if d > 0.0:
        return SCLERA[0]
    if ri > IRIS_R:
        k = 1.4 + 2.6 * math.exp(-(ri - IRIS_R) / 0.4) * (0.5 + 0.5 * pulse)
        if d > -0.3:
            k -= 1.0
        return SCLERA[int(clamp(k + thr * 0.4, 0, len(SCLERA) - 1))]
    w0 = PUPIL_W[1] + (PUPIL_W[0] - PUPIL_W[1]) * pulse          # the slit narrows as the glow peaks
    half_w = w0 * max(0.0, 1 - (y / PUPIL_H) ** 2) ** 0.6
    ax = abs(x)
    if ax < half_w:
        return PUPIL[0] if ax < half_w - 0.3 else PUPIL[1]
    rr = ri / IRIS_R
    if rr > 0.86:
        lvl = 1.3 + 0.9 * pulse                                  # the dark limbal ring
    else:
        lvl = 3.9 + 3.3 * (1 - rr) ** 1.2 + 1.4 * pulse
        fibre = math.cos(12 * th + 2.5 * rr - TAU * t)          # iris fibres, turning slowly
        lvl += 0.75 if fibre > 0.45 else -0.75 if fibre < -0.55 else 0.0
        arc = (th - (1.9 - TAU * t) + math.pi) % TAU - math.pi  # a glint sweeping round the iris
        if rr > 0.3:
            lvl += 1.9 * math.exp(-(arc / 0.42) ** 2)
    if ax - half_w < 0.34:
        lvl = max(lvl, 7.6 + 1.6 * pulse)                       # a hot ring hugging the slit
    if y > 1.0:
        lvl -= 1.8 * clamp((y - 1.0) / 0.5)                     # shade under the brow
    lvl += thr * 0.3
    return IRIS[int(clamp(lvl, 0, len(IRIS) - 1))]


def eye_frame(t: float):
    img = canvas(32)
    px = img.load()
    for j in range(32):
        for i in range(32):
            g = EYE_GEO[j][i]
            if g is None:
                continue
            px[i, j] = rgba(eye_colour(*g, t, i, j))
    # Glints on the cornea, up and left of the pupil (texel = column, row of the eye atlas).
    for (i, j), col in (((14, 13), "#ffffff"), ((13, 13), "#eaffd0"), ((14, 14), "#d8f7c0"), ((18, 19), "#e8ffd8")):
        px[i, j] = rgba(col)
    # The lens copies: the same texels, cut round, for the stacked cornea discs.
    for hs, radius, _, (ox, oy) in LENSES:
        n = round(hs * 2 * EYE_DENSITY)
        i0 = 16 - n // 2
        for dj in range(n):
            for di in range(n):
                x = (i0 + di + 0.5 - 16) / EYE_DENSITY
                y = (16 - (i0 + dj) - 0.5) / EYE_DENSITY
                if math.hypot(x, y) <= radius:
                    px[ox + di, oy + dj] = px[i0 + di, i0 + dj]
    return img


# Ender particles hovering in the empty corners of the slot: (azimuth, height, distance
# from the axis, size, phase). Each lives for part of the loop, flaring as it drifts up.
MOTES = ((-72.0, 10.1, 4.3, 2.0, 0.0), (66.0, 9.6, 4.5, 1.7, 0.5), (-92.0, 1.9, 5.0, 1.35, 0.27),
         (96.0, 2.4, 5.2, 1.35, 0.77), (155.0, 8.2, 5.2, 1.7, 0.14), (-140.0, 3.6, 5.6, 1.5, 0.64))
MOTE_LIFE = 0.45


def mote_frame(t: float):
    """Each particle's 8x8 cell: a violet spark that swells into a four-point twinkle with
    a white heart as it drifts up its cell, then shrinks away."""
    img = canvas(32)
    px = img.load()
    for i, (_, _, _, _, phase) in enumerate(MOTES):
        u0, v0 = (i % 4) * 8, (i // 4) * 8
        age = ((t - phase) % 1.0) / MOTE_LIFE
        if age >= 1.0:
            continue
        b = math.sin(math.pi * age) ** 0.8            # brightness over its life
        cx, cy = u0 + 4, v0 + 5 - round(1.6 * age)     # drifts up its cell
        arm = 3 if b > 0.82 else 2 if b > 0.55 else 1 if b > 0.3 else 0
        core = 5 if b > 0.82 else 4 if b > 0.55 else 3 if b > 0.3 else 2

        def put(x, y, idx):
            if u0 <= x < u0 + 8 and v0 <= y < v0 + 8:
                px[x, y] = rgba(PORTAL[max(0, idx)])

        for k in range(1, arm + 1):
            for dx, dy in ((k, 0), (-k, 0), (0, k), (0, -k)):
                put(cx + dx, cy + dy, core - k)
        if b > 0.55:
            for dx, dy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
                put(cx + dx, cy + dy, 1 if b < 0.82 else 2)
        put(cx, cy, core)
    return img


LID_SHADES = 8
# End stone, hue-shifted: the crease against the eye is the socket's dark teal-green.
END_LID = ["#1d3a30", "#646240", "#908d5c", "#b8b37e", "#d6d994", "#e8eea8", "#f5fabb", "#fdffd9"]
LID_ZONES = {"upper": 0, "crest": 1, "lower": 2, "corner": 3}      # 8-texel column blocks of lid_top


def paint_lids() -> None:
    """End-stone lids. lid_top holds, per 8-texel column block (LID_ZONES), eight lighting
    variants (darkest first), 4 texels each from the outer edge to the edge on the eye:
    the upper lid ends in a shadowed crease, the lower lid catches the eye's light."""
    top = canvas(32)
    px = top.load()
    rng = random.Random(9)
    for s in range(LID_SHADES):
        k = round(s * 3 / (LID_SHADES - 1)) - 2                      # -2 .. +1
        e = lambda i: END_LID[max(1, min(len(END_LID) - 1, i + k))]
        zones = {
            "upper": [e(4), e(6), e(5), END_LID[0]],
            "crest": [e(5), e(7), e(6), e(4)],
            "lower": [e(4), e(6), e(5), RIM[2]],
            "corner": [e(5), e(6), e(6), e(5)],
        }
        for name, rows in zones.items():
            u = 8 * LID_ZONES[name]
            for dy in range(4):
                for x in range(8):
                    px[u + x, 4 * s + dy] = rgba(rows[dy])
        for x in range(32):                                          # end-stone pits
            if rng.random() < 0.2:
                y = 4 * s + 1 + rng.randrange(2)
                px[x, y] = rgba(e(4) if x < 8 or x >= 16 else e(5))
    save(top, "lid_top")
    inner = canvas(16)
    for y, col in enumerate([RIM[3], RIM[2], RIM[1], LID[2], LID[1], LID[1], LID[0], LID[0]]):
        for yy in (2 * y, 2 * y + 1):
            for x in range(16):
                inner.putpixel((x, yy), rgba(col))
    save(inner, "lid_inner")
    side = canvas(16)
    for y, col in enumerate([END_LID[6], END_LID[5], END_LID[4], END_LID[4], END_LID[3], END_LID[3],
                             END_LID[2], END_LID[2]]):
        for yy in (2 * y, 2 * y + 1):
            for x in range(16):
                side.putpixel((x, yy), rgba(col))
    for x in range(0, 14, 5):
        side.putpixel((x, 5), rgba(END_LID[2]))
        side.putpixel((x + 2, 8), rgba(END_LID[1]))
    save(side, "lid_side")


def ledge_colour(rho: float, upper: bool) -> str:
    """Radial ledge maps: every overlapping slab top samples the same texel, so the rings
    never flicker. The tops catch full light, so they sit a step or two under the sides;
    each ring darkens against the wall above it and brightens at its rounded outer lip."""
    if upper:
        ks = range(3, len(SLICES))
        for k in ks:
            outer = RADII[k]
            inner = RADII[k + 1] if k + 1 < len(SLICES) else 0.0
            if inner <= rho <= outer + 0.05:
                f = (rho - inner) / max(1e-6, outer - inner)
                base = {3: 6, 4: 6, 5: 6, 6: 7, 7: 7, 8: 8}[k]
                if k == len(SLICES) - 1:
                    return SHELL[9 if rho < 0.45 else 8]
                return SHELL[base - 1 if f < 0.28 else base + 1 if f > 0.78 else base]
        return SHELL[6]
    return SHELL[4] if rho <= RADII[0] else SHELL[5]             # the undersides: a dim, even teal


def paint_ledges() -> None:
    for name, upper, size in (("ledge_up", True, 64), ("ledge_down", False, 32)):
        img = canvas(size)
        scale = size / 16
        for py in range(size):
            for px_ in range(size):
                rho = math.hypot((px_ + 0.5) / scale - 8, (py + 0.5) / scale - 8) / K_LEDGE
                img.putpixel((px_, py), rgba(ledge_colour(rho, upper)))
        save(img, name)


def textures() -> None:
    for half in ("front", "back"):
        save_animation(animate(lambda t, half=half: shell_frame(half, t), FRAMES), "shell_" + half,
                       frametime=FRAMETIME)
        save_animation(animate(lambda t, half=half: gloss_frame(half, t), FRAMES), "gloss_" + half,
                       frametime=FRAMETIME)
    save_animation(animate(eye_frame, FRAMES), "eye", frametime=FRAMETIME)
    save_animation(animate(mote_frame, FRAMES), "motes", frametime=FRAMETIME)
    paint_lids()
    paint_ledges()


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def slice_index(e: dict) -> int:
    h0 = e["from"][1] - Y0
    return min(range(len(SLICES)), key=lambda k: abs(SLICES[k][0] - h0))


def radial_uv(e: dict) -> list[float]:
    (x0, _, z0), (x1, _, z1) = e["from"], e["to"]
    hx, hz = (x1 - x0) / 2 * K_LEDGE, (z1 - z0) / 2 * K_LEDGE
    return _uv([8 - hx, 8 - hz, 8 + hx, 8 + hz])


def shell() -> list[dict]:
    slabs = lathe(EGG_CENTER, EGG_PROFILE, "shell_front", sides=16)
    for e in slabs:
        k = slice_index(e)
        a = e.get("rotation", {}).get("angle", 0.0)
        faces = {}
        for side, base in (("south", 0.0), ("east", 90.0), ("north", 180.0), ("west", 270.0)):
            if side not in e["faces"]:
                continue
            phi = wrap(base + a)
            half = half_of(phi)
            u0 = (phi - 11.25 - HALF[half]) / 180.0 * 16.0
            faces[side] = {"uv": _uv([u0, ROW0[k] / 2, u0 + 2.0, (ROW0[k] + ROWS[k]) / 2]),
                           "texture": "#shell_" + half}
        if k >= 3:
            faces["up"] = {"uv": radial_uv(e), "texture": "#ledge_up"}
        if k <= 3:
            faces["down"] = {"uv": radial_uv(e), "texture": "#ledge_down"}
        e["faces"] = faces
    return slabs


GLOSS_EPS = 0.04
GLOSS_SEAM = round(GLOSS_EPS * TAN + 0.004, 4)


def gloss_skin(slabs) -> list[dict]:
    """A hair-thin unshaded twin of the shell carrying gloss_front/back (same UVs)."""
    out = []
    for e in slabs:
        g = copy(e)
        sides = {k: v for k, v in g["faces"].items() if k in ("north", "south", "east", "west")}
        axis, across = (0, 2) if "east" in sides else (2, 0)
        g["from"][axis] = round(g["from"][axis] - GLOSS_EPS, 4)
        g["to"][axis] = round(g["to"][axis] + GLOSS_EPS, 4)
        g["from"][across] = round(g["from"][across] - GLOSS_SEAM, 4)
        g["to"][across] = round(g["to"][across] + GLOSS_SEAM, 4)
        for face in sides.values():
            face["texture"] = face["texture"].replace("#shell_", "#gloss_")
        g["faces"] = sides
        g["shade"] = False
        out.append(g)
    return out


def eu(x: float) -> float:
    return 8 + x * EYE_DENSITY / 2


def ev(y: float) -> float:
    return 8 - y * EYE_DENSITY / 2


def eye_surface() -> list[dict]:
    """Five glowing facets just proud of the shell carry the eye; each facet's texture is the
    eye as seen straight from the front, so it reads undistorted in the slot and in flight."""
    parts = []
    hw = R_V * TAN
    rc = R_V / math.cos(math.pi / 16)
    for j in range(-2, 3):
        x0 = rc * math.sin(math.radians(22.5 * j - 11.25))
        x1 = rc * math.sin(math.radians(22.5 * j + 11.25))
        e = box((8 - hw, Y0 + EYE_H + V_LO, 8 + R_V - 0.01), (8 + hw, Y0 + EYE_H + V_HI, 8 + R_V), "eye",
                faces={"south": ("eye", _uv([eu(x0), ev(V_HI), eu(x1), ev(V_LO)]))},
                skip=("north", "east", "west", "up", "down"), glow=15, shade=False)
        if j:
            turn(e, 22.5 * j, "y", (8, Y0 + EYE_H, 8))
        parts.append(e)
    for hs, _, rise, (ox, oy) in LENSES:
        n = round(hs * 2 * EYE_DENSITY)
        z = 8 + R_V + rise
        parts.append(box((8 - hs, Y0 + EYE_H - hs, z - 0.01), (8 + hs, Y0 + EYE_H + hs, z), "eye",
                         faces={"south": ("eye", _uv([ox / 2, oy / 2, (ox + n) / 2, (oy + n) / 2]))},
                         skip=("north", "east", "west", "up", "down"), glow=15, shade=False))
    return parts


def oriented(center, x_axis, y_axis, size, tex, faces=None, **kw) -> dict:
    """A box of `size` (along its own x, y, z) centred on `center`, turned so its x and y
    axes point along x_axis and y_axis (z = x cross y)."""
    z_axis = _cross(x_axis, y_axis)
    m = tuple((x_axis[i], y_axis[i], z_axis[i]) for i in range(3))
    sx, sy, sz = size
    e = box((center[0] - sx / 2, center[1] - sy / 2, center[2] - sz / 2),
            (center[0] + sx / 2, center[1] + sy / 2, center[2] + sz / 2), tex, faces=faces, **kw)
    ex, ey, ez = matrix_to_euler(m)
    return turn(e, x=ex, y=ey, z=ez, origin=center)


SINK = 0.3


def visor_r(p) -> float:
    """Distance from the axis of the eye surface at the azimuth of point p."""
    phi = math.degrees(math.atan2(p[0] - 8, p[2] - 8))
    d = phi - 22.5 * round(phi / 22.5)
    return R_V / math.cos(math.radians(d))


def lid_box(c2, t2, n2, hl, hw, upper: bool, j: int, crest: bool = False) -> dict:
    """One piece of a lid: a box laid on the shell over the band (c2, t2, n2, hl, hw),
    leaning over the eye, tall enough that its inner top edge clears the eye surface."""
    p, n = shell_point(*c2)
    a3, _ = shell_point(*_add(c2, t2, -hl))
    b3, _ = shell_point(*_add(c2, t2, hl))
    i3, _ = shell_point(*_add(c2, n2, -hw))          # the edge over the eye
    o3, _ = shell_point(*_add(c2, n2, hw))
    tangent = tuple(b3[i] - a3[i] for i in range(3))
    length = math.sqrt(_dot(tangent, tangent)) * 1.22
    width = math.dist(i3, o3)
    x_axis = _norm(_add(tangent, n, -_dot(tangent, n)))
    inward = _norm(tuple(i3[i] - o3[i] for i in range(3)))
    y_axis = _norm(_add(n, inward, 0.3 if upper else 0.1))
    y_axis = _norm(_add(y_axis, x_axis, -_dot(y_axis, x_axis)))
    z_axis = _cross(x_axis, y_axis)
    inner_side = "south" if _dot(z_axis, inward) > 0 else "north"
    q = max(0.0, 1.0 - (c2[0] / EYE_A) ** 2)
    height = (0.62 + 0.5 * q) if upper else (0.5 + 0.12 * q)
    while True:
        top = _add(_add(p, y_axis, height - SINK), inward, width / 2)
        if math.hypot(top[0] - 8, top[2] - 8) >= visor_r(top) + 0.1 or height > 2.5:
            break
        height += 0.05
    if crest:
        height += 0.12 + 0.2 * q
    centre = _add(p, y_axis, height / 2 - SINK)
    lit = clamp((_dot(y_axis, KEY) + 0.2) / 1.1)
    s = min(LID_SHADES - 1, int(lit * LID_SHADES))
    zone = "crest" if crest else "upper" if upper else "lower"
    u0 = LID_ZONES[zone] * 4 + (j % 2) * 2
    top_uv = [u0, 2 * s, u0 + 2, 2 * s + 2]
    if inner_side == "north":
        top_uv = [u0, 2 * s + 2, u0 + 2, 2 * s]
    outer_side = "north" if inner_side == "south" else "south"
    end_uv = [u0, 2 * s + 0.5, u0 + 1, 2 * s + 1.5]
    faces = {"up": ("lid_top", top_uv), inner_side: ("lid_inner", [0, 0, 16, 16]),
             outer_side: ("lid_side", [0, 0, 16, 16]), "east": ("lid_top", end_uv), "west": ("lid_top", end_uv)}
    return oriented(centre, x_axis, y_axis, (length, height, width), "lid_side", faces=faces, skip=("down",))


def lid(bands, upper: bool) -> list[dict]:
    parts = [lid_box(*b, upper, j) for j, b in enumerate(bands)]
    if upper:
        # A raised crest along the middle of the upper lid: a rounded, hooded brow.
        for j, (c2, t2, n2, hl, hw) in enumerate(bands):
            if abs(c2[0]) > EYE_A * 0.78:
                continue
            parts.append(lid_box(_add(c2, n2, -hw * 0.15), t2, n2, hl, hw * 0.55, upper, j, crest=True))
    return parts


def corners() -> list[dict]:
    """End-stone caps where the lids meet, drawn out into a point."""
    parts = []
    for sx in (-1, 1):
        c2 = (sx * (EYE_A + 0.12), EYE_LIFT + 0.06)
        p, n = shell_point(*c2)
        q3, _ = shell_point(sx * (EYE_A + 0.6), EYE_LIFT + 0.14)
        out = tuple(q3[i] - p[i] for i in range(3))
        x_axis = _norm(_add(out, n, -_dot(out, n)))
        y_axis = _norm(_add(n, x_axis, -_dot(n, x_axis)))
        height = 0.72
        centre = _add(p, y_axis, height / 2 - SINK)
        lit = clamp((_dot(y_axis, KEY) + 0.2) / 1.1)
        s = min(LID_SHADES - 1, int(lit * LID_SHADES))
        u0 = LID_ZONES["corner"] * 4
        faces = {"up": ("lid_top", [u0, 2 * s + 0.5, u0 + 2, 2 * s + 1.5]), "north": ("lid_side", [0, 0, 16, 16]),
                 "south": ("lid_side", [0, 0, 16, 16]), "east": ("lid_side", [0, 0, 16, 16]),
                 "west": ("lid_side", [0, 0, 16, 16])}
        parts.append(oriented(centre, x_axis, y_axis, (1.0, height, 0.9), "lid_side", faces=faces, skip=("down",)))
    return parts


def motes() -> list[dict]:
    """Flat, double-sided glowing quads turned mostly toward the inventory camera."""
    parts = []
    for i, (az, h, dist, size, _) in enumerate(MOTES):
        a = math.radians(az)
        radial = (math.sin(a), 0.0, math.cos(a))
        centre = (8 + dist * radial[0], Y0 + h, 8 + dist * radial[2])
        facing = _norm(_add(_add((0, 0, 0), VIEW, 0.85), radial, 0.45))
        up = _norm(_add((0, 1, 0), facing, -facing[1]))
        side = _cross(up, facing)
        u0, v0 = (i % 4) * 4.0, (i // 4) * 4.0
        cell = [u0, v0, u0 + 4, v0 + 4]
        faces = {"south": ("motes", cell), "north": ("motes", [cell[2], cell[1], cell[0], cell[3]])}
        parts.append(oriented(centre, side, up, (size, size, 0.02), "motes", faces=faces,
                              skip=("east", "west", "up", "down"), glow=15, shade=False))
    return parts


def build() -> list[dict]:
    body = shell()
    # The translucent gloss skin goes last, over everything it may show through.
    return body + eye_surface() + lid(UPPER, True) + lid(LOWER, False) + corners() + motes() + gloss_skin(body)


def transforms(parts) -> dict:
    d = display(KIND, parts, grip=GRIP, size=SIZE, gui_rotation=GUI_ROT, gui_span=15.6)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, 1)}, GRIP, "fist", 0.45 * SIZE)
    # First person: the eye turned toward the camera, held low on the right.
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.3, 0.12, 1)}, GRIP, (0.47, -0.36, -0.88),
                                       0.47 * SIZE, pose=None)
    # A thrown egg is drawn facing the camera through "ground": the eye looks at the viewer.
    d["ground"] = fit(parts, (0, 0, 0), 8.0, lift=2.0)
    return d


def models() -> dict:
    parts = build()
    return {"main": model(parts, transforms(parts))}
