"""Enchanted Koi: an epic fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's molten orange-gold body with its white face patch. A deep,
round-backed koi: a blunt head with a white face mask and pink lips, a pair of barbels,
a gold-rimmed eye, a gill cover, a reticulated net of scales over a red-orange back and
golden flanks, a white saddle marking, a countershaded cream belly, a long low dorsal fin,
big rounded pectoral fins and a broad flowing tail. The fins fade from orange at the base
to translucent pearl-lavender at the tips, with painted rays.

Animation (EPIC, 16 frames x 2 ticks): the tail wags and the fins sway about a pixel, a
soft glint slides along the back, an iridescent sheen rolls over the scales from head to
tail, its colour shifting purple -> pink -> cyan with sparkling scale glints inside it,
and three enchanted sparkles twinkle around the fish in turn.
"""
from __future__ import annotations

import colorsys
import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_enchanted_koi"
NAME = "Enchanted Koi"
KIND = "item"
MODEL_KEY = "fish/enchanted_koi"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 16
FRAMETIME = 2

# ---- palettes (darkest -> lightest), from the old sprite's orange / gold / white ----------
OUTLINE = "#5a1a2e"                 # deep plum-maroon (hue-shifted, never black)
FIN_OUTLINE = ("#7a2e4a", 225)
ORANGE = ["#8e2a1c", "#b8401c", "#d85e20", "#ef8128", "#f9a23a", "#fdc257", "#ffe08a"]
CREAM = ["#e9b98a", "#f6d3a2", "#fde8c4", "#fff6e2"]
WHITE = ["#b8a0c8", "#d6c6e2", "#eee6f4", "#ffffff"]
LIP = ["#e0788a", "#f7a4ae"]
FIN = ["#c2402a", "#e2682c", "#f4904a", "#fbbd84", "#ffdbe6", "#f3e8ff"]
EYE = ["#1c0a2a", "#ffffff", "#e8b440"]
GLINT = "#fff6dc"
IRIDESCENT = ["#b27cff", "#ff78d2", "#6ef0ff"]   # purple -> pink -> cyan
SPARKS = ["#f3d4ff", "#aefcff", "#ffd2ef"]

# ---- geometry (pixels) --------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 22.5                      # snout to tail base
SNOUT = (2.0, 8.4)             # snout tip in the frame
SS = 4                         # supersampling per pixel side
TAIL_REST = 6.0


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


# Half-depths of the body above (TOP) and below (BOT) the axis: a blunt, round head, a deep
# arched back and belly, and a thick caudal peduncle.
TOP = _smooth([(0.0, 0.7), (0.8, 1.45), (1.8, 2.25), (3.0, 2.95), (4.5, 3.55), (6.5, 3.95), (8.8, 4.1),
               (11.3, 3.95), (13.8, 3.5), (16.3, 2.8), (18.8, 2.0), (21.0, 1.5), (22.5, 1.45)])
BOT = _smooth([(0.0, 0.75), (0.7, 1.3), (1.6, 1.95), (2.8, 2.6), (4.3, 3.25), (6.3, 3.9), (8.8, 4.35),
               (11.3, 4.3), (13.8, 3.8), (16.3, 2.95), (18.8, 2.1), (21.0, 1.55), (22.5, 1.45)])

_ys, _xs = np.mgrid[0:SIZE * SS, 0:SIZE * SS]
_SX, _SY = (_xs + 0.5) / SS - SNOUT[0], (_ys + 0.5) / SS - SNOUT[1]
U = _SX * AX[0] + _SY * AX[1]
V = _SX * UP[0] + _SY * UP[1]
_py, _px = np.mgrid[0:SIZE, 0:SIZE]
UC = (_px + 0.5 - SNOUT[0]) * AX[0] + (_py + 0.5 - SNOUT[1]) * AX[1]
VC = (_px + 0.5 - SNOUT[0]) * UP[0] + (_py + 0.5 - SNOUT[1]) * UP[1]


