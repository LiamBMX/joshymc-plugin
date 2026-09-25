"""Levitation Egg: a buttery shulker-bullet egg that wants to float away.

The shared egg body (the EGG_PROFILE lathe) in a pale buttery-yellow shell with painted,
self-lit shading, a glossy glint and the shulker bullet's little square rings pressed
faintly into it. Shulker-shell bands belt the equator: a lid rim and a base rim whose
teeth part round a glowing pale-cyan seam, like a shulker cracked open. Small white
feathered wings (painted cut-out layers of flight feathers and coverts on a rounded
leading edge, the outer primaries tipped with the levitation glow) spring from octagonal
shulker bosses with glowing gems, lifted, leant back and swept.

Animation (one 3.2 s loop): a soft iridescent shimmer (pale cyan leading edge, white
core, lavender tail) rises up the shell; the seam, the rims beside it and the gems flare
as it passes under the belt; a glint then runs out along both wings; and as it leaves
the top it floats off as a trail of stars twinkling upward above the egg, while little
sparkles drift up over the shell the whole time.
"""
from __future__ import annotations

import math

from art.kit import (animate, bar, box, canvas, display, fit, lathe, mirror, model, place, prism, rgba,
                     rotation_of, save_animation, shine, turn)

ID = "levitation_egg"
NAME = "Levitation Egg"
KIND = "item"
COUNTERPART = "item/egg"

# --------------------------------------------------------------------------------------
# Palettes (darkest -> lightest), hand-tuned and hue-shifted
# --------------------------------------------------------------------------------------
SHELL = ["#8a5e5a", "#b8875c", "#d9aa64", "#edc874", "#f8e088", "#fdef99", "#ffffaa", "#ffffd0", "#fffff0"]
SHULK = ["#2e1a3e", "#4d3160", "#6e4a80", "#8f67a0", "#ad86ba", "#cba6d2", "#e6ccea"]
FEATH = ["#4e3f73", "#7a6aa0", "#a698c8", "#cdc5e4", "#ebe8f6", "#ffffff", "#fffbe6"]
MAGIC = ["#3f9fca", "#6fcbe8", "#a6ecfb", "#d4fbff", "#ffffff"]
IRIS = "#f3e6ff"                 # the lavender tail of the iridescent shimmer
MAUVE = ["#d9b8b4", "#c49a9e"]   # the shulker bullet's square markings, faded into the shell

# --------------------------------------------------------------------------------------
# Layout (model units). The egg stands on its round end, centred on x = z = 8; +Z is
# the front (what the thrown egg shows the camera).
# --------------------------------------------------------------------------------------
EGG_PROFILE = [(0, 1.4), (0.7, 3.2), (1.8, 4.3), (3.3, 4.9), (5.0, 5.0), (6.8, 4.7), (8.4, 4.0), (9.8, 3.0),
               (10.9, 1.8), (11.5, 0.6)]
EGG_C = (8.0, 2.2, 8.0)
EGG_H = 11.5
FRAMES = 32                      # one 3.2 s loop at frametime 2

# The shulker bands round the equator: lid rim over a glowing seam over the base rim.
BELT_LID = (5.05, 6.0, 5.45)     # (h0, h1, apothem), heights above the egg's base
BELT_SEAM = (4.45, 5.05, 5.1)
BELT_BASE = (3.8, 4.45, 5.3)
TEETH = 8                        # base-rim teeth standing across the seam

WING_H = 5.3                     # height of the wing clasps on the belt
SWEEP = 22.0                     # wings sweep back (toward -Z) by this much
LEAN = -6.0                      # and lean back at the top

# The 64 px animated texture "shell": rows 0-31 wrap the shell (64 columns = the full
# turn, 4 per face), the rows below hold everything else that shares its clock.
ROW_LID, ROW_BASE, ROW_SEAM = 32, 36, 40          # 4, 3 and 3 rows x 64 columns
ROW_SWATCH = 44                                   # flat colours, see SW
ROW_CAP_UP, ROW_CAP_DOWN = 45, 46                 # ring-step colours, one column per shell row
ROW_STARS = 48                                    # 8 twinkle cells of 8x8 texels
SW = {f"s{i}": i for i in range(7)} | {"gem": 8} | {f"f{i}": 16 + i for i in range(7)}
SHULK_SW = [f"s{i}" for i in range(7)]
FEATH_SW = [f"f{i}" for i in range(7)]


