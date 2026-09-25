"""Walleye: an uncommon catch in the JoshyMC fishing collection, as a flat animated sprite.

Side view, head up and to the left, the body running diagonally down to a forked tail at
the lower right like the rest of the collection. A slender olive-and-brass predator: an
olive back broken by four dark saddles under a lit back edge, a golden flank with a glossy
streak, a countershaded cream belly, the big glassy eye the species is named for (dark
pupil, catchlight, silvery rim), a long jaw line, the gill-cover edge, a spiny first dorsal
with the black blotch at its rear base, a spotted soft dorsal, white-tipped pelvic and anal
fins, and a forked tail with the walleye's white tip on its lower lobe.

Animation (uncommon, 12 frames of 3 ticks): a wave rolls through the tail so the lobes
flick about a pixel, the pectoral, pelvic and anal fins sweep back in turn, and once per
loop a golden shimmer band crosses the body head to tail, lighting the scales, with a soft
kit.shine() glint sliding along the back just ahead of it.

The art is hand-placed on a 32x32 grid: the body is a column-by-column silhouette whose
tone bands sit at fixed offsets from its outline (so they step as cleanly as the outline),
plus pixel-by-pixel fins; everything is then tilted column by column into the diagonal.
"""
from __future__ import annotations

from art.kit import animate, canvas, mix, rgba, save_animation, shine, sprite

ID = "fish_walleye"
NAME = "Walleye"
KIND = "item"
MODEL_KEY = "fish/walleye"
COUNTERPART = "item/cod"

N = 32
FRAMES = 12
FRAMETIME = 3

# --------------------------------------------------------------------------------------
# Palette. Skin drifts from cool olive shadows through gold to warm cream.
# --------------------------------------------------------------------------------------
PAL = {
    "1": "#283015", "2": "#39441c", "3": "#4d5a23", "4": "#65712b", "5": "#818835", "6": "#a39e3f",
    "7": "#c1ae48", "8": "#d8c15c", "9": "#e9d88e", "A": "#f3ead0",
    # fins (translucent): webs, rays, the black blotch, white tips
    "f": "#8f8434dc", "g": "#ad9f45dc", "h": "#d0bf68dc", "r": "#4a4220e0", "s": "#6a6230e0",
    "k": "#17180ee6", "w": "#f6f2e0e6", "u": "#c2ae4edc", "q": "#8e8036e0", "z": "#f4eccae4",
    # pectoral fin over the flank (opaque: it is painted onto the body)
    "P": "#9d8c3c", "Q": "#7a6c2e", "R": "#5c5222", "S": "#e2cd74",
    # eye: dark pupil, glassy lower pupil, catchlight, silvery rim
    "E": "#0e1418", "F": "#2b3a40", "C": "#ffffff", "I": "#aab09a", "J": "#8e9474",
    # scale shimmer highlights
    "X": "#fff3b8", "Y": "#ffe486",
}
OUTLINE = "#1b2110"
FIN_OUTLINE = (0x28, 0x25, 0x11, 236)
BODY_CHARS = set("123456789APQRSEFCIJXY")  # outlined with OUTLINE rather than FIN_OUTLINE
SKIN = "123456789A"  # the skin ramp, darkest to lightest

# --------------------------------------------------------------------------------------
# Body: per column, the top row and the tones from the back down to the belly
# --------------------------------------------------------------------------------------
# 5/4 lit back edge, 1-2 saddles, 3-4 olive back, 7-9 gold flank, A cream belly, 8/7/6 belly
# shadow line. Head: C catchlight, E/F glassy pupil, I/J silvery rim, 3 jaw line, 2 gill edge.
BODY = {
    2: (12, "7"),
    3: (11, "583"),
    4: (11, "5839"),
    5: (10, "5CF73A"),
    6: (10, "5EEJ4A"),
    7: (10, "5IJ88A8"),
    8: (10, "537A9A8"),
    9: (10, "533989A8"),
    10: (10, "534229A8"),
    11: (11, "53489A8"),
    12: (11, "51278AA8"),
    13: (12, "5129AA8"),
    14: (12, "5349AA8"),
    15: (13, "53498A8"),
    16: (13, "51279A7"),
    17: (14, "4128A7"),
    18: (14, "434897"),
    19: (15, "434897"),
    20: (15, "412796"),
    21: (16, "41286"),
    22: (16, "4367"),
    23: (17, "325"),
    24: (17, "314"),
}