def _cov(mask: np.ndarray) -> np.ndarray:
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
    au, av = a
    bu, bv = b
    du, dv = bu - au, bv - av
    k = max(0.0, min(1.0, ((u - au) * du + (v - av) * dv) / (du * du + dv * dv)))
    return math.hypot(u - au - k * du, v - av - k * dv) <= reach


BODY = _cov((U >= 0) & (U <= SL + 0.3) & (V <= TOP(U)) & (V >= -BOT(U))) >= 0.5
BODY_ADD: list = []
BODY_CUT: list = []


def _mask() -> np.ndarray:
    mask = BODY.copy()
    for x, y in BODY_ADD:
        mask[y, x] = True
    for x, y in BODY_CUT:
        mask[y, x] = False
    return mask


MASK = _mask()


def _distance(mask: np.ndarray) -> np.ndarray:
    """Chebyshev steps from the body for every pixel (0 on the body)."""
    dist = np.where(mask, 0, 99)
    cur = mask.copy()
    for step in range(1, 10):
        grown = cur.copy()
        grown[1:, :] |= cur[:-1, :]
        grown[:-1, :] |= cur[1:, :]
        grown[:, 1:] |= cur[:, :-1]
        grown[:, :-1] |= cur[:, 1:]
        new = grown & ~cur
        dist[new] = step
        cur = grown
    return dist


DIST = _distance(MASK)


# ---- fins: (polygon, rays) in local coordinates; v > 0 is the back ------------------------

TAIL_PIVOT = (22.0, 0.0)


def _tail(sway: float, lag: float):
    """Broad koi tail with rounded lobes; the tips trail the root (lag) so it flows."""
    b = 0.55 * lag
    pts = [(21.6, 1.6), (23.4, 2.7), (25.4, 3.9), (27.3, 5.0 + b), (28.5, 4.9 + b), (28.6, 3.6 + b),
           (27.7, 2.0 + 0.5 * b), (26.8, 0.4), (26.9, -0.4), (27.8, -2.1 + 0.5 * b), (28.7, -3.8 + b),
           (28.3, -5.3 + b), (27.0, -5.5 + b), (25.2, -4.2), (23.3, -2.8), (21.6, -1.6)]
    rays = [((22.4, 0.9), (27.7, 4.4 + b)), ((22.6, 0.3), (26.8, 2.0 + 0.5 * b)),
            ((22.6, -0.3), (26.9, -2.2 + 0.5 * b)), ((22.4, -0.9), (27.6, -4.8 + b))]
    a = TAIL_REST + 5.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [_rot(r, TAIL_PIVOT, a) for r in rays]


def _dorsal(ripple: float):
    r = 0.45 * ripple
    pts = [(7.6, TOP(7.6) - 0.7), (8.6, TOP(8.6) + 3.4), (10.4 + r, TOP(10.4) + 3.6 + 0.3 * ripple),
           (13.4 + r, TOP(13.4) + 2.8), (16.6 + r, TOP(16.6) + 1.9), (18.9, TOP(18.9) - 0.6)]
    rays = [((9.0, TOP(9.0)), (9.3, TOP(9.3) + 3.2)), ((11.8, TOP(11.8)), (12.3 + r, TOP(12.3) + 3.0)),
            ((14.6, TOP(14.6)), (15.2 + r, TOP(15.2) + 2.1))]
    return pts, rays


def _pectoral(flap: float):
    """Big rounded paddle of a pectoral fin, low behind the gill cover."""
    pivot = (5.4, -BOT(5.4) + 1.0)
    pts = [(4.6, -BOT(4.6) + 1.6), (6.6, -BOT(6.6) + 1.3), (9.0, -BOT(9.0) - 0.4), (10.6, -BOT(10.6) - 2.1),
           (10.2, -BOT(10.2) - 3.4), (8.4, -BOT(8.4) - 3.9), (6.4, -BOT(6.4) - 2.8), (5.0, -BOT(5.0) - 0.6)]
    rays = [(pivot, (10.0, -BOT(10.0) - 3.0)), (pivot, (7.8, -BOT(7.8) - 3.4))]
    a = 11.0 * flap
    return _rot(pts, pivot, a), [_rot(r, pivot, a) for r in rays]


