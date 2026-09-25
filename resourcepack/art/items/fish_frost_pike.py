"""Frost Pike: a rare cold-water pike of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's pale glacier-blue scheme with its bright ice-white midline. Pike
anatomy: a long, low torpedo body with a straight back, the flat duck-bill snout with the
long dark gape running back under the eye, the eye set high on the head, a gill-cover arc,
a low pectoral, pelvics mid-belly and the pike's tell, the dorsal and anal fins pushed far
back beside the forked tail. Deep sapphire back under an icy rim light, glacier-blue flanks
carrying frost-white bean spots, a bright ice midline over a snow-white belly, and
translucent ice-blue fins with painted rays and frosted white rims.

Animation (RARE, 12 frames x 3 ticks): the tail wags with its lobes leading, the paired
fins beat and the dorsal/anal tips ripple, a soft glint slides along the back, then a cold
blue shimmer band rolls across the scales, while three snowflake twinkles bloom in turn in
the open water around the fish.
"""
from __future__ import annotations

import math

from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_frost_pike"
NAME = "Frost Pike"
KIND = "item"
MODEL_KEY = "fish/frost_pike"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palette: letter -> (colour, alpha). Cold ramps: shadows lean violet, lights cyan ------
COLOURS = {
    # back, darkest -> rim light
    "1": ("#1d2f66", 255), "2": ("#24428a", 255), "3": ("#2f5da3", 255), "4": ("#5b98cf", 255),
    "5": ("#6ba8da", 255), "6": ("#9ccbec", 255), "7": ("#cdeaf8", 255),
    # frost-white bean spots (lit left half, cooler right half) and the ice midline
    "S": ("#f4fdff", 255), "s": ("#cbeefa", 255), "I": ("#d4f6fa", 255), "i": ("#a9e2f0", 255),
    # countershaded belly, shadow -> light
    "b": ("#8fb6d8", 255), "c": ("#a9cbe6", 255), "d": ("#dcf0fa", 255), "e": ("#ffffff", 255),
    # head: gape, gill-cover line, eye (pupil, catchlight, glacier-cyan iris)
    "m": ("#152056", 255), "g": ("#223f82", 255), "E": ("#0b1433", 255), "W": ("#ffffff", 255),
    "n": ("#5fd0ea", 255),
    # glacier-blue flank
    "G": ("#72b1de", 255), "h": ("#a4d6ef", 255),
    # unpaired fins: dark root, membrane, pale ray, frosted rim
    "o": ("#3567a8", 224), "f": ("#6aa8da", 210), "r": ("#aad6f2", 228), "x": ("#e6f8fe", 226),
    # tail: membrane from the root out to the lobes, and the dark rays
    "t": ("#4f89c4", 218), "u": ("#6aa8da", 212), "v": ("#8fc7ec", 210), "T": ("#2f5c9e", 230),
}
BODY = set("1234567SsIibcdemgEWnGh")
FIN = set("ofrxtuvT")
TAILS = set("tuvTx")
SHEEN = set("1234567SsIibcdeGh")          # pixels the glint and shimmer may light
OUTLINE = "#172456"                      # back and head
OUTLINE_BELLY = "#2a4b86"                # a lighter cold blue under the belly
FIN_OUTLINE = ("#2f4f86", 228)
PECT = {"P": ("#34629f", 0.72), "q": ("#5f98cf", 0.66), "Q": ("#9ccbec", 0.62)}   # tint, blend
GLINT = "#c8f4ff"
SHIMMER = "#b7ecff"
SPARK = "#f4feff"
SPARK_TINT = "#9fdcff"

