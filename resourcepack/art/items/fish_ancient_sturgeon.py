"""Ancient Sturgeon: a rare river fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's mossy olive-grey body, pale bone plates and pale eye. Long
armoured body: a pointed shovel snout with barbels hanging in front of the small ventral
mouth, a high-set eye with a pale ring, a plated head with a lit gill-cover edge, a
serrated back of standing dorsal scutes, a row of bright lateral scutes (each with its
own shadow) along the flank, a countershaded grey-cream belly, dusky paddle pectorals, a
small dorsal fin set far back over the pelvic and anal fins, and the heterocercal tail
whose long, plated upper lobe carries the back on (fins translucent with painted rays).

Animation (RARE, 12 frames x 3 ticks): the tail beats about a pixel with its lobe tips
leading, the fins sway on offset phases; a soft glint slides along the plated back
(frames 1-5), then a blue-tinted sheen rolls down the body and flashes each scute it
passes (frames 7-11), while three small sparkles twinkle around the fish in turn.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_ancient_sturgeon"
NAME = "Ancient Sturgeon"
KIND = "item"
MODEL_KEY = "fish/ancient_sturgeon"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3
SHIFT = (0, 1)            # the art below is drawn one pixel above centre

# ---- palettes (darkest -> lightest): the old sprite's mossy olive, grey-green and bone,
# hue-shifted (shadows lean teal, lights lean khaki) -------------------------------------------
BACK = ["#1f2a1e", "#2e3b2a", "#3e4c33", "#52603c", "#6e7a4c", "#929c66"]
FLANK = ["#56643f", "#6b7751", "#848e66", "#9ca47c"]
BELLY = ["#9a9d82", "#b6b79c", "#d2d1b8", "#e6e3cc"]
SCUTE = ["#2c3624", "#a8a283", "#d8d1ae", "#f1ead0", "#fffbeb"]
HEADP = ["#2a3626", "#46553a", "#5d6a45", "#7c8659", "#a3aa7e"]   # plates, cheek, lit edge
SNOUT = ["#7b7d57", "#999870", "#b7b388"]
OUTLINE = "#141d15"
OUTLINE_LIT = "#1d291c"
FIN = ["#3f4630", "#565d3e", "#6c714b", "#888b5e", "#b0ae83"]    # dusky olive-bronze fins
FIN_ALPHA, FIN_EDGE_ALPHA, FIN_ROOT_ALPHA, RAY_ALPHA = 212, 204, 224, 236
FIN_OUTLINE_ALPHA = 232
BARBEL = ["#bba982", "#8e8062"]
MOUTH = "#2b2622"
EYE = ["#0e1411", "#ffffff"]
EYE_RING = "#c6c29f"
GLINT = "#f2f8e4"
SHEEN = "#8cc2ff"                                    # the rare blue-tinted sheen
SPARK = "#e6f4ff"

BODY = {  # letter -> (colour, zone)
    # back: crown / rim light, rear rim, dark back, back
    "A": (BACK[5], "back"), "a": (BACK[4], "back"), "b": (BACK[1], "back"), "B": (BACK[2], "back"),
    # flank between the lateral scutes
    "f": (FLANK[0], "body"),
    # belly (countershaded, its lower edge turning away from the light)
    "V": (BELLY[1], "body"), "W": (BELLY[2], "body"), "u": (BELLY[0], "body"),
    # scutes: standing / lateral plates and their lit bases
    "S": (SCUTE[3], "scute"), "s": (SCUTE[2], "scute"),
    # head: plates, cheek, gill seam and the lit gill-cover edge, snout
    "H": (HEADP[2], "head"), "h": (HEADP[1], "head"), "c": (HEADP[3], "head"), "L": (HEADP[4], "head"),
    "G": (HEADP[0], "head"), "N": (SNOUT[2], "head"), "n": (SNOUT[1], "head"),
    # eye: 2x2 pupil with a catchlight, a pale ring; the ventral mouth
    "E": (EYE[0], "eye"), "e": (EYE[1], "eye"), "R": (EYE_RING, "eye"), "m": (MOUTH, "eye"),
    # tail: the plated upper lobe (opaque, it carries the back on)
    "T": (BACK[4], "back"), "Y": (BACK[1], "back"),
}

# ---- the art (x 0..31 as drawn; SHIFT centres it) --------------------------------------------
ART = {
    7:  "......AAAA.S",
    8:  "...NNNheERAs.S",
    9:  "..NnnnREEhGbAs.S",
    10: "....uuccchLGbbAs",
    11: "......umccLGBBbbA",
    12: "........ucLGfSBBbaa",
    13: ".........uuWVVfSBbba",
    14: "...........uWWVVfSBbaa",
    15: "............uuWWVVfSbba",
    16: "..............uuWWWVfSba",
    17: "................uuuWWWfb",
    18: "...................uuuWW",
    19: "......................uu",
}
BARBELS = [[(3, 10), (3, 11)], [(5, 11), (5, 12)]]

# Fins (translucent): lower-case membranes, capitals rays / lit leading edges.
DORSAL = {(18, 11): "D", (19, 10): "D", (19, 11): "d", (19, 12): "d", (20, 11): "d", (20, 12): "D",
          (20, 13): "d", (21, 13): "d"}
DORSAL_RIPPLE = {(20, 10): "d"}                           # the tip flicks up
# Near pectoral (drawn over the body): swept back, and flapped down a pixel.
PECT = {
    "spread": {(9, 14): "P", (10, 15): "P", (11, 16): "P", (12, 17): "P", (10, 14): "p", (11, 15): "p",
               (12, 16): "p", (13, 16): "p", (13, 17): "p", (14, 17): "p", (10, 13): "r", (11, 14): "r"},
    "folded": {(9, 14): "P", (10, 15): "P", (10, 16): "P", (11, 17): "P", (10, 14): "p", (11, 15): "p",
               (11, 16): "p", (12, 16): "p", (12, 17): "p", (13, 16): "p", (10, 13): "r", (11, 14): "r"},
}
PELVIC = {"rest": [(16, 18), (17, 18), (17, 19)], "swept": [(17, 18), (18, 18), (18, 19)]}
ANAL = [(20, 19), (21, 19), (21, 20), (22, 20)]
# Tail, neutral pose: the long plated upper lobe (T edge, Y plates) over the membrane (k)
# with its rays (K), the short lower lobe hanging under it.
TAIL = {
    (24, 16): "T", (24, 17): "Y", (24, 18): "k", (24, 19): "K", (24, 20): "k", (24, 21): "k",
    (25, 17): "T", (25, 18): "Y", (25, 19): "k", (25, 20): "K", (25, 21): "k", (25, 22): "k",
    (26, 17): "T", (26, 18): "Y", (26, 19): "K", (26, 20): "k",
    (27, 18): "T", (27, 19): "Y", (27, 20): "k",
    (28, 19): "T", (28, 20): "Y",
    (29, 20): "T",
}
TAIL_BASE = (23.5, 17.5)
TAIL_AXIS = (math.cos(math.radians(38)), math.sin(math.radians(38)))
BODY_AXIS = (math.cos(math.radians(27)), math.sin(math.radians(27)))
SNOUT_TIP = (2.0, 9.0)


def _static() -> dict:
    out = {}
    for y, row in ART.items():
        for x, ch in enumerate(row):
            if ch != ".":
                out[(x, y)] = ch
    return out


BODY_LETTERS = _static()


def _tail(near: int, far: int) -> dict:
    """The tail flexed: pixels 2-4 px out from the root move `near` rows, those further
    out move `far` rows (positive: down), so the lobe tips lead the beat."""
    if near == 0 and far == 0:
        return dict(TAIL)
    out = {}
    for (x, y), ch in TAIL.items():
        along = (x + 0.5 - TAIL_BASE[0]) * TAIL_AXIS[0] + (y + 0.5 - TAIL_BASE[1]) * TAIL_AXIS[1]
        dy = far if along >= 4.0 else near if along >= 2.0 else 0
        out.setdefault((x, y + dy), ch)
    step = 1 if far > 0 else -1
    for (x, y) in list(TAIL):
        if (x, y) not in out and (x, y - step) in out and (x, y + step) in out:
            out[(x, y)] = "k"
    return out


def _compose(t: float):
    """Letters behind the body (tail, medial and pelvic fins) and the pectoral overlay."""
    wag = math.sin(2 * math.pi * t)
    near, far = round(0.6 * wag), round(1.3 * wag)
    pect = "folded" if math.sin(2 * math.pi * 2 * t + 1.0) > 0.3 else "spread"
    pelvic = "swept" if math.sin(2 * math.pi * t + 2.0) > 0 else "rest"
    ripple = math.sin(2 * math.pi * (t - 0.15)) > 0.4
    back = _tail(near, far)
    back.update(DORSAL)
    if ripple:
        for k, ch in DORSAL_RIPPLE.items():
            back.setdefault(k, ch)
    for k in ANAL + PELVIC[pelvic]:
        back[k] = "q"
    return back, PECT[pect]


def _fin_colour(ch: str, near_empty: bool, near_body: bool):
    if ch == "D":                                  # lit leading rays
        return FIN[4], RAY_ALPHA
    if ch == "P":                                  # the pectoral's thick bony leading ray
        return SCUTE[1], 242
    if ch == "K":                                  # darker rays in the tail membrane
        return FIN[1], RAY_ALPHA
    if ch == "r":                                  # pectoral root, over the body
        return FIN[2], 255
    if near_empty:
        return FIN[3], FIN_EDGE_ALPHA
    if near_body:
        return FIN[1], FIN_ROOT_ALPHA
    return FIN[2], FIN_ALPHA


def _paint(t: float):
    back, pect = _compose(t)
    img = canvas(SIZE)
    px = img.load()
    sx, sy = SHIFT
    zones = {}
    layer = dict(back)
    layer.update(BODY_LETTERS)
    layer.update(pect)
    solid = set(BODY_LETTERS) | {k for k, ch in back.items() if ch in ("T", "Y")}
    for (x, y), ch in layer.items():
        X, Y = x + sx, y + sy
        if (x, y) in solid and (x, y) not in pect:
            colour, zone = BODY[ch]
            px[X, Y] = rgba(colour)
            zones[(X, Y)] = zone
            continue
        near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        near_empty = any(n not in layer for n in near)
        near_body = any(n in solid and n not in pect for n in near)
        colour, alpha = _fin_colour(ch, near_empty, near_body)
        if ch == "r":
            colour = mix(BODY[BODY_LETTERS.get((x, y), "u")][0], colour, 0.6)
        c = rgba(colour)
        px[X, Y] = (c[0], c[1], c[2], alpha)
        zones[(X, Y)] = "fin"
    # 1 px outline: solid beside the body, a touch translucent where it only borders fins,
    # a little lighter on the sides facing the top-left light
    filled = set(zones)
    solid_px = {(x + sx, y + sy) for (x, y) in solid}
    dark, lit = rgba(OUTLINE), rgba(OUTLINE_LIT)
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in filled:
                continue
            nb = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + dx, y + dy) in filled]
            if not nb:
                continue
            facing = ((x, y + 1) in filled or (x + 1, y) in filled) and \
                (x, y - 1) not in filled and (x - 1, y) not in filled
            o = lit if facing else dark
            px[x, y] = o if any(p in solid_px for p in nb) else (o[0], o[1], o[2], FIN_OUTLINE_ALPHA)
    # barbels hang under the snout, drawn over the outline
    for strand in BARBELS:
        for i, (x, y) in enumerate(strand):
            px[x + sx, y + sy] = rgba(BARBEL[min(i, 1)])
    return img, zones


def _along(x: int, y: int) -> float:
    """Distance along the body from the snout, in frame pixels."""
    return ((x + 0.5 - SNOUT_TIP[0] - SHIFT[0]) * BODY_AXIS[0]
            + (y + 0.5 - SNOUT_TIP[1] - SHIFT[1]) * BODY_AXIS[1])


def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


# Sparkles around the fish (frame coordinates): (x, y, phase, reach).
SPARKLES = [(25, 10, 0.0, 2), (6, 19, 0.33, 2), (15, 24, 0.66, 1)]


def _frame(t: float):
    img, zones = _paint(t)
    px = img.load()
    step = round(t * FRAMES)
    if 1 <= step <= 5:            # a soft glint slides along the plated back
        centre = 3.0 + 22.0 * (step - 1) / 4.0
        for (x, y), zone in zones.items():
            if zone not in ("back", "head", "scute"):
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.0) * 0.72
            if zone == "head":
                k *= 0.6
            if k > 0:
                _blend(px, x, y, GLINT, k)
    elif step >= 7:               # a blue sheen rolls down the body, flashing each scute
        centre = 5.0 + 20.0 * (step - 7) / 4.0
        for (x, y), zone in zones.items():
            if zone == "eye":
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.4)
            if k <= 0:
                continue
            if zone == "scute":
                _blend(px, x, y, SCUTE[4], min(1.0, 1.3 * k))
            elif zone == "fin":
                _blend(px, x, y, SHEEN, 0.3 * k)
            else:
                _blend(px, x, y, SHEEN, 0.4 * k)
    for x, y, phase, reach in SPARKLES:
        amount = max(0.0, wave(t, phase) * 1.6 - 0.6)       # each lit for about a third of the loop
        sparkle(img, x, y, amount, SPARK, reach=reach)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
