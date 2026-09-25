"""Northern Pike: an uncommon fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection's shared pose: side view, head up-left, the long
straight back running down a 2:1 diagonal to the forked tail. Pike anatomy: the flat
duck-bill jaws with the long dark gape and the lower jaw jutting past the upper, a golden
eye set high on the head, a gill cover behind it, a low pectoral fin, the pelvics mid-belly,
and the pike's tell: dorsal and anal fins pushed far back, right in front of the tail.
Colours follow the old 16x16 texture: dark olive back, green flanks with pale bean spots,
a cream belly, and fins that run from olive at the root to rust with dark blotches.

The art is hand-placed pixel by pixel (ART). The animation moves it without redrawing:
the rear body, the dorsal/anal fins and the tail slide whole pixel columns up and down in
a wave that grows toward the tail, the paired fins flap between two poses, and two sheens
sweep head to tail, a soft glint along the back and then a shimmer band over the scales.
"""
from __future__ import annotations

import math

from PIL import Image

from art.kit import mix, rgba, save_animation, shine, sprite

ID = "fish_northern_pike"
NAME = "Northern Pike"
KIND = "item"
MODEL_KEY = "fish/northern_pike"
COUNTERPART = "item/salmon"

N = 32
FRAMES = 12          # UNCOMMON: 10-12 frames
FRAMETIME = 2

# --------------------------------------------------------------------------------------
# Palette: character -> (colour, alpha). Hue-shifted ramps, shadows lean teal-blue, lights
# lean warm yellow; the fins run olive at the root to rust at the rim.
# --------------------------------------------------------------------------------------
COLOURS = {
    # outline: a dark hue-shifted green on the back, dark olive under the belly
    "#": ("#12281b", 255), "%": ("#2a3417", 255),
    # body greens, darkest -> lightest
    "1": ("#1d4027", 255), "2": ("#2a552c", 255), "3": ("#3b6b32", 255), "4": ("#51833a", 255),
    "5": ("#6c9c43", 255), "6": ("#8fb452", 255), "7": ("#b5c969", 255),
    # countershaded belly, shadow -> light
    "a": ("#7f8a4c", 255), "b": ("#aeb070", 255), "c": ("#d2cf95", 255), "d": ("#e8e4b6", 255),
    "e": ("#f6f2d6", 255),
    # pale bean spots
    "s": ("#cfd78a", 255), "S": ("#ebeeb2", 255),
    # jaws and eye
    "m": ("#1e2c18", 255), "W": ("#fffbe6", 255), "E": ("#0d1510", 255), "I": ("#d8a93a", 255),
    # unpaired fins: rim, olive root, rust membrane, pale rays, dark blotches
    "F": ("#3a1c0e", 232), "o": ("#6f6a2c", 214), "f": ("#b25a2a", 204), "r": ("#dc8a4c", 222),
    "k": ("#4a200f", 222),
    # paired fins (pectoral, pelvic): translucent amber
    "P": ("#7c5424", 226), "q": ("#c48c46", 204), "Q": ("#ecbc70", 220),
}
BODY = set("#%1234567abcdesSmWEI")
FIN = set("FofrkPqQ")
SHEEN = set("1234567abcdesS")         # pixels the glint and the shimmer may light
GLINT = "#f4f8c6"
SHIMMER = "#f0fbbf"

# --------------------------------------------------------------------------------------
# The art (neutral pose). Body, head, spots, dorsal, anal, caudal. The paired fins are
# overlays below so they can flap.
# --------------------------------------------------------------------------------------
ART = [
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "..###...........................",
    ".#566##.........................",
    ".%dm555##.......................",
    "..%dm44WE##...........FF........",
    "...%dmmEI55##........FfrF.......",
    "....%dcm44254##.....FfrkF.......",
    ".....%%cm531244##..ForfkF....F..",
    ".......%bd5312244##FookrF...FrF.",
    "........%bd51Ss2244##ooorF.FrfF.",
    ".........%baa4433Ss44##F..FrfF..",
    "..........%b66644332233#.FofF...",
    "...........%bdd6S44331S3#orF....",
    "............%bbdd66Ss2212okF....",
    ".............%%bbdd6633S1ofrF...",
    "...............%%bbdddd22ofrfF..",
    ".................%%bbbbbbofrfF..",
    ".................Fo%%%%%%FfkrF..",
    "..................FofrfF..FfrF..",
    "...................FrkrF..FrfF..",
    "....................FFF....FrF..",
    "...........................FF...",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
]
# Paired fins, two poses each: (x, y) -> character. Painted over the body where they overlap.
PECTORAL = [
    {(12, 14): "P", (12, 15): "q", (13, 15): "Q", (13, 16): "q", (14, 16): "Q", (14, 17): "q"},
    {(12, 14): "P", (12, 15): "q", (13, 15): "Q", (14, 15): "q", (13, 16): "q", (14, 16): "Q"},
]
PELVIC = [
    {(13, 19): "P", (14, 19): "q", (14, 20): "P", (15, 20): "Q", (16, 20): "q", (17, 20): "P",
     (15, 21): "P", (16, 21): "P"},
    {(13, 19): "P", (14, 19): "q", (14, 20): "P", (15, 20): "Q", (16, 20): "P", (15, 21): "P"},
]

# Axial position along the body (the 2:1 axis), used to sweep the sheens head to tail.
_AXIS = (2 / math.sqrt(5), 1 / math.sqrt(5))


