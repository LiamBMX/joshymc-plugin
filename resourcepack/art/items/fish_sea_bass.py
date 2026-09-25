"""Sea Bass: an uncommon ocean fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's slate-blue / teal / mauve-grey scheme. A European sea bass: a
long, solid, moderately deep body; a dark teal-slate back under a lit rim; mauve-silver
flanks carrying a staggered scale lattice and the bold dark lateral line; a countershaded
pearl belly; a big head with a wide terminal mouth whose jaw runs back under the silver-
ringed eye, and the sea bass's trademark dark blotch on the rear edge of the gill cover.
Two separate dorsal fins (a spiky first fin of stiff spines, then a soft rounded one), a
spined anal fin, low pectoral and pelvic fins and a broad, shallow-forked tail, all
translucent slate with painted rays.

Animation (UNCOMMON, 12 frames x 3 ticks): the tail wags once per loop with its tips
leading, the dorsal fins ripple and the pectoral and pelvic fins sway a pixel; a soft glint
slides along the back (frames 1-5), then a shimmer band of sparkling scales rolls across
the body from gill to tail (frames 7-11).
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sprite

ID = "fish_sea_bass"
NAME = "Sea Bass"
KIND = "item"
MODEL_KEY = "fish/sea_bass"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean indigo, lights lean aqua/cream
OUTLINE = "#172230"
FIN_OUTLINE = ("#2f3d52", 232)     # softer edge where the outline only touches a fin
BACK = ["#1f2d40", "#2b4155", "#3a5669", "#4d6c7b", "#668892", "#8fb0b0", "#bdd7cf"]
SILVER = ["#4f5068", "#686782", "#84819b", "#a19fb5", "#bebdcd", "#dcdbe5", "#f4f3f8"]
MAUVE = ["#8a7f98", "#a49aae"]     # the old sprite's warm mauve-grey sheen
LINE = ["#2e394d", "#414c63"]      # lateral line (dark dashes / their lighter joins)
FIN = ["#2e3f55", "#44586e", "#5e7488", "#7b93a3", "#9fb4bf", "#c9d9db"]
SPOT = ["#1d2434", "#2d3548"]      # the gill-cover blotch
EYE = ["#0c111c", "#ffffff", "#c8d4d6", "#8f9cab"]
GLINT = "#f2fdff"
SHIMMER = "#e2f4f2"
SPARK = "#fbfffd"

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(27.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 22.8                      # snout to tail base
SNOUT = (1.6, 10.2)             # snout tip in the frame
SS = 4                         # supersampling per pixel side
SHIFT = (0, -1)                # the art is drawn 1 px below centre
TAIL_REST = 2.0                # the tail rests bent a little toward the back (degrees)


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


# Half-depths of the body above (TOP) and below (BOT) the axis: a big blunt head, a solid
# deep mid-body and a thick caudal peduncle.
TOP = _smooth([(0.0, 0.5), (1.0, 1.5), (2.2, 2.45), (3.6, 3.2), (5.2, 3.7), (7.0, 3.95), (8.9, 4.05),
               (10.8, 3.95), (12.8, 3.65), (14.8, 3.15), (16.8, 2.5), (18.8, 1.85), (20.6, 1.5), (22.0, 1.4),
               (22.8, 1.4)])
BOT = _smooth([(0.0, 0.5), (1.0, 1.05), (2.2, 1.7), (3.6, 2.35), (5.2, 2.9), (7.0, 3.35), (8.9, 3.65),
               (10.8, 3.7), (12.8, 3.55), (14.8, 3.1), (16.8, 2.45), (18.8, 1.85), (20.6, 1.5), (22.0, 1.4),
               (22.8, 1.4)])

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

TAIL_PIVOT = (21.8, 0.0)


def _tail(sway: float):
    pts = [(20.6, 1.4), (22.4, 2.3), (24.4, 3.6), (26.6, 4.8), (26.6, 3.6), (25.7, 2.1), (25.0, 0.9), (24.7, 0.0),
           (25.0, -0.9), (25.7, -2.1), (26.6, -3.6), (26.6, -4.8), (24.4, -3.6), (22.4, -2.3), (20.6, -1.4)]
    rays = [((21.8, 0.9), (26.1, 4.1), FIN[1]), ((21.8, -0.9), (26.1, -4.1), FIN[1]),
            ((22.0, 0.0), (24.0, 0.0), FIN[1])]
    a = TAIL_REST + 6.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _spiny_dorsal(ripple: float):
    """The first dorsal: stiff spines, tallest in the middle, the membrane dipping between."""
    r = 0.35 * ripple
    s1 = (7.5 + r, TOP(7.5) + 3.4)
    s2 = (8.9 + r, TOP(8.9) + 4.2)
    s3 = (10.3 + r, TOP(10.3) + 3.6)
    s4 = (11.6 + r, TOP(11.6) + 2.4)
    pts = [(6.6, TOP(6.6) - 0.6), s1, (8.2 + r, TOP(8.2) + 2.6), s2, (9.7 + r, TOP(9.7) + 2.6), s3,
           (11.0 + r, TOP(11.0) + 2.0), s4, (12.3 + r, TOP(12.3) + 0.6), (12.3, TOP(12.3) - 0.6)]
    rays = [((7.2, TOP(7.2)), s1, FIN[5]),                    # lit leading spine
            ((8.7, TOP(8.7)), s2, FIN[1]),
            ((10.1, TOP(10.1)), s3, FIN[1]),
            ((11.4, TOP(11.4)), s4, FIN[1])]
    return pts, rays


def _soft_dorsal(ripple: float):
    """The second dorsal: soft rays, a lower rounded lobe behind a small gap."""
    r = 0.3 * ripple
    pts = [(13.2, TOP(13.2) - 0.6), (13.6 + r, TOP(13.6) + 2.6), (15.0 + r, TOP(15.0) + 2.4),
           (16.8 + r, TOP(16.8) + 1.4), (17.8, TOP(17.8) + 0.3), (17.8, TOP(17.8) - 0.6)]
    rays = [((13.8, TOP(13.8)), (14.0 + r, TOP(14.0) + 2.4), FIN[1]),
            ((15.6, TOP(15.6)), (15.8 + r, TOP(15.8) + 2.0), FIN[1])]
    return pts, rays


def _pectoral(flap: float):
    pivot = (7.8, -0.5)
    pts = [(7.3, 0.2), (8.5, 0.3), (12.0, -1.0), (11.9, -2.3), (10.6, -2.5), (7.9, -1.4)]
    rays = [((8.2, -0.1), (11.8, -1.3), FIN[4]), ((8.2, -0.9), (11.0, -2.3), FIN[1])]
    a = 9.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _pelvic(flap: float):
    pivot = (8.6, -BOT(8.6))
    pts = [(8.0, -BOT(8.0) + 0.5), (9.4, -BOT(9.4) + 0.4), (11.3, -BOT(11.3) - 1.7),
           (10.0, -BOT(10.0) - 1.9)]
    rays = [((8.7, -BOT(8.7)), (10.4, -BOT(10.4) - 1.6), FIN[4])]
    a = 9.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _anal(ripple: float):
    r = 0.3 * ripple
    pts = [(14.2, -BOT(14.2) + 0.5), (14.8 + r, -BOT(14.8) - 2.1), (16.9 + r, -BOT(16.9) - 1.7),
           (18.6, -BOT(18.6) - 0.6), (18.8, -BOT(18.8) + 0.5)]
    rays = [((14.6, -BOT(14.6)), (14.9 + r, -BOT(14.9) - 2.0), FIN[4]),
            ((16.4, -BOT(16.4)), (16.6 + r, -BOT(16.6) - 1.5), FIN[1])]
    return pts, rays


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=230, lit=None):
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
                if cov[y, x] >= 0.85 and _near(u, v, ra, rb, 0.45):
                    colour, a = rc, ray_alpha
                    break
            if lit and colour == membrane and inside[y, x] and (
                    (y > 0 and not inside[y - 1, x] and not MASK[y - 1, x])
                    or (x > 0 and not inside[y, x - 1] and not MASK[y, x - 1])):
                colour = lit
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)


# ---- body -------------------------------------------------------------------------------
# The silhouette is the procedural lens above with a few hand edits; its colours come from
# discrete bands counted in from the dorsal and ventral contours (clean staircases that
# follow the outline), then hand-placed head details on top.

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

# Palette letters for the hand-placed head details.
LETTERS = {"o": OUTLINE, "K": EYE[0], "W": EYE[1], "I": EYE[2], "i": EYE[3],
           "S": SPOT[0], "s": SPOT[1], "L": LINE[0], "l": LINE[1]}
LETTERS.update({k: c for k, c in zip("abcdefg", BACK)})
LETTERS.update({k: c for k, c in zip("0123456", SILVER)})
LETTERS.update({k: c for k, c in zip("mn", MAUVE)})

# Head details: (x, y) -> letter. The 2x2 eye with its catchlight and silver ring, the wide
# mouth running back under the eye above a pale lower jaw, the gill-cover edge and its dark
# blotch at the top.
HEAD_ROWS = {
    10: (5, "Ii"),
    11: (2, "o.IWKi.S"),
    12: (3, "o.KKi.S"),
    13: (3, "5L...l"),
    14: (7, "l"),
    15: (6, "l"),
}
HEAD_OFF = {(x0 + i, y): ch for y, (x0, row) in HEAD_ROWS.items() for i, ch in enumerate(row) if ch != "."}
HEAD = HEAD_OFF
HEAD_ZONES = ("eye", "spot")   # head letters the glint and shimmer leave alone


def _band(x: int, y: int) -> tuple[str, str]:
    """(colour, zone) of a body pixel from its band position."""
    kb, kv = int(KB[y, x]), int(KV[y, x])
    h = kb + kv + 1
    u = UC[y, x]
    if kb == 0:
        return (BACK[6] if 3.5 < u < 8.5 else BACK[5] if u < 16 else BACK[4]), "back"   # rim light
    if kb == 1:
        return BACK[3] if u < 5 else BACK[2], "back"                           # teal-slate back
    if u < 6.8 and kv >= 1:                                         # silver cheek below the crown
        if kb == 2:
            return BACK[4], "head"
        return (SILVER[5] if kv >= 3 else SILVER[4]), "head"
    if kb == 2 and h > 5:
        return BACK[1] if u < 16 else BACK[2], "back"               # darkest just above the flank
    if kb == 3 and h > 7:
        return BACK[4], "flank"                                     # steel upper flank
    line_k = 4 if h > 8 else 3
    if kb == line_k and kv >= 2 and u < SL - 0.3:                   # the bold lateral line
        return (LINE[0] if (x + y) % 3 else LINE[1]), "line"
    if kv == 0:
        return SILVER[3], "belly"                                   # belly edge in shade
    if kv == 1:
        return SILVER[6], "belly"                                   # pearl belly
    if kv == 2:
        return SILVER[5], "flank"
    if kb == line_k + 1:                                            # mauve sheen under the line
        return (MAUVE[1] if (x + 2 * y) % 4 == 0 else SILVER[4]), "flank"
    # flank scale lattice: staggered darker marks on silver
    if y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1:
        return MAUVE[0], "flank"
    return SILVER[4], "flank"


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
    for (x, y), letter in HEAD.items():
        px[x, y] = rgba(LETTERS[letter])
        zones[(x, y)] = "eye" if letter in "KWIi" else "spot" if letter in "Ss" else "head"
    return img, zones


def _outline(img: Image.Image) -> Image.Image:
    """1 px outline around the silhouette: dark slate beside the body, a softer dusky
    slate where it only borders translucent fins."""
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


def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _glint(px, zones, centre: float, strength: float = 0.62, width: float = 3.0):
    """A soft glint on the back at distance `centre` along the axis."""
    for (x, y), zone in zones.items():
        kb = int(KB[y, x])
        if zone in HEAD_ZONES or kb < 0 or kb > 3:
            continue
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / width) * strength * (1.0 - kb / 4.5)
        if k > 0:
            _blend(px, x, y, GLINT, k)


def _sparkle_spot(x: int, y: int) -> bool:
    """A denser diamond lattice: where the scales catch the light as the shimmer passes."""
    return y % 2 == 1 and (x + y // 2) % 2 == 0


def _shimmer(px, zones, centre: float, half: float = 3.0):
    """A band of shimmering scales across the whole flank at distance `centre`."""
    for (x, y), zone in zones.items():
        if zone in HEAD_ZONES or zone == "head":
            continue
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / half)
        if k <= 0:
            continue
        spark = _sparkle_spot(x, y) and zone in ("flank", "back", "belly")
        strength = (0.95 if spark else 0.44) * min(1.0, k * 1.3)
        if zone == "line" and not spark:
            strength *= 0.5
        _blend(px, x, y, SPARK if spark else SHIMMER, strength)


def _frame(t: float) -> Image.Image:
    """One 32x32 frame at loop phase t (0..1)."""
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))
    step = round(t * FRAMES)

    img = canvas(SIZE)
    # Medial fins sit behind the body; the near pectoral and pelvic fins lie over it.
    _paint_fin(img, _tail(sway), FIN[2], 214, cov_min=0.4, lit=FIN[4])
    _paint_fin(img, _spiny_dorsal(ripple), FIN[4], 176, ray_alpha=238)
    _paint_fin(img, _soft_dorsal(ripple), FIN[3], 200, lit=FIN[4])
    _paint_fin(img, _anal(ripple), FIN[3], 200)
    body, zones = _body()
    bpx = body.load()
    if 1 <= step <= 5:
        _glint(bpx, zones, 2.0 + (SL - 1.0) * (step - 1) / 4.0)
    elif step >= 7:
        _shimmer(bpx, zones, 5.0 + (SL - 7.0) * (step - 7) / 4.0)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), FIN[2], 196)
    _paint_fin(front, _pelvic(flap), FIN[3], 205)
    img.alpha_composite(front)
    sx, sy = SHIFT
    return _outline(img).crop((-sx, -sy, SIZE - sx, SIZE - sy))


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
