"""Krampus Helmet: the signature piece of the Krampus set (issue #1306), built on the Krampus
Chestplate (the set's pilot module, whose palette, painters and 3D parts it imports). No
beard: the face is the demon's.

A Krampus head worn as a helm, like the carved masks of the Alpine Krampus runs:
  * a blackened iron demon mask over the whole face: a heavy, scowling brow ridge in a V,
    slanted ember eyes glowing in deep sockets, sharp cheekbones, a long goat muzzle
    pushing forward with flared nostrils, an open maw with bone fangs top and bottom, and
    the long red tongue lolling out of it;
  * a dirty-brass band across the forehead, riveted, where the horns are bolted on;
  * two huge ridged goat horns rising from the forehead, sweeping up, back and out, their
    tips hooking down behind the head;
  * pointed goat ears jutting from the sides, black fur outside and crimson inside;
  * a shaggy black goat-fur hood over the crown, sides and back of the head, its long
    locks hanging over the nape.

Frame (helmet): the wearer's head is the cube [1.6, 14.4]^3, face at -Z, up = +Y,
+X = the wearer's right. Inner surfaces stay at 0.6 / 15.4 to clear the hat layer.
Steve's eyes are the face's 5th pixel row (y 6.4 .. 8.0) at x 3.2 .. 6.4 and 9.6 .. 12.8.
"""
from __future__ import annotations

import math
import random

from art.items.krampus_chestplate import fur_tufts, save_set_materials, tilt_tufts
from art.items.snowveil_guard import fur_roll
from art.kit import bar, box, display, mirror, model, turn

ID = "krampus_helmet"
NAME = "Krampus Helmet"
KIND = "helmet"
COUNTERPART = "item/netherite_helmet"

X0, X1 = -0.4, 16.4           # outer shell
Z0, Z1 = -0.4, 16.4
IN0, IN1 = 0.6, 15.4          # inner surfaces
BAND = (12.6, 14.2)           # the brass forehead band (y)
BROW_Y = 9.6                  # the brow ridge's centre line (y), over the eyes


def textures() -> None:
    save_set_materials(skip=("strap", "leather"))


