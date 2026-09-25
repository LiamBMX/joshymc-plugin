"""Falling Leaf: the September Exclusive elytra.

Worn, the wings are overlapping maple and oak leaves that run from harvest gold at the
shoulders through amber and burnt orange to crimson maple tips. Every leaf is cut out,
so the wing edge is ragged and leafy, and the veins are gilded. A gilded oak twig forms
each leading edge. A woven-vine chest harness with an acorn-and-leaf clasp and leafy
epaulettes holds them on. The item is the folded pair of leaf wings on their vine yoke,
with the gilded acorn clasp at the centre and a few leaves falling away.
"""
from __future__ import annotations

import math
import random

from PIL import Image

from art.kit import (HUMANOID, WINGS, bar, box, canvas, display, mirror, model, place, prism, ramp, region, rgba,
                     save, save_layer, shade, turn)

ID = "falling_leaf"
NAME = "Falling Leaf"
KIND = "elytra"

# --------------------------------------------------------------------------------------
# Palette: one hue-shifted ramp per material (darkest -> lightest)
# --------------------------------------------------------------------------------------

GOLD = ramp("#d99a22", 6, 0.62)
AMBER = ramp("#e28a2a", 6, 0.62)
ORANGE = ramp("#cd5a25", 6, 0.62)
SCARLET = ramp("#b53027", 6, 0.62)
CRIMSON = ramp("#8f172c", 6, 0.62)
FAMILIES = (GOLD, AMBER, ORANGE, SCARLET, CRIMSON)
BARK = ramp("#84532e", 6, 0.72)
BRONZE = ramp("#c28b3b", 6, 0.8)
VINE = ramp("#4f3a1c", 6, 0.6)
OLIVE = ramp("#6b6624", 6, 0.6)
GEM = ramp("#f09a28", 6, 0.72)
WITHER = ramp("#8a6844", 6, 0.6)
# Polished gilt for the metal leaves: the bright end of the bronze ramp plus a white-gold glint.
GILT = (BRONZE[1], BRONZE[2], BRONZE[3], BRONZE[4], BRONZE[5], "#fff4d0")

# --------------------------------------------------------------------------------------
# Leaf shapes, in leaf units: the leaf points up (+y), veins meet at (0, 0)
# --------------------------------------------------------------------------------------

# Maple outline as (angle from the tip in degrees, radius), mirrored for the left half.
MAPLE = ((0, 1.0), (8, 0.66), (15, 0.79), (25, 0.46), (33, 0.66), (42, 0.62), (53, 0.97),
         (63, 0.66), (73, 0.74), (87, 0.42), (99, 0.56), (106, 0.51), (117, 0.67), (133, 0.36),
         (152, 0.22), (180, 0.15))
MAPLE_VEINS = (0.0, 53.0, -53.0, 117.0, -117.0)
# Oak lobes as (height, sideways offset, radius) on each side of the midrib.
OAK_LOBES = ((0.50, 0.25, 0.22), (0.08, 0.34, 0.25), (-0.33, 0.27, 0.2))
LIGHT = (-0.5, 0.866)  # light from the upper left ("up" is +y here)


def _profile_r(profile, a: float) -> float:
    a = abs(a)
    for (a0, r0), (a1, r1) in zip(profile, profile[1:]):
        if a <= a1:
            return r0 + (r1 - r0) * (a - a0) / max(1e-6, a1 - a0)
    return profile[-1][1]


class _Leaf:
    """One leaf silhouette with a little per-leaf jitter so no two are identical."""

    def __init__(self, kind: str, seed: int):
        rng = random.Random(seed)
        self.kind = kind
        if kind == "maple":
            def jitter(profile):
                out = []
                for i, (a, r) in enumerate(profile):
                    if 0 < i < len(profile) - 1:
                        a += rng.uniform(-1.5, 1.5)
                        r *= rng.uniform(0.94, 1.06)
                    out.append((a, r))
                return out
            self.right, self.left = jitter(MAPLE), jitter(MAPLE)
            self.stem = ((0.0, -0.05), (0.0, -0.98))
        else:
            self.lobes = [(y + rng.uniform(-0.03, 0.03), x * rng.uniform(0.94, 1.06), r * rng.uniform(0.92, 1.08))
                          for y, x, r in OAK_LOBES]
            self.stem = ((0.0, -0.62), (0.0, -1.0))

    def inside(self, lx: float, ly: float):
        """Radial fraction (0 centre .. 1 rim) if the point is on the blade, else None."""
        if self.kind == "maple":
            r = math.hypot(lx, ly)
            a = math.degrees(math.atan2(lx, ly))
            limit = _profile_r(self.right if a >= 0 else self.left, a)
            return min(1.0, r) if r <= limit else None
        hit = (lx / 0.17) ** 2 + ((ly - 0.12) / 0.84) ** 2 <= 1 or lx * lx + (ly - 0.78) ** 2 <= 0.21 ** 2
        if not hit:
            for y, x, r in self.lobes:
                if (abs(lx) - x) ** 2 + (ly - y) ** 2 <= r * r:
                    hit = True
                    break
        if not hit:
            return None
        return min(1.0, math.sqrt((lx / 0.58) ** 2 + ((ly - 0.1) / 0.92) ** 2))

    def veins(self, big: bool):
        """Vein segments (leaf units)."""
        if self.kind == "maple":
            out = []
            for v in MAPLE_VEINS:
                prof = self.right if v >= 0 else self.left
                length = _profile_r(prof, v) * 0.78
                out.append(((0.0, 0.0), (length * math.sin(math.radians(v)), length * math.cos(math.radians(v)))))
            if big:
                for v, side in ((0.0, 15.0), (0.0, -15.0), (53.0, 33.0), (53.0, 73.0), (-53.0, -33.0),
                                (-53.0, -73.0)):
                    base = 0.44
                    p0 = (base * math.sin(math.radians(v)), base * math.cos(math.radians(v)))
                    prof = self.right if side >= 0 else self.left
                    r = _profile_r(prof, side) * 0.78
                    out.append((p0, (r * math.sin(math.radians(side)), r * math.cos(math.radians(side)))))
            return out
        out = [((0.0, -0.62), (0.0, 0.84))]
        if big:
            for y, x, _ in self.lobes:
                for s in (-1, 1):
                    out.append(((0.0, y - 0.14), (s * x * 1.15, y + 0.02)))
        return out

    def half_normal(self, lx: float, ly: float):
        """Outward direction of the half of the leaf this point is on (ridge-fold shading)."""
        if self.kind == "oak":
            return (1.0 if lx >= 0 else -1.0, 0.0)
        a = math.degrees(math.atan2(lx, ly))
        v = min(MAPLE_VEINS, key=lambda vv: abs(((a - vv + 180) % 360) - 180))
        d = ((a - v + 180) % 360) - 180
        rv = math.radians(v)
        return (math.cos(rv), -math.sin(rv)) if d >= 0 else (-math.cos(rv), math.sin(rv))


