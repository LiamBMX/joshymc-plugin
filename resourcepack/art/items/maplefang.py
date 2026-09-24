"""Maplefang: Woodland Limited Edition trident.

The head is a maple leaf whose three main lobes are the prongs, crimson at the heart
fading to gold at the tips, with engraved side veins, raised main veins, honed rims and
two small basal lobes as barbs. It is extruded from one painted silhouette in two layers
(a thin honed rim that glows faintly at night, then the thicker blade), folded into a
shallow V along the midrib, with a glowing amber heart in a copper bezel where the veins
meet. The leaf sits in a brass collar set with amber sap beads and hung with six carved
wolf fangs. The shaft is two strands of twisted maple wound around a glowing amber sap
core, banded in copper, with a buffalo-plaid grip laced in forest-green leather and a
brass-caged amber sap-drop pommel.
"""
from __future__ import annotations

import math
import random

from art.kit import (bar, bevel, box, canvas, copy, display, display_for_state, fill, model, place, prism,
                     ramp, rgba, rotation_of, save, turn)

ID = "maplefang"
NAME = "Maplefang"
KIND = "trident"

SIZE = 0.92            # in-hand scale
GRIP = (8.0, -4.8, 8.0)

# --------------------------------------------------------------------------------------
# Palettes
# --------------------------------------------------------------------------------------
# The leaf: deep crimson at the heart through scarlet and orange to gold at the tips.
LEAF = ["#2e0819", "#480d22", "#661427", "#861b2a", "#a6232a", "#c23027", "#d74523", "#e5611f",
        "#ef8021", "#f5a02a", "#f9bd3b", "#fcd75e", "#ffee98", "#fffbe0"]
WOOD = ramp("#915b35", 6, contrast=0.8)        # twisted maple, stained warm
AMBER = ramp("#f09a1e", 6, contrast=0.7)       # glowing sap
COPPER = ramp("#c46d3c", 6, contrast=0.8)
BRASS = ramp("#c9a044", 6, contrast=0.8)
IVORY = ["#6a4a30", "#9a7650", "#c4a574", "#dcc394", "#eddcb4", "#faf1d8"]
LACE = ramp("#2f5a38", 6, contrast=0.8)          # forest-green leather lacing
VERDIGRIS = ["#2f5a3a", "#3f7a58", "#5c9c78"]   # forest-green patina
PLAID_RED = ramp("#b8262e", 5, contrast=0.6)
PLAID_BLACK = "#1b1417"

# --------------------------------------------------------------------------------------
# The maple leaf head
# --------------------------------------------------------------------------------------
# Right half of the leaf outline in leaf units (x across, y up from the stem), mirrored.
LEAF_RIGHT = [(0.0, 18.5), (1.0, 15.8), (2.6, 16.9), (2.1, 14.3), (4.0, 14.5), (2.2, 11.0), (1.7, 9.0),
              (4.6, 13.4), (5.3, 12.2), (8.6, 15.6), (8.0, 12.6), (10.0, 12.2), (7.6, 9.6), (6.9, 7.2),
              (9.6, 6.4), (7.6, 4.6), (5.0, 4.0), (2.3, 1.8), (0.8, 1.2)]
