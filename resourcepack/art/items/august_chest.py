"""August Chest (issue #1495, design in #1407): the monthly crate chest for August, a
golden-hour summer sunset chest in golden yellow, warm amber, sunset orange, sandy beige
and muted coral.

A body of pale sandy beige boards laid across, banded at the base by a sunset gradient
(coral up through orange to gold) and framed in deep warm amber metal: a plinth, a seam
band, upright straps either side of the lock and stepped corner plates with round golden
sun rivets. The lid is a rounded vault running front to back, its boards banded like the
evening sky: muted coral at the eaves, then sunset orange, warm amber and gold along the
crown.

The emblem is the sunset itself, set into the vault's front arch: a large glowing golden
half sun sitting on the horizon (the lid's front edge) in front of a sky that fades from
gold through amber and orange to coral and dusk, with layered rays (long gold ones behind,
short pale gold ones in front) that break out past the arch's amber rim. Raised gold ribs
carry each long ray on over the vault to the back. Below it a round sun lock across the
seam, a golden face on an amber disc ringed with bold rays, with a glowing ember keyhole.
Sprays of orange and golden summer flowers with coral hearts grow up both side walls, and
two amber ray-fan hinges hold the lid at the back.

Animation (one loop): the half sun breathes warm light, the rays shimmer, and the
keyhole glows like an ember.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block. Unlike the other crate chests: a low rounded vault with
the sunset in its arch and sunbeam ribs, the lowest of the monthly chests (no crown,
spire or standing ornament).
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM,  # noqa: F401
                                    keyhole_glow, planks, put, shell)
from art.kit import animate, arc, bar, box, canvas, copy, display, model, prism, save, save_animation, shine, \
    turn, wave

ID = "august_chest"
NAME = "August Chest"

SANDY = ["#6a4a26", "#8e6a3c", "#b6884c", "#d0a464", "#e2bb7c", "#ecca92", "#f4d9ac", "#fbecd0"]
AMBER = ["#3c1d06", "#5e2f0a", "#834510", "#a95e17", "#c97a22", "#e19a36", "#f0b955", "#fbd885"]
GOLDEN = ["#7a4d06", "#a8700c", "#d09514", "#ebb424", "#f8cc3c", "#ffdf6a", "#fff0a6", "#fffbe2"]
ORANGE = ["#6a2406", "#94360a", "#bd4c10", "#dd6618", "#f08228", "#f8a048", "#fcc07a"]
CORAL = ["#5e2426", "#833638", "#a84c4a", "#c4655c", "#d8806f", "#e69e88", "#f0bea7"]
DUSK = ["#6e3a54", "#8a4658"]
PALE = ["#c98e1c", "#f2c544", "#ffe58a", "#fff3c0", "#fffbe8"]
LEAF = ["#2c3a12", "#3f5318", "#566e20", "#70892c", "#8ea43e"]
GLOW = ["#2a0c02", "#5a1c04", "#93330a", "#d05814", "#f48a2a", "#ffc163", "#fff1c4"]

ARCH_H = 3.7                                     # how far the vault rises over the lid
_CHORD = (L1 - L0) / 2
R = (_CHORD ** 2 + ARCH_H ** 2) / (2 * ARCH_H)  # vault radius
CY = LID_TOP + ARCH_H - R                        # vault centre height
SPAN = math.degrees(math.asin(_CHORD / R))       # half the vault's angle
SUN = (8.0, LID_TOP)                             # the sun sits on the horizon line
SUN_R = 2.95


# ======================================================================================
# Textures
# ======================================================================================

def boards(size: int = 32) -> object:
    """Sandy beige boards laid across, with a muted coral pinstripe on every other board."""
    img = planks(SANDY[2:], size=size, rows=4, seed=101, vertical=False, knots=0, seam_dark=0)
    for x in range(size):
        put(img, x, 12, CORAL[5])
    return img


def lid_boards(size: int = 32) -> object:
    img = planks(SANDY[2:], size=size, rows=3, seed=103, vertical=False, knots=0, seam_dark=0)
    for x in range(size):
        put(img, x, 4, AMBER[5])
    return img


def roof(pal, seed: int):
    """A vault board in one sunset band: grain along the board, a lit edge, a dark seam."""
    def paint(size: int = 16) -> object:
        img = canvas(size)
        rng = random.Random(seed)
        n = len(pal)
        for y in range(size):
            for x in range(size):
                tone = n - 3 if rng.random() > 0.25 else n - 4
                if x == 0:
                    tone = n - 1
                elif x == size - 1:
                    tone = 1
                elif x == 1:
                    tone = n - 2
                put(img, x, y, pal[tone])
        for _ in range(5):
            gx, gy = rng.randrange(2, size - 2), rng.randrange(size)
            for i in range(rng.randint(3, 6)):
                put(img, gx, (gy + i) % size, pal[n - 5])
        return img
    return paint


def sky(size: int = 32) -> object:
    """The vault's front arch: a sunset sky fading gold (at the horizon) through amber and
    orange to coral (at the crown), cut to the arch's shape, with a few wisps of cloud."""
    img = canvas(size)
    bands = [GOLDEN[6], GOLDEN[5], AMBER[6], ORANGE[5], ORANGE[4], CORAL[4], CORAL[3], DUSK[1], DUSK[0]]
    rng = random.Random(107)
    for y in range(size):
        uy = ARCH_H * (1 - (y + 0.5) / size)          # height over the horizon
        for x in range(size):
            ux = L0 + (L1 - L0) * (x + 0.5) / size
            if math.hypot(ux - 8.0, LID_TOP + uy - CY) > R - 0.05:
                continue
            k = uy / ARCH_H * len(bands)
            i = min(len(bands) - 1, int(k))
            # a checker of the next band along each band's upper edge softens the fade
            if k - i > 0.72 and (x + y) % 2 == 0 and i + 1 < len(bands):
                i += 1
            put(img, x, y, bands[i])
    for (cx, cy, ln) in ((5, 14, 7), (22, 11, 6), (13, 7, 5)):
        for i in range(ln):
            put(img, cx + i, cy, CORAL[6])
            if 0 < i < ln - 1:
                put(img, cx + i, cy + 1, ORANGE[6])
    return img


