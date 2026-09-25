"""God's Bait: the mythical bait of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, nose up-left, tail down-right). It
is exactly what the name says: a heavenly fishing lure. The old sprite's radiant ivory
orb, pale-gold glow and gold accents become a plump mother-of-pearl crankbait with a
gilded, engraved back, an ivory belly, a big golden doll eye, a translucent golden diving
lip, a gold tow ring on the nose, a small forked gold tail and a gold J hook
hanging from the belly ring. A golden halo floats over its head.

Animation (MYTHICAL, 24 frames x 2 ticks): the signature effect is divine light, a slow
sunburst of six soft golden rays turning behind the lure. On top of that the halo bobs, the
tail sways and the hook swings on its ring, a glint slides along the gilded back, an
iridescent mother-of-pearl sheen (violet, pink, cyan) rolls across the body, a golden rim
glow breathes just outside the outline, three sparkles orbit it and the eye shines.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_gods_bait"
NAME = "God's Bait"
KIND = "item"
MODEL_KEY = "fish/gods_bait"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 24
FRAMETIME = 2

# ---- palettes (darkest -> lightest), from the old sprite's ivory / pale gold / gold ----
OUTLINE = "#5a3320"                # warm umber: a deep, hue-shifted shade of the gold
SOFT_OUTLINE = ("#7a4a22", 225)    # where the outline only touches translucent parts
GOLD = ["#6e3f12", "#9a5d1a", "#c98a24", "#eeb535", "#ffd65e", "#fff0a0", "#fffbe0"]
IVORY = ["#8c7466", "#b39c86", "#d8c7a4", "#f0e5c6", "#faf3da", "#fffbec", "#ffffff"]
PEARL = ["#b7a3c6", "#d2c2d6"]     # lavender scale marks on the pearl
EYE = ["#2a1606", "#ffffff"]
IRIDESCENT = ["#c9a2ff", "#ff9fd8", "#8ff0ff", "#c9a2ff"]  # violet -> pink -> cyan -> violet
GLOW = "#ffe060"
LIP_GLASS = ["#bfe6f2", "#f4feff"]
RAY = "#fff2bc"

# ---- geometry ----------------------------------------------------------------------------
# Local frame: u runs from the nose toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 19.0                      # nose to tail peg
SNOUT = (7.3, 12.6)            # nose in the frame
SS = 4


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


# Plump plug body: blunt round nose, deepest a third of the way back, narrow tail peg.
TOP = _smooth([(0.0, 0.9), (0.6, 1.9), (1.5, 2.65), (3.0, 3.3), (5.0, 3.75), (7.5, 3.9), (10.0, 3.7),
               (12.5, 3.1), (15.0, 2.3), (17.0, 1.65), (19.0, 1.2)])
BOT = _smooth([(0.0, 0.9), (0.6, 1.95), (1.5, 2.8), (3.0, 3.6), (5.0, 4.15), (7.5, 4.35), (10.0, 4.1),
               (12.5, 3.4), (15.0, 2.5), (17.0, 1.75), (19.0, 1.2)])

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


def _to_px(u: float, v: float) -> tuple[int, int]:
    x = SNOUT[0] + u * AX[0] + v * UP[0]
    y = SNOUT[1] + u * AX[1] + v * UP[1]
    return int(math.floor(x)), int(math.floor(y))


# nose cap: a round nose instead of the flat cut at u = 0
_NOSE = ((U + 0.1) ** 2 / 1.2 + V ** 2 / 0.95 <= 1.0) & (U < 0.3)
BODY = _cov(((U >= 0) & (U <= SL + 0.3) & (V <= TOP(U)) & (V >= -BOT(U))) | _NOSE) >= 0.5

BODY_ADD: list[tuple[int, int]] = []
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

# ---- body colours ------------------------------------------------------------------------


def _band(x: int, y: int) -> tuple[str, str]:
    """(colour, zone) for a body pixel: gilded engraved back, pearl flank, ivory belly."""
    kb, kv = int(KB[y, x]), int(KV[y, x])
    h = kb + kv + 1
    u = UC[y, x]
    if kb == 0:
        return (GOLD[5] if u < 9 else GOLD[4]), "back"            # lit rim of the gilded back
    if kb == 1:
        if (x + y) % 3 == 0 and 3.0 < u < 17:                     # engraved scale arcs
            return GOLD[2], "back"
        return (GOLD[4] if u < 7 else GOLD[3]), "back"
    if kb == 2 and h >= 8:
        return GOLD[2], "seam"                                       # the gilt edge line
    if kv == 0:
        return IVORY[2], "belly"                                     # form shadow under the belly
    if kv == 1:
        return IVORY[5], "belly"
    if kv == 2 and h >= 7:
        return IVORY[4], "belly"
    # pearl flank with lavender scale marks in a clean diamond lattice
    if y % 2 == 0 and (x + (y // 2) % 2 * 2) % 4 == 1 and u > 3.0:
        return PEARL[1], "flank"
    if kb <= 3:
        return IVORY[6], "flank"                                     # bright pearl just under the gilt
    return IVORY[3], "flank"


# ---- parts in local coordinates -----------------------------------------------------------

TAIL_PIVOT = (18.6, 0.0)


def _tail(sway: float):
    pts = [(18.2, 1.1), (20.0, 1.9), (22.6, 3.4), (23.2, 2.4), (22.0, 1.0), (21.3, 0.0),
           (22.0, -1.0), (23.2, -2.6), (22.6, -3.6), (20.0, -2.0), (18.2, -1.1)]
    return _rot(pts, TAIL_PIVOT, 7.0 * sway)


LIP = [(1.0, -0.8), (-1.0, -1.3), (-3.3, -2.9), (-2.8, -4.0), (0.0, -3.5), (2.0, -2.4)]


def _composite_masks(t: float):
    sway = math.sin(2 * math.pi * t)
    tail = (_poly(_tail(sway)) >= 0.45) & ~MASK
    lip = (_poly(LIP) >= 0.45) & ~MASK
    return tail, lip


# Tow ring on the nose, where the line would tie on.
RING_UV = (-1.0, -0.5)


HANGER_U = 9.4


def _hook(swing: float):
    """J hook hanging from a belly ring: ({(x, y): letter}, the open pixels inside the
    bend). The hook swings on the ring: rows further down move up to a pixel sideways."""
    hx, hy = _to_px(HANGER_U, -BOT(HANGER_U) - 0.6)
    shape = [
        "r.....",
        "s.....",
        "s.....",
        "s.....",
        "s....b",
        "s....s",
        ".s..s.",
        "..ss..",
    ]
    out, inner = {}, set()
    for j, row in enumerate(shape):
        dx = int(round(swing * j / 7.0))
        for i, ch in enumerate(row):
            if ch != ".":
                out[(hx + i + dx, hy + j)] = ch
            elif 4 <= j <= 6 and 1 < i < 4:
                inner.add((hx + i + dx, hy + j))
    return out, inner


HALO_AT = _to_px(2.4, TOP(2.4) + 5.2)
HALO_ROWS = [
    "..hHHHh..",
    ".h.....h.",
    "g.......g",
    ".d.....d.",
    "..ddddd..",
]


def _halo(bob: int):
    """A golden halo ring over the head: ({(x, y): letter}, hole pixels inside it)."""
    cx, cy = HALO_AT
    out, hole = {}, set()
    for j, row in enumerate(HALO_ROWS):
        xs = [i for i, ch in enumerate(row) if ch != "."]
        for i, ch in enumerate(row):
            p = (cx - 4 + i, cy - 2 + j + bob)
            if ch != ".":
                out[p] = ch
            elif xs and min(xs) < i < max(xs):
                hole.add(p)
    return out, hole


HALO_COLOURS = {"H": GOLD[6], "h": GOLD[5], "g": GOLD[4], "d": GOLD[3]}
HOOK_COLOURS = {"r": GOLD[4], "s": GOLD[3], "b": GOLD[5]}


# Head details: eye, gill line and the nose ring.
def _head() -> dict:
    ex, ey = _to_px(2.7, 0.9)
    out = {
        (ex, ey): ("W", EYE[1]), (ex + 1, ey): ("K", EYE[0]),
        (ex, ey + 1): ("K", EYE[0]), (ex + 1, ey + 1): ("K", EYE[0]),
        (ex - 1, ey): ("i", GOLD[4]), (ex - 1, ey + 1): ("i", GOLD[3]),
        (ex, ey - 1): ("i", GOLD[5]), (ex + 1, ey - 1): ("i", GOLD[4]),
        (ex + 2, ey): ("i", GOLD[3]), (ex + 2, ey + 1): ("i", GOLD[2]),
        (ex, ey + 2): ("i", GOLD[2]), (ex + 1, ey + 2): ("i", GOLD[2]),
    }
    # gill line: a curved gilt stroke behind the eye
    for u, v in ((5.3, 2.4), (5.6, 1.2), (5.7, 0.0), (5.5, -1.2), (5.1, -2.4)):
        p = _to_px(u, v)
        if MASK[p[1], p[0]] and p not in out:
            out[p] = ("g", GOLD[2])
    return out


EYE_AT = _to_px(2.7, 0.9)


def _base(t: float):
    """Every opaque/translucent pixel of the lure at phase t: image and zone map."""
    img = canvas(SIZE)
    px = img.load()
    zones: dict[tuple[int, int], str] = {}
    tail, lip = _composite_masks(t)
    for y in range(SIZE):
        for x in range(SIZE):
            if tail[y, x]:
                # translucent gold fin with painted rays
                u, v = UC[y, x], VC[y, x]
                ray = abs(v) > 0.5 and abs(abs(v) - (u - 18.6) * 0.62) < 0.55
                c = GOLD[2] if ray else GOLD[4]
                r, g, b, _ = rgba(c)
                px[x, y] = (r, g, b, 225 if ray else 200)
                zones[(x, y)] = "tail"
            elif lip[y, x]:
                # clear heavenly glass: pale sky tint, bright rim along its leading edge
                edge = VC[y, x] < -2.9 or UC[y, x] < -2.2
                c = LIP_GLASS[1] if edge else LIP_GLASS[0]
                r, g, b, _ = rgba(c)
                px[x, y] = (r, g, b, 235 if edge else 175)
                zones[(x, y)] = "lip"
            elif MASK[y, x]:
                c, zone = _band(x, y)
                px[x, y] = rgba(c)
                zones[(x, y)] = zone
    for p, (letter, c) in _head().items():
        px[p] = rgba(c)
        zones[p] = "eye" if letter in "WK" else "head"
    rx, ry = _to_px(*RING_UV)
    px[rx, ry] = rgba(GOLD[5])
    zones[(rx, ry)] = "ring"
    hook, inner = _hook(1.2 * math.sin(2 * math.pi * (t - 0.2)))
    for p, ch in hook.items():
        if p not in zones:
            px[p] = rgba(HOOK_COLOURS[ch])
            zones[p] = "hook"
    bob = -int(round(wave(t, 0.25)))
    halo, hole = _halo(bob)
    hx, hy = HALO_AT
    run = 2 * math.pi * t * 2
    for p, ch in halo.items():
        c = HALO_COLOURS[ch]
        ang = math.atan2((p[1] - hy - bob) * 2.2, p[0] - hx)
        k = 0.5 + 0.5 * math.cos(ang - run)
        if k > 0.75:
            c = mix(c, GOLD[6], (k - 0.75) / 0.25 * 0.85)
        px[p] = rgba(c)
        zones[p] = "halo"
    return img, zones, hole, inner


SOLID = {"back", "seam", "flank", "belly", "eye", "head", "ring", "hook", "halo"}


def _outline(img: Image.Image, zones: dict, hole: set) -> Image.Image:
    px = img.load()
    dark = rgba(OUTLINE)
    soft = rgba(SOFT_OUTLINE[0])
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in zones or (x, y) in hole:
                continue
            near = [zones[(x + dx, y + dy)] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                    if (x + dx, y + dy) in zones]
            if not near:
                continue
            if any(z in SOLID for z in near):
                px[x, y] = dark
            else:
                px[x, y] = (soft[0], soft[1], soft[2], SOFT_OUTLINE[1])
    return img


def _blend(px, p, colour, k):
    r, g, b, a = px[p]
    c = rgba(colour)
    px[p] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


# the burst radiates from the middle of the body
RAY_CENTRE = _to_px(9.0, -0.2)

WEDGES = 6
WEDGE_WIDTH = 0.19      # share of each sector a beam fills
WEDGE_ALPHA = 155


def _wedges(px, t: float, skip: set, breath: float) -> None:
    """A sunburst of soft golden wedges turning one sector (60 deg) per loop, so the loop
    is seamless; each beam is brightest near the lure and fades out toward the frame."""
    rc = rgba(RAY)
    cx, cy = RAY_CENTRE[0] + 0.5, RAY_CENTRE[1] + 0.5
    turn = t / WEDGES
    for y in range(1, SIZE - 1):
        for x in range(1, SIZE - 1):
            if (x, y) in skip:
                continue
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            r = math.hypot(dx, dy)
            if r < 2 or r > 16.5:
                continue
            ang = (math.atan2(dy, dx) / (2 * math.pi) - turn) * WEDGES
            d = abs(ang - round(ang))                    # 0 at a beam's centre, 0.5 between
            width = WEDGE_WIDTH * (0.6 + 0.4 * min(1.0, r / 10))
            k = 1.0 - d / width
            if k <= 0:
                continue
            k = min(1.0, k * 1.8)
            fade = min(1.0, (16.5 - r) / 8.0)
            a = round(WEDGE_ALPHA * k * fade * (0.8 + 0.2 * breath))
            if a >= 20:
                px[x, y] = (rc[0], rc[1], rc[2], a)


def _frame(t: float) -> Image.Image:
    img, zones, hole, inner = _base(t)
    px = img.load()

    # 1) glint sliding along the gilded back, frames 0-9
    if t < 0.42:
        centre = -1.0 + 23.0 * t / 0.42
        for (x, y), z in zones.items():
            if z not in ("back", "seam", "head"):
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 2.6) * 0.7
            if k > 0:
                _blend(px, (x, y), GOLD[6], k)

    # 2) iridescent mother-of-pearl sheen rolling across the flank and belly, frames 9-21
    if 0.36 <= t < 0.9:
        k0 = (t - 0.36) / 0.54
        centre = -2.0 + 25.0 * k0
        for (x, y), z in zones.items():
            if z not in ("flank", "belly"):
                continue
            d = UC[y, x] - centre + 0.6 * VC[y, x]
            k = max(0.0, 1.0 - abs(d) / 3.4)
            if k <= 0:
                continue
            hue = (d / 6.8 + 0.5) * (len(IRIDESCENT) - 1)
            i = min(len(IRIDESCENT) - 2, max(0, int(hue)))
            c = mix(IRIDESCENT[i], IRIDESCENT[i + 1], hue - i)
            _blend(px, (x, y), c, 0.55 * k)

    img = _outline(img, zones, hole)
    px = img.load()
    filled = {(x, y) for y in range(SIZE) for x in range(SIZE) if px[x, y][3]}

    # 3) breathing golden rim glow just outside the outline (not around the thin parts)
    breath = wave(t)
    rim = set()
    for (x, y) in filled:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            p = (x + dx, y + dy)
            if p in filled or p in hole or p in inner or zones.get((x, y)) == "hook" or not (0 <= p[0] < SIZE and 0 <= p[1] < SIZE):
                continue
            rim.add(p)
    g = rgba(GLOW)
    for p in rim:
        px[p] = (g[0], g[1], g[2], round(55 + 95 * breath))

    # 4) divine rays behind the lure
    _wedges(px, t, filled | rim | hole | inner, breath)

    # 6) radiant eye: the catchlight flares twice a loop
    ex, ey = EYE_AT
    flare = wave(t * 2)
    _blend(px, (ex - 1, ey), GOLD[6], 0.6 * flare)
    _blend(px, (ex, ey - 1), GOLD[6], 0.6 * flare)
    if flare > 0.7:
        sparkle(img, ex, ey, (flare - 0.7) / 0.3 * 0.8, colour="#fffbe0", reach=1)

    # 7) three sparkles orbiting the lure, each twinkling as it travels (each moves a
    #    third of the orbit per loop and 10/3 twinkles, so it lands in the next one's place
    #    and phase and the loop has no seam)
    cx, cy = SNOUT[0] + 9.0 * AX[0], SNOUT[1] + 9.0 * AX[1]
    for i in range(3):
        a = 2 * math.pi * ((t + i) / 3.0) - 0.4
        sx = cx + 13.0 * math.cos(a)
        sy = cy + 8.5 * math.sin(a)
        ix = min(max(int(round(sx)), 3), SIZE - 4)      # arms stay inside the 1 px border
        iy = min(max(int(round(sy)), 3), SIZE - 4)
        sparkle(img, ix, iy, 0.35 + 0.65 * wave(t * 10 / 3.0, i / 3.0), colour="#fffde8", reach=2)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
