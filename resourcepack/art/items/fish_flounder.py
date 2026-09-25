"""Flounder: an uncommon flatfish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (head up-left, tail down-right), showing the
eyed side of a flatfish: a deep oval disc in the old sprite's sandy khaki, domed by
top-left light, both bulging eyes (golden irises, catchlights) stacked on the upper side
of the head, the wide oblique gape over a pale lower jaw, the summer flounder's quincunx
of five dark ocelli with pale rings and a few pinkish and pale flecks. Translucent dorsal
and anal fin fringes with dashed ray roots run round almost the whole body into a short
peduncle and a rounded, spotted fan tail; the pectoral fin lies over the flank.

Animation (UNCOMMON, 12 frames x 3 ticks): the fin fringes ripple in a wave that travels
from head to tail (their edges swell and catch the light), the tail sways about a pixel,
the pectoral flutters, a soft glint slides along the upper body (frames 1-5) and then a
shimmer band of sparkling scales rolls across the disc (frames 7-11).
"""
from __future__ import annotations

import math

import numpy as np

from art.kit import animate, mix, rgba, save_animation, shade, sprite

ID = "fish_flounder"
NAME = "Flounder"
KIND = "item"
MODEL_KEY = "fish/flounder"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palette: sandy khaki from the old sprite, shadows lean olive-violet, lights lean gold --
SAND = "#b09c62"


def _s(amount: float) -> str:
    return shade(SAND, amount, 0.13)


SKIN = [_s(-0.58), _s(-0.44), _s(-0.29), _s(-0.14), SAND, mix(_s(0.2), "#d8b86a", 0.25), _s(0.42), _s(0.62)]
OUTLINE = shade(SAND, -0.74, 0.12)
OUTLINE_LIT = shade(SAND, -0.64, 0.12)
FINBASE = mix(SAND, "#e2d6a8", 0.35)


def _f(amount: float) -> str:
    return shade(FINBASE, amount, 0.16)


FIN = ["#5a4a33", "#7a6646", "#8f7d58", "#bfb087", "#d6caa0", "#ebe2bd"]
OCELLUS = ["#3f2716", "#6a4526"]            # dark core
RING = ["#efe3b8", "#d9c998", "#bca87a"]    # pale ring: lit, mid, shaded
MOTTLE = SKIN[2]
SPECK = ["#cfa99a", "#e6dcb6"]               # the old sprite's pinkish and pale flecks
EYE = {"pupil": "#17120b", "catch": "#fbfff0", "iris": "#c99a3c"}
GLINT = "#fbf6d6"
SHIMMER = "#f3ecc0"
SPARK = "#fffbe8"
MEMBRANE_ALPHA, EDGE_ALPHA, BASE_ALPHA, RAY_ALPHA = 204, 192, 236, 222
FIN_OUTLINE_ALPHA = 236

