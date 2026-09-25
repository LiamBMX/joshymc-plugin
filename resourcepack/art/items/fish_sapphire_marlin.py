"""Sapphire Marlin: a rare ocean fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's all-sapphire colour scheme and white eye. A true billfish: a long
spear bill over a short pale lower jaw, a forehead rising into the tall sickle first dorsal
fin that runs on as a low ridge, a sleek torpedo body tapering to a thin keeled tail stalk
and a big lunate (crescent) tail. Deep indigo back under an ice-blue rim light, gem-blue
flanks crossed by the pale cobalt vertical bars of a blue marlin, a countershaded ice-white
belly, a long scythe pectoral fin swept back under the chest and a small pointed anal fin;
fins translucent sapphire with painted rays and ice-blue lit leading edges.

Animation (RARE, 12 frames x 3 ticks): the crescent tail sweeps, the dorsal tip ripples and
the pectoral fin flaps about a pixel on offset sine phases; a soft glint slides
along the back (frames 0-5), then a blue-cyan sheen of sparkling scales rolls down the body
(6-11), while three sparkles twinkle around the fish on staggered phases.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_sapphire_marlin"
NAME = "Sapphire Marlin"
KIND = "item"
MODEL_KEY = "fish/sapphire_marlin"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palettes (darkest -> lightest): sapphire blues, shadows lean indigo, lights lean ice ----
OUTLINE = "#0a0e3a"
FIN_OUTLINE = ("#1a2470", 228)
PAL = {
    # back: rim light (front, middle, rear), deep indigo back, keel
    "R": "#9ad8ff", "r": "#62a4f2", "q": "#3a6cd4", "B": "#16248a", "b": "#1e39a8", "k": "#0e155a",
    # flanks: gem horizon, sapphire flank, deeper lower flank; pale cobalt bars
    "H": "#4f96f7", "F": "#3572e6", "f": "#2957cc", "X": "#8cc6ff", "x": "#6aa6f4", "y": "#2d56c4",
    # belly (countershaded ice blue)
    "W": "#f3f9ff", "w": "#d4e5ff", "v": "#a8c2f4", "u": "#7488d8",
    # head: eye with a catchlight, mouth, gill cover edge and its lit rim, cheek
    "E": "#060922", "e": "#ffffff", "m": "#0c1344", "g": "#14206e", "G": "#77b8fb", "c": "#5aa0f6",
    # bill: lit top, body, shaded root
    "S": "#9fd2fb", "s": "#4a80e0", "z": "#1a2c86",
}
# Fin colours: membrane, rays, lit edge; alphas keep them a little translucent.
FIN = {"membrane": "#2a4cc8", "ray": "#142890", "lit": "#8ec8fc"}
MEMBRANE_ALPHA, RAY_ALPHA, EDGE_ALPHA = 208, 228, 220
GLINT = "#cdf6ff"
SHEEN = "#8ef0ff"
SPARK = "#f2fdff"
TWINKLE = "#b8eeff"

# ---- geometry (pixels) --------------------------------------------------------------------
# Local frame: u runs from the snout (bill root) toward the tail, v points toward the back.
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))
UP = (math.sin(THETA), -math.cos(THETA))
SL = 17.0                      # snout to tail base
SNOUT = (9.6, 11.0)
BILL_LEN = 6.9
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


# Half-depths above (TOP) and below (BOT) the axis: a forehead hump, then a long torpedo taper.
TOP = _smooth([(0.0, 0.6), (1.0, 1.4), (2.2, 2.35), (3.6, 3.2), (5.2, 3.75), (7.0, 3.95), (9.0, 3.8),
               (11.0, 3.3), (13.0, 2.55), (15.0, 1.65), (17.0, 0.9)])
BOT = _smooth([(0.0, 0.75), (1.0, 1.35), (2.2, 2.15), (3.6, 2.95), (5.2, 3.5), (7.0, 3.75), (9.0, 3.6),
               (11.0, 3.05), (13.0, 2.3), (15.0, 1.45), (17.0, 0.85)])

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
    du, dv = b[0] - au, b[1] - av
    k = max(0.0, min(1.0, ((u - au) * du + (v - av) * dv) / (du * du + dv * dv)))
    return math.hypot(u - au - k * du, v - av - k * dv) <= reach


def _to_xy(u, v):
    return SNOUT[0] + u * AX[0] + v * UP[0], SNOUT[1] + u * AX[1] + v * UP[1]


def _t(u):
    return float(TOP(u))


def _b(u):
    return float(BOT(u))


# ---- body mask, bill and column bands -----------------------------------------------------
BODY_ADD: list = []
BODY_CUT: list = []


def _mask() -> np.ndarray:
    mask = _cov((U >= 0) & (U <= SL + 0.3) & (V <= TOP(U)) & (V >= -BOT(U))) >= 0.5
    for x, y in BODY_ADD:
        mask[y, x] = True
    for x, y in BODY_CUT:
        mask[y, x] = False
    return mask


MASK = _mask()


def _bill() -> dict:
    """The spear: a clean 1 px staircase from the snout, doubled near its root."""
    out = {}
    root_x, root_y = _to_xy(0.3, 0.45)
    tip_x, _ = _to_xy(-BILL_LEN, 0.45)
    slope = math.tan(THETA)
    x = math.floor(root_x)
    while x >= math.floor(tip_x):
        y = math.floor(root_y - (root_x - (x + 0.5)) * slope)
        if not MASK[y, x]:
            out[(x, y)] = "S" if x < root_x - 3.5 else "s"
            if root_x - x < 3.0 and not MASK[y + 1, x]:
                out[(x, y + 1)] = "z"
        x -= 1
    return out


BILL = _bill()


def _column_letter(u: float, k: int, h: int) -> str:
    """Letter for the k-th pixel (from the top) of a body column h pixels deep."""
    j = h - 1 - k
    if k == 0:
        return "R" if u < 6.0 else "r" if u < 12.0 else "q"
    if j == 0:
        return "u"
    if k == 1:
        return "B"
    if k == 2 and h >= 7:
        return "b"
    if k == (3 if h >= 7 else 2):
        return "H"
    if j == 1:
        return "W"
    if j == 2 and h >= 6:
        return "v"
    return "F" if k <= (h - 1) // 2 else "f"


BARS = (7.2, 9.4, 11.6, 13.8)       # u positions of the pale vertical bars
BAR_OF = {"b": "y", "H": "X", "F": "X", "f": "x"}


def _body_letters():
    letters, depth = {}, {}
    for x in range(SIZE):
        ys = np.nonzero(MASK[:, x])[0]
        if not len(ys):
            continue
        top, h = int(ys[0]), int(ys[-1] - ys[0] + 1)
        for k in range(h):
            y = top + k
            ch = _column_letter(UC[y, x], k, h)
            if any(abs(UC[y, x] - b) < 0.5 for b in BARS):
                ch = BAR_OF.get(ch, ch)
            letters[(x, y)] = ch
            depth[(x, y)] = (k, h)
    letters.update(HEAD)
    letters.update(BILL)
    return letters, depth


# Head details over the banded body: eye with catchlight, mouth, pale jaw, gill cover, keel.
HEAD = {
    (9, 11): "R", (10, 11): "b", (11, 11): "B", (10, 12): "m", (11, 12): "c", (10, 13): "W", (11, 13): "W",
    (12, 11): "e", (13, 11): "E", (12, 12): "E", (13, 12): "E", (14, 12): "c",
    (15, 12): "g", (15, 13): "g", (15, 14): "g", (14, 15): "v", (16, 13): "G", (16, 14): "G",
}
# The near pectoral fin (a scythe blade swept back under the chest) and the small pointed
# anal fin, as hand-placed pixels: "L" lit leading edge, "p" membrane, "P" ray / trailing edge.
PECTORAL = {
    "spread": {(14, 18): "L", (15, 19): "p", (15, 20): "p", (16, 20): "P", (16, 21): "P"},
    "folded": {(14, 18): "L", (15, 19): "p", (16, 19): "p", (16, 20): "P", (17, 21): "P"},
}
ANAL = {(20, 20): "L", (21, 20): "p", (21, 21): "P"}
LOW_FIN = {"L": ("#8ec8fc", 222), "p": ("#3f74de", 212), "P": ("#1c3aa6", 228)}

BODY, DEPTH = _body_letters()
SOLID = set(BODY)

# ---- fins: (polygon, rays) in local coordinates; v > 0 is the back ------------------------
TAIL_ROOT = (23.9, 19.1)       # screen position of the tail root (the fork's vertex)
TAIL_AXIS = 4.0               # the crescent opens along this screen angle (degrees below +x)
TAIL_HORN = [(-0.4, 0.9), (0.9, 3.0), (2.2, 4.9), (3.5, 6.2), (4.3, 6.7), (3.4, 5.1), (2.5, 3.3),
             (1.9, 1.6), (1.7, 0.0)]


def _uv(x, y):
    dx, dy = x - SNOUT[0], y - SNOUT[1]
    return dx * AX[0] + dy * AX[1], dx * UP[0] + dy * UP[1]


def _tail(sway: float):
    """The lunate crescent: convex leading edges, concave trailing edge and two thin horns
    swept back, laid out in screen space around the root and flexed by `sway`."""
    phi = math.radians(TAIL_AXIS + 7.0 * sway)
    ax, bx = (math.cos(phi), math.sin(phi)), (math.sin(phi), -math.cos(phi))

    def scr(a, b):
        return _uv(TAIL_ROOT[0] + a * ax[0] + b * bx[0], TAIL_ROOT[1] + a * ax[1] + b * bx[1])

    upper = [scr(a, b) for a, b in TAIL_HORN]
    lower = [scr(a, -b) for a, b in reversed(TAIL_HORN[:-1])]
    rays = [(scr(0.2, 0.6), scr(3.9, 6.1)), (scr(0.2, -0.6), scr(3.9, -6.1))]
    return upper + lower, rays


def _dorsal(ripple: float):
    """The tall sickle first dorsal fin, running on as a low ridge down the back."""
    tip = (6.4 + 0.5 * ripple, _t(6.4) + 6.6)
    pts = [(3.7, _t(3.7) - 0.7), (4.6, _t(4.6) + 2.8), tip,
           (6.9 + 0.35 * ripple, _t(6.9) + 4.2), (7.4, _t(7.4) + 2.3), (8.5, _t(8.5) + 1.2),
           (10.0, _t(10.0) + 0.85), (13.4, _t(13.4) + 0.75), (14.2, _t(14.2) - 0.6)]
    rays = [((5.6, _t(5.6)), (6.6 + 0.4 * ripple, _t(6.6) + 4.2)),
            ((10.5, _t(10.5)), (10.9, _t(10.9) + 0.9))]
    return pts, rays


def _fin_pixels(fin, membrane, cov_min=0.45, lit=True, over_body=False) -> dict:
    """(x, y) -> (colour, alpha) for a translucent fin: membrane, painted rays and a lit edge
    where the fin faces the top-left light across open water."""
    poly, rays = fin
    cov = _poly(poly)
    inside = cov >= cov_min
    if not over_body:
        inside &= ~MASK
    out = {}
    for y in range(SIZE):
        for x in range(SIZE):
            if not inside[y, x]:
                continue
            u, v = UC[y, x], VC[y, x]
            colour, a = membrane, MEMBRANE_ALPHA
            if cov[y, x] >= 0.75 and any(_near(u, v, ra, rb, 0.45) for ra, rb in rays):
                colour, a = FIN["ray"], RAY_ALPHA
            if lit and ((y > 0 and not inside[y - 1, x] and not MASK[y - 1, x])
                        or (x > 0 and not inside[y, x - 1] and not MASK[y, x - 1])):
                colour, a = FIN["lit"], EDGE_ALPHA
            out[(x, y)] = (colour, a)
    return out


# ---- animation ----------------------------------------------------------------------------

def _lattice(x: int, y: int) -> bool:
    return y % 2 == 1 and (x + y // 2) % 2 == 0


TWINKLES = [((26, 8), 0.0, 2), ((7, 21), 0.34, 2), ((5, 3), 0.67, 1)]


def _frame(t: float) -> Image.Image:
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))
    img = canvas(SIZE)
    px = img.load()

    def put(x, y, colour, alpha=255):
        c = rgba(colour)
        px[x, y] = (c[0], c[1], c[2], alpha)

    # medial fins behind the body (tail and dorsal)
    back = {}
    for fin, membrane, cmin in ((_tail(sway), FIN["membrane"], 0.4), (_dorsal(ripple), FIN["membrane"], 0.45)):
        for key, val in _fin_pixels(fin, membrane, cmin).items():
            back.setdefault(key, val)
    for (x, y), (colour, a) in back.items():
        put(x, y, colour, a)

    # body, with the glint (first half of the loop) and the sheen (second half)
    g_centre = -1.0 + (SL + 3.0) * (t / 0.5)
    s_centre = -1.0 + (SL + 3.0) * ((t - 0.5) / 0.5)
    for (x, y), ch in BODY.items():
        colour = PAL[ch]
        special = ch in ("E", "e", "m", "S", "s", "z")
        if (x, y) in DEPTH and not special:
            kk, h = DEPTH[(x, y)]
            u = UC[y, x]
            if t < 0.5 and kk <= 3:
                k = max(0.0, 1.0 - abs(u - g_centre) / 3.2) * 0.85 * (1.0 - kk / 5.0)
                colour = mix(colour, GLINT, k)
            elif t >= 0.5:
                k = max(0.0, 1.0 - abs(u - s_centre) / 3.4)
                if k > 0:
                    spark = _lattice(x, y) and 2 <= kk < h - 1
                    colour = mix(colour, SPARK if spark else SHEEN, (1.0 if spark else 0.5) * min(1.0, k * 1.4))
        put(x, y, colour)

    # the near pectoral fin and the anal fin below the belly line
    pect = dict(PECTORAL["folded" if math.sin(2 * math.pi * 2 * t + 1.0) > 0.3 else "spread"])
    pect.update(ANAL)
    for (x, y), ch in pect.items():
        put(x, y, *LOW_FIN[ch])

    # 1 px outline: deep navy beside the body and bill, softer where it only borders fins
    src = img.copy().load()
    dark = rgba(OUTLINE)
    soft = (*rgba(FIN_OUTLINE[0])[:3], FIN_OUTLINE[1])
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                    if 0 <= x + dx < SIZE and 0 <= y + dy < SIZE and src[x + dx, y + dy][3]]
            if near:
                px[x, y] = dark if any((nx, ny) in SOLID for nx, ny in near) else soft

    # twinkling sparkles around the fish, one after another
    for (x, y), off, reach in TWINKLES:
        amount = max(0.0, (wave(t, off) - 0.45) / 0.55)
        sparkle(img, x, y, amount, colour=TWINKLE, reach=reach)
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}


def _preview(path: str):
    """Debug: frames upscaled on a mid-grey backdrop, with a pixel grid on frame 0."""
    frames = animate(_frame, FRAMES)
    sheet = Image.new("RGBA", (SIZE * 10 * 4, SIZE * 10 * 3), (138, 138, 138, 255))
    for i, f in enumerate(frames):
        sheet.alpha_composite(f.resize((SIZE * 10, SIZE * 10), Image.NEAREST),
                              ((i % 4) * SIZE * 10, (i // 4) * SIZE * 10))
    sheet.save(path)
    one = Image.new("RGBA", (SIZE * 20, SIZE * 20), (138, 138, 138, 255))
    one.alpha_composite(frames[0].resize((SIZE * 20, SIZE * 20), Image.NEAREST))
    g = one.load()
    for y in range(one.size[1]):
        for x in range(one.size[0]):
            if x % 20 == 0 or y % 20 == 0:
                r, gg, b_, a = g[x, y]
                k = 2 if (x % 80 == 0 or y % 80 == 0) else 4
                g[x, y] = (r * (k - 1) // k, gg * (k - 1) // k, b_ * (k - 1) // k, a)
    one.save(path.replace(".png", "_f0.png"))
    alpha = np.zeros((SIZE, SIZE), bool)
    for f in frames:
        alpha |= np.array(f)[:, :, 3] > 0
    ys, xs = np.nonzero(alpha)
    print("bounds x", xs.min(), xs.max(), "y", ys.min(), ys.max())
    print("   " + "".join(str(x % 10) for x in range(SIZE)))
    for y in range(SIZE):
        print(f"{y:2d} " + "".join(BODY.get((x, y), "#" if alpha[y, x] else ".") for x in range(SIZE)))


if __name__ == "__main__":
    import sys
    _preview(sys.argv[1])
