"""July Chest (issue #1495, design in #1407): the monthly crate chest for July, a summer
fireworks chest in deep navy, bright red, warm white and gold with small cyan accents.

A body of deep navy boards sprinkled with tiny painted stars, standing on a gold plinth,
with red corner posts wrapped in gold bands and capped with gold star studs. A red and
warm white striped bunting band runs round the lid. The lid is layered: the navy lid, a
gold-trimmed red middle tier and a small navy star-spangled top tier that carries a
little launch rack with three firework rockets (red, cyan and navy, striped, with gold
and red nose cones) standing ready, the tallest crowned by a small glowing starburst.
Two more finned rockets are strapped to each side wall with gold clamps, their fuses
glowing.

The emblem is a firework burst on the lid's front: a glowing gold heart with long and
short rays in red, warm white, gold and cyan, each ending in a glowing spark. Below it a
red lock plate across the seam carries a gold five-point star with a glowing cyan
keyhole in its middle. Two gold strap hinges with star studs hold the lid at the back,
and small gold stars are scattered over the lid.

Animation (one loop): the burst's sparks twinkle in turn, the rocket fuses flicker, the
star studs glint and the keyhole breathes cyan.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block. Unlike the other crate chests: a stepped three-tier lid
with standing rockets, the tallest silhouette of the monthly chests.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM,  # noqa: F401
                                    keyhole_glow, planks, put, shell)
from art.kit import animate, bar, box, canvas, copy, display, lathe, model, prism, save, save_animation, shine, \
    sparkle, turn, wave

ID = "july_chest"
NAME = "July Chest"

NAVY = ["#070b22", "#0c1434", "#121d4a", "#1a2961", "#243679", "#304592", "#4459ab", "#6a7fc8"]
RED = ["#3e0610", "#640a18", "#8e1222", "#b81c2b", "#d92f37", "#ef5650", "#fa8a7c", "#ffc2b5"]
CREAM = ["#7a6a58", "#a3927c", "#c4b49b", "#ddcfb7", "#ece2cd", "#f6efe0", "#fffaf0"]
GOLD = ["#4a2c06", "#74470c", "#9e6a14", "#c38f22", "#e0b13a", "#f2cc5c", "#fbe48e", "#fff6cc"]
CYAN = ["#063038", "#0a5160", "#0f7889", "#1ea3b2", "#45c9d2", "#8ae6ea", "#e2fdff"]
SPARK = ["#5a2a04", "#a3530a", "#e08a16", "#ffc13a", "#ffe27a", "#fff4c2", "#ffffff"]

T1 = LID_TOP + 1.1          # top of the red middle tier
T2 = T1 + 1.0               # top of the navy top tier
RACK = T2 + 0.6             # top of the launch rack
SIDES = 8


# ======================================================================================
# Textures
# ======================================================================================

def stars_over(img, seed: int, count: int, colours=(CREAM[6], GOLD[6], CYAN[5])) -> object:
    """Scatter tiny painted stars (a bright pixel, sometimes with a dim cross) over img."""
    rng = random.Random(seed)
    w, h = img.size
    for i in range(count):
        x, y = rng.randrange(2, w - 2), rng.randrange(2, h - 2)
        c = colours[2] if i % 6 == 5 else colours[i % 2]
        put(img, x, y, c)
        arm = NAVY[6] if c != colours[2] else CYAN[2]
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            put(img, x + dx, y + dy, arm)
    return img


def boards(size: int = 32) -> object:
    """Deep navy boards, upright, with a few painted stars."""
    img = planks(NAVY[1:], size=size, rows=4, seed=71, vertical=True, knots=1, seam_dark=0)
    return stars_over(img, 3, 6)


def lid_boards(size: int = 32) -> object:
    img = planks(NAVY[1:], size=size, rows=3, seed=73, vertical=False, knots=0, seam_dark=0)
    return stars_over(img, 5, 4)


def spangled(size: int = 16) -> object:
    """The top tier: a night sky field full of small stars."""
    img = canvas(size)
    rng = random.Random(79)
    for y in range(size):
        for x in range(size):
            put(img, x, y, NAVY[3] if rng.random() > 0.2 else NAVY[2])
    return stars_over(img, 7, 4)


def bunting(size: int = 32) -> object:
    """A garland: a twisted gold cord along the top and red and warm white pennants
    hanging off it, clear between the points (the lid shows through)."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            if y < 3:
                put(img, x, y, GOLD[6] if y == 0 else GOLD[5] if (x + y) % 3 else GOLD[2])
                continue
            k = x % 4
            half = 2.0 - (y - 3) * 2.0 / (size - 4)
            if abs(k + 0.5 - 2) < half + 0.25:
                red = (x // 4) % 2 == 0
                if red:
                    c = RED[4] if k < 2 else RED[3]
                    if y == 3:
                        c = RED[5]
                else:
                    c = CREAM[6] if k < 2 else CREAM[4]
                    if y == 3:
                        c = CREAM[6]
                put(img, x, y, c)
    return img


def red_wood(size: int = 16) -> object:
    """Red lacquered wood: vertical grain, a lit edge, a dark edge."""
    img = canvas(size)
    rng = random.Random(83)
    for y in range(size):
        for x in range(size):
            tone = 3 if x < 12 else 2
            if rng.random() < 0.12:
                tone += 1
            if x == 0:
                tone = 4
            if y in (0, 15) and x > 0:
                tone = 1
            put(img, x, y, RED[tone])
    return img


def gold_band(size: int = 16) -> object:
    img = canvas(size)
    rng = random.Random(89)
    for y in range(size):
        for x in range(size):
            tone = 6 if y == 0 else 5 if y < 4 else 4 if y < 10 else 3
            if y >= size - 2:
                tone = 2
            if rng.random() < 0.08:
                tone = max(2, tone - 1)
            put(img, x, y, GOLD[tone])
    for _ in range(3):
        put(img, rng.randrange(1, size - 1), rng.randrange(1, 4), GOLD[7])
    return img


def gold_star(t: float) -> object:
    """Polished gold with a glint passing over it (the star studs and the lock star)."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 6)
            tone = 6 if d < 3 else 5 if d < 7 else 4 if d < 11 else 3
            put(img, x, y, GOLD[tone])
    return shine(img, t, colour="#ffffff", width=3.0, strength=0.6, pause=0.55)


def rocket(pal, stripe):
    """A rocket tube: the main colour with a stripe band, lit on one side."""
    def paint(size: int = 16) -> object:
        img = canvas(size)
        n = len(pal)
        for y in range(size):
            for x in range(size):
                tone = n - 3 if x < 5 else n - 4 if x < 11 else n - 5
                c = pal[max(0, tone)]
                if 5 <= y <= 7:
                    c = stripe[-2] if x < 8 else stripe[-3]
                if y in (4, 8):
                    c = GOLD[5]
                if x == 1:
                    c = pal[-2]
                put(img, x, y, c)
        return img
    return paint


def stick(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            put(img, x, y, CREAM[2] if x < 8 else CREAM[1])
    return img


def fuse(t: float) -> object:
    """A burning fuse tip: an ember that flickers between hot white and orange."""
    img = canvas(16)
    rng = random.Random(int(t * 1000) % 7)
    pulse = wave(t)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8) / 8
            v = max(0.0, 1 - d) * (0.55 + 0.45 * pulse) + rng.random() * 0.12
            put(img, x, y, SPARK[max(0, min(6, int(v * 7)))])
    return img


def burst_heart(t: float) -> object:
    """The burst's glowing heart: gold with a white core that pulses."""
    img = canvas(16)
    pulse = wave(t)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8) / 8
            v = (1 - d) * (0.6 + 0.4 * pulse) + 0.15
            put(img, x, y, SPARK[max(2, min(6, int(v * 7)))])
    return img


