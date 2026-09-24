"""Pumpkin Pummel: a giant lattice-top pumpkin pie baked in a blackened-iron tart tin.

A quarter of the pie is cut away (the tin stays whole) to show the layers: flaky bottom
crust, pumpkin custard and the woven lattice on top. A pinched, fluted crust runs round
the rim, two cocoa-pastry bats with glowing eyes rest on the lattice, and the crown is a
piped swirl of whipped cream that turns into a little ghost with toxic-green glinting
eyes, dusted with cinnamon and topped with a candy-corn piece. Halloween Limited Edition.
"""
from __future__ import annotations

import math
import random

from art.kit import bar, box, canvas, display, fill, fit, model, place, ramp, rgba, save, turn

ID = "pumpkin_pummel"
NAME = "Pumpkin Pummel"
KIND = "food"

# --------------------------------------------------------------------------------------
# Palettes (darkest -> lightest)
# --------------------------------------------------------------------------------------
PASTRY = ramp("#c98535", 8, 0.74) + ["#fde6b4"]
FILL = ramp("#d86c1f", 8, 0.74) + ["#ffe2b0"]
TIN = ramp("#2f2742", 6, 0.7, 0.05) + ["#ddd0ff"]
CREAM = ["#3d5f69", "#6d9699", "#a5c7c1", "#d6e6dc", "#f1f1e4", "#fffdf4"]
CANDY_Y = ["#b86f0e", "#f0a91f", "#ffd23f", "#fff09a"]
CANDY_O = ["#9c3a0c", "#e0621a", "#ff8a2a", "#ffb869"]
CANDY_W = ["#b9ae98", "#ece4d2", "#fffaf0", "#ffffff"]
CHOC = ["#170706", "#2e1510", "#4d2a1e"]
GREEN = ["#1f7a2a", "#4ee04a", "#a8ff7a", "#eaffd8"]
DUST = ["#c98d5e", "#8a4a26"]                      # cinnamon powder: pale grain, dark grain
COCOA = ["#0f0710", "#24131f", "#3a2233", "#573650", "#9a78b0"]   # dark cocoa pastry, purple sheen
P = PASTRY

# --------------------------------------------------------------------------------------
# Layout (model units). The pie lies flat, centred on (CX, *, CZ); the missing quarter is
# x > CX, z < CZ, which faces the inventory camera.
# --------------------------------------------------------------------------------------
CX, CZ = 8.0, 8.0
Y_BASE, Y_FLOOR, Y_WALL = 1.0, 1.5, 6.0          # tin underside, tin floor, top of tin wall
R_BOT, R_TOP, T_WALL = 10.5, 12.3, 0.5           # outer radius of the tin wall, bottom/top
R_BEAD = 12.55                                   # rolled rim of the tin
Y_FILL = 6.5                                     # custard surface
LAT_O = (-6.75, -2.25, 2.25, 6.75)               # lattice strip offsets from the centre
LAT_W, Y_LAT, Y_HUMP, R_LAT = 2.5, 7.5, 8.0, 11.0
R_BAND0, R_BAND1, Y_BAND0, Y_BAND1 = 10.3, 12.9, 5.5, 7.9   # crust band on the rim
BAND_HALF = 2.05
CRX, CRZ = CX - 3.1, CZ + 3.1                    # whipped-cream swirl centre
ROLLS = [  # (radius, y0, y1, centre offset angle, offset, tilt): a piped swirl, drifting as it rises
    (4.1, Y_FILL, 8.5, 0, 0.0, 0),
    (3.25, 8.2, 10.0, 70, 0.35, 5),              # sunk into the roll below so the tilt never lifts it off
]
HEAD = (2.3, 9.7, 12.2)                          # the ghost's head: radius, y0, y1 (sunk into the swirl)
FACE = 315.0                                     # the ghost looks at the cut (and the GUI camera)


def in_notch(x: float, z: float) -> bool:
    return x > CX and z < CZ


def around(parts, theta: float, centre=(CX, CZ)):
    """Swing parts built facing +X (theta 0) round a vertical axis to face direction
    theta (degrees, measured from +X toward +Z)."""
    return turn(parts, -theta, "y", (centre[0], 0, centre[1]))


def dirv(theta: float) -> tuple[float, float]:
    return math.cos(math.radians(theta)), math.sin(math.radians(theta))


# World-anchored UVs for 64px textures at 2 texels per unit: texel (2x + 16, 2z + 16) on
# the flat faces, texel (2x|2z + 16, 2(16 - y)) on the sides, so neighbouring parts line up.
def _u(a: float) -> float:
    return round((a + 8) / 2, 4)


def _v(y: float) -> float:
    return round((16 - y) / 2, 4)


