"""Pond Minnow: a common fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's olive / sage-mauve / brass-gold scheme. A stout little pond
minnow (fathead-like): a blunt rounded head with a small upturned mouth, an olive back
under a lit rim, a brassy line over a dusky lateral stripe that widens toward the tail
and ends in a dark spot at its base, fine staggered scale marks, a countershaded
mauve-to-cream belly, a big eye with a gold iris, a rounded dorsal fin carrying the dark
fathead blotch, a pectoral fin laid back over the flank, small pelvic and anal fins and a
shallow-forked tail (fins translucent olive-gold with painted rays and a softer outline).

Animation (COMMON, 8 frames x 3 ticks): the tail wags once per loop with its tips
leading, the dorsal tip, pectoral, pelvic and anal fins sway a pixel on offset phases,
and a soft glint slides along the back from snout to tail, then rests.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, sprite

ID = "fish_pond_minnow"
NAME = "Pond Minnow"
KIND = "item"
MODEL_KEY = "fish/pond_minnow"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 8
FRAMETIME = 3
SHIFT = (0, 0)           # move the whole drawing (x, y) if it needs re-centring

# ---- palettes (darkest -> lightest): olive shadows lean teal, lights lean brass -----------
BACK = ["#26321b", "#364a24", "#4b6330", "#657c3b", "#86994b", "#abb96a"]
BRASS = ["#9a7a2c", "#d2a83f", "#f2cf62", "#fbe79c"]
FLANK = ["#6a5f58", "#8a7a74", "#b09c96", "#c6b6a6", "#ddd7ba", "#eeeacd", "#f9f6e2"]
STRIPE = ["#2b331e", "#3e4a2c", "#56623c"]
FIN = ["#525a2e", "#68713e", "#868f4f", "#a3a965", "#c2c486", "#dedcac"]
BLOTCH = "#33311b"
OUTLINE = "#20261a"
OUTLINE_LIT = "#353d20"
FIN_OUTLINE_ALPHA = 236
GLINT = "#fbffe4"

BODY = {  # letter: (colour, zone)
    # back: rim light (brightest over the head), olive back, darker scale row, gill line
    "R": (BACK[5], "back"), "r": (BACK[4], "back"), "b": (BACK[3], "back"), "B": (BACK[2], "back"),
    "D": (BACK[1], "back"),
    # brassy line over the dusky lateral stripe; its dark end spot at the tail base
    "g": (BRASS[2], "line"), "G": (BRASS[1], "line"),
    "s": (STRIPE[1], "stripe"), "S": (STRIPE[0], "stripe"), "t": (STRIPE[2], "stripe"),
    # flank and belly: mauve-sage lower flank, cream belly, shaded belly edge
    "M": (FLANK[1], "flank"), "m": (FLANK[2], "flank"), "n": (FLANK[3], "belly"),
    "c": (FLANK[4], "belly"), "w": (FLANK[5], "belly"), "W": (FLANK[6], "belly"),
    # head: mouth line; eye with a catchlight and a gold iris
    "k": (OUTLINE, "head"), "E": ("#10140a", "eye"), "e": ("#ffffff", "eye"),
    "i": (BRASS[2], "eye"), "I": (BRASS[1], "eye"),
}
# Fins: membrane letters -> base colour; rays (capitals) and the dorsal blotch.
FINS = {"f": FIN[4], "a": FIN[3], "q": FIN[3]}
RAYS = {"F": FIN[1], "A": FIN[1], "Q": FIN[1]}
MEMBRANE_ALPHA, EDGE_ALPHA, BASE_ALPHA, RAY_ALPHA, BLOTCH_ALPHA = 212, 204, 224, 232, 226
SCALED = {"B": "D", "b": "B"}     # letter -> its scale-mark letter

# ---- the art (x 0..31 as drawn; SHIFT moves it) -------------------------------------------
# Static layer: head, body, dorsal fin. The tail, pectoral, pelvic and anal fins are
# layers of their own so they can sway.
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    ".............Ff.................",  # 6
    ".....RRRR...Ffff................",  # 7
    "...RRbbbbRRRFxxFf...............",  # 8
    "..RbeEiBDbbbRrxff...............",  # 9
    "..cbEEIbDggBbbrrff..............",  # 10
    "...kcIccMssggBbbrrf.............",  # 11
    "...wwwcwMmmssgGBbbrr............",  # 12
    "....nnwMwccmmssGGBbbr...........",  # 13
    "......nnnWWccmtssGGBbrr.........",  # 14
    ".........nnWWcmttssGGbbr........",  # 15
    "...........nnwwmmttssBBb........",  # 16
    ".............nnnwmmttSSS........",  # 17
    "................nnnmmSSt........",  # 18
    "...................nnnnn........",  # 19
    "................................",  # 20
]
# Dorsal tip: rest and rippled back a pixel.
DORSAL_TIP = {"rest": [(13, 6, "F"), (14, 6, "f")], "ripple": [(14, 6, "F"), (15, 6, "f")]}
# Pectoral fin poses, laid back over the flank behind the gill cover ("p" membrane,
# "P" its darker lower edge): spread down toward the belly, and folded along the body.
PECT = {
    "spread": [(9, 13, "p"), (10, 13, "p"), (10, 14, "p"), (11, 14, "P"), (11, 15, "P")],
    "folded": [(9, 13, "p"), (10, 13, "p"), (10, 14, "P"), (11, 14, "P"), (12, 14, "p")],
}
# Pelvic and anal fins: hanging, and swept back a pixel.
PELVIC = {"rest": [(11, 17, "a"), (12, 17, "A"), (12, 18, "a"), (13, 18, "A")],
          "swept": [(11, 17, "a"), (12, 17, "A"), (13, 18, "A"), (14, 18, "a")]}
ANAL = {"rest": [(16, 19, "a"), (17, 19, "A"), (18, 19, "a"), (18, 20, "A"), (19, 20, "a")],
        "swept": [(16, 19, "a"), (17, 19, "A"), (18, 19, "a"), (19, 20, "A"), (20, 20, "a")]}
# Tail (neutral pose) as rows from (24, 14): a shallow fork with rounded lobes.
TAIL_AT = (24, 14)
TAIL = [
    "....qq",  # 14
    "..qqQq",  # 15
    "qqQQq.",  # 16
    "QQqq..",  # 17
    "qqq...",  # 18
    "Qqq...",  # 19
    "qQqq..",  # 20
    ".qQq..",  # 21
    ".qqQ..",  # 22
    "..qq..",  # 23
    "...q..",  # 24
]
TAIL_BASE = (24.0, 18.0)      # where the tail joins the peduncle
AXIS = (math.cos(math.atan2(1, 2)), math.sin(math.atan2(1, 2)))   # the body's 2:1 axis


def _static() -> dict:
    out = {}
    for y, row in enumerate(ART):
        for x, ch in enumerate(row):
            if ch != ".":
                out[(x, y)] = ch
    return out


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
        along = (x + 0.5 - TAIL_BASE[0]) * AXIS[0] + (y + 0.5 - TAIL_BASE[1]) * AXIS[1]
        dy = far if along >= 3.6 else near if along >= 1.8 else 0
        out.setdefault((x, y + dy), ch)
    # close any hole the flex opened inside a column or a row
    for (x, y) in list(base):
        if (x, y) not in out and (((x, y - 1) in out and (x, y + 1) in out)
                                  or ((x - 1, y) in out and (x + 1, y) in out)):
            out[(x, y)] = "q"
    return out


def _compose(t: float):
    """Letters of every pixel of the frame at phase t, plus the pectoral overlay."""
    wag = math.sin(2 * math.pi * t)
    near, far = round(0.6 * wag), round(1.3 * wag)
    layer = _static()
    dorsal = "ripple" if math.sin(2 * math.pi * (t - 0.2)) > 0.35 else "rest"
    for x, y, ch in DORSAL_TIP[dorsal]:
        layer[(x, y)] = ch
    for key, ch in _tail(near, far).items():
        layer.setdefault(key, ch)
    low = math.sin(2 * math.pi * t + 2.0)
    for x, y, ch in PELVIC["swept" if low > 0 else "rest"]:
        layer.setdefault((x, y), ch)
    for x, y, ch in ANAL["swept" if math.sin(2 * math.pi * t + 1.2) > 0 else "rest"]:
        layer.setdefault((x, y), ch)
    pect = "folded" if math.sin(2 * math.pi * t + 0.8) > 0.2 else "spread"
    return layer, {(x, y): part for x, y, part in PECT[pect]}


def _scale_mark(x: int, y: int) -> bool:
    """A sparse diamond lattice (image space) for scale marks."""
    return y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1


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
            if ch in SCALED and x >= 9 and _scale_mark(X, Y):
                ch = SCALED[ch]
            colour, zone = BODY[ch]
            if (x, y) in pect:
                tint = FIN[4] if pect[(x, y)] == "p" else FIN[1]
                colour = mix(colour, tint, 0.62 if pect[(x, y)] == "p" else 0.7)
                zone = "pect"
            px[X, Y] = rgba(colour)
            zones[(X, Y)] = zone
            continue
        near = [layer.get((x + dx, y + dy)) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        if ch == "x":
            colour, alpha = BLOTCH, BLOTCH_ALPHA
        elif ch in RAYS:
            colour, alpha = RAYS[ch], RAY_ALPHA
        else:
            base = FINS[ch]
            open_up_left = layer.get((x, y - 1)) is None or layer.get((x - 1, y)) is None
            if any(n is None for n in near):
                colour, alpha = (FIN[4] if open_up_left else base), EDGE_ALPHA
            elif any(n in BODY for n in near):
                colour, alpha = (FIN[3] if ch == "f" else FIN[2]), BASE_ALPHA
            else:
                colour, alpha = base, MEMBRANE_ALPHA
        c = rgba(colour)
        px[X, Y] = (c[0], c[1], c[2], alpha)
        zones[(X, Y)] = "fin"
    # 1 px outline around the whole silhouette: solid beside the body, a touch translucent
    # where it only borders fins, a touch lighter on the side facing the top-left light
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
    return (x + 0.5 - 2.0 - SHIFT[0]) * AXIS[0] + (y + 0.5 - 9.5 - SHIFT[1]) * AXIS[1]


def _frame(t: float):
    layer, pect = _compose(t)
    img, zones = _paint(layer, pect)
    px = img.load()
    # a soft glint slides along the back and the brass line (frames 0-5), then rests
    step = round(t * FRAMES)
    if step <= 5:
        centre = -1.0 + 25.0 * step / 5.0
        g = rgba(GLINT)
        for (x, y), zone in zones.items():
            if zone not in ("back", "line", "head"):
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.2)
            if k <= 0:
                continue
            k *= 0.58 if zone == "back" else 0.4
            r, gg, b, a = px[x, y]
            px[x, y] = (round(r + (g[0] - r) * k), round(gg + (g[1] - gg) * k), round(b + (g[2] - b) * k), a)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
