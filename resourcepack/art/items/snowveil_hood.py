"""Snowveil Hood: the helmet of the Snowveil set (December "Winter's Solstice" crate).

A quilted navy hood worn over the head: a softly domed crown dusted with settled snow and
sewn with a pale-blue centre seam, snow hanging over its edges, and sides that fall to a
piped, frost-crusted hem. A thick ring of white snow fur
frames the face; inside it the hood's dark lining shows as a deep recess round the face.
On the fur over the forehead sits the Snowveil sigil: a frosted-silver diamond setting
with a glowing ice crystal (the same sigil as the Guard's chest and the Skates' ankles).
The back carries the set's embroidered snowflake.

Kept compact for a worn helmet: the shell sits 1 unit off the head, the fur ring stands
2 units proud of the face, and nothing crosses the eyes.

Frame (helmet): the wearer's head is the cube [1.6, 14.4]^3, face at -Z, up = +Y,
+X = the wearer's right. Inner surfaces stay at 0.6 / 15.4 to clear the hat layer.
"""
from __future__ import annotations

import random

import numpy as np

from art.items.snowveil_guard import (NAVY, C, band_mask, blank, embroidered_flake, frost_creep, fur, grid, piping,
                                      quilt, save_set_materials, scallop, to_image)
from art.kit import box, display, mirror, model, save, turn

ID = "snowveil_hood"
NAME = "Snowveil Hood"
KIND = "helmet"
COUNTERPART = "item/netherite_helmet"

# --------------------------------------------------------------------------------------
# Frame. The shell paintings cover an 18-unit cube (64 texels, 0.28 units a texel):
# x -1..17, z -1..17, y 17.6 (top) .. -0.4 (bottom).
# --------------------------------------------------------------------------------------
XL, ZF, YT = -1.0, -1.0, 17.6
SPAN = 18.0
K = 16 / SPAN                  # uv units per model unit
TEX = 64
X0, X1 = -0.6, 16.6            # hood shell
Z0, Z1 = -0.2, 16.8
IN0, IN1 = 0.6, 15.4           # inner surfaces
CROWN = 16.4                   # top of the crown slab
RIM_Z = (-2.0, 0.0)            # the fur ring's depth
RIM_TOP = (11.8, 15.3)         # the ring's band over the forehead
RIM_IN = 2.0                   # the ring's inner edge (x) either side of the face
SIGIL = (8.0, 13.55)           # the forehead sigil's centre (x, y)
SIDE_Y0, BACK_Y0 = 1.0, 0.6    # bottoms of the shell's sides and back (just under the head)
RING_Y0 = 1.0                  # bottom of the fur ring's side bands


def tx(x):
    return int((x - XL) / SPAN * TEX)


def ty(y):
    return int((YT - y) / SPAN * TEX)


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def snow_cap(a: np.ndarray, seed: int, gaps: float = 0.0) -> None:
    """Snow settled over a whole face (the crown seen from above): even fur with, when
    gaps > 0, a few soft holes where the quilting shows through."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    rng = random.Random(seed)
    waves = [(rng.uniform(0.12, 0.3), rng.uniform(0.12, 0.3), rng.uniform(0, 6.3)) for _ in range(3)]
    n = sum(np.sin(X * fx + Y * fy + p) for fx, fy, p in waves) / 3
    m = n > (-1 + 2 * gaps)
    fur(a, m, seed=seed, hang=True, glints=0.025, lift=1)


def snow_band(a: np.ndarray, base_y: float, seed: int) -> None:
    """The snow hanging over the crown's edge onto a side: the set's scalloped fur edge."""
    h, w = a.shape[:2]
    rows = scallop(w, ty(base_y), seed, depth=3, tuft=6)
    fur(a, band_mask(h, w, bottom=rows), seed=seed, hang=True, glints=0.02, lift=1)


def hem(a: np.ndarray, y: float, seed: int) -> None:
    """Piped hem with frost crusting up from it."""
    row = ty(y)
    frost_creep(a, [row] * TEX, seed=seed, lo=2, hi=5)
    m = np.zeros(a.shape[:2], bool)
    m[row:row + 3] = True
    piping(a, m, ao=0)
    a[row + 3:] = C(NAVY[1])


def paint_side() -> np.ndarray:
    """The wearer's left side seen from outside (u = 0 at the front)."""
    a = blank(TEX, TEX)
    X, Y = grid(TEX, TEX)
    quilt(a, seed=3, bias=-0.2 * (Y / TEX))
    snow_band(a, IN1 - 0.6, seed=5)
    hem(a, SIDE_Y0 + 0.85, seed=7)
    return a


