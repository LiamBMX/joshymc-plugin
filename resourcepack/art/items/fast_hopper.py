"""Fast Hopper: an upgraded speed hopper that moves five items a second.

Built like a block item (block space, 16x16x16 centred on (8, 8, 8)). A tapered dark-iron
bowl leans out from a riveted base like a real funnel, edged by slim gold corner posts
and a chunky gold rim, with two engraved speed chevrons on every side; inside, its
walls slope down to a glowing orange core seated in a gold collar. Below it the body
shows the item stream through a slotted window on two sides and a gold-rimmed
lightning-bolt plate on the other two, over a tapered copper spout. Animated: items
stream down past the slots, the core and its glow on the funnel pulse, and the two
chevrons light up one after the other, top to bottom.
"""
from __future__ import annotations

import math
import random

from art.kit import (SIDES, animate, bar, box, canvas, copy, display, mix, model, place, prism, rgba, save,
                     save_animation, turn, wave)

ID = "fast_hopper"
NAME = "Fast Hopper"
KIND = "item"
COUNTERPART = "item/hopper"

# --------------------------------------------------------------------------------------
# Palettes, darkest -> lightest (shadows lean blue-violet, lights lean warm)
# --------------------------------------------------------------------------------------
IRON = ["#0d0c12", "#17161e", "#22212b", "#2e2d38", "#3c3b48", "#4d4c5a", "#63616f", "#7e7b8b", "#a3a0af"]
GOLD = ["#3b1502", "#6b2b00", "#a04d00", "#d47d00", "#ffaa00", "#ffc540", "#ffe189", "#fff6d3"]
COPPER = ["#2c0f08", "#541e0f", "#803219", "#aa4827", "#cd6339", "#e8844f", "#f5aa7c", "#ffdcc2"]
CORE = ["#3c0a01", "#7c1a02", "#c43804", "#f2600a", "#ff8c1d", "#ffb446", "#ffdc8c", "#fff8e2"]

# --------------------------------------------------------------------------------------
# Layout (model units, block space)
# --------------------------------------------------------------------------------------
PIV_Y, DROP, TAPER, T = 15.6, 6.0, 1.75, 1.8    # wall top edge, height, lean-in, thickness
SLANT = math.hypot(DROP, TAPER)                  # 6.25 along the wall
TILT = math.degrees(math.atan2(TAPER, DROP))     # 16.26 degrees
Y_RIM0, Y_RIM1 = 15.3, 16.4                      # gold rim band
POST, POST_IN = 1.6, 0.35                        # corner post size, how far its axis sits inside the corner
Y_FLOOR = 11.2                                   # bowl floor inside
Y_BASE0, Y_BASE1 = 9.0, 9.6                      # riveted base plate under the bowl
Y_BODY0, Y_BODY1 = 3.8, 9.0
Y_WIN0, Y_WIN1 = 4.5, 8.0                        # slotted window
WIN_X0, WIN_X1, MUL0, MUL1 = 5.5, 10.5, 7.5, 8.5
BP0, BP1 = 2.75, 4.5                             # body corner post footprint
FACE, RECESS = 3.0, 3.6                          # body face, depth of the item-stream plane
Y_SPOUT = 3.1                                    # bottom of the copper flange

# The bowl wall's outer face texture: u = 16 - x across (2 texels per unit), rows run
# down the slant. Two nested chevrons pointing down, centred between columns 15 and 16.
CHEV_POINTS = (7, 11)
CHEV_REACH = 4                                    # arm length in texels (10 columns wide)


# --------------------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------------------

def put(img, x: int, y: int, colour) -> None:
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), rgba(colour))


def row_of(y: float) -> int:
    """Texel row of world height y on a 32px side-view texture (2 texels per unit)."""
    return int(math.floor((16 - y) * 2 + 1e-6))


def col_of(x: float) -> int:
    return int(math.floor(x * 2 + 1e-6))


def _clamp_uv(uv):
    return [round(max(0.0, min(16.0, v)), 4) for v in uv]


