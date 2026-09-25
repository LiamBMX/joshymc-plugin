"""Freeze Egg: an egg of glassy glacier ice with a snowflake frozen in its heart (Egg set).

The shell is the set's shared egg body (the EGG_PROFILE lathe, 16 sides), lit in its
texture with shade off so the ice stays vivid in the inventory: aqua #55FFFF glowing on the
lit upper left and round the core, cooling through azure to deep sapphire at the rim, the
base and under the crown, with a crisp white glint on the shoulder and, like a glass orb,
light gathering inside the lower right. The map wraps the egg twice (half a turn per
repeat, the seams on the side folds). In the middle of each face the ice clears into a deep
sapphire window where the snowflake core hangs, lit from the far side. Six frost ferns are
etched into the shell, radiating from the window between the snowflake's arms, among a few
fracture planes and trapped bubbles. A crown of faceted ice spikes breaks out of the top: a
central spire, a front pair and a back pair leaning out and two low shards splayed to the
sides, each a diamond-section shaft with a narrower neck and a pointed termination (a cube
standing on its corner), every facet lit by hand for a crisp cut.

Animation (32 frames x 2 ticks = 3.2 s): the core flares and a ring of cold light races out
from the window across the shell; on a self-lit skin just proud of the shell, white frost
creeps out along the etched ferns behind it, crystallising at a glittering front, while
light runs up the crown and its points flash; the frost shimmers, a glassy glint rises
across the frozen egg and the snowflake's tips twinkle one after another; then the frost
sublimates in twinkles back into the etched lines. Star sparkles glint on the crown's points.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, box, canvas, copy, display, fit, lathe, model, move, place, rgba, rotation_of,
                     save, save_animation, sparkle, turn)

ID = "freeze_egg"
NAME = "Freeze Egg"
KIND = "item"
COUNTERPART = "item/egg"

# --------------------------------------------------------------------------------------
# Palettes, darkest -> lightest (shadows drift toward violet-navy, lights toward mint white)
# --------------------------------------------------------------------------------------
ICE = ["#060a33", "#0a1a5a", "#0e2f88", "#1348b4", "#1767d4", "#1a8ae9", "#21b2f4", "#33dcfb",
       "#55ffff", "#98fff8", "#d6fffb", "#ffffff"]
FROST = ["#4f93d8", "#7cc0ef", "#a9e3fb", "#d2f6ff", "#effdff", "#ffffff"]
WHITE = "#ffffff"

# --------------------------------------------------------------------------------------
# The shared egg body (every egg in the set uses exactly this profile)
# --------------------------------------------------------------------------------------
EGG_PROFILE = [(0, 1.4), (0.7, 3.2), (1.8, 4.3), (3.3, 4.9), (5.0, 5.0), (6.8, 4.7), (8.4, 4.0),
               (9.8, 3.0), (10.9, 1.8), (11.5, 0.6)]
EGG_CENTER = (8.0, 2.2, 8.0)
CX, CY, CZ = EGG_CENTER
TOP = EGG_PROFILE[-1][0]
SLICES = [(h0, h1, (r0 + r1) / 2) for (h0, r0), (h1, r1) in zip(EGG_PROFILE, EGG_PROFILE[1:])]

# --------------------------------------------------------------------------------------
# The shell map: 32 px per half turn (it repeats every 180 degrees), rows 0-23 the shell
# top to bottom (whole rows per slice, texels about square), rows 24-31 the ledge colours.
# --------------------------------------------------------------------------------------
N = 32
ROWS = (1, 2, 3, 4, 4, 4, 3, 2, 1)                     # rows per slice, bottom slice first
ROW0 = [sum(ROWS[k + 1:]) for k in range(len(ROWS))]   # top row of each slice
SHELL_ROWS = sum(ROWS)
GUI_ROT = (14.0, -11.25, 0.0)
PSI_FRONT = -GUI_ROT[1]          # azimuth (0 = +Z, 90 = +X) the inventory camera looks at
PSI_U0 = PSI_FRONT - 90.0        # azimuth at u = 0: a facet fold, so every face gets whole texels
FRAMES, FRAMETIME = 32, 2

BAYER = (0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)


def clamp(v: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, v))


def smooth(e0: float, e1: float, x: float) -> float:
    k = clamp((x - e0) / (e1 - e0))
    return k * k * (3 - 2 * k)


def _norm(v):
    length = math.sqrt(sum(c * c for c in v))
    return tuple(c / length for c in v)


def _dot(a, b) -> float:
    return sum(x * y for x, y in zip(a, b))


def bayer(x: int, y: int) -> float:
    return (BAYER[(y % 4) * 4 + x % 4] + 0.5) / 16.0 - 0.5


def radius(h: float) -> float:
    h = clamp(h, 0.0, TOP)
    for (h0, r0), (h1, r1) in zip(EGG_PROFILE, EGG_PROFILE[1:]):
        if h0 <= h <= h1:
            return r0 + (r1 - r0) * (h - h0) / (h1 - h0)
    return EGG_PROFILE[-1][1]


def slope(h: float, w: float = 0.6) -> float:
    a, b = clamp(h - w, 0.0, TOP), clamp(h + w, 0.0, TOP)
    return (radius(b) - radius(a)) / (b - a)


def row_info(y: int) -> tuple[int, float, float]:
    """(slice, height at the texel's top edge, height at its bottom edge) of map row y."""
    for k in range(len(SLICES)):
        if ROW0[k] <= y < ROW0[k] + ROWS[k]:
            h0, h1, _ = SLICES[k]
            dh = (h1 - h0) / ROWS[k]
            i = y - ROW0[k]
            return k, h1 - i * dh, h1 - (i + 1) * dh
    raise ValueError(y)


def row_h(y: int) -> float:
    _, a, b = row_info(y)
    return (a + b) / 2


def col_psi(x: float) -> float:
    """Azimuth of map column x's centre relative to the front (-90..90 degrees)."""
    return (x + 0.5) * 180.0 / N - 90.0


# Light, in the "front frame": x to the viewer's right, y up, z toward the inventory camera.
PITCH = math.radians(GUI_ROT[0])
VIEW = (0.0, math.sin(PITCH), math.cos(PITCH))
KEY = _norm((-0.62, 0.45, 0.62))
HALF = _norm(tuple(a + b for a, b in zip(KEY, VIEW)))
CAUSTIC = _norm((0.62, -0.45, 0.64))    # light gathering inside the ice, lower right


def _geo():
    geo = {}
    for y in range(SHELL_ROWS):
        h = row_h(y)
        beta = math.atan(-slope(h))
        for x in range(N):
            psi = math.radians(col_psi(x))
            n = (math.sin(psi) * math.cos(beta), math.sin(beta), math.cos(psi) * math.cos(beta))
            geo[x, y] = (psi, h, n)
    return geo


GEO = _geo()


def lighting(x: int, y: int) -> dict:
    """Baked light on shell texel (x, y). Left-right leaning terms fade out toward the side
    folds so the map joins its own repeat without a seam."""
    psi, _, n = GEO[x, y]
    lean = math.cos(psi)
    d = 0.5 + 0.5 * _dot(n, KEY)
    dm = 0.5 + 0.5 * _dot((-n[0], n[1], n[2]), KEY)
    sym = (d + dm) / 2
    return {"diffuse": sym + lean * (d - sym),
            "facing": max(0.0, _dot(n, VIEW)),
            "gloss": _dot(n, HALF) * lean ** 0.5,
            "caustic": max(0.0, _dot(n, CAUSTIC)) * (1.0 - smooth(70.0, 88.0, abs(math.degrees(psi))))}


# --------------------------------------------------------------------------------------
# The snowflake core (its centre texel faces the inventory camera)
# --------------------------------------------------------------------------------------
FLAKE = [
    ".....#.....",
    "...#.#.#...",
    "....###....",
    "#..#.#.#..#",
    ".##..#..##.",
    "...##@##...",
    ".##..#..##.",
    "#..#.#.#..#",
    "....###....",
    "...#.#.#...",
    ".....#.....",
]
FLAKE_C = (16, 12)
FLAKE_PX = {(FLAKE_C[0] - 5 + i, FLAKE_C[1] - 5 + j): ch
            for j, row in enumerate(FLAKE) for i, ch in enumerate(row) if ch != "."}
FLAKE_TIPS = [(16, 7), (21, 10), (21, 14), (16, 17), (11, 14), (11, 10)]   # clockwise from the top
PSI_CORE = math.radians(col_psi(FLAKE_C[0]))
H_CORE = row_h(FLAKE_C[1])
WINDOW_IN, WINDOW_OUT = 2.9, 3.9         # units: deep ice inside, clearing to the shell outside


def core_dist(x: int, y: int) -> float:
    psi, h, _ = GEO[x, y]
    return math.hypot(4.9 * (psi - PSI_CORE), h - H_CORE)


FLAKE_NEAR = {}
for _y in range(SHELL_ROWS):
    for _x in range(N):
        FLAKE_NEAR[_x, _y] = min(math.hypot(_x - a, _y - b) for (a, b) in FLAKE_PX)


# --------------------------------------------------------------------------------------
# Frost ferns: a stem with crisp branches every other texel, snapped to the 8 pixel
# directions and leaning toward the tip. Every texel knows how far the frost has to creep
# from the root to reach it.
# --------------------------------------------------------------------------------------

def line(p0, p1):
    (x0, y0), (x1, y1) = p0, p1
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    out = []
    while True:
        out.append((x0, y0))
        if (x0, y0) == (x1, y1):
            return out
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def fern(stem_points, lengths, sides=(-1, 1), every=2, first=1) -> dict:
    """{(x, y): (creep distance, kind)} for one fern; kind 0 = stem, 1+ = branch step."""
    stem = []
    for a, b in zip(stem_points, stem_points[1:]):
        for p in line(a, b):
            if not stem or stem[-1] != p:
                stem.append(p)
    raw = {p: 0 for p in stem}
    n = len(stem)
    for idx, i in enumerate(range(first, n - 1, every)):
        (xa, ya), (xb, yb) = stem[max(0, i - 1)], stem[min(n - 1, i + 1)]
        ang = math.atan2(yb - ya, xb - xa)
        length = lengths[min(idx, len(lengths) - 1)]
        x, y = stem[i]
        for s in sides:
            a = ang + s * math.radians(45)
            dx, dy = round(math.cos(a)), round(math.sin(a))
            for j in range(1, length + 1):
                p = (x + dx * j, y + dy * j)
                if p not in raw:
                    raw[p] = j
    # creep distance: breadth-first from the root through the fern's own texels
    dist = {stem[0]: 0.0}
    queue = [stem[0]]
    while queue:
        nxt = []
        for (x, y) in queue:
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    p = (x + dx, y + dy)
                    if p in raw and p not in dist:
                        dist[p] = dist[(x, y)] + (1.0 if dx == 0 or dy == 0 else 1.4)
                        nxt.append(p)
        queue = nxt
    out = {}
    for (x, y), kind in raw.items():
        if 0 <= y < SHELL_ROWS and (x % N, y) not in FLAKE_PX and (x, y) in dist:
            out[(x % N, y)] = (dist[(x, y)], kind)
    return out


# Six ferns radiate from the window's rim through the gaps between the snowflake's arms;
# (stem points, branch lengths from the root out, sides)
FERN_SPECS = [
    ([(19, 7), (20, 5), (22, 3), (25, 2)], (3, 3, 2, 2, 1), (-1, 1)),    # up right, curling out
    ([(13, 7), (12, 5), (10, 3), (7, 2)], (3, 3, 2, 2, 1), (-1, 1)),     # up left
    ([(19, 17), (20, 19), (22, 21), (25, 22)], (3, 3, 2, 1), (-1, 1)),       # down right
    ([(13, 17), (12, 19), (10, 21), (7, 22)], (3, 3, 2, 1), (-1, 1)),        # down left
    ([(22, 12), (24, 11), (26, 9), (27, 6)], (3, 2, 1), (-1, 1)),           # out to the right flank
    ([(10, 12), (8, 11), (6, 9), (5, 6)], (3, 2, 1), (-1, 1)),              # out to the left flank
]
FERNS = [fern(stem, lengths, sides) for stem, lengths, sides in FERN_SPECS]
ETCHED = {}
for _f in FERNS:
    for _key, (_d, _kind) in _f.items():
        ETCHED[_key] = min(_kind, ETCHED.get(_key, 9))


# Fracture planes frozen into the ice (pale line, dark edge below) and trapped bubbles,
# kept clear of the window and the ferns.
FRACTURES = [[(2, 12), (3, 14), (3, 17), (4, 19)], [(29, 11), (28, 13), (29, 16)],
             [(1, 3), (3, 5)], [(26, 19), (28, 21)], [(14, 21), (16, 22), (18, 22)]]
BUBBLES = [(4, 2), (1, 8), (28, 4), (30, 18), (2, 21), (25, 22), (11, 23), (21, 0)]


def _cracks():
    lit, dark = set(), set()
    for poly in FRACTURES:
        for a, b in zip(poly, poly[1:]):
            lit.update(line(a, b))
    for (x, y) in lit:
        if (x, y + 1) not in lit:
            dark.add((x, y + 1))
    return {k: 1 for k in lit} | {k: -1 for k in dark - lit}


CRACKS = _cracks()


# --------------------------------------------------------------------------------------
# The shell texture (static): lit ice, the deep window, bloom, etched ferns and cracks
# --------------------------------------------------------------------------------------

def body_tone(x: int, y: int) -> float:
    """The lit ice alone, before the window and the frost."""
    L = lighting(x, y)
    tone = 1.6 + 8.0 * L["diffuse"] ** 1.45 - 3.0 * (1.0 - L["facing"]) ** 1.4
    # light gathering inside the ice on the far side from the key, like a glass orb
    f = L["facing"]
    tone += 5.0 * smooth(0.76, 0.95, L["caustic"]) * math.exp(-((f - 0.46) / 0.22) ** 2)
    # the dome deepens under the crown, so the crystals stand clear of the shell
    tone -= 1.6 * smooth(8.2, 9.8, GEO[x, y][1]) + 1.6 * smooth(9.6, 11.0, GEO[x, y][1])
    if L["gloss"] > 0.99:
        tone = 11.5
    elif L["gloss"] > 0.968:
        tone += 1.6
    return tone


def shell_tone(x: int, y: int) -> float:
    tone = body_tone(x, y)
    d = core_dist(x, y)
    w = 1.0 - smooth(WINDOW_IN, WINDOW_OUT, d)
    psi, h, _ = GEO[x, y]
    lean = (4.9 * (psi - PSI_CORE) - (h - H_CORE)) / 2.9          # toward the lower right
    tone = tone * (1 - w) + (1.6 + 0.45 * d + 0.8 * lean) * w
    tone += 1.0 * math.exp(-((d - WINDOW_OUT + 0.1) / 0.3) ** 2)       # the window's rim catches the light
    fd = FLAKE_NEAR[x, y]
    tone += 3.3 if fd < 1.01 else 1.0 if fd < 1.5 else 0.0
    return tone


GLINT = {(1, 0): 11, (0, 1): 11, (1, 1): 10, (2, -1): 9, (-1, 2): 9}     # a 2x2 spark with a "/" glint


GLINT_AT = (5, 4)       # hand-placed on the clear upper-left shoulder, where the key light glances


def shell_colour(x: int, y: int, ferns: bool = True) -> str:
    tone = shell_tone(x, y)
    if ferns:
        kind = ETCHED.get((x, y))
        if kind is not None:
            tone += 1.3 if kind == 0 else 0.8
        elif (x, y) in CRACKS:
            tone += 2.2 if CRACKS[x, y] > 0 else -1.4
        elif (x, y) in BUBBLES:
            tone += 2.6
        elif (x, y - 1) in BUBBLES:
            tone -= 1.0
    return ICE[int(clamp(round(tone + bayer(x, y) * 0.3), 0, 11))]


def paint_shell():
    img = canvas(N)
    px = img.load()
    for y in range(SHELL_ROWS):
        for x in range(N):
            colour = shell_colour(x, y)
            if (x, y) in FLAKE_PX:
                colour = WHITE if FLAKE_PX[x, y] in "#@" else ICE[9]
            px[x, y] = rgba(colour)
    gx, gy = GLINT_AT
    for (dx, dy), idx in GLINT.items():
        px[(gx + dx) % N, gy + dy] = rgba(ICE[idx])
    px[gx, gy] = rgba(WHITE)
    # Ledges: one texel per slice and facet, the average of the shell beside it, so the
    # terraces between slices carry on the shell's light instead of striping it.
    for k in range(len(SLICES)):
        for j in range(8):
            for base, y in ((24, ROW0[k]), (28, ROW0[k] + ROWS[k] - 1)):
                cols = [rgba(shell_colour(4 * j + c, y, ferns=False)) for c in range(4)]
                avg = tuple(round(sum(c[i] for c in cols) / 4) for i in range(3)) + (255,)
                i = 8 * k + j
                px[i % N, base + i // N] = avg
    save(img, "shell")


def ledge_uv(k: int, j: int, up: bool) -> list[float]:
    i = 8 * k + j
    tx, ty = i % N, (24 if up else 28) + i // N
    return [tx / 2 + 0.125, ty / 2 + 0.125, tx / 2 + 0.375, ty / 2 + 0.375]


# --------------------------------------------------------------------------------------
# The frost skin (animated, self-lit): the core's flare, creeping frost, twinkles
# --------------------------------------------------------------------------------------
T_FLARE = 0.0                        # the core flares at the top of the loop ...
WAVE = 0.2                           # ... a ring of cold light races out over the shell ...
GROW = (0.05, 0.42)                  # ... frost creeps out along the ferns in its wake ...
HOLD = 0.70                          # ... shimmers until here ...
SWEEP = (0.44, 0.76)                 # a glassy glint rises across the frozen egg
GONE = 0.93                          # ... and has sublimated by here


def _thresholds():
    rng = random.Random(5)
    return {(x, y): rng.random() for y in range(SHELL_ROWS) for x in range(N)}


DISSOLVE = _thresholds()
FERN_LAG = [0.0, 0.03, 0.05, 0.02, 0.07, 0.04]


def blend(dst, src):
    sa, da = src[3] / 255, dst[3] / 255
    oa = sa + da * (1 - sa)
    if oa <= 0:
        return (0, 0, 0, 0)
    rgb = tuple(round((src[i] * sa + dst[i] * da * (1 - sa)) / oa) for i in range(3))
    return (*rgb, round(oa * 255))


def with_alpha(colour, alpha: float):
    c = rgba(colour)
    return (c[0], c[1], c[2], int(round(clamp(alpha) * 255)))


def flare(t: float) -> float:
    """The core's flare: a quick rise at T_FLARE, a slower fade."""
    s = (t - T_FLARE + 0.04) % 1.0
    if s < 0.04:
        return smooth(0.0, 0.04, s)
    return 1.0 - smooth(0.04, 0.34, s)


def frost_level(f: int, d: float, length: float, key, t: float) -> float:
    """How frosted a fern texel is at loop time t: 0 none, 1 settled, up to 2 glittering."""
    lag = FERN_LAG[f]
    g0, g1 = GROW[0] + lag, GROW[1] + lag
    if t < g0 or t >= GONE:
        return 0.0
    if t < g1:
        front = (t - g0) / (g1 - g0) * (length + 1.5)
        if d > front:
            return 0.0
        return 2.0 if front - d < 1.3 else 1.0
    if t < HOLD:
        wave_at = (t - g1) / (HOLD - g1) * (length + 5.0) - 2.0
        return 1.6 if abs(d - wave_at) < 0.9 else 1.0
    p = (t - HOLD) / (GONE - HOLD)
    r = DISSOLVE[key]
    if r <= p:
        return 0.0
    return 1.8 if r - p < 0.07 else 1.0


def frost_frame(t: float):
    img = canvas(N)
    px = img.load()
    frost = {}
    for f, texels in enumerate(FERNS):
        length = max(d for d, _ in texels.values())
        for key, (d, kind) in texels.items():
            level = frost_level(f, d, length, key, t)
            if level > 0:
                frost[key] = max(frost.get(key, 0.0), level - (0.3 if kind else 0.0))
    for (x, y), level in frost.items():
        if level >= 1.45:
            px[x, y] = rgba(WHITE)
        elif level >= 0.95:
            px[x, y] = rgba(FROST[4])
        else:
            px[x, y] = with_alpha(FROST[3], 0.92)
    # A soft haze round the frost, so it reads as frosted, translucent ice.
    haze = set()
    for (x, y) in frost:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            key = ((x + dx) % N, y + dy)
            if 0 <= key[1] < SHELL_ROWS and key not in frost and key not in FLAKE_PX:
                haze.add(key)
    for key in haze:
        px[key] = blend(px[key], with_alpha(FROST[2], 0.3))
    # The freeze wave: a bright ring racing out from the core, a pale wake behind it.
    s = (t - T_FLARE) % 1.0
    if s < WAVE:
        k = s / WAVE
        front = 2.5 + 17.0 * k ** 0.85
        fade = 1.0 - k ** 2
        for y in range(SHELL_ROWS):
            for x in range(N):
                dx = min(abs(x - FLAKE_C[0]), N - abs(x - FLAKE_C[0]))
                r = math.hypot(dx, (y - FLAKE_C[1]) * 1.1)
                band = math.exp(-((r - front) / 1.0) ** 2)
                wake = 0.3 * smooth(front - 5.0, front - 0.5, r) if r < front else 0.0
                a = fade * max(0.9 * band, wake)
                if a > 0.06 and (x, y) not in FLAKE_PX:
                    px[x, y] = blend(px[x, y], with_alpha(WHITE if band > 0.6 else FROST[2], a))
    # The glint: a bright "/" band rising up across the front, fading toward the folds.
    if SWEEP[0] <= t < SWEEP[1]:
        k = (t - SWEEP[0]) / (SWEEP[1] - SWEEP[0])
        centre = -8.0 + 38.0 * k
        for y in range(SHELL_ROWS):
            for x in range(N):
                d = (SHELL_ROWS - y - 0.5) - (x + 0.5 - 16.0) * 0.45 - centre
                edge = math.cos(math.radians(col_psi(x))) ** 1.2
                core = max(0.0, 1.0 - abs(d) / 1.4)
                halo = max(0.0, 1.0 - abs(d) / 3.4)
                a = edge * max(0.95 * core, 0.4 * halo)
                if a > 0.06 and (x, y) not in FLAKE_PX:
                    px[x, y] = blend(px[x, y], with_alpha(WHITE if core > 0.5 else FROST[3], a))
    # The glittering fronts throw little sparkles.
    for (x, y), level in frost.items():
        if level >= 1.95 and DISSOLVE[x, y] < 0.3:
            sparkle(img, x, y, 0.7, WHITE, reach=1)
    # The snowflake core and the ring of cold light it pulses out through the window.
    fl = flare(t)
    s = (t - T_FLARE) % 1.0
    ring_r = 1.5 + 6.5 * smooth(0.0, 0.22, s) if s < 0.22 else None
    for y in range(SHELL_ROWS):
        for x in range(N):
            if (x, y) in FLAKE_PX or core_dist(x, y) > WINDOW_OUT + 0.5:
                continue
            fd = FLAKE_NEAR[x, y]
            a = fl * (0.6 if fd < 1.01 else 0.35 if fd < 1.5 else 0.2) * (1.0 - smooth(WINDOW_IN, WINDOW_OUT, core_dist(x, y)))
            if ring_r is not None:
                rd = math.hypot(x - FLAKE_C[0], (y - FLAKE_C[1]) * 1.1)
                a = max(a, (1.0 - s / 0.22) * 0.8 * math.exp(-((rd - ring_r) / 0.75) ** 2))
            if a > 0.05:
                px[x, y] = blend(px[x, y], with_alpha(ICE[9], a))
    for (x, y), ch in FLAKE_PX.items():
        if ch == "o":
            colour = WHITE if fl > 0.3 else ICE[9]
        else:
            colour = WHITE if ch == "@" or fl > 0.1 else FROST[4]
        px[x, y] = rgba(colour)
    for i, (x, y) in enumerate(FLAKE_TIPS):
        at = 0.46 + i * 0.04
        dt = abs(((t - at) + 0.5) % 1.0 - 0.5)
        sparkle(img, x, y, clamp(1.0 - dt / 0.045), WHITE, reach=2)
    return img


# --------------------------------------------------------------------------------------
# Crown crystals: a shading atlas, 8 lighting levels (4 px columns) x tip -> base rows
# --------------------------------------------------------------------------------------
CRYSTAL_TONES = (1.8, 2.7, 3.7, 4.9, 6.2, 7.4, 8.5, 9.4)
CROWN_RUN = (0.04, 0.14)          # light runs up the crown just after the flare
TIP_FLASH = 0.16


def crystal_frame(t: float):
    img = canvas(N)
    px = img.load()
    run = (t - CROWN_RUN[0]) / (CROWN_RUN[1] - CROWN_RUN[0])
    band = 33.0 - 38.0 * run if 0.0 <= run < 1.0 else None
    dt = abs(((t - TIP_FLASH) + 0.5) % 1.0 - 0.5)
    flash = clamp(1.0 - dt / 0.05)
    for j, base in enumerate(CRYSTAL_TONES):
        for r in range(N):
            k = r / (N - 1)                               # 0 at the tip, 1 at the base
            tone = base + 1.4 * (1.0 - k) - 0.8 * smooth(0.75, 1.0, k)
            if band is not None:
                tone += 3.0 * math.exp(-((r - band) / 2.4) ** 2)
            if r < 6:
                tone += 3.0 * flash * (1.0 - r / 6)
            idx = int(clamp(round(tone), 0, 11))
            for c in range(4):
                px[4 * j + c, r] = rgba(ICE[idx])
    return img


STARS = (  # crisp, opaque star sprites, smallest to largest (5 x 5)
    [".....", ".....", "..w..", ".....", "....."],
    [".....", "..c..", ".cwc.", "..c..", "....."],
    ["..c..", "..w..", "cwwwc", "..w..", "..c.."],
    ["..w..", ".cwc.", "wwwww", ".cwc.", "..w.."],
)


def twinkle_frame(t: float):
    """Four star sparkles, one per 5 px cell, twinkling in turn."""
    img = canvas(N)
    for j, phase in enumerate((0.1, 0.36, 0.6, 0.82)):
        dt = abs(((t - phase) + 0.5) % 1.0 - 0.5)
        a = clamp(1.0 - dt / 0.11)
        if a <= 0.05:
            continue
        star = STARS[min(3, int(a * 4))]
        for yy, row in enumerate(star):
            for xx, ch in enumerate(row):
                if ch != ".":
                    img.putpixel((8 * j + xx, yy), rgba(WHITE if ch == "w" else FROST[3]))
    return img


def textures() -> None:
    paint_shell()
    save_animation(animate(frost_frame, FRAMES), "frost", frametime=FRAMETIME)
    save_animation(animate(crystal_frame, FRAMES), "crystal", frametime=FRAMETIME)
    save_animation(animate(twinkle_frame, FRAMES), "twinkle", frametime=FRAMETIME)


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------
LOCAL_N = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0),
           "up": (0, 1, 0), "down": (0, -1, 0)}
