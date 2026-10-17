"""Viking Boomerang: a carved wooden throwing stick with built-in (hidden) Loyalty III.

Two broad arms of unequal length sweep up from a rounded elbow in a curved crescent and
thin out to rounded tips. Each arm is stacked from three layers (a weathered rim, the
body and a low crest down the middle) so it reads as a thick carved airfoil, not a flat
plank. The face of each arm is painted as one unrolled strip: warm oak crest, darker
weathered rims with nicks, a recessed channel carved with Elder Futhark runes, an
interlace band by the grip and chevron grooves at the tips. The elbow is bound in wound
dark leather under a laced knot, closed by thin bronze collars.

The model is built in the trident's upright frame (elbow at the bottom, arms rising),
with the wooden body centred on (8, 8, 8) so the thrown ItemDisplay spins it about its
own middle.
"""
from __future__ import annotations

import math
import random

from PIL import Image, ImageEnhance

from art.kit import (bar, bounds, box, canvas, copy, display, display_for_state, fill, model, move, place, rgba,
                     save, turn)

ID = "viking_boomerang"
NAME = "Viking Boomerang"
KIND = "trident"
COUNTERPART = "item/trident"

SIZE = 0.74            # in-hand scale

# --------------------------------------------------------------------------------------
# Palettes (leather and bronze match the Nordic set)
# --------------------------------------------------------------------------------------
# Weathered wood: shadows lean red-brown, highlights warm yellow oak.
WOOD = ["#26120b", "#3b1d10", "#552b17", "#6f3d20", "#88512a", "#a16835", "#b98244", "#ce9e5c", "#e2bd7e"]
LEATHER = ["#24160f", "#3a2519", "#523523", "#6b4730", "#875c40", "#a37755"]
BRONZE = ["#3e2712", "#61401c", "#875c27", "#a97934", "#c49646", "#ddb768", "#f1dba0"]
CORD = "#c8b38a"

# --------------------------------------------------------------------------------------
# Shape
# --------------------------------------------------------------------------------------
ARMS = {1: 15.0, -1: 13.2}   # arm length along its curved centreline; the right arm is longer
SEGMENTS = 17
TURN = 46.0                  # degrees each arm curls upward from the elbow to the tip
START = 9.0                  # the arms already rise this steeply at the elbow (degrees)
W_ELBOW, W_TIP = 6.2, 3.1
TIP_ROUND = 0.2              # last share of the arm that rounds off
GRIP_LEN = 3.0               # leather binding reaches this far out along each arm
# (width share, back z, front z) of the three stacked layers; +Z is the carved face.
LAYERS = ((1.00, 7.3, 8.7), (0.84, 7.02, 9.0), (0.56, 7.3, 9.3))


def _width(t: float) -> float:
    w = W_ELBOW + (W_TIP - W_ELBOW) * min(1.0, t / (1 - TIP_ROUND)) ** 0.8
    if t > 1 - TIP_ROUND:
        k = (t - (1 - TIP_ROUND)) / TIP_ROUND
        w *= math.sqrt(max(0.0, 1 - k * k * 0.75))
    return max(1.0, w)


def _z(z: float, t: float) -> float:
    """Layer depth z at arm share t: the wood thins toward the tips like a carved airfoil."""
    thin = 1 - 0.5 * max(0.0, (t - 0.4) / 0.6)
    return 8 + (z - 8) * thin


_LINES: dict[float, list] = {}


def _line(length: float):
    """Points along an arm from the elbow (s = 0) to the tip, with the heading at each.
    The bend is spread along the arm, a little tighter at the elbow, so it reads as one
    curve rather than two sticks."""
    if length not in _LINES:
        n = 400
        ds = length / n
        weights = [math.exp(-1.5 * i / n) + 0.4 for i in range(n)]
        k = math.radians(TURN) / (sum(weights) * ds)
        x, y, a = 0.0, 0.0, math.radians(START)
        pts = [(0.0, 0.0, a)]
        for i in range(n):
            a += k * weights[i] * ds
            x += math.cos(a) * ds
            y += math.sin(a) * ds
            pts.append((x, y, a))
        _LINES[length] = pts
    return _LINES[length]


def _at(side: int, s: float):
    line = _line(ARMS[side])
    i = max(0, min(len(line) - 1, round(s / ARMS[side] * (len(line) - 1))))
    return line[i]


