"""Nordic Plate: the chest piece of the Nordic set (November crate), and the set's pilot
module: the Helm, the Greaves and the Treads import its palette and painters from here,
so the four pieces share one palette, one trim and one emblem.

Set language (rugged Viking kit in late-autumn / cold-weather colours):
  * blackened, hammered iron plates with bright rivets;
  * a mail byrnie (chainmail) worn over a dark woad-slate wool tunic;
  * oiled dark leather straps and belts (the Treads' own leather);
  * grey wolf-fur trim with dark-tipped guard hairs;
  * antique bronze rims carrying interlaced knotwork;
  * a tablet-woven band in muted madder red and ochre;
  * the Nordic boss: a round bronze boss with a knotwork ring round a glowing amber stone.

Worn (humanoid layer, 4x): a wolf-fur mantle over the shoulders, two leather straps
holding a riveted iron breastplate with a knotwork bronze rim and the boss at its heart,
mail round it, a leather belt with a bronze buckle, the mail's bronze-ringed edge and
the wool tunic's woven hem under it. Shoulders: fur over two riveted iron lames with
bronze rims, mail sleeves, leather bracers with a knotwork band. Back: the mantle, two
straps crossing at a bronze ring, the belt and the hem.

Item: the same paintings on a hollow torso, with the fur mantle, the layered shoulder
lames, the breastplate, the belt and the boss built in 3D; the boss's amber glows and a
warm glint sweeps over it.
"""
from __future__ import annotations

import math
import random

import numpy as np
from PIL import Image

from art.items.snowveil_guard import (C, _hash, band_mask, blank, edges, fur_roll, grid, pick, scallop, shadow,
                                      shift, sync_overlap, to_image)
from art.kit import (HUMANOID, box, display, lathe, mirror, model, place, ramp, region, save, save_animation,
                     save_layer, turn)

ID = "nordic_plate"
NAME = "Nordic Plate"
KIND = "chestplate"
COUNTERPART = "item/netherite_chestplate"

# ======================================================================================
# NORDIC SET PALETTE AND PAINTERS (imported by the other Nordic pieces)
# LEATHER and STRAP are the Treads' (the old Autumn Wanderer's) leather, darkened to an
# oiled brown; the rest is a cold, restrained iron / fur / slate palette with bronze,
# madder and amber as the only warm accents.
# ======================================================================================

IRON = ["#121418", "#1d2026", "#2a2e35", "#3a3f47", "#4f555e", "#6a717b", "#8f969f", "#c2c7cd"]
BRONZE = ["#3e2712", "#61401c", "#875c27", "#a97934", "#c49646", "#ddb768", "#f1dba0"]
LEATHER = ramp("#7a5236", 7, 0.8)
STRAP = ramp("#4f3224", 7, 0.8)
FUR = ["#221e1b", "#352f2a", "#4b433b", "#62584c", "#7b7060", "#958977", "#afa38f", "#c9bfab"]
WOOL = ["#121821", "#19212c", "#212b38", "#2a3646", "#344254", "#3f4e62", "#4c5c71", "#5c6c82"]
MADDER = ["#35130e", "#521d15", "#6f2a1e", "#8c3a29", "#a85038"]
OCHRE = ["#4f3714", "#75541e", "#9c7430", "#bf9746", "#d9b968"]
AMBER = ["#4a1f04", "#7c3a07", "#b5600e", "#e08a19", "#f6b240", "#ffdc86", "#fff4cf"]

S = 4   # worn layers are painted at 4x (256x128): 4 texels per model unit


def mask_rect(h: int, w: int, x0: int, y0: int, x1: int, y1: int) -> np.ndarray:
    """Inclusive rectangle mask."""
    m = np.zeros((h, w), bool)
    m[max(0, y0):y1 + 1, max(0, x0):x1 + 1] = True
    return m


