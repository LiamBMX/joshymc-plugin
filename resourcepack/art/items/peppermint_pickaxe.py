"""Peppermint Pickaxe (Christmas 2026, Survival only, issue #1399).

The head is two chunky candy canes: from the hub each cane runs out and up, then rolls
over into a classic crook whose blunt end points down like a pick. Their red-and-white
stripes spiral around the cane (continuous from segment to segment) and a lumpy cap of
glittering frosting, with a few drips, sits along their upper edges. At the hub a
gold-rimmed star mint faces out on both sides, crowned by a fan of holly leaves with red
berries; a satin bow with a little gold bell is tied round each cane beside it. The long
square handle spirals with peppermint stripes between gold collars, and a second,
smaller gold-rimmed mint hangs from its end below a ribbon bow.

Animated: the frosting glitters and a gloss band sweeps over both mints.
"""
import math
import random

from art.kit import (FIRST_PERSON_HAND, animate, art, bar, box, canvas, display, display_matrix, fill, mirror,
                     mix, model, move, place, prism, ramp, rgba, save, save_animation, shine, sparkle, turn, wave)

ID = "peppermint_pickaxe"
NAME = "Peppermint Pickaxe"
KIND = "pickaxe"
COUNTERPART = "item/netherite_pickaxe"

RED = ramp("#de2234", 6, 0.70, 0.10)       # candy red: wine shadows -> warm coral highlight
WHITE = ramp("#efe6ea", 6, 0.42, 0.12)     # sugar white with cool lilac shadows
RIBBON = ramp("#9c0c36", 6, 0.70, 0.10)    # deeper satin crimson for the bows
GOLD = ramp("#e4a92c", 6, 0.72, 0.12)
HOLLY = ramp("#36a046", 6, 0.70, 0.14)
BERRY = ramp("#d0162a", 5, 0.70, 0.10)
SNOW = ["#9fb8d8", "#c4d6ec", "#e2ecf8", "#f6faff", "#ffffff"]
SPARK = "#fffbe8"

P = 6.0            # candy stripe period along the cane, in model units (12 px)
SHAFT_P = 5.0      # handle stripe period (its four faces wrap 10 units, two stripes round)
HUB = (8.0, 19.0, 8.0)
MINT_R = 4.0       # hub mint radius (apothem of the 16-sided disc)
MINT_D = 2.9       # hub mint thickness along z
POMMEL = (8.0, -10.6, 8.0)
POM_R = 2.8
GRIP = (8.0, -2.6, 8.0)
HANDLE = (-6.6, 14.6)
CANE_W = 3.1


# ------------------------------------------------------------------------------------
# Textures
# ------------------------------------------------------------------------------------

def _stripe(phase: float) -> int:
    """Candy-cane band at a phase 0..1: 0 = white, 1 = red, 2 = thin red pinstripe,
    3 = the soft pink edge where red meets white."""
    p = phase % 1.0
    if p < 0.40:
        return 1
    if p < 0.46:
        return 3
    if p < 0.68:
        return 0
    if p < 0.76:
        return 2
    if p < 0.80:
        return 3
    return 0


# Round-tube shading across a face: edge -> lit -> edge (index into the ramps).
_TUBE = (1, 2, 4, 5, 4, 3, 2, 1)


def _tube_tone(frac: float) -> int:
    return _TUBE[min(len(_TUBE) - 1, int(frac * len(_TUBE)))]


def _cane_px(px, x, y, phase, frac, spec=False):
    band = _stripe(phase)
    t = _tube_tone(frac)
    if band == 1:
        c = RED[t]
    elif band == 2:
        c = RED[max(0, t - 1)]
    elif band == 3:
        c = mix(RED[t], WHITE[t], 0.55)
    else:
        c = WHITE[t]
    if spec and t == 5 and band in (0, 3):
        c = "#ffffff"
    px[x, y] = rgba(c)


