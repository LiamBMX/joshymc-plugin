"""Coral Wrasse: a rare warm-ocean fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's coral-orange / salmon-pink / golden-yellow scheme. A classic
wrasse build: a long, moderately deep body with a pointed snout and thick pink lips, a
turquoise face line under the eye, a crimson-coral back under a warm rim light, salmon
scale rows over a countershaded cream belly, one long continuous dorsal fin and a long
anal fin banded crimson / turquoise / gold, a golden pectoral fan and a broad truncate
tail with a turquoise and yellow crescent edge (fins translucent with a softer outline).

Animation (RARE, 12 frames x 3 ticks): the tail beats and the fins sway about a pixel on
offset sine phases, a warm glint slides along the back (frames 1-4), then a band of
sea-blue sheen with flashing scales rolls from head to tail (frames 6-10), while three
cyan sparkles with white cores twinkle in turn around the fish.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, rgba, save_animation, sparkle, sprite

ID = "fish_coral_wrasse"
NAME = "Coral Wrasse"
KIND = "item"
MODEL_KEY = "fish/coral_wrasse"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean plum, lights lean gold ------
OUTLINE = "#541430"
FIN_OUTLINE = ("#7c2340", 232)       # softer edge where the outline only touches a fin
BACK = ["#7a1c36", "#a0263e", "#c43a45", "#df5448", "#f0764c", "#fa9e66"]
FLANK = ["#e4634a", "#f07e5e", "#f5937a", "#f9ab8e", "#fcc4a6"]
BELLY = ["#ee9f8a", "#fcd0bb", "#ffe6d6"]
GOLD = ["#a8641c", "#dc9a2a", "#f6c23a", "#ffe07c"]
TURQ = ["#23508f", "#3b86c4", "#6cc6e6", "#b4ecf8"]
FIN = ["#7e1a3c", "#a3264a", "#c8384e", "#e05858", "#f07a64"]
PECT = ["#c9702a", "#eea23c", "#ffd05e"]
LIP = ["#b33a52", "#e0707a"]
EYE = ["#140a1e", "#ffffff"]
GLINT = "#fff4e2"
SHEEN_RAMP = ["#1f4f9e", "#2f74c4", "#4aa2e4", "#7ccdf6", "#b6ecff"]
SHEEN_SPARK = "#c8f4ff"
SPARK = "#62d6ff"
SPARK_CORE = "#ffffff"

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 22.0                      # snout to tail base
SNOUT = (2.9, 8.4)             # snout tip in the frame
SS = 4                         # supersampling per pixel side
TAIL_REST = 4.0                # the tail rests bent a little toward the back (degrees)


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


# Half-depths of the body above (TOP) and below (BOT) the axis: pointed snout, deep
# shoulders, long even flank and a thick caudal peduncle.
TOP = _smooth([(0.0, 0.5), (0.8, 0.95), (1.8, 1.5), (3.0, 2.05), (4.5, 2.6), (6.0, 3.05), (8.0, 3.4),
               (10.0, 3.55), (12.0, 3.5), (14.0, 3.25), (16.0, 2.8), (18.0, 2.2), (20.0, 1.8), (22.0, 1.65)])
BOT = _smooth([(0.0, 0.55), (0.8, 0.95), (1.8, 1.45), (3.0, 2.0), (4.5, 2.6), (6.0, 3.15), (8.0, 3.6),
               (10.0, 3.8), (12.0, 3.75), (14.0, 3.4), (16.0, 2.85), (18.0, 2.25), (20.0, 1.85), (22.0, 1.65)])

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

BODY_ADD = [(3, 10)]
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


def _scale_spot(x: int, y: int) -> bool:
    """A staggered diamond lattice for the big wrasse scales."""
    return y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1


# Palette letters for the hand-placed head details.
LETTERS = {"o": OUTLINE, "K": EYE[0], "W": EYE[1], "L": LIP[1], "l": LIP[0]}
LETTERS.update({k: c for k, c in zip("abcdef", BACK)})
LETTERS.update({k: c for k, c in zip("01234", FLANK)})
LETTERS.update({k: c for k, c in zip("pqr", BELLY)})
LETTERS.update({k: c for k, c in zip("ghij", GOLD)})
LETTERS.update({k: c for k, c in zip("stuv", TURQ)})

HEAD = {
    (3, 8): "L", (4, 8): "L",                                   # thick upper lip
    (3, 9): "o", (4, 9): "l",                                   # mouth slit
    (3, 10): "L", (4, 10): "l",                                 # thick lower lip
    (6, 8): "f", (7, 8): "f",
    (6, 9): "W", (7, 9): "K", (6, 10): "K", (7, 10): "K",       # eye with its catchlight
    (8, 9): "h", (8, 10): "g", (5, 9): "v", (5, 10): "3",
    (5, 11): "u", (6, 11): "u", (7, 12): "u", (8, 12): "t",     # turquoise face line
    (10, 10): "c", (10, 11): "c", (10, 12): "c", (9, 13): "c",  # gill cover edge
}


def _band_colour(x: int, y: int) -> tuple[str, str]:
    """Colour and zone of a body pixel from its steps in from the back and the belly."""
    kb, kv = int(KB[y, x]), int(KV[y, x])
    if kb == 0:
        return (BACK[5] if x < 12 else BACK[4]), "back"         # warm rim light
    if kv == 0:
        return BELLY[0], "belly"                                # belly edge in shadow
    if kb == 1:
        return BACK[2], "back"
    if kb == 2:
        return BACK[3], "back"
    if kv == 1:
        return BELLY[2], "belly"
    if kv == 2:
        return BELLY[1], "belly"
    if kv == 3:
        return (FLANK[4] if _scale_spot(x, y) else FLANK[3]), "flank"
    if kb == 3:
        return (FLANK[2] if _scale_spot(x, y) else FLANK[1]), "flank"
    return (FLANK[3] if _scale_spot(x, y) else FLANK[2]), "flank"


def _body():
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                colour, zone = _band_colour(x, y)
                px[x, y] = rgba(colour)
                zones[(x, y)] = zone
    for (x, y), letter in HEAD.items():
        px[x, y] = rgba(LETTERS[letter])
        zones[(x, y)] = "head"
    return img, zones


# ---- fins: local polygons (v > 0 is the back) plus a colour rule in local coordinates ----

def _fin_pixels(poly, pivot=(0.0, 0.0), angle=0.0, cov_min=0.45):
    """(x, y, u, v, cov) for every pixel of a fin; u, v are the fin's own (unrotated) coords."""
    cov = _poly(_rot(poly, pivot, angle) if angle else poly)
    a = math.radians(-angle)
    c, s = math.cos(a), math.sin(a)
    out = []
    for y in range(SIZE):
        for x in range(SIZE):
            if cov[y, x] < cov_min:
                continue
            u, v = UC[y, x] - pivot[0], VC[y, x] - pivot[1]
            out.append((x, y, pivot[0] + u * c - v * s, pivot[1] + u * s + v * c, cov[y, x]))
    return out


