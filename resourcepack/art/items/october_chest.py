"""October Chest (issue #1497, design in #1407): the monthly crate chest for October, a
haunted Halloween chest in pumpkin orange, midnight black, deep purple, dark oak and a
faint eerie green.

A body of dark oak boards (upright) in an ornate black iron frame: a plinth, a seam band
with a scalloped fringe, L brackets on every corner run and studded crosses, with deep
purple metal caps clamped over all eight corners. Twisted black-green vines climb both
side walls and the back, curling at their tips, and cobwebs hang in the front's upper
corners.

The lid is a carved pumpkin: its walls are bulging pumpkin ribs in layers (the middle rib
on each wall stands out furthest), and a big ribbed pumpkin dome rises from it to a
crooked stem with a curled tendril and a vine leaf. On the lid's front the middle rib is
carved into a jack-o'-lantern face (slanted eyes, a nose and a toothy grin) cut to the
pale flesh and lit from inside by a flickering candle.

The lock is a bat: a purple metal plate across the seam with two black bat wings spread
to the sides and a green glowing keyhole. Two small bats flutter on the front beside it.
The back hinges are black branches that curl into spirals on the lid.

Animation (one loop): the candle inside the pumpkin flickers, the keyhole breathes with a
sickly green light, and a cold glint passes over the purple metal.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block. Unlike the other crate chests (and September's leaf
mound with whole pumpkins on top): the lid itself is the carved pumpkin, with a glowing
face, a domed top and a curled stem.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM,  # noqa: F401
                                    corner_brackets, keyhole_glow, keyhole_shape, planks, put, shell)
from art.kit import animate, arc, bar, box, canvas, copy, display, lathe, mirror, model, save, save_animation, \
    shine, turn

ID = "october_chest"
NAME = "October Chest"

OAK = ["#160e08", "#22160c", "#2e1e11", "#3b2716", "#48311c", "#553b22", "#644729", "#755632"]
IRON = ["#060509", "#0d0b12", "#16131c", "#201b27", "#2b2533", "#383041", "#4a4054", "#5e5369"]
PURPLE = ["#160a24", "#24103a", "#351852", "#48226b", "#5c2e84", "#73409c", "#8f5ab8", "#b384d6"]
RIND = ["#4a1c04", "#702c07", "#93400b", "#b55510", "#cf6a16", "#e2821f", "#f09c34", "#f9ba5a"]
FLESH = ["#8a4a10", "#b46a1c", "#d4892c", "#e8a640", "#f4c262", "#fbdc8e"]
VINE = ["#1d2a16", "#27391c", "#324a22", "#3f5c2a", "#4d6e32", "#5e843c", "#749a4a"]
EERIE = ["#06140a", "#0b2a12", "#12461c", "#1d6a28", "#2f9638", "#52c450", "#9cf08a", "#e0ffd2"]
CANDLE = ["#3a0e02", "#7a2204", "#b84208", "#e66a10", "#fb9a24", "#ffc84a", "#ffe98a", "#fffbe0"]
WEB = "#d6d2dc"

STEM_TIP = 2.965           # the stem's cut top above the dome (sets the silhouette height)
DOME_TOP = LID_TOP + 4.1   # the pumpkin dome's top (the stem starts here)


# ======================================================================================
# Textures
# ======================================================================================

def boards(size: int = 32) -> object:
    return planks(OAK[1:], size=size, rows=4, seed=211, vertical=True, knots=4, seam_dark=0)


def iron(size: int = 16) -> object:
    """Black iron: a lit top edge, a dark bottom edge, fine hammer marks with a purple sheen."""
    img = canvas(size)
    rng = random.Random(213)
    for y in range(size):
        for x in range(size):
            tone = 6 if y == 0 else 5 if y < 3 else 3 if y < 12 else 2
            if y >= size - 2:
                tone = 1
            r = rng.random()
            if r < 0.12:
                tone = max(1, tone - 1)
            elif r > 0.94:
                tone = min(7, tone + 1)
            put(img, x, y, IRON[tone])
            if 3 < y < 12 and r > 0.985:
                put(img, x, y, PURPLE[3])
    return img


def stud(size: int = 16) -> object:
    """A domed black iron stud with a purple highlight."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 6)
            put(img, x, y, PURPLE[6] if d < 2.5 else IRON[6] if d < 6 else IRON[4] if d < 9 else IRON[1])
    return img


