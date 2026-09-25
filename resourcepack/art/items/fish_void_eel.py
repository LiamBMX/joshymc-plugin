"""Void Eel: an epic fish of the JoshyMC fishing collection, caught in the End.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's scheme: a near-black void-violet body with a glowing purple
lateral stripe and a pale eye. A slim eel body laid in a double bend along the diagonal
(flat head, a steep run, a flatter stretch, a second steep run to the pointed tail), a
raised crown over a big glowing eye with a white catchlight, a long mouth line over a
pale lower jaw, a gill slit and a small magenta pectoral fin, and one continuous
translucent violet fin veil that runs from mid-back round the tail and forward under the
belly, rays painted, blushing magenta toward the tip. Lit from the top-left: a lilac rim
along the back, a deep void back, a violet lateral stripe dotted with bioluminescent
spots and a pale countershaded belly line. The body is built from column spans so every
band is a clean staircase that follows the contour.

Animation (EPIC, 16 frames x 3 ticks): the tail swings a pixel each way with the stem
following a beat behind, the pectoral fin flaps, the lateral-line spots light up one
after the other from head to tail, an iridescent oil-slick band (cyan leading, pink, then
purple, glinting scales scattered through it) sweeps down the body, a soft glint then
slides along the back, and three small twinkles (pink, cyan, lilac) sparkle around the
fish in turn.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_void_eel"
NAME = "Void Eel"
KIND = "item"
MODEL_KEY = "fish/void_eel"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 16
FRAMETIME = 3

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean indigo, lights lean pink ------
OUTLINE = "#0d0324"
FIN_OUTLINE = ("#2c0c52", 232)
VOID = ["#140631", "#1e0b45", "#2a115a"]
RIM = ["#6a4cb0", "#8466c8", "#a68ce0", "#c8b4f0"]
STRIPE = ["#5a2294", "#7632b6"]
BELLY = ["#40307c", "#564696", "#7162b0", "#9284ca", "#b6a8e0"]
GLOW = ["#7b2aa6", "#b13fce", "#e46ee6", "#ffcdf5"]
FIN = ["#3c1a6c", "#6a3098", "#9a3cb4", "#cf62d2", "#f59ae6"]
EYE = ["#ffffff", "#f4c6ff", "#c66ae8", "#6a2a9e"]
IRIDESCENT = ["#6af0ff", "#ff7ad6", "#b07cff"]    # cyan leads, then pink, then purple
GLINT = "#efe2ff"
SHIMMER = "#fff0ff"
IRI_WIDTH = 6.5
SPARKS = [(22, 8, "#ff9be8"), (6, 19, "#8ef0ff"), (28, 15, "#e2ccff")]

# ---- silhouette: body column spans x -> (top, bottom), head at the left ----------------------
SPANS = {
    2: (8, 9), 3: (7, 10), 4: (7, 10), 5: (6, 10), 6: (6, 10), 7: (7, 11), 8: (7, 11), 9: (8, 12),
    10: (9, 13), 11: (10, 14), 12: (11, 15), 13: (12, 16), 14: (13, 17), 15: (13, 17), 16: (14, 18),
    17: (14, 18), 18: (14, 18), 19: (15, 19), 20: (15, 19), 21: (16, 20), 22: (17, 21), 23: (18, 22),
    24: (19, 22), 25: (20, 23), 26: (21, 23), 27: (22, 23), 28: (23, 23),
}
DORSAL = range(12, 29)       # columns with a fin pixel above the back
ANAL = range(14, 29)         # columns with a fin pixel under the belly
TAIL_TIP = {29: (23, 24)}    # fin-only column: the pointed tail
SWIM_FROM = 22               # columns from here back swim

# ---- head details (the head never moves) ------------------------------------------------------
LETTERS = {"o": OUTLINE, "W": EYE[0], "E": EYE[1], "M": EYE[2], "K": EYE[3], "j": BELLY[4],
           "J": BELLY[3], "g": VOID[0], "s": RIM[2], "h": VOID[2], "c": RIM[3]}
HEAD = {
    (5, 6): "c", (6, 6): "c",                                      # crown highlight
    (5, 7): "W", (6, 7): "E", (5, 8): "M", (6, 8): "K",           # glowing eye, catchlight top-left
    (2, 8): "s", (3, 8): "h", (4, 8): "h",                        # snout
    (3, 9): "o", (4, 9): "o", (5, 9): "o",                         # long mouth line
    (2, 9): "J", (3, 10): "j", (4, 10): "j", (5, 10): "J",         # pale lower jaw
    (9, 10): "g", (9, 11): "g",                                    # gill slit
}
# Pectoral fin poses (x, y, tone): spread and folded back. Tones index FIN.
PECT = {
    "spread": [(10, 11, 3), (11, 11, 4), (10, 12, 2), (11, 12, 3), (11, 13, 2), (12, 13, 2)],
    "folded": [(10, 11, 3), (11, 11, 4), (12, 11, 3), (10, 12, 2), (11, 12, 2), (12, 12, 2)],
}


def _offset(x: int, t: float) -> int:
    """Rows a column moves at phase t: the tail block swings a pixel each way and the stem
    ahead of it follows half as far, a beat behind."""
    if x < SWIM_FROM:
        return 0
    if x < SWIM_FROM + 3:
        return round(0.62 * math.sin(2 * math.pi * t - 0.5))
    return round(1.2 * math.sin(2 * math.pi * t))


def _layout(t: float):
    """Body pixels {(x, y): (kb, kv)} and fin pixels {(x, y): ray?} at phase t."""
    body, fins = {}, {}
    for x, (top, bot) in SPANS.items():
        dy = _offset(x, t)
        top, bot = top + dy, bot + dy
        for y in range(top, bot + 1):
            body[(x, y)] = (y - top, bot - y)
        ray = x % 3 == 0
        if x in DORSAL:
            fins[(x, top - 1)] = ray
        if x in ANAL:
            fins[(x, bot + 1)] = ray
    for x, (top, bot) in TAIL_TIP.items():
        dy = _offset(x, t)
        for y in range(top + dy, bot + dy + 1):
            fins[(x, y)] = x % 3 == 0
    return body, fins


def _body_colour(kb: int, kv: int, x: int) -> str:
    """Column bands, back to belly: lilac rim, a deep void back, the glowing lateral stripe
    just above the belly, and a pale countershaded belly line."""
    if kb == 0:
        return RIM[2] if x < 9 else RIM[1] if x < 18 else RIM[0]
    if kv == 0:
        return BELLY[3] if x < 12 else BELLY[2] if x < 20 else BELLY[1]
    if kv == 1:
        return STRIPE[1] if x < 20 else STRIPE[0]           # glowing lateral stripe
    if kb == 1:
        return VOID[0]
    return VOID[1] if x < 17 else VOID[2]


def _blend(px, x, y, colour, k):
    if k <= 0:
        return
    r, g, b, a = px[x, y]
    c = rgba(colour)
    k = min(1.0, k)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _iridescent(p):
    """Colour across the oil-slick band, p in 0..1: cyan -> pink -> purple."""
    p = min(max(p, 0.0), 1.0) * (len(IRIDESCENT) - 1)
    i = min(int(p), len(IRIDESCENT) - 2)
    return mix(IRIDESCENT[i], IRIDESCENT[i + 1], p - i)


def _tidy(img, body):
    """Drop fin pixels that hang on by one side (they flicker as the tail swims)."""
    px = img.load()
    for _ in range(2):
        drop = [(x, y) for y in range(SIZE) for x in range(SIZE)
                if (x, y) not in body and px[x, y][3]
                and sum(1 for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                        if 0 <= x + dx < SIZE and 0 <= y + dy < SIZE and px[x + dx, y + dy][3]) <= 1]
        for x, y in drop:
            px[x, y] = (0, 0, 0, 0)


def _outline(img, body):
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
            if any(p in body for p in near):
                dst[x, y] = rgba(OUTLINE)
            else:
                dst[x, y] = (fr, fg, fb, FIN_OUTLINE[1])
    return out


EFFECTS = True               # False paints the bare fish (for inspecting the base art)


def _frame(t: float):
    body, fins = _layout(t)
    img = canvas(SIZE)
    px = img.load()
    # fins: a translucent violet veil with lighter rays, blushing magenta toward the tail tip
    for (x, y), ray in fins.items():
        if (x, y) in body:
            continue
        glow = min(1.0, max(0.0, (x - 20) / 8.0))
        colour = mix(FIN[1] if ray else FIN[0], FIN[3] if ray else FIN[2], glow)
        r, g, b, _ = rgba(colour)
        px[x, y] = (r, g, b, 224 if ray else 200)
    for (x, y), (kb, kv) in body.items():
        px[x, y] = rgba(_body_colour(kb, kv, x))
    _tidy(img, body)
    for (x, y), letter in HEAD.items():
        px[x, y] = rgba(LETTERS[letter])
    # pectoral fin over the flank, flapping
    pose = "folded" if math.sin(2 * math.pi * (t + 0.15)) > 0.2 else "spread"
    pect = {(x, y) for x, y, _ in PECT[pose]}
    for x, y, tone in PECT[pose]:
        _blend(px, x, y, FIN[tone], 0.7)
    if not EFFECTS:
        return _outline(img, body)

    # lateral-line spots light up one after another, head to tail
    spots = [(x, y) for (x, y), (kb, kv) in body.items()
             if kv == 1 and kb >= 2 and x % 3 == 1 and 13 <= x <= 25 and (x, y) not in pect]
    spots.sort()
    n = max(len(spots), 1)
    for i, (x, y) in enumerate(spots):
        w = 0.5 - 0.5 * math.cos(2 * math.pi * (t - 0.8 * i / n))
        colour = mix(GLOW[1], GLOW[2], min(1.0, w * 1.4))
        px[x, y] = rgba(mix(colour, GLOW[3], max(0.0, w - 0.6) * 1.8))

    # iridescent sheen: an oil-slick band (cyan leading, pink, purple trailing) sweeps down
    # the body in the first part of the loop, glinting scales scattered through it
    run = 0.62
    if t < run:
        centre = 2.0 - IRI_WIDTH + (27.0 + 2 * IRI_WIDTH) * t / run
        for (x, y), (kb, kv) in body.items():
            if (x, y) in HEAD or (x, y) in spots or (x, y) in pect:
                continue
            along = x + 0.5 * (y - SPANS[x][0])
            off = (centre - along) / IRI_WIDTH          # -1 leading edge .. +1 trailing edge
            if not -1.0 < off < 1.0:
                continue
            step = min(4, int((off + 1.0) / 2.0 * 5))         # five clean colour steps
            colour = _iridescent(step / 4)
            edge = abs(off) > 0.72
            if kb >= 1 and kv >= 2 and (x + 2 * y) % 4 == 0 and not edge:
                _blend(px, x, y, mix(colour, SHIMMER, 0.55), 0.85)   # glinting scale
                continue
            k = 0.7 if kb == 0 or kv <= 1 else 0.5 if kb >= 2 else 0.36
            _blend(px, x, y, colour, k * (0.5 if edge else 1.0))

    # soft glint along the back rim in the rest of the loop
    if t >= 0.5:
        gc = -1.0 + 34.0 * (t - 0.5) / 0.5
        for (x, y), (kb, kv) in body.items():
            if kb <= 1 and (x, y) not in HEAD:
                k = max(0.0, 1.0 - abs(x - gc) / 2.5) * (0.55 if kb == 0 else 0.22)
                _blend(px, x, y, GLINT, k)

    out = _outline(img, body)
    # twinkles around the fish, one after another
    for i, (sx, sy, c) in enumerate(SPARKS):
        ph = (t - i / len(SPARKS)) % 1.0
        amount = math.sin(math.pi * ph / 0.42) if ph < 0.42 else 0.0
        sparkle(out, sx, sy, amount, colour=c, reach=2)
    return out


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
