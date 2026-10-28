"""Snowman Helmet: the signature piece of the Snowman set (issue #1316), built on the Snowman
Chestplate (the set's pilot module, whose palette, painters and 3D parts it imports).

A snowman's head worn as a helm:
  * a big, lumpy snowball over the whole head, rounded on every edge so it reads as a
    ball, with a few clumps of snow stuck to it;
  * two glossy coal eyes and a smile of seven coal pebbles;
  * a long orange carrot nose pushing forward, ringed, tapering to a blunt tip that
    droops a little, and rosy cheeks either side of it;
  * a black felt top hat set at a jaunty tilt, a red ribbon band with a holly sprig
    tucked in it, and a dusting of snow on its crown and brim.

Frame (helmet): the wearer's head is the cube [1.6, 14.4]^3, face at -Z, up = +Y,
+X = the wearer's right. The snowball spans [-1.4, 17.4] with corners rounded by 4.8, so even
the hat layer's corners (0.6 / 15.4) stay inside it.
"""
from __future__ import annotations

import math
import random

import numpy as np

from art.items.snowman_chestplate import RED, coal_lump, holly_3d, save_set_materials, snowball, twig
from art.items.snowveil_guard import C, _hash, grid, pick
from art.items.viking_chestplate import smooth_noise, swatch
from art.kit import bar, box, display, lathe, model, turn

ID = "snowman_helmet"
NAME = "Snowman Helmet"
KIND = "helmet"
COUNTERPART = "item/netherite_helmet"

LO, HI, R = -1.4, 17.4, 4.8               # the snowball's bounds and corner radius
FRAME = ((LO, LO, LO), (HI, HI, HI))
EYE_Y = 9.6                               # the eyes' centre height
NOSE = (8.0, 7.4)                         # where the carrot leaves the face (x, y)
HAT_Y = 16.9                             # the hat brim's underside
FELT = ["#07070a", "#0e0e14", "#16161e", "#1f1f29", "#2a2a36", "#393946", "#4d4d5c"]
BLUSH = ["#e98a95", "#f3a3ab", "#f9bcc1"]


def _felt_swatch(a):
    """Black felt: a soft, matte nap (smooth noise), faint brushed streaks, a lit top
    row."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    n = smooth_noise(h, w, 9, seed=3) * 0.7 + smooth_noise(h, w, 3, seed=4) * 0.3
    idx = np.rint(2.6 + (n - 0.5) * 2.0 + (_hash(X, Y // 5, 2) > 0.9) * 0.8).astype(int)
    a[:] = pick(FELT, idx)
    a[0] = C(FELT[5])
    a[1] = C(FELT[4])


def _ribbon_swatch(a):
    """Red satin ribbon: a glossy sheen band across it, darker toward the edges."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    band = np.abs(Y - 20) < 5
    idx = 3 + band * 2 + (np.abs(Y - 20) < 2) * 1 - (Y > 44) * 1
    a[:] = pick(RED, idx)
    a[0] = C(RED[6])


def _blush_swatch(a):
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    d = np.sqrt((X - 31.5) ** 2 + (Y - 31.5) ** 2) / 32
    a[:] = pick(BLUSH, np.clip(np.rint(2 - d * 2.5), 0, 2).astype(int))


def textures() -> None:
    save_set_materials(skip=("tail", "cream", "knit", "knit_x", "lining"))
    swatch("felt", _felt_swatch)
    swatch("ribbon", _ribbon_swatch)
    swatch("blush", _blush_swatch)


def head() -> list[dict]:
    """The snowball: a rounded block of snow over the whole head, with clumps stuck to it
    (never on the face) so its outline isn't perfectly smooth."""
    p = snowball((LO, LO, LO), (HI, HI, HI), R, steps=3, frame=FRAME, rim="snow_rim")
    rng = random.Random(7)
    clumps = ((HI - 0.6, 4.6, 9.5, 1.4), (LO + 0.5, 11.8, 11.2, 1.2), (5.0, HI - 0.6, 12.6, 1.3),
              (12.6, 2.2, HI - 0.6, 1.5), (3.0, 6.0, HI - 0.5, 1.2), (HI - 0.4, 12.2, 13.4, 1.0),
              (LO + 0.6, 3.6, 5.2, 1.1))
    for x, y, z, s in clumps:
        s *= rng.uniform(0.9, 1.1)
        p += snowball((x - s, y - s, z - s), (x + s, y + s, z + s), s * 0.55, steps=1, frame=FRAME)
    # a little twig stuck in the back of the head, a pebble beside it
    p += twig((4.0, 7.0, HI - 0.8), (1.6, 9.6, HI + 2.2), 0.55, forks=((0.6, 40, 0.9),), seed=3)
    p += coal_lump(5.6, 6.1, HI - 0.1, 0.45, 0.35, facing="south")
    return p


def face() -> list[dict]:
    """Coal eyes, a coal smile, rosy cheeks."""
    p = []
    zf = LO
    for x in (4.7, 11.3):
        p += coal_lump(x, EYE_Y, zf + 0.15, 1.45, 1.0)
        # a glint on each eye
        p.append(box((x - 0.95, EYE_Y + 0.45, zf - 1.28), (x - 0.3, EYE_Y + 1.05, zf - 1.22), "snow",
                     uv="full", shade=False))
    # the smile: seven pebbles on a curve, the middle ones lowest
    for k in range(7):
        t = (k - 3) / 3
        x = 8.0 + t * 4.9
        y = 3.0 + 2.1 * t * t
        p += coal_lump(x, y, zf + 0.15, 0.75 - 0.08 * abs(t), 0.6)
    for x in (3.2, 12.8):
        p.append(box((x - 1.1, 5.9, zf - 0.08), (x + 1.1, 6.7, zf + 0.1), "blush", uv="full"))
        p.append(box((x - 0.7, 5.6, zf - 0.06), (x + 0.7, 7.0, zf + 0.1), "blush", uv="full"))
    return p