def ray(pal):
    def paint(size: int = 16) -> object:
        img = canvas(size)
        n = len(pal)
        for y in range(size):
            for x in range(size):
                c = pal[n - 2] if x < 6 else pal[n - 3] if x < 12 else pal[n - 4]
                if y < 2:
                    c = pal[n - 1]
                put(img, x, y, c)
        return img
    return paint


def spark(offset: float):
    """A spark at a ray's tip, twinkling, each colour set out of step with the others."""
    def paint(t: float) -> object:
        img = canvas(16)
        v = wave(t, offset)
        base = ["#ffd86a", "#ffe9a0", "#fff6d6", "#ffffff"]
        for y in range(16):
            for x in range(16):
                d = math.hypot(x + 0.5 - 8, y + 0.5 - 8) / 8
                k = (1 - d) * (0.35 + 0.65 * v)
                put(img, x, y, base[max(0, min(3, int(k * 4 + 0.3)))])
        return img
    return paint


def plate(size: int = 16) -> object:
    """The red lock plate: bevelled, gold rivets in the corners."""
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            if x <= 1 or y <= 1:
                c = RED[5] if (x == 0 or y == 0) else RED[4]
            elif x >= size - 2 or y >= size - 2:
                c = RED[0] if (x == size - 1 or y == size - 1) else RED[1]
            else:
                c = RED[2] if (x + y) % 7 else RED[3]
            put(img, x, y, c)
    for rx, ry in ((3, 3), (11, 3), (3, 11), (11, 11)):
        put(img, rx, ry, GOLD[6])
        put(img, rx + 1, ry, GOLD[4])
        put(img, rx + 1, ry + 1, GOLD[2])
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(CYAN, t)


