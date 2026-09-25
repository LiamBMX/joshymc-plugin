"""River Trout: a common stream fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's grey-olive / sage / rosy-lavender / golden-fin scheme. A sturdy
brown-trout body: a grey-olive back peppered with dark spots under a lit rim, sage flanks
with a soft rosy lateral wash carrying a few rust-red spots in pale halos, a cream
countershaded belly, a blunt head with a long jaw running back under the eye, a dark
gill-cover edge, a squarish dorsal fin, the little adipose fin, pectoral, pelvic and anal
fins with golden leading edges and a broad, shallow-forked tail (fins translucent with
painted rays and a softer outline). Animated (COMMON, 8 frames x 3 ticks): the tail and
fins sway about a pixel on offset sine phases and a soft glint slides along the back from
snout to tail, then rests.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sprite

ID = "fish_river_trout"
NAME = "River Trout"
KIND = "item"
MODEL_KEY = "fish/river_trout"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 8
FRAMETIME = 3

# ---- palettes (darkest -> lightest), from the old sprite's grey-olive / sage / rose / gold
OUTLINE = "#222d27"
FIN_OUTLINE = ("#3b4634", 232)     # softer edge where the outline only touches a fin
BACK = ["#2c382f", "#3a4a3c", "#4b5c48", "#617359", "#7b8e6f", "#9aac8b"]
SAGE = ["#72896a", "#86a079", "#9cb68b", "#b1c89f"]
ROSE = ["#94808e", "#ae8f9c", "#c4a5ab", "#d6bdba"]
BELLY = ["#9ea68f", "#b5bca1", "#cbd0b5", "#dde0c7", "#ecedd9"]
SPOT = ["#2b3527", "#3b4834"]                # dark olive-black spots
RED = ["#8f3b2c", "#bb5a3e", "#e6d8c2"]      # rust spot, its lit pixel, the pale halo
FIN = ["#525c3c", "#6c7646", "#89904f", "#aba253", "#d6b448", "#f3d05c"]
EYE = ["#10160f", "#ffffff"]
IRIS = ["#8a6a2a", "#c89a3c"]
GLINT = "#f6fde6"

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 22.4                      # snout to tail base
SNOUT = (3.0, 7.4)             # snout tip in the frame
SS = 4                         # supersampling per pixel side
TAIL_REST = 17.0                # the tail rests bent a little toward the back (degrees)


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


# Half-depths of the body above (TOP) and below (BOT) the axis: a blunt, rounded head, a
# deep middle and a thick caudal peduncle (trout, not herring).
TOP = _smooth([(0.0, 0.9), (0.8, 1.55), (1.8, 2.15), (3.0, 2.65), (4.4, 3.05), (6.0, 3.4), (8.0, 3.65),
               (10.0, 3.75), (12.0, 3.65), (14.0, 3.3), (16.0, 2.8), (18.0, 2.25), (20.0, 1.85), (21.4, 1.75),
               (22.4, 1.85)])
BOT = _smooth([(0.0, 0.9), (0.8, 1.5), (1.8, 2.15), (3.0, 2.75), (4.4, 3.25), (6.0, 3.7), (8.0, 4.05),
               (10.0, 4.15), (12.0, 4.0), (14.0, 3.6), (16.0, 3.0), (18.0, 2.4), (20.0, 1.95), (21.4, 1.8),
               (22.4, 1.9)])

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


def _pix(u: float, v: float) -> tuple[int, int]:
    """The frame pixel holding local point (u, v)."""
    return (int(math.floor(SNOUT[0] + u * AX[0] + v * UP[0])),
            int(math.floor(SNOUT[1] + u * AX[1] + v * UP[1])))




# ---- fins: (polygon, rays) in local coordinates; v > 0 is the back ----------------------

TAIL_PIVOT = (21.8, 0.0)


def _tail(sway: float):
    """Broad, shallow-forked trout tail with rounded lobes."""
    pts = [(21.2, 1.7), (23.0, 2.4), (24.7, 3.2), (26.1, 3.95), (26.9, 3.85), (26.6, 2.6), (25.7, 1.2),
           (25.0, 0.0), (25.7, -1.2), (26.6, -2.6), (26.9, -3.85), (26.1, -3.95), (24.7, -3.2),
           (23.0, -2.4), (21.2, -1.7)]
    rays = [((22.4, 1.0), (26.3, 3.3), FIN[1]), ((22.4, -1.0), (26.3, -3.3), FIN[1]),
            ((22.6, 0.0), (24.4, 0.0), FIN[1])]
    a = TAIL_REST + 8.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _dorsal(ripple: float):
    """Squarish dorsal fin over the middle of the back, leading ray lit gold."""
    tip = (10.2 + 0.4 * ripple, TOP(10.2) + 4.0)
    pts = [(8.4, TOP(8.4) - 0.6), tip, (11.4 + 0.5 * ripple, TOP(11.4) + 3.8),
           (13.4 + 0.6 * ripple, TOP(13.4) + 1.9), (14.4, TOP(14.4) - 0.6)]
    rays = [((8.8, TOP(8.8)), tip, FIN[4]),
            ((11.0, TOP(11.0)), (11.6 + 0.5 * ripple, TOP(11.6) + 3.2), FIN[1]),
            ((12.8, TOP(12.8)), (13.3 + 0.6 * ripple, TOP(13.3) + 1.8), FIN[1])]
    return pts, rays


def _adipose(ripple: float):
    """The trout's small fleshy adipose fin between dorsal and tail."""
    pts = [(16.9, TOP(16.9) - 0.5), (17.9 + 0.2 * ripple, TOP(17.9) + 1.7), (19.0 + 0.2 * ripple, TOP(19.0) + 1.5),
           (19.6, TOP(19.6) - 0.5)]
    return pts, []


