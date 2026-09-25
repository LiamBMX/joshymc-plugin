"""Grouper: an uncommon ocean fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's mottled olive-brown / mauve / tan scheme. A heavy, deep-bodied
sea bass: a big head, a wide oblique mouth whose protruding lower jaw runs back under the
high-set golden eye, a dark stripe running back from the eye, a gill-cover edge, a dark
brown back under a warm rim light, warm brown flanks crossed by broken dark bars (saddle
over blotch) with pale freckles between them, a mauve lower flank and a countershaded tan
belly. One long dorsal fin (a
notched spiny front, a rounded soft rear), a rounded anal fin, a big fan-shaped pectoral,
small pelvic fins and a broad rounded tail with a pale trailing margin, all translucent
with painted rays.

Animation (UNCOMMON, 12 frames x 3 ticks): the tail fans once per loop, the pectoral and
pelvic fins sway a pixel; a soft glint slides along the back (frames 1-5), then a warm
shimmer band of glinting scales rolls across the body, head to tail (frames 7-11).
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sprite

ID = "fish_grouper"
NAME = "Grouper"
KIND = "item"
MODEL_KEY = "fish/grouper"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3
SHIFT = (0, 2)                 # the design grid below sits two pixels above centre

# ---- palettes (darkest -> lightest): olive-brown back, mauve flank, tan belly -------------
OUTLINE = "#2c1b20"
FIN_OUTLINE = ("#3f2c28", 232)
SKIN = ["#3d2622", "#56382b", "#6f4a31", "#86603b", "#9c7247", "#b1875b", "#c59f73"]
RIM = ["#b39168", "#cdb085"]            # warm rim light on the back
MAUVE = ["#664548", "#855f5c", "#a27d71"]
BELLY = ["#a99070", "#c6ad89", "#dcc7a0", "#eddcb8"]
FIN = ["#3a3122", "#4d4429", "#625a33", "#78703f", "#928a51", "#aea56b", "#d8d1a0"]
EYE = ["#140c10", "#fffbea"]
IRIS = ["#8d6a2e", "#d2a84c"]
GLINT = "#fff4d6"
SHIMMER = "#f7dfb2"
SPARK = "#fffbea"

# ---- design grid (x 0..31, before SHIFT) -------------------------------------------------
# Body silhouette: top and bottom row of every column (the jaw tip at x=2, tail base x=22).
TOP = {2: 9, 3: 7, 4: 6, 5: 6, 6: 5, 7: 5, 8: 5, 9: 5, 10: 5, 11: 6, 12: 6, 13: 7, 14: 7, 15: 8,
       16: 9, 17: 10, 18: 11, 19: 12, 20: 13, 21: 14, 22: 15}
BOT = {2: 9, 3: 10, 4: 11, 5: 12, 6: 12, 7: 13, 8: 13, 9: 14, 10: 14, 11: 15, 12: 15, 13: 16, 14: 16,
       15: 16, 16: 17, 17: 17, 18: 17, 19: 18, 20: 18, 21: 18, 22: 18}
MASK = np.zeros((SIZE, SIZE), bool)
for _x in TOP:
    MASK[TOP[_x]:BOT[_x] + 1, _x] = True

# Medial fins behind the body: membrane (f) and rays / spines (r). One long dorsal: a
# notched spiny front with pale spine tips, then a taller rounded soft rear; a rounded anal.
DORSAL_F = [(6, 4), (8, 4), (10, 4), (12, 4), (12, 5), (14, 5), (14, 6), (15, 5), (15, 6), (15, 7),
            (16, 5), (16, 6), (16, 8), (17, 6), (17, 7), (17, 8), (17, 9), (18, 7), (18, 8), (18, 10),
            (19, 9), (19, 10), (19, 11), (20, 11), (20, 12)]
DORSAL_R = [(7, 2), (7, 3), (7, 4), (9, 2), (9, 3), (9, 4), (11, 3), (11, 4), (11, 5), (13, 4), (13, 5),
            (13, 6), (16, 7), (18, 9)]
SPINE_TIPS = {(7, 2), (9, 2), (11, 3), (13, 4)}
ANAL_F = [(15, 17), (16, 18), (17, 18), (17, 20), (18, 18), (18, 19), (18, 20), (19, 19), (19, 20), (20, 19),
          (16, 19)]
ANAL_R = [(17, 19)]
# Tail (neutral pose): row -> (first, last) column; it fans about TAIL_ROOT.
TAIL = {13: (26, 27), 14: (25, 28), 15: (24, 29), 16: (23, 29), 17: (23, 29), 18: (23, 29), 19: (23, 29),
        20: (23, 28), 21: (23, 28), 22: (24, 27), 23: (24, 26), 24: (25, 25)}
TAIL_ROOT = (22.8, 17.0)
TAIL_RAYS = [(26.6, 13.6), (29.3, 16.6), (28.8, 20.4), (25.3, 23.6)]
# Pectoral fin poses over the flank (spread and folded) and the pelvic fins below it.
PECT = {
    "spread": {(11, 10), (12, 10), (13, 10), (11, 11), (12, 11), (13, 11), (14, 11),
               (12, 12), (13, 12), (14, 12), (15, 12), (13, 13), (14, 13)},
    "folded": {(11, 10), (12, 10), (13, 10), (14, 10), (11, 11), (12, 11), (13, 11), (14, 11),
               (15, 11), (13, 12), (14, 12), (15, 12)},
}
PECT_RAYS = {(12, 11), (13, 12), (14, 13), (13, 11), (14, 12), (15, 11), (14, 11)}
PELVIC = {"rest": {(8, 14), (9, 15), (10, 15), (10, 16)},
          "swept": {(8, 14), (9, 15), (10, 16), (11, 16)}}

# Head: mouth line (outline colour), the protruding pale lower jaw below it, the eye high
# and forward, dark bars radiating back from it, the gill-cover edge.
MOUTH = [(3, 9), (4, 10), (5, 10), (6, 11), (7, 11)]
JAW = [(2, 9), (3, 10), (4, 11), (5, 11), (6, 12), (7, 12)]
LIP = [(3, 8), (3, 7)]
EYE_AT = (5, 7)
BARS = [(8, 8), (9, 9), (9, 10)]
GILL = [(10, 6), (11, 7), (11, 8), (11, 9), (10, 10), (10, 11), (10, 12), (9, 13)]
GILL_LIT = [(12, 8), (12, 9)]
# Dark blotches (D core, d edge) and pale freckles (p), placed pixel by pixel: broken bars
# (a saddle under the dorsal fin, then a blotch lower on the flank) with pale gaps between.
MARKS = {
    "D": [(12, 7), (13, 8), (16, 10), (16, 11), (16, 12), (15, 13), (20, 14), (19, 14), (18, 15), (22, 16)],
    "d": [(11, 6), (12, 8), (17, 11), (15, 12), (16, 13), (15, 14), (19, 13), (19, 15), (18, 16), (21, 15),
          (21, 16), (17, 15)],
    "p": [(14, 8), (18, 12), (17, 13), (20, 16), (10, 7), (14, 10)],
}


def _bands():
    """Steps in from the back (kb) and from the belly (kv) for every body pixel."""
    kb = np.full(MASK.shape, -1)
    kv = np.full(MASK.shape, -1)
    for x in TOP:
        for y in range(TOP[x], BOT[x] + 1):
            kb[y, x] = y - TOP[x]
            kv[y, x] = BOT[x] - y
    return kb, kv


KB, KV = _bands()


def _tone(x: int, y: int) -> str:
    """Band colour of a body pixel, lit from the top-left, countershaded: a lit rim, a dark
    olive-brown back, warm brown flanks, mauve lower flanks and a tan belly."""
    kb, kv = int(KB[y, x]), int(KV[y, x])
    f = kb / max(1, kb + kv)
    if kb == 0:
        return RIM[1] if x < 12 else RIM[0] if x < 18 else SKIN[4]
    if kv == 0:
        return BELLY[0] if x > 4 else SKIN[5]
    if f < 0.3:
        return SKIN[3]
    if x <= 9 and f < 0.8:
        return SKIN[5] if f < 0.5 else SKIN[4]      # cheek, shading down to the mouth
    if f < 0.55:
        return SKIN[5]
    if f < 0.74:
        return MAUVE[2]
    if kv == 1:
        return BELLY[2]
    return BELLY[3]


DARKER = {BELLY[2]: MAUVE[0], BELLY[3]: MAUVE[1], BELLY[0]: MAUVE[0], MAUVE[2]: SKIN[1]}
DARK = {BELLY[2]: MAUVE[2], BELLY[3]: MAUVE[2], BELLY[0]: MAUVE[1], MAUVE[2]: MAUVE[0], SKIN[3]: SKIN[2],
        SKIN[4]: SKIN[2], SKIN[5]: SKIN[3]}
LIGHTER = {SKIN[3]: SKIN[5], SKIN[4]: BELLY[1], SKIN[5]: BELLY[2], MAUVE[2]: BELLY[2]}


def _body():
    """The body layer (design coords) and its zone map."""
    cols, zones = {}, {}
    for y in range(SIZE):
        for x in range(SIZE):
            if not MASK[y, x]:
                continue
            cols[(x, y)] = _tone(x, y)
            kb, kv = int(KB[y, x]), int(KV[y, x])
            zones[(x, y)] = "head" if x <= 10 else "back" if kb <= 1 else \
                "belly" if kb / max(1, kb + kv) >= 0.56 else "flank"
    for ch, points in MARKS.items():
        for p in points:
            if p not in cols:
                continue
            if ch == "d":
                cols[p] = DARK.get(_tone(*p), SKIN[2])
            elif ch == "D":
                cols[p] = DARKER.get(_tone(*p), SKIN[0])
            else:
                cols[p] = LIGHTER.get(_tone(*p), BELLY[3])
    for p in BARS:
        cols[p] = SKIN[1]
    for p in GILL:
        cols[p] = mix(DARKER.get(_tone(*p), SKIN[2]), SKIN[1], 0.4)
        zones[p] = "line"
    for p in GILL_LIT:
        cols[p] = SKIN[6]
    for p in LIP:
        cols[p] = SKIN[5]
    for p in JAW:
        cols[p] = BELLY[2] if p[0] > 2 else BELLY[3]
        zones[p] = "head"
    for p in MOUTH:
        cols[p] = OUTLINE
        zones[p] = "line"
    ex, ey = EYE_AT
    for dx, dy, c in ((0, 0, EYE[1]), (1, 0, EYE[0]), (0, 1, EYE[0]), (1, 1, EYE[0]),
                      (2, 0, IRIS[1]), (2, 1, IRIS[0]), (-1, 1, IRIS[1])):
        cols[(ex + dx, ey + dy)] = c
        zones[(ex + dx, ey + dy)] = "eye"
    return cols, zones


BODY_COLS, BODY_ZONES = _body()


def _near_seg(px_, py_, a, b, reach):
    ax_, ay_ = a
    bx_, by_ = b
    dx, dy = bx_ - ax_, by_ - ay_
    k = max(0.0, min(1.0, ((px_ - ax_) * dx + (py_ - ay_) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px_ - ax_ - k * dx, py_ - ay_ - k * dy) <= reach


def _tail(angle: float) -> dict:
    """Tail pixels -> (colour, alpha) rotated `angle` degrees about the root (nearest)."""
    base = {(x, y) for y, (x0, x1) in TAIL.items() for x in range(x0, x1 + 1)}
    a = math.radians(angle)
    c, s = math.cos(a), math.sin(a)
    rx, ry = TAIL_ROOT
    out = {}
    for y in range(9, 28):
        for x in range(21, 31):
            if MASK[y, x]:
                continue
            # sample the neutral pose (inverse rotation)
            dx, dy = x + 0.5 - rx, y + 0.5 - ry
            sx, sy = rx + dx * c + dy * s, ry - dx * s + dy * c
            src = (int(math.floor(sx)), int(math.floor(sy)))
            if src not in base:
                continue
            out[(x, y)] = (sx, sy)
    pix = {}
    keys = set(out)

    def margin(x, y):
        return (x, y) in keys and ((x + 1, y) not in keys and (x + 1, y + 1) not in keys
                                   or (x, y + 1) not in keys and y > 20)

    for (x, y), (sx, sy) in out.items():
        edge_r = margin(x, y)
        sub = not edge_r and (margin(x + 1, y) or margin(x, y + 1) and y > 19)
        lit = (x, y - 1) not in keys and not MASK[y - 1, x] or (x - 1, y) not in keys and not MASK[y, x - 1]
        ray = any(_near_seg(sx, sy, TAIL_ROOT, tip, 0.42) for tip in TAIL_RAYS)
        root = math.hypot(sx - rx, sy - ry) < 1.9
        if edge_r and not lit:
            pix[(x, y)] = (FIN[6], 188)             # pale trailing margin
        elif lit:
            pix[(x, y)] = (FIN[4], 212)
        elif ray or sub:
            pix[(x, y)] = (FIN[1], 232)
        elif root:
            pix[(x, y)] = (FIN[1], 226)
        else:
            pix[(x, y)] = (FIN[3], 214)
    return pix


def _fins(pelvic: str, ripple: int) -> dict:
    """Medial and pelvic fins -> (colour, alpha)."""
    pts = {p: "f" for p in DORSAL_F}
    pts.update({p: "r" for p in DORSAL_R})
    pts.update({p: "a" for p in ANAL_F})
    pts.update({p: "A" for p in ANAL_R})
    for p in PELVIC[pelvic]:
        pts[p] = "p"
    # the soft dorsal's crest lifts a pixel on the ripple
    if ripple:
        for p in [(16, 6), (17, 7)]:
            pts.setdefault((p[0] + 1, p[1] - 1), "f")
    out = {}
    for (x, y), ch in pts.items():
        open_up = (x, y - 1) not in pts and not MASK[y - 1, x]
        open_left = (x - 1, y) not in pts and not MASK[y, x - 1]
        by_body = any(MASK[y + dy, x + dx] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        if ch == "r":
            col, a = (FIN[6], 236) if (x, y) in SPINE_TIPS else (FIN[1], 234)
        elif ch == "A":
            col, a = FIN[2], 230
        elif open_up or open_left:
            col, a = (FIN[5] if ch == "f" else FIN[4]), 204
        elif by_body:
            col, a = FIN[2], 222
        else:
            col, a = (FIN[3] if ch == "f" else FIN[4]), 200
        out[(x, y)] = (col, a)
    return out


def _compose(t: float):
    """The frame at phase t in design coordinates: {pixel: (colour, alpha)} plus zones."""
    sway = math.sin(2 * math.pi * t)
    flap = math.sin(2 * math.pi * (t + 0.3))
    ripple = 1 if math.sin(2 * math.pi * (t - 0.15)) > 0.5 else 0
    pix = {}
    pix.update(_tail(8.0 * sway))
    pix.update(_fins("swept" if math.sin(2 * math.pi * t + 2.0) > 0 else "rest", ripple))
    zones = {}
    pect = PECT["folded" if flap > 0.0 else "spread"]
    for p, c in BODY_COLS.items():
        zone = BODY_ZONES[p]
        if p in pect and zone not in ("eye", "line"):
            tint = FIN[3] if p in PECT_RAYS else FIN[5]
            edge = any((p[0] + dx, p[1] + dy) not in pect for dx, dy in ((1, 0), (0, 1)))
            lit = (p[0], p[1] - 1) not in pect and not edge
            if lit:
                c = mix(c, FIN[6], 0.6)                 # the fin's lit leading edge
            else:
                c = mix(c, FIN[1] if edge else tint, 0.72 if edge else 0.62)
            zone = "pect"
        pix[p] = (c, 255)
        zones[p] = zone
    return pix, zones


def _paint(pix: dict) -> Image.Image:
    img = canvas(SIZE)
    px = img.load()
    sx, sy = SHIFT
    for (x, y), (c, a) in pix.items():
        r, g, b, _ = rgba(c)
        px[x + sx, y + sy] = (r, g, b, a)
    # 1 px outline: dark plum-brown beside the body, a softer brown beside fins only
    fr, fg, fb, _ = rgba(FIN_OUTLINE[0])
    dark = rgba(OUTLINE)
    body = {(x + sx, y + sy) for (x, y) in BODY_COLS}
    filled = {(x + sx, y + sy) for (x, y) in pix}
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in filled:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + dx, y + dy) in filled]
            if not near:
                continue
            px[x, y] = dark if any(p in body for p in near) else (fr, fg, fb, FIN_OUTLINE[1])
    return img


def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


AXIS = (math.cos(math.radians(27)), math.sin(math.radians(27)))


def _along(x: int, y: int) -> float:
    """Distance along the body axis from the snout (design coords)."""
    return (x + 0.5 - 2.0) * AXIS[0] + (y + 0.5 - 8.5) * AXIS[1]


def _effects(img: Image.Image, t: float, zones: dict) -> None:
    """Frames 1-5: a soft glint along the back. Frames 7-11: a shimmer band of glinting
    scales rolls across the body, head to tail. Frames 0 and 6 rest."""
    step = round(t * FRAMES) % FRAMES
    px = img.load()
    sx, sy = SHIFT
    if 1 <= step <= 5:
        centre = 2.0 + 19.0 * (step - 1) / 4.0
        for (x, y), zone in zones.items():
            kb = int(KB[y, x])
            if zone in ("eye", "line") or kb > 2:
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.2) * 0.78 * (1.0 - kb / 3.5)
            if k > 0:
                _blend(px, x + sx, y + sy, GLINT, k)
    elif step >= 7:
        centre = 5.0 + 16.0 * (step - 7) / 4.0
        for (x, y), zone in zones.items():
            if zone in ("eye", "line") or KB[y, x] < 1 or KV[y, x] < 1:
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.2)
            if k <= 0:
                continue
            spark = y % 2 == 1 and (x + y // 2) % 2 == 0 and zone != "pect"
            _blend(px, x + sx, y + sy, SPARK if spark else SHIMMER,
                   (0.9 if spark else 0.42) * min(1.0, k * 1.3))


def _frame(t: float) -> Image.Image:
    pix, zones = _compose(t)
    img = _paint(pix)
    _effects(img, t, zones)
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