def nose() -> list[dict]:
    """The carrot: a ringed orange cone from the middle of the face, tapering smoothly to
    a blunt tip, drooping a little and turned a touch to the wearer's left."""
    p = []
    x0, y0 = NOSE
    n = 8
    length = 8.4

    def at(t):
        return (x0 - 0.35 * t * t, y0 - 1.4 * t * t, LO + 0.9 - length * t)

    for k in range(n):
        t0, t1 = k / n, (k + 1) / n
        a, b = at(t0), at(t1)
        b = [a[i] + (b[i] - a[i]) * 1.15 for i in range(3)]
        wd = 2.4 * (1 - t0) ** 1.1 + 0.4
        p.append(bar(a, b, wd, wd, "carrot", offset=(k * 2 % 8, k * 2)))
    # growth rings standing a little proud along it
    for t in (0.12, 0.3, 0.5):
        a = at(t)
        b = at(t + 0.035)
        wd = 2.4 * (1 - t) ** 1.1 + 0.4 + 0.22
        p.append(bar(a, b, wd, wd, "carrot", offset=(0, 1)))
    return p


def bow(cx: float, cy: float, z: float) -> list[dict]:
    """A red ribbon bow on the back of the hat band (facing +z at depth z): two loops, a
    knot and two tails with notched ends."""
    p = [box((cx - 0.6, cy - 0.65, z - 0.1), (cx + 0.6, cy + 0.65, z + 0.55), "ribbon", offset=(0, 4))]
    for sgn in (-1, 1):
        loop = [box((cx + sgn * 0.5, cy - 0.9, z), (cx + sgn * 2.6, cy + 0.9, z + 0.4), "ribbon", offset=(1, 4)),
                box((cx + sgn * 2.2, cy - 0.6, z - 0.1), (cx + sgn * 2.8, cy + 0.6, z + 0.45), "ribbon", offset=(2, 4))]
        turn(loop, sgn * 12, "z", (cx, cy, z))
        p += loop
        tail = [box((cx + sgn * 0.1, cy - 2.6, z + 0.05), (cx + sgn * 0.9, cy - 0.4, z + 0.35), "ribbon",
                    offset=(3, 2))]
        turn(tail, sgn * 18, "z", (cx, cy - 0.4, z))
        p += tail
    return p


def hat() -> list[dict]:
    """The black felt top hat: a wide round brim curled up at the sides, a tall crown
    flaring a little toward its top, a red ribbon band with a holly sprig at the front and
    a bow at the back, snow dusting the crown and brim; all tilted toward the wearer's
    left at a jaunty angle."""
    cx, cz = 8.0, 8.4
    p = lathe((cx, HAT_Y, cz), [(0.0, 8.5), (0.8, 8.5)], "felt", sides=16, cap="felt")
    # the brim's rolled edge, up at the sides
    for sgn in (-1, 1):
        roll = box((cx + sgn * 7.7 - 0.8, HAT_Y + 0.5, cz - 4.2), (cx + sgn * 7.7 + 0.8, HAT_Y + 1.2, cz + 4.2),
                   "felt", offset=(0, 0))
        turn(roll, sgn * 16, "z", (cx + sgn * 6.9, HAT_Y + 0.5, cz))
        p.append(roll)
    crown = [(0.6, 5.3), (2.0, 5.3), (3.6, 5.35), (5.2, 5.5), (6.8, 5.7), (7.8, 5.8)]
    p += lathe((cx, HAT_Y, cz), crown, "felt", sides=16, cap="felt")
    p += lathe((cx, HAT_Y, cz), [(0.8, 5.53), (2.8, 5.55)], "ribbon", sides=16)
    # snow on the crown and a little on the brim
    p += lathe((cx, HAT_Y + 7.8, cz), [(0.0, 5.45), (0.4, 5.0), (0.75, 3.6)], "snow", sides=16, cap="snow")
    for x, z, s in ((cx + 6.2, cz - 4.9, 1.1), (cx - 5.6, cz + 5.6, 1.0)):
        p += snowball((x - s, HAT_Y + 0.75, z - s * 0.7), (x + s, HAT_Y + 1.4, z + s * 0.7), 0.25, steps=1,
                      frame=((x - 2, HAT_Y, z - 2), (x + 2, HAT_Y + 2, z + 2)))
    # the holly sprig tucked in the band at the front, to the wearer's right, the bow behind
    p += holly_3d(cx + 2.6, HAT_Y + 2.0, cz - 5.45, 1.3)
    p += bow(cx - 1.0, HAT_Y + 2.6, cz + 5.45)
    return turn(p, -13, "z", (cx, HAT_Y, cz))


def build() -> list[dict]:
    return head() + face() + nose() + hat()


def models() -> dict:
    parts = build()
    return {"main": model(parts, display("helmet", parts, gui_rotation=(10, 164, 0), gui_span=15.5))}
