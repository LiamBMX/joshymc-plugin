"""Abyssal Cod: an epic deep-ocean cod of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's near-black indigo / violet / dark-teal scheme. Real cod anatomy:
a heavy head with an overhanging upper jaw and a chin barbel, a deep front body tapering
to a slim wrist, three rounded dorsal fins, two anal fins, forward-set throat (pelvic)
fins, a pectoral behind the gill cover and a broad, square-cut tail. Abyssal touches: an
indigo back flecked with violet, a pale glowing lateral line arching over the pectoral, a
big deep-sea eye ringed with a teal tapetum, a row of cyan photophores along the belly and
a glowing lure bead on the barbel.

Animation (EPIC, 16 frames x 3 ticks): the tail wags with its tips leading and the
pectoral and pelvic fins sway a pixel; the whole body slowly cycles through an iridescent
violet -> pink -> cyan sheen while, once a loop, a band of light sweeps snout to tail,
flooding the scales with stronger colour, glinting along the back and flashing the scale
lattice; the photophores pulse in a travelling wave, the barbel's lure bead breathes and
three blue-white sparkles twinkle around the fish on offset phases.
"""
from __future__ import annotations

import colorsys
import math

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite

ID = "fish_abyssal_cod"
NAME = "Abyssal Cod"
KIND = "item"
MODEL_KEY = "fish/abyssal_cod"
COUNTERPART = "item/cod"

SIZE = 32
SHIFT_Y = 1              # the art below sits one pixel high of centre
FRAMES = 16
FRAMETIME = 3

# ---- palettes (darkest -> lightest), hue-shifted: shadows lean blue, lights lean lilac ----
OUTLINE = "#0a061c"
OUTLINE_LIT = "#171036"
BACK = ["#0d0a24", "#151136", "#1e1a4a", "#28225f", "#393378", "#524b9a", "#7a74c2"]
FLANK = ["#1c1236", "#2a184e", "#3a2166", "#4f2e86", "#66409e", "#8058b4"]
BELLY = ["#2e3058", "#40456f", "#545b88", "#6b73a2", "#8790bf", "#a7b0d8"]
FLECK = "#5b45a4"
LINE = ["#2c6c82", "#3fa3b0", "#72e2dc", "#c8fff4"]           # photophore glow
LATERAL = "#a6c4f2"                                            # the pale lateral line
FIN = ["#231d58", "#2e2870", "#4a449a", "#5f5ab4", "#7e7ad0", "#a9a6ec"]
EYE = ["#04020f", "#ffffff"]
IRIS = ["#1d5d74", "#3fc4c4"]
GLINT = "#e6eaff"
SPARK = "#e2f6ff"
IRIDESCENT = ["#9a5cff", "#ff70cf", "#5ff0ff"]                 # violet, pink, cyan

BODY = {  # letter: (colour, zone)
    "A": (BACK[6], "rim"), "B": (BACK[5], "rim"), "C": (BACK[4], "rim"),
    "D": (BACK[3], "back"), "E": (BACK[2], "back"), "M": (FLECK, "back"),
    "L": (LATERAL, "line"),
    "p": (FLANK[2], "flank"), "q": (FLANK[3], "flank"), "r": (FLANK[4], "flank"), "s": (FLANK[5], "flank"),
    "5": (BELLY[5], "belly"), "4": (BELLY[4], "belly"), "3": (BELLY[3], "edge"), "P": (LINE[1], "photo"),
    # head: cheek, gill cover edge, mouth, pale jaw, lit upper lip
    "h": (FLANK[3], "head"), "k": (FLANK[4], "head"), "g": (FLANK[0], "gill"), "m": (OUTLINE, "mouth"),
    "j": (BELLY[4], "head"), "u": (BACK[5], "head"),
    # eye: 2x2 pupil with a catchlight, ringed by a teal tapetum
    "K": (EYE[0], "eye"), "W": (EYE[1], "eye"), "t": (IRIS[0], "eye"), "T": (IRIS[1], "eye"),
}
FINS = {"f": FIN[2], "F": FIN[1], "a": FIN[3]}           # membrane, ray, lower-fin membrane
FIN_ALPHA, EDGE_ALPHA, RAY_ALPHA = 198, 192, 222
FIN_OUTLINE_ALPHA = 232
SCALE_MARK = {"flank": FLANK[1], "belly": BELLY[2]}   # zone -> darker scale-edge tone

