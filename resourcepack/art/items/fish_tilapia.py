"""Tilapia (UNCOMMON fish): a Nile tilapia in the old icon's sage green.

A flat 32x32 sprite in the fishing-collection style: side view, head up and to the left,
the body on the clean 1:2 pixel diagonal down to the tail, light from the top-left.

Nile tilapia anatomy: a deep, compressed cichlid body with a terminal, thick-lipped
mouth; one long dorsal fin whose low, saw-toothed spiny front runs into a tall soft rear
that trails back over the tail base, mirrored by the anal fin below; a squared-off
(truncate) caudal fin crossed by the species' tell-tale dark vertical stripes; long
pointed pectorals behind the gill cover and dark thoracic pelvics right under them.
Colours follow the old 16x16 texture (sage-green body, pale mint-silver belly), with
the faint dark flank bars, the black opercular spot and the rosy fin margins of a
real Nile tilapia.

The fish is described in fish space (u runs from the snout toward the tail, v toward
the belly, both in pixels), rasterised through the 1:2 diagonal so bars, fin rays and
the scale lattice land on clean pixel steps.

Animation: 12 frames x 3 ticks (1.8 s loop). The tail beats about a pixel, the soft
dorsal and anal flags ripple after it and the paired fins flick; in the first half a
soft glint slides along the back (kit.shine() at low strength, masked to the back),
and in the second half a band of mint-silver scale shimmer rolls across the flank.
"""
from __future__ import annotations

import bisect
import math

from art.kit import animate, canvas, mix, rgba, save_animation, shine, sprite

ID = "fish_tilapia"
NAME = "Tilapia"
KIND = "item"
MODEL_KEY = "fish/tilapia"
COUNTERPART = "item/cod"

N = 32
FRAMES = 12
FRAMETIME = 3
SS = 4  # supersamples per pixel axis when deciding what a pixel covers

# --------------------------------------------------------------------------------------
# Palette (dark -> light). Shadows lean teal-slate, lights lean warm sage-cream.
# --------------------------------------------------------------------------------------
BODY = ["#25372e", "#32483b", "#425b49", "#55705a", "#6b876b", "#86a07f", "#a2b898",
        "#bfd0b3", "#dbe6cf", "#eff4e5"]
OUTLINE = "#1d2c25"
FIN = ["#27352d", "#3e4f43", "#5a6d5b", "#788b75", "#98a993", "#bac7b2"]
FIN_OUT = "#1f2b25"
ROSE = ["#8e5650", "#b67468", "#d5998a", "#ecc2b2"]
PUPIL, CATCH = "#111a15", "#f7fbef"
IRIS = ["#6d6232", "#a8913f", "#d4b95a"]
AQUA = ["#8fcdb9", "#bdeadb", "#e4fbf2", "#f7fffb"]
GLINT = "#f7f7e3"

FIN_ALPHA = 205     # membranes
RAY_ALPHA = 220     # rays and margins
FIN_OUT_ALPHA = 235

# --------------------------------------------------------------------------------------
# Fish space -> frame. u = along the body toward the tail, v = toward the belly (pixels).
# +u is (2, 1)/sqrt5 on screen and +v is (-1, 2)/sqrt5: the 1:2 pixel diagonal.
# --------------------------------------------------------------------------------------
R5 = math.sqrt(5.0)
OX, OY = 3.3, 10.2   # the snout tip, in frame pixels


def to_fish(x: float, y: float) -> tuple[float, float]:
    dx, dy = x - OX, y - OY
    return (2 * dx + dy) / R5, (2 * dy - dx) / R5


def to_screen(u: float, v: float) -> tuple[float, float]:
    return OX + (2 * u - v) / R5, OY + (u + 2 * v) / R5


