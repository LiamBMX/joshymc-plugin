"""Warden's Catch: an epic deep-dark anglerfish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection's shared pose (side view, head up-left, tail
down-right), keeping the old sprite's scheme: a near-black sculk-teal body, a milky
blind eye and a golden lure. A deep, big-headed angler: a lit rim along the humped back,
a countershaded slate-teal belly, a gaping upturned mouth full of needle fangs, a lure
stalk arching forward from the forehead to a glowing golden bulb, a warden's sculk heart
glowing through the flank behind the gills with a line of cyan photophores running back
from it, a rear-set dorsal and anal fin, a low paddle pectoral and a fan tail
(translucent, painted rays, cyan-tipped).

Animation (EPIC, 16 frames x 3 ticks): the tail and fins sway about a pixel, the heart
throbs with the warden's double heartbeat and the glow runs out along the photophores to
the tail tips, the lure's bulb and halo breathe, a soft glint runs along the back, an
iridescent sheen (cyan leading, pink, violet trailing) rolls across the scales from head
to tail, and cyan, pink and gold twinkles sparkle around the fish.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_wardens_catch"
NAME = "Warden's Catch"
KIND = "item"
MODEL_KEY = "fish/wardens_catch"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 16
FRAMETIME = 3

# ---- palettes (darkest -> lightest) ------------------------------------------------------
OUTLINE = "#050d1a"
FIN_OUTLINE = ("#0b2130", 232)
# body: shadows drift to indigo, lights to sea green (the old sprite's #0f5154 / #1b555e)
BODY = ["#07101d", "#0a192a", "#0e2336", "#123043", "#194051", "#235662", "#347278", "#55968f"]
BELLY = ["#163847", "#1e4856", "#2a5b67", "#3a6f78", "#518588"]
SCULK = ["#0b5660", "#11818a", "#20b2b4", "#4ee6dc", "#b6fff2"]
GOLD = ["#6e3f10", "#b8741c", "#f0ae2c", "#ffc832", "#fff1a6"]
FIN = ["#0c2533", "#12344a", "#1b4b5e", "#2b6670", "#46878a", "#6fb0aa"]
EYE = ["#274f5a", "#7fb4b3", "#bfe4dc", "#ffffff"]
MOUTH = ["#07061a", "#150f2e", "#2a1d44"]
TOOTH = ["#8fa9a6", "#d6ece4", "#f4fffa"]
GLINT = "#d4fff6"
IRIDESCENT = ["#4dfcff", "#ff70d0", "#9c6bff"]  # leading, middle, trailing edge of the sheen

# ---- geometry (pixels) --------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(25.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 22.0                      # snout to tail base
SNOUT = (3.5, 10.6)            # snout tip in the frame
SS = 4                         # supersampling per pixel side
TAIL_REST = 8.0                # the tail rests bent a little toward the back (degrees)


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


# Half-depths above (TOP) and below (BOT) the axis: a huge head tapering to a thin tail stock.
TOP = _smooth([(0.0, 1.0), (0.9, 2.1), (2.0, 3.2), (3.4, 4.1), (5.0, 4.6), (7.0, 4.75), (9.0, 4.45),
               (11.0, 3.85), (13.0, 3.1), (15.0, 2.45), (17.0, 1.9), (19.0, 1.5), (21.5, 1.3)])
BOT = _smooth([(0.0, 1.6), (0.9, 2.7), (2.0, 3.7), (3.4, 4.5), (5.0, 5.0), (7.0, 5.15), (9.0, 4.9),
               (11.0, 4.2), (13.0, 3.3), (15.0, 2.55), (17.0, 1.95), (19.0, 1.5), (21.5, 1.3)])

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


BODY_MASK = _cov((U >= 0) & (U <= SL + 0.3) & (V <= TOP(U)) & (V >= -BOT(U))) >= 0.5
# the jutting lower jaw of an angler
JAW = _poly([(-1.3, -0.2), (0.4, -0.4), (2.5, -1.4), (1.5, -2.9), (-0.8, -1.6)]) >= 0.5

# ---- fins: (polygon, rays) in local coordinates; v > 0 is the back ------------------------

TAIL_PIVOT = (21.2, 0.0)


def _tail(sway: float):
    """A broad fan tail with a shallow notch."""
    pts = [(20.6, 1.3), (22.4, 2.4), (24.6, 3.9), (26.9, 4.6), (27.6, 3.3), (27.3, 1.6), (26.8, 0.0),
           (27.3, -1.6), (27.6, -3.3), (26.9, -4.6), (24.6, -3.9), (22.4, -2.4), (20.6, -1.3)]
    rays = [((21.8, 0.9), (26.6, 3.9), FIN[4]), ((21.8, -0.9), (26.6, -3.9), FIN[1]),
            ((22.0, 0.3), (26.6, 1.3), FIN[1]), ((22.0, -0.3), (26.6, -1.3), FIN[1])]
    a = TAIL_REST + 7.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _dorsal(ripple: float):
    """The soft dorsal fin, set far back as in anglers."""
    tip = (14.9 + 0.5 * ripple, TOP(14.9) + 3.2)
    pts = [(12.6, TOP(12.6) - 0.6), (13.3, TOP(13.3) + 2.2), tip, (17.4 + 0.5 * ripple, TOP(17.4) + 2.1),
           (18.6, TOP(18.6) + 0.4), (18.6, TOP(18.6) - 0.6)]
    rays = [((13.2, TOP(13.2)), (13.9, TOP(13.9) + 2.6), FIN[4]),
            ((15.2, TOP(15.2)), tip, FIN[1]),
            ((16.9, TOP(16.9)), (17.3 + 0.5 * ripple, TOP(17.3) + 1.9), FIN[1])]
    return pts, rays


def _anal(ripple: float):
    pts = [(14.2, -BOT(14.2) + 0.6), (15.2, -BOT(15.2) - 2.2 - 0.3 * ripple), (17.8, -BOT(17.8) - 1.8),
           (19.2, -BOT(19.2) + 0.5)]
    rays = [((15.4, -BOT(15.4)), (15.6, -BOT(15.6) - 2.0 - 0.3 * ripple), FIN[1]),
            ((17.2, -BOT(17.2)), (17.6, -BOT(17.6) - 1.5), FIN[1])]
    return pts, rays


def _pectoral(flap: float):
    """A paddle pectoral just behind the gill slit, lying over the flank."""
    pivot = (8.9, -2.6)
    pts = [(8.5, -1.9), (9.7, -1.8), (12.3, -3.4), (12.6, -4.6), (11.3, -5.2), (9.2, -3.6)]
    rays = [(pivot, (11.9, -4.3), FIN[2]), ((9.3, -2.0), (12.1, -3.4), FIN[5])]
    a = 12.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=226, lit=None, mask=None):
    poly, rays = fin
    cov = _poly(poly)
    body = MASK if mask is None else mask
    inside = (cov >= cov_min) & ~body
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
            if lit and inside[y, x] and ((y > 0 and not inside[y - 1, x] and not body[y - 1, x])
                                         or (x > 0 and not inside[y, x - 1] and not body[y, x - 1])):
                colour = lit
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)


# ---- body ---------------------------------------------------------------------------------

BODY_ADD = [(2, 12), (2, 13), (3, 14)]
BODY_CUT: list = []


def _mask() -> np.ndarray:
    mask = BODY_MASK | JAW
    for x, y in BODY_ADD:
        mask[y, x] = True
    for x, y in BODY_CUT:
        mask[y, x] = False
    return mask


MASK = _mask()


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

LETTERS = {"o": OUTLINE}
LETTERS.update({k: c for k, c in zip("abcdefgh", BODY)})
LETTERS.update({k: c for k, c in zip("01234", BELLY)})
LETTERS.update({k: c for k, c in zip("pqrst", SCULK)})
LETTERS.update({k: c for k, c in zip("EFGW", EYE)})
LETTERS.update({k: c for k, c in zip("mMn", MOUTH)})
LETTERS.update({k: c for k, c in zip("xyz", TOOTH)})

# Hand-placed head: (x, y) -> letter. Upper lip, the gape with fangs hanging from the
# upper jaw and rising from the jutting lower jaw, the milky blind eye, the gill slit.
HEAD = {
    (3, 10): "g", (4, 10): "f", (5, 10): "f", (6, 10): "e", (7, 10): "c",   # upper lip, lit
    (2, 11): "g", (3, 11): "z", (4, 11): "m", (5, 11): "z", (6, 11): "m", (7, 11): "b",   # upper fangs
    (2, 12): "M", (3, 12): "m", (4, 12): "n", (5, 12): "m", (6, 12): "M", (7, 12): "o",   # the gape
    (2, 13): "z", (3, 13): "m", (4, 13): "y", (5, 13): "m", (6, 13): "y", (7, 13): "c",   # lower fangs
    (3, 14): "2", (4, 14): "3", (5, 14): "3", (6, 14): "2",                 # jutting lower jaw
    (8, 9): "b", (9, 9): "b", (10, 10): "b",                               # eye socket
    (8, 10): "W", (9, 10): "G", (8, 11): "F", (9, 11): "E",                # milky blind eye
    (11, 11): "b", (11, 12): "b", (11, 13): "b", (10, 14): "b",            # gill slit
}
# Photophores (sculk glow): (x, y) -> (strength, delay): the glow runs out from the heart along the lateral line.
PHOTO = {
    (15, 15): (0.95, 0.03), (16, 15): (0.45, 0.04), (17, 16): (0.8, 0.06), (19, 17): (0.65, 0.08),
    (7, 16): (0.55, 0.05), (9, 17): (0.55, 0.06), (12, 18): (0.5, 0.08),
}
# The warden's heart, glowing through the flank behind the gills: (x, y) -> (rest, peak)
HEART = {(12, 13): (2, 4), (13, 13): (2, 3), (12, 14): (1, 3), (13, 14): (1, 3)}
HEART_SPILL = [(12, 12), (13, 12), (14, 13), (14, 14), (12, 15), (13, 15)]
# The lure: stalk pixels arching forward from the forehead, the bulb, its rim and halo.
STALK = [(8, 7, "f"), (8, 6, "g"), (8, 5, "g"), (7, 4, "g"), (6, 3, "h"), (5, 3, "h"), (4, 3, "g"),
         (3, 4, "f")]
# The glowing esca: (x, y) -> (dim gold index, bright gold index) as the glow breathes.
BULB = {(3, 5): (3, 4), (2, 6): (2, 3), (3, 6): (3, 4), (4, 6): (1, 3), (3, 7): (1, 2),
        (2, 5): (1, 2), (4, 5): (2, 3), (2, 7): (0, 1), (4, 7): (0, 1)}
HALO = [(1, 6), (5, 6), (3, 8)]


def _fleck(x: int, y: int) -> bool:
    """A sparse staggered lattice of pale sculk flecks on the flank (the scales the sheen catches)."""
    return y % 2 == 0 and (x + 2 * (y // 2 % 2)) % 4 == 1


def _band_colour(x: int, y: int) -> str:
    kb, kv = int(KB[y, x]), int(KV[y, x])
    u = UC[y, x]
    if kb == 0:
        return BODY[6] if u < 8 else BODY[5] if u < 15 else BODY[4]     # rim light on the back
    if kb == 1:
        return BODY[4] if u < 8 else BODY[3]
    if kv == 0:
        return BELLY[0]                                                  # reflected underside
    if kv == 1:
        return BELLY[3] if u < 11 else BELLY[2]                          # countershaded belly
    if kv == 2:
        return BELLY[1]
    if kv == 3:
        return BODY[3]
    if kb == 2:
        return BODY[2]
    if _fleck(x, y):
        return BODY[3]
    return BODY[1] if kv >= 5 else BODY[2]


def _body() -> Image.Image:
    img = canvas(SIZE)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                px[x, y] = rgba(_band_colour(x, y))
    for (x, y), letter in HEAD.items():
        px[x, y] = rgba(LETTERS[letter])
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
            else:
                dst[x, y] = (fr, fg, fb, FIN_OUTLINE[1])
    return out


def _screen(px, x, y, colour, k):
    """Add light: a screen blend of colour at strength k (keeps darks rich, lifts to glow)."""
    c = rgba(colour)
    r, g, b, a = px[x, y]
    px[x, y] = tuple(round(255 - (255 - v) * (1 - cv / 255 * k)) for v, cv in zip((r, g, b), c[:3])) + (a,)


def _blend(px, x, y, colour, k):
    c = rgba(colour)
    r, g, b, a = px[x, y]
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


SPARKLES = [  # (x, y, phase, reach, colour)
    (24, 8, 0.05, 2, SCULK[4]), (12, 24, 0.42, 2, "#f3d6ff"), (28, 27, 0.7, 1, "#c9f7ff"), (10, 2, 0.88, 1, GOLD[4]),
]
PLAIN = {"o", "m", "M", "n", "x", "y", "z", "E", "F", "G", "W"}   # head pixels the sheens skip


def _heartbeat(t: float) -> float:
    """The warden's double beat: two soft pulses early in the loop, then rest."""
    def pulse(c, w):
        d = abs(t - c) % 1.0
        d = min(d, 1.0 - d)
        return max(0.0, 1.0 - d / w) ** 2
    return min(1.0, pulse(0.0, 0.12) + 0.75 * pulse(0.19, 0.1))


