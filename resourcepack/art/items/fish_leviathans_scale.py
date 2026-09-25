"""Leviathan's Scale: a legendary catch of the JoshyMC fishing collection (deep ocean).

Not a fish but exactly what the name says: one huge armoured scale shed by a sea
leviathan, drawn as a flat 32x32 sprite in the collection pose (the broad, rounded crown
up-left, the point of the embedded root down-right, running diagonally like the vanilla
cod). Keeps the old sprite's deep sea-teal scheme and its white glint. The plate is a
domed kite shield lit from the top-left: a 7-tone teal ramp sinking to ocean navy, a pale
nacre enamel rim and bevel on the lit edge with reflected light along the shadowed one,
three growth ridges (dark groove, bright lip) nesting toward the root, a keel ridge
running to the point, a nacre highlight and a row of bioluminescent photophores along the
shadowed flank. 1 px navy outline with a lighter selective edge facing the light.

Animation (LEGENDARY, 20 frames x 2 ticks): an iridescent sheen (cyan, violet, rose)
drifts across the ridges; a glint slides along the plate from crown to root, then a
shimmer band rolls outward across the growth rings; a golden 1 px rim glow breathes just
outside the outline with a hotspot circling it, three sparkles orbit the scale, the
photophores pulse in a travelling wave and the nacre highlight flares into a radiant star.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, shade, sparkle, sprite, wave

ID = "fish_leviathans_scale"
NAME = "Leviathan's Scale"
KIND = "item"
MODEL_KEY = "fish/leviathans_scale"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 20
FRAMETIME = 2
SS = 4                                   # supersampling per pixel side

# ---- palettes (darkest -> lightest) ------------------------------------------------------
BASE = "#23a08a"                         # the old sprite's sea teal, a touch brighter
# Deep-sea teal: shadows sink toward ocean navy, lights lift toward pale aqua.
PLATE = ["#0a2438", "#0c4556", "#126e68", BASE, shade(BASE, 0.2, 0.06), mix(shade(BASE, 0.42, 0.06), "#8ff4ff", 0.2),
         mix(shade(BASE, 0.68, 0.05), "#c8fbff", 0.3)]
NACRE = "#e6fff2"                        # the lit rim and the old white glint
OUTLINE = "#061a2a"                      # deep ocean navy, never black
OUTLINE_LIT = "#0b3146"
GOLD = ["#9a5c12", "#dca232", "#ffd865", "#fff4c2"]
IRIDESCENT = ["#5fe6ff", "#9d7cff", "#ff8fd8"]
PHOTO = ["#39d8ea", "#d8ffff"]           # bioluminescent spots: resting, lit
GLINT = "#f2fff8"
SHIMMER = "#dafff0"

# ---- geometry: local frame u along the axis (crown edge -> root tip), v toward the
# upper-right. The plate is a kite shield: a broad, gently domed crown edge up-left with
# rounded shoulders, full sides and a point at the root, down-right.
THETA = math.radians(40.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
L = 25.0                                 # crown edge to root tip
W = 9.2                                  # half-width at the shoulders
BULGE = 3.0                              # how far the crown edge bows outward
CROWN = (9.6, 10.8)                      # middle of the crown edge in the frame
FOCUS_S = 0.64                           # growth focus (rings nest around it)
DOME_S = 0.38                            # top of the dome (where the light peaks)
RINGS = 4                                # growth bands (3 ridges between them)
KEEL_FROM = 5.0                          # the keel ridge runs from here to the root tip


def _halfwidth(u):
    s = np.clip(u / L, 0.0, 1.0)
    w = W * (1.0 - s ** 1.6) ** 0.8
    shoulder = np.clip((u + BULGE) / 4.0, 0.0, 1.0)      # round the shoulders
    return w * (0.72 + 0.28 * np.sqrt(shoulder))


def _local(x, y):
    dx, dy = x - CROWN[0], y - CROWN[1]
    return dx * AX[0] + dy * AX[1], dx * UP[0] + dy * UP[1]


def _frame_xy(u, v):
    return CROWN[0] + u * AX[0] + v * UP[0], CROWN[1] + u * AX[1] + v * UP[1]


_ys, _xs = np.mgrid[0:SIZE * SS, 0:SIZE * SS]
US, VS = _local((_xs + 0.5) / SS, (_ys + 0.5) / SS)
_py, _px = np.mgrid[0:SIZE, 0:SIZE]
UC, VC = _local(_px + 0.5, _py + 0.5)


def _cov(mask):
    return mask.reshape(SIZE, SS, SIZE, SS).mean(axis=(1, 3))


def _inside(u, v):
    top = -BULGE * (1.0 - np.clip(v / W, -1.0, 1.0) ** 2)
    return (u >= top) & (u <= L) & (np.abs(v) <= _halfwidth(u))


MASK = _cov(_inside(US, VS)) >= 0.5
N4 = ((1, 0), (-1, 0), (0, 1), (0, -1))


def _level(centre_u):
    """The smallest scale (about (centre_u, 0)) of the outline that still holds each
    pixel: 0 at the centre, 1 on the rim."""
    lam = np.full(UC.shape, 1.0)
    for k in np.linspace(1.0, 0.02, 100):
        lam = np.where(_inside(centre_u + (UC - centre_u) / k, VC / k), k, lam)
    return lam


FOCUS = (FOCUS_S * L, 0.0)
LEVEL = _level(FOCUS[0])
RING = np.minimum((LEVEL * RINGS).astype(int), RINGS - 1)

# Dome height -> normals -> light from the top-left (screen x right, y down, z to viewer).
_dome = _level(DOME_S * L)
_h = np.sqrt(np.clip(1.0 - _dome ** 2.2, 0.0, 1.0)) * 5.5
_gy, _gx = np.gradient(_h)
_n = np.stack([-_gx, -_gy, np.ones_like(_h)], axis=-1)
_n /= np.linalg.norm(_n, axis=-1, keepdims=True)
_light = np.array([-0.6, -0.66, 0.45])
_light /= np.linalg.norm(_light)
LIGHT = np.clip((_n * _light).sum(axis=-1), 0.0, 1.0)


def _depth():
    """Steps in from the silhouette edge: 1 on the edge, 2 on the next ring ..."""
    d = np.zeros(MASK.shape, int)
    cur = MASK.copy()
    k = 0
    while cur.any():
        k += 1
        pad = np.pad(cur, 1)
        core = cur & pad[:-2, 1:-1] & pad[2:, 1:-1] & pad[1:-1, :-2] & pad[1:-1, 2:]
        d[cur & ~core] = k
        cur = core
    return d


DEPTH = _depth()


def _in(x, y):
    return 0 <= x < SIZE and 0 <= y < SIZE and MASK[y, x]


def _facing(x, y):
    """How much the plate's edge near this pixel faces the top-left light (-1..1)."""
    cx, cy = _frame_xy(DOME_S * L, 0.0)
    dx, dy = x + 0.5 - cx, y + 0.5 - cy
    d = math.hypot(dx, dy) or 1.0
    return -(dx * 0.67 + dy * 0.74) / d


