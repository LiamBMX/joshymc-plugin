"""Primordial Leviathan: a mythical catch of the JoshyMC fishing collection (deep ocean).

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's vivid crimson scheme, its white eye and the tendrils trailing under
the head. A primordial sea monster: an armoured skull plate with a bone brow over a radiant
eye, an open maw with ivory fangs over a massive underslung jaw, a saw-toothed dorsal crest
of four swept spines (crimson at the root, bone at the point) webbed by translucent sails,
a lit crimson back over dark flanks split by glowing fissures, a countershaded coral belly,
a pectoral fin lying over the flank, two chin tendrils ending in glowing lures and a big
heterocercal (shark-like, long upper lobe) tail; fins translucent with painted rays.

Animation (MYTHICAL, 24 frames x 2 ticks): the tail beats with its tips leading, the spines
ripple, the fins and tendrils sway on offset phases. Signature effect: the leviathan's
primordial heartbeat, a pulse of molten light that runs through the fissures between its
scales from head to tail and lights the lure tips. On top: a drifting iridescent sheen
(violet, rose, cyan) across the scales, a breathing golden rim glow just outside the
outline, three golden sparkles orbiting across the fish (in front past the tail, behind it
past the head) and a radiant, pulsing eye.
"""
from __future__ import annotations

import math

import numpy as np

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_primordial_leviathan"
NAME = "Primordial Leviathan"
KIND = "item"
MODEL_KEY = "fish/primordial_leviathan"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 24
FRAMETIME = 2

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean wine-violet, lights coral --
OUTLINE = "#260512"
OUTLINE_LIT = "#3c0819"
FIN_OUTLINE = ("#44091d", 228)
BODY = ["#3a0615", "#5e0a1c", "#850f20", "#ad1824", "#d02a2a", "#ea4d38", "#fb7a52"]
PLATE = ["#330718", "#560c22", "#7c142a", "#a32434", "#c8423e", "#e46e52", "#f7a47a"]
BONE = ["#a9786a", "#dcb898", "#f6e6cc", "#fffaf0"]
BELLY = ["#8e2632", "#b8423c", "#d8664e", "#ee9670"]
MAW = ["#16020b", "#3e0616", "#7a1224"]
FIN = ["#2e0620", "#4c0c30", "#6c163c", "#912648", "#bd4458", "#e8756e"]
EMBER = ["#b8361a", "#e8601e", "#ff912e", "#ffc456", "#ffeaa4", "#fffbea"]
IRIS = ["#b366ff", "#ff74c8", "#5ee8ff"]           # violet, rose, cyan sheen
GOLD = "#ffc23a"
SPARK = "#fff4c4"
EYE = ["#fffdf4", "#ffe68e", "#ffb13a", "#d2461e"]

# ---- geometry ---------------------------------------------------------------------------
# Local frame: u runs from the snout tip toward the tail, v points toward the back.
THETA = math.radians(34.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 23.5                      # snout to tail base
SNOUT = (3.4, 8.4)             # snout tip in the frame
SS = 4


def _smooth(points):
    """Catmull-Rom through (u, value) control points, as a vectorised function of u."""
    us = np.array([p[0] for p in points], float)
    vs = np.array([p[1] for p in points], float)
    dense = np.linspace(us[0], us[-1], 400)
    out = []
    for u in dense:
        i = min(max(int(np.searchsorted(us, u, side="right")) - 1, 0), len(us) - 2)
        p0, p1, p2, p3 = vs[max(i - 1, 0)], vs[i], vs[i + 1], vs[min(i + 2, len(vs) - 1)]
        t = (u - us[i]) / (us[i + 1] - us[i])
        out.append(0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                          + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3))
    table = np.array(out)
    return lambda u: np.interp(u, dense, table)