def _pelvic(flap: float):
    pivot = (12.0, -BOT(12.0))
    pts = [(11.4, -BOT(11.4) + 0.6), (12.8, -BOT(12.8) + 0.5), (15.0, -BOT(15.0) - 1.7),
           (13.6, -BOT(13.6) - 2.1)]
    rays = [(pivot, (14.2, -BOT(14.2) - 1.9))]
    a = 9.0 * flap
    return _rot(pts, pivot, a), [_rot(r, pivot, a) for r in rays]


def _anal(ripple: float):
    pts = [(16.4, -BOT(16.4) + 0.5), (17.3, -BOT(17.3) - 1.8 - 0.3 * ripple), (19.6, -BOT(19.6) - 1.3),
           (20.4, -BOT(20.4) + 0.5)]
    rays = [((17.6, -BOT(17.6)), (17.6, -BOT(17.6) - 1.7 - 0.3 * ripple))]
    return pts, rays


def _fin_colour(dist: int, ray: bool):
    """Orange at the root fading to pearl-lavender at the tips; rays a step darker."""
    i = {1: 1, 2: 1, 3: 2, 4: 3, 5: 4}.get(dist, 5 if dist > 5 else 1)
    if ray:
        i = max(0, i - 1)
    alpha = (228, 222, 212, 200, 192, 186)[i] if not ray else (238, 236, 230, 222, 214, 206)[i]
    return FIN[i], alpha


def _paint_fin(img, fin, t: float, cov_min=0.45, root=None, reach=1.2):
    """Paint a translucent fin: membrane plus rays, orange at the root fading to pearl at
    the edge. Distance counts from the body, or from a root point (u, v) when given. The
    pearl edge slowly cycles through the enchanted opal colours."""
    poly, rays = fin
    cov = _poly(poly)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if cov[y, x] < cov_min:
                continue
            u, v = UC[y, x], VC[y, x]
            ray = cov[y, x] >= 0.85 and any(_near(u, v, a, b, 0.42) for a, b in rays)
            if root is None:
                d = int(DIST[y, x])
            else:
                d = int(1 + math.hypot(u - root[0], v - root[1]) / reach)
            colour, a = _fin_colour(d, ray)
            if d >= 4:
                opal = hexc_(_iri(t + u / 22.0 + v / 30.0))
                colour = mix(colour, mix(opal, "#ffffff", 0.35), 0.3 if d == 4 else 0.45)
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)


# ---- body -------------------------------------------------------------------------------

def _bands(mask: np.ndarray):
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


def _white_patch(u: float, v: float, kb: int) -> bool:
    """Kohaku-style white markings: a face mask, a saddle draped over the back behind the
    dorsal fin's peak, and a band on the peduncle."""
    face = u < 3.2 + 0.3 * v and u < 4.0
    du, dv = (u - 12.6) / 2.7, (v - 3.6) / 2.4
    saddle = du * du + dv * dv + 0.18 * math.sin(3.0 * math.atan2(dv, du) + 0.6) < 1.0
    peduncle = kb <= 1 and 18.2 < u < 20.0
    return face or saddle or peduncle


def _scale_mark(x: int, y: int) -> bool:
    """Reticulated scale net: a staggered diamond lattice in image space."""
    return y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1


def _scale_light(x: int, y: int) -> bool:
    """The lit upper edge of each scale, just above-left of its mark."""
    return _scale_mark(x + 1, y + 1)


ZONE = {}