LEAF_Y = 13.4          # model y of leaf y = 0
RES = 0.5              # one grid cell = one texel of the 64px leaf decal (2 texels per unit)
GX0, GY1 = -11.0, 19.0
COLS, ROWS = 44, 38
HEART = (0.0, 5.0)     # where the veins meet (leaf units)
LIGHT = (-0.55, 0.84)  # painted light: up and a little left
LAYERS = ((0, 0.9, 5), (2, 1.7, 0))   # (erosion in cells, thickness, glow): honed rim, then the blade
FOLD = 13.0            # each half of the leaf sweeps back this many degrees from the midrib
# Raised main veins follow each lobe's midline out from the heart, as polylines with a
# (width, depth) per segment: proud of the thick blade, then low where only the rim is.
_MIDRIB = ([(0.0, 6.3), (0.0, 11.8), (0.0, 15.2), (0.0, 17.1)], [(1.1, 2.3), (0.85, 2.0), (0.6, 1.3)])
_LATERAL = ([(0.95, 6.1), (4.7, 10.3), (6.85, 12.9), (7.45, 13.8)], [(1.1, 2.3), (0.85, 1.9), (0.6, 1.3)])
_BASAL = ([(1.35, 5.2), (5.6, 5.65), (8.4, 6.1)], [(1.0, 2.2), (0.7, 1.35)])
RIDGES = [_MIDRIB] + [([(sx * x, y) for x, y in pts], segs) for pts, segs in (_LATERAL, _BASAL) for sx in (1, -1)]
# Engraved side veins run from the main veins out to the teeth.
SIDE_VEINS = [((0.0, 12.2), (3.3, 14.0)), ((0.0, 12.2), (-3.3, 14.0)),
              ((0.0, 14.6), (2.0, 16.1)), ((0.0, 14.6), (-2.0, 16.1)),
              ((4.1, 9.0), (4.4, 12.6)), ((-4.1, 9.0), (-4.4, 12.6)),
              ((5.6, 10.8), (9.2, 11.9)), ((-5.6, 10.8), (-9.2, 11.9)),
              ((5.0, 5.9), (7.0, 4.8)), ((-5.0, 5.9), (-7.0, 4.8))]


def _outline():
    return LEAF_RIGHT + [(-x, y) for x, y in reversed(LEAF_RIGHT[1:])]


def _inside(pts, x, y):
    c = False
    for i in range(len(pts)):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % len(pts)]
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            c = not c
    return c


def _cell_centre(i, j):
    return (GX0 + (i + 0.5) * RES, GY1 - (j + 0.5) * RES)


def _grid():
    pts = _outline()
    return [[_inside(pts, *_cell_centre(i, j)) for i in range(COLS)] for j in range(ROWS)]


def _erode(g, n):
    for _ in range(n):
        g = [[g[j][i] and all(0 <= j + dj < ROWS and 0 <= i + di < COLS and g[j + dj][i + di]
                              for dj, di in ((1, 0), (-1, 0), (0, 1), (0, -1)))
              for i in range(COLS)] for j in range(ROWS)]
    return g


def _rects(g):
    """Greedy split of a cell grid into rectangles (i, j, w, h)."""
    used = [[False] * COLS for _ in range(ROWS)]
    out = []
    for j in range(ROWS):
        for i in range(COLS):
            if g[j][i] and not used[j][i]:
                w = 1
                while i + w < COLS and g[j][i + w] and not used[j][i + w]:
                    w += 1
                h = 1
                while j + h < ROWS and all(g[j + h][i + k] and not used[j + h][i + k] for k in range(w)):
                    h += 1
                for jj in range(j, j + h):
                    for ii in range(i, i + w):
                        used[jj][ii] = True
                out.append((i, j, w, h))
    return out


def _slab(g, rect, depth, glow=0):
    """One extruded block of the leaf; faces sample the decal, hidden sides are dropped."""
    i, j, w, h = rect
    t = 0.25                                     # one texel in uv units on the 64px decal
    x0 = 8 + GX0 + i * RES
    y1 = LEAF_Y + GY1 - j * RES
    faces = {"south": ("leaf", [i * t, j * t, (i + w) * t, (j + h) * t]),
             "north": ("leaf", [(i + w) * t, j * t, i * t, (j + h) * t]),
             "east": ("leaf", [(i + w - 1) * t, j * t, (i + w) * t, (j + h) * t]),
             "west": ("leaf", [i * t, j * t, (i + 1) * t, (j + h) * t]),
             "up": ("leaf", [i * t, j * t, (i + w) * t, (j + 1) * t]),
             "down": ("leaf", [i * t, (j + h - 1) * t, (i + w) * t, (j + h) * t])}

    def filled(ii, jj):
        return 0 <= ii < COLS and 0 <= jj < ROWS and g[jj][ii]

    skip = []
    if all(filled(i + w, jj) for jj in range(j, j + h)):
        skip.append("east")
    if all(filled(i - 1, jj) for jj in range(j, j + h)):
        skip.append("west")
    if all(filled(ii, j - 1) for ii in range(i, i + w)):
        skip.append("up")
    if all(filled(ii, j + h) for ii in range(i, i + w)):
        skip.append("down")
    return box((x0, y1 - h * RES, 8 - depth / 2), (x0 + w * RES, y1, 8 + depth / 2), "leaf",
               faces=faces, skip=tuple(skip), glow=glow)


