"""Rudd: a common fish of the JoshyMC fishing collection (rarity COMMON).

A flat 32x32 sprite in the collection's pose: side view, head upper-left, tail lower-right.
The rudd (Scardinius erythrophthalmus) is drawn from its field marks: a deep, humped
body with big brassy-gold scales over an olive back and a cream belly, a small upturned
mouth, a red-gold eye, blood-red pelvic, anal and forked tail fins, and a dorsal fin set
well behind the pelvic fins. It keeps the old icon's warm tan, bronze and red scheme.

The fish is modelled in its own frame (standard lengths along the body, rotated into the
pose), rasterised with supersampling, then painted with fixed hue-shifted palettes: a lit
rim on the back, a bronze back, a bright brass band over the upper flank, gold fading to
amber, a cream belly, and big scales from a 3x3 lattice that runs with the body. The head
never moves, so it is hand-painted pixel by pixel on top.

Animation (COMMON): 8 frames x 3 ticks, a 1.2 s loop. The tail flexes a pixel each way and
every fin sways about a pixel on its own phase, while a soft glint slides from the
forehead down the back to the tail and then rests. The glint steps the palette tones
instead of using shine(), so it stays on the palette and moves about 3 px per frame.
"""
from __future__ import annotations

import math

from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sprite

ID = "fish_rudd"
NAME = "Rudd"
KIND = "item"
MODEL_KEY = "fish/rudd"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 8
FRAMETIME = 3
ANGLE = math.radians(36.0)   # head up-left, tail down-right
SL = 22.5                    # standard length (snout to tail base) in pixels
SS = 4                       # supersamples per pixel side for the silhouette

# --------------------------------------------------------------------------------------
# Palettes, darkest -> lightest; shadows lean violet-brown, lights lean yellow
# --------------------------------------------------------------------------------------
BACK = ["#33231a", "#4d3620", "#6a4f27", "#86682e", "#a58439", "#c4a24d"]
FLANK = ["#6e3f22", "#955a2c", "#b77636", "#d39342", "#e6b056", "#f4cd76", "#fdeab0"]
BELLY = ["#b07e62", "#cda07e", "#e3c09c", "#f1dab9", "#fbefda"]
FIN = ["#6c1a18", "#9a241e", "#c23626", "#dc5030", "#ee7042", "#f9995e"]
DORSAL = ["#6a2019", "#91301f", "#b44427", "#cf5c30", "#e47a40", "#f39c5a"]
PECT = ["#a4512a", "#c96a30", "#e28a40", "#f2a95c", "#fcc98a"]
OUT_BODY = "#3d2419"
OUT_FIN = "#74201b"
PUPIL = "#1b1016"
CATCH = "#fffaf0"
IRIS = ["#d2402a", "#f0b43c"]
MOUTH = "#4e2619"

# --------------------------------------------------------------------------------------
# Anatomy, in standard lengths (x from the snout, y down; negative y is the back)
# --------------------------------------------------------------------------------------
TOP = [(0.000, -0.045), (0.030, -0.105), (0.070, -0.155), (0.120, -0.198), (0.180, -0.232),
       (0.250, -0.255), (0.320, -0.266), (0.400, -0.266), (0.480, -0.254), (0.560, -0.230),
       (0.640, -0.196), (0.720, -0.157), (0.800, -0.120), (0.870, -0.097), (0.940, -0.088),
       (1.000, -0.092)]
BOT = [(0.000, -0.030), (0.025, 0.004), (0.060, 0.038), (0.110, 0.072), (0.170, 0.104),
       (0.240, 0.134), (0.310, 0.155), (0.390, 0.167), (0.470, 0.168), (0.550, 0.160),
       (0.630, 0.142), (0.710, 0.120), (0.790, 0.100), (0.870, 0.088), (0.940, 0.085),
       (1.000, 0.090)]

