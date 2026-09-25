"""Sell Wand: a merchant's gilded wand that sells a whole chest with one tap.

A gilded rod: a turned pommel set with an emerald bead, an emerald-green leather grip
bound in gold wire between two ferrules, and a barley-twist gold shaft with a knop. At
the neck a crimson ribbon bow ties on a paper price tag; above it sit a hand-stacked
pile of gold coins and a flared gold cup set with eight emeralds, from which two gold
scroll arms rise to cradle a halo of twelve coins, crowned by a gold spike holding a
small emerald. In the middle of the halo, inside a beaded gold bezel, an emerald-cut
emerald floats in its own green glow, and a ring of gold dust orbits the head.

Animation (every loop is seamless):
  * coins: a glint runs up the coin stack, splits, and races round both sides of the
    coin halo to the top; each coin catches a sweeping shine and a sparkle
  * emerald: the gem and its glow breathe, flaring as the glint reaches the top
  * dust: gold motes and comet sparks circle the head on a tilted orbit

Frame: upright along +Y on x = z = 8, head at the top, front = +Z (the inventory view).
Textures are 32 px at 2 texels per model unit; each coin face is an 8 px cell.
"""
from __future__ import annotations

import math

from art.kit import (animate, arc, bar, box, canvas, display, display_euler, euler_matrix, fill, mirror, mix, model,
                     move, place, rgba, save, save_animation, shine, sparkle, turn, wave)

ID = "sell_wand"
NAME = "Sell Wand"
KIND = "sword"
COUNTERPART = "item/blaze_rod"

# --------------------------------------------------------------------------------------
# Palettes (dark -> light, hue shifted by hand)
# --------------------------------------------------------------------------------------
GOLD = ["#3b1a07", "#6d3410", "#a65c15", "#d69020", "#f2bf38", "#ffe27c", "#fff8d8"]
COIN = ["#5c2804", "#9c4f08", "#d6860f", "#eeaa1c", "#fbd23e", "#fff29a", "#ffffff"]
EMER = ["#022517", "#054a2a", "#0a773f", "#10a653", "#2fd574", "#8af4ad", "#e4fff0"]
GLOW = ["#04331d", "#0a6b37", "#13a352", "#27d46e", "#5cf294", "#b4ffcc", "#ffffff"]   # the gem at full pulse
LEATH = ["#0b2416", "#113a24", "#185433", "#1f6e43", "#2b8a55", "#46a86e"]
RED = ["#3d0613", "#6f0f24", "#a8182f", "#d83a49", "#f37b7b"]
PAPER = ["#5c3d26", "#98744f", "#cdab7c", "#e9d2a3", "#faf0d4"]
TWINE = ["#6b4b2b", "#a07b4f", "#cfae80"]
DUST = ["#b8740f", "#ffcf45", "#fff1a0", "#fffdf0"]
INK = "#0d5a31"

# --------------------------------------------------------------------------------------
# Layout (model units)
# --------------------------------------------------------------------------------------
C = 8.0
GEM = (8.0, 18.0, 8.0)             # the floating emerald, centre of the halo and the orbit
HALO_R, COIN_R, COIN_T, N_COINS = 6.6, 1.62, 0.6, 12
GRIP = (8.0, -2.2, 8.0)
SIZE = 0.53
COIN_FRAMES, COIN_TICKS = 32, 2       # one 3.2 s "cha-ching" cycle
GEM_FRAMES, GEM_TICKS = 16, 4         # same 64-tick cycle, interpolated
DUST_FRAMES, DUST_TICKS = 15, 2
DUST_PERIOD = 30                      # texels; the dust band repeats every 3 strips
ORBIT_R, ORBIT_W, ORBIT_TILT = 9.66, 2.0, 58.0
STRIP_L = 5.0                         # 12 chords of an orbit of radius ORBIT_R

NORMALS = {"east": (1.0, 0.0), "west": (-1.0, 0.0), "north": (0.0, -1.0), "south": (0.0, 1.0)}


# --------------------------------------------------------------------------------------
# Painting helpers
# --------------------------------------------------------------------------------------