def _pectoral(flap: float):
    pivot = (6.4, -BOT(6.4) + 0.7)
    pts = [(5.9, -BOT(5.9) + 1.2), (7.3, -BOT(7.3) + 0.8), (10.0, -BOT(10.0) - 1.3), (9.3, -BOT(9.3) - 2.3),
           (6.6, -BOT(6.6) - 0.6)]
    rays = [((6.2, -BOT(6.2) + 0.4), (9.5, -BOT(9.5) - 1.9), FIN[4])]
    a = 10.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _pelvic(flap: float):
    pivot = (12.2, -BOT(12.2))
    pts = [(11.6, -BOT(11.6) + 0.5), (13.0, -BOT(13.0) + 0.4), (14.8, -BOT(14.8) - 1.7),
           (13.4, -BOT(13.4) - 2.0)]
    rays = [((11.9, -BOT(11.9)), (13.5, -BOT(13.5) - 1.8), FIN[4])]
    a = 9.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _anal(ripple: float):
    pts = [(15.6, -BOT(15.6) + 0.5), (16.1, -BOT(16.1) - 2.0 - 0.3 * ripple),
           (17.3, -BOT(17.3) - 1.9 - 0.3 * ripple), (19.0, -BOT(19.0) - 0.8), (19.4, -BOT(19.4) + 0.5)]
    rays = [((15.9, -BOT(15.9)), (16.3, -BOT(16.3) - 1.8 - 0.3 * ripple), FIN[4]),
            ((17.6, -BOT(17.6)), (17.8, -BOT(17.8) - 1.4 - 0.3 * ripple), FIN[1])]
    return pts, rays


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=228, lit=None, dots=()):
    """Paint a translucent fin: membrane plus rays (segments with their own colour). With
    lit, fin pixels whose upper or left neighbour is open water catch the top-left light.
    dots are ((u, v), colour) marks (the trout's spotted fins) painted over the membrane."""
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
    for (u, v), colour in dots:
        x, y = _pix(u, v)
        if 0 <= x < SIZE and 0 <= y < SIZE and cov[y, x] >= 0.5 and not MASK[y, x]:
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, 236)


