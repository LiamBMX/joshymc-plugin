"""Void Drill [3x3]: a handheld void-powered mining drill.

A chunky dark-steel housing armoured in diamond-cyan plate, with a round porthole on each
face showing the swirling black-violet void core behind glass, finned heatsinks down both
flanks and glowing energy conduits down its corners. Chamfered cyan shoulders carry a 3x3
status matrix up to the collar. Below, a black rubber pistol grip (raked back, with a
cyan trigger inside a silver D-shaped guard) runs down to a battery pack. A keyless chuck
holds the long ogive spiral bit: steel flights with a glowing cyan cutting edge and violet
void light pooled in the flutes, running out to a diamond tip.

Animated: the bit's spiral scrolls so the bit spins (the chuck ribs turn with it, and a
thin glowing shell keeps the cutting edge burning in the dark), the void core swirls and
breathes with cyan sparks orbiting it, energy pulses climb the corner conduits toward the
bit, and the 3x3 matrix fills cell by cell.

The hull boxes are painted face by face into one atlas at 2 texels per model unit, so the
bevels, rivets, vents, bands and knurling land exactly on each part's edges.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, bounds, box, canvas, copy, display, fill, mirror, mix, model, place, rgba,
                     rotation_of, save, save_animation, sparkle, turn, wave)

ID = "void_drill"
NAME = "Void Drill [3x3]"
KIND = "pickaxe"
COUNTERPART = "item/diamond_pickaxe"

# --------------------------------------------------------------------------------------
# Palettes (darkest -> lightest), hand-tuned and hue-shifted
# --------------------------------------------------------------------------------------
STEEL = ["#06080e", "#0c1019", "#141a26", "#1d2533", "#283244", "#374359", "#4c5a73", "#687891", "#8f9fb8",
         "#c6d2e3"]
CYAN = ["#032029", "#053a48", "#085867", "#0c7c89", "#12a3ab", "#22cfd0", "#55ffff", "#9bfff5", "#d9fffa",
        "#ffffff"]
VOID = ["#020107", "#080314", "#110626", "#1c0a3b", "#2b0f54", "#3e156f", "#57208f", "#7630b0", "#9a4fd6",
        "#c381ff", "#e9cfff"]
RUBBER = ["#040508", "#090b10", "#0f1218", "#161a22", "#1f242e", "#2a303c"]

# --------------------------------------------------------------------------------------
# Layout (model units). Upright: the grip runs up +Y on x = z = 8, the bit at the top.
# The trigger side faces -X; the portholes face +Z (front) and -Z (back).
# --------------------------------------------------------------------------------------
HX0, HX1, HY0, HY1 = 2.5, 13.5, -1.0, 8.5   # housing outline (front view)
PX0, PX1 = 3.0, 13.0                        # the front plate, between the corner conduits
CX0, CX1 = 2.7, 13.3                        # housing core
ZF = (10.8, 11.4)                           # front plate; the back plate mirrors across z = 8
ZC = (5.2, 10.8)
WYF = 12.0                                  # front projection: u = x, v = WYF - y (2 texels/unit)
PORT = (8.0, 3.5)                           # porthole centre
PORT_IN, PORT_OUT = 2.3, 3.3                # bezel apothems
DECK = (4.5, 11.5, 8.5, 11.2)               # shoulder deck x0, x1, y0, y1
DECK_Z = (5.0, 11.0)
MATRIX = (6.75, 9.25, 8.6, 11.1)            # the 3x3 matrix inset in the deck (x0, x1, y0, y1)
FINS = ((0.5, 1.3), (2.5, 1.8), (4.5, 1.8), (6.5, 1.3))   # (centre y, reach)
FIN_T = 1.0

Y_COLLAR = (11.0, 12.0)
Y_SLEEVE = (12.0, 14.2)
Y_NOSE = (14.2, 15.0)
Y_BIT0, Y_TIP = 15.6, 31.8
BIT_N = 12                                  # facets round the bit
BIT_TW = 24                                 # texels round the bit in its texture (uv 0..12)
BIT_PROFILE = [(Y_BIT0, 2.8), (19.0, 2.9), (22.4, 2.65), (25.4, 2.15), (27.7, 1.5), (29.5, 0.9),
               (30.9, 0.38), (Y_TIP, 0.02)]

GRIP_Y = (-11.6, -1.4)
RAKE = 10.0                                 # the grip leans back like a pistol grip
RAKE_O = (8.0, -1.2, 8.0)
BAT_Y = (-15.4, -12.2)
BAND = (-13.3, -12.5)                       # the battery's cyan band (grip frame, before the rake)


def raked(p):
    a = math.radians(RAKE)
    dx, dy = p[0] - RAKE_O[0], p[1] - RAKE_O[1]
    return (RAKE_O[0] + dx * math.cos(a) - dy * math.sin(a), RAKE_O[1] + dx * math.sin(a) + dy * math.cos(a), p[2])


GRIP = raked((8.0, -7.0, 8.0))


def clamp(v, a=0.0, b=1.0):
    return max(a, min(b, v))


def put(img, x, y, colour):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), rgba(colour))


def solid(name, colour, size=16):
    save(canvas(size, fill=colour), name)


def rows(name, colours, size=32):
    """A texture painted in horizontal bands (row i gets colours[i], the last repeats)."""
    img = canvas(size)
    for y in range(size):
        fill(img, (0, y, size - 1, y), colours[min(y, len(colours) - 1)])
    save(img, name)
    return img


# --------------------------------------------------------------------------------------
# The hull atlas: every registered face gets its own region, painted from its placement
# --------------------------------------------------------------------------------------
DENS = 2
ATLAS_SIZE = 128
SIDES = ("north", "south", "east", "west", "up", "down")
FACE_VERTS = {  # corner order matches the uv corners (u0,v0) (u0,v1) (u1,v1) (u1,v0)
    "down": lambda a, b: [(a[0], a[1], b[2]), (a[0], a[1], a[2]), (b[0], a[1], a[2]), (b[0], a[1], b[2])],
    "up": lambda a, b: [(a[0], b[1], a[2]), (a[0], b[1], b[2]), (b[0], b[1], b[2]), (b[0], b[1], a[2])],
    "north": lambda a, b: [(b[0], b[1], a[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2]), (a[0], b[1], a[2])],
    "south": lambda a, b: [(a[0], b[1], b[2]), (a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], b[1], b[2])],
    "west": lambda a, b: [(a[0], b[1], a[2]), (a[0], a[1], a[2]), (a[0], a[1], b[2]), (a[0], b[1], b[2])],
    "east": lambda a, b: [(b[0], b[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (b[0], b[1], a[2])],
}
NORMAL = {"down": (0, -1, 0), "up": (0, 1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0),
          "east": (1, 0, 0)}


def _sub(a, b):
    return tuple(a[i] - b[i] for i in range(3))


def _dot(a, b):
    return sum(a[i] * b[i] for i in range(3))


def _mul(m, v):
    return tuple(sum(m[i][k] * v[k] for k in range(3)) for i in range(3))


class Face:
    """One face's region of the atlas and where that face sits in the model."""

    def __init__(self, e, side, mat, opt, x, y, tw, th, seed):
        self.mat, self.opt, self.side = mat, opt, side
        self.x, self.y, self.tw, self.th = x, y, tw, th
        self.rng = random.Random(seed)
        verts = FACE_VERTS[side](e["from"], e["to"])
        m, o = rotation_of(e)
        if o is None:
            def tm(p):
                return tuple(p)
        else:
            def tm(p):
                return tuple(v + o[i] for i, v in enumerate(_mul(m, _sub(p, o))))
        p0, p1, p3 = tm(verts[0]), tm(verts[1]), tm(verts[3])
        self.o, self.du, self.dv = p0, _sub(p3, p0), _sub(p1, p0)
        self.n = _mul(m, NORMAL[side])
        ys = [p0[1], p1[1], p3[1], p1[1] + self.du[1]]
        self.ymin, self.ymax = min(ys), max(ys)
        self.px = None
        self.sheet = "hull"
        self.calm = False                               # True while painting the icon's atlas

    def pos(self, i, j):
        fu, fv = (i + 0.5) / self.tw, (j + 0.5) / self.th
        return tuple(self.o[k] + self.du[k] * fu + self.dv[k] * fv for k in range(3))

    def t(self, i, j) -> float:
        """0 at the highest point of the face, 1 at the lowest (light comes from above)."""
        span = self.ymax - self.ymin
        return 0.5 if span < 1e-4 else clamp((self.ymax - self.pos(i, j)[1]) / span)

    def put(self, i, j, colour) -> None:
        if 0 <= i < self.tw and 0 <= j < self.th:
            self.px[self.x + int(i), self.y + int(j)] = rgba(colour)

    def texels(self):
        for j in range(self.th):
            for i in range(self.tw):
                yield i, j

    def fill(self, colour) -> None:
        for i, j in self.texels():
            self.put(i, j, colour)

    def shade(self, stops) -> None:
        """Bands down the face from its lit top to its shaded bottom."""
        for i, j in self.texels():
            self.put(i, j, stops[min(len(stops) - 1, int(self.t(i, j) * len(stops)))])

    def edges(self, light, dark, left=None) -> None:
        """Bevel the rim: edges nearer the light get `light`, the far ones `dark`; on
        vertical edges the left one is lit (texture left is the viewer's left)."""
        tw, th = self.tw, self.th
        flat = self.ymax - self.ymin < 1e-4
        for name, (i, j) in (("top", ((tw - 1) / 2, 0)), ("bottom", ((tw - 1) / 2, th - 1)),
                             ("left", (0, (th - 1) / 2)), ("right", (tw - 1, (th - 1) / 2))):
            t = 0.5 if flat else self.t(i, j)
            lit = (name in ("top", "left")) if flat or 0.34 <= t <= 0.66 else t < 0.34
            c = (left or light) if (lit and name == "left") else (light if lit else dark)
            if name == "top":
                for k in range(tw):
                    self.put(k, 0, c)
            elif name == "bottom":
                for k in range(tw):
                    self.put(k, th - 1, c)
            elif name == "left":
                for k in range(th):
                    self.put(0, k, c)
            else:
                for k in range(th):
                    self.put(tw - 1, k, c)

    def grain(self, colours, density=0.12, lo=2, hi=4) -> None:
        """Short brushed streaks along the face (deliberate runs, not speckle)."""
        if self.calm:
            return
        rng = self.rng
        for j in range(1, self.th - 1):
            i = rng.randrange(0, 3)
            while i < self.tw - 1:
                n = rng.randint(lo, hi)
                if rng.random() < density:
                    c = rng.choice(colours)
                    for k in range(i, min(self.tw - 1, i + n)):
                        self.put(k, j, c)
                i += n + rng.randint(1, 3)

    def rivets(self, light, mid, dark, inset=1) -> None:
        for sx, sy in ((inset, inset), (self.tw - inset - 2, inset), (inset, self.th - inset - 2),
                       (self.tw - inset - 2, self.th - inset - 2)):
            self.put(sx, sy, light)
            self.put(sx + 1, sy, mid)
            self.put(sx, sy + 1, mid)
            self.put(sx + 1, sy + 1, dark)


