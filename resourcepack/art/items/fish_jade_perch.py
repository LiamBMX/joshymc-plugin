"""Jade Perch: a rare fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection's shared pose (side view, head up-left, tail
down-right), keeping the old sprite's jade-green / mint scheme. The real Jade Perch
(Scortum barcoo, a grunter) is deep-bodied and compressed with a small head, a short
snout with a small terminal mouth, one long dorsal fin whose spiny front half rises into
a serrated crest and drops into a rounded soft lobe, a spiny anal fin, a broad, shallowly
notched tail and a dark spot on nearly every scale. Here: a deep teal-jade back under a
lit rim, jade flanks carrying rows of dark scale spots, a countershaded mint belly, a
gold-ringed eye with a catchlight, the gill-cover arc, and translucent sea-green fins with
painted spines and rays.

Animation (RARE, 12 frames x 3 ticks): the tail wags with its lobe tips leading, the
pectoral, pelvic and soft dorsal fins sway a pixel on offset phases, a soft glint slides
along the back (first half of the loop), then a blue-tinted sheen of glittering scales
rolls from head to tail (second half), while three pale-cyan sparkles twinkle around the
fish in turn.
"""
from __future__ import annotations

import math

from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_jade_perch"
NAME = "Jade Perch"
KIND = "item"
MODEL_KEY = "fish/jade_perch"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3
SHIFT = (0, 1)             # the art below is drawn one pixel above centre

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean teal-blue, lights lean lime
OUTLINE = "#0c3632"
OUTLINE_LIT = "#14493d"    # the outline a touch lighter on the sides facing the light
FIN_OUTLINE_ALPHA = 226
BACK = ["#123d3a", "#1a5548", "#226c52", "#2e855c", "#4aa872", "#86d59a"]
JADE = ["#2c8c5e", "#37a266", "#48b872", "#62ca84", "#86da9b", "#b0eab7"]
MINT = ["#7fc4a4", "#a4dcbf", "#c6ecd4", "#e2f7e6", "#f5fdf2"]
SPOT = ["#154b40", "#1c5f4a", "#2a7654"]
GOLD = ["#6f6d22", "#b3a53a", "#e3d56a"]
FIN = ["#123a3e", "#1a4f52", "#256763", "#3a8479", "#5ea895", "#93d0bb"]
EYE = ["#081a19", "#ffffff"]
GLINT = "#effff2"
SHEEN = "#8fd6ff"          # the rare blue-tinted sheen
SHEEN_SPARK = "#e8fbff"
SPARK = "#bff2ff"

# ---- silhouette ------------------------------------------------------------------------------
# The body, column by column: x -> (top row, bottom row). A deep grunter body: the short
# head rises steeply to the dorsal origin, the back arches, the belly is full and the
# caudal peduncle narrow.
COLUMNS = {
    2: (10, 10), 3: (9, 11), 4: (8, 12), 5: (8, 13), 6: (7, 14), 7: (7, 14), 8: (6, 15), 9: (6, 16),
    10: (6, 16), 11: (6, 17), 12: (7, 17), 13: (7, 18), 14: (8, 18), 15: (8, 19), 16: (9, 19),
    17: (10, 20), 18: (11, 20), 19: (12, 21), 20: (14, 21), 21: (15, 21), 22: (16, 20), 23: (17, 20),
}
BODY = {(x, y) for x, (a, b) in COLUMNS.items() for y in range(a, b + 1)}

# Dorsal fin (behind the body): d membrane, D spine / ray. Spiny crest x 7-15 with the
# spine tips standing proud, a notch, then the soft rear lobe x 16-21.
DORSAL = {
    (7, 6): "d", (8, 5): "d", (8, 4): "D",
    (9, 3): "D", (9, 4): "D", (9, 5): "d",
    (10, 4): "d", (10, 5): "D",
    (11, 3): "D", (11, 4): "D", (11, 5): "d",
    (12, 4): "d", (12, 5): "D", (12, 6): "d",
    (13, 4): "D", (13, 5): "D", (13, 6): "d",
    (14, 5): "d", (14, 6): "D", (14, 7): "d",
    (15, 6): "D", (15, 7): "d",
    (16, 7): "d", (16, 8): "d",
    (17, 7): "d", (17, 8): "D", (17, 9): "d",
    (18, 8): "d", (18, 9): "d", (18, 10): "D",
    (19, 9): "d", (19, 10): "d", (19, 11): "D",
    (20, 11): "d", (20, 12): "d", (20, 13): "d",
    (21, 13): "d", (21, 14): "d",
}
# The soft lobe's trailing edge, in and out with the ripple.
DORSAL_TRAIL = [(20, 10), (21, 12)]