def wuv(side: str, frm, to) -> list[float]:
    """Vanilla's position-locked UVs: every face samples the texture where it sits in the
    block, so neighbouring parts line up (u runs left to right seen from outside)."""
    (x0, y0, z0), (x1, y1, z1) = frm, to
    return _clamp_uv({"north": [16 - x1, 16 - y1, 16 - x0, 16 - y0],
                      "south": [x0, 16 - y1, x1, 16 - y0],
                      "east": [16 - z1, 16 - y1, 16 - z0, 16 - y0],
                      "west": [z0, 16 - y1, z1, 16 - y0],
                      "up": [x0, z0, x1, z1],
                      "down": [x0, 16 - z1, x1, 16 - z0]}[side])


def wbox(frm, to, tex: str, faces: dict | None = None, skip=(), **kw) -> dict:
    spec = {}
    for side in SIDES:
        if side in skip:
            continue
        f = (faces or {}).get(side, tex)
        spec[side] = f if isinstance(f, tuple) else (f, wuv(side, frm, to))
    return box(frm, to, tex, faces=spec, skip=skip, **kw)


def around(parts, quarters=(0, 1, 2, 3), origin=(8, 0, 8)) -> list[dict]:
    """Copies of parts built on the north side, swung round to the other sides
    (1 = west, 2 = south, 3 = east)."""
    out = []
    for q in quarters:
        c = copy(parts)
        out += turn(c, 90 * q, "y", origin) if q else c
    return out


def only(*sides):
    return tuple(s for s in SIDES if s not in sides)


def clamp(v: float, a: float = 0.0, b: float = 1.0) -> float:
    return max(a, min(b, v))


def chevron_pixels() -> dict:
    """(col, row) -> (chevron index, 0 = upper / 1 = lower row of its stroke)."""
    out = {}
    for i, p in enumerate(CHEV_POINTS):
        for c in range(15 - CHEV_REACH, 17 + CHEV_REACH):
            k = 15 - c if c <= 15 else c - 16
            out[(c, p - k - 1)] = (i, 0)
            out[(c, p - k)] = (i, 1)
    return out


def panel_edge(r: float) -> float:
    """Left edge (texel column) of the wall face left visible between the corner posts,
    at slant row r; the right edge mirrors it about column 16."""
    v = r / 2
    return 2 * (TAPER * v / SLANT + POST_IN + POST / 2)


def rivet(img, c: int, r: int) -> None:
    """A 2x2 dome-head rivet: lit crown, soft underside and a one-pixel cast shadow."""
    put(img, c, r, IRON[8])
    put(img, c + 1, r, IRON[7])
    put(img, c, r + 1, IRON[6])
    put(img, c + 1, r + 1, IRON[5])
    put(img, c + 2, r + 2, IRON[2])


# --------------------------------------------------------------------------------------
# Static textures
# --------------------------------------------------------------------------------------

