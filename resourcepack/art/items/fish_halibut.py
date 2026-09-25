"""Halibut: an uncommon flatfish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection's shared pose (head up-left, tail down-right), seen
from its eyed side like every flatfish icon. It keeps the old sprite's mottled taupe-olive
scheme with mauve-grey pale spots: a long, flat oval body fringed from above the eyes to
the tail by the continuous dorsal and anal fins (highest just behind mid-body, which gives
the halibut its diamond outline), both eyes stacked on the upper side of the small head,
a big oblique mouth whose gape runs back under the lower eye over a pale projecting lower
jaw, the gill cover, a little pectoral fin behind it, and a broad, shallow-crescent
tail. Lit rim along the back, countershaded toward a pale cream
margin along the lower edge, translucent darker fins with painted rays.

Animation (UNCOMMON, 12 frames x 3 ticks): the fin fringes ripple in waves that roll from
head to tail (how flatfish really swim), the tail sways a pixel and the pectoral fin
flicks; a soft glint slides along the back (frames 1-5), then a shimmer band of sparkling
scales rolls across the body (frames 7-11).
"""
from __future__ import annotations

import math

import numpy as np

from art.kit import animate, canvas, mix, rgba, save_animation, shade, sprite

ID = "fish_halibut"
NAME = "Halibut"
KIND = "item"
MODEL_KEY = "fish/halibut"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palette: hue-shifted ramps from the old sprite's taupe / olive / mauve-grey --------
BASE = "#a4966a"


def _s(amount: float) -> str:
    return shade(BASE, amount, 0.24)


# body, darkest -> lightest (shadows lean violet-brown, lights lean warm straw)
SKIN = [_s(-0.58), _s(-0.42), _s(-0.25), _s(-0.1), BASE, mix(_s(0.24), "#c9b98a", 0.2), _s(0.46)]
PALE = ["#b09c9c", "#cdb8b8", "#ebdcd6"]        # mauve-grey spots (the old sprite's pinkish greys)
DARK = _s(-0.4)                                 # brown blotches
MARGIN = ["#b0a282", "#cdc1a2", "#e3dac0"]      # countershaded lower edge (the blind side's cream)
OUTLINE = "#2c1f1d"
OUTLINE_LIT = "#3d2c25"
FIN_BASE = "#5f5042"
FIN = [shade(FIN_BASE, -0.3, 0.2), shade(FIN_BASE, -0.16, 0.2), FIN_BASE, shade(FIN_BASE, 0.22, 0.2),
       shade(FIN_BASE, 0.42, 0.2)]
MEMBRANE_ALPHA, EDGE_ALPHA, RAY_ALPHA, FIN_OUTLINE_ALPHA = 208, 214, 218, 232
EYE_DARK, EYE_LIGHT, IRIS = "#15170f", "#fbfff0", "#c9a24c"
GLINT = "#fbf6e2"
SHIMMER = "#f1ead6"
SPARK = "#fffdf4"

# ---- the body, painted by hand (x 0..31, y 0..31) ---------------------------------------
# 1-7 skin ramp (dark -> light), c/C cream margin, m/M pale spots, k dark blotch, E/e eye
# (pupil / catchlight), i golden iris, x mouth, l lit lip, g gill cover.
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    "................................",  # 6
    "....566eE6......................",  # 7
    "...5555EEi566...................",  # 8
    "..l5eE5555g5566.................",  # 9
    "...xEEi444g4Mm56................",  # 10
    "...cxxx444g44m556...............",  # 11
    "....cCc44g4444k556..............",  # 12
    ".....cCcg34444k4Mm6.............",  # 13
    ".......cCc344Mm44m56............",  # 14
    ".........cCc3km444k56...........",  # 15
    "..........cCc3344Mm456..........",  # 16
    "............cCc334m4456.........",  # 17
    "...............cCc33455.........",  # 18
    "...................cc34.........",  # 19
    "................................",  # 20
    "................................",  # 21
    "................................",  # 22
    "................................",  # 23
    "................................",  # 24
    "................................",  # 25
    "................................",  # 26
    "................................",  # 27
    "................................",  # 28
    "................................",  # 29
    "................................",  # 30
    "................................",  # 31
]
LETTERS = {
    **{str(i + 1): (SKIN[i], None) for i in range(7)},
    "c": (MARGIN[0], "belly"), "C": (MARGIN[1], "belly"),
    "m": (PALE[1], None), "M": (PALE[2], None), "k": (DARK, None),
    "E": (EYE_DARK, "eye"), "e": (EYE_LIGHT, "eye"), "i": (mix(SKIN[4], IRIS, 0.55), "head"),
    "x": (SKIN[0], "line"), "l": (SKIN[6], "head"), "g": (SKIN[1], "line"),
}
# Pectoral fin poses over the body: membrane p, dark trailing edge P.
PECT = {
    "spread": [(11, 11, "p"), (11, 12, "p"), (12, 12, "p"), (11, 13, "p"), (12, 13, "p"), (13, 13, "P"),
               (12, 14, "P")],
    "folded": [(11, 11, "p"), (11, 12, "p"), (12, 12, "p"), (13, 12, "p"), (14, 12, "P"), (12, 13, "P"),
               (13, 13, "P")],
}

