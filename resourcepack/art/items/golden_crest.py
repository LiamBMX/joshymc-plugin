"""Golden Crest: a regal harvest crown-helm (September Exclusive).

A gilded circlet with rope-twist rims and an engraved band set with garnets, gilded-bronze
corner pillars crowned with maple fleurons, crown points and a crimson velvet cap; a glowing
amber cabochon at the brow; a cloisonne maple leaf badge (crimson enamel on a gold backing)
above it; a tall plume of sculpted golden wheat ears and gilded maple leaves sweeping back
from the front; laurel-leaf cheek guards framing the face; and at the back a crimson bow
with swallowtail ribbons, pinned with an oak-and-gold acorn.

Built around the virtual head cube [1.6, 14.4]; face = -Z, up = +Y, +X = wearer's right.
All textures are 32 px (2 texels per model unit).
"""
import math

from art.kit import (bar, box, canvas, copy, display, fill, matrix_to_euler, mirror, model, move, ramp,
                     rgba, save, turn)

ID = "golden_crest"
NAME = "Golden Crest"
KIND = "helmet"

# --------------------------------------------------------------------------------------
# Palette: every material has its own hue-shifted ramp (dark -> light)
# --------------------------------------------------------------------------------------
GOLD = ramp("#e3a82b", 6, 0.8)      # gilded metal
GLINT = "#fff7da"
BRONZE = ramp("#a86a2c", 6)         # gilded bronze
AMBER = ramp("#ff9a1a", 6)          # the brow cabochon
CRIMSON = ramp("#b3202e", 5)        # maple enamel, ribbons, garnets
ORANGE = ramp("#d8621c", 5)         # burnt orange enamel
OAK = ramp("#7a4a28", 5)            # warm oak: the acorn, the leather lining
WHEAT = ramp("#e2b04a", 6)          # gilded wheat

SIDES = ("north", "south", "east", "west", "up", "down")


# --------------------------------------------------------------------------------------
# Painting helpers
# --------------------------------------------------------------------------------------

def put(img, x, y, colour):
    w, h = img.size
    if 0 <= x < w and 0 <= y < h:
        img.load()[x, y] = rgba(colour)


def rows_to(img, ox, oy, rows, pal):
    """Paint ASCII rows at (ox, oy); '.' is left untouched."""
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                put(img, ox + x, oy + y, pal[ch])


def padded_mask(rows, pad=1):
    h, w = len(rows) + 2 * pad, len(rows[0]) + 2 * pad
    m = [[False] * w for _ in range(h)]
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            m[y + pad][x + pad] = ch != "."
    return m


# --------------------------------------------------------------------------------------
# Sprites (hand-drawn silhouettes)
# --------------------------------------------------------------------------------------

MAPLE14 = [  # the badge's enamel; a 1 px gold border grows it to 16
    "......##......",
    "......##......",
    ".....####.....",
    "#....####....#",
    ".##.######.##.",
    "..##########..",
    "..##########..",
    "#..########..#",
    "##..######..##",
    ".############.",
    "..##########..",
    "...###..###...",
    "......##......",
    "......##......",
]
MAPLE12 = [
    ".....##.....",
    ".....##.....",
    ".#..####..#.",
    ".##.####.##.",
    "..########..",
    "#..######..#",
    "##.######.##",
    ".##########.",
    "..########..",
    "..###..###..",
    ".....##.....",
    ".....##.....",
]
MAPLE10 = [  # the corner fleurons (painted in a 12 px cell)
    "....##....",
    "...####...",
    "#..####..#",
    "##.####.##",
    ".########.",
    "..######..",
    "#.######.#",
    "##########",
    "..##..##..",
    "....##....",
]
GUARD = [  # 8 x 14 laurel leaf for the cheek guards, tip up; fold between columns 3 and 4
    "...#....",
    "...##...",
    "..####..",
    "..####..",
    ".######.",
    ".######.",
    "########",
    "########",
    "########",
    ".######.",
    ".######.",
    "..####..",
    "...##...",
    "...##...",
]
def paint_enamel_leaf(img, ox, oy, rows, heart, veins, hue="crimson"):
    """Enamel leaf body (crimson or orange at the lobes, amber at the heart) with lit and
    shaded edges, gold veins and a glossy glint. rows are drawn inside a 1 px margin."""
    m = padded_mask(rows)
    h, w = len(m), len(m[0])

    def ins(x, y):
        return 0 <= x < w and 0 <= y < h and m[y][x]

    lobe = CRIMSON if hue == "crimson" else ORANGE
    for y in range(h):
        for x in range(w):
            if not m[y][x]:
                continue
            d = math.hypot((x - heart[0]) / w, (y - heart[1]) / h)
            band = min(5, int(d * 9))
            body = [AMBER[3], ORANGE[3], ORANGE[2], lobe[3], lobe[2], lobe[1]][band]
            lit = [AMBER[4], ORANGE[4], ORANGE[3], lobe[3], lobe[3], lobe[2]][band]
            dark = [ORANGE[2], ORANGE[1], ORANGE[1], lobe[1], lobe[1], lobe[0]][band]
            c = body
            if not ins(x, y + 1) or (not ins(x + 1, y) and x >= w / 2):
                c = dark
            elif not ins(x, y - 1) or (not ins(x - 1, y) and x < w / 2):
                c = lit
            put(img, ox + x, oy + y, c)
    cx = w // 2
    for tx, ty in veins:
        tx, ty = tx + 1, ty + 1
        x0, y0 = (cx - 1 if tx < cx else cx), int(heart[1])
        n = max(abs(tx - x0), abs(ty - y0))
        for i in range(1, n):
            x, y = round(x0 + (tx - x0) * i / n), round(y0 + (ty - y0) * i / n)
            if all(ins(x + a, y + b) for a, b in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1))):
                put(img, ox + x, oy + y, GOLD[4] if x < cx else GOLD[3])
    for y in range(2, int(heart[1]) + 1):
        if ins(cx - 1, y) and ins(cx - 2, y) and ins(cx, y):
            put(img, ox + cx - 1, oy + y, GOLD[5])
    for gx, gy in ((cx - 3, 5), (cx - 4, 6)):
        if ins(gx, gy) and ins(gx, gy - 1):
            put(img, ox + gx, oy + gy, GLINT if gy == 5 else lobe[4])


