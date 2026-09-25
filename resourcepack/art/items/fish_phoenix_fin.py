"""Phoenix Fin: a legendary fire fish of the JoshyMC fishing collection (nether biomes).

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's orange rim / molten-gold core scheme. A deep ember-red back
under a lit rim, orange flanks with staggered scale marks, a countershaded golden belly,
a blunt head with a gill-cover arc and a gold-ringed eye, and fins that are phoenix
plumage: a crest of three flame feathers for the dorsal fin, a small wing for the
pectoral, flame-lick pelvic and anal fins and a three-plumed tail whose outer plumes curl
at the tips. Every feather is a translucent flame gradient (crimson at the root, orange,
then gold at the tip) with a brighter painted shaft.

Animation (LEGENDARY, 20 frames x 2 ticks): the tail plumes sway with their tips
leading, the crest feathers and small fins lick back and forth and their flame tips
flicker, a glint slides along the back, an iridescent rose/gold/cyan shimmer band rolls
across the scales, a golden rim glow breathes just outside the outline, three embers
orbit the fish (passing behind it on the far side) and the eye glows.
"""
from __future__ import annotations

import math
from collections import deque

from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, shade, sparkle, sprite, wave

ID = "fish_phoenix_fin"
NAME = "Phoenix Fin"
KIND = "item"
MODEL_KEY = "fish/phoenix_fin"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 20
FRAMETIME = 2

# ---- palettes (darkest -> lightest), from the old sprite's orange / gold ------------------
OUTLINE = "#4a0d16"                 # deep maroon, hue-shifted toward violet
OUTLINE_LIT = "#5e1418"             # a touch warmer on the edges facing the light
FIN_OUTLINE = ("#78161a", 228)      # softer edge where the outline only touches a fin
EMBER = ["#5e0f1c", "#861a1a", "#ad2a18", "#cf4318"]           # back
ORANGE = ["#e2600f", "#f07805", "#f78f1c", "#fca52e"]          # flanks
GOLD = ["#f3a93a", "#fdc84a", "#ffdc6a", "#ffeb98", "#fff6cc"]  # belly
RIM = ["#ffc052", "#ffa23c"]        # lit back edge (head, then toward the tail)
FLAME = ["#8e1420", "#b01d1d", "#d63818", "#f0601a", "#f99526", "#ffb62e", "#ffd443", "#ffea78"]
EYE = ["#1a0608", "#fffbe8"]
IRIS = ["#e8901c", "#ffe066"]
GLINT = "#fff7d6"
HALO = "#ffe070"
SPARK = "#fff4c0"
IRIDESCENT = ["#ff8fc4", "#fff1a8", "#9ff4ff"]   # rose, gold-white, cyan

# ---- the art (hand placed, x 0..31) -------------------------------------------------------
# '#' body, lower case fin membrane, upper case the feather shafts:
# d/D dorsal crest, t/T tail plumes, v pelvic, a anal. The pectoral wing is an overlay.
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    ".............d..................",  # 3
    "............dDd...d.............",  # 4
    "...........dDd..dDd.............",  # 5
    "..........ddDd.ddDd.............",  # 6
    "..........ddD.ddDdd.............",  # 7
    ".....#####ddd.ddDd....d.........",  # 8
    "....########.ddDdd..dDd.........",  # 9
    "...##########dDdd.ddDd..........",  # 10
    "...############d.ddDdd..........",  # 11
    "...#############.dDdd...........",  # 12
    "...##############ddd............",  # 13
    "....##############d.............",  # 14
    ".....##############........Tt...",  # 15
    "......##############...ttTTt....",  # 16
    "........#############.tTTttt....",  # 17
    "........vv############tttt......",  # 18
    ".........vvvv#########tTttt.....",  # 19
    "...........vvvaaa#####ttTTTt....",  # 20
    ".............v.aaaa..tTt.tt.....",  # 21
    ".................aaa.tTtt.......",  # 22
    "......................tTt.......",  # 23
    "......................tTtt......",  # 24
    ".......................tTt......",  # 25
    "........................Tt......",  # 26
    ".........................tt.....",  # 27
    "................................",  # 28
    "................................",  # 29
    "................................",  # 30
    "................................",  # 31
]
REACH = {"d": 6.0, "t": 7.5, "v": 3.0, "a": 3.2}       # distance from the body to the tip
FIN_ALPHA = {"d": 210, "t": 214, "v": 204, "a": 204}
SHAFT_ALPHA = 230

# Pectoral wing poses over the flank: p membrane, S shaft, P the dark trailing edge.
PECT = {
    "spread": ["..........pp.....", "..........pSpp...", "...........PpSpp.", "............PPPpp",
               ".............PPP."],
    "folded": ["..........ppp....", "..........PpSSp..", "...........PPpSpp", "............PPPPp",
               "................."],
}
PECT_Y = 13

