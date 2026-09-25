"""Explosive Egg: a red bomb egg with a riveted hazard-stripe belt, a bolted iron fuse
collar and a short sparking fuse.

The shared egg body of the throwable-egg set is painted like glossy red enamel over iron,
chipped in places. A riveted iron belt with yellow and charcoal hazard stripes rings the
waist, and a bolted collar on top holds a braided fuse that curls out toward the viewer's
upper right. Animation: the fuse tip crackles and throws sparks, and jagged cracks in the
shell throb from dull ember to white-hot, the heat rolling down each crack.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, fit, lathe, mix, model, prism, rgba, rotation_of,
                     save, save_animation, turn, wave)

ID = "explosive_egg"
NAME = "Explosive Egg"
KIND = "item"
COUNTERPART = "item/egg"

# The shared egg body of the set: (height, radius) turned about EGG_CENTRE.
EGG_PROFILE = [(0, 1.4), (0.7, 3.2), (1.8, 4.3), (3.3, 4.9), (5.0, 5.0), (6.8, 4.7), (8.4, 4.0),
               (9.8, 3.0), (10.9, 1.8), (11.5, 0.6)]
EGG_CENTRE = (8, 2.2, 8)

# --------------------------------------------------------------------------------------
# Palettes (darkest -> lightest), hand-tuned and hue-shifted
# --------------------------------------------------------------------------------------
RED = ["#2e0716", "#5a0f22", "#8c1a2c", "#c22c38", "#ee4448", "#ff5555", "#ff7d68", "#ffab8c", "#ffe1c8"]
IRON = ["#0e0d14", "#1a1822", "#272532", "#363444", "#4a4859", "#626073", "#807e92", "#a9a8bb", "#dcdbe8"]
HAZ = ["#5a2c08", "#94500e", "#d58a16", "#f8b92a", "#ffd95a", "#fff1a8"]
HOT = ["#1e0505", "#4a0c08", "#861a0c", "#c8340f", "#f45a14", "#ff8a24", "#ffbc45", "#ffe57e", "#fffbe0"]
CORD = ["#24160e", "#3f2816", "#62401f", "#8c6130", "#b58748", "#dcb577", "#f3dfb0"]

# --------------------------------------------------------------------------------------
# Layout (model units). Heights h are measured up the egg from its bottom (y = 2.2).
# --------------------------------------------------------------------------------------
TEX = 32                                   # unwrapped textures are 32 px, half the egg wide
SLICE_ROWS = (2, 3, 4, 5, 5, 4, 4, 3, 2)   # texel rows per lathe slice, bottom -> top
BELT = (3.7, 6.1, 5.3)                     # hazard belt: h0, h1, radius
BELT_ROWS = 7                              # rim, five rows of stripes, rim
FLANGE = (9.8, 10.95, 2.85)                # collar ring sitting on the egg's shoulder
NECK = (10.95, 11.85, 1.5)
SOCKET = (11.85, 12.0, 0.95)
RIVET_AT = (0, 45, 90, 135, 180, 225, 270, 315)
GUI_ROTATION = (15, 225, 0)
LEAN = (-GUI_ROTATION[1]) % 360            # the fuse curls toward the slot's upper right
GRIP = (8, 7.0, 8)
SIZE = 1.3


def _rows():
    out, top = [], TEX
    for n in SLICE_ROWS:
        top -= n
        out.append((top, top + n))
    return out


ROWS = _rows()   # slice index (0 = bottom) -> texel rows [start, end) of the shell unwrap


def y_of(h: float) -> float:
    return EGG_CENTRE[1] + h


def texel_h(row: int) -> tuple[float, int]:
    """Egg height at the centre of a shell-unwrap row, and its lathe slice."""
    for i, (a, b) in enumerate(ROWS):
        if a <= row < b:
            h0, h1 = EGG_PROFILE[i][0], EGG_PROFILE[i + 1][0]
            return h1 - (row - a + 0.5) / (b - a) * (h1 - h0), i
    raise ValueError(row)


def put(img, x: int, y: int, colour) -> None:
    if 0 <= y < img.height:
        img.putpixel((x % img.width, y), rgba(colour))


def get_rgb(img, x: int, y: int) -> tuple:
    return img.getpixel((x % img.width, y))


# --------------------------------------------------------------------------------------
# Cracks: jagged polylines on the shell unwrap (columns wrap every 32 texels)
# --------------------------------------------------------------------------------------
CRACKS = [
    # (points (col, row), phase offset of its heat pulse). The whole egg throbs as one;
    # the offsets and the distance along each crack only ripple the heat down it.
    ([(9, 5), (10, 6), (10, 7), (12, 8), (12, 9), (11, 10), (12, 11), (14, 12), (14, 13), (13, 14),
      (14, 15)], 0.0),                                                          # hero crack, GUI front
    ([(12, 9), (13, 9), (14, 10), (16, 10), (17, 11)], 0.08),                  # its branches
    ([(11, 10), (9, 11), (8, 12)], 0.1),
    ([(21, 5), (21, 6), (22, 7), (22, 8)], 0.04),                              # hairline by the collar
    ([(18, 21), (18, 22), (17, 23), (17, 24), (19, 25), (19, 26), (18, 27), (18, 28), (20, 29)], 0.12),
    ([(19, 25), (21, 25), (22, 26)], 0.2),
    ([(3, 21), (3, 22), (4, 23), (4, 24)], 0.14),                              # hairline under the belt
]


def _line(p0, p1):
    (x0, y0), (x1, y1) = p0, p1
    n = max(abs(x1 - x0), abs(y1 - y0))
    return [(round(x0 + (x1 - x0) * k / n), round(y0 + (y1 - y0) * k / n)) for k in range(n + 1)]


def crack_map():
    """texel -> (kind, distance along its crack, phase): kind 0 the glowing core, 1 the
    shadowed lip below and right of it."""
    core = {}
    for points, phase in CRACKS:
        d = 0.0
        for p0, p1 in zip(points, points[1:]):
            seg = _line(p0, p1)
            for k, (x, y) in enumerate(seg):
                key = (x % TEX, y)
                dist = d + k
                if key not in core or core[key][0] > dist:
                    core[key] = (dist, phase)
            d += len(seg) - 1
    out = {key: (0, d, ph) for key, (d, ph) in core.items()}
    for (x, y), (d, ph) in core.items():
        for dx, dy in ((1, 0), (0, 1)):
            key = ((x + dx) % TEX, y + dy)
            if key[1] < TEX and key not in core and (key not in out or out[key][1] > d):
                out[key] = (1, d, ph)
    return out


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------
# Red ramp index per shell row (row 0 is the top of the egg). Contact shadows sit under
# the collar (row 5) and against the belt (rows 14 and 21); a faint reflected light
# rises off the bottom (row 30). Rows 0-4 and 15-20 are hidden by the collar and belt.
ROW_TONES = (5, 5, 5, 5, 5, 4, 6, 6, 6, 6, 6, 5, 5, 5, 4, 4, 5, 5, 5, 5, 3,
             3, 4, 5, 4, 4, 4, 3, 3, 3, 4, 2)
# Worn paint: (col, row, width) chips of bare iron, mostly where the belt and the collar
# rub the enamel.
CHIPS = ((6, 14, 2), (21, 14, 3), (28, 13, 2), (13, 22, 2), (25, 22, 2), (0, 22, 1), (16, 5, 2), (4, 5, 1),
         (29, 5, 2), (8, 26, 1), (27, 27, 1))


def shell_colour(col: int, row: int) -> str:
    h, i = texel_h(row)
    if i >= 7:                       # under the collar
        return IRON[2]
    return RED[ROW_TONES[row]]


def tex_shell() -> None:
    img = canvas(TEX)
    for row in range(TEX):
        for col in range(TEX):
            put(img, col, row, shell_colour(col, row))
    # Hammered enamel: a few short dents, one step darker with a lit lower lip, so the big
    # red faces never sit perfectly flat.
    rng = random.Random(7)
    for row in (7, 10, 13, 23, 26, 28):
        col = rng.randrange(0, 6)
        while col < TEX:
            base = ROW_TONES[row]
            put(img, col, row, RED[base - 1])
            put(img, col + 1, row, RED[base - 1])
            put(img, col + 1, row + 1, RED[min(8, ROW_TONES[row + 1] + 1)])
            col += rng.randint(9, 14)
    # Specular glint: a bright streak down the upper-left flank (the slot's lit side),
    # and a softer bounce of light low on the same side.
    for col, row, c in ((2, 6, RED[7]), (3, 6, RED[8]), (2, 7, RED[8]), (3, 7, RED[8]), (2, 8, RED[8]),
                        (3, 8, RED[7]), (1, 9, RED[7]), (2, 9, RED[8]), (1, 10, RED[7]), (2, 10, RED[7]),
                        (1, 11, RED[6]), (1, 12, RED[6]), (4, 6, RED[6]), (4, 7, RED[6]),
                        (1, 23, RED[5]), (2, 23, RED[6]), (1, 24, RED[5]), (2, 24, RED[5]),
                        (18, 6, RED[6]), (18, 7, RED[7]), (17, 8, RED[6])):
        put(img, col, row, c)
    # Scorched paint around the cracks (the cracks themselves are the glowing overlay).
    cmap = crack_map()
    for (x, y), (kind, d, ph) in cmap.items():
        if kind:
            continue
        for dx, dy in ((-1, 0), (0, -1)):
            key = ((x + dx) % TEX, y + dy)
            if 0 <= key[1] < TEX and key not in cmap and texel_h(key[1])[1] < 7:
                r, g, b, _ = img.getpixel(key)
                put(img, key[0], key[1], mix((r, g, b), RED[2], 0.3))
    # Chips: bare iron with a dark inner edge, and a lit paint edge along their bottom.
    for col, row, w in CHIPS:
        for k in range(w):
            put(img, col + k, row, IRON[4] if k == 0 else IRON[3])
        put(img, col + w, row, RED[2])
        for k in range(w):
            put(img, col + k, row + 1, RED[7] if k == 0 else RED[6])
    save(img, "shell")

    # Ledge colours: (0, i) the up-facing ledge of slice i, (1, i) the down-facing one.
    # Up faces catch the most light, so they are painted darker than the sides.
    ledge = canvas(16)
    for i in range(9):
        up = {6: RED[3], 5: RED[2], 4: RED[2], 3: RED[3], 2: RED[3]}.get(i, IRON[3])
        down = {0: RED[3], 1: RED[4], 2: RED[4], 3: RED[4], 4: RED[5]}.get(i, IRON[3])
        put(ledge, 0, i, up)
        put(ledge, 1, i, down)
    save(ledge, "ledge")


def heat_colour(kind: int, heat: float) -> tuple:
    """Crack colours at a heat of 0 (dull ember) .. 1 (white hot)."""
    if kind == 0:
        stops = [HOT[2], HOT[3], HOT[5], HOT[7], HOT[8]]
    else:
        stops = [RED[0], RED[1], HOT[2], HOT[4], HOT[5]]
    x = max(0.0, min(0.999, heat)) * (len(stops) - 1)
    k = int(x)
    return rgba(mix(stops[k], stops[k + 1], x - k))


def tex_cracks() -> None:
    cmap = crack_map()

    def frame(t: float):
        img = canvas(TEX)
        for (x, y), (kind, d, ph) in cmap.items():
            heat = wave(t - ph - d * 0.035) ** 1.6
            img.putpixel((x, y), heat_colour(kind, heat))
        return img

    save_animation(animate(frame, 16), "cracks", frametime=2, interpolate=True)


def tex_belt() -> None:
    img = canvas(TEX)
    last = BELT_ROWS - 1
    for col in range(TEX):
        put(img, col, 0, IRON[7])                 # lit top rim
        put(img, col, last, IRON[5])              # lower rim
        for row in range(1, last):
            yellow = ((col + row) // 4) % 2 == 0
            lead = ((col + row) % 4) == 0       # the leading edge of each yellow stripe
            k = min(3, (row - 1) * 4 // (last - 1))
            if yellow:
                c = HAZ[5] if lead and row < last - 1 else [HAZ[5], HAZ[4], HAZ[4], HAZ[3]][k]
            else:
                c = [IRON[3], IRON[2], IRON[2], IRON[1]][k]
            put(img, col, row, c)
    for col, row in ((5, 2), (14, 4), (22, 2), (29, 4), (10, 5)):   # scuffed paint on the stripes
        if get_rgb(img, col, row) in (rgba(HAZ[4]), rgba(HAZ[3])):
            put(img, col, row, HAZ[2])
    put(img, 0, 8, IRON[5])     # belt top ring
    put(img, 2, 8, IRON[1])     # belt underside
    put(img, 4, 8, IRON[6])     # top of the upper lip
    for col in range(TEX):      # the lips' outer bands: row 10 upper lip, row 11 lower lip
        put(img, col, 10, IRON[7] if col % 8 else IRON[8])
        put(img, col, 11, IRON[4] if col % 8 else IRON[5])
    save(img, "belt")

    rivet = canvas(16)
    for (x, y), c in {(1, 0): IRON[8], (2, 0): IRON[7], (0, 1): IRON[8], (1, 1): IRON[8], (2, 1): IRON[6],
                      (3, 1): IRON[5], (0, 2): IRON[7], (1, 2): IRON[6], (2, 2): IRON[5], (3, 2): IRON[4],
                      (1, 3): IRON[4], (2, 3): IRON[3]}.items():
        put(rivet, x, y, c)
    put(rivet, 4, 0, IRON[4])
    save(rivet, "rivet")


def tex_iron() -> None:
    """Charcoal iron for the collar: a lit bevel along each top edge, rivet heads and
    scratches on the flange band, a glint down the neck."""
    img = canvas(TEX)
    for col in range(TEX):
        for row, c in enumerate((IRON[6], IRON[4], IRON[3], IRON[2])):     # flange side
            put(img, col, row, c)
        for row, c in enumerate((IRON[6], IRON[3], IRON[2]), 4):           # neck side
            put(img, col, row, c)
    for col in range(1, TEX, 8):                                           # rivets on the flange
        put(img, col, 1, IRON[8])
        put(img, col + 1, 1, IRON[6])
        put(img, col, 2, IRON[5])
        put(img, col + 1, 2, IRON[1])
    for col in range(0, TEX, 8):                                           # neck glints
        put(img, col + 3, 5, IRON[5])
    for col, row in ((5, 2), (6, 2), (13, 2), (20, 2), (27, 2), (28, 2)):  # scratches on the flange
        put(img, col, row, IRON[5])
    put(img, 0, 10, IRON[4])    # flange top
    put(img, 2, 10, IRON[5])    # neck top
    put(img, 4, 10, IRON[0])    # socket
    put(img, 6, 10, IRON[1])    # undersides
    save(img, "iron")


def tex_fuse() -> None:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            k = (x + 2 * y) % 6
            put(img, x, y, [CORD[2], CORD[3], CORD[4], CORD[5], CORD[3], CORD[1]][k])
    # Rows 8..11: the scorched end of the cord, smouldering where it meets the ember (row 8).
    for y in range(8, 12):
        for x in range(16):
            k = (x + 2 * y) % 6
            put(img, x, y, [IRON[1], IRON[2], CORD[1], CORD[2], IRON[2], IRON[0]][k])
    for x in range(16):
        put(img, x, 8, [HOT[3], HOT[2], HOT[1], HOT[4], HOT[2], IRON[1]][(x + 4) % 6])
        if (x + 1) % 3 == 0:
            put(img, x, 9, HOT[1])
    save(img, "fuse")


def tex_ember() -> None:
    def frame(t: float):
        img = canvas(16)
        rng = random.Random(3)
        for y in range(16):
            for x in range(16):
                ph = rng.random()
                heat = wave(t + ph) ** 2
                img.putpixel((x, y), rgba(mix(HOT[3], HOT[7], heat)))
        return img

    save_animation(animate(frame, 8), "ember", frametime=2, interpolate=True)


SPARK_FRAMES = 8
# Per frame: lengths of the up/right/down/left rays, and whether the diagonal (x) rays
# are long. The pattern never repeats a neighbour, so the star crackles.
SPARK_RAYS = [((5, 3, 4, 6), True), ((3, 6, 5, 3), False), ((6, 4, 3, 5), True), ((4, 5, 6, 3), False),
              ((6, 3, 5, 4), False), ((3, 5, 3, 6), True), ((5, 6, 4, 3), False), ((4, 3, 6, 5), True)]


def tex_spark() -> None:
    """A crackling fuse spark: a white-hot core, flickering rays of uneven length and
    sparks that fly out, fall and cool from yellow to red, each with a short trail."""
    rng = random.Random(11)
    particles = [(i * 2 * math.pi / 9 + rng.uniform(-0.3, 0.3), i / 9 + rng.uniform(0, 0.05),
                  rng.uniform(0.85, 1.15)) for i in range(9)]

    def blend(img, x, y, colour):
        if 0 <= x < 16 and 0 <= y < 16 and img.getpixel((x, y))[3] == 0:
            img.putpixel((x, y), rgba(colour))

    def spark_at(t, ang, off, speed):
        p = (t + off) % 1.0
        r = 3.0 + 5.0 * p * speed
        return p, 7.5 + math.cos(ang) * r, 7.5 + math.sin(ang) * r + 2.5 * p * p

    def frame(t: float):
        img = canvas(16)
        k = round(t * SPARK_FRAMES) % SPARK_FRAMES
        (up, right, down, left), diag = SPARK_RAYS[k]
        # Core: 2x2 white, a hot cross around it.
        for x, y in ((7, 7), (8, 7), (7, 8), (8, 8)):
            put(img, x, y, HOT[8])
        for x, y in ((7, 6), (8, 6), (9, 7), (9, 8), (7, 9), (8, 9), (6, 7), (6, 8)):
            put(img, x, y, HOT[8] if k % 2 else HOT[7])
        for x, y in ((6, 6), (9, 6), (6, 9), (9, 9)):
            put(img, x, y, HOT[6])
        # Cardinal rays: two pixels wide where they leave the core, then one.
        rays = ((up, (0, -1), (7, 5)), (right, (1, 0), (10, 7)), (down, (0, 1), (8, 10)), (left, (-1, 0), (5, 8)))
        for length, (dx, dy), (sx, sy) in rays:
            for s in range(length):
                c = HOT[8] if s == 0 else (HOT[7] if s < 3 else (HOT[6] if s < 5 else HOT[5]))
                x, y = sx + dx * s, sy + dy * s
                blend(img, x, y, c)
                if s == 0:
                    blend(img, x + (1 if dy else 0), y + (1 if dx else 0), HOT[7])
        # Diagonal rays, long on alternate frames.
        for ddx, ddy in ((1, -1), (1, 1), (-1, 1), (-1, -1)):
            n = 3 if diag else 1
            for s in range(n):
                x = (8 if ddx > 0 else 7) + ddx * (2 + s)
                y = (8 if ddy > 0 else 7) + ddy * (2 + s)
                blend(img, x, y, HOT[7] if s == 0 else HOT[5])
        # Flying sparks with a one-pixel trail.
        for ang, off, speed in particles:
            p, x, y = spark_at(t, ang, off, speed)
            c = HOT[8] if p < 0.3 else (HOT[7] if p < 0.55 else (HOT[5] if p < 0.8 else HOT[3]))
            _, tx, ty = spark_at(t - 0.07, ang, off, speed)
            if p > 0.1:
                blend(img, int(math.floor(tx)), int(math.floor(ty)), HOT[4] if p < 0.6 else HOT[2])
            if 0 <= x < 16 and 0 <= y < 16:
                img.putpixel((int(x), int(y)), rgba(c))
        return img

    save_animation(animate(frame, SPARK_FRAMES), "spark", frametime=2)


def textures() -> None:
    tex_shell()
    tex_cracks()
    tex_belt()
    tex_iron()
    tex_fuse()
    tex_ember()
    tex_spark()


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------
NORMALS = {"east": (1, 0, 0), "west": (-1, 0, 0), "south": (0, 0, 1), "north": (0, 0, -1)}
ALL_SIDES = ("north", "south", "east", "west", "up", "down")


def azimuth(element: dict, side: str) -> float:
    """Direction a side face looks, degrees from +X toward -Z."""
    m, _ = rotation_of(element)
    n = NORMALS[side]
    x = sum(m[0][k] * n[k] for k in range(3))
    z = sum(m[2][k] * n[k] for k in range(3))
    return math.degrees(math.atan2(-z, x)) % 360


def wrap(parts, v0: float, v1: float, sides: int = 16, cap=None, cap_down=None):
    """Unwrap the side faces of turned slabs around the egg's axis: u follows the azimuth
    (one texture spans half the circumference, so front and back match) and v runs from
    v0 at the top to v1 at the bottom. Caps get one flat texel."""
    per = sides // 2
    width = 16 / per
    for e in parts:
        for side, face in e["faces"].items():
            if side in ("up", "down"):
                uv = cap_down if (side == "down" and cap_down) else cap
                if uv:
                    face["uv"] = list(uv)
                continue
            k = round(azimuth(e, side) / (360 / sides)) % sides
            u0 = (k % per) * width
            face["uv"] = [u0, v0, u0 + width, v1]
    return parts


def texel_uv(x: int, y: int, px: int = TEX) -> list[float]:
    s = 16 / px
    return [x * s, y * s, (x + 1) * s, (y + 1) * s]


def slice_of(element: dict) -> int:
    h0 = element["from"][1] - EGG_CENTRE[1]
    for i, (h, _) in enumerate(EGG_PROFILE[:-1]):
        if abs(h - h0) < 1e-3:
            return i
    raise ValueError(h0)


def shell() -> list[dict]:
    parts = lathe(EGG_CENTRE, EGG_PROFILE, "shell", sides=16)
    for e in parts:
        i = slice_of(e)
        a, b = ROWS[i]
        wrap([e], a / 2, b / 2)
        for side in ("up", "down"):
            e["faces"][side] = {"uv": texel_uv(0 if side == "up" else 1, i, 16), "texture": "#ledge"}
    return parts


def crack_overlay() -> list[dict]:
    """A second, slightly larger shell carrying the glowing cracks on the faces that
    have any; everything else is left out."""
    cmap = crack_map()
    grown = [(h, r + 0.08) for h, r in EGG_PROFILE]
    out = []
    for e in lathe(EGG_CENTRE, grown, "cracks", sides=16, glow=12, shade=False):
        i = slice_of(e)
        a, b = ROWS[i]
        wrap([e], a / 2, b / 2)
        keep = {}
        for side, face in e["faces"].items():
            if side in ("up", "down"):
                continue
            c0 = round(face["uv"][0] * 2)
            if any((c, r) in cmap for c in range(c0, c0 + 4) for r in range(a, b)):
                keep[side] = face
        if keep:
            e["faces"] = keep
            out.append(e)
    return out


def ring(h0: float, h1: float, r: float, tex: str, sides: int, v0: float, v1: float, cap, cap_down=None, **kw):
    y0, y1 = y_of(h0), y_of(h1)
    parts = prism((8, (y0 + y1) / 2, 8), r, y1 - y0, tex, sides=sides, **kw)
    return wrap(parts, v0, v1, sides, cap=cap, cap_down=cap_down)


def belt() -> list[dict]:
    h0, h1, r = BELT
    parts = ring(h0, h1, r, "belt", 16, 0, BELT_ROWS / 2, texel_uv(0, 8), texel_uv(2, 8))
    # Raised iron lips along both edges, so the stripes sit in a channel. They stand a
    # hair past the belt's ends so their caps never share a plane with the belt's.
    parts += ring(h1 - 0.28, h1 + 0.04, r + 0.14, "belt", 16, 5, 5.5, texel_uv(4, 8), texel_uv(2, 8))
    parts += ring(h0 - 0.04, h0 + 0.28, r + 0.14, "belt", 16, 5.5, 6, texel_uv(0, 8), texel_uv(2, 8))
    ymid = y_of((h0 + h1) / 2)
    for ang in RIVET_AT:
        rv = box((8 + r - 0.1, ymid - 0.5, 7.5), (8 + r + 0.42, ymid + 0.5, 8.5), "rivet",
                 faces={"east": ("rivet", [0, 0, 4, 4])},
                 uv={s: [4, 0, 5, 1] for s in ("north", "south", "west", "up", "down")})
        parts.append(turn(rv, ang, "y", (8, ymid, 8)))
    return parts


def collar() -> list[dict]:
    parts = ring(*FLANGE, "iron", 16, 0, 2, texel_uv(0, 10), texel_uv(6, 10))
    parts += ring(*NECK, "iron", 8, 2, 3.5, texel_uv(2, 10), texel_uv(6, 10))
    parts += ring(*SOCKET, "iron", 8, 4.5, 5, texel_uv(4, 10))
    parts += ring(SOCKET[1], SOCKET[1] + 0.3, 0.8, "iron", 8, 2, 3, texel_uv(2, 10))    # fuse crimp
    # Four bolts on the flange's top face.
    top = y_of(FLANGE[1])
    for ang in (22.5, 112.5, 202.5, 292.5):
        bolt = box((8 + 2.0, top - 0.1, 7.7), (8 + 2.6, top + 0.3, 8.3), "rivet",
                   faces={"up": ("rivet", [0, 0, 4, 4])},
                   uv={s: [4, 0, 5, 1] for s in ("north", "south", "east", "west", "down")})
        parts.append(turn(bolt, ang, "y", (8, top, 8)))
    return parts


def lean(dist: float) -> tuple[float, float]:
    a = math.radians(LEAN)
    return 8 + math.cos(a) * dist, 8 - math.sin(a) * dist


def fuse():
    """The braided fuse curling out of the socket, and the burning ember at its tip."""
    y = y_of(SOCKET[1])
    pts = [(0.0, y - 0.4), (0.05, y + 0.55), (0.5, y + 1.25), (1.3, y + 1.6), (2.15, y + 1.65)]
    path = [(lean(d)[0], yy, lean(d)[1]) for d, yy in pts]
    parts = []
    for k, (p0, p1) in enumerate(zip(path, path[1:])):
        v0 = 8 if k == len(path) - 2 else 0
        parts.append(bar(p0, p1, 1.0, 1.0, "fuse", uv={s: [k * 3, v0, k * 3 + 2, v0 + 4] for s in ALL_SIDES}))
    tip, prev = path[-1], path[-2]
    d = [tip[i] - prev[i] for i in range(3)]
    n = math.sqrt(sum(v * v for v in d))
    ember_end = tuple(tip[i] + d[i] / n * 0.55 for i in range(3))
    parts.append(bar(tip, ember_end, 1.05, 1.05, "ember", glow=15, uv={s: [0, 0, 4, 4] for s in ALL_SIDES}))
    return parts, ember_end


def spark(at) -> list[dict]:
    """Three crossed glowing planes, so the spark reads from any side and from above;
    one faces the inventory camera square on."""
    s = 2.3
    parts = []
    face_on = (-90 - GUI_ROTATION[1]) % 360 - 90
    for ang in (face_on, face_on + 90):
        plane = box((at[0] - s, at[1] - s, at[2]), (at[0] + s, at[1] + s, at[2]), "spark",
                    faces={"north": ("spark", [0, 0, 16, 16]), "south": ("spark", [0, 0, 16, 16])},
                    skip=("east", "west", "up", "down"), glow=15, shade=False)
        parts.append(turn(plane, ang, "y", at))
    f = s * 0.85
    flat = box((at[0] - f, at[1], at[2] - f), (at[0] + f, at[1], at[2] + f), "spark",
               faces={"up": ("spark", [0, 0, 16, 16]), "down": ("spark", [0, 0, 16, 16])},
               skip=("north", "south", "east", "west"), glow=15, shade=False)
    parts.append(turn(flat, face_on, "y", at))
    return parts


def build() -> list[dict]:
    fuse_parts, tip = fuse()
    return shell() + crack_overlay() + belt() + collar() + fuse_parts + spark(tip)


def models() -> dict:
    parts = build()
    d = display(KIND, parts, grip=GRIP, size=SIZE, gui_rotation=GUI_ROTATION, gui_span=15.6)
    # In flight and on the ground the fuse and spark are extra, so let the body match
    # the other eggs of the set instead of shrinking to fit them.
    d["ground"] = fit(parts, (0, 0, 0), 10.0, lift=2.0)
    return {"main": model(parts, d)}