# Anal fin (behind the body): spiny front, soft rear.
ANAL = {
    (15, 20): "a", (16, 20): "a", (16, 21): "A",
    (17, 21): "A", (17, 22): "a", (18, 21): "a", (18, 22): "A",
    (19, 22): "a", (20, 22): "a",
}
ANAL_TRAIL = [(20, 23), (21, 22)]

# Pelvic fin (below the belly, behind the pectoral), two poses.
PELVIC = {
    "rest": {(8, 16): "a", (9, 17): "A", (10, 17): "a", (10, 18): "A", (11, 18): "a"},
    "swept": {(8, 16): "a", (9, 17): "A", (10, 17): "A", (11, 17): "a", (11, 18): "a", (12, 18): "a"},
}
# Pectoral fin (lies over the flank behind the gill cover), two poses. p membrane, P ray.
PECT = {
    "spread": {(10, 11): "P", (11, 11): "p", (12, 11): "p",
               (10, 12): "p", (11, 12): "P", (12, 12): "p", (13, 12): "p",
               (11, 13): "q", (12, 13): "P", (13, 13): "p", (14, 13): "p",
               (13, 14): "q", (14, 14): "q"},
    "folded": {(10, 11): "P", (11, 11): "p", (12, 11): "p", (13, 11): "p",
               (10, 12): "q", (11, 12): "P", (12, 12): "P", (13, 12): "p", (14, 12): "p",
               (12, 13): "q", (13, 13): "q", (14, 13): "p", (15, 13): "q"},
}
PECT_TINT = {"p": ("#b4ecd2", 0.55), "P": (FIN[2], 0.72), "q": (FIN[1], 0.66)}

# Tail: per column (x) the upper lobe and lower lobe row ranges at rest. The lobes meet at
# the base and part into a shallow notch. The tail rays run down each lobe.
TAIL_COLS = {
    24: [(16, 21)],
    25: [(15, 22)],
    26: [(14, 18), (20, 23)],
    27: [(14, 17), (21, 24)],
    28: [(13, 16), (22, 24)],
    29: [(13, 14), (23, 25)],
}
TAIL_RAYS = {(25, 17), (26, 16), (27, 15), (28, 14), (25, 20), (26, 21), (27, 22), (28, 23),
             (25, 19), (24, 18)}
TAIL_ROOT = 24

# Head details: (x, y) -> colour. A 2x2 eye with its catchlight and a gold iris, the
# gill-cover (operculum) arc with a lit lip in front, the small terminal mouth.
HEAD = {
    (5, 9): EYE[1], (6, 9): EYE[0], (5, 10): EYE[0], (6, 10): EYE[0],
    (7, 10): GOLD[1], (6, 11): GOLD[0], (5, 11): GOLD[2], (7, 9): JADE[4],
    (9, 8): SPOT[1], (9, 9): SPOT[1], (10, 10): SPOT[1], (9, 11): SPOT[2], (9, 12): SPOT[2],
    (9, 13): SPOT[2], (8, 14): SPOT[2],
    (8, 8): JADE[4], (8, 9): JADE[4], (8, 10): JADE[4], (8, 13): MINT[2], (7, 14): MINT[1],
    (3, 10): OUTLINE, (4, 11): SPOT[0], (2, 10): MINT[1], (3, 11): MINT[2], (3, 9): BACK[5],
    (4, 10): JADE[3],
}

# ---- tones -----------------------------------------------------------------------------------