class Hull:
    """Collects faces to paint and packs them into atlas sheets as they are registered
    (skyline packing: each region drops to the lowest spot it fits, with a texel of
    bleed round it)."""

    def __init__(self):
        self.jobs: list[Face] = []
        self.sheets = ["hull"]
        self.sky = [0] * ATLAS_SIZE

    def alloc(self, tw, th):
        w, h = tw + 2, th + 2
        best = None
        for x in range(0, ATLAS_SIZE - w + 1):
            y = max(self.sky[x:x + w])
            if y + h <= ATLAS_SIZE and (best is None or y < best[1]):
                best = (x, y)
        if best is None:
            self.sheets.append(f"hull{len(self.sheets) + 1}")
            self.sky = [0] * ATLAS_SIZE
            best = (0, 0)
        x, y = best
        for i in range(x, x + w):
            self.sky[i] = y + h
        return self.sheets[-1], x + 1, y + 1

    def paint(self, e, mat, sides=None, **opt):
        """Give the faces of element e (all, or `sides`) their own painted regions."""
        k = 16 / ATLAS_SIZE
        for side in list(e["faces"]):
            if sides and side not in sides:
                continue
            frm, to = e["from"], e["to"]
            d = [to[i] - frm[i] for i in range(3)]
            w, h = {"north": (d[0], d[1]), "south": (d[0], d[1]), "east": (d[2], d[1]), "west": (d[2], d[1]),
                    "up": (d[0], d[2]), "down": (d[0], d[2])}[side]
            tw, th = max(1, round(w * DENS)), max(1, round(h * DENS))
            sheet, x, y = self.alloc(tw, th)
            face = e["faces"][side]
            face["uv"] = [round(x * k, 5), round(y * k, 5), round((x + tw) * k, 5), round((y + th) * k, 5)]
            face["texture"] = "#" + sheet
            face.pop("rotation", None)
            job = Face(e, side, mat, opt, x, y, tw, th, len(self.jobs) * 31 + 7)
            job.sheet = sheet
            self.jobs.append(job)
        return e


# --------------------------------------------------------------------------------------
# Hull painters
# --------------------------------------------------------------------------------------

