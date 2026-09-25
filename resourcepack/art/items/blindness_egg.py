"""Blindness Egg: a glossy deep-purple egg blindfolded with shadow, a closed-eye sigil on its front.

The shell is the shared 16-sided egg body of the egg set. Its animated texture wraps the
egg twice (half a turn per repeat, the seams on facet edges): arms of ink-black shadow
spiral round the crown like a slow vortex and dark mist licks up from the base, over a
purple glaze. A band of shadow cloth is tied round the egg, riding high over the front
and dipping to a knot at the back, with two tails streaming off to one side. On the
band's front a decal carries the sigil: an almond eye shut on a glowing seam that slowly
parts on a pale, milky, blind eye and closes again. A white glint sits on the crown.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from art import kit
from art.kit import animate, box, canvas, display, lathe, model, place, rgba, save, save_animation, turn

ID = "blindness_egg"
NAME = "Blindness Egg"
KIND = "item"
COUNTERPART = "item/egg"

TAU = 2 * math.pi

# --------------------------------------------------------------------------------------
# Palette (darkest -> lightest), hand-tuned and hue-shifted
# --------------------------------------------------------------------------------------
SHELL = ["#1f0628", "#35083f", "#4f0b5b", "#6c0d78", "#8a0c96", "#aa00aa", "#c630cb", "#df6ee2", "#f6b8f6"]
INK = ["#030104", "#08040b", "#0f0714", "#170b1f", "#22102d", "#30163e", "#44205a"]
GLOW = ["#aa00aa", "#d23bd8", "#f080f2", "#ffc6ff", "#fff2ff"]
IRIS = ["#2a0536", "#5a0c6a", "#8f169c", "#c243c9"]
SCLERA = ["#a58cc4", "#cdb8e6", "#e8dcf6", "#fbf6ff"]
DEEP = [INK[1], INK[2], INK[3]] + SHELL      # the glaze ramp continued down into ink

# --------------------------------------------------------------------------------------
# The shared egg body
# --------------------------------------------------------------------------------------
EGG_PROFILE = [(0, 1.4), (0.7, 3.2), (1.8, 4.3), (3.3, 4.9), (5.0, 5.0), (6.8, 4.7), (8.4, 4.0),
               (9.8, 3.0), (10.9, 1.8), (11.5, 0.6)]
CENTRE = (8, 2.2, 8)
BASE_Y = CENTRE[1]
HEIGHT = EGG_PROFILE[-1][0]
SLICES = [(h0, h1, (r0 + r1) / 2) for (h0, r0), (h1, r1) in zip(EGG_PROFILE, EGG_PROFILE[1:])]
FACET = 22.5
HALF = math.tan(math.radians(FACET / 2))
SEAM = -78.75            # a facet edge; the half-turn textures repeat every 180 degrees from here
NORMALS = {"east": (1, 0, 0), "west": (-1, 0, 0), "south": (0, 0, 1), "north": (0, 0, -1)}
# Azimuth: 0 = the front (+Z, where the sigil is), 90 = +X. Heights h are measured up the
# egg from its base at model y = BASE_Y.

# --------------------------------------------------------------------------------------
# The blindfold, the sigil and the glint (model units)
# --------------------------------------------------------------------------------------
BAND_TALL = 3.6          # blindfold height
BAND_IN = 4.97           # inner apothem: clears the widest slice of the shell
BAND_OUT = BAND_IN + 0.26
BAND_SIDE_H = 5.1        # band centre height at the sides
BAND_TILT = 1.25         # it rides this much higher over the front and lower at the knot
PANEL_HW = BAND_OUT * HALF + 0.02

EYE_A = 3.25             # half-width of the eye
LINE_DIP = 1.15          # the lower rim sags this far below the corners
LID_RISE = 1.4           # the upper rim rises this far above the corners
SEAM_DIP = 0.2           # the shut lids meet on a nearly straight seam


def band_h(az: float) -> float:
    """Centre height of the tilted band at azimuth az."""
    return BAND_SIDE_H + BAND_TILT * math.cos(math.radians(az))


FRONT_Y = BASE_Y + band_h(0)                                  # model y of the band's front centre
EYE_YC = FRONT_Y - (LID_RISE - LINE_DIP) / 2 + 0.05           # model y of the eye's corners

# The crown glint is painted into the decal texture (texel coordinates) and shown through
# its own unshaded face on one crown facet, so it stays white under the item lighting.
GLINT = {(13, 10): "#ffffff", (12, 10): SHELL[8], (13, 9): SHELL[8], (14, 10): SHELL[7], (13, 11): SHELL[7],
         (12, 11): SHELL[6]}
GLINT_SLICE = 6          # the slice from h 8.4 to 9.8
GLINT_AZ = -22.5

SHELL_FRAMES, SHELL_TIME = 32, 3        # 96-tick loop
EYE_TIME = 2
# shut for most of the loop, a slow opening, a held stare, a quicker close (48 x 2 = 96 ticks)
EYE_ORDER = [0] * 27 + [1, 2, 3, 4, 5] + [5] * 10 + [4, 3, 2, 1] + [0] * 2
EYE_LEVELS = (0.0, 0.22, 0.45, 0.68, 0.88, 1.0)

SIZE = 1.35


# --------------------------------------------------------------------------------------
# Small helpers
# --------------------------------------------------------------------------------------

def clamp(v, a=0.0, b=1.0):
    return max(a, min(b, v))


def put(img, x, y, colour):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((int(x), int(y)), rgba(colour))


def slot_of(az: float) -> int:
    """Which 4-texel column (0..7) of a half-turn texture the facet at az uses."""
    return round(((az - FACET / 2 - SEAM) % 180.0) / FACET) % 8


def face_az(element: dict, side: str) -> float:
    m, _ = kit.rotation_of(element)
    n = kit._apply(m, NORMALS[side])
    return round(math.degrees(math.atan2(n[0], n[2])) / FACET) * FACET


def at(az: float, h: float, rad: float):
    a = math.radians(az)
    return (8 + rad * math.sin(a), BASE_Y + h, 8 + rad * math.cos(a))


def radial(az: float):
    a = math.radians(az)
    return (math.sin(a), 0.0, math.cos(a))


def _norm(v):
    n = math.sqrt(sum(c * c for c in v))
    return tuple(c / n for c in v)


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _dot(a, b):
    return sum(a[i] * b[i] for i in range(3))


# --------------------------------------------------------------------------------------
# Shell texture: a map of half the egg. x = azimuth (32 texels over 180 degrees, tiling
# sideways), y = height (the top of the egg at y = 0, the base at y = 32).
# Sliding the crown's smoke one repeat per loop spins it half a turn round the egg.
# --------------------------------------------------------------------------------------
GLAZE_ROWS = [7, 7, 6, 6, 6, 6, 6, 6, 5, 5, 5, 5, 5, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 3, 3, 3, 3, 3, 3, 2, 3, 4]
MIST_TOP = 22            # where the base mist starts to gather (row)
ASPECT = 1.37            # texture y is squashed on the egg; stretch shapes to stay round
# Crown wisps: tail x, tail y, heading (rad), length, curl (rad), sway, width, phase.
# Two per repeat (four round the egg); each spins a whole repeat round the crown per loop.
WISPS = [(0.0, 11.0, -0.28, 20.0, 7.0, 0.3, 2.1, 0.0),
         (16.0, 8.5, -0.3, 15.0, 6.5, 0.35, 1.6, 0.5)]


def glaze(x: int, y: int) -> int:
    """SHELL ramp index of the bare glaze: a bright crown (its facets stand upright, so the
    texture carries the light the curve would catch), a darker belly and a sheen column
    just left of the front, under the glint."""
    c = GLAZE_ROWS[y]
    dxs = ((x - 10 + 16) % 32) - 16
    if abs(dxs) <= 1 and 2 <= y <= 11:
        c = min(7, c + 1)
    return c


def wisp_path(length, curl, sway, flex, steps=100):
    """(x, y, s) along a smoke wisp from its tail (s = 0) to a curled head (s = 1)."""
    pts = []
    x = y = 0.0
    for i in range(steps + 1):
        s_ = i / steps
        k = max(0.0, (s_ - 0.5) / 0.5)
        heading = sway * math.sin(TAU * (0.8 * s_ + flex)) - curl * k ** 1.7
        pts.append((x, y, s_))
        step = length / steps * (1.0 - 0.55 * k)
        x += step * math.cos(heading)
        y += step * math.sin(heading)
    return pts


def smoke_levels(wisps, t: float, drift: int, n: int = 32, ss: int = 4) -> np.ndarray:
    """0-3 coverage levels (fringe, body, core) of curling wisps at loop phase t. Each wisp
    slides `drift` whole repeats sideways per loop, so the loop is seamless."""
    h = n * ss
    lvl = np.zeros((h, h), dtype=np.int8)
    yy, xx = np.mgrid[0:h, 0:h].astype(np.float32) / ss + 0.5 / ss
    for x0, y0, ang, length, curl, sway, width, phase in wisps:
        pts = wisp_path(length, curl * (1 + 0.12 * math.sin(TAU * (t + phase))), sway, t + phase)
        ca, sa = math.cos(ang), math.sin(ang)
        ox, oy = x0 + drift * n * t, y0 + 0.7 * math.sin(TAU * (t + phase))
        for px_, py_, s_ in pts:
            wx = ox + px_ * ca - py_ * sa
            wy = oy + (px_ * sa + py_ * ca) * ASPECT
            r = width * (0.3 + 0.7 * math.sin(math.pi * min(1.0, s_ * 1.3)) ** 0.6)
            if s_ > 0.75:
                r *= 1 - (s_ - 0.75) / 0.25 * 0.5
            r = max(r, 0.55)
            for dx in (-n, 0, n):
                cx = (wx % n) + dx
                if cx < -r - 2 or cx > n + r + 2:
                    continue
                d2 = (xx - cx) ** 2 + ((yy - wy) / 1.15) ** 2
                lvl = np.maximum(lvl, np.where(d2 < (0.5 * r) ** 2, 3, np.where(d2 < r * r, 2,
                                                                               np.where(d2 < (r + 0.9) ** 2, 1, 0))))
    out = np.zeros((n, n), dtype=np.int8)
    for y in range(n):
        for x in range(n):
            cell = lvl[y * ss:(y + 1) * ss, x * ss:(x + 1) * ss]
            out[y, x] = int(np.sort(cell, axis=None)[cell.size // 2])        # the median level
    return out


def mist(x: int, y: int, t: float, drift: int = 1) -> float:
    """Depth (0 = clear) of the mist pooled round the base, tongues licking upward."""
    tongue = 2.2 * (0.5 + 0.5 * math.sin(TAU * (x / 16.0 + drift * t))) \
        + 1.2 * math.sin(TAU * (x / 8.0 - 2 * drift * t) + 1.0)
    top_ = MIST_TOP + 3.0 - tongue
    return max(0.0, y - top_ + 0.001) if y >= top_ else 0.0


# Under-shadows painted on the shell, drifting against the veil above them.
UNDER = [(6.0, 9.5, -0.25, 17.0, 6.5, 0.3, 2.2, 0.3),
         (22.0, 6.5, -0.3, 13.0, 6.0, 0.35, 1.7, 0.8)]


def shell_frame(t: float) -> Image.Image:
    img = canvas(32)
    px = img.load()
    under = smoke_levels(UNDER, t, -1)
    for y in range(32):
        for x in range(32):
            base = glaze(x, y)
            m = mist(x, y, t, -1)
            shift = max(min(2, int(under[y, x])), 1 if 0 < m < 1.5 else (2 if m >= 1.5 else 0))
            px[x, y] = rgba(DEEP[max(0, base + 3 - shift)])
    return img


VEIL_ALPHA = {1: 70, 2: 135, 3: 190}
VEIL_TINT = {1: SHELL[1], 2: INK[3], 3: INK[1]}


def veil_frame(t: float) -> Image.Image:
    """The translucent smoke veil: ink wisps curling round the crown and mist rising round
    the base, softest at their purple-tinged edges."""
    img = canvas(32)
    px = img.load()
    crown = smoke_levels(WISPS, t, 1)
    for y in range(32):
        for x in range(32):
            k = int(crown[y, x])
            m = mist(x, y, t, 1)
            if m > 0:
                k = max(k, 1 if m < 1.2 else (2 if m < 3.5 else 3))
            if k:
                r, g, b, _ = rgba(VEIL_TINT[k])
                px[x, y] = (r, g, b, VEIL_ALPHA[k])
    return img


def paint_shell() -> None:
    save_animation(animate(shell_frame, SHELL_FRAMES), "shell", frametime=SHELL_TIME)
    save_animation(animate(veil_frame, SHELL_FRAMES), "veil", frametime=SHELL_TIME, interpolate=True)
    caps = canvas(16)
    # one flat colour per slice (column = slice number) so the stacked caps never flicker;
    # a step darker than the sides next to them, because caps face the light
    colours = [INK[3], SHELL[0], SHELL[1], SHELL[2], SHELL[3], SHELL[4], SHELL[4], SHELL[5], SHELL[5]]
    for i, c in enumerate(colours):
        for y in range(16):
            put(caps, i, y, c)
    save(caps, "caps")


# --------------------------------------------------------------------------------------
# Shadow cloth: rows 0-7 are the band (x = azimuth, a half-turn repeat); columns 0-7 of
# rows 8-31 the knot and tails (x across a tail, y along it, an angled cut at the end).
# --------------------------------------------------------------------------------------
TAIL_U = 3.6             # tail texture width in UV units


def paint_cloth() -> None:
    img = canvas(32)
    px = img.load()
    for y in range(8):
        for x in range(32):
            w = math.sin(TAU * (x / 16 + y / 10)) + 0.5 * math.sin(TAU * (x / 8 - y / 7))
            c = INK[3]
            if w > 0.9:
                c = INK[5]
            elif w > 0.35:
                c = INK[4]
            elif w < -0.9:
                c = INK[2]
            if y == 0:
                c = INK[6] if w > 0 else INK[5]
            elif y == 1:
                c = INK[4] if w < 0.9 else INK[5]
            elif y == 7:
                c = INK[1]
            px[x, y] = rgba(c)
    cols = [INK[6], INK[5], INK[4], INK[3], INK[3], INK[2], INK[2], INK[1]]
    for y in range(8, 32):
        for x in range(8):
            c = cols[x]
            if (y + x) % 5 == 0 and 1 <= x <= 5:
                c = INK[4] if x < 3 else INK[3]
            if y >= 29 and x < (y - 29) * 3:              # an angled cut across the end
                continue
            px[x, y] = rgba(c)
    save(img, "cloth")
    edge = canvas(16)
    for y in range(16):
        put(edge, 0, y, INK[4])
        put(edge, 1, y, INK[1])
    save(edge, "cloth_edge")


# --------------------------------------------------------------------------------------
# The sigil decal: a 32 px frame projected straight onto the front, texel
# (2x, 2(EYE_YC + 8 - y)). An almond eye with a glowing rim; shut, its lids meet on a
# bright seam. It slowly parts on a pale, clouded (blind) eye with a purple iris.
# --------------------------------------------------------------------------------------

def to_tex(x: float, y: float):
    return 2 * x, 2 * (EYE_YC + 8 - y)


def arch(dx: float) -> float:
    return math.cos(math.pi / 2 * clamp(abs(dx) / EYE_A))


def lower(dx: float) -> float:
    return EYE_YC - LINE_DIP * arch(dx)


def top(dx: float) -> float:
    return EYE_YC + LID_RISE * arch(dx)


def seam(dx: float) -> float:
    return EYE_YC - SEAM_DIP * arch(dx)


def lids(dx: float, openness: float):
    """(lower lid, upper lid) edges at dx: they part from the seam out to the rims."""
    s = seam(dx)
    return s + openness * (lower(dx) - s), s + openness * (top(dx) - s)


def stroke(img, pts, colour):
    """Mark every texel a polyline (model units) passes through."""
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        steps = max(2, int(math.hypot(x1 - x0, y1 - y0) * 8))
        for i in range(steps + 1):
            f = i / steps
            tx, ty = to_tex(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)
            put(img, math.floor(tx), math.floor(ty), colour)


def eye_frame(openness: float) -> Image.Image:
    img = canvas(32)
    px = img.load()
    iris_y = EYE_YC + 0.1
    for ty in range(32):
        for tx in range(32):
            x, y = (tx + 0.5) / 2, EYE_YC + 8 - (ty + 0.5) / 2
            dx = x - 8
            if abs(dx) >= EYE_A or not lower(dx) < y < top(dx):
                continue
            lo, hi = lids(dx, openness)
            if y >= hi:                                     # the upper lid, lit on its bulge
                c = SHELL[3] if y > (hi + top(dx)) / 2 else SHELL[2]
            elif y <= lo:                                   # the lower lid
                c = SHELL[1]
            else:                                           # the eyeball
                d = math.hypot(dx, (y - iris_y) * 0.95)
                if d < 0.45:
                    c = SCLERA[1]                           # clouded, blind pupil
                elif d < 1.2:
                    c = IRIS[3] if y < iris_y - 0.25 else IRIS[2]
                elif y > hi - 0.55:
                    c = SCLERA[0]                           # shadow under the lid
                elif abs(dx) > EYE_A - 0.9:
                    c = SCLERA[1]                           # the corners turn away
                else:
                    c = SCLERA[2]
                if openness > 0.6 and (tx, ty) == (15, 15):
                    c = "#ffffff"                           # a wet glint on the iris
            px[tx, ty] = rgba(c)
    xs = [EYE_A * (i / 40 * 2 - 1) for i in range(41)]
    rim = GLOW[1] if openness < 0.3 else GLOW[3]
    stroke(img, [(8 + dx, top(dx)) for dx in xs], rim)
    stroke(img, [(8 + dx, lower(dx)) for dx in xs], rim)
    for side in (-1, 1):                                    # the corners sweep out into flicks
        stroke(img, [(8 + side * (EYE_A - 0.1), EYE_YC + 0.05), (8 + side * (EYE_A + 0.55), EYE_YC + 0.4)], rim)
    inner = xs[3:-3]
    if openness == 0:
        stroke(img, [(8 + dx, seam(dx)) for dx in inner], GLOW[4])
    else:
        stroke(img, [(8 + dx, lids(dx, openness)[1]) for dx in inner], GLOW[4])
        stroke(img, [(8 + dx, lids(dx, openness)[0]) for dx in inner], GLOW[3])
    img = kit.outline(img, SHELL[1] if openness < 0.3 else SHELL[2], diagonal=True)
    for (gx, gy), c in GLINT.items():
        put(img, gx, gy, c)
    return img


def paint_eye() -> None:
    save_animation([eye_frame(o) for o in EYE_LEVELS], "eye", frametime=EYE_TIME, order=EYE_ORDER)


def textures() -> None:
    paint_shell()
    paint_cloth()
    paint_eye()


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def egg_body() -> list[dict]:
    """The shared lathe egg; each facet shows its own column of the shell map."""
    parts = lathe(CENTRE, EGG_PROFILE, "shell", sides=16, cap="caps")
    for e in parts:
        h0, h1 = e["from"][1] - BASE_Y, e["to"][1] - BASE_Y
        k = min(range(len(SLICES)), key=lambda i: abs(SLICES[i][0] - h0) + abs(SLICES[i][1] - h1))
        for side, face in e["faces"].items():
            if side in ("up", "down"):
                face["uv"] = [k, 0, k + 1, 1]
                continue
            slot = slot_of(face_az(e, side))
            v0, v1 = (HEIGHT - h1) / HEIGHT * 16, (HEIGHT - h0) / HEIGHT * 16
            face["uv"] = [2 * slot, round(v0, 4), 2 * slot + 2, round(v1, 4)]
    return parts


def band_roll(az: float) -> float:
    """In-plane tilt (degrees) that lines a band panel up with the band's slope."""
    slope = -BAND_TILT * math.sin(math.radians(az)) / BAND_OUT
    return math.degrees(math.atan(slope))


