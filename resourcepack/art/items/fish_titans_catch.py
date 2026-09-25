"""Titan's Catch: a legendary fish of the JoshyMC fishing collection (rarity LEGENDARY).

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's champagne / pearl / rose / khaki-gold scheme. A colossal
tarpon-like king of the deep: a massive deep body armoured in big pearl scales netted in
gold with a catchlight on every scale, a bronze back under a gilded forehead, a
countershaded ivory belly, the upturned mouth over a jutting lower jaw, a rose gill plate,
a big gold-ringed eye, a tall crown-like dorsal crest with a lit gold leading spine, low
pectoral, pelvic and anal fins and a huge deeply forked tail (fins translucent champagne
with gold rays).

Animation (LEGENDARY, 16 frames x 3 ticks): the tail swings, the crest's last ray flicks
and the pectoral and pelvic fins sway on offset phases; a warm glint slides along the
back, then an iridescent band (cyan, pink, violet) rolls across the scales. All loop long
a golden rim glow breathes just outside the outline, two gold sparkles orbit the fish
(passing behind it on the far side) and the eye flares with a radiant starburst.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_titans_catch"
NAME = "Titan's Catch"
KIND = "item"
MODEL_KEY = "fish/titans_catch"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 16
FRAMETIME = 3

# ---- palettes (darkest -> lightest), from the old sprite's champagne / rose / khaki -------
OUTLINE = "#3f2427"                 # deep plum-brown, hue-shifted from the bronze back
FIN_OUTLINE = ("#5a3222", 228)
BACK = ["#4a2a26", "#6e3f30", "#94603c", "#b8864a", "#d7ad5e", "#f0d488"]
PEARL = ["#7d5550", "#a47266", "#c9957c", "#e4b796", "#f2d3b0", "#fae8cd", "#fffaf0"]
ROSE = ["#b37670", "#d59a8f", "#f2c3b3"]
FIN = ["#6e4a2a", "#8f6a3c", "#b08e55", "#cfae6e", "#e4ca8e", "#f7ebc0"]
GOLD = ["#8a5a1c", "#c28a2c", "#eab94a", "#ffe089", "#fff6d2"]
EYE = ["#1a0f14", "#ffffff"]
GLINT = "#fff8e2"
RIM = "#ffc83a"
SPARK = "#fff3b8"
IRIDESCENT = ["#6fe8ff", "#ff7fcf", "#a47bff"]   # leading edge -> trailing edge

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 21.8                      # snout to tail base
SNOUT = (3.9, 8.2)             # snout tip in the frame
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


# Half-depths above (TOP) and below (BOT) the axis: a deep, heavy body.
TOP = _smooth([(0.0, 0.95), (0.8, 1.7), (1.8, 2.45), (3.0, 3.1), (4.5, 3.7), (6.3, 4.2), (8.3, 4.5),
               (10.3, 4.5), (12.3, 4.2), (14.3, 3.6), (16.3, 2.8), (18.3, 2.05), (20.3, 1.6), (21.8, 1.5)])
BOT = _smooth([(0.0, 0.95), (0.8, 1.8), (1.8, 2.6), (3.0, 3.35), (4.5, 4.05), (6.3, 4.6), (8.3, 4.9),
               (10.3, 4.85), (12.3, 4.45), (14.3, 3.75), (16.3, 2.9), (18.3, 2.1), (20.3, 1.62), (21.8, 1.5)])

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



# ---- fins: (polygon, rays) in local coordinates; v > 0 is the back ----------------------

TAIL_PIVOT = (21.2, 0.0)


def _tail(sway: float):
    pts = [(20.6, 1.4), (22.4, 2.4), (24.4, 3.9), (26.4, 5.7), (26.5, 4.2), (25.6, 2.4), (24.6, 1.0), (24.1, 0.0),
           (24.6, -1.0), (25.6, -2.4), (26.5, -4.2), (26.4, -5.7), (24.4, -3.9), (22.4, -2.4), (20.6, -1.4)]
    rays = [((21.6, 0.8), (26.0, 4.9), GOLD[1]), ((21.6, -0.8), (26.0, -4.9), GOLD[1]),
            ((21.8, 0.0), (23.6, 0.0), GOLD[1])]
    a = TAIL_REST + 9.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=230, lit=None):
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
                if cov[y, x] >= 0.85 and _near(u, v, ra, rb, 0.45):
                    colour, a = rc, ray_alpha
                    break
            if lit and inside[y, x] and ((y > 0 and not inside[y - 1, x] and not MASK[y - 1, x])
                                         or (x > 0 and not inside[y, x - 1] and not MASK[y, x - 1])):
                colour = lit
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)


# ---- body -------------------------------------------------------------------------------

# The body is hand-painted: (first x, letters) per row. Back: e d c b B (lit gold ->
# dark bronze), head / belly pearl 2-6, W w countershaded belly, r gill edge, K eye (the
# eye and its ring are painted by _eye). The flank letters @ % * (upper, mid, lower) carry
# the big gilded scales painted by _flank().
ART = {
    7: (5, "deeddc"),
    8: (4, "dd4554dccb"),
    9: (3, "5B45KK54rdcb"),
    10: (4, "4B5KK55r@qcb"),
    11: (4, "34B5556r@@qcb"),
    12: (5, "34556r%@@@qcb"),
    13: (5, "2456r%%%@@@qcb"),
    14: (6, "256r*%%%%@@qcb"),
    15: (7, "25W***%%%@@qcb"),
    16: (8, "22WW***%%@@qcb"),
    17: (10, "223WW**%%@qcb"),
    18: (13, "2233333@qcb"),
    19: (20, "2cb"),
}


def _mask() -> np.ndarray:
    mask = np.zeros((SIZE, SIZE), bool)
    for y, (x0, row) in ART.items():
        for i, ch in enumerate(row):
            mask[y, x0 + i] = ch != "."
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

GILL = {7: 11, 8: 11, 9: 11, 10: 11, 11: 10, 12: 10, 13: 9, 14: 9, 15: 8, 16: 8}


def _scale(x: int, y: int):
    """Big diamond scales on an image-space lattice (6 px across): 'rim' on the net between
    scales, 'lit' on the one bright pixel at each scale's upper-left, else 'face'."""
    a = (x + y + SCALE_OFF[0]) % 6
    b = (x - y + SCALE_OFF[1]) % 6
    if a == 0 or b == 0:
        return "rim"
    if (a, b) == (2, 2):
        return "lit"
    return "face"


