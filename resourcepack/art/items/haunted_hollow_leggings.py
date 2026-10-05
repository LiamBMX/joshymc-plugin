"""Haunted Hollow Leggings: the October set's leg piece (with the Haunted Hollow Chestplate and
Boots; the helmet is the Jack-o'-Lantern Mask).

Dark charred-bark legs under a thick bark waistband whose rim of uneven blocks is lit orange
in places, a tattered rust flap hanging from it down the front and the back, olive vines
spiralling down both legs from the waistband's front corners with orange and rust maple
leaves, a crowned bark knee guard on each knee carved with a glowing jack-o'-lantern face
(slanted angry eyes, a little nose, a grin with two fangs), a vine garland swooping under the
knees, warm bark-plated shins crossed by vines, and a blocky bark cuff. A small pumpkin
lantern hangs at each hip.

Worn (humanoid_leggings layer, 4x = 256x128): painted flat on the vanilla leggings boxes
(inflated 0.5). The legs are one strip, outer | front | inner | back, that the left leg
mirrors; leg row r sits r * 13/48 units below the top of the leg box (the knee is row 24).
  * the chestplate's box hides leg rows 0-5, so its skirt runs straight into the flap's ends,
    its long tongue down the crease (rows 9-13) and the guards' crowns (rows 10-11)
  * the knee face fills rows 14-23 (its lower lip is row 24); the Haunted Hollow Boots' collar
    starts just under it (y 18.29), so keep these rows where they are or tell the boots
  * the guard is rows 12-26 on the front and wraps the outer side (outer cols 9-15); the
    lantern hangs on the outer side (rows 0-13), under the arm's swing
  * the garland is rows 28-31, then warm bark plates crossed by vines, and the cuff rows 44-47
The two leg boxes overlap by 1.2 units at their inner edges (front cols 12-15, back cols 0-3,
coplanar), so those columns are made symmetric (mirror_seams): whichever leg wins, the pair
shows one picture, with a dark crease down the middle. The waistband lives on the body box
(rows 33-44, hidden under any chestplate); row 44 is a dark seam that closes the sliver above
the legs' boxes, and rows 45-47 stay clear because the legs' top rows lie in the same planes.
Its glowing side slits, like the concept's side view, only show when the arms swing.

Item: a sculpted pair of leggings, front (and the knee faces) toward north (-Z) like the
chestplate's carved face, so the GUI, the hand, first person and item frames show it. The
waistband and hips take the top third, the legs stand apart under the crotch and the knee
guards sit a little below their middle, as in the concept. Each knee guard is really carved:
a bark plate with the face cut through, a dark cut-wall layer behind the top edges of the cuts
and a candle-lit back wall that glows and flickers (two phases, one per knee); the lanterns
and the waistband's side slits glow too.

Palette and painters are copied unchanged from haunted_hollow_chestplate.py (the set's pilot);
paint_tatters, ring, mirror_seams and the guard are this piece's own.
"""
from __future__ import annotations

import math
import random

import numpy as np
from PIL import Image

from art.kit import HUMANOID, bar, box, display, mirror, model, place, region, rgba, save, save_animation, \
    save_layer, turn

ID = "haunted_hollow_leggings"
NAME = "Haunted Hollow Leggings"
KIND = "leggings"
COUNTERPART = "item/netherite_leggings"

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


# ======================================================================================
# The knee face (one mask for the worn layer and the item): 11 x 10, the chestplate's face
# shrunk to a knee. Slanted angry eyes (their inner corners low), a little nose pointing up,
# a grin with upturned corners, two fangs hanging from its top and a jagged lower lip.
# ======================================================================================

def _mask(rows) -> np.ndarray:
    return np.array([[ch == "X" for ch in row] for row in rows], bool)


KNEE_FACE = _mask([
    "X.........X",
    "XX.......XX",
    "XXX.....XXX",
    "XXXX...XXXX",
    ".....X.....",
    "....XXX....",
    "X.........X",
    "XX.XXXXX.XX",
    ".XXXXXXXXX.",
    "..XX.X.XX..",
])
KNEE_CANDLE = (5.5, 7.6)      # where the candle sits behind the knee face (mask coordinates)


# ======================================================================================
# Worn layer (humanoid_leggings, 4x). Leg strip: outer (0-15) | front (16-31) | inner (32-47)
# | back (48-63), 48 rows. Body strip: right (0-15) | front (16-47) | left (48-63) | back
# (64-95), 48 rows, only the waistband painted.
# ======================================================================================

PAD = 10
FRONT, INNER, BACK = 16, 32, 48
GUARD_ROWS = (12, 26)               # the knee guard's plate (its crown rises two rows above)
GUARD_COLS = (9, 28)                # strip columns: the side of the knee (outer 9-15) and front 0-12
MERLONS = ((12, 15), (16, 18), (21, 23), (26, 28))     # the crown blocks
FACE_AT = (FRONT + 1, 14)           # the knee face's top-left on the leg strip (rows 14-23)
WAIST_TOP, WAIST_FOOT = 33, 43      # body rows of the waistband