def _line(p0, p1):
    """Bresenham pixels between two canvas points."""
    x0, y0 = int(math.floor(p0[0])), int(math.floor(p0[1]))
    x1, y1 = int(math.floor(p1[0])), int(math.floor(p1[1]))
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    out = []
    while True:
        out.append((x0, y0))
        if x0 == x1 and y0 == y1:
            return out
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


_SHADE: dict = {}


def _shaded(c, amount):
    """Cached shade() of a hex or RGBA colour, as RGBA."""
    key = (tuple(rgba(c)), round(amount, 3))
    if key not in _SHADE:
        _SHADE[key] = rgba(shade(c, amount)) if amount else rgba(c)
    return _SHADE[key]


def stamp(img: Image.Image, kind: str, cx: float, cy: float, size: float, angle: float, fam: int, seed: int,
          clip=None, shadow: bool = True, under: bool = False, flip: bool = False, families=FAMILIES,
          vein_colour=None, stem: bool = True, dim: float = 0.0, rim_tones=(1, 0), cast: float = -0.3) -> None:
    """Paint one leaf. (cx, cy) is where the veins meet, size the tip radius in pixels,
    angle the pointing direction in degrees clockwise from up, fam the colour family."""
    leaf = _Leaf(kind, seed)
    w, h = img.size
    px = img.load()
    a = math.radians(angle)
    ca, sa = math.cos(a), math.sin(a)
    light = (LIGHT[0] * ca - LIGHT[1] * sa, LIGHT[0] * sa + LIGHT[1] * ca)
    if flip:
        light = (-light[0], light[1])
    x0, y0, x1, y1 = clip or (0, 0, w - 1, h - 1)
    reach = size * 1.08 + 1
    bx0, bx1 = max(x0, int(cx - reach)), min(x1, int(cx + reach) + 1)
    by0, by1 = max(y0, int(cy - reach)), min(y1, int(cy + reach) + 1)

    def to_canvas(lx, ly):
        if flip:
            lx = -lx
        return (cx + (lx * ca + ly * sa) * size, cy - (-lx * sa + ly * ca) * size)

    blade = {}
    for y in range(by0, by1 + 1):
        for x in range(bx0, bx1 + 1):
            wx, wy = x + 0.5 - cx, -(y + 0.5 - cy)
            lx = (wx * ca - wy * sa) / size
            ly = (wx * sa + wy * ca) / size
            if flip:
                lx = -lx
            rf = leaf.inside(lx, ly)
            if rf is not None:
                blade[(x, y)] = (lx, ly, rf)
    stems = set()
    if stem:
        for p in _line(to_canvas(*leaf.stem[0]), to_canvas(*leaf.stem[1])):
            if x0 <= p[0] <= x1 and y0 <= p[1] <= y1 and p not in blade:
                stems.add(p)
    body = set(blade) | stems
    rim = {p for p in blade if any((p[0] + dx, p[1] + dy) not in blade for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))}
    veins = set()
    for s0, s1 in leaf.veins(size >= 10.5):
        for p in _line(to_canvas(*s0), to_canvas(*s1)):
            if p in blade and p not in rim:
                veins.add(p)

    if shadow:
        off = max(1, round(size / 8))
        for (x, y) in body:
            q = (x + off, y + off)
            if q in body or not (x0 <= q[0] <= x1 and y0 <= q[1] <= y1):
                continue
            c = px[q]
            if c[3]:
                px[q] = _shaded(c, cast)

    f_mid = families[fam]
    f_tip = families[min(len(families) - 1, fam + 1)]
    f_core = families[max(0, fam - 1)]
    fine = size >= 7.5  # small leaves get a simpler, cleaner shading
    if vein_colour is None:
        vein_colour = GOLD[5] if fam == 0 else GOLD[4]
    for (x, y) in stems:
        px[x, y] = _shaded(f_mid[1] if not under else f_mid[3], -dim)
    for (x, y), (lx, ly, rf) in blade.items():
        n = leaf.half_normal(lx, ly)
        lit = n[0] * light[0] + n[1] * light[1] > 0
        fam_c = f_tip if rf > 0.74 else (f_core if (rf < 0.24 and fine) else f_mid)
        if (x, y) in rim:
            colour = fam_c[rim_tones[0]] if lit else fam_c[rim_tones[1]]
        elif (x, y) in veins:
            colour = shade(vein_colour, 0.2) if under else vein_colour
        else:
            tone = 3 if lit else 2
            if fine and lit and any((x + dx, y + dy) not in blade for dx, dy in ((-1, 0), (0, -1))):
                tone = 4
            colour = fam_c[tone]
            if under:
                colour = shade(fam_c[min(5, tone + 1)], 0.1)
        px[x, y] = _shaded(colour, -dim)
    if fine and not under and dim == 0 and seed % 3 == 0:
        gx, gy = int(math.floor(cx)), int(math.floor(cy))
        if (gx, gy) in blade and (gx, gy) not in rim:
            px[gx, gy] = rgba("#fff3cf")


# --------------------------------------------------------------------------------------
# Painting helpers for branches, vines, plates and gems
# --------------------------------------------------------------------------------------

def _polyline_field(pts, x, y):
    """(distance, signed distance, segment index, t, arc length) to a polyline."""
    best = None
    acc = 0.0
    for k in range(len(pts) - 1):
        (ax, ay), (bx, by) = pts[k], pts[k + 1]
        vx, vy = bx - ax, by - ay
        seg = math.hypot(vx, vy)
        t = max(0.0, min(1.0, ((x - ax) * vx + (y - ay) * vy) / (seg * seg)))
        qx, qy = ax + vx * t, ay + vy * t
        d = math.hypot(x - qx, y - qy)
        cross = vx * (y - ay) - vy * (x - ax)
        if best is None or d < best[0]:
            best = (d, d if cross > 0 else -d, k, t, acc + t * seg)
        acc += seg
    return best