def put(img, x, y, colour, alpha=None):
    if 0 <= x < img.width and 0 <= y < img.height:
        c = rgba(colour)
        if alpha is not None:
            c = (c[0], c[1], c[2], int(alpha))
        img.putpixel((x, y), c)


def solid(name, colour, size=16):
    save(canvas(size, fill=colour), name)


def bump(x):
    """0 -> 1 -> 0 over x in [0, 1], zero outside."""
    return math.sin(math.pi * x) if 0.0 <= x <= 1.0 else 0.0


# --------------------------------------------------------------------------------------
# Static textures
# --------------------------------------------------------------------------------------

def paint_gold():
    """Polished gilding for turned parts: a lit top lip and left edge on every facet and
    one soft polish streak; the facets' own lighting does the rest."""
    img = canvas(32, fill=GOLD[3])
    fill(img, (0, 0, 0, 31), GOLD[5])
    fill(img, (1, 0, 1, 31), GOLD[4])
    fill(img, (3, 0, 3, 31), GOLD[4])
    fill(img, (6, 0, 31, 31), GOLD[2])
    fill(img, (0, 0, 31, 0), GOLD[5])
    for y in (5, 13, 22):
        put(img, 0, y, GOLD[6])
    save(img, "gold")
    solid("gold_cap", GOLD[4])
    ring = canvas(32, fill=GOLD[3])
    for y in range(32):
        for x in range(32):
            if x == 0:
                c = GOLD[5]
            elif x == 1:
                c = GOLD[4]
            else:
                c = GOLD[5] if y % 2 == 0 else GOLD[2]      # a beaded outer border
            put(ring, x, y, c)
    save(ring, "bezel")


def paint_twist():
    """Barley-twist flutes: stripes that step one texel per facet into a helix."""
    img = canvas(32)
    rows = [GOLD[5], GOLD[4], GOLD[3], GOLD[1]]
    for y in range(32):
        fill(img, (0, y, 31, y), rows[y % 4])
    save(img, "gold_twist")


def paint_leather():
    """Emerald leather wound in overlapping straps, a gold wire in every other seam."""
    img = canvas(32)
    rows = [GOLD[4], LEATH[2], LEATH[4], LEATH[3], LEATH[1], LEATH[5], LEATH[4], LEATH[3]]
    for y in range(32):
        fill(img, (0, y, 31, y), rows[y % 8])
    save(img, "leather")


def paint_coin_edge():
    """Reeded coin rims for the halo coins."""
    img = canvas(32)
    for y in range(32):
        for x in range(32):
            put(img, x, y, COIN[5] if y == 0 else (COIN[4] if x % 2 == 0 else COIN[2]))
    save(img, "coin_edge")
    solid("coin_top", COIN[4])


def paint_ribbon():
    img = canvas(32)
    for y in range(32):
        for x in range(32):
            c = RED[2]
            if y % 4 == 0:
                c = RED[3]
            if x == 0:
                c = RED[4]
            put(img, x, y, c)
    save(img, "ribbon")
    solid("ribbon_dark", RED[1])
    tail = canvas(32)
    for y in range(32):
        for x in range(32):
            put(tail, x, y, RED[3] if x == 0 else RED[2])
    put(tail, 1, 0, (0, 0, 0, 0))              # the tail's end is cut on the slant
    put(tail, 1, 1, RED[1])
    save(tail, "tail")
    knot = canvas(16, fill=RED[3])
    fill(knot, (0, 0, 15, 0), RED[4])
    fill(knot, (0, 0, 0, 15), RED[4])
    fill(knot, (1, 1, 1, 1), "#ffb3a8")
    save(knot, "knot")
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            put(img, x, y, TWINE[2] if (x + y) % 3 == 0 else TWINE[1])
    save(img, "twine")


TAG_W, TAG_H = 7, 12
DOLLAR = [".X.", "XXX", "X..", "XXX", "..X", "XXX", ".X."]


