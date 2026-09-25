"""The Uncatchable: the mythical fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection's shared pose (side view, head up-left, tail
down-right), keeping the old sprite's all-scarlet scheme with its coral-orange heart. A
sleek, tuna-built speedster: a pointed snout, a deep crimson back under a hot rim light,
scarlet flanks with a glowing coral core and a golden speed stripe, a countershaded
peach belly, a swept-back sickle dorsal, a scythe pectoral, golden finlets along the
peduncle and a razor crescent tail. Fins are translucent crimson with painted rays.

Animation (MYTHICAL, 24 frames x 2 ticks): the crescent tail beats twice per loop and the
fins sway; a golden rim glow breathes just outside the outline, two sparkles orbit the
fish and its eye radiates. The loop runs through a glint along the back, an iridescent
(violet-pink-cyan) sheen rolling over the scales, and then the signature: the fish
bursts into a sprint, wind streaks rip past it and fading scarlet afterimages peel off
behind it, as if it has already slipped the hook.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, shade, shine, sparkle, sprite, wave

ID = "fish_the_uncatchable"
NAME = "The Uncatchable"
KIND = "item"
MODEL_KEY = "fish/the_uncatchable"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 24
FRAMETIME = 2
SHIFT = (1, 1)            # the art below is drawn one pixel left / one up of centre

# ---- palette: hue-shifted ramps (shadows lean magenta-violet, lights lean gold) -------------
RIM = ["#ffa071", "#ff7a5a"]                       # back rim light: head, tailward
BACK = ["#8a1538", "#b01d3b"]                      # deep crimson back
FLANK = ["#d92a3c", "#f0443d"]                     # scarlet flank, lit flank
CORE = "#ff6d45"                                   # the old sprite's coral heart
LOW = "#ff8c5b"                                    # warm lower flank
BELLY = ["#ea7863", "#ff9e7c", "#ffc3a0"]          # shadow, belly, highlight
HEAD = ["#c52340", "#ea3a3f", "#ff6a4d"]           # head shadow, head, lit head
STRIPE = ["#f5a53c", "#ffd36e"]                    # golden speed stripe
OUTLINE = "#3d0a24"
OUTLINE_LIT = "#5a0f2c"
FIN = {"ray": "#8e1535", "ray2": "#b3203c", "tip": "#ff9a5e", "base": "#b41f3d",
       # (colour, alpha) by steps from the body: crimson roots warming to orange tips
       "ramp": [("#aa1c3d", 228), ("#d02c3e", 214), ("#e83b40", 206), ("#f84e42", 206),
                ("#ff6446", 210), ("#ff7a4c", 214)]}
FINLET = ["#e0a02c", "#ffe07a"]
PUPIL, CATCH, IRIS_GOLD = "#1c0714", "#fff8ec", "#ffc94d"
GOLD = "#ffdc4a"
GLINT = "#fff1d8"
SPARK = "#fff9e8"
IRIDESCENT = ["#b070ff", "#ff6fd2", "#66e6ff"]
SPEED = "#fff0ea"
GHOST = ["#ff4a5c", "#ff7a8e"]
BLUE_SHEEN = "#bfe6ff"

EDGE_ALPHA, RAY_ALPHA, FIN_OUTLINE_ALPHA = 220, 236, 232

# ---- the art (x 0..31 as drawn; SHIFT moves it to centre) -----------------------------------
# x: body (shaded from its position between the back and belly edges)
# E/e: pupil / catchlight, m: mouth, j: pale lower jaw, n: gill cover edge
# f/r: dorsal fin membrane / ray, a: anal fin, y: golden finlets
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "...............ff...............",  # 3
    ".............frff...............",  # 4
    "...........ffrfr................",  # 5
    ".........fffrfr.................",  # 6
    ".....xxxxxfrfr..................",  # 7
    "...xxxxxxxxxff..................",  # 8
    "..xxeExxnxxxxxf.................",  # 9
    ".xxxEExnxxxxxxx.................",  # 10
    ".jmmxxxnxxxxxxxx................",  # 11
    "..jjxxxnxxxxxxxxx...............",  # 12
    "...xxxnxxxxxxxxxxx..............",  # 13
    ".....xxxxxxxxxxxxxx.............",  # 14
    ".......xxxxxxxxxxxxxy...........",  # 15
    ".........xxxxxxxxxxxx...........",  # 16
    "...........xxxxxxxxxxxy.........",  # 17
    ".............xxxxxxxxxx.........",  # 18
    "..............aaxxxxxxxx........",  # 19
    "...............aaa.xxxxx........",  # 20
    ".................aa..y..........",  # 21
    "................................",  # 22
]
# The crescent tail (neutral pose), absolute coordinates as drawn; T = rays.
TAIL = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    "................................",  # 6
    "................................",  # 7
    "................................",  # 8
    "................................",  # 9
    "................................",  # 10
    "...........................tt...",  # 11
    "..........................tTt...",  # 12
    "..........................tTt...",  # 13
    ".........................tTt....",  # 14
    ".........................tTt....",  # 15
    "........................tTt.....",  # 16
    "........................ttt.....",  # 17
    "........................tt......",  # 18
    "........................tt......",  # 19
    "........................tt......",  # 20
    ".......................ttt......",  # 21
    ".......................tTtt.....",  # 22
    "........................tTt.....",  # 23
    "........................tTtt....",  # 24
    ".........................tTt....",  # 25
    "..........................tt....",  # 26
    "...........................t....",  # 27
]
TAIL_ROOT = (24.0, 19.5)
# Pectoral poses over the flank (p membrane, P dark trailing edge) and pelvic poses.
PECT = {
    "spread": [(8, 12, "p"), (9, 12, "p"), (9, 13, "P"), (10, 13, "p"), (11, 13, "p"), (11, 14, "P"),
               (12, 14, "p"), (13, 15, "P")],
    "folded": [(8, 12, "p"), (9, 12, "p"), (10, 12, "p"), (9, 13, "P"), (10, 13, "P"), (11, 13, "p"),
               (12, 13, "p"), (13, 14, "P")],
}
PELVIC = {"rest": [(7, 16), (8, 16), (8, 17), (9, 17)],
          "swept": [(8, 16), (9, 17), (10, 17), (8, 17)]}
AXIS = (math.cos(math.radians(30)), math.sin(math.radians(30)))
NORMAL = (-AXIS[1], AXIS[0])       # toward the belly


def _grid(rows) -> dict:
    return {(x, y): ch for y, row in enumerate(rows) for x, ch in enumerate(row) if ch != "."}


STATIC = _grid(ART)
TAIL_PX = _grid(TAIL)
BODY_LETTERS = set("xEemjn")
COLUMNS = {}
for (_x, _y), _ch in STATIC.items():
    if _ch in BODY_LETTERS:
        lo, hi = COLUMNS.get(_x, (99, -1))
        COLUMNS[_x] = (min(lo, _y), max(hi, _y))


# The golden speed stripe: one pixel per column, stepping down along the lateral line.
STRIPE_Y = {8: 11, 9: 11, 10: 12, 11: 12, 12: 13, 13: 13, 14: 14, 15: 14, 16: 15, 17: 16, 18: 16,
            19: 17, 20: 18, 21: 18}


def _stripe(x: int, y: int) -> bool:
    return STRIPE_Y.get(x) == y


def _tail(angle: float) -> dict:
    """The tail rotated about its root by `angle` (radians), sampled back to the source so it
    stays gap free; the tips swing the most."""
    if abs(angle) < 1e-6:
        return dict(TAIL_PX)
    out = {}
    ca, sa = math.cos(-angle), math.sin(-angle)
    rx, ry = TAIL_ROOT
    for y in range(8, 31):
        for x in range(22, 31):
            dx, dy = x + 0.5 - rx, y + 0.5 - ry
            sx, sy = rx + dx * ca - dy * sa, ry + dx * sa + dy * ca
            key = (math.floor(sx), math.floor(sy))
            if key in TAIL_PX:
                out[(x, y)] = TAIL_PX[key]
    for key in TAIL_PX:         # the root never moves
        if math.hypot(key[0] + 0.5 - rx, key[1] + 0.5 - ry) < 1.6:
            out[key] = TAIL_PX[key]
    return out


def _compose(t: float):
    wag = math.sin(2 * math.pi * 2 * t)
    layer = dict(STATIC)
    for key, ch in _tail(math.radians(6.5) * wag).items():
        layer.setdefault(key, ch)
    pect = "folded" if math.sin(2 * math.pi * 2 * t + 1.2) > 0.25 else "spread"
    pelvic = "swept" if math.sin(2 * math.pi * 2 * t + 2.4) > 0 else "rest"
    for x, y in PELVIC[pelvic]:
        layer.setdefault((x, y), "a")
    return layer, {(x, y): part for x, y, part in PECT[pect]}


def _body_colour(x: int, y: int, ch: str):
    """Shade a body pixel from its place between the back (k = 0) and belly (k = 1)."""
    lo, hi = COLUMNS[x]
    k = (y - lo) / max(1, hi - lo)
    head = x <= 6 or (x == 7 and y <= 12)
    if ch == "E":
        return PUPIL, "eye"
    if ch == "e":
        return CATCH, "eye"
    if ch == "m":
        return OUTLINE_LIT, "mouth"
    if ch == "j":
        return BELLY[2], "head"
    if ch == "n":
        return (BACK[0] if k < 0.5 else BELLY[0]), "head"
    if y == lo:
        return (RIM[0] if x <= 12 else RIM[1]), "back"
    if _stripe(x, y):
        return (STRIPE[1] if x <= 14 else STRIPE[0]), "stripe"
    if head:
        if k < 0.34:
            return HEAD[1], "head"
        if k < 0.62:
            return HEAD[2], "head"
        if k < 0.85:
            return BELLY[1], "head"
        return BELLY[0], "head"
    if k < 0.3:
        return BACK[0] if x >= 9 else BACK[1], "back"
    if k < 0.44:
        return BACK[1] if x >= 12 else FLANK[0], "back"
    if k < 0.62:
        if 9 <= x <= 17 and k >= 0.47:
            return CORE, "flank"
        return FLANK[1] if x < 19 else FLANK[0], "flank"
    if k < 0.75:
        return LOW, "flank"
    if k < 0.9:
        return (BELLY[2] if 7 <= x <= 13 else BELLY[1]), "belly"
    return BELLY[0], "belly"


def _scale_mark(x: int, y: int) -> bool:
    return y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1


def _fin_distance(layer: dict, body: set) -> dict:
    """Steps from the body for every fin pixel (4-connected), so fins warm toward the tips."""
    dist = {k: 0 for k in body}
    edge = list(body)
    while edge:
        nxt = []
        for x, y in edge:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                k = (x + dx, y + dy)
                if k in layer and k not in dist:
                    dist[k] = dist[(x, y)] + 1
                    nxt.append(k)
        edge = nxt
    return dist


def _paint(layer: dict, pect: dict):
    """Paint the letters (drawn coordinates) into a frame. Returns image, zone map (frame
    coordinates) and the set of outline pixels."""
    img = canvas(SIZE)
    px = img.load()
    sx, sy = SHIFT
    zones = {}
    body = {k for k, ch in layer.items() if ch in BODY_LETTERS}
    dist = _fin_distance(layer, body)
    for (x, y), ch in layer.items():
        X, Y = x + sx, y + sy
        if ch in BODY_LETTERS:
            colour, zone = _body_colour(x, y, ch)
            if zone == "flank" and _scale_mark(X, Y):
                colour = shade(colour, -0.14, 0.12)
            if (x, y) in pect:
                part = pect[(x, y)]
                colour = mix(colour, "#ff9d6a" if part == "p" else FIN["base"], 0.62 if part == "p" else 0.8)
                zone = "pect"
            px[X, Y] = rgba(colour)
            zones[(X, Y)] = zone
            continue
        if ch == "y":
            px[X, Y] = rgba(FINLET[1] if (x + y) % 2 else FINLET[0])
            zones[(X, Y)] = "finlet"
            continue
        zone = "tail" if ch in "tT" else "fin"
        d = dist.get((x, y), 1)
        empty = sum(layer.get((x + dx, y + dy)) is None for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        if ch in "rT":
            colour, alpha = (FIN["ray"] if d <= 3 else FIN["ray2"]), RAY_ALPHA
        elif empty >= 2 and d >= 3:
            colour, alpha = FIN["tip"], EDGE_ALPHA
        else:
            colour, alpha = FIN["ramp"][min(d, len(FIN["ramp"])) - 1]
        c = rgba(colour)
        px[X, Y] = (c[0], c[1], c[2], alpha)
        zones[(X, Y)] = zone
    filled = set(zones)
    solid = {(x + sx, y + sy) for (x, y) in body} | {k for k, z in zones.items() if z == "finlet"}
    dark, lit = rgba(OUTLINE), rgba(OUTLINE_LIT)
    ring = set()
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in filled:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + dx, y + dy) in filled]
            if not near:
                continue
            facing = ((x, y + 1) in filled or (x + 1, y) in filled) and (x, y - 1) not in filled and (x - 1, y) not in filled
            o = lit if facing else dark
            px[x, y] = o if any(p in solid for p in near) else (o[0], o[1], o[2], FIN_OUTLINE_ALPHA)
            ring.add((x, y))
    return img, zones, ring


def _along(x: float, y: float) -> float:
    """Distance along the body axis from the snout, in frame pixels."""
    return (x + 0.5 - 2.0) * AXIS[0] + (y + 0.5 - 11.5) * AXIS[1]


BODY_ZONES = ("back", "flank", "belly", "stripe", "head", "pect")
# Timeline (frame numbers of the 24-frame loop).
GLINT_FRAMES = (1, 5)          # a soft glint slides along the back
IRIS_FRAMES = (7, 12)          # the iridescent sheen rolls over the scales
DASH_FRAMES = (14, 21)         # the signature sprint: streaks, afterimages, cold sheen
# Wind lanes in the empty space around the fish (frame coords, a point on the lane) and
# the pass phase of each.
LANES = [((19.0, 8.5), 0.00, 5, True), ((23.5, 5.5), 0.55, 4, False), ((7.0, 20.5), 0.30, 5, True),
         ((11.0, 25.5), 0.80, 4, False), ((4.5, 5.0), 0.15, 3, False)]
ORBIT_CENTRE = (16.0, 16.5)


def _dash(step: int) -> float:
    """0..1 envelope of the sprint over its frames (0 outside them)."""
    lo, hi = DASH_FRAMES
    if not lo <= step <= hi:
        return 0.0
    return math.sin(math.pi * (step - lo + 1) / (hi - lo + 2)) ** 0.8


def _blend(px, x, y, colour, k, alpha=None):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k),
                a if alpha is None else alpha)


def _put_empty(px, x, y, colour, alpha):
    if 1 <= x < SIZE - 1 and 1 <= y < SIZE - 1 and alpha > 0:
        r, g, b, a = px[x, y]
        if a == 0:
            c = rgba(colour)
            px[x, y] = (c[0], c[1], c[2], min(255, round(alpha)))
            return True
    return False


def _frame(t: float):
    step = round(t * FRAMES)
    layer, pect = _compose(t)
    img, zones, ring = _paint(layer, pect)
    px = img.load()
    body = {k for k, z in zones.items() if z in BODY_ZONES}
    dash = _dash(step)

    # 1) glint along the back, snout to tail
    lo, hi = GLINT_FRAMES
    if lo <= step <= hi:
        centre = 6.0 + 22.0 * (step - lo) / (hi - lo)
        width = 3.5
        run = (centre + 2 * width) / (32 * AXIS[0] + 32 * AXIS[1] + 4 * width)
        glint = shine(img, run, colour=GLINT, width=width, strength=0.55, angle=30.0, pause=0.0).load()
        for (x, y), z in zones.items():
            if z in ("back", "head", "fin"):
                px[x, y] = glint[x, y]

    # 2) iridescent sheen: every scale flashes violet / pink / cyan, the colours rolling
    #    head to tail, under a soft band of the same hues
    lo, hi = IRIS_FRAMES
    if lo <= step <= hi:
        tau = (step - lo + 1) / (hi - lo + 2)
        env = math.sin(math.pi * tau)
        centre = 6.0 + 20.0 * (step - lo) / (hi - lo)
        for (x, y) in body:
            if zones[(x, y)] in ("eye", "head"):
                continue
            al = _along(x, y)
            hue = IRIDESCENT[int(al / 2.5 - 6 * tau) % 3]
            d = abs(al - centre)
            band = max(0.0, 1.0 - d / 4.0)
            spot = (_scale_mark(x, y) or _scale_mark(x + 2, y + 1)) and zones[(x, y)] in ("flank", "back", "belly")
            if spot:
                _blend(px, x, y, SPARK if band > 0.7 else hue, (0.85 if band > 0.7 else 0.7) * env)
            elif band > 0:
                _blend(px, x, y, hue, 0.3 * band * env)

    # 3) the sprint: a cold sheen races over the body ...
    if dash > 0:
        lo, hi = DASH_FRAMES
        centre = -2.0 + 34.0 * (step - lo) / (hi - lo)
        for (x, y) in body:
            if zones[(x, y)] == "eye":
                continue
            d = abs(_along(x, y) - centre)
            if d < 3.0 and zones[(x, y)] in ("back", "head", "flank", "stripe"):
                _blend(px, x, y, BLUE_SHEEN, 0.42 * (1 - d / 3.0) * dash)

    # radiant eye: a golden ring breathing with the rim glow, a bright catchlight
    glow = wave(t)
    eye = [k for k, z in zones.items() if z == "eye"]
    ex, ey = min(eye)
    for x, y in eye:
        if (x, y) == (ex, ey):
            px[x, y] = rgba(mix(CATCH, "#ffffff", glow))
    for x, y in {(ex + dx, ey + dy) for dx in (-1, 0, 1, 2) for dy in (-1, 0, 1, 2)} - set(eye):
        if zones.get((x, y)) in BODY_ZONES and (x in (ex, ex + 1) or y in (ey, ey + 1)):
            _blend(px, x, y, IRIS_GOLD, 0.25 + 0.5 * glow)

    silhouette = set(zones) | ring
    # ... afterimages of the fish peel off behind it ...
    if dash > 0:
        lo, hi = DASH_FRAMES
        tau = (step - lo) / (hi - lo)
        source = img.copy().load()
        for j, (colour, strength) in enumerate(zip(GHOST, (0.74, 0.42))):
            off = (j + 1) * (2.2 + 2.2 * tau)
            dx, dy = round(off * AXIS[0]), round(off * AXIS[1])
            for (x, y) in silhouette:
                r, g, b, a = source[x, y]
                tint = mix((r, g, b, 255), colour, 0.5)
                _put_empty(px, x + dx, y + dy, tint, a * strength * dash)

    # ... and wind streaks rip past (always a breeze, a gale during the sprint)
    for (p0, phase, length, calm) in LANES:
        s0 = -7.0 + 20.0 * ((2 * t + phase) % 1.0)
        base = (60 if calm else 0) + (210 - (60 if calm else 0)) * dash
        if base <= 0:
            continue
        for i in range(length + round(3 * dash)):
            sp = s0 - i
            x = math.floor(p0[0] + sp * AXIS[0])
            y = math.floor(p0[1] + sp * AXIS[1])
            k = 1.0 - i / (length + 4)
            _put_empty(px, x, y, SPEED, base * k)

    # golden rim glow breathing just outside the outline
    filled = {(x, y) for x in range(SIZE) for y in range(SIZE) if px[x, y][3] > 0 and (x, y) in silhouette}
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in silhouette:
                continue
            if any((x + dx, y + dy) in ring for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                a = px[x, y][3]
                alpha = 8 + 132 * glow
                if a == 0:
                    c = rgba(GOLD)
                    px[x, y] = (c[0], c[1], c[2], round(alpha))
                else:
                    _blend(px, x, y, GOLD, 0.25 + 0.35 * glow, max(a, round(alpha)))

    # orbiting sparkles: in front of the fish along the belly side of their path, behind it
    # elsewhere (so they never land on the eye)
    for j in range(2):
        phi = 2 * math.pi * (t + j * 0.5)
        ox = ORBIT_CENTRE[0] + 14.5 * math.cos(phi) * AXIS[0] + 10.5 * math.sin(phi) * NORMAL[0]
        oy = ORBIT_CENTRE[1] + 14.5 * math.cos(phi) * AXIS[1] + 10.5 * math.sin(phi) * NORMAL[1]
        x, y = round(ox), round(oy)
        amount = 0.5 + 0.5 * math.sin(2 * math.pi * (3 * t + j * 0.33))
        if not (2 <= x <= 29 and 2 <= y <= 29):
            continue
        if math.sin(phi) >= 0.35:
            sparkle(img, x, y, amount, colour=SPARK, reach=2)
            continue
        star = canvas(SIZE)          # behind the fish: only where the silhouette is not
        sparkle(star, x, y, amount, colour=SPARK, reach=2)
        spx = star.load()
        for sy_ in range(max(0, y - 2), min(SIZE, y + 3)):
            for sx_ in range(max(0, x - 2), min(SIZE, x + 3)):
                a = spx[sx_, sy_][3]
                if a and (sx_, sy_) not in filled:
                    _blend(px, sx_, sy_, SPARK, a / 255, max(px[sx_, sy_][3], a))
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