OVERLAY_EPS = 0.05
OVERLAY_SEAM = round(OVERLAY_EPS * math.tan(math.pi / 16) + 0.004, 4)


def world_normal(e: dict, side: str):
    m, _ = rotation_of(e)
    n = LOCAL_N[side]
    return tuple(sum(m[i][k] * n[k] for k in range(3)) for i in range(3))


def face_psi(e: dict, side: str) -> float:
    w = world_normal(e, side)
    return math.degrees(math.atan2(w[0], w[2]))


def facet_of(psi_c: float) -> int:
    """Which of the map's 8 facet columns (4 texels each) the facet facing psi_c shows."""
    return round((psi_c - 11.25 - PSI_U0) / 22.5) % 8


def slice_of(e: dict) -> int:
    mid = (e["from"][1] + e["to"][1]) / 2 - CY
    return next(k for k, (h0, h1, _) in enumerate(SLICES) if h0 - 1e-6 <= mid <= h1 + 1e-6)


def shell() -> list[dict]:
    parts = lathe(EGG_CENTER, EGG_PROFILE, "shell", sides=16, shade=False)
    for e in parts:
        k = slice_of(e)
        v0, v1 = ROW0[k] / 2, (ROW0[k] + ROWS[k]) / 2
        ends = [s for s in e["faces"] if s not in ("up", "down")]
        j_end = facet_of(face_psi(e, ends[0]))          # both ends of a slab show the same column
        for side in list(e["faces"]):
            face = e["faces"][side]
            if side == "up":
                if k < 3:
                    del e["faces"][side]
                else:
                    face["uv"] = ledge_uv(k, j_end, True)
            elif side == "down":
                if k > 3:
                    del e["faces"][side]
                else:
                    face["uv"] = ledge_uv(k, j_end, False)
            else:
                u = 2.0 * facet_of(face_psi(e, side))
                face["uv"] = [u, v0, u + 2, v1]
    return parts