def purple(t: float) -> object:
    """Deep purple metal: bevelled edges, a few scratches, a cold glint crossing it."""
    img = canvas(16)
    rng = random.Random(217)
    for y in range(16):
        for x in range(16):
            if x == 0 or y == 0:
                tone = 6
            elif x == 15 or y == 15:
                tone = 1
            elif x == 1 or y == 1:
                tone = 5
            elif x == 14 or y == 14:
                tone = 2
            else:
                tone = 4 if (x * 3 + y * 5) % 13 else 3
                if rng.random() < 0.06:
                    tone = 5
            put(img, x, y, PURPLE[tone])
    return shine(img, t, colour="#e6d2ff", width=3.0, strength=0.6, pause=0.55)


def rind(size: int = 16) -> object:
    """Pumpkin rind across one rib: a rounded bulge from a soft dark edge to a bright middle,
    lit a little more on the left, with faint mottling (the lid's ribs and the dome)."""
    img = canvas(size)
    rng = random.Random(219)
    for y in range(size):
        for x in range(size):
            u = (x + 0.5) / size
            bulge = math.sin(math.pi * u) ** 0.8
            tone = 2 + bulge * 5.0 + (0.4 if u < 0.45 else -0.3)
            if x == 0 or x == size - 1:
                tone = 1.6
            r = rng.random()
            if r < 0.06:
                tone += 1
            elif r < 0.1:
                tone -= 1
            put(img, x, y, RIND[max(1, min(7, round(tone)))])
    return img


def groove(size: int = 16) -> object:
    """The dark grooves between the dome's ribs."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            put(img, x, y, RIND[1] if (x + y) % 5 else RIND[0])
    return img


def rind_top(size: int = 16) -> object:
    """The lid's top under the dome: ribs running in to the middle."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            a = math.atan2(y + 0.5 - 8, x + 0.5 - 8)
            k = (a / (2 * math.pi) * 10) % 1
            tone = 1 if k < 0.15 else 5 if k < 0.6 else 4
            put(img, x, y, RIND[tone])
    return img


def stem(size: int = 16) -> object:
    """The pumpkin's stem: dry, fibrous, dark green going brown."""
    img = canvas(size)
    rng = random.Random(223)
    for y in range(size):
        for x in range(size):
            tone = 5 if x < 4 else 4 if x < 9 else 3 if x < 13 else 2
            if x % 4 == 3:
                tone -= 2
            if rng.random() < 0.1:
                tone -= 1
            c = VINE[max(0, min(6, tone))] if y > 5 else OAK[max(0, min(7, tone + 1))]
            put(img, x, y, c)
    return img


def vine(size: int = 16) -> object:
    """A twisted vine: two strands spiralling round each other."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            u = (x + 0.5) / size
            twist = (u + y / 8.0) % 1
            tone = 5 if twist < 0.25 else 3 if twist < 0.5 else 4 if twist < 0.75 else 1
            put(img, x, y, VINE[tone])
    return img


def ivy_mask(x: int, y: int) -> bool:
    """A three-lobed ivy leaf in a 16x16 card, stalk at the bottom."""
    fx, fy = x + 0.5, y + 0.5
    if 7 <= fx <= 9 and fy > 12:
        return True
    lobes = ((8, 5.0, 4.0), (4.4, 8.6, 3.2), (11.6, 8.6, 3.2), (8, 9.0, 3.6))
    return any(math.hypot(fx - cx, fy - cy) < r for cx, cy, r in lobes)


def ivy(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            if not ivy_mask(x, y):
                continue
            edge = any(not (0 <= x + dx < 16 and 0 <= y + dy < 16) or not ivy_mask(x + dx, y + dy)
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            tone = 2 if edge else 5 if x < 8 and y < 9 else 4
            if x == 8 and 3 < y < 14:
                put(img, x, y, EERIE[3])
                continue
            put(img, x, y, VINE[tone])
    return img


# the jack-o'-lantern face, painted in a 32 x 16 box (the top half of a 32 px texture),
# which the model stretches over a 2:1 panel
def _face_cut(fx: float, fy: float, grow: float = 0.0) -> bool:
    """True inside one of the face's cuts (eyes, nose, mouth); grow widens them."""
    def tri(ax, ay, bx, by, cx, cy):
        def side(px, py, qx, qy, rx, ry):
            return (px - rx) * (qy - ry) - (qx - rx) * (py - ry)
        if grow:
            # widen by testing a few offsets
            return any(tri0(fx + ox, fy + oy, ax, ay, bx, by, cx, cy, side)
                       for ox in (-grow, 0, grow) for oy in (-grow, 0, grow))
        return tri0(fx, fy, ax, ay, bx, by, cx, cy, side)

    def tri0(px, py, ax, ay, bx, by, cx, cy, side):
        d1 = side(px, py, ax, ay, bx, by)
        d2 = side(px, py, bx, by, cx, cy)
        d3 = side(px, py, cx, cy, ax, ay)
        neg = d1 < 0 or d2 < 0 or d3 < 0
        pos = d1 > 0 or d2 > 0 or d3 > 0
        return not (neg and pos)

    # slanted eyes (the inner corner lower, a mean look), a small nose
    if tri(4.0, 2.0, 13.0, 6.2, 5.0, 7.4) or tri(28.0, 2.0, 19.0, 6.2, 27.0, 7.4):
        return True
    if tri(16.0, 6.6, 14.2, 9.0, 17.8, 9.0):
        return True
    # the grin: a crescent between two curves, with two teeth hanging into it
    g = grow
    top = 10.0 + 2.2 * ((fx - 16) / 13.0) ** 2 * -1 + 0.6
    bottom = 15.0 - 3.6 * ((fx - 16) / 13.0) ** 2
    if 3.0 - g <= fx <= 29.0 + g and top - g <= fy <= bottom + g and abs(fx - 16) < 13.0 + g:
        tooth = (9.5 <= fx <= 12.0 or 20.0 <= fx <= 22.5) and fy < top + 2.0 - g
        lower = 15.0 <= fx <= 17.5 and fy > bottom - 1.6 + g
        return not (tooth or lower)
    return False


