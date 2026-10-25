"""Viking Helmet: the signature piece of the Viking set (issue #1294), built on the Viking
Chestplate (the set's pilot module, whose palette, painters and boss it imports). No
beard: the face stays open under the guard.

A raider's war helm in weathered steel:
  * a rounded, slightly peaked dome with a raised comb running front to back over the
    crown, studded with bronze rivets;
  * a dark steel brow band riveted all the way round;
  * a spectacle guard over the face (the gjermundbu look): an arch over each eye joined
    by the nasal, which runs down between the eyes, with rims down the outside of the
    eyes and under them, so the eyes themselves stay clear; the set's boss with its
    polished silver crown sits where the nasal meets the brow;
  * riveted cheek flares hanging at the sides, edged in leather;
  * a neck guard of three overlapping steel lames at the back, trimmed with the set's
    brown bear fur;
  * two short ivory horns, banded with steel at their roots, sweeping out from the sides
    of the dome and curling up and a little forward. Kept short so they read clean on the
    head rather than cartoonish.

Frame (helmet): the wearer's head is the cube [1.6, 14.4]^3, face at -Z, up = +Y,
+X = the wearer's right. Inner surfaces stay at 0.6 / 15.4 to clear the hat layer.
Steve's eyes are the face's 5th pixel row (y 6.4 .. 8.0) at x 3.2 .. 6.4 and 9.6 .. 12.8.
"""
from __future__ import annotations

import math
import random

import numpy as np

from art.items.snowveil_guard import C, _hash, blank, grid, pick, to_image
from art.items.viking_chestplate import boss_3d, fur_tufts, save_set_materials, swatch
from art.items.snowveil_guard import fur_roll
from art.kit import bar, box, display, mirror, model, save, turn

ID = "viking_helmet"
NAME = "Viking Helmet"
KIND = "helmet"
COUNTERPART = "item/netherite_helmet"

HORN = ["#2e251c", "#4d3f30", "#6f5f49", "#937f63", "#b09c7c", "#cbb999", "#e1d4b8"]

X0, X1 = -0.2, 16.2           # outer shell
Z0, Z1 = -0.2, 16.2
IN0, IN1 = 0.6, 15.4          # inner surfaces
BROW = (9.2, 10.8)            # the dark steel brow band (y)
NECK_BOTTOM = 2.6             # the neck guard's lowest lame


def _horn_swatch(a):
    """Ivory horn: growth rings across it (faces run root to tip along v), lit along one
    side, darker toward the root, with fine streaks along its length."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    idx = 3 + np.where((Y % 5) == 0, -2, 0) + np.where((Y % 5) == 1, 1, 0)
    idx = idx + np.where(_hash(X // 1, Y // 6, 4) > 0.8, 1, 0) - np.where(_hash(X, Y // 4, 5) > 0.88, 1, 0)
    idx = idx + (Y > 32) * 1 + (Y > 48) * 1
    a[:] = pick(HORN, idx)
    a[:, 0:2] = pick(HORN, idx[:, 0:2] + 1)


def textures() -> None:
    save_set_materials(skip=("lamellar", "wool", "leather"))
    swatch("horn", _horn_swatch)


TIERS = [  # (y0, y1, inset, corner): a short skull over the band, then a tall, rounded,
    # slightly conical crown. Below y 15.3 every tier still wraps the hat layer (x / z
    # 0.8 .. 15.2).
    (BROW[1], 12.0, 0.0, 2.2),
    (12.0, 13.0, 0.1, 1.9),
    (13.0, 14.0, 0.3, 1.6),
    (14.0, 14.9, 0.6, 1.3),
    (14.9, 15.6, 0.95, 1.5),
    (15.6, 16.3, 1.7, 1.9),
    (16.3, 17.0, 2.6, 2.0),
    (17.0, 17.6, 3.6, 1.9),
    (17.6, 18.1, 4.7, 1.6),
    (18.1, 18.5, 5.8, 1.2),
    (18.5, 18.8, 6.7, 0.7),
]


def dome() -> list[dict]:
    """The steel dome: a skull from the brow band up, then tiers drawing in to a rounded
    peak, each tier's corners cut back in steps so it reads round from every side."""
    parts = []
    for i, (y0, y1, inset, c) in enumerate(TIERS):
        x0, x1, z0, z1 = X0 + inset, X1 - inset, Z0 + inset, Z1 - inset
        skip = ("down",) if i else ()
        k = c * 0.45
        v = 0 if i == 0 else 2 + i % 3   # only the skull catches the swatch's lit top row
        for (a, b), u in (((c, 0), i * 3), ((0, c), i * 7), ((k, k), i * 11)):
            parts.append(box((x0 + a, y0, z0 + b), (x1 - a, y1, z1 - b), "steel", skip=skip,
                             faces={"down": "lining"}, offset=(u % 12, v)))
    return parts


