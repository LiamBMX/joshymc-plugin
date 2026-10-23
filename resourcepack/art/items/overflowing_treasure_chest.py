"""Overflowing Treasure Chest (issue #1286): an open pirate chest spilling over with gold.

A dark-oak chest on four gilded feet, bound with gold straps, corner brackets and a rim
band, an iron lock plate with a keyhole on its front. The barrel-topped lid stands open
on its back hinge, lined with purple velvet, and a domed heap of coins rises far above
the rim, studded with a ruby, a sapphire, an emerald and a diamond. Coins tip over the
front edge, a pearl strand with a sapphire pendant hangs over the front and loose coins
and a gem lie spilled on the floor in front. Decorative furniture, not a container.
Block space, front (lock) = +Z, hinge at the back.
"""
from __future__ import annotations

import math
import random

from art.items._treasure import (COUNTERPART, GEMS, KIND, brilliant, cabochon, chip, coin, mound,  # noqa: F401
                                 mound_coins, necklace, octahedron, only, paint, slope_at, step_cut)
from art.kit import arc, box, display, model, turn

ID = "overflowing_treasure_chest"
NAME = "Overflowing Treasure Chest"

X0, X1 = 1.5, 14.5           # body width
Z0, Z1 = 4.0, 12.5           # body depth (front = Z1)
Y0, Y1 = 0.9, 8.0            # body bottom (on its feet) and rim
WALL = 0.9
HINGE = (8.0, Y1, Z0 + 0.7)
LID_OPEN = -100.0            # degrees about X: the lid swings up and back
MX, MZ = 8.0, (Z0 + Z1) / 2
LAYERS = [(4.6, Y1 + 0.6), (3.9, Y1 + 1.6), (3.1, Y1 + 2.5), (2.3, Y1 + 3.3), (1.4, Y1 + 3.9)]


def textures() -> None:
    paint("wood", "band", "keyhole", "velvet", "gold", "coin", "coin_edge", "coin_rim", "heap", "heap_side",
          "pearl", *GEMS, "diamond_pavilion")


def body() -> list[dict]:
    w = WALL
    parts = [
        box((X0, Y0, Z1 - w), (X1, Y1, Z1), "wood"),                       # front
        box((X0, Y0, Z0), (X1, Y1, Z0 + w), "wood"),                       # back
        box((X0, Y0, Z0 + w), (X0 + w, Y1, Z1 - w), "wood"),               # left
        box((X1 - w, Y0, Z0 + w), (X1, Y1, Z1 - w), "wood"),               # right
        box((X0 + w, Y0, Z0 + w), (X1 - w, Y1 - 0.8, Z1 - w), "heap", skip=("down",)),   # coins to the brim
    ]
    e = 0.22
    # Gold rim band and base band all round.
    for y0, y1 in ((Y1 - 0.9, Y1 + 0.1), (Y0, Y0 + 0.8)):
        parts.append(box((X0 - e, y0, Z0 - e), (X1 + e, y1, Z1 + e), "band", skip=("down",) if y0 == Y0 else ()))
    # Two straps over the front, top rim and back.
    for sx in (3.4, 11.4):
        parts.append(box((sx, Y0, Z1), (sx + 1.2, Y1, Z1 + e + 0.08), "band", skip=("north",)))
        parts.append(box((sx, Y0, Z0 - e - 0.08), (sx + 1.2, Y1, Z0), "band", skip=("south",)))
    # Corner brackets.
    for cx in (X0, X1):
        for cz in (Z0, Z1):
            sx, sz = (1 if cx == X0 else -1), (1 if cz == Z0 else -1)
            b = e + 0.06            # just proud of the rim and base bands, so they never z-fight
            parts.append(box((cx - b * sx, Y0, cz - b * sz), (cx + 1.3 * sx, Y1 + 0.05, cz + 1.3 * sz), "band"))
    # Gilded feet.
    for fx in (X0 + 0.9, X1 - 0.9):
        for fz in (Z0 + 0.9, Z1 - 0.9):
            parts.append(box((fx - 0.9, 0.0, fz - 0.9), (fx + 0.9, Y0, fz + 0.9), "gold"))
    # Lock plate, keyhole and the hasp tongue.
    parts.append(box((6.6, 4.4, Z1), (9.4, 7.4, Z1 + 0.45), "band", faces={"south": ("keyhole", [0, 0, 16, 16])}))
    parts.append(box((6.3, 7.1, Z1), (9.7, 7.6, Z1 + 0.55), "band"))
    for rx in (6.9, 9.1):
        for ry in (4.8, 6.9):
            parts.append(box((rx - 0.18, ry - 0.18, Z1 + 0.45), (rx + 0.18, ry + 0.18, Z1 + 0.6), "gold"))
    return parts