def blindfold() -> list[dict]:
    """Sixteen cloth panels round the egg, following the tilted band."""
    parts = []
    for k in range(16):
        az = k * FACET
        yc = BASE_Y + band_h(az)
        y0, y1 = yc - BAND_TALL / 2, yc + BAND_TALL / 2
        slot = slot_of(az)
        faces = {"south": ("cloth", [2 * slot, 0, 2 * slot + 2, round(BAND_TALL, 4)]),
                 "up": ("cloth_edge", [0, 0, 1, 1]), "down": ("cloth_edge", [1, 0, 2, 1]),
                 "east": ("cloth_edge", [1, 0, 2, 1]), "west": ("cloth_edge", [1, 0, 2, 1])}
        e = box((8 - PANEL_HW, y0, 8 + BAND_IN), (8 + PANEL_HW, y1, 8 + BAND_OUT), "cloth", faces=faces,
                skip=("north",))
        roll = band_roll(az)
        if abs(roll) > 1e-3:
            turn(e, roll, "z", (8, yc, 8 + BAND_OUT))
        if az:
            turn(e, az, "y", (8, 0, 8))
        parts.append(e)
    return parts


def facet_decal(y0: float, y1: float, r: float, az: float, tex: str, glow: int = 0) -> dict:
    """One unshaded face lying just proud of a facet at azimuth az, with the decal texture
    projected straight on from the front (u = model x, v = EYE_YC + 8 - model y)."""
    hw = r * HALF
    a = math.radians(az)
    c, s = math.cos(a), math.sin(a)
    xl, xr = 8 - c * hw + s * r, 8 + c * hw + s * r
    uv = [round(clamp(xl, 0, 16), 4), round(EYE_YC + 8 - y1, 4), round(clamp(xr, 0, 16), 4),
          round(EYE_YC + 8 - y0, 4)]
    e = box((8 - hw, y0, 8 + r - 0.02), (8 + hw, y1, 8 + r), tex, faces={"south": (tex, uv)},
            skip=("north", "east", "west", "up", "down"), glow=glow, shade=False)
    if az:
        turn(e, az, "y", (8, 0, 8))
    return e


