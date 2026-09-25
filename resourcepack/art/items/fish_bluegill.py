"""Bluegill: a common sunfish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's slate-blue / periwinkle / teal scheme. The deep, round panfish
body of a real bluegill: a steep forehead over a small upturned mouth, a big eye with a
catchlight, electric-blue cheek streaks, the gill cover ending in the black "ear" flap,
a lit indigo back, periwinkle flanks crossed by dusky vertical bars, and a warm
gold-peach breast (countershaded) fading to pale silver-blue under the tail. Fins: one
long dorsal (toothed spiny front, tall rounded soft rear with the species' dark spot at
its base), a rounded anal fin, a long pointed pectoral lying over the flank, a pelvic fin
and a broad, shallow-forked tail, all translucent with painted rays.

Animation (COMMON, 8 frames x 3 ticks): the tail sways about a pixel with its tips
leading, the pectoral and pelvic fins flutter and the soft dorsal and anal fins ripple on
offset phases, and a soft glint slides along the back from snout to tail, then rests.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, sprite

ID = "fish_bluegill"
NAME = "Bluegill"
KIND = "item"
MODEL_KEY = "fish/bluegill"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 8
FRAMETIME = 3
SHIFT = (0, 0)           # moves the whole drawing in the frame

# ---- palette: hue-shifted ramps (shadows lean violet, lights lean warm) ------------------
OUTLINE = "#161a44"
OUTLINE_LIT = "#1f2a5e"
FIN_OUTLINE_ALPHA = 226
BACK = ["#1d2356", "#26336e", "#2f4784", "#3c5f98", "#5a84b6", "#86acd4"]
FLANK = ["#3b5594", "#476aa5", "#577eb6", "#6c92c4", "#86a9d2", "#a6c3df", "#c6daeb"]
SHEEN = ["#7479c2", "#9398d6"]      # periwinkle iridescence (the old sprite's lavender)
TEAL = ["#4f8f9c", "#74b6b4"]
BAR = ["#27316c", "#34457f", "#46598f"]    # dusky vertical bars: core, body, fringe
BREAST = ["#c28a55", "#dca760", "#ecc27a", "#f5d99a", "#fbecc4"]
PALE = ["#8ea6c6", "#adc2da", "#cad9e9", "#e2ebf5"]
CHEEK = ["#3b83c6", "#5fb3ea", "#a2e2ff"]  # electric-blue cheek streaks
EAR = ["#0b0c22", "#1a1d42", "#3c4c8e"]    # black opercular flap and its lit edge
FIN = ["#2c3466", "#3c4478", "#4e5689", "#6870a6", "#8890c2", "#b1b7de"]
EYE = ["#0a0c20", "#ffffff", "#5d4630", "#a8844a"]
GLINT = "#effaff"

BODY = {  # letter: (colour, zone)
    "5": (BACK[5], "back"), "4": (BACK[4], "back"), "3": (BACK[3], "back"),
    "2": (BACK[2], "back"), "1": (BACK[1], "back"),
    "a": (FLANK[1], "flank"), "b": (FLANK[2], "flank"), "c": (FLANK[3], "flank"),
    "d": (FLANK[4], "flank"), "e": (FLANK[5], "flank"), "f": (FLANK[6], "flank"),
    "s": (SHEEN[0], "flank"), "S": (SHEEN[1], "flank"), "T": (TEAL[1], "flank"), "t": (TEAL[0], "flank"),
    "o": (BREAST[0], "belly"), "p": (BREAST[1], "belly"), "q": (BREAST[2], "belly"),
    "r": (BREAST[3], "belly"), "R": (BREAST[4], "belly"),
    "j": (PALE[0], "belly"), "k": (PALE[1], "belly"), "l": (PALE[2], "belly"), "L": (PALE[3], "belly"),
    "u": (CHEEK[0], "head"), "v": (CHEEK[1], "head"), "w": (CHEEK[2], "head"),
    "E": (EAR[0], "ear"), "F": (EAR[1], "ear"), "G": (EAR[2], "ear"),
    "K": (EYE[0], "eye"), "W": (EYE[1], "eye"), "i": (EYE[2], "eye"), "I": (EYE[3], "eye"),
    "x": (BAR[1], "flank"), "z": (BAR[2], "flank"),
    "m": (OUTLINE, "line"),
}
# Fins: letter -> (colour, alpha). n membrane, N ray, h lit edge, g root, y the dark spot.
FINS = {"n": (FIN[4], 182), "N": (FIN[2], 212), "h": (FIN[5], 198), "g": (FIN[2], 216),
        "y": (EAR[1], 240)}

# ---- the art (x 0..31) -------------------------------------------------------------------
# Static layer: body, head, dorsal and anal fins. Tail, pectoral and pelvic fins are
# layers of their own so they can move.
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "............h.h.................",  # 4
    "..........hhNnNhh...............",  # 5
    ".......4554gNnNnNhh.............",  # 6
    ".....45eed223gnnNnNh............",  # 7
    "....4eeddca223gnnNnNh...........",  # 8
    "...4eWKddcacS23gnnNnnh..........",  # 9
    "..dedKKiccacbS23gnnNnn..........",  # 10
    "..emdbcdccacSsc23gyynn..........",  # 11
    "..qwvddccbaccccx23gynn..........",  # 12
    "...qrvvdcaEEccxb21gn............",  # 13
    "....qrruaedFdxcbb11g............",  # 14
    ".....prrqeTdddxccxba1...........",  # 15
    "......pqrrqeTzddcxcb11..........",  # 16
    ".......pqqrqezedxdcxba1.........",  # 17
    ".........pqqqllkzedxcb1.........",  # 18
    "...........pkkkkkjjjja1.........",  # 19
    "............hggggggg............",  # 20
    ".............hNnNnnn............",  # 21
    "..............hnnNn.............",  # 22
    "................nn..............",  # 23
    "................................",  # 24
]
# Tail (neutral pose) as rows from TAIL_AT; the left column meets the peduncle.
TAIL_AT = (23, 14)
TAIL = [
    "....hh.",   # 14
    "..hhNnh",   # 15
    "ghNNnn.",   # 16
    "gNnnnn.",   # 17
    "gNNnn..",   # 18
    "gnnn...",   # 19
    "gNnnn..",   # 20
    "hnNnn..",   # 21
    ".hnNn..",   # 22
    "..hnNh.",   # 23
    "...hh..",   # 24
]
TAIL_BASE = (23.0, 18.5)      # where the tail joins the peduncle
AXIS = (math.cos(math.radians(30)), math.sin(math.radians(30)))
# Pectoral poses (x, y, part): spread and lifted. "p" blends a pale wash over the flank,
# "P" is its dark lower edge, "R" a lit ray.
PECT = {
    "spread": [(10, 15, "R"), (11, 15, "p"), (12, 15, "p"), (13, 15, "p"), (14, 15, "p"), (11, 16, "R"),
               (12, 16, "p"), (13, 16, "p"), (14, 16, "p"), (15, 16, "p"), (16, 16, "p"), (12, 17, "P"),
               (13, 17, "P"), (14, 17, "P"), (15, 17, "P"), (16, 17, "P")],
    "lifted": [(10, 15, "R"), (11, 15, "p"), (12, 15, "p"), (13, 15, "p"), (14, 15, "p"), (15, 15, "p"),
               (16, 15, "P"), (11, 16, "R"), (12, 16, "p"), (13, 16, "p"), (14, 16, "p"), (15, 16, "P"),
               (12, 17, "P"), (13, 17, "P")],
}
# Pelvic fin poses: hanging, and swept back a pixel.
PELVIC = {"rest": [(7, 18, "g"), (8, 18, "g"), (6, 19, "h"), (7, 19, "N"), (8, 19, "n"), (7, 20, "h"),
                   (8, 20, "n")],
          "swept": [(7, 18, "g"), (8, 18, "g"), (7, 19, "h"), (8, 19, "N"), (9, 19, "n"), (8, 20, "h"),
                    (9, 20, "n")]}


def _static() -> dict:
    out = {}
    for y, row in enumerate(ART):
        for x, ch in enumerate(row):
            if ch != ".":
                out[(x, y)] = ch
    return out


def _back_depth() -> dict:
    """For every body pixel, how many pixels it lies in from the back (the top edge of
    its column or the right edge of its row, whichever is nearer)."""
    body = {k for k, ch in _static().items() if ch in BODY}
    out = {}
    for (x, y) in body:
        up = 0
        while (x, y - up - 1) in body:
            up += 1
        right = 0
        while (x + right + 1, y) in body:
            right += 1
        out[(x, y)] = min(up, right)
    return out


DEPTH = _back_depth()


def _tail(near: int, far: int) -> dict:
    """The tail flexed: pixels 2-4 px out from the root move `near` px, the lobes further
    out move `far` px (positive: down), so the tips lead the sway."""
    base = {}
    for j, row in enumerate(TAIL):
        for i, ch in enumerate(row):
            if ch != ".":
                base[(TAIL_AT[0] + i, TAIL_AT[1] + j)] = ch
    if near == 0 and far == 0:
        return base
    out = {}
    for (x, y), ch in base.items():
        along = (x + 0.5 - TAIL_BASE[0]) * AXIS[0] + (y + 0.5 - TAIL_BASE[1]) * AXIS[1]
        dy = far if along >= 3.6 else near if along >= 1.6 else 0
        out.setdefault((x, y + dy), ch)
    step = 1 if far > 0 else -1
    for (x, y) in list(base):
        if (x, y) not in out and (x, y - step) in out and (x, y + step) in out:
            out[(x, y)] = "n"
    return out


def _compose(t: float):
    """Letters of every pixel of the frame at phase t, plus the pectoral overlay."""
    wag = math.sin(2 * math.pi * t)
    near, far = round(0.6 * wag), round(1.3 * wag)
    pect = "lifted" if math.sin(2 * math.pi * t + 1.2) > 0.2 else "spread"
    pelvic = "swept" if math.sin(2 * math.pi * t + 2.4) > 0 else "rest"
    layer = _static()
    ripple = math.sin(2 * math.pi * (t - 0.2))
    if ripple > 0.5:            # the soft dorsal and anal fin edges billow out a pixel
        layer[(22, 10)] = "n"
        layer[(22, 11)] = "n"
        layer[(18, 23)] = "n"
    elif ripple < -0.5:         # ...and draw back in
        layer.pop((21, 12), None)
        layer.pop((19, 21), None)
    for key, ch in _tail(near, far).items():
        layer.setdefault(key, ch)
    for x, y, ch in PELVIC[pelvic]:
        layer.setdefault((x, y), ch)
    return layer, {(x, y): part for x, y, part in PECT[pect]}


def _paint(layer: dict, pect: dict):
    """Paint letters to an image (in frame coordinates). Returns image and zone map."""
    img = canvas(SIZE)
    px = img.load()
    sx, sy = SHIFT
    zones = {}
    body = {k for k, ch in layer.items() if ch in BODY}
    for (x, y), ch in layer.items():
        X, Y = x + sx, y + sy
        if ch in BODY:
            colour, zone = BODY[ch]
            if (x, y) in pect and zone not in ("ear", "eye"):
                part = pect[(x, y)]
                tint, k = {"p": (FIN[3], 0.5), "P": (FIN[1], 0.62), "R": (FIN[5], 0.6)}[part]
                colour, zone = mix(colour, tint, k), "pect"
            px[X, Y] = rgba(colour)
            zones[(X, Y)] = zone
            continue
        colour, alpha = FINS[ch]
        c = rgba(colour)
        px[X, Y] = (c[0], c[1], c[2], alpha)
        zones[(X, Y)] = "fin"
    # 1 px outline around the whole silhouette: solid beside the body, a touch translucent
    # where it only borders fins; a touch lighter on the side facing the top-left light
    filled = set(zones)
    solid = {(x + sx, y + sy) for (x, y) in body}
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
            px[x, y] = o if any(p in solid for p in near) else (o[0], o[1], o[2], FIN_OUTLINE_ALPHA)
    return img, zones


def _along(x: int, y: int) -> float:
    """Distance along the body axis from the snout, in frame pixels."""
    return (x + 0.5 - 2.0) * AXIS[0] + (y + 0.5 - 10.5) * AXIS[1]


def _frame(t: float):
    layer, pect = _compose(t)
    img, zones = _paint(layer, pect)
    px = img.load()
    # a soft glint slides along the back from snout to tail during the first 70% of the
    # loop, then rests (a shine() band that follows the fish's own axis)
    run = 0.7
    if t < run:
        centre = -1.0 + 24.0 * (t / run)
        c = rgba(GLINT)
        for (x, y), depth in DEPTH.items():
            X, Y = x + SHIFT[0], y + SHIFT[1]
            if depth > 2 or layer.get((x, y)) in ("K", "W", "m", "E", "F") or zones.get((X, Y)) == "pect":
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.0) * 0.72 * (1.0 - depth / 3.4)
            if k <= 0:
                continue
            r, g, b, a = px[X, Y]
            px[X, Y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
