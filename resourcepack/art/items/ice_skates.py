"""Ice Skates - Winter Limited Edition boots.

Classic figure skates: frosted white leather boots with crimson laces criss-crossing
through silver eyelets and speed hooks, big fluffy white pompoms on the bows, a quilted
collar trimmed with glowing aurora piping, navy stacked heels, and mirror-polished blades
that sweep forward into a curled, serrated toe pick capped with an ice crystal. Frost
runes glow along the blades and an ice snowflake medallion glows on each ankle.

The item model is the pair hanging from their knotted laces. Worn, the blade cannot
stick out below the boot, so the runner is painted as a bright silver band along the
sole with the stanchions and the curled toe pick drawn above it.

Frame: each skate is built toe toward -X, sole top at y = 0, centred on z = 0, then the
pair is tilted and hung side by side from a lace loop.
"""
from __future__ import annotations

import math
import random

from art.kit import (FIST, HUMANOID, bar, bounds, box, canvas, display, fill, hand_frame, mix, model, move, place,
                     ramp, region, rgba, save, save_layer, turn)

ID = "ice_skates"
NAME = "Ice Skates"
KIND = "boots"

# --------------------------------------------------------------------------------------
# Palettes (darkest first; shadows lean blue-violet, lights lean toward clean white)
# --------------------------------------------------------------------------------------

LEATHER = ["#4a5890", "#7d90bf", "#aebfdd", "#d2def0", "#edf3fa", "#ffffff"]   # frosted white
STEEL = ["#18203f", "#3d4a72", "#71829f", "#a9b8cf", "#d9e3ee", "#ffffff"]     # polished silver
LACE = ramp("#c81f3e", 6, 0.75, 0.1)                                             # crimson laces
NAVY = ramp("#2a3870", 6, 0.75, 0.08)                                            # sole, heel, lining
ICE = ramp("#56d8e8", 6, 0.75)                                                   # frost glow
VIOLET = ramp("#8f6df0", 6, 0.8)                                                 # aurora accent
POM = ["#8499cc", "#a9bfe6", "#cadcf3", "#e6f0fc", "#ffffff"]                    # frosted wool
FROST = "#cdeefa"
FROST_HI = "#f2fbff"
EMBROIDERY = ("#abd7f1", "#cfeaf9")                               # pale ice-blue thread

# Skate layout (local frame: toe -X, sole top y = 0), shared by geometry and textures.
LACE_LOWER = ((3.4, 3.5), (6.9, 8.8))     # front line of the instep lacing panel
LACE_UPPER = ((6.7, 8.4), (7.4, 13.4))    # front line of the ankle lacing panel
BOW = (7.0, 13.9, 0.0)
RUNNER = (-5.8, -3.9)                      # runner bottom / top
BT = 0.4                                   # half the blade's thickness
RUNNER_NOSE = -1.2                         # the runner runs on past the toe to here...
CURL_C = (-1.2, -2.55)                     # ...and rolls up into a scroll around this centre
CURL_R = 2.3                               # radius of the hook's centre line
CURL_SWEEP = 232                           # degrees it sweeps: down, forward, up and over
PICKS = (246, 221, 196)                    # angles of the three serrated picks
TOE_PLATE = (1.2, 8.4, -1.2, -0.6)         # x0, x1, y0, y1
HEEL_PLATE = (11.2, 15.6, -2.3, -1.7)
FRONT_ST = (3.0, 7.0, -4.0, -1.2)          # front stanchion
REAR_ST = (12.0, 15.0, -4.0, -2.3)         # rear stanchion


# --------------------------------------------------------------------------------------
# Painting helpers
# --------------------------------------------------------------------------------------

def _put(img, x, y, c):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((int(x), int(y)), rgba(c))


def _line(img, p0, p1, c):
    (x0, y0), (x1, y1) = p0, p1
    n = int(max(abs(x1 - x0), abs(y1 - y0))) + 1
    for i in range(n):
        t = i / max(1, n - 1)
        _put(img, round(x0 + (x1 - x0) * t), round(y0 + (y1 - y0) * t), c)


def _hline(img, x0, x1, y, c):
    fill(img, (min(x0, x1), y, max(x0, x1), y), c)


def _vline(img, x, y0, y1, c):
    fill(img, (x, min(y0, y1), x, max(y0, y1)), c)


def _flake(img, cx, cy, dark, light, centre="#ffffff"):
    """A 5x5 embroidered snowflake."""
    for d in (-2, -1, 1, 2):
        _put(img, cx + d, cy, dark if abs(d) == 2 else light)
        _put(img, cx, cy + d, dark if abs(d) == 2 else light)
    for dx, dy in ((-2, -2), (2, -2), (-2, 2), (2, 2)):
        _put(img, cx + dx, cy + dy, dark)
    for dx, dy in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
        _put(img, cx + dx, cy + dy, light)
    _put(img, cx, cy, centre)


def _frost_band(img, x0, x1, bottom, seed=0, lo=2, hi=5):
    """Frost creeping up from row `bottom`: a smooth uneven edge, glints on the crest."""
    rng = random.Random(seed)
    h = rng.randint(lo, hi)
    for x in range(x0, x1 + 1):
        h = max(lo, min(hi, h + rng.choice((-1, 0, 0, 1))))
        for y in range(bottom - h + 1, bottom + 1):
            _put(img, x, y, FROST if y > bottom - h + 1 else FROST_HI)
        if rng.random() < 0.18:
            _put(img, x, bottom - h, FROST_HI)


# --------------------------------------------------------------------------------------
# The curled toe pick as extruded pixel art
# --------------------------------------------------------------------------------------
# The scroll is rasterised onto a 0.5-unit grid (one texel per cell at 2 texels/unit):
# cell (col, row) covers x = -6 + col/2 .. +1/2 and y = -row/2 - 1/2 .. -row/2 in the
# skate's frame. The same cells build the boxes and paint the "curl" texture, so the
# stepped outline and its bevel shading line up exactly.

_CURL_CACHE: dict = {}


def _half_width(t):
    """The blade leaves the runner at full height and tapers fast into a slender hook."""
    full = (RUNNER[1] - RUNNER[0]) / 2
    return full - (full - 0.62) * min(1.0, t / 0.3) - 0.1 * t


def _curl_path(n=240):
    """Centre line of the hook as (x, y, half-width), from the runner's nose outward."""
    cx, cy = CURL_C
    out = []
    for i in range(n + 1):
        t = i / n
        a = math.radians(270 - CURL_SWEEP * t)
        out.append((cx + CURL_R * math.cos(a), cy + CURL_R * math.sin(a), _half_width(t)))
    return out


