"""Viking set (issue #1217): the palette, swatches and painters every Viking piece shares.
Never ships on its own (the name starts with "_"); viking_helmet, viking_chestplate,
viking_leggings and viking_boots import from here, so the four pieces share one palette,
one trim and one emblem.

Set language (a raider's kit, warm and weathered, distinct from the cold, grey Nordic set):
  * weathered, warm-grey steel with dull brass rivets;
  * thick dark-brown bear fur (never grey wolf);
  * oiled tan leather and darker cross-laced straps;
  * a blood-red wool tunic with a brass-and-red woven hem;
  * brass rims stamped with a running chevron (the set's trim, not Nordic knotwork);
  * pale, ridged horn darkening to the tip;
  * the ginger beard: deep rust to bright copper strands with gold highlights;
  * the emblem: an ember rune (a brass roundel with a glowing rune) on the brow and chest.

Painters work on float RGBA numpy arrays (see snowveil_guard's helpers, re-exported here).
"""
from __future__ import annotations

import numpy as np
from PIL import Image

from art.items.snowveil_guard import C, _hash, blank, edges, grid, pick, shadow, shift, to_image
from art.kit import save, save_animation

__all__ = ["C", "_hash", "blank", "edges", "grid", "pick", "shadow", "shift", "to_image"]

# ======================================================================================
# Palette (dark -> light). Hue-shifted: shadows lean cool/purple, lights lean warm.
# ======================================================================================

STEEL = ["#141518", "#202227", "#2e3036", "#41434a", "#585a60", "#74757a", "#9a9a9c", "#c9c6bf"]
BRASS = ["#3a2010", "#5c3816", "#82531f", "#a6702b", "#c48f3c", "#dcb15e", "#f0d595"]
HORN = ["#2e2219", "#4d3b2c", "#715a42", "#94795a", "#b59a75", "#cfb892", "#e6d6b4", "#f6eedb"]
GINGER = ["#2e0d06", "#4f1809", "#74260c", "#9a3a10", "#bd5215", "#d96c1f", "#ec8a30", "#f8b257", "#ffd890"]
BEAR = ["#140d0a", "#21160f", "#301f15", "#43301f", "#58402a", "#6e5236", "#876644", "#a17d58"]
TAN = ["#2f1a10", "#4a2b19", "#683f23", "#88562f", "#a6703d", "#c18c51", "#d9aa70"]
STRAP = ["#1d110c", "#2e1b12", "#43281a", "#5a3722", "#72482c", "#8c5c39"]
WOOL = ["#1f0809", "#33100f", "#4a1715", "#62201b", "#7b2b22", "#94372a", "#ad4834", "#c35d42"]
EMBER = ["#3c0a02", "#6e1a04", "#a83306", "#de5a0e", "#ff8a24", "#ffbb5c", "#ffe6b0"]

S = 4   # worn layers are painted at 4x (256x128): 4 texels per model unit


def rect(h: int, w: int, x0: int, y0: int, x1: int, y1: int) -> np.ndarray:
    """Inclusive rectangle mask."""
    m = np.zeros((h, w), bool)
    m[max(0, y0):y1 + 1, max(0, x0):x1 + 1] = True
    return m


def _apply(a, mask, paint):
    if mask is None:
        a[:] = paint
    else:
        a[mask] = paint[mask]


# ======================================================================================
# Painters
# ======================================================================================

