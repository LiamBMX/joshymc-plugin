"""Ember Catfish: a rare fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's ember orange scheme. Catfish anatomy: a broad, blunt head with a
wide terminal mouth, long drooping barbels (whiskers) and a short nasal barbel, a small eye
set high and forward, a tall spined dorsal fin right behind the head, a small free adipose
fin near the tail, a spined pectoral fin, a long rounded anal fin and a deeply forked tail.
Smooth scaleless skin: a smouldering rust-red back under a lit rim, bright orange flanks
flecked with glowing coal-gold spots and char freckles, and a hot golden countershaded
belly; translucent fins run from deep ember red at the base to flame-orange edges.

Animation (RARE, 12 frames x 3 ticks): the tail wags with its tips leading, the fins sway a
pixel and the whiskers trail, the coal spots breathe, a soft glint slides along the back
(frames 1-4), then a blue-tinted sheen band rolls across the body (6-10) while three
cool-white sparkles twinkle around the fish in turn.
"""
from __future__ import annotations

import math

from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_ember_catfish"
NAME = "Ember Catfish"
KIND = "item"
MODEL_KEY = "fish/ember_catfish"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3
SHIFT = (0, 3)          # the art below is drawn three pixels above centre

# ---- palettes (darkest -> lightest): ember oranges, shadows lean crimson, lights lean gold --
OUTLINE = "#4a0f10"
OUTLINE_LIT = "#6a1a12"
FIN_OUTLINE_ALPHA = 232
BACK = ["#7a1c12", "#aa320f", "#c8460c", "#dc5a0e"]
SKIN = ["#c9530e", "#dc661a", "#ea7c26", "#f59436", "#fbaa48", "#ffc160", "#ffd988"]
RIM = ["#f08a3a", "#ffa84c", "#ffc870"]
BELLY = ["#ef9a40", "#fbb857", "#ffd077", "#ffe4a0", "#fff2c8"]
COAL = ["#ffd04a", "#fff3a8"]
CHAR = "#a2340f"
FIN = ["#7a1c12", "#9a2a14", "#bc3e16", "#d9561c", "#ef7628", "#ff9f44", "#ffc56c"]
BARBEL = ["#8a2412", "#c4480f", "#f07a28", "#ffb050"]
EYE = ["#1c0706", "#fffbe8", "#e3a634"]
GLINT = "#fff1c8"
SHEEN = "#c2e2ff"          # the rare tier's blue-tinted sheen
SPARK = "#eef6ff"

# Body contour of the drawn art: first and last body row of every column.
TOP = {3: 8, 4: 8, 5: 7, 6: 7, 7: 6, 8: 6, 9: 6, 10: 7, 11: 7, 12: 8, 13: 8, 14: 9, 15: 10, 16: 10,
       17: 11, 18: 12, 19: 12, 20: 13, 21: 14, 22: 14}
BOT = {3: 10, 4: 11, 5: 12, 6: 12, 7: 12, 8: 13, 9: 13, 10: 13, 11: 14, 12: 14, 13: 15, 14: 15, 15: 16,
       16: 16, 17: 16, 18: 17, 19: 17, 20: 17, 21: 18, 22: 18}

# letter -> (colour, zone)
BODY = {
    "K": (RIM[2], "back"), "k": (RIM[1], "back"), "j": (RIM[0], "back"),
    "B": (BACK[1], "back"), "b": (BACK[2], "back"), "q": (BACK[3], "back"),
    "o": (SKIN[1], "flank"), "O": (SKIN[2], "flank"), "g": (SKIN[3], "flank"), "G": (SKIN[4], "flank"),
    "L": (SKIN[5], "flank"),
    "w": (BELLY[2], "belly"), "W": (BELLY[3], "belly"), "v": (BELLY[1], "belly"), "V": (BELLY[4], "belly"),
    "c": (COAL[0], "coal"), "C": (COAL[1], "coal"), "x": (CHAR, "flank"),
    "h": (SKIN[2], "head"), "H": (SKIN[4], "head"), "n": (BACK[1], "line"), "N": (BACK[3], "head"),
    "m": (OUTLINE, "line"), "l": (SKIN[6], "head"), "J": (BELLY[3], "head"),
    "E": (EYE[0], "eye"), "e": (EYE[1], "eye"), "i": (EYE[2], "eye"),
}
# Fins: membrane letters -> (base colour, zone); ray letters -> (colour, zone).
FINS = {"f": (FIN[3], "fin"), "t": (FIN[4], "tail"), "a": (FIN[4], "fin")}
FIN_BASE = {"f": FIN[1], "t": FIN[2], "a": FIN[3]}
FIN_EDGE = {"f": FIN[5], "t": FIN[6], "a": FIN[6]}
RAYS = {"F": (FIN[2], "fin"), "T": (FIN[2], "tail"), "A": (FIN[2], "fin"), "S": (FIN[6], "fin")}
MEMBRANE_ALPHA, EDGE_ALPHA, BASE_ALPHA, RAY_ALPHA = 212, 208, 224, 236


