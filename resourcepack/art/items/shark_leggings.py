"""Shark Leggings: the leg piece of the Shark Set, built on the Shark Chestplate (the pilot).

Worn (humanoid_leggings layer, 4x): the chestplate's navy-slate plating in mottled one-unit
tiles. Each thigh carries a bold chevron, a steel-blue band over a white band, running
round the leg as one tilted ring: highest at the back of the outer thigh, raking down the
outer thigh to the front, then stepping down toward the inside of the leg on the front and
on the back, so the two legs make a V from either side; the white stops short of the
inner edge and the blue runs on into it, like the concept. Under it a stepped shark fin
is painted on the outer side of the knee, swept back like the chestplate's forearm fins,
its pale leading edge peeking round the front corner; it ends where the boots begin, and
their own fin carries on below. With no boots on, a white trim over a slate cuff closes
the ankle. The waistband carries on the chestplate's hem: the white band of its sides
runs round the front with two teeth under it, the back keeps the point of its chevron,
and a dark seam closes it where it shares a plane with the legs.

Item: a pair of leggings in 3D wearing the same paintings, with the chevrons standing a
little proud (a box per design column, so their stepped edges catch the light), the
belt's white band and teeth and the back chevron raised, a rimmed waist opening, and a
stepped ice-blue fin rising off the outside of each shin like the concept's tile. On the
chestplate's 2.4 s loop a glint climbs the fins' leading edges, then runs down the blue
chevrons.
"""
from __future__ import annotations

import numpy as np
from PIL import Image

from art.kit import (HUMANOID, box, display, mirror, model, place, region, rgba, save, save_animation,
                     save_layer)

ID = "shark_leggings"
NAME = "Shark Leggings"
KIND = "leggings"
COUNTERPART = "item/netherite_leggings"

# ======================================================================================
# SHARK SET PALETTE AND PAINTERS - copied from art/items/shark_chestplate.py (the pilot):
# the palette, the grid and the painters this piece uses, so the suit reads as one outfit.
# Colours were sampled from the concept sheet.
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


def plate(a: np.ndarray, mask=None, seed: int = 0, tones=SLATE, bias=0.0, cell: int = S,
          spread: float = 1.0) -> None:
    """Armour plating: one-unit tiles (cell x cell texels) in slightly different tones,
    the mottled checker of the concept. tones is a darkest -> lightest ramp (SLATE or
    BLUE) and the tiles mostly use its indices 3-5; bias > 0 lightens, < 0 darkens (a
    number, or a per-texel array for a gradient); spread < 1 keeps the tones closer."""
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


# The chestplate's hem shapes (design pixels on a 16x24 body face, left half, mirrored):
BACK_V_ROWS = {15: (1, 2), 16: (1, 3), 17: (2, 4), 18: (3, 4), 19: (3, 5), 20: (4, 6), 21: (5, 6), 22: (5, 7),
               23: (6, 7)}
BACK_CYAN_ROWS = {15: (3, 4), 16: (4, 5), 17: (5, 5), 18: (5, 6), 19: (6, 7), 20: (7, 7)}

# ======================================================================================
# Worn layer (humanoid_leggings, 256x128)
# ======================================================================================
# Leg faces are 16x48 texels (8x24 design pixels). Design columns run, on the wearer's
# right leg: outer (back -> front), front (outer -> inner), inner (front -> back), back
# (inner -> outer), so neighbouring faces meet at the leg's corners; the left leg wears
# the mirror image. With the chestplate on, rows 0-2 of the legs hide under its hem, and
# the boots cover rows 15 and down, so the art sits on rows 3-15: the chevron on the
# thigh, the fin on the knee.
#
# Shared planes (vanilla leggings z-fight here; these paintings make both sides agree):
#   * the two legs overlap by 1.2 units: the inner 4 texel columns of the front (12-15)
#     and of the back (0-3) are painted one colour per row, so both legs show the same;
#   * the waistband's bottom unit shares the front/back plane with the legs' top unit:
#     both are painted the same flat dark seam there (the waistband's texel rows 44-47,
#     the legs' rows 0-3). Leaving either clear lets the skin show through mid-stride.

