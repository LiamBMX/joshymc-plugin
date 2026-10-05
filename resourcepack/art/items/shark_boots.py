"""Shark Boots: the feet of the Shark Set (shark_chestplate.py is the set's pilot).

Worn (humanoid layer, 4x): chunky navy-slate ankle boots whose top edge is the cuff band,
a steel-blue upper course with a white tooth in front over a white lower course with a
notch under the tooth (the concept's stepped white band), starting on leg design row 15
(layer row 110) where the leggings' art ends.
Under it the lit top of the toe box and an ice-blue toe cap that runs back along the
sides as the sole; a white heel tooth hangs from the band at the back. The stepped fin is
painted on the outer side (nothing may stick out of worn armour), tip at the heel and
pale leading edge stepping down toward the toe.

Item: a pair of boots in 3D, standing side by side as worn: hollow shafts with a lit rim,
the cuff band standing proud round each ankle with its white tooth and lower course
standing out again, a heel tooth, a toe box with a raised ice-blue toe cap on a sole, and
a stepped fin leaning off the outer side of each boot. A glint climbs the fins' leading
edges, then sweeps the toe caps and the cuff (16 frames x 3 ticks, in step with the
chestplate's fins and stripes).
"""
from __future__ import annotations

import numpy as np
from PIL import Image

from art.kit import (HUMANOID, SIDES, bounds, box, display, mirror, model, place, region, rgba, save,
                     save_animation, save_layer, turn)

ID = "shark_boots"
NAME = "Shark Boots"
KIND = "boots"
COUNTERPART = "item/netherite_boots"

# ======================================================================================
# SHARK SET PALETTE AND PAINTERS - copied from shark_chestplate.py (the pilot) so the
# suit reads as one outfit. Colours were sampled from the concept sheet.
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


# ======================================================================================
# Shared boot design. The worn faces and the item paint their cuff, toe cap and fin with
# these, so armour and icon are one design.
# ======================================================================================

def rect(w: int, h: int, x0: int, x1: int, y0: int, y1: int) -> np.ndarray:
    """Texel mask of the inclusive box x0..x1, y0..y1 (clipped to the face)."""
    m = np.zeros((h, w), bool)
    m[max(0, y0):min(h, y1 + 1), max(0, x0):min(w, x1 + 1)] = True
    return m


def steel(a: np.ndarray, mask: np.ndarray, ao: float = 0.35) -> None:
    """The cuff's upper course: the chestplate's stripe in the concept's deep steel blue
    (ice rim on the lit side, the darkest blue under it), so the white tooth stands out."""
    stripe(a, mask, lo=2, hi=4, ao=ao)


def whiten(a: np.ndarray, mask: np.ndarray, ao: float = 0.45) -> None:
    """A raised white plate: the chestplate's hem teeth."""
    emboss(a, mask, WHITE[5], WHITE[1], ao=ao, fill=WHITE[4])


def toe_cap(a: np.ndarray, mask: np.ndarray, seed: int, light=None) -> None:
    """The ice-blue toe cap and sole: pale blue tiles, an ice rim on top, a lighter block
    where the light catches the toe (light = texel columns c0..c1) and a deep-blue edge
    along the bottom."""
    plate(a, mask, seed=seed, tones=BLUE, bias=0.55, spread=0.6)
    top, bottom, left, right = edges(mask)
    if light is not None:
        r = np.where(mask.any(1))[0]
        hl = mask & rect(a.shape[1], a.shape[0], light[0], light[1], r.min() + 1, r.min() + 3)
        a[hl] = C(ICE[0])
        a[hl & shift(top, 0, 1)] = C(ICE[1])
    a[top] = C(ICE[1])
    a[bottom] = C(BLUE[3])


