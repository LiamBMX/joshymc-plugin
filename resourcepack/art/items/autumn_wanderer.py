"""Autumn Wanderer - Woodland Limited Edition boots.

Rugged traveller's boots: worn saddle-brown leather on a chunky stacked sole, fold-down
cuffs with cream wool rolling out of the collar, red twine criss-cross laces through
bronze eyelets and speed hooks, glossy stitched toe caps, a bronze maple-leaf buckle on
each cuff, buffalo-plaid pull loops, and a tiny maple-leaf charm on the right boot.

Frame: the pair stands on y = 0 with the toes toward -Z; the right boot sits on +X.
Every big face is painted on its own patch of an atlas sheet at 2 texels per unit
(see Pn / pbox), so seams, stitching, scuffs and trail dust land exactly where they belong.
"""
from __future__ import annotations

import copy as _copy
import math
import random
import zlib

from art import kit
from art.kit import (HUMANOID, arc, art, bar, box, canvas, display, fill, mirror, mix, model, move, place,
                     ramp, region, rgba, save, save_layer, turn)

ID = "autumn_wanderer"
NAME = "Autumn Wanderer"
KIND = "boots"

# --------------------------------------------------------------------------------------
# Palettes (hue-shifted ramps, darkest first)
# --------------------------------------------------------------------------------------

LEATHER = ramp("#8e5a34", 7, 0.8)      # worn saddle-brown upper
CAP = ramp("#5e3522", 7, 0.8)          # darker polished leather: toe caps, heel cups, cuffs
TAN = ramp("#c39061", 6, 0.7)          # hickory: welt, thread, strap, scuffs
WALNUT = ramp("#5a3a26", 6, 0.75)      # stacked heel
SOLE = ramp("#3b2c26", 6, 0.6)         # lug sole
WOOL = ["#7a6450", "#a68e70", "#cdb795", "#e4d4b3", "#f2e7cf", "#fbf6e8"]
TWINE = ramp("#b8352a", 5, 0.7)        # red twine laces
BRONZE = ramp("#c08a45", 6, 0.85)      # buckle, eyelets, hooks
COPPER = ramp("#c0673a", 5, 0.8)
PLAID_R = ramp("#b0271f", 5, 0.7)
PLAID_K = ["#120a0b", "#231417", "#3a2226"]
GREEN = ramp("#3f6a3a", 5, 0.75)
LEAF = ramp("#e07a26", 5, 0.75)        # autumn-orange charm leaf
AMBER = ramp("#f0a030", 5, 0.7)
MUD = ["#5f4d3b", "#7d6a51", "#9a8667"]
BAYER = (0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

# --------------------------------------------------------------------------------------
# Painted-panel atlas: each face gets its own patch at DENSITY texels per unit
# --------------------------------------------------------------------------------------

DENSITY = 2
SHEETS = {"leather": (128, 64), "sole": (64, 64), "wool": (64, 32)}
EPS = 0.012


class Pn:
    """A face painted on its own patch of `sheet` by painter(img, x, y, w, h, key=..., **opts).
    du/dv offset the face inside a larger patch (units), so faces that share one patch
    (a slab's top split over several elements) line up."""

    def __init__(self, sheet, key, painter, flip_u=False, flip_v=False, du=0.0, dv=0.0, **opts):
        self.sheet, self.key, self.painter, self.opts = sheet, key, painter, opts
        self.flip_u, self.flip_v, self.du, self.dv = flip_u, flip_v, du, dv

    def at(self, du, dv):
        other = _copy.copy(self)
        other.du, other.dv = du, dv
        return other


_REG: dict = {"panels": {}, "refs": [], "layout": {}}


def _reset():
    _REG["panels"], _REG["refs"], _REG["layout"] = {}, [], {}


def _register(face, pn, w, h):
    key = (pn.sheet, pn.key)
    p = _REG["panels"].setdefault(key, {"w": 0.0, "h": 0.0, "painter": pn.painter, "opts": pn.opts})
    p["w"], p["h"] = max(p["w"], pn.du + w), max(p["h"], pn.dv + h)
    _REG["refs"].append((face, pn, w, h))


def _pack():
    by_sheet: dict = {}
    for (sheet, key), p in _REG["panels"].items():
        by_sheet.setdefault(sheet, []).append((key, p))
    for sheet, items in by_sheet.items():
        sw, sh = SHEETS[sheet]
        dims = {k: (math.ceil(p["w"] * DENSITY - 1e-6), math.ceil(p["h"] * DENSITY - 1e-6)) for k, p in items}
        items.sort(key=lambda kp: (-dims[kp[0]][1], -dims[kp[0]][0], kp[0]))
        x = y = row = 0
        for key, p in items:
            tw, th = dims[key]
            if x + tw > sw:
                x, y, row = 0, y + row + 1, 0
            if y + th > sh or tw > sw:
                raise ValueError(f"atlas sheet {sheet!r} is full at panel {key!r}")
            _REG["layout"][(sheet, key)] = (x, y, tw, th)
            x += tw + 1
            row = max(row, th)
    for face, pn, w, h in _REG["refs"]:
        px, py, _, _ = _REG["layout"][(pn.sheet, pn.key)]
        sw, sh = SHEETS[pn.sheet]
        ku, kv = 16 / sw, 16 / sh
        u0, v0 = (px + pn.du * DENSITY) * ku, (py + pn.dv * DENSITY) * kv
        u1, v1 = u0 + w * DENSITY * ku, v0 + h * DENSITY * kv
        if pn.flip_u:
            u0, u1 = u1, u0
        if pn.flip_v:
            v0, v1 = v1, v0
        face["uv"] = [round(v, 4) for v in (u0, v0, u1, v1)]
        face["texture"] = "#" + pn.sheet


def pbox(frm, to, spec, glow=0):
    """box() whose faces come from spec: side -> Pn (own painted patch), a texture name
    (uv 'true'), (name, (u0, v0)) for uv 'true' at an offset, or None to drop the face.
    '*' is the default for unlisted sides."""
    frm, to = [min(a, b) for a, b in zip(frm, to)], [max(a, b) for a, b in zip(frm, to)]
    sides = {s: spec.get(s, spec.get("*")) for s in kit.SIDES}
    skip = [s for s, v in sides.items() if v is None]
    e = box(frm, to, "twine", skip=skip, glow=glow)
    for side, face in e["faces"].items():
        s = sides[side]
        w, h = kit._face_size(side, frm, to)
        if isinstance(s, Pn):
            _register(face, s, w, h)
        elif isinstance(s, tuple):
            name, (u0, v0) = s
            face["texture"] = "#" + name
            face["uv"] = [u0, v0, round(min(16.0, u0 + max(w, 0.01)), 4), round(min(16.0, v0 + max(h, 0.01)), 4)]
        else:
            face["texture"] = "#" + s
    return e


def wall(p0, p1, y0, y1, t, spec, glow=0):
    """A vertical slab whose outer face runs from p0 to p1 ((x, z) points), t thick behind
    it. spec keys: out, in, top, bottom, ends. Rotations stay within +-90 degrees."""
    (x0, z0), (x1, z1) = p0, p1
    length = math.hypot(x1 - x0, z1 - z0)
    ux, uz = (x1 - x0) / length, (z1 - z0) / length
    nx, nz = -uz, ux
    outer, inner = "north", "south"
    if ux < -1e-9:
        ux, uz = -ux, -uz
        outer, inner = "south", "north"
    theta = math.degrees(math.atan2(-uz, ux))
    mx, mz = (x0 + x1) / 2 + nx * t / 2, (z0 + z1) / 2 + nz * t / 2
    s = {outer: spec.get("out"), inner: spec.get("in"), "up": spec.get("top"), "down": spec.get("bottom"),
         "east": spec.get("ends"), "west": spec.get("ends")}
    e = pbox((mx - length / 2, y0, mz - t / 2), (mx + length / 2, y1, mz + t / 2), s, glow)
    if abs(theta) > 1e-6:
        turn(e, theta, "y", (mx, (y0 + y1) / 2, mz))
    return e


def rslab(x0, x1, z0, z1, y0, y1, rf, rb, spec, glow=0):
    """A slab with 45-degree chamfered corners, rf at the front (-z) and rb at the back.
    spec keys: east, west (or side), front, back, top, bottom, cham (chamfer faces)."""
    def g(k, fallback=None):
        return spec.get(k, spec.get(fallback) if fallback else None)

    def at(k, du, dv):
        t = g(k)
        return t.at(du, dv) if isinstance(t, Pn) else t

    depth = z1 - z0
    parts = [pbox((x0, y0, z0 + rf), (x1, y1, z1 - rb),
                  {"east": g("east", "side"), "west": g("west", "side"),
                   "north": None if rf else g("front"), "south": None if rb else g("back"),
                   "up": at("top", 0, rf), "down": at("bottom", 0, rb)}, glow)]
    cham = {"out": g("cham", "side"), "top": at("top", 0, 0), "bottom": None}
    if rf:
        parts.append(pbox((x0 + rf, y0, z0), (x1 - rf, y1, z0 + rf),
                          {"north": g("front"), "up": at("top", rf, 0), "down": at("bottom", rf, depth - rf)}, glow))
        t = rf / math.sqrt(2) + 0.02
        parts.append(wall((x0, z0 + rf), (x0 + rf, z0), y0 + EPS, y1 - EPS, t, cham, glow))
        parts.append(wall((x1 - rf, z0), (x1, z0 + rf), y0 + EPS, y1 - EPS, t, cham, glow))
    if rb:
        parts.append(pbox((x0 + rb, y0, z1 - rb), (x1 - rb, y1, z1),
                          {"south": g("back"), "up": at("top", rb, depth - rb), "down": at("bottom", rb, 0)}, glow))
        t = rb / math.sqrt(2) + 0.02
        parts.append(wall((x1, z1 - rb), (x1 - rb, z1), y0 + EPS, y1 - EPS, t, cham, glow))
        parts.append(wall((x0 + rb, z1), (x0, z1 - rb), y0 + EPS, y1 - EPS, t, cham, glow))
    return parts


def slope(x0, x1, a, b, thick, spec, glow=0):
    """A slab across x0..x1 whose top face runs from a = (y, z) to b = (y, z), thick deep.
    Its "up" face texture runs v0 -> v1 from a to b."""
    (ya, za), (yb, zb) = a, b
    length = math.hypot(yb - ya, zb - za)
    vy, vz = (yb - ya) / length, (zb - za) / length
    ny, nz = vz, -vy
    my, mz = (ya + yb) / 2 - ny * thick / 2, (za + zb) / 2 - nz * thick / 2
    e = pbox((x0, my - thick / 2, mz - length / 2), (x1, my + thick / 2, mz + length / 2), spec, glow)
    turn(e, math.degrees(math.atan2(-vy, vz)), "x", ((x0 + x1) / 2, my, mz))
    return e


def on_slope(a, b, t, lift):
    """(y, z) of the point a fraction t along a slope surface, lifted along its normal."""
    (ya, za), (yb, zb) = a, b
    length = math.hypot(yb - ya, zb - za)
    ny, nz = (zb - za) / length, -(yb - ya) / length
    return ya + (yb - ya) * t + ny * lift, za + (zb - za) * t + nz * lift


def slope_angle(a, b):
    (ya, za), (yb, zb) = a, b
    return math.degrees(math.atan2(-(yb - ya), zb - za))


# --------------------------------------------------------------------------------------
# Pixel helpers
# --------------------------------------------------------------------------------------

def _rng(key: str) -> random.Random:
    return random.Random(zlib.crc32(key.encode()))


def put(img, x, y, c):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((int(x), int(y)), rgba(c))


def hline(img, x0, x1, y, c):
    for x in range(min(x0, x1), max(x0, x1) + 1):
        put(img, x, y, c)


def vline(img, x, y0, y1, c):
    for y in range(min(y0, y1), max(y0, y1) + 1):
        put(img, x, y, c)


def dith(img, x0, y0, x1, y1, c, ratio):
    """Ordered-dither colour c over the inclusive box at the given coverage."""
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if BAYER[(y % 4) * 4 + x % 4] < ratio * 16:
                put(img, x, y, c)


def clusters(img, x, y, w, h, colours, rng, count, shapes=((0, 0), (1, 0))):
    """A few deliberate 2-3 pixel clusters (patina, nap), never single-pixel noise."""
    for _ in range(count):
        cx, cy = x + rng.randrange(w), y + rng.randrange(h)
        c = rng.choice(colours)
        for dx, dy in shapes if rng.random() < 0.6 else ((0, 0), (1, 0), (1, 1)):
            if x <= cx + dx < x + w and y <= cy + dy < y + h:
                put(img, cx + dx, cy + dy, c)


def dust(img, x, y, w, rng, chance=0.45):
    """Dried trail dust along one row."""
    for xx in range(x, x + w):
        if rng.random() < chance:
            put(img, xx, y, mix(img.getpixel((xx, y)), rng.choice(MUD), 0.5))


def stitches_h(img, x0, x1, y, thread, step=2, phase=0):
    for x in range(x0, x1 + 1):
        if (x - x0 + phase) % step == 0:
            put(img, x, y, thread)


def stitches_v(img, x, y0, y1, thread, step=2, phase=0):
    for y in range(y0, y1 + 1):
        if (y - y0 + phase) % step == 0:
            put(img, x, y, thread)


# --------------------------------------------------------------------------------------
# Panel painters: painter(img, x, y, w, h, key=..., **opts); (x, y) = the patch's top-left
# --------------------------------------------------------------------------------------

def p_leather(img, x, y, w, h, key="p_leather", pal=LEATHER, base=3, rim=1, foot=1, spots=2, scuffs=1):
    """Plain worn leather: a lit rolled top edge, form shading into the bottom edge, a few
    patina clusters and scuffs."""
    rng = _rng(key)
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), pal[base])
    clusters(img, x, y, w, h, [pal[base - 1], pal[base + 1]], rng, spots)
    if foot and h >= 3:
        dith(img, x, y1 - 1, x1, y1 - 1, pal[base - 1], 0.5)
        hline(img, x, x1, y1, pal[base - 1])
    for i in range(rim):
        hline(img, x, x1, y + i, pal[base + 1])
        stitches_h(img, x, x1, y + i, pal[base + 2], step=5, phase=rng.randrange(5))
    for _ in range(scuffs):
        sx, sy = x + rng.randrange(max(1, w - 1)), y + rim + rng.randrange(max(1, h - rim - foot))
        hline(img, sx, min(x1, sx + 1), sy, TAN[4])


