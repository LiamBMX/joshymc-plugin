"""End Bass: an epic fish of the JoshyMC fishing collection, found in the End.

A flat 32x32 sprite in the collection's shared pose (side view, head up-left, tail
down-right), keeping the old sprite's lavender / purpur scheme and white eye. A deep bass
body: a dark indigo back under a pink-lit rim, freckled with pale end-stone flecks,
lavender flanks carrying the jagged lateral band of deep violet blotches, a countershaded
pale lilac belly, the big bass mouth whose jaw runs back under an ender-magenta eye, a tall
crystal-spiked spiny dorsal with pale end-rod tips, a notched soft dorsal and a broad forked
tail with chorus-pink tips (fins translucent with painted rays).

Animation (EPIC, 16 frames x 3 ticks): the tail wags once per loop with its tips leading,
the pectoral and pelvic fins sway a pixel, an oil-slick iridescence slowly drifts purple ->
pink -> cyan across the scales, then a brighter rainbow sheen sweeps from snout to tail
with the scales sparkling and a glint riding the back, while three soft four-point stars
twinkle in turn around the fish.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, shade, sparkle, sprite

ID = "fish_end_bass"
NAME = "End Bass"
KIND = "item"
MODEL_KEY = "fish/end_bass"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 16
FRAMETIME = 3
SHIFT = (1, -1)          # the art below is drawn one pixel left / one down of centre

# ---- palette: hue-shifted ramps (shadows lean indigo, lights lean pink) -------------------
VIOLET = "#8c5fcc"     # flank (the old sprite's lavender, a touch deeper)
LILAC = "#f2e3f5"      # belly and jaw
FINC = "#aa68d4"       # dorsal fins and tail
LOWFIN = mix(FINC, LILAC, 0.35)
SPINY = "#7a44a8"      # the dim membrane between the pale crystal spines


def _g(amount: float) -> str:
    return shade(VIOLET, amount, 0.16)


def _f(amount: float, base: str = FINC) -> str:
    return shade(base, amount, 0.15)


# One 7-tone ramp for the whole body, darkest to lightest; the light tones lean pink.
SKIN = [_g(-0.7), _g(-0.47), _g(-0.25), _g(0.0), mix(_g(0.28), "#f0a8ec", 0.18), _g(0.5), _g(0.72)]
BELLY = [shade(LILAC, -0.15, 0.1), shade(LILAC, -0.07, 0.1), LILAC, shade(LILAC, 0.6, 0.1)]
OUTLINE = _g(-0.76)
OUTLINE_LIT = _g(-0.66)
FLECK = "#d9ccef"      # pale end-stone flecks on the dark back

BODY = {  # letter: (colour, zone)
    # back: forehead light, rim light (fading toward the tail), indigo back, flecks
    "K": (SKIN[6], "back"), "k": (SKIN[5], "back"), "y": (SKIN[4], "back"),
    "b": (SKIN[2], "back"), "B": (SKIN[1], "back"), "x": (FLECK, "back"),
    # flanks: sheen, flank, lower flank; the lateral band of blotches
    "G": (SKIN[4], "flank"), "g": (SKIN[3], "flank"), "l": (SKIN[4], "flank"),
    "d": (SKIN[1], "band"), "D": (SKIN[0], "band"), "q": ("#c04ee6", "band"),   # ender-magenta flecks
    # head: crown, cheek plate, gill cover edge, mouth, pale lower jaw
    "H": (SKIN[4], "head"), "h": (SKIN[3], "head"), "c": (SKIN[3], "head"),
    "n": (SKIN[1], "line"), "m": (OUTLINE, "line"), "j": (BELLY[2], "head"),
    # belly (countershaded)
    "v": (BELLY[0], "belly"), "u": (BELLY[1], "belly"), "w": (BELLY[2], "belly"), "W": (BELLY[3], "belly"),
    # eye: 2x2 pupil with a white catchlight, an ender-magenta iris behind it
    "E": ("#170a28", "eye"), "e": ("#ffffff", "eye"), "i": ("#e467ff", "eye"),
}
# Fins: letter -> (membrane base colour, zone). Rays are the capital letters / r.
FINS = {"f": (FINC, "fin"), "F": (SPINY, "fin"), "t": (FINC, "tail"), "a": (LOWFIN, "fin")}
RAYS = {"r": (FINC, "fin"), "T": (FINC, "tail"), "A": (LOWFIN, "fin")}
# end-rod spine tips, chorus-pink tail tips, and the pale crystal spines of the spiny dorsal
TIPS = {"s": ("#ffd2f6", "fin"), "z": ("#f7b6ef", "tail"), "R": ("#d6aef2", "fin")}
MEMBRANE_ALPHA, EDGE_ALPHA, BASE_ALPHA, RAY_ALPHA, TIP_ALPHA = 212, 204, 224, 234, 236
SCALED = {"G": "g", "l": "g", "g": "b", "b": "B", "w": "u", "d": "D"}   # letter -> its scale-mark letter
PECT_TINT, PECT_EDGE = _f(0.4, LOWFIN), _f(0.05)
FIN_OUTLINE_ALPHA = 236

# ---- effect colours ----------------------------------------------------------------------
IRIDESCENT = ["#b77cff", "#ff84da", "#7cefff"]     # purple -> pink -> cyan
GLINT = "#fff0ff"
SPARK = "#fbf2ff"
STARS = [  # (x, y, phase offset, colour) in frame coordinates, around the fish
    (5, 3, 0.0, "#f4e4ff"),
    (27, 10, 1 / 3, "#d4fbff"),
    (8, 24, 2 / 3, "#ffd4f4"),
]

# ---- the art (x 0..31 as drawn; SHIFT moves it to centre) -----------------------------------
# Static layer: head, body, dorsal/anal/pelvic fins. The tail and pectoral fin are layers
# of their own so they can sway.
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    ".............s..................",  # 5
    ".............R..s...............",  # 6
    "....KKkk....RFFR................",  # 7
    "...KHHHhkk..RFFRF...............",  # 8
    "..KHeEhhnbkkRFRFFs..............",  # 9
    ".jmcEEicnGxbkFRFFR..............",  # 10
    "..jmccccnGGgbkFFRF..............",  # 11
    "..vjmmccnDdgxbkFR.fs............",  # 12
    "...vjjmnlDqddgbkfffrf...........",  # 13
    "....vvnWwlldDdgxkffrff..........",  # 14
    "......vWwwllDDdgbyffrf..........",  # 15
    ".......vvwwlllDqDxyff...........",  # 16
    ".........vvwwwlDddByf...........",  # 17
    "...........vvvvwldqdyy..........",  # 18
    ".............aavvvDDd...........",  # 19
    ".............aAaaavvv...........",  # 20
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
    "...ttttzz.",  # 18
    "..ttTTTtt.",  # 19
    "..tttttt..",  # 20
    "ttTTt.....",  # 21
    ".tttT.....",  # 22
    "..Ttttt...",  # 23
    "..tTtt....",  # 24
    "...zz.....",  # 25
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
                colour = mix(colour, tint, 0.62 if pect[(x, y)] == "p" else 0.7)
                zone = "pect"
            px[X, Y] = rgba(colour)
            zones[(X, Y)] = zone
            continue
        near = [layer.get((x + dx, y + dy)) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        if ch in TIPS:
            colour, zone = TIPS[ch]
            alpha = TIP_ALPHA
        elif ch in RAYS:
            base, zone = RAYS[ch]
            colour, alpha = _f(-0.22, base), RAY_ALPHA
        else:
            base, zone = FINS[ch]
            if any(n is None for n in near):
                colour, alpha = _f(0.26, base), EDGE_ALPHA
            elif any(n in BODY for n in near):
                colour, alpha = _f(-0.12, base), BASE_ALPHA
            else:
                colour, alpha = _f(0.08, base), MEMBRANE_ALPHA
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
            # selective outline: a touch lighter on the side facing the top-left light
            facing_light = ((x, y + 1) in filled or (x + 1, y) in filled) and \
                (x, y - 1) not in filled and (x - 1, y) not in filled
            o = lit if facing_light else dark
            px[x, y] = o if any(p in solid for p in near) else (o[0], o[1], o[2], FIN_OUTLINE_ALPHA)
    return img, zones


def _along(x: int, y: int) -> float:
    """Distance along the body axis from the snout, in frame pixels."""
    return (x + 0.5 - 2.0) * AXIS[0] + (y + 0.5 - 8.5) * AXIS[1]


def _sparkle_spot(x: int, y: int) -> bool:
    """A denser diamond lattice: where the scales catch the light as the sheen passes."""
    return y % 2 == 1 and (x + y // 2) % 2 == 0


def _irid(phase: float) -> str:
    """The oil-slick cycle purple -> pink -> cyan -> purple, periodic in phase."""
    p = (phase % 1.0) * len(IRIDESCENT)
    i = int(p)
    return mix(IRIDESCENT[i], IRIDESCENT[(i + 1) % len(IRIDESCENT)], p - i)


def _screen(pixel, colour, k: float):
    """Screen-blend colour over pixel by k: brightens and tints without going muddy."""
    c = rgba(colour)
    return tuple(round(pixel[i] + (255 - pixel[i]) * c[i] / 255 * k) for i in range(3)) + (pixel[3],)


SCALES = ("flank", "band", "belly", "back")
DRIFT = {"flank": 0.1, "back": 0.1, "band": 0.07, "belly": 0.07}


def _frame(t: float):
    layer, pect = _compose(t)
    img, zones = _paint(layer, pect)
    px = img.load()
    step = round(t * FRAMES)
    # 1) always: a gentle iridescence drifting through purple, pink and cyan across the
    #    scales (one full colour cycle per loop, so it joins seamlessly)
    for (x, y), zone in zones.items():
        if zone not in SCALES:
            continue
        mark = _scale_mark(x, y)
        k = DRIFT[zone] * (1.6 if mark else 1.0)
        px[x, y] = rgba(mix(px[x, y], _irid(_along(x, y) / 16.0 - t), k))
    # 2) frames 0-10: a rainbow sheen sweeps snout -> tail, scales sparkling in it, a glint
    #    riding the back; frames 11-15 rest on the drift alone
    if step <= 10:
        centre = -2.0 + 32.0 * step / 10.0
        for (x, y), zone in zones.items():
            d = _along(x, y) - centre
            k = max(0.0, 1.0 - abs(d) / 3.6)
            if k <= 0:
                continue
            if zone in ("back", "head"):
                edge = x > 0 and (x - 1, y) not in zones or (x, y - 1) not in zones
                px[x, y] = rgba(mix(px[x, y], GLINT, (0.62 if edge else 0.4) * k))
            if zone in SCALES:
                spark = _sparkle_spot(x, y)
                if spark and k > 0.55:
                    px[x, y] = rgba(mix(px[x, y], SPARK, 0.9 * min(1.0, k * 1.3)))
                else:
                    boost = 0.85 if zone == "band" else 0.62
                    px[x, y] = _screen(px[x, y], _irid(0.67 + d / 10.0), boost * min(1.0, k * 1.3))
            elif zone in ("fin", "tail", "pect"):
                px[x, y] = _screen(px[x, y], _irid(0.67 + d / 10.0), 0.4 * k)
    # 3) three soft stars twinkle in turn around the fish
    for sx, sy, off, colour in STARS:
        s = math.sin(2 * math.pi * (t - off))
        if s > 0:
            sparkle(img, sx, sy, s ** 1.4, colour=colour, reach=2)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
