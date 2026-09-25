"""Lava Serpent: an epic serpent-fish of the JoshyMC fishing collection (every Nether biome).

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's molten orange body, its hot pale-orange lateral line and white
eye. A long serpentine body in a gentle S: a row of cooled obsidian crust plates along the
back split by glowing lava seams, molten orange flanks around a white-hot lateral vein, a
countershaded amber belly, a wedge-shaped serpent head with a crusted brow, a long mouth
line with a fang and a gill slit, a small pectoral fin, a dorsal fin of flame tongues, a
low ventral fin and a paddle tail.

Animation (EPIC, 16 frames x 2 ticks): the serpent swims in place, a travelling wave
rolling down its rear two thirds so the tail sways a pixel, while the dorsal flames
flicker and lick backward; a pulse of heat flows down the lateral vein and the seams, an
iridescent purple-pink-cyan sheen sweeps over the obsidian plates, and three embers
twinkle around the fish on staggered phases.
"""
from __future__ import annotations

import math

from PIL import Image

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_lava_serpent"
NAME = "Lava Serpent"
KIND = "item"
MODEL_KEY = "fish/lava_serpent"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 16
FRAMETIME = 2

# ---- palette (hue-shifted: shadows lean crimson-violet, lights lean yellow) --------------------
OUTLINE = "#3c0812"
OUTLINE_LIT = "#5c1412"
FIN_OUTLINE = ("#70160e", 230)
CRUST = ["#1c0a1a", "#2e0f22", "#44152a", "#5e1e2c", "#7e2c2e", "#a3443a"]   # obsidian crust
LAVA = ["#7c1406", "#a82206", "#cf3606", "#e8500c", "#f86e1a", "#ff8f30"]    # molten flesh
HOT = ["#ffa436", "#ffdc6e", "#fff4c4"]                                       # vein and seams
BELLY = ["#c23c10", "#e45c18", "#fb8434", "#ffb058"]
FIN = ["#a0240a", "#d4400e", "#f26a1c", "#ff9a36", "#ffc85a", "#fff0a0"]      # flame fin
DFIN = ["#6a1826", "#a02a18"]                                                 # dorsal root
EYE = ["#1a0306", "#fff8e4", "#ffce48"]
FANG = "#fff2d0"
IRIS = ["#c070ff", "#ff6ad8", "#60f4ff"]                                      # purple, pink, cyan
EMBER = "#ffd76a"

# ---- the body, column by column: x -> (top y, bottom y) of the static pose ---------------------
# A wedge head (x 1-7), a neck, then a long serpent body in a gentle S tapering to the tail.
COLS = {
    1: (8, 9), 2: (7, 10), 3: (6, 11), 4: (6, 12), 5: (6, 12), 6: (6, 12), 7: (7, 13),
    8: (8, 13), 9: (9, 14), 10: (10, 15), 11: (11, 16), 12: (12, 17), 13: (13, 17), 14: (14, 18),
    15: (14, 18), 16: (15, 19), 17: (15, 19), 18: (15, 19), 19: (16, 20), 20: (16, 20),
    21: (16, 20), 22: (17, 20), 23: (17, 20), 24: (18, 21), 25: (19, 21),
}
SHIFT_X = 1                    # the art is drawn one pixel left of centre
WAVE_FROM = 10                 # columns from here on sway with the travelling wave


def _cross(k: int, n: int) -> str:
    """Letter for row k (0 = top) of an n-row column: rim-lit crust, crust, flank, vein,
    belly, belly edge."""
    if n <= 2:
        return "R" if k == 0 else "o"
    if n == 3:
        return "pve"[k]
    if n == 4:
        return "Rpve"[k]
    if n == 5:
        return "Rpvbe"[k]
    if n == 6:
        return "Rpovbe"[k]
    return ("RPpovbe" + "e" * n)[k] if k < n - 1 else "e"


