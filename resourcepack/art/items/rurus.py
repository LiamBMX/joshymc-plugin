"""Rurus (issue #1205): a little caramel-filled chocolate cup, standing in front of the
opened RURUS roll it came out of. A retextured Cookie, so it eats like one.

The candy is a round cup with a wide foot and sides that taper in to a softly domed top
(the opposite of the Peanut Butter Cup, which flares out). It is built from radial
pieces: 24 wall staves leaning inward (every other one stands a little proud, which gives
the shallow ridges round the outside), filling wedges inside them, a shoulder ring and a
two-step dome on top. One bite is taken out of the front: inside it the staves stop at a
floor that rises toward the bite's edges, the floor shows glossy caramel inside the thin
chocolate shell, and the cut walls show the shell over the caramel.

Behind it lies the roll: a caramel-gold paper tube (16 staves round the X axis, then
turned to lie across the view) with orange stripes round both ends and a chocolate label
patch carrying the cream RURUS logo on two sides. The far end is pleated shut; the near
end is torn open, with peeled flaps, a ring of gold foil and the next candy's top
peeking out.

The roll paper is 128 px with one stave per 8 px row (u runs along the roll at 8 px per
unit). The bitten caramel is animated: a bright glint slides across it now and then.
Wedge top faces use uv "true", so their u runs outward from the candy's centre: those
textures are painted in columns by radius (4 px per unit).
"""
from __future__ import annotations

import math

from art import kit
from art.kit import animate, box, canvas, display, fill, lathe, model, rgba, save, save_animation, shine, sparkle, turn

ID = "rurus"
NAME = "Rurus"
KIND = "food"
COUNTERPART = "item/cookie"

# --------------------------------------------------------------------------------------
# Palettes (darkest -> lightest), hand-picked and hue-shifted
# --------------------------------------------------------------------------------------
# Milk chocolate a touch warmer than the Peanut Butter Cup's, so the two never read alike.
CHOC = ["#1c0d08", "#2b150c", "#3b1d10", "#4c2715", "#5e321b", "#713e22", "#864c2b", "#9c5d36", "#b47448"]
CARAMEL = ["#6e2f05", "#94450a", "#b8600f", "#d27e1c", "#e59a2f", "#f1b649", "#f9cf6e", "#ffe7a3"]
GOLD = ["#5b3609", "#8a5512", "#b7791d", "#d69a2c", "#ecba45", "#f8d576", "#fff0b8"]
ORANGE = ["#7c2405", "#b23a08", "#e0590f", "#f77b22", "#ffa04a"]
CREAM = ["#c8a77a", "#e5cb9f", "#f6e6c4", "#fff7e4"]
FOIL = ["#6b4a12", "#a87a22", "#d9ad44", "#f3d57d", "#fff1bd"]
INSIDE = ["#cdb48a", "#e2cda6", "#f1e3c5"]          # the paper side of the wrapper

# --------------------------------------------------------------------------------------
# Layout (model units)
# --------------------------------------------------------------------------------------
# The candy stands upright, centred on (CX, *, CZ).
CX, CZ = 10.6, 5.2
Y0, Y1 = 1.0, 5.7                     # wall foot and wall top
R_FOOT, R_TOP = 3.8, 2.6              # wall centre radius at the foot and the top
WALL = 0.6                            # wall thickness
N = 24                                # staves / wedges round the cup
STEP = 360.0 / N
Y_TOP = 6.0                           # top of the shoulder ring
DOME = ((6.3, 2.35), (6.5, 1.6))      # the top swells in two soft steps: (top height, radius)
BITE_AT = 315.0                      # centre of the bite, degrees round Y from +X toward +Z
# |sector offset| from the bite centre -> (radius the candy keeps, floor height): a rounded
# scoop out of the front edge, deepest in the middle.
BITE = {0.5: (1.15, 3.5), 1.5: (1.35, 3.7), 2.5: (1.9, 4.4), 3.5: (2.5, 5.2)}
GLOSS_AT = 60.0                       # the dome's highlight, on the side facing the light

# The roll is built along +X, then turned to lie across the view behind the candy.
RX0, RX1 = 3.5, 11.5                  # pleated end, torn open end
RCY, RCZ = 4.6, 8.0                   # axis height and depth before turning
ROLL_YAW = 135.0                     # turn round Y: open end toward the candy
ROLL_AT = (8.6, 12.6)                 # where the middle of the roll ends up (x, z)
RR = 3.4                              # outer radius
RN = 16                               # staves round the roll
RSTEP = 360.0 / RN
LOGO_ROWS = (2, 10)                   # the staves each RURUS label starts on (two staves tall)
BAND = (4.7, 10.3)                    # the label patch along the roll (x, before turning)


