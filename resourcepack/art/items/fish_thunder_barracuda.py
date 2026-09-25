"""Thunder Barracuda: an epic ocean fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's electric-yellow scheme and its lightning marking. A long torpedo
barracuda: flat-topped pointed head with a jutting, fanged lower jaw, a big eye over a
golden iris and the gill cover, an amber back crossed by storm-dark bars under a lit rim,
an orange lateral band split by a white-hot zigzag lightning bolt with an electric-blue
fringe, bright yellow lower flanks with scale marks over a countershaded cream belly, the
two widely spaced dorsal fins, low pectoral, pelvic and anal fins and a forked tail (fins
translucent amber with painted rays and a softer outline).

Animation (EPIC, 16 frames x 2 ticks): the tail swings between three hand-drawn poses and
the pectoral and pelvic fins sway a pixel; an iridescent purple / pink / cyan sheen rolls
head to tail once per loop, glittering on a lattice of scales; a glowing spark of charge
runs down the lightning bolt (frames 0-7) and a soft glint slides along the back (9-14);
three electric sparkles twinkle around the fish on staggered phases.
"""
from __future__ import annotations

import math

from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, shine, sparkle, sprite, wave

ID = "fish_thunder_barracuda"
NAME = "Thunder Barracuda"
KIND = "item"
MODEL_KEY = "fish/thunder_barracuda"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 16
FRAMETIME = 2
SHIFT = (1, -1)          # the art below is drawn one pixel left and one low of centre

# ---- palettes (darkest -> lightest): shadows lean red-violet, lights lean lemon -----------
OUTLINE = "#471f2c"
OUTLINE_LIT = "#5c2a2c"
BACK = ["#6a2618", "#8f3a1a", "#b8581c", "#cf6c1e", "#f0b93a", "#fbd862"]
GOLD = ["#e39c27", "#efb12e", "#f5c236", "#f8cf45", "#fff09a"]
BELLY = ["#e6c070", "#f6e0a0", "#fdf0c4", "#fffae6"]
BOLT = ["#fff38a", "#fff5c4", "#ffffff", "#a9e6f4"]      # lemon tip, core, white-hot, blue fringe
FIN = ["#9c5516", "#c0781c", "#dc9d2e", "#ecbd48", "#f7d977", "#fcecac"]
EYE = ["#1c0d18", "#ffffff", "#f2b82e", "#a8561a"]
GLINT = "#fffbe0"
CHARGE = ["#ffffff", "#e8fcff", "#fff6b4"]              # pulse core, fringe flash, halo
IRIDESCENT = ["#a57aff", "#ff78d2", "#74f0ff"]          # purple -> pink -> cyan
SPARKS = ["#fffbd2", "#c4f6ff", "#ffe488"]

LETTERS = {
    # back: rim light (head / body), dark back, storm bars
    "R": BACK[5], "r": BACK[4], "B": BACK[1], "X": BACK[0],
    # flanks: orange lateral band, gold scale mark, bright gold
    "c": BACK[3], "G": GOLD[1], "h": GOLD[3],
    # belly (countershaded)
    "v": BELLY[0], "u": BELLY[1], "w": BELLY[2], "W": BELLY[3],
    # lightning bolt: lemon tips, core, white-hot kinks, electric fringe
    "y": BOLT[0], "z": BOLT[1], "Z": BOLT[2], "q": BOLT[3],
    # head: eye, catchlight, iris, mouth line, fang, gill cover
    "E": EYE[0], "e": EYE[1], "i": EYE[2], "I": EYE[3], "m": OUTLINE, "k": "#fffdf2", "n": BACK[1],
}
ZONES = {"R": "back", "r": "back", "B": "back", "X": "back",
         "c": "flank", "G": "flank", "h": "flank", "q": "fringe",
         "v": "belly", "u": "belly", "w": "belly", "W": "belly",
         "y": "bolt", "z": "bolt", "Z": "bolt",
         "E": "eye", "e": "eye", "i": "eye", "I": "eye", "m": "line", "k": "head", "n": "line"}
SCALED = {"h": "G", "W": "u", "w": "u"}      # letter -> its scale-mark letter