# ---- the art ------------------------------------------------------------------------------
# Static layer: body, head, dorsal and anal fins. The tail, pectoral and pelvic fins are
# separate layers so they can sway.
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    ".........fFf....................",  # 4
    "........fFfff...................",  # 5
    ".......AAAABf..fF...............",  # 6
    ".....AADDDDDBBfFff..............",  # 7
    "....AttEEgEEDDBBff..............",  # 8
    "...AtWKtEMgEMMDDBf..............",  # 9
    "..uDtKKTqqgLLEEEDC..f...........",  # 10
    "..mmqTTqqrgqqLLLMDCfFf..........",  # 11
    "...jmqqrrggqqqqqLMDCff..........",  # 12
    "....j4555grrrqqqqLEDCFf.........",  # 13
    ".....334P5555rrrqqLMDff.........",  # 14
    ".......3333P4555rqqLMCf.........",  # 15
    "...........334P45rrqLDC.........",  # 16
    ".............333455rqLDC........",  # 17
    ".............fff3P45rqLD........",  # 18
    "............ffFff334P5qL........",  # 19
    "............fF....f3335q........",  # 20
    "..................ffFf33........",  # 21
    "...................Ffff.........",  # 22
    "...................ff...........",  # 23
    "................................",  # 24
    "................................",  # 25
]

# Tail (neutral pose) as rows from TAIL_AT: broad, square-cut and faintly concave.
TAIL_AT = (24, 16)
TAIL = [
    "....ff",  # 16
    "ffffFf",  # 17
    "fFFff.",  # 18
    "fffff.",  # 19
    "fFFf..",  # 20
    "fFf...",  # 21
    "fFf...",  # 22
    "ffF...",  # 23
    ".ff...",  # 24
    ".f....",  # 25
]
TAIL_BASE = (24.0, 19.5)       # where the tail joins the wrist
AXIS = (math.cos(math.radians(30)), math.sin(math.radians(30)))
# Pectoral fin poses (x, y, part), lying over the flank just behind the gill cover:
# "p" membrane (blended over the body), "P" its dark trailing edge.
PECT = {
    "spread": [(11, 12), (12, 12), (11, 13), (12, 13), (13, 13), (12, 14), (13, 14), (14, 14), (14, 15)],
    "folded": [(11, 12), (12, 12), (13, 12), (11, 13), (12, 13), (13, 13), (14, 13), (13, 14), (14, 14)],
}
PECT_EDGE = {"spread": {(12, 14), (13, 14), (14, 14), (14, 15)}, "folded": {(13, 14), (14, 14), (14, 13)}}
# Slender throat (pelvic) fins, set forward of the pectoral as on a real cod.
PELVIC = {"rest": [(6, 15), (6, 16), (7, 16), (7, 17)],
          "swept": [(6, 15), (7, 16), (7, 17), (8, 17)]}
BARBEL = [(3, 13), (3, 14)]    # chin whisker and its glowing lure bead (drawn after outline)
SPARKLES = [((26, 10), 0.05, 2), ((7, 22), 0.4, 1), ((4, 4), 0.72, 1)]


def _static() -> dict:
    out = {}
    for y, row in enumerate(ART):
        for x, ch in enumerate(row):
            if ch != ".":
                out[(x, y)] = ch
    return out


STATIC = _static()


