"""Shared palette and painters for the Shark Set (art/items/shark_*.py).

Dark shark-gray armor, deep ocean-blue accents, a pale underbelly and cyan highlights.
All four pieces draw from the same palette so the set reads as one.
"""
from __future__ import annotations

import random

from art.kit import canvas, fill, outline, rgba

# Darkest -> lightest.
GRAY = ["#1c2530", "#2b3846", "#3c4d5e", "#51667a", "#6b8196", "#8aa0b3"]
BELLY = ["#aab8c4", "#c6d1da", "#dfe7ed", "#f2f6f9", "#ffffff"]
OCEAN = ["#0d2a4a", "#12406b", "#1a5a8f", "#2478ad", "#3a97c4"]
CYAN = ["#2fc1d6", "#5fe0ee", "#a6f4fb"]
OUTLINE = "#0c141c"
TOOTH = ["#d8dfe5", "#f4f7f9", "#ffffff"]
EYE = ["#05080c", "#ffffff"]
GILL = "#10181f"


def put(img, x, y, colour) -> None:
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((int(x), int(y)), rgba(colour))


def shade_rows(img, box, colours) -> None:
    """Banded vertical gradient (first colour at the top row) across the inclusive box."""
    x0, y0, x1, y1 = box
    n = y1 - y0 + 1
    for i in range(n):
        fill(img, (x0, y0 + i, x1, y0 + i), colours[min(len(colours) - 1, i * len(colours) // n)])


def speckle(img, box, colours, density=0.12, seed=0) -> None:
    rng = random.Random(seed)
    x0, y0, x1, y1 = box
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if rng.random() < density:
                put(img, x, y, rng.choice(colours))


# --------------------------------------------------------------------------------------
# Small tiles for the 3D helmet (stretched over each face with uv="full")
# --------------------------------------------------------------------------------------

def shell_tile(seed: int = 1):
    img = canvas(16)
    shade_rows(img, (0, 0, 15, 15), [GRAY[4], GRAY[3], GRAY[3], GRAY[2], GRAY[2], GRAY[1]])
    speckle(img, (0, 0, 15, 15), [GRAY[2], GRAY[4], GRAY[5]], 0.14, seed)
    fill(img, (0, 0, 15, 0), GRAY[5])
    fill(img, (0, 15, 15, 15), GRAY[0])
    return img


def belly_tile():
    img = canvas(16)
    shade_rows(img, (0, 0, 15, 15), [BELLY[3], BELLY[2], BELLY[2], BELLY[1], BELLY[0]])
    speckle(img, (0, 0, 15, 15), [BELLY[4], BELLY[1]], 0.1, 5)
    return img


def tooth_tile():
    img = canvas(16)
    shade_rows(img, (0, 0, 15, 15), [TOOTH[2], TOOTH[2], TOOTH[1], TOOTH[1], TOOTH[0], TOOTH[0]])
    return img


def eye_tile():
    img = canvas(16)
    fill(img, (0, 0, 15, 15), EYE[0])
    fill(img, (2, 2, 13, 13), "#0b1620")
    fill(img, (3, 3, 7, 7), EYE[1])
    fill(img, (9, 9, 12, 12), CYAN[0])
    return img


def gill_tile():
    img = canvas(16)
    shade_rows(img, (0, 0, 15, 15), [GRAY[3], GRAY[2], GRAY[2], GRAY[1]])
    for x in (2, 6, 10, 13):
        fill(img, (x, 1, x + 1, 14), GILL)
        fill(img, (x + 2, 2, x + 2, 13), GRAY[4])
    return img


def fin_tile():
    img = canvas(16)
    shade_rows(img, (0, 0, 15, 15), [GRAY[4], GRAY[3], GRAY[2], GRAY[1], OCEAN[1], OCEAN[0]])
    fill(img, (0, 0, 15, 0), CYAN[1])
    return img


def band_tile():
    img = canvas(16)
    shade_rows(img, (0, 0, 15, 15), [OCEAN[3], OCEAN[2], OCEAN[2], OCEAN[1], OCEAN[0]])
    fill(img, (0, 0, 15, 1), CYAN[0])
    return img


# --------------------------------------------------------------------------------------
# Worn-armor surfaces
# --------------------------------------------------------------------------------------

def gray_surface(img, box, seed=0) -> None:
    """Dark shark-gray skin with a lighter top and darker bottom."""
    shade_rows(img, box, [GRAY[4], GRAY[3], GRAY[3], GRAY[2], GRAY[2], GRAY[1]])
    speckle(img, box, [GRAY[2], GRAY[4]], 0.12, seed)


def belly_surface(img, box, seed=0) -> None:
    shade_rows(img, box, [BELLY[3], BELLY[2], BELLY[2], BELLY[1], BELLY[0]])
    speckle(img, box, [BELLY[4], BELLY[1]], 0.08, seed)


def trim(img, box, top=True, bottom=True) -> None:
    """Ocean-blue edging with a cyan highlight line."""
    x0, y0, x1, y1 = box
    if top:
        fill(img, (x0, y0, x1, y0), CYAN[0])
        fill(img, (x0, y0 + 1, x1, y0 + 1), OCEAN[3])
    if bottom:
        fill(img, (x0, y1, x1, y1), OCEAN[0])
        fill(img, (x0, y1 - 1, x1, y1 - 1), OCEAN[2])


def gills(img, x, y, count=3, length=5, step=2) -> None:
    """Slanted gill slits starting at (x, y)."""
    for i in range(count):
        for j in range(length):
            put(img, x + i * step + j // 3, y + j, GILL)


def icon_outline(img):
    return outline(img, OUTLINE)
