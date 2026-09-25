"""World Serpent Scale: a legendary catch of the JoshyMC fishing collection.

Not a fish but exactly what the name says: one scale shed by the World Serpent, drawn as a
flat 32x32 sprite in the collection pose (the broad rounded base up-left, the pointed free
tip down-right, running diagonally like the vanilla cod). Keeps the old sprite's emerald
greens and its white glint, pushed toward jade (teal shadows, mint highlights) so it reads
as a glossy scale rather than a leaf. It is a keeled reptile scale: a teardrop plate ringed
by a rolled, teal-shifted margin with a pale lit rim, concentric growth ridges around the
domed base, and a raised keel (bright ridge over a dark crease) running from the dome to
the pointed tip between two facets: the upper one catches the top-left light and carries
a glossy streak, the lower one falls into shadow with a little bounce light. A radiant
2x2 highlight with a catchlight sits on the dome.

Animation (LEGENDARY, 20 frames x 2 ticks): a soft glint slides down the keel, then a
shimmer band of sparkling scale texture rolls across the plate, while an iridescent sheen
(cyan, violet, rose) drifts over the facets; a golden 1 px rim glow breathes just outside
the outline, three golden sparkles orbit the scale and the highlight on the dome pulses
and flares into a small star.
"""
from __future__ import annotations

import math

import numpy as np

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_world_serpent_scale"
NAME = "World Serpent Scale"
KIND = "item"
MODEL_KEY = "fish/world_serpent_scale"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 20
FRAMETIME = 2

# ---- palette (darkest -> lightest), hue-shifted: shadows lean teal-blue, lights lean lime --
OUTLINE = "#0a2226"
OUTLINE_LIT = "#103a33"
PLATE = ["#0d2b2d", "#11413b", "#175b40", "#207545", "#30924d", "#52af62", "#8bd186", "#cdf2c4"]
BORDER = ["#0b2a2f", "#103c3e", "#16524e", "#1f6b5e", "#34896c"]   # teal-shifted margin band
RIM = "#e6fbe0"                 # pale lit edge on the front
GLINT = "#f6ffd8"
SHIMMER = "#e4f8b4"
SPARK = "#fdffe8"
HALO = "#ffd45a"                # rim glow
STAR = "#fff2b0"
IRIDESCENT = ["#5fe6e0", "#9a7cf0", "#f08cc8"]   # cyan, violet, rose

# ---- silhouette (hand-drawn): row -> (first x, last x) ----------------------------------------
ROWS = {
    6: (10, 14), 7: (7, 17), 8: (6, 18), 9: (5, 19), 10: (4, 20), 11: (4, 21), 12: (3, 21),
    13: (3, 22), 14: (3, 22), 15: (3, 23), 16: (4, 23), 17: (4, 24), 18: (5, 24), 19: (6, 25),
    20: (7, 25), 21: (9, 26), 22: (11, 26), 23: (14, 27), 24: (17, 27), 25: (21, 28), 26: (25, 28),
    27: (27, 28),
}
MASK = np.zeros((SIZE, SIZE), bool)
for _y, (_a, _b) in ROWS.items():
    MASK[_y, _a:_b + 1] = True

# Keel: a straight ridge from the dome to the tip, (x0, y0) -> (x1, y1).
KEEL = ((13, 14), (28, 27))
GROWTH = (4, 6)                 # rings carrying a growth ridge
HOT = (8, 10)                   # radiant highlight on the dome (2x2, top-left pixel)

# Local axes for the sweeping effects: u along the scale (base -> tip), v across it.
THETA = math.radians(38.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
CENTRE = (15.5, 16.0)
_py, _px = np.mgrid[0:SIZE, 0:SIZE]
UC = (_px + 0.5 - CENTRE[0]) * AX[0] + (_py + 0.5 - CENTRE[1]) * AX[1]
VC = (_px + 0.5 - CENTRE[0]) * UP[0] + (_py + 0.5 - CENTRE[1]) * UP[1]
U_MIN, U_MAX = float(UC[MASK].min()), float(UC[MASK].max())


def _rings():
    """Steps (4-neighbour) in from the silhouette: 0 = edge pixel, -1 = outside."""
    d = np.where(MASK, 10 ** 6, -1)
    current = {(x, y) for y in range(SIZE) for x in range(SIZE) if not MASK[y, x]}
    level = -1
    while current:
        level += 1
        nxt = set()
        for (x, y) in current:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < SIZE and 0 <= ny < SIZE and d[ny, nx] > level:
                    d[ny, nx] = level
                    nxt.add((nx, ny))
        current = nxt
    return d


RING = _rings()


def _facing(x: int, y: int) -> float:
    """How much this pixel's outward direction faces the top-left light (-1..1)."""
    ox = oy = 0.0
    r = RING[y, x]
    for dx in (-2, -1, 0, 1, 2):
        for dy in (-2, -1, 0, 1, 2):
            nx, ny = x + dx, y + dy
            if (dx or dy) and 0 <= nx < SIZE and 0 <= ny < SIZE and RING[ny, nx] < r:
                w = 1.0 / (dx * dx + dy * dy)
                ox += dx * w
                oy += dy * w
    n = math.hypot(ox, oy)
    if n == 0:
        return 0.0
    return -(ox + oy) / (n * math.sqrt(2))


def _line(p0, p1):
    """Bresenham line from p0 to p1."""
    (x0, y0), (x1, y1) = p0, p1
    pts = []
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x1 > x0 else -1), (1 if y1 > y0 else -1)
    err = dx + dy
    x, y = x0, y0
    while True:
        pts.append((x, y))
        if (x, y) == (x1, y1):
            break
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x += sx
        if e2 <= dx:
            err += dx
            y += sy
    return pts


