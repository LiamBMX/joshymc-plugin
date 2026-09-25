"""Orchard Pickaxe (September Exclusive).

A twisted apple-wood branch handle with a spiral leather wrap, a gilded leaf at its
collar and a golden apple hanging from its end. The gilded head cradles a glossy red
apple in its eye, crowned by a curled leaf and a sprig of apple blossom; the two picks
sweep out like gilded branches, ruby-collared, to polished gold points. A faint golden
glow rims the apple. The inventory icon is the same model with calmer textures.
"""
import math
import random

from art.kit import (FIRST_PERSON_HAND, art, bar, box, canvas, display, display_matrix, fill, grain, mirror, mix,
                     model, move, place, prism, ramp, rgba, save, turn)

ID = "orchard_pickaxe"
NAME = "Orchard Pickaxe"
KIND = "pickaxe"

GOLD = ramp("#e8ac2c", 6, 0.72, 0.12)      # harvest gold, deep bronze shadows -> pale highlight
BRONZE = ramp("#b0702a", 6, 0.66, 0.12)    # gilded bronze recesses
APPLE = ramp("#c8102a", 6, 0.66, 0.12)     # crimson apple skin
WOOD = ramp("#9a5430", 6, 0.64, 0.12)      # warm apple wood
LEATHER = ramp("#7a3a1e", 6, 0.62, 0.12)   # burnt-orange saddle leather
LEAF = ramp("#6a8e28", 5, 0.62, 0.14)      # olive apple leaf
PETAL = ramp("#f2a6bc", 5, 0.62, 0.08)     # blossom pink
GLINT = "#fff2c2"
SPARK = "#fffcee"
AMBER = "#e0901e"
RUST = "#b4501a"

APPLE_C = (8.0, 19.6, 8.0)     # centre of the apple set in the head's eye
GAPPLE_C = (8.0, -11.2, 8.0)   # centre of the golden apple hanging off the handle end
GRIP = (8.0, -3.4, 8.0)

_LIGHT = (-0.45, -0.62, 0.64)  # texture-space light: from the upper left, toward the viewer


# ------------------------------------------------------------------------------------
# Textures
# ------------------------------------------------------------------------------------

def _norm(v):
    n = math.sqrt(sum(c * c for c in v))
    return tuple(c / n for c in v)


def _sphere(img, cx, cy, r, tones, cuts, spec=None, rim=None, hl_cut=(0.975, 0.993), amb=0.12):
    """Clean banded sphere shading (pixel-art style: hard bands, checkered only on the
    band edges). tones dark->light, cuts are the lit thresholds between them."""
    px = img.load()
    w, h = img.size
    lx, ly, lz = _norm(_LIGHT)
    hx, hy, hz = _norm((lx, ly, lz + 1.0))
    for y in range(h):
        for x in range(w):
            nx = (x + 0.5 - cx) / r
            ny = (y + 0.5 - cy) / r
            d2 = nx * nx + ny * ny
            if d2 > 1.0:
                k = math.sqrt(d2)
                nx, ny, nz = nx / k * 0.98, ny / k * 0.98, 0.2
            else:
                nz = math.sqrt(1.0 - d2)
            lit = amb + (1 - amb) * max(0.0, nx * lx + ny * ly + nz * lz)
            band = sum(1 for c in cuts if lit > c)
            for c in cuts:  # checker the pixels that sit right on a band edge
                if 0 < c - lit < 0.03 and (x + y) % 2 == 0:
                    band = min(band + 1, len(tones) - 1)
            col = tones[band]
            if rim and ny > 0.42 and d2 > 0.5:
                t = (ny - 0.42) * 1.9 + (d2 - 0.5) * 1.3
                if t > 1.05:
                    col = rim[1]
                elif t > 0.62:
                    col = rim[0]
            if spec and d2 <= 1.0:
                s = nx * hx + ny * hy + nz * hz
                if s > hl_cut[1]:
                    col = spec[1]
                elif s > hl_cut[0]:
                    col = spec[0]
            px[x, y] = rgba(col)