def _window(t: float, start: float, length: float) -> float:
    """0 -> 1 -> 0 (sine) while t runs through [start, start + length) mod 1, else 0."""
    d = (t - start) % 1.0
    return math.sin(math.pi * d / length) if d < length else 0.0


def _sheen(px, t: float, fins: np.ndarray) -> None:
    """An iridescent band rolling from head to tail: cyan leading edge, pink middle and a
    violet trailing edge, strongest on the flecks, fainter on the fins."""
    start, run = 0.32, 0.6
    d = (t - start) % 1.0
    if d >= run:
        return
    width = 4.0
    centre = -width + (SL + 5.0 + 2 * width) * d / run
    for y in range(SIZE):
        for x in range(SIZE):
            body = MASK[y, x] and HEAD.get((x, y)) not in PLAIN and (x, y) not in PHOTO and (x, y) not in HEART
            if not body and not fins[y, x]:
                continue
            off = (UC[y, x] - centre) / width
            if abs(off) >= 1:
                continue
            k = (1 - abs(off)) ** 0.8
            pos = (1 - off) / 2      # 0 at the leading edge (toward the tail), 1 trailing
            colour = mix(IRIDESCENT[0], IRIDESCENT[1], pos * 2) if pos < 0.5 else                 mix(IRIDESCENT[1], IRIDESCENT[2], pos * 2 - 1)
            if body:
                strength = 1.0 if _fleck(x, y) and KB[y, x] >= 2 else 0.78
                if KV[y, x] <= 2:
                    strength *= 0.85
            else:
                strength = 0.5
            _screen(px, x, y, colour, strength * k)


