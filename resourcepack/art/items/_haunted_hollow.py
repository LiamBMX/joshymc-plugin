"""Shared palette and painters for the Haunted Hollow armor set (chestplate, leggings, boots).

Dark charcoal plate, deep brown leather, a glowing pumpkin-orange jack-o'-lantern face,
forest-green vines, orange autumn leaves and a few golden highlights, so the three pieces
match each other and the Jack-o'-Lantern Mask. Not an item itself (the leading underscore
keeps it out of the build); the three haunted_hollow_*.py modules import it.
"""
from __future__ import annotations

import random

from art.kit import HUMANOID, bevel, canvas, fill, grain, mix, ramp, region, rgba, save, speckle

CHAR = ["#0f1013", "#191b20", "#252830", "#32363f", "#454a56", "#5d6370"]   # dark charcoal
BROWN = ramp("#5a3a22", 6, 0.8, 0.06)                                          # deep brown leather
ORANGE = ["#6b2204", "#a63a08", "#d95a0e", "#ff7a18", "#ffa238", "#ffd070"]   # pumpkin, dark to glow
GREEN = ramp("#2f6a2c", 6, 0.8, 0.05)                                          # forest-green vines
GOLD = ["#6b4a12", "#a6761c", "#d9a42e", "#f2c850", "#ffe68a", "#fff6c4"]      # golden highlights
LEAF = ["#7a2a05", "#b8480c", "#e0661a", "#f58a2a"]                            # autumn leaves
GLOW = "#ffd25a"                                                                # carved face light

S = 2  # worn layers are painted at 2x (128x64)


# --------------------------------------------------------------------------------------
# Item textures (16x16)
# --------------------------------------------------------------------------------------

def tex_plate() -> None:
    """Dark charcoal plating: riveted panels with a worn grain."""
    img = canvas(16, fill=CHAR[2])
    grain(img, (0, 0, 15, 15), [CHAR[1], CHAR[3]], axis="y", seed=7, density=0.6)
    fill(img, (0, 7, 15, 7), CHAR[0])
    fill(img, (0, 8, 15, 8), CHAR[4])
    fill(img, (7, 0, 7, 15), CHAR[0])
    fill(img, (8, 0, 8, 15), CHAR[3])
    for x, y in ((2, 2), (12, 2), (2, 12), (12, 12), (4, 10), (11, 5)):
        img.putpixel((x, y), rgba(CHAR[5]))
        img.putpixel((x + 1, y + 1), rgba(CHAR[0]))
    speckle(img, (0, 0, 15, 15), [ORANGE[1], ORANGE[0]], density=0.03, seed=3)
    save(img, "plate")


def tex_leather() -> None:
    """Deep brown leather straps with stitching."""
    img = canvas(16, fill=BROWN[2])
    grain(img, (0, 0, 15, 15), [BROWN[1], BROWN[3]], axis="x", seed=11, density=0.7)
    fill(img, (0, 0, 15, 0), BROWN[4])
    fill(img, (0, 15, 15, 15), BROWN[0])
    for x in range(1, 15, 3):
        img.putpixel((x, 3), rgba(BROWN[5]))
        img.putpixel((x, 12), rgba(BROWN[0]))
    save(img, "leather")


def tex_pumpkin() -> None:
    """Round pumpkin skin: bright ribs and dark furrows."""
    img = canvas(16, fill=ORANGE[3])
    for x in range(16):
        k = x % 4
        fill(img, (x, 0, x, 15), [ORANGE[2], ORANGE[3], ORANGE[4], ORANGE[2]][k])
    for x in range(3, 16, 4):
        fill(img, (x, 0, x, 15), ORANGE[1])
    fill(img, (0, 0, 15, 1), ORANGE[2])
    fill(img, (0, 14, 15, 15), ORANGE[1])
    speckle(img, (0, 0, 15, 15), [ORANGE[5], ORANGE[1]], density=0.05, seed=5)
    save(img, "pumpkin")


def tex_stem() -> None:
    img = canvas(16, fill=GREEN[2])
    grain(img, (0, 0, 15, 15), [GREEN[1], GREEN[3], BROWN[2]], axis="y", seed=2, density=0.7)
    save(img, "stem")


def tex_face() -> None:
    """The glowing carved jack-o'-lantern face on a dark rim."""
    img = canvas(16, fill=ORANGE[1])
    bevel(img, (0, 0, 15, 15), CHAR[4], CHAR[0], 1)
    fill(img, (1, 1, 14, 14), ORANGE[2])
    for x in range(1, 15, 4):
        fill(img, (x, 1, x, 14), ORANGE[3])
    # eyes: two triangles
    for ex in (3, 9):
        fill(img, (ex + 1, 3, ex + 2, 3), "#fff0a0")
        fill(img, (ex, 4, ex + 3, 6), GLOW)
        fill(img, (ex + 1, 4, ex + 2, 4), "#fff0a0")
    # nose
    fill(img, (7, 7, 8, 8), GLOW)
    # jagged grin
    fill(img, (3, 10, 12, 11), GLOW)
    for x in (4, 7, 10):
        fill(img, (x, 12, x + 1, 12), GLOW)
    for x in (5, 8, 11):
        img.putpixel((x, 10), rgba(ORANGE[2]))
    save(img, "face")


def tex_vine() -> None:
    img = canvas(16, fill=GREEN[2])
    grain(img, (0, 0, 15, 15), [GREEN[1], GREEN[3], GREEN[4]], axis="x", seed=9, density=0.8)
    for x in range(0, 16, 5):
        fill(img, (x, 0, x, 15), GREEN[0])
    save(img, "vine")


