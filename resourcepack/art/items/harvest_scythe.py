"""Harvest Scythe (September Exclusive).

A grand reaper's scythe: a tall polished dark-oak snath with crimson-and-gold wrapped
grips, a side peg and a gilded pommel; a sweeping burnished-bronze crescent blade with a
polished gold spine, a raised gold wheat-ear relief and a glowing honey-amber edge; a
gilded collar, amber medallion and jewelled finial where blade meets shaft, dressed with a
bound sheaf of golden wheat, a crimson cord and a corn-husk ribbon.
"""
from __future__ import annotations

import math
import random

import numpy as np
from PIL import ImageDraw

from art.kit import (bar, box, canvas, display, fill, grain, model, place, prism, ramp, rgba, rotation_of, save,
                     speckle, turn)

ID = "harvest_scythe"
NAME = "Harvest Scythe"
KIND = "hoe"

OAK = ramp("#5a3620", 6, 0.8)        # polished dark oak
LEATHER = ramp("#a8231c", 6, 0.8)    # crimson maple leather
CORD = ramp("#b3261c", 6, 0.8)       # crimson tie cord
GOLD = ramp("#f0b52e", 6, 0.8)       # harvest gold
BRONZE = ramp("#c06a24", 6, 0.8)     # burnished bronze
AMBER = ramp("#f5a51e", 6, 0.8)      # honey amber (glows)
WHEAT = ramp("#eeb238", 6, 0.8)      # ripe wheat
HUSK = ramp("#ebcf8c", 6, 0.7)       # corn husk

# --------------------------------------------------------------------------------------
# Blade curves (upright frame: snath along +Y at x = z = 8, blade sweeping toward -X)
# --------------------------------------------------------------------------------------
SPINE = ((7.2, 26.8), (-0.5, 32.6), (-15.6, 31.4), (-14.4, 14.2))   # outer back of the blade
EDGE = ((6.0, 21.4), (-2.0, 25.2), (-12.4, 27.2), (-14.4, 14.2))    # inner cutting edge
BZ = 8.0                     # blade mid-plane
PLATE = 0.4                  # half thickness of the blade plate
PLATE_BOX = (-15.5, 13.5, 8.0, 30.5)   # x0, y0, x1, y1 of the painted plate (units)
TPU = 2                      # texels per unit on the plate (64 px texture: 4 texels per uv unit)
GRIP = (8.0, -7.8, 8.0)      # the lower grip, where the fist closes


def _lerp_after(t, start, end, a, b):
    if t <= start:
        return a
    return a + (b - a) * min(1.0, (t - start) / (end - start))


def spine_w(t):
    return _lerp_after(t, 0.5, 0.96, 1.5, 0.55)


def spine_d(t):
    return _lerp_after(t, 0.45, 0.96, 1.7, 0.9)


def edge_w(t):
    return _lerp_after(t, 0.6, 1.0, 1.1, 0.35)


def edge_d(t):
    return _lerp_after(t, 0.6, 1.0, 0.9, 0.55)


def _bez(c, t):
    (x0, y0), (x1, y1), (x2, y2), (x3, y3) = c
    u = 1 - t
    a, b, cc, d = u * u * u, 3 * u * u * t, 3 * u * t * t, t * t * t
    return a * x0 + b * x1 + cc * x2 + d * x3, a * y0 + b * y1 + cc * y2 + d * y3


def _table(xs, ys):
    return np.concatenate([[0.0], np.cumsum(np.hypot(np.diff(xs), np.diff(ys)))])


TS = np.linspace(0.0, 1.0, 901)


def _curve(c):
    x, y = _bez(c, TS)
    return x, y, _table(x, y)


def _stalk_curve():
    """The centre line of the wheat relief: the middle of the bronze field."""
    ax, ay, _ = _curve(SPINE)
    bx, by, _ = _curve(EDGE)
    w = np.hypot(ax - bx, ay - by)
    top = np.array([spine_w(t) for t in TS]) - 0.2 + 0.5
    lam = np.clip((top + (w - 1.4)) / 2 / np.maximum(w, 1e-3), 0, 1)
    x, y = ax + (bx - ax) * lam, ay + (by - ay) * lam
    return x, y, _table(x, y)


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def _blade_field():
    """(t, lam, miss) for every plate texel: t runs heel -> tip, lam 0 = spine, 1 = edge,
    miss = distance to the nearest sample (large when the texel is off the blade)."""
    x0, y0, x1, y1 = PLATE_BOX
    w, h = int((x1 - x0) * TPU), int((y1 - y0) * TPU)
    tt, ll = np.meshgrid(np.linspace(0.0, 1.0, 330), np.linspace(-0.3, 1.3, 120))
    ax, ay = _bez(SPINE, tt)
    bx, by = _bez(EDGE, tt)
    sx = ((1 - ll) * ax + ll * bx).ravel()
    sy = ((1 - ll) * ay + ll * by).ravel()
    tt, ll = tt.ravel(), ll.ravel()
    cx = x0 + (np.arange(w) + 0.5) / TPU
    cy = y1 - (np.arange(h) + 0.5) / TPU
    T, L, D = (np.zeros((h, w)) for _ in range(3))
    for j in range(h):
        d2 = (cx[:, None] - sx[None, :]) ** 2 + (cy[j] - sy[None, :]) ** 2
        k = np.argmin(d2, axis=1)
        T[j], L[j], D[j] = tt[k], ll[k], np.sqrt(d2[np.arange(w), k])
    return T, L, D


