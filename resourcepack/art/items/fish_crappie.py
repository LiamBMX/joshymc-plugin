"""Crappie: an uncommon panfish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's pale sage-silver body inside a dark green-slate outline. Drawn as
a black crappie: a deep, flat, almost diamond body that climbs steeply behind a small
head, an olive back under a lit rim, silvery sage flanks with a faint lavender iridescence
and the species' irregular dark olive speckles, a countershaded cream belly, a big eye
with a golden iris high on the head, an upturned mouth with a jutting lower jaw, the gill
cover with its dark opercular spot, and the crappie's signature pair of large, rounded,
mirror-image dorsal and anal fins set far back (short spines in front, a tall soft lobe
behind), their rays banded dark and light like the crappie's spotted fins, ahead of a gently
forked tail with banded rays.

Animation (UNCOMMON, 12 frames x 3 ticks): the tail wags once per loop with its tips
leading, the pectoral and pelvic fins flutter a pixel, a soft glint slides along the back
(frames 1-5), then a lavender-white shimmer band of sparkling scales rolls across the body
from head to tail (frames 7-11).
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, sprite

ID = "fish_crappie"
NAME = "Crappie"
KIND = "item"
MODEL_KEY = "fish/crappie"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 12
FRAMETIME = 3
SHIFT = (0, 0)           # nudges the whole drawing to centre it in the frame

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean teal, lights lean straw ----
OUTLINE = "#1e3228"
OUTLINE_LIT = "#2a4535"
BACK = ["#263d2e", "#33503a", "#456645", "#5b7d54", "#7c9a68", "#a3ba86"]
SAGE = ["#6b7c72", "#86968a", "#9fae9d", "#b6c3ae", "#cad4bf", "#dce4cf", "#edf2e1"]
BELLY = ["#c9cfb6", "#e2e6cf", "#f3f5e6"]
SHEEN = "#bab6cd"                       # the old sprite's lavender iridescence
SPOT = ["#243a2d", "#3b5543", "#6f8570"]
GOLD = ["#8a6e30", "#c7a24c"]
EYE = ["#0b1511", "#ffffff"]
FIN = ["#2c4434", "#46614b", "#6a8266", "#98ab8e", "#b5c4a8", "#d3dec5"]

BODY = {  # letter: (colour, zone)
    # back: rim light (bright at the forehead, fading aft), olive back, olive-sage blend
    "K": (BACK[5], "back"), "k": (BACK[4], "back"), "H": (BACK[4], "back"),
    "b": (BACK[3], "back"), "B": (BACK[2], "back"), "c": (mix(BACK[4], SAGE[2], 0.45), "back"),
    # flanks: upper sage, sage, lavender sheen, light sage
    "s": (SAGE[2], "flank"), "S": (SAGE[3], "flank"), "L": (mix(SAGE[3], SHEEN, 0.6), "flank"), "l": (SAGE[4], "flank"),
    # belly (countershaded, with a shaded lower edge)
    "v": (BELLY[0], "belly"), "w": (BELLY[1], "belly"), "W": (BELLY[2], "belly"),
    # speckles: dark and faded
    "x": (SPOT[0], "spot"), "X": (SPOT[2], "spot"),
    # head: cheek, gill cover line, opercular spot, mouth line, pale lower jaw
    "h": (SAGE[3], "head"), "g": (SAGE[1], "line"), "n": (SPOT[1], "line"),
    "m": (OUTLINE, "line"), "j": (BELLY[1], "head"),
    # eye: 2x2 pupil with a catchlight, a golden iris
    "E": (EYE[0], "eye"), "e": (EYE[1], "eye"), "i": (GOLD[1], "eye"),
}

# Body rows: y -> (first x, letters). Back along the top and the upper-right edge (under
# the dorsal fin), flanks through the middle, belly along the lower-left edge.
BODY_ROWS = {
    4: (5, "KKKKK"),
    5: (3, "KKHeEicKKkk"),
    6: (2, "jmhiEEhhnbbbkk"),
    7: (3, "jmmhhhgnscbbBk"),
    8: (3, "vjjhhhgSLscbBBB"),
    9: (4, "vjwhgSlSSscbbBB"),
    10: (4, "vwWglllSLSsscbB"),
    11: (5, "vwWlllSSSsccbBB"),
    12: (5, "vvwWllLSSsscbBB"),
    13: (6, "vwWwlllSLsccbBB"),
    14: (7, "vvwWllSSSscbBB"),
    15: (8, "vwwWlllSSscbBB"),
    16: (9, "vvwwwllLSscbB"),
    17: (10, "vvvwwwllSscb"),
    18: (13, "vvvvwlSsc"),
    19: (18, "vvw"),
}

# Fins: membrane regions as y -> [(x0, x1), ...] minus cut pixels, and rays as pixel runs
# from base to tip. Pixels already taken by the body are skipped.
DORSAL = {
    "rows": {3: [(13, 13), (15, 18)], 4: [(11, 20)], 5: [(14, 21)], 6: [(16, 22)], 7: [(17, 23)], 8: [(18, 23)],
             9: [(19, 23)], 10: [(19, 23)], 11: [(20, 22)], 12: [(20, 22)], 13: [(21, 22)], 14: [(21, 21)]},
    "cut": [(20, 4), (23, 7), (23, 10), (22, 13)],
    # banded rays, base to tip: dark ("p") and light ("r") bars alternate like the crappie's
    # spotted fin rays
    "rays": [[(11, 4)], [(13, 4), (13, 3)], [(14, 5), (15, 4), (15, 3)], [(16, 6), (17, 5), (17, 4), (17, 3)],
             [(18, 7), (19, 6), (20, 5)], [(19, 9), (20, 8), (21, 7), (22, 6)],
             [(20, 11), (21, 10), (22, 9), (23, 8)], [(21, 13), (22, 12), (22, 11)]],
}
ANAL = {
    "rows": {17: [(9, 9)], 18: [(8, 12)], 19: [(8, 17)], 20: [(9, 16)], 21: [(10, 14)], 22: [(11, 12)]},
    "cut": [(8, 18), (16, 20), (14, 21)],
    "rays": [[(9, 17), (9, 18), (8, 19)], [(11, 18), (10, 19), (10, 20), (10, 21)],
             [(13, 19), (12, 20), (12, 21), (11, 22)], [(15, 19), (14, 20), (13, 21)], [(17, 19)]],
}
TAIL = {
    "rows": {17: [(22, 27)], 18: [(22, 28)], 19: [(21, 27)], 20: [(20, 25)], 21: [(21, 24)], 22: [(21, 24)],
             23: [(22, 24)], 24: [(22, 24)], 25: [(23, 23)]},
    "cut": [(27, 19), (25, 20)],
    "rays": [[(22, 18), (23, 18), (24, 18), (25, 18), (26, 18), (27, 18)],
             [(21, 20), (22, 21), (22, 22), (23, 23), (23, 24)]],
}
TAIL_BASE = (21.5, 18.5)        # where the tail joins the peduncle
AXIS = (math.cos(math.radians(38)), math.sin(math.radians(38)))

MEMBRANE_ALPHA, EDGE_ALPHA, BASE_ALPHA, RAY_ALPHA, SPOT_ALPHA = 196, 190, 216, 226, 222
FIN_OUTLINE_ALPHA = 226

# Pectoral fin poses (x, y) laid over the body just behind the gill cover, and the pixels of
# its dark trailing edge.
PECT = {
    "spread": [(10, 8), (10, 9), (11, 9), (11, 10), (12, 10), (12, 11), (13, 11)],
    "folded": [(10, 8), (11, 8), (11, 9), (12, 9), (12, 10), (13, 10), (13, 11)],
}
PECT_EDGE = {"spread": {(11, 10), (12, 11), (13, 11)}, "folded": {(12, 10), (13, 10), (13, 11)}}
PECT_TINT, PECT_DARK = FIN[4], FIN[1]
# Pelvic fin poses (hanging below the belly, and swept back a pixel).
PELVIC = {"rest": [(4, 12), (4, 13), (5, 13), (4, 14), (5, 14)],
          "swept": [(4, 12), (5, 13), (5, 14), (6, 14), (6, 15)]}

GLINT = "#f6ffe6"
SHIMMER = "#ebe5f7"
SPARK = "#fdfff6"


# Extra speckle clusters laid over the lettering: the black crappie's irregular dark
# mottling, bold on the upper flank and fading ("X") toward the belly.
SPECKLES = {
    # dark blotches on the upper flank
    (12, 6): "x", (13, 7): "x",
    (15, 8): "x", (15, 9): "x", (16, 9): "x",
    (12, 9): "x", (13, 9): "x",
    (16, 11): "x", (17, 11): "x", (17, 12): "x",
    (14, 12): "x", (14, 13): "x",
    (18, 14): "x", (18, 15): "x",
    (19, 16): "x",
    # faded ones toward the belly
    (10, 10): "X", (11, 13): "X", (12, 14): "X", (15, 15): "X", (16, 15): "X", (13, 16): "X",
    (17, 17): "X", (9, 13): "X",
}


def _body_letters() -> dict:
    out = {}
    for y, (x0, letters) in BODY_ROWS.items():
        for i, ch in enumerate(letters):
            out[(x0 + i, y)] = ch
    for p, ch in SPECKLES.items():
        if p in out:
            out[p] = ch
    return out


BODY_PX = _body_letters()


def _fin_letters(fin: dict, membrane: str, light: str, dark: str) -> dict:
    out = {}
    for y, spans in fin["rows"].items():
        for x0, x1 in spans:
            for x in range(x0, x1 + 1):
                if (x, y) not in BODY_PX:
                    out[(x, y)] = membrane
    for p in fin["cut"]:
        out.pop(p, None)
    for ray in fin["rays"]:
        for i, p in enumerate(ray):
            if p in out:
                out[p] = dark if i % 2 == 0 else light
    return out


DORSAL_PX = _fin_letters(DORSAL, "f", "r", "p")
ANAL_PX = _fin_letters(ANAL, "f", "r", "p")
TAIL_PX = _fin_letters(TAIL, "t", "T", "q")
FIN_COLOURS = {"f": FIN[3], "r": FIN[2], "p": FIN[0], "t": FIN[3], "T": FIN[2], "q": FIN[0], "a": FIN[4]}


def _tail(near: int, far: int) -> dict:
    """The tail flexed: pixels 2-4 px out from the root move `near` rows, the lobes further
    out move `far` rows (positive: down), so the tips lead the sway."""
    if near == 0 and far == 0:
        return dict(TAIL_PX)
    out = {}
    for (x, y), ch in TAIL_PX.items():
        along = (x + 0.5 - TAIL_BASE[0]) * AXIS[0] + (y + 0.5 - TAIL_BASE[1]) * AXIS[1]
        dy = far if along >= 3.6 else near if along >= 1.8 else 0
        out.setdefault((x, y + dy), ch)
    step = 1 if far > 0 else -1
    for (x, y) in list(TAIL_PX):
        if (x, y) not in out and (x, y - step) in out and (x, y + step) in out:
            out[(x, y)] = "t"
    return out


def _compose(t: float):
    wag = math.sin(2 * math.pi * t)
    near, far = round(0.7 * wag), round(1.35 * wag)
    pect = "folded" if math.sin(2 * math.pi * 2 * t + 1.0) > 0.3 else "spread"
    pelvic = "swept" if math.sin(2 * math.pi * t + 2.0) > 0 else "rest"
    layer = dict(BODY_PX)
    for src in (DORSAL_PX, ANAL_PX, _tail(near, far)):
        for key, ch in src.items():
            layer.setdefault(key, ch)
    for p in PELVIC[pelvic]:
        layer.setdefault(p, "a")
    return layer, pect


SCALED = {"l": "S", "w": "l"}      # letter -> its scale-mark letter


def _scale_mark(x: int, y: int) -> bool:
    """A sparse diamond lattice of scale marks on the pale flank and belly."""
    return y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1


def _paint(layer: dict, pect: str):
    img = canvas(SIZE)
    px = img.load()
    sx, sy = SHIFT
    zones = {}
    for (x, y), ch in layer.items():
        X, Y = x + sx, y + sy
        if ch in BODY:
            if ch in SCALED and x >= 9 and _scale_mark(x, y):
                ch = SCALED[ch]
            colour, zone = BODY[ch]
            if (x, y) in PECT[pect]:
                dark = (x, y) in PECT_EDGE[pect]
                colour = mix(colour, PECT_DARK if dark else PECT_TINT, 0.72 if dark else 0.55)
                zone = "pect"
            px[X, Y] = rgba(colour)
            zones[(X, Y)] = zone
            continue
        near = [layer.get((x + dx, y + dy)) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        colour = FIN_COLOURS[ch]
        if ch in "rT":
            alpha = RAY_ALPHA
        elif ch in "pq":
            alpha = SPOT_ALPHA
        elif any(n is None for n in near):
            # fin edge: catches the light on the upper/left side
            lit = layer.get((x, y - 1)) is None or layer.get((x - 1, y)) is None
            colour, alpha = (FIN[5] if lit else FIN[4]), EDGE_ALPHA
        elif any(n in BODY for n in near):
            colour, alpha = mix(FIN[2], FIN[3], 0.4), BASE_ALPHA
        else:
            alpha = MEMBRANE_ALPHA
        c = rgba(colour)
        px[X, Y] = (c[0], c[1], c[2], alpha)
        zones[(X, Y)] = "fin"
    # 1 px outline around the silhouette: solid beside the body, a touch translucent where
    # it only borders fins, a touch lighter on the side facing the top-left light
    filled = set(zones)
    solid = {(x + sx, y + sy) for (x, y) in BODY_PX}
    dark, lit = rgba(OUTLINE), rgba(OUTLINE_LIT)
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in filled:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + dx, y + dy) in filled]
            if not near:
                continue
            facing_light = ((x, y + 1) in filled or (x + 1, y) in filled) and \
                (x, y - 1) not in filled and (x - 1, y) not in filled
            o = lit if facing_light else dark
            px[x, y] = o if any(p in solid for p in near) else (o[0], o[1], o[2], FIN_OUTLINE_ALPHA)
    return img, zones


def _along(x: int, y: int) -> float:
    """Distance along the body axis from the snout, in frame pixels."""
    return (x + 0.5 - 2.5) * AXIS[0] + (y + 0.5 - 5.5) * AXIS[1]


def _sparkle_spot(x: int, y: int) -> bool:
    """A diamond lattice: the scales that catch the light as the shimmer passes."""
    return y % 2 == 1 and (x + y // 2) % 2 == 0


def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _frame(t: float):
    layer, pect = _compose(t)
    img, zones = _paint(layer, pect)
    px = img.load()
    step = round(t * FRAMES)
    if 1 <= step <= 5:
        # a soft glint slides along the back, snout to tail
        centre = 2.0 + 18.0 * (step - 1) / 4.0
        for (x, y), zone in zones.items():
            if zone != "back":
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.4) * 0.72
            if k > 0:
                _blend(px, x, y, GLINT, k)
    elif step >= 7:
        # a shimmer band of sparkling scales rolls across the body, head to tail
        centre = 3.0 + 16.0 * (step - 7) / 4.0
        for (x, y), zone in zones.items():
            if zone not in ("flank", "belly", "back", "spot", "pect", "head"):
                continue
            k = max(0.0, 1.0 - abs(_along(x, y) - centre) / 3.0)
            if k <= 0:
                continue
            spark = _sparkle_spot(x, y) and zone in ("flank", "belly", "back")
            strength = (0.92 if spark else 0.36) * min(1.0, k * 1.3)
            _blend(px, x, y, SPARK if spark else SHIMMER, strength)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