def paint_apple():
    tones = [APPLE[0], APPLE[1], APPLE[2], APPLE[3], APPLE[4], APPLE[5]]
    img = canvas(32)
    _sphere(img, 16, 16, 8.8, tones, (0.3, 0.45, 0.62, 0.84, 0.95), spec=("#ffc6b6", "#fff4ee"),
            rim=(mix(APPLE[3], GOLD[3], 0.5), GOLD[4]), hl_cut=(0.972, 0.992), amb=0.26)
    px = img.load()
    rng = random.Random(9)
    # skin streaks: short vertical runs of the next darker band on the lit side
    for _ in range(34):
        x, y = rng.randrange(6, 26), rng.randrange(5, 22)
        run = rng.randint(2, 4)
        for i in range(run):
            if y + i < 32 and px[x, y + i][:3] == rgba(APPLE[4])[:3]:
                px[x, y + i] = rgba(APPLE[3])
            elif y + i < 32 and px[x, y + i][:3] == rgba(APPLE[3])[:3] and rng.random() < 0.6:
                px[x, y + i] = rgba(APPLE[2])
    # lenticels: a few pale freckles
    for _ in range(14):
        x, y = rng.randrange(7, 25), rng.randrange(6, 22)
        if px[x, y][:3] in (rgba(APPLE[3])[:3], rgba(APPLE[2])[:3]):
            px[x, y] = rgba("#f07a66")
    save(img, "apple")

    side = canvas(32)
    _sphere(side, 16, 16, 8.8, tones, (0.3, 0.45, 0.62, 0.84, 0.95), rim=(mix(APPLE[3], GOLD[3], 0.5), GOLD[4]),
            amb=0.26)
    spx = side.load()
    rng = random.Random(10)
    for _ in range(30):
        x, y = rng.randrange(6, 26), rng.randrange(5, 22)
        for i in range(rng.randint(2, 4)):
            if y + i < 32 and spx[x, y + i][:3] == rgba(APPLE[4])[:3]:
                spx[x, y + i] = rgba(APPLE[3])
    save(side, "apple_side")

    top = canvas(32)
    px = top.load()
    for y in range(32):
        for x in range(32):
            dx, dy = x + 0.5 - 16, y + 0.5 - 16
            d = math.sqrt(dx * dx + dy * dy) / 8.8
            lit = -(dx * 0.45 + dy * 0.62) / 8.8  # the shoulder on the upper left catches the sun
            if d < 0.2:
                c = mix(APPLE[0], GOLD[1], 0.5) if d > 0.1 else GOLD[1]
            elif d < 0.3:
                c = APPLE[1]
            elif d < 0.44:
                c = APPLE[2] if lit < 0.1 else APPLE[3]
            elif d < 0.72:
                c = APPLE[4] if lit > -0.25 else APPLE[3]
            elif d < 0.88:
                c = APPLE[3] if lit > -0.35 else APPLE[2]
            else:
                c = APPLE[2] if lit > -0.2 else APPLE[1]
            px[x, y] = rgba(c)
    rng = random.Random(21)
    for _ in range(22):  # radial streaks from the stem well
        a = rng.random() * math.tau
        r0 = rng.uniform(3.2, 4.2)
        for step in range(int(r0 * 2), int(r0 * 2) + rng.randint(2, 4)):
            x = int(16 + math.cos(a) * step * 0.95)
            y = int(16 + math.sin(a) * step * 0.95)
            if 0 <= x < 32 and 0 <= y < 32:
                px[x, y] = rgba(APPLE[2] if px[x, y][:3] == rgba(APPLE[4])[:3] else APPLE[3])
    for x, y in ((11, 10), (12, 10), (11, 11)):  # glossy highlight on the shoulder
        px[x, y] = rgba("#ffd4c8")
    px[11, 10] = rgba("#fff3ec")
    save(top, "apple_top")


def paint_gapple():
    img = canvas(32)
    _sphere(img, 16, 16, 4.6, [GOLD[1], GOLD[2], GOLD[3], GOLD[4], GOLD[5]], (0.3, 0.5, 0.68, 0.85),
            spec=(GLINT, SPARK), hl_cut=(0.95, 0.985))
    px = img.load()
    for y in range(32):  # outside the painted ball keep a mid gold so box corners stay gold
        for x in range(32):
            if (x + 0.5 - 16) ** 2 + (y + 0.5 - 16) ** 2 > 4.6 ** 2:
                px[x, y] = rgba(GOLD[2] if y > 16 else GOLD[3])
    save(img, "gapple")


def _furrows(img, box_, colours, seed, spacing=4, wander=0.25):
    """Long, continuous bark furrows running down (v): groove, shadow wall, lit wall."""
    groove, shadow, lit = colours
    x0, y0, x1, y1 = box_
    px = img.load()
    rng = random.Random(seed)
    for lane in range(x0 + 1, x1, spacing):
        x = lane
        for y in range(y0, y1 + 1):
            if rng.random() < wander:
                x = max(lane - 1, min(lane + 1, x + rng.choice((-1, 1))))
            if rng.random() < 0.9:
                px[x, y] = rgba(groove)
                if x - 1 >= x0:
                    px[x - 1, y] = rgba(shadow)
                if x + 1 <= x1:
                    px[x + 1, y] = rgba(lit)


def paint_wood():
    img = canvas(32, fill=WOOD[3])
    _furrows(img, (0, 0, 31, 31), (WOOD[1], WOOD[2], WOOD[4]), seed=11, spacing=4, wander=0.2)
    px = img.load()
    rng = random.Random(12)
    for _ in range(10):  # small knots
        x, y = rng.randrange(2, 30), rng.randrange(2, 30)
        px[x, y] = rgba(WOOD[0])
        px[x, y - 1] = rgba(WOOD[1])
        px[x, y + 1] = rgba(WOOD[1])
        px[x + 1, y - 1] = rgba(WOOD[5])
    save(img, "wood")

    stem = canvas(32, fill=mix(WOOD[2], "#5a5a20", 0.3))
    grain(stem, (0, 0, 31, 31), [mix(WOOD[1], "#40401a", 0.3), mix(WOOD[3], "#7a8a30", 0.35)], axis="y", seed=14,
          density=0.45)
    save(stem, "stem")

    ends = canvas(32, fill=WOOD[3])
    ring = art(["abba",
                "bcdb",
                "bdcb",
                "abba"], {"a": WOOD[1], "b": WOOD[3], "c": WOOD[5], "d": WOOD[4]})
    for oy in range(0, 32, 4):
        for ox in range(0, 32, 4):
            ends.paste(ring, (ox, oy))
    save(ends, "wood_end")


def paint_twist():
    """The twisted shaft. Columns 0-4 run across each face (lit left edge, grain, shadowed
    right edge) so every twist step catches a highlight; columns 16-23 are the ledges."""
    for name, grain_on in (("twist", True), ("twist_gui", False)):
        img = canvas(32, fill=WOOD[3])
        px = img.load()
        rng = random.Random(61)
        prof = (WOOD[5], WOOD[4], WOOD[3], WOOD[3], WOOD[1]) if grain_on else \
            (WOOD[4], WOOD[3], WOOD[3], WOOD[2], WOOD[1])
        for y in range(32):
            for x, c in enumerate(prof):
                px[x, y] = rgba(c)
        if grain_on:
            y = 0
            while y < 32:
                x = rng.choice((2, 3))
                for i in range(rng.randint(2, 5)):
                    if y + i < 32:
                        px[x, y + i] = rgba(WOOD[2])
                y += rng.randint(3, 6)
            for y in range(1, 32, 9):
                px[2, y] = rgba(WOOD[0])
                px[3, y] = rgba(WOOD[1])
        fill(img, (16, 0, 31, 31), WOOD[4])
        fill(img, (16, 0, 31, 0), WOOD[5])
        save(img, name)


