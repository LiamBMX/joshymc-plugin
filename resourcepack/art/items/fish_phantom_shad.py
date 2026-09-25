"""Phantom Shad: a rare spectral shad for the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's pale ghostly periwinkle / lavender scheme with its gold accents.
A deep, flat-sided shad body with an arched, scute-keeled belly: a twilight-indigo back
under a moonlit rim, a pale ice-blue horizon line, lavender iridescence with staggered
scale marks over a near-white belly, the shad's shoulder spot and fading row of spots in
ghostly violet, a big eye with a glowing golden iris, the notched upper jaw over the
jutting lower jaw, one mid-body dorsal fin, low pectoral, pelvic and long anal fins and a
deeply forked tail whose lobes fade out into translucent phantom wisps.

Animated (RARE, 12 frames x 3 ticks): tail and fins sway about a pixel on offset phases,
a soft moonlight glint slides along the back (frames 0-4), then a blue-tinted sheen band
of glittering scales rolls from head to tail (frames 6-10); the violet spots breathe and
three ice-blue twinkles pop in turn in the open water around the fish.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_phantom_shad"
NAME = "Phantom Shad"
KIND = "item"
MODEL_KEY = "fish/phantom_shad"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palettes (darkest -> lightest): ghostly periwinkle, lavender and the old gold -------
OUTLINE = "#231d4a"
FIN_OUTLINE = ("#4a4585", 225)     # softer edge where the outline only touches a fin
BACK = ["#28265a", "#363875", "#4a5092", "#626cae", "#8590cc", "#b6bfee"]
ICE = ["#8fb3e6", "#b9d6fa", "#e0efff"]
SILVER = ["#6a6499", "#8680ba", "#a29dd4", "#bcb8e8", "#d4d2f4", "#e9e9fc", "#fbfbff"]
SHEEN = ["#a298dc", "#bcb2f0"]
SPOT = ["#352a6c", "#4b4089", "#6a60a8", "#948dca"]
GOLD = ["#8a561c", "#cc8d22", "#ffc832", "#fff0a8"]
FIN = ["#5a5ca2", "#7075b8", "#8e97d3", "#aebaec", "#ced9f9", "#ecf2ff"]
TAIL_EDGE = "#5d5aa0"
EYE = ["#110d2a", "#ffffff"]
GLINT = "#f4f7ff"
SHIMMER = "#b2d8ff"                # the blue-tinted sheen
SPARK = "#f2fbff"
TWINKLE = "#a9dcff"        # twinkle arms (ice blue, so they read on the grey slot)
TWINKLE_CORE = "#ffffff"

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 23.0                      # snout to tail base
SNOUT = (2.8, 7.2)             # snout tip in the frame
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


# Half-depths above (TOP) and below (BOT) the axis: a gently arched back and the shad's
# deep, keeled belly, narrowing to a slim tail stalk.
TOP = _smooth([(0.0, 0.6), (0.9, 1.2), (1.8, 1.75), (3.0, 2.3), (4.3, 2.8), (5.8, 3.2), (7.5, 3.5),
               (9.3, 3.65), (11.3, 3.6), (13.4, 3.35), (15.6, 2.85), (17.8, 2.2), (20.0, 1.6), (22.0, 1.2),
               (23.0, 1.1)])
BOT = _smooth([(0.0, 0.6), (0.9, 1.25), (1.8, 1.95), (3.0, 2.75), (4.3, 3.45), (5.8, 4.1), (7.5, 4.65),
               (9.3, 5.0), (11.3, 5.0), (13.4, 4.6), (15.6, 3.8), (17.8, 2.8), (20.0, 1.9), (22.0, 1.3),
               (23.0, 1.1)])

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

TAIL_PIVOT = (22.5, 0.0)


def _tail(sway: float):
    pts = [(21.9, 1.1), (23.6, 2.0), (25.8, 3.5), (29.4, 5.4), (28.5, 3.7), (27.0, 2.0), (25.8, 0.8), (25.0, 0.0),
           (25.8, -0.8), (27.0, -2.0), (28.5, -3.7), (29.4, -5.4), (25.8, -3.5), (23.6, -2.0), (21.9, -1.1)]
    rays = [((22.9, 0.7), (28.3, 4.5), FIN[1]), ((22.9, -0.7), (28.3, -4.5), FIN[1]),
            ((23.1, 0.0), (24.7, 0.0), FIN[1])]
    a = TAIL_REST + 8.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _dorsal(ripple: float):
    tip = (10.6 + 0.5 * ripple, TOP(10.6) + 4.0)
    pts = [(8.8, TOP(8.8) - 0.6), tip, (12.0 + 0.6 * ripple, TOP(12.0) + 3.0), (14.0, TOP(14.0) + 0.6),
           (14.0, TOP(14.0) - 0.6)]
    rays = [((9.2, TOP(9.2)), tip, FIN[4]),                                   # lit leading ray
            ((11.4, TOP(11.4)), (12.3 + 0.6 * ripple, TOP(12.3) + 2.6), FIN[1])]
    return pts, rays


def _pectoral(flap: float):
    pivot = (8.0, -BOT(8.0) + 0.9)
    pts = [(7.5, -BOT(7.5) + 1.4), (8.9, -BOT(8.9) + 1.0), (11.8, -BOT(11.8) - 0.8), (10.9, -BOT(10.9) - 1.7),
           (8.1, -BOT(8.1) - 0.3)]
    rays = [(pivot, (11.1, -BOT(11.1) - 1.2), FIN[2])]
    a = 10.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _pelvic(flap: float):
    pivot = (12.0, -BOT(12.0))
    pts = [(11.4, -BOT(11.4) + 0.5), (12.7, -BOT(12.7) + 0.4), (14.5, -BOT(14.5) - 1.6),
           (13.4, -BOT(13.4) - 1.8)]
    a = 9.0 * flap
    return _rot(pts, pivot, a), []


def _anal(ripple: float):
    pts = [(15.2, -BOT(15.2) + 0.5), (15.9, -BOT(15.9) - 1.5 - 0.3 * ripple), (20.4, -BOT(20.4) - 1.0),
           (21.0, -BOT(21.0) + 0.5)]
    rays = [((16.1, -BOT(16.1)), (16.1, -BOT(16.1) - 1.4 - 0.3 * ripple), FIN[1])]
    return pts, rays


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=228, lit=None, edge=None, fade=None):
    """Paint a translucent fin: membrane plus rays (segments with their own colour). With
    lit, fin pixels whose upper or left neighbour is open water catch the top-left light;
    with edge, pixels whose lower or right neighbour is open water take a dusky edge; with
    fade=(u0, u1, alpha_min), the fin thins toward alpha_min between u0 and u1 (the
    phantom's wispy tail)."""
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
            elif edge and inside[y, x] and ((y < SIZE - 1 and not inside[y + 1, x] and not MASK[y + 1, x])
                                            or (x < SIZE - 1 and not inside[y, x + 1] and not MASK[y, x + 1])):
                colour = edge
            if fade:
                u0, u1, amin = fade
                k = max(0.0, min(1.0, (u - u0) / (u1 - u0)))
                a = round(a + (amin - a) * k)
                colour = mix(colour, FIN[4], 0.45 * k)
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)