def _plate_ij(x, y):
    x0, y0, x1, y1 = PLATE_BOX
    return (x - x0) * TPU - 0.5, (y1 - y) * TPU - 0.5


def _paint_blade(icon: bool = False):
    """The crescent plate, cut out: a dark groove under the spine, a burnished bronze field
    lit along its upper curve, a dark line where the bevel starts and a honey-amber bevel.
    icon=True paints a calm version for the inventory model (no fine lines or dither,
    which only turn to speckle at slot size)."""
    img = canvas(64)
    px = img.load()
    T, L, D = _blade_field()
    h, w = T.shape
    ax, ay = _bez(SPINE, T)
    bx, by = _bez(EDGE, T)
    width = np.hypot(ax - bx, ay - by)
    da, db = L * width, (1 - L) * width                 # units below the spine / above the edge
    inside = (L >= 0.0) & (L <= 1.0) & (D < 0.3) & (width > 0.9)
    rng = random.Random(7)
    for j in range(h):
        for i in range(w):
            if not inside[j, i]:
                continue
            a, b, t = da[j, i], db[j, i], T[j, i]
            cover = spine_w(t) - 0.2
            field = width[j, i] - cover - 0.5 - 1.9
            if icon:
                c = AMBER[4] if b < 0.9 else AMBER[3] if b < 1.9 else BRONZE[4] if a < cover + 1.2 \
                    else BRONZE[3]
                px[i, j] = rgba(c)
                continue
            if a < cover + 0.5:
                c = BRONZE[1]                              # hidden, then the groove under the spine
            elif b < 0.45:
                c = AMBER[4]                               # hidden under the glowing edge
            elif b < 0.95:
                c = AMBER[3]
            elif b < 1.45:
                c = AMBER[2]                               # honed bevel
            elif b < 1.9:
                c = BRONZE[1]                              # line where the bevel starts
            else:
                # burnished field: lit along its upper curve, darkening toward the bevel,
                # with checker-dithered steps between the bands
                f = (a - cover - 0.5) / max(0.5, field)
                checker = (i + j) % 2 == 0
                if f < 0.16:
                    c = BRONZE[4]
                elif f < 0.3:
                    c = BRONZE[4] if checker else BRONZE[3]
                elif f < 0.55:
                    c = BRONZE[3]
                elif f < 0.68:
                    c = BRONZE[3] if checker else BRONZE[2]
                else:
                    c = BRONZE[2]
                if c == BRONZE[4] and rng.random() < 0.08:
                    c = BRONZE[5]                          # burnish glints
            px[i, j] = rgba(c)
    # Tang: the blade's heel runs on into the collar.
    ax0, ay0 = _bez(SPINE, 0.0)
    bx0, by0 = _bez(EDGE, 0.0)
    ImageDraw.Draw(img).polygon([_plate_ij(ax0, ay0), _plate_ij(8.2, ay0), _plate_ij(8.2, by0),
                                 _plate_ij(bx0, by0)], fill=rgba(BRONZE[2]))
    return img


def _t_spine():
    """Polished gold rib: bright rolled outer edge, darker toward the blade, and a raised
    bead every 4 units."""
    img = canvas(32)
    cols = [GOLD[5], GOLD[4], GOLD[4], GOLD[3], GOLD[3], GOLD[2]]
    for x in range(32):
        fill(img, (x, 0, x, 31), cols[min(len(cols) - 1, x)])
    for y in range(0, 32, 8):
        fill(img, (0, y, 31, y), GOLD[5])
        fill(img, (0, y + 1, 31, y + 1), GOLD[3])
    return img


def _t_edge():
    """The honed edge: honey on the blade side, pale glowing honey at the cutting line."""
    img = canvas(32)
    px = img.load()
    cols = [AMBER[4], AMBER[5], "#ffe7a3", "#ffe7a3"]
    for y in range(32):
        for x in range(32):
            px[x, y] = rgba(cols[x % 4])
    for y in range(0, 32, 5):
        px[2, y] = rgba("#fff8e0")
        px[1, (y + 2) % 32] = rgba("#ffe7a3")
    return img


