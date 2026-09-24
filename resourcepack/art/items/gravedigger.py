"""Gravedigger - Halloween Limited Edition shovel.

A gravedigger's spade: a big weathered iron blade cut like a tombstone (arched cutting
edge, a raised panel with a carved cross that glows ghost-teal, an engraved RIP, rust
bloom, chipped flanks and a riveted iron cross strapped to its back) on a gnarled
black-wood shaft that ends in a knobbly bone T-grip. A little iron lantern with a lit
candle hangs from the collar; purple cloth wraps, a tattered rag, a cobweb and a pair of
glowing foxfire mushrooms finish it off.
"""
from __future__ import annotations

import math
import random
from collections import deque

from art.kit import bar, box, canvas, display, fit, grain, model, place, prism, ramp, rgba, save, turn

ID = "gravedigger"
NAME = "Gravedigger"
KIND = "shovel"

# ---------------------------------------------------------------------------------------
# Palettes (dark -> light, hue-shifted)
# ---------------------------------------------------------------------------------------
IRON = ramp("#8a90a0", 6, 0.68, 0.12)     # weathered blade iron, purple shadows
BLK = ramp("#43404f", 6, 0.66, 0.05)      # blackened iron fittings
RUST = ramp("#b65a22", 6, 0.7, 0.05)      # rust bloom
WOOD = ramp("#4f3c38", 6, 0.74, 0.10)     # gnarled black wood
BONE = ramp("#e0d3b0", 6, 0.6, 0.10)      # bone T-grip
CLOTH = ramp("#553884", 6, 0.7, 0.08)     # midnight-purple rags
TWINE = ramp("#a4865a", 5, 0.62, 0.08)
MOSS = ["#18241a", "#29401f", "#436128", "#6a8a38"]
TEAL = ["#0b3a43", "#127c79", "#28c8b3", "#80f4df", "#ddfff7"]
FLAME = ["#9a2b0c", "#e5601b", "#ff982b", "#ffd15f", "#fff3c6"]
TOXIC = ["#1b4011", "#347e1b", "#6ecb2c", "#b0ff55", "#e8ffb6"]
WAX = ramp("#ecdcb6", 5, 0.6, 0.08)

GRIP = (8.0, -10.0, 8.0)
SIZE = 0.82

# ---------------------------------------------------------------------------------------
# Shared shapes (texel grids of the 32px textures: 2 texels per model unit)
# ---------------------------------------------------------------------------------------
# Blade: u = x, v = 28.5 - y, so texel (px, py) covers x in [px/2, px/2 + .5] and
# y in [(56 - py)/2, (57 - py)/2]. Rows 1..27 are the tombstone, 28..31 the tread.
ARCH = {13: 13, 12: 13, 11: 13, 10: 13, 9: 12, 8: 12, 7: 11, 6: 11, 5: 10, 4: 9, 3: 8, 2: 6, 1: 4}
CROSS_V = (14, 5, 17, 18)       # carved cross, upright   (px0, py0, px1, py1) inclusive
CROSS_H = (9, 8, 22, 11)        # carved cross, arms
BLADE_Z = (7.5, 8.5)            # rim of the blade
PANEL_Z = (7.2, 8.8)            # raised inner panel


def blade_rows() -> dict:
    """Tombstone silhouette per texel row (south view): py -> [first px, last px]."""
    rows = {py: [16 - ARCH.get(py, 13), 15 + ARCH.get(py, 13)] for py in range(1, 28)}
    rows[18][1] -= 1       # V-shaped chip in the right flank
    rows[19][1] -= 2
    rows[20][1] -= 1
    rows[24][0] += 1       # small bite from the left flank
    rows[7][0] += 1        # nick in the left shoulder of the arch
    rows[2][1] -= 1        # nick near the tip
    rows[3][1] -= 1
    return rows


def chip_cells() -> set:
    """Texels on the chipped edges (fresh, bright metal), south view."""
    return {(27, 18), (26, 19), (27, 20), (4, 24), (6, 7), (20, 2), (22, 3)}


def blade_mask(face: str) -> set:
    cells = set()
    for py, (l, r) in blade_rows().items():
        for px in range(l, r + 1):
            cells.add((px, py) if face == "s" else (31 - px, py))
    return cells


def cross_cells() -> set:
    return {(x, y) for x in range(32) for y in range(32)
            if (CROSS_V[0] <= x <= CROSS_V[2] and CROSS_V[1] <= y <= CROSS_V[3])
            or (CROSS_H[0] <= x <= CROSS_H[2] and CROSS_H[1] <= y <= CROSS_H[3])}


def row_groups(rows: dict) -> list:
    groups = []
    for py in sorted(rows):
        l, r = rows[py]
        if groups and groups[-1][2] == l and groups[-1][3] == r and groups[-1][1] == py - 1:
            groups[-1][1] = py
        else:
            groups.append([py, py, l, r])
    return groups


# Bone T-grip: u = x, v = BONE_TOP - y. A cartoon dog-bone: four round lobes and a shaft.
BONE_TOP = -10.25
BONE_LOBES = ((7.5, 2.9), (7.5, 7.6), (24.5, 2.9), (24.5, 7.6))
BONE_R = 2.7


def bone_mask() -> set:
    cells = set()
    for py in range(0, 11):
        for px in range(0, 32):
            cx, cy = px + 0.5, py + 0.5
            if any((cx - lx) ** 2 + (cy - ly) ** 2 <= BONE_R ** 2 for lx, ly in BONE_LOBES):
                cells.add((px, py))
            elif 8 <= px <= 23 and 3 <= py <= 6:
                cells.add((px, py))
    return cells