def h2y(h: float) -> float:
    return EGG_C[1] + h


def row_of(h: float) -> float:
    """Texel row (0..32, top = 0) of the shell wrap at height h above the egg's base."""
    return (EGG_H - h) / EGG_H * 32


def height_of(row: float) -> float:
    return EGG_H - row / 32 * EGG_H


# --------------------------------------------------------------------------------------
# UV helpers
# --------------------------------------------------------------------------------------
_NORMALS = {"east": (1, 0, 0), "west": (-1, 0, 0), "south": (0, 0, 1), "north": (0, 0, -1),
            "up": (0, 1, 0), "down": (0, -1, 0)}


def normal(e, side):
    m = rotation_of(e)[0]
    n = _NORMALS[side]
    return tuple(sum(m[i][k] * n[k] for k in range(3)) for i in range(3))


def face_index(e, side, count=16):
    """Which of the `count` directions round the Y axis this face looks toward
    (0 = +X, counting toward +Z)."""
    nx, _, nz = normal(e, side)
    return round(math.degrees(math.atan2(nz, nx)) / (360 / count)) % count


def column_block(k: int) -> int:
    """Wrap column block (4 texels) for the face looking toward direction k: the front
    (+Z) is block 8, the viewer's left (-X) block 4, the back block 0."""
    return (12 - k) % 16


def texel_uv(tx: int, ty: int, size: int = 64) -> list[float]:
    """A UV rect inside one texel: the face shows that texel's flat colour."""
    s = 16 / size
    return [round(tx * s + s * 0.25, 4), round(ty * s + s * 0.25, 4),
            round(tx * s + s * 0.75, 4), round(ty * s + s * 0.75, 4)]


def sw(name):
    return ("shell", texel_uv(SW[name], ROW_SWATCH))


def wrap_uv(e, side, row0, rows):
    """UV of a ring face: its 4-texel column block, over `rows` rows from row0."""
    c = column_block(face_index(e, side))
    return [float(c), row0 / 4, float(c + 1), (row0 + rows) / 4]


# --------------------------------------------------------------------------------------
# Painted light
# --------------------------------------------------------------------------------------

def _profile_radius(h: float) -> float:
    pts = EGG_PROFILE
    h = max(pts[0][0], min(pts[-1][0], h))
    for (h0, r0), (h1, r1) in zip(pts, pts[1:]):
        if h0 <= h <= h1:
            return r0 + (r1 - r0) * (h - h0) / (h1 - h0)
    return pts[-1][1]


def _slope(h: float) -> float:
    """Angle (radians) the shell's surface normal tilts up from horizontal at height h."""
    d = 0.35
    dr = _profile_radius(min(EGG_H, h + d)) - _profile_radius(max(0.0, h - d))
    return math.atan2(-dr, 2 * d)


def lambert(phi: float, beta: float) -> float:
    """Light on a surface facing phi round the egg from the front (+ = the viewer's
    left) and tilted up by beta: from above, the front and a little from the left.
    Symmetric front/back, so the egg reads the same held, thrown or framed."""
    lam = 0.6 * math.sin(beta) + (0.6 * abs(math.cos(phi)) + 0.2 * math.sin(2 * phi)) * math.cos(beta)
    return max(0.0, lam) + 0.12 * max(0.0, -math.sin(beta))    # a little bounce light underneath


def column_phi(tx: float) -> float:
    return math.radians((34 - tx) / 4 * 22.5)


def pick(ramp, v: float) -> str:
    return ramp[max(0, min(len(ramp) - 1, int(round(v))))]


_STATIC = {}