def comb() -> list[dict]:
    """The raised comb over the crown, front to back, stepping with the tiers, and a row
    of bronze rivets down each side of it on the skull."""
    parts = []
    w, p = 0.75, 0.45
    for y0, y1, inset, _ in TIERS:
        parts.append(box((8 - w, y0, Z0 + inset - p), (8 + w, y1 + p, Z1 - inset + p), "steel", skip=("down",),
                         offset=(2, 0)))
        parts.append(box((8 - 0.25, y0, Z0 + inset - p - 0.1), (8 + 0.25, y1 + p + 0.15, Z1 - inset + p + 0.1),
                         "silver", skip=("down",), uv="full"))
    # the cross band, side to side, in darker steel (the horns are fixed to it)
    wb = 0.7
    for y0, y1, inset, _ in TIERS:
        parts.append(box((X0 + inset - 0.25, y0, 8 - wb), (X1 - inset + 0.25, y1 + 0.25, 8 + wb), "dark_steel",
                         skip=("down",), offset=(5, 2)))
    for y, inset in ((12.5, 0.1), (13.5, 0.3), (14.4, 0.6), (15.2, 0.95)):
        for x in (X0 + inset - 0.45, X1 - inset + 0.25):
            parts.append(box((x, y - 0.2, 8 - 0.2), (x + 0.2, y + 0.2, 8 + 0.2), "bronze"))
    top = TIERS[-1][1]
    parts.append(box((8 - 0.35, top, 6.6), (8 + 0.35, top + 0.55, 9.4), "steel", offset=(4, 0)))
    parts.append(box((8 - 0.8, top, 8 - 0.8), (8 + 0.8, top + 0.4, 8 + 0.8), "dark_steel", offset=(2, 2)))
    for y, inset in ((11.4, 0.0), (12.4, 0.0), (13.3, 0.15), (14.3, 0.4)):
        for d in (-1.0, 1.0):
            for z in (Z0 + inset - 0.25, Z1 - inset):
                parts.append(box((8 + d - 0.2, y - 0.2, z), (8 + d + 0.2, y + 0.2, z + 0.25), "bronze"))
    return parts


def brow_band() -> list[dict]:
    """The dark steel brow band round the rim, a little proud of the skull, riveted all
    round; the open face under it shows the dark lining."""
    y0, y1 = BROW
    o = 0.15
    parts = [box((X0 - o, y0, Z0 - o), (X1 + o, y1, Z1 + o), "dark_steel")]
    n = 7
    for k in range(n):
        t = X0 + 1.3 + k * (X1 - X0 - 2.6) / (n - 1)
        if abs(t - 8) < 1.5:
            continue
        for z in (Z0 - o - 0.22, Z1 + o):
            parts.append(box((t - 0.22, 9.8, z), (t + 0.22, 10.25, z + 0.22), "bronze"))
        for x in (X0 - o - 0.22, X1 + o):
            parts.append(box((x, 9.8, t - 0.22), (x + 0.22, 10.25, t + 0.22), "bronze"))
    parts.append(box((IN0, y0 - 0.2, Z0), (IN1, y0, IN0), "lining", skip=("up",)))
    return parts