def distance(mask: set) -> dict:
    """Chebyshev distance (texels) from each masked texel to the outside; edge = 1."""
    d = {}
    q = deque()
    for (x, y) in mask:
        if any((x + dx, y + dy) not in mask for dx in (-1, 0, 1) for dy in (-1, 0, 1)):
            d[(x, y)] = 1
            q.append((x, y))
    while q:
        x, y = q.popleft()
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                n = (x + dx, y + dy)
                if n in mask and n not in d:
                    d[n] = d[(x, y)] + 1
                    q.append(n)
    return d


def rects(mask: set) -> list:
    """Split a texel mask into boxes: runs per row, merged downward while identical."""
    by_row = {}
    for (x, y) in mask:
        by_row.setdefault(y, []).append(x)
    out, open_runs = [], {}
    for y in sorted(by_row):
        xs = sorted(by_row[y])
        runs, start, prev = [], xs[0], xs[0]
        for x in xs[1:]:
            if x != prev + 1:
                runs.append((start, prev))
                start = x
            prev = x
        runs.append((start, prev))
        now = {}
        for run in runs:
            r = open_runs.get(run)
            if r is not None and r[3] == y - 1:
                r[3] = y
            else:
                r = [run[0], y, run[1], y]
                out.append(r)
            now[run] = r
        open_runs = now
    return out


# ---------------------------------------------------------------------------------------
# Texture painting
# ---------------------------------------------------------------------------------------

def _line(px, x0, y0, x1, y1, colour, cells=None):
    n = max(abs(x1 - x0), abs(y1 - y0), 1)
    for i in range(n + 1):
        x = round(x0 + (x1 - x0) * i / n)
        y = round(y0 + (y1 - y0) * i / n)
        if 0 <= x < 32 and 0 <= y < 32 and (cells is None or (x, y) in cells):
            px[x, y] = colour if isinstance(colour, tuple) and len(colour) == 4 else rgba(colour)


def _bloom(px, mask, cx, cy, r, seed):
    """An irregular rust bloom: dark core, orange crust, ragged speckled fringe."""
    rng = random.Random(seed)
    phases = [rng.uniform(0, 6.283) for _ in range(3)]
    amps = [rng.uniform(0.12, 0.25), rng.uniform(0.06, 0.14), rng.uniform(0.03, 0.08)]
    for y in range(int(cy - r - 2), int(cy + r + 3)):
        for x in range(int(cx - r - 2), int(cx + r + 3)):
            if (x, y) not in mask:
                continue
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            a = math.atan2(dy, dx)
            rr = r * (1 + sum(amps[k] * math.sin((k + 2) * a + phases[k]) for k in range(3)))
            t = math.hypot(dx, dy) / rr
            if t > 1.0:
                continue
            if t < 0.38:
                c = RUST[1] if rng.random() < 0.8 else RUST[0]
            elif t < 0.68:
                c = RUST[2] if (dx + dy) > -0.5 else RUST[3]
            elif t < 0.86:
                c = RUST[3] if (dx + dy) < 0 else RUST[2]
            else:
                if rng.random() < 0.45:
                    continue
                c = RUST[3]
            px[x, y] = rgba(c)
    for _ in range(max(1, int(r))):
        x, y = int(cx - rng.uniform(0, r * 0.6)), int(cy - rng.uniform(0, r * 0.6))
        if (x, y) in mask:
            px[x, y] = rgba(RUST[4])


