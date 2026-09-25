"""Shadow Trout: a rare fish of the JoshyMC fishing collection (swamp, deep dark).

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's midnight indigo / slate-teal / violet scheme and its gold
accent, now the glowing iris. A true trout: a stout torpedo body with a blunt snout, a
long jaw running back under the eye, a curved gill cover, a mid-body dorsal fin, the
little fleshy adipose fin in front of the tail, low pectoral, pelvic and anal fins and a
broad, barely notched tail. Colour runs from a navy back under a cool rim light through
slate-teal flanks peppered with dark trout spots and a violet lateral stripe (the shadow
version of a rainbow trout's pink band) to a dusky lavender belly. A row of sculk-cyan
photophores along the stripe gives it its deep-dark character. Fins are translucent
indigo with painted rays; the dorsal fin and tail carry spots too.

Animation (RARE, 12 frames x 3 ticks): the tail wags and the fins sway about a pixel, the
photophores pulse one after another from head to tail, a soft glint slides along the back
(frames 1-5), then an icy blue sheen of sparkling scales rolls across the body (7-11),
while three pale-blue twinkles bloom and fade around the fish in turn.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, shade, sparkle, sprite

ID = "fish_shadow_trout"
NAME = "Shadow Trout"
KIND = "item"
MODEL_KEY = "fish/shadow_trout"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palettes (darkest -> lightest), from the old sprite's indigo / slate / violet / gold ----
OUTLINE = "#130f2b"
OUTLINE_LIT = "#1d1a3e"
FIN_OUTLINE = ("#2a2854", 232)
BACK = ["#131228", "#1b1c3e", "#252a55", "#313a69", "#495a8c", "#6d82b4"]
FLANK = ["#2a3560", "#34436e", "#3f5379", "#566a8e", "#7086a6"]      # slate, leaning teal
STRIPE = ["#43287a", "#5d38a0", "#7a4fc6", "#9c74e2"]                # violet lateral band
BELLY = ["#5b5988", "#72719d", "#8c8bb6", "#a5a5cb", "#bdbedc"]      # dusky lavender
SPOT = "#141130"
SPOT_FIN = "#1a1740"
GOLD = ["#8a5a1c", "#d9962a", "#ffc832", "#fff0a0"]
EYE = ["#0a0818", "#ffffff"]
FIN = ["#27275a", "#343872", "#454c8a", "#5b66a3", "#7985be", "#a0acd9"]
PHOTO_DIM, PHOTO_LIT, PHOTO_CORE = "#2f6f86", "#43d8e0", "#c8fffb"
GLINT = "#dfe9ff"
SHEEN = "#8fb8ff"
SPARK = "#eef6ff"
TWINKLE = "#d6e6ff"

# ---- geometry (pixels) --------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 22.8                      # snout to tail base
SNOUT = (2.2, 8.4)             # snout tip in the frame
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


# Half-depths of the body above (TOP) and below (BOT) the axis: blunt snout, a stout
# mid-body and a thick caudal peduncle.
TOP = _smooth([(0.0, 1.1), (0.8, 1.9), (1.8, 2.55), (3.0, 3.1), (4.5, 3.55), (6.3, 3.9), (8.3, 4.1),
               (10.5, 4.15), (12.7, 3.95), (15.0, 3.4), (17.3, 2.7), (19.5, 2.1), (21.4, 1.75), (22.8, 1.7)])
BOT = _smooth([(0.0, 1.0), (0.8, 1.8), (1.8, 2.55), (3.0, 3.2), (4.5, 3.75), (6.3, 4.2), (8.3, 4.5),
               (10.5, 4.55), (12.7, 4.3), (15.0, 3.7), (17.3, 2.9), (19.5, 2.2), (21.4, 1.8), (22.8, 1.7)])

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


# ---- fins: (polygon, rays, spots) in local coordinates; v > 0 is the back -----------------

TAIL_PIVOT = (22.2, 0.0)
TAIL_REST = 6.0


def _tail(sway: float):
    # broad and barely notched: a trout's tail is almost square
    pts = [(21.6, 1.5), (23.4, 2.4), (25.3, 3.5), (27.4, 4.5), (27.9, 3.4), (27.6, 1.8), (27.1, 0.2),
           (27.6, -1.5), (28.0, -3.2), (27.6, -4.5), (25.4, -3.6), (23.4, -2.5), (21.6, -1.5)]
    rays = [((22.8, 0.9), (27.3, 3.7), FIN[1]), ((22.8, -0.9), (27.4, -3.8), FIN[1]),
            ((23.0, 0.0), (26.6, 0.1), FIN[1])]
    spots = [(24.6, 1.9), (26.4, 2.9), (25.8, -1.2), (24.3, -2.3), (27.0, -2.6)]
    a = TAIL_REST + 9.0 * sway
    return (_rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays],
            _rot(spots, TAIL_PIVOT, a))


def _dorsal(ripple: float):
    tip = (10.2 + 0.4 * ripple, TOP(10.2) + 3.6)
    pts = [(8.6, TOP(8.6) - 0.6), tip, (12.6 + 0.5 * ripple, TOP(12.6) + 2.4), (14.0, TOP(14.0) + 0.5),
           (14.0, TOP(14.0) - 0.6)]
    rays = [((9.0, TOP(9.0)), tip, FIN[4]),                                     # lit leading ray
            ((11.2, TOP(11.2)), (11.9 + 0.5 * ripple, TOP(11.9) + 2.7), FIN[1])]
    spots = [(10.6, TOP(10.6) + 1.3), (12.8, TOP(12.8) + 1.2)]
    return pts, rays, spots


def _adipose():
    return [(17.9, TOP(17.9) - 0.5), (18.5, TOP(18.5) + 1.5), (19.9, TOP(19.9) + 1.8), (20.7, TOP(20.7) + 0.4),
            (20.7, TOP(20.7) - 0.5)]


def _pectoral(flap: float):
    pivot = (5.9, -BOT(5.9) + 0.9)
    pts = [(5.3, -BOT(5.3) + 1.3), (6.8, -BOT(6.8) + 1.1), (9.6, -BOT(9.6) - 1.3), (8.7, -BOT(8.7) - 2.2),
           (6.1, -BOT(6.1) - 0.2)]
    rays = [(pivot, (9.0, -BOT(9.0) - 1.6), FIN[2])]
    a = 10.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays], []


def _pelvic(flap: float):
    pivot = (11.6, -BOT(11.6))
    pts = [(11.0, -BOT(11.0) + 0.5), (12.4, -BOT(12.4) + 0.4), (14.3, -BOT(14.3) - 1.7),
           (13.0, -BOT(13.0) - 1.9)]
    a = 9.0 * flap
    return _rot(pts, pivot, a), [], []


def _anal(ripple: float):
    pts = [(15.2, -BOT(15.2) + 0.5), (15.9, -BOT(15.9) - 2.1 - 0.3 * ripple), (19.2, -BOT(19.2) - 1.0),
           (19.8, -BOT(19.8) + 0.5)]
    rays = [((16.1, -BOT(16.1)), (16.1, -BOT(16.1) - 1.9 - 0.3 * ripple), FIN[4]),
            ((17.8, -BOT(17.8)), (18.2, -BOT(18.2) - 1.1), FIN[1])]
    return pts, rays, []


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=226, lit=None):
    """Paint a translucent fin: membrane plus rays (segments with their own colour) and
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
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)
    for su, sv in spots:
        x = int(math.floor(SNOUT[0] + su * AX[0] + sv * UP[0]))
        y = int(math.floor(SNOUT[1] + su * AX[1] + sv * UP[1]))
        if 0 <= x < SIZE and 0 <= y < SIZE and cov[y, x] >= 0.9:
            r, g, b_, _ = rgba(SPOT_FIN)
            px[x, y] = (r, g, b_, 236)