def _stations(length: float) -> list[float]:
    """Segment ends along an arm, closer together near the elbow where it bends most."""
    return [length * (k / SEGMENTS) ** 1.2 for k in range(SEGMENTS + 1)]


def _arm(side: int) -> list[dict]:
    """side +1 is the right arm (+X), -1 the left. Faces sample the unrolled strips so the
    carving runs continuously across segments; u is a share of the local width with the
    outer (lower) edge at u = 16, v runs from the tip (0) to the elbow (16)."""
    length = ARMS[side]
    tex = "face_r" if side > 0 else "face_l"
    stations = _stations(length)
    parts = []
    for li, (share, lz0, lz1) in enumerate(LAYERS):
        u0, u1 = 8 - 8 * share, 8 + 8 * share
        for k in range(SEGMENTS):
            s0, s1 = stations[k], stations[k + 1]
            if li == 2 and s0 < GRIP_LEN - 0.6:
                continue                                 # the binding covers the crest here
            if li == 2 and s1 > length * 0.9:
                continue                                 # the crest stops short of the tip
            t = (s0 + s1) / 2 / length
            z0, z1 = _z(lz0, t), _z(lz1, t)
            x0, y0, _ = _at(side, s0)
            x1, y1, _ = _at(side, s1)
            w = _width(t) * share
            # Overlap neighbours a little so the curve shows no gaps.
            dx, dy = x1 - x0, y1 - y0
            ext = 0.15 if k else 0.0
            p0 = (8 + side * (x0 - dx * ext), y0 - dy * ext, (z0 + z1) / 2)
            p1 = (8 + side * (x1 + dx * 0.05), y1 + dy * 0.05, (z0 + z1) / 2)
            va, vb = 16 * (1 - s1 / length), 16 * (1 - s0 / length)
            # Local +x of the bar is the outer edge on the right arm, the inner edge on the left.
            fu0, fu1 = (u0, u1) if side > 0 else (u1, u0)
            bu0, bu1 = (u1, u0) if side > 0 else (u0, u1)
            faces = {"south": (tex, [fu0, va, fu1, vb]),
                     "north": ("back", [bu0, va, bu1, vb]),
                     "east": ("edge", [0, va, 8, vb]), "west": ("edge", [8, va, 16, vb]),
                     "up": ("endgrain", [0, 0, 16, 4]), "down": ("endgrain", [0, 0, 16, 4])}
            skip = ("down",) if k == 0 and li < 2 else ()
            parts.append(bar(p0, p1, w, z1 - z0, tex, faces=faces, skip=skip))
    parts += _tip(side)
    return parts


def _tip(side: int) -> list[dict]:
    """Round the end off: two short bevelled bars splayed either side of the heading."""
    length = ARMS[side]
    x, y, a = _at(side, length * 0.97)
    w = _width(0.97) * 0.45
    z0, z1 = _z(LAYERS[1][1], 1.0), _z(LAYERS[1][2], 1.0)
    parts = []
    for spread in (-20, 20):
        h = a + math.radians(spread)
        p0 = (8 + side * x, y, 8.0)
        p1 = (8 + side * (x + math.cos(h) * 1.25), y + math.sin(h) * 1.25, 8.0)
        p0 = (p0[0], p0[1], (z0 + z1) / 2)
        p1 = (p1[0], p1[1], (z0 + z1) / 2)
        tex = "face_r" if side > 0 else "face_l"
        faces = {"south": (tex, [5, 0, 11, 1]), "north": ("back", [5, 0, 11, 1]),
                 "east": ("edge", [0, 0, 8, 1]), "west": ("edge", [8, 0, 16, 1]),
                 "up": ("endgrain", [0, 0, 16, 4]), "down": ("endgrain", [0, 0, 16, 4])}
        parts.append(bar(p0, p1, w, (z1 - z0) * 0.85, tex, faces=faces))
    return parts


def _cross_section(side: int, s: float, pad: float):
    """(centre, heading in degrees, width, back z, front z) of the arm at s, padded."""
    x, y, a = _at(side, s)
    t = s / ARMS[side]
    w = _width(t) + pad
    return (x, y), math.degrees(a), w, _z(LAYERS[1][1], t) - pad / 2, _z(LAYERS[2][2], t) - 0.2 + pad / 2