def p_foot_side(img, x, y, w, h, key="p_foot_side", toe="right"):
    """Side of the foot: rolled top edge, a dark polished heel cup behind a curved stitched
    seam, one flex crease behind the toe cap, a scuff, and trail dust along the welt."""
    rng = _rng(key)
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), LEATHER[3])
    hline(img, x, x1, y, LEATHER[4])
    stitches_h(img, x, x1, y, LEATHER[5], step=5, phase=2)
    dith(img, x, y1 - 1, x1, y1 - 1, LEATHER[2], 0.5)
    hline(img, x, x1, y1, LEATHER[2])

    def col(i):  # i counted from the heel end
        return x + i if toe == "right" else x1 - i

    # heel cup: the seam bows out toward the toe halfway down (a curve, not a diagonal)
    bow = [4, 5, 6, 6, 6, 5, 5, 4]
    for r in range(h):
        edge = bow[min(r, len(bow) - 1)]
        for i in range(edge):
            put(img, col(i), y + r, CAP[4] if r == 0 else (CAP[2] if r == h - 1 else CAP[3]))
        put(img, col(edge), y + r, CAP[1])
        if r % 2 == 1 and r < h - 1:
            put(img, col(edge + 1), y + r, TAN[4])
    put(img, col(1), y + 1, CAP[4])
    # one soft flex crease behind the toe cap
    c = w - 4
    put(img, col(c), y + 2, LEATHER[2])
    put(img, col(c + 1), y + 3, LEATHER[2])
    put(img, col(c - 1), y + 2, LEATHER[4])
    # a scuff on the flank and a soft sheen under the rolled edge
    sx = col(w // 2 + 1)
    put(img, sx, y + 2, TAN[3])
    put(img, sx + 1, y + 2, TAN[3])
    for i in range(9, w - 5):
        if BAYER[((y + 1) % 4) * 4 + (col(i) % 4)] < 7:
            put(img, col(i), y + 1, LEATHER[4])
    dust(img, x, y1, w, rng, 0.5)


def p_counter(img, x, y, w, h, key="p_counter"):
    rng = _rng(key)
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), CAP[3])
    hline(img, x, x1, y, CAP[4])
    stitches_h(img, x, x1, y + 1, TAN[4], phase=1)
    hline(img, x, x1, y1, CAP[2])
    dust(img, x, y1, w, rng, 0.45)


