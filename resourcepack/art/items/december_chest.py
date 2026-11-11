"""December Chest (issue #1497, design in #1407): the monthly crate chest for December, a
festive winter wonderland chest in snow white, icy blue, evergreen, festive red and warm
gold.

A body of warm honey wood boards framed in festive red lacquer with gold trim: a red
plinth and seam band edged in gold, red corner posts, and frosted corners: icy blue caps
crusted with frost and topped with snow at the foot and the top of every corner. Snow is
banked round the foot of the body.

The lid is buried in thick layered snow: a deep blanket overhanging every edge in rounded
lumps, a second, smaller drift on top, and from it rises a little evergreen tree in three
snowy tiers with a few warm lights and a glowing gold star. Two wrapped gifts (red with a
gold ribbon, icy blue with a white ribbon) sit at its foot, and candy canes stand in the
snow at the lid's front corners. An evergreen garland swags round the lid under the snow,
strung with small red, gold, blue and green lights.

The emblem is a golden snowflake in a red ring on the lid's front, where the garland's
swags meet. Below it the lock is a gold plate with a snowflake-shaped keyhole glowing icy
blue. Two frosted silver hinges hold the lid at the back.

Animation (one loop): the garland and tree lights twinkle in turn, the star shines, a
glint passes over the gold, and the keyhole breathes with a cold blue light.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block. Unlike January (ice brick walls, icicles and a crystal
spire): a warm wooden body in red and gold, a little star-topped tree, gifts, candy canes
and a lit garland.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM,  # noqa: F401
                                    keyhole_glow, planks, put, shell)
from art.kit import animate, arc, bar, box, canvas, copy, display, lathe, model, prism, save, save_animation, \
    shine, sparkle, turn, wave

ID = "december_chest"
NAME = "December Chest"

HONEY = ["#2a180c", "#3a2212", "#4b2d18", "#5c391f", "#6e4526", "#80522e", "#8f5e34", "#9c6a3a"]
RED = ["#2c0406", "#4c070b", "#6e0c12", "#901419", "#ae1e22", "#c8302c", "#de4c40", "#f07a66"]
GOLD = ["#4a2c04", "#76480a", "#a06a10", "#c48c1c", "#dcaa30", "#eec654", "#fae08a", "#fff4c8"]
SNOW = ["#6e86a6", "#8ea4c2", "#aec2da", "#cad8ea", "#e0eaf5", "#eff5fb", "#fbfdff"]
ICE = ["#0e2a4a", "#16406c", "#22608e", "#3484b0", "#52a6cc", "#7ec4e2", "#b4e0f2", "#e6f6fc"]
PINE = ["#06180e", "#0a2614", "#10361c", "#164824", "#1e5a2e", "#2a6e38", "#3a8446"]
SILVER = ["#3a4250", "#525c6c", "#6c7888", "#8894a4", "#a4b0be", "#c2ccd8", "#e2e8f0"]

SNOW1 = LID_TOP + 1.0         # top of the snow blanket
SNOW2 = SNOW1 + 0.7           # top of the drift
STAR_TOP = 20.7 - SNOW2       # how far the tree (star included) rises above the drift (top at 20.65)


# ======================================================================================
# Textures
# ======================================================================================

def boards(size: int = 32) -> object:
    """Warm walnut boards with a little pale blue frost along the top edge of each board."""
    img = planks(HONEY[1:], size=size, rows=4, seed=411, vertical=False, knots=3, seam_dark=0)
    rng = random.Random(412)
    step = size // 4
    for b in range(0, size, step):
        for x in range(size):
            r = rng.random()
            if r < 0.12:
                put(img, x, b, SNOW[3] if r < 0.07 else ICE[6])
    return img


def lacquer(size: int = 16) -> object:
    """Festive red lacquer: glossy, lit top rows, a few deeper streaks."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 6 if y == 0 else 5 if y < 3 else 4 if y < 9 else 3
            if y >= size - 2:
                tone = 2
            if (x * 5 + y * 3) % 19 == 0:
                tone -= 1
            put(img, x, y, RED[tone])
    return img