def _ridges():
    """Raised main veins, thick near the heart and thinner toward the tips, as
    (element, side) with side -1/0/1 for the left half, the midrib and the right half."""
    parts = []
    for pts, segs in RIDGES:
        side = (pts[-1][0] > 0) - (pts[-1][0] < 0)
        for (a, b), (w, dep) in zip(zip(pts, pts[1:]), segs):
            length = math.dist(a, b)
            ux, uy = (b[0] - a[0]) / length, (b[1] - a[1]) / length
            a2 = (a[0] - ux * 0.15, a[1] - uy * 0.15)
            b2 = (b[0] + ux * 0.15, b[1] + uy * 0.15)
            da, db = math.dist(a2, HEART), math.dist(b2, HEART)
            v0, v1 = round(max(0.0, 13.5 - db), 3), round(min(16.0, 13.5 - da), 3)
            faces = {"south": ("ridge", [0, v0, w, v1]), "north": ("ridge", [w, v0, 0, v1]),
                     "east": ("ridge", [4, v0, 4 + dep, v1]), "west": ("ridge", [4, v0, 4 + dep, v1]),
                     "up": ("ridge", [4, v0, 4 + w, v0 + 0.5]), "down": ("ridge", [4, v1 - 0.5, 4 + w, v1])}
            p0 = (8 + a2[0], LEAF_Y + a2[1], 8)
            p1 = (8 + b2[0], LEAF_Y + b2[1], 8)
            parts.append((bar(p0, p1, w, dep, "ridge", faces=faces), side))
    return parts


