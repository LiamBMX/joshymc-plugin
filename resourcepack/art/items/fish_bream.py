"""Bream (COMMON fish): a deep, hump-backed common bream in olive bronze.

A flat 32x32 sprite in the fishing-collection style: side view, head to the left and a
little up, the body on a 3:1 diagonal down to a deeply forked tail. Bream anatomy: a small
head with a small downturned mouth, a steep forehead rising into the hump of the back, a
tall pointed dorsal fin at the peak, a very long anal fin under the rear half, low
pectorals and small pelvics. Colours follow the old 16x16 texture (khaki-olive back,
bronze-gold flanks, cream belly), with the bream's dark slate fins and the fine net its
scale edges draw on the flank.

The body is hand-painted (BODY_ROWS); the fins are polygons with painted rays so they
can move. Light comes from the top-left: a sunlit rim along the back over a dark olive
saddle, a golden sheen band on the upper flank, and a countershaded cream belly.

Animation: 8 frames x 3 ticks (1.2 s loop). The tail beats about 1 px, the dorsal fin
leans, a ripple runs back along the anal fin, the pectoral and pelvic fins flick, and a
soft golden glint slides along the back from head to tail.
"""
from __future__ import annotations

import math

from PIL import Image

from art.kit import animate, rgba, save_animation, sprite

ID = "fish_bream"
NAME = "Bream"
KIND = "item"
MODEL_KEY = "fish/bream"
COUNTERPART = "item/salmon"

N = 32
FRAMES = 8
SS = 4   # supersamples per axis when rasterising fins
DY = 2   # the painting is drawn 2 px higher than it sits in the frame

# --------------------------------------------------------------------------------------
# Palette (dark -> light). Shadows lean cool olive-slate, lights lean warm gold-cream.
# --------------------------------------------------------------------------------------
BODY = ["#2f3329", "#424535", "#57573d", "#716a44", "#8e804a", "#ad9751", "#c8ae5c",
        "#dbc57a", "#e8dca4", "#f2ebc9", "#fcf9ea"]
TONES = "abcdefghijk"          # a..k = BODY[0..10]
OUTLINE = "#282a1f"            # deep olive-umber, never black
FIN = ["#25272a", "#363a3b", "#4b4f4e", "#636762", "#80837a", "#9ea195"]   # dark slate
FIN_OUT = "#202325"
SPECIAL = {"E": "#11140e", "C": "#fffdf0", "R": "#dcc57c", "M": OUTLINE}
GLINT_CAP = 9                  # the glint never goes past pale cream (no hard white flash)

# Hand-painted body: row -> (first column, pixels). a..k are BODY tones; E eye, C its
# catchlight, R the golden iris ring, M the mouth.
BODY_ROWS = {
    8: (12, "iih"),
    9: (8, "hhiidddg"),
    10: (5, "iihddddddddf"),
    11: (4, "iCEgddddeeedc"),
    12: (3, "ihEERhdegggfecf"),
    13: (3, "MhRRghdghhggfdc"),
    14: (3, "gihgghdggffffece"),
    15: (5, "ihhdggfgggfeece"),
    16: (6, "ihdhhhhhggfeece"),
    17: (7, "ghiiiiihhgfeccee"),
    18: (9, "hhiiihhggfeccc"),
    19: (11, "ghhgggffeedd"),
}

# Body axis (for the glint): the fish rises 1 px every 3 px toward the head.
AXIS_O = (2.7, 12.2)
AXIS = (3 / math.sqrt(10), 1 / math.sqrt(10))


def body_map() -> dict:
    out = {}
    for y, (x0, row) in BODY_ROWS.items():
        for i, ch in enumerate(row):
            out[(x0 + i, y + DY)] = ch
    return out


def columns(bmap: dict) -> dict:
    """x -> (top row, bottom row) of the body in that column."""
    cols = {}
    for (x, y) in bmap:
        lo, hi = cols.get(x, (y, y))
        cols[x] = (min(lo, y), max(hi, y))
    return cols


# --------------------------------------------------------------------------------------
# Fins (painting coordinates; pixel (x, y) covers [x, x+1] x [y, y+1])
# --------------------------------------------------------------------------------------

def in_poly(x: float, y: float, pts) -> bool:
    inside = False
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            inside = not inside
    return inside