def gold(t: float) -> object:
    """Warm gold trim with a glint running across."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 6 if y == 0 else 5 if y < 4 else 4 if y < 11 else 3
            if y >= 14:
                tone = 2
            if (x + y * 2) % 7 == 0:
                tone += 1
            put(img, x, y, GOLD[min(7, tone)])
    return shine(img, t, colour=GOLD[7], width=3.0, strength=0.6, pause=0.5)


def snow(t: float) -> object:
    """Soft snow: white with blue shadows in the hollows and a few twinkling crystals."""
    img = canvas(16)
    rng = random.Random(413)
    for y in range(16):
        for x in range(16):
            r = rng.random()
            # brightest in the middle, a soft cool mottle drifting toward one side, no edge ring
            d = math.hypot(x + 0.5 - 7, y + 0.5 - 7) / 9
            tone = 6 if d < 0.45 and r > 0.3 else 5
            if r < 0.12 + 0.25 * max(0.0, (x + y) / 30 - 0.4):
                tone = 4
            put(img, x, y, SNOW[tone])
    for k, (sx, sy) in enumerate(((3, 4), (11, 2), (7, 11), (13, 12))):
        sparkle(img, sx, sy, wave(t, k / 4) ** 3, colour="#ffffff", reach=1)
    return img


def snow_side(size: int = 16) -> object:
    """The snow's side: bright on top, shading to blue underneath."""
    img = canvas(size)
    rng = random.Random(417)
    for y in range(size):
        for x in range(size):
            tone = 6 if y < 3 else 5 if y < 7 else 4 if y < 10 else 3 if y < 14 else 1
            if rng.random() < 0.12 and y < 14:
                tone -= 1
            put(img, x, y, SNOW[max(0, tone)])
    return img


def frost(size: int = 16) -> object:
    """Frosted icy corner caps: blue ice under white rime creeping in from the edges."""
    img = canvas(size)
    rng = random.Random(419)
    for y in range(size):
        for x in range(size):
            edge = min(x, y, size - 1 - x, size - 1 - y)
            tone = 5 if edge > 3 else 6
            if rng.random() < 0.18:
                tone = 4
            c = ICE[tone]
            if edge <= 1 or (rng.random() < 0.25 and edge <= 3):
                c = SNOW[5]
            put(img, x, y, c)
    return img