def paint_leather():
    img = canvas(32, fill=LEATHER[2])
    px = img.load()
    rng = random.Random(31)
    # rows 0..3: the raised strap (lit top edge, gold running stitch, shadowed lip)
    fill(img, (0, 0, 31, 0), LEATHER[4])
    fill(img, (0, 1, 31, 2), LEATHER[3])
    fill(img, (0, 3, 31, 3), LEATHER[1])
    for x in range(0, 32, 3):
        px[x, 1] = rgba(GOLD[4])
        px[(x + 1) % 32, 2] = rgba(GOLD[2])
    # rows 4..31: the wrap core, a little darker with a pebbled grain
    fill(img, (0, 4, 31, 31), LEATHER[2])
    for y in range(4, 32):
        for x in range(32):
            r = rng.random()
            if r < 0.14:
                px[x, y] = rgba(LEATHER[1])
            elif r < 0.2:
                px[x, y] = rgba(LEATHER[3])
    save(img, "leather")


def paint_gold():
    img = canvas(32, fill=GOLD[3])
    px = img.load()
    # soft horizontal reflections so any small face picks up light and dark banding
    for y in range(32):
        c = (GOLD[5], GOLD[4], GOLD[4], GOLD[3], GOLD[3], GOLD[3], GOLD[2], GOLD[3])[y % 8]
        fill(img, (0, y, 31, y), c)
    for x, y in ((3, 1), (17, 9), (26, 17), (9, 25), (22, 1), (12, 17)):
        px[x, y] = rgba(SPARK)
    save(img, "gold")

    save(canvas(32, fill=GOLD[4]), "gold_cap")

    # Cradle band. Columns 0-3: front face across the band (inner -> outer);
    # columns 4-15: the outer rim, an engraved vine between two beads.
    cr = canvas(32, fill=GOLD[3])
    px = cr.load()
    for y in range(32):
        for x, c in ((0, GOLD[5]), (1, GOLD[4]), (2, GOLD[3]), (3, GOLD[2])):
            px[x, y] = rgba(c)
    fill(cr, (4, 0, 15, 31), GOLD[3])
    fill(cr, (4, 0, 4, 31), GOLD[5])
    fill(cr, (5, 0, 5, 31), GOLD[2])
    fill(cr, (14, 0, 14, 31), GOLD[2])
    fill(cr, (15, 0, 15, 31), GOLD[1])
    for y in range(32):
        x = 9 + int(round(1.6 * math.sin(y * math.pi / 4)))
        px[x, y] = rgba(GOLD[1])
        if y % 8 == 1:
            px[x + 1, y] = rgba(GOLD[4])
            px[x + 2, y - 1 if y else 0] = rgba(GOLD[5])
            px[x + 2, y] = rgba(GOLD[2])
        if y % 8 == 5:
            px[x - 1, y] = rgba(GOLD[4])
            px[x - 2, y - 1] = rgba(GOLD[5])
            px[x - 2, y] = rgba(GOLD[2])
    for y in range(2, 32, 6):
        px[4, y] = rgba(SPARK)
    fill(cr, (16, 0, 31, 31), GOLD[2])
    save(cr, "cradle")

    # Gilded leaf (cutout) for the langets, calyx and twig leaves.
    pal = {"a": GOLD[0], "b": GOLD[1], "c": GOLD[2], "d": GOLD[3], "e": GOLD[4], "f": GOLD[5], "s": SPARK}
    gl = art([
        "...bbbb.....",
        ".bbeffedbb..",
        "bdeefeedddbb",
        "cssfffffffec",
        "bddcddcdddb.",
        ".bbcdcccbb..",
        "...bbbb.....",
    ], pal)
    leaf = canvas(32)
    leaf.paste(gl, (0, 0))
    save(leaf, "leaf_gold")


def paint_bark_gold():
    """Gilded-bronze branch bark for the picks, painted as a cross-profile that seg()
    stretches over each segment: columns 0..9 run across the front face from the lit
    upper edge to the shadowed underside; columns 16..23 run across the top surface."""
    img = canvas(32, fill=GOLD[3])
    px = img.load()
    rng = random.Random(51)
    front = (GOLD[5], GOLD[4], GOLD[3], GOLD[3], GOLD[4], GOLD[3], GOLD[3], GOLD[3], GOLD[2], GOLD[1])
    top = (GOLD[4], GOLD[4], GOLD[3], GOLD[3], GOLD[4], GOLD[3], GOLD[3], GOLD[2])
    for y in range(32):
        for x, c in enumerate(front):
            px[x, y] = rgba(c)
        for x, c in enumerate(top):
            px[16 + x, y] = rgba(c)
    # long bark furrows: groove with a shadowed upper wall and a lit lower lip
    for lane, lo, hi in ((3, 2, 7), (6, 4, 8), (19, 17, 22)):
        x = lane
        y = 0
        while y < 32:
            run = rng.randint(5, 11)
            for i in range(run):
                if y + i < 32:
                    px[x, y + i] = rgba(BRONZE[1])
                    px[x - 1, y + i] = rgba(GOLD[2])
                    px[x + 1, y + i] = rgba(GOLD[4])
            y += run + rng.randint(1, 3)
            x = max(lo, min(hi - 1, x + rng.choice((-1, 0, 1))))
    # fine cross cracks and a couple of bark eyes
    for _ in range(9):
        x, y = rng.randrange(2, 8), rng.randrange(32)
        px[x, y] = rgba(BRONZE[2])
    for kx, ky in ((5, 11), (20, 25)):
        for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1)):
            px[kx + dx, ky + dy] = rgba(BRONZE[1])
        px[kx, ky] = rgba(GOLD[5])
    for y in (4, 17, 26):
        px[0, y] = rgba(SPARK)
    save(img, "bark_gold")

    # the inventory icon gets the same profile without furrows (they alias at GUI size)
    gui = canvas(32, fill=GOLD[3])
    gpx = gui.load()
    for y in range(32):
        for x, c in enumerate(front):
            gpx[x, y] = rgba(c)
        for x, c in enumerate(top):
            gpx[16 + x, y] = rgba(c)
    for y in (4, 17, 26):
        gpx[0, y] = rgba(SPARK)
    save(gui, "bark_gui")

    gem = canvas(32, fill=APPLE[2])
    g = art([
        "bccb",
        "cfec",
        "cedc",
        "bccb",
    ], {"b": APPLE[0], "c": APPLE[2], "d": APPLE[4], "e": "#ff8a78", "f": "#fff0e8"})
    for oy in range(0, 32, 4):
        for ox in range(0, 32, 4):
            gem.paste(g, (ox, oy))
    save(gem, "ruby")