def paint_blade(face: str):
    south = face == "s"
    mask = blade_mask(face)
    dist = distance(mask)
    img = canvas(32)
    px = img.load()
    mir = (lambda x: x) if south else (lambda x: 31 - x)

    # 1. panel: light up top, darkening toward the tread (dithered), one crisp sheen streak
    for (x, y) in mask:
        c = IRON[3]
        if y >= 23 or (y == 22 and x % 4 in (0, 1)) or (y == 21 and x % 7 == 3):
            c = IRON[2]
        s = x + y
        if s in (19, 20) or (s == 21 and x % 2 == 0):
            c = IRON[4]
        elif s == 31 and y < 20:
            c = IRON[4]
        px[x, y] = rgba(c)

    # 2. oxidised stains, dents (shadowed top-left, lit bottom-right) and pits, by hand
    for x, y in ((6, 20), (7, 20), (5, 21), (6, 21), (7, 21), (8, 21), (6, 22), (7, 22),
                 (23, 9), (24, 9), (24, 10), (25, 10), (20, 26), (21, 26), (22, 25)):
        x = mir(x)
        if (x, y) in mask:
            px[x, y] = rgba(IRON[2] if y < 24 else IRON[1])
    for x, y in ((6, 14), (24, 13), (12, 3), (20, 17)):
        x = mir(x)
        for (dx, dy), c in (((0, 0), IRON[2]), ((1, 0), IRON[2]), ((0, 1), IRON[2]), ((1, 1), IRON[4])):
            if (x + dx, y + dy) in mask:
                px[x + dx, y + dy] = rgba(c)
    for x, y in ((8, 9), (21, 5), (25, 21), (4, 16), (19, 15), (10, 18), (12, 13)):
        x = mir(x)
        if (x, y) in mask and (x + 1, y + 1) in mask:
            px[x, y] = rgba(IRON[1])
            px[x + 1, y + 1] = rgba(IRON[4])

    # 3. rim and raised panel: a honed bright edge, a dark recessed rim band, a lit bevel
    for (x, y), d in dist.items():
        if d > 3:
            continue
        left, right = (x - 1, y) not in mask, (x + 1, y) not in mask
        up, down = (x, y - 1) not in mask, (x, y + 1) not in mask
        if d == 1:
            if up or (y <= 9 and (left or right)):
                c = IRON[5]
            elif down or right:
                c = IRON[1]
            else:
                c = IRON[4]
        elif d == 2:
            c = IRON[2]
        else:
            lit = dist.get((x, y - 1)) == 2 or dist.get((x - 1, y)) == 2
            dark = dist.get((x, y + 1)) == 2 or dist.get((x + 1, y)) == 2
            c = IRON[4] if lit and not dark else IRON[1] if dark and not lit else IRON[3]
        px[x, y] = rgba(c)

    # 4. rust bloom (it eats into the edges too) and a weeping streak or two
    blooms = [(5.0, 24.5, 3.0, 1), (26.0, 18.5, 2.3, 2), (9.0, 6.5, 1.8, 3), (21.5, 14.5, 1.3, 4)] if south else \
             [(6.0, 18.5, 2.5, 5), (26.0, 24.0, 3.0, 6), (22.5, 6.5, 1.9, 7), (10.0, 14.0, 1.4, 8)]
    for cx, cy, r, seed in blooms:
        _bloom(px, mask, cx, cy, r, seed)
    streaks = [(9, 8, 11), (21, 16, 18)] if south else [(22, 8, 11), (6, 21, 22)]
    for x, y0, y1 in streaks:
        for y in range(y0, y1 + 1):
            if (x, y) in mask:
                px[x, y] = rgba(RUST[2] if y < y1 else RUST[1])

    # 5. chips: freshly exposed bright metal with a dark crack behind; the deep V-chip
    #    has split the plate a little way in
    for (x, y) in chip_cells():
        x = mir(x)
        if (x, y) in mask:
            px[x, y] = rgba(IRON[5])
            inward = (x - 1, y) if x > 15 else (x + 1, y)
            if inward in mask:
                px[inward] = rgba(IRON[1])
    crack = [(mir(x), y) for x, y in ((25, 18), (24, 17), (24, 16), (23, 15), (23, 14))]
    for (x, y) in crack:
        px[x, y] = rgba(IRON[0])
    for (x, y) in crack:
        lit = (x + 1, y + 1)
        if lit in mask and lit not in crack:
            px[lit] = rgba(IRON[4])

    # 6. graveyard moss climbing the corner from the tread (dark roots, lit tufts)
    tuft = ["........3",
            ".......32",
            "....3..21",
            "...32.321",
            "..3221121",
            "321121111"]
    for j, row in enumerate(tuft):
        for i, ch in enumerate(row):
            if ch != ".":
                x, y = mir(20 + i), 22 + j
                if (x, y) in mask:
                    px[x, y] = rgba(MOSS[int(ch)])

    # 7. scratches (bright line with a dark tail)
    scratches = [((6, 15), (8, 17)), ((23, 21), (25, 19)), ((18, 3), (19, 4))] if south else \
                [((6, 10), (8, 12)), ((22, 20), (24, 18)), ((12, 4), (13, 5))]
    for (x0, y0), (x1, y1) in scratches:
        _line(px, x0, y0, x1, y1, IRON[5], mask)
        if (x1, y1 + 1) in mask:
            px[x1, y1 + 1] = rgba(IRON[1])

    if south:
        # 8. the carved cross: shadowed top/left walls, lit bottom/right walls, teal floor
        cross = cross_cells()
        for (x, y) in cross:
            inner = all(n in cross for n in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)))
            if inner:
                c = TEAL[1]
            elif (x - 1, y) not in cross or (x, y - 1) not in cross:
                c = IRON[0]
            else:
                c = IRON[4]
            px[x, y] = rgba(c)
        # 9. RIP, engraved
        letters = ["### # ###",
                   "# # # # #",
                   "##  # ###",
                   "# # # #  ",
                   "# # # #  "]
        cells = {(11 + i, 20 + j) for j, row in enumerate(letters) for i, ch in enumerate(row) if ch == "#"}
        for (x, y) in cells:
            px[x, y] = rgba(IRON[0])
        for (x, y) in cells:
            for n in ((x + 1, y), (x, y + 1)):
                if n not in cells and n in mask:
                    px[n] = rgba(IRON[4])

    # 10. the tread (plinth) in blackened iron with rivets
    for x in range(2, 30):
        for y, c in ((28, BLK[4]), (29, BLK[3]), (30, BLK[2]), (31, BLK[1])):
            px[x, y] = rgba(c)
    for y in range(28, 32):
        px[2, y] = rgba(BLK[4] if y < 31 else BLK[2])
        px[29, y] = rgba(BLK[1])
    for x in (4, 9, 21, 26):
        px[x, 29] = rgba(BLK[5])
        px[x + 1, 29] = rgba(BLK[4])
        px[x, 30] = rgba(BLK[3])
        px[x + 1, 30] = rgba(BLK[0])
    for x in (13, 17):
        px[x, 30] = rgba(RUST[2])
        px[x + 1, 31] = rgba(RUST[1])
    return img


def paint_edge():
    img = canvas(32, fill=IRON[4])
    px = img.load()
    for i in range(32):
        for k, c in ((14, IRON[5]), (15, IRON[4]), (16, IRON[3]), (17, IRON[3])):
            px[k, i] = rgba(c)
            px[i, k] = rgba(c)
    rng = random.Random(3)
    for _ in range(9):
        i = rng.randrange(2, 30)
        px[rng.choice((14, 15, 16)), i] = rgba(RUST[rng.choice((2, 3))])
        j = rng.randrange(2, 30)
        px[j, rng.choice((14, 15, 16))] = rgba(RUST[rng.choice((2, 3))])
    return img


