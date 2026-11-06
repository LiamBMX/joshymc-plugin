"""Cabin Crate (issue #1389): the placed crate chest of the Cabin crate, a little log cabin.

The body is a log wall: three round oak logs on the front and back run past the corners,
side logs poke out between them (end grain showing) and the side walls are chinked logs
with a lamp-lit four-pane window in a timber frame on each. The lid is warm oak boards
with a brass band, brass corner brackets and a brass lock plate whose keyhole glows. A
low, snow-covered shake roof crowns it: icicle-trimmed snow on both slopes, plank gables
with a round glowing attic window, a ridge log, and a fieldstone chimney with a brass cap
on the back slope. Twin snowy pines carved in relief flank the hasp on the lid's front.
Everything stays inside the block (x/z 0..16), so crates side by side never overlap.

Animation (one loop): the windows and attic windows flicker like a hearth
fire, the keyhole breathes, and the snow on the roof glitters.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py so the three crate
chests share their footprint and hardware.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM, chunk, hinges,  # noqa: F401
                                    keyhole_glow, lid_frame, lock, lock_plate, metal, only, planks, put, rims,
                                    round_log, shell)
from art.kit import animate, bar, box, canvas, display, mix, model, prism, save, save_animation, sparkle

ID = "cabin_crate"
NAME = "Cabin Crate"

BARK = ["#2a170c", "#432812", "#5e3a19", "#7b4f23", "#98652f", "#b47e40", "#cc9a58"]
RINGS = ["#6b4220", "#94653a", "#b9884f", "#d2a66a", "#e6c288", "#f4dcaa"]
CHINK = ["#4f463c", "#6e6455", "#8c806c", "#a99c84", "#c4b79c"]
OAK = ["#2f1a0c", "#4c2d15", "#68401f", "#85552a", "#a26c37", "#bd8647", "#d6a35e"]
SHAKE = ["#1c100a", "#2f1b10", "#452a18", "#5c3a22", "#764c2c", "#8f5f37"]
BRASS = ["#3b2306", "#68420e", "#966618", "#c08e2c", "#dfb446", "#f4d87a", "#fff2bc"]
IRON = ["#121016", "#211f27", "#34313b", "#4a4652", "#64606c", "#86828e"]
SNOW = ["#8fa6c2", "#b6c9de", "#d3e1f0", "#e8f0f9", "#f6faff", "#ffffff"]
STONE = ["#35333a", "#4e4b53", "#68646b", "#827d83", "#9d979b", "#b8b2b3"]
PINE = ["#0f2a1c", "#16402a", "#1f5a38", "#2d7448", "#3f8e58"]
FIRE = ["#4a1d06", "#8c3f0a", "#cf7414", "#f3a12e", "#ffc95a", "#ffe594", "#fff6d6"]

LOG_R = 1.55
RISE = 2.6                            # low roof pitch, so the lid panel still shows under the eave
FRONT_LOGS = (1.55, 4.65, 7.75)       # y centres of the front/back logs
SIDE_LOGS = (3.1, 6.2)                # y centres of the side logs (between the front ones)


# ======================================================================================
# Textures
# ======================================================================================

def bark() -> Image:
    """32 px round-log bark running along u: lit crown, dark underside, bark furrows."""
    img = canvas(32)
    rng = random.Random(4)
    for y in range(32):
        for x in range(32):
            band = y % 16
            tone = (2, 3, 4, 5, 5, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1)[band]
            if rng.random() < 0.22:
                tone = max(1, tone - 1)
            put(img, x, y, BARK[tone])
    for _ in range(26):
        y = rng.randrange(32)
        x = rng.randrange(32)
        for k in range(rng.randint(3, 8)):
            put(img, (x + k) % 32, y, BARK[1] if rng.random() < 0.7 else BARK[0])
    return img


def log_end() -> Image:
    """16 px end grain: growth rings round a dark pith, bark rim."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if d > 7.0:
                c = BARK[2]
            elif d > 6.2:
                c = BARK[3]
            else:
                ring = int(d * 1.25) % 2
                c = RINGS[3 + ring] if d > 1.2 else RINGS[1]
                if int(d) == 4:
                    c = RINGS[2]
            put(img, x, y, c)
    put(img, 8, 6, RINGS[0])
    put(img, 9, 9, RINGS[2])
    return img


def log_wall() -> Image:
    """32 px side wall: round logs (8 px each = 4 units) with mortar chinking between."""
    img = canvas(32)
    rng = random.Random(12)
    for y in range(32):
        k = (y + 2) % 12
        for x in range(32):
            if k >= 10:                                       # chinking
                c = CHINK[2] if rng.random() > 0.25 else CHINK[rng.choice((1, 3))]
            else:
                tone = (6, 5, 5, 4, 4, 3, 3, 2, 2, 1)[k]
                if rng.random() < 0.2:
                    tone = max(1, tone - 1)
                c = BARK[tone]
            put(img, x, y, c)
    return img