def _t_kernel():
    """Gold for the raised wheat relief: lit crown, shaded base."""
    img = canvas(32)
    _rows(img, [GOLD[5], GOLD[4], GOLD[4], GOLD[3], GOLD[2]])
    fill(img, (0, 0, 0, 31), GOLD[5])
    return img


def _t_oak():
    img = canvas(32, fill=OAK[2])
    grain(img, (0, 0, 31, 31), [OAK[1]], axis="y", seed=11, min_len=4, max_len=12, density=0.45)
    grain(img, (0, 0, 31, 31), [OAK[3]], axis="y", seed=13, min_len=3, max_len=8, density=0.18)
    return img


def _t_wrap():
    """Crimson leather strap with a gold cord; bands repeat every 8 texels (one turn of
    the double helix that _helix() lays around the grip)."""
    img = canvas(32)
    rows = [GOLD[3], LEATHER[4], LEATHER[3], LEATHER[2], LEATHER[1], LEATHER[3], LEATHER[2], LEATHER[0]]
    for y in range(32):
        fill(img, (0, y, 31, y), rows[y % 8])
    for y in range(0, 32, 8):
        for x in range(0, 32, 2):
            img.putpixel((x, y), rgba(GOLD[4]))
    return img


def _t_ring():
    """Gold band for 1-unit rings: bright rolled top edge, darker lower edge."""
    img = canvas(32, fill=GOLD[3])
    fill(img, (0, 0, 31, 0), GOLD[4])
    fill(img, (0, 1, 31, 1), GOLD[2])
    for x in range(0, 32, 3):
        img.putpixel((x, 0), rgba(GOLD[5]))
    return img


def _rows(img, colours):
    """One texel row per colour from the top; the last colour fills the rest."""
    for y in range(img.height):
        fill(img, (0, y, img.width - 1, y), colours[min(len(colours) - 1, y)])


def _t_knob():
    """Vertical gold gradient for 2-unit knobs and bulbs."""
    img = canvas(32)
    _rows(img, [GOLD[5], GOLD[4], GOLD[3], GOLD[2], GOLD[1]])
    for x in range(1, 32, 4):
        img.putpixel((x, 1), rgba("#fff0b8"))
    return img


def _t_collar():
    """Engraved collar band (4 units = 8 texels): gold lips, bronze field, kernel chevrons."""
    img = canvas(32, fill=BRONZE[2])
    px = img.load()
    for x in range(32):
        px[x, 0] = rgba(GOLD[4])
        px[x, 1] = rgba(BRONZE[1])
        px[x, 6] = rgba(BRONZE[1])
        px[x, 7] = rgba(GOLD[3])
        for y in (2, 3, 4, 5):
            px[x, y] = rgba(BRONZE[3] if y < 4 else BRONZE[2])
    for x in range(0, 32, 3):
        px[x, 2] = rgba(GOLD[4])
        px[x, 3] = rgba(GOLD[3])
        px[(x + 1) % 32, 4] = rgba(GOLD[4])
        px[(x + 1) % 32, 5] = rgba(GOLD[2])
    for x in range(32):
        for y in range(8, 32):
            px[x, y] = px[x, y % 8]
    return img


def _t_bronze():
    """Dark burnished bronze with a gilded lip."""
    img = canvas(32, fill=BRONZE[2])
    fill(img, (0, 0, 31, 0), GOLD[4])
    fill(img, (0, 1, 31, 1), BRONZE[3])
    speckle(img, (0, 2, 31, 31), [BRONZE[1], BRONZE[3]], density=0.08, seed=5)
    return img


def _t_flat(colour):
    return canvas(16, fill=colour)


def _pixels(img, rows, palette):
    """Paint rows of palette keys into the top-left corner of img."""
    for y, row in enumerate(rows):
        for x, key in enumerate(row):
            img.putpixel((x, y), rgba(palette[key]))


GEM_KEYS = {"0": AMBER[1], "1": AMBER[2], "2": AMBER[3], "3": AMBER[4], "4": AMBER[5], "w": "#fff6d8"}


def _t_gem():
    """Amber cabochon at 2 texels per unit (a 2 x 2 unit face): hot glint up-left,
    darker rounded rim."""
    img = canvas(32, fill=AMBER[2])
    _pixels(img, ["1221",
                  "2w32",
                  "2331",
                  "1110"], GEM_KEYS)
    return img


def _t_facet():
    """A cut amber facet (2 x 2 unit face): the top-left corner becomes the crystal's top
    vertex once the box is turned 45 degrees, so the light sits there."""
    img = canvas(32, fill=AMBER[2])
    _pixels(img, ["w432",
                  "4321",
                  "3211",
                  "2110"], GEM_KEYS)
    return img


