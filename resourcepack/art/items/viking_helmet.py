"""Viking Helmet: the head piece of the Viking set (issue #1217) and the set's centrepiece,
with the wearer's big ginger beard built into the model.

A rounded iron cap in the Gjermundbu style: a low dome of weathered steel with a riveted
crest ridge front to back, a brass brow band stamped with the set's running chevron and,
over the forehead, the ember rune roundel. A spectacle guard rings the eyes (never
across them) and runs into the nasal. Two ridged ivory horns sweep out of brass collars
on the sides, curving up and a little forward.

Under the helmet the wearer's ginger hair shows at the sides and back, ending in a thick
braid down the back, and a big ginger beard covers the jaw: a drooping moustache over
the mouth, sideburns running up to the cheek guards, the beard hanging in tiers onto the
chest (it stays in front of the chestplate) and finishing in two brass-ringed braids.
The beard is part of the helmet's item model, so it follows the head everywhere the
helmet renders (third person, other players, armor stands); the client never draws the
wearer's own helmet in first person, so it never blocks the view.

Frame (helmet): the wearer's head is the cube [1.6, 14.4]^3, face at -Z, up = +Y,
+X = the wearer's right. Inner surfaces stay at 0.6 / 15.4 to clear the hat layer.
Steve's eyes are the face's 5th pixel row (y 6.4 .. 8.0) at x 3.2 .. 6.4 and 9.6 .. 12.8.
Below the head (y < 1.6) the body's front is at z 4.8 and a chestplate's at about 3.2.
"""
from __future__ import annotations

import random

from art.items._viking import hair as paint_hair, save_set_materials, swatch
from art.kit import bar, box, display, lathe, mirror, model, turn

ID = "viking_helmet"
NAME = "Viking Helmet"
KIND = "helmet"
COUNTERPART = "item/netherite_helmet"

X0, X1 = -0.2, 16.2           # outer shell
Z0, Z1 = -0.2, 16.2
IN0, IN1 = 0.6, 15.4          # inner surfaces
RIM = (9.6, 11.6)             # the brass brow band (y)


def textures() -> None:
    save_set_materials(skip=("leather", "wool", "hem", "fur", "strap"))
    # the moustache catches the light: the beard's locks a step brighter
    swatch("beard_light", lambda a: paint_hair(a, seed=8, bias=0.7))


# ======================================================================================
# The cap
# ======================================================================================

TIERS = [  # (y0, y1, inset, corner): the skull from the band up, then a low rounded crown.
    (RIM[1], 12.6, 0.0, 2.4),
    (12.6, 13.6, 0.15, 2.0),
    (13.6, 14.6, 0.4, 1.7),
    (14.6, 15.35, 0.75, 1.6),
    (15.35, 16.0, 1.6, 2.4),
    (16.0, 16.6, 2.8, 2.4),
    (16.6, 17.1, 4.2, 2.1),
    (17.1, 17.45, 5.7, 1.5),
]


def dome() -> list[dict]:
    """The steel cap: tiers drawing in to a rounded crown, each tier's corners cut back in
    two steps so it reads round from above and the side."""
    parts = []
    for i, (y0, y1, inset, c) in enumerate(TIERS):
        x0, x1, z0, z1 = X0 + inset, X1 - inset, Z0 + inset, Z1 - inset
        skip = ("down",) if i else ()
        k = c * 0.45
        v = 0 if i == 0 else 2 + i % 3
        for (a, b), u in (((c, 0), i * 3), ((0, c), i * 7), ((k, k), i * 11)):
            parts.append(box((x0 + a, y0, z0 + b), (x1 - a, y1, z1 - b), "steel", skip=skip,
                             faces={"down": "lining"}, offset=(u % 12, v)))
    return parts


