"""Great Pumpkin Pie: a towering deep-dish pumpkin pie on a midnight-purple cake stand.

Eight wedge slices, each with a flared, fluted golden crust, a caramel zig-zag that runs
over the rim and drips down the crust, a piped whipped-cream rosette with a candy corn,
and a stubby purple or toxic-green candle with a flickering flame. The round heart of the
pie stays on the stand to the end, crowned by a swirl of cream, a glowing carved
jack-o'-lantern and two little chocolate bats on picks; sugared maple leaves lie on the
stand's spider-web glaze.

Eating a slice takes its candle with it (the latest one lies snuffed on the plate, still
smoking), shows the layered cross-section of its neighbours and of the heart (crust,
spiced custard with a cinnamon ripple, a cream layer and the glossy top), leaves crumbs
and a smear on the plate, and changes the jack-o'-lantern's carved face and pose: a
jaunty grin, a nervous side-glance, a shocked recoil, then a pleading droop. The
inventory shows a hand-painted, animated 32 px icon. Halloween Limited Edition.

Frame (cake): block space, y = 0 is the floor, centred on x = z = 8. Slice k faces
(sin a, 0, cos a) with a = 45 * (k - 1); every slice is built facing +Z and swung round.
"""
from __future__ import annotations

import math
import random

from art.kit import SIDES, arc, bar, box, canvas, display, mix, model, rgba, save, save_animation, sprite, turn

ID = "great_pumpkin_pie"
NAME = "Great Pumpkin Pie"
KIND = "cake"

# --------------------------------------------------------------------------------------
# Palettes (darkest -> lightest), each hue-shifted: shadows lean purple, lights lean gold
# --------------------------------------------------------------------------------------
CRUST = ["#3d1c0c", "#633016", "#8f4d1e", "#b96d29", "#d98f37", "#ecae4c", "#f7cb74", "#fee6ae"]
CUSTARD = ["#4e1706", "#7c290a", "#a83e0e", "#cf5a16", "#e97a22", "#f79a38", "#ffc063", "#ffe3a6"]
CREAM = ["#6f6488", "#9f94b6", "#cdc4d8", "#ece5ea", "#faf5ef", "#ffffff"]
CINN = ["#3e1a0c", "#6a3319", "#955028", "#b87445", "#d49a6a"]
CARAMEL = ["#4a1d04", "#7a360a", "#a95512", "#d27d1e", "#eea43a", "#ffcf6e", "#fff1c4"]
PLUM = ["#0e0719", "#1a0f2e", "#281846", "#3a2463", "#523486", "#7050ad", "#9a7fd6", "#c4b2f0"]
IRON = ["#060509", "#0f0d15", "#1a1722", "#282333", "#3c3549", "#5a5170"]
TRIM = ["#6e2305", "#a8400b", "#dc6614", "#fa8e2a", "#ffb85c", "#ffe0a0"]
GREEN = ["#0f4a1a", "#1c7a28", "#35b23a", "#6fe656", "#c6ff9e"]
PUMPKIN = ["#5c1a04", "#963608", "#cc540f", "#f07018", "#ff8e28", "#ffae4e", "#ffd08c"]
GLOW = ["#8a2406", "#d0560c", "#ff9416", "#ffc83a", "#ffec86", "#fffbe2"]
FLAME = ["#e04a06", "#ff7a14", "#ffb02e", "#ffe070", "#fff8d0"]
WAXP = ["#140a26", "#241444", "#382066", "#51308e", "#7250b8", "#9a7ce0"]
WAXG = ["#0b240f", "#153f19", "#1f6325", "#2f9133", "#58c94c", "#9df27f"]
CANDY_Y = ["#b86f0e", "#f0a91f", "#ffd23f", "#fff09a"]
CANDY_O = ["#9c3a0c", "#e0621a", "#ff8a2a", "#ffb869"]
CANDY_W = ["#b9ae98", "#ece4d2", "#fffaf0", "#ffffff"]
STEM = ["#1e2a0c", "#344416", "#4e5f1e", "#6d7d2a", "#909f44"]
CHOC = ["#120605", "#26110b", "#3d1e14", "#583020", "#7c4c36"]
MAPLE = ["#5c1006", "#96200a", "#c93a10", "#ec6418", "#ff9a3a"]

# --------------------------------------------------------------------------------------
# Layout (model units)
# --------------------------------------------------------------------------------------
CX, CZ = 8.0, 8.0
S22, C22 = math.sin(math.radians(22.5)), math.cos(math.radians(22.5))
S11, C11 = math.sin(math.radians(11.25)), math.cos(math.radians(11.25))

# The cake stand: foot, iron stem with a knot, collar and a wide glazed plate with a lip.
Y_FOOT1, Y_KNOT0, Y_KNOT1, Y_COLLAR, Y_PLATE0 = 0.5, 0.8, 1.2, 1.5, 1.75
Y_BASE, Y_LIP = 2.5, 3.05                      # plate top (the pie's floor), lip top
R_FOOT, R_STEM, R_KNOT, R_COLLAR = 4.2, 0.95, 1.45, 2.4
R_PLATE, R_LIP0 = 8.8, 8.1                     # apothems of the plate and the lip's inner wall

# The pie: a flared 16-sided crust wall (two facets per slice) round a round heart.
R_COL = 2.8                                    # the heart that stays with the centrepiece
R_BOT, R_TOP = 6.3, 7.5                        # outer crust radius at the plate and at the rim
WALL = 1.0                                     # crust wall thickness
Y_BOT, Y_CR0, Y_CR1, Y_TOP, Y_RIM = 3.5, 6.0, 7.0, 8.0, 8.5   # crust / custard / cream / top
T_CUT = 0.7                                    # width of the strip that caps a cut edge
LID_M = 0.36                                   # how far a lid overlaps a neighbour that is there
AP_BOT, AP_TOP = R_BOT * C11, R_TOP * C11      # apothems of the outer wall
TILT = math.degrees(math.atan2(AP_TOP - AP_BOT, Y_RIM - Y_BASE))
FACET_LEN = math.hypot(AP_TOP - AP_BOT, Y_RIM - Y_BASE)
FACET_W = 2 * R_TOP * S11


def r_out(y: float) -> float:
    """Outer crust radius at height y (at the facet corners)."""
    return R_BOT + (y - Y_BASE) * (R_TOP - R_BOT) / (Y_RIM - Y_BASE)


R_FILL = r_out(Y_TOP) - WALL                   # where the custard top meets the crust

# Toppings on each slice (slice frame: +Z is the slice's centreline).
R_CANDLE, R_ROSE = 4.15, 5.4
Y_WAX = Y_TOP + 1.05                           # candle top
ZIG = [(-22.5, 3.55), (-7.5, 6.3), (0.0, 3.55), (7.5, 6.3), (22.5, 3.55)]   # caramel zig-zag

# The centrepiece.
Y_PUMP = 9.7                                   # jack-o'-lantern bottom
# How the jack-o'-lantern holds itself as the pie goes (Euler x, y, z degrees about its base):
# a jaunty tilt, a nervous lean, a shocked recoil, then a pleading droop toward you.
POSES = {"grin": (0.0, 0.0, 6.0), "nervous": (-4.0, 0.0, -5.0), "shock": (-10.0, 0.0, 0.0), "sad": (9.0, 0.0, -7.0)}
FACES = {0: "grin", 1: "grin", 2: "nervous", 3: "nervous", 4: "shock", 5: "shock", 6: "sad", 7: "sad"}


