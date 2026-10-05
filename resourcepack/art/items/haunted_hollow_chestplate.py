"""Haunted Hollow Chestplate - October armor set, worn with the Jack-o'-Lantern Mask.

Dark charcoal plate with a glowing carved jack-o'-lantern face on the chest, pumpkin
pauldrons on both shoulders, green vines wrapping across the chest and shoulders, small
orange autumn leaves and a brown leather belt.
"""
from __future__ import annotations

from art.items._haunted_hollow import (CHAR, GREEN, ORANGE, S, Worn, carved_face, leaf_at, leather_pixel,
                                       paint_item_textures, plate_pixel, pumpkin_pixel, vine_pixel)
from art.kit import HUMANOID, bar, box, display, model, prism, region, rgba, save_layer, turn

ID = "haunted_hollow_chestplate"
NAME = "Haunted Hollow Chestplate"
KIND = "chestplate"


def vine_line(w: Worn, face: str, x0: int, y0: int, x1: int, y1: int) -> None:
    """A two-pixel-thick green vine from (x0, y0) to (x1, y1), face-local pixels."""
    rx, ry, _, _ = region(HUMANOID, face, S)
    steps = max(abs(x1 - x0), abs(y1 - y0))
    for i in range(steps + 1):
        x = x0 + round((x1 - x0) * i / steps)
        y = y0 + round((y1 - y0) * i / steps)
        w.px[rx + x, ry + y] = rgba(GREEN[3])
        w.px[rx + x, ry + y + 1] = rgba(GREEN[1])


def paint_worn() -> None:
    w = Worn()
    # torso: plate everywhere, brown leather belt at the bottom
    for face in ("body_right", "body_left", "body_front", "body_back", "body_top", "body_bottom"):
        w.face(face, lambda x, y, fw, fh: leather_pixel(x, y) if y >= fh - 4 else plate_pixel(x, y))
    # carved face on the chest with a vine sash behind it
    vine_line(w, "body_front", 0, 1, 15, 17)
    carved_face(w, "body_front", 2, 3, 12)
    leaf_at(w, "body_front", 12, 17, 6)

    # arms: pumpkin pauldron, vine ring, plate sleeve, leather cuff
    def arm(x, y, fw, fh):
        if y < 9:
            return ORANGE[1] if y == 8 else pumpkin_pixel(x, y, fw)
        if y in (9, 10):
            return vine_pixel(x, y)
        if y >= fh - 4:
            return leather_pixel(x, y)
        return plate_pixel(x, y, 5)

    for face in ("arm_outer", "arm_front", "arm_inner", "arm_back"):
        w.face(face, arm)
    w.face("arm_top", lambda x, y, fw, fh: pumpkin_pixel(x, y, fw))
    w.face("arm_bottom", lambda x, y, fw, fh: CHAR[1])

    # back: spine seam, a vine and leaves
    w.face("body_back", lambda x, y, fw, fh: CHAR[0] if x in (7, 8) and y < fh - 4 else None)
    vine_line(w, "body_back", 15, 1, 0, 16)
    leaf_at(w, "body_back", 10, 4, 1)
    leaf_at(w, "body_back", 3, 14, 2)
    leaf_at(w, "arm_outer", 2, 13, 3)
    leaf_at(w, "arm_front", 3, 12, 4)
    save_layer(w.img, "humanoid")


def textures() -> None:
    paint_item_textures()
    paint_worn()


def models() -> dict:
    p = []
    # torso plate and belt
    p.append(box((4, 2, 5.5), (12, 14, 10.5), "plate"))
    p.append(box((3.6, 2, 5.1), (12.4, 4.4, 10.9), "leather"))
    # glowing jack-o'-lantern face on the chest (north = front), rimmed in plate
    p.append(box((4.6, 5, 5.0), (11.4, 13, 5.5), "plate"))
    p.append(box((5.2, 5.6, 4.7), (10.8, 12.4, 5.1), "face", uv="full", glow=12))
    # shoulder pumpkins with stems and a vine collar
    for cx in (1.2, 14.8):
        p += prism((cx, 12.6, 8), 3.4, 5.0, "pumpkin", sides=8)
        p.append(box((cx - 0.6, 15.0, 7.4), (cx + 0.6, 16.6, 8.6), "stem"))
        p.append(box((cx - 2.0, 9.0, 6.0), (cx + 2.0, 10.4, 10.0), "vine"))
    # arm guards with leather cuffs
    p.append(box((-0.4, 4.0, 6.2), (3.2, 9.0, 9.8), "plate"))
    p.append(box((12.8, 4.0, 6.2), (16.4, 9.0, 9.8), "plate"))
    p.append(box((-0.6, 3.6, 6.0), (3.4, 5.0, 10.0), "leather"))
    p.append(box((12.6, 3.6, 6.0), (16.6, 5.0, 10.0), "leather"))
    # vine sash across the chest, a golden buckle and a few leaves
    p.append(bar((3.4, 14.0, 4.8), (12.6, 2.6, 4.8), 1.2, 0.6, "vine"))
    p.append(box((7.2, 2.4, 4.4), (8.8, 4.0, 5.0), "gold"))
    for x, y, a in ((5.0, 11.4, 30), (10.4, 4.6, -40), (4.0, 8.0, 70)):
        p.append(turn(box((x - 1, y, 4.5), (x + 1, y + 1.6, 4.9), "leaf", uv="full"), a, "z", (x, y, 5)))
    p = turn(p, 180, "y", (8, 8, 8))
    return {"main": model(p, display(KIND, p))}
