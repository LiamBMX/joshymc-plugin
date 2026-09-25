"""Void Drill [5x5]: the heavy, upgraded void drill that bores a 5x5 hole.

A netherite-black motor housing under a wide gearbox painted with magenta hazard
chevrons (a glowing gem where they meet, a 5X5 plate on each end, heat-blued exhaust
stacks raked out of the back) driving a crown of three stepped, spiral-fluted bits: a
big centre bit in a jawed chrome chuck and two counter-rotating outriggers on tilted
shoulder mounts, splayed out, forward and back, each tipped with a twinkling void spark.
Twin void cores swirl in glass capsules on the flanks, crackling with magenta lightning;
louvred vents glow with heat front and back under a 5x5 status matrix that charges row
by row, power conduits pulse up to the head, and the reinforced handle has a ribbed
rubber grip with steel splints and bands, chasing light rings and a diamond spike pommel.

Static faces are painted into per-face atlases at 2 texels per model unit, so bevels,
rivets and stripes land exactly on each part. Three animated textures carry the motion:
"bit" (the scrolling spiral flutes), "core" (the swirling void and its lightning) and
"fx" (vent and exhaust heat, the matrix, conduit pulses, chasing rings, tip sparks).
The inventory draws the same sculpt as the "gui" model, which samples icon versions of
every texture (one clean value per face, bolder spirals, brighter cores, a pulsing
screen) because fine texel detail aliases into noise at slot size.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, box, canvas, copy, display, mirror, model, place, prism, rgba, rotation_of, save,
                     save_animation, turn)

ID = "void_drill_5x5"
NAME = "Void Drill [5x5]"
KIND = "pickaxe"
COUNTERPART = "item/netherite_pickaxe"

# ------------------------------------------------------------------------------------------
# Palette: hand-tuned hue-shifted ramps, darkest -> lightest
# ------------------------------------------------------------------------------------------
NETH = ["#0f0b13", "#1a141e", "#261e2b", "#342a3a", "#44374b", "#57465e", "#715f79", "#927e9a"]
STEEL = ["#1c1725", "#2e283b", "#453e56", "#625a75", "#847d99", "#aca6c0", "#d8d3e8", "#ffffff"]
CHROME = ["#120e18", "#2a2433", "#4d4459", "#7a7088", "#aba2b9", "#d9d2e4", "#fbf7ff"]
RUBBER = ["#09070c", "#120f16", "#1b1621", "#261f2d", "#33293c"]
MAG = ["#2a0636", "#4c0c5c", "#7a1690", "#b02ac4", "#e544ec", "#ff6bff", "#ffa8ff", "#ffe2ff"]
VOID = ["#0a0310", "#16061f", "#250a33"]
HAZ = ["#6d1673", "#a42aab", "#d23fd9", "#f06ef2"]      # hazard paint (not glowing)
AMBER = ["#3a0d0a", "#6e180c", "#a8300e", "#e05a16", "#ff8f2a", "#ffc259", "#fff1b8"]
GLASS = "#f3d9ff"
WHITE = "#ffffff"

# ------------------------------------------------------------------------------------------
# Layout (model units; the drill runs up +Y on x = z = 8, bits at the top)
# ------------------------------------------------------------------------------------------
DENS = 2                          # texels per model unit on every atlas face
GRIP = (8.0, -7.0, 8.0)           # where the fist closes
SIZE = 0.64                       # in-hand size multiplier
L_DIR = (-0.42, 0.87, 0.26)       # baked light: from above, a little from -X and the front
SIDES = ("north", "south", "east", "west", "up", "down")

BX0, BX1 = 3.4, 12.6              # motor housing
BY0, BY1 = -1.4, 9.4
BZ0, BZ1 = 4.6, 11.4
PLATE = 0.45                      # armour plates stand this proud of the housing
CAP_X, CAP_R = 2.1, 1.85          # left void capsule (mirrored to the right)
CAP_Y0, CAP_Y1 = -0.1, 7.9        # glass tube
CORE_R = 1.35
GB0, GB1 = (0.0, 9.4, 4.3), (16.0, 12.2, 11.7)     # lower gearbox (hazard paint)
SIDE_BIT_X, SIDE_TILT = 13.3, 20.0                  # right outrigger (mirrored left)
SPLAY = 18.0                                        # outriggers also lean front / back

# Stepped bits: (y0, y1, radius) from the chuck up.
CENTRE_BIT = [(15.8, 18.0, 2.75), (18.0, 20.0, 2.5), (20.0, 21.9, 2.22), (21.9, 23.6, 1.93),
              (23.6, 25.2, 1.63), (25.2, 26.7, 1.33), (26.7, 28.0, 1.03), (28.0, 29.2, 0.75),
              (29.2, 30.3, 0.49), (30.3, 31.2, 0.26)]
SIDE_BIT = [(14.2, 16.0, 1.7), (16.0, 17.7, 1.48), (17.7, 19.3, 1.24), (19.3, 20.7, 1.0),
            (20.7, 22.0, 0.76), (22.0, 23.1, 0.52), (23.1, 23.9, 0.3)]

# ------------------------------------------------------------------------------------------
# Vector helpers
# ------------------------------------------------------------------------------------------


def _sub(a, b):
    return tuple(a[i] - b[i] for i in range(3))


def _add(a, b):
    return tuple(a[i] + b[i] for i in range(3))


def _scale(a, k):
    return tuple(v * k for v in a)


def _dot(a, b):
    return sum(a[i] * b[i] for i in range(3))


def _mul(m, v):
    return tuple(sum(m[i][k] * v[k] for k in range(3)) for i in range(3))


def _norm(v):
    n = math.sqrt(_dot(v, v)) or 1.0
    return tuple(x / n for x in v)


L_DIR = _norm(L_DIR)
KEY = _norm((-0.55, 0.35, 0.75))  # the light metal highlights look for
_NORMAL = {"down": (0, -1, 0), "up": (0, 1, 0), "north": (0, 0, -1), "south": (0, 0, 1),
           "west": (-1, 0, 0), "east": (1, 0, 0)}
_FACE_VERTS = {
    "down": lambda a, b: [(a[0], a[1], b[2]), (a[0], a[1], a[2]), (b[0], a[1], a[2]), (b[0], a[1], b[2])],
    "up": lambda a, b: [(a[0], b[1], a[2]), (a[0], b[1], b[2]), (b[0], b[1], b[2]), (b[0], b[1], a[2])],
    "north": lambda a, b: [(b[0], b[1], a[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2]), (a[0], b[1], a[2])],
    "south": lambda a, b: [(a[0], b[1], b[2]), (a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], b[1], b[2])],
    "west": lambda a, b: [(a[0], b[1], a[2]), (a[0], a[1], a[2]), (a[0], a[1], b[2]), (a[0], b[1], b[2])],
    "east": lambda a, b: [(b[0], b[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (b[0], b[1], a[2])],
}


def _face_size(side, a, b):
    dx, dy, dz = (b[i] - a[i] for i in range(3))
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
            "up": (dx, dz), "down": (dx, dz)}[side]


def _face_normal(e, side):
    m, _ = rotation_of(e)
    return _mul(m, _NORMAL[side])


# ------------------------------------------------------------------------------------------
# Part registry: elements plus how each one is painted
# ------------------------------------------------------------------------------------------

class _Parts:
    def __init__(self):
        self.items: list[dict] = []

    def add(self, elements, mat: str, **opt):
        els = elements if isinstance(elements, list) else [elements]
        for e in els:
            self.items.append({"e": e, "mat": mat, "opt": opt})
        return els

    def mark(self) -> int:
        return len(self.items)

    def mirror_since(self, start: int, axis: str = "x", about: float = 8.0, post=None) -> None:
        """Mirrored copies of every source part added since `start` (sharing painted faces);
        post = (angle, axis, origin) turns the copies afterwards."""
        for i in range(start, len(self.items)):
            if "src" not in self.items[i]:
                self.items.append({"src": i, "op": ("mirror", axis, about, post)})


def _oct(center, radius, length, **kw) -> list[dict]:
    return prism(center, radius, length, "x", sides=8, **kw)


def _no_caps(elements):
    for e in elements:
        e["faces"].pop("up", None)
        e["faces"].pop("down", None)
    return elements


def _plane(frm, to, side: str, tex: str, uv, glow: int = 0, shade: bool = True) -> dict:
    """A zero-thickness element showing one side with a fixed texture region."""
    e = box(frm, to, "x", skip=tuple(s for s in SIDES if s != side), glow=glow, shade=shade)
    e["faces"][side]["texture"] = "#" + tex
    e["faces"][side]["uv"] = [round(v, 4) for v in uv]
    return e


def _wrap(slabs, tex: str, v_top: float, v_bottom: float, u0: float = 0.0, u1: float = 16.0) -> None:
    """Wrap a texture around an upright octagonal prism: side face k (counted to the
    viewer's right from the +Z face) covers u0 + k*du .. u0 + (k+1)*du, so a pattern
    that tiles across u runs seamlessly round the prism."""
    du = (u1 - u0) / 8
    for e in slabs:
        for side, face in e["faces"].items():
            if side in ("up", "down"):
                continue
            n = _face_normal(e, side)
            phi = math.degrees(math.atan2(n[2], n[0]))
            k = int(round((90.0 - phi) / 45.0)) % 8
            face["texture"] = "#" + tex
            face["uv"] = [round(u0 + k * du, 4), round(v_top, 4), round(u0 + (k + 1) * du, 4), round(v_bottom, 4)]


# ------------------------------------------------------------------------------------------
# Geometry
# ------------------------------------------------------------------------------------------

def _pommel(P: _Parts) -> None:
    spike = box((7.05, -15.35, 7.05), (8.95, -13.45, 8.95), "x")     # a cube stood on its point
    turn(spike, 45, "z", (8, -14.4, 8))
    turn(spike, 35.26, "x", (8, -14.4, 8))
    P.add(spike, "steel", style="spike")
    P.add(_oct((8, -14.55, 8), 1.55, 0.7), "steel", round=True, style="knob")
    ring = _oct((8, -13.9, 8), 2.05, 0.6, glow=15, shade=False)
    _wrap(ring, "fx", 12, 14)
    P.add(ring, "glow", round=True)
    P.add(_oct((8, -12.7, 8), 2.35, 1.8), "neth", round=True, style="cap")


def _handle(P: _Parts) -> None:
    P.add(_oct((8, -11.4, 8), 1.95, 0.8), "steel", round=True, style="band")
    P.add(_no_caps(_oct((8, -7.0, 8), 1.7, 8.0)), "rubber", round=True)
    for ang in (45, 135, 225, 315):
        splint = box((7.72, -10.9, 9.62), (8.28, -3.62, 9.95), "x", skip=("down",))
        P.add(turn(splint, ang, "y", (8, 0, 8)), "steel", style="splint")
    for yc in (-8.9, -5.1):
        P.add(_oct((8, yc, 8), 1.9, 0.5), "steel", round=True, style="band")
    ring = _oct((8, -3.25, 8), 1.95, 0.5, glow=15, shade=False)
    _wrap(ring, "fx", 12, 14)
    P.add(ring, "glow", round=True)
    P.add(_oct((8, -2.4, 8), 2.3, 1.2), "steel", round=True, style="collar")


def _vent(P: _Parts, z: float) -> None:
    """A louvred exhaust vent standing proud of the front plate, heat glowing behind."""
    x0, x1, y0, y1, d = 4.4, 11.6, -0.4, 3.8, 0.75
    P.add(box((x0, y1 - 0.5, z), (x1, y1, z + d), "x", skip=("north",)), "steel", style="frame")
    P.add(box((x0, y0, z), (x1, y0 + 0.5, z + d), "x", skip=("north",)), "steel", style="frame")
    P.add(box((x0, y0 + 0.5, z), (x0 + 0.5, y1 - 0.5, z + d), "x", skip=("north", "up", "down")), "steel",
          style="frame")
    P.add(box((x1 - 0.5, y0 + 0.5, z), (x1, y1 - 0.5, z + d), "x", skip=("north", "up", "down")), "steel",
          style="frame")
    P.add(_plane((x0 + 0.5, y0 + 0.5, z + 0.05), (x1 - 0.5, y1 - 0.5, z + 0.05), "south", "fx",
                 (0, 0, 16, 4), glow=15, shade=False), "anim")
    gap = (y1 - y0 - 1.0) / 3
    for k in range(3):
        yc = y0 + 0.5 + (k + 0.62) * gap
        slat = box((x0 + 0.5, yc - 0.17, z + 0.12), (x1 - 0.5, yc + 0.17, z + d - 0.08), "x", skip=("east", "west"))
        P.add(turn(slat, 38, "x", (8, yc, z + d / 2)), "steel", style="slat")


def _matrix(P: _Parts, z: float) -> None:
    """The 5x5 status matrix above the vent."""
    x0, x1, y0, y1 = 5.6, 10.4, 4.5, 8.9
    P.add(box((x0, y0, z), (x1, y1, z + 0.35), "x", skip=("north",)), "steel", style="bezel")
    P.add(_plane((x0 + 0.35, y0 + 0.35, z + 0.39), (x1 - 0.35, y1 - 0.35, z + 0.39), "south", "fx",
                 (0, 4, 6, 10), glow=15, shade=False), "anim")


def _body(P: _Parts) -> None:
    P.add(box((4.4, -2.0, 5.4), (11.6, BY0, 10.6), "x", skip=("up",)), "neth", style="skirt")
    P.add(box((BX0, BY0, BZ0), (BX1, BY1, BZ1), "x", skip=("up",)), "neth", style="housing")
    s = P.mark()
    P.add(box((BX0 + 0.5, BY0 + 0.45, BZ1), (BX1 - 0.5, BY1 - 0.35, BZ1 + PLATE), "x", skip=("north",)),
          "neth", style="plate")
    _vent(P, BZ1 + PLATE)
    _matrix(P, BZ1 + PLATE)
    for x in (4.65, 10.65):
        P.add(box((x, 7.95, BZ1 + PLATE), (x + 0.7, 8.65, BZ1 + PLATE + 0.25), "x", skip=("north",)), "steel",
              style="bolt")
    for x0 in (3.98, 11.62):                            # power conduits carrying pulses up to the head
        z = BZ1 + PLATE
        P.add(box((x0 - 0.06, -0.95, z), (x0 + 0.46, 8.75, z + 0.18), "x", skip=("north",)), "steel", style="channel")
        P.add(_plane((x0 + 0.04, -0.85, z + 0.22), (x0 + 0.36, 8.65, z + 0.22), "south", "fx", (6, 4, 7, 10),
                     glow=15, shade=False), "anim")
    P.mirror_since(s, "z", 8.0)
    # steel rails either side of each capsule
    s = P.mark()
    for z0, z1 in ((BZ0 + 0.25, BZ0 + 1.15), (BZ1 - 1.15, BZ1 - 0.25)):
        P.add(box((BX0 - 0.45, BY0 + 0.4, z0), (BX0, BY1 - 0.2, z1), "x", skip=("east",)), "steel", style="rail")
    P.mirror_since(s, "x", 8.0)


def _capsule(P: _Parts) -> None:
    """The left void capsule, mirrored to the right."""
    s = P.mark()
    P.add(_oct((CAP_X, CAP_Y0 - 0.55, 8), 2.1, 1.1), "steel", round=True, style="capcap")
    P.add(_oct((CAP_X, CAP_Y1 + 0.55, 8), 2.1, 1.1), "steel", round=True, style="capcap")
    core = _no_caps(_oct((CAP_X, (CAP_Y0 + CAP_Y1) / 2, 8), CORE_R, CAP_Y1 - CAP_Y0 - 0.1, glow=15, shade=False))
    _wrap(core, "core", 0, 16)
    P.add(core, "anim")
    glass = _no_caps(_oct((CAP_X, (CAP_Y0 + CAP_Y1) / 2, 8), CAP_R, CAP_Y1 - CAP_Y0, glow=10))
    P.add(glass, "glass", round=True, translucent=True)
    P.mirror_since(s, "x", 8.0)


def _gearbox(P: _Parts) -> None:
    P.add(box(GB0, GB1, "x"), "hazard")
    P.add(box((0.4, GB1[1], 4.7), (15.6, GB1[1] + 0.4, 11.3), "x", skip=("down",)), "neth", style="lid")
    P.add(box((0.5, GB0[1] - 0.5, 4.8), (15.5, GB0[1], 11.2), "x", skip=("up",)), "neth", style="lip")
    P.add(_oct((8, 13.1, 8), 3.75, 1.0), "neth", round=True, style="turret")
    # 5X5 plates on both ends, each built on its own so the type never reads mirrored
    for x0, x1, xp, side, back in ((GB1[0], GB1[0] + 0.2, GB1[0] + 0.24, "east", "west"),
                                   (GB0[0] - 0.2, GB0[0], GB0[0] - 0.24, "west", "east")):
        P.add(box((x0, 9.55, 4.65), (x1, 12.05, 11.35), "x", skip=(back,)), "steel", style="labelframe")
        P.add(_plane((xp, 9.7, 4.8), (xp, 11.9, 11.2), side, "label", (0, 0, 16, 5.5), glow=12, shade=False),
              "anim")
    s = P.mark()
    for x in (0.7, 14.4):
        P.add(box((x, 10.35, GB1[2]), (x + 0.9, 11.25, GB1[2] + 0.3), "x", skip=("north",)), "steel", style="bolt")
    ec = (8.0, 10.8)
    plate = box((ec[0] - 1.25, ec[1] - 1.25, GB1[2]), (ec[0] + 1.25, ec[1] + 1.25, GB1[2] + 0.35), "x",
                skip=("north",))
    P.add(turn(plate, 45, "z", (ec[0], ec[1], GB1[2])), "steel", style="emblem")
    gem = box((ec[0] - 0.6, ec[1] - 0.6, GB1[2] + 0.35), (ec[0] + 0.6, ec[1] + 0.6, GB1[2] + 0.62), "x",
              skip=("north",), glow=15, shade=False)
    P.add(turn(gem, 45, "z", (ec[0], ec[1], GB1[2])), "gem")
    P.mirror_since(s, "z", 8.0)
    # exhaust stacks raked back out of the gearbox, heat glowing in their mouths
    s = P.mark()
    base = (4.3, 10.2, 5.2)
    pipe = _oct((base[0], base[1] + 2.9, base[2]), 0.78, 5.8)
    rim = _oct((base[0], base[1] + 5.55, base[2]), 1.12, 0.8)
    top = base[1] + 6.0
    mouth = _plane((base[0] - 0.66, top, base[2] - 0.66), (base[0] + 0.66, top, base[2] + 0.66),
                   "up", "fx", (0, 14, 4, 16), glow=15, shade=False)
    turn(pipe + rim + [mouth], -38, "x", base)
    P.add(pipe, "steel", round=True, style="pipe")
    P.add(rim, "steel", round=True, style="band")
    P.add(mouth, "anim")
    P.mirror_since(s, "x", 8.0)


def _bit(P: _Parts, cx: float, slices, spark: int = 0) -> list:
    """A stepped, spiral-fluted bit standing on (cx, slices[0].y0, 8); returns its elements."""
    parts = []
    v = 16.0                                              # v at the bottom edge of the slice
    for y0, y1, r in slices:
        slab = _oct((cx, (y0 + y1) / 2, 8), r, y1 - y0, glow=4)
        w = 2 * r * math.tan(math.pi / 8)
        span = min(8.0, 2.0 * (y1 - y0) / w)            # square texels: 2 uv per face width
        top = v - span
        shift = math.floor(top / 8.0) * 8.0               # the spiral repeats every 8 uv
        _wrap(slab, "bit", top - shift, v - shift)
        v = top
        parts += P.add(slab, "bitcap", round=True)
    ytip = slices[-1][1]
    tip = box((cx - 0.4, ytip - 0.1, 7.6), (cx + 0.4, ytip + 0.7, 8.4), "x", glow=15, shade=False)
    for face in tip["faces"].values():
        face["texture"] = "#fx"
        face["uv"] = [2 * spark, 10, 2 * spark + 2, 12]
    turn(tip, 45, "y", (cx, ytip, 8))
    parts += P.add(tip, "anim", spark=spark)
    return parts


def _head(P: _Parts) -> None:
    P.add(_oct((8, 14.35, 8), 3.1, 1.5), "chrome", round=True, style="chuck")
    ring = _oct((8, 15.3, 8), 2.85, 0.4, glow=15, shade=False)
    _wrap(ring, "fx", 12, 14)
    P.add(ring, "glow", round=True)
    P.add(_oct((8, 15.65, 8), 2.7, 0.3), "chrome", round=True, style="ring")
    for ang in (45, 135, 225, 315):                     # chuck jaws biting the bit
        jaw = box((7.45, 15.4, 10.35), (8.55, 17.4, 11.35), "x", skip=("down",))
        P.add(turn(jaw, ang, "y", (8, 0, 8)), "chrome", style="jaw")
    _bit(P, 8.0, CENTRE_BIT, spark=0)
    # right outrigger: a tilted shoulder mount, chuck and bit leaning out, mirrored left
    s = P.mark()
    base_y = GB1[1] + 0.4
    side = P.add(box((SIDE_BIT_X - 2.3, base_y - 2.3, 4.9), (SIDE_BIT_X + 2.3, base_y, 11.1), "x", skip=("down",)),
                 "hazard", style="mount")
    side += P.add(_oct((SIDE_BIT_X, base_y + 0.65, 8), 1.95, 1.3), "chrome", round=True, style="chuck")
    ring = _oct((SIDE_BIT_X, base_y + 1.45, 8), 1.75, 0.3, glow=15, shade=False)
    _wrap(ring, "fx", 12, 14)
    side += P.add(ring, "glow", round=True)
    side += _bit(P, SIDE_BIT_X, SIDE_BIT, spark=1)
    turn(side, -SIDE_TILT, "z", (SIDE_BIT_X, base_y, 8))
    turn(side, SPLAY, "x", (SIDE_BIT_X, base_y, 8))      # right one leans to the front...
    P.mirror_since(s, "x", 8.0, post=(-2 * SPLAY, "x", (16 - SIDE_BIT_X, base_y, 8)))   # ...left to the back


def _build() -> list[dict]:
    P = _Parts()
    _pommel(P)
    _handle(P)
    _body(P)
    _capsule(P)
    _gearbox(P)
    _head(P)
    return P.items


# ------------------------------------------------------------------------------------------
# Atlas layout: one painted region per face
# ------------------------------------------------------------------------------------------

class _Job:
    """One face's region in an atlas, with its placement in model space for painting."""

    def __init__(self, atlas, x, y, tw, th, part, side, seed):
        self.atlas, self.x, self.y, self.tw, self.th = atlas, x, y, tw, th
        self.mat, self.opt, self.side, self.seed = part["mat"], part["opt"], side, seed
        e = part["e"]
        verts = _FACE_VERTS[side](e["from"], e["to"])
        m, o = rotation_of(e)
        to_model = (lambda p: tuple(p)) if o is None else (lambda p: _add(_mul(m, _sub(p, o)), o))
        p0, p1, p3 = to_model(verts[0]), to_model(verts[1]), to_model(verts[3])
        self.o, self.du, self.dv = p0, _sub(p3, p0), _sub(p1, p0)
        self.n = _mul(m, _NORMAL[side])
        corners = (p0, p1, p3, _add(p1, self.du))
        hs = [_dot(p, L_DIR) for p in corners]
        self.hmin, self.hmax = min(hs), max(hs)
        self.rng = random.Random(seed)
        self.px = None

    def pos(self, i, j):
        fu, fv = (i + 0.5) / self.tw, (j + 0.5) / self.th
        return tuple(self.o[k] + self.du[k] * fu + self.dv[k] * fv for k in range(3))

    def t(self, i, j) -> float:
        """0 at the best-lit end of the face, 1 at the darkest."""
        span = self.hmax - self.hmin
        if span < 1e-4:
            return 0.5
        return max(0.0, min(1.0, (self.hmax - _dot(self.pos(i, j), L_DIR)) / span))

    def light(self, border: str) -> float:
        """How much a border's outward direction faces the light (-1..1)."""
        d = {"top": _scale(self.dv, -1), "bottom": self.dv, "left": _scale(self.du, -1), "right": self.du}[border]
        return _dot(_norm(d), L_DIR)

    def put(self, i, j, colour) -> None:
        i, j = int(i), int(j)
        if 0 <= i < self.tw and 0 <= j < self.th:
            self.px[self.x + i, self.y + j] = rgba(colour)

    def get(self, i, j):
        return self.px[self.x + int(i), self.y + int(j)]

    def texels(self):
        for j in range(self.th):
            for i in range(self.tw):
                yield i, j

    def box(self):
        return self.x, self.y, self.x + self.tw - 1, self.y + self.th - 1


_CACHE: dict = {}


def _layout() -> dict:
    if _CACHE:
        return _CACHE
    parts = _build()
    requests = []
    for idx, p in enumerate(parts):
        if "src" in p:
            continue
        e = p["e"]
        for side, face in e["faces"].items():
            if face["texture"] != "#x":
                continue
            w, h = _face_size(side, e["from"], e["to"])
            requests.append((max(1, round(h * DENS)), max(1, round(w * DENS)), idx, side))
    requests.sort(key=lambda r: (-r[0], -r[1], r[2], SIDES.index(r[3])))
    sheets, cur, x, y, row = [], [], 0, 0, 0
    for th, tw, idx, side in requests:
        w, h = tw + 2, th + 2
        if x + w > 128:
            x, y, row = 0, y + row, 0
        if y + h > 128:
            sheets.append(cur)
            cur, x, y, row = [], 0, 0, 0
        cur.append((x, y, tw, th, idx, side))
        x += w
        row = max(row, h)
    sheets.append(cur)
    atlases: dict[str, int] = {}
    jobs: list[_Job] = []
    for n, placed in enumerate(sheets):
        name = "atlas" if n == 0 else f"atlas{n + 1}"
        used = max(max(p[0] + p[2] + 2 for p in placed), max(p[1] + p[3] + 2 for p in placed))
        size = next(s for s in (16, 32, 64, 128) if s >= used)
        atlases[name] = size
        k = 16 / size
        for x0, y0, tw, th, idx, side in placed:
            face = parts[idx]["e"]["faces"][side]
            face["uv"] = [round((x0 + 1) * k, 5), round((y0 + 1) * k, 5),
                          round((x0 + 1 + tw) * k, 5), round((y0 + 1 + th) * k, 5)]
            face["texture"] = "#" + name
            face.pop("rotation", None)
            jobs.append(_Job(name, x0 + 1, y0 + 1, tw, th, parts[idx], side, idx * 17 + SIDES.index(side)))
    elements, clear = [], []
    for p in parts:
        if "src" in p:
            src = parts[p["src"]]
            op = p["op"]
            p["e"] = mirror(src["e"], op[1], op[2])[0]
            if op[3]:
                turn(p["e"], *op[3])
            p["opt"] = src["opt"]
            if "spark" in src["opt"]:
                k = src["opt"]["spark"] + 1
                for face in p["e"]["faces"].values():
                    face["uv"] = [2 * k, 10, 2 * k + 2, 12]
        (clear if p.get("opt", {}).get("translucent") else elements).append(p["e"])
    _CACHE.update(parts=parts, elements=elements + clear, jobs=jobs, atlases=atlases)
    return _CACHE


# ------------------------------------------------------------------------------------------
# Painters
# ------------------------------------------------------------------------------------------

def _band(stops, t):
    return stops[min(len(stops) - 1, int(t * len(stops)))]


def _flat(j: _Job, colour) -> None:
    for i, jj in j.texels():
        j.put(i, jj, colour)


def _bevel(j: _Job, light, dark, mid=None, width: int = 1) -> None:
    """Rim the face: borders facing the light get `light`, the far ones `dark`."""
    for border in ("top", "bottom", "left", "right"):
        f = j.light(border)
        colour = light if f > 0.25 else dark if f < -0.25 else mid
        if colour is None:
            continue
        for w in range(width):
            if border == "top":
                for i in range(j.tw):
                    j.put(i, w, colour)
            elif border == "bottom":
                for i in range(j.tw):
                    j.put(i, j.th - 1 - w, colour)
            elif border == "left":
                for jj in range(j.th):
                    j.put(w, jj, colour)
            else:
                for jj in range(j.th):
                    j.put(j.tw - 1 - w, jj, colour)


def _rivet(j: _Job, x, y, ramp_) -> None:
    j.put(x, y, ramp_[-1])
    j.put(x + 1, y, ramp_[-3])
    j.put(x, y + 1, ramp_[-3])
    j.put(x + 1, y + 1, ramp_[1])


def _p_neth(j: _Job) -> None:
    style = j.opt.get("style", "plate")
    if j.opt.get("round") and j.side in ("up", "down"):
        _flat(j, NETH[4] if j.side == "up" else NETH[1])
        return
    rng = j.rng
    up = j.n[1]
    if up > 0.7:
        stops = [NETH[5], NETH[4], NETH[4], NETH[4]]
    elif up < -0.7:
        stops = [NETH[2], NETH[1], NETH[1]]
    else:
        stops = [NETH[4], NETH[3], NETH[3], NETH[3], NETH[3], NETH[2]]
    for i, jj in j.texels():
        j.put(i, jj, _band(stops, j.t(i, jj)))
    # netherite mottling: soft clusters a step lighter or darker
    for _ in range(max(1, j.tw * j.th // 30)):
        x, y = rng.randrange(j.tw), rng.randrange(j.th)
        c = NETH[4] if rng.random() < 0.45 else NETH[2]
        for dx, dy in ((0, 0), (1, 0), (0, 1)):
            if rng.random() < 0.7:
                j.put(x + dx, y + dy, c)
    # diagonal sheen across the big armour plates
    if style == "plate" and abs(j.n[2]) > 0.9:
        for i, jj in j.texels():
            s = i - jj + j.th // 2
            if 3 <= s <= 4:
                j.put(i, jj, NETH[5])
            elif s == 6:
                j.put(i, jj, NETH[4])
    for _ in range(max(0, j.tw * j.th // 90)):           # scratches
        x, y = rng.randrange(1, max(2, j.tw - 2)), rng.randrange(1, max(2, j.th - 2))
        for k in range(rng.randint(2, 3)):
            j.put(x + k, y - k, NETH[6])
    _bevel(j, NETH[6], NETH[0], NETH[2])
    if style in ("plate", "housing", "turret") and j.tw >= 8 and j.th >= 6 and abs(j.n[1]) < 0.5:
        for x, y in ((1, 1), (j.tw - 3, 1), (1, j.th - 3), (j.tw - 3, j.th - 3)):
            _rivet(j, x, y, STEEL)


def _p_steel(j: _Job) -> None:
    style = j.opt.get("style", "band")
    if j.opt.get("round") and j.side in ("up", "down"):
        _flat(j, STEEL[4] if j.side == "up" else STEEL[1])
        return
    if style == "bolt":
        facing = abs(j.n[2]) > 0.9
        for i, jj in j.texels():
            if not facing:
                colour = STEEL[2]
            elif (i, jj) == (0, 0):
                colour = STEEL[6]
            elif (i, jj) == (j.tw - 1, j.th - 1):
                colour = STEEL[1]
            else:
                colour = STEEL[4] if i == 0 or jj == 0 else STEEL[3]
            j.put(i, jj, colour)
        return
    f = _dot(j.n, KEY)
    b = 2 if f < -0.3 else 3 if f < 0.3 else 4
    if j.n[1] > 0.7:
        b = 5
    elif j.n[1] < -0.7:
        b = 1
    stops = [STEEL[b + 1], STEEL[b], STEEL[b], STEEL[b - 1]]
    for i, jj in j.texels():
        j.put(i, jj, _band(stops, j.t(i, jj)))
    if j.opt.get("round") and f > 0.5 and j.tw >= 2:
        for jj in range(j.th):
            j.put(0, jj, STEEL[6])
    _bevel(j, STEEL[min(7, b + 2)], STEEL[max(0, b - 2)], None)
    if style == "pipe":                                  # heat-blued steel toward the hot end
        for i, jj in j.texels():
            h = (j.pos(i, jj)[1] - 11.5) / 3.4
            if h > 0.35:
                j.put(i, jj, "#5d4d78" if h < 0.6 else "#4f408c" if h < 0.8 else "#3b4596")
    if style == "rail" and j.th >= 8:
        for y in range(2, j.th - 2, 5):
            _rivet(j, max(0, j.tw // 2 - 1), y, STEEL)
    if style == "emblem" and abs(j.n[2]) > 0.9:
        for i, jj in j.texels():
            if min(i, jj, j.tw - 1 - i, j.th - 1 - jj) == 1:
                j.put(i, jj, STEEL[2])
        for x, y in ((0, 0), (j.tw - 1, 0), (0, j.th - 1), (j.tw - 1, j.th - 1)):
            j.put(x, y, STEEL[6])
    if style == "channel" and abs(j.n[2]) > 0.9:
        _flat(j, STEEL[1])
        _bevel(j, STEEL[4], STEEL[0], STEEL[2])


def _p_chrome(j: _Job) -> None:
    style = j.opt.get("style", "chuck")
    if j.opt.get("round") and j.side in ("up", "down"):
        _flat(j, CHROME[4] if j.side == "up" else CHROME[1])
        return
    f = _dot(j.n, KEY)
    lift = 1 if f > 0.4 else 0 if f > -0.3 else -1
    rows = [5, 4, 2, 1, 2, 3, 4, 3, 2] if style == "chuck" else [4, 2, 3]
    for i, jj in j.texels():
        k = rows[min(len(rows) - 1, int(jj * len(rows) / j.th))] + lift
        c = CHROME[max(0, min(6, k))]
        if style == "chuck" and i % 3 == 2 and jj > 1:
            c = CHROME[max(0, min(6, k - 1))]
        j.put(i, jj, c)
    if f > 0.6:
        j.put(0, 0, WHITE)


def _p_rubber(j: _Job) -> None:
    f = _dot(j.n, KEY)
    lift = 1 if f > 0.4 else 0 if f > -0.3 else -1
    for i, jj in j.texels():
        y = j.pos(i, jj)[1]
        rib = int(math.floor(y * DENS)) % 3
        k = (3 if rib == 0 else 1 if rib == 2 else 2) + lift
        j.put(i, jj, RUBBER[max(0, min(4, k))])
    if f > 0.6:
        for jj in range(j.th):
            if int(math.floor(j.pos(0, jj)[1] * DENS)) % 3 == 0:
                j.put(0, jj, STEEL[3])


def _p_hazard(j: _Job) -> None:
    """Magenta and black hazard paint: chevrons pointing up on the front and back,
    diagonal stripes on the ends and top."""
    if j.opt.get("style") == "mount" and j.n[1] > 0.5:
        _flat(j, NETH[3])
        _bevel(j, NETH[6], NETH[1], NETH[2])
        return
    period = 3.2
    right = _norm(j.du)
    for i, jj in j.texels():
        p = j.pos(i, jj)
        if abs(j.n[2]) > 0.9:
            s = (abs(p[0] - 8.0) + p[1]) / period
        elif abs(j.n[0]) > 0.9:
            s = (_dot(p, right) + p[1]) / period
        else:
            s = (p[0] + p[2]) / period
        frac = s - math.floor(s)
        if frac < 0.52:
            c = HAZ[3] if frac < 0.1 else HAZ[2] if frac < 0.44 else HAZ[1]
        else:
            c = NETH[1] if frac < 0.93 else NETH[3]
        j.put(i, jj, c)
    if abs(j.n[1]) < 0.5:
        _bevel(j, NETH[6], NETH[0], None)


def _p_glass(j: _Job) -> None:
    f = _dot(j.n, _norm((-0.5, 0.0, 0.85)))
    base = rgba(GLASS)
    for i, jj in j.texels():
        a = 150 if jj <= 0 or jj >= j.th - 1 else 34
        j.put(i, jj, (base[0], base[1], base[2], a))
    if f > 0.75:                                         # one crisp glint, broken near the ends
        for jj in range(2, j.th - 2):
            j.put(0, jj, (255, 255, 255, 175 if 3 <= jj < j.th - 4 else 90))
    elif f > 0.2:
        for jj in range(3, j.th - 3):
            if jj % 5 != 0:
                j.put(j.tw - 1, jj, (255, 230, 255, 80))


def _p_glow(j: _Job) -> None:
    if j.side in ("up", "down"):
        _flat(j, MAG[5])
        return
    for i, jj in j.texels():
        j.put(i, jj, MAG[6] if jj == j.th // 2 else MAG[5])


def _p_gem(j: _Job) -> None:
    """A cut magenta gem: white-hot centre, bright upper facets, deep lower ones."""
    if abs(j.n[2]) < 0.9:
        _flat(j, MAG[3])
        return
    cx, cy = (j.tw - 1) / 2, (j.th - 1) / 2
    for i, jj in j.texels():
        up = j.pos(i, jj)[1] - 10.8
        r = max(abs(i - cx), abs(jj - cy)) / max(cx, 1)
        c = WHITE if r < 0.3 else MAG[6] if up > 0.1 else MAG[4] if r < 0.75 else MAG[3]
        j.put(i, jj, c)


def _p_bitcap(j: _Job) -> None:
    _flat(j, CHROME[3] if j.side == "up" else CHROME[1])


PAINT = {"neth": _p_neth, "steel": _p_steel, "chrome": _p_chrome, "rubber": _p_rubber, "hazard": _p_hazard,
         "glass": _p_glass, "glow": _p_glow, "bitcap": _p_bitcap, "gem": _p_gem}


# The inventory icon draws this sculpt at about 0.7 screen pixels per model unit, where
# fine texel detail aliases into noise. The "gui" model samples these icon textures
# instead: one clean value per face, lit from the top-left like vanilla sprites.

def _g_value(j: _Job, ramp_, front: int, side: int, top: int, bottom: int):
    n = j.n
    if n[1] > 0.7:
        return ramp_[top]
    if n[1] < -0.7:
        return ramp_[bottom]
    if abs(n[2]) > 0.7 or _dot(n, KEY) > 0.5:
        return ramp_[front]
    return ramp_[side]


def _g_hazard(j: _Job) -> None:
    if (j.opt.get("style") == "mount" and j.n[1] > 0.5) or abs(j.n[1]) > 0.7:
        _flat(j, NETH[4])
        return
    right = _norm(j.du)
    for i, jj in j.texels():
        p = j.pos(i, jj)
        s = (abs(p[0] - 8.0) + p[1]) / 5.0 if abs(j.n[2]) > 0.9 else (_dot(p, right) + p[1]) / 5.0
        j.put(i, jj, HAZ[2] if s - math.floor(s) < 0.6 else NETH[1])


def _g_neth(j: _Job) -> None:
    _flat(j, _g_value(j, NETH, 4, 3, 5, 2))
    if j.tw >= 10 and j.th >= 10 and abs(j.n[1]) < 0.7:     # big faces keep a bold bevel
        _bevel(j, NETH[6], NETH[2], None, width=2)


def _g_steel(j: _Job) -> None:
    if j.opt.get("style") == "slat":                  # louvres read as one block of heat in a slot
        _flat(j, AMBER[3] if j.n[1] > 0 else AMBER[2])
        return
    _flat(j, _g_value(j, STEEL, 5, 3, 5, 2))


GUI_PAINT = {
    "neth": _g_neth,
    "steel": _g_steel,
    "chrome": lambda j: _flat(j, _g_value(j, CHROME, 5, 3, 5, 2)),
    "rubber": lambda j: _flat(j, _g_value(j, RUBBER, 4, 2, 3, 1)),
    "hazard": _g_hazard,
    "glass": lambda j: _flat(j, (0, 0, 0, 0)),          # clear, so the core glows undiluted
    "glow": lambda j: _flat(j, MAG[5]),
    "bitcap": lambda j: _flat(j, CHROME[4] if j.side == "up" else CHROME[2]),
    "gem": lambda j: _flat(j, MAG[6]),
}


def _bleed(img, j: _Job) -> None:
    """Copy each region's border one texel outward so edges never sample a neighbour."""
    px = img.load()
    w, h = img.size
    x0, y0, x1, y1 = j.box()
    for x in range(x0, x1 + 1):
        if y0 > 0:
            px[x, y0 - 1] = px[x, y0]
        if y1 + 1 < h:
            px[x, y1 + 1] = px[x, y1]
    for y in range(max(0, y0 - 1), min(h - 1, y1 + 1) + 1):
        if x0 > 0:
            px[x0 - 1, y] = px[x0, y]
        if x1 + 1 < w:
            px[x1 + 1, y] = px[x1, y]


# ------------------------------------------------------------------------------------------
# Animated textures
# ------------------------------------------------------------------------------------------

def _bit_colour(ph: float) -> str:
    """One flute period of the spiral, 0..1 across the band: a bright cutting edge, the
    gunmetal land falling into shadow, then the deep flute with a magenta glint. Every
    band is two texels of the diagonal wide (1/8 of the period), so each reads as a
    solid 45-degree line and the 2-texel animation step keeps them aligned."""
    bands = [WHITE, CHROME[5], CHROME[4], CHROME[3], CHROME[1], VOID[1], MAG[4], VOID[2]]
    return bands[min(7, int(ph * 8))]


def _bit_frame(t: float):
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            px[x, y] = rgba(_bit_colour(((x + y + 1) / 16.0 + t) % 1.0))
    return img


def _bit_frame_gui(t: float):
    """The icon's spiral: three bold bands (bright land, shadow, magenta flute)."""
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            ph = ((x + y + 1) / 16.0 + t) % 1.0
            px[x, y] = rgba(CHROME[5] if ph < 0.5 else CHROME[3] if ph < 0.625 else MAG[1])
    return img


_CORE_GUI = {VOID[0]: MAG[2], VOID[2]: MAG[3], MAG[1]: MAG[3], MAG[2]: MAG[4], MAG[3]: MAG[4], MAG[4]: MAG[5],
             MAG[5]: MAG[6]}


def _brighten(img, table):
    px = img.load()
    lut = {rgba(k): rgba(v) for k, v in table.items()}
    for y in range(img.height):
        for x in range(img.width):
            px[x, y] = lut.get(px[x, y], px[x, y])
    return img


def _core_frame(index: int, frames: int = 16):
    """The void core unrolled: 32 texels round the column, 32 along it (top row = top).
    Two magenta arms spiral round a dark void and turn once per loop; lightning bolts
    crackle along the column and fade over two frames."""
    t = index / frames
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            a = x / 32.0
            h = y / 32.0
            wob = 0.06 * math.sin(2 * math.pi * (h * 2 + t))
            s = 0.5 + 0.5 * math.sin(2 * math.pi * (2 * a + 0.9 * h - t + wob))
            s2 = 0.5 + 0.5 * math.sin(2 * math.pi * (3 * a - 1.4 * h + 2 * t))
            v = 0.62 * s + 0.38 * s2
            c = VOID[0] if v < 0.22 else VOID[2] if v < 0.36 else MAG[1] if v < 0.48 else MAG[2] if v < 0.6 \
                else MAG[3] if v < 0.72 else MAG[4] if v < 0.85 else MAG[5]
            px[x, y] = rgba(c)
    rng = random.Random(900 + index)
    for _ in range(4):
        px[rng.randrange(32), rng.randrange(32)] = rgba(WHITE)
    for pts, level in _bolts(index, frames):
        _polyline(px, pts, level)
    return img


def _bolts(index: int, frames: int):
    """Bolts on this frame: (points, level) with level 2 = fresh, 1 = fading."""
    out = []
    for start in range(frames):
        rng = random.Random(start * 31 + 3)
        if rng.random() > 0.34:
            continue
        age = (index - start) % frames
        if age > 1:
            continue
        x = rng.randrange(32)
        y0 = rng.randint(0, 8)
        y1 = rng.randint(22, 31)
        pts = []
        for y in range(y0, y1 + 1):
            if y % 2 == 0:
                x += rng.choice((-2, -1, 1, 2))
            pts.append((x % 32, y))
        out.append((pts, 2 if age == 0 else 1))
    return out


def _polyline(px, pts, level):
    core = rgba(WHITE if level == 2 else MAG[6])
    halo = rgba(MAG[6] if level == 2 else MAG[5])
    for (x, y) in pts:
        px[x, y] = core
        if level == 2:
            for dx in (-1, 1):
                xx = (x + dx) % 32
                if px[xx, y] != core:
                    px[xx, y] = halo


def _fx_frame(index: int, frames: int = 16, gui: bool = False):
    t = index / frames
    img = canvas(32)
    px = img.load()
    # vent heat: rows 0..7, full width; hottest low in the vent, each slot flickering
    for y in range(8):
        for x in range(32):
            k = x / 32.0
            cell = math.floor(k * 5)
            flick = 0.2 * math.sin(2 * math.pi * (t + 0.37 * cell)) + 0.1 * math.sin(2 * math.pi * (2 * t + k * 3))
            heat = 0.35 + 0.55 * (y / 7.0) + flick
            heat *= 0.8 + 0.2 * math.sin(math.pi * k)
            c = AMBER[1] if heat < 0.3 else AMBER[2] if heat < 0.45 else AMBER[3] if heat < 0.62 else \
                AMBER[4] if heat < 0.8 else AMBER[5]
            px[x, y] = rgba(c)
    # 5x5 matrix: rows 8..19, cols 0..11; rows charge bottom-up, then flash and fade
    for y in range(8, 20):
        for x in range(12):
            px[x, y] = rgba(NETH[0])
    lit = index // 2 + 1
    for r in range(5):
        for c in range(5):
            x, y = 2 + 2 * c, 8 + 2 + 2 * r
            if index >= 10:
                col = MAG[7] if index in (10, 11) else MAG[5] if index in (12, 13) else MAG[3]
            else:
                col = MAG[5] if (4 - r) < lit else MAG[1]
            px[x, y] = rgba(col)
    if gui:                        # the icon shows the matrix as one screen that charges and flashes
        level = [MAG[1], MAG[2], MAG[2], MAG[3], MAG[3], MAG[3], MAG[4], MAG[4], MAG[4], MAG[4], MAG[7], MAG[7],
                 MAG[6], MAG[5], MAG[3], MAG[2]][index]
        for y in range(9, 19):
            for x in range(1, 11):
                px[x, y] = rgba(level)
    # conduits: cols 12..13, rows 8..19; two pulses climb toward the head each loop
    for y in range(8, 20):
        d = min(((y - 8) + 12 * 2 * t) % 6, 6 - ((y - 8) + 12 * 2 * t) % 6)
        c = WHITE if d < 0.6 else MAG[6] if d < 1.3 else MAG[5] if d < 2.2 else MAG[3]
        for x in (12, 13):
            px[x, y] = rgba(MAG[1] if gui else c)       # too thin to read in a slot
    # tip sparks: rows 20..23, one 4x4 cell per bit, each twinkling on its own phase
    for k in range(3):
        a = 0.5 - 0.5 * math.cos(2 * math.pi * (t + k / 3.0))
        core = WHITE if a > 0.66 else MAG[6] if a > 0.33 else MAG[5]
        edge = MAG[6] if a > 0.66 else MAG[5] if a > 0.33 else MAG[4]
        for y in range(20, 24):
            for x in range(4 * k, 4 * k + 4):
                inner = 1 <= x - 4 * k <= 2 and 21 <= y <= 22
                px[x, y] = rgba(core if inner else edge)
    # ring chase: rows 24..27 wrap round the glowing rings; two lights race round per loop
    for x in range(32):
        d = min((x - 32 * t) % 16, (32 * t - x) % 16)
        c = WHITE if d < 1.0 else MAG[6] if d < 2.5 else MAG[5] if d < 5 else MAG[4]
        for y in range(24, 28):
            px[x, y] = rgba(c if y in (25, 26) else (MAG[5] if c in (WHITE, MAG[6]) else MAG[4]))
    # exhaust stack mouths: rows 28..31, cols 0..7, a hot core that breathes
    for y in range(28, 32):
        for x in range(8):
            r = max(abs(x - 3.5), abs(y - 29.5)) / 4.0
            heat = 1.0 - r + 0.18 * math.sin(2 * math.pi * (t + x * 0.13)) + 0.1 * math.sin(4 * math.pi * t)
            c = AMBER[6] if heat > 0.9 else AMBER[5] if heat > 0.72 else AMBER[4] if heat > 0.5 else AMBER[3]
            px[x, y] = rgba(c)
    return img


GLYPHS = {
    "5": ["#####", "#....", "####.", "....#", "....#", "#...#", ".###."],
    "x": ["#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"],
}


def _paint_label() -> None:
    """The 5X5 identity plate for the gearbox ends: glowing magenta type on black."""
    img = canvas(32, 16)
    for y in range(11):
        for x in range(32):
            img.putpixel((x, y), rgba(NETH[1] if 0 < y < 10 and 0 < x < 31 else STEEL[2]))
    x = 7
    for ch in "5x5":
        for gy, row in enumerate(GLYPHS[ch]):
            for gx, c in enumerate(row):
                if c == "#":
                    img.putpixel((x + gx, 2 + gy), rgba(MAG[7] if gy == 0 else MAG[5] if gy < 5 else MAG[4]))
                    if img.getpixel((x + gx + 1, 3 + gy))[:3] == rgba(NETH[1])[:3]:
                        img.putpixel((x + gx + 1, 3 + gy), rgba(MAG[1]))       # drop shadow
        x += 6
    for cx, cy in ((2, 2), (29, 2), (2, 8), (29, 8)):
        img.putpixel((cx, cy), rgba(STEEL[5]))
    save(img, "label")


def _paint_animations() -> None:
    save_animation(animate(_bit_frame, 8), "bit", frametime=1)
    save_animation([_core_frame(i) for i in range(16)], "core", frametime=2)
    save_animation([_fx_frame(i) for i in range(16)], "fx", frametime=2)
    save_animation(animate(_bit_frame_gui, 8), "bit_gui", frametime=1)
    save_animation([_brighten(_core_frame(i), _CORE_GUI) for i in range(16)], "core_gui", frametime=2)
    save_animation([_fx_frame(i, gui=True) for i in range(16)], "fx_gui", frametime=2)


# ------------------------------------------------------------------------------------------
# Module contract
# ------------------------------------------------------------------------------------------

def textures() -> None:
    lay = _layout()
    images = {name: canvas(size) for name, size in lay["atlases"].items()}
    for j in lay["jobs"]:
        j.px = images[j.atlas].load()
        j.rng = random.Random(j.seed)
        PAINT[j.mat](j)
    for j in lay["jobs"]:
        _bleed(images[j.atlas], j)
    for name, image in images.items():
        save(image, name)
    icons = {name: canvas(size) for name, size in lay["atlases"].items()}
    for j in lay["jobs"]:
        j.px = icons[j.atlas].load()
        j.rng = random.Random(j.seed)
        GUI_PAINT[j.mat](j)
    for j in lay["jobs"]:
        _bleed(icons[j.atlas], j)
    for name, image in icons.items():
        save(image, name + "_gui")
    _paint_label()
    save(canvas(32, 16, fill=NETH[2]), "label_gui")
    _paint_animations()


def models() -> dict:
    parts = copy(_layout()["elements"])
    disp = display(KIND, parts, grip=GRIP, size=SIZE)
    # First person: the drill rises from the lower right, its front (vents, matrix) toward
    # the player and the whole head clear of the crosshair.
    disp["firstperson_righthand"] = place({"y": (-0.3, 0.93, -0.2), "z": (-0.45, 0.0, 0.9)}, GRIP,
                                          (0.7, -0.66, -0.92), 0.68 * SIZE * 0.98, pose=None)
    main = model(parts, disp)
    main["textures"] = {"particle": "#atlas"}
    # Inventory icon: the same sculpt, sampling the icon textures.
    icon = copy(parts)
    for e in icon:
        for face in e["faces"].values():
            face["texture"] += "_gui"
    gui = model(icon, copy(disp))
    gui["textures"] = {"particle": "#atlas"}
    return {"main": main, "gui": gui}