def paint_cane():
    """Cane stripes for seg(): columns 0..7 (4 units) are the front window, 16..23 the
    side window. The stripe phase runs (u + v) / P, and the side window continues the
    front's phase so the spiral wraps round the corner."""
    img = canvas(32)
    px = img.load()
    for y in range(32):
        v = (y + 0.5) / 2
        for x in range(8):
            u = (x + 0.5) / 2
            _cane_px(px, x, y, (u + v) / P, x / 8, spec=True)
        for x in range(8):
            u = (x + 0.5) / 2 + 4.0
            _cane_px(px, 16 + x, y, (u + v) / P, x / 8)
        for x in range(8, 16):    # columns 8..15: end caps (white, rimmed in red)
            px[x, y] = rgba(WHITE[3])
    end = canvas(16)
    epx = end.load()
    for y in range(16):
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5))
            epx[x, y] = rgba(RED[2] if d > 6 else (WHITE[4] if d < 2.5 else WHITE[3]))
    save(img, "cane")
    save(end, "cane_end")

    # Calmer inventory version: two-tone bands without the pinstripe (it aliases at 1x).
    gui = canvas(32)
    gpx = gui.load()
    for y in range(32):
        v = (y + 0.5) / 2
        for x in range(32):
            u = (x % 16 + 0.5) / 2 + (4.0 if x >= 16 else 0.0)
            band = 1 if ((u + v) / P) % 1.0 < 0.48 else 0
            t = _tube_tone((x % 8) / 8)
            gpx[x, y] = rgba(RED[min(5, t + 1)] if band else WHITE[min(5, t + 1)])
    save(gui, "cane_gui")


def paint_shaft():
    """The handle's spiral: u runs once round the square shaft (south, east, north,
    west; 2.5 units each, 5 px), so stripes meet at every corner and at the wrap."""
    img = canvas(32)
    px = img.load()
    for y in range(32):
        v = (y + 0.5) / 2
        for x in range(20):
            u = (x + 0.5) / 2
            frac = (x % 5) / 5
            band = _stripe((u + v) / SHAFT_P)
            t = (2, 4, 5, 3, 1)[x % 5]
            if band == 1:
                c = RED[t]
            elif band == 2:
                c = RED[max(0, t - 1)]
            elif band == 3:
                c = mix(RED[t], WHITE[t], 0.55)
            else:
                c = WHITE[t]
            px[x, y] = rgba(c)
    fill(img, (20, 0, 31, 31), WHITE[3])
    save(img, "shaft")


def _mint_disc(size: int, wedges: int, swirl: float, t: float | None):
    """A star mint seen face on: curved red wedges on sugar white, domed shading, a
    white rim; t adds the travelling gloss for the animation."""
    img = canvas(size)
    px = img.load()
    c = size / 2
    rad = c - 0.5
    for y in range(size):
        for x in range(size):
            dx, dy = x + 0.5 - c, y + 0.5 - c
            r = math.hypot(dx, dy) / rad
            if r > 1.0:
                continue
            a = math.atan2(dy, dx) + swirl * r
            wedge = (0.5 + 0.5 * math.cos(wedges * a)) > (0.38 + 0.25 * r)
            lit = -(dx * 0.55 + dy * 0.65) / rad   # light from the upper left
            dome = lit * 0.9 + (1 - r) * 0.6
            if r > 0.9:
                tone = 3 if lit > -0.2 else 2
                col = WHITE[tone]
            elif r < 0.16:
                col = WHITE[5] if lit > -0.1 else WHITE[4]
            elif wedge:
                tone = 4 if dome > 0.75 else (3 if dome > 0.25 else (2 if dome > -0.25 else 1))
                col = RED[tone]
            else:
                tone = 5 if dome > 0.6 else (4 if dome > 0.0 else 3)
                col = WHITE[tone]
            if 0.86 < r <= 0.9:
                col = mix(col, WHITE[1], 0.5)
            px[x, y] = rgba(col)
    # a fixed glossy fleck on the upper left
    for hx, hy in ((0.30, 0.25), (0.34, 0.25), (0.30, 0.29)):
        x, y = int(size * hx), int(size * hy)
        if px[x, y][3]:
            px[x, y] = rgba("#ffffff")
    if t is not None:
        img = shine(img, t, colour="#ffffff", width=size / 7, strength=0.55, angle=35, pause=0.55)
        sparkle(img, int(size * 0.30), int(size * 0.27), wave(t, 0.15) * 0.9, reach=2)
    return img


