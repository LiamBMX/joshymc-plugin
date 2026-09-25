"""Largemouth Bass: an uncommon fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection's shared pose (side view, head up-left, tail
down-right). Olive-green back under a lit rim, bronze-green flanks carrying the jagged dark
lateral band of blotches, a countershaded cream belly, the huge mouth whose jaw runs back
past the golden eye, the gill cover, a deeply notched spiny/soft dorsal fin, translucent
olive fins with painted rays and a broad, forked tail.

Animation (UNCOMMON, 12 frames x 3 ticks): the tail wags once per loop with its tips
leading, the pectoral and pelvic fins sway a pixel, a soft glint slides along the back
(frames 1-5) and then a shimmer band of sparkling scales rolls across the body (7-11).
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, shade, shine, sprite

ID = "fish_largemouth_bass"
NAME = "Largemouth Bass"
KIND = "item"
MODEL_KEY = "fish/largemouth_bass"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3
SHIFT = (1, -1)          # the art below is drawn one pixel left / one down of centre

# ---- palette: hue-shifted ramps (shadows lean teal, lights lean yellow) --------------------
GREEN = "#4f8a3a"      # flank
CREAM = "#eaf0c8"      # belly and jaw
OLIVE = "#708f40"      # dorsal fins and tail
LOWFIN = mix(OLIVE, CREAM, 0.35)


def _g(amount: float) -> str:
    return shade(GREEN, amount, 0.25)


def _f(amount: float, base: str = OLIVE) -> str:
    return shade(base, amount, 0.15)


# One 7-tone ramp for the whole body, darkest to lightest; the light tone leans bronze.
SKIN = [_g(-0.6), _g(-0.42), _g(-0.22), _g(0.0), mix(_g(0.34), "#d6c25c", 0.24), _g(0.56), _g(0.74)]
BELLY = [shade(CREAM, -0.18, 0.1), shade(CREAM, -0.09, 0.1), CREAM, shade(CREAM, 0.4, 0.1)]
OUTLINE = _g(-0.69)
OUTLINE_LIT = _g(-0.6)

BODY = {  # letter: (colour, zone)
    # back: forehead light, rim light (fading toward the tail), olive back
    "K": (SKIN[6], "back"), "k": (SKIN[5], "back"), "y": (SKIN[4], "back"),
    "b": (SKIN[2], "back"), "B": (SKIN[1], "back"),
    # flanks: sheen, flank, bronze-green lower flank; the lateral band of blotches
    "G": (SKIN[4], "flank"), "g": (SKIN[3], "flank"), "l": (SKIN[4], "flank"),
    "d": (SKIN[1], "band"), "D": (SKIN[0], "band"),
    # head: crown, cheek plate, gill cover edge, mouth, pale lower jaw
    "H": (SKIN[4], "head"), "h": (SKIN[3], "head"), "c": (SKIN[4], "head"),
    "n": (SKIN[1], "line"), "m": (OUTLINE, "line"), "j": (BELLY[2], "head"),
    # belly (countershaded)
    "v": (BELLY[0], "belly"), "u": (BELLY[1], "belly"), "w": (BELLY[2], "belly"), "W": (BELLY[3], "belly"),
    # eye: 2x2 pupil with a catchlight, a touch of golden iris behind it
    "E": ("#0f1e18", "eye"), "e": ("#f4ffec", "eye"), "i": ("#b3913a", "eye"),
}
# Fins: letter -> (membrane base colour, zone). Rays are the capital letters.
FINS = {"f": (OLIVE, "fin"), "t": (OLIVE, "tail"), "a": (LOWFIN, "fin")}
RAYS = {"r": (OLIVE, "fin"), "T": (OLIVE, "tail"), "A": (LOWFIN, "fin")}
MEMBRANE_ALPHA, EDGE_ALPHA, BASE_ALPHA, RAY_ALPHA = 214, 206, 224, 234
SCALED = {"G": "g", "l": "g", "g": "b", "b": "B", "w": "u", "d": "D"}   # letter -> its scale-mark letter
PECT_TINT, PECT_EDGE = _f(0.3), _f(-0.32)
FIN_OUTLINE_ALPHA = 236
GLINT = "#f7ffdc"
SHIMMER = "#eef6b0"
SPARK = "#fbffe6"

# ---- the art (x 0..31 as drawn; SHIFT moves it to centre) -----------------------------------
# Static layer: head, body, dorsal/anal/pelvic fins. The tail and pectoral fin are layers
# of their own so they can sway.
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    "................................",  # 6
    "....KKkk......r.................",  # 7
    "...KHHHhkk...rf.................",  # 8
    "..KHeEhhnbkkrfffr...............",  # 9
    ".jmcEEicnGbbkffrf...............",  # 10
    "..jmccccnGGgbkrf..f.............",  # 11
    "..vjmmccnDdggbkffrff............",  # 12
    "...vjjmnlDDddgbkrfffr...........",  # 13
    "....vvnWwlldDdgbkffrff..........",  # 14
    "......vWwwllDDdgbyrfff..........",  # 15
    ".......vvwwlllDDDByff...........",  # 16
    ".........vvwwwlDddByf...........",  # 17
    "...........vvvvwldDdyy..........",  # 18
    ".............aavvvDDd...........",  # 19
    ".............aaaaavvv...........",  # 20
    ".............aaa................",  # 21
    "................................",  # 22
]
# Pectoral fin poses (x, y, part): spread and folded against the body. "p" is the membrane
# (blended over the flank), "P" the dark lower edge.
PECT = {
    "spread": [(9, 14, "p"), (10, 14, "p"), (9, 15, "P"), (10, 15, "p"), (11, 15, "p"),
               (10, 16, "P"), (11, 16, "P"), (12, 16, "P")],
    "folded": [(9, 14, "p"), (10, 14, "p"), (11, 14, "p"), (9, 15, "P"), (10, 15, "P"), (11, 15, "p"),
               (12, 15, "P")],
}
# Pelvic fin poses: hanging, and swept back a pixel.
PELVIC = {"rest": [(7, 17), (8, 17), (7, 18), (8, 18), (8, 19)],
          "swept": [(7, 17), (8, 17), (8, 18), (9, 18), (9, 19)]}
# Tail (neutral pose) as rows from (19, 17).
TAIL_AT = (19, 17)
TAIL = [
    "..........",  # 17
    "...tttttt.",  # 18
    "..ttTTTtt.",  # 19
    "..tttttt..",  # 20
    "ttTTt.....",  # 21
    ".tttT.....",  # 22
    "..Ttttt...",  # 23
    "..tTtt....",  # 24
    "...tT.....",  # 25
    "..........",  # 26
]
TAIL_BASE = (21.0, 19.5)      # where the tail joins the peduncle
AXIS = (math.cos(math.radians(30)), math.sin(math.radians(30)))


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
        dy = far if along >= 4.4 else near if along >= 2.2 else 0
        out.setdefault((x, y + dy), ch)
    # close any hole the flex opened inside a column
    step = 1 if far > 0 else -1
    for (x, y) in list(base):
        if (x, y) not in out and (x, y - step) in out and (x, y + step) in out:
            out[(x, y)] = "t"
    return out


def _compose(t: float):
    """Letters of every pixel of the frame at phase t, plus the pectoral overlay."""
    wag = math.sin(2 * math.pi * t)
    near, far = round(0.75 * wag), round(1.35 * wag)
    pect = "folded" if math.sin(2 * math.pi * 2 * t + 1.0) > 0.3 else "spread"
    pelvic = "swept" if math.sin(2 * math.pi * t + 2.0) > 0 else "rest"
    layer = _static()
    for key, ch in _tail(near, far).items():
        layer.setdefault(key, ch)
    for x, y in PELVIC[pelvic]:
        layer.setdefault((x, y), "a")
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
            if ch in SCALED and _scale_mark(X, Y):
                ch = SCALED[ch]
            colour, zone = BODY[ch]
            if (x, y) in pect:
                tint = PECT_TINT if pect[(x, y)] == "p" else PECT_EDGE
                colour = mix(colour, tint, 0.6 if pect[(x, y)] == "p" else 0.75)
                zone = "pect"
            px[X, Y] = rgba(colour)
            zones[(X, Y)] = zone
            continue
        near = [layer.get((x + dx, y + dy)) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        if ch in RAYS:
            base, zone = RAYS[ch]
            colour, alpha = _f(-0.3, base), RAY_ALPHA
        else:
            base, zone = FINS[ch]
            if any(n is None for n in near):
                colour, alpha = _f(0.24, base), EDGE_ALPHA
            elif any(n in BODY for n in near):
                colour, alpha = _f(-0.12, base), BASE_ALPHA
            else:
                colour, alpha = base, MEMBRANE_ALPHA
        c = rgba(colour)
        px[X, Y] = (c[0], c[1], c[2], alpha)
        zones[(X, Y)] = zone
    # 1 px outline around the whole silhouette: solid beside the body, a touch translucent
    # where it only borders fins
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
            # selective outline: a touch lighter where the silhouette lies below/right of this
            # pixel, i.e. on the side facing the top-left light
            facing_light = ((x, y + 1) in filled or (x + 1, y) in filled) and \
                (x, y - 1) not in filled and (x - 1, y) not in filled
            o = lit if facing_light else dark
            px[x, y] = o if any(p in solid for p in near) else (o[0], o[1], o[2], FIN_OUTLINE_ALPHA)
    return img, zones


def _along(x: int, y: int) -> float:
    """Distance along the body axis from the snout, in frame pixels."""
    return (x + 0.5 - 2.0) * AXIS[0] + (y + 0.5 - 8.5) * AXIS[1]


def _sparkle_spot(x: int, y: int) -> bool:
    """A denser diamond lattice: where the scales catch the light as the shimmer passes."""
    return y % 2 == 1 and (x + y // 2) % 2 == 0


def _frame(t: float):
    layer, pect = _compose(t)
    img, zones = _paint(layer, pect)
    px = img.load()
    step = round(t * FRAMES)
    # 1) frames 1-5: a soft glint slides along the back, snout to tail (shine() mapped so
    #    its band travels exactly the length of the back). Frames 0 and 6 rest.
    if 1 <= step <= 5:
        centre = 8.0 + 21.0 * (step - 1) / 4.0
        width = 3.5
        run = (centre + 2 * width) / (32 * AXIS[0] + 32 * AXIS[1] + 4 * width)
        glint = shine(img, run, colour=GLINT, width=width, strength=0.62, angle=30.0, pause=0.0).load()
        for (x, y), zone in zones.items():
            if zone in ("back", "head"):
                px[x, y] = glint[x, y]
    # 2) frames 7-11: a shimmer band rolls across the scales, head to tail
    elif step >= 7:
        centre = 6.0 + 17.0 * (step - 7) / 4.0
        for (x, y), zone in zones.items():
            if zone not in ("flank", "band", "belly", "back", "pect"):
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.2)
            if k <= 0:
                continue
            spark = _sparkle_spot(x, y) and zone in ("flank", "back", "belly", "band")
            strength = (0.95 if spark else 0.36) * min(1.0, k * 1.3)
            if zone == "band" and not spark:
                strength *= 0.6
            px[x, y] = rgba(mix(px[x, y], SPARK if spark else SHIMMER, strength))
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