def _tone(x: int, y: int) -> tuple[str, str]:
    """Colour and zone of a body pixel, from how far down its column it lies."""
    top, bot = COLUMNS[x]
    f = (y - top) / max(1, bot - top)
    back_dark = x >= 17
    if y == top:
        return (BACK[5] if x <= 11 else BACK[4] if x <= 19 else BACK[3]), "back"
    if y == bot and x >= 4:
        return (MINT[1] if x <= 9 else MINT[0] if x <= 17 else JADE[3]), "belly"
    if f < 0.2:
        return (BACK[2] if back_dark else BACK[3]), "back"
    if f < 0.34:
        return (BACK[3] if back_dark else JADE[0]), "flank"
    if f < 0.5:
        return (JADE[1] if back_dark else JADE[2]), "flank"
    if f < 0.62:
        return (JADE[2] if back_dark else JADE[3]), "flank"
    if f < 0.74:
        return (JADE[3] if back_dark else JADE[4]), "flank"
    if f < 0.86 or x >= 18:
        return MINT[1], "belly"
    return MINT[2], "belly"


def _spot_lattice() -> set:
    """One dark spot per scale: a staggered lattice laid along the body axis (rows run
    parallel to the fish, alternate rows offset half a scale), snapped to single pixels."""
    a = math.radians(26)
    ax, up = (math.cos(a), math.sin(a)), (math.sin(a), -math.cos(a))
    out = set()
    for j in range(-6, 7):
        for i in range(0, 12):
            u = 2.7 * i + 1.35 * (j % 2)
            v = 1.9 * j
            out.add((math.floor(2.5 + u * ax[0] + v * up[0]), math.floor(10.5 + u * ax[1] + v * up[1])))
    return out


SPOTS = _spot_lattice()


def _spot(x: int, y: int) -> bool:
    return (x, y) in SPOTS


def _body(pect: dict):
    """The body layer with its zones: tones, scale spots, head details, pectoral overlay."""
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    sx, sy = SHIFT
    for (x, y) in BODY:
        c, z = _tone(x, y)
        top, bot = COLUMNS[x]
        f = (y - top) / max(1, bot - top)
        if 10 <= x <= 21 and z in ("flank", "back") and y > top and 0.1 < f < 0.76 and _spot(x, y):
            c, z = (SPOT[0] if f < 0.36 else SPOT[1] if f < 0.6 else SPOT[2]), "spot"
        if x <= 8 and z == "flank":               # head: no spots, cheek tones
            c, z = (JADE[3] if f < 0.55 else JADE[4]), "head"
        if (x, y) in HEAD:
            c, z = HEAD[(x, y)], "eye"
        if (x, y) in pect:
            tint, k = PECT_TINT[pect[(x, y)]]
            c, z = mix(c, tint, k), "pect"
        px[x + sx, y + sy] = rgba(c)
        zones[(x + sx, y + sy)] = z
    return img, zones


def _tail(wag: float) -> dict:
    """Tail pixels flexed by wag (-1..1): columns further out move further, tips leading."""
    out = {}
    for x, ranges in TAIL_COLS.items():
        c = x - TAIL_ROOT
        dy = round(wag * 1.25 * (c / 5.0) ** 1.2)
        for a, b in ranges:
            for y in range(a, b + 1):
                edge = y in (a, b) and c >= 2
                ch = "T" if (x, y) in TAIL_RAYS else ("e" if edge else "t")
                out[(x, y + dy)] = ch
    return out


def _fins(t: float):
    """Behind-body fins and the pectoral overlay for phase t, as {(x, y): letter}."""
    wag = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.2))
    flap = math.sin(2 * math.pi * (t + 0.3))
    behind = dict(_tail(wag))
    behind.update(DORSAL)
    behind.update(ANAL)
    if ripple > -0.2:
        for k in DORSAL_TRAIL:
            behind[k] = "d"
    if ripple < 0.2:
        for k in ANAL_TRAIL:
            behind[k] = "a"
    behind.update(PELVIC["swept" if flap > 0 else "rest"])
    pect = PECT["folded" if math.sin(2 * math.pi * t + 1.3) > 0.35 else "spread"]
    return behind, pect


FIN_COLOURS = {  # letter: (colour, alpha)
    "d": (FIN[3], 196), "D": (FIN[1], 226), "a": (FIN[3], 194), "A": (FIN[1], 222),
    "t": (FIN[3], 202), "T": (FIN[1], 224), "e": (FIN[4], 198),
}


def _paint_fins(img, behind: dict):
    px = img.load()
    sx, sy = SHIFT
    keys = set(behind)
    for (x, y), ch in behind.items():
        if (x, y) in BODY:
            continue
        colour, alpha = FIN_COLOURS[ch]
        # membrane edge pixels facing the top-left light catch it
        if ch in "dat" and (((x, y - 1) not in keys and (x, y - 1) not in BODY)
                            or ((x - 1, y) not in keys and (x - 1, y) not in BODY)):
            colour = FIN[5] if ch == "d" else FIN[4]
        c = rgba(colour)
        px[x + sx, y + sy] = (c[0], c[1], c[2], alpha)