def sun_face(t: float) -> object:
    """The half sun: a glowing golden disc (top half of the texture) with a hot heart at the
    horizon that breathes."""
    img = canvas(16)
    pulse = wave(t)
    core = [GOLDEN[3], GOLDEN[4], GOLDEN[5], GOLDEN[6], GOLDEN[7]]
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8) / 8
            if d > 0.94:
                c = AMBER[5]
            else:
                v = (1 - d) * (0.6 + 0.4 * pulse) + 0.1
                c = core[min(4, int(v * 5))]
            put(img, x, y, c)
    return img


def ray_tex(pal, t_offset: float):
    def paint(t: float) -> object:
        img = canvas(16)
        n = len(pal)
        for y in range(16):
            for x in range(16):
                c = pal[n - 2] if x < 6 else pal[n - 3] if x < 12 else pal[n - 4]
                put(img, x, y, c)
        return shine(img, (t + t_offset) % 1, colour=GOLDEN[7], width=4.0, strength=0.55, angle=80, pause=0.4)
    return paint


def amber(size: int = 16) -> object:
    """Warm amber metal: lit top rows, shaded body, dark lower edge, a few bright flecks."""
    img = canvas(size)
    rng = random.Random(109)
    for y in range(size):
        for x in range(size):
            tone = 6 if y == 0 else 5 if y < 3 else 4 if y < 9 else 3
            if y >= size - 2:
                tone = 1
            if rng.random() < 0.1:
                tone = max(1, tone - 1)
            put(img, x, y, AMBER[tone])
    return img