def _paint_adipose(img):
    cov = _poly(_adipose())
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if cov[y, x] >= 0.45 and not MASK[y, x]:
                lit = y > 0 and cov[y - 1, x] < 0.45 and not MASK[y - 1, x]
                r, g, b, _ = rgba(BACK[5] if lit else BACK[3])
                px[x, y] = (r, g, b, 240)


# ---- body ---------------------------------------------------------------------------------

BODY_ADD: list = []
BODY_CUT: list = []


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


def _column_depth(mask: np.ndarray) -> np.ndarray:
    out = np.zeros(mask.shape)
    for x in range(SIZE):
        ys = np.nonzero(mask[:, x])[0]
        if len(ys):
            top, bot = ys[0], ys[-1]
            for y in ys:
                out[y, x] = (y - top + 0.5) / (bot - top + 1)
    return out


DEPTH = _column_depth(MASK)

# Hand-placed head details: (x, y) -> a colour, or ("dark" | "light", k) to shade the
# painted band colour there. A 2x2 eye with a catchlight and the old sprite's gold as a
# glowing iris crescent; the mouth gape and long jaw line; a pale lower jaw; the curved
# gill cover edge with a lit rim ahead of it.
HEAD: dict = {
    (5, 8): EYE[1], (6, 8): EYE[0], (5, 9): EYE[0], (6, 9): EYE[0],
    (4, 8): GOLD[1], (4, 9): GOLD[2], (5, 10): GOLD[2], (6, 10): GOLD[1], (7, 9): GOLD[0],
    (2, 10): OUTLINE, (3, 10): ("dark", 0.75), (4, 11): ("dark", 0.7), (5, 11): ("dark", 0.45),
    (2, 11): BELLY[3], (3, 11): BELLY[4], (3, 12): BELLY[3],
    (2, 9): ("light", 0.25), (3, 9): ("light", 0.2),
    (9, 9): ("dark", 0.45), (9, 10): ("dark", 0.5), (8, 11): ("dark", 0.55), (8, 12): ("dark", 0.55),
    (7, 13): ("dark", 0.5),
    (8, 10): ("light", 0.22), (7, 11): ("light", 0.22), (7, 12): ("light", 0.2),
}
# Photophores along the violet stripe, head to tail: (x, y).
PHOTOPHORES: list = [(10, 13), (13, 14), (16, 16), (19, 18)]