# Fins drawn pixel by pixel: the spiny dorsal (four spines, black blotch at the rear base),
# the spotted soft dorsal, the anal and pelvic fins with white tips.
FINS = {
    (11, 6): "r", (12, 6): "h", (13, 6): "h",
    (10, 8): "r", (10, 9): "r",
    (11, 7): "r", (12, 7): "g", (13, 7): "r", (14, 7): "h",
    (11, 8): "g", (12, 8): "f", (13, 8): "r", (14, 8): "g", (15, 8): "r",
    (11, 9): "f", (12, 9): "r", (13, 9): "f", (14, 9): "g", (15, 9): "r",
    (11, 10): "f", (12, 10): "r", (13, 10): "f", (14, 10): "r", (15, 10): "g", (16, 10): "h",
    (13, 11): "f", (14, 11): "r", (15, 11): "k", (16, 11): "k",
    (15, 12): "k", (16, 12): "k",
    (18, 12): "h", (19, 12): "h", (20, 12): "h",
    (18, 13): "g", (19, 13): "r", (20, 13): "f", (21, 13): "h",
    (19, 14): "f", (20, 14): "r", (21, 14): "r", (22, 14): "h",
    (21, 15): "f", (22, 15): "f",
}

# Anal and pelvic fins, white-tipped, each with a rest pose and a swept-back pose.
ANAL = [
    {(19, 21): "q", (20, 21): "u", (20, 22): "z", (21, 21): "q", (21, 22): "u", (22, 20): "u"},
    {(19, 21): "q", (20, 21): "u", (21, 21): "q", (21, 22): "z", (22, 20): "u", (22, 21): "u"},
]
PELVIC = [
    {(11, 18): "q", (12, 19): "u", (13, 19): "q", (13, 20): "u", (14, 19): "u", (14, 20): "z", (15, 20): "z"},
    {(11, 18): "q", (12, 19): "u", (13, 19): "q", (13, 20): "u", (14, 19): "u", (14, 20): "u", (15, 20): "z",
     (16, 20): "z"},
]

# Tail at rest: per column, the (top, bottom) spans of the upper and lower lobes.
CAUDAL = {24: ((16, 16), (20, 20)), 25: ((15, 21),), 26: ((14, 17), (19, 22)), 27: ((13, 16), (20, 23)),
          28: ((12, 15), (21, 24)), 29: ((11, 13), (23, 25))}
WHITE_TIP = ((28, 24), (29, 24), (29, 25))
TAIL_TILT = 1  # the tail moves as one piece with the peduncle, carried a little high
# The tail wave, frame by frame: vertical offsets of columns 27, 28 and 29. One column
# moves per frame, so the flick rolls from the peduncle out to the lobe tips and back.
TAIL_WAVE = [(0, 0, 0), (-1, 0, 0), (-1, -1, 0), (-1, -1, -1), (0, -1, -1), (0, 0, -1),
             (0, 0, 0), (1, 0, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1), (0, 0, 1)]
# Frames in which the paired fins sweep back (staggered so something moves every frame).
PECTORAL_OUT = {2, 3, 4, 5}
PELVIC_OUT = {4, 5, 6, 7}
ANAL_OUT = {8, 9, 10, 11}

# Pectoral fin poses: pale leading edge, olive-amber web, dark trailing edge.
PECTORAL = [
    {(10, 15): "Q", (11, 15): "S", (12, 15): "S", (13, 16): "S", (14, 16): "S", (11, 16): "P",
     (12, 16): "P", (13, 17): "P", (14, 17): "Q", (12, 17): "R"},
    {(10, 15): "Q", (11, 15): "S", (12, 16): "S", (13, 16): "S", (14, 17): "S", (11, 16): "P",
     (12, 17): "P", (13, 17): "P", (14, 18): "Q", (13, 18): "R"},
]


# The fish above is drawn nearly level, then tilted: each column drops by _tilt(x), in steps
# every six columns on odd/even boundaries so they never double up with the outline's own
# steps. Head up-left, back sloping down to the tail at about 25 degrees.
def _tilt(x: int) -> int:
    return (0 if x <= 9 else 1 if x <= 15 else 2 if x <= 21 else 3 if x <= 27 else 4) - 2


def _tilted(layer: dict) -> dict:
    return {(x, y + _tilt(x)): c for (x, y), c in layer.items()}


def _body_base() -> dict:
    return _tilted({(x, top + i): c for x, (top, tones) in BODY.items() for i, c in enumerate(tones)})