def frost_skin(shell_parts: list[dict]) -> list[dict]:
    """A self-lit twin of the shell floating just outside it, carrying the frost."""
    skin = []
    for e in shell_parts:
        g = copy(e)
        sides = [s for s in g["faces"] if s not in ("up", "down")]
        axis, across = (0, 2) if "east" in sides else (2, 0)
        g["from"][axis] = round(g["from"][axis] - OVERLAY_EPS, 4)
        g["to"][axis] = round(g["to"][axis] + OVERLAY_EPS, 4)
        g["from"][across] = round(g["from"][across] - OVERLAY_SEAM, 4)
        g["to"][across] = round(g["to"][across] + OVERLAY_SEAM, 4)
        g["faces"] = {s: {"uv": list(g["faces"][s]["uv"]), "texture": "#frost"} for s in sides}
        g["light_emission"] = 15
        g["shade"] = False
        skin.append(g)
    return skin


# --- the crown -------------------------------------------------------------------------

def _front_to_model(v):
    """A front-frame vector (x right, y up, z toward the inventory camera) in model space."""
    a = math.radians(PSI_FRONT)
    x, y, z = v
    return (x * math.cos(a) + z * math.sin(a), y, -x * math.sin(a) + z * math.cos(a))


CROWN_KEY = _front_to_model(_norm((-0.6, 0.55, 0.58)))


