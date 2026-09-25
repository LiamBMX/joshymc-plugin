"""Lumberjack's Legacy: a massive double-bitted felling axe (Woodland Limited Edition).

A long hickory handle with a red-and-black buffalo-plaid grip wrap between beaded brass
collars, a walnut swell knob with a brass butt cap, a riveted brass maker's plate, a
stitched leather overstrike guard strapped under the head, and a heavy forged steel head
whose two broad bits flare out to polished edges, each etched with an enamelled pine
around a brass-ringed maple-red medallion on the eye.

The head is modelled like an extruded pixel sprite: a 2-texels-per-unit grid of thickness
levels (edge, bevel, body, eye, boss) is meshed into boxes, and one side painting of the
head is projected onto every cheek face so the etching runs seamlessly across the boxes.
"""
from __future__ import annotations

import math
import random

from art.kit import box, canvas, display, fill, grain, mix, model, outline, place, ramp, rgba, save, turn

ID = "lumberjacks_legacy"
NAME = "Lumberjack's Legacy"
KIND = "axe"

D = 2  # texels per model unit on every surface

# Palettes, darkest -> lightest (hue-shifted ramps).
STEEL = ramp("#66727e", 8, 0.8, 0.1) + ["#f6f8ef"]  # [8] is the specular glint
HICKORY = ramp("#c28b55", 7, 0.6, 0.1)
WALNUT = ramp("#5c3a24", 6, 0.62, 0.08)
BRASS = ramp("#c99a33", 7, 0.72, 0.1)
COPPER = ramp("#b25f35", 6, 0.66, 0.08)
LEATHER = ramp("#80502e", 7, 0.7, 0.08)
RED = ramp("#b3252b", 5, 0.55, 0.06)
BLACK = ramp("#2b2328", 4, 0.45, 0.05)
PINE = ramp("#357a47", 6, 0.7, 0.1)

# --------------------------------------------------------------------------------------
# Layout along the handle (model units, handle axis x = 8, z = 8)
# --------------------------------------------------------------------------------------
HX, HY = 8.0, 24.3            # head centre
EYE_W, EYE_H = 3.0, 4.4       # half extents of the eye block
EDGE = 13.5                   # axis-to-edge distance on the centre line
GUARD_LEN = 4.0               # leather overstrike guard under the head
GIRTH = 1.12                  # handle thickness multiplier
RADIUS = {"wood": 1.45, "plaid": 1.7, "guard": 1.8, "collar": 1.95, "bead": 2.1, "neck": 1.85,
          "knob": 2.2, "butt": 2.15}


def rad(part: str) -> float:
    return round(RADIUS[part] * GIRTH, 4)


def _layout(short: bool = False) -> dict:
    """Y ranges of the handle parts, built down from the head. The inventory icon uses a
    shorter handle (short=True) so the head reads bigger in the slot."""
    y = HY - EYE_H
    out = {}
    for name, length in (("guard", GUARD_LEN), ("wood", 5.6 if short else 10.8), ("collar", 1.5),
                         ("plaid", 6.4 if short else 9.3), ("low", 1.0), ("neck", 1.0), ("knob", 0.8),
                         ("butt", 0.8)):
        out[name] = (round(y - length, 4), round(y, 4))
        y -= length
    w0, w1 = out["wood"]
    mid = (w0 + w1) / 2 - (0.6 if not short else 0.0)
    out["plate"] = (mid - 2.0, mid + 2.0)
    p0, p1 = out["plaid"]
    out["grip"] = (8.0, p0 + (p1 - p0) * 0.37, 8.0)          # where the fist closes
    return out


LAYOUT = _layout()
GRIP = LAYOUT["grip"]

# --------------------------------------------------------------------------------------
# Head shape: a grid of thickness levels seen from the side (+Z)
# --------------------------------------------------------------------------------------
GRID_W, GRID_H = 29.0, 18.0
HX0, HY1 = HX - GRID_W / 2, HY + GRID_H / 2      # top-left corner of the grid, units
NX, NY = int(GRID_W * D), int(GRID_H * D)        # 58 x 36 texels
ATLAS, NORTH = 128, 64                           # head atlas size; row of the north view
THICK = {1: 0.7, 2: 1.5, 3: 2.8, 4: 4.3, 5: 5.0}
GLEAM = 6                     # light emission of the honed edges: a moonlit glint at night