def mirror_seams(leg: np.ndarray) -> None:
    """Front cols 12-15 and back cols 0-3 lie in the same plane as the other leg's (the leg
    boxes overlap by 1.2 units and the left leg mirrors this texture), so make them
    symmetric: whichever leg is drawn, the pair shows the same picture."""
    leg[:, FRONT + 15] = leg[:, FRONT + 12]
    leg[:, FRONT + 14] = leg[:, FRONT + 13]
    leg[:, BACK + 0] = leg[:, BACK + 3]
    leg[:, BACK + 1] = leg[:, BACK + 2]


def ring(points, pad: int = PAD, width: int = 64) -> list:
    """A closed path round a strip `width` wide, from one turn of (x, y) points with x in
    [0, width), extended into the padding on both sides (padded coordinates for fold())."""
    pts = sorted(points)
    ext = [(x - width, y) for x, y in pts if x - width >= -pad] + pts + \
        [(x + width, y) for x, y in pts if x + width < width + pad]
    return [(x + pad, y) for x, y in ext]


def paint_tatters(a: np.ndarray, strips, y0: int = 0, seed: int = 0) -> None:
    """Tattered cloth strips hanging from row y0 over what is already painted (paint_skirt's
    strips without its lining): strips = [(x, width, end_row, palette, ember)]. Lit left
    edge, fold line, ragged notched end with a one-pixel shadow under it; ember strips fray
    into glowing orange."""
    rng = random.Random(seed)
    W = a.shape[1]
    for x, sw, end, pal, ember in strips:
        lo = 2 if pal is RUST else 1
        notch = rng.randrange(sw)
        for k in range(sw):
            xx = (x + k) % W
            e = end - (2 if k == notch else 0) - (1 if k % 3 == 2 else 0)
            for yy in range(y0, e + 1):
                i = lo + 1
                if k == 0:
                    i = lo + 2
                elif k == sw - 1:
                    i = lo - 1
                elif k == sw // 2 and (yy - y0) % 5 < 3:
                    i = lo
                if yy == y0:
                    i = max(i - 1, 0)
                c = pal[max(0, min(len(pal) - 1, i))]
                if ember and yy >= e - 1:
                    c = LEAF[4] if yy == e else LEAF[3]
                elif yy == e:
                    c = pal[max(0, lo - 1)]
                put(a, xx, yy, c)
            tint(a, xx, e + 1, BARK[0], 0.55)


def leg_tatters():
    """The cloth hanging from the waistband round the top of the leg: a rust flap at the front
    and back middle (the inner columns, which the other leg mirrors), short frayed tatters
    round the rest. The front flap stops above the knee guard's crown."""
    R, K = RUST, SOOT
    return [
        (FRONT + 8, 3, 8, R, False), (FRONT + 11, 3, 9, R, False),               # front flap
        (FRONT + 4, 4, 6, K, False), (FRONT + 1, 3, 4, R, False), (13, 3, 3, K, False),
        (INNER + 2, 4, 5, K, False), (INNER + 6, 4, 7, R, False), (INNER + 10, 3, 4, K, True),
        (INNER + 13, 3, 9, R, False),
        (BACK + 2, 3, 16, R, True), (BACK + 5, 3, 13, R, False), (BACK + 8, 3, 9, K, False),   # back flap
        (BACK + 11, 4, 5, R, False),
    ]


def paint_flap_tongue(a: np.ndarray, x: int, y0: int, y1: int) -> None:
    """One long rust tongue down the crease between the legs (the seam doubles it), frayed
    into embers at its tip."""
    for y in range(y0, y1 + 1):
        put(a, x, y, LEAF[4] if y == y1 else LEAF[3] if y == y1 - 1 else RUST[3] if y % 4 else RUST[2])
    tint(a, x, y1 + 1, BARK[0], 0.55)


def paint_guard(a: np.ndarray) -> np.ndarray:
    """The knee guard: a plate of warm, glow-lit bark (vertical ridges like the chestplate's
    carved front) over the front of the knee and round its outer side, framed by a course
    of lit bark blocks on top and a darker course at its foot, crowned with blocks and
    carved with the glowing face. Returns the carved mask."""
    (y0, y1), (x0, x1) = GUARD_ROWS, GUARD_COLS
    paint_bark(a, (x0, y0, x1, y1), seed=54, tone=1, warm=0.5)
    paint_shingles(a, (x0, y0, x1, y0 + 1), seed=55, course=2, tone=2, warm=0.5)
    paint_shingles(a, (x0, y1 - 2, x1, y1), seed=56, course=3, tone=1, warm=0.25)
    crown = np.zeros(a.shape[:2], bool)
    for m0, m1 in MERLONS:
        crown[y0 - 2:y0, m0:m1 + 1] = True
        for x in range(m0, m1 + 1):
            put(a, x, y0 - 2, BARK[6] if x < m1 else BARK[5])
            put(a, x, y0 - 1, BARK[5] if x == m0 else BARK[2] if x == m1 else BARK[4])
        tint(a, m1 + 1, y0 - 1, BARK[0], 0.45)
    for x in range(x0, x1 + 1):                             # top edge between the crown blocks
        if not crown[y0 - 1, x]:
            put(a, x, y0, BARK[6] if x % 3 else EMBER[3])
            tint(a, x, y0 - 1, BARK[0], 0.35)
    for y in range(y0, y1 + 1):
        put(a, x1, y, BARK[2])                              # the inner edge, away from the light
        put(a, x0, y, BARK[1])                              # the back edge of the knee cop
        tint(a, x0 - 1, y, BARK[0], 0.4)
        put(a, FRONT, y, BARK[5])                           # the plate's corner catching the light
        put(a, FRONT - 1, y, BARK[3])
    for x in range(x0, x1 + 1):
        put(a, x, y1, BARK[1])
        tint(a, x, y1 + 1, BARK[0], 0.5)
    return carve(a, KNEE_FACE, FACE_AT[0], FACE_AT[1], candle=KNEE_CANDLE, reach=10.0, halo=3, heat=2.2,
                 warmth=0.5)