SCALE_OFF = (0, 0)


PAINT = {"e": BACK[5], "d": BACK[4], "c": BACK[3], "b": BACK[2], "B": BACK[1], "q": mix(BACK[4], PEARL[3], 0.55),
         "2": PEARL[2], "3": PEARL[3], "4": PEARL[4], "5": PEARL[5], "6": PEARL[6],
         "W": PEARL[6], "w": PEARL[5], "r": ROSE[0], "K": EYE[0]}
FLANK = {"@": 3, "%": 4, "*": 5}


def _letter(x: int, y: int):
    row = ART.get(y)
    if not row or not (row[0] <= x < row[0] + len(row[1])):
        return None
    return row[1][x - row[0]]


def _flank(x: int, y: int, tone: int) -> str:
    s = _scale(x, y)
    if s == "rim":
        return mix(GOLD[1], PEARL[tone], 0.2 if tone == 3 else 0.32 if tone == 4 else 0.45)
    if s == "lit":
        return PEARL[6]
    return PEARL[tone]


def _band_colour(x: int, y: int) -> str:
    ch = _letter(x, y)
    if ch is None:
        return PEARL[4]
    if ch in FLANK:
        return _flank(x, y, FLANK[ch])
    return PAINT[ch]


def _zone(x: int, y: int) -> str:
    ch = _letter(x, y)
    if ch in FLANK:
        return "flank"
    if ch in ("e", "d", "c", "b", "q"):
        return "back"
    return "head" if x <= GILL.get(y, -1) else "belly"


def _body() -> tuple[Image.Image, dict]:
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                px[x, y] = rgba(_band_colour(x, y))
                zones[(x, y)] = _zone(x, y)
    return img, zones