def shell_static():
    """Base shell values (fractional SHELL indices) over the 64x32 wrap."""
    if "shell" in _STATIC:
        return _STATIC["shell"]
    vals = [[0.0] * 64 for _ in range(32)]
    for ty in range(32):
        h = height_of(ty + 0.5)
        beta = _slope(h)
        for tx in range(64):
            v = 2.9 + 5.4 * lambert(column_phi(tx + 0.5), beta)
            v += 0.18 * math.sin(2 * math.pi * (tx / 16 + ty / 11))   # soft, irregular band borders
            if BELT_BASE[0] - 0.8 < h < BELT_BASE[0]:
                v -= 1.2                                               # contact shadow under the belt
            elif BELT_LID[1] < h < BELT_LID[1] + 0.45:
                v -= 0.6
            vals[ty][tx] = v
    _STATIC["shell"] = vals
    return vals


# --------------------------------------------------------------------------------------
# Animation: the rising shimmer, drifting sparkles, the seam flare, the star trail
# --------------------------------------------------------------------------------------

def shimmer_row(t: float) -> float:
    """Centre row of the rising shimmer: from just below the shell to just above it."""
    return 44.0 - 57.0 * t


def shimmer(ty: float, tx: float, t: float):
    """(brightness boost in ramp steps, iridescent tint or None) at a shell texel. The
    band undulates round the egg: a pale cyan leading edge, a white core, a warm tail."""
    d = ty - (shimmer_row(t) + 2.6 * math.sin(2 * math.pi * tx / 32))
    if d < -6.0 or d > 6.5:
        return 0.0, None
    if d < -4.6:
        return 0.6, MAGIC[2] if (int(tx) + int(ty)) % 2 else None    # a sparkly leading edge
    if d < -2.8:
        return 1.5, MAGIC[3]
    if d < 0.2:
        return 3.0, "#ffffff"
    if d < 2.0:
        return 2.0, IRIS
    return 2.2 * math.exp(-((d - 2.0) / 2.6) ** 2), None


GLYPHS = [(21, 9), (39, 11), (31, 23), (46, 25), (5, 10), (55, 9), (61, 24)]   # (column, row)
GLINT = [(1, 0, 8), (2, 0, 7), (0, 1, 8), (1, 1, 8), (2, 1, 7), (0, 2, 8), (1, 2, 7), (0, 3, 7)]
SPARKLES = [  # (column, start row, rise in rows, birth phase, life, size); columns 18-50 face the GUI
    (29, 31, 19, 0.00, 0.62, 2), (38, 27, 14, 0.16, 0.50, 1), (45, 31, 20, 0.30, 0.62, 2),
    (24, 14, 12, 0.46, 0.46, 2), (35, 13, 11, 0.62, 0.44, 1), (42, 30, 17, 0.72, 0.58, 2),
    (21, 29, 15, 0.86, 0.52, 1), (31, 9, 8, 0.33, 0.40, 1),
    (4, 30, 18, 0.10, 0.6, 2), (12, 27, 14, 0.42, 0.5, 1), (58, 29, 16, 0.64, 0.55, 2),
    (52, 13, 10, 0.88, 0.45, 1),
]


def _blend(px, x, y, colour, k=1.0):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), 255)


def draw_sparkle(px, x, y, amount, size):
    def put(dx, dy, colour, k=1.0):
        if 0 <= y + dy < 32:
            _blend(px, (x + dx) % 64, y + dy, colour, k)

    if amount < 0.12:
        return
    if amount < 0.4:
        put(0, 0, MAGIC[2])
        return
    put(0, 0, MAGIC[4])
    arm = MAGIC[3] if amount > 0.72 else MAGIC[2]
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        put(dx, dy, arm)
    if size >= 2 and amount > 0.72:
        for dx, dy in ((2, 0), (-2, 0), (0, 2), (0, -2)):
            put(dx, dy, MAGIC[1], 0.9)


def ring_light(px, row0, betas, offsets, ramp, plates=True):
    """Paint rows of a band round the egg with the same light as the shell: one row per
    beta (surface tilt), each shifted by an offset; plates two faces wide."""
    for j, (beta, off) in enumerate(zip(betas, offsets)):
        for tx in range(64):
            v = 1.0 + 5.0 * lambert(column_phi(tx + 0.5), math.radians(beta)) + off
            if plates and tx % 8 == 7:
                v -= 1.3                        # the dark seam between two plates
            elif plates and tx % 8 == 0:
                v += 0.7                        # and the lit edge of the next
            px[tx, row0 + j] = rgba(pick(ramp, v))


