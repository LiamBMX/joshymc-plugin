"""Celestial Marlin: a legendary billfish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's pale gold / cream / ivory scheme. A true marlin: a long spear bill
over a short pale lower jaw, the humped nape rising into the tall raked sickle of the first
dorsal fin, a deep torpedo body on a thin tail stalk and a big rigid crescent tail.
Deep copper back under a bright rim light, a gleaming gold flank crossed by the marlin's
vertical bars with a belt of three stars along the back, a countershaded ivory
belly, a gill cover and mouth line, a long scythe pectoral and a pointed anal fin; fins
translucent amber-gold with painted rays.

Animation (LEGENDARY, 20 frames x 2 ticks): the crescent tail sweeps, the dorsal tip ripples
and the pectoral flaps on offset sine phases; a soft glint slides along the back, then a
band of sparkling scales rolls down the body, while an iridescent cyan / violet / pink sheen
sweeps across the scales twice per loop. A golden rim glow breathes just outside the
outline with a brighter arc circling it, three star motes orbit the fish (passing behind
and in front of it), the belt stars twinkle into tiny crosses and the eye radiates a
pulsing halo with a flaring catchlight.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_celestial_marlin"
NAME = "Celestial Marlin"
KIND = "item"
MODEL_KEY = "fish/celestial_marlin"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 20
FRAMETIME = 2

# ---- palettes (darkest -> lightest): the old sprite's pale golds, hue-shifted ------------
OUTLINE = "#3b1c33"                 # deep plum: the gold's shadow pushed toward violet
OUTLINE_LIT = "#55293f"             # a touch lighter on the edges facing the light
FIN_OUTLINE = ("#4e2438", 236)      # softer edge where the outline only touches a fin
BACK = ["#5a2e3a", "#7e3f37", "#a45a33", "#c77b35", "#e3a043", "#f4c865", "#ffe8a0"]
GOLD = ["#b8793a", "#e2ae4a", "#f0c95f", "#f9de84", "#fff0b4", "#fffae0"]
BELLY = ["#d9b98a", "#eed9ae", "#faeccb", "#fff8e6", "#ffffff"]
FIN = ["#6b2f3f", "#8f4145", "#b45a44", "#d27e3e", "#eba24b", "#fbd07a"]
STAR = "#ffffff"
EYE = ["#1d1233", "#ffffff"]
GLINT = "#fffbe6"
SHIMMER = "#fff2b8"
SPARK = "#fffef4"
IRI = ["#7fe3ff", "#b48cff", "#ff9bd6"]   # the iridescent sheen: cyan, violet, pink
RIM = "#ffdd55"
MOTE = "#fff8d8"

# ---- the body, hand-drawn -------------------------------------------------------------------
# Row -> (first x, last x) of the body; the bill is a clean one-pixel staircase in front.
BODY_ROWS = {
    8: (10, 12), 9: (8, 14), 10: (7, 15), 11: (7, 16), 12: (7, 17), 13: (8, 18), 14: (8, 19),
    15: (9, 20), 16: (10, 21), 17: (11, 22), 18: (12, 23), 19: (15, 23),
}
BILL = [(2, 8), (3, 8), (4, 9), (5, 9), (6, 10)]

# Local body axis (for glints, bars and sheens): u from the bill tip toward the tail.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SNOUT = (2.5, 8.5)
SL = 23.4                                   # bill tip to the tail stalk, along the axis

_py, _px = np.mgrid[0:SIZE, 0:SIZE]
UC = (_px + 0.5 - SNOUT[0]) * AX[0] + (_py + 0.5 - SNOUT[1]) * AX[1]
VC = (_px + 0.5 - SNOUT[0]) * UP[0] + (_py + 0.5 - SNOUT[1]) * UP[1]


def _mask() -> np.ndarray:
    mask = np.zeros((SIZE, SIZE), bool)
    for y, (x0, x1) in BODY_ROWS.items():
        mask[y, x0:x1 + 1] = True
    for x, y in BILL:
        mask[y, x] = True
    return mask


MASK = _mask()
TRUNK = MASK.copy()
for _x, _y in BILL:
    TRUNK[_y, _x] = False
COL_TOP = {x: int(np.nonzero(TRUNK[:, x])[0][0]) for x in range(SIZE) if TRUNK[:, x].any()}
COL_BOT = {x: int(np.nonzero(TRUNK[:, x])[0][-1]) for x in range(SIZE) if TRUNK[:, x].any()}

# Head details: the 2x2 eye (catchlight top-left), mouth line over the short pale lower
# jaw, the gill cover's dark edge with a lit rim behind it.
EYE_AT = (9, 10)
HEAD = {
    (7, 11): "m", (8, 12): "m", (7, 12): "j", (8, 13): "j", (9, 12): "j",
    (12, 10): "k", (12, 11): "k", (12, 12): "k", (11, 13): "k", (11, 14): "k",
    (13, 11): "h", (13, 12): "h", (12, 13): "h",
}
# The marlin's bars: lines across the body (constant u), shown on the back and upper flank.
BARS = (15.0, 17.3, 19.6)


def _frac(x: int, y: int) -> float:
    """0 at the top (back) of this column of the body, 1 at its bottom (belly)."""
    top, bot = COL_TOP[x], COL_BOT[x]
    return (y - top) / max(1, bot - top)


def _bar(x: int, y: int) -> int:
    f = _frac(x, y)
    if not 0.12 < f < 0.66:
        return -1
    for i, bu in enumerate(BARS):
        if abs(UC[y, x] - bu) < 0.5:
            return i
    return -1


# Stars on the bars: three in a row along the back (a belt of stars) and two fainter ones
# lower on the flank.
STARS = [(16, 13), (18, 15), (20, 17)]
SMALL_STARS = [(14, 15), (17, 17)]


def _band(x: int, y: int):
    """(colour, zone) of a body pixel from its place in the column (clean staircases that
    follow the back and belly contours)."""
    if (x, y) in BILL:
        return (BACK[6] if BILL.index((x, y)) % 2 == 0 else BACK[5]), "bill"
    if (x, y) in HEAD:
        letter = HEAD[(x, y)]
        return {"m": BACK[1], "j": BELLY[2], "k": GOLD[1], "h": GOLD[4]}[letter], "head"
    f = _frac(x, y)
    top, bot = COL_TOP[x], COL_BOT[x]
    right_edge = x == BODY_ROWS[y][1]
    if y == top or (right_edge and f < 0.5):
        return (BACK[6] if x < 12 else BACK[5] if x < 18 else BACK[4]), "rim"
    if (x, y) in STARS:
        return STAR, "star"
    if (x, y) in SMALL_STARS:
        return GOLD[5], "star"
    bar = _bar(x, y) >= 0
    if y == bot or (right_edge and f >= 0.5):
        return BELLY[1], "belly"
    crown = 1 if x <= 12 else 0          # the head's crown catches more of the top-left light
    if f < 0.26:
        return BACK[2 + crown + bar], ("bar" if bar else "back")
    if f < 0.38:
        return BACK[3 + crown + bar], ("bar" if bar else "back")
    if bar:
        return (GOLD[4] if f < 0.48 else GOLD[3]), "bar"
    if f < 0.47:
        return GOLD[4], "horizon"                                        # bright lateral line
    if f < 0.58:
        return GOLD[2], "flank"
    if f < 0.68:
        return GOLD[1], "flank"
    if f < 0.84:
        return BELLY[2], "belly"
    return BELLY[3], "belly"


def _body():
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                colour, zone = _band(x, y)
                px[x, y] = rgba(colour)
                zones[(x, y)] = zone
    ex, ey = EYE_AT
    for (dx, dy), c in {(0, 0): EYE[1], (1, 0): EYE[0], (0, 1): EYE[0], (1, 1): EYE[0]}.items():
        px[ex + dx, ey + dy] = rgba(c)
        zones[(ex + dx, ey + dy)] = "eye"
    return img, zones


# ---- fins -----------------------------------------------------------------------------------
# Pixel-art fins: L lit leading edge, D membrane, R ray, E dark trailing edge.
FIN_PALETTE = {"L": (FIN[5], 228), "D": (FIN[4], 206), "R": (FIN[2], 228), "E": (FIN[3], 214),
               "d": (FIN[4], 206)}

# The tall sickle first dorsal: rakes back from the humped nape to a tip over the mid-body.
DORSAL = [
    ".......L",  # 3
    "......LD",  # 4
    ".....LRD",  # 5
    "....LRDE",  # 6
    "..LLRDDE",  # 7
    "....RDDDE",  # 8
    "......DDE",  # 9
]
DORSAL_AT = (9, 3)


def _dorsal(ripple: float) -> dict:
    """Dorsal pixels (x, y) -> letter; the top rows lean with the ripple (tip leading)."""
    out = {}
    for j, row in enumerate(DORSAL):
        lean = round(ripple * (1.0 if j <= 1 else 0.55 if j <= 3 else 0.0))
        for i, ch in enumerate(row):
            if ch != ".":
                out.setdefault((DORSAL_AT[0] + i + lean, DORSAL_AT[1] + j), ch)
    return out


# The long scythe pectoral lies over the lower flank and pokes out below the belly.
PECTORAL = {
    "spread": {(11, 15): "L", (12, 15): "D", (12, 16): "L", (13, 16): "D", (13, 17): "L", (14, 17): "R",
               (14, 18): "L", (15, 18): "R", (15, 19): "D", (16, 19): "R", (14, 19): "d", (15, 20): "d",
               (16, 20): "E"},
    "folded": {(11, 15): "L", (12, 15): "D", (12, 16): "L", (13, 16): "D", (13, 17): "L", (14, 17): "R",
               (14, 18): "D", (15, 18): "R", (15, 19): "L", (16, 19): "R", (16, 20): "d", (17, 20): "E"},
}
# The pointed first anal fin (its tip trails with the ripple) and the tiny second one.
ANAL = {(19, 20): "L", (20, 20): "D", (21, 20): "R", (20, 21): "L", (21, 21): "E"}
ANAL_TIP = [(21, 22), (22, 22)]


def _paint_pixels(img, pixels: dict, zones=None, zone="fin", over_body=False):
    """Paint pixel-art fin letters. Off the body they are translucent fin; with over_body
    they tint the body beneath (the near pectoral and pelvic fins)."""
    px = img.load()
    for (x, y), ch in pixels.items():
        if not (0 <= x < SIZE and 0 <= y < SIZE):
            continue
        colour, alpha = FIN_PALETTE[ch]
        if MASK[y, x]:
            if not over_body or zones is None or zones.get((x, y)) in ("eye",):
                continue
            base = px[x, y]
            k = {"L": 0.4, "D": 0.5, "R": 0.62, "E": 0.62, "d": 0.4}[ch]
            px[x, y] = rgba(mix(base, colour, k))
            zones[(x, y)] = "pect"
            continue
        r, g, b_, _ = rgba(colour)
        px[x, y] = (r, g, b_, alpha)
        if zones is not None:
            zones[(x, y)] = zone


# The rigid crescent tail as a polygon in frame pixels, swept around its root.
TAIL_PIVOT = (23.2, 18.5)
TAIL = [(22.9, 16.5), (24.3, 15.1), (25.8, 13.6), (27.3, 12.1), (28.9, 10.9), (28.6, 12.6), (27.8, 14.3),
        (26.9, 15.9), (26.0, 17.3), (25.3, 18.7), (25.6, 20.0), (25.9, 21.8), (26.1, 23.6), (26.1, 25.3),
        (25.7, 26.9), (25.0, 25.3), (24.5, 23.6), (24.0, 21.9), (23.4, 20.3)]
TAIL_RAYS = [((24.0, 17.4), (28.0, 12.2)), ((24.2, 19.6), (25.4, 25.4))]
TAIL_REST = 0.0
SS = 4
_sy, _sx = np.mgrid[0:SIZE * SS, 0:SIZE * SS]
XS, YS = (_sx + 0.5) / SS, (_sy + 0.5) / SS


def _rot(points, pivot, degrees):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    px_, py_ = pivot
    return [(px_ + (x - px_) * c - (y - py_) * s, py_ + (x - px_) * s + (y - py_) * c) for x, y in points]


def _poly(poly) -> np.ndarray:
    """Per-pixel coverage of a polygon in frame pixels (even-odd rule, supersampled)."""
    inside = np.zeros(XS.shape, bool)
    n = len(poly)
    for i in range(n):
        x0, y0 = poly[i]
        x1, y1 = poly[(i + 1) % n]
        if y0 == y1:
            continue
        cond = (y0 > YS) != (y1 > YS)
        cross = x0 + (YS - y0) * (x1 - x0) / (y1 - y0)
        inside ^= cond & (XS < cross)
    return inside.reshape(SIZE, SS, SIZE, SS).mean(axis=(1, 3))


def _near(x, y, a, b, reach):
    ax_, ay_ = a
    bx_, by_ = b
    dx, dy = bx_ - ax_, by_ - ay_
    k = max(0.0, min(1.0, ((x - ax_) * dx + (y - ay_) * dy) / (dx * dx + dy * dy)))
    return math.hypot(x - ax_ - k * dx, y - ay_ - k * dy) <= reach


def _tail(img, sway: float, zones: dict):
    """Paint the crescent tail swept by `sway` (-1..1): membrane, rays, lit outer edge."""
    angle = TAIL_REST + 7.0 * sway
    poly = _rot(TAIL, TAIL_PIVOT, angle)
    rays = [tuple(_rot(r, TAIL_PIVOT, angle)) for r in TAIL_RAYS]
    cov = _poly(poly)
    inside = (cov >= 0.42) & ~MASK
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if not inside[y, x]:
                continue
            cx, cy = x + 0.5, y + 0.5
            colour, alpha = FIN[4], 210
            if any(_near(cx, cy, a, b, 0.5) for a, b in rays) and cov[y, x] > 0.85:
                colour, alpha = FIN[2], 228
            elif (not inside[y - 1, x] and not MASK[y - 1, x]) or (not inside[y, x - 1] and not MASK[y, x - 1]):
                colour, alpha = FIN[5], 226                      # lit upper / left edge
            elif not inside[y + 1, x] or not inside[y, x + 1]:
                colour = FIN[3]
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, alpha)
            zones[(x, y)] = "tail"


# ---- outline and glow -----------------------------------------------------------------------

def _outline(img: Image.Image) -> Image.Image:
    """1 px outline around the silhouette: deep plum beside the body (a touch lighter on the
    edges facing the light), a softer translucent plum where it only borders fins."""
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
                lit = ((y + 1 < SIZE and src[x, y + 1][3]) or (x + 1 < SIZE and src[x + 1, y][3])) and \
                    not (y > 0 and src[x, y - 1][3]) and not (x > 0 and src[x - 1, y][3])
                dst[x, y] = rgba(OUTLINE_LIT if lit else OUTLINE)
            else:
                dst[x, y] = (fr, fg, fb, FIN_OUTLINE[1])
    return out


def _blend(px, x, y, colour, k):
    if k <= 0:
        return
    c = rgba(colour)
    r, g, b, a = px[x, y]
    k = min(1.0, k)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _screen(px, x, y, colour, k):
    """Screen-blend colour over the pixel by k (brightens, never muddies)."""
    if k <= 0:
        return
    c = rgba(colour)
    r, g, b, a = px[x, y]
    scr = [255 - (255 - v) * (255 - cv) / 255 for v, cv in ((r, c[0]), (g, c[1]), (b, c[2]))]
    k = min(1.0, k)
    px[x, y] = (round(r + (scr[0] - r) * k), round(g + (scr[1] - g) * k), round(b + (scr[2] - b) * k), a)


def _iri(p: float) -> str:
    """Cyclic cyan -> violet -> pink -> cyan colour at phase p."""
    p = (p % 1.0) * 3
    i = int(p)
    return mix(IRI[i % 3], IRI[(i + 1) % 3], p - i)


SCALES = ("back", "horizon", "flank", "bar", "belly")


def _effects(img, zones, t: float):
    """Iridescent drift, back glint, scale shimmer and twinkling stars over the body."""
    px = img.load()
    centre = -5.0 + 38.0 * ((t * 2) % 1.0)          # two passes of the sheen per loop
    for (x, y), zone in zones.items():
        if zone in SCALES or zone in ("rim", "tail", "fin", "pect", "star"):
            # the iridescent sheen: a band that sweeps tailward, its colour drifting from
            # cyan through violet to pink; screen-blended so it brightens like a pearl
            u = UC[y, x] - 0.35 * VC[y, x]
            band = max(0.0, 1.0 - abs(u - centre) / 4.5) ** 1.4
            k = 0.06 + 0.5 * band
            if zone == "belly":
                k *= 0.5
            _screen(px, x, y, _iri(t + u * 0.02), k)
    # a soft glint along the back, frames 0-5
    if t < 0.3:
        centre = 3.0 + (SL + 1.0) * (t / 0.3)
        for (x, y), zone in zones.items():
            if zone not in ("rim", "back", "bill", "bar", "star") or x not in COL_TOP:
                continue
            depth = y - COL_TOP[x] if (x, y) not in BILL else 0
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.0) * 0.7 * (1.0 - depth / 4.5)
            _blend(px, x, y, GLINT, k)
    # a band of sparkling scales rolls down the body, frames 7-14
    elif 0.35 <= t < 0.75:
        centre = 8.0 + 16.0 * (t - 0.35) / 0.4
        for (x, y), zone in zones.items():
            if zone not in SCALES and zone != "pect":
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.0)
            if k <= 0:
                continue
            spot = y % 2 == 1 and (x + y // 2) % 2 == 0
            _blend(px, x, y, SPARK if spot else SHIMMER, (0.9 if spot else 0.34) * min(1.0, k * 1.3))
    # the stars twinkle on staggered phases, the belt stars flaring into tiny crosses
    for i, (x, y) in enumerate(STARS + SMALL_STARS):
        tw = wave(t * 2, i * 0.29)
        if i < len(STARS):
            _blend(px, x, y, GOLD[4], 0.5 * (1 - tw))
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if zones.get((x + dx, y + dy)) in SCALES:
                    _blend(px, x + dx, y + dy, SPARK, 0.55 * max(0.0, tw - 0.35) / 0.65)
        else:
            _blend(px, x, y, STAR, 0.8 * tw)


def _eye_glow(img, t: float):
    """The radiant eye: a pulsing golden halo around the pupil and a violet glint in it."""
    px = img.load()
    ex, ey = EYE_AT
    pulse = wave(t * 2)
    for dx, dy in ((-1, 0), (2, 0), (-1, 1), (2, 1), (0, -1), (1, -1), (0, 2), (1, 2)):
        x, y = ex + dx, ey + dy
        if px[x, y][3] and MASK[y, x]:
            _blend(px, x, y, "#fff6c0", 0.2 + 0.55 * pulse)
    _blend(px, ex + 1, ey + 1, "#6a4ac8", 0.75 * pulse)
    # at the peak the catchlight flares: short rays up and to the left of it
    flare = max(0.0, pulse - 0.45) / 0.55
    for x, y in ((ex - 1, ey), (ex, ey - 1)):
        _blend(px, x, y, "#ffffff", 0.85 * flare)


def _rim_glow(img, t: float) -> Image.Image:
    """A golden 1 px glow just outside the outline, breathing, with a brighter arc
    travelling around the fish."""
    src = img.load()
    out = canvas(SIZE)
    dst = out.load()
    breath = wave(t)
    c = rgba(RIM)
    cx, cy = 16.0, 15.5
    for y in range(1, SIZE - 1):
        for x in range(1, SIZE - 1):
            if src[x, y][3]:
                continue
            if not any(src[x + dx, y + dy][3] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                continue
            ang = math.atan2(y + 0.5 - cy, x + 0.5 - cx) / (2 * math.pi)
            travel = max(0.0, math.cos(2 * math.pi * (ang - t))) ** 4
            a = 18 + 70 * breath + 120 * travel
            dst[x, y] = (c[0], c[1], c[2], round(min(200, a)))
    return out


ORBIT_C = (16.0, 16.0)
ORBIT_R = (14.0, 7.0)


def _motes(t: float):
    """Three star motes on a tilted orbit round the fish; (behind, in front) layers."""
    back, front = canvas(SIZE), canvas(SIZE)
    for k in range(3):
        a = 2 * math.pi * (t + k / 3)
        ca, sa = math.cos(a), math.sin(a)
        x = ORBIT_C[0] + ORBIT_R[0] * ca * AX[0] + ORBIT_R[1] * sa * UP[0]
        y = ORBIT_C[1] + ORBIT_R[0] * ca * AX[1] + ORBIT_R[1] * sa * UP[1]
        amount = 0.35 + 0.65 * wave(t * 2, k / 3)
        sparkle(front if sa < 0 else back, int(math.floor(x)), int(math.floor(y)), amount, colour=MOTE, reach=2)
    for layer in (back, front):
        px = layer.load()
        for i in range(SIZE):
            for p in ((i, 0), (i, SIZE - 1), (0, i), (SIZE - 1, i)):
                px[p] = (0, 0, 0, 0)
    return back, front


# ---- frames ---------------------------------------------------------------------------------

def _frame(t: float) -> Image.Image:
    """One 32x32 frame at loop phase t (0..1)."""
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    zones: dict = {}
    # Medial fins sit behind the body; the near pectoral and pelvic fins lie over it.
    _tail(img, sway, zones)
    _paint_pixels(img, _dorsal(ripple), zones)
    anal = dict(ANAL)
    anal[ANAL_TIP[0] if ripple < 0.3 else ANAL_TIP[1]] = "E"
    _paint_pixels(img, anal, zones)
    body, bz = _body()
    img.alpha_composite(body)
    zones.update(bz)
    _paint_pixels(img, PECTORAL["spread" if flap > -0.2 else "folded"], zones, zone="fin", over_body=True)
    _effects(img, zones, t)
    _eye_glow(img, t)
    img = _outline(img)
    behind, front = _motes(t)
    out = canvas(SIZE)
    out.alpha_composite(_rim_glow(img, t))
    out.alpha_composite(behind)
    out.alpha_composite(img)
    out.alpha_composite(front)
    return out


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
