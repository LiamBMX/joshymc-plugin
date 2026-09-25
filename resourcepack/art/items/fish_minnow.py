"""Minnow (fishing collection, COMMON): a flat, animated 32x32 fish sprite.

A slim silver minnow swimming up and to the left, drawn like the vanilla fish icons
but at 32 px: a sage-green back with a wet rim light, a gold line along the upper
flank over a row of dark blotches that ends in a dark spot at the tail base,
lavender-to-mint silver flanks, a white belly, a big eye with a gold iris crescent and
see-through cream fins with golden rays. The forked tail flicks about a pixel, the
fins sway with it and a soft glint slides along the back (8 frames x 3 ticks).

The fish is modelled in a local frame (u from the snout to the tail tip, v toward the
belly) laid on the sprite at a clean 2:1 pixel slope, so the lines along the body step
evenly. Each pixel column is shaded from the row where the lateral line crosses it,
and the blotches sit on whole runs of that stepped line, so they stay clean clusters.
"""
from __future__ import annotations

import math

from art.kit import animate, canvas, mix, rgba, save_animation, shine, sprite

ID = "fish_minnow"
NAME = "Minnow"
KIND = "item"
MODEL_KEY = "fish/minnow"
COUNTERPART = "item/salmon"

N = 32
FRAMES, FRAMETIME = 8, 3

# ---- palettes (darkest -> lightest), hand-tuned hue-shifted ramps ----------------------
BACK = ["#1c3336", "#2c4a47", "#406657", "#5a8469", "#7ba481", "#a5c69a"]
SILVER = ["#55577a", "#767b9a", "#979eb6", "#b6c1cd", "#d0e1d9", "#ebf6eb"]
BELLY = ["#7c7a9b", "#a4a6bf", "#c9cfdb", "#e2e9e6", "#f7fbf1"]
BAND = ["#1b2536", "#2b3b4e", "#415467", "#5e7284"]
GOLD = ["#6d4913", "#a06f1c", "#cf9e2a", "#edc54a", "#fae28a"]
FIN = ["#7c6a36", "#a8914f", "#d6c07c", "#e9dca4", "#f6efcf"]
EYE = ["#111626", "#2a3246", "#ffffff"]
OUT_BACK, OUT_LIT, OUT_BELLY = "#1a2e32", "#28443f", "#43425e"
OUT_FIN = "#7c6a36"
MOUTH = "#3b3a57"
GLINT = "#f4fff2"
GLINT_WIDTH, GLINT_STRENGTH = 6, 0.45
SHEEN = GOLD[3]                           # the gold line along the upper flank

# The Eurasian minnow's markings: 2x2 dark blotches on every other run of the stepped
# lateral line between these u (darkest on top), and a dark dash at the tail base.
BLOTCH_FROM, BLOTCH_TO = 7.6, 19.2
BLOTCH = {(0, 0): BAND[0], (1, 0): BAND[0], (0, 1): BAND[1], (1, 1): BAND[1]}

# ---- pose: the local frame runs from the snout (u = 0) to the tail tip ----------------
THETA = math.atan2(1, 2)                  # a clean 2:1 pixel slope, head up-left
CA, SA = math.cos(THETA), math.sin(THETA)
SNOUT = (2.0, 9.7)                        # screen position of the snout tip
SL, TL = 22.4, 28.0                       # snout -> tail base, snout -> tail tip


def to_local(x: float, y: float) -> tuple[float, float]:
    """Screen -> (u along the body, v toward the belly)."""
    dx, dy = x - SNOUT[0], y - SNOUT[1]
    return dx * CA + dy * SA, -dx * SA + dy * CA


def to_screen(u: float, v: float) -> tuple[float, float]:
    return SNOUT[0] + u * CA - v * SA, SNOUT[1] + u * SA + v * CA