def rib(size: int = 16) -> object:
    """A gold rib: bright crown, warm amber flanks."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            put(img, x, y, GOLDEN[6] if x < 4 else GOLDEN[4] if x < 11 else AMBER[5])
    return img


def rivet(t: float) -> object:
    """A polished golden sun rivet with a glint passing over it."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 6)
            put(img, x, y, GOLDEN[6] if d < 3 else GOLDEN[5] if d < 7 else GOLDEN[3] if d < 11 else GOLDEN[2])
    return shine(img, t, colour="#ffffff", width=3.0, strength=0.6, pause=0.55)


def sunband(size: int = 16) -> object:
    """The band round the base: a sunset gradient, coral at the bottom up to orange and gold."""
    img = canvas(size)
    bands = [GOLDEN[4], AMBER[6], ORANGE[5], ORANGE[4], CORAL[4], CORAL[3]]
    for y in range(size):
        for x in range(size):
            k = y / size * len(bands)
            i = min(len(bands) - 1, int(k))
            if k - i > 0.7 and (x + y) % 2 == 0 and i + 1 < len(bands):
                i += 1
            put(img, x, y, bands[i])
    return img


def marigold(layer: str) -> object:
    """A summer flower head seen face on: ten rounded petals cut out round the edge, lit
    toward the top, warm orange (outer) or golden (inner) shading in to the heart."""
    img = canvas(16)
    pal = ORANGE if layer == "outer" else GOLDEN
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            d = math.hypot(dx, dy)
            a = math.atan2(dy, dx)
            edge = 6.4 + 1.5 * math.cos(10 * a)
            if d > edge:
                continue
            tone = 5 if dy < 0 else 4
            if d > edge - 1.2:
                tone -= 2
            if abs(math.cos(10 * a)) < 0.25 and d > 3:
                tone -= 1
            put(img, x, y, pal[max(0, tone)])
    return img