# Fins: outline polygon, sway pivot, ray (base, tip) pairs, colour ramp, sway amplitude
# (degrees) and phase. The pectoral and pelvic fins are on the near side, over the body.
# They are drawn larger than life so they read at 32 px.
FINS = {
    "pectoral": dict(
        poly=[(0.225, 0.085), (0.29, 0.085), (0.35, 0.11), (0.405, 0.15), (0.425, 0.185), (0.375, 0.185),
              (0.30, 0.16), (0.24, 0.13), (0.22, 0.105)],
        pivot=(0.235, 0.105), rays=[((0.24, 0.105), (0.41, 0.172))], ramp=PECT, amp=12.0, phase=0.25),
    "pelvic": dict(
        poly=[(0.37, 0.150), (0.45, 0.150), (0.53, 0.172), (0.625, 0.215), (0.61, 0.24), (0.51, 0.215),
              (0.40, 0.185)],
        pivot=(0.42, 0.165), rays=[((0.43, 0.17), (0.61, 0.225))], ramp=FIN, amp=10.0, phase=0.4),
    "dorsal": dict(
        poly=[(0.43, -0.262), (0.47, -0.37), (0.505, -0.46), (0.53, -0.515), (0.575, -0.505), (0.59, -0.43),
              (0.63, -0.33), (0.68, -0.24), (0.72, -0.18), (0.70, -0.155)],
        pivot=(0.56, -0.23), rays=[((0.46, -0.26), (0.535, -0.495)), ((0.545, -0.245), (0.60, -0.395)),
                                   ((0.625, -0.215), (0.67, -0.245))],
        ramp=DORSAL, amp=4.0, phase=0.15),
    "anal": dict(
        poly=[(0.63, 0.13), (0.69, 0.175), (0.75, 0.215), (0.815, 0.255), (0.835, 0.235), (0.83, 0.18),
              (0.84, 0.13), (0.85, 0.10), (0.79, 0.095)],
        pivot=(0.73, 0.12), rays=[((0.67, 0.14), (0.815, 0.24)), ((0.75, 0.12), (0.83, 0.19))],
        ramp=FIN, amp=11.0, phase=0.2),
    "caudal": dict(
        poly=[(0.93, -0.09), (1.00, -0.11), (1.08, -0.15), (1.17, -0.20), (1.265, -0.255), (1.29, -0.23),
              (1.25, -0.15), (1.205, -0.075), (1.17, -0.005), (1.205, 0.07), (1.25, 0.145), (1.29, 0.225),
              (1.265, 0.25), (1.17, 0.195), (1.08, 0.15), (1.00, 0.11), (0.93, 0.09)],
        pivot=(0.96, 0.0), rays=[((0.97, -0.05), (1.27, -0.24)), ((0.97, 0.05), (1.27, 0.235)),
                                 ((0.98, -0.01), (1.21, -0.11)), ((0.98, 0.01), (1.21, 0.105))],
        ramp=FIN, amp=0.0, phase=0.0),
}
BEND_FROM = 0.70             # the tail bends behind this point (standard lengths)
TAIL_TIP = 1.29
TAIL_SWAY = 1.0              # pixels at the tail tip
BODY = ("body", "pectoral_body", "pelvic_body")
# One scale = a 3x3 block of the lattice (X + 3Y) mod 9: blocks run in rows along the
# body at ~34 degrees. Tone offsets per block position: lit front, dark rear corner.
SCALE = {0: 1, 3: 1, 5: -1, 7: -1, 8: -1}

# The head is the same in every frame, so it is hand-painted over the body: a rounded
# forehead, the small upturned mouth, the 2x2 eye with its catchlight in the rudd's
# red-and-gold iris, a shining gill cover and its rear edge. '.' keeps the painted body;
# the overlay only touches body pixels, so the pectoral fin still sways over it.
HEAD_AT = (3, 4)
HEAD_ADD = ((5, 4), (8, 4))
HEAD = [
    "..effe..",
    "Jeeeeedd",
    "MrWEKJH.",
    "IgEELKH.",
    "wKggKH..",
    ".xxKJH..",
    ".wxxH...",
    "..wH....",
]