# The garland under the knee guards, one turn round the leg: it swoops under each face,
# runs level across the crease between the legs (so the other leg's mirror continues it),
# hugs the foot of the guard's side and dips again at the back.
KNEE_GARLAND = [(0, 29.4), (4, 30.6), (8, 29.6), (12, 28.4), (16, 28.4), (19, 30.2), (22.5, 31.2), (25.5, 30.2),
                (27.5, 28.9), (29.5, 28.6), (33, 28.6), (37, 29.6), (41, 30.4), (45, 29.6), (48.5, 28.8),
                (50.5, 28.8), (53, 29.8), (56.5, 30.8), (60, 29.9)]


def paint_leg_strip() -> np.ndarray:
    W, H = 64, 48
    a = blank(W, H, BARK[2])
    paint_bark(a, (0, 0, W - 1, 31), seed=51, tone=-1)                       # thighs
    paint_shingles(a, (0, 29, W - 1, 43), seed=52, course=4, tone=0, warm=0.45)  # shin plates
    paint_shingles(a, (0, 44, W - 1, 47), seed=53, course=4, tone=1, warm=0.5)   # the cuff
    for x in range(W):
        put(a, x, 44, EMBER[3] if x % 7 == 3 else BARK[5])
        tint(a, x, 43, BARK[0], 0.65)
        put(a, x, 47, BARK[1])
        tint(a, x, 0, BARK[0], 0.85)                                         # under the waistband
    for y in range(GUARD_ROWS[0], H - 1):                  # the crease where the legs meet (the seam
        tint(a, FRONT + 13, y, BARK[0], 0.55)              # copies these columns to the other side)
        tint(a, BACK + 2, y, BARK[0], 0.55)
    paint_guard(a)
    paint_tatters(a, leg_tatters(), 0, seed=5)
    paint_flap_tongue(a, FRONT + 13, 9, 13)
    # Vines: one spirals down from the waistband over the front-outer corner, round the
    # outer side under the lantern and across the back into the garland under the knees;
    # two more cross on the outer side and the back of the shin.
    over = blank(W + 2 * PAD, H)
    P = PAD
    paint_vine(over, [(22 + P, -2), (18.5 + P, 4), (15.5 + P, 8.5), (12.5 + P, 12.5), (8.5 + P, 16),
                      (3.5 + P, 19), (-1.5 + P, 21.5), (-6 + P, 24)], 2.8, 0.4)
    paint_vine(over, [(60 + P, 22.6), (56.5 + P, 25.2), (53 + P, 27.6), (50.5 + P, 29.0)], 2.8, 1.7)
    paint_vine(over, ring(KNEE_GARLAND), 3.0, 0.9)
    paint_vine(over, [(1 + P, 32), (5 + P, 35.8), (9 + P, 39.5), (13 + P, 43.2), (15.5 + P, 46)], 2.4, 0.2)
    paint_vine(over, [(15 + P, 32), (11 + P, 35.8), (7 + P, 39.5), (3 + P, 43.2), (0.5 + P, 46)], 2.4, 2.4)
    paint_vine(over, [(BACK + 3.5 + P, 32), (BACK + 7 + P, 35.8), (BACK + 10.5 + P, 39.5), (BACK + 14 + P, 43.5)],
               2.4, 1.1)
    paint_vine(over, [(BACK + 14.5 + P, 32), (BACK + 11 + P, 35.8), (BACK + 7.5 + P, 39.5), (BACK + 4 + P, 43.5)],
               2.4, 3.0)
    for tx, ty, dx, dy, c in ((15 + P, 8, 1, -1, 1), (5 + P, 18, -1, 1, -1), (19 + P, 31, -1, 1, -1),
                              (BACK + 9 + P, 30, -1, -1, 1)):
        paint_tendril(over, tx, ty, dx, dy, c)
    composite(a, fold(over, P))
    # Leaves along the vines, mostly bright orange and on the outer side like the concept's.
    stamp_leaf(a, 13, 5, LEAF, "small", flip=True)
    stamp_leaf(a, 18, 6, RUST, "small", rot=1)
    stamp_leaf(a, 2, 14, LEAF, "maple_big")
    stamp_leaf(a, 3, 25, LEAF, "maple_big", flip=True)
    stamp_leaf(a, 17, 28, LEAF, "small", rot=1)
    stamp_leaf(a, 23, 31, RUST, "small", flip=True)
    stamp_leaf(a, 55, 11, RUST, "maple")
    stamp_leaf(a, 58, 21, LEAF, "small")
    stamp_leaf(a, 52, 25, LEAF, "maple", flip=True)
    stamp_leaf(a, 9, 38, LEAF, "maple", flip=True)
    stamp_leaf(a, 58, 38, LEAF, "small", rot=1)
    stamp_leaf(a, 20, 38, RUST, "small", flip=True)
    # The lantern hangs at the hip on the outer side, toward the back.
    stamp_lantern(a, 1, 0, chain=3)
    mirror_seams(a)
    return a


