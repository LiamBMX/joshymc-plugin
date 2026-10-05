"""Shark Chestplate: the chest piece of the Shark Set, and the set's pilot module.

Worn (humanoid layer, 4x): navy-slate plating in mottled one-unit tiles, a bold white
belly V across the chest, cyan gill stripes either side of it on deep-blue flanks, white
hem teeth, chunky pauldrons with a cyan flash over a stepped white rim and gill slits,
finned forearm bracers (the upper arm and the hand stay bare, like the concept) and the
dorsal fin painted on the back as a lit sail with a pale ice leading edge.

The fin is painted flat rather than on the "wings" layer: wing panels hang behind the
back facing backwards, so a fin painted on them shows as a flat cut-out floating off the
back (a leaf, from behind), vanishes edge-on from the side, and splits into two splayed
blades whenever the wearer sneaks. The flat fin matches the concept's back view and
never breaks.

Item: the same plating sculpted in 3D: a hollow torso wearing the worn front and back
paintings, with the white V, the hem teeth and the collar standing proud, chunky
pauldrons with a cyan flash, stepped white rims, gill slits and curved ice-blue shoulder
fins, and a tall stepped dorsal fin on the back. A slow glint climbs the fins' pale
leading edges, then runs down the cyan stripes. The inventory uses the same model without
the dorsal fin (a "gui" model), framed like the concept's tile.
"""
from __future__ import annotations

import numpy as np
from PIL import Image

from art.kit import (HUMANOID, box, display, mirror, model, place, region, rgba, save, save_animation,
                     save_layer, turn)

ID = "shark_chestplate"
NAME = "Shark Chestplate"
KIND = "chestplate"
COUNTERPART = "item/netherite_chestplate"

# ======================================================================================
# SHARK SET PALETTE AND PAINTERS - copy this whole block into the other Shark pieces so
# the suit reads as one outfit. Colours were sampled from the concept sheet.
# ======================================================================================

SLATE = ["#12151c", "#1b1f28", "#252a35", "#2f3542", "#3a4150", "#474f5f", "#59616f", "#6e7787"]  # plates
BLUE = ["#1e3446", "#26455c", "#2f5770", "#42677e", "#527b92", "#6191a8", "#72a8bf", "#84c0d6"]   # flanks, stripes
ICE = ["#8fb2c3", "#a9c7d6", "#c3dae6", "#dcecf3"]                                                # fin edges, glints
WHITE = ["#6f7480", "#8e939e", "#a9adb7", "#bfc2ca", "#d2d4da", "#e6e8ec"]                          # belly V, rims, teeth

S = 4   # worn layers are painted at 4x (256x128): 4 texels per model unit
G = 2   # most shapes sit on a grid of 2-texel "design pixels" (half a unit, the concept's
        # own pixel size) and get single-texel bevels; diagonals use the texel grid


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


def _inside(X, Y, pts) -> np.ndarray:
    inside = np.zeros(X.shape, bool)
    for (x0, y0), (x1, y1) in zip(pts, pts[1:] + pts[:1]):
        if y0 == y1:
            continue
        cross = (y0 > Y) != (y1 > Y)
        inside ^= cross & (X < x0 + (x1 - x0) * (Y - y0) / (y1 - y0))
    return inside