def shade_crystal(parts: list[dict], length: float) -> list[dict]:
    """Point every face at the crystal atlas: the lighting column from its normal, the rows
    from where it sits along the crystal (built upright: foot at y = 0, point at `length`)."""
    for e in parts:
        y0, y1 = e.pop("_y")
        for side, face in e["faces"].items():
            lit = _dot(world_normal(e, side), CROWN_KEY)
            j = int(clamp(round(3.4 + 4.6 * lit), 0, 7))
            v0 = 16.0 * (1 - clamp(y1 / length))
            v1 = 16.0 * (1 - clamp(y0 / length))
            if side in ("up", "down") or v1 - v0 < 0.3:
                v1 = min(16.0, v0 + 0.3)
            face["uv"] = [2.0 * j + 0.25, round(v0, 4), 2.0 * j + 1.75, round(v1, 4)]
            face["texture"] = "#crystal"
    return parts


def crystal(length: float, width: float, tilt: float, yaw: float, foot, spin: float = 0.0,
            glow: int = 6) -> list[dict]:
    """An ice crystal: a diamond-section shaft, a narrower neck and a pointed termination
    (a cube standing on a corner), built upright, then tilted `tilt` degrees toward
    azimuth `yaw` (0 = +Z, 90 = +X) with its foot at `foot`."""
    w = width
    tip_c = 0.56 * w
    neck_w = 0.72 * w
    neck_top = length - 0.6 * tip_c
    body_top = neck_top - 0.26 * length
    shaft = box((-w / 2, 0, -w / 2), (w / 2, body_top, w / 2), "crystal", skip=("down",), glow=glow, shade=False)
    shaft["_y"] = (0.0, body_top)
    neck = box((-neck_w / 2, body_top - 0.02, -neck_w / 2), (neck_w / 2, neck_top, neck_w / 2), "crystal",
               skip=("down",), glow=glow, shade=False)
    neck["_y"] = (body_top, neck_top)
    turn([shaft, neck], 45.0 + spin, "y", (0, 0, 0))
    tip = box((-tip_c / 2, -tip_c / 2, -tip_c / 2), (tip_c / 2, tip_c / 2, tip_c / 2), "crystal", glow=glow,
              shade=False)
    turn(tip, -45.0, "y", (0, 0, 0))
    turn(tip, -54.7356, "x", (0, 0, 0))
    turn(tip, spin, "y", (0, 0, 0))
    move(tip, 0, length - 0.866 * tip_c, 0)
    tip["_y"] = (neck_top, length)
    parts = [shaft, neck, tip]
    turn(parts, tilt, "x", (0, 0, 0))
    turn(parts, yaw, "y", (0, 0, 0))
    move(parts, *foot)
    return shade_crystal(parts, length)