# ---- fin geometry (local frame: u from the snout toward the tail, v toward the back) ----
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SNOUT = (2.8, 7.4)
SS = 4
SL = 21.6          # snout to tail base
TL = 5.2           # tail length


def _smooth(points):
    """Catmull-Rom through (u, value) control points, as a vectorised function of u."""
    us = np.array([p[0] for p in points], float)
    vs = np.array([p[1] for p in points], float)
    dense = np.linspace(us[0], us[-1], 400)
    out = []
    for u in dense:
        i = min(max(int(np.searchsorted(us, u, side="right")) - 1, 0), len(us) - 2)
        p0, p1, p2, p3 = vs[max(i - 1, 0)], vs[i], vs[i + 1], vs[min(i + 2, len(vs) - 1)]
        t = (u - us[i]) / (us[i + 1] - us[i])
        out.append(0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                          + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3))
    table = np.array(out)
    return lambda u: np.interp(u, dense, table)


# Half-depths of the (procedural) body the fins hang from, above and below the axis.
TOP = _smooth([(0.0, -0.1), (0.8, 0.85), (1.8, 1.6), (3.0, 2.2), (4.4, 2.75), (6.0, 3.3), (8.0, 3.85),
               (10.5, 4.15), (13.0, 4.0), (15.4, 3.4), (17.6, 2.5), (19.6, 1.65), (21.6, 1.15)])
BOT = _smooth([(0.0, 1.0), (1.0, 1.55), (2.2, 2.05), (3.8, 2.6), (5.6, 3.2), (8.0, 3.85), (10.5, 4.2),
               (13.0, 4.05), (15.4, 3.45), (17.6, 2.55), (19.6, 1.7), (21.6, 1.15)])
# Fin fringe heights beyond the body edge: the dorsal starts over the upper eye, the anal
# behind the gut; both peak just behind mid-body, sharpening the halibut's diamond outline.
DORSAL = _smooth([(2.4, 0.0), (3.4, 0.55), (5.4, 1.05), (8.0, 1.5), (10.8, 1.95), (13.4, 2.3), (15.6, 2.25),
                  (18.0, 1.75), (20.2, 0.9), (21.4, 0.0)])
ANAL = _smooth([(5.6, 0.0), (6.8, 0.8), (9.4, 1.5), (12.8, 2.2), (15.4, 2.2), (18.0, 1.7), (20.2, 0.9),
                (21.4, 0.0)])

_ys, _xs = np.mgrid[0:SIZE * SS, 0:SIZE * SS]
_SX, _SY = (_xs + 0.5) / SS - SNOUT[0], (_ys + 0.5) / SS - SNOUT[1]
U = _SX * AX[0] + _SY * AX[1]
V = _SX * UP[0] + _SY * UP[1]
_py, _px = np.mgrid[0:SIZE, 0:SIZE]
UC = (_px + 0.5 - SNOUT[0]) * AX[0] + (_py + 0.5 - SNOUT[1]) * AX[1]
VC = (_px + 0.5 - SNOUT[0]) * UP[0] + (_py + 0.5 - SNOUT[1]) * UP[1]


def _cov(mask: np.ndarray) -> np.ndarray:
    """Per-pixel coverage (0..1) of a supersampled mask."""
    return mask.reshape(SIZE, SS, SIZE, SS).mean(axis=(1, 3))


BODY = {(x, y): ch for y, row in enumerate(ART) for x, ch in enumerate(row) if ch != "."}


def _body_zone(x: int, y: int, ch: str) -> str:
    zone = LETTERS[ch][1]
    if zone:
        return zone
    if x <= 8:
        return "head"
    if ch in "56" or (x, y - 1) not in BODY or (x, y - 2) not in BODY or (x + 1, y - 1) not in BODY:
        return "back"
    return "flank"