# ---- the art (as drawn; SHIFT moves it to centre) ---------------------------------------------
# Body, head, dorsal fins, anal fin. Letters not in LETTERS are fins: F/f dorsal ray and
# membrane, A/a lower ray and membrane. The tail, pelvic and pectoral fins are separate
# layers so they can sway.
ART = [
    "..........F.....................",  # 8
    "...RRRRR..Ff....................",  # 9
    "..rccBeERRFff...................",  # 10
    ".wmkmhEEXBRRff..................",  # 11
    "...vWWiInyBXrr..................",  # 12
    "....vvWWnczcBBrr.F..............",  # 13
    "......vWhhczccXBrFf.............",  # 14
    ".......vWhhhzcccBrrf............",  # 15
    "........vWWhhZczcXBr............",  # 16
    ".........vvWWhhhzczBrr..........",  # 17
    "...........vvwwwhZczXBr.........",  # 18
    ".............vvvwwwwycB.........",  # 19
    "................vvvvwww.........",  # 20
    "................Aaa.vvv.........",  # 21
    ".................a..............",  # 22
]
ART_TOP = 8
# The bolt, head to tail: 45-degree strokes across the band with sharp kinks back up.
BOLT_PATH = [(9, 12), (10, 13), (11, 14), (12, 15), (13, 16), (14, 15), (15, 16), (16, 17), (17, 18), (18, 17),
             (19, 18), (20, 19)]
PELVIC = {"rest": {(10, 18): "A", (11, 19): "a", (12, 19): "a"},
          "swept": {(10, 18): "A", (11, 19): "A", (12, 19): "a", (13, 20): "a"}}
# Pectoral overlays on the lower flank: "p" membrane, "P" dark trailing edge.
PECT = {"spread": {(9, 15): "p", (10, 15): "p", (10, 16): "p", (11, 16): "P", (11, 17): "P"},
        "folded": {(9, 15): "p", (10, 15): "p", (11, 15): "p", (10, 16): "P", (11, 16): "P"}}
# Forked tail in three poses (as drawn, rows from TAIL_AT): neutral, swung clockwise and
# swung counter-clockwise about its root. T membrane, Q rays.
TAIL_AT = (22, 14)
TAIL_POSES = {
    "mid": [
        "........",  # 14
        "........",  # 15
        ".....TT.",  # 16
        ".TTTTT..",  # 17
        ".TQQQT..",  # 18
        ".TTT....",  # 19
        ".TT.....",  # 20
        ".TTT....",  # 21
        ".TQTT...",  # 22
        "..TQT...",  # 23
        "..TQT...",  # 24
        "...TQ...",  # 25
        "...TT...",  # 26
    ],
    "cw": [
        "........",  # 14
        "........",  # 15
        "........",  # 16
        ".TTTTTT.",  # 17
        ".TQQQT..",  # 18
        ".TTT....",  # 19
        ".TT.....",  # 20
        ".TT.....",  # 21
        ".TQT....",  # 22
        ".TQT....",  # 23
        "..QT....",  # 24
        "..TT....",  # 25
        "..T.....",  # 26
    ],
    "ccw": [
        "........",  # 14
        "......T.",  # 15
        "....TQT.",  # 16
        ".TTTQT..",  # 17
        ".TQTT...",  # 18
        ".TTT....",  # 19
        ".TT.....",  # 20
        ".TTTT...",  # 21
        ".TTQT...",  # 22
        "..TTQT..",  # 23
        "...TQT..",  # 24
        "....TT..",  # 25
        "........",  # 26
    ],
}
AXIS = (math.cos(math.atan(0.5)), math.sin(math.atan(0.5)))
SNOUT = (1.5, 11.5)                      # as drawn


def _scale_mark(x: int, y: int) -> bool:
    """A sparse diamond lattice for scale marks."""
    return y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1


def _parse():
    body, fins = {}, {}
    for j, row in enumerate(ART):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            if ch in LETTERS:
                if ch in SCALED and _scale_mark(x, ART_TOP + j):
                    ch = SCALED[ch]
                body[(x, ART_TOP + j)] = ch
            else:
                fins[(x, ART_TOP + j)] = ch
    # an electric-blue fringe under the bolt so the white core pops off the gold
    for x, y in BOLT_PATH:
        q = (x, y + 1)
        if q in body and q not in BOLT_PATH and body[q] in ("c", "h", "G"):
            body[q] = "q"
    return body, fins


BODY, STATIC_FINS = _parse()
BOLT_T = {p: i / (len(BOLT_PATH) - 1) for i, p in enumerate(BOLT_PATH)}


def _along(x: float, y: float) -> float:
    """Distance along the body axis from the snout (drawn coordinates)."""
    return (x + 0.5 - SNOUT[0]) * AXIS[0] + (y + 0.5 - SNOUT[1]) * AXIS[1]


