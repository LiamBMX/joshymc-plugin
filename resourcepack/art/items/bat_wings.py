"""Bat Wings: a dark, superhero-style bat-wing elytra (Survival, issue #1317).

Worn: two leathery bat wings. A charcoal arm bone runs down the leading edge to the
wrist, four finger bones fan out from it to sharp steel-tipped points, and the black
membrane between them is cut into deep scallops, so the wings hang like a scalloped
cape when folded and spread into a bat silhouette in flight. The worn wings are the
vanilla elytra boxes (one flat 10x20 panel each); the shape is cut out of the texture.
A charcoal leather harness with a gunmetal bat emblem on the chest shows while worn.

Item: a spread pair of 3D bat wings on a gunmetal yoke: jointed arm and finger bones
with knuckles, thumb claws and spiked tips, membrane panels set like a folding fan
(each one tilted on its finger, so the wing has real depth), a thick scalloped hem and a
layered bat emblem with ember eyes. At 1 durability the membrane is torn and a finger
tip has snapped off.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image, ImageDraw

from art.kit import (HUMANOID, WINGS, bar, bounds, box, display, mirror, model, move, place, region, rgba, save,
                     save_layer, turn)

ID = "bat_wings"
NAME = "Bat Wings"
KIND = "elytra"

# --------------------------------------------------------------------------------------
# Palette: near-black with cool slate highlights; shadows lean blue-violet, the few
# bright pixels are cold steel. Ember red only on the emblem's eyes.
# --------------------------------------------------------------------------------------

MEMBRANE = ["#050509", "#0a0b11", "#101219", "#171a23", "#20232f", "#2a2e3c", "#373c4d", "#4a5064", "#626a80"]
UNDER = ["#0b0c12", "#12141c", "#1a1d27", "#232733", "#2d3241", "#393f50", "#474e62", "#596178", "#70788f"]
TORN = ["#060609", "#0c0d12", "#13151b", "#1b1e26", "#242832", "#2f333f", "#3c414e", "#4c5260", "#5f6574"]
BONE = ["#08090d", "#13141b", "#1f212a", "#2d303b", "#3e4250", "#535868", "#6d7385", "#8d93a5", "#b1b7c6"]
STEEL = ["#121319", "#242731", "#3a3e4b", "#575d6c", "#7d8494", "#a7aebb", "#d2d8e1", "#f3f6fa"]
LEATHER = ["#08080b", "#111117", "#1a1b22", "#24262f", "#30333e", "#3e424f"]
EMBER = ["#3a070a", "#7a1016", "#c21d25", "#ff4a3d", "#ffb19a"]

S = 4                      # worn layers are painted at 4x (256x128)
FACE_W, FACE_H = 10 * S, 20 * S
LIGHT = (-0.55, -0.83)     # painted light comes from the upper left (texture space, v down)
BAYER = np.array([0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5], np.float32).reshape(4, 4) / 16 - 0.47


def _c(colour) -> np.ndarray:
    return np.array(rgba(colour), dtype=np.float32)


def _lut(colours) -> np.ndarray:
    return np.stack([_c(c) for c in colours])


def _to_image(arr: np.ndarray) -> Image.Image:
    return Image.fromarray(np.clip(np.rint(arr), 0, 255).astype(np.uint8), "RGBA")


def _shift(mask: np.ndarray, dx: int, dy: int) -> np.ndarray:
    out = np.zeros_like(mask)
    h, w = mask.shape
    out[max(0, dy):h + min(0, dy), max(0, dx):w + min(0, dx)] = \
        mask[max(0, -dy):h - max(0, dy), max(0, -dx):w - max(0, dx)]
    return out


def _grow(mask: np.ndarray) -> np.ndarray:
    return mask | _shift(mask, 1, 0) | _shift(mask, -1, 0) | _shift(mask, 0, 1) | _shift(mask, 0, -1)


def _bayer(h: int, w: int) -> np.ndarray:
    return np.tile(BAYER, (h // 4 + 1, w // 4 + 1))[:h, :w]


# --------------------------------------------------------------------------------------
# Shapes
# --------------------------------------------------------------------------------------

def scallop(a, b, depth: float, toward, n: int = 10) -> list[tuple[float, float]]:
    """Points of a concave hem from a to b (a excluded): a quadratic curve whose middle is
    pulled `depth` toward the point `toward`."""
    mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
    dx, dy = toward[0] - mx, toward[1] - my
    k = depth / max(1e-6, math.hypot(dx, dy))
    cx, cy = mx + dx * k * 2, my + dy * k * 2          # a quadratic reaches half its control offset
    out = []
    for i in range(1, n + 1):
        t = i / n
        out.append(((1 - t) ** 2 * a[0] + 2 * (1 - t) * t * cx + t * t * b[0],
                    (1 - t) ** 2 * a[1] + 2 * (1 - t) * t * cy + t * t * b[1]))
    return out


def polygon_mask(w: int, h: int, pts, ss: int = 4) -> np.ndarray:
    """Pixels whose centres fall inside the polygon (supersampled, majority vote)."""
    m = Image.new("L", (w * ss, h * ss), 0)
    ImageDraw.Draw(m).polygon([(x * ss, y * ss) for x, y in pts], fill=255)
    a = np.array(m, np.float32).reshape(h, ss, w, ss).mean(axis=(1, 3))
    return a >= 127


def polyline_dist(X, Y, pts):
    """Distance from each pixel centre to a polyline, plus the side (signed) and the
    unit normal of the nearest segment."""
    best = np.full(X.shape, np.inf, np.float32)
    side = np.zeros(X.shape, np.float32)
    nxs = np.zeros(X.shape, np.float32)
    nys = np.zeros(X.shape, np.float32)
    along = np.zeros(X.shape, np.float32)
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
        nxs = np.where(closer, -uy, nxs)
        nys = np.where(closer, ux, nys)
        along = np.where(closer, (acc + t), along)
        acc += length
    return best, side, nxs, nys, along, acc


# The bat emblem as pixel art: for each column of the right half (0 = the centre column)
# the first and last filled row of a 27x13 grid. Ears on column 1, the wingtip on
# column 13, three scallop points on the lower edge (columns 3, 7 and 11), a short tail.
BAT_TOP = [2, 0, 3, 4, 4, 4, 4, 4, 3, 3, 2, 2, 1, 0]
BAT_BOTTOM = [12, 11, 10, 10, 8, 7, 8, 10, 8, 7, 7, 9, 6, 3]
BAT_W, BAT_H = 27, 13
BAT_EYES = ((-1, 3), (1, 3))


def bat_mask(w: int, h: int, x0: int, y0: int, scale: int = 1) -> np.ndarray:
    """The pixel bat with its top-left corner at (x0, y0), each cell scale x scale."""
    mask = np.zeros((h, w), bool)
    c = BAT_W // 2
    for i, (t, b) in enumerate(zip(BAT_TOP, BAT_BOTTOM)):
        for col in {c + i, c - i}:
            xa, xb = x0 + col * scale, x0 + (col + 1) * scale
            ya, yb = y0 + t * scale, y0 + (b + 1) * scale
            mask[max(0, ya):max(0, yb), max(0, xa):max(0, xb)] = True
    return mask


# --------------------------------------------------------------------------------------
# The membrane painter (shared by the worn wings and the item's panels). Coordinates are
# texture pixels, v down. `hem` is the free (scalloped) edge, `bones` the finger and arm
# segments as (p0, p1, width0, width1).
# --------------------------------------------------------------------------------------

def paint_membrane(w, h, mask, hem, bones, wrist, palette=MEMBRANE, torn=None, sheen=1.0, veins=True,
                   seed=0):
    img = np.zeros((h, w, 4), np.float32)
    if not mask.any():
        return img
    X, Y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    lut = _lut(palette)
    top = len(palette) - 1
    # Distance to the nearest bone: the skin is pulled tight (lighter) along the bones
    # and sags (darker) in the middle of each panel.
    d_bone = np.full(X.shape, np.inf, np.float32)
    for p0, p1, w0, w1 in bones:
        d, *_ = polyline_dist(X, Y, [p0, p1])
        d_bone = np.minimum(d_bone, d)
    d_hem, hem_side, hnx, hny, _, _ = polyline_dist(X, Y, hem)
    scale = max(w, h) / 80.0
    tight = np.exp(-d_bone / (5.5 * scale))
    sag = np.clip(d_bone / (14.0 * scale), 0, 1)
    # Broad light from the upper left, plus wrinkle bands fanning out from the wrist.
    gx = (X / w - 0.5) * LIGHT[0] + (Y / h - 0.5) * LIGHT[1]
    ang = np.arctan2(Y - wrist[1], X - wrist[0])
    r = np.hypot(X - wrist[0], Y - wrist[1])
    wrinkle = np.sin(ang * 22 + np.sin(r / (7 * scale)) * 0.8) * np.clip(r / (18 * scale), 0, 1)
    tone = 2.7 + 2.0 * tight - 1.2 * sag + 1.3 * gx * sheen + 0.32 * wrinkle
    # A cold sheen band across the upper membrane, like gloss on armoured fabric.
    band = np.exp(-((X * 0.55 + Y * 0.84) / max(w, h) - 0.42) ** 2 / 0.004) * sheen
    tone += 1.1 * band * (1 - sag * 0.6)
    if veins:
        vm = Image.new("L", (w, h), 0)
        d = ImageDraw.Draw(vm)
        rnd = np.random.default_rng(seed)
        for p0, p1, w0, _ in bones[1:]:
            length = math.hypot(p1[0] - p0[0], p1[1] - p0[1])
            ux, uy = (p1[0] - p0[0]) / length, (p1[1] - p0[1]) / length
            step = 7.0 * scale
            k = step * 0.7
            while k < length - step * 0.5:
                bx, by = p0[0] + ux * k, p0[1] + uy * k
                for sg in (-1, 1):
                    if rnd.random() < 0.3:
                        continue
                    ln = (4.0 + rnd.random() * 4.0) * scale
                    a = math.atan2(uy, ux) + sg * math.radians(58 + rnd.random() * 18)
                    ex, ey = bx + math.cos(a) * ln, by + math.sin(a) * ln
                    mx_, my_ = bx + math.cos(a) * ln * 0.55, by + math.sin(a) * ln * 0.55
                    d.line((bx, by, mx_, my_), fill=255, width=1)
                    d.line((mx_, my_, ex + uy * sg * 1.5, ey - ux * sg * 1.5), fill=255, width=1)
                    a2 = a + sg * math.radians(32)
                    d.line((mx_, my_, mx_ + math.cos(a2) * ln * 0.4, my_ + math.sin(a2) * ln * 0.4), fill=255, width=1)
                k += step * (0.8 + rnd.random() * 0.5)
        vein = (np.array(vm) > 0) & mask
        tone = np.where(vein, tone + 1.0, tone)
        under = _shift(vein, 1, 1) & ~vein & mask
        tone = np.where(under, tone - 0.9, tone)
    # The hem: a dark cut edge and a lit rim just inside it, so the skin reads as thick.
    edge = mask & ~(_shift(mask, 1, 0) & _shift(mask, -1, 0) & _shift(mask, 0, 1) & _shift(mask, 0, -1))
    rim = (d_hem < 2.6 * max(1.0, scale * 0.9)) & mask & ~edge
    lit_rim = (hnx * LIGHT[0] + hny * LIGHT[1]) * np.sign(hem_side + 1e-6)
    tone = np.where(rim, tone + 1.6 + 0.6 * (lit_rim > 0), tone)
    idx = np.clip(np.floor(tone + _bayer(h, w) * 0.7 + 0.5), 1, top - 1)
    idx = np.where(edge & (d_hem < 2.0), 0, idx)
    col = lut[idx.astype(int)]
    img[mask] = col[mask]
    if torn is not None:
        img[torn] = 0
    return img


def paint_bones(img, bones, joints, tips, claws=(), palette=BONE, dull=False):
    """Bones on top of the membrane: a lit ridge, a dark underside and an outline; round
    knuckles; steel claw points at the finger tips."""
    h, w = img.shape[:2]
    X, Y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    lut = _lut(palette)
    steel = _lut(STEEL)
    lift = -1 if dull else 0
    for p0, p1, w0, w1 in bones:
        d, s, nx, ny, along, length = polyline_dist(X, Y, [p0, p1])
        t = np.clip(along / max(length, 1e-6), 0, 1)
        half = (w0 + (w1 - w0) * t) / 2
        inside = d <= half + 0.5
        out = (d <= half + 1.3) & ~inside & (img[..., 3] > 0)
        img[out] = lut[0]
        lit = s * (nx * LIGHT[0] + ny * LIGHT[1])
        rel = np.clip(lit / np.maximum(half, 0.5), -1, 1)
        idx = 4.0 + 2.4 * rel + 0.8 * (1 - t) + lift
        idx = np.where(d > half - 0.4, idx - 1.6, idx)
        idx = np.clip(np.rint(idx), 1, len(palette) - 1).astype(int)
        col = lut[idx]
        img[inside] = col[inside]
    for (jx, jy), r in joints:
        d = np.hypot(X - jx, Y - jy)
        ring = (d <= r + 1.0) & (d > r)
        img[ring & (img[..., 3] > 0)] = lut[0]
        inside = d <= r
        lit = ((X - jx) * LIGHT[0] + (Y - jy) * LIGHT[1]) / max(r, 0.5)
        idx = np.clip(np.rint(5.0 + 2.5 * lit + lift), 2, len(palette) - 1).astype(int)
        img[inside] = lut[idx][inside]
    for (bx, by), (tx, ty), wd in list(tips) + list(claws):
        length = math.hypot(tx - bx, ty - by)
        d, s, nx, ny, along, _ = polyline_dist(X, Y, [(bx, by), (tx, ty)])
        t = np.clip(along / max(length, 1e-6), 0, 1)
        half = wd / 2 * (1 - t) + 0.35
        inside = (d <= half) & (along <= length + 0.3)
        lit = s * (nx * LIGHT[0] + ny * LIGHT[1]) > 0
        idx = np.where(lit, 5, 3) + np.rint(t * 1.5).astype(int) + lift
        idx = np.clip(idx, 1, len(STEEL) - 1)
        img[inside] = steel[idx][inside]
        ex, ey = int(tx), int(ty)
        if 0 <= ex < w and 0 <= ey < h:
            img[ey, ex] = steel[-1] if not dull else steel[4]


def tear_mask(w, h, mask, holes, slits, seed=1) -> np.ndarray:
    """Ragged holes and slits for the torn membrane."""
    m = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(m)
    rnd = np.random.default_rng(seed)
    for cx, cy, r in holes:
        pts = []
        for k in range(11):
            a = k / 11 * math.tau
            rr = r * (0.55 + rnd.random() * 0.65)
            pts.append((cx + math.cos(a) * rr, cy + math.sin(a) * rr * 1.2))
        d.polygon(pts, fill=255)
    for (x0, y0), (x1, y1) in slits:
        pts = [(x0, y0)]
        for k in range(1, 6):
            t = k / 6
            pts.append((x0 + (x1 - x0) * t + rnd.uniform(-1.2, 1.2), y0 + (y1 - y0) * t + rnd.uniform(-1.2, 1.2)))
        pts.append((x1, y1))
        d.line(pts, fill=255, width=2)
    return (np.array(m) > 0) & mask


# --------------------------------------------------------------------------------------
# Worn wings (outer face is 40x80 at 4x: u = 0 is the hinge / leading edge, v = 0 the
# root). In flight the leading edge is on top and v runs out to the tip; folded, the
# wing hangs root-up and the scalloped hem becomes the lower edge of a cape. The inner
# face stays clear like the vanilla elytra (the outer face shows from both sides).
# --------------------------------------------------------------------------------------

WORN_WRIST = (5.0, 28.0)
WORN_FINGERS = [  # (knuckle, tip) leading finger first
    ((3.0, 52.0), (1.4, 79.6)),
    ((11.5, 50.0), (17.5, 75.0)),
    ((18.5, 44.0), (30.5, 60.0)),
    ((19.5, 35.0), (31.0, 40.5)),
]
WORN_HEEL = (24.0, 24.0)       # the fifth spar ends here
WORN_HIP = (23.0, 11.0)        # one more point on the hem between the heel and the root
WORN_ROOT = (22.0, 0.0)


def worn_outline():
    tips = [t for _, t in WORN_FINGERS]
    hem = [tips[0]]
    for a, b, depth in ((tips[0], tips[1], 4.2), (tips[1], tips[2], 5.0), (tips[2], tips[3], 5.0),
                        (tips[3], WORN_HEEL, 4.6), (WORN_HEEL, WORN_HIP, 3.6), (WORN_HIP, WORN_ROOT, 3.0)):
        hem += scallop(a, b, depth, WORN_WRIST, n=14)
    outline = [(0.0, 0.0), (0.0, 72.0), (0.5, 76.5)] + hem + [(0.0, 0.0)]
    return outline, hem


def worn_bones():
    wx, wy = WORN_WRIST
    bones = [((2.6, 0.0), (4.0, 15.0), 4.2, 3.8), ((4.0, 15.0), (wx, wy), 3.8, 3.4),
             ((wx, wy), (wx + (WORN_HEEL[0] - wx) * 0.85, wy + (WORN_HEEL[1] - wy) * 0.85), 1.8, 1.0)]
    for (kx, ky), (tx, ty) in WORN_FINGERS:
        bones.append(((wx, wy), (kx, ky), 2.8, 2.2))
        # The bone stops short of the tip; the steel claw point finishes it.
        f = 0.82
        bones.append(((kx, ky), (kx + (tx - kx) * f, ky + (ty - ky) * f), 2.2, 1.3))
    joints = [((4.0, 15.0), 1.9), ((wx, wy), 2.4)] + [((kx, ky), 1.3) for (kx, ky), _ in WORN_FINGERS]
    tips = []
    for (kx, ky), (tx, ty) in WORN_FINGERS:
        f = 0.8
        tips.append(((kx + (tx - kx) * f, ky + (ty - ky) * f), (tx, ty), 1.8))
    claws = [((wx - 0.5, wy - 1.5), (1.2, wy - 6.5), 2.2)]   # the thumb, hooked toward the hinge
    return bones, joints, tips, claws


def paint_mount(img: np.ndarray) -> None:
    """The gunmetal clip at the wing root where the harness holds it."""
    steel = _lut(STEEL)
    for v in range(0, 7):
        for u in range(0, 7):
            if u + v > 7:
                continue
            edge = u + v >= 7
            idx = 0 if edge else 3 if (u == 0 or v == 0) else 1 + ((u + v) % 3 == 0)
            img[v, u] = steel[idx]
    for u, v in ((2, 2), (4, 1), (1, 4)):
        img[v, u] = steel[4]
        img[v + 1, u + 1] = steel[0]


def paint_wing_face(torn=False):
    outline, hem = worn_outline()
    mask = polygon_mask(FACE_W, FACE_H, outline)
    bones, joints, tips, claws = worn_bones()
    holes = None
    if torn:
        holes = tear_mask(FACE_W, FACE_H, mask, [(22, 62, 2.6), (12, 46, 2.0)], [((26, 54), (31, 61))])
    img = paint_membrane(FACE_W, FACE_H, mask, hem, bones, WORN_WRIST, MEMBRANE, torn=holes, seed=3)
    paint_bones(img, bones, joints, tips, claws)
    paint_mount(img)
    mask = img[..., 3] > 0
    return img, mask


def paint_wings() -> Image.Image:
    out = np.zeros((32 * S, 64 * S, 4), np.float32)
    outer, mask = paint_wing_face()

    def put(name, arr):
        x0, y0, x1, y1 = region(WINGS, name, S)
        out[y0:y1 + 1, x0:x1 + 1] = arr

    put("outer", outer)
    # Thin rims give the wing thickness where it is solid: the hinge edge (the arm), the
    # root and wherever the hem touches the far edge.
    thick = 2 * S
    dark = np.array([0.45, 0.45, 0.52, 1.0], np.float32)
    rim = np.zeros((FACE_H, thick, 4), np.float32)
    for v in range(FACE_H):
        if mask[v, 0]:
            rim[v, thick - 1] = outer[v, 0]
            rim[v, thick - 2] = outer[v, 1] * dark
    put("edge_inner", rim)
    far = np.zeros((FACE_H, thick, 4), np.float32)
    for v in range(FACE_H):
        if mask[v, FACE_W - 1]:
            far[v, 0] = outer[v, FACE_W - 1]
    put("edge_outer", far)
    top = np.zeros((thick, FACE_W, 4), np.float32)
    bottom = np.zeros((thick, FACE_W, 4), np.float32)
    for j in range(FACE_W):
        c = FACE_W - 1 - j
        if mask[0, c]:
            top[0, j] = outer[0, c]
            top[1, j] = outer[0, c] * dark
        if mask[FACE_H - 1, c]:
            bottom[thick - 1, j] = outer[FACE_H - 1, c]
    put("top", top)
    put("bottom", bottom)
    return _to_image(out)


# --------------------------------------------------------------------------------------
# Chest harness (humanoid layer, 4x)
# --------------------------------------------------------------------------------------

def strap(layer: np.ndarray, pts, width: float, rivets: float = 9.0, phase: float = 0.0) -> None:
    """A charcoal leather strap: stitched edges, a dark shadow and gunmetal rivets."""
    h, w = layer.shape[:2]
    X, Y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    dist, s, nx, ny, arc, _ = polyline_dist(X, Y, pts)
    inside = dist <= width / 2
    if not inside.any():
        return
    opaque = layer[..., 3] > 0
    shadow = (_shift(inside, 1, 1) | _shift(inside, 0, 1)) & ~inside & opaque
    layer[shadow, :3] = layer[shadow, :3] * 0.45
    lit = s * (nx * LIGHT[0] + ny * LIGHT[1]) > 0
    edge = np.abs(s) > width / 2 - 1.0
    lea = _lut(LEATHER)
    idx = np.where(edge, np.where(lit, 4, 0), np.where(lit, 3, 2))
    stitch = (np.abs(np.abs(s) - (width / 2 - 1.6)) < 0.5) & ((arc % 3.0) < 1.4) & ~edge
    idx = np.where(stitch, 5, idx)
    col = lea[idx]
    a = (arc + phase) % rivets
    st = _lut(STEEL)
    rivet = (np.hypot(a - rivets / 2, s) < 1.25) & ~edge
    col = np.where(rivet[..., None], np.where((s * LIGHT[1] + (a - rivets / 2) * LIGHT[0] > 0)[..., None],
                                              st[5], st[2]), col)
    layer[inside] = col[inside]


def emblem(layer: np.ndarray, x0: int, y0: int, scale: int = 1, eyes: bool = True) -> None:
    """The gunmetal bat emblem: dark outline and drop shadow, a lit top edge, a darker
    underside and ember eyes."""
    h, w = layer.shape[:2]
    mask = bat_mask(w, h, x0, y0, scale)
    halo = _grow(mask)
    drop = _shift(halo, 1, 1) & ~halo
    layer[drop & (layer[..., 3] > 0), :3] *= 0.4
    layer[halo & ~mask] = _c(STEEL[0])
    st = _lut(STEEL)
    X, Y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    cx, cy = x0 + BAT_W * scale / 2, y0 + BAT_H * scale / 2
    g = ((X - cx) * LIGHT[0] * 0.5 + (Y - cy) * LIGHT[1]) / (BAT_H * scale / 2)
    idx = np.clip(np.rint(2.6 + 1.2 * g + _bayer(h, w) * 0.6), 1, 4).astype(int)
    col = st[idx]
    lower = mask & ~_shift(mask, 0, -1)
    col = np.where(lower[..., None], st[1], col)
    layer[mask] = col[mask]
    for k in range(scale):
        top_edge = mask & ~_shift(mask, 0, k + 1)
        layer[top_edge] = st[4] if k == 0 else st[3]
    if eyes:
        c = BAT_W // 2
        for ex, ey in BAT_EYES:
            xa, ya = x0 + (c + ex) * scale, y0 + ey * scale
            layer[ya:ya + scale, xa:xa + scale] = _c(EMBER[3])
            if scale > 1:
                layer[ya, xa] = _c(EMBER[4])


def buckle(layer: np.ndarray, x0: int, y0: int, x1: int, y1: int) -> None:
    st = _lut(STEEL)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if x in (x0, x1) or y in (y0, y1):
                layer[y, x] = st[6] if (x == x0 or y == y0) else st[1]
    layer[(y0 + y1) // 2, x0 + 1:x1] = st[3]


def paint_harness() -> Image.Image:
    out = np.zeros((32 * S, 64 * S, 4), np.float32)

    def area(name):
        x0, y0, x1, y1 = region(HUMANOID, name, S)
        return (x0, y0, x1, y1), np.zeros((y1 - y0 + 1, x1 - x0 + 1, 4), np.float32)

    def put(box_, layer):
        x0, y0, x1, y1 = box_
        out[y0:y1 + 1, x0:x1 + 1] = layer

    band_y, band_w = 37.0, 5
    # Front: shoulder straps run down into the bat emblem on the chest; a belt below.
    b, lay = area("body_front")
    strap(lay, [(6.5, -4), (5.0, 6), (4.0, 10)], 4)
    strap(lay, [(25.5, -4), (27.0, 6), (28.0, 10)], 4, phase=4.0)
    strap(lay, [(-4, band_y), (36, band_y)], band_w, phase=2.0)
    strap(lay, [(16.0, 22), (16.0, band_y)], 3, phase=1.0)
    emblem(lay, 3, 10)
    buckle(lay, 13, int(band_y) - 3, 18, int(band_y) + 2)
    put(b, lay)
    b, lay = area("body_top")
    strap(lay, [(6.5, -4), (6.5, 20)], 4)
    strap(lay, [(25.5, -4), (25.5, 20)], 4, phase=4.0)
    put(b, lay)
    # Back: straps cross to the wing mount, a small bat plate where the wings attach.
    b, lay = area("body_back")
    strap(lay, [(6.5, -4), (5.0, 3), (4.0, 7)], 4, phase=2.0)
    strap(lay, [(25.5, -4), (27.0, 3), (28.0, 7)], 4, phase=6.0)
    strap(lay, [(16.0, 18), (16.0, band_y)], 4, phase=1.0)
    strap(lay, [(-4, band_y), (36, band_y)], band_w, phase=3.0)
    emblem(lay, 3, 7, eyes=False)
    put(b, lay)
    for name in ("body_right", "body_left"):
        b, lay = area(name)
        strap(lay, [(-4, band_y), (20, band_y)], band_w, phase=5.0)
        buckle(lay, 4, int(band_y) - 4, 11, int(band_y) + 3)
        put(b, lay)
    return _to_image(out)


# --------------------------------------------------------------------------------------
# Item model: a spread pair of bat wings (right wing built, then mirrored about x = 8).
# Model units, y up. The membrane is split into one panel per finger gap; each panel is
# tilted back on its leading finger so the wing folds like a fan.
# --------------------------------------------------------------------------------------

SH = (10.2, 16.6)            # shoulder
EL = (13.4, 18.4)            # elbow
WR = (17.2, 26.6)            # wrist
THUMB = [(17.2, 26.6), (16.8, 28.2), (15.9, 29.0), (15.2, 28.5)]
FINGERS = [  # (knuckle, tip) leading finger first
    ((21.6, 27.8), (27.4, 28.2)),
    ((21.2, 22.2), (26.0, 16.2)),
    ((19.8, 19.4), (21.8, 10.2)),
    ((17.9, 18.8), (16.4, 7.4)),
]
HEEL = (11.0, 8.6)           # the hem meets the body here
Z0 = 8.0                     # membrane plane
TILT = [13.0, 13.0, 12.0, 9.0]        # how far each panel leans back on its hinge line
# The item canvas: a 24-unit square at 128 px, covering x 8..32 and y 4..28.
CX0, CY1, CSPAN, CPX = 8.0, 29.0, 24.0, 128


def to_px(p):
    k = CPX / CSPAN
    return ((p[0] - CX0) * k, (CY1 - p[1]) * k)


def panel_outlines():
    """Each panel's outline in model units, plus its hem (the free scalloped edge)."""
    tips = [t for _, t in FINGERS]
    knuckles = [k for k, _ in FINGERS]
    depths = [2.0, 2.4, 2.4, 2.6]
    panels = []
    for i in range(3):
        hem = [tips[i]] + scallop(tips[i], tips[i + 1], depths[i], WR, n=10)
        outline = [WR, knuckles[i]] + hem + [knuckles[i + 1]]
        panels.append((outline, hem))
    # The last panel runs from the fifth finger round to the body and up the arm.
    hem = [tips[3]] + scallop(tips[3], HEEL, depths[3], WR, n=12)
    outline = [WR, knuckles[3]] + hem + [(SH[0] - 0.2, SH[1] - 1.0), SH, EL]
    panels.append((outline, hem))
    return panels