def paint_branch(img, pts, widths, clip, bands=(), knots=(), seed=3):
    """A bark twig along a polyline: lit on its left, ring grooves, knots and gilded bands.
    bands and knots are arc lengths in pixels."""
    px = img.load()
    x0, y0, x1, y1 = clip
    rng = random.Random(seed)
    marks, s = [], 0
    for _ in range(64):
        s += rng.randint(3, 6)
        marks.append(s)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            d, sd, k, t, s = _polyline_field(pts, x + 0.5, y + 0.5)
            wdt = widths[k] + (widths[k + 1] - widths[k]) * t
            band = any(abs(s - b) <= 1.6 for b in bands)
            half = wdt / 2 + (0.9 if band else 0)
            if d > half:
                continue
            u = sd / max(half, 0.01)
            if band:
                if half - d < 0.9:
                    c = BRONZE[0] if u < 0 else BRONZE[1]
                else:
                    c = BRONZE[5] if u > 0.35 else (BRONZE[4] if u > -0.2 else BRONZE[2])
                    if any(abs(s - b) <= 0.5 for b in bands) and u > 0.2:
                        c = "#fff4d0"
                px[x, y] = rgba(c)
                continue
            if half - d < 0.85:
                c = BARK[0] if u < 0.3 else BARK[1]
            else:
                c = BARK[4] if u > 0.45 else (BARK[3] if u > -0.1 else BARK[2])
                if any(abs(s - m) < 0.6 for m in marks) and u < 0.5:
                    c = BARK[1]
                for kn in knots:
                    dist = abs(s - kn)
                    if dist < 1.2 and abs(u) < 0.5:
                        c = BARK[0]
                    elif dist < 2.0 and abs(u) < 0.8:
                        c = BARK[4]
            px[x, y] = rgba(c)


def paint_rope(img, pts, width, clip, sprout=None, seed=5):
    """A woven vine strap along a polyline (two twisted strands), optionally with leaves.
    sprout = (spacing px, leaf size px, families to pick from)."""
    px = img.load()
    x0, y0, x1, y1 = clip
    half = width / 2
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            d, sd, k, t, s = _polyline_field(pts, x + 0.5, y + 0.5)
            if d > half:
                continue
            u = sd / half
            if half - d < 0.75:
                c = VINE[0] if u < 0 else VINE[1]
            else:
                # two vines twisted together: diagonal bands alternate brown and olive
                twist = (s * 0.9 + u * half * 1.3) / 3.2
                band = int(math.floor(twist)) % 2
                frac = twist - math.floor(twist)
                ramp_ = VINE if band == 0 else OLIVE
                c = ramp_[4] if frac < 0.28 else (ramp_[3] if frac < 0.72 else ramp_[1])
            px[x, y] = rgba(c)
    if not sprout:
        return
    rng = random.Random(seed)
    step, size, fams = sprout
    total = sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts, pts[1:]))
    s, side = step * 0.5, 1
    while s < total - size:
        acc = 0.0
        for a, b in zip(pts, pts[1:]):
            seg = math.hypot(b[0] - a[0], b[1] - a[1])
            if acc + seg >= s:
                t = (s - acc) / seg
                p = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
                dirx, diry = (b[0] - a[0]) / seg, (b[1] - a[1]) / seg
                break
            acc += seg
        nx, ny = -diry * side, dirx * side
        cx, cy = p[0] + nx * (half + size * 0.45), p[1] + ny * (half + size * 0.45)
        ang = math.degrees(math.atan2(nx + dirx * 0.5, -(ny + diry * 0.5)))
        stamp(img, "oak" if rng.random() < 0.5 else "maple", cx, cy, size, ang, rng.choice(fams),
              seed=rng.randint(0, 9999), clip=clip, shadow=True, stem=False)
        side = -side
        s += step


def paint_disc(img, cx, cy, r, ramp_, clip=None):
    """A bevelled round plate lit from the upper left."""
    px = img.load()
    for y in range(int(cy - r - 1), int(cy + r + 2)):
        for x in range(int(cx - r - 1), int(cx + r + 2)):
            if clip and not (clip[0] <= x <= clip[2] and clip[1] <= y <= clip[3]):
                continue
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            if d > r:
                continue
            lit = (-dx - dy) / max(d, 0.01)
            if r - d < 1.0:
                c = ramp_[0] if lit < 0.2 else ramp_[2]
            elif r - d < 2.0:
                c = ramp_[5] if lit > 0.3 else (ramp_[1] if lit < -0.3 else ramp_[3])
            else:
                c = ramp_[3] if (dx + dy) > 0 else ramp_[4]
            px[x, y] = rgba(c)


def paint_gem(img, cx, cy, r, clip=None):
    """A small cut amber gem with a glint."""
    px = img.load()
    for y in range(int(cy - r - 1), int(cy + r + 2)):
        for x in range(int(cx - r - 1), int(cx + r + 2)):
            if clip and not (clip[0] <= x <= clip[2] and clip[1] <= y <= clip[3]):
                continue
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = abs(dx) + abs(dy)
            if d > r:
                continue
            if r - d < 0.9:
                c = GEM[0]
            elif dx + dy < -r * 0.3:
                c = GEM[5]
            elif dx + dy > r * 0.3:
                c = GEM[2]
            else:
                c = GEM[3]
            px[x, y] = rgba(c)
    gx, gy = int(cx - r * 0.35), int(cy - r * 0.35)
    if not clip or (clip[0] <= gx <= clip[2] and clip[1] <= gy <= clip[3]):
        px[gx, gy] = rgba("#fffbe8")


def _rows(spec, seed, lean_of):
    """Expand rows of (y, xs, size, families, kinds) into leaves, bottom row first."""
    rng = random.Random(seed)
    out = []
    for y, xs, size, fams, kinds in spec:
        for i, x in enumerate(xs):
            fam = fams[i % len(fams)] if isinstance(fams, tuple) else fams
            kind = kinds[i % len(kinds)] if isinstance(kinds, tuple) else kinds
            jx, jy = rng.uniform(-0.15, 0.15), rng.uniform(-0.15, 0.15)
            out.append((kind, x + jx, y + jy, size * rng.uniform(0.95, 1.05), fam,
                        lean_of(x) + rng.uniform(-6, 6), rng.randint(0, 99999)))
    return out


# --------------------------------------------------------------------------------------
# Worn wings (256x128): the outer face is painted as the left wing seen from behind
# (column 0 = leading edge / shoulder side, row 0 = shoulder, last row = tip)
# --------------------------------------------------------------------------------------

S = 4  # 4x layout: 256x128


def _interp(pts, y):
    """Piecewise-linear x at height y through [(y, x), ...] sorted by y."""
    if y <= pts[0][0]:
        return pts[0][1]
    for (ya, xa), (yb, xb) in zip(pts, pts[1:]):
        if y <= yb:
            return xa + (xb - xa) * (y - ya) / (yb - ya)
    return pts[-1][1]


