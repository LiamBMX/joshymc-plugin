"""Golden Carp: a rare fish of the JoshyMC fishing collection (rarity RARE).

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's all-gold scheme. A deep-bodied carp: an arched bronze back
rising behind a small blunt head, rows of big amber-edged scales over bright gold
flanks, a countershaded cream belly, a small down-turned mouth with a
barbel, a golden-ringed eye with a catchlight, a long low dorsal fin with a tall leading
spine, low pectoral and pelvic fins, a short anal fin and a broad forked tail (fins
translucent orange-gold with painted rays).

Animation (RARE, 12 frames x 3 ticks): the tail wags and the fins sway about a pixel on
offset phases, a warm glint slides along the back, then a cool blue-white sheen rolls
across the scales, while three sparkles twinkle around the fish in turn.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_golden_carp"
NAME = "Golden Carp"
KIND = "item"
MODEL_KEY = "fish/golden_carp"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean red-brown, lights lemon
OUTLINE = "#4a220e"
OUTLINE_LIT = "#6e3312"
FIN_OUTLINE = ("#82301a", 232)
BACK = ["#6a2c0e", "#96430f", "#c06514", "#dc861c", "#f0ab2c", "#ffd04e"]
GOLD = ["#b0600e", "#d27f16", "#eaa21f", "#f6c232", "#fdda4f", "#fff07e", "#fffbc8"]
BELLY = ["#e9b74c", "#f7d676", "#fde7a0", "#fff4cc"]
NET = ["#8f4a10", "#b0631a", "#c98021"]     # scale margins on back / flank / belly
FIN = ["#b0401a", "#d65e26", "#ee8232", "#f9a746", "#ffcb6a", "#ffe7a2"]
EYE = ["#1c0d06", "#ffffff"]
IRIS = ["#a86a12", "#f6d058"]
BARBEL = "#e6a24a"
GLINT = "#fffbe4"
SHEEN = "#b8dcff"        # cool blue-white sheen over the gold
SPARK = "#dff1ff"

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 20.6                      # snout to tail base
SNOUT = (3.8, 9.2)             # snout tip in the frame
SS = 4                         # supersampling per pixel side
TAIL_REST = 6.0                # the tail rests bent a little toward the back (degrees)


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


# Half-depths of the body above (TOP) and below (BOT) the axis: a small blunt head, then
# the carp's arched back (highest just ahead of the dorsal fin) and a deep belly.
TOP = _smooth([(0.0, 0.9), (0.7, 1.75), (1.6, 2.55), (2.8, 3.25), (4.2, 3.95), (5.8, 4.55), (7.4, 4.9),
               (9.0, 5.0), (10.8, 4.85), (12.8, 4.3), (14.8, 3.45), (16.8, 2.6), (18.8, 2.0),
               (20.6, 1.8)])
BOT = _smooth([(0.0, 0.9), (0.7, 1.6), (1.6, 2.3), (2.8, 3.0), (4.2, 3.75), (5.8, 4.4), (7.4, 4.85),
               (9.0, 5.1), (10.8, 5.05), (12.8, 4.5), (14.8, 3.6), (16.8, 2.65), (18.8, 2.0),
               (20.6, 1.8)])

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


BODY = _cov((U >= 0) & (U <= SL + 0.3) & (V <= TOP(U)) & (V >= -BOT(U))) >= 0.5


# ---- fins: (polygon, rays) in local coordinates; v > 0 is the back ----------------------

TAIL_PIVOT = (20.6, 0.0)


def _tail(sway: float):
    """Broad forked tail with rounded lobes."""
    pts = [(20.0, 1.7), (21.6, 2.6), (23.4, 3.8), (25.2, 5.0), (26.3, 5.3), (26.6, 4.6), (26.0, 3.2),
           (24.9, 1.6), (24.3, 0.0),
           (24.9, -1.6), (26.0, -3.2), (26.6, -4.6), (26.3, -5.3), (25.2, -5.0), (23.4, -3.8), (21.6, -2.6),
           (20.0, -1.7)]
    rays = [((21.2, 1.0), (25.8, 4.6), FIN[1]), ((21.2, -1.0), (25.8, -4.6), FIN[1]),
            ((21.4, 0.0), (23.9, 1.3), FIN[1]), ((21.4, 0.0), (23.9, -1.3), FIN[1])]
    a = TAIL_REST + 9.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _dorsal(ripple: float):
    """Long carp dorsal: a tall leading spine, then a long soft-rayed section."""
    r = 0.45 * ripple
    tip = (8.6 + r, TOP(8.6) + 3.9)
    pts = [(7.3, TOP(7.3) - 0.6), (7.6, TOP(7.6) + 1.4), tip, (10.0 + r, TOP(10.0) + 2.9),
           (12.8 + r, TOP(12.8) + 2.5), (15.4 + r, TOP(15.4) + 2.1), (16.9, TOP(16.9) + 0.6),
           (16.9, TOP(16.9) - 0.6)]
    rays = [((7.8, TOP(7.8)), tip, FIN[4]),                                      # lit leading spine
            ((10.4, TOP(10.4)), (11.2 + r, TOP(11.2) + 2.6), FIN[1]),
            ((13.2, TOP(13.2)), (14.0 + r, TOP(14.0) + 2.2), FIN[1])]
    return pts, rays


def _pectoral(flap: float):
    pivot = (5.4, -BOT(5.4) + 1.0)
    pts = [(4.9, -BOT(4.9) + 1.6), (6.3, -BOT(6.3) + 1.3), (9.6, -BOT(9.6) - 0.4), (9.0, -BOT(9.0) - 1.4),
           (5.6, -BOT(5.6) + 0.1)]
    rays = [(pivot, (9.2, -BOT(9.2) - 0.8), FIN[2])]
    a = 10.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _pelvic(flap: float):
    pivot = (10.3, -BOT(10.3))
    pts = [(9.6, -BOT(9.6) + 0.5), (11.2, -BOT(11.2) + 0.5), (13.0, -BOT(13.0) - 1.9),
           (11.4, -BOT(11.4) - 2.1)]
    rays = [(pivot, (12.2, -BOT(12.2) - 1.9), FIN[2])]
    a = 9.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _anal(ripple: float):
    r = 0.3 * ripple
    pts = [(14.6, -BOT(14.6) + 0.5), (15.0, -BOT(15.0) - 2.3 - r), (16.3, -BOT(16.3) - 2.2 - r),
           (18.0, -BOT(18.0) - 0.9), (18.4, -BOT(18.4) + 0.5)]
    rays = [((15.2, -BOT(15.2)), (15.3, -BOT(15.3) - 2.1 - r), FIN[1])]
    return pts, rays


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=228, lit=None):
    """Paint a translucent fin: membrane plus rays (segments with their own colour). With
    lit, fin pixels whose upper or left neighbour is open water catch the top-left light."""
    poly, rays = fin
    cov = _poly(poly)
    inside = (cov >= cov_min) & ~MASK
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if cov[y, x] < cov_min:
                continue
            u, v = UC[y, x], VC[y, x]
            colour, a = membrane, alpha
            for ra, rb, rc in rays:
                if cov[y, x] >= 0.9 and _near(u, v, ra, rb, 0.45):
                    colour, a = rc, ray_alpha
                    break
            if lit and inside[y, x] and ((y > 0 and not inside[y - 1, x] and not MASK[y - 1, x])
                                         or (x > 0 and not inside[y, x - 1] and not MASK[y, x - 1])):
                colour = lit
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)


# ---- body -------------------------------------------------------------------------------
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

LETTERS = {"o": OUTLINE, "K": EYE[0], "W": EYE[1], "n": IRIS[0], "N": IRIS[1], "m": BACK[0]}
LETTERS.update({k: c for k, c in zip("abcdef", BACK)})
LETTERS.update({k: c for k, c in zip("0123456", GOLD)})
LETTERS.update({k: c for k, c in zip("wxyz", BELLY)})

# Head details: (x, y) -> letter. Eye with a catchlight in a golden iris, a small
# down-turned mouth at the snout tip.
HEAD = {
    (6, 9): "W", (7, 9): "K", (8, 9): "N",
    (6, 10): "K", (7, 10): "K", (8, 10): "n",
    (6, 11): "n", (7, 11): "n",
    (3, 11): "a", (4, 12): "m",
}
# Barbel: a short whisker hanging from the corner of the mouth (outside the body mask).
BARBEL_PX = [(3, 13), (2, 14)]

GILL_U = 5.4                  # the gill cover's rear edge (local u)


def _pn(y: int, x: int) -> float:
    """Height across the body: 0 at the belly contour, 1 at the back contour."""
    u, v = UC[y, x], VC[y, x]
    top, bot = float(TOP(u)), float(BOT(u))
    return (v + bot) / (top + bot)


def _gill(x: int, y: int) -> float:
    """Signed distance (local u) from the gill cover's curved rear edge."""
    v = VC[y, x]
    return UC[y, x] - (GILL_U - 0.06 * v * v)


