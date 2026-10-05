"""Haunted Hollow Boots: the October set's boots (with the Haunted Hollow Chestplate, the
leggings and the Jack-o'-Lantern Mask).

Chunky charred-bark boots: a blocky collar with a band of candle light glowing out from under
it, olive vines wrapping each shaft with orange and rust maple leaves, a small glowing pumpkin
lantern hanging on each boot, a rounded stepped toe cap with a glowing T cracked into its top,
and a second glowing band round the foot over a thick dark sole.

The PALETTE block and the PAINTERS section are copied from the chestplate (the set's pilot)
so bark, vines, leaves, lanterns and glow match it exactly (stamp_lantern also takes the
boots' smaller sprite, and leaf3d a shade flag; both default to the chestplate's behaviour).

Worn (humanoid layer, 4x = 256x128): painted flat on the leg boxes, rows 25-47 of each leg
strip (see the worn layer notes for the rows and the overlapping columns). The collar's top
row sits at y 18.29, just under the bottom of the leggings' knee faces (their row 24 ends at
y 18.27), so the whole knee face shows over the boots; its lit top row makes a clean edge.
Each boot's front carries the lantern over the toe's T, like the concept's front view.

Item: the pair standing side by side as in the concept, toes toward -Z: two copies of one
boot (the concept's two boots match rather than mirror), each with a vine crossing its front
and a lantern hanging from it on the front, and the -X boot with a second lantern on its side,
where the inventory icon shows it. Glowing parts (both bands, the T on each toe, the
lanterns) emit light; the bands breathe and the lanterns flicker (slow interpolated
animations).
"""
from __future__ import annotations

import math
import random

import numpy as np
from PIL import Image

from art.kit import bar, bounds, box, display, model, move, place, rgba, save, save_animation, save_layer, \
    turn

ID = "haunted_hollow_boots"
NAME = "Haunted Hollow Boots"
KIND = "boots"
COUNTERPART = "item/netherite_boots"

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
# PAINTERS (copied from the chestplate; numpy float RGBA arrays 0..255, x right, y down,
# boxes inclusive)
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


def stamp_lantern(a: np.ndarray, x: int, y: int, chain: int = 3, rows=None, chain_x: int = 3) -> None:
    """A glowing pumpkin lantern hanging from a short chain, top-left at (x, y), with a warm
    halo on what is behind it. (rows/chain_x: another sprite in LANTERN's keys, e.g. the
    boots' smaller LANTERN_SMALL; the defaults are the chestplate's lantern.)"""
    rows = rows or LANTERN
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
        put(a, x + chain_x, y + j, IRON[2] if j % 2 else IRON[4])
    for j in range(h):
        for i in range(w):
            ch = rows[j][i]
            if ch != ".":
                put(a, x + i, y + chain + j, _LANTERN_KEYS[ch])


def flicker(t: float, phase: float = 0.0) -> float:
    """Candle flicker 0..1 over one loop (periodic, a little irregular)."""
    v = (0.55 * math.sin(2 * math.pi * (t + phase)) + 0.3 * math.sin(2 * math.pi * (3 * t + phase) + 1.3)
         + 0.15 * math.sin(2 * math.pi * (5 * t + 2 * phase) + 0.4))
    return 0.5 + 0.5 * v


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
           ry: float = 0.0, rz: float = 0.0, shade: bool = True) -> dict:
    """A flat autumn leaf (both sides textured) centred on (x, y, z), facing north before
    the Euler turn (rx, ry, rz). (shade=False: the boots' leaves hang on upright faces, out
    of the light that the chestplate's leaves catch lying on its pauldrons, so they skip the
    directional shading to read as the same bright orange.)"""
    h = size / 2
    e = box((x - h, y - h, z - 0.04), (x + h, y + h, z + 0.04), tex,
            faces={"north": (tex, [0, 0, 16, 16]), "south": (tex, [16, 0, 0, 16])},
            skip=("east", "west", "up", "down"), shade=shade)
    return turn(e, x=rx, y=ry, z=rz, origin=(x, y, z))