def paint_bowl() -> None:
    """The bowl wall's outer face: a smooth dark plate with engraved chevron grooves, a
    shadow under the rim, rivets and a rolled lower lip."""
    side = canvas(32, fill=IRON[4])
    rows = 13
    for r in range(rows):
        left = panel_edge(r + 0.5)
        for c in range(32):
            col = (IRON[5], IRON[4], IRON[4], IRON[4], IRON[4], IRON[4], IRON[3], IRON[3])[min(max(r - 3, 0), 7)]
            if r <= 1:
                col = IRON[2]                          # shadow under the rim
            elif r == 2:
                col = IRON[3]
            elif r == rows - 1:
                col = IRON[6]                          # rolled lower lip
            edge = min(c + 0.5 - left, 32 - left - (c + 0.5))
            if 0 <= edge < 1 and r > 1:
                col = IRON[2]                          # the posts' shadow along the sides
            put(side, c, r, col)
    # machined seams split the plate in three: riveted side plates, the chevron plate
    for r in range(2, rows - 1):
        for c, (dark, lit) in ((8, (IRON[2], IRON[6])), (23, (IRON[2], IRON[6]))):
            put(side, c, r, dark)
            put(side, c + 1, r, lit)
    for c in range(9, 23):                             # the chevron plate's lit top edge
        put(side, c, 2, IRON[5])
    for c, r in ((4, 5), (5, 4), (6, 3), (26, 5), (27, 4), (5, 6), (26, 6)):
        put(side, c, r, IRON[5])                       # a soft sheen on the side plates
    chev = chevron_pixels()
    for (c, r) in chev:
        put(side, c, r, IRON[0])
    for c in range(9, 23):
        for r in range(1, rows - 1):
            if (c, r) in chev:
                continue
            above, below = (c, r - 1) in chev, (c, r + 1) in chev
            beside = (c - 1, r) in chev or (c + 1, r) in chev
            if above and below:
                put(side, c, r, mix(IRON[5], CORE[2], 0.35))   # ridge between the grooves, lit by both
            elif below:
                put(side, c, r, mix(IRON[2], CORE[1], 0.25))   # shadowed upper wall of a groove
            elif above:
                put(side, c, r, mix(IRON[6], CORE[3], 0.3))    # lower lip catching the glow
            elif beside:
                put(side, c, r, mix(IRON[2], CORE[1], 0.25))
            elif any((c + dc, r + dr) in chev for dc in (-2, -1, 0, 1, 2) for dr in (-2, -1, 0, 1, 2)):
                put(side, c, r, mix(side.getpixel((c, r)), CORE[1], 0.14))   # soft warm spill
    for c, r in ((5, 8), (25, 8)):
        rivet(side, c, r)
    save(side, "bowl_side")

    edge = canvas(16, fill=IRON[2])
    for c in range(16):
        put(edge, c, 0, IRON[4])
    save(edge, "bowl_edge")

    base = canvas(32, fill=IRON[4])
    for c in range(32):
        put(base, c, 0, IRON[7])
        put(base, c, 1, IRON[3])
        if c % 6 == 3:
            put(base, c, 0, IRON[8])
            put(base, c, 1, IRON[2])
    save(base, "base_side")

    under = canvas(32, fill=IRON[2])
    for r in range(32):
        for c in range(32):
            ring = min(c, r, 31 - c, 31 - r) - 3
            if ring >= 0:
                put(under, c, r, (IRON[3], IRON[4], IRON[3], IRON[2])[min(ring, 3)])
    for c, r in ((4, 4), (25, 4), (4, 25), (25, 25)):
        rivet(under, c, r)
    save(under, "bowl_under")


def paint_gold() -> None:
    rng = random.Random(8)
    post = canvas(32)
    for r in range(32):
        for c, col in enumerate((GOLD[6], GOLD[5], GOLD[3], GOLD[2])):
            put(post, c, r, col)
        if rng.random() < 0.3:
            put(post, 0, r, GOLD[7])                    # glints along the lit edge
    save(post, "gold_post")
    save(canvas(16, fill=GOLD[3]), "gold_post_end")

    head = canvas(16, fill=GOLD[5])
    put(head, 0, 0, GOLD[7])
    put(head, 1, 0, GOLD[6])
    put(head, 0, 1, GOLD[6])
    for i in range(3):
        put(head, 2, i, GOLD[3])
        put(head, i, 2, GOLD[3])
    save(head, "bolt_head")

    rim = canvas(32, fill=GOLD[4])
    for c in range(32):
        put(rim, c, 0, GOLD[6])
        put(rim, c, 1, GOLD[4])
        put(rim, c, 2, GOLD[2])
        if c % 7 == 3:
            put(rim, c, 1, GOLD[2])                     # rivets along the band
            put(rim, c - 1, 1, GOLD[6])
        elif rng.random() < 0.15:
            put(rim, c, 0, GOLD[7])
    save(rim, "rim_side")

    top = canvas(32, fill=GOLD[4])
    for c in range(32):
        for r, col in enumerate((GOLD[6], GOLD[4], GOLD[4], GOLD[3], GOLD[3])):
            put(top, c, r, col)
        if c % 7 == 3:
            put(top, c, 0, GOLD[7])
    save(top, "rim_top")
    save(canvas(16, fill=GOLD[2]), "rim_under")

    collar = canvas(32, fill=GOLD[4])
    for c in range(32):
        put(collar, c, 0, GOLD[6])
        put(collar, c, 1, GOLD[3])
    save(collar, "collar_side")
    save(canvas(16, fill=GOLD[5]), "collar_cap")