def face_rim(size: int = 32) -> object:
    """The carved flesh round every cut (the pumpkin wall's cut faces)."""
    img = canvas(size)
    for y in range(16):
        for x in range(32):
            fx, fy = x + 0.5, y + 0.5
            if _face_cut(fx, fy, grow=1.0) and not _face_cut(fx, fy):
                lit = _face_cut(fx + 1.0, fy + 1.0)
                put(img, x, y, FLESH[4] if lit else FLESH[2])
    return img


def face(t: float) -> object:
    """The candle light inside the cuts: hottest low in the middle, flickering."""
    img = canvas(32)
    f = 0.62 + 0.18 * math.sin(2 * math.pi * t) + 0.12 * math.sin(2 * math.pi * 3 * t + 1.1) \
        + 0.08 * math.sin(2 * math.pi * 5 * t + 2.3)
    n = len(CANDLE)
    for y in range(16):
        for x in range(32):
            fx, fy = x + 0.5, y + 0.5
            if not _face_cut(fx, fy):
                continue
            d = math.hypot((fx - 16) / 18.0, (fy - 13) / 14.0)
            heat = (1.05 - d) * f + 0.25
            put(img, x, y, CANDLE[max(2, min(n - 1, int(heat * n)))])
    return img


def bat_mask(x: int, y: int) -> bool:
    """A bat with spread scalloped wings in a 16x16 card (wings to the edges)."""
    fx, fy = x + 0.5, y + 0.5
    # body and ears
    if math.hypot((fx - 8) / 1.6, (fy - 8.2) / 2.6) < 1:
        return True
    if (6.6 <= fx <= 7.6 or 8.4 <= fx <= 9.4) and 4.6 <= fy <= 6.2:
        return True
    # wings: an upper edge rising to the tips and a scalloped lower edge
    dx = abs(fx - 8)
    if dx < 1.2 or dx > 7.8:
        return False
    upper = 7.2 - dx * 0.55
    scallop = 9.4 + 1.1 * abs(math.sin(dx / 7.8 * math.pi * 1.5)) - dx * 0.25
    return upper <= fy <= scallop