def _spline(points):
    """Smooth y(x) through the control points (cubic Hermite, finite-difference slopes)."""
    xs = [p[0] * SL for p in points]
    ys = [p[1] * SL for p in points]
    n = len(xs)
    ms = []
    for i in range(n):
        a, b = max(0, i - 1), min(n - 1, i + 1)
        ms.append((ys[b] - ys[a]) / (xs[b] - xs[a]))

    def f(x: float) -> float:
        if x <= xs[0]:
            return ys[0]
        if x >= xs[-1]:
            return ys[-1]
        for i in range(n - 1):
            if x <= xs[i + 1]:
                h = xs[i + 1] - xs[i]
                t = (x - xs[i]) / h
                t2, t3 = t * t, t * t * t
                return ((2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * ms[i]
                        + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * ms[i + 1])
        return ys[-1]
    return f


top = _spline(TOP)
bot = _spline(BOT)


def _inside(poly, x, y) -> bool:
    hit = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            hit = not hit
        j = i
    return hit


def _rot(px, py, pivot, degrees):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    dx, dy = px - pivot[0], py - pivot[1]
    return pivot[0] + dx * c - dy * s, pivot[1] + dx * s + dy * c


COS, SIN = math.cos(ANGLE), math.sin(ANGLE)


def _to_image(x, y, origin):
    return origin[0] + x * COS - y * SIN, origin[1] + x * SIN + y * COS


def _to_local(X, Y, origin):
    dx, dy = X - origin[0], Y - origin[1]
    return dx * COS + dy * SIN, -dx * SIN + dy * COS


def _centred_origin():
    """Where the snout goes so the whole fish is centred in the frame (how ORIGIN was found)."""
    pts = []
    for i in range(0, 101):
        x = SL * i / 100
        pts += [(x, top(x)), (x, bot(x))]
    for fin in FINS.values():
        pts += [(px * SL, py * SL) for px, py in fin["poly"]]
    ims = [_to_image(x, y, (0.0, 0.0)) for x, y in pts]
    xs, ys = [p[0] for p in ims], [p[1] for p in ims]
    return SIZE / 2 - (min(xs) + max(xs)) / 2, SIZE / 2 - (min(ys) + max(ys)) / 2


# The snout's place in the frame, within 0.1 px of _centred_origin(); pinned so the
# hand-painted head stays registered if a fin is retouched.
ORIGIN = (2.542, 6.476)


def _depth(x: float, yu: float) -> float:
    """0 on the back edge, 1 on the belly edge."""
    t, b = top(x), bot(x)
    return (yu - t) / max(0.5, b - t)


def _gill(n: float) -> float:
    """Local x of the gill cover's rear edge at depth n."""
    return SL * (0.205 + 0.05 * math.sin(math.pi * max(0.0, min(1.0, n))))


class Pose:
    """The animated state at loop phase t."""

    def __init__(self, t: float):
        self.t = t
        self.tail = TAIL_SWAY * math.sin(2 * math.pi * t)
        self.fin_angle = {name: fin["amp"] * math.sin(2 * math.pi * (t - fin["phase"]))
                          for name, fin in FINS.items()}

    def bend(self, x: float) -> float:
        start, end = BEND_FROM * SL, TAIL_TIP * SL
        if x <= start:
            return 0.0
        k = min(1.0, (x - start) / (end - start))
        return self.tail * k ** 1.5

    def local(self, X, Y):
        x, y = _to_local(X, Y, ORIGIN)
        return x, y - self.bend(x)

    def fin_hit(self, name, x, y) -> bool:
        fin = FINS[name]
        pivot = (fin["pivot"][0] * SL, fin["pivot"][1] * SL)
        lx, ly = _rot(x, y, pivot, -self.fin_angle[name])
        return _inside([(px * SL, py * SL) for px, py in fin["poly"]], lx, ly)

    def region(self, X, Y):
        """(region, local x, unbent local y) of the image point (X, Y)."""
        x, yu = self.local(X, Y)
        in_body = 0 <= x <= SL and top(x) <= yu <= bot(x)
        for name in ("pectoral", "pelvic"):
            if self.fin_hit(name, x, yu):
                return (name + "_body" if in_body else name), x, yu
        if in_body:
            return "body", x, yu
        for name in ("dorsal", "anal", "caudal"):
            if self.fin_hit(name, x, yu):
                return name, x, yu
        return None, x, yu


# --------------------------------------------------------------------------------------
# Painting
# --------------------------------------------------------------------------------------

def _rasterise(pose: Pose):
    """Region per pixel: supersampled coverage decides the fill (fins need less, so their
    tips survive as single pixels), the centre sample decides the region."""
    grid = [[None] * SIZE for _ in range(SIZE)]
    local = [[None] * SIZE for _ in range(SIZE)]
    full = SS * SS
    for Y in range(SIZE):
        for X in range(SIZE):
            counts: dict = {}
            for sy in range(SS):
                for sx in range(SS):
                    r, _, _ = pose.region(X + (sx + 0.5) / SS, Y + (sy + 0.5) / SS)
                    if r:
                        counts[r] = counts.get(r, 0) + 1
            r, x, yu = pose.region(X + 0.5, Y + 0.5)
            local[Y][X] = (x, yu)
            hits = sum(counts.values())
            if not hits:
                continue
            best = r or max(counts, key=counts.get)
            need = 0.5 if best in BODY else 0.3
            if hits >= need * full:
                grid[Y][X] = best
    # Pixel-art cleanup: drop stray pixels, fill one-pixel notches.
    for _ in range(2):
        for Y in range(SIZE):
            for X in range(SIZE):
                def at(dx, dy):
                    return grid[Y + dy][X + dx] if 0 <= X + dx < SIZE and 0 <= Y + dy < SIZE else None
                n4 = [r for r in (at(1, 0), at(-1, 0), at(0, 1), at(0, -1)) if r]
                n8 = n4 + [r for r in (at(1, 1), at(-1, 1), at(1, -1), at(-1, -1)) if r]
                me = grid[Y][X]
                if me and not n4 and (me in BODY or not n8):
                    grid[Y][X] = None
                elif not me and len(n4) >= 3:
                    grid[Y][X] = max(set(n4), key=n4.count)
    return grid, local


def _line(x0, y0, x1, y1):
    pts = []
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    while True:
        pts.append((x0, y0))
        if x0 == x1 and y0 == y1:
            return pts
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def _ray_pixels(pose: Pose) -> dict:
    """Bresenham fin rays in image space, per fin."""
    out = {}
    for name, fin in FINS.items():
        pivot = (fin["pivot"][0] * SL, fin["pivot"][1] * SL)
        pts = set()
        for (bx, by), (tx, ty) in fin["rays"]:
            ends = []
            for px, py in ((bx * SL, by * SL), (tx * SL, ty * SL)):
                lx, ly = _rot(px, py, pivot, pose.fin_angle[name])
                ly += pose.bend(lx)
                X, Y = _to_image(lx, ly, ORIGIN)
                ends.append((int(math.floor(X)), int(math.floor(Y))))
            pts |= set(_line(*ends[0], *ends[1]))
        out[name] = pts
    return out


def _pick(ramp_, value: float) -> str:
    return ramp_[max(0, min(len(ramp_) - 1, int(round(value))))]


def _body_tone(X: int, Y: int, x: float, yu: float, rim_top: bool, rim_bot: bool):
    """(ramp, tone index) of a body pixel: clean bands, big scales on the flank."""
    n = _depth(x, yu)
    gill = _gill(n)
    shadow = 1 if x > 0.74 * SL else 0          # the rear of the fish turns from the light
    if rim_top:
        return BACK, 4 - shadow
    if rim_bot:
        return BELLY, 1
    if x < gill:                                 # the head: no scales, a shiny gill cover
        if n < 0.28:
            return BACK, 3
        if n > 0.74:
            return BELLY, 3
        if x > gill - 1.2:
            return FLANK, 2                      # the gill cover's rear edge
        if x > gill - 2.2:
            return FLANK, 4
        return FLANK, 5
    k = (X + 3 * Y) % 9
    mark = SCALE.get(k, 0) if x < 0.93 * SL else 0
    if n < 0.13:
        return BACK, 2 - shadow
    if n < 0.25:
        return BACK, 3 - shadow + min(0, mark)       # scale rims only: the back stays calm
    if n > 0.88:
        return BELLY, 1
    if n > 0.72:
        return BELLY, 2
    tone = (5 if n < 0.40 else (4 if n < 0.55 else 3)) - shadow
    if mark > 0 and tone >= 5:
        mark = 0                                     # no pale specks on the bright band
    return FLANK, tone + mark


def _fin_tone(name: str, on_ray: bool, x: float, yu: float, light: int = 0):
    """(ramp, tone index, alpha) of a fin pixel: translucent membrane that brightens toward
    the tips, firmer darker rays; light is +1 on margins facing the light, -1 facing away."""
    fin = FINS[name]
    pivot = (fin["pivot"][0] * SL, fin["pivot"][1] * SL)
    reach = math.hypot(x - pivot[0], yu - pivot[1]) / SL
    if on_ray:
        return fin["ramp"], (2 if reach < 0.16 else 3) - (1 if name == "dorsal" else 0) + max(0, light), 220
    tone = 3 if reach < 0.13 else (4 if reach < 0.24 else 5)
    return fin["ramp"], tone + light, 200 if light <= 0 else 212


def _glint(t: float, x: float, n: float) -> float:
    """Strength 0..1 of the soft glint travelling down the back (0 while it rests)."""
    run = 0.75
    if t >= run:
        return 0.0
    centre = SL * (0.14 + 0.86 * t / run)
    along = max(0.0, 1.0 - abs(x - centre) / 2.4)
    across = 1.0 if n < 0.25 else max(0.0, 1.0 - (n - 0.25) / 0.2)
    return along * across


def _head_palette() -> dict:
    """Overlay letters: a..f = BACK, F..L = FLANK, u..y = BELLY tones (these still catch the
    glint), plus fixed colours for the mouth, eye and iris."""
    pal = {"M": MOUTH, "W": CATCH, "E": PUPIL, "r": IRIS[0], "g": IRIS[1]}
    pal.update({"abcdef"[i]: (BACK, i) for i in range(len(BACK))})
    pal.update({"FGHIJKL"[i]: (FLANK, i) for i in range(len(FLANK))})
    pal.update({"uvwxy"[i]: (BELLY, i) for i in range(len(BELLY))})
    return pal


HEAD_PAINT = {(HEAD_AT[0] + i, HEAD_AT[1] + j): _head_palette()[ch]
              for j, row in enumerate(HEAD) for i, ch in enumerate(row) if ch != "."}


def _frame(t: float) -> Image.Image:
    pose = Pose(t)
    grid, local = _rasterise(pose)
    for X, Y in HEAD_ADD:
        grid[Y][X] = grid[Y][X] or "body"
    rays = _ray_pixels(pose)
    img = canvas(SIZE)
    px = img.load()

    def region(X, Y):
        return grid[Y][X] if 0 <= X < SIZE and 0 <= Y < SIZE else None

    for Y in range(SIZE):
        for X in range(SIZE):
            r = grid[Y][X]
            if not r:
                continue
            x, yu = local[Y][X]
            if r in BODY:
                above = region(X, Y - 1)
                rim_top = yu < 0 and above not in BODY
                rim_bot = yu > 0 and region(X, Y + 1) not in BODY
                ramp_, tone = _body_tone(X, Y, x, yu, rim_top, rim_bot)
                painted = HEAD_PAINT.get((X, Y))
                if isinstance(painted, str):
                    col = painted
                else:
                    if painted:
                        ramp_, tone = painted
                    col = _pick(ramp_, tone + round(1.6 * _glint(t, x, _depth(x, yu))))
                if r != "body":
                    fin = r.split("_")[0]
                    framp, ftone, _ = _fin_tone(fin, (X, Y) in rays[fin], x, yu)
                    col = mix(col, _pick(framp, ftone - 1), 0.75)
                px[X, Y] = rgba(col)
            else:
                lit = region(X - 1, Y) is None or region(X, Y - 1) is None
                away = region(X + 1, Y) is None or region(X, Y + 1) is None
                light = 1 if lit and not away else (-1 if away and not lit else 0)
                framp, ftone, a = _fin_tone(r, (X, Y) in rays[r], x, yu, light)
                c = rgba(_pick(framp, ftone))
                px[X, Y] = (c[0], c[1], c[2], a)
    # Outline: selective, darker hue-shifted shades of what it borders.
    out = img.copy()
    op = out.load()
    for Y in range(SIZE):
        for X in range(SIZE):
            if grid[Y][X]:
                continue
            near = [region(X + dx, Y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
            near = [r for r in near if r]
            if not near:
                continue
            if any(r in BODY for r in near):
                op[X, Y] = rgba(OUT_BODY)
            else:
                c = rgba(OUT_FIN)
                op[X, Y] = (c[0], c[1], c[2], 225)
    return out


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