def polar(phi: float, r: float) -> tuple[float, float]:
    """Model (x, z) at angle phi (degrees from +Z toward +X) and radius r round the centre."""
    a = math.radians(phi)
    return CX + r * math.sin(a), CZ + r * math.cos(a)


# --------------------------------------------------------------------------------------
# Texture helpers
# --------------------------------------------------------------------------------------

def put(img, x: int, y: int, colour) -> None:
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), rgba(colour))


def line(x0: int, y0: int, x1: int, y1: int) -> list[tuple[int, int]]:
    """Bresenham: a clean single-texel line."""
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


def polyline(points, scale: float = 2.0) -> list[tuple[int, int]]:
    """Texels of a model-space polyline on a texture with `scale` texels per unit."""
    out = []
    for (x0, z0), (x1, z1) in zip(points, points[1:]):
        for p in line(int(x0 * scale), int(z0 * scale), int(x1 * scale), int(z1 * scale)):
            if p not in out:
                out.append(p)
    return out


def slice_texels():
    """(tx, ty, dx, dz, r, phi) for every texel of a 32px slice-frame texture (2 texels per
    unit, texel = 2 * model coordinate), relative to the pie's centre."""
    for ty in range(32):
        for tx in range(32):
            dx, dz = (tx + 0.5) / 2 - CX, (ty + 0.5) / 2 - CZ
            yield tx, ty, dx, dz, math.hypot(dx, dz), math.degrees(math.atan2(dx, dz))


def side_dist(dx: float, dz: float) -> tuple[float, float]:
    """Signed distances past the slice's left (-22.5 deg) and right (+22.5 deg) cut planes."""
    return -dx * C22 - dz * S22, dx * C22 - dz * S22