def paint_leg_sole() -> np.ndarray:
    a = blank(16, 16, BARK[1])
    paint_shingles(a, (0, 0, 15, 15), seed=57, course=4, tone=-1)
    return a


def paint_waist_strip() -> np.ndarray:
    """The waistband round the body box: a rim of bark blocks at uneven heights (some lit
    orange), a band of bark shingles with a glowing carved slit on each hip, vines dropping
    from the front corners and the tops of the rust flaps."""
    W, H = 96, 48
    band = blank(W, H, BARK[3])
    paint_bark(band, (0, WAIST_TOP + 3, W - 1, WAIST_FOOT), seed=61, tone=1, warm=0.5)
    keep = np.zeros((H, W), bool)
    keep[WAIST_TOP + 3:WAIST_FOOT + 1] = True
    rng = random.Random(62)
    x, k = 0, 0
    while x < W:                                            # the rim blocks
        bw = rng.choice((4, 5, 6, 6, 7))
        top = WAIST_TOP + rng.choice((0, 0, 1, 2))
        lit = k % 4 == 1 or (k % 7 == 5)
        for xx in range(x, min(x + bw, W)):
            for y in range(top, WAIST_TOP + 3):
                keep[y, xx] = True
                if xx == x + bw - 1:
                    c = BARK[1]
                elif y == top:
                    c = LEAF[3] if lit else BARK[6]
                elif lit:
                    c = EMBER[3] if xx == x else EMBER[2]
                else:
                    c = BARK[5] if xx == x else BARK[4]
                put(band, xx, y, c)
        x += bw
        k += 1
    for x in range(W):
        put(band, x, WAIST_TOP + 3, BARK[1])
        put(band, x, WAIST_FOOT, BARK[0])
        put(band, x, WAIST_FOOT - 1, BARK[2] if x % 5 else BARK[1])
    # Glowing slits on the hips (the body's side faces), like the concept's side view.
    slit = _mask(["..XXXXXX..", "XXXXXXXXXX", "..XXXXXX.."])
    for ox in (3, 51):
        carve(band, slit, ox, 38, candle=(5.0, 1.5), reach=5.0, halo=2, warmth=0.5)
    # Vines from the rim's front corners down to the legs, and the flaps' tops.
    over = blank(W + 2 * PAD, H)
    P = PAD
    paint_vine(over, [(17 + P, 33), (20 + P, 37), (23 + P, 41), (24.5 + P, 45)], 2.8, 0.6)
    paint_vine(over, [(46 + P, 33), (43 + P, 37), (40 + P, 41), (39.5 + P, 45)], 2.8, 2.1)
    paint_vine(over, [(63 + P, 35.0), (70 + P, 36.5), (76 + P, 35.5)], 2.6, 0.3)
    over = fold(over, P)
    keep |= over[..., 3] >= 200                             # vines may rise over the rim; shadows may not
    composite(band, over)
    for x0, x1 in ((25, 38), (73, 86)):
        for x in range(x0, x1 + 1):
            put(band, x, WAIST_FOOT - 1, RUST[1] if x in (x0, x1) else RUST[3] if x % 3 else RUST[2])
            put(band, x, WAIST_FOOT, RUST[2] if x % 3 else RUST[1])
    leaves = blank(W, H)
    for x, y, pal, kind, flip in ((13, 31, LEAF, "maple", False), (45, 31, RUST, "maple", True),
                                  (66, 33, LEAF, "small", False), (88, 32, LEAF, "small", True)):
        stamp_leaf(band, x, y, pal, kind, flip=flip)
        stamp_leaf(leaves, x, y, pal, kind, flip=flip, shadow=False)
    keep |= leaves[..., 3] > 0
    keep[WAIST_FOOT + 2:] = False                           # the legs' top rows show there
    for x in range(W):                                      # row 44 closes the sliver above the legs
        flap = 25 <= x <= 38 or 73 <= x <= 86
        put(band, x, WAIST_FOOT + 1, RUST[1] if flap else BARK[0])
        keep[WAIST_FOOT + 1, x] = True
    out = blank(W, H)
    out[keep] = band[keep]
    return out


def paint_worn() -> Image.Image:
    out = blank(64 * S, 32 * S)

    def place_at(name, arr, dx=0):
        x0, y0, x1, y1 = region(HUMANOID, name, S)
        out[y0:y1 + 1, x0:x1 + 1] = arr[:, dx:dx + x1 - x0 + 1]

    waist = paint_waist_strip()
    for name, dx in (("body_right", 0), ("body_front", 16), ("body_left", 48), ("body_back", 64)):
        place_at(name, waist, dx)
    place_at("body_bottom", blank(32, 16, SOOT[0]))
    leg = paint_leg_strip()
    for name, dx in (("leg_outer", 0), ("leg_front", 16), ("leg_inner", 32), ("leg_back", 48)):
        place_at(name, leg, dx)
    place_at("leg_top", blank(16, 16, BARK[1]))
    place_at("leg_sole", paint_leg_sole())
    return to_image(out)


