"""Sardine: a common ocean schooling fish for the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's slate / lavender / pale-aqua / gold scheme. A slim, round-bellied
clupeid: a blue-green back under a rim light, a bright aqua "horizon" line, the pilchard's
row of dark spots along the upper flank, lavender iridescence over a white countershaded
belly, a big eye, a silvery gill cover with a golden blush and a curved hind edge, a small
terminal mouth, one mid-body dorsal fin, low pectoral, pelvic and anal fins and a deeply
forked tail (fins translucent teal-grey with painted rays and a softer outline).
Animated (COMMON, 8 frames x 3 ticks): the tail and fins sway about a pixel on offset
sine phases and a soft glint slides along the back from snout to tail, then rests.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, rgba, save_animation, sprite

ID = "fish_sardine"
NAME = "Sardine"
KIND = "item"
MODEL_KEY = "fish/sardine"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 8
FRAMETIME = 3

# ---- palettes (darkest -> lightest), from the old sprite's slate / lavender / aqua / gold
OUTLINE = "#172a3a"
FIN_OUTLINE = ("#34566a", 235)     # softer edge where the outline only touches a fin
BACK = ["#16323f", "#1f4b58", "#2a6571", "#3a8089", "#58a0a3", "#88c3bf"]   # blue-green back
AQUA = ["#6fb0b6", "#9fd4d0", "#c8efe8"]
SILVER = ["#636887", "#7e84a6", "#9ca0c4", "#b5b8da", "#cfd2ea", "#e7e9f6", "#fafbff"]
SHEEN = ["#9a92c8", "#b3acdc"]
SPOT = ["#1d2d4e", "#3a4c74"]      # the pilchard's row of dark flank spots
GOLD = ["#7a5a2c", "#b88b3c", "#deb450", "#f6d98a"]
FIN = ["#3e5f6c", "#4f717d", "#668a93", "#85a6ab", "#a8c4c5", "#cfe2e0"]
EYE = ["#0c1424", "#ffffff"]
GLINT = "#f0fffb"

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 23.5                      # snout to tail base
SNOUT = (2.6, 7.6)             # snout tip in the frame
SS = 4                         # supersampling per pixel side
TAIL_REST = 9.0                # the tail rests bent a little toward the back (degrees)


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


# Half-depths of the body above (TOP) and below (BOT) the axis.
TOP = _smooth([(0.0, 0.55), (0.85, 1.1), (1.7, 1.62), (2.8, 2.12), (4.0, 2.52), (5.5, 2.95), (7.3, 3.28),
               (9.0, 3.5), (11.2, 3.62), (13.4, 3.5), (15.7, 3.1), (17.9, 2.42), (20.1, 1.78), (22.3, 1.28),
               (23.5, 1.2)])
BOT = _smooth([(0.0, 0.62), (0.85, 1.18), (1.7, 1.78), (2.8, 2.35), (4.0, 2.82), (5.5, 3.32), (7.3, 3.78),
               (9.0, 4.08), (11.2, 4.25), (13.4, 4.12), (15.7, 3.6), (17.9, 2.8), (20.1, 2.02), (22.3, 1.36),
               (23.5, 1.2)])

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

TAIL_PIVOT = (23.0, 0.0)


def _tail(sway: float):
    pts = [(22.4, 1.2), (24.1, 2.1), (26.1, 3.4), (28.5, 4.65), (28.2, 3.3), (27.0, 1.8), (26.1, 0.7), (25.5, 0.0),
           (26.1, -0.7), (27.0, -1.8), (28.2, -3.3), (28.5, -4.65), (26.1, -3.4), (24.1, -2.1), (22.4, -1.2)]
    rays = [((23.4, 0.7), (28.0, 3.9), FIN[1]), ((23.4, -0.7), (28.0, -3.9), FIN[1]),
            ((23.6, 0.0), (25.2, 0.0), FIN[1])]
    a = TAIL_REST + 8.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _dorsal(ripple: float):
    tip = (11.1 + 0.5 * ripple, TOP(11.1) + 4.1)
    pts = [(9.2, TOP(9.2) - 0.6), tip, (12.5 + 0.6 * ripple, TOP(12.5) + 3.2), (14.5, TOP(14.5) + 0.6),
           (14.5, TOP(14.5) - 0.6)]
    rays = [((9.6, TOP(9.6)), tip, FIN[4]),                                   # lit leading ray
            ((11.8, TOP(11.8)), (12.7 + 0.6 * ripple, TOP(12.7) + 2.8), FIN[1])]
    return pts, rays


def _pectoral(flap: float):
    pivot = (6.9, -BOT(6.9) + 0.6)
    pts = [(6.4, -BOT(6.4) + 1.1), (7.8, -BOT(7.8) + 0.7), (10.9, -BOT(10.9) - 1.6), (10.0, -BOT(10.0) - 2.4),
           (7.0, -BOT(7.0) - 0.6)]
    rays = [(pivot, (10.2, -BOT(10.2) - 1.9), FIN[2])]
    a = 10.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _pelvic(flap: float):
    pivot = (12.7, -BOT(12.7))
    pts = [(12.1, -BOT(12.1) + 0.5), (13.4, -BOT(13.4) + 0.4), (15.3, -BOT(15.3) - 1.6),
           (14.1, -BOT(14.1) - 1.8)]
    a = 9.0 * flap
    return _rot(pts, pivot, a), []


def _anal(ripple: float):
    pts = [(16.5, -BOT(16.5) + 0.5), (17.2, -BOT(17.2) - 1.7 - 0.3 * ripple), (20.8, -BOT(20.8) - 1.15),
           (21.4, -BOT(21.4) + 0.5)]
    rays = [((17.4, -BOT(17.4)), (17.4, -BOT(17.4) - 1.6 - 0.3 * ripple), FIN[1])]
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
# The body silhouette is the procedural lens above with a few hand edits; its colours come
# from discrete bands counted in from the dorsal and ventral contours (clean staircases that
# follow the outline), then hand-placed head details on top.

BODY_ADD = []
BODY_CUT = []


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

# Palette letters for the hand-placed details (and the dump tools).
LETTERS = {"o": OUTLINE, "K": EYE[0], "W": EYE[1]}
LETTERS.update({k: c for k, c in zip("abcdef", BACK)})
LETTERS.update({k: c for k, c in zip("qrs", AQUA)})
LETTERS.update({k: c for k, c in zip("0123456", SILVER)})
LETTERS.update({k: c for k, c in zip("ghij", GOLD)})
LETTERS.update({k: c for k, c in zip("uv", SHEEN)})
LETTERS.update({k: c for k, c in zip("xy", SPOT)})

# Head details: (x, y) -> letter. Eye with a catchlight and a golden iris / gill-cover
# glint (the old sprite's gold), the gill slit, a notch for the mouth and the jutting jaw.
HEAD = {
    (3, 7): "e", (4, 7): "f", (5, 7): "f",
    (2, 8): "4", (3, 8): "o", (4, 8): "c",           # small terminal mouth, lower jaw a touch ahead
    (3, 9): "5", (4, 9): "4", (5, 9): "W", (6, 9): "K", (7, 9): "j", (8, 9): "d",
    (3, 10): "4", (4, 10): "3", (5, 10): "K", (6, 10): "K", (7, 10): "i", (8, 10): "1",
    # silvery gill cover with a golden blush, its curved hind edge behind
    (4, 11): "3", (5, 11): "5", (6, 11): "4", (7, 11): "i", (8, 11): "j", (9, 11): "1",
    (5, 12): "3", (6, 12): "h", (7, 12): "h", (8, 12): "i", (9, 12): "1",
    (6, 13): "3", (7, 13): "3", (8, 13): "1",
    (8, 14): "3",
}
# The dark spot row along the upper flank, on the lavender row just under the aqua line.
SPOTS = [(11, 13), (13, 14), (15, 15), (17, 16)]


def _band_colour(x: int, y: int) -> str:
    kb, kv = int(KB[y, x]), int(KV[y, x])
    h = kb + kv + 1
    nb = 1 if h <= 4 else 2
    if kb == 0:
        return BACK[4] if x < 12 else BACK[3]      # rim light on the back
    if kb <= nb:
        return BACK[1] if kb == 1 else BACK[2]      # dark steel-blue back
    if kb == nb + 1:
        return AQUA[2]                              # bright horizon line
    if kv == 0:
        return SILVER[3]                            # belly edge
    if kb == nb + 2 and kv >= 2:                    # lavender iridescence with scale marks
        return SHEEN[0] if (x + y) % 3 == 0 else SHEEN[1]
    if kb == nb + 3 and kv >= 2 and x >= 8 and (x + y) % 3 == 2:
        return SHEEN[1]                             # second, staggered scale row
    if kv == 1:
        return SILVER[6]
    if kv == 2:
        return SILVER[5]
    return SILVER[4]


def _body() -> Image.Image:
    img = canvas(SIZE)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                px[x, y] = rgba(_band_colour(x, y))
    for x, y in SPOTS:
        px[x, y] = rgba(SPOT[0])
        if _band_colour(x + 1, y) in SHEEN:           # a softer trailing pixel
            px[x + 1, y] = rgba(SPOT[1])
        elif _band_colour(x, y + 1) in SHEEN + SILVER:
            px[x, y + 1] = rgba(SPOT[1])
    for (x, y), letter in HEAD.items():
        px[x, y] = rgba(LETTERS[letter])
    return img


def _outline(img: Image.Image) -> Image.Image:
    """1 px outline around the silhouette: dark indigo beside the body, a softer dusky
    indigo where it only borders translucent fins."""
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


def _glint(img: Image.Image, t: float, strength: float = 0.6, width: float = 3.0, pause: float = 0.3):
    """A soft glint sliding along the back from head to tail, then resting (a shine()
    band that follows the fish's own axis instead of the frame's diagonal)."""
    run = 1.0 - pause
    if t >= run:
        return
    centre = -1.5 + (SL + 3.0) * (t / run)
    px = img.load()
    c = rgba(GLINT)
    for y in range(SIZE):
        for x in range(SIZE):
            kb = int(KB[y, x])
            if kb < 0 or kb > 3 or HEAD.get((x, y), "") in ("o", "W", "K"):
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / width) * strength * (1.0 - kb / 4.5)
            if k <= 0:
                continue
            r, g, b, a = px[x, y]
            px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _frame(t: float) -> Image.Image:
    """One 32x32 frame at loop phase t (0..1): fins and tail on sine sways, glint on top."""
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    # Medial fins sit behind the body; the near pectoral and pelvic fins lie over it.
    _paint_fin(img, _tail(sway), FIN[2], 212, cov_min=0.4, lit=FIN[3])
    _paint_fin(img, _dorsal(ripple), FIN[2], 205, lit=FIN[4])
    _paint_fin(img, _anal(ripple), FIN[2], 200)
    body = _body()
    _glint(body, t)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), FIN[3], 205)
    _paint_fin(front, _pelvic(flap), FIN[3], 205)
    img.alpha_composite(front)
    return _outline(img)


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