def bat(size: int = 16) -> object:
    """A bat: black body, deep purple wing membranes with black finger bones, a lit top edge."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            if not bat_mask(x, y):
                continue
            dx = abs(x + 0.5 - 8)
            top = y == 0 or not bat_mask(x, y - 1)          # only the upper edge catches light
            bottom = y == 15 or not bat_mask(x, y + 1)
            if dx < 1.7:
                c = PURPLE[6] if top else IRON[1]           # ear tips lit, body dark
            elif top:
                c = PURPLE[6]
            elif bottom:
                c = IRON[0]                                 # dark scalloped hem
            elif int(dx) % 3 == 0:
                c = IRON[1]                 # wing fingers
            else:
                c = PURPLE[3] if y > 8 else PURPLE[4]
            put(img, x, y, c)
    put(img, 7, 7, EERIE[6])                # eyes
    put(img, 8, 7, EERIE[6])
    return img


def web(size: int = 32) -> object:
    """A corner cobweb: radial threads from the top left with sagging rings."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            r = math.hypot(x + 0.5, y + 0.5)
            if r > size - 1:
                continue
            a = math.degrees(math.atan2(y + 0.5, x + 0.5))       # 0..90
            spoke = min(abs(a - s) for s in (6, 24, 45, 66, 84)) * math.pi / 180 * r < 0.55
            ring = False
            for k in range(1, 5):
                base = k * 7.2
                sag = base + 1.4 * math.sin(math.radians(a * 4)) ** 2
                if abs(r - sag) < 0.5:
                    ring = True
            if spoke or ring:
                put(img, x, y, WEB if (x + y) % 3 else "#a8a2b2")
    return img


def plate(size: int = 16) -> object:
    """The bat lock's plate: black iron with a purple border and a cut keyhole."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            if x <= 1 or y <= 1 or x >= size - 2 or y >= size - 2:
                c = PURPLE[6] if (x == 0 or y == 0) else PURPLE[2] if (x == size - 1 or y == size - 1) else PURPLE[4]
            else:
                c = IRON[3] if (x + y) % 6 else IRON[4]
            put(img, x, y, c)
    keyhole_shape(img, EERIE[0])
    return img


def branch(size: int = 16) -> object:
    """Black bark for the branch hinges: ridged, with a purple-grey sheen on the ridges."""
    img = canvas(size)
    rng = random.Random(229)
    for y in range(size):
        for x in range(size):
            tone = 2 if x % 5 in (0, 1) else 4 if x % 5 == 3 else 3
            if rng.random() < 0.12:
                tone -= 1
            put(img, x, y, IRON[max(0, tone)] if (y + x) % 9 else OAK[3])
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(EERIE, t)


def textures() -> None:
    save(boards(), "boards")
    save(iron(), "iron")
    save(stud(), "stud")
    save(rind(), "rind")
    save(rind_top(), "rind_top")
    save(groove(), "groove")
    save(stem(), "stem")
    save(vine(), "vine")
    save(ivy(), "ivy")
    save(face_rim(), "face_rim")
    save(bat(), "bat")
    save(web(), "web")
    save(plate(), "plate")
    save(branch(), "branch")
    save_animation(animate(purple, 12), "purple", frametime=3)
    save_animation(animate(face, 16), "face", frametime=2, interpolate=True)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

# the lid's ribs across one wall, built on the front: (x0, x1, how far out past the lid)
RIBS = ((L0 + 1.2, 3.7, 0.16), (3.7, 12.3, 0.42), (12.3, L1 - 1.2, 0.16))


def lid_ribs() -> list[dict]:
    """Bulging pumpkin ribs on all four lid walls; the middle one on each wall sticks out
    furthest (it holds the face on the front)."""
    wall = []
    for (x0, x1, out) in RIBS:
        wall.append(box((x0, SEAM + 0.1, L1 - 0.05), (x1, LID_TOP + 0.05, L1 + out), "rind", uv="full",
                        skip=("north", "down")))
    # a thinner inner step on every rib, so all three bulge
    for (x0, x1, out) in RIBS:
        inset = (x1 - x0) * 0.2
        wall.append(box((x0 + inset, SEAM + 0.05, L1 + out - 0.02), (x1 - inset, LID_TOP + 0.02, L1 + out + 0.12),
                        "rind", uv="full", skip=("north", "down")))
    out = []
    for yaw in (0, 90, 180, 270):
        out += turn(copy(wall), yaw, "y", (8.0, 0.0, 8.0))
    return out


def dome() -> list[dict]:
    """The pumpkin's dome on the lid: a dark core with ten bulging ribs running from the
    lid's edge up to a crooked stem, a curled tendril and two vine leaves."""
    rx, ry = 6.55, DOME_TOP - LID_TOP            # the dome is a squashed half ellipsoid
    core = [(0.0, 6.3), (1.4, 6.1), (2.6, 5.0), (3.4, 3.4), (3.9, 1.6), (4.0, 0.6)]
    parts = lathe((8.0, LID_TOP, 8.0), core, "groove", sides=8, cap="rind_top", uv="full")
    rib = []
    steps = (0, 18, 36, 54, 70, 84)
    for k, (a0, a1) in enumerate(zip(steps, steps[1:])):
        p = []
        for a in (a0, a1):
            t = math.radians(a)
            p.append((8.0 + rx * math.cos(t), LID_TOP + ry * math.sin(t), 8.0))
        wide = 3.9 * math.cos(math.radians((a0 + a1) / 2)) + 0.7
        rib.append(bar(p[0], p[1], 1.1, wide, "rind", uv="full"))
    for i in range(10):
        parts += turn(copy(rib), i * 36 + 18, "y", (8.0, 0.0, 8.0))
    # the stem: a thick crooked stalk with ridges, a flared foot and a dry cut top
    s0 = (8.0, DOME_TOP - 0.5, 8.0)
    s1 = (8.5, DOME_TOP + 1.3, 7.8)
    s2 = (8.1, DOME_TOP + STEM_TIP, 7.35)
    parts += lathe((8.0, DOME_TOP - 0.35, 8.0), [(0.0, 1.5), (0.35, 1.0)], "stem", sides=8, cap="stem", uv="full")
    parts.append(bar(s0, s1, 1.35, 1.35, "stem"))
    parts.append(bar(s1, s2, 1.05, 1.05, "stem"))
    # the tendril: a vine leaving the stem's foot, arching over and curling into a spiral
    parts += arc((10.15, DOME_TOP + 0.2, 8.9), 1.55, 180, 20, 6, 0.3, 0.3, "vine", plane="xy")
    parts += arc((11.0, DOME_TOP + 0.73, 8.9), 0.72, 0, -280, 7, 0.27, 0.27, "vine", plane="xy")
    parts += arc((10.95, DOME_TOP + 0.68, 8.9), 0.34, -280, -520, 5, 0.24, 0.24, "vine", plane="xy")
    # vine leaves lying on the dome by the stem
    for (c, s, yaw, tilt) in (((6.2, DOME_TOP - 0.55, 8.9), 2.8, 30, -24), ((8.9, DOME_TOP - 0.4, 6.0), 2.1, 160, 20)):
        e = box((c[0] - s / 2, c[1], c[2] - s / 2), (c[0] + s / 2, c[1] + 0.02, c[2] + s / 2), "ivy", uv="full",
                skip=("north", "south", "east", "west"))
        turn(e, yaw, "y", c)
        turn(e, tilt, "x", c)
        parts.append(e)
    return parts