def hood() -> list[dict]:
    """The shaggy fur hood over the crown, the sides and the back, rounded off in tiers,
    with long locks hanging over the nape and the sides and tufts on top."""
    p = []
    # crown: rounded tiers
    tiers = ((13.6, 14.8, 0.2, 3.0), (14.8, 15.7, 1.1, 2.8), (15.7, 16.4, 2.6, 2.2))
    for i, (y0, y1, inset, c) in enumerate(tiers):
        x0, x1, z0, z1 = X0 + inset, X1 - inset, Z0 + 1.2 + inset, Z1 + 0.4 - inset
        k = c * 0.45
        for (a, b), u in (((c, 0), i * 3), ((0, c), i * 7), ((k, k), i * 11)):
            p.append(box((x0 + a, y0, z0 + b), (x1 - a, y1, z1 - b), "fur", offset=(u % 12, (i * 5) % 12),
                         faces={"down": "lining"}))
    # sides (behind the mask's cheek plates) and back, down to the nape
    # the side and back pelts hang from the crown and flare out toward their foot
    left = box((X0 - 0.3, 3.2, 5.6), (IN0, 14.2, Z1 + 0.3), "fur", offset=(2, 3), faces={"east": "lining"})
    right = box((IN1, 3.2, 5.6), (X1 + 0.3, 14.2, Z1 + 0.3), "fur", offset=(5, 1), faces={"west": "lining"})
    back_pelt = box((X0 + 0.4, 1.2, IN1), (X1 - 0.4, 14.2, Z1 + 0.3), "fur", offset=(7, 4), faces={"north": "lining"})
    turn(left, -7, "z", (IN0, 14.2, 8.0))
    turn(right, 7, "z", (IN1, 14.2, 8.0))
    turn(back_pelt, 7, "x", (8.0, 14.2, IN1))
    p += [left, right, back_pelt]
    # the mane: it swells out over the nape and tapers toward the crown, with a shaggy
    # crest running from the crown down the back
    p.append(box((X0 + 0.9, 0.4, Z1), (X1 - 0.9, 7.6, Z1 + 1.1), "fur", offset=(3, 6)))
    p.append(box((X0 + 2.2, -0.6, Z1 + 0.6), (X1 - 2.2, 5.2, Z1 + 1.9), "fur", offset=(9, 2)))
    crest = []
    for k, (y0, y1, zo) in enumerate(((15.4, 17.3, 3.6), (14.0, 16.9, 8.0), (11.0, 16.0, 12.6), (6.5, 13.5, Z1 + 0.1))):
        e = box((7.0 - 0.15 * k, y0, zo - 1.8), (9.0 + 0.15 * k, y1, zo + 1.8), "fur", offset=(k * 3, k * 2))
        turn(e, -8 - 10 * k, "x", (8.0, y0, zo))
        crest.append(e)
    p += crest
    # rolls that soften the corners
    for xa, xb, sgn, seed in ((X0 - 0.9, X0 + 0.6, -1, 3), (X1 - 0.6, X1 + 0.9, 1, 4)):
        roll = fur_roll((xa, 2.2, Z1 - 1.4), (xb, 12.6, Z1 + 1.1), r=0.4, seed=seed)
        turn(roll, sgn * 7, "z", ((xa + xb) / 2, 12.6, Z1))
        turn(roll, 6, "x", ((xa + xb) / 2, 12.6, Z1))
        p += roll
    # long locks hanging at the nape and down the sides
    back = fur_tufts(X0 - 0.2, X1 + 0.2, 1.8, Z1 + 0.1, Z1 + 1.0, seed=5, size=1.4, step=1.1)
    tilt_tufts(back, 6, True, 12)
    side = []
    for xa, xb, seed in ((X0 - 0.8, X0 + 0.1, 7), (X1 - 0.1, X1 + 0.8, 8)):
        t = fur_tufts(5.8, Z1 + 0.2, 3.6, 0, 1, seed=seed, size=1.7, step=1.0)
        t += fur_tufts(6.4, Z1 - 0.4, 8.4, 0, 1, seed=seed + 20, size=1.4, step=1.2)
        for e in t:
            (a0, y0, _), (a1, y1, _) = e["from"], e["to"]
            e["from"], e["to"] = [xa, y0, a0], [xb, y1, a1]
        side += t
    tilt_tufts(side, 9, True, 10)
    # overlapping shingles of locks down the back so it reads shaggy, not boxy
    for y, seed, inset, zo in ((12.4, 21, 1.6, 0.4), (9.0, 22, 0.9, 0.7), (5.6, 23, 0.5, 1.2)):
        row = fur_tufts(X0 + inset, X1 - inset, y, Z1 + zo, Z1 + zo + 0.8, seed=seed, size=1.6, step=1.2)
        back += tilt_tufts(row, seed, True, 9)
    # shaggy tufts standing on the crown
    rng = random.Random(10)
    tops = []
    for k in range(9):
        x = 3.0 + rng.uniform(0, 10.0)
        z = 5.5 + rng.uniform(0, 9.0)
        hgt = rng.uniform(0.6, 1.3)
        e = box((x - 0.55, 15.8, z - 0.55), (x + 0.55, 15.8 + hgt, z + 0.55), "fur",
                offset=(rng.randrange(12), rng.randrange(12)))
        turn(e, rng.uniform(-25, 25), "x", (x, 15.8, z))
        turn(e, rng.uniform(-20, 20), "z", (x, 15.8, z))
        tops.append(e)
    return p + back + side + tops


