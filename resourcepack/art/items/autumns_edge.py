"""Autumn's Edge: the September greatsword, forged like a giant maple leaf.

The blade is cut into maple lobes and teeth. Its colour burns from harvest gold at the
hilt through amber and orange to crimson at the tip, veined in gold around a raised
midrib with a glowing engraved channel. Two curling bronze maple leaves form the guard,
the grip is dark oak bark bound with twine, and the pommel is a glowing amber acorn.
Loose leaves drift beside the upper blade.

Most faces are baked: every texel is painted from its position on the sword, so the
colour, veins and edges run on without seams across the rotated lobes. Each leaf
(blade and guard) is a thin plate that carries the sharp lobed outline, with thicker
parts set inside it for depth.
"""
from __future__ import annotations

import math

from art.kit import (bar, box, canvas, copy, corners, display, mirror, model, place, prism, ramp, rgba,
                     rotation_of, save, turn)

ID = "autumns_edge"
NAME = "Autumn's Edge"
KIND = "sword"

# --------------------------------------------------------------------------------------
# Palettes (hue-shifted ramps, darkest -> lightest; index 3 is the base colour)
# --------------------------------------------------------------------------------------
GOLD = ramp("#f2bd44", 7, 0.75)
AMBER = ramp("#f09a33", 7, 0.75)
ORANGE = ramp("#e46f25", 7, 0.75)
RED = ramp("#c93f25", 7, 0.75)
CRIMSON = ramp("#9e2231", 7, 0.75)
BURN = (GOLD, AMBER, ORANGE, RED, CRIMSON)
VEIN = ramp("#f8c440", 7, 0.8)
EMBER = ramp("#ffb040", 7, 0.7)
BRONZE = ramp("#b27634", 7, 0.8)
BARK = ramp("#4f3323", 7, 0.8)
TWINE = ramp("#cdaa68", 7, 0.8)
CAP = ramp("#7d5630", 7, 0.8)
NUT = ramp("#f4a52e", 7, 0.7)

PX = 2              # texels per model unit on every baked face
PAGE = 64           # bake page size
K = PAGE / 16       # texels per UV unit on a bake page
BAYER = (0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)


def clamp(v, lo=0.0, hi=1.0):
    return max(lo, min(hi, v))


def unit(dx, dy):
    n = math.hypot(dx, dy)
    return dx / n, dy / n


def perp(d):
    return (-d[1], d[0])


def add2(*terms):
    """Sum of plain 2D points and (vector, scale) pairs."""
    x = y = 0.0
    for t in terms:
        if isinstance(t[0], tuple):
            (vx, vy), k = t
            x, y = x + vx * k, y + vy * k
        else:
            x, y = x + t[0], y + t[1]
    return (x, y)


def mx(p):
    return (16 - p[0], p[1])


# --------------------------------------------------------------------------------------
# 2D shapes (front view of the blade and guard)
# --------------------------------------------------------------------------------------

def hull(points):
    pts = sorted({(round(x, 5), round(y, 5)) for x, y in points})
    if len(pts) < 3:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def footprint(element):
    return hull([(p[0], p[1]) for p in corners(element)])


