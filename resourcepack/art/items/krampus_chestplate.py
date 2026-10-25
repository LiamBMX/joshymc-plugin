"""Krampus Chestplate: the chest piece of the Krampus set (issue #1306, Survival, December),
and the set's pilot module: the Helmet, the Leggings and the Boots import its palette and
painters from here, so the four pieces share one palette, one trim and one emblem.

Set language (the winter demon who comes for the naughty ones, heavy and cursed):
  * blackened iron, cool and nearly black, scorched with a few ember-red flecks, cut into
    lames with jagged, sawtoothed lower edges;
  * shaggy black-brown goat fur: the mantle, the arms' paws, the leggings' goat legs and
    the boots' fetlocks;
  * crimson cloth trimmed with black fur, a cursed copy of Saint Nicholas' red coat;
  * dark leather harness straps riveted with dirty brass, iron chains, and brass bells;
  * ember orange only in the eyes (glowing on the 3D items, painted bright when worn);
  * the Krampus emblem: an iron goat-demon face with curled horns, slanted ember eyes,
    a brass rim and two bone fangs.

Worn (humanoid layer, 4x): a shaggy black fur mantle over the shoulders, an iron cuirass
breastplate with a sawtoothed foot and the emblem on the sternum, a leather harness down from each shoulder
to the belt, an iron chain slung across the chest carrying three brass bells, the belt
with its brass buckle, and a crimson coat-skirt hemmed in black fur. Arms: fur over two
single bevelled iron pauldron plate, shaggy fur sleeves, and hairy paws with bone
claws. Back: the mantle, the lames, the harness crossing in an X
through a brass ring, the belt and the skirt.

Item: the same paintings on a hollow torso, with the fur mantle, spiked pauldrons, the
emblem (glowing eyes), the chain and its bells, the belt and the skirt flaps in 3D.
"""
from __future__ import annotations

import math
import random

import numpy as np
from PIL import Image

from art.items.snowveil_guard import (C, _hash, band_mask, blank, edges, fur_roll, grid, pick, scallop, shadow,
                                      shift, sync_overlap, to_image)
from art.items.viking_chestplate import (TX0, TX1, TY0, TY1, TZ0, TZ1, _x_front, _y, mask_rect, smooth_noise,
                                         swatch, torso)
from art.kit import HUMANOID, bar, box, display, lathe, mirror, model, place, ramp, region, save, \
    save_animation, save_layer, shine, turn

ID = "krampus_chestplate"
NAME = "Krampus Chestplate"
KIND = "chestplate"
COUNTERPART = "item/netherite_chestplate"

# ======================================================================================
# KRAMPUS SET PALETTE AND PAINTERS (imported by the other Krampus pieces)
# ======================================================================================

IRON = ["#0b090c", "#151217", "#1f1b22", "#2a252d", "#37313a", "#463f49", "#5a515c", "#746871", "#998b92"]
FUR = ["#0c0807", "#150f0c", "#1f1611", "#2a1e17", "#37281e", "#463327", "#584131", "#6e523e", "#87674e"]
CRIMSON = ["#1b0406", "#2d070a", "#440c10", "#5d1216", "#7a181b", "#982223", "#b52f2b", "#d0493a"]
BRASS = ["#2c1e0b", "#4a3414", "#6c4e1c", "#8f6c29", "#b08a3b", "#cca95a", "#e5c987"]
LEATHER = ramp("#4d2e1d", 7, 0.8)
STRAP = ramp("#2a1a12", 7, 0.8)
EMBER = ["#3d0a04", "#7a1806", "#b8320a", "#e65a12", "#ff8a2a", "#ffbe5c", "#fff0b0"]
BONE = ["#3a3226", "#5e5442", "#857a63", "#aaa085", "#cbc2a6", "#e6dfc8"]
HORN = ["#100c0b", "#1c1513", "#2a201c", "#3a2d26", "#4e3e33", "#665243", "#826b57", "#a28a72", "#c2ab90"]

S = 4   # worn layers are painted at 4x (256x128): 4 texels per model unit