def _t_rim():
    """Beaded gold rim for the medallion's edge."""
    img = canvas(32, fill=GOLD[3])
    px = img.load()
    for x in range(32):
        px[x, 0] = rgba(GOLD[2])
        px[x, 3] = rgba(GOLD[2])
        px[x, 1] = rgba(GOLD[5] if x % 2 == 0 else GOLD[3])
        px[x, 2] = rgba(GOLD[4] if x % 2 == 0 else GOLD[2])
    return img


def _t_boss():
    """The diamond boss under the medallion gem: rolled gold border around bronze."""
    img = canvas(32, fill=BRONZE[3])
    for i in range(6):
        img.putpixel((i, 0), rgba(GOLD[5]))
        img.putpixel((0, i), rgba(GOLD[4]))
        img.putpixel((i, 5), rgba(GOLD[2]))
        img.putpixel((5, i), rgba(GOLD[2]))
    for i in (1, 4):
        img.putpixel((i, i), rgba(GOLD[4]))
    return img


EAR_W, EAR_H = 4.0, 7.0      # units covered by the ear sprite plane (8 x 14 texels)


def _t_ear():
    """Cut-out wheat ear for the crossed planes: a braid of plump kernels, each with a
    bristly awn, the upper awns streaming past the tip. Row 0 is the top."""
    img = canvas(32)
    px = img.load()
    draw = ImageDraw.Draw(img)
    awn, tip = rgba(WHEAT[3]), rgba(WHEAT[4])
    for x0, y0, x1, y1 in ((3, 4, 1, 0), (4, 4, 6, 0), (3, 3, 3, 0), (2, 6, 0, 2), (5, 6, 7, 2),
                           (1, 9, 0, 6), (6, 9, 7, 6)):
        draw.line((x0, y0, x1, y1), fill=awn)
        px[x1, y1] = tip
    cell_l = ((WHEAT[5], WHEAT[4]), (WHEAT[3], WHEAT[2]))
    cell_r = ((WHEAT[4], WHEAT[3]), (WHEAT[2], WHEAT[1]))
    for y in range(5, 12, 2):                       # left kernels, cols 2-3
        for dy in (0, 1):
            for dx in (0, 1):
                px[2 + dx, y + dy] = rgba(cell_l[dy][dx])
        px[1, y + 1] = rgba(WHEAT[3])               # kernel tip poking out
    for y in range(6, 13, 2):                       # right kernels, cols 4-5
        for dy in (0, 1):
            for dx in (0, 1):
                px[4 + dx, y + dy] = rgba(cell_r[dy][dx])
        px[6, y + 1] = rgba(WHEAT[2])
    px[3, 4], px[4, 4] = rgba(WHEAT[5]), rgba(WHEAT[4])   # crowning kernel
    px[4, 5] = rgba(WHEAT[3])
    px[3, 13], px[4, 13] = rgba(WHEAT[2]), rgba(WHEAT[1])  # base where the stalk joins
    return img


def _t_ear_core():
    img = canvas(32)
    for y in range(32):
        for x in range(32):
            img.putpixel((x, y), rgba([WHEAT[3], WHEAT[2]][(x + y) % 2]))
    return img


def _t_stalk():
    """Straw stalk: a lit edge and a knotted node every 5 units."""
    img = canvas(32, fill=WHEAT[2])
    fill(img, (0, 0, 0, 31), WHEAT[3])
    for y in (9, 19, 29):
        fill(img, (0, y, 31, y), WHEAT[1])
    return img


def _t_straw():
    """The bound stalk ends below the tie: vertical straws."""
    img = canvas(32)
    px = img.load()
    cols = [WHEAT[4], WHEAT[3], WHEAT[2], WHEAT[3], WHEAT[5], WHEAT[3]]
    for y in range(32):
        for x in range(32):
            px[x, y] = rgba(cols[x % 6])
    return img


def _t_husk(vertical=True):
    img = canvas(32, fill=HUSK[3])
    grain(img, (0, 0, 31, 31), [HUSK[2], HUSK[4], HUSK[4]], axis="y" if vertical else "x", seed=21,
          min_len=3, max_len=9, density=0.5)
    return img


def _t_bow():
    """Ribbon loop: husk with a shaded fold across its middle."""
    img = _t_husk(False)
    fill(img, (0, 0, 31, 0), HUSK[5])
    for x in range(1, 32, 4):
        fill(img, (x, 1, x, 31), HUSK[2])
    return img


def _t_cord():
    """Twisted crimson cord (0.5 units tall: a single texel row of twists)."""
    img = canvas(32, fill=CORD[2])
    px = img.load()
    for y in range(32):
        for x in range(32):
            px[x, y] = rgba([CORD[4], CORD[3], CORD[2], CORD[1]][(x + y) % 4])
    return img