def rot(pt, origin, degrees):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    x, y = pt[0] - origin[0], pt[1] - origin[1]
    return origin[0] + x * c - y * s, origin[1] + x * s + y * c


class Fin:
    """A fin outline swung `angle` degrees about `pivot`, placed in the frame."""

    def __init__(self, name, pts, pivot, angle=0.0):
        self.name, self.pivot, self.angle = name, pivot, angle
        self.pts = [rot((x, y + DY), (pivot[0], pivot[1] + DY), angle) for x, y in pts]

    def rest(self, x, y):
        """A frame point back in the fin's painting coordinates, sway undone."""
        px, py = rot((x, y), (self.pivot[0], self.pivot[1] + DY), -self.angle)
        return px, py - DY

    def contains(self, x, y):
        return in_poly(x, y, self.pts)


TAIL_PIVOT = (22.4, 18.4)


def fins(t: float) -> dict:
    ph = 2 * math.pi * t

    def ripple(x, k=1.0):  # a wave running back along the anal fin's margin
        return 0.55 * k * math.sin(ph - (x - 14.0) * 0.9)

    dorsal = Fin("dorsal", [(12.6, 8.8), (13.3, 6.8), (14.2, 5.0), (15.3, 3.3), (15.9, 4.6),
                            (16.6, 6.2), (17.4, 7.8), (18.2, 9.6), (17.2, 11.2)],
                 (14.8, 9.2), 8.0 * math.sin(ph - 0.6))
    anal = Fin("anal", [(14.2, 19.4), (14.4, 21.4 + ripple(14.4, 0.5)), (14.9, 22.8 + ripple(14.9)),
                        (15.5, 22.4 + ripple(15.5)), (17.0, 21.8 + ripple(17.0)),
                        (18.5, 21.3 + ripple(18.5)), (20.0, 20.9 + ripple(20.0, 0.8)),
                        (21.7, 20.5 + ripple(21.7, 0.5)), (22.4, 19.6)], (17.5, 19.6))
    pelvic = Fin("pelvic", [(8.4, 17.8), (10.4, 18.2), (11.3, 19.8), (11.9, 22.0), (10.8, 21.6),
                            (9.4, 19.9), (8.8, 18.9)], (9.4, 18.3), 9.0 * math.sin(ph + 1.3))
    pectoral = Fin("pectoral", [(8.6, 14.4), (10.0, 14.6), (12.6, 16.1), (13.9, 17.5), (12.3, 17.9),
                                (9.6, 16.8), (8.8, 16.0)], (9.0, 15.2), 10.0 * math.sin(ph + 2.2))
    tail = Fin("tail", [(22.2, 16.6), (24.6, 15.2), (27.2, 13.9), (29.6, 12.9), (28.4, 14.6),
                        (26.9, 16.4), (25.5, 18.9), (26.3, 20.7), (27.2, 22.4), (28.4, 24.3),
                        (26.5, 23.4), (24.5, 21.8), (22.2, 20.3)], TAIL_PIVOT, 7.0 * math.sin(ph))
    return {f.name: f for f in (tail, dorsal, anal, pelvic, pectoral)}


def fin_shade(fin: Fin, x: float, y: float) -> tuple[int, int]:
    """(FIN index, alpha) for a fin pixel centred at (x, y): a translucent mid-slate
    membrane with thin dark rays, a paler fleshy base and a dusky margin."""
    x, y = fin.rest(x, y)
    ray = False
    if fin.name == "tail":
        dx, dy = x - TAIL_PIVOT[0], y - TAIL_PIVOT[1]
        r = math.hypot(dx, dy)
        ray = (math.degrees(math.atan2(dy, dx)) / 15.0 + 0.2) % 1.0 < 0.36
        if r < 2.2:
            return 4, 220
        if r > 6.3:
            return 1, 214
    elif fin.name == "dorsal":
        ray = ((x - 0.45 * (9.5 - y)) / 1.35) % 1.0 < 0.38
        if y > 8.6:
            return 4, 220
        if y < 5.0:
            return 1, 214
    elif fin.name == "anal":
        ray = ((x + 0.55 * (y - 19.5)) / 1.4) % 1.0 < 0.38
        if y < 20.2:
            return 4, 220
        if y > 21.8 or (x > 18 and y > 21.0):
            return 1, 214
    return (1, 212) if ray else (3, 196)