def paint_body() -> None:
    """Side-view textures of the body (world UVs): the window frame on north/south and the
    bolt-plate side on east/west."""
    ns = canvas(32, fill=IRON[5])
    ew = canvas(32, fill=IRON[5])
    r0, r1 = row_of(Y_BODY1 - 0.01), row_of(Y_BODY0 + 0.01)
    for img in (ns, ew):
        for r in range(r0, r1 + 1):
            for c in range(32):
                col = IRON[5]
                if r == r0:
                    col = IRON[2]                        # shadow under the bowl
                elif r == r0 + 1:
                    col = IRON[4]
                elif r == r1:
                    col = IRON[3]
                put(img, c, r, col)
    wc0, wc1 = col_of(WIN_X0), col_of(WIN_X1) - 1
    wr0, wr1 = row_of(Y_WIN1 - 0.01), row_of(Y_WIN0 + 0.01)
    for c in range(wc0 - 1, wc1 + 2):
        put(ns, c, wr0 - 1, GOLD[5])
        put(ns, c, wr1 + 1, GOLD[3])
    for r in range(wr0 - 1, wr1 + 2):
        put(ns, wc0 - 1, r, GOLD[4])
        put(ns, wc1 + 1, r, GOLD[3])
    put(ns, wc0 - 1, wr0 - 1, GOLD[7])
    for r in range(wr0, wr1 + 1):                         # the mullion: lit left edge
        put(ns, col_of(MUL0), r, IRON[7])
        put(ns, col_of(MUL1) - 1, r, IRON[4])
    for img in (ns, ew):
        rivet(img, 9, r0 + 2)
        rivet(img, 21, r0 + 2)
    save(ns, "body_ns")
    save(ew, "body_ew")

    post = canvas(32, fill=IRON[5])
    for r in range(32):
        for c, col in enumerate((IRON[7], IRON[6], IRON[5], IRON[4])):
            put(post, c, r, col)
    for r in (1, 2, 8, 9):                              # gold ferrules near both ends
        for c, col in enumerate((GOLD[6], GOLD[5], GOLD[4], GOLD[3])):
            put(post, c, r, col if r in (1, 8) else GOLD[2 + (c < 2)])
    put(post, 0, 1, GOLD[7])
    put(post, 0, 8, GOLD[7])
    save(post, "body_post")
    save(canvas(16, fill=IRON[2]), "body_bottom")

    plate = canvas(32, fill=IRON[1])
    w, h = 10, 9
    for r in range(h):
        for c in range(w):
            ring = min(c, r, w - 1 - c, h - 1 - r)
            if ring == 0:
                col = GOLD[6] if (r == 0 or c == 0) else GOLD[2]
                if (r == 0 and c == w - 1) or (c == 0 and r == h - 1):
                    col = GOLD[4]
            elif ring == 1:
                col = IRON[0] if (r == 1 or c == 1) else IRON[2]
            else:
                col = IRON[1]
            put(plate, c, r, col)
    save(plate, "bolt_plate")
    save(canvas(16, fill=GOLD[3]), "bolt_plate_side")


def paint_copper() -> None:
    fl = canvas(32, fill=COPPER[4])
    for c in range(32):
        put(fl, c, 0, COPPER[6])
        put(fl, c, 1, COPPER[3])
        if c % 6 == 2:
            put(fl, c, 0, COPPER[7])
    save(fl, "copper_flange")

    def facets(name, rows):
        img = canvas(32, fill=COPPER[4])
        for r in range(32):
            shade_ = rows[min(r, len(rows) - 1)]
            for c, col in enumerate((COPPER[6], COPPER[5], COPPER[5], COPPER[4], COPPER[3])):
                put(img, c, r, COPPER[min(7, max(0, COPPER.index(col) + shade_))])
        save(img, name)

    facets("copper_collar", [1, 0, -1])
    facets("copper_pipe", [-1, 0, 0, -1])
    facets("copper_tip", [1, -1])
    save(canvas(16, fill=COPPER[5]), "copper_cap")
    save(canvas(16, fill=mix(CORE[1], IRON[0], 0.3)), "hole")   # the mouth glows where the stream leaves