# ======================================================================================
# Item textures (32 px = 2 texels per model unit unless noted)
# ======================================================================================

def tex_bark(seed: int, tone: int = 0, warm: float = 0.0) -> Image.Image:
    a = blank(32, 32, BARK[3])
    paint_bark(a, (0, 0, 31, 31), seed=seed, tone=tone, warm=warm)
    return to_image(a)


def tex_shingles(seed: int, tone: int = 0, warm: float = 0.0) -> Image.Image:
    a = blank(32, 32, BARK[3])
    paint_shingles(a, (0, 0, 31, 31), seed=seed, course=4, tone=tone, warm=warm)
    return to_image(a)


def tex_ember() -> Image.Image:
    """Rim blocks lit orange by the candles inside."""
    a = blank(16, 16, EMBER[2])
    rng = random.Random(41)
    for y in range(16):
        for x in range(16):
            r = rng.random()
            c = EMBER[3] if r < 0.35 else EMBER[1] if r < 0.5 else EMBER[2]
            if y < 2:
                c = LEAF[3] if (x + y) % 3 else LEAF[4]
            a[y, x] = _c(c)
    return to_image(a)


def tex_waist() -> Image.Image:
    """The waistband's sides: courses of bark blocks warmed by the candles inside."""
    a = blank(32, 32, BARK[3])
    paint_shingles(a, (0, 0, 31, 31), seed=9, course=4, tone=1, warm=0.6)
    return to_image(a)


KNEE_W, KNEE_H = 13, 14     # the knee plate's texels (64 px texture, 4 per uv unit)
KNEE_FACE_AT = (1, 2)       # the face's top-left on the plate


def tex_knee() -> Image.Image:
    """The knee plate, like the worn guard: warm glow-lit bark framed by a lit course on top
    and a dark one at its foot, with the face cut right through (transparent) and the
    candle's halo on the bark round the cuts."""
    a = blank(64, 64)
    p = blank(KNEE_W, KNEE_H, BARK[3])
    paint_bark(p, (0, 0, KNEE_W - 1, KNEE_H - 1), seed=71, tone=0, warm=0.35)
    paint_shingles(p, (0, KNEE_H - 2, KNEE_W - 1, KNEE_H - 1), seed=72, course=2, tone=1, warm=0.2)
    for x in range(KNEE_W):
        put(p, x, 0, BARK[6] if x % 4 else EMBER[3])
        put(p, x, KNEE_H - 1, BARK[1])
    for y in range(1, KNEE_H - 1):
        put(p, 0, y, BARK[5])
        put(p, KNEE_W - 1, y, BARK[2])
    full = carve(p, KNEE_FACE, KNEE_FACE_AT[0], KNEE_FACE_AT[1], candle=KNEE_CANDLE, reach=6.0, halo=2,
                 warmth=0.45)
    p[full] = 0
    a[:KNEE_H, :KNEE_W] = p
    return to_image(a)


def tex_knee_wall() -> Image.Image:
    """The shadowed top wall just behind every cut (the rest of the hole shows the candle)."""
    a = blank(64, 64)
    full = np.zeros((64, 64), bool)
    fx, fy = KNEE_FACE_AT
    full[fy:fy + KNEE_FACE.shape[0], fx:fx + KNEE_FACE.shape[1]] = KNEE_FACE
    top = full & ~shift(full, 0, 1)
    a[top] = _c(GLOW[1])
    side = full & ~top & (~shift(full, 1, 0) | ~shift(full, -1, 0))
    a[side & ~shift(full, 0, 2)] = _c(GLOW[2])
    return to_image(a)


def flicker(t: float, phase: float = 0.0) -> float:
    """Candle flicker 0..1 over one loop (periodic, a little irregular)."""
    v = (0.55 * math.sin(2 * math.pi * (t + phase)) + 0.3 * math.sin(2 * math.pi * (3 * t + phase) + 1.3)
         + 0.15 * math.sin(2 * math.pi * (5 * t + 2 * phase) + 0.4))
    return 0.5 + 0.5 * v


def tex_knee_glow_frame(t: float, phase: float) -> Image.Image:
    """The candle-lit back wall behind a knee face (stretched over the plate): hottest low in
    the middle behind the grin, cooling to orange behind the eyes; it sways and breathes."""
    f = flicker(t, phase)
    cx = 8.0 + 0.7 * math.sin(2 * math.pi * (2 * t + phase))
    cy = 10.6 + 0.6 * math.sin(2 * math.pi * (t + phase) + 1.0)
    a = blank(16, 16)
    for y in range(16):
        for x in range(16):
            d = math.hypot((x + 0.5 - cx) / 1.3, y + 0.5 - cy)
            heat = (1 - d / 11.0) * (0.74 + 0.26 * f)
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