def iron(a: np.ndarray, mask: np.ndarray, seed: int = 0, bias=0.0, ao: float = 0.45, rim: bool = True) -> None:
    """Blackened iron over the mask: soft tarnish, short scratches, a few scorched ember
    flecks, a cold lit top/left edge, a black bottom/right edge, a contact shadow."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    n = smooth_noise(h, w, 6, seed=seed)
    idx = 4 + (n - 0.5) * 2.2
    idx = idx - (_hash(X, Y, seed + 3) > 0.975)
    scratch = ((X * 2 + Y + seed) % 13 == 0) & (_hash(X // 4, Y // 5, seed + 7) > 0.72)
    idx = np.rint(idx + scratch + np.asarray(bias) * 2).astype(int)
    if ao:
        shadow(a, mask, ao, reach=1)
    paint = pick(IRON, idx)
    scorch = (_hash(X, Y, seed + 11) > 0.985)
    paint[scorch] = C(CRIMSON[2])
    a[mask] = paint[mask]
    if rim:
        top, bottom, left, right = edges(mask)
        a[top] = C(IRON[8])
        a[left & ~top] = C(IRON[7])
        a[bottom] = C(IRON[0])
        a[right & ~bottom & ~top] = C(IRON[1])


def jag_rows(w: int, y: int, period: int = 4, depth: int = 3, phase: int = 0) -> list[int]:
    """Per-column bottom rows of a sawtoothed edge: points `depth` texels below y every
    `period` texels (the demon-plate edge)."""
    out = []
    for c in range(w):
        k = (c + phase) % period
        t = 1 - abs(k - (period - 1) / 2) / ((period - 1) / 2 + 0.5)
        out.append(y + int(round(t * depth)))
    return out


def jagged_lame(a: np.ndarray, top: int, bottom: int, seed: int, bias=0.0, period: int = 4, depth: int = 3,
                phase: int = 0, ao: float = 0.6, c0: int = 0, c1=None) -> np.ndarray:
    """An iron lame from row `top` to a sawtoothed lower edge round row `bottom`; its foot
    casts a shadow on whatever is under it. Returns its mask."""
    h, w = a.shape[:2]
    c1 = w - 1 if c1 is None else c1
    rows = jag_rows(w, bottom, period, depth, phase)
    m = band_mask(h, w, top=[top] * w, bottom=rows)
    m[:, :c0] = False
    m[:, c1 + 1:] = False
    iron(a, m, seed=seed, bias=bias, ao=ao)
    # a raised rib along the top of the lame
    X, Y = grid(h, w)
    a[m & (Y == top + 1)] = C(IRON[6])
    return m


def cloth(a: np.ndarray, mask=None, seed: int = 0, bias=0.0) -> None:
    """Crimson coat cloth: a near-solid felted field with soft folds (low-frequency
    noise), a broken twill and a few dark slubs. bias lightens (> 0) or darkens (< 0)."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    n = smooth_noise(h, w, 8, seed=seed)
    twill = ((X + Y) % 4 == 0) & (_hash(X // 2, Y, seed + 2) > 0.5)
    idx = 4 + (n - 0.5) * 1.8 + twill * 0.6 - (_hash(X // 3, Y // 2, seed) > 0.94)
    idx = np.rint(idx + np.asarray(bias) * 3).astype(int)
    paint = pick(CRIMSON, idx)
    if mask is None:
        a[:] = paint
    else:
        a[mask] = paint[mask]


def rivet(a: np.ndarray, x: int, y: int, metal=None) -> None:
    """A 2x2 domed rivet head (dirty brass by default)."""
    m = metal or BRASS
    h, w = a.shape[:2]
    for dx, dy, c in ((0, 0, m[-1]), (1, 0, m[-2]), (0, 1, m[-3]), (1, 1, m[1])):
        if 0 <= x + dx < w and 0 <= y + dy < h:
            a[y + dy, x + dx] = C(c)


def leather(a: np.ndarray, mask: np.ndarray, seed: int = 0, pal=None, stitch: bool = True, ao: float = 0.4) -> None:
    """Oiled dark leather: lit top/left edge, dark bottom/right edge, stitched lines inside
    the long edges, a few creases."""
    pal = pal or STRAP
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    if ao:
        shadow(a, mask, ao, reach=1)
    idx = 3 + np.where(_hash(X // 2, Y, seed) > 0.86, -1, 0) + np.where(_hash(X, Y // 2, seed + 1) > 0.93, 1, 0)
    a[mask] = pick(pal, idx)[mask]
    top, bottom, left, right = edges(mask)
    a[top | left] = C(pal[5])
    a[bottom | right] = C(pal[0])
    if stitch:
        inner_top = shift(top, 0, 1) & mask & ~bottom
        inner_bot = shift(bottom, 0, -1) & mask & ~top
        a[(inner_top | inner_bot) & (X % 2 == 0)] = C(pal[4])


def fur(a: np.ndarray, mask: np.ndarray, seed: int = 0, hang: bool = True, lift: int = 0, edge: bool = True,
        cell: int = 1, long: int = 0) -> None:
    """Shaggy black-brown goat fur: long, thin, slightly wavy locks (2*cell texels wide,
    4*cell + 3 + long long), staggered like shingles, each lit toward its point with the
    dark roots of the next course showing, dark guard hairs and a few rusty tips. When
    edge, the free edge is ragged (some texels left unpainted). hang=True: the band hangs
    down (free edge at the bottom); False: it rises. edge=False paints an even field."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    lw, ll = 2 * cell, 4 * cell + 3 + long
    col = (X + ((Y // ll) % 2)) // lw
    off = (_hash(col, 0, seed) * ll).astype(int)
    yy = Y + off if hang else -Y + off + 4096
    v = (yy % ll) / (ll - 1)
    u = np.abs(((X + ((Y // ll) % 2)) % lw) - (lw - 1) / 2) / (lw / 2)
    end = 1.0 - 0.3 * u * u
    r = _hash(col, yy // ll, seed + 1)
    idx = 2.6 + v * 2.6 - u * 0.5 + (r - 0.5) * 1.6
    idx = np.where(v > end, 1.8 + (r - 0.5), idx)
    idx = np.rint(idx).astype(int) + lift
    dist = np.full((h, w), 9, np.int32)
    for k in range(8, 0, -1) if edge else ():
        out = mask & ~shift(mask, 0, -k if hang else k)
        dist[out] = k - 1
    idx = idx + np.select([dist == 0, dist == 1, dist == 2], [-1, 1, 1], 0)
    paint = pick(FUR, idx)
    guard = (_hash(X, Y // 3, seed + 5) > 0.96) & (dist > 1)
    paint[guard] = C(FUR[0])
    tip = (_hash(X + 7, Y // 2, seed + 6) > 0.975) & ~guard & (v > 0.6) & (v <= end)
    paint[tip] = C(FUR[8])
    ragged = mask & (((dist == 0) & (_hash(X, Y, seed + 8) > 0.4)) | ((dist == 1) & (_hash(X, Y, seed + 9) > 0.8)))
    paint_mask = mask & ~ragged
    a[paint_mask] = paint[paint_mask]
    if hang:
        shadow(a, paint_mask, 0.5, reach=2, down_only=True)


BELL = ["..oo..",
        ".bhmb.",
        ".hmmd.",
        "bhmmmd",
        "dddddd",
        "..cc.."]


def bell(a: np.ndarray, x: int, y: int) -> None:
    """A small brass bell (6x6 texels) hung from texel (x + 2, y), with its clapper."""
    pal = {"o": IRON[6], "b": BRASS[3], "h": BRASS[6], "m": BRASS[4], "d": BRASS[1], "c": IRON[2]}
    h, w = a.shape[:2]
    m = np.zeros((h, w), bool)
    for j, row in enumerate(BELL):
        for i, ch in enumerate(row):
            if ch != "." and 0 <= x + i < w and 0 <= y + j < h:
                m[y + j, x + i] = True
    shadow(a, m, 0.5, reach=1)
    for j, row in enumerate(BELL):
        for i, ch in enumerate(row):
            if ch != "." and 0 <= x + i < w and 0 <= y + j < h:
                a[y + j, x + i] = C(pal[ch])


def chain(a: np.ndarray, x0: int, x1: int, y_end: float, sag: float, bells=()) -> list[tuple[int, int]]:
    """An iron chain slung from (x0, y_end) to (x1, y_end), sagging `sag` texels at its
    middle: alternate face-on rings and edge-on links, a shadow under it. Bells hang at
    the listed columns. Returns the (x, y) of every chain texel row centre."""
    h, w = a.shape[:2]
    cx, hw = (x0 + x1) / 2, max(1.0, (x1 - x0) / 2)
    pts = []
    m = np.zeros((h, w), bool)
    for x in range(x0, x1 + 1):
        y = int(round(y_end + sag * (1 - ((x - cx) / hw) ** 2)))
        pts.append((x, y))
        for dy in (-1, 0, 1):
            if 0 <= y + dy < h and 0 <= x < w:
                m[y + dy, x] = True
    shadow(a, m, 0.55, reach=1)
    for x, y in pts:
        if not 0 <= x < w:
            continue
        k, j = (x - x0) // 3, (x - x0) % 3
        if k % 2 == 0:
            cols = ([IRON[7], IRON[5], IRON[2]] if j != 1 else [IRON[8], IRON[0], IRON[3]])
        else:
            cols = [None, IRON[6], IRON[2]] if j != 1 else [None, IRON[7], IRON[3]]
        for dy, c in zip((-1, 0, 1), cols):
            if c is not None and 0 <= y + dy < h:
                a[y + dy, x] = C(c)
    for bx in bells:
        for x, y in pts:
            if x == bx:
                bell(a, x - 2, y + 1)
    return pts


def emblem(a: np.ndarray, cx: float, top: int, height: int = 14) -> np.ndarray:
    """The Krampus emblem: an iron goat-demon face, wide at the brow and narrowing to the
    muzzle, framed in dirty brass, with a dark brow ridge, slanted ember eyes, a snout
    line, two bone fangs and curled horns rising from its temples. Returns its mask."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    t = (Y + 0.5 - top) / height
    half = np.where(t < 0.4, height * 0.4, height * 0.4 * (1 - (t - 0.4) * 1.15))
    dx = X + 0.5 - cx
    face = (t >= 0) & (t <= 0.94) & (np.abs(dx) <= half)
    # horns: thick curves from the temples, out and up, curling back in at the tip
    horns = np.zeros((h, w), bool)
    horn_shade = np.zeros((h, w), np.float32)
    # each horn rises from a top corner of the face, sweeping up and out, its tip hooking
    # back over (kept inside the strip the arms leave visible when worn)
    for sgn in (-1, 1):
        for k in range(32):
            s = k / 31
            px = cx + sgn * (height * 0.25 + height * 0.18 * s ** 2)
            py = top + 1.0 - height * 0.5 * s
            rad = max(0.5, 1.5 * (1 - s) + 0.4)
            hit = (X + 0.5 - px) ** 2 + (Y + 0.5 - py) ** 2 <= rad * rad
            horns |= hit
            horn_shade = np.where(hit, np.maximum(horn_shade, s), horn_shade)
    horns &= ~face
    plate = face | horns
    shadow(a, plate, 0.6, reach=2)
    # an inked outline so the dark horns read on the dark plate
    ring_ = (shift(horns, 1, 0) | shift(horns, -1, 0) | shift(horns, 0, 1) | shift(horns, 0, -1)) & ~plate
    a[ring_ & (a[..., 3] > 0)] = C(IRON[0])
    ridges = ((np.floor((X + Y) / 2) % 2) == 0)
    hidx = np.rint(5 + horn_shade * 3.5 + ridges * 1).astype(int)
    a[horns] = pick(HORN, hidx)[horns]
    he = edges(horns)
    a[he[1] | he[3]] = C(HORN[3])
    iron(a, face, seed=5, bias=0.35, ao=0.0, rim=False)
    # brass rim round the face
    top_e, bot_e, left_e, right_e = edges(face)
    rim = top_e | bot_e | left_e | right_e
    a[rim] = C(BRASS[3])
    a[rim & (dx < 0)] = C(BRASS[5])
    a[bot_e] = C(BRASS[1])
    # brow ridge: a dark V over the eyes
    ty = top + height * 0.28
    brow = face & ~rim & (np.abs((Y + 0.5) - (ty + np.abs(dx) * -0.35 + 1.2)) <= 0.7) & (np.abs(dx) < half - 1)
    a[brow] = C(IRON[1])
    # slanted ember eyes under the brow, lower toward the nose
    for sgn in (-1, 1):
        for i in range(3):
            ex = int(math.floor(cx + sgn * (1.5 + i)))
            ey = int(math.floor(ty + 2.2 + (2 - i) * 0.5 - 0.5 * (i == 0)))
            if 0 <= ex < w and 0 <= ey < h:
                a[ey, ex] = C(EMBER[5] if i == 1 else EMBER[4])
                if ey + 1 < h:
                    a[ey + 1, ex] = C(EMBER[2])
    # snout line and nostrils
    sy = int(top + height * 0.62)
    for x in (int(cx) - 1, int(cx)):
        a[sy, x] = C(IRON[1])
        a[sy + 1, x] = C(IRON[6])
    # fangs below the muzzle
    for sgn in (-1, 1):
        fx = int(math.floor(cx + sgn * 1.5 - (0.5 if sgn < 0 else 0)))
        for j, c in enumerate((BONE[5], BONE[4], BONE[2])):
            fy = int(top + height * 0.94) - 1 + j
            if 0 <= fy < h:
                a[fy, fx] = C(c)
    return plate


COWBELL = ["..oo..",
           ".o..o.",
           ".bhmb.",
           ".hmmd.",
           ".hmmd.",
           "bhmmmd",
           "bhmmdd",
           "dddddd",
           "..cc.."]


def cowbell(a: np.ndarray, x: int, y: int) -> None:
    """A Krampus cowbell (6x9 texels) hung by an iron loop from texel (x + 2, y): a
    tapered brass body lit at the left, a dark mouth and the clapper under it."""
    pal = {"o": IRON[7], "b": BRASS[3], "h": BRASS[6], "m": BRASS[4], "d": BRASS[1], "c": IRON[2]}
    h, w = a.shape[:2]
    m = np.zeros((h, w), bool)
    for j, row in enumerate(COWBELL):
        for i, ch in enumerate(row):
            if ch != "." and 0 <= x + i < w and 0 <= y + j < h:
                m[y + j, x + i] = True
    shadow(a, m, 0.55, reach=1)
    for j, row in enumerate(COWBELL):
        for i, ch in enumerate(row):
            if ch != "." and 0 <= x + i < w and 0 <= y + j < h:
                a[y + j, x + i] = C(pal[ch])


def ring(a: np.ndarray, cx: int, cy: int) -> None:
    """A brass harness ring (5x5) where straps cross."""
    pal = {"h": BRASS[6], "m": BRASS[4], "d": BRASS[1], "x": STRAP[0]}
    rows = [".hhm.", "hmxmd", "hx.xd", "mmxdd", ".ddd."]
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch == ".":
                continue
            a[cy - 2 + j, cx - 2 + i] = C(pal[ch])


def buckle(a: np.ndarray, cx: int, y0: int, h: int, w: int = 8) -> None:
    """A dirty-brass belt buckle (a square frame with an iron tongue) on column cx."""
    H, W = a.shape[:2]
    x0 = cx - w // 2
    m = mask_rect(H, W, x0, y0, x0 + w - 1, y0 + h - 1)
    shadow(a, m, 0.5, reach=1)
    a[m] = C(BRASS[4])
    top, bottom, left, right = edges(m)
    a[top | left] = C(BRASS[6])
    a[bottom | right] = C(BRASS[1])
    inner = mask_rect(H, W, x0 + 2, y0 + 2, x0 + w - 3, y0 + h - 3)
    a[inner] = C(STRAP[2])
    a[y0 + 1:y0 + h - 1, cx - 1] = C(IRON[7])
    a[y0 + 1:y0 + h - 1, cx] = C(IRON[3])


def belt(a: np.ndarray, y0: int, h: int = 4, buckle_x=None, seed: int = 0) -> None:
    """The dark leather belt across a face (rows y0..y0+h-1), a buckle when buckle_x."""
    H, W = a.shape[:2]
    leather(a, mask_rect(H, W, 0, y0, W - 1, y0 + h - 1), seed=seed)
    if buckle_x is not None:
        buckle(a, buckle_x, y0 - 1, h + 2)


def fur_hem(a: np.ndarray, top: int, seed: int, depth: int = 2) -> None:
    """A black fur trim from row `top` down to the face's foot, its edge ragged."""
    h, w = a.shape[:2]
    rows = scallop(w, h - 1 - depth, seed, depth=depth, tuft=3)
    rows = [min(h - 1, r) for r in rows]
    fur(a, band_mask(h, w, top=[top] * w, bottom=rows), seed=seed, lift=1)


# ======================================================================================
# Worn layer (humanoid, 256x128). Body faces 32x48 texels, body sides and arm faces
# 16x48, body top 32x16, arm top 16x16.
# ======================================================================================

MANTLE = 10                      # the fur mantle's base lower edge (texel row)
LAMES = ((7, 29),)               # the breastplate, one plate down to its sawtooth foot
EMBLEM_TOP, EMBLEM_H = 9, 19     # the emblem on the sternum (front face)
CHAIN_Y, CHAIN_SAG = 25, 4       # the chain's ends and its sag at the middle
BELLS = (5, 21)                  # the cowbells' left columns, hung from the belt
BELT = 32                        # the belt's top row (4 rows)
HEM = 42                         # the skirt's fur hem's top row
HARNESS = ((3, 6), (25, 28))     # the shoulder straps' columns on the front


def mantle_rows(w: int, seed: int) -> list[int]:
    return scallop(w, MANTLE, seed, depth=3, tuft=3, symmetric=w == 32)


def body_base(w: int, h: int, seed: int) -> np.ndarray:
    """Jagged iron lames down to the belt over crimson, the skirt with its fur hem."""
    a = blank(w, h)
    X, Y = grid(h, w)
    cloth(a, seed=seed + 1, bias=-0.1)
    # a near-black underlay so the gaps between the lames' teeth read as shadow
    a[LAMES[0][0]:BELT] = C(IRON[1])
    side = -0.25 * (np.abs(X + 0.5 - w / 2) / (w / 2)) ** 2 if w == 32 else -0.1
    for i, (t, b) in enumerate(LAMES[::-1]):
        m = jagged_lame(a, t, b, seed=seed + i, bias=side + 0.15 * i, phase=1 if w == 32 else 0, depth=2,
                        period=6)
        if i == len(LAMES) - 1:
            # the breastplate: a raised keel down the middle and a cold sheen across its
            # upper half, so it reads as one forged plate
            if w == 32:
                a[m & (X == 15)] = C(IRON[7])
                a[m & (X == 16)] = C(IRON[2])
            a[m & (Y == t + 5) & (X % 5 != 4)] = C(IRON[6])
    # the crimson coat below the cuirass, darkening toward the hem
    skirt = mask_rect(h, w, 0, BELT + 4, w - 1, h - 1)
    cloth(a, skirt, seed=seed + 3, bias=0.15 - 0.2 * (Y - BELT) / 12)
    fur_hem(a, HEM, seed + 4)
    belt(a, BELT, 4, seed=seed + 2)
    return a


def fur_top(a: np.ndarray, rows: list[int], seed: int) -> None:
    h, w = a.shape[:2]
    fur(a, band_mask(h, w, bottom=rows), seed=seed, long=1)


def strap_v(a: np.ndarray, c0: int, c1: int, y0: int, y1: int, seed: int, rivets=()) -> None:
    h, w = a.shape[:2]
    leather(a, mask_rect(h, w, c0, y0, c1, y1), seed=seed, ao=0.55)
    for y in rivets:
        rivet(a, c0 + (c1 - c0) // 2 - 0 if c1 - c0 >= 2 else c0, y)


def diag_mask(h: int, w: int, x_top: float, y_top: int, x_bot: float, y_bot: int, half: float = 1.7):
    X, Y = grid(h, w)
    t = (Y - y_top) / max(1, y_bot - y_top)
    cx = x_top + (x_bot - x_top) * t
    return (np.abs(X + 0.5 - cx) <= half) & (Y >= y_top) & (Y <= y_bot)


def paint_body_front(seed: int = 11, emblem_on: bool = True, chain_on: bool = True) -> np.ndarray:
    w, h = 8 * S, 12 * S
    a = body_base(w, h, seed)
    for c0, c1 in HARNESS:
        strap_v(a, c0, c1, 0, BELT - 1, seed + c0, rivets=(12, 26))
    belt(a, BELT, 4, buckle_x=16, seed=seed + 2)
    fur_top(a, mantle_rows(w, seed + 5), seed + 6)
    if emblem_on:
        emblem(a, 16.0, EMBLEM_TOP, EMBLEM_H)
    if chain_on:
        chain(a, 4, 27, CHAIN_Y, CHAIN_SAG)
        for x in BELLS:
            cowbell(a, x, BELT + 2)
    return a


def paint_body_back(seed: int = 23) -> np.ndarray:
    w, h = 8 * S, 12 * S
    a = body_base(w, h, seed)
    for xa, xb in ((4.5, 27.5), (27.5, 4.5)):
        m = diag_mask(h, w, xa, 0, xb, BELT - 1, 1.8)
        leather(a, m, seed=seed + int(xa), ao=0.55)
        rows = np.where(m.any(axis=1))[0]
        for y in rows:
            xs = np.where(m[y])[0]
            a[y, xs[0]] = C(STRAP[5])
            a[y, xs[-1]] = C(STRAP[0])
    ring(a, 16, (BELT - 1) // 2)
    belt(a, BELT, 4, seed=seed + 2)
    for x in (5, 25):
        rivet(a, x, BELT + 1)
    fur_top(a, mantle_rows(w, seed + 5), seed + 6)
    return a


def paint_body_side(seed: int = 31) -> np.ndarray:
    w, h = 4 * S, 12 * S
    a = body_base(w, h, seed)
    fur_top(a, mantle_rows(w, seed + 5), seed + 6)
    return a


def paint_body_top(seed: int = 41) -> np.ndarray:
    w, h = 8 * S, 4 * S
    a = blank(w, h)
    fur(a, np.ones((h, w), bool), seed=seed, hang=False, lift=1, edge=False)
    a[:, 2 * S:6 * S] = C(CRIMSON[0])
    return a


PAULDRON = ((4, 14),)            # the iron pauldron plate (top row, sawtooth base row)
SLEEVE = (16, 47)                # the shaggy fur sleeve down to the paw
PAW = 40                         # the hairy paw's top row


def paint_arm_face(kind: str, seed: int) -> np.ndarray:
    """One side of the right arm. kind: "front" (left = outside), "back" (left = inside),
    "outer" (left = back, right = front), "inner" (left = front, right = back)."""
    w, h = 4 * S, 12 * S
    a = blank(w, h)
    # the shaggy fur sleeve (the demon's own arm), dark under the pauldrons
    fur(a, np.ones((h, w), bool), seed=seed + 1, edge=False, long=2)
    a[:SLEEVE[0]] = C(IRON[1])
    # the paw: shaggy fur, bone claws poking out at the front
    paw = mask_rect(h, w, 0, PAW, w - 1, h - 1)
    fur(a, paw, seed=seed + 4, lift=1, edge=False)
    shadow(a, mask_rect(h, w, 0, PAW - 1, w - 1, PAW - 1), 0.0)
    a[PAW, :] = C(FUR[1])
    if kind == "front":
        for x in (2, 6, 10):
            a[h - 3, x] = C(BONE[5])
            a[h - 2, x] = C(BONE[3])
            a[h - 1, x] = C(BONE[2])
    # the pauldron lames, lower first so the upper one overlaps it
    for i, (t, b) in enumerate(PAULDRON[::-1]):
        m = jagged_lame(a, t, b, seed=seed + 5 + i, bias=0.3, period=5, depth=2, phase=i)
        # a rolled, bevelled plate: a lit roll under the fur, a sheen, a dark crease
        X, Y = grid(h, w)
        a[m & (Y == t + 4)] = C(IRON[7])
        a[m & (Y == t + 5)] = C(IRON[5])
        a[m & (Y == b - 3)] = C(IRON[1])
        rivs = (2, 12) if kind != "outer" else (2, 7, 12)
        for x in rivs:
            rivet(a, x, b - 2)
    fur_top(a, scallop(w, 6, seed + 3, depth=2, tuft=3), seed + 4)
    seam = {"front": slice(w - 2, w), "back": slice(0, 2)}.get(kind)
    if seam is not None:
        sub = a[:, seam]
        sub[..., :3] *= 0.8
        a[:, seam] = sub
    return a


def paint_arm_top(seed: int = 61) -> np.ndarray:
    w = h = 4 * S
    a = blank(w, h)
    fur(a, np.ones((h, w), bool), seed=seed, hang=False, lift=1, edge=False)
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
    bottom[:] = C(FUR[1])
    put("body_bottom", bottom)
    put("arm_front", arm_front)
    put("arm_back", arm_back)
    put("arm_outer", paint_arm_face("outer", 53))
    put("arm_inner", paint_arm_face("inner", 54))
    put("arm_top", paint_arm_top())
    bottom = blank(4 * S, 4 * S)
    bottom[:] = C(FUR[1])
    put("arm_bottom", bottom)
    return to_image(out)


# ======================================================================================
# Item textures (shared with the set's 3D parts)
# ======================================================================================

def _iron_swatch(a):
    """Blackened iron for the 3D parts: soft tarnish, short diagonal wear streaks, a few
    scorched flecks, a cold lit top row (faces start at v 0 to catch it)."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    n = smooth_noise(h, w, 12, seed=7) * 0.65 + smooth_noise(h, w, 5, seed=8) * 0.35
    idx = np.rint(4 + (n - 0.5) * 2.0).astype(int)
    idx = idx + (_hash(X + Y, (X - Y + 64) // 6, 3) > 0.94)
    a[:] = pick(IRON, idx)
    a[(_hash(X, Y, 9) > 0.993)] = C(IRON[8])
    a[(_hash(X, Y, 10) > 0.99)] = C(CRIMSON[2])
    a[0] = C(IRON[8])
    a[1] = C(IRON[6])


def _dark_iron_swatch(a):
    _iron_swatch(a)
    a[..., :3] *= 0.7
    a[0] = C(IRON[5])


def _crimson_swatch(a):
    cloth(a, seed=4, bias=0.15)
    a[0] = C(CRIMSON[7])
    a[1] = C(CRIMSON[6])


def _brass_swatch(a):
    """Dirty brass: a soft, low-contrast tarnish with grime collected in faint streaks, a
    bright lit top row."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    n = smooth_noise(h, w, 8, seed=4)
    idx = np.rint(2.7 + (n - 0.5) * 1.8).astype(int)
    idx = idx - ((_hash(X // 3, Y, 5) > 0.85) & (Y % 3 == 2))
    a[:] = pick(BRASS, idx)
    # grime: darker greenish-brown patina in blotches
    grime = smooth_noise(h, w, 5, seed=9) > 0.68
    a[grime] = a[grime] * np.array([0.72, 0.74, 0.62, 1.0])
    a[0] = C(BRASS[5])
    a[1] = C(BRASS[4])


def brass_frames() -> list[Image.Image]:
    """The dirty brass with a slow glint sweeping across it now and then (the bells,
    buckles and rivets catch the light)."""
    a = blank(64, 64)
    _brass_swatch(a)
    base = to_image(a)
    return [shine(base, i / 16, colour=BRASS[6], width=10, strength=0.55, angle=30, pause=0.55)
            for i in range(16)]


def _leather_swatch(a):
    leather(a, np.ones(a.shape[:2], bool), seed=5, pal=LEATHER, stitch=False, ao=0)
    a[0] = C(LEATHER[5])
    a[1] = C(LEATHER[4])


def _strap_swatch(a):
    leather(a, np.ones(a.shape[:2], bool), seed=6, stitch=False, ao=0)
    a[0] = C(STRAP[5])
    a[1, ::2] = C(LEATHER[4])


def _fur_swatch(a):
    fur(a, np.ones((64, 64), bool), seed=3, hang=True, lift=0, edge=False, cell=2, long=2)


def _horn_swatch(a):
    """Dark goat horn: tight growth ridges across it (faces run root to tip along v), lit
    along one side, paling toward the tip (v grows toward the root)."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    ridge = (Y % 4)
    idx = 3 + np.choose(ridge, [2, 1, 0, -1]) + np.where(_hash(X, Y // 4, 4) > 0.82, 1, 0)
    idx = idx + np.clip((40 - Y) // 12, -1, 3)
    a[:] = pick(HORN, idx)
    a[:, 0:2] = pick(HORN, idx[:, 0:2] + 1)


def _bone_swatch(a):
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    idx = 4 - (Y > 24) - (Y > 44) + (_hash(X, Y // 3, 2) > 0.85)
    a[:] = pick(BONE, idx)
    a[0] = C(BONE[5])


def ember_frame(t: float) -> Image.Image:
    """Ember glow (16 px): a white-hot core fading through orange to red at the edge,
    breathing brighter and dimmer over the loop like a coal being blown on."""
    a = blank(16, 16)
    X, Y = grid(16, 16)
    d = np.maximum(np.abs(X - 7.5), np.abs(Y - 7.5)) / 8
    k = 0.5 + 0.5 * math.cos(2 * math.pi * t)
    idx = np.rint(6 - d * 5.5 + (k - 0.5) * 1.6 + (_hash(X, Y, int(t * 8) % 4 + 1) > 0.85) * 0.6).astype(int)
    a[:] = pick(EMBER, idx)
    return to_image(a)


def save_set_materials(skip=()) -> None:
    """The swatches the Krampus 3D parts use (leave out the ones a piece never uses)."""
    swatches = {
        "fur": lambda: swatch("fur", _fur_swatch),
        "iron": lambda: swatch("iron", _iron_swatch),
        "dark_iron": lambda: swatch("dark_iron", _dark_iron_swatch),
        "crimson": lambda: swatch("crimson", _crimson_swatch),
        "brass": lambda: save_animation(brass_frames(), "brass", frametime=2),
        "leather": lambda: swatch("leather", _leather_swatch),
        "strap": lambda: swatch("strap", _strap_swatch),
        "horn": lambda: swatch("horn", _horn_swatch),
        "bone": lambda: swatch("bone", _bone_swatch),
        "lining": lambda: swatch("lining", lambda a: a.__setitem__(slice(None), C(FUR[0]))),
        "ember": lambda: save_animation([ember_frame(i / 12) for i in range(12)], "ember", frametime=2,
                                        interpolate=True),
    }
    for name, paint in swatches.items():
        if name not in skip:
            paint()


# --------------------------------------------------------------------------------------
# Shared 3D parts
# --------------------------------------------------------------------------------------

def fur_tufts(x0, x1, y, z0, z1, seed, size=1.0, down=True, step=1.1):
    """A ragged row of shaggy fur locks hanging from (or standing on) height y."""
    rng = random.Random(seed)
    parts = []
    x = x0
    while x < x1 - 0.2:
        w = min(step, x1 - x)
        h = size * rng.choice((0.7, 1.0, 1.3, 1.7))
        y0, y1 = (y - h, y) if down else (y, y + h)
        parts.append(box((x + 0.05, y0, z0), (x + w - 0.05, y1, z1), "fur",
                         offset=(rng.randrange(12), rng.randrange(12))))
        x += w
    return parts


def tilt_tufts(tufts, seed, hang=True, amount=14):
    rng = random.Random(seed)
    for e in tufts:
        lo, hi = e["from"], e["to"]
        turn(e, rng.uniform(-amount, amount), "z" if rng.random() < 0.5 else "x",
             ((lo[0] + hi[0]) / 2, hi[1] if hang else lo[1], (lo[2] + hi[2]) / 2))
    return tufts


def bell_3d(cx: float, top: float, z: float, r: float = 0.9) -> list[dict]:
    """A brass bell hanging from (cx, top, z): an iron loop, a flared turned body and an
    iron clapper under its mouth."""
    h = r * 2.0
    prof = [(0.0, r * 0.35), (-h * 0.2, r * 0.6), (-h * 0.55, r * 0.78), (-h * 0.85, r * 1.0), (-h, r * 1.1)]
    parts = lathe((cx, top - 0.3, z), prof, "brass", axis="y", sides=8, cap="brass")
    parts.append(box((cx - 0.12, top - 0.35, z - 0.3), (cx + 0.12, top + 0.15, z + 0.3), "iron"))
    parts.append(box((cx - 0.22, top - 0.3 - h - 0.35, z - 0.22), (cx + 0.22, top - 0.3 - h + 0.1, z + 0.22),
                     "dark_iron"))
    return parts


def chain_3d(p0, p1, sag: float, link: float = 0.75, thick: float = 0.22, z_out=None) -> list[dict]:
    """An iron chain slung from p0 to p1 (x, y, z points), sagging `sag` units at its
    middle: links alternate face-on and edge-on along the curve."""
    parts = []
    n = max(2, int(math.dist(p0, p1) / link))
    pts = []
    for k in range(n + 1):
        t = k / n
        p = [p0[i] + (p1[i] - p0[i]) * t for i in range(3)]
        p[1] -= sag * 4 * t * (1 - t)
        pts.append(p)
    for k in range(n):
        a, b = pts[k], pts[k + 1]
        if k % 2 == 0:
            parts.append(bar(a, b, link * 0.75, thick, "iron", offset=(k % 6, 0)))
        else:
            parts.append(bar(a, b, thick, link * 0.75, "iron", offset=(k % 6, 1)))
    return parts, pts


def spike(base, tip, w0: float, tex: str = "iron", steps: int = 4, bend=(0.0, 0.0, 0.0)) -> list[dict]:
    """A tapering spike from base to tip in `steps` stacked bars, each narrower, ending in
    a needle point; bend pushes its middle sideways so it rakes like a claw."""
    parts = []
    pts = []
    for k in range(steps + 1):
        t = k / steps
        b = 4 * t * (1 - t)
        pts.append([base[i] + (tip[i] - base[i]) * t + bend[i] * b for i in range(3)])
    for k in range(steps):
        p0, p1 = pts[k], pts[k + 1]
        p1 = [p0[i] + (p1[i] - p0[i]) * 1.12 for i in range(3)]
        wd = max(0.14, w0 * (1 - k / steps) ** 1.15)
        parts.append(bar(p0, p1, wd, wd, tex, offset=(k * 2, k)))
    return parts


def cowbell_3d(cx: float, top: float, z: float, s: float = 1.0) -> list[dict]:
    """A Krampus cowbell hung from (cx, top, z): an iron loop, a tapered brass body in
    three steps (wider at the mouth, flattened front to back), a dark mouth and an iron
    clapper hanging out of it."""
    p = [box((cx - 0.15 * s, top - 0.5 * s, z - 0.12), (cx + 0.15 * s, top, z + 0.12), "iron")]
    steps = ((0.0, 0.7, 0.45, 0.32), (0.7, 1.5, 0.6, 0.4), (1.5, 2.3, 0.75, 0.48))
    for y0, y1, hw, hd in steps:
        p.append(box((cx - hw * s, top - 0.5 * s - y1 * s, z - hd * s), (cx + hw * s, top - 0.5 * s - y0 * s,
                      z + hd * s), "brass", offset=(int(y0 * 4) % 8, 0 if y0 == 0 else 2)))
    yb = top - 0.5 * s - 2.3 * s
    p.append(box((cx - 0.82 * s, yb - 0.25 * s, z - 0.55 * s), (cx + 0.82 * s, yb + 0.05, z + 0.55 * s), "brass",
                 offset=(0, 3), faces={"down": "lining"}))
    p.append(box((cx - 0.22 * s, yb - 0.6 * s, z - 0.22 * s), (cx + 0.22 * s, yb - 0.2 * s, z + 0.22 * s),
                 "dark_iron"))
    return p


def emblem_3d(cx: float, cy: float, z: float, s: float = 1.0) -> list[dict]:
    """The Krampus emblem in 3D on a surface facing -z at depth z: an iron goat-demon face
    (wide brow tapering to the muzzle) in a brass frame, a heavy brow, glowing ember eyes,
    a snout, two bone fangs and curled horns rising from the temples."""
    p = []
    # the face plate in tapering steps, framed in brass
    rows = ((1.2, 2.0, 1.7), (0.0, 1.2, 1.55), (-1.0, 0.0, 1.2), (-1.9, -1.0, 0.85), (-2.5, -1.9, 0.55))
    for y0, y1, hw in rows:
        p.append(box((cx - (hw + 0.2) * s, cy + y0 * s, z - 0.2), (cx + (hw + 0.2) * s, cy + y1 * s, z + 0.4),
                     "brass", offset=(2, 1)))
        p.append(box((cx - hw * s, cy + y0 * s, z - 0.35), (cx + hw * s, cy + y1 * s, z), "iron",
                     offset=(int(y0 * 2) % 6 + 6, 2)))
    # the brow: a heavy dark ridge sloping down to the nose
    for sgn in (-1, 1):
        e = box((cx - 0.05 * s, cy + 0.85 * s, z - 0.75), (cx + sgn * 1.6 * s, cy + 1.35 * s, z - 0.3), "dark_iron")
        turn(e, sgn * 14, "z", (cx, cy + 0.9 * s, z))
        p.append(e)
    # the ember eyes, slanted, under the brow (the only glowing parts of the set)
    for sgn in (-1, 1):
        e = box((cx + sgn * 0.35 * s, cy + 0.25 * s, z - 0.45), (cx + sgn * 1.25 * s, cy + 0.65 * s, z - 0.3),
                "ember", glow=12, uv="full", shade=False)
        turn(e, sgn * 16, "z", (cx + sgn * 0.8 * s, cy + 0.45 * s, z))
        p.append(e)
    # the snout and nostrils
    p.append(box((cx - 0.5 * s, cy - 1.7 * s, z - 0.75), (cx + 0.5 * s, cy - 0.1 * s, z - 0.3), "iron",
                 offset=(4, 0)))
    p.append(box((cx - 0.4 * s, cy - 1.55 * s, z - 0.8), (cx + 0.4 * s, cy - 1.25 * s, z - 0.7), "dark_iron"))
    # the fangs
    for sgn in (-1, 1):
        p.append(bar((cx + sgn * 0.45 * s, cy - 2.2 * s, z - 0.35), (cx + sgn * 0.35 * s, cy - 3.0 * s, z - 0.45),
                     0.3 * s, 0.25, "bone", offset=(0, 0)))
    # the horns: curls from the temples, out, up and back in at the tips
    for sgn in (-1, 1):
        pts = []
        for k in range(7):
            t = k / 6
            ang = math.radians(-60 + 230 * t)
            r = 1.25 * s * (1 - 0.25 * t)
            hx = cx + sgn * (1.9 * s + r * math.cos(ang) * 0.9)
            hy = cy + 2.0 * s + r * math.sin(ang) + 0.2 * s
            pts.append((hx, hy, z - 0.15))
        for k in range(6):
            wd = (0.75 - 0.09 * k) * s
            p.append(bar(pts[k], pts[k + 1], wd, 0.45, "horn", offset=(k * 2 % 10, max(0, 12 - k * 2))))
    return p


# ======================================================================================
# Item model (front = north / -z, the wearer's right = +x, like the set's other pieces)
# ======================================================================================

def item_textures() -> None:
    a = blank(64, 64)
    a[0:48, 0:32] = paint_body_front(emblem_on=False, chain_on=False)
    a[0:48, 32:64] = paint_body_back()
    sides = blank(64, 64)
    side = paint_body_side()
    sides[0:48, 0:16] = side
    sides[0:48, 16:32] = side[:, ::-1]
    save(to_image(a), "torso")
    save(to_image(sides), "torso_side")
    save_set_materials(skip=("leather",))


def mantle() -> list[dict]:
    """The black goat-fur mantle: a thick, shaggy pelt round the neck and over the
    shoulders, long locks hanging from its edge front and back, tufts breaking its top."""
    parts = []
    y0, y1 = 11.0, 13.6
    parts += fur_roll((TX0 + 0.6, y0, TZ0 - 0.9), (TX1 - 0.6, y1, TZ0 + 1.1), r=0.5, seed=1)
    parts += fur_roll((TX0 + 0.6, y0 + 0.2, TZ1 - 1.1), (TX1 - 0.6, y1, TZ1 + 0.9), r=0.5, seed=2)
    for xa, xb in ((TX0 - 0.8, TX0 + 2.0), (TX1 - 2.0, TX1 + 0.8)):
        parts += fur_roll((xa, y0 - 0.3, TZ0 - 0.9), (xb, y1 - 0.1, TZ1 + 0.9), r=0.5, seed=3)
    rng0 = random.Random(11)
    for k, (xa, xb) in enumerate(((TX0 + 1.0, 6.3), (5.9, 10.1), (9.7, TX1 - 1.0))):
        dy = rng0.choice((0.25, 0.45, 0.6))
        parts += fur_roll((xa, y0 + 0.6, TZ0 - 1.2), (xb, y1 + dy, TZ0 + 0.4), r=0.45, seed=20 + k)
        parts += fur_roll((xa + 0.3, y0 + 0.7, TZ1 - 0.4), (xb - 0.3, y1 + dy * 0.7, TZ1 + 1.2), r=0.45,
                          seed=30 + k)
    parts.append(box((TX0 + 2.0, y1 - 0.9, TZ0 + 1.1), (TX1 - 2.0, y1 - 0.8, TZ1 - 1.1), "lining",
                     skip=("north", "south", "east", "west", "down")))
    hang = fur_tufts(TX0 + 0.6, TX1 - 0.6, y0 + 0.3, TZ0 - 0.85, TZ0 + 0.2, seed=4, size=0.9, step=0.8)
    hang += fur_tufts(TX0 + 0.6, TX1 - 0.6, y0 + 0.4, TZ1 - 0.2, TZ1 + 0.85, seed=5, size=1.0, step=0.85)
    tops = fur_tufts(TX0 + 1.0, TX1 - 1.0, y1 - 0.2, TZ0 - 0.7, TZ0 + 0.7, seed=7, size=0.45, down=False, step=1.1)
    tops += fur_tufts(TX0 + 1.0, TX1 - 1.0, y1 - 0.2, TZ1 - 0.8, TZ1 + 0.6, seed=8, size=0.4, down=False, step=1.2)
    tilt_tufts(hang, 7, True)
    tilt_tufts(tops, 9, False)
    return parts + hang + tops


def shoulder() -> list[dict]:
    """The wearer's right shoulder (+x): two blackened iron lames stacked like roof tiles,
    sawtoothed teeth along each foot, three iron spikes raking up and out, and the fur
    spilling over the top."""
    p = []
    pivot = (TX1, 12.0, 8.0)
    for i, (drop, out, tilt) in enumerate(((3.4, 3.6, -26), (1.4, 3.1, -14))):
        y1 = 12.2 - drop + 2.2
        y0 = y1 - 2.2
        lame = [box((TX1 - 0.3, y0, TZ0 - 0.6 - 0.15 * (1 - i)), (TX1 + out, y1, TZ1 + 0.6 + 0.15 * (1 - i)),
                    "iron", offset=(i * 3, 0))]
        lame.append(box((TX1 + out - 0.05, y0 + 0.2, TZ0 - 0.45), (TX1 + out + 0.2, y1 - 0.2, TZ1 + 0.45),
                        "dark_iron"))
        # sawtooth teeth hanging from the lame's outer foot
        z = TZ0 - 0.3
        while z < TZ1 + 0.3:
            t = box((TX1 + out - 0.5, y0 - 0.35, z), (TX1 + out + 0.1, y0 + 0.35, z + 0.7), "iron", offset=(1, 2))
            turn(t, 45, "x", (TX1 + out - 0.2, y0, z + 0.35))
            lame.append(t)
            z += 1.15
        for zz in (TZ0 + 0.2, TZ1 - 0.2):
            lame.append(box((TX1 + out + 0.15, y0 + 1.0, zz - 0.2), (TX1 + out + 0.38, y0 + 1.4, zz + 0.2), "brass"))
        turn(lame, tilt, "z", pivot)
        p += lame
    # three spikes on the upper lame, raking up and out
    for z, ln in ((TZ0 + 0.9, 2.4), (8.0, 3.2), (TZ1 - 0.9, 2.4)):
        base = (TX1 + 1.6, 12.6, z)
        tip = (TX1 + 1.6 + ln * 0.75, 12.6 + ln, z)
        p += spike(base, tip, 0.9, bend=(0.5, 0.0, 0.0))
    p += fur_roll((TX1 - 1.0, 10.9, TZ0 - 0.9), (TX1 + 1.8, 13.1, TZ1 + 0.9), r=0.5, seed=5)
    p += fur_roll((TX1 - 0.4, 11.6, TZ0 - 0.3), (TX1 + 1.1, 13.7, 8.2), r=0.4, seed=6)
    tufts = fur_tufts(TZ0 - 0.6, TZ1 + 0.6, 11.1, 0, 0.6, seed=13, size=0.8, step=0.9)
    for e in tufts:
        (xa, ya, za), (xb, yb, zb) = e["from"], e["to"]
        e["from"], e["to"] = [TX1 + 1.5 + za, ya, xa], [TX1 + 1.5 + zb, yb, xb]
    return p + tufts


def harness_3d() -> list[dict]:
    """Two dark straps over the shoulders down to the belt, with brass rivets, and the
    chain slung between them carrying three bells."""
    parts = []
    z = TZ0 - 0.15
    for c0, c1 in HARNESS:
        xa, xb = _x_front((c1 + 1) / 4), _x_front(c0 / 4)
        parts.append(box((xa, _y((BELT) / 4), z - 0.2), (xb, TY1 - 0.4, z), "strap", offset=(0, 1),
                         skip=("south",)))
        for row in (12, 26):
            x = (xa + xb) / 2
            parts.append(box((x - 0.2, _y((row + 1) / 4) - 0.2, z - 0.4), (x + 0.2, _y((row + 1) / 4) + 0.2, z - 0.2),
                             "brass"))
    xl, xr = _x_front(5.0 / 4), _x_front(27.0 / 4)
    yc = _y((CHAIN_Y + 0.5) / 4)
    links, _ = chain_3d((xl, yc, z - 0.45), (xr, yc, z - 0.45), CHAIN_SAG / 4 * (TY1 - TY0) / 12 * 1.05)
    parts += links
    return parts


def belt_3d() -> list[dict]:
    y1, y0 = _y(BELT / 4), _y((BELT + 4) / 4)
    parts = [box((TX0 - 0.2, y0, TZ0 - 0.2), (TX1 + 0.2, y1, TZ1 + 0.2), "strap",
                 faces={"up": "strap", "down": "lining"})]
    parts.append(box((7.0, y0 - 0.35, TZ0 - 0.45), (9.0, y1 + 0.35, TZ0 - 0.2), "brass"))
    parts.append(box((7.45, y0, TZ0 - 0.55), (8.55, y1, TZ0 - 0.4), "strap"))
    parts.append(box((7.85, y0 - 0.1, TZ0 - 0.65), (8.15, y1 + 0.1, TZ0 - 0.5), "iron"))
    # the cowbells hanging from the belt over the skirt, front left and right
    for col in BELLS:
        x = _x_front((col + 3) / 4)
        parts += cowbell_3d(x, y0 + 0.1, TZ0 - 0.95, 0.9)
    return parts


def skirt_3d() -> list[dict]:
    """The crimson coat-skirt below the belt: flaps a little proud of the coat, splaying
    out, each ending in a point, and a black fur hem rolled round the foot."""
    parts = []
    rng = random.Random(21)
    y1, y0 = _y((BELT + 4) / 4), TY0 + 0.4
    n = 5
    span = TX1 - TX0 + 0.4
    wv = span / n
    for side, z0, z1 in (("north", TZ0 - 0.32, TZ0 - 0.02), ("south", TZ1 + 0.02, TZ1 + 0.32)):
        for k in range(n):
            xa = TX0 - 0.2 + k * wv + 0.06
            xb = xa + wv - 0.12
            drop = rng.choice((0.0, 0.2, 0.35))
            flap = [box((xa, y0 + drop, z0), (xb, y1, z1), "crimson", offset=(k * 3 % 10, 2))]
            ang = (k - (n - 1) / 2) * 2.4
            for e in flap:
                turn(e, ang, "z", ((xa + xb) / 2, y1, (z0 + z1) / 2))
                turn(e, 6 if side == "north" else -6, "x", ((xa + xb) / 2, y1, (z0 + z1) / 2))
            parts += flap
    parts += fur_roll((TX0 - 0.6, TY0 - 0.7, TZ0 - 0.9), (TX1 + 0.6, TY0 + 1.1, TZ1 + 0.9), r=0.45, seed=9)
    hem = fur_tufts(TX0 - 0.5, TX1 + 0.5, TY0 - 0.5, TZ0 - 0.85, TZ0 - 0.25, seed=12, size=0.55, step=0.8)
    hem += fur_tufts(TX0 - 0.5, TX1 + 0.5, TY0 - 0.5, TZ1 + 0.25, TZ1 + 0.85, seed=13, size=0.55, step=0.8)
    return parts + tilt_tufts(hem, 3, True, 10)


def build() -> list[dict]:
    right = shoulder()
    cx, cy = 8.0, _y((EMBLEM_TOP + EMBLEM_H * 0.58) / 4)
    return (torso() + mantle() + right + mirror(right, "x", 8.0) + harness_3d() + belt_3d() + skirt_3d()
            + emblem_3d(cx, cy, TZ0 - 0.2, 1.1))


def textures() -> None:
    save_layer(paint_humanoid(), "humanoid")
    item_textures()


def models() -> dict:
    parts = build()
    d = display(KIND, parts, gui_rotation=(15, 158, 0), gui_span=15.5)
    centre = (8.0, 8.0, 8.0)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, centre, "fist", 0.42)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.4, 0, -1)}, centre, (0.52, -0.38, -0.95), 0.5,
                                       pose=None)
    return {"main": model(parts, d)}
