"""Joshees Bar (issue #1204): a wrapped chocolate candy bar, torn open at one end with a
bite taken out of it. A retextured Cookie, so it eats like one.

The bar lies along +X. Its wrapper is deep blue foil: a body with rounded long edges (two
overlapping boxes, one taller and one wider), a puffed label panel on top carrying the
JOSHEES logo (cream letters on a slanted red ribbon, gold pinstripes), a red band and gold
pinlines round the sides, a seam fin underneath and a barcode panel on the back. The far
(-X) end is pinched into a crimped, saw-toothed seal; the near (+X) end is torn open, with
peeled flaps lifting off the bar and a strip of silver liner showing.

Out of the wrapper comes the bar itself: milk-chocolate coating with softened top edges and
two drizzle ridges, ending in a bite. The bite is a row of slices along Z that stop at
different lengths, so every cut wall shows the filling: chocolate, caramel studded with
peanuts, a thick nougat layer and a chocolate base, with a few peanut chunks poking out
and a drop of caramel oozing from the cut.

Most textures are 128 px, used with uv "true", so they hold 8 px per model unit. The logo
and the crimp are animated (a glossy sheen sweeps across the foil now and then); their
frames are 64 px, and the logo is painted in the top half of each frame (uv [0, 0, 16, 8]
on an 8 x 4 unit panel keeps the same 8 px per unit).
"""
from __future__ import annotations

from art.kit import animate, box, canvas, display, fill, model, rgba, save, save_animation, shine, sparkle, turn

ID = "joshees_bar"
NAME = "Joshees Bar"
KIND = "food"
COUNTERPART = "item/cookie"

# --------------------------------------------------------------------------------------
# Palettes (darkest -> lightest), hand-picked and hue-shifted
# --------------------------------------------------------------------------------------
BLUE = ["#0a1238", "#101d55", "#172a74", "#1f3a93", "#284bae", "#3560c6", "#4d7cdb", "#7aa2ec"]
RED = ["#5a0d1a", "#871523", "#b01e2b", "#d32f37", "#ec5248", "#f77c63"]
CREAM = ["#c9ad7f", "#e6cfa2", "#f6e7c4", "#fff6e2", "#ffffff"]
GOLD = ["#5e3b10", "#8c5e1b", "#b9862c", "#dcab45", "#f2cf73", "#fde9a8"]
CHOC = ["#1a0c09", "#26130d", "#341a11", "#432317", "#532d1d", "#643825", "#77452e", "#8c5638"]
CARAMEL = ["#6a3008", "#8f450c", "#b25e13", "#cc7a20", "#df9737", "#eeb55a"]
NOUGAT = ["#9a7650", "#b89468", "#cfae82", "#e0c59b", "#eed9b6"]
PEANUT = ["#7d4f24", "#a26d38", "#c48e52", "#dcae72", "#efcd97"]
FOIL = ["#5f6573", "#808693", "#a3a9b5", "#c5c9d2", "#e2e5ea", "#ffffff"]
INSIDE = ["#cfd2d8", "#e4e6ea", "#f4f5f7"]          # the paper side of the wrapper

# --------------------------------------------------------------------------------------
# Layout (model units). The bar lies along +X, centred on z = 8.
# --------------------------------------------------------------------------------------
X_SEAL, X_BODY, X_TEAR = 0.45, 1.6, 11.2   # crimp tip, wrapper body start, torn edge
WY0, WY1 = 6.3, 9.7                        # wrapper bottom / top
WZ0, WZ1 = 5.4, 10.6                       # wrapper sides
ROUND = 0.3                                # how far the long edges are rounded off
PANEL = (2.2, 10.6, 5.9, 10.1, 10.0)       # label panel x0, x1, z0, z1, top
CY0, CY1, CAP = 6.45, 9.4, 9.6             # chocolate bottom, slice top, coated top
CZ0, CZ1 = 5.6, 10.4                       # chocolate sides
# The bite: slices along Z, each ending at its own x (a rounded scoop, deepest off-centre).
SLICES = [(5.6, 6.4, 14.9), (6.4, 7.2, 14.0), (7.2, 8.0, 13.2), (8.0, 8.8, 12.9),
          (8.8, 9.6, 13.4), (9.6, 10.4, 14.5)]


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------
LETTERS = {
    "J": ["..#####", "..#####", "....##.", "....##.", "....##.", "....##.", "....##.",
          "##..##.", "##..##.", "######.", ".####.."],
    "O": [".#####.", "#######", "##...##", "##...##", "##...##", "##...##", "##...##",
          "##...##", "##...##", "#######", ".#####."],
    "S": [".######", "#######", "##.....", "##.....", "######.", ".######", ".....##",
          ".....##", ".....##", "#######", "######."],
    "H": ["##...##", "##...##", "##...##", "##...##", "#######", "#######", "##...##",
          "##...##", "##...##", "##...##", "##...##"],
    "E": ["#######", "#######", "##.....", "##.....", "######.", "######.", "##.....",
          "##.....", "##.....", "#######", "#######"],
}