def _band(x: int, y: int) -> str:
    """Body letter from steps in from the back (kb) and the belly (kv) of the column."""
    kb, kv = y - TOP[x], BOT[x] - y
    depth = kb + kv + 1
    if kb == 0:
        return "K" if x <= 8 else "k" if x <= 14 else "j"
    if kv == 0:
        return "v"
    if depth <= 5:                     # the slim peduncle
        return "B" if kb == 1 else "G" if kv >= 2 else "W"
    if kb == 1:
        return "B"
    if kb == 2:
        return "q"
    if kv == 1:
        return "W"
    if kv == 2:
        return "w" if depth >= 8 else "L"
    if kv == 3 and depth >= 8:
        return "L"
    if kb == 3:
        return "O"
    if kv == 3:
        return "G"
    return "g"


# Head and marking overrides (x, y) -> letter.
DETAIL = {
    # crown and blunt snout
    (4, 8): "K", (5, 8): "N", (3, 8): "K", (3, 9): "l", (4, 9): "H", (5, 9): "H",
    # eye: 2x2 with a catchlight, set high, a gold iris glint behind it
    (6, 8): "e", (7, 8): "E", (6, 9): "E", (7, 9): "E", (8, 9): "i",
    # wide terminal mouth and pale lower jaw
    (3, 10): "J", (4, 10): "m", (5, 10): "m", (6, 10): "n", (4, 11): "J", (5, 11): "J", (6, 11): "w",
    # gill cover
    (8, 8): "h", (8, 10): "G", (8, 11): "L", (9, 9): "n", (9, 10): "n", (9, 11): "n",
    (9, 12): "n", (8, 12): "W",
    # glowing coals smouldering in the dark back
    (10, 9): "C", (11, 8): "c",
    (13, 10): "c", (14, 11): "C",
    (16, 12): "C", (17, 12): "c",
    # char freckles on the bright flank (the catfish's spots)
    (12, 12): "x", (16, 14): "x", (19, 15): "x",
}

# Fins drawn as rows: (x0, y, letters). '.' skips a pixel.
FIN_ROWS = [
    # dorsal: a strong lit spine leading, swept back
    (9, 2, "S"),
    (9, 3, "Sf"),
    (8, 4, "Sff"),
    (8, 5, "SfFf"),
    (10, 6, "fff"),
    (12, 7, "ff"),
    # adipose: a small free lobe near the tail
    (17, 10, "aa"),
    (18, 11, "aaa"),
    (20, 12, "a"),
    # pelvic: a small nub
    (11, 15, "aa"),
    # anal: long, low and rounded, hugging the belly
    (14, 16, "a"),
    (14, 17, "aaAa"),
    (15, 18, "aaaAaa"),
    (18, 19, "aa"),
]
# Tail (neutral pose) as rows from (23, 12).
TAIL_AT = (23, 12)
TAIL = [
    "....tt",   # 12
    "..tttt",   # 13
    "tttTt.",   # 14
    "ttTt..",   # 15
    "tTt...",   # 16
    "tt....",   # 17
    "tTt...",   # 18
    "ttT...",   # 19
    ".tTt..",   # 20
    ".ttT..",   # 21
    "..tTt.",   # 22
    "..ttt.",   # 23
    "...t..",   # 24
]
TAIL_BASE = (23.0, 16.5)
AXIS = (math.cos(math.radians(32)), math.sin(math.radians(32)))

