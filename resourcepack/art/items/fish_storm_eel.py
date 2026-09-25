"""Storm Eel: a rare eel of the JoshyMC fishing collection (ocean, deep ocean).

A flat 32x32 sprite in the collection pose (side view, head up-left, tail down-right),
keeping the old sprite's deep indigo body, its bright periwinkle lateral stripe and white
eye. A long, slender eel on an S-curve: a lit rim over a dark indigo back, the lateral
stripe painted as a barbed lightning line along the flank, a countershaded lavender belly,
a blunt head with a long mouth line, a small eye above it and a gill slit, a rounded
pectoral fin behind the gill, and one continuous translucent electric-blue fin that starts
behind the head, runs along the back, wraps the pointed tail and returns under the belly
as the anal fin.

Animation (RARE, 12 frames x 3 ticks): the eel swims in place, a wave travelling down its
body so the tail sways about a pixel; a soft glint slides along the back (frames 1-5),
then a blue electric sheen rolls down the body and charges the lightning stripe white as
it passes (frames 6-11), while three pale-blue sparks twinkle around the fish on
staggered phases.
"""
from __future__ import annotations

import math

import numpy as np

from art.kit import animate, canvas, mix, rgba, save_animation, sparkle, sprite, wave

ID = "fish_storm_eel"
NAME = "Storm Eel"
KIND = "item"
MODEL_KEY = "fish/storm_eel"
COUNTERPART = "item/cod"

SIZE = 32
FRAMES = 12
FRAMETIME = 3

# ---- palette (hue-shifted: shadows lean violet, lights lean cyan) ----------------------------
OUTLINE = "#130f46"
FIN_OUTLINE = ("#1b2f78", 228)
COLOURS = {
    # back, darkest -> lightest (the old sprite's indigo)
    "d": "#28227e", "b": "#32329a", "B": "#3e46b4", "r": "#5462cc", "R": "#7588e2",
    # belly (countershaded lavender)
    "x": "#5b58bc", "u": "#8386dc", "w": "#a0a8ec",
    # the lightning stripe (the old sprite's periwinkle, electrified)
    "z": "#9fd6ff", "Z": "#effdff",
    # head: mouth line, pale lower jaw, gill slit, eye and catchlight
    "m": "#150f4c", "j": "#8f98e4", "g": "#191456", "E": "#090724", "e": "#ffffff",
}
FIN = {  # letter -> (colour, alpha): lit edge, membrane, ray, lower edge, pectoral
    "k": ("#9fe8ff", 212), "f": ("#4aa0ea", 188), "F": ("#2e66c4", 222), "n": ("#3f86d6", 206),
    "q": ("#b0d6ff", 220), "Q": ("#3d76d0", 226),
}
PECT_TINT = "#a8ccff"
GLINT = "#eef7ff"
SHEEN = "#8fc6ff"
CHARGE = "#fbffff"
SPARK = "#dcf2ff"

# ---- geometry ------------------------------------------------------------------------------
# The eel lies on a spine: a diagonal (snout up-left) with a gentle S bend, plus the swimming
# wave. Every pixel gets (u, v): u is the distance along the spine from the snout, v the
# signed distance from it (positive toward the back).
THETA = math.radians(37.0)
BEND = 1.6
PERIOD = 34.0
AX = np.array([math.cos(THETA), math.sin(THETA)])
NRM = np.array([math.sin(THETA), -math.cos(THETA)])
SNOUT = np.array([3.1, 6.3])
L = 31.0
SS = 4
_KN = np.array([0, .03, .07, .12, .17, .24, .30, .5, .7, .85, .95, 1.0]) * L
_TOP = [1.0, 1.75, 2.35, 2.75, 2.85, 2.4, 2.15, 2.1, 1.8, 1.2, 0.6, 0.2]
_BOT = [0.9, 1.7, 2.4, 2.95, 3.05, 2.55, 2.3, 2.2, 1.9, 1.25, 0.65, 0.2]
_FD = ([0.3 * L, 0.36 * L, 0.8 * L, 0.9 * L, L], [0, 1.0, 1.1, 1.3, 1.0])
_FA = ([0.45 * L, 0.5 * L, 0.8 * L, 0.9 * L, L], [0, 1.0, 1.1, 1.1, 0.6])
TIP = 3.0          # the fin carries on past the body end into a point


def _spine(s, t):
    s = np.asarray(s, float)
    base = BEND * np.sin(2 * np.pi * (s - 1.5) / PERIOD)
    amp = np.clip((s - 8.0) / 23.0, 0.0, 1.0) ** 1.2 * 0.5
    return base + amp * np.sin(2 * np.pi * (t - s / 18.0))