def item_bones_px():
    """Bones in canvas pixels for painting the panels (the 3D bones sit on top)."""
    pts = [((SH, EL), 1.0), ((EL, WR), 0.9)]
    for k, t in FINGERS:
        pts.append(((WR, k), 0.7))
        pts.append(((k, t), 0.5))
    k = CPX / CSPAN
    return [(to_px(a), to_px(b), wd * k, wd * k * 0.7) for (a, b), wd in pts]


def paint_panel(i, torn=False, palette=MEMBRANE):
    outline, hem = panel_outlines()[i]
    mask = polygon_mask(CPX, CPX, [to_px(p) for p in outline])
    holes = None
    if torn:
        tears = {0: ([(98, 34, 3.4)], [((106, 46), (112, 54))]),
                 1: ([(86, 62, 4.2)], [((96, 74), (102, 84))]),
                 2: ([(62, 74, 3.0), (70, 92, 2.4)], []),
                 3: ([(38, 74, 4.6)], [((24, 88), (30, 98))])}[i]
        holes = tear_mask(CPX, CPX, mask, *tears, seed=10 + i)
    img = paint_membrane(CPX, CPX, mask, [to_px(p) for p in hem], item_bones_px(), to_px(WR), palette,
                         torn=holes, sheen=0.8, seed=20 + i)
    return _to_image(img)


