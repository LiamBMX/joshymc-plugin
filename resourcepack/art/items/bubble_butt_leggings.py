"""Bubble Butt Leggings: glossy pastel-blue leggings printed with soap bubbles.

Light-blue (#ADD8E6) glossy fabric covered in iridescent soap-bubble prints, a puffy
bubble-wrap seat, ribbed silver cuffs and trim, and a periwinkle belt closed by a round
silver buckle holding a soap bubble. The item model is the pair of leggings with big soap
bubbles orbiting it. Animated: the soap film on every bubble (the orbiting ones, the
buckle and every print on the fabric) swirls with a sliding rainbow sheen, and the
highlights twinkle. Worn, the same prints, belt, buckle, cuffs and bubble-wrap seat dress
the player's legs.
"""
from __future__ import annotations

import math
import random

from art.kit import (HUMANOID, animate, arc, box, canvas, display, model, place, prism, region, rgba,
                     rotation_of, save, save_animation, save_layer, shine, sparkle, turn)

ID = "bubble_butt_leggings"
NAME = "Bubble Butt Leggings"
KIND = "leggings"
EQUIPMENT_KEY = "bubble_butt"
COUNTERPART = "item/diamond_leggings"

# --------------------------------------------------------------------------------------
# Palettes (darkest -> lightest), hand-tuned and hue-shifted
# --------------------------------------------------------------------------------------
FAB = ["#36508a", "#5073b2", "#739dd3", "#98c4e6", "#b6def0", "#cfeef8", "#e8f9fd", "#ffffff"]
SILVER = ["#2a2f4e", "#4a5376", "#717c9e", "#9ea9c6", "#c8d1e4", "#e9eff9", "#ffffff"]
BELT = ["#1b2358", "#29398a", "#3a53b0", "#5373cc", "#7596e2", "#a0bcf2"]
WRAP = ["#5f86c4", "#86b0e0", "#b0dcf4", "#d6f3fd", "#f0fcff", "#ffffff"]
# Soap film: a cyclic pastel rainbow for the rims, and a paler one for the sheen inside.
FILM = ["#ff6fc4", "#b688ff", "#6faaff", "#4fd8e6", "#6ee8a8", "#ffe066", "#ffa06e"]
WHITE = (255, 255, 255, 255)
EDGE = "#4b3f93"          # deep violet at the very edge of a floating bubble
CORE = "#eef9ff"          # the clear middle of a floating bubble

FRAMES = 16               # every animation shares one loop: 16 frames x 3 ticks = 2.4 s
FRAMETIME = 3
GUI_ROT = (12.0, 205.0, 0.0)   # inventory view: the front, turned a little to show the side


def _t(c):
    return rgba(c) if isinstance(c, str) else tuple(c)


FAB_T = [_t(c) for c in FAB]
SILVER_T = [_t(c) for c in SILVER]
BELT_T = [_t(c) for c in BELT]
WRAP_T = [_t(c) for c in WRAP]
FILM_T = [_t(c) for c in FILM]
FILM_PALE_T = [tuple(round(c[i] + (255 - c[i]) * 0.55) for i in range(3)) + (255,) for c in FILM_T]


def blend(a, b, k: float):
    """Blend RGBA tuple a toward b by k (0..1)."""
    k = max(0.0, min(1.0, k))
    return (round(a[0] + (b[0] - a[0]) * k), round(a[1] + (b[1] - a[1]) * k), round(a[2] + (b[2] - a[2]) * k),
            max(a[3], b[3]) if a[3] else b[3])


def film(h: float, pal=None):
    """The soap-film colour at hue position h (cyclic, 0..1)."""
    pal = pal or FILM_T
    n = len(pal)
    f = (h % 1.0) * n
    i = int(f)
    return blend(pal[i % n], pal[(i + 1) % n], f - i)


def pulse(t: float, at: float, width: float = 0.16) -> float:
    """A short 0 -> 1 -> 0 bump centred on loop time `at` (for twinkles)."""
    d = abs(((t - at + 0.5) % 1.0) - 0.5)
    return max(0.0, 1.0 - d / width)


# --------------------------------------------------------------------------------------
# Layout (model units). The leggings stand upright, front facing -Z (north), centred on
# x = 8, z = 8, waistband on top. The fabric textures are projected straight onto the
# faces (2 texels per unit) so prints and shading run continuously across parts.
# --------------------------------------------------------------------------------------
LEG_X = (4.25, 11.75)
SPLAY = 3.0                                      # legs lean out a little from the crotch
THIGH = (5.8, 11.2, 2.8, 2.95)                   # y0, y1, half width, half depth
CALF = (1.3, 5.8, 2.55, 2.75)
CUFF = (0.0, 1.6, 2.75, 2.95)
HIPS = (10.6, 16.3, 1.15, 14.85, 4.75, 11.25)    # y0, y1, x0, x1, z0, z1
WAIST_X, WAIST_Z = (1.3, 14.7), (4.5, 11.5)
TRIM_LO, STRAP, TRIM_HI = (16.15, 16.8), (16.75, 18.75), (18.7, 19.35)
BUCKLE = (8.0, 17.75)                            # centre of the bubble buckle (x, y)
SEAT_Y, SEAT_R, SEAT_Z = 12.95, 3.05, 10.9       # bubble-wrap cheeks: centre height, radius, base


# --- projected UVs for the 64px fabric atlases (4 texels per uv unit) ------------------
def uv_front(x0, x1, y0, y1):
    return [(16 - x1) / 2, (20 - y1) / 2, (16 - x0) / 2, (20 - y0) / 2]


def uv_back(x0, x1, y0, y1):
    return [8 + x0 / 2, (20 - y1) / 2, 8 + x1 / 2, (20 - y0) / 2]


def uv_east(z0, z1, y0, y1):
    return [(16 - z1) / 2, (20 - y1) / 2, (16 - z0) / 2, (20 - y0) / 2]


def uv_west(z0, z1, y0, y1):
    return [8 + z0 / 2, (20 - y1) / 2, 8 + z1 / 2, (20 - y0) / 2]


def front_tx(x, y):
    return 2 * (16 - x), 2 * (20 - y)