# --------------------------------------------------------------------------------------
# Worn layer (humanoid, 256x128). The vanilla boot cube is the leg cube inflated by 1
# unit: each leg face (16 x 48 texels) stretches over 6 x 14 units, so a texel is 0.375
# wide and 0.29 tall, and layer row 124.6 is the ground (rows 125-127 sit in the floor
# while the wearer stands). Face rows below count from the face's top (layer row 80).
#   leg_front: left = outside, right = inside   leg_outer: left = back, right = front
#   leg_back:  left = inside, right = outside   leg_inner: left = front, right = back
#   leg_sole:  top = toe, left = outside        (the left leg mirrors all of these)
# --------------------------------------------------------------------------------------

FW, FH = 4 * S, 12 * S        # one leg face, 16 x 48 texels
TOP = 30                      # the boot's top edge: layer row 110, leg design row 15. The
                              # leggings' art (chevron, shin fin tip) ends above it; their
                              # ankle trim and cuff below it are covered by the boots.
BAND_HI = (30, 31)            # cuff band, upper course: steel blue, a white tooth in front
BAND_LO = (32, 33)            # lower course: white, a notch under the tooth
BAND_EDGE = 34                # the band's dark under-edge
TOE_TOP = (35, 36)            # lit top of the toe box
TOE = (37, 39)                # its shaded front
CAP = (40, 44)                # ice toe cap / sole; row 44 is the last one above ground
UNDER = 45                    # rows 45-47 are under the ground
SEAM_FRONT = (12, 13)         # the gap between the boots: the legs share their inner
SEAM_BACK = (2, 3)            # 2.2 units, whose centre lands on these columns
# The fin on the outer side: (first column, last column, top row) per step, column 0 at
# the heel. The tip stands at the back of the cuff and the pale leading edge steps down
# toward the toe, under the leggings' shin fin; FIN_BOTTOM is its lowest row.
WORN_FIN = [(0, 2, 30), (3, 4, 32), (5, 6, 34), (7, 8, 36)]
FIN_BOTTOM = 38


def boot_mask() -> np.ndarray:
    return rect(FW, FH, 0, FW - 1, TOP, FH - 1)


def paint_boot_base(a: np.ndarray, seed: int) -> None:
    """Mottled slate from the cuff to the sole, dark below the ground line."""
    plate(a, boot_mask(), seed=seed)
    a[UNDER:] = C(SLATE[1])


def paint_band(a: np.ndarray, tooth=None, heel=None) -> None:
    """The cuff band across a whole face: a steel-blue upper course over a white lower course,
    each a lit row over a body row, and a dark under-edge. tooth = (c0, c1): a white tooth
    in the upper course over a slate notch in the lower one (the front). heel = rows of
    (c0, c1): a white heel tooth hanging under the band (the back)."""
    hi0, hi1 = BAND_HI
    lo0, lo1 = BAND_LO
    a[hi0] = C(BLUE[5])
    a[hi1] = C(BLUE[3])
    a[lo0] = C(WHITE[5])
    a[lo1] = C(WHITE[3])
    a[BAND_EDGE] = C(SLATE[1])
    if tooth is not None:
        c0, c1 = tooth
        a[hi0, c0:c1 + 1] = C(WHITE[5])
        a[hi1, c0:c1 + 1] = C(WHITE[4])
        a[hi0:hi1 + 1, c0] = C(WHITE[5])
        a[hi1, c1] = C(WHITE[2])
        a[lo0, c0:c1 + 1] = C(SLATE[2])
        a[lo1, c0:c1 + 1] = C(SLATE[3])
        a[lo0:lo1 + 1, c1 + 1] = C(WHITE[2])
    if heel is not None:
        for i, (c0, c1) in enumerate(heel):
            r = BAND_EDGE + i
            a[r, c0:c1 + 1] = C(WHITE[4] if i < len(heel) - 1 else WHITE[2])
            a[r, c0] = C(WHITE[5])
            a[r, c1] = C(WHITE[2])
        a[BAND_EDGE + len(heel), heel[-1][0]:heel[-1][1] + 1] = C(SLATE[1])


