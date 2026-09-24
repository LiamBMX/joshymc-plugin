"""Woodland Hunter: a rustic hunter's crossbow from the Woodland Limited Edition.

A carved walnut stock with a brass butt plate, patch box and trigger guard, a stitched
leather cheek rest and a checkered pistol grip. The nose is carved into a stag's head
whose antlers form the prod, with an amber gem on its brow and glowing amber eyes. A
round buffalo-plaid quiver of pine-green fletched bolts is strapped to the right side,
and red wool silencers sit on the string.

Built in a local frame (+Y along the stock toward the nose, +X the crossbow's right,
+Z its top), then turned 45 degrees into the vanilla crossbow_standby sprite frame.
"""
from __future__ import annotations

import math
import random

import numpy as np

from art import kit
from art.kit import (arc, bar, box, canvas, fill, mirror, mix, model, move, prism, ramp, rgba, save, shade, turn)

ID = "woodland_hunter"
NAME = "Woodland Hunter"
KIND = "crossbow"

T = 32  # every texture is 32 px: 2 texels per model unit with box(uv="true")

WALNUT = ramp("#6a3d25", 6, 0.8)
STAG = ramp("#9a6238", 6, 0.75)
HORN = ramp("#3a2a26", 5, 0.7)
HICKORY = ramp("#caa06a", 6, 0.7)
LEATHER = ramp("#8e5a34", 6, 0.75)
BRASS = ramp("#d2a548", 6, 0.85)
COPPER = ramp("#bd6a3e", 6, 0.8)
PATINA = ("#2f6f66", "#4f9a88")
RED = ramp("#b8262c", 5, 0.6)
PLAID_BLACK = "#1d1618"
PINE = ramp("#2f6e3f", 6, 0.65)
MAPLE = ramp("#cf3b1f", 6, 0.75)
ANTLER = ramp("#bf9c6e", 7, 0.85, 0.035)
LINEN = ramp("#e4d4b0", 5, 0.5)
AMBER = ramp("#f39a22", 6, 0.8)
STEEL = ramp("#8d99a3", 6, 0.8)
THREAD = "#f0e3c0"


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def _put(img, x, y, c) -> None:
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), rgba(c))