def _tail(wag: float) -> dict:
    """The tail pose for a wag phase (-1..1): pixel -> "T" (membrane) or "Q" (ray)."""
    pose = "cw" if wag > 0.5 else "ccw" if wag < -0.5 else "mid"
    return {(TAIL_AT[0] + i, TAIL_AT[1] + j): ch for j, row in enumerate(TAIL_POSES[pose])
            for i, ch in enumerate(row) if ch != "."}


def _fin_colour(ch: str, near, x: int, y: int) -> tuple[str, int]:
    """Membrane / ray colours. near = (right, left, below, above) neighbours ("body", "fin"
    or None). Darker roots at the body, edges facing the top-left light lit, the rest of
    the membrane warming toward pale gold at the tail tips."""
    lit = near[1] is None or near[3] is None
    if ch in ("F", "A", "Q"):
        return (FIN[4] if ch == "F" else FIN[1]), 232
    if "body" in near:
        return FIN[1], 222
    if ch == "T":
        out = min(1.0, math.hypot(x + 0.5 - 22.5, y + 0.5 - 20.0) / 6.0)
        if lit:
            return mix(FIN[3], FIN[5], out), 206
        if None in near:
            return mix(FIN[2], FIN[3], out), 204
        return mix(FIN[1], FIN[3], out), 214
    if lit:
        return FIN[4], 204
    if None in near:
        return FIN[3], 200
    return FIN[2], 210


def _compose(t: float):
    wag = math.sin(2 * math.pi * t)
    pect = "folded" if math.sin(2 * math.pi * 2 * t + 1.0) > 0.3 else "spread"
    pelvic = "swept" if math.sin(2 * math.pi * t + 2.0) > 0 else "rest"
    fins = {}
    for part in (_tail(wag), STATIC_FINS, PELVIC[pelvic]):
        for key, ch in part.items():
            if key not in BODY:
                fins.setdefault(key, ch)
    return fins, PECT[pect]


def _paint(fins: dict, pect: dict):
    """Paint body and fins (display coordinates) with the outline; returns the image and a
    map of display pixel -> (zone, drawn pixel)."""
    img = canvas(SIZE)
    px = img.load()
    sx, sy = SHIFT
    zones = {}
    for (x, y), ch in BODY.items():
        colour, zone = LETTERS[ch], ZONES[ch]
        if (x, y) in pect and zone not in ("bolt", "fringe"):
            part = pect[(x, y)]
            colour = mix(colour, FIN[4] if part == "p" else FIN[0], 0.55 if part == "p" else 0.6)
            zone = "pect"
        px[x + sx, y + sy] = rgba(colour)
        zones[(x + sx, y + sy)] = (zone, (x, y))
    for (x, y), ch in fins.items():
        near = []
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            near.append("body" if q in BODY else ("fin" if q in fins else None))
        colour, alpha = _fin_colour(ch, near, x, y)
        c = rgba(colour)
        px[x + sx, y + sy] = (c[0], c[1], c[2], alpha)
        zones[(x + sx, y + sy)] = ("fin", (x, y))
    # 1 px outline: solid beside the body, a touch translucent where it only borders fins,
    # a little lighter on the side facing the top-left light
    filled = set(zones)
    solid = {(x + sx, y + sy) for (x, y) in BODY}
    dark, lit = rgba(OUTLINE), rgba(OUTLINE_LIT)
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in filled:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + dx, y + dy) in filled]
            if not near:
                continue
            facing_light = ((x, y + 1) in filled or (x + 1, y) in filled) and \
                (x, y - 1) not in filled and (x - 1, y) not in filled
            o = lit if facing_light else dark
            px[x, y] = o if any(p in solid for p in near) else (o[0], o[1], o[2], 228)
    return img, zones


