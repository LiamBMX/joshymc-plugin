"""Haunted Hollow Chestplate: the October set's chest piece (its helmet is the Jack-o'-Lantern Mask).

Charred, bark-brown armour carved with a huge jack-o'-lantern face that fills the chest (big
slanted angry eyes across the upper chest, a little nose, a wide jagged grin with fangs across
the lower chest) glowing candle orange from inside, olive vines wrapping the torso and the
rounded bark pauldrons, orange and rust autumn leaves, a charred belt with a tattered
rust-and-soot cloth skirt under it, and a small glowing pumpkin lantern hanging from each
shoulder.

Worn (humanoid layer, 4x = 256x128): everything is painted flat on the vanilla body and arm
boxes. The face fills the body front; it sells its light with saturated orange-yellow cores
(GLOW[6] at most, never the near-white GLOW[7]), darker cut edges and a warm halo on the
bark. The arms frame it: vines hang down the arm fronts beside it and each lantern hangs on
the front-outer corner of an arm (front face plus a sliver of the outer face). See the worn
layer notes below for which columns the arm boxes cover.

Item: a sculpted chestplate whose face is really carved. The bark front plate has the holes,
an orange cut-wall layer sits just behind it and the hot candle wall behind that, both
light-emitting. The candle wall and both lanterns flicker (slow interpolated animations).
The carved face looks north (-Z) like a helmet's: the GUI, the hand (held like a shield, face
out), first person and item frames all show it (the preview turntable calls that side "back").

FOR THE LEGGINGS AND BOOTS (the set's followers): copy the PALETTE block and the PAINTERS
section unchanged so the suit reads as one outfit (paint_bark for every bark surface,
paint_shingles for plated guards, paint_vine/paint_tendril, stamp_leaf, carve with a face
mask from face_mask() for knee or toe faces, stamp_lantern, paint_belt, paint_skirt; fold and
composite paint vines round a box as one wrapping strip). The chest's hem, which the leggings
should continue (rows of the 4x body faces, 48 rows tall):
  * rows 27-33: the vine garland swoops round the waist (paint_vine, width 3.2), lowest (row
    33) at the centre front and back, with a big LEAF maple leaf on it front-right and a RUST
    one front-left
  * rows 34-37: the charred belt (paint_belt): a BARK[4] top line, a SOOT[3] band with BARK[5]
    stitches every 4 px, a SOOT[2]/SOOT[1] row, a BARK[0] line under it
  * rows 38-47: the tattered skirt (paint_skirt): 3-5 px strips alternating RUST[2..4] and
    SOOT[1..3] with lit left edges, frayed ends in LEAF[3]/LEAF[4] on about every third strip,
    over a SOOT[0] lining. The leggings can start their leg tops with the same strips.
  * the item's hem: the "belt" texture band (y 7.2-8.8), the cloth atlas strips (rust, with
    the CLOTH_SOOT designs dark and the CLOTH_EMBER designs frayed orange), a soot underskirt
    and two dark tassets a side at the hips.
  * glow pixels never sit on the hem: the light lives in the face and the lanterns.
Coplanar overlaps to plan for when worn: the arm boxes cover the body's outer 2 px (chest);
the two leg boxes overlap each other at their inner edges (about 1.2 px with the leggings'
0.5 inflation, 2.2 px for boots at 1.0), so keep key details off those columns or repeat
them on both sides, like mirror_overlap does here.
"""
from __future__ import annotations

import math
import random

import numpy as np
from PIL import Image

from art.kit import HUMANOID, bar, box, display, mirror, model, place, region, rgba, save, save_animation, \
    save_layer, turn

ID = "haunted_hollow_chestplate"
NAME = "Haunted Hollow Chestplate"
KIND = "chestplate"
COUNTERPART = "item/netherite_chestplate"

# ======================================================================================
# PALETTE (shared by the whole Haunted Hollow set; every ramp runs darkest -> lightest)
# ======================================================================================
BARK = ["#0c0504", "#190d08", "#27150e", "#361e15", "#46291b", "#5b3621", "#77482a"]   # charred bark-brown
EMBER = ["#3f190b", "#5c250d", "#7d3410", "#a64812", "#cf6216"]                         # bark warmed by the glow
GLOW = ["#7a2606", "#a53a08", "#cf5612", "#f07418", "#fb9623", "#feb733", "#ffd75e", "#fff0a6"]  # cut edge -> candle
VINE = ["#22250a", "#353a12", "#4d531b", "#666e24", "#848d31", "#a9b04c"]                # olive-moss vines
LEAF = ["#5c2308", "#86360b", "#b0490d", "#d6620f", "#ef8424", "#fbaa45"]                # orange autumn leaves
RUST = ["#3a1006", "#55190b", "#702410", "#8c3115", "#aa431b", "#c75a22"]                # rust leaves and cloth
SOOT = ["#120a08", "#1e1411", "#2b1d18", "#3b2a22", "#4d3829"]                           # charred dark cloth
IRON = ["#140c09", "#24170f", "#3a2617", "#55391f", "#6e4c2a"]                           # lantern cap and hook

LIGHT = (-0.6, -0.8)   # painted light comes from the upper left (x right, y down)
S = 4                  # worn layers are painted at 4x (256x128)

# ======================================================================================
# PAINTERS (shared; numpy float RGBA arrays 0..255, x right, y down, boxes inclusive)
# ======================================================================================


def _c(colour) -> np.ndarray:
    return np.array(rgba(colour), dtype=np.float32)


def blank(w: int, h: int, colour=None) -> np.ndarray:
    a = np.zeros((h, w, 4), np.float32)
    if colour is not None:
        a[:] = _c(colour)
    return a


def to_image(a: np.ndarray) -> Image.Image:
    return Image.fromarray(np.clip(np.rint(a), 0, 255).astype(np.uint8), "RGBA")


def put(a: np.ndarray, x: int, y: int, colour) -> None:
    if 0 <= x < a.shape[1] and 0 <= y < a.shape[0]:
        a[y, x] = _c(colour)


def tint(a: np.ndarray, x: int, y: int, colour, k: float) -> None:
    """Blend an opaque pixel toward colour by k."""
    if 0 <= x < a.shape[1] and 0 <= y < a.shape[0] and a[y, x, 3] > 0:
        a[y, x, :3] = a[y, x, :3] * (1 - k) + _c(colour)[:3] * k


def shift(mask: np.ndarray, dx: int, dy: int) -> np.ndarray:
    """The mask moved by (dx, dy) pixels."""
    out = np.zeros_like(mask)
    h, w = mask.shape
    out[max(0, dy):h + min(0, dy), max(0, dx):w + min(0, dx)] = \
        mask[max(0, -dy):h - max(0, dy), max(0, -dx):w - max(0, dx)]
    return out


def grow(mask: np.ndarray) -> np.ndarray:
    return mask | shift(mask, 1, 0) | shift(mask, -1, 0) | shift(mask, 0, 1) | shift(mask, 0, -1)


def steps_to(mask: np.ndarray, limit: int = 8) -> np.ndarray:
    """4-connected steps from every pixel to the mask (0 inside), capped at limit + 1."""
    d = np.full(mask.shape, limit + 1, np.int16)
    cur = mask.copy()
    d[cur] = 0
    for k in range(1, limit + 1):
        nxt = grow(cur)
        d[nxt & ~cur] = k
        cur = nxt
    return d


def composite(base: np.ndarray, over: np.ndarray) -> None:
    """Paint an overlay (alpha 0..255) onto an opaque base in place."""
    k = over[..., 3:4] / 255.0
    base[..., :3] = base[..., :3] * (1 - k) + over[..., :3] * k


def fold(over: np.ndarray, pad: int) -> np.ndarray:
    """Wrap an overlay painted with `pad` spare columns on each side (for strips that go
    round a box: the body's right|front|left|back faces, or an arm's four sides)."""
    w = over.shape[1] - 2 * pad
    out = over[:, pad:pad + w].copy()
    for src, dst in ((over[:, :pad], slice(w - pad, w)), (over[:, pad + w:], slice(0, pad))):
        k = src[..., 3:4] / 255.0
        tgt = out[:, dst]
        tgt[..., :3] = tgt[..., :3] * (1 - k) + src[..., :3] * k
        tgt[..., 3] = np.maximum(tgt[..., 3], src[..., 3])
    return out


