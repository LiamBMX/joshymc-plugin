"""Lootbox Lucky Blocks (issue #1603): one shared design for the ten Lootbox levels of
issue #1602. Each art/items/lootbox_level_<n>.py only picks its level and palette.

The box is a classic Lucky Block: a cube whose four sides and top carry a big raised
question mark with a dark drop shadow, held in a bevelled metal frame (twelve edge rails
and eight corner caps). Every level uses the same build, and the frame grows more
ornate with the level, so the set reads as one collection that gets more impressive:

  level 1-3   frame, corner caps, the question marks
  level 4+    a gem set into every corner cap
  level 7+    a gem-set band at the middle of every rail
  level 10    crystal spikes rising from the four top corners, everything prismatic

Animated textures (client side only, nothing ticks on the server):
  face   32x32, 16 frames: panel with a bevelled border, a quilted pattern, a glow that
         pulses behind the question mark, a glint sweep and sparkles (more with the level);
         level 10 cycles the whole rainbow across it.
  glyph  16x16, 16 frames: the question mark, glowing (light_emission) and pulsing.
  trim   16x16, 16 frames: the metal frame with a travelling shimmer.
  gem    16x16, 16 frames: faceted gem twinkle (level 4+).
"""
from __future__ import annotations

import colorsys
import math

from art.kit import (animate, box, canvas, copy, display, hexc, mix, model, ramp, rgba, save, save_animation, shade,
                     shine, sparkle, turn, wave)

KIND = "item"
COUNTERPART = "item/chest"

FRAMES = 16
FRAMETIME = 2

# --------------------------------------------------------------------------------------
# Geometry (model units). The cube is centred on (8, 8, 8).
# --------------------------------------------------------------------------------------
C0, C1 = 2.5, 13.5          # core cube
R0, R1 = 1.9, 3.3           # rail cross-section near the low corner (mirrored at the high one)
K0, K1 = 1.6, 3.6           # corner caps
GLYPH_CELL = 1.0
TOP_TURN = 90               # spins the top question mark to read upright in the inventory
GLYPH_DEPTH = 0.75          # how far the question mark stands off the face
GLYPH_DEPTH_TOP = 0.4       # the top one, shallower
SHADOW_DEPTH = 0.35
SHADOW_SHIFT = 0.5          # drop shadow offset, down and to the viewer's right

# The question mark, 6 x 8 cells, top row first, as seen looking at the face.
GLYPH = [
    ".####.",
    "##..##",
    "....##",
    "...##.",
    "..##..",
    "..##..",
    "......",
    "..##..",
]
GW, GH = len(GLYPH[0]), len(GLYPH)


def _runs(rows: list[str]):
    """(row, start column, length) of every horizontal run of filled cells."""
    out = []
    for r, line in enumerate(rows):
        c = 0
        while c < len(line):
            if line[c] == "#":
                s = c
                while c < len(line) and line[c] == "#":
                    c += 1
                out.append((r, s, c - s))
            else:
                c += 1
    return out


def _north_glyph(depth: float = GLYPH_DEPTH, shift: float = SHADOW_SHIFT) -> list[dict]:
    """The question mark (and its drop shadow) on the north face (z = C0, facing -z),
    standing `depth` off the face.

    Seen from the north the viewer's right is -x, so column 0 sits at the high-x side."""
    parts = []
    w, h = GW * GLYPH_CELL, GH * GLYPH_CELL
    left = 8 + w / 2           # x of column 0's outer edge (viewer's left)
    top = 8 + h / 2
    for r, c, n in _runs(GLYPH):
        x1 = left - c * GLYPH_CELL
        x0 = x1 - n * GLYPH_CELL
        y1 = top - r * GLYPH_CELL
        y0 = y1 - GLYPH_CELL
        # Shadow: shifted down and to the viewer's right (-x), shallower.
        parts.append(box((x0 - shift, y0 - shift, C0 - SHADOW_DEPTH),
                         (x1 - shift, y1 - shift, C0), "shadow", skip=("south",)))
        # Front face samples the glyph texture by its place in the glyph, so the gradient
        # and highlight run across the whole question mark.
        u0 = c / GW * 16
        u1 = (c + n) / GW * 16
        v0 = r / GH * 16
        v1 = (r + 1) / GH * 16
        edge = ("glyph_edge", [0, 0, 16, 16])
        parts.append(box((x0, y0, C0 - depth), (x1, y1, C0), "glyph", glow=10, shade=False, skip=("south",),
                         faces={"north": ("glyph", [u0, v0, u1, v1]),
                                "east": edge, "west": edge, "up": edge, "down": edge}))
    return parts