# The chevron, per design column in each face's order: (first row, last row) or None.
# It runs round the leg as one tilted ring of a steel-blue band over a white band:
# highest at the back of the outer thigh, stepping down a row every two columns along the
# outer thigh to the front corner, then a row a column down the front and the back toward
# the inside (the back's V sits a little higher than the front's, like the concept).
FRONT_BLUE = [(3, 7), (4, 8), (5, 9), (6, 10), (7, 11), (8, 12), (9, 15), (9, 15)]
FRONT_WHITE = [(8, 11), (9, 12), (10, 13), (11, 14), (12, 15), (13, 15), None, None]
OUTER_BLUE = [(0, 4), (0, 4), (1, 5), (1, 5), (2, 6), (2, 6), (3, 7), (3, 7)]
OUTER_WHITE = [(5, 8), (5, 8), (6, 9), (6, 9), (7, 10), (7, 10), (8, 11), (8, 11)]
BACK_BLUE = [(6, 12), (6, 12), (5, 9), (4, 8), (3, 7), (2, 6), (1, 5), (0, 4)]
BACK_WHITE = [None, None, (10, 12), (9, 12), (8, 11), (7, 10), (6, 9), (5, 8)]
INNER_BLUE = [(9, 15), (9, 15), (8, 14), (8, 14), (7, 13), (7, 13), (6, 12), (6, 12)]
CHEVRON = {"front": (FRONT_BLUE, FRONT_WHITE), "outer": (OUTER_BLUE, OUTER_WHITE),
           "back": (BACK_BLUE, BACK_WHITE), "inner": (INNER_BLUE, [None] * 8)}
# The shin fin, painted on the outer face (column 0 = back) and swept back like the
# chestplate's forearm fins and the boots' fins: tip up at the back, the stepped pale
# leading edge running down to the front corner, where it peeks round onto the front
# (FIN_PEEK); a dark trailing edge shows on the back's outer column. It sits on the knee
# and upper shin, so the boots (whose tops reach about row 15) leave all of it showing,
# their own fin carrying on below it.
FIN_OUTER = [(12, 15), (10, 15), (11, 15), (12, 15), (13, 15), (14, 15), (15, 15), (15, 15)]
FIN_PEEK = (13, 15)
# The item's legs have 3D fins; beside the root of each, the front shows a small stepped
# wedge (the concept's tile has pale blocks there): tallest at the outer corner.
FIN_FRONT = [(16, 21), (17, 21), (18, 21), (19, 21), None, None, None, None]
TRIM_ROW = 19                # white ankle trim, then the slate cuff
WAIST_TOP = 16               # the waistband is the body's lower 4 units (design rows 16-23)
SEAM = 22                    # its last two rows: the dark seam the legs' tops share
# The chestplate's hem, raised two rows to clear the seam: the white band of its sides
# carried round the front with two teeth under it, and the point of the back chevron.
FRONT_TRIM = {18: (0, 7), 19: (0, 7), 20: (5, 6), 21: (5, 6)}
SIDE_TRIM = {18: (0, 7), 19: (0, 7)}
WAIST_V = {r - 2: run for r, run in BACK_V_ROWS.items() if r - 2 >= WAIST_TOP}
WAIST_CYAN = {r - 2: run for r, run in BACK_CYAN_ROWS.items() if r - 2 >= WAIST_TOP}