def paint_cross_glow():
    img = canvas(32)
    px = img.load()
    cross = cross_cells()
    core = {p for p in cross if all(n in cross for n in ((p[0] - 1, p[1]), (p[0] + 1, p[1]),
                                                           (p[0], p[1] - 1), (p[0], p[1] + 1)))}
    r, g, b, _ = rgba(TEAL[3])
    for (x, y) in cross - core:
        px[x, y] = (r, g, b, 80)
    for (x, y) in core:
        px[x, y] = rgba(TEAL[2])
    for (x, y) in core:
        if x == CROSS_V[0] + 1 or y == CROSS_H[1] + 1:
            px[x, y] = rgba(TEAL[3])
    for (x, y) in ((15, 9), (16, 9), (15, 10)):
        px[x, y] = rgba(TEAL[4])
    # ectoplasm oozing out of the carving and running down the stone
    for x, y0, y1 in ((10, 12, 14), (21, 12, 13), (13, 12, 12)):
        for y in range(y0, y1 + 1):
            px[x, y] = rgba(TEAL[2] if y < y1 else TEAL[3])
    px[10, 15] = (r, g, b, 150)
    return img


def paint_gem():
    """A 3x3-texel teal cabochon for the boss on the back."""
    img = canvas(32, fill=TEAL[1])
    px = img.load()
    for (x, y), c in (((0, 0), TEAL[4]), ((1, 0), TEAL[3]), ((2, 0), TEAL[2]), ((0, 1), TEAL[3]),
                      ((1, 1), TEAL[3]), ((2, 1), TEAL[1]), ((0, 2), TEAL[2]), ((1, 2), TEAL[1]),
                      ((2, 2), TEAL[0])):
        px[x, y] = rgba(c)
    return img


def paint_strap():
    img = canvas(32, fill=BLK[2])
    px = img.load()
    # upright strap: px 0..3, rows 0..23
    for y in range(24):
        for x, c in ((0, BLK[4]), (1, BLK[3]), (2, BLK[2]), (3, BLK[1])):
            px[x, y] = rgba(c)
    for x in range(4):
        px[x, 0] = rgba(BLK[4])
        px[x, 23] = rgba(BLK[1])
    for y in (10, 16, 21):
        px[1, y] = rgba(BLK[5])
        px[2, y] = rgba(BLK[3])
        px[1, y + 1] = rgba(BLK[3])
        px[2, y + 1] = rgba(BLK[0])
    px[2, 13] = rgba(RUST[2])
    px[1, 18] = rgba(RUST[1])
    # arms: px 8..21, rows 0..3
    for x in range(8, 22):
        for y, c in ((0, BLK[4]), (1, BLK[3]), (2, BLK[2]), (3, BLK[1])):
            px[x, y] = rgba(c)
    for y in range(4):
        px[8, y] = rgba(BLK[4])
        px[21, y] = rgba(BLK[1])
    for x in (9, 19):
        px[x, 1] = rgba(BLK[5])
        px[x + 1, 1] = rgba(BLK[3])
        px[x, 2] = rgba(BLK[3])
        px[x + 1, 2] = rgba(BLK[0])
    px[12, 2] = rgba(RUST[2])
    # boss plate under the gem: px 24..28, rows 0..4, bevelled with corner rivets
    for y in range(5):
        for x in range(24, 29):
            c = BLK[3]
            if y == 0 or x == 24:
                c = BLK[4]
            if y == 4 or x == 28:
                c = BLK[1]
            px[x, y] = rgba(c)
    for x, y in ((25, 1), (27, 1), (25, 3), (27, 3)):
        px[x, y] = rgba(BLK[5] if y == 1 else BLK[4])
    return img


def paint_black_iron():
    img = canvas(32, fill=BLK[2])
    px = img.load()
    rng = random.Random(8)
    for _ in range(22):                      # soft forge-scale blotches, two texels long
        x, y = rng.randrange(1, 30), rng.randrange(1, 31)
        px[x, y] = rgba(BLK[1])
        px[x + 1, y] = rgba(BLK[1])
    for _ in range(10):
        x, y = rng.randrange(1, 32), rng.randrange(1, 32)
        px[x, y] = rgba(BLK[3])
    for _ in range(6):
        x, y = rng.randrange(1, 32), rng.randrange(1, 32)
        px[x, y] = rgba(RUST[rng.choice((1, 2))])
    for i in range(32):
        px[i, 0] = rgba(BLK[4])
        px[0, i] = rgba(BLK[3])
    return img


def paint_socket():
    img = canvas(32, fill=BLK[2])
    px = img.load()
    for y in range(32):
        px[0, y] = rgba(BLK[4])
        px[1, y] = rgba(BLK[3])
        px[2, y] = rgba(BLK[2])
    for x in range(3):
        px[x, 0] = rgba(BLK[4])
        px[x, 6] = rgba(BLK[1])
    px[1, 3] = rgba(BLK[5])
    px[1, 4] = rgba(BLK[1])
    px[2, 2] = rgba(RUST[2])
    return img


def paint_band():
    img = canvas(32, fill=BLK[3])
    px = img.load()
    for x in range(32):
        px[x, 0] = rgba(BLK[5])
        px[x, 1] = rgba(BLK[3])
        px[x, 2] = rgba(BLK[1])
    for y in range(3):
        px[0, y] = rgba(BLK[4] if y else BLK[5])
    return img


def paint_wood():
    img = canvas(32, fill=WOOD[2])
    grain(img, (0, 0, 31, 31), [WOOD[1], WOOD[1], WOOD[3]], axis="y", seed=5, min_len=3, max_len=10, density=0.6)
    grain(img, (0, 0, 31, 31), [WOOD[0]], axis="y", seed=6, min_len=4, max_len=12, density=0.2)
    grain(img, (0, 0, 31, 31), [WOOD[3], WOOD[4]], axis="y", seed=7, min_len=2, max_len=5, density=0.12)
    px = img.load()
    for kx, ky in ((3, 20), (10, 6), (7, 13), (20, 24), (26, 9), (19, 12)):
        for dx, dy, c in ((0, -2, 0), (-1, -1, 0), (1, -1, 0), (-1, 0, 0), (1, 0, 0), (-1, 1, 0), (1, 1, 0),
                          (0, 2, 0), (0, -1, 3), (0, 0, 4), (0, 1, 3)):
            px[(kx + dx) % 32, (ky + dy) % 32] = rgba(WOOD[c])
    return img


