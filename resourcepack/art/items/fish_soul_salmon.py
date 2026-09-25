"""Soul Salmon: an epic fish of the JoshyMC fishing collection, caught in the soul sand valley.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's soul-fire cyan body and golden accents. Real salmon anatomy: a
deep, streamlined body with a pointed snout, a long mouth running back under the eye and a
hooked spawning kype, a gill cover, one mid-body dorsal fin, the little adipose fin before
the tail, low pectoral, pelvic and anal fins and a broad, shallowly forked tail. The
colours read as soul fire: a deep blue-teal back dusted with dark salmon spots, a glowing
soul-fire lateral stripe set with golden embers, a cyan flank, a pale aqua belly,
translucent teal fins with painted rays (the tail and dorsal spotted too) and a golden iris.

Animation (EPIC, 16 frames x 2 ticks): the tail and fins sway about a pixel on offset
sine phases, the lateral stripe flows with a soft iridescent shimmer and its embers glow
one after another, a glint slides along the back and then a broad iridescent sheen (cyan, purple, pink) rolls across the scales
head to tail, while three soul sparkles twinkle around the fish in turn.
"""
from __future__ import annotations

import colorsys
import math

import numpy as np

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_soul_salmon"
NAME = "Soul Salmon"
KIND = "item"
MODEL_KEY = "fish/soul_salmon"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 16
FRAMETIME = 2

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean indigo, lights lean mint --
OUTLINE = "#0b1a3c"
FIN_OUTLINE = ("#1b4470", 230)
BACK = ["#0f2a5c", "#143d73", "#1a5584", "#236f96", "#3591aa", "#5cc0cc"]        # soul-teal back
FLANK = ["#1789a8", "#1ea6c4", "#2cc3d8", "#52dbe8", "#8aeef2", "#9ffcff"]       # soul-fire cyan
BELLY = ["#6fa6c0", "#96c9d8", "#bfe4ea", "#e2f7f6", "#f6fffd"]                  # pale aqua belly
GOLD = ["#6e4217", "#b3781f", "#ecb33a", "#ffd86a", "#fff1b8"]                   # embers / iris
SPOT = "#10214d"
FIN = ["#1d4f78", "#2a6e92", "#3b90ac", "#56b0c4", "#7fcfd8", "#b4eeee"]
GLINT = "#effffd"
SPARK = ["#e8fdff", "#b9f4ff", "#ffd9f4"]
IRIS_HUES = (0.52, 0.76, 0.90)           # cyan -> purple -> pink (HSV hues)

BODY = {  # letter: (colour, zone)
    # back: rim light, crown, deep back, back, spots
    "R": (BACK[5], "back"), "r": (BACK[4], "back"), "H": (mix(BACK[4], BACK[5], 0.4), "back"),
    "D": (BACK[2], "back"), "B": (BACK[3], "back"), "x": (SPOT, "back"),
    # soul-fire lateral stripe with golden embers
    "S": (FLANK[5], "stripe"), "y": (GOLD[3], "ember"),
    # flank: upper flank, flank, scale marks
    "F": (FLANK[3], "flank"), "f": (FLANK[2], "flank"), "g": (FLANK[1], "flank"),
    # belly (countershaded)
    "u": (BELLY[2], "belly"), "w": (BELLY[3], "belly"), "W": (BELLY[4], "belly"), "v": (BELLY[1], "belly"),
    # head: cheek, gill shadow, gill-cover line, pale jaw, kype, mouth
    "h": (FLANK[4], "head"), "c": (FLANK[1], "head"), "n": (BACK[2], "line"), "j": (BELLY[3], "head"),
    "k": (BELLY[2], "head"), "m": (OUTLINE, "line"),
    # eye: 2x2 pupil with a catchlight and a golden iris behind it
    "E": ("#07102a", "eye"), "e": ("#ffffff", "eye"), "i": (GOLD[2], "eye"),
}

# ---- the body (x 0..31, y 0..31) ---------------------------------------------------------
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    ".....RRRRR......................",  # 6
    "...RRHHHHBRRr...................",  # 7
    "..RHHeEHnBDxDrr.................",  # 8
    "..mmhEEihnBBDxDr................",  # 9
    "..kkmmhhhnSySBBDrr..............",  # 10
    "....vjjhcnFFFSSBBDr.............",  # 11
    ".....vvjnufffFFSBxDr............",  # 12
    ".......vwwuufgfFSyBDr...........",  # 13
    "........vvwwuugfFFSxDr..........",  # 14
    "..........vvwwuufgFSBDr.........",  # 15
    "............vvwwuuFSyBB.........",  # 16
    "..............vvwwuFSxB.........",  # 17
    "................vvvwSBB.........",  # 18
    "...................vuBD.........",  # 19
    "................................",  # 20
]
PIXELS = {(x, y): ch for y, row in enumerate(ART) for x, ch in enumerate(row) if ch != "."}
MASK = np.zeros((SIZE, SIZE), bool)
for (_x, _y) in PIXELS:
    MASK[_y, _x] = True
