"""Glacier Breaker: Winter Limited Edition pickaxe.

A massive ice-breaker pick. The head is a thick glacial block bound by two frost-rimed iron
bands and a riveted diamond bezel, through which the glowing blue ice core shows inside
the block. Two jagged ice spikes sweep out and down as the picks, serrated along their
backs. Snow sits on top, icicles hang underneath, and the steel-banded shaft is wrapped in
dark navy leather down to an aurora crystal pommel.

Every face is painted into small per-material atlases at 2 texels per model unit, so the
bevels, frost, rivets, stitching and runes land exactly on the edges of each part.
"""
from __future__ import annotations

import math
import random

from art.kit import (bar, box, canvas, copy, display, display_matrix, hand_frame, mirror, mix, model, place,
                     prism, ramp, rgba, rotation_of, save, turn)

ID = "glacier_breaker"
NAME = "Glacier Breaker"
KIND = "pickaxe"

# ------------------------------------------------------------------------------------------
# Palette: every material has its own hue-shifted ramp (darkest -> lightest)
# ------------------------------------------------------------------------------------------
ICE = ramp("#62bdf0", 6, 0.8, 0.08)        # glacial ice: block and spikes
GLACIER = ramp("#3d6fb8", 6, 0.8, 0.10)    # deep, thick ice
CORE = ramp("#4fd8ff", 6, 0.8, 0.06)       # the glowing core
IRON = ramp("#8a9bb3", 6, 0.8, 0.08)       # frost-rimed bands, socket and ferrule
STEEL = ramp("#b3c1d3", 6, 0.8, 0.08)      # polished bezel, shaft bands, bolts and claws
NAVY = ramp("#26356a", 6, 0.6, 0.10)       # leather wrap
TEAL = ramp("#38e0c0", 5, 0.75, 0.06)      # aurora accents
VIOLET = ramp("#9a70ff", 5, 0.75, 0.06)
SNOW = [mix(GLACIER[3], VIOLET[2], 0.35), mix(ICE[3], VIOLET[3], 0.3), mix(ICE[3], "#ffffff", 0.55),
        mix(ICE[4], "#ffffff", 0.78), "#f3faff", "#ffffff"]
WHITE = "#ffffff"
RUNE_HOT, RUNE_EDGE = "#dcfffa", "#72f6e2"

# ------------------------------------------------------------------------------------------
# Layout (model units; the handle runs up +Y on x = z = 8, the picks spread along X)
# ------------------------------------------------------------------------------------------
DENS = 2                      # texels per model unit on every face
CORE_C = (8.0, 18.0, 8.0)     # centre of the glowing core
WIN_R = 3.3                   # half-diagonal of the core window
GRIP = (8.0, -1.6, 8.0)       # where the fist closes
SIZE = 0.8                    # in-hand scale
FP_SIZE = 0.64                # first person is drawn smaller so the whole head frames the view
TP_ROLL = -25.0               # third person: roll about the handle so the upper pick clears the face
L_DIR = (0.0, 1.0, 0.0)       # baked light comes from above
MAT_GROUP = {"ice": "ice", "deep": "glow", "core": "glow", "rune": "glow", "aurora": "glow", "fracture": "glow",
             "iron": "metal", "steel": "metal", "snow": "soft", "leather": "soft"}
SIDES = ("north", "south", "east", "west", "up", "down")

SPIKE_START = (14.4, 18.0)    # root of the right spike, inside the block
SPIKE_LENGTH = 14.6           # arc length of the spike's spine
SPIKE_SEGMENTS = 10


def _spike_angle(f: float) -> float:
    """Direction of the spine (degrees) at fraction f of its length: level, then curling down."""
    return 3.0 - 57.0 * max(0.0, (f - 0.09) / 0.86) ** 1.15


def _spike_width(f: float) -> float:
    return max(0.8, min(5.6, 5.6 - 4.7 * (f - 0.09) / 0.86))


SPIKE_ANG = [_spike_angle((k + 0.5) / SPIKE_SEGMENTS) for k in range(SPIKE_SEGMENTS)]
SPIKE_LEN = [SPIKE_LENGTH / SPIKE_SEGMENTS] * SPIKE_SEGMENTS
SPIKE_W = [_spike_width((k + 0.5) / SPIKE_SEGMENTS) for k in range(SPIKE_SEGMENTS)]


# ------------------------------------------------------------------------------------------
# Small vector helpers
# ------------------------------------------------------------------------------------------

def _sub(a, b):
    return tuple(a[i] - b[i] for i in range(len(a)))


def _add(a, b):
    return tuple(a[i] + b[i] for i in range(len(a)))


def _scale(a, k):
    return tuple(v * k for v in a)


def _dot(a, b):
    return sum(a[i] * b[i] for i in range(len(a)))


def _mul(m, v):
    return tuple(sum(m[i][k] * v[k] for k in range(3)) for i in range(3))


def _lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(len(a)))


def _norm(v):
    n = math.sqrt(_dot(v, v)) or 1.0
    return tuple(x / n for x in v)


def _dir(deg):
    return math.cos(math.radians(deg)), math.sin(math.radians(deg))


# ------------------------------------------------------------------------------------------
# Part registry: elements plus how each one is painted
# ------------------------------------------------------------------------------------------

class _Parts:
    def __init__(self):
        self.items: list[dict] = []

    def add(self, elements, mat: str, **opt):
        for e in (elements if isinstance(elements, list) else [elements]):
            self.items.append({"e": e, "mat": mat, "opt": opt})
        return elements

    def mark(self) -> int:
        return len(self.items)

    def mirror_since(self, start: int, axis: str = "x", about: float = 8.0) -> None:
        """Mirrored copies of every part added since `start` (they share the painted faces)."""
        for i in range(start, len(self.items)):
            if "src" not in self.items[i]:
                self.items.append({"src": i, "op": ("mirror", axis, about)})

    def turn_since(self, start: int, angle: float, axis: str, origin) -> None:
        """Rotated copies of every source part added since `start` (sharing the painted faces)."""
        for i in range(start, len(self.items)):
            if "src" not in self.items[i]:
                self.items.append({"src": i, "op": ("turn", angle, axis, tuple(origin))})