def _classify():
    """What each plate pixel is: enamel rim, bevel, growth groove / lip or open plate."""
    zone = {}
    for y in range(SIZE):
        for x in range(SIZE):
            if not MASK[y, x]:
                continue
            f = _facing(x, y)
            d = DEPTH[y, x]
            if d == 1:
                zone[(x, y)] = "rim_lit" if f > 0.25 else "rim_mid" if f > -0.35 else "rim_dark"
                continue
            if d == 2:
                zone[(x, y)] = "bevel_lit" if f > 0.25 else "bevel_dark" if f < -0.35 else "plate"
                continue
            r = RING[y, x]
            groove = any(_in(x + dx, y + dy) and DEPTH[y + dy, x + dx] >= 3 and RING[y + dy, x + dx] > r
                         for dx, dy in N4)
            lip = any(_in(x + dx, y + dy) and DEPTH[y + dy, x + dx] >= 3 and RING[y + dy, x + dx] < r
                      for dx, dy in N4)
            if groove and r >= 1:
                zone[(x, y)] = "groove"
            elif lip and r >= 2 and f > -0.2:
                zone[(x, y)] = "lip"
            elif KEEL_FROM < UC[y, x] < L - 3.0 and abs(VC[y, x]) < 0.55:
                zone[(x, y)] = "keel"
            elif KEEL_FROM < UC[y, x] < L - 3.0 and -1.6 < VC[y, x] <= -0.55:
                zone[(x, y)] = "keel_shadow"
            else:
                zone[(x, y)] = "plate"
    return zone


ZONE = _classify()
_hx, _hy = _frame_xy(DOME_S * L * 0.42, 2.4)
SPEC = (int(_hx), int(_hy))              # the nacre highlight (the old sprite's glint)
SPEC_PIX = {SPEC, (SPEC[0] + 1, SPEC[1]), (SPEC[0], SPEC[1] + 1)}


