"""Small Gold Pile (issue #1286): a little heap of gold coins for a table, shelf or floor.

A low two-step mound of coins with a few struck coins lying on top, a short hand-stacked
column of coins leaning against it, one coin rolled away on its edge and a scatter of
loose coins round its foot. Placeable furniture (block space, front = +Z).
"""
from __future__ import annotations

import random

from art.items._treasure import COUNTERPART, KIND, coin, mound, mound_coins, paint, scatter_coins, stack  # noqa: F401
from art.kit import display, model

ID = "small_gold_pile"
NAME = "Small Gold Pile"

LAYERS = [(4.1, 1.2), (3.4, 2.3), (2.6, 3.3), (1.8, 4.2), (1.0, 4.9)]


def textures() -> None:
    paint("coin", "coin_edge", "coin_rim", "coin_stack", "heap", "heap_side")


def parts() -> list[dict]:
    rng = random.Random(1)
    out = mound(LAYERS, cx=7.6, cz=7.4)
    out += mound_coins(rng, LAYERS, 14, cx=7.6, cz=7.4)
    out += stack(11.4, 0.0, 9.6, 6)
    out += stack(4.4, 0.0, 10.4, 3)
    out += scatter_coins(rng, 7, 4.6, 6.2, cx=7.6, cz=7.6, standing=1)
    return out


def models() -> dict:
    p = parts()
    return {"main": model(p, display(KIND, p, size=1.5, gui_rotation=(28, 25, 0), gui_span=15.0))}
