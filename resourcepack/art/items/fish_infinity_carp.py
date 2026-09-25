"""Infinity Carp: the mythical carp of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's electric violet / orchid-magenta scheme. A deep, hump-backed
carp: a lilac-lit violet back netted with big scales, a smooth head with a bevelled gill
cover, a small down-turned mouth with its barbel, a radiant golden-ringed eye, the long
carp dorsal fin running from the hump almost to the tail, orchid fins with painted rays
and a broad forked tail, over a countershaded pale orchid belly. Its flank is a window
into deep space: an indigo nebula full of stars, with a golden infinity sign drawn across it.

Animation (MYTHICAL, 20 frames x 2 ticks): the tail beats twice per loop with its tips
leading and the paired fins sway; a comet runs endlessly round the infinity sign on the
flank, leaving a fading trail, while the stars inside the body twinkle. An iridescent
violet-pink-cyan sheen rolls across the scales, a glint slides along the back, the eye
flares, a golden rim glow breathes just outside the outline and two sparkles orbit the fish.
"""
from __future__ import annotations

import math
import random

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, shade, sparkle, sprite, wave

ID = "fish_infinity_carp"
NAME = "Infinity Carp"
KIND = "item"
MODEL_KEY = "fish/infinity_carp"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 20
FRAMETIME = 2

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean indigo, lights lean pink --
VIOLET = "#9a1ff0"                      # the old sprite's body violet
OUTLINE = "#2a0a5c"
FIN_OUTLINE = ("#4a1a8a", 232)
BACK = [shade(VIOLET, -0.55, 0.2), shade(VIOLET, -0.36, 0.2), shade(VIOLET, -0.16, 0.15), VIOLET,
        mix(shade(VIOLET, 0.3, 0.1), "#f07cff", 0.3), mix(shade(VIOLET, 0.55, 0.1), "#ffc4ff", 0.35)]
BELLY = ["#a04ee0", "#bd72ee", "#d79af6", "#ecc4fb", "#fae6ff"]
SPACE = ["#0a0424", "#120736", "#1b0b4c", "#2a1170", "#40199a"]   # the nebula inside the flank
NEB_PINK = "#6a1a8e"
NEB_CYAN = "#1a4a9c"
FIN = ["#6a1cb8", "#8a30d8", "#a84ae8", "#c46ef2", "#dc98f8", "#f0c8ff"]
FIN_TIP = "#9fe8ff"                     # the fins' edges catch a cool starlight
GOLD = ["#8a5a1a", "#d49a2a", "#ffd35a", "#fff0b0"]
RIM = "#ffb020"
EYE = ["#10062a", "#ffffff"]
STAR = ["#fff8e0", "#d8f4ff", "#ffd8f8"]
GLINT = "#fff0ff"
COMET = "#ffffff"
LINE = "#ffd46a"                        # the infinity sign's starlight line
TRAIL = "#fff4c8"
COMET_TRAIL = 5

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 22.4                      # snout to tail base
SNOUT = (3.3, 9.3)             # snout tip in the frame
SS = 4                         # supersampling per pixel side


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


# Half-depths above (TOP) and below (BOT) the axis: a steep forehead rising to the carp's
# hump, a deep body, then a thick peduncle.
TOP = _smooth([(0.0, 0.8), (1.0, 1.6), (2.1, 2.5), (3.7, 3.5), (5.5, 4.4), (7.6, 5.05), (9.7, 5.3),
               (11.8, 5.1), (13.9, 4.5), (16.1, 3.7), (18.2, 2.8), (20.3, 2.05), (22.4, 1.65)])
BOT = _smooth([(0.0, 0.8), (1.0, 1.3), (2.1, 2.0), (3.7, 2.9), (5.5, 3.6), (7.6, 4.15), (9.7, 4.45),
               (11.8, 4.4), (13.9, 3.95), (16.1, 3.25), (18.2, 2.5), (20.3, 1.9), (22.4, 1.65)])

_ys, _xs = np.mgrid[0:SIZE * SS, 0:SIZE * SS]
_SX, _SY = (_xs + 0.5) / SS - SNOUT[0], (_ys + 0.5) / SS - SNOUT[1]
U = _SX * AX[0] + _SY * AX[1]
V = _SX * UP[0] + _SY * UP[1]
_py, _px = np.mgrid[0:SIZE, 0:SIZE]
UC = (_px + 0.5 - SNOUT[0]) * AX[0] + (_py + 0.5 - SNOUT[1]) * AX[1]
VC = (_px + 0.5 - SNOUT[0]) * UP[0] + (_py + 0.5 - SNOUT[1]) * UP[1]