def _photophores():
    """Bioluminescent spots of the deep: every third pixel of the band just inside the
    bevel on the shadowed side, ordered around the plate from the left shoulder to the
    right one (so a pulse can run along them)."""
    cx, cy = _frame_xy(DOME_S * L, 0.0)
    band = [(x, y) for (x, y) in ZONE if DEPTH[y, x] == 3 and ZONE[(x, y)] == "plate" and _facing(x, y) < -0.3]
    band.sort(key=lambda p: math.atan2(p[1] + 0.5 - cy, p[0] + 0.5 - cx))
    return band[1::3]


PHOTOPHORES = _photophores()


def _tones():
    """Base ramp index 0..6 per plate pixel from the dome light, cleaned of lone pixels
    (a pixel unlike all four neighbours takes their most common tone) so the light falls
    in clean clusters instead of noise."""
    tone = np.clip(np.round(0.7 + LIGHT * 5.7), 0, 6).astype(int)
    out = tone.copy()
    for (x, y) in ZONE:
        near = [tone[y + dy, x + dx] for dx, dy in N4 if (x + dx, y + dy) in ZONE]
        if len(near) >= 3 and all(n != tone[y, x] for n in near):
            out[y, x] = max(set(near), key=near.count)
    return out


TONE = _tones()


def _tone(x, y):
    return int(TONE[y, x])


def _plate() -> Image.Image:
    img = canvas(SIZE)
    px = img.load()
    for (x, y), z in ZONE.items():
        t = _tone(x, y)
        c = {
            "rim_lit": NACRE if t >= 4 else PLATE[min(6, t + 3)],
            "rim_mid": PLATE[min(5, t + 1)],
            "rim_dark": PLATE[2] if t <= 2 else PLATE[t - 1],     # reflected light
            "bevel_lit": PLATE[min(6, t + 1)],
            "bevel_dark": PLATE[max(0, t - 2)] if t > 2 else PLATE[0],
            "groove": PLATE[max(0, t - 2)],
            "lip": PLATE[min(6, t + 1)],
            "keel": PLATE[min(6, t + 2)],
            "keel_shadow": PLATE[max(0, t - 1)],
        }.get(z, PLATE[t])
        px[x, y] = rgba(c)
    for x, y in SPEC_PIX:
        px[x, y] = rgba(NACRE)
    return img


# ---- effects ------------------------------------------------------------------------------

def _outline(img: Image.Image) -> Image.Image:
    """1 px outline around the plate: deep ocean navy, a touch lighter (selective
    outline) where the edge faces the top-left light."""
    out = img.copy()
    dst = out.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x] or not any(_in(x + dx, y + dy) for dx, dy in N4):
                continue
            lit = (_in(x, y + 1) or _in(x + 1, y)) and not _in(x, y - 1) and not _in(x - 1, y)
            dst[x, y] = rgba(OUTLINE_LIT if lit else OUTLINE)
    return out


def _cycle(colours, p):
    p %= 1.0
    n = len(colours)
    i = int(p * n)
    return mix(colours[i], colours[(i + 1) % n], p * n - i)


def _iridescence(img, t):
    """A slow colour-shifting sheen (cyan -> violet -> rose) drifting across the plate in
    diagonal bands, strongest on the ridge lips and the lit rim."""
    px = img.load()
    for (x, y), z in ZONE.items():
        if (x, y) in SPEC_PIX:
            continue
        p = UC[y, x] * 0.05 - VC[y, x] * 0.035 + LEVEL[y, x] * 0.3 - t
        k = {"lip": 0.62, "rim_lit": 0.45, "bevel_lit": 0.45, "keel": 0.5, "groove": 0.26, "rim_dark": 0.16,
             "bevel_dark": 0.2}.get(z, 0.24)
        k *= max(0.0, math.sin(2 * math.pi * 2 * p)) ** 1.5
        k *= 0.45 + 0.55 * LIGHT[y, x]          # keep the shadows clean teal, not muddy
        px[x, y] = rgba(mix(px[x, y], _cycle(IRIDESCENT, p), k))