def _glyphs(shift: float = SHADOW_SHIFT) -> list[dict]:
    north = _north_glyph(shift=shift)
    parts = list(north)
    for angle in (90, 180, 270):
        parts += turn(copy(north), angle, axis="y", origin=(8, 8, 8))
    # The top one: a shallower north glyph tipped back onto the top face (a deep one
    # shows as a white bar over the top rail from the front).
    top = turn(_north_glyph(GLYPH_DEPTH_TOP, shift), 90, axis="x", origin=(8, 8, 8))
    parts += turn(top, TOP_TURN, axis="y", origin=(8, 8, 8))
    return parts


def _frame() -> list[dict]:
    parts = []
    lo, hi = (R0, R1), (16 - R1, 16 - R0)
    span = (K1, 16 - K1)
    # Rails along each axis, at the four (low/high, low/high) corners of the other two.
    for a in (lo, hi):
        for b in (lo, hi):
            parts.append(box((span[0], a[0], b[0]), (span[1], a[1], b[1]), "trim"))   # along x
            parts.append(box((a[0], span[0], b[0]), (a[1], span[1], b[1]), "trim"))   # along y
            parts.append(box((a[0], b[0], span[0]), (a[1], b[1], span[1]), "trim"))   # along z
    # Corner caps.
    for x in ((K0, K1), (16 - K1, 16 - K0)):
        for y in ((K0, K1), (16 - K1, 16 - K0)):
            for z in ((K0, K1), (16 - K1, 16 - K0)):
                parts.append(box((x[0], y[0], z[0]), (x[1], y[1], z[1]), "cap"))
    return parts


def _corner_gems(glow: int) -> list[dict]:
    """A gem stud standing out of every corner cap along its outward diagonal."""
    parts = []
    g = 0.7
    for sx in (-1, 1):
        for sy in (-1, 1):
            for sz in (-1, 1):
                cx = 8 + sx * (8 - (K0 + K1) / 2)
                cy = 8 + sy * (8 - (K0 + K1) / 2)
                cz = 8 + sz * (8 - (K0 + K1) / 2)
                ox, oy, oz = cx + sx * 0.75, cy + sy * 0.75, cz + sz * 0.75
                parts.append(box((ox - g, oy - g, oz - g), (ox + g, oy + g, oz + g), "gem", glow=glow, uv="full"))
    return parts


def _rail_bands(glow: int) -> list[dict]:
    """A raised band with a gem at the middle of every rail."""
    parts = []
    lo, hi = (R0 - 0.35, R1 + 0.35), (16 - R1 - 0.35, 16 - R0 + 0.35)
    m0, m1 = 7.1, 8.9
    for a in (lo, hi):
        for b in (lo, hi):
            parts.append(box((m0, a[0], b[0]), (m1, a[1], b[1]), "cap"))
            parts.append(box((a[0], m0, b[0]), (a[1], m1, b[1]), "cap"))
            parts.append(box((a[0], b[0], m0), (a[1], b[1], m1), "cap"))
    # Gems on the outward faces of each band: one small cube centred on the band, a bit bigger.
    g0, g1 = 7.5, 8.5
    for a in ((R0 - 0.6, R1 + 0.6), (16 - R1 - 0.6, 16 - R0 + 0.6)):
        for b in ((R0 - 0.6, R1 + 0.6), (16 - R1 - 0.6, 16 - R0 + 0.6)):
            for frm, to in (((g0, a[0], b[0]), (g1, a[1], b[1])),
                            ((a[0], g0, b[0]), (a[1], g1, b[1])),
                            ((a[0], b[0], g0), (a[1], b[1], g1))):
                parts.append(box(frm, to, "gem", glow=glow, uv="full"))
    return parts