def back_tx(x, y):
    return 32 + 2 * x, 2 * (20 - y)


def east_tx(z, y):
    return 2 * (16 - z), 2 * (20 - y)


def west_tx(z, y):
    return 32 + 2 * z, 2 * (20 - y)


# --------------------------------------------------------------------------------------
# Painting helpers
# --------------------------------------------------------------------------------------

def put(px, size, x, y, c):
    if 0 <= x < size[0] and 0 <= y < size[1]:
        px[x, y] = c


MINI = {  # hand-drawn small prints: c = leave the fabric, o = film rim, w = highlight, i = inside
    4: ["cooc", "owio", "oiio", "cooc"],
    5: ["coooc", "owwio", "owiio", "oiiio", "coooc"],
    6: ["ccoocc", "cowwoc", "owwiio", "owiiio", "coiioc", "ccoocc"],
}


def print_bubble(img, cx, cy, r, t, phase, spark=None, shadow=True):
    """A soap-bubble print on painted fabric: a pale disc whose iridescent rim and inner
    sheen turn with t, a white highlight on the upper left and a glint lower right.
    (cx, cy) and r are in texels; `spark` is the loop time the highlight twinkles.
    Small prints use hand-drawn masks so they stay round instead of turning to confetti."""
    px = img.load()
    size = img.size
    spin = t + phase
    if r < 3.2:
        n = 4 if r < 2.3 else (5 if r < 2.8 else 6)
        mask = MINI[n]
        x0, y0 = round(cx - n / 2), round(cy - n / 2)
        for j, row in enumerate(mask):
            for i, ch in enumerate(row):
                x, y = x0 + i, y0 + j
                if ch == "c" or not (0 <= x < size[0] and 0 <= y < size[1]) or px[x, y][3] == 0:
                    continue
                base = px[x, y]
                if ch == "o":
                    a01 = (math.atan2(j + 0.5 - n / 2, i + 0.5 - n / 2) / (2 * math.pi)) % 1.0
                    c = film(0.3 * a01 - spin)
                    if i + j >= n:
                        c = blend(c, FAB_T[1], 0.2)
                elif ch == "w":
                    c = WHITE
                else:
                    c = blend(base, WHITE, 0.5)
                px[x, y] = c
        if shadow and n >= 5:
            for i in range(1, n):
                for x, y in ((x0 + i, y0 + n), (x0 + n, y0 + i)):
                    if 0 <= x < size[0] and 0 <= y < size[1] and px[x, y][3]:
                        px[x, y] = blend(px[x, y], FAB_T[2], 0.35)
        hx, hy = x0 + 1, y0 + 1
        if spark is not None and pulse(t, spark) > 0:
            sparkle(img, hx, hy, pulse(t, spark), "#ffffff", reach=1)
        return
    rim = 1.5 if r < 4.5 else 2.0
    for y in range(math.floor(cy - r - 2), math.ceil(cy + r + 2)):
        for x in range(math.floor(cx - r - 2), math.ceil(cx + r + 2)):
            if not (0 <= x < size[0] and 0 <= y < size[1]):
                continue
            base = px[x, y]
            if base[3] == 0:
                continue
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            if d > r + 0.9:
                continue
            ang = math.atan2(dy, dx)
            a01 = (ang / (2 * math.pi)) % 1.0
            lit = -(dx + dy) / (d * 1.4142 + 1e-6)           # +1 upper left, -1 lower right
            if d > r:
                if shadow and lit < -0.2:
                    px[x, y] = blend(base, FAB_T[2], 0.5)     # a soft drop shadow
                continue
            if d > r - rim:
                c = film(a01 - spin)
                c = blend(c, WHITE, 0.3 * lit) if lit > 0 else blend(c, FAB_T[1], 0.35 * -lit)
                px[x, y] = c
            else:
                c = blend(base, WHITE, 0.45)
                k = max(0.0, math.cos(ang - 2 * math.pi * spin)) ** 2 * min(1.0, d / (r - rim + 1e-6)) ** 1.2
                c = blend(c, film(a01 + 0.45 - spin, FILM_PALE_T), 0.85 * k)
                px[x, y] = c
    # highlight: a white crescent on the upper left, a glint lower right
    for y in range(math.floor(cy - r), math.ceil(cy + 1)):
        for x in range(math.floor(cx - r), math.ceil(cx + 1)):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            ang = math.degrees(math.atan2(dy, dx)) % 360
            if 0.42 * r <= d <= r - rim - 0.2 and 195 <= ang <= 255:
                put(px, size, x, y, WHITE)
    hx, hy = round(cx - 0.5 * r), round(cy - 0.5 * r)
    gx, gy = math.floor(cx + 0.45 * r), math.floor(cy + 0.4 * r)
    if 0 <= gx < size[0] and 0 <= gy < size[1]:
        px[gx, gy] = blend(px[gx, gy], WHITE, 0.75)
    if spark is not None and pulse(t, spark) > 0:
        sparkle(img, hx, hy, pulse(t, spark), "#ffffff", reach=2)


def fabric_pixel(s, v=0.5, sheen=0.25):
    """Glossy shading across a tube of fabric: s = 0..1 from the viewer's left edge,
    v = 0..1 from top to bottom. Light comes from the upper left: a broad soft highlight
    with a brighter core, the base, and a shaded far edge."""
    if s > 0.935:
        return FAB_T[2] if v > 0.5 else FAB_T[3]
    if s > 0.87:
        return FAB_T[3]
    if s > 0.8:
        return FAB_T[5]                      # glossy rim light just inside the shaded edge
    if abs(s - sheen) < 0.05:
        return FAB_T[6]
    if abs(s - sheen) < 0.16:
        return FAB_T[5]
    if s > 0.6 and v > 0.7:
        return FAB_T[3]
    return FAB_T[4]


def gloss_dashes(px, size, cols, y0, y1, seed):
    """Short white specular dashes along a highlight column: wet-look gloss."""
    rng = random.Random(seed)
    y = y0 + rng.randrange(0, 4)
    while y < y1:
        n = rng.choice((2, 3, 3, 4))
        for k in range(n):
            for c in cols:
                if y + k < y1 and 0 <= c < size[0] and px[c, y + k][3]:
                    px[c, y + k] = WHITE
        y += n + rng.randrange(4, 9)