def _uv_box(p_lo, p_hi):
    """The canvas uv (0..16) of a model-space rectangle."""
    k = 16 / CSPAN
    return [(p_lo[0] - CX0) * k, (CY1 - p_hi[1]) * k, (p_hi[0] - CX0) * k, (CY1 - p_lo[1]) * k]


def turn_about(elements, angle, a, b):
    """Rotate elements by `angle` degrees about the line through points a and b (in the
    z = Z0 plane)."""
    dx, dy = b[0] - a[0], b[1] - a[1]
    n = math.hypot(dx, dy)
    ux, uy = dx / n, dy / n
    yaw = math.degrees(math.atan2(uy, ux))
    origin = (a[0], a[1], Z0)
    turn(elements, -yaw, "z", origin)        # line onto +x
    turn(elements, angle, "x", origin)       # lean back about it
    turn(elements, yaw, "z", origin)
    return elements


def panel_axis(i):
    """The hinge line each panel leans back on: its leading finger, or the arm for the
    body panel."""
    return (WR, FINGERS[i][1]) if i < 3 else (SH, WR)


def membrane_panels(broken=False):
    solid = []
    for i, (outline, hem) in enumerate(panel_outlines()):
        xs = [p[0] for p in outline]
        ys = [p[1] for p in outline]
        lo, hi = (min(xs), min(ys)), (max(xs), max(ys))
        tex = f"{'torn' if broken else 'membrane'}_{i}"
        uv = _uv_box(lo, hi)
        back = [uv[2], uv[1], uv[0], uv[3]]
        part = box((lo[0], lo[1], Z0 - 0.1), (hi[0], hi[1], Z0 + 0.1), tex,
                   faces={"south": (tex, uv), "north": (tex, back)}, skip=("up", "down", "east", "west"))
        group = [part]
        # A thick hem: short dark rim bars following the scallops, on the same panel.
        for a, b in zip(hem, hem[1:]):
            group.append(bar((*a, Z0), (*b, Z0), 0.32, 0.42, "hem"))
        turn_about(group, TILT[i], *panel_axis(i))
        solid += group
    return solid