def spectacles() -> list[dict]:
    """The spectacle guard: an arch over each eye, rims down the outside of the eyes and
    under them, the nasal between them (tapering to its tip), all close to the face and
    clear of the eyes themselves, and the boss where the nasal meets the brow."""
    z0, z1 = -0.45, 0.45
    side = []
    # the arch over the right eye (+x side): rising a little toward the nose
    side.append(box((9.1, 8.2, z0), (13.6, 9.3, z1), "steel", offset=(0, 0)))
    side.append(box((9.4, 9.0, z0 - 0.1), (13.2, 9.5, z1), "steel", offset=(2, 0)))
    # the rim down the outside of the eye and the rim under it
    side.append(box((12.8, 5.6, z0 + 0.1), (13.8, 8.4, z1), "steel", offset=(1, 1)))
    side.append(box((9.1, 5.4, z0 + 0.25), (13.6, 6.3, z1), "steel", offset=(3, 2)))
    side.append(box((9.4, 5.6, z0 + 0.05), (13.3, 6.15, z0 + 0.3), "steel", offset=(3, 0)))
    # rivets on the arch and at the outer corner
    for x, y in ((10.3, 8.75), (12.4, 8.75), (13.3, 6.0)):
        side.append(box((x - 0.2, y - 0.2, z0 - 0.2), (x + 0.2, y + 0.2, z0 + 0.05), "bronze"))
    parts = side + mirror(side, "x", 8.0)
    # the nasal: from the brow straight down between the eyes, tapering, a lit ridge
    parts += [box((7.0, 5.0, z0 - 0.15), (9.0, BROW[0] + 0.2, z1), "steel", offset=(4, 0)),
              box((7.3, 4.2, z0 - 0.1), (8.7, 5.0, z1), "steel", offset=(4, 3)),
              box((7.6, 3.7, z0 - 0.05), (8.4, 4.2, z1), "steel", offset=(4, 5)),
              box((7.8, 4.4, z0 - 0.35), (8.2, 8.6, z0 - 0.15), "steel", offset=(0, 0))]
    parts += boss_3d(8.0, (BROW[0] + BROW[1]) / 2, Z0 - 0.15, 1.05)
    return parts


def cheek_flares() -> list[dict]:
    """Riveted steel cheek guards hanging from the band at the sides (in front of the
    ears, behind the eyes), flaring out at the foot, with a leather edge."""
    side = []
    xo = X1 + 0.05
    plate = [box((xo, 4.0, 2.6), (xo + 0.55, BROW[0] + 0.1, 8.6), "steel", offset=(1, 1)),
             box((xo - 0.05, 3.6, 3.0), (xo + 0.6, 4.1, 8.2), "strap"),
             box((xo - 0.05, 4.0, 2.2), (xo + 0.6, BROW[0], 2.65), "strap")]
    for y, z in ((8.1, 4.0), (8.1, 7.3), (5.2, 4.3), (5.2, 7.0)):
        plate.append(box((xo + 0.55, y - 0.2, z - 0.2), (xo + 0.78, y + 0.2, z + 0.2), "bronze"))
    turn(plate, 8, "z", (xo, BROW[0], 5.6))
    side += plate
    return side + mirror(side, "x", 8.0)