# (azimuth from the front, length, width, lean, foot radius, foot depth, spin, glow)
CROWN = [
    (180.0, 4.3, 1.6, 4.0, 0.0, 1.3, 12.0, 8),      # the central spire
    (-40.0, 3.4, 1.15, 47.0, 0.9, 1.2, 0.0, 6),     # front pair, leaning well out
    (44.0, 3.2, 1.1, 50.0, 0.9, 1.2, 20.0, 6),
    (-150.0, 3.5, 1.2, 36.0, 0.9, 1.2, 35.0, 6),    # back pair
    (145.0, 3.3, 1.15, 38.0, 0.9, 1.2, 50.0, 6),
    (-96.0, 2.6, 0.85, 66.0, 1.3, 1.5, 25.0, 6),    # side shards, splayed low
    (94.0, 2.5, 0.8, 68.0, 1.3, 1.5, 5.0, 6),
]


def crown() -> list[dict]:
    parts = []
    top = CY + TOP
    f = PSI_FRONT
    for dpsi, length, width, tilt, rad, drop, spin, glow in CROWN:
        a = math.radians(f + dpsi)
        foot = (CX + rad * math.sin(a), top - drop, CZ + rad * math.cos(a))
        parts += crystal(length, width, tilt, f + dpsi, foot, spin=spin, glow=glow)
    return parts