# --------------------------------------------------------------------------------------
# Animated textures
# --------------------------------------------------------------------------------------

def ember(b: float) -> str:
    """A glow colour for brightness b (0 = dim ember, 1 = white-hot)."""
    ramp_ = CORE[1:]
    x = clamp(b) * (len(ramp_) - 1)
    i = min(len(ramp_) - 2, int(x))
    return mix(ramp_[i], ramp_[i + 1], x - i)


BOLT = [
    "....###.",
    "...###..",
    "..#####.",
    "....##..",
    "...##...",
    "..##....",
    "..#.....",
]


def bolt_frame(t: float):
    """The lightning bolt, brightening with the core's pulse, with a crisp drop shadow."""
    img = canvas(32)
    b = wave(t)
    cells = {(c, r) for r, line in enumerate(BOLT) for c, ch in enumerate(line) if ch == "#"}
    for (c, r) in cells:                               # a crisp drop shadow down and right
        for n in ((c + 1, r), (c, r + 1), (c + 1, r + 1)):
            if n not in cells and n[0] + 1 < 9 and n[1] + 1 < 8:
                put(img, n[0] + 1, n[1] + 1, mix(IRON[0], CORE[1], 0.25 * b))
    for (c, r) in cells:
        left, upper = (c - 1, r) not in cells, (c, r - 1) not in cells
        right = (c + 1, r) not in cells
        col = mix(GOLD[5], GOLD[6], b)
        if left or upper:
            col = mix(GOLD[6], GOLD[7], b)
        elif right:
            col = mix(CORE[4], CORE[5], b)
        put(img, c + 1, r + 1, col)
    return img


def chevron_frame(t: float):
    """The upper chevron lights, then the lower one, then both rest as dim embers."""
    img = canvas(32)
    f = t * 8
    for (c, r), (i, part) in chevron_pixels().items():
        d = abs(f - 2 * i)
        d = min(d, 8 - d)
        lit = clamp(1.0 - d / 1.5)
        put(img, c, r, ember(0.2 + 0.62 * lit + (0.08 if part == 0 else 0.0)))
    return img


def core_frame(t: float):
    """Side faces of the core: white-hot at the crown, deep orange at the base."""
    img = canvas(32)
    b = wave(t)
    for r in range(32):
        for c in range(32):
            heat = 1.0 - min(1.0, r / 6.0)
            fil = 0.06 if c % 4 == 1 else 0.0
            put(img, c, r, ember(0.35 + 0.45 * heat + 0.2 * b + fil))
    return img


def core_cap_frame(t: float):
    """Five flat bands, one per step of the dome, from the outer ring to the crown."""
    img = canvas(16)
    b = wave(t)
    for i in range(5):
        fill_ = ember(0.42 + 0.13 * i + 0.14 * b)
        for c in range(3 * i, 3 * i + 3):
            for r in range(16):
                put(img, c, r, fill_)
    return img


def funnel_frame(t: float):
    """Inner face of a wall: iron ribs washed with orange light from the core below,
    darker in the corners."""
    img = canvas(32)
    b = wave(t)
    for r in range(32):
        rib = r % 3
        base = (IRON[5], IRON[3], IRON[3])[rib] if 2 < r < 11 else IRON[2]
        if r <= 2:
            base = IRON[1]
        reflect = clamp((r - 1.5) / 8.0) ** 0.9 * (0.42 + 0.5 * b)
        left = 2 * (1.87 + TAPER * (r / 2) / SLANT)
        for c in range(32):
            edge = min(c + 0.5 - left, 32 - left - (c + 0.5))
            col = mix(base, CORE[3] if reflect < 0.55 else CORE[4], min(0.88, reflect))
            if edge < 1.5:
                col = mix(col, IRON[0], 0.5)
            put(img, c, r, col)
    return img