def chinking() -> Image:
    return chunk(CHINK, seed=21, size=16, density=0.35)


def shakes(t: float | None = None) -> Image:
    """32 px roof shakes in staggered rows, lit lower edge, dark gaps."""
    img = canvas(32)
    rng = random.Random(8)
    for y in range(32):
        row = y // 6
        k = y % 6
        for x in range(32):
            col = (x + (row % 2) * 3) // 6
            edge = (x + (row % 2) * 3) % 6 == 0
            tone = 2 + (col * 7 + row * 3) % 3
            if k == 5:
                tone = 4 + (1 if rng.random() < 0.3 else 0)
            elif k == 0:
                tone = 1
            if edge:
                tone = 0
            put(img, x, y, SHAKE[min(5, tone)])
    return img


def snow(t: float) -> Image:
    """32 px drifted snow with a soft blue shade and glittering flecks (seamless in t)."""
    img = canvas(32)
    rng = random.Random(30)
    for y in range(32):
        for x in range(32):
            v = 0.5 + 0.5 * math.sin(x / 3.1 + math.sin(y / 4.0) * 1.5) * math.cos(y / 5.3)
            tone = 2 + round(v * 2.0)
            if rng.random() < 0.08:
                tone -= 1
            put(img, x, y, SNOW[max(1, min(5, tone))])
    for i, (sx, sy) in enumerate(((5, 4), (21, 9), (12, 18), (27, 24), (8, 28), (17, 2))):
        amount = max(0.0, math.sin(2 * math.pi * (t - i / 6)))
        if amount > 0.2:
            sparkle(img, sx, sy, amount, "#ffffff", reach=1 + (amount > 0.7))
    return img


def snow_edge() -> Image:
    """16 px front edge of the snow: white top, icicles hanging off its lower half."""
    img = canvas(16)
    for x in range(16):
        drip = (3, 6, 2, 4, 8, 3, 5, 2, 7, 3, 4, 6, 2, 5, 3, 7)[x]
        for y in range(16):
            if y < 7:
                c = SNOW[4] if y < 2 else SNOW[3]
            elif y < 7 + drip:
                c = SNOW[2] if (y - 7) < drip - 1 else SNOW[1]
                if x % 4 == 0 and y < 9:
                    c = SNOW[5]
            else:
                continue
            put(img, x, y, c)
    return img


def window(t: float) -> Image:
    """16 px firelit window: four panes, a cross frame, light breathing and flickering."""
    img = canvas(16)
    flick = 0.5 + 0.30 * math.sin(2 * math.pi * t) + 0.20 * math.sin(2 * math.pi * 3 * t + 1.3)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 10)
            v = max(0.0, 1.0 - d / 11.0) * (0.6 + 0.4 * flick)
            idx = min(6, int(v * 7 + 1))
            put(img, x, y, FIRE[idx])
    for i in range(16):
        for c in (7, 8):
            put(img, c, i, OAK[2])
            put(img, i, c, OAK[2])
    return img


def attic(t: float) -> Image:
    """16 px round attic window, its glow out of step with the lower windows."""
    img = canvas(16, fill=OAK[3])
    flick = 0.5 + 0.35 * math.sin(2 * math.pi * (t + 0.4)) + 0.15 * math.sin(2 * math.pi * 2 * t)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if d < 5.6:
                v = (1 - d / 7) * (0.55 + 0.45 * flick)
                put(img, x, y, FIRE[min(6, int(v * 7 + 1.5))])
            elif d < 7.2:
                put(img, x, y, OAK[5] if y < 8 else OAK[2])
    for i in range(3, 13):
        put(img, 8, i, OAK[1])
    return img


def lantern_glass(t: float) -> Image:
    img = canvas(16)
    flick = 0.5 + 0.35 * math.sin(2 * math.pi * (t + 0.15)) + 0.15 * math.sin(2 * math.pi * 4 * t)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 9)
            v = max(0.0, 1 - d / 9) * (0.6 + 0.4 * flick)
            put(img, x, y, FIRE[min(6, int(v * 7 + 1.5))])
    return img


def keyhole(t: float) -> Image:
    return keyhole_glow(FIRE, t)