def seg_dist(x, y, a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    n = dx * dx + dy * dy
    t = 0.0 if n == 0 else clamp(((x - a[0]) * dx + (y - a[1]) * dy) / n)
    return math.hypot(x - a[0] - t * dx, y - a[1] - t * dy)


def in_poly(poly, x, y):
    n = len(poly)
    for i in range(n):
        ax, ay = poly[i]
        bx, by = poly[(i + 1) % n]
        if (bx - ax) * (y - ay) - (by - ay) * (x - ax) < 0:
            return False
    return True


class Shape:
    """A union of convex polygons with inside and distance tests."""

    def __init__(self, polys=()):
        self.polys = []
        for p in polys:
            self.add(p)

    def add(self, poly):
        xs, ys = [q[0] for q in poly], [q[1] for q in poly]
        self.polys.append((poly, min(xs), min(ys), max(xs), max(ys)))

    def inside(self, x, y):
        for poly, x0, y0, x1, y1 in self.polys:
            if x0 <= x <= x1 and y0 <= y <= y1 and in_poly(poly, x, y):
                return True
        return False

    def dist(self, x, y, cap=6.0):
        best = cap
        for poly, x0, y0, x1, y1 in self.polys:
            if x < x0 - best or x > x1 + best or y < y0 - best or y > y1 + best:
                continue
            if in_poly(poly, x, y):
                return 0.0
            for i in range(len(poly)):
                best = min(best, seg_dist(x, y, poly[i], poly[(i + 1) % len(poly)]))
        return best

    def bounds(self):
        return (min(p[1] for p in self.polys), min(p[2] for p in self.polys),
                max(p[3] for p in self.polys), max(p[4] for p in self.polys))


class Lines:
    def __init__(self, segs=()):
        self.segs = list(segs)

    def dist(self, x, y):
        return min((seg_dist(x, y, a, b) for a, b in self.segs), default=99.0)


OUTLINE = True  # a dark 1-texel outline around the leaf plates (reads better small)


def near_edge(shape, x, y):
    """True for texels one step in from a shape's outline."""
    return not all(shape.inside(x + dx, y + dy) for dx, dy in ((1.0, 0.0), (-1.0, 0.0), (0.0, 1.0), (0.0, -1.0)))


def edge_kind(shape, x, y):
    """Where a texel sits on a shape's outline: 'top', 'bottom', 'side' or None (inside)."""
    if not shape.inside(x, y + 0.5):
        return "top"
    if not shape.inside(x, y - 0.5):
        return "bottom"
    if not (shape.inside(x + 0.5, y) and shape.inside(x - 0.5, y)):
        return "side"
    return None


def taper(p0, p1, w0, w1, m, spike=0.0):
    """Outline of a segment that narrows from width w0 to w1 (plus margin m on each side),
    optionally ending in a sharp point `spike` past p1."""
    d = unit(p1[0] - p0[0], p1[1] - p0[1])
    n = perp(d)
    pts = [add2(p0, (n, w0 / 2 + m)), add2(p0, (n, -w0 / 2 - m)),
           add2(p1, (n, w1 / 2 + m)), add2(p1, (n, -w1 / 2 - m))]
    if spike:
        pts.append(add2(p1, (d, w1 / 2 + spike)))
    return hull(pts)


def tooth_at(base, d, n, sgn, h):
    """A forward-leaning tooth whose base sits on an edge at `base` (sgn picks the side)."""
    return hull([add2(base, (d, -0.9 * h)), add2(base, (d, 0.5 * h)),
                 add2(base, (n, sgn * h), (d, 0.9 * h))])


def ring(c, r, sides=8):
    return hull([(c[0] + r * math.cos(2 * math.pi * k / sides), c[1] + r * math.sin(2 * math.pi * k / sides))
                 for k in range(sides)])


# --------------------------------------------------------------------------------------
# Baking: every requested face gets its own texels, painted from its position
# --------------------------------------------------------------------------------------

FACE_VERTS = {
    "down": lambda a, b: [(a[0], a[1], b[2]), (a[0], a[1], a[2]), (b[0], a[1], a[2]), (b[0], a[1], b[2])],
    "up": lambda a, b: [(a[0], b[1], a[2]), (a[0], b[1], b[2]), (b[0], b[1], b[2]), (b[0], b[1], a[2])],
    "north": lambda a, b: [(b[0], b[1], a[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2]), (a[0], b[1], a[2])],
    "south": lambda a, b: [(a[0], b[1], b[2]), (a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], b[1], b[2])],
    "west": lambda a, b: [(a[0], b[1], a[2]), (a[0], a[1], a[2]), (a[0], a[1], b[2]), (a[0], b[1], b[2])],
    "east": lambda a, b: [(b[0], b[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (b[0], b[1], a[2])],
}


def face_dims(e, side):
    (a0, a1, a2), (b0, b1, b2) = e["from"], e["to"]
    dx, dy, dz = b0 - a0, b1 - a1, b2 - a2
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
            "up": (dx, dz), "down": (dx, dz)}[side]


def face_points(e, side):
    pts = FACE_VERTS[side](e["from"], e["to"])
    m, o = rotation_of(e)
    if o is None:
        return pts
    return [tuple(sum(m[i][k] * (p[k] - o[k]) for k in range(3)) + o[i] for i in range(3)) for p in pts]


def _shelf(pg, w, h):
    if pg["x"] + w > PAGE:
        pg["y"] += pg["h"] + 1
        pg["x"], pg["h"] = 0, 0
    if pg["y"] + h > PAGE or w > PAGE:
        return None
    spot = (pg["x"], pg["y"])
    pg["x"] += w + 1
    pg["h"] = max(pg["h"], h)
    return spot


class Baker:
    def __init__(self, prefix):
        self.prefix = prefix
        self.items = []
        self.jobs = []
        self.pages = 0

    def add(self, elements, painter, share=True):
        """Bake these elements with painter(p, side) -> colour or None (transparent). The
        position p is recorded now, so later turn() calls do not change the painting.
        share=True paints north and south once (front and back show the same texel)."""
        for e in (elements if isinstance(elements, list) else [elements]):
            self.items.append((e, copy(e), painter, share))
        return elements

    def pack(self):
        todo = []
        for n, (e, snap, painter, share) in enumerate(self.items):
            for side in e["faces"]:
                if share and side == "north" and "south" in e["faces"]:
                    continue
                fw, fh = face_dims(snap, side)
                if fw < 1e-6 or fh < 1e-6:
                    continue
                w, h = max(1, math.ceil(fw * PX - 1e-6)), max(1, math.ceil(fh * PX - 1e-6))
                twin = "north" if share and side == "south" and "north" in e["faces"] else None
                todo.append((h, w, n, side, twin, fw, fh))
        todo.sort(key=lambda t: (-t[0], -t[1], t[2], t[3]))
        shelves = []
        for h, w, n, side, twin, fw, fh in todo:
            spot = None
            for i, pg in enumerate(shelves):
                s = _shelf(pg, w, h)
                if s:
                    spot = (i, *s)
                    break
            if spot is None:
                shelves.append({"x": 0, "y": 0, "h": 0})
                s = _shelf(shelves[-1], w, h)
                if s is None:
                    raise ValueError(f"face {side} of baked element {n} is {w}x{h} texels; max {PAGE}")
                spot = (len(shelves) - 1, *s)
            page, x, y = spot
            uv = [x / K, y / K, (x + fw * PX) / K, (y + fh * PX) / K]
            e, snap, painter, _ = self.items[n]
            name = f"{self.prefix}{page}"
            e["faces"][side]["uv"] = [round(v, 4) for v in uv]
            e["faces"][side]["texture"] = "#" + name
            if twin:
                e["faces"][twin]["uv"] = [round(v, 4) for v in (uv[2], uv[1], uv[0], uv[3])]
                e["faces"][twin]["texture"] = "#" + name
            self.jobs.append((page, uv, (x, y, w, h), snap, side, painter))
        self.pages = len(shelves)

    def paint(self):
        images = [canvas(PAGE) for _ in range(self.pages)]
        for page, uv, (x0, y0, w, h), snap, side, painter in self.jobs:
            px = images[page].load()
            v = face_points(snap, side)
            o = v[0]
            du = [v[3][i] - v[0][i] for i in range(3)]
            dv = [v[1][i] - v[0][i] for i in range(3)]
            u0, v0, u1, v1 = uv
            for j in range(h):
                t = clamp(((y0 + j + 0.5) / K - v0) / (v1 - v0))
                for i in range(w):
                    s = clamp(((x0 + i + 0.5) / K - u0) / (u1 - u0))
                    p = (o[0] + s * du[0] + t * dv[0], o[1] + s * du[1] + t * dv[1], o[2] + s * du[2] + t * dv[2])
                    c = painter(p, side)
                    if c is not None:
                        px[x0 + i, y0 + j] = rgba(c)
        # Extrude every region's border into its 1-texel gutter so mipmaps never pull
        # transparent texels into a face's edge.
        for page, uv, (x0, y0, w, h), snap, side, painter in self.jobs:
            px = images[page].load()
            for j in range(-1, h + 1):
                for i in range(-1, w + 1):
                    if 0 <= i < w and 0 <= j < h:
                        continue
                    x, y = x0 + i, y0 + j
                    if not (0 <= x < PAGE and 0 <= y < PAGE) or px[x, y][3]:
                        continue
                    src = px[x0 + min(max(i, 0), w - 1), y0 + min(max(j, 0), h - 1)]
                    if src[3]:
                        px[x, y] = src
        for n, image in enumerate(images):
            save(image, f"{self.prefix}{n}")


# --------------------------------------------------------------------------------------
# Part helpers
# --------------------------------------------------------------------------------------

def diamond(c, half, direction, z, tex="x", **kw):
    """A square turned so one corner points along `direction`, `half` from centre to corner."""
    a = math.atan2(direction[1], direction[0]) - math.pi / 4
    ex, ey = math.cos(a), math.sin(a)
    side = half * math.sqrt(2)
    zc, depth = (z[0] + z[1]) / 2, z[1] - z[0]
    return bar((c[0] - ex * side / 2, c[1] - ey * side / 2, zc), (c[0] + ex * side / 2, c[1] + ey * side / 2, zc),
               side, depth, tex, **kw)


def plate(p0, p1, width, z, tex="x", **kw):
    """A flat bar in the XY plane between two 2D points."""
    zc = (z[0] + z[1]) / 2
    return bar((p0[0], p0[1], zc), (p1[0], p1[1], zc), width, z[1] - z[0], tex, **kw)


def around(p, radius):
    """Distance travelled around a round part (units), from its angle about the y axis."""
    th = math.atan2(p[2] - 8, p[0] - 8)
    return th / (2 * math.pi) * (16 * radius * math.tan(math.pi / 8))


# --------------------------------------------------------------------------------------
# The blade
# --------------------------------------------------------------------------------------

Z_CORE = (7.3, 8.7)
Z_LOBE = (7.36, 8.64)
Z_TIPD = (7.42, 8.58)
Z_RIM = (7.86, 8.14)
Z_SPINE = (6.95, 9.05)
Z_CHANNEL = (7.18, 8.82)
Z_VEIN = (7.14, 8.86)
MARGIN = 0.9
RIM_GLOW = 6       # the honed edge smoulders in the dark

CORE = [(1.4, 5.2, 2.5), (5.2, 16.0, 3.3), (16.0, 18.4, 2.7), (18.4, 24.2, 3.0), (24.2, 27.0, 2.2)]
TIP_Y, TIP_HALF, APEX = 27.0, 2.2, 31.8
LOBES = [  # left side (mirrored): root, tip, root width, tip width, spike, teeth [(fraction, edge, height)]
    ((7.0, 7.2), (2.0, 14.6), 4.2, 1.8, 2.4, [(0.62, 1, 1.4), (0.72, -1, 1.0)]),
    ((7.2, 18.4), (4.6, 22.0), 2.6, 1.2, 1.8, [(0.58, 1, 0.9)]),
    ((7.4, 23.2), (5.6, 25.9), 1.9, 1.0, 1.4, []),
]


def burn_colour(b, idx, x, y):
    """The blade's autumn colour for burn level b (0 gold .. 1 crimson) at ramp index idx."""
    p = clamp(b) * (len(BURN) - 1)
    i = min(int(p), len(BURN) - 2)
    g = clamp((p - i - 0.44) / 0.12)
    thr = (BAYER[(int(math.floor(y * PX)) % 4) * 4 + int(math.floor(x * PX)) % 4] + 0.5) / 16
    return (BURN[i + 1] if g > thr else BURN[i])[idx]


def build_blade(bk):
    body = []
    sil = Shape()
    for y0, y1, hw in CORE:
        body.append(box((8 - hw, y0, Z_CORE[0]), (8 + hw, y1, Z_CORE[1]), "x"))
        sil.add(hull([(8 - hw - MARGIN, y0), (8 + hw + MARGIN, y0), (8 + hw + MARGIN, y1), (8 - hw - MARGIN, y1)]))
    body.append(diamond((8, TIP_Y), TIP_HALF, (0.0, 1.0), Z_TIPD))
    sil.add(hull([(8 - TIP_HALF - MARGIN, TIP_Y - 1.2), (8 + TIP_HALF + MARGIN, TIP_Y - 1.2), (8, APEX)]))
    major = [((8.0, 1.4), (8.0, 28.4))]
    minor = []
    veins = []
    for root, tip, w0, w1, spike, teeth in LOBES:
        w = (w0 + w1) / 2
        vec = (tip[0] - root[0], tip[1] - root[1])
        length = math.hypot(*vec)
        d = unit(*vec)
        n = perp(d)
        mid = add2(root, (vec, 0.5))
        parts = [plate(root, add2(root, (vec, 0.56)), w0, Z_LOBE),
                 plate(add2(root, (vec, 0.4)), tip, w1, (Z_LOBE[0] + 0.02, Z_LOBE[1] - 0.02)),
                 diamond(tip, w1 / 2, d, Z_TIPD)]
        body += parts + mirror(parts, "x", 8)
        polys = [taper(root, mid, w0, w0, MARGIN), taper(mid, tip, w0, w1, MARGIN, spike)]
        for f, sgn, h in teeth:
            hw = w0 / 2 + MARGIN if f <= 0.5 else (w0 + (w1 - w0) * (f - 0.5) / 0.5) / 2 + MARGIN
            polys.append(tooth_at(add2(root, (d, f * length), (n, sgn * (hw - 0.3))), d, n, sgn, h))
        start = (7.7, root[1] - 0.5)
        end = add2(root, ((tip[0] - root[0], tip[1] - root[1]), 0.9))
        vd = unit(end[0] - start[0], end[1] - start[1])
        vn = perp(vd)
        vlen = math.hypot(end[0] - start[0], end[1] - start[1])
        lets = []
        for frac in (0.4, 0.66):
            base = add2(start, (vd, vlen * frac))
            ln = 0.8 + w * 0.3
            for sgn in (1, -1):
                lets.append((base, add2(base, (vd, 0.7 * ln), (vn, 0.7 * ln * sgn))))
        for flip in (False, True):
            f = mx if flip else (lambda q: q)
            for poly in polys:
                sil.add(hull([f(q) for q in poly]))
            major.append((f(start), f(end)))
            minor += [(f(a), f(b)) for a, b in lets]
        veins.append(plate(start, end, 0.5, Z_VEIN, glow=5))
        veins += mirror(veins[-1], "x", 8)
    for yv in (10.6, 13.0, 15.4, 20.8, 25.2):
        for sgn in (1, -1):
            minor.append(((8 + 0.8 * sgn, yv), (8 + 2.1 * sgn, yv + 1.4)))

    shape = Shape([footprint(e) for e in body])
    major_l, minor_l = Lines(major), Lines(minor)
    # Specular glints on the honed bevel: the upper edge of each big lobe near its point,
    # and both edges of the tip spike.
    root, tip, w0, w1, spike, _ = LOBES[0]
    d = unit(tip[0] - root[0], tip[1] - root[1])
    lobe_glint = add2(tip, (perp(d), -1.1), (d, 0.9))
    glints = [lobe_glint, mx(lobe_glint), (8 - 1.3, TIP_Y + 1.8), (8 + 0.9, TIP_Y + 2.6)]

    def burn(x, y):
        t = (y - 3.0) / 26.0
        e = clamp(major_l.dist(x, y) / 3.2)
        return clamp(1.0 * t + 0.24 * e - 0.1)

    def body_paint(p, s):
        x, y = p[0], p[1]
        b = burn(x, y)
        if s not in ("north", "south"):
            return burn_colour(b, 2, x, y)
        kind = edge_kind(shape, x, y)
        if kind == "top":
            idx = 4
        elif kind:
            idx = 2
        elif major_l.dist(x, y) < 0.75 or minor_l.dist(x, y) < 0.28:
            idx = 4
        else:
            idx = 3
        return burn_colour(b, idx, x, y)

    def rim_paint(p, s):
        x, y = p[0], p[1]
        if not sil.inside(x, y):
            return None
        b = burn(x, y)
        kind = edge_kind(sil, x, y)
        if kind:
            if OUTLINE:
                return burn_colour(b, 1, x, y)
            return burn_colour(b, {"top": 6, "side": 5}.get(kind, 4), x, y)
        if OUTLINE and near_edge(sil, x, y):
            if any(math.hypot(x - gx, y - gy) < 0.8 for gx, gy in glints):
                return "#fff4d6"
            return burn_colour(b, 5, x, y)
        # The thin plate shows only outside the thick body: a lighter honed bevel.
        return burn_colour(b, 3 if shape.inside(x, y) else 4, x, y)

    bk.add(body, body_paint)
    x0, y0, x1, y1 = sil.bounds()
    x0, x1 = math.floor(x0 * 2) / 2, math.ceil(x1 * 2) / 2
    y0, y1 = 1.5, min(32.0, math.ceil(y1 * 2) / 2)
    rim_el = box((x0, y0, Z_RIM[0]), (x1, y1, Z_RIM[1]), "x", skip=("east", "west", "up", "down"), glow=RIM_GLOW)
    bk.add(rim_el, rim_paint)

    # Raised gilded veins and the midrib: two ridges around a glowing engraved channel.
    def vein(p, s):
        if s in ("north", "south"):
            return VEIN[5] if int(p[1] * PX) % 5 == 0 else VEIN[4]
        return VEIN[2]

    bk.add(veins, vein)
    ridges = [box((7.2, 1.4, Z_SPINE[0]), (7.7, 26.2, Z_SPINE[1]), "x"),
              box((8.3, 1.4, Z_SPINE[0]), (8.8, 26.2, Z_SPINE[1]), "x")]

    def ridge(p, s):
        if s in ("north", "south"):
            if int(math.floor(p[1] * PX)) % 6 == 0:
                return VEIN[2]
            return VEIN[5] if p[0] < 8 else VEIN[4]
        if s in ("up", "down"):
            return VEIN[3]
        inner = (s == "east") == (p[0] < 8)
        return VEIN[1] if inner else VEIN[3]

    bk.add(ridges, ridge)
    channel = box((7.7, 1.4, Z_CHANNEL[0]), (8.3, 26.2, Z_CHANNEL[1]), "x", glow=11)

    def ember(p, s):
        k = int(math.floor(p[1] * PX))
        return EMBER[5] if k % 7 == 3 else (EMBER[4] if k % 3 else EMBER[3])

    bk.add(channel, ember)
    cap = diamond((8, 26.2), 1.1, (0.0, 1.0), (7.0, 9.0))
    bk.add(cap, lambda p, s: VEIN[4] if s in ("north", "south") else VEIN[2])
    return body + [rim_el] + veins + ridges + [channel, cap]


# --------------------------------------------------------------------------------------
# The guard: two curling bronze maple leaves around a boss with an amber gem
# --------------------------------------------------------------------------------------

G_Z = (7.0, 9.0)
G_ZJ = (7.04, 8.96)
G_ZD = (7.08, 8.92)
G_RIM = (7.8, 8.2)
G_M = 0.7
G_STEM = ((6.6, 1.2), (4.2, 1.0))
G_PALM = (3.9, 1.0)
# Five lobes radiate from the palm of the left leaf: segments [(angle, length)], root
# width, tip width, spike. The terminal lobe bends upward at its end: the curl.
G_LOBES = [
    ([(186, 2.4), (146, 1.9)], 2.4, 1.1, 1.3),
    ([(118, 3.0)], 2.0, 1.0, 1.2),
    ([(238, 3.0)], 2.0, 1.0, 1.2),
    ([(64, 1.7)], 1.4, 0.8, 0.9),
    ([(296, 1.7)], 1.4, 0.8, 0.9),
]
G_PIVOT = (6.6, 1.2, 8.0)


def build_guard(bk):
    body = [plate(*G_STEM, 1.1, G_Z), diamond(G_PALM, 1.3, (0.0, 1.0), G_ZJ)]
    sil = Shape([taper(*G_STEM, 1.1, 1.1, G_M), ring(G_PALM, 1.3 + G_M)])
    veins = [G_STEM]
    for segs, w0, w1, spike in G_LOBES:
        pts = [G_PALM]
        for ang, ln in segs:
            a = math.radians(ang)
            pts.append(add2(pts[-1], ((math.cos(a), math.sin(a)), ln)))
        total, done = sum(ln for _, ln in segs), 0.0
        for i, (ang, ln) in enumerate(segs):
            p0, p1 = pts[i], pts[i + 1]
            wa = w0 + (w1 - w0) * done / total
            done += ln
            wb = w0 + (w1 - w0) * done / total
            last = i == len(segs) - 1
            vec = (p1[0] - p0[0], p1[1] - p0[1])
            body += [plate(p0, add2(p0, (vec, 0.58)), wa, G_Z),
                     plate(add2(p0, (vec, 0.42)), p1, wb, (G_Z[0] + 0.02, G_Z[1] - 0.02))]
            sil.add(taper(p0, p1, wa, wb, G_M, spike if last else 0.0))
            if i:
                d0 = unit(p0[0] - pts[i - 1][0], p0[1] - pts[i - 1][1])
                d1 = unit(p1[0] - p0[0], p1[1] - p0[1])
                body.append(diamond(p0, wa / 2 + 0.15, unit(d0[0] + d1[0], d0[1] + d1[1]), G_ZJ))
                sil.add(ring(p0, wa / 2 + G_M))
            veins.append((p0, add2(p0, ((p1[0] - p0[0], p1[1] - p0[1]), 0.8 if last else 1.0))))
        body.append(diamond(pts[-1], w1 / 2, unit(pts[-1][0] - pts[-2][0], pts[-1][1] - pts[-2][1]), G_ZD))
    shape = Shape([footprint(e) for e in body])
    vl = Lines(veins)

    def face(p, s):
        x, y = p[0], p[1]
        if s not in ("north", "south"):
            return BRONZE[2]
        kind = edge_kind(shape, x, y)
        if kind == "top":
            return BRONZE[5]
        if kind:
            return BRONZE[2]
        dv = vl.dist(x, y)
        if dv < 0.3:
            return GOLD[5]
        if dv < 0.8:
            return BRONZE[4]
        return BRONZE[3]

    def rim(p, s):
        x, y = p[0], p[1]
        if not sil.inside(x, y):
            return None
        kind = edge_kind(sil, x, y)
        if kind:
            return BRONZE[1] if OUTLINE else (GOLD[6] if kind == "top" else GOLD[4])
        if OUTLINE and near_edge(sil, x, y):
            return GOLD[5]
        return GOLD[4] if not shape.inside(x, y) else BRONZE[3]

    x0, y0, x1, y1 = sil.bounds()
    x0, y0 = math.floor(x0 * 2) / 2, math.floor(y0 * 2) / 2
    x1, y1 = math.ceil(x1 * 2) / 2, math.ceil(y1 * 2) / 2
    rim_el = box((x0, y0, G_RIM[0]), (x1, y1, G_RIM[1]), "x", skip=("east", "west", "up", "down"))

    left = body + [rim_el]
    right = mirror(left, "x", 8)
    flip = lambda f: (lambda p, s: f((16 - p[0], p[1], p[2]), s))  # noqa: E731
    bk.add(body, face)
    bk.add(rim_el, rim)
    bk.add(right[:-1], flip(face))
    bk.add(right[-1], flip(rim))
    turn(left, origin=G_PIVOT, x=0, y=-16, z=-6)
    turn(right, origin=(16 - G_PIVOT[0], G_PIVOT[1], G_PIVOT[2]), x=0, y=16, z=6)

    boss = diamond((8, 1.2), 2.3, (0.0, 1.0), (6.6, 9.4))

    def boss_paint(p, s):
        if s not in ("north", "south"):
            return BRONZE[2]
        d1 = abs(p[0] - 8) + abs(p[1] - 1.2)
        if d1 > 1.85:
            return BRONZE[5] if p[1] > 1.2 else BRONZE[1]
        if 1.3 < d1 < 1.65:
            return GOLD[4]
        return BRONZE[3]

    gem = diamond((8, 1.2), 1.05, (0.0, 1.0), (6.3, 9.7), glow=11)

    def gem_paint(p, s):
        x, y = p[0] - 8, p[1] - 1.2
        if s not in ("north", "south"):
            return NUT[2]
        d1 = abs(x) + abs(y)
        if d1 > 0.75:
            return NUT[1]
        if -0.5 < x < -0.1 and 0.1 < y < 0.5:
            return NUT[6]
        return NUT[4] if y > 0 else NUT[3]

    bk.add(boss, boss_paint)
    bk.add(gem, gem_paint)
    return left + right + [boss, gem]


# --------------------------------------------------------------------------------------
# Grip and pommel
# --------------------------------------------------------------------------------------

def build_grip(bk):
    grip = prism((8, -4.9, 8), 1.3, 8.6, "x")
    for e in grip:
        e["faces"].pop("up", None)
        e["faces"].pop("down", None)

    def bark(p, s):
        # Strips of dark oak bark wound in a helix: a shadowed seam under each wrap, a lit
        # upper lip, and short grain fissures in between.
        u = around(p, 1.3)
        q = (p[1] + u * 0.45) / 2.0
        k = math.floor(q)
        f = q - k
        if f < 0.2:
            return BARK[1]
        if f > 0.78:
            return BARK[4]
        cell = int(math.floor(u * 1.3))
        if (cell * 5 + k * 3) % 4 == 0 and 0.3 < f < 0.72:
            return BARK[1]
        return BARK[3] if f > 0.5 else BARK[2]

    bk.add(grip, bark, share=False)
    twine = prism((8, -5.7, 8), 1.5, 1.9, "x")

    def cord(p, s):
        # Turns of twisted twine: each turn lit on top and shaded below, with fibre twists.
        if s in ("up", "down"):
            return TWINE[2]
        u = around(p, 1.5)
        row = math.floor((p[1] - u * 0.1) * PX)
        twist = (math.floor(u * PX) + row // 2) % 3 == 0
        if row % 2:
            return TWINE[4] if twist else TWINE[5]
        return TWINE[1] if twist else TWINE[3]

    bk.add(twine, cord, share=False)

    def ferrule_paint(y0, y1):
        def paint(p, s):
            if s in ("up", "down"):
                return BRONZE[2]
            if p[1] > y1 - 0.5:
                return BRONZE[5]
            if p[1] < y0 + 0.5:
                return BRONZE[1]
            return GOLD[4] if int(math.floor(around(p, 1.75) * PX)) % 3 == 0 else BRONZE[3]
        return paint

    top = prism((8, -0.9, 8), 1.75, 1.2, "x")
    low = prism((8, -9.3, 8), 1.75, 1.0, "x")
    bk.add(top, ferrule_paint(-1.5, -0.3), share=False)
    bk.add(low, ferrule_paint(-9.8, -8.8), share=False)
    return grip + twine + top + low


def build_pommel(bk):
    """The glowing amber acorn: a scaly cup (dome, body, flared rim) over a tapering nut."""

    def scales(r):
        def paint(p, s):
            if s == "up":
                return CAP[3]
            if s == "down":
                return CAP[1]
            # Shingled scales, 3 texels wide and 2 tall, each row offset by half a scale.
            u = around(p, r)
            row = math.floor(p[1])
            fy = p[1] - row
            cu = u / 1.5 + (0.5 if row % 2 else 0.0)
            fu = cu - math.floor(cu)
            if fu < 0.3:
                return CAP[1]
            if fy > 0.5:
                return CAP[5] if fu < 0.65 else CAP[4]
            return CAP[3] if fu < 0.65 else CAP[2]
        return paint

    cup = []
    for r, y1, y0 in ((1.8, -9.4, -10.3), (2.35, -10.2, -11.7)):
        part = prism((8, (y0 + y1) / 2, 8), r, y1 - y0, "x")
        bk.add(part, scales(r), share=False)
        cup += part
    rim = prism((8, -11.95, 8), 2.6, 0.7, "x")
    bk.add(rim, lambda p, s: CAP[5] if s == "up" else (CAP[1] if s == "down" else
                                                       (CAP[3] if p[1] > -11.95 else CAP[2])), share=False)

    def amber(r, top, bottom):
        def paint(p, s):
            if s == "down":
                return NUT[2]
            if s == "up":
                return NUT[3]
            u = around(p, r)
            if p[1] > -12.25:
                return NUT[2]                                  # shadow under the cup's rim
            if abs(u - 1.1 * r) < 0.3 * r:
                return NUT[6] if p[1] > -14.0 else NUT[5]      # glossy highlight streak
            if abs(u - 1.1 * r) < 0.6 * r:
                return NUT[5]
            if int(math.floor(u * PX)) % 5 == 0 and p[1] > -14.8:
                return NUT[3]                                  # faint lengthwise stripes
            return NUT[4] if p[1] > (top + bottom) / 2 else NUT[3]
        return paint

    nut = []
    for r, y1, y0 in ((2.1, -11.9, -13.8), (1.8, -13.7, -14.7), (1.3, -14.6, -15.3), (0.7, -15.2, -15.75)):
        part = prism((8, (y0 + y1) / 2, 8), r, y1 - y0, "x", glow=12)
        bk.add(part, amber(r, y1, y0), share=False)
        nut += part
    point = box((7.8, -16.0, 7.8), (8.2, -15.7, 8.2), "x", glow=12)
    bk.add(point, lambda p, s: NUT[2], share=False)
    return cup + rim + nut + [point]


# --------------------------------------------------------------------------------------
# Falling leaves
# --------------------------------------------------------------------------------------

LEAF_POLY = [(0.0, 1.0), (0.14, 0.6), (0.34, 0.74), (0.27, 0.36), (0.64, 0.62), (0.56, 0.3), (0.98, 0.28),
             (0.64, 0.04), (0.76, -0.22), (0.32, -0.16), (0.12, -0.42)]
LEAF_POLY = LEAF_POLY + [(-x, y) for x, y in reversed(LEAF_POLY)]
LEAVES = [  # centre, euler rotation, palette
    ((-1.4, 21.0, 7.2), (20, 35, 25), GOLD),
    ((17.6, 25.6, 9.0), (-15, -30, -35), CRIMSON),
    ((0.8, 28.5, 8.4), (10, 50, 60), ORANGE),
]
LEAF_PX = 13


def leaf_sprite(pal):
    """A small maple leaf, rasterised from LEAF_POLY with 4x4 supersampling."""
    size = LEAF_PX
    img = canvas(16)
    px = img.load()

    def inside(x, y):
        c = False
        n = len(LEAF_POLY)
        for i in range(n):
            (x1, y1), (x2, y2) = LEAF_POLY[i], LEAF_POLY[(i + 1) % n]
            if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
                c = not c
        return c

    def to_px(x, y):
        return round((x + 1) / 2 * size - 0.5), round((1 - y) / 2 * size - 0.5)

    solid = [[sum(inside((c + (i + 0.5) / 4) / size * 2 - 1, 1 - (r + (j + 0.5) / 4) / size * 2)
                  for i in range(4) for j in range(4)) >= 7 for c in range(size)] for r in range(size)]
    mid = size // 2
    bottom = max(r for r in range(size) if solid[r][mid])
    stem = {(mid, r) for r in range(bottom + 1, min(size, bottom + 3))}
    veins = set()
    base = (mid, bottom - 1)
    for tx, ty in ((0.0, 1.0), (0.64, 0.62), (-0.64, 0.62), (0.98, 0.28), (-0.98, 0.28)):
        ex, ey = to_px(tx * 0.8, ty * 0.8 + (1 - 0.8) * -0.2)
        steps = max(abs(ex - base[0]), abs(ey - base[1]))
        for k in range(steps + 1):
            veins.add((round(base[0] + (ex - base[0]) * k / steps), round(base[1] + (ey - base[1]) * k / steps)))
    for r in range(size):
        for c in range(size):
            if (c, r) in stem:
                px[c, r] = rgba(pal[2])
                continue
            if not solid[r][c]:
                continue
            edge = any(not (0 <= r + dr < size and 0 <= c + dc < size and solid[r + dr][c + dc])
                       for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                px[c, r] = rgba(pal[1] if c >= mid else pal[2])
            elif (c, r) in veins:
                px[c, r] = rgba(pal[5])
            else:
                px[c, r] = rgba(pal[4] if c < mid else pal[3])
    return img


def build_leaves():
    out = []
    half = LEAF_PX / 4
    for n, (c, rot, pal) in enumerate(LEAVES):
        u0, v0 = (n % 2) * 8.0, (n // 2) * 8.0
        uv_s = [u0, v0, u0 + LEAF_PX / 2, v0 + LEAF_PX / 2]
        uv_n = [uv_s[2], v0, u0, uv_s[3]]
        e = box((c[0] - half, c[1] - half, c[2]), (c[0] + half, c[1] + half, c[2]), "leaves",
                faces={"south": ("leaves", uv_s), "north": ("leaves", uv_n)},
                skip=("east", "west", "up", "down"), glow=9, shade=False)
        turn(e, origin=c, x=rot[0], y=rot[1], z=rot[2])
        out.append(e)
    return out


def paint_leaves():
    sheet = canvas(32)
    for n, (c, rot, pal) in enumerate(LEAVES):
        sheet.paste(leaf_sprite(pal), ((n % 2) * 16, (n // 2) * 16))
    save(sheet, "leaves")


# --------------------------------------------------------------------------------------
# Module contract
# --------------------------------------------------------------------------------------

GRIP = (8, -3.2, 8)
SIZE = 0.88


def build():
    bk = Baker("sword")
    parts = build_blade(bk) + build_guard(bk) + build_grip(bk) + build_pommel(bk)
    bk.pack()
    return parts, build_leaves(), bk


def textures() -> None:
    parts, leaves, bk = build()
    bk.paint()
    paint_leaves()


def models() -> dict:
    parts, leaves, bk = build()
    main = display("sword", parts + leaves, grip=GRIP, size=SIZE)
    # First person: stood up at the right of the screen, leaf face turned to the viewer,
    # so the whole burning blade shows and the crosshair stays clear.
    main["firstperson_righthand"] = place({"y": (-0.08, 0.95, -0.35), "z": (-0.8, 0.15, 0.55)}, GRIP,
                                          (0.58, -0.52, -0.82), 0.42, pose=None)
    # The inventory icon leaves out the drifting leaves so the sword itself reads bigger.
    icon = display("sword", parts, grip=GRIP, size=SIZE)
    return {"main": model(parts + leaves, main), "gui": model(parts, icon)}