def _crown() -> list[dict]:
    """Level 10: a crystal shard rising from each of the four top corners, leaning outward."""
    out = []
    for sx in (-1, 1):
        for sz in (-1, 1):
            cx = 8 + sx * (8 - (K0 + K1) / 2)
            cz = 8 + sz * (8 - (K0 + K1) / 2)
            by = 16 - K0
            collar = box((cx - 0.8, by - 0.2, cz - 0.8), (cx + 0.8, by + 0.45, cz + 0.8), "cap")
            shard = box((cx - 0.6, by + 0.2, cz - 0.6), (cx + 0.6, by + 1.7, cz + 0.6), "gem", glow=12, uv="full")
            tip = box((cx - 0.32, by + 1.5, cz - 0.32), (cx + 0.32, by + 2.2, cz + 0.32), "gem", glow=12, uv="full")
            turn([shard, tip], 45, axis="y", origin=(cx, by, cz))
            group = [collar, shard, tip]
            turn(group, x=12 * sz, z=-12 * sx, origin=(cx, by, cz))
            for p in group:
                p["_crown"] = True    # models() leaves these out of the display fit
            out += group
    return out


def _inner_lip() -> list[dict]:
    """Level 2+: a thin raised lip inside the rails on all six faces, so each panel sits
    in a moulded frame."""
    parts = []
    a, b = R1, R1 + 0.5               # lip band, measured in from the rails
    lo, hi = C0 - 0.3, C0             # stands 0.3 off the face
    north = [
        box((a, a, lo), (16 - a, b, hi), "cap", skip=("south",)),
        box((a, 16 - b, lo), (16 - a, 16 - a, hi), "cap", skip=("south",)),
        box((a, b, lo), (b, 16 - b, hi), "cap", skip=("south",)),
        box((16 - b, b, lo), (16 - a, 16 - b, hi), "cap", skip=("south",)),
    ]
    parts += north
    for angle in (90, 180, 270):
        parts += turn(copy(north), angle, axis="y", origin=(8, 8, 8))
    parts += turn(copy(north), 90, axis="x", origin=(8, 8, 8))
    parts += turn(copy(north), -90, axis="x", origin=(8, 8, 8))
    return parts


def _rail_rivets() -> list[dict]:
    """Level 3-6: a rivet collar at the middle of every rail (level 7+ has gem bands there)."""
    parts = []
    lo, hi = (R0 - 0.25, R1 + 0.25), (16 - R1 - 0.25, 16 - R0 + 0.25)
    m0, m1 = 7.4, 8.6
    for a in (lo, hi):
        for b in (lo, hi):
            parts.append(box((m0, a[0], b[0]), (m1, a[1], b[1]), "cap"))
            parts.append(box((a[0], m0, b[0]), (a[1], m1, b[1]), "cap"))
            parts.append(box((a[0], b[0], m0), (a[1], b[1], m1), "cap"))
    return parts


def build(level: int) -> list[dict]:
    parts = [box((C0, C0, C0), (C1, C1, C1), "face", uv="full", shade=False)]
    parts += _frame()
    # Level 10's busy rainbow face reads better with a tighter shadow.
    parts += _glyphs(0.35 if level >= 10 else SHADOW_SHIFT)
    gem_glow = 12 if level >= 7 else 4 + level // 2
    if level >= 2:
        parts += _inner_lip()
    if 3 <= level < 7:
        parts += _rail_rivets()
    if level >= 4:
        parts += _corner_gems(gem_glow)
    if level >= 7:
        parts += _rail_bands(gem_glow)
    if level >= 10:
        parts += _crown()
    return parts


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def _hue(colour: str, t: float, sat: float | None = None, val: float | None = None) -> str:
    """colour with its hue moved round the wheel by t (0..1)."""
    r, g, b, _ = rgba(colour)
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    h = (h + t) % 1.0
    if sat is not None:
        s = sat
    if val is not None:
        v = val
    rr, gg, bb = colorsys.hsv_to_rgb(h, s, v)
    return hexc((round(rr * 255), round(gg * 255), round(bb * 255)))