def wuv(side: str, frm, to) -> list[float]:
    (x0, y0, z0), (x1, y1, z1) = frm, to
    return {"up": [_u(x0), _u(z0), _u(x1), _u(z1)],
            "down": [_u(x0), _u(z1), _u(x1), _u(z0)],
            "north": [_u(x1), _v(y1), _u(x0), _v(y0)],
            "south": [_u(x0), _v(y1), _u(x1), _v(y0)],
            "west": [_u(z0), _v(y1), _u(z1), _v(y0)],
            "east": [_u(z1), _v(y1), _u(z0), _v(y0)]}[side]


def wbox(frm, to, tex: str, faces: dict | None = None, skip=(), glow: int = 0, shade: bool = True) -> dict:
    spec = {}
    for side in ("north", "south", "east", "west", "up", "down"):
        if side in skip:
            continue
        f = (faces or {}).get(side, tex)
        spec[side] = f if isinstance(f, tuple) else (f, wuv(side, frm, to))
    return box(frm, to, tex, faces=spec, skip=skip, glow=glow, shade=shade)


# --------------------------------------------------------------------------------------
# Texture helpers
# --------------------------------------------------------------------------------------

def put(img, x: int, y: int, colour) -> None:
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), rgba(colour))


def world(tx: int, ty: int) -> tuple[float, float]:
    """Model (x, z) at the centre of texel (tx, ty) of a world-anchored 64px texture."""
    return tx / 2 - 8 + 0.25, ty / 2 - 8 + 0.25


def runs(rng: random.Random, n: int, chance: float, lo: int = 1, hi: int = 3) -> list[bool]:
    """A mask of short clustered runs (deliberate pixel clusters instead of noise)."""
    mask, i = [False] * n, 0
    while i < n:
        if rng.random() < chance:
            k = rng.randint(lo, hi)
            for j in range(i, min(n, i + k)):
                mask[j] = True
            i += k + 1
        else:
            i += 1
    return mask


def strip_gap(a: float) -> float:
    """Signed distance from offset a (from the centre) to the nearest lattice strip edge;
    negative inside a strip."""
    return min(abs(a - o) - LAT_W / 2 for o in LAT_O)


def r_inner(y: float) -> float:
    """Radius of the inside of the tin wall at height y."""
    return 10.03 + (y - 1.17) * (R_TOP - R_BOT) / (Y_WALL - Y_BASE)


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def paint_filling() -> None:
    """Glossy custard showing through the lattice: every open cell gets a soft shadow
    along its strips and a wet highlight in its lit corner."""
    img = canvas(64)
    rng = random.Random(3)
    for ty in range(64):
        for tx in range(64):
            x, z = world(tx, ty)
            dx, dz = x - CX, z - CZ
            r = math.hypot(dx, dz)
            if r > 11.2 or in_notch(x, z):
                continue
            gx, gz = strip_gap(dx), strip_gap(dz)
            if gx < 0 or gz < 0:
                put(img, tx, ty, FILL[3])
                continue
            cx_, cz_ = (dx + 1.0) % 4.5, (dz + 1.0) % 4.5   # 0..2 inside an open cell
            c = FILL[4]
            if cx_ >= 1.5 or cz_ >= 1.5:
                c = FILL[3]                                 # shadow under the next strip
            if (cx_ < 0.5 and cz_ < 1.5) or (cz_ < 0.5 and cx_ < 1.5):
                c = FILL[5]                                 # lit rim of the pool
            if r < 9.6 and 0.5 <= cx_ < 1.0 and 0.5 <= cz_ < 1.0:
                c = FILL[8]                                 # wet glint
            elif r < 9.6 and 0.5 <= cx_ < 1.5 and 0.5 <= cz_ < 1.5:
                c = FILL[6]
            if r > 9.6:
                c = FILL[3]
            if c == FILL[4] and rng.random() < 0.12:
                c = FILL[3]                                 # a fleck of spice
            put(img, tx, ty, c)
    save(img, "filling")