def _depth(x: int, y: int) -> float:
    """0 at the top of this pixel's body column, 1 at its bottom: bands counted down each
    column make clean staircases that follow the contours."""
    return float(DEPTH[y, x])


def _inside(x: int, y: int) -> bool:
    return 0 <= x < SIZE and 0 <= y < SIZE and bool(MASK[y, x])


def _zone(x: int, y: int) -> str:
    f = _depth(x, y)
    if not _inside(x, y - 1) or (not _inside(x - 1, y) and f < 0.5):
        return "rim"                   # edge facing the top-left light
    if not _inside(x, y + 1) or (not _inside(x + 1, y) and f > 0.5):
        return "belly_edge"            # edge in shadow
    if f < 0.36:
        return "back"
    if f < 0.47:
        return "flank"
    if f < 0.66:
        return "stripe"
    if f < 0.8:
        return "lower"
    return "belly"


# Trout spots, placed by hand: pale char-like spots on the dark back, dark spots on the
# flank and the top of the violet stripe.
PALE_SPOTS = {(11, 10), (9, 11), (13, 12), (15, 13), (16, 15), (18, 16)}
DARK_SPOTS = {(10, 12), (12, 13), (11, 14), (15, 15), (17, 16), (20, 18)}


def _spotted(x: int, y: int) -> bool:
    return (x, y) in DARK_SPOTS


def _pale_spot(x: int, y: int) -> bool:
    return (x, y) in PALE_SPOTS


def _band_colour(x: int, y: int) -> str:
    zone = _zone(x, y)
    u = UC[y, x]
    f = _depth(x, y)
    if zone == "rim":
        if f > 0.5:
            return FLANK[3]
        return BACK[5] if u < 9 else BACK[4] if u < 13 else BACK[3]
    if zone == "back":
        if _pale_spot(x, y):
            return BACK[4]                 # the pale char-like spots of a dark trout back
        return BACK[2] if f < 0.2 else BACK[1]
    if zone == "flank":
        if _spotted(x, y):
            return SPOT
        return FLANK[2] if u < 15 else FLANK[1]
    if zone == "stripe":
        if _spotted(x, y):
            return STRIPE[0]
        return STRIPE[3] if f < 0.52 and u < 12 else STRIPE[2] if f < 0.57 else STRIPE[1]
    if zone == "belly_edge":
        return BELLY[1] if f > 0.5 else BACK[1]
    if zone == "lower":
        return FLANK[4] if u < 14 else FLANK[3]
    return BELLY[4] if f < 0.88 and u < 14 else BELLY[3]


def _body() -> tuple[Image.Image, dict]:
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                px[x, y] = rgba(_band_colour(x, y))
                zones[(x, y)] = _zone(x, y)
    for (x, y), colour in HEAD.items():
        if isinstance(colour, tuple):
            kind, k = colour
            base = _band_colour(x, y) if MASK[y, x] else BACK[2]
            colour = mix(base, OUTLINE if kind == "dark" else "#e6ecff", k)
        px[x, y] = rgba(colour)
        zones[(x, y)] = "head"
    return img, zones