def p_heel_back(img, x, y, w, h, key="p_heel_back"):
    """Heel cup from behind: polished leather with an embossed pine maker's mark."""
    rng = _rng(key)
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), CAP[3])
    hline(img, x, x1, y, CAP[4])
    cx = x + w // 2
    pine = [(0, 1), (-1, 2), (0, 2), (1, 2), (-2, 3), (-1, 3), (0, 3), (1, 3), (2, 3)]
    for dx, dy in pine:
        if (dx + 1, dy + 1) not in pine:
            put(img, cx + dx + 1, y + dy + 1, CAP[1])
    for dx, dy in pine:
        put(img, cx + dx, y + dy, CAP[5] if dx <= 0 else CAP[4])
    put(img, cx, y + 4, CAP[2])
    hline(img, x, x1, y1, CAP[2])
    dust(img, x, y1, w, rng, 0.45)


def p_shaft_side(img, x, y, w, h, key="p_shaft_side", front="right", hidden=4):
    """Shaft side below the cuff: a crisp shadow under the cuff, a stitched back seam and a
    lit front edge where the leather rolls toward the laces."""
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), LEATHER[3])
    fill(img, (x, y, x1, y + hidden - 1), LEATHER[2])
    hline(img, x, x1, y + hidden, LEATHER[1])
    hline(img, x, x1, y + hidden + 1, LEATHER[2])

    def col(i):  # i counted from the back edge
        return x + i if front == "right" else x1 - i

    vline(img, col(1), y + hidden + 1, y1, LEATHER[2])
    stitches_v(img, col(2), y + hidden + 2, y1, TAN[4])
    vline(img, col(w - 1), y + hidden + 2, y1, LEATHER[4])
    # soft sheen where the shaft rounds toward the front
    for i in (w - 4, w - 3):
        for r in range(hidden + 2, h):
            if BAYER[((y + r) % 4) * 4 + (col(i) % 4)] < (6 if i == w - 4 else 10):
                put(img, col(i), y + r, LEATHER[4])


def p_shaft_plain(img, x, y, w, h, key="p_shaft_plain", hidden=4):
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), LEATHER[3])
    fill(img, (x, y, x1, y + hidden - 1), LEATHER[2])
    hline(img, x, x1, y + hidden, LEATHER[1])
    hline(img, x, x1, y + hidden + 1, LEATHER[2])
    put(img, x + w // 2, y + hidden + 4, LEATHER[2])
    put(img, x + w // 2, y + hidden + 3, LEATHER[4])


def p_shaft_back(img, x, y, w, h, key="p_shaft_back", hidden=4):
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), LEATHER[3])
    fill(img, (x, y, x1, y + hidden - 1), LEATHER[2])
    hline(img, x, x1, y + hidden, LEATHER[1])
    cx = x + w // 2
    vline(img, cx, y + hidden + 1, y1, LEATHER[4])
    stitches_v(img, cx - 1, y + hidden + 1, y1, TAN[4])
    stitches_v(img, cx + 1, y + hidden + 1, y1, TAN[4])
    vline(img, cx - 2, y + hidden + 1, y1, LEATHER[2])
    vline(img, cx + 2, y + hidden + 1, y1, LEATHER[2])


def p_cuff(img, x, y, w, h, key="p_cuff", fold=None):
    """Folded-down cuff: rich dark chocolate leather, a shadow under the wool, a lit rolled
    fold and a stitched hem."""
    rng = _rng(key)
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), CAP[3])
    hline(img, x, x1, y, CAP[1])
    hline(img, x, x1, y + 1, CAP[4])
    stitches_h(img, x, x1, y + 1, CAP[5], step=4, phase=rng.randrange(4))
    if h >= 5:
        stitches_h(img, x, x1, y1 - 1, TAN[4], phase=1)
    hline(img, x, x1, y1, CAP[2])
    clusters(img, x, y + 2, w, max(1, h - 4), [CAP[2]], rng, max(1, w // 6))
    if fold == "left":
        vline(img, x, y + 1, y1, CAP[1])
    elif fold == "right":
        vline(img, x1, y + 1, y1, CAP[1])


def p_edge(img, x, y, w, h, key="p_edge"):
    fill(img, (x, y, x + w - 1, y + h - 1), CAP[2])
    hline(img, x, x + w - 1, y, CAP[3])


def p_wool(img, x, y, w, h, key="p_wool", part="side"):
    """Cream shearling: a brick-laid field of 2x2 curls (lit crown, soft shadow) with
    irregular clumps, brighter along the top of the roll and darker underneath."""
    rng = _rng(key)
    x1, y1 = x + w - 1, y + h - 1
    curl = ((0, 0, 2), (1, 0, 1), (0, 1, 0), (1, 1, -1))   # (dx, dy, shade offset)
    base = 2 if part == "inner" else 3
    fill(img, (x, y, x1, y1), WOOL[base])
    for cy in range(y - 1, y1 + 1, 2):
        shift = rng.randrange(2)
        for cx in range(x - shift, x1 + 1, 2):
            lift = rng.choice((0, 0, 0, 1, -1))
            if part == "side":
                lift += 1 if cy <= y else (-1 if cy >= y1 - 1 and h > 2 else 0)
            for dx, dy, off in curl:
                px, py = cx + dx, cy + dy
                if x <= px <= x1 and y <= py <= y1:
                    put(img, px, py, WOOL[max(0, min(5, base + off + lift - (1 if part == "inner" else 0)))])
    if part == "side" and h > 2:
        for xx in range(x, x1 + 1):
            if rng.random() < 0.5:
                put(img, xx, y1, WOOL[1])


def p_opening(img, x, y, w, h, key="p_opening"):
    """Looking down into the boot: warm darkness ringed by the wool lining."""
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), "#24160f")
    for yy in range(y, y1 + 1):
        for xx in range(x, x1 + 1):
            edge = min(xx - x, x1 - xx, yy - y, y1 - yy)
            if edge == 0:
                put(img, xx, yy, WOOL[1])
            elif edge == 1:
                put(img, xx, yy, "#4a3526" if (xx + yy) % 2 else "#3a281c")
            elif edge >= 3:
                put(img, xx, yy, "#170d09")


def p_toe(img, x, y, w, h, key="p_toe", part="side", back=None, tone=0):
    """Glossy toe cap. The tiers are shaded as one dome: the lowest tier is darkest and the
    crown carries the specular streak. Stitched along its back edge."""
    rng = _rng(key)
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), CAP[3 + min(tone, 1)])
    if part == "top":
        if tone == 2:
            mid = y + h // 2
            hline(img, x + 1, x1 - 1, mid, CAP[5])
            hline(img, x + 2, x1 - 2, mid - 1 if mid > y else mid, CAP[4])
            gx = x + w // 3
            hline(img, gx, gx + 2, mid, CAP[6])
            put(img, gx + 1, mid - 1 if mid > y else mid, CAP[6])
    else:
        if tone == 0:
            hline(img, x, x1, y1, CAP[2])
            if h >= 3:
                dith(img, x, y1 - 1, x1, y1 - 1, CAP[2], 0.5)
            if part == "front":
                put(img, x + w // 2, y, TAN[4])
                put(img, x + w // 2 + 1, y, TAN[3])
            dust(img, x, y1, w, rng, 0.5)
        else:
            gx = x + rng.randrange(max(1, w - 1))
            put(img, gx, y, CAP[5])
            if tone == 2:
                put(img, gx + 1, y, CAP[6])
    if back == "left":
        vline(img, x, y, y1, CAP[2])
        stitches_v(img, x + 1, y, y1, TAN[5], phase=1)
    elif back == "right":
        vline(img, x1, y, y1, CAP[2])
        stitches_v(img, x1 - 1, y, y1, TAN[5], phase=1)
    elif back == "bottom":
        hline(img, x, x1, y1, CAP[2])
        stitches_h(img, x, x1, y1 - 1, TAN[5], phase=1)


def p_instep(img, x, y, w, h, key="p_instep", tongue=(3, 6)):
    """Top of the instep (toe end first): padded tongue between the eyestays and a stitched
    vamp seam across the toe end."""
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), LEATHER[3])
    t0, t1 = x + tongue[0], x + tongue[1]
    fill(img, (t0, y, t1, y1), LEATHER[4])
    for yy in range(y + 2, y1 + 1, 3):
        hline(img, t0 + 1, t1 - 1, yy, LEATHER[3])
        hline(img, t0 + 1, t1 - 1, yy - 1, LEATHER[5])
    vline(img, t0, y, y1, LEATHER[2])
    vline(img, t1, y, y1, LEATHER[2])
    hline(img, x, x1, y, LEATHER[2])
    stitches_h(img, x, x1, y + 1, TAN[4])
    for cy in (y + 3, y + 5):
        for cx in (x, x + 1, x1 - 1, x1):
            put(img, cx, cy, LEATHER[2])
            put(img, cx, cy - 1, LEATHER[4])