def tex_leaf() -> None:
    img = canvas(16, fill=LEAF[2])
    for y in range(16):
        fill(img, (0, y, 15, y), LEAF[1] if y % 5 == 0 else LEAF[2] if y % 2 else LEAF[3])
    fill(img, (7, 0, 8, 15), LEAF[0])
    save(img, "leaf")


def tex_gold() -> None:
    img = canvas(16, fill=GOLD[2])
    bevel(img, (0, 0, 15, 15), GOLD[5], GOLD[0], 1)
    fill(img, (4, 4, 11, 11), GOLD[3])
    save(img, "gold")


def paint_item_textures(*skip: str) -> None:
    """Paint every shared texture except the named ones (a model's unused textures)."""
    for paint in (tex_plate, tex_leather, tex_pumpkin, tex_stem, tex_face, tex_vine, tex_leaf, tex_gold):
        if paint.__name__[4:] not in skip:
            paint()


# --------------------------------------------------------------------------------------
# Worn layers
# --------------------------------------------------------------------------------------

class Worn:
    """A 128x64 worn texture with per-face painters."""

    def __init__(self) -> None:
        self.img = canvas(64 * S, 32 * S)
        self.px = self.img.load()

    def face(self, name: str, painter) -> None:
        """painter(x, y, w, h) -> colour or None, in face-local pixels."""
        x0, y0, x1, y1 = region(HUMANOID, name, S)
        w, h = x1 - x0 + 1, y1 - y0 + 1
        for y in range(h):
            for x in range(w):
                c = painter(x, y, w, h)
                if c is not None:
                    self.px[x0 + x, y0 + y] = rgba(c)


def plate_pixel(x: int, y: int, seed: int = 0) -> str:
    """Charcoal plate with riveted seams, shared by every worn piece."""
    rng = random.Random(x * 131 + y * 17 + seed)
    c = CHAR[2 + (1 if rng.random() < 0.25 else 0) - (1 if rng.random() < 0.2 else 0)]
    if y % 8 == 0:
        c = CHAR[0]
    elif y % 8 == 1:
        c = CHAR[4]
    if x % 8 == 3 and y % 8 == 4:
        c = CHAR[5]
    return c


def leather_pixel(x: int, y: int) -> str:
    if y % 4 == 0:
        return BROWN[0]
    if y % 4 == 1:
        return BROWN[4]
    return BROWN[2] if (x + y // 4) % 5 else BROWN[3]


def pumpkin_pixel(x: int, y: int, w: int) -> str:
    """Ribbed pumpkin, bright through the middle of each rib."""
    k = x % 4
    c = [ORANGE[2], ORANGE[3], ORANGE[4], ORANGE[2]][k]
    if k == 3:
        c = ORANGE[1]
    return c


def vine_pixel(x: int, y: int) -> str:
    return [GREEN[1], GREEN[2], GREEN[3], GREEN[2]][(x + y) % 4]


def leaf_at(w: Worn, name: str, cx: int, cy: int, seed: int = 0) -> None:
    """A small 3x2 autumn leaf with a stem pixel, drawn onto a face region."""
    x0, y0, x1, y1 = region(HUMANOID, name, S)
    rng = random.Random(seed + cx * 7 + cy)
    col = [LEAF[3], LEAF[2], LEAF[1]]
    for dx, dy in ((0, 0), (1, 0), (2, 0), (0, 1), (1, 1), (2, 1), (1, -1)):
        x, y = x0 + cx + dx, y0 + cy + dy
        if x0 <= x <= x1 and y0 <= y <= y1:
            w.px[x, y] = rgba(rng.choice(col))
    x, y = x0 + cx + 3, y0 + cy + 1
    if x0 <= x <= x1 and y0 <= y <= y1:
        w.px[x, y] = rgba(LEAF[0])


def carved_face(w: Worn, name: str, ox: int, oy: int, size: int = 12) -> None:
    """Paint the glowing jack-o'-lantern face onto a face region at (ox, oy), size x size pixels."""
    x0, y0, _, _ = region(HUMANOID, name, S)
    face = canvas(16)
    # reuse the item texture painter's layout by drawing the same shapes on a 16px grid
    fill(face, (0, 0, 15, 15), ORANGE[2])
    bevel(face, (0, 0, 15, 15), ORANGE[4], ORANGE[0], 1)
    for x in range(2, 14, 4):
        fill(face, (x, 1, x, 14), ORANGE[3])
    for ex in (3, 9):
        fill(face, (ex, 3, ex + 3, 3), GLOW)
        fill(face, (ex + 1, 4, ex + 2, 6), GLOW)
        fill(face, (ex + 1, 4, ex + 2, 4), "#fff0a0")
    fill(face, (7, 7, 8, 8), GLOW)
    fill(face, (3, 10, 12, 11), GLOW)
    for x in (4, 7, 10):
        fill(face, (x, 12, x + 1, 12), GLOW)
    face = face.resize((size, size))
    for y in range(size):
        for x in range(size):
            w.px[x0 + ox + x, y0 + oy + y] = face.getpixel((x, y))
    # soft glow halo on the plate around the face
    for y in range(-1, size + 1):
        for x in range(-1, size + 1):
            if 0 <= x < size and 0 <= y < size:
                continue
            px, py = x0 + ox + x, y0 + oy + y
            if w.px[px, py][3]:
                w.px[px, py] = rgba(mix("#%02x%02x%02x" % w.px[px, py][:3], ORANGE[1], 0.5))