def _no_caps(elements):
    for e in elements:
        e["faces"].pop("up", None)
        e["faces"].pop("down", None)
    return elements


def _decal(frm, to, side: str, glow: int = 13) -> dict:
    """A zero-thickness plane that only draws `side` (for glowing inlays)."""
    return box(frm, to, "x", skip=tuple(s for s in SIDES if s != side), glow=glow, shade=False)


def _crystal(P: _Parts, base, tip, w: float, tone: int = 1, split: float = 0.6) -> None:
    """A two-stage tapered ice crystal from base to tip (diamond cross-section)."""
    P.add(bar(base, _lerp(base, tip, split), w, w, "x", roll=45, skip=("down",)), "ice",
          style="spike", tone=tone)
    P.add(bar(_lerp(base, tip, split - 0.12), tip, w * 0.5, w * 0.5, "x", roll=45, skip=("down",)), "ice",
          style="spike", tone=tone + 1)


def _spill(P: _Parts, x0, x1, drop, side: str) -> None:
    """Snow slumping off the cap, over the block's edge and down its face."""
    if side == "north":
        lip = ((x0, 22.9 - drop * 0.35, 3.62), (x1, 23.45, 4.9))
        run = ((x0 + 0.25, 22.95 - drop, 3.66), (x1 - 0.25, 23.1 - drop * 0.35, 3.82))
        hidden = "south"
    else:
        lip = ((x0, 22.9 - drop * 0.35, 11.1), (x1, 23.45, 12.38))
        run = ((x0 + 0.25, 22.95 - drop, 12.18), (x1 - 0.25, 23.1 - drop * 0.35, 12.34))
        hidden = "north"
    P.add(box(*lip, "x"), "snow", style="drip")
    P.add(box(*run, "x", skip=("up", hidden)), "snow", style="drip")


# ------------------------------------------------------------------------------------------
# Geometry
# ------------------------------------------------------------------------------------------

def _head(P: _Parts) -> None:
    # Interior first: in game the translucent window blends over whatever was drawn before it.
    P.add(box((1.4, 13.6, 4.5), (14.6, 22.4, 11.5), "x", skip=("up", "down", "east", "west"), glow=6),
          "deep")
    core = box((6.1, 16.1, 4.15), (9.9, 19.9, 11.85), "x", glow=15, shade=False)
    P.add(turn(core, 45, "z", CORE_C), "core")
    P.add(box((0.5, 13.0, 3.8), (15.5, 23.0, 12.2), "x"), "ice", style="block", window=True)

    # Frost-rimed iron band (left), with bolts and a glowing rune inlay, mirrored right.
    s = P.mark()
    P.add(box((2.2, 12.5, 3.3), (4.2, 23.2, 12.7), "x"), "iron", style="band",
          channel=(2.45, 15.2, 3.95, 20.8))
    for y in (13.1, 21.5):
        P.add(box((2.6, y, 2.95), (3.8, y + 1.2, 3.3), "x", skip=("south",)), "steel", style="bolt")
        P.add(box((2.6, y, 12.7), (3.8, y + 1.2, 13.05), "x", skip=("north",)), "steel", style="bolt")
    P.add(_decal((2.45, 15.2, 3.24), (3.95, 20.8, 3.24), "north"), "rune", glyphs=(0, 1, 2))
    P.add(_decal((2.45, 15.2, 12.76), (3.95, 20.8, 12.76), "south"), "rune", glyphs=(3, 4, 5))
    P.mirror_since(s)

    # Light from the core leaking through fractures in the ice, on both faces.
    P.add(_decal((4.2, 13.0, 3.74), (11.8, 23.0, 3.74), "north", glow=10), "fracture", seed=3)
    P.add(_decal((4.2, 13.0, 12.26), (11.8, 23.0, 12.26), "south", glow=10), "fracture", seed=8)

    # Riveted diamond bezel around the core window (front), mirrored to the back.
    s = P.mark()
    rm, w = WIN_R + 0.64, 0.9
    cx, cy = CORE_C[0], CORE_C[1]
    pts = [(cx - rm, cy), (cx, cy + rm), (cx + rm, cy), (cx, cy - rm)]
    for a, b in zip(pts, pts[1:] + pts[:1]):
        d = _norm(_sub(b, a))
        p0, p1 = _sub(a, _scale(d, w * 0.5)), _add(b, _scale(d, w * 0.5))
        P.add(bar((p0[0], p0[1], 3.5), (p1[0], p1[1], 3.5), w, 0.6, "x"), "steel", style="bezel")
    for x, y in pts:
        stud = box((x - 0.55, y - 0.55, 2.95), (x + 0.55, y + 0.55, 3.25), "x", skip=("south",))
        P.add(turn(stud, 45, "z", (x, y, 3.1)), "steel", style="bolt")
    P.mirror_since(s, "z", 8.0)

    # Snow: a cap resting on the frosted top, low offset drifts, and spills slumping over
    # the long edges and down the faces.
    P.add(box((0.9, 23.0, 4.6), (15.1, 23.9, 11.4), "x", skip=("down",)), "snow")
    for (frm, to), yaw in ((((1.6, 23.8, 5.0), (7.9, 24.5, 11.0)), 5), (((8.5, 23.8, 5.3), (14.4, 24.35, 10.7)), -7),
                           (((2.9, 24.4, 5.8), (6.2, 24.9, 10.1)), -9), (((10.0, 24.25, 6.4), (12.7, 24.7, 9.6)), 11),
                           (((6.9, 23.8, 7.9), (8.9, 24.2, 10.2)), 20), (((4.1, 24.85, 7.0), (5.4, 25.2, 8.6)), 14)):
        centre = ((frm[0] + to[0]) / 2, frm[1], (frm[2] + to[2]) / 2)
        P.add(turn(box(frm, to, "x", skip=("down",)), yaw, "y", centre), "snow")
    for x0, x1, drop in ((0.8, 2.0, 1.3), (4.9, 6.6, 1.1), (9.5, 10.8, 0.8), (14.0, 15.2, 1.6)):
        _spill(P, x0, x1, drop, "north")
    for x0, x1, drop in ((1.0, 2.1, 1.0), (5.4, 7.0, 1.5), (9.0, 10.3, 1.0), (14.2, 15.3, 1.2)):
        _spill(P, x0, x1, drop, "south")


