"""Whitefish: a common lake whitefish for the JoshyMC fishing collection.

A flat 32x32 sprite in the collection's pose: side view, head up-left, tail down-right,
lit from the top-left. It keeps the old icon's pale silver-white body with cool lavender
and mint tints and its touch of yellow (now the gold iris), redrawn as the real species: a
small head with a blunt snout over a tucked-in mouth, a big eye, the whitefish's hump
rising behind the head, a teal-sage back over a pearly sheen, large scales, a white belly
shaded lavender, a tall dorsal fin, the salmonid adipose fin, and a deeply forked tail.
Fins are translucent with painted rays and dusky tips.

Animation (common): 8 frames x 3 ticks. The forked tail sways a pixel toward the belly
and back, the pectoral, pelvic and anal fins flick in a wave from head to tail, the
dorsal tip leans with the beat, and a soft glint slides along the back and over the
dorsal fin from head to tail, then rests.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, shine, sprite

ID = "fish_whitefish"
NAME = "Whitefish"
KIND = "item"
MODEL_KEY = "fish/whitefish"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 8

# --------------------------------------------------------------------------------------
# Palettes (darkest -> lightest), hue-shifted: shadows lean indigo, lights lean mint/pearl
# --------------------------------------------------------------------------------------
OUTLINE = "#3a4160"          # silhouette outline
OUTLINE_LIT = "#4f5a7c"      # the same outline where it faces the light (top-left)
BACK = ["#4d5f73", "#5f7686", "#76919a", "#91aeac", "#afc9c1", "#cde0d6"]
SILVER = ["#646c90", "#8289ad", "#a3a8c8", "#c1c5dc", "#dbdeea", "#edf1f1", "#fbfdf9"]
BELLY = ["#a5a3c2", "#c6c4da", "#dcdae8", "#ecebf2", "#f7f6f8"]
FIN = ["#535c7c", "#76819e", "#9ea9bf", "#c5ceda", "#e3e9ee"]
EYE = ["#151827", "#2b3048", "#ffffff"]
IRIS = ["#9c7c3c", "#e0c173"]
RAMPS = {"BACK": BACK, "SILVER": SILVER, "BELLY": BELLY}

# Fin pixel roles -> (colour, alpha): L leading ray, f membrane, r ray, u dusky margin.
ROLE = {"L": (FIN[4], 222), "f": (FIN[3], 200), "r": (FIN[2], 214), "u": (mix(FIN[1], FIN[2], 0.35), 226)}
OVER = {"L": FIN[4], "f": FIN[3], "r": FIN[2], "u": FIN[1]}   # the pectoral fin seen over the body

# --------------------------------------------------------------------------------------
# Shape (pixel space)
# --------------------------------------------------------------------------------------


def _curve(points):
    """Uniform Catmull-Rom through evenly spaced (x, y) points."""
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]

    def f(x: float) -> float:
        if x <= xs[0]:
            return ys[0]
        if x >= xs[-1]:
            return ys[-1]
        i = 0
        while xs[i + 1] < x:
            i += 1
        p0, p1, p2, p3 = ys[max(i - 1, 0)], ys[i], ys[i + 1], ys[min(i + 2, len(ys) - 1)]
        t = (x - xs[i]) / (xs[i + 1] - xs[i])
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                      + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3)
    return f


# Back and belly edges every 2 px: a flat forehead over the eye, the nape rising steeply
# into the hump, then the back falling to a slim caudal peduncle.
_XS = [1.5 + 2 * i for i in range(13)]
TOP = _curve(list(zip(_XS, [14.0, 12.3, 11.9, 10.0, 8.9, 8.7, 8.8, 9.6, 11.0, 12.9, 15.2, 17.3, 18.6])))
BOT = _curve(list(zip(_XS, [14.6, 16.0, 16.9, 17.5, 18.0, 18.6, 19.2, 19.9, 20.6, 21.2, 21.4, 21.3, 21.3])))
X0, X1 = 2, 23

# Forked tail, hand-drawn from (24, 14); the lobes are symmetric about the tilted body axis.
TAIL_X, TAIL_Y = 24, 14
TAIL = [
    "....Lu",  # 14
    "..LLru",  # 15
    "LLrru.",  # 16
    "rrfu..",  # 17
    "ffu...",  # 18
    "rf....",  # 19  the fork
    "fru...",  # 20
    "Lfru..",  # 21
    ".Lfru.",  # 22
    "..Lru.",  # 23
    "...Lr.",  # 24
    "....u.",  # 25
]
TAIL_SWAY_X = 27            # columns from here on sway a pixel with the beat

# Dorsal fin, hand-drawn from (10, 5): rounded top, light leading ray, dusky trailing edge.
# Its tip leans back a pixel while the tail beats toward the back.
DORSAL_X, DORSAL_Y = 10, 5
DORSAL = [
    ".uu....",  # 5
    ".Lru...",  # 6
    "Lfrfu..",  # 7
    "Lfrfru.",  # 8
    ".....fu",  # 9
]
DORSAL_LEAN = "..uu..."   # row 5 while leaning

# Small fins as (x, y, role): pose 1 at rest, pose 2 flicked out.
PECTORAL = {1: [(8, 15, "r"), (9, 15, "L"), (9, 16, "r"), (10, 16, "f"), (11, 16, "u"), (10, 17, "r"),
                (11, 17, "f"), (12, 17, "u"), (12, 18, "u")],
            2: [(8, 15, "r"), (9, 15, "L"), (9, 16, "r"), (10, 16, "f"), (10, 17, "r"), (11, 17, "f"),
                (11, 18, "r"), (12, 18, "u"), (11, 19, "u"), (12, 19, "u")]}
PELVIC = {1: [(13, 19, "L"), (14, 20, "r"), (15, 20, "f"), (15, 21, "u")],
          2: [(13, 19, "L"), (14, 20, "r"), (15, 20, "f"), (16, 21, "u")]}
ANAL = {1: [(19, 21, "L"), (20, 21, "f"), (20, 22, "r"), (21, 22, "u"), (21, 23, "u")],
        2: [(19, 21, "L"), (20, 21, "f"), (20, 22, "r"), (21, 22, "u"), (22, 23, "u")]}
ADIPOSE = [(20, 13, "f"), (21, 13, "u"), (21, 14, "u")]

# Head details over the banded body colour: (x, y, colour).
HEAD = [
    (2, 14, SILVER[0]), (3, 15, SILVER[2]),                                # mouth tucked under the snout
    (8, 11, BACK[1]), (9, 12, BACK[2]), (9, 13, SILVER[3]), (9, 14, SILVER[3]),      # gill cover edge
    (8, 13, SILVER[6]), (8, 14, SILVER[5]), (7, 14, SILVER[6]),            # bright operculum plate
    (5, 13, EYE[2]), (6, 13, EYE[0]), (5, 14, EYE[0]), (6, 14, EYE[1]),    # 2x2 eye, catchlight to the light
    (7, 13, IRIS[0]), (7, 14, IRIS[1]),                                    # gold iris behind the pupil
]

# Light comes from the top-left: each band dims toward the tail at its own column,
# so there is no single seam.
FALLOFF = {"rim": 17, "back": 16, "sheen": 17, "sheen2": 21, "flank": 18, "belly": 19, "belly2": 22}

# The glint's band centre (px along the back) in each frame; None = resting.
GLINT_STEPS = [None, 4.0, 8.5, 13.0, 17.5, 22.0, None, None]


# --------------------------------------------------------------------------------------
# Painting
# --------------------------------------------------------------------------------------

def _body():
    """The body's pixels plus each column's top and bottom row."""
    cells = set()
    for x in range(X0, X1 + 1):
        top, bot = TOP(x + 0.5), BOT(x + 0.5)
        for y in range(SIZE):
            if top <= y + 0.5 <= bot:
                cells.add((x, y))
    tops = {x: min(y for (xx, y) in cells if xx == x) for x in range(X0, X1 + 1)}
    bots = {x: max(y for (xx, y) in cells if xx == x) for x in range(X0, X1 + 1)}
    return cells, tops, bots


def _bands(n):
    """Rows of back pigment under the rim, for a column n pixels deep."""
    return max(1, round(n * 0.3))


def _zone(x, y, tops, bots):
    """(ramp, index) for a body pixel, in bands that follow the silhouette: lit back rim,
    teal-sage back, pearly sheen, silver flank, white belly, lavender belly shade and rim."""
    top, bot = tops[x], bots[x]
    n = bot - top + 1
    i, j = y - top, bot - y
    back_rows = _bands(n)
    sheen_rows = 2 if (9 <= x <= 14 and n >= 10) else 1
    if i == 0:
        return "BACK", 4 - (x >= FALLOFF["rim"])
    if j == 0:
        return "BELLY", 0
    if i <= back_rows:
        k = (i - 1) / max(1, back_rows - 1) if back_rows > 1 else 0.5
        idx = [1, 2, 3][min(2, int(k * 2.99))]
        return "BACK", idx - (x >= FALLOFF["back"] and idx > 1)
    if i <= back_rows + sheen_rows:
        idx = 6 - (1 if i == back_rows + 2 else 0)
        return "SILVER", idx - (x >= FALLOFF["sheen"]) - (x >= FALLOFF["sheen2"])
    if j == 1:
        return "BELLY", 1
    if j == 2 and n >= 8:
        return "BELLY", 4 - (x >= FALLOFF["belly"]) - (x >= FALLOFF["belly2"])
    return "SILVER", 5 - (x >= FALLOFF["flank"])


def _body_colour(x, y, tops, bots):
    ramp, idx = _zone(x, y, tops, bots)
    i, j = y - tops[x], bots[x] - y
    # Large scales: one darker mark per scale on a diamond lattice that follows the back,
    # over the back and flank (not on the rims, the sheen or the head).
    if 10 <= x <= 22 and i > 0 and j > 1 and not (ramp == "SILVER" and idx >= 6):
        if i % 2 == 1 and (x + i) % 4 == 0:
            idx -= 1
    r = RAMPS[ramp]
    return r[max(0, min(len(r) - 1, idx))]


def _fin_roles(t: float):
    """Fin pixels -> role, for loop phase t (pectoral pixels are prefixed with P)."""
    sway = math.sin(2 * math.pi * t)
    roles = {}
    shift = int(round(sway))   # the outer tail sways one pixel toward the belly or the back
    for r, row in enumerate(TAIL):
        for c, ch in enumerate(row):
            if ch != ".":
                x, y = TAIL_X + c, TAIL_Y + r
                roles[(x, y + (shift if x >= TAIL_SWAY_X else 0))] = ch
    lean = sway < -0.35
    for r, row in enumerate(DORSAL):
        for c, ch in enumerate(DORSAL_LEAN if (lean and r == 0) else row):
            if ch != ".":
                roles[(DORSAL_X + c, DORSAL_Y + r)] = ch
    for x, y, r in PECTORAL[2 if math.sin(2 * math.pi * t + 1.3) > 0.35 else 1]:
        roles[(x, y)] = "P" + r
    for x, y, r in PELVIC[2 if math.sin(2 * math.pi * t + 0.6) > 0.35 else 1]:
        roles[(x, y)] = r
    for x, y, r in ANAL[2 if sway > 0.35 else 1]:
        roles[(x, y)] = r
    for x, y, r in ADIPOSE:
        roles[(x, y)] = r
    return roles


def _outline(img):
    """1 px outline around the whole silhouette, a touch lighter on the lit top-left."""
    px = img.load()
    filled = {(x, y) for y in range(SIZE) for x in range(SIZE) if px[x, y][3]}
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in filled:
                continue
            if any((x + dx, y + dy) in filled for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                lit = (x, y + 1) in filled or (x + 1, y) in filled
                shaded = (x, y - 1) in filled or (x - 1, y) in filled
                px[x, y] = rgba(OUTLINE_LIT if lit and not shaded else OUTLINE)


def _glint(img, t, tops, bots, body):
    """A soft glint sliding along the back and over the dorsal fin (kit shine() at low
    strength, masked to the back)."""
    centre = GLINT_STEPS[int(round(t * FRAMES)) % FRAMES]
    if centre is None:
        return img
    width, angle, run = 2.6, 15.0, 0.9
    a = math.radians(angle)
    dx, dy = math.cos(a), math.sin(a)
    lo = min(0.0, SIZE * dy)
    hi = SIZE * dx + max(0.0, SIZE * dy)
    # shine() puts its band at lo - 2w + (hi - lo + 4w) * t / run: pick the t for our centre
    tt = run * (centre * dx + 13.0 * dy - (lo - 2 * width)) / (hi - lo + 4 * width)
    lit = shine(img, tt, colour="#ffffff", width=width, strength=0.5, angle=angle, pause=1 - run)
    out = img.copy()
    src, dst = lit.load(), out.load()
    for (x, y) in body:
        if y - tops[x] <= _bands(bots[x] - tops[x] + 1) + 2:
            dst[x, y] = src[x, y]
    for r, row in enumerate(DORSAL):
        for c, ch in enumerate(row + "."):
            x, y = DORSAL_X + c, DORSAL_Y + r
            if src[x, y][3] and src[x, y][3] < 255:   # the translucent fin pixels, not its outline
                dst[x, y] = src[x, y]
    return out


def _frame(t: float):
    img = canvas(SIZE)
    px = img.load()
    body, tops, bots = _body()
    for (x, y) in body:
        px[x, y] = rgba(_body_colour(x, y, tops, bots))
    for x, y, colour in HEAD:
        px[x, y] = rgba(colour)
    for (x, y), role in _fin_roles(t).items():
        if role.startswith("P") and (x, y) in body:
            px[x, y] = rgba(mix(px[x, y], OVER[role[1]], 0.7))    # translucent fin over the flank
        elif (x, y) not in body:
            colour, alpha = ROLE[role[-1]]
            c = rgba(colour)
            px[x, y] = (c[0], c[1], c[2], alpha)
    _outline(img)
    return _glint(img, t, tops, bots, body)


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=3)


def models() -> dict:
    return {"main": sprite("fish")}
