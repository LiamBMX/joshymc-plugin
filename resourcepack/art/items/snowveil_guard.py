"""Snowveil Guard: the chest piece of the Snowveil set (December "Winter's Solstice" crate),
and the set's pilot module: the Hood, the Leggings and the Skates import its palette and
painters from here, so the four pieces share one icy palette, one frost trim and one
emblem.

Set language:
  * deep navy quilted cloth (diamond quilting with pale-blue thread);
  * thick white snow-fur trim with a scalloped edge and a few ice glints;
  * pale-blue piping marking the layered panels;
  * frosted-silver fittings;
  * the Snowveil sigil: a frosted-silver diamond plate holding a bright ice snowflake;
  * frost crystals creeping up out of the fur hems.

Worn (humanoid layer, 4x): a fur mantle round the neck whose lower edge dips into a V
toward the sigil on the chest, a layered front panel framed by piping, a closure with
silver clasps under the sigil, frost creeping up into a fur hem. Shoulders: fur over a
layered plate with a silver rim and a small sigil, quilted sleeves, fur cuffs. Back: the
mantle, an embroidered snowflake and the hem.

Item: the same paintings on a hollow torso, with the fur mantle, the shoulder fur over
layered plates, the clasps and the sigil built in 3D; the sigil's ice crystal glows and
a glint sweeps over it while frost glints twinkle in the fur.
"""
from __future__ import annotations

import random

import numpy as np
from PIL import Image

from art.kit import (HUMANOID, box, display, mirror, model, place, region, rgba, save, save_animation,
                     save_layer, turn)

ID = "snowveil_guard"
NAME = "Snowveil Guard"
KIND = "chestplate"
COUNTERPART = "item/netherite_chestplate"

# ======================================================================================
# SNOWVEIL SET PALETTE AND PAINTERS (imported by the other Snowveil pieces)
# The navy, snow and silver ramps are the Ice Skates' own (their sole, leather and blade),
# so the boots already belong; ice is the December crate's glow blue.
# ======================================================================================

NAVY = ["#0b1130", "#121a42", "#1a2452", "#212c5f", "#2a376e", "#33437e", "#3d5090", "#4a5fa3"]
SNOW = ["#4a5890", "#6a7fb2", "#8ea3cc", "#aebfdd", "#c4d3ea", "#d9e4f3", "#edf3fa", "#ffffff"]
ICE = ["#1d5f9a", "#2a85c2", "#3fb0e2", "#62d0f2", "#9be6fa", "#d4f7ff"]
PALE = ["#5f7fba", "#7f9fd2", "#a3c1e8", "#cbe0f6"]
FROST = ["#18203f", "#3d4a72", "#71829f", "#a9b8cf", "#d9e3ee", "#ffffff"]

S = 4   # worn layers are painted at 4x (256x128): 4 texels per model unit


def C(colour) -> np.ndarray:
    """A colour as a float RGBA array (0-255)."""
    return np.array(rgba(colour), dtype=np.float32)


def blank(w: int, h: int) -> np.ndarray:
    return np.zeros((h, w, 4), np.float32)


def to_image(a: np.ndarray) -> Image.Image:
    return Image.fromarray(np.clip(np.rint(a), 0, 255).astype(np.uint8), "RGBA")


def shift(mask: np.ndarray, dx: int, dy: int) -> np.ndarray:
    """The mask moved by (dx, dy) texels (out[y, x] = mask[y - dy, x - dx])."""
    out = np.zeros_like(mask)
    h, w = mask.shape
    out[max(0, dy):h + min(0, dy), max(0, dx):w + min(0, dx)] = \
        mask[max(0, -dy):h - max(0, dy), max(0, -dx):w - max(0, dx)]
    return out


def edges(mask: np.ndarray):
    """(top, bottom, left, right) edge texels of a mask."""
    return (mask & ~shift(mask, 0, 1), mask & ~shift(mask, 0, -1),
            mask & ~shift(mask, 1, 0), mask & ~shift(mask, -1, 0))


def _hash(i, j, seed) -> np.ndarray:
    v = np.sin(i * 12.9898 + j * 78.233 + seed * 37.719) * 43758.5453
    return v - np.floor(v)


def grid(h: int, w: int):
    """(X, Y) texel index arrays of an h x w face."""
    return np.meshgrid(np.arange(w), np.arange(h))


