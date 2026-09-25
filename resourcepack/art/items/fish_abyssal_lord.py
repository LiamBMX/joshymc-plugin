"""Abyssal Lord: a legendary deep-ocean anglerfish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's near-black indigo / violet body, white eye and gold lure. Real
anglerfish anatomy: a huge head with a scowling brow and a gaping, fang-lined maw whose
lower jaw juts past the upper, a gill slit with a lit bevel, a short humped body tapering
to a rounded fan tail, a soft dorsal and anal fin set far back and a pectoral fin behind
the gill; the illicium arcs up from the forehead and hangs a glowing golden esca in front
of the face. Indigo skin in contour-following bands with a lit back, a scale lattice, a
countershaded dusky belly and a row of cyan photophores along the lower flank.

Animation (LEGENDARY, 20 frames x 2 ticks): the tail swings, the dorsal, anal and
pectoral fins flick a pixel, a soft glint slides along the back, the scale lattice drifts
violet -> pink -> cyan while a brighter iridescent band rolls head to tail, the
photophores pulse in a travelling wave, the eye glows with a breathing cyan halo, the esca
and a golden 1 px rim glow just outside the outline breathe together, and three gold
sparkles orbit the fish.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_abyssal_lord"
NAME = "Abyssal Lord"
KIND = "item"
MODEL_KEY = "fish/abyssal_lord"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 20
FRAMETIME = 2

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean blue, lights lean lilac ----
OUTLINE = "#0c0626"
OUTLINE_LIT = "#1d1150"
FIN_OUTLINE = ("#21134e", 232)
SKIN = ["#170d3a", "#23175c", "#312376", "#413290", "#5444ac", "#6c5cc8", "#9080e0", "#b6a8f4"]
BELLY = ["#2e2360", "#43377c", "#574b96", "#6d62b0"]
MOUTH = ["#1c0520", "#360b32", "#5c1a4a", "#80305e"]
TOOTH = ["#8a82b4", "#d8d2ef", "#fdfaff"]
GOLD = ["#5e320a", "#9a5c16", "#d69422", "#ffc832", "#ffe27a", "#fff8d8"]
FIN = ["#26175e", "#382482", "#4d36a0", "#684ebe", "#886ed6", "#ab94ea"]
EYE = ["#081232", "#2a86cc", "#8ee6ff", "#ffffff"]
CYAN = ["#2378b4", "#5ccff2", "#c8faff"]
IRIS = ["#a064ff", "#ff72d8", "#62e6ff"]          # iridescent sheen: violet -> pink -> cyan
GLINT = "#ece6ff"
RIM = "#ffc83c"
SPARK = "#fff3c0"

# ---- the art ---------------------------------------------------------------------------------
# '#' body (shaded automatically in bands that follow the contour), m/n mouth, t/T teeth,
# d dorsal fin, a anal fin. The tail, pectoral fin, eye and lure are painted separately.
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    "................................",  # 6
    "................................",  # 7
    "............####................",  # 8
    "..........########..............",  # 9
    "........###########d............",  # 10
    "......############ddd...........",  # 11
    "....################dd..........",  # 12
    "...##################dd.........",  # 13
    "...tTmm##############dd.........",  # 14
    "..tmTmmTm#############d.........",  # 15
    "..TtmmmtTmm############.........",  # 16
    "...#nnmnmtmm############........",  # 17
    "....###tT#mm############........",  # 18
    ".....###################........",  # 19
    ".......##################.......",  # 20
    ".........################.......",  # 21
    "...........##############.......",  # 22
    ".............###########........",  # 23
    "................aa#####.........",  # 24
    "..................aaa...........",  # 25
    "....................a...........",  # 26
    "................................",  # 27
    "................................",  # 28
    "................................",  # 29
    "................................",  # 30
    "................................",  # 31
]
GRID = {(x, y): ch for y, row in enumerate(ART) for x, ch in enumerate(row) if ch != "."}
BODY = {k for k, ch in GRID.items() if ch not in "da"}
SOLID = {k for k, ch in GRID.items() if ch == "#"}          # skin that gets banded

EYE_PX = {(14, 12): EYE[3], (15, 12): EYE[2], (14, 13): EYE[1], (15, 13): EYE[0]}
EYE_RING = [(13, 12), (13, 13), (14, 11), (15, 11), (16, 12), (16, 13), (14, 14), (15, 14)]
# Hand-placed skin details over the bands: (x, y) -> colour.
DETAIL = {
    (13, 12): SKIN[1], (14, 11): SKIN[1], (15, 11): SKIN[2], (13, 11): SKIN[3],   # scowling brow
    (13, 13): SKIN[4], (16, 13): SKIN[2],
    (12, 15): SKIN[1], (12, 16): SKIN[1], (12, 17): SKIN[1], (11, 18): SKIN[1], (11, 19): SKIN[1],
    (13, 15): SKIN[5], (13, 16): SKIN[4], (13, 17): SKIN[4], (12, 18): SKIN[3],   # gill-cover bevel
}
MOUTH_COL = {"m": MOUTH[0], "n": MOUTH[2]}
TEETH_COL = {"t": TOOTH[2], "T": TOOTH[1]}

# Lure: stalk pixels from the forehead up and forward to the esca (the glowing bulb).
STALK = [(14, 7), (14, 6), (13, 5), (13, 4), (12, 3), (11, 2), (10, 2), (9, 2), (8, 3)]
ESCA = (6.5, 4.8)

TAIL_PIVOT = (24.2, 21.6)
TAIL_DIR = 36.0                        # degrees, down-right
AXIS = (math.cos(math.radians(32)), math.sin(math.radians(32)))

DORSAL_UP = [(19, 9)]
ANAL_BACK = [(21, 26)]
PECT = {
    "spread": [(15, 17), (16, 17), (17, 17), (18, 17), (15, 18), (16, 18), (17, 18), (18, 18), (19, 18),
               (16, 19), (17, 19), (18, 19)],
    "folded": [(15, 17), (16, 17), (17, 17), (15, 18), (16, 18), (17, 18), (18, 18), (19, 18),
               (16, 19), (17, 19), (18, 19), (19, 19)],
}


def _bands(mask: set):
    """Distances to the contour for every skin pixel: (from top, from right), (from bottom,
    from left), counted along clean columns and rows."""
    kb, kv = {}, {}
    for (x, y) in mask:
        top = y
        while (x, top - 1) in mask:
            top -= 1
        bot = y
        while (x, bot + 1) in mask:
            bot += 1
        right = x
        while (right + 1, y) in mask:
            right += 1
        left = x
        while (left - 1, y) in mask:
            left -= 1
        kb[(x, y)] = (y - top, right - x)
        kv[(x, y)] = (bot - y, x - left)
    return kb, kv


KB, KV = _bands(SOLID)


def _photophores():
    out = []
    for x in (14, 17, 20, 23):
        ys = [y for (xx, y) in SOLID if xx == x]
        out.append((x, max(ys) - 3))
    return out


PHOTO = _photophores()


def _scale_mark(x: int, y: int) -> bool:
    return y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1


def _along(x: float, y: float) -> float:
    """Distance along the body axis from the snout, in pixels."""
    return (x + 0.5 - 3.0) * AXIS[0] + (y + 0.5 - 14.0) * AXIS[1]


def _skin(x: int, y: int):
    """Base colour and zone of a skin pixel from its bands (light from the top-left)."""
    (kb, kr), (kv, kl) = KB[(x, y)], KV[(x, y)]
    h = kb + kv + 1
    p = kb / max(1, h - 1)
    if kb == 0:
        return (SKIN[6] if x < 17 else SKIN[5]), "back"
    if kl == 0 and kb <= 3:
        return SKIN[5], "back"                       # lit snout front
    if kv == 0:
        return BELLY[0], "belly"
    if kr == 0:
        return SKIN[2], "flank"                      # shadowed rear edge
    if kb == 1:
        return (SKIN[5] if x < 15 else SKIN[4]), "back"
    if kb == 2:
        return SKIN[4], "back"
    if kv == 1:
        return BELLY[2], "belly"
    if kv == 2:
        return BELLY[1], "belly"
    if p < 0.42:
        return (SKIN[4] if _scale_mark(x, y) else SKIN[3]), "flank"
    if p < 0.66 or kv >= 5:
        return (SKIN[3] if _scale_mark(x, y) else SKIN[2]), "flank"
    return SKIN[1], "flank"


def _put(px, x, y, colour, alpha=255):
    r, g, b, _ = rgba(colour)
    px[x, y] = (r, g, b, alpha)


def _blend(px, x, y, colour, k):
    if k <= 0:
        return
    r, g, b, a = px[x, y]
    c = rgba(colour)
    k = min(1.0, k)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _tail_cov(sway: float) -> dict:
    """{pixel: is_ray} of the rounded fan tail swung by sway degrees."""
    ss = 4
    out = {}
    base = math.radians(TAIL_DIR + sway)
    for y in range(14, 31):
        for x in range(18, 31):
            hit = ray = 0
            for sy in range(ss):
                for sx in range(ss):
                    dx = x + (sx + 0.5) / ss - TAIL_PIVOT[0]
                    dy = y + (sy + 0.5) / ss - TAIL_PIVOT[1]
                    r = math.hypot(dx, dy)
                    rel = (math.degrees(math.atan2(dy, dx) - base) + 180) % 360 - 180
                    lim = 5.5 + 0.35 * math.cos(math.radians(rel * 2.4))
                    if abs(rel) <= 74 and 0.6 <= r <= lim:
                        hit += 1
                        if r > 1.8 and r < lim - 0.7 and any(abs(rel - a) * math.pi / 180 * r < 0.45
                                                            for a in (-46, -14, 18, 50)):
                            ray += 1
            if hit / (ss * ss) >= 0.45:
                out[(x, y)] = ray / (ss * ss) >= 0.3
    return out


def _cyclic(colours, ph):
    n = len(colours)
    f = (ph % 1.0) * n
    i = int(f)
    return mix(colours[i % n], colours[(i + 1) % n], f - i)


def _frame(t: float):
    sway = math.sin(2 * math.pi * t)
    ripple = math.sin(2 * math.pi * (t - 0.2))
    flap = math.sin(2 * math.pi * (t + 0.3))
    breath = wave(t)

    img = canvas(SIZE)
    px = img.load()
    zones = {}

    # --- fins behind the body -------------------------------------------------------------
    tail = _tail_cov(7.0 * sway)
    for (x, y), ray in tail.items():
        if (x, y) in BODY:
            continue
        free = lambda dx, dy: (x + dx, y + dy) not in tail and (x + dx, y + dy) not in BODY  # noqa: E731
        lit = free(-1, 0) or free(0, -1)
        edge = free(1, 0) or free(0, 1)
        if lit:
            _put(px, x, y, FIN[5], 222)
        elif ray:
            _put(px, x, y, FIN[1], 236)
        elif edge:
            _put(px, x, y, FIN[4], 210)
        else:
            _put(px, x, y, FIN[3], 206)
        zones[(x, y)] = "fin"
    fins = {k: ch for k, ch in GRID.items() if ch in "da"}
    if ripple > 0.35:
        fins.update({k: "d" for k in DORSAL_UP})
    if ripple < -0.35:
        fins.update({k: "a" for k in ANAL_BACK})
    for (x, y), ch in fins.items():
        base_near = any((x + dx, y + dy) in BODY for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        open_top = (x, y - 1) not in fins and (x, y - 1) not in BODY
        if open_top:
            _put(px, x, y, FIN[4], 218)
        elif base_near:
            _put(px, x, y, FIN[1], 228)
        else:
            _put(px, x, y, FIN[3] if (x + y) % 2 else FIN[2], 208)
        zones[(x, y)] = "fin"

    # --- body -------------------------------------------------------------------------------
    for (x, y) in BODY:
        ch = GRID[(x, y)]
        if ch in MOUTH_COL:
            _put(px, x, y, MOUTH_COL[ch])
            zones[(x, y)] = "mouth"
        elif ch in TEETH_COL:
            _put(px, x, y, TEETH_COL[ch])
            zones[(x, y)] = "tooth"
        else:
            colour, zone = _skin(x, y)
            _put(px, x, y, colour)
            zones[(x, y)] = zone
    for (x, y), colour in DETAIL.items():
        _put(px, x, y, colour)
        zones[(x, y)] = "head"
    for (x, y), colour in EYE_PX.items():
        _put(px, x, y, colour)
        zones[(x, y)] = "eye"

    # --- effects on the skin ----------------------------------------------------------------
    # 1) a soft glint slides along the back, snout to tail, during the first 40% of the loop
    if t < 0.4:
        centre = -2.0 + 28.0 * t / 0.4
        for (x, y), zone in zones.items():
            if zone != "back" and (x, y) not in DETAIL:
                continue
            if (x, y) in SOLID and KB[(x, y)][0] > 2:
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.0) * 0.5
            _blend(px, x, y, GLINT, k)
    # 2) iridescent sheen: the scale lattice always drifts violet -> pink -> cyan, and a
    #    brighter band of colour rolls head to tail in the second half of the loop
    band = -4.0 + 32.0 * (t - 0.45) / 0.5 if 0.45 <= t < 0.95 else None
    for (x, y), zone in zones.items():
        if zone not in ("flank", "back", "belly") or (x, y) in PHOTO:
            continue
        along = _along(x, y)
        hue = _cyclic(IRIS, along / 14.0 - t)
        k = 0.2 if (_scale_mark(x, y) and zone == "flank") else 0.0
        if band is not None:
            b = max(0.0, 1.0 - abs(along - band) / 3.4)
            k += b * (0.66 if _scale_mark(x, y) or zone == "back" else 0.42)
        _blend(px, x, y, hue, k)

    # photophores along the lower flank pulse in a wave that runs head to tail
    for i, (x, y) in enumerate(PHOTO):
        k = wave(t, -i * 0.12)
        _put(px, x, y, mix(CYAN[0], CYAN[2], k))
        zones[(x, y)] = "photo"
        if (x + 1, y) in SOLID:
            _blend(px, x + 1, y, CYAN[1], 0.15 + 0.3 * k)

    # pectoral fin over the flank
    pose = "folded" if flap > 0.2 else "spread"
    for (x, y) in PECT[pose]:
        lower = (x, y + 1) not in PECT[pose]
        top = (x, y - 1) not in PECT[pose]
        tint = FIN[1] if lower else FIN[5] if top else FIN[4] if (x + y) % 2 else FIN[3]
        _blend(px, x, y, tint, 0.64)
        zones[(x, y)] = "pect"

    # radiant eye: the iris brightens and a soft cyan halo breathes around it
    glow = wave(t, 0.25)
    _blend(px, 15, 12, EYE[3], 0.5 * glow)
    for (x, y) in EYE_RING:
        _blend(px, x, y, EYE[2], 0.12 + 0.3 * glow)

    # --- outline ----------------------------------------------------------------------------
    filled = set(zones)
    dark, lit = rgba(OUTLINE), rgba(OUTLINE_LIT)
    fo = rgba(FIN_OUTLINE[0])
    outline_px = set()
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in filled:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + dx, y + dy) in filled]
            if not near:
                continue
            facing = ((x, y + 1) in filled or (x + 1, y) in filled) and (x, y - 1) not in filled \
                and (x - 1, y) not in filled
            if any(p in BODY for p in near):
                px[x, y] = lit if facing else dark
            else:
                px[x, y] = (fo[0], fo[1], fo[2], FIN_OUTLINE[1])
            outline_px.add((x, y))

    # --- golden rim glow just outside the outline, breathing -----------------------------
    shape = filled | outline_px
    rim = rgba(RIM)
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in shape:
                continue
            if any((x + dx, y + dy) in shape for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[x, y] = (rim[0], rim[1], rim[2], round(12 + 150 * breath))

    # --- lure ---------------------------------------------------------------------------------
    for i, (x, y) in enumerate(STALK):
        _put(px, x, y, SKIN[6] if 3 <= i <= 7 else SKIN[5])
    ex, ey = ESCA
    for y in range(0, 10):
        for x in range(1, 12):
            d = math.hypot(x + 0.5 - ex, y + 0.5 - ey)
            if d < 1.2:
                _put(px, x, y, GOLD[5] if d < 0.6 else GOLD[4])
            elif d < 1.75:
                _put(px, x, y, GOLD[3] if (x + 0.5 < ex or y + 0.5 < ey) else GOLD[2])
            elif d < 2.7 and px[x, y][3] == 0:
                a = round((50 + 130 * breath) * (1 - (d - 1.75) / 0.95))
                if a > 8:
                    _put(px, x, y, GOLD[4], a)
    _blend(px, int(ex), int(ey), "#ffffff", 0.6 * breath)

    # --- orbiting sparkles --------------------------------------------------------------------
    ca, sa = math.cos(math.radians(34)), math.sin(math.radians(34))
    for i in range(3):
        ph = (t / 3.0 + i / 3.0) % 1.0
        ang = 2 * math.pi * ph
        lx, ly = 15.5 * math.cos(ang), 10.5 * math.sin(ang)
        sx, sy = round(16.0 + lx * ca - ly * sa), round(16.5 + lx * sa + ly * ca)
        if not (1 <= sx <= 30 and 1 <= sy <= 30) or (sx, sy) in shape:
            continue
        amount = 0.3 + 0.7 * wave(ph * 3.0)
        sparkle(img, sx, sy, amount, SPARK, reach=2)
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