def _paint(img, pixels, rule):
    """Paint fin pixels with rule(u, v, edge, x, y) -> (colour, alpha); edge is True where
    the pixel borders open water (not fin, not body)."""
    own = {(x, y) for x, y, *_ in pixels if not MASK[y, x]}
    ring = {}                                   # steps out from the body (1 = touching it)
    frontier = [p for p in own if any(0 <= p[0] + dx < SIZE and 0 <= p[1] + dy < SIZE
                                      and MASK[p[1] + dy, p[0] + dx]
                                      for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
    step = 1
    while frontier:
        nxt = []
        for p in frontier:
            if p not in ring:
                ring[p] = step
        for x, y in frontier:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                q = (x + dx, y + dy)
                if q in own and q not in ring and q not in nxt:
                    nxt.append(q)
        frontier, step = nxt, step + 1
    # vertical steps from the body within each column (for the long dorsal and anal fins)
    col = {}
    for x, y in own:
        d = 0
        while 0 <= y + d < SIZE and (x, y + d) in own:
            d += 1
        up = d if 0 <= y + d < SIZE and MASK[y + d, x] else 0
        d = 0
        while 0 <= y - d < SIZE and (x, y - d) in own:
            d += 1
        down = d if 0 <= y - d < SIZE and MASK[y - d, x] else 0
        col[(x, y)] = (up, down)
    px = img.load()
    for x, y, u, v, _ in pixels:
        edge = False
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            inside = 0 <= nx < SIZE and 0 <= ny < SIZE
            if (nx, ny) not in own and not (inside and MASK[ny, nx]):
                edge = True
        colour, alpha = rule(u, v, edge, (ring.get((x, y), 9), col.get((x, y), (0, 0)), own), x, y)
        r, g, b, _ = rgba(colour)
        px[x, y] = (r, g, b, alpha)


TAIL_PIVOT = (21.8, 0.0)
TAIL = [(21.4, 1.65), (23.2, 2.5), (25.0, 3.55), (26.6, 4.45), (27.4, 3.7), (27.1, 1.8), (26.7, 0.0),
        (27.1, -1.8), (27.4, -3.7), (26.6, -4.45), (25.0, -3.55), (23.2, -2.5), (21.4, -1.65)]
TAIL_RAYS = [((22.4, 0.0), (26.6, 3.4)), ((22.4, 0.0), (26.2, 0.0)), ((22.4, 0.0), (26.6, -3.4))]


def _tail_rule(u, v, edge, ring, x, y):
    if edge and u > 25.2:
        return (GOLD[3] if v > 0 else GOLD[2]), 228               # yellow crescent margin
    if u > 25.3:
        return TURQ[2], 216                                     # turquoise crescent inside it
    for a, b in TAIL_RAYS:
        if _near(u, v, a, b, 0.42):
            return FIN[1], 226
    if edge and v > 0:
        return FIN[4], 212                                      # lit upper edge
    return FIN[3], 208


def _dorsal_poly(ripple: float):
    top = [(5.0, 1.7), (6.4, 2.7), (9.0, 3.0), (12.0, 3.1), (14.5, 3.3), (16.6, 3.6 + 0.5 * ripple),
           (18.6, 3.0 + 0.4 * ripple), (20.1, 0.6)]
    base = [(u, -0.7) for u in (19.9, 17.0, 14.0, 11.0, 8.0, 5.0)]
    return [(4.4, TOP(4.4) - 0.7)] + [(u, TOP(u) + h) for u, h in top] + [(u, TOP(u) + h) for u, h in base]


def _dorsal_rule(u, v, edge, info, x, y):
    ring, (d, _), own = info
    top = (x, y - 1) not in own
    if d == 1 and not (top and ring > 1):
        return FIN[1], 226                                      # crimson base
    if top:
        return (GOLD[3] if u < 12 else GOLD[2]), 228            # golden margin
    if d == 2:
        return TURQ[2], 218                                     # turquoise band
    return FIN[3], 208


def _anal_poly(ripple: float):
    low = [(12.0, 1.8), (13.6, 2.6), (16.0, 2.9), (17.9, 3.2 + 0.45 * ripple), (19.5, 2.2), (20.3, 0.3)]
    base = [(u, -0.7) for u in (20.2, 17.5, 14.5, 12.0)]
    return [(11.4, -BOT(11.4) + 0.7)] + [(u, -BOT(u) - h) for u, h in low] + [(u, -BOT(u) - h) for u, h in base]


def _anal_rule(u, v, edge, info, x, y):
    ring, (_, d), own = info
    bottom = (x, y + 1) not in own
    if d == 1 and not (bottom and ring > 1):
        return FIN[1], 226
    if bottom:
        return GOLD[2], 226
    if d == 2:
        return TURQ[2], 216
    return FIN[3], 206


PECT_PIVOT = (8.0, -0.9)
PECT_POLY = [(7.4, -0.4), (8.4, 0.2), (10.4, -0.6), (11.5, -1.6), (11.2, -2.6), (9.2, -2.2), (7.8, -1.5)]


def _pect_rule(u, v, edge, ring, x, y):
    if _near(u, v, PECT_PIVOT, (11.0, -1.2), 0.4):
        return PECT[2], 222                                     # the lit leading ray
    if edge:
        return PECT[0], 214
    return PECT[1], 200


def _pelvic_poly():
    return [(9.2, -BOT(9.2) + 0.5), (10.4, -BOT(10.4) + 0.4), (11.9, -BOT(11.9) - 1.4), (10.8, -BOT(10.8) - 1.6)]


def _pelvic_rule(u, v, edge, ring, x, y):
    return (GOLD[2] if edge and v < -BOT(u) - 0.6 else FIN[3]), 206


def _outline(img: Image.Image) -> Image.Image:
    """1 px outline around the silhouette: deep plum beside the body, a softer rose where
    it only borders translucent fins."""
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


# ---- animation effects ------------------------------------------------------------------

def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _glint(img, zones, step):
    """Frames 1-4: a soft warm glint slides along the back, snout to tail."""
    if not 1 <= step <= 4:
        return
    centre = 1.0 + 20.0 * (step - 1) / 3.0
    px = img.load()
    for (x, y), zone in zones.items():
        kb = int(KB[y, x])
        if kb > 3 or HEAD.get((x, y)) in ("o", "W", "K"):
            continue
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.0) * 0.85 * (1.0 - kb / 4.0)
        if k > 0:
            _blend(px, x, y, GLINT, k)


def _sheen(img, zones, step):
    """Frames 6-10: a cool blue sheen rolls over the scales, with the scale lattice
    catching the light as it passes."""
    if not 6 <= step <= 10:
        return
    centre = 2.0 + 20.0 * (step - 6) / 4.0
    px = img.load()
    for (x, y), zone in zones.items():
        if HEAD.get((x, y)) in ("o", "W", "K"):
            continue
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 2.8)
        if k <= 0:
            continue
        k = min(1.0, k * 1.6)
        r, g, b, _ = px[x, y]
        lum = (0.3 * r + 0.55 * g + 0.15 * b) / 255
        blue = SHEEN_RAMP[min(len(SHEEN_RAMP) - 1, int(lum * len(SHEEN_RAMP)))]
        if zone in ("flank", "back") and _scale_spot(x, y + 1):
            _blend(px, x, y, SHEEN_SPARK, 0.95 * k)             # scales flash electric blue
        elif zone in ("flank", "back"):
            _blend(px, x, y, blue, 0.88 * k)                    # the flank turns sea-blue
        else:
            _blend(px, x, y, blue, 0.5 * k)