def envelope_leaves(left, right, rows, spacing, size_of, fam_of, kind_of, lean_of, seed, bottom=None):
    """Leaves in staggered rows between two edge curves (they bound the leaf centres).
    rows = [(y, offset)], offset 0 puts leaves on both edges, 0.5 between them.
    Returned bottom row first, so painting in order lets upper leaves overlap lower ones."""
    rng = random.Random(seed)
    out = []
    for y, off in rows:
        xl, xr = _interp(left, y), _interp(right, y)
        n = max(1, round((xr - xl) / spacing))
        count = n + 1 if off == 0 else n
        for i in range(count):
            x = xl + (xr - xl) * (i + off) / n + rng.uniform(-0.18, 0.18)
            yy = y + rng.uniform(-0.2, 0.2)
            size = size_of(yy) * rng.uniform(0.94, 1.06)
            if bottom is not None:
                size = min(size, bottom - yy)
            out.append((kind_of(x, yy, rng), x, yy, size, fam_of(yy, rng), lean_of(x) + rng.uniform(-7, 7),
                        rng.randint(0, 99999)))
    out.sort(key=lambda leaf_: -leaf_[2])
    return out


def _wing_lean(x):
    return 176 - 18 * (x / 10)


def _wing_size(y):
    return 1.9 + 0.85 * min(1.0, max(0.0, y) / 17.0)


def _wing_fam(y, rng):
    return max(0, min(4, int(round(y / 17.5 * 4 + rng.uniform(-0.45, 0.45)))))


def _wing_kind(x, y, rng):
    return "oak" if rng.random() < (0.75 if y < 5 else (0.45 if y < 11 else 0.12)) else "maple"


# Leaf-centre envelope of the worn wing (units of the 10x20 outer face): the wing hugs the
# spine at the shoulders and tapers to a tip on the outside, so the pair opens into a V.
WING_LEFT = ((0.0, 1.8), (13.5, 1.8), (16.0, 2.1), (17.5, 2.5))
WING_RIGHT = ((0.0, 5.3), (4.0, 5.9), (9.0, 6.2), (12.0, 6.3), (14.0, 5.6), (15.5, 4.6), (17.2, 2.7))
WING_FRONT = ((1.1, 0.0), (3.4, 0.5), (5.7, 0.0), (8.0, 0.5), (10.3, 0.0), (12.6, 0.5), (14.9, 0.0),
              (17.0, 0.5))
WING_BACK = ((2.25, 0.25), (4.55, 0.75), (6.85, 0.25), (9.15, 0.75), (11.45, 0.25), (13.75, 0.75),
             (15.9, 0.25))
# The leading-edge twig in units (x, y) with widths; it covers column 0 down to y = 14.
TWIG = ((0.55, 0.0), (0.58, 4.0), (0.6, 8.0), (0.56, 11.5), (0.5, 14.0), (0.66, 15.9))
TWIG_W = (1.2, 1.12, 1.04, 0.96, 0.8, 0.45)
# Two leaves drifting away from the trailing edge: (kind, x, y, size, family, angle, seed).
DRIFTERS = (("maple", 6.5, 18.3, 1.35, 4, 128, 61), ("oak", 7.0, 16.2, 0.95, 2, 212, 62))
# Thin "finger" twigs fanning from the leading edge into the leaves, like wing bones.
FINGERS = (((0.8, 4.6), (3.6, 8.6), (4.8, 11.2)), ((0.8, 9.4), (2.4, 13.6), (3.0, 16.2)))


def paint_wing_face(s: int = S):
    """The outer face (left wing seen from behind) and its twig layer."""
    img = canvas(10 * s, 20 * s)
    clip = (0, 0, 10 * s - 1, 20 * s - 1)
    common = dict(size_of=_wing_size, fam_of=_wing_fam, kind_of=_wing_kind, lean_of=_wing_lean, bottom=19.8)
    back = envelope_leaves(WING_LEFT, WING_RIGHT, WING_BACK, 2.5, seed=202, **common)
    front = envelope_leaves(WING_LEFT, WING_RIGHT, WING_FRONT, 2.5, seed=101, **common)
    for kind, x, y, size, fam, ang, seed in back:
        stamp(img, kind, x * s, y * s, size * s * 1.04, ang, fam, seed, clip=clip, stem=False, dim=0.36)
    for finger in FINGERS:
        pts = [(x * s, y * s) for x, y in finger]
        paint_branch(img, pts, [0.5 * s, 0.4 * s, 0.3 * s], clip, seed=11)
    for kind, x, y, size, fam, ang, seed in front:
        stamp(img, kind, x * s, y * s, size * s, ang, fam, seed, clip=clip, stem=False)
    for kind, x, y, size, fam, ang, seed in DRIFTERS:
        stamp(img, kind, x * s, y * s, size * s, ang, fam, seed, clip=clip, stem=True)
    twig = canvas(10 * s, 20 * s)
    pts = [(x * s, y * s) for x, y in TWIG]
    paint_branch(twig, pts, [w * s for w in TWIG_W], clip, bands=(4.2 * s, 9.6 * s), knots=(6.8 * s, 12.4 * s),
                 seed=7)
    # gilded shoulder mount with an amber gem
    paint_disc(twig, 1.1 * s, 1.05 * s, 1.1 * s, BRONZE, clip=clip)
    paint_gem(twig, 1.1 * s, 1.05 * s, 0.58 * s, clip=clip)
    img.alpha_composite(twig)
    return img, twig


def paint_wings() -> Image.Image:
    img = canvas(64 * S, 32 * S)
    face, twig = paint_wing_face()
    ox, oy = region(WINGS, "outer", S)[:2]
    img.paste(face, (ox, oy))
    # Thickness: the east edge strip beside the outer face follows the twig, and the top
    # face carries the shoulder row. Each strip is one unit deep (2 px of the 4x layout).
    tpx, fpx, px = twig.load(), face.load(), img.load()
    ex0, ey0, ex1, ey1 = region(WINGS, "edge_inner", S)
    for j in range(20 * S):
        c = tpx[1, j]
        if c[3]:
            for k in range(2):
                px[ex1 - k, ey0 + j] = _shaded(c, -0.12 * (k + 1))
    tx0, ty0, tx1, ty1 = region(WINGS, "top", S)
    for i in range(10 * S):
        c = fpx[i, 0]
        if c[3]:
            for k in range(2):
                px[tx1 - i, ty0 + k] = _shaded(c, 0.08 - 0.1 * k)
    return img


# --------------------------------------------------------------------------------------
# Chest harness (256x128, humanoid layer)
# --------------------------------------------------------------------------------------

ACORN = (
    ".....ss......",
    "....ss.......",
    "..bBBBBBBBb..",
    ".bBHhHhHhHBb.",
    "bBhHhHhHhHhBb",
    "bdBhBhBhBhBdb",
    ".bdddddddddb.",
    "..nNNNNNNNn..",
    ".nNWHNNNNNMn.",
    ".nNHNNNNNNMn.",
    ".nNHNNNNNNMn.",
    ".nNNNNNNNMMn.",
    "..nNNNNNNMn..",
    "...nNNNNMn...",
    "....nNNMn....",
    ".....nnn.....",
)