def paint_bark(a: np.ndarray, area, seed: int = 0, tone: int = 0, warm: float = 0.0) -> None:
    """Charred bark: long vertical ridges split by near-black furrows that wander, broken by
    short cross-checks. Each ridge is lit on its left edge and darker on its right. tone
    shifts the ramp; warm (0..1) leans some lit ridge edges toward EMBER (glow-lit bark)."""
    x0, y0, x1, y1 = area
    rng = random.Random(seed)
    h = y1 - y0 + 1
    furrows = []
    x = x0 - rng.randint(1, 3)
    while x <= x1 + 6:
        path, cx, step = [], float(x), rng.randint(3, 8)
        for r in range(h):
            if r % step == step - 1:
                cx += rng.choice((-1, 0, 0, 1))
                step = rng.randint(3, 8)
            path.append(int(cx))
        furrows.append(path)
        x += rng.choice((4, 4, 5, 5, 6))
    for r in range(h):
        y = y0 + r
        for f in range(len(furrows) - 1):
            left, right = furrows[f][r], furrows[f + 1][r]
            band = (f * 7 + (r + f * 5) // 11) % 4
            base = 3 + tone - (band == 0)
            for xx in range(left, right):
                if not x0 <= xx <= x1:
                    continue
                if xx == left:
                    c = BARK[0] if (r + f) % 6 else BARK[1]
                else:
                    i = base + 1 if xx == left + 1 else base - 1 if xx == right - 1 else base
                    c = BARK[max(0, min(len(BARK) - 1, i))]
                    if warm and xx == left + 1 and rng.random() < warm:
                        c = EMBER[1 + (rng.random() < 0.4)]
                a[y, xx] = _c(c)
    for _ in range((x1 - x0 + 1) * h // 45):                # cross-checks: a dark nick, lit below
        cx, cy = rng.randint(x0, x1), rng.randint(y0, y1 - 1)
        for k in range(rng.randint(1, 3)):
            xx = cx + k
            if xx <= x1 and not np.allclose(a[cy, xx], _c(BARK[0])):
                a[cy, xx] = _c(BARK[1])
                a[cy + 1, xx] = _c(BARK[max(0, min(len(BARK) - 1, 4 + tone))])


def paint_shingles(a: np.ndarray, area, seed: int = 0, course: int = 4, tone: int = 0, warm: float = 0.0) -> None:
    """Overlapping bark shingles in horizontal courses (pauldrons, guards): each plate lit
    along its top, dark at its foot with a shadow line under it, plates split by dark gaps
    and staggered course to course."""
    x0, y0, x1, y1 = area
    rng = random.Random(seed)
    row = y0
    k = 0
    while row <= y1:
        x = x0 - rng.randint(0, 5) - (3 if k % 2 else 0)
        while x <= x1:
            pw = rng.randint(5, 9)
            base = 3 + tone + (rng.random() < 0.3) - (rng.random() < 0.2)
            for j in range(course):
                yy = row + j
                if yy > y1:
                    break
                for xx in range(max(x, x0), min(x + pw, x1 + 1)):
                    i = base
                    if j == 0:
                        i = base + 1
                    elif j == course - 1:
                        i = base - 2
                    if xx == x + pw - 1:
                        i = 0
                    c = BARK[max(0, min(len(BARK) - 1, i))]
                    if warm and j == 0 and rng.random() < warm:
                        c = EMBER[2 + (rng.random() < 0.35)]
                    a[yy, xx] = _c(c)
            x += pw
        row += course
        k += 1


def poly_field(X, Y, pts):
    """Distance to a polyline, signed side offset, arc length and segment normal per pixel."""
    best = np.full(X.shape, np.inf)
    side = np.zeros(X.shape)
    arc = np.zeros(X.shape)
    nxs = np.zeros(X.shape)
    nys = np.zeros(X.shape)
    acc = 0.0
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        dx, dy = x1 - x0, y1 - y0
        length = math.hypot(dx, dy)
        if length < 1e-6:
            continue
        ux, uy = dx / length, dy / length
        t = np.clip((X - x0) * ux + (Y - y0) * uy, 0, length)
        dist = np.hypot(X - (x0 + ux * t), Y - (y0 + uy * t))
        s = (X - x0) * (-uy) + (Y - y0) * ux
        closer = dist < best
        best = np.where(closer, dist, best)
        side = np.where(closer, s, side)
        arc = np.where(closer, acc + t, arc)
        nxs = np.where(closer, -uy, nxs)
        nys = np.where(closer, ux, nys)
        acc += length
    return best, side, arc, nxs, nys


def paint_vine(over: np.ndarray, pts, width: float = 2.8, phase: float = 0.0, shadow: bool = True) -> None:
    """An olive vine along a polyline, painted on an overlay: lit edge, mid core, dark edge,
    a twist mark every few pixels and a soft drop shadow down-right onto the surface."""
    h, w = over.shape[:2]
    X, Y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    dist, s, arc, nx, ny = poly_field(X, Y, pts)
    r = width / 2
    inside = dist <= r
    if shadow:
        sh = (shift(inside, 1, 1) | shift(inside, 0, 1)) & ~inside & (over[..., 3] < 200)
        over[sh] = np.where(over[sh, 3:4] > 0, over[sh] * 0.6 + _c(BARK[0]) * 0.4,
                            np.array([*_c(BARK[0])[:3], 140], np.float32))
    lit = s * (nx * LIGHT[0] + ny * LIGHT[1]) > 0
    edge = np.abs(s) > r - 0.9
    idx = np.where(lit, 4, 3)
    idx = np.where(edge, np.where(lit, 5, 1), idx)
    twist = ((arc + phase + s * 1.2) % 5.0) < 0.9
    idx = np.where(twist & ~edge, 2, idx)
    lut = np.stack([_c(c) for c in VINE])
    over[inside] = lut[np.clip(idx, 0, 5)][inside]


def paint_tendril(over: np.ndarray, x: float, y: float, dx: int, dy: int, curl: int = 1) -> None:
    """A one-pixel tendril sprouting from (x, y) toward (dx, dy) that ends in a curl."""
    px, py = int(x), int(y)
    for i in range(3):
        px += dx if i != 1 else 0
        py += dy
        put(over, px, py, VINE[4])
    for ox, oy in ((curl, 0), (curl, -1), (0, -1)):
        put(over, px + ox, py + oy, VINE[3])


# Leaf sprites: h highlight, l light, m mid, d dark, D darker, v vein, s stem (stem down).
LEAF_SPRITES = {
    "maple_big": [
        "....h....",
        "...hlm...",
        ".h.hlm.d.",
        ".hhlhmmd.",
        "hhlhvmmdD",
        ".llhvmdD.",
        "..lmvdD..",
        ".l..v..D.",
        "....s....",
    ],
    "maple": [
        "...h...",
        ".h.lm.d",
        ".hlhmd.",
        "hlhvmdD",
        ".lmvdD.",
        "..mvD..",
        "...s...",
    ],
    "small": [
        ".l.m.",
        "lhvmd",
        ".mvD.",
        "..s..",
    ],
}
_LEAF_KEYS = {"h": 5, "l": 4, "m": 3, "d": 2, "D": 1, "v": 1}


def stamp_leaf(a: np.ndarray, x: int, y: int, pal=LEAF, kind: str = "maple", flip: bool = False,
               rot: int = 0, shadow: bool = True) -> None:
    """Stamp an autumn leaf sprite with its top-left at (x, y). flip mirrors it, rot turns it
    by rot * 90 degrees, and a one-pixel drop shadow tucks it onto the surface below."""
    rows = [list(r) for r in LEAF_SPRITES[kind]]
    if flip:
        rows = [r[::-1] for r in rows]
    for _ in range(rot % 4):
        rows = [list(r) for r in zip(*rows[::-1])]
    h, w = len(rows), len(rows[0])
    if shadow:
        for j in range(h):
            for i in range(w):
                if rows[j][i] != "." and (j + 1 >= h or i + 1 >= w or rows[j + 1][i + 1] == "."):
                    tint(a, x + i + 1, y + j + 1, BARK[0], 0.55)
    for j in range(h):
        for i in range(w):
            ch = rows[j][i]
            if ch != ".":
                put(a, x + i, y + j, VINE[1] if ch == "s" else pal[_LEAF_KEYS[ch]])


def candle_light(mask: np.ndarray, cx: float, cy: float, reach: float, heat: float = 1.0) -> np.ndarray:
    """GLOW ramp indices for the carved pixels of `mask`: an orange cut edge (the top edge,
    the wall in shadow, darker), hotter toward the middle of each hole and hottest (GLOW[6],
    never the near-white GLOW[7]) near the candle at (cx, cy)."""
    h, w = mask.shape
    depth = steps_to(~mask, 4)                          # 1 on the edge of a hole, 2, 3 deeper in
    X, Y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    near = np.clip(1 - np.hypot((X - cx) / 1.25, Y - cy) / reach, 0, 1)
    idx = 2.7 + np.minimum(depth - 1, 3) * 0.9 + near * 2.0 * heat
    top_edge = mask & ~shift(mask, 0, 1)
    idx = np.where(top_edge, idx - 1.2, idx)
    return np.clip(np.rint(idx), 1, 6).astype(int)


def carve(a: np.ndarray, mask: np.ndarray, ox: int, oy: int, candle, reach: float, halo: int = 3,
          heat: float = 1.0, warmth: float = 0.3) -> np.ndarray:
    """Cut a glowing jack-o'-lantern face (mask, True = carved) into opaque bark at (ox, oy):
    candle-lit holes, a black lip over each cut, a warm rind under it and a soft halo of
    glow-lit bark `halo` px wide (warmth = its strength next to the cut). Returns the
    full-size carved mask."""
    h, w = a.shape[:2]
    full = np.zeros((h, w), bool)
    mh, mw = mask.shape
    full[oy:oy + mh, ox:ox + mw] = mask
    lut = np.stack([_c(c) for c in GLOW])
    idx = candle_light(full, candle[0] + ox, candle[1] + oy, reach, heat)
    dist = steps_to(full, halo + 1)
    for k in range(halo, 1, -1):                        # the halo, strongest next to the cut
        ring = dist == k
        amt = warmth * (1 - (k - 2) / max(1, halo - 1))
        col = _c(EMBER[min(3, halo - k)])
        a[ring, :3] = a[ring, :3] * (1 - amt) + col[:3] * amt
    lip_over = ~full & shift(full, 0, -1)               # bark right above a hole
    lip_under = ~full & shift(full, 0, 1)               # bark right below a hole
    side = ~full & (shift(full, 1, 0) | shift(full, -1, 0)) & ~lip_over & ~lip_under
    a[lip_over] = _c(BARK[0])
    a[lip_under] = _c(EMBER[1])
    a[side] = _c(EMBER[0])
    a[full] = lut[idx][full]
    return full


def paint_belt(a: np.ndarray, x0: int, x1: int, y: int) -> None:
    """The charred belt, 4 rows from y: a lit top line, a soot band with stitches, a dark foot."""
    for x in range(x0, x1 + 1):
        put(a, x, y, BARK[4])
        put(a, x, y + 1, SOOT[3] if x % 4 else BARK[5])
        put(a, x, y + 2, SOOT[2] if (x + 2) % 7 else SOOT[1])
        put(a, x, y + 3, BARK[0])


def paint_skirt(a: np.ndarray, x0: int, x1: int, y0: int, y1: int, seed: int = 0, ember_every: int = 3) -> None:
    """Tattered skirt strips hanging from y0 to about y1 over a SOOT[0] lining: alternating
    rust and soot strips, lit left edge, fold line, ragged notched ends; every
    `ember_every`-th strip frays into glowing orange ends."""
    rng = random.Random(seed)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            put(a, x, y, SOOT[0])
    x, k = x0, 0
    while x <= x1:
        sw = rng.choice((3, 4, 4, 5))
        pal = RUST if k % 2 == 0 else SOOT
        lo = 2 if pal is RUST else 1
        end = y1 - rng.randint(0, 3)
        notch = rng.randrange(sw)
        ember = k % ember_every == ember_every - 1
        for xx in range(x, min(x + sw, x1 + 1)):
            e = end - (2 if xx == x + notch else 0) - (1 if (xx - x) % 3 == 2 else 0)
            for yy in range(y0, e + 1):
                i = lo + 1
                if xx == x:
                    i = lo + 2
                elif xx == x + sw - 1:
                    i = lo - 1
                elif xx == x + sw // 2 and (yy - y0) % 5 < 3:
                    i = lo
                if yy == y0:
                    i = max(i - 1, 0)
                c = pal[max(0, min(len(pal) - 1, i))]
                if ember and yy >= e - 1:
                    c = LEAF[4] if yy == e else LEAF[3]
                elif yy == e:
                    c = pal[max(0, lo - 1)]
                put(a, xx, yy, c)
        x += sw
        k += 1


LANTERN = [   # worn lantern sprite: i/I iron, o dark rim, g body, G bright, y/w hot carving
    "...I....",
    "..IiI...",
    ".iiiii..",
    "oggggGo.",
    "gywgwyGo",
    "gGyggyGo",
    "ggggggGo",
    "gywwwyGo",
    "ggyGyGgo",
    "oggggGo.",
    ".iiiii..",
]
_LANTERN_KEYS = {"i": IRON[1], "I": IRON[3], "o": GLOW[1], "g": GLOW[3], "G": GLOW[4], "y": GLOW[6],
                 "w": GLOW[7]}


def stamp_lantern(a: np.ndarray, x: int, y: int, chain: int = 3) -> None:
    """A glowing pumpkin lantern hanging from a short chain, top-left at (x, y), with a warm
    halo on what is behind it."""
    rows = LANTERN
    h, w = len(rows), len(rows[0])
    body = np.zeros(a.shape[:2], bool)
    for j in range(h):
        for i in range(w):
            if rows[j][i] in "goGyw" and 0 <= y + chain + j < a.shape[0] and 0 <= x + i < a.shape[1]:
                body[y + chain + j, x + i] = True
    dist = steps_to(body, 3)
    for k, amt in ((1, 0.45), (2, 0.25), (3, 0.1)):
        ring = dist == k
        a[ring, :3] = a[ring, :3] * (1 - amt) + _c(EMBER[3])[:3] * amt
    for j in range(chain):
        put(a, x + 3, y + j, IRON[2] if j % 2 else IRON[4])
    for j in range(h):
        for i in range(w):
            ch = rows[j][i]
            if ch != ".":
                put(a, x + i, y + chain + j, _LANTERN_KEYS[ch])


def face_mask(eye, nose, top, bottom, fang, width: int, height: int) -> np.ndarray:
    """A symmetric jack-o'-lantern face from its left half: eye spans (rows from 1), nose
    rows, the grin's top and bottom row per column, and the fang pixels left standing."""
    m = np.zeros((height, width), bool)
    half = width // 2
    for i, (a, b) in enumerate(eye):
        m[1 + i, a:b + 1] = True
    for r, (a, b) in nose.items():
        m[r, a:b + 1] = True
    for c in range(half):
        m[top[c]:bottom[c] + 1, c] = True
    for c, r in fang:
        m[r, c] = False
    m[:, half:] = m[:, :half][:, ::-1]
    return m


# The chest's face, 30 x 29: it fills the body front like the concept's. Huge angry eyes
# across the upper chest, a little nose, and a wide grin across the lower chest with two fangs
# on top and pointed teeth of light along the bottom. Knee or boot faces can use smaller masks
# from face_mask().
WORN_FACE = face_mask(
    eye=[(1, 1), (1, 2), (1, 3), (1, 4), (2, 5), (2, 6), (2, 7), (2, 8), (3, 9), (3, 10), (3, 12)],
    nose={13: (14, 14), 14: (13, 14)},
    top=[13, 13, 14, 14, 15, 15, 16, 16, 16, 16, 16, 16, 16, 16, 16],
    bottom=[14, 15, 17, 19, 17, 19, 20, 22, 20, 21, 22, 24, 22, 23, 25],
    fang=[(6, 16), (7, 16), (8, 16), (7, 17)],
    width=30, height=29)

# The item's face, 24 x 20 (2 texels per model unit: 12 x 10 units across the chest plate).
ITEM_FACE = face_mask(
    eye=[(1, 1), (1, 2), (1, 3), (1, 4), (2, 5), (2, 6), (2, 7), (3, 9)],
    nose={10: (11, 11), 11: (10, 11)},
    top=[10, 10, 11, 11, 12, 12, 13, 13, 13, 13, 13, 13],
    bottom=[11, 12, 13, 15, 13, 15, 17, 15, 16, 18, 17, 19],
    fang=[(5, 13), (6, 13), (5, 14)],
    width=24, height=20)


# ======================================================================================
# Worn layer (humanoid, 4x). The body's four sides are painted as one strip that wraps:
# right (x 0-15) | front (16-47) | left (48-63) | back (64-95), 48 rows; the arms likewise:
# outer (0-15) | front (16-31) | inner (32-47) | back (48-63). Body and arm rows line up.
#
# What shows when worn: the armour's arm boxes overlap the body box by 2 px on each side
# (coplanar at the front and back, and the idle arm sway flips which one wins), so only the
# middle 6 of the body's 10 px show cleanly: texels 7-24 of the body front and back (strip x
# 23-40 and 71-88). The face still fills the whole body front, so arm front columns 11-15
# repeat body front columns 1-5 (mirror_overlap): whichever box wins, the face reads whole.
# The body's side faces sit inside the arm boxes and its top under the head, so the arm
# fronts' outer 4 px frame the chest: the pauldrons, shoulder vines and lanterns live there.
# ======================================================================================

BELT_Y, SKIRT_Y = 34, 38
PAD = 10


def _garland_points():
    """The waist garland round the body strip (padded coordinates)."""
    pts = [(-8, 27.5), (-2, 28.5), (4, 27.0), (10, 28.0), (16, 28.0), (22, 29.0), (27, 30.6), (32, 31.6),
           (37, 30.6), (42, 29.0), (48, 28.0), (54, 28.5), (60, 27.5), (64, 27.0), (70, 28.0), (75, 30.0),
           (80, 31.4), (85, 30.0), (90, 28.0), (96, 27.5), (102, 28.5), (106, 27.0)]
    return [(x + PAD, y) for x, y in pts]


def paint_body_strip() -> np.ndarray:
    W, H = 96, 48
    a = blank(W, H, BARK[3])
    paint_bark(a, (0, 0, W - 1, H - 1), seed=11)
    for x in range(W):                                      # the collar rim
        put(a, x, 0, BARK[5] if x % 7 else BARK[4])
        tint(a, x, 1, BARK[0], 0.5)
    # Back: a tattered rust mantle hanging from the shoulders.
    mantle = blank(W, H)
    rng = random.Random(4)
    for x in range(69, 91):
        end = 18 + rng.randint(-1, 3) - (3 if x in (73, 74, 84) else 0)
        for y in range(1, end + 1):
            k = 3
            if (x - 69) % 6 == 0:
                k = 4
            elif (x - 69) % 6 == 5:
                k = 1
            elif (x - 69) % 6 == 3 and y % 5 < 3:
                k = 2
            if y == end or x in (69, 90):
                k = 1
            if y == 1:
                k = 5
            put(mantle, x, y, RUST[k])
        if rng.random() < 0.35:
            put(mantle, x, end - rng.randint(3, 7), BARK[1])            # a moth hole
    m = mantle[..., 3] > 0
    a[shift(m, 0, 1) & ~m] = _c(BARK[0])
    a[m] = mantle[m]
    # Under the arms at the back (the overlap columns) the body copies the pauldron's shingles
    # and rims, so whichever of the two coplanar boxes wins reads the same. (At the front the
    # face fills those columns and mirror_overlap copies them onto the arms instead.)
    for x0, x1 in ((64, 70), (89, 95)):
        paint_shingles(a, (x0, 0, x1, 8), seed=31 + x0, course=3, tone=1, warm=0.35)
        paint_shingles(a, (x0, 9, x1, 16), seed=32 + x0, course=4, tone=0, warm=0.15)
        for x in range(x0, x1 + 1):
            put(a, x, 0, EMBER[3] if x % 5 else LEAF[3])
            put(a, x, 8, BARK[5])
            put(a, x, 9, BARK[0])
            put(a, x, 16, BARK[5])
            put(a, x, 17, BARK[0])
    # Hem: belt and skirt all the way round.
    paint_belt(a, 0, W - 1, BELT_Y)
    paint_skirt(a, 0, W - 1, SKIRT_Y, H - 1, seed=7)
    # The carved face, filling the front (texels 1-30, rows 1-29).
    carve(a, WORN_FACE, 17, 1, candle=(15.0, 20.0), reach=14.0, halo=4, warmth=0.5)
    # Vines: the back V and the waist garland.
    over = blank(W + 2 * PAD, H)
    P = PAD
    paint_vine(over, [(70.0 + P, -2), (72.5 + P, 4), (76.5 + P, 10), (80.0 + P, 15.5)], 2.6, 2.2)
    paint_vine(over, [(90.0 + P, -2), (87.5 + P, 4), (83.5 + P, 10), (80.0 + P, 15.5)], 2.6, 0.9)
    paint_vine(over, _garland_points(), 3.2, 0.0)
    for tx, ty, dx, dy, c in ((25 + P, 31, -1, 1, -1), (39 + P, 31, 1, 1, 1), (74 + P, 29, -1, -1, 1),
                              (86 + P, 29, 1, -1, -1)):
        paint_tendril(over, tx, ty, dx, dy, c)
    composite(a, fold(over, P))
    # Leaves: on the garland and where the back vines meet.
    stamp_leaf(a, 34, 27, LEAF, "maple_big")                # big orange leaf front-right of centre
    stamp_leaf(a, 22, 28, RUST, flip=True)                  # rust leaf front-left
    stamp_leaf(a, 29, 31, LEAF, "small", rot=1)
    stamp_leaf(a, 76, 12, LEAF, "maple_big")                # where the back vines meet
    stamp_leaf(a, 83, 16, RUST, "small", flip=True)
    stamp_leaf(a, 71, 27, RUST, flip=True)
    stamp_leaf(a, 85, 27, LEAF, "small")
    return a


def paint_body_top() -> np.ndarray:
    """Body top (rows run from the back edge, row 0, to the front edge, row 15)."""
    a = blank(32, 16, BARK[3])
    paint_bark(a, (0, 0, 31, 15), seed=21, tone=1, warm=0.4)
    return a


def paint_arm_strip() -> np.ndarray:
    W, H = 64, 48
    a = blank(W, H, BARK[3])
    # Pauldron: two stepped tiers of bark shingles, lit warm along the shoulder.
    paint_shingles(a, (0, 0, W - 1, 8), seed=31, course=3, tone=1, warm=0.35)
    paint_shingles(a, (0, 9, W - 1, 16), seed=32, course=4, tone=0, warm=0.15)
    paint_bark(a, (0, 17, W - 1, H - 1), seed=33, tone=-1)
    for x in range(W):
        put(a, x, 0, EMBER[3] if x % 5 else LEAF[3])                   # rim light on the shoulder
        put(a, x, 8, BARK[5])
        put(a, x, 9, BARK[0])
        put(a, x, 16, BARK[5])
        put(a, x, 17, BARK[0])
        tint(a, x, 18, BARK[0], 0.6)
        tint(a, x, 10, BARK[0], 0.4)
    # Cuff: a rust cloth wrap with frayed, ember-lit ends.
    for x in range(W):
        for y in range(40, 44):
            put(a, x, y, RUST[3] if (x + y) % 4 else RUST[2])
        put(a, x, 40, RUST[4])
        put(a, x, 44, RUST[1])
    paint_skirt(a, 0, W - 1, 45, H - 1, seed=9, ember_every=2)
    # Vines: one wraps the pauldron; one hangs down each visible side of the sleeve (on the
    # front just inside the lantern, so the two arms frame the carved face).
    over = blank(W + 2 * PAD, H)
    P = PAD
    paint_vine(over, [(-8 + P, 5), (4 + P, 7), (16 + P, 4.5), (28 + P, 6.5), (40 + P, 4.5), (52 + P, 7),
                      (64 + P, 5), (72 + P, 7)], 2.8, 0.3)
    paint_vine(over, [(25.5 + P, 6), (24.6 + P, 13), (25.8 + P, 19), (24.8 + P, 25), (25.9 + P, 31),
                      (24.9 + P, 37), (25.5 + P, 41)], 2.6, 1.1)
    paint_vine(over, [(6.5 + P, 6), (8.2 + P, 14), (6.4 + P, 22), (8.4 + P, 30), (6.8 + P, 38), (7.5 + P, 41)],
               2.6, 2.0)
    paint_vine(over, [(56.5 + P, 6), (54.8 + P, 15), (56.8 + P, 24), (54.6 + P, 33), (56.0 + P, 41)], 2.6, 0.6)
    # Garland stubs on the inner columns, matching where the body's garland passes under the arm.
    paint_vine(over, [(25.0 + P, 25.5), (28.0 + P, 28.0), (33.0 + P, 28.5)], 3.0, 0.4)
    paint_vine(over, [(47.0 + P, 27.5), (51.5 + P, 27.5), (55.5 + P, 25.5)], 3.0, 1.4)
    for tx, ty, dx, dy, c in ((23 + P, 33, -1, -1, 1), (9 + P, 26, 1, -1, -1), (53 + P, 20, -1, -1, 1)):
        paint_tendril(over, tx, ty, dx, dy, c)
    composite(a, fold(over, P))
    # Leaves on the pauldron (front, outer and back faces) and down the sleeve vines.
    stamp_leaf(a, 18, 0, LEAF, "maple_big")
    stamp_leaf(a, 24, 9, RUST, "small", flip=True)
    stamp_leaf(a, 3, 1, RUST, flip=True)
    stamp_leaf(a, 9, 9, LEAF, "small")
    stamp_leaf(a, 51, 2, LEAF, flip=True)
    stamp_leaf(a, 4, 30, LEAF, "small", flip=True)
    stamp_leaf(a, 55, 28, RUST, "small")
    stamp_leaf(a, 22, 34, LEAF, "small", rot=1)
    # The lantern hangs on the front-outer corner: its face on the front, its side round the corner.
    stamp_lantern(a, 16, 17, chain=3)
    for j in range(3, 10):
        put(a, 15, 20 + j, GLOW[2] if j in (3, 9) else GLOW[3])
        put(a, 14, 20 + j, GLOW[1])
    put(a, 15, 24, GLOW[5])
    put(a, 15, 27, GLOW[5])
    return a


def paint_arm_top() -> np.ndarray:
    """Arm top (row 15 meets the arm's front face)."""
    a = blank(16, 16, BARK[3])
    paint_shingles(a, (0, 0, 15, 15), seed=41, course=4, tone=1, warm=0.5)
    over = blank(16, 16)
    paint_vine(over, [(9, -2), (8, 6), (10, 18)], 2.6)
    composite(a, over)
    stamp_leaf(a, 1, 3, LEAF, "maple_big")
    stamp_leaf(a, 11, 9, RUST, "small", rot=1)
    return a


def mirror_overlap(arm: np.ndarray, body: np.ndarray, rows: int = 34) -> None:
    """Arm front columns 11-15 lie on body front columns 1-5 (the arm boxes overlap the body
    by 2 px and the idle sway flips which one is drawn), so repeat the body's columns there.
    The left arm mirrors the texture and the face is symmetric, so one copy serves both."""
    for c in range(11, 16):
        src = 16 + int(1.2 * c - 12.7 + 0.5)            # body front column under arm column c
        arm[:rows, 16 + c] = body[:rows, src]


def paint_worn() -> Image.Image:
    out = blank(64 * S, 32 * S)

    def place_at(name, arr, dx=0):
        x0, y0, x1, y1 = region(HUMANOID, name, S)
        out[y0:y1 + 1, x0:x1 + 1] = arr[:, dx:dx + x1 - x0 + 1]

    body = paint_body_strip()
    for name, dx in (("body_right", 0), ("body_front", 16), ("body_left", 48), ("body_back", 64)):
        place_at(name, body, dx)
    place_at("body_top", paint_body_top())
    place_at("body_bottom", blank(32, 16, SOOT[0]))
    arm = paint_arm_strip()
    mirror_overlap(arm, body)
    for name, dx in (("arm_outer", 0), ("arm_front", 16), ("arm_inner", 32), ("arm_back", 48)):
        place_at(name, arm, dx)
    place_at("arm_top", paint_arm_top())
    place_at("arm_bottom", blank(16, 16, BARK[1]))
    return to_image(out)


# ======================================================================================
# Item textures (32 px = 2 texels per model unit unless noted)
# ======================================================================================

YT = 21.0          # the chest textures are anchored to the model: texel row 0 is y = 21,
XR = 16.0          # texel column 0 is x = 16 (a north face shows x falling to the right)
FACE_AT = (4, 2)   # the item face's top-left texel on the chest textures (x 2-14, y 10-20)


def tex_bark(seed: int, tone: int = 0, warm: float = 0.0) -> Image.Image:
    a = blank(32, 32, BARK[3])
    paint_bark(a, (0, 0, 31, 31), seed=seed, tone=tone, warm=warm)
    return to_image(a)


def tex_shingles(seed: int, tone: int = 0, warm: float = 0.0) -> Image.Image:
    a = blank(32, 32, BARK[3])
    paint_shingles(a, (0, 0, 31, 31), seed=seed, course=4, tone=tone, warm=warm)
    return to_image(a)


def tex_chest() -> Image.Image:
    """The carved front plate: bark with the face cut right through (transparent) and the
    candle's halo on the bark round the cuts."""
    a = blank(32, 32, BARK[3])
    paint_bark(a, (0, 0, 31, 31), seed=5)
    full = carve(a, ITEM_FACE, FACE_AT[0], FACE_AT[1], candle=(12.0, 15.0), reach=10.0, halo=3, warmth=0.42)
    a[full] = 0
    return to_image(a)


def tex_wall() -> Image.Image:
    """The cut walls just behind the plate: an orange ring inside every hole."""
    a = blank(32, 32)
    full = np.zeros((32, 32), bool)
    full[FACE_AT[1]:FACE_AT[1] + ITEM_FACE.shape[0], FACE_AT[0]:FACE_AT[0] + ITEM_FACE.shape[1]] = ITEM_FACE
    inner = full & ~grow(~full)
    ring = full & ~inner
    top = ring & ~shift(full, 0, 1)            # the top walls face down, away from the light
    a[ring] = _c(GLOW[2])
    a[top] = _c(GLOW[1])
    return to_image(a)


def flicker(t: float, phase: float = 0.0) -> float:
    """Candle flicker 0..1 over one loop (periodic, a little irregular)."""
    v = (0.55 * math.sin(2 * math.pi * (t + phase)) + 0.3 * math.sin(2 * math.pi * (3 * t + phase) + 1.3)
         + 0.15 * math.sin(2 * math.pi * (5 * t + 2 * phase) + 0.4))
    return 0.5 + 0.5 * v


def tex_glow_frame(t: float) -> Image.Image:
    """The candle-lit back wall behind the face: hottest low in the middle, cooling to deep
    orange at the edges; the hot spot sways and breathes over the loop."""
    f = flicker(t)
    cx = FACE_AT[0] + 12.0 + 0.9 * math.sin(2 * math.pi * 2 * t)
    cy = FACE_AT[1] + 14.5 + 0.7 * math.sin(2 * math.pi * t + 1.0)
    a = blank(32, 32)
    for y in range(32):
        for x in range(32):
            d = math.hypot((x + 0.5 - cx) / 1.35, (y + 0.5 - cy))
            heat = (1 - d / 15.0) * (0.78 + 0.22 * f)
            i = 3.0 + heat * 3.7
            if (x + y) % 2 and (i % 1) > 0.55:
                i += 0.5
            a[y, x] = _c(GLOW[int(max(2, min(6, i)))])
    return to_image(a)


def tex_inside() -> Image.Image:
    a = blank(16, 16, SOOT[0])
    for x in range(16):
        for y in range(16):
            if (x * 7 + y * 3) % 11 == 0:
                put(a, x, y, SOOT[1])
    return to_image(a)


def tex_vine() -> Image.Image:
    """Mottled olive vine bark for the vine bars: knobbly blotches, a few pale nodes."""
    a = blank(16, 16, VINE[3])
    rng = random.Random(13)
    for y in range(16):
        for x in range(16):
            r = rng.random()
            k = 2 if r < 0.22 else 4 if r < 0.42 else 3
            if (y * 5 + x * 3) % 13 == 0:
                k = 5
            if (y * 7 + x) % 11 == 0:
                k = 1
            a[y, x] = _c(VINE[k])
    return to_image(a)


LEAF_BIG = [   # 16 px maple leaf for the item's leaf quads (keys as LEAF_SPRITES)
    "................",
    ".......h........",
    "......hlm.......",
    "..h...hlm...d...",
    "..hh..hlmd..dd..",
    "...hhhlvmmmmd...",
    ".h..hhlvmmmd..D.",
    ".hhhhllvmmdddD..",
    "..hhlllvmmmdD...",
    "...hlllvmmdD....",
    "....llmvmdD.....",
    "...lm.mvdd.dD...",
    "..l....vD....D..",
    ".......s........",
    ".......s........",
    "................",
]


def tex_leaf(pal) -> Image.Image:
    a = blank(16, 16)
    for j, row in enumerate(LEAF_BIG):
        for i, ch in enumerate(row):
            if ch != ".":
                put(a, i, j, VINE[2] if ch == "s" else pal[_LEAF_KEYS[ch]])
    return to_image(a)


CLOTH_SLOTS = 12     # strip designs in the cloth atlas: 6 across, 2 rows, each 5 x 16 texels
CLOTH_SOOT = (1, 5, 9, 11)       # the dark designs (5 and 11 fray into embers: the tassets)
CLOTH_EMBER = (2, 5, 8, 11)      # designs whose tongues end in glowing orange
CLOTH_ENDS = [                   # last texel row of each column: pointed tongues and notches
    (11, 13, 15, 14, 12), (15, 12, 9, 12, 14), (12, 15, 13, 10, 13), (15, 14, 12, 10, 8), (9, 11, 13, 15, 14),
    (13, 15, 11, 14, 12), (12, 14, 15, 13, 10), (14, 11, 13, 15, 12), (10, 13, 15, 13, 11), (15, 13, 10, 12, 14),
    (13, 10, 12, 15, 14), (11, 14, 15, 12, 9),
]


def cloth_uv(k: int, width: float = 2.5) -> list[float]:
    """uv rectangle of strip design k: the whole 5 x 16 texel slot (a strip of any length
    shows its full tattered hem)."""
    u0 = (k % 6) * 2.5
    v0 = (k // 6) * 8.0
    return [u0, v0, u0 + width, v0 + 8.0]


def tex_cloth() -> Image.Image:
    a = blank(32, 32)
    rng = random.Random(17)
    for k in range(CLOTH_SLOTS):
        x0, y0 = (k % 6) * 5, (k // 6) * 16
        pal = SOOT if k in CLOTH_SOOT else RUST
        lo = 2 if pal is RUST else 1
        ember = k in CLOTH_EMBER
        for i, end in enumerate(CLOTH_ENDS[k]):
            for j in range(end + 1):
                c = pal[lo + 1]
                if i == 0:
                    c = pal[lo + 2]
                elif i == 4:
                    c = pal[lo - 1]
                elif i == 2 and j % 6 < 4:
                    c = pal[lo]
                if j == 0:
                    c = pal[lo - 1]
                if ember and j >= end - 2:
                    c = LEAF[4] if j == end else LEAF[3]
                elif j == end:
                    c = pal[lo - 1]
                put(a, x0 + i, y0 + j, c)
            if rng.random() < 0.3:
                put(a, x0 + i, y0 + rng.randint(3, 8), pal[0])          # a burnt hole
    return to_image(a)


def tex_underskirt() -> Image.Image:
    """Soot cloth hanging under the strips, folded, with a ragged hem (alpha)."""
    a = blank(32, 32)
    rng = random.Random(29)
    for x in range(32):
        end = 10 + rng.randint(0, 2) - (2 if x % 5 == 3 else 0)
        for y in range(end):
            k = (2, 3, 2, 1, 1)[x % 5]
            if y == end - 1:
                k = 0
            put(a, x, y, SOOT[k])
    return to_image(a)


def tex_mantle() -> Image.Image:
    """The rust mantle on the back, as seen from behind, with a tattered hem."""
    a = blank(32, 32)
    rng = random.Random(23)
    for x in range(19):
        end = 14 + rng.randint(0, 3) - (3 if x in (4, 11, 15) else 0)
        for y in range(end):
            k = 3
            if x % 5 == 0:
                k = 4
            elif x % 5 == 4:
                k = 1
            elif x % 5 == 2 and y % 6 < 4:
                k = 2
            if y == end - 1:
                k = 1
            if y == 0:
                k = 5
            put(a, x, y, RUST[k])
        if rng.random() < 0.3:
            put(a, x, end - rng.randint(3, 6), BARK[1])
    return to_image(a)


def tex_belt() -> Image.Image:
    a = blank(32, 32, SOOT[2])
    for x in range(32):
        put(a, x, 0, BARK[4])
        put(a, x, 1, SOOT[3] if x % 4 else BARK[5])
        put(a, x, 2, SOOT[2] if (x + 2) % 7 else SOOT[1])
        put(a, x, 3, BARK[0])
        for y in range(4, 32):
            put(a, x, y, SOOT[2] if (x + y) % 5 else SOOT[1])
    return to_image(a)


def tex_iron() -> Image.Image:
    a = blank(16, 16, IRON[1])
    for x in range(16):
        for y in range(16):
            k = 1
            if x == 0 or y == 0:
                k = 3
            elif (x + 2 * y) % 7 == 0:
                k = 2
            a[y, x] = _c(IRON[k])
    return to_image(a)


LANTERN_FRONT = [   # 8 x 8 lantern face: o rim, g body, G bright, y hot, w hottest
    "oooooooo",
    "ogggggGo",
    "oywggywo",
    "oGyggyGo",
    "oggggggo",
    "oywywywo",
    "ogyGyGgo",
    "oooooooo",
]
LANTERN_SIDE = [
    "oooooooo",
    "ogggGggo",
    "oggywggo",
    "oggyyggo",
    "oggyyGgo",
    "oggywggo",
    "ogggGggo",
    "oooooooo",
]


def tex_lantern_frame(t: float, phase: float) -> Image.Image:
    """16 px lantern atlas: front (0-7, 0-7), side (8-15, 0-7), top and bottom (0-7, 8-15).
    The light breathes with the flicker; the carved slits stay hot."""
    f = flicker(t, phase)
    keys = {"o": 1 + (f > 0.65), "g": 3 + (f > 0.45), "G": 4 + (f > 0.4), "y": 6, "w": 7}
    a = blank(16, 16)
    for ox, rows in ((0, LANTERN_FRONT), (8, LANTERN_SIDE)):
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                a[j, ox + i] = _c(GLOW[keys[ch]])
    for j in range(8):
        for i in range(8):
            edge = i in (0, 7) or j in (0, 7)
            a[8 + j, i] = _c(GLOW[1] if edge else GLOW[3 + (f > 0.5)])
    return to_image(a)


def textures() -> None:
    save_layer(paint_worn(), "humanoid")
    save(tex_bark(1), "bark")
    save(tex_bark(3, tone=-1), "bark_dark")
    save(tex_shingles(2), "shingles")
    save(tex_shingles(4, tone=1, warm=0.45), "shingles_lit")
    save(tex_chest(), "chest")
    save(tex_wall(), "wall")
    save_animation([tex_glow_frame(i / 12) for i in range(12)], "glow", frametime=2, interpolate=True)
    save(tex_inside(), "inside")
    save(tex_vine(), "vine")
    save(tex_leaf(LEAF), "leaf")
    save(tex_leaf(RUST), "leaf_rust")
    save(tex_cloth(), "cloth")
    save(tex_underskirt(), "underskirt")
    save(tex_mantle(), "mantle")
    save(tex_belt(), "belt")
    save(tex_iron(), "iron")
    save_animation([tex_lantern_frame(i / 8, 0.0) for i in range(8)], "lantern", frametime=3, interpolate=True)
    save_animation([tex_lantern_frame(i / 8, 0.37) for i in range(8)], "lantern_b", frametime=3,
                   interpolate=True)


# ======================================================================================
# Item model. Frame: the front (the carved face) looks north (-Z), +X is the wearer's
# right, up is +Y; the torso is centred on x = 8, z = 8.
# ======================================================================================

TX0, TX1 = 1.8, 14.2       # chest sides
WX0, WX1 = 3.0, 13.0       # waist sides (the torso tapers under the grin)
TZ0, TZ1 = 5.0, 11.0       # torso core front / back
PLATE_Z = 4.2              # the carved plate's face
WAIST_Y = 11.0             # where the chest steps in to the waist
TOP_Y = 20.6               # top of the torso core (the neck hollow)
BELT = (7.2, 8.8)


def front_uv(x0: float, x1: float, y0: float, y1: float) -> list[float]:
    """uv of a north face anchored to the chest textures (see YT/XR)."""
    return [round(XR - x1, 4), round(YT - y1, 4), round(XR - x0, 4), round(YT - y0, 4)]


def torso() -> list[dict]:
    parts = [
        box((TX0, WAIST_Y, TZ0), (TX1, TOP_Y, TZ1), "bark",
            faces={"up": ("inside", [0, 0, 12.4, 6]), "south": "bark_dark"}, skip=("down", "north")),
        box((WX0, BELT[0] + 0.4, TZ0 + 0.3), (WX1, WAIST_Y + 0.2, TZ1 - 0.2), "bark_dark",
            skip=("down", "up", "north")),
    ]
    # The carved front plate (chest and waist) and the two glowing layers behind its holes.
    y1 = TOP_Y - 0.2
    parts.append(box((TX0, WAIST_Y, PLATE_Z), (TX1, y1, TZ0), "bark",
                     faces={"north": ("chest", front_uv(TX0, TX1, WAIST_Y, y1)), "down": "bark_dark"},
                     skip=("south",)))
    parts.append(box((WX0, BELT[1] - 0.4, PLATE_Z + 0.25), (WX1, WAIST_Y, TZ0 + 0.3), "bark",
                     faces={"north": ("chest", front_uv(WX0, WX1, BELT[1] - 0.4, WAIST_Y))},
                     skip=("south", "up")))
    fh, fw = ITEM_FACE.shape
    fx0, fx1 = XR - (FACE_AT[0] + fw) / 2, XR - FACE_AT[0] / 2
    fy1, fy0 = YT - FACE_AT[1] / 2, YT - (FACE_AT[1] + fh) / 2
    for z, tex in ((PLATE_Z + 0.3, "wall"), (PLATE_Z + 0.7, "glow")):
        parts.append(box((fx0, fy0, z), (fx1, fy1, z + 0.04), tex,
                         faces={"north": (tex, front_uv(fx0, fx1, fy0, fy1))},
                         skip=("south", "east", "west", "up", "down"), glow=15, shade=False))
    # Bevel where the chest steps in to the waist.
    for sx, cx in ((TX0, TX0 + 0.55), (TX1, TX1 - 0.55)):
        b = box((cx - 0.8, WAIST_Y - 0.8, PLATE_Z + 0.1), (cx + 0.8, WAIST_Y + 0.8, TZ1 - 0.3), "bark_dark",
                faces={"north": "bark"})
        parts.append(turn(b, 45, "z", (cx, WAIST_Y, 8)))
    # Collar ring round the neck hollow.
    parts.append(box((5.2, TOP_Y - 0.9, PLATE_Z - 0.15), (10.8, TOP_Y + 0.7, TZ0 + 0.3), "shingles_lit"))
    parts.append(box((5.2, TOP_Y - 0.6, TZ1 - 0.4), (10.8, TOP_Y + 1.2, TZ1 + 0.4), "shingles_lit"))
    for sx in (4.4, 10.8):
        parts.append(box((sx, TOP_Y - 0.6, TZ0), (sx + 0.8, TOP_Y + 0.9, TZ1), "shingles_lit"))
    # Belt.
    parts.append(box((WX0 - 0.35, BELT[0], PLATE_Z - 0.05), (WX1 + 0.35, BELT[1], TZ1 + 0.1), "belt",
                     faces={"up": "bark_dark", "down": "inside"}))
    # Bark knobs that break up the silhouette of the sides and back.
    rng = random.Random(8)
    for i in range(8):
        side = i % 3
        y = rng.uniform(12.0, 15.5)
        if side == 0:
            z = rng.uniform(6.0, 9.5)
            parts.append(box((TX0 - 0.35, y, z), (TX0 + 0.15, y + rng.uniform(1.0, 2.2), z + rng.uniform(1.0, 2.0)),
                             "bark"))
        elif side == 1:
            z = rng.uniform(6.0, 9.5)
            parts.append(box((TX1 - 0.15, y, z), (TX1 + 0.35, y + rng.uniform(1.0, 2.2), z + rng.uniform(1.0, 2.0)),
                             "bark"))
        else:
            x = rng.uniform(3.5, 11.0)
            parts.append(box((x, y - 2, TZ1 - 0.1), (x + rng.uniform(1.0, 2.0), y - 2 + rng.uniform(1.0, 2.0),
                                                      TZ1 + 0.35), "bark_dark"))
    return parts


def pauldron() -> list[dict]:
    """The rounded bark pauldron on the wearer's left (-X side); mirrored for the right. Its
    front stays outside the carved face: an outer mass hugging the shoulder, a shoulder cap
    over the chest above the eyes, an outer roll, a rounded top edge and a flared lower rim."""
    lit = {"up": "shingles_lit"}
    parts = [
        box((-1.5, 16.2, 3.5), (1.9, 20.8, 12.5), "shingles", faces=lit),             # outer mass
        box((0.2, 20.4, 3.9), (5.4, 22.4, 12.1), "shingles", faces=lit),              # shoulder cap
        box((-2.4, 16.8, 4.1), (-1.5, 20.2, 11.9), "shingles", faces=lit),            # outer roll
    ]
    edge = box((-1.95, 20.25, 4.3), (-0.95, 21.25, 11.7), "shingles_lit")              # rounded top-outer edge
    parts.append(turn(edge, 45, "z", (-1.45, 20.75, 8.0)))
    rim = box((-2.2, 15.2, 3.1), (1.8, 16.5, 12.9), "bark_dark", faces={"up": "shingles_lit"})
    parts.append(turn(rim, -9, "z", (1.8, 16.5, 8.0)))                                # flared lower rim
    rng = random.Random(3)
    for i in range(3):                                                                # rough voxel knobs
        x, z = rng.uniform(0.4, 3.6), rng.uniform(4.8, 9.6)
        w, d, h = rng.uniform(1.0, 1.6), rng.uniform(1.4, 2.2), rng.uniform(0.35, 0.6)
        parts.append(box((x, 22.4, z), (x + w, 22.4 + h, z + d), "shingles", faces=lit))
    return parts


def lantern(x: float, y: float, z: float, tex: str, w: float = 2.3, h: float = 2.6, hook: float = 0.9) -> list[dict]:
    """A small pumpkin lantern hanging from (x, y, z): hook, ring, cap, glowing body, base."""
    q = {"north": (tex, [0, 0, 8, 8]), "south": (tex, [8, 0, 16, 8]), "east": (tex, [8, 0, 16, 8]),
         "west": (tex, [8, 0, 16, 8]), "up": (tex, [0, 8, 8, 16]), "down": (tex, [0, 8, 8, 16])}
    r, c = w / 2, w / 2 - 0.25
    top = y - hook - 0.45
    return [
        bar((x, y, z), (x, y - hook, z), 0.32, 0.32, "iron"),
        box((x - 0.4, y - hook - 0.25, z - 0.14), (x + 0.4, y - hook + 0.25, z + 0.14), "iron"),
        box((x - c, top, z - c), (x + c, top + 0.45, z + c), "iron"),
        box((x - r, top - h, z - r), (x + r, top, z + r), tex, faces=q, glow=15, shade=False),
        box((x - c, top - h - 0.35, z - c), (x + c, top - h, z + c), "iron"),
    ]


def vine_path(points, width: float = 0.8, tex: str = "vine") -> list[dict]:
    """Bars along a 3D polyline, overlapping a little at the joints."""
    parts = []
    for i, (a, b) in enumerate(zip(points, points[1:])):
        d = [b[k] - a[k] for k in range(3)]
        n = math.sqrt(sum(v * v for v in d)) or 1.0
        p0 = [a[k] - d[k] / n * 0.18 for k in range(3)]
        p1 = [b[k] + d[k] / n * 0.18 for k in range(3)]
        parts.append(bar(p0, p1, width, width, tex, roll=23 * i))
    return parts


def leaf3d(x: float, y: float, z: float, size: float = 2.2, tex: str = "leaf", rx: float = 0.0,
           ry: float = 0.0, rz: float = 0.0) -> dict:
    """A flat autumn leaf (both sides textured) centred on (x, y, z), facing north before
    the Euler turn (rx, ry, rz)."""
    h = size / 2
    e = box((x - h, y - h, z - 0.04), (x + h, y + h, z + 0.04), tex,
            faces={"north": (tex, [0, 0, 16, 16]), "south": (tex, [16, 0, 0, 16])},
            skip=("east", "west", "up", "down"))
    return turn(e, x=rx, y=ry, z=rz, origin=(x, y, z))


GARLAND_FRONT = [(14.6, 11.6, 5.4), (13.8, 10.6, 4.05), (12.0, 9.7, 3.8), (10.0, 9.2, 3.75), (8.0, 9.05, 3.75),
                 (6.0, 9.2, 3.75), (4.0, 9.7, 3.8), (2.2, 10.6, 4.05), (1.4, 11.6, 5.4)]


def vines() -> list[dict]:
    parts = []
    # Waist garland: swoops under the grin at the front and round the back.
    around = [(1.4, 11.6, 5.4), (1.4, 11.0, 7.4), (1.4, 11.6, 9.4), (2.2, 11.1, 11.4), (4.6, 10.0, 11.75),
              (8.0, 9.6, 11.8), (11.4, 10.0, 11.75), (13.8, 11.1, 11.4), (14.6, 11.6, 9.4), (14.6, 11.0, 7.4),
              (14.6, 11.6, 5.4)]
    parts += vine_path(GARLAND_FRONT, 1.05)
    parts += vine_path(around, 0.95)
    # Shoulder vines: over the shoulder cap, down the pauldron's front and outer corner, and
    # down the side of the chest behind the lantern into the garland.
    parts += vine_path([(3.2, 22.8, 9.6), (3.6, 22.7, 4.4), (2.0, 21.4, 3.5), (0.6, 20.2, 3.15), (-0.8, 18.8, 3.15),
                        (-1.9, 17.2, 3.6), (-2.5, 15.6, 4.6), (-1.4, 13.8, 6.0), (0.6, 12.4, 6.1), (1.4, 11.6, 5.4)],
                       0.95)
    parts += vine_path([(12.6, 22.8, 10.6), (12.6, 22.7, 4.2), (14.2, 21.4, 3.5), (15.6, 19.8, 3.15),
                        (17.0, 18.2, 3.2), (18.2, 16.4, 3.9), (17.4, 14.2, 6.0), (15.4, 12.4, 6.1), (14.6, 11.6, 5.4)],
                       0.95)
    # The V on the back.
    parts += vine_path([(3.4, 22.6, 11.9), (4.6, 20.6, 12.75), (6.2, 18.2, 11.75), (8.0, 15.6, 11.7)], 0.75)
    parts += vine_path([(12.6, 22.6, 11.9), (11.4, 20.6, 12.75), (9.8, 18.2, 11.75), (8.0, 15.6, 11.7)], 0.75)
    # Tendril curls.
    for p in ([(8.0, 9.05, 3.75), (7.4, 8.3, 3.45), (7.9, 7.8, 3.35)],
              [(12.0, 9.7, 3.8), (12.8, 9.1, 3.5), (13.1, 9.7, 3.35)],
              [(-0.8, 18.8, 3.15), (-1.6, 19.5, 2.75), (-1.3, 20.2, 2.55)],
              [(17.0, 18.2, 3.2), (17.8, 17.5, 2.8), (18.0, 18.2, 2.55)]):
        parts += vine_path(p, 0.4)
    return parts


def leaves() -> list[dict]:
    L, R = "leaf", "leaf_rust"
    spots = [   # x, y, z, size, texture, Euler x/y/z
        # garland, front: a big orange leaf right of centre, smaller ones along it
        (10.6, 8.7, 3.15, 3.0, L, 6, -14, 16), (5.0, 9.2, 3.2, 2.4, R, -6, 12, -26), (7.8, 8.0, 3.1, 1.9, L, 0, 0, 44),
        (13.9, 10.8, 3.6, 2.2, L, 0, -28, 32),
        # pauldrons: a cluster on each shoulder cap and down the front of each outer mass
        (1.3, 22.3, 3.3, 3.8, L, 34, 14, -16), (4.5, 23.1, 5.4, 3.0, R, 58, 0, 24), (1.4, 23.0, 8.2, 2.8, L, 78, 30, 0),
        (-1.1, 18.6, 3.0, 3.2, L, 10, 24, 32), (0.9, 17.2, 3.1, 2.2, R, 8, 10, -20),
        (14.7, 22.3, 3.3, 3.8, L, 34, -14, 16), (11.5, 23.1, 5.6, 3.0, L, 58, 0, -28), (14.6, 23.0, 8.6, 2.8, R, 80, -20, 0),
        (17.1, 18.6, 3.0, 3.2, R, 10, -24, -34), (15.1, 17.2, 3.1, 2.2, L, 8, -10, 22),
        # sides and back
        (1.0, 11.4, 8.3, 2.6, L, 0, 90, 15), (15.0, 11.2, 8.7, 2.6, R, 0, -90, -15),
        (8.0, 15.8, 12.15, 3.0, L, 0, 180, 0), (6.6, 14.4, 12.2, 2.2, R, 0, 180, 35),
        (5.0, 10.2, 12.2, 2.3, R, 0, 180, -20), (11.4, 10.4, 12.2, 2.3, L, 0, 180, 25),
    ]
    return [leaf3d(x, y, z, s, t, rx, ry, rz) for x, y, z, s, t, rx, ry, rz in spots]


def strip(x: float, z: float, width: float, length: float, design: int, flare: float, facing: str,
          lean: float = 0.0, top: float = BELT[0] + 0.5) -> dict:
    """One tattered cloth strip hanging from the belt, flared outward by `flare` degrees.
    facing is the side of the torso it hangs on (north, south, east or west); x/z is the
    middle of its top edge."""
    uv = cloth_uv(design, min(2.5, width))
    back = [uv[2], uv[1], uv[0], uv[3]]
    edge = [uv[0], uv[1], uv[0] + 0.5, uv[3]]
    t = 0.3
    if facing in ("north", "south"):
        e = box((x - width / 2, top - length, z - t / 2), (x + width / 2, top, z + t / 2), "cloth",
                faces={"north": ("cloth", uv if facing == "north" else back),
                       "south": ("cloth", back if facing == "north" else uv),
                       "east": ("cloth", edge), "west": ("cloth", edge)}, skip=("up", "down"))
        turn(e, flare * (1 if facing == "north" else -1), "x", (x, top, z))
    else:
        e = box((x - t / 2, top - length, z - width / 2), (x + t / 2, top, z + width / 2), "cloth",
                faces={"east": ("cloth", uv if facing == "east" else back),
                       "west": ("cloth", back if facing == "east" else uv),
                       "north": ("cloth", edge), "south": ("cloth", edge)}, skip=("up", "down"))
        turn(e, flare * (1 if facing == "east" else -1), "z", (x, top, z))
    if lean:
        turn(e, lean, "z" if facing in ("north", "south") else "x", (x, top, z))
    return e


def skirt() -> list[dict]:
    """The tattered skirt. Every piece starts just inside the belt (never on one of its faces,
    so nothing z-fights) and flares out as it falls: a soot underskirt, rust-and-soot strips
    front and back, side strips, and dark tassets at the hips that fray into embers."""
    parts = []
    # Soot underskirt: four panels hanging almost straight, so gaps show cloth, not air.
    top, ln = BELT[0] + 0.3, 5.2
    under = {"north": ("underskirt", [0, 0, 10.2, ln]), "south": ("underskirt", [10.2, 0, 0, ln])}
    for z, flare in ((PLATE_Z + 0.75, 5), (TZ1 - 0.6, -5)):
        e = box((WX0 + 0.1, top - ln, z - 0.1), (WX1 - 0.1, top, z + 0.1), "underskirt", faces=under,
                skip=("east", "west", "up", "down"))
        parts.append(turn(e, flare, "x", (8, top, z)))
    side_uv = {"east": ("underskirt", [0, 0, 6.2, ln]), "west": ("underskirt", [6.2, 0, 0, ln])}
    for x, flare in ((WX0 + 0.45, -5), (WX1 - 0.45, 5)):
        e = box((x - 0.1, top - ln, TZ0 + 0.2), (x + 0.1, top, TZ1 - 0.2), "underskirt", faces=side_uv,
                skip=("north", "south", "up", "down"))
        parts.append(turn(e, flare, "z", (x, top, 8)))
    # Front and back: six strips each, alternating rust and soot, two depths so they overlap.
    front = [(3.6, 6.6, 0, 3), (5.4, 7.6, 1, -2), (7.2, 6.2, 2, 2), (8.9, 8.0, 3, -2), (10.7, 6.8, 9, 2),
             (12.4, 7.4, 4, -3)]
    for i, (x, length, design, lean) in enumerate(front):
        parts.append(strip(x, PLATE_Z + 0.2 + 0.15 * (i % 2), 2.1, length, design, 9 + 2 * (i % 2), "north", lean))
    back = [(3.6, 7.0, 6, 2), (5.4, 6.4, 7, -3), (7.2, 7.8, 8, 2), (8.9, 6.6, 9, -2), (10.7, 7.4, 10, 3),
            (12.4, 6.8, 0, -2)]
    for i, (x, length, design, lean) in enumerate(back):
        parts.append(strip(x, TZ1 - 0.15 - 0.15 * (i % 2), 2.1, length, design, 8 + 2 * (i % 2), "south", lean))
    for facing, x in (("west", WX0), ("east", WX1)):
        for j, (z, length, design) in enumerate(((8.2, 6.6, 3), (10.0, 6.0, 6))):
            parts.append(strip(x, z, 2.0, length, design, 12, facing))
    # Tassets at the hips: two dark panels a side, frayed into glowing orange.
    for facing, x, s_ in (("west", WX0 - 0.15, 1), ("east", WX1 + 0.15, -1)):
        parts.append(strip(x, 5.05, 1.7, 7.8, 5, 17, facing, 4 * s_))
        parts.append(strip(x + 0.05 * s_, 6.6, 1.7, 7.1, 11, 16, facing))
    return parts


def mantle() -> list[dict]:
    x0, x1, y0, y1 = 3.4, 12.6, 12.2, TOP_Y + 0.2
    e = box((x0, y0, TZ1 + 0.05), (x1, y1, TZ1 + 0.35), "mantle",
            faces={"south": ("mantle", [0, 0, x1 - x0, y1 - y0]), "east": ("mantle", [0, 0, 0.5, y1 - y0]),
                   "west": ("mantle", [0, 0, 0.5, y1 - y0])}, skip=("north", "up", "down"))
    return [turn(e, -6, "x", (8, y1, TZ1 + 0.2))]


def build() -> list[dict]:
    left = pauldron()
    parts = torso() + left + mirror(left, "x", 8.0) + mantle() + skirt() + vines() + leaves()
    parts += lantern(-0.8, 15.4, 4.2, "lantern")
    parts += lantern(16.8, 15.4, 4.2, "lantern_b")
    return parts


def models() -> dict:
    parts = build()
    d = display(KIND, parts, gui_rotation=(14, 158, 0), gui_span=15.6)
    centre = (8.0, 13.0, 8.0)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, (8.0, 14.0, 12.4), "fist", 0.46)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.4, 0, -1)}, centre, (0.52, -0.38, -0.95), 0.5,
                                       pose=None)
    return {"main": model(parts, d)}