def _tail(near: int, far: int) -> dict:
    """The tail flexed: pixels 2-4 px out from the root move `near` rows, the lobes further
    out move `far` rows (positive: down), so the tips lead the sway."""
    base = {}
    for j, row in enumerate(TAIL):
        for i, ch in enumerate(row):
            if ch != ".":
                base[(TAIL_AT[0] + i, TAIL_AT[1] + j)] = ch
    if near == 0 and far == 0:
        return base
    out = {}
    for (x, y), ch in base.items():
        along = (x + 0.5 - TAIL_BASE[0]) * AXIS[0] + (y + 0.5 - TAIL_BASE[1]) * AXIS[1]
        dy = far if along >= 3.4 else near if along >= 1.6 else 0
        out.setdefault((x, y + dy), ch)
    step = 1 if far > 0 else -1
    for (x, y) in list(base):
        if (x, y) not in out and (x, y - step) in out and (x, y + step) in out:
            out[(x, y)] = "f"
    return out


def _compose(t: float):
    """Letters of every pixel of the frame at phase t, plus the pectoral overlay."""
    wag = math.sin(2 * math.pi * t)
    near, far = round(0.7 * wag), round(1.4 * wag)
    pect = "folded" if math.sin(2 * math.pi * t + 1.2) > 0.2 else "spread"
    pelvic = "swept" if math.sin(2 * math.pi * t + 2.2) > 0 else "rest"
    layer = dict(STATIC)
    for key, ch in _tail(near, far).items():
        layer.setdefault(key, ch)
    for key in PELVIC[pelvic]:
        layer.setdefault(key, "a")
    return layer, pect


def _paint(layer: dict, pect: str):
    """Paint letters to an image. Returns the image and a zone map."""
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    body = {k for k, ch in layer.items() if ch in BODY}
    overlay = set(PECT[pect])
    for (x, y), ch in layer.items():
        if ch in BODY:
            colour, zone = BODY[ch]
            if _scale(x, y) and zone in SCALE_MARK:          # painted scale rows
                colour = mix(colour, SCALE_MARK[zone], 0.45)
            if (x, y) in overlay and zone not in ("eye", "gill"):
                tint = FIN[1] if (x, y) in PECT_EDGE[pect] else FIN[4]
                colour = mix(colour, tint, 0.62)
                zone = "pect"
            px[x, y] = rgba(colour)
            zones[(x, y)] = zone
            continue
        near = [layer.get((x + dx, y + dy)) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        if ch == "F":
            colour, alpha = FINS["F"], RAY_ALPHA
        else:
            base = FINS[ch if ch in FINS else "f"]
            if any(n is None for n in near[:1] + near[3:]) and not any(n in BODY for n in near):
                colour, alpha = mix(base, FIN[5], 0.55), EDGE_ALPHA      # lit upper/right rim
            elif any(n is None for n in near):
                colour, alpha = mix(base, FIN[4], 0.3), EDGE_ALPHA
            elif any(n in BODY for n in near):
                colour, alpha = FIN[1], FIN_ALPHA
            else:
                colour, alpha = base, FIN_ALPHA
        c = rgba(colour)
        px[x, y] = (c[0], c[1], c[2], alpha)
        zones[(x, y)] = "fin"
    # 1 px outline around the whole silhouette: solid beside the body, a touch translucent
    # beside fins, and a hair lighter on the side facing the top-left light.
    filled = set(zones)
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
            px[x, y] = o if any(p in body for p in near) else (o[0], o[1], o[2], FIN_OUTLINE_ALPHA)
    return img, zones


# ---- animation --------------------------------------------------------------------------

def _along(x: int, y: int) -> float:
    """Distance along the body axis from the snout, in frame pixels."""
    return (x + 0.5 - 2.0) * AXIS[0] + (y + 0.5 - 10.5) * AXIS[1]


def _across(x: int, y: int) -> float:
    """Distance toward the back, perpendicular to the axis."""
    return (x + 0.5 - 2.0) * AXIS[1] - (y + 0.5 - 10.5) * AXIS[0]


def _iridescent(h: float) -> str:
    """Cyclic violet -> pink -> cyan -> violet colour at phase h."""
    h = h % 1.0
    n = len(IRIDESCENT)
    k = h * n
    i = int(k) % n
    return mix(IRIDESCENT[i], IRIDESCENT[(i + 1) % n], k - int(k))


def _scale(x: int, y: int) -> bool:
    """Staggered scale lattice: the spots that catch the sheen."""
    return y % 2 == 1 and (x + y // 2) % 2 == 0


def _blend(px, x, y, colour, k):
    r, g, b, a = px[x, y]
    c = rgba(colour)
    px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), a)