def textures() -> None:
    save(bark(), "bark")
    save(log_end(), "log_end")
    save(log_wall(), "log_wall")
    save(chinking(), "chinking")
    save(planks(OAK, size=32, rows=4, seed=3), "oak")
    save(planks(OAK, size=32, rows=4, seed=9, vertical=True), "oak_vertical")
    save(shakes(), "shakes")
    save_animation(animate(snow, 8), "snow", frametime=4, interpolate=True)
    save(snow_edge(), "snow_edge")
    save(metal(BRASS), "brass")
    save(lock_plate(BRASS), "brass_plate")
    save(metal(IRON, rivets=()), "iron")
    save(chunk(STONE, seed=5, density=0.45), "stone")
    save(chunk(PINE, seed=7, density=0.5), "pine")
    save_animation(animate(window, 12), "window", frametime=2, interpolate=True)
    save_animation(animate(attic, 12), "attic", frametime=2, interpolate=True)
    save_animation(animate(lantern_glass, 12), "lantern_glass", frametime=2, interpolate=True)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def log(center, radius: float, length: float, axis: str) -> list[dict]:
    return round_log(center, radius, length, axis, "bark", "log_end")


def logs() -> list[dict]:
    """Front and back logs (running past the corners) and the side log ends between them."""
    parts = []
    for y in FRONT_LOGS:
        for z in (B1 - LOG_R + 0.35, B0 + LOG_R - 0.35):
            parts += log((8.0, y, z), LOG_R, 15.9, "x")
    for y in SIDE_LOGS:
        for x in (B0 + LOG_R - 0.35, B1 - LOG_R + 0.35):
            parts += log((x, y, 8.0), LOG_R * 0.92, 15.9, "z")
    return parts


def side_windows() -> list[dict]:
    """A lamp-lit window in a timber frame with a sill on each side wall, kept inside the block."""
    parts = []
    for outer, side in ((0.05, "west"), (15.95, "east")):
        sx = -1 if side == "west" else 1
        inner = outer - 0.55 * sx            # the frame's back, sunk into the logs
        glass = outer - 0.3 * sx
        a, b = min(outer, inner), max(outer, inner)
        # a ring of frame boards round the opening, the lit glass set back inside it
        parts.append(box((a, 3.2, 5.0), (b, 3.8, 11.0), "oak_vertical"))
        parts.append(box((a, 8.0, 5.0), (b, 8.6, 11.0), "oak_vertical"))
        parts.append(box((a, 3.8, 5.0), (b, 8.0, 5.6), "oak_vertical"))
        parts.append(box((a, 3.8, 10.4), (b, 8.0, 11.0), "oak_vertical"))
        parts.append(only((glass, 3.8, 5.6), (glass, 8.0, 10.4), {side: ("window", [0, 0, 16, 16])}, glow=12,
                          shade=False))
        # sill and lintel, a hair proud of the frame
        lip = outer + 0.12 * sx
        parts.append(box((min(lip, inner), 2.7, 4.6), (max(lip, inner), 3.3, 11.4), "oak"))
        parts.append(box((min(lip, inner), 8.5, 4.7), (max(lip, inner), 9.1, 11.3), "oak"))
        parts.append(box((min(lip, glass), 3.3, 4.8), (max(lip, glass), 3.6, 11.2), "snow", uv="full"))
    return parts


def roof() -> list[dict]:
    parts = []
    ridge_y = LID_TOP + RISE
    eave_y = LID_TOP + 0.2
    for sz in (1, -1):
        zc = 8.0 + sz * 7.8                     # eave line, inside the block
        # shake slope
        parts.append(bar((8.0, eave_y, zc), (8.0, ridge_y, 8.0), 16.0, 0.8, "shakes",
                         roll=0 if sz == 1 else 180))
        # snow on the upper two thirds, lifted off the shakes along the slope's normal
        dz, dy = 8.0 - zc, ridge_y - eave_y
        length = math.hypot(dz, dy)
        nz, ny = sz * dy / length, abs(dz) / length
        lift = 0.62
        f = 0.36
        z0, y0 = zc + dz * f + nz * lift, eave_y + dy * f + ny * lift
        z1, y1 = 8.0 + nz * lift, ridge_y + ny * lift
        parts.append(bar((8.0, y0, z0), (8.0, y1, z1), 15.6, 0.6, "snow", uv="full"))
        # a snow lip along the eave with icicles hanging off it
        ze = zc + 0.05 * sz
        parts.append(only((0.05, eave_y - 1.5, ze + 0.3 * sz), (15.95, eave_y + 0.35, ze + 0.3 * sz),
                          {("south" if sz == 1 else "north"): ("snow_edge", [0, 0, 16, 16])}))
        parts.append(box((0.05, eave_y + 0.05, min(ze, ze + 0.3 * sz)), (15.95, eave_y + 0.35, max(ze, ze + 0.3 * sz)),
                         "snow", uv="full"))
    # gable ends (vertical boards) stepped to the roof line, with a round attic window
    steps = 4
    for x0, x1, side in ((0.15, 0.95, "west"), (15.05, 15.85, "east")):
        for k in range(steps):
            y0 = LID_TOP + k * (RISE / steps)
            y1 = y0 + RISE / steps + 0.05
            half = 7.8 * (1 - (k + 0.5) / steps) + 0.15
            parts.append(box((x0, y0, 8.0 - half), (x1, y1, 8.0 + half), "oak_vertical"))
        xf = x0 - 0.05 if side == "west" else x1 + 0.05
        parts.append(only((xf, LID_TOP + 0.35, 7.0), (xf, LID_TOP + 1.75, 9.0),
                          {side: ("attic", [0, 0, 16, 16])}, glow=11, shade=False))
    # ridge log with a cap of snow
    parts += log((8.0, ridge_y + 0.35, 8.0), 0.8, 15.9, "x")
    parts.append(box((0.1, ridge_y + 0.95, 7.2), (15.9, ridge_y + 1.4, 8.8), "snow", uv="full"))
    return parts