def wall_radius(y: float) -> float:
    return R_FOOT + (y - Y0) / (Y1 - Y0) * (R_TOP - R_FOOT)


def offset(i: int) -> float:
    """Sector i's distance from the bite centre, in sectors (always a half step)."""
    a = (i * STEP - BITE_AT + 180) % 360 - 180
    return abs(math.floor(a / STEP) + 0.5)


def bitten(i: int) -> tuple[float, float] | None:
    return BITE.get(offset(i))


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------
LETTERS = {
    "R": ["#####.", "######", "##..##", "##..##", "#####.", "####..", "##.##.", "##..##", "##..##"],
    "U": ["##..##", "##..##", "##..##", "##..##", "##..##", "##..##", "##..##", "######", ".####."],
    "S": [".#####", "######", "##....", "#####.", ".#####", "....##", "....##", "######", "#####."],
}


def logo_mask(word: str = "RURUS") -> set[tuple[int, int]]:
    """Rounded, chunky letters with a gentle bounce: R and S sit a pixel higher than U."""
    cells = set()
    for i, ch in enumerate(word):
        lift = 0 if ch == "U" else -1
        for y, row in enumerate(LETTERS[ch]):
            for x, c in enumerate(row):
                if c == "#":
                    cells.add((i * 8 + x, y + lift))
    # Soften the outer corners of every letter.
    for i, ch in enumerate(word):
        lift = 0 if ch == "U" else -1
        rows = LETTERS[ch]
        for (x, y) in ((0, 0), (5, 0), (0, len(rows) - 1), (5, len(rows) - 1)):
            if rows[y][x] == "#" and ch != "U" or (ch == "U" and y == len(rows) - 1):
                cells.discard((i * 8 + x, y + lift))
    return cells


def paper():
    """The roll's paper: caramel-gold with bright orange stripes round both ends, and a
    chocolate label patch on each side carrying the cream RURUS logo, outlined dark and
    framed in orange."""
    img = canvas(128)
    px = img.load()
    bx0, bx1 = round(BAND[0] * 8), round(BAND[1] * 8)
    for y in range(128):
        for x in range(128):
            # Gold paper with a fine lengthwise grain.
            px[x, y] = rgba(GOLD[4] if (x * 7 + y * 3) % 47 == 0 else GOLD[3])
            # Orange stripes round the tube: a wide one outside a thin one, at both ends.
            if bx0 - 7 <= x <= bx0 - 5 or bx1 + 4 <= x <= bx1 + 6:
                px[x, y] = rgba(ORANGE[3])
            if x in (bx0 - 3, bx1 + 2):
                px[x, y] = rgba(ORANGE[2])
    mask = logo_mask()
    width = max(x for x, _ in mask) + 1
    ox = bx0 + (bx1 - bx0 - width) // 2
    for start in LOGO_ROWS:
        y0, y1 = start * 8 - 3, start * 8 + 18          # the label patch, about 2.6 staves tall
        for y in range(y0 - 1, y1 + 2):
            for x in range(bx0 - 1, bx1 + 1):
                edge = y in (y0 - 1, y1 + 1) or x in (bx0 - 1, bx1)
                if edge and (x, y) in ((bx0 - 1, y0 - 1), (bx1, y0 - 1), (bx0 - 1, y1 + 1), (bx1, y1 + 1)):
                    continue                            # rounded corners
                px[x, y % 128] = rgba(ORANGE[3] if edge else CHOC[5] if (x * 5 + y * 11) % 29 else CHOC[6])
        oy = start * 8 + 4
        for (x, y) in mask:                             # a thin dark outline keeps it crisp when small
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                p = (x + dx, y + dy)
                if p not in mask:
                    px[ox + p[0], oy + p[1]] = rgba(CHOC[1])
        for (x, y) in mask:
            px[ox + x, oy + y] = rgba(CREAM[3] if y < 2 else CREAM[2] if y < 6 else CREAM[1])
    return img


def wave_window(t: float, a: float, b: float) -> float:
    """0 outside [a, b], a smooth 0 -> 1 -> 0 hump inside it."""
    if not a < t < b:
        return 0.0
    return math.sin(math.pi * (t - a) / (b - a))