def paint_toe(a: np.ndarray, c0: int, c1: int, seed: int) -> None:
    """The toe box over columns c0..c1: a lit top edge on light plating, then its front
    in darker plating shaded under that edge."""
    plate(a, rect(FW, FH, c0, c1, *TOE_TOP), seed=seed, bias=0.35, spread=0.6)
    a[TOE_TOP[0], c0:c1 + 1] = C(SLATE[7])
    plate(a, rect(FW, FH, c0, c1, *TOE), seed=seed + 1, bias=-0.1)
    a[TOE[0], c0:c1 + 1, :3] *= 0.7


def worn_fin() -> tuple[np.ndarray, np.ndarray]:
    """(mask, lead) of the outer-side fin on a 16 x 48 face (left = back): its stepped
    outline and the 2-texel leading edge along the top and the front-facing steps."""
    mask = np.zeros((FH, FW), bool)
    for c0, c1, r0 in WORN_FIN:
        mask[r0:FIN_BOTTOM + 1, c0:c1 + 1] = True
    lead = mask & (~shift(mask, 0, 2) | ~shift(mask, -2, 0))
    lead[FIN_BOTTOM - 1:] &= ~shift(mask, -2, 0)[FIN_BOTTOM - 1:]
    return mask, lead


def sync_inner(a: np.ndarray, inner: str) -> None:
    """The two boot cubes overlap by 2.2 units, so their front, back and sole planes share
    a 6-texel strip at the inner edge and z-fight there while the wearer stands still
    (vanilla armour does too). Each leg shows that strip mirrored (the left leg's texture
    is mirrored with its cube), so mirroring the strip about its centre makes both legs
    agree and the fight invisible. inner: "right" (front, sole) or "left" (back)."""
    pairs = ((10, 15), (11, 14), (12, 13)) if inner == "right" else ((5, 0), (4, 1), (3, 2))
    for src, dst in pairs:
        a[:, dst] = a[:, src]


def paint_leg_front(seed: int = 101) -> np.ndarray:
    a = blank(FW, FH)
    paint_boot_base(a, seed)
    # Cuff band: a white tooth over a notch in the middle (texel columns 4-9), steel blue
    # over white either side (12-13 are the seam, 14-15 mirror 10-11).
    paint_band(a, tooth=(4, 9))
    # Toe box: lit top edge, shaded front, the ice toe cap down to the ground.
    paint_toe(a, 0, FW - 1, seed + 7)
    toe_cap(a, rect(FW, FH, 0, FW - 1, *CAP), seed + 3, light=(2, 7))
    a[rect(FW, FH, SEAM_FRONT[0], SEAM_FRONT[1], TOP, FH - 1)] = C(SLATE[1])
    sync_inner(a, "right")
    return a


def paint_leg_back(seed: int = 131) -> np.ndarray:
    a = blank(FW, FH)
    paint_boot_base(a, seed)
    # Steel-blue upper course, white lower course with the heel tooth hanging from it.
    paint_band(a, heel=[(6, 13), (6, 13), (8, 11), (8, 11)])
    # A thin ice sole line along the heel.
    a[CAP[1] - 1] = C(ICE[0])
    a[CAP[1]] = C(BLUE[4])
    # The fin's trailing edge shows at the outer corner.
    rows = np.where(worn_fin()[0][:, 0])[0]
    a[rows.min():rows.max() + 1, FW - 1] = C(BLUE[2])
    a[rect(FW, FH, SEAM_BACK[0], SEAM_BACK[1], TOP, FH - 1)] = C(SLATE[1])
    sync_inner(a, "left")
    return a


def paint_leg_side(outer: bool, seed: int) -> np.ndarray:
    """The outer face (left = back, right = front) carries the fin; the inner face is its
    mirror image without it (left = front)."""
    a = blank(FW, FH)
    paint_boot_base(a, seed)
    paint_band(a)
    # Toe box along the front half; the ice sole runs deep at the toe and thins to the heel.
    paint_toe(a, 8, FW - 1, seed + 7)
    sole = rect(FW, FH, 9, 15, *CAP) | rect(FW, FH, 4, 8, CAP[0] + 2, CAP[1]) | \
        rect(FW, FH, 0, 3, CAP[1] - 1, CAP[1])
    toe_cap(a, sole, seed + 3, light=(11, 15))
    if outer:
        mask, lead = worn_fin()
        fin(a, mask, lead, ao=0.5)
    return a if outer else a[:, ::-1].copy()