STARS = [  # (x, y, z, size, cell): drifting up off the top of the egg, lowest first
    (4.0, 13.2, 9.4, 2.0, 0), (12.4, 14.3, 9.2, 2.3, 1), (6.2, 15.6, 8.8, 2.6, 2), (10.2, 16.9, 8.4, 2.1, 3)]
STAR_START, STAR_STEP, STAR_LIFE = 0.72, 0.075, 0.32


def star_cell(px, cell, t):
    """A 4-point twinkle in cell `cell`, peaking once per loop in rising order."""
    ox, oy = cell * 8, ROW_STARS
    age = (t - (STAR_START + STAR_STEP * cell)) % 1.0
    for y in range(8):
        for x in range(8):
            px[ox + x, oy + y] = (0, 0, 0, 0)
    if age >= STAR_LIFE:
        return
    a = math.sin(math.pi * age / STAR_LIFE)
    if a < 0.15:
        return
    cx, cy = ox + 3, oy + 3
    px[cx, cy] = rgba(MAGIC[4] if a > 0.4 else MAGIC[3])
    reach = 1 if a < 0.55 else (2 if a < 0.85 else 3)
    for i in range(1, reach + 1):
        c = MAGIC[4] if i == 1 and a > 0.7 else (MAGIC[3] if i < reach else MAGIC[2])
        for dx, dy in ((i, 0), (-i, 0), (0, i), (0, -i)):
            px[cx + dx, cy + dy] = rgba(c)
    if a > 0.85:
        for dx, dy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
            px[cx + dx, cy + dy] = rgba(MAGIC[2])


def shell_frame(t: float):
    vals = shell_static()
    img = canvas(64)
    px = img.load()
    for ty in range(32):
        for tx in range(64):
            boost, tint = shimmer(ty + 0.5, tx + 0.5, t)
            v = vals[ty][tx] + boost
            px[tx, ty] = rgba(tint if tint and v < 8.3 else pick(SHELL, v))
    # the shulker bullet's little square rings, pressed faintly into the shell
    for gx, gy in GLYPHS:
        for dx in range(4):
            for dy in range(4):
                x, y = (gx + dx) % 64, gy + dy
                edge = dx in (0, 3) or dy in (0, 3)
                if (dx, dy) in ((0, 0), (3, 0), (0, 3), (3, 3)):
                    continue                                   # soft, rounded corners
                boost = shimmer(y + 0.5, x + 0.5, t)[0]
                if boost > 1.2:
                    continue
                v = vals[y][x] + boost
                px[x, y] = rgba(MAUVE[1] if edge and v < 5.5 else (MAUVE[0] if edge else pick(SHELL, v + 0.6)))
    # a glossy glint high on the lit side (front and back)
    for gx in (26, 58):
        for dx, dy, v in GLINT:
            x, y = gx + dx, 6 + dy
            if shimmer(y + 0.5, x + 0.5, t)[0] < 1.0:
                px[x % 64, y] = rgba(SHELL[v])
    for col, start, rise, phase, life, size in SPARKLES:
        age = (t - phase) % 1.0
        if age < life:
            s = age / life
            draw_sparkle(px, col + round(0.9 * math.sin(2 * math.pi * s)), round(start - rise * s),
                         math.sin(math.pi * s), size)
    # ring steps: the colour an up- or down-facing ledge shows at each shell row
    # (graded by height, so seen from above the dome still reads round, not as a disc)
    for ty in range(32):
        boost, tint = shimmer(ty + 0.5, 34, t)
        px[ty, ROW_CAP_UP] = rgba(tint if tint else pick(SHELL, 0.5 * vals[ty][34] + 0.5 * (8.2 - 0.2 * ty) + boost))
        px[ty, ROW_CAP_DOWN] = rgba(pick(SHELL, 3.0 + 0.1 * (31 - ty) + boost * 0.5))
    # shulker bands: lid rim (bevelled top edge), base rim
    ring_light(px, ROW_LID, (55, 5, -5, -40), (0.9, 0.2, 0.0, -1.0), SHULK)
    ring_light(px, ROW_BASE, (30, 0, -45), (0.3, -0.2, -1.2), SHULK)
    # the seam's light flares white-cyan as the shimmer passes under the belt
    mid = row_of((BELT_SEAM[0] + BELT_SEAM[1]) / 2)
    flare = max(0.0, 1.0 - abs(shimmer_row(t) - mid) / 8.0)
    for j, (base, hot) in enumerate(((MAGIC[2], MAGIC[3]), (MAGIC[3], MAGIC[4]), (MAGIC[1], MAGIC[3]))):
        b0, b1 = rgba(base), rgba(hot)
        col = tuple(round(b0[i] + (b1[i] - b0[i]) * flare) for i in range(3)) + (255,)
        for tx in range(64):
            px[tx, ROW_SEAM + j] = col
    # and spills onto the rims facing it
    if flare > 0.05:
        for tx in range(64):
            _blend(px, tx, ROW_LID + 3, MAGIC[2], 0.55 * flare)
            _blend(px, tx, ROW_BASE, MAGIC[3], 0.45 * flare)
    # flat swatches; the clasp gems pulse with the seam
    for i, c in enumerate(SHULK):
        px[i, ROW_SWATCH] = rgba(c)
    g0, g1 = rgba(MAGIC[2]), rgba(MAGIC[4])
    k = 0.25 + 0.75 * flare
    px[SW["gem"], ROW_SWATCH] = tuple(round(g0[i] + (g1[i] - g0[i]) * k) for i in range(3)) + (255,)
    for i, c in enumerate(FEATH):
        px[SW[f"f{i}"], ROW_SWATCH] = rgba(c)
    for cell in range(len(STARS)):
        star_cell(px, cell, t)
    return img