def textures() -> None:
    save(_paint_blade(), "blade")
    save(_paint_blade(icon=True), "blade_icon")
    save(_t_spine(), "spine")
    save(_t_edge(), "edge")
    save(_t_kernel(), "kernel")
    save(_t_oak(), "oak")
    save(_t_wrap(), "wrap")
    save(_t_ring(), "ring")
    save(_t_knob(), "knob")
    save(_t_collar(), "collar")
    save(_t_bronze(), "bronze")
    save(_t_flat(GOLD[3]), "gold_flat")
    save(_t_flat(GOLD[1]), "gold_dark")
    save(_t_flat(WHEAT[2]), "straw_end")
    save(_t_flat(LEATHER[2]), "leather_flat")
    save(_t_flat(HUSK[2]), "husk_flat")
    save(_t_flat(CORD[2]), "cord_flat")
    save(_t_gem(), "gem")
    save(_t_facet(), "facet")
    save(_t_rim(), "rim")
    save(_t_boss(), "boss")
    save(_t_ear(), "ear")
    save(_t_ear_core(), "ear_core")
    save(_t_stalk(), "stalk")
    save(_t_straw(), "straw")
    save(_t_husk(True), "husk")
    save(_t_husk(False), "husk_band")
    save(_t_bow(), "bow")
    save(_t_cord(), "cord")


# --------------------------------------------------------------------------------------
# Geometry helpers
# --------------------------------------------------------------------------------------

def _open(parts, sides=("up", "down")):
    """Drop hidden cap faces (they would also z-fight where prism slabs overlap)."""
    for e in parts:
        for s in sides:
            if len(e["faces"]) > 1:
                e["faces"].pop(s, None)
    return parts


def _caps(parts, up=None, down=None):
    for e in parts:
        if up and "up" in e["faces"]:
            e["faces"]["up"]["texture"] = "#" + up
        if down and "down" in e["faces"]:
            e["faces"]["down"]["texture"] = "#" + down
    return parts


def _band(y0, y1, r, tex, top="gold_flat", bottom="gold_dark"):
    """An octagonal band wider than its neighbours, closed top and bottom. The caps use
    flat colours, so the prism slabs' overlapping caps cannot visibly z-fight."""
    return _caps(_oct(y0, y1, r, tex, cap=top), up=top, down=bottom)


def _helix(parts, period=4.0, sides=8):
    """Step each side face's v by its compass direction so horizontal bands spiral."""
    normals = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0)}
    for e in parts:
        m, _ = rotation_of(e)
        for side, face in e["faces"].items():
            if side not in normals:
                continue
            n = normals[side]
            wx = m[0][0] * n[0] + m[0][2] * n[2]
            wz = m[2][0] * n[0] + m[2][2] * n[2]
            step = round((math.degrees(math.atan2(wz, wx)) % 360) / (360 / sides)) % sides
            dv = step * period / sides
            u0, v0, u1, v1 = face["uv"]
            face["uv"] = [u0, round(v0 + dv, 4), u1, round(v1 + dv, 4)]
    return parts


def _vary(parts, seed):
    """Shift each side face to a different patch of the texture (varied wood grain)."""
    rng = random.Random(seed)
    for e in parts:
        for side, face in e["faces"].items():
            if side in ("up", "down"):
                continue
            u0, v0, u1, v1 = face["uv"]
            du = rng.randrange(0, int((16 - max(u0, u1)) * 2) + 1) / 2
            dv = rng.randrange(0, int((16 - max(v0, v1)) * 2) + 1) / 2
            face["uv"] = [u0 + du, v0 + dv, u1 + du, v1 + dv]
    return parts


def _flip_north(parts):
    """Mirror the north faces' u so both sides of a chain shade the same way."""
    for e in parts:
        f = e["faces"].get("north")
        if f:
            u0, v0, u1, v1 = f["uv"]
            f["uv"] = [u1, v0, u0, v1]
    return parts


def _oct(y0, y1, r, tex, cap=None):
    return prism((8, (y0 + y1) / 2, 8), r, y1 - y0, tex, sides=8, cap=cap)


