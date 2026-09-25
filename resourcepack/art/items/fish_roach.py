"""Roach (common fish): a silver cyprinid icon with its signature red eye and red-orange fins.

A flat 32x32 sprite in the fishing collection style: side view, head up-left, body on a
diagonal down to a forked tail. Teal-slate back, bright silver flanks with a rosy sheen,
white belly; dusky red dorsal and tail, vivid orange-red paired and anal fins.

Animation (COMMON): 8 frames x 3 ticks. The tail rocks about its base, every fin sways
about 1 px on its own phase, and a soft glint slides along the back.
"""
from __future__ import annotations

import math

from PIL import Image

from art.kit import animate, canvas, rgba, save_animation, shade, shine, sprite

ID = "fish_roach"
NAME = "Roach"
KIND = "item"
MODEL_KEY = "fish/roach"
COUNTERPART = "item/cod"

PAL = {
    # back: teal slate, dark -> light
    "2": "#29394d", "3": "#3a4f63", "4": "#526b7b", "5": "#7394a0", "6": "#a9c4c6",
    # silver flank
    "a": "#5f6180", "b": "#7b7e98", "c": "#989cb2", "d": "#b6b9c8", "e": "#d2d3de", "f": "#eeedf1",
    # rosy sheen and belly
    "s": "#c3a6b0", "t": "#dcc3c7", "u": "#b6acb9", "v": "#dcd4d9", "w": "#f8f3f0",
    # eye
    "k": "#150e20", "x": "#ffffff", "i": "#b01e2e", "j": "#e0403a",
    # red-orange fins (pectoral, pelvic, anal)
    "S": "#b3342e", "T": "#d9552e", "U": "#f07d3d", "V": "#f9a960",
    # dusky red fins (dorsal, tail)
    "D": "#55223a", "E": "#852f3f", "F": "#b04447", "G": "#cf604b", "H": "#ea8c62",
}
# Fins get lighter and more see-through toward their margins.
ALPHA = {"S": 216, "T": 208, "U": 200, "V": 192, "D": 222, "E": 216, "F": 208, "G": 200, "H": 192}
# Outline: darker hue-shifted shades of the back and the fins, as (lit top-left edge, shadowed edge).
OUTLINE = {"body": (shade(PAL["3"], -0.2), shade(PAL["3"], -0.55)),
           "fin": (shade(PAL["E"], -0.2), shade(PAL["E"], -0.55))}
GLINT = "#effcff"
GLINT_WIDTH = 4.5

