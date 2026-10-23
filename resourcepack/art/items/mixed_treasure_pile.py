"""Mixed Treasure Pile (issue #1286): gold coins and gemstones heaped together.

A domed mound of gold coins studded with all four stones (an octahedral ruby, a domed
star sapphire, a step-cut emerald and a brilliant diamond tipped into the coins), a
pearl strand with a ruby pendant draped down its front, a jewelled gold goblet heaped
with coins standing behind it, a short stack of coins and loose coins and gem chips
spilled round its foot. Placeable furniture (block space, front = +Z).
"""
from __future__ import annotations

import math
import random

from art.items._treasure import (COUNTERPART, GEMS, KIND, brilliant, cabochon, chip, goblet, mound,  # noqa: F401
                                 mound_coins, necklace, octahedron, paint, scatter_coins, slope_at, stack,
                                 step_cut)
from art.kit import display, model

ID = "mixed_treasure_pile"
NAME = "Mixed Treasure Pile"

MX, MZ = 8.8, 8.4
LAYERS = [(5.2, 1.2), (4.5, 2.3), (3.8, 3.3), (3.0, 4.3), (2.2, 5.2), (1.3, 5.9)]


def textures() -> None:
    paint("coin", "coin_edge", "coin_rim", "coin_stack", "heap", "heap_side", "polished", "pearl", *GEMS, "diamond_pavilion")


def on_mound(dx: float, dz: float) -> tuple[float, float, float, float, float]:
    """(x, y, z, slope, facing) of a point on the mound dx, dz from its centre."""
    d = math.hypot(dx, dz)
    h, slope = slope_at(LAYERS, d)
    return MX + dx, h, MZ + dz, slope, math.degrees(math.atan2(dx, dz))


def stones() -> list[dict]:
    parts = []
    x, y, z, slope, face = on_mound(-1.6, 2.2)
    parts += octahedron(x, y - 0.5, z, 2.0, "ruby", tilt=slope * 0.4, toward=face)
    x, y, z, slope, face = on_mound(2.4, 1.6)
    parts += cabochon(x, y - 0.4, z, 1.6, "sapphire", tilt=slope * 0.8, toward=face)
    x, y, z, slope, face = on_mound(1.2, -1.6)
    parts += step_cut(x, y - 0.3, z, 2.2, 3.2, 1.6, "emerald", tilt=slope * 0.8, toward=face)
    x, y, z, slope, face = on_mound(-0.4, 0.1)
    parts += brilliant(x, y - 0.6, z, 1.7, "diamond", tilt=22, toward=20)
    rng = random.Random(5)
    for i, kind in enumerate(list(GEMS) * 2):
        a = math.radians(i * 47 + 15)
        d = rng.uniform(1.4, 4.4)
        x, y, z, _, _ = on_mound(d * math.sin(a), d * math.cos(a))
        parts += chip(x, y, z, rng.uniform(0.7, 1.0), kind, yaw=rng.uniform(0, 90))
    return parts


def pearls() -> list[dict]:
    points = []
    for dx, dz in ((-3.6, 1.2), (-2.8, 2.9), (-1.2, 4.3), (0.6, 4.5), (2.2, 3.6), (3.4, 2.4)):
        x, y, z, _, _ = on_mound(dx, dz)
        points.append((x, y + 0.7, z))
    return necklace(points, 0.72, pendant="ruby")


def parts() -> list[dict]:
    rng = random.Random(3)
    out = mound(LAYERS, cx=MX, cz=MZ)
    out += mound_coins(rng, LAYERS, 12, cx=MX, cz=MZ)
    out += stones()
    out += pearls()
    out += goblet(3.4, 0.0, 3.8, 1.4)
    out += stack(13.4, 0.0, 13.4, 5)
    out += scatter_coins(rng, 6, 5.8, 7.2, cx=MX, cz=MZ, standing=1)
    for (x, z), kind in zip(((2.6, 11.0), (6.0, 14.2), (14.0, 5.8), (11.2, 2.6)), GEMS):
        out += chip(x, 0.0, z, 0.95, kind, yaw=x * 20)
    return out


def models() -> dict:
    p = parts()
    return {"main": model(p, display(KIND, p, size=1.3, gui_rotation=(28, 25, 0), gui_span=15.5))}