def sigil() -> list[dict]:
    """The eye decal on the five front panels (kept inside each rolled panel), and the
    crown glint on its facet."""
    parts = []
    for k in (-2, -1, 0, 1, 2):
        az = k * FACET
        yc = BASE_Y + band_h(az)
        inset = PANEL_HW * abs(math.sin(math.radians(band_roll(az)))) + 0.05
        parts.append(facet_decal(yc - BAND_TALL / 2 + inset, yc + BAND_TALL / 2 - inset, BAND_OUT + 0.05, az, "eye",
                                 glow=15))
    h0, h1, r = SLICES[GLINT_SLICE]
    parts.append(facet_decal(BASE_Y + h0, BASE_Y + h1, r + 0.03, GLINT_AZ, "eye"))
    return parts


def frame_box(centre, ex, ey, ez, size, tex, faces=None, skip=(), glow=0):
    """A box of size (sx, sy, sz) centred on `centre` whose local x, y, z axes point
    along the orthonormal world directions ex, ey, ez."""
    sx, sy, sz = size
    cx, cy, cz = centre
    e = box((cx - sx / 2, cy - sy / 2, cz - sz / 2), (cx + sx / 2, cy + sy / 2, cz + sz / 2), tex,
            faces=faces, skip=skip, glow=glow)
    m = ((ex[0], ey[0], ez[0]), (ex[1], ey[1], ez[1]), (ex[2], ey[2], ez[2]))
    kit._set_rotation(e, m, centre)
    return e