def paint_tip_gold():
    img = canvas(32, fill=GOLD[3])
    px = img.load()
    profile = (SPARK, GOLD[5], GOLD[4], GOLD[4], GOLD[3], GOLD[3], GOLD[2], GOLD[1])
    for y in range(32):
        for x in range(32):
            px[x, y] = rgba(profile[x % 8])
    for y in range(3, 32, 6):  # travelling glints along the ridge
        px[2, y] = rgba(GLINT)
        px[3, y + 1 if y < 31 else y] = rgba(GOLD[5])
    save(img, "tip_gold")

    glow = canvas(32, fill="#ffe27a")
    px = glow.load()
    for y in range(32):
        for x in range(32):
            if (x * 7 + y * 3) % 11 == 0:
                px[x, y] = rgba("#fff6c4")
            elif (x + y * 5) % 13 == 0:
                px[x, y] = rgba("#ffc94a")
    save(glow, "glow")


def paint_leaf():
    pal = {"a": LEAF[0], "b": LEAF[1], "c": LEAF[2], "d": LEAF[3], "e": LEAF[4], "m": GOLD[4], "n": GOLD[3],
           "t": AMBER, "r": RUST}
    leaf = art([
        "......aaaaa.....",
        "...aaaddeedaaa..",
        ".aadddcdddcdtraa",
        "abmmmmmmmmmnnnta",
        ".abbcbbcbbcbtra.",
        "...aabbbbbbraa..",
        "......aaaaa.....",
    ], pal)
    img = canvas(32)
    img.paste(leaf, (0, 0))
    small = art([
        ".aaaa.",
        "admmta",
        ".abba.",
    ], pal)
    img.paste(small, (0, 10))
    save(img, "leaf")


def paint_blossom():
    """Apple blossoms: white petals blushing pink at the base, golden stamens."""
    img = canvas(32)
    px = img.load()
    W, w, p, P = "#fff9f6", "#ffe3ea", PETAL[2], PETAL[1]

    def flower(ox, oy, rot):
        size = 9
        c = (size - 1) / 2
        for y in range(size):
            for x in range(size):
                best = None
                for k in range(5):
                    a = math.radians(rot + k * 72)
                    ux, uy = math.cos(a), math.sin(a)
                    dx, dy = x - c, y - c
                    along = dx * ux + dy * uy
                    across = -dx * uy + dy * ux
                    if along < -0.2:
                        continue
                    e = ((along - 2.3) / 2.25) ** 2 + (across / 1.55) ** 2
                    if e <= 1.0 and (best is None or e < best[0]):
                        best = (e, along)
                if best is None:
                    continue
                e, along = best
                if along < 1.0:
                    col = P
                elif along < 1.9:
                    col = p
                elif e > 0.72:
                    col = w
                else:
                    col = W
                px[ox + x, oy + y] = rgba(col)
        px[ox + 4, oy + 4] = rgba(GOLD[3])
        px[ox + 3, oy + 4] = rgba(GOLD[4])
        px[ox + 4, oy + 3] = rgba(GOLD[5])
        px[ox + 5, oy + 5] = rgba(GOLD[2])

    flower(0, 0, -90)
    flower(10, 0, -54)
    bud = art([
        ".pp.",
        "pwWp",
        "pWwP",
        ".PP.",
    ], {"p": p, "w": w, "W": W, "P": P})
    img.paste(bud, (20, 0))
    save(img, "blossom")

    st = canvas(32, fill=GOLD[4])
    fill(st, (0, 0, 31, 0), GOLD[5])
    save(st, "stamen")


def textures() -> None:
    paint_apple()
    paint_gapple()
    paint_wood()
    paint_leather()
    paint_twist()
    paint_gold()
    paint_bark_gold()
    paint_tip_gold()
    paint_leaf()
    paint_blossom()


# ------------------------------------------------------------------------------------
# Geometry helpers
# ------------------------------------------------------------------------------------

def _dist(a, b):
    return math.sqrt(sum((a[i] - b[i]) ** 2 for i in range(len(a))))


def _stretch(p0, p1, f):
    mid = [(p0[i] + p1[i]) / 2 for i in range(3)]
    return ([mid[i] + (p0[i] - mid[i]) * f for i in range(3)],
            [mid[i] + (p1[i] - mid[i]) * f for i in range(3)])


def _clamp_uv(uv):
    return [max(0.0, min(16.0, v)) for v in uv]