def crest() -> list[dict]:
    """The riveted crest ridge, front to back over the crown, stepping with the tiers, and
    a narrower brass strap side to side."""
    parts = []
    w, p = 0.7, 0.3
    for y0, y1, inset, _ in TIERS:
        parts.append(box((8 - w, y0, Z0 + inset - p), (8 + w, y1 + p, Z1 - inset + p), "steel",
                         skip=("down",), offset=(2, 0)))
        parts.append(box((8 - w - 0.12, y0, Z0 + inset - p + 0.1), (8 + w + 0.12, y1 + 0.12, Z1 - inset + p - 0.1),
                         "brass", skip=("down", "up")))
        parts.append(box((X0 + inset - 0.12, y0, 8 - 0.5), (X1 - inset + 0.12, y1 + 0.1, 8 + 0.5), "brass",
                         skip=("down",)))
    top = TIERS[-1][1]
    parts.append(box((7.1, top, 6.2), (8.9, top + 0.6, 9.8), "steel", offset=(4, 0)))
    parts.append(box((7.5, top + 0.6, 7.0), (8.5, top + 1.0, 9.0), "brass"))
    # rivets on the crest's flanks
    for y, inset in ((12.1, 0.0), (13.1, 0.15), (14.1, 0.4)):
        a, b = Z0 + inset - p, Z1 - inset + p
        for z in (a - 0.15, b):
            parts.append(box((8 - 0.25, y - 0.25, z), (8 + 0.25, y + 0.25, z + 0.15), "brass", offset=(0, 0)))
        for x in (X0 + inset - 0.3, X1 - inset + 0.15):
            for dz in (-1.6, 1.6):
                parts.append(box((x, y - 0.22, 8 + dz - 0.22), (x + 0.15, y + 0.22, 8 + dz + 0.22), "brass"))
    return parts


def brow_band() -> list[dict]:
    """The brass brow band round the rim, stamped with the chevron, a lip under it and the
    dark lining in the open face."""
    y0, y1 = RIM
    o = 0.15
    ch = {s: ("chevron", [0, 0, 16, 1.6]) for s in ("north", "south", "east", "west")}
    ch.update({"up": "brass", "down": "brass"})
    parts = [box((X0 - o, y0 + 0.3, Z0 - o), (X1 + o, y1, Z1 + o), "chevron", faces=ch),
             box((X0 - o + 0.05, y0, Z0 - o + 0.05), (X1 + o - 0.05, y0 + 0.4, Z1 + o - 0.05), "brass")]
    parts.append(box((IN0, y0 - 0.2, Z0), (IN1, y0, IN0), "lining", skip=("up",)))
    return parts


def emblem() -> list[dict]:
    """The ember rune roundel over the forehead: a turned brass dome with the glowing rune
    stone at its face."""
    cx, cy, z = 8.0, (RIM[0] + RIM[1]) / 2 + 0.1, Z0 - 0.15
    prof = [(0.0, 1.55), (-0.35, 1.55), (-0.35, 1.3), (-0.55, 1.3)]
    parts = lathe((cx, cy, z), prof, "brass", axis="z", sides=16, cap="chevron")
    parts.append(box((cx - 0.85, cy - 0.85, z - 0.75), (cx + 0.85, cy + 0.85, z - 0.45), "ember",
                     uv="full", glow=12, faces={"north": "ember"}, skip=("south",)))
    return parts


def spectacles() -> list[dict]:
    """The spectacle guard: a steel ring round each eye (open over the eye itself) joined
    to the nasal, its outer edge wrapping back along the cheek to the hair."""
    zf0, zf1 = Z0 - 0.15, IN0
    right = [
        # under the eye: a lower rim curving up at its outer end
        box((7.0, 5.6, zf0), (2.8, 6.4, zf1), "steel", offset=(3, 2)),
        box((3.2, 5.25, zf0 + 0.1), (6.6, 5.6, zf1), "steel", offset=(5, 3)),
        # the outer post beside the eye, then back along the cheek
        box((X0, 5.6, zf0), (3.2, RIM[0], zf1), "steel", offset=(1, 1)),
        box((X0 - 0.1, 5.6, zf1), (IN0, RIM[0], 4.6), "steel", offset=(6, 1)),
        box((X0 - 0.25, 5.3, zf0 - 0.05), (2.8, 5.6, 4.6), "brass", skip=("east",)),
    ]
    # rivets on the ring
    for x, y in ((1.4, 8.6), (1.4, 6.6), (4.9, 5.8)):
        right.append(box((x - 0.25, y - 0.25, zf0 - 0.15), (x + 0.25, y + 0.25, zf0), "brass"))
    right.append(box((X0 - 0.3, 7.4, 2.6), (X0 - 0.1, 7.9, 3.1), "brass"))
    parts = right + mirror(right, "x", 8.0)
    # the nasal between the eyes, tapering to its tip over the moustache
    parts.append(box((7.0, 5.6, zf0 - 0.1), (9.0, RIM[0], zf1), "steel", offset=(8, 0)))
    parts.append(box((7.3, 4.6, zf0 - 0.05), (8.7, 5.6, zf1), "steel", offset=(8, 1)))
    parts.append(box((7.6, 4.1, zf0), (8.4, 4.6, zf1), "steel", offset=(8, 2)))
    parts.append(box((6.9, 5.6, zf0 - 0.15), (7.1, RIM[0], zf1), "brass"))
    parts.append(box((8.9, 5.6, zf0 - 0.15), (9.1, RIM[0], zf1), "brass"))
    parts.append(box((7.75, 7.0, zf0 - 0.3), (8.25, 7.5, zf0 - 0.1), "brass"))
    return parts


