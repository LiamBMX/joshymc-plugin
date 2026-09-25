"""Nether Pike: an epic fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's crimson body and bright salmon-red lateral stripe. A long,
torpedo-shaped pike: the flat duck-bill snout with its long gaping jaw line, the eye set
high on the head with an ember-gold iris, a gill cover, a dark crimson back under a lit
rim, the glowing salmon stripe, rows of ember-gold bean spots (the pike's pale spots,
nether style), a countershaded peach belly, the dorsal and anal fins set far back
opposite each other above a forked tail, and translucent ember fins with painted rays.

Animation (EPIC, 16 frames x 3 ticks): the tail wags once per loop with the lobes leading,
the dorsal, anal, pectoral and pelvic fins ripple, a soft glint slides along the back,
an iridescent purple / pink / cyan sheen flows over the scales and flares into a band that
rolls from head to tail, and three sparkles twinkle around the fish in turn.
"""
from __future__ import annotations

import math

import numpy as np

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_nether_pike"
NAME = "Nether Pike"
KIND = "item"
MODEL_KEY = "fish/nether_pike"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 16
FRAMETIME = 3

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean wine-violet, lights ember
OUTLINE = "#3a0a24"
FIN_OUTLINE = ("#5a1628", 232)
BACK = ["#3c0a22", "#560c26", "#721026", "#8c1826", "#aa2428", "#cc402c", "#ea6c3c"]
STRIPE = ["#bc3434", "#d8483a", "#ec6440", "#fa8c4e"]
FLANK = ["#8e2034", "#a82c3a", "#c04444"]
BELLY = ["#a84a56", "#cc7068", "#e4957e", "#f2b692", "#fcd6ae"]
EMBER = ["#e8781e", "#ffb238", "#ffe07a"]
FIN = ["#4a0c26", "#62102c", "#801a32", "#9c2a36", "#e8643a", "#ffa04c"]
FIN_HEAT = ["#701432", "#8e2036", "#b23636", "#dc5638", "#fa8642"]   # root -> ember tip
FIN_RAY = "#3e0a24"
FRONT_HEAT = ["#b8363c", "#d24c40", "#ea6a44", "#fb9050"]   # the near fins, over the body
EYE = ["#1c0612", "#fff6e2"]
IRIS = "#ffb030"
GLINT = "#fff2dc"
IRIDESCENT = ["#b46cff", "#ff66d0", "#62eeff"]   # purple -> pink -> cyan, cycled
SPARK = ["#fff0ff", "#eafdff", "#fff8ea"]

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(29.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 23.0                      # snout to tail base
SNOUT = (2.5, 8.2)             # snout tip in the frame
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


# Half-depths above (TOP) and below (BOT) the axis: a flat duck-bill snout, a long nearly
# cylindrical body and a slim tail stalk. The lower jaw is a touch deeper than the upper.
_K = SL / 24.0
TOP = _smooth([(u * _K, v) for u, v in [
    (0.0, 0.5), (1.2, 0.8), (2.5, 1.1), (3.7, 1.6), (4.9, 2.3), (6.3, 2.75), (8.3, 2.9), (10.8, 2.95),
    (13.3, 2.95), (15.8, 2.85), (18.2, 2.5), (20.6, 1.95), (22.6, 1.5), (24.0, 1.3)]])
BOT = _smooth([(u * _K, v) for u, v in [
    (0.0, 0.75), (1.2, 1.05), (2.5, 1.3), (3.7, 1.6), (5.1, 2.1), (6.6, 2.6), (8.6, 3.0), (11.0, 3.25),
    (13.6, 3.3), (16.1, 3.15), (18.5, 2.7), (20.9, 2.05), (22.8, 1.55), (24.0, 1.4)]])

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

TAIL_PIVOT = (22.4, 0.0)


def _tail(sway: float):
    """A forked pike tail with broad, slightly rounded lobes."""
    pts = [(21.8, 1.3), (23.5, 2.1), (25.3, 3.2), (27.1, 4.25), (28.2, 4.6), (28.6, 3.9), (27.9, 2.6),
           (26.9, 1.2), (26.3, 0.0),
           (26.9, -1.2), (27.9, -2.6), (28.6, -3.9), (28.2, -4.6), (27.1, -4.25), (25.3, -3.2), (23.5, -2.1),
           (21.8, -1.3)]
    rays = [((22.8, 0.7), (27.9, 4.0), FIN[0]), ((22.8, -0.7), (27.9, -4.0), FIN[0]),
            ((23.2, 0.0), (25.6, 0.0), FIN[0])]
    a = TAIL_REST + 8.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _dorsal(ripple: float):
    """Set far back, over the anal fin, with a rounded, higher rear lobe."""
    r = 0.5 * ripple
    pts = [(15.9, TOP(15.9) - 0.6), (16.6, TOP(16.6) + 1.7), (17.9, TOP(17.9) + 3.1 + r * 0.3),
           (19.6 + r, TOP(19.6) + 3.4 + r * 0.3), (21.0 + r, TOP(21.0) + 2.5), (21.8, TOP(21.8) + 0.9),
           (21.9, TOP(21.9) - 0.6)]
    rays = [((16.4, TOP(16.4)), (17.7, TOP(17.7) + 2.9), FIN[4]),                    # lit leading ray
            ((18.3, TOP(18.3)), (19.6 + r, TOP(19.6) + 3.0), FIN[0]),
            ((20.0, TOP(20.0)), (21.0 + r, TOP(21.0) + 2.1), FIN[0])]
    return pts, rays


def _anal(ripple: float):
    r = 0.5 * ripple
    pts = [(16.6, -BOT(16.6) + 0.6), (17.3, -BOT(17.3) - 1.6), (18.7, -BOT(18.7) - 2.8 - r * 0.3),
           (20.3 + r, -BOT(20.3) - 2.8), (21.5 + r, -BOT(21.5) - 1.8), (22.2, -BOT(22.2) + 0.6)]
    rays = [((18.0, -BOT(18.0)), (18.9 + r, -BOT(18.9) - 2.5), FIN[0]),
            ((20.0, -BOT(20.0)), (20.9 + r, -BOT(20.9) - 2.1), FIN[0])]
    return pts, rays


def _pectoral(flap: float):
    pivot = (10.6, -BOT(10.6) + 0.9)
    pts = [(10.1, -BOT(10.1) + 1.4), (11.5, -BOT(11.5) + 1.1), (14.2, -BOT(14.2) - 0.9), (13.1, -BOT(13.1) - 1.9),
           (10.6, -BOT(10.6) - 0.2)]
    rays = [(pivot, (13.6, -BOT(13.6) - 1.3), None)]
    a = 11.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _pelvic(flap: float):
    pivot = (14.3, -BOT(14.3))
    pts = [(13.8, -BOT(13.8) + 0.5), (14.9, -BOT(14.9) + 0.4), (16.4, -BOT(16.4) - 1.3),
           (15.3, -BOT(15.3) - 1.6)]
    a = 9.0 * flap
    return _rot(pts, pivot, a), []


def _paint_fin(img, fin, reach, alpha, cov_min=0.45, ray_alpha=228, lit=True, heat=None):
    """Paint a translucent fin whose membrane heats up from a wine-dark base to ember tips:
    reach(u, v) gives 0 at the root and 1 at the free margin, posterised onto FIN_HEAT.
    Rays are darker streaks over the membrane; with lit, margin pixels facing the top-left
    light are lifted a step."""
    poly, rays = fin
    cov = _poly(poly)
    inside = (cov >= cov_min) & ~MASK
    px = img.load()
    heat = heat or FIN_HEAT
    steps = len(heat)
    for y in range(SIZE):
        for x in range(SIZE):
            if cov[y, x] < cov_min:
                continue
            u, v = float(UC[y, x]), float(VC[y, x])
            k = max(0.0, min(0.999, reach(u, v)))
            i = int(k * steps)
            if lit and inside[y, x] and ((y > 0 and not inside[y - 1, x] and not MASK[y - 1, x])
                                         or (x > 0 and not inside[y, x - 1] and not MASK[y, x - 1])):
                i = min(steps - 1, i + 1)
            colour, a = heat[i], alpha + 6 * i
            if cov[y, x] >= 0.9 and any(_near(u, v, r_[0], r_[1], 0.45) for r_ in rays):
                colour, a = mix(heat[i], FIN_RAY, 0.45), ray_alpha
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, min(255, a))