def pick_cells() -> list[tuple[int, int]]:
    """One serration cell just proud of the hook's outer edge at each pick angle."""
    cx, cy = CURL_C
    out = []
    for deg in PICKS:
        t = (270 - deg) / CURL_SWEEP
        r = CURL_R + _half_width(t) + 0.3
        a = math.radians(deg)
        out.append((int((cx + r * math.cos(a) + 6) * 2), int(-(cy + r * math.sin(a)) * 2)))
    return out


def curl_cells() -> frozenset:
    """Cells (col, row) of the scroll: col = 2 (x + 6), row = -2 y (skate frame)."""
    if "cells" in _CURL_CACHE:
        return _CURL_CACHE["cells"]
    path = _curl_path()
    cells = set()
    for col in range(0, 18):
        for row in range(0, 14):
            x, y = -6 + (col + 0.5) / 2, -(row + 0.5) / 2
            if any((x - px) ** 2 + (y - py) ** 2 <= hw * hw for px, py, hw in path):
                cells.add((col, row))
    for col, row in pick_cells():
        cells.add((col, row))
    _CURL_CACHE["cells"] = frozenset(cells)
    return _CURL_CACHE["cells"]


def curl_rects() -> list[tuple[int, int, int, int]]:
    """Greedy merge of the cells into rectangles (col0, row0, col1, row1), inclusive."""
    left = set(curl_cells())
    rects = []
    for row in range(14):
        for col in range(18):
            if (col, row) not in left:
                continue
            c1 = col
            while (c1 + 1, row) in left:
                c1 += 1
            r1 = row
            while all((c, r1 + 1) in left for c in range(col, c1 + 1)):
                r1 += 1
            for r in range(row, r1 + 1):
                for c in range(col, c1 + 1):
                    left.discard((c, r))
            rects.append((col, row, c1, r1))
    return rects


# --------------------------------------------------------------------------------------
# Projected textures
# --------------------------------------------------------------------------------------
# A projected texture maps a skate's local coordinates straight onto the image, so
# neighbouring boxes continue one painting. FRAMES[name] = (ox, oy, oz, k):
#   north/south faces  u = (x - ox) * k, v = (oy - y) * k
#   east/west faces    u = (z - oz) * k, v = (oy - y) * k
#   up/down faces      u = (x - ox) * k, v = (z - oz) * k
# k = 1 on a 32 px texture (or 0.5 on 64 px) is 2 texels per unit, the density used
# everywhere on this model.

FRAMES = {
    "boot_side": (0, 16, -8, 1.0),
    "boot_top": (0, 16, -8, 1.0),
    "boot_front": (0, 16, -8, 1.0),
    "boot_back": (0, 16, -8, 1.0),
    "collar": (0, 16, -8, 1.0),
    "sole": (0, 1, -8, 1.0),
    "heel": (0, 0, -8, 1.0),
    "blade": (-4, 4, -16, 0.5),
    "blade_edge": (-4, 4, -16, 0.5),
    "runes": (-4, 4, -16, 0.5),
    "piping": (0, 16, -8, 1.0),
    "curl": (-6, 0, -12, 1.0),
}


def R(y, oy=16):
    """Texel row of model y on a k=1 side projection."""
    return int(math.floor((oy - y) * 2))


def tex_boot_side():
    """The boot's flanks (32 px, texel (tx, ty) <-> x = tx/2, y = 16 - ty/2)."""
    L = LEATHER
    img = canvas(32, fill=L[5])
    # Form shading: a shadow under the collar overhang, the heel rounding away at the
    # back, a soft band low on the boot and a crisp occlusion line at the welt.
    _hline(img, 0, 31, 8, L[4])
    fill(img, (29, 9, 31, 24), L[4])
    fill(img, (0, 25, 31, 27), L[4])
    fill(img, (0, 28, 31, 29), mix(L[4], L[3], 0.5))
    fill(img, (0, 30, 31, 31), L[3])
    # Heel counter: its own leather panel with an embossed edge and double stitching.
    edge = {}
    for tx in range(19, 32):
        x = (tx + 0.5) / 2
        t = max(0.0, min(1.0, (x - 9.6) / 6.2))
        edge[tx] = R(0.5 + 7.2 * t ** 0.6)
    for tx, ey in edge.items():
        for ty in range(ey + 1, 30):
            _put(img, tx, ty, L[4] if ty < 28 else mix(L[4], L[3], 0.6))
        _put(img, tx, ey - 1, L[5])
        _put(img, tx, ey, L[2])
        _put(img, tx, ey + 1, L[3])
        if tx % 2 == 0:
            _put(img, tx, ey + 3, L[2])
    # Toe cap seam.
    for ty in range(23, 30):
        tx = 6 + (ty - 23) // 3
        _put(img, tx, ty, L[2])
        _put(img, tx - 1, ty, L[5])
        if ty % 2:
            _put(img, tx + 2, ty, L[3])
    # Quarter seam curving from the instep down toward the heel counter.
    pts = [(15, 10), (15, 14), (16, 18), (17, 21), (19, 24), (21, 27)]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        _line(img, (x0, y0), (x1, y1), L[3])
    for x, y in pts[1:-1]:
        _put(img, x + 2, y, L[3])
    # Ankle flex creases.
    for (x0, y0), (x1, y1) in (((18, 17), (20, 15)), ((19, 20), (21, 18))):
        _line(img, (x0, y0), (x1, y1), L[3])
        _line(img, (x0 + 1, y0), (x1 + 1, y1), L[5])
    # Embroidered ice snowflakes scattered over the quarter (clear of the medallion).
    for cx, cy in ((19, 11), (28, 12), (24, 22)):
        _flake(img, cx, cy, *EMBROIDERY)
    # Frost creeping up from the welt: an uneven pale-ice band with bright crystal glints.
    _frost_band(img, 0, 31, 29, seed=7)
    save(img, "boot_side")


def tex_boot_top():
    L = LEATHER
    img = canvas(32, fill=L[5])
    # Across the width (v = z + 8 -> rows 10-22): rounded crown, softer toward the sides.
    fill(img, (0, 10, 31, 11), L[4])
    fill(img, (0, 21, 31, 22), L[4])
    fill(img, (0, 10, 31, 10), L[3])
    fill(img, (0, 22, 31, 22), L[3])
    # Toe cap seam across the toe (x ~ 3.2), and the edge of the vamp.
    for ty in range(10, 23):
        _put(img, 6, ty, L[2] if ty % 2 else L[3])
        _put(img, 5, ty, L[5])
    save(img, "boot_top")