def paint_endgrain():
    img = canvas(32, fill=WOOD[3])
    px = img.load()
    for y in range(32):
        for x in range(32):
            r = math.hypot(x + 0.5 - 9.2, y + 0.5 - 9.2)
            c = WOOD[1] if r > 2.1 else WOOD[3] if r > 1.4 else WOOD[2] if r > 0.7 else WOOD[4]
            px[x, y] = rgba(c)
    return img


def paint_bone():
    mask = bone_mask()
    dist = distance(mask)
    img = canvas(32)
    px = img.load()
    for (x, y), d in dist.items():
        if d == 1:
            left = (x - 1, y) not in mask
            up = (x, y - 1) not in mask
            right = (x + 1, y) not in mask
            down = (x, y + 1) not in mask
            if (up or left) and not (down or right):
                c = BONE[3]
            elif down or right:
                c = BONE[1]
            else:
                c = BONE[2]
        else:
            c = BONE[4]
            lobe = min(BONE_LOBES, key=lambda l: (x + 0.5 - l[0]) ** 2 + (y + 0.5 - l[1]) ** 2)
            ldx, ldy = x + 0.5 - lobe[0], y + 0.5 - lobe[1]
            if ldx * ldx + ldy * ldy <= BONE_R ** 2 and not 9 < x < 22:
                if ldx + ldy < -1.0:
                    c = BONE[5]
                elif ldx + ldy > 1.4:
                    c = BONE[3]
            else:
                if y == 4:
                    c = BONE[5]
                elif y == 5:
                    c = BONE[3]
        px[x, y] = rgba(c)
    for x, y in ((12, 5), (13, 5), (14, 4)):
        px[x, y] = rgba(BONE[2])
    for x, y in ((6, 7), (25, 2), (24, 7), (8, 2)):
        if (x, y) in dist and dist[(x, y)] > 1:
            px[x, y] = rgba(BONE[3])
    return img


def paint_bone_side():
    img = canvas(32, fill=BONE[3])
    px = img.load()
    bands = [BONE[2], BONE[4], BONE[5], BONE[4], BONE[3], BONE[2]]
    for x in range(8, 32):
        for y, c in enumerate(bands):
            px[x, y] = rgba(c)
    for y in range(8, 32):
        for x, c in enumerate(bands):
            px[x, y] = rgba(c)
    for x, y in ((12, 2), (19, 3), (25, 2), (2, 12), (3, 17)):
        px[x, y] = rgba(BONE[2])
    return img


def paint_twine():
    img = canvas(32, fill=TWINE[2])
    px = img.load()
    for y in range(32):
        for x in range(32):
            k = (x + y) % 3
            px[x, y] = rgba(TWINE[3] if k == 0 else TWINE[2] if k == 1 else TWINE[1])
    return img


def paint_cloth():
    img = canvas(32, fill=CLOTH[2])
    px = img.load()
    for y in range(32):
        for x in range(32):
            k = y % 3
            c = CLOTH[3] if k == 0 else CLOTH[2] if k == 1 else CLOTH[1]
            if k == 1 and (x + y) % 4 == 0:
                c = CLOTH[1]
            px[x, y] = rgba(c)
    rng = random.Random(4)
    for _ in range(14):
        px[rng.randrange(32), rng.randrange(0, 32, 3)] = rgba(CLOTH[4])
    return img


def paint_tatter():
    """Torn rag strips, painted to the length of each ribbon (top = knotted end, bottom =
    torn end): the long tail at u 0, the short tail at u 3, the grip's frayed end at u 5.5."""
    img = canvas(32)
    px = img.load()

    def strip(x0, w, rows, holes, seed):
        rng = random.Random(seed)
        shades = [CLOTH[4], CLOTH[3], CLOTH[3], CLOTH[2], CLOTH[1]]
        for y in range(rows):
            for i in range(w):
                c = shades[round(i * (len(shades) - 1) / max(1, w - 1))]
                if (i + y) % 4 == 0:
                    c = CLOTH[2] if i < w - 1 else CLOTH[1]
                if y == 0:
                    c = CLOTH[1]
                px[x0 + i, y] = rgba(c)
        for i in range(w):
            for y in range(rows - rng.choice((0, 1, 2)), rows):
                px[x0 + i, y] = (0, 0, 0, 0)
        for i, y in holes:
            px[x0 + i, y] = (0, 0, 0, 0)

    strip(0, 4, 12, [(1, 7), (2, 4)], 1)
    strip(6, 3, 9, [(1, 5)], 2)
    strip(11, 3, 5, [], 3)
    return img


def paint_glass():
    """Warm lit glass: amber at the frame, a brighter glow where the candle stands."""
    img = canvas(32, fill=(255, 158, 58, 150))
    px = img.load()
    for x in range(1, 5):
        for y in range(2, 6):
            px[x, y] = (255, 198, 96, 165)
    for x in range(2, 4):
        for y in range(3, 6):
            px[x, y] = (255, 222, 140, 175)
    for x, y in ((1, 1), (1, 2), (2, 1)):
        px[x, y] = (255, 248, 222, 225)
    for x in range(6):
        px[x, 0] = (214, 104, 36, 185)
        px[x, 6] = (196, 84, 28, 195)
    return img