def neck_guard() -> list[dict]:
    """Three steel lames hanging at the back of the neck, each overlapping the one below
    and flaring out a little more, with a bronze-rivet row, and the bear fur round the
    foot of the guard and along the sides."""
    parts = []
    lames = ((7.0, BROW[0] + 0.1, 0.0, -6), (4.8, 7.4, 0.35, -12), (NECK_BOTTOM, 5.2, 0.75, -18))
    for i, (y0, y1, out, tilt) in enumerate(lames[::-1]):
        lame = [box((X0 + 0.3, y0, Z1 - 0.3 + out), (X1 - 0.3, y1, Z1 + 0.35 + out), "steel",
                    faces={"north": "lining"}, offset=(i * 3, 0))]
        for x in (2.0, 5.0, 8.0, 11.0, 14.0):
            lame.append(box((x - 0.2, y1 - 0.75, Z1 + 0.35 + out), (x + 0.2, y1 - 0.35, Z1 + 0.55 + out), "bronze"))
        turn(lame, tilt * 0.5, "x", (8.0, y1, Z1 + out))
        parts += lame
    # bear fur at the foot of the guard, across the back and round to the cheeks
    parts += fur_roll((X0 - 0.4, NECK_BOTTOM - 0.9, Z1 - 0.2), (X1 + 0.4, NECK_BOTTOM + 0.8, Z1 + 1.8), r=0.4, seed=3)
    for xa, xb in ((X0 - 0.7, X0 + 0.5), (X1 - 0.5, X1 + 0.7)):
        parts += fur_roll((xa, 4.6, 8.4), (xb, 6.8, Z1 + 0.6), r=0.35, seed=int(xa) + 7)
    tufts = fur_tufts(X0 - 0.2, X1 + 0.2, NECK_BOTTOM - 0.7, Z1 + 0.6, Z1 + 1.5, seed=9, size=0.55, step=1.1)
    rng = random.Random(5)
    for e in tufts:
        lo, hi = e["from"], e["to"]
        turn(e, rng.uniform(-10, 10), "z", ((lo[0] + hi[0]) / 2, hi[1], (lo[2] + hi[2]) / 2))
    return parts + tufts


def horn() -> list[dict]:
    """The right horn (+x): a tapering ivory curve from the side of the dome, out and then
    up, turned a little forward, with a steel collar and bronze rivets at its root."""
    parts = []
    cx, cy, r = X1 + 0.4, 12.9 + 3.9, 3.9
    a0, a1, n = -90.0, 40.0, 9
    pts = []
    for k in range(n + 1):
        t = math.radians(a0 + (a1 - a0) * k / n)
        pts.append((cx + r * math.cos(t) * 1.05, cy + r * math.sin(t), 8.0))
    widths = [2.8, 2.6, 2.35, 2.1, 1.85, 1.6, 1.35, 1.1, 0.85, 0.6]
    for k in range(n):
        p0, p1 = pts[k], pts[k + 1]
        mid = [(p0[i] + p1[i]) / 2 for i in range(3)]
        q0 = [mid[i] + (p0[i] - mid[i]) * 1.12 for i in range(3)]
        q1 = [mid[i] + (p1[i] - mid[i]) * 1.12 for i in range(3)]
        wd = (widths[k] + widths[k + 1]) / 2
        parts.append(bar(q0, q1, wd, wd, "horn", offset=(k * 2 % 12, max(0.0, 12.0 - k * 1.45))))
    tip = pts[-1]
    parts.append(bar(pts[-1], (tip[0] - 0.25, tip[1] + 0.7, tip[2]), 0.38, 0.38, "horn", offset=(3, 0)))
    # the root: a steel collar where the horn meets the dome, with two rivets
    root = [box((X1 - 0.3, 11.5, 6.6), (X1 + 1.0, 14.3, 9.4), "steel", offset=(0, 0)),
            box((X1 + 0.9, 11.75, 6.85), (X1 + 1.25, 14.05, 9.15), "dark_steel")]
    for y in (12.3, 13.5):
        root.append(box((X1 + 1.0, y - 0.2, 7.0), (X1 + 1.2, y + 0.2, 7.4), "bronze"))
        root.append(box((X1 + 1.0, y - 0.2, 8.6), (X1 + 1.2, y + 0.2, 9.0), "bronze"))
    parts = [box((X1 - 0.1, 11.6, 6.7), (X1 + 2.0, 14.2, 9.3), "horn", offset=(0, 13))] + parts
    turn(parts, -16, "y", (X1, 12.9, 8.0))
    return root + parts


def build() -> list[dict]:
    right_horn = horn()
    return (dome() + comb() + brow_band() + spectacles() + cheek_flares() + neck_guard() + right_horn
            + mirror(right_horn, "x", 8.0))


def models() -> dict:
    parts = build()
    return {"main": model(parts, display("helmet", parts, gui_rotation=(10, 155, 0)))}
