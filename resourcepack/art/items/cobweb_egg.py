"""Cobweb Egg: a pale silk-wrapped egg bound in a dewy cobweb, a tiny spider clinging on (Egg set).

The shell is the shared egg body (lathe of EGG_PROFILE, 16 sides). Its maps follow the
egg round (azimuth -> u, height -> v, one map per half turn), so the painted silk wraps
the shell seamlessly: soft, cottony #DDDDDD silk with fine strands wound round it, each a
lit thread with a shadow under it, and crisp lips and feet on the stepped tiers. The light
is painted in and the shell skips the game's face shading, so it stays as pale as silk.

An orb web is draped over the front in 3D: frame threads run out from a hub on the upper
right shoulder, traced as taut threads over the stepped shell, and scalloped capture
threads sag between them toward the hub. Four frame threads run on round the egg as
binding strands and meet again on its far side. Every thread's shadow is painted onto the
silk. Dew beads hang on the threads, and a black widow sits at the hub: a glossy octagonal
abdomen with a red hourglass, jointed three-part legs splayed over the web, red eyes.

Animation (one 3.2 s loop, every texture in step): a cold glint of dew light runs out
along the threads from the hub and on round the binding strands, washing the silk beside
them; each dew bead flares into a blue twinkle as it passes and dew on the silk sparkles;
then the spider's red eyes glimmer, twice, with a red twinkle over them.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, fit, lathe, matrix_to_euler, mix, model, place, rgba,
                     save, save_animation, sparkle, turn, wave)

ID = "cobweb_egg"
NAME = "Cobweb Egg"
KIND = "item"
COUNTERPART = "item/egg"

# --------------------------------------------------------------------------------------
# Palettes, darkest -> lightest (shadows drift to violet-blue, lights to warm ivory)
# --------------------------------------------------------------------------------------
SILK = ["#1f1b2e", "#2f2a42", "#433d59", "#5a5472", "#736e8b", "#8c88a1", "#a4a1b5", "#b9b7c7",
        "#cccbd6", "#dcdce1", "#e8e7e6", "#f2f1eb", "#faf8f0", "#fffdf7"]
THREAD = ["#6a6f8e", "#9398b3", "#b9bdd2", "#d5d9e8", "#eceff8", "#ffffff"]
GLINT = ["#58c4f0", "#8fdcff", "#c6f1ff", "#ffffff"]
DEW = ["#123f63", "#2a6f9c", "#4f9fca", "#86c8e6", "#bfe6f5", "#e8f8fd", "#ffffff"]
SPIDER = ["#060409", "#0f0a16", "#1a1324", "#281e36", "#382b4b", "#4e3f66", "#6c5c89", "#9585b0"]
RED = ["#2a0006", "#5c000e", "#9a0a16", "#d91a22", "#ff4a3a", "#ff9a7a", "#ffe6d8"]

# --------------------------------------------------------------------------------------
# The shared egg body
# --------------------------------------------------------------------------------------
EGG_PROFILE = [(0, 1.4), (0.7, 3.2), (1.8, 4.3), (3.3, 4.9), (5.0, 5.0), (6.8, 4.7), (8.4, 4.0),
               (9.8, 3.0), (10.9, 1.8), (11.5, 0.6)]
EGG_CENTER = (8.0, 2.2, 8.0)
CX, CY, CZ = EGG_CENTER
EGG_TOP = EGG_PROFILE[-1][0]
SLICES = [(h0, h1, (r0 + r1) / 2) for (h0, r0), (h1, r1) in zip(EGG_PROFILE, EGG_PROFILE[1:])]

# The shell maps: every frame is N x N. Each half of the egg (8 faces of 22.5 degrees) spans
# the full width, 4 texels per face; each slice gets a whole number of rows.
N = 32
FRAMES, FRAMETIME = 32, 2
ROWS = (2, 3, 4, 5, 5, 4, 4, 3, 2)                        # rows per slice, bottom to top
ROW0 = [sum(ROWS[k + 1:]) for k in range(len(ROWS))]      # first row (from the top) of a slice
HALF = {"front": -101.25, "back": 78.75}                  # azimuth at u = 0; each half spans 180

# --------------------------------------------------------------------------------------
# The web (azimuth from +Z toward +X, heights along the egg; theta 0 = round the egg toward
# +azimuth, 90 = up toward the tip)
# --------------------------------------------------------------------------------------
HUB_PHI, HUB_H = 34.0, 7.1
SPOKES = [(8.0, 4.6), (52.0, 4.0), (97.0, 4.6), (141.0, 5.6), (184.0, 6.6), (226.0, 7.0), (268.0, 6.4),
          (313.0, 5.4)]
RINGS = (1.55, 2.7, 3.85, 5.0)
# Frame threads that run on past the web as taut binding strands, round the egg to the far
# side (threads from one hub meet again over there): spoke -> full length.
BINDINGS = {0: 13.0, 2: 12.4, 5: 13.4, 7: 12.8}
RING_JITTER = ((0.05, -0.08, 0.1, 0.0), (-0.06, 0.08, -0.05, 0.1), (0.08, 0.0, 0.06, -0.08),
               (0.0, 0.1, -0.06, 0.05), (-0.05, -0.04, 0.08, 0.0), (0.06, 0.05, -0.08, 0.08),
               (-0.08, 0.06, 0.0, -0.05), (0.04, -0.06, 0.05, 0.08))
CHORD_PIECES = (2, 3, 3, 4)
SAG = 0.12            # capture threads sag toward the hub by this share of their radius
D_MAX = 15.0          # hub distance covered by the thread texture's rows
LIFT = 0.08           # threads ride this far above the shell's outer corners
SPOKE_W, RING_W, THREAD_T = 0.36, 0.33, 0.12
THREAD_GLOW = 4       # a faint silver web by moonlight

# Spider: at the hub, head toward SPIDER_THETA, built at SPIDER_SCALE.
SPIDER_THETA = 236.0
SPIDER_SCALE = 0.8

SIZE = 1.45
GRIP = (8, 5.0, 8)
GUI_ROT = (12, -20, 0)

# Loop timing (t in 0..1)
T_WAVE0, T_RUN = 0.0, 0.92          # the dew glint leaves the hub, and takes this long to reach D_MAX
T_EYES = (0.64, 0.86)               # the spider's eyes glimmer


# --------------------------------------------------------------------------------------
# Small vector helpers
# --------------------------------------------------------------------------------------

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


def _len(v):
    return math.sqrt(_dot(v, v))


def _tangent(d, n):
    return _norm(_add(d, n, -_dot(d, n)))


def clamp(v, lo=0.0, hi=1.0):
    return max(lo, min(hi, v))


def azimuth_of(p) -> float:
    return math.degrees(math.atan2(p[0] - CX, p[2] - CZ))


# --------------------------------------------------------------------------------------
# Shell geometry: texel <-> egg surface
# --------------------------------------------------------------------------------------

def wrap(phi: float) -> float:
    """Azimuth into [-101.25, 258.75): the front half first, then the back half."""
    return (phi + 101.25) % 360.0 - 101.25


def half_of(phi: float) -> str:
    return "front" if wrap(phi) < 78.75 else "back"


def texel_geo(half: str, c: int, r: int):
    """(azimuth, height, slice radius, slice) at the centre of texel (c, r) of a half's map."""
    phi = HALF[half] + (c + 0.5) * 180.0 / N
    k = next(k for k in range(len(SLICES)) if ROW0[k] <= r < ROW0[k] + ROWS[k])
    h0, h1, rad = SLICES[k]
    f = (r - ROW0[k] + 0.5) / ROWS[k]
    return phi, h1 - f * (h1 - h0), rad, k