def _charge(img: Image.Image, zones: dict, pos: float) -> None:
    """A bright spark at `pos` (0..1) running down the bolt: a lemon-white glow around its
    head, the bolt white-hot behind it with a fading afterglow and the blue fringe flashing."""
    px = img.load()
    sx, sy = SHIFT
    for (x, y), t in BOLT_T.items():
        if t <= pos:
            k = max(0.0, 1.0 - (pos - t) / 0.5)
        else:
            k = max(0.0, 1.0 - (t - pos) / 0.1)
        if k <= 0:
            continue
        X, Y = x + sx, y + sy
        px[X, Y] = rgba(mix(px[X, Y], CHARGE[0], k))
        below = (X, Y + 1)
        if below in zones and zones[below][0] == "fringe":
            px[below] = rgba(mix(px[below], CHARGE[1], 0.85 * k))
    # the glowing head of the spark
    f = max(0.0, min(1.0, pos)) * (len(BOLT_PATH) - 1)
    i = min(int(f), len(BOLT_PATH) - 2)
    (ax, ay), (bx, by) = BOLT_PATH[i], BOLT_PATH[i + 1]
    hx, hy = ax + (bx - ax) * (f - i) + sx + 0.5, ay + (by - ay) * (f - i) + sy + 0.5
    if not 0.0 <= pos <= 1.0:
        return
    for (X, Y), (zone, drawn) in zones.items():
        if zone in ("eye", "line", "fin") or drawn in BOLT_T:
            continue
        dist = math.hypot(X + 0.5 - hx, Y + 0.5 - hy)
        if dist < 1.8:
            px[X, Y] = rgba(mix(px[X, Y], CHARGE[2], 0.75 * (1.0 - dist / 1.8)))


def _glitter(x: int, y: int) -> bool:
    """A denser diamond lattice: the scales that catch the sheen (drawn coordinates)."""
    return y % 2 == 1 and (x + y // 2) % 2 == 0


def _iridescence(img: Image.Image, zones: dict, centre: float, drift: float) -> None:
    """A sheen band rolling along the body. Across its width (and drifting over the loop)
    the colour runs purple -> pink -> cyan; the glittering scales take it at full strength,
    the skin between them only a soft violet-pink wash."""
    px = img.load()
    width = 4.2
    for (X, Y), (zone, (x, y)) in zones.items():
        if zone not in ("flank", "belly", "back", "pect", "fringe") or x <= 8:
            continue
        d = (_along(x, y) - centre) / width
        if abs(d) >= 1:
            continue
        k = min(1.0, (1.0 - abs(d)) * 1.4)
        h = ((d + 1) / 2 + drift) % 1.0 * len(IRIDESCENT)
        i = int(h)
        colour = mix(IRIDESCENT[i % 3], IRIDESCENT[(i + 1) % 3], h - i)
        if _glitter(x, y) and zone != "back":
            strength = 0.92 * k
        else:
            colour = mix(IRIDESCENT[0], IRIDESCENT[1], wave(drift, d * 0.5))
            strength = (0.25 if zone == "back" else 0.38) * k
        px[X, Y] = rgba(mix(px[X, Y], colour, strength))


def _glint(img: Image.Image, zones: dict, t: float) -> None:
    """A soft glint sliding along the back and head: shine() limited to the rim light and
    the lit head, with the dark back warming toward amber under it."""
    lit = shine(img, t, colour=GLINT, width=3.5, strength=0.65, angle=28.0, pause=0.0).load()
    warm = shine(img, t, colour=BACK[3], width=3.5, strength=0.5, angle=28.0, pause=0.0).load()
    px = img.load()
    for (X, Y), (zone, (x, y)) in zones.items():
        ch = BODY.get((x, y))
        if ch in ("R", "r") or (x <= 8 and ch in ("c", "h", "k")):
            px[X, Y] = lit[X, Y]
        elif ch in ("B", "X"):
            px[X, Y] = warm[X, Y]


# Sparkles around the fish (display coordinates): position, phase, colour.
SPARKLES = [((4, 4), 0.0, 0), ((26, 10), 1 / 3, 1), ((19, 25), 2 / 3, 2)]


def _frame(t: float) -> Image.Image:
    fins, pect = _compose(t)
    img, zones = _paint(fins, pect)
    step = round(t * FRAMES) % FRAMES
    # the iridescent sheen rolls head to tail once per loop (half a loop behind the pulse)
    _iridescence(img, zones, -4.5 + 36.0 * ((step + 8) % FRAMES) / FRAMES, drift=t)
    if step <= 7:
        # frames 0-7: a charge pulse runs down the lightning bolt
        _charge(img, zones, -0.05 + 1.1 * step / 7)
    elif 9 <= step <= 14:
        # frames 9-14: a soft glint slides along the back
        _glint(img, zones, 0.08 + 0.84 * (step - 9) / 5)
    for (x, y), phase, colour in SPARKLES:
        amount = max(0.0, math.sin(2 * math.pi * (t + phase))) ** 1.5
        sparkle(img, x, y, amount, SPARKS[colour], reach=2)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