def paint_candle():
    img = canvas(32, fill=WAX[2])
    px = img.load()
    for x in range(4):
        px[x, 0] = rgba(WAX[4])
        px[x, 3] = rgba(WAX[1])
    px[0, 1] = rgba(WAX[4])
    px[0, 2] = rgba(WAX[3])
    return img


def paint_flame():
    img = canvas(32, fill=FLAME[3])
    px = img.load()
    px[0, 0] = rgba(FLAME[4])
    px[1, 0] = rgba(FLAME[4])
    for x in range(4):
        px[x, 2] = rgba(FLAME[2])
    return img


def paint_web():
    """Cobweb in the corner at texel (0, 0), spanning 12 x 11 texels."""
    img = canvas(32)
    px = img.load()
    strand, knot = (236, 240, 255, 185), (250, 252, 255, 235)
    ends = []
    for a in (8, 34, 58, 84):
        c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
        t = 1 / (c / 12.0 + s / 11.0)
        ends.append((c * t, s * t))
    for ex, ey in ends:
        _line(px, 0, 0, round(ex * 0.96), round(ey * 0.96), strand)
    for f in (0.42, 0.8):
        pts = [(ex * f, ey * f) for ex, ey in ends]
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            mx, my = (x0 + x1) / 2 * 0.88, (y0 + y1) / 2 * 0.88     # sag toward the corner
            _line(px, round(x0), round(y0), round(mx), round(my), strand)
            _line(px, round(mx), round(my), round(x1), round(y1), strand)
        for x, y in pts:
            if 0 <= round(x) < 32 and 0 <= round(y) < 32:
                px[round(x), round(y)] = knot
    px[0, 0] = knot
    return img


def paint_cap():
    """Glowing toadstool cap: bright rim, pale spots, deeper green toward the edge."""
    img = canvas(32, fill=TOXIC[2])
    px = img.load()
    for i in range(32):
        px[i, 0] = rgba(TOXIC[3])
        px[0, i] = rgba(TOXIC[3])
    for x, y in ((1, 1), (3, 2), (2, 4), (4, 4), (1, 3), (5, 1), (6, 3), (3, 6), (7, 6), (5, 5)):
        px[x, y] = rgba(TOXIC[4])
    for x, y in ((2, 1), (4, 2), (2, 5), (6, 5), (4, 6)):
        px[x, y] = rgba(TOXIC[1])
    return img


def paint_gill():
    img = canvas(32, fill="#c6e4a6")
    px = img.load()
    for y in range(32):
        for x in range(0, 32, 2):
            px[x, y] = rgba("#8fb86b")
    return img


def paint_stem():
    img = canvas(32, fill="#d6e2c0")
    px = img.load()
    for y in range(32):
        px[1, y] = rgba("#a9b894")
    return img


def textures() -> None:
    save(paint_blade("s"), "blade_s")
    save(paint_blade("n"), "blade_n")
    save(paint_edge(), "blade_edge")
    save(paint_cross_glow(), "cross_glow")
    save(paint_gem(), "gem")
    save(paint_strap(), "strap")
    save(paint_black_iron(), "black_iron")
    save(canvas(16, fill=BLK[1]), "black_flat")
    save(paint_socket(), "socket")
    save(paint_band(), "band")
    save(paint_wood(), "wood")
    save(paint_endgrain(), "endgrain")
    save(paint_bone(), "bone")
    save(paint_bone_side(), "bone_side")
    save(paint_twine(), "twine")
    save(paint_cloth(), "cloth")
    save(paint_tatter(), "tatter")
    save(paint_glass(), "glass")
    save(paint_candle(), "candle")
    save(paint_flame(), "flame")
    save(paint_web(), "web")
    save(paint_cap(), "cap")
    save(paint_gill(), "gill")
    save(paint_stem(), "stem")


# ---------------------------------------------------------------------------------------
# Geometry
# ---------------------------------------------------------------------------------------

def blade_box(x0, y0, x1, y1, z0, z1) -> dict:
    """A piece of the tombstone blade; both faces sample the blade art in place."""
    v0, v1 = 28.5 - y1, 28.5 - y0
    dz = z1 - z0
    return box((x0, y0, z0), (x1, y1, z1), "blade_edge", faces={
        "south": ("blade_s", [x0, v0, x1, v1]),
        "north": ("blade_n", [16 - x1, v0, 16 - x0, v1]),
        "east": ("blade_edge", [7, v0, 7 + dz, v1]),
        "west": ("blade_edge", [7, v0, 7 + dz, v1]),
        "up": ("blade_edge", [x0, 7, x1, 7 + dz]),
        "down": ("blade_edge", [x0, 7, x1, 7 + dz]),
    })


