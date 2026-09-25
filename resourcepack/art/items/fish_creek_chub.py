"""Creek Chub: a common minnow of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's taupe-olive / khaki / mauve / sage / gold scheme. A stout,
round-snouted minnow: a big blunt head with a wide terminal mouth whose jaw runs back
under the golden eye, an olive-brown back netted with dark-edged scales under a rim light,
a brassy sheen line, a dusky lateral stripe running from the snout through the eye to a
dark spot at the tail base, mauve-silver flanks with sage scale glints over a pale cream
belly, and the species' black spot at the front of the rounded dorsal fin. Fins are
translucent olive-amber with painted rays; the low pectoral, pelvic and anal fins carry
the breeding chub's warm orange flush. Animated (COMMON, 8 frames x 3 ticks): the tail
and fins sway about a pixel on offset sine phases and a soft glint slides along the back
from snout to tail, then rests.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, rgba, save_animation, sprite

ID = "fish_creek_chub"
NAME = "Creek Chub"
KIND = "item"
MODEL_KEY = "fish/creek_chub"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 8
FRAMETIME = 3

# ---- palettes (darkest -> lightest), from the old sprite's taupe / khaki / mauve / gold ----
OUTLINE = "#2c2119"
FIN_OUTLINE = ("#4a3a26", 232)     # softer edge where the outline only touches a fin
BACK = ["#2d2a19", "#423d23", "#59532e", "#736c3a", "#958b4b", "#b8ad66"]
BRASS = ["#bca255", "#dcc36c", "#f2df92"]
FLANK = ["#6a5264", "#85697c", "#a08494", "#b9a0ab", "#d0bec2", "#e6dcd6", "#f7f2ea"]
STRIPE = ["#33273a", "#4a3a4e", "#6c5a6c"]
SAGE = ["#8a9884", "#a6b39b"]
GOLD = ["#6f4f1f", "#b08231", "#e0ad3a", "#ffd65a"]
FIN = ["#4e4428", "#655733", "#7e6d42", "#998656", "#b6a26e", "#d3c290"]
WARM = ["#8a6334", "#ad7f45", "#c89a5c"]   # orange flush on the lower fins
SPOT = "#1f1714"
EYE = ["#120d0c", "#ffffff"]
GLINT = "#fff8e2"

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 22.5                      # snout to tail base
SNOUT = (2.8, 7.4)             # snout tip in the frame
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


# Half-depths of the body above (TOP) and below (BOT) the axis: a blunt, rounded snout,
# a big head and a thick, rounded body that tapers into a sturdy tail stalk.
TOP = _smooth([(0.0, 0.9), (0.6, 1.6), (1.4, 2.2), (2.6, 2.75), (4.0, 3.15), (5.6, 3.5), (7.4, 3.75),
               (9.2, 3.85), (11.2, 3.75), (13.4, 3.35), (15.6, 2.75), (17.8, 2.15), (20.0, 1.7), (21.6, 1.52),
               (22.5, 1.5)])
BOT = _smooth([(0.0, 0.85), (0.6, 1.65), (1.4, 2.3), (2.6, 2.95), (4.0, 3.45), (5.6, 3.9), (7.4, 4.25),
               (9.2, 4.4), (11.2, 4.25), (13.4, 3.75), (15.6, 3.0), (17.8, 2.3), (20.0, 1.78), (21.6, 1.55),
               (22.5, 1.52)])

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

TAIL_PIVOT = (22.0, 0.0)


def _tail(sway: float):
    # forked, with rounded lobe tips and a deep central notch
    pts = [(21.3, 1.5), (23.0, 2.3), (24.8, 3.2), (26.4, 4.0), (27.5, 4.0), (27.3, 3.0), (26.4, 1.8),
           (25.7, 0.4), (25.7, -0.4), (26.4, -1.9), (27.3, -3.1), (27.5, -4.2), (26.4, -4.2),
           (24.8, -3.3), (23.0, -2.4), (21.3, -1.55)]
    rays = [((22.4, 0.9), (27.0, 3.4), FIN[4]), ((22.4, -0.9), (27.0, -3.6), FIN[4]),
            ((22.6, 0.0), (25.6, 0.0), FIN[1])]
    a = TAIL_REST + 6.5 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _dorsal(ripple: float):
    # rounded-triangular, set mid-back above the pelvic fins, higher in front
    tip = (11.6 + 0.4 * ripple, TOP(11.6) + 4.1)
    pts = [(9.8, TOP(9.8) - 0.6), (10.3, TOP(10.3) + 2.2), tip, (12.9 + 0.5 * ripple, TOP(12.9) + 3.0),
           (13.9, TOP(13.9) + 0.9), (14.1, TOP(14.1) - 0.6)]
    rays = [((10.3, TOP(10.3)), tip, FIN[4]),                                  # lit leading ray
            ((12.2, TOP(12.2)), (12.9 + 0.5 * ripple, TOP(12.9) + 2.4), FIN[1])]
    return pts, rays


# The species' mark: a black spot at the front base of the dorsal fin, in local (u, v).
DORSAL_SPOT = [(10.1, 0.35), (10.9, 0.4), (10.4, 1.1)]


def _pectoral(flap: float):
    # the near pectoral: a short blade just behind the gill cover, lying over the lower
    # flank and swept back, its tip just clearing the belly
    pivot = (6.0, -BOT(6.0) + 1.6)
    pts = [(5.6, -BOT(5.6) + 2.1), (6.8, -BOT(6.8) + 2.0), (9.4, -BOT(9.4) + 0.3), (9.6, -BOT(9.6) - 0.6),
           (8.6, -BOT(8.6) - 0.9), (6.0, -BOT(6.0) + 1.0)]
    rays = [((6.2, -BOT(6.2) + 1.6), (9.2, -BOT(9.2) - 0.3), WARM[0])]
    a = 9.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _pelvic(flap: float):
    # small, swept-back paired fin under the dorsal
    pivot = (11.0, -BOT(11.0))
    pts = [(10.3, -BOT(10.3) + 0.5), (12.0, -BOT(12.0) + 0.5), (14.6, -BOT(14.6) - 1.0), (14.1, -BOT(14.1) - 1.8),
           (12.4, -BOT(12.4) - 1.5)]
    rays = [((11.0, -BOT(11.0)), (13.9, -BOT(13.9) - 1.3), WARM[0])]
    a = 8.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _anal(ripple: float):
    pts = [(15.6, -BOT(15.6) + 0.5), (16.4, -BOT(16.4) - 1.4), (17.6, -BOT(17.6) - 2.1 - 0.3 * ripple),
           (19.4, -BOT(19.4) - 1.3 - 0.2 * ripple), (19.8, -BOT(19.8) + 0.5)]
    rays = [((16.6, -BOT(16.6)), (17.6, -BOT(17.6) - 1.8 - 0.3 * ripple), WARM[0])]
    return pts, rays


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=228, lit=None, dark=None):
    """Paint a translucent fin: membrane plus rays (segments with their own colour). With
    lit, fin pixels whose upper or left neighbour is open water catch the top-left light;
    with dark, those whose lower or right neighbour is open water fall into shadow."""
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
            elif dark and inside[y, x] and ((y < SIZE - 1 and not inside[y + 1, x] and not MASK[y + 1, x])
                                            or (x < SIZE - 1 and not inside[y, x + 1] and not MASK[y, x + 1])):
                colour = dark
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)


# ---- body -------------------------------------------------------------------------------
# The body silhouette is the procedural lens above with a few hand edits; its colours come
# from discrete bands counted in from the dorsal and ventral contours (clean staircases that
# follow the outline), a lateral stripe that follows the body axis, then hand-placed head
# details on top.

BODY_ADD = [(6, 6)]
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

# Palette letters for the hand-placed details (and the dump tools).
LETTERS = {"o": OUTLINE, "K": EYE[0], "W": EYE[1], "x": SPOT}
LETTERS.update({k: c for k, c in zip("abcdef", BACK)})
LETTERS.update({k: c for k, c in zip("lmn", BRASS)})
LETTERS.update({k: c for k, c in zip("0123456", FLANK)})
LETTERS.update({k: c for k, c in zip("pqr", STRIPE)})
LETTERS.update({k: c for k, c in zip("st", SAGE)})
LETTERS.update({k: c for k, c in zip("ghij", GOLD)})

# Head details: (x, y) -> letter. The eye sits high in the stripe with a catchlight and a
# golden iris, the wide terminal mouth notches the snout and runs back under the eye, a
# pale lower jaw, a brassy cheek and the curved edge of the gill cover.
HEAD = {
    (2, 8): "e", (3, 8): "q", (4, 8): "p",                          # upper lip, stripe to the snout
    (4, 9): "2", (4, 10): "r",
    (5, 8): "W", (6, 8): "K", (5, 9): "K", (6, 9): "K",            # eye
    (7, 9): "h", (7, 8): "c",                         # golden iris
    (3, 9): "o",                                                    # mouth corner
    (3, 10): "4", (4, 11): "5", (5, 11): "6",                       # pale lower jaw
    (5, 10): "n",                                                   # brassy cheek
    (8, 8): "b", (8, 9): "l", (7, 11): "2", (6, 12): "4", (6, 11): "5",   # gill cover edge
}


STRIPE_V = 0.0          # the lateral stripe's centre line (local v)


def _stripe(x: int, y: int) -> int:
    """0 outside the lateral stripe, 1 at its soft lower edge, 2 in its dark core. The stripe
    runs from the snout through the eye to a dark spot at the tail base."""
    u, v = UC[y, x], VC[y, x]
    if u > 20.2 and math.hypot(u - 21.3, v - STRIPE_V) < 1.3:   # dark spot at the tail base
        return 2
    if u < 0.6 or u > SL:
        return 0
    d = v - STRIPE_V
    half = 0.5 if u < 5.0 else 0.62
    if abs(d) < half:
        return 2
    return 1 if -half - 0.55 < d < 0 and u > 5.5 else 0


def _depth(x: int, y: int) -> float:
    """0 at the belly contour, 1 at the back contour."""
    u, v = UC[y, x], VC[y, x]
    top, bot = float(TOP(u)), float(BOT(u))
    return (v + bot) / (top + bot)


def _band_colour(x: int, y: int) -> str:
    kb, kv = int(KB[y, x]), int(KV[y, x])
    f = _depth(x, y)
    v = VC[y, x]
    st = _stripe(x, y)
    if kb == 0:
        return BACK[5] if x < 10 else BACK[4]       # rim light on the back
    if st == 2:                                     # dusky lateral stripe
        return STRIPE[1] if (x + 2 * y) % 4 == 1 else STRIPE[0]
    if v > STRIPE_V + 0.6:
        if v < STRIPE_V + 1.25 and kb >= 2:
            return BRASS[1] if x < 15 else BRASS[0]     # brassy line above the stripe
        # olive-brown back, netted with dark-edged scales
        tone = 4 if kb == 1 else 2 if f > 0.8 and kb == 2 else 3
        if x >= 7 and (x + 2 * y) % 4 == 0:
            tone -= 1
        return BACK[tone]
    if st == 1:
        return STRIPE[2]
    if kv == 0:
        return FLANK[3]                             # belly edge
    if kv == 1 or f < 0.16:
        return FLANK[6]
    if f < 0.3:
        return FLANK[5]
    if f < 0.42:                                    # pale mauve-silver flank
        return FLANK[4]
    if (x + 2 * y) % 6 == 0 and x > 8:              # sage scale glints under the stripe
        return SAGE[1]
    return FLANK[3]


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
    """1 px outline around the silhouette: dark umber beside the body, a softer olive-brown
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
            if kb < 0 or kb > 3 or HEAD.get((x, y), "") in ("o", "W", "K", "x"):
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / width) * strength * (1.0 - kb / 4.5)
            if k <= 0:
                continue
            r, g, b, a = px[x, y]
            px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _spot(img: Image.Image) -> None:
    """The black dorsal-fin spot, painted over the fin base where it meets the back."""
    cov = _poly([(10.5, TOP(10.5) - 0.2), (10.7, TOP(10.7) + 1.7), (11.9, TOP(11.9) + 1.5),
                 (12.1, TOP(12.1) - 0.2)])
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if cov[y, x] >= 0.35 and px[x, y][3] and not MASK[y, x]:
                r, g, b, _ = rgba(SPOT)
                px[x, y] = (r, g, b, 238)


def _frame(t: float) -> Image.Image:
    """One 32x32 frame at loop phase t (0..1): fins and tail on sine sways, glint on top."""
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    # Medial and pelvic fins sit behind the body (hanging below the belly); only the near
    # pectoral lies over the lower flank, as a light translucent blade.
    _paint_fin(img, _tail(sway), FIN[2], 204, cov_min=0.4, lit=FIN[3], dark=FIN[1])
    _paint_fin(img, _dorsal(ripple), FIN[2], 205, lit=FIN[3], dark=FIN[1])
    _spot(img)
    _paint_fin(img, _anal(ripple), WARM[1], 200, lit=WARM[2], dark=WARM[0])
    _paint_fin(img, _pelvic(flap), WARM[1], 200, lit=WARM[2], dark=WARM[0])
    body = _body()
    _glint(body, t)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), WARM[2], 170, ray_alpha=200)
    img.alpha_composite(front)
    return _outline(img)


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
