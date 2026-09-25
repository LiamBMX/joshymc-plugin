"""Smallmouth Bass (uncommon fish).

A bronze-olive bass in the shared fish pose (flat 32x32 sprite, head up-left, tail
down-right): an olive back running into a bronze-gold flank crossed by dark vertical
bars, a cream belly, a red eye with a bar behind it, the jaw ending under the eye, a
joined dorsal fin with a shallow notch between the spiny and soft parts, amber fins with
painted rays and a gently forked tail.

Animated (12 frames x 3 ticks): the tail flexes and the soft fins rock about a pixel,
a soft glint runs along the back from the snout, and right behind it a band of lit
scales sweeps across the flank while the translucent fins catch the light.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, shade, sprite

ID = "fish_smallmouth_bass"
NAME = "Smallmouth Bass"
KIND = "item"
MODEL_KEY = "fish/smallmouth_bass"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# --------------------------------------------------------------------------------------
# Palette (every ramp hue-shifts: shadows toward teal, lights toward warm gold)
# --------------------------------------------------------------------------------------

BODY = {"1": "#263f22", "2": "#35592a", "3": "#4f7a33", "4": "#74933a", "5": "#a0a844", "6": "#c6bd5a",
        "7": "#dfd78c"}                                         # olive back -> bronze-gold flank
BAR = {"x": "#283419", "y": "#3e4c22", "z": "#5d692f"}          # the dark vertical bars
BELLY = {"c": "#b3a977", "k": "#ddd6a6", "w": "#eee8c4"}        # countershaded belly
HEAD = {"m": "#2b2716", "o": "#324c28", "e": "#1c0d0e", "r": "#be3c25", "g": "#fff6dc"}
FINS = {"F": ("#4d3217", 225), "S": ("#6b4a22", 220), "f": ("#7f5a29", 215), "l": ("#b07f38", 205),
        "L": ("#deb66c", 190)}                                  # amber fins: rays dark, edges pale
PECTORAL = {"P": ("#dcbd72", 175), "R": ("#86622c", 210)}       # laid over the flank
UNDER_PECTORAL = BODY["5"]
GLINT = "#f6ebb0"
SCALE_HI = "#fbf4cf"
SCALE_LO = "#e6cf6e"
FIN_LIGHT = "#f6dc96"
OUTLINE_SHADE = -0.65
GLINT_LEAD = 3.5    # the glint runs this far ahead of the scale band

SOLID = {**BODY, **BAR, **BELLY, **HEAD}

# --------------------------------------------------------------------------------------
# The sprite at rest, one key per pixel (see the palette). m mouth, o gill-cover edge,
# e/g eye and its catchlight, r red iris, x/y/z bars. Fin keys are told apart by where
# they sit (see _part()); P/R is the pectoral fin laid over the flank.
# --------------------------------------------------------------------------------------

PAINT = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    ".........S.S....................",  # 6
    ".........FLSLS..................",  # 7
    "........FFlSlSLL.LL.............",  # 8
    ".....67666fSlSlSlfL.............",  # 9
    "...76ge322666SlSflL.............",  # 10
    "..64reeo332x255flllL............",  # 11
    "..4mmr5yo53x32x55ffL............",  # 12
    "...wkmm5oRRy43x225lfL...........",  # 13
    "....ckk5oPPRRy433x4lL...........",  # 14
    ".....ckko6PPPP544x24lL......LL..",  # 15
    ".......ckk666555y43244...llfLL..",  # 16
    "........cckkk666z543x244flflL...",  # 17
    ".........Fccckkkkkky222xfflL....",  # 18
    "..........Fl.cccccccccccflL.....",  # 19
    "..........lFl..FflflL..fflL.....",  # 20
    "...........lL...FlfL...flflL....",  # 21
    ".................LL.....llfL....",  # 22
    ".........................llL....",  # 23
    "..........................LL....",  # 24
    "................................",  # 25
    "................................",  # 26
    "................................",  # 27
    "................................",  # 28
    "................................",  # 29
    "................................",  # 30
    "................................",  # 31
]

FLESH = set(SOLID) | set(PECTORAL)
LEAN = 0.36        # bars and the light band run perpendicular to the body axis (s = x + LEAN * y)


def _key(x: int, y: int) -> str:
    return PAINT[y][x] if 0 <= x < SIZE and 0 <= y < SIZE else "."


def _span(x: int):
    rows = [y for y in range(SIZE) if PAINT[y][x] in FLESH]
    return (min(rows), max(rows)) if rows else None


SPANS = {x: _span(x) for x in range(SIZE)}   # (back row, belly row) of the body per column


def _part(x: int, y: int) -> str:
    """Which moving part a fin pixel belongs to."""
    if _key(x, y) in PECTORAL:
        return "pectoral"
    span = SPANS.get(x)
    if x >= 23 or not span:
        return "tail"
    if y < span[0]:
        return "spiny" if x <= 15 else "soft"
    return "anal" if x >= 14 else "pelvic"


def _parts() -> dict[str, dict[tuple[int, int], str]]:
    out: dict[str, dict[tuple[int, int], str]] = {}
    for y in range(SIZE):
        for x in range(SIZE):
            key = _key(x, y)
            if key in FINS or key in PECTORAL:
                out.setdefault(_part(x, y), {})[(x, y)] = key
    return out


PARTS = _parts()
BEHIND_BODY = ("tail", "spiny", "soft", "anal", "pelvic")

# --------------------------------------------------------------------------------------
# Light: one sweep per loop, a glint along the back leading a band of lit scales
# --------------------------------------------------------------------------------------


def _sweep(t: float) -> float:
    """Where the light is, in s units. It is still off the snout on the first frame and
    has left the tail by the last one, so the loop has no seam."""
    return -2.0 + 37.0 * (t * FRAMES) / (FRAMES - 1)


def _scale(x: int, y: int) -> bool:
    """A staggered lattice with one lit pixel per scale."""
    return x % 4 == (0 if y % 2 == 0 else 2)


def glint(x: int, y: int, t: float) -> float:
    """A soft highlight gliding along the back rim, a little ahead of the scale band."""
    if x > 23:
        return 0.0
    dt = y - SPANS[x][0]
    if dt > 1:
        return 0.0
    d = (x + LEAN * y) - (_sweep(t) + GLINT_LEAD)
    return math.exp(-(d / 1.7) ** 2) * (0.9 if dt == 0 else 0.55)


def shimmer(x: int, y: int, t: float) -> float:
    """A band of lit scales crossing the flank from head to tail."""
    if x < 8:
        return 0.0
    d = (x + LEAN * y) - _sweep(t)
    return math.exp(-(d / 2.0) ** 2)


def fin_light(x: int, y: int, t: float) -> float:
    """The translucent fins brighten as the band passes behind them."""
    d = (x + LEAN * y) - _sweep(t)
    return 0.4 * math.exp(-(d / 2.2) ** 2)


def body_colour(x: int, y: int, t: float) -> str:
    key = _key(x, y)
    c = SOLID.get(key, UNDER_PECTORAL)
    if key in HEAD and key != "o":           # eye, mouth and iris stay as painted
        return c
    g = glint(x, y, t)
    if g > 0.01:
        c = mix(c, GLINT, g)
    s = shimmer(x, y, t)
    if s > 0.01:
        c = mix(c, SCALE_HI, s) if _scale(x, y) else mix(c, SCALE_LO, 0.5 * s)
    return c


# --------------------------------------------------------------------------------------
# Motion: whole-pixel offsets only, so nothing boils between frames
# --------------------------------------------------------------------------------------

def _offset(part: str, x: int, y: int, t: float) -> tuple[int, int]:
    """Displacement of a moving part at destination pixel (x, y), phase t."""
    a = 2 * math.pi * t
    span = SPANS.get(x) or SPANS.get(x - 1) or SPANS.get(x + 1)
    if part == "tail":                       # the tail flexes: tips swing a pixel, the root holds
        w = max(0.0, min(1.0, (x - 23) / 5))
        return 0, round(1.3 * math.sin(a) * w)
    if part == "soft" and span:              # soft dorsal, anal and pelvic rays rock to and fro
        return (round(0.8 * math.sin(a + 0.6)) if span[0] - y >= 2 else 0), 0
    if part == "anal" and span:
        return (round(0.8 * math.sin(a + 2.2)) if y - span[1] >= 2 else 0), 0
    if part == "pelvic" and span:
        return (round(0.8 * math.sin(a + 1.4)) if y - span[1] >= 2 else 0), 0
    if part == "pectoral" and x >= 12:       # the pectoral tip flutters
        return 0, round(0.8 * math.sin(a + 3.2))
    return 0, 0


def _moved(part: str, t: float):
    """Inverse mapping: every destination pixel samples its source, so fins never tear."""
    pixels = PARTS.get(part, {})
    if not pixels:
        return
    xs = [p[0] for p in pixels]
    ys = [p[1] for p in pixels]
    for y in range(min(ys) - 1, max(ys) + 2):
        for x in range(min(xs) - 1, max(xs) + 2):
            dx, dy = _offset(part, x, y, t)
            key = pixels.get((x - dx, y - dy))
            if key:
                yield x, y, key


def _over(dst, src):
    sr, sg, sb, sa = src
    dr, dg, db, da = dst
    a = sa / 255
    b = da / 255 * (1 - a)
    out = a + b
    if out <= 0:
        return (0, 0, 0, 0)
    return (round((sr * a + dr * b) / out), round((sg * a + dg * b) / out), round((sb * a + db * b) / out),
            round(out * 255))


def fish_frame(t: float):
    img = canvas(SIZE)
    px = img.load()
    for part in BEHIND_BODY:
        for x, y, key in _moved(part, t):
            if 0 <= x < SIZE and 0 <= y < SIZE:
                c, a = FINS[key]
                lit = fin_light(x, y, t)
                px[x, y] = rgba(mix(c, FIN_LIGHT, lit) if lit > 0.01 else c)[:3] + (a,)
    body = set()
    for y in range(SIZE):
        for x in range(SIZE):
            if _key(x, y) in FLESH:
                px[x, y] = rgba(body_colour(x, y, t))
                body.add((x, y))
    for x, y, key in _moved("pectoral", t):
        if (x, y) in body:
            c, a = PECTORAL[key]
            px[x, y] = _over(px[x, y], rgba(c)[:3] + (a,))
    # Outline: a dark, hue-shifted shade of whatever it borders (olive on the body,
    # bronze on the fins), never black.
    src = img.copy().load()
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            near = [src[x + dx, y + dy] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                    if 0 <= x + dx < SIZE and 0 <= y + dy < SIZE and src[x + dx, y + dy][3]]
            if near:
                avg = tuple(round(sum(p[i] for p in near) / len(near)) for i in range(3))
                px[x, y] = rgba(shade(avg, OUTLINE_SHADE))
    return img


def textures() -> None:
    save_animation(animate(fish_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
