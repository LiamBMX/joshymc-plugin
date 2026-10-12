"""Nordic Helm: the helmet of the Nordic set (November crate), built on the Nordic Plate
(the set's pilot module, whose palette, painters and boss it imports).

A Viking spangenhelm without horns: a low, slightly peaked dome of blackened, hammered
iron plates held by four bronze bands (front to back and side to side) studded with
rivets, a finial knob at the crown, and a bronze brow band carrying the set's knotwork
all the way round. Over the forehead, where the nasal meets the brow band, sits the
Nordic boss with its glowing amber stone. An iron nasal runs down between the eyes
(never across them), and a short mail aventail hangs from the rim at the sides and back
to the neck, finished with a bronze-ringed edge and the set's wolf fur.

Kept compact for a worn helmet: the dome stands about 2 units over the hat layer, the rim
and aventail sit 1 unit off the head, and the face stays open.

Frame (helmet): the wearer's head is the cube [1.6, 14.4]^3, face at -Z, up = +Y,
+X = the wearer's right. Inner surfaces stay at 0.6 / 15.4 to clear the hat layer.
Steve's eyes are the face's 5th pixel row (y 6.4 .. 8.0) at x 3.2 .. 6.4 and 9.6 .. 12.8.
"""
from __future__ import annotations

import random

from art.items.nordic_plate import boss_3d, fur_tufts, save_set_materials
from art.items.snowveil_guard import fur_roll
from art.kit import box, display, mirror, model

ID = "nordic_helm"
NAME = "Nordic Helm"
KIND = "helmet"
COUNTERPART = "item/netherite_helmet"

X0, X1 = -0.2, 16.2           # outer shell
Z0, Z1 = -0.2, 16.2
IN0, IN1 = 0.6, 15.4          # inner surfaces
RIM = (9.6, 11.6)             # the bronze brow band (y)
NASAL = (7.0, 9.0, 6.1)       # nasal x range and its lower tip (y)
AV_SIDE, AV_BACK = 1.4, 0.8   # bottoms of the aventail's sides and back


def textures() -> None:
    save_set_materials(skip=("leather", "weave"))


TIERS = [  # (y0, y1, inset, corner): the skull drawing in from the band, then a low peak.
    # Below y 15.3 every tier still wraps the hat layer (x / z 0.8 .. 15.2), corners included.
    (RIM[1], 12.4, 0.0, 2.2),
    (12.4, 13.4, 0.2, 1.7),
    (13.4, 14.4, 0.45, 1.2),
    (14.4, 15.3, 0.7, 0.6),
    (15.3, 15.8, 1.5, 2.4),
    (15.8, 16.3, 2.7, 2.2),
    (16.3, 16.8, 4.0, 1.8),
    (16.8, 17.2, 5.3, 1.2),
    (17.2, 17.5, 6.4, 0.6),
]


def dome() -> list[dict]:
    """The iron dome: a skull from the brow band up, then tiers drawing in to a low,
    rounded peak, each tier's corners cut back in two steps so it reads round from
    above and from the side."""
    parts = []
    for i, (y0, y1, inset, c) in enumerate(TIERS):
        x0, x1, z0, z1 = X0 + inset, X1 - inset, Z0 + inset, Z1 - inset
        skip = ("down",) if i else ()
        k = c * 0.45
        v = 0 if i == 0 else 2 + i % 3   # only the skull catches the swatch's lit top row
        for (a, b), u in (((c, 0), i * 3), ((0, c), i * 7), ((k, k), i * 11)):
            parts.append(box((x0 + a, y0, z0 + b), (x1 - a, y1, z1 - b), "iron", skip=skip,
                             faces={"down": "lining"}, offset=(u % 12, v)))
    # the finial: a small bronze knob on the peak
    top = TIERS[-1][1]
    parts.append(box((7.3, top, 7.3), (8.7, top + 0.35, 8.7), "bronze"))
    parts.append(box((7.6, top + 0.35, 7.6), (8.4, top + 0.75, 8.4), "bronze"))
    parts.append(box((7.85, top + 0.75, 7.85), (8.15, top + 1.0, 8.15), "bronze"))
    return parts


def bands() -> list[dict]:
    """The four bronze bands over the dome (front-back and side to side), stepping with
    its tiers, with rivets down each side of them."""
    parts = []
    w, p = 0.75, 0.15
    for y0, y1, inset, _ in TIERS:
        parts.append(box((8 - w, y0, Z0 + inset - p), (8 + w, y1 + p * 0.5, Z1 - inset + p), "bronze",
                         skip=("down",)))
        parts.append(box((X0 + inset - p, y0, 8 - w), (X1 - inset + p, y1 + p * 0.5, 8 + w), "bronze",
                         skip=("down",)))
    # rivets along the bands on the skull (front, back and sides)
    for y, inset in ((12.0, 0.0), (12.9, 0.2), (13.9, 0.45)):
        a, b = X0 + inset, X1 - inset
        for d in (-1.25, 1.25):
            parts.append(box((8 + d - 0.22, y - 0.22, a - 0.3), (8 + d + 0.22, y + 0.22, a), "iron", offset=(1, 0)))
            parts.append(box((8 + d - 0.22, y - 0.22, b), (8 + d + 0.22, y + 0.22, b + 0.3), "iron", offset=(1, 0)))
            parts.append(box((a - 0.3, y - 0.22, 8 + d - 0.22), (a, y + 0.22, 8 + d + 0.22), "iron", offset=(1, 0)))
            parts.append(box((b, y - 0.22, 8 + d - 0.22), (b + 0.3, y + 0.22, 8 + d + 0.22), "iron", offset=(1, 0)))
    return parts