def tex_boot_front():
    L = LEATHER
    img = canvas(32, fill=L[4])
    fill(img, (12, 0, 19, 31), L[5])
    fill(img, (8, 0, 9, 31), L[3])
    fill(img, (22, 0, 23, 31), L[3])
    fill(img, (0, 30, 31, 31), L[3])
    save(img, "boot_front")


def tex_boot_back():
    L = LEATHER
    img = canvas(32, fill=L[4])
    fill(img, (12, 0, 19, 31), L[5])
    fill(img, (0, 30, 31, 31), L[3])
    for ty in range(0, 30):
        _put(img, 16, ty, L[2] if ty % 2 else L[3])
    fill(img, (13, 20, 18, 29), L[4])
    _hline(img, 13, 18, 20, L[3])
    save(img, "boot_back")


def tex_collar():
    """Rows 4-7: the quilted roll (row 7 sits under the aurora piping). Top (cols 14-31,
    rows 9-22): the padded rim around the dark opening."""
    L, N = LEATHER, NAVY
    img = canvas(32, fill=L[5])
    _hline(img, 0, 31, 4, L[5])
    _hline(img, 0, 31, 5, L[5])
    _hline(img, 0, 31, 6, L[4])
    _hline(img, 0, 31, 7, L[3])
    for tx in range(1, 32, 3):
        _put(img, tx, 5, L[4])                    # quilting dimples
        _put(img, tx, 6, L[3])
    # Top of the collar: rim and opening.
    fill(img, (13, 8, 31, 23), L[5])
    fill(img, (17, 11, 30, 20), N[1])
    fill(img, (18, 12, 30, 19), N[0])
    _hline(img, 17, 30, 11, N[2])
    _vline(img, 17, 11, 20, N[2])
    _hline(img, 16, 31, 21, L[4])
    save(img, "collar")


def tex_piping():
    """Aurora piping along the collar edge: teal fading to violet (row 7)."""
    img = canvas(32)
    for tx in range(32):
        t = tx / 31
        c = mix(ICE[3], VIOLET[3], t)
        _put(img, tx, 7, c)
        if tx % 3 == 0:
            _put(img, tx, 7, mix(ICE[5], VIOLET[5], t))
    save(img, "piping")


def tex_lacing():
    """Front of both lacing panels (u = z + 8 -> cols 10-21). Lower panel rows 1-13,
    upper panel rows 16-27: eyestays with silver eyelets either side of the tongue."""
    L, S, N = LEATHER, STEEL, NAVY
    img = canvas(32, fill=L[5])
    for top, bot, rows in ((1, 14, (2, 5, 8, 11)), (16, 28, (17, 20, 23, 26))):
        fill(img, (10, top, 12, bot), L[5])
        fill(img, (19, top, 21, bot), L[5])
        _vline(img, 21, top, bot, L[4])
        fill(img, (13, top, 18, bot), L[3])          # the tongue, set back
        _vline(img, 13, top, bot, L[1])               # shadow under the left eyestay
        _vline(img, 18, top, bot, L[2])
        fill(img, (15, top, 16, bot), L[4])           # tongue highlight
        for r in rows:
            for cx in (10, 19):
                fill(img, (cx, r, cx + 2, r + 2), S[3])
                _put(img, cx, r, S[5])
                _put(img, cx + 1, r, S[4])
                _put(img, cx, r + 1, S[4])
                _put(img, cx + 2, r + 2, S[1])
                _put(img, cx + 1, r + 1, N[0])
    save(img, "lacing")


def tex_eyestay():
    L = LEATHER
    img = canvas(32, fill=L[5])
    _hline(img, 0, 31, 0, L[5])
    for ty in range(2, 32, 2):
        _put(img, 1, ty, L[3])
    save(img, "eyestay")


def tex_tongue():
    L, N, S = LEATHER, NAVY, STEEL
    img = canvas(32, fill=L[5])
    # A navy woven label with a silver snowflake on the front of the tongue (cols 0-7).
    fill(img, (1, 1, 6, 5), N[2])
    _hline(img, 1, 6, 1, N[3])
    _hline(img, 1, 6, 5, N[1])
    for x, y, c in ((3, 3, S[5]), (4, 3, S[4]), (3, 2, S[4]), (3, 4, S[3]), (2, 3, S[3]), (5, 3, S[3])):
        _put(img, x, y, c)
    save(img, "tongue")


def tex_tab():
    L, N, S = LEATHER, NAVY, STEEL
    img = canvas(32, fill=L[5])
    fill(img, (0, 0, 5, 7), N[2])
    _vline(img, 0, 0, 7, N[3])
    _hline(img, 0, 5, 0, N[4])
    _put(img, 2, 3, S[5])
    _put(img, 3, 4, S[3])
    save(img, "tab")


def tex_sole():
    N = NAVY
    img = canvas(32, fill=N[2])
    _hline(img, 0, 31, 1, N[4])
    _hline(img, 0, 31, 2, N[3])
    _hline(img, 0, 31, 3, N[2])
    _hline(img, 0, 31, 4, N[1])
    for tx in range(0, 32, 2):
        _put(img, tx, 2, N[5])
    fill(img, (0, 8, 31, 24), N[1])
    save(img, "sole")


def tex_heel():
    N = NAVY
    img = canvas(32, fill=N[2])
    for ty, c in ((1, N[4]), (2, N[2]), (3, N[3]), (4, N[2]), (5, N[1])):
        _hline(img, 0, 31, ty, c)
    rng = random.Random(3)
    for tx in range(32):
        if rng.random() < 0.3:
            _put(img, tx, 2, N[3])
        if rng.random() < 0.3:
            _put(img, tx, 4, N[3])
    save(img, "heel")