def _pchip(knots):
    """A smooth monotone curve through (x, y) knots (no overshoot between them)."""
    xs = [k[0] for k in knots]
    ys = [k[1] for k in knots]
    n = len(xs)
    h = [xs[i + 1] - xs[i] for i in range(n - 1)]
    d = [(ys[i + 1] - ys[i]) / h[i] for i in range(n - 1)]
    m = [d[0]] + [0.0] * (n - 2) + [d[-1]]
    for i in range(1, n - 1):
        if d[i - 1] * d[i] > 0:
            w1, w2 = 2 * h[i] + h[i - 1], h[i] + 2 * h[i - 1]
            m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])

    def f(x: float) -> float:
        if x <= xs[0]:
            return ys[0]
        if x >= xs[-1]:
            return ys[-1]
        i = bisect.bisect_right(xs, x) - 1
        t = (x - xs[i]) / h[i]
        t2, t3 = t * t, t * t * t
        return ((2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h[i] * m[i]
                + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h[i] * m[i + 1])

    return f


# --------------------------------------------------------------------------------------
# Anatomy (fish space, pixels). Standard length about 22 px, tail to about 28.
# --------------------------------------------------------------------------------------
BACK = _pchip([(0.0, 0.3), (0.6, -0.9), (1.4, -1.8), (2.5, -2.8), (3.8, -3.7), (5.3, -4.5),
               (7.0, -5.1), (8.7, -5.5), (10.3, -5.65), (11.9, -5.55), (13.5, -5.2), (15.1, -4.6),
               (16.7, -3.85), (18.2, -3.15), (19.6, -2.65), (21.0, -2.4), (22.6, -2.35)])
BELLY = _pchip([(0.0, 0.3), (0.4, 1.2), (1.1, 1.95), (2.1, 2.65), (3.4, 3.35), (4.9, 3.95),
                (6.5, 4.4), (8.2, 4.75), (9.9, 4.85), (11.6, 4.75), (13.2, 4.45), (14.8, 3.95),
                (16.4, 3.35), (18.0, 2.8), (19.6, 2.45), (21.0, 2.3), (22.6, 2.25)])
BODY_END = 22.4
EYE = (3.3, -1.55)          # centre of the eye
GILL = (6.6, 0.4, 0.085)    # gill-cover edge: u = a - k * (v - b)^2
SCALES_FROM = 7.4           # scales start behind the gill cover


def gill_u(v: float) -> float:
    a, b, k = GILL
    return a - k * (v - b) ** 2


def _dorsal() -> tuple[list, list]:
    """The long dorsal fin: saw-toothed spiny front, tall soft rear trailing over the tail."""
    pts = [(6.4, BACK(6.4) + 0.6)]
    spines = [7.0, 8.6, 10.2, 11.8, 13.4]
    rays = []
    for i, ub in enumerate(spines):
        tip = (ub + 0.7, BACK(ub) - (1.7 if i == 0 else 2.55))
        pts.append(tip)
        rays.append(((ub, BACK(ub) + 0.4), tip))
        if i + 1 < len(spines):
            mid = (ub + spines[i + 1]) / 2
            pts.append((mid + 0.7, BACK(mid) - 1.75))
    soft = [(14.6, 3.0), (15.8, 3.55), (17.0, 3.85), (18.2, 3.75), (19.3, 3.35)]
    for ub, hgt in soft:
        tip = (ub + 0.9, BACK(ub) - hgt)
        pts.append(tip)
        rays.append(((ub, BACK(ub) + 0.4), tip))
    pts += [(21.3, -4.9), (22.5, -4.1), (23.4, -3.2), (22.6, -2.55), (21.0, -2.1), (19.6, BACK(19.6) + 0.6)]
    return pts, rays


def _anal() -> tuple[list, list]:
    """The anal fin: two short spines, then a soft flag mirroring the rear dorsal."""
    pts = [(14.0, BELLY(14.0) - 0.6)]
    rays = []
    for ub, hgt in [(14.5, 1.3), (15.5, 2.1), (16.5, 2.6), (17.5, 2.8), (18.5, 2.6)]:
        tip = (ub + 0.8, BELLY(ub) + hgt)
        pts.append(tip)
        rays.append(((ub, BELLY(ub) - 0.4), tip))
    pts += [(20.4, 4.3), (21.6, 3.6), (22.3, 2.9), (21.4, 2.3), (20.0, BELLY(20.0) - 0.6)]
    return pts, rays


def _caudal() -> tuple[list, list]:
    """The truncate tail: a squared fan with rounded corners."""
    pts = [(21.4, -2.1), (23.4, -3.3), (25.3, -4.3), (26.6, -4.75), (27.4, -4.55), (27.8, -3.9),
           (28.05, -2.0), (28.15, 0.0), (28.05, 2.0), (27.8, 3.9), (27.4, 4.55), (26.6, 4.75),
           (25.3, 4.3), (23.4, 3.3), (21.4, 2.1)]
    rays = [((21.0, 0.0), (28.1, v)) for v in (-4.2, -2.1, 0.0, 2.1, 4.2)]
    return pts, rays


def _pectoral() -> tuple[list, list]:
    """Long pointed pectoral fin behind the gill cover (near side, over the body)."""
    pts = [(6.9, -0.1), (8.6, 0.25), (10.6, 0.9), (12.6, 1.75), (13.6, 2.35), (12.1, 2.55),
           (10.1, 2.4), (8.3, 2.05), (6.9, 1.6)]
    rays = [((7.0, 0.1), (13.4, 2.25)), ((7.0, 0.9), (11.6, 2.45))]
    return pts, rays


def _pelvic() -> tuple[list, list]:
    """Thoracic pelvic fin under the pectoral base, hanging down and back."""
    b = BELLY(8.2)
    pts = [(7.7, b - 0.8), (8.9, b - 0.5), (10.4, b + 0.9), (12.2, b + 2.6), (12.5, b + 3.2),
           (11.2, b + 2.8), (9.5, b + 1.9), (7.9, b + 0.6)]
    rays = [((8.0, b - 0.3), (12.3, b + 3.0))]
    return pts, rays


DORSAL, ANAL, CAUDAL, PECTORAL, PELVIC = _dorsal(), _anal(), _caudal(), _pectoral(), _pelvic()

# --------------------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------------------


def _clamp(x: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, x))