def tex_waist_top() -> Image.Image:
    """The top of the waistband, 24 x 13 texels: a bark rim round the dark opening."""
    a = blank(32, 32, SOOT[0])
    paint_shingles(a, (0, 0, 31, 31), seed=81, course=4, tone=1, warm=0.3)
    for y in range(2, 11):
        for x in range(2, 22):
            d = min(x - 2, 21 - x, y - 2, 10 - y)
            put(a, x, y, SOOT[0] if d > 0 else SOOT[1])
    for x in range(2, 22):
        put(a, x, 2, BARK[0])
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
    save_layer(paint_worn(), "humanoid_leggings")
    save(tex_bark(1), "bark")
    save(tex_bark(3, tone=-1), "bark_dark")
    save(tex_shingles(2), "shingles")
    save(tex_shingles(4, tone=1, warm=0.45), "shingles_lit")
    save(tex_ember(), "ember")
    save(tex_waist(), "waist")
    save(tex_knee(), "knee")
    save(tex_knee_wall(), "knee_wall")
    save_animation([tex_knee_glow_frame(i / 12, 0.15) for i in range(12)], "knee_glow", frametime=2,
                   interpolate=True)
    save_animation([tex_knee_glow_frame(i / 12, 0.6) for i in range(12)], "knee_glow_b", frametime=2,
                   interpolate=True)
    save(tex_inside(), "inside")
    save(tex_waist_top(), "waist_top")
    save(tex_vine(), "vine")
    save(tex_leaf(LEAF), "leaf")
    save(tex_leaf(RUST), "leaf_rust")
    save(tex_cloth(), "cloth")
    save(tex_iron(), "iron")
    save_animation([tex_lantern_frame(i / 8, 0.0) for i in range(8)], "lantern", frametime=3, interpolate=True)
    save_animation([tex_lantern_frame(i / 8, 0.37) for i in range(8)], "lantern_b", frametime=3,
                   interpolate=True)


# ======================================================================================
# Item model. Frame: the front (the knee faces) looks north (-Z), +X is the wearer's
# right, up is +Y; centred on x = 8, z = 8. One leg is built at x = LCX (the wearer's left,
# -X side) and mirrored across x = 8. Proportions follow the concept: the waistband and
# hips take the top third, the legs stand apart below the crotch, and the knee guards sit
# a little below the middle of the legs.
# ======================================================================================

LCX, HW = 4.65, 2.5             # the built leg's centre and half width (the legs stand 1.7 apart)
LZ0, LZ1 = 5.55, 10.45          # leg front / back
WX0, WX1, WY0, WY1, WZ0, WZ1 = 2.0, 14.0, 19.4, 22.0, 4.75, 11.25     # waistband
HX0, HX1, HY0, HY1, HZ0, HZ1 = 2.05, 13.95, 14.8, 19.5, 5.2, 10.8     # hips (the crotch at HY0)
KNEE_Y = (5.0, 10.6)            # the knee plate's bottom and top (the crown rises above)
PLATE_Z = 4.55                  # the knee plate's face
SHIN_Y = (1.4, 5.2)


def around(t: float, y: float, out: float = 0.35, cx: float = LCX, hw: float = HW, z0: float = LZ0,
           z1: float = LZ1) -> tuple:
    """A point on a rounded square round the leg at turn t (0 the front middle, 0.25 the
    outer side, 0.5 the back, 0.75 the inner side), `out` units off its surface."""
    a = 2 * math.pi * t
    dx, dz = -math.sin(a), -math.cos(a)
    k = 1.0 / (abs(dx) ** 4 + abs(dz) ** 4) ** 0.25
    hd = (z1 - z0) / 2
    return (round(cx + dx * k * (hw + out), 3), round(y, 3), round((z0 + z1) / 2 + dz * k * (hd + out), 3))


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