def texel_at(phi: float, h: float):
    """(half, column, row) of the texel that shows the surface point (azimuth, height)."""
    phi = wrap(phi)
    half = half_of(phi)
    c = min(N - 1, int(math.floor((phi - HALF[half]) / 180.0 * N)))
    h = clamp(h, 0.0, EGG_TOP - 1e-6)
    k = next(k for k, (h0, h1, _) in enumerate(SLICES) if h0 <= h < h1)
    h0, h1, _ = SLICES[k]
    r = ROW0[k] + min(ROWS[k] - 1, int((h1 - h) / (h1 - h0) * ROWS[k]))
    return half, c, r


def neighbour(half: str, c: int, r: int, dc: int, dr: int):
    """The texel next to (c, r), stepping across the seam into the other half."""
    c, r = c + dc, r + dr
    if not 0 <= r < N:
        return None
    if c < 0:
        return ("back" if half == "front" else "front"), c + N, r
    if c >= N:
        return ("back" if half == "front" else "front"), c - N, r
    return half, c, r


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


def smooth_point(phi: float, h: float):
    """Point on the smooth egg (relative to the egg's middle) for the silk patterns."""
    p = math.radians(phi)
    r = profile_r(h)
    return (r * math.sin(p), h - EGG_TOP / 2, r * math.cos(p))


# --------------------------------------------------------------------------------------
# The taut hull the threads ride on: the convex hull of the stepped shell's outer corners,
# turned round the axis (a thread stretched over the facets touches only their edges).
# --------------------------------------------------------------------------------------
CORNER = 1.0 / math.cos(math.pi / 16)


def _meridian():
    pts = sorted({(h, r * CORNER) for h0, h1, r in SLICES for h in (h0, h1)})
    hull = []
    for p in pts:
        while len(hull) >= 2 and ((hull[-1][0] - hull[-2][0]) * (p[1] - hull[-2][1])
                                  - (hull[-1][1] - hull[-2][1]) * (p[0] - hull[-2][0])) >= 0:
            hull.pop()
        hull.append(p)
    return [(0.0, 0.0)] + [(r, h) for h, r in hull] + [(0.0, EGG_TOP)]     # (rho, height)


MERIDIAN = _meridian()
_SEGS = []
for (_a, _b) in zip(MERIDIAN, MERIDIAN[1:]):
    _d = (_b[0] - _a[0], _b[1] - _a[1])
    _l = math.hypot(*_d)
    _SEGS.append((_a, _d, _l, (_d[1] / _l, -_d[0] / _l)))


def surface(p, lift: float = LIFT):
    """(point, outward normal) on the lifted hull closest to the model point p."""
    x, y, z = p[0] - CX, p[1] - CY, p[2] - CZ
    rho = math.hypot(x, z)
    phi = math.atan2(x, z)
    best = None
    for a, d, l, n in _SEGS:
        s = clamp(((rho - a[0]) * d[0] + (y - a[1]) * d[1]) / (l * l))
        q = (a[0] + d[0] * s, a[1] + d[1] * s)
        dist = math.hypot(rho - q[0], y - q[1])
        if best is None or dist < best[0]:
            best = (dist, q, n)
    dist, q, n = best
    off = (rho - q[0], y - q[1])
    if dist > 1e-6 and off[0] * n[0] + off[1] * n[1] > 0:
        n = (off[0] / dist, off[1] / dist)
    rho2, y2 = q[0] + lift * n[0], q[1] + lift * n[1]
    point = (CX + rho2 * math.sin(phi), CY + y2, CZ + rho2 * math.cos(phi))
    normal = _norm((n[0] * math.sin(phi), n[1], n[0] * math.cos(phi)))
    return point, normal


def trace(p0, d0, length: float, step: float = 0.05):
    """A taut thread (geodesic) over the hull from p0 heading d0: points and distances."""
    p, n = surface(p0)
    d = _tangent(d0, n)
    pts, dist, normals = [p], [0.0], [n]
    s = 0.0
    while s < length:
        q, nq = surface(_add(p, d, step))
        seg = _sub(q, p)
        seg_len = _len(seg)
        d = _tangent(_norm(seg), nq)
        s += seg_len
        p = q
        pts.append(p)
        dist.append(s)
        normals.append(nq)
    return pts, dist, normals


def along(path, s: float):
    pts, dist, normals = path
    if s <= 0:
        return pts[0], normals[0]
    for i in range(1, len(dist)):
        if dist[i] >= s:
            f = (s - dist[i - 1]) / max(1e-9, dist[i] - dist[i - 1])
            p = tuple(pts[i - 1][k] + (pts[i][k] - pts[i - 1][k]) * f for k in range(3))
            return p, _norm(_add(normals[i - 1], _sub(normals[i], normals[i - 1]), f))
    return pts[-1], normals[-1]