def _smooth(a: float, b: float, x: float) -> float:
    t = _clamp((x - a) / (b - a))
    return t * t * (3 - 2 * t)


def _inside(poly, u: float, v: float) -> bool:
    hit = False
    j = len(poly) - 1
    for i in range(len(poly)):
        ui, vi = poly[i]
        uj, vj = poly[j]
        if (vi > v) != (vj > v) and u < (uj - ui) * (v - vi) / (vj - vi) + ui:
            hit = not hit
        j = i
    return hit


def _seg_dist(p, a, b) -> float:
    ax, ay = a
    bx, by = b
    px, py = p
    dx, dy = bx - ax, by - ay
    t = _clamp(((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy))
    return math.hypot(px - ax - t * dx, py - ay - t * dy)


def _edge_dist(poly, p) -> float:
    return min(_seg_dist(p, poly[i], poly[(i + 1) % len(poly)]) for i in range(len(poly)))


def _rotate(pts, pivot, degrees):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    pu, pv = pivot
    return [(pu + (u - pu) * c - (v - pv) * s, pv + (u - pu) * s + (v - pv) * c) for u, v in pts]


def _coverage(test) -> dict:
    """Pixels whose area is at least half inside test(u, v): {(x, y): (u, v) of the centre}."""
    out = {}
    for y in range(N):
        for x in range(N):
            cu, cv = to_fish(x + 0.5, y + 0.5)
            if abs(cu - 14) > 18 or abs(cv) > 14:
                continue
            hits = 0
            for j in range(SS):
                for i in range(SS):
                    if test(*to_fish(x + (i + 0.5) / SS, y + (j + 0.5) / SS)):
                        hits += 1
            if hits * 2 >= SS * SS:
                out[(x, y)] = (cu, cv)
    return out


# --------------------------------------------------------------------------------------
# One frame
# --------------------------------------------------------------------------------------


def _sway(t: float, phase: float) -> float:
    return math.sin(2 * math.pi * t + phase)


def _bend(base: float, length: float, amp: float):
    """A v displacement that grows from 0 at u = base to amp at u = base + length."""
    return lambda u: amp * _clamp((u - base) / length) ** 1.5


def _fin_layer(shape, t, bend=None, pivot=None, angle=0.0):
    """Rasterise a fin (optionally bent in v or turned about its base) for this frame."""
    pts, rays = shape
    if pivot is not None and angle:
        pts = _rotate(pts, pivot, angle)
        rays = [tuple(_rotate(r, pivot, angle)) for r in rays]
    if bend is not None:
        test = lambda u, v: _inside(pts, u, v - bend(u))  # noqa: E731
    else:
        test = lambda u, v: _inside(pts, u, v)  # noqa: E731
    cells = _coverage(test)
    out = {}
    for key, (u, v) in cells.items():
        vv = v - bend(u) if bend is not None else v
        ray = min(_seg_dist((u, vv), a, b) for a, b in rays) if rays else 9.0
        edge = _edge_dist(pts, (u, vv))
        out[key] = (u, vv, ray, edge)
    return out


def _body_cells() -> dict:
    return _coverage(lambda u, v: 0.0 <= u <= BODY_END and BACK(u) <= v <= BELLY(u))


BODY_CELLS = None


def _body_tone(x: int, y: int, u: float, v: float, cells: dict) -> float:
    top, bot = BACK(u), BELLY(u)
    s = _clamp((v - top) / max(0.5, bot - top))
    # countershading: dark olive-sage back, sage flank, pale mint-silver belly
    tone = 3.1 + 3.7 * _smooth(0.25, 0.92, s)
    # the fore-body faces the light a little more than the tail end
    tone += 0.7 * (1 - _clamp(u / 22.0)) - 0.35
    # a soft sheen band where the rounded upper flank turns toward the light
    tone += 0.7 * math.exp(-((s - 0.40) / 0.08) ** 2) * (1 - _smooth(15, 21, u))
    # the belly's lower edge rolls away from the light
    tone -= 1.3 * _smooth(0.84, 1.0, s)
    # rim light on edges facing the top-left light, shadow on edges facing away
    ox = oy = 0
    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (1, -1), (-1, 1), (1, 1)):
        if (x + dx, y + dy) not in cells:
            ox += dx
            oy += dy
    if ox or oy:
        lit = (-ox - oy) / (math.hypot(ox, oy) * math.sqrt(2))
        if lit > 0.2:
            tone += 1.9 * lit
        elif lit < -0.2:
            tone += 0.9 * lit
    return tone