def paint_top() -> np.ndarray:
    """Seen from above, the front at the top: the crown under settled snow, a little
    quilting showing through."""
    a = blank(TEX, TEX)
    quilt(a, seed=13, bias=0.15)
    snow_cap(a, seed=17, gaps=0.18)
    return a


def paint_back() -> np.ndarray:
    a = blank(TEX, TEX)
    X, Y = grid(TEX, TEX)
    quilt(a, seed=23, bias=-0.2 * (Y / TEX))
    embroidered_flake(a, tx(8.0), ty(7.4), 5, scale=2)
    snow_band(a, IN1 - 0.6, seed=29)
    hem(a, BACK_Y0 + 0.85, seed=31)
    return a


def paint_inner() -> np.ndarray:
    """The hood's lining round the face: the set's quilting, deep in shadow."""
    a = blank(64, 64)
    quilt(a, seed=37, bias=-0.7)
    return a


def paint_fur_shade() -> np.ndarray:
    """The fur ring's inner faces, turned away from the light into the hood."""
    a = blank(64, 64)
    fur(a, np.ones((64, 64), bool), seed=41, hang=False, lift=-2, glints=0.0, edge=False)
    return a


def textures() -> None:
    save(to_image(paint_side()), "side")
    save(to_image(paint_top()), "top")
    save(to_image(paint_back()), "back")
    save(to_image(paint_fur_shade()), "fur_shade")
    save(to_image(paint_inner()), "inner")
    save_set_materials(skip=("navy",))


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def _clamp(uv):
    return [round(min(16.0, max(0.0, v)), 4) for v in uv]


def proj(side, frm, to):
    """UVs of a face projected onto the shell painting for that side."""
    (x0, y0, z0), (x1, y1, z1) = frm, to
    v0, v1 = (YT - y1) * K, (YT - y0) * K
    if side == "west":
        return _clamp([(z0 - ZF) * K, v0, (z1 - ZF) * K, v1])
    if side == "east":     # mirrored, so the front of the side painting stays at the front
        return _clamp([(z1 - ZF) * K, v0, (z0 - ZF) * K, v1])
    if side == "south":
        return _clamp([(x0 - XL) * K, v0, (x1 - XL) * K, v1])
    if side == "north":
        return _clamp([(XL + SPAN - x1) * K, v0, (XL + SPAN - x0) * K, v1])
    return _clamp([(x0 - XL) * K, (z0 - ZF) * K, (x1 - XL) * K, (z1 - ZF) * K])


SHELL = {"east": "side", "west": "side", "north": "side", "south": "back", "up": "top", "down": "lining"}


def shell_box(frm, to, inner=(), skip=(), **kw) -> dict:
    """A shell piece wearing the projected paintings; sides in `inner` show the lining."""
    faces = {}
    for side in ("north", "south", "east", "west", "up", "down"):
        if side in skip:
            continue
        if side in inner or side == "down":
            faces[side] = ("lining", [0, 0, 4, 4])
        else:
            faces[side] = (SHELL[side], proj(side, frm, to))
    return box(frm, to, "side", faces=faces, skip=skip, **kw)


def shell() -> list[dict]:
    """The quilted shell: a domed crown (three stepped slabs, rounded at the back too),
    the sides and the back wall, with the back corners and the back of the crown rounded
    off by inset slabs, and the brow over the forehead inside the fur ring."""
    parts = [
        shell_box((X0, IN1, Z0 + 0.6), (X1, CROWN - 0.3, Z1 - 0.6)),
        shell_box((X0 + 0.5, CROWN - 0.3, Z0 + 0.8), (X1 - 0.5, CROWN + 0.3, Z1 - 0.9)),
        shell_box((X0 + 1.6, CROWN + 0.3, Z0 + 1.6), (X1 - 1.6, CROWN + 0.8, Z1 - 1.9)),
        shell_box((X0 + 3.6, CROWN + 0.8, Z0 + 3.0), (X1 - 3.6, CROWN + 1.1, Z1 - 3.6)),
        # back wall, falling a little lower than the sides
        shell_box((IN0, BACK_Y0, IN1), (IN1, IN1 - 0.6, Z1), inner=("north",)),
        shell_box((IN0 + 0.3, IN1 - 0.6, IN1), (IN1 - 0.3, IN1, Z1 - 0.6), inner=("north",)),
        # the brow over the forehead, inside the fur ring
        shell_box((IN0, RIM_TOP[0] - 0.4, Z0 + 0.6), (IN1, IN1, IN0), inner=("south",)),
    ]
    side = [
        shell_box((X0, SIDE_Y0, Z0 + 0.6), (IN0, IN1, Z1 - 0.6), inner=("east",)),
        # the back edge of the side, rounded into the back wall
        shell_box((X0 + 0.4, (SIDE_Y0 + BACK_Y0) / 2, Z1 - 0.6), (IN0 + 0.4, IN1 - 0.6, Z1), inner=("east",)),
    ]
    parts += side + mirror_x(side)
    return parts