def _outline(img: Image.Image) -> Image.Image:
    """1 px outline around the silhouette: deep indigo beside the body (a touch lighter on
    the side facing the top-left light), softer where it only borders translucent fins."""
    src = img.load()
    out = img.copy()
    dst = out.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                    if 0 <= x + dx < SIZE and 0 <= y + dy < SIZE and src[x + dx, y + dy][3]]
            if not near:
                continue
            filled = {p for p in near}
            facing_light = ((x, y + 1) in filled or (x + 1, y) in filled) and \
                (x, y - 1) not in filled and (x - 1, y) not in filled
            if any(MASK[ny, nx] for nx, ny in near):
                dst[x, y] = rgba(OUTLINE_LIT if facing_light else OUTLINE)
            else:
                r, g, b, _ = rgba(FIN_OUTLINE[0])
                dst[x, y] = (r, g, b, FIN_OUTLINE[1])
    return out


def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _glint(img: Image.Image, centre: float, strength: float = 0.7, width: float = 3.5):
    """A soft glint on the back at distance `centre` along the body axis."""
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            kb = int(KB[y, x])
            if kb < 0 or kb > 2 or (x, y) in HEAD:
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / width) * strength * (1.0 - kb / 3.5)
            if k > 0:
                _blend(px, x, y, GLINT, k)


def _sparkle_spot(x: int, y: int) -> bool:
    """A diamond lattice: where the scales catch the light as the sheen passes."""
    return y % 2 == 1 and (x + y // 2) % 2 == 0


def _sheen(img: Image.Image, zones: dict, centre: float):
    """An icy blue sheen rolling across the scales at distance `centre` along the axis."""
    px = img.load()
    for (x, y), zone in zones.items():
        if zone in ("head", "rim"):
            continue
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.4)
        if k <= 0:
            continue
        spark = _sparkle_spot(x, y) and zone in ("back", "flank", "stripe", "lower", "belly")
        _blend(px, x, y, SPARK if spark else SHEEN, (0.9 if spark else 0.34) * min(1.0, k * 1.3))


def _photophores(img: Image.Image, t: float):
    """The cyan photophores pulse one after another, head to tail."""
    px = img.load()
    n = max(1, len(PHOTOPHORES))
    for i, (x, y) in enumerate(PHOTOPHORES):
        k = 0.5 - 0.5 * math.cos(2 * math.pi * (t - i / n))
        c = mix(PHOTO_DIM, PHOTO_LIT, k)
        if k > 0.8:
            c = mix(PHOTO_LIT, PHOTO_CORE, (k - 0.8) / 0.2 * 0.7)
        px[x, y] = rgba(c)


TWINKLES = [((24, 10), 0.05, 2), ((4, 20), 0.38, 2), ((30, 23), 0.7, 1)]   # (xy, phase, reach)


def _twinkles(img: Image.Image, t: float):
    for (x, y), phase, reach in TWINKLES:
        d = abs(((t - phase + 0.5) % 1.0) - 0.5)       # 0 at the peak
        amount = max(0.0, 1.0 - d / 0.2)
        sparkle(img, x, y, amount * amount * (3 - 2 * amount), colour=TWINKLE, reach=reach)


def _frame(t: float) -> Image.Image:
    """One 32x32 frame at loop phase t (0..1)."""
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))
    step = round(t * FRAMES)

    img = canvas(SIZE)
    _paint_fin(img, _tail(sway), FIN[3], 212, cov_min=0.4, lit=FIN[5])
    _paint_fin(img, _dorsal(ripple), FIN[3], 206, lit=FIN[5])
    _paint_fin(img, _anal(ripple), FIN[3], 200, lit=FIN[4])
    _paint_adipose(img)
    body, zones = _body()
    _photophores(body, t)
    if 1 <= step <= 5:
        _glint(body, 1.0 + 20.0 * (step - 1) / 4.0)
    elif step >= 7:
        _sheen(body, zones, 3.0 + 18.0 * (step - 7) / 4.0)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), FIN[4], 205)
    _paint_fin(front, _pelvic(flap), FIN[4], 205)
    img.alpha_composite(front)
    img = _outline(img)
    _twinkles(img, t)
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