def _chain(c, t0, t1, n, width, depth, inset, tex, glow=0, period=0.0, stretch=1.12):
    """n bars following Bezier c from t0 to t1 in equal arc lengths. width/depth/inset are
    functions of t at each bar's middle; inset moves the bar toward the concave side."""
    xs, ys, ls = _curve(c)
    l0, l1 = float(np.interp(t0, TS, ls)), float(np.interp(t1, TS, ls))
    parts = []
    for k in range(n):
        a, b = l0 + (l1 - l0) * k / n, l0 + (l1 - l0) * (k + 1) / n
        ta, tb, tm = (float(np.interp(v, ls, TS)) for v in (a, b, (a + b) / 2))
        pa, pb = np.array(_bez(c, ta)), np.array(_bez(c, tb))
        d = (pb - pa) / np.linalg.norm(pb - pa)
        left = np.array([-d[1], d[0]])
        mid = (pa + pb) / 2 + left * inset(tm)
        half = (pb - pa) / 2 * stretch
        tip, heel = mid + half, mid - half
        v = round(((a % period) if period else 0.0) * 2) / 2
        dep = depth(tm) + (0.05 if k % 2 else 0.0)
        parts.append(bar((tip[0], tip[1], BZ), (heel[0], heel[1], BZ), width(tm), dep, tex, offset=(0, v),
                         glow=glow))
    return _flip_north(parts)


def _along(p0, p1, r, length_at, length, tex, cap=None):
    """An octagonal prism on the segment p0 -> p1 (in the XY plane), centred at the
    fraction length_at, `length` long."""
    d = np.array(p1, dtype=float) - np.array(p0, dtype=float)
    centre = np.array(p0, dtype=float) + d * length_at
    ang = math.degrees(math.atan2(-d[0], d[1]))
    parts = prism(tuple(centre), r, length, tex, sides=8, cap=cap)
    return turn(parts, ang, "z", tuple(centre))


# --------------------------------------------------------------------------------------
# Parts
# --------------------------------------------------------------------------------------

def _pommel(top):
    """Gilded knob with a bottom button, its top at y = top."""
    parts = _caps(_oct(top - 2.6, top - 1.9, 1.15, "gold_flat", cap="gold_dark"), up="gold_dark")
    parts += _caps(_oct(top - 1.9, top, 1.8, "knob", cap="gold_flat"), down="gold_dark")
    return parts


def snath(compact: bool = False) -> list[dict]:
    """The dark-oak snath. compact=True is the inventory icon's shortened version: the
    pommel sits right under the upper grip so the blade can fill more of the slot."""
    parts = []
    if compact:
        parts += _pommel(-4.9)
        parts += _band(-4.9, -3.9, 1.6, "ring")
        parts += _helix(_open(_oct(-3.9, 5.5, 1.45, "wrap")))
    else:
        parts += _pommel(-13.4)
        parts += _band(-13.4, -12.4, 1.6, "ring")
        # lower grip: crimson leather + gold cord, spiral wrapped
        parts += _helix(_open(_oct(-12.4, -3.2, 1.45, "wrap")))
        parts += _band(-3.2, -2.2, 1.6, "ring")
        parts += _vary(_open(_oct(-2.2, 5.5, 1.207, "oak")), 3)
    # upper grip
    parts += _band(5.5, 6.5, 1.6, "ring")
    parts += _helix(_open(_oct(6.5, 10.5, 1.45, "wrap")))
    parts += _band(10.5, 11.5, 1.6, "ring")
    # side peg: engraved clamp, oak peg with a wrapped hand-hold and a faceted gold knob
    parts += _band(11.5, 13.3, 1.6, "collar")
    p0, p1 = (8.0, 12.4, 8.0), (2.6, 14.6, 8.0)
    parts.append(bar(p0, p1, 1.3, 1.3, "oak", offset=(3, 2)))
    parts += _caps(_along(p0, p1, 0.85, 0.58, 2.4, "wrap", cap="leather_flat"))
    parts += _caps(_along(p0, p1, 1.0, 1.0, 1.2, "knob", cap="gold_flat"), down="gold_dark")
    # upper shaft
    parts += _vary(_open(_oct(13.3, 21.0, 1.207, "oak")), 9)
    return parts