def p_steel(f: Face) -> None:
    o = f.opt
    dark = o.get("dark", False)
    if f.n[1] > 0.6:
        f.fill(STEEL[5] if dark else STEEL[6])
        f.grain([STEEL[5], STEEL[7]], 0.1)
        f.edges(STEEL[7], STEEL[4])
        return
    if f.n[1] < -0.6:
        f.fill(STEEL[2])
        f.edges(STEEL[3], STEEL[1])
        return
    f.shade([STEEL[4], STEEL[4], STEEL[3], STEEL[3]] if dark else [STEEL[5], STEEL[5], STEEL[4], STEEL[4], STEEL[3]])
    f.grain([STEEL[3], STEEL[6]] if not dark else [STEEL[2], STEEL[5]], 0.1)
    win = o.get("window")
    if win and abs(f.n[2]) > 0.9:
        x0, x1, y0, y1 = win
        for i, j in f.texels():
            p = f.pos(i, j)
            if x0 - 0.5 <= p[0] <= x1 + 0.5 and y0 - 0.5 <= p[1] <= y1 + 0.5:
                inner = x0 <= p[0] <= x1 and y0 <= p[1] <= y1
                f.put(i, j, STEEL[1] if inner else (STEEL[6] if p[1] < y0 else STEEL[2]))
        for sx in (x0 - 1.6, x1 + 0.6):                   # vent slits either side of the matrix
            for i, j in f.texels():
                p = f.pos(i, j)
                if sx <= p[0] <= sx + 1.0 and y0 + 0.2 <= p[1] <= y1 - 0.2 and int((p[1] - y0) * 2) % 2 == 0:
                    f.put(i, j, STEEL[1])
    f.edges(STEEL[7], STEEL[2], left=STEEL[6])
    if o.get("rivets") and f.tw >= 8 and f.th >= 6:
        f.rivets(STEEL[8], STEEL[6], STEEL[2])


def p_flank(f: Face) -> None:
    """Housing flank: dark steel with a vent slot in every gap between the fins."""
    if abs(f.n[0]) < 0.9:
        p_steel(f)
        return
    f.shade([STEEL[4], STEEL[3], STEEL[3], STEEL[2]])
    gaps = [(a[0] + FIN_T / 2, b[0] - FIN_T / 2) for a, b in zip(FINS, FINS[1:])]
    gaps.insert(0, (HY0, FINS[0][0] - FIN_T / 2))
    for i, j in f.texels():
        p = f.pos(i, j)
        for g0, g1 in gaps:
            mid = (g0 + g1) / 2
            if 5.8 <= p[2] <= 10.2 and g1 - g0 > 0.6:
                if mid <= p[1] < mid + 0.5:
                    f.put(i, j, STEEL[0])
                elif mid - 0.5 <= p[1] < mid:
                    f.put(i, j, STEEL[1])
                elif mid + 0.5 <= p[1] < mid + 1.0 and g1 - g0 > 1.5:
                    f.put(i, j, STEEL[5])
    f.edges(STEEL[6], STEEL[1])


def p_armour(f: Face) -> None:
    """Diamond-cyan plate: a bright top, a bevelled rim, a facet split and a glint."""
    if f.n[1] > 0.6:
        f.fill(CYAN[7])
        for i, j in f.texels():
            if (i + j) % 9 in (0, 1) and 0 < i < f.tw - 1 and 0 < j < f.th - 1:
                f.put(i, j, CYAN[8])
        f.edges(CYAN[8], CYAN[6])
        return
    if f.n[1] < -0.6:
        f.fill(CYAN[3])
        f.edges(CYAN[4], CYAN[2])
        return
    f.shade([CYAN[6], CYAN[6], CYAN[5], CYAN[5]])
    if f.tw >= 5 and f.th >= 4:
        for i, j in f.texels():                       # the far facet of the cut falls into shade
            if (f.tw - 1 - i) + (f.th - 1 - j) < min(f.tw, f.th) * 0.6:
                f.put(i, j, CYAN[4])
        for k in range(3):                            # a glint across the lit corner
            f.put(1 + k, min(f.th - 2, 3) - k, CYAN[9] if k == 1 else CYAN[8])
    f.edges(CYAN[8], CYAN[3], left=CYAN[7])
    if f.opt.get("rivets") and f.tw >= 8 and f.th >= 6:
        f.rivets(STEEL[8], STEEL[6], STEEL[3])


def p_silver(f: Face) -> None:
    """Polished bright steel (the trigger guard, latches, conduit caps)."""
    if f.n[1] > 0.6:
        f.fill(STEEL[8])
        f.edges(STEEL[9], STEEL[7])
        return
    if f.n[1] < -0.6:
        f.fill(STEEL[4])
        f.edges(STEEL[5], STEEL[3])
        return
    f.shade([STEEL[8], STEEL[7], STEEL[7], STEEL[6], STEEL[5]])
    f.edges(STEEL[9], STEEL[4], left=STEEL[8])
    if f.tw >= 3 and f.th >= 3 and (f.n[2] > 0.5 or f.n[0] < -0.5):
        f.put(1, 1, STEEL[9])


def p_bezel(f: Face) -> None:
    """The porthole bezel: a polished ring, bright at the lip, its inner walls lit violet
    by the core; the bolt heads are separate parts."""
    cx, cy = PORT
    if abs(f.n[2]) > 0.9:                             # the ring's front
        for i, j in f.texels():
            p = f.pos(i, j)
            r = max(abs(p[0] - cx), abs(p[1] - cy), (abs(p[0] - cx) + abs(p[1] - cy)) / math.sqrt(2))
            k = (r - PORT_IN) / (PORT_OUT - PORT_IN)
            upper = p[1] > cy
            c = STEEL[9] if k < 0.3 else (STEEL[8] if upper else STEEL[7]) if k < 0.7 else \
                (STEEL[7] if upper else STEEL[5])
            f.put(i, j, c)
        return
    p = f.pos((f.tw - 1) / 2, (f.th - 1) / 2)
    inward = _dot(f.n, (p[0] - cx, p[1] - cy, 0.0)) < 0
    if inward and abs(f.n[2]) < 0.5:
        for i, j in f.texels():
            q = f.pos(i, j)
            front = abs(q[2] - 8) > ZF[1] - 8 + 0.15
            f.put(i, j, CYAN[6] if front else (VOID[7] if (i + j) % 5 else VOID[8]))
        return
    f.shade([STEEL[5], STEEL[4], STEEL[3]])


def p_bolt(f: Face) -> None:
    if abs(f.n[2]) > 0.9:
        f.fill(STEEL[6])
        f.put(0, 0, STEEL[9])
        f.put(f.tw - 1, f.th - 1, STEEL[3])
        return
    f.fill(STEEL[4])


