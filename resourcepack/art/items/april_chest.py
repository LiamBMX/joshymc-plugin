"""April Chest (issue #1482, design in #1407): the monthly crate chest for April, a spring
showers chest in lavender, light blue and pastel pink.

A body of lavender painted boards (vertical) with pastel pink corner posts capped in
pewter, light blue panel frames and a falling-rain pattern painted between them. The lid
is light blue and rounded into a soft barrel vault (three stepped bands, cross-banded by
pewter straps), and an open pastel umbrella stands in it, tipped back a little: eight
pink and lavender gores on pewter ribs with silver tips, a ferrule on top and a crook
handle. Cherry blossom sprigs grow over both front corners of the lid and up each side
wall, and fat raindrops hang off the lid's edge on every side.

The emblem is a rain cloud: a puffy white and lavender cloud on a pewter shield over the
seam, with three glowing raindrops falling out of it, and a raindrop-shaped keyhole below
that glows light blue. Two round pewter knuckle hinges hold the lid at the back.

Animation (one loop): light runs down the raindrops, a soft glint crosses the umbrella
gores and the cloud, the blossoms' hearts twinkle and the keyhole breathes.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block. Unlike the other crate chests: a rounded barrel lid with
an umbrella standing out of it instead of a crown, spire, bow, clover or roof.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM,  # noqa: F401
                                    keyhole_glow, metal, only, planks, put, rims, shell)
from art.kit import animate, arc, bar, box, canvas, copy, display, model, prism, save, save_animation, shine, \
    sparkle, turn, wave

ID = "april_chest"
NAME = "April Chest"

LAV = ["#3d2f5e", "#55447e", "#6e5c9c", "#8774b6", "#a08fcc", "#bbaee0", "#d6cdf0", "#f1edfc"]
DEEP = ["#1f1838", "#2c2350", "#3b3068", "#4c3f80", "#5f5298", "#7568ae", "#8f85c4"]
SKY = ["#203e62", "#2f5885", "#4375a8", "#5f95c8", "#82b5df", "#a8d0ef", "#cfe7f9", "#eff8ff"]
PINK = ["#5e2440", "#87355a", "#b25079", "#d6759c", "#eb9cbc", "#f6c2d7", "#fce2ec", "#fff5f9"]
PEWTER = ["#262838", "#3d4054", "#575b70", "#787c91", "#9a9eb2", "#bcc0cf", "#dcdfe8", "#ffffff"]
CLOUD = ["#6c6594", "#8b85b0", "#aba6ca", "#c9c6df", "#e2e0ef", "#f3f2fa", "#ffffff"]
RAIN = ["#173a63", "#25598d", "#3c7fb9", "#62a6dc", "#93cbf1", "#c8e8fb", "#ffffff"]
GLOW = ["#0b1d38", "#143561", "#215a92", "#3a86c3", "#6cb3e6", "#ade0fa", "#f2fbff"]
BARK = ["#2c1a20", "#41272e", "#583740", "#704a52"]

VAULT = LID_TOP + 2.15            # top of the rounded lid
UMB_FOOT = (8.6, VAULT, 7.6)      # where the umbrella's shaft goes into the lid
UMB_TOP = 20.0                    # canopy apex before the tilt


# ======================================================================================
# Textures
# ======================================================================================

def rain_boards(size: int = 32) -> object:
    """Lavender boards with slanting pale rain streaks painted over them."""
    img = planks(LAV, size=size, rows=4, seed=23, vertical=True, knots=1, seam_dark=1)
    rng = random.Random(5)
    for _ in range(14):
        x, y = rng.randrange(size), rng.randrange(size)
        for i in range(rng.randint(2, 4)):
            put(img, (x + i // 2) % size, (y + i) % size, LAV[6] if i else LAV[7])
    return img


def vault(size: int = 32) -> object:
    """The lid's light blue boards, laid across, with a pink pinstripe on every board."""
    img = planks(SKY, size=size, rows=4, seed=29, vertical=False, knots=0, seam_dark=1)
    for x in range(size):
        for y in (2, 18):
            put(img, x, y, PINK[5] if (x // 4) % 2 else PINK[4])
    return img


def trim(pal, size: int = 16) -> object:
    """A painted moulding: lit edge, dark edge, fine centre groove."""
    img = canvas(size)
    n = len(pal)
    for y in range(size):
        for x in range(size):
            if x <= 1:
                c = pal[n - 1] if x == 0 else pal[n - 2]
            elif x >= size - 2:
                c = pal[1] if x == size - 1 else pal[2]
            elif x in (7, 8):
                c = pal[n - 4]
            else:
                c = pal[n - 3] if (x + y) % 9 else pal[n - 2]
            put(img, x, y, c)
    return img


def post(size: int = 16) -> object:
    """A round pink corner post: lit on one side, rings every few texels."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 5 if x < 4 else 4 if x < 10 else 3
            if y % 8 == 0:
                tone -= 2
            elif y % 8 == 1:
                tone += 1
            put(img, x, y, PINK[max(0, min(7, tone))])
    return img


def pewter(size: int = 16) -> object:
    img = canvas(size)
    rng = random.Random(8)
    for y in range(size):
        for x in range(size):
            tone = 5 if y == 0 else 4 if y < 6 else 3
            if y >= size - 2:
                tone = 2
            if rng.random() < 0.12:
                tone = max(2, tone - 1)
            put(img, x, y, PEWTER[tone])
    for _ in range(4):
        put(img, rng.randrange(1, size - 1), rng.randrange(1, 5), PEWTER[7])
    return img


def gore(pal, seed: int):
    """An umbrella panel: a soft fold down the middle, a lit hem and a passing glint."""
    def paint(t: float) -> object:
        img = canvas(16)
        rng = random.Random(seed)
        for y in range(16):
            for x in range(16):
                d = abs(x + 0.5 - 8) / 8
                tone = 5 if d < 0.25 else 4 if d < 0.7 else 3
                if y >= 14:
                    tone = 6 if y == 14 else 2
                if rng.random() < 0.06:
                    tone -= 1
                put(img, x, y, pal[tone])
        return shine(img, t, colour="#ffffff", width=4.0, strength=0.45, pause=0.55)
    return paint


def cloud(t: float) -> object:
    """The cloud's puffs: white tops, lavender undersides and a slow light sweep."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 6 if y < 6 else 5 if y < 11 else 4
            if (x * 3 + y * 5) % 17 == 0:
                tone -= 1
            put(img, x, y, CLOUD[tone])
    return shine(img, t, colour="#ffffff", width=5.0, strength=0.35, pause=0.4)


def drop(t: float) -> object:
    """A raindrop: clear blue, a white highlight and light running down it."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            tone = 4 if x < 6 else 3 if x < 12 else 2
            put(img, x, y, RAIN[tone])
    for (x, y) in ((3, 3), (4, 3), (3, 4), (4, 4), (5, 5)):
        put(img, x, y, RAIN[6])
    p = (t * 22) % 22 - 3
    for y in range(16):
        if abs(y - p) < 1.6:
            for x in range(1, 8):
                put(img, x, y, RAIN[5])
    return img


def blossom(t: float) -> object:
    """A cherry blossom petal face: pink with a pale edge and a twinkling heart."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            tone = 6 if d > 6.5 else 5 if d > 3.5 else 4
            put(img, x, y, PINK[tone])
    for (x, y) in ((7, 7), (8, 7), (7, 8), (8, 8)):
        put(img, x, y, "#f7d65a")
    sparkle(img, 8, 8, wave(t), colour="#ffffff", reach=3)
    return img


def bark(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 3 if x < 5 else 2 if x < 11 else 1
            if (y * 3 + x) % 7 == 0:
                tone -= 1
            put(img, x, y, BARK[max(0, tone)])
    return img


def bud(size: int = 16) -> object:
    """A closed blossom bud / leaf tip: soft green fading to pink."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            c = "#8fc48a" if y > 9 else PINK[4] if y > 4 else PINK[5]
            put(img, x, y, c)
    return img


def shield(size: int = 16) -> object:
    """The pewter shield behind the cloud: bevelled edge, four rivets."""
    img = canvas(size)
    n = len(PEWTER)
    for y in range(size):
        for x in range(size):
            if x <= 1 or y <= 1:
                c = PEWTER[n - 1] if (x == 0 or y == 0) else PEWTER[n - 2]
            elif x >= size - 2 or y >= size - 2:
                c = PEWTER[1] if (x == size - 1 or y == size - 1) else PEWTER[2]
            else:
                d = math.hypot(x + 0.5 - 8, y + 0.5 - 6)
                c = DEEP[4] if d < 4 else DEEP[3] if d < 7 else DEEP[2]
                if (x * 3 + y) % 13 == 0:
                    c = DEEP[5]
            put(img, x, y, c)
    for rx, ry in ((3, 3), (11, 3), (3, 11), (11, 11)):
        put(img, rx, ry, PEWTER[7])
        put(img, rx + 1, ry + 1, PEWTER[1])
    return img


def backing(size: int = 16) -> object:
    """Deep lavender enamel with a pewter rim, behind the cloud."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            c = PEWTER[5] if d > 7.2 else DEEP[1] if d > 6.2 else DEEP[3] if (x + y) % 6 else DEEP[4]
            put(img, x, y, c)
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(GLOW, t)


def textures() -> None:
    save(rain_boards(), "boards")
    save(vault(), "lidboards")
    save(trim(SKY), "skytrim")
    save(trim(LAV), "lavtrim")
    save(post(), "post")
    save(pewter(), "pewter")
    save(metal(PEWTER[:7], rivets=(3, 12)), "band")
    save(shield(), "shield")
    save(backing(), "backing")
    save(bark(), "bark")
    save(bud(), "bud")
    save_animation(animate(gore(PINK, 3), 12), "gore_pink", frametime=3)
    save_animation(animate(gore(LAV, 4), 12), "gore_lav", frametime=3)
    save_animation(animate(cloud, 12), "cloud", frametime=4)
    save_animation(animate(drop, 10), "drop", frametime=3)
    save_animation(animate(blossom, 10), "blossom", frametime=3, interpolate=True)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def flower(c, s: float, tex: str = "blossom", plane: str = "xy", petals: int = 5, spin: float = 0.0) -> list[dict]:
    """A five-petal blossom facing out of `plane`'s normal, centred on c."""
    parts = []
    cx, cy, cz = c
    for k in range(petals):
        a = spin + k * 360.0 / petals
        p = box((cx - s * 0.42, cy, cz - 0.06), (cx + s * 0.42, cy + s, cz + 0.06), tex, uv="full")
        turn(p, a, "z", (cx, cy, cz))
        parts.append(p)
    parts.append(box((cx - s * 0.22, cy - s * 0.22, cz - 0.02), (cx + s * 0.22, cy + s * 0.22, cz + 0.12), "bud",
                     uv="full"))
    if plane == "xz":
        turn(parts, -90, "x", (cx, cy, cz))
    return parts


def sprig(pts, z: float, blossoms, out: float = 0.0) -> list[dict]:
    """A blossom twig along pts in the plane z, with flowers at the given point indexes."""
    parts = []
    for (ax, ay), (bx, by) in zip(pts, pts[1:]):
        parts.append(bar((ax, ay, z), (bx, by, z), 0.24, 0.22, "bark"))
    for i, s in blossoms:
        x, y = pts[i]
        parts += flower((x, y, z + 0.14 + out), s, spin=i * 17.0)
    return parts


def vault_lid() -> list[dict]:
    """The rounded barrel lid: three stepped bands of light blue boards, pewter straps."""
    parts = []
    steps = ((LID_TOP, LID_TOP + 0.95, 1.0), (LID_TOP + 0.95, LID_TOP + 1.65, 2.6), (LID_TOP + 1.65, VAULT, 4.6))
    for (y0, y1, inset) in steps:
        parts.append(box((L0, y0 - 0.01, L0 + inset), (L1, y1, L1 - inset), "lidboards", skip=("down",)))
    # a lavender edge moulding along the top step
    for z in (L0 + 4.6, L1 - 4.6):
        parts.append(box((L0 - 0.08, VAULT - 0.22, z - 0.12), (L1 + 0.08, VAULT + 0.06, z + 0.12), "lavtrim"))
    # pewter straps over the vault (follow the steps)
    for x in (2.6, 13.4):
        for (y0, y1, inset) in steps:
            parts.append(box((x - 0.55, y1 - 0.02, L0 + inset - 0.12), (x + 0.55, y1 + 0.14, L1 - inset + 0.12),
                             "band"))
            for z in (L0 + inset, L1 - inset):
                parts.append(only((x - 0.55, y0, z - 0.14 if z < 8 else z), (x + 0.55, y1 + 0.14,
                                  z if z < 8 else z + 0.14), {"north": "band", "south": "band"}))
    return parts


def umbrella() -> list[dict]:
    """An open umbrella standing in the lid, tipped back a little: a domed canopy of eight
    gores (three panels each, so it curves), pewter ribs with silver tips, a ferrule on top
    and a pink bow tied round the shaft."""
    fx, fy, fz = UMB_FOOT
    parts = [bar((fx, fy - 0.4, fz), (fx, UMB_TOP, fz), 0.36, 0.36, "pewter")]
    r, ribs = 5.0, 8
    # canopy profile: (fraction of the radius, drop below the apex)
    prof = [(0.0, 0.0), (0.38, 0.45), (0.72, 1.25), (1.0, 2.35)]

    def at(ang, f, dy, k=1.0):
        return (fx + r * f * k * math.cos(ang), UMB_TOP - dy, fz + r * f * k * math.sin(ang))

    for g in range(ribs):
        a = math.radians(g * 360.0 / ribs)
        a2 = math.radians((g + 1) * 360.0 / ribs)
        am = (a + a2) / 2
        tex = "gore_pink" if g % 2 == 0 else "gore_lav"
        roll = 90.0 - math.degrees(am)
        k = math.cos(math.pi / ribs)
        for (f0, d0), (f1, d1) in zip(prof, prof[1:]):
            w = 2 * r * f1 * math.sin(math.pi / ribs) * 1.06 + 0.1
            parts.append(bar(at(am, f0, d0, k), at(am, f1, d1, k), w, 0.16, tex, roll=roll, uv="full"))
        # rib along the gore's edge and its tip
        for (f0, d0), (f1, d1) in zip(prof, prof[1:]):
            parts.append(bar(at(a, f0, d0 - 0.06), at(a, f1, d1 - 0.06), 0.16, 0.16, "pewter"))
        tx, ty, tz = at(a, 1.0, 2.35)
        parts.append(box((tx - 0.2, ty - 0.45, tz - 0.2), (tx + 0.2, ty + 0.05, tz + 0.2), "band"))
    parts += prism((fx, UMB_TOP + 0.5, fz), 0.2, 1.1, "pewter", sides=8, cap="band")
    parts += prism((fx, UMB_TOP + 0.12, fz), 0.55, 0.35, "gore_pink", sides=8, cap="gore_pink")
    parts += prism((fx, UMB_TOP + 1.12, fz), 0.3, 0.3, "band", sides=8, cap="band")
    # the runner and a pink bow on the shaft
    parts += prism((fx, UMB_TOP - 2.9, fz), 0.32, 0.5, "band", sides=8, cap="band")
    for sx in (-1, 1):
        loop = box((fx - 0.05, fy + 1.0, fz - 0.45), (fx + 0.9 * sx, fy + 1.7, fz + 0.45), "gore_pink", uv="full")
        turn(loop, 18 * sx, "z", (fx, fy + 1.35, fz))
        parts.append(loop)
    parts.append(box((fx - 0.3, fy + 1.1, fz - 0.3), (fx + 0.3, fy + 1.6, fz + 0.3), "gore_pink", uv="full"))
    turn(parts, -12, "x", (fx, fy, fz))
    turn(parts, 9, "z", (fx, fy, fz))
    return parts


def cloud_emblem() -> list[dict]:
    """The rain cloud on its pewter shield, over the seam, and the raindrop keyhole."""
    z0 = B1 - 0.05
    zf = 16.02
    parts = [box((5.4, 5.0, z0), (10.6, 12.6, zf), "shield",
                 uv={"north": [0, 0, 4, 4], "east": [0, 0, 1, 4], "west": [0, 0, 1, 4], "up": [0, 0, 4, 1],
                     "down": [0, 0, 4, 1], "south": [0, 0, 16, 16]})]
    # the shield's top is cloud-shaped: deep lavender puffs a little larger than the cloud's
    for (x, y, r) in ((6.3, 11.4, 1.5), (8.1, 12.6, 1.95), (9.9, 11.6, 1.55)):
        parts += prism((x, y, (z0 + zf) / 2 + 0.05), r, zf - z0 - 0.1, "backing", axis="z", sides=8,
                       cap="backing")
    # the cloud: puffs in front of the shield's top half
    cz = zf + 0.03
    for (x, y, r, d, n) in ((6.3, 11.4, 1.15, 0.16, 8), (8.1, 12.6, 1.6, 0.22, 16), (9.9, 11.6, 1.2, 0.16, 8)):
        parts += prism((x, y, cz + d / 2 - 0.05), r, d, "cloud", axis="z", sides=n, cap="cloud")
    parts.append(box((5.4, 10.25, cz - 0.08), (10.6, 11.2, cz + 0.12), "cloud", uv="full"))
    # three drops falling out of it
    for (x, y) in ((6.5, 9.4), (8.0, 8.9), (9.5, 9.4)):
        d = box((x - 0.26, y - 0.26, cz - 0.08), (x + 0.26, y + 0.26, cz + 0.16), "drop", uv="full", glow=6)
        turn(d, 45, "z", (x, y, cz))
        parts.append(d)
        parts.append(box((x - 0.12, y + 0.18, cz - 0.04), (x + 0.12, y + 0.62, cz + 0.12), "drop", uv="full", glow=6))
    # the raindrop keyhole
    parts += prism((8.0, 6.5, zf + 0.0), 0.62, 0.08, "keyhole", axis="z", sides=8, cap="keyhole", glow=11,
                   shade=False)
    tip = box((7.62, 6.6, zf - 0.03), (8.38, 7.36, zf + 0.04), "keyhole", glow=11, shade=False, uv="full")
    turn(tip, 45, "z", (8.0, 6.98, zf))
    parts.append(tip)
    # a pewter rim round the keyhole
    parts += arc((8.0, 6.5, zf + 0.06), 0.85, 0, 360, 8, 0.22, 0.14, "band", plane="xy")
    return parts


def walls() -> list[dict]:
    """Light blue panel frames on every wall; blossom sprigs climb the sides."""
    parts = []
    y0, y1 = 1.5, SEAM - 1.3
    t = 0.45

    def frame_front(a0, a1):
        z0, z1 = B1, B1 + 0.26
        return [box((a0, y1 - t, z0), (a1, y1, z1), "skytrim"), box((a0, y0, z0), (a1, y0 + t, z1), "skytrim"),
                box((a0, y0 + t, z0), (a0 + t, y1 - t, z1), "skytrim"),
                box((a1 - t, y0 + t, z0), (a1, y1 - t, z1), "skytrim")]

    for a0, a1 in ((1.9, 5.0), (11.0, 14.1)):
        parts += frame_front(a0, a1)
        cx = (a0 + a1) / 2
        # a raindrop pendant in each front panel
        parts += prism((cx, 4.4, B1 + 0.3), 0.62, 0.26, "drop", axis="z", sides=8, cap="drop", glow=4)
        d = box((cx - 0.44, 4.4, B1 + 0.18), (cx + 0.44, 5.28, B1 + 0.4), "drop", uv="full", glow=4)
        turn(d, 45, "z", (cx, 4.84, B1 + 0.3))
        parts.append(d)
    # side walls: the same frame and a blossom sprig, built on the front and turned round
    side = []
    for a0, a1 in ((2.0, 14.0),):
        side += frame_front(a0, a1)
    side += sprig([(3.2, 1.9), (4.6, 3.4), (6.0, 4.0), (7.6, 5.6), (9.6, 6.0), (11.2, 7.2), (12.6, 7.0)],
                  B1 + 0.28, ((1, 0.62), (3, 0.8), (5, 0.66)))
    side += sprig([(7.6, 5.6), (8.2, 3.6), (9.4, 2.8)], B1 + 0.28, ())
    for ang in (90, -90):
        parts += turn(copy(side), ang, "y", (8.0, 0.0, 8.0))
    # pink corner posts with pewter caps
    for x in (B0, B1):
        for z in (B0, B1):
            parts += prism((x, SEAM / 2, z), 0.62, SEAM, "post", sides=8, cap="pewter")
            parts.append(box((x - 0.75, SEAM - 0.45, z - 0.75), (x + 0.75, SEAM, z + 0.75), "pewter"))
            parts.append(box((x - 0.75, 0.0, z - 0.75), (x + 0.75, 0.55, z + 0.75), "pewter", skip=("down",)))
    return parts


def lid_blossoms() -> list[dict]:
    """Cherry blossom sprigs over the two front corners of the lid."""
    parts = []
    z = L1 + 0.05
    parts += sprig([(0.9, SEAM + 0.8), (2.0, SEAM + 2.2), (2.6, SEAM + 3.6), (3.9, LID_TOP - 0.4)], z,
                   ((1, 0.7), (2, 0.55), (3, 0.62)))
    parts += sprig([(15.1, SEAM + 0.8), (14.0, SEAM + 2.4), (13.4, SEAM + 3.4), (12.1, LID_TOP - 0.5)], z,
                   ((1, 0.6), (2, 0.72), (3, 0.55)))
    # blossoms tucked on the vault, lying on it
    for (x, z2, s) in ((4.4, 12.4, 0.62), (12.0, 3.6, 0.58)):
        y = LID_TOP + 1.0 if z2 > 12 or z2 < 4 else LID_TOP + 1.7
        parts += flower((x, y + 0.05, z2), s, plane="xz", spin=x * 30)
    return parts


def raindrops() -> list[dict]:
    """Fat raindrops hanging off the lid's edge on every side."""
    parts = []
    y = SEAM - 0.05

    def drip(x, z, ln):
        d = [box((x - 0.14, y - ln - 0.25, z - 0.14), (x + 0.14, y + 0.05, z + 0.14), "drop", uv="full", glow=4),
             box((x - 0.34, y - ln - 0.95, z - 0.34), (x + 0.34, y - ln - 0.25, z + 0.34), "drop", uv="full", glow=4)]
        turn(d, 45, "y", (x, y, z))
        return d

    for x, ln in ((5.1, 0.5), (10.9, 0.9)):
        parts += drip(x, L1 - 0.05, ln)
    for x, ln in ((5.0, 1.0), (11.0, 0.6)):
        parts += drip(x, L0 + 0.05, ln)
    for zz, ln in ((5.0, 0.7), (11.2, 1.1)):
        parts += drip(L1 - 0.05, zz, ln)
        parts += drip(L0 + 0.05, 16.0 - zz, ln * 1.2)
    return parts


def back_hinges() -> list[dict]:
    parts = []
    for x in (4.0, 12.0):
        for dx in (-0.75, 0.75):
            parts += prism((x + dx, SEAM, L0 - 0.05), 0.5, 0.7, "pewter", axis="x", sides=8, cap="band")
        parts.append(box((x - 1.0, SEAM - 2.6, B0 - 0.22), (x + 1.0, SEAM - 0.3, B0), "band"))
        parts.append(box((x - 1.0, SEAM + 0.3, L0 - 0.22), (x + 1.0, SEAM + 3.2, L0), "band"))
        parts += prism((x, SEAM + 2.4, L0 - 0.26), 0.45, 0.1, "blossom", axis="z", sides=8, cap="blossom")
    return parts


def base() -> list[dict]:
    return [box((B0 - 0.28, 0.0, B0 - 0.28), (B1 + 0.28, 0.85, B1 + 0.28), "band", skip=("down",)),
            box((B0 - 0.1, 0.85, B0 - 0.1), (B1 + 0.1, 1.2, B1 + 0.1), "lavtrim", skip=("down",))]


def models() -> dict:
    p = shell("boards", "lidboards", "lidboards")
    p += rims("band", e=0.2)[1:]
    p += base()
    p += walls()
    p += vault_lid()
    p += lid_blossoms()
    p += umbrella()
    p += raindrops()
    p += cloud_emblem()
    p += back_hinges()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
