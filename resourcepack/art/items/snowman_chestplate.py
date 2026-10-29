"""Snowman Chestplate: the chest piece of the Snowman set (issue #1316, Survival, winter
holiday), and the set's pilot module: the Helmet, the Leggings and the Boots import its
palette and painters from here, so the four pieces share one snow, one knit and one coal.

Set language (a friendly snowman, freshly rolled on the first snow day):
  * packed snow, cool white with blue-violet shadows and warm highlights, lumpy and
    rounded, with a few twinkling ice glints (animated on the 3D items);
  * a red knit scarf (V stitches, cream stripes and a fringed end), the red repeated in
    the mittens, the leggings' sash and the boots' cuffs;
  * glossy coal: buttons, eyes, the smile and the boots' soles;
  * an orange carrot nose and a black top hat with a red band (Helmet);
  * a holly sprig (green leaves, red berries) pinned where the scarf is knotted.

Worn (humanoid layer, 4x): a snowball torso shaded round, the red scarf wrapped round the
neck and knotted on the wearer's right with its striped tail hanging down the front, a
holly sprig on the knot, three coal buttons down the middle, the scarf's second tail
flung over the back. Arms: snow sleeves with a snow lump on the shoulder and red knit
mittens with a cream cuff.

Item: a round snowball torso with a belly bulge and snow lumps, the scarf in 3D (a ring
round the neck, the knot, two fringed tails), the coal buttons standing proud, the holly
sprig, and twig arms sticking out of the shoulders, each ending in a red mitten.
"""
from __future__ import annotations

import math
import random

import numpy as np
from PIL import Image

from art.items.snowveil_guard import C, _hash, blank, edges, grid, pick, shadow, shift, sync_overlap, to_image
from art.items.viking_chestplate import mask_rect, smooth_noise, swatch
from art.kit import HUMANOID, bar, box, display, mirror, model, place, region, save, save_animation, save_layer, \
    turn

ID = "snowman_chestplate"
NAME = "Snowman Chestplate"
KIND = "chestplate"
COUNTERPART = "item/netherite_chestplate"

# ======================================================================================
# SNOWMAN SET PALETTE AND PAINTERS (imported by the other Snowman pieces)
# ======================================================================================

SNOW = ["#4a5688", "#6676aa", "#8a9acb", "#adbbe2", "#c9d4ef", "#dfe7f7", "#edf2fc", "#f6f9fe", "#ffffff"]
ICE = ["#5f8fd0", "#8fbdf0", "#c8e6ff", "#ffffff"]
COAL = ["#060608", "#0e0e13", "#18181f", "#23232c", "#31313c", "#43434f", "#5b5b6a", "#82829a"]
RED = ["#2a0509", "#480a10", "#6e1118", "#96191e", "#ba2427", "#d63a31", "#ec5c46", "#ff8b6b"]
CREAM = ["#7d6f5f", "#a89a84", "#cfc4ad", "#ebe3cf", "#fbf6ea"]
CARROT = ["#4a1804", "#7a2a06", "#a83f0a", "#d35a12", "#ee7a22", "#fb9a3e", "#ffc070"]
GREEN = ["#0b2612", "#123a1b", "#1a5226", "#256b31", "#36893f", "#55a956"]
BERRY = ["#4a0508", "#8c0d12", "#cc1b1e", "#f2453a", "#ff9a86"]
TWIG = ["#1f130b", "#2f1e12", "#43301e", "#5a432b", "#74593a", "#917450"]

S = 4   # worn layers are painted at 4x (256x128): 4 texels per model unit


def snow(a: np.ndarray, mask=None, seed: int = 0, bias=0.0, glints: float = 0.01, ao: float = 0.0) -> None:
    """Packed snow: soft lumps (two scales of smooth noise), a few blue-grey pits and
    crumbs, rare ice glints. bias lightens (> 0) or darkens (< 0): a number or a
    per-texel array (use it to shade a face round)."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    n = smooth_noise(h, w, 7, seed=seed) * 0.6 + smooth_noise(h, w, 3, seed=seed + 1) * 0.4
    idx = 6.6 + (n - 0.5) * 2.4 + np.asarray(bias) * 2
    pit = _hash(X, Y, seed + 3) > 0.975
    crumb = (_hash(X // 2, Y // 2, seed + 4) > 0.93) & (_hash(X, Y, seed + 5) > 0.5)
    idx = idx - pit * 2 - crumb * 1
    idx = np.rint(idx).astype(int)
    paint = pick(SNOW, idx)
    glint = _hash(X, Y, seed + 7) < glints
    paint[glint & ~pit] = C(ICE[3])
    if mask is None:
        mask = np.ones((h, w), bool)
    if ao:
        shadow(a, mask, ao, reach=1)
    a[mask] = paint[mask]


def round_bias(h: int, w: int, cx=None, cy=None, rx=None, ry=None, power: float = 2.0, depth: float = 1.0,
               light=(-0.35, -0.45)) -> np.ndarray:
    """A bias field that shades a face like a ball: light near (cx, cy) pushed toward the
    upper left by `light`, falling off toward blue-violet at the rim."""
    X, Y = grid(h, w)
    cx = w / 2 if cx is None else cx
    cy = h / 2 if cy is None else cy
    rx = w / 2 if rx is None else rx
    ry = h / 2 if ry is None else ry
    dx = (X + 0.5 - cx) / rx - light[0] * 0.5
    dy = (Y + 0.5 - cy) / ry - light[1] * 0.5
    d = np.clip(np.sqrt(dx * dx + dy * dy), 0, 1.6)
    return 0.35 - depth * d ** power


def knit(a: np.ndarray, mask=None, seed: int = 0, bias=0.0, pal=None, along: str = "y") -> None:
    """Red knit: columns of V stitches (4 texels wide, 3 tall), each stitch lit on top and
    shaded below, dark furrows between the columns, a few stray fibres. along="x" turns
    the stitches sideways (for a band running across a face)."""
    pal = pal or RED
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    if along == "x":
        X, Y = Y, X
    k = X % 4
    v = (Y + np.where((k == 1) | (k == 2), 1, 0)) % 3
    idx = 4.0 + (v == 0) * 1.2 - (v == 2) * 1.0 - ((k == 0) | (k == 3)) * 0.7
    idx = idx + (_hash(X // 4, Y // 3, seed) - 0.5) * 0.8 + np.asarray(bias) * 2
    fibre = _hash(X, Y, seed + 2) > 0.97
    idx = np.rint(idx + fibre).astype(int)
    paint = pick(pal, idx)
    if mask is None:
        a[:] = paint
    else:
        a[mask] = paint[mask]


CREAM_KNIT = [CREAM[0], CREAM[1], CREAM[1], CREAM[2], CREAM[3], CREAM[3], CREAM[4], CREAM[4]]


def stripes(a: np.ndarray, mask: np.ndarray, rows, seed: int = 0, along: str = "y") -> None:
    """Cream knit stripes over the rows (or columns when along="x") listed."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    pos = Y if along == "y" else X
    m = mask & np.isin(pos, list(rows))
    tmp = blank(w, h)
    knit(tmp, None, seed=seed, pal=CREAM_KNIT, along=along)
    a[m] = tmp[m]


