"""Moonfish: a rare fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right). The
old sprite's pale periwinkle / ice / lavender diamond, redrawn as a silver moony: a deep,
laterally flattened rhombic body, tiny upturned mouth under a steep forehead, a big eye
crossed by a soft indigo bar with a second bar behind the gill cover, tall sickle-shaped
dorsal and anal lobes with pale-gold leading rays and dark tips (the diamond's points), no
pelvic fins (adult moonies lose them), a small pectoral and a short notched tail. The body
is hand-painted pixel by pixel; the fins are procedural so they can sway. Mirror-silver flanks with a
periwinkle back, an icy horizon line, lavender scale marks and a pale moonstone crescent on
the flank.

Animation (RARE, 12 frames x 3 ticks): tail and fins sway a pixel on offset phases, a soft
glint slides along the back (frames 1-4), then a blue moonlit sheen with sparkling scales
rolls over the body (6-10); three star twinkles blink around the fish on staggered phases
and the crescent breathes softly.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_moonfish"
NAME = "Moonfish"
KIND = "item"
MODEL_KEY = "fish/moonfish"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palettes (darkest -> lightest), from the old sprite's periwinkle / ice / lavender -----
OUTLINE = "#262a5c"
OUTLINE_LIT = "#343a78"
FIN_OUTLINE = ("#3d4486", 232)
BACK = ["#2b306a", "#3b4488", "#4a56a2", "#6477c0", "#8ea2de", "#b4c6ef"]
AQUA = ["#8fc4e2", "#b6e4f0", "#dcf6fa"]
SILVER = ["#6d73ae", "#8a91c8", "#a5aede", "#bec7ec", "#d4dcf5", "#e8edfb", "#f8faff"]
SHEEN = ["#a99fdc", "#c3baf0", "#d9d2fb"]
BAR = ["#363a82", "#50569f", "#7479bd", "#9a9fd6"]
FIN = ["#2c2f72", "#5a67b4", "#7f95d6", "#a6bdf0", "#cddcf7", "#eef4ff"]
FIN_GOLD = "#f1e2b0"          # the moony's pale-gold leading fin rays
MOON = ["#c6c8ea", "#eceefc", "#fff3cf"]
EYE = ["#10122c", "#ffffff", "#dfe8fa"]
GLINT = "#f4fbff"
SHIMMER = "#9ccbff"
SPARK = "#f6fcff"
STAR = "#e4f2ff"

# ---- geometry (pixels) ------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(16.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 19.6                      # snout to tail base
SNOUT = (2.3, 11.0)            # snout tip in the frame
SS = 4
K = 1.1                        # design units -> pixels
TAIL_REST = 1.0


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


# Half-depths of the deep rhombic body above (TOP) and below (BOT) the axis.
TOP = _smooth([(0.0, 0.6), (0.9, 1.8), (2.0, 3.4), (3.4, 4.9), (5.0, 6.0), (6.8, 6.8), (8.6, 7.0),
               (10.4, 6.5), (12.2, 5.3), (14.0, 3.8), (15.8, 2.5), (17.6, 1.7), (19.6, 1.5)])
BOT = _smooth([(0.0, 0.6), (0.9, 1.4), (2.0, 2.8), (3.4, 4.4), (5.0, 5.8), (6.8, 6.9), (8.6, 7.3),
               (10.4, 6.9), (12.2, 5.6), (14.0, 4.0), (15.8, 2.6), (17.6, 1.7), (19.6, 1.5)])

_ys, _xs = np.mgrid[0:SIZE * SS, 0:SIZE * SS]
_SX, _SY = (_xs + 0.5) / SS - SNOUT[0], (_ys + 0.5) / SS - SNOUT[1]
U = (_SX * AX[0] + _SY * AX[1]) / K
V = (_SX * UP[0] + _SY * UP[1]) / K
_py, _px = np.mgrid[0:SIZE, 0:SIZE]
UC = ((_px + 0.5 - SNOUT[0]) * AX[0] + (_py + 0.5 - SNOUT[1]) * AX[1]) / K
VC = ((_px + 0.5 - SNOUT[0]) * UP[0] + (_py + 0.5 - SNOUT[1]) * UP[1]) / K


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


def _to_frame(u, v):
    return (SNOUT[0] + K * (u * AX[0] + v * UP[0]), SNOUT[1] + K * (u * AX[1] + v * UP[1]))



# ---- fins: (polygon, rays, tip) in local coordinates; v > 0 is the back ------------------
# Each fin also names a "tip" region (dark tips on the sickle lobes, as on a real moony).

TAIL_PIVOT = (19.2, 0.0)


def _tail(sway: float):
    pts = [(18.8, 1.5), (20.6, 2.3), (22.8, 3.7), (25.0, 4.7), (24.7, 3.2), (23.8, 1.5), (23.2, 0.0),
           (23.8, -1.5), (24.7, -3.2), (25.0, -4.7), (22.8, -3.7), (20.6, -2.3), (18.8, -1.5)]
    rays = [((19.6, 0.8), (24.4, 3.9)), ((19.6, -0.8), (24.4, -3.9)), ((19.8, 0.0), (22.6, 0.0))]
    a = TAIL_REST + 6.5 * sway
    return _rot(pts, TAIL_PIVOT, a), [_rot(r, TAIL_PIVOT, a) for r in rays], None


def _dorsal(ripple: float):
    """A tall sickle lobe sweeping up and back from the nape, then a low fin to the tail."""
    r = 0.5 * ripple
    tip = (15.6 + r, 12.2 - 0.3 * r)
    pts = [(6.2, TOP(6.2) - 0.6), (9.0, 8.7), (12.0, 10.7 + 0.2 * r), tip, (15.0 + r, 11.0),
           (14.3 + 0.6 * r, 9.0), (14.5 + 0.3 * r, 6.6), (16.0, TOP(16.0) + 1.4), (18.0, TOP(18.0) + 0.6),
           (18.6, TOP(18.6) - 0.6)]
    rays = [((7.4, TOP(7.4)), (9.6, 9.1)), ((9.6, 9.1), tip), ((10.4, TOP(10.4)), (14.4 + 0.7 * r, 10.2)),
            ((13.0, TOP(13.0)), (14.9 + 0.3 * r, 6.8))]
    return pts, rays, (tip, 2.1)


def _anal(ripple: float):
    """The mirror sickle below, sweeping down and back from behind the belly."""
    r = 0.5 * ripple
    tip = (15.0 + r, -11.4 + 0.3 * r)
    pts = [(6.8, -BOT(6.8) + 0.6), (9.0, -8.9), (12.0, -10.4 - 0.2 * r), tip, (14.4 + r, -10.2),
           (13.9 + 0.6 * r, -8.4), (14.2 + 0.3 * r, -6.5), (15.8, -BOT(15.8) - 1.3), (17.8, -BOT(17.8) - 0.6),
           (18.4, -BOT(18.4) + 0.6)]
    rays = [((8.0, -BOT(8.0)), (9.8, -9.3)), ((9.8, -9.3), tip), ((10.8, -BOT(10.8)), (14.0 + 0.7 * r, -9.6)),
            ((13.2, -BOT(13.2)), (14.6 + 0.3 * r, -6.8))]
    return pts, rays, (tip, 2.1)


# The near pectoral fin is painted over the flank, just behind the gill-cover bar, in two
# poses: "p" membrane, "P" its darker trailing edge.
PECT = {
    "spread": [(9, 13, "p"), (10, 13, "p"), (10, 14, "p"), (11, 14, "p"), (9, 14, "P"), (10, 15, "P"),
               (11, 15, "P"), (12, 15, "P")],
    "folded": [(9, 13, "p"), (10, 13, "p"), (11, 13, "p"), (11, 14, "p"), (9, 14, "P"), (10, 14, "P"),
               (12, 14, "P")],
}


# ---- body (hand-painted) -------------------------------------------------------------------
# One letter per pixel, rows 6-20 (start column, letters). Light comes from the top-left:
# a rim light along the forehead and back, a dark periwinkle back band under it, the icy
# horizon line, a specular patch on the upper-front flank and mid-tone lavender scales toward
# the lower rear, a countershaded belly with reflected light and a shaded belly edge. Two
# indigo bars cross the head (through the silver-ringed eye, and behind the gill cover) and
# the moonstone crescent sits on the mid flank.
BODY_ROWS = {
    6: (8, "FFFEEE"),
    7: (6, "FxxDxxCCE"),
    8: (5, "FxxDxxDDDCE"),
    9: (4, "FEiiDyyrrrDCE"),
    10: (3, "65iWKiyy665rDCE"),
    11: (2, "4B5iKKiy6665rDCE"),
    12: (3, "355ii5y66554rDCE"),
    13: (3, "24yy4z554v4mmrDCE"),
    14: (4, "2yz4z4v33mnuvrDCD"),
    15: (4, "2zz4!v3v3nu2u2rDCDDD"),
    16: (4, "2!4v3v3v3nu2u22qDCCB"),
    17: (5, "254v3u3umnu2111111"),
    18: (6, "25554444mm21"),
    19: (7, "266555421"),
    20: (8, "233321"),
}

LETTERS = {"o": OUTLINE, "K": EYE[0], "W": EYE[1], "i": EYE[2]}
LETTERS.update({k: c for k, c in zip("ABCDEF", BACK)})
LETTERS.update({k: c for k, c in zip("qrs", AQUA)})
LETTERS.update({k: c for k, c in zip("0123456", SILVER)})
LETTERS.update({k: c for k, c in zip("uvw", SHEEN)})
LETTERS.update({k: c for k, c in zip("xyz!", BAR)})
ZONE = {}
ZONE.update({k: "back" for k in "ABCDEF"})
ZONE.update({k: "horizon" for k in "qrs"})
ZONE.update({k: "flank" for k in "0123456uvw"})
ZONE.update({k: "bar" for k in "xyz!"})
ZONE.update({k: "moon" for k in "lmn"})
ZONE.update({k: "eye" for k in "KWio"})


def _art() -> dict:
    out = {}
    for y, (x0, row) in BODY_ROWS.items():
        for i, ch in enumerate(row):
            if ch != ".":
                out[(x0 + i, y)] = ch
    return out


ART = _art()
MASK = np.zeros((SIZE, SIZE), bool)
for (_x, _y) in ART:
    MASK[_y, _x] = True


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


def _body(t: float) -> tuple[Image.Image, dict]:
    """The body at phase t: the crescent breathes from pale silver to warm moonlight."""
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    glow = 0.5 + 0.5 * math.sin(2 * math.pi * (t - 0.1))
    for (x, y), ch in ART.items():
        if ch == "n":
            colour = mix(MOON[1], MOON[2], glow)
        elif ch == "m":
            colour = mix(MOON[0], MOON[1], 0.2 + 0.6 * glow)
        else:
            colour = LETTERS[ch]
        px[x, y] = rgba(colour)
        zones[(x, y)] = ZONE[ch]
    return img, zones


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=224, lead=None):
    """Translucent fin: membrane, painted rays (the first, leading ray catches the light
    when lead is given) and dark sickle tips."""
    poly, rays, tip = fin
    cov = _poly(poly)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if cov[y, x] < cov_min:
                continue
            u, v = UC[y, x], VC[y, x]
            colour, a = membrane, alpha
            for i, (ra, rb) in enumerate(rays):
                if cov[y, x] >= 0.7 and _near(u, v, ra, rb, 0.5):
                    colour, a = (lead if i == 1 and lead else FIN[1]), ray_alpha
                    break
            if tip:
                d = math.hypot(u - tip[0][0], v - tip[0][1])
                if d < tip[1]:
                    colour, a = FIN[0], 232
                elif d < tip[1] + 1.0:
                    colour, a = mix(colour, FIN[0], 0.5), 214
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)


def _pectoral_over(img, zones, flap):
    """Blend the translucent pectoral fin over the flank."""
    px = img.load()
    for x, y, part in PECT["folded" if flap > 0.35 else "spread"]:
        if px[x, y][3]:
            _blend(px, x, y, FIN[1] if part == "P" else FIN[2], 0.78 if part == "P" else 0.58)
            zones[(x, y)] = "pect"


def _outline(img: Image.Image) -> Image.Image:
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
            facing = ((y + 1 < SIZE and src[x, y + 1][3]) or (x + 1 < SIZE and src[x + 1, y][3])) and \
                not (y > 0 and src[x, y - 1][3]) and not (x > 0 and src[x - 1, y][3])
            if any(MASK[ny, nx] for nx, ny in near):
                dst[x, y] = rgba(OUTLINE_LIT if facing else OUTLINE)
            else:
                r, g, b, _ = rgba(FIN_OUTLINE[0])
                dst[x, y] = (r, g, b, FIN_OUTLINE[1])
    return out


def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _glint(img, zones, t):
    """Frames 1-4: a soft glint sliding along the back, snout to tail."""
    step = round(t * FRAMES)
    if not 1 <= step <= 4:
        return
    centre = 1.0 + 18.0 * (step - 1) / 3.0
    px = img.load()
    for (x, y), zone in zones.items():
        kb = int(KB[y, x])
        if zone not in ("back", "horizon", "bar") or kb > 3:
            continue
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.0) * 0.72 * (1.0 - kb / 4.5)
        if k > 0:
            _blend(px, x, y, GLINT, k)


def _shimmer(img, zones, t):
    """Frames 6-10: a blue moonlit sheen with sparkling scales rolls across the body."""
    step = round(t * FRAMES)
    if not 6 <= step <= 10:
        return
    centre = 0.5 + 19.0 * (step - 6) / 4.0
    px = img.load()
    for (x, y), zone in zones.items():
        if zone in ("eye", "moon"):
            continue
        along = UC[y, x] + 0.35 * VC[y, x]
        k = max(0.0, 1.0 - abs(along - centre) / 3.0)
        if k <= 0:
            continue
        spark = y % 2 == 1 and (x + y // 2) % 2 == 0 and zone in ("flank", "belly", "horizon")
        _blend(px, x, y, SPARK if spark else SHIMMER, (1.0 if spark else 0.52) * min(1.0, k * 1.3))


# Star twinkles around the fish: (x, y, phase, reach)
STARS = [(5, 4, 0.0, 2), (27, 9, 0.36, 2), (8, 25, 0.68, 2)]


def _stars(img, t):
    for x, y, phase, reach in STARS:
        p = (t - phase) % 1.0
        amount = math.sin(math.pi * p / 0.4) if p < 0.4 else 0.0
        sparkle(img, x, y, amount, STAR, reach)


def _frame(t: float) -> Image.Image:
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    _paint_fin(img, _tail(sway), FIN[3], 196, cov_min=0.4)
    _paint_fin(img, _dorsal(ripple), FIN[3], 184, lead=FIN_GOLD)
    _paint_fin(img, _anal(ripple), FIN[3], 184, lead=FIN_GOLD)
    body, zones = _body(t)
    _pectoral_over(body, zones, flap)
    _glint(body, zones, t)
    _shimmer(body, zones, t)
    img.alpha_composite(body)
    img = _outline(img)
    _stars(img, t)
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