def seg(p0, p1, w, d, tex, front=(0, 0), side=(8, 0), end=(0, 0), roll=0.0, glow=0, side_tex=None,
        end_tex=None, skip=(), fw=None, sw=None):
    """A bar with explicit uv windows at true texel density (1 uv unit per model unit).
    front: (u, v) of the north/south window (w x length), mirrored on the back so both
    faces agree; side: east/west window (d x length); end: up/down window (w x d).
    fw / sw pin the across-width of the front / side windows, so a painted profile
    (lit edge ... shadow edge) always spans the face however thick the segment is."""
    length = _dist(p0, p1)
    fu, fv = front
    su, sv = side
    eu, ev = end
    st = side_tex or tex
    et = end_tex or tex
    fw = w if fw is None else fw
    sw = d if sw is None else sw
    faces = {
        "south": (tex, _clamp_uv([fu, fv, fu + fw, fv + length])),
        "north": (tex, _clamp_uv([fu + fw, fv, fu, fv + length])),
        "west": (st, _clamp_uv([su, sv, su + sw, sv + length])),
        "east": (st, _clamp_uv([su + sw, sv, su, sv + length])),
        "up": (et, _clamp_uv([eu, ev, eu + w, ev + d])),
        "down": (et, _clamp_uv([eu, ev, eu + w, ev + d])),
    }
    return bar(p0, p1, w, d, tex, faces=faces, roll=roll, glow=glow, skip=skip)


def pbox(frm, to, tex, centre, top=None, bottom=None, glow=0, skip=(), side=None):
    """An axis-aligned box whose faces sample a painting projected from world space
    around `centre` (painting centre = uv 8,8), so stacked boxes share one image."""
    (x0, y0, z0), (x1, y1, z1) = frm, to
    cx, cy, cz = centre
    top = top or tex
    bottom = bottom or top
    side = side or tex

    def U(v):
        return 8 + v

    faces = {
        "south": (tex, _clamp_uv([U(x0 - cx), U(cy - y1), U(x1 - cx), U(cy - y0)])),
        "north": (tex, _clamp_uv([U(cx - x1), U(cy - y1), U(cx - x0), U(cy - y0)])),
        "east": (side, _clamp_uv([U(cz - z1), U(cy - y1), U(cz - z0), U(cy - y0)])),
        "west": (side, _clamp_uv([U(z0 - cz), U(cy - y1), U(z1 - cz), U(cy - y0)])),
        "up": (top, _clamp_uv([U(x0 - cx), U(z0 - cz), U(x1 - cx), U(z1 - cz)])),
        "down": (bottom, _clamp_uv([U(x0 - cx), U(cz - z1), U(x1 - cx), U(cz - z0)])),
    }
    return box(frm, to, tex, faces=faces, glow=glow, skip=skip)


def plane(x0, y0, x1, y1, tex, uv, glow=0, shade=True):
    """A zero-thickness quad in the local XY plane at z = 0, textured on both sides with
    the same texels (a thin cutout such as a leaf or petal)."""
    u0, v0, u1, v1 = uv
    return box((x0, y0, 0), (x1, y1, 0), tex,
               faces={"south": (tex, [u0, v0, u1, v1]), "north": (tex, [u1, v0, u0, v1])},
               skip=("east", "west", "up", "down"), glow=glow, shade=shade)


def ball(centre, profile, tex, top, glow=0, side=None):
    """A round ball from stacked layers; each layer is a stepped circle of 2-3 boxes
    (few, chunky facets read cleaner than many thin ribs).
    profile: [(y0, y1, radius), ...] relative to the centre."""
    cx, cy, cz = centre
    steps = {2: ((0.92, 0.5), (0.5, 0.92)),
             3: ((0.94, 0.4), (0.74, 0.74), (0.4, 0.94))}
    parts = []
    for y0, y1, r in profile:
        n = 3 if r >= 2.6 else 2
        for fx, fz in steps[n]:
            rx, rz = r * fx, r * fz
            parts.append(pbox((cx - rx, cy + y0, cz - rz), (cx + rx, cy + y1, cz + rz), tex, centre, top=top,
                              glow=glow, side=side))
    return parts


def _bezier(p, t):
    a, b, c, d = p
    s = 1 - t
    return tuple(s ** 3 * a[i] + 3 * s * s * t * b[i] + 3 * s * t * t * c[i] + t ** 3 * d[i] for i in range(2))


def _resample(ctrl, n, samples=400):
    """n+1 points spaced evenly by arc length along a cubic Bezier."""
    pts = [_bezier(ctrl, i / samples) for i in range(samples + 1)]
    acc = [0.0]
    for i in range(samples):
        acc.append(acc[-1] + _dist(pts[i], pts[i + 1]))
    total = acc[-1]
    out, j = [], 0
    for k in range(n + 1):
        target = total * k / n
        while j < samples and acc[j + 1] < target:
            j += 1
        f = 0 if acc[j + 1] == acc[j] else (target - acc[j]) / (acc[j + 1] - acc[j])
        f = max(0.0, min(1.0, f))
        out.append(tuple(pts[j][i] + (pts[min(j + 1, samples)][i] - pts[j][i]) * f for i in range(2)))
    return out, total


# ------------------------------------------------------------------------------------
# Parts
# ------------------------------------------------------------------------------------

APPLE_PROFILE = [  # (y0, y1, radius) relative to the apple centre: wide shoulders, narrower base
    (-4.3, -3.6, 2.1),
    (-3.6, -2.7, 3.2),
    (-2.7, -1.5, 4.0),
    (-1.5, 1.5, 4.45),
    (1.5, 2.6, 4.3),
    (2.6, 3.4, 3.8),
]


def apple() -> list[dict]:
    cx, cy, cz = APPLE_C
    parts = ball(APPLE_C, APPLE_PROFILE, "apple", "apple_top", glow=5, side="apple_side")
    # the shoulder ring around the stem dimple
    y0, y1, ym = cy + 3.4, cy + 4.3, cy + 3.85  # lobes stand proud; the stem well dips between them
    for frm, to in (((cx - 3.1, y0, cz - 2.5), (cx - 0.9, y1, cz + 2.5)),
                    ((cx + 0.9, y0, cz - 2.5), (cx + 3.1, y1, cz + 2.5)),
                    ((cx - 0.9, y0, cz + 0.9), (cx + 0.9, ym, cz + 2.5)),
                    ((cx - 0.9, y0, cz - 2.5), (cx + 0.9, ym, cz - 0.9))):
        parts.append(pbox(frm, to, "apple", APPLE_C, top="apple_top", glow=5, side="apple_side"))
    return parts


