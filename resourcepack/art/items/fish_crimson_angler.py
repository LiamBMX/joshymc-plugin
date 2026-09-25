"""Crimson Angler: an epic anglerfish of the JoshyMC fishing collection (crimson forest).

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's crimson body, pale eye and golden lure. A deep, bulbous
anglerfish: a huge round head with a gaping underbite mouth lined with pale fangs, a
small eye over the jaw hinge, a crimson hide lit from the top-left with scale marks and
a countershaded coral belly, a rounded fan tail, soft rear dorsal and anal fins and a
paddle-like pectoral fin (translucent with painted rays). From its forehead the illicium
arches forward over the mouth, ending in a glowing shroomlight-gold esca.

Animation (EPIC, 16 frames x 3 ticks): the tail and fins sway, the lure bobs and its glow
breathes (casting warm light on the jaw), a soft glint runs along the back, then an
iridescent sheen rolls from head to tail, flashing the scales cyan, pink and violet as it
passes; three twinkles and two drifting crimson spores float around it all loop.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_crimson_angler"
NAME = "Crimson Angler"
KIND = "item"
MODEL_KEY = "fish/crimson_angler"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 16
FRAMETIME = 3
SS = 4
SHIFT = (1, 1)         # the art below is drawn one pixel left / up of centre

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean wine-violet, lights coral
OUTLINE = "#3a0620"
OUTLINE_LIT = "#541028"
FIN_OUTLINE = ("#5c0f2c", 232)
RED = ["#4a0a24", "#6e1029", "#951a2e", "#b82834", "#d6423c", "#ec6a4c", "#f99a6c"]
BELLY = ["#b83a44", "#d45a56", "#e97e68", "#f6a484", "#fdcaa8"]
MOUTH = ["#1c0310", "#2e0618", "#4a0c24"]
TOOTH = ["#b68e8a", "#e9d6c8", "#fff6ea"]
FIN = ["#6a1030", "#8e1c38", "#b23244", "#cf5256", "#e97c6c", "#f8aa8c"]
LURE = ["#b44a16", "#ea7f22", "#ffb83a", "#ffe07a", "#fff8d2"]
EYE = ["#14030b", "#fff3e0"]
IRIS = "#e9a93a"
GLINT = "#fff0e4"
IRIDESCENT = ["#9cf6ff", "#ffa6e2", "#c8a2ff"]      # cyan, pink, violet
SHEEN = "#ff9fd6"
SPARKS = ["#ffd6f0", "#c6f6ff", "#e2ccff"]
SPORE = "#ff4d4d"

AXIS = (math.cos(math.radians(28)), math.sin(math.radians(28)))

# ---- the body, drawn by hand: S skin, M the gaping mouth ---------------------------------
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    ".........SSSSS..................",  # 6
    ".......SSSSSSSSS................",  # 7
    "......SSSSSSSSSSSS..............",  # 8
    ".....SSSSSSSSSSSSSS.............",  # 9
    "....SSSSSSSSSSSSSSSS............",  # 10
    "...SSSSSSSSSSSSSSSSSS...........",  # 11
    "..SSSSSSSSSSSSSSSSSSSS..........",  # 12
    "...MMMMMSSSSSSSSSSSSSSS.........",  # 13
    "..SMMMMMMMSSSSSSSSSSSSS.........",  # 14
    ".SSMMMMMMSSSSSSSSSSSSSSS........",  # 15
    ".SSSMMMSSSSSSSSSSSSSSSSS........",  # 16
    "..SSSSSSSSSSSSSSSSSSSSSSS.......",  # 17
    "...SSSSSSSSSSSSSSSSSSSSSS.......",  # 18
    ".....SSSSSSSSSSSSSSSSSSSS.......",  # 19
    ".......SSSSSSSSSSSSSSSSSS.......",  # 20
    "..........SSSSSSSSSSSSSSS.......",  # 21
    ".............SSSSSSSSSSSS.......",  # 22
    "................SSSSSSS.........",  # 23
    "................................",  # 24
    "................................",  # 25
    "................................",  # 26
    "................................",  # 27
    "................................",  # 28
    "................................",  # 29
    "................................",  # 30
    "................................",  # 31
]
MASK = np.array([[ch != "." for ch in row] for row in ART])
GAPE = np.array([[ch == "M" for ch in row] for row in ART])
SKIN = MASK & ~GAPE

# Hand-placed head details: (x, y) -> colour.
HEAD = {
    # eye: 2x2 with a catchlight, a lit socket above and a golden iris glint behind
    (9, 10): EYE[1], (10, 10): EYE[0], (9, 11): EYE[0], (10, 11): EYE[0], (11, 11): IRIS,
    # fangs hanging from the upper jaw and rising from the jutting lower jaw
    (3, 13): TOOTH[2], (5, 13): TOOTH[2], (5, 14): TOOTH[1], (7, 13): TOOTH[1],
    (2, 14): TOOTH[2], (4, 15): TOOTH[1], (4, 16): TOOTH[2], (6, 16): TOOTH[1], (8, 15): TOOTH[0],
    # the tongue catching a little light at the back of the gape
    (5, 16): MOUTH[2], (6, 15): MOUTH[2], (7, 15): MOUTH[2],
}


def _lattice(x: int, y: int) -> bool:
    """A sparse diamond lattice (frame space) for scale marks, behind the smooth head."""
    return x >= 11 and y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1


def _light() -> np.ndarray:
    """Lambert light from the top-left on a pillow height field raised from the silhouette
    (distance to its edge), so the bulbous head and body read as round."""
    pts = np.argwhere(~MASK)
    d = np.zeros((SIZE, SIZE))
    for y, x in np.argwhere(MASK):
        d[y, x] = np.sqrt(((pts - (y, x)) ** 2).sum(axis=1)).min()
    h = np.sqrt(np.clip(d / 7.0, 0, 1))
    hp = np.pad(h, 1)
    gx = (hp[1:-1, 2:] - hp[1:-1, :-2]) * 2.0
    gy = (hp[2:, 1:-1] - hp[:-2, 1:-1]) * 2.0
    n = np.stack([-gx, -gy, np.ones_like(gx)], axis=-1)
    n /= np.linalg.norm(n, axis=-1, keepdims=True)
    lx, ly, lz = -0.62, -0.72, 0.62
    ln = math.sqrt(lx * lx + ly * ly + lz * lz)
    return (n[..., 0] * lx + n[..., 1] * ly + n[..., 2] * lz) / ln


LIGHT = _light()


def _tone(x: int, y: int) -> int:
    """Quantised light level 0 (shadow) .. 5 (highlight)."""
    i = LIGHT[y, x]
    for level, edge in enumerate((0.25, 0.40, 0.52, 0.70, 0.86)):
        if i < edge:
            return level
    return 5


def _is_belly(x: int, y: int) -> bool:
    return y > 15.5 + 0.36 * (x - 4)


def _skin_colour(x: int, y: int) -> str:
    k = _tone(x, y)
    if _is_belly(x, y):
        return BELLY[min(4, max(0, k))]
    c = RED[min(6, k + 1)]
    if _lattice(x, y) and 2 <= k <= 4:
        c = RED[k]
    return c


def _body():
    img = canvas(SIZE)
    zones = {}
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if GAPE[y, x]:
                px[x, y] = rgba(MOUTH[1] if x < 5 else MOUTH[0])
                zones[(x, y)] = "mouth"
            elif SKIN[y, x]:
                px[x, y] = rgba(_skin_colour(x, y))
                zones[(x, y)] = "belly" if _is_belly(x, y) else "skin"
    for (x, y), c in HEAD.items():
        px[x, y] = rgba(c)
        zones[(x, y)] = "detail"
    return img, zones


# ---- fins: image-space polygons with rays ------------------------------------------------
_ys, _xs = np.mgrid[0:SIZE * SS, 0:SIZE * SS]
X = (_xs + 0.5) / SS
Y = (_ys + 0.5) / SS


def _cov(mask: np.ndarray) -> np.ndarray:
    return mask.reshape(SIZE, SS, SIZE, SS).mean(axis=(1, 3))


def _poly(poly) -> np.ndarray:
    inside = np.zeros(X.shape, bool)
    n = len(poly)
    for i in range(n):
        x0, y0 = poly[i]
        x1, y1 = poly[(i + 1) % n]
        if y0 == y1:
            continue
        cond = (y0 > Y) != (y1 > Y)
        cross = x0 + (Y - y0) * (x1 - x0) / (y1 - y0)
        inside ^= cond & (X < cross)
    return _cov(inside)


def _rot(points, pivot, degrees):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    px_, py_ = pivot
    return [(px_ + (x - px_) * c - (y - py_) * s, py_ + (x - px_) * s + (y - py_) * c) for x, y in points]


def _near(x, y, a, b, reach):
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    k = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(x - ax - k * dx, y - ay - k * dy) <= reach


TAIL_PIVOT = (23.8, 20.4)
TAIL_REST = 16.0       # the fan points down-right along the body's diagonal
TAIL_LENGTH = 0.84


def _tail(sway: float):
    pts = [(23.2, 18.4), (25.4, 17.3), (27.6, 17.1), (29.2, 18.3), (29.9, 20.8), (29.5, 23.4), (28.2, 25.6),
           (26.6, 26.5), (25.0, 25.0), (23.4, 22.6)]
    rays = [(TAIL_PIVOT, (28.4, 18.4), FIN[1]), (TAIL_PIVOT, (29.2, 21.6), FIN[1]),
            (TAIL_PIVOT, (27.5, 25.0), FIN[1])]
    pts = [(TAIL_PIVOT[0] + (x - TAIL_PIVOT[0]) * TAIL_LENGTH, y) for x, y in pts]
    rays = [((TAIL_PIVOT[0] + (a[0] - TAIL_PIVOT[0]) * TAIL_LENGTH, a[1]),
             (TAIL_PIVOT[0] + (b[0] - TAIL_PIVOT[0]) * TAIL_LENGTH, b[1]), c) for a, b, c in rays]
    a = TAIL_REST + 8.0 * sway
    return _rot(pts, TAIL_PIVOT, a), [(*_rot(r[:2], TAIL_PIVOT, a), r[2]) for r in rays]


def _dorsal(ripple: float):
    tip = (21.4 + 0.5 * ripple, 8.4 - 0.3 * ripple)
    pts = [(17.6, 10.4), (19.0, 8.6), tip, (23.9 + 0.4 * ripple, 10.6), (24.6, 13.6), (22.0, 13.0)]
    rays = [((19.0, 10.8), (19.6, 8.9), FIN[4]), ((21.2, 11.8), tip, FIN[1]), ((22.8, 13.0), (24.0, 11.2), FIN[1])]
    return pts, rays


def _anal(ripple: float):
    pts = [(18.2, 23.0), (19.0, 25.4 + 0.3 * ripple), (21.4, 25.6 + 0.4 * ripple), (23.6, 23.6), (23.6, 22.0)]
    rays = [((20.2, 23.2), (20.6, 25.2), FIN[1])]
    return pts, rays


def _pectoral(flap: float):
    pivot = (12.8, 17.4)
    pts = [(12.0, 16.6), (14.6, 16.4), (17.2, 18.4), (18.0, 21.2), (16.4, 23.4), (14.8, 22.4), (12.4, 18.6)]
    rays = [(pivot, (17.2, 20.0), FIN[5]), (pivot, (15.6, 22.2), FIN[2])]
    a = 10.0 * flap
    return _rot(pts, pivot, a), [(*_rot(r[:2], pivot, a), r[2]) for r in rays]


def _paint_fin(img, fin, membrane, alpha, cov_min=0.45, ray_alpha=226, lit=None, over=False, edge=None):
    """A translucent fin: membrane plus rays. lit colours fin pixels facing the top-left
    light. over=True paints on top of the body (the near pectoral). Returns its pixels."""
    poly, rays = fin
    cov = _poly(poly)
    inside = cov >= cov_min
    if not over:
        inside &= ~MASK
    px = img.load()
    out = set()
    for y in range(SIZE):
        for x in range(SIZE):
            if not inside[y, x]:
                continue
            colour, a = membrane, alpha
            for ra, rb, rc in rays:
                if cov[y, x] >= 0.9 and _near(x + 0.5, y + 0.5, ra, rb, 0.45):
                    colour, a = rc, ray_alpha
                    break
            if lit and ((y > 0 and not inside[y - 1, x] and not MASK[y - 1, x])
                        or (x > 0 and not inside[y, x - 1] and not MASK[y, x - 1])):
                colour = lit
            if edge and ((y + 1 < SIZE and not inside[y + 1, x]) or (x + 1 < SIZE and not inside[y, x + 1])):
                colour, a = edge, 235
            r, g, b_, _ = rgba(colour)
            px[x, y] = (r, g, b_, a)
            out.add((x, y))
    return out


def _outline(img: Image.Image) -> Image.Image:
    """1 px outline: dark wine beside the body (a touch lighter on the lit top-left
    side), a softer translucent wine where it only borders fins."""
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
            if any(MASK[ny, nx] for nx, ny in near):
                lit = (y + 1 < SIZE and MASK[y + 1, x] or x + 1 < SIZE and MASK[y, x + 1]) and \
                    not (y > 0 and MASK[y - 1, x]) and not (x > 0 and MASK[y, x - 1])
                dst[x, y] = rgba(OUTLINE_LIT if lit else OUTLINE)
            else:
                dst[x, y] = (fr, fg, fb, FIN_OUTLINE[1])
    return out


# ---- the lure (illicium + esca) ---------------------------------------------------------
ESCA = [  # relative to the esca's top-left; 4 = hottest core
    ".21.",
    "2432",
    "1321",
    ".10.",
]


# Illicium poses drawn by hand (base on the forehead -> tip), and the esca's top-left
# corner hanging under the tip: the lure bobs up / level / down.
LURE_POSES = {
    -1: ([(11, 5), (10, 4), (10, 3), (9, 2), (8, 1), (7, 1), (6, 1), (5, 1), (4, 2)], (1, 3)),
    0: ([(11, 5), (10, 4), (10, 3), (9, 2), (8, 1), (7, 1), (6, 1), (5, 2)], (2, 3)),
    1: ([(11, 5), (10, 4), (10, 3), (9, 2), (8, 2), (7, 2), (6, 2), (5, 3)], (2, 4)),
}


def _lure(bob: float):
    """Illicium pixels (base to tip) and the esca's top-left corner."""
    return LURE_POSES[max(-1, min(1, round(bob)))]