# ---- hand-drawn fins (the tail is procedural so it can swing) ------------------------------
# Letters: L lit leading spine, R ray, m membrane, n membrane near the base.
FIN_PAINT = {"L": (GOLD[3], 240), "R": (GOLD[1], 235), "m": (FIN[4], 200), "n": (FIN[3], 214)}
# Tall crest-like dorsal whose last ray trails the tarpon's long filament along the back.
DORSAL = {
    3: (13, "L"),
    4: (12, "Lm"),
    5: (12, "LmR"),
    6: (12, "LmRm"),
    7: (11, "LnmRmR"),
    8: (14, "nnRm"),
    9: (15, "nn"),
}
FILAMENT = {  # the tip of the last ray flicks as the fin ripples
    -1: [],
    0: [(17, 7)],
    1: [(17, 7), (17, 6)],
}
ANAL = {
    19: (15, "nnnnn"),
    20: (16, "RmmR"),
    21: (17, "Rm"),
}
PELVIC = {"rest": [(11, 18, "n"), (12, 18, "n"), (12, 19, "m"), (13, 19, "R"), (13, 20, "m")],
          "swept": [(11, 18, "n"), (12, 18, "n"), (13, 19, "m"), (14, 19, "R"), (14, 20, "m")]}
# Near-side pectoral over the flank: membrane (p) and dark trailing edge (P) blended on.
PECT = {
    "spread": [(10, 14, "p"), (11, 14, "p"), (11, 15, "p"), (12, 15, "p"), (10, 15, "P"), (11, 16, "P"),
               (12, 16, "P"), (13, 16, "P")],
    "folded": [(10, 14, "p"), (11, 14, "p"), (12, 14, "p"), (10, 15, "P"), (11, 15, "P"), (12, 15, "p"),
               (13, 15, "P")],
}


def _put_fin(img, x, y, ch) -> None:
    colour, alpha = FIN_PAINT[ch]
    c = rgba(colour)
    px = img.load()
    if 0 <= x < SIZE and 0 <= y < SIZE and not MASK[y, x]:
        px[x, y] = (c[0], c[1], c[2], alpha)


def _paint_fins(img, ripple: float, flap: float) -> None:
    for rows in (DORSAL, ANAL):
        for y, (x0, row) in rows.items():
            for i, ch in enumerate(row):
                _put_fin(img, x0 + i, y, ch)
    for x, y in FILAMENT[round(ripple)]:
        _put_fin(img, x, y, "R")
    for x, y, ch in PELVIC["swept" if flap > 0 else "rest"]:
        _put_fin(img, x, y, ch)


def _paint_pectoral(img, flap: float) -> None:
    px = img.load()
    pose = "folded" if flap > 0.35 else "spread"
    for x, y, part in PECT[pose]:
        if MASK[y, x]:
            tint = FIN[5] if part == "p" else GOLD[1]
            px[x, y] = rgba(mix(px[x, y], tint, 0.5 if part == "p" else 0.7))


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


def _rim(img: Image.Image, t: float) -> None:
    """Breathing golden glow on the empty pixels just outside the outline."""
    src = img.copy().load()
    px = img.load()
    breath = 0.5 - 0.5 * math.cos(2 * math.pi * t)
    c = rgba(RIM)
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            n4 = sum(1 for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                     if 0 <= x + dx < SIZE and 0 <= y + dy < SIZE and src[x + dx, y + dy][3] >= 200)
            if not n4:
                continue
            a = 85 + 115 * breath
            px[x, y] = (c[0], c[1], c[2], round(a))


def _along(x, y) -> float:
    return float(UC[y, x])