def paint_leg_sole(seed: int = 151) -> np.ndarray:
    """Underside (top = toe, left = outside): dark blue tread, an ice rim at the toe."""
    a = blank(FW, FW)
    plate(a, seed=seed, tones=BLUE, bias=-0.35, spread=0.6)
    for y in range(3, FW, 4):
        a[y, 1:FW - 1] = C(SLATE[1])
    a[0:2] = C(BLUE[5])
    a[0] = C(ICE[0])
    a[rect(FW, FW, SEAM_FRONT[0], SEAM_FRONT[1], 0, FW - 1)] = C(SLATE[1])
    sync_inner(a, "right")
    return a


def paint_humanoid() -> Image.Image:
    out = blank(64 * S, 32 * S)

    def put(name, arr):
        x0, y0, x1, y1 = region(HUMANOID, name, S)
        out[y0:y1 + 1, x0:x1 + 1] = arr

    put("leg_front", paint_leg_front())
    put("leg_back", paint_leg_back())
    put("leg_outer", paint_leg_side(True, 111))
    put("leg_inner", paint_leg_side(False, 121))
    put("leg_sole", paint_leg_sole())
    return to_image(out)


# --------------------------------------------------------------------------------------
# Item. The right boot (+x, toe toward -z like the set's other pieces) is built first and
# mirrored about x = 8 for the left one. Most faces sample projected paintings, 4 texels
# per unit, laid out as each side is seen (front: wearer's right on the left; outer side:
# heel on the left; inner side: toe on the left; back: inside on the left; top and sole:
# toe up), so the toe box, cap, cuff, shaft and heel tooth continue one painting across
# their boxes and the bevels light from the top left like the worn layer.
# --------------------------------------------------------------------------------------

BX0, BX1 = 9.0, 14.5          # boot width (x): 2 units apart once mirrored
TZ0, SZ0, SZ1 = 1.5, 6.0, 12.0  # toe front, shaft front, shaft back (z)
TOE_H, H = 3.75, 11.5         # toe box height, shaft height
CAP_H = 2.5                   # ice toe cap (on the toe box, standing 0.25 proud)
CUFF = (3.75, 5.0, 6.25)      # cuff band: bottom, the line between its courses, top (y)
TOOTH = (10.25, 13.25)        # x of the front tooth (upper course) over its notch
D = 0.25                      # how proud the cuff ring stands; its white parts twice that
WALL = 1.25
FLOOR = 9.0                   # the dark floor inside the shaft
LIP = 0.75                    # the lit lip round the top of the shaft, D proud
SOLE = -0.5                   # sole slab bottom (y)
OY = 14.0                     # v = OY - y on the elevations
HEEL = [(9.75, 13.75, 2.75, CUFF[0]), (10.75, 12.75, 1.75, 2.75)]   # heel tooth (x0, x1, y0, y1)
# Fin on the outer wall: slabs (y0, y1, z front, z back) stepping up toward the heel, the
# top step clearing the rim; it leans out from its root by FIN_LEAN degrees.
FIN = [(3.0, 7.5, 6.75, 12.25), (7.5, 8.75, 7.75, 12.25), (8.75, 10.0, 8.75, 12.25), (10.0, 11.0, 9.75, 12.25),
       (11.0, 12.25, 10.75, 12.25)]
FIN_X = (BX1 - 0.25, BX1 + 0.5)
FIN_OZ = 4.0                  # projection origin of the fin texture (z), v = OY - y
FIN_LEAN = -5.0

PROJ = ("front", "back", "side_out", "side_in", "top", "sole")


def _px(v: float) -> int:
    return int(round(v * 4))