def _bands(mask: np.ndarray):
    """Steps down from the top contour (kb) for every body pixel."""
    kb = np.full(mask.shape, -1)
    for x in range(SIZE):
        ys = np.nonzero(mask[:, x])[0]
        for y in ys:
            kb[y, x] = y - ys[0]
    return kb


KB = _bands(MASK)


def _along(x: float, y: float) -> float:
    """Distance along the body axis from the jaw, in frame pixels."""
    return (x + 0.5 - 2.0) * AXIS[0] + (y + 0.5 - 12.0) * AXIS[1]


def _cycle(phase: float) -> str:
    """The iridescent cyan -> pink -> violet -> cyan loop."""
    p = (phase % 1.0) * 3
    i = int(p)
    return mix(IRIDESCENT[i], IRIDESCENT[(i + 1) % 3], p - i)


def _effects(img: Image.Image, zones: dict, t: float, step: int) -> None:
    px = img.load()
    # 1) frames 0-4: a soft glint slides along the back, head to tail
    if step <= 4:
        centre = 3.0 + 21.0 * step / 4.0
        for (x, y), zone in zones.items():
            kb = int(KB[y, x])
            if zone not in ("skin",) or kb < 0 or kb > 2:
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.2) * 0.66 * (1.0 - kb / 3.2)
            if k > 0:
                px[x, y] = rgba(mix(px[x, y], GLINT, k))
    # 3) frames 6-14: an iridescent sheen rolls across the body, cyan leading, violet trailing
    if 6 <= step <= 14:
        centre = -1.0 + 27.0 * (step - 6) / 8.0
        for (x, y), zone in zones.items():
            if zone not in ("skin", "belly", "fin"):
                continue
            d = _along(x, y) - centre
            k = max(0.0, 1.0 - abs(d) / 4.4)
            if k <= 0:
                continue
            if _lattice(x, y) and zone == "skin":
                # the scales flash through cyan (leading), pink and violet (trailing)
                colour = _cycle(0.5 - d / 8.0 + t)
                strength = min(1.0, k * 1.5)
            else:
                k = max(0.0, 1.0 - abs(d) / 2.8)
                colour, strength = SHEEN, k * (0.42 if zone != "fin" else 0.3)
            px[x, y] = rgba(mix(px[x, y], colour, strength))