SCALE_STAMP = ["...#.",
               ".+..#",
               "...#."]


SCALE_SHIFT = 3


def _scale(x: int, y: int):
    """Big carp scales: rows of ")" arcs (the free rear margin of each scale, the fish
    faces left) staggered brick-wise. Returns 'edge', 'lit' or None."""
    h, w = len(SCALE_STAMP), len(SCALE_STAMP[0])
    band = y // h
    ch = SCALE_STAMP[y % h][(x + SCALE_SHIFT * band) % w]
    return {"#": "edge", "+": "lit"}.get(ch)


def _band_colour(x: int, y: int) -> str:
    kb, kv = int(KB[y, x]), int(KV[y, x])
    p = _pn(y, x)
    u = UC[y, x]
    if kb == 0:
        return BACK[5] if u < 9 else BACK[4]         # rim light along the back
    if kv == 0:
        return BELLY[0] if u > 3 else GOLD[3]         # belly contour in soft shadow
    # base tones, back (bronze pigment) to belly (cream), with a lit flank horizon; the
    # second colour is the scale margin drawn on that tone
    if p > 0.86:
        base, net = BACK[2], BACK[1]
    elif p > 0.74:
        base, net = BACK[3], BACK[2]
    elif p > 0.6:
        base, net = GOLD[3], GOLD[1]
    elif p > 0.46:
        base, net = GOLD[5], GOLD[3]
    elif p > 0.32:
        base, net = GOLD[4], GOLD[2]
    elif p > 0.18:
        base, net = BELLY[2], BELLY[0]
    else:
        base, net = BELLY[1], BELLY[0]
    g = _gill(x, y)
    if g < 0:                                          # head: smooth, no scales
        if g > -0.75:
            return BACK[2] if p > 0.6 else NET[2]      # gill cover rim
        if g > -1.5 and p < 0.85:
            return GOLD[6] if p > 0.4 else base        # the cover's lit bevel
        return base
    kind = _scale(x, y) if u < 18.5 and 0.18 < p <= 0.8 else None
    if kind == "edge":
        return net
    if kind == "lit" and 0.46 < p < 0.74:
        return GOLD[6]
    return base