def _to_xy(u: float, v: float) -> tuple[float, float]:
    return SNOUT[0] + u * AX[0] + v * UP[0], SNOUT[1] + u * AX[1] + v * UP[1]


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


BODY = _cov((U >= 0) & (U <= SL + 0.3) & (V <= TOP(U)) & (V >= -BOT(U))) >= 0.5

# ---- fins: (polygon, rays) in local coordinates; v > 0 is the back ----------------------

TAIL_PIVOT = (23.2, 0.0)
TAIL_REST = 13.0


def _tail(sway: float):
    pts = [(21.6, 1.5), (23.4, 2.6), (25.4, 3.9), (27.6, 5.2), (27.7, 3.9), (26.8, 2.2), (25.8, 0.8), (25.4, 0.0),
           (25.8, -0.8), (26.8, -2.2), (27.7, -3.9), (27.0, -4.6), (25.1, -3.6), (23.4, -2.6), (21.6, -1.5)]
    rays = [((22.6, 1.0), (27.0, 4.3), FIN[1]), ((22.6, -1.0), (27.0, -4.3), FIN[1]),
            ((22.8, 0.3), (24.8, 1.6), FIN[1]), ((22.8, -0.3), (24.8, -1.6), FIN[1])]
    a = TAIL_REST + 9.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _dorsal(ripple: float):
    """The long carp dorsal: a tall serrated leading spine at the hump, then a low soft fin
    running almost to the tail."""
    lead = (8.9 + 0.4 * ripple, TOP(8.9) + 3.0)
    pts = [(7.6, TOP(7.6) - 0.6), lead, (10.6 + 0.4 * ripple, TOP(10.6) + 2.0), (14.2, TOP(14.2) + 1.45),
           (17.4 + 0.3 * ripple, TOP(17.4) + 1.1), (18.9, TOP(18.9) - 0.3)]
    rays = [((7.9, TOP(7.9)), lead, FIN[4]),
            ((11.4, TOP(11.4)), (11.9 + 0.4 * ripple, TOP(11.9) + 1.7), FIN[1]),
            ((15.4, TOP(15.4)), (15.8 + 0.3 * ripple, TOP(15.8) + 1.3), FIN[1])]
    return pts, rays


def _pectoral(flap: float):
    pivot = (5.6, -BOT(5.6) + 0.8)
    pts = [(5.2, -BOT(5.2) + 0.9), (6.4, -BOT(6.4) + 0.7), (8.6, -BOT(8.6) - 1.0), (7.6, -BOT(7.6) - 1.7),
           (5.6, -BOT(5.6) - 0.4)]
    rays = [(pivot, (8.0, -BOT(8.0) - 1.2), FIN[2])]
    a = 11.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _pelvic(flap: float):
    pivot = (10.8, -BOT(10.8))
    pts = [(10.2, -BOT(10.2) + 0.5), (11.6, -BOT(11.6) + 0.4), (13.6, -BOT(13.6) - 1.9),
           (12.2, -BOT(12.2) - 2.1)]
    rays = [((11.0, -BOT(11.0)), (12.8, -BOT(12.8) - 1.8), FIN[2])]
    a = 9.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _anal(ripple: float):
    pts = [(16.2, -BOT(16.2) + 0.5), (16.8, -BOT(16.8) - 2.0 - 0.3 * ripple), (19.7, -BOT(19.7) - 1.0),
           (20.2, -BOT(20.2) + 0.5)]
    rays = [((17.0, -BOT(17.0)), (17.1, -BOT(17.1) - 1.8 - 0.3 * ripple), FIN[4])]
    return pts, rays


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=226, lit=None, tip=None):
    """Paint a translucent fin: membrane plus rays. With lit, fin pixels whose upper or left
    neighbour is open water catch the top-left light; with tip, the outer edge (away from
    the body) takes a cool starlit tint."""
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
            if tip and inside[y, x]:
                edge = any(not (0 <= x + dx < SIZE and 0 <= y + dy < SIZE) or
                           (cov[y + dy, x + dx] < cov_min and not MASK[y + dy, x + dx])
                           for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                if edge:
                    colour = mix(colour, tip, 0.45)
            if lit and inside[y, x] and ((y > 0 and not inside[y - 1, x] and not MASK[y - 1, x])
                                         or (x > 0 and not inside[y, x - 1] and not MASK[y, x - 1])):
                colour = lit
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)