# --------------------------------------------------------------------------------------
# Fabric atlases (item model)
# --------------------------------------------------------------------------------------
# Prints: (a, y, radius, film phase, twinkle time or None); a = world x on the front and
# back, world z on the sides; units.
FRONT_PRINTS = [
    (11.9, 7.4, 2.3, 0.00, 0.06), (4.4, 3.9, 1.9, 0.50, 0.56), (4.0, 13.5, 1.7, 0.25, 0.31),
    (12.4, 14.3, 1.25, 0.75, 0.81), (10.2, 2.9, 1.0, 0.40, None), (5.7, 8.6, 1.2, 0.85, None),
    (13.4, 3.5, 1.0, 0.10, None), (2.8, 7.3, 1.0, 0.60, None), (9.4, 11.9, 1.0, 0.90, None),
]
BACK_PRINTS = [
    (4.8, 5.6, 1.6, 0.15, 0.45), (11.3, 3.6, 1.8, 0.60, 0.95), (12.8, 8.3, 1.0, 0.30, None),
    (2.9, 2.7, 1.0, 0.80, None), (6.3, 8.9, 1.0, 0.45, None), (9.7, 6.6, 1.1, 0.05, None),
]
SIDE_PRINTS = [(6.1, 4.2, 1.2, 0.25, 0.70), (10.0, 8.4, 1.0, 0.70, None), (6.3, 13.6, 1.3, 0.95, 0.20),
               (10.1, 2.6, 1.0, 0.50, None)]


MARGIN = 0.6    # paint every part a little oversize so no face edge samples an empty texel


def leg_span(y):
    """(half width, half depth) of a leg at height y, or None below the leg."""
    if y >= THIGH[0]:
        return THIGH[2], THIGH[3]
    if y >= CALF[0] - MARGIN:
        return CALF[2], CALF[3]
    return None


def paint_fabric_base():
    """Front half (cols 0-31, x 16..0) and back half (cols 32-63, x 0..16), no prints."""
    img = canvas(64)
    px = img.load()
    for ty in range(64):
        y = 20 - (ty + 0.5) / 2
        if y < CALF[0] - MARGIN or y > HIPS[1] + MARGIN:
            continue
        for half in (0, 1):
            for col in range(32):
                tx = col + 32 * half
                x = 16 - (col + 0.5) / 2 if half == 0 else (col + 0.5) / 2
                if y >= HIPS[0]:
                    x0, x1 = HIPS[2], HIPS[3]
                    if not x0 - MARGIN <= x <= x1 + MARGIN:
                        continue
                    s = (x1 - x) / (x1 - x0) if half == 0 else (x - x0) / (x1 - x0)
                    s = max(0.0, min(1.0, s))
                    c = fabric_pixel(s, (HIPS[1] - y) / (HIPS[1] - HIPS[0]), sheen=0.17)
                    if y > HIPS[1] - 0.55:
                        c = FAB_T[3]                              # shadow under the belt
                else:
                    span = leg_span(y)
                    if span is None:
                        continue
                    hw = span[0]
                    leg = min(LEG_X, key=lambda lx: abs(lx - x))
                    if abs(x - leg) > hw + MARGIN:
                        continue
                    s = (leg + hw - x) / (2 * hw) if half == 0 else (x - leg + hw) / (2 * hw)
                    s = max(0.0, min(1.0, s))
                    c = fabric_pixel(s, (THIGH[1] - y) / (THIGH[1] - CALF[0]))
                px[tx, ty] = c
    size = img.size
    # wet-look gloss dashes on each highlight column
    for i, lx in enumerate(LEG_X):
        hw = THIGH[2]
        for half, conv in ((0, front_tx), (1, back_tx)):
            edge = lx + hw if half == 0 else lx - hw
            col = int(conv(edge - (0.25 * 2 * hw) * (1 if half == 0 else -1), 5)[0])
            gloss_dashes(px, size, (col,), 2 * (20 - HIPS[0]) + 1, 2 * (20 - CALF[0]) - 1, 11 + 7 * i + half)
    # creases: two short folds pulling up from the crotch, and knee folds front and back
    for tx0, step in ((14, -1), (17, 1)):
        for k in range(3):
            tx, ty = tx0 + step * k, 18 - k
            px[tx, ty] = FAB_T[3]
            px[tx, ty - 1] = FAB_T[6]
    for lx in LEG_X:
        for conv in (front_tx, back_tx):
            for dx, y, n in ((-1.5, 6.0, 3), (0.9, 5.8, 2)):
                tx, ty = (int(v) for v in conv(lx + dx, y))
                for k in range(n):
                    xx = tx + k
                    if 0 <= xx < 64 and px[xx, ty][3]:
                        px[xx, ty] = FAB_T[3]
                        if px[xx, ty - 1][3]:
                            px[xx, ty - 1] = FAB_T[5]
    # the crack between the cheeks on the back of the hips
    for ty in range(64):
        y = 20 - (ty + 0.5) / 2
        if SEAT_Y - 2.6 <= y <= SEAT_Y + 2.6:
            for tx in (47, 48):
                px[tx, ty] = FAB_T[1] if tx == 47 else FAB_T[2]
    return img


def paint_side_base():
    """East panel (cols 0-31, z 16..0) and west panel (cols 32-63, z 0..16)."""
    img = canvas(64)
    px = img.load()
    for ty in range(64):
        y = 20 - (ty + 0.5) / 2
        if y < CALF[0] - MARGIN or y > HIPS[1] + MARGIN:
            continue
        for half in (0, 1):
            for col in range(32):
                tx = col + 32 * half
                z = 16 - (col + 0.5) / 2 if half == 0 else (col + 0.5) / 2
                if y >= HIPS[0]:
                    z0, z1 = HIPS[4], HIPS[5]
                else:
                    span = leg_span(y)
                    if span is None:
                        continue
                    z0, z1 = 8 - span[1], 8 + span[1]
                if z0 - MARGIN <= z <= z1 + MARGIN:
                    s = (z1 - z) / (z1 - z0) if half == 0 else (z - z0) / (z1 - z0)
                    s = max(0.0, min(1.0, s))
                    c = fabric_pixel(s, 0.5, sheen=0.22)
                    dz = z - 8.0 if half == 1 else 8.0 - z          # + toward the viewer's right
                    if -0.5 <= dz < 0.0:
                        c = SILVER_T[5]                              # silver side stripe
                    elif 0.0 <= dz < 0.5:
                        c = SILVER_T[3]
                    elif 0.5 <= dz < 1.0:
                        c = FAB_T[3]
                    px[tx, ty] = c
    return img