# ======================================================================================
# Worn layer (humanoid, 4x). Each leg box is painted as one strip of its four sides that
# wraps: outer (x 0-15) | front (16-31) | inner (32-47) | back (48-63), rows 0-47 (layer
# rows 80-127). Boot boxes are inflated by 1, so the 48 rows cover 14 units: the floor
# cuts each leg at row 44.6, and rows 45-47 sit in the block under the player.
#
# The two leg boxes overlap by 2.2 units at their inner edges, coplanar at the front and
# back, so whichever box wins there shows: front columns 26-31 lie on the other boot's
# front columns 31-26 (pairs 26/31, 27/30, 28/29) and back columns 48-53 on its 53-48. Those
# columns are made symmetric (mirror_seams), so the meeting line between the boots reads the
# same either way. Each boot's visible front is columns 16-28, centred on column 22, which is
# where the lantern and the toe's T sit, not at the face's own middle. The inner faces
# only show when the legs swing apart.
#
# Rows: collar 25-26 (its lit top row is the clean edge over the leggings: the leggings'
# knee faces end at their row 24, y 18.27, just above it), shadow lip 27, cuff band 28-29,
# warm rind 30; shaft 30-39 with the vines, leaves and the lantern (front, columns 19-24,
# rows 30-36); the toe cap's lit crown arcs from row 37 (middle) to 39 (corners) with the
# T on rows 38-40; lip 40, toe band 41-42, rind 43, sole 44 (45-47 under the floor).
# ======================================================================================

TOP = 25                # the collar's top row
CUFF = (28, 29)         # the glowing band under the collar
TOE_BAND = (41, 42)     # the glowing band round the foot
MID = 22                # the visual middle column of each boot's front
PAD = 12

LANTERN_SMALL = [   # the boots' lantern: the chestplate's design at 6 x 7, same keys
    "..I...",
    ".iiii.",
    "gwggwG",
    "gggggG",
    "gywywG",
    "oggGgo",
    ".iiii.",
]

# The toe cap's glowing T (5 x 3): a crack across the top of the cap and one running down
# from it into the toe band.
TOE_T = np.array([[c == 2 or r == 0 for c in range(5)] for r in range(3)])
TOE_T_AT = (MID - 2, 38)


def dome_top(c: int) -> int:
    """Top row of the toe cap at front column c: an arc, highest in the boot's middle."""
    c = min(c, 57 - c)                       # past the meeting line the other boot's cap rises
    return 37 + int(round(((c - MID - 0.5) / 6.5) ** 2 * 2.2))


def light_band(a: np.ndarray, y0: int, y1: int, candles, reach: float = 10.0, halo: int = 3,
               heat: float = 1.0, warmth: float = 0.45) -> np.ndarray:
    """A band of candle light glowing out all round the boot between rows y0 and y1, cut like
    carve() (black lip over it, warm rind under it, glow-lit bark round it), with a candle
    behind each face so every face has its own hot middle."""
    h, w = a.shape[:2]
    full = np.zeros((h, w), bool)
    full[y0:y1 + 1] = True
    lut = np.stack([_c(c) for c in GLOW])
    idx = np.max([candle_light(full, cx, cy, reach, heat) for cx, cy in candles], axis=0)
    dist = steps_to(full, halo + 1)
    for k in range(halo, 1, -1):
        ring = dist == k
        amt = warmth * (1 - (k - 2) / max(1, halo - 1))
        col = _c(EMBER[min(3, halo - k)])
        a[ring, :3] = a[ring, :3] * (1 - amt) + col[:3] * amt
    a[~full & shift(full, 0, -1)] = _c(BARK[0])
    a[~full & shift(full, 0, 1)] = _c(EMBER[1])
    a[full] = lut[idx][full]
    return full


def periodic(points, pad: int = PAD, width: int = 64):
    """A closed path round the strip as a polyline in padded coordinates: the control points
    (x 0..64) repeated a turn before and after, cut to the padded strip."""
    pts = sorted(points)
    run = [(x - width, y) for x, y in pts] + pts + [(x + width, y) for x, y in pts]
    return [(x + pad, y) for x, y in run if -pad - 6 <= x <= width + pad + 6]


