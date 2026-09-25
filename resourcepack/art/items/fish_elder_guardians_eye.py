"""Elder Guardian's Eye: a legendary catch of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection's shared pose: the thing it names, the great eye of
an elder guardian, laid along the set's diagonal (upper-left to lower-right, like the
fish). An almond of pale tan guardian hide, mottled with the elder's mauve patches and
bristling with bone spikes, frames a glossy cream eyeball veined with rose toward its
corners; the upper lid hoods the huge amber iris, which glows around a 2x2 pupil with a
bright catchlight. The old sprite's tan / rose-beige scheme is kept.

Animation (LEGENDARY, 16 frames x 3 ticks): the eye holds its gaze up-left, darts to the
viewer and back; the spikes flex in and out one after another like a guardian's; a glint
rolls across the hide and eyeball, then an iridescent (violet, rose, cyan) shimmer band
sparkles over the hide; the iris breathes with a golden halo, a golden rim glow breathes
just outside the outline and two sparkles orbit the eye while a third twinkles.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_elder_guardians_eye"
NAME = "Elder Guardian's Eye"
KIND = "item"
MODEL_KEY = "fish/elder_guardians_eye"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 16
FRAMETIME = 3
SS = 4                                 # supersampling per pixel side

# ---- palettes (darkest -> lightest), hue-shifted --------------------------------------------
OUTLINE = "#34202f"                    # deep plum-brown, never black
HIDE = ["#4c3542", "#6e515a", "#93756e", "#b3977f", "#ceb691", "#e7d6b0", "#f6ecd0"]
MAUVE = ["#5a3a4d", "#7d5569", "#a07688", "#c29aa6"]
LID_WET = "#d99aa0"                    # the wet rim of the lower lid
LASH = "#3e2433"                       # the upper lid's dark margin
BONE = ["#7a5a48", "#a88a68", "#cfb58a", "#ecdcb4", "#fbf3dc"]
BONE_TIP = "#b8683a"
SCLERA = ["#735672", "#9d8094", "#c6abb0", "#e0cfc6", "#f2e9dc", "#fdf9f0"]
CARUNCLE = ["#b05a6c", "#d9868e"]
VEIN = ["#b04858", "#cf767c"]
IRIS = ["#4a140e", "#842a12", "#bd4e16", "#e67f22", "#f9b640", "#ffe27e", "#fff6c4"]
PUPIL = "#1a0712"
CATCH = "#ffffff"
GLINT = "#fff9ea"
GOLD_GLOW = "#ffcf4a"
SPARK = "#fff4b8"
IRIDESCENT = ["#b48cff", "#ff9ed2", "#8ef0ff"]

# ---- geometry -------------------------------------------------------------------------------
# Local frame: u runs along the eye from the up-left corner toward the down-right corner,
# v points up (toward the brow). Units are pixels.
THETA = math.radians(28.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
CENTRE = (16.0, 16.4)
LIGHT = np.array([-0.55, -0.65, 0.55])
LIGHT = LIGHT / np.linalg.norm(LIGHT)

L_OUT, TOP_OUT, BOT_OUT = 13.8, 7.6, 6.0      # hide almond
L_IN, TOP_IN, BOT_IN = 11.0, 4.4, 3.9          # eye opening
OPEN_SHIFT = -0.3                              # the opening sits a touch toward the up-left


def _lens(u, length, height, p=2.0, q=0.85):
    k = np.clip(1 - (np.abs(u) / length) ** p, 0, None)
    return height * k ** q


def _top_out(u):
    return _lens(u, L_OUT, TOP_OUT, 1.9, 0.8)


def _bot_out(u):
    return _lens(u, L_OUT, BOT_OUT, 1.9, 0.8)


def _top_in(u):
    return _lens(u - OPEN_SHIFT, L_IN, TOP_IN, 2.0, 0.9)


def _bot_in(u):
    return _lens(u - OPEN_SHIFT, L_IN, BOT_IN, 2.0, 0.9)


def _local(x, y):
    dx, dy = x - CENTRE[0], y - CENTRE[1]
    return dx * AX[0] + dy * AX[1], dx * UP[0] + dy * UP[1]


def _image(u, v):
    return CENTRE[0] + u * AX[0] + v * UP[0], CENTRE[1] + u * AX[1] + v * UP[1]


_ys, _xs = np.mgrid[0:SIZE * SS, 0:SIZE * SS]
SU, SV = _local((_xs + 0.5) / SS, (_ys + 0.5) / SS)
_py, _px = np.mgrid[0:SIZE, 0:SIZE]
PU, PV = _local(_px + 0.5, _py + 0.5)


def _cov(mask: np.ndarray) -> np.ndarray:
    return mask.reshape(SIZE, SS, SIZE, SS).mean(axis=(1, 3))


def _poly(poly) -> np.ndarray:
    """Coverage of a polygon in local (u, v) coordinates (even-odd rule)."""
    inside = np.zeros(SU.shape, bool)
    n = len(poly)
    for i in range(n):
        u0, v0 = poly[i]
        u1, v1 = poly[(i + 1) % n]
        if v0 == v1:
            continue
        cond = (v0 > SV) != (v1 > SV)
        cross = u0 + (SV - v0) * (u1 - u0) / (v1 - v0)
        inside ^= cond & (SU < cross)
    return _cov(inside)


HIDE_M = _cov((np.abs(SU) <= L_OUT) & (SV <= _top_out(SU)) & (SV >= -_bot_out(SU))) >= 0.5
OPEN_M = _cov((np.abs(SU - OPEN_SHIFT) <= L_IN) & (SV <= _top_in(SU)) & (SV >= -_bot_in(SU))) >= 0.5
OPEN_M &= HIDE_M


# ---- spikes ---------------------------------------------------------------------------------
# (u on the lid edge, top (+1) or bottom (-1) lid, length, tilt along u, phase)
SPIKES = [(-8.0, 1, 3.9, -1.3, 0.00), (-2.0, 1, 4.6, -0.4, 0.17), (4.2, 1, 4.4, 0.6, 0.34),
          (9.6, 1, 3.4, 1.5, 0.50), (7.2, -1, 3.4, 1.1, 0.67), (-0.6, -1, 3.6, 0.0, 0.84)]


def _spike_geom(u0, side, length, tilt):
    """Base centre, outward direction and side normal of a spike (local coordinates)."""
    if side == 0:                                   # a corner spike, pointing along the axis
        sgn = 1 if u0 > 0 else -1
        base = (u0, 0.0)
        du, dv = sgn, 0.0
    else:
        v0 = float(_top_out(u0)) if side > 0 else -float(_bot_out(u0))
        base = (u0, v0 - side * 0.5)
        du, dv = tilt * 0.45, float(side)
    n = math.hypot(du, dv)
    du, dv = du / n, dv / n
    return base, (du, dv), (-dv, du)


def _paint_spikes(img, t: float):
    px = img.load()
    mask = np.zeros((SIZE, SIZE), bool)
    for u0, side, base_len, tilt, phase in SPIKES:
        length = base_len + 0.9 * (wave(t, phase) - 0.5)
        (bu, bv), (du, dv), (nu, nv) = _spike_geom(u0, side, length, tilt)
        half = 1.6
        reach = length + 0.5
        poly = [(bu + nu * half, bv + nv * half), (bu + du * reach, bv + dv * reach),
                (bu - nu * half, bv - nv * half)]
        cov = _poly(poly)
        # the side normal in image space; the side facing the top-left light is lit
        nx = nu * AX[0] + nv * UP[0]
        ny = nu * AX[1] + nv * UP[1]
        lit = 1 if (nx * LIGHT[0] + ny * LIGHT[1]) > 0 else -1
        for y in range(SIZE):
            for x in range(SIZE):
                if cov[y, x] < 0.4 or HIDE_M[y, x] or mask[y, x]:
                    continue
                ru, rv = PU[y, x] - bu, PV[y, x] - bv
                along = ru * du + rv * dv
                across = (ru * nu + rv * nv) * lit
                if along > reach - 1.3:
                    col = BONE_TIP if across > -0.3 else mix(BONE_TIP, BONE[0], 0.45)
                elif across > 0.2:
                    col = BONE[4] if along < reach * 0.55 else BONE[3]
                elif across > -0.4:
                    col = BONE[2]
                else:
                    col = BONE[1]
                px[x, y] = rgba(col)
                mask[y, x] = True
    return mask


# ---- hide lids ------------------------------------------------------------------------------
# Mauve patches of elder guardian hide, (u, v, radius) in local coordinates.
PATCHES = [(-8.2, 4.6, 1.2), (-2.4, 6.2, 1.1), (4.6, 5.6, 1.3), (10.6, 2.6, 1.0),
           (-1.4, -5.0, 1.0), (-7.8, -3.4, 0.9)]


def _plate(x: int, y: int) -> bool:
    return y % 3 == 0 and (x + (y // 3) % 2 * 2) % 4 == 0


def _hide_colour(x: int, y: int) -> tuple[str, str]:
    u, v = float(PU[y, x]), float(PV[y, x])
    upper = v >= 0 if abs(u - OPEN_SHIFT) >= L_IN else v > float(_top_in(u)) - 0.01 or v > 0
    if upper:
        edge_in, edge_out = float(_top_in(u)), float(_top_out(u))
        sgn = 1.0
    else:
        edge_in, edge_out = -float(_bot_in(u)), -float(_bot_out(u))
        sgn = -1.0
    span = max(0.6, abs(edge_out - edge_in))
    w = min(1.0, max(0.0, abs(v - edge_in) / span))          # 0 at the margin, 1 at the rim
    phi = math.radians((w * 2 - 1) * 70)
    nu = (u / L_OUT) * 0.9
    nv = sgn * math.sin(phi)
    nz = math.cos(phi)
    nx = nu * AX[0] + nv * UP[0]
    ny = nu * AX[1] + nv * UP[1]
    n = np.array([nx, ny, nz])
    n /= np.linalg.norm(n)
    lam = float(n @ LIGHT)
    wrap = 0.5 + 0.5 * lam                                       # wrapped light: no dead shadow side
    idx = int(np.clip(round(0.4 + wrap * 6.0), 2, 6))
    if not upper:
        idx = max(2, idx - 1)                                  # the lower lid sits in shadow
    for pu, pv, pr in PATCHES:
        if math.hypot(u - pu, v - pv) <= pr:
            return MAUVE[int(np.clip(idx - 2, 1, 3))], "hide"
    # plated hide: a sparse staggered lattice of plate edges (a lit pixel over a dark one),
    # kept off the rim and the margin
    if 0.25 < w < 0.8:
        if _plate(x, y):
            return HIDE[min(6, idx + 1)], "hide"
        if _plate(x, y - 1):
            return HIDE[max(1, idx - 1)], "hide"
    return HIDE[idx], "hide"


def _margins(img, zones):
    """The upper lid's dark lash line and the lower lid's wet pink rim, 1 px each, on the
    hide pixels that touch the opening."""
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if not HIDE_M[y, x] or OPEN_M[y, x]:
                continue
            touch = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                     if 0 <= x + dx < SIZE and 0 <= y + dy < SIZE and OPEN_M[y + dy, x + dx]]
            if not touch:
                continue
            if PV[y, x] > 0.0:
                # dark lash line over the iris, easing to a soft crease toward the corners
                far = PU[y, x] > 4.0 or PU[y, x] < -7.5
                px[x, y] = rgba(mix(LASH, HIDE[2], 0.55) if far else LASH)
                zones[(x, y)] = "lash"
            else:
                px[x, y] = rgba(LID_WET if PU[y, x] > -6 else mix(LID_WET, MAUVE[1], 0.4))
                zones[(x, y)] = "wet"


# ---- eyeball --------------------------------------------------------------------------------
# Veins toward the corners: polylines in local (u, v).
VEINS = [[(8.9, 0.4), (7.2, 0.9), (6.2, 0.3)], [(7.2, 0.9), (6.4, 2.1)],
         [(8.4, -1.3), (6.8, -1.7)],
         [(-9.1, -0.4), (-7.6, -1.0), (-6.6, -0.6)], [(-7.6, -1.0), (-6.9, -2.3)]]


def _near_seg(u, v, a, b, reach=0.5):
    au, av = a
    bu, bv = b
    du, dv = bu - au, bv - av
    k = max(0.0, min(1.0, ((u - au) * du + (v - av) * dv) / (du * du + dv * dv)))
    return math.hypot(u - au - k * du, v - av - k * dv) <= reach


R_SPHERE = 9.0


def _sclera_colour(x: int, y: int) -> str:
    u, v = float(PU[y, x]), float(PV[y, x])
    dx, dy = x + 0.5 - CENTRE[0], y + 0.5 - CENTRE[1]
    r2 = (dx * dx + dy * dy) / (R_SPHERE * R_SPHERE)
    n = np.array([dx / R_SPHERE, dy / R_SPHERE, math.sqrt(max(0.0, 1 - r2))])
    lam = float(n @ LIGHT)
    idx = int(np.clip(round(3.3 + lam * 2.0), 3, 5))
    # the hooding upper lid shades the eyeball just under it
    if float(_top_in(u)) - v < 1.3:
        idx -= 2 if u < 3.0 else 1
    elif float(_top_in(u)) - v < 2.3:
        idx -= 1
    idx = max(0, idx)
    col = SCLERA[idx]
    # pink caruncle in the up-left (inner) corner
    if u < OPEN_SHIFT - L_IN + 1.9:
        return CARUNCLE[0] if v < 0.2 else CARUNCLE[1]
    for vein in VEINS:
        for a, b in zip(vein, vein[1:]):
            if _near_seg(u, v, a, b, 0.5):
                return mix(col, VEIN[0] if idx <= 3 else VEIN[1], 0.6)
    return col


# ---- gaze: integer pupil positions (pixel corner the 2x2 pupil sits on) ---------------------
PUPIL_HOME = (15, 15)
GAZE_REST = (-1, -1)                   # up-left, the collection's "head" direction
GAZE_LOOK = (1, 1)                     # at the viewer, then down along the eye
GAZE = [GAZE_REST] * 6 + [(0, 0)] + [GAZE_LOOK] * 7 + [(0, 0)] + [GAZE_REST]
R_IRIS = 4.1


def _iris(img, zones, t: float, frame: int):
    """Iris, pupil, catchlight and radiant halo, clipped by the lids."""
    px = img.load()
    gx, gy = GAZE[frame % len(GAZE)]
    ox, oy = PUPIL_HOME[0] + gx, PUPIL_HOME[1] + gy          # pupil corner (pixel coords)
    pulse = wave(t, 0.1)
    for y in range(SIZE):
        for x in range(SIZE):
            if not OPEN_M[y, x]:
                continue
            dx, dy = x + 0.5 - ox, y + 0.5 - oy
            d = math.hypot(dx, dy)
            if d <= R_IRIS:
                f = d / R_IRIS
                lit = dx + dy                                   # concave iris: lit lower-right
                if f > 0.8:
                    idx = 0 if lit < 0.5 else 1                 # limbal ring
                elif f > 0.52:
                    idx = 2 if lit < -0.5 else 3
                else:
                    idx = 4 if lit < -0.3 else 5
                # radiant: striations turning slowly, brighter as the iris breathes
                a = math.atan2(dy, dx) + 2 * math.pi * t / 5
                if 2 <= idx <= 4 and math.cos(a * 5) > 0.6:
                    idx += 1
                if pulse > 0.55 and 3 <= idx <= 5:
                    idx += 1
                # the upper lid's shadow falls across the top of the iris
                if float(_top_in(PU[y, x])) - PV[y, x] < 1.2:
                    idx = max(0, idx - 2)
                px[x, y] = rgba(IRIS[min(idx, 6)])
                zones[(x, y)] = "iris"
            elif d <= R_IRIS + 1.4 and zones.get((x, y)) == "sclera":
                k = (0.12 + 0.34 * pulse) * (1 - (d - R_IRIS) / 1.4)
                px[x, y] = rgba(mix(px[x, y], GOLD_GLOW, k))
    for x, y in ((ox - 1, oy - 1), (ox, oy - 1), (ox - 1, oy), (ox, oy)):
        if OPEN_M[y, x]:
            px[x, y] = rgba(PUPIL)
            zones[(x, y)] = "pupil"
    # catchlight: bright 1 px at the pupil's top-left, a dim second on the lower right
    px[ox - 1, oy - 1] = rgba(CATCH)
    if zones.get((ox + 1, oy + 1)) == "iris":
        px[ox + 1, oy + 1] = rgba(mix(px[ox + 1, oy + 1], "#fff6d8", 0.55))


def _body(t: float, frame: int):
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for y in range(SIZE):
        for x in range(SIZE):
            if OPEN_M[y, x]:
                px[x, y] = rgba(_sclera_colour(x, y))
                zones[(x, y)] = "sclera"
            elif HIDE_M[y, x]:
                col, zone = _hide_colour(x, y)
                px[x, y] = rgba(col)
                zones[(x, y)] = zone
    _margins(img, zones)
    # glossy wet specular on the sclera, lower-right of the upper lid's shadow, fixed to light
    for (x, y), k in (((11, 16), 0.7), ((12, 16), 0.45), ((20, 18), 0.5)):
        if zones.get((x, y)) == "sclera":
            px[x, y] = rgba(mix(px[x, y], "#ffffff", k))
    _iris(img, zones, t, frame)
    return img, zones


# ---- effects --------------------------------------------------------------------------------

def _along(x: int, y: int) -> float:
    return float(PU[y, x]) + L_OUT


def _glint(img, zones, k01: float):
    """A soft glint rolling along the eye from the up-left corner (k01 = 0..1 of its run)."""
    px = img.load()
    centre = -2.0 + (2 * L_OUT + 4.0) * k01
    for (x, y), zone in zones.items():
        if zone in ("pupil", "lash"):
            continue
        k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 2.6) * 0.5
        if zone == "iris":
            k *= 0.55
        if k > 0:
            px[x, y] = rgba(mix(px[x, y], GLINT, k))


def _shimmer(img, zones, k01: float):
    """An iridescent band (violet, rose, cyan across its width) sweeping the hide and spikes,
    with sparkling hide plates as it passes."""
    px = img.load()
    centre = -2.0 + (2 * L_OUT + 4.0) * k01
    for (x, y), zone in zones.items():
        if zone not in ("hide", "spike"):
            continue
        d = _along(x, y) - centre
        if abs(d) > 3.4:
            continue
        k = min(1.0, (1.0 - abs(d) / 3.4) * 1.4)
        hue = IRIDESCENT[0] if d < -1.1 else IRIDESCENT[1] if d < 1.1 else IRIDESCENT[2]
        spot = y % 2 == 0 and (x + (y // 2) % 2 * 2) % 3 == 0
        if spot:
            px[x, y] = rgba(mix(mix(px[x, y], hue, 0.4 * k), "#fffbe8", 0.8 * k))
        else:
            px[x, y] = rgba(mix(px[x, y], hue, 0.52 * k))


def _outline(img):
    src = img.load()
    out = img.copy()
    dst = out.load()
    o = rgba(OUTLINE)
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            if any(0 <= x + dx < SIZE and 0 <= y + dy < SIZE and src[x + dx, y + dy][3]
                   for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                dst[x, y] = o
    return out


def _rim_glow(img, strength: float):
    """A breathing golden 1 px glow just outside the outline."""
    src = img.load()
    out = img.copy()
    dst = out.load()
    g = rgba(GOLD_GLOW)
    for y in range(1, SIZE - 1):
        for x in range(1, SIZE - 1):
            if src[x, y][3]:
                continue
            if any(src[x + dx, y + dy][3] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                dst[x, y] = (g[0], g[1], g[2], round(255 * strength))
    return out


def _orbit(t: float, offset: float):
    """A point on an ellipse around the eye, along its diagonal."""
    a = 2 * math.pi * (t + offset)
    u, v = 14.6 * math.cos(a), 9.6 * math.sin(a)
    x, y = _image(u, v)
    return int(round(min(28, max(3, x)))), int(round(min(28, max(3, y))))


def _frame(t: float) -> Image.Image:
    frame = int(round(t * FRAMES)) % FRAMES
    img = canvas(SIZE)
    spikes = _paint_spikes(img, t)
    body, zones = _body(t, frame)
    img.alpha_composite(body)
    for y in range(SIZE):
        for x in range(SIZE):
            if spikes[y, x] and (x, y) not in zones:
                zones[(x, y)] = "spike"
    # glint on frames 0-6, iridescent shimmer on frames 8-14
    if frame <= 6:
        _glint(img, zones, frame / 6)
    elif 8 <= frame <= 14:
        _shimmer(img, zones, (frame - 8) / 6)
    img = _outline(img)
    img = _rim_glow(img, 0.28 + 0.5 * wave(t))
    # two sparkles orbiting, a third twinkling by the brow
    for off in (0.0, 0.5):
        x, y = _orbit(t, off)
        sparkle(img, x, y, 0.35 + 0.65 * wave(2 * t, off), colour=SPARK, reach=2)
    sparkle(img, 27, 7, max(0.0, wave(t, 0.35) * 1.4 - 0.4), colour="#fffbe6", reach=2)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