def paint_gold_leaf(img, ox, oy, rows, heart, veins, dilate=True):
    """A gilded leaf: the silhouette (grown by 1 px when dilate) in polished gold, lit rim on
    the upper left, dark rim lower right, engraved veins."""
    base = padded_mask(rows)
    h, w = len(base), len(base[0])
    m = [[base[y][x] or (dilate and any(0 <= x + a < w and 0 <= y + b < h and base[y + b][x + a]
                                         for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))))
          for x in range(w)] for y in range(h)]

    def ins(x, y):
        return 0 <= x < w and 0 <= y < h and m[y][x]

    for y in range(h):
        for x in range(w):
            if not m[y][x]:
                continue
            d = math.hypot((x - heart[0]) / w, (y - heart[1]) / h)
            c = [GOLD[4], GOLD[4], GOLD[3], GOLD[3], GOLD[3], GOLD[2]][min(5, int(d * 7))]
            if not ins(x, y + 1) or (not ins(x + 1, y) and x >= w / 2):
                c = GOLD[1]
            elif not ins(x, y - 1) or (not ins(x - 1, y) and x < w / 2):
                c = GOLD[5]
            put(img, ox + x, oy + y, c)
    cx = w // 2
    for tx, ty in veins:
        tx, ty = tx + 1, ty + 1
        x0, y0 = (cx - 1 if tx < cx else cx), int(heart[1])
        n = max(abs(tx - x0), abs(ty - y0))
        for i in range(1, n):
            x, y = round(x0 + (tx - x0) * i / n), round(y0 + (ty - y0) * i / n)
            if all(ins(x + a, y + b) for a, b in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1))):
                put(img, ox + x, oy + y, GOLD[2])
                if ins(x, y + 1) and all(ins(x + a, y + 1 + b) for a, b in ((1, 0), (-1, 0), (0, 1))):
                    put(img, ox + x, oy + y + 1, GOLD[5] if x < cx else GOLD[4])
    for y in range(2, int(heart[1]) + 1):
        if ins(cx - 1, y) and ins(cx - 2, y) and ins(cx, y):
            put(img, ox + cx - 1, oy + y, GOLD[2])
            put(img, ox + cx, oy + y, GOLD[4])
    put(img, ox + cx - 3, oy + 5, GLINT)


# --------------------------------------------------------------------------------------
# Textures (all 32 px)
# --------------------------------------------------------------------------------------

def paint_band():
    """Circlet field (rows 0-5 = 3 units): an engraved running scroll with inset garnets.
    Rows 8-15: plain gold for small end faces. Rows 16-31: oak leather lining."""
    img = canvas(32)
    for y, c in enumerate([GOLD[1], GOLD[4], GOLD[3], GOLD[3], GOLD[3], GOLD[2]]):
        fill(img, (0, y, 31, y), c)
    for x in range(32):  # engraved groove whose lower lip catches the light
        yi = max(2, min(4, int(round(3.0 + 1.1 * math.sin((x + 1) * 2 * math.pi / 8)))))
        put(img, x, yi, GOLD[2])
        if yi + 1 <= 4:
            put(img, x, yi + 1, GOLD[4])
    for k in range(4):  # inset garnets at the scroll's crossings
        x = k * 8 + 3
        put(img, x, 2, CRIMSON[3])
        put(img, x + 1, 2, CRIMSON[2])
        put(img, x, 3, CRIMSON[2])
        put(img, x + 1, 3, CRIMSON[1])
        put(img, x - 1, 3, GOLD[5])
    for y in range(8, 16):
        fill(img, (0, y, 31, y), GOLD[3] if y % 3 else GOLD[4])
    fill(img, (0, 16, 31, 31), OAK[1])
    for y in range(16, 32, 2):
        for x in range(y % 4, 32, 4):
            put(img, x, y, OAK[2])
    return img


