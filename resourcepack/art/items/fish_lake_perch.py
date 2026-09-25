"""Lake Perch: a common barred panfish for the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's olive / gold / cream scheme and giving it real yellow-perch
anatomy: a pointed snout with a terminal mouth, a back that humps up behind the head, a
deep golden body crossed by five dark olive saddle bars that taper down the flank, a
countershaded cream belly, a big eye with a golden iris, the gill-cover edge, a tall spiny
first dorsal fin carrying the perch's dark rear blotch, a separate soft second dorsal,
bright orange pelvic and anal fins, an amber pectoral fin and a forked olive tail (fins
translucent with painted rays).

Animation (COMMON, 8 frames x 3 ticks): the tail wags once per loop with its tips leading,
the pectoral and pelvic fins sway a pixel on offset phases and a soft glint slides along
the back from snout to tail, then rests.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, shade, sprite

ID = "fish_lake_perch"
NAME = "Lake Perch"
KIND = "item"
MODEL_KEY = "fish/lake_perch"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 8
FRAMETIME = 3
SHIFT = (0, 2)           # the art below is drawn two pixels above centre

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean green-blue, lights warm ------
OUTLINE = "#2c3012"
OUTLINE_LIT = "#3b3d16"
BACK = ["#34401a", "#526026", "#6a7830", "#848f38", "#a6aa48", "#c8c76c"]
GOLD = ["#86701f", "#ad972b", "#cbb33c", "#e2ca50", "#efdc76", "#f8eea9"]
BELLY = ["#cfc289", "#e6dcac", "#f4edcf", "#fffbe9"]
BAR = ["#2f3a16", "#3f4a1b", "#566024", "#737a30"]
ORANGE = ["#9a3a15", "#c75620", "#e57a31", "#f4a85c"]
FIN = ["#4a5320", "#687130", "#8d953e", "#b2b45a", "#d3d188", "#ece9b6"]
BLOTCH = "#283015"
IRIS = ["#8b6c1d", "#caa032", "#efd05a"]
PECT = "#f0bf62"
GLINT = "#fffbe2"

BODY = {  # letter: (colour, zone)
    # back: bright forehead, rim light, olive back
    "E": (BACK[5], "back"), "e": (BACK[4], "back"), "d": (BACK[3], "back"),
    "c": (BACK[2], "back"), "b": (BACK[1], "back"),
    # flanks: olive-gold transition, golden flank, pale lower flank
    "1": (GOLD[1], "flank"), "3": (GOLD[3], "flank"), "4": (GOLD[4], "flank"),
    # the saddle bars (darkest in the back, fading down the flank)
    "w": (BAR[0], "bar"), "x": (BAR[1], "bar"), "y": (BAR[2], "bar"), "z": (BAR[3], "bar"),
    # belly (countershaded)
    "p": (BELLY[0], "belly"), "r": (BELLY[2], "belly"),
    # head: gill-cover edge, mouth
    "n": (GOLD[0], "line"), "m": (OUTLINE, "line"),
    # eye: 2x2 pupil with a catchlight, golden iris behind and below it
    "K": ("#11140a", "eye"), "W": ("#ffffff", "eye"), "h": (IRIS[1], "eye"), "i": (IRIS[2], "eye"),
}
# Fins: letter -> (colour, zone, kind). kind "m" membrane (shaded by position), "r" ray.
FINS = {
    "f": (FIN[3], "fin", "m"), "l": (FIN[4], "fin", "r"), "r": (FIN[2], "fin", "r"),
    "k": (BLOTCH, "fin", "r"),
    "t": (FIN[2], "tail", "m"), "T": (FIN[1], "tail", "r"),
    "a": (ORANGE[2], "orange", "m"), "A": (ORANGE[1], "orange", "r"),
}
MEMBRANE_ALPHA, EDGE_ALPHA, BASE_ALPHA, RAY_ALPHA = 208, 198, 222, 232
FIN_OUTLINE_ALPHA = 234
SCALE_MARK = {"3": GOLD[2], "4": GOLD[3]}   # a sparse lattice of scale marks on the flank

# ---- the art (x 0..31 as drawn; SHIFT moves it to centre) -----------------------------------
# Static layer: head, body, both dorsal fins and the anal fin. The tail, pectoral and pelvic
# fins are layers of their own so they can sway.
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "..............l.................",  # 4
    ".............lfr................",  # 5
    "............lfrfr...............",  # 6
    ".......EEEelfrfrkf..............",  # 7
    "....EEEcccceerfkkf..............",  # 8
    "...EcWKh14nccwykff..............",  # 9
    "..E44KKi34n1wwcef..lf...........",  # 10
    "..rmmhi34n33xx1cwylfrf..........",  # 11
    "...rr434n33xx33wwbdffrf.........",  # 12
    "....pp44n44yy33xxcwyfrf.........",  # 13
    "......ppprrz44xx3wwbdff.........",  # 14
    ".........pprrzy43xx1wyf.........",  # 15
    "...........ppprryy3wwbd.........",  # 16
    "..............ppzr4xx3wy........",  # 17
    "..............aapppz4xxd........",  # 18
    "...............Aaaappppd........",  # 19
    "................aA..............",  # 20
    "................a...............",  # 21
    "................................",  # 22
]
# Tail (neutral pose), absolute coordinates as drawn.
TAIL_AT = (24, 17)
TAIL = [
    "tt....",  # 17
    "tTtt..",  # 18
    "ttTTtt",  # 19
    "tTt...",  # 20
    "ttTt..",  # 21
    ".tTt..",  # 22
    "..tT..",  # 23
    "...t..",  # 24
]
TAIL_BASE = (24.0, 19.0)      # the tail swings about its root on the peduncle
TAIL_SWING = 6.5              # degrees either way
AXIS = (math.cos(math.radians(32)), math.sin(math.radians(32)))
# Pectoral fin poses (x, y, part) over the flank: "p" membrane, "P" darker lower edge.
PECTORAL = {
    "spread": [(10, 12, "p"), (11, 12, "p"), (10, 13, "p"), (11, 13, "p"), (12, 13, "p"),
               (11, 14, "P"), (12, 14, "P"), (13, 14, "P")],
    "folded": [(10, 12, "p"), (11, 12, "p"), (12, 12, "p"), (11, 13, "p"), (12, 13, "P"), (13, 13, "P")],
}
# Pelvic fin poses below the belly: hanging, and swept back a pixel.
PELVIC = {"rest": [(7, 15, "a"), (8, 15, "A"), (8, 16, "a"), (9, 16, "a"), (9, 17, "a")],
          "swept": [(8, 15, "a"), (9, 16, "A"), (10, 16, "a"), (10, 17, "a"), (9, 17, "a")]}


def _static() -> dict:
    out = {}
    for y, row in enumerate(ART):
        for x, ch in enumerate(row):
            if ch != ".":
                out[(x, y)] = ch
    return out


def _tail(angle: float) -> dict:
    """The tail turned `angle` degrees about its root (nearest-pixel rotation, so the lobe
    tips travel furthest and the root stays put)."""
    base = {}
    for j, row in enumerate(TAIL):
        for i, ch in enumerate(row):
            if ch != ".":
                base[(TAIL_AT[0] + i, TAIL_AT[1] + j)] = ch
    if abs(angle) < 0.5:
        return base
    a = math.radians(-angle)
    ca, sa = math.cos(a), math.sin(a)
    bx, by = TAIL_BASE
    out = {}
    for y in range(TAIL_AT[1] - 3, TAIL_AT[1] + len(TAIL) + 3):
        for x in range(TAIL_AT[0], TAIL_AT[0] + len(TAIL[0]) + 3):
            dx, dy = x + 0.5 - bx, y + 0.5 - by
            sx, sy = bx + dx * ca - dy * sa, by + dx * sa + dy * ca
            ch = base.get((math.floor(sx), math.floor(sy)))
            if ch:
                out[(x, y)] = ch
    return out


def _compose(t: float):
    """Letters of every pixel of the frame at phase t, plus the pectoral overlay."""
    wag = math.sin(2 * math.pi * t)
    pect = "folded" if math.sin(2 * math.pi * t + 1.0) > 0.0 else "spread"
    pelvic = "swept" if math.sin(2 * math.pi * t + 2.6) > 0.0 else "rest"
    layer = _static()
    for key, ch in _tail(TAIL_SWING * wag).items():
        layer.setdefault(key, ch)
    for x, y, ch in PELVIC[pelvic]:
        layer.setdefault((x, y), ch)
    return layer, {(x, y): part for x, y, part in PECTORAL[pect]}


def _scale_mark(x: int, y: int) -> bool:
    """A sparse diagonal lattice (image space) for scale marks."""
    return (x + 2 * y) % 5 == 0


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
            if ch in SCALE_MARK and _scale_mark(X, Y):
                colour = SCALE_MARK[ch]
            if (x, y) in pect:
                part = pect[(x, y)]
                colour = mix(colour, PECT if part == "p" else ORANGE[1], 0.45 if part == "p" else 0.5)
                zone = "pect"
            px[X, Y] = rgba(colour)
            zones[(X, Y)] = zone
            continue
        colour, zone, kind = FINS[ch]
        near = [layer.get((x + dx, y + dy)) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        if kind == "r":
            alpha = RAY_ALPHA
        elif any(n is None for n in near):
            colour, alpha = shade(colour, 0.22, 0.15), EDGE_ALPHA        # lit, thin fin edge
        elif any(n in BODY for n in near):
            colour, alpha = shade(colour, -0.12, 0.15), BASE_ALPHA       # fin root, a touch darker
        else:
            alpha = MEMBRANE_ALPHA
        c = rgba(colour)
        px[X, Y] = (c[0], c[1], c[2], alpha)
        zones[(X, Y)] = zone
    # 1 px outline around the whole silhouette: solid beside the body, a touch translucent
    # where it only borders fins; a little lighter on the side facing the top-left light
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
    return (x + 0.5 - 2.0 - SHIFT[0]) * AXIS[0] + (y + 0.5 - 11.0 - SHIFT[1]) * AXIS[1]


GLINT_DEPTH = {"E": 1.0, "e": 1.0, "d": 0.9, "c": 0.7, "b": 0.6, "w": 0.45, "y": 0.6}


def _frame(t: float):
    layer, pect = _compose(t)
    img, zones = _paint(layer, pect)
    px = img.load()
    # a soft glint slides along the back (snout to tail) over frames 0-5; frames 6-7 rest
    step = round(t * FRAMES)
    if step <= 5:
        centre = -1.0 + 26.0 * step / 5.0
        c = rgba(GLINT)
        for (x, y), zone in zones.items():
            if zone not in ("back", "bar", "flank"):
                continue
            ch = layer.get((x - SHIFT[0], y - SHIFT[1]))
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 2.6) * 0.62 * GLINT_DEPTH.get(ch, 0.25)
            if k <= 0:
                continue
            r, g, b, a = px[x, y]
            px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