def _vivid(base: str, steps: int = 7, contrast: float = 0.7) -> list[str]:
    """A ramp that keeps the colour's saturation in its light half (ramp() washes the
    highlights toward white), so the block faces stay vivid under the inventory's side
    shading. Shadows still drift toward blue-violet."""
    r, g, b, _ = rgba(base)
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    out = []
    for i in range(steps):
        t = (i / (steps - 1) - 0.5) * contrast * 2
        if t <= 0:
            out.append(shade(base, t) if t else hexc(base))
            continue
        vv = min(1.0, v + (1 - v) * t * 1.2 + v * t * 0.25)
        ss = max(0.0, s * (1 - t * 0.25))
        hh = (h + (0.14 - h) * t * 0.08) % 1.0 if s > 0.05 else h
        rr, gg, bb = colorsys.hsv_to_rgb(hh, ss, vv)
        out.append(hexc((round(rr * 255), round(gg * 255), round(bb * 255))))
    return out


def _rainbow(t: float, sat: float = 0.78, val: float = 0.98) -> str:
    rr, gg, bb = colorsys.hsv_to_rgb(t % 1.0, sat, val)
    return hexc((round(rr * 255), round(gg * 255), round(bb * 255)))


# Sparkle spots on the 32x32 face, used in order (more with the level). Kept clear of the
# centre, which the question mark covers.
FACE_SPARKS = [(7, 7), (25, 24), (24, 7), (7, 25), (16, 5), (27, 15), (5, 17), (16, 27)]