def _body() -> Image.Image:
    img = canvas(SIZE)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                px[x, y] = rgba(_band_colour(x, y))
    for (x, y), letter in HEAD.items():
        px[x, y] = rgba(LETTERS[letter])
    for x, y in BARBEL_PX:
        px[x, y] = rgba(BARBEL)
    return img


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
                # selective outline: a touch warmer where the body lies below / right of
                # this pixel, the side facing the top-left light
                lit = ((y + 1 < SIZE and MASK[y + 1, x]) or (x + 1 < SIZE and MASK[y, x + 1])) and                     not (y > 0 and src[x, y - 1][3]) and not (x > 0 and src[x - 1, y][3])
                dst[x, y] = rgba(OUTLINE_LIT if lit else OUTLINE)
            else:
                dst[x, y] = (fr, fg, fb, FIN_OUTLINE[1])
    return out


def _glint(img: Image.Image, t: float, strength: float = 0.72, width: float = 3.0, run: float = 5 / 12):
    """A soft warm glint sliding along the back from snout to tail during the first part
    of the loop (a shine() band that follows the fish's own axis)."""
    if t >= run:
        return
    centre = -1.5 + (SL + 3.0) * (t / run)
    px = img.load()
    c = rgba(GLINT)
    for y in range(SIZE):
        for x in range(SIZE):
            kb = int(KB[y, x])
            if kb < 0 or kb > 3 or HEAD.get((x, y), "") in ("W", "K", "n", "N", "m"):
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / width) * strength * (1.0 - kb / 4.5)
            if k > 0:
                px[x, y] = rgba(mix(px[x, y], c, k))


def _sheen(img: Image.Image, t: float, start: float = 6 / 12, run: float = 5 / 12):
    """The rare fish's cool sheen: a blue-white band rolling across the scales from head to
    tail in the second part of the loop, the lit scale centres flashing as it passes."""
    if not start <= t < start + run:
        return
    centre = 2.0 + (SL - 1.0) * ((t - start) / (run - 1 / 12))
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if not MASK[y, x] or HEAD.get((x, y)) in ("W", "K", "n", "N", "m"):
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.0)
            if k <= 0:
                continue
            lit = _scale(x, y) == "lit" or (int(KB[y, x]) == 0)
            strength = (0.85 if lit else 0.3) * min(1.0, k * 1.35)
            px[x, y] = rgba(mix(px[x, y], SPARK if lit else SHEEN, strength))


# Twinkles around the fish: (x, y, phase, reach).
SPARKLES = [(23, 5, 0.0, 2), (6, 24, 0.36, 2), (28, 11, 0.68, 1)]


def _twinkle(t: float, phase: float) -> float:
    """0 most of the loop, rising to 1 and back over about 5 frames."""
    w = wave(t, 0.5 - phase)
    return max(0.0, (w - 0.45) / 0.55)


def _frame(t: float) -> Image.Image:
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    # Medial fins sit behind the body; the near pectoral and pelvic fins lie over it.
    _paint_fin(img, _tail(sway), FIN[3], 210, cov_min=0.4, lit=FIN[4])
    _paint_fin(img, _dorsal(ripple), FIN[3], 200, lit=FIN[5])
    _paint_fin(img, _anal(ripple), FIN[3], 200)
    body = _body()
    _glint(body, t)
    _sheen(body, t)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), FIN[4], 205)
    _paint_fin(front, _pelvic(flap), FIN[4], 205)
    img.alpha_composite(front)
    img = _outline(img)
    for x, y, phase, reach in SPARKLES:
        amount = _twinkle(t, phase)
        sparkle(img, x, y, amount, colour=SPARK, reach=reach)
        sparkle(img, x, y, amount * 0.6, colour="#ffffff", reach=0)     # hot white core
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