def bone_parts(broken=False):
    solid, steel = [], []
    zb = Z0 + 0.15
    solid.append(bar((*SH, zb), (*EL, zb), 1.5, 1.4, "bone"))
    solid.append(bar((*EL, zb), (*WR, zb), 1.3, 1.25, "bone"))
    # Leading-edge skin in front of the arm (the bat's propatagium) as a thin dark strip.
    solid.append(bar((SH[0] + 0.2, SH[1] + 0.7, zb - 0.1), (WR[0] - 0.4, WR[1] + 0.4, zb - 0.1), 0.7, 0.3, "hem"))
    for n, ((kx, ky), (tx, ty)) in enumerate(FINGERS):
        f1 = bar((*WR, zb), (kx, ky, zb), 0.85, 0.8, "bone")
        snap = broken and n == 1
        f = 0.55 if snap else 0.8
        ex, ey = kx + (tx - kx) * f, ky + (ty - ky) * f
        f2 = bar((kx, ky, zb), (ex, ey, zb), 0.62, 0.6, "bone")
        group = [f1, f2]
        if not snap:
            # spiked steel tip: two tapering segments
            mx, my = ex + (tx - ex) * 0.55, ey + (ty - ey) * 0.55
            group.append(bar((ex, ey, zb), (mx, my, zb), 0.5, 0.5, "steel"))
            group.append(bar((mx, my, zb), (tx, ty, zb), 0.26, 0.26, "steel"))
        else:
            group.append(bar((ex, ey, zb), (ex + (tx - kx) * 0.06, ey + (ty - ky) * 0.06, zb), 0.7, 0.7,
                             "bone_end"))
        # knuckle
        group.append(turn(box((kx - 0.55, ky - 0.55, zb - 0.5), (kx + 0.55, ky + 0.55, zb + 0.5), "knuckle"),
                          45, "z", (kx, ky, zb)))
        # Each finger sits on the leading edge of its own panel; the last one rides the
        # trailing edge of the body panel, which leans back on the arm.
        if n == 3:
            turn_about(group, TILT[3], *panel_axis(3))
        solid += group
    # Joints at the shoulder, elbow and wrist.
    for (jx, jy), r in ((SH, 1.1), (EL, 0.95), (WR, 1.15)):
        solid.append(turn(box((jx - r, jy - r, zb - r * 0.9), (jx + r, jy + r, zb + r * 0.9), "knuckle"),
                          45, "z", (jx, jy, zb)))
    # Thumb: a short bone and a hooked steel claw.
    solid.append(bar((*THUMB[0], zb + 0.3), (*THUMB[1], zb + 0.3), 0.6, 0.6, "bone"))
    solid.append(bar((*THUMB[1], zb + 0.3), (*THUMB[2], zb + 0.3), 0.45, 0.45, "steel"))
    solid.append(bar((*THUMB[2], zb + 0.3), (*THUMB[3], zb + 0.3), 0.25, 0.25, "steel"))
    # Little steel spur on the elbow, pointing back.
    solid.append(bar((*EL, zb), (EL[0] + 0.4, EL[1] - 1.4, zb), 0.4, 0.4, "steel"))
    return solid