TOP = _smooth([(0.0, 1.1), (0.8, 1.9), (1.8, 2.6), (3.2, 3.2), (4.8, 3.6), (7.0, 3.85), (9.4, 3.85),
               (11.8, 3.6), (14.2, 3.15), (16.6, 2.6), (18.8, 2.05), (20.8, 1.6), (22.6, 1.35), (23.5, 1.3)])
BOT = _smooth([(0.0, 1.0), (0.8, 1.7), (1.8, 2.4), (3.2, 3.0), (4.8, 3.45), (7.0, 3.75), (9.4, 3.75),
               (11.8, 3.5), (14.2, 3.05), (16.6, 2.5), (18.8, 1.95), (20.8, 1.5), (22.6, 1.3), (23.5, 1.3)])

_ys, _xs = np.mgrid[0:SIZE * SS, 0:SIZE * SS]
_SX, _SY = (_xs + 0.5) / SS - SNOUT[0], (_ys + 0.5) / SS - SNOUT[1]
U = _SX * AX[0] + _SY * AX[1]
V = _SX * UP[0] + _SY * UP[1]
_py, _px = np.mgrid[0:SIZE, 0:SIZE]
UC = (_px + 0.5 - SNOUT[0]) * AX[0] + (_py + 0.5 - SNOUT[1]) * AX[1]
VC = (_px + 0.5 - SNOUT[0]) * UP[0] + (_py + 0.5 - SNOUT[1]) * UP[1]


def _cov(mask: np.ndarray) -> np.ndarray:
    return mask.reshape(SIZE, SS, SIZE, SS).mean(axis=(1, 3))


def _poly(poly) -> np.ndarray:
    """Coverage of a polygon given in local (u, v) coordinates (even-odd rule)."""
    inside = np.zeros(U.shape, bool)
    n = len(poly)
    for i in range(n):
        u0, v0 = poly[i]
        u1, v1 = poly[(i + 1) % n]
        if v0 == v1:
            continue
        cond = (v0 > V) != (v1 > V)
        cross = u0 + (V - v0) * (u1 - u0) / (v1 - v0)
        inside ^= cond & (U < cross)
    return _cov(inside)


def _rot(points, pivot, degrees):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    pu, pv = pivot
    return [(pu + (u - pu) * c - (v - pv) * s, pv + (u - pu) * s + (v - pv) * c) for u, v in points]


def _near(u, v, a, b, reach):
    au, av = a
    bu, bv = b
    du, dv = bu - au, bv - av
    k = max(0.0, min(1.0, ((u - au) * du + (v - av) * dv) / (du * du + dv * dv)))
    return math.hypot(u - au - k * du, v - av - k * dv) <= reach


def _frame_xy(u, v):
    """Frame pixel coordinates of local (u, v)."""
    return (SNOUT[0] + u * AX[0] + v * UP[0], SNOUT[1] + u * AX[1] + v * UP[1])


# Hand-painted head (frame pixels, x from 0): the armoured skull with a bone brow, the eye
# socket (E, painted by the eye code), and the open maw: an upper jaw with a hanging fang,
# the dark throat, and the massive underslung lower jaw jutting past it with its own fang.
# ' ' keeps the procedural body, '.' cuts the pixel out of the silhouette.
HEAD_ORIGIN = (1, 7)
HEAD_ART = [
    "...RRrr ",  # 7
    "..RrSEE ",  # 8
    "..rPsEE ",  # 9
    "..sqpTq ",  # 10
    ".T.MTMMm",  # 11
    ".JJMMmm ",  # 12
    "..kJJj  ",  # 13
    "...kkj  ",  # 14
]
HEAD_COLOURS = {
    "R": BONE[1], "r": PLATE[6], "P": PLATE[5], "p": PLATE[4], "q": PLATE[3], "s": PLATE[2], "S": PLATE[1],
    "T": BONE[2], "t": BONE[0], "M": MAW[0], "m": MAW[1],
    "J": BELLY[3], "j": BELLY[2], "k": BELLY[1], "E": PLATE[1],
}
HEAD: dict = {}
BODY_CUT: list = []
for _j, _row in enumerate(HEAD_ART):
    for _i, _ch in enumerate(_row):
        _key = (HEAD_ORIGIN[0] + _i, HEAD_ORIGIN[1] + _j)
        if _ch == ".":
            BODY_CUT.append(_key)
        elif _ch != " ":
            HEAD[_key] = HEAD_COLOURS[_ch]