def _spine() -> list[tuple[float, float]]:
    pts = [SPIKE_START]
    x, y = SPIKE_START
    for a, length in zip(SPIKE_ANG, SPIKE_LEN):
        dx, dy = _dir(a)
        x, y = x + dx * length, y + dy * length
        pts.append((x, y))
    return pts


def _ridge(pts, k: int, sink: float, lift: float = 0.0):
    """A point on the top ridge of spike segment k (sink pulls it into the ice)."""
    x0, y0 = pts[k]
    ux, uy = _dir(SPIKE_ANG[min(k, len(SPIKE_ANG) - 1)] + 90)
    w = SPIKE_W[min(k, len(SPIKE_W) - 1)]
    rise = w / math.sqrt(2) * (1 - sink) + lift
    return (x0 + ux * rise, y0 + uy * rise, 8.0)


def _spikes(P: _Parts) -> None:
    pts = _spine()
    s = P.mark()
    for k, w in enumerate(SPIKE_W):
        (x0, y0), (x1, y1) = pts[k], pts[k + 1]
        dx, dy = _dir(SPIKE_ANG[k])
        back = 0.0 if k == 0 else 0.45
        e = bar((x0 - dx * back, y0 - dy * back, 8.0), (x1, y1, 8.0), w, w, "x", roll=45, skip=("down",))
        P.add(e, "ice", style="spike", tone=0 if k < SPIKE_SEGMENTS * 0.45 else 1)
    # serrated back: two big ice teeth growing up and out along the top ridge
    for k, length, w, lean in ((3, 3.6, 2.0, 38), (6, 2.6, 1.4, 34)):
        base = _ridge(pts, k, 0.5)
        tx, ty = _dir(SPIKE_ANG[k] + lean)
        _crystal(P, base, (base[0] + tx * length, base[1] + ty * length, 8.0), w)
    # snow riding the ridge of the spike root, thinning outward
    for k0, k1, w in ((0, 3, 2.4), (3, 5, 1.6)):
        a, b = _ridge(pts, k0, 0.0, 0.15), _ridge(pts, k1, 0.0, 0.15)
        if k0 == 0:
            a = (a[0] - 0.4, a[1], a[2])
        P.add(bar(a, b, w, w, "x", roll=45), "snow", style="ridge")
    P.mirror_since(s)


def _icicle(P: _Parts, top, length: float, w: float = 1.2) -> None:
    x, y, z = top
    P.add(bar((x, y + 0.3, z), (x, y - length * 0.6, z), w, w, "x", roll=45, skip=("down",)), "ice",
          style="icicle")
    P.add(bar((x, y - length * 0.48, z), (x, y - length, z), w * 0.5, w * 0.5, "x", roll=45, skip=("down",)),
          "ice", style="icicle", tip=True)


def _icicles(P: _Parts) -> None:
    for top, length, w in (((1.4, 13.1, 4.4), 2.6, 1.3), ((4.8, 13.1, 4.4), 3.4, 1.4),
                           ((11.3, 13.1, 4.5), 2.8, 1.3), ((14.5, 13.1, 4.4), 1.8, 1.1),
                           ((1.7, 13.1, 11.6), 3.0, 1.3), ((11.2, 13.1, 11.6), 3.6, 1.4),
                           ((16.5, 14.4, 8.0), 2.6, 1.3), ((-0.5, 14.4, 8.0), 2.0, 1.2)):
        _icicle(P, top, length, w)


def _handle(P: _Parts) -> None:
    P.add(box((5.0, 12.2, 4.8), (11.0, 13.2, 11.2), "x", skip=("up",)), "iron", style="plate")
    P.add(box((5.9, 8.8, 5.9), (10.1, 12.3, 10.1), "x", skip=("up", "down")), "iron", style="ferrule",
          channel=(6.8, 9.35, 9.2, 11.75))
    P.add(_decal((6.8, 9.35, 5.84), (9.2, 11.75, 5.84), "north"), "rune", glyphs=(6,))
    P.add(_decal((6.8, 9.35, 10.16), (9.2, 11.75, 10.16), "south"), "rune", glyphs=(6,))
    P.add(prism((8, 8.5, 8), 2.35, 0.8, "x", sides=8), "steel", style="band")
    P.add(_no_caps(prism((8, 6.0, 8), 1.75, 4.4, "x", sides=8)), "leather", style="spiral")
    P.add(prism((8, 3.4, 8), 2.15, 1.1, "x", sides=8), "steel", style="band")
    P.add(_no_caps(prism((8, -1.6, 8), 1.8, 9.2, "x", sides=8)), "leather", style="grip")
    P.add(prism((8, -6.6, 8), 2.15, 1.1, "x", sides=8), "steel", style="band")
    P.add(prism((8, -7.85, 8), 2.3, 1.4, "x", sides=8), "steel", style="collar")
    P.add(_no_caps(prism((8, -8.95, 8), 1.6, 0.9, "x", sides=8)), "iron", style="neck")

    # Aurora crystal pommel held by four steel claws.
    for (y0, y1), w in (((-9.0, -12.5), 2.7), ((-12.1, -14.3), 1.8), ((-14.0, -15.4), 0.8)):
        P.add(bar((8, y0, 8), (8, y1, 8), w, w, "x", roll=45, skip=("down",), glow=11), "aurora")
    s = P.mark()
    P.add(bar((10.3, -8.6, 8), (9.75, -11.2, 8), 0.6, 0.6, "x", skip=("up",)), "steel", style="claw")
    for angle in (90, 180, 270):
        P.turn_since(s, angle, "y", (8, 0, 8))


def _build() -> list[dict]:
    P = _Parts()
    _head(P)
    _spikes(P)
    _icicles(P)
    _handle(P)
    return P.items


# ------------------------------------------------------------------------------------------
# Atlas layout: one painted region per face
# ------------------------------------------------------------------------------------------