def _face_frame(level: int, base: str, accent: str, t: float):
    size = 32
    img = canvas(size)
    px = img.load()
    rainbow = level >= 10
    pal = _vivid(base, 7, contrast=0.7)
    tier = (level - 1) // 3          # 0, 1, 2, 3 (level 10)
    pulse = wave(t)
    for y in range(size):
        for x in range(size):
            # Distance to the border (0 on the outer ring).
            d = min(x, y, size - 1 - x, size - 1 - y)
            if rainbow:
                hue = (t + (x + y) / 64.0) % 1.0
                local = _vivid(_rainbow(hue, sat=0.65), 7, contrast=0.7)
            else:
                local = pal
            if d == 0:
                c = local[0]
            elif d == 1:
                # Bevel: lit on the top/left, dark on the bottom/right.
                c = local[5] if (x <= y and x + y < size) or y == 1 and x < size - 2 else local[1]
                if x == 1 or y == 1:
                    c = local[5]
                if x == size - 2 or y == size - 2:
                    c = local[1]
            elif d == 2:
                c = local[2]
            else:
                # Panel: quilted pattern whose cells get finer with the tier, plus a soft
                # radial glow behind the question mark that pulses.
                cell = (7, 6, 8, 4)[tier]
                qx, qy = (x - 3) % cell, (y - 3) % cell
                seam = qx == 0 or qy == 0
                diag = (x + y) % cell == 0 or (x - y) % cell == 0
                k = 4
                if tier == 0:
                    # Stone: cut blocks, speckled.
                    k = 3 if seam else 4
                    if (x * 7 + y * 13) % 11 == 0:
                        k = 5
                    elif (x * 5 + y * 3) % 17 == 0:
                        k = 3
                elif tier == 1:
                    # Quilted tiles with a lit corner.
                    k = 3 if seam else (5 if (qx == 1 and qy == 1) else 4)
                elif tier == 2:
                    # Argyle diamonds with a bright point in each.
                    centre = (x + y) % cell == cell // 2 and (x - y) % cell == cell // 2
                    k = 3 if diag else (6 if centre else 4)
                else:
                    k = 4 if not diag else 5
                r = math.hypot(x - 15.5, y - 15.5) / 13.0
                glow = max(0.0, 1.0 - r) * (0.45 + 0.35 * pulse)
                # The core is unshaded (so the colours stay true in every view): panels sit
                # one step under the ramp's middle, which is the level's own colour.
                c = mix(local[k - 1], accent if not rainbow else "#ffffff", glow * 0.5)
            px[x, y] = rgba(c)
    # Corner rivets on the bevel.
    for cx, cy in ((3, 3), (28, 3), (3, 28), (28, 28)):
        rc = ramp(accent, 5, contrast=0.8)
        px[cx, cy] = rgba(rc[4])
        px[cx + 1, cy + 1] = rgba(rc[1])
        px[cx + 1, cy] = rgba(rc[2])
        px[cx, cy + 1] = rgba(rc[2])
    if level >= 7:
        # Shimmering border: two bright runs chase each other round the bevel ring.
        perimeter = 4 * (size - 3)
        for y in range(size):
            for x in range(size):
                if min(x, y, size - 1 - x, size - 1 - y) != 1:
                    continue
                if y == 1:
                    p = x - 1
                elif x == size - 2:
                    p = (size - 3) + (y - 1)
                elif y == size - 2:
                    p = 2 * (size - 3) + (size - 2 - x)
                else:
                    p = 3 * (size - 3) + (size - 2 - y)
                f = p / perimeter
                k = 0.0
                for head in (t, (t + 0.5) % 1.0):
                    d = (head - f) % 1.0          # trail behind the head
                    if d < 0.12:
                        k = max(k, 1.0 - d / 0.12)
                if k > 0:
                    r, g, b, a = px[x, y]
                    px[x, y] = (round(r + (255 - r) * k * 0.85), round(g + (255 - g) * k * 0.85),
                                round(b + (255 - b) * k * 0.85), a)
    img = shine(img, t, width=3 + tier, strength=0.35 + 0.08 * tier, pause=0.5)
    sparks = 1 + level * len(FACE_SPARKS) // 10
    for i, (sx, sy) in enumerate(FACE_SPARKS[:sparks]):
        sparkle(img, sx, sy, wave(t, i * 0.37 % 1.0) ** 2, reach=1 + (tier >= 2))
    return img


def _glyph_frame(level: int, colour: str, t: float):
    img = canvas(16)
    px = img.load()
    pulse = wave(t)
    for y in range(16):
        for x in range(16):
            if level >= 10:
                # Solid white with only a faint travelling rainbow sheen, so the "?" wins
                # over the rainbow face.
                base = _rainbow((t * 2 + y / 24.0) % 1.0, sat=0.12, val=1.0)
            else:
                base = colour
            pal = ramp(base, 6, contrast=0.8)
            # Light at the top, darker toward the foot, a dark underside row; pulses brighter.
            k = 5 - int(y / 16 * 3)
            if y >= 14:
                k = 2
            # Breathes between about 80% and 100% brightness.
            c = mix(mix(pal[k], shade(base, -0.25), 0.35 * (1 - pulse)), "#ffffff", 0.25 * pulse)
            px[x, y] = rgba(c)
    return shine(img, t, width=4, strength=0.6, pause=0.4)


def _trim_frame(level: int, colour: str, t: float):
    img = canvas(16)
    px = img.load()
    for y in range(16):
        for x in range(16):
            base = _rainbow((t + (x + y) / 32.0) % 1.0, sat=0.28, val=1.0) if level >= 10 else colour
            pal = ramp(base, 6, contrast=0.35 if level >= 10 else 0.6)
            # Brushed bands across the rail with a lit top edge.
            k = 3 + (1 if (x + 2 * y) % 5 == 0 else 0) + (1 if y % 4 == 0 else 0)
            if x in (0, 15) or y in (0, 15):
                k = 1
            px[x, y] = rgba(pal[min(5, k)])
    return shine(img, t, width=3, strength=0.5 + 0.03 * level, pause=0.45)