def floor_frame(t: float):
    img = canvas(32)
    b = wave(t)
    for r in range(32):
        for c in range(32):
            d = max(abs(c - 15.5), abs(r - 15.5)) / 10.0
            glow = clamp(1.0 - d) * (0.45 + 0.4 * b)
            base = IRON[2] if (c + r) % 4 else IRON[3]
            put(img, c, r, mix(base, CORE[3], glow))
    return img


SPRITES = [
    # (rows, palette); 'a' dark .. 'd' highlight
    ([".cd.", "cbbc", "abba", ".aa."], {"a": "#1d7b89", "b": "#3fd5d0", "c": "#8ff3ec", "d": "#e8fffd"}),   # diamond
    (["cddc", "bbbb", "abba", "...."], {"a": "#6f6f79", "b": "#bcbcc6", "c": "#e2e2ea", "d": "#ffffff"}),   # iron
    (["cddc", "bbbb", "abba", "...."], {"a": "#9a6a06", "b": "#e9bf22", "c": "#fbe45a", "d": "#fffbc0"}),   # gold
    ([".c..", "cbc.", ".bab", "..b."], {"a": "#6e0000", "b": "#c81010", "c": "#ff5c4a", "d": "#ff5c4a"}),   # redstone
    ([".cd.", "cbbc", "abba", ".aa."], {"a": "#0b6630", "b": "#17c45a", "c": "#7ef0a7", "d": "#dcffe8"}),   # emerald
    ([".cc.", "bcab", "abba", ".aa."], {"a": "#4b4b52", "b": "#7c7c85", "c": "#a8a8b0", "d": "#a8a8b0"}),   # cobblestone
    ([".bc.", "abbb", "aaba", ".aa."], {"a": "#101014", "b": "#2c2c33", "c": "#5b5b66", "d": "#5b5b66"}),   # coal
    (["cddc", "bbbb", "abba", "...."], {"a": "#803219", "b": "#cd6339", "c": "#f5aa7c", "d": "#ffdcc2"}),   # copper
]
# (left texel of the 4px sprite, sprite, row offset) for the two slots (32-row period)
STREAM = [
    (11, 0, 0), (11, 2, 8), (11, 6, 16), (11, 7, 24),
    (17, 1, 4), (17, 4, 12), (17, 5, 20), (17, 3, 28),
]


def flow_frame(t: float):
    img = canvas(32, fill=IRON[1])
    wr0 = row_of(Y_WIN1 - 0.01)
    pulse = 0.82 + 0.18 * wave(t)
    for r in range(32):
        k = clamp(1.0 - (r - wr0) / 9.0) * pulse       # lit from the core above, fading down
        for c in range(32):
            base = IRON[1] if c % 6 not in (3, 4) else IRON[0]
            put(img, c, r, mix(base, CORE[3] if k > 0.6 else CORE[2], 0.25 + 0.45 * k))
    shift = t * 32
    for left, s, off in STREAM:
        rows, pal = SPRITES[s]
        y = int(round(off + shift)) % 32
        for dy in (-3, -2, -1):                        # a speed streak trailing above
            yy = (y + dy) % 32
            a = 0.2 + 0.1 * (3 + dy)
            put(img, left + 1, yy, mix(img.getpixel((left + 1, yy)), pal["b"], a))
            put(img, left + 2, yy, mix(img.getpixel((left + 2, yy)), pal["c"], a))
        for j, line in enumerate(rows):
            for i, ch in enumerate(line):
                if ch != ".":
                    put(img, left + i, (y + j) % 32, pal[ch])
    return img