def paint_lattice() -> None:
    rng = random.Random(21)
    cross = [CX + o for o in LAT_O]
    for name in ("lat_x", "lat_z"):
        img = canvas(64)
        for p in (0, 1):
            # a rounded strip: lit edge, an egg-wash streak, golden body, baked shadow edge
            sheen = runs(rng, 64, 0.35, 2, 4)
            blister = runs(rng, 64, 0.07, 2, 2)
            for t in range(64):
                a = t / 2 - 8 + 0.25                   # model x (lat_x) or z (lat_z)
                ao = False
                for j, c in enumerate(cross):
                    over_me = ((p + j) % 2 == 1) if name == "lat_x" else ((p + j) % 2 == 0)
                    if over_me and abs(a - c) < 2.0:
                        ao = True
                col = [P[6], P[5], P[5], P[4], P[3]]
                if sheen[t]:
                    col[1] = P[7] if rng.random() < 0.8 else P[8]
                if blister[t]:
                    col[2], col[3] = P[4], P[3]         # a browned spot
                if ao:
                    col = [P[5], P[4], P[4], P[3], P[2]]   # shade where it dives under
                for w, c in enumerate(col):
                    if name == "lat_x":
                        put(img, t, 8 * p + w, c)
                    else:
                        put(img, 8 * p + w, t, c)
        save(img, name)

    side = canvas(64)
    for tx in range(64):
        put(side, tx, 16, P[5])
        put(side, tx, 17, P[5] if rng.random() < 0.6 else P[4])
        put(side, tx, 18, P[3] if rng.random() < 0.75 else P[2])
        for ty in range(19, 24):
            put(side, tx, ty, P[2])
    save(side, "lat_side")

    cut = canvas(32)
    fill(cut, (0, 0, 31, 31), P[7])
    for x in range(32):
        put(cut, x, 0, P[4] if x % 3 else P[5])
        put(cut, x, 1, P[7] if x % 3 else P[6])
    for y in range(32):
        put(cut, 0, y, P[4])
        put(cut, 4, y, P[4])
    save(cut, "lat_cut")

    for name, along_x in (("hump_x", True), ("hump_z", False)):
        img = canvas(32)
        length, width = 7, 5
        for i in range(length):
            for w in range(width):
                c = [P[6], P[7], P[6], P[5], P[4]][w]
                if i in (0, length - 1):
                    c = [P[5], P[5], P[5], P[4], P[3]][w]
                if i == length // 2 and w == 1:
                    c = P[8]
                put(img, i if along_x else w, w if along_x else i, c)
        save(img, name)