def tex_blade():
    """Blade flanks as one side picture (64 px, texel (tx, ty) <-> x = tx/2 - 4, y = 4 - ty/2)."""
    S = STEEL
    img = canvas(64, fill=S[3])

    def px_(x):
        return int(math.floor((x + 4) * 2))

    def py_(y):
        return int(math.floor((4 - y) * 2))

    # Plates bolted under the sole and heel: hot top edge, screws.
    for (x0, x1, y0, y1), screws in ((TOE_PLATE, (3.0, 5.0, 7.0)), (HEEL_PLATE, (12.6, 14.4))):
        fill(img, (px_(x0), py_(y1), px_(x1), py_(y0)), S[4])
        _hline(img, px_(x0), px_(x1), py_(y1), S[5])
        for sx in screws:
            _put(img, px_(sx), py_(y0) - 1, S[1])
    # Stanchions: vertical chrome, bright leading edge, dark trailing edge.
    for x0, x1, y0, y1 in (FRONT_ST, REAR_ST):
        a, b = px_(x0), px_(x1) - 1
        top, bot = py_(y1), py_(y0) - 1
        fill(img, (a, top, b, bot), S[3])
        _vline(img, a, top, bot, S[5])
        _vline(img, a + 1, top, bot, S[4])
        _vline(img, b, top, bot, S[2])
        _hline(img, a, b, top, S[4])
        _hline(img, a, b, bot, S[2])
    # Runner: white-hot top edge, a mirror band carrying the runes, a crisp dark ice edge.
    top, bot = py_(RUNNER[1]), py_(RUNNER[0])
    for row, c in zip(range(top, bot + 1), ("#ffffff", "#ffffff", S[4], S[3], S[1])):
        _hline(img, 0, 63, row, c)
    save(img, "blade")


def tex_curl():
    """The hook's flanks, one texel per cell, lit from the upper left: each cell is shaded
    by the normal of the edge it sits on (outer edge faces away from the hook's centre,
    inner edge toward it). Cols 23-24 and rows 23-24 carry the polish its extruded
    edges sample."""
    S = STEEL
    img = canvas(32)
    cells = curl_cells()
    picks = set(pick_cells())
    cx, cy = CURL_C
    light = (-0.6, 0.8)
    for col, row in cells:
        x, y = -6 + (col + 0.5) / 2, -(row + 0.5) / 2
        dx, dy = x - cx, y - cy
        dist = math.hypot(dx, dy) or 1.0
        ux, uy = dx / dist, dy / dist
        outer = dist >= CURL_R or (col, row) in picks
        nx, ny = (ux, uy) if outer else (-ux, -uy)
        b = nx * light[0] + ny * light[1]
        if y > RUNNER[1] - 0.2 and x > cx:      # still the runner: keep its bands
            b = 0.2
        c = "#ffffff" if b > 0.45 else S[4] if b > 0.0 else S[3] if b > -0.45 else S[2]
        if (col, row) in picks:
            c = S[4] if b > 0 else S[2]
        _put(img, col, row, c)
    # the runner's own banding where it flows into the hook
    for col, row in cells:
        x, y = -6 + (col + 0.5) / 2, -(row + 0.5) / 2
        if x > cx and y < RUNNER[1]:
            band = int((RUNNER[1] - y) / (RUNNER[1] - RUNNER[0]) * 5)
            _put(img, col, row, ("#ffffff", "#ffffff", S[4], S[3], S[1])[min(4, max(0, band))])
    fill(img, (23, 0, 24, 31), S[4])
    fill(img, (0, 23, 31, 24), S[5])
    save(img, "curl")


def tex_blade_edge():
    """Top and bottom edges of the blade parts (z -0.4..0.4 -> rows 31-32)."""
    S = STEEL
    img = canvas(64, fill=S[4])
    _hline(img, 0, 63, 31, S[5])
    _hline(img, 0, 63, 32, S[4])
    save(img, "blade_edge")


def tex_runes():
    """Glowing frost runes (same frame as the blade): a rune on the front stanchion and a
    line of rune marks along the runner's mirror band."""
    img = canvas(64)
    x0, x1, y0, y1 = FRONT_ST
    cx, cy = int(((x0 + x1) / 2 + 4) * 2), int((4 - (y0 + y1) / 2) * 2)
    for d in (-2, -1, 1, 2):
        _put(img, cx + d, cy, ICE[3] if abs(d) == 1 else ICE[4])
    for d in (-1, 1):
        _put(img, cx, cy + d, ICE[3])
        _put(img, cx + d, cy + d, ICE[4])
        _put(img, cx + d, cy - d, ICE[4])
    _put(img, cx, cy, "#ffffff")
    row = int(math.floor((4 - RUNNER[1]) * 2)) + 3
    for i, tx in enumerate(range(12, 42, 5)):
        c0, c1 = (ICE[4], ICE[3]) if i % 2 else (VIOLET[4], VIOLET[3])
        _put(img, tx, row, c0)
        _put(img, tx + 1, row, c1)
    save(img, "runes")


def tex_chrome():
    """Generic polished steel for rotated parts: highlight down one edge of each face."""
    S = STEEL
    img = canvas(32, fill=S[3])
    for tx in range(32):
        _vline(img, tx, 0, 31, [S[5], S[4], S[3], S[2], S[3], S[4]][tx % 6])
    save(img, "chrome")


def tex_lace():
    C = LACE
    img = canvas(32, fill=C[3])
    for ty in range(32):
        for tx in range(32):
            k = (tx + ty) % 3
            if k == 0:
                _put(img, tx, ty, C[4])
            elif k == 2:
                _put(img, tx, ty, C[2])
    save(img, "lace")


def tex_pompom():
    """Fluffy frosted wool in 2x2 tufts: bright crowns, icy shadows, a few frost sparkles."""
    P = POM
    img = canvas(32, fill=P[3])
    rng = random.Random(11)
    for ty in range(0, 32, 2):
        for tx in range(0, 32, 2):
            r = rng.random()
            c = P[4] if r < 0.36 else P[2] if r < 0.62 else P[3] if r < 0.95 else P[1]
            fill(img, (tx, ty, tx + 1, ty + 1), c)
            _put(img, tx + rng.randrange(2), ty + rng.randrange(2), P[4] if c != P[4] else P[3])
    for _ in range(10):
        _put(img, rng.randrange(32), rng.randrange(32), FROST)
    save(img, "pompom")


def tex_gem():
    img = canvas(32, fill=ICE[3])
    fill(img, (0, 0, 31, 0), ICE[5])
    fill(img, (0, 0, 0, 31), ICE[4])
    fill(img, (1, 1, 1, 1), "#ffffff")
    fill(img, (2, 2, 31, 31), ICE[3])
    fill(img, (0, 2, 1, 2), ICE[2])
    save(img, "gem")


def tex_medal():
    """Ankle medallion (cols 0-6, rows 0-6): silver bezel, navy enamel."""
    S, N = STEEL, NAVY
    img = canvas(32, fill=S[3])
    fill(img, (0, 0, 6, 6), S[3])
    _hline(img, 1, 5, 0, S[5])
    _vline(img, 0, 1, 5, S[4])
    _vline(img, 6, 1, 5, S[1])
    _hline(img, 1, 5, 6, S[1])
    for x, y in ((0, 0), (6, 0), (0, 6), (6, 6)):
        _put(img, x, y, (0, 0, 0, 0))
    fill(img, (1, 1, 5, 5), N[1])
    save(img, "medal")