def stem_and_leaf() -> list[dict]:
    cx, cy, cz = APPLE_C
    parts = [
        seg((cx, cy + 3.2, cz), (cx + 0.3, cy + 5.5, cz), 0.8, 0.8, "stem", front=(2, 2), side=(4, 2)),
        seg((cx + 0.3, cy + 5.3, cz), (cx + 1.1, cy + 6.7, cz), 0.7, 0.7, "stem", front=(6, 2), side=(8, 2)),
    ]
    base = plane(0, -1.75, 3.2, 1.75, "leaf", [0, 0, 3.2, 3.5])
    tip = plane(3.2, -1.75, 8.0, 1.75, "leaf", [3.2, 0, 8.0, 3.5])
    turn(tip, 48, "y", (3.2, 0, 0))          # the tip curls toward the viewer
    turn(tip, -22, "z", (3.2, 0, 0))         # and droops
    leaf = [base, tip]
    turn(leaf, 180 - 18, "z", (0, 0, 0))     # point left, rising
    turn(leaf, -35, "x", (0, 0, 0))          # tilt the blade up toward the sky
    move(leaf, cx + 0.2, cy + 5.0, cz + 0.2)
    return parts + leaf


def blossom_sprig() -> list[dict]:
    cx, cy, cz = APPLE_C
    a = (cx + 2.2, cy + 3.9, cz - 0.9)
    b = (cx + 4.2, cy + 5.6, cz - 0.6)
    c = (cx + 6.4, cy + 6.3, cz - 0.4)
    parts = [
        seg(a, b, 0.7, 0.7, "stem", front=(10, 4), side=(12, 4)),
        seg(b, c, 0.6, 0.6, "stem", front=(14, 4), side=(14, 8)),
    ]

    def flower(pos, u0, rx, ry, rz, size=3.6):
        f = plane(-size / 2, -size / 2, size / 2, size / 2, "blossom", [u0, 0, u0 + 4.5, 4.5], shade=False)
        st = box((-0.35, -0.35, -0.05), (0.35, 0.35, 0.45), "stamen")
        g = [f, st]
        turn(g, x=rx, y=ry, z=rz, origin=(0, 0, 0))
        move(g, *pos)
        return g

    parts += flower((c[0] + 0.6, c[1] + 0.8, c[2] + 0.6), 0, -30, 20, 10)
    parts += flower((b[0] - 0.4, b[1] + 1.4, b[2] + 1.0), 5, -25, -20, -15, size=3.2)
    bud = box((-0.7, -0.7, -0.7), (0.7, 0.9, 0.7), "blossom",
              faces={s: ("blossom", [10, 0.3, 11.4, 1.9]) for s in ("north", "south", "east", "west")}
              | {"up": ("blossom", [10.2, 0.2, 11.2, 1.2]), "down": ("blossom", [10.2, 1.2, 11.2, 2.0])})
    turn(bud, 25, "z", (0, 0, 0))
    move(bud, c[0] + 2.0, c[1] - 0.5, c[2])
    parts.append(bud)
    lf = plane(0, -0.75, 3.0, 0.75, "leaf", [0, 5, 3.0, 6.5])
    turn(lf, -25, "z", (0, 0, 0))
    turn(lf, -30, "x", (0, 0, 0))
    move(lf, b[0] + 0.5, b[1] - 0.1, b[2] + 0.3)
    parts.append(lf)
    return parts


def cradle(fine=True) -> list[dict]:
    """The gilded bezel cupping the apple: an open ring whose ends curl into scrolls, a
    glowing inner lip, and four small claws over the apple's faces."""
    cx, cy, cz = APPLE_C
    parts = []
    radius, width, depth = 5.2, 1.5, 5.2
    start, end, n = 146.0, 394.0, 10
    for k in range(n):
        t0 = math.radians(start + (end - start) * k / n)
        t1 = math.radians(start + (end - start) * (k + 1) / n)
        p0 = (cx + radius * math.cos(t0), cy + radius * math.sin(t0), cz)
        p1 = (cx + radius * math.cos(t1), cy + radius * math.sin(t1), cz)
        p0, p1 = _stretch(p0, p1, 1.14)
        v = (k * 2.9) % 12
        dz = 0.05 * (k % 2)  # neighbours differ by a hair so their overlapping faces never z-fight
        parts.append(seg(p0, p1, width, depth + dz, "cradle", front=(0, v), side=(4.0, v), end=(4, 0)))
        q0, q1 = _stretch((cx + 4.5 * math.cos(t0), cy + 4.5 * math.sin(t0), cz),
                          (cx + 4.5 * math.cos(t1), cy + 4.5 * math.sin(t1), cz), 1.12)
        parts.append(seg(q0, q1, 0.45, depth + 0.6 + dz, "glow", front=(0, v), side=(4, v), glow=12))
    # scroll curls: the ring's two open ends roll outward and down
    for sgn in (1, -1):
        base_ang = end if sgn > 0 else start
        t = math.radians(base_ang)
        p = (cx + radius * math.cos(t), cy + radius * math.sin(t))
        curl = [p]  # a small outward spiral
        r_c, steps = 1.25, 4
        centre = (p[0] + r_c * math.cos(math.radians(base_ang)), p[1] + r_c * math.sin(math.radians(base_ang)))
        for s in range(1, steps + 1):
            a = math.radians(base_ang + 180 + sgn * -70 * s)
            rr = r_c * (1 - 0.14 * s)
            curl.append((centre[0] + rr * math.cos(a), centre[1] + rr * math.sin(a)))
        for i in range(len(curl) - 1):
            wdt = 1.1 - 0.16 * i
            p0, p1 = _stretch((curl[i][0], curl[i][1], cz), (curl[i + 1][0], curl[i + 1][1], cz), 1.3)
            parts.append(seg(p0, p1, wdt, 2.6 - 0.3 * i, "cradle", front=(0, 2 * i), side=(4, 2 * i), end=(4, 0)))
    # small claws over the apple's front and back
    for ang in ((212.0, 328.0) if fine else ()):
        t = math.radians(ang)
        for sz in (1, -1):
            b0 = (cx + 5.0 * math.cos(t), cy + 5.0 * math.sin(t), cz + sz * 2.3)
            b1 = (cx + 3.4 * math.cos(t), cy + 3.4 * math.sin(t), cz + sz * 3.2)
            parts.append(seg(b0, b1, 1.2, 0.55, "tip_gold", front=(0, 0), side=(0, 4), fw=4.0, sw=4.0))
    return parts