def paint_rim():
    """Rope-twist moulding (rows 0-1 = 1 unit) and a polished rim top (rows 8-15)."""
    img = canvas(32)
    for x in range(32):
        k = x % 4
        put(img, x, 0, [GOLD[5], GOLD[4], GOLD[3], GOLD[2]][k])
        put(img, x, 1, [GOLD[3], GOLD[2], GOLD[4], GOLD[4]][k])
        put(img, x, 2, [GOLD[2], GOLD[1], GOLD[2], GOLD[3]][k])
    fill(img, (0, 8, 31, 15), GOLD[3])
    fill(img, (0, 8, 31, 8), GOLD[5])
    fill(img, (0, 9, 31, 9), GOLD[4])
    for x in range(0, 32, 6):
        put(img, x + 2, 9, GLINT)
    return img


def paint_metal():
    """Polished gold atlas: plate (0-15, 0-15), tine (20-23, 0-7), gilded-bronze pillar
    (24-27, 0-11), acorn (28-31, 0-5), garnet (16-19, 16-19), jewel setting (0-13, 16-27)."""
    img = canvas(32)
    for y in range(16):
        fill(img, (0, y, 15, y), GOLD[4] if y < 2 else (GOLD[3] if y < 11 else GOLD[2]))
    for y, x0, x1 in ((3, 1, 6), (5, 8, 13), (7, 2, 5), (9, 9, 14), (12, 3, 8)):
        fill(img, (x0, y, x1, y), GOLD[4])
    for y, x0, x1 in ((4, 10, 12), (8, 5, 7), (13, 10, 13)):
        fill(img, (x0, y, x1, y), GOLD[2])
    put(img, 1, 1, GLINT)
    put(img, 2, 1, GOLD[5])
    for y in range(8):
        for i, c in enumerate([GOLD[5], GOLD[4], GOLD[3], GOLD[2]]):
            put(img, 20 + i, y, c)
    for y in range(12):  # gilded-bronze pillar with gold ring bands
        for i, c in enumerate([BRONZE[5], BRONZE[4], BRONZE[3], BRONZE[2]]):
            put(img, 24 + i, y, c)
    for y in (0, 3, 7, 10):
        for i, c in enumerate([GOLD[5], GOLD[4], GOLD[3], GOLD[2]]):
            put(img, 24 + i, y, c)
        fill(img, (24, y + 1, 27, y + 1), BRONZE[1])
    rows_to(img, 28, 0, ["gGgd", "GgdD", "oOoq", "OooQ", "ooqQ", "oqqQ"],  # acorn: gold cap, oak nut
            {"g": GOLD[4], "G": GOLD[5], "d": GOLD[2], "D": GOLD[1], "o": OAK[3], "O": OAK[4],
             "q": OAK[2], "Q": OAK[1]})
    rows_to(img, 16, 16, ["hbcc", "bccd", "ccdd", "cddd"],
            {"h": "#ffd0d0", "b": CRIMSON[4], "c": CRIMSON[2], "d": CRIMSON[1]})
    fill(img, (0, 16, 13, 27), GOLD[3])
    for i in range(12):
        put(img, i, 16, GOLD[5])
        put(img, 0, 16 + i, GOLD[4])
        put(img, i + 1, 27, GOLD[1])
        put(img, 13, 16 + i, GOLD[2])
    for x in range(1, 13, 2):
        put(img, x, 17, GOLD[4])
        put(img, x, 26, GOLD[2])
    return img


def paint_amber():
    """The brow cabochon, projected: 10 x 9 texels at (0, 0); dark edge strip (12-15);
    the polished dome, 4 x 4 at (16, 0)."""
    img = canvas(32)
    pal = {"a": AMBER[0], "b": AMBER[1], "c": AMBER[2], "d": AMBER[3], "e": AMBER[4],
           "h": AMBER[5], "w": GLINT}
    rows_to(img, 0, 0, [
        "..aabbaa..",
        ".abhhccba.",
        "abhwhcccda",
        "abhcccccda",
        "abcccccdda",
        "abccccddea",
        ".bcccddeea",
        "..bddeeea.",
        "...aaaaa..",
    ], pal)
    for x, y in ((5, 4), (4, 5), (6, 5), (5, 5)):  # a tiny fossil leaf caught in the amber
        put(img, x, y, AMBER[1])
    fill(img, (12, 0, 15, 15), AMBER[1])
    fill(img, (12, 0, 12, 15), AMBER[2])
    rows_to(img, 16, 0, ["hwhd", "whdd", "hdde", "ddee"], pal)
    return img