_FACE_VERTS = {
    "down": lambda a, b: [(a[0], a[1], b[2]), (a[0], a[1], a[2]), (b[0], a[1], a[2]), (b[0], a[1], b[2])],
    "up": lambda a, b: [(a[0], b[1], a[2]), (a[0], b[1], b[2]), (b[0], b[1], b[2]), (b[0], b[1], a[2])],
    "north": lambda a, b: [(b[0], b[1], a[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2]), (a[0], b[1], a[2])],
    "south": lambda a, b: [(a[0], b[1], b[2]), (a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], b[1], b[2])],
    "west": lambda a, b: [(a[0], b[1], a[2]), (a[0], a[1], a[2]), (a[0], a[1], b[2]), (a[0], b[1], b[2])],
    "east": lambda a, b: [(b[0], b[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (b[0], b[1], a[2])],
}
_NORMAL = {"down": (0, -1, 0), "up": (0, 1, 0), "north": (0, 0, -1), "south": (0, 0, 1),
           "west": (-1, 0, 0), "east": (1, 0, 0)}


def _face_size(side, a, b):
    dx, dy, dz = (b[i] - a[i] for i in range(3))
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
            "up": (dx, dz), "down": (dx, dz)}[side]


class _Job:
    """One face's region in an atlas, with its placement in model space for painting."""

    def __init__(self, atlas, x, y, tw, th, part, side, seed):
        self.atlas, self.x, self.y, self.tw, self.th = atlas, x, y, tw, th
        self.mat, self.opt, self.side, self.seed = part["mat"], part["opt"], side, seed
        e = part["e"]
        verts = _FACE_VERTS[side](e["from"], e["to"])
        m, o = rotation_of(e)
        to_model = (lambda p: p) if o is None else (lambda p: _add(_mul(m, _sub(p, o)), o))
        p0, p1, p3 = to_model(verts[0]), to_model(verts[1]), to_model(verts[3])
        self.o, self.du, self.dv = p0, _sub(p3, p0), _sub(p1, p0)
        self.n = _mul(m, _NORMAL[side])
        self.lit = _dot(self.n, L_DIR)
        hs = [_dot(p, L_DIR) for p in (p0, p1, p3, _add(p1, self.du))]
        self.hmin, self.hmax = min(hs), max(hs)
        self.rng = random.Random(seed)
        self.px = None
        self.img = None

    # geometry --------------------------------------------------------------------------
    def pos(self, i, j):
        fu, fv = (i + 0.5) / self.tw, (j + 0.5) / self.th
        return tuple(self.o[k] + self.du[k] * fu + self.dv[k] * fv for k in range(3))

    def t(self, i, j) -> float:
        """0 at the best-lit end of the face, 1 at the darkest."""
        span = self.hmax - self.hmin
        if span < 1e-4:
            return 0.5
        return max(0.0, min(1.0, (self.hmax - _dot(self.pos(i, j), L_DIR)) / span))

    def texel_of(self, p):
        """Fractional texel coordinates of a model-space point projected on the face."""
        d = _sub(p, self.o)
        fu = _dot(d, self.du) / max(1e-9, _dot(self.du, self.du))
        fv = _dot(d, self.dv) / max(1e-9, _dot(self.dv, self.dv))
        return fu * self.tw - 0.5, fv * self.th - 0.5

    def border_t(self) -> dict:
        tw, th = self.tw, self.th
        return {"top": self.t((tw - 1) / 2, 0), "bottom": self.t((tw - 1) / 2, th - 1),
                "left": self.t(0, (th - 1) / 2), "right": self.t(tw - 1, (th - 1) / 2)}

    # pixels ----------------------------------------------------------------------------
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
    requests: dict[str, list] = {}
    for idx, p in enumerate(parts):
        if "src" in p:
            continue
        e = p["e"]
        for side in e["faces"]:
            w, h = _face_size(side, e["from"], e["to"])
            tw, th = max(1, round(w * DENS)), max(1, round(h * DENS))
            requests.setdefault(MAT_GROUP[p["mat"]], []).append((th, tw, idx, side))
    atlases: dict[str, int] = {}
    jobs: list[_Job] = []
    for group in sorted(requests):
        items = sorted(requests[group], key=lambda r: (-r[0], -r[1], r[2], SIDES.index(r[3])))
        sheets, cur, x, y, row = [], [], 0, 0, 0
        for th, tw, idx, side in items:
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
        for n, placed in enumerate(sheets):
            name = group if n == 0 else f"{group}{n + 1}"
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
    elements = []
    for p in parts:
        if "src" in p:
            src = parts[p["src"]]["e"]
            op = p["op"]
            if op[0] == "mirror":
                p["e"] = mirror(src, op[1], op[2])[0]
            else:
                p["e"] = turn(copy(src), op[1], op[2], op[3])
        elements.append(p["e"])
    _CACHE.update(parts=parts, elements=elements, jobs=jobs, atlases=atlases)
    return _CACHE


# ------------------------------------------------------------------------------------------
# Painters
# ------------------------------------------------------------------------------------------

def _band(stops, t):
    return stops[min(len(stops) - 1, int(t * len(stops)))]


def _alpha(colour, a):
    c = rgba(colour)
    return c[0], c[1], c[2], a


def _edges(j: _Job, light, dark, mid=None, only=None) -> None:
    """Bevel the face's rim: edges facing the light get `light`, the far ones `dark`."""
    bt = j.border_t()
    for name, t in bt.items():
        if only and name not in only:
            continue
        colour = light if t < 0.34 else dark if t > 0.66 else mid
        if colour is None:
            continue
        if name == "top":
            for i in range(j.tw):
                j.put(i, 0, colour)
        elif name == "bottom":
            for i in range(j.tw):
                j.put(i, j.th - 1, colour)
        elif name == "left":
            for jj in range(j.th):
                j.put(0, jj, colour)
        else:
            for jj in range(j.th):
                j.put(j.tw - 1, jj, colour)


