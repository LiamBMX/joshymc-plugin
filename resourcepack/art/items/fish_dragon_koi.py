"""Dragon Koi: a legendary fish of the JoshyMC fishing collection, found in the End.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's scarlet / flame-orange scheme. A long, deep-backed koi with a
dragon's trimmings: a rounded head with gold lips and long whisker barbels trailing from
the mouth, a radiant golden eye, a gill-cover arc, one row of big gold doitsu "dragon
scales" along the flank, a crimson back over scarlet flanks and an ember-to-gold
countershaded belly, a serrated crest of a dorsal fin with gold spine tips, broad
butterfly pectorals and a flowing, lightly notched flame tail (fins translucent with
painted rays).

Animation (LEGENDARY, 20 frames x 2 ticks): the tail flows, the fins and barbels sway
about a pixel and a ripple bends each crest spine back in turn; a soft glint slides along
the back, then an iridescent End sheen rolls over the body and fins head to tail shifting
cyan -> violet -> pink and flashing the dragon scales. All loop long a golden rim glow
breathes just outside the outline, the eye glows and three sparkles orbit the fish,
passing behind it (End pink) and in front of it (pale gold).
"""
from __future__ import annotations

import colorsys
import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_dragon_koi"
NAME = "Dragon Koi"
KIND = "item"
MODEL_KEY = "fish/dragon_koi"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 20
FRAMETIME = 2

# ---- palettes (darkest -> lightest), from the old sprite's scarlet and flame orange -------
OUTLINE = "#3d0a1f"                     # deep wine, hue-shifted toward magenta
FIN_OUTLINE = ("#5e1328", 232)
RED = ["#5c0d26", "#86142a", "#b01e27", "#d8342a", "#ee5530", "#fb7f45", "#ffae6a"]
EMBER = ["#c9412a", "#e7662f", "#f68c3c", "#fbb152", "#fdd27a", "#fff0b8"]
GOLD = ["#8a4a1c", "#c9832a", "#f0b83e", "#ffe07a", "#fff6c8"]
FIN = ["#8e1a2c", "#c22e2c", "#e45432", "#f5843f", "#fbb45e", "#ffdc96"]
EYE = ["#1a0610", "#ffffff"]
GLINT = "#fff4dc"
RIM = "#ffc93e"

# ---- geometry (pixels) ------------------------------------------------------------------
# The fish's own axis: u runs from the snout tip toward the tail (along AX), v toward the
# back (along UP). The art is hand-drawn; the axis steers the glint, the sheen band, the
# tail flex and the sparkle orbit.
THETA = math.radians(33.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 22.0                      # snout to tail base
SNOUT = (2.3, 8.6)             # snout tip in the frame
_py, _px = np.mgrid[0:SIZE, 0:SIZE]
UC = (_px + 0.5 - SNOUT[0]) * AX[0] + (_py + 0.5 - SNOUT[1]) * AX[1]


# ---- body (hand-painted) ----------------------------------------------------------------
# Diagonal countershading bands that follow the contour: crimson back (C c d) under a lit
# rim (R, r toward the tail), a bright scarlet lateral band (R r), an ember-gold belly
# (g h j) with a reflected-light edge (k). Head: eye (W catchlight, K pupil, i/I radiant
# iris), gill arc (G), gold lips (l, L) and the mouth corner (m). 3/2/s: dragon scales.
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    "................................",  # 6
    "....RRR.........................",  # 7
    "...RrrdRR.......................",  # 8
    "..RrWKidGR......................",  # 9
    "..lRKKIrGccc....................",  # 10
    "...mhRrrGdccc...................",  # 11
    "...LjhRGrrddcc..................",  # 12
    "....kjhgR32dccR.................",  # 13
    ".....kjhgRsrddcR................",  # 14
    "......kjhgRR32dcR...............",  # 15
    "........kjhgRsrdcR..............",  # 16
    ".........kkjhgR32dRR............",  # 17
    "............kkhgsrdcR...........",  # 18
    "...............kkgRdcR..........",  # 19
    "..................kkdR..........",  # 20
]