def paint_leaves():
    """Maple leaves: badge enamel (0,0; 16x16), its gold backing (16,0; 16x16), a gilded
    maple (0,16; 14x14) and an orange-enamel maple (14,16; 14x14)."""
    img = canvas(32)
    veins14 = [(0, 3), (13, 3), (0, 7), (13, 7)]
    paint_enamel_leaf(img, 0, 0, MAPLE14, (7.5, 10.5), veins14)
    paint_gold_leaf(img, 16, 0, MAPLE14, (7.5, 10.5), [], dilate=True)
    veins12 = [(1, 2), (10, 2), (0, 5), (11, 5)]
    paint_gold_leaf(img, 0, 16, MAPLE12, (6.5, 9.0), veins12, dilate=False)
    paint_enamel_leaf(img, 14, 16, MAPLE12, (6.5, 9.0), veins12, hue="orange")
    return img


def paint_guard_leaf(img, ox, oy):
    """The cheek guard: a polished gilded laurel leaf, lit half and shaded half either side of
    a bright midrib ridge, engraved veins running toward the tip, one specular glint."""
    m = [[ch != "." for ch in row] for row in GUARD]
    h, w = len(m), len(m[0])

    def ins(x, y):
        return 0 <= x < w and 0 <= y < h and m[y][x]

    for y in range(h):
        for x in range(w):
            if not m[y][x]:
                continue
            edge = not (ins(x - 1, y) and ins(x + 1, y) and ins(x, y - 1) and ins(x, y + 1))
            if x < w // 2:
                c = GOLD[5] if edge else GOLD[4]
            else:
                c = GOLD[1] if edge else GOLD[3]
            put(img, ox + x, oy + y, c)
    for y in range(1, 12):
        put(img, ox + 3, oy + y, GOLD[5])
        put(img, ox + 4, oy + y, GOLD[2])
    for y0 in (5, 8, 11):
        for x, y, c in ((2, y0, GOLD[3]), (1, y0 - 1, GOLD[3]), (5, y0, GOLD[2]), (6, y0 - 1, GOLD[1])):
            if ins(x, y) and ins(x - 1, y) and ins(x + 1, y):
                put(img, ox + x, oy + y, c)
    put(img, ox + 2, oy + 6, GLINT)
    for y in (12, 13):
        put(img, ox + 3, oy + y, GOLD[4])
        put(img, ox + 4, oy + y, GOLD[2])


def paint_foliage():
    """The cheek guard leaf (16,0; 8x14) and the gilded maple fleuron (0,16; 12x12)."""
    img = canvas(32)
    paint_guard_leaf(img, 16, 0)
    paint_gold_leaf(img, 0, 16, MAPLE10, (5.5, 8.0), [(1, 2), (8, 2), (0, 5), (9, 5)], dilate=False)
    return img


def paint_wheat():
    """Gilded wheat: kernel blocks (0-2 lit, 3-5 shaded), the dark lattice core (6-9),
    pale awns (10-11), stalk straw (20-23) and the sheaf cuff with its ribbon (24-31)."""
    img = canvas(32)
    k = {"H": "#fff3c8", "h": WHEAT[5], "m": WHEAT[4], "d": WHEAT[3], "o": WHEAT[2], "x": WHEAT[1]}
    rows_to(img, 0, 0, ["Hhm", "hmd", "mdo", "dox"], k)
    rows_to(img, 3, 0, ["hmd", "mdo", "dox", "oxx"], k)
    for y in range(16):
        for x in range(6, 10):
            put(img, x, y, WHEAT[2] if (x + y) % 2 else WHEAT[1])
    for y in range(16):
        put(img, 10, y, WHEAT[5] if y % 5 else WHEAT[4])
        put(img, 11, y, WHEAT[3])
    for y in range(32):
        for i, c in enumerate([WHEAT[4], WHEAT[3], WHEAT[2], WHEAT[1]]):
            put(img, 20 + i, y, c)
        if y % 6 == 0:
            fill(img, (20, y, 23, y), WHEAT[1])
    fill(img, (24, 0, 31, 15), GOLD[3])
    fill(img, (24, 0, 31, 0), GOLD[5])
    fill(img, (24, 3, 31, 3), GOLD[1])
    fill(img, (24, 4, 31, 5), CRIMSON[2])
    fill(img, (24, 4, 31, 4), CRIMSON[3])
    return img