def caramel_px(x: int, y: int) -> str:
    """Smooth, glossy caramel: soft diagonal swirls a shade lighter, rare bright glints."""
    swirl = (x + 2 * y + (x * y) % 5) % 13
    if (x * 31 + y * 17) % 97 == 0:
        return CARAMEL[7]
    return CARAMEL[5] if swirl < 3 else CARAMEL[4]


def caramel_top_frame(t: float):
    """The bite's floor, painted by radius (4 px per unit): glossy caramel out to the
    chocolate shell, with a slow glint sliding across it."""
    img = canvas(64)
    px = img.load()
    for y in range(64):
        for x in range(64):
            px[x, y] = rgba(caramel_px(x, y))
    # A wet ripple ring and a pale highlight pool near the centre.
    for y in range(64):
        px[5, y] = rgba(CARAMEL[6])
        px[2, y] = rgba(CARAMEL[7])
    return shine(img, t, "#fffaf0", width=7, strength=0.95, angle=70.0, pause=0.55)


def wall(name: str, shades: list[int]) -> None:
    """A wall stave, rounded across its width (shades left -> right), lit on the shoulder
    and darker toward the foot."""
    img = canvas(32)
    px = img.load()
    for x in range(32):
        col = shades[x * len(shades) // 32]
        for y in range(32):
            k = col + (1 if y < 2 else 0) - (1 if y >= 22 else 0)
            px[x, y] = rgba(CHOC[max(0, min(8, k))])
    save(img, name)


def radial(name: str, bands: list[tuple[float, str]], size: int = 64) -> None:
    """A wedge-top texture painted by radius (4 px per unit): bands of (from radius, colour)."""
    img = canvas(size)
    for r0, colour in bands:
        fill(img, (int(r0 * 4), 0, size - 1, size - 1), colour)
    save(img, name)


def textures() -> None:
    save(paper(), "paper")
    save_animation(animate(caramel_top_frame, 12), "caramel_top", frametime=4)

    # Candy walls: a proud ridge, rounded and brightest on its crest, and the groove.
    wall("wall_ridge", [6, 7, 7, 7, 7, 7, 6])
    wall("wall_valley", [6, 6, 6, 6, 6, 6, 6])
    # Shoulder ring and dome: one smooth tone, the step edges a touch lighter.
    radial("shoulder", [(0, CHOC[5])])
    radial("dome", [(0, CHOC[6])])
    edge = canvas(32, fill=CHOC[6])
    fill(edge, (0, 0, 31, 1), CHOC[7])
    fill(edge, (0, 4, 31, 31), CHOC[5])
    save(edge, "shoulder_side")
    save(canvas(16, fill=CHOC[7]), "dome_side")
    save(canvas(16, fill=CHOC[8]), "gloss")
    save(canvas(16, fill=CREAM[0]), "rim_light")
    base = canvas(32, fill=CHOC[2])
    fill(base, (0, 0, 31, 0), CHOC[3])
    save(base, "base")

    # Plain caramel (the filling sides) and a cut face: the shell over the caramel.
    car = canvas(64)
    px = car.load()
    for y in range(64):
        for x in range(64):
            px[x, y] = rgba(caramel_px(x, y))
    save(car, "caramel")
    cut = car.copy()
    fill(cut, (0, 0, 63, 0), CHOC[7])
    fill(cut, (0, 1, 63, 1), CHOC[5])
    fill(cut, (0, 2, 63, 2), CARAMEL[6])
    save(cut, "cut")

    # Roll: the inside of the paper, the pleated shut end and the gold foil liner.
    save(canvas(32, fill=INSIDE[1]), "inside")
    paper_edge = canvas(32, fill=GOLD[2])
    fill(paper_edge, (0, 0, 31, 7), INSIDE[2])
    save(paper_edge, "paper_edge")
    pleat = canvas(64)
    px = pleat.load()
    for y in range(64):
        for x in range(64):
            a = math.atan2(y - 31.5, x - 31.5)
            r = math.hypot(x - 31.5, y - 31.5)
            fold = int((a + math.pi) / (2 * math.pi) * 20) % 2
            g = 3 if fold else 2
            if r < 5:
                g = 1
            elif r > 26:
                g += 1
            px[x, y] = rgba(GOLD[g])
    save(pleat, "pleat")
    foil = canvas(32)
    for y in range(32):
        for x in range(32):
            foil.putpixel((x, y), rgba(FOIL[[2, 3, 1, 3, 4, 2][(x + y // 2) % 6]]))
    save(foil, "foil")
    # The next candy's top in the open end: chocolate with a lit rim.
    peek = canvas(64)
    px = peek.load()
    for y in range(64):
        for x in range(64):
            r = math.hypot(x - 31.5, y - 31.5)
            px[x, y] = rgba(CHOC[7] if r > 26 else CHOC[6] if r > 20 else CHOC[5])
    save(peek, "peek")
    save(canvas(16, fill=CHOC[4]), "peek_side")


# --------------------------------------------------------------------------------------
# Geometry: the candy
# --------------------------------------------------------------------------------------

def stave(i: int, top: float) -> dict:
    """One wall stave in sector i, leaning inward, standing from the foot to `top`."""
    proud = 0.08 if i % 2 == 0 else 0.0
    lean = math.degrees(math.atan((R_TOP - R_FOOT) / (Y1 - Y0)))
    height = (top - Y0) / math.cos(math.radians(lean))
    half = R_FOOT * math.tan(math.radians(STEP / 2)) * 1.1
    r = R_FOOT + proud
    tex = "wall_ridge" if i % 2 == 0 else "wall_valley"
    e = box((CX + r - WALL / 2, Y0, CZ - half), (CX + r + WALL / 2, Y0 + height, CZ + half), tex,
            faces={"up": "shoulder_side", "down": "base", "east": (tex, [0, 0, 16, 16])})
    turn(e, -lean, "z", (CX + r, Y0, CZ))
    return turn(e, -i * STEP, "y", (CX, Y0, CZ))


def wedge(i: int, y0: float, y1: float, r: float, up: str, sides: str, r0: float = 0.0,
          shade: bool = True, widen: float = 1.06) -> dict:
    """A slice of sector i from radius r0 out to r, between heights y0 and y1."""
    half = r * math.tan(math.radians(STEP / 2)) * widen
    e = box((CX + r0, y0, CZ - half), (CX + r, y1, CZ + half), sides,
            faces={"up": up, "down": "base"}, shade=shade, offset=(r0, 0))
    return turn(e, -i * STEP, "y", (CX, y0, CZ))


def candy() -> list[dict]:
    parts = []
    parts += lathe((CX, Y0, CZ), [(0.0, R_FOOT - 0.2), (0.5, R_FOOT - 0.2)], "base", sides=16, cap="base")
    for i in range(N):
        bite = bitten(i)
        lift = 0.03 if i % 2 else 0.0                # neighbouring caramel faces never z-fight
        if bite:
            keep, floor = bite
            parts.append(stave(i, floor))
            # Caramel up to the floor, then the piece the bite left, showing a cut face.
            parts.append(wedge(i, Y0 + 0.4, floor + lift, wall_radius(floor) - 0.05, "caramel_top", "caramel",
                               shade=False))
            parts.append(wedge(i, floor, Y_TOP, keep, "shoulder", "cut", shade=False))
        else:
            parts.append(stave(i, Y1))
            parts.append(wedge(i, Y0 + 0.4, Y1 - 0.2, wall_radius(Y1) - WALL / 2, "caramel", "caramel"))
            # The rounded shoulder: overhangs the wall top a touch and is a shade lighter.
            parts.append(wedge(i, Y1 - 0.3, Y_TOP, R_TOP + WALL / 2 + 0.05, "shoulder", "shoulder_side",
                               widen=1.12))
    # The top swells in two soft steps, cut back where the bite reaches it.
    y = Y_TOP
    for top, r in DOME:
        for i in range(N):
            bite = bitten(i)
            reach = min(r, bite[0] - 0.2) if bite else r
            if reach > 0.2:
                parts.append(wedge(i, y - 0.05, top, reach, "dome", "dome_side", shade=False, widen=1.12))
        y = top
    # Glossy streaks on the lit side of the dome and the shoulder.
    for a, r0, r1, y, w in ((GLOSS_AT, 0.3, 1.3, DOME[1][0], 0.45), (GLOSS_AT + 8, 1.7, 2.2, DOME[0][0], 0.4),
                            (GLOSS_AT + 14, 2.5, 3.0, Y_TOP, 0.35)):
        e = box((CX + r0, y, CZ - w / 2), (CX + r1, y + 0.04, CZ + w / 2), "gloss", uv="full",
                shade=False, skip=("down",))
        parts.append(turn(e, -a, "y", (CX, y, CZ)))
    # A bright arc along the lit rim of the upper step, so the dome reads even in the slot.
    top, r = DOME[1]
    for a in range(int(GLOSS_AT) - 50, int(GLOSS_AT) + 51, 20):
        e = box((CX + r - 0.4, top, CZ - 0.3), (CX + r - 0.05, top + 0.04, CZ + 0.3), "rim_light", uv="full",
                shade=False, skip=("down",))
        parts.append(turn(e, -a, "y", (CX, top, CZ)))
    return parts


# --------------------------------------------------------------------------------------
# Geometry: the roll
# --------------------------------------------------------------------------------------

def roll_stave(k: int, x0: float, x1: float, thick: float = 0.35) -> dict:
    """Stave k of the paper tube, from x0 to x1, its outside carrying row k of the label."""
    half = RR * math.tan(math.radians(RSTEP / 2)) * 1.06
    e = box((x0, RCY + RR - thick, RCZ - half), (x1, RCY + RR, RCZ + half), "paper",
            faces={"down": "inside", "east": "paper_edge", "west": "paper_edge"},
            uv={"up": [x0, k, x1, k + 1], "down": [0, 0, 4, 4], "east": [0, 0, 1, 1], "west": [0, 0, 1, 1],
                "north": [0, 0, 1, 1], "south": [0, 0, 1, 1]},
            skip=("north", "south"))
    return turn(e, k * RSTEP, "x", (x0, RCY, RCZ))


def roll() -> list[dict]:
    parts = []
    torn = {3: 0.5, 4: 0.9, 9: 0.6, 12: 1.0, 13: 0.4}   # staves that run past the tear, and by how much
    for k in range(RN):
        parts.append(roll_stave(k, RX0, RX1))
    # Peeled flaps at the open end, curling outward.
    for k, length in torn.items():
        half = RR * math.tan(math.radians(RSTEP / 2)) * 0.95
        e = box((RX1 - 0.05, RCY + RR - 0.2, RCZ - half), (RX1 + length, RCY + RR, RCZ + half), "paper",
                faces={"down": "inside", "east": "paper_edge"},
                uv={"up": [RX1, k, RX1 + length, k + 1], "down": [0, 0, 2, 2], "east": [0, 0, 1, 1],
                    "west": [0, 0, 1, 1], "north": [0, 0, 1, 1], "south": [0, 0, 1, 1]})
        turn(e, 18 + 14 * length, "z", (RX1, RCY + RR, RCZ))
        parts.append(turn(e, k * RSTEP, "x", (RX1, RCY, RCZ)))
    # The pleated, shut end.
    parts += lathe((RX0, RCY, RCZ), [(-0.35, RR - 0.5), (0.05, RR - 0.25)], "pleat", axis="x", sides=16, cap="pleat")
    parts += lathe((RX0, RCY, RCZ), [(-0.6, 0.9), (-0.3, 1.1)], "pleat", axis="x", sides=8, cap="pleat")
    # Gold foil lining the open end, and the next candy's top peeking out of it.
    parts += lathe((RX1, RCY, RCZ), [(-0.6, RR - 0.4), (0.1, RR - 0.4)], "foil", axis="x", sides=16, cap="foil")
    parts += lathe((RX1, RCY, RCZ), [(-0.2, 2.9), (0.35, 2.9)], "peek_side", axis="x", sides=16, cap="peek")
    mid = ((RX0 + RX1) / 2, RCY, RCZ)
    turn(parts, ROLL_YAW, "y", mid)
    return kit.move(parts, ROLL_AT[0] - mid[0], 0, ROLL_AT[1] - mid[2])


def build() -> list[dict]:
    return candy() + roll()


def models() -> dict:
    parts = build()
    d = display("food", parts, gui_rotation=(30, 225, 0), gui_span=15.5)
    fp = d["firstperson_righthand"]
    fp["translation"][1] = round(fp["translation"][1] + 2.0, 3)
    fp["scale"] = [round(s * 1.1, 4) for s in fp["scale"]]
    tp = d["thirdperson_righthand"]
    tp["scale"] = [round(s * 1.3, 4) for s in tp["scale"]]
    # Turned round so the bitten candy, not the back of the roll, faces the player.
    fp["rotation"][1] = round(fp["rotation"][1] + 180, 3)
    return {"main": model(parts, d)}