AXIS = (math.cos(math.radians(30)), math.sin(math.radians(30)))
SNOUT = (2.0, 8.5)
LENGTH = 23.0                            # snout to tail base, along the axis


def _along(x: float, y: float) -> float:
    """Distance along the body axis from the snout, in pixels."""
    return (x + 0.5 - SNOUT[0]) * AXIS[0] + (y + 0.5 - SNOUT[1]) * AXIS[1]


# ---- fins: hand-placed pixels ------------------------------------------------------------
# Letters: "p" membrane, "P" ray, "L" lit edge, "q" dark salmon spot. Medial fins (tail,
# dorsal, adipose, anal) sit behind the body in open water; the near pelvic and pectoral
# fins also tint the body pixels they overlap.
FIN_COLOURS = {  # letter: (colour, alpha)
    "p": (FIN[4], 200), "P": (FIN[2], 224), "L": (FIN[5], 212), "q": (FIN[0], 226),
    "o": (FIN[3], 214),        # membrane where it meets the body (a touch darker)
}


def _rows(at, rows) -> dict:
    x0, y0 = at
    return {(x0 + i, y0 + j): ch for j, row in enumerate(rows) for i, ch in enumerate(row) if ch != "."}


# Dorsal fin (two poses, the tip trailing a pixel back on the second).
DORSAL = {
    "rest": _rows((12, 4), ["..L..",
                            ".LPp.",
                            "LpPqp",
                            ".opPpp",
                            "...oop",
                            "....o."]),
    "back": _rows((12, 4), ["...L.",
                            ".LLPp",
                            "LpPqp",
                            ".opPpp",
                            "...oop",
                            "....o."]),
}
ADIPOSE = _rows((20, 11), ["L.",
                           "op",
                           ".o"])
# Anal fin (two poses) and pelvic fin (two poses).
ANAL = {
    "rest": _rows((17, 19), ["Pp..",
                             ".Ppp",
                             "..p."]),
    "back": _rows((17, 19), ["Pp..",
                             ".Ppp",
                             "...p"]),
}
PELVIC = {
    "rest": _rows((12, 17), ["Pp.",
                             ".Pp",
                             "..p"]),
    "swept": _rows((12, 17), ["Pp..",
                              ".PPp",
                              "...."]),
}
# Pectoral fin poses: (x, y, letter); on body pixels the fin tints the flank.
PECT = {
    "spread": _rows((9, 13), ["p..",
                              ".p.",
                              "ppP",
                              ".PP"]),
    "folded": _rows((9, 13), ["pp.",
                              ".pP",
                              "..PP",
                              "...."]),
}
# Tail (neutral pose): a broad, shallowly forked salmon tail with rays fanning from the
# peduncle and a few dark spots.
TAIL_AT = (23, 15)
TAIL = [
    "LLL....",   # 15
    "opPLL..",   # 16
    "oPPPPpL",   # 17
    "opqppp.",   # 18
    "oPpp...",   # 19
    "ppP....",   # 20
    ".pPp...",   # 21
    ".pqPp..",   # 22
    "..pPp..",   # 23
    "...pp..",   # 24
]
TAIL_BASE = (22.5, 17.8)


def _tail(near: int, far: int) -> dict:
    """The tail flexed: pixels 2-4 px out from the root move `near` rows, the lobes further
    out move `far` rows (positive: down), so the tips lead the sway."""
    base = {}
    for j, row in enumerate(TAIL):
        for i, ch in enumerate(row):
            if ch != ".":
                base[(TAIL_AT[0] + i, TAIL_AT[1] + j)] = "p" if ch == "t" else ch
    if near == 0 and far == 0:
        return base
    out = {}
    for (x, y), ch in base.items():
        along = (x + 0.5 - TAIL_BASE[0]) * AXIS[0] + (y + 0.5 - TAIL_BASE[1]) * AXIS[1]
        dy = far if along >= 4.0 else near if along >= 2.0 else 0
        out.setdefault((x, y + dy), ch)
    step = 1 if far > 0 else -1
    for (x, y) in list(base):          # close any hole the flex opened inside a column
        if (x, y) not in out and (x, y - step) in out and (x, y + step) in out:
            out[(x, y)] = "p"
    return out


def _paint_fins(img, zones, fins: dict, front: bool = False):
    px = img.load()
    for (x, y), ch in fins.items():
        if not (0 <= x < SIZE and 0 <= y < SIZE):
            continue
        colour, alpha = FIN_COLOURS[ch]
        if MASK[y, x]:
            if front:
                px[x, y] = rgba(mix(px[x, y], colour, 0.5 if ch in "pL" else 0.62))
                zones[(x, y)] = "fin"
            continue
        if px[x, y][3] and not front:
            continue
        c = rgba(colour)
        px[x, y] = (c[0], c[1], c[2], alpha)
        zones[(x, y)] = "fin"


def _outline(img):
    """1 px outline: deep indigo beside the body, a softer blue where it only borders fins."""
    src = img.load()
    out = img.copy()
    dst = out.load()
    fr, fg, fb, _ = rgba(FIN_OUTLINE[0])
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                    if 0 <= x + dx < SIZE and 0 <= y + dy < SIZE and src[x + dx, y + dy][3]]
            if not near:
                continue
            if any(MASK[ny, nx] for nx, ny in near):
                dst[x, y] = rgba(OUTLINE)
            else:
                dst[x, y] = (fr, fg, fb, FIN_OUTLINE[1])
    return out