def paint_ribbon():
    """Crimson velvet ribbon, 5 texels wide with gold edges (0-4); the tail end, the same with
    a swallowtail notch cut into its last rows (8-12, rows 0-7); the gathered knot (16-23)."""
    img = canvas(32)
    for ox in (0, 8):
        for y in range(32):
            for i, c in enumerate([GOLD[4], CRIMSON[3], CRIMSON[2], CRIMSON[1], GOLD[2]]):
                put(img, ox + i, y, c)
            if y % 7 == 3:
                put(img, ox + 2, y, CRIMSON[3])
                put(img, ox + 3, y, CRIMSON[2])
    for y, xs in ((5, (2,)), (6, (1, 2, 3)), (7, (1, 2, 3))):
        for x in xs:
            put(img, 8 + x, y, (0, 0, 0, 0))
    fill(img, (16, 0, 23, 7), CRIMSON[2])
    for x in range(16, 24, 2):
        put(img, x, 1, CRIMSON[3])
        put(img, x + 1, 5, CRIMSON[1])
    fill(img, (16, 0, 23, 0), CRIMSON[4])
    return img


def paint_velvet():
    """Crimson velvet seen from above: pleats radiating from the button, each lit on one side
    and shaded on the other, darkening toward the rim. Rows 28-31 hold the side strip."""
    img = canvas(32)
    for y in range(28):
        for x in range(32):
            dx, dy = x - 15.5, y - 15.5
            d = math.hypot(dx, dy)
            phase = (math.atan2(dy, dx) / (2 * math.pi) * 10) % 1.0
            c = CRIMSON[3] if phase < 0.3 else (CRIMSON[2] if phase < 0.65 else CRIMSON[1])
            if d > 11.5:
                c = CRIMSON[2] if phase < 0.3 else CRIMSON[1]
            if d < 3.2:
                c = CRIMSON[3]
            put(img, x, y, c)
    for x, y in ((13, 12), (12, 13), (19, 8), (8, 18), (22, 15)):
        put(img, x, y, CRIMSON[4])
    for x in range(32):
        for y in range(28, 32):
            put(img, x, y, CRIMSON[2] if x % 3 else CRIMSON[1])
        put(img, x, 28, CRIMSON[3] if x % 3 else CRIMSON[2])
    return img


def textures() -> None:
    save(paint_band(), "band")
    save(paint_rim(), "rim")
    save(paint_metal(), "metal")
    save(paint_amber(), "amber")
    save(paint_leaves(), "leaves")
    save(paint_foliage(), "foliage")
    save(paint_wheat(), "wheat")
    save(paint_ribbon(), "ribbon")
    save(paint_velvet(), "velvet")


# --------------------------------------------------------------------------------------
# Geometry helpers
# --------------------------------------------------------------------------------------

def _size(side, frm, to):
    dx, dy, dz = (to[i] - frm[i] for i in range(3))
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
            "up": (dx, dz), "down": (dx, dz)}[side]


def tbox(frm, to, tex, at=(0, 0), sides=None, skip=(), **kw):
    """box() where every face starts at its own UV offset (texel-true); `sides` maps a side
    to (u, v) or (texture, u, v)."""
    frm, to = list(frm), list(to)
    for i in range(3):
        if frm[i] > to[i]:
            frm[i], to[i] = to[i], frm[i]
    faces = {}
    for side in SIDES:
        if side in skip:
            continue
        spec = (sides or {}).get(side, at)
        name, (u, v) = (spec[0], spec[1:]) if len(spec) == 3 else (tex, spec)
        w, h = _size(side, frm, to)
        faces[side] = (name, [u, v, min(16.0, u + w), min(16.0, v + h)])
    return box(frm, to, tex, faces=faces, skip=skip, **kw)


def front_box(frm, to, tex, ref, edge, **kw):
    """A box whose north face is cut from one picture: ref = (x_ref, y_ref, u0, v0) maps world
    (x, y) to texture (u0 + x_ref - x, v0 + y_ref - y). The other faces sample `edge`."""
    xr, yr, u0, v0 = ref
    faces = {"north": (tex, [u0 + xr - to[0], v0 + yr - to[1], u0 + xr - frm[0], v0 + yr - frm[1]])}
    for side in ("south", "east", "west", "up", "down"):
        w, h = _size(side, frm, to)
        faces[side] = (tex, [edge[0], edge[1], min(16.0, edge[0] + w), min(16.0, edge[1] + h)])
    return box(frm, to, tex, faces=faces, **kw)


def folded(uv, w, h, tex, fold, **kw):
    """A sprite creased along its vertical midline, the halves swept back by `fold` degrees
    in total: a convex, sculpted leaf."""
    u0, v0, u1, v1 = uv
    um = (u0 + u1) / 2
    skip = ("east", "west", "up", "down")
    left = box((0, 0, 0), (w / 2, h, 0), tex,
               faces={"north": (tex, [u0, v0, um, v1]), "south": (tex, [um, v0, u0, v1])}, skip=skip, **kw)
    right = box((-w / 2, 0, 0), (0, h, 0), tex,
                faces={"north": (tex, [um, v0, u1, v1]), "south": (tex, [u1, v0, um, v1])}, skip=skip, **kw)
    turn(left, -fold / 2, "y", (0, 0, 0))
    turn(right, fold / 2, "y", (0, 0, 0))
    return [left, right]


