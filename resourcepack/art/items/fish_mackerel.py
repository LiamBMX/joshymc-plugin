"""Mackerel: an uncommon ocean fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's navy / steel-blue / pale-sky scheme with its golden accents.
A sleek spindle-shaped body with a pointed snout: a metallic blue-green back under a lit
rim, crossed by the mackerel's dark "tiger" bars, a teal iridescent line along the flank,
a silvery countershaded belly with a faint violet sheen, a big eye with a golden iris, a
gill-cover edge, two well separated dorsal fins, the row of little finlets running to the
tail on the back and belly, a very slim tail stem and a deeply forked, sharp-lobed tail.

Animation (UNCOMMON, 12 frames x 3 ticks): the tail wags once per loop with its tips
leading and the pectoral and pelvic fins sway a pixel on offset phases; a soft glint
slides along the back (frames 0-5), then a shimmer band of glittering scales rolls across
the body from head to tail (frames 6-11).
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, shade, sprite

ID = "fish_mackerel"
NAME = "Mackerel"
KIND = "item"
MODEL_KEY = "fish/mackerel"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palette: hue-shifted ramps (shadows lean indigo, lights lean aqua / warm) -----------
BACK = ["#132a4c", "#1c426a", "#25608a", "#2f7ea2", "#4a9db6", "#72bdc8", "#a4dbdc"]
BAR = ["#0c1736", "#132a50"]                 # the tiger bars
TEAL = ["#38a39c", "#58c4ae", "#9ae6cc"]     # iridescent flank line
SILVER = ["#56689a", "#7488b6", "#98acd2", "#b9c8e6", "#d4dff1", "#eaf1fa", "#fbfdff"]
VIOLET = "#b3a6d8"                           # faint sheen marks on the lower flank
GOLD = ["#6e4a1e", "#b1802e", "#e6b740", "#ffd96c"]
FIN = "#3f6590"                              # fin membrane base
LOWFIN = mix(FIN, SILVER[3], 0.4)
OUTLINE = "#0c1834"
OUTLINE_LIT = "#16284c"
GLINT = "#effbff"
SHIMMER = "#d9f4ff"
SHIMMER_TINTS = ["#bff5e4", "#dcd2fb"]     # the shimmer wash drifts from sea-green to violet
SPARK = "#ffffff"


def _f(amount: float, base: str = FIN) -> str:
    return shade(base, amount, 0.15)


# letter: (colour, zone)
BODY = {
    # back: rim light (fading toward the tail), steel-blue back, tiger bars
    "K": (BACK[6], "back"), "k": (BACK[5], "back"), "y": (BACK[5], "back"),
    "b": (BACK[3], "back"), "B": (BACK[2], "back"), "N": (BACK[1], "back"),
    "d": (BAR[1], "bar"), "D": (BAR[0], "bar"),
    # flank: teal line, silver, violet sheen marks
    "G": (TEAL[2], "flank"), "g": (TEAL[1], "flank"), "q": (TEAL[0], "flank"),
    "s": (SILVER[3], "flank"), "r": (VIOLET, "flank"),
    # belly (countershaded)
    "u": (SILVER[4], "belly"), "w": (SILVER[5], "belly"), "W": (SILVER[6], "belly"), "v": (SILVER[2], "belly"),
    # head: crown, cheek, gill-cover edge, mouth line, lower jaw
    "H": (BACK[5], "head"), "h": (BACK[4], "head"), "c": (SILVER[4], "head"), "C": (SILVER[5], "head"),
    "n": (BACK[1], "line"), "l": (SILVER[1], "line"), "m": (OUTLINE, "line"), "j": (SILVER[3], "head"),
    # eye: 2x2 pupil with a catchlight, golden iris
    "E": ("#080d20", "eye"), "e": ("#ffffff", "eye"), "i": (GOLD[2], "eye"), "I": (GOLD[1], "eye"),
}
FINS = {"f": (FIN, "fin"), "t": (FIN, "tail"), "a": (LOWFIN, "fin"), "o": (GOLD[2], "fin")}
RAYS = {"R": (FIN, "fin"), "T": (FIN, "tail"), "A": (LOWFIN, "fin")}
MEMBRANE_ALPHA, EDGE_ALPHA, BASE_ALPHA, RAY_ALPHA = 212, 204, 222, 234
FIN_OUTLINE_ALPHA = 234
PECT_TINT, PECT_EDGE = _f(0.62), _f(-0.12)

# ---- body silhouette: (top, bottom) row of every column, snout to tail stem ------------
COLS = {
    1: (9, 9), 2: (8, 10), 3: (8, 11), 4: (7, 12), 5: (7, 12), 6: (7, 13), 7: (7, 13), 8: (7, 14),
    9: (7, 14), 10: (8, 15), 11: (8, 15), 12: (8, 16), 13: (9, 16), 14: (9, 17), 15: (10, 17),
    16: (10, 18), 17: (11, 18), 18: (12, 19), 19: (12, 19), 20: (13, 20), 21: (14, 20), 22: (15, 20),
    23: (16, 20),
}
AXIS = (math.cos(math.radians(28)), math.sin(math.radians(28)))
SNOUT = (1.0, 9.5)

# Head details over the painted body.
HEAD = {
    (1, 9): "c",                                   # snout tip
    (2, 8): "H", (3, 8): "H", (2, 9): "h", (3, 9): "h",
    (2, 10): "m", (3, 10): "m", (4, 10): "l",      # mouth line, running back under the eye
    (3, 11): "c", (4, 11): "C",                    # lower jaw
}
EYE = {(4, 8): "e", (5, 8): "E", (4, 9): "E", (5, 9): "E", (6, 8): "i", (6, 9): "I", (5, 10): "I"}
GILL = [(7, 8), (8, 9), (8, 10), (8, 11), (7, 12)]

# Fins drawn around the body: (x, y) -> letter.
DORSAL1 = {(12, 3): "o", (11, 4): "f", (12, 4): "R", (10, 5): "f", (11, 5): "R", (12, 5): "f",
           (9, 6): "R", (10, 6): "f", (11, 6): "f", (12, 6): "R", (13, 6): "f",
           (12, 7): "f", (13, 7): "f", (13, 8): "f"}
DORSAL2 = {(16, 8): "R", (16, 9): "f", (17, 9): "f", (17, 10): "f"}
ANAL = {(16, 19): "A", (17, 19): "a", (17, 20): "a"}
FINLETS_TOP = [(19, 11), (21, 13), (23, 15)]
FINLETS_BOT = [(19, 20), (21, 21)]

# Pectoral fin poses over the flank: "P" its darker leading ray, "p" the pale membrane below.
PECT = {
    "spread": {(9, 12): "P", (10, 12): "P", (11, 13): "P", (12, 13): "P", (10, 13): "p", (11, 14): "p",
               (12, 14): "p"},
    "folded": {(9, 12): "P", (10, 12): "P", (11, 12): "P", (12, 13): "P", (13, 13): "P", (10, 13): "p",
               (11, 13): "p"},
}
PELVIC = {"rest": [(9, 15), (10, 16), (11, 16)], "swept": [(9, 15), (10, 16), (11, 17)]}

# Tail (neutral pose): rows from (22, 11). A deeply forked tail of two sharp lobes.
TAIL_AT = (22, 11)
TAIL = [
    "........",  # 11
    ".......t",  # 12
    "......tt",  # 13
    ".....tTt",  # 14
    "....tTt.",  # 15
    "...tTt..",  # 16
    "..tTt...",  # 17
    ".ttt....",  # 18
    ".tTt....",  # 19
    "..tTt...",  # 20
    "..ttTt..",  # 21
    "...tTt..",  # 22
    "...ttT..",  # 23
    "....tT..",  # 24
    "....tt..",  # 25
    ".....t..",  # 26
]
TAIL_BASE = (23.5, 18.5)


# The tiger bars: wavy 1 px stripes square to the body axis, one every 3 px along the back.
# Each is (rim x, x offset of every row from the rim down); they lean down-left, and their
# kinks alternate so neighbouring bars waver against each other.
BARS = [(10, [0, 0, -1, -1]), (13, [0, -1, -1, -2, -2]), (16, [0, 0, -1, -1, -2]),
        (19, [0, -1, -1, -2]), (22, [0, 0, -1, -1])]


def _back_rows(x: int):
    top, bot = COLS[x]
    nbk = max(1, int((bot - top + 1) * 0.5 + 0.5) - 1)
    return top, nbk


def _bar_pixels() -> dict:
    out = {}
    for x0, offsets in BARS:
        top0 = COLS[x0][0]
        for i, off in enumerate(offsets):
            x, y = x0 + off, top0 + i
            if x not in COLS:
                continue
            top, nbk = _back_rows(x)
            kb = y - top
            if kb == 0:
                out[(x, y)] = "N"
            elif 1 <= kb <= nbk:
                out[(x, y)] = "d" if i == len(offsets) - 1 or kb == nbk else "D"
    return out


def _body_letters() -> dict:
    out = {}
    for x, (top, bot) in COLS.items():
        _, nbk = _back_rows(x)
        for y in range(top, bot + 1):
            kb, kv = y - top, bot - y
            if kb == 0 and kv > 0:
                ch = "K" if x < 6 else "k" if x < 12 else "y"
            elif kb <= nbk and kv > 0:
                ch = "b" if kb == 1 else "B"
            elif kb == nbk + 1 and kv > 0:
                ch = "G" if x < 8 else "g" if x < 15 else "q"
            elif kv == 0:
                ch = "v"
            elif kv == 1:
                ch = "W" if x < 17 else "w"
            elif kv == 2:
                ch = "w" if x < 18 else "u"
            elif kb == nbk + 2:
                ch = "r" if (x % 3 == 0 and x > 8) else "s"
            else:
                ch = "u"
            out[(x, y)] = ch
    out.update(_bar_pixels())
    return out


def _static() -> dict:
    layer = _body_letters()
    for key, ch in HEAD.items():
        layer[key] = ch
    for key, ch in EYE.items():
        layer[key] = ch
    for x, y in GILL:
        if (x, y) in layer and layer[(x, y)] in BODY:
            layer[(x, y)] = "n" if BODY[layer[(x, y)]][1] in ("back", "bar") else "l"
    for part in (DORSAL1, DORSAL2, ANAL):
        for key, ch in part.items():
            layer.setdefault(key, ch)
    for key in FINLETS_TOP + FINLETS_BOT:
        layer.setdefault(key, "a")
    return layer


def _tail(near: int, far: int) -> dict:
    """The tail flexed around its root: pixels 2.4-4.5 px out move `near`, the lobe tips
    further out move `far` (the upper lobe shifts in y, the steep lower lobe in x, so both
    follow one bend and the tips lead the sway)."""
    base = {}
    for j, row in enumerate(TAIL):
        for i, ch in enumerate(row):
            if ch != ".":
                base[(TAIL_AT[0] + i, TAIL_AT[1] + j)] = ch
    if near == 0 and far == 0:
        return base
    out = {}
    for (x, y), ch in base.items():
        reach = math.hypot(x + 0.5 - TAIL_BASE[0], y + 0.5 - TAIL_BASE[1])
        d = far if reach >= 4.5 else near if reach >= 2.4 else 0
        key = (x, y + d) if y < TAIL_BASE[1] else (x - d, y)
        out.setdefault(key, ch)
    return out


def _compose(t: float):
    wag = math.sin(2 * math.pi * t)
    near, far = round(0.7 * wag), round(1.4 * wag)
    pect = "folded" if math.sin(2 * math.pi * 2 * t + 1.0) > 0.3 else "spread"
    pelvic = "swept" if math.sin(2 * math.pi * t + 2.0) > 0 else "rest"
    layer = _static()
    for key, ch in _tail(near, far).items():
        layer.setdefault(key, ch)
    for key in PELVIC[pelvic]:
        layer.setdefault(key, "a")
    return layer, PECT[pect]


def _paint(layer: dict, pect: dict):
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for (x, y), ch in layer.items():
        if not (0 < x < SIZE - 1 and 0 < y < SIZE - 1):
            continue
        if ch in BODY:
            colour, zone = BODY[ch]
            if (x, y) in pect:
                part = pect[(x, y)]
                colour = mix(colour, PECT_TINT if part == "p" else PECT_EDGE, 0.5 if part == "p" else 0.78)
                zone = "pect"
            px[x, y] = rgba(colour)
            zones[(x, y)] = zone
            continue
        near = [layer.get((x + dx, y + dy)) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        if ch in RAYS:
            base, zone = RAYS[ch]
            colour, alpha = _f(-0.28, base), RAY_ALPHA
        else:
            base, zone = FINS[ch]
            if ch == "o":
                colour, alpha = base, RAY_ALPHA
            elif any(n is None for n in near):
                colour, alpha = _f(0.26, base), EDGE_ALPHA
            elif any(n in BODY for n in near):
                colour, alpha = _f(-0.12, base), BASE_ALPHA
            else:
                colour, alpha = base, MEMBRANE_ALPHA
        c = rgba(colour)
        px[x, y] = (c[0], c[1], c[2], alpha)
        zones[(x, y)] = zone
    # 1 px outline around the silhouette: solid beside the body, a touch translucent where
    # it only borders fins, and a shade lighter on the side facing the top-left light
    filled = set(zones)
    body = {k for k, z in zones.items() if z not in ("fin", "tail")}
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
            px[x, y] = o if any(p in body for p in near) else (o[0], o[1], o[2], FIN_OUTLINE_ALPHA)
    return img, zones


def _along(x: int, y: int) -> float:
    """Distance along the body axis from the snout, in pixels."""
    return (x + 0.5 - SNOUT[0]) * AXIS[0] + (y + 0.5 - SNOUT[1]) * AXIS[1]


def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _frame(t: float):
    layer, pect = _compose(t)
    img, zones = _paint(layer, pect)
    px = img.load()
    step = round(t * FRAMES)
    if step <= 5:
        # a soft glint slides along the back, snout to tail
        centre = 1.0 + 24.0 * step / 5.0
        for (x, y), zone in zones.items():
            if zone not in ("back", "bar", "head"):
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.2) * 0.6
            if zone == "bar":
                k *= 0.5
            if k > 0:
                _blend(px, x, y, GLINT, k)
    else:
        # a shimmer band rolls across the scales, head to tail
        centre = 5.0 + 17.0 * (step - 6) / 5.0
        for (x, y), zone in zones.items():
            if zone not in ("back", "bar", "flank", "belly", "pect"):
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.0)
            if k <= 0:
                continue
            spark = y % 2 == 1 and (x + y // 2) % 2 == 0 and zone != "pect"
            strength = (0.92 if spark else 0.4) * min(1.0, k * 1.3)
            if zone == "bar" and not spark:
                strength *= 0.5
            wash = mix(SHIMMER_TINTS[0], SHIMMER_TINTS[1], (step - 6) / 5.0)
            _blend(px, x, y, SPARK if spark else wash, strength)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