def leaf():
    g = _grid()
    halves = {-1: [], 0: [], 1: []}
    for erosion, depth, glow in LAYERS:
        layer = _erode(g, erosion)
        for side, (c0, c1) in ((-1, (0, COLS // 2)), (1, (COLS // 2, COLS))):
            half = [[layer[j][i] and c0 <= i < c1 for i in range(COLS)] for j in range(ROWS)]
            halves[side] += [_slab(half, r, depth, glow) for r in _rects(half)]
    for part, side in _ridges():
        halves[side].append(part)
    # Fold the two halves back along the midrib so the leaf has a shallow V section.
    turn(halves[1], FOLD, "y", (8, 0, 8))
    turn(halves[-1], -FOLD, "y", (8, 0, 8))
    parts = halves[-1] + halves[0] + halves[1]
    hx, hy = 8 + HEART[0], LEAF_Y + HEART[1]
    bezel = box((hx - 1.45, hy - 1.45, 6.65), (hx + 1.45, hy + 1.45, 9.35), "bezel", uv="full")
    turn(bezel, 45, "z", (hx, hy, 8))
    heart = box((hx - 0.95, hy - 0.95, 6.4), (hx + 0.95, hy + 0.95, 9.6), "gem", uv="full", glow=12)
    turn(heart, 45, "z", (hx, hy, 8))
    return parts + [bezel, heart]


def _line(a, b):
    """Bresenham pixels between two points given in texel coordinates."""
    x0, y0, x1, y1 = int(math.floor(a[0])), int(math.floor(a[1])), int(math.floor(b[0])), int(math.floor(b[1]))
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err, pts = dx + dy, []
    while True:
        pts.append((x0, y0))
        if x0 == x1 and y0 == y1:
            return pts
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def _texel(p):
    return ((p[0] - GX0) / RES, (GY1 - p[1]) / RES)


def _seg_dist(p, a, b):
    """Distance from point p to segment ab (leaf units)."""
    abx, aby = b[0] - a[0], b[1] - a[1]
    t = max(0.0, min(1.0, ((p[0] - a[0]) * abx + (p[1] - a[1]) * aby) / (abx * abx + aby * aby)))
    return math.hypot(p[0] - a[0] - abx * t, p[1] - a[1] - aby * t)


def _leaf_value(d_heart):
    """Palette index along the leaf: crimson at the heart, gold toward the tips."""
    t = max(0.0, min(1.0, d_heart / 13.0))
    return 2.4 + 8.6 * t ** 1.15, t


def _pick(v, i, j):
    """Palette colour for a fractional index, with a checker dither between bands."""
    v = max(0.0, min(len(LEAF) - 1.0, v))
    lo = math.floor(v)
    f = v - lo
    k = lo + ((i + j) % 2 if 0.44 < f < 0.56 else (1 if f >= 0.56 else 0))
    return LEAF[min(len(LEAF) - 1, k)]


def _paint_leaf():
    g = _grid()
    # Distance (in cells) from the outside, 4-connected.
    dist = [[0 if not g[j][i] else 99 for i in range(COLS)] for j in range(ROWS)]
    for _ in range(6):
        for j in range(ROWS):
            for i in range(COLS):
                if g[j][i]:
                    best = min((dist[j + dj][i + di] if 0 <= j + dj < ROWS and 0 <= i + di < COLS else 0)
                               for dj, di in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                    dist[j][i] = min(dist[j][i], best + 1)
    lx, ly = LIGHT
    ln = math.hypot(lx, ly)
    lx, ly = lx / ln, ly / ln
    idx = [[0.0] * COLS for _ in range(ROWS)]
    for j in range(ROWS):
        for i in range(COLS):
            if not g[j][i]:
                continue
            x, y = _cell_centre(i, j)
            v, t = _leaf_value(math.hypot(x - HEART[0], y - HEART[1]))
            # Outward normal from nearby empty cells, for rim lighting.
            nx = ny = 0.0
            for dj in range(-2, 3):
                for di in range(-2, 3):
                    ii, jj = i + di, j + dj
                    if not (0 <= ii < COLS and 0 <= jj < ROWS and g[jj][ii]):
                        nx += di
                        ny -= dj
            n = math.hypot(nx, ny)
            lit = (nx * lx + ny * ly) / n if n else 0.0
            d = dist[j][i]
            k = 1.0 - 0.45 * t
            if d == 1:
                v += (3.0 if lit > 0.25 else (1.7 if lit > -0.35 else 0.2)) * k
            elif d == 2:
                v += (1.7 if lit > 0.25 else (0.9 if lit > -0.35 else -0.5)) * k
            elif d == 3:
                v += 0.7 if lit > 0.25 else (0.0 if lit > -0.35 else -0.8)
            idx[j][i] = min(v, 12.2 if d <= 2 else 11.2)
    # The raised main veins cast a soft dark channel on the blade either side of them.
    segs = [(a, b, w) for pts, specs in RIDGES for (a, b), (w, _) in zip(zip([HEART] + pts[1:], pts[1:]), specs)]
    for j in range(ROWS):
        for i in range(COLS):
            if g[j][i] and dist[j][i] > 2:
                p = _cell_centre(i, j)
                gap = min(_seg_dist(p, a, b) - w / 2 for a, b, w in segs)
                if 0.0 < gap < 0.55:
                    idx[j][i] -= 0.8
    # Engraved side veins: dark groove with a lit lip below it.
    groove = set()
    for a, b in SIDE_VEINS:
        groove.update(_line(_texel(a), _texel(b)))
    for i, j in groove:
        if 0 <= i < COLS and 0 <= j < ROWS and g[j][i] and dist[j][i] > 2:
            idx[j][i] = min(idx[j][i], idx[j][i] - 1.9)
    img = canvas(64)
    px = img.load()
    for j in range(ROWS):
        for i in range(COLS):
            if g[j][i]:
                px[i, j] = rgba(_pick(idx[j][i], i, j))
    return img


def _paint_ridge():
    """Raised vein: v = 13.5 - distance from the heart, so it shares the leaf gradient.
    Columns 0-2 are the lit top of the ridge, columns 8-15 its shaded flanks."""
    img = canvas(32)
    px = img.load()
    for y in range(32):
        v, _ = _leaf_value(13.5 - (y + 0.5) / 2)
        for x, bump in ((0, 1.9), (1, 0.8), (2, 0.2)):
            px[x, y] = rgba(_pick(min(12.0, v + bump), x, y))
        for x in range(8, 16):
            px[x, y] = rgba(_pick(v - 3.0, x, y))
    return img


# --------------------------------------------------------------------------------------
# Collar, shaft, grip, pommel
# --------------------------------------------------------------------------------------

def ring(y0, y1, r, tex, sides=8, glow=0, cap=None, v0=0.0, u0=0.0):
    """A round band whose side faces tile the texture around the circumference."""
    parts = prism((8, (y0 + y1) / 2, 8), r, y1 - y0, tex, sides=sides, glow=glow)
    w = 2 * r * math.tan(math.pi / sides)
    step = 360 / sides
    normals = {"east": (1, 0, 0), "west": (-1, 0, 0), "north": (0, 0, -1), "south": (0, 0, 1)}
    for el in parts:
        m, _ = rotation_of(el)
        for side in list(el["faces"]):
            face = el["faces"][side]
            if side in ("up", "down"):
                if cap is None:
                    del el["faces"][side]
                else:
                    face["texture"] = "#" + cap[0]
                    face["uv"] = list(cap[1])
                continue
            n = normals[side]
            nx = sum(m[0][k] * n[k] for k in range(3))
            nz = sum(m[2][k] * n[k] for k in range(3))
            k = round(-math.degrees(math.atan2(nz, nx)) / step) % sides
            u = (u0 + k * w) % 16
            if u + w > 16:
                u = max(0.0, 16 - w)
            face["uv"] = [round(u, 4), v0, round(min(16, u + w), 4), round(min(16, v0 + (y1 - y0)), 4)]
    return parts


# A wolf fang, root to tip, in its own vertical plane (x = distance from the axis).
FANG = [(2.1, 12.5), (2.95, 11.35), (3.45, 10.05), (3.6, 8.85), (3.42, 7.8)]
FANG_W = [1.45, 1.2, 0.95, 0.62]


def _fang(azimuth):
    seg = []
    lengths = [math.dist(FANG[k], FANG[k + 1]) for k in range(len(FANG) - 1)]
    total = sum(lengths)
    along = 0.0
    for k, w in enumerate(FANG_W):
        (x0, y0), (x1, y1) = FANG[k], FANG[k + 1]
        mx, my = (x0 + x1) / 2, (y0 + y1) / 2
        p0 = (8 + mx + (x0 - mx) * 1.12, my + (y0 - my) * 1.12, 8)
        p1 = (8 + mx + (x1 - mx) * 1.12, my + (y1 - my) * 1.12, 8)
        v0 = total - along - lengths[k]          # texture runs tip (v = 0) to root
        seg.append(bar(p0, p1, w, w * 0.95, "fang", offset=(0, round(max(0.0, v0), 3))))
        along += lengths[k]
    return turn(seg, azimuth, "y", (8, 0, 8))


def collar():
    cap = ("brass", [15, 15, 16, 16])
    parts = ring(12.0, 14.4, 2.3, "brass", v0=0.0, cap=cap)
    parts += ring(14.3, 14.9, 2.75, "brass", v0=6.0, cap=cap)
    parts += ring(11.5, 12.1, 2.05, "brass", v0=8.0, cap=cap)
    for k in range(6):
        parts += _fang(k * 60)
        # An amber sap bead set in the collar between each pair of fangs.
        bead = box((10.05, 12.55, 7.55), (10.95, 13.45, 8.45), "gem", uv="full", glow=10)
        turn(bead, 45, "x", (10.5, 13.0, 8))
        parts.append(turn(bead, 30 + k * 60, "y", (8, 0, 8)))
    return parts


def shaft():
    parts = ring(-0.8, 12.4, 0.92, "amber", glow=10)
    # Two strands of maple wound in a twist around the sap core.
    y0, y1, radius, pitch, step = -0.9, 12.3, 1.08, 10.0, 30.0
    nseg = math.ceil((y1 - y0) / pitch * 360 / step)
    dy = (y1 - y0) / nseg
    for s in range(2):
        for k in range(nseg):
            a0 = s * 180 + k * step
            a1 = a0 + step
            p0 = [8 + radius * math.cos(math.radians(a0)), y0 + k * dy, 8 + radius * math.sin(math.radians(a0))]
            p1 = [8 + radius * math.cos(math.radians(a1)), y0 + (k + 1) * dy, 8 + radius * math.sin(math.radians(a1))]
            mid = [(p0[i] + p1[i]) / 2 for i in range(3)]
            p0 = [mid[i] + (p0[i] - mid[i]) * 1.15 for i in range(3)]
            p1 = [mid[i] + (p1[i] - mid[i]) * 1.15 for i in range(3)]
            length = math.dist(p0, p1)
            u = (k * 1.3 + s * 0.7) % 2.5
            v = round((k * 2.3 + s * 3.1) % (15.5 - length), 3)
            # Outer face lit, flanks mid, inner face dark: the wood grain runs along the strand.
            faces = {"east": ("wood", [u, v, u + 1.35, v + length]),
                     "north": ("wood", [4 + u / 2, v, 5.5 + u / 2, v + length]),
                     "south": ("wood", [4 + u / 2, v, 5.5 + u / 2, v + length]),
                     "west": ("wood", [8, v, 9.35, v + length]),
                     "up": ("wood", [12, 0, 13.5, 1.35]), "down": ("wood", [12, 0, 13.5, 1.35])}
            parts.append(bar(p0, p1, 1.5, 1.35, "wood", roll=-(a0 + a1) / 2, faces=faces))
    parts += ring(4.6, 6.1, 1.95, "copper", v0=0.0, cap=("copper", [15, 15, 16, 16]))
    return parts


def grip():
    parts = ring(-7.7, -1.7, 1.72, "plaid")
    parts += ring(-2.2, -1.0, 1.95, "cord", cap=("cord", [15, 15, 16, 16]))
    parts += ring(-8.4, -7.2, 1.95, "cord", cap=("cord", [15, 15, 16, 16]))
    parts += ring(-1.2, -0.3, 1.85, "copper", v0=4.0, cap=("copper", [15, 15, 16, 16]))
    return parts


def pommel():
    cap = ("copper", [15, 15, 16, 16])
    parts = ring(-10.1, -8.3, 1.8, "copper", v0=8.0, cap=cap)
    parts += ring(-10.7, -10.0, 2.1, "copper", v0=12.0, cap=cap)
    # The sap drop: two crossed diamonds make a faceted amber gem.
    gem_a = box((6.7, -13.6, 6.9), (9.3, -11.0, 9.1), "gem", uv="full", glow=12)
    turn(gem_a, 45, "z", (8, -12.3, 8))
    gem_b = box((6.9, -13.6, 6.7), (9.1, -11.0, 9.3), "gem", uv="full", glow=12)
    turn(gem_b, 45, "x", (8, -12.3, 8))
    parts += [gem_a, gem_b]
    # Brass cage prongs curling round the drop into the butt spike.
    for k in range(4):
        upper = bar((9.55, -10.5, 8), (10.0, -12.4, 8), 0.6, 0.6, "brass", offset=(0, 10))
        lower = bar((10.0, -12.3, 8), (8.35, -14.4, 8), 0.6, 0.6, "brass", offset=(1, 10))
        seg = [upper, lower]
        turn(seg, 45 + k * 90, "y", (8, 0, 8))
        parts += seg
    spike_a = box((7.3, -15.4, 7.3), (8.7, -14.0, 8.7), "brass", offset=(2, 10))
    turn(spike_a, 45, "z", (8, -14.7, 8))
    spike_b = box((7.3, -15.4, 7.3), (8.7, -14.0, 8.7), "brass", offset=(2, 10))
    turn(spike_b, 45, "x", (8, -14.7, 8))
    parts += [spike_a, spike_b]
    return parts


def build():
    return leaf() + collar() + shaft() + grip() + pommel()


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def textures() -> None:
    save(_paint_leaf(), "leaf")
    save(_paint_ridge(), "ridge")

    # Amber gem: dark rim, hot centre, a white glint up top.
    gem = canvas(16, fill=AMBER[3])
    for r, c in ((7, AMBER[1]), (6, AMBER[2]), (5, AMBER[3]), (3, AMBER[4]), (1, AMBER[5])):
        fill(gem, (8 - r, 8 - r, 7 + r, 7 + r), c)
    for b in ((0, 0, 15, 0), (0, 15, 15, 15), (0, 0, 0, 15), (15, 0, 15, 15)):
        fill(gem, b, AMBER[0])
    fill(gem, (3, 3, 5, 4), "#fff6d8")
    fill(gem, (3, 5, 3, 5), "#fff6d8")
    save(gem, "gem")

    # Copper bezel around the heart gem.
    bez = canvas(16, fill=COPPER[3])
    bevel(bez, (0, 0, 15, 15), COPPER[5], COPPER[1])
    bevel(bez, (2, 2, 13, 13), COPPER[1], COPPER[4])
    fill(bez, (4, 4, 11, 11), COPPER[2])
    for x, y in ((1, 7), (7, 1), (14, 8), (8, 14)):
        fill(bez, (x, y, x, y), VERDIGRIS[1])
    save(bez, "bezel")

    # Sap core: bright flowing streaks.
    amber = canvas(16, fill=AMBER[3])
    rng = random.Random(3)
    px = amber.load()
    for x in range(16):
        for y in range(16):
            if (x + y // 3) % 4 == 0:
                px[x, y] = rgba(AMBER[4])
            if rng.random() < 0.06:
                px[x, y] = rgba(AMBER[5])
    save(amber, "amber")

    # Twisted maple, 2 texels per unit. Columns 0-7: the lit outer face of a strand;
    # 8-15: its flanks; 16-23: the inner face against the core; 24-31: cut ends.
    wood = canvas(32)
    rng = random.Random(11)
    px = wood.load()
    for x0, lanes, streak in ((0, [WOOD[3], WOOD[4], WOOD[4], WOOD[3], WOOD[4], WOOD[5], WOOD[4], WOOD[3]], WOOD[2]),
                              (8, [WOOD[2], WOOD[3], WOOD[2], WOOD[2], WOOD[3], WOOD[2], WOOD[3], WOOD[2]], WOOD[1]),
                              (16, [WOOD[1], WOOD[1], WOOD[2], WOOD[1], WOOD[1], WOOD[2], WOOD[1], WOOD[1]], WOOD[0])):
        for x in range(8):
            for y in range(32):
                px[x0 + x, y] = rgba(lanes[x])
        # Grain: long darker streaks and the odd knot along the strand.
        for _ in range(9):
            x, y = x0 + rng.randrange(8), rng.randrange(32)
            for k in range(rng.randint(3, 7)):
                px[x, (y + k) % 32] = rgba(streak)
    for y in range(32):
        for x in range(24, 32):
            px[x, y] = rgba(WOOD[1] if (x + y) % 3 else WOOD[0])
    save(wood, "wood")

    # Copper band profiles, one strip per band (v0 in uv units -> row 2 * v0): a bright
    # top edge, the body with rivets, a shadowed lower lip and a little green patina.
    copper = canvas(32)
    px = copper.load()
    rng = random.Random(5)
    for v0, rows in ((0, [COPPER[5], COPPER[4], COPPER[2]]),              # mid-shaft band, 1.5 tall
                     (4, [COPPER[5], COPPER[2]]),                          # band above the grip, 0.9
                     (8, [COPPER[5], COPPER[4], COPPER[3], COPPER[1]]),    # ferrule, 1.8
                     (12, [COPPER[5], COPPER[2]])):                        # ferrule flange, 0.7
        for y, c in enumerate(rows):
            fill(copper, (0, 2 * v0 + y, 31, 2 * v0 + y), c)
        for x in range(1, 32, 4):
            if len(rows) > 2:
                px[x, 2 * v0 + 1] = rgba(COPPER[5])
        for _ in range(4):
            px[rng.randrange(32), 2 * v0 + len(rows) - 1] = rgba(VERDIGRIS[1])
    fill(copper, (30, 30, 31, 31), COPPER[2])
    save(copper, "copper")

    # Brass collar (rows 0-4), flange (12-13), lower rim (16-17), cage wire (20-27).
    brass = canvas(32)
    for y, c in enumerate([BRASS[5], BRASS[4], BRASS[3], BRASS[3], BRASS[1]]):
        fill(brass, (0, y, 31, y), c)
    px = brass.load()
    for x in range(1, 32, 4):
        px[x, 2] = rgba(BRASS[0])
        px[x, 1] = rgba(BRASS[5])
    for y, c in enumerate([BRASS[5], BRASS[2]]):
        fill(brass, (0, 12 + y, 31, 12 + y), c)
    fill(brass, (0, 16, 31, 16), BRASS[4])
    fill(brass, (0, 17, 31, 17), BRASS[2])
    fill(brass, (0, 20, 31, 27), BRASS[3])
    fill(brass, (0, 20, 31, 20), BRASS[5])
    fill(brass, (0, 20, 0, 27), BRASS[5])
    fill(brass, (30, 30, 31, 31), BRASS[3])
    save(brass, "brass")

    # Wolf fang (v runs tip -> root): cream tip, ivory crown, tan root with a carved groove.
    fang = canvas(32)
    rows = [IVORY[5], IVORY[5], IVORY[4], IVORY[4], IVORY[4], IVORY[4], IVORY[3], IVORY[3], IVORY[3],
            IVORY[3], IVORY[2], IVORY[2], IVORY[1], IVORY[0], IVORY[1]]
    for y, c in enumerate(rows):
        fill(fang, (0, y, 31, y), c)
    fill(fang, (0, len(rows), 31, 31), IVORY[1])
    px = fang.load()
    for y in range(len(rows) - 3):
        px[0, y] = rgba(IVORY[5] if y > 1 else "#ffffff")
        px[2, y] = rgba(IVORY[2] if y < 10 else IVORY[1])
    save(fang, "fang")

    # Buffalo plaid: red and black checks, the crossings a dark woven red.
    plaid = canvas(32)
    px = plaid.load()
    for y in range(32):
        for x in range(32):
            rx, ry = (x // 3) % 2 == 0, (y // 3) % 2 == 0
            if rx and ry:
                c = PLAID_RED[2]
            elif not rx and not ry:
                c = PLAID_BLACK
            else:
                c = PLAID_RED[0] if (x + y) % 2 else "#44121a"
            px[x, y] = rgba(c)
    save(plaid, "plaid")

    # Leather cord binding: horizontal wraps with a lit crest and a dark groove.
    cord = canvas(32)
    for y in range(32):
        fill(cord, (0, y, 31, y), [LACE[4], LACE[2], LACE[0]][y % 3])
    px = cord.load()
    for x in range(0, 32, 5):
        for y in range(1, 32, 3):
            px[x, y] = rgba(LACE[3])
    fill(cord, (30, 30, 31, 31), LACE[1])            # flat patch for the band end caps
    save(cord, "cord")


# --------------------------------------------------------------------------------------
# Models
# --------------------------------------------------------------------------------------

def models() -> dict:
    parts = build()
    main = display(KIND, parts, grip=GRIP, size=SIZE, gui_span=16.0)
    # Third person: held like a tall staff, tipped a little outward so the fang collar
    # clears the wearer's face; the leaf still fans out to the side.
    main["thirdperson_righthand"] = place({"y": (-0.16, 1, 0.2), "z": (-1, 0, 0)}, GRIP, "fist", SIZE)
    # First person: stand it up at the right edge so the whole leaf shows beside the crosshair.
    main["firstperson_righthand"] = place({"y": (-0.2, 0.97, -0.12), "z": (0.2, 0, 1)}, GRIP,
                                          (0.58, -0.66, -0.95), 0.53 * SIZE, pose=None)
    throw_parts = copy(parts)
    throwing = display_for_state("trident", "throwing", throw_parts, grip=GRIP, size=SIZE)
    throwing["gui"] = main["gui"]
    return {"main": model(parts, main), "throwing": model(throw_parts, throwing)}