def _mask() -> np.ndarray:
    mask = _cov((U >= 0) & (U <= SL + 0.3) & (V <= TOP(U)) & (V >= -BOT(U))) >= 0.5
    for x, y in HEAD:
        mask[y, x] = True
    for x, y in BODY_CUT:
        mask[y, x] = False
    return mask


MASK = _mask()


def _bands(mask: np.ndarray):
    cols = {x: np.nonzero(mask[:, x])[0] for x in range(SIZE) if mask[:, x].any()}
    rows = {y: np.nonzero(mask[y, :])[0] for y in range(SIZE) if mask[y, :].any()}
    kb = np.full(mask.shape, -1)
    kv = np.full(mask.shape, -1)
    for y, xs in rows.items():
        for x in xs:
            kb[y, x] = min(y - cols[x][0], xs[-1] - x)
            kv[y, x] = min(cols[x][-1] - y, x - xs[0])
    return kb, kv


KB, KV = _bands(MASK)

# ---- fins: (polygon, rays) in local coordinates; v > 0 is the back ----------------------
def _local(x, y):
    """Local (u, v) of frame point (x, y)."""
    dx, dy = x - SNOUT[0], y - SNOUT[1]
    return (dx * AX[0] + dy * AX[1], dx * UP[0] + dy * UP[1])


TAIL_PIVOT = (22.4, 20.6)      # the peduncle, in frame pixels


def _tail(sway: float):
    """Heterocercal tail (drawn in frame pixels): a long swept upper lobe and a shorter,
    broad lower lobe. It beats about the peduncle, the tips leading."""
    pts = [(21.4, 19.2), (23.4, 17.6), (25.6, 15.6), (27.4, 13.9), (28.5, 13.2), (28.2, 14.8), (27.1, 17.2),
           (25.4, 19.4), (24.7, 20.8), (25.6, 22.8), (27.0, 25.4), (26.9, 26.6), (25.6, 25.2), (23.8, 23.4),
           (21.8, 22.4)]
    rays = [((22.6, 20.0), (28.0, 13.8), FIN[5]), ((22.6, 21.0), (26.6, 25.9), FIN[4])]
    a = math.radians(9.0 * sway)
    c, s_ = math.cos(a), math.sin(a)
    px, py = TAIL_PIVOT

    def turn(x, y):
        r = math.hypot(x - px, y - py) / 7.0
        k = min(1.0, r) * 0.6 + 0.4        # the tips swing further than the root
        ca, sa = math.cos(a * k), math.sin(a * k)
        return _local(px + (x - px) * ca - (y - py) * sa, py + (x - px) * sa + (y - py) * ca)

    return [turn(*q) for q in pts], [(turn(*r[0]), turn(*r[1]), r[2]) for r in rays]


CREST_SPINES = [(5.0, 4.4), (8.8, 5.4), (12.6, 4.6), (16.2, 3.2)]   # (u of the base centre, height)


def _crest(t: float):
    """The dorsal crest: four swept-back bone spines, each webbed by a translucent sail,
    with open water between them (a saw-toothed silhouette). A ripple runs down the
    spines: each tip leans back and forth on its own phase."""
    fins = []
    for i, (u, h) in enumerate(CREST_SPINES):
        lean = 0.5 * math.sin(2 * math.pi * t - i * 1.3)
        tip = (u + 0.2 + lean, TOP(u) + h)
        pts = [(u - 1.0, TOP(u - 1.0) - 0.4), tip, (u + 1.5, TOP(u + 1.5) - 0.4)]
        fins.append((pts, [((u - 0.5, TOP(u - 0.5) - 0.2), tip, BONE[2] if i < 2 else BONE[1])]))
    return fins