def _tail(offsets: dict) -> dict:
    """The caudal fin with each column shifted vertically by offsets[x]."""
    t = {}
    for x, spans in CAUDAL.items():
        dy = offsets.get(x, 0)
        for a, b in spans:
            for y in range(a, b + 1):
                if x <= 25:
                    c = "s" if y in (15, 16, 20, 21) else "r"
                elif y < 18:
                    c = "s" if (x + y) % 3 == 0 else ("h" if x >= 28 else "g")
                else:
                    c = "s" if (x - y) % 3 == 0 else ("h" if x >= 28 else "g")
                if (x, y) in WHITE_TIP:
                    c = "w"
                t[(x, y + dy + TAIL_TILT)] = c
    return t


BASE = _body_base()
DORSALS = _tilted(FINS)


def _chars(t: float) -> dict:
    i = round(t * FRAMES) % FRAMES
    grid = {}
    grid.update(_tail(dict(zip((27, 28, 29), TAIL_WAVE[i]))))
    grid.update(DORSALS)
    grid.update(_tilted(ANAL[i in ANAL_OUT]))
    grid.update(_tilted(PELVIC[i in PELVIC_OUT]))
    grid.update(BASE)
    grid.update(_tilted(PECTORAL[i in PECTORAL_OUT]))
    return grid


# --------------------------------------------------------------------------------------
# Light effects
# --------------------------------------------------------------------------------------
SWEEP = (0.0, 0.75)          # the part of the loop in which the light rolls down the fish
AXIS = (0.906, 0.423)        # the body's direction, 25 degrees below horizontal (cos, sin)
BAND_FROM, BAND_TO = 3.0, 35.0
SHIMMER_GOLD = "#ffd84a"


def _zones() -> dict:
    """(x, y) -> "rim" / "back" / "flank" / "belly" for every skin pixel of the body."""
    z = {}
    for x, (top, tones) in BODY.items():
        n = len(tones)
        for i, c in enumerate(tones):
            if c not in SKIN:
                continue
            j = n - 1 - i
            z[(x, top + i + _tilt(x))] = "rim" if i == 0 else ("back" if i <= 2 and n >= 5 else
                                                  ("belly" if j <= 1 else "flank"))
    return z


ZONES = _zones()


def _scale(x: int, y: int) -> bool:
    """A staggered scale lattice whose rows run along the body (vectors (3, 1) and (1, 2))."""
    return (2 * x - y) % 5 == 0


def _band(t: float):
    """Where the light band is along the body axis, or None while it rests."""
    a, b = SWEEP
    if not a <= t < b:
        return None
    return BAND_FROM + (BAND_TO - BAND_FROM) * (t - a) / (b - a)


def _shimmer(img, grid: dict, t: float) -> None:
    """A golden band crossing the body that lights the scales as it rolls head to tail.
    The pectoral fin lying on the flank only catches a little of it."""
    centre = _band(t)
    if centre is None:
        return
    px = img.load()
    for (x, y), zone in ZONES.items():
        d = abs((x + 0.5) * AXIS[0] + (y + 0.5) * AXIS[1] - centre)
        if d > 2.5:
            continue
        skin = grid.get((x, y)) in SKIN
        if skin and _scale(x, y) and zone in ("flank", "belly") and d < 1.6:
            px[x, y] = rgba(PAL["X"] if d < 0.9 else PAL["Y"])
        else:
            px[x, y] = rgba(mix(px[x, y], SHIMMER_GOLD, (0.62 if skin else 0.3) * (1.0 - d / 2.5)))


def _glint(img, t: float):
    """A soft glint sliding along the back just ahead of the band: kit.shine() at low
    strength, kept to the back edge and the olive back (never the eye)."""
    centre = _band(t)
    if centre is None:
        return img
    width, lead, span = 2.6, 2.5, N * (AXIS[0] + AXIS[1])
    ts = (centre + lead + 2 * width) / (span + 4 * width)
    if not 0.0 <= ts < 1.0:
        return img
    shined = shine(img, ts, colour="#fff6d2", width=width, strength=0.42, angle=25, pause=0.0)
    out = img.copy()
    spx, opx = shined.load(), out.load()
    for (x, y), zone in ZONES.items():
        if zone in ("rim", "back"):
            opx[x, y] = spx[x, y]
    return out


def _frame(t: float):
    grid = _chars(t)
    img = canvas(N)
    px = img.load()
    for (x, y), c in grid.items():
        px[x, y] = rgba(PAL[c])
    for y in range(N):
        for x in range(N):
            if (x, y) in grid:
                continue
            near = [grid.get((x + dx, y + dy)) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
            if any(c in BODY_CHARS for c in near if c):
                px[x, y] = rgba(OUTLINE)
            elif any(near):
                px[x, y] = FIN_OUTLINE
    _shimmer(img, grid, t)
    return _glint(img, t)


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