EYE_AT = (5, 10)                  # top-left pixel of the 2x2 eye (catchlight there)
IRIS_PX = [(7, 10), (7, 11), (5, 12), (6, 12), (4, 11)]
MOUTH = [(3, 12), (4, 12)]
GILL = [(8, 9), (9, 10), (9, 11), (9, 12), (9, 13), (8, 14)]

TAIL_BASE = (21.5, 19.0)          # where the plumes leave the peduncle
TAIL_AXIS = (math.cos(math.radians(40)), math.sin(math.radians(40)))
AXIS = (math.cos(math.radians(32)), math.sin(math.radians(32)))
SNOUT = (3.0, 11.0)


def _layer() -> dict:
    out = {}
    for y, row in enumerate(ART):
        for x, ch in enumerate(row):
            if ch != ".":
                out[(x, y)] = ch
    return out


BASE = _layer()
BODY = {k for k, ch in BASE.items() if ch == "#"}


def _bands(mask: set):
    """Steps in from the back (kb) and from the belly (kv) for every body pixel: the
    back is the top/right contour, the belly the bottom/left one."""
    kb, kv = {}, {}
    for (x, y) in mask:
        col = [yy for (xx, yy) in mask if xx == x]
        row = [xx for (xx, yy) in mask if yy == y]
        kb[(x, y)] = min(y - min(col), max(row) - x)
        kv[(x, y)] = min(max(col) - y, x - min(row))
    return kb, kv


KB, KV = _bands(BODY)


def _dist_from_body(pixels: set) -> dict:
    """4-connected steps from the body for every fin pixel (BFS)."""
    dist = {}
    q = deque()
    for (x, y) in pixels:
        if any((x + dx, y + dy) in BODY for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            dist[(x, y)] = 1
            q.append((x, y))
    while q:
        x, y = q.popleft()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, y + dy)
            if n in pixels and n not in dist:
                dist[n] = dist[(x, y)] + 1
                q.append(n)
    for p in pixels:
        dist.setdefault(p, 2)
    return dist


FIN_DIST = _dist_from_body({k for k, ch in BASE.items() if ch != "#"})


def _along(x: float, y: float) -> float:
    """Distance along the body axis from the snout."""
    return (x + 0.5 - SNOUT[0]) * AXIS[0] + (y + 0.5 - SNOUT[1]) * AXIS[1]


# ---- fin motion ---------------------------------------------------------------------------

def _flex(t: float) -> dict:
    """Every fin pixel of the frame -> (letter, distance from the body at rest).
    The tail plumes bend (tips leading), the crest and small fins lick back and forth."""
    wag = math.sin(2 * math.pi * t)
    t_near, t_far = round(0.7 * wag), round(1.4 * wag)
    lick = wave(t, 0.1)                                     # 0..1, crest tips sweep back
    d_near, d_far = (1 if lick > 0.72 else 0), (1 if lick > 0.36 else 0)
    low = wave(t, 0.55)
    s_far = 1 if low > 0.5 else 0
    out = {}
    for (x, y), ch in BASE.items():
        if ch == "#":
            continue
        dist = FIN_DIST[(x, y)]
        f = ch.lower()
        nx, ny = x, y
        if f == "t":
            along = (x + 0.5 - TAIL_BASE[0]) * TAIL_AXIS[0] + (y + 0.5 - TAIL_BASE[1]) * TAIL_AXIS[1]
            # sway across the tail axis: the upper plume moves along y, the lower along x
            shift = t_far if along >= 4.0 else t_near if along >= 2.0 else 0
            if y <= 19:
                ny = y + shift
            else:
                nx = x - shift
        elif f == "d":
            nx = x + (d_far if dist >= 4 else d_near if dist >= 2 else 0)
        elif f in "va":
            nx = x + (s_far if dist >= 2 else 0)
        if (nx, ny) in BODY:
            continue
        out.setdefault((nx, ny), (ch, dist))
    # close single-pixel holes the flex opened inside a fin
    for (x, y), (ch, dist) in list(out.items()):
        for dx, dy in ((1, 0), (0, 1)):
            gap, far = (x + dx, y + dy), (x + 2 * dx, y + 2 * dy)
            if gap not in out and gap not in BODY and far in out and out[far][0].lower() == ch.lower() \
                    and BASE.get(gap, ".").lower() == ch.lower():
                out[gap] = (ch.lower(), dist)
    return out


def _flame(k: float) -> str:
    k = max(0.0, min(0.999, k))
    return FLAME[int(k * len(FLAME))]


# ---- body colour ------------------------------------------------------------------------