def _curve(points):
    """Catmull-Rom through (u, value) points, clamped at the ends."""
    us = [p[0] for p in points]
    hs = [p[1] for p in points]

    def slope(k):
        a, b = max(k - 1, 0), min(k + 1, len(us) - 1)
        return (hs[b] - hs[a]) / (us[b] - us[a])

    def f(u: float) -> float:
        if u <= us[0]:
            return hs[0]
        if u >= us[-1]:
            return hs[-1]
        i = 0
        while us[i + 1] < u:
            i += 1
        h = us[i + 1] - us[i]
        t = (u - us[i]) / h
        t2, t3 = t * t, t * t * t
        return ((2 * t3 - 3 * t2 + 1) * hs[i] + (t3 - 2 * t2 + t) * h * slope(i)
                + (-2 * t3 + 3 * t2) * hs[i + 1] + (t3 - t2) * h * slope(i + 1))
    return f


# Half-depths above (TOP) and below (BOT) the axis: a blunt round snout, the deepest
# point just ahead of the dorsal fin and a slim caudal peduncle.
TOP = _curve([(0, 0.0), (0.4, 1.0), (1.0, 1.72), (2.0, 2.5), (3.2, 3.08), (4.6, 3.46), (6.5, 3.78),
              (8.5, 3.95), (10.5, 3.95), (12.5, 3.7), (15.0, 3.05), (17.5, 2.3), (19.8, 1.7),
              (21.4, 1.5), (22.6, 1.55)])
BOT = _curve([(0, 0.0), (0.4, 0.72), (1.0, 1.3), (2.0, 1.95), (3.2, 2.55), (4.6, 3.02), (6.5, 3.45),
              (8.5, 3.72), (10.5, 3.75), (12.5, 3.5), (15.0, 2.85), (17.5, 2.15), (19.8, 1.6),
              (21.4, 1.4), (22.6, 1.45)])


def _top(u):
    return -TOP(u)


def _bot(u):
    return BOT(u)


def _mid(u):
    """The lateral line, a hair above the middle of the body."""
    return (_top(u) + _bot(u)) / 2 - 0.2


def _s(u, v):
    """-1 at the top of the back, +1 at the bottom of the belly."""
    top, bot = _top(u), _bot(u)
    return (2 * v - top - bot) / max(0.2, bot - top)


# ---- fins, as polygons in the local frame ---------------------------------------------
DORSAL = [(10.4, _top(10.4) + 0.6), (10.8, _top(10.8) - 1.5), (11.4, _top(11.4) - 2.8),
          (12.2, _top(12.2) - 3.0), (13.4, _top(13.4) - 1.8), (14.8, _top(14.8) - 0.4),
          (14.4, _top(14.4) + 0.6)]
ANAL = [(15.0, _bot(15.0) - 0.6), (15.4, _bot(15.4) + 1.5), (16.0, _bot(16.0) + 2.6),
        (16.8, _bot(16.8) + 2.7), (19.0, _bot(19.0) + 0.6), (18.5, _bot(18.5) - 0.6)]
PELVIC = [(9.8, _bot(9.8) - 0.6), (10.3, _bot(10.3) + 1.3), (11.2, _bot(11.2) + 2.3),
          (12.2, _bot(12.2) + 2.2), (12.0, _bot(12.0) - 0.5)]
PECTORAL = [(6.0, 1.9), (6.9, 1.7), (9.9, 4.0), (9.7, 4.7), (6.3, 2.8)]
CAUDAL = [(SL - 0.8, -1.4), (SL + 1.2, -1.95), (SL + 3.1, -2.8), (SL + 4.9, -3.6), (SL + 5.5, -3.55),
          (SL + 5.3, -2.9), (SL + 3.9, -1.3), (SL + 2.6, -0.1), (SL + 2.6, 0.1), (SL + 3.9, 1.3),
          (SL + 5.3, 2.9), (SL + 5.5, 3.55), (SL + 4.9, 3.6), (SL + 3.1, 2.8), (SL + 1.2, 1.95),
          (SL - 0.8, 1.4)]