LETTERS = {
    "R": (RED[5], "flank"), "r": (RED[4], "flank"), "d": (RED[3], "back"), "c": (RED[2], "back"),
    "C": (RED[1], "back"), "k": (EMBER[1], "belly"), "g": (EMBER[2], "belly"), "h": (EMBER[3], "belly"),
    "j": (EMBER[4], "belly"), "3": (GOLD[3], "scale"), "2": (GOLD[2], "scale"), "s": (RED[1], "flank"),
    "G": (RED[2], "head"), "l": (GOLD[3], "head"), "L": (GOLD[2], "head"), "m": (OUTLINE, "head"),
    "W": (EYE[1], "eye"), "K": (EYE[0], "eye"), "i": (GOLD[3], "iris"), "I": (GOLD[2], "iris"),
}

ART += ["." * SIZE] * (SIZE - len(ART))
MASK = np.array([[ch != "." for ch in row] for row in ART])


def _rim_letters(y: int, x: int, ch: str) -> str:
    """Back-contour pixels (open water above or to the right) catch the rim light."""
    if ch not in "RrdcC":
        return ch
    top = y == 0 or not MASK[y - 1, x]
    right = x == SIZE - 1 or not MASK[y, x + 1]
    if top or right:
        return "R" if UC[y, x] < 11.5 else "r"
    return ch


BODY_LETTERS = {(x, y): _rim_letters(y, x, ch) for y, row in enumerate(ART) for x, ch in enumerate(row)
                if ch != "."}


def _dist_field(mask: np.ndarray) -> np.ndarray:
    """4-neighbour step distance of every pixel from the body (0 inside)."""
    d = np.where(mask, 0, 99)
    for _ in range(10):
        n = d.copy()
        n[1:, :] = np.minimum(n[1:, :], d[:-1, :] + 1)
        n[:-1, :] = np.minimum(n[:-1, :], d[1:, :] + 1)
        n[:, 1:] = np.minimum(n[:, 1:], d[:, :-1] + 1)
        n[:, :-1] = np.minimum(n[:, :-1], d[:, 1:] + 1)
        d = n
    return d


DIST = _dist_field(MASK)

# ---- fins (hand-drawn) ------------------------------------------------------------------
# Dorsal crest: e gold spine tip, S gold leading spine, F membrane, f root membrane.
# Tail: t membrane, T ray, E pale-gold tip. Anal fin: a membrane, A ray.
FIN_ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "............e...................",  # 4
    "...........SF...................",  # 5
    ".........SSFF...................",  # 6
    ".......fSFFFF...e...............",  # 7
    ".........fFFFffSF...............",  # 8
    "..........ffFfSFF...............",  # 9
    "............ffFFF..e............",  # 10
    ".............ffFf.SF............",  # 11
    "..............fffSFF............",  # 12
    "...............ffFFf............",  # 13
    "................fff.............",  # 14
    ".................f..............",  # 15
    ".........................EE.....",  # 16
    ".....................tttttttE...",  # 17
    ".....................tTTtttttE..",  # 18
    "......................ttTTtttEE.",  # 19
    ".............aa.......ttttTtE...",  # 20
    "..............Aaaa.tttTttE......",  # 21
    "................aa..tttTtE......",  # 22
    ".....................tttTE......",  # 23
    ".....................tttTE......",  # 24
    "......................tttE......",  # 25
    ".......................EE.......",  # 26
    "................................",  # 27
]
FIN_ART += ["." * SIZE] * (SIZE - len(FIN_ART))
TAIL_KEYS = set("tTE")
TAIL_BASE = (21.0, 19.5)       # where the tail joins the peduncle