def _wood(pal, seed: int, spacing: int = 5, wave: float = 0.12):
    """Clean long grain: wavy dark lines, a lighter band beside each, sparse sheen."""
    rng = random.Random(seed)
    img = canvas(T, fill=pal[2])
    px = img.load()
    for lane in range(T // spacing):
        x = lane * spacing + rng.randint(0, 1)
        for y in range(T):
            if rng.random() < wave:
                x += rng.choice((-1, 1))
            px[x % T, y] = rgba(pal[1] if rng.random() > 0.07 else pal[0])
            if rng.random() < 0.8:
                px[(x + 1) % T, y] = rgba(pal[3])
    for _ in range(12):
        x, y = rng.randrange(T), rng.randrange(T)
        for k in range(rng.randint(2, 4)):
            if px[x, (y + k) % T] == rgba(pal[3]):
                px[x, (y + k) % T] = rgba(pal[4])
    return img


def _checker():
    img = canvas(T)
    for y in range(T):
        for x in range(T):
            a, b = (x + y) % 4, (x - y) % 4
            c = WALNUT[1] if (a == 0 or b == 0) else WALNUT[4] if a == 1 else WALNUT[2] if a == 3 else WALNUT[3]
            _put(img, x, y, c)
    return img


def _hickory(seed: int):
    return _wood(HICKORY, seed, spacing=4, wave=0.08)


def _metal(pal, seed: int, patina=None):
    """Polished metal: diagonal reflection bands, a bright rim, a few sparkles."""
    rng = random.Random(seed)
    img = canvas(T)
    for y in range(T):
        for x in range(T):
            d = (x + y + 2) % 14
            c = pal[4] if d < 2 else pal[3] if d < 7 else pal[2] if d < 12 else pal[3]
            _put(img, x, y, c)
    fill(img, (0, 0, T - 1, 0), pal[5])
    fill(img, (0, 0, 0, T - 1), pal[4])
    for _ in range(9):
        _put(img, rng.randrange(T), rng.randrange(T), pal[5])
    if patina:
        for _ in range(6):
            x, y = rng.randrange(4, T), rng.randrange(4, T)
            _put(img, x, y, patina[0])
            _put(img, x + 1, y, patina[1])
    return img


def _leather(seed: int, pal=LEATHER):
    """Worn leather: soft low-frequency mottling, a few creases and pale scuffs."""
    rng = random.Random(seed)
    img = canvas(T, fill=pal[2])
    ph = [rng.random() * 6.28 for _ in range(3)]
    for y in range(T):
        for x in range(T):
            n = (math.sin(x * 0.45 + ph[0]) + math.sin(y * 0.38 + ph[1]) + math.sin((x + y) * 0.27 + ph[2])) / 3
            n += (rng.random() - 0.5) * 0.35
            _put(img, x, y, pal[1] if n < -0.45 else pal[3] if n > 0.45 else pal[2])
    for _ in range(7):
        x, y = rng.randrange(T), rng.randrange(T)
        for k in range(rng.randint(2, 4)):
            _put(img, (x + k) % T, (y + k // 2) % T, pal[1])
    for _ in range(6):
        x, y = rng.randrange(T), rng.randrange(T)
        _put(img, x, y, pal[4])
        _put(img, (x + 1) % T, y, pal[4])
    return img


def _plaid(check: int = 2):
    mixed = mix(RED[1], PLAID_BLACK, 0.5)
    img = canvas(T)
    for y in range(T):
        for x in range(T):
            rx = (x // check) % 2 == 0
            ry = (y // check) % 2 == 0
            if rx and ry:
                c = RED[3] if (x % check == 0 and y % check == 0) else RED[2]
            elif not rx and not ry:
                c = PLAID_BLACK
            else:
                c = shade(mixed, -0.25) if (x - y) % 3 == 0 else mixed
            _put(img, x, y, c)
    return img


def _antler():
    """Bone: ivory at v=0 (tips) to warm brown at v=16 (base), dithered between bands;
    faint grooves low down and pearling at the very base."""
    img = canvas(T)
    stops = [ANTLER[6], ANTLER[6], ANTLER[5], ANTLER[5], ANTLER[5], ANTLER[4], ANTLER[4], ANTLER[4],
             ANTLER[3], ANTLER[3], ANTLER[2], ANTLER[2]]
    bayer = (0.1, 0.6, 0.35, 0.85)
    for y in range(T):
        f = y / T * (len(stops) - 1)
        i = int(f)
        for x in range(T):
            j = min(len(stops) - 1, i + (1 if (f - i) > bayer[(x + 2 * y) % 4] else 0))
            _put(img, x, y, stops[j])
    rng = random.Random(41)
    px = img.load()
    for x in range(2, T, 4):
        for y in range(14, T):
            if rng.random() < 0.55:
                px[x, y] = rgba(shade(px[x, y], -0.12))
    for _ in range(18):
        x, y = rng.randrange(T), rng.randrange(24, T - 1)
        px[x, y] = rgba(shade(px[x, y], 0.25))
        px[x, y + 1] = rgba(shade(px[x, y + 1], -0.22))
    return img


def _burr():
    rng = random.Random(51)
    img = canvas(T, fill=ANTLER[1])
    for _ in range(60):
        x, y = rng.randrange(T), rng.randrange(T)
        _put(img, x, y, ANTLER[3])
        _put(img, x + 1, y, ANTLER[2])
        _put(img, x, y + 1, ANTLER[0])
    return img


def _string():
    img = canvas(T)
    for y in range(T):
        for x in range(T):
            _put(img, x, y, LINEN[3] if (x + y) % 3 else LINEN[1])
    for y in range(0, 3):
        for x in range(T):
            _put(img, x, y, PINE[3] if y % 2 else PINE[1])
    return img


def _cheek():
    """Leather cheek rest: padded, rolled edge, running stitch, faintly tooled maple leaf.
    u = height (bottom->top, 6 texels), v = length (front->back, 8 texels)."""
    img = _leather(61)
    w, h = 6, 8
    fill(img, (0, 0, w - 1, h - 1), LEATHER[3])
    for x in range(w):
        _put(img, x, 0, LEATHER[2])
        _put(img, x, h - 1, LEATHER[1])
    for y in range(h):
        _put(img, 0, y, LEATHER[2])
        _put(img, w - 1, y, LEATHER[4])
    for y in range(1, h - 1, 2):
        _put(img, 1, y, LINEN[2])
        _put(img, w - 2, y, LINEN[2])
    for x, y in ((3, 2), (2, 3), (3, 3), (4, 3), (3, 4), (2, 5), (3, 5), (4, 5)):
        _put(img, x, y, LEATHER[2])
    _put(img, 3, 6, LEATHER[1])
    return img


def _patchbox():
    """Engraved brass lid with an enamelled maple leaf. u = top->bottom, v = front->back."""
    img = _metal(BRASS, 71)
    w, h = 7, 9
    fill(img, (0, 0, w - 1, h - 1), BRASS[3])
    fill(img, (0, 0, w - 1, 0), BRASS[5])
    fill(img, (0, 0, 0, h - 1), BRASS[4])
    fill(img, (0, h - 1, w - 1, h - 1), BRASS[1])
    fill(img, (w - 1, 0, w - 1, h - 1), BRASS[1])
    leaf = ["..M..", "M.M.M", "MMMMM", ".MMM.", "..M..", "..m.."]
    for dy, row in enumerate(leaf):
        for dx, ch in enumerate(row):
            if ch == "M":
                _put(img, 1 + dx, 1 + dy, MAPLE[3] if dx + dy < 4 else MAPLE[2])
            elif ch == "m":
                _put(img, 1 + dx, 1 + dy, BRASS[1])
    return img


def _fletch(pal):
    img = canvas(T, fill=pal[3])
    for y in range(0, T, 2):
        fill(img, (0, y, T - 1, y), pal[2])
    fill(img, (0, 0, 0, T - 1), pal[5])
    fill(img, (0, 0, T - 1, 0), pal[4])
    return img


def _shaft():
    img = _hickory(81)
    for y, c in ((23, PINE[2]), (24, PINE[2]), (25, THREAD), (26, MAPLE[2]), (27, MAPLE[2]), (28, THREAD)):
        fill(img, (0, y, T - 1, y), c)
    fill(img, (0, 30, T - 1, 31), HORN[1])
    return img


def _broadhead():
    img = canvas(T)
    rows = [".a.", "aba", "abc", "abc", ".bc", ".c."]
    pal = {"a": STEEL[5], "b": STEEL[3], "c": STEEL[1]}
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                _put(img, x, y, pal[ch])
    return img


def _rocket():
    img = canvas(T, fill=MAPLE[2])
    fill(img, (0, 0, 0, T - 1), MAPLE[4])
    for y in (3, 4, 16, 17):
        fill(img, (0, y, T - 1, y), THREAD)
    for y in range(T):
        _put(img, (y // 2) % 4 + 1, y, MAPLE[1])
    return img


def _amber():
    img = canvas(T, fill=AMBER[2])
    for y, row in enumerate([[AMBER[5], AMBER[4]], [AMBER[3], AMBER[1]]]):
        for x, c in enumerate(row):
            _put(img, x, y, c)
    return img


def _fur(pal, seed: int, blaze: bool = False):
    """Carved fur: staggered gouges with a lit crest; optional pale blaze down columns 3-4."""
    rng = random.Random(seed)
    img = canvas(T, fill=pal[3])
    for row, y in enumerate(range(0, T, 3)):
        for x in range((row % 2) * 2, T, 4):
            jx = x + rng.randint(0, 1)
            _put(img, jx, y, pal[4])
            _put(img, jx, y + 1, pal[2])
            _put(img, jx + 1, y + 1, pal[1])
            _put(img, jx + 1, y + 2, pal[2])
    if blaze:
        for y in range(0, 11):
            _put(img, 3, y, pal[4] if y % 3 else pal[5])
            _put(img, 4, y, pal[5] if y % 3 else pal[4])
        for x in (1, 2, 5, 6):                      # shadowed crown between the antler bases
            for y in (3, 4):
                _put(img, x, y, pal[1])
    return img


def _foreside():
    """Fore-end flank: walnut with a carved groove near the top and domed brass tacks.
    u = height (bottom->top, 5 texels), v = length (front->back, 12 texels)."""
    img = _wood(WALNUT, 17)
    for y in range(T):
        _put(img, 3, y, WALNUT[0])
        _put(img, 4, y, WALNUT[4])
    for y in (2, 10):
        _put(img, 1, y, BRASS[5])
        _put(img, 1, y + 1, BRASS[2])
    return img


def _lockplate():
    """Engraved lock plate: bevelled rim, two screws, a scroll. u = top->bottom (4), v = front->back (6)."""
    img = _metal(BRASS, 13)
    rows = ["aaaa", "asca", "aecb", "acea", "asca", "bbbb"]
    pal = {"a": BRASS[4], "b": BRASS[1], "c": BRASS[3], "e": BRASS[1], "s": BRASS[5]}
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            _put(img, x, y, pal[ch])
    _put(img, 0, 0, BRASS[5])
    return img


def _wrist_top():
    """Wrist top: walnut with a brass diamond thumb-piece. u = x (5), v = front->back (7)."""
    img = _wood(WALNUT, 19)
    rows = ["..a..", ".aca.", "acbca", ".aba.", "..a.."]
    pal = {"a": BRASS[3], "b": BRASS[2], "c": BRASS[5]}
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                _put(img, x, y + 1, pal[ch])
    return img


def _stag_face():
    """Atlas for the carved stag's face (texel regions, 2 per unit):
      (0,0)  muzzle top 5x4: cream band at the front, blaze behind
      (8,0)  muzzle side 5x4 (u = height from the bottom): cream band, mouth line
      (16,0) muzzle front 5x5 (v = height from the bottom): cream chin under the nose pad
      (0,8)  skull side 6x5 (u = height, v = front->back): cream eye ring
      (8,8)  ear front 5x3 (u = root->tip, v = bottom->top): pale rim, dark hollow
      (16,8) chin / throat: cream"""
    img = _fur(STAG, 37)
    cream, cream2 = "#efe0c4", "#d8c4a0"
    for x in range(5):
        for y in range(4):
            _put(img, x, y, cream if y < 2 else (STAG[4] if x == 2 else img.getpixel((x, y))))
        _put(img, x, 1, cream2)
    for x in range(8, 13):
        for y in range(4):
            if y < 2:
                _put(img, x, y, cream if y == 0 else cream2)
        _put(img, x, 3, img.getpixel((x, 3)))
    for y in range(4):
        _put(img, 9, y, STAG[1] if y >= 2 else cream2)
    for x in range(16, 21):
        for y in range(5):
            _put(img, x, y, cream if y < 2 else STAG[2])
    for x in range(0, 6):
        for y in range(8, 13):
            if 2 <= x <= 4 and 8 <= y <= 10 and (x, y) != (3, 9):
                _put(img, x, y, cream)
    rows = ["aaaa.", "abbba", "aaaa."]
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            _put(img, 8 + x, 8 + y, (0, 0, 0, 0) if ch == "." else STAG[4] if ch == "a" else STAG[1])
    fill(img, (16, 8, 23, 15), cream2)
    fill(img, (16, 8, 23, 8), cream)
    return img


def _belly():
    """Butt belly flank: walnut relief-carved with an oak vine low down (lit from above).
    u = height (bottom->top, 6 texels), v = length (wrist->toe, 12 texels)."""
    img = _wood(WALNUT, 23)
    for v in range(12):
        u = 2 + round(0.9 * math.sin(v * 0.8 + 0.4))
        _put(img, u - 1, v, WALNUT[0])
        _put(img, u, v, WALNUT[1])
        _put(img, u + 1, v, WALNUT[4])
    for v, u in ((2, 4), (6, 0), (9, 4)):          # oak leaves off the vine
        _put(img, u, v, WALNUT[4])
        _put(img, u, v + 1, WALNUT[3])
        _put(img, u - 1 if u else u + 1, v + 1, WALNUT[0])
    return img


def _wool():
    """Red wool string silencer: a fluffy knot of maple-red yarn."""
    img = canvas(T, fill=MAPLE[2])
    rng = random.Random(97)
    for _ in range(300):
        _put(img, rng.randrange(T), rng.randrange(T), rng.choice([MAPLE[1], MAPLE[3], MAPLE[3], MAPLE[4]]))
    return img


def textures() -> None:
    save(_wood(WALNUT, 11), "walnut")
    save(_fur(STAG, 31), "stag")
    save(_fur(STAG, 33, blaze=True), "stag_top")
    save(_foreside(), "foreside")
    save(_lockplate(), "lockplate")
    save(_wrist_top(), "wrist_top")
    save(_checker(), "checker")
    horn = canvas(T, fill=HORN[1])
    fill(horn, (0, 0, T - 1, 0), HORN[3])
    fill(horn, (0, 0, 0, T - 1), HORN[2])
    for x, y in ((1, 1), (2, 1), (1, 2)):          # wet glint on the nose pad
        _put(horn, x, y, HORN[4])
    save(horn, "horn")
    save(_hickory(21), "hickory")
    deck = _hickory(22)
    for x, c in enumerate((HICKORY[5], HICKORY[1], HICKORY[0], HICKORY[4])):
        fill(deck, (x, 0, x, T - 1), c)
    save(deck, "deck")
    save(_metal(BRASS, 3), "brass")
    save(_metal(COPPER, 5, PATINA), "copper")
    save(_patchbox(), "patchbox")
    save(_leather(63), "leather")
    save(_cheek(), "cheek")
    save(_plaid(), "plaid")
    save(_antler(), "antler")
    save(_burr(), "burr")
    save(_string(), "string")
    save(_fletch(PINE), "fletch")
    save(_fletch(MAPLE), "fletch_red")
    save(_shaft(), "shaft")
    save(_broadhead(), "broadhead")
    save(_rocket(), "rocket")
    save(_amber(), "amber")
    save(_stag_face(), "stag_face")
    save(_belly(), "belly")
    save(_wool(), "wool")


# --------------------------------------------------------------------------------------
# Geometry (local frame: +Y toward the nose, +X the crossbow's right, +Z its top)
# --------------------------------------------------------------------------------------

S2 = math.sqrt(0.5)
DECK = 1.3                        # top of the hickory flight rail
STRING_Z = DECK + 0.2             # the string rides just above the deck
NUT_Y = 3.0                       # the latch the drawn string hooks onto
LOCAL_FIST = (0.0, -1.1, -2.7)    # where the shooting hand closes on the pistol grip
SIZE = 1.0
GUI_ROTATION = (-40, 0, 0)       # tilted so the rack stands up in the slot
CARRY_TILT = 38.0                 # idle carry, swung forward from the vanilla hang
ANTLER_BASE = (1.25, 12.0, 1.5)
# Main beam: out from the skull, sweeping back like a recurve limb; the string ties on at
# BEAM[NOCK] and the beam tip hooks up past it.
BEAM = [(1.3, 12.0, 1.6), (3.3, 12.75, 2.45), (5.7, 12.6, 2.9), (7.9, 11.3, 2.75), (9.25, 9.1, 2.1),
        (9.55, 7.7, 2.55), (9.3, 6.9, 3.3)]
BEAM_W = [2.1, 1.85, 1.6, 1.32, 1.0, 0.68]
NOCK = 4
TINES = [  # (points, widths): a short brow tine reaching forward, then G2-G4 rising, curling in
    ([(2.35, 12.7, 2.25), (2.5, 14.1, 3.0), (2.1, 15.1, 3.7)], [1.2, 0.72]),
    ([(4.4, 12.8, 2.8), (4.8, 13.3, 4.6), (4.8, 13.9, 6.4), (4.35, 14.5, 7.7)], [1.3, 0.95, 0.55]),
    ([(6.75, 12.1, 2.95), (7.25, 12.4, 4.6), (7.3, 12.85, 6.0), (6.9, 13.35, 7.0)], [1.22, 0.88, 0.52]),
    ([(8.6, 10.45, 2.6), (9.05, 10.65, 3.9), (8.9, 11.05, 4.9)], [1.06, 0.64]),
]


def chain(points, widths, tex, depth: float = 1.0, v_end: float = 16.0, u: float = 0.0, **kw):
    """Tapered bars through points; UVs climb the texture from v_end (base) toward 0 (tip)."""
    parts = []
    d = 0.0
    for i, (a, b) in enumerate(zip(points, points[1:])):
        a, b = np.asarray(a, float), np.asarray(b, float)
        length = float(np.linalg.norm(b - a))
        n = (b - a) / length
        w = widths[i]
        a2 = a - n * (w * 0.3 if i else 0.0)
        b2 = b + n * (w * 0.15)
        v0 = max(0.0, v_end - d - length)
        parts.append(bar(a2, b2, w, w * depth, tex, offset=(u, v0), **kw))
        d += length
    return parts


def stock():
    p = []
    p.append(box((-1.6, -8.0, -4.8), (1.6, -7.4, 1.8), "brass", offset=(1, 0)))                # butt plate
    p.append(box((-1.45, -7.6, 1.5), (1.45, -6.7, 1.85), "brass", offset=(4, 2)))              # heel wrap
    toe = box((-1.45, -7.6, -4.9), (1.45, -6.4, -4.5), "brass", offset=(4, 5))
    p.append(turn(toe, 20.6, "x", (0, -7.5, -4.7)))                                             # toe wrap
    p.append(box((-1.5, -7.5, -2.25), (1.5, -2.0, 1.5), "walnut"))                              # butt stock
    p.append(box((-1.05, -7.45, 1.45), (1.05, -3.0, 1.75), "walnut", offset=(2, 4)))           # comb
    blen = float(np.hypot(5.7, 2.15))
    p.append(bar((0, -8.0, -3.4), (0, -2.3, -1.25), 2.9, 2.8, "walnut", offset=(6, 1),
                 faces={"west": ("belly", [0, 0, 2.8, blen]), "east": ("belly", [2.8, 0, 0, blen])}))  # belly
    p.append(box((-1.25, -2.5, -1.5), (1.25, 1.0, 1.25), "checker",
                 faces={"south": ("wrist_top", [0, 0, 2.5, 3.5])}))                              # wrist
    p.append(bar((0, -0.3, -1.0), (0, -1.9, -5.0), 2.1, 2.5, "checker", offset=(3, 2)))         # pistol grip
    p.append(bar((0, -1.86, -4.85), (0, -2.1, -5.5), 2.25, 2.7, "brass"))                       # grip cap
    p.append(box((-1.35, 0.75, -1.5), (1.35, 4.5, 1.25), "walnut", offset=(8, 3)))              # action
    p.append(box((-1.25, 4.5, -1.5), (1.25, 10.5, 1.0), "walnut",
                 faces={"west": ("foreside", [0, 0, 2.5, 6.0]), "east": ("foreside", [2.5, 0, 0, 6.0])}))
    p.append(box((-0.95, 4.6, -1.9), (0.95, 10.35, -1.4), "walnut", offset=(5, 2)))           # fore-end belly
    p.append(box((-1.0, 0.7, 0.9), (1.0, 11.0, DECK), "hickory", faces={"south": "deck"}))      # flight rail
    plate = box((1.35, 1.1, -1.15), (1.55, 4.1, 0.85), "brass",
                faces={"east": ("lockplate", [0, 0, 2.0, 3.0])})                                # lock plates
    p += [plate] + mirror(plate, "x", 0)
    p += stagger_caps(prism((0, NUT_Y, 0.95), 0.75, 2.4, "copper", axis="x"))                   # the nut
    notch = box((0.2, 0.15, 1.25), (0.5, 0.6, 2.25), "brass")
    p += [notch] + mirror(notch, "x", 0)                                                        # rear sight
    p += arc((0, 1.0, -1.55), 1.8, 70, 255, 6, 0.45, 0.45, "brass", plane="zy")                 # trigger guard
    p.append(bar((0, 1.2, -1.4), (0, 0.75, -2.8), 0.4, 0.55, "brass"))                          # trigger
    p.append(box((-2.05, -6.8, -1.7), (-1.5, -2.9, 1.3), "leather",
                 faces={"west": ("cheek", [0, 0, 3.0, 3.9])}))                                   # cheek rest
    p.append(box((1.5, -6.9, -2.3), (1.7, -2.4, 1.2), "brass",
                 faces={"east": ("patchbox", [0, 0, 3.5, 4.5])}))                                # patch box
    return p


def head():
    """A carved stag's head at the nose: the antlers grow from its crown."""
    p = []
    p.append(box((-1.5, 10.3, -1.65), (1.5, 10.95, 1.2), "brass", offset=(2, 0)))               # collar
    side = [0, 4.0, 2.95, 6.5]                                                                  # eye ring
    p.append(box((-1.8, 10.9, -1.9), (1.8, 13.4, 1.05), "stag",
                 faces={"south": ("stag_top", [0, 0, 3.6, 2.5]), "west": ("stag_face", side),
                        "east": ("stag_face", [side[2], side[1], side[0], side[3]])}))         # skull
    brow = box((1.3, 12.25, 0.0), (2.02, 13.35, 0.95), "stag", offset=(6, 2))
    p += [brow] + mirror(brow, "x", 0)                                                          # brow ridges
    mside = [4.0, 0, 6.35, 2.4]
    muzzle = box((-1.1, 13.2, -1.75), (1.1, 15.6, 0.6), "stag",
                 faces={"south": ("stag_face", [0.2, 0, 2.4, 2.4]), "west": ("stag_face", mside),
                        "east": ("stag_face", [mside[2], mside[1], mside[0], mside[3]]),
                        "up": ("stag_face", [8.0, 0, 10.2, 2.35]), "north": ("stag_face", [8, 4, 10.2, 6.4])})
    jaw = box((-0.9, 13.1, -2.15), (0.9, 15.1, -1.5), "stag_face", uv={s_: [8, 4, 10, 6] for s_ in kit.SIDES})
    nose = box((-0.72, 15.45, -0.7), (0.72, 15.95, 0.5), "horn")
    p += turn([muzzle, jaw, nose], -12, "x", (0, 13.2, 0))
    ear = box((1.75, 11.0, -1.05), (4.05, 11.42, 0.25), "stag",
              faces={"up": ("stag_face", [4.0, 4.0, 6.3, 5.3])})                            # ear, hollow forward
    turn(ear, 8, "y", (1.75, 11.2, -0.4))
    turn(ear, -30, "z", (1.75, 11.2, -0.4))
    p += [ear] + mirror(ear, "x", 0)
    eye = box((1.8, 12.4, -0.6), (1.97, 13.1, 0.05), "amber", glow=11, uv={s_: [0, 0, 1, 1] for s_ in kit.SIDES})
    p += [eye] + mirror(eye, "x", 0)
    p.append(box((-0.42, 12.7, 0.8), (0.42, 13.55, 1.25), "amber", glow=13,
                 uv={s_: [0, 0, 1, 1] for s_ in kit.SIDES}))                                     # forehead gem
    return p


def _rot_z(pt, about, degrees):
    a = math.radians(degrees)
    x, y = pt[0] - about[0], pt[1] - about[1]
    return (about[0] + x * math.cos(a) - y * math.sin(a), about[1] + x * math.sin(a) + y * math.cos(a), pt[2])


def antler(flex: float):
    """The right antler (+X). flex (degrees) swings it back as the string is drawn."""
    p = stagger_caps(prism((1.4, 12.0, 1.6), 1.12, 0.8, "burr", axis="x"))
    p += chain(BEAM, BEAM_W, "antler")
    for pts, widths in TINES:
        p += chain(pts, widths, "antler", v_end=11.0, u=5.0)
    turn(p, -flex, "z", ANTLER_BASE)
    return p


def string(flex: float, center_y: float | None):
    """The bowstring from nock to nock through the centre point, with red wool silencers."""
    tip = _rot_z(BEAM[NOCK], ANTLER_BASE, -flex)
    if center_y is None:
        center_y = tip[1]
    c = np.array((0.0, center_y, STRING_Z))
    parts = []
    for t in (np.array(tip), np.array((-tip[0], tip[1], tip[2]))):
        parts.append(bar(t, c, 0.3, 0.3, "string"))
        q = t + (c - t) * 0.16
        puff = box(q - 0.42, q + 0.42, "wool")
        parts.append(turn(puff, x=35, y=45, z=20, origin=tuple(q)))
    return parts


def stagger_caps(slabs, step: float = 0.012):
    """kit.prism() gives every slab an end cap on the same plane; shorten each slab a
    hair more than the last so the overlapping caps never z-fight where an end shows."""
    for i, slab in enumerate(slabs):
        slab["from"][1] = round(slab["from"][1] + i * step, 4)
        slab["to"][1] = round(slab["to"][1] - i * step, 4)
    return slabs


# Facets of kit.prism(axis="y") in angular order around the tube: (slab index, face).
_RING = [(0, "east"), (3, "south"), (1, "south"), (2, "west"), (0, "west"), (3, "north"), (1, "north"), (2, "east")]


def tube(cx: float, cz: float, radius: float, y0: float, y1: float, tex: str, cap: str | None = None):
    """An 8-sided tube along Y. Each facet takes the next strip of the texture, so a
    pattern such as the buffalo check wraps around it instead of repeating per facet."""
    slabs = prism((cx, (y0 + y1) / 2, cz), radius, y1 - y0, tex, axis="y", cap=cap)
    w = 2 * radius * math.tan(math.pi / 8)
    length = min(16.0, y1 - y0)
    for k, (i, side) in enumerate(_RING):
        u = round((k * w) % (16 - w), 4)
        slabs[i]["faces"][side]["uv"] = [u, 0.0, round(u + w, 4), round(length, 4)]
    return stagger_caps(slabs)


def quiver():
    p = []
    cx, cz = 2.85, -0.35
    p += tube(cx, cz, 1.25, 4.9, 9.4, "plaid")                                                  # wool-wrapped body
    p += tube(cx, cz, 1.4, 4.3, 5.05, "leather", cap="leather")                                 # mouth rim
    p += tube(cx, cz, 1.4, 9.25, 10.25, "leather", cap="leather")                               # end cap
    for y in (5.8, 8.2):
        p += tube(cx, cz, 1.34, y, y + 0.55, "leather")                                         # strap round it
        p.append(box((-1.4, y, -1.55), (cx - 0.9, y + 0.55, 0.98), "leather", offset=(5, 1)))  # ...and the stock
        p.append(box((cx + 1.3, y - 0.1, cz - 0.42), (cx + 1.52, y + 0.65, cz + 0.42), "brass"))  # buckle
    for dx, dz, back in ((-0.55, 0.5, 1.3), (0.55, 0.4, 0.9), (0.0, -0.55, 1.7)):
        x, z = cx + dx, cz + dz
        tail = np.array((x + dx * 0.6, back, z + dz * 0.6))
        tip = np.array((x, 5.4, z))
        n = (tip - tail) / np.linalg.norm(tip - tail)
        p.append(bar(tail, tip, 0.45, 0.45, "shaft", offset=(0, 16 - float(np.linalg.norm(tip - tail)))))
        f0, f1 = tail + n * 0.35, tail + n * 2.7
        p.append(bar(f0, f1, 0.12, 1.3, "fletch"))
        p.append(bar(f0, f1, 1.3, 0.12, "fletch"))
    return p


def bolt():
    p = []
    y0, y1 = NUT_Y - 0.2, 14.2
    p.append(box((-0.28, y0, DECK), (0.28, y1, DECK + 0.56), "shaft", offset=(0, 16 - (y1 - y0))))
    p.append(box((-0.07, y0 + 0.4, DECK + 0.5), (0.07, y0 + 3.0, DECK + 1.7), "fletch_red"))
    vane = box((0.2, y0 + 0.4, DECK + 0.21), (1.35, y0 + 3.0, DECK + 0.35), "fletch")
    turn(vane, -25, "y", (0.2, y0, DECK + 0.28))
    p += [vane] + mirror(vane, "x", 0)
    p.append(box((-0.75, y1 - 0.2, DECK + 0.21), (0.75, y1 + 2.8, DECK + 0.35), "broadhead"))
    p.append(box((-0.07, y1 - 0.2, DECK - 0.47), (0.07, y1 + 2.8, DECK + 1.03), "broadhead"))
    p.append(box((-0.36, y1 - 0.55, DECK - 0.08), (0.36, y1 + 0.1, DECK + 0.64), "copper"))
    return p


def rocket():
    p = []
    p.append(box((-0.62, 3.5, DECK), (0.62, 10.6, DECK + 1.24), "rocket"))
    p.append(box((-0.47, 10.6, DECK + 0.15), (0.47, 11.7, DECK + 1.09), "fletch"))
    p.append(box((-0.26, 11.7, DECK + 0.36), (0.26, 12.3, DECK + 0.88), "fletch"))
    p.append(bar((0, 3.6, DECK + 0.62), (0, 2.4, DECK + 1.1), 0.2, 0.2, "horn"))
    return p


def _fist_point(size: float):
    rot, tr, sc = kit.VANILLA["crossbow"]["thirdperson_righthand"]
    m = np.array(kit.display_matrix(rot))
    return (m.T @ (np.array(kit.FIST) - np.array(tr) / 16) / (sc * size) + 0.5) * 16


def to_model(parts):
    fx, fy, fz = _fist_point(SIZE)
    lx, ly, lz = LOCAL_FIST
    turn(parts, 45, "z", (0, 0, 0))
    move(parts, fx - (lx - ly) * S2, fy - (lx + ly) * S2, fz - lz)
    return parts


def crossbow(flex: float = 0.0, center_y: float | None = None, load: str | None = None):
    r = antler(flex)
    parts = stock() + head() + quiver() + r + mirror(r, "x", 0) + string(flex, center_y)
    if load == "arrow":
        parts += bolt()
    elif load == "firework":
        parts += rocket()
    return to_model(parts)


def held_forward(size: float, tilt: float) -> dict:
    """The vanilla third-person crossbow transform swung forward by `tilt` degrees about
    the fist, so the long rack clears the ground while it is carried at the side."""
    rot, tr, sc = kit.VANILLA["crossbow"]["thirdperson_righthand"]
    c, sn = math.cos(math.radians(tilt)), math.sin(math.radians(tilt))
    m = np.array(((1, 0, 0), (0, c, -sn), (0, sn, c))) @ np.array(kit.display_matrix(rot))
    s = sc * size
    grip = _fist_point(size) / 16 - 0.5
    t = (np.array(kit.FIST) - m @ (grip * s)) * 16
    return {"rotation": kit.display_euler(m), "translation": [round(float(v), 3) for v in t],
            "scale": [round(s, 4)] * 3}


def models() -> dict:
    states = {
        "idle": crossbow(0.0),
        "pull_0": crossbow(3.0, 7.0),
        "pull_1": crossbow(5.5, 5.2),
        "pull_2": crossbow(8.0, NUT_Y),
        "arrow": crossbow(8.0, NUT_Y, "arrow"),
        "firework": crossbow(8.0, NUT_Y, "firework"),
    }
    # One display for every state (fitted to the idle crossbow plus the loaded bolt) so
    # the icon and the grip never jump between states.
    disp = kit.display("crossbow", states["idle"] + states["arrow"], size=SIZE, gui_rotation=GUI_ROTATION)
    idle = dict(disp, thirdperson_righthand=held_forward(SIZE, CARRY_TILT))
    return {name: model(parts, idle if name == "idle" else disp) for name, parts in states.items()}
