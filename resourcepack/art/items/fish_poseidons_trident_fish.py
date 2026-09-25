"""Poseidon's Trident Fish: a legendary ocean fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's azure / cyan scheme and its trident-fork snout. A deep, tuna-like
pelagic body shaped by hand column by column: a royal-blue back under a lit rim with a row
of wave crests, the yellowfin's golden side stripe as a clean 2:1 line, azure flanks with a
diamond lattice of scale marks and a countershaded pale-aqua belly. The snout grows into a
golden trident bill (three tines a row apart with see-through gaps, stepped to follow the
body axis); a swept dorsal, sickle anal, pelvic and pectoral fins and a three-pointed
lunate tail, all translucent with painted rays.

Animation (LEGENDARY, 20 frames x 2 ticks, 2 s): the tail and fins sway about a pixel twice
per loop, a blue-white glint runs from the trident's tines down the back to the tail, then
an iridescent cyan / violet / pink shimmer band with sparkling scales rolls across the body
over a faint, ever-drifting colour shift; all the while a golden rim glow breathes one pixel
outside the outline, three sparkles orbit the fish (a third of an ellipse per loop, so it
loops seamlessly) twinkling as they go, and the sea-green eye pulses and lights the cheek.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_poseidons_trident_fish"
NAME = "Poseidon's Trident Fish"
KIND = "item"
MODEL_KEY = "fish/poseidons_trident_fish"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 20
FRAMETIME = 2

# ---- palettes (darkest -> lightest), from the old sprite's azure / cyan ------------------
OUTLINE = "#0c2650"
OUTLINE_LIT = "#143a72"
FIN_OUTLINE = ("#1d4a78", 232)
BACK = ["#102f63", "#16498a", "#1c5fa6", "#2a7cc0", "#48a3d8", "#7ccbee"]
AZURE = ["#1f6fb0", "#2a8cc9", "#35a6dc", "#46bde8", "#6ad4f2", "#9de8f8"]
BELLY = ["#94d6ea", "#bdeaf3", "#dcf6f8", "#f4fffd"]
FIN = ["#1a5b8c", "#2479aa", "#3698c6", "#5ab6dc", "#8ad2ec", "#c2ecf7"]
GOLD = ["#5a3410", "#8f5a18", "#c48a28", "#e8b443", "#f8d977", "#fff4c2"]
GOLD_OUTLINE = "#6a3c14"
EYE = ["#081a2c", "#ffffff"]
IRIS = "#3fe0c0"
GLINT = "#f2fbff"
IRIDESCENT = ["#8ff6ff", "#c3a8ff", "#ffa8e0"]
RIM = "#ffc93a"
SPARK = "#fff6d0"

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 14.8                      # snout to tail base
SNOUT = (11.2, 12.3)            # snout tip in the frame
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


_K = SL / 16.0
_D = 1.14              # a deep-bodied, tuna-like build
TOP = _smooth([(0.0, 0.5), (1.0 * _K, 1.3 * _D), (2.0 * _K, 1.95 * _D), (3.5 * _K, 2.6 * _D), (5.0 * _K, 3.0 * _D),
               (7.0 * _K, 3.2 * _D), (9.0 * _K, 3.1 * _D), (11.0 * _K, 2.65 * _D), (13.0 * _K, 1.95 * _D),
               (14.8 * _K, 1.25), (SL, 1.0)])
BOT = _smooth([(0.0, 0.5), (1.0 * _K, 1.2 * _D), (2.0 * _K, 1.95 * _D), (3.5 * _K, 2.7 * _D), (5.0 * _K, 3.25 * _D),
               (7.0 * _K, 3.55 * _D), (9.0 * _K, 3.45 * _D), (11.0 * _K, 2.95 * _D), (13.0 * _K, 2.15 * _D),
               (14.8 * _K, 1.4), (SL, 1.0)])

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
TAIL_PIVOT = (SL - 0.4, 0.0)


def _tail(sway: float):
    o = SL - 16.0
    pts = [(15.2, 1.05), (16.4, 1.6), (17.9, 2.8), (19.8, 4.5), (19.5, 3.1), (18.8, 1.55), (18.5, 0.8),
           (20.5, 0.0),
           (18.5, -0.8), (18.8, -1.55), (19.5, -3.1), (19.8, -4.5), (17.9, -2.8), (16.4, -1.6), (15.2, -1.05)]
    pts = [(u + o, v) for u, v in pts]
    rays = [((16.2 + o, 0.8), (19.3 + o, 3.9), FIN[4]), ((16.2 + o, -0.8), (19.3 + o, -3.9), FIN[1]),
            ((16.4 + o, 0.0), (19.0 + o, 0.0), FIN[1])]
    a = 6.0 + 9.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _dorsal(ripple: float):
    k = _K
    tip = (7.0 * k + 0.5 * ripple, TOP(7.0 * k) + 5.3)
    pts = [(4.2 * k, TOP(4.2 * k) - 0.6), tip, (8.4 * k + 0.5 * ripple, TOP(8.4 * k) + 2.0),
           (10.6 * k, TOP(10.6 * k) + 0.6), (10.6 * k, TOP(10.6 * k) - 0.6)]
    rays = [((4.8 * k, TOP(4.8 * k)), tip, FIN[4]),
            ((7.3 * k, TOP(7.3 * k)), (8.3 * k + 0.5 * ripple, TOP(8.3 * k) + 1.9), FIN[1])]
    return pts, rays


def _pectoral(flap: float):
    k = _K
    pivot = (4.6 * k, -0.8)
    pts = [(4.2 * k, -0.3), (5.2 * k, -0.4), (9.4 * k, -2.5), (9.0 * k, -3.0), (4.8 * k, -1.8)]
    return _rot(pts, pivot, 8.0 * flap), []


# Pelvic and anal fins hang from the hand-shaped belly: (x, y, tone) per pose; "e" is the lit
# leading edge, "m" the membrane.
PELVIC = {"rest": [(15, 18, "e"), (16, 18, "m"), (16, 19, "m")],
          "swept": [(15, 18, "e"), (16, 18, "m"), (17, 19, "m")]}
ANAL = {"rest": [(19, 20, "e"), (20, 20, "m"), (20, 21, "e"), (21, 21, "m"), (21, 22, "m")],
        "swept": [(19, 20, "e"), (20, 20, "m"), (21, 20, "m"), (21, 21, "e"), (22, 22, "m")]}


def _paint_px(img, pixels, zones):
    px = img.load()
    for x, y, tone in pixels:
        if MASK[y, x] or px[x, y][3]:
            continue
        r, g, b, _ = rgba(FIN[4] if tone == "e" else FIN[3])
        px[x, y] = (r, g, b, 214 if tone == "e" else 200)
        zones[(x, y)] = "fin"


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=228, lit=None, zones=None, tag="fin"):
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
            if zones is not None:
                zones[(x, y)] = tag


# The body is hand shaped column by column: (top row, bottom row) for every x.
COLUMNS = {11: (12, 13), 12: (11, 14), 13: (11, 15), 14: (10, 16), 15: (10, 17), 16: (10, 17), 17: (10, 18),
           18: (11, 18), 19: (11, 19), 20: (12, 19), 21: (13, 19), 22: (14, 20), 23: (15, 20), 24: (17, 20)}
MASK = np.zeros((SIZE, SIZE), bool)
for _x, (_t, _b) in COLUMNS.items():
    MASK[_t:_b + 1, _x] = True


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

# ---- trident bill -------------------------------------------------------------------------
# Hand placed on a 2:1 pixel slope parallel to the body axis: the shaft grows out of the
# snout into a crossbar, then three prongs a row apart (dark outline pixels between them),
# the centre prong longest. Letters: T tip, L lit, M mid, D shadow, C collar at the snout.
TRIDENT_ART = [      # rows from y = 6, columns from x = 2
    ".TL.......",
    "...LMD....",
    "TLL..M....",
    "...LMMM...",
    ".TL..DM...",
    "...MMD.DD.",
]
TRIDENT_PX = {(2 + x, 6 + y): ch for y, row in enumerate(TRIDENT_ART) for x, ch in enumerate(row) if ch != "."}
TRIDENT_TONE = {"T": GOLD[5], "L": GOLD[4], "M": GOLD[3], "D": GOLD[2], "S": GOLD[1]}
TRIDENT = np.zeros((SIZE, SIZE), bool)
for (_x, _y) in TRIDENT_PX:
    TRIDENT[_y, _x] = True


def _gold(x: int, y: int) -> str:
    return TRIDENT_TONE[TRIDENT_PX[(x, y)]]


# ---- head: eye, mouth, gill cover ----------------------------------------------------------
EYE_AT = (12, 12)                        # top-left pixel of the 2x2 eye
EYE_PX = {EYE_AT: "lit", (EYE_AT[0] + 1, EYE_AT[1]): "iris", (EYE_AT[0], EYE_AT[1] + 1): "iris",
          (EYE_AT[0] + 1, EYE_AT[1] + 1): "pupil"}
IRIS_DARK = "#0d4a4c"


def _scale(x: int, y: int) -> bool:
    """A sparse diamond lattice (frame space) for scale marks."""
    return (x + y) % 2 == 0 and (x + (y // 1 % 4 >= 2) * 2) % 4 == 1


def _stripe_row(x: int) -> int:
    """The yellowfin's golden side stripe: a clean 2:1 line from behind the eye to the tail."""
    return 13 + (x - 14) // 2


