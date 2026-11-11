"""June Chest (issue #1482, design in #1407): the monthly crate chest for June, a sunny
summer beach chest in turquoise, ocean blue and sand gold.

A body of turquoise painted boards standing on a band of ocean blue surf (rolling waves
with white foam crests all the way round), bound in twisted sand gold rope at the seam
and the lid's top edge, with rope-wrapped corners. One side wall carries a turquoise and
white life ring, the other a coral starfish and two seashells. The lid is a little beach:
a sand dune with ripples, a woven straw hat with a turquoise band lying tipped on it,
a scallop shell, a coral starfish and a pearl, and a small tropical palm sprig at the
back corner.

The emblem is the summer sun on the lid's front: a gold disc with a glowing face ringed
by long and short sand gold rays. Below it a sand gold lock plate across the seam with a
big pale scallop shell set into it and a glowing turquoise keyhole at the shell's hinge.
Two rope-lashed gold hinges hold the lid at the back.

Animation (one loop): the surf rolls along the base, the sun's face pulses warm, a glint
crosses the pearl and the shells, and the keyhole glows like shallow water.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block. Unlike the other crate chests: low and wide, a straw hat
and a palm sprig on a sandy lid instead of a crown, spire, bow, clover, umbrella or flowers.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM,  # noqa: F401
                                    keyhole_glow, only, planks, put, shell)
from art.kit import animate, arc, bar, box, canvas, copy, display, model, prism, save, save_animation, shine, \
    sparkle, turn, wave

ID = "june_chest"
NAME = "June Chest"

TURQ = ["#0b3c41", "#0f585d", "#13757a", "#1f9393", "#35b0aa", "#5ccbbf", "#93e3d5", "#cff5ed"]
OCEAN = ["#0a1e48", "#122f6c", "#1b4591", "#295eb3", "#3e7ace", "#619fe5", "#93c4f3", "#d6ecff"]
SAND = ["#5a3e12", "#82601c", "#a7812b", "#c8a13e", "#dfbc5b", "#eed380", "#f7e7ae", "#fff6da"]
STRAW = ["#4a2a0c", "#6e4214", "#93601f", "#b57d2c", "#cf9a3e", "#e2b75a", "#f0d389"]
CORAL = ["#6e2414", "#9c391c", "#c9562a", "#e97b40", "#f6a062", "#fcc79a"]
SHELL = ["#8a4a46", "#c06a68", "#e9958a", "#f6c3a6", "#fde0c6", "#fff5ea"]
SUN = ["#8a3a04", "#c25a06", "#ec8a10", "#ffb52a", "#ffd84a", "#ffec8c", "#fffbe0"]
GLOW = ["#042326", "#073f44", "#0c656b", "#18938f", "#3cc3b4", "#8ae9db", "#eafffb"]

SAND_TOP = LID_TOP + 0.5            # top of the dune on the lid
HAT = (9.4, SAND_TOP + 0.75, 8.8)    # straw hat brim centre


# ======================================================================================
# Textures
# ======================================================================================

def boards(size: int = 32) -> object:
    return planks(TURQ[2:], size=size, rows=4, seed=53, vertical=True, knots=0, seam_dark=0)


def lid_boards(size: int = 32) -> object:
    """The lid's sides: turquoise boards across with a white painted stripe."""
    img = planks(TURQ[2:], size=size, rows=3, seed=59, vertical=False, knots=0, seam_dark=0)
    for x in range(size):
        for y in (3, 4, 5, 6, 7):
            put(img, x, y, "#f2fbff" if y == 3 else OCEAN[4] if y < 7 else OCEAN[2])
    return img


def surf(t: float) -> object:
    """Rolling surf: ocean blue water, white foam crests travelling along (seamless)."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            ph = (x / 16 + t) % 1
            crest = 4.5 + 2.2 * math.sin(2 * math.pi * ph)
            if y < crest - 1:
                c = OCEAN[5] if y < crest - 3 else OCEAN[4]
            elif y < crest + 0.6:
                c = "#ffffff" if (x + y) % 3 else OCEAN[7]
            elif y < crest + 2:
                c = OCEAN[6]
            else:
                c = OCEAN[3] if y < 11 else OCEAN[2]
                if (x * 5 + y * 3) % 13 == 0:
                    c = OCEAN[4]
            put(img, x, y, c)
    return img


def rope(size: int = 16) -> object:
    """Twisted sand gold rope: diagonal strands with dark grooves."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            k = (x + y) % 6
            tone = 1 if k == 0 else 5 if k == 1 else 4 if k < 4 else 3
            put(img, x, y, SAND[tone])
    return img