def textures() -> None:
    paint_bowl()
    paint_gold()
    paint_body()
    paint_copper()
    save_animation(animate(chevron_frame, 8), "chevrons", frametime=2, interpolate=True)
    save_animation(animate(core_frame, 8), "core", frametime=4, interpolate=True)
    save_animation(animate(core_cap_frame, 8), "core_cap", frametime=4, interpolate=True)
    save_animation(animate(funnel_frame, 8), "funnel", frametime=4, interpolate=True)
    save_animation(animate(floor_frame, 8), "floor", frametime=4, interpolate=True)
    save_animation(animate(flow_frame, 16), "flow", frametime=2)
    save_animation(animate(bolt_frame, 8), "bolt", frametime=4, interpolate=True)


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def corner(y: float) -> float:
    """How far the outer corner line of the tapered bowl sits in from the block edge."""
    return TAPER * (PIV_Y - y) / DROP


def north_wall() -> list[dict]:
    """The north wall, built upright and then leant in about its top edge: two coplanar
    strips, each as wide as the taper at its middle, so the corner posts hide the steps."""
    parts = []
    half = SLANT / 2
    for k in range(2):
        v0, v1 = k * half, (k + 1) * half
        inset = TAPER * (v0 + v1) / 2 / SLANT
        x0, x1 = inset, 16 - inset
        outer = [round(16 - x1, 4), round(v0, 4), round(16 - x0, 4), round(v1, 4)]
        inner = [round(x0, 4), round(v0, 4), round(x1, 4), round(v1, 4)]
        faces = {"north": ("bowl_side", outer), "down": "bowl_edge"}
        parts.append(box((x0, PIV_Y - v1, 0), (x1, PIV_Y - v0, T), "bowl_side", faces=faces,
                         skip=("east", "west", "up", "south") + (("down",) if k == 0 else ())))
        # the inside of the funnel, on its own plane so it can glow softly with the core
        parts.append(box((x0, PIV_Y - v1, T), (x1, PIV_Y - v0, T), "funnel", faces={"south": ("funnel", inner)},
                         skip=only("south"), glow=6))
    parts.append(box((0, PIV_Y - SLANT, -0.07), (16, PIV_Y, -0.07), "chevrons",
                     faces={"north": ("chevrons", [0, 0, 16, round(SLANT, 4)])}, skip=only("north"),
                     glow=13, shade=False))
    return turn(parts, -TILT, "x", (8, PIV_Y, 0))


def bowl() -> list[dict]:
    rim = box((-0.2, Y_RIM0, -0.2), (13.9, Y_RIM1, 2.1), "rim_side",
              faces={"up": "rim_top", "down": "rim_under"}, skip=("east",))
    head = box((0.35, Y_RIM1, 0.35), (1.55, Y_RIM1 + 0.45, 1.55), "bolt_head", skip=("down",))
    y_top, y_bot = 15.65, 9.45
    post = bar((corner(y_top) + POST_IN, y_top, corner(y_top) + POST_IN),
               (corner(y_bot) + POST_IN, y_bot, corner(y_bot) + POST_IN),
               POST, POST, "gold_post", faces={"up": "gold_post_end", "down": "gold_post_end"})
    parts = around(north_wall() + [rim, head, post])
    lo, hi = (TAPER - 0.15, Y_BASE0, TAPER - 0.15), (16 - TAPER + 0.15, Y_BASE1, 16 - TAPER + 0.15)
    parts.append(box(lo, hi, "base_side", faces={"down": ("bowl_under", wuv("down", lo, hi))}, skip=("up",)))
    lo, hi = (3.0, Y_FLOOR - 0.4, 3.0), (13.0, Y_FLOOR, 13.0)
    parts.append(box(lo, hi, "floor", faces={"up": ("floor", wuv("up", lo, hi))}, skip=only("up")))
    return parts


