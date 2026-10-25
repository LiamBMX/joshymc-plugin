"""Viking Chestplate: the chest piece of the Viking set (issue #1294, Survival), and the
set's pilot module: the Helmet, the Leggings and the Boots import its palette and
painters from here, so the four pieces share one palette, one trim and one boss.

Set language (a raider's war kit, heavy and battle-worn):
  * weathered silver-grey steel, laid as lamellar (rows of small riveted plates laced
    together with leather), scratched and pitted, brightest on its edges;
  * brown leather: an oiled mid-brown for straps and skirts, a dark brown for belts and
    lacing;
  * brown bear fur at the shoulders, the helmet's nape and the boots' cuffs (darker and
    warmer than the Nordic set's grey wolf);
  * a dark oxblood wool tunic showing under the steel, the only colour besides the metal;
  * small bronze rivets and rims as the only warm metal (no bright gold);
  * the Viking boss: a round, domed steel shield-boss with a ring of bronze rivets round
    a polished silver crown that catches a light glint (animated, no glow).

Worn (humanoid layer, 4x): a bear-fur mantle over the shoulders, a lamellar cuirass down
to the belt, a leather baldric from the right shoulder to the left hip carrying the boss
over the heart, a dark belt with a steel buckle, and a skirt of riveted leather strips
over the oxblood tunic. Arms: fur over two layered steel pauldron lames, lamellar
sleeves, steel vambraces strapped with leather. Back: the mantle, the lamellar, the baldric
running down to the hip, the belt and the skirt.

Item: the same paintings on a hollow torso, with the fur mantle, the stacked pauldrons,
the baldric, the boss, the belt and the skirt strips built in 3D.
"""
from __future__ import annotations

import math
import random

import numpy as np
from PIL import Image

from art.items.snowveil_guard import (C, _hash, band_mask, blank, edges, fur_roll, grid, pick, scallop, shadow,
                                      shift, sync_overlap, to_image)
from art.kit import (HUMANOID, bar, box, display, lathe, mirror, model, place, ramp, region, save,
                     save_animation, save_layer, turn)

ID = "viking_chestplate"
NAME = "Viking Chestplate"
KIND = "chestplate"
COUNTERPART = "item/netherite_chestplate"

# ======================================================================================
# VIKING SET PALETTE AND PAINTERS (imported by the other Viking pieces)
# ======================================================================================

STEEL = ["#15181c", "#23272d", "#33383f", "#474d55", "#5e656e", "#7a828b", "#99a1a9", "#bcc3c9", "#e1e5e8"]
BRONZE = ["#3c2814", "#5e401e", "#835c2a", "#a3783a", "#c19651", "#dcb97c"]
LEATHER = ramp("#73492a", 7, 0.8)
STRAP = ramp("#3f2a1b", 7, 0.8)
FUR = ["#1c1713", "#2a221c", "#3a3027", "#4a3a2c", "#5c4a39", "#715c48", "#8a7560", "#9a8a78", "#b3a592"]
WOOL = ["#1d0c0b", "#2b1210", "#3a1915", "#4a201b", "#5b2922", "#6c332a", "#7d3e33"]
SILVER = ["#3a4048", "#5d656e", "#87909a", "#adb5bd", "#cdd3d8", "#e9edf0", "#ffffff"]

S = 4   # worn layers are painted at 4x (256x128): 4 texels per model unit


def mask_rect(h: int, w: int, x0: int, y0: int, x1: int, y1: int) -> np.ndarray:
    """Inclusive rectangle mask."""
    m = np.zeros((h, w), bool)
    m[max(0, y0):max(0, y1 + 1), max(0, x0):max(0, x1 + 1)] = True
    return m