# ---- body -------------------------------------------------------------------------------

BODY_ADD: list[tuple[int, int]] = []
BODY_CUT: list[tuple[int, int]] = []


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

LETTERS = {"o": OUTLINE, "K": EYE[0], "W": EYE[1], "i": IRIS}
LETTERS.update({k: c for k, c in zip("abcdefg", BACK)})
LETTERS.update({k: c for k, c in zip("qrst", STRIPE)})
LETTERS.update({k: c for k, c in zip("lmn", FLANK)})
LETTERS.update({k: c for k, c in zip("01234", BELLY)})
LETTERS.update({k: c for k, c in zip("xyz", EMBER)})

HEAD: dict[tuple[int, int], str] = {
    (4, 9): "a", (5, 10): "a", (6, 10): "a", (7, 11): "b",           # long gaping mouth line
    (4, 10): "3", (5, 11): "2", (6, 11): "3", (6, 12): "2", (7, 12): "3",   # pale lower jaw
    (9, 10): "W", (10, 10): "K", (9, 11): "K", (10, 11): "K",         # eye + catchlight
    (8, 10): "x", (11, 11): "i",                                      # ember-gold iris
    (12, 11): "b", (12, 12): "b", (11, 13): "b", (11, 14): "b", (10, 15): "c",   # gill cover edge
}
# The pike's rows of pale bean spots, as nether embers: gold on the upper flank, pale
# amber below the stripe.
SPOTS = {(13, 12): EMBER[1], (16, 14): EMBER[1], (19, 16): EMBER[1]}