def _band(side: int, s: float, length: float, pad: float, tex: str, uv, tilt: float = 0.0) -> dict:
    (x, y), a, w, z0, z1 = _cross_section(side, s, pad)
    ar = math.radians(a)
    dx, dy = math.cos(ar) * length / 2, math.sin(ar) * length / 2
    zc = (z0 + z1) / 2
    p0 = (8 + side * (x - dx), y - dy, zc)
    p1 = (8 + side * (x + dx), y + dy, zc)
    faces = {f: (tex, uv) for f in ("north", "south", "east", "west")}
    faces.update({"up": (tex, [uv[0], uv[1], uv[2], uv[1] + 1]), "down": (tex, [uv[0], uv[1], uv[2], uv[1] + 1])})
    b = bar(p0, p1, w, z1 - z0, tex, faces=faces)
    if tilt:
        turn(b, side * tilt, "z", (8 + side * x, y, zc))
    return b


def grip() -> list[dict]:
    parts = []
    # Elbow block: fills the joint (and the notch inside the V) under the binding.
    (x, y), _, w, z0, z1 = _cross_section(1, 0.0, 0.3)
    parts.append(box((8 - 0.95, y - w / 2 + 0.35, z0), (8 + 0.95, y + w / 2 + 0.5, z1), "leather",
                     faces={f: ("leather", [0, 0, 16, 8]) for f in ("north", "south", "east", "west", "up", "down")}))
    # Wound leather out to each collar, the turns leaning alternately like a wrapped cord.
    for side in (1, -1):
        n = 6
        for i in range(n):
            s = 0.45 + (GRIP_LEN - 0.75) * (i + 0.5) / n
            row = 2 * (i % 4)
            parts.append(_band(side, s, 0.5, 0.36 + 0.08 * (i % 2), "leather", [0, row, 16, row + 2],
                               tilt=20 if i % 2 == 0 else -20))
        # Thin bronze collar closing the binding, and a narrow lip just outside it.
        parts.append(_band(side, GRIP_LEN, 0.5, 0.3, "bronze", [0, 0, 16, 5]))
        parts.append(_band(side, GRIP_LEN + 0.38, 0.22, 0.16, "bronze", [0, 8, 16, 11]))
    # A laced knot tied over the front of the binding, pinned with a bronze boss.
    (x, y), _, w, z0, z1 = _cross_section(1, 0.0, 0.3)
    knot = box((8 - 1.4, y - 1.4, z1 - 0.1), (8 + 1.4, y + 1.4, z1 + 0.4), "knot",
               faces={f: ("knot", [0, 0, 16, 16]) for f in ("north", "south")},
               uv={"east": [0, 0, 16, 2], "west": [0, 0, 16, 2], "up": [0, 0, 16, 2], "down": [0, 0, 16, 2]})
    parts.append(turn(knot, 45, "z", (8, y, z1)))
    parts.append(box((8 - 0.5, y - 0.5, z1 + 0.3), (8 + 0.5, y + 0.5, z1 + 0.65), "bronze",
                     faces={f: ("bronze", [0, 12, 4, 16]) for f in ("north", "south", "east", "west", "up", "down")}))
    return parts


def studs() -> list[dict]:
    """Bronze pins set through the arm just past each collar, front and back."""
    parts = []
    for side in (1, -1):
        s = GRIP_LEN + 1.5
        x, y, a = _at(side, s)
        cx = 8 + side * x
        t = s / ARMS[side]
        front, back = _z(LAYERS[2][2], t), _z(LAYERS[1][1], t)
        r = 0.4
        uv = {f: ("bronze", [8, 12, 12, 16]) for f in ("north", "south", "east", "west", "up", "down")}
        parts.append(box((cx - r, y - r, front - 0.05), (cx + r, y + r, front + 0.25), "bronze", faces=uv))
        parts.append(box((cx - r, y - r, back - 0.25), (cx + r, y + r, back + 0.05), "bronze", faces=uv))
    return parts


