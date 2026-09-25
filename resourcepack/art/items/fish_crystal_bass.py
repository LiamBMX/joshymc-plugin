"""Crystal Bass: a rare fish of the JoshyMC fishing collection.

A flat 32x32 sprite in the collection's shared pose (side view, head up-left, tail
down-right). A largemouth-style bass cut from ice-blue crystal, keeping the old sprite's
cyan / sky-blue scheme: a bright faceted back (two cut planes split by crisp seams) under
a white rim, a glittering white ridge running gill to tail like a gem's girdle, deep-blue
pavilion facets below it and a pale mint countershaded belly. Bass anatomy stays: the big
jaw running back under the eye, the gill-cover seam, a notched dorsal whose spiny half is
two crystal shards, a pectoral behind the gill, translucent glassy fins with painted rays
and a forked tail of two shard lobes.

Animation (RARE, 12 frames x 3 ticks): the tail wags once per loop with its tips leading,
the pectoral and pelvic fins sway a pixel, a soft glint slides along the back (frames
1-5), then a blue-tinted sheen of light runs through the crystal from snout to tail (6-11),
while three sparkles twinkle around the fish on staggered phases.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, shade, shine, sparkle, sprite, wave

ID = "fish_crystal_bass"
NAME = "Crystal Bass"
KIND = "item"
MODEL_KEY = "fish/crystal_bass"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3
SHIFT = (1, 0)           # the art below is drawn one pixel left of centre

# ---- palette: a 7-tone crystal ramp (shadows lean indigo, lights lean white-mint) -----------
CRY = ["#173c78", "#1e5c9c", "#2a81c2", "#3ea6da", "#69c7ec", "#a2e2f7", "#d9f7ff"]
MINT = ["#7fcfe0", "#a9e9ee", "#d4faf5"]          # pale countershaded belly and jaw
WHITE = "#f7ffff"
OUTLINE = "#11315f"
OUTLINE_LIT = "#1a4784"
GILL = "#1b4f8e"
FIN = "#4c9cd8"          # glassy fin membrane: shadowed and lit shard planes
FIN_LIT = "#7cc6ee"
FIN_RAY = "#2f79bb"
FIN_EDGE = "#a9e6fb"
MEMBRANE_ALPHA, EDGE_ALPHA, BASE_ALPHA, RAY_ALPHA = 192, 210, 220, 236
FIN_OUTLINE_ALPHA = 232
PECT_TINT, PECT_EDGE = "#bfeeff", "#1d5a9c"
GLINT = "#f2feff"
SHEEN = "#8fd2ff"        # blue-tinted light running through the crystal
SHEEN_HOT = "#eefbff"
SPARKS = [  # (x, y) in frame coordinates, phase offset, colour, reach
    ((3, 4), 0.00, "#effdff", 2),
    ((27, 9), 0.36, "#c9efff", 2),
    ((5, 21), 0.68, "#e6fbff", 2),
]

# ---- geometry (drawn coordinates; SHIFT moves them to the frame) ----------------------------
THETA = math.radians(30.0)
AX = (math.cos(THETA), math.sin(THETA))          # along the body, snout -> tail
ORIGIN = (2.0, 9.5)                              # the snout, where u = 0

# Head, body and eye, painted pixel by pixel. Body digits 0-6 index the crystal ramp; W is
# a white specular glint, M/N the pale mint belly. Head plates: H crown, h brow, c cheek,
# j pale jaw, n gill-cover seam, m mouth line; the eye is a 2x2 pupil E with a catchlight e
# and a touch of iris i. The white ridge runs (9,14) -> (19,19).
ART = [
    "................................",  # 0
    "................................",  # 1
    "................................",  # 2
    "................................",  # 3
    "................................",  # 4
    "................................",  # 5
    "................................",  # 6
    "....HHhh........................",  # 7
    "...HHHHh66......................",  # 8
    "..HHeEhhn566....................",  # 9
    ".jmcEEicn5W56...................",  # 10
    "..jmccccn45556..................",  # 11
    "..jjmmccn445356.................",  # 12
    "...jjjmn44434555................",  # 13
    "....jjn3W66444455...............",  # 14
    "......MM322664444...............",  # 15
    ".......MN321266444..............",  # 16
    ".........MN12226633.............",  # 17
    "...........MMN122553............",  # 18
    "...............MMN143...........",  # 19
    "..................NN2...........",  # 20
    "................................",  # 21
]
HEAD = {"H": (CRY[6], "head"), "h": (CRY[5], "head"), "c": (CRY[4], "cheek"), "j": (MINT[1], "jaw"),
        "n": (GILL, "line"), "m": (OUTLINE, "line"),
        "E": ("#0b1a3a", "eye"), "e": (WHITE, "eye"), "i": ("#5fb8ff", "eye")}
BODY_COLOUR = {**{str(i): (CRY[i], "back" if i >= 4 else "flank") for i in range(7)},
               "W": (WHITE, "back"), "M": (MINT[1], "belly"), "N": (MINT[0], "belly")}

# Fins as polygons in drawn coordinates (pixel x covers x..x+1), painted only where there is
# no body. Rays are segments.
DORSAL = [(8.6, 9.2), (12.2, 4.6), (13.2, 9.4), (15.8, 6.6), (16.6, 11.4),
          (18.8, 10.2), (20.6, 12.0), (19.8, 15.6), (18.2, 16.6), (9.0, 11.0)]
DORSAL_RAYS = [((10.2, 9.0), (12.3, 5.2)), ((14.1, 10.8), (15.7, 7.2)),
               ((17.2, 13.0), (18.8, 10.8)), ((18.4, 14.6), (20.2, 12.4))]
ANAL = [(13.2, 18.6), (12.6, 22.0), (15.0, 21.4), (17.6, 20.8), (18.4, 19.2)]
ANAL_RAYS = [((14.5, 19.4), (13.3, 21.6)), ((16.2, 19.8), (15.6, 21.2))]
PELVIC = {"rest": [(7.2, 16.4), (7.0, 19.8), (9.6, 18.6), (10.0, 17.2)],
          "swept": [(7.2, 16.4), (8.2, 19.9), (10.4, 18.8), (10.0, 17.2)]}
PELVIC_RAYS = {"rest": [((8.0, 17.0), (7.6, 19.2))], "swept": [((8.2, 17.0), (8.8, 19.2))]}
# Tail: two shard lobes, rotated about its root for the wag.
TAIL_ROOT = (20.6, 18.8)
TAIL = [(19.6, 16.4), (23.0, 15.8), (29.4, 13.6), (28.2, 16.6), (24.8, 20.2),
        (27.0, 26.2), (25.4, 26.8), (22.0, 23.8), (19.8, 21.2)]
TAIL_RAYS = [((22.0, 17.6), (28.0, 14.6)), ((21.6, 20.4), (25.4, 25.4))]
# Pectoral fin overlay just under the ridge behind the gill: spread and folded poses.
# "p" is the glassy membrane (blended over the flank), "P" its dark trailing edge.
PECT = {
    "spread": [(8, 15, "p"), (9, 15, "p"), (9, 16, "p"), (10, 16, "P"), (10, 17, "P"), (11, 17, "P")],
    "folded": [(8, 15, "p"), (9, 15, "p"), (10, 15, "p"), (9, 16, "p"), (10, 16, "P"), (11, 16, "P")],
}


def _inside(poly, x: float, y: float) -> bool:
    hit = False
    n = len(poly)
    for i in range(n):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % n]
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            hit = not hit
    return hit


def _raster(poly) -> set:
    xs = [p[0] for p in poly]
    ys = [p[1] for p in poly]
    out = set()
    for y in range(int(min(ys)) - 1, int(max(ys)) + 2):
        for x in range(int(min(xs)) - 1, int(max(xs)) + 2):
            if _inside(poly, x + 0.5, y + 0.5):
                out.add((x, y))
    return out


def _line(a, b) -> set:
    (x0, y0), (x1, y1) = a, b
    n = max(1, int(math.ceil(max(abs(x1 - x0), abs(y1 - y0)) * 2)))
    return {(int(math.floor(x0 + (x1 - x0) * i / n)), int(math.floor(y0 + (y1 - y0) * i / n)))
            for i in range(n + 1)}


def _rot(p, angle: float, about):
    c, s = math.cos(angle), math.sin(angle)
    dx, dy = p[0] - about[0], p[1] - about[1]
    return (about[0] + dx * c - dy * s, about[1] + dx * s + dy * c)


def _along(x: float, y: float) -> float:
    """Distance along the body axis from the snout."""
    return (x - ORIGIN[0]) * AX[0] + (y - ORIGIN[1]) * AX[1]


def _body() -> dict:
    out = {}
    for y, row in enumerate(ART):
        for x, ch in enumerate(row):
            if ch != ".":
                out[(x, y)] = ch
    return out


BODY = _body()
BODY_SET = set(BODY)


def _lit_side(p, rays) -> bool:
    """Whether a fin pixel lies on the top-left (lit) side of its nearest ray: each shard is
    split along its ray into a lit and a shadowed plane."""
    cx, cy = p[0] + 0.5, p[1] + 0.5
    best, lit = 1e9, False
    for (ax, ay), (bx, by) in rays:
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy) or 1.0
        t_ = max(0.0, min(1.0, ((cx - ax) * dx + (cy - ay) * dy) / (length * length)))
        dist = math.hypot(cx - ax - dx * t_, cy - ay - dy * t_)
        if dist < best:
            nx, ny = -dy / length, dx / length
            side = (cx - ax) * nx + (cy - ay) * ny
            best, lit = dist, side * (-nx - ny) > 0
    return lit


def _compose(t: float):
    """Fin pixels (drawn coordinates) -> (zone, is_ray) for phase t, plus the pectoral."""
    wag = math.sin(2 * math.pi * t)
    pect = "folded" if math.sin(2 * math.pi * 2 * t + 1.0) > 0.3 else "spread"
    pelvic = "swept" if math.sin(2 * math.pi * t + 2.0) > 0 else "rest"
    fins = {}

    def add(poly, rays, zone):
        ray = set()
        for a, b in rays:
            ray |= _line(a, b)
        for p in _raster(poly) - BODY_SET:
            fins.setdefault(p, (zone, p in ray, _lit_side(p, rays)))

    angle = math.radians(9.0) * wag
    add([_rot(p, angle, TAIL_ROOT) for p in TAIL],
        [(_rot(a, angle, TAIL_ROOT), _rot(b, angle, TAIL_ROOT)) for a, b in TAIL_RAYS], "tail")
    add(DORSAL, DORSAL_RAYS, "fin")
    add(ANAL, ANAL_RAYS, "fin")
    add(PELVIC[pelvic], PELVIC_RAYS[pelvic], "fin")
    return fins, {(x, y): part for x, y, part in PECT[pect]}


def _paint(fins: dict, pect: dict):
    img = canvas(SIZE)
    px = img.load()
    sx, sy = SHIFT
    info = {}   # frame pixel -> (zone, tone rank 0..1, distance along the body)
    for (x, y), ch in BODY.items():
        X, Y = x + sx, y + sy
        colour, zone = HEAD[ch] if ch in HEAD else BODY_COLOUR[ch]
        rank = int(ch) / 6 if ch.isdigit() else 1.0 if ch in "WHh" else 0.7
        if (x, y) in pect:
            part = pect[(x, y)]
            colour = mix(colour, PECT_TINT if part == "p" else PECT_EDGE, 0.5 if part == "p" else 0.7)
            zone, rank = "pect", 0.5
        px[X, Y] = rgba(colour)
        info[(X, Y)] = (zone, rank, _along(x + 0.5, y + 0.5))
    for (x, y), (zone, is_ray, lit) in fins.items():
        X, Y = x + sx, y + sy
        near = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        membrane = FIN_LIT if lit else FIN
        if is_ray:
            colour, alpha = FIN_RAY, RAY_ALPHA
        elif any(n not in fins and n not in BODY_SET for n in near):
            colour, alpha = FIN_EDGE if lit else mix(FIN_EDGE, FIN, 0.45), EDGE_ALPHA
        elif any(n in BODY_SET for n in near):
            colour, alpha = shade(membrane, -0.15, 0.1), BASE_ALPHA
        else:
            colour, alpha = membrane, MEMBRANE_ALPHA
        c = rgba(colour)
        px[X, Y] = (c[0], c[1], c[2], alpha)
        info[(X, Y)] = (zone, 0.0, _along(x + 0.5, y + 0.5))
    # 1 px outline around the silhouette: solid beside the body, a touch translucent where it
    # only borders fins, a little lighter on the side facing the top-left light
    filled = set(info)
    solid = {(x + sx, y + sy) for (x, y) in BODY_SET}
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
    return img, info


def _frame(t: float):
    fins, pect = _compose(t)
    img, info = _paint(fins, pect)
    px = img.load()
    step = round(t * FRAMES)
    # 1) frames 1-5: a soft glint slides along the back, snout to tail
    if 1 <= step <= 5:
        centre = 8.0 + 21.0 * (step - 1) / 4.0
        width = 3.5
        run = (centre + 2 * width) / (32 * AX[0] + 32 * AX[1] + 4 * width)
        glint = shine(img, run, colour=GLINT, width=width, strength=0.6, angle=30.0, pause=0.0).load()
        for (x, y), (zone, _, _) in info.items():
            if zone in ("back", "head"):
                px[x, y] = glint[x, y]
    # 2) frames 6-11: a blue-tinted sheen of light runs through the crystal, snout to tail;
    #    bright facets flare near-white, deep facets glow blue, fins catch a little of it
    elif step >= 6:
        centre = 1.0 + 23.0 * (step - 6) / 5.0
        for (x, y), (zone, rank, along) in info.items():
            if zone in ("eye", "line"):
                continue
            k = max(0.0, 1.0 - abs(along - centre) / 3.6)
            if k <= 0:
                continue
            r, g, b, a = px[x, y]
            if zone in ("fin", "tail"):
                c = rgba(mix((r, g, b, a), SHEEN, 0.4 * k))
            else:
                c = rgba(mix((r, g, b, a), mix(SHEEN, SHEEN_HOT, rank), (0.35 + 0.35 * rank) * k))
            px[x, y] = (c[0], c[1], c[2], a)
    # 3) sparkles twinkle around the fish
    for (sx_, sy_), off, colour, reach in SPARKS:
        sparkle(img, sx_, sy_, wave(t, off) ** 3, colour=colour, reach=reach)
    return img


def frames():
    return animate(_frame, FRAMES)


def textures() -> None:
    save_animation(frames(), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