def paint_acorn(img, left, top, clip=None):
    cap = {"s": BARK[1], "b": BRONZE[0], "B": BRONZE[3], "H": BRONZE[5], "h": BRONZE[4], "d": BRONZE[1]}
    nut = {"n": GEM[0], "N": GEM[3], "H": GEM[5], "W": "#fffbe8", "M": GEM[2]}
    px = img.load()
    for j, row in enumerate(ACORN):
        for i, ch in enumerate(row):
            if ch == ".":
                continue
            x, y = left + i, top + j
            if clip and not (clip[0] <= x <= clip[2] and clip[1] <= y <= clip[3]):
                continue
            px[x, y] = rgba((nut if j >= 7 else cap)[ch])


def paint_harness() -> Image.Image:
    img = canvas(64 * S, 32 * S)
    front = region(HUMANOID, "body_front", S)
    back = region(HUMANOID, "body_back", S)
    top = region(HUMANOID, "body_top", S)
    right = region(HUMANOID, "body_right", S)
    left = region(HUMANOID, "body_left", S)
    strap = 1.35 * S
    leafy = (13, 5.0, (0, 1, 2, 3))

    # Front: shoulder straps meet at the clasp, lower straps run out to the sides.
    fx, fy = front[:2]
    clasp = (fx + 16, fy + 17)
    for sx in (7, 24):
        paint_rope(img, [(fx + sx, fy - 1), (fx + sx + (16 - sx) * 0.35, fy + 8), clasp], strap, front,
                   sprout=leafy, seed=sx)
    for ex in (-2, 34):
        paint_rope(img, [clasp, (fx + ex, fy + 40)], strap, front, sprout=leafy, seed=ex + 50)
    # Over the shoulders (body_top: top rows are the back, bottom rows the front).
    tx, ty = top[:2]
    for sx in (7, 24):
        paint_rope(img, [(tx + sx, ty - 1), (tx + sx, ty + 17)], strap, top)
    # Back: the straps converge on the wing mount between the shoulder blades.
    bx, by = back[:2]
    mount = (bx + 16, by + 12)
    for sx in (7, 24):
        paint_rope(img, [(bx + sx, by - 1), mount], strap, back)
    for ex in (-2, 34):
        paint_rope(img, [mount, (bx + ex, by + 40)], strap, back, sprout=leafy, seed=ex + 90)
    # Sides: the lower straps wrap around the ribs.
    for reg in (right, left):
        rx, ry = reg[:2]
        paint_rope(img, [(rx - 1, ry + 40), (reg[2] + 2, ry + 40)], strap, reg)
    # Wing mount: a gilded maple leaf with an amber gem.
    stamp(img, "maple", mount[0], mount[1] + 1, 9.5, 0, 2, 303, clip=back, families=(GILT,) * 5,
          vein_colour=BRONZE[1], stem=False)
    paint_gem(img, mount[0], mount[1] + 1, 3.4, clip=back)
    # Clasp: an acorn flanked by two gilded oak leaves.
    stamp(img, "oak", clasp[0] - 7.5, clasp[1] - 1, 7.5, -64, 0, 301, clip=front, stem=False)
    stamp(img, "oak", clasp[0] + 7.5, clasp[1] - 1, 7.5, 64, 0, 302, clip=front, flip=True, stem=False)
    paint_acorn(img, clasp[0] - 6, clasp[1] - 7, clip=front)

    # Epaulettes: a cap of leaves over each shoulder, the tips hanging down the arm.
    outer = region(HUMANOID, "arm_outer", S)
    strip = (outer[0], outer[1], region(HUMANOID, "arm_back", S)[2], outer[3])
    ax0, ay0 = strip[:2]
    rng = random.Random(77)
    for k in range(8):
        cx = ax0 + 4 + k * 8 + rng.uniform(-1, 1)
        stamp(img, "maple" if k % 2 else "oak", cx, ay0 + 3.5, 8.0, 180 + rng.uniform(-12, 12),
              (1, 0, 2, 1, 0, 3, 1, 2)[k], seed=400 + k, clip=strip, stem=False)
    atop = region(HUMANOID, "arm_top", S)
    acx, acy = (atop[0] + atop[2] + 1) / 2, (atop[1] + atop[3] + 1) / 2
    for k, ang in enumerate((-90, 90, 0, 180)):
        stamp(img, "oak" if k % 2 else "maple", acx + 3 * math.sin(math.radians(ang)),
              acy - 3 * math.cos(math.radians(ang)), 7.5, ang, (1, 0, 2, 0)[k], seed=500 + k, clip=atop,
              stem=False)
    paint_gem(img, acx, acy, 2.6, clip=atop)
    return img


# --------------------------------------------------------------------------------------
# Item textures
# --------------------------------------------------------------------------------------

# Individual leaves (loose leaves, clasp leaves, withered leaves) in a 128x128 atlas:
# 24 px cells for leaves about 7 units long and 16 px cells for the little clasp leaves.
CELLS = {
    "crimson": (0, 0, 24), "scarlet": (24, 0, 24), "amber": (48, 0, 24), "gold_oak": (72, 0, 24),
    "orange": (96, 0, 24), "gold": (0, 24, 24), "wither_a": (24, 24, 24), "wither_b": (48, 24, 24),
    "wither_oak": (72, 24, 24), "bronze_oak": (0, 48, 16), "bronze_maple": (96, 24, 24),
}
CELL_LEAVES = {  # cell -> (kind, family, seed)
    "crimson": ("maple", 4, 1), "scarlet": ("maple", 3, 2), "amber": ("maple", 1, 3), "gold_oak": ("oak", 0, 4),
    "orange": ("maple", 2, 5), "gold": ("maple", 0, 6),
}


def paint_atlas(under: bool) -> Image.Image:
    img = canvas(128)
    for name, (kind, fam, seed) in CELL_LEAVES.items():
        x, y, n = CELLS[name]
        stamp(img, kind, x + n / 2, y + n / 2, n / 2 * 0.97, 0, fam, seed, clip=(x, y, x + n - 1, y + n - 1),
              shadow=False, under=under)
    x, y, n = CELLS["bronze_maple"]
    stamp(img, "maple", x + n / 2, y + n / 2, n / 2 * 0.97, 0, 2, 23, clip=(x, y, x + n - 1, y + n - 1),
          shadow=False, under=under, families=(GILT,) * 5, vein_colour=BRONZE[1])
    x, y, n = CELLS["bronze_oak"]
    stamp(img, "oak", x + n / 2, y + n / 2, n / 2 * 0.97, 0, 2, 17, clip=(x, y, x + n - 1, y + n - 1), shadow=False,
          under=under, families=(GILT,) * 5, vein_colour=BRONZE[1])
    for name, kind, seed in (("wither_a", "maple", 18), ("wither_b", "maple", 19), ("wither_oak", "oak", 20)):
        x, y, n = CELLS[name]
        stamp(img, kind, x + n / 2, y + n / 2, n / 2 * 0.97, 0, 2, seed, clip=(x, y, x + n - 1, y + n - 1),
              shadow=False, under=under, families=(WITHER,) * 5, vein_colour=WITHER[1])
    return img