# ---- body -------------------------------------------------------------------------------

BODY_ADD: list = []
BODY_CUT: list = [(4, 8)]       # round off the forehead corner


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
            # measured down from the back and up from the belly; only the last pixel of a
            # row (the tail join) and the first (the snout) count as edges sideways
            kb[y, x] = min(y - cols[x][0], 2 * (xs[-1] - x))
            kv[y, x] = min(cols[x][-1] - y, 2 * (x - xs[0]))
    return kb, kv


KB, KV = _bands(MASK)
GILL_U = 5.4                    # the gill cover's rear edge (head in front of it)


def _zone(x: int, y: int) -> str:
    """head / back / space / belly for a body pixel."""
    kb, kv = int(KB[y, x]), int(KV[y, x])
    u = UC[y, x]
    if u < GILL_U + (0.6 if VC[y, x] < 0 else 0.0):
        return "head"
    if kb <= 1:
        return "back"
    if kv <= 1:
        return "belly"
    return "space"


def _base_colour(x: int, y: int) -> str:
    kb, kv = int(KB[y, x]), int(KV[y, x])
    u, v = UC[y, x], VC[y, x]
    zone = _zone(x, y)
    if zone == "head":
        if kb == 0:
            return BACK[5]
        if kv == 0:
            return BELLY[2]
        if kv == 1 and v < -0.2:
            return BELLY[3]
        if kb == 1:
            return BACK[4]
        return BACK[3] if v > -0.5 else BELLY[1]
    if zone == "back":
        if kb == 0:
            return BACK[5] if u < 11 else BACK[4]
        return BACK[2] if _scale_mark(x, y) else BACK[3]
    if zone == "belly":
        # the lowest pixel turns under into reflected shade; the brightest row sits above it
        if kv == 0:
            return BELLY[1] if u > 8 else BELLY[2]
        return BELLY[2] if _scale_mark(x, y) else BELLY[3]
    # deep space: a violet haze where it meets the back and belly, darkest in the middle
    edge = min(kb - 2, kv - 2)
    if edge <= 0:
        return SPACE[3]
    if edge == 1:
        return SPACE[2]
    return SPACE[1]


def _scale_mark(x: int, y: int) -> bool:
    """A staggered diamond lattice (image space): the edges of the carp's big scales."""
    return y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1


def _nebula(img: Image.Image, t: float) -> None:
    """Soft magenta and cyan clouds drifting slowly through the flank (periodic in t)."""
    px = img.load()
    a = 2 * math.pi * t
    for y in range(SIZE):
        for x in range(SIZE):
            if not MASK[y, x] or _zone(x, y) != "space":
                continue
            u, v = UC[y, x], VC[y, x]
            pink = 0.5 + 0.5 * math.sin(0.55 * u - 0.9 * v + a)
            cyan = 0.5 + 0.5 * math.sin(0.5 * u + 1.1 * v - a + 1.7)
            c = px[x, y]
            col = mix(c, NEB_PINK, 0.5 * pink ** 4)
            col = mix(col, NEB_CYAN, 0.45 * cyan ** 4)
            px[x, y] = rgba(col)


# Head details: gill cover, mouth, barbel, eye (placed after the mask is known).
def _head_details() -> dict:
    out = {}
    # eye: 2x2 at local (2.3, 0.7)
    ex, ey = _to_xy(2.3, 0.75)
    ex, ey = int(ex), int(ey)
    out[(ex, ey)] = ("eye_hi", EYE[1])
    out[(ex + 1, ey)] = ("eye", EYE[0])
    out[(ex, ey + 1)] = ("eye", EYE[0])
    out[(ex + 1, ey + 1)] = ("eye", EYE[0])
    out[(ex + 2, ey + 1)] = ("iris", GOLD[1])
    out[(ex + 1, ey + 2)] = ("iris", GOLD[1])
    # gill cover: a dark curve with a lit edge behind it
    for y in range(SIZE):
        for x in range(SIZE):
            if not MASK[y, x]:
                continue
            u, v = UC[y, x], VC[y, x]
            edge = GILL_U + (0.6 if v < 0 else 0.0) - 0.08 * v * v
            if -3.2 < v < 3.2 and abs(u - edge) < 0.55 and KB[y, x] >= 1 and KV[y, x] >= 1:
                out[(x, y)] = ("gill", BACK[1])
            elif -2.6 < v < 3.0 and abs(u - (edge - 1.0)) < 0.45 and KB[y, x] >= 2 and KV[y, x] >= 1:
                out.setdefault((x, y), ("cheek", BACK[4]))
    # mouth: a small down-turned notch at the snout
    mx, my = _to_xy(0.4, -0.35)
    out[(int(mx), int(my))] = ("mouth", OUTLINE)
    return out