def mask() -> list[dict]:
    """The iron demon mask over the face, from the band down to the jaw."""
    p = []
    zf = Z0                   # the mask's front plane
    # forehead plate under the band, and the plate behind the eyes
    p.append(box((X0, 10.2, zf), (X1, BAND[0] + 0.1, IN0), "iron", offset=(0, 0)))
    # deep eye sockets: a dark recess the eyes glow out of
    p.append(box((X0 + 0.4, 6.0, zf + 0.5), (X1 - 0.4, 10.4, IN0), "lining"))
    # cheeks: plates down the sides of the face, with a sharp cheekbone ridge
    for x0, x1 in ((X0, 3.0), (13.0, X1)):
        p.append(box((x0, 2.4, zf), (x1, 6.4, IN0), "iron", offset=(2, 2)))
        p.append(box((x0, 6.4, zf), (x1 + (0.3 if x0 < 8 else -0.3), 10.2, IN0), "iron", offset=(3, 1)))
    # side plates round to the hood
    for x0, x1 in ((X0, IN0), (IN1, X1)):
        p.append(box((x0, 2.4, zf), (x1, BAND[0] + 0.1, 5.8), "iron", offset=(1, 1)))
    # the plate between the eyes (the muzzle's root)
    p.append(box((6.4, 6.0, zf - 0.2), (9.6, 10.4, IN0), "iron", offset=(4, 0)))
    # under the eyes, above the maw
    p.append(box((3.0, 4.6, zf - 0.1), (13.0, 6.2, IN0), "iron", offset=(1, 3)))
    side = []
    # cheekbone ridges: sharp, angled up toward the temples
    e = box((11.2, 5.6, zf - 0.7), (15.6, 6.4, zf + 0.2), "iron", offset=(0, 0))
    turn(e, 16, "z", (11.2, 6.0, zf))
    side.append(e)
    # the brow ridge: a heavy dark V, lowest at the nose, overhanging the sockets
    e = box((8.0, BROW_Y - 0.6, zf - 1.5), (16.0, BROW_Y + 0.9, zf + 0.4), "dark_iron", offset=(0, 0))
    turn(e, 14, "z", (8.0, BROW_Y, zf))
    side.append(e)
    e = box((8.6, BROW_Y + 0.6, zf - 1.0), (15.4, BROW_Y + 1.3, zf + 0.3), "iron", offset=(0, 0))
    turn(e, 14, "z", (8.0, BROW_Y, zf))
    side.append(e)
    # jagged spikes along the brow's outer end
    for k, (x, ln) in enumerate(((13.6, 1.1), (15.2, 1.5))):
        e = bar((x, BROW_Y + 1.6, zf - 0.6), (x + 0.6, BROW_Y + 1.6 + ln, zf - 0.9), 0.55, 0.55, "dark_iron",
                offset=(k, 0))
        side.append(e)
    # the ember eye: a slanted slit, glowing, under the brow
    e = box((10.0, 7.4, zf + 0.25), (13.4, 8.4, zf + 0.5), "ember", glow=13, uv="full", shade=False)
    turn(e, 14, "z", (10.0, 7.9, zf))
    side.append(e)
    e = box((10.6, 7.6, zf + 0.15), (11.6, 8.3, zf + 0.3), "ember", glow=15, uv="full", shade=False)
    turn(e, 14, "z", (10.0, 7.9, zf))
    side.append(e)
    # a sharp cheek fin sweeping back along the side of the head
    e = box((X1 - 0.1, 5.2, 0.6), (X1 + 0.5, 9.2, 5.6), "iron", offset=(0, 1))
    turn(e, -8, "y", (X1, 7.0, 0.6))
    side.append(e)
    for z in (1.8, 3.6):
        side.append(box((X1 + 0.45, 7.0, z - 0.22), (X1 + 0.7, 7.45, z + 0.22), "brass"))
    # rivets on the cheek
    for x, y in ((14.6, 3.4), (14.6, 4.9)):
        side.append(box((x - 0.22, y - 0.22, zf - 0.22), (x + 0.22, y + 0.22, zf), "brass"))
    p += side + mirror(side, "x", 8.0)
    return p