def _pectoral(flap: float):
    """The near pectoral fin, lying over the lower flank behind the gill and poking a
    pixel past the belly."""
    pivot = (7.4, -1.5)
    pts = [(6.8, -0.9), (8.4, -1.0), (12.4, -BOT(12.4) - 0.6), (11.2, -BOT(11.2) - 1.3), (7.2, -2.5)]
    rays = [((7.2, -1.2), (12.0, -BOT(12.0) - 0.7), BONE[1])]
    a = 8.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _pelvic(flap: float):
    pivot = (15.2, -BOT(15.2))
    pts = [(14.6, -BOT(14.6) + 0.6), (16.2, -BOT(16.2) + 0.5), (17.6, -BOT(17.6) - 1.7), (16.0, -BOT(16.0) - 1.6)]
    return _rot(pts, pivot, 8.0 * flap), []


def _anal(ripple: float):
    pts = [(18.8, -BOT(18.8) + 0.6), (19.8, -BOT(19.8) - 1.6 - 0.3 * ripple), (22.0, -BOT(22.0) - 0.9),
           (22.4, -BOT(22.4) + 0.5)]
    rays = [((19.8, -BOT(19.8)), (20.0, -BOT(20.0) - 1.4 - 0.3 * ripple), FIN[2])]
    return pts, rays


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=226, lit=None, spines=False):
    """Paint a translucent fin: membrane plus rays. With lit, fin pixels whose upper or
    left neighbour is open water catch the top-left light. With spines, the rays are
    solid bone that runs all the way out to the tips, even past thin membrane."""
    poly, rays = fin
    cov = _poly(poly)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            u, v = UC[y, x], VC[y, x]
            ray = None
            for ra, rb, rc in rays:
                if (cov[y, x] >= 0.85 or spines) and _near(u, v, ra, rb, 0.5 if spines else 0.45):
                    ray = rc
                    break
            if cov[y, x] < cov_min and ray is None:
                continue
            if ray is not None:
                colour, a = ray, ray_alpha
                if spines:     # crimson at the root, bone toward the tip, white at the point
                    (au, av), (bu, bv) = ra, rb
                    k = ((u - au) * (bu - au) + (v - av) * (bv - av)) / ((bu - au) ** 2 + (bv - av) ** 2)
                    colour = FIN[5] if k < 0.3 else BONE[0] if k < 0.55 else ray if k < 0.85 else BONE[3]
            else:
                colour, a = membrane, alpha
                if lit and not MASK[y, x] and ((y > 0 and cov[y - 1, x] < cov_min and not MASK[y - 1, x])
                                               or (x > 0 and cov[y, x - 1] < cov_min and not MASK[y, x - 1])):
                    colour = lit
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)


# ---- tendrils: two long barbels trailing from the chin -----------------------------------
def _tendrils(t: float):
    """Pixels -> shade index (0 tip .. 2 root) of the two chin tendrils, swaying on a
    travelling wave so they trail and curl like whiskers in a current."""
    out = {}
    roots = [((4.4, 15.2), 8.0, 0.0, 0.28), ((6.3, 15.8), 6.0, 2.1, 0.42)]
    for (x0, y0), length, phase, lean in roots:
        steps = int(length * 6)
        for i in range(steps + 1):
            s = i / steps
            d = s * length
            sway = 1.2 * s * math.sin(2 * math.pi * t - d * 0.5 + phase)
            x = x0 + lean * d + 1.6 * s * s + sway
            y = y0 + d * 0.95
            key = (int(math.floor(x)), int(math.floor(y)))
            if not (0 <= key[0] < SIZE and 0 <= key[1] < SIZE - 1) or MASK[key[1], key[0]]:
                continue
            tone = 2 if s < 0.3 else 1 if s < 0.8 else 0
            out[key] = min(out.get(key, 9), tone) if key in out else tone
    return out