def build() -> tuple[list[dict], tuple[float, float, float]]:
    """The whole model, centred so the wooden body's middle sits on (8, 8, 8) (the thrown
    display spins about that point), and the elbow centre where the hand closes."""
    body = _arm(1) + _arm(-1)
    parts = body + grip() + studs()
    lo, hi = bounds(body)
    shift = (8 - (lo[0] + hi[0]) / 2, 8 - (lo[1] + hi[1]) / 2, 8 - (lo[2] + hi[2]) / 2)
    move(parts, *shift)
    return parts, (8.0 + shift[0], shift[1], 8.0)


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------
# Elder Futhark runes, 5 wide (across the arm) x 7 long (along it).
RUNES = {
    "fehu": ["#...#", "#..#.", "#.#..", "##...", "#....", "#....", "#...."],
    "uruz": ["###..", "#..#.", "#...#", "#...#", "#...#", "#...#", "#...#"],
    "thurisaz": ["#....", "##...", "#.#..", "#..#.", "#.#..", "##...", "#...."],
    "ansuz": ["#....", "##...", "#.#..", "##...", "#.#..", "#....", "#...."],
    "raidho": ["###..", "#..#.", "###..", "##...", "#.#..", "#..#.", "#...#"],
    "kenaz": ["...#.", "..#..", ".#...", "#....", ".#...", "..#..", "...#."],
    "gebo": ["#...#", ".#.#.", "..#..", "..#..", "..#..", ".#.#.", "#...#"],
    "tiwaz": ["..#..", ".###.", "#.#.#", "..#..", "..#..", "..#..", "..#.."],
    "algiz": ["#.#.#", ".###.", "..#..", "..#..", "..#..", "..#..", "..#.."],
    "othala": ["..#..", ".#.#.", "#...#", ".#.#.", "..#..", ".#.#.", "#...#"],
}
RUNE_ROW_R = ["fehu", "tiwaz", "raidho", "algiz", "ansuz"]
RUNE_ROW_L = ["othala", "kenaz", "uruz", "gebo", "thurisaz"]
CHANNEL = (10, 21)     # the recessed rune channel, strip columns
CHANNEL_Y = (16, 82)   # and rows (tip side to grip side)