def poly(w: int, h: int, pts, grid: int = G) -> np.ndarray:
    """Texel mask of a polygon. With grid=G the points are in design pixels and edges come
    out in 2-texel stair steps like the concept's pixels; grid=1 takes texel points and
    gives finer diagonals (stripes, fins)."""
    X, Y = np.meshgrid(np.arange(w // grid) + 0.5, np.arange(h // grid) + 0.5)
    m = _inside(X, Y, [tuple(p) for p in pts])
    return m.repeat(grid, 0).repeat(grid, 1)


def spans(w: int, h: int, rows: dict, mirror_x: bool = False, grid: int = G) -> np.ndarray:
    """Texel mask from design-pixel rows: {row: (first col, last col)} or a list of such
    pairs per row. mirror_x also fills the mirror image across the face's centre."""
    m = np.zeros((h // grid, w // grid), bool)
    gw = w // grid
    for r, runs in rows.items():
        for c0, c1 in (runs if isinstance(runs, list) else [runs]):
            m[r, c0:c1 + 1] = True
            if mirror_x:
                m[r, gw - 1 - c1:gw - c0] = True
    return m.repeat(grid, 0).repeat(grid, 1)


def edges(mask: np.ndarray):
    """(top, bottom, left, right) edge texels of a mask: texels whose neighbour on that
    side is outside it."""
    return (mask & ~shift(mask, 0, 1), mask & ~shift(mask, 0, -1),
            mask & ~shift(mask, 1, 0), mask & ~shift(mask, -1, 0))


def _hash(i, j, seed) -> np.ndarray:
    v = np.sin(i * 12.9898 + j * 78.233 + seed * 37.719) * 43758.5453
    return v - np.floor(v)


def plate(a: np.ndarray, mask=None, seed: int = 0, tones=SLATE, bias: float = 0.0, cell: int = S,
          spread: float = 1.0) -> None:
    """Armour plating: one-unit tiles (cell x cell texels) in slightly different tones,
    the mottled checker of the concept. tones is a darkest -> lightest ramp (SLATE or
    BLUE) and the tiles mostly use its indices 3-5; bias > 0 lightens, < 0 darkens;
    spread < 1 keeps the tones closer together."""
    h, w = a.shape[:2]
    jj, ii = np.meshgrid(np.arange(w) // cell, np.arange(h) // cell)
    r = 0.5 + (_hash(ii, jj, seed) - 0.5) * spread + bias
    idx = np.where(r < 0.06, 2, np.where(r < 0.36, 3, np.where(r < 0.8, 4, np.where(r < 0.97, 5, 6))))
    paint = np.stack([C(c) for c in tones])[np.clip(idx, 0, len(tones) - 1)]
    if mask is None:
        a[:] = paint
    else:
        a[mask] = paint[mask]


def shadow(a: np.ndarray, mask: np.ndarray, amount: float = 0.45, reach: int = 1) -> None:
    """Contact shadow (ambient occlusion) on the texels just below/right of a raised shape."""
    grown = mask.copy()
    for k in range(1, reach + 1):
        grown |= shift(mask, 0, k) | shift(mask, k, 0) | shift(mask, k, k)
    under = grown & ~mask & (a[..., 3] > 0)
    a[under, :3] *= (1 - amount)


def emboss(a: np.ndarray, mask: np.ndarray, lit, dark, ao: float = 0.45, fill=None) -> None:
    """Make a painted shape look raised: lit top/left rim, dark bottom/right rim and a
    contact shadow just outside its bottom/right edges. fill repaints the inside first."""
    if fill is not None:
        a[mask] = C(fill)
    if ao:
        shadow(a, mask, ao)
    top, bottom, left, right = edges(mask)
    a[top | left] = C(lit)
    a[bottom | right] = C(dark)
    a[top & right] = C(lit) * 0.5 + C(dark) * 0.5
    a[bottom & left] = C(lit) * 0.5 + C(dark) * 0.5


def engrave(a: np.ndarray, mask: np.ndarray, deep=SLATE[0], lip=SLATE[6]) -> None:
    """A recessed groove or slot: dark inside with a light lip below/right of it."""
    a[mask] = C(deep)
    below = ~mask & (shift(mask, 0, 1) | shift(mask, 1, 0)) & (a[..., 3] > 0)
    a[below] = C(lip)


def rivet(a: np.ndarray, x: int, y: int) -> None:
    """A 2x2 rivet head at texel (x, y): lit top-left, dark bottom-right, a soft shadow."""
    a[y + 2, x + 1:x + 3, :3] *= 0.7
    a[y + 1:y + 3, x + 2, :3] *= 0.7
    a[y, x], a[y, x + 1] = C(SLATE[7]), C(SLATE[6])
    a[y + 1, x], a[y + 1, x + 1] = C(SLATE[6]), C(SLATE[2])


def belly(a: np.ndarray, mask: np.ndarray, cx: float) -> None:
    """The white belly emblem inside mask: a bright keel down the centre line cx (texels),
    the lit left facet brighter than the right, light top edges, grey under-edges and a
    contact shadow around it so it stands proud of the slate."""
    h, w = a.shape[:2]
    X = np.arange(w)[None, :].repeat(h, 0) + 0.5
    Y = np.arange(h)[:, None].repeat(w, 1) + 0.5
    a[mask] = C(WHITE[4])
    a[mask & (X > cx)] = C(WHITE[3])
    low = np.where(mask.any(1))[0].max() - 6
    a[mask & (Y > low) & (X > cx)] = C(WHITE[2])
    a[mask & (np.abs(X - cx) < 1.01)] = C(WHITE[5])
    shadow(a, mask, 0.3, reach=2)
    emboss(a, mask, WHITE[5], WHITE[1], ao=0.45)


def stripe(a: np.ndarray, mask: np.ndarray, lo: int = 4, hi: int = 7, ao: float = 0.4) -> None:
    """A cyan gill stripe: deep blue at its top running to bright cyan at its bottom,
    an ice rim on the lit side and a deep-blue rim on the shadow side."""
    h, w = a.shape[:2]
    rows = np.where(mask.any(1))[0]
    if not len(rows):
        return
    y0, y1 = rows.min(), rows.max()
    t = np.clip((np.arange(h)[:, None].repeat(w, 1) - y0) / max(1, y1 - y0), 0, 1)
    a[mask] = np.stack([C(c) for c in BLUE])[np.rint(lo + t * (hi - lo)).astype(int)][mask]
    emboss(a, mask, ICE[1], BLUE[1], ao=ao)


def fin(a: np.ndarray, mask: np.ndarray, lead: np.ndarray, ao: float = 0.5) -> None:
    """A fin painted flat: pale ice leading edge (lead, a band along one side of the fin)
    fading into a blue body that darkens away from it, a dark trailing rim, and a contact
    shadow on what is around it."""
    if ao:
        shadow(a, mask, ao, reach=2)
    dist = np.full(mask.shape, 99, np.int32)
    grown = lead & mask
    dist[grown] = 0
    for k in range(1, 9):
        grown = (grown | shift(grown, 1, 0) | shift(grown, -1, 0) | shift(grown, 0, 1) | shift(grown, 0, -1)) & mask
        dist[grown & (dist == 99)] = k
    tone = np.select([dist <= 1, dist <= 3, dist <= 6], [5, 4, 3], 2)
    a[mask] = np.stack([C(c) for c in BLUE])[tone][mask]
    band = lead & mask
    top, bottom, left, right = edges(mask)
    a[band] = C(ICE[1])
    a[band & (top | left)] = C(ICE[3])
    a[(bottom | right) & ~band] = C(SLATE[1])


def blade(slabs, ox: float, oy: float, base_row=None, t=None) -> np.ndarray:
    """A stepped 3D fin's broad side, for projected UVs on its slabs (64x64, 4 texels per
    unit). slabs are (y0, y1, a0, a1) with a the horizontal coordinate (z for a dorsal fin,
    x for a fin facing the front); texel column = (a - ox) * 4, row = (oy - y) * 4. The
    stepped top/left outline is the pale ice leading edge (the left edge only above
    base_row, where the fin leaves the armour); the body runs steel blue to deep blue
    toward the trailing edge and the base. t (0..1) adds a glint running up the leading
    edge for the first 60% of the loop (for save_animation frames)."""
    a = blank(64, 64)
    m = np.zeros((64, 64), bool)
    for y0, y1, a0, a1 in slabs:
        r0, r1 = int(round((oy - y1) * 4)), int(round((oy - y0) * 4))
        c0, c1 = int(round((a0 - ox) * 4)), int(round((a1 - ox) * 4))
        m[max(0, r0):r1, max(0, c0):c1] = True
    top, bottom, left, right = edges(m)
    rows = np.arange(64)[:, None].repeat(64, 1)
    lead = top | (left & (rows < (base_row if base_row is not None else 64)))
    lead = lead | (shift(lead, 1, 0) & m) | (shift(lead, 0, 1) & m)
    dist = np.full(m.shape, 99, np.int32)
    grown = lead.copy()
    dist[grown] = 0
    for k in range(1, 12):
        grown = (grown | shift(grown, 1, 0) | shift(grown, 0, 1) | shift(grown, -1, 0) | shift(grown, 0, -1)) & m
        dist[grown & (dist == 99)] = k
    tip, low = np.where(m.any(1))[0].min(), np.where(m.any(1))[0].max()
    t1 = np.clip(dist / 10.0, 0, 1)
    t2 = np.clip((rows - tip) / max(1, low - tip), 0, 1)
    paint = np.stack([C(c) for c in BLUE])[np.clip(np.rint(5.6 - 2.2 * t1 - 1.6 * t2), 1, 6).astype(int)]
    paint[_hash(rows // 4, np.arange(64)[None, :].repeat(64, 0) // 4, 7) > 0.72, :3] *= 0.92
    a[m] = paint[m]
    a[lead] = C(ICE[1])
    a[lead & (top | left)] = C(ICE[3])
    a[(right | bottom) & ~lead] = C(SLATE[1])
    if t is not None and t < 0.6:
        pos = low - (t / 0.6) * (low - tip + 8)
        near = np.abs(rows - pos)
        glow = (lead | (shift(lead, 1, 0) & m)) & (near < 4.5)
        a[glow, :3] = a[glow, :3] * 0.25 + C(ICE[3])[:3] * 0.75
        a[lead & (near < 2.0), :3] = 255
    return a


def glint(a: np.ndarray, mask: np.ndarray, t: float, run: float = 0.5, width: float = 3.5) -> np.ndarray:
    """A copy of a with an ice-white glint sliding down the masked texels (stripes, rims):
    it crosses during the first `run` of the loop and rests after, so frames t = i / n
    saved with save_animation read as a periodic flash. Item textures only: worn layers
    cannot animate."""
    out = a.copy()
    if t >= run or not mask.any():
        return out
    h, w = mask.shape
    Y = np.arange(h)[:, None].repeat(w, 1) + 0.5 + np.arange(w)[None, :].repeat(h, 0) * 0.35
    rows = Y[mask]
    pos = rows.min() - width + (rows.max() - rows.min() + 2 * width) * t / run
    k = (np.clip(1 - np.abs(Y - pos) / width, 0, 1) * mask)[..., None] * 0.8
    out[..., :3] = out[..., :3] * (1 - k) + C(ICE[3])[:3] * k
    return out


# --------------------------------------------------------------------------------------
# Worn layer (humanoid, 256x128). Face shapes are in design pixels (2 texels, half a unit):
# body front/back 16x24, body sides and arm faces 8x24, body top 16x8, arm top 8x8.
# Only the middle 6 of the chest's 8 units show between the arms: the outer unit on each
# side shares its plane with the arm (see sync_overlap), so the key art sits in cols 3-12.
# --------------------------------------------------------------------------------------

# Belly V on the chest front, left half {row: (first col, last col)}; mirrored about x = 8.
BELLY_ROWS = {5: (3, 4), 6: (3, 4), 7: (3, 7), 8: (3, 7), 9: (4, 7), 10: (5, 7), 11: (5, 7),
              12: (6, 7), 13: (6, 7), 14: (7, 7), 15: (7, 7), 16: (7, 7)}
# Gill stripe left of the V, a blade in texels (32x48 face); mirrored for the right.
STRIPE_PTS = [(2.0, 19.5), (7.0, 20.5), (13.0, 37.6), (10.4, 40.6), (8.0, 38.0), (2.0, 24.5)]
# Deep-blue flank outside each stripe (texels).
FLANK_PTS = [(0, 17.0), (2.5, 19.5), (2.5, 24.5), (9.6, 40.5), (0, 40.5)]
# Collar plate across the upper chest; hem teeth stepping down toward the middle.
COLLAR_ROWS = {2: (1, 7), 3: (1, 7), 4: (1, 7)}
TEETH_ROWS = {20: (3, 4), 21: (3, 5), 22: (5, 6), 23: (5, 6)}
TEETH_ITEM = {20: (1, 3), 21: (1, 3), 22: (3, 5), 23: (3, 5)}      # the item has no arms in front
# Back chevron: a thick white V whose point reaches the hem, a cyan V nested inside it.
BACK_V_ROWS = {15: (1, 2), 16: (1, 3), 17: (2, 4), 18: (3, 4), 19: (3, 5), 20: (4, 6), 21: (5, 6), 22: (5, 7),
               23: (6, 7)}
BACK_CYAN_ROWS = {15: (3, 4), 16: (4, 5), 17: (5, 5), 18: (5, 6), 19: (6, 7), 20: (7, 7)}
# HEM PATTERN for the leggings to continue (the bottom rows 20-23 of the body faces):
#   front: white teeth (TEETH_ROWS, WHITE[4] with WHITE[5]/WHITE[1] bevels) stepping down
#          and in: cols 3-5 on rows 20-21, cols 5-6 on rows 22-23 (and mirrored), SLATE
#          plating between and outside them, a SLATE[1] line along the bottom;
#   sides: a white band on rows 20-21 (same bevels), SLATE plating on rows 22-23;
#   back:  the point of the white chevron (BACK_V_ROWS) sits on the hem at cols 6-9, the
#          cyan inner V (BACK_CYAN_ROWS, BLUE[4] -> BLUE[7] with an ICE[1] lit rim) closes
#          just above it; SLATE plating either side.
# Spine plate on the back that carries the fin.
KEEL_ROWS = {r: (4, 7) for r in range(2, 14)}
KEEL_ROWS.update({14: (5, 7), 15: (5, 7), 16: (6, 7), 17: (6, 7), 18: (7, 7)})
# The dorsal fin as the concept draws it on the back: a sail leaning to one side, pale
# leading edge up its long curved side, a concave trailing edge (design pixels).
FLAT_FIN = [(5.4, 13.6), (6.6, 10.4), (8.2, 6.4), (9.6, 3.0), (11.0, 0.2), (11.9, 0.4), (11.8, 3.6), (12.1, 7.4),
            (12.8, 11.2), (12.4, 14.6), (9.4, 14.8)]
FLAT_FIN_LEAD = [(5.4, 13.6), (6.6, 10.4), (8.2, 6.4), (9.6, 3.0), (11.0, 0.2), (11.9, 0.4), (10.6, 3.6),
                 (9.2, 6.8), (7.6, 10.8), (6.8, 13.9)]


def paint_body_front(seed: int = 11, teeth_rows=TEETH_ROWS, flank: bool = True) -> np.ndarray:
    w, h = 8 * S, 12 * S
    a = blank(w, h)
    plate(a, seed=seed)
    # Gorget shadow at the neckline and a lighter collar plate across the upper chest.
    a[0:4] = C(SLATE[2])
    a[0] = C(SLATE[1])
    collar = spans(w, h, COLLAR_ROWS, mirror_x=True)
    plate(a, collar, seed=seed + 1, bias=0.3, spread=0.7)
    a[edges(collar)[0]] = C(SLATE[6])
    a[spans(w, h, {5: (5, 7), 6: (5, 7)}, mirror_x=True)] = C(SLATE[3])
    # Deep-blue flanks, the gill stripes, the belly V.
    if flank:
        m = poly(w, h, FLANK_PTS, grid=1)
        plate(a, m | m[:, ::-1], seed=seed + 2, tones=BLUE, bias=-0.3, spread=0.6)
    s = poly(w, h, STRIPE_PTS, grid=1)
    stripe(a, s | s[:, ::-1])
    belly(a, spans(w, h, BELLY_ROWS, mirror_x=True), w / 2)
    # Abdomen seams and the hem teeth.
    for x in (6 * G, 10 * G - 1):
        groove = np.zeros((h, w), bool)
        groove[17 * G + 1:20 * G, x] = True
        engrave(a, groove, SLATE[1], SLATE[5])
    teeth = spans(w, h, teeth_rows, mirror_x=True)
    emboss(a, teeth, WHITE[5], WHITE[1], fill=WHITE[4])
    a[h - 1, :][~teeth[h - 1]] = C(SLATE[1])
    return a


def paint_body_back(seed: int = 23, fin_decal: bool = True) -> np.ndarray:
    w, h = 8 * S, 12 * S
    a = blank(w, h)
    plate(a, seed=seed)
    a[0:3] = C(SLATE[2])
    a[0] = C(SLATE[1])
    keel = spans(w, h, KEEL_ROWS, mirror_x=True)
    plate(a, keel, seed=seed + 1, bias=0.08, spread=0.5)
    emboss(a, keel, SLATE[5], SLATE[1], ao=0.3)
    emboss(a, spans(w, h, BACK_V_ROWS, mirror_x=True), WHITE[5], WHITE[1], fill=WHITE[4])
    stripe(a, spans(w, h, BACK_CYAN_ROWS, mirror_x=True))
    if fin_decal:
        shape = poly(w, h, FLAT_FIN)
        lead = poly(w, h, FLAT_FIN_LEAD) & shape
        shadow(a, shape, 0.55, reach=3)
        fin(a, shape, lead, ao=0)
    return a


def paint_body_side(seed: int = 31) -> np.ndarray:
    """The wearer's right side (texture left = back, right = front); the left side is its
    mirror image."""
    w, h = 4 * S, 12 * S
    a = blank(w, h)
    plate(a, seed=seed)
    a[0:3] = C(SLATE[2])
    a[0] = C(SLATE[1])
    accent = poly(w, h, [(15.5, 20.0), (16.0, 20.0), (16.0, 26.0), (6.0, 38.0), (3.5, 38.0)], grid=1)
    stripe(a, accent)
    emboss(a, spans(w, h, {20: (0, 7), 21: (0, 7)}), WHITE[5], WHITE[1], fill=WHITE[4])
    a[h - 1] = C(SLATE[1])
    return a


def paint_body_top(seed: int = 41) -> np.ndarray:
    w, h = 8 * S, 4 * S
    a = blank(w, h)
    plate(a, seed=seed, bias=0.3)
    a[:, 2 * S:6 * S] = C(SLATE[1])      # under the head
    return a


# Pauldron rim (white) rows per design column on the arm FRONT face, outermost column
# first: it steps down toward the outside of the shoulder. Then bare skin, the bracer.
RIM_FRONT = [(7, 9), (7, 9), (7, 9), (6, 8), (6, 8), (5, 7), (5, 7), (5, 7)]
RIM = {"front": RIM_FRONT, "back": RIM_FRONT[::-1], "outer": [(7, 9)] * 8, "inner": [(5, 7)] * 8}
# Cyan flash above the rim on the outside of the shoulder, per face.
FLASH = {"front": {3: (0, 1), 4: (0, 3), 5: (0, 4), 6: (0, 2)}, "back": {3: (6, 7), 4: (4, 7), 5: (3, 7), 6: (5, 7)},
         "outer": {4: (2, 7), 5: (0, 7), 6: (0, 7)}, "inner": {}}
BRACER_TOP, BRACER_BOTTOM = 13, 21      # design rows: white trim, slate cuff, dark lower edge


def paint_arm_face(kind: str, seed: int) -> np.ndarray:
    """One side of the right arm. kind: "front" (left = outside), "back" (left = inside),
    "outer" (left = back, right = front), "inner" (left = front, right = back)."""
    w, h = 4 * S, 12 * S
    a = blank(w, h)
    shoulder = np.zeros((h // G, w // G), bool)
    rim = np.zeros_like(shoulder)
    under = np.zeros_like(shoulder)
    for c, (r0, r1) in enumerate(RIM[kind]):
        shoulder[:r0, c] = True
        rim[r0:r1 + 1, c] = True
        under[r1 + 1, c] = True
    shoulder, rim, under = (m.repeat(G, 0).repeat(G, 1) for m in (shoulder, rim, under))
    # Pauldron: slate tiles with a lit top edge, cyan flash, white rim, dark under-edge.
    plate(a, shoulder | rim | under, seed=seed, bias=0.1)
    a[0] = C(SLATE[6])
    if FLASH[kind]:
        stripe(a, spans(w, h, FLASH[kind]) & shoulder, lo=5, hi=7)
    if kind == "outer":     # three gill slits on the outside of the pauldron
        for c in (2, 4, 6):
            slot = spans(w, h, {1: (c, c), 2: (c, c), 3: (c, c)})
            slot[:, c * G + 1] = False
            engrave(a, slot, SLATE[0], BLUE[5])
    emboss(a, rim, WHITE[5], WHITE[1], fill=WHITE[4], ao=0)
    a[under] = C(SLATE[1])
    # Bracer: white trim, slate cuff, dark lower edge.
    plate(a, spans(w, h, {r: (0, 7) for r in range(BRACER_TOP, BRACER_BOTTOM + 1)}), seed=seed + 5)
    emboss(a, spans(w, h, {BRACER_TOP: (0, 7)}), WHITE[5], WHITE[1], fill=WHITE[4])
    a[(BRACER_BOTTOM + 1) * G - 1, :] = C(SLATE[1])
    if kind in ("front", "back"):
        for x in ((3, 9) if kind == "front" else (5, 11)):
            rivet(a, x, (BRACER_TOP + 2) * G)
    if kind == "outer":     # the forearm fin, leaning back
        shape = poly(w, h, [(0.8, 14.4), (2.2, 14.4), (6.8, 20.4), (0.8, 20.4)])
        lead = poly(w, h, [(0.8, 14.4), (2.2, 14.4), (6.8, 20.4), (5.2, 20.4)])
        fin(a, shape, lead & shape, ao=0.4)
    elif kind == "front":   # the fin's leading edge peeks round the outer corner
        a[(BRACER_TOP + 3) * G:(BRACER_BOTTOM - 1) * G, 0] = C(ICE[2])
    # The inner third of the front and back faces overlaps the chest (see sync_overlap): a
    # dark seam at the inner edge keeps the white rims from running into the belly V.
    seam = {"front": slice(w - 2, w), "back": slice(0, 2)}.get(kind)
    if seam is not None:
        solid = a[:, seam, 3] > 0
        a[:, seam][solid] = C(SLATE[1])
    return a


def paint_arm_top(seed: int = 61) -> np.ndarray:
    """Top of the pauldron (top = back, bottom = front, left = outside, right = inside)."""
    w = h = 4 * S
    a = blank(w, h)
    plate(a, seed=seed, bias=0.35, spread=0.7)
    top, bottom, left, right = edges(np.ones((h, w), bool))
    a[top | left] = C(SLATE[7])
    a[bottom | right] = C(SLATE[3])
    fin(a, spans(w, h, {r: (3, 4) for r in range(1, 7)}), spans(w, h, {r: (3, 3) for r in range(1, 7)}), ao=0.4)
    return a


def sync_overlap(body: np.ndarray, arm: np.ndarray, back: bool) -> None:
    """The inflated arm and body cubes share 2 units of the front and back planes (vanilla
    armour does too), which z-fights while the arms hang still. Copying the arm's texels
    into the body's overlapping strips makes both agree, so the fight is invisible. Both
    arms show the same arm columns there (the left arm's texture is mirrored with its cube)."""
    for c in range(32):
        d = (c + 0.5) * 10 / 32                  # units in from the body's edge
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
    bottom[:] = C(SLATE[2])
    put("body_bottom", bottom)
    put("arm_front", arm_front)
    put("arm_back", arm_back)
    put("arm_outer", paint_arm_face("outer", 53))
    put("arm_inner", paint_arm_face("inner", 54))
    put("arm_top", paint_arm_top())
    return to_image(out)


# --------------------------------------------------------------------------------------
# Item textures. The torso wears the worn front/back/side paintings (projected onto its
# faces, so icon and armour are one design); small parts use 64x64 material swatches.
# --------------------------------------------------------------------------------------

# Torso box in model units (front = north / -z like the set's other chests, wearer's
# right = +x). The 8x12 paintings stretch over it.
TX0, TX1, TY0, TY1, TZ0, TZ1 = 3.5, 12.5, 0.5, 13.0, 5.6, 10.4
V_TOP = 1.5                   # paintings start this far down (units) on the item
WALL = 0.7
# Dorsal fin profile in (z, y): leading edge from where it leaves the back to the tip,
# trailing edge from the tip down to the back. It stands on the spine plate.
FIN_LEAD = [(TZ1 - 0.4, 12.4), (11.0, 13.3), (12.0, 14.3), (13.2, 15.2), (14.6, 15.9), (15.6, 16.2)]
FIN_TRAIL = [(15.6, 16.2), (15.3, 15.1), (14.6, 13.6), (13.7, 11.5), (12.8, 9.0), (11.8, 6.8), (TZ1 - 0.4, 5.0)]
FIN_BASE = (5.0, 12.4)        # y range where the fin sits on the back
FIN_X = (7.2, 8.8)            # its thickness
FIN_O = (TZ1 - 0.5, 16.8)     # projection origin of its side texture (z, y)
# Shoulder fin on the right pauldron, built upright as slabs (y0, y1, x inner, x outer)
# with its pale leading edge on the inner side, then leaned out by SFIN_LEAN degrees
# about its root.
SHOULDER_FIN = [(13.2, 14.4, 12.6, 15.0), (14.4, 15.4, 13.1, 15.0), (15.4, 16.3, 13.6, 15.0), (16.3, 17.0, 14.1, 15.0),
                (17.0, 17.5, 14.5, 15.0)]
SFIN_O = (12.0, 18.0)
SFIN_LEAN, SFIN_ROOT = -16.0, (14.6, 13.4, 8.0)


def material(name: str, rows: list, seed: int = 0, tones=None) -> None:
    """A 64x64 swatch (4 texels per unit with uv "true"): rows lists the colours of its
    first texel rows (the lit top edge of every face), the rest is the last colour, or
    plate tiles when tones is given."""
    a = blank(64, 64)
    if tones is not None:
        plate(a, seed=seed, tones=tones)
    else:
        a[:] = C(rows[-1])
    for i, c in enumerate(rows[:-1] if tones is None else rows):
        a[i] = C(c)
    save(to_image(a), name)


def _interp(pts, y):
    """z on a profile polyline at height y."""
    pts = sorted(pts, key=lambda q: q[1])
    if y <= pts[0][1]:
        return pts[0][0]
    for (za, ya), (zb, yb) in zip(pts, pts[1:]):
        if ya <= y <= yb:
            return za + (zb - za) * (y - ya) / max(1e-6, yb - ya)
    return pts[-1][0]


def fin_slices():
    """The stepped dorsal fin: (y0, y1, z_front, z_back) for each one-unit slice."""
    out = []
    y, top = FIN_BASE[0], FIN_LEAD[-1][1]
    while y < top - 0.05:
        y1 = min(top, y + 1.0)
        ym = (y + y1) / 2
        zf = TZ1 - 0.4 if ym < FIN_BASE[1] else _interp(FIN_LEAD, ym)
        out.append((round(y, 3), round(y1, 3), round(zf, 3), round(_interp(FIN_TRAIL, ym), 3)))
        y = y1
    return out


def item_textures() -> None:
    torso = blank(64, 64)
    torso[0:48, 0:32] = paint_body_front(teeth_rows=TEETH_ITEM, flank=False)
    torso[0:48, 32:64] = paint_body_back(fin_decal=False)
    rim = blank(32, 16)                       # neck-rim plate for top faces (v 12..16)
    plate(rim, seed=71, bias=0.3, spread=0.6)
    rim[0] = C(SLATE[7])
    torso[48:64, 0:32] = rim
    # A glint runs down the chest stripes and the cyan V on the back (frames shared with
    # the fins: 16 x 3 ticks; the fins flash first, then the stripes).
    lit = np.zeros((64, 64), bool)
    stripes = poly(32, 48, STRIPE_PTS, grid=1)
    lit[0:48, 0:32] = stripes | stripes[:, ::-1]
    lit[0:48, 32:64] = spans(32, 48, BACK_CYAN_ROWS, mirror_x=True)
    save_animation([to_image(glint(torso, lit, (i / 16 - 0.3) % 1)) for i in range(16)], "torso", frametime=3)
    sides = blank(64, 64)
    side = paint_body_side()
    sides[0:48, 0:16] = side
    sides[0:48, 16:32] = side[:, ::-1]
    save(to_image(sides), "torso_side")
    material("slate", [SLATE[6], SLATE[5]], seed=81, tones=SLATE)
    material("white", [WHITE[5], WHITE[4], WHITE[4], WHITE[3]])
    material("cyan", [ICE[1], BLUE[7], BLUE[6], BLUE[5]])
    material("ice", [ICE[3], ICE[2], ICE[1]])
    material("dark", [SLATE[2], SLATE[1]])
    material("deep", [BLUE[3], BLUE[2], BLUE[1]])
    base_row = int(round((FIN_O[1] - FIN_BASE[1]) * 4))
    save_animation([to_image(blade(fin_slices(), FIN_O[0], FIN_O[1], base_row, t=i / 16)) for i in range(16)],
                   "fin", frametime=3)
    save_animation([to_image(blade(SHOULDER_FIN, SFIN_O[0], SFIN_O[1], t=(i / 16 + 0.5) % 1)) for i in range(16)],
                   "sfin", frametime=3)


# --------------------------------------------------------------------------------------
# Item model
# --------------------------------------------------------------------------------------

def _u_front(x):
    return (TX1 - x) * 8 / (TX1 - TX0)


def _u_back(x):
    return 8 + (x - TX0) * 8 / (TX1 - TX0)


def _v(y):
    return V_TOP + (TY1 - y) * (12 - V_TOP) / (TY1 - TY0)


def _y(v):
    """Inverse of _v: the model height showing painting row v (units)."""
    return TY1 - (v - V_TOP) * (TY1 - TY0) / (12 - V_TOP)


def torso_faces(frm, to, inner=()):
    """Faces of a torso wall: north/south/east/west show the projected front/back/side
    paintings, up the rim plate, the sides listed in inner the dark inside."""
    (xa, ya, za), (xb, yb, zb) = frm, to
    d = TZ1 - TZ0
    f = {"north": ("torso", [_u_front(xb), _v(yb), _u_front(xa), _v(ya)]),
         "south": ("torso", [_u_back(xa), _v(yb), _u_back(xb), _v(ya)]),
         "east": ("torso_side", [(TZ1 - zb) * 4 / d, _v(yb), (TZ1 - za) * 4 / d, _v(ya)]),
         "west": ("torso_side", [4 + (za - TZ0) * 4 / d, _v(yb), 4 + (zb - TZ0) * 4 / d, _v(ya)]),
         "up": ("torso", [(xa - TX0) * 8 / (TX1 - TX0), 12 + (za - TZ0) * 4 / d, (xb - TX0) * 8 / (TX1 - TX0),
                          12 + (zb - TZ0) * 4 / d]),
         "down": ("dark", [0, 0, xb - xa, zb - za])}
    for side in inner:
        f[side] = ("dark", [0, 0, 1, 1])
    return f


def raised(frm, to, side_tex: str, back=False):
    """A detail standing proud of the torso front (or back): its outward face shows the
    projected painting, the edges a material swatch."""
    (xa, ya, za), (xb, yb, zb) = frm, to
    faces = {s: side_tex for s in ("north", "south", "east", "west", "up", "down")}
    if back:
        faces["south"] = ("torso", [_u_back(xa), _v(yb), _u_back(xb), _v(ya)])
    else:
        faces["north"] = ("torso", [_u_front(xb), _v(yb), _u_front(xa), _v(ya)])
    return box(frm, to, side_tex, faces=faces, skip=("north",) if back else ("south",))


def design_box(rows_runs, depth, side_tex, back=False):
    """Boxes for a mirrored design-pixel shape {row: (c0, c1)} on the torso front (or
    back), standing `depth` proud; rows with the same run merge into one box."""
    parts, groups = [], []
    for r, run in sorted(rows_runs.items()):
        if groups and groups[-1][1] == r - 1 and groups[-1][2] == run:
            groups[-1][1] = r
        else:
            groups.append([r, r, run])
    cw = (TX1 - TX0) / 16
    for r0, r1, (c0, c1) in groups:
        runs = [(c0, 15 - c0)] if c1 >= 7 else [(c0, c1), (15 - c1, 15 - c0)]
        ya, yb = _y((r1 + 1) * 0.5), min(TY1, _y(r0 * 0.5))
        if ya >= TY1 - 0.05:
            continue
        for a0, a1 in runs:
            if back:
                parts.append(raised((TX0 + a0 * cw, ya, TZ1), (TX0 + (a1 + 1) * cw, yb, TZ1 + depth), side_tex,
                                    back=True))
            else:
                parts.append(raised((TX1 - (a1 + 1) * cw, ya, TZ0 - depth), (TX1 - a0 * cw, yb, TZ0), side_tex))
    return parts


def torso():
    t = WALL
    walls = [((TX0, TY0, TZ0), (TX1, TY1, TZ0 + t), ("south",), ()),
             ((TX0, TY0, TZ1 - t), (TX1, TY1, TZ1), ("north",), ()),
             ((TX1 - t, TY0, TZ0 + t), (TX1, TY1, TZ1 - t), ("west",), ("north", "south")),
             ((TX0, TY0, TZ0 + t), (TX0 + t, TY1, TZ1 - t), ("east",), ("north", "south"))]
    parts = [box(a, b, "torso", faces=torso_faces(a, b, inner), skip=skip) for a, b, inner, skip in walls]
    # Shoulder yokes over the top, leaving a neck opening, and its dark floor.
    for xa, xb in ((TX0 + t, TX0 + 2.4), (TX1 - 2.4, TX1 - t)):
        a, b = (xa, TY1 - 0.6, TZ0 + t), (xb, TY1, TZ1 - t)
        parts.append(box(a, b, "torso", faces=torso_faces(a, b, ("east", "west")), skip=("north", "south")))
    parts.append(box((TX0 + t, TY0, TZ0 + t), (TX1 - t, 10.6, TZ1 - t), "dark",
                     skip=("north", "south", "east", "west", "down")))
    # Raised front: collar plate, belly V, hem teeth. Raised back: spine plate, white V,
    # cyan inner V. The stripes stay painted.
    parts += design_box(COLLAR_ROWS, 0.25, "slate")
    parts += design_box(BELLY_ROWS, 0.55, "white")
    parts += design_box(TEETH_ITEM, 0.5, "white")
    parts += design_box(KEEL_ROWS, 0.3, "slate", back=True)
    parts += design_box(BACK_V_ROWS, 0.45, "white", back=True)
    parts += design_box(BACK_CYAN_ROWS, 0.3, "cyan", back=True)
    return parts


def dorsal_fin():
    """One-unit slabs on the spine: projected fin texture on the broad sides, ice on the
    stepped leading edge, deep blue on the trailing edge."""
    parts = []
    x0, x1 = FIN_X
    ox, oy = FIN_O
    for y0, y1, zf, zb in fin_slices():
        uf, ub, vt, vb = zf - ox, zb - ox, oy - y1, oy - y0
        faces = {"east": ("fin", [ub, vt, uf, vb]), "west": ("fin", [uf, vt, ub, vb]),
                 "up": ("ice", [0, 0, x1 - x0, zb - zf]), "north": ("ice", [0, 1, x1 - x0, 1 + y1 - y0]),
                 "south": "deep", "down": "dark"}
        parts.append(box((x0, y0, zf), (x1, y1, zb), "fin", faces=faces))
    return parts


def pauldron():
    """The wearer's right pauldron (+x); the left one is its mirror image."""
    p = [box((12.0, 8.6, 4.9), (16.0, 13.6, 11.1), "slate")]
    # Cyan flash over the rim on the front, the outside and the back.
    p.append(box((13.6, 8.6, 4.65), (16.25, 10.2, 4.9), "cyan"))
    p.append(box((16.0, 8.6, 4.9), (16.25, 10.2, 9.6), "cyan"))
    p.append(box((13.6, 8.6, 11.1), (16.25, 10.2, 11.35), "cyan"))
    # Stepped white rim: higher toward the neck, lower over the arm.
    p.append(box((11.8, 7.9, 4.6), (13.9, 9.1, 11.4), "white", faces={"down": "dark"}))
    p.append(box((13.7, 7.1, 4.5), (16.45, 8.7, 11.5), "white", faces={"down": "dark"}))
    # Gill slits on the outside: dark slots with ice lips.
    for z in (6.0, 7.4, 8.8):
        p.append(box((16.0, 10.6, z), (16.12, 12.8, z + 0.5), "dark"))
        p.append(box((16.0, 10.6, z + 0.5), (16.08, 12.8, z + 0.7), "ice"))
    # Shoulder fin rising from the cap near the neck, leaning out, pale leading edge.
    ox, oy = SFIN_O
    blade_ = []
    for y0, y1, xa, xb in SHOULDER_FIN:
        ua, ub, vt, vb = xa - ox, xb - ox, oy - y1, oy - y0
        faces = {"north": ("sfin", [ub, vt, ua, vb]), "south": ("sfin", [ua, vt, ub, vb]),
                 "up": "ice", "west": "ice", "east": "cyan", "down": "dark"}
        blade_.append(box((xa, y0, 7.4), (xb, y1, 8.6), "sfin", faces=faces))
    return p + turn(blade_, SFIN_LEAN, "z", SFIN_ROOT)


def build(fin_: bool = True) -> list[dict]:
    right = pauldron()
    return torso() + right + mirror(right, "x", 8.0) + (dorsal_fin() if fin_ else [])


def textures() -> None:
    save_layer(paint_humanoid(), "humanoid")
    item_textures()


def models() -> dict:
    parts = build()
    d = display(KIND, parts, gui_rotation=(15, 158, 0), gui_span=15.5)
    # The front (north) faces out in hand and toward the camera, like the set's other chests.
    centre = (8.0, 12.0, 8.0)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, centre, "fist", 0.42)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.4, 0, -1)}, centre, (0.52, -0.38, -0.95), 0.5,
                                       pose=None)
    icon = build(fin_=False)
    return {"main": model(parts, d), "gui": model(icon, dict(d, gui=display(KIND, icon, gui_rotation=(15, 158, 0),
                                                                         gui_span=15.5)["gui"]))}