def sand(size: int = 16) -> object:
    """Beach sand with wind ripples."""
    img = canvas(size)
    rng = random.Random(61)
    for y in range(size):
        for x in range(size):
            tone = 5 if (y + (x // 5)) % 5 else 4
            r = rng.random()
            if r < 0.12:
                tone = 6
            elif r < 0.2:
                tone = 3
            put(img, x, y, SAND[tone])
    return img


def gold(size: int = 16) -> object:
    img = canvas(size)
    rng = random.Random(67)
    for y in range(size):
        for x in range(size):
            tone = 5 if y == 0 else 4 if y < 6 else 3
            if y >= size - 2:
                tone = 2
            if rng.random() < 0.1:
                tone = max(2, tone - 1)
            put(img, x, y, SAND[tone])
    for _ in range(4):
        put(img, rng.randrange(1, size - 1), rng.randrange(1, 5), SAND[7])
    return img


def straw(size: int = 16) -> object:
    """Woven straw: a basket weave of light and shaded bands."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            cell = ((x // 4) + (y // 4)) % 2
            k = (y if cell else x) % 4
            tone = 5 if k == 0 else 4 if k < 3 else 2
            if cell:
                tone -= 1
            put(img, x, y, STRAW[max(0, tone)])
    return img


def hatband(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 6 if y < 3 else 4 if y < 12 else 2
            put(img, x, y, TURQ[tone])
    return img


def coral(size: int = 16) -> object:
    """Starfish skin: coral with bumpy pale dots."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 3 if (x + y) % 2 else 2
            if (x * 3 + y * 5) % 7 == 0:
                tone = 5
            put(img, x, y, CORAL[tone])
    return img


def shell_tex(t: float) -> object:
    """Scallop shell: pale ribs fanning from the bottom, darker grooves, a passing glint."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            a = math.atan2(x + 0.5 - 8, 16 - y)
            k = (a * 7 / math.pi) % 1
            tone = 4 if k < 0.55 else 2 if k < 0.7 else 3
            if y < 2:
                tone = 5
            put(img, x, y, SHELL[tone])
    return shine(img, t, colour="#ffffff", width=3.0, strength=0.5, pause=0.5)


def pearl(t: float) -> object:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 6)
            tone = 5 if d < 3 else 4 if d < 7 else 3
            put(img, x, y, SHELL[tone])
    sparkle(img, 5, 5, wave(t), colour="#ffffff", reach=3)
    return img


def sun_face(t: float) -> object:
    """The sun's face: bright yellow, a paler heart that pulses, a one-pixel orange rim."""
    img = canvas(16)
    pulse = wave(t)
    core = ["#ffd84a", "#ffe270", "#ffeb8e", "#fff0a0", "#fff8d0"]
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if d > 7.2:
                c = "#e8891c"
            else:
                v = (1 - d / 7.2) * (0.6 + 0.4 * pulse)
                c = core[min(4, int(v * 5 + 0.3))]
            put(img, x, y, c)
    return img


def sun_ray(size: int = 16) -> object:
    """A gold sun ray: bright on the lit side, warm amber shading."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            c = "#ffd75a" if x < 5 else "#f5c542" if x < 11 else "#e09a2a"
            if y < 2:
                c = "#ffe680"
            put(img, x, y, c)
    return img


def ring(red: bool):
    def paint(size: int = 16) -> object:
        img = canvas(size)
        pal = CORAL + [CORAL[-1]] * 2 if red else ["#a9b8c4", "#c9d4dd", "#dfe7ee", "#eef3f7", "#f7fafc", "#ffffff", "#ffffff",
                                "#ffffff"]
        for y in range(size):
            for x in range(size):
                tone = 6 if y < 4 else 5 if y < 10 else 3
                put(img, x, y, pal[tone])
        return img
    return paint


def frond(size: int = 16) -> object:
    """A palm leaflet card: a midrib with leaflets, clear between them."""
    img = canvas(size)
    leaf = ["#1d4d16", "#2a6a1e", "#3a8a28", "#52a835", "#74c24c"]
    for y in range(size):
        for x in range(size):
            fx = abs(x + 0.5 - 8)
            if fx < 0.8:
                put(img, x, y, leaf[1])
            elif fx < 7.5 * (1 - y / size * 0.6) and (y + int(fx)) % 3 != 0:
                put(img, x, y, leaf[3] if x < 8 else leaf[2])
    return img


def trunk(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 4 if y % 4 == 0 else 3 if x < 8 else 2
            put(img, x, y, STRAW[tone])
    return img


def plate(size: int = 16) -> object:
    img = canvas(size)
    n = len(SAND)
    for y in range(size):
        for x in range(size):
            if x <= 1 or y <= 1:
                c = SAND[n - 1] if (x == 0 or y == 0) else SAND[n - 2]
            elif x >= size - 2 or y >= size - 2:
                c = SAND[1] if (x == size - 1 or y == size - 1) else SAND[2]
            else:
                c = SAND[4] if (x + y) % 7 else SAND[3]
            put(img, x, y, c)
    for rx, ry in ((3, 3), (11, 3), (3, 11), (11, 11)):
        put(img, rx, ry, SAND[7])
        put(img, rx + 1, ry + 1, SAND[1])
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(GLOW, t)


def textures() -> None:
    save(boards(), "boards")
    save(lid_boards(), "lidboards")
    save(rope(), "rope")
    save(sand(), "sand")
    save(gold(), "gold")
    save(straw(), "straw")
    save(hatband(), "hatband")
    save(coral(), "coral")
    save(sun_ray(), "ray")
    save(ring(True)(), "ring_a")
    save(ring(False)(), "ring_b")
    save(frond(), "frond")
    save(trunk(), "trunk")
    save(plate(), "plate")
    save_animation(animate(surf, 16), "surf", frametime=3)
    save_animation(animate(shell_tex, 12), "shell", frametime=3)
    save_animation(animate(pearl, 10), "pearl", frametime=3, interpolate=True)
    save_animation(animate(sun_face, 10), "sun", frametime=3, interpolate=True)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def scallop(c, s: float, normal: str = "z", ribs: int = 7, depth: float = 0.14, spread: float = 150.0,
            **kw) -> list[dict]:
    """A scallop shell: a fan of ribs from a hinge at c (opening up +Y), facing +Z, then laid
    flat when normal = "y"."""
    x, y, z = c
    parts = []
    for k in range(ribs):
        a = -spread / 2 + spread * k / (ribs - 1)
        r = box((x - s * 0.17, y, z), (x + s * 0.17, y + s, z + depth), "shell", uv="full", **kw)
        turn(r, a, "z", (x, y, z))
        parts.append(r)
    parts.append(box((x - s * 0.32, y - s * 0.12, z - 0.01), (x + s * 0.32, y + s * 0.16, z + depth + 0.02),
                     "shell", uv="full", **kw))
    if normal == "y":
        turn(parts, -90, "x", (x, y, z))
    return parts


def starfish(c, s: float, normal: str = "y", spin: float = 0.0) -> list[dict]:
    """A five-armed starfish lying on c (normal y) or stuck on a wall facing +Z."""
    x, y, z = c
    parts = []
    for k in range(5):
        a = math.radians(spin + k * 72)
        parts.append(bar((x, y, z), (x + s * math.cos(a), y + s * math.sin(a), z), 0.36 * s, 0.18, "coral"))
    parts += prism((x, y, z), 0.3 * s, 0.24, "coral", axis="z", sides=8, cap="coral")
    if normal == "y":
        turn(parts, -90, "x", (x, y, z))
    return parts


def beach() -> list[dict]:
    """The sand dune on the lid with its treasures."""
    parts = [box((L0 - 0.1, LID_TOP - 0.01, L0 - 0.1), (L1 + 0.1, SAND_TOP, L1 + 0.1), "sand", skip=("down",))]
    for (x0, z0, x1, z1, h) in ((1.4, 1.2, 7.6, 6.0, 0.35), (6.4, 4.4, 13.8, 12.4, 0.25), (2.0, 9.0, 6.6, 14.0, 0.3),
                                (10.4, 1.4, 14.6, 5.0, 0.45)):
        parts.append(box((x0, SAND_TOP - 0.02, z0), (x1, SAND_TOP + h, z1), "sand", skip=("down",)))
    # the straw hat, lying tipped on the dune
    hx, hy, hz = HAT
    hat = prism((hx, hy, hz), 4.3, 0.3, "straw", sides=16, cap="straw")
    hat += prism((hx, hy + 0.12, hz), 3.6, 0.3, "straw", sides=16, cap="straw")
    hat += prism((hx, hy + 1.05, hz), 2.2, 1.8, "straw", sides=16, cap="straw")
    hat += prism((hx, hy + 2.05, hz), 1.7, 0.3, "straw", sides=8, cap="straw")
    hat += prism((hx, hy + 0.5, hz), 2.28, 0.7, "hatband", sides=16, cap="hatband")
    # a bow on the band and a little shell tucked into it
    hat.append(box((hx - 0.5, hy + 0.3, hz + 2.2), (hx + 0.5, hy + 0.95, hz + 2.45), "hatband"))
    for sx in (-1, 1):
        lp = box((hx, hy + 0.35, hz + 2.25), (hx + 1.0 * sx, hy + 0.9, hz + 2.4), "hatband")
        turn(lp, -25 * sx, "z", (hx, hy + 0.62, hz + 2.3))
        hat.append(lp)
    hat += scallop((hx + 1.4, hy + 0.3, hz + 1.85), 0.75, ribs=5, depth=0.1)
    # a little sand heap props the back of the brim up, so the hat tips toward the front
    parts.append(box((hx - 2.4, SAND_TOP - 0.02, hz - 3.6), (hx + 2.4, SAND_TOP + 0.75, hz - 2.0), "sand",
                     skip=("down",)))
    turn(hat, 12, "x", (hx, hy, hz))
    turn(hat, -7, "z", (hx, hy, hz))
    parts += hat
    # shells, a starfish and a pearl on the sand
    parts += scallop((4.2, SAND_TOP + 0.36, 12.0), 1.5, normal="y")
    parts += starfish((4.6, SAND_TOP + 0.45, 4.2), 1.3, spin=20)
    parts += prism((6.0, SAND_TOP + 0.62, 11.0), 0.38, 0.5, "pearl", sides=8, cap="pearl", uv="full")
    parts += scallop((12.8, SAND_TOP + 0.3, 13.6), 0.9, normal="y")
    # a small palm sprig at the back-left corner
    tx, tz = 2.5, 3.0
    pts = [(tx, SAND_TOP + 0.1, tz), (tx + 0.2, SAND_TOP + 1.6, tz + 0.1), (tx + 0.65, SAND_TOP + 3.0, tz + 0.3),
           (tx + 1.3, SAND_TOP + 4.1, tz + 0.55)]
    for a, b, w in zip(pts, pts[1:], (0.62, 0.54, 0.46)):
        parts.append(bar(a, b, w, w, "trunk"))
    top = pts[-1]
    for k in range(6):
        a = k * 60 + 20
        # lay the leaf out from the crown, drooping
        f = box((top[0] - 1.0, top[1], top[2]), (top[0] + 1.0, top[1] + 0.03, top[2] + 3.9), "frond", uv="full",
                skip=("north", "south", "east", "west"))
        turn(f, 32, "x", top)
        turn(f, a, "y", top)
        parts.append(f)
    parts += prism((top[0], top[1] - 0.1, top[2]), 0.35, 0.35, "coral", sides=8, cap="coral")
    return parts


def sun_emblem() -> list[dict]:
    """The summer sun on the lid's front: a glowing face, long and short rays."""
    cx, cy, z = 8.0, 12.0, 16.02
    parts = prism((cx, cy, z + 0.08), 1.3, 0.16, "sun", axis="z", sides=16, cap="sun", glow=9, uv="full")
    parts += arc((cx, cy, z + 0.1), 1.4, 0, 360, 12, 0.3, 0.2, "ray", plane="xy", glow=5)
    for k in range(12):
        ln = 2.75 if k % 2 == 0 else 2.0
        w = 0.66 if k % 2 == 0 else 0.42
        r = box((cx - w / 2, cy + 1.4, z - 0.02), (cx + w / 2, cy + ln, z + 0.1), "ray", glow=5)
        turn(r, k * 30, "z", (cx, cy, z))
        parts.append(r)
    return parts


def lock() -> list[dict]:
    """A sand gold plate across the seam with a big scallop shell and a turquoise keyhole."""
    z0, zf = B1 - 0.05, 16.02
    parts = [box((5.4, 4.6, z0), (10.6, 10.8, zf), "plate",
                 uv={"north": [0, 0, 4, 4], "east": [0, 0, 1, 4], "west": [0, 0, 1, 4], "up": [0, 0, 4, 1],
                     "down": [0, 0, 4, 1], "south": [0, 0, 16, 16]})]
    # gold frame round the plate
    for (a, b) in (((5.2, 10.6), (10.8, 11.0)), ((5.2, 4.4), (10.8, 4.8)), ((5.2, 4.8), (5.6, 10.6)),
                   ((10.4, 4.8), (10.8, 10.6))):
        parts.append(box((a[0], a[1], zf - 0.1), (b[0], b[1], zf + 0.12), "gold"))
    # the keyhole, ringed in rope, in the plate's middle
    parts += prism((8.0, 8.35, zf + 0.0), 0.62, 0.08, "keyhole", axis="z", sides=8, cap="keyhole", glow=11, uv="full",
                   shade=False)
    parts.append(box((7.7, 7.1, zf - 0.03), (8.3, 8.3, zf + 0.04), "keyhole", glow=11, shade=False, uv="full"))
    parts += arc((8.0, 8.0, zf + 0.06), 1.25, 0, 360, 10, 0.28, 0.14, "rope", plane="xy")
    # a small scallop shell under it
    parts += scallop((8.0, 4.95, zf + 0.02), 1.55, ribs=7, depth=0.1, spread=130)
    return parts


def walls() -> list[dict]:
    """The surf band, rope corners, the life ring and the starfish wall."""
    parts = [box((B0 - 0.12, 0.0, B0 - 0.12), (B1 + 0.12, 2.6, B1 + 0.12), "surf", uv="full", skip=("down",),
                 faces={"up": "gold"})]
    # rope: the seam, the lid's top edge, the base
    for (y0, y1, lo, hi) in ((SEAM - 0.7, SEAM, B0 - 0.2, B1 + 0.2), (LID_TOP - 0.75, LID_TOP, L0 - 0.18, L1 + 0.18),
                             (2.6, 3.2, B0 - 0.18, B1 + 0.18)):
        parts.append(box((lo, y0, lo), (hi, y1, hi), "rope", skip=("up", "down")))
    # rope-wrapped corner posts
    for x in (B0, B1):
        for z in (B0, B1):
            parts += prism((x, SEAM / 2 + 1.4, z), 0.58, SEAM - 2.8, "rope", sides=8, cap="gold")
    # east wall: life ring; west wall: starfish and shells (built on the front, turned round)
    ringw = []
    for k in range(12):
        t = "ring_a" if (k // 3) % 2 == 0 else "ring_b"
        ringw += arc((8.0, 5.9, B1 + 0.3), 1.9, k * 30, k * 30 + 30, 1, 0.85, 0.5, t, plane="xy")
    ringw += arc((8.0, 5.9, B1 + 0.58), 1.9, 0, 360, 8, 0.12, 0.08, "rope", plane="xy")
    parts += turn(ringw, 90, "y", (8.0, 0.0, 8.0))
    star = starfish((6.6, 6.0, B1 + 0.22), 1.55, normal="z", spin=12)
    star += scallop((10.6, 4.2, B1 + 0.02), 1.4, ribs=7)
    star += scallop((12.4, 6.6, B1 + 0.02), 0.9, ribs=5)
    parts += turn(star, -90, "y", (8.0, 0.0, 8.0))
    # small shells on the two front panels
    for x in (3.4, 12.6):
        parts += scallop((x, 4.2, B1 + 0.02), 1.4, ribs=7)
    return parts


def back_hinges() -> list[dict]:
    parts = []
    for x in (4.0, 12.0):
        parts += prism((x, SEAM, L0 - 0.05), 0.5, 2.2, "gold", axis="x", sides=8, cap="plate")
        for dx in (-1.3, 1.3):
            parts += prism((x + dx, SEAM, L0 + 0.05), 0.55, 0.42, "rope", axis="x", sides=8, cap="rope")
        parts.append(box((x - 0.9, SEAM - 2.5, B0 - 0.22), (x + 0.9, SEAM - 0.3, B0), "plate"))
        parts.append(box((x - 0.9, SEAM + 0.3, L0 - 0.22), (x + 0.9, SEAM + 3.0, L0), "plate"))
    return parts


def models() -> dict:
    p = shell("boards", "lidboards", "sand")
    p += walls()
    p += beach()
    p += sun_emblem()
    p += lock()
    p += back_hinges()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