HEAD = _head_details()


def _barbel_pixels() -> list:
    bx, by = _to_xy(1.1, -1.25)
    bx, by = int(bx), int(by)
    return [(bx, by + 1), (bx - 1, by + 2)]


def _body() -> Image.Image:
    img = canvas(SIZE)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                px[x, y] = rgba(_base_colour(x, y))
    return img


# ---- the infinity sign on the flank -------------------------------------------------------
# Hand-drawn 9x5 lemniscate in frame space, listed in path order so a comet can run round it
# one pixel per frame: from the crossing up and round the tail-side loop, back through the
# crossing, then round the head-side loop.
INF_AT = (9, 13)
INF_PATH_LOCAL = [(4, 2), (5, 1), (6, 0), (7, 0), (8, 1), (8, 2), (8, 3), (7, 4), (6, 4), (5, 3),
                  (4, 2), (3, 1), (2, 0), (1, 0), (0, 1), (0, 2), (0, 3), (1, 4), (2, 4), (3, 3)]
INF_PATH = [(INF_AT[0] + x, INF_AT[1] + y) for x, y in INF_PATH_LOCAL]
INF = sorted(set(INF_PATH))

# ---- stars inside the body (seeded, fixed positions, each twinkling on its own phase) --


def _stars():
    rng = random.Random(7)
    near_inf = {(x, y) for x in range(INF_AT[0] - 1, INF_AT[0] + 10) for y in range(INF_AT[1] - 1, INF_AT[1] + 6)}
    cells = [(x, y) for y in range(SIZE) for x in range(SIZE)
             if MASK[y, x] and _zone(x, y) == "space" and (x, y) not in near_inf and (x, y) not in HEAD]
    rng.shuffle(cells)
    chosen = []
    for c in cells:
        if all(abs(c[0] - o[0]) + abs(c[1] - o[1]) >= 3 for o in chosen):
            chosen.append(c)
        if len(chosen) >= 9:
            break
    return [(x, y, rng.random(), rng.choice((1, 2)), STAR[i % 3]) for i, (x, y) in enumerate(chosen)]


STARS = _stars()


def _space_fx(img: Image.Image, t: float) -> None:
    px = img.load()
    # the sign itself: a faint starlight line
    for x, y in INF:
        if MASK[y, x]:
            px[x, y] = rgba(mix(px[x, y], LINE, 0.8))
    # twinkling stars
    for x, y, ph, k, col in STARS:
        a = wave(t * k, ph)
        px[x, y] = rgba(mix(px[x, y], col, 0.25 + 0.75 * a))
    # the comet: a bright head running round the sign, one pixel per frame, with a trail
    # behind it and a soft halo in the dark around its head
    n = len(INF_PATH)
    head = round(t * n) % n
    hx, hy = INF_PATH[head]
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        x, y = hx + dx, hy + dy
        if MASK[y, x] and (x, y) not in INF:
            px[x, y] = rgba(mix(px[x, y], TRAIL, 0.4))
    for i in range(COMET_TRAIL, -1, -1):
        x, y = INF_PATH[(head - i) % n]
        k = 1.0 - i / (COMET_TRAIL + 1)
        col = COMET if i == 0 else mix(TRAIL, LINE, i / COMET_TRAIL)
        px[x, y] = rgba(mix(px[x, y], col, 0.4 + 0.6 * k))


# ---- sheen, glint, rim glow, eye flare, orbiting sparkles ---------------------------------