# Pectoral (butterfly fin, partly over the body) and pelvic poses: (x, y) lists.
PECT = {
    "spread": [(6, 14), (7, 14), (4, 15), (5, 15), (6, 15), (3, 16), (4, 16), (5, 16), (6, 16), (7, 16),
               (3, 17), (4, 17), (5, 17), (6, 17), (4, 18), (5, 18)],
    "folded": [(6, 14), (7, 14), (5, 15), (6, 15), (7, 15), (4, 16), (5, 16), (6, 16), (7, 16),
               (4, 17), (5, 17), (6, 17), (5, 18)],
}
PECT_RAYS = {(5, 16), (6, 15), (4, 17)}
PELVIC = {"rest": [(9, 18), (10, 18), (11, 18), (9, 19), (10, 19)],
          "swept": [(10, 18), (11, 18), (10, 19), (11, 19), (11, 20)]}


def _fin_layer() -> dict:
    return {(x, y): ch for y, row in enumerate(FIN_ART) for x, ch in enumerate(row) if ch != "."}


FIN_LAYER = _fin_layer()
CREST_TIPS = [(12, 4), (16, 7), (19, 10)]     # each bends back a pixel as the ripple passes


def _tail(near: int, far: int) -> dict:
    """The tail flexed: pixels 2-4 px out from the root move `near` rows, the lobes further
    out move `far` rows (positive: down), so the tips lead the sway."""
    base = {k: ch for k, ch in FIN_LAYER.items() if ch in TAIL_KEYS}
    if near == 0 and far == 0:
        return base
    out = {}
    for (x, y), ch in base.items():
        along = (x + 0.5 - TAIL_BASE[0]) * AX[0] + (y + 0.5 - TAIL_BASE[1]) * AX[1]
        dy = far if along >= 4.4 else near if along >= 2.4 else 0
        out.setdefault((x, y + dy), ch)
    step = 1 if far > 0 else -1
    for (x, y) in list(base):
        if (x, y) not in out and (x, y - step) in out and (x, y + step) in out:
            out[(x, y)] = "t"
    return out


def _fin_colour(ch: str, x: int, y: int, layer: dict) -> tuple[str, int]:
    """Colour and alpha of a fin pixel: membranes warm from deep red at the root to gold
    at the tip; rays a step darker; the edge facing the top-left light a step brighter."""
    d = int(DIST[y, x])
    if ch == "e":
        return GOLD[3], 236
    if ch == "S":
        return (GOLD[2] if d >= 2 else FIN[3]), 232
    if ch == "E":
        return FIN[5], 206
    if ch in "tT":
        idx = 1 if d <= 1 else 2 if d <= 3 else 3 if d <= 5 else 4
    elif ch in "aA":
        idx = 2 if d <= 1 else 3
    else:                                          # dorsal crest: crimson membrane
        idx = 0 if ch == "f" else 1 if d <= 2 else 2
    if ch in "TA":
        return FIN[max(0, idx - 1)], 230
    lit = ((x, y - 1) not in layer and not MASK[y - 1, x]) or ((x - 1, y) not in layer and not MASK[y, x - 1])
    if lit and ch not in "f":
        idx = min(5, idx + 1)
    return FIN[idx], 214


# ---- head details -----------------------------------------------------------------------

def _body(t: float):
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    glow = wave(t, 0.25)
    for (x, y), ch in BODY_LETTERS.items():
        colour, zone = LETTERS[ch]
        if ch == "i":
            colour = mix(GOLD[2], GOLD[4], 0.3 + 0.6 * glow)      # radiant iris
        elif ch == "I":
            colour = mix(GOLD[1], GOLD[3], 0.2 + 0.65 * glow)
        px[x, y] = rgba(colour)
        zones[(x, y)] = zone
    return img, zones


def _outline(img: Image.Image) -> Image.Image:
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


# ---- barbels: long dragon whiskers trailing from the mouth ---------------------------------

def _barbels(t: float):
    """(x, y, colour) pixels of the two whiskers; the long one's tip sways."""
    s = math.sin(2 * math.pi * (t - 0.3))
    tip = 1 if s > 0.35 else 0
    long_ = [(1, 11, GOLD[3]), (1, 12, GOLD[2]), (1, 13, GOLD[2]), (1 + tip, 14, GOLD[1]),
             (2 + tip, 15, GOLD[1])]
    short = [(2, 12, GOLD[2]), (2, 13, GOLD[1])]
    return long_ + short


