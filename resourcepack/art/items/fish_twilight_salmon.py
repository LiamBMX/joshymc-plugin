"""Twilight Salmon: a rare fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's violet body, lavender-pink belly and gold accents. Real salmon
anatomy: a deep, streamlined body, a pointed snout whose mouth runs back below the eye over
a pale hooked kype, a gill cover, one mid-body dorsal fin, the small adipose fin on the back
before the tail, a low pectoral lying over the flank, pelvic and anal fins and a broad,
shallowly forked tail with dark spots. The colours read as a dusk sky: an indigo back with
dark salmon spots and two golden "first stars", a violet flank with scale marks, a glowing
magenta-rose spawning stripe over a thin peach horizon line and a pale lavender belly; the
eye has a golden iris and the tail lobes warm to dusky rose.

Animation (RARE, 12 frames x 3 ticks): the tail and fins sway about a pixel on offset
sine phases and the back stars flicker; a soft glint slides along the back (frames 0-4),
then a blue twilight sheen with glittering scales rolls across the body (6-10), while three
stars twinkle around the fish in turn.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_twilight_salmon"
NAME = "Twilight Salmon"
KIND = "item"
MODEL_KEY = "fish/twilight_salmon"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean blue, lights lean warm ----
OUTLINE = "#1c0f3c"
FIN_OUTLINE = ("#3a2366", 235)
BACK = ["#261856", "#36226f", "#482e87", "#5d3b9b", "#7a57b6", "#a086d2"]       # indigo back
VIOLET = ["#5d3198", "#7640ad", "#8e54bf"]                                          # flank
ROSE = ["#b0418f", "#d05a9e", "#ee83b1", "#ffb3c8"]                                 # spawning stripe
PEACH = ["#e89a7a", "#ffc58e"]                                                      # horizon line
BELLY = ["#8d62b0", "#a982c8", "#c4a0da", "#d9bde8", "#ecdcf5"]                     # lavender belly
CHEEK = ["#743c9e", "#8e4fae"]                                                      # rosy gill plate
GOLD = ["#8a5a1e", "#c88a2a", "#f2bf45", "#ffe28a"]
SPOT = ["#1f1245", "#281755"]
FIN = ["#3b2470", "#4f3288", "#6a47a3", "#8a67bd", "#ad8fd6", "#d2bdec"]
LOWFIN = ["#4a2575", "#643289", "#83469f", "#a664b3", "#c98cc9"]                   # rosy lower fins
TAIL_TIP = "#c576b8"
TAILFIN = ["#36215f", "#4a2c82", "#62409c", "#8060b6", "#a487d2"]
EYE = ["#0c0820", "#ffffff"]
GILL_DARK = "#4a1a78"
GLINT = "#fff4f8"
SHEEN = "#9fd2ff"
GLITTER = "#e8f7ff"
STAR = ["#ffe9a6", "#dff3ff"]

# ---- geometry (pixels) --------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 21.6                      # snout to tail base
SNOUT = (3.2, 8.6)             # snout tip in the frame
SS = 4                         # supersampling per pixel side
TAIL_REST = 19.0                # the tail rests bent a little toward the back (degrees)


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


# Half-depths of the body above (TOP) and below (BOT) the axis: a deep, humped salmon
# with a thick tail stock.
TOP = _smooth([(0.0, 0.45), (1.0, 0.9), (2.1, 1.45), (3.3, 2.05), (4.6, 2.65), (6.1, 3.3), (7.9, 3.95),
               (9.8, 4.35), (11.8, 4.3), (13.8, 3.85), (15.8, 3.15), (17.7, 2.4), (19.5, 1.85), (21.0, 1.6),
               (22.0, 1.6)])
BOT = _smooth([(0.0, 0.6), (1.0, 1.15), (2.1, 1.75), (3.3, 2.35), (4.6, 2.95), (6.1, 3.55), (7.9, 4.1),
               (9.8, 4.45), (11.8, 4.4), (13.8, 3.9), (15.8, 3.2), (17.7, 2.45), (19.5, 1.9), (21.0, 1.6),
               (22.0, 1.6)])

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


# ---- fins: (polygon, rays) in local coordinates; v > 0 is the back ------------------------

TAIL_PIVOT = (21.4, 0.0)


def _tail(sway: float):
    """Broad, shallowly forked salmon tail."""
    pts = [(20.8, 1.5), (22.6, 2.3), (24.6, 3.4), (27.3, 4.7), (26.8, 3.5), (25.7, 1.9), (25.2, 0.7), (24.9, 0.0),
           (25.2, -0.7), (25.7, -1.9), (26.8, -3.5), (27.3, -4.7), (24.6, -3.4), (22.6, -2.3), (20.8, -1.5)]
    rays = [((22.0, 0.9), (26.8, 4.1), TAILFIN[2]), ((22.0, -0.9), (26.8, -4.1), TAILFIN[2]),
            ((22.4, 0.2), (25.0, 0.6), TAILFIN[2])]
    a = TAIL_REST + 10.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _dorsal(ripple: float):
    tip = (10.6 + 0.4 * ripple, TOP(10.6) + 3.0)
    pts = [(8.6, TOP(8.6) - 0.6), tip, (11.9 + 0.4 * ripple, TOP(11.9) + 2.5), (13.6, TOP(13.6) + 0.5),
           (13.6, TOP(13.6) - 0.6)]
    rays = [((9.0, TOP(9.0)), tip, FIN[4]),                                   # lit leading ray
            ((11.1, TOP(11.1)), (12.0 + 0.4 * ripple, TOP(12.0) + 2.1), FIN[1])]
    return pts, rays


def _adipose(ripple: float):
    """The small fleshy fin on the back between the dorsal and the tail: salmon's mark."""
    pts = [(17.4, TOP(17.4) - 0.5), (18.4 + 0.3 * ripple, TOP(18.4) + 1.35), (19.6 + 0.3 * ripple, TOP(19.6) + 1.2),
           (19.9, TOP(19.9) - 0.5)]
    return pts, []