def tex_glowflake():
    img = canvas(32)
    c = 3
    for d in range(-2, 3):
        _put(img, c + d, c, ICE[3])
        _put(img, c, c + d, ICE[3])
    for d in (-1, 1):
        _put(img, c + d, c + d, ICE[4])
        _put(img, c + d, c - d, ICE[4])
    _put(img, c, c, "#ffffff")
    save(img, "glowflake")


# --------------------------------------------------------------------------------------
# Worn layer (humanoid, 256x128): skates over the lower leg with a painted blade
# --------------------------------------------------------------------------------------

def worn_layer():
    """The worn skates on the 4x humanoid layout. Rows: pompoms and bow hang over the top
    (95-101), collar 98-100, aurora cord 101, white upper 102-114, navy welt 115-116,
    blade zone 117-119, bright runner 120-124. Rows below ~124.6 sit under the ground
    when the player stands, so the runner ends there."""
    S4 = 4
    img = canvas(256, 128)
    L, S, N, C = LEATHER, STEEL, NAVY, LACE
    faces = {n: region(HUMANOID, n, S4) for n in ("leg_outer", "leg_front", "leg_inner", "leg_back", "leg_sole")}
    TOP, CORD, UP, WELT, GAP, RUN = 98, 101, 102, 115, 117, 120
    AIR = mix(N[2], S[1], 0.5)
    cord = (ICE[3], ICE[4], VIOLET[4], VIOLET[3])
    runner = ("#ffffff", S[4], S[3], S[3], S[2], S[1], S[1], S[1])

    def band(x0):
        for i, x in enumerate(range(x0, x0 + 16)):
            _put(img, x, TOP, L[5])
            _put(img, x, TOP + 1, L[5] if i % 3 else L[4])
            _put(img, x, TOP + 2, L[3])
            _put(img, x, CORD, cord[x % 4])
            _vline(img, x, UP, WELT - 1, L[5])
            _put(img, x, WELT, N[4])
            _put(img, x, WELT + 1, N[2])
            _vline(img, x, GAP, RUN - 1, AIR)
            for row, c in zip(range(RUN, 128), runner):
                _put(img, x, row, c)
        _frost_band(img, x0, x0 + 15, WELT - 1, seed=x0, lo=1, hi=3)

    def paint_side(x0, front_right, medallion):
        def col(c):  # c counts from the heel (0) to the toe (15)
            return x0 + (c if front_right else 15 - c)
        band(x0)
        # heel counter
        for c in range(0, 7):
            h = 105 + (c * c) // 5
            for y in range(h + 1, WELT - 2):
                _put(img, col(c), y, L[4])
            _put(img, col(c), h, L[2])
            if c % 2 == 0:
                _put(img, col(c), h + 2, L[3])
        # toe cap seam
        for y in range(107, WELT - 1):
            _put(img, col(12 + (y - 107) // 4), y, L[3])
        # stacked heel with the rear stanchion below; front stanchion under the forefoot
        for c in range(0, 6):
            _put(img, col(c), GAP, N[2])
        for c in range(1, 5):
            _vline(img, col(c), GAP + 1, RUN - 1, S[3])
            _put(img, col(c), GAP + 1, S[4])
        for c in range(6, 13):
            _vline(img, col(c), GAP, RUN - 1, S[3])
            _put(img, col(c), GAP, S[5])
        _vline(img, col(6), GAP + 1, RUN - 1, AIR)
        _vline(img, col(7), GAP, RUN - 1, S[5])
        _vline(img, col(12), GAP + 1, RUN - 1, S[2])
        # the curled toe pick: a little silver hook rising off the runner's nose around
        # its ice crystal
        for y, c in ((GAP, S[4]), (GAP + 1, "#ffffff"), (GAP + 2, S[4])):
            _put(img, col(15), y, c)
        _put(img, col(14), GAP, "#ffffff")
        _put(img, col(13), GAP + 1, S[3])
        _put(img, col(14), GAP + 1, ICE[4])
        _put(img, col(14), GAP + 2, AIR)
        # frost runes along the runner
        for c in range(2, 15, 3):
            _put(img, col(c), RUN + 2, ICE[4] if c % 2 else VIOLET[4])
        if medallion:
            cx, cy = col(8), 107
            for dy in range(-3, 4):
                w = 3 if abs(dy) < 3 else 2
                _hline(img, cx - w, cx + w, cy + dy, S[3])
            _hline(img, cx - 2, cx + 2, cy - 3, S[5])
            _hline(img, cx - 2, cx + 2, cy + 3, S[1])
            _vline(img, cx - 3, cy - 2, cy + 2, S[4])
            _vline(img, cx + 3, cy - 2, cy + 2, S[2])
            fill(img, (cx - 2, cy - 2, cx + 2, cy + 2), N[1])
            for d in (-2, -1, 0, 1, 2):
                _put(img, cx + d, cy, ICE[3])
                _put(img, cx, cy + d, ICE[3])
            for d in (-1, 1):
                _put(img, cx + d, cy + d, ICE[4])
                _put(img, cx + d, cy - d, ICE[4])
            _put(img, cx, cy, "#ffffff")

    paint_side(faces["leg_outer"][0], front_right=True, medallion=True)
    paint_side(faces["leg_inner"][0], front_right=False, medallion=False)

    # Front: tongue and criss-cross laces, the bow and pompoms over the collar, the toe pick.
    fx = faces["leg_front"][0]
    band(fx)
    fill(img, (fx + 5, UP, fx + 10, WELT - 1), L[3])
    fill(img, (fx + 7, UP, fx + 8, WELT - 1), L[4])
    _vline(img, fx + 5, UP, WELT - 1, L[2])
    _vline(img, fx + 10, UP, WELT - 1, L[2])
    for r in (104, 108, 112):
        for cx in (fx + 2, fx + 12):
            fill(img, (cx, r - 1, cx + 1, r), S[4])
            _put(img, cx + 1, r, S[1])
            _put(img, cx, r - 1, "#ffffff")
        if r < 112:
            _line(img, (fx + 4, r - 1), (fx + 11, r + 3), C[3])
            _line(img, (fx + 4, r), (fx + 11, r + 4), C[2])
            _line(img, (fx + 11, r - 1), (fx + 4, r + 3), C[4])
            _line(img, (fx + 11, r), (fx + 4, r + 4), C[3])
    fill(img, (fx + 6, 96, fx + 9, 98), C[3])
    _hline(img, fx + 6, fx + 9, 96, C[4])
    _put(img, fx + 7, 97, C[1])
    _put(img, fx + 5, 96, C[3])
    _put(img, fx + 10, 96, C[3])
    ball = [".oooo.",
            "oWWHHo",
            "oWHHWo",
            "oWWWso",
            "osWsso",
            ".oooo."]
    shade = {"o": POM[1], "W": POM[3], "H": POM[4], "s": POM[2]}
    for px0 in (fx, fx + 10):
        for dy, line in enumerate(ball):
            for dx, ch in enumerate(line):
                if ch != ".":
                    _put(img, px0 + dx, 96 + dy, shade[ch])
    # the blade seen head-on: the toe pick is a slim silver column over the runner's nose
    fill(img, (fx + 6, GAP, fx + 9, RUN + 4), S[3])
    _vline(img, fx + 6, GAP, RUN + 4, S[5])
    _vline(img, fx + 7, GAP, RUN + 4, S[4])
    _vline(img, fx + 9, GAP, RUN + 4, S[2])
    for x in (fx + 5, fx + 10):
        _put(img, x, GAP + 1, S[3])
        _put(img, x, GAP + 2, S[2])

    # Back: pull tab, heel counter with its seam, stacked heel, rear stanchion, runner tail.
    bx = faces["leg_back"][0]
    band(bx)
    fill(img, (bx + 2, 107, bx + 13, WELT - 1), L[4])
    _hline(img, bx + 2, bx + 13, 107, L[2])
    for y in range(UP, WELT):
        _put(img, bx + 7, y, L[2] if y % 2 else L[3])
    fill(img, (bx + 6, TOP - 3, bx + 9, TOP + 2), L[5])
    fill(img, (bx + 7, TOP - 2, bx + 8, TOP + 1), N[2])
    _hline(img, bx, bx + 15, GAP, N[2])
    fill(img, (bx + 6, GAP + 1, bx + 9, RUN + 4), S[3])
    _vline(img, bx + 6, GAP + 1, RUN + 4, S[5])
    _vline(img, bx + 9, GAP + 1, RUN + 4, S[2])

    # Sole: navy leather with the blade line down the middle (top rows = toe).
    sx0, sy0, sx1, sy1 = faces["leg_sole"]
    fill(img, (sx0, sy0, sx1, sy1), N[2])
    fill(img, (sx0, sy0 + 10, sx1, sy1), N[1])
    fill(img, (sx0 + 6, sy0, sx0 + 9, sy1), S[3])
    _vline(img, sx0 + 7, sy0, sy1, "#ffffff")
    _vline(img, sx0 + 8, sy0, sy1, S[4])
    save_layer(img, "humanoid")


def textures() -> None:
    for paint in (tex_boot_side, tex_boot_top, tex_boot_front, tex_boot_back, tex_collar, tex_piping,
                  tex_lacing, tex_eyestay, tex_tongue, tex_tab, tex_sole, tex_heel, tex_blade,
                  tex_blade_edge, tex_curl, tex_runes, tex_chrome, tex_lace, tex_pompom, tex_gem, tex_medal,
                  tex_glowflake):
        paint()
    worn_layer()


# --------------------------------------------------------------------------------------
# Geometry helpers
# --------------------------------------------------------------------------------------

SIDES = ("north", "south", "east", "west", "up", "down")


def _clamp(v):
    return round(min(16.0, max(0.0, v)), 4)


def pbox(frm, to, tex, skip=(), glow=0, shade=True):
    """A box whose faces sample their textures by projection (see FRAMES).
    tex: a texture name, or {side: name, "default": name}."""
    frm = [float(v) for v in frm]
    to = [float(v) for v in to]
    for i in range(3):
        if frm[i] > to[i]:
            frm[i], to[i] = to[i], frm[i]
    (x0, y0, z0), (x1, y1, z1) = frm, to
    faces = {}
    for side in SIDES:
        if side in skip:
            continue
        name = tex.get(side, tex.get("default")) if isinstance(tex, dict) else tex
        ox, oy, oz, k = FRAMES[name]
        U = lambda x: (x - ox) * k
        V = lambda y: (oy - y) * k
        W = lambda z: (z - oz) * k
        uv = {
            "south": [U(x0), V(y1), U(x1), V(y0)],
            "north": [U(x1), V(y1), U(x0), V(y0)],
            "west": [W(z0), V(y1), W(z1), V(y0)],
            "east": [W(z1), V(y1), W(z0), V(y0)],
            "up": [U(x0), W(z0), U(x1), W(z1)],
            "down": [U(x0), W(z1), U(x1), W(z0)],
        }[side]
        faces[side] = (name, [_clamp(v) for v in uv])
    first = next(iter(faces.values()))[0]
    return box(frm, to, first, faces=faces, skip=skip, glow=glow, shade=shade)


def plane(frm, to, tex, side, glow=0):
    """A zero-thickness overlay facing `side`, textured by projection."""
    return pbox(frm, to, tex, skip=tuple(s for s in SIDES if s != side), glow=glow)


def octo(center, radius, length, tex, offset=(0, 0)):
    """An 8-sided prism along Y (the 45-degree slabs sit a hair lower so caps never z-fight)."""
    cx, cy, cz = center
    half = radius * math.tan(math.pi / 8)
    out = []
    for a, trim in ((0, 0.0), (45, 0.03)):
        y0, y1 = cy - length / 2 + trim, cy + length / 2 - trim
        s1 = box((cx - radius, y0, cz - half), (cx + radius, y1, cz + half), tex, skip=("north", "south"),
                 offset=offset)
        s2 = box((cx - half, y0, cz - radius), (cx + half, y1, cz + radius), tex, skip=("east", "west"),
                 offset=(offset[0] + 1, offset[1] + 1))
        if a:
            turn([s1, s2], a, "y", center)
        out += [s1, s2]
    return out


def ball(c, r, tex, seed=0):
    """A fluffy pompom: a rounded core (two octagonal prisms) with tufts poking out."""
    parts = octo(c, r * 0.84, r * 1.25, tex) + octo(c, r * 0.58, r * 1.8, tex, offset=(4, 3))
    rng = random.Random(seed)
    dirs = [(1, 0.25, 0.3), (-1, 0.1, -0.2), (0.2, 1, 0.1), (-0.3, -0.9, 0.2), (0.3, -0.1, 1), (-0.2, 0.3, -1),
            (0.7, 0.7, -0.6), (-0.7, 0.6, 0.6)]
    for i, d in enumerate(dirs):
        n = math.sqrt(sum(v * v for v in d))
        q = [c[k] + d[k] / n * r * 0.78 for k in range(3)]
        t = r * 0.3
        tuft = box((q[0] - t, q[1] - t, q[2] - t), (q[0] + t, q[1] + t, q[2] + t), tex, offset=(2 * i % 12, 2 * i % 10))
        parts.append(turn(tuft, x=rng.uniform(-40, 40), y=rng.uniform(0, 90), z=rng.uniform(-40, 40), origin=q))
    return parts


def _rotz(p, deg, o):
    a = math.radians(deg)
    x, y = p[0] - o[0], p[1] - o[1]
    return (o[0] + x * math.cos(a) - y * math.sin(a), o[1] + x * math.sin(a) + y * math.cos(a), p[2])


# --------------------------------------------------------------------------------------
# One skate (local frame: toe -X, sole top y = 0, centred on z = 0)
# --------------------------------------------------------------------------------------

def lacing_panel(f0, f1, thick, voff, hooks: bool):
    """A lacing panel built upright (front face at x = 0, facing -X), then tilted onto
    the line f0 -> f1, with 3D criss-cross laces and (optionally) speed hooks."""
    (ax, ay), (bx, by) = f0, f1
    length = math.hypot(bx - ax, by - ay)
    tilt = -math.degrees(math.atan2(bx - ax, by - ay))
    w = 2.7
    parts = [box((0, 0, -w), (thick, length, w), "eyestay",
                 faces={"west": ("lacing", [_clamp(8 - w), _clamp(voff), _clamp(8 + w), _clamp(voff + length)])},
                 skip=("down",))]
    rows = [length * (0.12 + 0.76 * i / 3) for i in range(4)]
    e = 1.95
    for i in range(3):
        y0, y1 = rows[i], rows[i + 1]
        parts.append(bar((-0.18, y0, -e), (-0.18, y1, e), 0.36, 0.72, "lace"))
        parts.append(bar((-0.38, y0, e), (-0.38, y1, -e), 0.36, 0.72, "lace", offset=(2, 0)))
    if hooks:
        for y in rows[1:]:
            for s in (-1, 1):
                parts.append(box((-0.6, y - 0.32, s * 2.4 - 0.32), (0.05, y + 0.32, s * 2.4 + 0.32), "chrome"))
    turn(parts, tilt, "z", (0, 0, 0))
    move(parts, ax, ay, 0)
    return parts


def boot(outer: int) -> list[dict]:
    sides = {"north": "boot_side", "south": "boot_side", "east": "boot_back", "west": "boot_front",
             "up": "boot_top", "down": "boot_top"}
    p = [
        pbox((0.0, 0.5, -1.9), (1.2, 2.5, 1.9), sides, skip=("down",)),          # toe tip
        pbox((0.7, 0.5, -2.6), (3.2, 3.4, 2.6), sides, skip=("down",)),          # toe box
        pbox((2.6, 0.5, -3.0), (9.0, 4.2, 3.0), sides, skip=("down",)),          # vamp
        pbox((7.8, 0.5, -2.9), (15.4, 7.0, 2.9), sides, skip=("down",)),         # lower shaft
        pbox((7.8, 6.8, -2.8), (14.9, 10.6, 2.8), sides, skip=("down", "up")),   # Achilles
        pbox((7.6, 10.4, -2.95), (15.2, 12.4, 2.95), sides, skip=("down", "up")),  # upper shaft
        pbox((15.0, 0.5, -2.4), (16.0, 5.2, 2.4), sides, skip=("down",)),        # heel cup
        pbox((7.3, 12.2, -3.25), (15.7, 14.0, 3.25), "collar", skip=("down",)),  # quilted collar
    ]
    # glowing aurora piping along both flanks of the collar
    for z, side in ((3.28, "south"), (-3.28, "north")):
        p.append(plane((7.3, 12.2, z), (15.7, 12.7, z), "piping", side, glow=10))
    # tongue, leaning forward out of the collar, and a pull tab at the back
    p.append(bar((7.55, 12.6, 0), (6.9, 15.8, 0), 1.2, 3.6, "eyestay",
                 faces={"west": ("tongue", [0, 0, 3.6, 3.2])}))
    tab = bar((15.35, 12.4, 0), (15.9, 15.4, 0), 0.7, 2.4, "eyestay",
              faces={"east": ("tab", [0, 0, 2.4, 3.1])})
    p.append(tab)
    p += lacing_panel(*LACE_LOWER, 3.2, 0.5, hooks=False)
    p += lacing_panel(*LACE_UPPER, 2.4, 8.0, hooks=True)
    # ankle medallion on the outer side
    s = outer
    zf = s * 2.9
    p.append(box((10.2, 7.4, min(zf, zf + s * 0.4)), (13.4, 10.6, max(zf, zf + s * 0.4)), "medal",
                 uv={"north": [0, 0, 3.5, 3.5], "south": [0, 0, 3.5, 3.5], "east": [0, 1, 0.4, 3],
                     "west": [0, 1, 0.4, 3], "up": [1, 0, 3, 0.4], "down": [1, 0, 3, 0.4]}))
    face = "south" if s > 0 else "north"
    zg = zf + s * 0.43
    p.append(box((10.2, 7.4, zg), (13.4, 10.6, zg), "glowflake", uv={face: [0, 0, 3.5, 3.5]},
                 skip=tuple(x for x in SIDES if x != face), glow=12))
    return p


def sole_and_blade() -> list[dict]:
    p = [
        pbox((0.5, -0.6, -3.15), (16.1, 0.5, 3.15), "sole"),
        pbox((-0.1, -0.6, -2.1), (1.0, 0.5, 2.1), "sole"),
        pbox((10.9, HEEL_PLATE[3], -2.6), (15.9, -0.6, 2.6), "heel"),
    ]
    edge = {"default": "blade", "up": "blade_edge", "down": "blade_edge"}
    for x0, x1, y0, y1 in (TOE_PLATE, HEEL_PLATE):                              # mounting plates
        p.append(pbox((x0, y0, -1.6), (x1, y1, 1.6), edge))
    for (x0, x1, y0, y1), flare in ((FRONT_ST, 1.0), (REAR_ST, 0.7)):         # stanchions + flares
        p.append(pbox((x0, y0, -BT), (x1, y1, BT), edge))
        p.append(bar((x0 + 0.1, y1 - 2.2 * flare, 0), (x0 - flare, y1, 0), 0.9, 2 * BT - 0.1, "chrome"))
        p.append(bar((x1 - 0.1, y1 - 2.2 * flare, 0), (x1 + flare, y1, 0), 0.9, 2 * BT - 0.1, "chrome",
                     offset=(3, 0)))
    rb, rt = RUNNER
    mid = pbox((5.0, rb, -BT), (12.6, rt, BT), edge)
    front = pbox((RUNNER_NOSE, rb, -BT), (5.4, rt, BT), edge)
    back = pbox((12.2, rb, -BT), (17.2, rt, BT), edge)
    turn(front, -4, "z", (5.4, rb, 0))        # a gentle rocker: both ends lift off the ice
    turn(back, 4, "z", (12.2, rb, 0))
    p += [mid, front, back]
    # The curled toe pick: extruded pixel art (see curl_cells), tilted with the rocker.
    curl = []
    for c0, r0_, c1, r1 in curl_rects():
        x0, x1 = -6 + c0 / 2, -6 + (c1 + 1) / 2
        y1, y0 = -r0_ / 2, -(r1 + 1) / 2
        curl.append(pbox((x0, y0, -BT), (x1, y1, BT), "curl"))
    turn(curl, -4, "z", (5.4, rb, 0))
    p += curl
    # an ice crystal set at the heart of the scroll
    ex, ey, _ = _curl_path(1)[-1]
    ex, ey, _ = _rotz((ex, ey, 0), -4, (5.4, rb))
    gem = box((ex - 0.7, ey - 0.7, -0.7), (ex + 0.7, ey + 0.7, 0.7), "gem", glow=12)
    p.append(turn(gem, x=35, y=0, z=45, origin=(ex, ey, 0)))
    # glowing frost runes on both flanks of the blade
    for z, side in ((BT + 0.03, "south"), (-BT - 0.03, "north")):
        p.append(plane((FRONT_ST[0], FRONT_ST[2], z), (FRONT_ST[1], FRONT_ST[3], z), "runes", side, glow=13))
        p.append(plane((5.0, rb, z), (12.6, rt, z), "runes", side, glow=13))
    return p


def bow_and_pompoms() -> list[dict]:
    bx, by, _ = BOW
    p = [box((bx - 0.5, by - 0.5, -0.5), (bx + 0.5, by + 0.5, 0.5), "lace")]
    for zs in (-1, 1):
        p.append(bar((bx, by, 0), (bx - 0.5, by + 0.7, zs * 1.6), 0.55, 0.9, "lace"))
    for i, c in enumerate(((5.2, 10.8, 1.5), (5.0, 9.0, -1.45))):
        p.append(bar((bx - 0.3, by - 0.2, c[2] * 0.3), (c[0] + 0.4, c[1] + 1.4, c[2] * 0.9), 0.4, 0.4, "lace"))
        p += ball(c, 1.9, "pompom", seed=i + 3)
    return p


def skate(outer: int) -> list[dict]:
    return boot(outer) + sole_and_blade() + bow_and_pompoms()


# --------------------------------------------------------------------------------------
# The pair, hung from their knotted laces
# --------------------------------------------------------------------------------------

def _follow(point, ops):
    """Track a point through the same turn/move steps applied to a skate."""
    x, y, z = point
    for op in ops:
        if op[0] == "z":
            x, y, z = _rotz((x, y, z), op[1], op[2])
        elif op[0] == "y":
            a = math.radians(op[1])
            ox, oz = op[2][0], op[2][2]
            dx, dz = x - ox, z - oz
            x, z = ox + dx * math.cos(a) + dz * math.sin(a), oz - dx * math.sin(a) + dz * math.cos(a)
        else:
            x, y, z = x + op[1], y + op[2], z + op[3]
    return (x, y, z)


def _apply(parts, ops):
    for op in ops:
        if op[0] in ("z", "y"):
            turn(parts, op[1], op[0], op[2])
        else:
            move(parts, op[1], op[2], op[3])
    return parts


OPS_A = [("z", -9, BOW)]                                     # near skate (right foot)
OPS_B = [("z", -4, BOW), ("y", 8, BOW), ("m", 4.0, 2.5, -7.0)]   # far skate: up, back, beside


def pair():
    """The two skates side by side, B hung a little higher and behind, their laces knotted
    into a loop above. Everything is centred on (8, 8, 8). Returns (parts, grip), where
    grip is the top of the loop."""
    parts = _apply(skate(+1), OPS_A) + _apply(skate(-1), OPS_B)
    bows = [_follow(BOW, OPS_A), _follow(BOW, OPS_B)]
    kx = (bows[0][0] + bows[1][0]) / 2 + 0.6
    ky = max(bows[0][1], bows[1][1]) + 3.2
    kz = (bows[0][2] + bows[1][2]) / 2
    for bow in bows:
        parts.append(bar(bow, (kx, ky - 0.2, kz), 0.55, 0.55, "lace"))
    parts.append(box((kx - 0.6, ky - 0.6, kz - 0.6), (kx + 0.6, ky + 0.6, kz + 0.6), "lace"))
    r, cyc = 1.5, ky + 1.9
    for i in range(8):
        a0 = math.radians(-90 + 45 * i + 22.5)
        a1 = math.radians(-90 + 45 * (i + 1) + 22.5)
        parts.append(bar((kx + r * math.cos(a0), cyc + r * math.sin(a0), kz),
                         (kx + r * math.cos(a1), cyc + r * math.sin(a1), kz), 0.55, 0.55, "lace"))
    grip = (kx, cyc + r, kz)
    lo, hi = bounds(parts)
    shift = [8 - (lo[i] + hi[i]) / 2 for i in range(3)]
    move(parts, *shift)
    return parts, tuple(grip[i] + shift[i] for i in range(3))


GUI_ROTATION = (-16, 12, 0)


def hang_point() -> tuple[float, float, float]:
    """Where the lace loop sits in third person: in the fist, nudged a hair outward and
    forward so the inner skate clears the leg."""
    import numpy as np
    frame = hand_frame("item")
    fist = frame[:3, :3] @ np.asarray(FIST) + frame[:3, 3]
    return tuple(float(v) for v in fist + np.array([-0.05, 0.0, 0.03]))


def models() -> dict:
    parts, grip = pair()
    d = display("boots", parts, grip=grip, gui_rotation=GUI_ROTATION)
    d["thirdperson_righthand"] = place({"x": (0, 0, -1), "y": (0, 1, 0)}, grip, hang_point(), 0.34)
    d["firstperson_righthand"] = place({"x": (0.8, 0, 0.6), "y": (0, 1, 0)}, grip, (0.5, 0.0, -0.95), 0.3,
                                       pose=None)
    return {"main": model(parts, d)}
