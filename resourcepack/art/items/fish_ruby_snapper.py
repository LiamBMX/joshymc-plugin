"""Ruby Snapper: a rare deep-water fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection's shared pose (side view, head up-left, tail
down-right), keeping the old sprite's all-ruby scheme. A deep-bodied snapper (Etelis): a
pointed snout with the lower jaw jutting past the upper, a big deep-water eye with a golden
iris, the curved gill cover, a plum-crimson back under a coral rim light, bright ruby flanks
carrying diagonal rows of gem-cut scale facets, a countershaded rose belly, the long
pectoral fin lying over the flank, a spiny then soft dorsal fin with a notch between, and
the deeply forked tail with long pointed lobes (fins translucent with painted rays).

Animation (RARE, 12 frames x 3 ticks): the tail wags and the pectoral and pelvic fins sway
a pixel; frames 0-5 a soft glint slides along the back, frames 6-11 a cool blue-tinted sheen
rolls across the ruby scales and the facets flash as it passes, while three twinkles flare
around the fish a third of a loop apart.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_ruby_snapper"
NAME = "Ruby Snapper"
KIND = "item"
MODEL_KEY = "fish/ruby_snapper"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palettes (darkest -> lightest): ruby reds leaning plum in shadow, coral in light ------
OUTLINE = "#3d0a24"
FIN_OUTLINE = ("#6e1636", 232)
RED = ["#4a0726", "#6e0b2c", "#94102e", "#b8182f", "#d82a36", "#f04a45", "#ff7a66"]
RIM = ["#ff8e7c", "#f5645a"]
ROSE = ["#c93a5a", "#e0647e", "#f08c9c", "#ffbcc0", "#ffe6e2"]
FIN = ["#5e0c2e", "#86143a", "#b8203f", "#dc3a52", "#f26c76", "#ffa9a6"]
EYE = ["#1c0614", "#ffffff"]
IRIS = ["#b0542e", "#e89a3c", "#ffd66e"]
GLINT = "#fff1e4"
SHEEN = "#b4dcff"      # the cool, blue-tinted sheen that rolls across the ruby scales
SPARK = "#f3f7ff"

# ---- the art ---------------------------------------------------------------------------
# Body letters -> colour; zone letters decide what the animation touches.
BODY = {
    "a": RED[0], "b": RED[1], "c": RED[2], "d": RED[3], "e": RED[4], "f": RED[5], "g": RED[6],
    "R": RIM[0], "r": RIM[1],
    "0": ROSE[0], "1": ROSE[1], "2": ROSE[2], "3": ROSE[3], "4": ROSE[4],
    "o": OUTLINE, "K": EYE[0], "W": EYE[1], "x": IRIS[0], "y": IRIS[1], "z": IRIS[2],
}
FACET = {"f", "g"}                      # the gem-cut scale highlights
FIXED = {"o", "K", "W", "x", "y", "z"}  # eye and mouth keep their colours
# Fin letters -> (colour, alpha): membrane, ray, lit leading edge, darker base.
FINS = {"m": (FIN[4], 200), "F": (FIN[2], 228), "l": (FIN[5], 212), "B": (FIN[3], 220)}

# Rows as (y, first x, letters); '.' leaves a pixel empty.
BODY_ROWS = [
    (8, 4, "RRRRr"),
    (9, 3, "RfzWKyrdc"),
    (10, 2, "3ooyKKxfbecd"),
    (11, 3, "32oyxfbefecbd"),
    (12, 3, "1320ebgeeedcbd"),
    (13, 4, "13210eegeedcbd"),
    (14, 5, "130efeeefedcb"),
    (15, 6, "1320efeeefdcd"),
    (16, 7, "12320efeeefbd"),
    (17, 9, "12320efeedbd"),
    (18, 11, "112220eedcd"),
    (19, 15, "110edcb"),
]
# Spiny dorsal (three spines), the notch, the soft dorsal; the anal fin under the belly.
FIN_ROWS = [
    (6, 12, "F"),
    (7, 10, "lFmF"),
    (8, 9, "BmFmFm"),
    (9, 12, "BmFm"),
    (10, 14, "Bm"),
    (11, 16, "B"),
    (11, 17, "l"),
    (12, 17, "BF"),
    (13, 18, "Bm"),
    (14, 18, "BF"),
    (15, 19, "Bm"),
    (16, 20, "B"),
    (19, 12, "BBB"),
    (20, 13, "mFBBBB"),
    (21, 14, "lFmm"),
]
# Pectoral fin poses (x, y, part), lying over the lower flank behind the gill cover:
# "P" the dark leading ray, "p" the pale translucent membrane (blended over the body).
PECT = {
    "spread": [(10, 13, "P"), (11, 13, "P"), (10, 14, "p"), (11, 14, "p"), (12, 14, "P"), (13, 14, "P"),
               (11, 15, "p"), (12, 15, "p"), (13, 15, "p"), (14, 15, "P"), (13, 16, "p"), (14, 16, "p"),
               (15, 16, "P")],
    "folded": [(10, 13, "P"), (11, 13, "P"), (10, 14, "p"), (11, 14, "p"), (12, 14, "P"), (13, 14, "P"),
               (12, 15, "p"), (13, 15, "p"), (14, 15, "P"), (15, 15, "P"), (14, 16, "p")],
}
PECT_TINT = "#ff9c9c"
# Pelvic fin poses under the throat: hanging, and swept back a pixel.
PELVIC = {"rest": [(8, 17, "B"), (9, 18, "F"), (10, 18, "B"), (9, 19, "m"), (10, 19, "m"), (10, 20, "l")],
          "swept": [(8, 17, "B"), (9, 18, "F"), (10, 18, "B"), (10, 19, "m"), (11, 19, "m"), (11, 20, "l")]}


def _rows(rows) -> dict:
    out = {}
    for y, x0, letters in rows:
        for i, ch in enumerate(letters):
            if ch != ".":
                out[(x0 + i, y)] = ch
    return out


ART = _rows(BODY_ROWS)
FIN_ART = _rows(FIN_ROWS)
MASK = np.zeros((SIZE, SIZE), bool)
for (_x, _y) in ART:
    MASK[_y, _x] = True

# ---- local frame for the tail and the travelling glints -----------------------------------
# u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SNOUT = (2.4, 8.6)
SL = 20.5
SS = 4
TAIL_REST = 4.0
TAIL_PIVOT = (20.1, 0.0)

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


def _tail(sway: float):
    """Deeply forked tail with long, pointed lobes (the Etelis signature)."""
    pts = [(19.7, 1.2), (21.5, 2.6), (24.0, 4.4), (28.0, 6.5), (26.7, 4.4), (25.1, 2.2), (23.9, 0.8),
           (23.3, 0.0), (23.9, -0.8), (25.1, -2.2), (26.6, -4.3), (27.6, -6.2), (24.0, -4.4), (21.5, -2.6),
           (19.7, -1.2)]
    rays = [((20.8, 0.8), (27.2, 5.6), FIN[1]), ((20.8, -0.8), (26.9, -5.4), FIN[1]),
            ((21.0, 0.0), (22.8, 0.0), FIN[1])]
    a = TAIL_REST + 8.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _paint_tail(img, sway: float):
    poly, rays = _tail(sway)
    cov = _poly(poly)
    inside = (cov >= 0.4) & ~MASK
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if not inside[y, x]:
                continue
            u, v = UC[y, x], VC[y, x]
            colour, a = FINS["m"]
            if any(0 <= y + dy < SIZE and 0 <= x + dx < SIZE and MASK[y + dy, x + dx]
                   for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                colour, a = FINS["B"]
            for ra, rb, rc in rays:
                if cov[y, x] >= 0.9 and _near(u, v, ra, rb, 0.45):
                    colour, a = FINS["F"]
                    break
            if (y > 0 and not inside[y - 1, x] and not MASK[y - 1, x]) or \
                    (x > 0 and not inside[y, x - 1] and not MASK[y, x - 1]):
                colour, a = FINS["l"]
            c = rgba(colour)
            px[x, y] = (c[0], c[1], c[2], a)


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


def _blend(px, x, y, colour, k):
    c = rgba(colour)
    r, g, b, a = px[x, y]
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _put_fin(px, x, y, letter):
    colour, a = FINS[letter]
    c = rgba(colour)
    px[x, y] = (c[0], c[1], c[2], a)


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


# Twinkles around the fish: (x, y, reach, phase). Each flares once per loop, a third apart.
TWINKLES = [(6, 4, 2, 0.0), (24, 9, 2, 0.36), (10, 25, 1, 0.68)]


def _effects(img: Image.Image, t: float) -> None:
    """Frames 0-5: a soft glint slides along the back, snout to tail. Frames 6-11: a cool,
    blue-tinted sheen rolls across the ruby scales and the facets flash as it passes."""
    px = img.load()
    step = round(t * FRAMES) % FRAMES
    for (x, y), ch in ART.items():
        if ch in FIXED:
            continue
        if step <= 5:
            kb = int(KB[y, x])
            if kb > 4:
                continue
            centre = -1.0 + (SL + 3.0) * step / 5.0
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.2) * 0.8 * (1.0 - kb / 6.0)
            if k > 0:
                _blend(px, x, y, GLINT, k)
        else:
            centre = 1.0 + (SL + 1.0) * (step - 6) / 5.0
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.8)
            if k <= 0:
                continue
            if ch in FACET:
                _blend(px, x, y, SPARK, min(1.0, 1.25 * k) * 0.9)
            else:
                _blend(px, x, y, SHEEN, min(1.0, 1.3 * k) * 0.55)


def _sparkles(img: Image.Image, t: float) -> None:
    for x, y, reach, phase in TWINKLES:
        w = math.sin(2 * math.pi * (t + phase))
        sparkle(img, x, y, max(0.0, w) ** 1.4, colour=SPARK, reach=reach)


def _frame(t: float) -> Image.Image:
    sway = math.sin(2 * math.pi * t)
    flap = math.sin(2 * math.pi * (t + 0.3))
    img = canvas(SIZE)
    _paint_tail(img, sway)
    px = img.load()
    for (x, y), ch in FIN_ART.items():
        _put_fin(px, x, y, ch)
    for x, y, ch in PELVIC["swept" if math.sin(2 * math.pi * t + 2.0) > 0 else "rest"]:
        _put_fin(px, x, y, ch)
    body = canvas(SIZE)
    bp = body.load()
    for (x, y), ch in ART.items():
        bp[x, y] = rgba(BODY[ch])
    _effects(body, t)
    for x, y, part in PECT["folded" if flap > 0.35 else "spread"]:
        if bp[x, y][3]:
            bp[x, y] = rgba(mix(bp[x, y], PECT_TINT, 0.45) if part == "p" else mix(bp[x, y], FIN[0], 0.7))
    img.alpha_composite(body)
    img = _outline(img)
    _sparkles(img, t)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