def _cap_frame(level: int, colour: str, t: float):
    img = canvas(16)
    px = img.load()
    for y in range(16):
        for x in range(16):
            base = _rainbow((t + 0.5 + (x - y) / 32.0) % 1.0, sat=0.28, val=1.0) if level >= 10 else colour
            pal = ramp(base, 6, contrast=0.35 if level >= 10 else 0.6)
            d = min(x, y, 15 - x, 15 - y)
            if d == 0:
                k = 1
            elif d == 1:
                k = 5 if x <= y else 1
            elif d < 4:
                k = 3
            else:
                k = 4 if (x + y) % 3 else 2
            px[x, y] = rgba(pal[k])
    out = shine(img, (t + 0.25) % 1.0, width=4, strength=0.45, pause=0.5)
    sparkle(out, 5, 5, wave(t, 0.2) ** 3, reach=2)
    return out


def _gem_frame(level: int, colour: str, t: float):
    img = canvas(16)
    px = img.load()
    for y in range(16):
        for x in range(16):
            if level >= 10:
                base = _rainbow((t + (x + y) / 24.0) % 1.0, sat=0.45, val=1.0)
                pal = ramp(base, 7, contrast=0.55)
            else:
                pal = ramp(colour, 7, contrast=0.9)
            # Facets: four triangles around a bright table.
            dx, dy = x - 7.5, y - 7.5
            if abs(dx) < 3 and abs(dy) < 3:
                k = 6 if (x + y) % 4 else 5
            elif abs(dx) > abs(dy):
                k = 4 if dx < 0 else 2
            else:
                k = 5 if dy < 0 else 1
            if min(x, y, 15 - x, 15 - y) == 0:
                k = 0 if level < 10 else 1
            px[x, y] = rgba(pal[k])
    sparkle(img, 5, 5, wave(t) ** 2, reach=3)
    sparkle(img, 11, 10, wave(t, 0.5) ** 2, reach=2)
    return img


def _edge(colour: str):
    """The raised sides of the question mark: a darker bevel of its colour."""
    img = canvas(16)
    a, b = shade(colour, -0.35), shade(colour, -0.42)
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), rgba(a if (x + y) % 2 else b))
    return img


def _shadow(colour: str):
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), rgba(shade(colour, -0.75 + 0.05 * ((x + y) % 2))))
    return img


def make(level: int, base: str, trim: str, glyph: str, gem: str, accent: str | None = None):
    """(textures, models) for one Lootbox level.

    base    the block colour (level 10 ignores it and cycles the rainbow)
    trim    the frame metal
    glyph   the question mark
    gem     corner/rail gems (level 4+)
    accent  the glow behind the question mark (defaults to the glyph colour)
    """
    accent = accent or _vivid(base)[6]

    def textures():
        save_animation(animate(lambda t: _face_frame(level, base, accent, t), FRAMES), "face",
                       frametime=FRAMETIME)
        save_animation(animate(lambda t: _glyph_frame(level, glyph, t), FRAMES), "glyph", frametime=FRAMETIME)
        save_animation(animate(lambda t: _trim_frame(level, trim, t), FRAMES), "trim", frametime=FRAMETIME)
        save_animation(animate(lambda t: _cap_frame(level, trim, t), FRAMES), "cap", frametime=FRAMETIME)
        if level >= 4:
            save_animation(animate(lambda t: _gem_frame(level, gem, t), FRAMES), "gem", frametime=FRAMETIME)
        save(_edge(glyph if level < 10 else "#d8ccff"), "glyph_edge")
        save(_shadow(base if level < 10 else "#5a3a8a"), "shadow")

    def models():
        parts = build(level)
        # Fit the views to the block itself, so level 10's crown pokes out of the frame
        # instead of shrinking its cube below the other nine.
        body = [p for p in parts if not p.get("_crown")]
        for p in parts:
            p.pop("_crown", None)
        return {"main": model(parts, display(KIND, body, size=1.0))}

    return textures, models