def p_eyestay(img, x, y, w, h, key="p_eyestay", inner="right"):
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), CAP[4])
    ie, oe = (x1, x) if inner == "right" else (x, x1)
    vline(img, ie, y, y1, CAP[5])
    vline(img, oe, y, y1, CAP[3])
    stitches_v(img, oe + (1 if inner == "right" else -1), y, y1, TAN[5])


def p_tongue(img, x, y, w, h, key="p_tongue", label=True):
    """Tongue top above the collar: padded leather with a little woodland label."""
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), LEATHER[4])
    hline(img, x, x1, y, LEATHER[5])
    vline(img, x, y + 1, y1, LEATHER[3])
    vline(img, x1, y + 1, y1, LEATHER[3])
    hline(img, x, x1, y1, LEATHER[3])
    if label and w >= 4 and h >= 5:
        lx, ly = x + (w - 2) // 2, y + 2
        fill(img, (lx, ly, lx + 1, ly + 1), WOOL[4])
        put(img, lx, ly, GREEN[3])
        put(img, lx + 1, ly + 1, GREEN[2])
        hline(img, lx, lx + 1, ly + 2, LEATHER[3])


def p_welt_side(img, x, y, w, h, key="p_welt_side"):
    """Hickory welt with dark lock-stitches over a walnut midsole line."""
    hline(img, x, x + w - 1, y, TAN[3])
    stitches_h(img, x, x + w - 1, y, WALNUT[2], step=3)
    for r in range(1, h):
        hline(img, x, x + w - 1, y + r, WALNUT[2] if r < h - 1 else WALNUT[1])


def p_welt_top(img, x, y, w, h, key="p_welt_top"):
    fill(img, (x, y, x + w - 1, y + h - 1), TAN[2])
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            if min(xx - x, x + w - 1 - xx, yy - y, y + h - 1 - yy) == 0:
                put(img, xx, yy, TAN[3] if (xx + yy) % 3 else TAN[4])


def p_outsole(img, x, y, w, h, key="p_outsole"):
    """Lug outsole edge: chunky rubber lugs split by deep notches."""
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), SOLE[2])
    hline(img, x, x1, y, SOLE[3])
    hline(img, x, x1, y1, SOLE[1])
    for xx in range(x, x1 + 1):
        if (xx - x) % 3 == 2:
            vline(img, xx, y + 1, y1, SOLE[0])


def p_heel(img, x, y, w, h, key="p_heel", nails=False):
    """Stacked leather heel: walnut lifts with pale edges over a rubber top lift."""
    x1 = x + w - 1
    rows = [WALNUT[4], WALNUT[3], SOLE[1]] if h <= 3 else [WALNUT[4], WALNUT[3], WALNUT[2], SOLE[1]]
    for r in range(h):
        hline(img, x, x1, y + r, rows[min(r, len(rows) - 1)])
    stitches_h(img, x, x1, y + 1, WALNUT[2], step=4, phase=2)
    if nails:
        for xx in range(x + 1, x1, 2):
            put(img, xx, y + h - 1, BRONZE[3])


def p_tread(img, x, y, w, h, key="p_tread"):
    fill(img, (x, y, x + w - 1, y + h - 1), SOLE[1])
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            if (xx - x) % 3 != 2 and (yy - y) % 3 != 2:
                put(img, xx, yy, SOLE[2])


def p_strap(img, x, y, w, h, key="p_strap"):
    """Hickory strap across the dark cuff: lit top edge, stitched centre, shadowed bottom."""
    x1, y1 = x + w - 1, y + h - 1
    fill(img, (x, y, x1, y1), TAN[3])
    hline(img, x, x1, y, TAN[4])
    hline(img, x, x1, y1, TAN[1])
    if h >= 2:
        stitches_h(img, x, x1, y + 1 if h > 2 else y, TAN[5])


def p_strap_edge(img, x, y, w, h, key="p_strap_edge"):
    fill(img, (x, y, x + w - 1, y + h - 1), TAN[2])


# --------------------------------------------------------------------------------------
# Small textures (not atlas panels)
# --------------------------------------------------------------------------------------

MAPLE_ROWS = [
    "......a......",
    ".....aba.....",
    "....abbba....",
    ".a..abbba..a.",
    ".aa.abbba.aa.",
    ".abaabbbaaba.",
    "aabbbbbbbbbaa",
    ".abbbbbbbbba.",
    "..abbbbbbba..",
    ".aabbbbbbbaa.",
    ".abbaacaabba.",
    "..aa..c..aa..",
    "......c......",
]
LEAF_PX = 13


def leaf_image(outline, body, light, dark, stem, vein=None):
    """A 13x13 maple leaf: five lobes and a stem, lit from the top-left, with veins."""
    img = art(MAPLE_ROWS, {"a": outline, "b": body, "c": stem})
    px = img.load()
    for y in range(LEAF_PX):
        for x in range(LEAF_PX):
            if px[x, y][3] and px[x, y][:3] == rgba(body)[:3]:
                if x + y < 10:
                    px[x, y] = rgba(light)
                elif x + y > 15:
                    px[x, y] = rgba(dark)
    if vein:
        veins = [(6, y) for y in range(3, 10)] + [(5, 6), (4, 5), (3, 4), (7, 6), (8, 5), (9, 4), (5, 8), (4, 9),
                                                  (7, 8), (8, 9)]
        for x, y in veins:
            if px[x, y][3] and px[x, y][:3] != rgba(outline)[:3]:
                px[x, y] = rgba(vein)
    return img