# Pectoral fin poses (x, y, part): 'p' membrane blended over the body, 'P' the spine,
# 'q' membrane hanging in open water.
PECT = {
    "spread": [(6, 11, "P"), (7, 12, "P"), (8, 13, "P"), (9, 14, "P"), (6, 12, "p"), (7, 13, "q")],
    "folded": [(6, 11, "P"), (7, 11, "P"), (8, 12, "P"), (9, 13, "P"), (7, 12, "p"), (8, 13, "p"),
               (9, 14, "q")],
}
# Barbel poses: (x, y, part) with 'z' whisker, 'Z' whisker shade, 'u' glowing tip.
BARBELS = {
    "rest": [
        (3, 7, "z"), (2, 6, "Z"), (2, 5, "u"),                                      # nasal
        (2, 10, "z"), (1, 11, "Z"), (1, 12, "Z"), (1, 13, "Z"), (1, 14, "Z"), (2, 15, "Z"), (2, 16, "u"),
        (4, 12, "z"), (4, 13, "Z"), (3, 14, "Z"), (3, 15, "u"),                    # chin
        (6, 13, "z"), (6, 14, "Z"), (6, 15, "Z"), (7, 16, "u"),                    # chin
    ],
    "trail": [
        (3, 7, "z"), (3, 6, "Z"), (3, 5, "u"),
        (2, 10, "z"), (1, 11, "Z"), (1, 12, "Z"), (1, 13, "Z"), (2, 14, "Z"), (2, 15, "Z"), (3, 16, "u"),
        (4, 12, "z"), (4, 13, "Z"), (4, 14, "Z"), (4, 15, "u"),
        (6, 13, "z"), (7, 14, "Z"), (7, 15, "Z"), (8, 16, "u"),
    ],
}
BARBEL_COL = {"z": BARBEL[1], "Z": BARBEL[0], "u": BARBEL[2]}


def _static() -> dict:
    out = {}
    for x in TOP:
        for y in range(TOP[x], BOT[x] + 1):
            out[(x, y)] = _band(x, y)
    out.update(DETAIL)
    for x0, y, row in FIN_ROWS:
        for i, ch in enumerate(row):
            if ch != "." and (x0 + i, y) not in out:
                out[(x0 + i, y)] = ch
    return out


STATIC = _static()


def _tail(near: int, far: int) -> dict:
    """The tail flexed: pixels 2-4 px out from the root move `near` rows, the lobes further
    out move `far` rows (positive: down), so the tips lead the sway."""
    base = {}
    for j, row in enumerate(TAIL):
        for i, ch in enumerate(row):
            if ch != ".":
                base[(TAIL_AT[0] + i, TAIL_AT[1] + j)] = ch
    if near == 0 and far == 0:
        return base
    out = {}
    for (x, y), ch in base.items():
        dist = math.hypot(x + 0.5 - TAIL_BASE[0], y + 0.5 - TAIL_BASE[1])
        dy = far if dist >= 4.6 else near if dist >= 2.4 else 0
        out.setdefault((x, y + dy), ch)
    step = 1 if far > 0 else -1
    for (x, y) in list(base):
        if (x, y) not in out and (x, y - step) in out and (x, y + step) in out:
            out[(x, y)] = "t"
    return out


def _compose(t: float):
    wag = math.sin(2 * math.pi * t)
    near, far = round(0.7 * wag), round(1.35 * wag)
    pect = "folded" if math.sin(2 * math.pi * t + 1.2) > 0.2 else "spread"
    barbel = "trail" if math.sin(2 * math.pi * t + 2.4) > 0 else "rest"
    layer = dict(STATIC)
    for key, ch in _tail(near, far).items():
        layer.setdefault(key, ch)
    return layer, {(x, y): p for x, y, p in PECT[pect]}, BARBELS[barbel]