EDGE = {s: ("cloth_edge", [1, 0, 2, 1]) for s in ("east", "west", "up", "down")}


def tail(spec, widths, thick=0.28) -> list[dict]:
    """A cloth tail through (azimuth, height, lift off the band) points. Its flat side
    faces away from the egg at the root and turns toward the front as it streams out, so
    it reads as a ribbon from the front; textured from the root to the cut end."""
    pts = [at(az, h, BAND_OUT + lift) for az, h, lift in spec]
    total = sum(math.dist(a, b) for a, b in zip(pts, pts[1:]))
    v = 16 - total
    parts = []
    for i in range(len(pts) - 1):
        p0, p1 = pts[i], pts[i + 1]
        seg = math.dist(p0, p1)
        ey = _norm(tuple(p0[j] - p1[j] for j in range(3)))          # local +y points back to the root
        rad = radial((spec[i][0] + spec[i + 1][0]) / 2)
        k = i / max(1, len(pts) - 2)
        facing = _norm(tuple(rad[j] * (1 - 0.75 * k) + (0.0, 0.25, 1.0)[j] * 0.75 * k for j in range(3)))
        ez = _norm(tuple(facing[j] - _dot(facing, ey) * ey[j] for j in range(3)))
        ex = _cross(ey, ez)
        centre = tuple((p0[j] + p1[j]) / 2 for j in range(3))
        w = (widths[i] + widths[i + 1]) / 2
        v0, v1 = round(v, 4), round(min(16.0, v + seg), 4)
        faces = {"south": ("cloth", [0, v0, TAIL_U, v1]), "north": ("cloth", [TAIL_U, v0, 0, v1]), **EDGE}
        parts.append(frame_box(centre, ex, ey, ez, (w, seg + (0.3 if i < len(pts) - 2 else 0.0), thick),
                               "cloth", faces=faces))
        v += seg
    return parts