# ======================================================================================
# Horns
# ======================================================================================

def _bezier(p0, p1, p2, t):
    return tuple((1 - t) ** 2 * p0[i] + 2 * (1 - t) * t * p1[i] + t * t * p2[i] for i in range(3))


def horn() -> list[dict]:
    """The wearer's left horn (-x): a ridged ivory horn curving out of a brass collar on
    the side of the cap, up and a little forward, tapering to a dark tip."""
    p0, p1, p2 = (0.4, 12.9, 7.4), (-6.2, 12.4, 6.6), (-6.4, 19.6, 3.6)
    n = 9
    parts = []
    pts = [_bezier(p0, p1, p2, k / n) for k in range(n + 1)]
    for k in range(n):
        a, b = pts[k], pts[k + 1]
        # stretch each segment so neighbours overlap
        mid = [(a[i] + b[i]) / 2 for i in range(3)]
        a2 = [mid[i] + (a[i] - mid[i]) * 1.18 for i in range(3)]
        b2 = [mid[i] + (b[i] - mid[i]) * 1.18 for i in range(3)]
        t = (k + 0.5) / n
        w = 2.7 * (1 - t) ** 0.8 + 0.35
        v0, v1 = 16 * k / n, 16 * (k + 1) / n
        uv = {s: [0, v0, 16, v1] for s in ("north", "south", "east", "west")}
        uv.update({"up": [4, v0, 12, v0 + 1], "down": [4, v1 - 1, 12, v1]})
        parts.append(bar(a2, b2, w, w, "horn", uv=uv))
        if k in (1, 3, 5):  # growth ridges standing proud
            parts.append(bar([mid[i] - (b[i] - a[i]) * 0.08 for i in range(3)],
                             [mid[i] + (b[i] - a[i]) * 0.08 for i in range(3)], w + 0.3, w + 0.3, "horn",
                             uv={s: [0, v0 + 1, 16, v0 + 1.6] for s in
                                                      ("north", "south", "east", "west", "up", "down")}))
    # the brass collar where the horn meets the cap, with rivets
    parts += lathe((X0 + 0.2, 12.9, 7.4), [(-0.1, 1.95), (-0.9, 1.95), (-0.9, 1.6), (-1.25, 1.6)], "brass",
                   axis="x", sides=16, cap="chevron")
    for dy, dz in ((1.55, 0), (-1.55, 0), (0, 1.55), (0, -1.55)):
        parts.append(box((X0 - 1.15, 12.9 + dy - 0.22, 7.4 + dz - 0.22), (X0 - 0.95, 12.9 + dy + 0.22,
                                                                          7.4 + dz + 0.22), "steel", offset=(0, 0)))
    return parts


# ======================================================================================
# Hair and beard (ginger)
# ======================================================================================

def locks(x0, x1, y, z0, z1, seed, size=1.0, step=0.9, tex="beard", tilt=8.0) -> list[dict]:
    """A ragged row of hair locks hanging from height y across x0..x1, each a little
    turned so the edge reads loose rather than cut."""
    rng = random.Random(seed)
    parts = []
    x = x0
    while x < x1 - 0.15:
        w = min(step * rng.uniform(0.8, 1.2), x1 - x)
        h = size * rng.choice((0.5, 0.8, 1.0, 1.3, 1.6))
        e = box((x + 0.04, y - h, z0), (x + w - 0.04, y, z1), tex, offset=(rng.randrange(13), rng.randrange(10)))
        turn(e, rng.uniform(-tilt, tilt), "z", (x + w / 2, y, (z0 + z1) / 2))
        parts.append(e)
        x += w
    return parts


