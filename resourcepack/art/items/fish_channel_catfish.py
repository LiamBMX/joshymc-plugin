"""Channel Catfish: an uncommon river and swamp fish from the JoshyMC fishing collection.

A flat 32x32 sprite in the fishing-collection style: side view, head up and to the left,
body running diagonally down to a deeply forked tail. Channel catfish anatomy: a broad flat
head with a wide mouth, long drooping barbels (whiskers) and a short nasal barbel, a tall
spined dorsal fin right behind the head, a small free adipose fin near the tail, spiny
pectoral fins, a long rounded anal fin and a deeply forked caudal fin. Smooth scaleless
skin: dark slate-olive back with a lit rim, olive flanks with scattered black spots and a
wet sheen, and a pale cream belly (the old texture's olive/sage scheme).

Animation (UNCOMMON, 12 frames x 3 ticks): the tail wags about the peduncle, the pectoral
fin paddles, the anal fin ripples and the whiskers trail, a clear silver-green shimmer band
washes along the body from head to tail, then a soft glint slides along the back.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, shine, sprite

ID = "fish_channel_catfish"
NAME = "Channel Catfish"
KIND = "item"
MODEL_KEY = "fish/channel_catfish"
COUNTERPART = "item/cod"

FRAMES = 12
FRAMETIME = 3

PALETTE = {
    # skin: slate-teal back -> olive flank (hue shifts warm as it lightens)
    "1": "#24362f", "2": "#2f443a", "3": "#3e5646", "4": "#55714b", "5": "#6c8953", "6": "#86a060",
    "7": "#9db57a", "8": "#bccf93", "9": "#d9e6b2",
    # countershaded belly
    "a": "#8f9474", "b": "#a3a57f", "c": "#c6c59a", "d": "#dcdab4", "e": "#eceacb",
    # spots, eye, mouth, gill slit
    "s": "#1f2c24", "z": "#33463a", "E": "#0c110e", "W": "#f3f7e6", "M": "#1f2a23", "G": "#2c3c33",
    # fins: dusky olive membrane, darker rays, lit spine and edges
    "m": "#474c33", "n": "#676a46", "o": "#85885c", "p": "#a4a676", "k": "#bfc08f",
    # barbels
    "B": "#212c25", "C": "#3f4a3a",
}
OUTLINE = "#19241f"
FIN = "mnopk"            # translucent fin texels
FIN_ALPHA = {"m": 205, "n": 198, "o": 200, "p": 212, "k": 220}
EYE = "EW"

# Body, head and the fins that stay still (dorsal, adipose, pelvic). One texel per character.
BODY = [
    "................................",
    "................................",
    "................................",
    "................................",
    "..............k.................",
    ".............ko.................",
    "............knmp................",
    ".....887...knmno................",
    "....864356knmnno................",
    "....M75WE311nnmo................",
    "....dM6EE4221mnn................",
    ".....dM65533211m................",
    "......dc67G43221................",
    ".......cd7G573327...............",
    "........bc6s588327..............",
    ".........bc65557227.............",
    "..........bc6z5s4227on..........",
    "...........bcc6554z22on.........",
    "............bbc66s5422n.........",
    ".............obcc66z422.........",
    ".............nobbcc6s427........",
    ".................bbcc5s36.......",
    "...................bbcc6b.......",
    ".....................bbb........",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
]


def _patch(x0: int, y0: int, rows: list[str]) -> dict:
    return {(x0 + i, y0 + j): ch for j, row in enumerate(rows) for i, ch in enumerate(row) if ch != "."}


# Moving parts, one patch per pose (x0, y0 is the patch's top-left texel).
TAIL = {
    "mid": _patch(24, 17, [
        "....op",
        "..omn.",
        ".omn..",
        "nmn...",
        ".n....",
        ".n....",
        "omn...",
        "omn...",
        ".omn..",
        "..om..",
        "..on..",
        "...p..",
    ]),
    "ccw": _patch(24, 16, [   # swept up: upper lobe tilts back, lower lobe swings out
        "...op",
        "..omn",
        ".omn.",
        ".mn..",
        "nmn..",
        ".n...",
        ".n...",
        "omn..",
        ".omn.",
        "..omn",
        "...om",
        "...on",
        "....p",
    ]),
    "cw": _patch(24, 18, [    # swept down: upper lobe flattens, lower lobe tucks in
        "....op",
        "..omn.",
        "nmmn..",
        ".n....",
        ".n....",
        "omn...",
        "omn...",
        "omn...",
        ".om...",
        ".on...",
        "..p...",
    ]),
}
DORSAL = {  # the spine stays rigid; the membrane behind it flutters
    "mid": {},
    "flutter": _patch(15, 6, ["op", ".o"]),
}
PECTORAL = {
    "mid": _patch(8, 15, ["p..", "on.", ".om", ".pn", "..o"]),
    "flare": _patch(8, 15, ["p.", "on", "om", "pn", ".o"]),
}
ANAL = {
    "mid": _patch(15, 21, ["on....", ".onm..", "..nmnm", "....no"]),
    "ripple": _patch(15, 21, ["on....", ".onm..", "..nmnm", "...onn", ".....o"]),
}
WHISKERS = {
    "mid": _patch(1, 5, [
        "..C..",
        "...B.",
        ".....",
        ".....",
        ".....",
        ".....",
        "..B..",
        ".B..B",
        "B...B",
        "B..B.",
        "B..B.",
        ".B..B",
        ".C..C",
    ]),
    "trail": _patch(1, 5, [
        "..C...",
        "...B..",
        "......",
        "......",
        "......",
        "......",
        "..B...",
        ".B..B.",
        "B...B.",
        "B..B..",
        ".B..B.",
        ".B..B.",
        "..C..C",
    ]),
}


def _poses(t: float) -> dict:
    """Which pose each moving part shows at phase t (one full swim cycle per loop)."""
    s, c = math.sin(2 * math.pi * t), math.cos(2 * math.pi * t)
    return {
        "tail": "ccw" if s > 0.6 else "cw" if s < -0.6 else "mid",
        "dorsal": "flutter" if s < -0.6 else "mid",
        "pectoral": "flare" if c > 0.4 else "mid",
        "anal": "ripple" if s < -0.4 else "mid",
        "whiskers": "trail" if c < -0.4 else "mid",
    }


def _axis(x: int, y: int) -> float:
    """Distance along the body axis from the snout (texel centre), in texels."""
    return (x + 0.5 - 4.0) * 0.835 + (y + 0.5 - 9.0) * 0.551


def _texels(t: float) -> dict:
    poses = _poses(t)
    cells = {}
    for y, row in enumerate(BODY):
        for x, ch in enumerate(row):
            if ch != ".":
                cells[(x, y)] = ch
    for part, table in (("tail", TAIL), ("pectoral", PECTORAL), ("anal", ANAL)):
        for key, ch in table[poses[part]].items():
            cells.setdefault(key, ch)
    cells.update(DORSAL[poses["dorsal"]])  # the membrane edge moves over the static fin
    return cells


SHIMMER = (0, 8)   # frames that carry the shimmer band, head to tail
GLINT = (6, 12)    # frames that carry the back glint, head to tail
BACK_BOX = (4, 4, 25, 24)  # crop that holds the whole back, for kit.shine()


def _back_texels() -> dict:
    """The lit back, as texel -> how much of the glint it catches: the top texel of every
    body column (the dorsal spine and skin rim fully, other fin edges partly), then up to
    two darker back texels under each skin rim texel."""
    out = {}
    for x in range(24):  # the tail starts at x = 24
        ys = [y for y in range(32) if BODY[y][x] != "."]
        if not ys:
            continue
        ch = BODY[ys[0]][x]
        if ch in FIN:
            out[(x, ys[0])] = 1.0 if ch == "k" else 0.6
            continue
        out[(x, ys[0])] = 1.0
        for depth, weight in ((1, 0.8), (2, 0.45)):
            below = BODY[ys[0] + depth][x]
            if not (below.isdigit() or below in "sz"):
                break
            out[(x, ys[0] + depth)] = weight
    return out


def _tint(px, key, colour, k: float) -> None:
    r, g, b, a = px[key]
    nr, ng, nb, _ = rgba(mix((r, g, b, 255), colour, k))
    px[key] = (nr, ng, nb, a)


def _frame(t: float):
    cells = _texels(t)
    img = canvas(32)
    px = img.load()
    for (x, y), ch in cells.items():
        r, g, b, _ = rgba(PALETTE[ch])
        px[x, y] = (r, g, b, FIN_ALPHA.get(ch, 255))
    i = t * FRAMES

    # Shimmer: a clear silver-green band (bright core, soft edges) washes along the body
    # from the snout to the tail tips.
    if SHIMMER[0] <= i < SHIMMER[1]:
        p = (i - SHIMMER[0]) / (SHIMMER[1] - SHIMMER[0])
        centre = -1.5 + 33.0 * p
        for (x, y), ch in cells.items():
            if ch in EYE:
                continue
            d = abs(_axis(x, y) - centre)
            k = 0.62 if d < 0.9 else 0.3 if d < 2.3 else 0.0
            if k:
                _tint(px, (x, y), "#dcf0ee", k * (0.7 if ch in FIN else 1.0))

    # Glint: kit.shine() at low strength, run over the back only, so a soft warm highlight
    # slides along the rim from the head to the tail.
    if GLINT[0] <= i < GLINT[1]:
        p = (i - GLINT[0]) / (GLINT[1] - GLINT[0])
        x0, y0, x1, y1 = BACK_BOX
        back = {k: w for k, w in _back_texels().items() if x0 <= k[0] < x1 and y0 <= k[1] < y1}
        layer = canvas(x1 - x0, y1 - y0)
        lp = layer.load()
        for (x, y) in back:
            lp[x - x0, y - y0] = px[x, y]
        run = 0.62
        lit = shine(layer, run * (0.16 + 0.7 * p), colour="#fbffe8", width=4.0, strength=0.75, angle=33.0,
                    pause=1 - run)
        sp = lit.load()
        for (x, y), w in back.items():  # deeper texels catch less of it
            r0, g0, b0, a0 = px[x, y]
            r1, g1, b1, _ = sp[x - x0, y - y0]
            px[x, y] = (round(r0 + (r1 - r0) * w), round(g0 + (g1 - g0) * w), round(b0 + (b1 - b0) * w), a0)

    # 1 px outline around the whole silhouette (body, fins and tail), then the barbels.
    out = img.copy()
    op = out.load()
    edge = rgba(OUTLINE)
    for y in range(32):
        for x in range(32):
            if px[x, y][3]:
                continue
            if any(0 <= x + dx < 32 and 0 <= y + dy < 32 and px[x + dx, y + dy][3]
                   for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                op[x, y] = edge
    for key, ch in WHISKERS[_poses(t)["whiskers"]].items():
        op[key] = rgba(PALETTE[ch])
    return out


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
