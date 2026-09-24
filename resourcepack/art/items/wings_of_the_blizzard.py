"""Wings of the Blizzard: Winter Limited Edition elytra.

Worn: two wings of frozen feathers. Deep navy quills run into glacial-blue vanes with an
aurora teal-to-violet sheen and crystalline frost-glass tips, under staggered frosted
coverts, a silver leading edge carved with frost runes and a snowflake sigil. Worn layers
render cutout in game, so the glass is painted as opaque glassy ice. A silver
frost-filigree chest harness with a snowflake clasp shows while the wings are worn.

Item: a sculpted pair of folded frost wings on a silver yoke: arched ice-bone arms with
glowing runes, wrist snowflakes and drifts of snow, layered coverts and fanned flight
feathers with translucent glowing frost-glass tips, and an aurora gem in a snowflake clasp.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image, ImageDraw

from art.kit import (HUMANOID, WINGS, bar, bounds, box, display, mirror, model, move, place, ramp, region, rgba,
                     save, save_layer, turn)

ID = "wings_of_the_blizzard"
NAME = "Wings of the Blizzard"
KIND = "elytra"

# --------------------------------------------------------------------------------------
# Palette: every material has its own hue-shifted ramp (shadows drift to violet-navy,
# highlights to cyan-white).
# --------------------------------------------------------------------------------------

NAVY = ramp("#1d2f63", 6, 0.8, 0.06)
TEAL = ramp("#25c9bd", 6, 0.75, 0.03)
VIOLET = ramp("#8559e8", 6, 0.75, 0.03)
SILVER = ["#262e4b", "#475374", "#73819d", "#a3b0c8", "#cdd7e6", "#f4f8fe"]
FEATHER = ["#0e1840", "#192f6e", "#28519c", "#3a74c2", "#5896de", "#83bcef", "#b6dbf9", "#e6f5ff"]
UNDER = ["#3f5f9e", "#5b7fbc", "#7ea2d7", "#a1c3ea", "#c1dbf5", "#dbebfb", "#eef6ff", "#fbfdff"]
BROKEN = ["#12182a", "#1f283f", "#303d5a", "#445575", "#5d6f90", "#7d8ea9", "#9eacc2", "#c3ccda"]
COVERT = ["#2a4c92", "#4675c0", "#72a2e0", "#a0caf4", "#cde7fc", "#f1faff"]
COVERT_UNDER = ["#5d7fb8", "#7c9dd0", "#9dbde6", "#bfd8f3", "#dcecfb", "#f6fbff"]
COVERT_BROKEN = ["#222c44", "#34435f", "#4b5c7c", "#667997", "#8595b0", "#a9b5c9"]
GLASS = ["#1a6594", "#3591c2", "#62bde4", "#97dbf5", "#c8f0ff", "#ffffff"]
DEAD_ICE = ["#18243c", "#26364f", "#374b68", "#4d6383", "#6a7f9d", "#8d9fb8"]   # frost gone dull
SNOW = ["#8db0d6", "#b7d2ec", "#d9e8f7", "#eff6fd", "#ffffff"]
RUNE = ["#2fc8f0", "#8ff3ff", "#e9ffff"]
WHITE = "#ffffff"

S = 4                      # worn layers are painted at 4x (256x128)
FACE_W, FACE_H = 10 * S, 20 * S
LIGHT = (-0.55, -0.83)     # painted light comes from the upper left (texture space, v down)


def _c(colour) -> np.ndarray:
    return np.array(rgba(colour), dtype=np.float32)


def _lut(colours) -> np.ndarray:
    return np.stack([_c(c) for c in colours])


def aurora(h: float) -> np.ndarray:
    """Aurora sheen: teal (0) through ice blue to violet (1)."""
    a, b, c = _c(TEAL[4]), _c("#6fb0ff"), _c(VIOLET[4])
    h = min(1.0, max(0.0, h))
    return a + (b - a) * (h * 2) if h < 0.5 else b + (c - b) * ((h - 0.5) * 2)


def _shift(mask: np.ndarray, dx: int, dy: int) -> np.ndarray:
    """The mask moved by (dx, dy) pixels."""
    out = np.zeros_like(mask)
    h, w = mask.shape
    out[max(0, dy):h + min(0, dy), max(0, dx):w + min(0, dx)] = \
        mask[max(0, -dy):h - max(0, dy), max(0, -dx):w - max(0, dx)]
    return out


def _grow(mask: np.ndarray) -> np.ndarray:
    return mask | _shift(mask, 1, 0) | _shift(mask, -1, 0) | _shift(mask, 0, 1) | _shift(mask, 0, -1)


def _to_image(arr: np.ndarray) -> Image.Image:
    return Image.fromarray(np.clip(np.rint(arr), 0, 255).astype(np.uint8), "RGBA")


# --------------------------------------------------------------------------------------
# Feather painter (shared by the worn wings and the item's feather atlas)
# --------------------------------------------------------------------------------------

def _profile(t: np.ndarray, kind: str) -> np.ndarray:
    """Half-width (0..1) along a feather, t = 0 at the quill, 1 at the tip."""
    if kind == "flight":      # narrow quill, full vane, long pointed tip
        rise = 0.6 + 0.4 * np.clip(t / 0.14, 0, 1)
        fall = np.clip((1 - t) / 0.38, 0, 1) ** 1.15
        return np.minimum(rise, fall)
    if kind == "covert":      # short feather with a rounded end
        k = np.clip((t - 0.45) / 0.55, 0, 1)
        return np.minimum(0.7 + 0.3 * np.clip(t / 0.15, 0, 1), np.sqrt(np.clip(1 - k * k, 0, 1)))
    if kind == "slab_covert":  # item covert: full width with a rounded end
        k = np.clip((t - 0.6) / 0.4, 0, 1)
        return np.sqrt(np.clip(1 - k * k, 0, 1))
    return np.ones_like(t)    # "slab": an item feather body, the glass tip is geometry


def paint_plumes(w: int, h: int, plumes: list[dict], feather=FEATHER, covert=COVERT, sheen=0.36, cracks=False,
                 ice=GLASS):
    """Paint feathers back to front; each casts a soft navy shadow onto what is already
    there. Returns (float RGBA array, coverage mask)."""
    img = np.zeros((h, w, 4), np.float32)
    owner = np.full((h, w), -1, np.int16)
    X, Y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    lut, clut, glut = _lut(feather), _lut(covert), _lut(ice)
    navy = _c(NAVY[1])
    for k, p in enumerate(plumes):
        (bx, by), (tx, ty) = p["base"], p["tip"]
        dx, dy = tx - bx, ty - by
        length = math.hypot(dx, dy)
        ax, ay = dx / length, dy / length
        nx, ny = -ay, ax
        qx, qy = X - bx, Y - by
        t = (qx * ax + qy * ay) / length
        s = qx * nx + qy * ny
        kind = p.get("kind", "flight")
        hw = _profile(t, kind) * p["w"] / 2
        inside = (t >= 0) & (t <= 1) & (np.abs(s) <= hw)
        if not inside.any():
            continue
        if (owner >= 0).any() and p.get("shadow", True):
            cast = (_shift(inside, 1, 1) | _shift(inside, 1, 2) | _shift(inside, 2, 2)) & ~inside & (owner >= 0)
            k_sh = 0.24 if p.get("soft") else 0.38
            img[cast, :3] = img[cast, :3] * (1 - k_sh) + navy[:3] * k_sh
        lit = (s * (nx * LIGHT[0] + ny * LIGHT[1])) > 0
        edge = np.abs(s) > hw - 1.0
        tone = p.get("tone", 0.0)
        rach = np.abs(s) < 0.5
        along = t * length
        barb = ((along - np.abs(s) * 1.1) % 3.0) < 1.0
        if kind in ("covert", "slab_covert"):
            idx = 1.2 + 2.6 * np.clip(t / 0.75, 0, 1) + tone + np.where(lit, 0.8, -0.3)
            idx = idx + np.where(barb & ~lit & (t > 0.2), -0.6, 0.0)
            rim = edge & (t > 0.35)
            idx = np.where(rim & ~lit, 1.0 if p.get("soft") else 0.2, np.where(rim, idx + 0.7, idx))
            idx = np.where(rach & (t > 0.1) & (t < 0.72), idx + 1.0, idx)
            col = clut[np.clip(np.rint(idx), 0, len(covert) - 1).astype(int)].copy()
        else:
            glass = p.get("glass", 0.0)
            g0 = 1.0 - glass
            tn = np.clip(t / g0, 0, 1)
            idx = p.get("lo", 0.9) + p.get("span", 4.4) * tn + tone + np.where(lit, 0.9, -0.4)
            idx = idx + np.where(barb & (tn > 0.15), np.where(lit, 0.6, -0.9), 0.0)
            idx = np.where(edge, idx + np.where(lit, 1.0, -1.9), idx)
            col = lut[np.clip(np.rint(idx), 0, len(feather) - 1).astype(int)].copy()
            aur = aurora(p.get("hue", 0.5))
            glow = lit & ~edge & (tn > 0.25) & (tn < 0.96)
            col[glow, :3] = col[glow, :3] * (1 - sheen) + aur[:3] * sheen
            quill = rach & (t > 0.02) & (t < g0)
            col[quill, :3] = _c(feather[-1])[:3] * 0.7 + aur[:3] * 0.3
            if kind == "slab":        # the last stretch of an item feather is already freezing
                zig = 0.8 + 0.035 * (np.floor(np.abs(s)) % 2)
                fz = t > zig
                gi = np.where(lit, 4, 3)
                gi = np.where(edge, np.where(lit, 5, 2), gi)
                gi = np.where(rach, 5, gi)
                line = fz & (t < zig + 1.2 / length)
                gi = np.where(line, 2, gi)
                spark = fz & lit & ~edge & ~rach & ((np.floor(X) * 7 + np.floor(Y) * 5) % 17 == 0)
                gi = np.where(spark, 5, gi)
                col[fz] = glut[gi][fz]
            if glass > 0:
                gz = t >= g0
                serr = (np.floor(np.abs(s) / 1.5) % 2) * 1.3
                gi = np.where(lit, 4, 2)
                gi = np.where(edge, np.where(lit, 5, 1), gi)
                gi = np.where(rach, 5, gi)
                gi = np.where(gz & (t < g0 + (1.3 + serr) / length), 1, gi)
                col[gz] = glut[gi][gz]
        if cracks:                    # one V-shaped fracture across the feather, with a short branch
            t0 = (0.42 + 0.14 * math.sin(k * 2.3)) * length
            crack = np.abs(along - t0 - 0.8 * np.abs(s)) < 0.6
            branch = (np.abs(along - t0 - 2.5 + 0.9 * s) < 0.55) & (s > 0.5) & (s < 2.6)
            col[crack | branch, :3] = _c(NAVY[1])[:3]
            lip = _shift(crack, 0, 1) & ~crack & ~branch
            col[lip, :3] = np.minimum(col[lip, :3] + 40, 255)
        img[inside] = col[inside]
        owner[inside] = k
    return img, owner >= 0


# --------------------------------------------------------------------------------------
# Worn wings (outer face is 40x80 at 4x: u = 0 is the hinge / leading edge, v = 0 the root)
# When the wings hang, the spine of the back crosses the face along u ~ 19 + 0.24 v, so
# the silhouette follows that line and the two wings meet without overlapping. Like the
# vanilla elytra the inner face stays clear: the outer face shows from both sides.
# --------------------------------------------------------------------------------------

PRIMARIES = [  # (base, tip, width, aurora hue) innermost first; outer ones lie on top
    ((17.0, 40.0), (38.3, 79.6), 9.0, 1.0),
    ((14.0, 42.0), (32.8, 78.8), 9.0, 0.9),
    ((11.0, 44.0), (26.8, 77.2), 9.0, 0.8),
    ((8.0, 46.0), (20.8, 74.8), 9.0, 0.72),
    ((5.0, 48.0), (14.6, 71.4), 8.6, 0.64),
    ((2.4, 50.0), (8.6, 66.8), 8.0, 0.56),
]
SECONDARIES = [  # lower ones first so each upper feather overlaps the one below
    ((15.0, 34.0), (35.8, 65.2), 9.0, 0.5),
    ((15.0, 28.0), (34.0, 55.4), 9.0, 0.4),
    ((15.0, 22.0), (32.0, 45.6), 9.0, 0.3),
    ((15.0, 16.0), (30.0, 35.6), 8.6, 0.2),
    ((14.0, 10.0), (27.0, 25.6), 8.0, 0.1),
    ((13.0, 4.0), (23.6, 15.6), 7.6, 0.0),
]
COVERT_DIR = (0.5, 0.866)      # coverts flow the same way as the flight feathers


def wing_plumes() -> list[dict]:
    out = []
    for base, tip, w, hue in PRIMARIES:
        out.append(dict(base=base, tip=tip, w=w, hue=hue, glass=0.27, lo=0.7, span=4.6))
    for base, tip, w, hue in SECONDARIES:
        out.append(dict(base=base, tip=tip, w=w, hue=hue, glass=0.24, lo=0.9, span=4.4))
    dx, dy = COVERT_DIR
    for i in reversed(range(7)):        # greater coverts over the secondaries' quills, lowest first
        bu, bv = 7.0 + 0.35 * i, 1.0 + 5.6 * i
        out.append(dict(base=(bu, bv), tip=(bu + 13 * dx, bv + 13 * dy), w=8.2, kind="covert",
                        tone=0.05 - 0.04 * i + (-0.3 if i % 2 else 0.1), soft=True))
    for i in reversed(range(7)):        # median coverts along the leading edge
        bu, bv = 1.8, 0.8 + 5.6 * i
        out.append(dict(base=(bu, bv), tip=(bu + 10.5 * dx, bv + 10.5 * dy), w=7.4, kind="covert",
                        tone=0.55 if i % 2 else 0.3, soft=True))
    return out


def ridge_width(v: int) -> int:
    """Leading-edge ridge width in pixels (it tapers out by v = 58)."""
    if v > 58:
        return 0
    wv = 4 if v < 30 else 3
    if v > 53:
        wv = max(1, wv - (v - 53) // 2)
    return wv


def paint_ridge(img: np.ndarray, mask: np.ndarray) -> None:
    """The frost-silver leading edge with glowing-teal carved runes."""
    for v in range(FACE_H):
        wv = ridge_width(v)
        for u in range(wv):
            c = SILVER[5] if u == 0 else SILVER[1] if u == wv - 1 else SILVER[4] if u == 1 else SILVER[3]
            img[v, u] = _c(c)
            mask[v, u] = True
        if wv >= 3 and 5 < v < 52:                   # runes: a short tick every 7 rows
            m = v % 7
            if m in (2, 3, 4):
                img[v, 1] = _c(TEAL[5] if m == 3 else TEAL[4])
            if m == 3 and wv >= 4:
                img[v, 2] = _c(TEAL[4])
    for v in range(4, 56, 9):                        # snow caught on the edge
        for du, dv in ((0, 0), (0, 1), (1, 0)):
            if mask[v + dv, du]:
                img[v + dv, du] = _c(WHITE)


def paint_mount(img: np.ndarray, mask: np.ndarray) -> None:
    """The silver clip at the wing root where the harness holds it."""
    for v in range(0, 7):
        for u in range(0, 8):
            if u + v > 9:
                continue
            edge = u + v >= 8 or v == 6
            c = SILVER[1] if edge else SILVER[5] if (u == 0 or v == 0) else SILVER[3]
            img[v, u] = _c(c)
            mask[v, u] = True
    for u, v, c in ((2, 2, TEAL[5]), (3, 2, TEAL[4]), (2, 3, TEAL[4]), (3, 3, VIOLET[3])):
        img[v, u] = _c(c)


def snowflake_mask(size, cx, cy, r, width=1, branches=((0.52, 0.4), (0.8, 0.24)), rot=90.0) -> np.ndarray:
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)
    for k in range(6):
        a = math.radians(rot + 60 * k)
        d.line((cx, cy, cx + r * math.cos(a), cy - r * math.sin(a)), fill=255, width=width)
        for f, bl in branches:
            bx, by = cx + r * f * math.cos(a), cy - r * f * math.sin(a)
            for sg in (-1, 1):
                b = a + sg * math.radians(55)
                d.line((bx, by, bx + r * bl * math.cos(b), by - r * bl * math.sin(b)), fill=255, width=1)
    return np.array(m) > 0


def paint_sigil(img: np.ndarray, mask: np.ndarray, cx: float, cy: float, r: float) -> None:
    """A frost sigil: pale ice arms keyed in with a deep-blue groove, aurora core."""
    flake = snowflake_mask((FACE_W, FACE_H), cx, cy, r) & mask
    groove = _grow(flake) & ~flake & mask
    img[groove, :3] = img[groove, :3] * 0.35 + _c(FEATHER[1])[:3] * 0.65
    img[flake] = _c(GLASS[4])
    tips = snowflake_mask((FACE_W, FACE_H), cx, cy, r, branches=()) & ~snowflake_mask(
        (FACE_W, FACE_H), cx, cy, r * 0.62, branches=()) & mask
    img[tips] = _c(WHITE)
    X, Y = np.meshgrid(np.arange(FACE_W) + 0.5, np.arange(FACE_H) + 0.5)
    d = np.abs(X - cx) + np.abs(Y - cy)
    img[(d <= 2.2) & mask] = _c(FEATHER[1])
    img[(d <= 1.6) & mask] = _c(TEAL[4])
    img[(d <= 1.6) & (X > cx) & (Y > cy) & mask] = _c(VIOLET[4])
    img[int(cy) - 1, int(cx) - 1] = _c(WHITE)


def paint_flakes(img: np.ndarray, mask: np.ndarray, spots) -> None:
    """Tiny resting snowflakes: a white plus with pale diagonals."""
    for u, v in spots:
        for du, dv, c in ((0, 0, WHITE), (1, 0, SNOW[3]), (-1, 0, SNOW[3]), (0, 1, SNOW[3]), (0, -1, SNOW[3]),
                          (1, 1, GLASS[3]), (-1, -1, GLASS[3]), (1, -1, GLASS[3]), (-1, 1, GLASS[3])):
            if 0 <= u + du < FACE_W and 0 <= v + dv < FACE_H and mask[v + dv, u + du]:
                img[v + dv, u + du] = _c(c)


def paint_wing_face():
    img, mask = paint_plumes(FACE_W, FACE_H, wing_plumes())
    paint_ridge(img, mask)
    paint_mount(img, mask)
    paint_sigil(img, mask, 19.5, 33.5, 6.5)
    paint_flakes(img, mask, ((28, 52), (21, 65), (32, 42), (14, 58), (25, 24)))
    return img, mask


def paint_wings() -> Image.Image:
    out = np.zeros((32 * S, 64 * S, 4), np.float32)
    outer, mask = paint_wing_face()

    def put(name, arr):
        x0, y0, x1, y1 = region(WINGS, name, S)
        out[y0:y1 + 1, x0:x1 + 1] = arr

    put("outer", outer)
    # Thin rims give the membrane a little thickness where it is solid: the hinge edge
    # (the silver ridge), the root and the frost-glass tips, on the outer side only.
    thick = 2 * S
    rim = np.zeros((FACE_H, thick, 4), np.float32)      # east face: its right column is the outer side
    for v in range(FACE_H):
        if mask[v, 0]:
            rim[v, thick - 1] = outer[v, 0]
            rim[v, thick - 2] = outer[v, 0] * np.array([0.78, 0.8, 0.9, 1.0], np.float32)
    put("edge_inner", rim)
    far = np.zeros((FACE_H, thick, 4), np.float32)
    for v in range(FACE_H):
        if mask[v, FACE_W - 1]:
            far[v, 0] = outer[v, FACE_W - 1]
    put("edge_outer", far)
    top = np.zeros((thick, FACE_W, 4), np.float32)
    bottom = np.zeros((thick, FACE_W, 4), np.float32)
    for j in range(FACE_W):
        c = FACE_W - 1 - j                    # top/bottom run from the far edge to the hinge
        if mask[0, c]:
            top[0, j] = outer[0, c]
            top[1, j] = outer[0, c] * np.array([0.78, 0.8, 0.9, 1.0], np.float32)
        if mask[FACE_H - 1, c]:
            bottom[thick - 1, j] = outer[FACE_H - 1, c]
    put("top", top)
    put("bottom", bottom)
    return _to_image(out)


# --------------------------------------------------------------------------------------
# Chest harness (humanoid layer, 4x)
# --------------------------------------------------------------------------------------

def _poly_field(X, Y, pts):
    """Distance to a polyline, signed side offset, arc length and segment normal per pixel."""
    best = np.full(X.shape, np.inf)
    side = np.zeros(X.shape)
    arc = np.zeros(X.shape)
    nxs = np.zeros(X.shape)
    nys = np.zeros(X.shape)
    acc = 0.0
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        dx, dy = x1 - x0, y1 - y0
        length = math.hypot(dx, dy)
        ux, uy = dx / length, dy / length
        t = np.clip((X - x0) * ux + (Y - y0) * uy, 0, length)
        dist = np.hypot(X - (x0 + ux * t), Y - (y0 + uy * t))
        s = (X - x0) * (-uy) + (Y - y0) * ux
        closer = dist < best
        best = np.where(closer, dist, best)
        side = np.where(closer, s, side)
        arc = np.where(closer, acc + t, arc)
        nxs = np.where(closer, -uy, nxs)
        nys = np.where(closer, ux, nys)
        acc += length
    return best, side, arc, nxs, nys


def strap(layer: np.ndarray, pts, width: float, studs: float = 8.0, phase: float = 0.0) -> None:
    """A silver filigree strap: a bright lit edge, an engraved centre line with scroll
    curls, frost-crystal studs and a dark shadow edge."""
    h, w = layer.shape[:2]
    X, Y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    dist, s, arc, nx, ny = _poly_field(X, Y, pts)
    inside = dist <= width / 2
    if not inside.any():
        return
    opaque = layer[..., 3] > 0
    shadow = (_shift(inside, 1, 1) | _shift(inside, 0, 1)) & ~inside & opaque
    layer[shadow, :3] = layer[shadow, :3] * 0.55 + _c(NAVY[1])[:3] * 0.45
    lit = s * (nx * LIGHT[0] + ny * LIGHT[1]) > 0
    edge = np.abs(s) > width / 2 - 1.0
    col = np.where(edge[..., None], np.where(lit[..., None], _c(SILVER[5]), _c(SILVER[1])),
                   np.where(lit[..., None], _c(SILVER[4]), _c(SILVER[3])))
    a = (arc + phase) % studs
    groove = (np.abs(s) < 0.5) & ~edge & (a > 1.6) & (a < studs - 1.6)
    col = np.where(groove[..., None], _c(SILVER[2]), col)
    curl = (np.abs(s) < 1.3) & ~edge & ((a > studs / 2 - 0.6) & (a < studs / 2 + 0.6)) & (s * np.sin(arc) > 0)
    col = np.where(curl[..., None], _c(SILVER[2]), col)
    stud = (a < 1.5) & (np.abs(s) < 1.2) & ~edge
    col = np.where(stud[..., None], np.where((a < 0.75)[..., None], _c(WHITE), _c(TEAL[4])), col)
    layer[inside] = col[inside]


def clasp(layer: np.ndarray, cx: float, cy: float, r: float) -> None:
    """The snowflake clasp: bright silver arms with a single-pixel shadow edge around an
    aurora gem."""
    h, w = layer.shape[:2]
    flake = snowflake_mask((w, h), cx, cy, r, width=2, branches=((0.56, 0.4),))
    X, Y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    drop = (_shift(flake, 1, 1) | _shift(flake, 0, 1)) & ~flake
    layer[drop] = _c(NAVY[2])
    lit = (X - cx) * LIGHT[0] + (Y - cy) * LIGHT[1] > 0
    layer[flake & lit] = _c(SILVER[5])
    layer[flake & ~lit] = _c(SILVER[4])
    tip = snowflake_mask((w, h), cx, cy, r, branches=()) & ~snowflake_mask((w, h), cx, cy, r - 1.5, branches=())
    layer[tip] = _c(WHITE)
    d = np.abs(X - cx) + np.abs(Y - cy)
    layer[d <= 3.6] = _c(SILVER[1])
    layer[d <= 2.7] = _c(TEAL[3])
    layer[(d <= 2.7) & (X + Y > cx + cy)] = _c(VIOLET[3])
    layer[d <= 1.5] = _c(TEAL[5])
    layer[(d <= 1.5) & (X + Y > cx + cy + 0.5)] = _c(VIOLET[4])
    layer[int(cy) - 1, int(cx) - 1] = _c(WHITE)


def pendant(layer: np.ndarray, cx: int, top: int) -> None:
    """A small icicle hanging from the clasp."""
    rows = [3, 3, 3, 2, 2, 1, 1]
    layer[top - 1, cx] = _c(SILVER[4])
    for i, wv in enumerate(rows):
        for j in range(wv):
            x = cx - 1 + j
            c = WHITE if j == 0 else GLASS[3] if j < wv - 1 else GLASS[1]
            layer[top + i, x] = _c(c)


def plate(layer: np.ndarray, cx: float, cy: float, rx: float, ry: float) -> None:
    """A diamond-shaped silver mount plate with a frost rune (under the wing roots)."""
    h, w = layer.shape[:2]
    X, Y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    d = np.abs(X - cx) / rx + np.abs(Y - cy) / ry
    inside = d <= 1.0
    edge = d > 1.0 - 1.6 / min(rx, ry)
    lit = (X - cx) * LIGHT[0] + (Y - cy) * LIGHT[1] > 0
    col = np.where(edge[..., None], np.where(lit[..., None], _c(SILVER[5]), _c(SILVER[1])), _c(SILVER[3]))
    layer[inside] = col[inside]
    rune = snowflake_mask((w, h), cx, cy, min(rx, ry) * 0.55, branches=()) & inside & ~edge
    layer[rune] = _c(TEAL[4])
    layer[int(cy), int(cx)] = _c(WHITE)


def buckle(layer: np.ndarray, x0: int, y0: int, x1: int, y1: int) -> None:
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if x in (x0, x1) or y in (y0, y1):
                layer[y, x] = _c(SILVER[5] if (x == x0 or y == y0) else SILVER[1])
    layer[(y0 + y1) // 2, x0 + 1:x1] = _c(SILVER[2])
    layer[(y0 + y1) // 2, (x0 + x1) // 2] = _c(TEAL[4])


def paint_harness() -> Image.Image:
    out = np.zeros((32 * S, 64 * S, 4), np.float32)

    def area(name):
        x0, y0, x1, y1 = region(HUMANOID, name, S)
        return (x0, y0, x1, y1), np.zeros((y1 - y0 + 1, x1 - x0 + 1, 4), np.float32)

    def put(box_, layer):
        x0, y0, x1, y1 = box_
        out[y0:y1 + 1, x0:x1 + 1] = layer

    band_y, band_w = 25.0, 5
    # Front: shoulder straps sweep into the snowflake clasp on the chest band.
    b, lay = area("body_front")
    strap(lay, [(6.5, -4), (8.5, 8), (13.0, 20)], 4)
    strap(lay, [(25.5, -4), (23.5, 8), (19.0, 20)], 4, phase=4.0)
    strap(lay, [(-4, band_y), (36, band_y)], band_w, phase=2.0)
    clasp(lay, 16.0, band_y - 1.0, 9.5)
    pendant(lay, 16, 35)
    put(b, lay)
    # Top of the shoulders: the straps pass over.
    b, lay = area("body_top")
    strap(lay, [(6.5, -4), (6.5, 20)], 4)
    strap(lay, [(25.5, -4), (25.5, 20)], 4, phase=4.0)
    put(b, lay)
    # Back: straps converge on the wing mount plate.
    b, lay = area("body_back")
    strap(lay, [(6.5, -4), (10.0, 8), (14.0, 14)], 4, phase=2.0)
    strap(lay, [(25.5, -4), (22.0, 8), (18.0, 14)], 4, phase=6.0)
    strap(lay, [(16.0, 14), (16.0, 30)], 4, phase=1.0)
    strap(lay, [(-4, band_y), (36, band_y)], band_w, phase=3.0)
    plate(lay, 16.0, 13.5, 7.5, 8.0)
    put(b, lay)
    # Sides: the band wraps round through a buckle.
    for name in ("body_right", "body_left"):
        b, lay = area(name)
        strap(lay, [(-4, band_y), (20, band_y)], band_w, phase=5.0)
        buckle(lay, 4, int(band_y) - 4, 11, int(band_y) + 3)
        put(b, lay)
    # Arms: a filigree band round each upper arm, a snowflake stud on the outside.
    for name in ("arm_outer", "arm_front", "arm_inner", "arm_back"):
        b, lay = area(name)
        strap(lay, [(-4, 15.5), (20, 15.5)], 4, studs=8.0, phase=1.0)
        if name == "arm_outer":
            flake = snowflake_mask((lay.shape[1], lay.shape[0]), 8.0, 15.5, 4.6, branches=())
            drop = (_shift(flake, 1, 1)) & ~flake
            lay[drop] = _c(NAVY[2])
            lay[flake] = _c(SILVER[5])
            lay[15, 7] = _c(TEAL[4])
        put(b, lay)
    return _to_image(out)


# --------------------------------------------------------------------------------------
# Item textures
# --------------------------------------------------------------------------------------

ATLAS = 128   # feather atlas size; 2 texels per model unit, mapped with explicit UVs


def _slot_uv(slot, flip=False) -> list[float]:
    x, y, w, h = slot
    k = 16 / ATLAS
    uv = [x * k, y * k, (x + w) * k, (y + h) * k]
    return [uv[2], uv[1], uv[0], uv[3]] if flip else uv


def paint_atlas(slots, feather, covert, sheen, cracks=False, ice=GLASS) -> Image.Image:
    atlas = np.zeros((ATLAS, ATLAS, 4), np.float32)
    for f in ITEM_FEATHERS:
        x, y, w, h = slots[f["name"]]
        kind = "slab_covert" if f["kind"] == "covert" else "slab"
        p = dict(base=(w / 2, 0.0), tip=(w / 2, float(h)), w=float(w), kind=kind, hue=f.get("hue", 0.5),
                 tone=f.get("tone", 0.0), lo=0.9, span=4.3, shadow=False)
        img, _ = paint_plumes(w, h, [p], feather, covert, sheen=sheen, cracks=cracks, ice=ice)
        atlas[y:y + h, x:x + w] = img
    return _to_image(atlas)


def paint_glass() -> Image.Image:
    """Frost glass for the diamond tips, anchored at the bottom-left corner: that corner is
    the point, the anti-diagonal is the crystal ridge, the left half catches the light."""
    img = np.zeros((16, 16, 4), np.float32)
    for j in range(16):
        for i in range(16):
            d = i + j - 15
            c, a = (GLASS[4], 180) if d < 0 else (WHITE, 235) if d == 0 else (GLASS[2], 170)
            if i == 0:
                c, a = WHITE, 245
            elif j == 15:
                c, a = GLASS[1], 215
            elif d == 1:
                c, a = GLASS[3], 200
            if (i, j) in ((2, 11), (3, 12), (5, 9)):
                c, a = WHITE, 250
            img[j, i] = _c(c)
            img[j, i, 3] = a
    return _to_image(img)


def paint_shard() -> Image.Image:
    img = np.zeros((32, 32, 4), np.float32)
    for y in range(32):
        for x in range(32):
            if x == 0:
                c, a = WHITE, 240
            elif x == 1:
                c, a = GLASS[4], 205
            elif (x + 2 * y) % 9 == 0:
                c, a = WHITE, 235
            else:
                c, a = (GLASS[3] if x < 3 else GLASS[2]), 180
            img[y, x] = _c(c)
            img[y, x, 3] = a
    return _to_image(img)


def paint_bone() -> Image.Image:
    """Ice-bone for the wing arms: a frosted silver ridge with cold streaks."""
    img = np.zeros((32, 32, 4), np.float32)
    cols = [SILVER[5], SILVER[4], SILVER[4], SILVER[3], SILVER[3], SILVER[2]]
    for y in range(32):
        for x in range(32):
            c = cols[min(x, 5)]
            if 1 <= x <= 4 and (y * 3 + x * 5) % 11 == 0:
                c = GLASS[4] if x < 3 else SILVER[2]
            img[y, x] = _c(c)
    return _to_image(img)


def paint_runes() -> Image.Image:
    """Glowing frost runes for decals on the arms (transparent elsewhere)."""
    img = np.zeros((32, 32, 4), np.float32)
    glyphs = [((0, 0), (1, 1), (2, 2), (1, 3), (0, 3)), ((1, 0), (0, 1), (2, 1), (1, 2), (1, 3)),
              ((0, 0), (2, 0), (1, 1), (1, 2), (0, 3), (2, 3)), ((0, 0), (0, 1), (1, 1), (2, 2), (2, 3))]
    y, g = 1, 0
    while y + 4 < 32:
        for px, py in glyphs[g % len(glyphs)]:
            img[y + py, px] = _c(RUNE[1] if (px + py) % 2 else RUNE[2])
        y += 6
        g += 1
    return _to_image(img)


def paint_frost(dark=False) -> Image.Image:
    img = np.zeros((32, 32, 4), np.float32)
    cols = [SILVER[2], SILVER[3], SILVER[2]] if dark else [GLASS[4], WHITE, GLASS[3]]
    for y in range(32):
        for x in range(32):
            img[y, x] = _c(cols[min(x, 2)])
    return _to_image(img)


def paint_silver() -> Image.Image:
    img = np.zeros((32, 32, 4), np.float32)
    for y in range(32):
        for x in range(32):
            c = SILVER[5] if x == 0 else SILVER[4] if x == 1 else SILVER[3] if x < 4 else SILVER[2]
            if 2 <= x < 4 and (x + y) % 5 == 0:
                c = SILVER[2]
            if y % 8 == 3 and x < 4:
                c = SILVER[5]
            img[y, x] = _c(c)
    return _to_image(img)


def paint_gem(dark=False) -> Image.Image:
    """Aurora gem (a square face that is turned 45 degrees on the model)."""
    img = np.zeros((32, 32, 4), np.float32)
    n = 7
    for y in range(32):
        for x in range(32):
            if x >= n - 1 or y >= n - 1:
                c = NAVY[2]
            elif x == 0 or y == 0:
                c = TEAL[5] if not dark else SILVER[3]
            elif x + y < n - 1:
                c = TEAL[4] if not dark else SILVER[2]
            elif x + y == n - 1:
                c = "#e9ffff" if not dark else SILVER[3]
            else:
                c = VIOLET[3] if not dark else NAVY[3]
            img[y, x] = _c(c)
    if not dark:
        img[1, 1] = _c(WHITE)
        img[2, 1] = _c(WHITE)
    return _to_image(img)


def paint_strap() -> Image.Image:
    """Navy leather strap: a silver stitch line down the middle and a dark far edge."""
    img = np.zeros((32, 32, 4), np.float32)
    for y in range(32):
        for x in range(32):
            c = NAVY[3] if x < 1 else NAVY[1] if x >= 3 else NAVY[2]
            if x == 1 and y % 3 == 0:
                c = SILVER[4]
            img[y, x] = _c(c)
    return _to_image(img)


def paint_snow() -> Image.Image:
    img = np.zeros((32, 32, 4), np.float32)
    for y in range(32):
        for x in range(32):
            c = SNOW[4] if y == 0 else SNOW[3]
            if (x * 7 + y * 5) % 9 == 0:
                c = SNOW[2]
            if (x * 3 + y * 11) % 17 == 0:
                c = WHITE
            img[y, x] = _c(c)
    return _to_image(img)


# --------------------------------------------------------------------------------------
# Item model: a folded pair of frost wings (right wing built, then mirrored)
# --------------------------------------------------------------------------------------

J0, J1, J2, J3 = (10.2, 24.0), (17.0, 30.2), (23.6, 24.6), (25.2, 17.8)   # shoulder, wrist, hand, claw
ARM_Z = 11.0


def _on(a, b, f, dx=0.0, dy=0.0):
    return (a[0] + (b[0] - a[0]) * f + dx, a[1] + (b[1] - a[1]) * f + dy)


# Each feather hangs from `root`: `angle` swings it (degrees, + toward the outer edge),
# `bend` curls its lower part, flight feathers end in a frost-glass diamond. Listed back
# to front: primaries, secondaries, greater coverts, lesser coverts (inner ones on top).
ITEM_FEATHERS = [
    dict(name="pr1", root=_on(J2, J3, 0.25), angle=6, bend=-10, length=19, width=3.8, z=6.3, kind="flight",
         hue=1.0, roll=9),
    dict(name="pr2", root=_on(J1, J2, 0.78), angle=2, bend=-11, length=25, width=3.8, z=6.65, kind="flight",
         hue=0.9, roll=-6),
    dict(name="pr3", root=_on(J1, J2, 0.55), angle=-3, bend=-11, length=29, width=3.8, z=7.0, kind="flight",
         hue=0.8, roll=7),
    dict(name="pr4", root=_on(J1, J2, 0.32), angle=-7, bend=-10, length=31, width=3.6, z=7.35, kind="flight",
         hue=0.7, roll=-7),
    dict(name="pr5", root=_on(J1, J2, 0.1), angle=-10, bend=-9, length=30, width=3.4, z=7.7, kind="flight",
         hue=0.6, roll=6),
    dict(name="se1", root=_on(J0, J1, 0.85), angle=-9, bend=-8, length=25, width=3.6, z=8.4, kind="flight",
         hue=0.45, roll=-8),
    dict(name="se2", root=_on(J0, J1, 0.6), angle=-8, bend=-7, length=22, width=3.4, z=8.8, kind="flight",
         hue=0.3, roll=7),
    dict(name="se3", root=_on(J0, J1, 0.36), angle=-6, bend=-6, length=19, width=3.2, z=9.2, kind="flight",
         hue=0.15, roll=-6),
    dict(name="se4", root=_on(J0, J1, 0.12), angle=-4, bend=-5, length=16, width=3.0, z=9.6, kind="flight",
         hue=0.0, roll=6),
    dict(name="co1", root=_on(J2, J3, 0.1), angle=3, length=10.5, width=3.6, z=9.9, kind="covert", tone=-0.3, roll=8),
    dict(name="co2", root=_on(J1, J2, 0.7), angle=0, length=11, width=3.6, z=10.05, kind="covert", tone=-0.2,
         roll=-6),
    dict(name="co3", root=_on(J1, J2, 0.42), angle=-3, length=11.5, width=3.6, z=10.2, kind="covert", tone=-0.1,
         roll=7),
    dict(name="co4", root=_on(J1, J2, 0.15), angle=-5, length=11.5, width=3.5, z=10.35, kind="covert", tone=0.0,
         roll=-6),
    dict(name="co5", root=_on(J0, J1, 0.72), angle=-6, length=10.5, width=3.4, z=10.5, kind="covert", tone=0.1,
         roll=6),
    dict(name="co6", root=_on(J0, J1, 0.42), angle=-5, length=9.5, width=3.2, z=10.65, kind="covert", tone=0.2,
         roll=-5),
    dict(name="co7", root=_on(J0, J1, 0.14), angle=-4, length=8.5, width=3.0, z=10.8, kind="covert", tone=0.3,
         roll=5),
    dict(name="le1", root=_on(J1, J2, 0.75, 0, -0.6), angle=2, length=5.2, width=3.0, z=11.4, kind="covert",
         tone=0.5, roll=-4),
    dict(name="le2", root=_on(J1, J2, 0.42, 0, -0.6), angle=-2, length=5.6, width=3.0, z=11.6, kind="covert",
         tone=0.55, roll=4),
    dict(name="le3", root=_on(J1, J2, 0.1, 0, -0.6), angle=-4, length=5.6, width=3.0, z=11.4, kind="covert",
         tone=0.6, roll=-4),
    dict(name="le4", root=_on(J0, J1, 0.62, 0, -0.6), angle=-5, length=5.2, width=2.9, z=11.6, kind="covert",
         tone=0.55, roll=4),
    dict(name="le5", root=_on(J0, J1, 0.28, 0, -0.6), angle=-4, length=4.6, width=2.8, z=11.4, kind="covert",
         tone=0.5, roll=-4),
]
DEPTH = {"flight": 0.8, "covert": 0.9}
SPLIT = 0.58                    # where a flight feather bends
MISSING_WHEN_BROKEN = {"pr2", "se2", "le2"}


def _feather_len(f) -> float:
    return f["length"]


def atlas_slots() -> dict:
    """Shelf-pack every feather's front face into the atlas at 2 texels per unit; odd
    widths keep the quill on a single texel column."""
    slots, x, y, shelf = {}, 0, 0, 0
    for f in sorted(ITEM_FEATHERS, key=lambda f: -_feather_len(f)):
        w = int(round(f["width"] * 2))
        w += 1 - w % 2
        h = round(_feather_len(f) * 2)
        if x + w > ATLAS:
            x, y, shelf = 0, y + shelf + 1, 0
        slots[f["name"]] = (x, y, w, h)
        x += w + 1
        shelf = max(shelf, h)
    assert y + shelf <= ATLAS, "feather atlas overflow"
    return slots


def _feather_faces(slot, v0, v1, tex):
    """Faces of one feather segment showing atlas rows v0..v1 of its slot."""
    sx, sy, sw, _ = slot
    k = 16 / ATLAS
    under = "plume_broken" if tex == "plume_broken" else "plume_under"
    return {"south": (tex, [sx * k, (sy + v0) * k, (sx + sw) * k, (sy + v1) * k]),
            "north": (under, [(sx + sw) * k, (sy + v0) * k, sx * k, (sy + v1) * k]),
            "west": (tex, [sx * k, (sy + v0) * k, (sx + 1) * k, (sy + v1) * k]),
            "east": (tex, [(sx + sw - 1) * k, (sy + v0) * k, (sx + sw) * k, (sy + v1) * k]),
            "up": (tex, [sx * k, (sy + v0) * k, (sx + sw) * k, (sy + v0 + 1) * k]),
            "down": (tex, [sx * k, (sy + v1 - 1) * k, (sx + sw) * k, (sy + v1) * k])}


def _glass_tip(x, y, z, w, d):
    """A frost-glass diamond whose upper half hides inside the end of the feather."""
    side = w * 1.12 / math.sqrt(2)
    kk = round(side * 2, 3)
    rim = [0, 16 - kk, 1, 16]
    tip = box((x - side / 2, y - side / 2, z - d * 0.36), (x + side / 2, y + side / 2, z + d * 0.36), "glass",
              faces={"south": ("glass", [0, 16 - kk, kk, 16]), "north": ("glass", [kk, 16 - kk, 0, 16]),
                     "east": ("glass", rim), "west": ("glass", rim), "up": ("glass", rim), "down": ("glass", rim)},
              glow=8)
    return turn(tip, 45, "z", (x, y, z))


def feather3d(f, slot, broken=False):
    """One feather hanging from its root. Flight feathers bend at SPLIT and end in a
    frost-glass tip. Returns (solid parts, glass parts)."""
    rx, ry = f["root"]
    length, a1 = f["length"], f["angle"]
    w, z, d = f["width"], f["z"], DEPTH[f["kind"]]
    sh = slot[3]
    tex = "plume_broken" if broken else "plume"
    roll = f.get("roll", 0)
    if f["kind"] != "flight":
        part = box((rx - w / 2, ry - length, z - d / 2), (rx + w / 2, ry, z + d / 2), tex,
                   faces=_feather_faces(slot, 0, sh, tex))
        turn(part, roll, "y", (rx, ry, z))
        turn(part, a1, "z", (rx, ry, z))
        return [part], []
    a2 = a1 + f.get("bend", 0)
    l1, l2 = length * SPLIT, length * (1 - SPLIT)
    h1 = round(sh * SPLIT)
    upper = box((rx - w / 2, ry - l1, z - d / 2), (rx + w / 2, ry, z + d / 2), tex,
                faces=_feather_faces(slot, 0, h1, tex))
    turn(upper, roll, "y", (rx, ry, z))
    turn(upper, a1, "z", (rx, ry, z))
    jx, jy = rx + l1 * math.sin(math.radians(a1)), ry - l1 * math.cos(math.radians(a1))
    dd, ov = d * 0.94, 0.4           # the lower part tucks just behind the upper one
    lower = box((jx - w / 2, jy - l2, z - dd / 2), (jx + w / 2, jy + ov, z + dd / 2), tex,
                faces=_feather_faces(slot, h1 - 1, sh, tex))
    group = [lower]
    glass = []
    if not broken:
        glass = [_glass_tip(jx, jy - l2, z, w, dd)]
        group += glass
    turn(group, roll, "y", (jx, jy, z))
    turn(group, a2, "z", (jx, jy, z))
    return [upper, lower], glass


def _perp(p0, p1, amount):
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    n = math.hypot(dx, dy)
    return (-dy / n * amount, dx / n * amount)


def arm_parts(broken=False):
    """The leading edge: ice-bone arm, rune inlays, snow drifts, wrist snowflake, ice crown."""
    solid, decals, clear = [], [], []
    for a, b, w, d in ((J0, J1, 2.4, 2.2), (J1, J2, 2.2, 2.0)):
        solid.append(bar((*a, ARM_Z), (*b, ARM_Z), w, d, "bone"))
        if not broken:
            decals.append(bar((*a, ARM_Z + d / 2 + 0.05), (*b, ARM_Z + d / 2 + 0.05), 1.5, 0.02, "runes",
                              skip=("north", "east", "west", "up", "down"), glow=12))
        ox, oy = _perp(a, b, w / 2 + 0.35)
        pa = _on(a, b, 0.2, ox, oy)
        pb = _on(a, b, 0.8, ox, oy)
        solid.append(bar((*pa, ARM_Z), (*pb, ARM_Z), 1.1, d + 0.5, "snow"))
    solid.append(bar((*J3, ARM_Z - 0.3), (*J2, ARM_Z), 1.6, 1.6, "bone"))           # hand
    solid.append(turn(box((J1[0] - 1.5, J1[1] - 1.5, ARM_Z - 1.3), (J1[0] + 1.5, J1[1] + 1.5, ARM_Z + 1.3),
                          "silver"), 45, "z", (*J1, ARM_Z)))                           # wrist knuckle
    solid.append(turn(box((J0[0] - 1.4, J0[1] - 1.4, ARM_Z - 1.2), (J0[0] + 1.4, J0[1] + 1.4, ARM_Z + 1.2),
                          "silver"), 45, "z", (*J0, ARM_Z)))                           # shoulder knuckle
    # Wrist snowflake medallion in front of the knuckle.
    cz = ARM_Z + 1.55
    tex, glow = ("frost_dark", 0) if broken else ("frost", 12)
    for a in (90, 30, 150):
        r = math.radians(a)
        dx, dy = math.cos(r) * 2.3, math.sin(r) * 2.3
        solid.append(bar((J1[0] - dx, J1[1] - dy, cz), (J1[0] + dx, J1[1] + dy, cz), 0.7, 0.5, tex, glow=glow))
    solid.append(turn(box((J1[0] - 0.75, J1[1] - 0.75, cz - 0.2), (J1[0] + 0.75, J1[1] + 0.75, cz + 0.55),
                          "gem_dark" if broken else "gem", glow=0 if broken else 14,
                          uv={s: [0, 0, 3, 3] for s in ("north", "south", "east", "west", "up", "down")}),
                      45, "z", (*J1, cz)))
    # A crown of ice shards rising from the wrist and an icicle claw below the hand.
    if not broken:
        for ex, ey, wd, dz in ((0.9, 4.2, 1.2, -0.5), (3.4, 2.8, 1.0, -0.8), (-1.6, 3.0, 0.9, -0.7)):
            clear.append(bar((J1[0] + ex * 0.15, J1[1] + 0.5, ARM_Z + dz * 0.5),
                             (J1[0] + ex, J1[1] + ey, ARM_Z + dz), wd, wd, "shard", glow=7))
        clear.append(bar((J3[0] - 0.6, J3[1] - 3.2, ARM_Z - 0.5), (J3[0], J3[1] + 0.6, ARM_Z - 0.3), 1.0, 1.0,
                         "shard", glow=7))
    return solid, decals, clear


def wing(broken=False):
    """The right wing, flat, before the V-turn: (solid, decals, clear) parts."""
    slots = atlas_slots()
    solid, decals, clear = [], [], []
    for f in ITEM_FEATHERS:
        if broken and f["name"] in MISSING_WHEN_BROKEN:
            continue
        s, g = feather3d(f, slots[f["name"]], broken)
        solid += s
        clear += g
    a, dcl, c = arm_parts(broken)
    return solid + a, decals + dcl, clear + c


YOKE = (8.0, 23.6, 11.2)


def centre(broken=False):
    """The harness yoke with the snowflake clasp, straps and icicle pendants."""
    solid, clear = [], []
    solid.append(bar((YOKE[0] - 3.4, YOKE[1], YOKE[2]), (YOKE[0] + 3.4, YOKE[1], YOKE[2]), 2.0, 1.8, "silver"))
    cx, cy, cz = YOKE[0], YOKE[1], 12.6
    for a in (90, 30, 150):
        r = math.radians(a)
        dx, dy = math.cos(r) * 4.6, math.sin(r) * 4.6
        solid.append(bar((cx - dx, cy - dy, cz), (cx + dx, cy + dy, cz), 1.2, 0.9, "silver"))
    for k in range(6):
        r = math.radians(90 + 60 * k)
        bx, by = cx + math.cos(r) * 2.9, cy + math.sin(r) * 2.9
        for sg in (-1, 1):
            b = r + sg * math.radians(52)
            solid.append(bar((bx, by, cz), (bx + math.cos(b) * 1.8, by + math.sin(b) * 1.8, cz), 0.7, 0.7, "silver"))
    gem = box((cx - 1.7, cy - 1.7, cz - 0.3), (cx + 1.7, cy + 1.7, cz + 1.0), "gem_dark" if broken else "gem",
              glow=0 if broken else 13,
              uv={s: [0, 0, 3.5, 3.5] for s in ("north", "south", "east", "west", "up", "down")})
    solid.append(turn(gem, 45, "z", (cx, cy, cz)))
    for sg in (-1, 1):
        x0, x1 = cx + sg * 1.3, cx + sg * 1.9
        solid.append(bar((x1, 9.0, 10.7), (x0, 23.2, 11.0), 1.6, 0.5, "strap"))      # tucks behind the yoke
        solid.append(box((x1 - 1.1, 8.0, 10.2), (x1 + 1.1, 9.4, 11.3), "silver"))
        if not broken:
            clear.append(bar((x1 + sg * 0.3, 5.0, 10.75), (x1, 8.2, 10.75), 0.9, 0.9, "shard", glow=7))
    return solid, clear


def _raw(broken=False) -> list[dict]:
    solid, decals, clear = wing(broken)
    turn(solid + decals + clear, 24, "y", (10.0, 20.0, 10.0))     # outer wing sweeps back: a shallow V

    def mirrored(parts):
        return [mirror(p, "x", 8)[0] for p in parts]

    c_solid, c_clear = centre(broken)
    # Opaque first, then cutout decals, then translucent glass (items do not sort faces).
    return (solid + mirrored(solid) + c_solid + decals + mirrored(decals)
            + clear + mirrored(clear) + c_clear)


def offset() -> tuple[float, float, float]:
    """Moves the intact model's bounding box centre to (8, 8, 8); the broken one shares it."""
    lo, hi = bounds(_raw())
    return tuple(round(8 - (lo[i] + hi[i]) / 2, 3) for i in range(3))


