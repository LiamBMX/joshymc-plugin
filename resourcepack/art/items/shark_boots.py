"""Shark Boots: dark gray boots with white trim at the cuff, light-blue accents and a
pale sole. Flat worn texture (the lower rows of each leg) and a flat inventory icon."""
from __future__ import annotations

from art import shark
from art.kit import HUMANOID, canvas, fill, region, save, save_layer, sprite

ID = "shark_boots"
NAME = "Shark Boots"
KIND = "boots"
COUNTERPART = "item/netherite_boots"

S = 2


def _worn():
    img = canvas(64 * S, 32 * S)
    for seed, name in enumerate(("leg_top", "leg_outer", "leg_front", "leg_inner", "leg_back")):
        r = region(HUMANOID, name, S)
        if name == "leg_top":
            shark.gray_surface(img, r, seed=seed)
            continue
        # Boots cover the lower 7 of the leg's 24 rows (3.5 of its 12 pixels).
        top = r[3] - 6
        shark.gray_surface(img, (r[0], top, r[2], r[3]), seed=seed)
        fill(img, (r[0], top, r[2], top + 1), shark.BELLY[3])               # white cuff trim
        fill(img, (r[0], top + 2, r[2], top + 2), shark.BELLY[0])
        fill(img, (r[0], top + 3, r[2], top + 3), "#6fc3e6")                # light-blue accent
        fill(img, (r[0], r[3] - 1, r[2], r[3]), shark.GRAY[0])              # sole edge
    sole = region(HUMANOID, "leg_sole", S)
    fill(img, sole, shark.GRAY[1])
    shark.speckle(img, sole, [shark.GRAY[0], shark.GRAY[2]], 0.2, 7)
    return img


def _icon():
    img = canvas(16)
    fill(img, (3, 2, 8, 9), shark.GRAY[3])          # shaft
    fill(img, (3, 9, 13, 13), shark.GRAY[3])        # foot
    fill(img, (3, 2, 8, 3), shark.BELLY[3])         # white cuff
    fill(img, (3, 4, 8, 4), "#6fc3e6")              # light-blue accent
    shark.shade_rows(img, (3, 5, 8, 8), [shark.GRAY[4], shark.GRAY[3], shark.GRAY[2]])
    shark.shade_rows(img, (3, 9, 13, 12), [shark.GRAY[4], shark.GRAY[3], shark.GRAY[2]])
    fill(img, (12, 9, 13, 9), (0, 0, 0, 0))         # rounded toe
    fill(img, (3, 13, 13, 13), shark.BELLY[1])      # pale sole
    fill(img, (9, 10, 12, 11), shark.BELLY[2])      # underbelly toe cap
    return shark.icon_outline(img)


def textures() -> None:
    save(_icon(), "icon")
    save_layer(_worn(), "humanoid")


def models() -> dict:
    return {"main": sprite("icon")}