# Item wing A's three layer cards share one 20x40-unit frame (model x 6.5..26.5, y -10..30),
# painted at 3.2 px per unit on a 64x128 half-atlas: px = (26.5 - x) * 3.2, py = (30 - y) * 3.2
# (left = the outer edge). The margins keep every ragged leaf tip inside the card.
CARD_X0, CARD_X1, CARD_Y0, CARD_Y1 = 6.5, 26.5, -10.0, 30.0
CARD_PX = 3.2
# Softer leaf rims and shadows on the item cards keep the icon bright at GUI scale 2.
CARD_RIM, CARD_BACK_DIM, CARD_LEAF_CAST, CARD_CAST = (2, 1), 0.26, -0.22, -0.28


def _item_lean(x):
    # leaves hang down and lean toward the inner edge (the centre gap)
    return 178 - 22 * ((24 - x) / 14)


# Rows of (y, xs, size, families, kinds): the coverts follow the arm, the secondaries
# bulge out below the twig's end, and the primaries taper to a tip on the outside.
ITEM_LAYERS = {
    "coverts": dict(
        front=((19.4, (11.4, 14.1, 16.8, 19.5, 22.1, 24.2), 2.45, (0, 0, 1, 0, 0, 1),
                ("oak", "maple", "oak", "maple", "oak", "maple")),
               (21.8, (12.4, 15.3, 18.2, 21.1, 23.6), 2.35, (0, 1, 0, 0, 0), ("maple", "oak", "maple", "oak", "oak")),
               (24.2, (14.6, 17.4, 20.2, 22.8), 2.2, (0,), ("oak", "oak", "maple", "oak"))),
        back=((20.6, (12.8, 15.5, 18.2, 20.9, 23.5), 2.4, (1,), ("maple",)),
              (23.0, (13.8, 16.8, 19.8, 22.4), 2.2, (0,), ("oak",))),
    ),
    "secondaries": dict(
        front=((10.6, (12.0, 15.0, 18.0, 21.0, 23.8), 2.9, (2, 2, 1, 2, 2), ("maple", "maple", "oak", "maple", "maple")),
               (13.6, (10.6, 13.4, 16.2, 19.0, 21.8, 24.3), 2.9, (1, 2, 1, 2, 1, 2),
                ("maple", "oak", "maple", "maple", "oak", "maple")),
               (16.6, (10.2, 13.0, 15.8, 18.6, 21.4, 24.1), 2.8, (1,),
                ("maple", "maple", "oak", "maple", "maple", "oak"))),
        back=((12.1, (11.2, 14.0, 16.8, 19.6, 22.4, 24.2), 2.8, (2,), ("maple",)),
              (15.1, (11.8, 14.8, 17.8, 20.8, 23.8), 2.8, (1,), ("maple",))),
    ),
    "primaries": dict(
        front=((-4.6, (19.8,), 2.6, (4,), ("maple",)),
               (-2.0, (18.2, 21.2), 3.0, (4,), ("maple",)),
               (1.2, (16.4, 19.6, 22.6), 3.2, (4, 4, 3), ("maple",)),
               (4.4, (14.4, 17.6, 20.8, 23.8), 3.2, (3, 4, 3, 4), ("maple",)),
               (7.6, (12.8, 15.7, 18.6, 21.4, 24.0), 3.1, (3,), ("maple",))),
        back=((-3.2, (19.6,), 2.8, (4,), ("maple",)),
              (-0.4, (17.8, 20.8), 3.1, (4,), ("maple",)),
              (2.8, (15.4, 18.6, 21.6), 3.2, (4,), ("maple",)),
              (6.0, (13.6, 16.8, 19.9, 23.0), 3.1, (3,), ("maple",))),
    ),
}


def paint_card(layer: str, under: bool) -> Image.Image:
    img = canvas(64, 128)
    spec = ITEM_LAYERS[layer]
    front = _rows(spec["front"], len(layer) * 31, _item_lean)
    back = _rows(spec["back"], len(layer) * 17, _item_lean)
    clip = (0, 0, 63, 127)
    k = CARD_PX
    for group, dim in ((back, CARD_BACK_DIM), (front, 0.0)):
        for kind, x, y, size, fam, ang, seed in group:
            stamp(img, kind, (CARD_X1 - x) * k, (CARD_Y1 - y) * k, size * k, ang, fam, seed, clip=clip, stem=False,
                  dim=dim, under=under, shadow=not under, rim_tones=CARD_RIM, cast=CARD_LEAF_CAST)
    return img


def _cast(lower: Image.Image, uppers, dx: int = 2, dy: int = 3, amount: float = -0.36) -> None:
    """Paint the shadow the cards in front throw onto `lower` (light from the upper left)."""
    w, h = lower.size
    lp = lower.load()
    ups = [u.load() for u in uppers]
    for y in range(h):
        for x in range(w):
            sx, sy = x - dx, y - dy
            if not lp[x, y][3] or not (0 <= sx < w and 0 <= sy < h):
                continue
            if any(u[sx, sy][3] for u in ups):
                lp[x, y] = _shaded(lp[x, y], amount)


def paint_cards(under: bool) -> tuple[Image.Image, Image.Image]:
    """Two 128x128 atlases: [primaries | secondaries] and [coverts | spare]."""
    cov, sec, pri = (paint_card(n, under) for n in ("coverts", "secondaries", "primaries"))
    if not under:
        _cast(sec, [cov], amount=CARD_CAST)
        _cast(pri, [cov, sec], amount=CARD_CAST)
    a, b = canvas(128), canvas(128)
    a.paste(pri, (0, 0))
    a.paste(sec, (64, 0))
    b.paste(cov, (0, 0))
    return a, b