# ---- body -------------------------------------------------------------------------------
GILL_U = 6.0


def _gill(u, v):
    return GILL_U - 0.07 * v * v + 0.25 * v


def _lateral(u):
    return 0.35 + 0.45 * math.sin(u * 0.95 + 0.6)


FISSURE_BRANCHES = [((10.0, None), (11.4, 2.8)), ((13.0, None), (14.2, -2.4)),
                    ((16.0, None), (17.2, 2.0)), ((18.8, None), (19.8, -1.5))]


def _fissures() -> dict:
    """Glowing cracks: a wavering lateral fissure plus short branches, as pixel -> u."""
    out = {}
    for y in range(SIZE):
        for x in range(SIZE):
            if not MASK[y, x] or KB[y, x] < 1 or KV[y, x] < 1:
                continue
            u, v = UC[y, x], VC[y, x]
            if u < GILL_U + 1.3 or u > SL - 0.4:
                continue
            hit = abs(v - _lateral(u)) < 0.52
            if not hit:
                for (bu, _), (eu, ev) in FISSURE_BRANCHES:
                    if _near(u, v, (bu, _lateral(bu)), (eu, ev), 0.42):
                        hit = True
                        break
            if hit:
                out[(x, y)] = u
    return out


FISSURE = _fissures()


def _ganoid(x: int, y: int) -> bool:
    """Scale marks: a staggered lattice of single pixels (clean clusters, not noise)."""
    return (x + 2 * y) % 4 == 0


EYE_AT = (6, 8)          # top-left of the 2x2 eye
EYE_PX = {(0, 0): EYE[0], (1, 0): EYE[1], (0, 1): EYE[1], (1, 1): EYE[2]}


def _zone(x: int, y: int) -> str:
    u, v = UC[y, x], VC[y, x]
    if (x, y) in HEAD:
        return "head"
    if u < _gill(u, v):
        return "plate"
    return "body"


def _body_colour(x: int, y: int) -> tuple[str, str]:
    kb, kv = int(KB[y, x]), int(KV[y, x])
    h = kb + kv + 1
    u, v = UC[y, x], VC[y, x]
    if _zone(x, y) == "plate":
        g = _gill(u, v)
        if u > g - 0.8:
            return PLATE[1], "seam"                      # dark edge of the gill cover
        if kb == 0:
            return PLATE[6], "rim"
        if v < -1.4:
            return (BELLY[2] if kv >= 1 else BELLY[1]), "jaw"
        if kb == 1:
            return PLATE[5], "plate"
        if u > g - 1.8:
            return PLATE[5] if v > 0.8 else PLATE[4], "plate"   # lit lip of the gill cover
        return (PLATE[4] if kb == 2 else PLATE[3]), "plate"
    # the scaled body: lit rim, crimson back, dark flanks with the fissures, coral belly
    nb = 2 if h > 6 else 1
    seam = _ganoid(x, y)
    if kb == 0:
        return (BODY[6] if u < 12 else BODY[5]), "rim"
    if kv == 0:
        return BELLY[0], "belly"
    if u < _gill(u, v) + 0.9:
        return (BODY[5] if v > -1.4 else BELLY[2]), "gillback"   # light catching behind the gill
    if kb <= nb:
        if kb == 1:
            return (BODY[4] if seam else BODY[5]), "back"
        return BODY[4], "back"
    if kv == 1:
        return BELLY[2], "belly"
    if kv == 2 and h > 6:
        return BELLY[1], "belly"
    if kb == nb + 1:
        return (BODY[4] if seam else BODY[3]), "flank"
    if v > _lateral(u):
        return BODY[2], "flank"
    return (BODY[2] if seam else BODY[1]), "flank"


def _body():
    img = canvas(SIZE)
    zones = {}
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if MASK[y, x]:
                colour, zone = _body_colour(x, y)
                px[x, y] = rgba(colour)
                zones[(x, y)] = zone
    for (x, y), colour in HEAD.items():
        px[x, y] = rgba(colour)
        zones[(x, y)] = "head"
    ex, ey = EYE_AT
    for (dx, dy), colour in EYE_PX.items():
        px[ex + dx, ey + dy] = rgba(colour)
        zones[(ex + dx, ey + dy)] = "eye"
    return img, zones