def blade_parts() -> list:
    parts = []
    for pa, pb, l, r in row_groups(blade_rows()):
        parts.append(blade_box(l / 2, (56 - pb) / 2, (r + 1) / 2, (57 - pa) / 2, *BLADE_Z))
    panel = {p for p, d in distance(blade_mask("s")).items() if d >= 3}
    for a, c, b, d in rects(panel):
        parts.append(blade_box(a / 2, (56 - d) / 2, (b + 1) / 2, (57 - c) / 2, *PANEL_Z))
    # tread / tombstone base, and a narrower second tier onto the socket
    parts.append(box((1.0, 12.5, 7.0), (15.0, 14.5, 9.0), "black_iron", faces={
        "south": ("blade_s", [1, 14, 15, 16]), "north": ("blade_n", [1, 14, 15, 16]),
        "up": ("black_iron", [1, 0, 15, 2]), "down": ("black_iron", [1, 2, 15, 4]),
        "east": ("black_iron", [0, 4, 2, 6]), "west": ("black_iron", [0, 4, 2, 6])}))
    parts.append(box((4.5, 11.5, 7.2), (11.5, 12.5, 8.8), "black_iron", offset=(2, 6)))
    # riveted iron cross strapped to the back of the blade
    parts.append(box((7.0, 14.5, 6.7), (9.0, 26.0, 7.5), "black_iron",
                     faces={"north": ("strap", [0, 0, 2, 11.5])}, skip=("south",)))
    parts.append(box((4.5, 22.5, 6.6), (11.5, 24.5, 7.2), "black_iron",
                     faces={"north": ("strap", [4, 0, 11, 2])}, skip=("south",)))
    parts.append(box((6.9, 22.4, 6.25), (9.1, 24.6, 6.6), "black_iron",
                     faces={"north": ("strap", [12, 0, 14.2, 2.2])}, skip=("south",)))
    parts.append(box((7.25, 22.75, 6.0), (8.75, 24.25, 6.25), "gem", uv={"north": [0, 0, 1.5, 1.5]},
                     skip=("south",), glow=12))
    # ghost-teal light pooled in the carved cross on the front
    parts.append(box((4.5, 19.0, 8.86), (11.5, 26.0, 8.86), "cross_glow",
                     faces={"south": ("cross_glow", [4.5, 2.5, 11.5, 9.5])},
                     skip=("north", "east", "west", "up", "down"), glow=12, shade=False))
    return parts


def collar_parts(side: int) -> list:
    """Socket, collar band and the lantern hook on the -X (side=-1) or +X (side=1) side."""
    parts = prism((8, 10.1, 8), 1.65, 3.4, "socket", cap="black_flat")
    parts += prism((8, 8.75, 8), 2.0, 1.1, "band", cap="black_flat")
    parts.append(bar((8 + side * 1.7, 8.75, 8.0), (8 + side * 3.05, 8.75, 8.0), 0.55, 0.55, "black_iron"))
    return parts


def lantern(ax, ay, az, s=1.0) -> list:
    """An iron lantern hanging straight down (-Y) from the chain end (ax, ay, az)."""
    def b(x0, y0, z0, x1, y1, z1, tex="black_iron", **kw):
        return box((ax + x0 * s, ay + y0 * s, az + z0 * s), (ax + x1 * s, ay + y1 * s, az + z1 * s), tex, **kw)

    parts = [
        b(-0.3, -1.0, -0.1, 0.3, 0.0, 0.1),               # chain link
        b(-0.55, -1.8, -0.12, 0.55, -0.85, 0.12),         # handle ring
        b(-0.45, -2.3, -0.45, 0.45, -1.7, 0.45),          # finial
        b(-1.1, -2.85, -1.1, 1.1, -2.25, 1.1),            # roof
        b(-1.8, -3.35, -1.8, 1.8, -2.8, 1.8),             # eave
        b(-1.35, -6.75, -1.35, 1.35, -3.35, 1.35, "glass", glow=14, shade=False),
        b(-1.8, -7.3, -1.8, 1.8, -6.75, 1.8),             # base
        b(-0.7, -7.75, -0.7, 0.7, -7.3, 0.7),             # foot
        b(-0.5, -6.75, -0.5, 0.5, -5.2, 0.5, "candle", glow=10),
        b(-0.32, -5.2, -0.32, 0.32, -4.0, 0.32, "flame", glow=15, shade=False),
    ]
    for cx in (-1.35, 1.35):
        for cz in (-1.35, 1.35):
            parts.append(b(cx - 0.25, -6.75, cz - 0.25, cx + 0.25, -3.35, cz + 0.25))
    return parts


def web_parts() -> list:
    return [box((9.6, 7.0, 8.0), (15.6, 12.5, 8.0), "web",
                faces={"south": ("web", [0, 0, 6, 5.5]), "north": ("web", [6, 0, 0, 5.5])},
                skip=("east", "west", "up", "down"))]


NODES = [(8.0, -12.6, 8.0), (8.0, -4.4, 8.0), (8.4, -0.2, 7.85), (7.75, 3.9, 8.2), (8.1, 6.6, 7.95),
         (8.0, 10.2, 8.0)]
WIDTHS = [2.3, 2.4, 2.2, 2.35, 2.25]
ROLLS = [0, 18, -12, 24, 6]


def shaft_parts(lift: float = 0.0) -> list:
    """The gnarled shaft; `lift` shortens it from the grip end (for the compact icon)."""
    nodes = [(8.0, NODES[0][1] + lift, 8.0)] + NODES[1:]
    parts = []
    for i in range(5):
        a, b = nodes[i], nodes[i + 1]
        d = [b[k] - a[k] for k in range(3)]
        n = math.sqrt(sum(v * v for v in d))
        ext = 0.4 / n
        a2 = [a[k] - d[k] * ext for k in range(3)] if i else list(a)
        b2 = [b[k] + d[k] * ext for k in range(3)]
        parts.append(bar(a2, b2, WIDTHS[i], WIDTHS[i], "wood", roll=ROLLS[i],
                         offset=((i * 3.1) % 12, (i * 2.3) % 5)))
    x, y, z = NODES[3]
    burl = box((x - 1.45, y - 0.7, z - 1.45), (x + 1.45, y + 0.7, z + 1.45), "wood", offset=(9, 10))
    parts.append(turn(burl, -22, "y", (x, y, z)))
    if not lift:     # the compact icon drops the twig stub; its lifted grip wrap would swallow it
        parts.append(bar((7.7, -3.2, 8.0), (5.1, -0.9, 7.7), 1.15, 1.15, "wood", roll=20, offset=(12, 2),
                         faces={"up": ("endgrain", [4, 4, 5.15, 5.15])}))
    return parts