def jack_face() -> list[dict]:
    """The carved face on the front rib: the cut flesh, then the candle light inside."""
    z = L1 + 0.54
    x0, x1, y0, y1 = 3.9, 12.1, 10.0, 14.1
    uv = {"south": [0, 0, 16, 8]}
    return [
        box((x0, y0, z), (x1, y1, z + 0.02), "face_rim", uv=uv, skip=("north", "east", "west", "up", "down")),
        box((x0, y0, z + 0.03), (x1, y1, z + 0.05), "face", uv=uv, glow=13, shade=False,
            skip=("north", "east", "west", "up", "down")),
    ]


def frame() -> list[dict]:
    """Black iron round the body: plinth, seam band with a scalloped fringe, corner
    brackets, studs; deep purple caps clamped over the eight corners."""
    parts = [box((B0 - 0.22, 0.0, B0 - 0.22), (B1 + 0.22, 0.95, B1 + 0.22), "iron", skip=("down",)),
             box((B0 - 0.2, SEAM - 0.8, B0 - 0.2), (B1 + 0.2, SEAM, B1 + 0.2), "iron", skip=("up",))]
    # a scalloped fringe under the seam band on the front and sides (little hanging points)
    fringe = []
    for i in range(7):
        x = 1.6 + i * 2.13
        if 5.6 < x < 10.4:
            continue
        fringe.append(box((x - 0.45, SEAM - 1.35, B1 + 0.05), (x + 0.45, SEAM - 0.8, B1 + 0.2), "iron"))
    for yaw in (0, 90, -90):
        parts += turn(copy(fringe), yaw, "y", (8.0, 0.0, 8.0))
    parts += corner_brackets("iron", e=0.24, arm=1.2)[:16]            # body corners only
    # purple caps over the four bottom and four top corners of the body, and the lid's corners
    for cx in (B0, B1):
        for cz in (B0, B1):
            sx = 1 if cx == B0 else -1
            sz = 1 if cz == B0 else -1
            for (y0, y1) in ((0.0, 2.0), (SEAM - 2.2, SEAM + 0.05)):
                parts.append(box((cx - 0.3 * sx, y0, cz - 0.3 * sz), (cx + 1.9 * sx, y1, cz + 1.9 * sz),
                                 "purple", uv="full", skip=("down",) if y0 == 0 else ()))
                # a raised inner plate with a stud, so each cap is bevelled
                parts.append(box((cx - 0.42 * sx, y0 + 0.35, cz - 0.42 * sz), (cx + 1.35 * sx, y1 - 0.35, cz + 1.35 * sz),
                                 "purple", uv="full", skip=("down", "up")))
    for cx in (L0, L1):
        for cz in (L0, L1):
            sx = 1 if cx == L0 else -1
            sz = 1 if cz == L0 else -1
            parts.append(box((cx - 0.2 * sx, SEAM + 0.05, cz - 0.2 * sz), (cx + 1.35 * sx, LID_TOP + 0.25, cz + 1.35 * sz),
                             "purple", uv="full"))
            # a spike on each lid corner cap
            parts.append(bar((cx + 0.55 * sx, LID_TOP + 0.2, cz + 0.55 * sz),
                             (cx + 0.3 * sx, LID_TOP + 1.2, cz + 0.3 * sz), 0.6, 0.6, "purple", uv="full"))
    # studs down the middle of the side walls and on the front corners
    for y in (2.6, 4.6):
        for x in (B0 + 0.6, B1 - 0.6):
            parts.append(box((x - 0.22, y - 0.22, B1 + 0.26), (x + 0.22, y + 0.22, B1 + 0.36), "stud", uv="full"))
    return parts