def paint_mint():
    save_animation(animate(lambda t: _mint_disc(32, 7, 1.15, t), 16), "mint", frametime=3)
    save(_mint_disc(32, 7, 1.15, None), "mint_still")
    save_animation(animate(lambda t: _mint_disc(32, 6, 1.0, (t + 0.5) % 1.0), 16), "mint_small", frametime=3)
    # The mint's edge: short red and white bands where the wedges reach the rim.
    edge = canvas(32)
    px = edge.load()
    for y in range(32):
        for x in range(32):
            band = (x // 3) % 2
            t = (3, 4, 4, 3, 2, 2, 1, 1)[y % 8] if y < 8 else 3
            px[x, y] = rgba(RED[t] if band else WHITE[min(5, t + 1)])
    save(edge, "mint_edge")


def paint_gold():
    img = canvas(32, fill=GOLD[3])
    for y in range(32):
        c = (GOLD[5], GOLD[4], GOLD[4], GOLD[3], GOLD[3], GOLD[2], GOLD[2], GOLD[3])[y % 8]
        fill(img, (0, y, 31, y), c)
    px = img.load()
    for x, y in ((3, 1), (17, 9), (26, 17), (9, 25), (22, 1), (12, 17)):
        px[x, y] = rgba(SPARK)
    save(img, "gold")
    cap = canvas(16, fill=GOLD[4])
    fill(cap, (0, 0, 15, 1), GOLD[5])
    fill(cap, (0, 14, 15, 15), GOLD[2])
    save(cap, "gold_cap")
    # Beaded band (a run of gold pearls with a dark seam) for the collars.
    bead = canvas(32, fill=GOLD[2])
    bp = bead.load()
    for y in range(32):
        for x in range(32):
            k = x % 4
            bp[x, y] = rgba((GOLD[1], GOLD[4], GOLD[5], GOLD[3])[k])
            if y % 8 == 0:
                bp[x, y] = rgba(GOLD[1])
    save(bead, "gold_bead")


def paint_frost():
    """Glittering frosting: soft snow with blue-grey hollows, twinkles that come and go."""
    rng = random.Random(41)
    base = canvas(32, fill=SNOW[2])
    px = base.load()
    for y in range(32):
        for x in range(32):
            r = rng.random()
            if r < 0.07:
                px[x, y] = rgba(SNOW[0])
            elif r < 0.20:
                px[x, y] = rgba(SNOW[1])
            elif r < 0.45:
                px[x, y] = rgba(SNOW[3])
            elif r < 0.55:
                px[x, y] = rgba(SNOW[4])
    for y in range(0, 4):    # the lit crest of the cap
        for x in range(32):
            if px[x, y][:3] != rgba(SNOW[1])[:3]:
                px[x, y] = rgba(SNOW[4] if (x + y) % 4 else SNOW[3])
    for y in range(26, 32):    # the underside of the cap rolls into shadow
        for x in range(32):
            px[x, y] = rgba(SNOW[2] if (x + y) % 3 else SNOW[1])
    stars = [(rng.randrange(2, 30), rng.randrange(2, 24), rng.random()) for _ in range(9)]

    def frame(t):
        img = base.copy()
        for sx, sy, off in stars:
            k = max(0.0, wave(t, off) * 1.5 - 0.5)
            sparkle(img, sx, sy, k, colour="#ffffff", reach=2)
            if k > 0.5:
                img.load()[sx, sy] = rgba("#fff6c0")
        p = img.load()
        for sx, sy, off in stars[:5]:
            if wave(t, off + 0.5) > 0.65:
                p[(sx + 7) % 32, (sy + 11) % 26] = rgba("#9fd4ff")
        return img

    save_animation(animate(frame, 12), "frost", frametime=3)
    save(base, "frost_still")


def paint_holly():
    pal = {"a": HOLLY[0], "b": HOLLY[1], "c": HOLLY[2], "d": HOLLY[3], "e": HOLLY[4], "f": HOLLY[5],
           "v": mix(HOLLY[5], "#e8f0b0", 0.4)}
    # Upper and lower halves of one holly leaf (vein along the bottom/top row), pointing right.
    upper = art([
        "....aa....aa....aa..",
        "...abda..abda..abda.",
        "..abddba.abddbabddea",
        ".abddeedbdddeedddeea",
        "abcddeefddddeeffeeea",
        "vvvvvvvvvvvvvvvvvvvv",
    ], pal)
    lower = art([
        "vvvvvvvvvvvvvvvvvvvv",
        "abbccccbbbcccbbbccca",
        ".abbcccbbbccbbbbcca.",
        "..abccba.abcba.aba..",
        "...abba...aba...a...",
        "....aa.....a........",
    ], pal)
    img = canvas(32)
    img.paste(upper, (0, 0))
    img.paste(lower, (0, 6))
    save(img, "holly")
    berry = canvas(16, fill=BERRY[2])
    bp = berry.load()
    for y in range(16):
        for x in range(16):
            if (x + y) % 5 == 0:
                bp[x, y] = rgba(BERRY[1])
    save(berry, "berry")
    top = canvas(16, fill=BERRY[3])
    tp = top.load()
    for x, y in ((3, 3), (4, 3), (3, 4)):
        tp[x, y] = rgba("#ffd6d6")
    tp[8, 8] = rgba(BERRY[0])
    save(top, "berry_top")


def paint_ribbon():
    """Satin ribbon: a sheen down the middle, darker folds at the edges, gold edging."""
    img = canvas(32)
    px = img.load()
    prof = (GOLD[4], RIBBON[1], RIBBON[2], RIBBON[3], RIBBON[4], RIBBON[5], RIBBON[4], RIBBON[3],
            RIBBON[2], RIBBON[2], RIBBON[1], GOLD[3])
    for y in range(32):
        for x in range(32):
            c = prof[x % 12] if x < 24 else RIBBON[2]
            if x < 24 and y % 9 == 4 and prof[x % 12] in (RIBBON[4], RIBBON[5]):
                c = RIBBON[3]   # soft crease
            px[x, y] = rgba(c)
    save(img, "ribbon")
    knot = canvas(16, fill=RIBBON[3])
    kp = knot.load()
    for y in range(16):
        for x in range(16):
            if y % 4 == 0:
                kp[x, y] = rgba(RIBBON[1])
            elif y % 4 == 1:
                kp[x, y] = rgba(RIBBON[4])
    save(knot, "knot")


def textures() -> None:
    paint_cane()
    paint_shaft()
    paint_mint()
    paint_gold()
    paint_frost()
    paint_holly()
    paint_ribbon()


# ------------------------------------------------------------------------------------
# Geometry helpers
# ------------------------------------------------------------------------------------

def _dist(a, b):
    return math.sqrt(sum((a[i] - b[i]) ** 2 for i in range(len(a))))


def _stretch(p0, p1, f):
    mid = [(p0[i] + p1[i]) / 2 for i in range(3)]
    return ([mid[i] + (p0[i] - mid[i]) * f for i in range(3)],
            [mid[i] + (p1[i] - mid[i]) * f for i in range(3)])


def _clamp_uv(uv):
    return [max(0.0, min(16.0, v)) for v in uv]


def seg(p0, p1, w, d, tex, front=(0, 0), side=(8, 0), end=(0, 0), glow=0, end_tex=None, skip=(),
        fw=None, sw=None, shade=True):
    """A bar with explicit uv windows (see orchard_pickaxe.seg)."""
    length = _dist(p0, p1)
    fu, fv = front
    su, sv = side
    eu, ev = end
    et = end_tex or tex
    fw = w if fw is None else fw
    sw = d if sw is None else sw
    faces = {
        "south": (tex, _clamp_uv([fu, fv, fu + fw, fv + length])),
        "north": (tex, _clamp_uv([fu + fw, fv, fu, fv + length])),
        "west": (tex, _clamp_uv([su, sv, su + sw, sv + length])),
        "east": (tex, _clamp_uv([su + sw, sv, su, sv + length])),
        "up": (et, _clamp_uv([eu, ev, eu + 4, ev + 4])),
        "down": (et, _clamp_uv([eu, ev, eu + 4, ev + 4])),
    }
    return bar(p0, p1, w, d, tex, faces=faces, glow=glow, skip=skip, shade=shade)


def plane(x0, y0, x1, y1, tex, uv, glow=0, shade=True):
    """A zero-thickness quad at z = 0, the same texels on both sides (a cutout)."""
    u0, v0, u1, v1 = uv
    return box((x0, y0, 0), (x1, y1, 0), tex,
               faces={"south": (tex, [u0, v0, u1, v1]), "north": (tex, [u1, v0, u0, v1])},
               skip=("east", "west", "up", "down"), glow=glow, shade=shade)


def _bezier2(a, b, c, t):
    s = 1 - t
    return tuple(s * s * a[i] + 2 * s * t * b[i] + t * t * c[i] for i in range(2))


def cane_path():
    """The right cane's centre line (x, y) from the hub out to the crook's blunt end."""
    a, b, c = (9.6, 19.4), (15.0, 21.8), (20.4, 21.6)
    pts = [_bezier2(a, b, c, i / 7) for i in range(8)]
    centre, r = (20.4, 18.1), 3.5
    for k in range(1, 15):
        ang = math.radians(90 - 176 * k / 14)
        pts.append((centre[0] + r * math.cos(ang), centre[1] + r * math.sin(ang)))
    return pts


# ------------------------------------------------------------------------------------
# Parts
# ------------------------------------------------------------------------------------

def cane(fine=True) -> list[dict]:
    """One candy cane arm (+X side) with its frosting cap and drips."""
    pts = cane_path()
    tex = "cane" if fine else "cane_gui"
    parts = []
    acc = 0.0
    n = len(pts) - 1
    rng = random.Random(7)
    for i in range(n):
        q0 = (pts[i][0], pts[i][1], 8.0)
        q1 = (pts[i + 1][0], pts[i + 1][1], 8.0)
        ln = _dist(q0, q1)
        p0, p1 = _stretch(q0, q1, 1.22 if i < 7 else 1.12)
        ls = _dist(p0, p1)
        start = acc - (ls - ln) / 2
        v0 = (-(start + ls)) % P
        dz = 0.04 * (i % 2)   # neighbours differ a hair so overlapping faces never z-fight
        parts.append(seg(p0, p1, CANE_W, CANE_W + dz, tex, front=(0, v0), side=(8, v0), end=(8, 0),
                         fw=4.0, sw=4.0, end_tex="cane"))
        # Frosting along the upper edge: only where the cane's outer side faces the sky.
        ux, uy = (q1[0] - q0[0]) / ln, (q1[1] - q0[1]) / ln
        nx, ny = -uy, ux     # left normal = the upper side for a cane running outward
        if fine is not None and ny > 0.28 and i >= 1:
            off = CANE_W / 2 + 0.1
            f0 = (q0[0] + nx * off, q0[1] + ny * off, 8.0)
            f1 = (q1[0] + nx * off, q1[1] + ny * off, 8.0)
            f0, f1 = _stretch(f0, f1, 1.3)
            thick = 0.6 + 0.5 * rng.random()
            frost_tex = "frost" if fine else "frost_still"
            parts.append(seg(f0, f1, thick, CANE_W - 1.0 + 0.03 * (i % 2), frost_tex, front=(0, 0),
                             side=(4, 0), end=(0, 0), fw=8.0, sw=8.0))
            if fine and i % 3 != 2:  # a narrower crest rounds the cap off, with gaps
                c0 = (f0[0] + nx * (thick / 2 + 0.1), f0[1] + ny * (thick / 2 + 0.1), 8.0)
                c1 = (f1[0] + nx * (thick / 2 + 0.1), f1[1] + ny * (thick / 2 + 0.1), 8.0)
                c0, c1 = _stretch(c0, c1, 0.9 + 0.12 * (i % 2))
                parts.append(seg(c0, c1, 0.5, CANE_W - 1.8, frost_tex, front=(0, 0), side=(4, 0), end=(0, 0),
                                 fw=8.0, sw=8.0))
            if fine and i % 3 == 1 and i < n - 2:   # a drip running down the front and back
                for sz in (1, -1):
                    dl = 1.0 + 0.8 * rng.random()
                    mx = (f0[0] + f1[0]) / 2 + ux * (rng.random() - 0.5)
                    my = (f0[1] + f1[1]) / 2 + uy * (rng.random() - 0.5)
                    z = 8.0 + sz * (CANE_W / 2 + 0.12)
                    parts.append(seg((mx, my, z), (mx - nx * dl, my - ny * dl, z), 0.85, 0.32, frost_tex,
                                     front=(2, 2), side=(2, 2), end=(2, 2), fw=1.7, sw=0.6))
                    parts.append(box((mx - nx * dl - 0.5, my - ny * dl - 0.5, z - 0.22),
                                     (mx - nx * dl + 0.5, my - ny * dl + 0.4, z + 0.22), frost_tex,
                                     offset=(4, 6)))
        acc += ln
    # The blunt end: a slightly smaller rounded cap with the cane's white end.
    (x0, y0), (x1, y1) = pts[-2], pts[-1]
    ux, uy = (x1 - x0) / _dist(pts[-2], pts[-1]), (y1 - y0) / _dist(pts[-2], pts[-1])
    parts.append(seg((x1, y1, 8), (x1 + ux * 0.55, y1 + uy * 0.55, 8), CANE_W - 0.6, CANE_W - 0.6, "cane",
                     front=(8, 0), side=(8, 0), end=(0, 0), fw=2.0, sw=2.0, end_tex="cane_end"))
    # Gold band where the cane meets the hub.
    a, b = pts[1], pts[2]
    ux, uy = (b[0] - a[0]) / _dist(a, b), (b[1] - a[1]) / _dist(a, b)
    mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
    parts.append(seg((mx - ux * 0.5, my - uy * 0.5, 8), (mx + ux * 0.5, my + uy * 0.5, 8), CANE_W + 0.7,
                     CANE_W + 0.7, "gold_bead", front=(0, 0), side=(0, 0), end=(0, 0), end_tex="gold"))
    return parts


def bow(centre, scale=1.0, bell=True) -> list[dict]:
    """A satin ribbon bow facing +Z: two loops, a knot, two notched tails and a bell."""
    cx, cy, cz = centre
    s = scale
    parts = []
    for sgn in (1, -1):
        loop = box((-2.5 * s, -0.85 * s, -0.4 * s), (0, 0.85 * s, 0.4 * s), "ribbon",
                   faces={"south": ("ribbon", [0, 0, 6, 4]), "north": ("ribbon", [6, 0, 0, 4])})
        inner = box((-1.9 * s, -0.45 * s, -0.55 * s), (-0.4 * s, 0.45 * s, 0.55 * s), "knot")
        g = [loop, inner]
        turn(g, -22, "z", (0, 0, 0))
        turn(g, 14, "y", (0, 0, 0))
        if sgn < 0:
            g = mirror(g, "x", 0)
        move(g, cx, cy, cz)
        parts += g
        tail = seg((cx + sgn * 0.3 * s, cy - 0.2 * s, cz - 0.05),
                   (cx + sgn * 1.25 * s, cy - 2.6 * s, cz + 0.15), 0.95 * s, 0.3 * s, "ribbon",
                   front=(0, 2), side=(24, 2), end=(24, 0), fw=6.0, sw=1.0)
        parts.append(tail)
    parts.append(box((cx - 0.6 * s, cy - 0.6 * s, cz - 0.55 * s), (cx + 0.6 * s, cy + 0.6 * s, cz + 0.6 * s),
                     "knot", offset=(4, 4)))
    if bell:
        by = cy - 1.55 * s
        parts += prism((cx, by, cz + 0.2), 0.62 * s, 1.1 * s, "gold", sides=8, cap="gold_cap")
        parts += prism((cx, by - 0.65 * s, cz + 0.2), 0.82 * s, 0.35 * s, "gold", sides=8, cap="gold_cap")
        parts.append(box((cx - 0.18, by - 1.05 * s, cz + 0.02), (cx + 0.18, by - 0.75 * s, cz + 0.38), "gold_cap"))
    return parts


def mint(centre, r, d, face_tex, fine=True) -> list[dict]:
    """A gold-rimmed star mint facing +Z and -Z."""
    cx, cy, cz = centre
    parts = prism(centre, r, d, "mint_edge", axis="z", sides=16, cap="mint_edge")
    parts += prism(centre, r + 0.55, d - 0.9, "gold", axis="z", sides=16, cap="gold_cap")
    half = r * 1.06
    for sz in (1, -1):
        p = plane(-half, -half, half, half, face_tex, [0, 0, 16, 16])
        if sz < 0:
            turn(p, 180, "y", (0, 0, 0))
        move(p, cx, cy, cz + sz * (d / 2 + 0.02))
        parts.append(p)
    if fine:  # gold studs round the rim, like a bezel setting
        for k in range(8):
            a = math.radians(22.5 + 45 * k)
            x, y = cx + (r + 0.45) * math.cos(a), cy + (r + 0.45) * math.sin(a)
            st = box((x - 0.4, y - 0.4, cz - d / 2 + 0.2), (x + 0.4, y + 0.4, cz + d / 2 - 0.2), "gold",
                     offset=(2, 1))
            parts.append(turn(st, 45, "z", (x, y, cz)))
    return parts


def holly_leaf(length=6.0, width=1.9, fold=22.0) -> list[dict]:
    """A holly leaf along +X from the origin, folded up along its vein."""
    up = plane(0, 0, length, width, "holly", [0, 0, 10, 3])
    lo = plane(0, -width, length, 0, "holly", [0, 3, 10, 6])
    turn(up, fold, "x", (0, 0, 0))
    turn(lo, -fold, "x", (0, 0, 0))
    return [up, lo]


def crown(fine=True) -> list[dict]:
    """Holly leaves fanned above the hub mint with a cluster of berries."""
    cx, cy, cz = HUB
    parts = []
    top = cy + MINT_R + 0.2
    leaves = ((24, 6.4, 0.9), (156, 6.4, -0.9), (60, 5.0, 0.4), (120, 5.0, -0.4)) if fine else \
        ((26, 6.4, 0.9), (154, 6.4, -0.9))
    for ang, ln, dz in leaves:
        lf = holly_leaf(ln, width=1.9 if ln > 6 else 1.6)
        turn(lf, ang, "z", (0, 0, 0))
        tilt = 12 if fine else 25
        turn(lf, tilt if dz > 0 else -tilt, "x", (0, 0, 0))
        move(lf, cx, top - 0.6, cz + dz * 0.5)
        parts += lf
    berries = ((0, 0.9, 0.5, 0.85), (-1.0, 0.2, -0.3, 0.75), (1.05, 0.25, -0.2, 0.75)) if fine else \
        ((0, 0.9, 0.5, 0.95),)
    for bx, by, bz, br in berries:
        x, y, z = cx + bx, top + by, cz + bz
        for ex, ey, ez in ((br, br * 0.75, br * 0.75), (br * 0.75, br, br * 0.75), (br * 0.75, br * 0.75, br)):
            parts.append(box((x - ex, y - ey, z - ez), (x + ex, y + ey, z + ez), "berry",
                             faces={"up": "berry_top"}, offset=(2, 2)))
    return parts


def handle(fine=True) -> list[dict]:
    parts = []
    y0, y1 = HANDLE
    h = 2.5
    half = 1.25
    y = y1
    while y > y0 + 1e-6:
        ya = max(y0, y - 3.0)
        ln = y - ya
        v0 = ((y1 - y) % SHAFT_P)
        faces = {
            "south": ("shaft", [0, v0, 2.5, v0 + ln]),
            "east": ("shaft", [2.5, v0, 5.0, v0 + ln]),
            "north": ("shaft", [5.0, v0, 7.5, v0 + ln]),
            "west": ("shaft", [7.5, v0, 10.0, v0 + ln]),
            "up": ("shaft", [10, 0, 12.5, 2.5]),
            "down": ("shaft", [10, 0, 12.5, 2.5]),
        }
        parts.append(box((8 - half, ya, 8 - half), (8 + half, y + 0.01, 8 + half), "shaft", faces=faces))
        y = ya
    # gold collars: under the head, mid-grip and at the end
    parts += prism((8, y1 - 0.3, 8), 1.85, 1.4, "gold_bead", sides=8, cap="gold_cap")
    parts += prism((8, y1 + 0.8, 8), 2.25, 0.8, "gold", sides=8, cap="gold_cap")
    parts += prism((8, y0 + 0.35, 8), 1.85, 1.2, "gold_bead", sides=8, cap="gold_cap")
    parts += prism((8, y0 - 0.45, 8), 1.45, 0.6, "gold", sides=8, cap="gold_cap")
    if fine:
        parts += prism((8, 4.6, 8), 1.6, 0.5, "gold", sides=8, cap="gold_cap")
    return parts


def pommel(fine=True) -> list[dict]:
    cx, cy, cz = POMMEL
    face = "mint_small" if fine else "mint_still"
    parts = mint(POMMEL, POM_R, 2.6, face, fine=False)
    # a gold ring hanging it from the end collar
    parts += prism((8, cy + POM_R + 0.75, 8), 0.8, 1.1, "gold", sides=8, cap="gold_cap")
    if fine:
        for sz in (1, -1):
            b = bow((0, 0, 0), scale=0.62, bell=False)
            if sz < 0:
                turn(b, 180, "y", (0, 0, 0))
            move(b, cx, HANDLE[0] - 0.3, cz + sz * 1.85)
            parts += b
    return parts


def hub(fine=True) -> list[dict]:
    face = "mint" if fine else "mint_still"
    parts = mint(HUB, MINT_R if fine else MINT_R + 0.5, MINT_D, face, fine)
    # a gold cup tying the hub to the handle
    parts += prism((8, HUB[1] - MINT_R + 0.1, 8), 1.6, 1.2, "gold", sides=8, cap="gold_cap")
    return parts


def arm_bows() -> list[dict]:
    """A bow tied round each cane next to the hub (front and back)."""
    pts = cane_path()
    a, b = pts[2], pts[3]
    mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
    ang = math.degrees(math.atan2(b[1] - a[1], b[0] - a[0]))
    parts = [turn(seg((mx - 0.5, my, 8), (mx + 0.5, my, 8), CANE_W + 0.5, CANE_W + 0.5, "ribbon",
                      front=(24, 0), side=(24, 0), end=(24, 0), fw=1.0, sw=1.0), ang, "z", (mx, my, 8))]
    for sz in (1, -1):
        bw = bow((0, 0, 0), scale=1.15, bell=(sz > 0))
        if sz < 0:
            turn(bw, 180, "y", (0, 0, 0))
        turn(bw, ang, "z", (0, 0, 0))
        move(bw, mx, my, 8 + sz * (CANE_W / 2 + 0.45))
        parts += bw
    return parts


def build(fine=True) -> list[dict]:
    right = cane(fine) + (arm_bows() if fine else [])
    left = mirror(right, "x", 8)
    return handle(fine) + pommel(fine) + hub(fine) + crown(fine) + right + left


def _first_person(parts, size=0.5, turn_deg=32.0, shift=(-0.09, -0.025, 0.0)) -> dict:
    """Vanilla first-person pose, then the head turned about the handle so the mint face
    shows to the camera; the grip stays in the fist (see orchard_pickaxe)."""
    fp = display("pickaxe", parts, grip=GRIP, size=size)["firstperson_righthand"]
    m = display_matrix(fp["rotation"])
    sc = fp["scale"][0]
    g = [c / 16 - 0.5 for c in GRIP]
    target = [FIRST_PERSON_HAND[i] + fp["translation"][i] / 16 + sum(m[i][k] * g[k] * sc for k in range(3))
              + shift[i] for i in range(3)]
    a = math.radians(turn_deg)
    ry = ((math.cos(a), 0, math.sin(a)), (0, 1, 0), (-math.sin(a), 0, math.cos(a)))
    m2 = [[sum(m[i][k] * ry[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
    axes = {"y": tuple(m2[i][1] for i in range(3)), "z": tuple(m2[i][2] for i in range(3))}
    return place(axes, GRIP, tuple(target), sc, pose=None)


def models() -> dict:
    parts = build()
    disp = display("pickaxe", parts, grip=GRIP, size=0.76)
    disp["firstperson_righthand"] = _first_person(parts)
    icon = build(fine=False)
    return {"main": model(parts, disp), "gui": model(icon, display("pickaxe", icon, grip=GRIP, size=0.76))}