KEEL_SET = set(_line(*KEEL))


def _side(x: int, y: int) -> int:
    """+1 on the upper-right facet of the keel, -1 on the lower-left one."""
    (x0, y0), (x1, y1) = KEEL
    return 1 if (x1 - x0) * (y - y0) - (y1 - y0) * (x - x0) < 0 else -1


def _keel_distance(x: int, y: int) -> float:
    """Perpendicular distance (pixels) from the keel line."""
    (x0, y0), (x1, y1) = KEEL
    return abs((x1 - x0) * (y - y0) - (y1 - y0) * (x - x0)) / math.hypot(x1 - x0, y1 - y0)


def _along(x: int, y: int) -> float:
    """0 at the base of the scale, 1 at the tip."""
    return (UC[y, x] - U_MIN) / (U_MAX - U_MIN)


def _plate_colour(x: int, y: int):
    """(colour, zone) of a plate pixel, from its ring, its facet and how it faces the light."""
    r = RING[y, x]
    f = _facing(x, y)
    s = _along(x, y)
    side = _side(x, y)
    kx = KEEL[0][0]
    if (x, y) in KEEL_SET and r < 2:             # the keel runs on through the margin to the tip
        return (BORDER[4] if r > 0 else PLATE[5]), "keel"
    if r == 0:                                   # the rolled edge of the margin
        if f > 0.35:
            return (RIM if s < 0.35 else PLATE[6]), "rim"
        if f > -0.25:
            return BORDER[4], "margin"
        return BORDER[3], "margin"
    if r == 1:                                   # the groove under the rolled edge
        if f > 0.35:
            return PLATE[4], "margin"
        if f > -0.25:
            return BORDER[3], "margin"
        return BORDER[2], "margin"
    # the field: keel ridge, two facets, a domed base
    if (x, y) in KEEL_SET:
        return PLATE[7 if s < 0.6 else 6], "keel"
    if (x, y - 1) in KEEL_SET and (x - 1, y) not in KEEL_SET and x > kx:
        return PLATE[1], "keel"                  # the crease under the ridge
    if r == 2 and f > 0.35:                      # the lit lip of the field
        return PLATE[6], "lip"
    if s < 0.3 and x < kx + 1:                   # the domed base
        tone = 5
    else:
        tone = (5 if s < 0.6 else 4) if side > 0 else (4 if s < 0.4 else 3)
    dist = _keel_distance(x, y)
    if r == 2 and f < -0.25:
        tone -= 1                                # the field turning down into the groove
    elif r == 3 and f < -0.25 and s > 0.25:
        tone += 1                                # light bouncing up under the lower facet
    elif side > 0 and 1.9 < dist < 3.0 and 0.44 < s < 0.76:
        tone += 1                                # a glossy streak along the lit facet
    elif r in GROWTH and s < 0.5:                # concentric growth ridges round the dome
        tone += 1 if f > 0.3 else -1
    return PLATE[max(0, min(7, tone))], "plate"


def _mark(x: int, y: int) -> bool:
    """A sparse diamond lattice for the reptile-skin micro-scales."""
    return y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1


def _base():
    """Static plate colours and a zone map (for the animated overlays)."""
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                colour, zone = _plate_colour(x, y)
                px[x, y] = rgba(colour)
                zones[(x, y)] = zone
    hx, hy = HOT
    for dx, dy, c in ((0, 0, SPARK), (1, 0, PLATE[7]), (0, 1, PLATE[7]), (1, 1, PLATE[6])):
        px[hx + dx, hy + dy] = rgba(c)
        zones[(hx + dx, hy + dy)] = "hot"
    return img, zones


BASE, ZONES = _base()


def _outline(img):
    """1 px hue-shifted outline, a touch lighter on the side facing the light."""
    src = img.load()
    out = img.copy()
    dst = out.load()
    filled = {(x, y) for y in range(SIZE) for x in range(SIZE) if src[x, y][3]}
    edge = set()
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in filled:
                continue
            near = [(x + dx, y + dy) in filled for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
            if not any(near):
                continue
            facing_light = ((x, y + 1) in filled or (x + 1, y) in filled) and \
                (x, y - 1) not in filled and (x - 1, y) not in filled
            dst[x, y] = rgba(OUTLINE_LIT if facing_light else OUTLINE)
            edge.add((x, y))
    return out, edge


BASE_OUTLINED, EDGE = _outline(BASE)
SOLID = MASK.copy()
for (_x, _y) in EDGE:
    SOLID[_y, _x] = True
HALO_PX = {(x, y) for y in range(SIZE) for x in range(SIZE)
           if not SOLID[y, x] and any((x + dx, y + dy) in EDGE for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))}