def fringe(a: np.ndarray, c0: int, c1: int, y: int, length: int = 4, seed: int = 0) -> None:
    """The scarf's fringe: tassels hanging from row y between columns c0..c1, every other
    column, ragged lengths, red with cream tips."""
    h, w = a.shape[:2]
    rng = random.Random(seed)
    for x in range(c0, c1 + 1):
        if (x - c0) % 2:
            continue
        n = length - rng.randint(0, 1)
        for j in range(n):
            yy = y + j
            if 0 <= yy < h and 0 <= x < w:
                a[yy, x] = C(RED[4] if j < n - 1 else RED[2])
        if 0 <= y + n < h:
            shade = a[y + n, x].copy()
            if shade[3] > 0:
                shade[:3] *= 0.6
                a[y + n, x] = shade


COAL_PEBBLE = [".abba.",
               "abccdb",
               "bceddb",
               "bcdddb",
               "abddba",
               ".aaaa."]


def coal(a: np.ndarray, x: int, y: int, pattern=None) -> np.ndarray:
    """A glossy coal lump (6x6 by default) with its top-left at (x, y): faceted, a bright
    glint at the upper left, a contact shadow. Returns its mask."""
    pattern = pattern or COAL_PEBBLE
    pal = {"a": COAL[0], "b": COAL[2], "c": COAL[4], "d": COAL[3], "e": COAL[7]}
    h, w = a.shape[:2]
    m = np.zeros((h, w), bool)
    for j, row in enumerate(pattern):
        for i, ch in enumerate(row):
            if ch != "." and 0 <= x + i < w and 0 <= y + j < h:
                m[y + j, x + i] = True
    shadow(a, m, 0.5, reach=1)
    for j, row in enumerate(pattern):
        for i, ch in enumerate(row):
            if ch != "." and 0 <= x + i < w and 0 <= y + j < h:
                a[y + j, x + i] = C(pal[ch])
    return m


HOLLY = ["..gg.....GG..",
         ".gGGg...gGGg.",
         "gGllGg.gGllGg",
         ".gGGlgggGlGg.",
         "..ggg.rr.ggg.",
         "....rRRr.....",
         "....rRhr.rr..",
         ".....rr.rRhr.",
         ".........rr.."]


def holly(a: np.ndarray, x: int, y: int) -> None:
    """A holly sprig (13x9 texels) with its top-left at (x, y): two spiky leaves with lit
    midribs and three red berries with a highlight."""
    pal = {"g": GREEN[1], "G": GREEN[3], "l": GREEN[5], "r": BERRY[1], "R": BERRY[2], "h": BERRY[4]}
    h, w = a.shape[:2]
    m = np.zeros((h, w), bool)
    for j, row in enumerate(HOLLY):
        for i, ch in enumerate(row):
            if ch != "." and 0 <= x + i < w and 0 <= y + j < h:
                m[y + j, x + i] = True
    shadow(a, m, 0.5, reach=1)
    for j, row in enumerate(HOLLY):
        for i, ch in enumerate(row):
            if ch != "." and 0 <= x + i < w and 0 <= y + j < h:
                a[y + j, x + i] = C(pal[ch])


def lumps(a: np.ndarray, base: list[int], seed: int, up: bool = False) -> None:
    """Snow lumps along an edge: per column, the edge row base[c] bulges into rounded
    clumps; a shaded rim under each (or over each when up)."""
    h, w = a.shape[:2]
    rng = random.Random(seed)
    c = 0
    while c < w:
        n = rng.randint(3, 6)
        d = rng.randint(1, 2)
        for i in range(n):
            if c + i >= w:
                break
            t = 1 - abs(i - (n - 1) / 2) / (n / 2)
            r = base[c + i] + (int(round(t * d)) if not up else -int(round(t * d)))
            if 0 <= r < h:
                a[r, c + i] = C(SNOW[4])
                rr = r - 1 if not up else r + 1
                if 0 <= rr < h and a[rr, c + i, 3] > 0:
                    a[rr, c + i] = C(SNOW[5])
        c += n