def flower_heart(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            put(img, x, y, CORAL[1] if d > 6 else CORAL[2] if (x + y) % 3 else AMBER[2])
    return img


def leaf_tex(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 3 if x < 8 else 2
            if x == 8:
                tone = 1
            put(img, x, y, LEAF[tone])
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(GLOW, t)


def textures() -> None:
    save(boards(), "boards")
    save(lid_boards(), "lidboards")
    save(roof(CORAL, 111)(), "roof_coral")
    save(roof(ORANGE, 113)(), "roof_orange")
    save(roof(AMBER, 117)(), "roof_amber")
    save(roof(GOLDEN[:-2], 119)(), "roof_gold")
    save(sky(), "sky")
    save(amber(), "amber")
    save(rib(), "rib")
    save(sunband(), "sunband")
    save(marigold("outer"), "flower_a")
    save(marigold("inner"), "flower_b")
    save(flower_heart(), "flower_heart")
    save(leaf_tex(), "leaf")
    save_animation(animate(sun_face, 10), "sun", frametime=3, interpolate=True)
    save_animation(animate(ray_tex(GOLDEN, 0.0), 12), "ray_long", frametime=3)
    save_animation(animate(ray_tex(PALE, 0.5), 12), "ray_short", frametime=3)
    save_animation(animate(rivet, 12), "rivet", frametime=3)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def vault() -> list[dict]:
    """The rounded lid: sunset-banded boards on an arc, a solid stepped core under them,
    amber straps over the crown and the sky cards in both arch ends."""
    parts = []
    segs = 8
    bands = ("roof_coral", "roof_orange", "roof_amber", "roof_gold", "roof_gold", "roof_amber", "roof_orange",
             "roof_coral")
    for k in range(segs):
        a0 = 90 + SPAN - 2 * SPAN * k / segs
        a1 = 90 + SPAN - 2 * SPAN * (k + 1) / segs
        parts += arc((8.0, CY, 8.0), R - 0.35, a0, a1, 1, 0.7, L1 - L0 + 0.2, bands[k], plane="xy",
                     uv={"up": [0, 0, 16, 16], "down": [0, 0, 16, 16], "east": [0, 0, 16, 16],
                         "west": [0, 0, 16, 16], "north": [0, 0, 16, 2], "south": [0, 0, 16, 2]})
    # the core under the boards, stepped, so the vault is solid from every side
    steps = 6
    for i in range(steps):
        y0 = LID_TOP - 0.02 + ARCH_H * i / steps
        y1 = LID_TOP + ARCH_H * (i + 1) / steps - 0.1
        half = math.sqrt(max(0.0, (R - 0.6) ** 2 - (y1 - CY) ** 2))
        parts.append(box((8.0 - half, y0, L0 + 0.15), (8.0 + half, y1, L1 - 0.15), "lidboards",
                         skip=("north", "south", "down")))
    # the sky in both arch ends
    for z, side in ((L1 - 0.12, "south"), (L0 + 0.12, "north")):
        parts.append(box((L0, LID_TOP, z - 0.01), (L1, LID_TOP + ARCH_H, z + 0.01), "sky", uv="full",
                         skip=tuple(s for s in ("north", "south", "east", "west", "up", "down") if s != side)))
    # amber rims round both arch ends and two straps over the crown
    for z in (L1 - 0.1, L0 - 0.15):
        parts += arc((8.0, CY, z + 0.12), R - 0.05, 90 - SPAN, 90 + SPAN, 10, 0.5, 0.26, "amber", plane="xy")
    # gold ribs run back over the vault from where each long ray of the sun meets the rim,
    # so the sunbeams carry on over the lid
    for a in ray_angles():
        theta = math.radians(rim_angle(a))
        px, py = 8.0 + (R - 0.02) * math.cos(theta), CY + (R - 0.02) * math.sin(theta)
        rib = box((px - 0.24, py - 0.2, L0 + 0.1), (px + 0.24, py + 0.2, L1 - 0.1), "rib")
        parts.append(turn(rib, math.degrees(theta) - 90, "z", (px, py, 8.0)))
    return parts


def ray_angles() -> list[float]:
    """Directions of the seven long sun rays, degrees from +X round the sun's centre."""
    return [18 + 24 * k for k in range(7)]


def ray_reach(a: float) -> float:
    """How far a ray from the sun's centre at angle a runs before it meets the vault rim."""
    rad = math.radians(a)
    cx, cy = SUN
    reach = 0.0
    while math.hypot(cx + (reach + 0.05) * math.cos(rad) - 8.0, cy + (reach + 0.05) * math.sin(rad) - CY) < R:
        reach += 0.05
    return reach


def rim_angle(a: float) -> float:
    """The vault angle (round its own centre) where the ray at a meets the rim."""
    rad = math.radians(a)
    cx, cy = SUN
    d = ray_reach(a)
    return math.degrees(math.atan2(cy + d * math.sin(rad) - CY, cx + d * math.cos(rad) - 8.0))


def sunset() -> list[dict]:
    """The emblem in the front arch: a glowing half sun on the horizon, layered rays."""
    cx, cy = SUN
    z = L1 - 0.06
    parts = []
    # long amber rays behind, short gold rays in front, fanning over the horizon
    for k, a in enumerate(ray_angles()):
        reach = ray_reach(a) + (0.45 if 2 <= k <= 4 else 0.25)
        r = box((cx - 0.3, cy + SUN_R - 0.2, z), (cx + 0.3, cy + reach, z + 0.12), "ray_long", uv="full")
        parts.append(turn(r, a - 90, "z", (cx, cy, z)))
    for k in range(6):
        a = 30 + 24 * k
        r = box((cx - 0.2, cy + SUN_R - 0.2, z + 0.08), (cx + 0.2, cy + SUN_R + 0.85, z + 0.16), "ray_short",
                uv="full", glow=6)
        parts.append(turn(r, a - 90, "z", (cx, cy, z)))
    # the half sun: horizontal slices of a disc, each showing its part of the sun texture
    n = 6
    for i in range(n):
        y0 = ARCH_H * 0 + SUN_R * i / n
        y1 = SUN_R * (i + 1) / n
        half = SUN_R * math.sqrt(max(0.0, 1 - ((y0 + y1) / 2 / SUN_R) ** 2))
        u0, u1 = 8 - 8 * half / SUN_R, 8 + 8 * half / SUN_R
        v0, v1 = 8 - 8 * y1 / SUN_R, 8 - 8 * y0 / SUN_R
        parts.append(box((cx - half, cy + y0, z + 0.12), (cx + half, cy + y1, z + 0.34), "sun", glow=11,
                         shade=False, uv={"south": [u0, v0, u1, v1], "north": [u0, v0, u1, v1],
                                          "up": [u0, v0, u1, v0 + 0.5], "down": [u0, v1 - 0.5, u1, v1],
                                          "east": [u1 - 0.5, v0, u1, v1], "west": [u0, v0, u0 + 0.5, v1]}))
    # the horizon: an amber ledge along the lid's front top edge
    parts.append(box((L0 - 0.12, LID_TOP - 0.55, L0 - 0.12), (L1 + 0.12, LID_TOP + 0.05, L1 + 0.12), "amber",
                     skip=("up", "down")))
    return parts


def frame() -> list[dict]:
    """Amber framing: plinth, seam band, lock straps and stepped corner plates with sun
    rivets."""
    parts = [box((B0 - 0.2, 0.0, B0 - 0.2), (B1 + 0.2, 1.0, B1 + 0.2), "amber", skip=("down",)),
             box((B0 - 0.18, SEAM - 0.75, B0 - 0.18), (B1 + 0.18, SEAM, B1 + 0.18), "amber", skip=("up",)),
             box((L0 - 0.1, SEAM, L0 - 0.1), (L1 + 0.1, SEAM + 0.55, L1 + 0.1), "amber", skip=("up", "down"))]
    for x in (B0, B1):
        for z in (B0, B1):
            sx = 1 if x < 8 else -1
            sz = 1 if z < 8 else -1
            for (y0, y1, arm) in ((0.9, SEAM - 0.7, 1.9), (2.6, SEAM - 2.6, 2.6)):
                ex = x - 0.22 * sx
                ez = z - 0.22 * sz
                parts.append(box((ex, y0, ez), (x + arm * sx, y1, ez + 0.24 * sz), "amber"))
                parts.append(box((ex, y0, ez), (ex + 0.24 * sx, y1, z + arm * sz), "amber"))
            # lid corner posts
            parts.append(box((x - 0.5 * sx + (L0 - B0) * (1 if sx > 0 else -1), SEAM + 0.5,
                              z - 0.5 * sz + (L0 - B0) * (1 if sz > 0 else -1)),
                             (x + 0.9 * sx, LID_TOP - 0.5, z + 0.9 * sz), "amber"))
    # sun rivets on the four front corner plates and the back ones
    for x in (B0 + 1.1, B1 - 1.1):
        for z in (B1 + 0.04, B0 - 0.26):
            for y in (2.0, SEAM - 1.8):
                parts += prism((x, y, z + 0.11), 0.42, 0.22, "rivet", axis="z", sides=8, cap="rivet", uv="full")
    # upright straps either side of the lock
    for x in (4.6, 11.4):
        parts.append(box((x - 0.4, 1.0, B1 - 0.02), (x + 0.4, SEAM - 0.75, B1 + 0.18), "amber"))
    return parts


def sun_lock() -> list[dict]:
    """A round sun lock across the seam: an amber disc with a golden face, ringed by bold
    rays, and a glowing keyhole."""
    cx, cy, zf = 8.0, 8.9, 16.02
    parts = prism((cx, cy, (B1 + zf) / 2), 2.2, zf - B1, "amber", axis="z", sides=16, cap="amber", uv="full")
    for k in range(12):
        r = box((cx - 0.2, cy + 2.0, B1 + 0.05), (cx + 0.2, cy + 3.3, B1 + 0.42), "rib")
        parts.append(turn(r, k * 30 + 15, "z", (cx, cy, B1)))
    parts += prism((cx, cy, zf + 0.04), 1.65, 0.12, "rivet", axis="z", sides=16, cap="rivet", uv="full")
    parts += prism((cx, cy + 0.35, zf + 0.1), 0.5, 0.08, "keyhole", axis="z", sides=8, cap="keyhole", glow=11,
                   uv="full", shade=False)
    parts.append(box((cx - 0.2, cy - 0.85, zf + 0.06), (cx + 0.2, cy + 0.3, zf + 0.14), "keyhole", glow=11,
                     shade=False, uv="full"))
    return parts


def flower(c, s: float, spin: float = 0.0) -> list[dict]:
    """A summer flower facing +Z: an orange petal ring, a golden inner ring turned between
    its petals, and a coral heart, each layer standing a little further out."""
    x, y, z = c
    parts = [box((x - s, y - s, z), (x + s, y + s, z + 0.04), "flower_a", uv="full",
                 skip=("north", "east", "west", "up", "down")),
             box((x - s * 0.66, y - s * 0.66, z + 0.12), (x + s * 0.66, y + s * 0.66, z + 0.16), "flower_b",
                 uv="full", skip=("north", "east", "west", "up", "down"))]
    turn(parts[0], spin, "z", c)
    turn(parts[1], spin + 18, "z", c)
    parts += prism((x, y, z + 0.18), s * 0.3, 0.2, "flower_heart", axis="z", sides=8, cap="flower_heart",
                   uv="full")
    return parts


def flower_spray() -> list[dict]:
    """Three flowers on stems with leaves, growing up a wall from the sunset band (built
    on the front)."""
    zf = B1 + 0.04
    heads = ((5.0, 6.0, 1.15, 0), (8.0, 7.3, 1.35, 12), (11.0, 5.8, 1.1, 30))
    parts = []
    for (x, y, s, spin) in heads:
        parts.append(bar((8.0 + (x - 8.0) * 0.35, 2.3, zf + 0.05), (x, y, zf + 0.05), 0.22, 0.12, "leaf"))
        parts += flower((x, y, zf + 0.14), s, spin)
    for (x, y, a) in ((6.6, 3.6, 40), (9.6, 3.9, -40), (7.5, 4.8, 65), (8.7, 5.2, -60)):
        lf = box((x - 0.32, y, zf + 0.06), (x + 0.32, y + 1.25, zf + 0.12), "leaf")
        parts.append(turn(lf, a, "z", (x, y, zf + 0.09)))
    return parts


def side_flowers() -> list[dict]:
    """A flower spray on each side wall, and the sunset band round the base."""
    wall = flower_spray()
    parts = turn(copy(wall), 90, "y", (8.0, 0.0, 8.0)) + turn(copy(wall), -90, "y", (8.0, 0.0, 8.0))
    parts.append(box((B0 - 0.1, 1.0, B0 - 0.1), (B1 + 0.1, 2.3, B1 + 0.1), "sunband", uv="full",
                     skip=("up", "down")))
    return parts


def back_hinges() -> list[dict]:
    """Two amber hinges at the back, each a little fan of rays over a knuckle."""
    parts = []
    for x in (4.0, 12.0):
        parts += prism((x, SEAM, L0 - 0.1), 0.5, 2.2, "amber", axis="x", sides=8, cap="rivet")
        parts.append(box((x - 0.9, SEAM - 2.4, B0 - 0.2), (x + 0.9, SEAM - 0.3, B0), "amber"))
        for k in range(5):
            r = box((x - 0.22, SEAM + 0.3, L0 - 0.22), (x + 0.22, SEAM + 2.6, L0), "amber")
            parts.append(turn(r, -40 + 20 * k, "z", (x, SEAM + 0.3, L0)))
    return parts


def models() -> dict:
    p = shell("boards", "lidboards", "lidboards")
    p += frame()
    p += vault()
    p += sunset()
    p += sun_lock()
    p += side_flowers()
    p += back_hinges()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