# --------------------------------------------------------------------------------------
# The wings: painted cut-out layers
# --------------------------------------------------------------------------------------
# Two 32 px regions of the 64 px "wing" texture, stacked a little apart: the flight
# feathers behind, the coverts and the marginal band along the leading edge in front.
# Each region is the right wing seen from the front, painted lying flat (the model lifts
# it): the root at the left, the leading edge rising and running out to the wrist, the
# feathers hanging from it; the primaries past the wrist are longest and make the tip.
EDGE = ((2.0, 22.5), (2.5, 4.0), (30.0, 3.0))    # leading edge: quadratic Bezier, root -> tip
# The scalloped trailing edge: one round flight-feather tip each (x, y, radius), from the
# wing tip back to the root, and where each feather's quill meets the leading edge.
TIPS = [(27.9, 7.8, 2.7), (27.2, 13.8, 3.1), (23.6, 19.4, 3.2), (18.4, 23.6, 3.1), (12.4, 26.3, 2.9),
        (6.6, 27.4, 2.6)]
QUILLS = [0.96, 0.80, 0.63, 0.46, 0.30, 0.14]
COVERT_DEPTH = 0.40              # how far down each flight feather the coverts reach
CYAN_TIPS = 3                    # the outer primaries catch the levitation glow


def edge_point(s: float) -> tuple[float, float]:
    (x0, y0), (x1, y1), (x2, y2) = EDGE
    a, b, c = (1 - s) ** 2, 2 * s * (1 - s), s * s
    return a * x0 + b * x1 + c * x2, a * y0 + b * y1 + c * y2


def _mask(tips, bases, notch=1.6):
    """Texels inside the wing outline closed by the given scallops; the outline between
    them is pulled `notch` texels in toward the quills so each tip stands out."""
    from PIL import Image, ImageDraw
    m = Image.new("L", (32, 32), 0)
    d = ImageDraw.Draw(m)
    inner = []
    for (x, y, _), (bx, by) in zip(tips, bases):
        k = notch / max(1e-6, math.hypot(x - bx, y - by))
        inner.append((x + (bx - x) * k, y + (by - y) * k))
    d.polygon([edge_point(i / 40) for i in range(41)] + inner, fill=255)
    for x, y, r in tips:
        d.ellipse((x - r, y - r, x + r, y + r), fill=255)
    px = m.load()
    return {(x, y) for y in range(32) for x in range(32) if px[x, y]}