def _hub():
    p = math.radians(HUB_PHI)
    r = profile_r(HUB_H)
    point, n = surface((CX + r * math.sin(p), CY + HUB_H, CZ + r * math.cos(p)))
    up = _tangent((0.0, 1.0, 0.0), n)
    east = _norm(_cross(up, n))
    return point, n, east, up


HUB, HUB_N, HUB_E, HUB_U = _hub()
_PATHS: dict = {}


def direction(theta: float):
    a = math.radians(theta)
    return _norm(_add(_add((0, 0, 0), HUB_E, math.cos(a)), HUB_U, math.sin(a)))


def web_point(theta: float, d: float):
    """(point, normal) at geodesic polar coordinates (theta, d) round the hub."""
    key = round((theta % 360.0) * 4) / 4
    if key not in _PATHS:
        _PATHS[key] = trace(HUB, direction(key), D_MAX + 0.5)
    return along(_PATHS[key], d)


def web_xy(theta: float, d: float):
    a = math.radians(theta)
    return d * math.cos(a), d * math.sin(a)


def xy_polar(x: float, y: float):
    return math.degrees(math.atan2(y, x)), math.hypot(x, y)


# --------------------------------------------------------------------------------------
# Web layout: spokes, capture rings, dew beads (hub distance d sets the glint's timing)
# --------------------------------------------------------------------------------------

def ring_d(k: int, j: int) -> float:
    return RINGS[j] + RING_JITTER[k][j]


def chord_polar(k: int, j: int, s: float):
    """Polar (theta, d) of the capture thread between spokes k and k+1 on ring j at s."""
    k2 = (k + 1) % len(SPOKES)
    ta, tb = SPOKES[k][0], SPOKES[k2][0]
    if tb < ta:
        tb += 360.0
    ax, ay = web_xy(ta, ring_d(k, j))
    bx, by = web_xy(tb, ring_d(k2, j))
    x, y = ax + (bx - ax) * s, ay + (by - ay) * s
    theta, d = xy_polar(x, y)
    return theta, d * (1.0 - SAG * 4 * s * (1 - s))


def chords():
    """(k, j) of every capture thread whose two spokes both reach its ring."""
    out = []
    for k in range(len(SPOKES)):
        k2 = (k + 1) % len(SPOKES)
        for j in range(len(RINGS)):
            if SPOKES[k][1] >= ring_d(k, j) + 0.25 and SPOKES[k2][1] >= ring_d(k2, j) + 0.25:
                out.append((k, j))
    return out


# Dew beads: ("spoke", k, j) sits where spoke k crosses ring j; ("chord", k, j, s) hangs on
# a capture thread. Then its size, and whether it throws a big twinkle.
BEADS = [
    ("spoke", 4, 2, 0.5, True), ("spoke", 5, 1, 0.44, False), ("spoke", 6, 3, 0.46, False),
    ("chord", 5, 2, 0.5, 0.5, True), ("chord", 3, 1, 0.55, 0.42, False), ("chord", 4, 3, 0.45, 0.44, False),
    ("chord", 6, 1, 0.5, 0.4, False), ("spoke", 3, 3, 0.42, True),
]


def bead_polar(spec):
    if spec[0] == "spoke":
        _, k, j = spec[:3]
        return SPOKES[k][0], ring_d(k, j)
    _, k, j, s = spec[:4]
    return chord_polar(k, j, s)


def glint_time(d: float) -> float:
    return T_WAVE0 + T_RUN * d / D_MAX


def blip(t: float, phase: float, width: float = 0.07) -> float:
    dt = abs(t - phase) % 1.0
    dt = min(dt, 1.0 - dt)
    return max(0.0, 1.0 - dt / width)


def glint_at(d: float, t: float) -> float:
    """Brightness of the dew glint at hub distance d: a hot front and a fading wake."""
    front = (t - T_WAVE0) / T_RUN * D_MAX
    if t < T_WAVE0 or front > D_MAX + 2.0:
        return 0.0
    behind = front - d
    if -0.25 <= behind < 0.35:
        return 1.0
    if 0.35 <= behind < 1.4:
        return 0.6 * (1.0 - (behind - 0.35) / 1.05)
    return 0.0


# --------------------------------------------------------------------------------------
# Textures: the silk shell
# --------------------------------------------------------------------------------------
KEY = _norm((-0.55, 0.75, 0.6))          # key light: upper left, in front
BACKLIGHT = _norm((0.5, 0.45, -0.75))    # a softer light over the back
FILL = _norm((0.75, -0.4, 0.2))          # bounce light on the lower right


BAYER = (0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)


def threshold(c: int, r: int) -> float:
    return (BAYER[(r % 4) * 4 + c % 4] + 0.5) / 16.0 - 0.5


SHEEN_DIR = _norm((-0.45, 0.62, 0.64))   # where the silk's soft sheen sits: the upper-left shoulder


def light(phi: float, h: float) -> float:
    """Baked light on the silk as a ramp position (0 = darkest). The shell skips the game's
    face shading, so this is all the light it gets: a key light, a softer back light, a
    bounce from below right and a sheen on the upper-left shoulder."""
    n = normal_at(phi, h)
    key = clamp((_dot(n, KEY) + 0.3) / 1.3)
    back = clamp((_dot(n, BACKLIGHT) + 0.2) / 1.2)
    bounce = max(0.0, _dot(n, FILL))
    sheen = _dot(n, SHEEN_DIR)
    gloss = 1.2 if sheen > 0.975 else (0.6 if sheen > 0.9 else 0.0)
    return 7.3 + 4.1 * key + 1.0 * back * (1 - key) + 0.6 * bounce + gloss


def _waves(seed: int, count: int, lo: float, hi: float):
    rng = random.Random(seed)
    out = []
    for _ in range(count):
        z = rng.uniform(-1, 1)
        a = rng.uniform(0, 2 * math.pi)
        d = (math.sqrt(1 - z * z) * math.cos(a), z, math.sqrt(1 - z * z) * math.sin(a))
        k = 2 * math.pi / rng.uniform(lo, hi)
        out.append((tuple(c * k for c in d), rng.uniform(0, 2 * math.pi)))
    return out


# Fluffy silk: plane waves summed into a soft noise, embossed against the key light so the
# silk puffs up in cottony clumps, lit on their upper-left sides.
FLUFF = _waves(9, 10, 1.3, 2.3)