def knot() -> list[dict]:
    """The knot at the back of the band and two tails streaming off to the right."""
    hk = band_h(180)
    back = radial(180)
    up = (0.0, 1.0, 0.0)
    side = _cross(up, back)
    parts = []
    for size, roll in (((2.3, 1.9, 1.0), 0.0), ((1.8, 1.8, 1.25), 45.0)):
        c = at(180, hk + 0.05, BAND_OUT + size[2] / 2 - 0.15)
        a = math.radians(roll)
        ex = tuple(side[j] * math.cos(a) + up[j] * math.sin(a) for j in range(3))
        ey = tuple(-side[j] * math.sin(a) + up[j] * math.cos(a) for j in range(3))
        faces = {s: ("cloth", [0.4, 8.5, 2.9, 10.5]) for s in ("north", "south", "east", "west", "up", "down")}
        parts.append(frame_box(c, ex, ey, back, size, "cloth", faces=faces))
    # the band gathers into the knot
    for sgn in (-1, 1):
        for dh in (0.75, -0.75):
            p0 = at(180 + sgn * 17, hk + dh * 1.6, BAND_OUT + 0.05)
            p1 = at(180 + sgn * 5, hk + dh * 0.45, BAND_OUT + 0.35)
            ey = _norm(tuple(p1[j] - p0[j] for j in range(3)))
            f = radial(180 + sgn * 11)
            ez = _norm(tuple(f[j] - _dot(f, ey) * ey[j] for j in range(3)))
            ex = _cross(ey, ez)
            c = tuple((p0[j] + p1[j]) / 2 for j in range(3))
            faces = {"south": ("cloth", [0, 12, 1.2, 14]), **EDGE}
            parts.append(frame_box(c, ex, ey, ez, (0.9, math.dist(p0, p1) + 0.3, 0.4), "cloth", faces=faces,
                                   skip=("north",)))
    parts += tail([(180, hk + 0.2, 0.45), (165, hk + 0.35, 0.85), (149, hk + 0.2, 1.35), (133, hk - 0.3, 1.95),
                   (118, hk - 0.9, 2.55)], [1.9, 1.85, 1.75, 1.6, 1.45])
    parts += tail([(181, hk - 0.3, 0.4), (170, hk - 0.9, 0.8), (157, hk - 1.6, 1.25), (144, hk - 2.5, 1.65)],
                  [1.75, 1.7, 1.6, 1.45])
    return parts