SPARKLES = [((5, 3), 0.0, 2), ((26, 10), 1 / 3, 2), ((6, 20), 2 / 3, 1)]


def _pulse(t: float, offset: float, span: float = 0.2) -> float:
    d = abs((t - offset + 0.5) % 1.0 - 0.5)
    return max(0.0, 1.0 - d / span)


def _frame(t: float) -> Image.Image:
    """One 32x32 frame at loop phase t (0..1)."""
    step = round(t * FRAMES)
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    # Medial fins sit behind the body; the near pectoral and pelvic fins lie over it.
    a = TAIL_REST + 7.0 * sway
    _paint(img, _fin_pixels(TAIL, TAIL_PIVOT, a, 0.4), _tail_rule)
    _paint(img, _fin_pixels(_dorsal_poly(ripple)), _dorsal_rule)
    _paint(img, _fin_pixels(_anal_poly(ripple)), _anal_rule)
    body, zones = _body()
    _glint(body, zones, step)
    _sheen(body, zones, step)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint(front, _fin_pixels(_pelvic_poly(), (10.0, -BOT(10.0)), 9.0 * flap), _pelvic_rule)
    _paint(front, _fin_pixels(PECT_POLY, PECT_PIVOT, 12.0 * flap), _pect_rule)
    img.alpha_composite(front)
    img = _outline(img)
    for (x, y), offset, reach in SPARKLES:
        amount = _pulse(t, offset)
        sparkle(img, x, y, amount, SPARK, reach)                # cyan arms
        if amount > 0.3:
            sparkle(img, x, y, amount * 0.6, SPARK_CORE, 0)     # white-hot core
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