def lid_panel() -> list[dict]:
    """A raised board frame on the lid's front with a brass pine emblem either side of the hasp."""
    z = L1
    parts = lid_frame("oak")
    for cx in (4.4, 11.6):
        # a little snowy pine carved in relief: a trunk, three stepped tiers, snow on each tier
        parts.append(box((cx - 0.3, SEAM + 1.2, z), (cx + 0.3, SEAM + 1.7, z + 0.35), "oak"))
        for k, (w, y0) in enumerate(((1.6, SEAM + 1.6), (1.15, SEAM + 2.35), (0.65, SEAM + 3.05))):
            d = 0.4 + k * 0.08
            parts.append(box((cx - w, y0, z), (cx + w, y0 + 0.85, z + d), "pine"))
            parts.append(box((cx - w + 0.25, y0 + 0.7, z), (cx + w - 0.25, y0 + 0.95, z + d), "snow",
                             uv="full"))
    return parts


def chimney() -> list[dict]:
    x0, x1 = 10.6, 13.4
    z0, z1 = 3.4, 6.2
    return [
        box((x0, LID_TOP + 0.6, z0), (x1, LID_TOP + 5.0, z1), "stone"),
        box((x0 - 0.35, LID_TOP + 4.8, z0 - 0.35), (x1 + 0.35, LID_TOP + 5.5, z1 + 0.35), "brass"),
        box((x0 - 0.15, LID_TOP + 5.5, z0 - 0.15), (x1 + 0.15, LID_TOP + 5.85, z1 + 0.15), "snow", uv="full"),
        # soot-dark flue mouth glowing faintly
        only((x0 + 0.5, LID_TOP + 5.86, z0 + 0.5), (x1 - 0.5, LID_TOP + 5.86, z1 - 0.5),
             {"up": ("lantern_glass", [0, 0, 16, 16])}, glow=6, shade=False),
    ]


def lantern(cx: float, cz: float, base: float) -> list[dict]:
    """A little iron lantern standing at (cx, base, cz) with a carry handle (the Homestead's lid uses it)."""
    posts = [box((cx + sx * 0.75 - 0.13, base + 0.4, cz + sz * 0.75 - 0.13),
                 (cx + sx * 0.75 + 0.13, base + 2.7, cz + sz * 0.75 + 0.13), "iron")
             for sx in (-1, 1) for sz in (-1, 1)]
    return [
        box((cx - 0.95, base, cz - 0.95), (cx + 0.95, base + 0.4, cz + 0.95), "iron"),            # foot
        box((cx - 0.75, base + 0.4, cz - 0.75), (cx + 0.75, base + 2.7, cz + 0.75), "lantern_glass", glow=13,
            shade=False, uv="full"),
        *posts,
        box((cx - 0.95, base + 2.7, cz - 0.95), (cx + 0.95, base + 3.15, cz + 0.95), "iron"),     # cap
        box((cx - 0.5, base + 3.15, cz - 0.5), (cx + 0.5, base + 3.5, cz + 0.5), "iron"),
        box((cx - 0.7, base + 3.5, cz - 0.1), (cx - 0.5, base + 4.3, cz + 0.1), "iron"),          # handle
        box((cx + 0.5, base + 3.5, cz - 0.1), (cx + 0.7, base + 4.3, cz + 0.1), "iron"),
        box((cx - 0.7, base + 4.3, cz - 0.1), (cx + 0.7, base + 4.5, cz + 0.1), "iron"),
    ]


def models() -> dict:
    p = shell("log_wall", "oak", "oak",
              faces_body={"north": "chinking", "south": "chinking"})
    p += logs()
    p += side_windows()
    p += rims("brass")
    p += lock("brass_plate", "keyhole", "brass", width=5.4)
    p += hinges("iron")
    p += lid_panel()
    p += roof()
    p += chimney()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.5))}