def _glint(img, t, start=0.0, run=0.34, strength=0.62, width=3.2):
    """A soft glint sliding along the plate, crown to root, then resting."""
    if not (start <= t < start + run):
        return
    centre = -3.0 + (L + 5.0) * (t - start) / run
    px = img.load()
    c = rgba(GLINT)
    for (x, y), z in ZONE.items():
        k = max(0.0, 1.0 - abs(UC[y, x] - centre) / width) * strength
        if z in ("rim_dark", "bevel_dark"):
            k *= 0.5
        if k > 0:
            r, g, b, a = px[x, y]
            px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _shimmer(img, t, start=0.45, run=0.4):
    """A shimmer band rolling outward across the growth rings from the focus, sparking on
    the ridge lips and the enamel rim."""
    if not (start <= t < start + run):
        return
    centre = -0.1 + 1.25 * (t - start) / run
    px = img.load()
    for (x, y), z in ZONE.items():
        k = max(0.0, 1.0 - abs(LEVEL[y, x] - centre) / 0.15)
        if k <= 0:
            continue
        spark = z in ("lip", "rim_lit", "bevel_lit") or (z == "plate" and (x + 2 * y) % 5 == 0)
        px[x, y] = rgba(mix(px[x, y], SHIMMER, (0.8 if spark else 0.3) * k))


def _radiance(img, t):
    """The radiant heart of the scale: the nacre highlight flares into a soft star and
    fades again once per loop."""
    glow = wave(t, 0.5)
    px = img.load()
    x0, y0 = SPEC
    for x, y in [(x0 - 1, y0), (x0, y0 - 1), (x0 + 2, y0), (x0 + 1, y0 + 1), (x0, y0 + 2), (x0 + 1, y0 - 1),
                 (x0 - 1, y0 + 1)]:
        if MASK[y, x]:
            px[x, y] = rgba(mix(px[x, y], NACRE, 0.15 + 0.5 * glow))
    if glow > 0.55:
        sparkle(img, x0, y0, (glow - 0.55) / 0.45, "#ffffff", reach=2)


def _glow_spots(img, t):
    """The photophores pulse in a slow wave running along the scale's shadowed edge."""
    px = img.load()
    n = len(PHOTOPHORES)
    for i, (x, y) in enumerate(PHOTOPHORES):
        k = wave(t, -i / max(1, n) * 0.6) ** 2
        px[x, y] = rgba(mix(mix(px[x, y], PHOTO[0], 0.55), PHOTO[1], k))
        for dx, dy in N4:
            q = (x + dx, y + dy)
            if q in ZONE and q not in PHOTOPHORES and DEPTH[q[1], q[0]] >= 2:
                px[q] = rgba(mix(px[q], PHOTO[0], 0.35 * k))


OUTLINED = np.array([[not MASK[y, x] and any(_in(x + dx, y + dy) for dx, dy in N4) for x in range(SIZE)]
                     for y in range(SIZE)])


def _rim_glow(img, t) -> None:
    """A breathing golden 1 px glow just outside the plate's outline, brightest where an
    orbiting hotspot passes."""
    src = img.copy().load()
    px = img.load()
    breath = wave(t)
    cx, cy = _frame_xy(L * 0.45, 0.0)
    hot = 2 * math.pi * t
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            if not any(0 <= x + dx < SIZE and 0 <= y + dy < SIZE and OUTLINED[y + dy, x + dx]
                       for dx, dy in N4):
                continue
            ang = math.atan2(y + 0.5 - cy, x + 0.5 - cx)
            h = 0.5 + 0.5 * math.cos(ang - hot)
            k = 0.25 + 0.45 * breath + 0.3 * h ** 3
            c = rgba(mix(GOLD[1], GOLD[3], min(1.0, 0.1 + 0.8 * k)))
            px[x, y] = (c[0], c[1], c[2], round(90 + 150 * k))


def _sparkles(img, t) -> None:
    """Three sparkles orbiting the scale, each twinkling on its own phase."""
    cx, cy = _frame_xy(L * 0.45, 0.0)
    for k in range(3):
        a = 2 * math.pi * (t + k / 3)
        su, sv = 13.8 * math.cos(a), 11.2 * math.sin(a)
        x = cx + su * AX[0] + sv * UP[0]
        y = cy + su * AX[1] + sv * UP[1]
        xi = max(3, min(SIZE - 4, int(round(x - 0.5))))
        yi = max(3, min(SIZE - 4, int(round(y - 0.5))))
        amt = 0.25 + 0.75 * wave(t * 2, k / 3)
        if img.getpixel((xi, yi))[3] == 255:
            amt *= 0.45
        sparkle(img, xi, yi, amt, GOLD[3] if k != 1 else "#ffffff", reach=2 if amt > 0.6 else 1)


def _frame(t: float) -> Image.Image:
    plate = _plate()
    _iridescence(plate, t)
    _glint(plate, t)
    _shimmer(plate, t)
    _radiance(plate, t)
    _glow_spots(plate, t)
    img = _outline(plate)
    _rim_glow(img, t)
    _sparkles(img, t)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