def paint_toe_cap(a: np.ndarray) -> None:
    """The rounded toe cap: on the front an arc of lit bark over a crease, filled with dark
    plates; on the outer and inner faces its side slopes back from the front edge."""
    rng = random.Random(71)
    for c in range(16, 32):
        top = dome_top(c)
        put(a, c, top - 1, BARK[0])                                    # crease under the shaft
        put(a, c, top, BARK[6] if rng.random() < 0.35 else BARK[5])    # the lit crown
        for y in range(top + 1, TOE_BAND[0] - 1):
            put(a, c, y, BARK[4] if y == top + 1 else BARK[3])
        if c in (16, 31):
            for y in range(top, TOE_BAND[0] - 1):                      # rounding off at the corners
                tint(a, c, y, BARK[0], 0.45)
    for c0, sign in ((15, -1), (32, 1)):                                # outer / inner side
        for k, row in enumerate((39, 39, 39, 40)):
            c = c0 + sign * k
            put(a, c, row - 1, BARK[0])
            if row < TOE_BAND[0] - 1:
                put(a, c, row, BARK[5])


def paint_leg_strip() -> np.ndarray:
    W, H = 64, 48
    a = blank(W, H)
    boot = blank(W, H, BARK[3])
    paint_bark(boot, (0, TOP, W - 1, H - 1), seed=61)
    a[TOP:] = boot[TOP:]
    # The collar: chunky bark blocks with a lit top row, the clean edge over the leggings.
    paint_shingles(a, (0, TOP, W - 1, TOP + 1), seed=62, course=2, tone=1, warm=0.2)
    for x in range(W):
        put(a, x, TOP, BARK[6] if x % 7 in (2, 3) else BARK[5])
    paint_toe_cap(a)
    # The sole under the toe band.
    for x in range(W):
        for y in range(TOE_BAND[1] + 2, H):
            put(a, x, y, (SOOT[2] if x % 4 else SOOT[1]) if y == TOE_BAND[1] + 2 else SOOT[1] if y < 46 else SOOT[0])
    # The two glowing bands, a candle behind the middle of each face.
    for y0, y1 in (CUFF, TOE_BAND):
        cy = (y0 + y1 + 1) / 2
        light_band(a, y0, y1, [(8, cy), (MID + 0.5, cy), (40, cy), (57, cy)], reach=10.0, halo=2,
                   heat=1.5, warmth=0.4)
    # The T cracked into the toe cap; its stem runs down into the toe band, so the band's
    # rows are kept clear of the cut's lip and halo.
    keep = a[TOE_BAND[0]:].copy()
    carve(a, TOE_T, TOE_T_AT[0], TOE_T_AT[1], candle=(2.5, 1.5), reach=4.0, halo=2, warmth=0.5)
    a[TOE_BAND[0]:] = keep
    # Vines: one runs from the front-outer corner down behind the lantern to the inner side,
    # round the back and along the outer side under the band; two diagonals run down the
    # outer side and the back to the foot.
    over = blank(W + 2 * PAD, H)
    P = PAD
    ring = [(0, 31.6), (7, 31.4), (15.5, 30.8), (21, 33.2), (26, 36.0), (31.5, 36.0), (36, 35.4),
            (42, 34.0), (47.5, 32.8), (53.5, 32.8), (58, 32.2)]
    paint_vine(over, periodic(ring), 2.4, 0.0)
    paint_vine(over, [(1.5 + P, 31.8), (5.5 + P, 34.6), (10 + P, 37.4), (13.6 + P, 38.6)], 2.2, 1.6)
    paint_vine(over, [(61.5 + P, 32.2), (58.5 + P, 35.2), (56.0 + P, 38.4)], 2.2, 0.7)
    for tx, ty, dx, dy, c in ((16 + P, 33, -1, 1, -1), (9 + P, 31, 1, -1, -1), (44 + P, 35, 1, 1, 1),
                              (60 + P, 36, -1, 1, -1)):
        paint_tendril(over, tx, ty, dx, dy, c)
    composite(a, fold(over, P))
    # Leaves: a big maple on the outer side and the back, small ones low on the outer side and
    # on the inner side.
    stamp_leaf(a, 4, 32, LEAF, "maple")
    stamp_leaf(a, 0, 36, RUST, "small", flip=True)
    stamp_leaf(a, 55, 32, LEAF, "maple", flip=True)
    stamp_leaf(a, 36, 32, RUST, "small")
    # The lantern hangs in the middle of the boot's front, over the toe's T.
    stamp_lantern(a, MID - 3, 30, chain=0, rows=LANTERN_SMALL, chain_x=2)
    mirror_seams(a)
    return a