def strip(x: float, z: float, width: float, length: float, design: int, flare: float, facing: str,
          lean: float = 0.0, top: float = WY0 + 0.1) -> dict:
    """One tattered cloth strip hanging from the waistband, flared outward by `flare`
    degrees. facing is the side it hangs on (north, south, east or west); x/z is the
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


def waist() -> list[dict]:
    """The waistband: a thick bark band whose top shows the dark opening, a rim of bark blocks
    at uneven heights (some lit orange), and a glowing slit on each hip."""
    parts = [box((WX0, WY0, WZ0), (WX1, WY1, WZ1), "waist",
                 faces={"up": ("waist_top", [0, 0, WX1 - WX0, WZ1 - WZ0]), "down": "bark_dark"})]
    rim = [   # x0, x1, z0, z1, height, texture: front row, back row, then the sides
        (2.0, 4.4, 4.75, 5.75, 0.8, "ember"), (4.4, 6.1, 4.75, 5.75, 0.45, "shingles"),
        (6.1, 8.6, 4.75, 5.75, 0.7, "shingles"), (8.6, 10.2, 4.75, 5.75, 0.4, "ember"),
        (10.2, 12.0, 4.75, 5.75, 0.75, "shingles"), (12.0, 14.0, 4.75, 5.75, 0.55, "ember"),
        (2.0, 4.0, 10.25, 11.25, 0.55, "shingles"), (4.0, 6.6, 10.25, 11.25, 0.8, "ember"),
        (6.6, 9.2, 10.25, 11.25, 0.45, "shingles"), (9.2, 11.4, 10.25, 11.25, 0.75, "shingles"),
        (11.4, 14.0, 10.25, 11.25, 0.5, "ember"),
        (2.0, 3.0, 5.75, 8.2, 0.5, "shingles"), (2.0, 3.0, 8.2, 10.25, 0.7, "ember"),
        (13.0, 14.0, 5.75, 7.8, 0.65, "shingles"), (13.0, 14.0, 7.8, 10.25, 0.45, "ember"),
    ]
    for x0, x1, z0, z1, h, tex in rim:
        up = "ember" if tex == "ember" else "shingles_lit"
        parts.append(box((x0, WY1, z0), (x1, WY1 + h, z1), tex, faces={"up": up}, skip=("down",)))
    for x in (WX0 - 0.06, WX1):                                  # the glowing slits on the hips
        parts.append(box((x, 20.3, 6.5), (x + 0.06, 20.95, 9.5), "knee_glow",
                         faces={"east": ("knee_glow", [0, 3, 16, 13]), "west": ("knee_glow", [0, 3, 16, 13])},
                         skip=("north", "south", "up", "down"), glow=15, shade=False))
    return parts


def hips() -> list[dict]:
    """The hips, and a shadow panel recessed between the legs so the gap reads dark, as in the
    concept, instead of showing the inventory slot through it."""
    gap = (LCX + HW, 16 - LCX - HW)
    return [box((HX0, HY0, HZ0), (HX1, HY1, HZ1), "bark_dark", faces={"down": "inside"}, skip=("up",)),
            box((gap[0], 0.6, 7.8), (gap[1], HY0, 8.2), "inside", skip=("east", "west", "up"))]


def knee_uv() -> list[float]:
    return [0.0, 0.0, KNEE_W / 4, KNEE_H / 4]


def leg() -> list[dict]:
    """The leg on the -X side: dark bark thigh, the carved knee guard, bark shin, blocky cuff,
    and bark knobs that break up its outline."""
    cx = LCX
    x0, x1 = cx - HW, cx + HW
    parts = [
        box((x0, SHIN_Y[1] - 0.2, LZ0), (x1, HY0 + 0.6, LZ1), "bark_dark", skip=("up",)),   # thigh
        box((x0 - 0.15, SHIN_Y[0], LZ0 - 0.15), (x1 + 0.15, SHIN_Y[1], LZ1 + 0.15), "shingles_lit"),   # shin
        box((x0 - 0.35, 0.0, LZ0 - 0.35), (x1 + 0.35, SHIN_Y[0] + 0.15, LZ1 + 0.35), "shingles_lit",
            faces={"down": "bark_dark"}),                                                    # cuff
    ]
    # The knee guard: the carved plate, the cut walls and the candle light behind it.
    k0, k1 = KNEE_Y
    px0, px1 = cx - 2.6, cx + 2.6
    parts.append(box((px0, k0, PLATE_Z), (px1, k1, LZ0 + 0.05), "shingles_lit",
                     faces={"north": ("knee", knee_uv())}, skip=("south",)))
    for z, tex in ((PLATE_Z + 0.22, "knee_wall"), (PLATE_Z + 0.5, "knee_glow")):
        uv = knee_uv() if tex == "knee_wall" else [0, 0, 16, 16]
        parts.append(box((px0 + 0.05, k0 + 0.05, z), (px1 - 0.05, k1 - 0.05, z + 0.04), tex,
                         faces={"north": (tex, uv)}, skip=("south", "east", "west", "up", "down"), glow=15,
                         shade=False))
    for mx0, mx1, h in ((px0, px0 + 1.45, 0.85), (cx - 0.7, cx + 0.7, 0.85), (px1 - 1.45, px1, 0.85)):
        parts.append(box((mx0, k1, PLATE_Z), (mx1, k1 + h, LZ0 + 0.05), "shingles",
                         faces={"up": "shingles_lit", "north": "shingles_lit"}, skip=("south",)))
    # Bark knobs on the outer side and back.
    for (bx0, by0, bz0), (bx1, by1, bz1) in (((x0 - 0.35, 12.2, 7.0), (x0 + 0.1, 13.9, 8.6)),
                                             ((x0 - 0.3, 6.4, 8.4), (x0 + 0.1, 7.6, 9.9)),
                                             ((x0 + 1.0, 11.0, LZ1 - 0.1), (x0 + 2.3, 12.1, LZ1 + 0.3))):
        parts.append(box((bx0, by0, bz0), (bx1, by1, bz1), "bark", skip=("east",)))
    return parts


def vines() -> list[dict]:
    """Vines on the -X leg: one spiralling down from the waistband's front corner round the
    thigh into a garland under the knee guard, an X on the shin's outer side and back, and
    tendril curls."""
    parts = []
    spiral = [(4.9, 19.7, 4.55), (3.5, 17.8, 4.75), (2.25, 16.0, 4.95)]    # across the front of the hip
    for t, y in ((0.17, 14.6), (0.25, 13.4), (0.33, 12.3), (0.42, 11.3), (0.52, 10.3), (0.6, 9.2), (0.55, 7.9),
                 (0.46, 6.6)):                          # round the outer side, then an S down the back
        spiral.append(around(t, y, 0.38))
    spiral.append(around(0.42, 5.3, 0.42))
    parts += vine_path(spiral, 0.9)
    band = [around(i / 10, 4.75 - 0.45 * math.cos(2 * math.pi * i / 10) + 0.18 * (i % 2), 0.45) for i in range(11)]
    parts += vine_path(band, 0.95)
    parts += vine_path([around(0.19, 4.1, 0.3), around(0.25, 2.8, 0.3), around(0.31, 1.5, 0.3)], 0.7)
    parts += vine_path([around(0.31, 4.1, 0.3), around(0.25, 2.8, 0.3), around(0.19, 1.5, 0.3)], 0.7)
    parts += vine_path([around(0.44, 4.1, 0.3), around(0.5, 2.8, 0.3), around(0.56, 1.5, 0.3)], 0.7)
    parts += vine_path([around(0.56, 4.1, 0.3), around(0.5, 2.8, 0.3), around(0.44, 1.5, 0.3)], 0.7)
    for p in ([(3.5, 17.8, 4.75), (3.0, 18.6, 4.3), (2.5, 18.4, 4.1)],
              [around(0.05, 4.4, 0.45), (5.3, 3.5, 4.6), (4.8, 3.0, 4.55)],
              [around(0.25, 13.1, 0.38), (1.3, 13.8, 7.6), (1.25, 14.4, 7.1)]):
        parts += vine_path(p, 0.4)
    return parts


def leaves() -> list[dict]:
    L, R = "leaf", "leaf_rust"
    spots = [   # x, y, z, size, texture, Euler x/y/z
        (2.6, 21.3, 4.25, 3.0, L, 10, 25, -20),        # on the waistband's front corner
        (4.2, 18.6, 4.2, 2.3, R, 0, 15, 35),           # on the vine across the hip
        (1.2, 17.6, 5.0, 3.0, L, 0, 40, 25),           # out from the hip, in front of the lantern
        (1.5, 15.3, 4.5, 2.6, L, 0, 30, -30),          # where the vine rounds the hip's corner
        (0.95, 13.4, 6.6, 3.3, L, 0, 50, -15),         # outer thigh
        (1.4, 12.6, 5.0, 2.6, L, 0, 20, 40),           # front-outer edge of the thigh
        (1.2, 10.9, 9.6, 2.6, R, 0, 70, 20),           # outer thigh, lower
        (3.6, 12.6, 11.3, 2.7, R, 0, 180, 20),         # back of the thigh
        (1.0, 8.2, 7.4, 2.7, L, 0, 60, 35),            # outer side, by the knee guard
        (1.05, 4.7, 5.8, 3.3, L, 0, 35, 30),           # on the knee garland, outer side
        (5.4, 4.1, 4.4, 2.0, R, 0, 0, 30),             # on the knee garland, front
        (1.1, 1.9, 8.6, 2.8, L, 0, 70, 10),            # by the cuff
        (2.0, 1.2, 5.0, 2.2, R, 0, 35, -20),           # front of the cuff, outer corner
        (3.7, 5.0, 11.4, 2.4, L, 0, 180, -25),         # garland, back
    ]
    return [leaf3d(x, y, z, s, t, rx, ry, rz) for x, y, z, s, t, rx, ry, rz in spots]


def cloth() -> list[dict]:
    """Rust flaps hanging from the waistband down the front and back middle, and a short
    frayed strip at each hip."""
    parts = []
    front = [(6.4, 6.0, 0, 3), (8.0, 7.6, 3, -2), (9.6, 6.4, 2, 2)]
    for i, (x, length, design, lean) in enumerate(front):
        parts.append(strip(x, HZ0 - 0.25 + 0.12 * (i % 2), 1.7, length, design, 6 + 2 * (i % 2), "north", lean))
    back = [(8.6, 8.8, 6, 2), (10.2, 10.4, 8, -2), (11.8, 9.2, 7, -3)]     # off-centre, as in the concept
    for i, (x, length, design, lean) in enumerate(back):
        parts.append(strip(x, HZ1 + 0.25 - 0.12 * (i % 2), 1.7, length, design, 5 + 2 * (i % 2), "south", lean))
    for facing, x in (("west", HX0 - 0.2), ("east", HX1 + 0.2)):
        parts.append(strip(x, 9.8, 1.6, 4.6, 1, 8, facing))
    return parts


def build() -> list[dict]:
    one = leg() + vines() + leaves()
    other = mirror(one, "x", 8.0)
    for e in other:                              # the other knee flickers out of step
        for face in e["faces"].values():
            if face["texture"] == "#knee_glow":
                face["texture"] = "#knee_glow_b"
    parts = waist() + hips() + one + other + cloth()
    parts += lantern(0.85, WY0 + 0.1, 7.9, "lantern")
    parts += lantern(15.15, WY0 + 0.1, 7.9, "lantern_b")
    return parts


def models() -> dict:
    parts = build()
    d = display(KIND, parts, gui_rotation=(10, 166, 0), gui_span=15.6)
    centre = (8.0, 11.0, 8.0)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, (2.6, 21.4, 8.0), "fist", 0.4)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.4, 0, -1)}, centre, (0.52, -0.3, -0.95), 0.4,
                                       pose=None)
    return {"main": model(parts, d)}