def _outline(img: Image.Image) -> Image.Image:
    """1 px outline around the silhouette: deep teal beside the body (a touch lighter on the
    light-facing sides), a softer translucent teal where it only borders fins."""
    src = img.load()
    out = img.copy()
    dst = out.load()
    sx, sy = SHIFT
    body = {(x + sx, y + sy) for x, y in BODY}
    dark, lit = rgba(OUTLINE), rgba(OUTLINE_LIT)

    def filled(x, y):
        return 0 <= x < SIZE and 0 <= y < SIZE and src[x, y][3] > 0

    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if filled(x + dx, y + dy)]
            if not near:
                continue
            facing = (filled(x, y + 1) or filled(x + 1, y)) and not filled(x, y - 1) and not filled(x - 1, y)
            o = lit if facing else dark
            dst[x, y] = o if any(p in body for p in near) else (o[0], o[1], o[2], FIN_OUTLINE_ALPHA)
    return out


# ---- effects ---------------------------------------------------------------------------------
AXIS = (math.cos(math.radians(26)), math.sin(math.radians(26)))


def _along(x: int, y: int) -> float:
    """Distance along the body axis from the snout (art coordinates)."""
    return (x + 0.5 - 2.0) * AXIS[0] + (y + 0.5 - 10.5) * AXIS[1]


def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _glint(img, zones, p: float):
    """A soft glint sliding along the back, snout to tail, over p = 0..1."""
    centre = -1.0 + 24.0 * p
    px = img.load()
    sx, sy = SHIFT
    for (X, Y), z in zones.items():
        x, y = X - sx, Y - sy
        depth = y - COLUMNS[x][0]
        if depth > 3 or z == "eye":
            continue
        k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.0) * 0.6 * (1.0 - depth / 4.5)
        if k > 0:
            _blend(px, X, Y, GLINT, k)


def _glitter(x: int, y: int) -> bool:
    """Scale points that flash as the sheen passes (a diamond lattice)."""
    return y % 2 == 1 and (x + y // 2) % 2 == 0


def _sheen(img, zones, p: float):
    """The rare blue-tinted sheen: a band rolling head to tail across the scales, with
    glittering scale points inside it."""
    centre = 1.0 + 22.0 * p
    px = img.load()
    sx, sy = SHIFT
    for (X, Y), z in zones.items():
        if z == "eye":
            continue
        k = max(0.0, 1.0 - abs(_along(X - sx, Y - sy) - centre) / 3.4)
        if k <= 0:
            continue
        spark = _glitter(X, Y) and z in ("flank", "back", "spot", "belly")
        strength = (0.9 if spark else 0.5) * min(1.0, k * 1.3)
        if z == "spot" and not spark:
            strength *= 0.5
        _blend(px, X, Y, SHEEN_SPARK if spark else SHEEN, strength)


# Sparkles around the fish: (x, y, phase) in frame coordinates. Each twinkles once per loop.
SPARKLES = [(26, 7, 0.0), (5, 22, 0.34), (19, 4, 0.67)]


def _twinkle(t: float, phase: float) -> float:
    """0 most of the loop, a smooth rise and fall over ~40% of it around phase."""
    d = ((t - phase + 0.5) % 1.0) - 0.5
    return math.cos(math.pi * d / 0.4) ** 1.5 if abs(d) < 0.2 else 0.0


def _frame(t: float) -> Image.Image:
    behind, pect = _fins(t)
    img = canvas(SIZE)
    _paint_fins(img, behind)
    body, zones = _body(pect)
    step = round(t * FRAMES)
    if step <= 5:
        _glint(body, zones, step / 5.0)
    else:
        _sheen(body, zones, (step - 6) / 5.0)
    img.alpha_composite(body)
    img = _outline(img)
    for x, y, ph in SPARKLES:
        amount = _twinkle(t, ph)
        sparkle(img, x, y, amount, colour=SPARK, reach=2)
        if amount > 0.55:
            img.putpixel((x, y), (255, 255, 255, 255))
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
