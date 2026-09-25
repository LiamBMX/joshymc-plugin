"""Smelt: a common, slender silver fish for the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's pale sage-green / rosy silver / mint scheme with its golden
accent. A long, slim rainbow-smelt body: a translucent olive-sage back freckled with fine
dark speckles under a rim light, a mint seam, the smelt's silver lateral stripe
shifting pink -> lilac -> periwinkle from head to tail, a pearly countershaded belly, a big golden-ringed
eye, a long gaping mouth with the jutting lower jaw, one dorsal fin mid-body, the tiny
adipose fin near the tail, low pectoral, pelvic and anal fins and a forked tail (fins
translucent with painted rays). Animated (COMMON, 8 frames x 3 ticks): the tail and fins
sway about a pixel on offset sine phases and a soft glint slides along the back from snout
to tail, then rests.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, rgba, save_animation, sprite

ID = "fish_smelt"
NAME = "Smelt"
KIND = "item"
MODEL_KEY = "fish/smelt"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 8
FRAMETIME = 3

# ---- palettes (darkest -> lightest), from the old sprite's sage / rosy silver / mint / gold
OUTLINE = "#233a33"
FIN_OUTLINE = ("#44604f", 235)     # softer edge where the outline only touches a fin
BACK = ["#2c4a3c", "#3f6450", "#567f62", "#709a74", "#8fb489", "#b6d2a6"]
SPECK = "#223a30"
MINT = ["#8fc7a4", "#b7e3c3", "#dcf5e0"]
SILVER = ["#6f7187", "#8e8ea4", "#aeacbf", "#c9c5d2", "#dfdbe2", "#efebee", "#fdfbfa"]
IRIS = ["#dcaed0", "#c3a8de", "#a9b9e6"]   # iridescent lateral stripe: pink -> lilac -> periwinkle
IRIS_LIT = ["#f3d9e8", "#e4d9f3", "#d9e4f7"]
GOLD = ["#7d5b1e", "#bf8a24", "#f0b834", "#ffd96a"]
FIN = ["#4f6b5a", "#648270", "#7f9d88", "#a1bba5", "#c3d7c0", "#e3eedb"]
EYE = ["#0f1a17", "#ffffff"]
GLINT = "#f6fff2"

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 24.0                      # snout to tail base
SNOUT = (2.2, 8.2)             # snout tip in the frame
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


# Half-depths of the body above (TOP) and below (BOT) the axis: long and slim, with a long
# pointed head and a narrow tail stalk.
TOP = _smooth([(0.0, 0.55), (1.0, 1.05), (2.2, 1.6), (3.6, 2.1), (5.2, 2.5), (7.2, 2.8), (9.5, 3.0),
               (12.0, 3.05), (14.5, 2.9), (17.0, 2.45), (19.4, 1.85), (21.8, 1.3), (23.2, 1.05), (24.0, 1.0)])
BOT = _smooth([(0.0, 0.65), (1.0, 1.25), (2.2, 1.85), (3.6, 2.45), (5.2, 2.9), (7.2, 3.3), (9.5, 3.55),
               (12.0, 3.6), (14.5, 3.35), (17.0, 2.8), (19.4, 2.05), (21.8, 1.4), (23.2, 1.1), (24.0, 1.0)])

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

TAIL_PIVOT = (23.4, 0.0)


def _tail(sway: float):
    pts = [(22.8, 1.0), (24.5, 2.2), (26.6, 3.5), (28.8, 4.5), (28.5, 3.0), (27.3, 1.6), (26.3, 0.6), (25.8, 0.0),
           (26.3, -0.6), (27.3, -1.6), (28.5, -3.0), (28.8, -4.5), (26.6, -3.5), (24.5, -2.2), (22.8, -1.0)]
    rays = [((23.8, 0.6), (28.1, 3.6), FIN[1]), ((23.8, -0.6), (28.1, -3.6), FIN[1]),
            ((24.0, 0.0), (25.5, 0.0), FIN[1])]
    a = TAIL_REST + 8.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _tail_tint(sway: float):
    """The tail's trailing edge darkens a shade (measured in the unswayed tail frame)."""
    a = -math.radians(TAIL_REST + 8.0 * sway)
    c, s_ = math.cos(a), math.sin(a)
    pu, pv = TAIL_PIVOT

    def tint(u, v):
        u0 = pu + (u - pu) * c - (v - pv) * s_
        v0 = pv + (u - pu) * s_ + (v - pv) * c
        return FIN[1] if u0 > 27.2 and abs(v0) > 2.4 else None
    return tint


def _dorsal(ripple: float):
    tip = (11.3 + 0.5 * ripple, TOP(11.3) + 3.1)
    pts = [(10.0, TOP(10.0) - 0.6), tip, (12.6 + 0.6 * ripple, TOP(12.6) + 2.3), (14.3, TOP(14.3) + 0.5),
           (14.3, TOP(14.3) - 0.6)]
    rays = [((10.4, TOP(10.4)), tip, FIN[4]),                                   # lit leading ray
            ((12.2, TOP(12.2)), (12.9 + 0.6 * ripple, TOP(12.9) + 2.0), FIN[1])]
    return pts, rays


def _adipose(ripple: float):
    """The smelt's tiny fleshy fin between the dorsal fin and the tail."""
    pts = [(18.6, TOP(18.6) - 0.5), (19.6 + 0.3 * ripple, TOP(19.6) + 1.35), (20.6, TOP(20.6) + 0.9),
           (21.0, TOP(21.0) - 0.5)]
    return pts, []