def _norm(v):
    n = math.sqrt(sum(c * c for c in v))
    return [c / n for c in v]


def _cross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def orient(parts, up, front, pos):
    """Turn a local assembly (up = +Y, painted front = -Z, base at the origin) so that its up
    runs along `up` and its front faces `front`, then move its base to `pos`."""
    y = _norm(up)
    z = [-c for c in _norm(front)]
    d = sum(z[i] * y[i] for i in range(3))
    z = _norm([z[i] - d * y[i] for i in range(3)])
    x = _cross(y, z)
    m = ((x[0], y[0], z[0]), (x[1], y[1], z[1]), (x[2], y[2], z[2]))
    ex, ey, ez = matrix_to_euler(m)
    turn(parts, x=ex, y=ey, z=ez, origin=(0, 0, 0))
    move(parts, *pos)
    return parts


def bez(p0, p1, p2, t):
    return [(1 - t) ** 2 * p0[i] + 2 * (1 - t) * t * p1[i] + t ** 2 * p2[i] for i in range(3)]


# --------------------------------------------------------------------------------------
# Model parts
# --------------------------------------------------------------------------------------

FIELD = (9.6, 12.6)                     # engraved band
RIM_LO, RIM_HI = (8.6, 9.6), (12.6, 13.6)


def ring(y0, y1, out, inn, tex, at, top=None, bottom=None, skip_ud=False):
    """A square ring of four pieces hugging the head: outer faces from `at`, inner faces lined."""
    lining = ("band", 0, 8)
    parts = []
    specs = [  # (from, to, outer side, inner side, end sides)
        ((out, y0, out), (16 - out, y1, inn), "north", "south", ("east", "west")),
        ((out, y0, 16 - inn), (16 - out, y1, 16 - out), "south", "north", ("east", "west")),
        ((out, y0, inn), (inn, y1, 16 - inn), "west", "east", ("north", "south")),
        ((16 - inn, y0, inn), (16 - out, y1, 16 - inn), "east", "west", ("north", "south")),
    ]
    for frm, to, o, i, ends in specs:
        sides = {o: at, i: lining}
        for e in ends:
            sides[e] = at
        if top:
            sides["up"] = top
        if bottom:
            sides["down"] = bottom
        skip = ["up", "down"] if skip_ud else []
        if o in ("west", "east"):
            skip += list(ends)  # hidden by the front and back pieces
        parts.append(tbox(frm, to, tex, sides=sides, skip=tuple(skip)))
    return parts


def circlet():
    parts = []
    parts += ring(*FIELD, 0.4, 1.2, "band", (0, 0), skip_ud=True)
    parts += ring(*RIM_LO, 0.2, 1.2, "rim", (0, 0), top=(0, 4), bottom=(0, 4))
    parts += ring(*RIM_HI, 0.2, 1.2, "rim", (0, 0), top=(0, 4), bottom=(0, 4))
    for cx, cz in ((0.45, 0.45), (15.55, 0.45), (0.45, 15.55), (15.55, 15.55)):
        post = tbox((cx - 0.9, 8.2, cz - 0.9), (cx + 0.9, 14.0, cz + 0.9), "metal", at=(12, 0),
                    sides={"up": (0, 0), "down": (0, 0)})
        turn(post, 45, "y", (cx, 11, cz))
        cap = tbox((cx - 0.6, 13.9, cz - 0.6), (cx + 0.6, 14.8, cz + 0.6), "metal", at=(0, 0))
        turn(cap, 45, "y", (cx, 14.35, cz))
        out = (-1 if cx < 8 else 1, 0, -1 if cz < 8 else 1)
        fleuron = orient(folded((0, 8, 6, 14), 6.0, 6.0, "foliage", 40), (0, 1, 0), out, (cx, 14.3, cz))
        parts += [post, cap] + fleuron
    return parts


def cap():
    """A crimson velvet cap filling the circlet, domed, with a small gold button. The tops are
    projected from one picture of the pleats (about 2 texels per unit, like everything else)."""
    parts = []
    for (x0, y0, x1, y1) in ((1.25, 13.6, 14.75, 14.9), (3.6, 14.9, 12.4, 15.7)):
        side = ("velvet", [0, 14, x1 - x0, 14 + (y1 - y0)])
        parts.append(box((x0, y0, x0), (x1, y1, x1), "velvet", skip=("down",),
                         faces={"up": ("velvet", [x0, x0, x1, x1]), "north": side, "south": side,
                                "east": side, "west": side}))
    parts.append(tbox((7.4, 15.7, 7.4), (8.6, 16.3, 8.6), "metal", at=(0, 0), skip=("down",)))
    return parts