# ---- the art (neutral pose). Outline is added around the silhouette automatically -------
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    "................................",  # 6
    "................................",  # 7
    "..67777665.........x............",  # 8
    "..cmm445WE555.....rrx...........",  # 9
    "...cdmm4EEn2255...frfx..........",  # 10
    ".....cdmn65g32255offrfx.........",  # 11
    ".......cddh5g332254oofrx......x.",  # 12
    "........ccdhg44Ss2244oo.....tx..",  # 13
    "..........cghGG44332244...tTt...",  # 14
    "...........cdhSsG44Ss224ttTt....",  # 15
    "............cdhhGSs44332tTt.....",  # 16
    ".............cddhhhGGSs3tt......",  # 17
    "..............ccdddhhhhhtt......",  # 18
    "................cccdddddtTt.....",  # 19
    "...................cccccttTt....",  # 20
    "...................oooo..ttT....",  # 21
    "....................frf...tTt...",  # 22
    ".....................fx....tTx..",  # 23
    "............................x...",  # 24
    "................................",  # 25
    "................................",  # 26
    "................................",  # 27
    "................................",  # 28
    "................................",  # 29
    "................................",  # 30
    "................................",  # 31
]
# Paired fins, two poses each, laid over the body (tinted) or into open water.
PECTORAL = [
    {(10, 14): "P", (11, 15): "q", (11, 16): "q", (12, 17): "Q"},
    {(10, 14): "P", (11, 14): "q", (11, 15): "q", (12, 16): "Q", (12, 17): "q"},
]
PELVIC = [
    {(16, 20): "P", (17, 20): "q", (17, 21): "Q", (18, 21): "q"},
    {(16, 20): "P", (17, 20): "q", (16, 21): "q", (17, 21): "Q"},
]
# Dorsal/anal tip pixels that ripple on and off (drawn only in the "up" half of the ripple).
RIPPLE = {(19, 7): "x", (22, 24): "x"}

AXIS = (math.cos(math.radians(24)), math.sin(math.radians(24)))
TAIL_ROOT = (23.5, 17.0)
SNOUT = (1.5, 8.5)


def _along(x: float, y: float) -> float:
    """Distance along the body axis from the snout (frame pixels)."""
    return (x + 0.5 - SNOUT[0]) * AXIS[0] + (y + 0.5 - SNOUT[1]) * AXIS[1]


def _grid() -> dict:
    out = {}
    for y, row in enumerate(ART):
        for x, ch in enumerate(row):
            if ch != ".":
                out[(x, y)] = ch
    return out


BASE = _grid()


def _tail(wag: float) -> dict:
    """The tail flexed: pixels 2.2-4 px out from the root move `near` rows, the lobes
    beyond move `far` rows, so the tips lead the sway."""
    near, far = round(0.7 * wag), round(1.4 * wag)
    out = {}
    for (x, y), ch in BASE.items():
        if x < 24 or ch not in TAILS:
            continue
        d = math.hypot(x + 0.5 - TAIL_ROOT[0], y + 0.5 - TAIL_ROOT[1])
        dy = far if d >= 4.0 else near if d >= 2.2 else 0
        if ch == "t":
            ch = "t" if d < 2.6 else "u" if d < 4.6 else "v"
        out.setdefault((x, y + dy), ch)
    if far:
        step = 1 if far > 0 else -1
        for (x, y), ch in list(out.items()):       # close holes the flex opened in a column
            if (x, y + step) not in out and (x, y + 2 * step) in out:
                out[(x, y + step)] = "u"
    return out


def _compose(t: float):
    wag = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.2))
    pose = 0 if math.sin(2 * math.pi * (2 * t + 0.1)) >= 0 else 1
    layer = {k: v for k, v in BASE.items() if not (k[0] >= 24 and v in TAILS)}
    for k, v in _tail(wag).items():
        layer.setdefault(k, v)
    if ripple > 0.2:
        for k, v in RIPPLE.items():
            layer.setdefault(k, v)
    over = {}
    for fin in (PELVIC[1 - pose], PECTORAL[pose]):
        for k, v in fin.items():
            if k in layer and layer[k] in BODY:
                over[k] = v
            else:
                layer.setdefault(k, v)
    return layer, over