def hair() -> list[dict]:
    """The wearer's ginger hair showing under the rim at the sides and back, and a thick
    plait down the back with a brass ring."""
    parts = []
    ytop = RIM[0] + 0.1
    # sides (behind the cheek guards) and back
    side = [box((X0 - 0.15, 3.6, 4.6), (IN0, ytop, IN1), "beard", faces={"east": "beard_shade"}, offset=(3, 0)),
            box((X0 - 0.35, 5.0, 6.5), (X0 - 0.15, ytop - 0.6, 13.5), "beard", offset=(7, 2))]
    side += [e for e in locks(4.6, IN1, 3.7, 0, 1, seed=31, size=0.9, step=1.1)]
    for e in side[2:]:   # stand the side locks along z on the side of the head
        (xa, ya, za), (xb, yb, zb) = e["from"], e["to"]
        e["from"], e["to"] = [X0 - 0.1, ya, xa], [IN0 - 0.1, yb, xb]
        e.pop("rotation", None)
    parts += side + mirror(side, "x", 8.0)
    parts.append(box((X0 - 0.15, 2.4, IN1), (X1 + 0.15, ytop, Z1 + 0.25), "beard", faces={"north": "beard_shade"},
                     offset=(1, 0)))
    back = locks(X0 - 0.1, X1 + 0.1, 2.5, Z1 + 0.05, Z1 + 0.35, seed=37, size=1.0, step=1.2)
    parts += back
    # the back plait: stacked, alternating lobes down to the brass ring and a tassel
    y = 2.6
    for k in range(6):
        dx = 0.35 if k % 2 else -0.35
        parts.append(box((8 - 1.3 + dx, y - 1.35, Z1 + 0.15), (8 + 1.3 + dx, y, Z1 + 1.55), "beard",
                         faces={"down": "beard_shade"}, offset=(k * 2, k)))
        y -= 1.1
    parts += lathe((8.0, y + 0.6, Z1 + 0.85), [(-0.6, 1.0), (0.6, 1.0)], "brass", axis="y", sides=8)
    parts.append(box((8 - 0.9, y - 1.6, Z1 + 0.3), (8 + 0.9, y + 0.1, Z1 + 1.4), "beard", offset=(9, 4)))
    parts.append(box((8 - 0.5, y - 2.3, Z1 + 0.5), (8 + 0.5, y - 1.5, Z1 + 1.2), "beard", offset=(4, 7)))
    return parts


BEARD_TIERS = [  # (y0, y1, x inset from the head's sides, z front, z back)
    (-1.0, 1.7, 0.6, -1.7, 4.0),
    (-3.2, -0.9, 1.9, -1.9, 2.6),
    (-5.0, -3.1, 3.3, -1.7, 2.0),
    (-6.5, -4.9, 4.8, -1.4, 1.5),
]