COLOURS = {
    "R": CRUST[4], "L": CRUST[5], "P": CRUST[3], "p": CRUST[2], "d": CRUST[1],
    "C": LAVA[4], "c": HOT[1],
    "o": LAVA[3], "O": LAVA[4], "q": LAVA[2],
    "v": HOT[1], "V": HOT[2],
    "b": BELLY[2], "B": BELLY[1], "e": BELLY[0], "j": BELLY[3],
    "E": EYE[0], "w": EYE[1], "i": EYE[2], "m": OUTLINE, "g": LAVA[0], "f": FANG,
}
ZONE = {"R": "plate", "L": "plate", "P": "plate", "p": "plate", "d": "plate", "C": "crack", "c": "crack",
        "o": "flank", "O": "flank", "q": "flank", "v": "vein", "V": "vein",
        "b": "belly", "B": "belly", "e": "belly", "j": "head",
        "E": "eye", "w": "eye", "i": "eye", "m": "line", "g": "line", "f": "line"}


def _seam(x: int, k: int) -> bool:
    """Glowing seams between the obsidian plates: every 3 columns, the lower row one
    column behind the rim so each plate reads as an overlapping scale."""
    return 9 <= x <= 23 and k <= 1 and (x - 9 - (1 if k == 1 else 0)) % 3 == 0


# Hand-painted head (the wave does not reach it): crusted skull with a lit brow, eye with
# a golden iris and catchlight, long mouth line with a fang, pale jaw, gill slit.
HEAD = {
    # skull crust plates
    (2, 7): "L", (3, 6): "L", (4, 6): "L", (5, 6): "R", (6, 6): "R", (7, 7): "R", (8, 8): "R",
    (3, 7): "P", (4, 7): "p", (5, 7): "p", (6, 7): "P", (7, 8): "p", (8, 9): "p",
    (1, 8): "O", (2, 8): "O",
    # eye (2x2) under the brow, golden iris ring behind it
    (4, 8): "w", (5, 8): "E", (4, 9): "E", (5, 9): "E", (6, 8): "i", (6, 9): "o", (3, 8): "o",
    (3, 9): "o",
    # snout, mouth line and fang, jaw
    (1, 9): "m", (2, 9): "o", (2, 10): "m", (3, 10): "m", (4, 10): "m", (5, 10): "m", (6, 10): "g",
    (3, 11): "f", (4, 11): "j", (5, 11): "j", (6, 11): "b", (4, 12): "e", (5, 12): "e", (6, 12): "e",
    # cheek and gill slit
    (7, 9): "o", (7, 10): "v", (7, 11): "g", (7, 12): "b", (7, 13): "e", (6, 13): "e",
    (8, 10): "o", (8, 11): "g", (8, 12): "g", (8, 13): "e",
}


def _scale(x: int, y: int) -> bool:
    """A sparse diamond lattice of darker scale marks on the flank and belly."""
    return x >= 9 and (x + 2 * y) % 4 == 0


def _body_static() -> dict:
    out = {}
    for x, (top, bot) in COLS.items():
        n = bot - top + 1
        for k in range(n):
            ch = _cross(k, n)
            if ch in "ob" and _scale(x, top + k):
                ch = "q" if ch == "o" else "B"
            if ch == "p" and n >= 4 and _seam(x, k):
                ch = "c"
            elif ch == "R" and n >= 4 and _seam(x - 1, 1):
                ch = "C"
            elif ch == "R" and x < 16:
                ch = "L"                     # the rim light fades toward the tail
            out[(x, top + k)] = ch
    out.update(HEAD)
    return out


def _shift(x: int, t: float) -> int:
    """Vertical sway of column x at phase t (the travelling wave)."""
    if x < WAVE_FROM:
        return 0
    amp = min(1.0, (x - WAVE_FROM) / 12.0) * 1.15
    return round(amp * math.sin(2 * math.pi * ((x - WAVE_FROM) / 16.0 - t)))