# ---- effects ----------------------------------------------------------------------------
def _heartbeat(u: float, t: float) -> float:
    """0..1: a pulse of molten light running head to tail once per loop (periodic in t)."""
    period = 26.0
    c = 4.0 + period * t
    d = (u - c) % period
    d = min(d, period - d)
    return math.exp(-d * d / 7.0)


def _fissure_pass(img, zones, t):
    px = img.load()
    breath = 0.12 * wave(t * 2)
    halo = {}
    for (x, y), u in FISSURE.items():
        k = min(1.0, 0.3 + breath + 0.8 * _heartbeat(u, t))
        idx = k * (len(EMBER) - 1.001)
        i = int(idx)
        colour = mix(EMBER[i], EMBER[i + 1], idx - i)
        px[x, y] = rgba(colour)
        zones[(x, y)] = "fissure"
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            if q in FISSURE or zones.get(q) not in ("flank", "back", "belly"):
                continue
            halo[q] = max(halo.get(q, 0.0), k)
    for (x, y), k in halo.items():
        px[x, y] = rgba(mix(px[x, y], EMBER[1], 0.2 + 0.45 * k * k))


def _iridescence(img, zones, t):
    """A slow sheen drifting tail -> head across the scale seams, its hue cycling
    violet -> rose -> cyan with its position."""
    px = img.load()
    period = 30.0
    c = SL + 4.0 - period * t
    for (x, y), zone in zones.items():
        if zone not in ("flank", "back", "rim"):
            continue
        u = UC[y, x]
        d = (u - c) % period
        d = min(d, period - d)
        k = max(0.0, 1.0 - d / 4.0)
        if k <= 0:
            continue
        seam = _ganoid(x, y) or zone == "rim"
        hue = (u / 7.0 + t) % 1.0 * 3
        i = int(hue)
        colour = mix(IRIS[i % 3], IRIS[(i + 1) % 3], hue - i)
        px[x, y] = rgba(mix(px[x, y], colour, (0.7 if seam else 0.28) * k))


def _eye_glow(img, zones, t):
    """The radiant eye: a warm glow pulses in the ring of pixels around it."""
    px = img.load()
    ex, ey = EYE_AT
    k = 0.12 + 0.3 * wave(t * 2)
    for dx in range(-1, 3):
        for dy in range(-1, 3):
            q = (ex + dx, ey + dy)
            if zones.get(q) in (None, "eye") or (dx in (-1, 2) and dy in (-1, 2)):
                continue
            px[q] = rgba(mix(px[q], EYE[2], k))
    # the catchlight flares a little at the peak
    if wave(t * 2) > 0.8:
        px[ex - 1, ey - 1] = rgba(mix(px[ex - 1, ey - 1], EYE[1], 0.35))


def _outline(img, solid):
    src = img.load()
    out = img.copy()
    dst = out.load()
    fr, fg, fb, _ = rgba(FIN_OUTLINE[0])
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                    if 0 <= x + dx < SIZE and 0 <= y + dy < SIZE and src[x + dx, y + dy][3]]
            if not near:
                continue
            if any(solid[ny, nx] for nx, ny in near):
                lit = (y + 1 < SIZE and src[x, y + 1][3]) or (x + 1 < SIZE and src[x + 1, y][3])
                dst[x, y] = rgba(OUTLINE_LIT if lit and not (y > 0 and src[x, y - 1][3]) else OUTLINE)
            else:
                dst[x, y] = (fr, fg, fb, FIN_OUTLINE[1])
    return out