def _iridescence(img: Image.Image, t: float) -> None:
    """A violet -> pink -> cyan sheen band rolling head to tail over the scales (first half
    of the loop, then resting)."""
    run = 0.5
    if t >= run:
        return
    centre = -2.0 + (SL + 4.0) * (t / run)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if not MASK[y, x] or (x, y) in HEAD and HEAD[(x, y)][0] in ("eye", "eye_hi", "iris", "mouth"):
                continue
            d = UC[y, x] - centre
            k = max(0.0, 1.0 - abs(d) / 3.0)
            if k <= 0:
                continue
            hue = "#ff7ce8" if d < -1.0 else "#9ff4ff" if d > 1.0 else "#e0a0ff"
            px[x, y] = rgba(mix(px[x, y], hue, 0.42 * k))


def _glint(img: Image.Image, t: float) -> None:
    """A soft glint sliding along the back (second half of the loop)."""
    if not 0.55 <= t < 0.9:
        return
    centre = -1.0 + (SL + 2.0) * ((t - 0.55) / 0.35)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            kb = int(KB[y, x])
            if kb < 0 or kb > 2 or (x, y) in HEAD and HEAD[(x, y)][0] in ("eye", "eye_hi", "iris"):
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 2.6) * 0.62 * (1.0 - kb / 3.5)
            if k > 0:
                px[x, y] = rgba(mix(px[x, y], GLINT, k))


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


def _rim_glow(img: Image.Image, t: float) -> None:
    """A breathing golden 1 px glow just outside the outline."""
    src = img.copy().load()
    px = img.load()
    breath = wave(t)
    g = rgba(mix(RIM, GOLD[2], 0.5 * breath))
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            if not any(0 <= x + dx < SIZE and 0 <= y + dy < SIZE and src[x + dx, y + dy][3] >= 200
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                continue
            # brighter where it faces the top-left light
            lit = (y + 1 < SIZE and src[x, y + 1][3]) or (x + 1 < SIZE and src[x + 1, y][3])
            alpha = (40 + 120 * breath) * (1.0 if lit else 0.6)
            px[x, y] = (g[0], g[1], g[2], round(alpha))


def _eye_flare(img: Image.Image, t: float) -> None:
    px = img.load()
    k = wave(t, 0.25)
    for (x, y), (part, col) in HEAD.items():
        if part == "iris":
            px[x, y] = rgba(mix(GOLD[1], GOLD[3], 0.3 + 0.7 * k))
    (ex, ey), = [p for p, (part, _c) in HEAD.items() if part == "eye_hi"]
    # a brief star-shaped flare on the catchlight once per loop
    f = max(0.0, math.sin(2 * math.pi * (t - 0.62))) ** 4
    sparkle(img, ex, ey, f, colour="#fff6c8", reach=2)


def _orbit(img: Image.Image, t: float) -> None:
    """Two sparkles orbiting the fish on a tilted ellipse, twinkling as they go."""
    for i, off in enumerate((0.0, 0.5)):
        a = 2 * math.pi * (t + off)
        u = 12.0 + 13.2 * math.cos(a)
        v = 0.4 + 7.4 * math.sin(a)
        x, y = _to_xy(u, v)
        x = max(1, min(SIZE - 2, int(round(x))))
        y = max(1, min(SIZE - 2, int(round(y))))
        amt = 0.45 + 0.55 * wave(2 * t, 0.25 * i)
        sparkle(img, x, y, amt, colour="#fff2b8" if i == 0 else "#e8d8ff", reach=1)


def _frame(t: float) -> Image.Image:
    sway = math.sin(2 * math.pi * 2 * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * 2 * (t + 0.15))

    img = canvas(SIZE)
    _paint_fin(img, _tail(sway), FIN[2], 214, cov_min=0.4, lit=FIN[4], tip=FIN_TIP)
    _paint_fin(img, _dorsal(ripple), FIN[2], 208, lit=FIN[4], tip=FIN_TIP)
    _paint_fin(img, _anal(ripple), FIN[3], 204, tip=FIN_TIP)
    body = _body()
    _nebula(body, t)
    _space_fx(body, t)
    bpx = body.load()
    for (x, y), (part, col) in HEAD.items():
        bpx[x, y] = rgba(col)
    _iridescence(body, t)
    _glint(body, t)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), FIN[1], 214, tip=FIN_TIP)
    _paint_fin(front, _pelvic(flap), FIN[1], 214, tip=FIN_TIP)
    img.alpha_composite(front)
    px = img.load()
    for x, y in _barbel_pixels():
        if not px[x, y][3]:
            px[x, y] = rgba(BACK[2])
    out = _outline(img)
    _rim_glow(out, t)
    _eye_flare(out, t)
    _orbit(out, t)
    return out


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