def fluff(p, n) -> float:
    """Emboss shading (-1..1) of the silk's clumps at p (normal n)."""
    g = (0.0, 0.0, 0.0)
    for k, ph in FLUFF:
        g = _add(g, k, math.cos(_dot(k, p) + ph))
    g = _add(g, n, -_dot(g, n))
    light_t = _add(KEY, n, -_dot(KEY, n))
    return clamp(-_dot(g, light_t) / 9.0, -1.0, 1.0)


# Silk strands wound round the egg, painted as fine lit threads with a shadow under them:
# (tilt, azimuth of tilt, offset) of the plane each one follows round the egg.
WRAPS = ((28.0, 205.0, -2.6), (36.0, 30.0, 0.3), (22.0, 265.0, 2.9), (62.0, 150.0, 0.8))


def _wrap_map():
    """Texels on the wound strands and the shadow texels just under them."""
    on, under = set(), set()
    for tilt, azim, offset in WRAPS:
        a, b = math.radians(tilt), math.radians(azim)
        m = (math.sin(a) * math.sin(b), math.cos(a), math.sin(a) * math.cos(b))
        e1 = _norm(_cross(m, (0.0, 0.0, 1.0) if abs(m[2]) < 0.9 else (1.0, 0.0, 0.0)))
        e2 = _cross(m, e1)
        for i in range(720):
            ang = 2 * math.pi * i / 720
            d = _add(_add((0, 0, 0), e1, math.cos(ang)), e2, math.sin(ang))
            # March out from the plane's centre on the egg's axis to the surface.
            c = _add((0.0, 0.0, 0.0), m, offset)
            lo, hi = 0.0, 8.0
            for _ in range(30):
                mid = (lo + hi) / 2
                q = _add(c, d, mid)
                h = q[1] + EGG_TOP / 2
                if 0 <= h <= EGG_TOP and math.hypot(q[0], q[2]) < profile_r(h):
                    lo = mid
                else:
                    hi = mid
            q = _add(c, d, lo)
            h = q[1] + EGG_TOP / 2
            if not 0.2 < h < EGG_TOP - 0.2:
                continue
            phi = math.degrees(math.atan2(q[0], q[2]))
            on.add(texel_at(phi, h))
            under.add(texel_at(phi, h - 0.36))
    return on, under - on


WRAP_ON, WRAP_UNDER = _wrap_map()


# Crisp tiers: (slice, 0 = its top row / -1 = its bottom row) -> ramp offset. Up top each tier's
# upper edge catches the light and its foot is tucked in shadow against the ledge below;
# down below, the overhangs throw shade on the tier under them.
TIER_EDGE = {(4, 0): 0.7, (5, 0): 0.7, (6, 0): 0.7, (7, 0): 0.6,
             (5, -1): -0.6, (6, -1): -0.6, (7, -1): -0.6, (8, -1): -0.5,
             (1, -1): -0.4, (2, -1): -0.4, (3, -1): -0.3,
             (0, 0): -0.7, (1, 0): -0.7, (2, 0): -0.6}


def _shell_data():
    """Static per-texel ramp position: baked light, fluffy clumps, wound strands, dither."""
    data = {}
    for half in ("front", "back"):
        for r in range(N):
            for c in range(N):
                phi, h, rad, k = texel_geo(half, c, r)
                p = smooth_point(phi, h)
                pos = light(phi, h) + 0.45 * fluff(p, normal_at(phi, h)) + threshold(c, r) * 0.25
                row = r - ROW0[k]
                pos += TIER_EDGE.get((k, 0 if row == 0 else (-1 if row == ROWS[k] - 1 else None)), 0.0)
                key = (half, c, r)
                if key in WRAP_ON:
                    pos += 1.7
                elif key in WRAP_UNDER:
                    pos -= 1.5
                data[key] = pos
    return data


SHELL_DATA = None


def _strand_paths():
    """Every thread as a list of (point, normal, hub distance) samples."""
    paths = []
    for k, (theta, length) in enumerate(SPOKES):
        length = BINDINGS.get(k, length)
        n = int(length * 7)
        paths.append([web_point(theta, 0.45 + (length - 0.45) * i / n) + (0.45 + (length - 0.45) * i / n,)
                      for i in range(n + 1)])
    for k, j in chords():
        n = 18
        pts = []
        for i in range(n + 1):
            theta, d = chord_polar(k, j, i / n)
            pts.append(web_point(theta, d) + (d,))
        paths.append(pts)
    return paths


def _web_map():
    """Texels under the threads, their shadows (cast away from the key light) and the halo
    beside them that the glint washes, each with the hub distance of its thread."""
    under, shadow = {}, {}
    for path in _strand_paths():
        for p, n, d in path:
            key = texel_at(azimuth_of(p), p[1] - CY)
            under[key] = min(d, under.get(key, 99.0))
            away = _add(KEY, n, -_dot(KEY, n))          # the shadow falls away from the key light
            off = _add(p, _norm(away), -0.5) if _len(away) > 1e-6 else p
            key2 = texel_at(azimuth_of(off), off[1] - CY)
            shadow[key2] = min(d, shadow.get(key2, 99.0))
    halo = {}
    for (half, c, r), d in under.items():
        for dc in (-1, 0, 1):
            for dr in (-1, 0, 1):
                nb = neighbour(half, c, r, dc, dr)
                if nb and nb not in under:
                    halo[nb] = min(d, halo.get(nb, 99.0))
    return under, {k: v for k, v in shadow.items() if k not in under}, halo


def _silk_dew():
    """Tiny dew drops scattered on the silk near the web (azimuth, height, hub distance, big)."""
    rng = random.Random(41)
    out = []
    while len(out) < 12:
        theta = rng.uniform(120, 330)
        d = rng.uniform(1.8, 6.4)
        p, n = web_point(theta, d)
        phi, h = azimuth_of(p), p[1] - CY
        if not 0.8 < h < 10.6:
            continue
        key = texel_at(phi, h)
        if key in WEB_UNDER or key in WEB_HALO:
            continue
        out.append((phi, h, d, len(out) % 3 == 0))
    return out


WEB_UNDER, WEB_SHADOW, WEB_HALO = _web_map()
SILK_DEW = _silk_dew()