EMB = (8.0, 18.0, Z0 + 1.6)      # emblem centre on the yoke


def emblem_texture(dull=False) -> Image.Image:
    img = np.zeros((64, 64, 4), np.float32)
    emblem(img, 5, 19, scale=2, eyes=False)
    if dull:
        img[..., :3] *= 0.72
    return _to_image(img)


def centre(broken=False):
    """The gunmetal yoke: a spine plate, shoulder straps and a layered bat emblem."""
    solid, decal = [], []
    x, y, z = EMB
    solid.append(box((7.1, 12.6, Z0 - 0.4), (8.9, 18.6, Z0 + 0.8), "plate"))           # spine plate
    solid.append(turn(box((7.3, 11.4, Z0 - 0.2), (8.7, 12.8, Z0 + 0.6), "plate"), 45, "z", (8.0, 12.6, Z0)))
    for sg in (-1, 1):
        sx = 8 + sg * (SH[0] - 8)
        solid.append(bar((8 + sg * 0.9, 18.4, Z0 + 0.5), (sx, SH[1], Z0 + 0.4), 1.0, 0.8, "strap"))
        solid.append(bar((8 + sg * 0.8, 14.0, Z0 + 0.5), (8 + sg * 2.9, 11.2, Z0 + 0.3), 0.8, 0.6, "strap"))
    # Emblem: three stacked cut-out plates (dark core, lit face, polished top) read as a
    # thick cast badge from the side.
    w, h = 7.6, 7.6
    tex = "emblem_dull" if broken else "emblem"
    for dz, t in ((-0.35, "emblem_back"), (-0.15, "emblem_back"), (0.05, tex)):
        decal.append(box((x - w / 2, y - h / 2, z + dz), (x + w / 2, y + h / 2, z + dz + 0.1), t,
                         faces={"south": (t, [0, 0, 16, 16]), "north": (t, [16, 0, 0, 16])},
                         skip=("up", "down", "east", "west")))
    solid.append(box((x - 1.2, y - 1.6, Z0 + 0.8), (x + 1.2, y + 1.0, z - 0.3), "plate"))   # emblem stand
    if not broken:
        for sg in (-1, 1):
            k = w / 64                      # emblem texture pixels -> model units
            ex = x + sg * 2 * k                # eye cells: columns 12 and 14, row 3, at 2x from (5, 19)
            ey = y + (32 - (19 + 3 * 2 + 1)) * k
            decal.append(box((ex - 0.16, ey - 0.12, z + 0.16), (ex + 0.16, ey + 0.12, z + 0.26), "ember", glow=12,
                             uv="full", skip=("north",)))
    return solid, decal