def needles(size: int = 16) -> object:
    """Evergreen needles: dark green with lighter needle strokes, a little snow dusting."""
    img = canvas(size)
    rng = random.Random(421)
    for y in range(size):
        for x in range(size):
            tone = 2 if (x + y) % 3 else 4
            if (x - y) % 4 == 0:
                tone = 5
            if rng.random() < 0.1:
                tone = 1
            put(img, x, y, PINE[tone])
    for _ in range(6):
        put(img, rng.randrange(size), rng.randrange(size // 3), SNOW[5])
    return img


def tree_tier(size: int = 16) -> object:
    """A tree tier's side: needle layers with a snowy top edge."""
    img = needles()
    for x in range(size):
        put(img, x, 0, SNOW[6])
        if x % 3:
            put(img, x, 1, SNOW[5])
    return img


def bulb(hue, phase: float):
    """A little light bulb, twinkling at its own phase."""
    def paint(t: float) -> object:
        img = canvas(16)
        k = wave(t, phase)
        n = len(hue)
        for y in range(16):
            for x in range(16):
                d = math.hypot(x + 0.5 - 8, y + 0.5 - 8) / 8
                heat = (1.05 - d) * (0.4 + 0.6 * k)
                put(img, x, y, hue[max(0, min(n - 1, int(heat * n + 0.5)))])
        return img
    return paint


BULB_RED = ["#3a0408", "#7a0c12", "#c41c22", "#f04a3e", "#ff9a86"]
BULB_GOLD = ["#4a2c04", "#9a6410", "#e0a024", "#ffd25a", "#fff2b8"]
BULB_BLUE = ["#08224a", "#14468a", "#2a7ccc", "#62b4f0", "#c4ecff"]
BULB_GREEN = ["#062a10", "#0e5a20", "#22963a", "#5ad064", "#c4f8b8"]


def star(t: float) -> object:
    """The tree's star: a gold five-point star, glowing, with a shine crossing it."""
    img = canvas(16)
    pts = []
    for i in range(10):
        a = math.radians(90 + i * 36)
        r = 7.6 if i % 2 == 0 else 3.2
        pts.append((8 + r * math.cos(a), 8.6 - r * math.sin(a)))

    def inside(px, py):
        c = False
        j = len(pts) - 1
        for i in range(len(pts)):
            xi, yi = pts[i]
            xj, yj = pts[j]
            if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi:
                c = not c
            j = i
        return c
    for y in range(16):
        for x in range(16):
            if inside(x + 0.5, y + 0.5):
                d = math.hypot(x + 0.5 - 8, y + 0.5 - 8.6) / 7.6
                put(img, x, y, GOLD[7] if d < 0.25 else GOLD[6] if d < 0.5 else GOLD[5] if x < 8 else GOLD[4])
    return shine(img, t, colour="#ffffff", width=3.0, strength=0.7, pause=0.4)


def gift(base, ribbon):
    """Wrapping paper with a ribbon across the middle and a small dot pattern."""
    def paint(size: int = 16) -> object:
        img = canvas(size)
        for y in range(size):
            for x in range(size):
                c = base[3] if (x + y) % 6 else base[5]
                if x in (0, size - 1) or y in (0, size - 1):
                    c = base[2]
                if 6 <= x <= 9:
                    c = ribbon[4] if x in (6, 7) else ribbon[3]
                put(img, x, y, c)
        return img
    return paint


def cane(size: int = 16) -> object:
    """Candy cane stripes: red and white diagonals with a gloss line."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            red = ((x + y) // 3) % 2 == 0
            c = (RED[5] if x < 8 else RED[4]) if red else (SNOW[6] if x < 8 else SNOW[4])
            if x in (3, 4):
                c = "#ffffff" if not red else RED[7]
            put(img, x, y, c)
    return img


def ribbon_gold(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            put(img, x, y, GOLD[6] if y < 4 else GOLD[4] if y < 12 else GOLD[3])
    return img


def ribbon_red(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            put(img, x, y, RED[6] if y < 4 else RED[5] if y < 12 else RED[3])
    return img


def ribbon_white(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            put(img, x, y, SNOW[6] if y < 4 else SNOW[5] if y < 12 else SNOW[3])
    return img


def snowflake_mask(fx: float, fy: float, scale: float = 1.0) -> bool:
    """A six-armed snowflake with side branches, centred on (8, 8)."""
    x, y = (fx - 8) / scale, (fy - 8) / scale
    r = math.hypot(x, y)
    if r < 1.3:
        return True
    if r > 7.4:
        return False
    for k in range(6):
        a = math.radians(k * 60 + 90)
        ux, uy = math.cos(a), math.sin(a)
        along = x * ux + y * uy
        across = abs(-x * uy + y * ux)
        if 0 < along < 7.3 and across < 0.75:
            return True
        for b0 in (3.4, 5.4):
            # branches leaning out at 60 degrees from the arm
            for s in (1, -1):
                bx, by = ux * b0, uy * b0
                vx = ux * 0.5 - s * uy * 0.866
                vy = uy * 0.5 + s * ux * 0.866
                dx, dy = x - bx, y - by
                t = dx * vx + dy * vy
                d = abs(-dx * vy + dy * vx)
                if 0 < t < (2.0 if b0 < 5 else 1.4) and d < 0.6:
                    return True
    return False


def snowflake(size: int = 32) -> object:
    """The golden snowflake emblem card."""
    img = canvas(size)
    s = size / 16
    for y in range(size):
        for x in range(size):
            fx, fy = (x + 0.5) / s, (y + 0.5) / s
            if snowflake_mask(fx, fy):
                lit = not snowflake_mask(fx - 0.6, fy - 0.6)
                put(img, x, y, GOLD[7] if lit else GOLD[5] if fx < 8 else GOLD[4])
    return img


def field(size: int = 16) -> object:
    """The emblem's backing: deep red, darker toward the rim."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            put(img, x, y, RED[3] if d < 4.5 else RED[2] if d < 6.5 else RED[1])
    return img


def plate(size: int = 16) -> object:
    """The gold lock plate with a dark snowflake cut through it."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            if x <= 1 or y <= 1:
                c = GOLD[6]
            elif x >= size - 2 or y >= size - 2:
                c = GOLD[2]
            else:
                c = GOLD[4] if (x + y) % 6 else GOLD[3]
            put(img, x, y, c)
            if snowflake_mask(x + 0.5, y + 0.5, scale=0.72):
                put(img, x, y, ICE[0])
    return img


def keyhole(t: float) -> object:
    """The snowflake keyhole's cold light."""
    base = keyhole_glow(ICE, t)
    out = canvas(16)
    for y in range(16):
        for x in range(16):
            if snowflake_mask(x + 0.5, y + 0.5, scale=0.95):
                out.putpixel((x, y), base.getpixel((x, y)))
    return out


def icicle(size: int = 16) -> object:
    """Clear ice: pale on the lit side, blue in the middle, a white edge."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            put(img, x, y, ICE[7] if x < 4 else ICE[6] if x < 10 else ICE[5])
    return img


def silver(size: int = 16) -> object:
    """Frosted silver for the hinges."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 6 if y < 2 else 4 if y < 12 else 2
            if (x + y) % 5 == 0:
                tone += 1
            put(img, x, y, SILVER[min(6, tone)] if y > 3 or x % 3 else SNOW[6])
    return img


def textures() -> None:
    save(boards(), "boards")
    save(lacquer(), "lacquer")
    save(snow_side(), "snow_side")
    save(frost(), "frost")
    save(needles(), "needles")
    save(tree_tier(), "tier")
    save(gift(RED, GOLD)(), "gift_red")
    save(gift(ICE, SNOW)(), "gift_blue")
    save(gift(PINE, RED)(), "gift_green")
    save(ribbon_red(), "ribbon_red")
    save(cane(), "cane")
    save(ribbon_gold(), "ribbon_gold")
    save(ribbon_white(), "ribbon_white")
    save(snowflake(), "snowflake")
    save(field(), "field")
    save(plate(), "plate")
    save(silver(), "silver")
    save(icicle(), "icicle")
    save_animation(animate(gold, 12), "gold", frametime=3)
    save_animation(animate(snow, 8), "snow", frametime=4)
    save_animation(animate(star, 12), "star", frametime=2)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)
    for name, hue, phase in (("bulb_red", BULB_RED, 0.0), ("bulb_gold", BULB_GOLD, 0.25),
                             ("bulb_blue", BULB_BLUE, 0.5), ("bulb_green", BULB_GREEN, 0.75)):
        save_animation(animate(bulb(hue, phase), 8), name, frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

BULBS = ("bulb_red", "bulb_gold", "bulb_blue", "bulb_green")


def frame() -> list[dict]:
    """Red lacquer plinth and seam band edged in gold, candy-cane corner posts, frosted caps
    on every corner, a snow ledge on the plinth and snow banked round the foot."""
    parts = [box((B0 - 0.22, 0.0, B0 - 0.22), (B1 + 0.22, 1.0, B1 + 0.22), "lacquer", skip=("down",)),
             box((B0 - 0.2, SEAM - 0.85, B0 - 0.2), (B1 + 0.2, SEAM, B1 + 0.2), "lacquer", skip=("up",))]
    for y in (1.0, SEAM - 0.85):
        parts.append(box((B0 - 0.12, y - 0.12, B0 - 0.12), (B1 + 0.12, y + 0.18, B1 + 0.12), "gold",
                         skip=("up", "down")))
    for cx in (B0, B1):
        for cz in (B0, B1):
            sx, sz = (1 if cx == B0 else -1), (1 if cz == B0 else -1)
            parts.append(box((cx - 0.2 * sx, 1.0, cz - 0.2 * sz), (cx + 1.1 * sx, SEAM - 0.85, cz + 1.1 * sz),
                             "cane", uv="full"))
            for (y0, y1) in ((0.0, 2.2), (SEAM - 2.4, SEAM + 0.05)):
                parts.append(box((cx - 0.34 * sx, y0, cz - 0.34 * sz), (cx + 1.6 * sx, y1, cz + 1.6 * sz), "frost",
                                 uv="full", skip=("down",) if y0 == 0 else ()))
                parts.append(box((cx - 0.38 * sx, y1, cz - 0.38 * sz), (cx + 1.5 * sx, y1 + 0.35, cz + 1.5 * sz),
                                 "snow", faces={"north": "snow_side", "south": "snow_side", "east": "snow_side",
                                                "west": "snow_side"}, uv="full", skip=("down",)))
    # a ledge of snow along the plinth's top, and snow banked round the foot in soft lumps
    parts.append(box((B0 - 0.3, 1.0, B0 - 0.3), (B1 + 0.3, 1.3, B1 + 0.3), "snow",
                     faces={"north": "snow_side", "south": "snow_side", "east": "snow_side", "west": "snow_side"},
                     uv="full", skip=("down",)))
    rng = random.Random(431)
    bank = []
    for x in (3.0, 5.4, 10.6, 13.0):
        w = rng.uniform(1.0, 1.4)
        bank.append(box((x - w, 0.0, B1 + 0.2), (x + w, rng.uniform(0.9, 1.3), B1 + 0.55), "snow",
                        faces={"south": "snow_side", "east": "snow_side", "west": "snow_side"}, uv="full",
                        skip=("down", "north")))
    for yaw in (0, 90, -90):
        parts += turn(copy(bank), yaw, "y", (8.0, 0.0, 8.0))
    return parts


def lid_snow() -> list[dict]:
    """The thick snow over the lid: a blanket bulging past every edge as a cornice, an uneven
    mound of overlapping drifts (highest behind the tree, slumping toward the edges), and
    lumps drooping over every edge with an icicle under each."""
    side = {"north": "snow_side", "south": "snow_side", "east": "snow_side", "west": "snow_side"}
    parts = [box((L0 - 0.25, LID_TOP - 0.05, L0 - 0.25), (L1 + 0.25, SNOW1, L1 + 0.25), "snow", faces=side,
                 uv="full", skip=("down",))]
    # uneven drifts, each turned and tipped a little, with a round heap under the tree
    for (cx, cz, hx, hz, top, yaw, tilt) in ((7.8, 7.4, 5.4, 4.8, 0.42, 14, 0), (9.2, 5.4, 3.9, 3.1, 0.56, -27, 4),
                                             (4.2, 11.0, 2.7, 2.5, 0.26, 33, -6), (11.9, 11.6, 2.3, 2.2, 0.34, -18, 7)):
        d = box((cx - hx, SNOW1 - 0.05, cz - hz), (cx + hx, SNOW1 + top, cz + hz), "snow", faces=side, uv="full",
                skip=("down",))
        turn(d, yaw, "y", (cx, SNOW1, cz))
        if tilt:
            turn(d, tilt, "x", (cx, SNOW1, cz))
        parts.append(d)
    parts += prism((8.0, (SNOW1 + SNOW2) / 2, 7.2), 3.3, SNOW2 - SNOW1 + 0.05, "snow_side", sides=8, cap="snow",
                   uv="full")
    rng = random.Random(433)
    lumps = []
    for x in (1.8, 4.3, 11.7, 14.2):
        w = rng.uniform(0.95, 1.3)
        drop = rng.uniform(0.6, 1.6)
        z0, z1 = L1 + 0.1, L1 + 0.32
        lumps.append(box((x - w, LID_TOP - 0.45, z0), (x + w, SNOW1 - 0.1, z1), "snow", faces=side, uv="full",
                         skip=("north",)))
        lumps.append(box((x - w * 0.6, LID_TOP - drop, z0), (x + w * 0.6, LID_TOP - 0.4, z1 - 0.04), "snow_side",
                         uv="full", skip=("north", "up")))
        lumps.append(bar((x, LID_TOP - drop + 0.05, z0 + 0.1), (x + 0.05, LID_TOP - drop - 0.6, z0 + 0.1), 0.3, 0.2,
                         "icicle", uv="full"))
    for yaw in (0, 90, 180, 270):
        parts += turn(copy(lumps), yaw, "y", (8.0, 0.0, 8.0))
    # more drips on the front edge, over the garland, so the cornice line breaks up
    z0, z1 = L1 + 0.12, L1 + 0.34
    for (x, w, drop) in ((3.0, 0.7, 1.45), (6.1, 0.6, 0.75), (9.9, 0.65, 0.85), (13.0, 0.7, 1.25)):
        parts.append(box((x - w, LID_TOP - drop, z0), (x + w, SNOW1 - 0.15, z1), "snow_side", uv="full",
                         skip=("north",)))
        parts.append(bar((x, LID_TOP - drop + 0.05, z0 + 0.1), (x - 0.04, LID_TOP - drop - 0.45, z0 + 0.1), 0.26,
                         0.18, "icicle", uv="full"))
    return parts


def garland() -> list[dict]:
    """An evergreen garland swagging round the lid under the snow, with lights. On the front
    it hangs in two swags meeting at the emblem."""
    z = L1 + 0.12

    def swag(x0, x1, top, sag, n, lights):
        pts = []
        for i in range(n + 1):
            u = i / n
            pts.append((x0 + (x1 - x0) * u, top - sag * 4 * u * (1 - u), z))
        out = [bar(a, b, 0.7, 0.5, "needles") for a, b in zip(pts, pts[1:])]
        for i in lights:
            x, y, _ = pts[i]
            out.append(box((x - 0.22, y - 0.65, z + 0.1), (x + 0.22, y - 0.15, z + 0.38), BULBS[(i + int(x0)) % 4],
                           uv="full", glow=11, shade=False))
        return out
    front = swag(L0 + 0.4, 6.0, LID_TOP - 0.5, 1.6, 5, (1, 3)) + swag(10.0, L1 - 0.4, LID_TOP - 0.5, 1.6, 5, (2, 4))
    sides = swag(L0 + 0.4, L1 - 0.4, LID_TOP - 0.5, 1.9, 6, (1, 3, 5))
    parts = front
    for yaw in (90, -90, 180):
        parts += turn(copy(sides), yaw, "y", (8.0, 0.0, 8.0))
    return parts


def emblem() -> list[dict]:
    """A golden snowflake in a red ring with a gold rim on the lid's front."""
    cx, cy, z = 8.0, SEAM + 2.4, L1
    parts = arc((cx, cy, z + 0.2), 2.15, 0, 360, 12, 0.36, 0.3, "gold", plane="xy")
    parts += prism((cx, cy, z + 0.1), 2.0, 0.2, "lacquer", axis="z", sides=16, cap="field", uv="full")
    parts.append(box((cx - 2.0, cy - 2.0, z + 0.22), (cx + 2.0, cy + 2.0, z + 0.26), "snowflake", uv="full",
                     skip=("north", "east", "west", "up", "down")))
    return parts


def snowflake_lock() -> list[dict]:
    """A gold plate across the seam with a snowflake keyhole glowing icy blue, and a gold
    hasp down from the emblem."""
    zf = 16.02
    parts = [box((6.2, 5.6, B1 - 0.05), (9.8, SEAM + 0.5, zf), "plate",
                 uv={"north": [0, 0, 4, 4], "east": [0, 0, 1, 4], "west": [0, 0, 1, 4], "up": [0, 0, 4, 1],
                     "down": [0, 0, 4, 1], "south": [0, 0, 16, 16]}),
             box((6.45, 5.85, zf), (9.55, SEAM + 0.25, zf + 0.03), "keyhole", uv="full", glow=12, shade=False,
                 skip=("north", "east", "west", "up", "down")),
             box((7.4, SEAM + 0.3, zf - 0.04), (8.6, SEAM + 0.8, zf + 0.14), "gold")]
    return parts


def tree() -> list[dict]:
    """A little evergreen in three snowy tiers on a short trunk, a few lights and the star."""
    x, z = 8.0, 7.2
    y = SNOW2 - 0.05
    parts = prism((x, y + 0.35, z), 0.4, 0.7, "boards", sides=8, cap="boards")
    tiers = ((0.45, 1.55, 3.0), (1.45, 1.4, 2.3), (2.4, 1.2, 1.55))  # (base height, height, radius)
    for (h0, h, r) in tiers:
        prof = [(0.0, r), (h * 0.35, r * 0.78), (h * 0.7, r * 0.45), (h, r * 0.12)]
        parts += lathe((x, y + h0, z), prof, "tier", sides=8, cap="snow", uv="full")
    for (a, hy, rr, tex) in ((20, 0.95, 2.35, "bulb_red"), (110, 1.0, 2.3, "bulb_gold"), (200, 0.9, 2.4, "bulb_blue"),
                             (290, 1.05, 2.3, "bulb_green"), (65, 2.0, 1.65, "bulb_blue"), (165, 2.05, 1.6, "bulb_red"),
                             (255, 1.95, 1.7, "bulb_gold"), (345, 2.0, 1.6, "bulb_green"), (120, 2.95, 0.95, "bulb_gold"),
                             (300, 2.9, 1.0, "bulb_red")):
        t = math.radians(a)
        bx, bz = x + rr * math.cos(t), z + rr * math.sin(t)
        parts.append(box((bx - 0.21, y + hy - 0.21, bz - 0.21), (bx + 0.21, y + hy + 0.21, bz + 0.21), tex,
                         uv="full", glow=11, shade=False))
    # the star: two crossed cards so it reads from every side
    sy = y + STAR_TOP - 0.9
    for yaw in (0, 90):
        e = box((x - 0.9, sy - 0.9, z - 0.02), (x + 0.9, sy + 0.9, z + 0.02), "star", uv="full", glow=14,
                shade=False, skip=("up", "down", "east", "west"))
        parts.append(turn(e, yaw, "y", (x, sy, z)))
    parts.append(box((x - 0.1, y + 3.45, z - 0.1), (x + 0.1, sy - 0.6, z + 0.1), "gold"))
    return parts


def gifts() -> list[dict]:
    """Two wrapped gifts at the tree's foot, each with a ribbon cross and a bow."""
    parts = []
    for (cx, cz, w, h, yaw, tex, rib) in ((4.3, 11.2, 1.3, 1.7, 18, "gift_red", "ribbon_gold"),
                                          (11.8, 10.9, 1.05, 1.3, -24, "gift_blue", "ribbon_white")):
        y = SNOW1 + 0.25
        g = [box((cx - w, y, cz - w), (cx + w, y + h, cz + w), tex, uv="full", skip=("down",)),
             box((cx - w - 0.03, y + h - 0.01, cz - 0.22), (cx + w + 0.03, y + h + 0.05, cz + 0.22), rib,
                 skip=("down",)),
             box((cx - 0.22, y + h - 0.01, cz - w - 0.03), (cx + 0.22, y + h + 0.05, cz + w + 0.03), rib,
                 skip=("down",))]
        for s in (1, -1):
            g.append(bar((cx, y + h + 0.05, cz), (cx + 0.6 * s, y + h + 0.45, cz), 0.36, 0.2, rib))
        g.append(box((cx - 0.16, y + h + 0.02, cz - 0.16), (cx + 0.16, y + h + 0.3, cz + 0.16), rib))
        parts += turn(g, yaw, "y", (cx, y, cz))
    return parts


def candy_cane(c, h: float, face: float) -> list[dict]:
    """A candy cane standing in the snow: a straight shaft and a hooked top."""
    x, y, z = c
    parts = [bar((x, y - 0.3, z), (x, y + h, z), 0.62, 0.62, "cane")]
    parts += arc((x + 0.62, y + h, z), 0.62, 180, 0, 5, 0.56, 0.56, "cane", plane="xy")
    parts.append(bar((x + 1.24, y + h, z), (x + 1.24, y + h - 0.6, z), 0.52, 0.52, "cane"))
    return turn(parts, face, "y", (x, y, z))


def candy_canes() -> list[dict]:
    """One candy cane standing in the snow beside the red gift."""
    return candy_cane((2.3, SNOW1 + 0.1, 13.4), 2.0, -20)


def gift_ornaments() -> list[dict]:
    """Small wrapped gifts fixed to the body's front either side of the lock (red with gold,
    green with red ribbon) and one on each side wall."""
    parts = []
    z = B1
    for (cx, cy, w, tex, rib, yaw) in ((3.9, 2.05, 1.0, "gift_red", "ribbon_gold", 0),
                                       (12.1, 1.95, 0.9, "gift_green", "ribbon_red", 0)):
        g = [box((cx - w, cy - w, z - 0.1), (cx + w, cy + w, z + 0.78), tex, uv="full", skip=("north",)),
             box((cx - 0.2, cy - w - 0.03, z - 0.1), (cx + 0.2, cy + w + 0.03, z + 0.8), rib, skip=("north",)),
             box((cx - w - 0.03, cy - 0.2, z - 0.1), (cx + w + 0.03, cy + 0.2, z + 0.8), rib, skip=("north",))]
        for sx in (1, -1):
            g.append(bar((cx, cy + w, z + 0.4), (cx + 0.55 * sx, cy + w + 0.4, z + 0.4), 0.32, 0.18, rib))
        parts += g
    side = [box((7.0, 2.8, B1 - 0.1), (9.0, 4.8, B1 + 0.75), "gift_blue", uv="full", skip=("north",)),
            box((7.8, 2.77, B1 - 0.1), (8.2, 4.83, B1 + 0.78), "ribbon_white", skip=("north",)),
            box((6.97, 3.6, B1 - 0.1), (9.03, 4.0, B1 + 0.78), "ribbon_white", skip=("north",))]
    for yaw in (90, -90):
        parts += turn(copy(side), yaw, "y", (8.0, 0.0, 8.0))
    return parts


def hinges() -> list[dict]:
    """Two frosted silver barrel hinges at the back with straps up the lid."""
    parts = []
    for x in (4.0, 12.0):
        parts += prism((x, SEAM, L0 - 0.08), 0.45, 2.0, "silver", axis="x", sides=8, cap="frost")
        parts.append(box((x - 0.7, SEAM - 2.4, B0 - 0.18), (x + 0.7, SEAM - 0.3, B0), "silver"))
        parts.append(box((x - 0.7, SEAM + 0.3, L0 - 0.18), (x + 0.7, LID_TOP - 0.8, L0), "silver"))
        parts.append(box((x - 1.05, SEAM + 0.42, L0 - 0.5), (x + 1.05, SEAM + 0.7, L0 - 0.05), "snow",
                         faces={"north": "snow_side", "east": "snow_side", "west": "snow_side"}, uv="full",
                         skip=("down", "south")))
        for dx, ln in ((-0.5, 0.9), (0.45, 0.6)):
            parts.append(bar((x + dx, SEAM - 0.4, L0 - 0.3), (x + dx, SEAM - 0.4 - ln, L0 - 0.3), 0.22, 0.18, "icicle",
                             uv="full"))
    return parts


def models() -> dict:
    p = shell("boards", "boards", "snow", faces_lid={"north": "boards", "south": "boards", "east": "boards",
                                                      "west": "boards"})
    p += frame()
    p += lid_snow()
    p += garland()
    p += emblem()
    p += snowflake_lock()
    p += tree()
    p += gifts()
    p += candy_canes()
    p += gift_ornaments()
    p += hinges()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