# ---- animation ----------------------------------------------------------------------------

def _blend(px, x, y, colour, k):
    if k <= 0:
        return
    r, g, b, a = px[x, y]
    c = rgba(colour)
    k = min(1.0, k)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _iridescent(phase: float):
    """Cyan -> violet -> rose -> cyan around a loop of phase."""
    p = (phase % 1.0) * 3
    i = int(p)
    return rgba(mix(IRIDESCENT[i % 3], IRIDESCENT[(i + 1) % 3], p - i))


def _luma(c) -> float:
    return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]


def _tint(px, x, y, colour, k):
    """Tint toward colour while keeping the pixel's brightness (no greying)."""
    if k <= 0:
        return
    r, g, b, a = px[x, y]
    scale = _luma((r, g, b)) / max(_luma(colour), 1.0)
    tc = [min(255.0, ch * scale) for ch in colour[:3]]
    px[x, y] = (round(r + (tc[0] - r) * k), round(g + (tc[1] - g) * k), round(b + (tc[2] - b) * k), a)


PLATE_ZONES = ("plate", "keel", "margin", "lip", "rim")


def _frame(t: float):
    img = BASE_OUTLINED.copy()
    px = img.load()
    step = round(t * FRAMES) % FRAMES
    breath = wave(t)

    # 1) the iridescent sheen: a soft diagonal band of colour drifting over the facets,
    #    its hue cycling cyan -> violet -> rose
    for (x, y), zone in ZONES.items():
        if zone not in PLATE_ZONES:
            continue
        u, v = UC[y, x], VC[y, x]
        band = wave(t, -(u * 0.8 + v * 1.4) / 24.0) ** 4
        light = _luma(px[x, y]) / 255.0
        _tint(px, x, y, _iridescent(t + u / 34.0), (0.08 + 0.8 * light) * band)

    # 2) frames 0-7: a glint slides down the lit facet along the keel, base to tip
    if step <= 7:
        centre = U_MIN + 2.0 + (U_MAX - U_MIN - 2.0) * step / 7.0
        for (x, y), zone in ZONES.items():
            if zone not in PLATE_ZONES:
                continue
            u, v = UC[y, x], VC[y, x]
            k = max(0.0, 1.0 - abs(u - centre) / 2.4) * max(0.0, 1.0 - abs(v - 1.4) / 3.2)
            _blend(px, x, y, GLINT, 0.62 * k)
    # 3) frames 10-17: a shimmer band of sparkling scale texture rolls across the plate
    elif 10 <= step <= 17:
        centre = U_MIN + 3.0 + (U_MAX - U_MIN - 4.0) * (step - 10) / 7.0
        for (x, y), zone in ZONES.items():
            if zone not in PLATE_ZONES:
                continue
            u = UC[y, x] + 0.7 * abs(VC[y, x])
            k = max(0.0, 1.0 - abs(u - centre) / 2.8)
            if k <= 0:
                continue
            spark = y % 2 == 1 and (x + y // 2) % 2 == 0
            _blend(px, x, y, SPARK if spark else SHIMMER, (0.8 if spark else 0.28) * min(1.0, k * 1.3))

    # 4) the radiant highlight breathes and flares into a small star at its peak
    hx, hy = HOT
    for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
        _blend(px, hx + dx, hy + dy, SPARK, 0.35 + 0.5 * breath)
    sparkle(img, hx, hy, max(0.0, breath * 1.4 - 0.55), colour=SPARK, reach=2)

    # 5) the golden rim glow breathing just outside the outline, stronger on the lit side
    c = rgba(HALO)
    for (x, y) in HALO_PX:
        lit = 1.0 if UC[y, x] < 0 else 0.72
        px[x, y] = (c[0], c[1], c[2], round((45 + 125 * breath) * lit))

    # 6) three golden sparkles orbiting the scale (the set repeats after a third of an orbit,
    #    and each twinkle depends only on its place in the orbit, so the loop is seamless)
    for i in range(3):
        f = (i / 3.0 + t / 3.0) % 1.0
        a = 2 * math.pi * f
        su, sv = 15.6 * math.cos(a), 10.4 * math.sin(a)
        sx = CENTRE[0] + su * AX[0] + sv * UP[0]
        sy = CENTRE[1] + su * AX[1] + sv * UP[1]
        amount = (0.35 + 0.65 * wave(f, 0.5)) * (0.65 + 0.35 * wave(5 * f))
        sparkle(img, int(sx), int(sy), amount, colour=STAR, reach=2)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
