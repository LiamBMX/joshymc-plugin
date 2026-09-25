"""Glacial Swordfish: an epic billfish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's pale periwinkle-ice scheme with its cyan-white highlights.
Swordfish anatomy: the long flat sword (an icicle-clear crystal blade, white at the tip)
growing out of the upper jaw over a short pointed lower jaw and a wide gape, a big eye
with an ice-cyan iris, a gill-cover arc, the tall rigid sickle of the first dorsal fin set
just behind the head, the long falcate pectoral lying back along the belly, small anal and
second dorsal finlets by the tail, and a big crisp lunate crescent tail. The body and tail
are painted pixel by pixel: icy rim light, deep glacier-blue back with frost flecks, a cyan
horizon line, pale ice flanks with scale pips, a snow-white belly, translucent ice fins
with painted rays.

Animation (EPIC, 16 frames x 2 ticks): the crescent tail beats with its tips leading and
the fins sway a pixel, an iridescent sheen of cyan, violet and pink drifts continuously
across the scales with a brighter swell rolling head to tail, a soft glint runs down the
sword and along the back, a shimmer band of sparkling scales follows it, and icy sparkles
twinkle in turn around the fish and on the sword tip.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_glacial_swordfish"
NAME = "Glacial Swordfish"
KIND = "item"
MODEL_KEY = "fish/glacial_swordfish"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 16
FRAMETIME = 2

# ---- palettes (darkest -> lightest), from the old sprite's periwinkle / ice / cyan -------
OUTLINE = "#172456"
FIN_OUTLINE = ("#34508e", 232)
SWORD_OUTLINE = "#1e3b86"
BACK = ["#1d2d66", "#253f84", "#30589e", "#4072b8", "#5f95d2", "#8fbdea"]
AQUA = ["#7fd6ea", "#aaeef6", "#d6fbff"]
ICE = ["#6c8fd0", "#86a9e0", "#9fc2ee", "#b8d6f6", "#d0e6fb", "#e6f3ff", "#f8fcff"]
FIN = ["#4a6cb4", "#6186c6", "#7ea3da", "#9fc1ea", "#c3dcf6", "#e3f0ff"]
SWORD = ["#2e5aa4", "#4d85c6", "#7fb8e6", "#b2e0f8", "#e2f8ff", "#ffffff"]
EYE = ["#0c1233", "#ffffff"]
IRIS = "#5fb8e8"
GLINT = "#f4feff"
FROST = "#e9f8ff"
IRIDESCENT = ["#7af0ff", "#b08cff", "#ff9ad8", "#9fb4ff"]   # cyan, violet, pink, periwinkle
SPARK = "#f2fdff"

# ---- geometry (pixels) ------------------------------------------------------------------
THETA = math.radians(25.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SNOUT = (1.7, 8.7)           # sword tip in the frame
UB = 8.0                     # where the sword meets the head
SL = 23.6                    # sword tip to tail base
SS = 4


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


# Half-depths of the body above (TOP) and below (BOT) the axis, from the sword base back.
TOP = _smooth([(UB, 1.0), (9.1, 1.6), (10.4, 2.45), (12.0, 3.3), (13.8, 3.85), (15.6, 3.95), (17.6, 3.6),
               (19.6, 2.85), (21.4, 1.95), (22.8, 1.25), (SL, 1.0)])
BOT = _smooth([(UB, 0.05), (8.9, 1.0), (10.2, 2.0), (12.0, 3.1), (13.8, 3.8), (15.6, 3.9), (17.6, 3.5),
               (19.6, 2.7), (21.4, 1.8), (22.8, 1.15), (SL, 1.0)])

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


# ---- the body, hand painted ------------------------------------------------------------
# Sword, head and body are painted pixel by pixel (the fins stay procedural so they can
# sway). Bands run parallel to the back: icy rim light, deep glacier-blue back with frost
# flecks, a cyan horizon line, pale ice flanks with scale pips, a snow-white belly.
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    "................................",  # 6
    "................................",  # 7
    "..G.............................",  # 8
    "...EED..........................",  # 9
    ".....BDD........................",  # 10
    ".......BCefffffee...............",  # 11
    "........4adWKcbFbfe.............",  # 12
    ".........5aKKIhbbbbe............",  # 13
    "..........43rrgrccFbe...........",  # 14
    "..........x53g23rrccbd..........",  # 15
    "...........x564232rrcb..........",  # 16
    "............x5664433rcd.........",  # 17
    ".............xxx6655xqbc........",  # 18
    "................xxxx............",  # 19
    "................................",  # 20
]
ART += ["." * SIZE] * (SIZE - len(ART))

LETTERS = {"o": OUTLINE, "K": EYE[0], "W": EYE[1], "I": IRIS, "F": FROST}
LETTERS.update({k: c for k, c in zip("abcdef", BACK)})
LETTERS.update({k: c for k, c in zip("qrs", AQUA)})
LETTERS.update({k: c for k, c in zip("0123456", ICE)})
LETTERS.update({k: c for k, c in zip("ABCDEG", SWORD)})
LETTERS.update({"x": ICE[2], "g": ICE[1], "h": BACK[0]})

ZONE_OF = {"q": "horizon", "r": "horizon", "s": "horizon", "F": "frost", "x": "belly", "5": "belly",
           "6": "belly", "g": "gill", "h": "gill", "W": "head", "K": "head", "I": "head"}
ZONE_OF.update({k: "back" for k in "abcdef"})
ZONE_OF.update({k: "flank" for k in "01234"})
ZONE_OF.update({k: "sword" for k in "ABCDEG"})
HEAD_AT = {(8, 12), (9, 12), (9, 13), (10, 12), (10, 13), (10, 14)}   # jaw, gape and cheek


def _masks():
    body = np.zeros((SIZE, SIZE), bool)
    blade = np.zeros((SIZE, SIZE), bool)
    for y, row in enumerate(ART):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            if ZONE_OF[ch] == "sword":
                blade[y, x] = True
            else:
                body[y, x] = True
    return body, blade


MASK, BLADE = _masks()
SOLID = MASK | BLADE
# Rows below the top of each body column (0 = the rim), for the glint's falloff.
KTOP = np.full((SIZE, SIZE), -1)
for _x in range(SIZE):
    _col = np.nonzero(MASK[:, _x])[0]
    for _y in _col:
        KTOP[_y, _x] = _y - _col[0]


# ---- fins: (polygon, rays) in local coordinates; v > 0 is the back -----------------------

# The lunate tail, hand drawn: a crisp crescent (L = lit leading edge, T = ray, t = membrane).
TAIL = {
    (29, 12): "L",
    (28, 13): "L", (29, 13): "t",
    (27, 14): "L", (28, 14): "T",
    (26, 15): "L", (27, 15): "T", (28, 15): "t",
    (25, 16): "L", (26, 16): "T", (27, 16): "t",
    (24, 17): "t", (25, 17): "T", (26, 17): "t",
    (24, 18): "t", (25, 18): "t",
    (24, 19): "t", (25, 19): "T", (26, 19): "t",
    (24, 20): "L", (25, 20): "T", (26, 20): "t",
    (25, 21): "L", (26, 21): "T",
    (25, 22): "L", (26, 22): "T", (27, 22): "t",
    (26, 23): "L", (27, 23): "t",
    (27, 24): "L",
}
TAIL_BASE = (23.6, 18.6)      # where the tail joins the peduncle


def _tail(near: int, far: int) -> dict:
    """The tail flexed: pixels 2-3.5 px out from the root move `near` rows, the lobes
    further out move `far` rows (positive: down), so the tips lead the sway."""
    if near == 0 and far == 0:
        return dict(TAIL)
    out = {}
    for (x, y), ch in TAIL.items():
        d = math.hypot(x + 0.5 - TAIL_BASE[0], y + 0.5 - TAIL_BASE[1])
        dy = far if d >= 3.6 else near if d >= 2.0 else 0
        out.setdefault((x, y + dy), ch)
    step = 1 if far > 0 else -1
    for (x, y) in TAIL:
        if (x, y) not in out and (x, y - step) in out and (x, y + step) in out:
            out[(x, y)] = "t"
    return out


def _paint_tail(img, sway: float):
    near, far = round(0.6 * sway), round(1.3 * sway)
    px = img.load()
    looks = {"L": (FIN[4], 222), "T": (FIN[1], 230), "t": (FIN[2], 212)}
    for (x, y), ch in _tail(near, far).items():
        if SOLID[y, x]:
            continue
        colour, alpha = looks[ch]
        c = rgba(colour)
        px[x, y] = (c[0], c[1], c[2], alpha)


def _dorsal(ripple: float):
    tip = (13.6 + 0.35 * ripple, TOP(13.0) + 5.4)
    pts = [(11.0, TOP(11.0) - 0.6), (12.0, TOP(12.0) + 2.6), tip, (13.7 + 0.3 * ripple, TOP(13.7) + 3.6),
           (14.0, TOP(14.0) + 1.6), (15.6, TOP(15.6) + 0.5), (16.4, TOP(16.4) - 0.6)]
    rays = [((11.3, TOP(11.3)), tip, FIN[5]),
            ((13.0, TOP(13.0)), (13.6 + 0.3 * ripple, TOP(13.6) + 3.4), FIN[1])]
    return pts, rays


def _dorsal2(ripple: float):
    pts = [(21.4, TOP(21.4) - 0.5), (22.3 + 0.2 * ripple, TOP(22.3) + 1.7), (22.9, TOP(22.9) - 0.3)]
    return pts, []


def _anal(ripple: float):
    pts = [(19.6, -BOT(19.6) + 0.5), (20.5 + 0.2 * ripple, -BOT(20.5) - 1.9), (21.2, -BOT(21.2) - 1.3),
           (21.9, -BOT(21.9) + 0.4)]
    rays = [((20.0, -BOT(20.0)), (20.5 + 0.2 * ripple, -BOT(20.5) - 1.7), FIN[1])]
    return pts, rays


def _pectoral(flap: float):
    pivot = (12.4, -BOT(12.4) + 0.9)
    pts = [(11.9, -BOT(11.9) + 1.3), (13.6, -BOT(13.6) + 0.7), (16.2, -BOT(16.2) - 0.5),
           (18.8, -BOT(18.8) - 1.9), (17.6, -BOT(17.6) - 2.3), (15.2, -BOT(15.2) - 1.8),
           (13.3, -BOT(13.3) - 0.8)]
    rays = [((12.8, -BOT(12.8) + 0.8), (18.2, -BOT(18.2) - 1.9), FIN[4])]
    a = 7.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=228, lit=None, over=False):
    poly, rays = fin
    cov = _poly(poly)
    inside = (cov >= cov_min) & (over | ~SOLID)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if cov[y, x] < cov_min:
                continue
            u, v = UC[y, x], VC[y, x]
            colour, a = membrane, alpha
            for ra, rb, rc in rays:
                if cov[y, x] >= 0.85 and _near(u, v, ra, rb, 0.45):
                    colour, a = rc, ray_alpha
                    break
            if lit and inside[y, x] and ((y > 0 and not inside[y - 1, x] and not SOLID[y - 1, x])
                                         or (x > 0 and not inside[y, x - 1] and not SOLID[y, x - 1])):
                colour = lit
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)


# ---- body -------------------------------------------------------------------------------

ZONES: dict = {}     # (x, y) -> zone name for the effects, filled by _body()


def _body() -> Image.Image:
    img = canvas(SIZE)
    px = img.load()
    ZONES.clear()
    for y, row in enumerate(ART):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            px[x, y] = rgba(LETTERS[ch])
            ZONES[(x, y)] = "head" if (x, y) in HEAD_AT else ZONE_OF[ch]
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
                dst[x, y] = rgba(OUTLINE)
            elif any(BLADE[ny, nx] for nx, ny in near):
                dst[x, y] = rgba(SWORD_OUTLINE)
            else:
                dst[x, y] = (fr, fg, fb, FIN_OUTLINE[1])
    return out


# ---- effects ------------------------------------------------------------------------------

def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    k = max(0.0, min(1.0, k))
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _iridescent(t: float) -> str:
    """Cyclic cyan -> violet -> pink -> periwinkle colour at phase t."""
    t %= 1.0
    n = len(IRIDESCENT)
    i = int(t * n)
    return mix(IRIDESCENT[i], IRIDESCENT[(i + 1) % n], t * n - i)


def _sheen(img: Image.Image, t: float):
    """An iridescent sheen drifting along the scales: the hue cycles cyan -> violet -> pink
    across the body, and a broad brighter swell of it travels head to tail once a loop."""
    px = img.load()
    for (x, y), zone in ZONES.items():
        if zone not in ("flank", "horizon", "belly", "back", "gill"):
            continue
        u, v = UC[y, x], VC[y, x]
        phase = u / 12.0 - v / 16.0 - t
        swell = 0.5 + 0.5 * math.cos(2 * math.pi * (u / 17.0 - t))
        base, gain = {"flank": (0.28, 0.36), "horizon": (0.22, 0.3), "belly": (0.1, 0.18), "back": (0.06, 0.12),
                      "gill": (0.2, 0.25)}[zone]
        _blend(px, x, y, _iridescent(phase), base + gain * swell)


def _glint(img: Image.Image, t: float, start=0.0, run=0.4, strength=0.6, width=3.0):
    """A soft glint sliding along the sword and the back, snout to tail."""
    if not (start <= t < start + run):
        return
    centre = -1.5 + (SL + 3.0) * ((t - start) / run)
    px = img.load()
    for (x, y), zone in ZONES.items():
        if zone not in ("back", "sword"):
            continue
        kb = int(KTOP[y, x]) if zone == "back" else 0
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / width) * strength * (1.0 - kb / 4.5)
        if k > 0:
            _blend(px, x, y, GLINT, k)


def _shimmer(img: Image.Image, t: float, start=0.5, run=0.375):
    """A band of sparkling scales rolling from head to tail."""
    if not (start <= t < start + run):
        return
    centre = 9.0 + 17.0 * ((t - start) / run)
    px = img.load()
    for (x, y), zone in ZONES.items():
        if zone not in ("flank", "belly", "horizon", "back", "gill"):
            continue
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.0)
        if k <= 0:
            continue
        spark = y % 2 == 1 and (x + y // 2) % 2 == 0
        _blend(px, x, y, SPARK if spark else AQUA[2], (0.9 if spark else 0.32) * min(1.0, k * 1.3))


# Icy twinkles in the open water: (x, y, phase offset, reach)
SPARKLES = [(6, 4, 0.0, 2), (26, 9, 0.36, 2), (11, 23, 0.68, 2), (2, 8, 0.55, 1)]   # last: the sword tip


def _sparkles(img: Image.Image, t: float):
    for x, y, off, reach in SPARKLES:
        w = math.sin(2 * math.pi * (t + off))
        amount = max(0.0, (w - 0.25) / 0.75)
        sparkle(img, x, y, amount, colour=SPARK, reach=reach)


def _frame(t: float) -> Image.Image:
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    _paint_tail(img, sway)
    _paint_fin(img, _dorsal(ripple), FIN[2], 208, lit=FIN[5])
    _paint_fin(img, _dorsal2(ripple), FIN[2], 205)
    _paint_fin(img, _anal(ripple), FIN[2], 205)
    body = _body()
    _sheen(body, t)
    _glint(body, t)
    _shimmer(body, t)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), FIN[1], 210, over=True, lit=FIN[4])
    img.alpha_composite(front)
    img = _outline(img)
    _sparkles(img, t)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