CAUDAL_PIVOT, CAUDAL_TILT = (SL - 0.8, 0.0), 14.0   # the fork turned up so it opens evenly
PECTORAL_PIVOT = (6.3, 2.2)
PELVIC_PIVOT = (10.4, _bot(10.4))

# Fin rays fan out from these points: (origin u, origin v, ray angles in radians).
RAYS = {
    "caudal": (SL - 2.0, 0.0, (-0.44, 0.44)),
    "dorsal": (13.8, _top(13.8) + 3.0, (-1.9, -1.52)),
    "anal": (17.0, _bot(17.0) - 3.0, ()),
    "pelvic": (10.4, _bot(10.4) - 2.0, ()),
    "pectoral": (6.3, 2.2, (0.6,)),
}


def _inside(poly, u, v) -> bool:
    inside = False
    j = len(poly) - 1
    for i in range(len(poly)):
        ui, vi = poly[i]
        uj, vj = poly[j]
        if (vi > v) != (vj > v) and u < ui + (v - vi) * (uj - ui) / (vj - vi):
            inside = not inside
        j = i
    return inside


def _smooth(a, b, x):
    k = max(0.0, min(1.0, (x - a) / (b - a)))
    return k * k * (3 - 2 * k)


CURL = 1.3                     # static upward curl of the tail (the swimming pose), px at the tip
SWAY, SWAY_FROM = 1.05, 19.5   # tail flick amplitude at the tip, and the u where it starts


def _bend(u, t):
    """Where the spine sits at u: the tail's gentle upward curl plus a flick that grows
    from the caudal peduncle to about 1 px at the tail tip. The body ahead stays still."""
    curl = -CURL * _smooth(SL - 4.0, TL, u)
    k = _smooth(SWAY_FROM, TL, u) ** 1.2
    return curl + SWAY * k * math.sin(2 * math.pi * t)


def _rotate(u, v, pivot, degrees):
    a = math.radians(degrees)
    du, dv = u - pivot[0], v - pivot[1]
    return pivot[0] + du * math.cos(a) - dv * math.sin(a), pivot[1] + du * math.sin(a) + dv * math.cos(a)


# ---- the face, hand-placed around the eye (it never moves) ------------------------------
# Offsets from the eye's top-left pixel: a sage cap over a 2x2 eye with a catchlight and a
# gold iris crescent, a small dark mouth at the snout, bright silver cheeks and the rear
# edge of the gill cover embossed (light plate edge in front, dark shadow behind).
_ex, _ey = to_screen(3.2, -0.2)
EYE_AT = (int(_ex - 0.5), int(_ey - 0.5))
HEAD = {
    (0, -2): BACK[5], (1, -2): BACK[5], (2, -2): BACK[4],
    (-2, -1): BACK[4], (-1, -1): BACK[5], (0, -1): BACK[3], (1, -1): BACK[3], (2, -1): BACK[3],
    (-2, 0): MOUTH, (-1, 0): SILVER[4], (0, 0): EYE[2], (1, 0): EYE[0], (2, 0): GOLD[3], (3, 0): BACK[3],
    (-1, 1): SILVER[5], (0, 1): EYE[0], (1, 1): EYE[0], (2, 1): GOLD[2], (3, 1): SILVER[2],
    (-1, 2): BELLY[2], (0, 2): SILVER[5], (1, 2): SILVER[5], (2, 2): SILVER[5], (3, 2): SILVER[2],
    (0, 3): BELLY[2], (1, 3): SILVER[5], (2, 3): SILVER[2],
    (1, 4): BELLY[2], (2, 4): SILVER[2],
    (2, 5): BELLY[2],
}


# ---- painting -------------------------------------------------------------------------

