"""Red Snapper: an uncommon ocean fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's rose-red scheme. Deep, laterally compressed snapper body with the
steep sloping forehead and pointed snout, a big mouth with a jutting lower jaw, a red-irised
eye set high on the head, a crimson back under a warm rim light, coral-red flanks crossed by
oblique scale rows and a countershaded silvery-pink belly. One long continuous dorsal fin
(low spiny front, taller soft rear), long pointed pectoral fin, pelvic fin, an anal fin with
a pointed rear and a shallowly forked tail; fins translucent rose with darker painted rays.

Animation (UNCOMMON, 12 frames x 3 ticks): the tail wags once per loop, pectoral and pelvic
fins sway a pixel and the dorsal ripples; a soft glint slides along the back (frames 1-5),
then a shimmer band of glittering pink-gold scales rolls across the body (frames 7-11).
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sprite

ID = "fish_red_snapper"
NAME = "Red Snapper"
KIND = "item"
MODEL_KEY = "fish/red_snapper"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palettes (darkest -> lightest); shadows lean violet, lights lean warm gold ----------
OUTLINE = "#3d0b1f"
OUTLINE_LIT = "#56122a"
FIN_OUTLINE = ("#63182e", 232)
BACK = ["#5e1027", "#901d35", "#b3283d", "#d03a44", "#e6544d", "#f57d60", "#ffae88"]
FLANK = ["#c23744", "#d9494c", "#ea625a", "#f47f6b", "#fb9f86"]
BELLY = ["#e0808a", "#f0a7a5", "#f8c7bd", "#fde2d8", "#fff3ee"]
FIN = ["#7c1a30", "#a82d40", "#cc4a50", "#e46c64", "#f39583", "#fbbfa8"]
EYE = ["#1c0610", "#fff6ee", "#d9382f", "#ff8456"]   # pupil, catchlight, red iris, lit iris
GLINT = "#fff2e6"
SHIMMER = "#ffd9c4"
SPARK = "#fffaf2"

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 22.0                      # snout to tail base
SNOUT = (2.9, 9.4)             # snout tip in the frame
SS = 4                         # supersampling per pixel side
TAIL_REST = 10.0               # the tail rests bent a little toward the back (degrees)


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


# Half-depths of the body above (TOP) and below (BOT) the axis: a deep snapper body with
# the steep, straight forehead rising from a pointed snout.
TOP = _smooth([(0.0, 0.8), (1.0, 1.9), (2.0, 3.0), (3.2, 4.15), (4.6, 5.05), (6.2, 5.55), (8.0, 5.7),
               (10.0, 5.55), (12.0, 5.05), (14.0, 4.25), (16.0, 3.3), (18.0, 2.35), (20.0, 1.7), (22.0, 1.4)])
BOT = _smooth([(0.0, 0.3), (1.0, 0.75), (2.0, 1.25), (3.2, 1.85), (4.6, 2.5), (6.2, 3.1), (8.0, 3.6),
               (10.0, 3.9), (12.0, 3.8), (14.0, 3.3), (16.0, 2.6), (18.0, 1.95), (20.0, 1.55), (22.0, 1.4)])

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


# ---- swaying fins: (polygon, rays) in local coordinates; v > 0 is the back --------------

TAIL_PIVOT = (21.4, 0.0)


def _tail_grade(u: float, v: float) -> str:
    """Tail membrane: deep red at the root, brighter coral toward the lobe tips."""
    d = math.hypot(u - TAIL_PIVOT[0], v - TAIL_PIVOT[1])
    return FIN[1] if d < 2.0 else FIN[2] if d < 4.0 else FIN[3]


def _tail(sway: float):
    """Shallowly forked (emarginate) snapper tail with pointed lobes."""
    pts = [(20.8, 1.4), (22.4, 2.7), (24.4, 4.3), (27.3, 5.4), (27.0, 3.9), (26.2, 2.4), (25.5, 1.1),
           (25.2, 0.0), (25.5, -1.1), (26.2, -2.4), (27.0, -3.9), (27.3, -5.4), (24.4, -4.3), (22.4, -2.7),
           (20.8, -1.4)]
    rays = [((23.2, 1.6), (26.6, 4.2), FIN[1]), ((23.2, -1.6), (26.6, -4.2), FIN[1]),
            ((23.6, 0.5), (25.6, 1.3), FIN[1]), ((23.6, -0.5), (25.6, -1.3), FIN[1])]
    a = TAIL_REST + 9.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _pelvic(flap: float):
    pivot = (8.4, -BOT(8.4))
    pts = [(7.6, -BOT(7.6) + 0.5), (9.4, -BOT(9.4) + 0.4), (11.8, -BOT(11.8) - 2.4), (10.0, -BOT(10.0) - 2.8)]
    rays = [((8.6, -BOT(8.6)), (10.2, -BOT(10.2) - 1.9), FIN[4])]
    a = 9.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=228, lit=None, edge=None, grade=None):
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
            colour, a = (grade(u, v) if grade else membrane), alpha
            for ra, rb, rc in rays:
                if cov[y, x] >= 0.75 and _near(u, v, ra, rb, 0.45):
                    colour, a = rc, ray_alpha
                    break
            if lit and inside[y, x] and ((y > 0 and not inside[y - 1, x] and not MASK[y - 1, x])
                                         or (x > 0 and not inside[y, x - 1] and not MASK[y, x - 1])):
                colour = lit
            elif edge and ((y + 1 < SIZE and cov[y + 1, x] < cov_min) or (x + 1 < SIZE and cov[y, x + 1] < cov_min)):
                colour = edge
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)


# ---- body -------------------------------------------------------------------------------

BODY_ADD: list[tuple[int, int]] = [(10, 7), (11, 7), (3, 11)]
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

LETTERS = {"o": OUTLINE, "K": EYE[0], "W": EYE[1], "R": EYE[2], "r": EYE[3]}
LETTERS.update({k: c for k, c in zip("abcdefg", BACK)})
LETTERS.update({k: c for k, c in zip("hijkl", FLANK)})
LETTERS.update({k: c for k, c in zip("01234", BELLY)})

# Head details, (x, y) -> letter; filled in after looking at the mask.
HEAD = {
    (7, 9): "W", (8, 9): "K", (9, 9): "R",                 # eye: pupil, catchlight, red iris
    (7, 10): "K", (8, 10): "K", (9, 10): "R",
    (6, 10): "R", (7, 11): "R", (8, 11): "r",
    (3, 10): "o", (4, 11): "o", (5, 11): "b",              # mouth gape and maxilla
    (3, 11): "3", (4, 12): "2", (5, 12): "2",              # pale lower jaw
}


# Dorsal and anal fins are hand-placed pixel clusters: S = a lit spine, m = membrane,
# R = a dark soft ray. The long snapper dorsal: four notched spines, then the taller soft part.
DORSAL = {
    (12, 5): "S", (12, 6): "S", (12, 7): "S",
    (13, 7): "m", (13, 8): "m",
    (14, 7): "S", (14, 8): "S", (14, 9): "m",
    (15, 9): "m", (15, 10): "m",
    (16, 9): "S", (16, 10): "S", (16, 11): "m",
    (17, 11): "m", (17, 12): "m",
    (18, 11): "S", (18, 12): "m", (18, 13): "R", (18, 14): "m",
    (19, 12): "m", (19, 13): "m", (19, 14): "R", (19, 15): "m",
    (20, 13): "m", (20, 14): "m", (20, 15): "R", (20, 16): "m",
    (21, 15): "m", (21, 16): "m", (21, 17): "m",
    (22, 18): "m",
}
# Pointed-rear snapper anal fin under the tail end of the belly.
ANAL = {
    (14, 20): "S",
    (15, 20): "m", (15, 21): "S",
    (16, 20): "m", (16, 21): "m", (16, 22): "S",
    (17, 20): "m", (17, 21): "R", (17, 22): "m", (17, 23): "m",
    (18, 20): "m", (18, 21): "m", (18, 22): "R",
    (19, 21): "m",
}
# The long pointed pectoral fin lies over the flank just behind the gill cover, in two
# poses (spread / folded back): p = pale membrane, P = its darker lower edge.
PECT = {
    "spread": [(9, 13, "p"), (10, 13, "p"), (10, 14, "p"), (11, 14, "p"), (11, 15, "p"), (12, 15, "p"),
               (9, 14, "P"), (10, 15, "P"), (11, 16, "P"), (12, 16, "P"), (13, 16, "P")],
    "folded": [(9, 13, "p"), (10, 13, "p"), (11, 13, "p"), (11, 14, "p"), (12, 14, "p"), (13, 14, "p"),
               (9, 14, "P"), (10, 14, "P"), (12, 15, "P"), (13, 15, "P"), (14, 15, "P")],
}
PECT_TINT = {"p": ("#ffc6ab", 0.5), "P": (FIN[1], 0.6)}
FIN_ART = {"S": (FIN[4], 228), "m": (FIN[2], 208), "R": (FIN[0], 224)}


def _paint_art(img, cells: dict) -> None:
    px = img.load()
    for (x, y), letter in cells.items():
        colour, alpha = FIN_ART[letter]
        r, g, b, _ = rgba(colour)
        px[x, y] = (r, g, b, alpha)


def _medial(ripple: float):
    """The dorsal and anal fins at a ripple phase: a wave runs back along the soft dorsal
    (its peak steps from one ray to the next) and the anal tip flexes a pixel."""
    dorsal = dict(DORSAL)
    anal = dict(ANAL)
    if ripple > 0.35:
        dorsal[(20, 12)] = "m"
        dorsal.pop((19, 12))
    elif ripple < -0.35:
        dorsal[(18, 10)] = "S"
    if ripple > 0.0:
        anal[(18, 23)] = "m"
        anal.pop((17, 23))
    return dorsal, anal


def _scale_mark(x: int, y: int) -> bool:
    """Oblique scale rows: a staggered lattice of marks, one every other row."""
    return y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1


def _gill(x: int, y: int) -> bool:
    """The gill-cover edge: a curve bowed toward the tail just behind the eye."""
    v = VC[y, x]
    return abs(UC[y, x] - (6.9 - 0.075 * v * v)) < 0.55 and -3.4 < v < 4.2


def _band_colour(x: int, y: int) -> str:
    kb, kv = int(KB[y, x]), int(KV[y, x])
    h = kb + kv
    f = kb / h if h else 0.0
    u = UC[y, x]
    head = u < 6.6
    body = not head and u < SL - 1.0
    scale = _scale_mark(x, y) and body
    lit = _scale_mark(x + 1, y) and body and f >= 0.46   # the lit rim of each flank scale
    if kb == 0:                                              # rim light, fading toward the tail
        return BACK[5] if u < 8 else BACK[4] if u < 15 else BACK[3]
    if kv == 0:
        return BELLY[0] if u > 15 else BELLY[1]              # belly edge turning away
    if kb == 1:
        return BACK[3] if head else BACK[1]
    if f < 0.3:
        return BACK[1] if scale else BACK[2]
    if f < 0.46:
        return BACK[2] if scale else BACK[3]
    if f < 0.6:
        return FLANK[0] if scale else FLANK[2] if lit else FLANK[1]
    if f < 0.7:
        return FLANK[1] if scale else FLANK[3] if lit else FLANK[2]
    if f < 0.8:
        return FLANK[4] if lit else FLANK[3]
    if kv == 1:
        return BELLY[2]
    return BELLY[3] if u < 13 else BELLY[2]


def _body() -> Image.Image:
    img = canvas(SIZE)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                c = _band_colour(x, y)
                if _gill(x, y) and KB[y, x] > 0 and KV[y, x] > 0:
                    c = mix(c, OUTLINE, 0.4)
                px[x, y] = rgba(c)
    for (x, y), letter in HEAD.items():
        px[x, y] = rgba(LETTERS[letter])
    return img


def _outline(img: Image.Image) -> Image.Image:
    """1 px outline around the silhouette: dark maroon beside the body (a touch lighter on
    the side facing the top-left light), softer and translucent where it only borders fins."""
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
                # a touch lighter where the body lies below this pixel and it faces the light
                lit = y + 1 < SIZE and MASK[y + 1, x] and not (y > 0 and src[x, y - 1][3])                     and not (x > 0 and src[x - 1, y][3])
                dst[x, y] = rgba(OUTLINE_LIT if lit else OUTLINE)
            else:
                dst[x, y] = (fr, fg, fb, FIN_OUTLINE[1])
    return out


def _glint(img: Image.Image, t: float, strength: float = 0.72, width: float = 3.4):
    """Frames 1-5: a soft glint slides along the back from snout to tail (a shine() band
    that follows the fish's own axis instead of the frame's diagonal)."""
    step = round(t * FRAMES)
    if not 1 <= step <= 5:
        return
    centre = 1.0 + (SL - 1.0) * (step - 1) / 4.0
    px = img.load()
    c = rgba(GLINT)
    for y in range(SIZE):
        for x in range(SIZE):
            kb = int(KB[y, x])
            if kb < 0 or kb > 4 or HEAD.get((x, y), "") in ("o", "W", "K", "R", "r"):
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / width) * strength * (1.0 - kb / 5.5)
            if k <= 0:
                continue
            r, g, b, a = px[x, y]
            px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _spark_spot(x: int, y: int) -> bool:
    """Where the scales catch the light as the shimmer passes (a denser diamond lattice)."""
    return y % 2 == 1 and (x + y // 2) % 2 == 0


def _shimmer(img: Image.Image, t: float):
    """Frames 7-11: a shimmer band of glittering scales rolls across the body, head to tail."""
    step = round(t * FRAMES)
    if step < 7:
        return
    centre = 5.0 + 15.0 * (step - 7) / 4.0
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if not MASK[y, x] or UC[y, x] < 5.5 or KB[y, x] == 0 or KV[y, x] == 0:
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.6)
            if k <= 0:
                continue
            spark = _spark_spot(x, y)
            strength = (0.95 if spark else 0.5) * min(1.0, k * 1.4)
            px[x, y] = rgba(mix(px[x, y], SPARK if spark else SHIMMER, strength))


def _frame(t: float) -> Image.Image:
    """One 32x32 frame at loop phase t (0..1)."""
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    # Medial fins sit behind the body; the near pectoral and pelvic fins lie over it.
    _paint_fin(img, _tail(sway), FIN[2], 214, cov_min=0.4, lit=FIN[5], grade=_tail_grade)
    dorsal, anal = _medial(ripple)
    _paint_art(img, dorsal)
    _paint_art(img, anal)
    body = _body()
    _glint(body, t)
    _shimmer(body, t)
    bp = body.load()
    for x, y, part in PECT["folded" if flap > 0.3 else "spread"]:
        tint, k = PECT_TINT[part]
        bp[x, y] = rgba(mix(bp[x, y], tint, k))
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pelvic(flap), FIN[4], 210, edge=FIN[1])
    img.alpha_composite(front)
    return _outline(img)


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