def bat_card(c, s: float, z: float, spin: float = 0.0) -> list[dict]:
    """A small bat on a wall facing +Z: the wing card and a little body in relief."""
    x, y = c
    parts = [box((x - s / 2, y - s / 2, z), (x + s / 2, y + s / 2, z + 0.03), "bat", uv="full",
                 skip=("north", "east", "west", "up", "down")),
             box((x - s * 0.08, y - s * 0.16, z), (x + s * 0.08, y + s * 0.1, z + 0.16), "branch")]
    return turn(parts, spin, "z", (x, y, z))


def bat_lock() -> list[dict]:
    """The lock: a purple-bordered plate across the seam, black bat wings spread from it,
    a green keyhole and a hasp from the lid's rib."""
    zf = 16.02
    parts = [box((6.3, 5.8, B1 - 0.05), (9.7, SEAM + 0.5, zf), "plate",
                 uv={"north": [0, 0, 4, 4], "east": [0, 0, 1, 4], "west": [0, 0, 1, 4], "up": [0, 0, 4, 1],
                     "down": [0, 0, 4, 1], "south": [0, 0, 16, 16]})]
    # the keyhole's light set just in front of the cut
    parts.append(box((7.45, 6.6, zf), (8.55, 8.1, zf + 0.03), "keyhole", glow=12, shade=False, uv="full"))
    parts.append(box((7.6, 8.0, zf), (8.4, 9.0, zf + 0.03), "keyhole", glow=12, shade=False, uv="full"))
    # bat wings: three swept fingers on each side with a membrane card between them
    wing = []
    for (ang, ln) in ((28, 3.2), (6, 3.6), (-16, 2.9)):
        a = math.radians(ang)
        wing.append(bar((9.6, 8.6, zf + 0.06), (9.6 + ln * math.cos(a), 8.6 + ln * math.sin(a), zf + 0.06),
                        0.3, 0.12, "purple", uv="full"))
    wing.append(box((9.6, 6.9, zf), (12.8, 10.4, zf + 0.02), "bat", uv={"south": [8, 2, 16, 12]},
                    skip=("north", "east", "west", "up", "down")))
    parts += wing + mirror(wing, "x", 8.0)
    # hasp: a purple tab from the face rib down over the plate's top, with a ring
    parts.append(box((7.2, SEAM - 0.1, zf), (8.8, SEAM + 0.6, zf + 0.16), "purple", uv="full"))
    parts += arc((8.0, SEAM - 0.45, zf + 0.12), 0.42, 180, 360, 4, 0.16, 0.16, "iron", plane="xy")
    return parts