def _raw(broken=False) -> list[dict]:
    wing = membrane_panels(broken) + bone_parts(broken)
    turn(wing, 18, "y", (10.0, 16.0, Z0))      # the wings sweep back from the yoke in a shallow V

    def mirrored(parts):
        return [mirror(p, "x", 8)[0] for p in parts]

    c_solid, c_decal = centre(broken)
    return wing + mirrored(wing) + c_solid + c_decal


def offset() -> tuple[float, float, float]:
    lo, hi = bounds(_raw())
    return tuple(round(8 - (lo[i] + hi[i]) / 2, 3) for i in range(3))


def build(broken=False, shift=None) -> list[dict]:
    parts = _raw(broken)
    move(parts, *(shift or offset()))
    return parts


def transforms(parts, shift) -> dict:
    grip = (EMB[0] + shift[0], EMB[1] + shift[1], EMB[2] + shift[2])
    d = display("elytra", parts, grip=grip, size=0.72, gui_rotation=(12, 0, 0), gui_span=15.8)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.45, 0.15, 1)}, (8, 8, 8), (0.62, -0.2, -1.0), 0.33,
                                       pose=None)
    return d


def models() -> dict:
    shift = offset()
    main, broken = build(shift=shift), build(broken=True, shift=shift)
    return {"main": model(main, transforms(main, shift)), "broken": model(broken, transforms(broken, shift))}