def muzzle() -> list[dict]:
    """The goat muzzle pushing forward from between the eyes: a ridged bridge, a broad
    snout with flared nostrils, the open maw with bone fangs and the lolling tongue."""
    p = []
    zf = Z0
    # the bridge, from the brow down and forward
    p.append(bar((8.0, 9.6, zf - 0.6), (8.0, 6.0, zf - 2.6), 2.6, 1.4, "iron", offset=(2, 0)))
    p.append(bar((8.0, 9.4, zf - 1.1), (8.0, 6.2, zf - 3.1), 0.7, 0.6, "iron", offset=(0, 0)))
    # scowl wrinkles across the bridge
    for k, y in enumerate((8.6, 7.6)):
        p.append(box((6.7 + k * 0.2, y, zf - 2.2 - k * 0.7), (9.3 - k * 0.2, y + 0.35, zf - 1.6 - k * 0.7),
                     "dark_iron"))
    # the snout: a broad block, rounded at its corners in two steps
    p.append(box((5.2, 3.6, zf - 3.6), (10.8, 6.6, zf), "iron", offset=(3, 1)))
    p.append(box((5.6, 3.9, zf - 3.9), (10.4, 6.3, zf - 3.5), "iron", offset=(1, 0)))
    p.append(box((5.8, 6.4, zf - 3.4), (10.2, 6.9, zf - 0.2), "iron", offset=(0, 0)))
    # nostrils: dark flared slits on the snout's front
    for x in (6.3, 8.9):
        p.append(box((x, 5.2, zf - 4.0), (x + 0.8, 5.9, zf - 3.85), "lining"))
        p.append(box((x - 0.1, 5.9, zf - 4.0), (x + 0.9, 6.1, zf - 3.85), "iron", offset=(0, 0)))
    # brass nose ring through the septum
    p.append(box((7.55, 3.4, zf - 4.2), (8.45, 3.7, zf - 3.9), "brass"))
    p.append(box((7.35, 3.6, zf - 4.2), (7.6, 4.6, zf - 3.9), "brass"))
    p.append(box((8.4, 3.6, zf - 4.2), (8.65, 4.6, zf - 3.9), "brass"))
    # the maw: a dark open mouth under the snout, the lower jaw below it
    p.append(box((4.6, 2.0, zf - 3.0), (11.4, 3.7, zf + 0.2), "lining"))
    p.append(box((4.4, 0.6, zf - 2.8), (11.6, 2.0, zf + 0.6), "iron", offset=(2, 3)))
    p.append(box((5.0, 0.3, zf - 2.6), (11.0, 0.7, zf + 0.4), "dark_iron"))
    # upper fangs hanging from the snout, the long ones at the corners
    for x, ln in ((5.5, 1.5), (6.9, 0.7), (9.1, 0.7), (10.5, 1.5)):
        p.append(bar((x, 3.7, zf - 3.1), (x + (0.15 if x > 8 else -0.15), 3.7 - ln, zf - 3.2), 0.5, 0.45, "bone",
                     offset=(0, 0)))
    # lower tusks rising from the jaw, outside the snout
    for x in (4.7, 11.3):
        p.append(bar((x, 1.8, zf - 2.5), (x + (0.35 if x > 8 else -0.35), 4.3, zf - 2.9), 0.6, 0.55, "bone",
                     offset=(0, 0)))
    # the tongue, lolling out of the maw on the wearer's left and down past the chin
    pts = [(9.2, 2.6, zf - 2.6), (9.9, 2.0, zf - 3.6), (10.3, 0.4, zf - 4.1), (10.2, -1.4, zf - 4.0),
           (9.7, -2.8, zf - 3.6)]
    widths = (1.5, 1.4, 1.2, 0.9, 0.5)
    for k in range(len(pts) - 1):
        a, b = pts[k], pts[k + 1]
        b = [a[i] + (b[i] - a[i]) * 1.12 for i in range(3)]
        p.append(bar(a, b, widths[k], 0.45, "crimson", offset=(k * 2, 2)))
    return p


def band() -> list[dict]:
    """The dirty-brass band across the forehead and round the temples, riveted, a jagged
    iron crest of short spikes along its top between the horns."""
    p = []
    y0, y1 = BAND
    p.append(box((X0 - 0.15, y0, Z0 - 0.15), (X1 + 0.15, y1, 6.0), "brass", offset=(0, 0)))
    for x in (1.0, 3.2, 12.8, 15.0):
        p.append(box((x - 0.25, 13.15, Z0 - 0.4), (x + 0.25, 13.65, Z0 - 0.15), "iron"))
    for x in (6.2, 8.0, 9.8):
        ln = 1.5 if x == 8.0 else 1.0
        p.append(bar((x, y1 - 0.2, Z0 + 0.3), (x, y1 + ln, Z0 + 0.6), 0.7, 0.6, "dark_iron", offset=(0, 0)))
        p.append(bar((x, y1 + ln - 0.1, Z0 + 0.6), (x, y1 + ln + 0.6, Z0 + 0.7), 0.3, 0.3, "dark_iron"))
    return p