def layer(y: float, u: int) -> str | None:
    """The pie's layered section at height y (u = texel column): a baked bottom crust, smooth
    spiced custard with one wavy cinnamon ripple, a cream layer and the glossy top with its
    baked skin. Clean bands, so it reads at a glance."""
    if y < Y_BASE:
        return None
    if y < Y_BOT:                                                          # bottom crust
        if y < Y_BASE + 0.5:
            return CRUST[3] if u % 4 else CRUST[2]
        return CRUST[6] if u % 3 else CRUST[5]
    if y < Y_CR0:                                                          # spiced custard
        ripple = 4.75 + (0.5 if (u // 2) % 3 == 0 else (-0.5 if (u // 2) % 3 == 2 else 0.0))
        if abs(y - ripple) < 0.25:
            return CINN[3]
        if y > Y_CR0 - 0.5 and u % 5 == 1:
            return CUSTARD[6]                                              # a moist gleam
        return CUSTARD[5]
    if y < Y_CR1:                                                          # cream layer
        if y < Y_CR0 + 0.5 and u % 3 == 0:
            return CREAM[4]
        return CREAM[5]
    if y < Y_TOP:                                                          # glossy top
        return CUSTARD[3] if y > Y_TOP - 0.5 else CUSTARD[4]
    return None


# --------------------------------------------------------------------------------------
# Textures: the pie
# --------------------------------------------------------------------------------------

def zig_texels() -> list[tuple[int, int]]:
    """The caramel zig-zag of this slice and its neighbours, so it runs on across the seams."""
    out = []
    for rot in (-45.0, 0.0, 45.0):
        out += polyline([polar(p + rot, r) for p, r in ZIG])
    return out


def paint_lids() -> None:
    """The glossy custard top of one slice, in four versions: each cut side either overlaps
    its neighbour (so the seam never gaps) or stops just inside the cut (a strip caps it)."""
    zig = set(zig_texels())
    for left in (False, True):
        for right in (False, True):
            img = canvas(32)
            rng = random.Random(11)
            m_l = -0.34 if left else LID_M
            m_r = -0.34 if right else LID_M
            for tx, ty, dx, dz, r, phi in slice_texels():
                s_l, s_r = side_dist(dx, dz)
                if r < R_COL - 0.2 or r > R_FILL + 0.3 or s_l > m_l or s_r > m_r:
                    continue
                roll = rng.random()
                c = CUSTARD[4]
                if r > R_FILL - 0.5:
                    c = CUSTARD[3]                                          # baked edge by the crust
                elif r < 3.9 and abs(phi) < 17:
                    c = CUSTARD[5]                                          # glossy sheen
                if R_FILL - 1.1 < r < R_FILL - 0.2 and roll < 0.34:
                    c = CINN[3] if roll < 0.22 else CINN[2]                 # cinnamon dusting
                if 4.3 < r < 4.9 and abs(abs(phi) - 14) < 3:
                    c = CUSTARD[6]                                          # wet glints
                if (tx, ty) in zig:
                    c = CARAMEL[2] if (tx + ty) % 3 else CARAMEL[4]         # deep amber, wet glints
                elif (tx, ty - 1) in zig:
                    c = CUSTARD[3]                                          # the drizzle's shadow
                if not left and s_l > LID_M - 0.5:
                    c = CUSTARD[3]                                          # knife line
                if not right and s_r > LID_M - 0.5:
                    c = CUSTARD[3]
                put(img, tx, ty, c)
            save(img, f"lid_{'e' if left else 'c'}{'e' if right else 'c'}")


def paint_cut() -> None:
    """The cross-section on a cut plane, texel (2r, 2(16 - y)): the layers out to the crust,
    then the flared crust wall; empty above the custard and outside the crust."""
    img = canvas(32)
    for ty in range(32):
        y = 16 - (ty + 0.5) / 2
        for tx in range(32):
            r = (tx + 0.5) / 2
            ro = r_out(y)
            if y < Y_BASE or y > Y_RIM or r > ro or r < R_COL - 0.4:
                continue
            if r > ro - WALL or (y < Y_BOT and r > ro - WALL - 0.5):       # the crust wall
                if y > Y_RIM - 0.5:
                    c = CRUST[5]
                elif r > ro - 0.5:
                    c = CRUST[3]                                           # baked outer skin
                else:
                    c = CRUST[6] if ty % 3 else CRUST[5]
            else:
                c = layer(y, tx)
                if c is None:
                    continue
            put(img, tx, ty, c)
    save(img, "cut")

    top = canvas(32)                                                       # the strip on a cut edge
    rng = random.Random(4)
    for ty in range(32):
        r = (ty + 0.5) / 2
        for tx in range(2):
            c = CUSTARD[3] if r > R_FILL - 0.5 else CUSTARD[4]
            if R_FILL - 1.1 < r < R_FILL - 0.2 and rng.random() < 0.3:
                c = CINN[3]
            if tx == 1 and c == CUSTARD[4]:
                c = CUSTARD[5]                                             # the fresh cut edge
            put(top, tx, ty, c)
    save(top, "cut_top")

    core = canvas(32)                                                      # the round heart's side
    for ty in range(32):
        y = 16 - (ty + 0.5) / 2
        for tx in range(32):
            c = layer(y, tx)
            if c is not None:
                put(core, tx, ty, c)
    save(core, "core")


def paint_crust() -> None:
    rng = random.Random(8)
    for name, seed in (("crust_side", 1), ("crust_side_b", 2)):
        img = canvas(32)
        rr = random.Random(seed)
        band = (4, 5, 5, 5, 6, 5, 5, 5, 5, 4, 4, 3)                        # baked lip, golden, baked foot
        flute = (-1, 0, 1, 1, 0, -1)                                       # one soft flute per facet
        flakes = [(rr.randrange(0, 5), rr.randrange(2, 9)) for _ in range(3)]
        for ty in range(12):
            for tx in range(6):
                k = band[ty] + flute[tx]
                if ty == 0 and rr.random() < 0.35:
                    k = 3                                                  # browned spots on the lip
                put(img, tx, ty, CRUST[max(1, min(7, k))])
        for fx, fy in flakes:                                              # flaky layers catch the light
            for dx in (0, 1):
                put(img, fx + dx, fy, CRUST[min(7, band[fy] + flute[fx + dx] + 1)])
        save(img, name)

    top = canvas(32)
    for ty in range(2):
        for tx in range(6):
            c = CRUST[6] if ty == 0 else CRUST[5]
            if rng.random() < 0.2:
                c = CRUST[7]
            put(top, tx, ty, c)
    save(top, "crust_top")

    inner = canvas(32)
    for ty in range(12):
        for tx in range(6):
            put(inner, tx, ty, CRUST[5] if ty == 0 else CRUST[4])
    save(inner, "crust_inner")

    flute = canvas(32)                                                     # crimps bake the darkest
    for ty in range(32):
        for tx in range(32):
            c = (CRUST[6], CRUST[5], CRUST[4])[min(tx, 2)]
            if ty == 0:
                c = CRUST[3]                                               # the browned tip
            elif ty == 1 and tx == 0:
                c = CRUST[7]                                               # egg-wash glint
            put(flute, tx, ty, c)
    save(flute, "flute")
    end = canvas(32, fill=CRUST[4])
    put(end, 0, 0, CRUST[5])
    save(end, "flute_end")

    crumb = canvas(32)
    for ty in range(32):
        for tx in range(32):
            put(crumb, tx, ty, (CRUST[5], CRUST[6], CRUST[4])[(tx * 2 + ty) % 3])
    save(crumb, "crumb")


def paint_caramel() -> None:
    """Deep amber caramel, darker than the crust so the drips read, with a glossy edge."""
    img = canvas(32)
    for ty in range(32):
        for tx in range(32):
            c = (CARAMEL[4], CARAMEL[3], CARAMEL[2], CARAMEL[2])[min(tx, 3)]
            if tx == 0 and ty % 5 == 1:
                c = CARAMEL[6]
            put(img, tx, ty, c)
    save(img, "caramel")


def paint_cream() -> None:
    """Whipped cream is drawn self-lit (shade off), so the texture carries its own shading:
    soft piped ridges, a cool shadow at the foot and a dusting of cinnamon on top."""
    rng = random.Random(21)
    rib = canvas(32)
    for ty in range(32):
        for tx in range(32):
            c = (CREAM[5], CREAM[4], CREAM[4], CREAM[3])[tx % 4]
            if ty >= 2:
                c = (CREAM[4], CREAM[3], CREAM[3], CREAM[2])[tx % 4]
            if ty == 0 and rng.random() < 0.12:
                c = CINN[4]
            put(rib, tx, ty, c)
    save(rib, "cream_rib")

    top = canvas(32)
    for ty in range(32):
        for tx in range(32):
            c = CREAM[5] if (tx + 2 * ty) % 7 else CREAM[4]
            roll = rng.random()
            if roll < 0.04:
                c = CINN[3]
            elif roll < 0.10:
                c = CINN[4]
            put(top, tx, ty, c)
    save(top, "cream_top")
    save(canvas(32, fill=CREAM[2]), "cream_under")


def paint_bats() -> None:
    """A dark-chocolate bat cut-out (both sides) with toxic-green sugar eyes, on a striped pick."""
    bat = [
        "X...X...X",
        "XX.XXX.XX",
        "XXXXXXXXX",
        ".X.X.X.X.",
    ]
    img = canvas(16)
    for y, row in enumerate(bat):
        for x, ch in enumerate(row):
            if ch == "X":
                lit = y == 0 or bat[y - 1][x] != "X"
                put(img, x, y, CHOC[3] if lit else CHOC[1])
    put(img, 3, 2, GREEN[3])                                               # eyes either side of the snout
    put(img, 5, 2, GREEN[3])
    save(img, "bat")
    pick = canvas(32)
    for ty in range(32):
        for tx in range(32):
            put(pick, tx, ty, TRIM[3] if (ty // 2) % 2 else CREAM[4])
    save(pick, "pick")


def paint_leaves() -> None:
    """A sugared maple leaf: autumn red running to orange at the tips, a darker vein and a
    crust of sugar glinting along its edges."""
    leaf = [
        "...A...",
        ".A.B.A.",
        "ABBCBBA",
        ".BCCCB.",
        "BBCDCBB",
        "..BDB..",
        "...V...",
    ]
    img = canvas(16)
    shades = {"A": MAPLE[4], "B": MAPLE[3], "C": MAPLE[2], "D": MAPLE[1], "V": MAPLE[0]}
    for y, row in enumerate(leaf):
        for x, ch in enumerate(row):
            if ch in shades:
                put(img, x, y, shades[ch])
    for x, y in ((3, 0), (0, 2), (6, 2), (1, 1), (6, 4)):
        put(img, x, y, CREAM[5])                                           # sugar
    save(img, "maple")


def paint_candy() -> None:
    for name, pal in (("candy_y", CANDY_Y), ("candy_o", CANDY_O), ("candy_w", CANDY_W)):
        img = canvas(32)
        for ty in range(32):
            for tx in range(32):
                c = (pal[3], pal[2], pal[2], pal[1])[min(tx, 3)]
                if ty == 0:
                    c = pal[3] if tx < 3 else pal[2]
                put(img, tx, ty, c)
        save(img, name)


def paint_candles() -> None:
    for name, pal in (("wax_p", WAXP), ("wax_g", WAXG)):
        side = canvas(32)
        for ty in range(32):
            for tx in range(32):
                c = pal[3] if tx % 2 == 0 else pal[2]
                if ty == 0:
                    c = pal[5]
                elif ty == 1 and tx % 3 == 0:
                    c = pal[4]                                             # wax running down
                elif ty >= 2:
                    c = pal[2] if tx % 2 == 0 else pal[1]
                put(side, tx, ty, c)
        save(side, name)
        top = canvas(32, fill=pal[4])
        put(top, 0, 0, pal[5])
        save(top, name + "_top")
    save(canvas(32, fill=IRON[1]), "wick")
    smoke = canvas(16)                                                     # a curl of smoke, see-through
    curl = [".#..", "..#.", ".#..", "#...", ".#..", "..#.", "..#.", ".#.."]
    for y, row in enumerate(curl):
        for x, ch in enumerate(row):
            if ch == "#":
                put(smoke, x, y, (228, 224, 238, 215 - 14 * (7 - y)))
    save(smoke, "smoke")

    def flame(level: float):
        img = canvas(32)
        cols = [mix(FLAME[3], FLAME[4], level), mix(FLAME[2], FLAME[3], level), mix(FLAME[0], FLAME[1], level)]
        for ty in range(32):
            for tx in range(32):
                put(img, tx, ty, cols[min(2, ty * 3 // 32)])
        return img
    levels = (1.0, 0.55, 0.85, 0.25, 0.7, 0.4)
    for name, order, tip_order in (("a", [0, 1, 2, 3, 4, 5, 2, 1], [3, 0, 4, 1, 5, 2, 0, 4]),
                                   ("b", [4, 2, 5, 0, 3, 1, 5, 2], [1, 5, 0, 3, 2, 4, 1, 0])):
        save_animation([flame(v) for v in levels], "flame_" + name, frametime=2, interpolate=True, order=order)
        save_animation([canvas(32, fill=mix(FLAME[3], FLAME[4], v)) for v in levels], "flame_tip_" + name,
                       frametime=2, interpolate=True, order=tip_order)


# --------------------------------------------------------------------------------------
# Textures: the centrepiece
# --------------------------------------------------------------------------------------

FACE_ART = {  # 9 x 7 texels on the pumpkin's front: "Y" is carved through and glows, "T" is an
    # uncut tooth; every other texel next to a carving becomes the dark cut rim
    "grin": [
        ".........",
        ".Y.....Y.",
        "YYY...YYY",
        "....Y....",
        "Y.YYYYY.Y",
        "YYTYTYTYY",
        ".YYYYYYY.",
    ],
    "nervous": [
        ".........",
        ".YYY.YYY.",
        ".YYT.YYT.",
        ".YYY.YYY.",
        ".........",
        ".Y.Y.Y.Y.",
        "Y.Y.Y.Y.Y",
    ],
    "shock": [
        ".........",
        ".YYY.YYY.",
        ".YTY.YTY.",
        ".YYY.YYY.",
        "...YYY...",
        "..YTTTY..",
        "...YYY...",
    ],
    "sad": [
        ".........",
        "YYY...YYY",
        ".YY...YY.",
        "..Y...Y..",
        "......Y..",
        "...YYY...",
        "..Y...Y..",
    ],
}


def paint_pumpkin() -> None:
    side = canvas(32)
    ribs = (2, 4, 5, 3, 5, 3, 5, 4, 2)                                     # lobes and grooves, 9 texels
    for ty in range(32):
        for tx in range(32):
            k = ribs[tx % 9]
            if ty == 0:
                k += 1
            elif ty >= 5:
                k -= 1
            put(side, tx, ty, PUMPKIN[max(0, min(6, k))])
    save(side, "pump_side")
    corner = canvas(32)
    for ty in range(32):
        for tx in range(32):
            put(corner, tx, ty, PUMPKIN[3] if ty < 5 else PUMPKIN[2])
    save(corner, "pump_corner")

    top = canvas(32)
    for tx, ty, dx, dz, r, phi in slice_texels():
        if r > 3.2:
            continue
        rib = abs(((phi + 22.5) % 45) - 22.5) < 6
        c = PUMPKIN[5] if not rib else PUMPKIN[3]
        if r < 1.0:
            c = PUMPKIN[2]
        elif r > 2.4 and not rib:
            c = PUMPKIN[4]
        put(top, tx, ty, c)
    save(top, "pump_top")
    save(canvas(32, fill=PUMPKIN[2]), "pump_bottom")

    for name, rows in FACE_ART.items():
        def carve(level: float, rows=rows):
            img = canvas(16)
            cut = {(x, y) for y, row in enumerate(rows) for x, ch in enumerate(row) if ch == "Y"}
            for y, row in enumerate(rows):
                for x, ch in enumerate(row):
                    if ch == "Y":
                        above = (x, y - 1) in cut
                        below = (x, y + 1) in cut
                        c = GLOW[5] if not above and below else (GLOW[3] if above and not below else GLOW[4])
                        put(img, x, y, mix(c, GLOW[2], (1 - level) * 0.55))
                    elif ch == "T":
                        put(img, x, y, PUMPKIN[4])                         # an uncut tooth
                    elif any((x + dx, y + dy) in cut for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                        put(img, x, y, PUMPKIN[1])                         # the cut rim in shadow
            return img
        save_animation([carve(v) for v in (1.0, 0.8, 0.95, 0.6, 0.9, 0.75)], f"face_{name}", frametime=3,
                       interpolate=True)

    stem = canvas(32)
    for ty in range(32):
        for tx in range(32):
            put(stem, tx, ty, (STEM[3], STEM[2], STEM[1], STEM[2])[tx % 4])
    save(stem, "stem")
    save(canvas(32, fill=STEM[3]), "vine")

    leaf = canvas(32)
    shape = [
        "...X....",
        "..XXX.X.",
        "XXXXXXX.",
        ".XXXXXXX",
        "..XXXXX.",
        "...XX...",
        "....X...",
    ]
    for y, row in enumerate(shape):
        for x, ch in enumerate(row):
            if ch == "X":
                put(leaf, x, y, STEM[4] if (x + y) % 3 == 0 else STEM[3])
    save(leaf, "leaf")


# --------------------------------------------------------------------------------------
# Textures: the stand and the leftovers
# --------------------------------------------------------------------------------------

def paint_stand() -> None:
    img = canvas(64)
    rng = random.Random(5)
    spokes = []
    for n in range(8):
        a = 22.5 + 45 * n
        spokes += polyline([polar(a, R_COL), polar(a, 7.6)], 2.0)
    threads = []
    for rad in (3.7, 5.0, 6.3, 7.4):
        for n in range(8):
            a0, a1 = 22.5 + 45 * n, 67.5 + 45 * n
            threads += polyline([polar(a0, rad), polar((a0 + a1) / 2, rad - 0.45), polar(a1, rad)], 2.0)
    web = set(spokes) | set(threads)
    for ty in range(64):
        for tx in range(64):
            x, z = CX - 8.4 + (tx + 0.5) / 2, CZ - 8.4 + (ty + 0.5) / 2
            dx, dz = x - CX, z - CZ
            r = math.hypot(dx, dz)
            if r > 8.4:
                continue
            c = PLUM[2]
            if 7.8 < r:
                c = TRIM[2]
            elif (int(x * 2), int(z * 2)) in web:
                c = PLUM[5] if rng.random() < 0.8 else PLUM[6]
            put(img, tx, ty, c)
    save(img, "plate_top")

    side = canvas(32)
    for ty in range(32):
        for tx in range(32):
            c = PLUM[3]
            if ty == 0:
                c = TRIM[3]
            elif ty == 1:
                c = PLUM[4] if tx % 2 else PLUM[3]
            elif ty == 2:
                c = TRIM[2] if (tx // 2) % 2 else IRON[1]
            elif ty >= 3:
                c = PLUM[2]
            put(side, tx, ty, c)
    save(side, "plate_side")
    save(canvas(32, fill=PLUM[1]), "plate_under")

    lip = canvas(32)
    for ty in range(32):
        for tx in range(32):
            put(lip, tx, ty, TRIM[3] if ty == 0 else (TRIM[2] if ty == 1 else TRIM[1]))
    save(lip, "lip_top")
    lip_in = canvas(32)
    for ty in range(32):
        for tx in range(32):
            put(lip_in, tx, ty, PLUM[4] if ty == 0 else PLUM[2])
    save(lip_in, "lip_in")

    iron = canvas(32)
    for ty in range(32):
        for tx in range(32):
            put(iron, tx, ty, (IRON[3], IRON[4], IRON[2], IRON[1])[tx % 4])
    save(iron, "iron")
    save(canvas(32, fill=IRON[2]), "iron_top")
    knot = canvas(32)
    for ty in range(32):
        for tx in range(32):
            put(knot, tx, ty, TRIM[3] if ty == 0 else PLUM[4] if ty == 1 else PLUM[3])
    save(knot, "knot")
    foot = canvas(32)
    for ty in range(32):
        for tx in range(32):
            put(foot, tx, ty, TRIM[3] if ty == 0 else PLUM[2])
    save(foot, "foot_side")
    foot_top = canvas(32)
    for ty in range(32):
        for tx in range(32):
            put(foot_top, tx, ty, PLUM[3] if (tx + ty) % 5 else PLUM[4])
    save(foot_top, "foot_top")


def paint_leftovers() -> None:
    """What an eaten slice leaves on the plate (slice frame, like the lids): crumbs of crust
    along its outer edge, a smear of custard, a dab of cream and cinnamon."""
    for variant in range(3):
        img = canvas(32)
        rng = random.Random(40 + variant)
        smear = polyline([polar(6 - 9 * variant, 3.2), polar(-5 + 7 * variant, 5.6)])
        for tx, ty, dx, dz, r, phi in slice_texels():
            if r < R_COL or r > 7.6 or abs(phi) > 22.0:
                continue
            c = None
            if R_BOT - 0.6 < r < R_BOT + 0.9 and rng.random() < 0.22:
                c = rng.choice([CRUST[4], CRUST[5], CRUST[6]])
            elif 3.2 < r < 5.6 and rng.random() < 0.04:
                c = CINN[2]
            if c:
                put(img, tx, ty, c)
        for i, (tx, ty) in enumerate(smear):
            put(img, tx, ty, CUSTARD[4])
            put(img, tx + 1, ty, CUSTARD[5] if i % 3 else CUSTARD[6])
            put(img, tx, ty + 1, CUSTARD[2])
        cx, cz = polar(14 - 20 * variant, 4.4)
        for ox, oz, c in ((0, 0, CREAM[5]), (1, 0, CREAM[4]), (0, 1, CREAM[4]), (1, 1, CREAM[3])):
            put(img, int(cx * 2) + ox, int(cz * 2) + oz, c)
        save(img, f"smear_{variant}")


# --------------------------------------------------------------------------------------
# The inventory icon: a hand-painted 32 px sprite (like the vanilla trident's), animated so
# the jack-o'-lantern and the candles flicker. Every pixel is placed for GUI scale 2.
# --------------------------------------------------------------------------------------

def _in_ellipse(x: int, y: int, cx: float, cy: float, rx: float, ry: float) -> bool:
    return ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1.0


ICON_LANTERN = [  # 13 x 11 at x 10..22, y 3..13: digits are PUMPKIN shades, Y is carved and glows
    "...3455543...",
    "..345666543..",
    ".23Y56665Y32.",
    "23YYY565YYY32",
    "2YYYYY6YYYYY2",
    "234455Y554432",
    "2Y3YYYYYYY3Y2",
    "23YY4Y4Y4YY32",
    "123YYYYYYY321",
    ".12233333221.",
    "...122222....",
]


def icon_frame(level: float, flicker: tuple):
    """One frame of the icon; level dims the carved glow, flicker sets each candle."""
    img = canvas(32)

    def glow(c):
        return mix(c, GLOW[1], (1 - level) * 0.6)

    # the stand's plate: purple glaze, an orange rim, the darker lip underneath
    for y in range(32):
        for x in range(32):
            if _in_ellipse(x, y, 16, 28.2, 15.3, 3.4):
                put(img, x, y, PLUM[1])
            if _in_ellipse(x, y, 16, 27.3, 15.3, 3.4):
                edge = not _in_ellipse(x, y, 16, 27.3, 13.9, 2.5)
                put(img, x, y, (TRIM[3] if y < 28 else TRIM[2]) if edge else PLUM[3])
    # the flared crust: golden with soft flutes, baked darker toward the foot
    for y in range(13, 29):
        t = (y + 0.5 - 16.0) / 8.5
        half = 13.2 - 1.8 * max(0.0, min(1.0, t))
        for x in range(32):
            dx = x + 0.5 - 16
            inside = (16.0 <= y + 0.5 <= 24.5 and abs(dx) <= half) or _in_ellipse(x, y, 16, 24.5, 11.4, 2.4)
            if not inside:
                continue
            k = 5 if y < 20 else (4 if y < 23 else 3)
            if x % 3 == 1:
                k -= 1                                                     # flute grooves
            if abs(dx) > half - 1.6:
                k -= 1                                                     # the side turns away
            put(img, x, y, CRUST[max(2, k)])
    for x, top, length in ((6, 20, 2), (11, 21, 3), (17, 21, 2), (21, 21, 3), (26, 20, 2)):
        for y in range(top, top + length):                                 # caramel drips
            put(img, x, y, CARAMEL[3])
        put(img, x, top + length, CARAMEL[2])
        put(img, x, top, CARAMEL[5])
    # the top: a crimped rim round glossy custard, with a caramel zig-zag
    for y in range(9, 23):
        for x in range(32):
            if not _in_ellipse(x, y, 16, 16.0, 13.2, 5.0):
                continue
            if not _in_ellipse(x, y, 16, 16.0, 11.5, 3.8):
                c = CRUST[6] if (x + y) % 2 else CRUST[4]                  # crimps
                if y > 18:
                    c = CRUST[5] if (x + y) % 2 else CRUST[3]
            else:
                c = CUSTARD[5] if y < 15 else CUSTARD[4]
                if (x + 2 * y) % 9 == 0:
                    c = CARAMEL[4]
            put(img, x, y, c)
    for x, y in ((6, 14), (7, 14), (25, 14), (26, 14)):
        put(img, x, y, CUSTARD[6])                                         # glossy highlights
    # candles at the back, rosettes of cream at the front
    for i, (cx_, cy_) in enumerate(((5, 13), (9, 15), (23, 15), (27, 13))):
        wax = WAXP if i % 2 else WAXG
        put(img, cx_, cy_ + 1, wax[4])
        put(img, cx_, cy_ + 2, wax[2])
        f = flicker[i % len(flicker)]
        put(img, cx_, cy_, mix(FLAME[2], FLAME[4], f))
        if f > 0.45:
            put(img, cx_, cy_ - 1, mix(FLAME[1], FLAME[3], f))
    for rx, ry in ((6, 17), (11, 19), (21, 19), (26, 17)):
        for ox, oy, c in ((0, -1, CREAM[5]), (-1, 0, CREAM[4]), (0, 0, CREAM[5]), (1, 0, CREAM[4]),
                          (-1, 1, CREAM[3]), (0, 1, CREAM[4]), (1, 1, CREAM[2])):
            put(img, rx + ox, ry + oy, c)
    for cx_ in (15, 17):
        put(img, cx_, 19, CANDY_Y[2])
        put(img, cx_, 18, CANDY_O[2])
        put(img, cx_, 17, CANDY_W[2])
    # the jack-o'-lantern, with carved rims so the grin stands off the skin
    carved = {(x, y) for y, row in enumerate(ICON_LANTERN) for x, ch in enumerate(row) if ch == "Y"}
    for y, row in enumerate(ICON_LANTERN):
        for x, ch in enumerate(row):
            px, py = 10 + x, 3 + y
            if ch == "Y":
                top = (x, y - 1) not in carved
                put(img, px, py, glow(GLOW[5] if top else GLOW[4]))
            elif ch.isdigit():
                rim = any((x + dx, y + dy) in carved for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                put(img, px, py, PUMPKIN[0] if rim else PUMPKIN[int(ch)])  # the carved rim in shadow
    for x, y, c in ((16, 1, STEM[3]), (16, 2, STEM[2]), (15, 1, STEM[2]), (15, 2, STEM[3]), (16, 3, STEM[1]),
                    (17, 2, GREEN[2]), (18, 2, GREEN[3]), (19, 2, GREEN[2]), (18, 1, GREEN[2])):
        put(img, x, y, c)
    # two little chocolate bats flitting round the lantern
    for bx, by in ((2, 5), (25, 3)):
        for y, row in enumerate(("X...X", "XXXXX", ".X.X.")):
            for x, ch in enumerate(row):
                if ch == "X":
                    put(img, bx + x, by + y, CHOC[3] if y == 0 else CHOC[2])
        put(img, bx + 2, by + 1, GREEN[3])
    # a dark outline so it pops on any slot
    out = img.copy()
    src = img.load()
    dst = out.load()
    for y in range(32):
        for x in range(32):
            if src[x, y][3]:
                continue
            if any(0 <= x + dx < 32 and 0 <= y + dy < 32 and src[x + dx, y + dy][3]
                   for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                dst[x, y] = rgba("#1c0b10")
    return out


def paint_icon() -> None:
    levels = (1.0, 0.75, 0.95, 0.55, 0.9, 0.7)
    flickers = ((1.0, 0.6, 0.9, 0.4), (0.7, 1.0, 0.5, 0.9), (0.9, 0.5, 1.0, 0.7),
                (0.5, 0.9, 0.7, 1.0), (1.0, 0.7, 0.4, 0.8), (0.6, 0.8, 0.9, 0.5))
    save_animation([icon_frame(v, f) for v, f in zip(levels, flickers)], "icon", frametime=3, interpolate=True)


def textures() -> None:
    paint_lids()
    paint_cut()
    paint_crust()
    paint_caramel()
    paint_cream()
    paint_candy()
    paint_leaves()
    paint_bats()
    paint_candles()
    paint_pumpkin()
    paint_stand()
    paint_leftovers()
    paint_icon()


# --------------------------------------------------------------------------------------
# Geometry helpers
# --------------------------------------------------------------------------------------

def only(frm, to, faces: dict, **kw) -> dict:
    """A box that draws only the listed faces."""
    first = next(iter(faces.values()))
    tex = first if isinstance(first, str) else first[0]
    return box(frm, to, tex, faces=faces, skip=tuple(s for s in SIDES if s not in faces), **kw)


def disc(cx: float, cz: float, r: float, y0: float, y1: float, side: str, top: str | None = None,
         bottom: str | None = None, sides: int = 16, lift: float = 0.0, lobe: float = 0.0,
         side_uv=None, **kw) -> list[dict]:
    """A round slab of crossed boxes (16 or 8 sides). lift staggers the tops so overlapping
    caps never z-fight; lobe > 0 makes the boxes thin arms, so the outline is a piped star.
    side_uv, if given, maps every visible side face the same way."""
    half = r * math.tan(math.pi / sides) * (1 - lobe)
    angles = (0, 22.5, 45, -22.5) if sides == 16 else (0, 45)
    parts = []
    n = 0
    for a in angles:
        for along_x in (True, False):
            if along_x:
                frm, to, keep = (cx - r, y0, cz - half), (cx + r, y1 + lift * n, cz + half), ("east", "west")
            else:
                frm, to, keep = (cx - half, y0, cz - r), (cx + half, y1 + lift * n, cz + r), ("north", "south")
            if lobe:
                keep = ("north", "south", "east", "west")
            faces = {}
            if side_uv:
                faces.update({s: (side, side_uv) for s in keep})
            if top:
                faces["up"] = top
            if bottom:
                faces["down"] = bottom
            skip = tuple(s for s in ("north", "south", "east", "west") if s not in keep)
            skip += () if top else ("up",)
            skip += () if bottom else ("down",)
            e = box(frm, to, side, faces=faces, skip=skip, offset=((n * 1.3) % 8, 0), **kw)
            parts.append(turn(e, a, "y", (cx, 0, cz)))
            n += 1
    return parts


# --------------------------------------------------------------------------------------
# Geometry: one slice (built facing +Z)
# --------------------------------------------------------------------------------------

def lid(k: int, left: bool, right: bool) -> list[dict]:
    y = Y_TOP + (0.02 if k % 2 else 0.0)
    x0, x1, z0, z1 = CX - 3.1, CX + 3.1, CZ + R_COL - 0.5, CZ + R_FILL + 0.35
    tex = f"lid_{'e' if left else 'c'}{'e' if right else 'c'}"
    return [only((x0, y, z0), (x1, y, z1), {"up": (tex, [x0, z0, x1, z1])})]


def crust(k: int) -> list[dict]:
    """Two flared facets of the crust wall, with caramel running down the flute valleys."""
    parts = []
    zc = CZ + (AP_BOT + AP_TOP) / 2
    yc = (Y_BASE + Y_RIM) / 2
    lengths = ((3.4, 2.1), (2.4, 3.8), (4.2, 1.6), (1.8, 3.0))[k % 4]
    for i, ang in enumerate((-11.25, 11.25)):
        tex = "crust_side" if i == 0 else "crust_side_b"
        top = yc + FACET_LEN / 2
        wall = box((CX - FACET_W / 2, yc - FACET_LEN / 2, zc - WALL), (CX + FACET_W / 2, top + 0.015 * i, zc),
                   tex, faces={"south": (tex, [0, 0, 3, 6]), "north": ("crust_inner", [3, 0, 0, 6]),
                               "up": ("crust_top", [0, 0, 3, 1])},
                   skip=("east", "west", "down"))
        dx = -AP_TOP * math.tan(math.radians(3.75)) * (1 if ang > 0 else -1)
        length = lengths[i]
        run = box((CX + dx - 0.3, top - length, zc - 0.05), (CX + dx + 0.3, top + 0.05, zc + 0.2),
                  "caramel", skip=("up", "north"))
        bead = box((CX + dx - 0.42, top - length - 0.5, zc - 0.05), (CX + dx + 0.42, top - length + 0.15, zc + 0.34),
                   "caramel", skip=("north",))
        group = [wall, run, bead]
        turn(group, TILT, "x", (CX, yc, zc))
        cap = box((CX + dx - 0.32, Y_RIM - 0.25, CZ + R_FILL - 0.1), (CX + dx + 0.32, Y_RIM + 0.25, CZ + AP_TOP + 0.3),
                  "caramel", skip=("down",))
        group.append(cap)
        parts += turn(group, ang, "y", (CX, 0, CZ))
    return parts


def flutes() -> list[dict]:
    parts = []
    s = 1.2
    for ang in (-15.0, 0.0, 15.0):
        f = bar((CX, Y_RIM - 0.2, CZ + R_FILL + 0.1), (CX, Y_RIM - 0.75, CZ + R_TOP + 0.12), s, s, "flute",
                roll=45, faces={"up": "flute_end", "down": "flute_end"})
        parts.append(turn(f, ang, "y", (CX, 0, CZ)))
    return parts


def rosette(cx: float, cz: float, y0: float, lean: float = 20.0) -> list[dict]:
    """A piped whipped-cream star: an eight-armed base, a four-armed tier and a peak."""
    parts = []
    for n, a in enumerate((0, 45, 90, 135)):
        arm = box((cx - 0.95, y0, cz - 0.4), (cx + 0.95, y0 + 0.7 + 0.02 * n, cz + 0.4), "cream_rib",
                  faces={"up": "cream_top", "down": "cream_under"}, offset=(n, 0), shade=False)
        parts.append(turn(arm, a, "y", (cx, 0, cz)))
    for n, a in enumerate((22.5, 112.5)):
        arm = box((cx - 0.65, y0 + 0.6, cz - 0.36), (cx + 0.65, y0 + 1.15 + 0.02 * n, cz + 0.36), "cream_rib",
                  faces={"up": "cream_top"}, skip=("down",), offset=(2 * n, 0), shade=False)
        parts.append(turn(arm, a, "y", (cx, 0, cz)))
    peak = box((cx - 0.33, y0 + 1.05, cz - 0.33), (cx + 0.33, y0 + 1.5, cz + 0.33), "cream_rib",
               faces={"up": "cream_top"}, skip=("down",), shade=False)
    parts.append(turn(peak, 45, "y", (cx, 0, cz)))
    tip = box((cx - 0.16, y0 + 1.45, cz - 0.16), (cx + 0.16, y0 + 1.75, cz + 0.16), "cream_rib",
              faces={"up": "cream_top"}, skip=("down",), shade=False)
    parts.append(turn(tip, lean, "z", (cx, y0 + 1.45, cz)))
    return parts


def candy_corn(x: float, y: float, z: float, lean: float = 35.0, yaw: float = 0.0, s: float = 1.0) -> list[dict]:
    parts = [
        box((x - 0.5 * s, y, z - 0.27 * s), (x + 0.5 * s, y + 0.55 * s, z + 0.27 * s), "candy_y"),
        box((x - 0.38 * s, y + 0.55 * s, z - 0.25 * s), (x + 0.38 * s, y + 1.1 * s, z + 0.25 * s), "candy_o"),
        box((x - 0.22 * s, y + 1.1 * s, z - 0.21 * s), (x + 0.22 * s, y + 1.5 * s, z + 0.21 * s), "candy_w"),
    ]
    turn(parts, lean, "x", (x, y, z))
    if yaw:
        turn(parts, yaw, "y", (x, y, z))
    return parts


def candle(k: int) -> list[dict]:
    x, z = CX, CZ + R_CANDLE
    wax = "wax_p" if k % 2 else "wax_g"
    body = box((x - 0.5, Y_TOP - 0.1, z - 0.5), (x + 0.5, Y_WAX, z + 0.5), wax, faces={"up": wax + "_top"},
               skip=("down",))
    drip = box((x + 0.05, Y_WAX - 0.7, z + 0.45), (x + 0.35, Y_WAX + 0.05, z + 0.58), wax, skip=("down", "north"))
    wick = box((x - 0.1, Y_WAX, z - 0.1), (x + 0.1, Y_WAX + 0.35, z + 0.1), "wick", skip=("down",))
    v = "a" if k % 2 else "b"
    flame = box((x - 0.28, Y_WAX + 0.2, z - 0.28), (x + 0.28, Y_WAX + 0.95, z + 0.28), "flame_" + v,
                uv="full", glow=15, shade=False)
    tip = box((x - 0.13, Y_WAX + 0.9, z - 0.13), (x + 0.13, Y_WAX + 1.25, z + 0.13), "flame_tip_" + v,
              glow=15, shade=False)
    turn([flame, tip], 45, "y", (x, 0, z))
    return [body, drip, wick, flame, tip]


def cut_face(side: int) -> list[dict]:
    """The exposed cross-section on the slice's left (side -1) or right (+1) cut plane, and
    a strip that caps the lid's edge along the cut."""
    r0, r1 = R_COL - 0.4, R_TOP
    y_top = Y_TOP + 0.05
    if side > 0:
        x0, x1, face = CX - T_CUT, CX, "east"
        uv = [r1, 16 - Y_RIM, r0, 16 - Y_BASE]
        top_uv = [0, R_COL - 0.4, T_CUT, R_FILL + 0.1]
    else:
        x0, x1, face = CX, CX + T_CUT, "west"
        uv = [r0, 16 - Y_RIM, r1, 16 - Y_BASE]
        top_uv = [T_CUT, R_COL - 0.4, 0, R_FILL + 0.1]
    section = only((x1 if side > 0 else x0, Y_BASE, CZ + r0), (x1 if side > 0 else x0, Y_RIM, CZ + r1),
                   {face: ("cut", uv)}, shade=False)
    strip = only((x0, y_top, CZ + R_COL - 0.4), (x1, y_top, CZ + R_FILL + 0.1), {"up": ("cut_top", top_uv)})
    return turn([section, strip], 22.5 * side, "y", (CX, 0, CZ))


def slice_parts(k: int, left: bool = False, right: bool = False) -> list[dict]:
    parts = lid(k, left, right) + crust(k) + flutes()
    side = 1 if k % 2 else -1                                              # alternate so the ring isn't stamped
    parts += rosette(CX, CZ + R_ROSE, Y_TOP - 0.05, lean=20.0 * side)
    parts += candy_corn(CX + side * 1.05, Y_TOP + 0.05, CZ + R_ROSE + 0.35, lean=38.0, yaw=62.0 * side, s=1.2)
    parts += candle(k)
    if left:
        parts += cut_face(-1)
    if right:
        parts += cut_face(1)
    return turn(parts, 45 * (k - 1), "y", (CX, 0, CZ))


def snuffed(k: int) -> list[dict]:
    """The candle taken off slice k, lying snuffed on the plate where the slice was, with a
    curl of smoke still rising from its wick."""
    wax = "wax_p" if k % 2 else "wax_g"
    y0 = Y_BASE + 0.02
    x0, z0 = CX + 0.9, CZ + 4.3
    body = box((x0 - 0.45, y0, z0 - 0.65), (x0 + 0.45, y0 + 0.9, z0 + 0.65), wax,
               faces={"north": wax + "_top", "south": wax + "_top"}, skip=("down",))
    wick = box((x0 - 0.1, y0 + 0.35, z0 + 0.65), (x0 + 0.1, y0 + 0.55, z0 + 0.95), "wick", skip=("down",))
    wisp = [only((x0 - 0.4, y0 + 0.5, z0 + 0.95), (x0 + 0.4, y0 + 2.1, z0 + 0.95),
                 {"south": ("smoke", [0, 0, 4, 8]), "north": ("smoke", [4, 0, 0, 8])}),
            only((x0, y0 + 0.5, z0 + 0.55), (x0, y0 + 2.1, z0 + 1.35),
                 {"east": ("smoke", [0, 0, 4, 8]), "west": ("smoke", [4, 0, 0, 8])})]
    parts = [turn(body, 18, "y", (x0, 0, z0)), turn(wick, 18, "y", (x0, 0, z0))] + wisp
    return turn(parts, 45 * (k - 1), "y", (CX, 0, CZ))


def leftovers(k: int) -> list[dict]:
    """Crumbs and a smear on the plate where slice k was."""
    x0, x1, z0, z1 = CX - 3.1, CX + 3.1, CZ + R_COL - 0.4, CZ + 7.9
    parts = [only((x0, Y_BASE + 0.03, z0), (x1, Y_BASE + 0.03, z1), {"up": (f"smear_{k % 3}", [x0, z0, x1, z1])})]
    rng = random.Random(100 + k)
    for _ in range(3):
        px, pz = polar(rng.uniform(-15, 15), rng.uniform(3.4, 6.8))
        s = rng.uniform(0.4, 0.7)
        c = box((px - s / 2, Y_BASE, pz - s / 2), (px + s / 2, Y_BASE + s * 0.7, pz + s / 2), "crumb",
                skip=("down",))
        parts.append(turn(c, rng.uniform(0, 90), "y", (px, 0, pz)))
    return turn(parts, 45 * (k - 1), "y", (CX, 0, CZ))


# --------------------------------------------------------------------------------------
# Geometry: stand and centrepiece
# --------------------------------------------------------------------------------------

def leaves() -> list[dict]:
    """Three sugared maple leaves dropped on the stand round the foot of the pie."""
    parts = []
    for phi, r, spin, tilt in ((-28.0, 7.3, 35.0, 7.0), (118.0, 7.1, -50.0, -6.0), (203.0, 7.2, 80.0, 5.0)):
        x, z = polar(phi, r)
        leaf = only((x - 1.75, Y_BASE + 0.06, z - 1.75), (x + 1.75, Y_BASE + 0.06, z + 1.75),
                    {"up": ("maple", [0, 0, 7, 7]), "down": ("maple", [0, 7, 7, 0])})
        parts.append(turn(leaf, x=tilt, y=spin, origin=(x, Y_BASE + 0.06, z)))
    return parts


def stand() -> list[dict]:
    parts = disc(CX, CZ, R_FOOT, 0.0, Y_FOOT1, "foot_side", top="foot_top", bottom="plate_under")
    parts += disc(CX, CZ, R_STEM, Y_FOOT1, Y_COLLAR, "iron", sides=8)
    parts += disc(CX, CZ, R_KNOT, Y_KNOT0, Y_KNOT1, "knot", top="iron_top", bottom="iron_top", sides=8)
    parts += disc(CX, CZ, R_COLLAR, Y_COLLAR, Y_PLATE0 + 0.05, "iron", bottom="iron_top", sides=8)
    parts += disc(CX, CZ, R_PLATE, Y_PLATE0, Y_BASE, "plate_side", bottom="plate_under")
    e = 8.4
    parts.append(only((CX - e, Y_BASE + 0.01, CZ - e), (CX + e, Y_BASE + 0.01, CZ + e),
                      {"up": ("plate_top", [0, 0, 8.4, 8.4])}))
    width = 2 * R_PLATE * math.tan(math.pi / 16) + 0.02
    for n in range(16):
        seg = box((CX - width / 2, Y_BASE - 0.05, CZ + R_LIP0), (CX + width / 2, Y_LIP + 0.01 * (n % 2), CZ + R_PLATE),
                  "plate_side", faces={"up": ("lip_top", [0, 0, width, 1]), "north": ("lip_in", [0, 0, width, 1]),
                                       "south": ("plate_side", [0, 0, width, 1])},
                  skip=("east", "west", "down"))
        parts.append(turn(seg, 22.5 * n + 11.25, "y", (CX, 0, CZ)))
    return parts


PW, PD = 2.5, 2.25                             # jack-o'-lantern half-width, and its rounded step
PH = 3.5                                       # body height (the face is 7 texels tall)


def jack(face: str) -> list[dict]:
    """The carved jack-o'-lantern: a rounded voxel pumpkin, its glowing face decal, a curled
    stem, a leaf and a vine tendril."""
    y0, y1 = Y_PUMP + 0.4, Y_PUMP + 0.4 + PH
    cap = PD - 0.25
    parts = [
        box((CX - cap, Y_PUMP, CZ - cap), (CX + cap, y0 + 0.05, CZ + cap), "pump_corner",
            faces={"down": ("pump_bottom", [0, 0, 2 * cap, 2 * cap])}),
        box((CX - PW, y0, CZ - PD), (CX + PW, y1, CZ + PD), "pump_corner",
            faces={"east": ("pump_side", [0, 0, 2 * PD, PH]), "west": ("pump_side", [0, 0, 2 * PD, PH]),
                   "up": ("pump_top", [CX - PW, CZ - PD, CX + PW, CZ + PD])}, skip=("down",)),
        box((CX - PD, y0, CZ - PW), (CX + PD, y1, CZ + PW), "pump_corner",
            faces={"north": ("pump_side", [0, 0, 2 * PD, PH]), "south": ("pump_side", [0, 0, 2 * PD, PH]),
                   "up": ("pump_top", [CX - PD, CZ - PW, CX + PD, CZ + PW])}, skip=("down",)),
        box((CX - cap, y1 - 0.05, CZ - cap), (CX + cap, y1 + 0.45, CZ + cap), "pump_corner",
            faces={"up": ("pump_top", [CX - cap, CZ - cap, CX + cap, CZ + cap])}, skip=("down",)),
    ]
    parts.append(only((CX - PD, y0, CZ + PW + 0.02), (CX + PD, y1, CZ + PW + 0.02),
                      {"south": (f"face_{face}", [0, 0, 9, 7])}, glow=15, shade=False))
    top = y1 + 0.45
    parts.append(bar((CX + 0.1, top - 0.3, CZ - 0.1), (CX + 0.4, top + 1.1, CZ - 0.3), 0.75, 0.75, "stem"))
    parts.append(bar((CX + 0.35, top + 0.95, CZ - 0.28), (CX + 1.0, top + 1.4, CZ - 0.15), 0.5, 0.5, "stem"))
    leaf = only((CX - 2.4, top + 0.02, CZ - 0.4), (CX - 0.3, top + 0.02, CZ + 1.4),
                {"up": ("leaf", [0, 0, 4, 3.5]), "down": ("leaf", [0, 0, 4, 3.5])})
    parts.append(turn(leaf, -12, "z", (CX - 0.3, top, CZ)))
    parts += arc((CX + 1.2, top + 0.1, CZ + 0.9), 0.55, 30, 330, 4, 0.2, 0.2, "vine", plane="xz")
    return parts


def bats() -> list[dict]:
    """Two little chocolate bats on picks, flying out either side of the jack-o'-lantern."""
    parts = []
    for side, tilt, lift in ((-1, 18.0, -0.4), (1, -14.0, 0.9)):
        bx, by, bz = CX + side * 4.9, Y_PUMP + 3.0 + lift, CZ + 0.9
        parts.append(bar((CX + side * 2.3, Y_TOP + 1.3, CZ + 0.5), (bx, by - 0.3, bz - 0.05), 0.2, 0.2, "pick"))
        plate = only((bx - 2.25, by - 1.0, bz), (bx + 2.25, by + 1.0, bz),
                     {"south": ("bat", [0, 0, 9, 4]), "north": ("bat", [9, 0, 0, 4])}, glow=9)
        parts.append(turn(plate, x=-12.0, z=tilt, origin=(bx, by, bz)))
    return parts


def centrepiece(face: str) -> list[dict]:
    """What stays on the stand to the end: the layered heart of the pie, its crown of cream,
    the jack-o'-lantern (posed for the state) and the bats."""
    parts = disc(CX, CZ, R_COL, Y_BASE, Y_TOP - 0.05, "core", side_uv=[0, 16 - Y_TOP, 1.1, 16 - Y_BASE],
                 shade=False)
    parts += disc(CX, CZ, 3.25, Y_TOP - 0.1, Y_TOP + 0.95, "cream_rib", top="cream_top", bottom="cream_under",
                  sides=8, lift=0.02, lobe=0.45, shade=False)
    parts += disc(CX, CZ, 2.75, Y_TOP + 0.85, Y_PUMP + 0.45, "cream_rib", top="cream_top", sides=8, lift=0.02,
                  lobe=0.3, shade=False)
    lantern = jack(face)
    px, py, pz = POSES[face]
    turn(lantern, x=px, y=py, z=pz, origin=(CX, Y_PUMP, CZ))
    parts += bats()
    return parts + lantern


# --------------------------------------------------------------------------------------
# Models
# --------------------------------------------------------------------------------------

def state(eaten: int) -> list[dict]:
    parts = stand() + leaves() + centrepiece(FACES[eaten])
    for k in range(eaten + 1, 9):
        parts += slice_parts(k, left=eaten > 0 and k == eaten + 1, right=eaten > 0 and k == 8)
    for k in range(1, eaten + 1):
        parts += leftovers(k)
    if eaten:
        parts += snuffed(eaten)
    return parts


def models() -> dict:
    out = {}
    for n in range(8):
        parts = state(n)
        out["main" if n == 0 else f"bite_{n}"] = model(parts, display(KIND, parts, gui_rotation=(38, -28, 0),
                                                                      gui_span=15.5))
    out["gui"] = sprite("icon")
    return out