# Letters of the painted body; zone decides which effects touch a pixel.
TONES = {
    "R": (BACK[5], "back"), "r": (BACK[4], "back"), "B": (BACK[2], "back"), "b": (BACK[3], "back"),
    "G": (GOLD[4], "stripe"), "g": (GOLD[3], "stripe"),
    "F": (AZURE[4], "flank"), "f": (AZURE[3], "flank"), "s": (AZURE[5], "flank"),
    "w": (BELLY[3], "belly"), "v": (BELLY[2], "belly"), "u": (BELLY[0], "belly"),
    "h": (AZURE[3], "head"), "H": (AZURE[5], "head"), "c": (AZURE[1], "head"), "m": (OUTLINE_LIT, "head"),
}
# Head details over the column rules: snout, eye (placed by EYE_PX), gill cover, mouth.
HEAD = {
    (11, 12): "R", (11, 13): "m",
    (12, 14): "w",
    (13, 14): "H", (13, 15): "w",
    (14, 12): "H", (14, 14): "h",
    (15, 11): "b", (15, 12): "c", (15, 14): "c", (15, 15): "c", (14, 15): "v",
}


def _letter(x: int, y: int) -> str:
    t, b = COLUMNS[x]
    if (x, y) in HEAD:
        return HEAD[(x, y)]
    sy = _stripe_row(x) if x >= 14 else None
    if y == t:
        return "R" if x <= 17 else "r"
    if y == b:
        return "u"
    if y == b - 1:
        return "w"
    if sy is not None and y == sy:
        return "G" if x <= 19 else "g"
    if sy is None or y < sy:
        if sy is not None and y == sy - 1 and y > t + 1:
            return "b"
        # a row of wave crests (Poseidon's sea) painted into the dark back
        if sy is not None and y == t + 2 and y < sy - 1 and (x - 15) % 4 in (0, 1):
            return "b"
        return "B"
    # flank between the stripe and the belly: azure with scale marks
    if y == sy + 1:
        return "f" if _scale(x, y) else "F"
    return "s" if _scale(x, y) else "F"