# --------------------------------------------------------------------------------------
# Small textures
# --------------------------------------------------------------------------------------

def paint_bone_tex(dull=False) -> Image.Image:
    """A charcoal bone: lit ridge on the left, dark underside, faint growth rings."""
    img = np.zeros((32, 32, 4), np.float32)
    lut = _lut(BONE)
    cols = [7, 6, 5, 4, 4, 3, 2, 2]
    for y in range(32):
        for x in range(32):
            i = cols[min(x, 7)] - (1 if dull else 0)
            if y % 6 == 0 and 1 <= x <= 5:
                i -= 1
            img[y, x] = lut[max(1, i)]
    return _to_image(img)


def paint_steel(dull=False) -> Image.Image:
    img = np.zeros((32, 32, 4), np.float32)
    st = _lut(STEEL)
    for y in range(32):
        for x in range(32):
            i = 7 if x == 0 else 6 if x == 1 else 4 if x < 3 else 2
            if dull:
                i = max(1, i - 2)
            img[y, x] = st[i]
    return _to_image(img)


def paint_plate() -> Image.Image:
    """Gunmetal plate with a bevel and rivets."""
    img = np.zeros((32, 32, 4), np.float32)
    st = _lut(STEEL)
    for y in range(32):
        for x in range(32):
            i = 2
            if x == 0 or y == 0:
                i = 5
            elif (x + y) % 7 == 0:
                i = 3
            img[y, x] = st[i]
    for (x, y) in ((4, 4), (4, 12), (4, 20), (12, 4)):
        img[y, x] = st[7]
        img[y + 1, x + 1] = st[0]
    return _to_image(img)