def cols(w: int, h: int, runs) -> np.ndarray:
    """Texel mask from design-pixel columns: runs[c] = (first row, last row) or None."""
    m = np.zeros((h // G, w // G), bool)
    for c, run in enumerate(runs):
        if run is not None:
            m[run[0]:run[1] + 1, c] = True
    return m.repeat(G, 0).repeat(G, 1)


def chevron_band(a: np.ndarray, blue: np.ndarray, white: np.ndarray, light: np.ndarray, seed: int,
                 lift: float = 0.0) -> None:
    """The chevron: a mottled steel-blue band (lighter toward `light`, a 0..1 per-texel
    map, and by `lift`) with a cyan lower edge, embossed, and the white band under it,
    embossed."""
    plate(a, blue, seed=seed, tones=BLUE, bias=0.3 * light + 0.05 + lift, spread=0.55)
    lower = blue & shift(white, 0, -1)          # the row just above the white band
    emboss(a, blue, BLUE[5], BLUE[1], ao=0.35)
    a[lower] = C(BLUE[6])
    emboss(a, white, WHITE[5], WHITE[1], fill=WHITE[4], ao=0.45)
    _, wb, _, _ = edges(white)
    inner = white & shift(wb, 0, -1) & ~wb      # the row above its under-edge: a grey facet
    a[inner] = C(WHITE[3])


def shin_fin(a: np.ndarray, runs, corner: int, trailing: int | None = None) -> None:
    """The fin painted flat: pale stepped top edge and a pale edge down the leg's
    front-outer corner (texel column `corner`), blue body, dark rim. The design column
    `trailing` (the concave back edge) keeps a dark top."""
    h, w = a.shape[:2]
    shape = cols(w, h, runs)
    lead = shape & ~shift(shape, 0, 3)
    if trailing is not None:
        lead[:, trailing * G:(trailing + 1) * G] = False
    lead[:, corner] |= shape[:, corner]
    fin(a, shape, lead, ao=0.45)


def even_strip(a: np.ndarray, c0: int, c1: int, src: int) -> None:
    """Paint texel columns c0..c1 one colour per row (from column src): the legs' shared strip."""
    a[:, c0:c1 + 1] = a[:, src:src + 1]


def paint_leg_face(kind: str, seed: int, worn: bool = True) -> np.ndarray:
    """One face of the wearer's right leg: "outer", "front", "inner" or "back". worn=False
    is the item's version: no ankle trim or seam (the concept's tile has none), and in
    place of the painted shin fin (the item has 3D ones) a small wedge on the front."""
    w, h = 4 * S, 12 * S
    a = blank(w, h)
    plate(a, seed=seed, bias=-0.12 if kind == "inner" else 0.0)
    a[0:2] = C(SLATE[2])                         # in the waistband's shadow
    blue_runs, white_runs = CHEVRON[kind]
    blue, white = cols(w, h, blue_runs), cols(w, h, white_runs)
    # Lighter toward the outer thigh, darker toward the crotch.
    x = (np.arange(w) + 0.5) / w
    toward_outer = {"front": 1 - x, "back": x, "outer": np.full(w, 0.8), "inner": np.zeros(w)}[kind]
    light = np.broadcast_to(toward_outer[None, :], (h, w))
    chevron_band(a, blue, white, light, seed + 3, lift=0.0 if worn else 0.15)
    fin_rows = slice(FIN_PEEK[0] * G, (FIN_PEEK[1] + 1) * G)
    if kind == "outer" and worn:
        shin_fin(a, FIN_OUTER, w - 1, trailing=0)
    elif kind == "front" and worn:
        a[fin_rows, 0] = C(ICE[2])
    elif kind == "back" and worn:
        rows = np.where(cols(w, h, FIN_OUTER)[:, 0])[0]
        a[rows.min():rows.max() + 1, w - 1] = C(BLUE[2])
    elif kind == "front":
        shin_fin(a, FIN_FRONT, 0)
    if worn:
        # Ankle: white trim over a slate cuff with a dark lower edge.
        cuff = np.zeros((h, w), bool)
        cuff[(TRIM_ROW + 1) * G:] = True
        plate(a, cuff, seed=seed + 5)
        emboss(a, spans(w, h, {TRIM_ROW: (0, 7)}), WHITE[5], WHITE[1], fill=WHITE[4])
        if kind in ("front", "back"):
            a[0:4] = C(SLATE[1])                 # the seam shared with the waistband
    a[h - 1] = C(SLATE[1])
    if kind == "front":
        even_strip(a, 12, 15, 13)
    elif kind == "back":
        even_strip(a, 0, 3, 2)
    return a


def paint_leg_top(seed: int = 71) -> np.ndarray:
    a = blank(4 * S, 4 * S)
    plate(a, seed=seed, bias=-0.1)
    return a


def paint_sole(seed: int = 73) -> np.ndarray:
    a = blank(4 * S, 4 * S)
    plate(a, seed=seed, bias=-0.4, spread=0.5)
    return a


def paint_waist(kind: str, seed: int) -> np.ndarray:
    """The waistband: the body's lower 4 units. kind "front", "back" or "side" (the wearer's
    right side, texture left = back; the left side is its mirror). A lighter belt plate
    with a lit lip, then the chestplate's hem: the white band of its sides carried round
    the front with two teeth under it, the point of the back chevron with its cyan inner
    V. The last two rows are the dark seam the legs' tops share (see SEAM)."""
    w, h = (8 if kind in ("front", "back") else 4) * S, 12 * S
    a = blank(w, h)
    band = np.zeros((h, w), bool)
    band[WAIST_TOP * G:] = True
    plate(a, band, seed=seed)
    belt = np.zeros((h, w), bool)
    belt[WAIST_TOP * G:(WAIST_TOP + 2) * G] = True
    plate(a, belt, seed=seed + 1, bias=0.3, spread=0.7)
    a[WAIST_TOP * G] = C(SLATE[6])
    if kind == "front":
        emboss(a, spans(w, h, FRONT_TRIM, mirror_x=True), WHITE[5], WHITE[1], fill=WHITE[4])
    elif kind == "side":
        emboss(a, spans(w, h, SIDE_TRIM), WHITE[5], WHITE[1], fill=WHITE[4])
    else:
        a[(WAIST_TOP + 2) * G - 1] = C(SLATE[1])
        emboss(a, spans(w, h, WAIST_V, mirror_x=True), WHITE[5], WHITE[1], fill=WHITE[4])
        stripe(a, spans(w, h, WAIST_CYAN, mirror_x=True))
    a[SEAM * G:] = C(SLATE[1])
    return a


def paint_humanoid() -> Image.Image:
    out = blank(64 * S, 32 * S)

    def put(name, arr):
        x0, y0, x1, y1 = region(HUMANOID, name, S)
        out[y0:y1 + 1, x0:x1 + 1] = arr

    put("leg_outer", paint_leg_face("outer", 101))
    put("leg_front", paint_leg_face("front", 111))
    put("leg_inner", paint_leg_face("inner", 121))
    put("leg_back", paint_leg_face("back", 131))
    put("leg_top", paint_leg_top())
    put("leg_sole", paint_sole())
    put("body_front", paint_waist("front", 141))
    put("body_back", paint_waist("back", 151))
    side = paint_waist("side", 161)
    put("body_right", side)
    put("body_left", side[:, ::-1])
    return to_image(out)


# ======================================================================================
# Item: a pair of leggings in 3D (model units, front = north / -z, the wearer's right =
# +x, like the chestplate). The worn paintings are projected onto the faces.
# ======================================================================================

HX0, HX1 = 2.8, 13.2          # hips; the legs' outer faces line up with them
LEG = (9.2, 13.2)             # the right leg's x range (the left leg is its mirror)
LZ0, LZ1 = 5.7, 10.3
LY1, HY1 = 13.2, 16.8         # top of the legs, top of the hips
RIM_H, RIM_W = 0.6, 0.75      # the rim round the waist opening
BAND_PROUD = 0.15             # how far the chevrons stand out on the item
LW, LD = LEG[1] - LEG[0], LZ1 - LZ0
# The shin fin on the right leg: upright slabs (y0, y1, x inner, x outer), the stepped
# pale leading edge on the inner side rising out to the tip, the outer edge straight.
SHIN_FIN = [(0.7, 3.3, 13.0, 15.9), (3.3, 4.1, 13.6, 15.9), (4.1, 4.9, 14.1, 15.9), (4.9, 5.7, 14.6, 15.9),
            (5.7, 6.5, 15.1, 15.9)]
SHIN_FIN_Z = (6.4, 7.5)
SHIN_FIN_O = (12.5, 7.5)      # projection origin of its texture (x, y)


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


def leg_atlas() -> tuple[np.ndarray, np.ndarray]:
    """The "legs" texture: the leg faces side by side on rows 0-47 (outer, front, inner,
    back), the hips' front and back on rows 48-63; and the glint mask (blue bands)."""
    a = blank(64, 64)
    lit = np.zeros((64, 64), bool)
    for i, (kind, seed) in enumerate((("outer", 101), ("front", 111), ("inner", 121), ("back", 131))):
        face = paint_leg_face(kind, seed, worn=False)
        if kind == "inner":
            face[..., :3] *= 0.6                  # it faces the shadowed gap between the legs
        a[0:48, 16 * i:16 * i + 16] = face
        blue_runs, _ = CHEVRON[kind]
        if kind != "inner":
            lit[0:48, 16 * i:16 * i + 16] = cols(16, 48, blue_runs)
    a[48:64, 0:32] = paint_waist("front", 141)[32:48]
    a[48:64, 32:64] = paint_waist("back", 151)[32:48]
    cyan = spans(32, 48, WAIST_CYAN, mirror_x=True)
    lit[48:64, 32:64] = cyan[32:48]
    return a, lit


def item_textures() -> None:
    atlas, lit = leg_atlas()
    save_animation([to_image(glint(atlas, lit, (i / 16 - 0.3) % 1)) for i in range(16)], "legs", frametime=3)
    sides = blank(64, 64)
    side = paint_waist("side", 161)[32:48]
    sides[0:16, 0:16] = side
    sides[0:16, 16:32] = side[:, ::-1]
    sides[32:48, 0:16] = paint_sole()
    save(to_image(sides), "hips")
    material("slate", [SLATE[7], SLATE[6]], seed=81, tones=SLATE)
    material("white", [WHITE[5], WHITE[4], WHITE[4], WHITE[3]])
    material("band_top", [BLUE[6], BLUE[5]])
    material("band_edge", [BLUE[5]] + [BLUE[4]] * 10 + [WHITE[4]])
    material("cyan", [ICE[1], BLUE[7], BLUE[6], BLUE[5]])
    material("ice", [ICE[3], ICE[2], ICE[1]])
    material("dark", [SLATE[2], SLATE[1]])
    material("deep", [BLUE[3], BLUE[2], BLUE[1]])
    base_row = int(round((SHIN_FIN_O[1] - SHIN_FIN[0][1]) * 4))
    save_animation([to_image(blade(SHIN_FIN, SHIN_FIN_O[0], SHIN_FIN_O[1], base_row, t=(i / 16 + 0.5) % 1))
                    for i in range(16)], "fin", frametime=3)


def textures() -> None:
    save_layer(paint_humanoid(), "humanoid_leggings")
    item_textures()


# --- projected UVs (uv units: 4 texels each on the 64x64 atlases) ----------------------

def _v_leg(y):
    return (LY1 - y) * 12 / LY1


def _v_hip(y):
    return 12 + (HY1 - y) * 4 / (HY1 - LY1)


def leg_faces(frm, to) -> dict:
    """Faces of a box on the right leg (or standing on it): the projected leg paintings."""
    (xa, ya, za), (xb, yb, zb) = frm, to
    vt, vb = _v_leg(yb), _v_leg(ya)
    return {"north": ("legs", [4 + (LEG[1] - xb) * 4 / LW, vt, 4 + (LEG[1] - xa) * 4 / LW, vb]),
            "south": ("legs", [12 + (xa - LEG[0]) * 4 / LW, vt, 12 + (xb - LEG[0]) * 4 / LW, vb]),
            "east": ("legs", [(LZ1 - zb) * 4 / LD, vt, (LZ1 - za) * 4 / LD, vb]),
            "west": ("legs", [8 + (za - LZ0) * 4 / LD, vt, 8 + (zb - LZ0) * 4 / LD, vb])}


def hip_faces(frm, to) -> dict:
    (xa, ya, za), (xb, yb, zb) = frm, to
    vt, vb = _v_hip(yb), _v_hip(ya)
    wv = HX1 - HX0
    st, sb = (HY1 - yb) * 4 / (HY1 - LY1), (HY1 - ya) * 4 / (HY1 - LY1)
    return {"north": ("legs", [(HX1 - xb) * 8 / wv, vt, (HX1 - xa) * 8 / wv, vb]),
            "south": ("legs", [8 + (xa - HX0) * 8 / wv, vt, 8 + (xb - HX0) * 8 / wv, vb]),
            "east": ("hips", [(LZ1 - zb) * 4 / LD, st, (LZ1 - za) * 4 / LD, sb]),
            "west": ("hips", [4 + (za - LZ0) * 4 / LD, st, 4 + (zb - LZ0) * 4 / LD, sb])}


def band_runs(kind: str) -> list:
    """Per design column of a leg face, the (first row, last row) the chevron covers (the
    blue band and the white band under it are one run), or None."""
    blue_runs, white_runs = CHEVRON[kind]
    out = []
    for b, w in zip(blue_runs, white_runs):
        if b is None:
            out.append(w)
        elif w is None:
            out.append(b)
        else:
            out.append((min(b[0], w[0]), max(b[1], w[1])))
    return out


def raised_band(kind: str, depth: float = BAND_PROUD) -> list[dict]:
    """The chevron standing a little proud of one face of the right leg: one box per
    design column, so its stepped top and bottom edges catch the light like the
    concept's pixel stairs. The outward face shows the projected painting; runs at the
    outer corner reach over it so the front, side and back bands meet."""
    parts = []
    cw, cd, rh = LW / 8, LD / 8, LY1 / 24
    for c, run in enumerate(band_runs(kind)):
        if run is None:
            continue
        r0, r1 = run
        ya, yb = LY1 - (r1 + 1) * rh, LY1 - r0 * rh
        if kind == "front":
            xa, xb = LEG[1] - (c + 1) * cw, LEG[1] - c * cw
            frm, to, out = (xa, ya, LZ0 - depth), (xb + (depth if c == 0 else 0), yb, LZ0), "north"
            uv_to = (xb, yb, LZ0)
        elif kind == "back":
            xa, xb = LEG[0] + c * cw, LEG[0] + (c + 1) * cw
            frm, to, out = (xa, ya, LZ1), (xb + (depth if c == 7 else 0), yb, LZ1 + depth), "south"
            uv_to = (xb, yb, LZ1 + depth)
        else:
            za, zb = LZ1 - (c + 1) * cd, LZ1 - c * cd
            frm, to, out = (LEG[1], ya, za), (LEG[1] + depth, yb, zb), "east"
            uv_to = to
        faces = {s: "band_edge" for s in ("north", "south", "east", "west")}
        faces.update(up="band_top", down="dark")
        faces[out] = leg_faces(frm, uv_to)[out]
        parts.append(box(frm, to, "band_edge", faces=faces))
    return parts


def hip_boxes(rows_runs: dict, depth: float, side_tex: str, back: bool = False) -> list[dict]:
    """Boxes for a mirrored design-pixel shape {row: (c0, c1)} (body rows 16-23) standing
    proud of the hips' front (or back); rows with the same run merge into one box."""
    parts, groups = [], []
    for r, run in sorted(rows_runs.items()):
        if groups and groups[-1][1] == r - 1 and groups[-1][2] == run:
            groups[-1][1] = r
        else:
            groups.append([r, r, run])
    cw = (HX1 - HX0) / 16
    rh = (HY1 - LY1) / 8
    for r0, r1, (c0, c1) in groups:
        runs = [(c0, 15 - c0)] if c1 >= 7 else [(c0, c1), (15 - c1, 15 - c0)]
        ya, yb = HY1 - (r1 + 1 - WAIST_TOP) * rh, HY1 - (r0 - WAIST_TOP) * rh
        for a0, a1 in runs:
            if back:
                frm, to, out = (HX0 + a0 * cw, ya, LZ1), (HX0 + (a1 + 1) * cw, yb, LZ1 + depth), "south"
            else:
                frm, to, out = (HX1 - (a1 + 1) * cw, ya, LZ0 - depth), (HX1 - a0 * cw, yb, LZ0), "north"
            faces = {s: side_tex for s in ("north", "south", "east", "west", "up", "down")}
            faces[out] = hip_faces(frm, to)[out]
            parts.append(box(frm, to, side_tex, faces=faces))
    return parts


def right_leg() -> list[dict]:
    frm, to = (LEG[0], 0.0, LZ0), (LEG[1], LY1, LZ1)
    faces = leg_faces(frm, to)
    faces["down"] = ("hips", [0, 8, 4, 12])
    parts = [box(frm, to, "legs", faces=faces, skip=("up",))]
    parts += raised_band("front") + raised_band("outer") + raised_band("back")
    ox, oy = SHIN_FIN_O
    z0, z1 = SHIN_FIN_Z
    for y0, y1, xa, xb in SHIN_FIN:
        ua, ub, vt, vb = xa - ox, xb - ox, oy - y1, oy - y0
        faces = {"north": ("fin", [ub, vt, ua, vb]), "south": ("fin", [ua, vt, ub, vb]),
                 "up": "ice", "west": "ice", "east": "deep", "down": "dark"}
        parts.append(box((xa, y0, z0), (xb, y1, z1), "fin", faces=faces))
    return parts


def hips() -> list[dict]:
    frm, to = (HX0, LY1, LZ0), (HX1, HY1, LZ1)
    faces = hip_faces(frm, to)
    faces["up"] = "dark"
    faces["down"] = "dark"
    parts = [box(frm, to, "legs", faces=faces)]
    # Rim round the waist opening (the hips' top shows as its dark floor).
    rim = {"down": "dark"}
    y0, y1 = HY1, HY1 + RIM_H
    for frm, to in (((HX0, y0, LZ0), (HX1, y1, LZ0 + RIM_W)), ((HX0, y0, LZ1 - RIM_W), (HX1, y1, LZ1)),
                    ((HX0, y0, LZ0 + RIM_W), (HX0 + RIM_W, y1, LZ1 - RIM_W)),
                    ((HX1 - RIM_W, y0, LZ0 + RIM_W), (HX1, y1, LZ1 - RIM_W))):
        parts.append(box(frm, to, "slate", faces=rim))
    parts += hip_boxes(FRONT_TRIM, 0.35, "white")
    parts += hip_boxes(WAIST_V, 0.4, "white", back=True)
    parts += hip_boxes(WAIST_CYAN, 0.25, "cyan", back=True)
    return parts


def build() -> list[dict]:
    right = right_leg()
    return hips() + right + mirror(right, "x", 8.0)


def models() -> dict:
    parts = build()
    d = display(KIND, parts, gui_rotation=(18, 150, 0), gui_span=15.5)
    # The front (north) faces out in hand and toward the camera, like the chestplate.
    centre = (8.0, 9.0, 8.0)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, centre, "fist", 0.42)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.4, 0, -1)}, centre, (0.52, -0.38, -0.95), 0.5,
                                       pose=None)
    return {"main": model(parts, d)}