# ======================================================================================
# Worn layer (humanoid, 256x128). Body faces 32x48 texels, body sides and arm faces
# 16x48, body top 32x16, arm top 16x16.
# ======================================================================================

SCARF = (0, 11)                  # the scarf's thick wrap round the neck (rows)
KNOT = (5, 8, 12, 16)            # the knot on the wearer's right (texture left): x0, y0, x1, y1
TAIL = (6, 10, 34)               # the front tail: columns 6..10 (clear of the buttons), down to row 34
TAIL_STRIPES = ((19, 20, 21), (27, 28, 29))
BUTTONS = (16, 25, 34)           # the coal buttons' top rows (centre column 16)
BACK_TAIL = (19, 26, 30)         # the back tail: columns 19..26 down to row 30


def scarf_band(a: np.ndarray, seed: int, top: int = SCARF[0], bottom: int = SCARF[1]) -> np.ndarray:
    """The scarf wrapped round the neck: red knit across the face, a rolled lit top edge,
    a fold line, a shaded lower edge with a shadow on the snow under it."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    rows = [bottom + (1 if _hash(c // 3, 0, seed) > 0.6 else 0) for c in range(w)]
    m = (Y >= top) & (Y <= np.asarray(rows)[None, :])
    shadow(a, m, 0.5, reach=2, down_only=True)
    knit(a, m, seed=seed, along="x", bias=0.1)
    a[m & (Y == top)] = C(RED[6])
    fold = m & (Y == top + 5)
    a[fold] = C(RED[2])
    a[shift(fold, 0, 1) & m] = C(RED[5])
    bot = m & ~shift(m, 0, -1)
    a[bot] = C(RED[1])
    return m


def scarf_tail(a: np.ndarray, c0: int, c1: int, top: int, bottom: int, seed: int, stripe_rows=()) -> None:
    """A scarf tail hanging from row `top` to `bottom`: knit running down it, lit on its
    left edge, dark on its right, cream stripes across, a fringe at the foot."""
    h, w = a.shape[:2]
    m = mask_rect(h, w, c0, top, c1, bottom)
    shadow(a, m, 0.55, reach=1)
    knit(a, m, seed=seed)
    for rows in stripe_rows:
        stripes(a, m, rows, seed=seed + 1)
    X, Y = grid(h, w)
    a[m & (X == c0)] = a[m & (X == c0)] * np.array([1.2, 1.2, 1.2, 1.0])
    a[m & (X == c1)] = C(RED[1])
    fringe(a, c0, c1, bottom + 1, 4, seed + 2)


def body_base(w: int, h: int, seed: int, front: bool = True) -> np.ndarray:
    """A snowball torso face: snow shaded round (light at the upper left), the lumpy foot
    where it meets the Leggings' sash."""
    a = blank(w, h)
    X, Y = grid(h, w)
    if w == 32:
        # a snowball chest: bright high in the middle, blue occlusion toward the edges and
        # in the armpits (the columns beside the arms)
        bias = round_bias(h, w, cy=h * 0.42, rx=w * 0.58, ry=h * 0.6, depth=1.15)
        pit = np.clip(1 - np.minimum(np.abs(X - 6.5), np.abs(X - 25.5)) / 3.0, 0, 1) * ((Y > 10) & (Y < 34))
        bias = bias - pit * 0.45
    else:
        bias = round_bias(h, w, rx=w * 0.9, ry=h * 0.62, depth=0.9) - 0.15
    snow(a, seed=seed, bias=bias)
    lumps(a, [h - 3] * w, seed + 1)
    a[h - 1, :] = C(SNOW[2])
    return a


def paint_body_front(seed: int = 11) -> np.ndarray:
    w, h = 8 * S, 12 * S
    a = body_base(w, h, seed)
    for y in BUTTONS:
        coal(a, 13, y)
    x0, y0, x1, y1 = KNOT
    scarf_tail(a, TAIL[0], TAIL[1], y1 - 1, TAIL[2], seed + 3, TAIL_STRIPES)
    # a soft shadow the belly casts under the scarf wrap
    a[SCARF[1] + 2:SCARF[1] + 4, 11:29, :3] *= 0.92
    scarf_band(a, seed + 4)
    # the knot: a puffed lump of knit over the band, lit at the top left
    knot = mask_rect(h, w, x0, y0, x1, y1)
    knot &= ~((np.arange(h)[:, None] == y0) & (np.isin(np.arange(w)[None, :], (x0, x1))))
    shadow(a, knot, 0.55, reach=1)
    knit(a, knot, seed=seed + 5, bias=0.25)
    top, bottom, left, right = edges(knot)
    a[top | left] = C(RED[6])
    a[bottom | right] = C(RED[1])
    holly(a, x0 + 1, y0 - 5)
    return a


def paint_body_back(seed: int = 23) -> np.ndarray:
    w, h = 8 * S, 12 * S
    a = body_base(w, h, seed)
    c0, c1, bottom = BACK_TAIL
    scarf_tail(a, c0, c1, SCARF[1] - 1, bottom, seed + 3, ((16, 17, 18), (23, 24, 25)))
    scarf_band(a, seed + 4)
    return a


def paint_body_side(seed: int = 31) -> np.ndarray:
    w, h = 4 * S, 12 * S
    a = body_base(w, h, seed)
    scarf_band(a, seed + 4)
    return a