def _effects(img: Image.Image, zones: dict, t: float) -> None:
    px = img.load()
    # 1) warm glint along the back (t 0 .. 0.35)
    if t < 0.35:
        centre = -1.5 + (SL + 3.0) * (t / 0.35)
        g = rgba(GLINT)
        for (x, y), zone in zones.items():
            kb = int(KB[y, x])
            if kb > 3:
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.4) * 0.78 * (1.0 - kb / 4.5)
            if k > 0:
                r, gg, b, a = px[x, y]
                px[x, y] = (round(r + (g[0] - r) * k), round(gg + (g[1] - gg) * k), round(b + (g[2] - b) * k), a)
    # 2) iridescent band across the scales (t 0.42 .. 0.9): cyan leads, pink, violet trails;
    #    the scale rims and catchlights take the colour hardest, the faces a pearly tint
    elif 0.42 <= t < 0.92:
        centre = 3.0 + 18.0 * (t - 0.42) / 0.45
        for (x, y), zone in zones.items():
            if zone not in ("flank", "back", "belly"):
                continue
            d = _along(x, y) - centre
            k = max(0.0, 1.0 - abs(d) / 3.6)
            if k <= 0:
                continue
            hue = IRIDESCENT[0] if d > 1.2 else IRIDESCENT[1] if d > -1.2 else IRIDESCENT[2]
            s = _scale(x, y) if zone == "flank" else "face"
            if s == "lit":
                target, strength = mix(hue, "#ffffff", 0.55), 0.9
            elif s == "rim":
                target, strength = hue, 0.7
            else:
                target, strength = mix(hue, "#ffffff", 0.4), 0.5 if zone == "flank" else 0.3
            px[x, y] = rgba(mix(px[x, y], target, strength * min(1.0, k * 1.5)))


EYE_AT = (7, 9)     # top-left of the 2x2 eye (set once the head is finalised)


def _eye(img: Image.Image, t: float) -> None:
    px = img.load()
    ex, ey = EYE_AT
    flare = max(0.0, math.cos(2 * math.pi * (t - 0.25))) ** 3
    # golden iris ring pulsing brighter
    ring = [(ex - 1, ey), (ex - 1, ey + 1), (ex, ey + 2), (ex + 1, ey + 2), (ex + 2, ey + 1), (ex + 2, ey),
            (ex, ey - 1), (ex + 1, ey - 1)]
    for x, y in ring:
        if px[x, y][3]:
            px[x, y] = rgba(mix(GOLD[2], GOLD[4], flare * 0.85))
    # the flare: a starburst from the catchlight, with the pupil kept dark on top
    if flare > 0.2:
        sparkle(img, ex, ey, flare, colour="#fff6cc", reach=3)
    px[ex, ey] = rgba(EYE[1])
    px[ex + 1, ey] = rgba(EYE[0])
    px[ex, ey + 1] = rgba(EYE[0])
    px[ex + 1, ey + 1] = rgba(mix(EYE[0], GOLD[1], 0.35 + 0.4 * flare))


ORBIT_C = (16.0, 15.2)


def _orbit_points(t: float):
    """Two sparkles on a tilted ellipse around the fish; front = drawn over it."""
    out = []
    for k in range(2):
        a = 2 * math.pi * (t + k / 2)
        cu, cv = 13.6 * math.cos(a), 9.0 * math.sin(a)
        x = ORBIT_C[0] + cu * AX[0] + cv * UP[0]
        y = ORBIT_C[1] + cu * AX[1] + cv * UP[1]
        front = math.sin(a) < 0.15          # the lower arc passes in front of the fish
        amount = 0.55 + 0.45 * math.cos(2 * math.pi * (t * 2 + k * 0.5))
        out.append((round(x), round(y), amount, front))
    return out


def _fish(t: float) -> Image.Image:
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))
    img = canvas(SIZE)
    _paint_fin(img, _tail(sway), FIN[4], 200, cov_min=0.4, lit=FIN[5])
    _paint_fins(img, ripple, flap)
    body, zones = _body()
    _paint_pectoral(body, flap)
    _effects(body, zones, t)
    img.alpha_composite(body)
    img = _outline(img)
    _eye(img, t)
    _rim(img, t)
    return img


def _frame(t: float) -> Image.Image:
    fish = _fish(t)
    back = canvas(SIZE)
    top = canvas(SIZE)
    for x, y, amount, front in _orbit_points(t):
        sparkle(top if front else back, x, y, amount, colour=SPARK, reach=2)
    back.alpha_composite(fish)
    back.alpha_composite(top)
    return back


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