def p_fin(f: Face) -> None:
    """Heatsink fin: lit top, shaded edge, a cyan tip that catches the core light."""
    reach = f.opt["reach"]
    tip = (CX1 - 8) + reach - 0.55

    def at_tip(i, j):
        return abs(f.pos(i, j)[0] - 8) > tip

    if abs(f.n[0]) > 0.9:                              # the tip face
        f.fill(CYAN[6])
        for i in range(f.tw):
            f.put(i, 0, CYAN[8])
            f.put(i, f.th - 1, CYAN[4])
        return
    if f.n[1] > 0.6:
        for i, j in f.texels():
            f.put(i, j, CYAN[7] if at_tip(i, j) else (STEEL[7] if j % 4 else STEEL[8]))
        return
    if f.n[1] < -0.6:
        for i, j in f.texels():
            f.put(i, j, CYAN[3] if at_tip(i, j) else STEEL[2])
        return
    for i, j in f.texels():
        top = f.t(i, j) < 0.5
        f.put(i, j, (CYAN[7] if top else CYAN[5]) if at_tip(i, j) else (STEEL[8] if top else STEEL[5]))


def p_rubber(f: Face) -> None:
    """Moulded grip rubber: a staggered grid of little lit bumps."""
    if abs(f.n[1]) > 0.6 or f.calm:
        f.shade([RUBBER[3], RUBBER[3], RUBBER[2]])
        f.edges(RUBBER[5], RUBBER[1])
        return
    for i, j in f.texels():
        off = (j // 3) % 2
        ci, cj = (i + off) % 3, j % 3
        c = {(0, 0): RUBBER[5], (1, 0): RUBBER[4], (0, 1): RUBBER[4], (1, 1): RUBBER[3]}.get((ci, cj), RUBBER[1])
        f.put(i, j, c)
    f.edges(RUBBER[4], RUBBER[0])


def p_knurl(f: Face) -> None:
    """The grip's side panels: diamond knurling in a thin steel frame."""
    if abs(f.n[2]) < 0.9:
        f.fill(STEEL[4])
        return
    if f.calm:
        f.fill(RUBBER[3])
        f.edges(STEEL[6], STEEL[3])
        return
    for i, j in f.texels():
        if (i + j) % 4 == 0 or (i - j) % 4 == 0:
            c = RUBBER[1]
        elif (i + j) % 4 == 1 and (i - j) % 4 == 3:
            c = RUBBER[5]
        else:
            c = RUBBER[3]
        f.put(i, j, c)
    f.edges(STEEL[6], STEEL[3])


def p_battery(f: Face) -> None:
    """Battery pack: dark steel, a bevelled cyan band, vents on the ends, corner screws."""
    if f.n[1] > 0.6:
        f.fill(STEEL[5])
        f.grain([STEEL[4], STEEL[6]], 0.1)
        f.edges(STEEL[7], STEEL[4])
        return
    if f.n[1] < -0.6:
        f.fill(STEEL[1])
        f.edges(STEEL[2], STEEL[0])
        return
    f.shade([STEEL[4], STEEL[4], STEEL[3], STEEL[3], STEEL[2]])
    for i, j in f.texels():
        y = f.pos(i, j)[1]
        if BAND[0] <= y <= BAND[1]:
            c = CYAN[8] if y > BAND[1] - 0.5 else (CYAN[3] if y < BAND[0] + 0.5 else CYAN[6])
            f.put(i, j, c)
    if abs(f.n[0]) > 0.9 and f.tw >= 8 and not f.calm:  # vent slits on the ends
        for i, j in f.texels():
            p = f.pos(i, j)
            if BAT_Y[0] + 0.6 <= p[1] <= BAND[0] - 0.4 and 6.2 <= p[2] <= 9.8 and int(p[2] * 2) % 3 == 0:
                f.put(i, j, STEEL[0])
    f.edges(STEEL[6], STEEL[1], left=STEEL[5])
    if abs(f.n[2]) > 0.9 and f.tw >= 8:
        for sx in (1, f.tw - 2):                       # screws in the lower corners
            f.put(sx, f.th - 2, STEEL[7])


def p_trigger(f: Face) -> None:
    f.shade([CYAN[7], CYAN[6], CYAN[5]])
    f.edges(CYAN[8], CYAN[4])


PAINTERS = {"steel": p_steel, "flank": p_flank, "armour": p_armour, "silver": p_silver, "bezel": p_bezel,
            "bolt": p_bolt, "fin": p_fin, "rubber": p_rubber, "knurl": p_knurl, "battery": p_battery,
            "trigger": p_trigger}


def paint_hull(hull: Hull, calm: bool = False) -> None:
    """Paint every registered face. calm=True paints the inventory icon's copy of the same
    layout, with the fine patterns (grain, bumps, knurling, vents) left out."""
    images = {name: canvas(ATLAS_SIZE) for name in hull.sheets}
    for n, f in enumerate(hull.jobs):
        f.px = images[f.sheet].load()
        f.rng = random.Random(n * 31 + 7)
        f.calm = calm
        PAINTERS[f.mat](f)
    for f in hull.jobs:                               # bleed each region one texel outward
        px = images[f.sheet].load()
        x0, y0, x1, y1 = f.x, f.y, f.x + f.tw - 1, f.y + f.th - 1
        for x in range(x0, x1 + 1):
            px[x, y0 - 1] = px[x, y0]
            px[x, y1 + 1] = px[x, y1]
        for y in range(y0 - 1, y1 + 2):
            px[x0 - 1, y] = px[x0, y]
            px[x1 + 1, y] = px[x1, y]
    for name, image in images.items():
        save(image, name + ("_gui" if calm else ""))


# --------------------------------------------------------------------------------------
# Textures: the bit (animated)
# --------------------------------------------------------------------------------------
# One period of the flight, from the cutting edge down: the cyan rim, the shaded underside,
# the violet-lit flute, then the steel upper face of the next flight rising to the rim.
FLIGHT = [CYAN[8], CYAN[6], CYAN[5], STEEL[2], STEEL[0], VOID[2], VOID[4], VOID[1], STEEL[4], STEEL[6],
          STEEL[7], STEEL[8]]
FLIGHT_BOLD = [CYAN[8], CYAN[6], STEEL[1], VOID[3], STEEL[6], STEEL[8]]
EDGE_ROWS = 3                                     # rows of the period that are cutting edge
TIP_TINT = {STEEL[4]: "#2a4a5c", STEEL[6]: "#4a8c98", STEEL[7]: "#63adb4", STEEL[8]: "#8fd6d8"}


def bit_frame(t: float, bold: bool = False, edge: bool = False):
    """One frame of the spiral: two flutes wound round the bit (uv 0..12 round it).
    Moving the pattern one texel round per frame turns the bit, so the flights appear
    to screw back toward the chuck, the way a spinning drill looks. edge=True keeps only
    the cyan cutting edge (the rest transparent) for the glowing shell over the bit."""
    img = canvas(32)
    px = img.load()
    shift = round(t * BIT_TW / 2)
    for y in range(32):
        g = y / 31.0                                   # 0 at the tip, 1 at the base
        for x in range(32):
            xx = (x % BIT_TW) - shift
            k = (2 * xx + 2 * y) % 24
            if edge and k // 2 >= EDGE_ROWS:
                continue
            c = FLIGHT_BOLD[k // 4] if bold else FLIGHT[k // 2]
            if g < 0.3:                                # the diamond tip runs hot
                c = {CYAN[8]: CYAN[9], CYAN[6]: CYAN[8], CYAN[5]: CYAN[7]}.get(c, TIP_TINT.get(c, c))
            if y <= 1:
                c = CYAN[8] if y == 0 else CYAN[7]      # the diamond point
            px[x, y] = rgba(c)
    return img


def paint_bit():
    save_animation(animate(lambda t: bit_frame(t), 12), "bit", frametime=1)
    save_animation(animate(lambda t: bit_frame(t, bold=True), 12), "bit_gui", frametime=1)
    save_animation(animate(lambda t: bit_frame(t, edge=True), 12), "bit_edge", frametime=1)
    rows("bit_base", [CYAN[7], CYAN[5], STEEL[5], STEEL[3]])
    solid("bit_cap", STEEL[2])
    rows("shank", [STEEL[6], STEEL[4], STEEL[3], STEEL[3], STEEL[2]])


# --------------------------------------------------------------------------------------
# Textures: the void core (animated)
# --------------------------------------------------------------------------------------

def vortex_frame(t: float, bright: bool = False, n: int = 16):
    """A black singularity with a pulsing cyan horizon, violet arms turning round it and
    three cyan sparks in orbit. The arms turn a third of a turn per loop (they are
    three-fold), the sparks a whole turn, so it loops without a seam."""
    img = canvas(n)
    px = img.load()
    c = (n - 1) / 2
    pulse = wave(t)
    for y in range(n):
        for x in range(n):
            dx, dy = x - c, y - c
            r = math.hypot(dx, dy) / (n / 2)
            a = math.atan2(dy, dx)
            arms = 0.5 + 0.5 * math.cos(3 * a + 7.0 * r - 2 * math.pi * t)
            if r < 0.19:
                col = VOID[0]
            elif r < 0.33:
                col = CYAN[9] if pulse > 0.6 else (CYAN[8] if pulse > 0.25 else CYAN[7])
                if r > 0.27:
                    col = mix(col, VOID[8], 0.5)
            else:
                fall = clamp(1.25 - r) ** 0.8
                v = fall * (0.3 + 0.7 * arms) * (0.78 + 0.3 * pulse)
                if bright:
                    v = min(1.0, v * 1.25 + 0.1)
                col = VOID[min(10, 1 + int(v * 9.6))]
                if v > 0.9:
                    col = mix(VOID[9], CYAN[7], clamp((v - 0.9) * 6))
            px[x, y] = rgba(col)
    for i in range(3):
        ang = 2 * math.pi * (i / 3 - t)
        sx = round(c + math.cos(ang) * 0.7 * n / 2)
        sy = round(c + math.sin(ang) * 0.7 * n / 2)
        sparkle(img, sx, sy, wave(t, i / 3), colour=CYAN[8], reach=1)
    return img


def paint_core():
    save_animation(animate(vortex_frame, 16), "core", frametime=2)
    save_animation(animate(lambda t: vortex_frame(t, bright=True), 16), "core_gui", frametime=2)
    glass = canvas(16, fill=(160, 255, 248, 30))
    for i, (x, y) in enumerate([(3, 6), (4, 5), (5, 4), (6, 3), (4, 7), (5, 6), (6, 5), (7, 4)]):
        put(glass, x, y, (236, 255, 252, 235 if i < 4 else 140))
    put(glass, 11, 12, (236, 255, 252, 200))
    put(glass, 12, 11, (236, 255, 252, 200))
    save(glass, "glass")


# --------------------------------------------------------------------------------------
# Textures: energy conduits and the 3x3 matrix (animated)
# --------------------------------------------------------------------------------------
PULSE = [1.0, 0.82, 0.62, 0.46, 0.34, 0.24, 0.16, 0.1, 0.06, 0.03, 0, 0, 0, 0, 0, 0]


def conduit_frame(t: float):
    """A pulse with a fading tail every 16 texels (8 units), climbing one texel a frame."""
    img = canvas(32)
    head = round(-t * 16) % 16
    for y in range(32):
        k = PULSE[(y - head) % 16]
        c = (CYAN[9] if k >= 0.99 else CYAN[8] if k > 0.7 else CYAN[6] if k > 0.4 else CYAN[5] if k > 0.2
             else CYAN[4] if k > 0.05 else CYAN[3])
        fill(img, (0, y, 31, y), c)
    return img


MATRIX_ORDER = [4, 1, 2, 5, 8, 7, 6, 3, 0]   # centre first, then round the ring


def matrix_frame(t: float):
    """The 3x3 matrix (5x5 texels): cells light one by one from the centre round the ring,
    all nine flare, then fade back to dim."""
    img = canvas(16, fill=STEEL[1])
    fill(img, (0, 0, 4, 4), STEEL[0])
    i = round(t * 16)
    for cell, order in enumerate(MATRIX_ORDER):
        cx, cy = cell % 3, cell // 3
        if i < 9:
            c = CYAN[8] if order == i else (CYAN[5] if order < i else CYAN[2])
        elif i < 12:
            c = CYAN[9] if i == 9 else (CYAN[8] if i == 10 else CYAN[6])
        else:
            c = [CYAN[5], CYAN[4], CYAN[3], CYAN[2]][i - 12]
        put(img, cx * 2, cy * 2, c)
    return img


def paint_energy():
    save_animation(animate(conduit_frame, 16), "conduit", frametime=2)
    save_animation(animate(matrix_frame, 16), "matrix", frametime=2)
    rows("charge", [CYAN[8], CYAN[6]])


# --------------------------------------------------------------------------------------
# Textures: the projected front plate and the chuck
# --------------------------------------------------------------------------------------

def paint_front(icon: bool = False):
    """The front plate, projected (u = x, v = WYF - y, 2 texels per unit): a bevelled field
    of diamond-cyan armour in a thin steel frame, with a facet glint, engraved corner
    brackets and rivets, and the porthole cut out so the core and glass behind show."""
    img = canvas(32)
    px = img.load()
    c0, c1 = int(PX0 * 2), int(PX1 * 2) - 1        # cols 6..25
    r0, r1 = int((WYF - HY1) * 2), int((WYF - HY0) * 2) - 1   # rows 7..25
    fill(img, (c0, r0, c1, r1), STEEL[3])
    fill(img, (c0, r0, c1, r0), STEEL[7])
    fill(img, (c0, r1, c1, r1), STEEL[1])
    fill(img, (c0, r0 + 1, c0, r1 - 1), STEEL[6])
    fill(img, (c1, r0 + 1, c1, r1 - 1), STEEL[2])
    a0, a1, b0, b1 = c0 + 1, c1 - 1, r0 + 1, r1 - 1  # cyan field cols 7..24, rows 8..24
    for ty in range(b0, b1 + 1):
        for tx in range(a0, a1 + 1):
            d = (tx - a0) + (ty - b0)
            col = CYAN[6] if d < 17 else CYAN[5]
            if d in (4, 5) or (not icon and d == 9):
                col = CYAN[7]                         # facet glints across the upper corner
            if d >= 17 and (tx - a0) - (ty - b0) > 12:
                col = CYAN[4]                         # the far facet falls into shade
            px[tx, ty] = rgba(col)
    fill(img, (a0, b0, a1, b0), CYAN[8])
    fill(img, (a0, b0 + 1, a0, b1), CYAN[7])
    fill(img, (a0 + 1, b1, a1, b1), CYAN[3])
    fill(img, (a1, b0 + 1, a1, b1 - 1), CYAN[4])
    if not icon:
        for (bx, by, sx, sy) in ((a0 + 1, b0 + 1, 1, 1), (a1 - 1, b0 + 1, -1, 1), (a0 + 1, b1 - 1, 1, -1),
                                 (a1 - 1, b1 - 1, -1, -1)):
            put(img, bx, by, STEEL[9] if sy > 0 else STEEL[7])   # rivet
            put(img, bx + sx, by + sy, STEEL[4])
            for i in range(2, 5):                                 # engraved corner bracket
                put(img, bx + sx * i, by, CYAN[4])
                put(img, bx, by + sy * i, CYAN[4])
    cx, cy = PORT[0] * 2, (WYF - PORT[1]) * 2
    for ty in range(32):
        for tx in range(32):
            if math.hypot(tx + 0.5 - cx, ty + 0.5 - cy) < (PORT_IN + 0.45) * 2:
                px[tx, ty] = (0, 0, 0, 0)
    save(img, "front_gui" if icon else "front")


def paint_chuck():
    img = canvas(32)
    for y, c in enumerate([STEEL[8], STEEL[6], STEEL[5], STEEL[4], STEEL[3]]):
        fill(img, (0, y, 31, y), c)
    fill(img, (0, 5, 31, 31), STEEL[3])
    for x in range(0, 32, 4):                         # bolt heads round the collar
        put(img, x, 1, STEEL[9])
        put(img, x + 1, 1, STEEL[7])
        put(img, x + 1, 2, STEEL[3])
    save(img, "collar")
    solid("ring", CYAN[6])

    def ribs(t):
        img = canvas(32, fill=STEEL[3])
        s = round(t * 4)
        for x in range(32):
            c = [STEEL[8], STEEL[6], STEEL[4], STEEL[2]][(x - s) % 4]
            fill(img, (x, 1, x, 3), c)
            if (x - s) % 12 in (0, 1):
                fill(img, (x, 1, x, 3), CYAN[6] if (x - s) % 12 == 0 else CYAN[4])
        fill(img, (0, 0, 31, 0), STEEL[8])
        fill(img, (0, 4, 31, 4), STEEL[2])
        return img

    save_animation(animate(ribs, 4), "chuck", frametime=1)
    rows("nose", [STEEL[8], STEEL[7], STEEL[5], STEEL[4]])
    solid("chuck_cap", STEEL[3])


def textures() -> None:
    paint_bit()
    paint_core()
    paint_energy()
    paint_front()
    paint_front(icon=True)
    paint_chuck()
    paint_hull(_layout()["hull"])
    paint_hull(_layout()["hull"], calm=True)


# --------------------------------------------------------------------------------------
# Geometry helpers
# --------------------------------------------------------------------------------------

def drum(y0, y1, a, n, tex, uspan=None, v=(0.0, None), cap=None, glow=0, cx=8.0, cz=8.0):
    """An n-sided drum (n a multiple of 4) on the axis, crossed slabs of apothem a. Side
    faces map u round the circumference (face k gets [k, k+1] * uspan / n), so a texture
    that scrolls in u makes the drum turn."""
    uspan = uspan or n
    half = a * math.tan(math.pi / n)
    v0 = v[0]
    v1 = v[1] if v[1] is not None else v0 + (y1 - y0)
    step = 360.0 / n
    parts = []
    for j in range(n // 4):
        ang = j * step
        for frm, to, sides in (((cx - a, y0, cz - half), (cx + a, y1, cz + half), ("east", "west")),
                               ((cx - half, y0, cz - a), (cx + half, y1, cz + a), ("south", "north"))):
            faces = {}
            for side in sides:
                phi = {"south": 0, "east": 90, "north": 180, "west": 270}[side] + ang
                k = int(round(phi / step)) % n
                faces[side] = (tex, [k * uspan / n, v0, (k + 1) * uspan / n, v1])
            skip = tuple(s for s in ("north", "south", "east", "west") if s not in sides)
            if cap:
                faces["up"] = (cap, [0, 0, 16, 16])
                faces["down"] = (cap, [0, 0, 16, 16])
            else:
                skip += ("up", "down")
            e = box(frm, to, tex, faces=faces, skip=skip, glow=glow)
            if ang:
                turn(e, ang, "y", (cx, 0, cz))
            parts.append(e)
    return parts


def cone(profile, n, tex, uspan, vfun, glow=None):
    """A smooth round taper of one-face plates, one per facet per segment, each tilted to
    its segment's slope (no terraces). u runs round the circumference, v along the length.
    glow may be a function of the segment's mid height."""
    parts = []
    tn = math.tan(math.pi / n)
    for (y0, a0), (y1, a1) in zip(profile, profile[1:]):
        length = math.hypot(y1 - y0, a0 - a1)
        beta = math.degrees(math.atan2(a0 - a1, y1 - y0))
        ym, am = (y0 + y1) / 2, (a0 + a1) / 2
        w = 2 * max(a0, a1) * tn + 0.03
        g = glow(ym) if callable(glow) else (glow or 0)
        for k in range(n):
            face = (tex, [k * uspan / n, vfun(y1), (k + 1) * uspan / n, vfun(y0)])
            p = box((8 - w / 2, ym - length / 2, 8 + am - 0.05), (8 + w / 2, ym + length / 2, 8 + am), tex,
                    faces={"south": face}, skip=("north", "east", "west", "up", "down"), glow=g)
            turn(p, -beta, "x", (8, ym, 8 + am))
            turn(p, k * 360.0 / n, "y", (8, ym, 8))
            parts.append(p)
    return parts


def pj_front(x0, y0, x1, y1):
    return [x0, WYF - y1, x1, WYF - y0]


def only(*sides):
    return tuple(s for s in SIDES if s not in sides)


# --------------------------------------------------------------------------------------
# Parts
# --------------------------------------------------------------------------------------

def bit() -> list[dict]:
    def vfun(y):
        return round((Y_TIP - y) * 16.0 / (Y_TIP - Y_BIT0), 4)

    parts = cone(BIT_PROFILE, BIT_N, "bit", BIT_TW / 2, vfun, glow=lambda y: 12 if y > 29.4 else 0)
    # a hair-thin shell over the flights carrying only the cutting edge at full glow, so
    # the spiral keeps burning cyan in the dark (in step with the bit's frames)
    shell = [(y, a + 0.05) for y, a in BIT_PROFILE if y <= 29.6]
    parts += cone(shell, BIT_N, "bit_edge", BIT_TW / 2, vfun, glow=15)
    parts += drum(Y_BIT0 - 0.45, Y_BIT0 + 0.05, 2.82, 12, "bit_base", cap="bit_cap")   # the flutes' end ring
    parts += drum(Y_NOSE[0], Y_BIT0 - 0.3, 1.2, 8, "shank", cap="bit_cap")           # shank in the chuck
    return parts


def chuck() -> list[dict]:
    parts = drum(*Y_COLLAR, 3.3, 12, "collar", cap="chuck_cap")
    parts += drum(Y_COLLAR[0] + 0.35, Y_COLLAR[0] + 0.6, 3.38, 12, "ring", cap="ring", glow=12)
    parts += drum(*Y_SLEEVE, 2.9, 12, "chuck", uspan=12, v=(0.0, 2.5), cap="chuck_cap")
    parts += drum(*Y_NOSE, 2.2, 12, "nose", cap="chuck_cap")
    return parts


def front_face(h: Hull) -> list[dict]:
    """Everything on the front of the housing; mirrored for the back."""
    plate = box((PX0, HY0, ZF[0]), (PX1, HY1, ZF[1]), "front",
                faces={"south": ("front", pj_front(PX0, HY0, PX1, HY1))}, skip=("north",))
    f = [h.paint(plate, "steel", sides=("east", "west", "up", "down"))]
    px_, py_ = PORT
    s = 2.7
    f.append(box((px_ - s, py_ - s, ZF[0] + 0.05), (px_ + s, py_ + s, ZF[0] + 0.07), "core",
                 faces={"south": ("core", [0, 0, 16, 16])}, skip=only("south"), glow=15, shade=False))
    f.append(box((px_ - s, py_ - s, ZF[1] - 0.12), (px_ + s, py_ + s, ZF[1] - 0.1), "glass",
                 faces={"south": ("glass", [0, 0, 16, 16])}, skip=only("south")))
    am = (PORT_IN + PORT_OUT) / 2
    wid = PORT_OUT - PORT_IN
    side_len = 2 * am * math.tan(math.pi / 8) + wid * math.tan(math.pi / 8) + 0.05
    z0, z1 = ZF[0] + 0.05, ZF[1] + 0.45
    zc, dz = (z0 + z1) / 2, z1 - z0
    for k in range(8):
        ang = math.radians(k * 45)
        mx, my = px_ + am * math.cos(ang), py_ + am * math.sin(ang)
        tx, ty = -math.sin(ang), math.cos(ang)
        p0 = (mx - tx * side_len / 2, my - ty * side_len / 2, zc)
        p1 = (mx + tx * side_len / 2, my + ty * side_len / 2, zc)
        f.append(h.paint(bar(p0, p1, wid, dz, "hull", skip=("north",)), "bezel"))
    for k in range(4):
        ang = math.radians(45 + 90 * k)
        r = am / math.cos(math.pi / 8) * 0.93
        bx, by = px_ + r * math.cos(ang), py_ + r * math.sin(ang)
        f.append(h.paint(box((bx - 0.4, by - 0.4, z1), (bx + 0.4, by + 0.4, z1 + 0.2), "hull", skip=("north",)),
                         "bolt"))
    x0, x1, y0, y1 = MATRIX
    f.append(box((x0, y0, DECK_Z[1]), (x1, y1, DECK_Z[1] + 0.12), "matrix",
                 faces={"south": ("matrix", [0, 0, 5, 5])}, skip=only("south"), glow=10))
    # glowing conduits down the housing's corners (world-anchored v so the pulses line up)
    for cx0, cx1 in ((HX0, PX0), (PX1, HX1)):
        for ya, yb in ((HY0 + 0.2, 3.75), (3.75, HY1 - 0.2)):
            v0 = (WYF - yb) % 8.0
            uv = [0, v0, 0.8, v0 + yb - ya]
            f.append(box((cx0, ya, ZF[1] - 0.8), (cx1, yb, ZF[1]), "conduit",
                         faces={"south": ("conduit", [0, v0, 0.5, v0 + yb - ya]), "east": ("conduit", uv),
                                "west": ("conduit", uv)}, skip=("north", "up", "down"), glow=15))
        f.append(h.paint(box((cx0 - 0.05, HY1 - 0.2, ZF[1] - 0.85), (cx1 + 0.05, HY1 + 0.05, ZF[1] + 0.05), "hull"),
                         "silver"))
        f.append(h.paint(box((cx0 - 0.05, HY0 - 0.05, ZF[1] - 0.85), (cx1 + 0.05, HY0 + 0.2, ZF[1] + 0.05), "hull"),
                         "steel"))
    return f


def housing(h: Hull) -> list[dict]:
    core = box((CX0, HY0, ZC[0]), (CX1, HY1, ZC[1]), "hull", skip=("north", "south", "up"))
    parts = [h.paint(core, "flank")]
    f = front_face(h)
    parts += f + mirror(f, "z", 8)
    # shoulder deck, with cyan 45 degree chamfers down to the housing
    x0, x1, y0, y1 = DECK
    parts.append(h.paint(box((x0, y0, DECK_Z[0]), (x1, y1, DECK_Z[1]), "hull", skip=("down",)), "steel",
                         window=MATRIX))
    s = (x0 - HX0) / math.sqrt(2)
    wedge = box((x0 - s, y0 - s, DECK_Z[0] + 0.05), (x0 + s, y0 + s, DECK_Z[1] - 0.05), "hull")
    turn(wedge, 45, "z", (x0, y0, 8))
    h.paint(wedge, "armour")
    parts += [wedge] + mirror([wedge], "x", 8)
    # heatsink fins down both flanks, a cyan cheek plate above them
    fins = []
    for yc, reach in FINS:
        fins.append(h.paint(box((CX1, yc - FIN_T / 2, 5.8), (CX1 + reach, yc + FIN_T / 2, 10.2), "hull",
                                skip=("west",)), "fin", reach=reach))
    fins.append(h.paint(box((CX1, 7.3, 5.6), (CX1 + 0.4, HY1 - 0.1, 10.4), "hull", skip=("west",)), "armour",
                        rivets=True))
    parts += fins + mirror(fins, "x", 8)
    return parts


def grip(h: Hull) -> list[dict]:
    y0, y1 = GRIP_Y
    g = [h.paint(box((6.0, y0, 6.7), (10.0, y1, 9.3), "hull", skip=("up", "down")), "rubber"),
         h.paint(box((6.4, y0, 6.3), (9.6, y1, 9.7), "hull", skip=("up", "down")), "rubber")]
    panel = [h.paint(box((6.7, y0 + 1.2, 9.7), (9.3, y1 - 1.4, 9.95), "hull", skip=("north",)), "knurl")]
    v0 = (WYF - (y1 - 1.8)) % 8.0
    panel.append(box((7.7, y0 + 1.6, 9.95), (8.3, y1 - 1.8, 10.05), "conduit",
                     faces={"south": ("conduit", [0, v0, 0.6, v0 + (y1 - y0 - 3.4)])}, skip=only("south"), glow=15))
    g += panel + mirror(panel, "z", 8)
    for yc in (-6.7, -8.5, -10.3):                    # finger ridges below the guard
        g.append(h.paint(box((5.55, yc - 0.6, 6.9), (6.0, yc + 0.6, 9.1), "hull", skip=("east",)), "rubber"))
    g.append(h.paint(box((10.0, -8.8, 6.9), (10.45, -3.6, 9.1), "hull", skip=("west",)), "rubber"))   # palm swell
    tail = box((9.4, -2.3, 6.6), (11.4, -1.3, 9.4), "hull")
    turn(tail, 16, "z", (9.4, -1.3, 8))               # beavertail over the web of the hand
    g.append(h.paint(tail, "steel"))
    g.append(h.paint(box((5.4, y0 - 0.2, 6.0), (10.9, y0 + 0.9, 10.0), "hull"), "armour"))           # heel flare
    # battery pack
    b0, b1 = BAT_Y
    g.append(h.paint(box((4.0, b0, 4.6), (12.0, b1, 11.4), "hull"), "battery"))
    g.append(h.paint(box((4.6, b1, 5.2), (11.4, y0 + 0.1, 10.8), "hull", skip=("down",)), "steel", dark=True))
    g.append(h.paint(box((4.3, b0 - 0.35, 4.9), (11.7, b0, 11.1), "hull", skip=("up",)), "steel", dark=True))
    face = [box((5.2, b0 + 0.9, 11.4), (10.8, b0 + 1.5, 11.5), "charge", glow=12, skip=("north",))]
    g += face + mirror(face, "z", 8)
    latch = [h.paint(box((12.0, b0 + 0.8, 6.6), (12.35, BAND[0] - 0.2, 9.4), "hull", skip=("west",)), "silver")]
    g += latch + mirror(latch, "x", 8)
    return turn(g, RAKE, "z", RAKE_O)


def trigger(h: Hull) -> list[dict]:
    """The chin under the housing's trigger side, the trigger and its D-shaped guard."""
    parts = [h.paint(box((3.9, -2.4, 6.4), (7.2, HY0 + 0.1, 9.6), "hull", skip=("up",)), "steel")]
    parts.append(h.paint(bar((6.2, -2.2, 8), (5.0, -3.0, 8), 1.0, 1.1, "hull", glow=8), "trigger"))
    parts.append(h.paint(bar((5.05, -2.8, 8), (5.3, -4.6, 8), 1.0, 1.1, "hull", glow=8), "trigger"))
    end = raked((6.1, -5.7, 8))
    pts = [(4.45, -2.2), (3.95, -3.8), (4.2, -5.0), (5.0, -5.75), (end[0] + 0.1, end[1])]
    for a, b in zip(pts, pts[1:]):
        parts.append(h.paint(bar((a[0], a[1], 8), (b[0], b[1], 8), 1.0, 1.5, "hull"), "silver"))
    return parts


# --------------------------------------------------------------------------------------
# Models
# --------------------------------------------------------------------------------------
_CACHE: dict = {}
ICON_SWAP = {"#bit": "#bit_gui", "#core": "#core_gui", "#front": "#front_gui", "#hull": "#hull_gui",
             "#hull2": "#hull2_gui"}
ICON_DROP = {"#glass", "#matrix", "#bit_edge"}


def _layout() -> dict:
    """Build the model once: its elements and the hull faces to paint."""
    if not _CACHE:
        h = Hull()
        parts = bit() + chuck() + housing(h) + grip(h) + trigger(h)
        _CACHE.update(parts=parts, hull=h)
    return _CACHE


def transforms(parts) -> dict:
    lo, hi = bounds(parts)
    extent = max(hi[i] - lo[i] for i in range(3))
    size = 1.2 / (extent / 16 * 0.85 * 0.9375)        # about 1.2 blocks in the hand
    d = display(KIND, parts, grip=GRIP, size=size, gui_span=15.8)
    # First person: rising from the lower right toward the crosshair, porthole to the camera.
    d["firstperson_righthand"] = place({"y": (-0.42, 0.82, -0.38), "z": (-0.45, 0.05, 0.9)}, GRIP,
                                       (0.6, -0.6, -0.97), 1.0 * size, pose=None)
    return d


def models() -> dict:
    parts = copy(_layout()["parts"])
    d = transforms(parts)
    # Inventory icon: the same sculpt with bolder spiral and core frames and without the
    # glass, matrix and glow shell, which only add noise at slot size.
    icon = []
    for e in copy(parts):
        if any(face["texture"] in ICON_DROP for face in e["faces"].values()):
            continue
        for face in e["faces"].values():
            face["texture"] = ICON_SWAP.get(face["texture"], face["texture"])
        icon.append(e)
    main = model(parts, d)
    main["textures"] = {"particle": "#front"}
    gui = model(icon, copy(d))
    gui["textures"] = {"particle": "#front"}
    return {"main": main, "gui": gui}
