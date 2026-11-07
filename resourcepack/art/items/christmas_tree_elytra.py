"""Christmas Tree Elytra: an evergreen elytra decorated like a Christmas tree (Survival,
issue #1397).

Worn: each wing is one half of a four-tier fir tree. The folded vanilla wings overlap
across the back and each one only shows the part outside a seam that runs down the
middle of the back (15 degrees across the texture, because the wings hang splayed), so
the tree is painted in a frame turned onto that seam: folded, the two halves meet and
read as one whole tree with a pointed top, layered tiers widening toward the bottom, a
red garland and a string of warm lights draped across every tier, red, blue, purple and
gold baubles, a golden star at the top and a red bow under it. The part of each wing
hidden behind the other one when folded is foliage too, so spread for gliding the same
tiers fan out across the whole wing as layered fir boughs. The worn wings are the vanilla
elytra boxes (one flat 10x20 panel each), so the tree outline is cut out of the texture.
A chest layer adds an evergreen garland harness with lights and a gold star clasp with a
red bow, front and back (the back one shows between the spread wings).

Item: the folded pair as a 3D tree: four layered tier panels per wing, each tilted
forward on its top edge like a real fir tier, needle sprigs along every hem, garland
swags and light strings as 3D bars, octagonal baubles with gold caps, a thick 3D gold
star on top and a red bow below it. The lights twinkle and the star gleams (animated
textures, glowing). At 1 durability the tree has dried out: brown needles, lights off,
a dull star and two baubles gone.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image, ImageDraw

from art.kit import (HUMANOID, WINGS, animate, bar, bounds, box, display, mirror, model, move, place, region, rgba,
                     save,
                     save_animation, save_layer, shine, sparkle, turn, wave)

ID = "christmas_tree_elytra"
NAME = "Christmas Tree Elytra"
KIND = "elytra"

# --------------------------------------------------------------------------------------
# Palette: every material has a hue-shifted ramp. Needle shadows lean blue-teal and the
# lit tips lean yellow-green; the red garland's shadows go crimson-violet and its
# highlights coral; gold shadows go brown-orange. Glow is only on the lights and star.
# --------------------------------------------------------------------------------------

NEEDLE = ["#04120f", "#08201a", "#0d2f20", "#123f25", "#19522b", "#226731", "#2f7f37", "#41993f",
          "#5bb24a", "#83c95c", "#b0df7c"]
DRY = ["#140d06", "#22160a", "#33220e", "#463012", "#5a3f17", "#6e4f1d", "#836026", "#977233",
       "#a98545", "#bb9a5d", "#cfb37d"]
RED = ["#24030f", "#470818", "#740f20", "#a41927", "#cf2830", "#ec4a40", "#ff8066"]
GOLD = ["#3d1f05", "#6b3c08", "#a4650f", "#d6961c", "#f3c23a", "#ffe171", "#fff5bd", "#ffffff"]
BLUE = ["#08103f", "#122a7a", "#1f4dbd", "#3f7fea", "#86b8ff", "#e1eeff"]
PURPLE = ["#1f0838", "#3d1263", "#62239a", "#8f45cc", "#c387f0", "#f1dcff"]
BAUBLE_RED = ["#30040c", "#650a16", "#a8151f", "#e2312c", "#ff8b6e", "#ffe0d2"]
BAUBLE_GOLD = ["#3d1f05", "#7a4a0a", "#c78a18", "#f2c23a", "#ffeb99", "#fffbe6"]
LIGHT = ["#5a2a02", "#a85a06", "#f0a020", "#ffd04a", "#fff09a", "#fffbe6"]
LIGHT_OFF = ["#1c160c", "#2c2414", "#3d331f", "#4d432c", "#5e553d", "#70684f"]
WIRE = "#071510"
BAUBLE_PALETTES = {"red": BAUBLE_RED, "blue": BLUE, "purple": PURPLE, "gold": BAUBLE_GOLD}

S = 4                      # worn layers are painted at 4x (256x128)
FACE_W, FACE_H = 10 * S, 20 * S
SUN = (-0.55, -0.83)       # painted light comes from the upper left (texture space, v down)
BAYER = np.array([0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5], np.float32).reshape(4, 4) / 16 - 0.47


def _c(colour) -> np.ndarray:
    return np.array(rgba(colour), dtype=np.float32)


def _lut(colours) -> np.ndarray:
    return np.stack([_c(c) for c in colours])


def _to_image(arr: np.ndarray) -> Image.Image:
    return Image.fromarray(np.clip(np.rint(arr), 0, 255).astype(np.uint8), "RGBA").copy()


def _shift(mask: np.ndarray, dx: int, dy: int) -> np.ndarray:
    out = np.zeros_like(mask)
    h, w = mask.shape
    out[max(0, dy):h + min(0, dy), max(0, dx):w + min(0, dx)] = \
        mask[max(0, -dy):h - max(0, dy), max(0, -dx):w - max(0, dx)]
    return out


def _grow(mask: np.ndarray) -> np.ndarray:
    return mask | _shift(mask, 1, 0) | _shift(mask, -1, 0) | _shift(mask, 0, 1) | _shift(mask, 0, -1)


def _edge(mask: np.ndarray) -> np.ndarray:
    return mask & ~(_shift(mask, 1, 0) & _shift(mask, -1, 0) & _shift(mask, 0, 1) & _shift(mask, 0, -1))


def _bayer(h: int, w: int) -> np.ndarray:
    return np.tile(BAYER, (h // 4 + 1, w // 4 + 1))[:h, :w]


def _grid(w: int, h: int):
    return np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)


def polygon_mask(w: int, h: int, pts, ss: int = 4) -> np.ndarray:
    """Pixels whose centres fall inside the polygon (supersampled, majority vote)."""
    m = Image.new("L", (w * ss, h * ss), 0)
    ImageDraw.Draw(m).polygon([(x * ss, y * ss) for x, y in pts], fill=255)
    a = np.array(m, np.float32).reshape(h, ss, w, ss).mean(axis=(1, 3))
    return a >= 127


def polyline_dist(X, Y, pts):
    """Distance from each pixel centre to a polyline, the signed side and the distance
    along it."""
    best = np.full(X.shape, np.inf, np.float32)
    side = np.zeros(X.shape, np.float32)
    along = np.zeros(X.shape, np.float32)
    acc = 0.0
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        dx, dy = x1 - x0, y1 - y0
        length = math.hypot(dx, dy)
        if length < 1e-6:
            continue
        ux, uy = dx / length, dy / length
        t = np.clip((X - x0) * ux + (Y - y0) * uy, 0, length)
        dist = np.hypot(X - (x0 + ux * t), Y - (y0 + uy * t))
        s = (X - x0) * (-uy) + (Y - y0) * ux
        closer = dist < best
        best = np.where(closer, dist, best)
        side = np.where(closer, s, side)
        along = np.where(closer, acc + t, along)
        acc += length
    return best, side, along, acc


def point_at(pts, k):
    """The point at arc length k along a polyline."""
    acc = 0.0
    for a, b in zip(pts, pts[1:]):
        seg = math.hypot(b[0] - a[0], b[1] - a[1])
        if acc + seg >= k and seg > 1e-9:
            t = (k - acc) / seg
            return a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
        acc += seg
    return pts[-1]


def length_of(pts) -> float:
    return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts, pts[1:]))


# --------------------------------------------------------------------------------------
# Tree space: p = distance out from the trunk line (the seam), q = distance down from the
# top. A Frame maps tree space onto a texture (x, y) with orthonormal axes.
# --------------------------------------------------------------------------------------

class Frame:
    def __init__(self, origin, n, d):
        self.ox, self.oy = origin
        self.n, self.d = n, d          # texture directions of +p (outward) and +q (down)

    def fwd(self, p, q):
        return (self.ox + p * self.n[0] + q * self.d[0], self.oy + p * self.n[1] + q * self.d[1])

    def pts(self, pts):
        return [self.fwd(p, q) for p, q in pts]

    def inv(self, X, Y):
        dx, dy = X - self.ox, Y - self.oy
        return dx * self.n[0] + dy * self.n[1], dx * self.d[0] + dy * self.d[1]


SEAM = math.radians(15.0)     # the folded wings hang 15 degrees out from the spine
# Folded, the middle of the back crosses each wing's face at u = 20.7 + v tan 15.
WORN_FRAME = Frame((20.7, 0.0), (-math.cos(SEAM), math.sin(SEAM)), (math.sin(SEAM), math.cos(SEAM)))
ITEM_FRAME = Frame((0.0, 0.0), (1.0, 0.0), (0.0, 1.0))


class Tier:
    def __init__(self, top: float, bottom: float, w_top: float, w_bottom: float, droop: float, teeth: int):
        self.top, self.bottom = top, bottom
        self.w_top, self.w_bottom = w_top, w_bottom
        self.droop = droop        # how much lower the outer tip hangs than the trunk end
        self.teeth = teeth        # needle points along the hem

    def width_at(self, q: float) -> float:
        t = min(1.0, max(0.0, (q - self.top) / max(1e-6, self.bottom - self.top)))
        # a slightly convex flank: the tier swells out toward its drooping tip
        return self.w_top + (self.w_bottom - self.w_top) * (t ** 0.85)

    def hem_q(self, p: float) -> float:
        """Where the hem is at distance p from the trunk (the tip hangs `droop` lower)."""
        t = min(1.0, abs(p) / max(1e-6, self.w_bottom))
        return self.bottom - self.droop * (1 - t) ** 1.3

    def outline(self, scale: float = 1.0, whole: bool = False):
        """Tree-space outline (trunk top, flank down to the outer tip, toothed hem back to
        the trunk) and the hem points on their own. `whole` mirrors it across the trunk
        too, for the part of a worn wing that the other wing covers when folded."""
        flank = []
        steps = 10
        for i in range(steps + 1):
            q = self.top + (self.bottom - self.top) * i / steps
            w = self.width_at(q)
            if 0 < i < steps:
                w -= (0.9 * scale) * (i % 2)          # fine serration: needle bunches
            flank.append((w, q))
        hem = [(self.w_bottom, self.bottom)]
        n = self.teeth * 2
        for i in range(1, n + 1):
            p = self.w_bottom * (1 - i / n)
            q = self.hem_q(p)
            if i % 2 == 1 and i < n:
                q -= 2.2 * scale                      # notch between two needle points
            hem.append((p, q))
        pts = [(0.0, self.top)] + flank + hem[1:]
        if whole:
            pts += [(-p, q) for p, q in reversed(hem[:-1])] + [(-p, q) for p, q in reversed(flank)]
        return pts, hem


def swag(frame: Frame, p0: float, p1: float, q_edge: float, drop: float, n: int = 16):
    """A garland draped across the tier, lowest at the trunk (a smile across both wings),
    from p0 to p1 in tree space; returns texture points."""
    out = []
    span = max(abs(p0), abs(p1), 1e-6)
    for i in range(n + 1):
        p = p0 + (p1 - p0) * i / n
        out.append(frame.fwd(p, q_edge + drop * (1 - (p / span) ** 2)))
    return out


# --------------------------------------------------------------------------------------
# Painters (texture space, fed tree-space shapes through a Frame)
# --------------------------------------------------------------------------------------

def paint_needles(w, h, mask, frame, tier, palette, seed, scale):
    """Needle foliage for one tier: darker deep inside and up under the tier above, lit
    toward the hem and the outer flank, short needle strokes raking down and outward."""
    X, Y = _grid(w, h)
    P, Q = frame.inv(X, Y)
    lut = _lut(palette)
    top = len(palette) - 1
    span = max(1e-6, tier.bottom - tier.top)
    tv = np.clip((Q - tier.top) / span, 0, 1)            # 0 at the top of the tier, 1 at the hem
    wd = np.clip(np.abs(P) / max(1.0, tier.w_bottom), 0, 1)
    tone = 2.3 + 3.8 * tv ** 1.4 + 1.5 * wd - 0.9 * (1 - wd) * (1 - tv)
    sm = Image.new("L", (w, h), 0)
    dm = Image.new("L", (w, h), 0)
    ds, dd = ImageDraw.Draw(sm), ImageDraw.Draw(dm)
    rnd = np.random.default_rng(seed)
    ys, xs = np.nonzero(mask)
    count = int(len(xs) / (6.5 * scale * scale)) + 4
    for _ in range(count):
        k = rnd.integers(0, len(xs))
        x, y = xs[k] + 0.5, ys[k] + 0.5
        p, _ = frame.inv(x, y)
        out = 1.0 if p >= 0 else -1.0
        ang = math.radians(58 + rnd.uniform(-16, 16))
        ln = (2.2 + rnd.random() * 2.8) * scale
        dp, dq = out * math.cos(ang) * ln, math.sin(ang) * ln
        ex = x + dp * frame.n[0] + dq * frame.d[0]
        ey = y + dp * frame.n[1] + dq * frame.d[1]
        (ds if rnd.random() < 0.55 else dd).line((x, y, ex, ey), fill=255, width=max(1, int(round(scale * 0.8))))
    lit = (np.array(sm) > 0) & mask
    dark = (np.array(dm) > 0) & mask & ~lit
    tone = np.where(lit, tone + 1.3, tone)
    tone = np.where(dark, tone - 1.1, tone)
    edge = _edge(mask)
    inner = mask & ~edge
    rim = inner & ~(_shift(inner, 0, -1) & _shift(inner, 0, -2))
    tone = np.where(rim & (tv > 0.6), tone + 1.6, tone)
    idx = np.clip(np.floor(tone + _bayer(h, w) * 0.8 + 0.5), 1, top)
    idx = np.where(edge, 0, idx)
    col = lut[idx.astype(int)]
    img = np.zeros((h, w, 4), np.float32)
    img[mask] = col[mask]
    return img


def ribbon(img, pts, width, palette=RED, twist=6.0):
    """A red garland ribbon: lit top edge, shaded underside, a spiral twist mark every few
    pixels and a dark drop shadow on what it lies on."""
    h, w = img.shape[:2]
    X, Y = _grid(w, h)
    d, s, along, _ = polyline_dist(X, Y, pts)
    opaque = img[..., 3] > 0
    inside = (d <= width / 2) & opaque
    shadow = (_shift(inside, 0, 1) | _shift(inside, 1, 1)) & ~inside & opaque
    img[shadow, :3] *= 0.5
    lut = _lut(palette)
    # which side is "up" depends on the drawing direction; use the texture's v
    rel = np.clip(s / max(0.5, width / 2), -1, 1)
    if pts[-1][0] < pts[0][0]:
        rel = -rel
    idx = 3.6 - 1.4 * rel
    tw = ((along + s * 0.8) % twist) < twist * 0.3
    idx = np.where(tw, idx + 1.4, idx)
    idx = np.where(np.abs(rel) > 0.72, idx - 1.4, idx)
    idx = np.clip(np.rint(idx), 1, len(palette) - 1).astype(int)
    img[inside] = lut[idx][inside]


def lights(img, pts, spacing, size, phase=0, palette=LIGHT, wire=True):
    """A dark wire along pts with warm bulbs every `spacing` pixels, each with a halo."""
    h, w = img.shape[:2]
    X, Y = _grid(w, h)
    opaque = img[..., 3] > 0
    if wire:
        d, _, _, _ = polyline_dist(X, Y, pts)
        img[(d <= 0.55) & opaque] = _c(WIRE)
    lut = _lut(palette)
    total = length_of(pts)
    k = spacing * 0.5
    i = phase
    while k < total:
        bx, by = point_at(pts, k)
        by += size * 0.6
        r = np.hypot((X - bx) / 0.8, Y - by)
        halo = (r <= size * 1.9) & (r > size) & opaque
        warm = _c(palette[3])
        img[halo, :3] = img[halo, :3] * 0.55 + warm[:3] * 0.45
        bulb = (r <= size) & opaque
        g = ((X - bx) * SUN[0] + (Y - by) * SUN[1]) / max(size, 0.5)
        idx = np.clip(np.rint(3.2 + 1.3 * g - (i % 3 == 1) * 0.6), 2, len(palette) - 1).astype(int)
        img[bulb] = lut[idx][bulb]
        cx, cy = int(bx), int(by)
        if 0 <= cx < w and 0 <= cy < h and opaque[cy, cx]:
            img[cy, cx] = lut[-1]
        k += spacing
        i += 1


def bauble(img, cx, cy, r, palette):
    """A round ornament: dark outline, shaded sphere with a highlight, gold cap on top."""
    h, w = img.shape[:2]
    X, Y = _grid(w, h)
    d = np.hypot(X - cx, Y - cy)
    opaque = img[..., 3] > 0
    shadow = (np.hypot(X - cx - 1, Y - cy - 1.2) <= r + 0.8) & opaque
    img[shadow, :3] *= 0.5
    lut = _lut(palette)
    ring = (d <= r + 0.9) & (d > r - 0.1)
    img[ring] = lut[0]
    inside = d <= r - 0.1
    g = ((X - cx) * SUN[0] + (Y - cy) * SUN[1]) / max(r, 0.5)
    idx = np.clip(np.rint(2.2 + 1.5 * g + _bayer(h, w) * 0.5), 1, len(palette) - 2).astype(int)
    img[inside] = lut[idx][inside]
    hx, hy = int(cx - r * 0.42), int(cy - r * 0.42)
    if 0 <= hx < w and 0 <= hy < h:
        img[hy, hx] = lut[-1]
        if r >= 3 and hx + 1 < w:
            img[hy, hx + 1] = lut[-2]
    gold = _lut(GOLD)
    capw = max(1, int(round(r * 0.5)))
    y0 = int(cy - r - 1.0)
    for x in range(int(cx - capw), int(cx + capw) + 1):
        for y in (y0, y0 + 1):
            if 0 <= x < w and 0 <= y < h:
                img[y, x] = gold[4] if y == y0 else gold[2]


def star_points(cx, cy, r_out, r_in, n=5, rot=-90.0):
    pts = []
    for k in range(n * 2):
        a = math.radians(rot + k * 180.0 / n)
        r = r_out if k % 2 == 0 else r_in
        pts.append((cx + math.cos(a) * r, cy + math.sin(a) * r))
    return pts


def paint_star(img, frame, p, q, r, palette=GOLD, dull=False):
    """A faceted gold star upright in tree space: each point split into a lit and a
    shaded half (never darker than mid gold, so it can't turn brown), a bright core and
    a dark outline only on the outside."""
    h, w = img.shape[:2]
    mask = polygon_mask(w, h, frame.pts(star_points(p, q, r, r * 0.45)))
    lut = _lut(palette)
    img[_grow(mask) & ~mask] = lut[1]
    X, Y = _grid(w, h)
    P, Q = frame.inv(X, Y)
    ang = (np.degrees(np.arctan2(Q - q, P - p)) + 90.0) % 72.0     # 0..72 within each point
    lit = ang < 36.0
    g = ((P - p) * -0.4 + (Q - q) * -0.8) / max(r, 1)
    idx = np.where(lit, 5.0, 3.6) + 0.8 * g
    near = np.hypot(P - p, Q - q) < r * 0.3
    idx = np.where(near, idx + 0.9, idx)
    lo = 3
    if dull:
        idx, lo = idx - 1.6, 2
    idx = np.clip(np.rint(idx), lo, len(palette) - 2).astype(int)
    img[mask] = lut[idx][mask]
    rim = _edge(mask) & (Q < q)                                       # a lit rim along the upper edges
    if not dull:
        img[rim] = lut[6]


def paint_bow(img, frame, p, q, s, palette=RED):
    """A red bow upright in tree space: two loops, a knot and two notched tails."""
    h, w = img.shape[:2]
    shapes = []
    for sg in (-1, 1):
        shapes.append([(p, q), (p + sg * 2.8 * s, q - 1.9 * s), (p + sg * 3.3 * s, q - 0.2 * s),
                       (p + sg * 2.6 * s, q + 1.0 * s)])                                   # loop
        shapes.append([(p + sg * 0.3 * s, q + 0.3 * s), (p + sg * 1.9 * s, q + 3.6 * s),
                       (p + sg * 1.3 * s, q + 3.1 * s), (p + sg * 0.9 * s, q + 3.9 * s),
                       (p - sg * 0.2 * s, q + 0.6 * s)])                                     # tail
    lut = _lut(palette)
    X, Y = _grid(w, h)
    P, Q = frame.inv(X, Y)
    full = np.zeros((h, w), bool)
    for pts in shapes:
        full |= polygon_mask(w, h, frame.pts(pts))
    knot = (np.abs(P - p) <= 0.8 * s) & (np.abs(Q - q) <= 0.8 * s)
    full |= knot
    img[_grow(full) & ~full] = lut[0]
    g = (-(Q - q) * 0.9 - (P - p) * 0.2) / max(s * 2, 1)
    fold = np.abs(P - p) > 1.7 * s
    idx = 3.6 + 1.2 * g - 0.8 * fold
    inner_loop = (np.abs(P - p) > 1.0 * s) & (np.abs(P - p) < 2.3 * s) & (np.abs(Q - q + 0.35 * s) < 0.45 * s)
    idx = np.where(inner_loop, 1.3, idx)                     # the dark inside of each loop
    idx = np.where(knot, 5.0 + g, idx)
    idx = np.clip(np.rint(idx), 1, len(palette) - 1).astype(int)
    img[full] = lut[idx][full]


def tier_point(frame, tier, fx, fy):
    """A texture point inside a tier: fy from its top to its hem, fx from the trunk out."""
    q = tier.top + (tier.bottom - tier.top) * fy
    p = tier.width_at(q) * fx
    q = min(q, tier.hem_q(p) - 1.5)
    return frame.fwd(p, q)


def decorate_tier(img, i, tier, frame, scale, ornaments, light_palette=LIGHT, whole=False, garland=True):
    """Garland, light string and baubles on one tier; `scale` is the decoration size in
    texture pixels per unit."""
    span = tier.bottom - tier.top
    if garland:
        q_edge = tier.top + span * 0.50
        w = tier.width_at(q_edge) - 2.0 * scale
        ribbon(img, swag(frame, w, -w if whole else 0.0, q_edge, span * 0.24), 1.6 * scale, twist=4.0 * scale)
    q_edge = tier.top + span * 0.74
    w = tier.width_at(q_edge) - 2.5 * scale
    lights(img, swag(frame, w, -w if whole else 0.0, q_edge, span * 0.2), 3.0 * scale, 0.62 * scale,
           phase=i, palette=light_palette)
    for t, fx, fy, colour in ornaments:
        if t == i:
            x, y = tier_point(frame, tier, fx, fy)
            bauble(img, x, y, 0.95 * scale, BAUBLE_PALETTES[colour])


def paint_tree(w, h, frame, tiers, scale, palette=NEEDLE, ornaments=(), light_palette=LIGHT, only=None, seed=0,
               whole=False, garlands=(0, 1, 2, 3), deco=None):
    """Paint tiers bottom-up (each upper tier overlaps the one below), decorate them and
    shade the band each tier shadows on the next. With `only`, just that tier (the
    item's separate panels)."""
    masks = [polygon_mask(w, h, frame.pts(t.outline(scale, whole)[0])) for t in tiers]
    img = np.zeros((h, w, 4), np.float32)
    for i in range(len(tiers) - 1, -1, -1):
        if only is not None and i != only:
            continue
        layer = paint_needles(w, h, masks[i], frame, tiers[i], palette, seed + i * 7, scale)
        decorate_tier(layer, i, tiers[i], frame, deco or scale, ornaments, light_palette, whole,
                      garland=i in garlands)
        m = layer[..., 3] > 0
        img[m] = layer[m]
    if only is None:
        # each tier's hem casts a dark band on the tier below (two steps: a deep core
        # line right under the hem, a softer shade after it), so the stacked tiers still
        # read when the wing is spread flat
        dark = _c(palette[1])
        for i in range(1, len(tiers)):
            near = np.zeros((h, w), bool)
            far = np.zeros((h, w), bool)
            for k in range(1, int(2 * scale) + 1):
                near |= _shift(masks[i - 1], 0, k)
            for k in range(1, int(5 * scale) + 1):
                far |= _shift(masks[i - 1], 0, k)
            near &= masks[i] & ~masks[i - 1]
            far &= masks[i] & ~masks[i - 1] & ~near
            img[near, :3] = img[near, :3] * 0.25 + dark[:3] * 0.75
            img[far, :3] *= 0.6
    return img


# --------------------------------------------------------------------------------------
# Worn wings (outer face 40x80 at 4x; u = 0 is the outer edge folded / leading edge
# gliding, v = 0 the root). Tree space here is in face pixels.
# --------------------------------------------------------------------------------------

WORN_TIERS = [
    Tier(6.0, 26.0, 1.0, 18.0, 3.5, 3),
    Tier(20.0, 45.0, 7.0, 27.0, 4.5, 4),
    Tier(38.0, 64.0, 11.0, 35.0, 5.0, 5),
    Tier(57.0, 82.0, 15.0, 40.0, 5.0, 6),
]
WORN_BAUBLES = [
    (0, 0.55, 0.74, "blue"),
    (1, 0.32, 0.40, "purple"), (1, 0.80, 0.86, "gold"),
    (2, 0.18, 0.36, "red"), (2, 0.56, 0.90, "blue"), (2, 0.88, 0.40, "purple"),
    (3, 0.10, 0.34, "gold"), (3, 0.42, 0.92, "red"), (3, 0.70, 0.40, "blue"), (3, 0.92, 0.90, "purple"),
    # the half the other wing covers when folded (seen while gliding)
    (1, -0.55, 0.80, "red"), (2, -0.40, 0.40, "gold"), (2, -0.80, 0.88, "purple"),
    (3, -0.30, 0.40, "blue"), (3, -0.66, 0.90, "gold"),
]
WORN_STAR = (0.0, 8.0, 7.0)       # p, q, outer radius: on the seam at the top
WORN_BOW_Q = 19.5
WORN_DECO = 1.9                   # decoration size: about half a game pixel per unit


def paint_wing_face(dry=False):
    img = paint_tree(FACE_W, FACE_H, WORN_FRAME, WORN_TIERS, 1.0, DRY if dry else NEEDLE, WORN_BAUBLES,
                     LIGHT_OFF if dry else LIGHT, seed=3, whole=True, garlands=(1, 2, 3), deco=WORN_DECO)
    p, q, r = WORN_STAR
    paint_bow(img, WORN_FRAME, 0.0, WORN_BOW_Q, 1.8)
    paint_star(img, WORN_FRAME, p, q, r, dull=dry)
    mask = img[..., 3] > 0
    return img, mask


def paint_wings() -> Image.Image:
    out = np.zeros((32 * S, 64 * S, 4), np.float32)
    outer, mask = paint_wing_face()

    def put(name, arr):
        x0, y0, x1, y1 = region(WINGS, name, S)
        out[y0:y1 + 1, x0:x1 + 1] = arr

    put("outer", outer)
    thick = 2 * S
    dark = np.array([0.55, 0.55, 0.6, 1.0], np.float32)
    rim_in = np.zeros((FACE_H, thick, 4), np.float32)
    rim_out = np.zeros((FACE_H, thick, 4), np.float32)
    for v in range(FACE_H):
        if mask[v, 0]:
            rim_in[v, thick - 1] = outer[v, 0]
            rim_in[v, thick - 2] = outer[v, 1] * dark
        if mask[v, FACE_W - 1]:
            rim_out[v, 0] = outer[v, FACE_W - 1]
            rim_out[v, 1] = outer[v, FACE_W - 2] * dark
    put("edge_inner", rim_in)
    put("edge_outer", rim_out)
    top = np.zeros((thick, FACE_W, 4), np.float32)
    bottom = np.zeros((thick, FACE_W, 4), np.float32)
    for j in range(FACE_W):
        c = FACE_W - 1 - j
        if mask[0, c]:
            top[0, j] = outer[0, c]
            top[1, j] = outer[0, c] * dark
        if mask[FACE_H - 1, c]:
            bottom[thick - 1, j] = outer[FACE_H - 1, c]
    put("top", top)
    put("bottom", bottom)
    return _to_image(out)


# --------------------------------------------------------------------------------------
# Chest layer: an evergreen garland harness with lights, a gold star clasp and a red bow.
# --------------------------------------------------------------------------------------

def garland_rope(layer, pts, width, phase=0, light_every=6.0):
    """A thick evergreen rope: needle tufts in alternating greens, warm bulbs along it."""
    h, w = layer.shape[:2]
    X, Y = _grid(w, h)
    d, s, along, _ = polyline_dist(X, Y, pts)
    inside = d <= width / 2
    if not inside.any():
        return
    opaque = layer[..., 3] > 0
    shadow = (_shift(inside, 1, 1) | _shift(inside, 0, 1)) & ~inside & opaque
    layer[shadow, :3] *= 0.45
    lut = _lut(NEEDLE)
    tuft = np.sin(along * 1.7 + s * 2.1) + 0.6 * np.sin(along * 0.9 - s * 3.3)
    rel = s / max(0.5, width / 2)
    idx = 5.0 - 1.8 * rel + 1.0 * tuft
    idx = np.where(d > width / 2 - 1.0, idx - 2.5, idx)
    idx = np.clip(np.rint(idx + _bayer(h, w) * 0.7), 1, len(NEEDLE) - 1).astype(int)
    layer[inside] = lut[idx][inside]
    ragged = (d <= width / 2 + 0.9) & ~inside & (np.sin(along * 2.3) > 0.2)
    layer[ragged] = lut[2]
    lights(layer, pts, light_every, 1.1, phase=phase, wire=False)


def paint_harness() -> Image.Image:
    out = np.zeros((32 * S, 64 * S, 4), np.float32)

    def area(name):
        x0, y0, x1, y1 = region(HUMANOID, name, S)
        return (x0, y0, x1, y1), np.zeros((y1 - y0 + 1, x1 - x0 + 1, 4), np.float32)

    def put(box_, layer):
        x0, y0, x1, y1 = box_
        m = layer[..., 3] > 0
        out[y0:y1 + 1, x0:x1 + 1][m] = layer[m]

    # Front (32x48): two garland straps from the shoulders meet at a gold bauble clasp; a
    # garland belt with lights.
    b, lay = area("body_front")
    garland_rope(lay, [(6.0, -3), (8.0, 6), (13.0, 13), (16.0, 14)], 5.0)
    garland_rope(lay, [(26.0, -3), (24.0, 6), (19.0, 13), (16.0, 14)], 5.0, phase=1)
    garland_rope(lay, [(-4, 39.0), (8, 41.0), (16, 41.5), (24, 41.0), (36, 39.0)], 5.0, phase=2, light_every=7.0)
    bauble(lay, 16.0, 15.0, 3.2, BAUBLE_GOLD)
    put(b, lay)
    b, lay = area("body_top")
    garland_rope(lay, [(6.0, -3), (6.0, 20)], 5.0)
    garland_rope(lay, [(26.0, -3), (26.0, 20)], 5.0, phase=1)
    put(b, lay)
    # Back: the same straps meet at a gold bauble clasp between the wing roots (the star
    # and bow are on the wings themselves, so they sit on the folded tree).
    b, lay = area("body_back")
    garland_rope(lay, [(6.0, -3), (8.0, 6), (13.0, 13), (16.0, 14)], 5.0, phase=2)
    garland_rope(lay, [(26.0, -3), (24.0, 6), (19.0, 13), (16.0, 14)], 5.0, phase=3)
    garland_rope(lay, [(-4, 39.0), (8, 41.0), (16, 41.5), (24, 41.0), (36, 39.0)], 5.0, phase=1, light_every=7.0)
    bauble(lay, 16.0, 15.0, 3.2, BAUBLE_GOLD)
    put(b, lay)
    for name in ("body_right", "body_left"):
        b, lay = area(name)
        garland_rope(lay, [(-4, 40.0), (20, 40.0)], 5.0, phase=2, light_every=7.0)
        put(b, lay)
    return _to_image(out)


# --------------------------------------------------------------------------------------
# Item model: the folded pair as a 3D tree. Right half built (x >= 8), then mirrored.
# Model units, y up. Item canvas: 128 px over 25.6 units (5 px per unit) covering
# x 8..33.6 and y 30..4.4 (v runs down); tree space there = canvas pixels.
# --------------------------------------------------------------------------------------

CX0, CY1, CSPAN, CPX = 8.0, 30.0, 25.6, 128
K = CPX / CSPAN
Z0 = 8.0
APEX = 27.4
ITEM_TIERS = [
    Tier((CY1 - 26.9) * K, (CY1 - 21.0) * K, 0.6 * K, 4.7 * K, 0.7 * K, 3),
    Tier((CY1 - 23.0) * K, (CY1 - 16.4) * K, 2.4 * K, 7.6 * K, 0.9 * K, 4),
    Tier((CY1 - 18.6) * K, (CY1 - 11.2) * K, 3.6 * K, 10.6 * K, 1.0 * K, 5),
    Tier((CY1 - 13.6) * K, (CY1 - 5.2) * K, 4.8 * K, 13.6 * K, 1.1 * K, 6),
]
ITEM_BAUBLES = [
    (1, 0.62, 0.70, "purple"),
    (2, 0.40, 0.36, "red"), (2, 0.84, 0.86, "blue"),
    (3, 0.28, 0.36, "gold"), (3, 0.62, 0.88, "purple"), (3, 0.90, 0.40, "red"),
]
TIER_Z = [10.4, 9.2, 8.0, 6.8]
TIER_TILT = [-20.0, -22.0, -24.0, -26.0]   # each tier leans its hem forward
ITEM_SCALE = K / 4.0 * 0.62                 # painter scale on the item canvas


def to_model(p, q):
    return CX0 + p / K, CY1 - q / K


def paint_item_tier(i, dry=False) -> Image.Image:
    img = paint_tree(CPX, CPX, ITEM_FRAME, ITEM_TIERS, ITEM_SCALE, DRY if dry else NEEDLE, (),
                     LIGHT_OFF if dry else LIGHT, only=i, seed=40, deco=ITEM_SCALE * 1.5)
    return _to_image(img)


def paint_item_tier_back(i, dry=False) -> Image.Image:
    """The back of a tier panel: the same needle cut-out without the garland, with a
    sparse scatter of lit needle tips and (on the live tree) a few warm lights, so the
    back and side views aren't a flat green wall."""
    pal = DRY if dry else NEEDLE
    tier = ITEM_TIERS[i]
    mask = polygon_mask(CPX, CPX, tier.outline(ITEM_SCALE)[0])
    img = paint_needles(CPX, CPX, mask, ITEM_FRAME, tier, pal, 90 + i, ITEM_SCALE)
    rnd = np.random.default_rng(70 + i)
    inner = mask & ~_edge(mask)
    ys, xs = np.nonzero(inner)
    tips = _lut(pal)
    for _ in range(max(6, len(xs) // 90)):
        k = rnd.integers(0, len(xs))
        x, y = xs[k], ys[k]
        img[y, x] = tips[rnd.integers(5, 8)]
        if x + 1 < CPX and inner[y, x + 1]:
            img[y, x + 1] = tips[4]
    if not dry:
        warm = _lut(LIGHT)
        for _ in range(max(2, len(xs) // 900)):
            k = rnd.integers(0, len(xs))
            x, y = xs[k], ys[k]
            for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
                if 0 <= x + dx < CPX and 0 <= y + dy < CPX and inner[y + dy, x + dx]:
                    img[y + dy, x + dx] = warm[4 if (dx, dy) == (0, 0) else 3]
    return _to_image(img)


def _uv_rect(lo, hi):
    """Canvas uv (0..16) of a model-space rectangle."""
    k = 16 / CSPAN
    return [(lo[0] - CX0) * k, (CY1 - hi[1]) * k, (hi[0] - CX0) * k, (CY1 - lo[1]) * k]


def model_swag(p0, p1, q_edge, drop, n):
    return [to_model(x, y) for x, y in swag(ITEM_FRAME, p0, p1, q_edge, drop, n)]


def tier_group(i, broken=False):
    tier = ITEM_TIERS[i]
    z = TIER_Z[i]
    parts = []
    top_y = to_model(0, tier.top)[1]
    lo = (CX0, to_model(0, tier.bottom + 1.0)[1])
    hi = (CX0 + (tier.w_bottom + 3) / K, top_y + 0.1)
    tex = f"{'dry' if broken else 'tier'}_{i}"
    tex_back = f"{'dry' if broken else 'tier'}_back_{i}"
    uv = _uv_rect(lo, hi)
    back = [uv[2], uv[1], uv[0], uv[3]]
    parts.append(box((lo[0], lo[1], z - 0.12), (hi[0], hi[1], z + 0.12), tex,
                     faces={"south": (tex, uv), "north": (tex_back, back)}, skip=("up", "down", "east", "west")))
    sprig = "sprig_dry" if broken else "sprig"
    # needle sprigs hanging from the hem's points, raking down and outward
    _, hem = tier.outline(ITEM_SCALE)
    for k, (p, q) in enumerate(hem):
        if k % 2 or p < 0.6 * K:
            continue
        x, y = to_model(p, q)
        ln = 1.2 + 0.3 * (k % 4 == 0)
        parts.append(bar((x - 0.2, y + 0.9, z + 0.05), (x + 0.3, y - ln + 0.5, z + 0.3), 0.75, 0.55, sprig))
    for f in (0.3, 0.65):
        q = tier.top + (tier.bottom - tier.top) * f
        x, y = to_model(tier.width_at(q), q)
        parts.append(bar((x - 0.9, y + 0.2, z + 0.02), (x + 0.5, y - 0.6, z + 0.2), 0.7, 0.5, sprig))
    # garland swag and a light string, 3D, in front of the panel
    span = tier.bottom - tier.top
    zg = z + 0.42
    if i >= 1:
        q_edge = tier.top + span * 0.5
        pts = model_swag(tier.width_at(q_edge) - 0.35 * K, 0.0, q_edge, span * 0.24, 5 if i < 3 else 6)
        for a, b in zip(pts, pts[1:]):
            parts.append(bar((a[0], a[1], zg), (b[0], b[1], zg), 0.9, 0.6, "garland_dull" if broken else "garland"))
    q_edge = tier.top + span * (0.74 if i else 0.55)
    pts = model_swag(tier.width_at(q_edge) - 0.55 * K, 0.0, q_edge, span * 0.2, 6)
    for a, b in zip(pts, pts[1:]):
        parts.append(bar((a[0], a[1], zg - 0.1), (b[0], b[1], zg - 0.1), 0.16, 0.16, "wire"))
    total = length_of(pts)
    dist, j = 0.55, 0
    while dist < total - 0.2:
        bx, by = point_at(pts, dist)
        if bx - CX0 > 0.45:
            if broken:
                parts.append(box((bx - 0.22, by - 0.62, zg - 0.05), (bx + 0.22, by - 0.06, zg + 0.3), "bulb_off",
                                 uv="full"))
            else:
                parts.append(box((bx - 0.25, by - 0.68, zg - 0.05), (bx + 0.25, by - 0.06, zg + 0.36),
                                 f"bulb_{'abc'[(j + i) % 3]}", uv="full", glow=13, shade=False))
        dist += 1.8
        j += 1
    # baubles: an octagon of two crossed cubes with a gold cap
    for n, (t, fx, fy, colour) in enumerate(ITEM_BAUBLES):
        if t != i or (broken and n in (2, 4)):
            continue
        q = tier.top + span * fy
        p = tier.width_at(q) * fx
        q = min(q, tier.hem_q(p) - 0.4 * K)
        x, y = to_model(p, q)
        r = 0.64
        zb = z + 0.58
        tb = f"bauble_{colour}"
        parts.append(box((x - r, y - r, zb - r * 0.8), (x + r, y + r, zb + r * 0.8), tb, uv="full"))
        parts.append(turn(box((x - r * 0.86, y - r * 0.86, zb - r * 0.6), (x + r * 0.86, y + r * 0.86, zb + r * 0.95),
                              tb, uv="full"), 45, "z", (x, y, zb)))
        parts.append(box((x - 0.22, y + r - 0.05, zb - 0.22), (x + 0.22, y + r + 0.3, zb + 0.22), "cap", uv="full"))
    turn(parts, TIER_TILT[i], "x", (8.0, top_y, z))     # lean the tier forward on its top edge
    return parts


def half_tree(broken=False):
    parts = []
    for i in range(len(ITEM_TIERS)):
        parts += tier_group(i, broken)
    # The wing splays out a little from the top and its outer edge sweeps back, like the
    # folded elytra.
    turn(parts, 4.0, "z", (8.0, APEX, Z0))
    turn(parts, 20.0, "y", (8.0, 16.0, Z0))
    return parts


STAR_C = (8.0, 29.4, 10.6)
STAR_R = 1.3                 # star size multiplier (it has to read at GUI scale 2)
BOW_C = (8.0, 24.2, 11.3)


def star(broken=False):
    """A thick five-point gold star: tapered points (wide inner bar + thin tip bar) round
    a faceted core, on a darker backing so it reads from the side. Unshaded so the gold
    stays bright in the inventory."""
    x, y, z = STAR_C
    s = STAR_R
    tex = "star_dull" if broken else "star"
    lit = {"glow": 0 if broken else 11, "shade": broken, "uv": "full"}
    parts = []
    for k in range(5):
        a = math.radians(90 + k * 72)
        ux, uy = math.cos(a), math.sin(a)
        mx, my = x + ux * 1.35 * s, y + uy * 1.35 * s
        parts.append(bar((x, y, z), (mx, my, z), 1.55 * s, 0.9, tex, **lit))
        parts.append(bar((mx - ux * 0.3 * s, my - uy * 0.3 * s, z), (x + ux * 2.75 * s, y + uy * 2.75 * s, z),
                         0.75 * s, 0.6, tex, **lit))
        parts.append(bar((x, y, z - 0.45), (x + ux * 2.1 * s, y + uy * 2.1 * s, z - 0.45), 0.8 * s, 0.25,
                         "star_back", uv="full"))
    parts.append(turn(box((x - 1.0 * s, y - 1.0 * s, z - 0.55), (x + 1.0 * s, y + 1.0 * s, z + 0.62), tex, **lit),
                      45, "z", (x, y, z)))
    parts.append(box((x - 0.55 * s, y - 0.55 * s, z + 0.55), (x + 0.55 * s, y + 0.55 * s, z + 0.78), tex, **lit))
    parts.append(box((x - 0.3, y - 3.6, z - 1.4), (x + 0.3, y - 1.2, z - 0.6), "cap", uv="full"))   # stem
    return parts


def bow():
    x, y, z = BOW_C
    parts = [box((x - 0.5, y - 0.45, z - 0.3), (x + 0.5, y + 0.45, z + 0.45), "bow", uv="full")]
    for sg in (-1, 1):
        parts.append(bar((x + sg * 0.3, y + 0.1, z), (x + sg * 2.0, y + 0.95, z - 0.15), 0.85, 0.55, "bow", uv="full"))
        parts.append(bar((x + sg * 0.3, y - 0.1, z), (x + sg * 1.9, y - 0.55, z - 0.15), 0.8, 0.55, "bow", uv="full"))
        parts.append(bar((x + sg * 1.85, y + 1.05, z - 0.15), (x + sg * 2.05, y - 0.6, z - 0.15), 0.55, 0.5,
                         "bow", uv="full"))
        parts.append(bar((x + sg * 0.2, y - 0.3, z + 0.05), (x + sg * 1.1, y - 2.5, z - 0.1), 0.6, 0.35, "bow",
                         uv="full"))
    return parts


def _raw(broken=False) -> list[dict]:
    half = half_tree(broken)
    return half + [mirror(p, "x", 8)[0] for p in half] + star(broken) + bow()


def offset() -> tuple[float, float, float]:
    lo, hi = bounds(_raw())
    return tuple(round(8 - (lo[i] + hi[i]) / 2, 3) for i in range(3))


def build(broken=False, shift=None) -> list[dict]:
    parts = _raw(broken)
    move(parts, *(shift or offset()))
    return parts


def transforms(parts, shift) -> dict:
    grip = (8 + shift[0], 15 + shift[1], 9 + shift[2])
    d = display("elytra", parts, grip=grip, size=0.72, gui_rotation=(10, 0, 0), gui_span=15.6)
    # First person: held low and to the side like the other elytras, the whole tree in view.
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.45, 0.15, 1)}, (8, 8, 8), (0.62, -0.3, -1.0), 0.3,
                                       pose=None)
    return d


def models() -> dict:
    shift = offset()
    main, broken = build(shift=shift), build(broken=True, shift=shift)
    return {"main": model(main, transforms(main, shift)), "broken": model(broken, transforms(broken, shift))}


# --------------------------------------------------------------------------------------
# Small textures
# --------------------------------------------------------------------------------------

def paint_sprig(dry=False) -> Image.Image:
    """Needle bunch: stripes of needles raking down a dark stem, lit on the left."""
    pal = _lut(DRY if dry else NEEDLE)
    img = np.zeros((16, 16, 4), np.float32)
    for y in range(16):
        for x in range(16):
            i = 4 + (x < 5) * 2 - (x > 11) * 2 + (((x + y * 2) % 5) == 0) * 2 - (((x * 3 + y) % 7) == 0)
            img[y, x] = pal[int(np.clip(i, 1, 9))]
    return _to_image(img)


def paint_garland(dull=False) -> Image.Image:
    lut = _lut(RED)
    img = np.zeros((16, 16, 4), np.float32)
    for y in range(16):
        for x in range(16):
            i = 3 if x < 4 else 2 if x < 10 else 1
            if (x + y) % 6 < 2:
                i += 2
            if dull:
                i -= 2
            img[y, x] = lut[int(np.clip(i, 1, 6))]
    return _to_image(img)


def paint_bauble(palette) -> Image.Image:
    lut = _lut(palette)
    img = np.zeros((16, 16, 4), np.float32)
    for y in range(16):
        for x in range(16):
            g = ((x - 7.5) * SUN[0] + (y - 7.5) * SUN[1]) / 8
            img[y, x] = lut[int(np.clip(round(2 + 1.6 * g), 1, len(palette) - 2))]
    for x, y in ((3, 3), (4, 3), (3, 4)):
        img[y, x] = lut[-1]
    img[5, 5] = lut[-2]
    return _to_image(img)


def paint_flat(colours, bands) -> Image.Image:
    lut = _lut(colours)
    img = np.zeros((16, 16, 4), np.float32)
    for y in range(16):
        for x in range(16):
            img[y, x] = lut[bands(x, y)]
    return _to_image(img)


def paint_bulb_frame(t: float, offset_: float) -> Image.Image:
    """One frame of a twinkling warm bulb: brightness rides a wave, with a sparkle at the
    peak."""
    k = wave(t, offset_)
    lut = _lut(LIGHT)
    img = np.zeros((16, 16, 4), np.float32)
    for y in range(16):
        for x in range(16):
            g = ((x - 7.5) * SUN[0] + (y - 7.5) * SUN[1]) / 8
            img[y, x] = lut[int(np.clip(round(2.4 + 1.2 * g + 1.6 * k), 1, 5))]
    im = _to_image(img)
    if k > 0.75:
        sparkle(im, 5, 5, (k - 0.75) * 4, "#ffffff", reach=3)
    return im


def paint_star_tex(dull=False) -> Image.Image:
    lut = _lut(GOLD)
    img = np.zeros((16, 16, 4), np.float32)
    for y in range(16):
        for x in range(16):
            # GOLD[3..6] only (never the brown shadow steps): a lit rim, a lit and a
            # shaded facet
            i = 6 if (x < 1 or y < 1) else 5 if x + y < 15 else 4 - (x > 13 or y > 13)
            if dull:
                i -= 2
            img[y, x] = lut[int(np.clip(i, 1 if dull else 3, 6))]
    return _to_image(img)


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def textures() -> None:
    save_layer(paint_wings(), "wings")
    save_layer(paint_harness(), "humanoid")
    for i in range(len(ITEM_TIERS)):
        save(paint_item_tier(i), f"tier_{i}")
        save(paint_item_tier(i, dry=True), f"dry_{i}")
        save(paint_item_tier_back(i), f"tier_back_{i}")
        save(paint_item_tier_back(i, dry=True), f"dry_back_{i}")
    save(paint_sprig(), "sprig")
    save(paint_sprig(dry=True), "sprig_dry")
    save(paint_garland(), "garland")
    save(paint_garland(dull=True), "garland_dull")
    for name, pal in BAUBLE_PALETTES.items():
        save(paint_bauble(pal), f"bauble_{name}")
    save(paint_flat(GOLD, lambda x, y: 5 if x < 5 else 3 if x < 11 else 2), "cap")
    save(paint_flat(["#020806", WIRE, "#123020"], lambda x, y: 1 + (x < 3)), "wire")
    save(paint_flat(GOLD, lambda x, y: 2 + ((x + y) % 4 == 0)), "star_back")
    save(paint_flat(LIGHT_OFF, lambda x, y: 3 if x + y < 12 else 1), "bulb_off")
    # brighter than the garland so the bow separates from it
    save(paint_flat(RED, lambda x, y: 6 if y < 3 else 5 if y < 10 else 4 - ((x + y) % 5 == 0)), "bow")
    for n, off in (("a", 0.0), ("b", 0.33), ("c", 0.66)):
        save_animation(animate(lambda t, o=off: paint_bulb_frame(t, o), 12), f"bulb_{n}", frametime=3,
                       interpolate=True)
    base = paint_star_tex()
    save_animation(animate(lambda t: shine(base, t, "#ffffff", width=4, strength=0.75, pause=0.55), 16), "star",
                   frametime=2)
    save(paint_star_tex(dull=True), "star_dull")