def fab_frame(base, t):
    img = base.copy()
    for x, y, r, phase, spark in FRONT_PRINTS:
        tx, ty = front_tx(x, y)
        print_bubble(img, tx, ty, 2 * r, t, phase, spark)
    for x, y, r, phase, spark in BACK_PRINTS:
        tx, ty = back_tx(x, y)
        print_bubble(img, tx, ty, 2 * r, t, phase, spark)
    return img


def side_frame(base, t):
    img = base.copy()
    for z, y, r, phase, spark in SIDE_PRINTS:
        for conv, ph in ((east_tx, 0.0), (west_tx, 0.5)):
            tx, ty = conv(z, y)
            print_bubble(img, tx, ty, 2 * r, t, phase + ph, None if spark is None else (spark + ph) % 1)
    return img


# --------------------------------------------------------------------------------------
# Bubbles: the frame, the cheeks' bubble-wrap image, and the projection that lays them on
# --------------------------------------------------------------------------------------

def orb_frame(t, phase=0.0):
    """One 32px frame of a soap bubble seen head on: a clear pale middle with swirling
    pastel film, an iridescent rim that deepens at the edge, a window highlight upper
    left and a glint lower right."""
    img = canvas(32)
    px = img.load()
    spin = t + phase
    core, edge = _t(CORE), _t(EDGE)
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 15.5, (y + 0.5 - 16) / 15.5
            d = math.hypot(dx, dy)
            a01 = (math.atan2(dy, dx) / (2 * math.pi)) % 1.0
            lit = -(dx + dy) / 1.4142
            if d > 0.72:
                k = min(1.0, (d - 0.72) / 0.28)
                c = film(a01 + 0.3 * d - spin)
                c = blend(c, edge, 0.55 * k ** 1.6)
                if lit > 0:
                    c = blend(c, WHITE, 0.35 * lit * (1 - k))
            else:
                swirl = 0.5 + 0.5 * math.sin(2 * math.pi * (2 * a01 + 1.4 * d - spin))
                c = blend(core, film(a01 + d - spin, FILM_PALE_T), (0.35 + 0.65 * swirl) * (0.25 + 0.75 * d / 0.72))
            px[x, y] = c
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 15.5, (y + 0.5 - 16) / 15.5
            if (dx + 0.42) ** 2 / 0.028 + (dy + 0.42) ** 2 / 0.045 <= 1.0:
                px[x, y] = WHITE                                  # window highlight
            d = math.hypot(dx, dy)
            ang = math.degrees(math.atan2(dy, dx)) % 360
            if 0.6 <= d <= 0.68 and (160 <= ang <= 185 or 270 <= ang <= 290):
                px[x, y] = blend(px[x, y], WHITE, 0.8)            # the reflection's arc
    for x, y in ((21, 22), (22, 21), (22, 22)):
        px[x, y] = blend(px[x, y], WHITE, 0.85)
    sparkle(img, 9, 9, pulse(t, (0.15 + phase) % 1.0, 0.2), "#ffffff", reach=3)
    sparkle(img, 22, 22, pulse(t, (0.65 + phase) % 1.0, 0.15), "#ffffff", reach=2)
    return img


SEAT_CELLS = [(16.0 + col * 8.4 + (4.2 if row % 2 else 0.0), 16.0 + row * 7.3)
              for row in range(-3, 4) for col in range(-3, 3)]