# ---- effects ----------------------------------------------------------------------------

def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _glint(img, zones, t, start=0.0, end=0.34, width=3.0, strength=0.72):
    """A soft glint sliding along the back, snout to tail, during [start, end)."""
    if not start <= t < end:
        return
    centre = -1.0 + (SL + 3.0) * (t - start) / (end - start)
    px = img.load()
    for (x, y), z in zones.items():
        if z not in ("flank", "back", "head", "scale") or DIST_BACK[y, x] > 3:
            continue
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / width) * strength * (1.0 - DIST_BACK[y, x] / 4.5)
        if k > 0:
            _blend(px, x, y, GLINT, k)


def _back_steps(mask: np.ndarray) -> np.ndarray:
    """Steps in from the back contour (open water above or to the right)."""
    kb = np.full(mask.shape, 99)
    for y in range(SIZE):
        for x in range(SIZE):
            if not mask[y, x]:
                continue
            up = 0
            while y - up - 1 >= 0 and mask[y - up - 1, x]:
                up += 1
            right = 0
            while x + right + 1 < SIZE and mask[y, x + right + 1]:
                right += 1
            kb[y, x] = min(up, right)
    return kb


DIST_BACK = _back_steps(MASK)


def _iridescent(hue_phase: float) -> str:
    """End-crystal colours: cyan (0) -> violet (0.5) -> pink (1)."""
    h = (0.5 + 0.42 * hue_phase) % 1.0
    r, g, b = colorsys.hsv_to_rgb(h, 0.58, 1.0)
    return "#%02x%02x%02x" % (round(r * 255), round(g * 255), round(b * 255))


def _sheen(img, zones, t, start=0.4, end=0.92, width=3.4):
    """An iridescent sheen rolling head to tail over the body, colour shifting along the
    band, the dragon scales flashing inside it."""
    if not start <= t < end:
        return
    p = (t - start) / (end - start)
    centre = 3.0 + (SL + 1.0) * p
    px = img.load()
    for (x, y), z in zones.items():
        if z not in ("back", "flank", "scale", "belly", "fin", "pect"):
            continue
        off = UC[y, x] - centre
        k = max(0.0, 1.0 - abs(off) / width)
        if k <= 0:
            continue
        hue = min(1.0, max(0.0, 0.5 + off / (2 * width) + (p - 0.5) * 0.4))
        col = _iridescent(hue)
        if z == "scale":
            _blend(px, x, y, mix(col, "#ffffff", 0.6), min(1.0, 1.2 * k))
        elif z in ("fin", "pect"):
            _blend(px, x, y, mix(col, "#ffffff", 0.2), 0.4 * k)
        else:
            _blend(px, x, y, col, 0.58 * k * (0.75 if z == "belly" else 1.0))


def _rim_glow(img, t):
    """A breathing golden glow 1 px outside the outline."""
    src = img.load()
    out = img.copy()
    dst = out.load()
    breathe = wave(t)
    c = rgba(RIM)
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            near = sum(1 for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                       if 0 <= x + dx < SIZE and 0 <= y + dy < SIZE and src[x + dx, y + dy][3] >= 200)
            if not near:
                continue
            a = (45 + 115 * breathe) * (1.0 if near >= 2 else 0.75)
            dst[x, y] = (c[0], c[1], c[2], round(a))
    return out


ORBIT_CENTRE = (15.6, 15.6)
ORBIT_R = (15.0, 6.6)          # along / across the fish's axis
SPARK_FRONT, SPARK_BACK = "#fff3c4", "#ffb8ec"


