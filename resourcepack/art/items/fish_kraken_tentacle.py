"""Kraken Tentacle: a legendary catch of the JoshyMC fishing collection (deep ocean).

A flat 32x32 sprite in the collection pose: the thick severed root sits up-left (a pink
cut face with a pale core) and the arm tapers diagonally down-right, hooking into a tight
curl at the tip. Deep violet skin from the old sprite under a lit back, darker chromatophore
spots along the top, a countershaded lavender underside carrying a row of pale suckers that
scallop the lower edge and shrink toward the tip, and a hue-shifted plum outline.

Animation (LEGENDARY, 20 frames x 2 ticks): a travelling wave sways the arm and flicks the
curled tip, an iridescent violet/pink/cyan sheen rolls down the skin, a glint slides along
the back and a bioluminescent pulse runs down the suckers, while a golden rim glow breathes
just outside the outline, three sparkles orbit the arm and the great root sucker glows
like an eye.
"""
from __future__ import annotations

import math

import numpy as np

from art.kit import animate, canvas, mix, rgba, save_animation, shade, sparkle, sprite, wave

ID = "fish_kraken_tentacle"
NAME = "Kraken Tentacle"
KIND = "item"
MODEL_KEY = "fish/kraken_tentacle"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 20
FRAMETIME = 2

# ---- palette (darkest -> lightest), from the old sprite's deep violets -------------------
SKIN_BASE = "#66288a"
SKIN = [shade(SKIN_BASE, -0.62, 0.12), shade(SKIN_BASE, -0.44, 0.12), shade(SKIN_BASE, -0.24, 0.1),
        SKIN_BASE, mix(shade(SKIN_BASE, 0.2, 0.05), "#c0589a", 0.3), shade(SKIN_BASE, 0.4, 0.06),
        shade(SKIN_BASE, 0.6, 0.06)]
BELLY_BASE = "#b877b0"
BELLY = [shade(BELLY_BASE, -0.4, 0.1), shade(BELLY_BASE, -0.22, 0.1), BELLY_BASE,
         shade(BELLY_BASE, 0.25, 0.06), shade(BELLY_BASE, 0.45, 0.06)]
SUCKER = {"rim": "#f8d6e6", "rimdark": "#d59ac2", "cup": "#a2518a", "hole": "#5e2360", "hi": "#ffffff"}
FLESH = {"ring": "#b24f78", "meat": "#e98aa4", "core": "#fbd2da", "dark": "#7a2c5c"}
OUTLINE = shade(SKIN_BASE, -0.8, 0.12)
OUTLINE_LIT = shade(SKIN_BASE, -0.66, 0.12)
GOLD = ["#c07a10", "#ffbe22", "#ffe066", "#fff6d0"]
IRIDESCENT = ["#b27cff", "#ff8fd0", "#6fe6ff"]     # violet -> pink -> cyan
GLINT = "#fbe9ff"
GLOW = "#9ff6ff"
SHEEN = 0.5
RIM_ALPHA = (16, 190)

# ---- geometry ------------------------------------------------------------------------------
# Rest centre line, root (up-left) to tip; Catmull-Rom smoothed.
_CONTROL = [(7.0, 7.4), (11.0, 10.0), (15.4, 13.0), (19.6, 16.4), (23.2, 19.8), (25.4, 23.2),
           (24.6, 26.4), (21.8, 27.2), (19.9, 25.4), (20.6, 23.4), (22.4, 23.6)]
R_ROOT, R_TIP = 3.9, 0.5
CONTROL = [(16 + (x - 16) * 1.06 + 0.4, 16 + (y - 16) * 1.03 - 0.6) for x, y in _CONTROL]
FACE_DEPTH = 1.35
SWAY = 1.15               # px of sway at the tip
N = 360