def paint_small() -> None:
    # red twine: a twisted cord at 2 texels per unit
    tw = canvas(32)
    for y in range(32):
        for x in range(32):
            tw.putpixel((x, y), rgba([TWINE[3], TWINE[2], TWINE[2], TWINE[1]][(y + x // 2) % 4]))
    save(tw, "twine")

    # bronze: bright worked metal (the tiny hooks and eyelets sample its lit corner)
    br = canvas(32, fill=BRONZE[3])
    fill(br, (0, 0, 1, 1), BRONZE[5])
    fill(br, (2, 0, 3, 1), BRONZE[4])
    fill(br, (0, 2, 3, 3), BRONZE[2])
    for y in range(4, 32):
        for x in range(32):
            if (x + y) % 4 == 0:
                br.putpixel((x, y), rgba(BRONZE[4]))
    save(br, "bronze")

    # maple-leaf buckle: polished bronze leaf with a dark rim, veins and two glints
    buckle = canvas(16)
    leaf = leaf_image(BRONZE[1], BRONZE[3], BRONZE[5], BRONZE[2], BRONZE[1], vein=BRONZE[2])
    buckle.paste(leaf, (0, 0), leaf)
    buckle.putpixel((5, 3), rgba("#fff4d6"))
    buckle.putpixel((2, 6), rgba("#fff4d6"))
    save(buckle, "buckle")

    # charm: an autumn-orange enamel maple leaf in a copper setting
    charm = canvas(16)
    leaf = leaf_image(COPPER[1], LEAF[2], LEAF[4], LEAF[1], COPPER[2], vein=LEAF[1])
    charm.paste(leaf, (0, 0), leaf)
    charm.putpixel((5, 3), rgba("#fff0d0"))
    save(charm, "charm")

    # buffalo plaid pull loop (a strap with a hole near the top)
    plaid = canvas(16)
    for y in range(12):
        for x in range(6):
            red, dark_row = (x // 2) % 2 == 0, (y // 2) % 2 == 0
            c = (PLAID_R[1] if dark_row else PLAID_R[2]) if red else (PLAID_K[0] if dark_row else PLAID_K[2])
            plaid.putpixel((x, y), rgba(c))
    for y in range(1, 5):
        for x in range(2, 4):
            plaid.putpixel((x, y), (0, 0, 0, 0))
    save(plaid, "plaid")

    amber = canvas(16, fill=AMBER[3])
    fill(amber, (0, 0, 0, 0), AMBER[4])
    fill(amber, (1, 0, 1, 1), AMBER[2])
    save(amber, "amber")


# --------------------------------------------------------------------------------------
# Geometry: the right boot (x around XC); the left boot is its mirror image
# --------------------------------------------------------------------------------------

XC = 11.5
FX0, FX1 = 8.9, 14.1          # foot upper
SX0, SX1 = 9.1, 13.9          # shaft
Y_SOLE, Y_WELT = 1.5, 2.5     # outsole top, welt top (upper starts here)
Y_FOOT = 5.4                  # foot block top
SZ0, SZ1 = 8.3, 14.0          # shaft front / back
Y_SHAFT = 11.75               # shaft top (floor of the opening)
CUFF = (9.0, 11.5)
WOOLY = (10.9, 12.3, 12.85)   # wool roll: bottom, main top, crown top
INSTEP_A, INSTEP_B = (4.35, 4.7), (7.5, 8.6)   # (y, z) instep surface, toe end -> shin
T0, T1 = 10.55, 12.45         # tongue between the eyestays
EYE_X = (10.15, 12.85)
SLOPE_T = (0.16, 0.46, 0.76)
HOOK_Y = (8.25, 9.4, 10.55)
STAY = 0.28
LACE = 0.36


def sole_parts():
    parts = []
    side = Pn("sole", "outsole", p_outsole)
    tread = Pn("sole", "tread", p_tread)
    parts += rslab(8.65, 14.35, 1.3, 8.9, 0.0, Y_SOLE, 1.35, 0,
                   {"side": side, "front": side, "cham": side, "back": Pn("sole", "arch_f", p_outsole),
                    "top": tread, "bottom": tread})
    heel = Pn("sole", "heel", p_heel)
    parts += rslab(8.75, 14.25, 10.2, 14.35, 0.0, Y_SOLE, 0, 0.95,
                   {"side": heel, "front": Pn("sole", "heel_f", p_heel), "back": Pn("sole", "heel_b", p_heel, nails=True),
                    "cham": heel, "top": tread, "bottom": Pn("sole", "tread_h", p_tread)})
    welt = Pn("sole", "welt", p_welt_side)
    parts += rslab(8.5, 14.5, 1.05, 14.5, Y_SOLE, Y_WELT, 1.45, 1.05,
                   {"side": welt, "front": welt, "back": welt, "cham": welt,
                    "top": Pn("sole", "welttop", p_welt_top), "bottom": Pn("sole", "weltbot", p_tread)})
    return parts


def foot_parts():
    parts = []
    # foot block with a rounded heel cup
    parts += rslab(FX0, FX1, 4.4, 14.15, Y_WELT, Y_FOOT, 0, 0.85,
                   {"east": Pn("leather", "foot_out", p_foot_side, toe="right"),
                    "west": Pn("leather", "foot_in", p_foot_side, toe="left"),
                    "back": Pn("leather", "heel_back", p_heel_back),
                    "cham": Pn("leather", "counter", p_counter),
                    "top": Pn("leather", "foot_top", p_counter)})
    # toe cap: three stacked, set-back tiers round it into a glossy dome
    tiers = [(8.8, 14.2, 1.3, 3.55, 1.5), (8.95, 14.05, 1.75, 4.1, 1.35), (9.25, 13.75, 2.45, 4.55, 1.05)]
    y0 = Y_WELT
    for i, (x0, x1, z0, y1, r) in enumerate(tiers):
        spec = {"east": Pn("leather", f"cap{i}_o", p_toe, back="left", tone=i),
                "west": Pn("leather", f"cap{i}_i", p_toe, back="right", tone=i),
                "front": Pn("leather", f"cap{i}_f", p_toe, part="front", tone=i),
                "cham": Pn("leather", f"cap{i}_f", p_toe, part="front", tone=i),
                "top": Pn("leather", f"cap{i}_t", p_toe, part="top", tone=i, back="bottom" if i == 2 else None)}
        parts += rslab(x0, x1, z0, 5.3, y0, y1, r, 0, spec)
        y0 = y1
    # instep rising from the toe cap to the shin
    parts.append(slope(9.0, 14.0, INSTEP_A, INSTEP_B, 3.0,
                       {"up": Pn("leather", "instep", p_instep, tongue=(3, 6)),
                        "east": Pn("leather", "instep_o", p_leather, foot=0, scuffs=0, spots=0),
                        "west": Pn("leather", "instep_i", p_leather, foot=0, scuffs=0, spots=0),
                        "north": None, "south": None, "down": None}))
    return parts


def shaft_parts():
    parts = []
    hidden = int((Y_SHAFT - CUFF[0]) * DENSITY)
    parts += rslab(SX0, SX1, SZ0, SZ1, 5.2, Y_SHAFT, 0, 0.75,
                   {"east": Pn("leather", "shaft_out", p_shaft_side, front="right", hidden=hidden),
                    "west": Pn("leather", "shaft_in", p_shaft_side, front="left", hidden=hidden),
                    "front": Pn("leather", "tongue_low", p_instep, tongue=(3, 6)),
                    "back": Pn("leather", "shaft_back", p_shaft_back, hidden=hidden),
                    "cham": Pn("leather", "shaft_cham", p_shaft_plain, hidden=hidden),
                    "top": Pn("leather", "opening", p_opening)})
    parts += cuff_parts()
    parts += wool_parts()
    # tongue rising above the collar, leaning forward, with a padded top roll
    tongue = pbox((T0, 10.6, SZ0 - 0.5), (T1, 13.4, SZ0 + 0.1),
                  {"north": Pn("leather", "tongue_f", p_tongue), "south": Pn("leather", "tongue_b", p_leather),
                   "east": Pn("leather", "tongue_s", p_leather, rim=0), "west": Pn("leather", "tongue_s", p_leather, rim=0),
                   "up": Pn("leather", "tongue_t", p_leather, rim=0, foot=0), "down": None})
    pad = pbox((T0 - 0.12, 13.0, SZ0 - 0.68), (T1 + 0.12, 13.65, SZ0 + 0.18),
               {"*": Pn("leather", "tongue_pad", p_leather, pal=CAP, base=4), "down": None})
    parts += turn([tongue, pad], -10, "x", (XC, 10.6, SZ0))
    return parts


def corner_walls(o, t, y0, y1, spec, c=0.75, y1_left=None):
    """The two rounded back corners of a ring hugging the shaft, pushed out by o: the
    shaft's 45-degree chamfer (size c) offset along its own normal, so each corner wall
    meets the straight walls exactly."""
    k = math.sqrt(2) - 1
    right = wall((SX1 + o, SZ1 - c + o * k), (SX1 - c + o * k, SZ1 + o), y0, y1, t, spec)
    left = wall((SX0 + c - o * k, SZ1 + o), (SX0 - o, SZ1 - c + o * k), y0,
                y1 if y1_left is None else y1_left, t, spec)
    return [right, left]


def cuff_parts():
    """The folded cuff: side walls, back, rounded back corners and short front returns."""
    parts = []
    t = 0.38
    y0, y1 = CUFF
    k = math.sqrt(2) - 1
    edge = Pn("leather", "cuff_edge", p_edge)
    zb = SZ1 - 0.75 + t * k
    parts.append(pbox((SX1, y0, SZ0 - t), (SX1 + t, y1, zb),
                      {"east": Pn("leather", "cuff_out", p_cuff), "north": edge, "up": edge, "down": edge}))
    parts.append(pbox((SX0 - t, y0, SZ0 - t), (SX0, y1, zb),
                      {"west": Pn("leather", "cuff_in", p_cuff), "north": edge, "up": edge, "down": edge}))
    parts.append(pbox((SX0 + 0.75 - t * k, y0, SZ1), (SX1 - 0.75 + t * k, y1, SZ1 + t),
                      {"south": Pn("leather", "cuff_back", p_cuff), "up": edge, "down": edge}))
    cham = {"out": Pn("leather", "cuff_cham", p_cuff), "top": edge, "bottom": edge}
    parts += corner_walls(t, 0.6, y0 + EPS, y1 - EPS, cham)
    # front returns wrap the corners and stop short of the laces
    parts.append(pbox((SX1 - 0.5, y0, SZ0 - t), (SX1, y1, SZ0),
                      {"north": Pn("leather", "cuff_ret_o", p_cuff, fold="left"), "west": edge, "up": edge,
                       "down": edge}))
    parts.append(pbox((SX0, y0, SZ0 - t), (SX0 + 0.5, y1, SZ0),
                      {"north": Pn("leather", "cuff_ret_i", p_cuff, fold="right"), "east": edge, "up": edge,
                       "down": edge}))
    return parts


def wool_parts():
    """A fat roll of wool lining folded over the collar: a main ring bulging past the
    cuff, an inset crown of uneven height on top, and front tufts folding to the laces."""
    parts = []
    y0, y1, y2 = WOOLY
    wside = Pn("wool", "wool_side", p_wool)
    wtop = Pn("wool", "wool_top", p_wool, part="top")
    win = Pn("wool", "wool_in", p_wool, part="inner")
    k = math.sqrt(2) - 1
    # (bulge past the shaft, radial thickness, bottom, top per segment: out, in, back, corners)
    tiers = ((0.6, 1.05, y0, (y1, y1, y1, y1, y1), "a"),
             (0.33, 1.15, y1 - 0.02, (y2 + 0.05, y2 - 0.12, y2 + 0.1, y2 - 0.05, y2), "b"))
    for o, t, ya, tops, skin in tiers:
        side = Pn("wool", f"wool_side_{skin}", p_wool)
        zb = SZ1 - 0.75 + o * k
        xb0, xb1 = SX0 + 0.75 - o * k, SX1 - 0.75 + o * k
        parts.append(pbox((SX1 + o - t, ya, SZ0 - 0.1), (SX1 + o, tops[0], zb),
                          {"east": side, "west": win, "up": wtop, "north": wside, "down": None}))
        parts.append(pbox((SX0 - o, ya, SZ0 - 0.1), (SX0 - o + t, tops[1], zb),
                          {"west": side, "east": win, "up": wtop, "north": wside, "down": None}))
        parts.append(pbox((xb0, ya, SZ1 + o - t), (xb1, tops[2], SZ1 + o),
                          {"south": side, "north": win, "up": wtop, "down": None}))
        cs = {"out": side, "top": wtop, "in": win}
        parts += corner_walls(o, t, ya + EPS, tops[3] - EPS, cs, y1_left=tops[4] - EPS)
    # front tufts folding round to the lacing
    wfront = Pn("wool", "wool_front", p_wool)
    parts.append(pbox((SX1 - 0.95, y0 + 0.1, SZ0 - 0.7), (SX1 + 0.68, y1 + 0.2, SZ0 + 0.4),
                      {"north": wfront, "west": wfront, "up": wtop, "east": wside, "down": None}))
    parts.append(pbox((SX0 - 0.68, y0 + 0.1, SZ0 - 0.7), (SX0 + 0.95, y1 + 0.1, SZ0 + 0.4),
                      {"north": wfront, "east": wfront, "up": wtop, "west": wside, "down": None}))
    return parts


def lacing_parts():
    parts = []
    ang = slope_angle(INSTEP_A, INSTEP_B)
    # eyestays on the instep and up the shin
    for i, (x0, x1) in enumerate(((9.05, T0), (T1, 13.95))):
        inner = "right" if i == 0 else "left"
        pn = Pn("leather", f"stay_slope_{i}", p_eyestay, inner=inner)
        a = on_slope(INSTEP_A, INSTEP_B, 0.0, STAY)
        b = on_slope(INSTEP_A, INSTEP_B, 0.97, STAY)
        parts.append(slope(x0, x1, a, b, STAY + 0.4, {"up": pn, "east": pn, "west": pn, "north": pn,
                                                       "south": None, "down": None}))
        pf = Pn("leather", f"stay_front_{i}", p_eyestay, inner=inner)
        sx0, sx1 = (SX0 + 0.05, T0) if i == 0 else (T1, SX1 - 0.05)
        parts.append(pbox((sx0, 6.9, SZ0 - STAY), (sx1, 11.2, SZ0),
                          {"north": pf, "east": pf, "west": pf, "up": pf, "south": None, "down": None}))
    anchors = []
    # eyelets on the slope
    for t in SLOPE_T:
        row = []
        for ex in EYE_X:
            cy, cz = on_slope(INSTEP_A, INSTEP_B, t, STAY + 0.08)
            e = box((ex - 0.34, cy - 0.12, cz - 0.34), (ex + 0.34, cy + 0.12, cz + 0.34), "bronze")
            turn(e, ang, "x", (ex, cy, cz))
            parts.append(e)
            ly, lz = on_slope(INSTEP_A, INSTEP_B, t, STAY + 0.3)
            row.append((ex, ly, lz))
        anchors.append(row)
    # speed hooks up the shin
    for y in HOOK_Y:
        row = []
        for ex in EYE_X:
            parts.append(box((ex - 0.3, y - 0.3, SZ0 - STAY - 0.12), (ex + 0.3, y + 0.3, SZ0 - STAY), "bronze"))
            parts.append(box((ex - 0.14, y - 0.02, SZ0 - STAY - 0.6), (ex + 0.14, y + 0.44, SZ0 - STAY - 0.1),
                             "bronze"))
            row.append((ex, y, SZ0 - STAY - 0.4))
        anchors.append(row)
    # laces: straight across the bottom, then criss-cross with one strand riding over
    (l0, r0) = anchors[0]
    parts.append(bar((l0[0] - 0.1, l0[1], l0[2]), (r0[0] + 0.1, r0[1], r0[2]), LACE, LACE, "twine"))
    ny, nz = on_slope(INSTEP_A, INSTEP_B, 0.0, 1.0)
    ny, nz = ny - INSTEP_A[0], nz - INSTEP_A[1]
    for i in range(len(anchors) - 1):
        (la, ra), (lb, rb) = anchors[i], anchors[i + 1]
        lift = 0.13
        dy, dz = (ny, nz) if i < len(SLOPE_T) - 1 else (0.0, -1.0)
        parts.append(bar(la, rb, LACE, LACE, "twine"))
        parts.append(bar((ra[0], ra[1] + dy * lift, ra[2] + dz * lift), (lb[0], lb[1] + dy * lift, lb[2] + dz * lift),
                         LACE, LACE, "twine"))
    return parts, anchors[-1]


def bow_parts(top):
    """The knot, two loops and two tails at the top of the laces."""
    (lx, ly, lz), (rx, ry, rz) = top
    k = (XC, 11.5, SZ0 - 0.82)
    w = 0.34
    parts = [bar((lx, ly, lz), k, w, w, "twine"), bar((rx, ry, rz), k, w, w, "twine")]
    parts.append(box((k[0] - 0.4, k[1] - 0.3, k[2] - 0.28), (k[0] + 0.4, k[1] + 0.3, k[2] + 0.28), "twine"))
    loop_l = arc((k[0] - 0.8, k[1] + 0.12, k[2] - 0.05), 0.52, 30, 330, 5, w, w, "twine")
    loop_r = arc((k[0] + 0.8, k[1] + 0.12, k[2] - 0.05), 0.52, -150, 150, 5, w, w, "twine")
    turn(loop_l, 22, "z", k)
    turn(loop_r, -22, "z", k)
    turn(loop_l + loop_r, -25, "x", k)
    parts += loop_l + loop_r
    ends = ((k[0] - 0.8, k[1] - 1.8, k[2] - 0.25), (k[0] + 0.5, k[1] - 2.05, k[2] - 0.3))
    for end in ends:
        parts.append(bar(k, end, w, w, "twine"))
        parts.append(box((end[0] - 0.19, end[1] - 0.4, end[2] - 0.19), (end[0] + 0.19, end[1] + 0.05, end[2] + 0.19),
                         "bronze"))
    return parts


def buckle_parts():
    """A hickory strap across the outer cuff, fastened by a bronze maple-leaf buckle."""
    x = SX1 + 0.38
    strap = Pn("leather", "strap", p_strap)
    se = Pn("leather", "strap_e", p_strap_edge)
    parts = [pbox((x, 9.65, 8.95), (x + 0.2, 10.75, 13.35), {"east": strap, "up": se, "down": se, "north": se,
                                                            "south": se, "west": None})]
    size = 2.6
    zc, yc = 10.85, 10.2
    plate = box((x + 0.2, yc - size / 2, zc - size / 2), (x + 0.44, yc + size / 2, zc + size / 2), "buckle",
                faces={"east": ("buckle", [0, 0, 13, 13]), "west": ("buckle", [13, 0, 0, 13])},
                skip=("up", "down", "north", "south"))
    parts.append(plate)
    return parts


def pull_loop():
    """A buffalo-plaid pull loop standing up at the back of the collar."""
    z = SZ1 + 0.62
    loop = box((XC - 0.75, 11.2, z), (XC + 0.75, 14.2, z + 0.22), "plaid",
               faces={"south": ("plaid", [0, 0, 6, 12]), "north": ("plaid", [6, 0, 0, 12])},
               skip=("up", "down", "east", "west"))
    return [turn(loop, -8, "x", (XC, 11.2, z))]


def charm_parts():
    """A tiny maple-leaf charm hanging from the outer top speed hook of the right boot."""
    hx, hy, hz = EYE_X[1], HOOK_Y[-1], SZ0 - STAY - 0.5
    ring = box((hx - 0.16, hy - 0.5, hz - 0.16), (hx + 0.16, hy - 0.1, hz + 0.16), "bronze")
    cord_end = (hx + 0.75, hy - 1.3, hz - 0.2)
    cord = bar((hx, hy - 0.35, hz), cord_end, 0.2, 0.2, "twine")
    bead = box((cord_end[0] - 0.25, cord_end[1] - 0.3, cord_end[2] - 0.25),
               (cord_end[0] + 0.25, cord_end[1] + 0.2, cord_end[2] + 0.25), "amber", glow=12)
    size = 2.2
    cx, cy, cz = cord_end[0] + 0.15, cord_end[1] - 0.2 - size / 2, cord_end[2]
    leaf = box((cx - size / 2, cy - size / 2, cz - 0.1), (cx + size / 2, cy + size / 2, cz + 0.1), "charm",
               faces={"north": ("charm", [0, 0, 13, 13]), "south": ("charm", [13, 0, 0, 13])},
               skip=("up", "down", "east", "west"), glow=6)
    turn(leaf, -40, "y", (cx, cy, cz))
    turn(leaf, 10, "z", (cx, cy + size / 2, cz))
    return [ring, cord, bead, leaf]


def right_boot():
    lace, top = lacing_parts()
    return sole_parts() + foot_parts() + shaft_parts() + lace + bow_parts(top) + buckle_parts() + pull_loop()


_CACHE: dict = {}


def build() -> list[dict]:
    if "parts" not in _CACHE:
        _reset()
        right = right_boot()
        charm = charm_parts()
        _pack()
        left = mirror(right, "x", 8.0)
        turn(left, 7, "y", (4.5, 0, 8))
        move(left, 0, 0, -1.0)
        turn(right + charm, -5, "y", (11.5, 0, 8))
        _CACHE["parts"] = right + charm + left
    return _CACHE["parts"]


# --------------------------------------------------------------------------------------
# Worn layer (humanoid, 4x: 256 x 128)
# --------------------------------------------------------------------------------------

S = 4
SMALL_MAPLE = [
    ".....#.....",
    "....###....",
    ".#..###..#.",
    ".##.###.##.",
    "..#######..",
    "###########",
    ".#########.",
    "..#######..",
    "...##.##...",
    ".....#.....",
    ".....#.....",
]


def lit_silhouette(rows, pal):
    """A filled pixel silhouette lit from the top-left (pal = 6-step ramp) with a one-pixel
    drop shadow, returned as {(x, y): colour}."""
    h, w = len(rows), len(rows[0])
    inside = {(x, y) for y in range(h) for x in range(w) if rows[y][x] == "#"}
    out = {}
    for (x, y) in inside:
        if (x + 1, y + 1) not in inside:
            out[(x + 1, y + 1)] = pal[1]
    for (x, y) in inside:
        tl = (x - 1, y) not in inside or (x, y - 1) not in inside
        br = (x + 1, y) not in inside or (x, y + 1) not in inside
        if tl and not br:
            c = pal[5]
        elif br and not tl:
            c = pal[2]
        elif x + y < (w + h) / 2 - 2:
            c = pal[4]
        else:
            c = pal[3]
        out[(x, y)] = c
    return out


def worn_layer():
    """The boots on the player (humanoid layer at 4x). Each leg face is 16 x 48 texels;
    the boot fills rows 20-47: wool roll, dark cuff, laced upper, welt and lug sole.
    Rows 45-47 sit just below the ground, so the sole is painted from row 42."""
    img = canvas(64 * S, 32 * S)
    rng = random.Random(41)
    faces = {n: region(HUMANOID, n, S) for n in ("leg_outer", "leg_front", "leg_inner", "leg_back", "leg_sole")}

    def P(face, x, y, c):
        x0, y0, _, _ = faces[face]
        if 0 <= x < 16 and 0 <= y < 48:
            put(img, x0 + x, y0 + y, c)

    def get(face, x, y):
        x0, y0, _, _ = faces[face]
        return img.getpixel((x0 + x, y0 + y))

    sides = ("leg_outer", "leg_front", "leg_inner", "leg_back")
    # ---- shared bands on all four faces ----------------------------------------------
    for f in sides:
        # wool roll: brick-laid curls, a scalloped top edge
        for y in range(22, 26):
            for x in range(16):
                P(f, x, y, WOOL[3])
        for cy in range(21, 26, 2):
            shift = rng.randrange(2)
            for cx in range(-shift, 16, 2):
                lift = rng.choice((0, 0, 1, -1)) + (1 if cy <= 22 else (-1 if cy >= 24 else 0))
                for dx, dy, off in ((0, 0, 2), (1, 0, 1), (0, 1, 0), (1, 1, -1)):
                    if 22 <= cy + dy <= 25:
                        P(f, cx + dx, cy + dy, WOOL[max(0, min(5, 3 + off + lift))])
        for x in range(16):
            P(f, x, 25, WOOL[1] if rng.random() < 0.6 else WOOL[2])
        x = 0
        while x < 16:  # scallops of fleece on top
            wdt = rng.choice((2, 3, 3, 4))
            hgt = rng.choice((1, 2, 2))
            for i in range(wdt):
                for j in range(hgt):
                    if not (j == hgt - 1 and i in (0, wdt - 1) and wdt > 2):
                        P(f, x + i, 21 - j, WOOL[4] if j == hgt - 1 else WOOL[3])
            P(f, x + 1, 21 - hgt + 1, WOOL[5])
            x += wdt + rng.choice((0, 1))
        # cuff
        for y in range(26, 32):
            for x in range(16):
                P(f, x, y, CAP[3])
        for x in range(16):
            P(f, x, 26, CAP[1])
            P(f, x, 27, CAP[5] if x % 4 == 1 else CAP[4])
            P(f, x, 30, TAN[4] if x % 2 else CAP[3])
            P(f, x, 31, CAP[2])
        for _ in range(4):
            P(f, rng.randrange(16), rng.randrange(28, 30), CAP[2])
        # upper leather with a shadow under the cuff
        for y in range(32, 39):
            for x in range(16):
                P(f, x, y, LEATHER[3])
        for x in range(16):
            P(f, x, 32, LEATHER[1])
            P(f, x, 33, LEATHER[2])
            P(f, x, 37, LEATHER[2] if (x + 37) % 2 else LEATHER[3])
            P(f, x, 38, LEATHER[2])
            if rng.random() < 0.45:
                P(f, x, 38, mix(get(f, x, 38), rng.choice(MUD), 0.5))
        # welt, midsole and lug sole
        for x in range(16):
            P(f, x, 39, WALNUT[2] if x % 3 == 0 else TAN[3])
            P(f, x, 40, TAN[2])
            P(f, x, 41, WALNUT[1])
            P(f, x, 42, SOLE[3])
            for y in range(43, 48):
                P(f, x, y, SOLE[2] if y < 45 else SOLE[1])
            if x % 3 == 2:
                for y in range(43, 46):
                    P(f, x, y, SOLE[0])

    def heel_stack(f, xs):
        for x in xs:
            for y, c in zip(range(42, 48), (WALNUT[4], WALNUT[3], WALNUT[2], WALNUT[3], SOLE[1], SOLE[1])):
                P(f, x, y, c)
            if x % 4 == 1:
                P(f, x, 43, WALNUT[2])

    def heel_cup(f, back_x, step):
        bow = {33: 4, 34: 5, 35: 6, 36: 6, 37: 5, 38: 4}
        for y, wdt in bow.items():
            for i in range(wdt):
                P(f, back_x + step * i, y, CAP[4] if y == 33 else (CAP[2] if y == 38 else CAP[3]))
            P(f, back_x + step * wdt, y, CAP[1])
            if y % 2 == 0:
                P(f, back_x + step * (wdt + 1), y, TAN[4])

    # ---- front: tongue, lacing, bow, toe cap -----------------------------------------
    f = "leg_front"

    def line(x0, y0, x1, y1):
        n = max(abs(x1 - x0), abs(y1 - y0))
        return [(round(x0 + (x1 - x0) * i / n), round(y0 + (y1 - y0) * i / n)) for i in range(n + 1)]

    for y in range(18, 36):  # tongue between the eyestays
        for x in range(4, 12):
            P(f, x, y, LEATHER[4] if 5 <= x <= 10 else LEATHER[3])
    for y in range(20, 36, 3):
        for x in range(5, 11):
            P(f, x, y, LEATHER[3])
    for x in range(5, 11):  # padded tongue top above the collar, rounded corners
        P(f, x, 16, CAP[3])
    for x in range(4, 12):
        P(f, x, 17, CAP[4])
    P(f, 4, 17, CAP[3])
    P(f, 11, 17, CAP[3])
    for x in range(6, 10):  # woven tag: cream over forest green
        P(f, x, 18, WOOL[5] if x == 6 else WOOL[4])
        P(f, x, 19, GREEN[2] if x in (7, 8) else GREEN[1])
    for y in range(26, 36):  # eyestays with stitched outer edges
        P(f, 2, y, TAN[4] if y % 2 else CAP[2])
        P(f, 3, y, CAP[4])
        P(f, 12, y, CAP[4])
        P(f, 13, y, TAN[4] if y % 2 else CAP[2])
    eyes = (26, 30, 34)
    for i in range(len(eyes) - 1):  # criss-cross: under-strand first, over-strand on top
        r0, r1 = eyes[i], eyes[i + 1]
        for x, y in line(12, r0, 3, r1):
            P(f, x, y, TWINE[1])
        for x, y in line(3, r0, 12, r1):
            P(f, x, y, TWINE[3])
        P(f, 7, (r0 + r1) // 2, TWINE[4])
        P(f, 8, (r0 + r1) // 2, TWINE[4])
    for x in range(3, 13):
        P(f, x, 34, TWINE[2])
        P(f, x, 35, TWINE[1] if x % 2 else LEATHER[3])
    for r in eyes:
        for x in (3, 12):
            P(f, x, r, BRONZE[5])
            P(f, x, r + 1, BRONZE[2])
    bow = {(4, 21): 3, (5, 20): 4, (6, 20): 3, (3, 22): 3, (4, 23): 2, (5, 23): 1, (6, 22): 2,
           (11, 21): 3, (10, 20): 3, (9, 20): 3, (12, 22): 2, (11, 23): 1, (10, 23): 1, (9, 22): 2,
           (7, 21): 3, (8, 21): 2, (7, 22): 2, (8, 22): 1,
           (6, 24): 2, (5, 25): 2, (5, 26): 1, (9, 24): 2, (10, 25): 2, (10, 26): 1}
    for (x, y), t in bow.items():
        P(f, x, y, TWINE[t])
    P(f, 5, 27, BRONZE[4])
    P(f, 10, 27, BRONZE[4])
    # toe cap with a stitched rounded seam and a polish glint
    for x in range(16):
        top = 36 if 2 <= x <= 13 else (37 if x in (1, 14) else 38)
        for y in range(top, 39):
            P(f, x, y, CAP[3])
        P(f, x, top, CAP[1])
        if x % 2 == 0 and top < 38:
            P(f, x, top + 1, TAN[4])
    for x in range(5, 11):
        P(f, x, 38, CAP[4])
    P(f, 6, 38, CAP[5])
    P(f, 7, 38, CAP[6])

    # ---- outer side: strap and maple-leaf buckle, heel cup, stacked heel -------------
    f = "leg_outer"
    for x in range(1, 16):
        P(f, x, 28, TAN[4])
        P(f, x, 29, TAN[3])
        P(f, x, 30, TAN[1])
    for (x, y), c in lit_silhouette(SMALL_MAPLE, BRONZE).items():
        P(f, 3 + x, 23 + y, c)
    P(f, 8, 24, "#fff4d6")
    heel_cup(f, 0, 1)
    P(f, 11, 35, TAN[3])
    P(f, 12, 35, TAN[3])
    heel_stack(f, range(0, 6))
    for y in range(42, 46):
        P(f, 6, y, SOLE[0])
        P(f, 7, y, SOLE[0])

    # ---- inner side: strap with a copper rivet, heel cup, stacked heel ---------------
    f = "leg_inner"
    for x in range(0, 15):
        P(f, x, 28, TAN[4])
        P(f, x, 29, TAN[3])
        P(f, x, 30, TAN[1])
    P(f, 11, 29, COPPER[3])
    P(f, 11, 28, COPPER[4])
    heel_cup(f, 15, -1)
    heel_stack(f, range(10, 16))
    for y in range(42, 46):
        P(f, 8, y, SOLE[0])
        P(f, 9, y, SOLE[0])

    # ---- back: plaid pull loop, backstay, heel cup, stacked heel ---------------------
    f = "leg_back"
    for y in range(17, 29):
        for x in range(6, 10):
            if 18 <= y <= 20 and 7 <= x <= 8:
                continue
            if y == 17 and x in (6, 9):
                continue
            red, dark = (x // 2) % 2 == 1, ((y - 17) // 2) % 2 == 0
            P(f, x, y, (PLAID_R[1] if dark else PLAID_R[2]) if red else (PLAID_K[0] if dark else PLAID_K[2]))
    for y in range(33, 39):
        for x in range(16):
            P(f, x, y, CAP[4] if y == 33 else (CAP[2] if y == 38 else CAP[3]))
    for y in range(32, 39):
        P(f, 7, y, CAP[1])
        P(f, 8, y, CAP[1])
        if y % 2:
            P(f, 6, y, TAN[4])
            P(f, 9, y, TAN[4])
    heel_stack(f, range(0, 16))
    for x in range(1, 16, 3):
        P(f, x, 45, BRONZE[3])

    # ---- sole: lug tread with a separate heel ----------------------------------------
    x0, y0, x1, y1 = faces["leg_sole"]
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            lug = (x - x0) % 4 != 0 and (y - y0) % 4 != 0
            put(img, x, y, SOLE[2] if lug else SOLE[0])
    for x in range(x0, x1 + 1):
        put(img, x, y0 + 10, SOLE[0])
        put(img, x, y0 + 11, SOLE[0])
        for y in range(y0 + 12, y1 + 1):
            put(img, x, y, WALNUT[2] if (y - y0) % 2 else WALNUT[3])
    return img


# --------------------------------------------------------------------------------------
# Module contract
# --------------------------------------------------------------------------------------

def textures() -> None:
    build()
    for sheet, (sw, sh) in SHEETS.items():
        img = canvas(sw, sh)
        for (s, key), (px, py, tw, th) in _REG["layout"].items():
            if s == sheet:
                p = _REG["panels"][(s, key)]
                p["painter"](img, px, py, tw, th, key=f"{sheet}:{key}", **p["opts"])
        save(img, sheet)
    paint_small()
    save_layer(worn_layer(), "humanoid")


def displays(parts) -> dict:
    d = display("boots", parts, gui_rotation=(24, 218, 0), gui_span=15.5)
    # carried by the plaid pull loops, the pair hanging from the fist, toes forward
    grip = (8.0, 13.6, 14.4)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, grip, "fist", 0.52)
    # first person: three-quarter view of the toes and the right boot's buckled side
    d["firstperson_righthand"] = place({"y": (0.0, 0.95, 0.3), "z": (0.87, 0.0, -0.5)}, (8, 7, 8),
                                       (0.46, -0.27, -0.95), 0.58, pose=None)
    # item frames and shelves look at the model's -Z side: show the toes three-quarter on
    d["fixed"] = kit.fit(parts, (-18, 35, 0), 14.0)
    d["on_shelf"] = kit.fit(parts, (-12, 35, 0), 12.0)
    return d


def models() -> dict:
    parts = _copy.deepcopy(build())
    return {"main": model(parts, displays(parts))}