def _wood_strip(seed: int, runes: list[str], carved: bool = True):
    """The unrolled face of one arm: x 0..31 across (31 = outer edge), y 0..127 from the
    tip (0) to the elbow (127)."""
    rng = random.Random(seed)
    img = canvas(32, 128)
    px = img.load()
    for y in range(128):
        for x in range(32):
            d = min(x, 31 - x)                          # distance to the nearest rim
            base = 1 if d <= 1 else 3 if d <= 4 else 4 if d <= 8 else 5
            # Light comes from the inner (upper) edge: the outer rim sits a step darker.
            if x > 26:
                base -= 1
            elif 2 <= x <= 4:
                base += 1                               # a worn, lit chamfer on the inner edge
            # Wavy grain lanes running the length of the arm.
            lane = math.sin((x + 2.2 * math.sin(y / 11.0 + seed)) * 1.35)
            if lane > 0.78:
                base += 1
            elif lane < -0.9 and d > 4:
                base -= 1
            px[x, y] = rgba(WOOD[max(0, min(8, base))])
    # A few long grain streaks and the odd pale fleck.
    for _ in range(15):
        x, y = rng.randrange(3, 29), rng.randrange(128)
        c = WOOD[3] if rng.random() < 0.7 else WOOD[7]
        for k in range(rng.randint(3, 9)):
            if y + k < 128:
                px[x, y + k] = rgba(c)
    # Knots, kept out on the flanks away from the carving: a dark eye with a lighter ring.
    for kx, ky in ((rng.randrange(5, 8), rng.randrange(30, 60)), (rng.randrange(24, 27), rng.randrange(60, 90))):
        for dy in range(-3, 4):
            for dx in range(-2, 3):
                r = (dx / 2.2) ** 2 + (dy / 3.2) ** 2
                if r < 0.35:
                    px[kx + dx, ky + dy] = rgba(WOOD[1])
                elif r < 1.0:
                    px[kx + dx, ky + dy] = rgba(WOOD[3])
    # Weathering: nicks chewed out of the outer rim, pale scuffs across the face.
    for _ in range(9):
        y = rng.randrange(4, 120)
        for k in range(rng.randint(1, 3)):
            if y + k < 128:
                px[31, y + k] = rgba(WOOD[0])
                px[30, y + k] = rgba(WOOD[1])
    for _ in range(6):
        x, y = rng.choice((rng.randrange(4, 9), rng.randrange(23, 27))), rng.randrange(8, 120)
        for k in range(rng.randint(2, 4)):
            if 0 <= x + k < 32 and y - k >= 0:
                px[x + k, y - k] = rgba(WOOD[6])
    if not carved:
        return img
    deep, lit = WOOD[1], WOOD[8]
    cx0, cx1 = CHANNEL
    cy0, cy1 = CHANNEL_Y
    # The rune channel: cut a step down into the crest, shadowed along its lit-side wall
    # and catching light on the far wall, so it reads as one carved band when small.
    for y in range(cy0, cy1 + 1):
        for x in range(cx0, cx1 + 1):
            px[x, y] = rgba(WOOD[3])
        px[cx0 - 1, y] = rgba(deep)
        px[cx0, y] = rgba(WOOD[2])
        px[cx1, y] = rgba(WOOD[5])
        px[cx1 + 1, y] = rgba(WOOD[7])
    for x in range(cx0 - 1, cx1 + 2):
        px[x, cy0 - 1] = rgba(deep)
        px[x, cy1 + 1] = rgba(WOOD[7])
    # Runes carved into the channel floor, each 5 across x 7 along, with a lit lip.
    y = cy0 + 4
    for name in runes:
        pts = [(13 + c, y + r) for r, row in enumerate(RUNES[name]) for c, ch in enumerate(row) if ch == "#"]
        cut = set(pts)
        for x, yy in pts:
            px[x, yy] = rgba(WOOD[1])
        for x, yy in pts:
            for nx, ny in ((x + 1, yy + 1), (x, yy + 1)):
                if (nx, ny) not in cut and cx0 < nx < cx1:
                    px[nx, ny] = rgba(WOOD[6])
        y += 13
    # Interlace band by the grip: two strands crossing over and under.
    for y in range(87, 104):
        t = (y - 87) / 17 * 2 * math.pi * 1.5
        for phase in (0.0, math.pi):
            x = round(15.5 + 4.6 * math.sin(t + phase))
            over = math.cos(t + phase) > 0
            for w in (0, 1):
                if cx0 < x + w < cx1 + 1:
                    px[x + w, y] = rgba(WOOD[7] if over else WOOD[3])
            if over and x + 2 <= cx1:
                px[x + 2, y] = rgba(WOOD[1])
    for y, c in ((86, deep), (104, WOOD[7])):
        for x in range(cx0, cx1 + 1):
            px[x, y] = rgba(c)
    # Chevrons cut across the arm near the tip.
    for y0 in (5, 9):
        for x in range(6, 26):
            y = int(y0 + abs(x - 15.5) // 3)
            px[x, y] = rgba(deep)
            px[x, y + 1] = rgba(lit)
    return img


def _small(image, size):
    """A box-filtered copy for the inventory model: at slot size the full strips alias
    into noise, the averaged ones keep the carved channel as one clean band."""
    small = image.resize(size, Image.BOX)
    # The inventory's flat lighting reads darker than the world: lift it a little.
    return ImageEnhance.Brightness(small).enhance(1.12)


def textures() -> None:
    face_r, face_l = _wood_strip(3, RUNE_ROW_R), _wood_strip(8, RUNE_ROW_L)
    back = _wood_strip(21, [], carved=False)
    px = back.load()
    for y in range(10, 110):
        px[15, y] = rgba(WOOD[1])
        px[16, y] = rgba(WOOD[5])
    for image, name in ((face_r, "face_r"), (face_l, "face_l"), (back, "back")):
        save(image, name)
        save(_small(image, (16, 32)), name + GUI_SUFFIX)

    # Edge (the cut sides), worn grain along the arm. Each side face uses one half
    # (u 0-7 and 8-15) with a lit chamfer toward the carved face and shadow toward the back.
    edge = canvas(16, 16)
    rng = random.Random(5)
    epx = edge.load()
    for y in range(16):
        for x in range(16):
            c = WOOD[3]
            if x in (0, 15):
                c = WOOD[1]
            elif x in (7, 8):
                c = WOOD[5]
            elif rng.random() < 0.16:
                c = WOOD[2]
            epx[x, y] = rgba(c)
    save(edge, "edge")

    end = canvas(16, 16)
    epx = end.load()
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, (y - 2) * 3)
            epx[x, y] = rgba(WOOD[4] if int(r) % 3 else WOOD[2])
    save(end, "endgrain")

    # Leather: two-row wraps (lit crest, shadowed seam) with a cord stitch every few rows.
    leather = canvas(32, 32)
    lpx = leather.load()
    for y in range(32):
        c = [LEATHER[4], LEATHER[2], LEATHER[5], LEATHER[3], LEATHER[4], LEATHER[1], LEATHER[3], LEATHER[2]][y % 8]
        for x in range(32):
            lpx[x, y] = rgba(c)
    for y in (2, 6):
        for x in range(1, 32, 4):
            lpx[x, y] = rgba(CORD)
    rng = random.Random(9)
    for _ in range(30):
        lpx[rng.randrange(32), rng.randrange(32)] = rgba(LEATHER[1] if rng.random() < 0.5 else LEATHER[5])
    save(leather, "leather")

    # Knot: a pale laced diamond over the binding.
    knot = canvas(16, 16, fill=LEATHER[3])
    kpx = knot.load()
    for i in range(16):
        for d in (-1, 0, 1):
            for x, y in ((i, i + d), (i, 15 - i + d)):
                if 0 <= y < 16:
                    kpx[x, y] = rgba(CORD if d == 0 else LEATHER[5])
    for x in range(16):
        kpx[x, 0] = rgba(LEATHER[4])
        kpx[x, 15] = rgba(LEATHER[1])
        kpx[0, x] = rgba(LEATHER[4])
        kpx[15, x] = rgba(LEATHER[1])
    for y in range(2):
        for x in range(16):
            kpx[x, y] = rgba(LEATHER[4] if y == 0 else LEATHER[3])
    save(knot, "knot")

    # Bronze: rows 0-4 collar (a quiet aged band with rivets), 8-10 lip, 12-15 small parts.
    bronze = canvas(16, 16)
    rows = [BRONZE[4], BRONZE[3], BRONZE[3], BRONZE[2], BRONZE[1], BRONZE[1], BRONZE[1], BRONZE[1],
            BRONZE[4], BRONZE[2], BRONZE[1], BRONZE[1]]
    for y, c in enumerate(rows):
        fill(bronze, (0, y, 15, y), c)
    bpx = bronze.load()
    for x in range(1, 16, 4):
        bpx[x, 1] = rgba(BRONZE[5])
        bpx[x, 2] = rgba(BRONZE[1])
    for x0 in (0, 4, 8, 12):
        fill(bronze, (x0, 12, x0 + 3, 15), BRONZE[3])
        bpx[x0, 12] = rgba(BRONZE[6])
        bpx[x0 + 1, 12] = rgba(BRONZE[5])
        bpx[x0, 13] = rgba(BRONZE[5])
        bpx[x0 + 3, 15] = rgba(BRONZE[0])
        bpx[x0 + 2, 15] = rgba(BRONZE[1])
    save(bronze, "bronze")