# ---- effects ----------------------------------------------------------------------------

def _iris(h: float, s: float, v: float = 1.0) -> tuple[int, int, int]:
    r, g, b = colorsys.hsv_to_rgb(h % 1.0, s, v)
    return round(r * 255), round(g * 255), round(b * 255)


def _iris_hue(p: float) -> float:
    """p in 0..1 around the cyan -> purple -> pink -> cyan loop."""
    p %= 1.0
    k = p * 3
    i = int(k)
    f = k - i
    a, b = IRIS_HUES[i % 3], IRIS_HUES[(i + 1) % 3]
    d = ((b - a + 0.5) % 1.0) - 0.5
    return a + d * f


def _blend(px, x, y, rgb, k):
    r, g, b, a = px[x, y]
    k = max(0.0, min(1.0, k))
    px[x, y] = (round(r + (rgb[0] - r) * k), round(g + (rgb[1] - g) * k), round(b + (rgb[2] - b) * k), a)


def _stripe_flow(img, zones, t):
    """The soul-fire stripe shimmers with iridescent colour flowing toward the tail."""
    px = img.load()
    for (x, y), zone in zones.items():
        if zone == "stripe":
            _blend(px, x, y, _iris(_iris_hue(_along(x, y) / 12.0 - t), 0.3), 0.3)


def _embers(img, zones, t):
    """The golden soul embers along the stripe glow softly, one after another."""
    px = img.load()
    for (x, y), zone in zones.items():
        if zone == "ember":
            k = wave(t, -_along(x, y) / 24.0)
            px[x, y] = rgba(mix(GOLD[2], GOLD[4], k))


def _glint(img, zones, t, start=0.0, run=0.32, strength=0.6, width=3.0):
    """A soft glint sliding along the back from snout to tail during [start, start + run)."""
    p = (t - start) % 1.0
    if p >= run:
        return
    centre = -1.5 + (LENGTH + 3.0) * (p / run)
    px = img.load()
    c = rgba(GLINT)
    for (x, y), zone in zones.items():
        if zone != "back":
            continue
        k = max(0.0, 1.0 - abs(_along(x, y) - centre) / width) * strength
        _blend(px, x, y, c, k)


def _sheen(img, zones, t, start=0.38, run=0.56, width=5.5, strength=0.58):
    """A broad iridescent band rolling across the scales head to tail: cyan leading, a
    purple core and a pink trailing edge; individual scales flash as it passes."""
    p = (t - start) % 1.0
    if p >= run:
        return
    centre = -width + (LENGTH + 2 * width) * (p / run)
    px = img.load()
    for (x, y), zone in zones.items():
        if zone in ("eye", "line", "ember"):
            continue
        d = (_along(x, y) - centre) / width          # +1 head-most edge (leading) .. -1 trailing
        if abs(d) >= 1:
            continue
        fall = 1.0 - d * d
        rgb = _iris(_iris_hue(0.5 - 0.5 * d), 0.5 if zone not in ("belly", "fin") else 0.34)
        k = strength * fall * (0.75 if zone in ("back", "fin") else 1.0)
        if zone in ("flank", "stripe", "belly") and (x + 2 * y) % 5 == 0:
            k *= 1.55                                   # scales catching the light
        _blend(px, x, y, rgb, k)


SPARKLES = [  # (x, y, phase offset, colour index)
    (8, 3, 0.0, 0),
    (27, 11, 0.34, 1),
    (6, 22, 0.67, 2),
]


def _sparkles(img, t):
    for x, y, off, ci in SPARKLES:
        amount = max(0.0, wave(t, off) * 1.7 - 0.7)       # each one twinkles once per loop
        sparkle(img, x, y, amount, colour=SPARK[ci], reach=2)


def _frame(t: float):
    """One 32x32 frame at loop phase t (0..1)."""
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for (x, y), ch in PIXELS.items():
        colour, zone = BODY[ch]
        px[x, y] = rgba(colour)
        zones[(x, y)] = zone
    wag = math.sin(2 * math.pi * t)
    near, far = round(0.7 * wag), round(1.3 * wag)
    _paint_fins(img, zones, _tail(near, far))
    _paint_fins(img, zones, DORSAL["back" if ripple > 0.35 else "rest"])
    _paint_fins(img, zones, ADIPOSE)
    _paint_fins(img, zones, ANAL["back" if ripple > 0.35 else "rest"])
    _paint_fins(img, zones, PELVIC["swept" if flap > 0.3 else "rest"], front=True)
    _paint_fins(img, zones, PECT["folded" if math.sin(2 * math.pi * 2 * t + 1.0) > 0.4 else "spread"], front=True)
    _stripe_flow(img, zones, t)
    _embers(img, zones, t)
    _glint(img, zones, t)
    _sheen(img, zones, t)
    img = _outline(img)
    _sparkles(img, t)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