def _paint(t: float):
    global BODY_CELLS
    if BODY_CELLS is None:
        BODY_CELLS = _body_cells()
    body = BODY_CELLS
    img = canvas(N)
    px = img.load()
    zones: dict = {}

    # ---- fins behind the body: caudal, dorsal, anal -------------------------------
    tail = _fin_layer(CAUDAL, t, bend=lambda u: 0.95 * _clamp((u - 21.2) / 6.9) ** 1.5 * _sway(t, 0.0))
    dorsal = _fin_layer(DORSAL, t, bend=lambda u: 0.75 * _clamp((u - 16.5) / 7.0) ** 1.6 * _sway(t, -0.9))
    anal = _fin_layer(ANAL, t, bend=lambda u: 0.7 * _clamp((u - 16.0) / 6.3) ** 1.6 * _sway(t, -1.4))

    def put(key, colour, alpha):
        r, g, b, _ = rgba(colour)
        px[key] = (r, g, b, alpha)

    for key, (u, v, ray, edge) in tail.items():
        # truncate fan: darker near the peduncle, the Nile tilapia's dark vertical stripes,
        # faint rays, a rosy trailing margin
        k = _clamp((u - 21.0) / 7.0)
        tone = 3.2 + 1.2 * k - 0.8 * _smooth(3.0, 4.8, abs(v))
        U = 2 * key[0] + key[1]
        stripe = U % 6 in (0, 1) and u > 22.3 and edge > 0.55
        if ray < 0.42:
            tone -= 1.0
        colour = FIN[int(round(_clamp(tone, 0, 5)))]
        alpha = FIN_ALPHA
        if stripe:
            colour, alpha = FIN[1] if ray >= 0.42 else FIN[0], RAY_ALPHA
        if edge < 0.62 and u > 27.0:
            colour, alpha = ROSE[1] if abs(v) < 3.6 else ROSE[0], RAY_ALPHA
        put(key, colour, alpha)
        zones[key] = "tail"
    for layer, name in ((dorsal, "dorsal"), (anal, "anal")):
        for key, (u, v, ray, edge) in layer.items():
            if key in body:
                continue
            soft = u > (14.0 if name == "dorsal" else 15.0)
            k = _clamp(1 - edge / 2.2)
            tone = 2.6 + 1.6 * k
            colour, alpha = FIN[int(round(_clamp(tone, 0, 5)))], FIN_ALPHA
            if ray < 0.45:
                colour, alpha = FIN[1], RAY_ALPHA
            if edge < 0.62 and soft:
                colour, alpha = (ROSE[1] if u < 20.5 else ROSE[0]), RAY_ALPHA
            elif edge < 0.62:
                colour, alpha = FIN[4], RAY_ALPHA
            put(key, colour, alpha)
            zones[key] = name

    # ---- body -------------------------------------------------------------------
    for (x, y), (u, v) in body.items():
        tone = _body_tone(x, y, u, v, body)
        top, bot = BACK(u), BELLY(u)
        s = _clamp((v - top) / max(0.5, bot - top))
        U, V = 2 * x + y, 2 * y - x
        head = u < gill_u(v)
        if not head:
            # faint dark bars down the flank (Nile tilapia), fading into the belly
            if U % 6 in (0, 1) and 7.6 < u < 21.0 and 0.08 < s < 0.72:
                tone -= 0.95 * (1 - _smooth(0.5, 0.72, s))
            # scale lattice: one bright scale edge every ~3 px on a diamond net
            if u > SCALES_FROM and 0.14 < s < 0.86 and V % 5 == 0 and (U - V) % 10 == 0:
                tone += 1.0
        else:
            # smooth cheek below the eye catches light
            tone += 0.6 * math.exp(-(((u - 3.8) / 1.8) ** 2 + ((v - 1.2) / 1.3) ** 2))
        # gill-cover edge: a dark slit with a lit rim just behind it
        g = u - gill_u(v)
        if -0.45 < g < 0.45 and s > 0.08:
            tone -= 1.6
        elif 0.45 <= g < 1.25 and s > 0.08:
            tone += 0.5
        colour = BODY[int(round(_clamp(tone, 1, 8)))]
        px[x, y] = rgba(colour)
        zones[(x, y)] = "head" if head else ("back" if s < 0.34 else "flank")

    # opercular spot: the dark blotch at the gill cover's upper rear corner
    for du, dv in ((0.0, 0.0), (0.45, 0.9)):
        sx, sy = to_screen(gill_u(-2.2) - 0.2 + du, -2.2 + dv)
        key = (int(sx), int(sy))
        if key in body:
            px[key] = rgba(BODY[1])

    # ---- near-side fins over the body: pelvic, pectoral --------------------------
    pelvic = _fin_layer(PELVIC, t, pivot=(8.0, BELLY(8.0) - 0.5), angle=5.0 * _sway(t, 2.0))
    pectoral = _fin_layer(PECTORAL, t, pivot=(7.0, 0.7), angle=6.0 * _sway(t, 1.2))
    for key, (u, v, ray, edge) in pelvic.items():
        colour, alpha = FIN[2], FIN_ALPHA + 10
        if ray < 0.5:
            colour, alpha = FIN[0], RAY_ALPHA + 10
        elif edge < 0.55:
            colour = FIN[3]
        _over(px, key, colour, alpha)
        zones.setdefault(key, "fin")
    for key, (u, v, ray, edge) in pectoral.items():
        k = _clamp((u - 7.0) / 6.5)
        colour = mix(FIN[4], ROSE[2], 0.25 + 0.35 * k)
        alpha = 150
        if ray < 0.45:
            colour, alpha = FIN[2], 185
        elif edge < 0.5 and v < 1.4:
            colour, alpha = FIN[5], 175
        _over(px, key, colour, alpha)
        zones[key] = "pect"

    # ---- head details: eye, mouth -------------------------------------------------
    ex, ey = to_screen(*EYE)
    ex, ey = int(ex - 0.5), int(ey - 0.5)   # top-left pixel of the 2x2 eye
    for (dx, dy), c in {(-1, 0): IRIS[1], (-1, 1): IRIS[0], (0, 2): IRIS[0], (1, 2): IRIS[1],
                        (2, 1): IRIS[1], (2, 0): IRIS[2], (0, -1): IRIS[1], (1, -1): IRIS[2]}.items():
        if (ex + dx, ey + dy) in body:
            px[ex + dx, ey + dy] = rgba(c)
    px[ex, ey] = rgba(CATCH)
    for dx, dy in ((1, 0), (0, 1), (1, 1)):
        px[ex + dx, ey + dy] = rgba(PUPIL)
    for du, dv, c in ((0.25, 0.35, BODY[1]), (1.15, 0.7, BODY[2]), (0.6, -0.55, BODY[7]),
                      (0.55, 1.25, BODY[6])):
        sx, sy = to_screen(du, dv)
        key = (int(sx), int(sy))
        if key in body:
            px[key] = rgba(c)
    return img, zones