def _paint(layer: dict, pect: dict, coal: float):
    img = canvas(SIZE)
    px = img.load()
    sx, sy = SHIFT
    zones = {}
    body = {k for k, ch in layer.items() if ch in BODY}
    for (x, y), ch in layer.items():
        X, Y = x + sx, y + sy
        if ch in BODY:
            colour, zone = BODY[ch]
            if zone == "coal":
                colour = mix(SKIN[4], colour, 0.55 + 0.45 * coal)
            if (x, y) in pect:
                part = pect[(x, y)]
                colour = mix(colour, FIN[1] if part == "P" else FIN[3], 0.8 if part == "P" else 0.62)
                zone = "pect"
            px[X, Y] = rgba(colour)
            zones[(X, Y)] = zone
            continue
        near = [layer.get((x + dx, y + dy)) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        if ch in RAYS:
            colour, zone = RAYS[ch]
            alpha = RAY_ALPHA
        else:
            base, zone = FINS[ch]
            if any(n in BODY for n in near):
                colour, alpha = FIN_BASE[ch], BASE_ALPHA
            elif any(n is None for n in near):
                colour, alpha = FIN_EDGE[ch], EDGE_ALPHA
            else:
                colour, alpha = base, MEMBRANE_ALPHA
        c = rgba(colour)
        px[X, Y] = (c[0], c[1], c[2], alpha)
        zones[(X, Y)] = zone
    # pectoral pixels hanging below the belly
    for (x, y), part in pect.items():
        if (x, y) in layer:
            continue
        X, Y = x + sx, y + sy
        c = rgba(FIN[2] if part == "P" else FIN[5])
        px[X, Y] = (c[0], c[1], c[2], RAY_ALPHA if part == "P" else EDGE_ALPHA)
        zones[(X, Y)] = "fin"
    # 1 px outline: solid beside the body, a touch translucent where it only borders fins
    filled = set(zones)
    solid = {(x + sx, y + sy) for (x, y) in body}
    dark, lit = rgba(OUTLINE), rgba(OUTLINE_LIT)
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in filled:
                continue
            nb = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + dx, y + dy) in filled]
            if not nb:
                continue
            facing_light = ((x, y + 1) in filled or (x + 1, y) in filled) and \
                (x, y - 1) not in filled and (x - 1, y) not in filled
            o = lit if facing_light else dark
            px[x, y] = o if any(p in solid for p in nb) else (o[0], o[1], o[2], FIN_OUTLINE_ALPHA)
    return img, zones


def _along(x: int, y: int) -> float:
    """Distance along the body axis from the snout, in frame pixels."""
    return (x + 0.5 - 3.0) * AXIS[0] + (y + 0.5 - 9.5 - SHIFT[1]) * AXIS[1]


# Sparkles around the fish (frame coordinates) and the phase each one peaks at.
SPARKS = [((4, 5), 0.08), ((25, 8), 0.42), ((10, 26), 0.75)]


def _frame(t: float) -> Image.Image:
    layer, pect, barbels = _compose(t)
    coal = 0.5 + 0.5 * math.cos(2 * math.pi * t)
    img, zones = _paint(layer, pect, coal)
    px = img.load()
    sx, sy = SHIFT
    for x, y, part in barbels:
        colour = BARBEL_COL[part]
        if part == "u":                        # the whisker tips glow like lit wicks
            colour = mix(BARBEL[2], BARBEL[3], coal)
        px[x + sx, y + sy] = rgba(colour)
    step = round(t * FRAMES) % FRAMES
    if 1 <= step <= 4:
        # a soft glint slides along the lit back, snout to tail
        centre = 1.0 + 21.0 * (step - 1) / 3.0
        g = rgba(GLINT)
        for (x, y), zone in zones.items():
            if zone not in ("back", "head", "coal"):
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.4) * 0.72
            if layer.get((x - sx, y - sy)) in ("K", "k", "j"):
                k = min(1.0, k * 1.25)
            if k > 0:
                px[x, y] = rgba(mix(px[x, y], g, k))
    elif 6 <= step <= 10:
        # a blue-tinted sheen band rolls across the wet skin, head to tail
        centre = 2.0 + 22.0 * (step - 6) / 4.0
        for (x, y), zone in zones.items():
            if zone in ("eye", "line"):
                continue
            d = _along(x, y) - centre
            k = max(0.0, 1.0 - abs(d) / 3.0)
            if k <= 0:
                continue
            strength = (0.7 if zone in ("back", "flank", "belly", "head", "coal", "pect") else 0.28) * k
            hot = abs(d) < 0.9 and (x + y) % 2 == 0 and zone in ("flank", "back", "belly")
            px[x, y] = rgba(mix(px[x, y], SPARK if hot else SHEEN, min(1.0, strength * (1.6 if hot else 1.0))))
    for (x, y), peak in SPARKS:
        d = ((t - peak + 0.5) % 1.0) - 0.5
        amount = max(0.0, 1.0 - abs(d) / 0.2)
        sparkle(img, x, y, amount, colour=SPARK, reach=2)
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