def build(broken=False, shift=None) -> list[dict]:
    parts = _raw(broken)
    move(parts, *(shift or offset()))
    return parts


def transforms(parts, shift) -> dict:
    grip = tuple(YOKE[i] + shift[i] for i in range(3))     # a hand carries the wings by the yoke
    d = display("elytra", parts, grip=grip, size=0.72, gui_rotation=(18, 0, 0), gui_span=15.5)
    # First person: the wings stand up in the lower right, face turned toward the eye.
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.45, 0.15, 1)}, (8, 8, 8), (0.62, -0.2, -1.0), 0.33,
                                       pose=None)
    return d


def models() -> dict:
    shift = offset()
    main, broken = build(shift=shift), build(broken=True, shift=shift)
    return {"main": model(main, transforms(main, shift)), "broken": model(broken, transforms(broken, shift))}


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def textures() -> None:
    save_layer(paint_wings(), "wings")
    save_layer(paint_harness(), "humanoid")
    slots = atlas_slots()
    save(paint_atlas(slots, FEATHER, COVERT, 0.3), "plume")
    save(paint_atlas(slots, UNDER, COVERT_UNDER, 0.16), "plume_under")
    save(paint_atlas(slots, BROKEN, COVERT_BROKEN, 0.0, cracks=True, ice=DEAD_ICE), "plume_broken")
    save(paint_glass(), "glass")
    save(paint_shard(), "shard")
    save(paint_bone(), "bone")
    save(paint_runes(), "runes")
    save(paint_frost(), "frost")
    save(paint_frost(dark=True), "frost_dark")
    save(paint_silver(), "silver")
    save(paint_gem(), "gem")
    save(paint_gem(dark=True), "gem_dark")
    save(paint_strap(), "strap")
    save(paint_snow(), "snow")