def mirror_x(elements) -> list[dict]:
    """Mirror parts across x = 8 keeping projected side paintings reading front-at-u0."""
    out = []
    for e in elements:
        a, b = e["from"], e["to"]
        m = {"from": [round(16 - b[0], 4), a[1], a[2]], "to": [round(16 - a[0], 4), b[1], b[2]], "faces": {}}
        for side, face in e["faces"].items():
            new = {"east": "west", "west": "east"}.get(side, side)
            f = dict(face)
            u0, v0, u1, v1 = f["uv"]
            f["uv"] = [u1, v0, u0, v1]
            m["faces"][new] = f
        for key in ("light_emission", "shade"):
            if key in e:
                m[key] = e[key]
        if "rotation" in e:
            r = dict(e["rotation"])
            o = list(r["origin"])
            o[0] = round(16 - o[0], 4)
            r["origin"] = o
            if "axis" in r:
                if r["axis"] in ("y", "z"):
                    r["angle"] = -r["angle"]
            else:
                r["y"], r["z"] = -r.get("y", 0), -r.get("z", 0)
            m["rotation"] = r
        out.append(m)
    return out


def seam() -> list[dict]:
    """The pale piping seam running over the crown from the fur ring to the tip, and the
    piping along the hem."""
    parts = [box((7.75, CROWN + 1.1, Z0 + 3.0), (8.25, CROWN + 1.3, Z1 - 3.6), "pale"),
             box((7.75, CROWN + 0.8, Z1 - 3.6), (8.25, CROWN + 1.0, Z1 - 1.9), "pale"),
             box((7.75, CROWN + 0.3, Z1 - 1.9), (8.25, CROWN + 0.5, Z1 - 0.9), "pale"),
             box((7.75, IN1 - 0.6, Z1 - 0.9), (8.25, CROWN - 0.1, Z1 - 0.4), "pale")]
    y0, yb = SIDE_Y0, (SIDE_Y0 + BACK_Y0) / 2
    hem_ = [box((X0 - 0.12, y0 + 0.05, Z0 + 0.6), (X0 + 0.1, y0 + 0.55, Z1 - 0.6), "pale"),
            box((X0 + 0.28, yb + 0.05, Z1 - 0.6), (X0 + 0.5, yb + 0.55, Z1), "pale")]
    parts += hem_ + mirror(hem_, "x", 8.0)
    parts.append(box((IN0, BACK_Y0, Z1 + 0.02), (IN1, BACK_Y0 + 0.5, Z1 + 0.2), "pale"))
    return parts