def _edge_d(dy: float) -> float:
    """Axis distance of the convex cutting edge at height dy from the centre."""
    return EDGE - 2.1 * (dy / 8.4) ** 2


def _half_height(d: float) -> float:
    """Half height of a bit at axis distance d: the flare toward the toe and heel."""
    t = max(0.0, (d - EYE_W) / (EDGE - EYE_W))
    return 3.3 + 5.1 * t ** 1.8


def _level(x: float, y: float) -> int:
    d, dy = abs(x - HX), y - HY
    if d <= EYE_W:
        if abs(dy) > EYE_H:
            return 0
        return 5 if d <= EYE_W - 0.5 and abs(dy) <= EYE_H - 0.5 else 4
    if abs(dy) > _half_height(d):
        return 0
    e = _edge_d(dy) - d
    if e < 0:
        return 0
    return 1 if e < 0.8 else 2 if e < 2.2 else 3


def _grid() -> list[list[int]]:
    return [[_level(HX0 + (i + 0.5) / D, HY1 - (j + 0.5) / D) for i in range(NX)] for j in range(NY)]


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

PINE_ROWS = [  # (half width, tier) per row of the tree, tip first: three stepped tiers
    (0, 0), (1, 0), (1, 0), (2, 0),
    (1, 1), (2, 1), (3, 1),
    (2, 2), (3, 2), (4, 2), (4, 2),
]


def _pine_sprite():
    """A three-tier pine in green enamel, lit from the upper left, with an engraved
    outline (a 1-texel margin keeps the outline whole)."""
    w, h = 11, len(PINE_ROWS) + 4
    img = canvas(w, h)
    p = img.load()
    cx = w // 2
    for r, (half, tier) in enumerate(PINE_ROWS):
        y = r + 1
        first = r == 0 or PINE_ROWS[r - 1][1] != tier
        last = r == len(PINE_ROWS) - 1 or PINE_ROWS[r + 1][1] != tier
        for rel in range(-half, half + 1):
            colour = PINE[3]
            if rel == -half:
                colour = PINE[5] if tier == 0 or not last else PINE[4]
            elif rel == -half + 1:
                colour = PINE[4]
            elif rel >= half - (1 if half < 3 else 2):
                colour = PINE[2]
            if last and rel > -half + 1:
                colour = PINE[2] if rel < half - 1 else PINE[1]
            if first and tier and rel > -half:
                colour = PINE[1]                            # shadow under the tier above
            p[cx + rel, y] = rgba(colour)
    for y in (len(PINE_ROWS) + 1, len(PINE_ROWS) + 2):  # trunk
        p[cx, y] = rgba(WALNUT[4] if y == len(PINE_ROWS) + 1 else WALNUT[3])
    return outline(img, STEEL[1])


def _medallion(p, cx: float, cy: float) -> None:
    """A brass-ringed boss with a maple-red enamel centre and a bright glint."""
    for dj in range(-5, 6):
        for di in range(-5, 6):
            r = math.hypot(di, dj)
            x, y = int(cx + di), int(cy + dj)
            if r <= 2.3:
                c = RED[3] if (di < 0 and dj < 0) else RED[2] if di + dj <= 1 else RED[1]
                p[x, y] = rgba(c)
            elif r <= 3.6:
                lit = di + dj < 0
                p[x, y] = rgba(BRASS[5] if lit and r < 3.1 else BRASS[4] if lit else BRASS[2] if r >= 3.1 else BRASS[3])
            elif r <= 4.3:
                p[x, y] = rgba(STEEL[1] if di + dj <= 0 else STEEL[4])   # recess around the ring
    p[int(cx) - 1, int(cy) - 1] = rgba(RED[4])
    p[int(cx) - 1, int(cy) - 2] = rgba("#ffd2c4")