def tine():
    """A crown point in the local frame (base at the origin, facing -Z)."""
    stem = tbox((-0.7, 0, -0.3), (0.7, 2.4, 0.3), "metal", at=(10, 0))
    point = tbox((-0.5, -0.5, -0.3), (0.5, 0.5, 0.3), "metal", at=(10, 0))
    turn(point, 45, "z", (0, 0, 0))
    move(point, 0, 2.4, 0)
    gem = tbox((-0.35, 0.6, -0.55), (0.35, 1.3, -0.25), "metal", at=(8, 8))
    return [stem, point, gem]


def crown_points():
    parts = []
    for pos, facing in (((0.2, 13.5, 8.0), (-1, 0, 0)), ((15.8, 13.5, 8.0), (1, 0, 0)),
                        ((8.0, 13.5, 15.8), (0, 0, 1))):
        parts += orient(tine(), (0, 1, 0), facing, pos)
    return parts


def brow_jewel():
    """The amber cabochon in a stepped gold setting on the band at the brow."""
    parts = []
    ref = (11.0, 14.0, 0, 8)
    for frm, to in (((5.0, 9.9, -0.25), (11.0, 12.3, 0.4)),
                    ((6.2, 8.3, -0.25), (9.8, 13.9, 0.4)),
                    ((5.5, 9.2, -0.3), (10.5, 13.0, 0.4))):
        parts.append(front_box(frm, to, "metal", ref, edge=(0, 0)))
    ref = (10.5, 13.3, 0, 0)
    for frm, to in (((6.1, 9.5, -0.75), (9.9, 12.7, -0.2)),
                    ((6.8, 8.9, -0.6), (9.2, 13.3, -0.2)),
                    ((5.5, 10.2, -0.6), (10.5, 12.0, -0.2))):
        parts.append(front_box(frm, to, "amber", ref, edge=(6, 0), glow=13))
    parts.append(front_box((7.0, 10.3, -1.05), (9.0, 12.3, -0.7), "amber", (9.0, 12.3, 8, 0),
                           edge=(6, 0), glow=14))
    for x, y in ((5.7, 12.4), (9.7, 12.4), (5.7, 9.1), (9.7, 9.1)):
        parts.append(tbox((x, y, -0.55), (x + 0.6, y + 0.6, 0.0), "metal", at=(0, 0)))
    return parts


def maple_badge():
    """Cloisonne maple leaf: crimson enamel over a gold backing one texel larger all round."""
    enamel = folded((0, 0, 8, 8), 8.0, 8.0, "leaves", 36, glow=5)
    backing = folded((8, 0, 16, 8), 8.0, 8.0, "leaves", 36)
    move(backing, 0, 0, 0.22)
    leaf = enamel + backing
    turn(leaf, 5, "x", (0, 0, 0))
    return move(leaf, 8.0, 12.6, -0.1)


# The plume's ears: (fan angle seen from the front, lean back from vertical, straight rise of
# the stalk above the cuff, length of its bend, turn of the ear's broad side) in degrees and
# model units.
EARS = [(0, 15, 2.6, 2.4, 0), (22, 20, 2.0, 2.4, 25), (-22, 20, 2.0, 2.4, -25), (44, 26, 1.4, 2.2, 50),
        (-44, 26, 1.4, 2.2, -50), (12, 58, 1.8, 2.5, 90), (-12, 58, 1.8, 2.5, -90)]


def wheat_ear(d, base, spin=0.0, awns=True):
    """A sculpted gilded wheat ear along direction d: a diamond-section core, three tiers of
    plump kernels tilted outward, a tip kernel and three splayed awns (left off the
    inventory icon, where they would only speckle)."""
    parts = []
    core = tbox((-0.5, 0.0, -0.5), (0.5, 5.0, 0.5), "wheat", at=(3, 0))
    parts.append(turn(core, 45, "y", (0, 0, 0)))
    for y in (0.5, 1.8, 3.1):
        for side in (-1, 1):
            kern = tbox((-0.55, 0, -0.5), (0.55, 1.5, 0.5), "wheat", at=(0, 0) if side < 0 else (1.5, 0))
            turn(kern, -20 * side, "z", (0, 0, 0))
            parts.append(move(kern, 0.5 * side, y + (0.45 if side > 0 else 0.0), 0))
    parts.append(tbox((-0.45, 4.3, -0.45), (0.45, 5.6, 0.45), "wheat", at=(0, 0)))
    for ang in ((-13, 0, 13) if awns else ()):
        a = math.radians(ang)
        parts.append(bar((0, 5.2, 0), (3.3 * math.sin(a), 5.2 + 3.3 * math.cos(a), 0), 0.3, 0.3,
                         "wheat", offset=(5, 0)))
    a = math.radians(spin)
    return orient(parts, d, (math.sin(a), 0, -math.cos(a)), base)