# ---- body -------------------------------------------------------------------------------
# The body silhouette is the procedural lens above with a few hand edits; its colours come
# from discrete bands counted in from the dorsal and ventral contours (clean staircases that
# follow the outline), then hand-placed head details on top.

BODY_ADD = [(2, 8)]
BODY_CUT = [(3, 7)]


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

# Palette letters for the hand-placed details.
LETTERS = {"o": OUTLINE, "K": EYE[0], "W": EYE[1]}
LETTERS.update({k: c for k, c in zip("abcdef", BACK)})
LETTERS.update({k: c for k, c in zip("qrs", ICE)})
LETTERS.update({k: c for k, c in zip("0123456", SILVER)})
LETTERS.update({k: c for k, c in zip("ghij", GOLD)})
LETTERS.update({k: c for k, c in zip("uv", SHEEN)})
LETTERS.update({k: c for k, c in zip("xyzw", SPOT)})

# Head details: (x, y) -> letter. Eye with a catchlight and a glowing golden iris, the
# notched upper jaw over the jutting lower jaw, the cheek and the curved gill-cover edge.
HEAD = {
    (2, 8): "4", (3, 8): "o", (4, 8): "c",
    (3, 9): "5", (4, 9): "2", (5, 9): "W", (6, 9): "K", (7, 9): "i",
    (4, 10): "4", (5, 10): "K", (6, 10): "K", (7, 10): "h", (8, 10): "1",
    (4, 11): "3", (5, 11): "j", (6, 11): "i", (7, 11): "5", (8, 11): "5", (9, 11): "1",
    (5, 12): "3", (6, 12): "5", (7, 12): "4", (8, 12): "1",
    (6, 13): "3", (7, 13): "1",
}
EYE_PIXELS = {(5, 9), (6, 9), (5, 10), (6, 10)}
# The shoulder spot and the fading row of smaller spots behind it.
SPOTS = {
    (10, 13): "x", (11, 13): "x", (10, 14): "x", (11, 14): "y",
    (13, 15): "y", (15, 16): "z", (17, 17): "w",
}