def _pectoral(flap: float):
    """Low on the flank just behind the gill cover, lying over the body, tip swept back."""
    pivot = (7.6, -BOT(7.6) + 1.5)
    pts = [(7.2, -BOT(7.2) + 1.9), (8.4, -BOT(8.4) + 1.7), (10.8, -BOT(10.8) + 0.1), (10.3, -BOT(10.3) - 0.8),
           (7.6, -BOT(7.6) + 0.8)]
    rays = [(pivot, (10.4, -BOT(10.4) - 0.2), LOWFIN[1])]
    a = 9.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _pelvic(flap: float):
    pivot = (12.4, -BOT(12.4) + 0.3)
    pts = [(11.8, -BOT(11.8) + 0.6), (13.1, -BOT(13.1) + 0.5), (15.1, -BOT(15.1) - 1.3),
           (13.9, -BOT(13.9) - 1.7)]
    a = 8.0 * flap
    return _rot(pts, pivot, a), []


def _anal(ripple: float):
    pts = [(15.8, -BOT(15.8) + 0.5), (16.4, -BOT(16.4) - 1.5 - 0.3 * ripple), (19.2, -BOT(19.2) - 0.9),
           (19.8, -BOT(19.8) + 0.5)]
    rays = [((16.6, -BOT(16.6)), (16.6, -BOT(16.6) - 1.4 - 0.3 * ripple), LOWFIN[0])]
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


# ---- body ---------------------------------------------------------------------------------

BODY_ADD = [(3, 10)]                # the kype: hooked lower-jaw tip
BODY_CUT: list = []


def _mask() -> np.ndarray:
    mask = BODY.copy()
    for x, y in BODY_ADD:
        mask[y, x] = True
    for x, y in BODY_CUT:
        mask[y, x] = False
    return mask


MASK = _mask()



LETTERS = {"o": OUTLINE, "K": EYE[0], "W": EYE[1]}
LETTERS.update({k: c for k, c in zip("abcdef", BACK)})
LETTERS.update({k: c for k, c in zip("lmn", VIOLET)})
LETTERS.update({k: c for k, c in zip("pqrs", ROSE)})
LETTERS.update({k: c for k, c in zip("01234", BELLY)})
LETTERS.update({k: c for k, c in zip("ghij", GOLD)})
LETTERS.update({"x": SPOT[0], "y": SPOT[1], "t": PEACH[0], "u": PEACH[1]})

# Head details: (x, y) -> letter. The 2x2 eye with its catchlight and golden iris, the
# mouth running back from the snout to below the eye over the pale kype, and the gill cover.
HEAD = {
    (3, 8): "e", (4, 8): "f", (3, 9): "d", (4, 9): "e", (5, 9): "d",   # snout and upper jaw
    (4, 10): "x", (5, 10): "x", (6, 11): "y",                         # mouth, back to below the eye
    (3, 10): "2", (4, 11): "3", (5, 11): "3", (6, 12): "2",           # pale lower jaw, kype tip
    (7, 10): "W", (8, 10): "K", (7, 11): "K", (8, 11): "K",           # eye
    (9, 11): "i", (8, 12): "h",                                       # golden iris
}
GILL = [(11, 10), (11, 11), (10, 12), (10, 13), (9, 14)]
# Dark salmon spots on the back, and golden first stars among them.
SPOTS = [(13, 10), (15, 12), (12, 12), (17, 13), (19, 15), (16, 14)]
STARS_ON_BACK = [(14, 11), (18, 14)]