def pick(ramp, idx) -> np.ndarray:
    """Colours of a ramp at an index array (clamped)."""
    return np.stack([C(c) for c in ramp])[np.clip(idx, 0, len(ramp) - 1)]


def shadow(a: np.ndarray, mask: np.ndarray, amount: float = 0.45, reach: int = 1, down_only=False) -> None:
    """Contact shadow on the texels just below (and right of) a raised shape."""
    grown = mask.copy()
    for k in range(1, reach + 1):
        grown |= shift(mask, 0, k)
        if not down_only:
            grown |= shift(mask, k, 0) | shift(mask, k, k)
    under = grown & ~mask & (a[..., 3] > 0)
    a[under, :3] *= (1 - amount)


def emboss(a: np.ndarray, mask: np.ndarray, lit, dark, ao: float = 0.4, fill=None) -> None:
    """A raised shape: lit top/left rim, dark bottom/right rim, contact shadow outside."""
    if fill is not None:
        a[mask] = C(fill)
    if ao:
        shadow(a, mask, ao)
    top, bottom, left, right = edges(mask)
    a[top | left] = C(lit)
    a[bottom | right] = C(dark)


def quilt(a: np.ndarray, mask=None, seed: int = 0, bias=0.0, phase=(0, 0), period: int = 8) -> None:
    """Navy quilted cloth: diamonds `period` texels across, each puffed (lit toward its
    upper-left seam, shaded toward its lower-right one) and sewn with dotted pale-blue
    thread. bias lightens (> 0) or darkens (< 0): a number or a per-texel array."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    X, Y = X + phase[0], Y + phase[1]
    s, t = (X + Y) % period, (X - Y) % period
    cell = _hash((X + Y) // period, (X - Y) // period, seed)
    # s grows toward the lower right (s = 1 hugs the upper-left seam), t toward the upper right
    idx = (4 + 2 * (s == 1) + 1 * (s == 2) + 1 * (t >= period - 2) - 1 * (s >= period - 2) - 1 * (s == period - 1)
           - 1 * (t <= 1))
    idx = idx + np.where(cell > 0.93, 1, 0)
    idx = np.rint(idx + np.asarray(bias) * 2).astype(int)
    paint = pick(NAVY, idx)
    seam = (s == 0) | (t == 0)
    paint[seam] = C(NAVY[1])
    paint[seam & (X % 2 == 0)] = C(NAVY[5])
    if mask is None:
        a[:] = paint
    else:
        a[mask] = paint[mask]


def scallop(w: int, base: int, seed: int, depth: int = 2, tuft: int = 4, symmetric: bool = False,
            dip=None) -> list[int]:
    """Per-column edge rows of a fur band: rounded tufts `tuft` texels wide, each 0..depth
    texels longer than base, longest in its middle. dip(c) adds a shape (a V, a curve).
    symmetric mirrors the left half so a face reads the same either side."""
    rng = random.Random(seed)
    half = (w + 1) // 2 if symmetric else w
    out = []
    c = 0
    while c < half:
        d = rng.randint(0, depth)
        for i in range(tuft):
            out.append(base + d + (1 if 0 < i < tuft - 1 else 0))
        c += tuft
    out = out[:half]
    if symmetric:
        out = out + out[:w // 2][::-1]
    if dip is not None:
        out = [r + int(round(dip(c))) for c, r in enumerate(out)]
    return out


def band_mask(h: int, w: int, top=None, bottom=None) -> np.ndarray:
    """Mask between per-column rows: top[c] .. bottom[c] inclusive (None = face edge)."""
    _, Y = grid(h, w)
    m = np.ones((h, w), bool)
    if top is not None:
        m &= Y >= np.asarray(top)[None, :]
    if bottom is not None:
        m &= Y <= np.asarray(bottom)[None, :]
    return m


def fur(a: np.ndarray, mask: np.ndarray, seed: int = 0, hang: bool = True, glints: float = 0.012,
        lift: int = 0, edge: bool = True, cell: int = 2) -> None:
    """Snow fur: cell x cell tufts of white and frosted blue, brightest away from the free
    edge, stray strands, a shaded free edge and a few ice glints. hang=True means the band
    hangs down (free edge at the bottom); False, it rises (free edge at the top).
    edge=False paints an even field with no free edge (swatches, tops)."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    r = _hash(Y // cell, X // cell + (Y // cell) % 2, seed)
    idx = np.where(r < 0.1, 4, np.where(r < 0.4, 5, np.where(r < 0.8, 6, 7))) + lift
    # distance to the free edge, in texels
    dist = np.full((h, w), 9, np.int32)
    for k in range(8, 0, -1) if edge else ():
        out = mask & ~shift(mask, 0, -k if hang else k)
        dist[out] = k - 1
    idx = idx - np.select([dist == 0, dist == 1, dist == 2], [3, 2, 1], 0)
    paint = pick(SNOW, idx)
    # strands: short streaks running toward the free edge
    strand = (_hash(X, Y // 3, seed + 5) > 0.86) & (dist > 1)
    paint[strand] = C(SNOW[4])
    sparkle = _hash(X, Y, seed + 9) < glints
    paint[sparkle & (dist > 0)] = C(ICE[4])
    a[mask] = paint[mask]
    if hang:
        shadow(a, mask, 0.45, reach=2, down_only=True)


def frost_creep(a: np.ndarray, base: list, seed: int, lo: int = 1, hi: int = 4, spikes: float = 0.18) -> None:
    """Frost growing out of a fur hem into the cloth: per column, a ragged crust rising
    lo..hi texels above row base[c] (exclusive), pale blue and white, with taller
    single-texel crystal ferns here and there."""
    rng = random.Random(seed)
    h, w = a.shape[:2]
    height = rng.randint(lo, hi)
    for x in range(w):
        height = max(lo, min(hi, height + rng.choice((-1, 0, 0, 1))))
        b = base[x]
        for k in range(1, height + 1):
            y = b - k
            if 0 <= y < h and a[y, x, 3] > 0:
                a[y, x] = C(PALE[2] if k < height else PALE[3])
                if k == 1:
                    a[y, x] = C(SNOW[5])
        if rng.random() < spikes:
            for k in range(height + 1, height + 4):
                y = b - k
                if 0 <= y < h and a[y, x, 3] > 0:
                    a[y, x] = C(PALE[1] if k < height + 3 else ICE[4])


def piping(a: np.ndarray, mask: np.ndarray, ao: float = 0.35) -> None:
    """Pale-blue piping cord: lit top/left, deeper blue under, a soft shadow."""
    emboss(a, mask, PALE[3], PALE[0], ao=ao, fill=PALE[2])


def sigil(a: np.ndarray, cx: int, cy: int, r: int, field=None) -> np.ndarray:
    """The Snowveil sigil centred on texel (cx, cy): a frosted-silver diamond plate (|dx| +
    |dy| <= r) with a bevelled rim and an ice field holding a white-hot snowflake. Returns
    the plate's mask. field overrides the field colours (dark -> light)."""
    h, w = a.shape[:2]
    X, Y = grid(h, w)
    dx, dy = X - cx, Y - cy
    d = np.abs(dx) + np.abs(dy)
    plate = d <= r
    shadow(a, plate, 0.5, reach=2)
    # rim: two texels, lit toward the upper left
    rim = plate & (d >= r - 1)
    lit = (dx + dy) < 0
    a[rim & lit] = C(FROST[4])
    a[rim & ~lit] = C(FROST[2])
    a[plate & (d == r) & lit] = C(FROST[3])
    a[plate & (d == r) & ~lit] = C(FROST[1])
    a[rim & (dx == 0) & (dy < 0)] = C(FROST[5])
    # ice field: brighter toward the centre
    inner = plate & (d <= r - 2)
    ramp = field or [ICE[0], ICE[1], ICE[2]]
    k = np.clip((d / max(1, r - 2)) * len(ramp), 0, len(ramp) - 1).astype(int)
    a[inner] = pick(ramp[::-1], k)[inner]
    a[inner & (d == r - 2) & ~lit] = C(NAVY[2])
    # the snowflake
    arm = max(1, r - 3)
    for sx, sy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        for i in range(1, arm + 1):
            a[cy + sy * i, cx + sx * i] = C(ICE[5] if i < arm else ICE[4])
        if arm >= 3:   # branch ticks
            m = arm - 1
            for side in (-1, 1):
                px, py = cx + sx * m + sy * side, cy + sy * m + sx * side
                a[py, px] = C(ICE[4])
    for sx, sy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
        for i in range(1, max(1, (arm * 2) // 3) + 1):
            a[cy + sy * i, cx + sx * i] = C(ICE[4] if i > 1 else ICE[5])
    a[cy, cx] = C("#ffffff")
    return plate


def clasp(a: np.ndarray, x0: int, y: int, w: int = 6) -> None:
    """A frosted-silver toggle clasp, w texels wide and 2 tall, at (x0, y)."""
    m = np.zeros(a.shape[:2], bool)
    m[y:y + 2, x0:x0 + w] = True
    shadow(a, m, 0.5, reach=1)
    a[y, x0:x0 + w] = C(FROST[4])
    a[y + 1, x0:x0 + w] = C(FROST[2])
    a[y, x0] = C(FROST[5])
    a[y, x0 + w - 1] = C(FROST[3])


def embroidered_flake(a: np.ndarray, cx: int, cy: int, r: int, scale: int = 1) -> None:
    """A large snowflake stitched in pale-blue thread (no plate). scale 2 doubles every
    stitch, for textures with twice the texel density of the worn layers."""
    def put(x, y, c):
        a[y * scale - (scale - 1) * cy:y * scale - (scale - 1) * cy + scale,
          x * scale - (scale - 1) * cx:x * scale - (scale - 1) * cx + scale] = C(c)

    for sx, sy in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, 1), (1, -1), (-1, -1)):
        n = r if sx == 0 or sy == 0 else (r * 2) // 3
        for i in range(1, n + 1):
            put(cx + sx * i, cy + sy * i, PALE[2] if i % 3 else PALE[3])
        m = (n * 2) // 3
        for side in (-1, 1):
            if sx == 0 or sy == 0:
                for j in (1, 2):
                    put(cx + sx * (m + j) + sy * side * j, cy + sy * (m + j) + sx * side * j, PALE[1])
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            put(cx + dx, cy + dy, PALE[2])
    put(cx, cy, PALE[3])


# ======================================================================================
# Worn layer (humanoid, 256x128). Body faces 32x48 texels, body sides and arm faces
# 16x48, body top 32x16, arm top 16x16. Only the middle 6 of the chest's 8 units show
# between the arms: the outer unit each side shares its plane with the arm (sync_overlap).
# ======================================================================================

MANTLE = 9                     # fur mantle's base lower edge (texel row)
SIGIL_C, SIGIL_R = (16, 22), 8  # the chest sigil (texel centre, radius)
HEM = 42                       # the fur hem's base top edge
PANEL_COLS = (9, 22)           # piping framing the front panel


def _v_dip(c: float) -> float:
    """The mantle's V: 3 texels lower at the centre of a 32-texel face."""
    return 3.0 * max(0.0, 1 - abs(c - 15.5) / 12.0)


def mantle_rows(w: int, seed: int, v: bool = False) -> list[int]:
    return scallop(w, MANTLE, seed, depth=1, symmetric=w == 32, dip=_v_dip if v else None)


def hem_rows(w: int, seed: int) -> list[int]:
    return [2 * HEM - r for r in scallop(w, HEM, seed, depth=1, symmetric=w == 32)]


def cloth(w: int, h: int, seed: int, side_dark: bool = True) -> np.ndarray:
    """Quilted navy with soft form shading toward the face's sides and bottom."""
    a = blank(w, h)
    X, Y = grid(h, w)
    bias = -0.35 * (np.abs(X + 0.5 - w / 2) / (w / 2)) ** 2 if side_dark else 0.0
    quilt(a, seed=seed, bias=bias - 0.2 * (Y / h))
    return a


def fur_top(a: np.ndarray, rows: list[int], seed: int) -> None:
    h, w = a.shape[:2]
    fur(a, band_mask(h, w, bottom=rows), seed=seed)


def fur_hem(a: np.ndarray, rows: list[int], seed: int, creep: bool = True) -> None:
    h, w = a.shape[:2]
    if creep:
        frost_creep(a, rows, seed + 3)
    fur(a, band_mask(h, w, top=rows), seed=seed, hang=False, lift=-1)
    a[h - 1, :, :3] *= 0.7


def paint_body_front(seed: int = 11) -> np.ndarray:
    w, h = 8 * S, 12 * S
    a = cloth(w, h, seed)
    rows = mantle_rows(w, seed + 1, v=True)
    # front panel framed by piping, the closure seam and its clasps under the sigil
    for c in PANEL_COLS:
        m = np.zeros((h, w), bool)
        m[MANTLE:HEM, c] = True
        piping(a, m)
    cx, cy = SIGIL_C
    a[cy + SIGIL_R:HEM, cx - 1:cx + 1] = C(NAVY[0])
    a[cy + SIGIL_R:HEM, cx - 2] = C(PALE[1])
    a[cy + SIGIL_R:HEM, cx + 1] = C(PALE[1])
    for y in (33, 38):
        clasp(a, cx - 3, y)
    sigil(a, cx, cy, SIGIL_R)
    fur_hem(a, hem_rows(w, seed + 2), seed + 4)
    fur_top(a, rows, seed + 5)
    return a


def paint_body_back(seed: int = 23) -> np.ndarray:
    w, h = 8 * S, 12 * S
    a = cloth(w, h, seed)
    for c in PANEL_COLS:
        m = np.zeros((h, w), bool)
        m[MANTLE:HEM, c] = True
        piping(a, m)
    embroidered_flake(a, 16, 25, 9)
    fur_hem(a, hem_rows(w, seed + 2), seed + 4)
    fur_top(a, mantle_rows(w, seed + 1), seed + 5)
    return a


def paint_body_side(seed: int = 31) -> np.ndarray:
    """The wearer's right side (texture left = back, right = front)."""
    w, h = 4 * S, 12 * S
    a = cloth(w, h, seed, side_dark=False)
    fur_hem(a, hem_rows(w, seed + 2), seed + 4)
    fur_top(a, mantle_rows(w, seed + 1), seed + 5)
    return a


def paint_body_top(seed: int = 41) -> np.ndarray:
    w, h = 8 * S, 4 * S
    a = blank(w, h)
    fur(a, np.ones((h, w), bool), seed=seed, hang=False, lift=1, edge=False)
    a[:, 2 * S:6 * S] = C(NAVY[0])      # under the head
    a[:, 2 * S, :3] = C(SNOW[2])[:3]
    return a


PLATE = (9, 17)       # the shoulder plate's rows (under the fur), silver rim on the last
CUFF = 40             # fur cuff's base top edge


def paint_arm_face(kind: str, seed: int) -> np.ndarray:
    """One side of the right arm. kind: "front" (left = outside), "back" (left = inside),
    "outer" (left = back, right = front), "inner" (left = front, right = back)."""
    w, h = 4 * S, 12 * S
    a = cloth(w, h, seed, side_dark=False)
    # layered shoulder plate: lighter quilt, piping, a silver rim
    p0, p1 = PLATE
    plate = np.zeros((h, w), bool)
    plate[p0:p1 + 1] = True
    quilt(a, plate, seed=seed + 1, bias=0.5, phase=(4, 0))
    pipe = np.zeros((h, w), bool)
    pipe[p1 - 2] = True
    piping(a, pipe, ao=0)
    a[p1 - 1] = C(FROST[4])
    a[p1] = C(FROST[2])
    a[p1 + 1:p1 + 3, :, :3] *= 0.6
    if kind == "outer":
        sigil(a, 8, p0 + 3, 3)
    # fur over the shoulder and round the wrist
    fur_top(a, scallop(w, 7, seed + 2, depth=1), seed + 3)
    fur_hem(a, [2 * CUFF - r for r in scallop(w, CUFF, seed + 4, depth=1)], seed + 5)
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


def sync_overlap(body: np.ndarray, arm: np.ndarray, back: bool) -> None:
    """The inflated arm and body cubes share 2 units of the front and back planes, which
    z-fights while the arms hang still; copying the arm's texels into the body's
    overlapping strips makes both agree (as the Shark Chestplate does)."""
    for c in range(32):
        d = (c + 0.5) * 10 / 32
        if d > 2:
            break
        k = (d + 4) * 16 / 6 if not back else (2 - d) * 16 / 6
        src = arm[:, min(15, int(k))]
        solid = src[:, 3] > 0
        for dst in (c, 31 - c):
            body[solid, dst] = src[solid]


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
    bottom[:] = C(NAVY[1])
    put("body_bottom", bottom)
    put("arm_front", arm_front)
    put("arm_back", arm_back)
    put("arm_outer", paint_arm_face("outer", 53))
    put("arm_inner", paint_arm_face("inner", 54))
    put("arm_top", paint_arm_top())
    bottom = blank(4 * S, 4 * S)
    bottom[:] = C(NAVY[1])
    put("arm_bottom", bottom)
    return to_image(out)


# ======================================================================================
# Item textures (shared with the set's 3D parts)
# ======================================================================================

def material(name: str, rows: list, seed: int = 0, base=None) -> None:
    """A 64x64 swatch (4 texels per unit with uv "true"): rows are the first texel rows
    (the lit top edge of every face); the rest is the last colour, or `base` painted by
    a function when given."""
    a = blank(64, 64)
    if base is not None:
        base(a)
    else:
        a[:] = C(rows[-1])
    for i, c in enumerate(rows[:-1] if base is None else rows):
        a[i] = C(c)
    save(to_image(a), name)


def fur_swatch(seed: int = 3) -> np.ndarray:
    a = blank(64, 64)
    fur(a, np.ones((64, 64), bool), seed=seed, hang=False, lift=1, glints=0.01, edge=False, cell=3)
    return a


def crystal_frame(t: float) -> Image.Image:
    """The sigil crystal (16 px, glowing): ice facets lit at the upper left, a white core,
    and a glint that slides down it in the first half of the loop."""
    a = blank(16, 16)
    X, Y = grid(16, 16)
    d = X + Y
    a[:] = pick([ICE[5], ICE[4], ICE[3], ICE[2]], np.clip(d // 8, 0, 3))
    a[(X < 2) | (Y < 2)] = C(ICE[5])
    a[(X > 13) | (Y > 13)] = C(ICE[2])
    if t < 0.5:
        pos = -4 + 40 * t / 0.5
        k = np.clip(1 - np.abs(d - pos) / 3.0, 0, 1)[..., None]
        a[..., :3] = a[..., :3] * (1 - k * 0.85) + 255 * k * 0.85
    return to_image(a)


def save_set_materials(skip=()) -> None:
    """The swatches the Snowveil 3D parts use (leave out the ones a piece never uses)."""
    swatches = {
        "fur": lambda: material("fur", [], base=lambda a: a.__setitem__(slice(None), fur_swatch())),
        "navy": lambda: material("navy", [NAVY[5], NAVY[4]], base=lambda a: quilt(a, seed=91)),
        "lining": lambda: material("lining", [NAVY[2], NAVY[1], NAVY[0]]),
        "silver": lambda: material("silver", [FROST[5], FROST[4], FROST[4], FROST[3]]),
        "pale": lambda: material("pale", [PALE[3], PALE[2], PALE[2], PALE[1]]),
        "ice": lambda: material("ice", [ICE[5], ICE[4], ICE[3]]),
        "crystal": lambda: save_animation([crystal_frame(i / 12) for i in range(12)], "crystal", frametime=3),
    }
    for name, paint in swatches.items():
        if name not in skip:
            paint()


# ======================================================================================
# Item model (front = north / -z, the wearer's right = +x, like the set's other chests)
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
    save_set_materials()


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
    """A scalloped row of fur tufts hanging from (or standing on) height y across x0..x1."""
    rng = random.Random(seed)
    parts = []
    x = x0
    while x < x1 - 0.2:
        w = min(step, x1 - x)
        h = size * rng.choice((0.5, 0.8, 1.0, 1.2))
        y0, y1 = (y - h, y) if down else (y, y + h)
        parts.append(box((x + 0.05, y0, z0), (x + w - 0.05, y1, z1), "fur",
                         offset=(rng.randrange(12), rng.randrange(12))))
        x += w
    return parts


def fur_roll(frm, to, r: float = 0.4, seed: int = 0) -> list[dict]:
    """A fur roll with a rounded profile: a core inset top and bottom by r, and a cap inset
    by r on the sides, so its edges read soft rather than boxy."""
    (x0, y0, z0), (x1, y1, z1) = frm, to
    o = (seed * 5 % 12, seed * 7 % 12)
    return [box((x0, y0 + r, z0), (x1, y1 - r, z1), "fur", offset=o),
            box((x0 + r, y0, z0 + r), (x1 - r, y1, z1 - r), "fur", offset=(o[1], o[0]))]


def mantle() -> list[dict]:
    """The fur mantle: a rounded fur collar round the neck whose front edge dips into a V of
    tufts toward the sigil, with tufts breaking its top."""
    parts = []
    y0, y1 = 11.5, 13.9
    parts += fur_roll((TX0 + 1.0, y0, TZ0 - 0.7), (TX1 - 1.0, y1, TZ0 + 1.1), seed=1)
    parts += fur_roll((TX0 + 1.0, y0 + 0.2, TZ1 - 1.1), (TX1 - 1.0, y1, TZ1 + 0.5), seed=2)
    for xa, xb in ((TX0 - 0.3, TX0 + 1.7), (TX1 - 1.7, TX1 + 0.3)):
        parts += fur_roll((xa, y0 - 0.2, TZ0 - 0.5), (xb, y1 - 0.3, TZ1 + 0.3), seed=3)
    # the neck opening's dark inside
    parts.append(box((TX0 + 1.7, y1 - 0.9, TZ0 + 1.1), (TX1 - 1.7, y1 - 0.8, TZ1 - 1.1), "lining",
                     skip=("north", "south", "east", "west", "down")))
    # the V of tufts on the front edge, hanging longest at the centre
    rng = random.Random(4)
    x = TX0 + 0.9
    while x < TX1 - 1.0:
        w = 0.9
        c = x + w / 2
        dip = 1.7 * max(0.0, 1 - abs(c - 8) / 3.6) + rng.choice((0.4, 0.6, 0.8))
        parts.append(box((x + 0.04, y0 - dip, TZ0 - 0.55), (x + w - 0.04, y0 + 0.4, TZ0 + 0.3), "fur",
                         offset=(rng.randrange(12), rng.randrange(12))))
        x += w
    # tufts breaking the top of the collar all the way round, each leaning a little
    rng = random.Random(7)
    tops = fur_tufts(TX0 + 1.1, TX1 - 1.1, y1 - 0.2, TZ0 - 0.5, TZ0 + 0.7, seed=7, size=0.6, down=False, step=1.1)
    tops += fur_tufts(TX0 + 1.1, TX1 - 1.1, y1 - 0.2, TZ1 - 0.8, TZ1 + 0.3, seed=8, size=0.55, down=False, step=1.2)
    for xa, xb in ((TX0 - 0.2, TX0 + 1.6), (TX1 - 1.6, TX1 + 0.2)):
        side = fur_tufts(TZ0 - 0.3, TZ1 + 0.1, y1 - 0.5, 0, xb - xa, seed=int(xa * 10), size=0.6, down=False,
                         step=1.2)
        for e in side:     # built along x, swapped onto z
            (pa, ya, za), (pb, yb, zb) = e["from"], e["to"]
            e["from"], e["to"] = [xa + za, ya, pa], [xa + zb, yb, pb]
        tops += side
    for e in tops:
        lo, hi = e["from"], e["to"]
        turn(e, rng.uniform(-14, 14), "z" if rng.random() < 0.5 else "x",
             ((lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2))
    return parts + tops


def shoulder() -> list[dict]:
    """The wearer's right shoulder (+x): a fur roll over a layered quilted plate with a
    piping edge, a silver rim and a small sigil boss on the outside."""
    p = [
        box((TX1 - 0.1, 7.9, TZ0 - 0.2), (TX1 + 2.6, 11.0, TZ1 + 0.2), "navy"),              # plate
        box((TX1 + 0.1, 7.2, TZ0 - 0.4), (TX1 + 2.85, 7.9, TZ1 + 0.4), "silver"),            # silver rim
        box((TX1 + 0.05, 7.9, TZ0 - 0.32), (TX1 + 2.75, 8.3, TZ1 + 0.32), "pale"),           # piping
    ]
    p += fur_roll((TX1 - 0.6, 10.6, TZ0 - 0.5), (TX1 + 2.9, 12.9, TZ1 + 0.5), r=0.5, seed=5)
    # tufts hang along the outer edge of the roll: built along x, then swapped onto z
    tufts = fur_tufts(TZ0 - 0.3, TZ1 + 0.3, 10.7, 0, 0.6, seed=13, size=0.7)
    for e in tufts:
        (xa, ya, za), (xb, yb, zb) = e["from"], e["to"]
        e["from"], e["to"] = [TX1 + 2.35 + za, ya, xa], [TX1 + 2.35 + zb, yb, xb]
    p += tufts
    # sigil boss on the outside of the plate
    cy, cz = 9.4, 8.0
    boss = box((TX1 + 2.6, cy - 0.95, cz - 0.95), (TX1 + 2.95, cy + 0.95, cz + 0.95), "silver")
    gem = box((TX1 + 2.95, cy - 0.5, cz - 0.5), (TX1 + 3.2, cy + 0.5, cz + 0.5), "crystal", glow=11,
              uv="full")
    p += turn([boss, gem], 45, "x", (TX1 + 2.8, cy, cz))
    return p


def front_panel() -> list[dict]:
    """The layered front panel: the painted panel between the piping cords stands a little
    proud of the coat, its edges in navy."""
    xa, xb = _x_front((PANEL_COLS[1] + 0.5) / 4), _x_front((PANEL_COLS[0] + 0.5) / 4)
    ya, yb = _y(HEM / 4), _y(12 / 4)
    faces = {"north": ("torso", [_u_front(xb), _v(yb), _u_front(xa), _v(ya)]), "east": "navy", "west": "navy",
             "up": "navy", "down": "lining"}
    return [box((xa, ya, TZ0 - 0.15), (xb, yb, TZ0), "navy", faces=faces, skip=("south",))]


def chest_sigil() -> list[dict]:
    """The sigil standing proud of the chest: a silver diamond plate with a bevelled step,
    an ice field and a glowing crystal at its heart."""
    cx, cy = 8.0, _y(SIGIL_C[1] / 4 + 0.125)
    z = TZ0 - 0.15
    parts = [
        box((cx - 1.85, cy - 1.85, z - 0.35), (cx + 1.85, cy + 1.85, z), "silver"),
        box((cx - 1.35, cy - 1.35, z - 0.55), (cx + 1.35, cy + 1.35, z - 0.35), "silver"),
        box((cx - 1.0, cy - 1.0, z - 0.6), (cx + 1.0, cy + 1.0, z - 0.55), "ice", glow=8, shade=False),
    ]
    turn(parts, 45, "z", (cx, cy, z))
    # crystal: an upright diamond shard with pointed ends, set in the centre
    shard = [box((cx - 0.45, cy - 1.3, z - 1.35), (cx + 0.45, cy + 1.3, z - 0.45), "crystal", glow=13, uv="full"),
             box((cx - 0.28, cy + 1.3, z - 1.15), (cx + 0.28, cy + 1.8, z - 0.65), "crystal", glow=13, uv="full"),
             box((cx - 0.28, cy - 1.8, z - 1.15), (cx + 0.28, cy - 1.3, z - 0.65), "crystal", glow=13, uv="full")]
    turn(shard, 45, "y", (cx, cy, z - 0.9))
    side = [box((cx + 0.6, cy - 0.35, z - 0.95), (cx + 1.5, cy + 0.35, z - 0.55), "crystal", glow=12, uv="full")]
    side += mirror(side, "x", cx)
    return parts + shard + side


def clasps() -> list[dict]:
    parts = []
    for row in (33, 38):
        y1 = _y(row / 4)
        y0 = _y((row + 2) / 4)
        parts.append(box((8 - 0.75, y0, TZ0 - 0.45), (8 + 0.75, y1, TZ0 - 0.15), "silver"))
    # the panel piping as raised cords
    for col in PANEL_COLS:
        x = _x_front((col + 0.5) / 4)
        parts.append(box((x - 0.16, _y(HEM / 4), TZ0 - 0.32), (x + 0.16, _y(11 / 4), TZ0), "pale"))
    return parts


def hem() -> list[dict]:
    """A slim fur roll round the hem, a little proud of the paintings, with low tufts
    rising into the frost."""
    y0, y1 = TY0 - 0.1, _y(HEM / 4)
    parts = fur_roll((TX0 - 0.25, y0, TZ0 - 0.25), (TX1 + 0.25, y1, TZ1 + 0.25), r=0.25, seed=6)
    parts += fur_tufts(TX0 - 0.2, TX1 + 0.2, y1, TZ0 - 0.2, TZ0 + 0.3, seed=21, size=0.45, down=False, step=1.0)
    return parts


def build() -> list[dict]:
    right = shoulder()
    return (torso() + front_panel() + mantle() + right + mirror(right, "x", 8.0) + chest_sigil() + clasps()
            + hem())


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