def _orbiters(t: float):
    """Three sparkles evenly spaced on a tilted orbit around the fish. Each moves a third of
    the way round per loop and its size and colour depend only on where it is on the
    orbit, so the set loops seamlessly: pale gold in front of the fish, End-pink behind.
    Returns (x, y, amount, colour, in_front)."""
    out = []
    for i in range(3):
        turn = i / 3 + t / 3 + 0.06
        a = 2 * math.pi * turn
        cu, cv = ORBIT_R[0] * math.cos(a), ORBIT_R[1] * math.sin(a)
        x = ORBIT_CENTRE[0] + cu * AX[0] + cv * UP[0]
        y = ORBIT_CENTRE[1] + cu * AX[1] + cv * UP[1]
        amount = 0.4 + 0.6 * wave(4 * turn)
        colour = mix(SPARK_FRONT, SPARK_BACK, 0.5 + 0.5 * math.sin(a))
        out.append((int(round(x - 0.5)), int(round(y - 0.5)), amount, colour, math.sin(a) < 0))
    return out


def _frame(t: float) -> Image.Image:
    wag = math.sin(2 * math.pi * t)
    near, far = round(0.75 * wag), round(1.4 * wag)
    pect = "folded" if math.sin(2 * math.pi * 2 * t + 1.0) > 0.3 else "spread"
    pelvic = "swept" if math.sin(2 * math.pi * t + 2.0) > 0 else "rest"

    layer = {k: ch for k, ch in FIN_LAYER.items() if ch not in TAIL_KEYS}
    for k, (tx, ty) in enumerate(CREST_TIPS):      # a ripple runs down the crest
        if math.sin(2 * math.pi * (t - 0.12 * k)) > 0.45:
            del layer[(tx, ty)]
            layer[(tx + 1, ty + 1)] = "e"
    for key, ch in _tail(near, far).items():
        layer.setdefault(key, ch)
    for key in PELVIC[pelvic]:
        layer.setdefault(key, "a")
    img = canvas(SIZE)
    px = img.load()
    zones: dict = {}
    for (x, y), ch in layer.items():
        if MASK[y, x]:
            continue
        colour, alpha = _fin_colour(ch, x, y, layer)
        c = rgba(colour)
        px[x, y] = (c[0], c[1], c[2], alpha)
        zones[(x, y)] = "fin"
    body, bz = _body(t)
    img.alpha_composite(body)
    zones.update(bz)
    # the butterfly pectoral lies over the body: pale gold, translucent, darker rim
    px = img.load()
    pset = set(PECT[pect])
    for (x, y) in pset:
        on_body = MASK[y, x]
        rim = any((x + dx, y + dy) not in pset for dx, dy in ((1, 0), (0, 1)))
        if (x, y) in PECT_RAYS:
            colour = FIN[3]
        elif rim and on_body:
            colour = FIN[1]
        else:
            colour = FIN[4] if (x - 1, y) in pset and (x, y - 1) in pset else FIN[5]
        if on_body:
            px[x, y] = rgba(mix(px[x, y], colour, 0.72))
            zones[(x, y)] = "pect"
        else:
            cc = rgba(colour)
            px[x, y] = (cc[0], cc[1], cc[2], 212)
            zones[(x, y)] = "fin"
    _glint(img, zones, t)
    _sheen(img, zones, t)
    img = _outline(img)
    px = img.load()
    for x, y, c in _barbels(t):
        if not px[x, y][3]:
            px[x, y] = rgba(c)
    img = _rim_glow(img, t)
    px = img.load()
    orb = _orbiters(t)
    for x, y, amount, colour, front_ in orb:
        if front_:
            continue
        layer_img = canvas(SIZE)
        sparkle(layer_img, x, y, amount, colour=colour, reach=2)
        lp = layer_img.load()
        for yy in range(SIZE):
            for xx in range(SIZE):
                if lp[xx, yy][3] and px[xx, yy][3] < 200:
                    px[xx, yy] = lp[xx, yy]
    for x, y, amount, colour, front_ in orb:
        if front_:
            sparkle(img, x, y, amount, colour=colour, reach=2)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