def _paint_head() -> None:
    grid = _grid()
    south = canvas(NX, NY)
    p = south.load()

    def lv(i, j):
        return grid[j][i] if 0 <= i < NX and 0 <= j < NY else 0

    shade = [[0] * NX for _ in range(NY)]
    for j in range(NY):
        for i in range(NX):
            L = grid[j][i]
            if not L:
                continue
            x = HX0 + (i + 0.5) / D
            y = HY1 - (j + 0.5) / D
            d, dy = abs(x - HX), y - HY
            out = -1 if x < HX else 1          # one texel toward this bit's edge
            if L >= 4:
                v = dy / EYE_H
                c = 4 if v > 0.35 else 3 if v > -0.35 else 2
            elif L == 3:
                v = dy / _half_height(d)
                c = 4 if v > 0.45 else 3 if v > -0.45 else 2
                if 0 <= (d * D + dy * D * 1.1 + 4) % 34 < 2:   # faint diagonal sheen
                    c += 1
            elif L == 2:
                c = 6 if dy > -1.5 else 5
            else:
                c = 7
            # silhouette rims: lit top, shadowed underside
            if not lv(i, j - 1):
                c = 8 if L <= 2 else 6
            elif not lv(i, j - 2) and L == 3:
                c = 5
            elif not lv(i, j + 1):
                c = 1 if L >= 3 else 4
            # grind lines between the thickness steps
            if L == 3 and lv(i + out, j) == 2:
                c = 1
            if L == 2 and lv(i - out, j) == 3:
                c = 8
            if L == 1 and not lv(i + out, j):
                c = 8                                # the honed edge itself
            shade[j][i] = c
            p[i, j] = rgba(STEEL[c])

    # brushed-steel streaks: a few deliberate horizontal highlights in the lit band
    for side in (-1, 1):
        for d0, dy0, n in ((3.6, 2.2, 3), (4.1, 1.2, 2), (3.4, -2.6, 2)):
            for k in range(n):
                x = HX + side * (d0 + k / D)
                i, j = int((x - HX0) * D), int((HY1 - (HY + dy0)) * D)
                if lv(i, j) == 3:
                    p[i, j] = rgba(STEEL[min(6, shade[j][i] + 1)])

    # specular glints on the polished toes and heels
    for j in range(NY):
        for i in range(NX):
            if lv(i, j) == 1 and not lv(i, j - 1) and not lv(i, j - 2):
                p[i, j] = rgba(STEEL[8])

    # eye: a chamfer frame around the raised boss, which carries the medallion
    eye = [(i, j) for j in range(NY) for i in range(NX) if lv(i, j) >= 4]
    i0, i1 = min(i for i, _ in eye), max(i for i, _ in eye)
    j0, j1 = min(j for _, j in eye), max(j for _, j in eye)
    for i in range(i0, i1 + 1):
        p[i, j0] = rgba(STEEL[7])
        p[i, j1] = rgba(STEEL[1])
    for j in range(j0 + 1, j1):
        p[i0, j] = rgba(STEEL[5])
        p[i1, j] = rgba(STEEL[2])
    for i in range(i0 + 1, i1):
        p[i, j0 + 1] = rgba(STEEL[6])
        p[i, j1 - 1] = rgba(STEEL[2])
    for j in range(j0 + 2, j1 - 1):
        p[i0 + 1, j] = rgba(STEEL[5])
        p[i1 - 1, j] = rgba(STEEL[2])
    _medallion(p, (i0 + i1) / 2 + 0.5, (j0 + j1) / 2 + 0.5)

    # etched pines on both bits
    pine = _pine_sprite()
    for side in (-1, 1):
        ci = (HX + side * 8.0 - HX0) * D
        south.alpha_composite(pine, (int(round(ci - pine.width / 2)), int(round((HY1 - HY) * D - pine.height / 2))))

    atlas = canvas(ATLAS)
    atlas.paste(south, (0, 0))
    atlas.paste(south.transpose(0), (0, NORTH))  # the north cheek is the mirror image
    save(atlas, "head")


def _rim(base: int, name: str, seed: int) -> None:
    img = canvas(32, fill=STEEL[base])
    grain(img, (0, 0, 31, 31), [STEEL[max(0, base - 1)], STEEL[min(7, base + 1)]], axis="x", seed=seed,
          min_len=2, max_len=7, density=0.3)
    save(img, name)