# ---- body -------------------------------------------------------------------------------
# The body is hand-painted column by column (x: first row, letters top -> bottom) so every
# band, spot and head detail is a deliberate pixel. Bands per column: lit rim, grey-olive
# back, darker back, sage flank, the rosy lateral wash, pale lower flank, cream belly and
# its shaded edge; the head carries the crown, eye, long jaw line and the gill cover.

COLS = {
    3: (7, "aSw"),
    4: (6, "AaSTmw"),
    5: (6, "ABBSmwV"),
    6: (6, "aBeETmwV"),
    7: (7, "aEEimwV"),
    8: (7, "aiSTTvwV"),
    9: (8, "aGgSTvwV"),
    10: (8, "aBbgggTwV"),
    11: (8, "aBbbSRrwV"),
    12: (9, "aBbxRrwV"),
    13: (9, "axbbshrwV"),
    14: (10, "abbhorwV"),
    15: (10, "aBxsOrwU"),
    16: (11, "abSRrwU"),
    17: (12, "axbRrwU"),
    18: (13, "cbhorU"),
    19: (14, "cxOrU"),
    20: (15, "cbRwU"),
    21: (16, "cbsU"),
    22: (17, "ct"),
}

LETTERS = {
    "A": BACK[5], "a": BACK[4], "c": BACK[3], "B": BACK[2], "b": BACK[1], "d": BACK[0],
    "x": SPOT[0], "X": SPOT[1],
    "t": SAGE[0], "s": SAGE[1], "S": SAGE[2], "T": SAGE[3],
    "q": ROSE[0], "R": ROSE[1], "r": ROSE[2],
    "O": RED[0], "o": RED[1], "h": RED[2],
    "U": BELLY[0], "V": BELLY[1], "v": BELLY[2], "w": BELLY[3], "W": BELLY[4],
    "E": EYE[0], "e": EYE[1], "i": IRIS[0], "I": IRIS[1],
    "m": OUTLINE, "G": BACK[1], "g": mix(SAGE[0], ROSE[0], 0.45),
}
NO_GLINT = set("EemxX")


def _grid() -> dict:
    out = {}
    for x, (y0, col) in COLS.items():
        for j, ch in enumerate(col):
            out[(x, y0 + j)] = ch
    return out


GRID = _grid()


def _mask() -> np.ndarray:
    mask = np.zeros((SIZE, SIZE), bool)
    for x, y in GRID:
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
    for (x, y), ch in GRID.items():
        px[x, y] = rgba(LETTERS[ch])
    return img


def _outline(img: Image.Image) -> Image.Image:
    """1 px outline around the silhouette: dark olive beside the body, a softer olive
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


def _glint(img: Image.Image, t: float, strength: float = 0.62, width: float = 3.4, pause: float = 0.3):
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
            if kb < 0 or kb > 3 or GRID.get((x, y)) in NO_GLINT:
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
    _paint_fin(img, _tail(sway), FIN[2], 214, cov_min=0.4, lit=FIN[3])
    _paint_fin(img, _dorsal(ripple), FIN[2], 206, lit=FIN[3],
               dots=[((10.4 + 0.2 * ripple, TOP(10.4) + 2.1), SPOT[1]), ((12.6 + 0.3 * ripple, TOP(12.6) + 1.3), SPOT[1])])
    _paint_fin(img, _adipose(ripple), FIN[2], 224, cov_min=0.4,
               dots=[((18.6 + 0.2 * ripple, TOP(18.6) + 1.3), mix(RED[1], FIN[3], 0.3))])
    _paint_fin(img, _anal(ripple), FIN[3], 204)
    body = _body()
    _glint(body, t)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), FIN[3], 208)
    _paint_fin(front, _pelvic(flap), FIN[3], 206)
    img.alpha_composite(front)
    return _outline(img)


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