# --------------------------------------------------------------------------------------
# Models
# --------------------------------------------------------------------------------------

def models() -> dict:
    parts, grip_at = build()
    # Inventory: the classic boomerang icon, elbow up and the arms sweeping down.
    main = display(KIND, parts, grip=grip_at, size=SIZE, gui_rotation=GUI_ROTATION, gui_span=15.0)
    # First person: stood up at the bottom right, carved face turned to the camera, the
    # arms opening up and a little left so the whole curve shows beside the crosshair.
    main["firstperson_righthand"] = place({"y": (-0.3, 0.95, 0), "z": (0.35, 0.1, 1)}, grip_at,
                                          (0.54, -0.38, -0.9), 0.55, pose=None)
    throw_parts = copy(parts)
    throwing = display_for_state("trident", "throwing", throw_parts, grip=grip_at, size=SIZE)
    # Wound up for the throw: raised behind the shoulder, the arms reaching back and up.
    throwing["thirdperson_righthand"] = place({"y": (0, 0.75, -0.65), "z": (-1, 0, 0)}, grip_at, "fist",
                                              SIZE, pose="raised")
    throwing["firstperson_righthand"] = place({"y": (0.55, 0.8, 0.25), "z": (0.25, 0.15, 1)}, grip_at,
                                              (0.5, -0.3, -0.85), 0.5, pose=None)
    throwing["gui"] = main["gui"]
    # Inventory-only copy with the box-filtered strips (see _small()).
    gui_parts = copy(parts)
    for element in gui_parts:
        for face in element["faces"].values():
            if face["texture"][1:] in ("face_r", "face_l", "back"):
                face["texture"] += GUI_SUFFIX
    return {"main": model(parts, main), "throwing": model(throw_parts, throwing), "gui": model(gui_parts, main)}


GUI_ROTATION = (0, 0, 135)
GUI_SUFFIX = "_gui"