def _catmull(points, n):
    pts = [points[0]] + list(points) + [points[-1]]
    out = []
    segs = len(points) - 1
    per = max(2, n // segs)
    for i in range(segs):
        p0, p1, p2, p3 = (np.array(pts[i + k], float) for k in range(4))
        for j in range(per):
            u = j / per
            out.append(0.5 * ((2 * p1) + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u
                              + (-p0 + 3 * p1 - 3 * p2 + p3) * u ** 3))
    out.append(np.array(points[-1], float))
    return np.array(out)


REST = _catmull(CONTROL, N)
_seg = np.linalg.norm(np.diff(REST, axis=0), axis=1)
ARC = np.concatenate([[0.0], np.cumsum(_seg)])
LENGTH = ARC[-1]
S = ARC / LENGTH


def _tangents(line):
    t = np.gradient(line, axis=0)
    return t / np.linalg.norm(t, axis=1, keepdims=True)


REST_T = _tangents(REST)
REST_N = np.stack([-REST_T[:, 1], REST_T[:, 0]], axis=1)   # points to the underside


def radius(s):
    s = np.asarray(s, float)
    r = R_ROOT + (R_TIP - R_ROOT) * s ** 0.72
    return r


RAD = radius(S)


def _line(t: float):
    """The centre line at phase t: a wave travelling root -> tip, growing toward the tip."""
    phase = 2 * math.pi * t - 5.2 * S
    amp = SWAY * S ** 1.6
    return REST + REST_N * (amp * np.sin(phase))[:, None]


LIGHT = np.array([-0.55, -0.7, 0.62])
LIGHT = LIGHT / np.linalg.norm(LIGHT)


SUCKER_SIZES = ((2.25, "L", 3.4, 0.6), (1.25, "M", 2.5, 0.35), (0.0, "S", 1.8, 0.1))
# stamps (dx, dy, part) around the sucker's pixel; light from the top-left: the convex rim
# is lit up-left, the cup inside is shadowed there and lit on its far side
STAMPS = {
    "L": [(-1, -1, "rimdark"), (0, -1, "hi"), (1, -1, "rim"),
          (-1, 0, "hi"), (0, 0, "hole"), (1, 0, "rim"),
          (-1, 1, "rim"), (0, 1, "rim"), (1, 1, "rimdark")],
    "M": [(0, 0, "hi"), (1, 0, "rim"), (0, 1, "rim"), (1, 1, "cup")],
    "S": [(0, 0, "rim")],
}


def _suckers(line, tan, nrm):
    """(pixel, size, arc index) of each sucker along the underside edge."""
    out = []
    a = 3.3
    while a < LENGTH - 1.0:
        i = min(int(np.searchsorted(ARC, a)), len(ARC) - 1)
        r = RAD[i]
        for limit, size, step, inset in SUCKER_SIZES:
            if r >= limit:
                break
        c = line[i] + nrm[i] * (r - inset)
        if size == "M":
            c = c - 0.5
        out.append(((int(math.floor(c[0])), int(math.floor(c[1]))), size, i))
        a += step
    return out


def _body(t: float):
    """Per-pixel description of the arm at phase t: dict (x, y) -> info."""
    line = _line(t)
    tan = _tangents(line)
    nrm = np.stack([-tan[:, 1], tan[:, 0]], axis=1)
    ys, xs = np.mgrid[0:SIZE, 0:SIZE]
    P = np.stack([xs.ravel() + 0.5, ys.ravel() + 0.5], axis=1)
    D = P[:, None, :] - line[None, :, :]
    dist = np.linalg.norm(D, axis=2)
    field = dist - RAD[None, :]
    idx = np.argmin(field, axis=1)
    fmin = field[np.arange(len(P)), idx]
    info = {}
    # cut face at the root: an ellipse across the arm, just before the start
    root_t, root_n = tan[0], nrm[0]
    face_c = line[0] - root_t * 0.3
    for k, (x, y) in enumerate(zip(xs.ravel(), ys.ravel())):
        p = P[k]
        off = p - face_c
        along = off @ root_t
        across = off @ root_n
        q = math.sqrt((along / FACE_DEPTH) ** 2 + (across / (R_ROOT + 0.15)) ** 2)
        i = idx[k]
        o = p - line[i]
        if q <= 1.0:
            info[(x, y)] = {"zone": "face", "q": q, "along": along, "across": across}
            continue
        if fmin[k] > 0 or ((p - line[0]) @ root_t < 0 and i == 0):
            continue
        r = RAD[i]
        v = float(o @ nrm[i]) / r          # + underside, - back
        ox, oy = o / r
        z = math.sqrt(max(0.0, 1 - min(1.0, ox * ox + oy * oy)))
        lam = float(np.array([ox, oy, z]) @ LIGHT)
        info[(x, y)] = {"zone": "skin", "s": float(S[i]), "v": v, "lam": lam, "i": int(i)}
    suckers = _suckers(line, tan, nrm)
    for n, ((cx, cy), size, i) in enumerate(suckers):
        for dx, dy, part in STAMPS[size]:
            x, y = cx + dx, cy + dy
            prev = info.get((x, y))
            if prev and prev["zone"] == "face":
                continue
            info[(x, y)] = {"zone": "sucker", "part": part, "n": n, "s": float(S[i])}
    return info, line, suckers


def _tone(ramp, value):
    value = max(0.0, min(0.999, value))
    return ramp[int(value * len(ramp))]


def _spot(s: float, v: float, i: int) -> bool:
    """Chromatophore spots: small clusters along the back."""
    a = ARC[i]
    k = a % 3.6
    return -0.72 < v < -0.18 and 1.0 < k < 2.3 and 4.0 < a < LENGTH - 5


def _paint(t: float):
    info, line, suckers = _body(t)
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for (x, y), d in info.items():
        z = d["zone"]
        if z == "skin":
            lam, v = d["lam"], d["v"]
            if v > 0.22:        # countershaded underside
                c = _tone(BELLY, 0.18 + lam * 0.85)
                zones[(x, y)] = "belly"
            else:
                band = 0.5 - 0.5 * v            # top of the arm lighter, flank darker
                val = 0.5 * band + 0.5 * (0.06 + lam * 0.95)
                if v < -0.66:
                    val += 0.2       # lit rim along the back
                elif v > 0.02:
                    val -= 0.16      # core shadow above the pale underside
                if _spot(d["s"], v, d["i"]):
                    val -= 0.3
                c = _tone(SKIN, val)
                zones[(x, y)] = "back" if v < -0.2 else "flank"
            px[x, y] = rgba(c)
        elif z == "sucker":
            px[x, y] = rgba(SUCKER[d["part"]])
            zones[(x, y)] = "sucker%d" % d["n"]
        else:   # cut face
            q = d["q"]
            if q > 0.78:
                c = FLESH["ring"] if d["across"] < 0 else FLESH["dark"]
            elif q > 0.42:
                c = FLESH["meat"]
            else:
                c = FLESH["core"]
            px[x, y] = rgba(c)
            zones[(x, y)] = "face"
    # 1 px outline, lighter on edges facing the light
    filled = set(zones)
    dark, lit = rgba(OUTLINE), rgba(OUTLINE_LIT)
    outline = set()
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in filled:
                continue
            if any((x + dx, y + dy) in filled for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                facing = ((x, y + 1) in filled or (x + 1, y) in filled) and \
                    (x, y - 1) not in filled and (x - 1, y) not in filled
                px[x, y] = lit if facing else dark
                outline.add((x, y))
    return img, zones, outline, suckers, line


def _along(p, line):
    d = np.linalg.norm(line - np.array(p, float), axis=1)
    return float(S[int(np.argmin(d))])


def _frame(t: float):
    img, zones, outline, suckers, line = _paint(t)
    px = img.load()
    arc_s = {p: _along((p[0] + 0.5, p[1] + 0.5), line) for p in zones}
    # 1) iridescent sheen: a violet -> pink -> cyan band rolling root -> tip over the skin
    centre = -0.25 + 1.5 * t
    for p, zone in zones.items():
        if zone not in ("back", "flank", "belly"):
            continue
        d = arc_s[p] - centre
        k = max(0.0, 1 - abs(d) / 0.2)
        if k <= 0:
            continue
        h = min(0.999, max(0.0, (d + 0.2) / 0.4)) * 2
        col = mix(IRIDESCENT[int(h)], IRIDESCENT[int(h) + 1], h - int(h))
        strength = SHEEN * k * (0.55 if zone == "belly" else 1.0)
        px[p] = rgba(mix(px[p], col, strength))
    # 2) a soft glint slides along the back (second half of the loop)
    if 0.5 <= t < 0.9:
        centre = -0.05 + 1.1 * (t - 0.5) / 0.4
        for p, zone in zones.items():
            if zone == "back":
                k = max(0.0, 1 - abs(arc_s[p] - centre) / 0.08)
                if k > 0:
                    px[p] = rgba(mix(px[p], GLINT, 0.5 * k))
    # 3) a bioluminescent pulse runs down the suckers (first half)
    if t < 0.5:
        centre = -0.05 + 1.1 * t / 0.5
        for p, zone in zones.items():
            if zone.startswith("sucker"):
                s = float(S[suckers[int(zone[6:])][2]])
                k = max(0.0, 1 - abs(s - centre) / 0.1)
                if k > 0:
                    px[p] = rgba(mix(px[p], GLOW, 0.75 * k))
    # 4) the great root sucker glows like an eye: pulsing golden core, white catchlight
    (cx, cy), size, i = suckers[0]
    pulse = wave(t)
    sparkle(img, cx, cy, 0.25 + 0.6 * pulse, colour=GOLD[2], reach=2)
    for (x, y), tone in (((cx + 1, cy), 2), ((cx, cy + 1), 1), ((cx + 1, cy + 1), 0)):
        px[x, y] = rgba(mix(GOLD[tone], GOLD[tone + 1], pulse))
    px[cx, cy] = rgba("#ffffff")        # catchlight
    # 5) breathing golden rim glow just outside the outline
    breath = wave(t, 0.5)
    filled = set(zones) | outline
    g = rgba(mix(GOLD[1], GOLD[2], breath))
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in filled:
                continue
            if any((x + dx, y + dy) in outline for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[x, y] = (g[0], g[1], g[2], int(RIM_ALPHA[0] + (RIM_ALPHA[1] - RIM_ALPHA[0]) * breath))
    # 6) three sparkles orbit the arm on a tilted ellipse, twinkling as they go
    rot = math.radians(38)
    for k in range(3):
        ang = 2 * math.pi * (t + k / 3)
        ex, ey = 12.0 * math.cos(ang), 5.5 * math.sin(ang)
        sx = 16 + ex * math.cos(rot) - ey * math.sin(rot)
        sy = 16.0 + ex * math.sin(rot) + ey * math.cos(rot)
        amt = 0.3 + 0.7 * wave(2 * t, k / 3)
        sparkle(img, int(round(sx)), int(round(sy)), amt, colour=GOLD[3], reach=2)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