def shell_frame(half: str, t: float):
    global SHELL_DATA
    if SHELL_DATA is None:
        SHELL_DATA = _shell_data()
    img = canvas(N)
    px = img.load()
    for r in range(N):
        for c in range(N):
            key = (half, c, r)
            pos = SHELL_DATA[key]
            if key in WEB_SHADOW:
                pos -= 2.3
            colour = SILK[int(clamp(round(pos), 1, len(SILK) - 1))]
            # The glint's cold light spilling onto the silk beside the threads: a solid front,
            # then a wake that breaks up into glittering dew.
            for table, k in ((WEB_HALO, 0.62), (WEB_SHADOW, 0.45)):
                if key in table:
                    g = glint_at(table[key], t)
                    if g > 0.8:
                        colour = mix(colour, GLINT[1], k * 1.1)
                    elif g > 0.05 and (c + 2 * r + round(t * FRAMES)) % 3 == 0:
                        colour = mix(colour, GLINT[2] if g < 0.35 else GLINT[1], 0.4 + 0.5 * g)
                    break
            px[c, r] = rgba(colour)
    # Dew on the wet silk: pinpricks that flash as the glint passes.
    for phi, h, d, big in SILK_DEW:
        hf, c, r = texel_at(phi, h)
        if hf != half:
            continue
        g = blip(t, glint_time(d) + 0.02, 0.07)
        if g > 0.1:
            px[c, r] = rgba(GLINT[1] if g < 0.5 else GLINT[3])
        if big and g > 0.4:
            sparkle(img, c, r, g, GLINT[3], reach=1)
    return img


def paint_shell() -> None:
    for half in ("front", "back"):
        save_animation(animate(lambda t, half=half: shell_frame(half, t), FRAMES), "shell_" + half,
                       frametime=FRAMETIME)
    # Ledges between slices: solid silk tones so the overlapping cap faces never flicker.
    save(canvas(16, fill=SILK[12]), "cap_top")
    save(canvas(16, fill=SILK[11]), "cap_upper")
    save(canvas(16, fill=SILK[10]), "cap_mid")
    save(canvas(16, fill=SILK[6]), "cap_lower")


# --------------------------------------------------------------------------------------
# Textures: threads, dew, spider
# --------------------------------------------------------------------------------------

def thread_frame(t: float):
    """Rows = hub distance (0..D_MAX). Columns 0-1: a thread's outer face (lit edge, body),
    2-3: its sides. The dew glint runs down the rows."""
    img = canvas(32)
    px = img.load()
    for row in range(32):
        d = (row + 0.5) / 32 * D_MAX
        g = glint_at(d, t)
        if g >= 0.9:
            cols = (GLINT[3], GLINT[2], GLINT[1], GLINT[0])
        elif g >= 0.45:
            cols = (GLINT[2], GLINT[1], GLINT[0], GLINT[0])
        elif g > 0.05:
            cols = (THREAD[5], GLINT[2], GLINT[1], THREAD[2])
        else:
            cols = (THREAD[5], THREAD[4], THREAD[3], THREAD[2])
        for c, col in enumerate(cols):
            px[c, row] = rgba(col)
    return img


def _bead_cell(img, x0, y0, g: float) -> None:
    """A 4x4 dew bead: a white catch-light up top, cold refraction below."""
    px = img.load()
    lift = 2 if g > 0.6 else (1 if g > 0.25 else 0)
    for y, row in enumerate(("3443", "4654", "3442", "2352")):
        for x, ch in enumerate(row):
            px[x0 + x, y0 + y] = rgba(DEW[min(6, int(ch) + lift)])


def star_cell(img, x0, y0, g: float, palette=None) -> None:
    """An 8x8 twinkle, transparent at rest: a white core, arms fading out through the
    palette (cold blue by default), faint diagonals at its peak."""
    if g <= 0.08:
        return
    core, near, mid, far = palette or (GLINT[3], GLINT[2], GLINT[1], GLINT[0])
    cx, cy = x0 + 3, y0 + 3
    arm = 1 if g < 0.45 else (2 if g < 0.8 else 3)
    px = img.load()
    px[cx, cy] = rgba(core)
    for i in range(1, arm + 1):
        col = near if i == 1 else (mid if i == 2 else far)
        for dx, dy in ((i, 0), (-i, 0), (0, i), (0, -i)):
            px[cx + dx, cy + dy] = rgba(col)
    if g > 0.7:
        for dx, dy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
            px[cx + dx, cy + dy] = (*rgba(far)[:3], 190)


def eye_level(t: float) -> float:
    return clamp(0.25 + 0.15 * wave(t, 0.4) + 0.75 * blip(t, T_EYES[0], 0.1) + 0.5 * blip(t, T_EYES[1], 0.06))


def eye_glint(t: float) -> float:
    return max(blip(t, T_EYES[0], 0.075), 0.6 * blip(t, T_EYES[1], 0.05))