def _rim_glow(img, t):
    """A breathing golden 1 px glow just outside the outline."""
    src = img.load()
    out = img.copy()
    dst = out.load()
    g = rgba(GOLD)
    for y in range(1, SIZE - 1):
        for x in range(1, SIZE - 1):
            if src[x, y][3]:
                continue
            if not any(src[x + dx, y + dy][3] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                continue
            # keep narrow gaps (between spines, tail lobes) open
            if (src[x - 1, y][3] and src[x + 1, y][3]) or (src[x, y - 1][3] and src[x, y + 1][3]):
                continue
            # brighter on the lit (upper-left facing) side
            lit = src[x, y + 1][3] or src[x + 1, y][3]
            k = wave(t * 2 + (x + y) / 48.0)
            a = round((40 if lit else 20) + (170 if lit else 110) * k)
            dst[x, y] = (g[0], g[1], g[2], a)
    return out


ORBIT_C, ORBIT_A, ORBIT_B = (15.4, 15.2), 7.5, 12.0   # along the fish, across it


def _orbiters(img, solid, t):
    """Three sparkles on an ellipse standing across the fish: they swing in front of it
    past the tail and behind it past the head (hidden where they overlap it)."""
    for i in range(3):
        a = 2 * math.pi * (t + i / 3.0)
        lu, lv = ORBIT_A * math.cos(a), ORBIT_B * math.sin(a)
        x = ORBIT_C[0] + lu * AX[0] + lv * UP[0] * 1.0
        y = ORBIT_C[1] + lu * AX[1] + lv * UP[1] * 1.0
        xi, yi = int(round(x)), int(round(y))
        xi, yi = max(2, min(SIZE - 3, xi)), max(2, min(SIZE - 3, yi))
        behind = math.cos(a) < 0
        amount = 0.45 + 0.55 * wave(t * 3 + i / 3.0)
        if behind and solid[yi, xi]:
            continue
        if abs(xi - EYE_AT[0] - 0.5) < 3 and abs(yi - EYE_AT[1] - 0.5) < 3:
            continue           # never cross the eye
        layer = canvas(SIZE)
        sparkle(layer, xi, yi, amount, SPARK, reach=2 if not behind else 1)
        if behind:
            lp = layer.load()
            for yy in range(SIZE):
                for xx in range(SIZE):
                    if solid[yy, xx] or img.getpixel((xx, yy))[3] > 200:
                        lp[xx, yy] = (0, 0, 0, 0)
        img.alpha_composite(layer)


def _frame(t: float):
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))

    img = canvas(SIZE)
    _paint_fin(img, _tail(sway), FIN[3], 212, cov_min=0.42, lit=FIN[5])
    for spine in _crest(t):
        _paint_fin(img, spine, FIN[3], 214, cov_min=0.45, ray_alpha=255, lit=FIN[5], spines=True)
    _paint_fin(img, _anal(ripple), FIN[3], 206, lit=FIN[5])
    body, zones = _body()
    _fissure_pass(body, zones, t)
    _iridescence(body, zones, t)
    _eye_glow(body, zones, t)
    img.alpha_composite(body)
    front = canvas(SIZE)
    _paint_fin(front, _pectoral(flap), FIN[3], 214, lit=FIN[5])
    _paint_fin(front, _pelvic(flap), FIN[3], 210, lit=FIN[5])
    img.alpha_composite(front)
    solid = MASK.copy()
    img = _outline(img, solid)
    img = _rim_glow(img, t)
    # the chin tendrils: fine whiskers over open water, no outline so they stay thin
    px = img.load()
    tip = mix(EMBER[2], EMBER[5], _heartbeat(-3.0, t))            # glowing lure tips
    tones = [tip, BODY[4], BODY[3]]
    for (x, y), tone in _tendrils(t).items():
        if px[x, y][3] < 200:
            c = rgba(tones[tone])
            px[x, y] = (c[0], c[1], c[2], 255 if tone == 0 else 236)
    _orbiters(img, np.array([[img.getpixel((x, y))[3] > 0 for x in range(SIZE)] for y in range(SIZE)]), t)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