def plume(awns=True):
    """A sheaf of wheat bound in a gold cuff behind the badge: the stalks rise straight, then
    bend into a fan of ears leaning back like a plume. Gilded maple leaves flank the badge."""
    parts = []
    for phi, lean, rise, bend, spin in EARS:
        f, t = math.radians(phi), math.radians(lean)
        d = _norm([math.sin(f), math.cos(f), math.tan(t)])
        p0 = [8 + 0.35 * math.sin(f), 14.4, 0.6]
        p1 = [p0[0], p0[1] + rise, p0[2]]
        p2 = [p1[i] + bend * d[i] for i in range(3)]
        pts = [bez(p0, p1, p2, s) for s in (0, 0.5, 1.0)]
        for s in range(2):
            parts.append(bar(pts[s], pts[s + 1], 0.5, 0.5, "wheat", offset=(10, 0)))
        parts += wheat_ear(d, [p2[i] - 0.3 * d[i] for i in range(3)], spin, awns)
    parts.append(tbox((7.0, 13.4, 0.1), (9.0, 15.0, 1.1), "wheat", at=(12, 0)))
    for side in (-1, 1):
        leaf = folded((0, 8, 7, 15), 7.0, 7.0, "leaves", 36)
        orient(leaf, (1.15 * side, 1, 0.1), (0.45 * side, 0, -1), (8 + 3.4 * side, 13.55, 0.55))
        parts += leaf
        ember = folded((7, 8, 14, 15), 7.0, 7.0, "leaves", 36)
        orient(ember, (0.45 * side, 1, 0.9), (1 * side, 0.25, -0.35), (8 + 0.6 * side, 16.6, 1.6))
        parts += ember
    return parts


def cheek_guard():
    """The wearer's right cheek guard (mirrored for the left): one large gilded laurel leaf,
    creased along its midrib and pinned by a garnet rivet under the corner pillar. Facing
    diagonally out, it wraps the front corner of the head, framing the face without covering it."""
    parts = []
    leaf = folded((8, 0, 12, 7), 4.0, 7.0, "foliage", 64)
    parts += orient(leaf, (-0.1, -1, -0.12), (0.8, 0, -0.6), (15.35, 8.7, 1.35))
    collar = tbox((-0.7, -0.7, -0.25), (0.7, 0.7, 0.25), "metal", at=(0, 0))
    rivet = tbox((-0.45, -0.45, -0.5), (0.45, 0.45, -0.2), "metal", at=(8, 8))  # a garnet
    turn([collar, rivet], 45, "z", (0, 0, 0))
    parts += orient([collar, rivet], (0, 1, 0), (0.8, 0, -0.6), (15.5, 7.75, 1.05))
    return parts


def ribbons():
    """A crimson bow at the back of the circlet: two loops, an acorn brooch through the knot,
    and two swallowtail tails that twist as they fall."""
    parts = [tbox((7.1, 10.1, 15.6), (8.9, 12.1, 16.5), "ribbon", at=(8, 0))]
    # an acorn brooch pinned through the knot: oak nut in a gilded cap
    parts.append(tbox((7.35, 10.2, 16.45), (8.65, 11.5, 17.15), "metal", at=(14, 1)))
    parts.append(tbox((7.2, 11.4, 16.4), (8.8, 12.0, 17.3), "metal", at=(14, 0)))
    parts.append(tbox((7.85, 12.0, 16.75), (8.15, 12.5, 17.05), "metal", at=(15, 0)))
    for side in (-1, 1):
        loop = tbox((0.0, -1.0, -0.3), (2.6, 1.0, 0.3), "ribbon", at=(8, 0))
        turn(loop, 18, "z", (0, 0, 0))
        if side < 0:
            loop = mirror(loop, "x", 0)[0]
        parts.append(move(loop, 8 + 0.8 * side, 11.2, 16.1))
        a = (8 + 0.5 * side, 10.4, 16.3)
        b = (8 + 1.4 * side, 6.6, 16.8)
        c = (8 + 2.0 * side, 3.0, 16.5)
        parts.append(bar(b, a, 2.5, 0.3, "ribbon", offset=(0, 0), roll=-12 * side))
        parts.append(bar(c, b, 2.5, 0.3, "ribbon", offset=(4, 0), roll=28 * side, skip=("down",)))
    return parts


def models() -> dict:
    crown = circlet() + cap() + crown_points() + brow_jewel() + maple_badge()
    guard = cheek_guard()
    parts = crown + plume() + guard + mirror(guard, "x", 8) + ribbons()
    # The inventory icon: the crown and its plume without the hanging guards, ribbons and
    # awns, turned to show the jewel and the badge.
    icon = copy(crown) + plume(awns=False)
    return {"main": model(parts, display("helmet", parts, gui_rotation=(8, 165, 0))),
            "gui": model(icon, display("helmet", icon, gui_rotation=(8, 165, 0), gui_span=15.5))}