def _classify(t):
    """(x, y) -> (region, u, v) for every pixel of the fish, v already un-bent."""
    ph = 2 * math.pi * t
    cells = {}
    for y in range(N):
        for x in range(N):
            u, v = to_local(x + 0.5, y + 0.5)
            v0 = v - _bend(u, t)
            pu, pv = _rotate(u, v0, PECTORAL_PIVOT, -10 * math.sin(ph + 1.6))
            pect = _inside(PECTORAL, pu, pv)
            if 0 <= u <= SL + 0.4 and _top(u) <= v0 <= _bot(u):
                cells[(x, y)] = ("pect_body" if pect else "body", u, v0)
                continue
            # dorsal and anal fins flex back and forth (a shear about their base)
            du = u + 0.3 * math.sin(ph - 0.7) * max(0.0, _top(u) - v0)
            au = u + 0.25 * math.sin(ph - 1.0) * max(0.0, v0 - _bot(u))
            gu, gv = _rotate(u, v0, PELVIC_PIVOT, -12 * math.sin(ph + 2.1))
            cu, cv = _rotate(u, v0, CAUDAL_PIVOT, CAUDAL_TILT)
            if cu > SL - 1.0 and _inside(CAUDAL, cu, cv):
                cells[(x, y)] = ("caudal", cu, cv)
            elif _inside(DORSAL, du, v0):
                cells[(x, y)] = ("dorsal", du, v0)
            elif _inside(ANAL, au, v0):
                cells[(x, y)] = ("anal", au, v0)
            elif _inside(PELVIC, gu, gv):
                cells[(x, y)] = ("pelvic", gu, gv)
            elif pect:
                cells[(x, y)] = ("pectoral", pu, pv)
    return cells


def _head(u, v):
    """True in front of the gill cover's rear edge."""
    s = _s(u, v)
    return u < 5.0 + 0.9 * (1 - s * s)


def _body_colour(u, r, is_top, is_bot, head):
    """Countershaded colour of a body pixel r rows below the lateral line in its column."""
    rear = u > 16.5
    if head:
        if is_top:
            return BACK[4]
        if is_bot:
            return BELLY[2]
        if r <= -2:
            return BACK[3]
        return SILVER[5] if r >= 1 else SILVER[4]
    if is_top:                                            # rim light along the back
        if u < 8.2:
            return BACK[5]                                # wet gleam on the nape
        return BACK[4] if u < 17 else BACK[3]
    if is_bot:                                            # shadowed underside
        return BELLY[1] if u > 7 else BELLY[2]
    if r <= -3:
        return BACK[2] if rear else BACK[3]
    if r == -2:
        return BACK[1] if rear else BACK[2]
    if r == -1:                                           # the gold line, brightest behind the gill
        if u < 9.6:
            return GOLD[4]
        return BACK[1] if u > SL - 3.0 else SHEEN
    if r == 0:                                            # lateral line: lavender silver
        return SILVER[3] if u < 15 else SILVER[2]
    if r == 1:                                            # lower flank: mint silver
        return SILVER[4] if u < 14 else SILVER[3]
    return BELLY[4] if u < 12 else BELLY[3]


def _marks(lats, us):
    """Screen pixels of the blotches and the tail spot. lats: column -> row of the lateral
    line, us: column -> u there. They ride the tail flick with the body."""
    cols = sorted(c for c in lats if us[c] > BLOTCH_FROM - 0.8)
    runs: list[list[int]] = []
    for c in cols:
        if runs and c == runs[-1][-1] + 1 and lats[c] == lats[runs[-1][-1]]:
            runs[-1].append(c)
        else:
            runs.append([c])
    marks = {}
    for c in [c for c in cols if us[c] < SL - 0.3][-2:]:      # the dark dash at the tail base
        marks[(c, lats[c])] = BAND[0]
    chosen = [r for r in runs if BLOTCH_FROM <= us[r[0]] <= BLOTCH_TO and len(r) >= 2][::2]
    for r in chosen:
        for dx, c in enumerate(r[:2]):
            for dy in (0, 1):
                marks.setdefault((c, lats[c] + dy), BLOTCH[(dx, dy)])
    return marks