def textures() -> None:
    save(paint_atlas(False), "leaves")
    save(paint_atlas(True), "leaves_under")
    cards_a, cards_b = paint_cards(False)
    under_a, under_b = paint_cards(True)
    save(cards_a, "wing_a")
    save(cards_b, "wing_b")
    save(under_a, "wing_a_under")
    save(under_b, "wing_b_under")

    rng = random.Random(9)
    bark = canvas(64, fill=BARK[2])
    for x in range(64):
        run = 0
        for y in range(64):
            if run <= 0:
                colour = rng.choice((BARK[1], BARK[2], BARK[2], BARK[3], BARK[3], BARK[4]))
                run = rng.randint(3, 9)
            bark.putpixel((x, y), rgba(colour))
            run -= 1
    for _ in range(10):
        kx, ky = rng.randrange(1, 63), rng.randrange(1, 63)
        bark.putpixel((kx, ky), rgba(BARK[0]))
        bark.putpixel((kx - 1, ky), rgba(BARK[4]))
    save(bark, "bark")

    bronze = canvas(64, fill=BRONZE[3])
    for y in range(64):
        for x in range(64):
            if y % 4 == 0:
                bronze.putpixel((x, y), rgba(BRONZE[5] if y % 8 == 0 else BRONZE[4]))
            elif y % 4 == 3:
                bronze.putpixel((x, y), rgba(BRONZE[2]))
    save(bronze, "bronze")

    vine = canvas(64, fill=VINE[3])
    for y in range(64):
        for x in range(64):
            t = (x + y) / 4.0
            ramp_ = VINE if int(t) % 2 == 0 else OLIVE
            frac = t - int(t)
            c = ramp_[4] if frac < 0.28 else (ramp_[3] if frac < 0.72 else ramp_[1])
            if x % 16 == 15:
                c = ramp_[1]
            vine.putpixel((x, y), rgba(c))
    save(vine, "vine")

    cap = canvas(64, fill=BRONZE[1])
    for y in range(64):
        for x in range(64):
            gx, gy = x % 4, (y + (x // 4) % 2 * 2) % 4
            if gy == 3 or gx == 3:
                c = BRONZE[0]
            elif gy == 0 and gx < 2:
                c = BRONZE[4]
            elif gy <= 1:
                c = BRONZE[3]
            else:
                c = BRONZE[2]
            cap.putpixel((x, y), rgba(c))
    save(cap, "acorn_cap")

    nut = canvas(64, fill=GEM[3])
    for y in range(64):
        for x in range(64):
            c = GEM[4] if y % 16 < 3 else (GEM[3] if y % 16 < 11 else GEM[2])
            if x % 8 == 1 and y % 16 < 12:
                c = GEM[5]
            if x % 8 == 7:
                c = GEM[2]
            nut.putpixel((x, y), rgba(c))
    save(nut, "acorn_nut")

    save_layer(paint_wings(), "wings")
    save_layer(paint_harness(), "humanoid")


# --------------------------------------------------------------------------------------
# 3D item model: folded leaf wings on a woven vine yoke
# --------------------------------------------------------------------------------------
# The decorated side faces -Z (north), which the kit's GUI, item frame and third-person
# presets show. Wing A is built at x > 8 and mirrored to x < 8. Each wing is three layer
# cards (coverts in front, then secondaries, then primaries) that curve back toward the
# outer edge and fan open toward the tips, with the twig, 3D leaves and ornaments on top.

LAYER_Z = {"coverts": 7.3, "secondaries": 8.3, "primaries": 9.3}
LAYER_CURVE = {"coverts": -10.0, "secondaries": -13.0, "primaries": -16.0}
LAYER_TILT = {"coverts": 0.0, "secondaries": -2.5, "primaries": -5.5}
LAYER_UV = {"primaries": ("wing_a", 0.0), "secondaries": ("wing_a", 8.0), "coverts": ("wing_b", 0.0)}
TILT_PIVOT_Y = 26.0  # the layers fan open below the wing's top edge


def _uv(cell):
    x, y, n = CELLS[cell]
    return x / 8, y / 8, (x + n) / 8, (y + n) / 8


def card_z(x, layer="coverts", y=TILT_PIVOT_Y):
    """Depth of a layer card's surface at model (x, y)."""
    return (LAYER_Z[layer] - (x - 16) * math.tan(math.radians(LAYER_CURVE[layer]))
            + (TILT_PIVOT_Y - y) * math.tan(math.radians(-LAYER_TILT[layer])))


def wing_card(layer):
    """One layer card of wing A: the full 20x40 card frame, top on the north face."""
    tex, u0 = LAYER_UV[layer]
    z = LAYER_Z[layer]
    card = box((CARD_X0, CARD_Y0, z), (CARD_X1, CARD_Y1, z), tex,
               faces={"north": (tex, [u0, 0.0, u0 + 8.0, 16.0]), "south": (tex + "_under", [u0 + 8.0, 0.0, u0, 16.0])},
               skip=("east", "west", "up", "down"))
    turn(card, LAYER_CURVE[layer], "y", (16, 10, z))
    if LAYER_TILT[layer]:
        turn(card, LAYER_TILT[layer], "x", (16, TILT_PIVOT_Y, z))
    return card


def leaf(cell, centre, half, direction, z, fold=16.0, tilt=None, glow=0):
    """A single leaf card (top on the north face, underside on the south face), folded
    along its midrib, pointing along `direction` in the XY plane."""
    cx, cy = centre
    u0, v0, u1, v1 = _uv(cell)
    um = (u0 + u1) / 2
    skip = ("east", "west", "up", "down")
    if fold:
        pa = box((cx, cy - half, z), (cx + half, cy + half, z), "leaves",
                 faces={"north": ("leaves", [u0, v0, um, v1]), "south": ("leaves_under", [um, v0, u0, v1])},
                 skip=skip, glow=glow)
        pb = box((cx - half, cy - half, z), (cx, cy + half, z), "leaves",
                 faces={"north": ("leaves", [um, v0, u1, v1]), "south": ("leaves_under", [u1, v0, um, v1])},
                 skip=skip, glow=glow)
        turn(pa, -fold, "y", (cx, cy, z))
        turn(pb, fold, "y", (cx, cy, z))
        parts = [pa, pb]
    else:
        parts = [box((cx - half, cy - half, z), (cx + half, cy + half, z), "leaves",
                     faces={"north": ("leaves", [u0, v0, u1, v1]), "south": ("leaves_under", [u1, v0, u0, v1])},
                     skip=skip, glow=glow)]
    dx, dy = direction
    turn(parts, math.degrees(math.atan2(-dx, dy)), "z", (cx, cy, z))
    if tilt:
        turn(parts, x=tilt[0], y=tilt[1], z=0, origin=(cx, cy, z))
    return parts


def chain(points, widths, tex, glow=0):
    """Bars joining successive points (a twig), each a little longer so joints overlap."""
    out = []
    for k in range(len(points) - 1):
        p0, p1 = points[k], points[k + 1]
        d = [p1[i] - p0[i] for i in range(3)]
        n = math.sqrt(sum(v * v for v in d))
        ext = min(0.3, n * 0.1)
        q0 = [p0[i] - d[i] / n * ext for i in range(3)]
        q1 = [p1[i] + d[i] / n * ext for i in range(3)]
        out.append(bar(q0, q1, widths[k], widths[k], tex, glow=glow))
    return out


# The wing's arm: from the yoke up to the wrist, then down the upper outer edge.
TWIG_PTS = ((12.6, 23.6), (15.4, 26.0), (18.8, 27.0), (22.0, 25.8), (23.9, 22.6), (24.4, 18.6))
TWIG_WIDTHS = (1.9, 1.8, 1.7, 1.55, 1.35)


def _twig_z(x):
    return card_z(x, "coverts") - 0.9


def wing_a(leaves=True) -> list[dict]:
    parts = []
    twig = [(x, y, _twig_z(x)) for x, y in TWIG_PTS]
    parts += chain(twig, TWIG_WIDTHS, "bark")
    # gilded wrist band and a gilded ferrule on the twig's end
    wx, wy = TWIG_PTS[2]
    parts += prism((wx, wy, _twig_z(wx)), 1.15, 1.0, "bronze", axis="x", sides=8)
    ex, ey = TWIG_PTS[-1]
    tip = prism((ex, ey + 0.2, _twig_z(ex)), 0.95, 1.2, "bronze", axis="y", sides=8)
    turn(tip, -6, "z", (ex, ey, _twig_z(ex)))
    parts += tip
    # a gilded maple crest on the wrist, set with a glowing amber bead
    cz = _twig_z(18.2) - 1.15
    parts += leaf("bronze_maple", (18.2, 27.9), 1.8, (-0.15, 1), cz, fold=0)
    parts.append(box((17.8, 27.5, cz - 0.5), (18.6, 28.3, cz + 0.05), "acorn_nut", glow=9))
    # a curled vine tendril above the wrist
    tz = _twig_z(20)
    parts += chain([(19.4, 27.4, tz), (20.2, 29.2, tz), (21.8, 30.0, tz), (23.0, 29.2, tz), (22.7, 28.1, tz)],
                   (0.75, 0.65, 0.55, 0.5), "vine")
    if leaves:
        parts += [wing_card("primaries"), wing_card("secondaries"), wing_card("coverts")]
        # a few leaves that stand proud of the cards
        parts += leaf("crimson", (19.4, -2.4), 3.4, (0.1, -1), card_z(19.4, "primaries", -2.4) - 1.1, fold=20,
                      tilt=(14, 0))
        parts += leaf("orange", (23.4, 7.8), 2.8, (0.35, -1), card_z(23.4, "primaries", 7.8) - 1.4, fold=18,
                      tilt=(10, 12))
        parts += leaf("amber", (11.0, 15.4), 2.6, (-0.55, -1), card_z(11.0, "secondaries", 15.4) - 0.9, fold=16,
                      tilt=(8, -10))
        parts += leaf("gold_oak", (22.2, 20.6), 2.5, (0.2, -1), card_z(22.2, "coverts", 20.6) - 0.8, fold=14,
                      tilt=(12, 10))
        parts += leaf("scarlet", (12.6, 6.6), 2.8, (-0.45, -1), card_z(12.6, "primaries", 6.6) - 1.0, fold=18,
                      tilt=(10, -12))
    else:
        parts += leaf("wither_a", (23.6, 14.2), 3.0, (0.3, -1), _twig_z(23.6) + 0.6, fold=22, tilt=(12, 0))
        parts += leaf("wither_oak", (16.2, 22.4), 2.4, (-0.2, -1), _twig_z(16.2) + 0.6, fold=0)
        parts += leaf("wither_b", (20.4, 21.0), 2.2, (0.1, -1), _twig_z(20.4) + 0.6, fold=18, tilt=(-10, 0))
    return parts


def yoke() -> list[dict]:
    parts = []
    zc = _twig_z(8) - 0.2
    pts = [(12.9, 23.6), (10.6, 22.9), (8.0, 22.7), (5.4, 22.9), (3.1, 23.6)]
    parts += chain([(x, y, zc) for x, y in pts], (1.3,) * 4, "vine")
    parts += chain([(x, y - 0.8, zc + 0.6) for x, y in pts], (1.0,) * 4, "vine")
    # acorn clasp at the centre, in front of the yoke
    za = zc - 1.5
    parts.append(bar((8, 23.8, za + 0.3), (8, 22.5, za), 0.55, 0.55, "bark"))
    # the cup: a scaled dome with a flared rim
    for y, r, length in ((22.55, 0.9, 0.35), (22.15, 1.6, 0.5), (21.45, 2.05, 0.9), (20.9, 2.2, 0.3)):
        parts += prism((8, y, za), r, length, "acorn_cap", sides=8, cap="acorn_cap")
    # the nut: polished amber, egg-shaped, tapering to a point
    for y, r, length in ((20.3, 1.55, 0.9), (19.1, 1.72, 1.5), (18.0, 1.35, 0.7), (17.4, 0.8, 0.45)):
        parts += prism((8, y, za), r, length, "acorn_nut", sides=8, cap="acorn_nut", glow=10)
    parts.append(box((7.78, 16.75, za - 0.22), (8.22, 17.2, za + 0.22), "acorn_nut", glow=10))
    # gilded oak leaves either side of the acorn cap
    parts += leaf("bronze_oak", (10.9, 21.9), 2.0, (1, 0.6), za + 0.3, fold=0)
    parts += leaf("bronze_oak", (5.1, 21.9), 2.0, (-1, 0.6), za + 0.3, fold=0)
    return parts


def loose_leaves() -> list[dict]:
    parts = []
    parts += leaf("crimson", (8.0, -4.8), 3.3, (-0.5, -0.8), 7.4, fold=16, tilt=(30, -25))
    parts += leaf("gold", (26.2, 0.2), 3.1, (-0.45, -0.9), 9.0, fold=14, tilt=(25, -30))
    return parts


def build(leaves=True) -> list[dict]:
    a = wing_a(leaves)
    parts = a + mirror(a, "x", 8) + yoke()
    if leaves:
        parts += loose_leaves()
    return parts


def _display(parts):
    d = display("elytra", parts, gui_rotation=(-10, 180, 0), gui_span=15.5)
    # Carried by the vine yoke, the wings hanging from the fist, turned a little to show depth.
    d["thirdperson_righthand"] = place({"y": (0, 1, 0.08), "z": (0.2, 0, -1)}, (6.8, 22.8, 5.0), "fist", 0.38)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.35, 0, -1)}, (8.0, 12.0, 8.0),
                                       (0.56, -0.44, -0.85), 0.42, pose=None)
    return d


def models() -> dict:
    main = build()
    broken = build(leaves=False)
    return {"main": model(main, _display(main)), "broken": model(broken, _display(broken))}
