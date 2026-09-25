"""Yellowtail: an uncommon ocean fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's olive / lime-yellow / gold scheme. A sleek amberjack torpedo: a
dark olive-teal back under a lit rim, the species' bright yellow stripe running from the
pointed snout through the eye to the tail base, a countershaded silver belly with staggered
scale marks, a gill cover behind the eye, a small low first dorsal ahead of the long second
dorsal, a long low anal fin, small pectoral and pelvic fins and the big deeply forked
golden tail (fins translucent with painted rays and a softer outline).

Animation (UNCOMMON, 12 frames x 3 ticks): the tail beats once per loop, the pectoral and
pelvic fins sway a pixel on offset phases, a soft glint slides along the back (frames 0-5)
and then a shimmer band of sparkling scales rolls across the flank from gill to tail
(frames 6-11).
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, sprite

ID = "fish_yellowtail"
NAME = "Yellowtail"
KIND = "item"
MODEL_KEY = "fish/yellowtail"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palettes (darkest -> lightest); shadows lean teal, lights lean yellow ----------------
OUTLINE = "#15291f"
OUTLINE_LIT = "#22392a"            # the outline a touch lighter on the side facing the light
FIN_OUTLINE = "#6e5314"            # softer amber edge where the outline only touches a fin
FIN_OUTLINE_ALPHA = 226
BACK = ["#17332c", "#224536", "#2f5a3b", "#48753c", "#6f9640", "#9fbc52"]
STRIPE = ["#c8931a", "#e9b927", "#ffdb4a", "#fff09a"]
SILVER = ["#5f7766", "#7f9784", "#a1b7a1", "#bfd2bb", "#d8e6d2", "#ecf4e6", "#fafdf5"]
SHEEN = ["#b3c99f", "#cfe0bd"]
FIN = ["#80520f", "#a96f16", "#d5961f", "#f2b92e", "#ffd456", "#ffea96"]
DFIN = [mix(BACK[3], FIN[1], 0.5), mix(BACK[4], FIN[3], 0.55), mix(BACK[5], FIN[4], 0.6)]
PFIN = [mix(SILVER[2], FIN[2], 0.5), mix(SILVER[4], FIN[3], 0.55), mix(SILVER[6], FIN[4], 0.5)]
EYE = ["#0d140e", "#ffffff"]
IRIS = "#d7ae33"
GLINT = "#fbffe2"
SHIMMER = "#fff5c2"
SPARK = "#fffff4"

# ---- silhouette ---------------------------------------------------------------------------
# Body columns: x -> (top y, bottom y), both inclusive. A pointed snout, a rounded forehead,
# the deepest point a third of the way back and a long taper to a slim peduncle.
COLS = {
    2: (9, 10), 3: (8, 11), 4: (8, 12), 5: (7, 12), 6: (7, 13), 7: (7, 14), 8: (7, 14),
    9: (8, 15), 10: (8, 16), 11: (9, 16), 12: (9, 17), 13: (10, 17), 14: (10, 18), 15: (11, 18),
    16: (12, 19), 17: (13, 19), 18: (14, 19), 19: (15, 20), 20: (16, 20), 21: (17, 20), 22: (18, 20),
}


def _stripe_y(x: int) -> float:
    """Centre of the yellow stripe: level from the snout to the eye, then down the flank to
    the tail base."""
    return 9.5 - 0.12 * (6 - x) if x < 6 else 9.5 + 0.56 * (x - 6)


# Fins as (x, y) -> letter: m membrane, r ray, over-body fins handled separately.
DORSAL = {  # first dorsal (low spines) then the long second dorsal, raised at the front
    (9, 7): "m", (10, 6): "r", (10, 7): "m", (11, 7): "r", (11, 8): "m", (12, 8): "m",
    (14, 8): "r", (14, 9): "m", (15, 8): "m", (15, 9): "r", (15, 10): "m", (16, 10): "m", (16, 11): "m",
    (17, 11): "r", (17, 12): "m", (18, 12): "m", (18, 13): "m", (19, 14): "m", (20, 15): "m",
    (21, 16): "m",
}
ANAL = {
    (15, 19): "m", (15, 20): "r", (16, 20): "m", (16, 21): "r", (17, 20): "m", (18, 20): "r",
    (19, 21): "m", (20, 21): "m", (21, 21): "m",
}
PELVIC = {"rest": {(9, 16): "m", (10, 17): "m", (11, 17): "r", (11, 18): "m"},
          "swept": {(9, 16): "m", (10, 17): "m", (11, 17): "r", (12, 18): "m"}}
# Pectoral fin over the flank, behind the gill cover: spread and folded.
PECT = {"spread": [(9, 13, "p"), (10, 13, "p"), (10, 14, "P"), (11, 14, "p"), (12, 15, "P"), (11, 15, "P")],
        "folded": [(9, 13, "p"), (10, 13, "p"), (11, 13, "p"), (10, 14, "P"), (11, 14, "P"), (12, 14, "P")]}
# The tail at rest: a deep fork, the rays running out along each lobe.
TAIL = {
    (27, 14): "m", (28, 14): "m",
    (26, 15): "m", (27, 15): "m", (28, 15): "m",
    (25, 16): "m", (26, 16): "r", (27, 16): "m",
    (23, 17): "m", (24, 17): "r", (25, 17): "m",
    (23, 18): "r", (24, 18): "m",
    (23, 19): "m",
    (23, 20): "r", (24, 20): "m",
    (23, 21): "m", (24, 21): "r", (25, 21): "m",
    (24, 22): "m", (25, 22): "r",
    (24, 23): "m", (25, 23): "m", (26, 23): "m",
    (25, 24): "m", (26, 24): "m",
    (25, 25): "m", (26, 25): "m",
    (26, 26): "m",
}
TAIL_ROOT = (22.8, 19.3)
AXIS = (math.cos(math.radians(29)), math.sin(math.radians(29)))
SNOUT = (2.0, 9.5)

# Head details: 2x2 eye on the stripe with a catchlight and a golden iris; mouth slit
# behind the pointed snout, pale lower jaw.
HEAD = {
    (5, 9): EYE[1], (6, 9): EYE[0], (5, 10): EYE[0], (6, 10): EYE[0], (7, 10): IRIS, (6, 11): IRIS,
    (3, 10): OUTLINE, (4, 11): SILVER[1], (2, 10): SILVER[5], (3, 11): SILVER[4],
}
# The gill cover's rear edge (darkened) and the lit rim just behind it.
GILL = [(8, 9), (8, 10), (9, 11), (9, 12), (8, 13), (8, 14)]
GILL_LIT = [(9, 10), (10, 12)]


def _body_pixels():
    return {(x, y) for x, (t, b) in COLS.items() for y in range(t, b + 1)}


BODY = _body_pixels()


def _zone(x: int, y: int) -> str:
    t, b = COLS[x]
    s = _stripe_y(x)
    hi = math.floor(s - 0.5)                  # the stripe's lit top row
    if y == t:
        return "rim"
    if y < hi - 1:
        return "back"
    if y == hi - 1:
        return "edge"                         # dark line above the stripe
    if y == hi:
        return "stripe_hi"
    if y == hi + 1:
        return "stripe"
    if y == b:
        return "belly_edge"
    if y <= hi + 2 + (1 if b - t >= 7 else 0) and x >= 9:
        return "flank"
    return "belly"


ZONES = {(x, y): _zone(x, y) for (x, y) in BODY}


def _body_colour(x: int, y: int, zone: str) -> str:
    t, b = COLS[x]
    if zone == "rim":
        return BACK[5] if x <= 9 else BACK[4] if x <= 16 else BACK[3]
    if zone == "back":
        return BACK[2] if y == t + 1 else BACK[1]
    if zone == "edge":
        return BACK[0] if x >= 7 else BACK[1]
    if zone == "stripe_hi":
        return STRIPE[3] if x <= 9 else STRIPE[2]
    if zone == "stripe":
        return STRIPE[1] if x <= 18 else STRIPE[0]
    if zone == "belly_edge":
        return SILVER[3] if x <= 5 else SILVER[2]
    if zone == "flank":                        # staggered scale marks on a pale sheen
        return SHEEN[0] if (x + 2 * y) % 4 == 0 else SHEEN[1]
    if y == b - 1:
        return SILVER[5]
    return SILVER[6] if x <= 13 else SILVER[5]


def _along(x: float, y: float) -> float:
    """Distance along the body axis from the snout (pixel centres)."""
    return (x + 0.5 - SNOUT[0]) * AXIS[0] + (y + 0.5 - SNOUT[1]) * AXIS[1]


def _tail(angle: float) -> dict:
    """The tail rotated `angle` degrees about its root (positive swings it toward the back),
    resampled with 4x4 supersampling so the lobes stay solid."""
    a = math.radians(angle)
    c, s = math.cos(a), math.sin(a)
    rx, ry = TAIL_ROOT
    out = {}
    for y in range(10, 31):
        for x in range(21, 32):
            hits = {}
            for sy in range(4):
                for sx in range(4):
                    px, py = x + (sx + 0.5) / 4 - rx, y + (sy + 0.5) / 4 - ry
                    # inverse rotation (image y points down, so "toward the back" is -angle)
                    qx, qy = px * c - py * s, px * s + py * c
                    key = (math.floor(qx + rx), math.floor(qy + ry))
                    if key in TAIL:
                        hits[TAIL[key]] = hits.get(TAIL[key], 0) + 1
            n = sum(hits.values())
            if n >= 7 and (x, y) not in BODY:
                out[(x, y)] = "r" if hits.get("r", 0) * 2 >= n else "m"
    return out


def _frame(t: float):
    wave_t = math.sin(2 * math.pi * t)
    tail = _tail(7.0 * wave_t)
    pect = "folded" if math.sin(2 * math.pi * t + 1.2) > 0.2 else "spread"
    pelvic = "swept" if math.sin(2 * math.pi * t + 2.4) > 0 else "rest"

    img = canvas(SIZE)
    px = img.load()
    filled = {}

    # fins behind the body: (colour ramp, alpha) per fin group
    def fin(pixels: dict, ramp, alpha: int):
        keys = set(pixels)
        for (x, y), kind in pixels.items():
            if (x, y) in BODY:
                continue
            open_ul = (x, y - 1) not in keys and (x, y - 1) not in BODY or \
                (x - 1, y) not in keys and (x - 1, y) not in BODY
            if kind == "r":
                colour, a = ramp[0], min(255, alpha + 16)
            elif open_ul:
                colour, a = ramp[2], alpha - 6
            else:
                colour, a = ramp[1], alpha
            c = rgba(colour)
            px[x, y] = (c[0], c[1], c[2], a)
            filled[(x, y)] = "fin"

    fin(tail, (FIN[2], FIN[3], FIN[4]), 222)
    fin(DORSAL, DFIN, 212)
    fin(ANAL, PFIN, 200)
    fin(PELVIC[pelvic], PFIN, 204)

    # body
    for (x, y), zone in ZONES.items():
        colour = _body_colour(x, y, zone)
        if (x, y) in GILL and zone != "rim":
            colour = mix(colour, OUTLINE, 0.38)
        elif (x, y) in GILL_LIT:
            colour = mix(colour, "#ffffff", 0.28)
        if (x, y) in HEAD:
            colour = HEAD[(x, y)]
        px[x, y] = rgba(colour)
        filled[(x, y)] = zone
    for x, y, part in PECT[pect]:
        base = px[x, y]
        tint = mix(PFIN[1], FIN[3], 0.35) if part == "p" else mix(PFIN[0], FIN[1], 0.4)
        px[x, y] = rgba(mix(base, tint, 0.6 if part == "p" else 0.75))
        filled[(x, y)] = "pect"

    step = round(t * FRAMES) % FRAMES
    if step <= 5:
        _glint(px, step, filled)
    else:
        _shimmer(px, step, filled)
    _outline(px, filled)
    return img


def _glint(px, step: int, filled: dict):
    """Frames 0-5: a soft glint slides along the back, snout to tail."""
    centre = 1.0 + 21.0 * step / 5.0
    g = GLINT
    for (x, y), zone in filled.items():
        if zone not in ("rim", "back", "stripe_hi") or (x, y) in HEAD:
            continue
        k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.2)
        k *= 0.72 if zone == "rim" else 0.45 if zone == "stripe_hi" else 0.4
        if k > 0:
            px[x, y] = rgba(mix(px[x, y], g, k))


def _spark_spot(x: int, y: int) -> bool:
    return (x + 2 * y) % 4 == 2


def _shimmer(px, step: int, filled: dict):
    """Frames 6-11: a shimmer band of sparkling scales rolls across the flank."""
    centre = 4.0 + 18.0 * (step - 6) / 5.0
    for (x, y), zone in filled.items():
        if zone in ("rim", "fin") or (x, y) in HEAD:
            continue
        k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.4)
        if k <= 0:
            continue
        spark = _spark_spot(x, y) and zone in ("flank", "belly", "stripe", "back", "edge")
        strength = (0.92 if spark else 0.3) * min(1.0, k * 1.35)
        if zone in ("back", "edge") and not spark:
            strength *= 0.6
        px[x, y] = rgba(mix(px[x, y], SPARK if spark else SHIMMER, strength))


def _outline(px, filled: dict):
    """1 px outline around the silhouette: dark olive-teal beside the body (a touch lighter
    on the side facing the top-left light), a softer amber where it only borders fins."""
    body = {k for k, z in filled.items() if z != "fin"}
    dark, lit = rgba(OUTLINE), rgba(OUTLINE_LIT)
    amber = rgba(FIN_OUTLINE)
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in filled:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + dx, y + dy) in filled]
            if not near:
                continue
            if any(p in body for p in near):
                facing = ((x, y + 1) in body or (x + 1, y) in body) and (x, y - 1) not in filled \
                    and (x - 1, y) not in filled
                px[x, y] = lit if facing else dark
            else:
                px[x, y] = (amber[0], amber[1], amber[2], FIN_OUTLINE_ALPHA)


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