def _fin_colour(region, u, v):
    """Cream membranes with golden rays, see-through, and a warm gold blush where the fin
    joins the body."""
    ou, ov, rays = RAYS[region]
    if region == "caudal":
        base = u < SL + 0.7
    elif region == "dorsal":
        base = v > _top(u) - 0.75
    elif region in ("anal", "pelvic"):
        base = v < _bot(u) + 0.75
    else:
        base = False
    d = math.hypot(u - ou, v - ov)
    ang = math.atan2(v - ov, u - ou)
    ray = any(abs(math.sin(ang - a)) * d < 0.55 and math.cos(ang - a) > 0 for a in rays)
    if base:
        return rgba(mix(GOLD[3], FIN[3], 0.3))[:3] + (218,)
    if ray:
        return rgba(FIN[2])[:3] + (212,)
    return rgba(FIN[4])[:3] + (194,)


def _frame(t):
    cells = _classify(t)
    img = canvas(N)
    px = img.load()
    body = {p: c for p, c in cells.items() if c[0] in ("body", "pect_body")}

    # body, one pixel column at a time, keyed to the row the lateral line crosses
    columns: dict[int, list[int]] = {}
    for (x, y) in body:
        columns.setdefault(x, []).append(y)
    lats = {}
    for x, ys in columns.items():
        ys.sort()
        lats[x] = min(ys, key=lambda y: abs(body[(x, y)][2] - _mid(body[(x, y)][1])))
    us = {x: body[(x, lat)][1] for x, lat in lats.items()}
    marks = _marks(lats, us)
    for x, ys in columns.items():
        lat = lats[x]
        for y in ys:
            region, u, v = body[(x, y)]
            if (x, y) in marks and y not in (ys[0], ys[-1]):
                colour = rgba(marks[(x, y)])
            else:
                colour = rgba(_body_colour(u, y - lat, y == ys[0], y == ys[-1], _head(u, v)))
            if region == "pect_body":                       # the pectoral fin lies on the flank
                fin = _fin_colour("pectoral", u, v)
                colour = tuple(round(colour[i] + (fin[i] - colour[i]) * 0.6) for i in range(3)) + (255,)
            px[x, y] = colour

    ex, ey = EYE_AT
    for (dx, dy), colour in HEAD.items():
        if (ex + dx, ey + dy) in body:
            px[ex + dx, ey + dy] = rgba(colour)

    for (x, y), (region, u, v) in cells.items():
        if region not in ("body", "pect_body"):
            px[x, y] = _fin_colour(region, u, v)

    # 1 px outline around the whole silhouette: dark teal over the back (a touch lighter
    # on the lit head), dusky lavender under the belly, deep straw around the fins
    out = img.copy()
    opx = out.load()
    for y in range(N):
        for x in range(N):
            if (x, y) in cells:
                continue
            best = None
            for dx, dy in ((0, 1), (0, -1), (1, 0), (-1, 0)):
                c = cells.get((x + dx, y + dy))
                if c is None:
                    continue
                if c[0] in ("body", "pect_body"):
                    if c[2] > _mid(c[1]) + 0.4:
                        best = rgba(OUT_BELLY)
                    else:
                        best = rgba(OUT_LIT if c[1] < 7.5 else OUT_BACK)
                    break
                best = rgba(OUT_FIN)[:3] + (228,)
            if best is not None:
                opx[x, y] = best
    img = out
    px = img.load()

    # a soft glint slides along the back, fading out down the flank
    eye = {(ex + dx, ey + dy) for dx in (0, 1) for dy in (0, 1)}
    glint = shine(img, t, colour=GLINT, width=GLINT_WIDTH, strength=GLINT_STRENGTH,
                  angle=math.degrees(THETA), pause=0.3)
    gpx = glint.load()
    for (x, y), (region, u, v) in body.items():
        w = max(0.0, min(1.0, (0.15 - _s(u, v)) / 0.55))
        if w > 0 and (x, y) not in eye:
            a, b = px[x, y], gpx[x, y]
            px[x, y] = tuple(round(a[i] + (b[i] - a[i]) * w) for i in range(3)) + (a[3],)
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
