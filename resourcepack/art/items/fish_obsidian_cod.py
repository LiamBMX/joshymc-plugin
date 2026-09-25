"""Obsidian Cod: a rare fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's volcanic-glass scheme: a near-black violet body with slate-teal
reflections and a pale eye. Real cod anatomy: a stout, pot-bellied body with a big blunt
head, the overhanging upper jaw and a chin barbel, three rounded dorsal fins and two anal
fins, jugular pelvic fins under the throat, a pale lateral line arching over the pectoral
fin and a broad, square-cut tail. The glass reads through a lit violet rim, a bright
specular streak under the back, a hot forehead highlight, a curved conchoidal fracture
ridge on the flank and a slate-teal bounce light on the countershaded belly; the smoky
translucent fins carry painted rays.

Animation (RARE, 12 frames x 3 ticks): the tail wags with its tips leading, the pectoral
and pelvic fins and the barbel sway a pixel, a soft glint slides along the back (frames
0-5), then a blue sheen rolls across the glassy body (frames 6-11), while three pale-blue
sparkles twinkle around the fish on offset phases.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_obsidian_cod"
NAME = "Obsidian Cod"
KIND = "item"
MODEL_KEY = "fish/obsidian_cod"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palettes (darkest -> lightest) --------------------------------------------------------
OUTLINE = "#08040f"                       # blue-black, never pure black
OUTLINE_LIT = "#1b1130"                   # outline on the side facing the top-left light
FIN_OUTLINE = ("#170f2a", 230)            # softer edge where the outline only touches a fin
# volcanic glass: violet-black ramp, shadows toward indigo, lights toward lavender
OBS = ["#110b1f", "#1d1430", "#291c41", "#392a56", "#513f73", "#75619c", "#a797cf", "#e6dffb"]
# slate-teal reflected light on the lower flank and belly (the old sprite's teal greys)
TEAL = ["#1b2033", "#263347", "#33475b", "#476171", "#60808b", "#88a7ac"]
# smoky glass fins
FIN = ["#1d1531", "#2b2046", "#3d2f5f", "#56467a", "#77669f", "#a294c8"]
EYE = ["#05030a", "#fbfaff", "#8a7cc2", "#b9aee6"]  # pupil, catchlight, iris, lit iris
LINE = "#8e81bd"                          # the pale lateral line
BARBEL = "#76689c"
GLINT = "#f1ecff"
SHEEN = "#8db3ff"                         # blue sheen rolling across the glass
SPARK = "#dfeaff"

# ---- silhouette ------------------------------------------------------------------------------
# Body: column x -> (top y, bottom y). A blunt head, the deep pot belly behind the throat
# and a slim tail stalk.
COLS = {2: (9, 10), 3: (8, 11), 4: (7, 12), 5: (7, 13), 6: (6, 14), 7: (6, 15), 8: (6, 15),
        9: (6, 16), 10: (6, 16), 11: (6, 17), 12: (7, 17), 13: (7, 18), 14: (8, 18), 15: (9, 19),
        16: (10, 19), 17: (11, 19), 18: (12, 20), 19: (13, 20), 20: (14, 20), 21: (15, 20),
        22: (16, 20), 23: (17, 20)}

# Fins that do not move (fin letter "f", ray "F").
DORSAL = {  # three soft, rounded dorsal fins, the first the tallest
    (7, 5): "f", (8, 4): "f", (8, 5): "f", (9, 3): "f", (9, 4): "F", (9, 5): "f", (10, 3): "f",
    (10, 4): "f", (10, 5): "f", (11, 4): "f", (11, 5): "F", (12, 5): "f", (12, 6): "f",
    (14, 7): "f", (15, 6): "f", (15, 7): "f", (15, 8): "f", (16, 7): "f", (16, 8): "F",
    (16, 9): "f", (17, 9): "f", (17, 10): "f",
    (19, 12): "f", (20, 11): "f", (20, 12): "f", (20, 13): "f", (21, 12): "f", (21, 13): "F",
    (21, 14): "f", (22, 14): "f", (22, 15): "f",
}
ANAL = {  # two anal fins
    (13, 19): "f", (14, 19): "f", (14, 20): "F", (15, 20): "f", (15, 21): "F", (16, 20): "f",
    (16, 21): "f", (16, 22): "f", (17, 20): "f", (17, 21): "f",
    (19, 21): "f", (20, 21): "f", (20, 22): "F", (21, 21): "f", (21, 22): "f", (21, 23): "f",
    (22, 21): "f", (22, 22): "f",
}
# Tail (neutral pose): the broad square-cut cod tail fanning out from the stalk.
TAIL = {
    15: (27, 28),
    16: (25, 29),
    17: (24, 29),
    18: (24, 28),
    19: (24, 28),
    20: (24, 27),
    21: (24, 27),
    22: (24, 26),
    23: (24, 26),
    24: (25, 25),
}
TAIL_RAYS = {(25, 17), (26, 16), (27, 16), (25, 19), (26, 19), (27, 19), (25, 21), (25, 22), (26, 23)}
# Pectoral fin over the flank, spread and folded ("p" membrane, "P" lit ray).
PECT = {
    "spread": {(10, 13): "P", (11, 13): "p", (11, 14): "P", (12, 14): "p", (12, 15): "P",
               (13, 15): "p", (13, 16): "p"},
    "folded": {(10, 13): "P", (11, 13): "P", (12, 13): "p", (11, 14): "p", (12, 14): "P",
               (13, 14): "p", (14, 15): "p"},
}
# Jugular pelvic fins under the throat: hanging and swept back.
PELVIC = {"rest": {(6, 15): "F", (7, 16): "F", (8, 16): "f", (8, 17): "f"},
          "swept": {(6, 15): "F", (7, 16): "F", (8, 16): "F", (9, 17): "f"}}
# Chin barbel: root under the lower jaw plus a tip that sways.
BARBEL_ROOT = (4, 13)
BARBEL_TIP = {"rest": (4, 14), "swept": (5, 14)}

# ---- body shading ----------------------------------------------------------------------------
# The glass has a clear value plan: a lit rim along the back, one specular streak on the
# shoulder with a hot glint, the darkest glass through the middle, and a slate-teal bounce
# light on the countershaded belly. Letters:
#   R r q  back rim (bright / mid / dim)      H S s  specular streak (hot / bright / soft)
#   n m d  glass (lit / mid / dark)           p g    gill-cover plate edge / gill slit
#   L l    lateral line (pale / dim)          D      mouth
#   1-4    slate-teal belly (dark -> light)   6      slate step between glass and belly
#   W O    eye: catchlight, pupil          i I    pale iris ring (shade / lit)
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    "......RRRRRr....................",  # 6
    "....RRmSSdddrr..................",  # 7
    "...RiWOimsSHddr.................",  # 8
    "..RniOOimpgdSsdr................",  # 9
    "..3DmIInmpgdddndr...............",  # 10
    "...2DmmmnpgLLddddq..............",  # 11
    "....23mmpgdddLLdddq.............",  # 12
    ".....2433g6ddddLLddq............",  # 13
    "......2453366ddddlldq...........",  # 14
    ".......22453366ddddldq..........",  # 15
    ".........224433666ddldq.........",  # 16
    "...........2233666mmdldq........",  # 17
    ".............2233366mmnd........",  # 18
    "...............1112226md........",  # 19
    "..................11111m........",  # 20
]
PAINT = {
    "R": OBS[5], "r": OBS[4], "q": OBS[3], "H": OBS[7], "S": OBS[6], "s": OBS[5],
    "n": OBS[3], "m": OBS[2], "d": OBS[1], "D": OBS[0], "p": OBS[4], "g": OBS[0],
    "L": LINE, "l": OBS[4],
    "1": TEAL[1], "2": TEAL[2], "3": TEAL[3], "4": TEAL[4], "5": TEAL[5], "6": mix(OBS[2], TEAL[2], 0.5),
    "W": EYE[1], "O": EYE[0], "i": EYE[2], "I": EYE[3],
}
ZONE = {"R": "back", "r": "back", "q": "back", "H": "back", "S": "back", "s": "back",
        "W": "eye", "O": "eye", "i": "eye", "I": "eye", "L": "line", "l": "line",
        "1": "belly", "5": "belly", "2": "belly", "3": "belly", "4": "belly", "6": "belly"}
BODY = {(x, y) for y, row in enumerate(ART) for x, ch in enumerate(row) if ch != "."}


def _steps(x: int, y: int):
    """Steps in from the back (kb) and from the belly (kv)."""
    top, bot = COLS[x]
    row = [c for c in COLS if COLS[c][0] <= y <= COLS[c][1]]
    return min(y - top, max(row) - x), min(bot - y, x - min(row))


def _body_colour(x: int, y: int):
    ch = ART[y][x]
    return PAINT[ch], ZONE.get(ch, "flank")


def _tail(wag: float) -> dict:
    """Tail pixels flexed: columns 25-26 move `near` rows and 27+ move `far` rows."""
    near, far = round(0.7 * wag), round(1.3 * wag)
    out = {}
    for y, (a, b) in TAIL.items():
        for x in range(a, b + 1):
            dy = far if x >= 27 else near if x >= 25 else 0
            out[(x, y + dy)] = "F" if (x, y) in TAIL_RAYS else "f"
    # close holes the flex opened inside a column
    for x in range(24, 30):
        ys = sorted(y for (cx, y) in out if cx == x)
        if ys:
            for y in range(ys[0], ys[-1] + 1):
                out.setdefault((x, y), "f")
    return out


def _fin_colour(key, ch, fins: dict):
    """Smoky glass fins, paler than the body: darker rays and root, and thin edges that
    turn translucent and pale (brightest where they face the top-left light)."""
    x, y = key
    near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
    touches_body = any(n in BODY for n in near)
    open_sides = [n for n in near if n not in fins and n not in BODY]
    if ch == "F":
        return FIN[2], 230
    if (x, y - 1) in open_sides or (x - 1, y) in open_sides:
        return FIN[5], 206                              # lit edge facing the light
    if touches_body:
        return FIN[3], 224                              # fin root
    if open_sides:
        return FIN[4], 198                              # thin translucent trailing edge
    return FIN[4], 212


def _frame(t: float):
    wag = math.sin(2 * math.pi * t)
    flap = math.sin(2 * math.pi * (t + 0.3))
    pect = PECT["folded" if math.sin(2 * math.pi * 2 * t + 1.0) > 0.35 else "spread"]
    pelvic = PELVIC["swept" if flap > 0 else "rest"]

    fins = dict(DORSAL)
    fins.update(ANAL)
    fins.update(_tail(wag))
    fins.update(pelvic)
    fins = {k: v for k, v in fins.items() if k not in BODY}

    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for key, ch in fins.items():
        colour, alpha = _fin_colour(key, ch, fins)
        c = rgba(colour)
        px[key] = (c[0], c[1], c[2], alpha)
        zones[key] = "fin"
    for key in BODY:
        colour, zone = _body_colour(*key)
        if zone == "back" and (key[0], key[1] - 1) in fins:
            colour = OBS[3]                             # the rim sits in the shade of a fin
        px[key] = rgba(colour)
        zones[key] = zone
    for key, part in pect.items():
        tint = FIN[5] if part == "P" else FIN[4]
        px[key] = rgba(mix(px[key], tint, 0.62 if part == "P" else 0.5))
        zones[key] = "pect"
    # barbel
    px[BARBEL_ROOT] = rgba(BARBEL)
    tip = BARBEL_TIP["swept" if flap > 0.3 else "rest"]
    px[tip] = rgba(mix(BARBEL, OBS[3], 0.35))
    zones[BARBEL_ROOT] = zones[tip] = "barbel"

    _glint(img, zones, t)
    img = _outline(img, zones)
    _sheen(img, zones, t)
    _sparkles(img, t)
    return img


def _outline(img, zones: dict):
    """1 px outline around the silhouette: blue-black beside the body, a touch lighter on
    the side facing the light, and a softer smoky violet where it only borders fins."""
    out = img.copy()
    dst = out.load()
    solid = {k for k, z in zones.items() if z != "fin"}
    fr, fg, fb, _ = rgba(FIN_OUTLINE[0])
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in zones:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + dx, y + dy) in zones]
            if not near:
                continue
            if any(n in solid for n in near):
                facing = ((x, y + 1) in solid or (x + 1, y) in solid) and (x, y - 1) not in zones \
                    and (x - 1, y) not in zones and x <= 14
                dst[x, y] = rgba(OUTLINE_LIT if facing else OUTLINE)
            else:
                dst[x, y] = (fr, fg, fb, FIN_OUTLINE[1])
    return out


# ---- light effects ---------------------------------------------------------------------------

def _blend(px, key, colour, k):
    r, g, b, a = px[key]
    c = rgba(colour)
    px[key] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _glint(img, zones: dict, t: float, strength: float = 0.85, width: float = 1.7):
    """Frames 0-5: a soft glint slides along the back, snout to tail, riding the dark
    rows just under the rim so it reads against the glass."""
    step = round(t * FRAMES)
    if step > 5:
        return
    centre = 4.0 + 18.0 * step / 5.0
    px = img.load()
    for (x, y), zone in zones.items():
        if zone not in ("back", "flank", "line") or (x, y) not in BODY:
            continue
        kb, _ = _steps(x, y)
        if kb > 2:
            continue
        k = max(0.0, 1.0 - abs(x - centre) / width) * strength * (0.75 if kb == 0 else 1.0)
        if k > 0:
            _blend(px, (x, y), GLINT, k)


def _sheen(img, zones: dict, t: float, strength: float = 0.5, width: float = 2.4):
    """Frames 6-11: a blue sheen band rolls across the glass body, tilted steeply."""
    step = round(t * FRAMES)
    if step < 6:
        return
    centre = 6.0 + 24.0 * (step - 6) / 5.0
    px = img.load()
    for (x, y), zone in zones.items():
        if zone in ("eye", "fin"):
            continue
        d = x + 0.55 * y
        k = max(0.0, 1.0 - abs(d - centre) / width) * strength
        if k > 0:
            _blend(px, (x, y), SHEEN, k)


SPARKLES = [(27, 9, 0.0, 2), (5, 21, 0.36, 2), (20, 5, 0.68, 2)]   # x, y, phase, reach


def _sparkles(img, t: float):
    for x, y, phase, reach in SPARKLES:
        sparkle(img, x, y, wave(t, -phase) ** 3, colour=SPARK, reach=reach)


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