def _dorsal(t: float) -> dict:
    """Flame tongues rising from the back: a dark translucent root membrane with tongues
    up to 3 px tall (orange body, yellow tip) that flicker and lick backward.
    (x, y) -> (colour, alpha, zone)."""
    out = {}
    for x in range(10, 25):
        top = COLS[x][0]
        env = 1.0 if 12 <= x <= 22 else 0.55
        ph = math.sin(2 * math.pi * ((x - 10) / 3.5 - 2 * t))
        h = max(1, min(4, 1 + round(env * (1.3 + 1.6 * ph))))
        for j in range(1, h + 1):
            zone = "fin"
            if j == 1:
                col, a, zone = DFIN[0], 226, "root"
            elif j == h:
                col, a = (FIN[5] if h >= 4 else FIN[4]), 208
            elif j == h - 1:
                col, a = FIN[3], 212
            else:
                col, a = FIN[1], 218
            out[(x, top - j)] = (col, a, zone)
    return out


def _ventral(t: float) -> dict:
    out = {}
    for x in range(14, 25):
        bot = COLS[x][1]
        ph = math.sin(2 * math.pi * ((x - 15) / 8.0 - t))
        h = 2 if (17 <= x <= 23 and ph > -0.55) else 1
        for j in range(1, h + 1):
            col, a = (FIN[3], 206) if j == h else (FIN[1], 220)
            out[(x, bot + j)] = (col, a, "fin")
    return out


# Spade tail past the body end, drawn from (24, 17): t bright rim, r ray, m membrane.
TAIL_AT = (24, 17)
TAIL = [
    "...t.",   # 17
    "...rt",   # 18
    "..mrt",   # 19
    "..rmt",   # 20
    "..mrt",   # 21
    ".rmrt",   # 22
    ".trt.",   # 23
    "..t..",   # 24
]
TAIL_PAINT = {"t": (FIN[4], 206), "r": (FIN[1], 224), "m": (FIN[3], 212)}


def _tail(t: float) -> dict:
    out = {}
    for j, row in enumerate(TAIL):
        for i, ch in enumerate(row):
            if ch != ".":
                col, a = TAIL_PAINT[ch]
                out[(TAIL_AT[0] + i, TAIL_AT[1] + j)] = (col, a, "tail")
    return out


def _pectoral(t: float) -> dict:
    """A crimson flame pectoral fin behind the gill, rooted on the flank and hanging below
    the belly with dark rays and a bright tip; it flaps between spread and folded."""
    spread = math.sin(2 * math.pi * (2 * t + 0.2)) > 0
    if spread:
        pts = {(10, 14): "m", (10, 15): "r", (10, 16): "r", (11, 16): "m", (10, 17): "t", (11, 17): "r",
               (11, 18): "t", (12, 18): "t"}
    else:
        pts = {(10, 14): "m", (10, 15): "r", (11, 16): "r", (10, 16): "m", (11, 17): "m", (12, 17): "r",
               (12, 18): "t", (13, 18): "t"}
    paint = {"m": (DFIN[1], 218), "r": (DFIN[0], 228), "t": (FIN[4], 208)}
    return {p: (*paint[ch], "pect") for p, ch in pts.items()}


def _compose(t: float):
    """Letters / fin pixels of the frame at phase t, after the travelling-wave sway."""
    body = {}
    for (x, y), ch in _body_static().items():
        body[(x + SHIFT_X, y + _shift(x, t))] = ch
    fins = {}
    for part in (_dorsal(t), _ventral(t)):
        for (x, y), v in part.items():
            fins[(x + SHIFT_X, y + _shift(x, t))] = v
    for (x, y), v in _tail(t).items():          # the tail sways as one piece with the body end
        fins.setdefault((x + SHIFT_X, y + _shift(25, t)), v)
    pect = {(x + SHIFT_X, y + _shift(x, t)): v for (x, y), v in _pectoral(t).items()}
    return body, fins, pect