def wrect(proj: str, h0: float, h1: float, v0: float, v1: float) -> np.ndarray:
    """Mask on a 64x64 projected painting of the world box h0..h1 (x; z on the sides) by
    v0..v1 (y; z on top and sole), in that painting's layout."""
    if proj in ("front", "side"):
        c0, c1 = _px(16 - h1), _px(16 - h0) - 1
    else:
        c0, c1 = _px(h0), _px(h1) - 1
    if proj in ("top", "sole"):
        r0, r1 = _px(v0), _px(v1) - 1
    else:
        r0, r1 = _px(OY - v1), _px(OY - v0) - 1
    return rect(64, 64, c0, c1, r0, r1)


def pbox(frm, to, tex, skip=()):
    """A box whose faces sample the projected paintings (PROJ) or plain swatches.
    tex: one name, or {side: name, "default": name}."""
    (x0, y0, z0), (x1, y1, z1) = frm, to
    faces = {}
    for side in SIDES:
        if side in skip:
            continue
        name = tex.get(side, tex.get("default")) if isinstance(tex, dict) else tex
        if name not in PROJ:
            faces[side] = name
            continue
        uv = {"north": [16 - x1, OY - y1, 16 - x0, OY - y0], "south": [x0, OY - y1, x1, OY - y0],
              "east": [16 - z1, OY - y1, 16 - z0, OY - y0], "west": [z0, OY - y1, z1, OY - y0],
              "up": [x0, z0, x1, z1], "down": [x0, z1, x1, z0]}[side]
        faces[side] = (name, [round(min(16.0, max(0.0, v)), 4) for v in uv])
    first = next(iter(faces.values()))
    return box(frm, to, first if isinstance(first, str) else first[0], faces=faces, skip=skip)


def paint_cuff(a: np.ndarray, proj: str) -> None:
    """The cuff band on an elevation ("front", "back" or "side")."""
    lo_y, mid, hi_y = CUFF
    h0, h1 = (BX0, BX1) if proj in ("front", "back") else (SZ0, SZ1)
    hi = wrect(proj, h0 - D, h1 + D, mid, hi_y)          # the ring
    lo = wrect(proj, h0 - 2 * D, h1 + 2 * D, lo_y, mid)  # the raised lower course
    if proj == "front":
        tooth = wrect(proj, TOOTH[0], TOOTH[1], mid, hi_y)
        notch = wrect(proj, TOOTH[0], TOOTH[1], lo_y, mid)
        steel(a, hi & ~tooth, ao=0)
        plate(a, notch, seed=207, bias=-0.15)
        a[edges(notch)[1]] = C(SLATE[2])
        whiten(a, tooth, ao=0.45)
        whiten(a, lo & ~notch, ao=0)
    else:
        steel(a, hi, ao=0)
        whiten(a, lo, ao=0)


def paint_shaft(a: np.ndarray, proj: str, h0: float, h1: float, seed: int) -> None:
    """Slate plating up the shaft and the lit lip round its top."""
    plate(a, wrect(proj, h0, h1, 0, H), seed=seed)
    lip = wrect(proj, h0 - D, h1 + D, H - LIP, H)
    plate(a, lip, seed=seed + 1, bias=0.3, spread=0.6)
    top, bottom, left, right = edges(lip)
    a[top] = C(SLATE[7])
    a[bottom] = C(SLATE[3])
    shadow(a, lip, 0.4)


def item_front() -> np.ndarray:
    a = blank(64, 64)
    paint_shaft(a, "front", BX0, BX1, 201)
    # Toe box front: lit top edge and shaded slate over the toe cap.
    toe = wrect("front", BX0, BX1, CAP_H, TOE_H)
    plate(a, toe, seed=203, bias=-0.1)
    a[edges(toe)[0]] = C(SLATE[6])
    cap = wrect("front", BX0 - D, BX1 + D, 0, CAP_H)
    c = np.where(cap.any(0))[0]
    toe_cap(a, cap, 205, light=(c.min() + 4, c.min() + 12))
    a[wrect("front", BX0 - D, BX1 + D, SOLE, 0)] = C(BLUE[2])
    a[edges(wrect("front", BX0 - D, BX1 + D, SOLE, 0))[0]] = C(BLUE[4])
    paint_cuff(a, "front")
    return a