def _body(zones: dict) -> Image.Image:
    img = canvas(SIZE)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                colour, zone = TONES[_letter(x, y)]
                px[x, y] = rgba(colour)
                zones[(x, y)] = zone
            elif TRIDENT[y, x]:
                px[x, y] = rgba(_gold(x, y))
                zones[(x, y)] = "gold"
    for (x, y), part in EYE_PX.items():
        px[x, y] = rgba({"lit": EYE[1], "iris": IRIS_DARK, "pupil": EYE[0]}[part])
        zones[(x, y)] = "eye"
    return img


GAPS: set = set()


def _outline(img: Image.Image, zones: dict) -> tuple[Image.Image, set]:
    src = img.load()
    out = img.copy()
    dst = out.load()
    ring = set()
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                    if 0 <= x + dx < SIZE and 0 <= y + dy < SIZE and src[x + dx, y + dy][3]]
            if not near:
                continue
            ring.add((x, y))
            kinds = {zones.get(p, "fin") for p in near}
            facing = ((x, y + 1) in near or (x + 1, y) in near) and (x, y - 1) not in near and (x - 1, y) not in near
            if kinds & {"back", "line", "stripe", "flank", "belly", "head", "eye"}:
                dst[x, y] = rgba(OUTLINE_LIT if facing else OUTLINE)
            elif "gold" in kinds:
                # the gaps between the prongs stay see-through so the tines read apart
                gold = lambda q: zones.get(q) == "gold"
                if (gold((x, y - 1)) and gold((x, y + 1))) or (gold((x - 1, y)) and gold((x + 1, y))):
                    GAPS.add((x, y))
                    continue
                dst[x, y] = rgba(GOLD_OUTLINE)
            else:
                r, g, b, _ = rgba(FIN_OUTLINE[0])
                dst[x, y] = (r, g, b, FIN_OUTLINE[1])
    return out, ring