def crown_tip(i: int):
    dpsi, length, width, tilt, rad, drop, _, _ = CROWN[i]
    a, b = math.radians(PSI_FRONT + dpsi), math.radians(tilt)
    foot = (CX + rad * math.sin(a), CY + TOP - drop, CZ + rad * math.cos(a))
    d = (math.sin(b) * math.sin(a), math.cos(b), math.sin(b) * math.cos(a))
    return tuple(foot[k] + length * d[k] for k in range(3))


# --- floating sparkles -----------------------------------------------------------------

def twinkles() -> list[dict]:
    """Star sparkles glinting on the crown's points, facing the inventory camera."""
    parts = []
    view = _front_to_model(VIEW)
    for j, (i, lift) in enumerate(((0, -0.7), (1, -0.2), (2, -0.2), (5, 0.0))):
        tx, ty, tz = crown_tip(i)
        x, y, z = tx + view[0] * 1.2, ty + lift + view[1] * 1.2, tz + view[2] * 1.2
        cell = [4.0 * j, 0.0, 4.0 * j + 2.5, 2.5]
        faces = {"south": ("twinkle", cell), "north": ("twinkle", [cell[2], cell[1], cell[0], cell[3]])}
        e = box((x - 1.25, y - 1.25, z - 0.01), (x + 1.25, y + 1.25, z + 0.01), "twinkle",
                faces=faces, skip=("east", "west", "up", "down"), glow=15, shade=False)
        turn(e, -GUI_ROT[0], "x", (x, y, z))
        turn(e, PSI_FRONT, "y", (x, y, z))
        parts.append(e)
    return parts


