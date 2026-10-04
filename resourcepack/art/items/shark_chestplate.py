"""Shark Chestplate: dark gray shark body armor with a pale underbelly, ocean-blue trim and
gill slits at the shoulders, plus a dorsal fin on the back.

Worn, the fin is drawn on the "wings" layer. Armor layers are flat textures wrapped on the
body model and cannot grow geometry, but any chest item whose equipment asset has a wings
layer renders the elytra model on the back. The fin is painted on those two wing panels as
a tall dark blade hugging the spine, so it stands out behind the player, tilts back with the
wing model and moves with the body. It starts at the shoulders, so it cannot rise above
them; that is the one thing this client-side system cannot do.
"""
from __future__ import annotations

from art import shark
from art.kit import HUMANOID, WINGS, canvas, fill, region, save, save_layer, sprite

ID = "shark_chestplate"
NAME = "Shark Chestplate"
KIND = "chestplate"
COUNTERPART = "item/netherite_chestplate"

S = 2  # worn textures are 128x64


def _humanoid():
    img = canvas(64 * S, 32 * S)
    for seed, name in enumerate(("body_top", "body_bottom", "body_right", "body_left", "arm_top", "arm_bottom",
                                 "arm_outer", "arm_front", "arm_inner", "arm_back")):
        shark.gray_surface(img, region(HUMANOID, name, S), seed=seed)
    front = region(HUMANOID, "body_front", S)
    back = region(HUMANOID, "body_back", S)
    shark.gray_surface(img, front, seed=3)
    shark.gray_surface(img, back, seed=4)
    # Underbelly: a pale oval down the front, widening toward the waist.
    x0, y0, x1, y1 = front
    for y in range(y0 + 4, y1 + 1):
        t = (y - y0 - 4) / max(1, y1 - y0 - 4)
        half = 3 + int(t * 5)
        cx = (x0 + x1) // 2
        fill(img, (cx - half, y, cx + half, y), shark.BELLY[min(4, 1 + int(t * 3))])
    # Cyan-edged ocean-blue hem, and a blue band across the shoulders on every side.
    for r in (front, back, region(HUMANOID, "body_right", S), region(HUMANOID, "body_left", S)):
        shark.trim(img, (r[0], r[3] - 3, r[2], r[3]), top=True, bottom=True)
    for name in ("arm_outer", "arm_front", "arm_inner", "arm_back"):
        r = region(HUMANOID, name, S)
        shark.trim(img, (r[0], r[1] + 14, r[2], r[1] + 17), top=True, bottom=True)
    # Spine stripe down the back.
    bx0, by0, bx1, by1 = back
    cx = (bx0 + bx1) // 2
    fill(img, (cx - 1, by0 + 2, cx, by1 - 5), shark.OCEAN[2])
    fill(img, (cx - 1, by0 + 2, cx - 1, by1 - 5), shark.CYAN[0])
    # Gill slits near the shoulders: front corners of the chest and the outside of each arm.
    shark.gills(img, x0 + 2, y0 + 4, 3, 6, 2)
    shark.gills(img, x1 - 9, y0 + 4, 3, 6, 2)
    ax0, ay0, _, _ = region(HUMANOID, "arm_outer", S)
    shark.gills(img, ax0 + 2, ay0 + 2, 3, 6, 2)
    return img


def _wing_fin_width(row: int) -> int:
    """Width in texels of the fin on a wing panel at this row: a point at the top, widening
    to a broad swept base, ending in a gently curved trailing edge."""
    if row > 34:
        return 0
    return 2 + round(10 * (row / 34) ** 0.9)


def _wings():
    img = canvas(64 * S, 32 * S)
    ox, oy, ow, oh = (v * S for v in WINGS["outer"])
    ix, iy, _, _ = (v * S for v in WINGS["inner"])
    for row in range(0, 36):
        w = _wing_fin_width(row)
        colour = shark.GRAY[4 - min(3, row // 10)]
        # Outer face: u grows toward the spine, so the fin hugs the high-u side.
        # Inner face is mirrored, so it hugs the low-u side.
        for k in range(w):
            c = shark.GRAY[3] if k else shark.CYAN[0]          # leading edge catches the light
            if k >= w - 2 and row > 6:
                c = shark.OCEAN[2]                              # blue shading along the trailing edge
            elif k:
                c = colour
            shark.put(img, ox + ow - 1 - k, oy + row, c)
            shark.put(img, ix + k, iy + row, shark.GRAY[2] if k else shark.CYAN[0])
    # The spine-side edges of both panels, so the fin has a thin dark edge from behind.
    for edge in ("edge_outer", "edge_inner"):
        ex, ey, ew, _ = (v * S for v in WINGS[edge])
        for row in range(0, 36):
            if _wing_fin_width(row):
                fill(img, (ex, ey + row, ex + ew - 1, ey + row), shark.GRAY[1])
    return img


def _icon():
    img = canvas(16)
    # Dorsal fin behind the neck, drawn first so the torso overlaps its base.
    for y, (x0, x1) in enumerate([(9, 9), (8, 9), (8, 10), (7, 10), (7, 11)]):
        fill(img, (x0, y, x1, y), shark.GRAY[1])
    fill(img, (9, 0, 9, 0), shark.CYAN[0])
    # Shoulders and torso.
    fill(img, (0, 4, 4, 9), shark.GRAY[3])
    fill(img, (11, 4, 15, 9), shark.GRAY[3])
    fill(img, (3, 4, 12, 14), shark.GRAY[3])
    fill(img, (5, 4, 10, 5), (0, 0, 0, 0))     # neck opening
    shark.shade_rows(img, (3, 6, 12, 14), [shark.GRAY[4], shark.GRAY[3], shark.GRAY[2], shark.GRAY[2], shark.GRAY[1]])
    # Underbelly and blue hem.
    fill(img, (6, 7, 9, 13), shark.BELLY[2])
    fill(img, (7, 8, 8, 12), shark.BELLY[3])
    fill(img, (3, 13, 12, 14), shark.OCEAN[2])
    fill(img, (3, 13, 12, 13), shark.CYAN[0])
    # Gill slits on the shoulders, cyan trim at the cuffs.
    for x in (1, 2, 13, 14):
        fill(img, (x, 5, x, 7), shark.GILL)
    fill(img, (0, 9, 4, 9), shark.OCEAN[2])
    fill(img, (11, 9, 15, 9), shark.OCEAN[2])
    return shark.icon_outline(img)


def textures() -> None:
    save(_icon(), "icon")
    save_layer(_humanoid(), "humanoid")
    save_layer(_wings(), "wings")


def models() -> dict:
    return {"main": sprite("icon")}