def _crack(j: _Job, x, y, angle, length, bright, shadow, branch=True, keep=None) -> None:
    rng = j.rng
    fx, fy = float(x), float(y)
    for step in range(int(length)):
        ix, iy = int(round(fx)), int(round(fy))
        if keep is None or keep(ix, iy):
            j.put(ix, iy, bright)
            if 0 <= ix < j.tw and 0 <= iy + 1 < j.th and j.get(ix, iy + 1)[:3] != rgba(bright)[:3] \
                    and (keep is None or keep(ix, iy + 1)):
                j.put(ix, iy + 1, shadow)
        angle += rng.uniform(-0.4, 0.4)
        fx += math.cos(angle)
        fy += math.sin(angle)
        if branch and step > 2 and rng.random() < 0.16:
            _crack(j, fx, fy, angle + rng.choice((-0.9, 0.9)), length * 0.4, bright, shadow, False, keep)


def _p_ice(j: _Job) -> None:
    style = j.opt.get("style", "block")
    if style == "block":
        _ice_block(j)
    elif style == "icicle":
        _ice_icicle(j)
    else:
        _ice_spike(j)


def _ice_block(j: _Job) -> None:
    rng = j.rng
    tw, th = j.tw, j.th
    if j.lit > 0.5:                                    # top: rimed with frost under the snow
        for i, jj in j.texels():
            j.put(i, jj, ICE[4] if rng.random() > 0.45 else SNOW[3])
        for _ in range(max(2, tw * th // 12)):
            j.put(rng.randrange(tw), rng.randrange(th), SNOW[5] if rng.random() < 0.6 else ICE[5])
        _edges(j, None, None, SNOW[4])
        return
    if j.lit < -0.5:                                   # underside: deeper blue, still icy
        for i, jj in j.texels():
            j.put(i, jj, ICE[2] if rng.random() > 0.22 else GLACIER[3])
        for _ in range(4):
            _crack(j, rng.randrange(tw), rng.randrange(th), rng.uniform(0, 6.28), 7, ICE[4], GLACIER[2])
        _edges(j, ICE[4], GLACIER[2], ICE[3])
        return
    window = j.opt.get("window") and j.side in ("north", "south")
    cx, cy = j.texel_of(CORE_C)
    r = WIN_R * DENS

    def solid(i, jj):
        return not window or abs(i - cx) + abs(jj - cy) >= r + 0.5

    stops = [ICE[5], ICE[4], ICE[4], ICE[4], ICE[3], ICE[3]]
    for i, jj in j.texels():
        j.put(i, jj, _band(stops, j.t(i, jj)))
    # thicker ice looks deeper blue toward the lower corners
    for i, jj in j.texels():
        edge = min(i, tw - 1 - i)
        if j.t(i, jj) > 0.72 and edge + (th - 1 - jj) < 4:
            j.put(i, jj, GLACIER[3])
    # glassy diagonal sheen across the upper half
    a = tw * 0.3
    for i, jj in j.texels():
        s = i + jj
        if solid(i, jj) and jj < th * 0.7 and (a <= s < a + 2 or a + 4 <= s < a + 5):
            j.put(i, jj, ICE[5] if a <= s < a + 2 else WHITE)
    # frost under the snow, bubbles, cracks
    for i in range(tw):
        j.put(i, 0, SNOW[4])
        if rng.random() < 0.5:
            j.put(i, 1, SNOW[3])
    for _ in range(max(3, tw * th // 60)):
        x, y = rng.randrange(tw), rng.randrange(2, th)
        if solid(x, y):
            j.put(x, y, ICE[5])
    for _ in range(1 if window else 2):
        _crack(j, rng.randrange(tw), rng.randrange(th // 3, th), rng.uniform(-2.6, -0.5), 7,
               ICE[5], GLACIER[2], keep=solid)
    if window:
        _window(j, cx, cy, r)
    _edges(j, None, GLACIER[2], None, only=("bottom",))


def _window(j: _Job, cx, cy, r) -> None:
    """A diamond pane of clear ice showing the glowing core inside."""
    for i, jj in j.texels():
        dx, dy = i - cx, jj - cy
        d = abs(dx) + abs(dy)
        if d < r + 0.5:
            colour, alpha = ICE[4], 50
            if -2.5 < (dx - dy) < -0.5 and dy < 0:
                colour, alpha = ICE[5], 130             # reflection streak
            elif d > r - 1.0:
                colour, alpha = SNOW[4], 110            # frosted rim of the pane
            j.put(i, jj, _alpha(colour, alpha))


def _ice_spike(j: _Job) -> None:
    """Crystal facets painted with fixed-width edges and a flat body, so consecutive
    segments of a spike read as one continuous crystal instead of stacked plates."""
    tone = j.opt.get("tone", 0)
    upper_body = ICE[4] if tone == 0 else mix(ICE[4], ICE[5], 0.5)
    lower_body = ICE[2] if tone == 0 else mix(ICE[3], VIOLET[3], 0.25)
    if j.side in ("up", "down"):                      # the stepped end of a segment
        cy = j.pos((j.tw - 1) / 2, (j.th - 1) / 2)[1]
        for i, jj in j.texels():
            j.put(i, jj, upper_body if j.pos(i, jj)[1] >= cy else lower_body)
        return
    upper = j.lit > 0.05
    bt = j.border_t()
    lit_left = bt["left"] < bt["right"]
    rng = j.rng
    for i, jj in j.texels():
        d = i if lit_left else j.tw - 1 - i          # texels from the lit (ridge) edge
        far = j.tw - 1 - d
        if upper:
            colour = WHITE if d == 0 else ICE[5] if d == 1 and j.tw > 3 else \
                ICE[3] if far == 0 else upper_body
            if tone and far == 1 and j.tw > 4:
                colour = mix(ICE[4], TEAL[4], 0.35)   # aurora glint along the facet
        else:
            colour = ICE[4] if d == 0 else GLACIER[1] if far == 0 else \
                GLACIER[2] if far == 1 and j.tw > 3 else lower_body
        j.put(i, jj, colour)
    # a facet line at a fixed fraction of the width carries across every segment
    if j.tw >= 5:
        lane = int(round((j.tw - 1) * 0.55)) if lit_left else int(round((j.tw - 1) * 0.45))
        for jj in range(j.th):
            j.put(lane, jj, ICE[5] if upper else ICE[3])
    for i, jj in j.texels():
        if rng.random() < 0.03:
            j.put(i, jj, SNOW[5] if upper else ICE[4])


def _ice_icicle(j: _Job) -> None:
    if j.side in ("up", "down"):
        for i, jj in j.texels():
            j.put(i, jj, ICE[4])
        return
    for i, jj in j.texels():
        j.put(i, jj, ICE[5] if i == 0 else ICE[4] if i < j.tw - 1 else ICE[3])
    if j.opt.get("tip"):
        for i in range(j.tw):
            j.put(i, 0, _alpha(ICE[5], 170))
    elif j.th > 2:
        j.put(0, j.th - 1, WHITE)


def _p_deep(j: _Job) -> None:
    cx, cy = j.texel_of(CORE_C)
    bayer = (0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    rings = [(4.0, CORE[3]), (6.5, CORE[2]), (9.0, GLACIER[3]), (12.0, GLACIER[2]), (99, GLACIER[1])]
    for i, jj in j.texels():
        d = math.hypot(i - cx, (jj - cy) * 1.2)
        for k, (lim, colour) in enumerate(rings):
            if d < lim:
                prev = rings[k - 1][0] if k else 0.0
                frac = (d - prev) / max(0.01, lim - prev)
                if frac > 0.6 and k + 1 < len(rings) and bayer[(jj % 4) * 4 + i % 4] < (frac - 0.6) * 40:
                    colour = rings[k + 1][1]
                j.put(i, jj, colour)
                break
    for _ in range(10):
        j.put(j.rng.randrange(j.tw), j.rng.randrange(j.th), ICE[5])


def _p_core(j: _Job) -> None:
    if j.side in ("north", "south"):
        cx, cy = (j.tw - 1) / 2, (j.th - 1) / 2
        rmax = max(cx, cy) or 1
        for i, jj in j.texels():
            r = max(abs(i - cx), abs(jj - cy)) / rmax
            colour = WHITE if r < 0.25 else CORE[5] if r < 0.5 else CORE[4] if r < 0.8 else CORE[3]
            if abs(abs(i - cx) - abs(jj - cy)) < 0.6 and r >= 0.25:
                colour = CORE[5]
            j.put(i, jj, colour)
    else:
        for i, jj in j.texels():
            j.put(i, jj, CORE[3] if jj % 2 else CORE[4])


def _rime(j: _Job, rng, depth: int = 3) -> None:
    """Frost crusting the top edge of a face, with the odd drip."""
    h = 1
    for i in range(j.tw):
        h = max(1, min(depth, h + rng.choice((-1, 0, 0, 1))))
        for d in range(h):
            j.put(i, d, SNOW[5] if d == 0 else SNOW[3] if d == h - 1 else SNOW[4])
        if rng.random() < 0.15:
            j.put(i, h, SNOW[2])


def _p_iron(j: _Job) -> None:
    o = j.opt
    style = o.get("style", "plate")
    rng = j.rng
    b = 3 if j.lit > 0.5 else 2
    stops = [IRON[b + 1], IRON[b], IRON[b], IRON[b - 1]]
    for i, jj in j.texels():
        j.put(i, jj, _band(stops, j.t(i, jj)))
    # brushed grain across the face
    for jj in range(j.th):
        x = rng.randrange(0, 3)
        while x < j.tw:
            n = rng.randint(2, 5)
            if rng.random() < 0.25:
                for i in range(x, min(j.tw, x + n)):
                    j.put(i, jj, IRON[b + 1] if rng.random() < 0.5 else IRON[b - 1])
            x += n + rng.randint(1, 3)
    # engraved slot behind a glowing rune inlay
    ch = o.get("channel")
    has_channel = bool(ch) and j.side in ("north", "south")
    if has_channel:
        x0, y0, x1, y1 = ch
        for i, jj in j.texels():
            p = j.pos(i, jj)
            if x0 - 0.5 <= p[0] <= x1 + 0.5 and y0 - 0.5 <= p[1] <= y1 + 0.5:
                inner = x0 <= p[0] <= x1 and y0 <= p[1] <= y1
                j.put(i, jj, NAVY[1] if inner else (IRON[1] if p[1] > (y0 + y1) / 2 else IRON[5]))
    _edges(j, IRON[5], IRON[1], IRON[2])
    # rivets on the plain faces
    if style in ("band", "plate") and j.lit < 0.5 and j.tw >= 6 and j.th >= 6 and not has_channel:
        spots = [(1, 1), (j.tw - 3, 1), (1, j.th - 3), (j.tw - 3, j.th - 3)]
        if style == "plate":
            spots = [(1, (j.th - 2) // 2), (j.tw - 3, (j.th - 2) // 2)]
        for sx, sy in spots:
            j.put(sx, sy, IRON[5])
            j.put(sx + 1, sy, IRON[3])
            j.put(sx, sy + 1, IRON[3])
            j.put(sx + 1, sy + 1, IRON[0])
    # frost rime on the upper edge and scattered frost
    if style in ("band", "plate", "ferrule") and abs(j.lit) < 0.75:
        top = min(j.border_t().items(), key=lambda kv: kv[1])
        if top[0] == "top" and top[1] < 0.34:
            _rime(j, rng, 3 if style == "band" else 2)
        for i, jj in j.texels():
            if rng.random() < 0.05 and j.t(i, jj) < 0.5:
                j.put(i, jj, SNOW[4])
    if j.lit > 0.5 and style in ("band", "plate"):
        for i, jj in j.texels():
            if rng.random() < 0.4:
                j.put(i, jj, SNOW[4] if rng.random() < 0.6 else SNOW[5])


def _p_bezel(j: _Job) -> None:
    """Polished silver frame: lit outer edge, shadowed inner edge toward the window, frost on top."""
    cx, cy = CORE_C[0], CORE_C[1]
    rm = WIN_R + 0.64
    rng = j.rng
    for i, jj in j.texels():
        p = j.pos(i, jj)
        r = abs(p[0] - cx) + abs(p[1] - cy)
        high = p[1] > cy
        if abs(j.n[2]) > 0.9:                      # the face looking out of the block
            if r < rm:
                colour = STEEL[2] if high else STEEL[1]
            else:
                colour = STEEL[5] if high else STEEL[3]
        elif j.lit > 0.2:                          # upper outer sides catch frost
            colour = SNOW[5] if rng.random() < 0.5 else STEEL[4]
        elif _dot(j.n, _norm((p[0] - cx, p[1] - cy, 0.0))) < 0:
            colour = STEEL[1]                      # inner sides look into the window
        else:
            colour = STEEL[2]
        j.put(i, jj, colour)
    if abs(j.n[2]) > 0.9 and j.th > 4:
        j.put(0, j.th // 2, WHITE)


def _p_steel(j: _Job) -> None:
    style = j.opt.get("style", "band")
    if style == "bezel":
        _p_bezel(j)
        return
    if style == "bolt":
        facing = abs(j.n[2]) > 0.9                 # the domed head: lit top-left, shaded bottom-right
        for i, jj in j.texels():
            if not facing:
                colour = STEEL[2]
            elif (i, jj) == (0, 0):
                colour = STEEL[5]
            elif (i, jj) == (j.tw - 1, j.th - 1):
                colour = STEEL[1]
            else:
                colour = STEEL[4] if i == 0 or jj == 0 else STEEL[3]
            j.put(i, jj, colour)
        return
    if j.lit > 0.5:
        for i, jj in j.texels():
            j.put(i, jj, STEEL[4] if (i + jj) % 5 else STEEL[5])
        return
    if j.lit < -0.5:
        for i, jj in j.texels():
            j.put(i, jj, STEEL[2])
        return
    rows = [STEEL[5], STEEL[4], STEEL[3], STEEL[3], STEEL[2], STEEL[1]]
    for i, jj in j.texels():
        j.put(i, jj, _band(rows, j.t(i, jj)))
    if style == "collar":
        mid = j.th // 2
        for i in range(j.tw):
            j.put(i, mid, STEEL[1])
            j.put(i, mid + 1, STEEL[5])
    # a specular glint on faces turned toward the viewer's usual side
    if j.n[2] > 0.6 or j.n[0] < -0.6:
        for jj in range(1, max(2, j.th - 1)):
            j.put(min(1, j.tw - 1), jj, WHITE if jj == 1 else STEEL[5])


def _p_leather(j: _Job) -> None:
    style = j.opt.get("style", "spiral")
    if abs(j.n[1]) > 0.5:
        for i, jj in j.texels():
            j.put(i, jj, NAVY[1])
        return
    ang = math.degrees(math.atan2(j.n[0], j.n[2])) % 360
    k = int(round(ang / 45)) % 8
    dim = j.n[2] < -0.3
    for i, jj in j.texels():
        gu = k * j.tw + i
        gy = int(math.floor(j.pos(i, jj)[1] * DENS))
        if style == "spiral":
            r = (gy + gu) % 5
            colour = [NAVY[0], NAVY[1], NAVY[2], NAVY[3], NAVY[4]][r]
        else:
            a, b2 = (gy + gu) % 8, (gy - gu) % 8
            over = ((gy + gu) // 8 + (gy - gu) // 8) % 2 == 0   # which cord crosses on top
            if a in (0, 1) and (b2 not in (0, 1) or over):
                colour = NAVY[4] if a == 1 else NAVY[3]
            elif b2 in (0, 1):
                colour = NAVY[4] if b2 == 1 else NAVY[3]
            elif a == 2 or b2 == 2:
                colour = NAVY[0]                       # shadow under the cord
            elif a == 5 and b2 == 5:
                colour = TEAL[3]
            elif (a, b2) in ((4, 5), (5, 4), (6, 5), (5, 6)):
                colour = TEAL[1]
            else:
                colour = NAVY[1]
        if dim and colour in (NAVY[2], NAVY[3]):
            colour = NAVY[1] if colour == NAVY[2] else NAVY[2]
        j.put(i, jj, colour)


def _p_snow(j: _Job) -> None:
    rng = j.rng
    if j.opt.get("style") == "ridge" and j.side not in ("up", "down"):
        upper = j.lit > 0
        for i, jj in j.texels():
            j.put(i, jj, _band([SNOW[5], SNOW[4], SNOW[4], SNOW[3]] if upper else [SNOW[3], SNOW[2]], j.t(i, jj)))
        for _ in range(max(1, j.tw * j.th // 14)):
            j.put(rng.randrange(j.tw), rng.randrange(j.th), WHITE if upper else SNOW[3])
        return
    if j.opt.get("style") == "drip" and j.side not in ("up", "down"):
        for i, jj in j.texels():
            j.put(i, jj, _band([SNOW[4], SNOW[4], SNOW[3], SNOW[3], SNOW[2]], j.t(i, jj)))
        j.put(0, 0, SNOW[5])
        return
    if j.lit > 0.5:
        for i, jj in j.texels():
            rim = min(i, jj, j.tw - 1 - i, j.th - 1 - jj)
            j.put(i, jj, SNOW[3] if rim == 0 and (i + jj) % 3 else SNOW[4])
        for _ in range(max(1, j.tw * j.th // 10)):
            x, y = rng.randrange(1, max(2, j.tw - 1)), rng.randrange(1, max(2, j.th - 1))
            for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
                if 0 < x + dx < j.tw - 1 and 0 < y + dy < j.th - 1:
                    j.put(x + dx, y + dy, SNOW[5])
        for _ in range(max(1, j.tw * j.th // 24)):
            j.put(rng.randrange(j.tw), rng.randrange(j.th), SNOW[3])
        for _ in range(max(1, j.tw * j.th // 40)):
            j.put(rng.randrange(j.tw), rng.randrange(j.th), "#bff6ff")
        return
    if j.lit < -0.5:
        for i, jj in j.texels():
            j.put(i, jj, SNOW[3] if rng.random() > 0.2 else SNOW[2])
        return
    stops = [SNOW[5], SNOW[4], SNOW[4], SNOW[3], SNOW[2]] if j.th >= 3 else [SNOW[5], SNOW[3]]
    for i, jj in j.texels():
        j.put(i, jj, _band(stops, j.t(i, jj)))
    for i in range(j.tw):                              # a lumpy lower edge
        if rng.random() < 0.35 and j.th >= 3:
            j.put(i, j.th - 2, SNOW[3])
    for _ in range(max(1, j.tw * j.th // 25)):
        j.put(rng.randrange(j.tw), rng.randrange(max(1, j.th - 1)), WHITE)


GLYPHS = [
    ["#.#", ".#.", "#.#"],
    ["###", ".#.", "#.#"],
    [".#.", "###", "#.#"],
    ["#..", "###", "..#"],
    ["#.#", "###", ".#."],
    [".##", ".#.", "##."],
    ["..#..", "#.#.#", ".###.", "#.#.#", "..#.."],   # snowflake
]


def _p_rune(j: _Job) -> None:
    glyphs = [GLYPHS[g] for g in j.opt.get("glyphs", (0,))]
    total = sum(len(g) for g in glyphs) + (len(glyphs) - 1)
    y = (j.th - total) // 2
    for g in glyphs:
        gw = len(g[0])
        x = (j.tw - gw) // 2
        for gy, row in enumerate(g):
            for gx, ch in enumerate(row):
                if ch == "#":
                    centre = (gx == gw // 2 and gy == len(g) // 2)
                    j.put(x + gx, y + gy, RUNE_HOT if centre or gy == 0 else RUNE_EDGE)
        y += len(g) + 1


def _p_aurora(j: _Job) -> None:
    ylo, yhi = -15.4, -9.0
    stops = [VIOLET[3], VIOLET[2], mix(VIOLET[2], GLACIER[3], 0.5), GLACIER[4], mix(GLACIER[4], TEAL[3], 0.5),
             TEAL[3], TEAL[4]]
    bayer = (0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    for i, jj in j.texels():
        f = max(0.0, min(0.999, (j.pos(i, jj)[1] - ylo) / (yhi - ylo))) * (len(stops) - 1)
        k = int(f)
        if f - k > 0.5 and bayer[(jj % 4) * 4 + i % 4] < (f - k - 0.5) * 32:
            k += 1
        j.put(i, jj, stops[min(k, len(stops) - 1)])
    if j.side in ("up", "down"):
        return
    bt = j.border_t()
    lit_edge = 0 if bt["left"] < bt["right"] else j.tw - 1
    for jj in range(j.th):
        j.put(lit_edge, jj, "#f0e6ff")
    if j.lit > 0 and j.th > 2:
        j.put(j.tw // 2, j.th // 2, WHITE)


def _p_fracture(j: _Job) -> None:
    """Glowing hairline fractures running out from the bezel's corners (transparent elsewhere)."""
    rng = random.Random(j.opt.get("seed", 0))
    cx, cy = j.texel_of(CORE_C)
    clear = (WIN_R + 1.35) * DENS                   # stay outside the bezel and its studs

    def visible(i, jj):
        return abs(i - cx) + abs(jj - cy) >= clear

    # one crack from the middle of each of the bezel's four sides out into the open ice,
    # with a short branch partway along
    for sx, sy in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
        a = math.atan2(sy, sx) + rng.uniform(-0.35, 0.35)
        fx, fy = cx + sx * clear / 2, cy + sy * clear / 2
        length = rng.randint(5, 7)
        branch_at = rng.randint(2, 3)
        for step in range(length):
            ix, iy = int(round(fx)), int(round(fy))
            if visible(ix, iy):
                j.put(ix, iy, CORE[5] if step < 2 else CORE[4] if step < length - 1 else CORE[3])
            if step == branch_at:
                b = a + rng.choice((-0.8, 0.8))
                bx, by = fx, fy
                for _ in range(2):
                    bx, by = bx + math.cos(b), by + math.sin(b)
                    if visible(int(round(bx)), int(round(by))):
                        j.put(int(round(bx)), int(round(by)), CORE[3])
            a += rng.uniform(-0.4, 0.4)
            fx += math.cos(a)
            fy += math.sin(a)


PAINT = {"ice": _p_ice, "deep": _p_deep, "core": _p_core, "iron": _p_iron, "steel": _p_steel,
         "leather": _p_leather, "snow": _p_snow, "rune": _p_rune, "aurora": _p_aurora,
         "fracture": _p_fracture}


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
# Module contract
# ------------------------------------------------------------------------------------------

def textures() -> None:
    lay = _layout()
    images = {name: canvas(size) for name, size in lay["atlases"].items()}
    for j in lay["jobs"]:
        j.img = images[j.atlas]
        j.px = j.img.load()
        j.rng = random.Random(j.seed)
        PAINT[j.mat](j)
    for j in lay["jobs"]:
        _bleed(images[j.atlas], j)
    for name, image in images.items():
        save(image, name)


def _third_person(base: dict) -> dict:
    """The vanilla-derived third-person pose, rolled about the handle by TP_ROLL with the
    grip kept in the fist."""
    import numpy as np
    rot = np.asarray(display_matrix(base["rotation"]))
    frame = hand_frame("item")
    world = frame[:3, :3] / 0.9375 @ rot
    y_axis, z_axis = world @ np.array([0.0, 1.0, 0.0]), world @ np.array([0.0, 0.0, 1.0])
    g = np.asarray(GRIP) / 16 - 0.5
    grip_world = frame[:3, :3] @ (rot @ (g * base["scale"][0]) + np.asarray(base["translation"]) / 16) \
        + frame[:3, 3]
    k = y_axis / np.linalg.norm(y_axis)
    a = math.radians(TP_ROLL)
    z_rolled = z_axis * math.cos(a) + np.cross(k, z_axis) * math.sin(a) + k * np.dot(k, z_axis) * (1 - math.cos(a))
    return place({"y": tuple(y_axis), "z": tuple(z_rolled)}, GRIP, tuple(grip_world), base["scale"][0])


def models() -> dict:
    parts = _layout()["elements"]
    disp = display(KIND, parts, grip=GRIP, size=SIZE)
    disp["thirdperson_righthand"] = _third_person(disp["thirdperson_righthand"])
    # First person: handle leaning up and left, the core turned toward the player, the whole
    # head in the right third of the screen and clear of the crosshair.
    disp["firstperson_righthand"] = place({"y": (-0.3, 0.93, -0.2), "z": (-0.45, 0.0, 0.9)}, GRIP,
                                          (0.6, -0.55, -0.9), 0.68 * SIZE * FP_SIZE, pose=None)
    main = model(parts, disp)
    main["textures"] = {"particle": "#ice"}      # break particles in glacial ice, not the glow atlas
    return {"main": main}