def _axial(x: float, y: float) -> float:
    return (x + 0.5) * _AXIS[0] + (y + 0.5) * _AXIS[1]


# --------------------------------------------------------------------------------------
# Motion
# --------------------------------------------------------------------------------------

def _sway(x: int, t: float) -> int:
    """Whole-pixel vertical offset of column x: a wave that grows toward the tail and runs
    a little behind itself, so the tail stock bends before the fin flicks."""
    k = min(1.0, max(0.0, (x - 15) / 14))
    if k == 0:
        return 0
    amp = 1.45 * k ** 1.2
    return int(round(amp * math.sin(2 * math.pi * (t - 0.14 * k))))


def _pose(t: float) -> int:
    """Which paired-fin pose shows at phase t (they beat twice per loop)."""
    return 0 if math.sin(2 * math.pi * (2 * t + 0.1)) >= 0 else 1


# --------------------------------------------------------------------------------------
# Painting
# --------------------------------------------------------------------------------------

def _edges(grid) -> set:
    """Pixels of a character grid that touch an empty pixel (4-neighbours)."""
    out = set()
    for y in range(N):
        for x in range(N):
            if grid[y][x] == ".":
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if not (0 <= nx < N and 0 <= ny < N) or grid[ny][nx] == ".":
                    out.add((x, y))
                    break
    return out


ART_EDGE = _edges(ART)
# Middle row of the body in each art column: above it a new edge takes the back outline.
_MID = {}
for _x in range(N):
    _rows = [_y for _y in range(N) if ART[_y][_x] in BODY]
    if _rows:
        _MID[_x] = (_rows[0] + _rows[-1]) / 2


def _layout(t: float):
    """(base, over, src) for phase t: the art character at every pixel after the sway, the
    paired fin laid over it (or None), and the art pixel each came from."""
    base = [["."] * N for _ in range(N)]
    src = [[None] * N for _ in range(N)]
    for x in range(N):
        dy = _sway(x, t)
        for y in range(N):
            sy = y - dy
            if 0 <= sy < N and ART[sy][x] != ".":
                base[y][x] = ART[sy][x]
                src[y][x] = (x, sy)
    # Where neighbouring columns slid apart, a pixel that was inside can land on the edge
    # of the silhouette: give it the outline colour so the outline never breaks.
    for x, y in _edges(base):
        sx, sy = src[y][x]
        if (sx, sy) in ART_EDGE:
            continue
        ch = base[y][x]
        if ch in FIN:
            base[y][x] = "F"
        elif ch in BODY:
            base[y][x] = "#" if sy < _MID.get(sx, sy) else "%"
    over = [[None] * N for _ in range(N)]
    pose = _pose(t)
    for fin in (PELVIC[pose], PECTORAL[pose]):
        for (x, y), ch in fin.items():
            over[y][x] = ch
    return base, over, src


def _rgba(ch: str) -> tuple[int, int, int, int]:
    colour, alpha = COLOURS[ch]
    r, g, b, _ = rgba(colour)
    return (r, g, b, alpha)


def _frame(t: float) -> Image.Image:
    base, over, src = _layout(t)
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    px = img.load()
    for y in range(N):
        for x in range(N):
            ch = base[y][x]
            if ch != ".":
                px[x, y] = _rgba(ch)
    # A soft glint slides along the back: the two rows under the back outline.
    back = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    bpx = back.load()
    for x in range(N):
        top = next((y for y in range(N) if base[y][x] in BODY), None)
        if top is None:
            continue
        for y in (top + 1, top + 2):
            if y < N and base[y][x] in SHEEN:
                bpx[x, y] = px[x, y]
    lit = shine(back, t, colour=GLINT, width=3.0, strength=0.42, angle=26.57, pause=0.45).load()
    for y in range(N):
        for x in range(N):
            if bpx[x, y][3]:
                px[x, y] = lit[x, y]
    # A shimmer band crosses the scales from head to tail in the second half of the loop.
    run = (t - 0.42) % 1.0
    if run < 0.58:
        centre = 3.0 + (34.0 - 3.0) * run / 0.58
        c = rgba(SHIMMER)
        for y in range(N):
            for x in range(N):
                ch = base[y][x]
                if ch not in SHEEN:
                    continue
                sx, sy = src[y][x]
                k = max(0.0, 1.0 - abs(_axial(sx, sy) - centre) / 2.6)
                if k <= 0:
                    continue
                if ch in "sS":
                    amount = 0.5 * k
                elif (sx + 2 * sy) % 4 == 0:             # a diamond scale net along the axis
                    amount = 0.72 * k
                else:
                    amount = 0.22 * k
                r, g, b, a = px[x, y]
                px[x, y] = (round(r + (c[0] - r) * amount), round(g + (c[1] - g) * amount),
                            round(b + (c[2] - b) * amount), a)
    # Paired fins last, translucent over whatever is beneath.
    for y in range(N):
        for x in range(N):
            ch = over[y][x]
            if ch is None:
                continue
            fin = _rgba(ch)
            under = px[x, y]
            if under[3] == 0:
                px[x, y] = fin
            else:
                k = fin[3] / 255 * 0.85
                px[x, y] = tuple(round(under[i] * (1 - k) + fin[i] * k) for i in range(3)) + (255,)
    return img


def textures() -> None:
    frames = [_frame(i / FRAMES) for i in range(FRAMES)]
    save_animation(frames, "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