def _fins(t: float):
    """Coverage of the dorsal and anal fringes and the tail at phase t."""
    # travelling ripple, head -> tail, loops once per cycle; the anal fin runs half a wave behind
    ph = 2 * math.pi * (t - U / 7.0)
    d_h = DORSAL(U) * (1 + 0.28 * np.sin(ph))
    a_h = ANAL(U) * (1 + 0.28 * np.sin(ph + math.pi))
    dorsal = (U >= 2.4) & (U <= 21.4) & (V > TOP(U) - 1.5) & (V <= TOP(U) + d_h)
    anal = (U >= 5.6) & (U <= 21.4) & (V < -BOT(U) + 1.5) & (V >= -BOT(U) - a_h)
    # tail: a widening fan with a shallow crescent edge; the bend grows toward the tips
    sway = 1.3 * math.sin(2 * math.pi * t)
    k = np.clip((U - SL) / TL, 0, 1)
    vb = V - sway * k ** 1.5
    half = 1.2 + 4.6 * k ** 0.6
    edge = SL + TL - 1.5 * (1 - np.clip(np.abs(vb) / 5.4, 0, 1) ** 2)
    tail = (U >= SL - 1.2) & (U <= edge) & (np.abs(vb) <= half)
    return _cov(dorsal), _cov(anal), _cov(tail), sway


def _paint(t: float):
    img = canvas(SIZE)
    px = img.load()
    dcov, acov, tcov, sway = _fins(t)
    zones = {}

    # ---- fins ----
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in BODY:
                continue
            u = UC[y, x]
            if tcov[y, x] >= 0.45 and u >= SL - 1.2:
                zones[(x, y)] = "tail"
            elif dcov[y, x] >= 0.45:
                zones[(x, y)] = "dorsal"
            elif acov[y, x] >= 0.45:
                zones[(x, y)] = "anal"
    fin = set(zones)

    def open_(x, y):
        return (x, y) not in fin and (x, y) not in BODY

    for (x, y), kind in zones.items():
        u, v = UC[y, x], VC[y, x]
        if kind == "tail":
            vb = v - sway * min(1.0, max(0.0, (u - SL) / TL)) ** 1.5
            q = vb / (u - SL + 2.2) * 3.4                 # rays fan out from the peduncle
            ray = abs(q - round(q)) < 0.2 and u > SL + 0.6
        else:
            d = (v - TOP(u)) if kind == "dorsal" else (-BOT(u) - v)
            q = (u - 0.55 * d) / 2.2                      # rays slant back toward the tail
            ray = abs(q - round(q)) < 0.2
        colour, alpha = (FIN[3], RAY_ALPHA) if ray else (FIN[1], MEMBRANE_ALPHA)
        if open_(x, y - 1) and (kind != "anal" or open_(x - 1, y)):
            colour, alpha = FIN[3], EDGE_ALPHA                          # rim facing the light
        elif open_(x, y + 1) or open_(x + 1, y) or open_(x - 1, y):
            colour, alpha = (FIN[3] if ray else FIN[2]), EDGE_ALPHA
        c = rgba(colour)
        px[x, y] = (c[0], c[1], c[2], alpha)

    # ---- body ----
    for (x, y), ch in BODY.items():
        px[x, y] = rgba(LETTERS[ch][0])
        zones[(x, y)] = _body_zone(x, y, ch)
    pose = "folded" if math.sin(2 * math.pi * 2 * t + 0.8) > 0.2 else "spread"
    for x, y, part in PECT[pose]:
        tint = FIN[4] if part == "p" else FIN[1]
        px[x, y] = rgba(mix(px[x, y], tint, 0.5 if part == "p" else 0.7))
        zones[(x, y)] = "pect"

    # ---- outline: 1 px around the silhouette, lighter on the side facing the light ----
    filled = set(zones)
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
            px[x, y] = o if any(p in BODY for p in near) else (o[0], o[1], o[2], FIN_OUTLINE_ALPHA)
    return img, zones


def _sparkle_spot(x: int, y: int) -> bool:
    """A diamond lattice: where the scales catch the light as the shimmer passes."""
    return y % 2 == 1 and (x + y // 2) % 2 == 0


def _frame(t: float):
    img, zones = _paint(t)
    px = img.load()
    step = round(t * FRAMES)
    if 1 <= step <= 5:
        # 1) a soft glint slides along the back and dorsal fin, snout to tail
        centre = 3.0 + 18.0 * (step - 1) / 4.0
        for (x, y), zone in zones.items():
            if zone not in ("back", "head", "dorsal"):
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.0)
            if k > 0:
                r, g, b, a = rgba(mix(px[x, y], GLINT, (0.72 if zone != "dorsal" else 0.35) * k))
                px[x, y] = (r, g, b, px[x, y][3])
    elif step >= 7:
        # 2) a shimmer band of sparkling scales rolls across the body
        centre = 5.5 + 15.5 * (step - 7) / 4.0
        for (x, y), zone in zones.items():
            if zone not in ("flank", "back", "belly", "pect", "line"):
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 2.8)
            if k <= 0:
                continue
            spark = _sparkle_spot(x, y) and zone in ("flank", "back", "belly")
            strength = (0.85 if spark else 0.3) * min(1.0, k * 1.3)
            px[x, y] = rgba(mix(px[x, y], SPARK if spark else SHIMMER, strength))
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