def _body_colour(x: int, y: int):
    """Colour and zone of a body pixel, from bands counted in from the back and belly."""
    kb, kv = int(KB[y, x]), int(KV[y, x])
    u, v = UC[y, x], VC[y, x]
    white = _white_patch(u, v, kb)
    net = _scale_mark(x, y) and u > 4.5
    lit = _scale_light(x, y) and u > 4.5
    if kv == 0:
        return (WHITE[1] if white else CREAM[0]), "belly"
    if kv == 1:
        return (WHITE[2] if white else CREAM[2] if u < 15 else CREAM[1]), "belly"
    if kv == 2 and u > 3.5 and not white:
        return ORANGE[6], "scales"
    if white:
        if kb == 0:
            return WHITE[3], "white"
        if net and kb >= 1:
            return WHITE[1], "white"
        return (WHITE[2] if kb >= 2 or u > 5 else WHITE[3]), "white"
    if kb == 0:
        return (ORANGE[5] if u < 9 else ORANGE[4]), "back"
    if kb == 1:
        return ORANGE[2], "back"
    if kb == 2:
        return (ORANGE[1] if net else ORANGE[4] if lit else ORANGE[3]), "scales"
    if net:
        return ORANGE[3], "scales"
    if lit:
        return ORANGE[6], "scales"
    if kv == 3:
        return ORANGE[5], "scales"
    return ORANGE[4], "scales"


def _loc(u: float, v: float):
    """Frame pixel containing local point (u, v)."""
    x = SNOUT[0] + u * AX[0] + v * UP[0]
    y = SNOUT[1] + u * AX[1] + v * UP[1]
    return int(math.floor(x)), int(math.floor(y))


def _head() -> dict:
    """Hand-placed head details: (x, y) -> (colour, zone)."""
    out = {}
    ex, ey = _loc(2.7, 0.1)
    out[(ex, ey)] = (EYE[1], "eye")                  # catchlight, top-left
    out[(ex + 1, ey)] = (EYE[0], "eye")
    out[(ex, ey + 1)] = (EYE[0], "eye")
    out[(ex + 1, ey + 1)] = (EYE[0], "eye")
    out[(ex + 2, ey + 1)] = (EYE[2], "eye")          # golden iris rim behind the pupil
    out[(ex + 1, ey + 2)] = (EYE[2], "eye")
    # gill cover: a curved plate edge behind the eye
    for vv in (1.6, 0.6, -0.4, -1.4, -2.4):
        gx, gy = _loc(5.2 + 0.06 * vv * vv, vv)
        if MASK[gy, gx] and KB[gy, gx] >= 1 and KV[gy, gx] >= 1:
            out[(gx, gy)] = (ORANGE[2], "head")
    # pink lips at the front of the snout, a dark mouth corner behind them
    out[(1, 9)] = (LIP[1], "head")
    out[(2, 10)] = (LIP[0], "head")
    return out


def _body() -> tuple[Image.Image, dict]:
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                c, z = _body_colour(x, y)
                px[x, y] = rgba(c)
                zones[(x, y)] = z
    for (x, y), (c, z) in _head().items():
        px[x, y] = rgba(c)
        zones[(x, y)] = z
    return img, zones


BARBELS = [((1, 11), 255), ((0, 12), 205)]
BARBEL = "#b8506a"


def _barbels(img):
    """Whisker barbels trailing down from the chin, drawn over the outline so they stay
    1 px thin."""
    px = img.load()
    c = rgba(BARBEL)
    for (x, y), a in BARBELS:
        px[x, y] = (c[0], c[1], c[2], a)


def _outline(img: Image.Image) -> Image.Image:
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
            if any(MASK[ny, nx] for nx, ny in near):
                dst[x, y] = rgba(OUTLINE)
            else:
                dst[x, y] = (fr, fg, fb, FIN_OUTLINE[1])
    return out


# ---- effects ------------------------------------------------------------------------------

def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _glint(img, zones, t: float, strength: float = 0.5, width: float = 3.0):
    """A soft glint sliding along the back during the first half of the loop."""
    run = 0.5
    if t >= run:
        return
    centre = -1.5 + (SL + 3.0) * (t / run)
    px = img.load()
    for (x, y), z in zones.items():
        kb = int(KB[y, x])
        if z == "eye" or kb < 0 or kb > 2:
            continue
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / width) * strength * (1.0 - kb / 3.5)
        if k > 0:
            _blend(px, x, y, GLINT, k)