def _glint(px, t: float) -> None:
    """A soft glint sliding along the lit back, snout to tail (first part of the loop)."""
    k0 = (t % 1.0) / 0.3
    if k0 >= 1:
        return
    centre = -2.0 + (SL + 4.0) * k0
    for y in range(SIZE):
        for x in range(SIZE):
            kb = int(KB[y, x])
            if kb < 0 or kb > 2 or HEAD.get((x, y)) in PLAIN:
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 2.6) * 0.55 * (1.0 - kb / 3.5)
            if k > 0:
                _blend(px, x, y, GLINT, k)


def _frame(t: float) -> Image.Image:
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))
    img = canvas(SIZE)
    tail = canvas(SIZE)
    _paint_fin(tail, _tail(sway), FIN[2], 210, cov_min=0.4, lit=FIN[3])
    img.alpha_composite(tail)
    _paint_fin(img, _dorsal(ripple), FIN[2], 205, lit=FIN[4])
    _paint_fin(img, _anal(ripple), FIN[2], 200)
    img.alpha_composite(_body())
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), FIN[4], 215, ray_alpha=235)
    img.alpha_composite(front)
    fins = (np.array(img)[:, :, 3] > 0) & ~MASK
    img = _outline(img)
    px = img.load()

    # the heartbeat: photophores throb, then the glow runs out to the tail's fringe
    beat = _heartbeat(t)
    for (x, y), (k, delay) in PHOTO.items():
        level = 0.22 + 0.78 * _heartbeat(t - delay)
        px[x, y] = rgba(mix(SCULK[1], SCULK[4] if k > 0.85 else SCULK[3], level * k))
    for (x, y), (lo, hi) in HEART.items():
        px[x, y] = rgba(mix(SCULK[lo], SCULK[hi], 0.15 + 0.85 * beat))
    for x, y in HEART_SPILL:
        _screen(px, x, y, SCULK[2], 0.12 + 0.5 * beat)
    tail_beat = _heartbeat(t - 0.08)
    tpx = tail.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if tpx[x, y][3] == 0 or MASK[y, x]:
                continue
            edge = any(0 <= x + dx < SIZE and 0 <= y + dy < SIZE and tpx[x + dx, y + dy][3] == 0
                       and not MASK[y + dy, x + dx] for dx, dy in ((1, 0), (0, 1), (1, 1)))
            if edge and UC[y, x] > 24.0:
                _blend(px, x, y, SCULK[2], 0.35 + 0.5 * tail_beat)

    _glint(px, t)
    _sheen(px, t, fins)

    # the lure: stalk, the breathing esca and its halo
    for x, y, letter in STALK:
        px[x, y] = rgba(LETTERS[letter])
    glow = 0.5 - 0.5 * math.cos(2 * math.pi * t)
    for (x, y), (lo, hi) in BULB.items():
        px[x, y] = rgba(mix(GOLD[lo], GOLD[hi], glow))
    for x, y in HALO:
        c = rgba(GOLD[3])
        px[x, y] = (c[0], c[1], c[2], round(30 + 110 * glow))

    for x, y, phase, reach, colour in SPARKLES:
        sparkle(img, x, y, _window(t, phase, 0.3), colour=colour, reach=reach)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