def paint_body_top(seed: int = 41) -> np.ndarray:
    w, h = 8 * S, 4 * S
    a = blank(w, h)
    knit(a, seed=seed, along="x", bias=0.1)
    X, Y = grid(h, w)
    a[(X == 0) | (Y == 0)] = C(RED[6])
    return a


MITTEN = 39                      # the mitten's cuff (3 cream rows), the knit below it
ARM_LAYERS = (21,)               # a packed-snow seam half way down the sleeve


def paint_arm_face(kind: str, seed: int) -> np.ndarray:
    """One side of the right arm. kind: "front", "back", "outer" or "inner"."""
    w, h = 4 * S, 12 * S
    a = blank(w, h)
    bias = round_bias(h, w, cy=h * 0.4, rx=w * 0.9, ry=h * 0.7, depth=0.7) - 0.05
    if kind == "inner":
        bias = bias - 0.3
    snow(a, seed=seed, bias=bias)
    X, Y = grid(h, w)
    # where one packed layer of snow sits on the next (the Leggings have the same seams)
    for k, row in enumerate(ARM_LAYERS):
        lumps(a, [row] * w, seed + 7 + k)
    # the red knit mitten with its cream cuff
    mit = Y >= MITTEN
    shadow(a, mit, 0.5, reach=1)
    knit(a, mit, seed=seed + 3)
    cuff = (Y >= MITTEN) & (Y < MITTEN + 3)
    knit(a, cuff, seed=seed + 4, pal=CREAM_KNIT, along="x")
    a[Y == MITTEN] = C(CREAM[4])
    a[Y == MITTEN + 3] = C(RED[1])
    if kind == "front":
        # the thumb: a knit lobe outlined darker
        thumb = mask_rect(h, w, 9, 42, 13, 46)
        knit(a, thumb, seed=seed + 5, bias=0.3)
        top, bottom, left, right = edges(thumb)
        a[left | bottom] = C(RED[1])
        a[top] = C(RED[6])
    a[h - 1, :] = C(RED[1])
    seam = {"front": slice(w - 2, w), "back": slice(0, 2)}.get(kind)
    if seam is not None:
        sub = a[:, seam]
        sub[..., :3] *= 0.85
        a[:, seam] = sub
    return a


def paint_arm_top(seed: int = 61) -> np.ndarray:
    w = h = 4 * S
    a = blank(w, h)
    snow(a, seed=seed, bias=0.3)
    return a


def paint_humanoid() -> Image.Image:
    out = blank(64 * S, 32 * S)

    def put(name, arr):
        x0, y0, x1, y1 = region(HUMANOID, name, S)
        out[y0:y1 + 1, x0:x1 + 1] = arr

    front, back = paint_body_front(), paint_body_back()
    arm_front, arm_back = paint_arm_face("front", 51), paint_arm_face("back", 52)
    sync_overlap(front, arm_front, back=False)
    sync_overlap(back, arm_back, back=True)
    side = paint_body_side()
    put("body_front", front)
    put("body_back", back)
    put("body_right", side)
    put("body_left", side[:, ::-1])
    put("body_top", paint_body_top())
    bottom = blank(8 * S, 4 * S)
    bottom[:] = C(SNOW[3])
    put("body_bottom", bottom)
    put("arm_front", arm_front)
    put("arm_back", arm_back)
    put("arm_outer", paint_arm_face("outer", 53))
    put("arm_inner", paint_arm_face("inner", 54))
    put("arm_top", paint_arm_top())
    bottom = blank(4 * S, 4 * S)
    bottom[:] = C(RED[2])
    put("arm_bottom", bottom)
    return to_image(out)


# ======================================================================================
# Item textures (shared with the set's 3D parts)
# ======================================================================================

GLINTS = [(7, 9, 0.0), (22, 4, 0.35), (41, 12, 0.7), (56, 6, 0.15), (12, 30, 0.55), (33, 26, 0.85),
          (50, 35, 0.3), (5, 49, 0.75), (26, 46, 0.1), (45, 55, 0.5), (60, 44, 0.9), (18, 60, 0.4),
          (30, 15, 0.6), (3, 21, 0.25), (52, 22, 0.45), (38, 39, 0.05), (14, 41, 0.95), (58, 58, 0.65),
          (24, 54, 0.8), (44, 2, 0.5)]


def snow_frames(count: int = 8) -> list[Image.Image]:
    """Packed snow (64 px, tiling) with ice glints twinkling on and off at different
    moments of the loop, the way fresh snow sparkles as you walk past it: each glint is
    a white core with pale-blue arms 2-3 texels long, lit for about a third of the loop."""
    a = blank(64, 64)
    snow(a, seed=5, bias=0.15, glints=0.0)
    base = to_image(a)
    arm_c = (170, 210, 255)
    frames = []
    for i in range(count):
        t = i / count
        img = base.copy()
        px = img.load()
        for x, y, phase in GLINTS:
            d = ((t - phase + 0.5) % 1.0) - 0.5
            k = max(0.0, 1 - abs(d) / 0.18)
            if k <= 0:
                continue
            arm = 1 + round(2 * k)
            px[x, y] = (255, 255, 255, 255)
            for r in range(1, arm + 1):
                f = k * (1 - (r - 1) / (arm + 0.5))
                for dx, dy in ((r, 0), (-r, 0), (0, r), (0, -r)):
                    xx, yy = (x + dx) % 64, (y + dy) % 64
                    c0 = px[xx, yy]
                    tgt = (255, 255, 255) if r == 1 else arm_c
                    px[xx, yy] = tuple(round(c0[j] + (tgt[j] - c0[j]) * f) for j in range(3)) + (255,)
        frames.append(img)
    return frames