def item_back() -> np.ndarray:
    a = blank(64, 64)
    paint_shaft(a, "back", BX0, BX1, 211)
    paint_cuff(a, "back")
    heel = wrect("back", BX0 - D, BX1 + D, CUFF[0], CUFF[1])
    for x0, x1, y0, y1 in HEEL:
        heel |= wrect("back", x0, x1, y0, y1)
    whiten(a, heel, ao=0.45)
    a[wrect("back", BX0, BX1, 0, 0.25)] = C(BLUE[5])
    a[wrect("back", BX0 - D, BX1 + D, SOLE, 0)] = C(BLUE[2])
    a[edges(wrect("back", BX0 - D, BX1 + D, SOLE, 0))[0]] = C(BLUE[4])
    return a


def item_side() -> np.ndarray:
    """An elevation of a boot's side, heel on the left: side_out as is, side_in mirrored."""
    a = blank(64, 64)
    paint_shaft(a, "side", SZ0, SZ1, 221)
    toe = wrect("side", TZ0, SZ0, CAP_H, TOE_H)
    plate(a, toe, seed=223, bias=-0.1)
    a[edges(toe)[0]] = C(SLATE[6])
    paint_cuff(a, "side")
    # The toe cap's side plates, then a thin ice line along the heel.
    cap = wrect("side", TZ0 - D, SZ0 - D, 0, CAP_H)
    c = np.where(cap.any(0))[0]
    toe_cap(a, cap, 225, light=(c.max() - 7, c.max() - 1))
    a[wrect("side", SZ0 - D, SZ1, 0, 0.25)] = C(BLUE[5])
    sole = wrect("side", TZ0 - D, SZ1 + D, SOLE, 0)
    a[sole] = C(BLUE[2])
    a[edges(sole)[0]] = C(BLUE[4])
    return a


def item_top() -> np.ndarray:
    """Up faces (x to the right, toe at the top)."""
    a = blank(64, 64)
    toe = wrect("top", BX0, BX1, TZ0, SZ0)
    plate(a, toe, seed=241, bias=0.45, spread=0.6)
    top, bottom, left, right = edges(toe)
    a[top] = C(SLATE[7])
    a[left | right] = C(SLATE[6])
    # Rim around the opening and the dark floor inside.
    rim = wrect("top", BX0, BX1, SZ0, SZ1)
    plate(a, rim, seed=243, bias=0.35, spread=0.6)
    top, bottom, left, right = edges(rim)
    a[top | left] = C(SLATE[7])
    a[bottom | right] = C(SLATE[4])
    hole = wrect("top", BX0 + WALL, BX1 - WALL, SZ0 + WALL, SZ1 - WALL)
    a[hole] = C(SLATE[1])
    a[hole & ~shift(hole, 0, 3)] = C(SLATE[0])
    # Cuff ring tops: steel blue, white over the tooth.
    ring = wrect("top", BX0 - D, BX1 + D, SZ0 - D, SZ1 + D) & ~rim
    a[ring] = C(BLUE[4])
    a[ring & wrect("top", TOOTH[0], TOOTH[1], SZ0 - D, SZ0)] = C(WHITE[5])
    return a


def item_sole() -> np.ndarray:
    a = blank(64, 64)
    m = wrect("sole", BX0 - D, BX1 + D, TZ0 - D, SZ1 + D)
    plate(a, m, seed=251, tones=BLUE, bias=-0.35, spread=0.6)
    for r in range(_px(TZ0) + 3, _px(SZ1), 4):
        a[r, _px(BX0):_px(BX1)] = C(SLATE[1])
    return a


def fin_texture(t=None) -> np.ndarray:
    return blade(FIN, FIN_OZ, OY, t=t)