def paint_tag():
    """A paper price tag with a gold eyelet and a green-ink dollar sign."""
    img = canvas(32)
    widths = {0: 1, 1: 3, 2: 5}
    for y in range(TAG_H):
        w = widths.get(y, TAG_W)
        x0 = (TAG_W - w) // 2
        for x in range(x0, x0 + w):
            c = PAPER[3]
            if y == TAG_H - 1:
                c = PAPER[1]
            elif x == x0 + w - 1:
                c = PAPER[2]
            elif x == x0 or y == 0:
                c = PAPER[4]
            put(img, x, y, c)
    # eyelet
    for x, y in ((3, 1), (2, 2), (4, 2), (3, 3)):
        put(img, x, y, COIN[4])
    put(img, 3, 2, (0, 0, 0, 0))
    for j, row in enumerate(DOLLAR):
        for i, ch in enumerate(row):
            if ch == "X":
                put(img, 2 + i, 4 + j, INK)
    save(img, "tag")


# --------------------------------------------------------------------------------------
# Animated textures
# --------------------------------------------------------------------------------------

def _in_coin(x, y):
    return 0 <= x < 8 and 0 <= y < 8 and (x - 3.5) ** 2 + (y - 3.5) ** 2 <= 4.1 ** 2


def coin_cell():
    """An 8 px gold coin lit from the top left: a dark milled outline, a raised inner rim
    (bright top left, shaded bottom right), a warm field and a small raised boss."""
    img = canvas(8)
    for y in range(8):
        for x in range(8):
            if not _in_coin(x, y):
                continue
            edge = any(not _in_coin(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            inner = not edge and any(not _in_coin(x + dx, y + dy) for dx, dy in
                                     ((2, 0), (-2, 0), (0, 2), (0, -2), (1, 1), (-1, -1), (1, -1), (-1, 1)))
            lit = x + y <= 7
            if edge:
                c = COIN[2] if lit else COIN[1]
            elif inner:
                c = COIN[5] if lit else COIN[3]
            else:
                c = COIN[4]
            put(img, x, y, c)
    put(img, 3, 3, COIN[6])
    put(img, 4, 3, COIN[5])
    put(img, 3, 4, COIN[5])
    put(img, 4, 4, COIN[3])
    return img


def lit_coin(cell, k):
    """The coin brightened toward pale gold by k (0..1), for the glint."""
    if k <= 0:
        return cell
    out = cell.copy()
    px = out.load()
    target = rgba("#ffe24c")
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a:
                px[x, y] = (round(r + (target[0] - r) * k), round(g + (target[1] - g) * k),
                            round(b + (target[2] - b) * k), a)
    return out


def halo_start(i):
    """When the glint reaches halo coin i (0 = the bottom coin, counting round)."""
    return 0.27 + 0.055 * min(i, N_COINS - i)


STACK_START = (0.02, 0.08, 0.14, 0.20)
GLINT_W = 0.17


def coins_frame(t):
    img = canvas(32)
    base = coin_cell()
    for i in range(N_COINS):
        local = (t - halo_start(i)) % 1.0
        k = bump(local / GLINT_W)
        cell = lit_coin(base, 0.35 * k)
        cell = shine(cell, local, colour="#fff3a0", width=3.5, strength=0.85, angle=35, pause=1 - GLINT_W)
        if k > 0.05:
            sparkle(cell, 2, 1, k, "#ffffff", reach=2)
        img.paste(cell, ((i % 4) * 8, (i // 4) * 8))
    # the coin stack's rims: a lip row and a reeded row per coin
    for k, start in enumerate(STACK_START):
        glow = bump(((t - start) % 1.0) / GLINT_W)
        y = 24 + 2 * k
        for x in range(32):
            lip = mix(COIN[5], "#fff6c0", glow * 0.8)
            body = mix(COIN[3] if x % 2 == 0 else COIN[2], "#ffe04a", glow * 0.9)
            put(img, x, y, lip)
            put(img, x, y + 1, body)
    return img


def pulse(t):
    """Gem brightness: breathes once per cycle, brightest as the glint reaches the top."""
    return wave(t, -0.22)


def gem_palette(t):
    b = pulse(t)
    return [mix(EMER[i], GLOW[i], b) for i in range(7)], b


def gem_front(tx, ty, E):
    """The emerald-cut face (10 x 14 texels): girdle, a stepped crown and the table."""
    if tx == 0:
        return E[3] if 2 <= ty <= 11 else None
    if tx == 9:
        return E[1] if 2 <= ty <= 11 else None
    if ty == 0:
        return E[4] if 2 <= tx <= 7 else None
    if ty == 13:
        return E[1] if 2 <= tx <= 7 else None
    if 3 <= tx <= 6 and 3 <= ty <= 10:              # the table
        d = (tx - 3) + (ty - 3)
        if d == 0:
            return E[6]
        if d == 1:
            return E[5]
        if d == 3:
            return E[4]
        if d >= 8:
            return E[2]
        return E[3]
    dist = {"top": ty - 1, "bottom": 12 - ty, "left": tx - 1, "right": 8 - tx}
    side = min(dist, key=lambda s: (dist[s], s not in ("top", "bottom")))
    return {"top": E[5], "left": E[4], "right": E[2], "bottom": E[1]}[side]


def emerald_frame(t):
    E, b = gem_palette(t)
    img = canvas(32)
    for ty in range(14):
        for tx in range(10):
            c = gem_front(tx, ty, E)
            if c:
                put(img, tx, ty, c)
    fill(img, (12, 0, 13, 1), E[5])      # chamfer facets: top-left, top-right, bottom-left, bottom-right
    fill(img, (14, 0, 15, 1), E[3])
    fill(img, (12, 2, 13, 3), E[3])
    fill(img, (14, 2, 15, 3), E[1])
    fill(img, (0, 16, 15, 31), E[2])      # pavilion sides
    fill(img, (0, 16, 15, 16), E[3])
    fill(img, (0, 16, 0, 31), E[4])
    fill(img, (1, 18, 15, 31), E[1])
    fill(img, (16, 0, 31, 15), E[2])      # beads and cabochons
    fill(img, (16, 0, 31, 0), E[4])
    fill(img, (16, 0, 16, 15), E[3])
    fill(img, (17, 1, 17, 1), E[5])
    fill(img, (24, 16, 31, 23), E[3])     # flat swatch for the caps of turned gem parts
    sparkle(img, 4, 4, max(0.0, b * 1.2 - 0.2), "#ffffff", reach=1)
    return img


def aura_frame(t):
    """The glow round the floating gem, breathing with it (the gem hides the middle)."""
    _, b = gem_palette(t)
    img = canvas(32)
    k = 0.25 + 0.75 * b
    for y in range(32):
        for x in range(32):
            r = math.hypot(x - 15.5, y - 15.5)
            if r > 15.5:
                continue
            if r < 11.5:
                c, a = GLOW[4], 200
            elif r < 13.5:
                c, a = GLOW[4], 140
            else:
                c, a = GLOW[3], 85
            put(img, x, y, c, a * k)
    return img


# Dust motes riding the orbit: (u offset, row, brightness 0-3, twinkle phase)
MOTES = [(8, 0, 1, 0.1), (11, 3, 2, 0.45), (14, 1, 1, 0.8), (16, 3, 1, 0.55), (18, 0, 2, 0.3), (21, 2, 1, 0.9),
         (23, 0, 1, 0.65), (25, 3, 2, 0.2), (27, 1, 1, 0.75), (29, 2, 1, 0.4)]


def dust_frame(t):
    img = canvas(32)
    shift = t * DUST_PERIOD

    def at(u, v, colour, alpha=255):
        x = int(round(u + shift)) % DUST_PERIOD
        if img.getpixel((x, v))[3] < alpha:
            put(img, x, v, colour, alpha)

    for u, v, level, phase in MOTES:
        tw = wave(3 * t + phase)
        if tw > 0.25:
            at(u, v, DUST[min(3, level + (1 if tw > 0.8 else 0))], 150 + 105 * tw)
    for u, v in ((19, 2), (20, 2)):
        at(u, v, DUST[2] if u == 20 else DUST[1], 230)
    at(21, 2, DUST[3])
    head = 4
    trail = [(DUST[2], 245), (DUST[1], 225), (DUST[1], 190), (DUST[0], 160), (DUST[0], 120)]
    for n, (c, a) in enumerate(trail, start=1):
        at(head - n, 1 + (n % 2 == 0), c, a)
        if n <= 2:
            at(head - n, 2 - (n % 2 == 0), c, a - 40)
    at(head, 1, DUST[3])
    at(head, 2, DUST[3])
    at(head + 1, 1, DUST[2], 220)
    at(head + 1, 2, DUST[2], 220)
    at(head, 0, DUST[1], 200)
    at(head, 3, DUST[1], 200)
    return img


def textures() -> None:
    paint_gold()
    paint_twist()
    paint_leather()
    paint_coin_edge()
    paint_ribbon()
    paint_tag()
    save_animation(animate(coins_frame, COIN_FRAMES), "coins", frametime=COIN_TICKS)
    save_animation(animate(emerald_frame, GEM_FRAMES), "emerald", frametime=GEM_TICKS, interpolate=True)
    save_animation(animate(aura_frame, GEM_FRAMES), "aura", frametime=GEM_TICKS, interpolate=True)
    save_animation(animate(dust_frame, DUST_FRAMES), "dust", frametime=DUST_TICKS)


# --------------------------------------------------------------------------------------
# Geometry helpers
# --------------------------------------------------------------------------------------

def cyl(center, r, length, tex, cap=None, axis="y", sides=8, caps=True, face_uv=None, **kw):
    """A round rod like kit.prism(): r is the distance to the flat facets. face_uv(k, w, h)
    may give each facet (k = 0.. round from +Z toward +X) its own uv, e.g. a v offset
    that grows by facet so painted stripes wind round as a helix."""
    cx, cy, cz = center
    half = r * math.tan(math.pi / sides)
    angles = (0.0, 45.0) if sides == 8 else (0.0, 22.5, 45.0, -22.5)
    step = 360.0 / sides
    parts = []
    for a in angles:
        ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
        for plane in ("x", "z"):
            if plane == "x":
                frm, to = (cx - r, cy - length / 2, cz - half), (cx + r, cy + length / 2, cz + half)
                walls, skip = ("east", "west"), ["north", "south"]
            else:
                frm, to = (cx - half, cy - length / 2, cz - r), (cx + half, cy + length / 2, cz + r)
                walls, skip = ("north", "south"), ["east", "west"]
            if not caps:
                skip += ["up", "down"]
            faces = {}
            if cap and caps:
                faces["up"] = cap
                faces["down"] = cap
            if face_uv:
                for side in walls:
                    nx, nz = NORMALS[side]
                    rx, rz = ca * nx + sa * nz, -sa * nx + ca * nz
                    k = int(round((math.degrees(math.atan2(rx, rz)) % 360) / step)) % sides
                    faces[side] = (tex, face_uv(k, 2 * half, length))
            e = box(frm, to, tex, faces=faces, skip=tuple(skip), **kw)
            if a:
                turn(e, a, "y", (cx, cy, cz))
            parts.append(e)
    if axis == "x":
        turn(parts, 90, "z", (cx, cy, cz))
    elif axis == "z":
        turn(parts, 90, "x", (cx, cy, cz))
    return parts


def turned(profile, tex, cap, x=C, z=C, sides=8, **kw):
    """A lathe-turned part from [(y, radius), ...] (absolute heights)."""
    parts = []
    for (y0, r0), (y1, r1) in zip(profile, profile[1:]):
        r = (r0 + r1) / 2
        if r > 0 and y1 - y0 > 1e-6:
            parts += cyl((x, (y0 + y1) / 2, z), r, y1 - y0, tex, cap=cap, sides=sides, **kw)
    return parts


def hoop(center, radius, start, end, segments, width, depth, tex, **kw):
    """kit.arc() with every other segment a hair deeper, so the overlapping ends of
    neighbouring segments never share a plane (no z-fighting where they overlap)."""
    parts = []
    for k in range(segments):
        a0 = start + (end - start) * k / segments
        a1 = start + (end - start) * (k + 1) / segments
        parts += arc(center, radius, a0, a1, 1, width, depth + 0.03 * (k % 2), tex, plane="xy", **kw)
    return parts


def helix(step):
    return lambda k, w, h: [0, k * step, w, k * step + h]


# --------------------------------------------------------------------------------------
# Parts
# --------------------------------------------------------------------------------------

GEM_CAP = ("emerald", [12.5, 8.5, 15.5, 11.5])   # a flat swatch: overlapping slab caps can't flicker


def emerald_uv(region):
    """Offset of a solid region of the emerald atlas, for small gem parts."""
    return {"bead": (8, 0), "side": (0, 8)}[region]


def pommel():
    parts = turned([(-10.0, 0.7), (-9.4, 1.55), (-8.4, 1.75), (-7.6, 1.05), (-7.1, 1.3)], "gold", "gold_cap")
    parts += turned([(-10.9, 0.7), (-10.0, 0.9)], "emerald", GEM_CAP, glow=12, offset=emerald_uv("bead"))
    return parts


def grip():
    parts = turned([(-7.1, 1.45), (-6.8, 1.72), (-6.4, 1.72), (-6.2, 1.45)], "gold", "gold_cap")
    parts += cyl((C, -2.2, C), 1.207, 8.0, "leather", caps=False, face_uv=helix(0.5))
    parts += turned([(1.8, 1.45), (2.0, 1.72), (2.4, 1.72), (2.7, 1.45)], "gold", "gold_cap")
    return parts


def shaft():
    parts = cyl((C, 4.4, C), 0.97, 3.4, "gold_twist", caps=False, face_uv=helix(0.5))
    parts += turned([(3.3, 1.0), (3.6, 1.45), (3.95, 1.72), (4.3, 1.45), (4.6, 1.0)], "gold", "gold_cap")
    return parts


def ribbon_and_tag():
    parts = cyl((C, 5.2, C), 1.1, 0.6, "ribbon", cap="ribbon_dark")
    kz = C + 1.3
    parts.append(box((C - 0.42, 4.82, kz - 0.3), (C + 0.42, 5.62, kz + 0.32), "knot"))
    # one loop (a ribbon outline round a shaded inside) and one tail, mirrored so both
    # halves of the bow are lit alike
    top, low = (C + 2.05, 6.15, kz), (C + 2.2, 4.55, kz)
    half = [bar((C + 0.3, 5.4, kz), top, 0.5, 0.45, "ribbon"),
            bar((C + 0.3, 5.05, kz), low, 0.5, 0.45, "ribbon"),
            bar(top, low, 0.5, 0.45, "ribbon"),
            box((C + 0.6, 4.9, kz - 0.2), (C + 2.0, 5.75, kz - 0.02), "ribbon_dark"),
            bar((C + 0.2, 5.0, kz - 0.1), (C + 0.95, 3.1, kz - 0.15), 1.0, 0.3, "tail")]
    parts += half + mirror(half, "x", C)
    # the tag hangs on a twine from the knot, down and to the right (straight down in the slot)
    d = (math.sqrt(0.5), -math.sqrt(0.5))
    start = (C + 0.45, 4.95, kz - 0.05)
    hole = (start[0] + 3.0 * d[0], start[1] + 3.0 * d[1], start[2])
    parts.append(bar(start, hole, 0.22, 0.22, "twine"))
    tcx, tcy = hole[0] + 1.75 * d[0], hole[1] + 1.75 * d[1]
    hw, hh = TAG_W / 4, TAG_H / 4
    tag = box((tcx - hw, tcy - hh, hole[2] - 0.08), (tcx + hw, tcy + hh, hole[2] + 0.08), "tag",
              faces={"south": ("tag", [0, 0, TAG_W / 2, TAG_H / 2]), "north": ("tag", [0, 0, TAG_W / 2, TAG_H / 2])},
              skip=("east", "west", "up", "down"))
    turn(tag, 45, "z", (tcx, tcy, hole[2]))
    parts.append(tag)
    return parts


STACK = [(0.0, 0.0, 2.45, 0.0, 0.0), (0.32, -0.15, 2.35, 7.0, 2.5), (-0.28, 0.2, 2.5, -4.0, -2.0),
         (0.18, 0.05, 2.4, 10.0, 1.5)]      # dx, dz, radius, yaw, tilt


def coin_stack():
    parts = []
    y = 6.1
    for k, (dx, dz, r, yaw, tilt) in enumerate(STACK):
        coin = cyl((C + dx, y + 0.3, C + dz), r, 0.58, "coins", cap="coin_top", sides=16,
                   face_uv=lambda idx, w, h, k=k: [0, 12 + k, w, 13 + k])
        turn(coin, yaw, "y", (C + dx, y + 0.3, C + dz))
        if tilt:
            turn(coin, tilt, "z", (C + dx, y + 0.3, C + dz))
        parts += coin
        y += 0.6
    return parts


def cup():
    return turned([(8.5, 1.15), (8.9, 1.35), (9.4, 1.75), (9.9, 2.2), (10.4, 2.6), (10.8, 2.9), (11.2, 3.05)],
                  "gold", "gold_cap")


def cabochons():
    """Eight little emeralds set round the cup."""
    parts = []
    for k in range(8):
        e = box((C - 0.42, 9.75, C + 2.25), (C + 0.42, 10.6, C + 2.72), "emerald", offset=emerald_uv("bead"),
                glow=12)
        parts.append(turn(e, 45 * k, "y", (C, 10.2, C)))
    return parts


def arms():
    """Gold scrolls that rise out of the cup and cradle the coin ring, ending in knobs."""
    parts = []
    gx, gy, gz = GEM
    for sx in (-1, 1):
        a0, a1 = (255, 208) if sx < 0 else (285, 332)
        parts += hoop(GEM, 8.75, a0, a1, 4, 0.8, 0.9, "gold")
        bx, by = gx + 8.75 * math.cos(math.radians(a1)), gy + 8.75 * math.sin(math.radians(a1))
        parts += cyl((bx, by, gz), 0.72, 1.1, "gold", cap="gold_cap")
    return parts


def finial():
    """A gold claw on top of the halo holding a small emerald, then a gold spike."""
    parts = turned([(25.8, 0.7), (26.2, 1.0), (26.5, 0.9)], "gold", "gold_cap")
    parts += turned([(26.4, 0.45), (26.75, 0.78), (27.25, 0.78), (27.6, 0.4)], "emerald", GEM_CAP,
                    glow=13, offset=emerald_uv("bead"))
    parts += turned([(27.5, 0.55), (27.8, 0.5), (28.4, 0.22), (28.9, 0.05)], "gold", "gold_cap")
    return parts


def bezel():
    """A gold hoop inside the coins that frames the floating gem."""
    return hoop(GEM, 4.65, 0, 360, 20, 1.3, 1.2, "bezel")


def halo():
    parts = []
    gx, gy, gz = GEM
    for i in range(N_COINS):
        a = math.radians(270 + i * 360 / N_COINS)
        x, y = gx + HALO_R * math.cos(a), gy + HALO_R * math.sin(a)
        z = gz + (0.17 if i % 2 == 0 else -0.17)
        parts += cyl((x, y, z), COIN_R * 0.92, COIN_T, "coin_edge", axis="z", caps=False)
        # the face: an unshaded plate either side, so the painted shine stays bright
        u, v = (i % 4) * 4, (i // 4) * 4
        t = COIN_T / 2 + 0.03
        parts.append(box((x - COIN_R, y - COIN_R, z - t), (x + COIN_R, y + COIN_R, z + t), "coins",
                         faces={"south": ("coins", [u, v, u + 4, v + 4]), "north": ("coins", [u + 4, v, u, v + 4])},
                         skip=("east", "west", "up", "down"), shade=False))
    return parts


def gem():
    gx, gy, gz = GEM
    x0, y1 = gx - 2.5, gy + 3.5
    parts = []
    for hw, hh, hd in ((2.5, 2.5, 1.0), (1.5, 3.5, 0.95), (2.0, 3.0, 1.3), (1.0, 2.0, 1.55)):
        u = [gx - hw - x0, y1 - (gy + hh), gx + hw - x0, y1 - (gy - hh)]
        parts.append(box((gx - hw, gy - hh, gz - hd), (gx + hw, gy + hh, gz + hd), "emerald",
                         faces={"south": ("emerald", u), "north": ("emerald", [u[2], u[1], u[0], u[3]])},
                         offset=emerald_uv("side"), glow=14))
    s = math.sqrt(2)
    for sx, sy, uv in ((-1, 1, [6, 0, 7, 1]), (1, 1, [7, 0, 8, 1]), (-1, -1, [6, 1, 7, 2]), (1, -1, [7, 1, 8, 2])):
        px, py = gx + 1.5 * sx, gy + 2.5 * sy
        d = box((px - s / 2, py - s / 2, gz - 0.9), (px + s / 2, py + s / 2, gz + 0.9), "emerald",
                faces={"south": ("emerald", uv), "north": ("emerald", uv)}, offset=emerald_uv("side"), glow=14)
        parts.append(turn(d, 45, "z", (px, py, gz)))
    return parts


def aura():
    gx, gy, gz = GEM
    return [box((gx - 4.4, gy - 4.4, gz - 0.01), (gx + 4.4, gy + 4.4, gz + 0.01), "aura", uv="full",
                skip=("east", "west", "up", "down"), glow=10, shade=False)]


def orbit():
    """Twelve flat chords of a ring round the head; the dust texture scrolls along them."""
    gx, gy, gz = GEM
    parts = []
    n = 12
    mid = ORBIT_R * math.cos(math.pi / n)
    for k in range(n):
        a = 360.0 * k / n
        u0 = (k % 3) * STRIP_L
        dz = 0.03 * (k % 2)          # neighbours overlap at the joints: keep them off one plane
        strip = box((gx - STRIP_L / 2, gy - ORBIT_W / 2, gz - 0.005 + dz),
                    (gx + STRIP_L / 2, gy + ORBIT_W / 2, gz + 0.005 + dz), "dust",
                    faces={"south": ("dust", [u0, 0, u0 + STRIP_L, ORBIT_W]),
                           "north": ("dust", [u0 + STRIP_L, 0, u0, ORBIT_W])},
                    skip=("east", "west", "up", "down"), glow=15, shade=False)
        # lay it along the ring (local +x = the direction of travel, counter-clockwise)
        turn(strip, a, "z", (gx, gy, gz))
        ang = math.radians(a - 90)
        parts.append(move(strip, mid * math.cos(ang), mid * math.sin(ang)))
    turn(parts, -ORBIT_TILT, "x", GEM)
    return parts


def build():
    opaque = (pommel() + grip() + shaft() + ribbon_and_tag() + coin_stack() + cup() + cabochons() + arms()
              + halo() + bezel() + finial() + gem())
    return opaque + aura() + orbit()      # translucent last


GUI_TURN = -18.0      # a slight turn about the rod in the slot shows the coins' thickness


def transforms(parts):
    d = display("sword", parts, grip=GRIP, size=SIZE, gui_rotation=display_euler(euler_matrix(0, GUI_TURN, -45)))
    # Third person: carried like a sceptre, raised and a little forward, the coin face outward.
    d["thirdperson_righthand"] = place({"y": (-0.16, 0.86, 0.48), "z": (-1.0, 0.0, 0.1)}, GRIP, "fist", 0.85 * SIZE)
    # First person: the head rises in the right of the view, clear of the crosshair.
    d["firstperson_righthand"] = place({"y": (-0.28, 0.94, -0.2), "z": (-0.5, 0.0, 0.87)}, GRIP,
                                       (0.5, -0.52, -0.88), 0.68 * SIZE, pose=None)
    return d


def models() -> dict:
    parts = build()
    return {"main": model(parts, transforms(parts))}