def core() -> list[dict]:
    parts = prism((8, Y_FLOOR + 0.35, 8), 3.45, 0.7, "collar_side", cap="collar_cap")
    base = Y_FLOOR + 0.4
    profile = [(0.0, 3.1), (0.8, 3.1), (1.6, 2.75), (2.3, 2.2), (2.85, 1.45), (3.2, 0.65)]
    top = base + profile[-1][0]
    for i, ((h0, r0), (h1, r1)) in enumerate(zip(profile, profile[1:])):
        slabs = prism((8, base + (h0 + h1) / 2, 8), (r0 + r1) / 2, h1 - h0, "core", cap="core_cap",
                      offset=(0, round(top - (base + h1), 4)), glow=15, shade=False)
        for s in slabs:                                # each step's cap takes its own flat band
            for side in ("up", "down"):
                s["faces"][side]["uv"] = [3 * i + 0.5, 1, 3 * i + 2.5, 15]
        parts += slabs
    return parts


def body() -> list[dict]:
    parts = [wbox((FACE, Y_BODY0, RECESS), (16 - FACE, Y_BODY1, 16 - RECESS), "body_ew",
                  faces={"down": "body_bottom"}, skip=("up", "north", "south"))]
    lo, hi = (FACE, Y_BODY0, RECESS), (16 - FACE, Y_BODY1, RECESS)
    stream = box(lo, hi, "flow", faces={"north": ("flow", wuv("north", lo, hi))}, skip=only("north"),
                 glow=8, shade=False)
    parts += around([stream], (0, 2))
    frame = [
        wbox((BP1, Y_WIN1, FACE), (16 - BP1, Y_BODY1, RECESS), "body_ns", skip=("up", "south")),   # top rail
        wbox((BP1, Y_BODY0, FACE), (16 - BP1, Y_WIN0, RECESS), "body_ns", faces={"down": "body_bottom"},
             skip=("south",)),
        wbox((BP1, Y_WIN0, FACE), (WIN_X0, Y_WIN1, RECESS), "body_ns", skip=("south", "up", "down")),
        wbox((WIN_X1, Y_WIN0, FACE), (16 - BP1, Y_WIN1, RECESS), "body_ns", skip=("south", "up", "down")),
        wbox((MUL0, Y_WIN0, FACE + 0.2), (MUL1, Y_WIN1, RECESS), "body_ns", skip=("south", "up", "down")),
    ]
    parts += around(frame, (0, 2))
    post = box((BP0, Y_BODY0, BP0), (BP1, Y_BODY1, BP1), "body_post", faces={"down": "body_bottom"}, skip=("up",))
    parts += around([post])
    plate = box((16 - FACE, 4.3, 5.5), (16 - FACE + 0.4, 8.7, 10.5), "bolt_plate_side",
                faces={"east": ("bolt_plate", [0, 0, 5, 4.4])}, skip=("west",))
    bolt = box((16 - FACE + 0.46, 4.3, 5.5), (16 - FACE + 0.46, 8.7, 10.5), "bolt",
               faces={"east": ("bolt", [0, 0, 5, 4.4])}, skip=only("east"), glow=13, shade=False)
    parts += around([plate, bolt], (0, 2))
    return parts


def spout() -> list[dict]:
    parts = [box((4.0, Y_SPOUT, 4.0), (12.0, Y_BODY0, 12.0), "copper_flange", faces={"down": "copper_cap"},
                 skip=("up",))]
    parts += prism((8, 2.7, 8), 2.8, 0.8, "copper_collar", cap="copper_cap")
    parts += prism((8, 1.55, 8), 2.25, 1.5, "copper_pipe", cap="copper_cap")
    parts += prism((8, 0.45, 8), 1.8, 0.7, "copper_tip", cap="copper_cap")
    parts += prism((8, 0.08, 8), 1.2, 0.04, "hole", cap="hole", glow=9, shade=False)
    return parts


def build() -> list[dict]:
    return bowl() + core() + body() + spout()


def models() -> dict:
    parts = build()
    d = display(KIND, parts)
    # Held like a vanilla block in third person; in first person it sits fully in view at
    # the lower right, tipped toward the camera so the glowing funnel shows.
    d["thirdperson_righthand"] = {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375] * 3}
    d["firstperson_righthand"] = place({"y": (0.0, 0.9, 0.44), "z": (0.7, 0, 0.7)}, (8, 8, 8),
                                       (0.56, -0.4, -0.95), 0.42, pose=None)
    return {"main": model(parts, d)}
