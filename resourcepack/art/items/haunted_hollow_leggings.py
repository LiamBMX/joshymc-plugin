"""Haunted Hollow Leggings - October armor set, worn with the Jack-o'-Lantern Mask.

Charcoal and brown plate greaves with small pumpkins on the knees, green vines winding up
the legs, orange autumn leaves, and a leather belt with a golden buckle.
"""
from __future__ import annotations

from art.items._haunted_hollow import (BROWN, CHAR, GOLD, GREEN, ORANGE, S, Worn, leaf_at, leather_pixel,
                                       paint_item_textures, plate_pixel, pumpkin_pixel, vine_pixel)
from art.kit import bar, box, display, model, prism, save_layer, turn

ID = "haunted_hollow_leggings"
NAME = "Haunted Hollow Leggings"
KIND = "leggings"


def paint_worn() -> None:
    w = Worn()
    # belt: the lower third of the body, brown leather with a golden buckle on the front
    for face in ("body_right", "body_left", "body_front", "body_back"):
        w.face(face, lambda x, y, fw, fh: leather_pixel(x, y) if y >= fh - 8 else None)
    w.face("body_front", lambda x, y, fw, fh: (GOLD[3] if 6 <= x <= 9 and fh - 7 <= y <= fh - 3 else None))
    w.face("body_front", lambda x, y, fw, fh: (GOLD[5] if 6 <= x <= 9 and y == fh - 7 else
                                               GOLD[1] if 6 <= x <= 9 and y == fh - 3 else None))

    # legs: plate with a pumpkin knee guard, vine band below it and a brown shin strap
    def leg(face):
        def paint(x, y, fw, fh):
            if y < 4:
                return leather_pixel(x, y) if y < 3 else CHAR[0]
            if 10 <= y <= 16 and (face != "leg_back"):
                if y in (10, 16) or x in (0, fw - 1):
                    return ORANGE[0]
                return pumpkin_pixel(x, y, fw)
            if y in (17, 18):
                return vine_pixel(x, y)
            if y >= fh - 4:
                return leather_pixel(x, y)
            return plate_pixel(x, y, 9)
        return paint

    for face in ("leg_outer", "leg_front", "leg_inner", "leg_back"):
        w.face(face, leg(face))
    w.face("leg_top", lambda x, y, fw, fh: CHAR[1])
    w.face("leg_sole", lambda x, y, fw, fh: CHAR[1])
    leaf_at(w, "leg_front", 2, 4, 1)
    leaf_at(w, "leg_outer", 2, 19, 2)
    leaf_at(w, "leg_back", 2, 8, 3)
    save_layer(w.img, "humanoid_leggings")


def textures() -> None:
    paint_item_textures("face")
    paint_worn()


def models() -> dict:
    p = []
    # belt and buckle
    p.append(box((3.8, 11.0, 5.6), (12.2, 14.0, 10.4), "leather"))
    p.append(box((7.2, 11.4, 5.2), (8.8, 13.6, 5.6), "gold"))
    # two legs: charcoal plate shafts, brown straps, pumpkin knee guards, vine wraps
    for x0 in (3.8, 8.4):
        x1 = x0 + 3.8
        cx = (x0 + x1) / 2
        p.append(box((x0, 1.0, 6.0), (x1, 11.2, 10.0), "plate"))
        p.append(box((x0 - 0.2, 0.6, 5.8), (x1 + 0.2, 2.0, 10.2), "leather"))
        p += prism((cx, 5.4, 5.4), 1.9, 1.4, "pumpkin", axis="z", sides=8)
        p.append(box((cx - 0.4, 6.8, 4.6), (cx + 0.4, 7.8, 5.4), "stem"))
        p.append(bar((x0 - 0.1, 3.0, 5.8), (x1 + 0.1, 9.4, 10.2), 0.9, 0.9, "vine"))
        p.append(turn(box((cx - 1, 9.0, 5.7), (cx + 1, 10.6, 6.0), "leaf", uv="full"), 35, "z", (cx, 9.6, 5.8)))
    p.append(box((7.7, 2.0, 6.2), (8.3, 11.0, 9.8), "plate"))
    p = turn(p, 180, "y", (8, 8, 8))
    return {"main": model(p, display(KIND, p))}