def wool(a: np.ndarray, mask=None, seed: int = 0, bias=0.0) -> None:
    """Oxblood wool: a quiet, near-solid field with a broken tabby weave (scattered lit
    texel pairs) and a few darker slubs. bias lightens (> 0) or darkens (< 0)."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    tabby = ((X // 2 + Y) % 2 == 0) & (_hash(X // 2, Y, seed + 2) > 0.55)
    idx = 3 + 1 * tabby + np.where(_hash(X // 3, Y // 2, seed) > 0.93, -1, 0)
    idx = np.rint(idx + np.asarray(bias) * 3).astype(int)
    paint = pick(WOOL, idx)
    if mask is None:
        a[:] = paint
    else:
        a[mask] = paint[mask]


def steel(a: np.ndarray, mask: np.ndarray, seed: int = 0, bias=0.0, ao: float = 0.45, rim: bool = True) -> None:
    """Weathered steel over the mask: soft pits and long scratches, a bright lit top and
    left edge, a dark bottom and right edge, a contact shadow outside."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    n = smooth_noise(h, w, 6, seed=seed)
    idx = 5 + (n - 0.5) * 2.2
    idx = idx - (_hash(X, Y, seed + 3) > 0.975)
    scratch = ((X * 2 + Y + seed) % 13 == 0) & (_hash(X // 4, Y // 5, seed + 7) > 0.7)
    idx = idx + scratch
    idx = np.rint(idx + np.asarray(bias) * 2).astype(int)
    if ao:
        shadow(a, mask, ao, reach=1)
    a[mask] = pick(STEEL, idx)[mask]
    if rim:
        top, bottom, left, right = edges(mask)
        a[top] = C(STEEL[8])
        a[left & ~top] = C(STEEL[7])
        a[bottom] = C(STEEL[1])
        a[right & ~bottom & ~top] = C(STEEL[2])


def rivet(a: np.ndarray, x: int, y: int, metal=None) -> None:
    """A 2x2 domed rivet head (bronze by default)."""
    m = metal or BRONZE
    h, w = a.shape[:2]
    for dx, dy, c in ((0, 0, m[-1]), (1, 0, m[-2]), (0, 1, m[-3]), (1, 1, m[1])):
        if 0 <= x + dx < w and 0 <= y + dy < h:
            a[y + dy, x + dx] = C(c)


def lamellar(a: np.ndarray, mask: np.ndarray, seed: int = 0, bias=0.0, plate: int = 3, course: int = 6,
             ao: float = 0.0) -> None:
    """Lamellar armour over the mask: courses `course` texels tall of narrow steel lames
    `plate` texels wide, staggered course to course so they read as overlapping scales.
    Kept low in contrast (about two ramp steps): each lame is lit down its left edge and
    along its rounded top, a step darker down its right, and its foot tucks behind the
    course below one step darker again. Every other course carries one continuous line
    of dark leather lacing. bias lightens or darkens (number or per-texel array)."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    row = Y // course
    sx = X + (plate // 2 + 1) * (row % 2)
    lx = sx % plate
    ly = Y % course
    cell = _hash(sx // plate, row, seed)
    idx = 5 + np.where(cell > 0.85, 1, 0) - np.where(cell < 0.12, 1, 0)
    idx = idx + np.where(lx == 0, 1, 0) - np.where(lx == plate - 1, 1, 0)
    corner = (ly == 0) & ((lx == 0) | (lx == plate - 1))
    idx = np.where((ly == 0) & ~corner, 7, idx)
    idx = np.where(corner, 4, idx)
    idx = np.where(ly == course - 1, 3, idx)
    pitted = (_hash(X, Y, seed + 4) > 0.97) & (ly > 0) & (ly < course - 1)
    idx = idx - pitted
    idx = np.rint(idx + np.asarray(bias) * 2).astype(int)
    paint = pick(STEEL, idx)
    lace = (ly == 2) & (row % 2 == 0)
    paint[lace] = C(STRAP[2])
    if ao:
        shadow(a, mask, ao, reach=1)
    a[mask] = paint[mask]


def leather(a: np.ndarray, mask: np.ndarray, seed: int = 0, pal=None, stitch: bool = True, ao: float = 0.4) -> None:
    """Oiled leather: lit top/left edge, dark bottom/right edge, stitched lines inside
    the long edges, a few creases and scuffs."""
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
        a[(inner_top | inner_bot) & (X % 2 == 0)] = C(LEATHER[5])


def fur(a: np.ndarray, mask: np.ndarray, seed: int = 0, hang: bool = True, lift: int = 0, edge: bool = True,
        cell: int = 1) -> None:
    """Bear fur in greyed browns: long, thin locks of hair (2*cell texels wide, 4*cell + 2
    long), staggered like shingles, each lit toward its point, the dark roots of the next
    course showing round it, a few dark guard hairs and pale tips. When edge, the free
    edge is ragged (some texels left unpainted so whatever is under shows through) and
    its tips are lit. hang=True: the band hangs down (free edge at the bottom); False: it
    rises. edge=False paints an even field (swatches, tops)."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    lw, ll = 2 * cell, 4 * cell + 2
    col = X // lw
    off = (_hash(col, 0, seed) * ll).astype(int)
    yy = Y + off if hang else -Y + off + 4096
    v = (yy % ll) / (ll - 1)                          # 0 at the root, 1 at the tip
    u = np.abs((X % lw) - (lw - 1) / 2) / (lw / 2)    # 0 in the lock's middle, ~1 at its sides
    end = 1.0 - 0.3 * u * u
    r = _hash(col, yy // ll, seed + 1)
    idx = 3.0 + v * 2.2 - u * 0.5 + (r - 0.5) * 1.4
    idx = np.where(v > end, 2.2 + (r - 0.5), idx)      # the shadowed root below the point
    idx = np.rint(idx).astype(int) + lift
    dist = np.full((h, w), 9, np.int32)
    for k in range(8, 0, -1) if edge else ():
        out = mask & ~shift(mask, 0, -k if hang else k)
        dist[out] = k - 1
    idx = idx + np.select([dist == 0, dist == 1, dist == 2], [-1, 1, 1], 0)
    paint = pick(FUR, idx)
    guard = (_hash(X, Y // 3, seed + 5) > 0.97) & (dist > 1)
    paint[guard] = C(FUR[1])
    pale = (_hash(X + 7, Y // 2, seed + 6) > 0.97) & ~guard & (v > 0.55) & (v <= end)
    paint[pale] = C(FUR[8])
    ragged = mask & (((dist == 0) & (_hash(X, Y, seed + 8) > 0.45)) | ((dist == 1) & (_hash(X, Y, seed + 9) > 0.85)))
    paint_mask = mask & ~ragged
    a[paint_mask] = paint[paint_mask]
    if hang:
        shadow(a, paint_mask, 0.45, reach=2, down_only=True)


def boss(a: np.ndarray, cx: float, cy: float, r: int) -> np.ndarray:
    """The Viking boss centred on (cx, cy): a round, domed steel shield-boss with a dark
    flange, a ring of bronze rivets on the flange, and a polished silver crown lit at the
    upper left with a white glint. Returns its mask."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    dx, dy = X + 0.5 - cx, Y + 0.5 - cy
    d = np.sqrt(dx * dx + dy * dy)
    plate = d <= r
    shadow(a, plate, 0.55, reach=2)
    lit = (dx + dy) < 0
    a[plate] = C(STEEL[4])
    a[plate & (d > r - 1.1) & lit] = C(STEEL[7])
    a[plate & (d > r - 1.1) & ~lit] = C(STEEL[1])
    crown_r = r - (2.4 if r >= 5 else 1.2)
    if r >= 4:
        ang = np.arctan2(dy, dx)
        n = 8 if r >= 5 else 6
        slot = np.floor((ang + math.pi) / (2 * math.pi) * n + 0.5) % n
        centre = slot * 2 * math.pi / n - math.pi
        on = np.abs(np.angle(np.exp(1j * (ang - centre)))) * (r - 1.6) < 0.8
        ring = plate & (d > crown_r) & (d <= r - 1.1)
        a[ring & on] = C(BRONZE[4])
        a[ring & on & ~lit] = C(BRONZE[2])
    crown = plate & (d <= crown_r)
    k = np.clip(((dx + dy) / max(crown_r, 1) + 1.3) / 2.6 * 5, 0, 5).astype(int)
    a[crown] = pick(SILVER[1:6][::-1], k)[crown]
    a[crown & (d > crown_r - 0.9) & ~lit] = C(SILVER[1])
    gx, gy = int(round(cx - crown_r * 0.4 - 0.5)), int(round(cy - crown_r * 0.4 - 0.5))
    if 0 <= gx < w and 0 <= gy < h:
        a[gy, gx] = C(SILVER[6])
    return plate


def buckle(a: np.ndarray, cx: int, y0: int, h: int, w: int = 8) -> None:
    """A forged steel belt buckle (a square frame with a bronze tongue) on column cx."""
    H, W = a.shape[:2]
    x0 = cx - w // 2
    m = mask_rect(H, W, x0, y0, x0 + w - 1, y0 + h - 1)
    steel(a, m, seed=3, bias=0.8, ao=0.5)
    inner = mask_rect(H, W, x0 + 2, y0 + 2, x0 + w - 3, y0 + h - 3)
    a[inner] = C(STRAP[2])
    a[y0 + 1:y0 + h - 1, cx - 1] = C(BRONZE[5])
    a[y0 + 1:y0 + h - 1, cx] = C(BRONZE[2])


def belt(a: np.ndarray, y0: int, h: int = 4, buckle_x=None, seed: int = 0) -> None:
    """The dark leather belt across a face (rows y0..y0+h-1), a buckle when buckle_x."""
    H, W = a.shape[:2]
    leather(a, mask_rect(H, W, 0, y0, W - 1, y0 + h - 1), seed=seed)
    if buckle_x is not None:
        buckle(a, buckle_x, y0 - 1, h + 2)


def strips(a: np.ndarray, y0: int, y1: int, width: int = 5, phase: int = 0, seed: int = 0) -> None:
    """A skirt of hanging leather strips (rows y0..y1) over the wool: strips `width`
    texels wide with a 1-texel gap, each with a bronze rivet near its foot and a rounded,
    slightly ragged end."""
    H, W = a.shape[:2]
    X, Y = grid(H, W)
    rng = random.Random(seed)
    x = -phase
    while x < W:
        end = y1 - rng.choice((0, 0, 1))
        m = mask_rect(H, W, x, y0, x + width - 2, end)
        if m.any():
            m[end, max(0, x)] = False
            m[end, min(W - 1, max(0, x + width - 2))] = False
            leather(a, m, seed=seed + x, pal=LEATHER, stitch=False, ao=0.5)
            mid = x + (width - 1) // 2 - 1
            if 0 <= mid < W - 1:
                rivet(a, mid, end - 4)
        x += width
    shadow(a, mask_rect(H, W, 0, y0 - 1, W - 1, y0 - 1), 0.0)


def edge_rows(a: np.ndarray, y: int) -> None:
    """A dark lacing line along row y where a lamellar band meets leather."""
    a[y, :] = C(STRAP[1])
    a[y, ::3] = C(STRAP[3])


# ======================================================================================
# Worn layer (humanoid, 256x128). Body faces 32x48 texels, body sides and arm faces
# 16x48, body top 32x16, arm top 16x16.
# ======================================================================================

MANTLE = 8                       # the bear-fur mantle's base lower edge (texel row)
LAMELLAR = (8, 32)               # the cuirass's rows
BOSS_C, BOSS_R = (12, 18), 6     # the boss over the heart (front face; wearer's right is
                                 # the image's left)
BELT = 32                        # the belt's top row (4 rows)
SKIRT = (36, 46)                 # the leather strips


def mantle_rows(w: int, seed: int) -> list[int]:
    return scallop(w, MANTLE, seed, depth=2, tuft=3, symmetric=w == 32)


def body_base(w: int, h: int, seed: int) -> np.ndarray:
    """Lamellar down to the belt over the wool tunic, the skirt strips below."""
    a = blank(w, h)
    X, Y = grid(h, w)
    side = -0.3 * (np.abs(X + 0.5 - w / 2) / (w / 2)) ** 2 if w == 32 else -0.1
    wool(a, seed=seed + 1, bias=-0.1)
    lam = mask_rect(h, w, 0, 0, w - 1, BELT - 1)
    lamellar(a, lam, seed=seed, bias=side + 0.05 - 0.35 * (Y / h))
    edge_rows(a, BELT - 1)
    strips(a, BELT + 4, SKIRT[1], phase=1 if w == 32 else 2, seed=seed + 9)
    a[h - 1, :] = np.where(a[h - 1, :, 3:4] > 0, a[h - 1, :] * np.array([0.8, 0.8, 0.8, 1]), a[h - 1, :])
    belt(a, BELT, 4, seed=seed + 2)
    return a


def fur_top(a: np.ndarray, rows: list[int], seed: int) -> None:
    h, w = a.shape[:2]
    fur(a, band_mask(h, w, bottom=rows), seed=seed)


def baldric_mask(h: int, w: int, x_top: float, y_top: int, x_bot: float, y_bot: int, half: float = 2.0):
    X, Y = grid(h, w)
    t = (Y - y_top) / max(1, y_bot - y_top)
    cx = x_top + (x_bot - x_top) * t
    return (np.abs(X + 0.5 - cx) <= half) & (Y >= y_top) & (Y <= y_bot)


def baldric(a: np.ndarray, m: np.ndarray, seed: int, studs=()) -> None:
    leather(a, m, seed=seed, pal=LEATHER, stitch=False, ao=0.55)
    X, Y = grid(*a.shape[:2])
    rows = np.where(m.any(axis=1))[0]
    for y in rows:
        xs = np.where(m[y])[0]
        if len(xs):
            a[y, xs[0]] = C(LEATHER[5])
            a[y, xs[-1]] = C(LEATHER[0])
    for x, y in studs:
        rivet(a, x, y, STEEL[3:])


def paint_body_front(seed: int = 11) -> np.ndarray:
    w, h = 8 * S, 12 * S
    a = body_base(w, h, seed)
    # the baldric: over the wearer's right shoulder (image left) down to the left hip
    m = baldric_mask(h, w, 6.5, 0, 27.0, BELT - 1, 2.2)
    baldric(a, m, seed + 4, studs=((9, 9), (19, 21), (23, 26)))
    belt(a, BELT, 4, buckle_x=16, seed=seed + 2)
    fur_top(a, mantle_rows(w, seed + 5), seed + 6)
    boss(a, BOSS_C[0] + 0.5, BOSS_C[1] + 0.5, BOSS_R)
    return a


def paint_body_back(seed: int = 23) -> np.ndarray:
    w, h = 8 * S, 12 * S
    a = body_base(w, h, seed)
    # on the back the baldric runs from the wearer's right shoulder (image right) down
    # to the left hip (image left)
    m = baldric_mask(h, w, 25.5, 0, 5.0, BELT - 1, 2.2)
    baldric(a, m, seed + 4, studs=((21, 12), (11, 24)))
    belt(a, BELT, 4, seed=seed + 2)
    for x in (4, 26):
        rivet(a, x, BELT + 1, STEEL[3:])
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
    return a


LAMES = ((3, 11), (10, 19))  # the two steel pauldron lames (rows), the upper overlapping the lower
SLEEVE = (20, 29)            # lamellar down the upper arm
BRACER = (30, 44)            # the steel vambrace


def paint_arm_face(kind: str, seed: int) -> np.ndarray:
    """One side of the right arm. kind: "front" (left = outside), "back" (left = inside),
    "outer" (left = back, right = front), "inner" (left = front, right = back)."""
    w, h = 4 * S, 12 * S
    a = blank(w, h)
    wool(a, seed=seed + 1, bias=-0.15)
    # lamellar down the upper arm to the vambrace
    lamellar(a, mask_rect(h, w, 0, SLEEVE[0], w - 1, BRACER[0] - 1), seed=seed + 2, bias=-0.15)
    # the vambrace: weathered steel splints, a ridge down the middle, two leather straps
    m = mask_rect(h, w, 0, BRACER[0], w - 1, BRACER[1])
    steel(a, m, seed=seed + 3, bias=-0.1, ao=0.5)
    for y in (BRACER[0] + 3, BRACER[1] - 4):
        leather(a, mask_rect(h, w, 0, y, w - 1, y + 1), seed=seed + y, stitch=False, ao=0.4)
    if kind == "outer":
        rivet(a, 3, BRACER[0] + 7, STEEL[3:])
        rivet(a, 11, BRACER[0] + 7, STEEL[3:])
    a[BRACER[1], :] = C(STEEL[1])
    # the pauldron lames, lower first so the upper one overlaps it; bronze trims only the
    # foot of the lower lame
    for i, (y0, y1) in enumerate(LAMES[::-1]):
        lame = mask_rect(h, w, 0, y0, w - 1, y1)
        steel(a, lame, seed=seed + 5 + i, bias=0.1 + 0.2 * i, ao=0.6)
        if i == 0:
            a[y1, :] = C(BRONZE[1])
            a[y1 - 1, :] = C(BRONZE[3])
        for x in (2, 12) if kind != "outer" else (2, 7, 12):
            rivet(a, x, y0 + 3, STEEL[3:] if i else BRONZE)
    fur_top(a, scallop(w, 4, seed + 3, depth=1, tuft=3), seed + 4)
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


def _steel_swatch(a):
    """Weathered steel for the 3D parts: broad soft pits, scratches, a lit top row (faces
    start at v 0 to catch it) and a few bright nicks."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    # soft, low-frequency tarnish (smooth value noise) so the metal never reads as cells
    n = smooth_noise(h, w, 12, seed=7) * 0.65 + smooth_noise(h, w, 5, seed=8) * 0.35
    idx = np.rint(5 + (n - 0.5) * 2.0).astype(int)
    # short diagonal wear streaks (never long horizontal ones, which read as planks)
    streak = _hash(X + Y, (X - Y + 64) // 6, 3) > 0.94
    idx = idx + streak
    a[:] = pick(STEEL, idx)
    # rare nicks and pits from battle
    a[(_hash(X, Y, 9) > 0.994)] = C(STEEL[8])
    a[(_hash(X, Y, 10) > 0.994)] = C(STEEL[3])
    a[0] = C(STEEL[8])
    a[1] = C(STEEL[6])


def smooth_noise(h: int, w: int, scale: int, seed: int = 0) -> np.ndarray:
    """Smooth value noise in 0..1 (bilinear over a hashed lattice `scale` texels apart),
    wrapping every 64 texels so swatches tile."""
    X, Y = grid(h, w)
    gx, gy = X / scale, Y / scale
    x0, y0 = np.floor(gx).astype(int), np.floor(gy).astype(int)
    fx, fy = gx - x0, gy - y0
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    period = max(1, 64 // scale)

    def lat(i, j):
        return _hash(i % period, j % period, seed)

    top = lat(x0, y0) * (1 - fx) + lat(x0 + 1, y0) * fx
    bot = lat(x0, y0 + 1) * (1 - fx) + lat(x0 + 1, y0 + 1) * fx
    return top * (1 - fy) + bot * fy


def _dark_steel_swatch(a):
    _steel_swatch(a)
    a[..., :3] *= 0.72
    a[0] = C(STEEL[6])


def _lamellar_swatch(a):
    lamellar(a, np.ones(a.shape[:2], bool), seed=5, bias=0.1)


def _bronze_swatch(a):
    a[:] = C(BRONZE[3])
    a[0] = C(BRONZE[5])
    a[1] = C(BRONZE[4])
    X, Y = grid(64, 64)
    a[(Y > 2) & ((X + 2 * Y) % 7 == 0)] = C(BRONZE[2])


def _leather_swatch(a):
    leather(a, np.ones(a.shape[:2], bool), seed=5, pal=LEATHER, stitch=False, ao=0)
    a[0] = C(LEATHER[5])
    a[1] = C(LEATHER[4])
    a[2, ::2] = C(LEATHER[6])


def _strap_swatch(a):
    leather(a, np.ones(a.shape[:2], bool), seed=6, stitch=False, ao=0)
    a[0] = C(STRAP[5])
    a[1, ::2] = C(LEATHER[4])


def _fur_swatch(a):
    fur(a, np.ones((64, 64), bool), seed=3, hang=True, lift=0, edge=False, cell=2)


def _wool_swatch(a):
    wool(a, seed=4, bias=0.1)


def silver_frame(t: float) -> Image.Image:
    """The polished silver crown of the boss (16 px), shaded as a dome: lit at the upper
    left, a cool shadow at the lower right, a white hot spot, and a light glint that
    slides across it in the first half of the loop."""
    a = blank(16, 16)
    X, Y = grid(16, 16)
    d = X + Y
    r = np.maximum(np.abs(X - 7.5), np.abs(Y - 7.5))
    a[:] = pick([SILVER[5], SILVER[4], SILVER[3], SILVER[2]], np.clip((d - 2) // 7, 0, 3))
    a[r > 6] = C(SILVER[2])
    a[(r > 6) & (d > 15)] = C(SILVER[1])
    a[(r > 6) & (d < 9)] = C(SILVER[4])
    a[3:6, 3:6] = C(SILVER[5])
    a[3:5, 3:5] = C(SILVER[6])
    if t < 0.5:
        pos = -4 + 40 * t / 0.5
        k = np.clip(1 - np.abs(d - pos) / 3.0, 0, 1)[..., None]
        a[..., :3] = a[..., :3] * (1 - k * 0.75) + 255 * k * 0.75
    return to_image(a)


def save_set_materials(skip=()) -> None:
    """The swatches the Viking 3D parts use (leave out the ones a piece never uses)."""
    swatches = {
        "fur": lambda: swatch("fur", _fur_swatch),
        "steel": lambda: swatch("steel", _steel_swatch),
        "dark_steel": lambda: swatch("dark_steel", _dark_steel_swatch),
        "lamellar": lambda: swatch("lamellar", _lamellar_swatch),
        "bronze": lambda: swatch("bronze", _bronze_swatch),
        "leather": lambda: swatch("leather", _leather_swatch),
        "strap": lambda: swatch("strap", _strap_swatch),
        "wool": lambda: swatch("wool", _wool_swatch),
        "lining": lambda: swatch("lining", lambda a: a.__setitem__(slice(None), C(STRAP[0]))),
        "silver": lambda: save_animation([silver_frame(i / 12) for i in range(12)], "silver", frametime=3),
    }
    for name, paint in swatches.items():
        if name not in skip:
            paint()


def boss_3d(cx: float, cy: float, z: float, r: float, facing: str = "north") -> list[dict]:
    """The Viking boss in 3D, standing out of a surface at depth z: a turned steel flange
    and dome (lathe) with bronze rivets round the flange and a polished silver crown.
    facing is the side it looks out of ("north" = -z, "south", "east" = +x, "west")."""
    sgn = -1 if facing in ("north", "west") else 1
    prof = [(0.0, r), (0.3, r), (0.3, r * 0.74), (0.75, r * 0.66), (1.0, r * 0.5)]
    parts = lathe((cx, cy, z), [(sgn * h, rr) for h, rr in prof], "steel", axis="z", sides=16, cap="dark_steel")
    parts += lathe((cx, cy, z), [(sgn * 0.95, r * 0.5), (sgn * 1.25, r * 0.38), (sgn * 1.4, r * 0.2)], "silver",
                   axis="z", sides=8, cap="silver", uv="full")
    n = 8 if r >= 1.2 else 6
    for k in range(n):
        ang = 2 * math.pi * (k + 0.5) / n
        x, y = cx + math.cos(ang) * r * 0.86, cy + math.sin(ang) * r * 0.86
        s = 0.16 if r >= 1.2 else 0.12
        z0, z1 = (z + sgn * 0.3, z + sgn * 0.48)
        parts.append(box((x - s, y - s, min(z0, z1)), (x + s, y + s, max(z0, z1)), "bronze"))
    if facing in ("east", "west"):
        turn(parts, 90, "y", (cx, cy, z))
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
    save_set_materials(skip=("wool", "lamellar"))


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


def mantle() -> list[dict]:
    """The bear-fur mantle: a thick pelt round the neck and over the shoulders, its edge
    hanging in ragged tufts, and tufts breaking its top."""
    parts = []
    y0, y1 = 11.2, 13.5
    parts += fur_roll((TX0 + 0.8, y0, TZ0 - 0.7), (TX1 - 0.8, y1, TZ0 + 1.1), seed=1)
    parts += fur_roll((TX0 + 0.8, y0 + 0.2, TZ1 - 1.1), (TX1 - 0.8, y1, TZ1 + 0.7), seed=2)
    for xa, xb in ((TX0 - 0.6, TX0 + 1.9), (TX1 - 1.9, TX1 + 0.6)):
        parts += fur_roll((xa, y0 - 0.3, TZ0 - 0.7), (xb, y1 - 0.1, TZ1 + 0.7), seed=3)
    # lumps: shorter, offset rolls breaking the pelt's line front and back
    rng0 = random.Random(11)
    for k, (xa, xb) in enumerate(((TX0 + 1.2, 6.4), (6.0, 10.0), (9.6, TX1 - 1.2))):
        dy = rng0.choice((0.2, 0.4, 0.55))
        parts += fur_roll((xa, y0 + 0.6, TZ0 - 1.0), (xb, y1 + dy, TZ0 + 0.4), r=0.45, seed=20 + k)
        parts += fur_roll((xa + 0.3, y0 + 0.7, TZ1 - 0.4), (xb - 0.3, y1 + dy * 0.7, TZ1 + 1.0), r=0.45, seed=30 + k)
    parts.append(box((TX0 + 1.9, y1 - 0.9, TZ0 + 1.1), (TX1 - 1.9, y1 - 0.8, TZ1 - 1.1), "lining",
                     skip=("north", "south", "east", "west", "down")))
    hang = fur_tufts(TX0 + 0.8, TX1 - 0.8, y0 + 0.3, TZ0 - 0.65, TZ0 + 0.2, seed=4, size=0.75, step=0.9)
    hang += fur_tufts(TX0 + 0.8, TX1 - 0.8, y0 + 0.4, TZ1 - 0.2, TZ1 + 0.65, seed=5, size=0.85, step=1.0)
    rng = random.Random(7)
    tops = fur_tufts(TX0 + 1.0, TX1 - 1.0, y1 - 0.2, TZ0 - 0.5, TZ0 + 0.7, seed=7, size=0.4, down=False, step=1.2)
    tops += fur_tufts(TX0 + 1.0, TX1 - 1.0, y1 - 0.2, TZ1 - 0.8, TZ1 + 0.5, seed=8, size=0.35, down=False, step=1.3)
    for e in hang + tops:
        lo, hi = e["from"], e["to"]
        turn(e, rng.uniform(-12, 12), "z" if rng.random() < 0.5 else "x",
             ((lo[0] + hi[0]) / 2, hi[1] if e in hang else lo[1], (lo[2] + hi[2]) / 2))
    return parts + hang + tops


def shoulder() -> list[dict]:
    """The wearer's right shoulder (+x): two steel pauldron lames stacked like roof tiles,
    each overhanging out and down from the shoulder (the lower one steeper and wider), a
    bronze rim on the lower one's foot, rivets, the fur over the top."""
    p = []
    pivot = (TX1, 12.0, 8.0)
    for i, (drop, out, tilt) in enumerate(((3.3, 3.5, -24), (1.4, 3.0, -13))):
        y1 = 12.2 - drop + 2.2
        y0 = y1 - 2.2
        lame = [box((TX1 - 0.3, y0, TZ0 - 0.55 - 0.15 * (1 - i)), (TX1 + out, y1, TZ1 + 0.55 + 0.15 * (1 - i)),
                    "steel", offset=(i * 3, 0))]
        lame.append(box((TX1 + out - 0.05, y0 + 0.2, TZ0 - 0.4), (TX1 + out + 0.2, y1 - 0.2, TZ1 + 0.4), "dark_steel"))
        if i == 0:
            lame.append(box((TX1 - 0.25, y0 - 0.05, TZ0 - 0.75), (TX1 + out + 0.25, y0 + 0.4, TZ1 + 0.75), "bronze"))
        for z in (TZ0 + 0.2, 8.0, TZ1 - 0.2):
            lame.append(box((TX1 + out + 0.15, y0 + 1.0, z - 0.2), (TX1 + out + 0.35, y0 + 1.4, z + 0.2),
                            "steel", offset=(0, 0)))
        turn(lame, tilt, "z", pivot)
        p += lame
    p += fur_roll((TX1 - 1.0, 10.9, TZ0 - 0.8), (TX1 + 1.9, 13.1, TZ1 + 0.8), r=0.5, seed=5)
    p += fur_roll((TX1 - 0.4, 11.6, TZ0 - 0.3), (TX1 + 1.2, 13.6, 8.2), r=0.4, seed=6)
    tufts = fur_tufts(TZ0 - 0.6, TZ1 + 0.6, 11.1, 0, 0.6, seed=13, size=0.7)
    for e in tufts:
        (xa, ya, za), (xb, yb, zb) = e["from"], e["to"]
        e["from"], e["to"] = [TX1 + 1.6 + za, ya, xa], [TX1 + 1.6 + zb, yb, xb]
    return p + tufts


def baldric_3d() -> list[dict]:
    """The baldric standing proud of the lamellar from the wearer's right shoulder to the
    left hip, with steel studs, and the boss riding on it over the heart."""
    z = TZ0 - 0.2
    top = (_x_front(6.5 / 4), _y(0.0) + 0.4, z)
    bot = (_x_front(27.0 / 4), _y((BELT - 1) / 4), z)
    parts = [bar(top, bot, 4.4 / 4 * (TX1 - TX0) / 8, 0.3, "leather")]
    for col, row in ((9, 9), (19, 21), (23, 26)):
        x, y = _x_front((col + 1) / 4), _y((row + 1) / 4)
        parts.append(box((x - 0.2, y - 0.2, z - 0.32), (x + 0.2, y + 0.2, z - 0.1), "steel", offset=(1, 0)))
    cx, cy = _x_front((BOSS_C[0] + 0.5) / 4), _y((BOSS_C[1] + 0.5) / 4)
    parts += boss_3d(cx, cy, z - 0.1, 1.6)
    return parts


def belt_3d() -> list[dict]:
    y1, y0 = _y(BELT / 4), _y((BELT + 4) / 4)
    parts = [box((TX0 - 0.2, y0, TZ0 - 0.2), (TX1 + 0.2, y1, TZ1 + 0.2), "strap",
                 faces={"up": "strap", "down": "lining"})]
    parts.append(box((7.0, y0 - 0.35, TZ0 - 0.45), (9.0, y1 + 0.35, TZ0 - 0.2), "steel"))
    parts.append(box((7.45, y0, TZ0 - 0.55), (8.55, y1, TZ0 - 0.4), "strap"))
    parts.append(box((7.85, y0 - 0.1, TZ0 - 0.65), (8.15, y1 + 0.1, TZ0 - 0.5), "bronze"))
    return parts


def skirt_3d() -> list[dict]:
    """The leather strips hanging below the belt, each its own flap a little proud of the
    tunic and splaying out slightly, front and back."""
    parts = []
    rng = random.Random(21)
    y1, y0 = _y((BELT + 4) / 4), TY0 - 0.4
    n = 6
    span = TX1 - TX0 + 0.4
    wv = span / n
    for side, z0, z1 in (("north", TZ0 - 0.32, TZ0 - 0.02), ("south", TZ1 + 0.02, TZ1 + 0.32)):
        for k in range(n):
            xa = TX0 - 0.2 + k * wv + 0.08
            xb = xa + wv - 0.16
            drop = rng.choice((0.0, 0.15, 0.3))
            e = box((xa, y0 + drop, z0), (xb, y1, z1), "leather", offset=(k * 2 % 10, 3))
            ang = (k - (n - 1) / 2) * 2.2
            turn(e, ang, "z", ((xa + xb) / 2, y1, (z0 + z1) / 2))
            turn(e, 5 if side == "north" else -5, "x", ((xa + xb) / 2, y1, (z0 + z1) / 2))
            parts.append(e)
            cx = (xa + xb) / 2
            zz = z0 - 0.12 if side == "north" else z1
            r = box((cx - 0.17, y0 + drop + 0.45, zz), (cx + 0.17, y0 + drop + 0.8, zz + 0.12), "bronze")
            turn(r, ang, "z", ((xa + xb) / 2, y1, (z0 + z1) / 2))
            turn(r, 5 if side == "north" else -5, "x", ((xa + xb) / 2, y1, (z0 + z1) / 2))
            parts.append(r)
    return parts


def build() -> list[dict]:
    right = shoulder()
    return (torso() + mantle() + right + mirror(right, "x", 8.0) + baldric_3d() + belt_3d() + skirt_3d())


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