def socket(fine=True) -> list[dict]:
    """Gold collar where the handle meets the head, and gilded leaves hugging the handle."""
    parts = []
    parts += prism((8, 13.2, 8), 2.05, 2.8, "gold", sides=8, cap="gold_cap")
    parts += prism((8, 11.55, 8), 2.4, 0.7, "gold", sides=8, cap="gold_cap")
    parts += prism((8, 14.9, 8), 2.55, 0.9, "gold", sides=8, cap="gold_cap")
    # two gilded leaves hanging from the collar down the front and back of the handle
    for sz in ((1, -1) if fine else ()):
        # the sprite points right; stand it upright with the tip down
        lf = plane(0, -1.75, 6.0, 1.75, "leaf_gold", [0, 0, 6.0, 3.5])
        turn(lf, -90, "z", (0, 0, 0))
        move(lf, 8, 11.6, 8 + sz * 1.85)
        parts.append(lf)
    return parts


ARM_CTRL = ((11.0, 19.4), (17.2, 22.3), (22.9, 20.6), (25.6, 9.2))


def _frame(a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    ln = math.hypot(dx, dy)
    return dx / ln, dy / ln


def arm(fine=True) -> list[dict]:
    """The right-hand pick (+X): a gilded branch sweeping out and down to a faceted
    polished-gold point, with a ruby-set collar, a gilded band and a snapped twig."""
    n, n_bark = 13, 8
    pts, _ = _resample(ARM_CTRL, n)
    parts = []
    acc = 0.0
    for i in range(n):
        (x0, y0), (x1, y1) = pts[i], pts[i + 1]
        if i < n_bark:
            f = i / (n_bark - 1)
            w = 5.3 - 2.4 * f
            d = 4.1 - 1.4 * f
            p0, p1 = _stretch((x0, y0, 8), (x1, y1, 8), 1.2)
            ln = _dist(p0, p1)
            v = max(0.0, 15.8 - acc - ln)
            parts.append(seg(p0, p1, w, d, "bark_gold", front=(0, v), side=(8, v), end=(8, 0), fw=5.0, sw=4.0))
        else:
            k = i - n_bark
            wd = (2.95, 2.4, 1.85, 1.25, 0.6)[k]
            p0, p1 = _stretch((x0, y0, 8), (x1, y1, 8), 1.34 if k < 4 else 1.15)
            parts.append(seg(p0, p1, wd, wd * 0.92, "tip_gold", front=(0, 2 * k), side=(0, 2 * k), end=(0, 0),
                             fw=4.0, sw=4.0))
        acc += _dist(pts[i], pts[i + 1])
    # gold collar between bark and tip, set with a ruby on each face
    a, b = pts[n_bark - 1], pts[n_bark]
    ux, uy = _frame(a, b)
    mx, my = a[0] * 0.2 + b[0] * 0.8, a[1] * 0.2 + b[1] * 0.8
    parts.append(seg((mx - ux * 0.6, my - uy * 0.6, 8), (mx + ux * 0.6, my + uy * 0.6, 8), 3.5, 3.3, "gold",
                     front=(0, 0), side=(0, 8), end=(0, 0)))
    for sz in (1, -1):
        gem = box((mx - 0.6, my - 0.6, 8 + sz * 1.65 - 0.35), (mx + 0.6, my + 0.6, 8 + sz * 1.65 + 0.35), "ruby",
                  offset=(0.5, 0.5), glow=6)
        parts.append(turn(gem, 45 + math.degrees(math.atan2(uy, ux)), "z", (mx, my, 8)))
    # a gilded band near the root
    a, b = pts[1], pts[2]
    ux, uy = _frame(a, b)
    mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
    parts.append(seg((mx - ux * 0.45, my - uy * 0.45, 8), (mx + ux * 0.45, my + uy * 0.45, 8), 5.8, 4.6, "gold",
                     front=(0, 1), side=(0, 9), end=(0, 0)))
    # a snapped-off twig stub on the upper edge (its cut end shows the wood) and a gilded leaf
    a, b = pts[4], pts[5]
    ux, uy = _frame(a, b)
    nx, ny = -uy, ux  # outward normal (the arm's upper side)
    mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
    half = (5.3 - 2.4 * 4.5 / 7) / 2
    s0 = (mx + nx * (half - 0.4), my + ny * (half - 0.4), 8)
    s1 = (mx + nx * (half + 1.5) + ux * 0.6, my + ny * (half + 1.5) + uy * 0.6, 8)
    parts.append(seg(s0, s1, 1.4, 1.4, "gold", front=(0, 2), side=(0, 4), end=(0, 0), end_tex="wood_end"))
    if fine:
        lf = plane(0, -0.9, 3.0, 0.9, "leaf_gold", [0, 0, 3.6, 3.5])
        turn(lf, math.degrees(math.atan2(uy, ux)) + 30, "z", (0, 0, 0))
        turn(lf, 25, "x", (0, 0, 0))
        move(lf, s1[0] - 0.2, s1[1] - 0.5, 8.3)
        parts.append(lf)
    return parts


def handle(fine=True) -> list[dict]:
    parts = []
    # A twisted apple-wood branch: a square shaft twisting a little every step, thicker
    # toward the grip, with two gnarled knots.
    y0, y1, n = 0.3, 13.0, 12
    h = (y1 - y0) / n
    for k in range(n):
        ya = y0 + k * h
        sz = 2.6 - 0.35 * k / (n - 1)
        v = (k * 2.3) % 14
        faces = {side: ("twist", [0, v, 2.5, v + h]) for side in ("north", "south", "east", "west")}
        faces |= {"up": ("twist", [8, 0, 8 + sz, sz]), "down": ("twist", [8, 0, 8 + sz, sz])}
        b = box((8 - sz / 2, ya, 8 - sz / 2), (8 + sz / 2, ya + h + 0.02, 8 + sz / 2), "twist", faces=faces)
        turn(b, 19.0 * k, "y", (8, ya, 8))
        parts.append(b)
    for ky, ang in (((4.2, 30.0), (9.1, 205.0)) if fine else ()):
        kn = box((9.0, ky - 0.7, 7.3), (9.9, ky + 0.7, 8.7), "wood", offset=(12, 3), faces={"east": "wood_end"})
        turn(kn, ang, "y", (8, ky, 8))
        parts.append(kn)
    # Spiral leather wrap over the grip: a square core with helical straps.
    w0, w1 = -7.4, 0.8
    parts.append(box((6.55, w0, 6.55), (9.45, w1, 9.45), "leather", offset=(0, 4)))
    pitch = 2.0
    alpha = math.degrees(math.atan((pitch / 4) / 2.9))
    faces_order = ("south", "east", "north", "west")
    y = w1 - 0.75
    i = 0
    while y > w0 + 0.55:
        side = faces_order[i % 4]
        uo = (i % 3) * 3
        if side == "south":
            st = box((6.3, y - 0.5, 9.35), (9.7, y + 0.5, 9.8), "leather", offset=(uo, 0))
            turn(st, -alpha, "z", (8, y, 9.55))
        elif side == "north":
            st = box((6.3, y - 0.5, 6.2), (9.7, y + 0.5, 6.65), "leather", offset=(uo, 0))
            turn(st, alpha, "z", (8, y, 6.45))
        elif side == "east":
            st = box((9.35, y - 0.5, 6.3), (9.8, y + 0.5, 9.7), "leather", offset=(uo, 0))
            turn(st, -alpha, "x", (9.55, y, 8))
        else:
            st = box((6.2, y - 0.5, 6.3), (6.65, y + 0.5, 9.7), "leather", offset=(uo, 0))
            turn(st, alpha, "x", (6.45, y, 8))
        parts.append(st)
        y -= pitch / 4
        i += 1
    parts += prism((8, 1.25, 8), 2.2, 1.1, "gold", sides=8, cap="gold_cap")
    parts += prism((8, -7.85, 8), 2.1, 1.0, "gold", sides=8, cap="gold_cap")
    return parts


def pommel(fine=True) -> list[dict]:
    """The branch ends in a knot from which a small golden apple hangs."""
    cx, cy, cz = GAPPLE_C
    parts = [box((7.15, -9.1, 7.15), (8.85, -8.2, 8.85), "wood", offset=(4, 4), faces={"down": "wood_end"}),
             seg((8, -8.4, 8), (8.2, -9.6, 8), 0.6, 0.6, "stem", front=(2, 6), side=(4, 6))]
    parts += ball(GAPPLE_C, [(-2.0, -1.4, 1.3), (-1.4, 0.8, 2.15), (0.8, 1.5, 1.7)], "gapple", "gapple", glow=5)
    if fine:
        lf = plane(0, -0.9, 3.0, 0.9, "leaf_gold", [0, 0, 3.6, 3.5])
        turn(lf, -25, "z", (0, 0, 0))
        turn(lf, 35, "y", (0, 0, 0))
        move(lf, 8.3, -9.4, 8.3)
        parts.append(lf)
    return parts


GUI_SWAP = {"#bark_gold": "#bark_gui", "#twist": "#twist_gui"}


def build(fine=True) -> list[dict]:
    right = arm(fine)
    left = mirror(right, "x", 8)
    parts = (handle(fine) + pommel(fine) + socket(fine) + cradle(fine) + apple() + stem_and_leaf()
             + blossom_sprig() + right + left)
    if not fine:  # inventory icon: calmer textures that stay crisp at GUI scale
        for e in parts:
            for face in e["faces"].values():
                face["texture"] = GUI_SWAP.get(face["texture"], face["texture"])
    return parts


def _first_person(parts, size=0.5, turn_deg=32.0, shift=(-0.09, -0.025, 0.0)) -> dict:
    """Vanilla first-person pose, then the head turned about the handle so its jewelled
    face (apple, cradle, far pick) faces the camera; the grip stays in the fist."""
    fp = display("pickaxe", parts, grip=GRIP, size=size)["firstperson_righthand"]
    m = display_matrix(fp["rotation"])
    sc = fp["scale"][0]
    g = [c / 16 - 0.5 for c in GRIP]
    target = [FIRST_PERSON_HAND[i] + fp["translation"][i] / 16 + sum(m[i][k] * g[k] * sc for k in range(3))
              + shift[i] for i in range(3)]
    a = math.radians(turn_deg)
    ry = ((math.cos(a), 0, math.sin(a)), (0, 1, 0), (-math.sin(a), 0, math.cos(a)))
    m2 = [[sum(m[i][k] * ry[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
    axes = {"y": tuple(m2[i][1] for i in range(3)), "z": tuple(m2[i][2] for i in range(3))}
    return place(axes, GRIP, tuple(target), sc, pose=None)


def models() -> dict:
    parts = build()
    disp = display("pickaxe", parts, grip=GRIP, size=0.76)
    disp["firstperson_righthand"] = _first_person(parts)
    icon = build(fine=False)
    return {"main": model(parts, disp), "gui": model(icon, display("pickaxe", icon, grip=GRIP, size=0.76))}