def lid() -> list[dict]:
    """The closed lid (an arched barrel top over the body), then swung open on the hinge."""
    r = 5.0
    cy = Y1 - 2.4
    depth = (Z1 - Z0) / 2
    start = math.degrees(math.acos(depth / r))
    centre = (8.0, cy, MZ)
    parts = arc(centre, r, start, 180 - start, 6, X1 - X0, 0.9, "wood", plane="zy")
    for sx in (3.4, 11.4):
        parts += arc((sx + 0.6, cy, MZ), r + 0.3, start, 180 - start, 6, 1.2, 0.35, "band", plane="zy")
    parts += arc((8.0, cy, MZ), r + 0.3, start, 180 - start, 6, 0.6, 0.35, "band", plane="zy")
    # Gable ends: stepped panels filling the arch on both sides.
    top = cy + r
    for x0, x1 in ((X0, X0 + 0.8), (X1 - 0.8, X1)):
        for y0, y1, half in ((Y1, Y1 + 1.0, depth), (Y1 + 1.0, Y1 + 1.8, depth - 0.8), (Y1 + 1.8, top - 0.2, depth - 2.1)):
            parts.append(box((x0, y0, MZ - half), (x1, y1, MZ + half), "wood"))
    # Velvet lining across the lid's opening.
    parts.append(only((X0 + 0.6, Y1 + 0.05, Z0 + 0.4), (X1 - 0.6, Y1 + 0.05, Z1 - 0.4),
                      {"down": ("velvet", [0, 0, 13, 8])}))
    # The lid's front rim band.
    parts.append(box((X0 - 0.2, Y1, Z1 - 0.6), (X1 + 0.2, Y1 + 0.8, Z1 + 0.2), "band"))
    # The hasp hanging from the lid's front.
    parts.append(box((7.4, Y1 - 1.2, Z1 + 0.2), (8.6, Y1 + 0.6, Z1 + 0.5), "band"))
    turn(parts, LID_OPEN, "x", HINGE)
    return parts


def treasure() -> list[dict]:
    rng = random.Random(9)
    parts = mound(LAYERS, cx=MX, cz=MZ, y0=Y1 - 0.8)
    parts += mound_coins(rng, LAYERS, 14, cx=MX, cz=MZ, y0=Y1 - 0.8)

    def at(dx, dz):
        d = math.hypot(dx, dz)
        h, slope = slope_at(LAYERS, d, Y1 - 0.8)
        return MX + dx, h, MZ + dz, slope, math.degrees(math.atan2(dx, dz))

    x, y, z, s, f = at(-2.4, 1.2)
    parts += octahedron(x, y - 0.4, z, 1.9, "ruby", tilt=s * 0.4, toward=f)
    x, y, z, s, f = at(2.6, 0.8)
    parts += cabochon(x, y - 0.3, z, 1.5, "sapphire", tilt=s * 0.7, toward=f)
    x, y, z, s, f = at(1.0, -1.8)
    parts += step_cut(x, y - 0.3, z, 2.0, 3.0, 1.5, "emerald", tilt=s * 0.7, toward=f)
    x, y, z, s, f = at(-0.3, 0.2)
    parts += brilliant(x, y - 0.5, z, 1.6, "diamond", tilt=24, toward=0)
    for i, kind in enumerate(GEMS):
        a = math.radians(i * 90 + 40)
        x, y, z, _, _ = at(3.2 * math.sin(a), 2.6 * math.cos(a))
        parts += chip(x, y, z, 0.85, kind, yaw=i * 30)
    # Coins tipping over the front rim and lying spilled on the floor.
    for cx, tilt, toward in ((5.0, 62, 6), (9.8, 70, -10), (12.4, 55, 20)):
        parts += coin(cx, Y1 - 0.4, Z1 + 0.35, tilt=tilt, toward=toward)
    for x, z, tilt, toward in ((4.4, 14.4, 0, 0), (6.4, 15.0, 12, 160), (10.6, 14.6, 0, 0), (12.6, 15.2, 20, 30),
                               (8.4, 14.0, 14, 200)):
        parts += coin(x, 0.0 if tilt < 60 else 1.35, z, tilt=tilt, toward=toward)
    parts += chip(2.6, 0.0, 14.6, 1.1, "emerald", yaw=20)
    # A pearl strand hanging over the front with a sapphire pendant.
    parts += necklace([(4.2, Y1 + 0.3, Z1 + 0.5), (5.2, Y1 - 1.6, Z1 + 0.75), (6.6, Y1 - 2.6, Z1 + 0.8),
                       (8.0, Y1 - 2.9, Z1 + 0.8), (9.4, Y1 - 2.4, Z1 + 0.8), (10.4, Y1 - 1.2, Z1 + 0.75),
                       (11.0, Y1 + 0.3, Z1 + 0.5)], 0.6, pendant="sapphire")
    return parts


def models() -> dict:
    p = body() + lid() + treasure()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.5))}