def _band_colour(x: int, y: int) -> str:
    kb, kv = int(KB[y, x]), int(KV[y, x])
    h = kb + kv + 1
    nb = 1 if h <= 4 else 2 if h <= 8 else 3
    if kb == 0:
        return BACK[5] if x < 11 else BACK[4]      # moonlit rim on the back
    if kb <= nb:                                    # twilight-indigo back, darkening down
        return [BACK[2], BACK[1]][kb - 1] if nb == 2 else BACK[2] if nb == 1 else BACK[4 - kb]
    if kb == nb + 1:
        return ICE[1] if x < 16 else ICE[0]        # pale ice-blue horizon line
    if kv == 0:
        return SILVER[2] if x % 2 == 0 else SILVER[3]   # saw-toothed keel of scutes
    if kb == nb + 2 and kv >= 2:                    # lavender iridescence with scale marks
        return SHEEN[0] if (x + y) % 3 == 0 else SHEEN[1]
    if kb == nb + 3 and kv >= 3 and x >= 8 and (x + y) % 3 == 2:
        return SHEEN[1]                             # second, staggered scale row
    if kv == 1:
        return SILVER[6]
    if kv == 2:
        return SILVER[5]
    return SILVER[4]


def _body(t: float) -> Image.Image:
    img = canvas(SIZE)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                px[x, y] = rgba(_band_colour(x, y))
    for (x, y), letter in HEAD.items():
        px[x, y] = rgba(LETTERS[letter])
    # the ghostly spots breathe: a soft violet glow rises and sinks across the row
    for i, ((x, y), letter) in enumerate(SPOTS.items()):
        k = 0.5 - 0.5 * math.cos(2 * math.pi * (t - 0.06 * i))
        px[x, y] = rgba(mix(LETTERS[letter], SHEEN[1], 0.4 * k))
    return img


def _outline(img: Image.Image) -> Image.Image:
    """1 px outline around the silhouette: deep indigo beside the body, a softer dusky
    violet where it only borders translucent fins."""
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
                a = min(FIN_OUTLINE[1], max(src[nx, ny][3] for nx, ny in near) + 20)
                dst[x, y] = (fr, fg, fb, a)
    return out


def _skip(x: int, y: int) -> bool:
    return HEAD.get((x, y), "") in ("o", "W", "K") or (x, y) in SPOTS


def _glint(img: Image.Image, t: float, strength: float = 0.78, width: float = 3.6):
    """Frames 0-4: a soft moonlight glint slides along the back from snout to tail."""
    step = t * FRAMES
    if step > 4.01:
        return
    centre = 1.0 + (SL + 1.0) * step / 4.0
    px = img.load()
    c = rgba(GLINT)
    for y in range(SIZE):
        for x in range(SIZE):
            kb = int(KB[y, x])
            if kb < 0 or kb > 3 or _skip(x, y):
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / width) * strength * (1.0 - kb / 6.0)
            if k <= 0:
                continue
            r, g, b, a = px[x, y]
            px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _sparkle_spot(x: int, y: int) -> bool:
    """A diamond lattice of scales that catch the light as the sheen passes."""
    return y % 2 == 1 and (x + y // 2) % 2 == 0


def _sheen(img: Image.Image, t: float):
    """Frames 6-10: a blue-tinted sheen band of glittering scales rolls head to tail."""
    step = t * FRAMES
    if step < 5.99 or step > 10.01:
        return
    centre = 7.0 + 15.5 * (step - 6) / 4.0
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if not MASK[y, x] or (x, y) in HEAD:
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.2)
            if k <= 0:
                continue
            spark = _sparkle_spot(x, y) and int(KB[y, x]) >= 1 and int(KV[y, x]) >= 1
            strength = (0.92 if spark else 0.52) * min(1.0, k * 1.3)
            px[x, y] = rgba(mix(px[x, y], SPARK if spark else SHIMMER, strength))


# Twinkles in the open water around the fish: (x, y, phase, reach).
TWINKLES = [(21, 4, 0.0, 2), (5, 24, 0.36, 2), (28, 9, 0.68, 2)]


def _twinkle(t: float, phase: float) -> float:
    """A brief pop once per loop: 0 most of the time, rising to 1 and back over ~40%."""
    d = ((t - phase + 0.5) % 1.0) - 0.5
    return max(0.0, math.cos(math.pi * d / 0.4)) ** 1.5 if abs(d) < 0.2 else 0.0


def _frame(t: float) -> Image.Image:
    """One 32x32 frame at loop phase t (0..1)."""
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    # Medial fins sit behind the body; the near pectoral and pelvic fins lie over it.
    _paint_fin(img, _tail(sway), FIN[2], 212, cov_min=0.4, lit=FIN[3], edge=TAIL_EDGE,
               fade=(24.0, 29.4, 120))
    _paint_fin(img, _dorsal(ripple), FIN[2], 205, lit=FIN[4])
    _paint_fin(img, _anal(ripple), FIN[2], 200)
    body = _body(t)
    _glint(body, t)
    _sheen(body, t)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), FIN[3], 205)
    _paint_fin(front, _pelvic(flap), FIN[3], 205)
    img.alpha_composite(front)
    img = _outline(img)
    for x, y, phase, reach in TWINKLES:
        k = _twinkle(t, phase)
        sparkle(img, x, y, k, colour=TWINKLE, reach=reach)
        sparkle(img, x, y, k, colour=TWINKLE_CORE, reach=0)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