# ---- geometry ---------------------------------------------------------------------------
# Local frame in design units: u runs snout -> tail, v points toward the dorsal edge.
THETA = math.radians(33.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
K = 1.18                        # pixels per design unit
SNOUT = (3.3, 9.6)              # snout tip in the frame (pixels)
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


SL = 20.4
TOP = _smooth([(0.0, 0.5), (0.8, 1.2), (2.0, 2.15), (3.5, 3.2), (5.5, 4.05), (8.0, 4.55), (10.5, 4.6),
               (13.0, 4.2), (15.0, 3.5), (16.8, 2.5), (18.3, 1.7), (19.5, 1.4), (20.4, 1.35)])
BOT = _smooth([(0.0, 0.8), (0.8, 1.5), (2.0, 2.35), (3.5, 3.25), (5.5, 4.1), (8.0, 4.65), (10.5, 4.75),
               (13.0, 4.4), (15.0, 3.7), (16.8, 2.65), (18.3, 1.8), (19.5, 1.4), (20.4, 1.35)])

_ys, _xs = np.mgrid[0:SIZE * SS, 0:SIZE * SS]
_SX, _SY = (_xs + 0.5) / SS - SNOUT[0], (_ys + 0.5) / SS - SNOUT[1]
U = (_SX * AX[0] + _SY * AX[1]) / K
V = (_SX * UP[0] + _SY * UP[1]) / K
_py, _px = np.mgrid[0:SIZE, 0:SIZE]
UC = ((_px + 0.5 - SNOUT[0]) * AX[0] + (_py + 0.5 - SNOUT[1]) * AX[1]) / K
VC = ((_px + 0.5 - SNOUT[0]) * UP[0] + (_py + 0.5 - SNOUT[1]) * UP[1]) / K


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


def _to_px(u: float, v: float) -> tuple[int, int]:
    x = SNOUT[0] + K * (u * AX[0] + v * UP[0])
    y = SNOUT[1] + K * (u * AX[1] + v * UP[1])
    return int(math.floor(x)), int(math.floor(y))


def _curve(fn, a: float, b: float, steps: int = 80) -> list[tuple[int, int]]:
    """Rasterise a curve u -> (u, v) (or t -> (u, v)) into an ordered pixel list."""
    out = []
    for i in range(steps + 1):
        p = _to_px(*fn(a + (b - a) * i / steps))
        if not out or out[-1] != p:
            out.append(p)
    return out


BODY = _cov((U >= 0) & (U <= SL) & (V <= TOP(U)) & (V >= -BOT(U))) >= 0.5

# ---- fin fringes ------------------------------------------------------------------------
D0, D1, DW = 2.0, 18.9, 2.45         # dorsal fringe: start, end, full width
A0, A1, AW = 5.6, 18.7, 2.3          # anal fringe
WAVE = 7.0                            # ripple wavelength (design units)


def _fringe_width(u, start, end, width, t, phase):
    env = np.clip(np.minimum((u - start) / 2.6, (end - u) / 1.8), 0.0, 1.0) ** 0.6
    ripple = 0.38 * np.sin(2 * math.pi * (u / WAVE - t + phase))
    return np.where((u >= start) & (u <= end), env * (width + ripple), -1.0)


def _fringes(t: float):
    wd = _fringe_width(U, D0, D1, DW, t, 0.0)
    wa = _fringe_width(U, A0, A1, AW, t, 0.18)
    dors = _cov((V > TOP(U) - 0.3) & (V <= TOP(U) + wd) & (wd > 0)) >= 0.5
    anal = _cov((V < -BOT(U) + 0.3) & (V >= -BOT(U) - wa) & (wa > 0)) >= 0.5
    return dors & ~BODY, anal & ~BODY


TAIL_PIVOT = (19.8, 0.0)
TAIL_PTS = [(19.3, 1.45), (20.8, 2.25), (22.4, 3.0), (23.8, 3.15), (24.7, 2.35), (25.2, 1.1), (25.35, 0.0),
            (25.2, -1.1), (24.7, -2.35), (23.8, -3.15), (22.4, -3.0), (20.8, -2.25), (19.3, -1.45)]
TAIL_RAYS = [-40.0, -14.0, 14.0, 40.0]


def _tail(t: float):
    a = 10.0 * math.sin(2 * math.pi * t)
    return _poly(_rot(TAIL_PTS, TAIL_PIVOT, a)) >= 0.5, a


PECT_PIVOT = (6.7, 0.9)
PECT_PTS = [(6.5, 1.6), (8.2, 2.3), (9.7, 2.1), (10.3, 1.3), (9.3, 0.4), (7.6, 0.1), (6.5, 0.3)]


def _pect(t: float):
    a = 13.0 * math.sin(2 * math.pi * 2 * t + 0.8)
    return (_poly(_rot(PECT_PTS, PECT_PIVOT, a)) >= 0.55) & BODY


# ---- the static body: shading and markings ----------------------------------------------
LIGHT = np.array([-0.62, -0.78])


def _body_tones() -> np.ndarray:
    """Ramp index per body pixel: a domed disc lit from the top-left, rim-lit on the edges
    facing the light and a tone darker on those facing away."""
    h = np.where(VC > 0, TOP(np.clip(UC, 0, SL)), BOT(np.clip(UC, 0, SL)))
    n = np.clip(VC / np.maximum(h, 0.5), -1.0, 1.0)
    ex = (UC - 9.8) / 10.4
    dx = ex * AX[0] * 10.4 + n * h * UP[0]
    dy = ex * AX[1] * 10.4 + n * h * UP[1]
    ln = np.maximum(np.hypot(dx, dy), 1e-6)
    facing = (dx * LIGHT[0] + dy * LIGHT[1]) / ln
    r = np.clip(np.hypot(ex, n), 0.0, 1.0)
    value = 0.63 + 0.33 * facing * r ** 1.2 + 0.04 * n
    idx = np.clip(np.round(value * 7 - 0.4), 2, 6).astype(int)
    out = np.full((SIZE, SIZE), -1)
    for y in range(SIZE):
        for x in range(SIZE):
            if not BODY[y, x]:
                continue
            i = int(idx[y, x])
            up_open = y == 0 or not BODY[y - 1, x]
            left_open = x == 0 or not BODY[y, x - 1]
            down_open = y == SIZE - 1 or not BODY[y + 1, x]
            right_open = x == SIZE - 1 or not BODY[y, x + 1]
            if up_open or (left_open and facing[y, x] > -0.2):
                i = min(7, i + 2 if up_open and facing[y, x] > 0.2 else i + 1)
            elif (down_open or right_open) and facing[y, x] < -0.3:
                i = max(2, i - 1)
            out[y, x] = i
    return out


# Hand-placed details over the shaded disc, in frame pixels ("." keeps the shaded tone).
#   digits  a SKIN tone          P C I  eye pupil, catchlight, golden iris
#   O Q     mouth line           K k    ocellus core (k is its lit corner)
#   R r     pale ocellus ring    S s    pinkish and pale flecks
#   b       pale blotch (+1)     m      olive mottle (-1)
# Both eyes sit on the upper side of the head, the lower one forward; the wide gape slants
# back over the pale lower jaw. The five ocelli form the summer flounder's quincunx.
OVERLAY = [
    "................................",  # 8
    ".......66CPI....................",  # 9
    "...577CPIPP3....................",  # 10
    "...OQ6PP332.Rr..................",  # 11
    "...76332...RkK..................",  # 12
    ".........s.rKK4.................",  # 13
    "......bb.........R..............",  # 14
    "........Rr...Rr.RkK.............",  # 15
    ".......RkK..RkK.................",  # 16
    ".......rKK..rKK....s............",  # 17
    "................................",  # 18
    "...........S.mm.Rr..............",  # 19
    "...............RkK..S...........",  # 20
    "................................",  # 21
    "................................",  # 22
]
OVERLAY_TOP = 8
OLIVE = "#6f7a45"


def _markings() -> dict:
    """(x, y) -> colour of every hand-placed detail on the body."""
    marks: dict = {}
    for j, row in enumerate(OVERLAY):
        y = OVERLAY_TOP + j
        for x, ch in enumerate(row):
            if ch == "." or not BODY[y, x]:
                continue
            t = int(TONES[y, x])
            if ch.isdigit():
                c = SKIN[int(ch)]
            else:
                c = {
                    "P": EYE["pupil"], "C": EYE["catch"], "I": EYE["iris"],
                    "O": OUTLINE, "Q": OUTLINE_LIT, "K": OCELLUS[0], "k": OCELLUS[1],
                    "R": mix(RING[0], SKIN[t], 0.25), "r": mix(RING[1], SKIN[t], 0.3),
                    "S": mix(SPECK[0], SKIN[t], 0.2), "s": mix(SPECK[1], SKIN[t], 0.25),
                    "b": SKIN[min(7, t + 1)], "m": mix(SKIN[max(0, t - 1)], OLIVE, 0.35),
                }[ch]
            marks[(x, y)] = c
    return marks


TONES = _body_tones()
MARKS = _markings()


# ---- painting ---------------------------------------------------------------------------

def _fin_pixel(x, y, filled, body, ray, t, phase):
    """Colour and alpha of a fringe or tail pixel: a darker root against the body, paler
    translucent membrane toward the free edge, rays a tone down, and a travelling ripple
    that lifts the tones where its crest passes."""
    near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
    open_ = any(not (0 <= a < SIZE and 0 <= b < SIZE) or not filled[b, a] for a, b in near)
    touch = any(0 <= a < SIZE and 0 <= b < SIZE and body[b, a] for a, b in near)
    s = math.sin(2 * math.pi * (UC[y, x] / WAVE - t + phase))
    lift = 1 if s > 0.45 else 0
    if touch:
        i, a = (2, RAY_ALPHA) if ray else (3, BASE_ALPHA)
    elif open_:
        i, a = (3 if ray else 4), EDGE_ALPHA
    else:
        i, a = (2, RAY_ALPHA) if ray else (3, MEMBRANE_ALPHA)
    return FIN[max(0, min(5, i + lift))], a


def _frame(t: float):
    dors, anal = _fringes(t)
    tail, tail_a = _tail(t)
    tail = tail & ~BODY & ~dors & ~anal
    pect = _pect(t)
    img = np.zeros((SIZE, SIZE, 4), np.uint8)
    filled = BODY | dors | anal | tail
    zone = {}
    # two dark 2 px spots on the fan, riding along with its sway
    spots = set()
    for sa in (28.0, -30.0):
        r = math.radians(sa + tail_a)
        sx, sy = _to_px(TAIL_PIVOT[0] + 2.9 * math.cos(r), TAIL_PIVOT[1] + 2.9 * math.sin(r))
        spots |= {(sx, sy), (sx + 1, sy)}
    for y in range(SIZE):
        for x in range(SIZE):
            if BODY[y, x]:
                c = MARKS.get((x, y), SKIN[TONES[y, x]])
                z = "body"
                if pect[y, x]:
                    edge = not pect[y + 1, x] or not pect[y, x + 1]
                    c = mix(c, FIN[2] if edge else FIN[5], 0.55 if edge else 0.45)
                    z = "pect"
                img[y, x] = rgba(c)
                zone[(x, y)] = z
            elif dors[y, x] or anal[y, x]:
                ray = x % 2 == 0          # rays: a dashed root every other column
                c, a = _fin_pixel(x, y, filled, BODY, ray, t, 0.0 if dors[y, x] else 0.18)
                r, g, b, _ = rgba(c)
                img[y, x] = (r, g, b, a)
                zone[(x, y)] = "fin"
            elif tail[y, x]:
                du, dv = UC[y, x] - TAIL_PIVOT[0], VC[y, x] - TAIL_PIVOT[1]
                ang = math.degrees(math.atan2(dv, du)) - tail_a
                rad = math.hypot(du, dv)
                ray = rad > 1.6 and any(abs(ang - ra) * math.pi / 180 * rad * K < 0.5 for ra in TAIL_RAYS)
                c, a = _fin_pixel(x, y, filled, BODY, ray, 0.0, 0.25)
                if (x, y) in spots:
                    c, a = mix(OCELLUS[1], FIN[2], 0.3), 232
                r, g, b, _ = rgba(c)
                img[y, x] = (r, g, b, a)
                zone[(x, y)] = "tail"
    # glint and shimmer
    step = round(t * FRAMES) % FRAMES
    if 1 <= step <= 5:
        centre = 2.0 + 17.0 * (step - 1) / 4.0
        for (x, y), z in zone.items():
            if z not in ("body", "pect"):
                continue
            h = TOP(UC[y, x])
            if VC[y, x] < h * 0.12:
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 2.2) * min(1.0, (VC[y, x] / h - 0.12) * 2.2)
            if k > 0:
                img[y, x] = rgba(mix(tuple(img[y, x]), GLINT, 0.72 * k))
    elif step >= 7:
        centre = 1.5 + 18.0 * (step - 7) / 4.0
        for (x, y), z in zone.items():
            if z not in ("body", "pect"):
                continue
            along = UC[y, x] + 0.35 * VC[y, x]
            k = max(0.0, 1.0 - abs(along - centre) / 2.6)
            if k <= 0:
                continue
            spark = y % 2 == 1 and (x + y // 2) % 2 == 0
            strength = (0.9 if spark else 0.32) * min(1.0, k * 1.3)
            img[y, x] = rgba(mix(tuple(img[y, x]), SPARK if spark else SHIMMER, strength))
    # outline around the silhouette
    dark, lit = rgba(OUTLINE), rgba(OUTLINE_LIT)
    out = img.copy()
    for y in range(SIZE):
        for x in range(SIZE):
            if filled[y, x]:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                    if 0 <= x + dx < SIZE and 0 <= y + dy < SIZE and filled[y + dy, x + dx]]
            if not near:
                continue
            below = y + 1 < SIZE and filled[y + 1, x]
            right = x + 1 < SIZE and filled[y, x + 1]
            above = y > 0 and filled[y - 1, x]
            left = x > 0 and filled[y, x - 1]
            o = lit if (below or right) and not above and not left else dark
            solid = any(BODY[b, a] for a, b in near)
            out[y, x] = o if solid else (o[0], o[1], o[2], FIN_OUTLINE_ALPHA)
    from PIL import Image
    return Image.fromarray(out, "RGBA")


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