def steel(a: np.ndarray, mask=None, seed: int = 0, bias: float = 0.0, ao: float = 0.45, rim: bool = True) -> None:
    """Weathered steel plate: soft hammer dents, rust-brown pitting in the low spots, a lit
    top/left rim and a dark bottom/right rim, a contact shadow outside."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    dent = (_hash(X // 3, Y // 3, seed) + _hash((X + 1) // 4, (Y + 2) // 3, seed + 1)) / 2
    idx = 4 + np.where(dent > 0.66, 1, 0) - np.where(dent < 0.3, 1, 0)
    idx = np.rint(idx + bias * 2).astype(int)
    paint = pick(STEEL, idx)
    pit = (_hash(X, Y, seed + 3) > 0.955) & (dent < 0.45)
    paint[pit] = C(TAN[2])
    scratch = (_hash(X // 5, Y, seed + 4) > 0.93) & ((X + Y) % 5 == 0)
    paint[scratch] = C(STEEL[6])
    m = np.ones((h, w), bool) if mask is None else mask
    if ao and mask is not None:
        shadow(a, m, ao, reach=1)
    _apply(a, m, paint)
    if rim:
        top, bottom, left, right = edges(m)
        a[top] = C(STEEL[7])
        a[left & ~top] = C(STEEL[6])
        a[bottom] = C(STEEL[1])
        a[right & ~bottom & ~top] = C(STEEL[2])


def rivet(a: np.ndarray, x: int, y: int, metal=None) -> None:
    """A 2x2 domed rivet head (brass by default)."""
    m = metal or BRASS
    h, w = a.shape[:2]
    for dx, dy, c in ((0, 0, m[-1]), (1, 0, m[-2]), (0, 1, m[-3]), (1, 1, m[1])):
        if 0 <= x + dx < w and 0 <= y + dy < h:
            a[y + dy, x + dx] = C(c)


def brass(a: np.ndarray, mask: np.ndarray, ao: float = 0.4) -> None:
    """A plain brass rim: lit top and left, dark bottom and right."""
    if ao:
        shadow(a, mask, ao, reach=1)
    a[mask] = C(BRASS[3])
    top, bottom, left, right = edges(mask)
    a[top | left] = C(BRASS[5])
    a[bottom | right] = C(BRASS[1])


def chevron_band(a: np.ndarray, x0: int, x1: int, y0: int, h: int = 5, phase: int = 0, ao: float = 0.4) -> None:
    """The set's trim: a brass band stamped with a running chevron (>>>), lit rim on top,
    dark rim below, the chevrons punched dark with a lit leading edge."""
    H, W = a.shape[:2]
    m = rect(H, W, x0, y0, x1, y0 + h - 1)
    brass(a, m, ao=ao)
    inner = h - 2
    if inner < 1:
        return
    mid = (inner - 1) / 2
    period = inner + 2
    for x in range(max(0, x0), min(W, x1 + 1)):
        for r in range(inner):
            y = y0 + 1 + r
            k = (x - x0 + phase - abs(r - mid)) % period
            if k < 1:
                a[y, x] = C(BRASS[0])
            elif k < 2:
                a[y, x] = C(BRASS[6] if r <= mid else BRASS[4])
    a[y0, max(0, x0):x1 + 1] = C(BRASS[5])
    a[y0 + h - 1, max(0, x0):x1 + 1] = C(BRASS[1])


def chevron_column(a: np.ndarray, x0: int, y0: int, y1: int, w: int = 5, phase: int = 0) -> None:
    """chevron_band() turned upright."""
    sub = np.ascontiguousarray(a[y0:y1 + 1, x0:x0 + w].transpose(1, 0, 2))
    chevron_band(sub, 0, sub.shape[1] - 1, 0, w, phase, ao=0)
    a[y0:y1 + 1, x0:x0 + w] = sub.transpose(1, 0, 2)


def leather(a: np.ndarray, mask: np.ndarray, seed: int = 0, pal=None, stitch: bool = True, ao: float = 0.4) -> None:
    """Oiled leather: mottled, lit top/left, dark bottom/right, a stitched line inside the
    long edges."""
    pal = pal or TAN
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    if ao:
        shadow(a, mask, ao, reach=1)
    mott = _hash(X // 2, Y // 2, seed)
    idx = 3 + np.where(mott > 0.8, 1, 0) - np.where(mott < 0.18, 1, 0)
    a[mask] = pick(pal, idx)[mask]
    top, bottom, left, right = edges(mask)
    a[top | left] = C(pal[min(len(pal) - 1, 5)])
    a[bottom | right] = C(pal[0])
    if stitch:
        inner_top = shift(top, 0, 1) & mask & ~bottom
        inner_bot = shift(bottom, 0, -1) & mask & ~top
        a[(inner_top | inner_bot) & (X % 2 == 0)] = C(HORN[5])


def wool(a: np.ndarray, mask=None, seed: int = 0, bias=0.0) -> None:
    """Blood-red wool twill: fine diagonal ribs and a few darker slubs."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    rib = ((X - Y) % 3 == 0)
    idx = 4 - 1 * rib + np.where(_hash(X // 3, Y // 2, seed) > 0.88, -1, 0)
    idx = np.rint(idx + np.asarray(bias) * 3).astype(int)
    _apply(a, mask, pick(WOOL, idx))


def woven_hem(a: np.ndarray, x0: int, x1: int, y0: int, h: int = 5) -> None:
    """The woven hem: dark strap selvedges and, between them, brass and red lozenges."""
    H, W = a.shape[:2]
    mid = y0 + (h - 1) / 2
    for y in range(y0, min(H, y0 + h)):
        for x in range(max(0, x0), min(W, x1 + 1)):
            if y in (y0, y0 + h - 1):
                c = STRAP[1] if y > y0 else STRAP[3]
            else:
                d = abs(y - mid)
                k = int((x - x0 + d) // 2) % 3
                c = (BRASS[5], WOOL[6], WOOL[2])[k]
                if k == 0 and y > mid:
                    c = BRASS[3]
            a[y, x] = C(c)


def fur(a: np.ndarray, mask: np.ndarray, seed: int = 0, hang: bool = True, lift: int = 0, edge: bool = True,
        cell: int = 2) -> None:
    """Dark bear fur: clumped brown tufts with long streaks of pale-tipped guard hair,
    brightest away from the free edge, the free edge shaded and ragged.
    hang=True: the band hangs down (free edge at the bottom); False: it rises."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    r = _hash(Y // (cell + 1), X // cell + (Y // (cell + 1)) % 2, seed)
    idx = np.where(r < 0.25, 3, np.where(r < 0.65, 4, 5)) + lift
    dist = np.full((h, w), 9, np.int32)
    for k in range(8, 0, -1) if edge else ():
        out = mask & ~shift(mask, 0, -k if hang else k)
        dist[out] = k - 1
    idx = idx - np.select([dist == 0, dist == 1, dist == 2], [3, 2, 1], 0)
    paint = pick(BEAR, idx)
    guard = (_hash(X, Y // 3, seed + 5) > 0.84) & (dist > 0)
    paint[guard] = C(BEAR[7])
    deep = (_hash(X + 7, Y // 2, seed + 6) > 0.88) & ~guard
    paint[deep] = C(BEAR[1])
    a[mask] = paint[mask]
    if hang:
        shadow(a, mask, 0.5, reach=2, down_only=True)


def hair(a: np.ndarray, mask=None, seed: int = 0, bias: float = 0.0, flow: int = 0) -> None:
    """Ginger beard hair: long locks running down the face (flow leans them: texels of
    sideways drift per 8 rows), each lock lit on its left, shadowed between locks, with
    copper-gold highlight strands and a few deep rust ones."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    # wavy locks 6 texels wide: each sways a little and is rounded across (lit ridge
    # left of centre, dark gap at its edges), breaking into a new lock now and then
    sway = np.rint(1.2 * np.sin(Y / 4.5 + (X // 6) * 1.7 + seed)).astype(int)
    xs = X + sway + (Y * flow) // 8
    lock = xs // 6
    lx = xs % 6
    seg = (Y + (_hash(lock, 0, seed) * 17).astype(int)) // 13
    across = np.array([4, 5, 6, 7, 6, 4])[lx]
    wob = _hash(lock, seg, seed)
    idx = across + np.where(wob > 0.8, 1, 0) - np.where(wob < 0.2, 1, 0)
    tip = ((Y + (_hash(lock, 0, seed) * 17).astype(int)) % 13) >= 11
    idx = idx - np.where(tip, 2, 0)
    idx = np.rint(idx + bias * 2).astype(int)
    paint = pick(GINGER, idx)
    hi = (lx == 2) & (_hash(lock, Y // 2, seed + 4) > 0.55) & ~tip
    paint[hi] = C(GINGER[7])
    spark = hi & (_hash(X, Y, seed + 6) > 0.96)
    paint[spark] = C(GINGER[8])
    gap = (lx == 0) & (_hash(lock, Y // 4, seed + 5) > 0.55)
    paint[gap] = C(GINGER[2])
    _apply(a, mask, paint)


def horn(a: np.ndarray, seed: int = 0) -> None:
    """Horn along the face's v axis (top = base, bottom = tip): ridged growth rings over
    pale ivory, darkening toward the tip, a soft lit streak down the left."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    t = Y / max(1, h - 1)
    base = 6 - np.floor(t * 4.2).astype(int)
    ring = (Y % 5 == 4)
    idx = base - ring + np.where(_hash(X // 2, Y, seed) > 0.85, -1, 0)
    idx = idx + np.where(X < w * 0.3, 1, 0) - np.where(X > w * 0.75, 1, 0)
    a[:] = pick(HORN, idx)


def ember_rune(a: np.ndarray, cx: float, cy: float, r: int, glow: bool = True) -> np.ndarray:
    """The set emblem centred on (cx, cy): a brass roundel with a bevelled rim and, cut
    into it, an ember-lit rune (a staff with two raised arms: the elder-futhark Algiz,
    "protection"). Returns its mask."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    dx, dy = X + 0.5 - cx, Y + 0.5 - cy
    d = np.sqrt(dx * dx + dy * dy)
    plate = d <= r
    shadow(a, plate, 0.55, reach=2)
    lit = (dx + dy) < 0
    a[plate] = C(BRASS[2])
    a[plate & lit & (d > r * 0.4)] = C(BRASS[3])
    a[plate & (d > r - 1.1) & lit] = C(BRASS[5])
    a[plate & (d > r - 1.1) & ~lit] = C(BRASS[1])
    inner = r - 1.6
    staff = (np.abs(dx) <= 0.6) & (np.abs(dy) <= inner)
    arm = (np.abs(np.abs(dx) - (-(dy) + 0.2) * 0.85) <= 0.65) & (dy <= 0.2) & (dy >= -inner * 0.9)
    rune = plate & (staff | arm) & (d <= inner + 0.3)
    a[rune] = C(EMBER[4] if glow else BRASS[0])
    a[rune & (dy < -inner * 0.3)] = C(EMBER[5] if glow else BRASS[0])
    a[shift(rune, 1, 1) & plate & ~rune] = C(BRASS[0])
    return plate


# ======================================================================================
# Swatches for the 3D parts (64x64 = 4 texels per unit with uv "true"; faces start at
# v 0 to catch the lit top row where a swatch has one)
# ======================================================================================

def swatch(name: str, paint) -> None:
    a = blank(64, 64)
    paint(a)
    save(to_image(a), name)


def _steel_swatch(a):
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    dent = (_hash(X // 6, Y // 5, 7) + _hash((X + 3) // 6, (Y + 2) // 5, 8)) / 2
    idx = 4 + np.where(dent > 0.68, 1, 0) - np.where(dent < 0.28, 1, 0)
    a[:] = pick(STEEL, idx)
    a[(_hash(X, Y, 9) > 0.975) & (dent < 0.5)] = C(TAN[2])
    a[(_hash(X // 7, Y, 10) > 0.95) & ((X + Y) % 7 == 0)] = C(STEEL[6])
    a[0] = C(STEEL[6])
    a[1] = C(STEEL[5])


def _brass_swatch(a):
    a[:] = C(BRASS[3])
    a[0] = C(BRASS[6])
    a[1] = C(BRASS[5])
    a[2] = C(BRASS[4])
    X, Y = grid(64, 64)
    a[(Y > 3) & ((X + Y) % 5 == 0)] = C(BRASS[2])


def _chevron_swatch(a):
    for y in range(0, 60, 5):
        chevron_band(a, 0, 63, y, 5, phase=y, ao=0)
    chevron_band(a, 0, 63, 59, 5, ao=0)


def _fur_swatch(a):
    fur(a, np.ones((64, 64), bool), seed=3, hang=False, lift=1, edge=False, cell=3)


def _leather_swatch(a):
    leather(a, np.ones(a.shape[:2], bool), seed=5, stitch=False, ao=0)
    a[0] = C(TAN[6])
    a[1] = C(TAN[5])
    a[2, ::2] = C(HORN[5])


def _strap_swatch(a):
    leather(a, np.ones(a.shape[:2], bool), seed=6, pal=STRAP, stitch=False, ao=0)
    a[0] = C(STRAP[5])


def _beard_swatch(a):
    hair(a, seed=4)


def _beard_shade_swatch(a):
    """The beard's undersides and inner faces: the same locks a step darker."""
    hair(a, seed=6, bias=-1.0)


def _horn_swatch(a):
    horn(a, seed=2)


def _hem_swatch(a):
    for y in range(0, 60, 5):
        woven_hem(a, 0, 63, y, 5)
    woven_hem(a, 0, 63, 59, 5)


def ember_frame(t: float) -> Image.Image:
    """The ember rune stone (16 px, glowing): the rune's embers breathing brighter and
    dimmer over the loop, with a spark climbing the staff."""
    import math
    a = blank(16, 16)
    X, Y = grid(16, 16)
    a[:] = C(BRASS[1])
    a[1:15, 1:15] = C(BRASS[0])
    k = 0.5 + 0.5 * math.sin(2 * math.pi * t)
    staff = (X >= 7) & (X <= 8) & (Y >= 2) & (Y <= 13)
    arms = (np.abs(np.abs(X - 7.5) - (8.5 - Y) * 0.9) <= 0.8) & (Y >= 2) & (Y <= 8)
    rune = staff | arms
    a[rune] = C(EMBER[3]) * (1 - k) + C(EMBER[5]) * k
    halo = (shift(rune, 1, 0) | shift(rune, -1, 0) | shift(rune, 0, 1) | shift(rune, 0, -1)) & ~rune
    a[halo] = C(EMBER[1]) * (1 - k * 0.5) + C(EMBER[2]) * (k * 0.5)
    sy = int(round(13 - 11 * t))
    if staff[sy, 7]:
        a[sy, 7:9] = C(EMBER[6])
    return to_image(a)


def save_set_materials(skip=()) -> None:
    """The swatches the Viking 3D parts use (leave out the ones a piece never uses)."""
    swatches = {
        "steel": lambda: swatch("steel", _steel_swatch),
        "brass": lambda: swatch("brass", _brass_swatch),
        "chevron": lambda: swatch("chevron", _chevron_swatch),
        "fur": lambda: swatch("fur", _fur_swatch),
        "leather": lambda: swatch("leather", _leather_swatch),
        "strap": lambda: swatch("strap", _strap_swatch),
        "beard": lambda: swatch("beard", _beard_swatch),
        "beard_shade": lambda: swatch("beard_shade", _beard_shade_swatch),
        "horn": lambda: swatch("horn", _horn_swatch),
        "hem": lambda: swatch("hem", _hem_swatch),
        "wool": lambda: swatch("wool", lambda a: wool(a, seed=3)),
        "lining": lambda: swatch("lining", lambda a: a.__setitem__(slice(None), C(STRAP[1]))),
        "ember": lambda: save_animation([ember_frame(i / 12) for i in range(12)], "ember", frametime=3),
    }
    for name, paint in swatches.items():
        if name not in skip:
            paint()