def wool(a: np.ndarray, mask=None, seed: int = 0, bias=0.0) -> None:
    """Woad-slate wool twill: fine diagonal ribs, a few darker slubs. bias lightens
    (> 0) or darkens (< 0): a number or per-texel array."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    rib = ((X + Y) % 3 == 0)
    idx = 4 - 1 * rib + np.where(_hash(X // 3, Y // 2, seed) > 0.9, -1, 0)
    idx = np.rint(idx + np.asarray(bias) * 3).astype(int)
    paint = pick(WOOL, idx)
    if mask is None:
        a[:] = paint
    else:
        a[mask] = paint[mask]


def mail(a: np.ndarray, mask=None, seed: int = 0, bias=0.0) -> None:
    """Riveted chainmail: staggered rows of rings 4 texels wide and 3 tall, each lit along
    its top, shaded underneath, with the dark wool showing through the links. bias
    lightens or darkens (number or per-texel array)."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    row = Y // 3
    lx, ly = (X + 2 * (row % 2)) % 4, Y % 3
    idx = np.full((h, w), 1)
    idx = np.where((ly == 0) & ((lx == 1) | (lx == 2)), 6, idx)
    idx = np.where((ly == 1) & (lx == 0), 5, idx)
    idx = np.where((ly == 1) & (lx == 3), 3, idx)
    idx = np.where((ly == 2) & ((lx == 1) | (lx == 2)), 3, idx)
    idx = np.where((ly == 1) & ((lx == 1) | (lx == 2)), 0, idx)
    ring = idx > 1
    tarnish = _hash(X // 4, row, seed) > 0.85
    idx = idx - (ring & tarnish)
    idx = np.rint(idx + np.asarray(bias) * 2 * ring).astype(int)
    paint = pick(IRON, idx)
    if mask is None:
        a[:] = paint
    else:
        a[mask] = paint[mask]


def iron(a: np.ndarray, mask: np.ndarray, seed: int = 0, bias: float = 0.0, ao: float = 0.45) -> None:
    """A hammered, blackened iron plate over the mask: soft hammer dents, a lit top and
    left rim, a dark bottom and right rim, a contact shadow outside."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    dent = _hash(X // 3, Y // 3, seed)
    idx = 4 + np.where(dent > 0.8, 1, 0) - np.where(dent < 0.15, 1, 0)
    idx = idx + np.where(_hash(X, Y, seed + 3) > 0.94, -1, 0)
    idx = np.rint(idx + bias * 2).astype(int)
    if ao:
        shadow(a, mask, ao, reach=1)
    a[mask] = pick(IRON, idx)[mask]
    top, bottom, left, right = edges(mask)
    a[top] = C(IRON[7])
    a[left & ~top] = C(IRON[6])
    a[bottom] = C(IRON[1])
    a[right & ~bottom & ~top] = C(IRON[2])


def rivet(a: np.ndarray, x: int, y: int, metal=None) -> None:
    """A 2x2 domed rivet head (iron by default)."""
    m = metal or IRON
    lit, hi = (m[-1], m[-2])
    h, w = a.shape[:2]
    for dx, dy, c in ((0, 0, lit), (1, 0, hi), (0, 1, m[-3]), (1, 1, m[1])):
        if 0 <= x + dx < w and 0 <= y + dy < h:
            a[y + dy, x + dx] = C(c)


def bronze(a: np.ndarray, mask: np.ndarray, ao: float = 0.4) -> None:
    """A plain cast-bronze rim over the mask: lit top and left, dark bottom and right."""
    if ao:
        shadow(a, mask, ao, reach=1)
    a[mask] = C(BRONZE[3])
    top, bottom, left, right = edges(mask)
    a[top | left] = C(BRONZE[5])
    a[bottom | right] = C(BRONZE[1])


def knot_band(a: np.ndarray, x0: int, x1: int, y0: int, h: int = 6, phase: int = 0, ao: float = 0.4) -> None:
    """A bronze band carrying an interlaced plait: a lit rim on top, a dark rim below, and
    between them two rounded strands twisting over and under each other on a dark
    ground (the strand on top alternates at every crossing, with a gap cut round it)."""
    H, W = a.shape[:2]
    m = mask_rect(H, W, x0, y0, x1, y0 + h - 1)
    bronze(a, m, ao=ao)
    inner = h - 2
    if inner < 2:
        return
    mid = y0 + 1 + (inner - 1) / 2
    amp = (inner - 1) / 2
    period = 4 * inner - 2
    for x in range(max(0, x0), min(W, x1 + 1)):
        t = 2 * math.pi * (x - x0 + phase + 0.5) / period
        ya, yb = mid + amp * math.sin(t), mid - amp * math.sin(t)
        top_is_a = math.cos(t) > 0
        for r in range(inner):
            a[y0 + 1 + r, x] = C(BRONZE[0])
        order = ((yb, False), (ya, True)) if top_is_a else ((ya, False), (yb, True))
        for ys, over in order:
            for r in range(inner):
                y = y0 + 1 + r
                d = y - ys
                if abs(d) <= 0.75:
                    a[y, x] = C(BRONZE[6] if d < -0.25 else BRONZE[4] if d < 0.3 else BRONZE[2])
                    if not over:
                        a[y, x] = C(BRONZE[3] if d < 0.3 else BRONZE[1])
                elif over and abs(d) <= 1.3 and abs(ya - yb) < 1.6:
                    a[y, x] = C(BRONZE[0])
    a[y0, max(0, x0):x1 + 1] = C(BRONZE[5])
    a[y0 + h - 1, max(0, x0):x1 + 1] = C(BRONZE[1])


def knot_column(a: np.ndarray, x0: int, y0: int, y1: int, w: int = 6, phase: int = 0) -> None:
    """knot_band() turned upright (for vertical trims)."""
    sub = np.ascontiguousarray(a[y0:y1 + 1, x0:x0 + w].transpose(1, 0, 2))
    knot_band(sub, 0, sub.shape[1] - 1, 0, w, phase, ao=0)
    a[y0:y1 + 1, x0:x0 + w] = sub.transpose(1, 0, 2)


def weave(a: np.ndarray, x0: int, x1: int, y0: int, h: int = 5) -> None:
    """The tablet-woven band: dark leather selvedges and, between them, little diamonds of
    madder red and ochre on a deep red ground."""
    H, W = a.shape[:2]
    mid = y0 + (h - 1) / 2
    for y in range(y0, min(H, y0 + h)):
        for x in range(max(0, x0), min(W, x1 + 1)):
            if y in (y0, y0 + h - 1):
                c = STRAP[1] if y > y0 else STRAP[3]
            else:
                d = abs(y - mid)
                k = int((x - x0 + d) // 2) % 3
                c = (OCHRE[4], MADDER[4], MADDER[1])[k]
                if k == 0 and y > mid:
                    c = OCHRE[3]
            a[y, x] = C(c)


def leather(a: np.ndarray, mask: np.ndarray, seed: int = 0, pal=None, stitch: bool = True, ao: float = 0.4) -> None:
    """Oiled leather strap: lit top edge, dark bottom edge, a stitched line inside each
    long edge, a few creases."""
    pal = pal or STRAP
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    if ao:
        shadow(a, mask, ao, reach=1)
    idx = 2 + np.where(_hash(X // 2, Y, seed) > 0.88, -1, 0)
    a[mask] = pick(pal, idx)[mask]
    top, bottom, left, right = edges(mask)
    a[top | left] = C(pal[4])
    a[bottom | right] = C(pal[0])
    if stitch:
        inner_top = shift(top, 0, 1) & mask & ~bottom
        inner_bot = shift(bottom, 0, -1) & mask & ~top
        a[(inner_top | inner_bot) & (X % 2 == 0)] = C(LEATHER[4])


def fur(a: np.ndarray, mask: np.ndarray, seed: int = 0, hang: bool = True, lift: int = 0, edge: bool = True,
        cell: int = 2) -> None:
    """Grey wolf fur: tufts of warm grey with long streaks of dark-tipped guard hair and
    pale underfur, brightest away from the free edge, the free edge shaded and ragged.
    hang=True: the band hangs down (free edge at the bottom); False: it rises.
    edge=False paints an even field (swatches, tops)."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    r = _hash(Y // (cell + 1), X // cell + (Y // (cell + 1)) % 2, seed)
    idx = np.where(r < 0.2, 4, np.where(r < 0.6, 5, 6)) + lift
    dist = np.full((h, w), 9, np.int32)
    for k in range(8, 0, -1) if edge else ():
        out = mask & ~shift(mask, 0, -k if hang else k)
        dist[out] = k - 1
    idx = idx - np.select([dist == 0, dist == 1, dist == 2], [3, 2, 1], 0)
    paint = pick(FUR, idx)
    guard = (_hash(X, Y // 3, seed + 5) > 0.86) & (dist > 0)
    paint[guard] = C(FUR[3])
    pale = (_hash(X + 7, Y // 2, seed + 6) > 0.9) & (dist > 1) & ~guard
    paint[pale] = C(FUR[7])
    a[mask] = paint[mask]
    if hang:
        shadow(a, mask, 0.5, reach=2, down_only=True)


def boss(a: np.ndarray, cx: float, cy: float, r: int, amber: bool = True) -> np.ndarray:
    """The Nordic boss centred on (cx, cy): a round bronze boss with a bevelled rim, a ring
    of knotwork beads and, when amber, a domed amber stone at its heart (lit at the upper
    left, a white glint). Returns its mask."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    dx, dy = X + 0.5 - cx, Y + 0.5 - cy
    d = np.sqrt(dx * dx + dy * dy)
    plate = d <= r
    shadow(a, plate, 0.55, reach=2)
    lit = (dx + dy) < 0
    a[plate] = C(BRONZE[3])
    a[plate & (d > r - 1.1) & lit] = C(BRONZE[5])
    a[plate & (d > r - 1.1) & ~lit] = C(BRONZE[1])
    if r >= 5:
        ring = plate & (d > r - 2.6) & (d <= r - 1.1)
        ang = np.arctan2(dy, dx)
        beads = (np.floor((ang + math.pi) / (2 * math.pi) * (r * 3)) % 2) == 0
        a[ring & beads] = C(BRONZE[4])
        a[ring & ~beads] = C(BRONZE[0])
        a[ring & beads & lit] = C(BRONZE[6])
    core = r - (2.6 if r >= 5 else 1.1)
    stone = plate & (d <= core)
    if amber:
        k = np.clip(((dx + dy) / max(core, 1) + 1.2) / 2.4 * 5, 0, 5).astype(int)
        a[stone] = pick(AMBER[1:][::-1], k)[stone]
        a[stone & (d > core - 1)] = C(AMBER[2])
        gx, gy = int(round(cx - core * 0.4 - 0.5)), int(round(cy - core * 0.4 - 0.5))
        if 0 <= gx < w and 0 <= gy < h:
            a[gy, gx] = C(AMBER[6])
    else:
        a[stone] = C(BRONZE[2])
        a[stone & lit] = C(BRONZE[4])
    return plate


def buckle(a: np.ndarray, cx: int, y0: int, h: int, w: int = 8) -> None:
    """A cast bronze belt buckle (a frame with a tongue) centred on column cx."""
    H, W = a.shape[:2]
    x0 = cx - w // 2
    m = mask_rect(H, W, x0, y0, x0 + w - 1, y0 + h - 1)
    bronze(a, m, ao=0.5)
    inner = mask_rect(H, W, x0 + 2, y0 + 2, x0 + w - 3, y0 + h - 3)
    a[inner] = C(STRAP[2])
    a[y0 + 1:y0 + h - 1, cx - 1] = C(BRONZE[6])
    a[y0 + 1:y0 + h - 1, cx] = C(BRONZE[2])


def belt(a: np.ndarray, y0: int, h: int = 4, buckle_x=None, seed: int = 0) -> None:
    """The leather belt across a face (rows y0..y0+h-1), with a buckle when buckle_x."""
    H, W = a.shape[:2]
    leather(a, mask_rect(H, W, 0, y0, W - 1, y0 + h - 1), seed=seed)
    if buckle_x is not None:
        buckle(a, buckle_x, y0 - 1, h + 2)


# ======================================================================================
# Worn layer (humanoid, 256x128). Body faces 32x48 texels, body sides and arm faces
# 16x48, body top 32x16, arm top 16x16.
# ======================================================================================

MANTLE = 8                       # the wolf-fur mantle's base lower edge (texel row)
PLATE_ROWS = (11, 31)            # the breastplate's rows
PLATE_COLS = (5, 26)             # ... and columns at its widest
BOSS_C, BOSS_R = (16, 22), 5     # the boss on the breastplate
BELT = 33                        # the belt's top row (4 rows)
MAIL_EDGE = 41                   # the mail's bronze-ringed edge
HEM = 43                         # the woven hem band's top row (4 rows)


def mantle_rows(w: int, seed: int) -> list[int]:
    return scallop(w, MANTLE, seed, depth=2, tuft=3, symmetric=w == 32)


def body_base(w: int, h: int, seed: int) -> np.ndarray:
    """Mail over the tunic: mail down to its bronze-ringed edge, the wool and its woven
    hem below, the belt across."""
    a = blank(w, h)
    X, Y = grid(h, w)
    side = -0.25 * (np.abs(X + 0.5 - w / 2) / (w / 2)) ** 2 if w == 32 else 0.0
    mail(a, seed=seed, bias=side - 0.25 - 0.15 * (Y / h))
    tunic = mask_rect(h, w, 0, MAIL_EDGE, w - 1, h - 1)
    wool(a, tunic, seed=seed + 1, bias=-0.2)
    shadow(a, mask_rect(h, w, 0, 0, w - 1, MAIL_EDGE - 1), 0.5, reach=1, down_only=True)
    for x in range(w):                           # the mail's edge: a row of bronze rings
        a[MAIL_EDGE - 1, x] = C(BRONZE[4] if x % 2 == 0 else BRONZE[1])
    weave(a, 0, w - 1, HEM, 4)
    a[h - 1, :] = C(WOOL[1])
    belt(a, BELT, 4, seed=seed + 2)
    return a


def fur_top(a: np.ndarray, rows: list[int], seed: int) -> None:
    h, w = a.shape[:2]
    fur(a, band_mask(h, w, bottom=rows), seed=seed)


def plate_mask(h: int, w: int) -> np.ndarray:
    """The breastplate: square shoulders, its lower part tapering to a rounded point."""
    y0, y1 = PLATE_ROWS
    c0, c1 = PLATE_COLS
    m = np.zeros((h, w), bool)
    for y in range(y0, y1 + 1):
        t = max(0, y - (y1 - 7))
        inset = t + (t * t) // 6
        m[y, c0 + inset:c1 - inset + 1] = True
    return m


def breastplate(a: np.ndarray, seed: int) -> None:
    h, w = a.shape[:2]
    m = plate_mask(h, w)
    X, _ = grid(h, w)
    iron(a, m, seed=seed, bias=0.6, ao=0.6)
    # the plate is raised along its centre: a lit ridge, the right half a touch darker
    a[m & (X == 15)] = C(IRON[7])
    a[m & (X == 16)] = C(IRON[4])
    right_half = m & (X > 16)
    a[right_half, :3] *= 0.88
    # a knotwork bronze rim along the top, plain bronze down the sides
    y0, _ = PLATE_ROWS
    c0, c1 = PLATE_COLS
    knot_band(a, c0, c1, y0, 5, ao=0)
    rim = m & ~shift(m, 1, 0) | m & ~shift(m, -1, 0) | m & ~shift(m, 0, -1)
    rim &= ~mask_rect(h, w, 0, 0, w - 1, y0 + 4)
    a[rim] = C(BRONZE[3])
    a[rim & (grid(h, w)[0] < w / 2)] = C(BRONZE[4])
    a[m & ~shift(m, 0, -1)] = C(BRONZE[1])
    # rivets just inside the rim
    for y in range(y0 + 7, PLATE_ROWS[1] - 6, 4):
        rivet(a, c0 + 2, y)
        rivet(a, c1 - 3, y)
    boss(a, BOSS_C[0], BOSS_C[1], BOSS_R)


def straps_front(a: np.ndarray, seed: int) -> None:
    """Two shoulder straps holding the breastplate, each with a bronze stud."""
    h, w = a.shape[:2]
    for x0 in (8, 20):
        leather(a, mask_rect(h, w, x0, 0, x0 + 3, PLATE_ROWS[0]), seed=seed + x0, stitch=False)
        rivet(a, x0 + 1, PLATE_ROWS[0] - 3, BRONZE)


def paint_body_front(seed: int = 11) -> np.ndarray:
    w, h = 8 * S, 12 * S
    a = body_base(w, h, seed)
    straps_front(a, seed)
    breastplate(a, seed + 3)
    belt(a, BELT, 4, buckle_x=16, seed=seed + 2)
    fur_top(a, mantle_rows(w, seed + 5), seed + 6)
    return a


def paint_body_back(seed: int = 23) -> np.ndarray:
    w, h = 8 * S, 12 * S
    a = body_base(w, h, seed)
    # two straps crossing at a bronze ring between the shoulder blades
    X, Y = grid(h, w)
    cx, cy = 15.5, 21.0
    for sgn in (1, -1):
        d = (X - cx) - sgn * (Y - cy) * 0.95
        m = (np.abs(d) <= 1.6) & (Y >= MANTLE - 1) & (Y < BELT)
        leather(a, m, seed=seed + sgn, stitch=False, ao=0.35)
    boss(a, 16, cy + 0.5, 4, amber=False)
    belt(a, BELT, 4, seed=seed + 2)
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
    a[:, 2 * S:6 * S] = C(WOOL[0])      # under the head
    a[:, 2 * S, :3] = C(FUR[3])[:3]
    return a


LAMES = ((5, 11), (11, 17))   # the two iron shoulder lames (rows), the lower overlapping
BRACER = (31, 44)             # the leather bracer's rows


def paint_arm_face(kind: str, seed: int) -> np.ndarray:
    """One side of the right arm. kind: "front" (left = outside), "back" (left = inside),
    "outer" (left = back, right = front), "inner" (left = front, right = back)."""
    w, h = 4 * S, 12 * S
    a = blank(w, h)
    mail(a, seed=seed, bias=-0.1)
    wool(a, mask_rect(h, w, 0, BRACER[1] + 1, w - 1, h - 1), seed=seed + 1, bias=-0.2)
    # the bracer: dark leather, a knotwork band at its top, bronze studs or lacing
    m = mask_rect(h, w, 0, BRACER[0], w - 1, BRACER[1])
    leather(a, m, seed=seed + 2, stitch=False, ao=0.5)
    knot_band(a, 0, w - 1, BRACER[0], 5, phase=seed % 4, ao=0)
    if kind == "inner":
        for y in range(BRACER[0] + 6, BRACER[1], 3):
            a[y, 6:10] = C(LEATHER[4])
            a[y + 1, 7:9] = C(STRAP[0])
    else:
        for y in range(BRACER[0] + 7, BRACER[1] - 1, 4):
            rivet(a, 3, y, BRONZE)
            rivet(a, 11, y, BRONZE)
    a[BRACER[1], :] = C(STRAP[1])
    # the shoulder lames, lower first so the upper one overlaps it
    for i, (y0, y1) in enumerate(LAMES[::-1]):
        lame = mask_rect(h, w, 0, y0, w - 1, y1)
        iron(a, lame, seed=seed + 5 + i, bias=0.1 * i, ao=0.6)
        a[y1, :] = C(BRONZE[1])
        a[y1 - 1, :] = C(BRONZE[4])
        for x in (2, 7, 12):
            rivet(a, x, y0 + 2, None)
    if kind == "outer":
        boss(a, 8, LAMES[0][0] + 6, 3, amber=False)
    fur_top(a, scallop(w, 5, seed + 3, depth=1, tuft=3), seed + 4)
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
    bottom[:] = C(WOOL[1])
    put("body_bottom", bottom)
    put("arm_front", arm_front)
    put("arm_back", arm_back)
    put("arm_outer", paint_arm_face("outer", 53))
    put("arm_inner", paint_arm_face("inner", 54))
    put("arm_top", paint_arm_top())
    bottom = blank(4 * S, 4 * S)
    bottom[:] = C(WOOL[1])
    put("arm_bottom", bottom)
    return to_image(out)


# ======================================================================================
# Item textures (shared with the set's 3D parts)
# ======================================================================================

def swatch(name: str, paint) -> None:
    """A 64x64 swatch (4 texels per unit with uv "true") painted by paint(a)."""
    a = blank(64, 64)
    paint(a)
    save(to_image(a), name)


def _iron_swatch(a):
    """Blackened iron for the 3D parts: broad, soft hammer dents in dark steel, one lit row
    along the top (faces start at v 0 to catch it) and a few bright pocks."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    dent = (_hash(X // 6, Y // 5, 7) + _hash((X + 3) // 6, (Y + 2) // 5, 8)) / 2
    idx = 4 + np.where(dent > 0.68, 1, 0) - np.where(dent < 0.28, 1, 0)
    a[:] = pick(IRON, idx)
    a[_hash(X, Y, 9) > 0.985] = C(IRON[6])
    a[0] = C(IRON[6])
    a[1] = C(IRON[4])


def _bronze_swatch(a):
    a[:] = C(BRONZE[3])
    a[0] = C(BRONZE[6])
    a[1] = C(BRONZE[5])
    a[2] = C(BRONZE[4])
    X, Y = grid(64, 64)
    a[(Y > 3) & ((X + Y) % 5 == 0)] = C(BRONZE[2])


def _knot_swatch(a):
    for y in range(0, 60, 6):
        knot_band(a, 0, 63, y, 6, phase=y, ao=0)
    knot_band(a, 0, 63, 58, 6, ao=0)


def _leather_swatch(a):
    leather(a, np.ones(a.shape[:2], bool), seed=5, stitch=False, ao=0)
    a[0] = C(STRAP[5])
    a[1] = C(STRAP[4])
    a[2, ::2] = C(LEATHER[5])


def _weave_swatch(a):
    for y in range(0, 60, 5):
        weave(a, 0, 63, y, 5)
    weave(a, 0, 63, 59, 5)


def _fur_swatch(a):
    fur(a, np.ones((64, 64), bool), seed=3, hang=False, lift=1, edge=False, cell=3)


def amber_frame(t: float) -> Image.Image:
    """The amber stone (16 px, glowing), shaded as a cabochon: a dark rim, honey deepening
    toward the lower right, a lit upper left with a hot highlight, and a warm glint that
    slides across it in the first half of the loop."""
    a = blank(16, 16)
    X, Y = grid(16, 16)
    d = X + Y
    r = np.maximum(np.abs(X - 7.5), np.abs(Y - 7.5))
    a[:] = pick([AMBER[5], AMBER[4], AMBER[3], AMBER[2]], np.clip((d - 2) // 7, 0, 3))
    a[r > 6] = C(AMBER[2])
    a[(r > 6) & (d > 15)] = C(AMBER[1])
    a[(r > 6) & (d < 9)] = C(AMBER[3])
    a[3:6, 3:6] = C(AMBER[5])
    a[3:5, 3:5] = C(AMBER[6])
    a[10:12, 11:13] = C(AMBER[4])
    if t < 0.5:
        pos = -4 + 40 * t / 0.5
        k = np.clip(1 - np.abs(d - pos) / 3.0, 0, 1)[..., None]
        glint = np.array([255, 244, 207], np.float32)
        a[..., :3] = a[..., :3] * (1 - k * 0.8) + glint * k * 0.8
    return to_image(a)


def save_set_materials(skip=()) -> None:
    """The swatches the Nordic 3D parts use (leave out the ones a piece never uses)."""
    swatches = {
        "fur": lambda: swatch("fur", _fur_swatch),
        "iron": lambda: swatch("iron", _iron_swatch),
        "bronze": lambda: swatch("bronze", _bronze_swatch),
        "knot": lambda: swatch("knot", _knot_swatch),
        "leather": lambda: swatch("leather", _leather_swatch),
        "weave": lambda: swatch("weave", _weave_swatch),
        "mail": lambda: swatch("mail", lambda a: mail(a, seed=9, bias=0.4)),
        "lining": lambda: swatch("lining", lambda a: a.__setitem__(slice(None), C(WOOL[2]))),
        "amber": lambda: save_animation([amber_frame(i / 12) for i in range(12)], "amber", frametime=3),
    }
    for name, paint in swatches.items():
        if name not in skip:
            paint()


def boss_3d(cx: float, cy: float, z: float, r: float, facing: str = "north", stone: bool = True) -> list[dict]:
    """The Nordic boss in 3D, standing out of a surface at depth z: a turned bronze dome
    (lathe) with a knotwork collar and, when stone, a glowing amber cabochon. facing is the
    side it looks out of ("north" = -z, "south", "east" = +x, "west")."""
    sgn = -1 if facing in ("north", "west") else 1
    prof = [(0.0, r), (0.35, r), (0.35, r * 0.82), (0.6, r * 0.82), (0.6, r * 0.62)]
    parts = lathe((cx, cy, z), [(sgn * h, rr) for h, rr in prof], "bronze", axis="z", sides=16, cap="knot")
    if stone:
        gem = [(0.55, r * 0.55), (0.95, r * 0.5), (1.15, r * 0.3)]
        parts += lathe((cx, cy, z), [(sgn * h, rr) for h, rr in gem], "amber", axis="z", sides=8, cap="amber",
                       glow=12, uv="full")
    else:
        parts += lathe((cx, cy, z), [(sgn * 0.55, r * 0.4), (sgn * 0.9, r * 0.25)], "bronze", axis="z", sides=8)
    if facing in ("east", "west"):
        turn(parts, 90, "y", (cx, cy, z))
    return parts


# ======================================================================================
# Item model (front = north / -z, the wearer's right = +x, like the set's other pieces)
# ======================================================================================

TX0, TX1, TY0, TY1, TZ0, TZ1 = 3.5, 12.5, 0.5, 13.0, 5.6, 10.4
WALL = 0.7


def _u_front(x):
    return (TX1 - x) * 8 / (TX1 - TX0)


def _u_back(x):
    return 8 + (x - TX0) * 8 / (TX1 - TX0)


def _v(y):
    return (TY1 - y) * 12 / (TY1 - TY0)


def _y(v):
    """The model height showing painting row v (uv units, 4 texels each)."""
    return TY1 - v * (TY1 - TY0) / 12


def _x_front(u):
    return TX1 - u * (TX1 - TX0) / 8


def torso_faces(frm, to, inner=()):
    (xa, ya, za), (xb, yb, zb) = frm, to
    d = TZ1 - TZ0
    f = {"north": ("torso", [_u_front(xb), _v(yb), _u_front(xa), _v(ya)]),
         "south": ("torso", [_u_back(xa), _v(yb), _u_back(xb), _v(ya)]),
         "east": ("torso_side", [(TZ1 - zb) * 4 / d, _v(yb), (TZ1 - za) * 4 / d, _v(ya)]),
         "west": ("torso_side", [4 + (za - TZ0) * 4 / d, _v(yb), 4 + (zb - TZ0) * 4 / d, _v(ya)]),
         "up": ("fur", [0, 0, xb - xa, zb - za]),
         "down": ("lining", [0, 0, xb - xa, zb - za])}
    for side in inner:
        f[side] = ("lining", [0, 0, 1, 1])
    return f


def item_textures() -> None:
    torso = blank(64, 64)
    torso[0:48, 0:32] = paint_body_front()
    torso[0:48, 32:64] = paint_body_back()
    sides = blank(64, 64)
    side = paint_body_side()
    sides[0:48, 0:16] = side
    sides[0:48, 16:32] = side[:, ::-1]
    save(to_image(torso), "torso")
    save(to_image(sides), "torso_side")
    save_set_materials(skip=("mail",))


def torso() -> list[dict]:
    t = WALL
    walls = [((TX0, TY0, TZ0), (TX1, TY1, TZ0 + t), ("south",), ()),
             ((TX0, TY0, TZ1 - t), (TX1, TY1, TZ1), ("north",), ()),
             ((TX1 - t, TY0, TZ0 + t), (TX1, TY1, TZ1 - t), ("west",), ("north", "south")),
             ((TX0, TY0, TZ0 + t), (TX0 + t, TY1, TZ1 - t), ("east",), ("north", "south"))]
    parts = [box(a, b, "torso", faces=torso_faces(a, b, inner), skip=skip) for a, b, inner, skip in walls]
    parts.append(box((TX0 + t, TY0 + 0.3, TZ0 + t), (TX1 - t, TY1 - 1.0, TZ1 - t), "lining",
                     skip=("north", "south", "east", "west", "down")))
    return parts


def fur_tufts(x0, x1, y, z0, z1, seed, size=1.0, down=True, step=1.1):
    """A ragged row of fur tufts hanging from (or standing on) height y across x0..x1."""
    rng = random.Random(seed)
    parts = []
    x = x0
    while x < x1 - 0.2:
        w = min(step, x1 - x)
        h = size * rng.choice((0.6, 0.9, 1.1, 1.4))
        y0, y1 = (y - h, y) if down else (y, y + h)
        parts.append(box((x + 0.05, y0, z0), (x + w - 0.05, y1, z1), "fur",
                         offset=(rng.randrange(12), rng.randrange(12))))
        x += w
    return parts


def mantle() -> list[dict]:
    """The wolf-fur mantle: a thick pelt round the neck and over the shoulders, its edge
    hanging in ragged tufts, and tufts breaking its top."""
    parts = []
    y0, y1 = 11.4, 13.4
    parts += fur_roll((TX0 + 0.8, y0, TZ0 - 0.6), (TX1 - 0.8, y1, TZ0 + 1.1), seed=1)
    parts += fur_roll((TX0 + 0.8, y0 + 0.2, TZ1 - 1.1), (TX1 - 0.8, y1, TZ1 + 0.6), seed=2)
    for xa, xb in ((TX0 - 0.5, TX0 + 1.8), (TX1 - 1.8, TX1 + 0.5)):
        parts += fur_roll((xa, y0 - 0.3, TZ0 - 0.6), (xb, y1 - 0.2, TZ1 + 0.6), seed=3)
    parts.append(box((TX0 + 1.8, y1 - 0.9, TZ0 + 1.1), (TX1 - 1.8, y1 - 0.8, TZ1 - 1.1), "lining",
                     skip=("north", "south", "east", "west", "down")))
    hang = fur_tufts(TX0 + 0.8, TX1 - 0.8, y0 + 0.3, TZ0 - 0.55, TZ0 + 0.2, seed=4, size=0.7, step=0.9)
    hang += fur_tufts(TX0 + 0.8, TX1 - 0.8, y0 + 0.4, TZ1 - 0.2, TZ1 + 0.55, seed=5, size=0.8, step=1.0)
    rng = random.Random(7)
    tops = fur_tufts(TX0 + 1.0, TX1 - 1.0, y1 - 0.2, TZ0 - 0.4, TZ0 + 0.7, seed=7, size=0.35, down=False, step=1.2)
    tops += fur_tufts(TX0 + 1.0, TX1 - 1.0, y1 - 0.2, TZ1 - 0.8, TZ1 + 0.4, seed=8, size=0.3, down=False, step=1.3)
    for e in hang + tops:
        lo, hi = e["from"], e["to"]
        turn(e, rng.uniform(-12, 12), "z" if rng.random() < 0.5 else "x",
             ((lo[0] + hi[0]) / 2, hi[1] if e in hang else lo[1], (lo[2] + hi[2]) / 2))
    return parts + hang + tops


def shoulder() -> list[dict]:
    """The wearer's right shoulder (+x): two riveted iron lames, the upper overlapping the
    lower, each with a bronze rim, a small boss on the outside, the fur over the top."""
    p = []
    for y0, y1, out in ((7.0, 9.3, 2.35), (8.9, 11.2, 2.75)):
        p.append(box((TX1 - 0.1, y0 + 0.35, TZ0 - 0.35), (TX1 + out, y1, TZ1 + 0.35), "iron"))
        p.append(box((TX1 - 0.05, y0, TZ0 - 0.45), (TX1 + out + 0.1, y0 + 0.4, TZ1 + 0.45), "bronze"))
        for z in (TZ0 + 0.3, 8.0, TZ1 - 0.3):
            p.append(box((TX1 + out, y0 + 0.75, z - 0.22), (TX1 + out + 0.15, y0 + 1.2, z + 0.22), "iron",
                         offset=(0, 0)))
    p += boss_3d(TX1 + 2.85, 10.1, 8.0, 0.8, facing="east", stone=False)
    p += fur_roll((TX1 - 0.7, 10.9, TZ0 - 0.6), (TX1 + 2.4, 12.7, TZ1 + 0.6), r=0.5, seed=5)
    tufts = fur_tufts(TZ0 - 0.4, TZ1 + 0.4, 11.0, 0, 0.6, seed=13, size=0.65)
    for e in tufts:
        (xa, ya, za), (xb, yb, zb) = e["from"], e["to"]
        e["from"], e["to"] = [TX1 + 2.1 + za, ya, xa], [TX1 + 2.1 + zb, yb, xb]
    return p + tufts


def plate_3d() -> list[dict]:
    """The breastplate standing proud of the mail, wearing its painting, with a bronze
    rim along the top and the boss at its heart."""
    y_top, y_bot = _y(PLATE_ROWS[0] / 4), _y((PLATE_ROWS[1] + 1) / 4)
    xa, xb = _x_front((PLATE_COLS[1] + 1) / 4), _x_front(PLATE_COLS[0] / 4)
    split = _y((PLATE_ROWS[1] - 7) / 4)
    z = TZ0 - 0.25

    def face(x0, x1, y0, y1):
        return {"north": ("torso", [_u_front(x1), _v(y1), _u_front(x0), _v(y0)]), "east": "iron", "west": "iron",
                "up": "bronze", "down": "iron"}

    parts = [box((xa, split, z), (xb, y_top, TZ0), "iron", faces=face(xa, xb, split, y_top), skip=("south",))]
    # the tapering point, in two steps
    for k, (y0, y1) in enumerate(((_y((PLATE_ROWS[1] - 3) / 4), split), (y_bot, _y((PLATE_ROWS[1] - 3) / 4)))):
        inset = (k + 1) * 0.75 + k * 0.3
        x0, x1 = xa + inset, xb - inset
        parts.append(box((x0, y0, z), (x1, y1, TZ0), "iron", faces=face(x0, x1, y0, y1), skip=("south", "up")))
    parts.append(box((xa - 0.1, y_top - 1.25, z - 0.2), (xb + 0.1, y_top + 0.05, TZ0), "knot",
                     faces={"north": ("knot", [0, 0, xb - xa, 1.3]), "up": "bronze", "down": "bronze",
                            "east": "bronze", "west": "bronze"}, skip=("south",)))
    cx, cy = 8.0, _y((BOSS_C[1]) / 4)
    parts += boss_3d(cx, cy, z, 1.55)
    return parts


def straps_3d() -> list[dict]:
    parts = []
    for col in (8, 20):
        x1, x0 = _x_front(col / 4), _x_front((col + 4) / 4)
        parts.append(box((x0, _y(PLATE_ROWS[0] / 4) - 0.1, TZ0 - 0.15), (x1, 11.6, TZ0), "leather",
                         skip=("south", "up")))
    return parts


def belt_3d() -> list[dict]:
    y1, y0 = _y(BELT / 4), _y((BELT + 4) / 4)
    parts = [box((TX0 - 0.2, y0, TZ0 - 0.2), (TX1 + 0.2, y1, TZ1 + 0.2), "leather",
                 faces={"up": "leather", "down": "lining"})]
    parts.append(box((7.0, y0 - 0.35, TZ0 - 0.45), (9.0, y1 + 0.35, TZ0 - 0.2), "bronze"))
    parts.append(box((7.55, y0, TZ0 - 0.55), (8.45, y1, TZ0 - 0.4), "leather"))
    parts.append(box((7.9, y0 - 0.1, TZ0 - 0.65), (8.15, y1 + 0.1, TZ0 - 0.5), "bronze"))
    return parts


def hem_3d() -> list[dict]:
    """The woven hem band round the bottom, a little proud of the tunic."""
    y1, y0 = _y(HEM / 4), _y((HEM + 4) / 4)
    return [box((TX0 - 0.12, y0, TZ0 - 0.12), (TX1 + 0.12, y1, TZ1 + 0.12), "weave",
                faces={"north": ("weave", [0, 0, 9, 1]), "south": ("weave", [0, 0, 9, 1]),
                       "east": ("weave", [0, 0, 4.8, 1]), "west": ("weave", [0, 0, 4.8, 1]), "up": "lining",
                       "down": "lining"})]


def build() -> list[dict]:
    right = shoulder()
    return (torso() + mantle() + right + mirror(right, "x", 8.0) + straps_3d() + plate_3d() + belt_3d()
            + hem_3d())


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
