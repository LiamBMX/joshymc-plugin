"""Large Gold Pile (issue #1286): a vault-sized hoard of gold coins.

A tall, wide five-step mound of coins that fills its block, crowned with struck coins
tipped down its slopes, four hand-stacked coin columns of different heights standing
round it (one leaning on the heap), two coins rolled away on their edges and a wide
spill of loose coins across the floor. Placeable furniture (block space, front = +Z).
"""
from __future__ import annotations

import random

from art.items._treasure import (COUNTERPART, KIND, mound, mound_coins, paint, place,  # noqa: F401
                                 scatter_coins, stack)
from art.kit import display, model

ID = "large_gold_pile"
NAME = "Large Gold Pile"

LAYERS = [(6.5, 1.1), (5.9, 2.1), (5.3, 3.1), (4.7, 4.0), (4.0, 4.9), (3.3, 5.8), (2.6, 6.6), (1.9, 7.4), (1.1, 8.1)]


def textures() -> None:
    paint("coin", "coin_edge", "coin_rim", "coin_stack", "heap", "heap_side")


def parts() -> list[dict]:
    rng = random.Random(7)
    out = mound(LAYERS)
    out += mound_coins(rng, LAYERS, 30)
    out += stack(13.6, 0.0, 11.8, 12)
    out += stack(2.4, 0.0, 12.6, 7)
    out += stack(13.8, 0.0, 3.0, 9)
    out += place(stack(2.6, 0.0, 3.4, 10), (2.6, 0.0, 3.4), tilt=14, toward=135)
    out += scatter_coins(rng, 9, 6.6, 7.8, standing=2)
    return out


def models() -> dict:
    p = parts()
    return {"main": model(p, display(KIND, p, size=1.3, gui_rotation=(28, 25, 0), gui_span=15.5))}