def beard() -> list[dict]:
    """The big ginger beard: a full mass over the jaw and cheeks from under the moustache,
    sideburns up to the cheek guards, then tiers hanging onto the chest, ragged locks at
    every tier's edge, and two brass-ringed braids at the point."""
    parts = []
    shade = {"down": "beard_shade", "south": "beard_shade"}
    # the jaw: in front of the mouth and chin, round the cheeks
    parts.append(box((X0 - 0.2, 1.4, Z0 - 1.0), (X1 + 0.2, 4.4, IN0), "beard", faces=shade, offset=(0, 1)))
    parts.append(box((X0 + 0.6, 4.4, Z0 - 0.7), (5.6, 5.3, IN0), "beard", faces=shade, offset=(2, 0)))
    parts.append(box((10.4, 4.4, Z0 - 0.7), (X1 - 0.6, 5.3, IN0), "beard", faces=shade, offset=(6, 0)))
    side = [box((X0 - 0.25, 1.4, IN0), (IN0, 5.6, 4.8), "beard", faces={"east": "beard_shade"}, offset=(5, 1))]
    parts += side + mirror(side, "x", 8.0)
    # under the chin: the beard's underside, back toward the neck (stays clear of the chest)
    for i, (y0, y1, inset, zf, zb) in enumerate(BEARD_TIERS):
        x0, x1 = X0 + inset, X1 - inset
        c = 0.9 if i < 2 else 0.6
        parts.append(box((x0, y0, zf + c), (x1, y1, zb), "beard", faces=shade, offset=(i * 3, i * 2)))
        parts.append(box((x0 + c, y0, zf), (x1 - c, y1, zb - 0.3), "beard", faces=shade, offset=(i * 5 % 12, i)))
        # ragged locks over each tier's front and bottom edge
        if i >= 1:
            parts += locks(x0 + 0.3, x1 - 0.3, y0 + 0.5, zf - 0.3, zf + 0.6, seed=11 + i, size=1.0, step=1.7,
                           tilt=12.0)
    parts += locks(X0 + 0.6, X1 - 0.6, 1.9, Z0 - 1.3, Z0 - 0.7, seed=7, size=1.1, step=1.9, tilt=10.0)
    # proud strands on the beard's face, for depth
    rng = random.Random(21)
    for _ in range(8):
        x = rng.uniform(2.4, 13.6)
        y = rng.uniform(-3.6, 3.6)
        lim = next((t for t in BEARD_TIERS if t[0] <= y <= t[1]), None)
        if lim is not None and not (X0 + lim[2] + 0.6 <= x <= X1 - lim[2] - 0.6):
            continue
        zf = lim[3] if lim else Z0 - 1.0
        h = rng.uniform(1.4, 2.6)
        e = box((x - 0.35, y - h, zf - 0.35), (x + 0.35, y, zf + 0.1), "beard", offset=(rng.randrange(14), 2))
        turn(e, rng.uniform(-10, 10), "z", (x, y, zf))
        parts.append(e)
    # the two braids from the point, each with a brass ring and a flared tip
    for bx in (6.5, 9.5):
        y = -6.3
        for k in range(4):
            dx = 0.25 if k % 2 else -0.25
            lobe = box((bx - 1.2 + dx, y - 1.15, -1.5), (bx + 1.2 + dx, y, 0.8), "beard",
                       faces={"down": "beard_shade", "up": "beard_shade"}, offset=((k * 3 + int(bx)) % 12, k))
            turn(lobe, 14 if k % 2 else -14, "z", (bx + dx, y - 0.6, -0.35))
            parts.append(lobe)
            y -= 0.95
        parts += lathe((bx, y + 0.35, -0.35), [(-0.55, 1.15), (0.55, 1.15)], "brass", axis="y", sides=8,
                       cap="chevron")
        parts.append(box((bx - 0.75, y - 1.4, -1.0), (bx + 0.75, y - 0.1, 0.4), "beard", offset=(3, 5)))
        parts.append(box((bx - 0.45, y - 2.0, -0.8), (bx + 0.45, y - 1.3, 0.2), "beard", offset=(8, 6)))
    return parts


def moustache() -> list[dict]:
    """The moustache: two full lobes under the nose, drooping past the mouth corners and
    down into the beard."""
    right = []
    z0, z1 = Z0 - 2.0, Z0 - 0.6
    sh = {"down": "beard_shade", "south": "beard_shade"}
    # the lobe under the nose, swelling out from the centre
    right.append(box((8.0, 3.8, z0 + 0.15), (10.6, 4.9, z1), "beard_light", faces=sh, offset=(2, 3)))
    right.append(box((8.2, 4.9, z0 + 0.35), (10.2, 5.35, z1), "beard_light", faces=sh, offset=(6, 1)))
    # sweeping out and drooping past the mouth corner, down into the beard
    for p0, p1, w, d in (((10.0, 4.4, z0 + 0.75), (12.7, 3.2, z0 + 0.8), 1.35, 1.25),
                         ((12.4, 3.6, z0 + 0.85), (13.3, 0.8, z0 + 0.9), 1.15, 1.1),
                         ((13.2, 1.1, z0 + 0.9), (13.4, -0.8, z0 + 0.95), 0.8, 0.85)):
        right.append(bar(p0, p1, w, d, "beard_light", faces=sh, offset=(int(p0[0]) % 12, 4)))
    # a dark parting down the middle
    right.append(box((7.85, 3.9, z0 + 0.05), (8.15, 5.2, z1), "beard_shade", offset=(3, 3)))
    return right + mirror(right, "x", 8.0)


def build() -> list[dict]:
    left = horn()
    return (dome() + crest() + brow_band() + emblem() + spectacles() + left + mirror(left, "x", 8.0)
            + hair() + beard() + moustache())


def models() -> dict:
    parts = build()
    return {"main": model(parts, display("helmet", parts, gui_rotation=(14, 152, 0), gui_span=15.5))}