def head() -> list[dict]:
    """Collar, finial and the jewelled medallion on the blade heel."""
    parts = []
    parts += _caps(_oct(21.0, 22.0, 1.95, "ring", cap="gold_flat"), up="gold_flat", down="gold_dark")
    parts += _open(_oct(22.0, 26.0, 1.75, "collar"))
    parts += _caps(_oct(26.0, 27.0, 1.95, "ring", cap="gold_flat"), up="gold_flat", down="gold_dark")
    parts += _open(_oct(27.0, 27.8, 1.1, "bronze"))
    parts += _caps(_oct(27.8, 29.4, 1.5, "knob", cap="gold_flat"), down="gold_dark")
    # crowning amber crystal: two crossed diamonds read as a cut gem from any side
    gy = 30.55
    front = box((7.0, gy - 1.0, 7.4), (9.0, gy + 1.0, 8.6), "facet", glow=13)
    side = box((7.4, gy - 1.0, 7.0), (8.6, gy + 1.0, 9.0), "facet", glow=13)
    parts.append(turn(front, 45, "z", (8, gy, 8)))
    parts.append(turn(side, 45, "x", (8, gy, 8)))
    # medallion on the blade heel
    mx, my = 4.2, 24.2
    disc = prism((mx, my, BZ), 2.35, 1.8, "rim", axis="z", sides=8, cap="gold_flat")
    parts += turn(disc, 22.5, "z", (mx, my, BZ))
    # a square and a diamond stack into an eight-point star, stepping up to the gem
    parts.append(box((mx - 1.5, my - 1.5, BZ - 1.05), (mx + 1.5, my + 1.5, BZ + 1.05), "boss"))
    boss = box((mx - 1.5, my - 1.5, BZ - 1.2), (mx + 1.5, my + 1.5, BZ + 1.2), "boss")
    parts.append(turn(boss, 45, "z", (mx, my, BZ)))
    parts.append(box((mx - 1.0, my - 1.0, BZ - 1.5), (mx + 1.0, my + 1.0, BZ + 1.5), "gem", glow=12))
    return parts


def blade(icon: bool = False) -> list[dict]:
    x0, y0, x1, y1 = PLATE_BOX
    uw, vh = (x1 - x0) * TPU / 4, (y1 - y0) * TPU / 4
    tex = "blade_icon" if icon else "blade"
    plate = box((x0, y0, BZ - PLATE), (x1, y1, BZ + PLATE), tex,
                faces={"south": (tex, [0, 0, uw, vh]), "north": (tex, [uw, 0, 0, vh])},
                skip=("east", "west", "up", "down"))
    parts = [plate]
    # polished gold spine: thick, proud of both faces, tapering toward the tip
    parts += _chain(SPINE, 0.0, 0.96, 20, spine_w, spine_d, lambda t: spine_w(t) / 2 - 0.2, "spine",
                    period=4.0)
    # glowing honey edge, running out to a needle point
    parts += _chain(EDGE, 0.0, 1.0, 17, edge_w, edge_d, lambda t: 0.1, "edge", glow=11)
    if not icon:
        parts += relief()
    return parts


def relief() -> list[dict]:
    """A raised gold wheat sheaf along the blade: three stalks bound by a tie just past the
    medallion, two short ears splaying off and one long ear running toward the tip. Every
    piece passes through the plate, so it stands proud on both faces."""
    xs, ys, ls = _stalk_curve()
    parts = []
    proud = 2 * PLATE                                    # plate thickness; pieces add to it

    def at(s, off=0.0):
        t = float(np.interp(s, ls, TS))
        p = np.array([np.interp(t, TS, xs), np.interp(t, TS, ys)])
        q = np.array([np.interp(t + 0.002, TS, xs), np.interp(t + 0.002, TS, ys)])
        d = (q - p) / np.linalg.norm(q - p)
        n = np.array([-d[1], d[0]])
        return p + n * off, d, n

    def rod(pa, pb, width, depth):
        mid, half = (pa + pb) / 2, (pb - pa) / 2 * 1.1
        return bar((*(mid + half), BZ), (*(mid - half), BZ), width, depth, "kernel")

    def ear(s_from, s_to, off, spread, size):
        s, side, out = s_from, 1, []
        while s < s_to:                                  # kernels, alternating sides
            p, d, n = at(s, off)
            axis = d * math.cos(math.radians(34)) + n * side * math.sin(math.radians(34))
            c = p + n * side * spread + d * 0.15
            out.append(bar((*(c + axis * 0.6 * size), BZ), (*(c - axis * 0.6 * size), BZ), 0.66 * size,
                           proud + 0.5, "kernel"))
            s += 0.95 * size
            side = -side
        return out

    s0, s_tie, s_ear, s1 = (float(np.interp(t, TS, ls)) for t in (0.1, 0.18, 0.32, 0.72))
    for k in range(5):                                   # the main stalk
        a, b = s0 + (s1 - s0) * k / 5, s0 + (s1 - s0) * (k + 1) / 5
        parts.append(rod(at(a)[0], at(b)[0], 0.42, proud + 0.3))
    parts += ear(s_ear, s1, 0.0, 0.45, 1.0)
    p, d, n = at(s1)
    for spread in (-0.3, 0.0, 0.3):                      # awns streaming toward the tip
        e = p + (d + n * spread) / np.linalg.norm(d + n * spread) * 3.0
        parts.append(bar((*p, BZ), (*e, BZ), 0.22, proud + 0.2, "kernel"))
    for side in (-1, 1):                                 # two short ears splaying off the tie
        bend, end = s_tie + 1.3, s_tie + 3.9
        parts.append(rod(at(s_tie)[0], at(bend, 0.72 * side)[0], 0.36, proud + 0.26))
        parts.append(rod(at(bend, 0.72 * side)[0], at(end, 0.8 * side)[0], 0.36, proud + 0.26))
        parts += ear(bend + 0.3, end, 0.78 * side, 0.3, 0.75)
        p, d, n = at(end, 0.8 * side)
        parts.append(bar((*p, BZ), (*(p + (d + n * side * 0.25) * 1.4), BZ), 0.2, proud + 0.2, "kernel"))
    p, d, n = at(s_tie)                                  # the tie binding the three stalks
    parts.append(bar((*(p - n * 0.95), BZ), (*(p + n * 0.95), BZ), 0.55, proud + 0.6, "kernel"))
    return parts