SPARKLES = [((27, 13), 0.0, SPARKS[0]), ((3, 22), 0.36, SPARKS[1]), ((17, 3), 0.68, SPARKS[2])]
SPORES = [(24, 7, 0.0), (7, 28, 0.5)]        # start x, start y, phase: they rise 6 px a loop


def _frame(t: float) -> Image.Image:
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.15))
    flap = math.sin(2 * math.pi * (t + 0.3))
    bob = 1.3 * math.sin(2 * math.pi * (t + 0.1))
    pulse = 0.5 - 0.5 * math.cos(2 * math.pi * t)

    img = canvas(SIZE)
    _paint_fin(img, _tail(sway), FIN[3], 214, cov_min=0.4, lit=FIN[4])
    _paint_fin(img, _dorsal(ripple), FIN[2], 205, lit=FIN[4])
    _paint_fin(img, _anal(ripple), FIN[2], 200)
    body, zones = _body()
    img.alpha_composite(body)
    front = canvas(SIZE)
    for p in _paint_fin(front, _pectoral(flap), FIN[4], 206, over=True, edge=FIN[0]):
        zones[p] = "fin"
    img.alpha_composite(front)
    img = _outline(img)
    step = round(t * FRAMES)
    path, (ex, ey) = _lure(bob)
    cx, cy = ex + 2.0, ey + 2.0
    # the esca's warm light on the brow and jaw, breathing with the glow
    px = img.load()
    for (x, y), zone in zones.items():
        if zone in ("skin", "belly", "detail", "mouth", "fin"):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if d < 6.0:
                px[x, y] = rgba(mix(px[x, y], LURE[3], (0.12 + 0.22 * pulse) * (1 - d / 6.0)))
    _effects(img, zones, t, step)

    for i, (x, y) in enumerate(path):
        if MASK[y, x]:
            continue
        k = i / max(1, len(path) - 1)
        px[x, y] = rgba(mix(RED[4] if i < 2 else RED[5], LURE[2], max(0.0, k - 0.55) * 1.8))
    # shadow side: the dark wine outline hugs the stalk's right edge, and its underside
    # only where it runs level
    on = set(path)
    for i, (x, y) in enumerate(path):
        level = (x - 1, y) in on or (x + 1, y) in on
        for dx, dy in ((1, 0),) + (((0, 1),) if level else ()):
            nx, ny = x + dx, y + dy
            if px[nx, ny][3] == 0 and (nx, ny) not in on:
                px[nx, ny] = rgba(OUTLINE)
    # glow halo, then the esca itself brightening with the pulse
    esca = {(ex + i, ey + j) for j, row in enumerate(ESCA) for i, ch in enumerate(row) if ch != "."}
    for y in range(max(0, ey - 1), min(SIZE, ey + 5)):
        for x in range(max(0, ex - 1), min(SIZE, ex + 5)):
            if px[x, y][3] or (x, y) in esca:
                continue
            side = sum((x + dx, y + dy) in esca for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if side:
                r, g, b, _ = rgba(LURE[2])
                px[x, y] = (r, g, b, round(50 + 130 * pulse))
    for j, row in enumerate(ESCA):
        for i, ch in enumerate(row):
            if ch != ".":
                n = int(ch)
                c = mix(LURE[n], LURE[min(4, n + 1)], pulse * 0.8) if n >= 2 else LURE[n]
                px[ex + i, ey + j] = rgba(c)
    # twinkles and drifting crimson spores
    for (sx, sy), phase, colour in SPARKLES:
        amount = max(0.0, 2.0 * (0.5 - 0.5 * math.cos(2 * math.pi * (t - phase))) - 1.0)
        sparkle(img, sx, sy, amount, colour, reach=2)
    for sx, sy, phase in SPORES:
        f = (t + phase) % 1.0
        x = sx + round(0.7 * math.sin(2 * math.pi * f))
        y = sy - round(6 * f)
        if px[x, y][3] == 0:
            r, g, b, _ = rgba(SPORE)
            px[x, y] = (r, g, b, round(230 * math.sin(math.pi * f)))
    out = canvas(SIZE)
    out.paste(img, SHIFT)
    return out


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