def dew_frame(t: float):
    """The dew atlas: bead cells (4x4) in rows 0-7, eyes in row 12, twinkles (8x8) below."""
    img = canvas(32)
    for i, spec in enumerate(BEADS):
        theta, d = bead_polar(spec)
        _bead_cell(img, (i % 8) * 4, (i // 8) * 4, blip(t, glint_time(d), 0.07))
    stars = [i for i, spec in enumerate(BEADS) if spec[-1]]
    for s, i in enumerate(stars):
        theta, d = bead_polar(BEADS[i])
        star_cell(img, (s % 4) * 8, 16 + (s // 4) * 8, blip(t, glint_time(d) + 0.01, 0.08))
    star_cell(img, 24, 16, eye_glint(t), ("#ffffff", RED[6], RED[5], RED[4]))
    # Eyes: 4x4 cells at (0, 12) big eye, (4, 12) small eye; (8, 12) the head's top with eyes.
    e = eye_level(t)
    px = img.load()
    base = 2 + round(e * 3.4)
    for (x0, spec) in ((0, ["3443", "4654", "3443", "2332"]), (4, ["2332", "3453", "3443", "2332"])):
        for y, row in enumerate(spec):
            for x, ch in enumerate(row):
                px[x0 + x, 12 + y] = rgba(RED[clamp(int(ch) - 3 + base, 0, 6)])
        if e > 0.7:
            px[x0 + 1, 13] = rgba(RED[6])
    head = ["1221", "2332", "e11e", "1EE1"]
    for y, row in enumerate(head):
        for x, ch in enumerate(row):
            if ch == "E":
                col = RED[clamp(base + 1, 0, 6)]
            elif ch == "e":
                col = RED[clamp(base, 0, 6)]
            else:
                col = SPIDER[int(ch) + 1]
            px[8 + x, 12 + y] = rgba(col)
    return img


def paint_spider() -> None:
    img = canvas(32, fill=SPIDER[2])
    px = img.load()
    # Abdomen top (texels 0-7 x 0-7, rear at the top, projected over the abdomen's back and
    # lit shoulder): glossy black, a violet sheen and a white glint to the upper left, a red
    # hourglass down the middle.
    top = ["23344332",
           "35654432",
           "46765432",
           "35654432",
           "34444332",
           "23333332",
           "22333322",
           "12222221"]
    for y, row in enumerate(top):
        for x, ch in enumerate(row):
            px[x, y] = rgba(SPIDER[int(ch)])
    px[1, 2] = rgba("#e4dcf5")
    for (x, y, lvl) in ((2, 2, 3), (3, 2, 4), (4, 2, 4), (5, 2, 3), (3, 3, 4), (4, 3, 3), (3, 4, 2), (4, 4, 2),
                        (3, 5, 4), (4, 5, 3), (2, 6, 3), (3, 6, 4), (4, 6, 3), (5, 6, 2)):
        px[x, y] = rgba(RED[lvl])
    # Abdomen sides (8-15 x 0-3) and the fangs (16-19 x 0-3): a lit rim over deep shadow;
    # 20-23 x 0-3 stays one flat tone for the abdomen's end faces.
    for x in range(8, 20):
        for y, lvl in enumerate((5, 3, 2, 1)):
            px[x, y] = rgba(SPIDER[lvl])
    # Cephalothorax top (0-5 x 8-13): dark with a pale groove down the middle.
    for y in range(8, 14):
        for x in range(0, 6):
            px[x, y] = rgba(SPIDER[3 if x in (0, 5) else 4])
        px[2, y] = px[3, y] = rgba(SPIDER[5])
    for x in range(8, 16):
        for y, lvl in enumerate((4, 3, 2, 1)):
            px[x, 8 + y] = rgba(SPIDER[lvl])
    # Legs (24-27 x 0-31): femur rows 0-11, tibia 12-21, tarsus 22-31; each segment darkens
    # toward its end and meets the next at a pale joint.
    for y in range(32):
        seg_end = y in (10, 11, 20, 21)
        lvl = 6 if seg_end else (4 if y < 22 else 3)
        if y in (0, 12, 22):
            lvl = 3
        for x, dl in zip(range(24, 28), (1, 0, -1, -2)):
            px[x, y] = rgba(SPIDER[clamp(lvl + dl, 0, 7)])
    save(img, "spider")


def textures() -> None:
    paint_shell()
    save_animation(animate(thread_frame, FRAMES), "thread", frametime=FRAMETIME)
    save_animation(animate(dew_frame, FRAMES), "dew", frametime=FRAMETIME)
    paint_spider()


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def _uv(vals):
    return [round(clamp(v, 0.0, 16.0), 4) for v in vals]


def slice_of(element: dict) -> int:
    h0 = element["from"][1] - CY
    return min(range(len(SLICES)), key=lambda k: abs(SLICES[k][0] - h0))


def egg_body():
    # The light is painted into the silk, so the shell skips the game's face shading: it
    # stays as pale as painted, round and smooth instead of banded facet by facet.
    slabs = lathe(EGG_CENTER, EGG_PROFILE, "shell_front", sides=16, cap="cap_top", shade=False)
    for e in slabs:
        k = slice_of(e)
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
            cap = "cap_top" if k == len(SLICES) - 1 else ("cap_mid" if k == 3 else "cap_upper")
            faces["up"] = {"uv": [0, 0, 16, 16], "texture": "#" + cap}
        if k <= 3:
            faces["down"] = {"uv": [0, 0, 16, 16], "texture": "#cap_lower"}
        e["faces"] = faces
    return slabs


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


def v_of(d: float) -> float:
    return clamp(d / D_MAX * 16.0, 0.0, 16.0)


def strand(pa, pb, na, nb, da, db, width, ring=None):
    """A thread from pa to pb lying on the hull (its flat side out), textured by hub distance."""
    axis = _sub(pb, pa)
    length = _len(axis)
    y_axis = _norm(axis)
    n = _tangent(_norm(_add(na, nb)), y_axis)
    x_axis = _norm(_cross(y_axis, n))
    sag = length * length / 30.0
    # Capture threads sit a hair under the frame threads, so where they cross the frame
    # thread's face always wins instead of flickering against it.
    centre = _add(_add(pa, axis, 0.5), n, sag + THREAD_T / 2 - (0.035 if ring is not None else 0.0))
    if ring is None:
        va, vb = v_of(da), v_of(db)
    else:
        va = vb = v_of(ring)
    if abs(vb - va) < 0.25:
        vb = va + 0.25
    faces = {"south": ("thread", _uv([0, vb, 1, va])), "east": ("thread", _uv([1.5, vb, 2, va])),
             "west": ("thread", _uv([1.5, vb, 2, va])), "up": ("thread", _uv([1.5, vb, 2, vb + 0.25])),
             "down": ("thread", _uv([1.5, va, 2, va + 0.25]))}
    return oriented(centre, x_axis, y_axis, (width, length + width * 0.4, THREAD_T), "thread", faces=faces,
                    skip=("north",), glow=THREAD_GLOW, shade=False)


def web() -> list[dict]:
    parts = []
    for k, (theta, length) in enumerate(SPOKES):
        steps = max(2, math.ceil((length - 0.5) / 0.9))
        ds = [0.5 + (length - 0.5) * i / steps for i in range(steps + 1)]
        if k in BINDINGS:
            far = BINDINGS[k]
            more = math.ceil((far - length) / 1.6)
            ds += [length + (far - length) * i / more for i in range(1, more + 1)]
        for da, db in zip(ds, ds[1:]):
            pa, na = web_point(theta, da)
            pb, nb = web_point(theta, db)
            parts.append(strand(pa, pb, na, nb, da, db, SPOKE_W))
    for k, j in chords():
        pieces = CHORD_PIECES[j]
        pts = [web_point(*chord_polar(k, j, i / pieces)) for i in range(pieces + 1)]
        for (pa, na), (pb, nb) in zip(pts, pts[1:]):
            parts.append(strand(pa, pb, na, nb, 0, 0, RING_W, ring=RINGS[j]))
    return parts


def beads() -> list[dict]:
    parts = []
    s_index = 0
    for i, spec in enumerate(BEADS):
        size, star = spec[-2], spec[-1]
        theta, d = bead_polar(spec)
        p, n = web_point(theta, d)
        up = _tangent((0.0, 1.0, 0.0), n)
        x_axis = _norm(_cross(up, n))
        # Hanging a little below its thread, bulging out of it.
        c = _add(_add(p, n, THREAD_T + size * 0.25), up, -size * 0.2)
        cell = [(i % 8) * 2, (i // 8) * 2, (i % 8) * 2 + 2, (i // 8) * 2 + 2]
        faces = {side: ("dew", cell) for side in ("north", "south", "east", "west", "up", "down")}
        parts.append(oriented(c, x_axis, up, (size, size, size * 0.8), "dew", faces=faces, glow=10, shade=False))
        diag_x = _norm(_add(x_axis, up))
        diag_y = _cross(n, diag_x)
        parts.append(oriented(c, diag_x, diag_y, (size * 0.78, size * 0.78, size * 0.95), "dew", faces=faces,
                              glow=10, shade=False))
        if star:
            x0, y0 = (s_index % 4) * 4, 8 + (s_index // 4) * 4
            s_index += 1
            sc = _add(c, n, size * 0.5 + 0.06)
            parts.append(oriented(sc, x_axis, up, (2.4, 2.4, 0.02), "dew",
                                  faces={"south": ("dew", [x0, y0, x0 + 4, y0 + 4])},
                                  skip=("north", "east", "west", "up", "down"), glow=15, shade=False))
    return parts


def _spider_frame():
    f = direction(SPIDER_THETA)
    n = HUB_N
    x = _norm(_cross(n, f))
    return x, n, f


def to_world(local):
    x, n, f = _spider_frame()
    k = SPIDER_SCALE
    return _add(_add(_add(HUB, x, local[0] * k), n, local[1] * k), f, local[2] * k)


def sbox(local, size, faces, tex="spider", **kw):
    x, n, f = _spider_frame()
    return oriented(to_world(local), x, n, tuple(v * SPIDER_SCALE for v in size), tex, faces=faces, **kw)


ABD_SIDE = [4, 0, 8, 2]
ABD_END = [8, 0, 10, 2]
CEPH_TOP = [0, 4, 3, 7]
CEPH_SIDE = [4, 4, 8, 6]
LEG = {"femur": (0.0, 6.0), "tibia": (6.0, 11.0), "tarsus": (11.0, 16.0)}   # v ranges of the leg column
# The abdomen's footprint (spider-local x and z): its top pattern is projected over it, so its
# back and shoulder faces show one continuous marking.
ABD_X, ABD_Z = (-0.82, 0.82), (-2.3, 0.05)
# The abdomen, turned along the spider's body: (z, radius) from its rear to the waist.
ABD_PROFILE = ((-2.3, 0.22), (-2.0, 0.6), (-1.5, 0.8), (-0.85, 0.77), (-0.32, 0.5), (0.05, 0.2))
ABD_Y = 0.86          # height of its axis above the web
ABD_CAP = [10, 0, 12, 2]   # one flat tone for the slices' coplanar end faces
# Legs: (femur angle, tibia angle from forward, femur, tibia, tarsus length, knee height, hip z)
LEGS = ((46.0, 14.0, 1.2, 1.25, 0.55, 1.15, 1.02), (80.0, 50.0, 1.15, 1.05, 0.5, 1.22, 0.84),
        (106.0, 130.0, 1.05, 1.0, 0.5, 1.15, 0.64), (130.0, 166.0, 1.15, 1.3, 0.6, 1.1, 0.46))


def proj_u(x0: float, x1: float) -> tuple[float, float]:
    """u range of the abdomen's top pattern over spider-local x0..x1."""
    k = 4 / (ABD_X[1] - ABD_X[0])
    return (x0 - ABD_X[0]) * k, (x1 - ABD_X[0]) * k


def rolled(roll: float):
    """The spider frame turned by roll degrees about its body axis."""
    x, n, f = _spider_frame()
    a = math.radians(roll)
    return _norm(_add(_add((0, 0, 0), x, math.cos(a)), n, math.sin(a))), \
        _norm(_add(_add((0, 0, 0), x, -math.sin(a)), n, math.cos(a)))


def abdomen() -> list[dict]:
    """An octagonal bulb: per slice a flat slab (the flanks), an upright one (the back),
    and both again turned 45 degrees (the shoulders). The top pattern is projected down
    over the back and the lit left shoulder, so the hourglass and sheen read as one."""
    parts = []
    t = math.tan(math.pi / 8)
    k = SPIDER_SCALE
    for (z0, r0), (z1, r1) in zip(ABD_PROFILE, ABD_PROFILE[1:]):
        r = (r0 + r1) / 2
        h = r * t
        zc, zl = (z0 + z1) / 2, z1 - z0
        v0, v1 = ((z0 - ABD_Z[0]) / (ABD_Z[1] - ABD_Z[0]) * 4, (z1 - ABD_Z[0]) / (ABD_Z[1] - ABD_Z[0]) * 4)
        centre = to_world((0, ABD_Y, zc))
        ends = {"north": ("spider", ABD_CAP), "south": ("spider", ABD_CAP)}
        u0, u1 = proj_u(-h, h)
        back = dict(ends, up=("spider", _uv([u0, v0, u1, v1])))
        u0, u1 = proj_u(-r, -h)
        shoulder = dict(ends, up=("spider", _uv([u0, v0, u1, v1])))
        flanks = dict(ends, east=("spider", ABD_SIDE), west=("spider", ABD_SIDE))
        dark = dict(ends, east=("spider", ABD_SIDE))
        for roll, size, faces, skip in (
                (0.0, (2 * r, 2 * h, zl), flanks, ("up", "down")),
                (0.0, (2 * h, 2 * r, zl), back, ("east", "west", "down")),
                (45.0, (2 * r, 2 * h, zl), dark, ("up", "down", "west")),
                (45.0, (2 * h, 2 * r, zl), shoulder, ("east", "west", "down"))):
            xa, ya = rolled(roll)
            parts.append(oriented(centre, xa, ya, tuple(v * k for v in size), "spider", faces=faces, skip=skip))
    return parts


def leg_faces(segment: str) -> dict:
    v0, v1 = LEG[segment]
    faces = {s_: ("spider", [12, v0, 14, v1]) for s_ in ("north", "south", "east", "west")}
    faces["up"] = faces["down"] = ("spider", [12, v1 - 0.5, 14, v1])
    return faces


def spider() -> list[dict]:
    parts = abdomen()
    parts.append(sbox((0, 0.7, -2.36), (0.34, 0.3, 0.2), {s_: ("spider", CEPH_SIDE) for s_ in
                                                            ("up", "north", "east", "west")}, skip=("down", "south")))
    parts.append(sbox((0, 0.55, 0.05), (0.42, 0.34, 0.5), {s_: ("spider", CEPH_SIDE) for s_ in
                                                         ("up", "north", "south", "east", "west")}, skip=("down",)))
    ceph = {"up": ("spider", CEPH_TOP), "north": ("spider", CEPH_SIDE), "south": ("spider", CEPH_SIDE),
            "east": ("spider", CEPH_SIDE), "west": ("spider", CEPH_SIDE)}
    parts.append(sbox((0, 0.5, 0.72), (1.02, 0.6, 1.05), ceph, skip=("down",)))
    head = dict(ceph)
    head["up"] = ("dew", [4, 6, 6, 8])
    parts.append(sbox((0, 0.64, 1.0), (0.76, 0.62, 0.62), head, glow=6, skip=("down",)))
    # Eyes: a big pair in front, a small pair behind them, glowing red.
    eye_big = {s_: ("dew", [0, 6, 2, 8]) for s_ in ("up", "north", "south", "east", "west", "down")}
    eye_small = {s_: ("dew", [2, 6, 4, 8]) for s_ in ("up", "north", "south", "east", "west", "down")}
    for sx in (-0.17, 0.17):
        parts.append(sbox((sx, 0.9, 1.22), (0.26, 0.2, 0.2), eye_big, glow=15, shade=False))
        parts.append(sbox((sx * 1.9, 0.92, 1.02), (0.17, 0.16, 0.17), eye_small, glow=15, shade=False))
    # The glimmer: a red twinkle over the eyes, invisible at rest.
    parts.append(sbox((0, 1.12, 1.16), (2.2, 0.02, 2.2), {"up": ("dew", [12, 8, 16, 12])}, glow=15, shade=False,
                      skip=("north", "south", "east", "west", "down")))
    # Fangs, and the palps reaching forward beside them.
    dark = {s_: ("spider", ABD_END) for s_ in ("up", "north", "south", "east", "west", "down")}
    for sx in (-0.15, 0.15):
        parts.append(bar(to_world((sx, 0.42, 1.2)), to_world((sx * 0.8, 0.28, 1.5)), 0.15, 0.15, "spider",
                         faces=dark))
    for side in (-1, 1):
        base, mid, tip = (side * 0.34, 0.45, 1.18), (side * 0.5, 0.62, 1.52), (side * 0.46, 0.3, 1.8)
        parts.append(bar(to_world(base), to_world(mid), 0.17, 0.17, "spider", faces=leg_faces("tibia")))
        parts.append(bar(to_world(mid), to_world(tip), 0.15, 0.15, "spider", faces=leg_faces("tarsus")))
    # Jointed legs: the femur rises out to the knee, the tibia drops to the ankle, and the
    # tarsus sets the foot down on the web.
    for a1, a2, l1, l2, l3, knee, hz in LEGS:
        for side in (-1, 1):
            r1, r2 = math.radians(a1), math.radians(a2)
            kx, kz = side * (0.42 + l1 * math.sin(r1)), hz + l1 * math.cos(r1)
            ax, az = kx + side * l2 * math.sin(r2), kz + l2 * math.cos(r2)
            fx, fz = ax + side * l3 * math.sin(r2), az + l3 * math.cos(r2)
            hip_w, knee_w = to_world((side * 0.42, 0.5, hz)), to_world((kx, knee, kz))
            ankle_w = to_world((ax, 0.62, az))
            foot_w, _ = surface(to_world((fx, 0.0, fz)), LIFT + THREAD_T + 0.04)
            ankle_w = _add(ankle_w, _sub(surface(ankle_w, LIFT + THREAD_T + 0.3)[0], ankle_w), 0.6)
            parts.append(bar(hip_w, _add(knee_w, _norm(_sub(knee_w, hip_w)), 0.08), 0.3, 0.3, "spider",
                             faces=leg_faces("femur")))
            parts.append(bar(knee_w, _add(ankle_w, _norm(_sub(ankle_w, knee_w)), 0.06), 0.27, 0.27, "spider",
                             faces=leg_faces("tibia")))
            parts.append(bar(ankle_w, foot_w, 0.23, 0.23, "spider", faces=leg_faces("tarsus")))
    return parts


def build():
    return egg_body() + web() + beads() + spider()


def transforms(parts) -> dict:
    d = display(KIND, parts, grip=GRIP, size=SIZE, gui_rotation=GUI_ROT, gui_span=15.4)
    # In the hand the web and spider face out, away from the body.
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.71, 0, 0.71)}, GRIP, "fist", 0.45 * SIZE)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.62, 0.1, 1)}, GRIP, (0.47, -0.36, -0.88),
                                       0.48 * SIZE, pose=None)
    # A thrown egg faces the camera through "ground" (model +Z toward the viewer).
    d["ground"] = fit(parts, (0, GUI_ROT[1], 0), 7.8, lift=2.0)
    d["fixed"] = fit(parts, (0, GUI_ROT[1] + 180, 0), 14.0)
    d["on_shelf"] = fit(parts, (0, GUI_ROT[1] + 180, 0), 12.0)
    return d


def models() -> dict:
    parts = build()
    return {"main": model(parts, transforms(parts))}