def _knit_swatch(a):
    knit(a, seed=4, bias=0.1)
    a[0] = C(RED[6])


def _knit_x_swatch(a):
    knit(a, seed=8, bias=0.1, along="x")
    a[0] = C(RED[6])


def _tail_swatch(a):
    """The scarf tail: knit down it with cream stripes across (rows 24-35 and 44-55; uv
    rows 6-9 and 11-14)."""
    knit(a, seed=6, bias=0.1)
    m = np.ones(a.shape[:2], bool)
    stripes(a, m, range(24, 36), seed=7)
    stripes(a, m, range(44, 56), seed=7)
    a[0] = C(RED[6])


def _cream_swatch(a):
    knit(a, seed=9, pal=CREAM_KNIT, along="x")
    a[0] = C(CREAM[4])


def _coal_swatch(a):
    """Glossy coal for the 3D lumps: dark facets in soft patches, a few bright glints and
    a lit top row (faces start at v 0 to catch it)."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    n = smooth_noise(h, w, 6, seed=3)
    idx = np.rint(2.6 + (n - 0.5) * 3.0).astype(int)
    a[:] = pick(COAL, idx)
    a[_hash(X, Y, 4) > 0.985] = C(COAL[7])
    a[0] = C(COAL[6])
    a[1] = C(COAL[4])


def _twig_swatch(a):
    """Bark: streaks running down (v), knots, a lit top row."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    n = smooth_noise(h, w, 5, seed=2)
    streak = (_hash(X, Y // 6, 3) > 0.7).astype(int) - (_hash(X, Y // 4, 4) > 0.8)
    idx = np.rint(3 + (n - 0.5) * 2 + streak).astype(int)
    a[:] = pick(TWIG, idx)
    a[0] = C(TWIG[5])


def _green_swatch(a):
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    idx = 3 + (_hash(X // 2, Y // 2, 3) > 0.7) - (_hash(X, Y, 5) > 0.85)
    a[:] = pick(GREEN, idx)
    a[0] = C(GREEN[5])


def _berry_swatch(a):
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    a[:] = C(BERRY[2])
    a[Y > 40] = C(BERRY[1])
    a[(X < 20) & (Y < 20)] = C(BERRY[3])
    a[(X < 8) & (Y < 8)] = C(BERRY[4])


def _carrot_swatch(a):
    """Carrot skin: rings across it (faces run root to tip along v), lit along one side,
    a few dark pores."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    ring = (Y % 6 == 0).astype(int) + (Y % 6 == 1)
    idx = 4 - ring + (X < 16) * 1 - (X > 48) * 1 + (_hash(X, Y, 3) > 0.96) * -2
    a[:] = pick(CARROT, idx)
    a[0] = C(CARROT[6])


def save_set_materials(skip=()) -> None:
    """The swatches the Snowman 3D parts use (leave out the ones a piece never uses)."""
    swatches = {
        "snow": lambda: save_animation(snow_frames(), "snow", frametime=3),
        "knit": lambda: swatch("knit", _knit_swatch),
        "knit_x": lambda: swatch("knit_x", _knit_x_swatch),
        "tail": lambda: swatch("tail", _tail_swatch),
        "cream": lambda: swatch("cream", _cream_swatch),
        "coal": lambda: swatch("coal", _coal_swatch),
        "twig": lambda: swatch("twig", _twig_swatch),
        "green": lambda: swatch("green", _green_swatch),
        "berry": lambda: swatch("berry", _berry_swatch),
        "carrot": lambda: swatch("carrot", _carrot_swatch),
        "lining": lambda: swatch("lining", lambda a: a.__setitem__(slice(None), C("#1c1f30"))),
        "snow_rim": lambda: swatch("snow_rim", lambda a: snow(a, seed=5, bias=-0.2, glints=0.0)),
    }
    for name, paint in swatches.items():
        if name not in skip:
            paint()


# --------------------------------------------------------------------------------------
# Shared 3D parts
# --------------------------------------------------------------------------------------

def snow_box(frm, to, frame, tex: str = "snow", **kw) -> dict:
    """A box whose faces sample the snow swatch by world position inside `frame`
    ((x0, y0, z0), (x1, y1, z1)), so overlapping boxes that share a plane show the very
    same texels there and never flicker against each other."""
    (fx0, fy0, fz0), (fx1, fy1, fz1) = frame
    (xa, ya, za), (xb, yb, zb) = frm, to

    def u(v, lo, hi):
        return round(max(0.0, min(16.0, (v - lo) / (hi - lo) * 16)), 4)

    ux = (u(xa, fx0, fx1), u(xb, fx0, fx1))
    uz = (u(za, fz0, fz1), u(zb, fz0, fz1))
    vy = (u(fy1 - yb + fy0, fy0, fy1), u(fy1 - ya + fy0, fy0, fy1))
    faces = {"north": (tex, [16 - ux[1], vy[0], 16 - ux[0], vy[1]]),
             "south": (tex, [ux[0], vy[0], ux[1], vy[1]]),
             "east": (tex, [16 - uz[1], vy[0], 16 - uz[0], vy[1]]),
             "west": (tex, [uz[0], vy[0], uz[1], vy[1]]),
             "up": (tex, [ux[0], uz[0], ux[1], uz[1]]),
             "down": (tex, [ux[0], 16 - uz[1], ux[1], 16 - uz[0]])}
    for side, spec in (kw.pop("faces", None) or {}).items():
        faces[side] = spec
    return box(frm, to, tex, faces=faces, **kw)


def snowball(lo, hi, r: float, steps: int = 3, frame=None, tex: str = "snow", skip_down: bool = False,
             rim: str | None = None) -> list[dict]:
    """A rounded block of snow between lo and hi: boxes inset by a quarter-circle profile
    of radius r on each axis (steps per quarter), so it reads as a ball, not a cube. rim
    textures the steps' risers (the faces set back from the outer planes), so a shaded
    rim swatch makes the stepped curve read as one shaded form instead of thin slivers."""
    frame = frame or (lo, hi)
    angles = [math.pi / 2 * i / steps for i in range(steps + 1)]
    insets = set()
    for t in angles:
        for p in angles:
            d = (math.cos(t) * math.cos(p), math.sin(t) * math.cos(p), math.sin(p))
            insets.add(tuple(round(r * (1 - v), 3) for v in d))
    parts = []
    for ix, iy, iz in sorted(insets):
        frm = (lo[0] + ix, lo[1] + iy, lo[2] + iz)
        to = (hi[0] - ix, hi[1] - iy, hi[2] - iz)
        if any(to[i] - frm[i] < 0.05 for i in range(3)):
            continue
        e = snow_box(frm, to, frame, tex, skip=("down",) if skip_down else ())
        if rim:
            for axis, inset, sides in ((0, ix, ("east", "west")), (1, iy, ("up", "down")), (2, iz, ("north", "south"))):
                if inset > 0.01:
                    for side in sides:
                        if side in e["faces"]:
                            e["faces"][side]["texture"] = "#" + rim
        parts.append(e)
    return parts


def coal_lump(cx: float, cy: float, z: float, r: float = 0.6, depth: float = 0.5, facing: str = "north") -> list[dict]:
    """A glossy coal lump on a surface facing -z at depth z (or +z with facing "south"):
    a rounded nugget in two crossed boxes, a little lopsided."""
    s = -1 if facing == "north" else 1
    za, zb = sorted((z, z + s * depth))
    zc, zd = sorted((z, z + s * depth * 1.35))
    return [box((cx - r, cy - r * 0.7, za), (cx + r, cy + r * 0.8, zb), "coal", offset=(1, 0)),
            box((cx - r * 0.7, cy - r, zc), (cx + r * 0.75, cy + r, zd), "coal", offset=(3, 0))]


def holly_3d(cx: float, cy: float, z: float, s: float = 1.0, facing: str = "north") -> list[dict]:
    """A holly sprig on a surface facing -z at depth z: two spiky leaves fanned out (each a
    leaf blade with notched edges) and three berries clustered at their root."""
    p = []
    sgn = -1 if facing == "north" else 1
    for k, ang in enumerate((28, 152)):
        a = math.radians(ang)
        tip = (cx + math.cos(a) * 2.4 * s, cy + math.sin(a) * 1.3 * s, z + sgn * 0.2)
        leaf = bar((cx, cy, z + sgn * 0.2), tip, 1.1 * s, 0.14, "green", offset=(k * 4, 0))
        p.append(leaf)
        for t in (0.35, 0.7):
            q = (cx + (tip[0] - cx) * t, cy + (tip[1] - cy) * t, z + sgn * 0.2)
            nub = box((q[0] - 0.3 * s, q[1] - 0.75 * s, q[2] - 0.07), (q[0] + 0.3 * s, q[1] + 0.75 * s, q[2] + 0.07),
                      "green", offset=(2, 3))
            turn(nub, 45, "z", q)
            turn(nub, math.degrees(a) - 90, "z", q)
            p.append(nub)
        p.append(bar((cx, cy, z + sgn * 0.3), (tip[0] * 0.8 + cx * 0.2, tip[1] * 0.8 + cy * 0.2, z + sgn * 0.3),
                     0.18, 0.06, "green", offset=(0, 0)))
    for bx, by, bz in ((-0.45, -0.35, 0.45), (0.45, -0.4, 0.4), (0.0, 0.25, 0.6)):
        x, y = cx + bx * s, cy + by * s
        zz = z + sgn * bz
        p.append(box((x - 0.42 * s, y - 0.42 * s, min(zz, zz - sgn * 0.8 * s)),
                     (x + 0.42 * s, y + 0.42 * s, max(zz, zz - sgn * 0.8 * s)), "berry", uv="full"))
    return p


def knit_roll(frm, to, tex: str = "knit_x", r: float = 0.35) -> list[dict]:
    """A soft knit roll (core + a cap inset by r), like the set's scarf and cuffs."""
    (x0, y0, z0), (x1, y1, z1) = frm, to
    return [box((x0, y0 + r, z0), (x1, y1 - r, z1), tex, offset=(0, 1)),
            box((x0 + r, y0, z0 + r), (x1 - r, y1, z1 - r), tex, offset=(1, 0))]


def fringe_3d(x0: float, x1: float, y: float, z0: float, z1: float, seed: int, length: float = 1.2) -> list[dict]:
    """Tassels hanging from height y along x0..x1, between depths z0..z1."""
    rng = random.Random(seed)
    out = []
    n = max(2, int((x1 - x0) / 0.55))
    for k in range(n):
        x = x0 + (k + 0.5) * (x1 - x0) / n
        ln = length * rng.choice((0.75, 0.9, 1.0))
        out.append(box((x - 0.17, y - ln, z0), (x + 0.17, y, z1), "knit", offset=(k % 4, 2)))
    return out


def mitten_3d(cx: float, cy: float, cz: float, s: float = 1.0) -> list[dict]:
    """A red knit mitten (palm up, fingers together) centred on (cx, cy, cz): the hand in
    two rounded boxes, the thumb sticking up and the cream cuff at its wrist (-y)."""
    p = [box((cx - 0.9 * s, cy - 0.7 * s, cz - 0.55 * s), (cx + 0.9 * s, cy + 1.1 * s, cz + 0.55 * s), "knit",
             offset=(1, 1)),
         box((cx - 0.7 * s, cy - 0.5 * s, cz - 0.7 * s), (cx + 0.7 * s, cy + 1.35 * s, cz + 0.7 * s), "knit",
             offset=(2, 0))]
    p.append(bar((cx - 0.75 * s, cy - 0.1 * s, cz), (cx - 1.35 * s, cy + 0.9 * s, cz), 0.6 * s, 0.6 * s, "knit",
                 offset=(0, 2)))
    p.append(box((cx - 1.0 * s, cy - 1.4 * s, cz - 0.75 * s), (cx + 1.0 * s, cy - 0.6 * s, cz + 0.75 * s), "cream",
                 offset=(0, 0)))
    return p


def twig(base, tip, width: float = 0.7, forks=(), seed: int = 0) -> list[dict]:
    """A snowman's twig arm from base to tip, in two slightly kinked bars, with short
    forked twigs branching off at the fractions listed in forks as (t, angle, length)."""
    rng = random.Random(seed)
    mid = [base[i] + (tip[i] - base[i]) * 0.5 for i in range(3)]
    mid[1] += 0.35
    mid[2] += rng.uniform(-0.3, 0.3)
    p = [bar(base, mid, width, width, "twig", offset=(0, 1)),
         bar(mid, tip, width * 0.75, width * 0.75, "twig", offset=(2, 1))]
    for t, ang, ln in forks:
        q = [base[i] + (tip[i] - base[i]) * t for i in range(3)]
        q[1] += 0.35 * 4 * t * (1 - t)
        d = [tip[i] - base[i] for i in range(3)]
        n = math.hypot(d[0], d[1])
        ux, uy = d[0] / n, d[1] / n
        a = math.radians(ang)
        fx, fy = ux * math.cos(a) - uy * math.sin(a), ux * math.sin(a) + uy * math.cos(a)
        p.append(bar(q, (q[0] + fx * ln, q[1] + fy * ln, q[2]), width * 0.5, width * 0.5, "twig", offset=(1, 0)))
    return p


# ======================================================================================
# Item model (front = north / -z, the wearer's right = +x, like the set's other pieces)
# ======================================================================================

BALL_C, BALL_R, BALL_H = (8.0, 4.6, 7.4), 5.2, 4.6   # the belly snowball: centre, radius, half height
CHEST = ((2.0, 6.0, 4.9), (14.0, 12.6, 11.1))         # the upper torso (shoulders), a rounded block
NECK_Y = 11.0                                         # the scarf ring's foot
FRAME = ((0.0, -1.0, 1.0), (16.0, 15.0, 14.0))


def ball_radius(y: float) -> float:
    """The belly snowball's radius at height y."""
    h = (y - BALL_C[1]) / BALL_H
    return BALL_R * math.sqrt(max(0.0, 1 - min(1.0, h * h)))


def surface_z(x: float, y: float, front: bool = True, out: float = 0.0) -> float:
    """Depth of the torso's outer surface at (x, y), on the front (-z) or the back (the
    belly ball or the upper torso, whichever stands further out), pushed `out` further."""
    r = ball_radius(y)
    d = math.sqrt(max(0.0, r * r - (x - BALL_C[0]) ** 2))
    (x0, y0, z0), (x1, y1, z1) = CHEST
    if front:
        z = BALL_C[2] - d if d else 99.0
        if y0 <= y <= y1 and x0 <= x <= x1:
            z = min(z, z0)
        return z - out
    z = BALL_C[2] + d if d else -99.0
    if y0 <= y <= y1 and x0 <= x <= x1:
        z = max(z, z1)
    return z + out


# heights relative to the centre and radii (an ellipsoid), slices close together where
# the ball curves most so its steps stay small; flattened at the foot
BALL_PROFILE = [(h, round(BALL_R * math.sqrt(max(0.0, 1 - (h / 4.9) ** 2)), 3)) for h in
                (-4.4, -4.0, -3.4, -2.6, -1.6, -0.4, 0.8, 1.9, 2.8, 3.5, 4.1)]


def body_3d() -> list[dict]:
    """The snowman's torso: a rounded block of snow for the chest and shoulders, a round
    snowball belly bulging out in front of it, snow caps over the shoulders, and a few
    clumps stuck on."""
    from art.kit import lathe
    p = snowball(CHEST[0], CHEST[1], 2.3, steps=3, frame=FRAME)
    p += lathe(BALL_C, BALL_PROFILE, "snow", axis="y", sides=16, cap="snow")
    # snow caps over the shoulders, rounding them like sleeve stubs
    for x0, x1 in ((0.7, 4.3), (11.7, 15.3)):
        p += snowball((x0, 8.0, 5.4), (x1, 12.8, 10.6), 1.4, steps=2, frame=FRAME)
    for x, y, front, s in ((4.6, 2.6, True, 0.8), (11.6, 9.2, False, 0.8), (4.2, 7.0, False, 0.7)):
        z = surface_z(x, y, front, -0.45)
        p += snowball((x - s, y - s, z - s), (x + s, y + s, z + s), s * 0.55, steps=1, frame=FRAME)
    return p


def tail_3d(x: float, y_top: float, y_bot: float, front: bool, width: float, seed: int, steps: int = 5,
            tilt: float = 0.0) -> list[dict]:
    """A scarf tail lying over the torso from y_top down to y_bot at column x: a chain of
    knit panels hinged at their tops, each leaning to follow the surface under it, the
    stripes running on from panel to panel, a fringe at the foot. tilt (degrees) swings
    the whole tail sideways about its top."""
    p = []
    seg = (y_top - y_bot) / steps
    for k in range(steps):
        ya, yb = y_top - k * seg, y_top - (k + 1) * seg
        za, zb = surface_z(x, ya, front, 0.3), surface_z(x, yb, front, 0.3)
        za = min(za, zb) if front else max(za, zb)       # never sink into a bulge below
        lean = math.degrees(math.atan2(zb - za, seg))
        v0, v1 = 16 * k / steps * 0.95, 16 * (k + 1) / steps * 0.95
        zz = (za - 0.45, za) if front else (za, za + 0.45)
        panel = box((x - width / 2, yb - 0.08, zz[0]), (x + width / 2, ya + 0.08, zz[1]), "tail",
                    faces={"north": ("tail", [0, v0, 6, v1]), "south": ("tail", [0, v0, 6, v1]),
                           "east": ("tail", [0, v0, 1, v1]), "west": ("tail", [0, v0, 1, v1])})
        turn(panel, lean if front else -lean, "x", (x, ya, za))
        p.append(panel)
    zf = surface_z(x, y_bot, front, 0.3)
    p += fringe_3d(x - width / 2 + 0.05, x + width / 2 - 0.05, y_bot + 0.1, zf - 0.35 if front else zf,
                   zf if front else zf + 0.35, seed, 1.3)
    if tilt:
        turn(p, tilt, "z", (x, y_top, surface_z(x, y_top, front)))
    return p


def scarf_3d() -> list[dict]:
    """The scarf: a thick, soft knit wrap round the neck (closed over the top), the knot
    on the wearer's right with the holly sprig, one long striped tail hanging from it over
    the right side of the chest, clear of the buttons; a second tail down the back."""
    from art.kit import lathe
    cx, cz = 8.0, 8.0
    ring = lathe((cx, NECK_Y, cz), [(0.0, 4.3), (0.5, 5.0), (1.5, 5.25), (2.4, 5.0), (2.9, 4.4)], "knit_x",
                 axis="y", sides=16, cap="knit")
    # the fold where the scarf wraps over itself
    ring += lathe((cx, NECK_Y + 1.35, cz), [(0.0, 5.35), (0.3, 5.35)], "knit", axis="y", sides=16)
    # the snow of the snowman's neck showing inside the wrap
    ring += lathe((cx, NECK_Y + 2.6, cz), [(0.0, 3.9), (0.3, 3.6), (0.6, 3.0), (0.9, 2.1), (1.1, 1.1)], "snow",
                  axis="y", sides=16, cap="snow_rim")
    # worn a little askew: lower at the front, where the knot pulls it down
    p = turn(ring, -5, "x", (cx, NECK_Y, cz))
    kx, ky = 11.0, NECK_Y + 0.7
    kz = cz - math.sqrt(5.2 ** 2 - (kx - cx) ** 2) - 0.45
    knot = [box((kx - 1.5, ky - 1.4, kz - 0.4), (kx + 1.5, ky + 1.4, kz + 1.5), "knit_x", offset=(1, 1)),
            box((kx - 1.2, ky - 1.7, kz - 0.7), (kx + 1.2, ky + 1.7, kz + 1.3), "knit_x", offset=(2, 0))]
    p += turn(knot, -12, "y", (kx, ky, kz))
    p += tail_3d(11.4, ky - 0.8, 3.0, True, 2.4, seed=3, tilt=-4)
    p += holly_3d(kx - 0.2, ky + 0.6, kz - 0.8, 1.05)
    # the back tail, flung over the wearer's left shoulder
    p += tail_3d(5.6, NECK_Y + 0.4, 3.4, False, 2.3, seed=5, tilt=5)
    return p


def buttons_3d() -> list[dict]:
    """Three coal buttons down the middle of the belly."""
    p = []
    for y in (8.0, 5.4, 2.8):
        p += coal_lump(8.0, y, surface_z(8.0, y, True, -0.25), 0.85, 0.65)
    return p


def arm_3d() -> list[dict]:
    """The wearer's right twig arm (+x): out of the shoulder's snow cap, angled up, a
    forked twig, and a red knit mitten on its end."""
    base = (14.4, 9.8, 8.0)
    tip = (17.6, 12.4, 7.6)
    p = twig(base, tip, 0.7, forks=((0.55, 42, 1.2),), seed=2)
    p += turn(mitten_3d(tip[0] + 0.35, tip[1] + 0.45, tip[2], 0.7), -35, "z", (tip[0], tip[1], tip[2]))
    return p


def build() -> list[dict]:
    right = arm_3d()
    return body_3d() + scarf_3d() + buttons_3d() + right + mirror(right, "x", 8.0)


def item_textures() -> None:
    save_set_materials(skip=("carrot", "lining"))


def textures() -> None:
    save_layer(paint_humanoid(), "humanoid")
    item_textures()


def models() -> dict:
    parts = build()
    d = display(KIND, parts, gui_rotation=(8, 168, 0), gui_span=15.5)
    centre = (8.0, 7.0, 8.0)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, centre, "fist", 0.42)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.4, 0, -1)}, centre, (0.52, -0.38, -0.95), 0.5,
                                       pose=None)
    return {"main": model(parts, d)}