def ear() -> list[dict]:
    """The wearer's right ear (+x): a long, pointed goat ear jutting out and drooping from
    the side of the head, black fur outside, crimson inside (facing forward), a brass
    ring through it."""
    p = []
    root = (X1 - 0.2, 10.6, 6.6)
    tip = (X1 + 5.4, 7.6, 5.6)
    p.append(bar(root, tip, 2.8, 0.7, "fur", offset=(2, 2)))
    mid = [(root[i] + tip[i]) / 2 for i in range(3)]
    p.append(bar(root, mid, 3.4, 0.8, "fur", offset=(5, 1)))
    p.append(bar(mid, tip, 1.4, 0.8, "fur", offset=(0, 0)))
    p.append(bar([root[0] + 0.5, root[1] - 0.05, root[2] - 0.45], [tip[0] - 1.1, tip[1] + 0.35, tip[2] - 0.45],
                 1.6, 0.12, "crimson", offset=(0, 4)))
    # tufts of fur at the root
    p += fur_roll((X1 - 0.6, 9.4, 5.6), (X1 + 1.2, 11.8, 7.8), r=0.35, seed=12)
    # a brass ring through the ear
    ring = [box((X1 + 2.6, 7.7, 5.75), (X1 + 2.85, 9.1, 6.0), "brass"),
            box((X1 + 2.6, 7.5, 5.75), (X1 + 3.5, 7.75, 6.0), "brass"),
            box((X1 + 3.3, 7.7, 5.75), (X1 + 3.55, 9.1, 6.0), "brass")]
    p += ring
    return turn(p, -12, "x", root)


def horn() -> list[dict]:
    """The wearer's right horn (+x): a huge ridged goat horn from the forehead, sweeping
    up, back and out, its tip hooking down behind the head, with a brass collar."""
    parts = []
    n = 14
    cz, cy = 10.2, 14.6
    pts = []
    for k in range(n + 1):
        t = k / n
        th = math.radians(180 - 160 * t)
        r = 8.4 * (1 - 0.34 * t)
        z = cz + r * math.cos(th)
        y = cy + r * math.sin(th) * 1.15
        x = 11.8 + 7.6 * t ** 1.1
        pts.append((x, y, z))
    pts[0] = (11.5, 13.2, 2.4)
    for k in range(n):
        p0, p1 = pts[k], pts[k + 1]
        mid = [(p0[i] + p1[i]) / 2 for i in range(3)]
        q0 = [mid[i] + (p0[i] - mid[i]) * 1.14 for i in range(3)]
        q1 = [mid[i] + (p1[i] - mid[i]) * 1.14 for i in range(3)]
        wd = 3.6 * (1 - k / n) ** 0.95 + 0.35
        parts.append(bar(q0, q1, wd, wd * 0.9, "horn", roll=k * 3,
                         offset=(k * 3 % 10, max(0.0, 12.0 - k * 0.9))))
    tip = pts[-1]
    parts.append(bar(tip, (tip[0] + 0.2, tip[1] - 0.9, tip[2] + 0.3), 0.32, 0.32, "horn", offset=(3, 0)))
    # growth ridges: thin rings standing proud every few segments near the root
    for k in (1, 4, 7):
        p0, p1 = pts[k], pts[k + 1]
        mid = [(p0[i] + p1[i]) / 2 for i in range(3)]
        wd = 3.6 * (1 - (k + 0.5) / n) ** 0.95 + 0.35 + 0.35
        parts.append(bar([mid[i] - (p1[i] - p0[i]) * 0.06 for i in range(3)],
                         [mid[i] + (p1[i] - p0[i]) * 0.06 for i in range(3)], wd, wd * 0.9, "horn",
                         roll=k * 3, offset=(1, 13)))
    # the brass collar bolting it to the band
    collar = [box((9.7, 12.3, 1.2), (13.5, 14.9, 4.6), "brass", offset=(1, 0)),
              box((10.0, 14.7, 1.5), (13.2, 15.4, 4.3), "dark_iron")]
    for x in (10.6, 12.6):
        collar.append(box((x - 0.22, 13.4, 1.4), (x + 0.22, 13.85, 1.62), "iron"))
    return collar + parts


def build() -> list[dict]:
    right_ear, right_horn = ear(), horn()
    return (hood() + mask() + muzzle() + band() + right_ear + mirror(right_ear, "x", 8.0) + right_horn
            + mirror(right_horn, "x", 8.0))


def models() -> dict:
    parts = build()
    return {"main": model(parts, display("helmet", parts, gui_rotation=(12, 152, 0), gui_span=15.5))}