def _depth(x: int, y: int) -> float:
    """0 at the back contour, 1 at the belly contour, measured across the body axis."""
    u, v = float(UC[y, x]), float(VC[y, x])
    top, bot = float(TOP(u)), float(BOT(u))
    return max(0.0, min(1.0, (top - v) / (top + bot)))


def _open(x: int, y: int) -> bool:
    return not (0 <= x < SIZE and 0 <= y < SIZE and MASK[y, x])


def _zone(x: int, y: int) -> str:
    if _open(x, y - 1) or (_open(x - 1, y) and _depth(x, y) < 0.5):
        return "rim"
    if _open(x, y + 1) or (_open(x + 1, y) and _depth(x, y) > 0.5):
        return "keel"
    f = _depth(x, y)
    for limit, name in BANDS:
        if f < limit:
            return name
    return "belly"


# (upper depth limit, zone) from the back down
BANDS = [(0.15, "back0"), (0.28, "back"), (0.38, "violet"), (0.50, "rose"), (0.595, "rose1"),
         (0.67, "horizon"), (0.81, "belly0"), (1.01, "belly")]


def _scale_mark(x: int, y: int) -> bool:
    """A sparse diamond lattice (image space) of darker scale edges."""
    return y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1


def _is_head(x: int, y: int) -> bool:
    """In front of the gill cover (which curves back toward the throat)."""
    return float(UC[y, x]) < 7.4 - 0.3 * float(VC[y, x])


def _band_colour(x: int, y: int) -> str:
    zone = _zone(x, y)
    u = float(UC[y, x])
    if _is_head(x, y):
        return {"rim": BACK[4] if u > 1.5 else BACK[3], "keel": BELLY[1], "back0": BACK[2], "back": BACK[3],
                "violet": CHEEK[1], "rose": CHEEK[1], "rose1": CHEEK[0], "horizon": PEACH[1],
                "belly0": BELLY[3], "belly": BELLY[2]}[zone]
    if zone == "rim":
        return BACK[5] if u < 9 else BACK[4] if u < 15 else BACK[3]
    if zone == "keel":
        return BELLY[0] if _depth(x, y) > 0.5 else BACK[1]
    if zone == "back0":
        return BACK[2] if u < 8 else BACK[1]
    scale = _scale_mark(x, y) and u > 8.5
    if zone == "back":
        return BACK[1] if scale else BACK[2]
    if zone == "violet":
        return VIOLET[0] if scale or u >= 17 else VIOLET[1]
    if zone == "rose":
        return ROSE[1] if scale or u >= 16 else ROSE[2]
    if zone == "rose1":
        return ROSE[0] if scale or u >= 17 else ROSE[1]
    if zone == "horizon":
        return PEACH[1] if u < 14 else PEACH[0]
    if zone == "belly0":
        return BELLY[3] if u < 13 else BELLY[2]
    return BELLY[2] if u < 15 else BELLY[1]


def _body() -> Image.Image:
    img = canvas(SIZE)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                px[x, y] = rgba(_band_colour(x, y))
    for x, y in SPOTS:
        px[x, y] = rgba(SPOT[0])
    for x, y in STARS_ON_BACK:
        px[x, y] = rgba(GOLD[3])
    for x, y in GILL:
        px[x, y] = rgba(mix(px[x, y], GILL_DARK, 0.55))
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


# Pectoral fin poses, hand-placed pixels: L lit leading ray, M membrane, R ray, D dark tip.
PECT = {
    "spread": {(9, 15): "L", (10, 16): "L", (11, 17): "L", (9, 16): "M", (10, 17): "R", (11, 18): "M",
               (12, 18): "D"},
    "folded": {(9, 15): "L", (10, 15): "M", (11, 16): "L", (12, 17): "L", (10, 16): "R", (11, 17): "R",
               (12, 18): "D"},
}
PECT_COLOURS = {"L": LOWFIN[4], "M": LOWFIN[3], "R": LOWFIN[1], "D": LOWFIN[2]}


TAIL_SPOTS = [(23.6, 1.9), (25.4, 3.3), (24.2, -2.1), (22.8, -0.6)]