VEIL_SLICES = (0, 1, 2, 5, 6, 7)       # base and crown slices the smoke veil wraps
VEIL_GAP = 0.14


def veil() -> list[dict]:
    """A thin translucent skin over the crown and base carrying the drifting smoke. It is
    listed last so it blends over everything opaque; its caps are left open."""
    parts = []
    for k in VEIL_SLICES:
        h0, h1, r = SLICES[k]
        slabs = lathe((8, BASE_Y + h0, 8), [(0, r + VEIL_GAP), (h1 - h0, r + VEIL_GAP)], "veil", sides=16)
        for e in slabs:
            e["faces"].pop("up", None)
            e["faces"].pop("down", None)
            for side, face in e["faces"].items():
                slot = slot_of(face_az(e, side))
                v0, v1 = (HEIGHT - h1) / HEIGHT * 16, (HEIGHT - h0) / HEIGHT * 16
                face["uv"] = [2 * slot, round(v0, 4), 2 * slot + 2, round(v1, 4)]
        parts += slabs
    return parts


def build() -> list[dict]:
    return egg_body() + blindfold() + sigil() + knot() + veil()


def models() -> dict:
    parts = build()
    d = display(KIND, parts, size=SIZE, gui_rotation=(8, -18, 0), gui_span=15.4)
    centre = (8, 8, 8)
    # held with the sigil facing forward, a little outward
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0.2, 0, 1)}, centre, "fist", 0.45 * SIZE)
    # first person: the sigil turned to the camera, clear of the crosshair
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (0.12, 0, 1)}, centre, (0.47, -0.31, -0.95),
                                       0.55 * 1.25, pose=None)
    return {"main": model(parts, d)}
