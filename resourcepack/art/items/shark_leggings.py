"""Shark Leggings: dark gray shark-skin leggings with blue-gray shading, a white inner
thigh and an ocean-blue belt with a cyan edge. Flat worn texture and a flat inventory icon."""
from __future__ import annotations

from art import shark
from art.kit import HUMANOID, canvas, fill, region, save, save_layer, sprite

ID = "shark_leggings"
NAME = "Shark Leggings"
KIND = "leggings"
COUNTERPART = "item/netherite_leggings"

S = 2


def _worn():
    img = canvas(64 * S, 32 * S)
    names = ("body_top", "body_bottom", "body_right", "body_front", "body_left", "body_back",
             "leg_top", "leg_sole", "leg_outer", "leg_front", "leg_inner", "leg_back")
    for seed, name in enumerate(names):
        shark.gray_surface(img, region(HUMANOID, name, S), seed=seed)
    # Pale inner thigh and a lighter stripe down the front of each leg.
    x0, y0, x1, y1 = region(HUMANOID, "leg_inner", S)
    shark.belly_surface(img, (x0, y0 + 2, x1, y1 - 3), seed=9)
    fx0, fy0, fx1, fy1 = region(HUMANOID, "leg_front", S)
    fill(img, (fx0 + 3, fy0 + 6, fx0 + 4, fy1 - 4), shark.OCEAN[2])
    # Belt around the waist (top of the body box), cuffs at the bottom of the legs.
    for name in ("body_front", "body_back", "body_right", "body_left"):
        r = region(HUMANOID, name, S)
        fill(img, (r[0], r[1], r[2], r[1] + 3), shark.OCEAN[2])
        fill(img, (r[0], r[1], r[2], r[1]), shark.CYAN[0])
        fill(img, (r[0], r[1] + 3, r[2], r[1] + 3), shark.OCEAN[0])
    for name in ("leg_outer", "leg_front", "leg_inner", "leg_back"):
        r = region(HUMANOID, name, S)
        shark.trim(img, (r[0], r[3] - 3, r[2], r[3]), top=True, bottom=True)
    # A fin-shaped notch of pale gray at the front of the belt.
    bx0, by0, bx1, _ = region(HUMANOID, "body_front", S)
    cx = (bx0 + bx1) // 2
    fill(img, (cx - 1, by0 + 1, cx, by0 + 2), shark.BELLY[1])
    return img


def _icon():
    img = canvas(16)
    fill(img, (3, 1, 12, 4), shark.OCEAN[2])
    fill(img, (3, 1, 12, 1), shark.CYAN[0])
    fill(img, (3, 5, 12, 7), shark.GRAY[3])
    fill(img, (3, 8, 6, 14), shark.GRAY[3])
    fill(img, (9, 8, 12, 14), shark.GRAY[3])
    shark.shade_rows(img, (3, 5, 12, 7), [shark.GRAY[4], shark.GRAY[3]])
    shark.shade_rows(img, (3, 8, 6, 14), [shark.GRAY[3], shark.GRAY[2], shark.GRAY[1]])
    shark.shade_rows(img, (9, 8, 12, 14), [shark.GRAY[3], shark.GRAY[2], shark.GRAY[1]])
    fill(img, (6, 8, 6, 12), shark.BELLY[2])
    fill(img, (9, 8, 9, 12), shark.BELLY[2])
    fill(img, (3, 13, 6, 14), shark.OCEAN[2])
    fill(img, (9, 13, 12, 14), shark.OCEAN[2])
    return shark.icon_outline(img)


def textures() -> None:
    save(_icon(), "icon")
    save_layer(_worn(), "humanoid_leggings")


def models() -> dict:
    return {"main": sprite("icon")}
