"""Gudgeon: a common river fish of the JoshyMC fishing collection (rarity COMMON).

A flat 32x32 animated sprite in the shared fish style: side view, head up-left, tail
down-right, 1 px hue-shifted outline, light from the top-left. The gudgeon's tells are
all there: a flat-bellied body with a big head and a large eye set high, an underslung
mouth with a barbel, an olive-brown mottled back, a row of dark blotches along the
lateral line on a mauve-silver flank, a cream belly, a brassy gill cover, and pale
straw fins with the dorsal and forked tail fins spotted in rows.

The body is a hand-placed pixel map; the fins are drawn around it every frame so they
can sway. Animation (COMMON): 8 frames at frametime 3. The tail and fins sway about a
pixel on offset phases and a soft glint slides along the back.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, ramp, rgba, save_animation, shine, sprite

ID = "fish_gudgeon"
NAME = "Gudgeon"
KIND = "item"
MODEL_KEY = "fish/gudgeon"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 8
FRAMETIME = 3

# ------------------------------------------------------------------------------------
# Palette: every material gets its own hue-shifted ramp (darkest -> lightest)
# ------------------------------------------------------------------------------------
OLIVE = ramp("#877548", 6, contrast=0.6)      # back: bronze-olive
MAUVE = ramp("#b08a9a", 6, contrast=0.55)     # flank sheen: rosy mauve-silver (the old sprite's)
SILVER = ramp("#dcd2b9", 5, contrast=0.5)     # belly: warm cream-silver
BLOTCH = ramp("#4c3f59", 4, contrast=0.5)     # lateral blotches
FIN = ramp("#d0b67c", 5, contrast=0.55)       # pale golden-straw fins
GOLD = ramp("#d8aa42", 5, contrast=0.6)       # iris (the old sprite's yellow accent)
BRASS = ramp("#bea36a", 5, contrast=0.5)      # brassy cheek and gill cover
OUTLINE = "#302635"                           # deep violet-brown, never black
GLINT = "#fff4d8"

PALETTE = {
    "o": OLIVE[1], "p": OLIVE[2], "q": OLIVE[3], "r": OLIVE[4],          # back, dark -> lit
    "s": mix(OLIVE[3], "#a3a488", 0.6), "t": mix(OLIVE[4], "#c2c29f", 0.5),  # sage-olive upper flank
    "m": MAUVE[2], "n": MAUVE[3], "u": mix(MAUVE[4], SILVER[2], 0.5),    # mauve-silver sheen
    "c": SILVER[1], "d": SILVER[2], "e": SILVER[3], "f": SILVER[4],      # cream belly
    "b": mix(BLOTCH[1], BLOTCH[2], 0.3),                                  # dusky lateral blotches
    "g": BRASS[1], "h": BRASS[2], "i": BRASS[3],                          # cheek, gill cover
    "E": "#1f1927", "L": "#fdf7e6", "y": GOLD[2],                         # eye, catchlight, iris
    "w": MAUVE[1],                                                        # mouth
}

# The body, pixel by pixel (screen space, rows 7-20): eye and gold iris high on the big
# head, brassy cheek, gill seam, underslung mouth, mottled back with a lit ridge, the
# row of dark lateral blotches, cream belly. Fins, outline and barbel are added around
# it every frame.
BODY_TOP = 7
BODY_ART = [
    ".....rrq..................",
    "...qqpppqqqq..............",
    "..hppLEppoppqq............",
    "..ihhEEyhopoopqq..........",
    "...wiyyhhgtsppppq.........",
    "...dfehhgnbbsppppq........",
    "....deehgnbbnsspoop.......",
    ".....deguuunnbbssppp......",
    "......ddeeuuubbnnqpop.....",
    ".......ddeeuuuunnbbqpp....",
    ".........ddeeuuunbbmqop...",
    "...........ddddeuunnbbq...",
    "..............ddccdnnmm...",
    "....................cm....",
]

# ------------------------------------------------------------------------------------
# Fish-local coordinates (s along the body from the snout, n toward the back) for the
# fins. The body axis runs at a 2:1 pixel slope, so its edges make clean staircases.
# ------------------------------------------------------------------------------------
TILT = math.atan2(1, 2)
AXIS = (math.cos(TILT), math.sin(TILT))      # snout -> tail on screen (y grows downward)
DORSAL = (math.sin(TILT), -math.cos(TILT))   # toward the back
SNOUT = (2.5, 9.3)                           # snout tip, screen px


def _to_fish(x: float, y: float) -> tuple[float, float]:
    dx, dy = x - SNOUT[0], y - SNOUT[1]
    return dx * AXIS[0] + dy * AXIS[1], dx * DORSAL[0] + dy * DORSAL[1]


def _to_screen(s: float, n: float) -> tuple[float, float]:
    return SNOUT[0] + s * AXIS[0] + n * DORSAL[0], SNOUT[1] + s * AXIS[1] + n * DORSAL[1]


def _pixel(s: float, n: float) -> tuple[int, int]:
    x, y = _to_screen(s, n)
    return int(math.floor(x)), int(math.floor(y))


def _inside(poly, a: float, b: float) -> bool:
    hit = False
    j = len(poly) - 1
    for i in range(len(poly)):
        ai, bi = poly[i]
        aj, bj = poly[j]
        if (bi > b) != (bj > b) and a < (aj - ai) * (b - bi) / (bj - bi) + ai:
            hit = not hit
        j = i
    return hit


NEIGHBOURS = ((1, 0), (-1, 0), (0, 1), (0, -1))


class Fin:
    """A fin polygon in its own frame (a along the body, b toward the back) around an
    anchor on the body; swaying is a small rotation about that anchor."""

    def __init__(self, anchor, poly, rays=(), spots=(), membrane=FIN[2], ray=FIN[3], spot=FIN[0],
                 edge=None, alpha=200):
        self.anchor, self.poly, self.rays, self.spots = anchor, poly, rays, spots
        self.membrane, self.ray, self.spot, self.edge, self.alpha = membrane, ray, spot, edge, alpha

    def local(self, s: float, n: float, angle: float) -> tuple[float, float]:
        ds, dn = s - self.anchor[0], n - self.anchor[1]
        c, si = math.cos(-angle), math.sin(-angle)
        return ds * c - dn * si, ds * si + dn * c

    def world(self, a: float, b: float, angle: float) -> tuple[float, float]:
        c, si = math.cos(angle), math.sin(angle)
        return self.anchor[0] + a * c - b * si, self.anchor[1] + a * si + b * c

    def mask(self, angle: float) -> set:
        """Pixels at least half covered by the fin (4x4 sub-samples)."""
        out = set()
        for y in range(SIZE):
            for x in range(SIZE):
                hits = sum(_inside(self.poly, *self.local(*_to_fish(x + (i + 0.5) / 4, y + (j + 0.5) / 4), angle))
                           for j in range(4) for i in range(4))
                if hits >= 8:
                    out.add((x, y))
        return out

    def paint(self, img, angle: float) -> set:
        px = img.load()
        mask = self.mask(angle)
        colours = {p: (self.membrane, self.alpha) for p in mask}
        if self.edge:
            # Over the body the fin needs its own edge: shadowed along the lower/trailing
            # side, catching the light along the upper-left side, root left soft.
            for (x, y) in mask:
                a, _ = self.local(*_to_fish(x + 0.5, y + 0.5), angle)
                if a <= 0.9:
                    continue
                if (x + 1, y) not in mask or (x, y + 1) not in mask:
                    colours[(x, y)] = (self.edge, min(255, self.alpha + 25))
                elif (x - 1, y) not in mask or (x, y - 1) not in mask:
                    colours[(x, y)] = (self.ray, min(255, self.alpha + 15))
        for p0, p1 in self.rays:
            for step in range(41):
                t = step / 40
                p = _pixel(*self.world(p0[0] + (p1[0] - p0[0]) * t, p0[1] + (p1[1] - p0[1]) * t, angle))
                if p in mask:
                    colours[p] = (self.ray, min(255, self.alpha + 15))
        for a, b in self.spots:
            p = _pixel(*self.world(a, b, angle))
            if p in mask:
                colours[p] = (self.spot, min(255, self.alpha + 30))
        for (x, y), (c, al) in colours.items():
            r, g, b_, _ = rgba(c)
            dst = px[x, y]
            if dst[3]:
                k = al / 255
                px[x, y] = (round(dst[0] + (r - dst[0]) * k), round(dst[1] + (g - dst[1]) * k),
                            round(dst[2] + (b_ - dst[2]) * k), 255)
            else:
                px[x, y] = (r, g, b_, al)
        return mask


# Forked tail: broad lobe roots tapering to points around a deep V, spotted in rows.
TAIL = Fin(anchor=(21.3, 0.0),
           poly=[(-0.6, 2.0), (1.2, 2.7), (2.8, 3.6), (4.2, 4.5), (5.3, 5.2), (6.0, 5.4), (5.6, 4.0), (4.8, 2.3),
                 (4.0, 0.9), (3.8, 0.0), (4.0, -0.9), (4.8, -2.3), (5.6, -4.0), (6.0, -5.4), (5.3, -5.2),
                 (4.2, -4.5), (2.8, -3.6), (1.2, -2.7), (-0.6, -2.0)],
           rays=[((0.6, 1.4), (5.6, 4.1)), ((0.6, -1.4), (5.6, -4.1))],
           spots=[(2.4, 1.9), (4.3, 3.2), (2.4, -1.9), (4.3, -3.2)],
           membrane=FIN[2], ray=FIN[3], alpha=205)
# Tall triangular dorsal fin at mid-back: bright leading ray, two rows of spots.
DORSAL_FIN = Fin(anchor=(10.2, 4.70),
                 poly=[(-1.8, -0.8), (-0.9, 1.6), (0.0, 3.6), (0.7, 4.8), (1.5, 4.4), (2.0, 3.0), (2.8, 1.5),
                       (3.9, 0.2), (4.4, -0.8)],
                 rays=[((-1.1, 0.4), (0.5, 4.4))],
                 spots=[(0.4, 1.7), (2.0, 1.4), (1.0, 3.2)],
                 membrane=FIN[2], ray=FIN[4], alpha=205)
# Low pectoral fan behind the gill cover, lying over the flank.
PECTORAL = Fin(anchor=(7.0, -1.9),
               poly=[(-0.4, 0.8), (1.5, 1.0), (3.2, 0.5), (4.6, -0.5), (4.2, -1.5), (2.6, -1.9), (0.9, -1.4),
                     (-0.4, -0.5)],
               rays=[((0.2, 0.3), (4.1, -0.4))],
               membrane=mix(FIN[3], GOLD[3], 0.3), ray=FIN[4], edge=FIN[1], alpha=215)
# Small pelvic and anal fins trail back along the flat belly instead of hanging like legs.
PELVIC = Fin(anchor=(12.2, -4.30),
             poly=[(-0.6, 0.7), (1.8, 0.7), (2.6, -0.1), (3.6, -0.8), (4.4, -1.4), (3.0, -1.5), (1.4, -1.1),
                   (-0.1, -0.4)],
             rays=[((0.4, -0.3), (3.9, -1.2))],
             membrane=FIN[2], ray=FIN[3], alpha=190)
ANAL = Fin(anchor=(17.4, -3.18),
           poly=[(-0.5, 0.6), (1.5, 0.6), (2.2, -0.1), (3.0, -0.7), (3.7, -1.3), (2.5, -1.4), (1.1, -1.0),
                 (-0.1, -0.3)],
           rays=[((0.4, -0.3), (3.3, -1.1))],
           membrane=FIN[2], ray=FIN[3], alpha=190)


def _paint_body(img) -> set:
    px = img.load()
    mask = set()
    for j, row in enumerate(BODY_ART):
        for x, ch in enumerate(row):
            if ch != ".":
                px[x, BODY_TOP + j] = rgba(PALETTE[ch])
                mask.add((x, BODY_TOP + j))
    return mask


def _frame(t: float):
    img = canvas(SIZE)
    phase = 2 * math.pi * t
    # Fins in the silhouette plane first; the body covers their roots.
    # (fin, resting angle, sway amplitude, phase lag): the tail flexes up a little at rest.
    for fin, rest, amp, lag in ((TAIL, 2.0, 8.0, 0.0), (DORSAL_FIN, 0.0, 6.0, 1.2), (PELVIC, 0.0, 9.0, 0.6),
                                (ANAL, 0.0, 9.0, 1.8)):
        fin.paint(img, math.radians(rest + amp * math.sin(phase + lag)))
    body = canvas(SIZE)
    mask = _paint_body(body)
    img.alpha_composite(body)
    PECTORAL.paint(img, math.radians(10.0) * math.sin(phase + 2.2))
    # Outline around the whole silhouette: solid on the body, lighter on the fins.
    px = img.load()
    solid = {(x, y) for y in range(SIZE) for x in range(SIZE) if px[x, y][3]}
    edge = rgba(OUTLINE)
    for y in range(SIZE):
        for x in range(SIZE):
            if (x, y) in solid:
                continue
            near = [(x + dx, y + dy) for dx, dy in NEIGHBOURS]
            if any(p in solid for p in near):
                px[x, y] = edge[:3] + ((255,) if any(p in mask for p in near) else (180,))
    # Barbel: a pale whisker hanging under the mouth, fading at its tip.
    px[2, 13] = rgba(MAUVE[3])
    px[2, 14] = rgba(MAUVE[3])[:3] + (200,)
    # Soft glint sliding along the back, head to tail: shine() swept over just the body's
    # box, so the band spends most of the loop on the fish (the eye stays dark).
    x0, y0 = min(x for x, _ in mask), min(y for _, y in mask)
    x1, y1 = max(x for x, _ in mask), max(y for _, y in mask)
    glint = shine(img.crop((x0, y0, x1 + 1, y1 + 1)), t, colour=GLINT, width=3.5, strength=0.35,
                  angle=math.degrees(TILT), pause=0.2).load()
    for (x, y) in mask:
        if _to_fish(x + 0.5, y + 0.5)[1] > 0.0 and BODY_ART[y - BODY_TOP][x] not in "ELy":
            px[x, y] = glint[x - x0, y - y0]
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