def _seg_dist(p, a, b):
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((p[0] - ax) * dx + (p[1] - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(p[0] - ax - t * dx, p[1] - ay - t * dy), t


def _paint_zone(px, ox, oy, cells, axes, colour_of, line, rim):
    """Split cells between feathers (nearest quill), shade each via colour_of(i, t, off)
    (t along its quill, off = distance from it), draw 1 px separations and a rim."""
    owner = {}
    for x, y in cells:
        p = (x + 0.5, y + 0.5)
        best = min(range(len(axes)), key=lambda i: _seg_dist(p, *axes[i])[0])
        owner[(x, y)] = best
    for (x, y), i in owner.items():
        dist, t = _seg_dist((x + 0.5, y + 0.5), *axes[i])
        inner = (x + 1, y) in owner and (x, y + 1) in owner
        split = inner and (owner[(x + 1, y)] != i or owner[(x, y + 1)] != i)
        px[ox + x, oy + y] = rgba(line if split else colour_of(i, t, dist))
    for y in range(32):
        for x in range(32):
            if (x, y) not in owner and any((x + dx, y + dy) in owner for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[ox + x, oy + y] = rgba(rim)


WING_GLINT = 0.47                # when the glint leaves the wing roots (just after the seam flares)


def paint_wing():
    """The static wing painting (both layers), before the glint."""
    img = canvas(64)
    px = img.load()
    F = FEATH
    flight_axes = [(edge_point(s), (x, y)) for s, (x, y, _) in zip(QUILLS, TIPS)]
    full = _mask(TIPS, [a for a, _ in flight_axes], notch=2.2)

    def flight_colour(i, t, off):
        if i < CYAN_TIPS and t > 0.8:
            return MAGIC[3] if off < TIPS[i][2] - 0.9 else MAGIC[2]
        if t < COVERT_DEPTH:
            return F[3]                          # tucked under the coverts
        return F[5] if off < 1.2 or t > 0.72 else F[4]

    _paint_zone(px, 0, 0, full, flight_axes, flight_colour, F[2], F[1])
    # the coverts: a second, shorter scalloped row over the feather bases
    ctips = []
    for (a, b), (_, _, r) in zip(flight_axes, TIPS):
        ctips.append((a[0] + (b[0] - a[0]) * COVERT_DEPTH, a[1] + (b[1] - a[1]) * COVERT_DEPTH, r * 0.95))
    covert_axes = [(a, (x, y)) for (a, _), (x, y, _) in zip(flight_axes, ctips)]
    covert = _mask(ctips, [a for a, _ in covert_axes], notch=1.6)

    def covert_colour(i, t, off):
        if t < 0.28:
            return F[6]                          # the marginal band along the leading edge
        return F[5] if off < 1.3 else F[4]

    _paint_zone(px, 32, 0, covert, covert_axes, covert_colour, F[3], F[1])
    return img


def wing_frame(t: float):
    """The wing with a pale-cyan glint sweeping out from the root to the tip once per
    loop, as if the seam's energy flowed into it."""
    if "wing" not in _STATIC:
        _STATIC["wing"] = paint_wing()
    base = _STATIC["wing"]
    out = base.copy()
    for area in ((0, 0, 32, 32), (32, 0, 64, 32)):
        region = shine(base.crop(area), (t - WING_GLINT) % 1.0, colour=MAGIC[2], width=6, strength=0.62,
                       angle=-34, pause=0.7)
        out.paste(region, area[:2])
    return out


def textures() -> None:
    save_animation(animate(shell_frame, FRAMES), "shell", frametime=2)
    save_animation(animate(wing_frame, FRAMES), "wing", frametime=2)


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def egg_body() -> list[dict]:
    """The shared egg lathe, each face showing its own slice of the shell wrap."""
    parts = lathe(EGG_C, EGG_PROFILE, "shell", sides=16, shade=False)
    for e in parts:
        h0, h1 = e["from"][1] - EGG_C[1], e["to"][1] - EGG_C[1]
        for side, face in e["faces"].items():
            if side == "up":
                face["uv"] = texel_uv(min(31, int(row_of(h1) + 0.05)), ROW_CAP_UP)
            elif side == "down":
                face["uv"] = texel_uv(max(0, math.ceil(row_of(h0) - 0.05) - 1), ROW_CAP_DOWN)
            else:
                c = column_block(face_index(e, side))
                face["uv"] = [float(c), round(row_of(h1) / 4, 4), float(c + 1), round(row_of(h0) / 4, 4)]
    return parts


def ring(h0, h1, apothem, row0, rows, top, bottom, glow=0) -> list[dict]:
    """A 16-sided band round the egg; each side face shows its column block of rows."""
    parts = prism((EGG_C[0], h2y((h0 + h1) / 2), EGG_C[2]), apothem, h1 - h0, "shell", sides=16, glow=glow,
                  shade=False)
    for e in parts:
        for side, face in e["faces"].items():
            if side == "up":
                face["uv"] = sw(top)[1]
            elif side == "down":
                face["uv"] = sw(bottom)[1]
            else:
                face["uv"] = wrap_uv(e, side, row0, rows)
    return parts


def belt() -> list[dict]:
    parts = ring(*BELT_LID, ROW_LID, 4, "s5", "s1")
    parts += ring(*BELT_BASE, ROW_BASE, 3, "s2", "s1")
    parts += ring(*BELT_SEAM, ROW_SEAM, 3, "gem", "gem", glow=15)
    # the base rim's teeth, standing across the seam like a shulker's
    h0, h1, _ = BELT_SEAM
    for i in range(TEETH):
        tooth = box((EGG_C[0] + 4.95, h2y(h0), EGG_C[2] - 0.6), (EGG_C[0] + BELT_BASE[2] - 0.02, h2y(h1) - 0.03,
                                                                  EGG_C[2] + 0.6), "shell",
                    skip=("west", "down"), shade=False)
        tooth["_lit"] = "shulker"
        # on every other face, leaving a glowing window dead centre at the front
        parts.append(turn(tooth, -(22.5 + i * 360 / TEETH), "y", (EGG_C[0], 0, EGG_C[2])))
    return parts


WING_SIZE = 5.0                  # model units one 32 px wing region covers
WING_LIFT = 14.0                 # the wing turns up about its root, tip toward the sky
WING_LAYERS = (((0, 0), 7.8), ((32, 0), 8.15))   # (texel region, z): flight feathers, coverts


def wing_plane(region, z, pivot) -> dict:
    """One painted wing layer: a flat double-sided cut-out whose root texel sits on pivot."""
    ox, oy = region
    s = WING_SIZE / 32
    rx, ry = EDGE[0]
    x0 = pivot[0] - rx * s
    y1 = pivot[1] + ry * s
    u0, v0 = ox / 4, oy / 4
    u1, v1 = u0 + 8, v0 + 8
    return box((x0, y1 - WING_SIZE, z), (x0 + WING_SIZE, y1, z), "wing",
               faces={"south": ("wing", [u0, v0, u1, v1]), "north": ("wing", [u1, v0, u0, v1])},
               skip=("east", "west", "up", "down"), shade=False)


def light_faces(elements) -> None:
    """Give every face of the tagged elements a flat swatch picked by the painted light on
    its final normal, so small unshaded parts share the egg's lighting."""
    ramps = {"shulker": (SHULK_SW, 1.2, 4.8), "feather": (FEATH_SW, 2.2, 4.4)}
    for e in elements:
        kind = e.pop("_lit", None)
        if not kind:
            continue
        names, lo, span = ramps[kind]
        for side, face in e["faces"].items():
            nx, ny, nz = normal(e, side)
            v = lo + span * lambert(math.atan2(-nx, nz), math.asin(max(-1.0, min(1.0, ny))))
            face["texture"] = "#shell"
            face["uv"] = sw(names[max(0, min(len(names) - 1, int(round(v))))])[1]


def tagged(elements, kind):
    for e in elements:
        e["_lit"] = kind
    return elements


def wing() -> list[dict]:
    """The right wing (+X side): painted layers with a rounded leading edge, on a purple
    shulker boss with a glowing gem; lifted, leant back and swept."""
    cx = EGG_C[0] + BELT_LID[2]
    cy = h2y(WING_H)
    pivot = (cx + 0.55, cy + 0.25)
    layers = [wing_plane(region, z, pivot) for region, z in WING_LAYERS]
    # the leading edge as a rounded arm, so the wing has a real edge from the side
    s = WING_SIZE / 32
    x0, y1 = pivot[0] - EDGE[0][0] * s, pivot[1] + EDGE[0][1] * s
    pts = [(x0 + tx * s, y1 - ty * s, 7.98) for tx, ty in (edge_point(u) for u in (0.0, 0.18, 0.4, 0.66, 0.93))]
    arm = []
    for (ax, ay, az), (bx, by, bz) in zip(pts, pts[1:]):
        dx, dy = bx - ax, by - ay
        n = math.hypot(dx, dy)
        ext = 0.12 / n
        arm.append(bar((ax - dx * ext, ay - dy * ext, az), (bx + dx * ext, by + dy * ext, bz), 0.5 - 0.06 * len(arm),
                       0.5, "shell", shade=False))
    turn(layers + arm, WING_LIFT, "z", (pivot[0], pivot[1], 8))
    # the boss on the belt that the wing grows from: an octagonal shulker knob and rim
    boss = prism((cx + 0.15, cy, 8), 1.2, 1.5, "shell", axis="x", sides=8, shade=False)
    boss += prism((cx + 1.02, cy, 8), 0.82, 0.3, "shell", axis="x", sides=8, shade=False)
    gem = box((cx - 0.2, cy - 0.45, 8 + 1.05), (cx + 0.5, cy + 0.45, 8 + 1.45), "shell",
              faces={side: sw("gem") for side in ("north", "south", "east", "west", "up", "down")}, glow=15,
              shade=False)
    parts = layers + tagged(arm, "feather") + tagged(boss, "shulker") + [gem]
    turn(parts, LEAN, "x", (cx, cy, 8))
    turn(parts, SWEEP, "y", (cx, cy, 8))
    return parts


def stars() -> list[dict]:
    """Twinkle planes above the egg; each shows its own cell of the star row."""
    out = []
    for x, y, z, size, cell in STARS:
        u0, v0 = cell * 2, ROW_STARS / 4
        uv = [u0, v0, u0 + 2, v0 + 2]
        back = [u0 + 2, v0, u0, v0 + 2]
        half = size / 2
        out.append(box((x - half, y - half, z), (x + half, y + half, z), "shell",
                       faces={"south": ("shell", uv), "north": ("shell", back)},
                       skip=("east", "west", "up", "down"), glow=15, shade=False))
    return out


def build() -> list[dict]:
    right = wing()
    parts = egg_body() + belt() + right + mirror(right, "x", 8) + stars()
    light_faces(parts)
    return parts


GUI_ROTATION = (8, -25, 0)
SIZE = 1.5
GRIP = (8.0, 2.2 + 6.4, 8.0 - 4.4)   # on the back of the shell, just above the belt


def models() -> dict:
    parts = build()
    d = display(KIND, parts, size=SIZE, gui_rotation=GUI_ROTATION, gui_span=15.4)
    d["ground"] = fit(parts, (0, 0, 0), 9.5, lift=2.0)        # thrown: the egg about vanilla's size
    # third person: facing the way the player faces, wings swept back, the hand closing on
    # the back of the upper shell so the egg sits in front of the fist instead of round it
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, 1)}, GRIP, "fist", 0.45 * SIZE)
    centre = (8.0, h2y(5.2), 8.0)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.1, 0, 1)}, centre, (0.52, -0.36, -0.95), 0.58,
                                       pose=None)
    return {"main": model(parts, d)}