def textures() -> None:
    save(boards(), "boards")
    save(lid_boards(), "lidboards")
    save(spangled(), "spangled")
    save(bunting(), "bunting")
    save(red_wood(), "redwood")
    save(gold_band(), "gold")
    save(rocket(RED, CREAM)(), "rocket_red")
    save(rocket(CREAM, RED)(), "rocket_white")
    save(rocket(NAVY, CYAN)(), "rocket_navy")
    save(rocket(CYAN, CREAM)(), "rocket_cyan")
    save(stick(), "stick")
    save(ray(RED)(), "ray_red")
    save(ray(CREAM)(), "ray_white")
    save(ray(GOLD)(), "ray_gold")
    save(ray(CYAN)(), "ray_cyan")
    save(plate(), "plate")
    save_animation(animate(gold_star, 12), "star", frametime=3)
    save_animation(animate(fuse, 8), "fuse", frametime=2, interpolate=True)
    save_animation(animate(burst_heart, 10), "heart", frametime=3, interpolate=True)
    for k in range(3):
        save_animation(animate(spark(k / 3), 12), f"spark_{k}", frametime=3, interpolate=True)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def star(c, s: float, tex: str = "star", depth: float = 0.2, normal: str = "z", spin: float = 90.0,
         fine: bool = False, **kw) -> list[dict]:
    """A five-point star centred on c, facing +Z (or lying flat, normal "y"). fine stacks a
    wide short bar under a narrow long one per arm, so the points taper (for the lock);
    small studs use one bar per arm."""
    x, y, z = c
    parts = []
    for k in range(5):
        a = math.radians(spin + k * 72)
        tip = (x + s * math.cos(a), y + s * math.sin(a), z)
        if fine:
            mid = (x + s * 0.55 * math.cos(a), y + s * 0.55 * math.sin(a), z)
            parts.append(bar((x, y, z), mid, 0.62 * s, depth, tex, uv="full", **kw))
            parts.append(bar((x, y, z + 0.01), tip, 0.3 * s, depth * 0.9, tex, uv="full", **kw))
        else:
            parts.append(bar((x, y, z), tip, 0.42 * s, depth, tex, uv="full", **kw))
    if fine:
        parts += prism((x, y, z), 0.42 * s, depth * 1.1, tex, axis="z", sides=8, cap=tex, uv="full", **kw)
    else:
        hub = box((x - 0.3 * s, y - 0.3 * s, z - depth * 0.55), (x + 0.3 * s, y + 0.3 * s, z + depth * 0.55), tex,
                  uv="full", **kw)
        parts.append(turn(hub, 45, "z", (x, y, z)))
    if normal == "y":
        turn(parts, -90, "x", (x, y, z))
    return parts


