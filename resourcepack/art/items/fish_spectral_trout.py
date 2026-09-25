"""Spectral Trout: an epic ghost fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's ghostly lavender-white body and golden eye. Real trout anatomy:
a sturdy torpedo body, blunt snout with a terminal mouth whose jaw runs back under the
eye, one mid-body dorsal fin, the little adipose fin before the tail, low pectoral,
pelvic and anal fins and a broad, shallowly forked tail. Periwinkle back speckled with
violet trout spots, a pastel pink-and-cyan "rainbow" lateral band, pale lavender flanks
and a white countershaded belly; fins are pale, ghostly translucent and spotted.

Animation (EPIC, 16 frames x 3 ticks): tail and fins sway on offset sine phases, an
iridescent sheen drifts purple -> pink -> cyan across the scales all loop long, a soft
glint slides along the back, a band of sparkling scales rolls from head to tail, and
three pale-gold and cyan twinkles blink around the fish in turn.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_spectral_trout"
NAME = "Spectral Trout"
KIND = "item"
MODEL_KEY = "fish/spectral_trout"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 16
FRAMETIME = 3

# ---- palettes (darkest -> lightest): shadows lean indigo, lights lean pink-white --------
OUTLINE = "#2c2566"
FIN_OUTLINE = ("#4a438f", 232)
BACK = ["#3d3688", "#4b48a4", "#5e60bd", "#7a84d3", "#9eaae6", "#c3cdf5"]
FLANK = ["#8a82c6", "#a5a0dc", "#c0bdec", "#d6d6f6", "#e8e8fc", "#f5f4ff", "#ffffff"]
BAND = ["#e6b3ea", "#f7c6f0", "#c2f3fb"]           # pastel rainbow band: rose, pink, cyan
SPOT = ["#2d2670", "#433a98"]
FIN = ["#5c58a6", "#716fba", "#8b8ecf", "#a7ade1", "#c4cbf0", "#e2e7fb"]
GOLD = ["#8a5a1c", "#d09a2a", "#ffc832", "#ffe597"]
EYE = ["#150f33", "#ffffff"]
GLINT = "#f6fbff"
IRIS = ["#b37cff", "#ff8fd6", "#7fe9ff"]          # iridescent sheen: purple, pink, cyan
SPARK_GOLD = "#ffe98a"
SPARK_CYAN = "#bff8ff"

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 22.6                      # snout to tail base
SNOUT = (3.0, 7.4)             # snout tip in the frame
SS = 4                         # supersampling per pixel side
TAIL_REST = 13.0               # the tail rests bent a little toward the back (degrees)


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


# Half-depths of the body above (TOP) and below (BOT) the axis: a blunt rounded snout, a
# deep shoulder, and a thick caudal peduncle (trout, not herring).
TOP = _smooth([(0.0, 0.9), (0.8, 1.65), (1.8, 2.3), (3.0, 2.85), (4.5, 3.3), (6.3, 3.7), (8.3, 3.95),
               (10.4, 3.95), (12.5, 3.7), (14.6, 3.2), (16.7, 2.55), (18.8, 2.0), (21.0, 1.72),
               (22.6, 1.7)])
BOT = _smooth([(0.0, 0.9), (0.8, 1.6), (1.8, 2.25), (3.0, 2.85), (4.5, 3.4), (6.3, 3.9), (8.3, 4.2),
               (10.4, 4.15), (12.5, 3.85), (14.6, 3.3), (16.7, 2.6), (18.8, 2.0), (21.0, 1.72),
               (22.6, 1.7)])

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


BODY_REF = _cov((U >= 0) & (U <= SL + 0.3) & (V <= TOP(U)) & (V >= -BOT(U))) >= 0.5


# ---- fins: (polygon, rays, spots) in local coordinates; v > 0 is the back ---------------

TAIL_PIVOT = (22.0, 0.0)


def _tail(sway: float):
    # broad, shallowly forked trout tail
    pts = [(21.4, 1.6), (23.4, 2.4), (25.7, 3.3), (28.3, 4.3), (28.3, 3.0), (27.5, 1.7), (26.8, 0.6),
           (26.5, 0.0), (26.8, -0.6), (27.5, -1.7), (28.3, -3.0), (28.3, -4.3), (25.7, -3.3), (23.4, -2.4),
           (21.4, -1.6)]
    rays = [((22.6, 1.0), (27.9, 3.7), FIN[1]), ((22.6, -1.0), (27.9, -3.7), FIN[1]),
            ((23.0, 0.0), (25.8, 0.0), FIN[2])]
    spots = [(25.0, 2.0), (25.2, -1.8)]
    a = TAIL_REST + 8.0 * sway
    return (_rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays],
            _rot(spots, TAIL_PIVOT, a))


def _dorsal(ripple: float):
    tip = (10.9 + 0.5 * ripple, TOP(10.9) + 3.8)
    pts = [(9.4, TOP(9.4) - 0.6), tip, (13.1 + 0.6 * ripple, TOP(13.1) + 2.7), (14.6, TOP(14.6) + 0.6),
           (14.6, TOP(14.6) - 0.6)]
    rays = [((9.8, TOP(9.8)), tip, FIN[4]),                                   # lit leading ray
            ((12.1, TOP(12.1)), (13.0 + 0.6 * ripple, TOP(13.0) + 2.3), FIN[1])]
    spots = [(11.4, TOP(11.4) + 1.7)]
    return pts, rays, spots


def _adipose(ripple: float):
    pts = [(16.8, TOP(16.8) - 0.5), (17.4 + 0.2 * ripple, TOP(17.4) + 1.5), (18.6 + 0.3 * ripple, TOP(18.6) + 1.3),
           (19.1, TOP(19.1) - 0.5)]
    return pts, [], []


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=222, lit=None):
    """Paint a translucent fin: membrane, rays (segments with their own colour) and trout
    spots. With lit, fin pixels whose upper or left neighbour is open water catch the
    top-left light."""
    poly, rays, spots = fin
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
            for su, sv in spots:
                if cov[y, x] >= 0.9 and math.hypot(u - su, v - sv) < 0.62:
                    colour, a = SPOT[1], ray_alpha
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)


# ---- body -------------------------------------------------------------------------------
# Hand-painted over the procedural lens (BODY above is only the reference silhouette).
# Each row reads belly (left) -> back (right): belly edge, white belly, flank with scale
# marks, the rose/pink rainbow band with cyan lateral-line dots, a light upper flank, the
# dark periwinkle back with violet trout spots, and the rim light along the top contour.
#   f e d c b   back ramp (light -> dark)      s S   trout spots
#   6 5 4 3 2   flank / belly ramp              p q r band: rose, pink, cyan dot
#   W K         eye catchlight / pupil          h i   golden iris
#   o           mouth line (outline colour)
ART = {
    6: (4, "fffe"),
    7: (3, "feWKheed"),
    8: (3, "odKKicbbdd"),
    9: (3, "4ohqqceScbdd"),
    10: (4, "5bqpcqedcbbd"),
    11: (4, "265c4pqreScbd"),
    12: (5, "26c543pqqedSd"),
    13: (6, "266544pqredbd"),
    14: (7, "266534pqqeSbd"),
    15: (9, "266544pqrebd"),
    16: (11, "222654pqeSd"),
    17: (14, "222265pqed"),
    18: (18, "225qd"),
    19: (20, "22c"),
}

LETTERS = {"o": OUTLINE, "K": EYE[0], "W": EYE[1], "s": SPOT[0], "S": SPOT[1]}
LETTERS.update({k: c for k, c in zip("abcdef", BACK)})
LETTERS.update({k: c for k, c in zip("0123456", FLANK)})
LETTERS.update({k: c for k, c in zip("ghij", GOLD)})
LETTERS.update({k: c for k, c in zip("pqr", BAND)})

PIXELS = {(x0 + i, y): ch for y, (x0, row) in ART.items() for i, ch in enumerate(row) if ch != "."}
SPOTS = {k for k, ch in PIXELS.items() if ch in "sS"}


def _mask() -> np.ndarray:
    mask = np.zeros((SIZE, SIZE), bool)
    for x, y in PIXELS:
        mask[y, x] = True
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


def _body() -> Image.Image:
    img = canvas(SIZE)
    px = img.load()
    for (x, y), ch in PIXELS.items():
        px[x, y] = rgba(LETTERS[ch])
    return img


def _outline(img: Image.Image) -> Image.Image:
    """1 px outline around the silhouette: deep indigo beside the body, a softer violet
    where it only borders translucent fins."""
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


def _protected(x: int, y: int) -> bool:
    return PIXELS.get((x, y), "") in ("o", "K", "W", "h", "i")


def _blend(px, x, y, colour, k):
    c = rgba(colour)
    r, g, b, a = px[x, y]
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _iris_colour(phase: float) -> str:
    """Cyclic purple -> pink -> cyan -> purple."""
    phase %= 1.0
    i = int(phase * 3)
    return mix(IRIS[i], IRIS[(i + 1) % 3], phase * 3 - i)


def _iridescence(img: Image.Image, t: float):
    """An oil-slick sheen on the scales: the hue varies along the body and drifts with t;
    scale-lattice pixels catch it harder, so it reads as scales, not a wash."""
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if not MASK[y, x] or _protected(x, y) or (x, y) in SPOTS:
                continue
            kb, kv = int(KB[y, x]), int(KV[y, x])
            if kb < 1 or kv < 1:
                continue
            u = UC[y, x]
            if u < 4.5:
                continue
            scale = (y % 2 == 0) and ((x + (y // 2) % 2 * 2) % 4 == 1)
            ch = PIXELS[(x, y)]
            if ch in "pqr":
                k = 0.5                                   # the rainbow band itself shifts hue
            elif scale and ch in "bcde34":
                k = 0.3 if ch in "bc" else 0.45           # scale marks catch the sheen
            elif ch in "34":
                k = 0.08
            else:
                continue
            k *= min(1.0, (u - 4.5) / 3.0)
            _blend(px, x, y, _iris_colour(u / 16.0 + VC[y, x] / 20.0 - t), k)


def _glint(img: Image.Image, t: float, strength: float = 0.72, width: float = 3.0):
    """A soft glint sliding along the back from head to tail during t in [0, 0.4)."""
    if t >= 0.4:
        return
    centre = -1.0 + (SL + 3.0) * (t / 0.4)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            kb = int(KB[y, x])
            if kb < 0 or kb > 3 or _protected(x, y):
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / width) * strength * (1.0 - kb / 4.5)
            if k > 0:
                _blend(px, x, y, GLINT, k)


def _shimmer(img: Image.Image, t: float):
    """A band of sparkling scales rolling from head to tail during t in [0.45, 0.9)."""
    if not 0.45 <= t < 0.9:
        return
    centre = 3.0 + (SL + 1.0) * (t - 0.45) / 0.45
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if not MASK[y, x] or _protected(x, y):
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.0)
            if k <= 0:
                continue
            spot = y % 2 == 1 and (x + y // 2) % 2 == 0
            tint = _iris_colour(UC[y, x] / 16.0 - t + 0.33)
            _blend(px, x, y, "#fbfaff" if spot else mix(tint, "#ffffff", 0.5),
                   (0.9 if spot else 0.34) * min(1.0, k * 1.3))


# Twinkles: (x, y, colour, phase centre, reach) - each blinks once per loop, in turn.
TWINKLES = [(24, 6, SPARK_GOLD, 0.1, 2), (6, 21, SPARK_CYAN, 0.43, 2), (14, 25, SPARK_GOLD, 0.76, 2)]


def _twinkles(img: Image.Image, t: float):
    for x, y, colour, centre, reach in TWINKLES:
        d = (t - centre + 0.5) % 1.0 - 0.5
        amount = max(0.0, 1.0 - abs(d) / 0.16)
        sparkle(img, x, y, amount ** 1.2, colour=colour, reach=reach)


# Lower fins as hand-placed pixels in two poses each: R root (where the fin meets the
# body), m membrane, r ray, l lit leading edge. Pectoral and pelvic lie over the body.
PECTORAL = {
    "spread": [(8, 13, "R"), (9, 14, "r"), (10, 14, "m"), (9, 15, "m"), (10, 15, "r"), (11, 15, "m"),
               (10, 16, "l"), (11, 16, "r"), (9, 16, "l")],
    "folded": [(8, 13, "R"), (9, 14, "r"), (10, 14, "m"), (11, 14, "m"), (10, 15, "r"), (11, 15, "r"),
               (12, 15, "m"), (11, 16, "l")],
}
PELVIC = {
    "rest": [(12, 17, "r"), (13, 17, "m"), (13, 18, "l")],
    "swept": [(13, 17, "r"), (13, 18, "m"), (14, 18, "l")],
}
ANAL = {
    "rest": [(16, 18, "r"), (17, 18, "m"), (18, 19, "m"), (17, 19, "l")],
    "ripple": [(16, 18, "r"), (17, 18, "m"), (18, 19, "m"), (18, 20, "l")],
}
PIXEL_FIN = {"R": (FIN[1], 225), "r": (FIN[1], 222), "m": (FIN[3], 200), "l": (FIN[4], 205)}


def _pixel_fin(img: Image.Image, pixels, over_body: bool):
    """Paint pixel fins; over the body they tint the flank instead of replacing it."""
    px = img.load()
    for x, y, part in pixels:
        colour, alpha = PIXEL_FIN[part]
        if over_body and MASK[y, x]:
            base = px[x, y]
            k = 0.55 if part in "mR" else 0.7 if part == "r" else 0.45
            c = rgba(mix(base, colour, k))
            px[x, y] = (c[0], c[1], c[2], 255)
        elif not MASK[y, x]:
            c = rgba(colour)
            px[x, y] = (c[0], c[1], c[2], alpha)


def _frame(t: float) -> Image.Image:
    """One 32x32 frame at loop phase t (0..1)."""
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    # Medial fins sit behind the body; the near pectoral and pelvic fins lie over it.
    _paint_fin(img, _tail(sway), FIN[3], 204, cov_min=0.4, lit=FIN[5])
    _paint_fin(img, _dorsal(ripple), FIN[3], 202, lit=FIN[5])
    _paint_fin(img, _adipose(ripple), FIN[3], 210, cov_min=0.4, lit=FIN[4])
    _pixel_fin(img, ANAL["ripple" if ripple > 0.2 else "rest"], False)
    body = _body()
    _iridescence(body, t)
    _glint(body, t)
    _shimmer(body, t)
    img.alpha_composite(body)
    _pixel_fin(img, PECTORAL["folded" if math.sin(2 * math.pi * t + 1.0) > 0.35 else "spread"], True)
    _pixel_fin(img, PELVIC["swept" if flap > 0 else "rest"], True)
    img = _outline(img)
    _twinkles(img, t)
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