def _over(px, key, colour, alpha: int) -> None:
    """Lay a translucent colour over whatever is already at key."""
    r, g, b, a = px[key]
    c = rgba(colour)
    if a == 0:
        px[key] = (c[0], c[1], c[2], alpha)
        return
    k = alpha / 255
    out_a = a + (255 - a) * k
    px[key] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k),
               round(max(a, out_a)))


def _outline(img, zones) -> None:
    px = img.load()
    src = img.copy().load()
    for y in range(N):
        for x in range(N):
            if src[x, y][3]:
                continue
            body_side = fin_side = False
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < N and 0 <= ny < N and src[nx, ny][3]:
                    if zones.get((nx, ny)) in ("head", "back", "flank"):
                        body_side = True
                    else:
                        fin_side = True
            if body_side:
                px[x, y] = rgba(OUTLINE)
            elif fin_side:
                r, g, b, _ = rgba(FIN_OUT)
                px[x, y] = (r, g, b, FIN_OUT_ALPHA)


def _frame(t: float):
    img, zones = _paint(t)
    _outline(img, zones)
    px = img.load()
    step = t * FRAMES
    if step < 6:
        # a soft glint slides along the back, snout to tail (shine() at low strength)
        width = 3.2
        centre_u = -1.0 + 25.0 * step / 5.0
        centre = centre_u + (2 * OX + OY) / R5
        hi = N * 2 / R5 + N / R5
        phase = (centre + 2 * width) / (hi + 4 * width)
        lit = shine(img, phase, colour=GLINT, width=width, strength=0.45, angle=math.degrees(math.atan2(1, 2)),
                    pause=0.0).load()
        for key, zone in zones.items():
            if zone in ("back", "head"):
                px[key] = lit[key]
    else:
        # a band of mint-silver shimmer rolls across the scales, head to tail
        centre = 3.0 + 22.0 * (step - 6) / 5.0
        for key, zone in zones.items():
            if zone not in ("back", "flank", "pect", "head"):
                continue
            u, v = to_fish(key[0] + 0.5, key[1] + 0.5)
            k = max(0.0, 1.0 - abs(u - 0.45 * v - centre) / 2.6)
            if k <= 0:
                continue
            U, V = 2 * key[0] + key[1], 2 * key[1] - key[0]
            spot = V % 5 == 0 and (U - V) % 10 == 0 and zone != "head"
            near = (V % 5 in (1, 4) and (U - V) % 10 in (1, 9)) and zone == "flank"
            if spot:
                px[key] = rgba(mix(px[key], AQUA[3], 0.95 * min(1.0, k * 1.4)))
            elif near:
                px[key] = rgba(mix(px[key], AQUA[1], 0.55 * k))
            else:
                px[key] = rgba(mix(px[key], AQUA[0], 0.28 * k))
    return img


def textures() -> None:
    save_animation(animate(_frame, FRAMES), "fish", frametime=FRAMETIME)


def models() -> dict:
    return {"main": sprite("fish")}