def _paint_tail(img: Image.Image, sway: float):
    """The tail fan, warming from violet at the root to dusky rose at the lobe tips, with
    a few dark salmon spots that ride along as it sways."""
    layer = canvas(SIZE)
    _paint_fin(layer, _tail(sway), TAILFIN[3], 212, cov_min=0.4, lit=TAILFIN[4])
    px = layer.load()
    for y in range(SIZE):
        for x in range(SIZE):
            r, g, b, a = px[x, y]
            if not a:
                continue
            d = math.hypot(float(UC[y, x]) - TAIL_PIVOT[0], float(VC[y, x]) - TAIL_PIVOT[1])
            k = max(0.0, min(1.0, (d - 3.0) / 3.0)) * 0.45
            c = rgba(mix((r, g, b, a), TAIL_TIP, k))
            px[x, y] = (c[0], c[1], c[2], a)
    angle = TAIL_REST + 10.0 * sway
    for u, v in _rot(TAIL_SPOTS, TAIL_PIVOT, angle):
        x = round(SNOUT[0] + u * AX[0] + v * UP[0] - 0.5)
        y = round(SNOUT[1] + u * AX[1] + v * UP[1] - 0.5)
        if 0 <= x < SIZE and 0 <= y < SIZE and px[x, y][3] and not MASK[y, x]:
            r, g, b, _ = rgba(TAILFIN[0])
            px[x, y] = (r, g, b, 235)
    img.alpha_composite(layer)


def _paint_pectoral(img: Image.Image, flap: float):
    px = img.load()
    for (x, y), part in PECT["folded" if flap > 0.25 else "spread"].items():
        r, g, b, _ = rgba(PECT_COLOURS[part])
        px[x, y] = (r, g, b, 225 if MASK[y, x] else 212)


ZONES = {(x, y): _zone(x, y) for y in range(SIZE) for x in range(SIZE) if MASK[y, x]}
EYE_PIXELS = {k for k, v in HEAD.items() if v in "WKx"}
BACK_ZONES = ("rim", "back0", "back")
# Stars twinkling around the fish: (x, y, colour, peak phase, reach)
STARS = [(22, 8, STAR[0], 0.22, 2), (5, 17, STAR[1], 0.55, 2), (29, 20, STAR[0], 0.86, 1)]


def _mixpx(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _pulse(t: float, peak: float, width: float = 0.2) -> float:
    """0 -> 1 -> 0 around `peak` (loop phase), lasting `width` of the loop on each side."""
    d = abs(((t - peak + 0.5) % 1.0) - 0.5)
    return max(0.0, 1.0 - d / width) ** 1.5


def _glint(img: Image.Image, t: float):
    """Frames 0-4: a soft glint slides along the back from snout to tail."""
    if t >= 0.42:
        return
    centre = -1.0 + 25.0 * (t / 0.42)
    px = img.load()
    for (x, y), zone in ZONES.items():
        if zone not in BACK_ZONES + ("violet",) or (x, y) in EYE_PIXELS:
            continue
        strength = {"rim": 0.8, "back0": 0.62, "back": 0.5}.get(zone, 0.3)
        k = min(1.0, 1.3 * max(0.0, 1.0 - abs(float(UC[y, x]) - centre) / 3.6)) * strength
        if k > 0:
            _mixpx(px, x, y, GLINT, k)


def _glitter_spot(x: int, y: int) -> bool:
    """A diamond lattice of scales that catch the light as the sheen passes."""
    return y % 2 == 1 and (x + y // 2) % 3 == 0


def _sheen(img: Image.Image, t: float):
    """Frames 6-10: a blue twilight sheen rolls head to tail, glittering on the scales."""
    if not 0.46 < t < 0.92:
        return
    centre = 2.0 + 22.0 * (t - 0.5) / 0.34
    px = img.load()
    for (x, y), zone in ZONES.items():
        if (x, y) in EYE_PIXELS or float(UC[y, x]) < 5.5:
            continue
        k = max(0.0, 1.0 - abs(float(UC[y, x]) - centre) / 4.0)
        if k <= 0:
            continue
        if _glitter_spot(x, y) and zone not in ("rim", "keel"):
            _mixpx(px, x, y, GLITTER, 0.9 * min(1.0, 1.4 * k))
        else:
            _mixpx(px, x, y, SHEEN, 0.5 * k)


def _frame(t: float) -> Image.Image:
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    _paint_tail(img, sway)
    _paint_fin(img, _dorsal(ripple), FIN[2], 205, lit=FIN[4])
    _paint_fin(img, _adipose(ripple), FIN[2], 215, lit=FIN[3])
    _paint_fin(img, _anal(ripple), LOWFIN[2], 205, lit=LOWFIN[3])
    body = _body()
    bpx = body.load()
    for i, (x, y) in enumerate(STARS_ON_BACK):          # the first stars on the back flicker
        bpx[x, y] = rgba(mix(GOLD[2], GOLD[3], wave(t, 0.5 * i)))
    _glint(body, t)
    _sheen(body, t)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_pectoral(front, flap)
    _paint_fin(front, _pelvic(flap), LOWFIN[2], 215)
    img.alpha_composite(front)
    img = _outline(img)
    for x, y, colour, peak, reach in STARS:
        amount = _pulse(t, peak)
        if amount > 0.05:
            sparkle(img, x, y, amount, colour=colour, reach=reach)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
