"""Carp: a common freshwater fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's olive / khaki-gold / rosy-tan scheme. A deep, hump-backed common
carp: a dark olive-bronze back under a lit rim, golden-bronze flanks netted with big
dark-edged scales, a countershaded buttery belly, a smooth scaleless head with a bevelled
gill cover, a small eye with a golden iris, a small mouth with a dangling barbel, the long
dorsal fin running from the hump almost to the tail with its lit serrated spine, and
translucent fins with painted rays (the paired fins, the anal fin and the lower tail lobe
blush orange, as on real carp).

Animation (COMMON, 8 frames x 3 ticks): the tail beats once per loop with its tips
leading, the pectoral and pelvic fins sway a pixel on offset phases, and a soft glint
slides along the back from snout to tail, then rests.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, shade, sprite

ID = "fish_carp"
NAME = "Carp"
KIND = "item"
MODEL_KEY = "fish/carp"
COUNTERPART = "item/salmon"

SIZE = 32
FRAMES = 8
FRAMETIME = 3
SHIFT = (0, 1)           # the art below is drawn one pixel above centre

# ---- palettes (darkest -> lightest): shadows lean umber / olive, lights lean yellow -------
OUTLINE = "#2b1c10"
OUTLINE_LIT = "#3d2a15"            # the outline where it faces the top-left light
FIN_OUTLINE_ALPHA = 236
BACK = ["#2e2a12", "#433d1b", "#595124", "#71672e", "#91853e", "#b4a75a"]
FLANK = ["#7d5a24", "#9a742d", "#b68c37", "#cfa644", "#e2bd57", "#f0d47c", "#f9e9ab"]
ROSE = ["#a4704a", "#c89464", "#e0b079", "#f0cb96"]   # the old sprite's rosy-tan lower flank
BELLY = ["#c39561", "#dcb784", "#f0d8aa", "#f9ebc9", "#fef6e0"]
HEAD = ["#3f3419", "#5f4f25", "#806c31", "#a38b40", "#c2a852", "#dcc475"]
FIN = ["#44321a", "#5e4623", "#7a5d2f", "#98773c", "#b8964f", "#d8b978"]
RED = ["#6a2e17", "#8f3e1f", "#b4562b", "#d0733c", "#e89a5f"]
EYE = ["#150d07", "#ffffff"]
IRIS = "#d4a53a"
BARBEL = "#7a5f2c"
GLINT = "#fff7d4"

# ---- the art (x 0..31 as drawn; SHIFT moves it to centre) ---------------------------------
# '#' is body (coloured from PAINT below), the other letters are
# head details and fins. The tail, pectoral and pelvic fins are layers of their own.
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "...........s....................",  # 4
    "..........sDD...................",  # 5
    ".........sDdDD..................",  # 6
    ".......####DDdD.................",  # 7
    "....#########dD.................",  # 8
    "..j#eE###n####Dd................",  # 9
    "..m#EEi#cn######Dd..............",  # 10
    "...j#i##cn#######DD.............",  # 11
    "....###cn#########dD............",  # 12
    ".....#cn###########DD...........",  # 13
    "......n#############dD..........",  # 14
    ".......##############D..........",  # 15
    "........##############..........",  # 16
    "...........############.........",  # 17
    "..............#########.........",  # 18
    ".................######.........",  # 19
    ".................aAA............",  # 20
    "..................aA............",  # 21
    "...................A............",  # 22
]
# letter: (colour, zone) of the head details drawn over the body.
DETAIL = {
    "E": (EYE[0], "eye"), "e": (EYE[1], "eye"), "i": (IRIS, "eye"),
    "n": (HEAD[1], "head"), "c": (HEAD[5], "head"), "m": (OUTLINE, "line"), "j": (BELLY[3], "head"),
}
# Hand-painted body colours, rows 7-19 (letters in PAINT_KEY; '.' = a detail or no body).
# The scale net runs in rows parallel to the back: alternating dark rims and lit centres.
PAINT = [
    ".......5555.....................",  # 7
    "....555222244...................",  # 8
    "...L..JJK.b224..................",  # 9
    "...K...K..cb4244................",  # 10
    "....M.LL..bcba324...............",  # 11
    "....sxM..ebecac324..............",  # 12
    ".....t..ecedbdcab24.............",  # 13
    ".......xxcfebedadb24............",  # 14
    ".......vyxxceebddab24...........",  # 15
    "........vvvyyxcfdbdb24..........",  # 16
    "...........vvvyxueebc24.........",  # 17
    "..............vvvyxcdb3.........",  # 18
    ".................vvwwss.........",  # 19
]
FINS = {  # letter: (colour, alpha, zone)
    "D": (FIN[2], 212, "fin"), "d": (FIN[1], 232, "fin"), "s": (FIN[5], 236, "fin"),
    "A": (RED[3], 210, "fin"), "a": (RED[1], 232, "fin"),
    "T": (FIN[3], 212, "tail"), "U": (FIN[1], 232, "tail"),
    "t": (RED[3], 210, "tail"), "u": (RED[1], 232, "tail"),
    "p": (RED[3], 214, "fin"), "P": (RED[1], 232, "fin"),
}
# Tail (neutral pose) as rows from TAIL_AT: upper lobe olive, lower lobe blushing orange.
TAIL_AT = (22, 13)
TAIL = [
    ".......T",  # 13
    ".....TTT",  # 14
    "...TTUT.",  # 15
    ".TTUTT..",  # 16
    ".TUTT...",  # 17
    ".tTT....",  # 18
    ".tut....",  # 19
    ".tut....",  # 20
    "..ut....",  # 21
    "..tu....",  # 22
    "..ttt...",  # 23
    "...tt...",  # 24
    "...t....",  # 25
]
TAIL_BASE = (22.5, 18.0)      # where the tail joins the peduncle
AXIS = (math.cos(math.radians(30)), math.sin(math.radians(30)))
# Pectoral fin poses (x, y, part): spread away from the body and folded against it. Over
# the body the fin is blended in as a translucent overlay.
PECT = {
    "spread": [(7, 15, "p"), (8, 15, "p"), (8, 16, "p"), (9, 16, "p"), (7, 16, "p"), (8, 17, "P"),
               (9, 17, "p"), (10, 17, "P")],
    "folded": [(7, 15, "p"), (8, 15, "p"), (9, 15, "p"), (8, 16, "p"), (9, 16, "p"), (10, 16, "p"),
               (9, 17, "P"), (10, 17, "P")],
}
PELVIC = {"rest": [(11, 18, "p"), (12, 18, "p"), (12, 19, "P"), (13, 19, "P")],
          "swept": [(11, 18, "p"), (12, 18, "p"), (13, 18, "p"), (13, 19, "P"), (14, 19, "P")]}
GILL = {8: 7, 9: 8, 10: 7, 11: 7, 12: 6, 13: 5}      # row -> last head column


def _static() -> dict:
    out = {}
    for y, row in enumerate(ART):
        for x, ch in enumerate(row):
            if ch != ".":
                out[(x, y)] = ch
    return out


STATIC = _static()
BODY = {k for k, ch in STATIC.items() if ch == "#" or ch in DETAIL}


def _bands():
    """Steps in from the back (kb: up / right) and the belly (kv: down / left)."""
    kb, kv = {}, {}
    for (x, y) in BODY:
        up = next(i for i in range(32) if (x, y - i - 1) not in BODY)
        right = next(i for i in range(32) if (x + i + 1, y) not in BODY)
        down = next(i for i in range(32) if (x, y + i + 1) not in BODY)
        left = next(i for i in range(32) if (x - i - 1, y) not in BODY)
        kb[(x, y)] = min(up, right)
        kv[(x, y)] = min(down, left)
    return kb, kv


KB, KV = _bands()

def _is_head(x, y) -> bool:
    return y in GILL and x <= GILL[y]


PAINT_KEY = {}
PAINT_KEY.update(zip("12345", BACK[1:]))
PAINT_KEY.update(zip("abcdefg", FLANK))
PAINT_KEY.update(zip("rstu", ROSE))
PAINT_KEY.update(zip("vwxyz", BELLY))
PAINT_KEY.update(zip("HIJKLM", HEAD))


def _band(x: int, y: int) -> str:
    return PAINT_KEY[PAINT[y - 7][x]]


def _tail(near: int, far: int) -> dict:
    """The tail flexed: pixels 2-4 px out from the root move `near` rows, the lobes further
    out move `far` rows (positive: down), so the tips lead the beat."""
    base = {}
    for j, row in enumerate(TAIL):
        for i, ch in enumerate(row):
            if ch != ".":
                base[(TAIL_AT[0] + i, TAIL_AT[1] + j)] = ch
    if near == 0 and far == 0:
        return base
    out = {}
    for (x, y), ch in base.items():
        dist = math.hypot(x + 0.5 - TAIL_BASE[0], y + 0.5 - TAIL_BASE[1])
        dy = far if dist >= 4.5 else near if dist >= 2.2 else 0
        out.setdefault((x, y + dy), ch)
    step = 1 if far > 0 else -1
    for (x, y), ch in base.items():
        if (x, y) not in out and (x, y - step) in out and (x, y + step) in out:
            out[(x, y)] = "t" if ch in "tu" else "T"
    return out


def _compose(t: float):
    wag = math.sin(2 * math.pi * t)
    near, far = round(0.6 * wag), round(1.3 * wag)
    pect = "folded" if math.sin(2 * math.pi * t + 1.2) > 0.2 else "spread"
    pelvic = "swept" if math.sin(2 * math.pi * t + 2.4) > 0 else "rest"
    layer = dict(STATIC)
    for key, ch in _tail(near, far).items():
        layer.setdefault(key, ch)
    for x, y, part in PELVIC[pelvic]:
        layer.setdefault((x, y), part)
    return layer, {(x, y): part for x, y, part in PECT[pect]}


def _paint(layer: dict, pect: dict):
    img = canvas(SIZE)
    px = img.load()
    sx, sy = SHIFT
    zones = {}
    for (x, y), ch in layer.items():
        X, Y = x + sx, y + sy
        if (x, y) in BODY:
            if ch in DETAIL:
                colour, zone = DETAIL[ch]
            else:
                colour, zone = _band(x, y), ("head" if _is_head(x, y) else "body")
            if (x, y) in pect and ch == "#":
                colour = mix(colour, RED[3], 0.45) if pect[(x, y)] == "p" else mix(colour, RED[1], 0.7)
                zone = "pect"
            px[X, Y] = rgba(colour)
            zones[(X, Y)] = (zone, KB[(x, y)])
            continue
        colour, alpha, zone = FINS[ch]
        if ch in "DTtAp":
            # membrane: the pixels on the open-water side catch the light
            if (x, y - 1) not in layer or (x - 1, y) not in layer:
                colour = shade(colour, 0.28, 0.1)
            elif (x + 1, y) not in layer or (x, y + 1) not in layer:
                colour = shade(colour, -0.12, 0.1)
        c = rgba(colour)
        px[X, Y] = (c[0], c[1], c[2], alpha)
        zones[(X, Y)] = (zone, 9)
    # pectoral pixels off the body are fin
    for (x, y), part in pect.items():
        if (x, y) in BODY or (x, y) in layer:
            continue
        colour, alpha, _ = FINS[part]
        c = rgba(colour)
        px[x + sx, y + sy] = (c[0], c[1], c[2], alpha)
        zones[(x + sx, y + sy)] = ("fin", 9)
    # 1 px outline around the whole silhouette: solid beside the body, a touch translucent
    # where it only borders fins, a touch lighter on the side facing the light
    filled = set(zones)
    solid = {(x + sx, y + sy) for (x, y) in BODY}
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
    # the barbel dangles from the corner of the mouth (a whisker, no outline of its own)
    for bx, by in ((2, 12), (2, 13)):
        c = rgba(BARBEL)
        px[bx + sx, by + sy] = (c[0], c[1], c[2], 235)
    return img, zones


def _glint(img, zones, t: float, strength: float = 0.62, width: float = 3.2, pause: float = 0.3):
    """A soft glint sliding along the back from snout to tail, then resting (a shine()
    band that follows the fish's own axis instead of the frame's diagonal)."""
    run = 1.0 - pause
    if t >= run:
        return
    centre = -2.0 + 28.0 * (t / run)
    px = img.load()
    c = rgba(GLINT)
    for (x, y), (zone, kb) in zones.items():
        if zone not in ("body", "head") or kb > 3:
            continue
        along = (x + 0.5 - 2.0) * AXIS[0] + (y + 0.5 - 10.0) * AXIS[1]
        k = max(0.0, 1.0 - abs(along - centre) / width) * strength * (1.0 - kb / 4.5)
        if k <= 0:
            continue
        r, g, b, a = px[x, y]
        px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _frame(t: float):
    layer, pect = _compose(t)
    img, zones = _paint(layer, pect)
    _glint(img, zones, t)
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