def _band_colour(x: int, y: int) -> str:
    kb, kv = int(KB[y, x]), int(KV[y, x])
    u, v = float(UC[y, x]), float(VC[y, x])
    top, bot = float(TOP(min(max(u, 0.0), SL))), float(BOT(min(max(u, 0.0), SL)))
    n = (v + bot) / (top + bot)                     # 0 at the belly, 1 at the back
    if kb == 0:
        return BACK[5] if u < 10 else BACK[4]         # rim light on the back
    if kv == 0:
        return BELLY[0]                               # shaded belly edge
    if n > 0.76:
        return BACK[2]
    if n > 0.6:
        return BACK[3]
    if n > 0.5:
        return STRIPE[3] if u < 17 else STRIPE[2]      # glowing lateral stripe, lit core
    if n > 0.4:
        return STRIPE[1]
    if n > 0.27:
        return FLANK[2]
    return BELLY[3] if u < 14 else BELLY[2]


def _body():
    img = canvas(SIZE)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                px[x, y] = rgba(_band_colour(x, y))
    for (x, y), colour in SPOTS.items():
        px[x, y] = rgba(colour)
    for (x, y), letter in HEAD.items():
        px[x, y] = rgba(LETTERS[letter])
    return img


def _outline(img):
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


def _tail_reach(u, v):
    return (u - 22.6) / 5.2 + abs(v) * 0.05 + 0.12


def _dorsal_reach(u, v):
    return (v - float(TOP(min(u, SL)))) / 3.5 + 0.1


def _anal_reach(u, v):
    return (-v - float(BOT(min(u, SL)))) / 2.9 + 0.1


def _pect_reach(u, v):
    return math.hypot(u - 10.6, v + float(BOT(10.6))) / 3.4


def _pelvic_reach(u, v):
    return math.hypot(u - 14.3, v + float(BOT(14.3))) / 2.6


def _cycle(p: float) -> str:
    """The iridescent hue at phase p (0..1): purple -> pink -> cyan -> purple."""
    p = (p % 1.0) * len(IRIDESCENT)
    i = int(p)
    return mix(IRIDESCENT[i], IRIDESCENT[(i + 1) % len(IRIDESCENT)], p - i)


def _scale_spot(x: int, y: int) -> bool:
    """A diamond lattice of scale highlights."""
    return (x + y) % 2 == 0 and (x - y) % 4 == 0


def _sheen(img, t: float) -> None:
    """Iridescence: once per loop a band rolls over the scales from head to tail, splitting
    into purple, pink and cyan stripes across the flank that drift as it goes, lighting the
    scale lattice and cresting white along the back (the glint)."""
    px = img.load()
    centre = -7.0 + (SL + 14.0) * t
    for y in range(SIZE):
        for x in range(SIZE):
            if not MASK[y, x] or (x, y) in HEAD:
                continue
            u = float(UC[y, x])
            d = (u - centre) / 5.0
            if abs(d) >= 1.0:
                continue
            band = 1.0 - abs(d)
            kb, kv = int(KB[y, x]), int(KV[y, x])
            v = float(VC[y, x])
            tint = _cycle(v * 0.15 - u * 0.035 + d * 0.12 - t)
            spot = _scale_spot(x, y) and kb >= 1 and kv >= 1
            colour = mix(px[x, y], tint, band ** 0.6 * (0.85 if spot else 0.47))
            if kb == 0:                            # white crest of the wave along the back
                colour = mix(colour, GLINT, band * 0.6)
            if spot and band > 0.5:
                colour = mix(colour, SPARK[1], (band - 0.5) * 1.3)
            px[x, y] = rgba(colour)


SPARKLES = [  # (x, y, phase, colour): three twinkles around the fish, one after another
    (14, 6, 0.1, SPARK[0]), (27, 12, 0.43, SPARK[1]), (7, 21, 0.76, SPARK[2]),
]


def _sparkles(img, t: float) -> None:
    for x, y, phase, colour in SPARKLES:
        d = abs(((t - phase + 0.5) % 1.0) - 0.5)
        amount = max(0.0, 1.0 - d / 0.17)
        sparkle(img, x, y, amount, colour=colour, reach=2)


def _frame(t: float):
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))
    img = canvas(SIZE)
    _paint_fin(img, _tail(sway), _tail_reach, 200, cov_min=0.4)
    _paint_fin(img, _dorsal(ripple), _dorsal_reach, 196)
    _paint_fin(img, _anal(ripple), _anal_reach, 192)
    body = _body()
    _sheen(body, t)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), _pect_reach, 214, heat=FRONT_HEAT)
    _paint_fin(front, _pelvic(flap), _pelvic_reach, 214, heat=FRONT_HEAT)
    img.alpha_composite(front)
    img = _outline(img)
    _sparkles(img, t)
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