def item_textures() -> None:
    front = item_front()
    cap = wrect("front", BX0 - D, BX1 + D, 0, CAP_H)
    cuff = wrect("front", BX0 - D, BX1 + D, CUFF[1], CUFF[2])
    lit = cap | cuff
    # The fins flash first (with the chestplate's dorsal fin), then the toe caps and cuff.
    save_animation([to_image(glint(front, lit, (i / 16 - 0.3) % 1)) for i in range(16)], "front", frametime=3)
    save(to_image(item_back()), "back")
    side = item_side()
    save(to_image(side), "side_out")
    save(to_image(side[:, ::-1].copy()), "side_in")
    save(to_image(item_top()), "top")
    save(to_image(item_sole()), "sole")
    save_animation([to_image(fin_texture(i / 16)) for i in range(16)], "fin", frametime=3)
    material("white", [WHITE[5], WHITE[4], WHITE[4], WHITE[3]])
    material("ice", [ICE[3], ICE[2], ICE[1]])
    material("dark", [SLATE[2], SLATE[1]])
    material("rim", [SLATE[7], SLATE[6], SLATE[5]])
    material("deep", [BLUE[3], BLUE[2], BLUE[1]])


# --------------------------------------------------------------------------------------
# Item model
# --------------------------------------------------------------------------------------

SIDE_TEX = {"north": "front", "south": "back", "east": "side_out", "west": "side_in", "up": "top",
            "down": "sole"}


def shaft() -> list[dict]:
    """Hollow shaft: four walls (dark inside) round a floor 2.5 units down."""
    t = WALL
    return [pbox((BX0, 0, SZ0), (BX1, H, SZ0 + t), dict(SIDE_TEX, south="dark"), skip=("down",)),
            pbox((BX0, 0, SZ1 - t), (BX1, H, SZ1), dict(SIDE_TEX, north="dark"), skip=("down",)),
            pbox((BX1 - t, 0, SZ0 + t), (BX1, H, SZ1 - t), dict(SIDE_TEX, west="dark"),
                 skip=("north", "south", "down")),
            pbox((BX0, 0, SZ0 + t), (BX0 + t, H, SZ1 - t), dict(SIDE_TEX, east="dark"),
                 skip=("north", "south", "down")),
            pbox((BX0 + t, 0, SZ0 + t), (BX1 - t, FLOOR, SZ1 - t), "top",
                 skip=("north", "south", "east", "west", "down"))]


def toe() -> list[dict]:
    """Toe box, its raised ice cap (front and both sides) and the sole slab."""
    tex = dict(SIDE_TEX, up="ice", south="ice")
    return [pbox((BX0, 0, TZ0), (BX1, TOE_H, SZ0), SIDE_TEX, skip=("south", "down")),
            pbox((BX0 - D, 0, TZ0 - D), (BX1 + D, CAP_H, TZ0), tex, skip=("south", "down")),
            pbox((BX1, 0, TZ0), (BX1 + D, CAP_H, SZ0 - D), tex, skip=("west", "north", "down")),
            pbox((BX0 - D, 0, TZ0), (BX0, CAP_H, SZ0 - D), tex, skip=("east", "north", "down")),
            pbox((BX0 - D, SOLE, TZ0 - D), (BX1 + D, 0, SZ1 + D), dict(SIDE_TEX, up="deep"))]