def fur_ring() -> list[dict]:
    """The thick snow-fur ring framing the face: a rounded band over the forehead and one
    down each side (inside the head's footprint, ending just under it), tufts breaking
    the outer edges, inner faces turned into the hood's shadow, and just inside the ring
    a recessed band of the dark quilted lining all round the face."""
    z0, z1 = RIM_Z
    y0, y1 = RIM_TOP
    parts = [
        box((X0 - 0.4, y0 + 0.5, z0), (X1 + 0.4, y1 - 0.5, z1), "fur", faces={"down": "fur_shade"}),
        box((X0, y0, z0 + 0.4), (X1, y1, z1), "fur", faces={"down": "fur_shade"}, offset=(5, 3)),
    ]
    side = [
        box((X0 - 0.4, RING_Y0 + 0.5, z0 + 0.1), (RIM_IN, y0 + 0.5, z1), "fur", faces={"east": "fur_shade"},
            offset=(2, 6)),
        box((X0, RING_Y0, z0 + 0.5), (RIM_IN - 0.4, y0, z1), "fur", faces={"east": "fur_shade"}, offset=(7, 1)),
    ]
    # tufts along the outer side edge
    rng = random.Random(11)
    y = RING_Y0 + 0.2
    while y < y0 + 0.6:
        h = rng.choice((0.9, 1.1, 1.3))
        out = rng.choice((0.2, 0.35, 0.5))
        side.append(box((X0 - 0.4 - out, y, z0 + 0.3), (X0 - 0.3, min(y0 + 0.9, y + h), z1 - 0.3), "fur",
                        offset=(rng.randrange(12), rng.randrange(12))))
        y += h
    side.append(box((X0 + 0.2, RING_Y0 - 0.35, z0 + 0.6), (RIM_IN - 0.6, RING_Y0, z1 - 0.2), "fur", offset=(3, 9)))
    parts += side + mirror(side, "x", 8.0)
    # along the top
    x = X0 - 0.2
    while x < X1 + 0.2:
        w = rng.choice((1.1, 1.3, 1.5))
        up = rng.choice((0.3, 0.5, 0.7))
        parts.append(box((x, y1 - 0.2, z0 + 0.4), (min(X1 + 0.4, x + w), y1 + up, z1 - 0.4), "fur",
                         offset=(rng.randrange(12), rng.randrange(12))))
        x += w
    # small fluffs on the ring's face so it reads soft
    for fx, fy in ((-0.1, 9.0), (0.6, 4.6), (-0.2, 2.2), (1.6, 14.4), (4.4, 14.8), (12.0, 14.6), (14.6, 14.2),
                   (15.3, 9.2), (16.0, 5.0), (15.4, 2.0)):
        s_ = rng.choice((0.6, 0.8))
        parts.append(box((fx - s_, fy - s_, z0 - 0.3), (fx + s_, fy + s_, z0 + 0.3), "fur",
                         offset=(rng.randrange(12), rng.randrange(12))))
    # the lining: a recessed band of dark quilting between the fur and the face, its
    # inner edges stepping back into the hood
    lining = [box((RIM_IN - 0.2, RING_Y0, z1 - 0.6), (RIM_IN + 1.0, y0 + 0.2, IN0 + 0.1), "inner",
                  faces={"east": "lining"}, skip=("west", "south")),
              box((IN0, RING_Y0, z1), (RIM_IN, y0, IN0 + 0.1), "lining", skip=("west", "south", "down", "north"))]
    parts += lining + mirror(lining, "x", 8.0)
    parts.append(box((RIM_IN + 1.0, y0 - 1.2, z1 - 0.6), (16 - RIM_IN - 1.0, y0 + 0.2, IN0 + 0.1), "inner",
                     faces={"down": "lining"}, skip=("up", "south")))
    return parts


def forehead_sigil() -> list[dict]:
    """The Snowveil sigil on the fur over the forehead, as on the Guard, the Leggings and the
    Skates: a frosted-silver diamond setting with a stepped bezel and an ice field, and a
    small glowing crystal held inside it."""
    cx, cy = SIGIL
    z = RIM_Z[0]
    setting = [
        box((cx - 2.0, cy - 2.0, z - 0.35), (cx + 2.0, cy + 2.0, z + 0.2), "silver"),
        box((cx - 1.45, cy - 1.45, z - 0.6), (cx + 1.45, cy + 1.45, z - 0.35), "silver"),
        box((cx - 1.1, cy - 1.1, z - 0.65), (cx + 1.1, cy + 1.1, z - 0.6), "ice", glow=8, shade=False),
    ]
    turn(setting, 45, "z", (cx, cy, z))
    shard = [box((cx - 0.42, cy - 0.95, z - 1.2), (cx + 0.42, cy + 0.95, z - 0.6), "crystal", glow=13, uv="full"),
             box((cx - 0.24, cy + 0.95, z - 1.05), (cx + 0.24, cy + 1.35, z - 0.75), "crystal", glow=13, uv="full"),
             box((cx - 0.24, cy - 1.35, z - 1.05), (cx + 0.24, cy - 0.95, z - 0.75), "crystal", glow=13, uv="full")]
    turn(shard, 45, "y", (cx, cy, z - 0.9))
    return setting + shard


def snow_lumps() -> list[dict]:
    """A few lumps of settled snow on the crown."""
    parts = []
    for x, z, w, d, h in ((4.2, 6.0, 2.4, 2.0, 0.45), (10.4, 9.2, 2.0, 2.6, 0.4), (6.6, 12.2, 1.6, 1.4, 0.35)):
        parts.append(box((x - w / 2, CROWN + 1.1, z - d / 2), (x + w / 2, CROWN + 1.1 + h, z + d / 2), "fur",
                         offset=(int(x) % 12, int(z) % 12)))
    return parts


def build() -> list[dict]:
    return shell() + seam() + fur_ring() + forehead_sigil() + snow_lumps()


def models() -> dict:
    parts = build()
    return {"main": model(parts, display("helmet", parts, gui_rotation=(18, 155, 0)))}
