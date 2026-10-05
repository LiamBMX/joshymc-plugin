"""Haunted Hollow Boots - October armor set, worn with the Jack-o'-Lantern Mask.

Dark brown and charcoal boots with pumpkin-orange trim, green vines wrapping the ankles,
a small carved pumpkin on each toe cap and orange accents.

The item model is the pair standing side by side, toes toward -Z.
"""
from __future__ import annotations

from art.items._haunted_hollow import (BROWN, CHAR, ORANGE, S, Worn, leaf_at, leather_pixel,
                                       paint_item_textures, plate_pixel, vine_pixel)
from art.kit import bar, box, display, model, prism, save_layer, turn

ID = "haunted_hollow_boots"
NAME = "Haunted Hollow Boots"
KIND = "boots"


def paint_worn() -> None:
    w = Worn()

    # legs: the boot covers the lower 8 rows of the 24-row leg faces
    def leg(face):
        def paint(x, y, fw, fh):
            if y < fh - 12:
                return None
            if y == fh - 12:
                return ORANGE[3] if x % 2 else ORANGE[2]               # pumpkin-orange cuff trim
            if y == fh - 11:
                return ORANGE[1]
            if y in (fh - 10, fh - 9):
                return vine_pixel(x, y)                                  # ankle vine wrap
            if y >= fh - 3:
                return CHAR[1] if y == fh - 1 else CHAR[3]               # charcoal toe/heel band
            return leather_pixel(x, y) if (y // 3) % 2 else BROWN[2] if x % 3 else BROWN[1]
        return paint

    for face in ("leg_outer", "leg_front", "leg_inner", "leg_back"):
        w.face(face, leg(face))
    # small carved-pumpkin patch on the toe cap
    w.face("leg_front", lambda x, y, fw, fh: (
        ORANGE[3] if 1 <= x <= 6 and fh - 7 <= y <= fh - 4 else None))
    w.face("leg_front", lambda x, y, fw, fh: (
        "#ffd25a" if (x in (2, 5) and y == fh - 6) or (3 <= x <= 4 and y == fh - 4) else None))
    w.face("leg_sole", lambda x, y, fw, fh: CHAR[0] if (x + y) % 4 else CHAR[2])
    leaf_at(w, "leg_outer", 3, 10, 1)
    save_layer(w.img, "humanoid")


def textures() -> None:
    paint_item_textures("gold", "stem")
    paint_worn()


def boot(ox: float) -> list[dict]:
    p = []
    # shaft, foot and sole
    p.append(box((ox, 3.0, 6.0), (ox + 4.4, 9.0, 10.4), "leather"))
    p.append(box((ox - 0.2, 8.0, 5.8), (ox + 4.6, 9.6, 10.6), "plate"))             # collar
    p.append(box((ox - 0.2, 8.8, 5.6), (ox + 4.6, 9.4, 10.8), "pumpkin"))           # orange trim
    p.append(box((ox, 0.8, 2.4), (ox + 4.4, 4.0, 10.4), "plate"))                    # foot
    p.append(box((ox - 0.2, 0.0, 2.2), (ox + 4.6, 1.0, 10.6), "leather"))            # sole
    # carved pumpkin on the toe cap, glowing
    p.append(box((ox + 0.6, 1.6, 1.8), (ox + 3.8, 3.8, 2.5), "face", uv="full", glow=10))
    # ankle vine and leaf
    p.append(bar((ox - 0.1, 4.6, 6.0), (ox + 4.5, 6.4, 10.4), 0.9, 0.9, "vine"))
    p.append(bar((ox + 4.5, 4.4, 6.0), (ox - 0.1, 6.2, 10.4), 0.9, 0.9, "vine"))
    p.append(turn(box((ox + 4.4, 6.6, 7.0), (ox + 4.9, 8.2, 9.0), "leaf", uv="full"), 25, "x", (ox + 4.6, 7.4, 8)))
    return p


def models() -> dict:
    p = boot(2.0) + boot(9.6)
    p = turn(p, 180, "y", (8, 8, 8))
    return {"main": model(p, display(KIND, p))}