def _paint(layer: dict, over: dict):
    img = canvas(SIZE)
    px = img.load()
    for (x, y), ch in layer.items():
        if ch in PECT:
            c = rgba(PECT[ch][0])
            px[x, y] = (c[0], c[1], c[2], 214)
            continue
        colour, alpha = COLOURS[ch]
        if (x, y) in over:
            tint, k = PECT[over[(x, y)]]
            colour = mix(colour, tint, k)
        c = rgba(colour)
        px[x, y] = (c[0], c[1], c[2], alpha)
    # 1 px outline around the whole silhouette: navy along the back and head, a lighter
    # cold blue under the belly, softer and translucent where it only borders fins.
    body = {k for k, v in layer.items() if v in BODY}
    spans = {}
    for (x, y) in body:
        lo, hi = spans.get(x, (y, y))
        spans[x] = (min(lo, y), max(hi, y))
    dark, belly = rgba(OUTLINE), rgba(OUTLINE_BELLY)
    fo = rgba(FIN_OUTLINE[0])
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in layer:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + dx, y + dy) in layer]
            if not near:
                continue
            solid = [p for p in near if p in body]
            if solid:
                lo, hi = spans[solid[0][0]]
                px[x, y] = belly if y > (lo + hi) / 2 + 0.5 and x > 3 else dark
            else:
                px[x, y] = (fo[0], fo[1], fo[2], FIN_OUTLINE[1])
    return img


def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _glint(img, layer, t):
    """First half-loop: a soft glint slides along the back (top three pixels of a column)."""
    if t >= 0.5:
        return
    centre = -1.0 + 26.0 * (t / 0.45)
    px = img.load()
    tops = {}
    for (x, y), ch in layer.items():
        if ch in BODY:
            tops[x] = min(tops.get(x, 99), y)
    for (x, y), ch in layer.items():
        if ch not in SHEEN or x not in tops:
            continue
        depth = y - tops[x]
        if depth > 2:
            continue
        k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.0) * 0.6 * (1.0 - depth / 3.2)
        if k > 0:
            _blend(px, x, y, GLINT, k)


def _shimmer(img, layer, t):
    """Second half-loop: a cold blue shimmer band rolls across the scales, head to tail;
    the frost spots and a sparse scale lattice flash as it passes."""
    if t < 0.5:
        return
    centre = 3.0 + 22.0 * ((t - 0.5) / 0.42)
    px = img.load()
    for (x, y), ch in layer.items():
        if ch not in SHEEN:
            continue
        k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.2)
        if k <= 0:
            continue
        hot = ch in "Ss" or (y % 2 == 1 and (x + y // 2) % 3 == 0)
        _blend(px, x, y, SPARK if hot else SHIMMER, (0.95 if hot else 0.55) * min(1.0, k * 1.3))


# Snowflake twinkles in open water around the fish: (x, y, phase offset, reach).
FLAKES = [(27, 7, 0.0, 2), (5, 19, 1 / 3, 2), (12, 4, 2 / 3, 1)]


def _flakes(img, t):
    q = img.load()
    for x, y, off, reach in FLAKES:
        p = (t - off) % 1.0
        amount = math.sin(math.pi * p / 0.5) if p < 0.5 else 0.0     # blooms for half a loop
        if amount <= 0.05:
            continue
        sparkle(img, x, y, amount, colour=SPARK if amount > 0.6 else SPARK_TINT, reach=reach)
        if amount > 0.8 and reach >= 2:                # a snowflake's short diagonals at the peak
            c = rgba(SPARK_TINT)
            for dx, dy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
                if q[x + dx, y + dy][3] == 0:
                    q[x + dx, y + dy] = (c[0], c[1], c[2], 150)


def _frame(t: float) -> Image.Image:
    layer, over = _compose(t)
    img = _paint(layer, over)
    _glint(img, layer, t)
    _shimmer(img, layer, t)
    _flakes(img, t)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