def cuff() -> list[dict]:
    """The band round the ankle, D proud, and its white parts standing out again: the
    front tooth over the notch, the lower course either side of it and round the back."""
    y0, mid, y1 = CUFF
    tex = dict(SIDE_TEX, down="dark")
    white = dict(SIDE_TEX, up="white", down="white", default="white")
    ring = [pbox((BX0 - D, y0, SZ0 - D), (BX1 + D, y1, SZ0), tex, skip=("south",)),
            pbox((BX0 - D, y0, SZ1), (BX1 + D, y1, SZ1 + D), tex, skip=("north",)),
            pbox((BX1, y0, SZ0), (BX1 + D, y1, SZ1), tex, skip=("west", "north", "south")),
            pbox((BX0 - D, y0, SZ0), (BX0, y1, SZ1), tex, skip=("east", "north", "south"))]
    e = 2 * D
    raised = [pbox((TOOTH[0], mid, SZ0 - e), (TOOTH[1], y1, SZ0 - D), dict(white, north="front"), skip=("south",)),
              pbox((TOOTH[1], y0, SZ0 - e), (BX1 + e, mid, SZ0 - D), dict(white, north="front"), skip=("south",)),
              pbox((BX0 - e, y0, SZ0 - e), (TOOTH[0], mid, SZ0 - D), dict(white, north="front"), skip=("south",)),
              pbox((BX0 - e, y0, SZ1 + D), (BX1 + e, mid, SZ1 + e), dict(white, south="back"), skip=("north",)),
              pbox((BX0 - e, y0, SZ0 - D), (BX0 - D, mid, SZ1 + D), dict(white, west="side_in"),
                   skip=("east", "north", "south")),
              pbox((BX1 + D, y0, SZ0 - D), (BX1 + e, mid, SZ1 + D), dict(white, east="side_out"),
                   skip=("west", "north", "south"))]
    return ring + raised


def lip() -> list[dict]:
    """A lit lip round the top of the shaft."""
    y0, y1 = H - LIP, H
    tex = dict(SIDE_TEX, up="rim", down="dark")
    return [pbox((BX0 - D, y0, SZ0 - D), (BX1 + D, y1, SZ0), tex, skip=("south",)),
            pbox((BX0 - D, y0, SZ1), (BX1 + D, y1, SZ1 + D), tex, skip=("north",)),
            pbox((BX1, y0, SZ0), (BX1 + D, y1, SZ1), tex, skip=("west", "north", "south")),
            pbox((BX0 - D, y0, SZ0), (BX0, y1, SZ1), tex, skip=("east", "north", "south"))]


def heel_tooth() -> list[dict]:
    tex = {"south": "back", "default": "white"}
    return [pbox((x0, y0, SZ1), (x1, y1, SZ1 + 2 * D), tex, skip=("north", "up")) for x0, x1, y0, y1 in HEEL]


def side_fin() -> list[dict]:
    """Stepped slabs, 0.75 thick, rooted in the outer wall: projected fin texture on the
    broad sides, ice on the stepped leading edge, deep blue on the trailing edge; the whole
    fin leans out from its root."""
    parts = []
    x0, x1 = FIN_X
    for y0, y1, zf, zb in FIN:
        uf, ub, vt, vb = zf - FIN_OZ, zb - FIN_OZ, OY - y1, OY - y0
        faces = {"east": ("fin", [ub, vt, uf, vb]), "west": ("fin", [uf, vt, ub, vb]),
                 "up": ("ice", [0, 0, x1 - x0, zb - zf]), "north": ("ice", [0, 1, x1 - x0, 1 + y1 - y0]),
                 "south": "deep", "down": "dark"}
        parts.append(box((x0, y0, zf), (x1, y1, zb), "fin", faces=faces))
    return turn(parts, FIN_LEAN, "z", (BX1, FIN[0][0], 9.5))


def boot() -> list[dict]:
    return shaft() + lip() + toe() + cuff() + heel_tooth() + side_fin()


def build() -> list[dict]:
    right = boot()
    return right + mirror(right, "x", 8.0)


def textures() -> None:
    save_layer(paint_humanoid(), "humanoid")
    item_textures()


def models() -> dict:
    parts = build()
    d = display(KIND, parts, gui_rotation=(18, 148, 0), gui_span=15.5)
    lo, hi = bounds(parts)
    centre = tuple((lo[i] + hi[i]) / 2 for i in range(3))
    # First person: a little smaller and higher than the preset so the pair clears the
    # bottom of the screen.
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (0.35, 0, 1)}, centre, (0.52, -0.34, -0.9), 0.45,
                                       pose=None)
    return {"main": model(parts, d)}