def rocket_model(base, height: float, tex: str, radius: float = 0.55, cone: str = "redwood",
                 stick_len: float = 0.0) -> list[dict]:
    """A standing firework rocket: a striped tube, a gold collar, a pointed nose cone and a
    glowing fuse at the bottom (or a launch stick under it)."""
    x, y, z = base
    parts = prism((x, y + height / 2, z), radius, height, tex, sides=SIDES, cap="gold", uv="full")
    parts += prism((x, y + height + 0.1, z), radius + 0.06, 0.22, "gold", sides=SIDES, cap="gold")
    parts += lathe((x, y + height + 0.2, z), [(0.0, radius), (0.55, radius * 0.66), (1.05, radius * 0.3),
                                              (1.4, 0.05)], cone, sides=SIDES, cap=cone)
    if stick_len:
        parts.append(box((x - 0.12, y - stick_len, z - 0.12), (x + 0.12, y + 0.2, z + 0.12), "stick"))
    else:
        parts.append(box((x - 0.2, y - 0.55, z - 0.2), (x + 0.2, y, z + 0.2), "fuse", glow=12, shade=False,
                         uv="full"))
    return parts


def garland_card(y0: float, y1: float, e: float = 0.07) -> list[dict]:
    """The pennant garland: a cut-out card standing just off each side of the lid."""
    return [
        box((L0, y0, L1 + e - 0.01), (L1, y1, L1 + e), "bunting", uv="full",
            skip=("north", "east", "west", "up", "down")),
        box((L0, y0, L0 - e), (L1, y1, L0 - e + 0.01), "bunting", uv="full",
            skip=("south", "east", "west", "up", "down")),
        box((L1 + e - 0.01, y0, L0), (L1 + e, y1, L1), "bunting", uv="full",
            skip=("north", "south", "west", "up", "down")),
        box((L0 - e, y0, L0), (L0 - e + 0.01, y1, L1), "bunting", uv="full",
            skip=("north", "south", "east", "up", "down")),
    ]


def lid_tiers() -> list[dict]:
    """The layered lid: a pennant garland round the lid, gold trims, a red middle tier and a
    spangled top tier."""
    parts = garland_card(11.0, 13.55)
    # gold trims: the seam band and the lid's top edge (open on top, so the lid shows)
    parts.append(box((B0 - 0.18, SEAM - 0.7, B0 - 0.18), (B1 + 0.18, SEAM, B1 + 0.18), "gold", skip=("up",)))
    parts.append(box((L0 - 0.14, LID_TOP - 0.7, L0 - 0.14), (L1 + 0.14, LID_TOP + 0.12, L1 + 0.14), "gold",
                     skip=("down", "up")))
    lo, hi, y0, y1, w = L0 - 0.14, L1 + 0.14, LID_TOP + 0.02, LID_TOP + 0.12, 0.84
    parts += [box((lo, y0, lo), (hi, y1, lo + w), "gold", skip=("down",)),
              box((lo, y0, hi - w), (hi, y1, hi), "gold", skip=("down",)),
              box((lo, y0, lo + w), (lo + w, y1, hi - w), "gold", skip=("down",)),
              box((hi - w, y0, lo + w), (hi, y1, hi - w), "gold", skip=("down",))]
    # red middle tier with a gold lip
    m0, m1 = 2.0, 14.0
    parts.append(box((m0, LID_TOP, m0), (m1, T1, m1), "redwood", faces={"up": "lidboards"}, skip=("down",)))
    parts.append(box((m0 - 0.15, T1 - 0.3, m0 - 0.15), (m1 + 0.15, T1 + 0.02, m1 + 0.15), "gold",
                     skip=("down", "up")))
    # navy top tier with a gold lip
    t0, t1 = 4.2, 11.8
    parts.append(box((t0, T1, t0), (t1, T2, t1), "spangled", skip=("down",)))
    parts.append(box((t0 - 0.12, T2 - 0.25, t0 - 0.12), (t1 + 0.12, T2 + 0.03, t1 + 0.12), "gold",
                     skip=("down", "up")))
    # gold star studs on the middle tier's corners
    for x in (m0 + 0.95, m1 - 0.95):
        for z in (m0 + 0.95, m1 - 0.95):
            parts += star((x, T1 + 0.1, z), 0.72, depth=0.16, normal="y", spin=90)
    return parts