def mirror_seams(a: np.ndarray) -> None:
    """Make the overlapping columns symmetric about the line where the boots meet (front
    26-31, back 48-53; see the notes above), keeping the side that belongs to this boot."""
    for k in range(3):
        a[:, 31 - k] = a[:, 26 + k]
        a[:, 48 + k] = a[:, 53 - k]


def paint_sole() -> np.ndarray:
    """The underside: dark soot with a worn tread."""
    a = blank(16, 16, SOOT[1])
    for y in range(16):
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                put(a, x, y, SOOT[2])
            elif y % 4 == 2 and 2 <= x <= 13:
                put(a, x, y, SOOT[0])
            elif y % 4 == 1 and 2 <= x <= 13 and (x + y) % 3:
                put(a, x, y, SOOT[2])
    return a


def paint_worn() -> Image.Image:
    out = blank(64 * S, 32 * S)
    out[80:128, 0:64] = paint_leg_strip()         # leg_outer | leg_front | leg_inner | leg_back
    out[64:80, 32:48] = paint_sole()              # leg_sole (the leg's top stays bare)
    return to_image(out)


# ======================================================================================
# Item textures. Most are projected (see pbox): 32 px, 2 texels per unit, texel row
# r <-> y = 16 - r / 2 on the side faces and (u, v) = (x + 8, z + 8) on top faces, so the
# halos painted at the bands' heights line up on every box. Boot layout (local units, y up):
# sole 0-1.5, toe band 1.5-2.5, toe plates 1.5-4.75 and the cap 4.75-5.5, shaft 1.5-13.5,
# cuff band 12.5-13.5, collar 13.5-15.5.
# ======================================================================================

def tex_boot() -> Image.Image:
    """The shaft: charred bark, warmed under the cuff band (rows 7-10) and over the toe
    band (rows 24-26)."""
    a = blank(32, 32, BARK[3])
    paint_bark(a, (0, 0, 31, 31), seed=5)
    for r, k, col in ((7, 0.6, EMBER[1]), (8, 0.4, EMBER[2]), (9, 0.22, EMBER[2]), (10, 0.1, EMBER[2]),
                      (26, 0.5, EMBER[2]), (25, 0.28, EMBER[2]), (24, 0.12, EMBER[2])):
        for x in range(32):
            tint(a, x, r, col, k)
    return to_image(a)