def logo_mask(word: str = "JOSHEES") -> dict[tuple[int, int], int]:
    """Pixels of the word in a chunky italic face (rows lean right toward the top),
    mapped to the index of the letter they belong to."""
    cells = {}
    for i, ch in enumerate(word):
        rows = LETTERS[ch]
        for y, row in enumerate(rows):
            lean = (len(rows) - 1 - y) // 4
            for x, c in enumerate(row):
                if c == "#":
                    cells[(i * 8 + x + lean, y)] = i
    return cells


def in_counter(mask: dict, x: int, y: int) -> bool:
    """True inside a letter's counter (the gaps in O, S, E...): the same letter has ink on
    both sides of this pixel in its row. Outlines skip these so the counters stay open."""
    left = next((mask[(x - k, y)] for k in range(1, 5) if (x - k, y) in mask), None)
    right = next((mask[(x + k, y)] for k in range(1, 5) if (x + k, y) in mask), None)
    return left is not None and left == right


def paint_word(px, mask: dict, ox: int, oy: int, fill_rows, outline=None, shadow=None) -> None:
    for (x, y) in mask:
        for dx, dy in ((1, 1), (2, 1), (1, 2)):
            p = (x + dx, y + dy)
            if shadow and p not in mask and not in_counter(mask, *p):
                px[ox + p[0], oy + p[1]] = rgba(shadow)
    for (x, y) in mask:
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            p = (x + dx, y + dy)
            if outline and p not in mask and not in_counter(mask, *p):
                px[ox + p[0], oy + p[1]] = rgba(outline)
    for (x, y) in mask:
        px[ox + x, oy + y] = rgba(fill_rows(y))


def blue_foil(img, box_, base: int, seed: int = 0, crinkles: bool = True) -> None:
    """Deep blue foil: the base shade with sparse diagonal crinkles a shade lighter."""
    x0, y0, x1, y1 = box_
    px = img.load()
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            # Sparse, soft and only ever a shade lighter, so it doesn't turn to noise in a slot.
            crinkle = crinkles and ((x + 2 * y + seed) % 37 == 0 or (x * 3 - y + seed) % 53 == 0)
            px[x, y] = rgba(BLUE[min(len(BLUE) - 1, base + (1 if crinkle else 0))])


def label_frame(t: float):
    img = canvas(64)
    blue_foil(img, (0, 0, 63, 63), 3, crinkles=False)
    # Rounded panel edges: a lighter rim on top, darker toward the bottom.
    fill(img, (0, 0, 63, 0), BLUE[5])
    fill(img, (0, 31, 63, 31), BLUE[1])
    fill(img, (0, 0, 0, 31), BLUE[4])
    fill(img, (63, 0, 63, 31), BLUE[2])
    # Slanted red ribbon behind the word, with gold pinstripes along both edges.
    px = img.load()
    for y in range(7, 25):
        shift = (24 - y) // 3
        for x in range(2 + shift, 61 + shift - 6):
            if y in (7, 24):
                px[x, y] = rgba(GOLD[4] if y == 7 else GOLD[2])
            else:
                px[x, y] = rgba(RED[3] if y < 12 else RED[2] if y < 20 else RED[1])
    # Small gold diamonds capping the ribbon ends.
    for cx, cy in ((3, 16), (59, 16)):
        for dx, dy in ((0, -1), (-1, 0), (0, 0), (1, 0), (0, 1)):
            px[cx + dx, cy + dy] = rgba(GOLD[4] if (dx, dy) == (0, 0) else GOLD[3])
    # The word: cream, a touch warmer at the bottom, with a navy outline and drop shadow.
    paint_word(px, logo_mask(), 4, 10, lambda y: CREAM[3] if y < 5 else CREAM[2] if y < 9 else CREAM[1],
               outline=BLUE[1], shadow=BLUE[0])
    out = shine(img, t, "#ffffff", width=5, strength=0.35, angle=30.0, pause=0.6)
    twinkle = wave_window(t, 0.45, 0.75)
    sparkle(out, 56, 9, twinkle, CREAM[4], reach=2)
    return out