def launch_rack() -> list[dict]:
    """A little gold-railed rack on the top tier with three standing rockets; the tallest
    is crowned by a glowing starburst."""
    parts = [box((5.0, T2, 5.6), (11.0, RACK, 10.4), "redwood", faces={"up": "spangled"}, skip=("down",))]
    for z in (5.6, 10.4):
        parts.append(box((4.9, RACK - 0.2, z - 0.12), (11.1, RACK + 0.1, z + 0.12), "gold"))
    # the middle rocket stands straight, the side ones lean out a little
    tall = 5.75
    rockets = ((8.0, 7.6, tall, "rocket_red", "redwood", 0.64, 0), (6.1, 8.9, 3.7, "rocket_cyan", "redwood", 0.52, 10),
               (9.9, 8.7, 4.4, "rocket_navy", "gold", 0.56, -9))
    for (x, z, h, tex, cone, r, lean) in rockets:
        parts += turn(rocket_model((x, RACK, z), h, tex, radius=r, cone=cone, stick_len=0.5), lean, "z",
                      (x, RACK, z))
    # starburst over the tallest rocket: two crossed fans of glowing sparks round a hot core
    tip = (8.0, RACK + tall + 1.75, 7.6)
    for k in range(8):
        for yaw in (0, 90):
            r = box((tip[0] - 0.09, tip[1] + 0.22, tip[2] - 0.05), (tip[0] + 0.09, tip[1] + (0.75 if k % 2 else 1.05),
                                                                     tip[2] + 0.05), f"spark_{k % 3}", glow=13,
                    shade=False, uv="full")
            turn(r, k * 45, "z", tip)
            turn(r, yaw, "y", tip)
            parts.append(r)
    parts.append(box((tip[0] - 0.24, tip[1] - 0.24, tip[2] - 0.24), (tip[0] + 0.24, tip[1] + 0.24, tip[2] + 0.24),
                     "heart", glow=14, shade=False, uv="full"))
    return parts


def side_rockets() -> list[dict]:
    """Two rockets strapped to each side wall with gold clamps, built on the front then
    turned to the east and west walls."""
    wall = []
    for (x, h, tex, cone) in ((5.0, 4.4, "rocket_red", "gold"), (11.0, 4.4, "rocket_white", "redwood")):
        wall += rocket_model((x, 2.0, B1 + 0.2), h, tex, radius=0.52, cone=cone)
        for y in (3.0, 5.4):
            wall.append(box((x - 0.72, y, B1 - 0.02), (x + 0.72, y + 0.36, B1 + 0.8), "gold", skip=("north",)))
        # a pair of red fins at the base
        for sx in (-1, 1):
            fin = box((x, 1.7, B1 + 0.12), (x + 1.05 * sx, 2.75, B1 + 0.28), "redwood")
            wall.append(turn(fin, 22 * sx, "z", (x, 2.75, B1 + 0.2)))
    out = []
    out += turn(copy(wall), 90, "y", (8.0, 0.0, 8.0))
    out += turn(copy(wall), -90, "y", (8.0, 0.0, 8.0))
    return out


def corners() -> list[dict]:
    """Red corner posts with gold bands, on a gold plinth."""
    parts = [box((B0 - 0.2, 0.0, B0 - 0.2), (B1 + 0.2, 0.9, B1 + 0.2), "gold", skip=("down",))]
    for x in (B0, B1):
        for z in (B0, B1):
            parts.append(box((x - 0.55, 0.9, z - 0.55), (x + 0.55, SEAM - 0.7, z + 0.55), "redwood", skip=("down",)))
            for y in (2.4, 6.0):
                parts.append(box((x - 0.64, y, z - 0.64), (x + 0.64, y + 0.45, z + 0.64), "gold"))
            parts.append(box((x - 0.5, SEAM, z - 0.5), (x + 0.5, LID_TOP - 0.7, z + 0.5), "redwood",
                             skip=("up", "down")))
            # a gold cap over the lid's corner, folded down both faces
            cx, cz = (x - 0.1 if x < 8 else x + 0.1), (z - 0.1 if z < 8 else z + 0.1)
            parts.append(box((cx - 0.72, LID_TOP - 1.3, cz - 0.72), (cx + 0.72, LID_TOP + 0.3, cz + 0.72), "gold"))
    return parts