def _wheat_ear(root, d, length, roll):
    """A stalk from root along d, then an ear: two crossed cut-out planes (the way
    vanilla crops are drawn) around a slim solid core."""
    d = np.asarray(d, dtype=float) / np.linalg.norm(d)
    e0 = root + d * length                       # where the ear starts
    parts = [bar(tuple(root - d * 0.8), tuple(e0 + d * 0.6), 0.42, 0.42, "stalk")]
    parts.append(bar(tuple(e0 + d * 0.3), tuple(e0 + d * 4.2), 0.8, 0.8, "ear_core"))
    p0, p1 = e0 - d * 0.5, e0 - d * 0.5 + d * EAR_H
    for r in (roll, roll + 90):
        parts.append(bar(tuple(p0), tuple(p1), EAR_W, 0.0, "ear", roll=r,
                         uv={"south": [0, 0, EAR_W, EAR_H], "north": [EAR_W, 0, 0, EAR_H]},
                         skip=("east", "west", "up", "down")))
    return parts


def sheaf() -> list[dict]:
    """Golden wheat bound under the collar with a crimson cord and a corn-husk bow."""
    parts = []
    parts += _caps(_oct(15.4, 17.6, 2.0, "straw", cap="straw_end"), up="straw_end")
    parts += _band(17.3, 18.9, 2.35, "husk_band", top="straw_end", bottom="husk_flat")
    parts += _band(17.85, 18.35, 2.5, "cord", top="cord_flat", bottom="cord_flat")
    # ears: (azimuth of the stalk root, lean from vertical, lean azimuth, stalk length)
    ears = [(-55, 8, -40, 4.6), (-18, 20, -12, 4.3), (18, 32, 10, 4.0), (52, 45, 32, 3.6), (88, 14, 60, 4.4)]
    for phi, lean, psi, length in ears:
        ph, le, ps = math.radians(phi), math.radians(lean), math.radians(psi)
        root = np.array([8 + 1.9 * math.cos(ph), 18.6, 8 + 1.9 * math.sin(ph)])
        d = np.array([math.sin(le) * math.cos(ps), math.cos(le), math.sin(le) * math.sin(ps)])
        parts += _wheat_ear(root, d, length, 15 + phi * 0.4)
    # corn-husk bow on the front
    bz = 8 + 2.35
    parts.append(box((7.95, 17.55, bz - 0.2), (8.85, 18.65, bz + 0.5), "husk"))
    for sgn in (-1, 1):
        loop = box((8.4 + sgn * 1.1 - 0.95, 17.5, bz - 0.1), (8.4 + sgn * 1.1 + 0.95, 18.75, bz + 0.35), "bow")
        parts.append(turn(loop, 20 * sgn, "z", (8.4, 18.1, bz)))
    parts.append(bar((7.5, 15.6, bz + 0.5), (8.25, 17.8, bz + 0.25), 0.75, 0.2, "husk"))
    parts.append(bar((9.35, 15.8, bz + 0.45), (8.55, 17.8, bz + 0.25), 0.75, 0.2, "husk"))
    return parts


def models() -> dict:
    parts = snath() + head() + blade() + sheaf()
    disp = display(KIND, parts, grip=GRIP)
    # Third person: carried upright like a reaper's scythe, the snath leaning out past the
    # head and the crescent sweeping outward over the shoulder.
    disp["thirdperson_righthand"] = place({"y": (-0.6, 1, 0.25), "x": (1, 0.6, -0.1)}, GRIP, "fist", 0.85)
    # First person: rising from the lower right, blade curling out to the right, clear of
    # the crosshair, the wheat sheaf toward the middle.
    disp["firstperson_righthand"] = place({"y": (-0.05, 1, -0.3), "x": (-1, 0, 0.35)}, GRIP,
                                          (0.66, -0.78, -1.05), 0.55, pose=None)
    icon = snath(compact=True) + head() + blade(icon=True) + sheaf()
    return {"main": model(parts, disp), "gui": model(icon, display(KIND, icon, grip=GRIP))}