def wave_window(t: float, a: float, b: float) -> float:
    """0 outside [a, b], a smooth 0 -> 1 -> 0 hump inside it."""
    if not a < t < b:
        return 0.0
    import math
    return math.sin(math.pi * (t - a) / (b - a))


def crimp_frame(t: float):
    """The pinched seal: tight crimp ridges running out to the saw-toothed edge."""
    img = canvas(64)
    px = img.load()
    for y in range(64):
        for x in range(64):
            ridge = (y // 2) % 2
            px[x, y] = rgba(BLUE[5 if ridge else 3] if (x + y) % 9 else BLUE[4])
    return shine(img, t, "#cfe0ff", width=4, strength=0.45, angle=60.0, pause=0.6)


def side_band(name: str, rows: int) -> None:
    """A wrapper side: highlight on top, foil, gold pinline, red band, gold pinline, shade."""
    img = canvas(128)
    blue_foil(img, (0, 0, 127, 127), 3, seed=9)
    band0 = rows // 2 - 2
    fill(img, (0, 0, 127, 0), BLUE[5])
    fill(img, (0, band0 - 1, 127, band0 - 1), GOLD[3])
    fill(img, (0, band0, 127, band0 + 4), RED[2])
    fill(img, (0, band0, 127, band0), RED[3])
    fill(img, (0, band0 + 5, 127, band0 + 5), GOLD[2])
    fill(img, (0, rows - 2, 127, rows), BLUE[2])
    # Tiny cream peanut dots dotted along the band.
    for x in range(6, 128, 12):
        img.putpixel((x, band0 + 2), rgba(CREAM[2]))
        img.putpixel((x + 1, band0 + 2), rgba(CREAM[1]))
    save(img, name)


def textures() -> None:
    save_animation(animate(label_frame, 16), "label", frametime=3)
    save_animation(animate(crimp_frame, 16), "crimp", frametime=3)

    top = canvas(128)
    blue_foil(top, (0, 0, 127, 127), 4, seed=1)
    save(top, "wrap_top")
    edge = canvas(128)
    blue_foil(edge, (0, 0, 127, 127), 5, seed=2)
    save(edge, "wrap_edge")
    low = canvas(128)
    blue_foil(low, (0, 0, 127, 127), 2, seed=3)
    save(low, "wrap_low")
    side_band("wrap_side", round((WY1 - WY0 - 2 * ROUND) * 8))

    # The back: plain foil with a cream barcode panel near one end.
    back = canvas(128)
    blue_foil(back, (0, 0, 127, 127), 2, seed=5)
    paint_word(back.load(), logo_mask(), 8, 5, lambda y: CREAM[2], shadow=BLUE[0])
    fill(back, (5, 21, 72, 21), GOLD[2])
    fill(back, (28, 24, 48, 34), CREAM[3])
    for x in range(30, 47):
        if (x * 7) % 5 < 3:
            fill(back, (x, 25, x, 31), BLUE[0])
    fill(back, (30, 33, 46, 33), BLUE[1])
    save(back, "wrap_back")

    save(canvas(32, fill=INSIDE[1]), "inside")
    fill_in = canvas(32, fill=INSIDE[2])
    fill(fill_in, (0, 0, 31, 3), INSIDE[0])
    save(fill_in, "inside_edge")

    foil = canvas(32)
    for y in range(32):
        for x in range(32):
            foil.putpixel((x, y), rgba(FOIL[[3, 4, 2, 4, 5, 3][(x + y // 2) % 6]]))
    save(foil, "foil")

    # Chocolate coating: smooth, with a faint pour texture.
    choc = canvas(128)
    px = choc.load()
    for y in range(128):
        for x in range(128):
            h = (x * 13 + y * 7 + x * y) % 31
            px[x, y] = rgba(CHOC[6] if h == 0 else CHOC[4] if h < 3 else CHOC[5])
    save(choc, "choc")
    choc_top = canvas(128)
    px = choc_top.load()
    for y in range(128):
        for x in range(128):
            h = (x * 13 + y * 7 + x * y) % 31
            px[x, y] = rgba(CHOC[7] if (x * 5 + y * 3) % 19 == 0 else CHOC[5] if h < 3 else CHOC[6])
    save(choc_top, "choc_top")
    save(canvas(32, fill=CHOC[3]), "choc_low")
    # The raised band is 5 px tall at 8 px per unit: a lit top edge, red, a shaded foot,
    # and small cream dots running along it.
    band = canvas(128, fill=RED[2])
    fill(band, (0, 0, 127, 0), RED[4])
    fill(band, (0, 1, 127, 1), RED[3])
    fill(band, (0, 4, 127, 4), RED[1])
    for x in range(5, 128, 10):
        fill(band, (x, 2, x + 1, 2), CREAM[2])
    save(band, "band")
    save(canvas(16, fill=GOLD[3]), "gold")
    save(canvas(16, fill=GOLD[1]), "gold_dark")
    save(canvas(32, fill=CHOC[6]), "drizzle")

    # The cut: chocolate top coat, caramel with peanuts, nougat, chocolate base (top -> down).
    cut = canvas(128)
    px = cut.load()
    layers = [(0, 2, "choc"), (3, 10, "caramel"), (11, 19, "nougat"), (20, 127, "base")]
    for y0, y1, what in layers:
        for y in range(y0, y1 + 1):
            for x in range(128):
                h = (x * 37 + y * 101 + x * y * 3) % 17
                if what == "choc":
                    c = CHOC[6] if y == 0 else CHOC[5]
                elif what == "caramel":
                    c = CARAMEL[4] if y == 3 else CARAMEL[3] if h > 3 else CARAMEL[2]
                elif what == "nougat":
                    c = NOUGAT[3] if h > 4 else NOUGAT[2] if h > 1 else NOUGAT[4]
                else:
                    c = CHOC[4] if y > 20 else CHOC[5]
                px[x, y] = rgba(c)
    # Peanut halves sitting in the caramel: rounded 4x3 blobs, lit on top with a dark
    # caramel shadow under them, alternating high and low so they don't line up.
    for i, x in enumerate(range(1, 124, 6)):
        y = 4 if i % 2 else 6
        for dx, dy in ((1, 0), (2, 0), (0, 1), (1, 1), (2, 1), (3, 1), (1, 2), (2, 2)):
            px[x + dx, y + dy] = rgba(PEANUT[4] if dy == 0 else PEANUT[3] if dy == 1 else PEANUT[1])
        px[x + 1, y + 1] = rgba(PEANUT[4])
        for dx in (1, 2):
            px[x + dx, y + 3] = rgba(CARAMEL[1])
    # A glossy caramel line where it meets the nougat.
    for x in range(0, 128, 3):
        px[x, 10] = rgba(CARAMEL[5])
    save(cut, "cut")

    nut = canvas(16, fill=PEANUT[3])
    fill(nut, (0, 0, 15, 3), PEANUT[4])
    fill(nut, (0, 12, 15, 15), PEANUT[1])
    save(nut, "peanut")
    drip = canvas(16, fill=CARAMEL[3])
    fill(drip, (0, 0, 3, 15), CARAMEL[5])
    save(drip, "caramel")


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def wrapper() -> list[dict]:
    parts = []
    # Body: one box taller, one wider, so the long edges read as rounded.
    parts.append(box((X_BODY, WY0, WZ0 + ROUND), (X_TEAR, WY1, WZ1 - ROUND), "wrap_edge",
                     faces={"up": "wrap_top", "down": "wrap_back", "east": "inside"}))
    parts.append(box((X_BODY, WY0 + ROUND, WZ0), (X_TEAR, WY1 - ROUND, WZ1), "wrap_side",
                     faces={"up": "wrap_edge", "down": "wrap_low", "east": "inside", "west": "wrap_low"},
                     skip=()))
    # The puffed label panel with the logo, and a softer step round it.
    x0, x1, z0, z1, top = PANEL
    parts.append(box((x0 - 0.3, WY1, z0 - 0.15), (x1 + 0.3, top - 0.15, z1 + 0.15), "wrap_edge",
                     faces={"up": "wrap_top"}, skip=("down",)))
    parts.append(box((x0, WY1, z0), (x1, top, z1), "wrap_edge",
                     faces={"up": ("label", [0, 0, 16, 8])}, skip=("down",)))
    # The red band and its gold pinlines stand a touch proud of both long sides.
    band_top = WY1 - ROUND - 9 / 8
    band_bot = WY1 - ROUND - 14 / 8
    for z, sign in ((WZ0, -1), (WZ1, 1)):
        zo = z + 0.1 * sign
        parts.append(box((X_BODY + 0.15, band_bot, min(z, zo)), (X_TEAR - 0.15, band_top, max(z, zo)), "band"))
        for y0, y1, tex in ((band_top, band_top + 0.14, "gold"), (band_bot - 0.14, band_bot, "gold_dark")):
            zg = z + 0.06 * sign
            parts.append(box((X_BODY + 0.15, y0, min(z, zg)), (X_TEAR - 0.15, y1, max(z, zg)), tex))
    # Wrinkles where the body is pinched into the seal.
    for z0, z1, h in ((6.4, 7.0, 0.16), (7.7, 8.2, 0.2), (8.9, 9.6, 0.14)):
        e = box((X_BODY - 0.05, WY1 - 0.05, z0), (X_BODY + 0.7, WY1 + h, z1), "wrap_edge", skip=("down",))
        parts.append(turn(e, -12, "z", (X_BODY, WY1, 8)))
    # Seam fin running along the underside.
    parts.append(box((X_BODY + 0.2, WY0 - 0.2, 7.82), (X_TEAR - 0.1, WY0, 8.18), "wrap_low", skip=("up",)))
    # The pinched end, then the crimped seal with a saw-toothed edge.
    parts.append(box((X_BODY - 0.6, WY0 + 0.45, WZ0 + 0.5), (X_BODY, WY1 - 0.45, WZ1 - 0.5), "wrap_low",
                     faces={"up": "wrap_edge"}))
    teeth = 12
    width = (WZ1 + 0.2 - (WZ0 - 0.2)) / teeth
    for i in range(teeth):
        za = WZ0 - 0.2 + i * width
        tip = X_SEAL if i % 2 == 0 else X_SEAL + 0.22
        parts.append(box((tip, 7.65, za), (X_BODY - 0.5, 8.35, za + width), "crimp",
                         faces={"west": "wrap_low"}))
    return parts


def torn_end() -> list[dict]:
    parts = []
    # A strip of silver liner peeking out between the wrapper and the chocolate.
    lx0, lx1 = X_TEAR - 0.2, X_TEAR + 0.35
    parts.append(box((lx0, CAP - 0.02, CZ0 - 0.05), (lx1, CAP + 0.1, CZ1 + 0.05), "foil", skip=("down",)))
    parts.append(box((lx0, CY0 - 0.12, CZ0 - 0.05), (lx1, CY0, CZ1 + 0.05), "foil", skip=("up",)))
    for z0, z1 in ((CZ0 - 0.12, CZ0), (CZ1, CZ1 + 0.12)):
        parts.append(box((lx0, CY0 - 0.1, z0), (lx1, CAP + 0.08, z1), "foil"))
    # Peeled flaps on top, lifting away from the bar.
    for za, zb, length, lift in ((WZ0 + ROUND, 7.0, 0.9, 14), (7.0, 7.7, 0.45, 6), (7.7, 9.1, 1.25, 22),
                                 (9.1, WZ1 - ROUND, 0.6, 10)):
        e = box((X_TEAR - 0.05, WY1 - 0.14, za), (X_TEAR + length, WY1, zb), "wrap_top",
                faces={"down": "inside", "east": "inside_edge"})
        parts.append(turn(e, lift, "z", (X_TEAR, WY1, 8)))
    # Side flaps, peeled outward.
    for z, sign in ((WZ0, -1), (WZ1, 1)):
        for ya, yb, length, peel in ((WY0 + ROUND, 7.6, 0.7, 12), (7.6, 8.6, 1.1, 20), (8.6, WY1 - ROUND, 0.4, 8)):
            zin = z - 0.14 * sign
            e = box((X_TEAR - 0.05, ya, min(z, zin)), (X_TEAR + length, yb, max(z, zin)), "wrap_side",
                    faces={"north" if sign > 0 else "south": "inside", "east": "inside_edge"},
                    uv={"north": [0, 7, length, 7 + yb - ya], "south": [0, 7, length, 7 + yb - ya],
                        "up": [0, 0, 1, 1], "down": [0, 0, 1, 1], "east": [0, 0, 1, 1], "west": [0, 0, 1, 1]})
            parts.append(turn(e, -peel * sign, "y", (X_TEAR, 8, z)))
    return parts


def chocolate() -> list[dict]:
    parts = []
    first, last = SLICES[0], SLICES[-1]
    for za, zb, xe in SLICES:
        faces = {"east": "cut", "north": "cut", "south": "cut", "up": "choc_top", "down": "choc_low"}
        if (za, zb, xe) == first:
            faces["north"] = "choc"
        if (za, zb, xe) == last:
            faces["south"] = "choc"
        parts.append(box((X_TEAR - 0.4, CY0, za), (xe, CY1, zb), "choc", faces=faces))
        # The coated top, softened at the outer edges and stopping short of the cut.
        ia = 0.2 if (za, zb, xe) == first else 0.0
        ib = 0.2 if (za, zb, xe) == last else 0.0
        parts.append(box((X_TEAR - 0.4, CY1, za + ia), (xe - 0.15, CAP, zb - ib), "choc",
                         faces={"up": "choc_top"}, skip=("down",)))
    # Two drizzle ridges across the top, set at an angle.
    for x in (11.9, 12.5):
        e = box((x - 0.15, CAP, 6.3), (x + 0.15, CAP + 0.18, 9.7), "drizzle", skip=("down",))
        parts.append(turn(e, 18, "y", (x, CAP, 8)))
    # Peanut chunks poking out of the caramel, and a caramel drop oozing down the cut.
    caramel_y = CY1 - 6.5 / 8
    for za, zb, xe in SLICES[1:5:2]:
        zc = (za + zb) / 2
        parts.append(box((xe - 0.1, caramel_y - 0.2, zc - 0.25), (xe + 0.18, caramel_y + 0.2, zc + 0.2), "peanut"))
    za, zb, xe = SLICES[3]
    parts.append(box((xe, CY0 + 0.6, za + 0.2), (xe + 0.12, caramel_y + 0.2, za + 0.55), "caramel"))
    # Peanut halves bared on top of the bite's steps, so the filling shows from above too.
    for (za, zb, xe), dz in ((SLICES[2], 0.15), (SLICES[4], 0.3)):
        parts.append(box((xe - 0.65, CAP - 0.05, za + dz), (xe - 0.2, CAP + 0.22, za + dz + 0.4), "peanut"))
    return parts


def build() -> list[dict]:
    return wrapper() + torn_end() + chocolate()


def models() -> dict:
    parts = build()
    # Turned so the logo reads left to right in the slot, with the bitten end to the right.
    d = display("food", parts, size=1.6, gui_rotation=(55, 40, 0), gui_span=15.5)
    fp = d["firstperson_righthand"]
    fp["translation"][1] = round(fp["translation"][1] + 2.5, 3)
    fp["scale"] = [round(v * 0.8, 4) for v in fp["scale"]]
    # Turned round so the logo faces the player the right way up.
    fp["rotation"][1] = round(fp["rotation"][1] + 180, 3)
    return {"main": model(parts, d)}