def _coords(t):
    s = np.linspace(-3.0, L + 6.0, 320)
    off = _spine(s, t)
    pts = SNOUT[None, :] + s[:, None] * AX[None, :] + off[:, None] * NRM[None, :]
    tan = np.gradient(pts, axis=0)
    tan /= np.linalg.norm(tan, axis=1)[:, None]
    nrm = np.stack([tan[:, 1], -tan[:, 0]], axis=1)

    def project(xs, ys):
        p = np.stack([xs.ravel(), ys.ravel()], axis=1)
        i = ((p[:, None, :] - pts[None, :, :]) ** 2).sum(axis=2).argmin(axis=1)
        rel = p - pts[i]
        return ((s[i] + (rel * tan[i]).sum(axis=1)).reshape(xs.shape),
                (rel * nrm[i]).sum(axis=1).reshape(xs.shape))

    ys, xs = np.mgrid[0:SIZE * SS, 0:SIZE * SS]
    U, V = project((xs + 0.5) / SS, (ys + 0.5) / SS)
    py, px = np.mgrid[0:SIZE, 0:SIZE]
    UC, VC = project(px + 0.5, py + 0.5)
    return U, V, UC, VC


def _cov(mask):
    return mask.reshape(SIZE, SS, SIZE, SS).mean(axis=(1, 3))


def _shapes(t):
    """Body mask, fin mask and pixel-centre (u, v) at phase t."""
    U, V, UC, VC = _coords(t)
    Uc = np.clip(U, 0, L)
    top, bot = np.interp(Uc, _KN, _TOP), np.interp(Uc, _KN, _BOT)
    body = _cov((U >= 0) & (U <= L) & (V <= top) & (V >= -bot)) >= 0.5
    taper = np.clip(1.0 - (U - L) / TIP, 0.0, 1.0)
    fd, fa = np.interp(Uc, *_FD), np.interp(Uc, *_FA)
    fin = (U >= 0) & (U <= L + TIP) & (V <= (top + fd) * taper) & (V >= -(bot + fa) * taper)
    fin = (_cov(fin) >= 0.45) & ~body
    return body, fin, UC, VC


def _bands(mask):
    """Steps in from the back (kb) and from the belly (kv) for every body pixel."""
    cols = {x: np.nonzero(mask[:, x])[0] for x in range(SIZE) if mask[:, x].any()}
    rows = {y: np.nonzero(mask[y, :])[0] for y in range(SIZE) if mask[y, :].any()}
    kb = np.full(mask.shape, -1)
    kv = np.full(mask.shape, -1)
    for y, xs in rows.items():
        for x in xs:
            kb[y, x] = min(y - cols[x][0], xs[-1] - x)
            kv[y, x] = min(cols[x][-1] - y, x - xs[0])
    return kb, kv


STRIPE_FROM, STRIPE_TO = 7.5, 27.0
BARBS = (11.5, 16.0, 20.5)      # where the stripe spikes a pixel toward the back

# Head: hand-placed pixels, rows of (x, y, letters) running right. The crown, a 2x2 eye with
# its catchlight, the long mouth line sloping back over a pale lower jaw, the throat and
# the gill slit the lightning stripe starts from.
HEAD_ROWS = [
    (4, 5, "RRRR"),
    (3, 6, "RBeEBRR"),
    (2, 7, "RBbEEbBBR"),
    (2, 8, "mmmBBbbgB"),
    (2, 9, "jjjmmddg"),
    (3, 10, "xjjwwu"),
    (5, 11, "xx"),
]
HEAD = {(x + i, y): ch for x, y, row in HEAD_ROWS for i, ch in enumerate(row)}
# Pectoral fin: pixels over the body (tinted) and two poses of the part hanging below it
# ("q" membrane, "Q" its dark trailing edge).
PECT_OVER = [(9, 10), (9, 11), (10, 11)]
PECT_POSES = {"spread": {(8, 12): "q", (9, 12): "q", (9, 13): "Q", (10, 13): "Q"},
              "folded": {(9, 12): "q", (10, 13): "Q"}}