def _paint(t: float):
    body, fins, pect = _compose(t)
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for (x, y), (col, a, zone) in fins.items():
        if (x, y) in body:
            continue
        r, g, b, _ = rgba(col)
        px[x, y] = (r, g, b, a)
        zones[(x, y)] = zone
    for (x, y), ch in body.items():
        col = COLOURS[ch]
        zone = ZONE[ch]
        if (x, y) in pect:
            pc, _, _ = pect[(x, y)]
            col = mix(col, pc, 0.8)
            zone = "pect"
        px[x, y] = rgba(col)
        zones[(x, y)] = zone
    for (x, y), (col, a, zone) in pect.items():
        if (x, y) not in body:
            r, g, b, _ = rgba(col)
            px[x, y] = (r, g, b, a)
            zones[(x, y)] = zone
    return img, zones, set(body)


def _outline(img: Image.Image, body: set) -> Image.Image:
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
            facing_light = ((x, y + 1) in near or (x + 1, y) in near) and \
                (x, y - 1) not in near and (x - 1, y) not in near
            if any(p in body for p in near):
                dst[x, y] = rgba(OUTLINE_LIT if facing_light else OUTLINE)
            else:
                dst[x, y] = (fr, fg, fb, FIN_OUTLINE[1])
    return out


AXIS = (math.cos(math.radians(30.0)), math.sin(math.radians(30.0)))
EMBERS = [  # (x, y, reach, phase offset)
    (16, 6, 2, 0.0), (28, 13, 1, 0.36), (7, 17, 1, 0.68),
]


def _along(x: int, y: int) -> float:
    """Distance along the body axis from the snout, in pixels."""
    return (x - 2.0) * AXIS[0] + (y - 8.0) * AXIS[1]


def _iris(p: float) -> str:
    """Iridescent colour at cycle position p (0..1): purple -> pink -> cyan -> purple."""
    p = (p % 1.0) * 3
    i = int(p)
    return mix(IRIS[i % 3], IRIS[(i + 1) % 3], p - i)


def _effects(img: Image.Image, zones: dict, t: float) -> None:
    """EPIC layer: a heat pulse flowing down the vein and seams, an iridescent sheen
    sweeping over the obsidian plates half a loop later."""
    px = img.load()
    heat = -5.0 + 38.0 * t                       # pulse centre along the axis
    sheen = -5.0 + 38.0 * ((t + 0.5) % 1.0)      # sheen centre along the axis
    for (x, y), zone in zones.items():
        u = _along(x, y)
        kh = max(0.0, 1.0 - abs(u - heat) / 3.2)
        ks = max(0.0, 1.0 - abs(u - sheen) / 4.2)
        c = px[x, y]
        if kh > 0:
            if zone == "vein":
                c = rgba(mix(c, HOT[2], 0.85 * kh))
            elif zone == "crack":
                c = rgba(mix(c, HOT[2], 0.9 * kh))
            elif zone == "flank":
                c = rgba(mix(c, LAVA[5], 0.55 * kh))
            elif zone == "belly":
                c = rgba(mix(c, BELLY[3], 0.45 * kh))
            elif zone in ("fin", "tail"):
                c = (*rgba(mix(c, HOT[1], 0.5 * kh))[:3], c[3])
        if ks > 0 and zone in ("plate", "root"):
            col = _iris(0.5 + (u - sheen) / 9.0 + t)
            k = (0.72 if zone == "plate" else 0.45) * ks ** 0.6
            c = (*rgba(mix(c, col, k))[:3], c[3])
        px[x, y] = c


def _frame(t: float) -> Image.Image:
    img, zones, body = _paint(t)
    _effects(img, zones, t)
    img = _outline(img, body)
    for x, y, reach, off in EMBERS:
        amount = wave(t, 0.5 - off) ** 3
        sparkle(img, x, y, amount, EMBER, reach=reach)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