def paint_seat_base():
    """One bubble-wrap cheek seen from behind: pale blue plastic packed with big round
    bubbles, each with its own highlight, soap-film glint and cast shadow; the whole
    cheek domed (lit upper left) and edged in deeper plastic."""
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            dx, dy = (x + 0.5 - 16) / 15.5, (y + 0.5 - 16) / 15.5
            d = math.hypot(dx, dy)
            lit = -(dx + dy) / 1.4142
            best, bx, by = 9e9, 0.0, 0.0
            for cx, cy in SEAT_CELLS:
                q = (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2
                if q < best:
                    best, bx, by = q, cx, cy
            r = math.sqrt(best)
            ex, ey = x + 0.5 - bx, y + 0.5 - by
            blit = -(ex + ey) / (r * 1.4142 + 1e-6)
            if r > 3.7:
                c = WRAP_T[2] if ex + ey > 0 else WRAP_T[3]           # film between bubbles
            elif r > 2.9:
                c = WRAP_T[4] if blit > 0.35 else (WRAP_T[3] if blit > -0.35 else WRAP_T[2])
            else:
                c = WRAP_T[3]
                if blit < -0.3 and r > 1.7:
                    c = WRAP_T[2]
            if -2.4 <= ex <= -0.4 and -2.4 <= ey <= -0.4 and r < 2.7:
                c = WHITE                                             # each bubble's highlight
            elif 0.9 <= ex <= 1.9 and 0.9 <= ey <= 1.9 and r < 2.9:
                c = FILM_PALE_T[0] if (bx + by) % 3 < 1.5 else FILM_PALE_T[2]   # a soap-film glint
            # the whole cheek is a dome: lighter upper left, shaded lower right, a deep edge
            if lit < -0.2:
                c = blend(c, FAB_T[1], 0.08 + 0.22 * (-lit - 0.2) / 0.8)
            elif lit > 0.3:
                c = blend(c, WHITE, 0.35 * (lit - 0.3) / 0.7)
            if d > 0.88:
                c = blend(c, FAB_T[2], min(1.0, 0.3 + 0.7 * (d - 0.88) / 0.12))
            px[x, y] = c
    return img


def seat_frame(base, t):
    """The cheek with a gloss glint sweeping across the plastic and two bubbles twinkling."""
    img = shine(base, t, "#ffffff", width=4.0, strength=0.5, angle=40.0, pause=0.5)
    sparkle(img, 10, 7, pulse(t, 0.62, 0.13), "#ffffff", reach=2)
    sparkle(img, 14, 14, pulse(t, 0.86, 0.12), "#ffffff", reach=2)
    return img


def view_axes(rot):
    """Screen right and screen up of a camera looking at the model through display
    rotation `rot`, in model space."""
    a, th = math.radians(rot[0]), math.radians(rot[1])
    return (math.cos(th), 0.0, math.sin(th)), (math.sin(a) * math.sin(th), math.cos(a), -math.sin(a) * math.cos(th))


FACE_VERTS = {  # corners in uv order (u0 v0, u0 v1, u1 v1, u1 v0), as Minecraft bakes faces
    "down": lambda a, b: [(a[0], a[1], b[2]), (a[0], a[1], a[2]), (b[0], a[1], a[2]), (b[0], a[1], b[2])],
    "up": lambda a, b: [(a[0], b[1], a[2]), (a[0], b[1], b[2]), (b[0], b[1], b[2]), (b[0], b[1], a[2])],
    "north": lambda a, b: [(b[0], b[1], a[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2]), (a[0], b[1], a[2])],
    "south": lambda a, b: [(a[0], b[1], b[2]), (a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], b[1], b[2])],
    "west": lambda a, b: [(a[0], b[1], a[2]), (a[0], a[1], a[2]), (a[0], a[1], b[2]), (a[0], b[1], b[2])],
    "east": lambda a, b: [(b[0], b[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (b[0], b[1], a[2])],
}


def project(elements, centre, radius, tex, rot=None):
    """Map `tex` onto every face as a camera looking through display rotation `rot`
    (default: the inventory view) sees it: the frame becomes one round image centred on
    `centre`, whatever facet it lands on."""
    h, u = view_axes(rot or GUI_ROT)
    cx, cy, cz = centre

    def clamp(v):
        return round(max(0.0, min(16.0, v)), 4)

    for e in elements:
        m, o = rotation_of(e)
        for side, face in e["faces"].items():
            pts = FACE_VERTS[side](e["from"], e["to"])
            if o is not None:
                pts = [tuple(sum(m[i][k] * (p[k] - o[k]) for k in range(3)) + o[i] for i in range(3)) for p in pts]
            uv = []
            for p in pts:
                q = (p[0] - cx, p[1] - cy, p[2] - cz)
                s = (q[0] * h[0] + q[1] * h[1] + q[2] * h[2]) / radius
                w = (q[0] * u[0] + q[1] * u[1] + q[2] * u[2]) / radius
                uv.append((8 + 8 * s, 8 - 8 * w))
            face["uv"] = [clamp((uv[0][0] + uv[1][0]) / 2), clamp((uv[0][1] + uv[3][1]) / 2),
                          clamp((uv[2][0] + uv[3][0]) / 2), clamp((uv[1][1] + uv[2][1]) / 2)]
            face["texture"] = "#" + tex
    return elements


def stagger(slabs, axis_index=1, step=0.012):
    """Offset the coplanar caps of a prism's slabs so they never z-fight."""
    for k, s in enumerate(slabs):
        s["to"][axis_index] = round(s["to"][axis_index] + step * k, 4)
        s["from"][axis_index] = round(s["from"][axis_index] - step * k, 4)
    return slabs


def sphere(centre, radius, tex, slices=5, glow=7):
    """A soap bubble: stacked octagonal slices, self-lit, with the bubble frame projected
    on so it reads as one round, glossy bubble in the inventory."""
    cx, cy, cz = centre
    parts = []
    hs = [-radius + 2 * radius * i / slices for i in range(slices + 1)]
    for h0, h1 in zip(hs, hs[1:]):
        hm = (h0 + h1) / 2
        r = math.sqrt(max(0.0, radius * radius - hm * hm)) * 0.97
        parts += stagger(prism((cx, cy + hm, cz), r, h1 - h0, tex, sides=8, cap=tex, shade=False, glow=glow))
    return project(parts, centre, radius, tex)


def dot(centre, size, tex, glow=7):
    """A tiny trailing bubble: one small cube turned onto its corner."""
    cx, cy, cz = centre
    s = size / 2
    e = box((cx - s, cy - s, cz - s), (cx + s, cy + s, cz + s), tex, uv="full", shade=False, glow=glow)
    return turn(e, x=35.26, y=45, origin=centre)


BUBBLES = [  # (centre, radius, texture, slices)
    ((-1.6, 15.9, 13.4), 3.0, "orb_a", 5),
    ((17.7, 4.3, 7.6), 2.8, "orb_b", 5),
    ((3.0, 1.9, 1.7), 1.8, "orb_b", 4),
    ((18.2, 15.2, 6.8), 1.25, "orb_a", 3),
    ((-0.9, 7.6, 4.2), 1.05, "orb_a", 3),
]
TRAILS = [((17.5, 8.1, 3.9), 0.65, "orb_a"), ((-1.3, 11.2, 11.6), 0.6, "orb_b")]


# --------------------------------------------------------------------------------------
# Static textures
# --------------------------------------------------------------------------------------

def paint_static():
    # silver bands: bright top edge, soft body, darker lower edge, glints every few texels
    band = canvas(32)
    px = band.load()
    for y in range(32):
        for x in range(32):
            c = SILVER_T[[5, 4, 3, 2][min(y, 3)]]
            if y == 0 and x % 7 == 2:
                c = WHITE
            px[x, y] = c
    save(band, "silver_band")
    save(canvas(32, fill=SILVER[5]), "silver_top")     # plain: overlapping caps must match exactly

    # ribbed silver cuffs
    cuff = canvas(32)
    px = cuff.load()
    for y in range(32):
        for x in range(32):
            rib = x % 2 == 0
            if y == 0:
                c = SILVER_T[6] if rib else SILVER_T[5]
            elif y == 1:
                c = SILVER_T[4] if rib else SILVER_T[3]
            elif y == 2:
                c = SILVER_T[3] if rib else SILVER_T[2]
            else:
                c = SILVER_T[2] if rib else SILVER_T[1]
            px[x, y] = c
    save(cuff, "cuff")

    # belt strap: periwinkle with a highlight, stitch dashes and a shaded lower edge
    belt = canvas(32)
    px = belt.load()
    rows = [BELT_T[4], BELT_T[3], BELT_T[3], BELT_T[2], BELT_T[1]]
    for y in range(32):
        for x in range(32):
            c = rows[min(y, 4)]
            if y in (1, 3) and x % 3 != 2:
                c = BELT_T[5] if y == 1 else BELT_T[4]
            px[x, y] = c
    save(belt, "belt")

    # inside the waistband: a silver lip over deep blue lining, and the shadowy floor
    lining = canvas(32, fill=FAB[1])
    px = lining.load()
    for x in range(32):
        px[x, 0] = SILVER_T[3]
    save(lining, "lining")
    floor = canvas(32, fill=FAB[0])
    px = floor.load()
    for y in range(32):
        for x in range(32):
            if y < 2 or (x + 2 * y) % 11 == 0:
                px[x, y] = FAB_T[1]
    save(floor, "lining_floor")

    save(canvas(32, fill=FAB[3]), "fab_flat")

    save(canvas(32, fill=SILVER[1]), "cuff_under")

    # buckle ring: polished silver, lit edge first
    ring = canvas(32)
    px = ring.load()
    for y in range(32):
        for x in range(32):
            px[x, y] = SILVER_T[[6, 5, 4, 4, 3, 2][min(x, 5)]]
    save(ring, "ring")


# --------------------------------------------------------------------------------------
# Worn layer (humanoid_leggings, 4x: 256 x 128)
# --------------------------------------------------------------------------------------
S = 4


WRAP5 = ["gooog", "ohhbo", "ohbbo", "obbso", "gosog"]   # one bubble of worn bubble wrap


def wrap_texel(x, y, ox=0, oy=0):
    """Bubble wrap at 4x: round 5-texel bubbles on a staggered grid, each with a white
    highlight upper left and a shaded lower right, film showing at the corners."""
    row = (y + oy) // 5
    cx = (x + ox + (2 if row % 2 else 0)) % 5
    cy = (y + oy) % 5
    ch = WRAP5[cy][cx]
    if ch == "o":
        return WRAP_T[3] if cx + cy < 4 else WRAP_T[1]
    return {"g": WRAP_T[1], "h": WHITE, "b": WRAP_T[4], "s": WRAP_T[2]}[ch]


def paint_worn():
    img = canvas(64 * S, 32 * S)
    px = img.load()

    def fill_face(name, painter):
        x0, y0, x1, y1 = region(HUMANOID, name, S)
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                c = painter(x - x0, y - y0, x1 - x0 + 1, y1 - y0 + 1)
                if c is not None:
                    px[x, y] = c

    # ---- legs: fabric to the ankle, ribbed silver cuff, bare below --------------------
    def leg_painter(face):
        def paint(x, y, w, h):
            if y >= 44:
                return None
            if y >= 37:                                   # cuff
                rib = x % 2 == 0
                if y == 37:
                    return SILVER_T[6] if rib else SILVER_T[5]
                if y == 43:
                    return SILVER_T[1]
                if y == 42:
                    return SILVER_T[2] if rib else SILVER_T[1]
                return SILVER_T[4] if rib else SILVER_T[3]
            if y == 36:
                return FAB_T[2]                            # fabric gathered into the cuff
            s = x / (w - 1)
            if face == "leg_front":
                c = fabric_pixel(s, y / 36, sheen=0.22)
            elif face == "leg_outer":
                c = fabric_pixel(s, y / 36, sheen=0.1)
                if 6 <= x <= 9:
                    c = SILVER_T[[3, 5, 4, 2][x - 6]]
            elif face == "leg_inner":
                c = blend(fabric_pixel(s, y / 36, sheen=0.5), FAB_T[2], 0.2)
            else:
                c = fabric_pixel(s, y / 36, sheen=0.3)
            if y <= 1:
                c = blend(c, FAB_T[2], 0.5)                # shade where the hips meet the belt
            return c
        return paint

    for face in ("leg_outer", "leg_front", "leg_inner", "leg_back"):
        fill_face(face, leg_painter(face))
    fill_face("leg_top", lambda x, y, w, h: FAB_T[4])
    size = img.size
    for face, col in (("leg_front", 3), ("leg_back", 4)):
        fx, fy, _, _ = region(HUMANOID, face, S)
        gloss_dashes(px, size, (fx + col,), fy + 2, fy + 35, 5 + col)

    # bubble-wrap cheek on the back of each leg: a round patch with a silver seam, so the
    # two legs together show a pair of round bubbly cheeks
    x0, y0, _, _ = region(HUMANOID, "leg_back", S)
    ccx, ccy, cr = 8.0, 10.0, 7.7
    for y in range(0, 21):
        for x in range(16):
            dx, dy = x + 0.5 - ccx, y + 0.5 - ccy
            d = math.hypot(dx, dy)
            lit = -(dx * 0.6 + dy) / (d + 1e-6)
            if d > cr + 1.2:
                continue
            if d > cr:
                if dy > -3:
                    px[x0 + x, y0 + y] = blend(px[x0 + x, y0 + y], FAB_T[1], 0.45)   # cast shadow
                continue
            if d > cr - 1.0:
                c = SILVER_T[5] if lit > 0.3 else (SILVER_T[4] if lit > -0.3 else SILVER_T[2])
            else:
                c = wrap_texel(x, y, 1, 0)
                k = d / cr
                if lit > 0.2 and k > 0.35:
                    c = blend(c, WHITE, 0.3 * k)
                elif lit < -0.2 and k > 0.35:
                    c = blend(c, FAB_T[1], 0.35 * k)
            px[x0 + x, y0 + y] = c

    # prints on the legs (static film at varied phases)
    prints = {
        "leg_front": [(5.0, 15.5, 4.6, 0.0), (12.0, 6.0, 2.7, 0.4), (11.0, 27.5, 3.6, 0.7), (3.5, 31.5, 2.0, 0.2),
                      (8.5, 3.0, 1.6, 0.55), (13.0, 17.0, 1.6, 0.85)],
        "leg_outer": [(3.0, 12.0, 2.4, 0.3), (12.5, 25.0, 2.6, 0.8), (3.0, 31.5, 1.5, 0.1)],
        "leg_inner": [(8.0, 10.0, 2.2, 0.6), (5.0, 27.0, 1.6, 0.9)],
        "leg_back": [(5.0, 28.5, 2.8, 0.35), (12.0, 32.5, 1.8, 0.65), (11.5, 23.5, 1.4, 0.15)],
    }
    for face, lst in prints.items():
        fx, fy, _, _ = region(HUMANOID, face, S)
        for cx, cy, r, phase in lst:
            print_bubble(img, fx + cx, fy + cy, r, 0.0, phase)

    # ---- body: the waistband (rows 28-47 of each body face) ---------------------------
    def belt_painter(face):
        def paint(x, y, w, h):
            if y < 28:
                return None
            if y == 28:
                return SILVER_T[5] if x % 5 else WHITE
            if y == 29:
                return SILVER_T[4]
            if y == 30:
                return SILVER_T[2]
            if 31 <= y <= 38:
                c = [BELT_T[4], BELT_T[3], BELT_T[3], BELT_T[3], BELT_T[3], BELT_T[3], BELT_T[2], BELT_T[1]][y - 31]
                if y in (32, 37) and x % 3 != 2:
                    c = BELT_T[5] if y == 32 else BELT_T[4]
                return c
            if y == 39:
                return SILVER_T[4]
            if y == 40:
                return SILVER_T[3]
            if y == 41:
                return SILVER_T[1]
            return fabric_pixel(x / (w - 1), 0.3, sheen=0.25) if y > 42 else FAB_T[3]
        return paint

    for face in ("body_right", "body_front", "body_left", "body_back"):
        fill_face(face, belt_painter(face))

    # belt loops (fabric tabs over the strap)
    for face, xs in (("body_front", (4, 26)), ("body_back", (5, 25)), ("body_right", (7,)), ("body_left", (6,))):
        fx, fy, _, _ = region(HUMANOID, face, S)
        for lx in xs:
            for y in range(29, 41):
                for k in range(3):
                    c = (FAB_T[5], FAB_T[4], FAB_T[3])[k]
                    if y in (29, 40):
                        c = FAB_T[3]
                    px[fx + lx + k, fy + y] = c

    # the bubble buckle on the front of the belt
    fx, fy, _, _ = region(HUMANOID, "body_front", S)
    bx, by = fx + 16.0, fy + 34.5
    for y in range(math.floor(by - 9), math.ceil(by + 9)):
        for x in range(math.floor(bx - 9), math.ceil(bx + 9)):
            dx, dy = x + 0.5 - bx, y + 0.5 - by
            d = math.hypot(dx, dy)
            lit = -(dx + dy) / (d * 1.4142 + 1e-6)
            if 7.0 < d <= 7.6:
                px[x, y] = SILVER_T[0]
            elif 5.2 < d <= 7.0:
                px[x, y] = SILVER_T[5 if lit > 0.35 else (4 if lit > -0.2 else (3 if lit > -0.6 else 2))]
    print_bubble(img, bx, by, 5.4, 0.0, 0.1, shadow=False)

    # the silver seam up the middle of the back, above the cheeks
    fx, fy, _, _ = region(HUMANOID, "body_back", S)
    for y in range(42, 48):
        px[fx + 15, fy + y] = SILVER_T[3]
        px[fx + 16, fy + y] = SILVER_T[1]
    return img


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def textures() -> None:
    fabric = paint_fabric_base()
    side = paint_side_base()
    save_animation(animate(lambda t: fab_frame(fabric, t), FRAMES), "fab", frametime=FRAMETIME, interpolate=True)
    save_animation(animate(lambda t: side_frame(side, t), FRAMES), "side", frametime=FRAMETIME, interpolate=True)
    save_animation(animate(lambda t: orb_frame(t, 0.0), FRAMES), "orb_a", frametime=FRAMETIME, interpolate=True)
    save_animation(animate(lambda t: orb_frame(t, 0.5), FRAMES), "orb_b", frametime=FRAMETIME, interpolate=True)
    seat_base = paint_seat_base()
    save_animation(animate(lambda t: seat_frame(seat_base, t), FRAMES), "seat", frametime=FRAMETIME,
                   interpolate=True)
    paint_static()
    save_layer(paint_worn(), "humanoid_leggings")


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def fbox(frm, to, up=None, down=None, skip=(), **kw):
    """A fabric box: its sides carry the projected fabric atlases."""
    (x0, y0, z0), (x1, y1, z1) = frm, to
    faces = {
        "north": ("fab", uv_front(x0, x1, y0, y1)),
        "south": ("fab", uv_back(x0, x1, y0, y1)),
        "east": ("side", uv_east(z0, z1, y0, y1)),
        "west": ("side", uv_west(z0, z1, y0, y1)),
        "up": up or "fab_flat",
        "down": down or "fab_flat",
    }
    return box(frm, to, "fab", faces=faces, skip=skip, **kw)


def rounded(x0, x1, y0, y1, z0, z1, c, **kw):
    """A fabric box with its four vertical edges cut back by c: two crossed boxes."""
    return [fbox((x0, y0, z0 + c), (x1, y1, z1 - c), **kw), fbox((x0 + c, y0, z0), (x1 - c, y1, z1), **kw)]


def legs():
    parts = []
    for i, lx in enumerate(LEG_X):
        leg = []
        y0, y1, hw, hd = THIGH
        leg += rounded(lx - hw, lx + hw, y0, y1, 8 - hd, 8 + hd, 0.5, skip=("up",))
        y0, y1, hw, hd = CALF
        leg += rounded(lx - hw, lx + hw, y0, y1, 8 - hd, 8 + hd, 0.5, skip=("up", "down"))
        y0, y1, hw, hd = CUFF
        for frm, to in (((lx - hw, y0, 8 - hd + 0.55), (lx + hw, y1, 8 + hd - 0.55)),
                        ((lx - hw + 0.55, y0, 8 - hd), (lx + hw - 0.55, y1, 8 + hd))):
            leg.append(box(frm, to, "cuff", faces={"up": "silver_top", "down": "cuff_under"}))
        turn(leg, -SPLAY if i == 0 else SPLAY, "z", (lx, HIPS[0] + 0.4, 8))
        parts += leg
    return parts


def hips():
    y0, y1, x0, x1, z0, z1 = HIPS
    return rounded(x0, x1, y0, y1, z0, z1, 0.6, skip=("up",))


def waist():
    """Silver-trimmed waistband: a lower trim ring, the periwinkle strap, and a hollow top
    frame whose recess shows the dark lining, so the top reads as the opening."""
    parts = []
    (x0, x1), (z0, z1) = WAIST_X, WAIST_Z
    ya, yb = TRIM_LO
    for frm, to in (((x0, ya, z0 + 0.7), (x1, yb, z1 - 0.7)), ((x0 + 0.7, ya, z0), (x1 - 0.7, yb, z1))):
        parts.append(box(frm, to, "silver_band", faces={"up": "silver_top"}))
    ya, yb = STRAP
    parts.append(box((x0 + 0.12, ya, z0 + 0.82), (x1 - 0.12, yb, z1 - 0.82), "belt",
                     faces={"up": "lining_floor"}, skip=("down",)))
    parts.append(box((x0 + 0.82, ya, z0 + 0.12), (x1 - 0.82, yb, z1 - 0.12), "belt", skip=("up", "down")))
    ya, yb = TRIM_HI
    w = 0.8
    frame = [((x0 + 0.7, ya, z0), (x1 - 0.7, yb, z0 + w), "south"),          # front bar
             ((x0 + 0.7, ya, z1 - w), (x1 - 0.7, yb, z1), "north"),          # back bar
             ((x0, ya, z0 + 0.7), (x0 + w, yb, z1 - 0.7), "east"),           # side bars
             ((x1 - w, ya, z0 + 0.7), (x1, yb, z1 - 0.7), "west")]
    for frm, to, inner in frame:
        parts.append(box(frm, to, "silver_band", faces={"up": "silver_top", inner: "lining", "down": "silver_top"}))
    # belt loops: little fabric tabs over the strap
    ya, yb = STRAP
    for lx in (3.1, 12.9):
        for z, zz in ((z0 - 0.2, z0 + 0.3), (z1 - 0.3, z1 + 0.2)):
            parts.append(box((lx - 0.45, ya - 0.1, z), (lx + 0.45, yb + 0.1, zz), "fab_flat",
                             faces={"north": "silver_top", "south": "silver_top"}))
    return parts


def buckle():
    """An oversized round buckle: a polished silver ring set with four studs, holding a
    glowing soap bubble that swirls like the floating ones."""
    bx, by = BUCKLE
    zf = WAIST_Z[0] + 0.12            # front face of the strap
    parts = arc((bx, by, zf - 0.38), 2.3, 0, 360, 12, 0.72, 0.82, "ring")
    for k in range(4):                # studs on the diagonals
        a = math.radians(45 + 90 * k)
        cx, cy = bx + 2.3 * math.cos(a), by + 2.3 * math.sin(a)
        stud = box((cx - 0.38, cy - 0.38, zf - 1.0), (cx + 0.38, cy + 0.38, zf - 0.6), "silver_top")
        parts.append(turn(stud, 45, "z", (cx, cy, zf - 0.8)))
    dome = []
    profile = [(0.0, 1.95), (-0.5, 1.9), (-0.95, 1.55), (-1.3, 0.9)]
    for (h0, r0), (h1, r1) in zip(profile, profile[1:]):
        dome += stagger(prism((bx, by, zf + (h0 + h1) / 2), (r0 + r1) / 2 * 0.97, abs(h1 - h0), "orb_a", axis="z",
                              sides=8, cap="orb_a", shade=False, glow=10))
    project(dome, (bx, by, zf - 0.45), 1.95, "orb_a")
    return parts + dome


SEAT_TIERS = [(3.05, 10.9, 12.3), (2.3, 12.3, 13.25)]       # (radius, z0, z1) of each tier
CIRCLE = [(1.0, 0.3), (0.92, 0.52), (0.72, 0.72), (0.52, 0.92), (0.3, 1.0)]   # a pixel-art circle


def seat():
    """Two round, puffy bubble-wrap cheeks on the back: each is a two-tier cushion whose
    tiers are pixel-art circles of axis-aligned boxes, so the wrap image lands exactly as
    seen from behind and Minecraft's shading rounds the edges."""
    parts = []
    for lx in LEG_X:
        cheek = []
        for r, z0, z1 in SEAT_TIERS:
            for k, (ax, ay) in enumerate(CIRCLE):
                cheek.append(box((lx - ax * r, SEAT_Y - ay * r, z0), (lx + ax * r, SEAT_Y + ay * r, z1 + 0.01 * k),
                                 "seat", skip=("north",)))
        parts += project(cheek, (lx, SEAT_Y, SEAT_Z), SEAT_R, "seat", rot=(0.0, 0.0, 0.0))
    return parts


def bubbles():
    parts = []
    for centre, radius, tex, slices in BUBBLES:
        parts += sphere(centre, radius, tex, slices)
    for centre, size, tex in TRAILS:
        parts.append(dot(centre, size, tex))
    return parts


def build():
    return legs() + hips() + waist() + buckle() + seat() + bubbles()


def displays(parts):
    d = display("leggings", parts, gui_rotation=GUI_ROT, gui_span=15.6)
    grip = (2.0, 18.9, 8.0)                          # held by the waistband's corner
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, grip, "fist", 0.42)
    # first person: held up to admire, buckle and prints toward you, turned a little
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (0.45, 0, -0.89)}, (8, 10, 8), (0.52, -0.24, -0.95), 0.42,
                                       pose=None)
    return d


def models() -> dict:
    parts = build()
    return {"main": model(parts, displays(parts))}