def tex_toe() -> Image.Image:
    """The toe cap's shell: dark bark blocks, warmed over the toe band (rows 24-26) and along
    the cap's top edge (rows 21-22), where the T glows."""
    a = blank(32, 32, BARK[3])
    rng = random.Random(8)
    for by in range(0, 32, 2):
        for bx in range(-(by // 2 % 2), 32, 3):
            k = rng.choice((2, 3, 3, 3, 4, 4))
            for y in range(by, by + 2):
                for x in range(max(0, bx), min(32, bx + 3)):
                    put(a, x, y, BARK[k])
            put(a, min(31, max(0, bx + 2)), by + 1, BARK[1])
            if k == 4:
                put(a, max(0, bx), by, BARK[5])
    for r, k in ((26, 0.5), (25, 0.28), (24, 0.12), (21, 0.4), (22, 0.2)):
        for x in range(32):
            tint(a, x, r, EMBER[2], k)
    return to_image(a)


def tex_cap() -> Image.Image:
    """Top faces of the toe plates and the cap (u = x + 8, v = z + 8): lit shingles, warmed
    round the T's cracks (its bar across z -1.2..-0.35, its stem along x = 0 toward the toe)."""
    a = blank(32, 32, BARK[3])
    paint_shingles(a, (0, 0, 31, 31), seed=9, course=3, tone=1, warm=0.45)
    for y in range(32):
        for x in range(32):
            d = abs(y + 0.5 - 14.45)
            if 9 <= y < 15:
                d = min(d, abs(x + 0.5 - 16.0))
            if d < 5.0:
                k = (1 - d / 5.0) ** 0.8
                tint(a, x, y, EMBER[4] if d < 1.6 else EMBER[3], 0.8 * k)
    return to_image(a)


def tex_rim() -> Image.Image:
    """The collar's sides (rows 1-4 = y 15.5-13.5): lit block tops, dark joints, the bottom
    row lit warm by the band under it. Row 0 is the knobs standing on the collar."""
    a = blank(32, 32, BARK[3])
    paint_shingles(a, (0, 0, 31, 31), seed=12, course=3, tone=1, warm=0.2)
    rng = random.Random(14)
    x = 0
    while x < 32:
        w = rng.randint(3, 6)
        hi = rng.random() < 0.45
        for xx in range(x, min(32, x + w)):
            put(a, xx, 0, BARK[5] if hi else BARK[4])
            put(a, xx, 1, BARK[6] if hi else BARK[5])
            put(a, xx, 2, BARK[5] if hi else BARK[4])
            put(a, xx, 3, BARK[3] if (xx + x) % 4 else BARK[2])
            put(a, xx, 4, EMBER[2] if (xx + x) % 3 else EMBER[1])
        put(a, min(31, x + w - 1), 2, BARK[1])
        put(a, min(31, x + w - 1), 3, BARK[1])
        x += w
    return to_image(a)


def tex_rim_top() -> Image.Image:
    """The collar from above (u = x + 8, v = z + 8): a ring of lit bark blocks round the
    dark opening (x -1.9..1.9, z 0.75..4.75)."""
    a = blank(32, 32, BARK[4])
    rng = random.Random(15)
    for by in range(0, 32, 2):
        for bx in range(-(by // 2 % 2) * 2, 32, 4):
            k = rng.choice((4, 5, 5, 6, 6))
            for y in range(by, by + 2):
                for x in range(max(0, bx), min(32, bx + 4)):
                    put(a, x, y, BARK[k])
            for x in range(max(0, bx), min(32, bx + 4)):
                put(a, x, by + 1, BARK[k - 1])
            put(a, max(0, bx), by, BARK[2])
            put(a, max(0, bx), by + 1, BARK[2])
    for y in range(32):
        for x in range(32):
            px, pz = x / 2 - 8 + 0.25, y / 2 - 8 + 0.25
            if abs(px) < 1.9 and 0.75 < pz < 4.75:
                edge = abs(px) > 1.4 or pz < 1.25
                put(a, x, y, BARK[1] if edge else SOOT[0])
    return to_image(a)


def tex_sole() -> Image.Image:
    """The sole: a dark welt (rows 29-31 on the sides), soot ledge and tread elsewhere."""
    a = blank(32, 32, SOOT[2])
    for y in range(32):
        for x in range(32):
            if (x * 3 + y * 5) % 7 == 0:
                put(a, x, y, SOOT[1])
    for x in range(32):
        put(a, x, 29, BARK[3] if x % 4 else BARK[4])
        put(a, x, 30, SOOT[2] if x % 5 else SOOT[1])
        put(a, x, 31, SOOT[1] if x % 3 else SOOT[0])
    return to_image(a)


def tex_band_frame(t: float, phase: float = 0.0) -> Image.Image:
    """The glowing bands, projected: the cuff band's two rows (5-6: cut edge, hot core), the
    toe band's two (27-28: hot core, lit foot), hot core everywhere else (the T, the
    band rims). Blocks of light along the bands breathe with the flicker."""
    f = flicker(t, phase)
    rng = random.Random(21)
    heat = [rng.random() for _ in range(16)]
    a = blank(32, 32)
    for x in range(32):
        hb = heat[(x // 2 + (x // 7)) % 16]
        hot = hb + 0.35 * f > 0.9
        warm = hb + 0.3 * f > 0.55
        core = GLOW[6] if hot else GLOW[5]
        for y in range(32):
            put(a, x, y, core if (x + y) % 3 else GLOW[5])
        put(a, x, 5, GLOW[4] if warm else GLOW[3])
        put(a, x, 6, core)
        put(a, x, 27, core)
        put(a, x, 28, GLOW[4] if warm else GLOW[3])
    return to_image(a)


def textures() -> None:
    save_layer(paint_worn(), "humanoid")
    save(tex_boot(), "boot")
    save(tex_toe(), "toe")
    save(tex_cap(), "cap")
    save(tex_rim(), "rim")
    save(tex_rim_top(), "rim_top")
    save(tex_sole(), "sole")
    save(tex_vine(), "vine")
    save(tex_leaf(LEAF), "leaf")
    save(tex_leaf(RUST), "leaf_rust")
    save(tex_iron(), "iron")
    save_animation([tex_band_frame(i / 8) for i in range(8)], "band", frametime=3, interpolate=True)
    save_animation([tex_lantern_frame(i / 8, 0.0) for i in range(8)], "lantern", frametime=3, interpolate=True)
    save_animation([tex_lantern_frame(i / 8, 0.37) for i in range(8)], "lantern_b", frametime=3,
                   interpolate=True)


# ======================================================================================
# Item model: the pair as the concept shows it, two boots standing side by side, toes
# toward -Z, the one on the +X side a little ahead. Each boot is built in its own frame (x
# centred on 0, toe toward -Z, the shaft over z 0..5.5, the sole's underside at y = 0) and
# the two are the same boot, like the concept's: a vine comes down the +X side, crosses the
# front from its +X corner down toward the -X side, then winds round the shaft to the
# ankle, and a lantern hangs from it on the front's +X half; the boot on the -X side also
# has a lantern on its -X side, which the inventory icon shows. The pair is then centred on
# (8, 8, 8). The icon looks at the toes and the -X sides from above, like the concept.
# ======================================================================================

SIDES = ("north", "south", "east", "west", "up", "down")
SHAFT_Z = 5.5           # the shaft's depth (front z = 0)


def _clamp(v: float) -> float:
    return round(min(16.0, max(0.0, v)), 4)


def pbox(frm, to, tex, skip=(), glow: int = 0, shade: bool = True, top=None) -> dict:
    """A box whose faces sample their textures by projection: sides u along the face, v = 16 - y;
    top and bottom u = x + 8, v = z + 8 (`top` names another texture for the up face)."""
    (x0, y0, z0), (x1, y1, z1) = frm, to
    U, V, W = (lambda x: x + 8), (lambda y: 16 - y), (lambda z: z + 8)
    uv = {"south": [U(x0), V(y1), U(x1), V(y0)], "north": [U(x1), V(y1), U(x0), V(y0)],
          "west": [W(z0), V(y1), W(z1), V(y0)], "east": [W(z1), V(y1), W(z0), V(y0)],
          "up": [U(x0), W(z0), U(x1), W(z1)], "down": [U(x0), W(z1), U(x1), W(z0)]}
    faces = {}
    for side in SIDES:
        if side in skip:
            continue
        faces[side] = ((top if side == "up" and top else tex), [_clamp(v) for v in uv[side]])
    return box(frm, to, tex, faces=faces, skip=skip, glow=glow, shade=shade)


# The toe cap: plates stepping in toward the top, a rounded voxel dome (bottom y, top y,
# front z, half-width), under a flat-topped cap with the glowing T cut into it like the
# concept's: its bar right across the cap near the shaft (so it also glows out of the cap's
# sides) and its stem running forward to short of the front edge.
TOE_PLATES = ((1.5, 3.0, -4.4, 2.65), (3.0, 4.0, -4.15, 2.6), (4.0, 4.75, -3.75, 2.45))
CAP = (4.75, 5.5, -3.2, 2.2)
T_BAR = (-1.2, -0.35)       # z span of the bar's crack
T_STEM = 0.42               # half-width of the stem's crack...
T_TIP = -2.75               # ...which runs forward to here


def boot_body() -> list[dict]:
    """Sole, foot, toe cap with its glowing T, shaft, bands and collar."""
    glow = {"glow": 15, "shade": False}
    z1 = SHAFT_Z
    p = [
        pbox((-2.85, 0.0, -4.65), (2.85, 1.5, z1 + 0.35), "sole"),
        pbox((-2.75, 1.5, -4.52), (2.75, 2.5, z1 + 0.22), "band", skip=("down",), **glow),       # toe band
        pbox((-2.5, 1.5, 0.0), (2.5, 13.5, z1), "boot", skip=("down", "up")),                   # shaft
        pbox((-2.63, 12.5, -0.13), (2.63, 13.5, z1 + 0.13), "band", skip=("down",), **glow),   # cuff band
        pbox((-2.95, 13.5, -0.45), (2.95, 15.5, z1 + 0.45), "rim", top="rim_top"),             # collar
    ]
    for y0, y1, zf, hw in TOE_PLATES:
        p.append(pbox((-hw, y0, zf), (hw, y1, 0.3), "toe", skip=("down", "south"), top="cap"))
    # The cap in four pieces round the T, the glowing core filling the cracks.
    y0, y1, zf, hw = CAP
    for xa, xb in ((-hw, -T_STEM), (T_STEM, hw)):
        p.append(pbox((xa, y0, zf), (xb, y1, T_BAR[0]), "toe", skip=("down",), top="cap"))
    p.append(pbox((-T_STEM, y0, zf), (T_STEM, y1, T_TIP), "toe", skip=("down",), top="cap"))
    p.append(pbox((-hw, y0, T_BAR[1]), (hw, y1, 0.3), "toe", skip=("down", "south"), top="cap"))
    p.append(pbox((-hw + 0.03, y0, T_BAR[0] + 0.03), (hw - 0.03, y1 - 0.06, T_BAR[1] - 0.03), "band",
                  skip=("down",), **glow))
    p.append(pbox((-T_STEM + 0.03, y0, T_TIP + 0.03), (T_STEM - 0.03, y1 - 0.06, T_BAR[0] + 0.05), "band",
                  skip=("down",), **glow))
    # Voxel knobs: blocks standing proud of the collar and the shaft.
    for frm, to in (((-2.6, 15.5, -0.25), (-1.0, 15.95, 1.4)), ((0.5, 15.5, 4.2), (2.3, 15.85, 5.75)),
                    ((2.95, 13.75, 2.8), (3.25, 14.75, 4.3)), ((-2.8, 9.9, 3.2), (-2.5, 11.2, 4.4)),
                    ((0.6, 10.1, 5.5), (1.9, 11.3, 5.8)), ((2.5, 7.9, 1.6), (2.8, 9.1, 3.0))):
        p.append(pbox(frm, to, "rim" if frm[1] >= 13.5 else "boot"))
    return p


VZ = SHAFT_Z + 0.38    # the vines' z on the back face
VINE_PATH = [   # one vine, a turn and three quarters: the front diagonal, then round to the ankle
    (2.88, 13.05, VZ), (2.92, 12.6, 2.8), (2.88, 11.95, -0.38),              # down the +X side to the corner
    (0.8, 10.9, -0.42), (-1.2, 9.6, -0.42), (-2.88, 8.75, -0.38),           # across the front
    (-2.92, 8.0, 2.8), (-2.88, 7.4, VZ), (0.0, 7.0, VZ + 0.04), (2.88, 6.7, VZ),   # down the -X side, the back
    (2.92, 6.5, 2.8), (2.88, 6.4, -0.38),                                    # the +X side at the ankle
    (0.6, 6.3, -0.46), (-1.6, 6.25, -0.46), (-2.88, 6.15, -0.38), (-3.05, 4.8, -1.6),   # over the toe, down its side
]
LANTERN_AT = (1.3, 11.15, -1.05)          # the front lantern (boot frame), hung from the vine on the front
SIDE_LANTERN_AT = (-3.42, 12.7, 3.5)      # the -X boot's side lantern


def boot_vines(side_hook: bool) -> list[dict]:
    """The vine, its tendrils and the leaves (plus the hook for the side lantern)."""
    p = vine_path(VINE_PATH, 0.84)
    for path in ([(-1.2, 9.6, -0.42), (-1.7, 10.5, -0.85), (-1.1, 10.9, -1.0)],
                 [(-2.92, 8.0, 2.8), (-3.35, 8.8, 3.3), (-3.4, 9.3, 2.8)],
                 [(0.0, 7.0, VZ + 0.04), (0.6, 7.9, VZ + 0.4), (0.2, 8.4, VZ + 0.55)],
                 [(0.6, 6.3, -0.46), (1.3, 5.85, -0.9), (1.7, 6.25, -1.05)]):
        p += vine_path(path, 0.36)
    if side_hook:
        p += vine_path([(-2.88, 13.05, VZ), (-2.94, 12.95, 4.4), (-2.94, 12.8, 3.5)], 0.7)
    L, R = "leaf", "leaf_rust"
    spots = [   # x, y, z, size, texture, Euler x/y/z
        (-1.5, 9.4, -0.95, 3.6, L, 6, 30, 22), (-0.4, 11.6, -0.92, 2.0, R, 0, -10, -30),
        (-0.5, 7.05, -0.98, 2.6, L, -20, 10, -18), (-2.88, 3.5, -1.6, 3.0, L, 0, 90, -28),
        (-3.3, 8.6, 1.7, 3.4, L, 0, 90, 16), (-3.25, 7.2, 4.5, 2.4, R, 0, 90, -24),
        (0.9, 8.2, VZ + 0.45, 3.0, L, 0, 180, 20), (-1.8, 6.8, VZ + 0.42, 2.1, R, 0, 180, -24),
        (3.3, 9.9, 3.2, 2.4, L, 0, -90, 12), (-1.9, 16.0, 1.1, 2.2, L, -66, 24, 10),
        (-2.25, 6.75, -0.98, 1.9, R, -16, 30, 24),
    ]
    p += [leaf3d(x, y, z, s, t, rx, ry, rz, shade=False) for x, y, z, s, t, rx, ry, rz in spots]
    return p


PAIR = ((11.5, -1.2), (4.5, 0.0))   # the two boots' centre x and z offset: +X ahead, -X behind


def build() -> list[dict]:
    parts = []
    for i, (cx, dz) in enumerate(PAIR):
        boot = boot_body() + boot_vines(side_hook=i == 1)
        lx, ly, lz = LANTERN_AT
        boot += lantern(lx, ly, lz, "lantern" if i == 0 else "lantern_b", w=1.8, h=2.6, hook=0.6)
        if i == 1:
            sx, sy, sz = SIDE_LANTERN_AT
            boot += lantern(sx, sy, sz, "lantern", w=1.8, h=2.4, hook=0.7)
        parts += move(boot, cx, 0, dz)
    lo, hi = bounds(parts)
    move(parts, 0, 8 - (lo[1] + hi[1]) / 2, 8 - (lo[2] + hi[2]) / 2)
    return parts


GUI_ROTATION = (24, 150, 0)


def models() -> dict:
    parts = build()
    d = display(KIND, parts, gui_rotation=GUI_ROTATION, gui_span=15.4)
    centre = (8.0, 8.0, 8.0)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.45, 0, -1)}, centre, (0.5, -0.42, -0.9), 0.5,
                                       pose=None)
    return {"main": model(parts, d)}