def pectoral_shift(fin: Fin, x: float, y: float) -> tuple[int, float]:
    """The pectoral lies on the flank: (body tone offset, slate tint) for the body seen
    through it. A pale leading ray on top, smoky membrane, a darker tip."""
    x, y = fin.rest(x, y)
    if y - 14.5 < (x - 8.6) * 0.55:
        return 1, 0.2
    return (-2, 0.35) if x < 12.4 else (-3, 0.4)


def scale_net(x: int, y: int) -> int:
    """The bream's reticulated scales: the dark scale edges form a fine diamond net."""
    return -1 if (x + y) % 4 == 0 or (x - y) % 4 == 0 else 0


def in_flank(x: int, y: int, ch: str, cols: dict) -> bool:
    """Mid-flank pixels that show the scale net (not the rim, saddle, sheen or belly)."""
    top, bottom = cols[x]
    return ch in "ef" and 10 <= x <= 19 and top + 4 <= y <= bottom - 2


def glint(x: int, y: int, cols: dict, t: float) -> int:
    """Tone boost of the soft glint sliding along the back from head to tail."""
    top, bottom = cols[x]
    s = (bottom - y) / max(1, bottom - top)      # 1 at the back, 0 at the belly
    u = (x + 0.5 - AXIS_O[0]) * AXIS[0] + (y - DY + 0.5 - AXIS_O[1]) * AXIS[1]
    centre = -5.0 + 30.0 * t                     # off the snout at t = 0, past the tail by 1
    along = math.exp(-((u - centre) / 2.6) ** 2)
    across = max(0.0, min(1.0, (s - 0.38) / 0.36))
    return round(2.2 * along * across)


# --------------------------------------------------------------------------------------
# Painting
# --------------------------------------------------------------------------------------

def coverage(test) -> list[list[bool]]:
    grid = [[False] * N for _ in range(N)]
    for py in range(N):
        for px in range(N):
            hits = 0
            for sy in range(SS):
                for sx in range(SS):
                    hits += test(px + (sx + 0.5) / SS, py + (sy + 0.5) / SS)
            grid[py][px] = hits * 2 >= SS * SS
    return grid


def body_colour(tone: int) -> tuple:
    return rgba(BODY[max(0, min(len(BODY) - 1, tone))])


def paint(t: float) -> Image.Image:
    bmap = body_map()
    cols = columns(bmap)
    shapes = fins(t)
    cov = {name: coverage(fin.contains) for name, fin in shapes.items()}
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    px = img.load()
    for y in range(N):
        for x in range(N):
            ch = bmap.get((x, y))
            if ch in SPECIAL:
                px[x, y] = rgba(SPECIAL[ch])
            elif ch is not None:
                tone = TONES.index(ch) + (scale_net(x, y) if in_flank(x, y, ch, cols) else 0)
                boost = glint(x, y, cols, t)
                if boost:
                    tone = max(tone, min(tone + boost, GLINT_CAP))
                if cov["pectoral"][y][x]:
                    shift, tint = pectoral_shift(shapes["pectoral"], x + 0.5, y + 0.5)
                    c, f = body_colour(tone + shift), rgba(FIN[3])
                    px[x, y] = tuple(round(c[i] * (1 - tint) + f[i] * tint) for i in range(3)) + (255,)
                else:
                    px[x, y] = body_colour(tone)
            else:
                for name in ("pelvic", "dorsal", "anal", "tail"):
                    if cov[name][y][x]:
                        k, a = fin_shade(shapes[name], x + 0.5, y + 0.5)
                        px[x, y] = rgba(FIN[k])[:3] + (a,)
                        break
    # Silhouette outline (4-connected): opaque around the body, a touch softer on fins.
    src = img.copy().load()
    for y in range(N):
        for x in range(N):
            if src[x, y][3]:
                continue
            near = [src[x + dx, y + dy][3] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                    if 0 <= x + dx < N and 0 <= y + dy < N and src[x + dx, y + dy][3]]
            if near:
                px[x, y] = rgba(OUTLINE) if max(near) == 255 else rgba(FIN_OUT)[:3] + (242,)
    return img


def textures() -> None:
    save_animation(animate(paint, FRAMES), "fish", frametime=3)


def models() -> dict:
    return {"main": sprite("fish")}