def _pectoral(flap: float):
    pivot = (6.6, -BOT(6.6) + 0.6)
    pts = [(6.1, -BOT(6.1) + 1.1), (7.5, -BOT(7.5) + 0.7), (10.4, -BOT(10.4) - 1.4), (9.5, -BOT(9.5) - 2.2),
           (6.7, -BOT(6.7) - 0.6)]
    rays = [(pivot, (9.7, -BOT(9.7) - 1.7), FIN[2])]
    a = 10.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _pelvic(flap: float):
    pivot = (11.6, -BOT(11.6))
    pts = [(11.0, -BOT(11.0) + 0.5), (12.3, -BOT(12.3) + 0.4), (14.2, -BOT(14.2) - 1.5),
           (13.0, -BOT(13.0) - 1.7)]
    a = 9.0 * flap
    return _rot(pts, pivot, a), []


def _anal(ripple: float):
    pts = [(15.8, -BOT(15.8) + 0.5), (16.6, -BOT(16.6) - 1.8 - 0.3 * ripple), (20.9, -BOT(20.9) - 1.1),
           (21.5, -BOT(21.5) + 0.5)]
    rays = [((16.8, -BOT(16.8)), (16.8, -BOT(16.8) - 1.7 - 0.3 * ripple), FIN[1]),
            ((19.0, -BOT(19.0)), (19.3, -BOT(19.3) - 1.2), FIN[1])]
    return pts, rays


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=228, lit=None, tint=None):
    """Paint a translucent fin: membrane plus rays (segments with their own colour). With
    lit, fin pixels whose upper or left neighbour is open water catch the top-left light;
    tint(u, v) may return a colour for the membrane there (a dusky trailing edge)."""
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
            if tint:
                colour = tint(u, v) or membrane
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
# The silhouette is the procedural lens above with a few hand edits; its colours come from
# discrete bands counted in from the dorsal and ventral contours (clean staircases that
# follow the outline), then hand-placed head details on top.

BODY_ADD = [(2, 10), (3, 11)]          # the jutting lower jaw          # the jutting lower jaw
BODY_CUT = [(2, 8)]                    # a pointed snout


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
LETTERS = {"o": OUTLINE, "K": EYE[0], "W": EYE[1], "x": SPECK}
LETTERS.update({k: c for k, c in zip("abcdef", BACK)})
LETTERS.update({k: c for k, c in zip("qrs", MINT)})
LETTERS.update({k: c for k, c in zip("0123456", SILVER)})
LETTERS.update({k: c for k, c in zip("ghij", GOLD)})
LETTERS.update({k: c for k, c in zip("uvw", IRIS)})

# Head details: (x, y) -> letter. The big eye with a catchlight and the old sprite's gold
# as a partial iris ring, the long mouth gaping back under the eye over the jutting lower
# jaw, a bright silver cheek and the gill-cover arc.
HEAD = {
    (3, 8): "e", (2, 9): "3", (3, 9): "c", (4, 9): "4", (5, 9): "W", (6, 9): "K", (7, 9): "5",
    (2, 10): "4", (3, 10): "o", (4, 10): "1", (5, 10): "K", (6, 10): "K", (7, 10): "i",
    (3, 11): "3", (4, 11): "4", (5, 11): "5", (6, 11): "h", (7, 11): "4", (8, 11): "1",
    (4, 12): "2", (5, 12): "4", (6, 12): "5", (7, 12): "4", (8, 12): "1",
    (7, 13): "1",
}



def _stripe(x: int, y: int, lit: bool) -> str:
    """The lateral stripe shifts pink -> lilac -> periwinkle from head to tail, in three
    clean runs rather than dithered dots."""
    u = UC[y, x]
    i = 0 if u < 9.0 else (1 if u < 15.5 else 2)
    return (IRIS_LIT if lit else IRIS)[i]


def _band_colour(x: int, y: int) -> str:
    kb, kv = int(KB[y, x]), int(KV[y, x])
    h = kb + kv + 1
    nb = 1 if h <= 6 else 2                         # rows of olive back under the rim
    if kb == 0:
        return BACK[4] if x < 12 else BACK[3]       # rim light on the back
    if kb <= nb:
        if kb == nb and x >= 9 and (x + 2 * y) % 5 == 0:
            return SPECK                            # fine dark speckles on the back
        return BACK[1] if kb == nb and nb == 2 else BACK[2]
    if kb == nb + 1:
        return MINT[1] if x < 19 else MINT[0]       # translucent mint seam above the stripe
    if kv == 0:
        return SILVER[2]                            # belly edge
    if kb == nb + 2 and kv >= 1:
        return _stripe(x, y, False)                 # the smelt's iridescent silver stripe
    if kb == nb + 3 and kv >= 2:
        return _stripe(x, y, True)
    if kv == 1:
        return SILVER[4]
    if kv == 2:
        return SILVER[6]
    return SILVER[5]


def _body() -> Image.Image:
    img = canvas(SIZE)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                px[x, y] = rgba(_band_colour(x, y))
    for (x, y), letter in HEAD.items():
        px[x, y] = rgba(LETTERS[letter])
    return img


def _outline(img: Image.Image) -> Image.Image:
    """1 px outline around the silhouette: deep green-teal beside the body, a softer sage
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


def _glint(img: Image.Image, t: float, strength: float = 0.66, width: float = 3.0, pause: float = 0.3):
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
    _paint_fin(img, _tail(sway), FIN[2], 212, cov_min=0.4, lit=FIN[3], tint=_tail_tint(sway))
    _paint_fin(img, _dorsal(ripple), FIN[2], 205, lit=FIN[4])
    _paint_fin(img, _adipose(ripple), FIN[3], 215, cov_min=0.4)
    _paint_fin(img, _anal(ripple), FIN[2], 200)
    body = _body()
    _glint(body, t)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), FIN[4], 200)
    _paint_fin(front, _pelvic(flap), FIN[4], 200)
    img.alpha_composite(front)
    return _outline(img)


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
