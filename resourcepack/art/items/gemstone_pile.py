"""Gemstone Pile (issue #1286): a jeweller's spill of cut stones on a velvet cloth.

A square of crushed purple velvet with a gold fringe and corner tassels, lying in soft
folds, and on it a heap of the four stones, each with its own cut and colour so they
read apart at a glance: a tall round brilliant diamond (pale cyan) at the back, a long
step-cut emerald, an octahedral ruby and a domed star sapphire in front, smaller stones
of every kind propping them up and loose chips scattered across the cloth. The stones
catch a little light in the dark and glint as you look at them. Placeable furniture
(block space, front = +Z).
"""
from __future__ import annotations

import random

from art.items._treasure import (COUNTERPART, GEMS, KIND, brilliant, cabochon, chip, octahedron,  # noqa: F401
                                 paint, step_cut)
from art.kit import bar, box, display, model, turn

ID = "gemstone_pile"
NAME = "Gemstone Pile"

CLOTH_Y = 0.4


def textures() -> None:
    paint("velvet", "fringe", "gold", *GEMS, "diamond_pavilion")


def cloth() -> list[dict]:
    """The velvet square, a little turned, with folds, a fringe and four tassels."""
    y = CLOTH_Y
    parts = [box((2.2, 0.0, 2.2), (13.8, y, 13.8), "velvet", faces={side: "fringe" for side in
                                                                     ("north", "south", "east", "west")})]
    parts.append(bar((3.0, y - 0.1, 5.0), (12.6, y - 0.1, 3.6), 0.9, 0.55, "velvet"))     # soft folds
    parts.append(bar((2.8, y - 0.1, 11.2), (9.6, y - 0.1, 13.2), 0.8, 0.5, "velvet"))
    for x, z in ((2.2, 2.2), (13.8, 2.2), (2.2, 13.8), (13.8, 13.8)):
        parts.append(box((x - 0.4, 0.45, z - 0.4), (x + 0.4, 0.85, z + 0.4), "gold"))
        parts.append(box((x - 0.55, 0.0, z - 0.55), (x + 0.55, 0.47, z + 0.55), "gold",
                         faces={side: "fringe" for side in ("north", "south", "east", "west")}))
    turn(parts, 8, "y", (8, 0, 8))
    return parts


def stones() -> list[dict]:
    y = CLOTH_Y
    parts = []
    # Supporting stones under and round the heroes.
    parts += step_cut(10.2, y, 5.0, 2.0, 2.8, 1.5, "emerald", tilt=-18, toward=60)
    parts += cabochon(5.2, y, 5.6, 1.4, "sapphire")
    parts += octahedron(6.9, y, 4.2, 1.5, "ruby", tilt=12, toward=200)
    parts += brilliant(11.8, y, 7.8, 1.3, "diamond", tilt=30, toward=110)
    parts += octahedron(4.0, y, 8.0, 1.3, "ruby", tilt=-20, toward=250)
    parts += cabochon(10.6, y, 10.4, 1.15, "sapphire")
    # The four heroes.
    parts += brilliant(8.0, y + 1.7, 6.4, 2.5, "diamond", tilt=26, toward=0)
    parts += step_cut(4.9, y, 10.2, 2.9, 4.2, 2.1, "emerald", tilt=10, toward=35)
    parts += octahedron(11.6, y, 11.4, 2.1, "ruby", tilt=-10, toward=-20)
    parts += cabochon(8.1, y, 11.3, 1.9, "sapphire", tilt=6)
    # Loose chips across the cloth.
    rng = random.Random(12)
    spots = [(3.2, 12.6), (6.6, 13.2), (13.0, 13.4), (13.2, 4.4), (2.9, 3.4), (7.6, 2.6), (12.4, 2.6),
             (9.6, 13.4), (3.0, 6.2), (13.4, 9.0), (6.4, 8.6), (9.6, 8.8)]
    kinds = list(GEMS) * 3
    for (x, z), kind in zip(spots, kinds):
        parts += chip(x, y, z, rng.uniform(0.8, 1.15), kind, yaw=rng.uniform(0, 90))
    return parts


def models() -> dict:
    p = cloth() + stones()
    return {"main": model(p, display(KIND, p, size=1.6, gui_rotation=(28, 25, 0), gui_span=15.5))}