def _tint(px, x, y, colour, k, lift=1.0):
    """Shift a pixel's hue toward colour while keeping its brightness (times lift), so the
    sheen recolours the scales instead of washing them out."""
    r, g, b, a = px[x, y]
    h0, s0, v0 = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    c = rgba(colour)
    h1, s1, _ = colorsys.rgb_to_hsv(c[0] / 255, c[1] / 255, c[2] / 255)
    tr, tg, tb = colorsys.hsv_to_rgb(h1, min(1.0, max(s0, s1 * 0.75)), min(1.0, v0 * lift))
    px[x, y] = (round(r + (tr * 255 - r) * k), round(g + (tg * 255 - g) * k), round(b + (tb * 255 - b) * k), a)


SHEEN_K = {"back": 0.2, "flank": 0.3, "rim": 0.18, "line": 0.1, "belly": 0.14, "pect": 0.2}
BAND_PERIOD = 34.0            # the sweeping band leaves the tail before it re-enters at the snout
BAND_HALF = 4.5               # half-width of the band, pixels along the axis


def _band_weight(x: int, y: int, t: float) -> float:
    """0..1: how close this pixel is to the light band sweeping snout -> tail once a loop."""
    centre = BAND_PERIOD * t - 4.0
    d = ((_along(x, y) - centre + BAND_PERIOD / 2) % BAND_PERIOD) - BAND_PERIOD / 2
    return max(0.0, 1.0 - abs(d) / BAND_HALF)


def _frame(t: float):
    layer, pect = _compose(t)
    img, zones = _paint(layer, pect)
    px = img.load()
    for (x, y), zone in zones.items():
        k = SHEEN_K.get(zone)
        if k is None:
            continue
        w = _band_weight(x, y, t)
        w2 = w * w * (3 - 2 * w)                       # smoothstep
        scale = _scale(x, y) and zone in ("flank", "back", "belly")
        # 1) iridescent sheen: the whole body slowly cycles violet -> pink -> cyan, and the
        #    passing band floods the scales with a stronger, brighter colour of its own
        hue = t + _along(x, y) / 48.0 - _across(x, y) / 30.0
        _tint(px, x, y, _iridescent(hue), k + 0.45 * w2, (1.12 if scale else 1.02) + 0.3 * w2)
        # 2) a soft glint rides the back just ahead of the band
        if zone in ("rim", "back") and w2 > 0:
            _blend(px, x, y, GLINT, (0.55 if zone == "rim" else 0.22) * w2)
        # 3) scale shimmer: lattice scales flash as the band crosses them
        if scale and w2 > 0.35:
            _blend(px, x, y, SPARK, 0.8 * (w2 - 0.35) / 0.65)
    # 4) photophores pulse in a wave running head to tail
    photos = sorted(k for k, z in zones.items() if z == "photo")
    for i, (x, y) in enumerate(photos):
        w = 0.5 - 0.5 * math.cos(2 * math.pi * (t * 2 - i / max(1, len(photos))))
        px[x, y] = rgba(mix(LINE[1], LINE[3], w))
    # 5) the barbel and its lure bead, then sparkles twinkling around the fish
    bead = 0.5 - 0.5 * math.cos(2 * math.pi * (t + 0.25))
    px[BARBEL[0]] = rgba(FLANK[3])
    px[BARBEL[1]] = rgba(mix(LINE[2], LINE[3], bead))
    for (sx, sy), phase, reach in SPARKLES:
        amt = max(0.0, math.sin(2 * math.pi * (t - phase))) ** 1.5
        sparkle(img, sx, sy, amt, colour=SPARK, reach=reach)
    # the art is drawn a pixel high of centre: settle it into the middle of the frame
    out = canvas(SIZE)
    out.paste(img.crop((0, 0, SIZE, SIZE - SHIFT_Y)), (0, SHIFT_Y))
    return out


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
