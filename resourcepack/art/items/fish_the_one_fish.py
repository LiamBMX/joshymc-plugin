"""The One Fish: the mythical crown of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection's shared pose (side view, head up-left, tail
down-right), keeping the old sprite's all-gold scheme and deep diamond body. A tall,
laterally flattened golden fish: a steep forehead, a small upturned mouth, a radiant eye,
polished-gold flanks shaded round from a lemon shoulder highlight to amber at the back and
tail, a lattice of scales whose lit pixels are opal flecks, a countershaded cream belly, a
scalloped gill cover, tall swept-back sail fins above and below and a broad fan tail, all
fins translucent gold warming from orange at the root with painted rays and a lit hem.

Animation (MYTHICAL, 24 frames x 3 ticks): the tail fans and the fins ripple twice per
loop, a soft glint slides along the back, a shimmer band of sparkling scales rolls over the
body, the opal flecks cycle pink / violet / cyan under a sweeping iridescent sheen, a golden
rim glow breathes just outside the outline, sparkles orbit and twinkle, the eye flares into
a small star, and the signature: a divine golden halo floats over the head, glowing with the
breath while a bright glint runs round it.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_the_one_fish"
NAME = "The One Fish"
KIND = "item"
MODEL_KEY = "fish/the_one_fish"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 24
FRAMETIME = 3

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean rust / rose, lights lean lemon
OUTLINE = "#5a1f12"
OUTLINE_LIT = "#7a3216"
FIN_OUTLINE = ("#8e4219", 226)
GOLD = ["#7a2f14", "#a8481a", "#d0741f", "#eca02a", "#f9c73a", "#ffe165", "#fff2a3", "#fffbe2"]
CREAM = ["#e2aa50", "#f9dc92", "#fff0c2", "#fffae8"]
FIN = ["#b04a18", "#e0802a", "#f5ae3c", "#ffcf5e", "#ffe79a", "#fff8d0"]
EYE = ["#3b0f2c", "#ffffff", "#fff4b0", "#ffd95a"]    # pupil, catchlight, iris glow, iris
GLINT = "#fffbe6"
SHIMMER = "#fff6c4"
SPARK = "#ffffff"
IRIDESCENT = ["#ff8fd0", "#b98cff", "#7fe6ff"]         # pink, violet, cyan
RIM = "#f4b832"
RIM_HOT = "#fff09a"
RIM_ALPHA = (165, 235)
HALO_RAMP = ["#b8601c", "#e8a62c", "#fcd24a", "#fff08a", "#fffbe0"]

# ---- geometry (pixels) --------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 19.5                      # snout to tail base
SNOUT = (2.8, 9.2)             # snout tip in the frame
CENTRE = (15.5, 16.0)          # centre of the sparkles' orbit
SS = 4                         # supersampling per pixel side


def _smooth(points):
    """Catmull-Rom through (u, value) control points, as a vectorised function of u."""
    us = np.array([p[0] for p in points], float)
    vs = np.array([p[1] for p in points], float)
    dense = np.linspace(us[0], us[-1], 400)
    out = []
    for u in dense:
        i = min(max(int(np.searchsorted(us, u, side="right")) - 1, 0), len(us) - 2)
        p0, p1, p2, p3 = vs[max(i - 1, 0)], vs[i], vs[i + 1], vs[min(i + 2, len(vs) - 1)]
        t = (u - us[i]) / (us[i + 1] - us[i])
        out.append(0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                          + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3))
    table = np.array(out)
    return lambda u: np.interp(u, dense, table)


# Half-depths of the body above (TOP) and below (BOT) the axis: a deep diamond with a
# steep forehead, like the old sprite.
TOP = _smooth([(0.0, 0.5), (1.0, 1.2), (2.2, 2.1), (3.6, 3.2), (5.2, 4.2), (7.0, 4.9), (8.8, 5.1),
               (10.6, 4.8), (12.6, 4.0), (14.6, 2.9), (16.6, 1.9), (18.4, 1.3), (19.5, 1.2)])
BOT = _smooth([(0.0, 0.5), (1.0, 1.1), (2.2, 1.8), (3.6, 2.8), (5.2, 3.8), (7.0, 4.5), (8.8, 4.8),
               (10.6, 4.7), (12.6, 4.1), (14.6, 3.1), (16.6, 2.1), (18.4, 1.4), (19.5, 1.2)])

_ys, _xs = np.mgrid[0:SIZE * SS, 0:SIZE * SS]
_SX, _SY = (_xs + 0.5) / SS - SNOUT[0], (_ys + 0.5) / SS - SNOUT[1]
U = _SX * AX[0] + _SY * AX[1]
V = _SX * UP[0] + _SY * UP[1]
_py, _px = np.mgrid[0:SIZE, 0:SIZE]
UC = (_px + 0.5 - SNOUT[0]) * AX[0] + (_py + 0.5 - SNOUT[1]) * AX[1]
VC = (_px + 0.5 - SNOUT[0]) * UP[0] + (_py + 0.5 - SNOUT[1]) * UP[1]


def _cov(mask: np.ndarray) -> np.ndarray:
    """Per-pixel coverage (0..1) of a supersampled mask."""
    return mask.reshape(SIZE, SS, SIZE, SS).mean(axis=(1, 3))


def _poly(poly) -> np.ndarray:
    """Coverage of a polygon given in local (u, v) coordinates (even-odd rule)."""
    inside = np.zeros(U.shape, bool)
    n = len(poly)
    for i in range(n):
        u0, v0 = poly[i]
        u1, v1 = poly[(i + 1) % n]
        if v0 == v1:
            continue
        cond = (v0 > V) != (v1 > V)
        cross = u0 + (V - v0) * (u1 - u0) / (v1 - v0)
        inside ^= cond & (U < cross)
    return _cov(inside)


def _rot(points, pivot, degrees):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    pu, pv = pivot
    return [(pu + (u - pu) * c - (v - pv) * s, pv + (u - pu) * s + (v - pv) * c) for u, v in points]


def _near(u, v, a, b, reach):
    """True if (u, v) lies within reach of the segment a-b (local units = pixels)."""
    au, av = a
    bu, bv = b
    du, dv = bu - au, bv - av
    k = max(0.0, min(1.0, ((u - au) * du + (v - av) * dv) / (du * du + dv * dv)))
    return math.hypot(u - au - k * du, v - av - k * dv) <= reach


MASK = _cov((U >= 0) & (U <= SL + 0.3) & (V <= TOP(U)) & (V >= -BOT(U))) >= 0.5


def _bands(mask: np.ndarray):
    """Steps in from the back (kb) and from the belly (kv) for every body pixel."""
    cols = {x: np.nonzero(mask[:, x])[0] for x in range(SIZE) if mask[:, x].any()}
    rows = {y: np.nonzero(mask[y, :])[0] for y in range(SIZE) if mask[y, :].any()}
    kb = np.full(mask.shape, -1)
    kv = np.full(mask.shape, -1)
    for y, xs in rows.items():
        for x in xs:
            kb[y, x] = min(y - cols[x][0], xs[-1] - x)
            kv[y, x] = min(cols[x][-1] - y, x - xs[0])
    return kb, kv


KB, KV = _bands(MASK)
# rows down from the top contour in each column: the up-facing edge catches the light
KT = np.array([[y - int(np.nonzero(MASK[:, x])[0][0]) if MASK[y, x] else -1 for x in range(SIZE)]
               for y in range(SIZE)])


# ---- fins: (polygon, rays) in local coordinates; v > 0 is the back ---------------------------

TAIL_PIVOT = (19.2, 0.0)
TAIL_REST = 0.0               # the tail rests bent a little toward the back (degrees)


def _tail(sway: float):
    """A flowing lyre tail: two long pointed lobes curving out of a deep fork."""
    pts = [(18.4, 1.25), (20.2, 2.2), (22.2, 3.4), (24.4, 4.6), (27.2, 5.4), (26.9, 4.1), (26.0, 2.6),
           (25.2, 1.2), (24.9, 0.0),
           (25.2, -1.2), (26.0, -2.6), (26.9, -4.1), (27.2, -5.4), (24.4, -4.6), (22.2, -3.4), (20.2, -2.2),
           (18.4, -1.25)]
    rays = [((19.4, 0.8), (26.4, 4.7), FIN[1]), ((19.6, 0.3), (25.0, 1.7), FIN[1]),
            ((19.6, -0.3), (25.0, -1.7), FIN[1]), ((19.4, -0.8), (26.4, -4.7), FIN[1])]
    a = TAIL_REST + 8.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _dorsal(ripple: float):
    """A tall sail fin, swept back to a pointed trailing tip over the tail root."""
    tip = (15.6 + 0.5 * ripple, TOP(15.6) + 4.2 + 0.3 * ripple)
    pts = [(5.6, TOP(5.6) - 0.6), (7.0, TOP(7.0) + 2.0), (9.6, TOP(9.6) + 3.0), (12.6, TOP(12.6) + 3.6), tip,
           (16.2, TOP(16.2) + 2.2), (17.2, TOP(17.2) - 0.6)]
    rays = [((6.2, TOP(6.2)), (7.3, TOP(7.3) + 1.9), FIN[4]),
            ((8.8, TOP(8.8)), (9.8, TOP(9.8) + 2.7), FIN[1]),
            ((11.2, TOP(11.2)), (12.4 + 0.2 * ripple, TOP(12.4) + 3.4), FIN[1]),
            ((13.6, TOP(13.6)), (15.0 + 0.4 * ripple, TOP(15.0) + 3.8), FIN[1])]
    return pts, rays


def _anal(ripple: float):
    tip = (15.8 + 0.5 * ripple, -BOT(15.8) - 3.6 - 0.3 * ripple)
    pts = [(9.0, -BOT(9.0) + 0.6), (10.2, -BOT(10.2) - 1.8), (12.8, -BOT(12.8) - 2.9), tip,
           (16.4, -BOT(16.4) - 1.8), (17.4, -BOT(17.4) + 0.6)]
    rays = [((10.6, -BOT(10.6)), (11.4, -BOT(11.4) - 2.2), FIN[1]),
            ((12.8, -BOT(12.8)), (13.8, -BOT(13.8) - 2.9), FIN[1]),
            ((14.8, -BOT(14.8)), (15.6 + 0.4 * ripple, -BOT(15.6) - 3.2), FIN[1])]
    return pts, rays


def _pectoral(flap: float):
    pivot = (5.8, -0.9)
    pts = [(5.4, -0.4), (6.6, -0.3), (9.8, -1.9), (9.6, -2.8), (6.2, -1.8)]
    rays = [(pivot, (9.4, -2.4), FIN[3])]
    a = 10.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _pelvic(flap: float):
    pivot = (6.0, -BOT(6.0))
    pts = [(5.4, -BOT(5.4) + 0.5), (6.8, -BOT(6.8) + 0.4), (8.0, -BOT(8.0) - 2.3), (6.7, -BOT(6.7) - 2.5)]
    a = 9.0 * flap
    return _rot(pts, pivot, a), [((6.2, -BOT(6.2)), (7.4, -BOT(7.4) - 2.1), FIN[1])]


def _distance(mask: np.ndarray) -> np.ndarray:
    """Chessboard distance (pixels) of every pixel from the body."""
    d = np.where(mask, 0, 99)
    for _ in range(12):
        grown = d.copy()
        grown[1:, :] = np.minimum(grown[1:, :], d[:-1, :] + 1)
        grown[:-1, :] = np.minimum(grown[:-1, :], d[1:, :] + 1)
        grown[:, 1:] = np.minimum(grown[:, 1:], d[:, :-1] + 1)
        grown[:, :-1] = np.minimum(grown[:, :-1], d[:, 1:] + 1)
        grown[1:, 1:] = np.minimum(grown[1:, 1:], d[:-1, :-1] + 1)
        grown[:-1, :-1] = np.minimum(grown[:-1, :-1], d[1:, 1:] + 1)
        grown[1:, :-1] = np.minimum(grown[1:, :-1], d[:-1, 1:] + 1)
        grown[:-1, 1:] = np.minimum(grown[:-1, 1:], d[1:, :-1] + 1)
        d = grown
    return d


DIST = _distance(MASK)


def _paint_fin(img, fin, alpha, cov_min=0.45, ray_alpha=230, over_body=False):
    """Paint a translucent fin: a membrane that warms from orange at the root to pale gold
    at the edge, darker rays, a lemon hem on the edge facing the top-left light and a paler
    gold hem on the rest of the open edge. over_body: a near fin lying on the flank."""
    poly, rays = fin
    cov = _poly(poly)
    inside = (cov >= cov_min) & ~MASK
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if cov[y, x] < cov_min:
                continue
            u, v = UC[y, x], VC[y, x]
            d = int(DIST[y, x])
            if over_body:
                colour, a = FIN[3], alpha
            else:
                colour, a = (FIN[1] if d <= 1 else FIN[2] if d <= 3 else FIN[3]), alpha
            for ra, rb, rc in rays:
                if cov[y, x] >= 0.9 and _near(u, v, ra, rb, 0.45):
                    colour, a = (rc if d <= 3 or over_body else FIN[2]), ray_alpha
                    break
            if not over_body and inside[y, x]:
                open_ul = (y > 0 and not inside[y - 1, x] and not MASK[y - 1, x]) or                     (x > 0 and not inside[y, x - 1] and not MASK[y, x - 1])
                open_dr = (y < SIZE - 1 and not inside[y + 1, x] and not MASK[y + 1, x]) or                     (x < SIZE - 1 and not inside[y, x + 1] and not MASK[y, x + 1])
                if open_ul:
                    colour, a = FIN[5], min(255, alpha + 20)
                elif open_dr:
                    colour, a = FIN[4], alpha
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)


# ---- body -----------------------------------------------------------------------------------

EYE_AT = (5, 10)                # top-left pixel of the 2x2 eye


def _scale_mark(x: int, y: int) -> bool:
    """A sparse diamond lattice (image space) for scale marks."""
    return y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1


def _body_pixel(x: int, y: int):
    """Colour and zone of one body pixel. Rounded polished gold: a lit rim, a lemon
    highlight on the shoulder shading off through gold to amber at the back edge, a
    countershaded cream belly, all falling off toward the tail, away from the light."""
    kb, kv = int(KB[y, x]), int(KV[y, x])
    u, v = UC[y, x], VC[y, x]
    tail = 1 if u > 15.0 else 0
    # gill cover: an arc behind the eye, dark edge with a lit rim just behind it
    g = math.hypot(u - 3.3, (v - 0.1) * 0.85)
    if 4.3 <= u <= 6.8 and 2.7 <= g < 3.45 and kb >= 1 and kv >= 1:
        return GOLD[3], "gill"
    if 4.8 <= u <= 7.4 and 3.45 <= g < 4.2 and kb >= 2 and kv >= 1:
        return GOLD[6], "head"
    head = g < 3.45 and u < 6.8
    if KT[y, x] == 0:
        return GOLD[7 if u < 9.0 else 6 if u < 14.0 else 5], "back"
    if kv == 0:
        return (CREAM[0] if u > 4.0 else CREAM[1]), "belly"
    # depth across the body at this point: 0 on the dorsal contour, 1 on the ventral one
    p = min(1.0, max(0.0, (float(TOP(u)) - v) / (float(TOP(u)) + float(BOT(u)))))
    if p > 0.74:
        c, zone = (CREAM[2] if kv >= 2 and p < 0.86 else CREAM[1]), "belly"
    else:
        # rounded polished gold: brightest on the shoulder just under the back (top-left
        # light), falling off toward the back edge, the belly and the tail
        across = 1.0 - abs(p - 0.3) / 0.46
        along = 1.0 - u / SL
        lv = 0.62 * across + 0.5 * along - 0.05
        tone = int(min(6, max(2, 2 + round(lv * 4.6))))
        c = GOLD[tone]
        zone = "back" if p < 0.2 else "flank"
    if head:
        return c, "head"
    # scale marks: a darker pixel with a lit pixel above-left of it, on a diamond lattice
    if zone in ("flank", "back") and 6.8 < u < 18.0 and kb >= 2 and kv >= 2:
        if _scale_mark(x, y):
            return GOLD[max(1, GOLD.index(c) - 1)], "scale"
        if _scale_mark(x + 1, y + 1) and MASK[y + 1, x + 1]:
            return GOLD[min(7, GOLD.index(c) + 1)], "fleck"
    return c, zone


def _body():
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                c, zone = _body_pixel(x, y)
                px[x, y] = rgba(c)
                zones[(x, y)] = zone
    return img, zones


def _head(img, zones, glow: float):
    """Eye (2x2 pupil + catchlight, glowing iris that pulses with `glow`) and mouth."""
    px = img.load()
    ex, ey = EYE_AT
    iris = mix(EYE[3], EYE[2], glow)
    for dx, dy in ((-1, 0), (-1, 1), (0, -1), (1, -1), (2, 0), (2, 1), (0, 2), (1, 2)):
        if MASK[ey + dy, ex + dx]:
            px[ex + dx, ey + dy] = rgba(mix(px[ex + dx, ey + dy], iris, 0.55 + 0.35 * glow))
            zones[(ex + dx, ey + dy)] = "eye"
    px[ex, ey] = rgba(EYE[1])
    px[ex + 1, ey] = rgba(EYE[0])
    px[ex, ey + 1] = rgba(EYE[0])
    px[ex + 1, ey + 1] = rgba(EYE[0])
    for k in ((ex, ey), (ex + 1, ey), (ex, ey + 1), (ex + 1, ey + 1)):
        zones[k] = "eye"
    # small upturned mouth: a dark notch at the snout with a pale lip under it
    for (x, y), c in {(3, 10): OUTLINE, (4, 11): GOLD[1], (3, 11): CREAM[2]}.items():
        if MASK[y, x]:
            px[x, y] = rgba(c)
            zones[(x, y)] = "line"


# ---- effects --------------------------------------------------------------------------------

def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _glint(img, zones, t: float):
    """A soft glint along the back, snout to tail, during the first third of the loop."""
    run = 1 / 3
    if t >= run:
        return
    centre = -1.5 + (SL + 3.0) * (t / run)
    px = img.load()
    for (x, y), zone in zones.items():
        kb = int(KB[y, x])
        if zone in ("eye", "line") or kb < 0 or kb > 3:
            continue
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.0) * 0.6 * (1.0 - kb / 4.5)
        if k > 0:
            _blend(px, x, y, GLINT, k)


def _shimmer(img, zones, t: float):
    """A band of sparkling scales rolling head to tail during the middle of the loop."""
    lo, hi = 0.45, 0.8
    if not lo <= t < hi:
        return
    centre = 3.0 + (SL - 1.0) * (t - lo) / (hi - lo)
    px = img.load()
    for (x, y), zone in zones.items():
        if zone not in ("flank", "scale", "fleck", "back", "belly"):
            continue
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.0)
        if k <= 0:
            continue
        spot = y % 2 == 1 and (x + y // 2) % 2 == 0
        _blend(px, x, y, SPARK if spot else SHIMMER, (0.9 if spot else 0.2) * min(1.0, k * 1.3))


def _iridescence(img, zones, t: float):
    """Opal flecks: the lit pixel of every scale cycles pink -> violet -> cyan, the colour
    rolling head to tail, and once per loop a broader iridescent sheen sweeps diagonally
    over the flank and brightens them."""
    px = img.load()
    for (x, y), zone in zones.items():
        if zone not in ("flank", "scale", "fleck"):
            continue
        u, v = UC[y, x], VC[y, x]
        band = (u / 16.0 + v / 20.0 - t) % 1.0
        sweep = math.sin(math.pi * band / 0.4) if band < 0.4 else 0.0
        if zone == "fleck":
            i = ((u / 7.0 - v / 9.0 - 2 * t) % 1.0) * 3
            c = mix(IRIDESCENT[int(i) % 3], IRIDESCENT[(int(i) + 1) % 3], i - int(i))
            _blend(px, x, y, c, 0.62 + 0.3 * sweep)
        elif sweep > 0:
            i = band / 0.4 * 2.0
            c = mix(IRIDESCENT[int(i)], IRIDESCENT[min(2, int(i) + 1)], i - int(i))
            _blend(px, x, y, c, 0.22 * sweep if zone == "flank" else 0.3 * sweep)


def _outline(img: Image.Image, solid) -> Image.Image:
    """1 px outline: dark rose-brown beside the body (a touch lighter where it faces the
    top-left light), a softer rust where it only borders translucent fins."""
    src = img.load()
    out = img.copy()
    dst = out.load()
    fr, fg, fb, _ = rgba(FIN_OUTLINE[0])
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                    if 0 <= x + dx < SIZE and 0 <= y + dy < SIZE and src[x + dx, y + dy][3]]
            if not near:
                continue
            if any(solid[ny, nx] for nx, ny in near):
                lit = (x, y + 1) in near or (x + 1, y) in near
                lit = lit and (x, y - 1) not in near and (x - 1, y) not in near
                dst[x, y] = rgba(OUTLINE_LIT if lit else OUTLINE)
            else:
                dst[x, y] = (fr, fg, fb, FIN_OUTLINE[1])
    return out


def _rim(img: Image.Image, t: float) -> None:
    """A breathing golden rim glow one pixel outside the outline."""
    px = img.load()
    occupied = np.array([[px[x, y][3] > 0 for x in range(SIZE)] for y in range(SIZE)])
    breath = 0.5 - 0.5 * math.cos(2 * math.pi * t)
    c = rgba(mix(RIM, RIM_HOT, breath))
    alpha = round(RIM_ALPHA[0] + (RIM_ALPHA[1] - RIM_ALPHA[0]) * breath)
    for y in range(1, SIZE - 1):
        for x in range(1, SIZE - 1):
            if not occupied[y, x] and (occupied[y - 1, x] or occupied[y + 1, x] or occupied[y, x - 1]
                                       or occupied[y, x + 1]):
                px[x, y] = (c[0], c[1], c[2], alpha)


# The halo: a level ring floating over the head, drawn as (dx, dy, part) from HALO_AT.
# "b" is the far arc (behind, in shade), "f" the near arc (lit), "e" the ends.
HALO_AT = (3, 1)
HALO = ["..bbbbbb..",
        ".e......e.",
        "..ffffff.."]
HALO_LOOP = [(2, 0), (3, 0), (4, 0), (5, 0), (6, 0), (7, 0), (8, 1), (7, 2), (6, 2), (5, 2), (4, 2), (3, 2),
             (2, 2), (1, 1)]     # the ring's pixels in order around it, for the travelling glint


def _halo(img: Image.Image, t: float) -> None:
    """The signature: a divine golden halo over the head. It glows with the breathing rim,
    a bright glint runs round it once per loop, and short rays flare from it in turn."""
    px = img.load()
    breath = 0.5 - 0.5 * math.cos(2 * math.pi * t)
    hx, hy = HALO_AT
    tone = {"b": HALO_RAMP[1], "e": HALO_RAMP[2], "f": HALO_RAMP[3]}
    for dy, row in enumerate(HALO):
        for dx, ch in enumerate(row):
            if ch in tone:
                px[hx + dx, hy + dy] = rgba(mix(tone[ch], HALO_RAMP[4], 0.35 * breath))
    # glint running round the ring
    n = len(HALO_LOOP)
    head = t * n
    for i, (dx, dy) in enumerate(HALO_LOOP):
        d = min((i - head) % n, (head - i) % n)
        k = max(0.0, 1.0 - d / 1.6)
        if k > 0:
            _blend(px, hx + dx, hy + dy, SPARK, 0.95 * k)
    # the halo's own soft glow: faint gold just outside its ends and above / below its arcs
    g = rgba(RIM)
    ga = round(40 + 70 * breath)
    for dx, dy in ((0, 1), (9, 1), (1, 0), (8, 0), (1, 2), (8, 2)):
        x, y = hx + dx, hy + dy
        if px[x, y][3] == 0:
            px[x, y] = (g[0], g[1], g[2], ga)


# Sparkles: two orbit the fish on a tilted ellipse, two twinkle in place.
TWINKLES = [((25, 7), 0.0, 2), ((6, 24), 0.5, 2)]


def _sparkles(img: Image.Image, t: float) -> None:
    for i in range(2):
        a = 2 * math.pi * (t + i / 2)
        ox = CENTRE[0] + 13.0 * math.cos(a) * math.cos(THETA) - 9.0 * math.sin(a) * math.sin(THETA)
        oy = CENTRE[1] + 13.0 * math.cos(a) * math.sin(THETA) + 9.0 * math.sin(a) * math.cos(THETA)
        x, y = round(ox - 0.5), round(oy - 0.5)
        x, y = min(max(x, 2), SIZE - 3), min(max(y, 2), SIZE - 3)
        sparkle(img, x, y, 0.35 + 0.45 * (0.5 + 0.5 * math.sin(4 * math.pi * t + i)), colour=SPARK, reach=1)
    for (x, y), off, reach in TWINKLES:
        amount = max(0.0, math.sin(2 * math.pi * (t + off)))
        sparkle(img, x, y, amount ** 1.5, colour=SPARK, reach=reach)


def _frame(t: float) -> Image.Image:
    """One 32x32 frame at loop phase t (0..1)."""
    sway = math.sin(4 * math.pi * t)
    ripple = math.sin(4 * math.pi * (t - 0.08))
    flap = math.sin(4 * math.pi * (t + 0.15))

    img = canvas(SIZE)
    _paint_fin(img, _tail(sway), 214, cov_min=0.4)
    _paint_fin(img, _dorsal(ripple), 196)
    _paint_fin(img, _anal(ripple), 194)
    body, zones = _body()
    _iridescence(body, zones, t)
    _glint(body, zones, t)
    _shimmer(body, zones, t)
    _head(body, zones, 0.5 - 0.5 * math.cos(2 * math.pi * t))
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), 196, over_body=True)
    _paint_fin(front, _pelvic(flap), 210)
    img.alpha_composite(front)
    img = _outline(img, MASK)
    _rim(img, t)
    _halo(img, t)
    # the radiant eye: once per loop its catchlight flares into a small star
    flare = math.sin(math.pi * (t - 0.62) / 0.3) if 0.62 <= t < 0.92 else 0.0
    sparkle(img, EYE_AT[0], EYE_AT[1], flare * 0.8, colour=SPARK, reach=1)
    _sparkles(img, t)
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