def _scale_mark(x: int, y: int) -> bool:
    return y % 2 == 0 and (x + (y // 2 % 2) * 2) % 4 == 1


def _band_colour(x: int, y: int) -> str:
    """Bands that follow the contours: a lit rim, the ember-red back fading through orange
    into a molten-gold core (the old sprite's glowing heart, set low on the flank so the
    belly stays countershaded), a pale belly and a warm belly edge."""
    kb, kv = KB[(x, y)], KV[(x, y)]
    along = _along(x, y)
    if kb == 0:
        return RIM[0] if along < 12 else RIM[1]
    if kv == 0:
        return GOLD[0]
    f = (kb - 0.5) / (kb + kv - 1.0)                 # 0 at the back .. 1 at the belly
    scaled = _scale_mark(x, y) and 7.0 < along < 19.0 and 0.2 < f < 0.8
    if f < 0.16:
        c = EMBER[1] if along > 14 else EMBER[2]
    elif f < 0.34:
        c = EMBER[3]
    elif f < 0.5:
        c = ORANGE[1]
    elif f < 0.66:
        c = ORANGE[3]
    elif kv >= 2:
        c = GOLD[2]
    else:
        c = GOLD[3]
    if min(kb, kv) >= 3 and 0.42 < f < 0.8:
        c = GOLD[1] if f < 0.6 else GOLD[2]          # molten core
    if scaled:
        c = shade(c, -0.2, 0.1)
    return c


def _zone(x: int, y: int) -> str:
    kb, kv = KB[(x, y)], KV[(x, y)]
    if kb <= 2:
        return "back"
    if kv <= 2:
        return "belly"
    return "flank"


# ---- painting ---------------------------------------------------------------------------

def _paint(t: float, eye_glow: float, flicker: float):
    """The fish at phase t without the glow effects. Returns the image and a zone map."""
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for (x, y), (ch, dist) in _flex(t).items():
        f = ch.lower()
        k = (dist - 0.5) / REACH[f]
        k += 0.07 * flicker * k * k * 2                    # the flame tips flicker
        if ch.isupper():
            colour, a = _flame(k + 0.28), SHAFT_ALPHA
        else:
            colour, a = _flame(k), FIN_ALPHA[f] - (8 if k > 0.85 else 0)
        c = rgba(colour)
        px[x, y] = (c[0], c[1], c[2], a)
        zones[(x, y)] = "fin"
    for (x, y) in BODY:
        px[x, y] = rgba(_band_colour(x, y))
        zones[(x, y)] = _zone(x, y)
    for (x, y) in GILL:
        px[x, y] = rgba(mix(px[x, y], EMBER[1], 0.6))
        zones[(x, y)] = "head"
    # pectoral wing, blended over the flank
    pose = "folded" if math.sin(2 * math.pi * 2 * t + 1.0) > 0.35 else "spread"
    for j, row in enumerate(PECT[pose]):
        for x, ch in enumerate(row):
            if ch == "." or (x, PECT_Y + j) not in BODY:
                continue
            y = PECT_Y + j
            if ch == "p":
                colour = mix(px[x, y], FLAME[6], 0.62)
            elif ch == "S":
                colour = FLAME[7]
            else:
                colour = mix(px[x, y], FLAME[2], 0.75)
            px[x, y] = rgba(colour)
            zones[(x, y)] = "pect"
    # head: eye, iris, mouth
    ex, ey = EYE_AT
    iris = mix(IRIS[0], IRIS[1], eye_glow)
    for (x, y) in IRIS_PX:
        px[x, y] = rgba(iris)
        zones[(x, y)] = "eye"
    px[ex, ey] = rgba(EYE[1])
    px[ex + 1, ey] = rgba(EYE[0])
    px[ex, ey + 1] = rgba(EYE[0])
    px[ex + 1, ey + 1] = rgba(mix(EYE[0], "#8a2410", 0.6 * eye_glow))
    for p in ((ex, ey), (ex + 1, ey), (ex, ey + 1), (ex + 1, ey + 1)):
        zones[p] = "eye"
    px[MOUTH[0]] = rgba(EMBER[0])
    px[MOUTH[1]] = rgba(mix(px[MOUTH[1]], EMBER[1], 0.6))
    zones[MOUTH[0]] = zones[MOUTH[1]] = "head"
    return img, zones


def _outline(img: Image.Image) -> Image.Image:
    """1 px outline: maroon beside the body (a touch warmer on the lit edges), a softer
    translucent red where it only borders fins."""
    src = img.load()
    out = img.copy()
    dst = out.load()
    for y in range(SIZE):
        for x in range(SIZE):
            if src[x, y][3]:
                continue
            near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                    if 0 <= x + dx < SIZE and 0 <= y + dy < SIZE and src[x + dx, y + dy][3]]
            if not near:
                continue
            if any(p in BODY for p in near):
                lit = (x, y + 1) in BODY or (x + 1, y) in BODY
                dst[x, y] = rgba(OUTLINE_LIT if lit and y < 12 else OUTLINE)
            else:
                c = rgba(FIN_OUTLINE[0])
                dst[x, y] = (c[0], c[1], c[2], FIN_OUTLINE[1])
    return out


# ---- effects ----------------------------------------------------------------------------

def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _glint(img, zones, t: float, start: float = 0.0, end: float = 0.35, width: float = 2.6):
    """A soft glint sliding along the back from head to tail during [start, end)."""
    if not start <= t < end:
        return
    centre = 1.0 + 21.0 * ((t - start) / (end - start))
    px = img.load()
    for (x, y), zone in zones.items():
        if zone not in ("back", "head") or (x, y) not in BODY:
            continue
        kb = KB[(x, y)]
        k = max(0.0, 1.0 - abs(_along(x, y) - centre) / width) * 0.62 * (1.0 - kb / 4.0)
        if k > 0:
            _blend(px, x, y, GLINT, k)


def _shimmer(img, zones, t: float, start: float = 0.42, end: float = 0.84):
    """An iridescent band rolling across the scales, rose at its front, gold-white in the
    middle and cyan behind, with the scale marks flashing as it passes."""
    if not start <= t < end:
        return
    centre = 3.0 + 20.0 * ((t - start) / (end - start))
    px = img.load()
    for (x, y), zone in zones.items():
        if zone not in ("back", "flank", "belly", "pect"):
            continue
        d = _along(x, y) - centre
        if abs(d) > 3.0:
            continue
        colour = IRIDESCENT[0] if d > 1.0 else IRIDESCENT[1] if d > -1.0 else IRIDESCENT[2]
        k = (1.0 - abs(d) / 3.3) * 0.4
        if _scale_mark(x, y) and zone != "pect":
            k, colour = min(0.9, k * 2.3), SPARK
        _blend(px, x, y, colour, k)


def _halo(img: Image.Image, breath: float, t: float) -> None:
    """A golden rim glow on the empty pixels just outside the outline, breathing once per
    loop with a slow brighter swell travelling around the silhouette."""
    src = img.load()
    filled = {(x, y) for y in range(SIZE) for x in range(SIZE) if src[x, y][3]}
    c = rgba(HALO)
    cx, cy = 15.5, 15.5
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in filled:
                continue
            if not any((x + dx, y + dy) in filled for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                continue
            ang = math.atan2(y + 0.5 - cy, x + 0.5 - cx)
            swell = 0.5 + 0.5 * math.cos(ang - 2 * math.pi * t)
            a = 8 + 128 * breath ** 1.5 + 34 * swell * breath
            src[x, y] = (c[0], c[1], c[2], int(a))


# Three embers on one tilted orbit (across the body, so they sweep through the empty
# corners), a third of a loop apart; on the far half they pass behind the fish.
ORBIT_C, ORBIT_R = (15.2, 15.2), (14.0, 3.6)
ORBIT_TILT = math.radians(-44.0)


def _embers(img: Image.Image, t: float, solid: set) -> None:
    for i in range(3):
        ph = 2 * math.pi * (t + i / 3.0)
        ox, oy = ORBIT_R[0] * math.cos(ph), ORBIT_R[1] * math.sin(ph)
        x = ORBIT_C[0] + ox * math.cos(ORBIT_TILT) - oy * math.sin(ORBIT_TILT)
        y = ORBIT_C[1] + ox * math.sin(ORBIT_TILT) + oy * math.cos(ORBIT_TILT)
        front = math.sin(ph) > 0
        xi, yi = int(round(x)), int(round(y))
        if not (1 <= xi <= 30 and 1 <= yi <= 30):
            continue
        if not front and (xi, yi) in solid:
            continue                                   # behind the fish
        amount = 0.4 + 0.6 * wave(2 * t, i / 3.0)
        sparkle(img, xi, yi, amount, SPARK if front else HALO, reach=2 if front and amount > 0.6 else 1)


# ---- frame ------------------------------------------------------------------------------

def _frame(t: float) -> Image.Image:
    breath = wave(t)
    flicker = math.sin(2 * math.pi * 2 * t)
    img, zones = _paint(t, breath, flicker)
    _glint(img, zones, t)
    _shimmer(img, zones, t)
    img = _outline(img)
    solid = {(x, y) for y in range(SIZE) for x in range(SIZE) if img.getpixel((x, y))[3]}
    _halo(img, breath, t)
    _embers(img, t, solid)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