def paint_cut() -> None:
    """The cross-section where the quarter was cut away: baked bottom crust with flaky
    layers, a thick band of custard with a darker baked skin, crust up the tin wall."""
    img = canvas(32)
    bubbles = {(5, 4), (11, 6), (17, 3), (8, 5), (14, 5), (2, 6), (20, 4)}
    for ty in range(10):
        y = Y_FILL - (ty + 0.5) / 2
        for tx in range(32):
            r = (tx + 0.5) / 2
            if (y >= Y_BAND0 and (tx + 1) / 2 > R_BAND0) or (y < Y_BAND0 and r >= r_inner(y) + 0.3):
                continue
            wall = r_inner(y) - r
            wall_crust = wall < 1.3 and y < Y_BAND0
            if ty >= 7 or wall_crust:                        # crust: bottom and up the wall
                if ty == 9 or wall < 0.45:
                    c = P[3]                                 # baked skin against the tin
                elif ty == 7 and not wall_crust:
                    c = P[5]                                 # custard-soaked top of the crust
                else:
                    c = P[7] if (tx // 2 + ty) % 3 else P[6] # flaky crumb in little clusters
            elif ty == 0:
                c = FILL[3]                                  # baked skin on top
            else:
                c = FILL[6] if ty <= 2 else FILL[5]
                if (tx, ty) in bubbles:
                    c = FILL[4]                              # a tiny air pocket
            put(img, tx, ty, c)
    for tx in range(2, 19, 5):                               # a moist gleam
        put(img, tx, 1, FILL[8])
        put(img, tx + 1, 1, FILL[7])
    save(img, "cut")


def paint_rim() -> None:
    rng = random.Random(5)
    top = canvas(32)
    for x in range(32):
        for y in range(32):
            u = x % 6                                        # radial across the band
            c = (P[5], P[5], P[4], P[4], P[3], P[3])[u]
            if rng.random() < 0.06:
                c = P[7]
            put(top, x, y, c)
    save(top, "rim_band_top")

    side = canvas(32)
    blister = runs(rng, 32, 0.18, 2, 3)
    for x in range(32):
        for y in range(32):
            put(side, x, y, (P[6], P[5], P[5], P[4], P[3], P[3])[min(y, 5)])
        if blister[x]:
            put(side, x, 2, P[4])                            # a browned ripple
        if rng.random() < 0.12:
            put(side, x, 0, P[8])                            # sugar glint on the lip
    save(side, "rim_band_side")

    flute = canvas(32)
    for y in range(32):
        for x in range(32):
            c = (P[7], P[6], P[5], P[4], P[4], P[3])[min(x, 5)]
            if y == 0:
                c = (P[5], P[5], P[4], P[3], P[3], P[2])[min(x, 5)]   # outer tip is more baked
            put(flute, x, y, c)
        if y in (2, 4):
            put(flute, 0, y, P[8])
    save(flute, "rim_flute")

    end = canvas(32)
    for y in range(32):
        for x in range(32):
            ring = min(x, y, 4 - x, 4 - y)
            put(end, x, y, (P[3], P[4], P[5])[max(0, min(ring, 2))])
    save(end, "rim_flute_end")

    cut = canvas(32)
    for x in range(32):
        for y in range(32):
            put(cut, x, y, P[7] if (x * 7 + y * 3) % 5 else P[6])
    for x in range(6):
        put(cut, x, 0, P[4])                                 # baked top of the band
        put(cut, x, 4, P[4])                                 # underside on the tin
    for y in range(5):
        put(cut, 5, y, P[4])                                 # outer baked skin
        put(cut, 0, y, P[5])
    save(cut, "rim_cut")


def paint_tin() -> None:
    rng = random.Random(9)
    side = canvas(32)
    for x in range(32):
        for y in range(32):
            k = x % 3
            if y < 1:
                c = (TIN[5], TIN[4], TIN[3])[k]
            elif y < 3:
                c = (TIN[4], TIN[3], TIN[2])[k]
            elif y < 8:
                c = (TIN[3], TIN[2], TIN[1])[k]
            else:
                c = (TIN[2], TIN[1], TIN[0])[k]
            put(side, x, y, c)
        if x % 3 == 0 and rng.random() < 0.5:
            put(side, x, 1, TIN[6])                          # glint on the flutes of the tin
    save(side, "tin_side")

    inner = canvas(32)
    for x in range(32):
        for y in range(32):
            c = TIN[2] if (x // 3) % 2 else TIN[3]
            if y < 2:
                c = TIN[4]
            put(inner, x, y, c)
        if rng.random() < 0.3:
            put(inner, x, 9, P[4])
    save(inner, "tin_in")

    bead = canvas(32)
    for x in range(32):
        for y in range(32):
            c = TIN[3]
            if y == 0 or x == 0:
                c = TIN[4]
            if y >= 2 and x >= 2:
                c = TIN[2]
            put(bead, x, y, c)
        if x % 4 == 1:
            put(bead, x, 0, TIN[6])
    save(bead, "tin_bead")

    for name in ("tin_floor", "tin_base"):
        img = canvas(64)
        for ty in range(64):
            for tx in range(64):
                x, z = world(tx, ty)
                r = math.hypot(x - CX, z - CZ)
                if r > 10.8:
                    continue
                c = TIN[3] if name == "tin_floor" else TIN[2]
                ring = r % 3.0
                if ring < 0.5:
                    c = TIN[4] if name == "tin_floor" else TIN[3]
                elif ring < 1.0:
                    c = TIN[2] if name == "tin_floor" else TIN[1]
                put(img, tx, ty, c)
        if name == "tin_floor":
            for _ in range(40):
                tx, ty = rng.randrange(33, 56), rng.randrange(9, 32)
                x, z = world(tx, ty)
                if math.hypot(x - CX, z - CZ) < 10:
                    c = rng.choice([P[4], P[5], P[6], FILL[4]])
                    put(img, tx, ty, c)
                    if rng.random() < 0.4:
                        put(img, tx + 1, ty, c)
            for tx in range(34, 44):                         # a smear left by the knife
                put(img, tx, 31 - (tx - 34) // 4, FILL[4])
        else:
            # embossed jack-o'-lantern stamp on the underside
            face = [
                "..##......##..",
                ".####....####.",
                "######..######",
                "..............",
                "......##......",
                ".....####.....",
                "..............",
                "#.##.####.##.#",
                "##############",
                ".############.",
                "..##.####.##..",
            ]
            ox, oy = 32 - 7, 32 - 5
            for j, row in enumerate(face):
                for i, ch in enumerate(row):
                    if ch == "#":
                        put(img, ox + i, oy + j, TIN[4])
            for j, row in enumerate(face):
                for i, ch in enumerate(row):
                    below = face[j + 1][i] if j + 1 < len(face) else "."
                    if ch == "#" and below != "#":
                        put(img, ox + i, oy + j + 1, TIN[1])
        save(img, name)


def paint_cream() -> None:
    rng = random.Random(14)
    side = canvas(32)
    for x in range(32):
        ribs = (CREAM[5], CREAM[5], CREAM[5], CREAM[4], CREAM[3], CREAM[3]) if x % 2 == 0 else \
               (CREAM[5], CREAM[4], CREAM[4], CREAM[3], CREAM[3], CREAM[2])
        for y in range(32):
            put(side, x, y, ribs[min(y, 5)])
        if rng.random() < 0.08:
            put(side, x, 0, DUST[0])                        # cinnamon caught on a ridge
    save(side, "cream_side")

    # a fine dusting of cinnamon: pale single grains, a few darker ones, never clumps
    top = canvas(32)
    for x in range(32):
        for y in range(32):
            c = CREAM[5] if (x + 2 * y) % 7 else CREAM[4]
            roll_ = rng.random()
            if roll_ < 0.035:
                c = DUST[1]
            elif roll_ < 0.10:
                c = DUST[0]
            put(top, x, y, c)
    save(top, "cream_top")

    for name in ("ghost", "ghost_face"):
        img = canvas(32)
        for x in range(32):
            rows = (CREAM[5], CREAM[5], CREAM[5], CREAM[4], CREAM[3], CREAM[2])
            for y in range(32):
                put(img, x, y, rows[min(y, 5)])
        if name == "ghost_face":
            put(img, 0, 2, CHOC[2])                          # a little "o" mouth
            put(img, 1, 2, CHOC[1])
            put(img, 0, 3, CHOC[0])
            put(img, 1, 3, CHOC[0])
        save(img, name)

    gtop = canvas(32)
    for x in range(32):
        for y in range(32):
            c = CREAM[5]
            roll_ = rng.random()
            if roll_ < 0.05:
                c = DUST[1]
            elif roll_ < 0.14:
                c = DUST[0]
            put(gtop, x, y, c)
    save(gtop, "ghost_top")

    # cocoa-pastry bat cut-outs laid on the lattice (a darker baked edge sits just below)
    bat = [
        "X..........X",
        "XX..X..X..XX",
        "XXX.XXXX.XXX",
        "XXXXXXXXXXXX",
        ".XXX.XX.XXX.",
        "..X..XX..X..",
    ]
    top_img, rim_img = canvas(32), canvas(32)
    for j, row in enumerate(bat):
        for i, ch in enumerate(row):
            if ch != "X":
                continue
            lit = j == 0 or bat[j - 1][i] != "X"             # top edges catch the light
            c = COCOA[3] if lit else COCOA[2]
            if j >= 4 or (j == 3 and (i < 2 or i > 9)):
                c = COCOA[1]                                  # wing scallops fall into shade
            put(top_img, 1 + i, 1 + j, c)
            for dx, dy in ((0, 0), (0, 1)):
                put(rim_img, 1 + i + dx, 1 + j + dy, COCOA[0])
    put(top_img, 6, 3, GREEN[2])                              # toxic-green eyes
    put(top_img, 7, 3, GREEN[2])
    put(top_img, 3, 4, COCOA[4])                              # sugar glints on the wings
    put(top_img, 10, 4, COCOA[4])
    save(top_img, "bat_top")
    save(rim_img, "bat_rim")

    eye = canvas(32)
    fill(eye, (0, 0, 31, 31), CHOC[0])
    put(eye, 0, 0, CHOC[2])
    put(eye, 1, 0, CHOC[1])
    save(eye, "eye")
    glint = canvas(16, fill=GREEN[2])
    put(glint, 0, 0, GREEN[3])
    save(glint, "glint")


def paint_candy() -> None:
    for name, pal in (("candy_y", CANDY_Y), ("candy_o", CANDY_O), ("candy_w", CANDY_W)):
        img = canvas(32)
        for x in range(32):
            for y in range(32):
                c = (pal[2], pal[3], pal[2], pal[2], pal[1])[min(x, 4)]
                if y == 0 and x < 4:
                    c = pal[3]
                put(img, x, y, c)
        save(img, name)


def textures() -> None:
    paint_filling()
    paint_lattice()
    paint_cut()
    paint_rim()
    paint_tin()
    paint_cream()
    paint_candy()


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def tin() -> list[dict]:
    parts = [wbox((CX - 10.7, Y_BASE, CZ - 10.7), (CX + 10.7, Y_FLOOR, CZ + 10.7), "tin_floor",
                  faces={"down": "tin_base"}, skip=("north", "south", "east", "west"))]
    length = math.hypot(R_TOP - R_BOT, Y_WALL - Y_BASE)
    tilt = math.degrees(math.atan2(R_TOP - R_BOT, Y_WALL - Y_BASE))
    nx, ny = (Y_WALL - Y_BASE) / length, -(R_TOP - R_BOT) / length
    rc = (R_BOT + R_TOP) / 2 - nx * T_WALL / 2
    yc = (Y_BASE + Y_WALL) / 2 - ny * T_WALL / 2
    width = 2 * R_TOP * math.tan(math.pi / 16) + 0.05
    wb = 2 * (R_BEAD + 0.5) * math.tan(math.pi / 16) + 0.1
    for k in range(16):
        theta = k * 22.5
        inside = theta >= 270 or theta == 0          # walls round the missing quarter show inside
        p = box((CX + rc - T_WALL / 2, yc - length / 2, CZ - width / 2),
                (CX + rc + T_WALL / 2, yc + length / 2, CZ + width / 2), "tin_side",
                faces={"west": "tin_in", "up": "tin_bead"}, skip=() if inside else ("west",))
        turn(p, -tilt, "z", (CX + rc, yc, CZ))
        parts.append(around(p, theta))
        bead = box((CX + R_BEAD - 0.5, 5.5 - 0.04 * (k % 2), CZ - wb / 2),
                   (CX + R_BEAD + 0.5, 6.4 + 0.04 * (k % 2), CZ + wb / 2), "tin_bead")
        parts.append(around(bead, theta))
    return parts


def body() -> list[dict]:
    parts = [wbox((CX - 11.0, Y_FILL - 0.2, CZ - 11.0), (CX + 11.0, Y_FILL, CZ + 11.0), "filling",
                  skip=("north", "south", "east", "west", "down"))]
    rc = 12.0
    # cut face A: the plane x = CX, looking east into the missing quarter
    parts.append(box((CX - 0.1, Y_FLOOR, CZ - rc), (CX, Y_FILL, CZ), "cut",
                     faces={"east": ("cut", [0, 0, rc, Y_FILL - Y_FLOOR])},
                     skip=("north", "south", "west", "up", "down"), shade=False))
    # cut face B: the plane z = CZ, looking north into the missing quarter
    parts.append(box((CX, Y_FLOOR, CZ), (CX + rc, Y_FILL, CZ + 0.1), "cut",
                     faces={"north": ("cut", [rc, 0, 0, Y_FILL - Y_FLOOR])},
                     skip=("south", "east", "west", "up", "down"), shade=False))
    return parts


def rim() -> list[dict]:
    parts = []
    w, h = R_BAND1 - R_BAND0, Y_BAND1 - Y_BAND0
    for k in range(1, 15):
        seg = box((CX + R_BAND0, Y_BAND0, CZ - BAND_HALF), (CX + R_BAND1, Y_BAND1 + 0.04 * (k % 2), CZ + BAND_HALF),
                  "rim_band_side", faces={"up": "rim_band_top"}, skip=("north", "south", "down"),
                  offset=((k * 3.3) % 11.0, 0))
        parts.append(around(seg, k * 18))
    # the band where the knife went through, showing the pastry inside
    parts.append(box((CX + R_BAND0, Y_BAND0, CZ), (CX + R_BAND1, Y_BAND1, CZ + BAND_HALF), "rim_band_side",
                     faces={"north": ("rim_cut", [w, 0, 0, h]), "up": "rim_band_top"},
                     skip=("south", "down")))
    parts.append(box((CX - BAND_HALF, Y_BAND0, CZ - R_BAND1), (CX, Y_BAND1 + 0.04, CZ - R_BAND0), "rim_band_side",
                     faces={"east": ("rim_cut", [0, 0, w, h]), "up": "rim_band_top"},
                     skip=("west", "down")))
    # pinched flutes: rounded diamond ridges radiating over the band, drooping outward
    s = 2.5
    rng = random.Random(31)
    for k in range(15):
        reach = rng.uniform(-0.25, 0.3)
        lift = rng.uniform(-0.15, 0.15)
        f = bar((CX + R_BAND0 + 0.1, Y_BAND1 - 0.45 + lift, CZ),
                (CX + R_BAND1 + 0.6 + reach, Y_BAND1 - 1.25 + lift, CZ), s, s, "rim_flute", roll=45,
                faces={"north": ("rim_flute", [s, 0, 0, 3.2]), "up": "rim_flute_end", "down": "rim_flute_end"})
        parts.append(around(f, 9 + 18 * k + rng.uniform(-2.0, 2.0)))
    return parts


def lattice() -> list[dict]:
    parts = []
    half = {}
    for i, o in enumerate(LAT_O):
        h = math.sqrt(R_LAT ** 2 - (abs(o) + LAT_W / 2) ** 2)
        half[i] = h
        p = i % 2
        # strip running along x at z = CZ + o
        x0, x1 = CX - h, (CX if o < 0 else CX + h)
        z0, z1 = CZ + o - LAT_W / 2, CZ + o + LAT_W / 2
        faces = {"up": ("lat_x", [_u(x0), 2 * p, _u(x1), 2 * p + LAT_W / 2]), "east": ("lat_cut", [0, 0, LAT_W, 1])}
        parts.append(wbox((x0, Y_FILL, z0), (x1, Y_LAT, z1), "lat_side", faces=faces,
                          skip=("down", "west") + (() if o < 0 else ("east",))))
        # strip running along z at x = CX + o
        z0, z1 = (CZ if o > 0 else CZ - h), CZ + h
        x0, x1 = CX + o - LAT_W / 2, CX + o + LAT_W / 2
        faces = {"up": ("lat_z", [2 * p, _u(z0), 2 * p + LAT_W / 2, _u(z1)]),
                 "north": ("lat_cut", [LAT_W, 0, 0, 1])}
        parts.append(wbox((x0, Y_FILL, z0), (x1, Y_LAT, z1), "lat_side", faces=faces,
                          skip=("down", "south") + (() if o > 0 else ("north",))))
    # humps where one strip rides over the other
    for i, oi in enumerate(LAT_O):
        for j, oj in enumerate(LAT_O):
            x, z = CX + oj, CZ + oi
            if in_notch(x, z) or math.hypot(oi, oj) > 10.2 or math.hypot(x - CRX, z - CRZ) < 2.0:
                continue
            if (i + j) % 2 == 0:            # the x-strip goes over
                a0 = max(x - 1.75, CX - half[i])
                a1 = min(x + 1.75, CX + half[i])
                parts.append(wbox((a0, Y_LAT, z - 1.25), (a1, Y_HUMP, z + 1.25), "lat_side",
                                  faces={"up": ("hump_x", [0, 0, a1 - a0, 2.5])}, skip=("down",)))
            else:
                a0 = max(z - 1.75, CZ - half[j])
                a1 = min(z + 1.75, CZ + half[j])
                parts.append(wbox((x - 1.25, Y_LAT, a0), (x + 1.25, Y_HUMP, a1), "lat_side",
                                  faces={"up": ("hump_z", [0, 0, 2.5, a1 - a0])}, skip=("down",)))
    return parts


def roll(cx: float, cz: float, y0: float, y1: float, r: float, tex: str, top: str, sides: int = 16,
         special: dict | None = None, seed: int = 0, shade: bool = True, glow: int = 0,
         lobe: float = 0.0) -> list[dict]:
    """A round slab of 8 or 16 sides made of crossed boxes. `special` maps a face
    direction (degrees) to a texture for that one face. Tops are staggered a hair so
    overlapping caps never z-fight. lobe > 0 pulls every other pair of faces in by that
    much, so the outline becomes an 8-lobed piped rosette (the slab sides then show)."""
    half = r * math.tan(math.pi / sides)
    angles = (0, 22.5, 45, -22.5) if sides == 16 else (0, 45)
    parts = []
    n = 0
    for a in angles:
        rr = r - lobe if a in (22.5, -22.5) else r
        hh = half if not lobe else rr * math.tan(math.pi / 8) * 0.62
        for frm, to, keep in (((cx - rr, y0, cz - hh), (cx + rr, y1, cz + hh), ("east", "west")),
                              ((cx - hh, y0, cz - rr), (cx + hh, y1, cz + rr), ("north", "south"))):
            if lobe:
                keep = ("north", "south", "east", "west")
            faces = {"up": top}
            for side, base in (("east", 0), ("south", 90), ("west", 180), ("north", 270)):
                d = round((base - a) % 360, 1)
                if special and side in keep and d in special:
                    faces[side] = (special[d], [0, 0, 2 * half, y1 - y0])
            e = box(frm, (to[0], to[1] + 0.025 * n, to[2]), tex, faces=faces,
                    skip=tuple(s for s in ("north", "south", "east", "west") if s not in keep) + ("down",),
                    offset=(((n + seed) * 1.7) % 12, 0), shade=shade, glow=glow)
            parts.append(turn(e, a, "y", (cx, 0, cz)))
            n += 1
    return parts


def cream() -> list[dict]:
    parts = []
    for n, (r, y0, y1, ang, off, tilt) in enumerate(ROLLS):
        ox, oz = dirv(ang)
        cx, cz = CRX + ox * off, CRZ + oz * off
        ring = roll(cx, cz, y0, y1, r, "cream_side", "cream_top", seed=n * 5, lobe=0.55 if n == 0 else 0.0)
        if tilt:                                     # a lopsided turn, like a real piped swirl
            ax, az = dirv(ang + 90)
            turn(ring, x=tilt * ax, z=-tilt * az, origin=(cx, y0, cz))
        parts += ring
    r, y0, y1 = HEAD
    parts += roll(CRX, CRZ, y0, y1, r, "ghost", "ghost_top", special={FACE: "ghost_face"}, shade=False, glow=4)
    for t in (FACE - 22.5, FACE + 22.5):             # eyes either side of the little mouth
        eye = box((CRX + r - 0.05, y1 - 1.05, CRZ - 0.32), (CRX + r + 0.2, y1 - 0.2, CRZ + 0.32), "eye")
        glint = box((CRX + r + 0.2, y1 - 0.5, CRZ - 0.3), (CRX + r + 0.24, y1 - 0.22, CRZ - 0.02), "glint",
                    uv="full", glow=15, shade=False, skip=("west", "up", "down", "north", "south"))
        parts += around([eye, glint], t, (CRX, CRZ))
    parts += roll(CRX, CRZ, y1, y1 + 0.6, 1.7, "ghost", "ghost_top", sides=8, seed=3, shade=False, glow=4)
    parts += candy_corn(CRX, y1 + 0.15, CRZ)
    return parts


def candy_corn(x: float, y: float, z: float) -> list[dict]:
    k = 1.15                                     # a touch oversized so the crown reads in the slot
    parts = [
        box((-1.3 * k, 0, -0.65 * k), (1.3 * k, 1.4 * k, 0.65 * k), "candy_y"),
        box((-1.0 * k, 1.4 * k, -0.6 * k), (1.0 * k, 2.7 * k, 0.6 * k), "candy_o"),
        box((-0.65 * k, 2.7 * k, -0.55 * k), (0.65 * k, 3.6 * k, 0.55 * k), "candy_w"),
        box((-0.3 * k, 3.6 * k, -0.45 * k), (0.3 * k, 4.0 * k, 0.45 * k), "candy_w"),
    ]
    turn(parts, -14, "z", (0, 0, 0))            # a jaunty lean
    turn(parts, 135, "y", (0, 0, 0))            # flat face toward the cut, lean to the north-west
    for e in parts:
        e["from"] = [round(e["from"][0] + x, 4), round(e["from"][1] + y, 4), round(e["from"][2] + z, 4)]
        e["to"] = [round(e["to"][0] + x, 4), round(e["to"][1] + y, 4), round(e["to"][2] + z, 4)]
        o = e["rotation"]["origin"]
        e["rotation"]["origin"] = [round(o[0] + x, 4), round(o[1] + y, 4), round(o[2] + z, 4)]
    return parts


def crumbs() -> list[dict]:
    spots = [((CX + 3.4, CZ - 5.2), 0.8, 0.5, 20), ((CX + 6.2, CZ - 2.2), 0.6, 0.4, -15),
             ((CX + 1.6, CZ - 8.1), 0.5, 0.35, 40)]
    parts = []
    for (x, z), s, hgt, rot in spots:
        c = box((x - s / 2, Y_FLOOR, z - s / 2), (x + s / 2, Y_FLOOR + hgt, z + s / 2), "rim_cut", skip=("down",))
        parts.append(turn(c, rot, "y", (x, 0, z)))
    return parts


def bats() -> list[dict]:
    """Two cocoa-pastry bats on the lattice, ears pointing out to the rim. Each is a top
    cut-out over a darker baked edge a little lower, which reads as the dough's thickness;
    the top glows so only its green eyes light up at night."""
    parts = []
    for bx, bz in ((12.4, 12.6), (3.0, 3.2)):
        out = math.degrees(math.atan2(bz - CZ, bx - CX))
        rim_ = box((bx - 3.5, Y_LAT + 0.02, bz - 2.0), (bx + 3.5, Y_LAT + 0.06, bz + 2.0), "bat_rim",
                   faces={"up": ("bat_rim", [0, 0, 7, 4])}, skip=("north", "south", "east", "west", "down"))
        top = box((bx - 3.5, Y_HUMP + 0.06, bz - 2.0), (bx + 3.5, Y_HUMP + 0.1, bz + 2.0), "bat_top",
                  faces={"up": ("bat_top", [0, 0, 7, 4])}, skip=("north", "south", "east", "west", "down"),
                  glow=10)
        parts += turn([rim_, top], 270 - out, "y", (bx, 0, bz))
    return parts


def build() -> list[dict]:
    return tin() + body() + rim() + lattice() + bats() + cream() + crumbs()


# --------------------------------------------------------------------------------------
# Display
# --------------------------------------------------------------------------------------

def _norm(v):
    n = math.sqrt(sum(c * c for c in v))
    return tuple(c / n for c in v)


def pie_axes(up, notch) -> dict:
    """Model-axis directions for place(): the pie's top faces `up`, its cut faces `notch`."""
    u = _norm(up)
    f = _norm(notch)
    d = sum(f[i] * u[i] for i in range(3))
    f = _norm(tuple(f[i] - d * u[i] for i in range(3)))
    m = (f[1] * u[2] - f[2] * u[1], f[2] * u[0] - f[0] * u[2], f[0] * u[1] - f[1] * u[0])
    return {"y": u, "x": _norm(tuple(f[i] + m[i] for i in range(3)))}


def models() -> dict:
    parts = build()
    d = display("food", parts, gui_rotation=(33, 232, 0), gui_span=15.6)
    rim_sw = (CX - 12.6 / math.sqrt(2), 7.0, CZ + 12.6 / math.sqrt(2))
    d["thirdperson_righthand"] = place(pie_axes((-0.2, 0.9, 0.38), (-0.3, 0.0, 1.0)), rim_sw, "fist", 0.45)
    d["firstperson_righthand"] = place(pie_axes((0.0, 0.9, 0.43), (-0.55, 0.0, 0.83)), (CX, 7.0, CZ),
                                       (0.42, -0.29, -0.95), 0.37, pose=None)
    d["fixed"] = fit(parts, (-90, 45, 0), 15.0)
    d["on_shelf"] = fit(parts, (-90, 45, 0), 12.0)
    return {"main": model(parts, d)}