# ---- effects -------------------------------------------------------------------------------
def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _glint(img, zones, t, start=0.0, end=0.36, width=3.2):
    """A blue-white glint running from the trident's tips down the back to the tail."""
    if not start <= t < end:
        return
    centre = -8.0 + (SL + 12.0) * (t - start) / (end - start)
    px = img.load()
    for (x, y), zone in zones.items():
        if zone == "gold":
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / (width * 0.7)) * 0.85
            if k > 0:
                _blend(px, x, y, GOLD[5] if k < 0.6 else "#ffffff", k)
            continue
        if zone not in ("back", "line", "stripe", "head"):
            continue
        kb = int(KB[y, x])
        if kb < 0 or kb > 3:
            continue
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / width) * 0.62 * (1.0 - kb / 4.5)
        if k > 0:
            _blend(px, x, y, GLINT, k)


def _iridescent(k: float) -> str:
    """Cyan -> violet -> pink -> cyan as k runs 0..1."""
    k = k % 1.0 * 3
    i = int(k)
    return mix(IRIDESCENT[i % 3], IRIDESCENT[(i + 1) % 3], k - i)


def _shimmer(img, zones, t, start=0.44, end=0.82, width=3.0):
    """An iridescent band rolling across the scales, head to tail, with sparkling scales;
    under it a faint colour shift drifts over the flank all the time."""
    px = img.load()
    for (x, y), zone in zones.items():
        if zone in ("flank", "line"):
            _blend(px, x, y, _iridescent(UC[y, x] / 14.0 - t), 0.14)
    if not start <= t < end:
        return
    centre = -1.0 + (SL + 3.0) * (t - start) / (end - start)
    for (x, y), zone in zones.items():
        if zone not in ("flank", "line", "belly", "back", "head"):
            continue
        d = (UC[y, x] - centre) / width                 # -1 .. 1 across the band
        if abs(d) >= 1:
            continue
        k = 1.0 - abs(d)
        spot = y % 2 == 1 and (x + y // 2) % 2 == 0 and zone in ("flank", "back")
        colour = "#ffffff" if spot else _iridescent(0.5 + 0.33 * d)
        base = 0.62 if zone == "back" else 0.5
        _blend(px, x, y, colour, min(1.0, k * 1.4) * (0.9 if spot else base))


def _eye_glow(img, t):
    """The radiant eye: a sea-green iris that pulses, lighting the cheek around it."""
    px = img.load()
    p = 0.5 + 0.5 * math.sin(2 * math.pi * 2 * t)
    ex, ey = EYE_AT
    for (x, y), part in EYE_PX.items():
        if part == "iris":
            px[x, y] = rgba(mix(IRIS_DARK, IRIS, 0.35 + 0.65 * p))
    for dx, dy in ((-1, 0), (0, -1), (2, 0), (1, -1), (-1, 1), (0, 2), (2, 1), (1, 2)):
        x, y = ex + dx, ey + dy
        if px[x, y][3]:
            _blend(px, x, y, IRIS, 0.12 + 0.3 * p)


def _rim(img, ring, t):
    """A breathing golden glow one pixel outside the outline, strongest on the lit side."""
    px = img.load()
    breath = 0.5 - 0.5 * math.cos(2 * math.pi * t)
    c = rgba(RIM)
    for y in range(SIZE):
        for x in range(SIZE):
            if px[x, y][3] or (x, y) in GAPS or not (1 <= x <= SIZE - 2 and 1 <= y <= SIZE - 2):
                continue
            if not any((x + dx, y + dy) in ring for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                continue
            side = 1.0 if VC[y, x] > 0 or UC[y, x] < 0 else 0.55
            a = (12 + 118 * breath) * side
            px[x, y] = (c[0], c[1], c[2], round(a))


ORBIT_C = (16.0, 15.0)


def _orbit(img, t):
    """Three sparkles 120 degrees apart travelling a third of an ellipse per loop, so the
    loop is seamless; each twinkles as it goes and shines brightest over the back."""
    for k in range(3):
        frac = (k + t) / 3.0
        ang = 2 * math.pi * frac
        a, b = 13.5, 8.0
        lu, lv = a * math.cos(ang), b * math.sin(ang)
        x = ORBIT_C[0] + lu * AX[0] + lv * UP[0]
        y = ORBIT_C[1] + lu * AX[1] + lv * UP[1]
        env = 0.55 + 0.45 * (0.5 + 0.5 * math.sin(ang))
        amt = env * (0.35 + 0.65 * (0.5 - 0.5 * math.cos(2 * math.pi * 4 * frac)))
        xi, yi = int(round(x)), int(round(y))
        if 1 <= xi <= 30 and 1 <= yi <= 30:
            sparkle(img, xi, yi, amt, colour=SPARK, reach=2 if amt > 0.7 else 1)


def _frame(t: float) -> Image.Image:
    sway = math.sin(2 * math.pi * 2 * t)
    ripple = math.sin(2 * math.pi * (2 * t - 0.15))
    flap = math.sin(2 * math.pi * (2 * t + 0.3))
    zones: dict = {}
    img = canvas(SIZE)
    _paint_fin(img, _tail(sway), FIN[2], 212, cov_min=0.4, lit=FIN[4], zones=zones)
    _paint_fin(img, _dorsal(ripple), FIN[2], 205, lit=FIN[4], zones=zones)
    body = _body(zones)
    img.alpha_composite(body)
    _paint_px(img, PELVIC["swept" if flap > 0.2 else "rest"], zones)
    _paint_px(img, ANAL["swept" if ripple > 0.2 else "rest"], zones)
    # the near pectoral lies over the flank (clipped to the body so it never floats)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), FIN[4], 190)
    fp = front.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if not MASK[y, x] or (x, y) in EYE_PX:
                fp[x, y] = (0, 0, 0, 0)
    img.alpha_composite(front)
    _glint(img, zones, t)
    _shimmer(img, zones, t)
    _eye_glow(img, t)
    out, ring = _outline(img, zones)
    _rim(out, ring, t)
    _orbit(out, t)
    return out


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