def brow_band() -> list[dict]:
    """The bronze brow band round the rim, carrying the set's knotwork, a little proud of
    the skull; its lower edge is a plain bronze lip."""
    y0, y1 = RIM
    o = 0.1
    kn = {"north": ("knot", [0, 0, 16, 2]), "south": ("knot", [0, 0, 16, 2]),
          "east": ("knot", [0, 0, 16, 2]), "west": ("knot", [0, 0, 16, 2]),
          "up": "bronze", "down": "bronze"}
    parts = [box((X0 - o, y0 + 0.3, Z0 - o), (X1 + o, y1, Z1 + o), "knot", faces=kn),
             box((X0 - o + 0.05, y0, Z0 - o + 0.05), (X1 + o - 0.05, y0 + 0.4, Z1 + o - 0.05), "bronze")]
    # the open face under the band shows the dark lining
    parts.append(box((IN0, y0 - 0.2, Z0), (IN1, y0, IN0), "lining", skip=("up",)))
    return parts


def nasal() -> list[dict]:
    """The iron nasal: from inside the brow band straight down between the eyes, close to
    the face, tapering at its tip, with bronze edges and a rivet."""
    xa, xb, tip = NASAL
    z0, z1 = 0.0, 0.6
    parts = [box((xa, tip + 0.9, z0), (xb, RIM[1] - 0.2, z1), "iron", offset=(4, 0)),
             box((xa + 0.3, tip + 0.3, z0 + 0.05), (xb - 0.3, tip + 0.9, z1), "iron", offset=(4, 0)),
             box((xa + 0.6, tip, z0 + 0.1), (xb - 0.6, tip + 0.3, z1), "iron", offset=(4, 0)),
             box((xa - 0.1, tip + 1.0, z0 + 0.05), (xa + 0.1, RIM[0], z1), "bronze"),
             box((xb - 0.1, tip + 1.0, z0 + 0.05), (xb + 0.1, RIM[0], z1), "bronze"),
             box((7.75, tip + 1.5, z0 - 0.2), (8.25, tip + 2.0, z0), "iron", offset=(1, 0))]
    return parts


def aventail() -> list[dict]:
    """The mail aventail hanging from the rim behind the cheeks and round the back, in two
    courses (the lower one flaring out a little), with a bronze-ringed edge and wolf fur
    round its foot."""
    parts = []
    y_top, y_mid = RIM[0] + 0.2, 5.4
    zf = Z0 + 4.2
    side = [box((X0 + 0.1, y_mid, zf), (IN0, y_top, Z1 - 0.6), "mail", faces={"east": "lining"},
                offset=(0, 2)),
            box((X0 - 0.25, AV_SIDE, zf + 0.3), (IN0, y_mid, Z1 - 0.3), "mail", faces={"east": "lining"},
                offset=(2, 5)),
            box((X0 - 0.3, AV_SIDE, zf + 0.3), (X0 - 0.25, AV_SIDE + 0.35, Z1 - 0.3), "bronze", skip=("east",)),
            box((X0 - 0.05, y_mid, zf), (X0 + 0.1, y_mid + 0.3, Z1 - 0.6), "bronze", skip=("east",))]
    parts += side + mirror(side, "x", 8.0)
    parts.append(box((X0 + 0.1, y_mid, IN1), (X1 - 0.1, y_top, Z1 - 0.1), "mail", faces={"north": "lining"},
                     offset=(1, 3)))
    parts.append(box((X0 - 0.25, AV_BACK, IN1), (X1 + 0.25, y_mid, Z1 + 0.25), "mail",
                     faces={"north": "lining"}, offset=(3, 7)))
    parts.append(box((X0 - 0.25, AV_BACK, Z1 + 0.25), (X1 + 0.25, AV_BACK + 0.35, Z1 + 0.3), "bronze",
                     skip=("north",)))
    parts.append(box((X0 + 0.1, y_mid, Z1 - 0.1), (X1 - 0.1, y_mid + 0.3, Z1 + 0.05), "bronze", skip=("north",)))
    # the fur round the foot of the aventail: along the back and down each side
    parts += fur_roll((X0 - 0.6, AV_BACK - 0.6, IN1 - 0.2), (X1 + 0.6, AV_BACK + 0.9, Z1 + 0.7), r=0.35, seed=3)
    for xa, xb in ((X0 - 0.6, X0 + 0.6), (X1 - 0.6, X1 + 0.6)):
        parts += fur_roll((xa, AV_SIDE - 0.6, zf + 0.6), (xb, AV_SIDE + 0.8, Z1 - 0.2), r=0.3, seed=int(xa) + 7)
    tufts = fur_tufts(X0 - 0.3, X1 + 0.3, AV_BACK + 0.8, Z1 + 0.15, Z1 + 0.6, seed=9, size=0.45, down=False,
                      step=1.3)
    rng = random.Random(5)
    for e in tufts:
        e["to"][1] = round(e["to"][1] - rng.choice((0.0, 0.1, 0.2)), 4)
    return parts + tufts


def build() -> list[dict]:
    return (dome() + bands() + brow_band() + nasal() + aventail()
            + boss_3d(8.0, (RIM[0] + RIM[1]) / 2, Z0 - 0.1, 1.0))


def models() -> dict:
    parts = build()
    return {"main": model(parts, display("helmet", parts, gui_rotation=(18, 155, 0)))}