def _paint_wood() -> None:
    """Hickory: long, calm grain streaks and a couple of darker heart lines."""
    rng = random.Random(3)
    wood = canvas(32, fill=HICKORY[3])
    p = wood.load()
    for x in range(32):
        lane = rng.choice([3, 3, 3, 4, 4, 2])
        y = rng.randint(0, 6) - 6
        while y < 32:
            n = rng.randint(7, 18)
            c = lane if rng.random() < 0.75 else rng.choice([2, 4])
            for k in range(n):
                if 0 <= y + k < 32:
                    p[x, y + k] = rgba(HICKORY[c])
            y += n
    for x in (4, 13, 21, 28):                           # darker heart lines
        y = rng.randint(0, 8)
        for k in range(rng.randint(12, 22)):
            if y + k < 32:
                p[x, y + k] = rgba(HICKORY[2] if k % 9 else HICKORY[1])
    # hand-worn patina: the wood darkens toward the grip (the bottom of the bare run)
    for y in range(15, 22):
        t = (y - 14) / 8
        for x in range(32):
            if rng.random() < t * 0.8:
                r, g, b, a = p[x, y]
                p[x, y] = rgba(mix((r, g, b, a), HICKORY[1], 0.35))
    save(wood, "hickory")


def _paint_plaid() -> None:
    """Buffalo check: 4-texel red, black and mixed (red/black woven) blocks, twill on the red."""
    plaid = canvas(32)
    p = plaid.load()
    for y in range(32):
        for x in range(32):
            warp_red = (x // 4) % 2 == 0
            weft_red = (y // 4) % 2 == 0
            diag = (x - y) % 4 == 0
            if warp_red and weft_red:
                c = RED[3] if diag else RED[2]
            elif not warp_red and not weft_red:
                c = BLACK[0]
            else:
                c = RED[1] if (x + y) % 2 else mix(RED[1], BLACK[0], 0.5)
            p[x, y] = rgba(c)
    save(plaid, "plaid")


def _paint_metal() -> None:
    brass = canvas(32, fill=BRASS[3])
    fill(brass, (0, 0, 31, 0), BRASS[6])
    fill(brass, (0, 1, 31, 1), BRASS[4])
    fill(brass, (0, 2, 31, 2), BRASS[2])
    for x in range(0, 32, 5):                           # glints that roll around the ring
        brass.load()[x, 1] = rgba(BRASS[6])
    save(brass, "brass")

    bead = canvas(32, fill=BRASS[5])
    fill(bead, (0, 1, 31, 31), BRASS[2])
    save(bead, "bead")

    # the maker's plate (3 x 8 texels): two copper rivets around an engraved maker's mark
    plate = canvas(32, fill=BRASS[3])
    rows = ["666", "5c4", "545", "434", "414", "434", "4c3", "222"]
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            plate.load()[x, y] = rgba(COPPER[4] if ch == "c" else BRASS[int(ch)])
    fill(plate, (4, 0, 31, 31), BRASS[2])
    save(plate, "plate")

    rivet = canvas(32, fill=COPPER[3])
    fill(rivet, (0, 0, 0, 0), COPPER[5])
    save(rivet, "rivet")


def _paint_leather() -> None:
    rng = random.Random(5)
    leather = canvas(32, fill=LEATHER[3])
    p = leather.load()
    for _ in range(50):                                 # soft mottling
        x, y = rng.randrange(31), rng.randrange(2, 6)
        p[x, y] = rgba(LEATHER[rng.choice([2, 4])])
        p[x + 1, y] = rgba(LEATHER[rng.choice([3, 4])])
    for _ in range(7):                                  # scuffs: a pale scrape over a dark crease
        x, y = rng.randrange(1, 29), rng.randrange(2, 5)
        p[x, y] = rgba(LEATHER[5])
        p[x + 1, y] = rgba(LEATHER[5])
        p[x + 1, y + 1] = rgba(LEATHER[1])
        p[x + 2, y + 1] = rgba(LEATHER[2])
    fill(leather, (0, 0, 31, 0), LEATHER[1])            # burnished, stitched edges
    fill(leather, (0, 7, 31, 7), LEATHER[0])
    for x in range(0, 32, 2):
        p[x, 1] = rgba(LEATHER[6])
        p[x + 1, 1] = rgba(LEATHER[2])
        p[x + 1, 6] = rgba(LEATHER[6])
        p[x, 6] = rgba(LEATHER[2])
    fill(leather, (0, 8, 31, 31), LEATHER[2])
    save(leather, "leather")

    walnut = canvas(32, fill=WALNUT[3])
    grain(walnut, (0, 0, 31, 31), [WALNUT[2], WALNUT[4]], axis="y", seed=8, min_len=2, max_len=5, density=0.45)
    fill(walnut, (0, 0, 31, 0), WALNUT[5])
    save(walnut, "walnut")

    caps = canvas(16)
    fill(caps, (0, 0, 7, 7), HICKORY[4])
    fill(caps, (8, 0, 15, 7), BRASS[3])
    fill(caps, (0, 8, 7, 15), WALNUT[3])
    fill(caps, (8, 8, 15, 15), LEATHER[2])
    save(caps, "caps")


def textures() -> None:
    _paint_head()
    _rim(6, "edge_rim", 1)
    _rim(4, "bevel_rim", 2)
    _rim(2, "body_rim", 3)
    _rim(2, "eye_rim", 4)
    _paint_wood()
    _paint_plaid()
    _paint_metal()
    _paint_leather()


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

CAP = {"hickory": ("caps", [0, 0, 8, 8]), "brass": ("caps", [8, 0, 16, 8]), "bead": ("caps", [8, 0, 16, 8]),
       "walnut": ("caps", [0, 8, 8, 16]), "leather": ("caps", [8, 8, 16, 16]), "plaid": ("caps", [8, 8, 16, 16])}


def shaft(y0: float, y1: float, r: float, tex: str, sides: int = 8, px: int = 32, u0: float = 0.0,
          v0: float = 0.0, caps=(True, True), cx: float = HX, cz: float = 8.0, seam: float = 225.0) -> list[dict]:
    """A round shaft along Y with `tex` wrapped around it at D texels per unit.
    Face k starts k face-widths along u, so patterns flow around the shaft; the wrap
    seam sits at angle `seam` (degrees from +X toward +Z), at the back by default."""
    half = r * math.tan(math.pi / sides)
    fw = 2 * half
    step = 360.0 / sides
    k_uv = 16.0 / px
    length = y1 - y0

    def side_uv(phi: float):
        k = int(round(((seam - phi) % 360.0) / step)) % sides
        a = u0 + k * fw * D
        return [round(a * k_uv, 4), round(v0 * k_uv, 4), round((a + fw * D) * k_uv, 4),
                round((v0 + length * D) * k_uv, 4)]

    cap = CAP.get(tex)
    drop = tuple(s for s, keep in zip(("down", "up"), caps) if not keep)
    parts = []
    for a in ((0, 45) if sides == 8 else (0, 22.5, 45, -22.5)):
        f1 = {"east": (tex, side_uv(-a)), "west": (tex, side_uv(180 - a))}
        f2 = {"south": (tex, side_uv(90 - a)), "north": (tex, side_uv(270 - a))}
        if cap:
            f1.update(up=cap, down=cap)
            f2.update(up=cap, down=cap)
        s1 = box((cx - r, y0, cz - half), (cx + r, y1, cz + half), tex, faces=f1, skip=("north", "south") + drop)
        s2 = box((cx - half, y0, cz - r), (cx + half, y1, cz + r), tex, faces=f2, skip=("east", "west") + drop)
        if a:
            turn([s1, s2], a, "y", (cx, (y0 + y1) / 2, cz))
        parts += [s1, s2]
    return parts


def _cheek_uv(i0: int, i1: int, j0: int, j1: int):
    k = 16 / ATLAS
    south = [i0 * k, j0 * k, i1 * k, j1 * k]
    north = [(NX - i1) * k, (NORTH + j0) * k, (NX - i0) * k, (NORTH + j1) * k]
    return south, north


def head() -> list[dict]:
    grid = _grid()
    rim = {1: "edge_rim", 2: "bevel_rim", 3: "body_rim", 4: "eye_rim", 5: "eye_rim"}
    # Per column and level: the rows spanned by cells of that level or thicker, only for
    # levels present in the column, so no two boxes share a visible face.
    cols = []
    for i in range(NX):
        levels = [grid[j][i] for j in range(NY)]
        spec = {}
        for L in set(levels) - {0}:
            rows = [j for j in range(NY) if levels[j] >= L]
            spec[L] = (min(rows), max(rows) + 1)
        cols.append(spec)
    parts = []
    for L in sorted(THICK):
        i = 0
        while i < NX:
            span = cols[i].get(L)
            if not span:
                i += 1
                continue
            k = i
            while k + 1 < NX and cols[k + 1].get(L) == span:
                k += 1
            i0, i1, j0, j1 = i, k + 1, span[0], span[1]
            t = THICK[L] / 2
            south, north = _cheek_uv(i0, i1, j0, j1)
            parts.append(box((HX0 + i0 / D, HY1 - j1 / D, 8 - t), (HX0 + i1 / D, HY1 - j0 / D, 8 + t), rim[L],
                             faces={"south": ("head", south), "north": ("head", north)},
                             glow=GLEAM if L == 1 else 0))
            i = k + 1
    return parts


def handle(lay: dict) -> list[dict]:
    parts = []
    parts += shaft(*lay["butt"], rad("butt"), "brass", sides=16, caps=(True, False))       # butt cap
    parts += shaft(*lay["knob"], rad("knob"), "walnut", sides=16, caps=(False, True))      # swell knob
    parts += shaft(*lay["neck"], rad("neck"), "walnut", v0=3, caps=(False, False))
    parts += shaft(*lay["low"], rad("collar"), "brass", sides=16)                          # lower collar
    parts += shaft(lay["low"][0] + 0.3, lay["low"][0] + 0.7, rad("bead"), "bead", sides=16)
    parts += shaft(*lay["plaid"], rad("plaid"), "plaid", caps=(False, False))              # plaid grip
    parts += shaft(*lay["collar"], rad("collar"), "brass", sides=16)                       # upper collar
    parts += shaft(lay["collar"][0] + 0.5, lay["collar"][0] + 1.0, rad("bead"), "bead", sides=16)
    parts += shaft(*lay["wood"], rad("wood"), "hickory", caps=(False, False))              # bare hickory
    parts += shaft(*lay["guard"], rad("guard"), "leather")                                 # overstrike guard
    top = HY + EYE_H
    parts += shaft(top, top + 0.7, rad("wood"), "hickory", v0=24, caps=(False, True))     # handle end
    w = rad("wood") * 0.9
    parts.append(box((HX - w, top + 0.7, 7.8), (HX + w, top + 0.85, 8.2), "walnut"))      # kerf wedge
    # a ring of copper rivets through the overstrike guard
    yy = (lay["guard"][0] + lay["guard"][1]) / 2
    for sx, sz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        cx, cz = HX + sx * (rad("guard") + 0.05), 8.0 + sz * (rad("guard") + 0.05)
        hx, hz = (0.2, 0.38) if sx else (0.38, 0.2)
        parts.append(box((cx - hx, yy - 0.38, cz - hz), (cx + hx, yy + 0.38, cz + hz), "rivet"))
    # the brass maker's plate on the front of the handle
    z = 8 + rad("wood")
    parts.append(box((HX - 0.75, lay["plate"][0], z - 0.05), (HX + 0.75, lay["plate"][1], z + 0.25), "plate",
                     faces={"south": ("plate", [0, 0, 1.5, 4.0]), "north": ("plate", [1.5, 0, 0, 4.0])}))
    return parts


def models() -> dict:
    top = head()
    parts = top + handle(LAYOUT)
    shown = display(KIND, parts, grip=GRIP, size=0.84)
    # Third person: held like the vanilla axe (handle 10 degrees above level), main bit
    # facing the chop, so the head stays clear of the wearer's face.
    tilt = math.radians(10)
    shown["thirdperson_righthand"] = place({"y": (0, math.sin(tilt), math.cos(tilt)),
                                            "x": (0, math.cos(tilt), -math.sin(tilt))}, GRIP, "fist", 0.84 * 0.85)
    shown["firstperson_righthand"] = place({"y": (0.15, 0.85, -0.45), "x": (0.3, -0.3, 0.9)}, GRIP,
                                           (0.5, -0.6, -0.8), 0.52, pose=None)
    # The inventory icon: the same axe with a shorter handle, so the head fills the slot.
    short = _layout(short=True)
    icon = top + handle(short)
    return {"main": model(parts, shown), "gui": model(icon, display(KIND, icon, grip=short["grip"], size=0.84))}
