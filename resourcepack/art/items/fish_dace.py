"""Dace: a common silver river fish from the JoshyMC fishing collection (rarity COMMON).

A flat 32x32 sprite in the collection's shared pose: side view, head up-left, tail
down-right along a 2:1 pixel diagonal. Slender cyprinid anatomy: a narrow head with a
small terminal mouth and the dace's yellow eye, an olive back with a lit ridge, a steel
sheen over bright silver flanks set with large scale plates and a dotted lateral line, a
faint rosy belly, a concave dorsal fin mid-back over the pelvics, pale amber lower fins
and a deeply forked grey tail. Keeps the old icon's scheme (grey-olive, silver-sage, rose
tints, yellow accents). Animated (8 frames x 3 ticks): the tail flexes and the fins tick
about a pixel while a soft glint slides along the back.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, shine, sprite

ID = "fish_dace"
NAME = "Dace"
KIND = "item"
MODEL_KEY = "fish/dace"
COUNTERPART = "item/cod"

S = 32
FRAMES = 8
FRAMETIME = 3

# --------------------------------------------------------------------------------------
# Palette (darkest -> lightest, hue-shifted: shadows lean teal-blue, lights lean cream)
# --------------------------------------------------------------------------------------

OLIVE = ["#1d2627", "#2c3a37", "#3f5041", "#5a6a4f", "#7d8766"]          # back
SILVER = ["#56606a", "#7f8890", "#a6aba8", "#c8cac0", "#e3e2d6", "#f8f6ea"]  # flank + belly
STEEL = "#8a979a"                                                         # sheen between back and flank
ROSE = ["#bca6a8", "#dac7c4", "#ecdfd9"]                                   # faint rosy sheen
GOLD = ["#7a5a18", "#c9a23a", "#ecd060", "#fff0a0"]                        # the yellow iris
PUPIL = "#141722"
CATCHLIGHT = "#ffffff"
# Fin membranes: k root, m membrane, n ray, h lit edge; plus the rim colour.
FIN_GREY = {"k": "#6d7866", "m": "#9aa293", "n": "#5d6a5a", "h": "#c3c9b8", "edge": "#29322f"}    # dorsal, tail
FIN_AMBER = {"k": "#d9b87c", "m": "#ecd7a8", "n": "#c9a56a", "h": "#f7ecd0", "edge": "#8e7248"}   # lower fins
FIN_ALPHA = 210
EDGE_ALPHA = 240

# Letters of the hand-painted body below (4 lit ridge, 2-3 back, g steel, f-a silver light to
# dark, t-r rose, P/W pupil and catchlight, Y-Z gold iris).
INK = {
    "1": OLIVE[1], "2": OLIVE[2], "3": OLIVE[3], "4": OLIVE[4], "g": STEEL,
    "a": SILVER[0], "b": SILVER[1], "c": SILVER[2], "d": SILVER[3], "e": SILVER[4], "f": SILVER[5],
    "r": ROSE[0], "s": ROSE[1], "t": ROSE[2],
    "P": PUPIL, "W": CATCHLIGHT, "Z": GOLD[0], "z": GOLD[1], "y": GOLD[2], "Y": GOLD[3],
}

# --------------------------------------------------------------------------------------
# Shape, in body pixels (x right, y down; SHIFT places it in the frame). The body is
# hand-painted and never moves. The tail and dorsal fin are polygons that sway about a hinge
# on the body; the small fins are pixel sprites that step between poses.
# --------------------------------------------------------------------------------------

BODY = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    "................................",  # 6
    "......4444......................",  # 7
    "....443322443...................",  # 8
    "...4WPYgg222233.................",  # 9
    "..3ePPyeeggg22233...............",  # 10
    "...aezffcffeggg2233.............",  # 11
    "....ceesceedeffgg223............",  # 12
    ".....ccctdceeeeeegg23...........",  # 13
    ".......cctttcdcdefeg23..........",  # 14
    ".........ccctttecdceg23.........",  # 15
    "............ccctteedcd2.........",  # 16
    "...............cccceedc.........",  # 17
    "...................bbbb.........",  # 18
    "................................",  # 19
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

# Big fins: (polygon, rays, hinge, sway degrees, phase, membrane), swung about the hinge.
FINS = {
    "caudal": ([(22.4, 15.0), (24.3, 14.5), (26.3, 14.1), (28.4, 13.8), (27.1, 15.1), (26.0, 16.3),
                (25.2, 17.4), (25.6, 18.6), (26.2, 19.9), (26.9, 21.5), (25.6, 20.7), (24.2, 19.7),
                (22.9, 19.0), (22.4, 18.8)],
               [((23.0, 15.6), (27.4, 14.4)), ((23.2, 16.4), (25.8, 16.0)),
                ((23.2, 17.8), (25.4, 19.4)), ((23.0, 18.4), (26.2, 20.8))],
               (22.5, 17.0), 12.0, 0.0, FIN_GREY),
    "dorsal": ([(14.3, 9.7), (15.3, 7.9), (16.4, 6.0), (17.1, 7.4), (17.9, 8.7), (19.0, 9.7), (20.4, 10.6),
                (19.8, 12.4), (16.8, 11.2)],
               [((15.4, 9.8), (16.4, 6.8)), ((16.6, 10.4), (17.5, 8.4)), ((17.9, 11.1), (18.9, 10.0))],
               (15.0, 10.2), 4.0, -1.1, FIN_GREY),
}
# Small fins as pixel sprites per pose (body coordinates, letters as FIN_* keys):
# "rest" plus "a" / "b" for the two ends of the sway.
SMALL_FINS = {
    "pelvic": ({"rest": "11,16k 12,17m 13,17m 14,18h",
                "a": "11,16k 12,17m 13,17m 13,18h",
                "b": "11,16k 12,17m 13,17m 14,17h"}, -1.6, FIN_AMBER, False),
    "anal": ({"rest": "15,18k 16,18k 17,18m 18,18h 16,19h",
              "a": "15,18k 16,18k 17,18m 18,18h 17,19h",
              "b": "15,18k 16,18k 17,18m 18,18h 15,19h"}, -0.7, FIN_AMBER, False),
    "pectoral": ({"rest": "9,13k 10,13m 10,14m 11,14h",
                  "a": "9,13k 10,13m 11,13h 10,14m",
                  "b": "9,13k 9,14m 10,14m 11,14h"}, 0.9, FIN_AMBER, True),
}
BEND = {"caudal": 2.6}   # the tail flexes: its root and fork notch stay put, the lobe tips travel
SHIFT = (1, 2)   # where the drawing sits in the frame


def _rotate(points, hinge, degrees, bend: float = 0.0):
    """Rotate points about the hinge. With bend > 0 the fin flexes instead: points closer
    than bend px to the hinge stay put and the turn grows toward the tips."""
    hx, hy = hinge
    out = []
    reach = max(math.hypot(x - hx, y - hy) for x, y in points) or 1.0
    for x, y in points:
        r = math.hypot(x - hx, y - hy)
        k = 1.0 if bend <= 0 else max(0.0, min(1.0, (r - bend) / max(1e-6, reach - bend)))
        a = math.radians(degrees * k)
        c, s = math.cos(a), math.sin(a)
        out.append((hx + (x - hx) * c - (y - hy) * s, hy + (x - hx) * s + (y - hy) * c))
    return out


def _inside(poly, x, y) -> bool:
    hit = False
    n = len(poly)
    for i in range(n):
        x0, y0 = poly[i]
        x1, y1 = poly[(i + 1) % n]
        if (y0 > y) != (y1 > y):
            if x < x0 + (y - y0) * (x1 - x0) / (y1 - y0):
                hit = not hit
    return hit


def _line_pixels(p0, p1):
    """Pixels of a 1 px line between two points (DDA)."""
    (x0, y0), (x1, y1) = p0, p1
    steps = max(1, int(math.ceil(max(abs(x1 - x0), abs(y1 - y0)) * 1.5)))
    out = []
    for k in range(steps + 1):
        x = x0 + (x1 - x0) * k / steps
        y = y0 + (y1 - y0) * k / steps
        p = (int(math.floor(x)), int(math.floor(y)))
        if not out or out[-1] != p:
            out.append(p)
    return out


def _moved(points):
    return [(x + SHIFT[0], y + SHIFT[1]) for x, y in points]


# --------------------------------------------------------------------------------------
# Painting
# --------------------------------------------------------------------------------------

def _fins(t: float) -> dict:
    """Fin shapes for loop phase t: the big fins swing about their hinges, the small fins
    step between their poses."""
    out = {}
    for name, (poly, rays, hinge, sway, phase, membrane) in FINS.items():
        a = sway * math.sin(2 * math.pi * t + phase)
        poly, hinge = _moved(poly), _moved([hinge])[0]
        rays = [tuple(_moved(r)) for r in rays]
        bend = BEND.get(name, 0.0)
        reach = max(math.hypot(x - hinge[0], y - hinge[1]) for x, y in poly)
        flex = lambda pts: _rotate(pts + [(hinge[0] + reach, hinge[1])], hinge, a, bend)[:-1]
        out[name] = {"poly": flex(poly), "rays": [tuple(flex(list(r))) for r in rays],
                     "ink": membrane, "hinge": hinge,
                     "reach": max(math.hypot(x - hinge[0], y - hinge[1]) for x, y in poly)}
    for name, (poses, phase, membrane, on_top) in SMALL_FINS.items():
        w = math.sin(2 * math.pi * t + phase)
        pose = poses["a"] if w > 0.5 else (poses["b"] if w < -0.5 else poses["rest"])
        pixels = {}
        for item in pose.split():
            xy, letter = item[:-1], item[-1]
            x, y = (int(v) for v in xy.split(","))
            pixels[(x + SHIFT[0], y + SHIFT[1])] = letter
        out[name] = {"pixels": pixels, "ink": membrane, "on_top": on_top}
    return out


def _body_ink(x, y):
    bx, by = x - SHIFT[0], y - SHIFT[1]
    if 0 <= bx < S and 0 <= by < S and BODY[by][bx] != ".":
        return BODY[by][bx]
    return None


def _labels(fins: dict):
    """Body from the painting; fins behind it (polygons by 4x4 supersampled coverage)."""
    labels = [[("body" if _body_ink(x, y) else None) for x in range(S)] for y in range(S)]
    for name, f in fins.items():
        if "pixels" in f and not f["on_top"]:
            for (x, y) in f["pixels"]:
                if 0 <= x < S and 0 <= y < S and not labels[y][x]:
                    labels[y][x] = name
    big = [n for n, f in fins.items() if "poly" in f]
    for y in range(S):
        for x in range(S):
            if labels[y][x]:
                continue
            cover = dict.fromkeys(big, 0)
            for j in range(4):
                for i in range(4):
                    px, py = x + (i + 0.5) / 4, y + (j + 0.5) / 4
                    for name in big:
                        if _inside(fins[name]["poly"], px, py):
                            cover[name] += 1
                            break
            best = max(big, key=lambda k: cover[k])
            if cover[best] >= 8:
                labels[y][x] = best
    return labels


def _put(img, x, y, colour, alpha=None):
    if 0 <= x < S and 0 <= y < S:
        c = rgba(colour)
        img.putpixel((x, y), (c[0], c[1], c[2], alpha if alpha is not None else c[3]))


def _tint(img, x, y, colour, k):
    """Blend a pixel toward colour by k, keeping its alpha."""
    r, g, b, a = img.getpixel((x, y))
    _put(img, x, y, mix((r, g, b), colour, k), a)


def _paint_body(img) -> None:
    for y in range(S):
        for x in range(S):
            ink = _body_ink(x, y)
            if ink:
                _put(img, x, y, INK[ink])


def _paint_fins(img, labels, fins) -> None:
    """Fins behind the body: darker at the root, translucent membrane, darker rays."""
    for y in range(S):
        for x in range(S):
            name = labels[y][x]
            if name in (None, "body"):
                continue
            f = fins[name]
            if "pixels" in f:
                _put(img, x, y, f["ink"][f["pixels"][(x, y)]], FIN_ALPHA)
                continue
            r = math.hypot(x + 0.5 - f["hinge"][0], y + 0.5 - f["hinge"][1]) / f["reach"]
            _put(img, x, y, f["ink"]["k"] if r < 0.35 else f["ink"]["m"], FIN_ALPHA)
    for name, f in fins.items():
        for k, (p0, p1) in enumerate(f.get("rays", ())):
            ink = f["ink"]["h"] if k == 0 else f["ink"]["n"]   # the leading ray catches the light
            for (x, y) in _line_pixels(p0, p1):
                if 0 <= x < S and 0 <= y < S and labels[y][x] == name:
                    _put(img, x, y, ink, FIN_ALPHA)


def _paint_pectoral(img, labels, fins) -> None:
    """The near pectoral fin lies over the flank: pale amber glazed onto the silver."""
    f = fins["pectoral"]
    for (x, y), letter in f["pixels"].items():
        if labels[y][x] == "body":
            _tint(img, x, y, f["ink"][letter], 0.72)


def _paint_outline(img, labels, fins) -> None:
    """A 1 px outline around the whole silhouette: a touch lighter on the lit top-left."""
    for y in range(S):
        for x in range(S):
            if labels[y][x]:
                continue
            near = {}
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if 0 <= x + dx < S and 0 <= y + dy < S and labels[y + dy][x + dx]:
                    near[(dx, dy)] = labels[y + dy][x + dx]
            if not near:
                continue
            lit = (1, 0) in near or (0, 1) in near
            if "body" in near.values():
                _put(img, x, y, OLIVE[1] if lit else OLIVE[0])
            else:
                name = next(iter(near.values()))
                _put(img, x, y, fins[name]["ink"]["edge"], EDGE_ALPHA)


EYE_INKS = set("PWZzyY")   # the eye keeps its own light
GLINT_FRAMES = 6            # frames the glint takes to run along the back; it rests for the rest
GLINT_PATH = (9.0, 3.6)     # start and step of the band centre, measured along the body axis
GLINT_WEIGHT = (1.0, 0.8, 0.45, 0.2)   # by row below the back edge
AXIS = math.atan2(1, 2)     # the body axis drops 1 px per 2 px


def _glint(img, t: float, labels):
    """A soft glint slides along the back: kit.shine() driven so its band crosses the back
    in even steps, kept to the top rows of the body."""
    k = t * FRAMES
    if k >= GLINT_FRAMES:
        return img
    width = 3.0
    dx, dy = math.cos(AXIS), math.sin(AXIS)
    lo, hi = 0.0, S * dx + S * dy
    centre = GLINT_PATH[0] + GLINT_PATH[1] * k
    phase = (centre - lo + width * 2) / (hi - lo + width * 4)
    lit = shine(img, phase, colour="#fff8e2", width=width, strength=0.55, angle=math.degrees(AXIS), pause=0.0)
    out = img.copy()
    for x in range(S):
        ys = [y for y in range(S) if labels[y][x] == "body"]
        for y in ys:
            i = y - ys[0]
            if i < len(GLINT_WEIGHT) and _body_ink(x, y) not in EYE_INKS:
                a, b = img.getpixel((x, y)), lit.getpixel((x, y))
                w = GLINT_WEIGHT[i]
                out.putpixel((x, y), tuple(round(a[c] + (b[c] - a[c]) * w) for c in range(3)) + (a[3],))
    return out


def _paint(t: float):
    fins = _fins(t)
    labels = _labels(fins)
    img = canvas(S)
    _paint_body(img)
    _paint_fins(img, labels, fins)
    _paint_pectoral(img, labels, fins)
    _paint_outline(img, labels, fins)
    return _glint(img, t, labels)


def frames():
    return animate(_paint, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