def cobwebs() -> list[dict]:
    """Corner webs in the front's upper corners, and on both side walls' back corners."""
    out = []
    s = 3.6
    for (x0, flip) in ((B0 + 1.9, False), (B1 - 1.9 - s, True)):
        uv = [16, 0, 0, 16] if flip else [0, 0, 16, 16]
        out.append(box((x0, SEAM - 0.85 - s, B1 + 0.06), (x0 + s, SEAM - 0.85, B1 + 0.08), "web",
                       uv={"south": uv}, skip=("north", "east", "west", "up", "down")))
    side = box((B0 - 0.06, SEAM - 0.85 - 2.8, B0 + 1.9), (B0 - 0.04, SEAM - 0.85, B0 + 4.7), "web",
               uv={"west": [16, 0, 0, 16]}, skip=("north", "south", "east", "up", "down"))
    out.append(side)
    out += mirror([side], "x", 8.0)
    return out


def twisted_vine(points, leaves) -> list[dict]:
    """A twisted vine along points (each a bar), curling at the top, with ivy leaves."""
    parts = []
    for p0, p1 in zip(points, points[1:]):
        parts.append(bar(p0, p1, 0.32, 0.32, "vine"))
    for (c, s, spin) in leaves:
        x, y, z = c
        e = box((x - s / 2, y - s / 2, z), (x + s / 2, y + s / 2, z + 0.02), "ivy", uv="full",
                skip=("north", "east", "west", "up", "down"))
        parts.append(turn(e, spin, "z", c))
    return parts


def vines() -> list[dict]:
    """Vines twisting up both side walls and the back (built on the front plane, turned)."""
    z = B1 + 0.12
    pts = [(4.0, 0.9, z), (5.4, 2.4, z), (4.6, 3.9, z), (6.2, 5.2, z), (5.6, 6.5, z), (7.0, 7.4, z)]
    curl = arc((7.7, 7.4, z), 0.7, 180, 470, 6, 0.26, 0.26, "vine", plane="xy")
    leaves = (((5.7, 2.0, z + 0.05), 1.7, -30), ((4.3, 4.4, z + 0.05), 1.5, 35), ((6.6, 5.6, z + 0.05), 1.6, -20))
    left = twisted_vine(pts, leaves) + curl
    wall = left + mirror(copy(left), "x", 8.0)
    # each side wall gets the pair, shifted so it frames the wall's middle; the back too
    out = []
    for yaw in (90, -90, 180):
        out += turn(copy(wall), yaw, "y", (8.0, 0.0, 8.0))
    return out


def branch_hinges() -> list[dict]:
    """Two black branch hinges at the back: a knotted knuckle on the seam, a branch up the
    lid's back curling into a spiral, and a short branch down the body."""
    parts = []
    z = L0 - 0.48
    for x in (4.0, 12.0):
        parts.append(box((x - 1.4, SEAM - 0.5, z), (x + 1.4, SEAM + 0.5, L0), "branch"))
        for dx in (-0.75, 0.75):
            parts.append(box((x + dx - 0.18, SEAM - 0.58, z - 0.02), (x + dx + 0.18, SEAM + 0.58, L0), "purple",
                             uv="full"))
        parts.append(bar((x, SEAM - 2.8, B0 - 0.2), (x + 0.3, SEAM - 0.3, B0 - 0.2), 0.7, 0.36, "branch"))
        parts.append(bar((x, SEAM + 0.3, z + 0.1), (x - 0.25, SEAM + 2.6, z + 0.1), 0.7, 0.4, "branch"))
        parts += arc((x + 0.55, SEAM + 3.05, z + 0.1), 0.8, 180, 470, 6, 0.42, 0.38, "branch", plane="xy")
    return parts


def front_bats() -> list[dict]:
    return bat_card((4.1, 3.8), 4.8, B1 + 0.06, spin=12) + bat_card((12.0, 3.3), 4.2, B1 + 0.06, spin=-14)


def models() -> dict:
    p = shell("boards", "rind", "rind_top", faces_lid={"north": "rind", "south": "rind", "east": "rind",
                                                         "west": "rind"})
    p += frame()
    p += lid_ribs()
    p += dome()
    p += jack_face()
    p += bat_lock()
    p += front_bats()
    p += cobwebs()
    p += vines()
    p += branch_hinges()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