def _letters(t):
    """(x, y) -> letter for every pixel of the eel at phase t, plus pixel-centre u."""
    body, fin, UC, VC = _shapes(t)
    kb, kv = _bands(body)
    out: dict = {}
    for y in range(SIZE):
        for x in range(SIZE):
            u = float(UC[y, x])
            if body[y, x]:
                b, v = int(kb[y, x]), int(kv[y, x])
                stripe = STRIPE_FROM <= u <= STRIPE_TO
                if b == 0:
                    ch = "R" if u < 7 else "r"
                elif v == 0:
                    ch = "x"
                elif b == 2 and stripe:
                    ch = "z"
                elif b == 1:
                    ch = "B"
                elif v == 1:
                    ch = "w" if u < 7 else "u"
                elif b == 2:
                    ch = "b"
                else:
                    ch = "d"
                out[(x, y)] = ch
            elif fin[y, x]:
                v = float(VC[y, x])
                open_ = [not (0 <= x + dx < SIZE and 0 <= y + dy < SIZE) or
                         not (body[y + dy, x + dx] or fin[y + dy, x + dx])
                         for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
                if v > 0 and (open_[1] or open_[3]):
                    ch = "k"
                elif v < 0 and any(open_):
                    ch = "n"
                elif int(u / 1.6) % 2 == 0:
                    ch = "F"
                else:
                    ch = "f"
                out[(x, y)] = ch
    for bu in BARBS:        # the kb == 1 pixel nearest each barb becomes a bright spike
        cand = [(abs(float(UC[y, x]) - bu), x, y) for (x, y), ch in out.items() if ch == "B" and kb[y, x] == 1]
        if cand:
            _, x, y = min(cand)
            out[(x, y)] = "Z"
    out.update(HEAD)
    for y in range(1, SIZE - 1):     # close pinholes the swim wave opens in the thin fin
        for x in range(1, SIZE - 1):
            if (x, y) not in out and sum((x + dx, y + dy) in out
                                         for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))) >= 3:
                out[(x, y)] = "n" if float(VC[y, x]) < 0 else "k"
    pose = "spread" if math.sin(2 * math.pi * (t + 0.1)) > -0.3 else "folded"
    for p, ch in PECT_POSES[pose].items():
        out.setdefault(p, ch)
    return out, UC, kb


def _paint(layer: dict):
    img = canvas(SIZE)
    px = img.load()
    zones = {}
    for (x, y), ch in layer.items():
        if ch in COLOURS:
            colour = COLOURS[ch]
            zone = "bolt" if ch in "zZ" else "back" if ch in "RrBbd" else "head" if ch in "mjgEe" else "belly"
            if (x, y) in PECT_OVER:
                colour, zone = mix(colour, PECT_TINT, 0.5), "pect"
            px[x, y] = rgba(colour)
            zones[(x, y)] = zone
        else:
            colour, alpha = FIN[ch]
            c = rgba(colour)
            px[x, y] = (c[0], c[1], c[2], alpha)
            zones[(x, y)] = "fin"
    return img, zones


def _outline(img, zones):
    """1 px outline: dark indigo beside the body, a softer blue where it only meets fin."""
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
            if any(zones.get(p) not in (None, "fin") for p in near):
                dst[x, y] = rgba(OUTLINE)
            else:
                dst[x, y] = (fr, fg, fb, FIN_OUTLINE[1])
    return out


SPARKS = [((22, 7), 0.0), ((7, 21), 0.36), ((28, 15), 0.68)]


def _frame(t: float):
    letters, UC, kb = _letters(t)
    img, zones = _paint(letters)
    px = img.load()
    step = round(t * FRAMES)
    if 1 <= step <= 5:                     # a soft glint slides along the back
        centre = 1.0 + 28.0 * (step - 1) / 4.0
        for (x, y), z in zones.items():
            if z not in ("back", "head") or kb[y, x] > 1:
                continue
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.4) * 0.55
            if k > 0:
                px[x, y] = rgba(mix(px[x, y], GLINT, k))
    elif step >= 6:                        # electric sheen charges the stripe head to tail
        centre = 5.0 + 23.0 * (step - 6) / 5.0
        for (x, y), z in zones.items():
            k = max(0.0, 1.0 - abs(UC[y, x] - centre) / 3.6)
            if k <= 0 or z == "head":
                continue
            if z == "bolt":
                px[x, y] = rgba(mix(px[x, y], CHARGE, min(1.0, k * 1.5)))
            elif z == "fin":
                r, g, b, a = px[x, y]
                c = rgba(mix((r, g, b, 255), SHEEN, 0.35 * k))
                px[x, y] = (c[0], c[1], c[2], a)
            else:
                px[x, y] = rgba(mix(px[x, y], SHEEN, 0.4 * k))
    img = _outline(img, zones)
    for (x, y), off in SPARKS:
        sparkle(img, x, y, max(0.0, wave(t, off) * 1.4 - 0.4), SPARK, reach=2)
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