BODY = [
    # 0         1         2         3
    # 01234567890123456789012345678901
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    "................................",  # 6
    ".....56655......................",  # 7
    "...554443355....................",  # 8
    "..54biib44335...................",  # 9
    "...adxkibbb3344.................",  # 10
    "...uekkjdcbb4334................",  # 11
    "....wjjefcdfb3424...............",  # 12
    "....uwssefcefbbb23..............",  # 13
    ".....uwsssceefefb33.............",  # 14
    "......uwwwssdcdcfb23............",  # 15
    ".......uwwwwtsttdeb33...........",  # 16
    "........uuwwssstscdb23..........",  # 17
    "..........uuwwwwvssccc..........",  # 18
    "............uuuuuvvssu..........",  # 19
    ".................uuuu...........",  # 20
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

# Fins as (left, top, rows, pivot): pivot is the base point each fin sways about.
DORSAL = (12, 4, [
    "..H..",
    ".GG..",
    ".GEH.",
    "GFEG.",
    "FEFE.",
    ".DEFG",
    "...EG",
    "....G",
], (14.0, 10.0))
CAUDAL = (22, 15, [
    ".....GH",
    "..GGGH.",
    "DEFEG..",
    "DFEG...",
    "DEG....",
    "DF.....",
    "EFG....",
    "EEG....",
    ".EFG...",
    ".FEG...",
    "..FG...",
    "...H...",
], (21.6, 18.6))
PELVIC = (8, 18, [
    "SS..",
    ".ST.",
    "..TU",
    "...V",
], (9.2, 18.0))
ANAL = (13, 20, [
    "SSTT..",
    "TUTUUV",
    "TUV...",
    "UV....",
], (15.5, 20.0))
PECTORAL = (9, 14, [
    "S..",
    "TU.",
    ".UV",
], (9.4, 14.2))
PECTORAL_OPACITY = 0.6  # it lies on the flank, so it is blended over the body


def _layer(fin) -> dict:
    left, top, rows, _ = fin
    return {(left + x, top + y): ch for y, row in enumerate(rows) for x, ch in enumerate(row) if ch != "."}


def _swayed(fin, degrees: float, columns: bool = False) -> dict:
    """The fin swung `degrees` (clockwise on screen) about its pivot, as whole-pixel shears
    so painted rays and edges stay crisp: each row slides sideways by a rounded amount,
    and with columns=True each column also slides up or down (a full small rotation,
    used for the tail; the other fins just lean like pendulums)."""
    pixels = _layer(fin)
    if abs(degrees) < 1e-6:
        return pixels
    px, py = fin[3]
    k = math.radians(degrees)
    xs = [p[0] for p in pixels]
    ys = [p[1] for p in pixels]
    out = {}
    for y in range(min(ys) - 2, max(ys) + 3):
        for x in range(min(xs) - 2, max(xs) + 3):
            src = (x + round(k * (y + 0.5 - py)), y - (round(k * (x + 0.5 - px)) if columns else 0))
            if src in pixels:
                out[(x, y)] = pixels[src]
    return out


def _body() -> dict:
    return {(x, y): ch for y, row in enumerate(BODY) for x, ch in enumerate(row) if ch != "."}


BACK = set("23456b")


def _frame(t: float) -> Image.Image:
    wave_ = lambda offset: math.sin(2 * math.pi * (t - offset))  # noqa: E731
    fins = _swayed(CAUDAL, 9.0 * wave_(0.0), columns=True)
    for fin, amp, offset in ((DORSAL, 9.0, 0.15), (PELVIC, 12.0, 0.35), (ANAL, 11.0, 0.3)):
        fins.update(_swayed(fin, amp * wave_(offset)))
    body = _body()
    pectoral = _swayed(PECTORAL, 14.0 * wave_(0.5))

    img = canvas(32)
    px = img.load()
    material = {}
    for (x, y), ch in fins.items():
        if 0 <= x < 32 and 0 <= y < 32:
            px[x, y] = rgba(PAL[ch])[:3] + (ALPHA[ch],)
            material[(x, y)] = "fin"
    for (x, y), ch in body.items():
        px[x, y] = rgba(PAL[ch])
        material[(x, y)] = "body"

    # The glint rides on the back only: shine a copy of those pixels, then paste them back.
    back = canvas(32)
    bpx = back.load()
    for (x, y), ch in body.items():
        if ch in BACK:
            bpx[x, y] = px[x, y]
    back = shine(back, _glint_phase(t), colour=GLINT, width=GLINT_WIDTH, strength=0.45, angle=30, pause=0.3)
    bpx = back.load()
    for (x, y), ch in body.items():
        if ch in BACK:
            px[x, y] = bpx[x, y]

    # Translucent pectoral fin over the flank: the silver shows through it.
    for (x, y), ch in pectoral.items():
        if (x, y) in body:
            under = px[x, y]
            c = rgba(PAL[ch])
            k = ALPHA[ch] / 255 * PECTORAL_OPACITY
            px[x, y] = tuple(round(under[i] + (c[i] - under[i]) * k) for i in range(3)) + (255,)

    return _outline(img, material)


def _outline(img: Image.Image, material: dict) -> Image.Image:
    """1 px outline around the silhouette: slate around the body, wine around the fins,
    a touch lighter on the top-left edges that face the light."""
    out = img.copy()
    dst = out.load()
    for y in range(32):
        for x in range(32):
            if (x, y) in material:
                continue
            after = (material.get((x + 1, y)), material.get((x, y + 1)))
            before = (material.get((x - 1, y)), material.get((x, y - 1)))
            kind = "body" if "body" in after + before else ("fin" if "fin" in after + before else None)
            if kind is None:
                continue
            lit = kind in after and kind not in before
            dst[x, y] = rgba(OUTLINE[kind][0 if lit else 1])
    return out


def _glint_phase(t: float) -> float:
    """Map loop time to shine()'s phase so the band sweeps just the length of the back."""
    run = 0.7  # the glint crosses during this share of the loop and rests for the rest
    if t >= run:
        return 0.99
    # shine() (angle 30, 32 px image) sweeps its centre from -9 to about 52; the back spans
    # about 6 -> 29 along that direction.
    lo = -2 * GLINT_WIDTH
    hi = 32 * math.cos(math.radians(30)) + 32 * math.sin(math.radians(30)) + 2 * GLINT_WIDTH
    target = 5.0 + (29.0 - 5.0) * (t / run)
    return min(0.699, max(0.0, (target - lo) / (hi - lo) * 0.7))


def textures() -> None:
    save_animation(animate(_frame, 8), "fish", frametime=3)


def models() -> dict:
    return {"main": sprite("fish")}