def paint_knuckle() -> Image.Image:
    img = np.zeros((16, 16, 4), np.float32)
    lut = _lut(BONE)
    for y in range(16):
        for x in range(16):
            i = 7 if (x < 1 or y < 1) else 5 if x + y < 3 else 3
            img[y, x] = lut[i]
    img[1, 1] = lut[8]
    return _to_image(img)


def paint_hem() -> Image.Image:
    img = np.zeros((16, 16, 4), np.float32)
    lut = _lut(MEMBRANE)
    for y in range(16):
        for x in range(16):
            img[y, x] = lut[7 if x == 0 else 4 if x < 2 else 2]
    return _to_image(img)


def paint_strap_tex() -> Image.Image:
    img = np.zeros((32, 32, 4), np.float32)
    lea = _lut(LEATHER)
    st = _lut(STEEL)
    for y in range(32):
        for x in range(32):
            i = 4 if x < 1 else 1 if x >= 3 else 2
            img[y, x] = lea[i]
            if x == 1 and y % 3 == 0:
                img[y, x] = lea[5]
        if y % 8 == 4:
            img[y, 1] = st[6]
    return _to_image(img)


def paint_ember() -> Image.Image:
    img = np.zeros((16, 16, 4), np.float32)
    for y in range(16):
        for x in range(16):
            img[y, x] = _c(EMBER[4] if (x < 6 and y < 6) else EMBER[3] if x + y < 18 else EMBER[2])
    return _to_image(img)


def paint_emblem_back() -> Image.Image:
    img = np.zeros((64, 64, 4), np.float32)
    img[_grow(bat_mask(64, 64, 5, 19, 2))] = _c(STEEL[0])
    return _to_image(img)


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def textures() -> None:
    save_layer(paint_wings(), "wings")
    save_layer(paint_harness(), "humanoid")
    for i in range(4):
        save(paint_panel(i), f"membrane_{i}")
        save(paint_panel(i, torn=True, palette=TORN), f"torn_{i}")
    save(paint_bone_tex(), "bone")
    save(paint_bone_tex(dull=True), "bone_end")
    save(paint_steel(), "steel")
    save(paint_plate(), "plate")
    save(paint_knuckle(), "knuckle")
    save(paint_hem(), "hem")
    save(paint_strap_tex(), "strap")
    save(paint_ember(), "ember")
    save(emblem_texture(), "emblem")
    save(emblem_texture(dull=True), "emblem_dull")
    save(paint_emblem_back(), "emblem_back")
