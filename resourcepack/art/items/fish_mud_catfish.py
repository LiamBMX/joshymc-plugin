"""Mud Catfish: a common bottom-dwelling catfish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's muddy olive-brown / mauve / putty-grey scheme. Flathead ("mud
cat") anatomy: a broad, flattened head with a small high-set eye and a wide gape whose
lower jaw juts past the upper, trailing barbels (a long whisker from the upper lip and two
chin barbels), a short spined dorsal fin right behind the head, a big fleshy adipose fin,
a long rounded anal fin, a spined pectoral fin lying over the flank and a broad,
squared-off tail. Smooth scaleless skin: an umber back under an olive rim light,
olive-khaki flanks marbled with mauve-brown blotches and a countershaded putty belly.

Animated (COMMON, 8 frames x 3 ticks): the tail and fins sway about a pixel on offset sine
phases, the barbels trail, and a soft wet glint slides along the back from snout to tail,
then rests.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, sprite

ID = "fish_mud_catfish"
NAME = "Mud Catfish"
KIND = "item"
MODEL_KEY = "fish/mud_catfish"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 8
FRAMETIME = 3
SHIFT = (1, 0)                   # the art below is drawn one pixel left of centre

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean violet, lights lean gold --
OUTLINE = "#2b1d18"
OUTLINE_LIT = "#3a2a1f"          # the outline on the side facing the top-left light
FIN_OUTLINE_ALPHA = 232
BACK = ["#2f221b", "#3d2d22", "#57452e", "#6f5f37", "#877845", "#a29658", "#bfb575"]
FLANK = ["#817a44", "#978f55", "#b0a96a", "#c8c287"]
MOTTLE = ["#3a282b", "#523a3d", "#6c5153", "#846a69"]
BELLY = ["#7d6f58", "#978a6c", "#b1a582", "#cbc198", "#ded6ae", "#eee8c8"]
FIN = ["#3c2f22", "#51422e", "#67593d", "#80734f", "#9b8f66", "#b7ad84"]
BARBEL = ["#34241c", "#5e4b39", "#7f6c53", "#ab9a7b"]
EYE = ["#110b09", "#fcf7e6", "#c89a3c"]
GLINT = "#f7f3d8"

COLOURS = {
    # back and flanks
    "R": BACK[6], "r": BACK[5], "q": BACK[4], "c": BACK[3], "d": BACK[2], "D": BACK[1],
    "F": FLANK[1], "G": FLANK[2], "H": FLANK[3],
    # marbling: core, body, soft edge
    "M": MOTTLE[0], "N": MOTTLE[1], "P": MOTTLE[2],
    # belly (1 is the underside in shadow)
    "1": BELLY[1], "2": BELLY[2], "3": BELLY[3], "4": BELLY[4], "5": BELLY[5],
    # head: eye, catchlight, iris, gape, pale lips
    "E": EYE[0], "W": EYE[1], "Y": EYE[2], "m": OUTLINE,
}
BODY_LETTERS = set(COLOURS)

# Fins: letter -> (colour, alpha). f membrane, z ray, e lit spine / leading edge,
# a fleshy adipose fin (nearly opaque skin).
FINS = {"f": (FIN[3], 212), "z": (FIN[2], 228), "e": (FIN[5], 226), "a": (BACK[3], 238),
        "t": (FIN[3], 210), "T": (FIN[2], 226), "u": (FIN[4], 206)}

# ---- the body, hand-painted column by column: (top row, letters from top to bottom) ------
# Every column carries the same bands, stepped down the 30 degree axis: a lit rim, the
# dark umber back, the marbled olive-brown upper flank, the olive-khaki flank with its
# light lateral line (G), the pale putty belly and its shadowed underside (1).
BODY = {
    3: (12, "43"),                   # the jutting lower lip
    4: (10, "rcm5"),
    5: (9, "Rqcm41"),
    6: (8, "RRqFGm41"),
    7: (8, "RWEcFG431"),             # small high-set eye with its catchlight
    8: (8, "REEdFPG431"),
    9: (9, "RYDdFG431"),
    10: (9, "rNNdcNG431"),           # marbling on the crown, the gill crease
    11: (10, "rDdcFG4431"),
    12: (10, "rDNPFG4431"),
    13: (11, "rMNcFG4431"),
    14: (12, "rDdNFG431"),
    15: (13, "rDNcFG431"),
    16: (14, "rMNFG431"),
    17: (15, "rNdFG431"),
    18: (16, "qDNFG31"),
    19: (16, "qDdFG31"),
    20: (17, "qNdF31"),
    21: (18, "qNcG1"),
    22: (19, "qcF1"),
}
COLUMNS = {x: (top, top + len(col) - 1) for x, (top, col) in BODY.items()}


def _base() -> dict:
    out = {}
    for x, (top, col) in BODY.items():
        for i, ch in enumerate(col):
            out[(x, top + i)] = ch
    return out


BASE = _base()

# Fins that stay put (the dorsal edge, anal edge and pelvic have a second pose).
# e lit leading spine, u lit membrane edge, f membrane, z ray.
DORSAL = {(10, 6): "e", (10, 7): "e", (10, 8): "e", (11, 6): "u", (11, 7): "f", (11, 8): "z", (11, 9): "f",
          (12, 7): "u", (12, 8): "f", (12, 9): "z", (13, 9): "f", (13, 10): "f", (14, 11): "f"}
DORSAL_FLUTTER = {(13, 8): "u", (14, 10): "f"}   # the trailing edge lifts
ADIPOSE = {(17, 14): "a", (18, 14): "a", (18, 15): "a", (19, 15): "a", (20, 15): "a", (20, 16): "a",
           (21, 16): "a", (21, 17): "a"}
ANAL = {(16, 22): "e", (16, 23): "f", (17, 23): "f", (18, 23): "z", (19, 23): "f", (20, 23): "z",
        (21, 23): "f", (18, 24): "f", (19, 24): "f", (20, 24): "f"}
ANAL_RIPPLE = {(17, 24): "f", (21, 24): "f"}   # the free edge billows
PELVIC = {"rest": {(13, 21): "e", (14, 21): "f", (14, 22): "f"},
          "swept": {(13, 21): "e", (14, 21): "f", (15, 22): "f"}}
# Near-side pectoral fin, painted over the flank: s lit leading spine, p dusky membrane;
# the pixels past the belly hang free.
PECTORAL = {
    "spread": {(8, 14): "s", (8, 15): "s", (9, 16): "s", (9, 17): "s", (10, 18): "s", (10, 19): "s",
               (9, 15): "p", (10, 16): "p", (10, 17): "p", (11, 17): "p", (11, 18): "p", (11, 19): "p"},
    "folded": {(8, 14): "s", (9, 15): "s", (10, 16): "s", (11, 17): "s", (12, 18): "s", (13, 19): "s",
               (9, 14): "p", (10, 15): "p", (11, 16): "p", (12, 16): "p", (12, 17): "p", (13, 17): "p",
               (13, 18): "p", (14, 19): "p"},
}
PECT_COLOUR = {"s": FIN[5], "p": FIN[2]}

# Tail: a broad, squared-off paddle with rounded corners; its end edge runs square to the
# body axis (upper corner up-right, lower corner down-left). Per column (top, bottom).
TAIL_COLUMNS = {23: (19, 23), 24: (18, 24), 25: (17, 25), 26: (17, 26), 27: (17, 25), 28: (18, 24)}
TAIL_RAYS = {(25, 19), (26, 19), (27, 19),                           # upper ray
             (24, 21), (25, 21), (26, 22), (27, 22),                 # middle ray
             (24, 23), (25, 24), (26, 24)}                           # lower ray
TAIL_EDGE = {(28, 18), (28, 19), (28, 20), (28, 21), (28, 22), (28, 23), (28, 24), (27, 25), (26, 26), (26, 25)}
TAIL_TOP = {(24, 18), (25, 17), (26, 17), (27, 17)}
AXIS = (math.cos(math.radians(30)), math.sin(math.radians(30)))


def _tail_art() -> dict:
    out = {}
    for x, (top, bot) in TAIL_COLUMNS.items():
        for y in range(top, bot + 1):
            key = (x, y)
            out[key] = "T" if key in TAIL_RAYS else "u" if key in TAIL_EDGE else "e" if key in TAIL_TOP else "t"
    return out


TAIL = _tail_art()


def _tail(sway: float) -> dict:
    """The tail flexed by whole columns: the root stays, the next column moves `near` rows
    and the paddle beyond it `far` rows (positive: down), so the end leads the sway and
    the shape stays clean."""
    far, near = round(1.2 * sway), round(0.6 * sway)
    out = {}
    for (x, y), ch in TAIL.items():
        dy = 0 if x <= 23 else near if x == 24 else far
        out[(x, y + dy)] = ch
    return out


# ---- barbels: pixel paths; the tip ends trail with the swim ------------------------------
BARBELS = {
    "rest": [
        [(2, 11), (1, 12), (0, 13), (0, 14), (0, 15), (1, 16)],          # long upper-lip whisker
        [(4, 15), (4, 16), (4, 17)],                                     # chin barbels
        [(6, 17), (6, 18)],
    ],
    "trail": [
        [(2, 11), (1, 12), (0, 13), (0, 14), (1, 15), (1, 16)],
        [(4, 15), (4, 16), (5, 17)],
        [(6, 17), (7, 18)],
    ],
}


def _compose(t: float):
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))
    trail = math.sin(2 * math.pi * (t - 0.25))
    layer = dict(BASE)
    for key, ch in _tail(sway).items():
        layer.setdefault(key, ch)
    fins = dict(DORSAL)
    if ripple > 0.3:
        fins.update(DORSAL_FLUTTER)
    fins.update(ADIPOSE)
    fins.update(ANAL)
    if ripple < -0.3:
        fins.update(ANAL_RIPPLE)
    fins.update(PELVIC["swept" if flap > 0 else "rest"])
    for key, ch in fins.items():
        layer.setdefault(key, ch)
    pect = PECTORAL["folded" if math.sin(2 * math.pi * 2 * t + 1.0) > 0.3 else "spread"]
    barbels = BARBELS["trail" if trail > 0.2 else "rest"]
    return layer, pect, barbels


def _paint(layer: dict, pect: dict, barbels: list):
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for (x, y), ch in layer.items():
        if ch in BODY_LETTERS:
            colour = COLOURS[ch]
            zone = "back" if y - COLUMNS[x][0] <= 2 else "body"
            if ch in "EWYm":
                zone = "eye"
            if (x, y) in pect:
                colour = mix(colour, PECT_COLOUR[pect[(x, y)]], 0.8)
                zone = "pect"
            px[x, y] = rgba(colour)
            zones[(x, y)] = zone
            continue
        colour, alpha = FINS[ch]
        near = [layer.get((x + dx, y + dy)) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        if ch in "ft" and ((x, y - 1) not in layer or (x - 1, y) not in layer):
            colour = FIN[4]                                       # edge catching the light
        elif ch == "a" and (x, y - 1) not in layer:
            colour = BACK[5] if x <= 18 else BACK[4]              # fleshy adipose, lit top
        elif ch in "ft" and any(n is None for n in near):
            colour = mix(FINS[ch][0], FIN[1], 0.4)                # shadowed trailing edge
        c = rgba(colour)
        px[x, y] = (c[0], c[1], c[2], alpha)
        zones[(x, y)] = "fin" if ch not in "tT" else "tail"
    # the pectoral tip that leaves the body
    for (x, y), part in pect.items():
        if (x, y) not in layer:
            c = rgba(FIN[5] if part == "s" else FIN[2])
            px[x, y] = (c[0], c[1], c[2], 226)
            zones[(x, y)] = "fin"
    # 1 px outline around the silhouette: solid beside the body, softer beside fins, a
    # touch lighter on the side facing the light
    filled = set(zones)
    dark, lit = rgba(OUTLINE), rgba(OUTLINE_LIT)
    out = img.copy()
    op = out.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in filled:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + dx, y + dy) in filled]
            if not near:
                continue
            facing = ((x, y + 1) in filled or (x + 1, y) in filled) and \
                (x, y - 1) not in filled and (x - 1, y) not in filled
            o = lit if facing else dark
            solid = any(layer.get(p) in BODY_LETTERS for p in near)
            op[x, y] = o if solid else (o[0], o[1], o[2], FIN_OUTLINE_ALPHA)
    # barbels over open water: dark root, lighter fleshy tip
    for path in barbels:
        n = len(path)
        for i, (x, y) in enumerate(path):
            if op[x, y][3] == 255 and (x, y) in filled:
                continue
            colour = BARBEL[1] if i == 0 else BARBEL[3] if i == n - 1 else BARBEL[2]
            op[x, y] = rgba(colour)
    return out, zones


def _along(x: int, y: int) -> float:
    """Distance along the body axis from the snout, in pixels."""
    return (x + 0.5 - 4.0) * AXIS[0] + (y + 0.5 - 10.5) * AXIS[1]


def _frame(t: float):
    layer, pect, barbels = _compose(t)
    img, zones = _paint(layer, pect, barbels)
    px = img.load()
    # a soft wet glint slides along the back from snout to tail over the first 70 % of the
    # loop, then rests (kit.shine() style band, mapped onto the fish's own axis)
    run = 0.7
    if t < run:
        centre = -1.5 + 25.0 * (t / run)
        c = rgba(GLINT)
        for (x, y), zone in zones.items():
            if zone != "back" and not (zone == "fin" and layer.get((x, y)) == "e"):
                continue
            depth = y - COLUMNS[x][0] if x in COLUMNS else 0
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.4) * 0.7 * (1.0 - depth / 3.0)
            if zone == "fin":
                k *= 0.55
            if k <= 0:
                continue
            r, g, b, a = px[x, y]
            px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)
    # the art is drawn a pixel left of centre; shift it so the whiskers get their air
    out = canvas(SIZE)
    out.alpha_composite(img, SHIFT)
    return out


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