def _iri(phase: float) -> tuple[float, float, float]:
    """Iridescent colour at a phase 0..1 cycling purple -> pink -> cyan -> purple."""
    n = len(IRIDESCENT)
    f = (phase % 1.0) * n
    i = int(f)
    a, b = rgba(IRIDESCENT[i % n]), rgba(IRIDESCENT[(i + 1) % n])
    s = f - i
    s = s * s * (3 - 2 * s)
    return tuple(a[j] + (b[j] - a[j]) * s for j in range(3))


def _iridescence(img, zones, t: float):
    """A wide opal sheen rolling over the body from head to tail once per loop. Its hue
    cycles purple -> pink -> cyan with position and time: on the pale belly and white
    markings it tints like mother-of-pearl, on the orange it turns the hue toward rose and
    violet (never through muddy green), and the lit scale edges inside it flash."""
    px = img.load()
    centre = -6.0 + (SL + 12.0) * t
    for (x, y), z in zones.items():
        if z == "eye":
            continue
        u, v = UC[y, x], VC[y, x]
        k = max(0.0, 1.0 - abs(u - centre) / 5.5)
        k = k * k * (3 - 2 * k)
        phase = u / 16.0 - v / 20.0 - 2 * t
        tint = _iri(phase)
        r, g, b, a = px[x, y]
        h, s, val = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        if z in ("belly", "white", "head") and s < 0.45:
            # pearl: tint the pale colour, keeping its brightness
            th, ts, tv = colorsys.rgb_to_hsv(*(c / 255 for c in tint))
            nr, ng, nb = colorsys.hsv_to_rgb(th, 0.1 + 0.32 * ts * (0.3 + 0.7 * k), val)
            _blend(px, x, y, (round(nr * 255), round(ng * 255), round(nb * 255)), 0.2 + 0.55 * k)
        else:
            # orange: rotate the hue backward (orange -> rose -> violet) inside the band
            th = colorsys.rgb_to_hsv(*(c / 255 for c in tint))[0]
            back = ((h - th) % 1.0)                  # how far back round the wheel the tint is
            turn = min(back, 0.16) * (0.06 + 0.64 * k)
            nr, ng, nb = colorsys.hsv_to_rgb((h - turn) % 1.0, s * (1 - 0.3 * k), min(1.0, val + 0.08 * k))
            px[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
        if k > 0.3 and z in ("scales", "back", "white") and _scale_light(x, y):
            pastel = mix(hexc_(tint), "#ffffff", 0.45)
            _blend(px, x, y, pastel, 0.8 * k)


def hexc_(c):
    return "#%02x%02x%02x" % tuple(round(v) for v in c[:3])


SPARKLE_SPOTS = [((8, 3), 0.0, 0), ((27, 11), 0.34, 1), ((4, 22), 0.67, 2)]


def _sparkles(img, t: float):
    for (x, y), off, ci in SPARKLE_SPOTS:
        w = wave(t, 0.5 - off)                       # peaks at t = off
        amount = max(0.0, (w - 0.45) / 0.55)
        sparkle(img, x, y, amount, SPARKS[ci], reach=2)


def _frame(t: float) -> Image.Image:
    sway = math.sin(2 * math.pi * t)
    lag = math.sin(2 * math.pi * (t - 0.12))
    ripple = math.sin(2 * math.pi * (t - 0.2))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    _paint_fin(img, _tail(sway, lag), t, cov_min=0.4, root=(21.2, 0.0), reach=1.3)
    _paint_fin(img, _dorsal(ripple), t)
    _paint_fin(img, _anal(ripple), t)
    body, zones = _body()
    _glint(body, zones, t)
    _iridescence(body, zones, t)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), t, root=(5.2, -BOT(5.2) + 1.2), reach=1.15)
    _paint_fin(front, _pelvic(flap), t, root=(11.8, -BOT(11.8) + 0.6), reach=0.9)
    img.alpha_composite(front)
    img = _outline(img)
    _barbels(img)
    _sparkles(img, t)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
