"""Sunfish: a common panfish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's golden-yellow scheme. A pumpkinseed-style sunfish: a deep, round,
disc-shaped body with a steep forehead and a small terminal mouth, an olive back under a
lit rim, golden flanks with rust-orange spots, a warm orange countershaded belly,
turquoise squiggles across the cheek, the black "ear flap" on the gill cover tipped with
its red spot, a big 2x2 eye with a catchlight, one long dorsal fin (low notched spiny
front, tall rounded soft rear), the matching anal fin, a long pointed pectoral lying over
the flank, a pelvic fin with a pale leading ray and a broad, shallowly forked tail (fins
translucent with painted rays and a softer outline).

Animated (COMMON, 8 frames x 3 ticks): the tail and fins sway about a pixel on offset
sine phases and a soft warm glint slides along the back from snout to tail, then rests.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, shade, sprite

ID = "fish_sunfish"
NAME = "Sunfish"
KIND = "item"
MODEL_KEY = "fish/sunfish"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 8
FRAMETIME = 3

# ---- palettes (darkest -> lightest), from the old sprite's golds ------------------------
GOLD_BASE = "#d8b43a"


def _g(amount: float) -> str:
    return shade(GOLD_BASE, amount, 0.22)


BODY = [_g(-0.62), _g(-0.46), _g(-0.3), _g(-0.14), GOLD_BASE, _g(0.2), _g(0.4), _g(0.62)]
BACK = [mix(_g(-0.5), "#46602a", 0.4), mix(_g(-0.34), "#62762e", 0.36), mix(_g(-0.18), "#8a9038", 0.3)]
BELLY = ["#b0662a", "#d4863a", "#e89c44", "#f2b654", "#f8d27e"]
OUTLINE = mix(shade(GOLD_BASE, -0.76, 0.3), "#2a2410", 0.3)
OUTLINE_LIT = mix(shade(GOLD_BASE, -0.66, 0.3), "#3a3014", 0.3)
FIN_OUTLINE_ALPHA = 228
_FB = "#d2aa4c"
FIN = [shade(_FB, -0.42, 0.2), shade(_FB, -0.24, 0.2), _FB, shade(_FB, 0.2, 0.15), shade(_FB, 0.42, 0.15),
       shade(_FB, 0.68, 0.15)]
_LB = "#e2a246"
LOW = [shade(_LB, -0.42, 0.2), shade(_LB, -0.2, 0.2), _LB, shade(_LB, 0.25, 0.15), shade(_LB, 0.5, 0.15),
       shade(_LB, 0.72, 0.15)]
SPOT = ["#9c3e1c", "#cc5e26", "#ec8438"]
TEAL = ["#2c7c7a", "#3faaa4", "#7fdcd0"]
FLAP = ["#12160c", "#2a2c16"]
RED = ["#a8221c", "#e64a30"]
EYE = ["#140f08", "#ffffff"]
IRIS = "#b8461c"
GLINT = "#fff6d0"

# ---- the art ----------------------------------------------------------------------------
# Body letters -> (colour, zone). Zones steer the glint.
BODY_LETTERS = {
    "H": (BODY[7], "back"), "h": (BODY[6], "head"), "R": (BODY[5], "back"), "r": (BODY[4], "back"),
    "O": (BACK[1], "back"), "o": (BACK[2], "back"), "q": (BODY[3], "flank"),
    "G": (BODY[4], "flank"), "g": (BODY[5], "flank"),
    "y": (BELLY[3], "belly"), "Y": (BELLY[4], "belly"), "e": (BELLY[2], "belly"), "n": (BELLY[0], "belly"),
    "s": (SPOT[1], "flank"), "S": (SPOT[2], "flank"),
    "T": (TEAL[2], "head"), "t": (TEAL[1], "head"), "c": (BODY[2], "head"),
    "K": (FLAP[0], "eye"), "k": (FLAP[1], "eye"), "X": (RED[1], "eye"), "x": (RED[0], "eye"),
    "P": (EYE[0], "eye"), "W": (EYE[1], "eye"), "i": (IRIS, "eye"), "m": (OUTLINE, "eye"),
}
# Medial fins drawn in the art: membrane / ray letters.
FIN_LETTERS = "fFaA"

ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................ff..............",  # 5
    ".........F..F..fFff.............",  # 6
    ".......fFffFffFffFff............",  # 7
    "......HhOOOOOOfffFfff...........",  # 8
    ".....HhgooooooOOfffFff..........",  # 9
    "....HhWPqqqqqqooOOffFff.........",  # 10
    "...ghgPPiGcGGGqqooOffFf.........",  # 11
    "...mYTtgGGckxGGqqqooOfff........",  # 12
    "....YygggcKKXGGGqqooOff.........",  # 13
    "....eYgTttgGGsGGqqooOf..........",  # 14
    ".....neYyggGGGGsGGqooO..........",  # 15
    ".....neYyygsGGGGGGqooO..........",  # 16
    "......neYyygGGsGGGqor...........",  # 17
    ".......neYyyggGGGsGqoOR.........",  # 18
    "........neYYyyggGGGGqoOR........",  # 19
    ".........nneeYYyyyGGqqqO........",  # 20
    "...........nneeeYyyeeenn........",  # 21
    "..............nnnnaaaaa.........",  # 22
    "..............AaaAaaAa..........",  # 23
    "...............aAaaAa...........",  # 24
    ".................aaa............",  # 25
    "................................",  # 26
    "................................",  # 27
    "................................",  # 28
    "................................",  # 29
    "................................",  # 30
    "................................",  # 31
]


def _cells():
    body, fins = {}, {}
    for y, row in enumerate(ART):
        assert len(row) == SIZE, (y, len(row))
        for x, ch in enumerate(row):
            if ch in BODY_LETTERS:
                body[(x, y)] = ch
            elif ch in FIN_LETTERS:
                fins[(x, y)] = ch
    return body, fins


BODY_CELLS, FIN_CELLS = _cells()
MASK = np.zeros((SIZE, SIZE), bool)
for (_x, _y) in BODY_CELLS:
    MASK[_y, _x] = True

# ---- procedural fins (tail, pelvic, pectoral) in a local frame ----------------------------
# Local frame: origin at the tail base, u along the body axis toward the tail, v toward the back.
THETA = math.radians(24.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
ORIGIN = (23.6, 19.9)
SS = 4
TAIL_REST = 4.0

_ys, _xs = np.mgrid[0:SIZE * SS, 0:SIZE * SS]
_SX, _SY = (_xs + 0.5) / SS - ORIGIN[0], (_ys + 0.5) / SS - ORIGIN[1]
U = _SX * AX[0] + _SY * AX[1]
V = _SX * UP[0] + _SY * UP[1]
_py, _px = np.mgrid[0:SIZE, 0:SIZE]
UC = (_px + 0.5 - ORIGIN[0]) * AX[0] + (_py + 0.5 - ORIGIN[1]) * AX[1]
VC = (_px + 0.5 - ORIGIN[0]) * UP[0] + (_py + 0.5 - ORIGIN[1]) * UP[1]


def _loc(x: float, y: float) -> tuple[float, float]:
    """Local (u, v) of a frame point."""
    dx, dy = x - ORIGIN[0], y - ORIGIN[1]
    return dx * AX[0] + dy * AX[1], dx * UP[0] + dy * UP[1]


def _cov(mask: np.ndarray) -> np.ndarray:
    return mask.reshape(SIZE, SS, SIZE, SS).mean(axis=(1, 3))


def _poly(poly) -> np.ndarray:
    """Coverage of a polygon given in local (u, v) coordinates (even-odd rule)."""
    inside = np.zeros(U.shape, bool)
    n = len(poly)
    for i in range(n):
        u0, v0 = poly[i]
        u1, v1 = poly[(i + 1) % n]
        if v0 == v1:
            continue
        cond = (v0 > V) != (v1 > V)
        cross = u0 + (V - v0) * (u1 - u0) / (v1 - v0)
        inside ^= cond & (U < cross)
    return _cov(inside)


def _rot(points, pivot, degrees):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    pu, pv = pivot
    return [(pu + (u - pu) * c - (v - pv) * s, pv + (u - pu) * s + (v - pv) * c) for u, v in points]


def _near(u, v, a, b, reach):
    au, av = a
    bu, bv = b
    du, dv = bu - au, bv - av
    k = max(0.0, min(1.0, ((u - au) * du + (v - av) * dv) / (du * du + dv * dv)))
    return math.hypot(u - au - k * du, v - av - k * dv) <= reach


def _tail(sway: float):
    pts = [(-0.8, 1.9), (1.1, 2.6), (3.0, 3.7), (4.6, 4.6), (5.3, 4.0), (5.0, 2.5), (4.2, 1.0), (3.6, 0.0),
           (4.2, -1.0), (5.0, -2.5), (5.3, -4.0), (4.6, -4.6), (3.0, -3.7), (1.1, -2.6), (-0.8, -1.9)]
    rays = [((0.2, 1.0), (4.3, 3.7), 1), ((0.2, -1.0), (4.3, -3.7), 1), ((0.4, 0.0), (3.0, 0.0), 1)]
    a = TAIL_REST + 8.0 * sway
    return _rot(pts, (0.0, 0.0), a), [(*_rot(r[:2], (0.0, 0.0), a), r[2]) for r in rays]


# Pectoral poses (x, y, part), lying over the flank behind the gill cover: "L" lit upper
# edge, "p" membrane, "D" shaded lower edge. Pelvic poses hang below the throat ("L" is
# its pale leading ray).
PECT = {
    "spread": [(9, 15, "L"), (10, 15, "L"), (11, 16, "L"), (12, 16, "p"), (13, 17, "p"),
               (9, 16, "D"), (10, 16, "p"), (11, 17, "D"), (12, 17, "D")],
    "lifted": [(9, 15, "L"), (10, 15, "L"), (11, 15, "L"), (12, 16, "p"), (13, 16, "p"),
               (9, 16, "D"), (10, 16, "p"), (11, 16, "p"), (12, 17, "D")],
}
PELVIC = {
    "rest": [(8, 20, "L"), (8, 21, "L"), (9, 21, "m"), (9, 22, "m")],
    "swept": [(8, 20, "L"), (9, 21, "L"), (10, 21, "m"), (10, 22, "m")],
}


def _fin_pixels(fin, cov_min=0.45, over_body=False) -> dict:
    """Cells of a procedural fin: 'm' membrane or 'r<k>' ray (k = palette index)."""
    poly, rays = fin
    cov = _poly(poly)
    out = {}
    for y in range(SIZE):
        for x in range(SIZE):
            if cov[y, x] < cov_min or (MASK[y, x] and not over_body):
                continue
            u, v = UC[y, x], VC[y, x]
            kind = "m"
            for ra, rb, k in rays:
                if cov[y, x] >= 0.8 and _near(u, v, ra, rb, 0.42):
                    kind = f"r{k}"
                    break
            out[(x, y)] = kind
    return out


# ---- painting ---------------------------------------------------------------------------

def _paint_fin_cells(px, cells: dict, pal, alpha: int, ray_alpha: int, others) -> None:
    """Translucent fin: lit edge facing the top-left light, darker trailing edge, rays."""
    def free(p):
        return p not in cells and p not in others

    for (x, y), kind in cells.items():
        if kind.startswith("r"):
            colour, a = pal[int(kind[1:])], ray_alpha
        elif free((x, y - 1)) or free((x - 1, y)):
            colour, a = pal[4], alpha
        elif free((x, y + 1)) or free((x + 1, y)):
            colour, a = pal[2], alpha
        else:
            colour, a = pal[3], alpha
        c = rgba(colour)
        px[x, y] = (c[0], c[1], c[2], a)


def _ripple(cells: dict, ripple: float, rows) -> dict:
    """Shift the outermost rows of an art fin a pixel sideways on the ripple phase."""
    d = round(0.7 * ripple)
    if d == 0:
        return dict(cells)
    return {((x + d, y) if y in rows else (x, y)): ch for (x, y), ch in cells.items()}


def _glint(px, t: float, strength: float = 0.62, width: float = 2.8, pause: float = 0.3) -> None:
    """A soft glint sliding along the back from snout to tail, then resting."""
    run = 1.0 - pause
    if t >= run:
        return
    head_u = _loc(3.5, 11.0)[0]
    centre = head_u - 1.5 + (-head_u + 3.0) * (t / run)
    c = rgba(GLINT)
    for (x, y), ch in BODY_CELLS.items():
        if BODY_LETTERS[ch][1] not in ("back", "head") or ch in "Tt":
            continue
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / width) * strength
        if ch in "Oo":
            k *= 0.7
        if k <= 0:
            continue
        r, g, b, a = px[x, y]
        px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _outline(img: Image.Image, body) -> Image.Image:
    """1 px outline: solid dark olive beside the body, translucent beside fins, a touch
    lighter on the side facing the top-left light."""
    src = img.load()
    out = img.copy()
    dst = out.load()
    dark, lit = rgba(OUTLINE), rgba(OUTLINE_LIT)

    def filled(p):
        return 0 <= p[0] < SIZE and 0 <= p[1] < SIZE and src[p[0], p[1]][3] > 0

    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            near = [p for p in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)) if filled(p)]
            if not near:
                continue
            facing = (filled((x, y + 1)) or filled((x + 1, y))) and not filled((x, y - 1)) \
                and not filled((x - 1, y))
            o = lit if facing else dark
            if any(p in body for p in near):
                dst[x, y] = o
            else:
                dst[x, y] = (o[0], o[1], o[2], FIN_OUTLINE_ALPHA)
    return out


def _frame(t: float) -> Image.Image:
    """One 32x32 frame at loop phase t (0..1)."""
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    px = img.load()
    body = set(BODY_CELLS)

    dorsal = _ripple({k: v for k, v in FIN_CELLS.items() if v in "fF"}, ripple, rows=(5, 6))
    anal = _ripple({k: v for k, v in FIN_CELLS.items() if v in "aA"}, -ripple, rows=(25,))
    fins = {
        "dorsal": ({k: ("r1" if v == "F" else "m") for k, v in dorsal.items()}, FIN, 200, 228),
        "anal": ({k: ("r1" if v == "A" else "m") for k, v in anal.items()}, LOW, 200, 228),
        "tail": (_fin_pixels(_tail(sway), cov_min=0.4), FIN, 205, 230),
        "pelvic": ({(x, y): ("r5" if k == "L" else "m") for x, y, k in PELVIC["rest" if flap < 0.2 else "swept"]},
                   LOW, 210, 225),
    }
    for cells, *_ in fins.values():
        for k in [k for k in cells if k in body]:
            del cells[k]
    everything = set(body)
    for cells, *_ in fins.values():
        everything |= set(cells)
    for cells, pal, alpha, ray_alpha in fins.values():
        _paint_fin_cells(px, cells, pal, alpha, ray_alpha, everything - set(cells))

    for (x, y), ch in BODY_CELLS.items():
        px[x, y] = rgba(BODY_LETTERS[ch][0])
    _glint(px, t)

    # the near pectoral lies over the flank, translucent
    tints = {"L": ("#fff2c4", 0.55), "p": (LOW[1], 0.38), "D": (SPOT[0], 0.5)}
    for x, y, part in PECT["lifted" if math.sin(2 * math.pi * (t + 0.05)) > 0.35 else "spread"]:
        tint, k = tints[part]
        px[x, y] = rgba(mix(px[x, y], tint, k))

    return _outline(img, body)


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