def build() -> list[dict]:
    body = shell()
    return body + crown() + frost_skin(body) + twinkles()


# --------------------------------------------------------------------------------------
# Display
# --------------------------------------------------------------------------------------
GRIP = (8.0, CY + 4.6, 8.0)
SIZE = 1.25


def facing(direction) -> tuple[float, float, float]:
    """Where model +Z must point so the shell's front (PSI_FRONT) faces `direction`."""
    a = math.radians(-PSI_FRONT)
    x, _, z = direction
    return (x * math.cos(a) + z * math.sin(a), 0.0, -x * math.sin(a) + z * math.cos(a))


def transforms(parts) -> dict:
    d = display(KIND, parts, grip=GRIP, size=SIZE, gui_rotation=GUI_ROT, gui_span=15.6)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": facing((0, 0, 1))}, GRIP, "fist", 0.45 * SIZE)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": facing((-0.35, 0, 1))}, GRIP, (0.5, -0.36, -0.88),
                                       0.5 * SIZE, pose=None)
    # A thrown egg is drawn through "ground" facing the camera: show it the snowflake.
    d["ground"] = fit(parts, (0, GUI_ROT[1], 0), 8.0, lift=2.0)
    d["fixed"] = fit(parts, (0, GUI_ROT[1] - 180, 0), 14.0)
    d["on_shelf"] = fit(parts, (0, GUI_ROT[1] - 180, 0), 12.0)
    return d


def models() -> dict:
    parts = build()
    return {"main": model(parts, transforms(parts))}