def wrap_parts(lift: float = 0.0) -> list:
    parts = []
    for i in range(3):                                   # grip wrap
        y = -9.9 + i * 1.45 + lift
        b = box((6.55, y, 6.55), (9.45, y + 1.5, 9.45), "cloth", offset=(0, (i * 1.5) % 9))
        turn(b, 10, "z", (8, y + 0.75, 8))
        parts.append(b)
    parts.append(box((6.7, -11.9 + lift, 6.7), (9.3, -10.4 + lift, 9.3), "twine"))
    # the wrap's frayed loose end, peeling off the top of the grip
    parts.append(ribbon((9.9, -7.1 + lift, 9.95), (8.9, -4.9 + lift, 9.35), 1.3, (5.5, 0)))
    # a rag knotted under the collar, two torn tails fluttering off it
    rag = box((6.55, 6.7, 6.55), (9.55, 8.2, 9.45), "cloth", offset=(3, 3))
    parts.append(turn(rag, -8, "z", (8, 7.45, 8)))
    parts.append(box((7.4, 6.6, 5.9), (8.9, 8.1, 6.6), "cloth", offset=(6, 4)))          # the knot
    parts.append(ribbon((9.3, 1.6, 4.4), (8.3, 7.0, 6.1), 2.0, (0, 0)))
    parts.append(ribbon((5.3, 3.5, 5.3), (7.7, 6.8, 6.1), 1.5, (3, 0)))
    return parts


def ribbon(free, attached, width, region) -> dict:
    """A double-sided torn cloth strip from its attached end to its free end."""
    u, v = region
    length = min(7.0, math.dist(free, attached))
    return bar(free, attached, width, 0.0, "tatter",
               faces={"south": ("tatter", [u, v, u + width, v + length]),
                      "north": ("tatter", [u + width, v, u, v + length])},
               skip=("east", "west", "up", "down"))


def mushroom(base, tip, stem_w, r) -> list:
    """A foxfire toadstool growing out of the wood along +X: stem, pale gills, a domed
    cap that overhangs them and a small crown."""
    x, y, z = tip
    return [
        bar(base, tip, stem_w, stem_w, "stem", glow=4),
        box((x - 0.15, y - r * 0.8, z - r * 0.8), (x + 0.15, y + r * 0.8, z + r * 0.8), "gill", glow=6),
        box((x + 0.15, y - r, z - r), (x + 0.15 + r * 0.45, y + r, z + r), "cap", glow=11),
        box((x + 0.15 + r * 0.45, y - r * 0.58, z - r * 0.58), (x + 0.15 + r * 0.75, y + r * 0.58, z + r * 0.58),
            "cap", glow=11, offset=(2, 2)),
    ]


def mushroom_parts() -> list:
    return mushroom((9.0, 3.5, 8.3), (10.5, 3.9, 8.45), 0.6, 1.2) + \
        mushroom((9.0, 2.1, 7.6), (9.85, 1.85, 7.25), 0.45, 0.78)


def bone_parts(lift: float = 0.0) -> list:
    mask = bone_mask()
    inner = {p for p, d in distance(mask).items() if d >= 2}
    parts = []
    for layer, (z0, z1) in ((mask, (6.9, 9.1)), (inner, (6.6, 9.4))):
        for a, c, b, d in rects(layer):
            x0, x1 = a / 2, (b + 1) / 2
            y0, y1 = BONE_TOP - (d + 1) / 2 + lift, BONE_TOP - c / 2 + lift
            dz = z1 - z0
            parts.append(box((x0, y0, z0), (x1, y1, z1), "bone_side", faces={
                "south": ("bone", [a / 2, c / 2, (b + 1) / 2, (d + 1) / 2]),
                "north": ("bone", [(b + 1) / 2, c / 2, a / 2, (d + 1) / 2]),
                "up": ("bone_side", [4, 0, 4 + (x1 - x0), dz]),
                "down": ("bone_side", [4, 0, 4 + (x1 - x0), dz]),
                "east": ("bone_side", [0, 4, dz, 4 + (y1 - y0)]),
                "west": ("bone_side", [0, 4, dz, 4 + (y1 - y0)])}))
    return parts


def build(for_gui: bool = False) -> list:
    """The whole spade. In hand the lantern hangs from the -X side (down in third person,
    swung a little back); the inventory icon hangs it from the +X side, straight down, and
    shortens the shaft a little so the tombstone reads bigger in the slot."""
    side = 1 if for_gui else -1
    lift = 4.0 if for_gui else 0.0
    anchor = (8 + side * 3.05, 8.75, 8.0)
    swing = 45 if for_gui else -60
    parts = blade_parts() + collar_parts(side)
    parts += turn(lantern(*anchor), swing, "z", anchor)
    parts += web_parts() + shaft_parts(lift) + wrap_parts(lift) + mushroom_parts() + bone_parts(lift)
    return parts


FIRST_PERSON = ({"y": (-0.32, 0.88, -0.36), "z": (-0.45, 0.05, 0.9)}, (0.80, -0.78, -0.92), 0.56)


def models() -> dict:
    parts = build()
    d = display("shovel", parts, grip=GRIP, size=SIZE)
    # First person: smaller than the huge vanilla-scaled pose, leaning into the view with
    # the tombstone's carved face turned toward the camera.
    axes, target, scale = FIRST_PERSON
    d["firstperson_righthand"] = place(axes, GRIP, target, scale, pose=None)
    # Item frames and shelves: hung on the wall at a shallow angle so the lantern dangles
    # straight down under the collar instead of sticking out sideways.
    d["fixed"] = fit(parts, (0, 180, 60), 16.0)
    d["on_shelf"] = fit(parts, (0, 0, 60), 12.0)
    icon = build(for_gui=True)
    gd = display("shovel", icon, grip=GRIP, size=SIZE, gui_span=15.6)
    return {"main": model(parts, d), "gui": model(icon, gd)}
