"""Rainbow Trout: an uncommon river fish from the JoshyMC fishing collection.

A 32x32 animated sprite in the shared fish style: side view, head up and to the left,
tail down to the right on a clean 1:2 pixel diagonal, light from the top-left.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, rgba, save_animation, sprite

ID = "fish_rainbow_trout"
NAME = "Rainbow Trout"
KIND = "item"
MODEL_KEY = "fish/rainbow_trout"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 12      # uncommon: 10-12 frames
FRAMETIME = 3    # 36 ticks, a 1.8 s loop

# --------------------------------------------------------------------------------------
# Palette: symbol -> colour or (colour, alpha). Each material is a hue-shifted ramp.
# --------------------------------------------------------------------------------------
PALETTE = {
    # olive back, darkest -> lightest (shadows lean teal, lights lean yellow)
    "1": "#1a3a2b", "2": "#2f5a38", "3": "#4a7a42", "4": "#5f8f48", "5": "#7eab55", "6": "#a8c96b",
    "7": "#cddf90",
    "o": "#a6b08a", "y": "#d3dcbc",  # silvery sage sheen between the back and the band
    "x": "#1b2520",  # black spot
    # the rainbow band, deep wine -> blush
    "q": "#5e1a3f", "r": "#8f2f5b", "s": "#b84a72", "t": "#d6698e", "u": "#ec8fac", "v": "#f9bfcf",
    "m": "#e2bccd",  # pale pink where the band melts into the belly
    # silvery lilac belly
    "a": "#4d3d5c", "b": "#857a9c", "c": "#aea6c2", "d": "#d4cfe0", "e": "#eeebf4", "f": "#fbfafd",
    # eye and mouth
    "E": "#0f141d", "w": "#ffffff", "n": "#4a2a3c",
    "z": "#fffaf0",  # scale sparkle
    # dorsal, adipose and tail fins (translucent olive, rosy at the root)
    "F": ("#56623f", 225), "G": ("#859062", 205), "H": ("#b8bd86", 215), "J": ("#b8687f", 215),
    "K": ("#1f2a22", 240), "I": ("#dfe3b6", 220), "P": ("#dc93a4", 220),
    # pectoral, pelvic and anal fins (rosy peach with white tips)
    "M": ("#9a5563", 220), "L": ("#c98583", 205), "N": ("#e5aaa0", 205), "O": ("#fbf1ea", 230),
}
ZONES = {"back": "1234567oxy", "band": "qrstuvn", "belly": "mabcdefz", "eye": "Ew", "fin": "FGHIJKP",
         "lowfin": "MLNO"}
ZONE = {ch: zone for zone, chars in ZONES.items() for ch in chars}
OUTLINE = {"back": ("#12281d", 255), "band": ("#3d1331", 255), "belly": ("#3a2c48", 255),
           "eye": ("#12281d", 255), "fin": ("#2a3524", 235), "lowfin": ("#7a3d50", 230)}
PRIORITY = ("back", "belly", "band", "fin", "lowfin", "eye")
RAMPS = ("1234567", "qrstuv", "abcdef", "FGHI")
LIFT = {s: ramp[i:] + ramp[-1] * 3 for ramp in RAMPS for i, s in enumerate(ramp)}
LIFT.update({"o": "oyyf", "m": "mvff", "J": "JPPP"})

# --------------------------------------------------------------------------------------
# The fish, pixel by pixel ('.' = empty): body, head, dorsal and adipose fins (static).
# --------------------------------------------------------------------------------------
BODY = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "............HH..................",  # 5
    "...........HGFG.................",  # 6
    ".....6765.HGFGKG................",  # 7
    "...56433255FGGFG................",  # 8
    "..444wE432155FGG................",  # 9
    "..dn4EE3oqx225GKG...............",  # 10
    "...dnruttq333255G...............",  # 11
    "....detsruoox3x25...............",  # 12
    ".....cfersvuoo3325..............",  # 13
    "......cbdessuuoox255x...........",  # 14
    ".......ceemmsstto3x53...........",  # 15
    "........cdeeemssto335...........",  # 16
    ".........cddeeemstto354.........",  # 17
    "..........ccddeeesstx33.........",  # 18
    "............ccdddeesttt.........",  # 19
    "..............cccdddsss.........",  # 20
    ".................ccccbb.........",  # 21
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

# The tail, hand drawn in three poses (x0, y0, rows): swung down, level and swung up.
# It wags twice a loop; the lobes move about a pixel each way.
TAIL = {
    -1: (22, 14, [
        ".........",
        ".........",
        ".....HH..",
        ".HHHHGGF.",
        ".JJGKGF..",
        ".JJGF....",
        ".JKF.....",
        ".HGF.....",
        ".HGF.....",
        ".HF......",
        ".HF......",
        "..H......",
        "..G......",
    ]),
    0: (22, 14, [
        ".........",
        ".....HH..",
        "...HHGGF.",
        ".HHGKGF..",
        ".JJGGF...",
        ".JJKF....",
        ".JGF.....",
        ".HKF.....",
        ".HGF.....",
        ".HGF.....",
        "..HF.....",
        "...H.....",
        ".........",
    ]),
    1: (22, 14, [
        ".....HH..",
        "...HHGGF.",
        "..HHGKF..",
        ".HGGGF...",
        ".JJGF....",
        ".JJF.....",
        ".JGF.....",
        ".HGKF....",
        "..HGF....",
        "..HGF....",
        "...HF....",
        ".........",
        ".........",
    ]),
}

# Lower fins as (x0, y0, rows): at rest, then trailing back. They paddle in a wave that
# runs from the pectoral fin back to the tail.
PECTORAL = ((8, 16, ["MN.", ".LN", "..L", "..O"]), (8, 16, ["MN..", ".LN.", "..LN", "...O"]))
PELVIC = ((13, 20, ["M..", ".LN", "..O"]), (13, 20, ["M...", ".LNO"]))
ANAL = ((17, 22, ["ML.", ".NO"]), (17, 22, ["MLN.", "...O"]))
WAVE = {"pectoral": 0.0, "pelvic": 0.1, "anal": 0.2, "tail": 0.3}

# Light effects run along the body axis (a 1:2 diagonal): p = x * cos + y * sin.
AXIS = (math.cos(math.atan2(1, 2)), math.sin(math.atan2(1, 2)))
LATTICE = 4  # scale sparkles sit on a diamond lattice aligned with the body


def _colour(symbol: str) -> tuple[int, int, int, int]:
    spec = PALETTE[symbol]
    if isinstance(spec, tuple):
        return rgba(spec[0])[:3] + (spec[1],)
    return rgba(spec)


def _lift(symbol: str, steps: int) -> str:
    """The symbol `steps` tones up its own ramp (light moves pixels along their ramp, so
    every lit pixel is still a hand-picked palette colour)."""
    chain = LIFT.get(symbol)
    return chain[min(steps, len(chain) - 1)] if chain else symbol


def _grid(part) -> dict:
    x0, y0, rows = part
    return {(x0 + dx, y0 + dy): ch for dy, row in enumerate(rows) for dx, ch in enumerate(row) if ch != "."}


def _along(x: int, y: int) -> float:
    """How far a pixel centre lies along the body axis, in pixels."""
    return (x + 0.5) * AXIS[0] + (y + 0.5) * AXIS[1]


def _scale(x: int, y: int) -> bool:
    """Scale sparkles: a diamond lattice whose rows follow the body."""
    return (x + 2 * y) % LATTICE == 0


def _glint(syms: dict, t: float) -> None:
    """A soft highlight sliding down the back and over the fins, head to tail (first half)."""
    if t >= 0.5:
        return
    centre = 6.5 + 55.0 * t
    for (x, y), s in syms.items():
        if ZONE[s] in ("back", "fin"):
            d = abs(_along(x, y) - centre)
            syms[(x, y)] = _lift(s, 2 if d < 1.2 else 1 if d < 2.6 else 0)


def _shimmer(syms: dict, t: float) -> None:
    """Then a band of glittering scales rolls back up the flank, tail to head, so the light
    runs one loop round the fish."""
    if t < 0.5:
        return
    centre = 29.5 - 53.0 * (t - 0.5)
    for (x, y), s in syms.items():
        if ZONE[s] in ("band", "belly") or s == "o":
            d = abs(_along(x, y) - centre)
            if d >= 3.0:
                continue
            if _scale(x, y):
                syms[(x, y)] = "z" if d < 1.4 else _lift(s, 2)
            else:
                syms[(x, y)] = _lift(s, 1)


def _outline(img, zones) -> None:
    src = img.copy().load()
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            votes = {}
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < SIZE and 0 <= ny < SIZE and src[nx, ny][3]:
                    votes[zones[ny][nx]] = votes.get(zones[ny][nx], 0) + 1
            if votes:
                best = max(votes, key=lambda z: (votes[z], -PRIORITY.index(z)))
                c, a = OUTLINE[best]
                px[x, y] = rgba(c)[:3] + (a,)


def _pose(t: float, phase: float) -> float:
    return math.sin(2 * math.pi * (2 * t - phase))


def paint(t: float):
    swing = _pose(t, WAVE["tail"])
    syms = _grid(TAIL[1 if swing > 0.5 else -1 if swing < -0.5 else 0])
    syms.update(_grid((0, 0, BODY)))
    syms.update(_grid(PELVIC[_pose(t, WAVE["pelvic"]) > 0.2]))
    syms.update(_grid(ANAL[_pose(t, WAVE["anal"]) > 0.2]))
    _glint(syms, t)
    _shimmer(syms, t)
    img = canvas(SIZE)
    px = img.load()
    zones = [[None] * SIZE for _ in range(SIZE)]
    for (x, y), s in syms.items():
        px[x, y] = _colour(s)
        zones[y][x] = ZONE[s]
    # The near pectoral fin lies over the body: blend it onto what is already there.
    for (x, y), s in _grid(PECTORAL[_pose(t, WAVE["pectoral"]) > 0.2]).items():
        c, under = _colour(s), px[x, y]
        if under[3] == 255:
            k = c[3] / 255
            px[x, y] = tuple(round(under[i] * (1 - k) + c[i] * k) for i in range(3)) + (255,)
        else:
            px[x, y] = c
            zones[y][x] = ZONE[s]
    _outline(img, zones)
    return img


def textures() -> None:
    save_animation(animate(paint, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