def burst_emblem() -> list[dict]:
    """The firework burst on the lid's front: a glowing heart, long and short rays in four
    colours, a spark on every long ray's tip."""
    cx, cy, z = 8.0, 12.35, L1 + 0.1
    parts = prism((cx, cy, z + 0.12), 0.72, 0.16, "heart", axis="z", sides=8, cap="heart", glow=14, uv="full",
                  shade=False)
    cols = ("ray_red", "ray_gold", "ray_white", "ray_cyan")
    for k in range(16):
        a = k * 22.5
        long_ = k % 2 == 0
        ln = 2.55 if long_ else 1.4
        w = 0.4 if long_ else 0.28
        r = box((cx - w / 2, cy + 0.65, z), (cx + w / 2, cy + ln, z + 0.14), cols[(k // 2 + k % 2) % 4], uv="full")
        turn(r, a, "z", (cx, cy, z))
        parts.append(r)
        if long_:
            tip = box((cx - 0.26, cy + ln - 0.02, z + 0.02), (cx + 0.26, cy + ln + 0.5, z + 0.18), f"spark_{k % 3}",
                      glow=12, shade=False, uv="full")
            turn(tip, 45, "z", (cx, cy + ln + 0.24, z))
            turn(tip, a, "z", (cx, cy, z))
            parts.append(tip)
    return parts


def lock() -> list[dict]:
    """A red plate across the seam with a gold five-point star and a cyan keyhole."""
    z0, zf = B1 - 0.05, 16.02
    parts = [box((5.6, 4.6, z0), (10.4, 9.9, zf), "plate",
                 uv={"north": [0, 0, 4, 4], "east": [0, 0, 1, 4], "west": [0, 0, 1, 4], "up": [0, 0, 4, 1],
                     "down": [0, 0, 4, 1], "south": [0, 0, 16, 16]})]
    for (a, b) in (((5.4, 9.7), (10.6, 10.1)), ((5.4, 4.4), (10.6, 4.8)), ((5.4, 4.8), (5.8, 9.7)),
                   ((10.2, 4.8), (10.6, 9.7))):
        parts.append(box((a[0], a[1], zf - 0.1), (b[0], b[1], zf + 0.1), "gold"))
    parts += star((8.0, 7.2, zf + 0.02), 2.05, depth=0.16, fine=True)
    parts += prism((8.0, 7.45, zf + 0.18), 0.42, 0.06, "keyhole", axis="z", sides=8, cap="keyhole", glow=11,
                   uv="full", shade=False)
    parts.append(box((7.8, 6.45, zf + 0.15), (8.2, 7.4, zf + 0.21), "keyhole", glow=11, shade=False, uv="full"))
    return parts


def back_hinges() -> list[dict]:
    parts = []
    for x in (4.0, 12.0):
        parts += prism((x, SEAM, L0 - 0.08), 0.48, 2.2, "gold", axis="x", sides=8, cap="gold")
        parts.append(box((x - 0.85, SEAM - 2.6, B0 - 0.2), (x + 0.85, SEAM - 0.3, B0), "gold"))
        parts.append(box((x - 0.85, SEAM + 0.3, L0 - 0.2), (x + 0.85, SEAM + 3.4, L0), "gold"))
        s = star((x, SEAM + 2.2, L0 - 0.3), 0.6, depth=0.12)
        parts += turn(s, 180, "y", (x, SEAM + 2.2, L0 - 0.3))
    return parts


def models() -> dict:
    p = shell("boards", "lidboards", "lidboards")
    p += corners()
    p += lid_tiers()
    p += launch_rack()
    p += side_rockets()
    p += burst_emblem()
    p += lock()
    p += back_hinges()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
