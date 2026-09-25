"""Teleport Egg: a violet egg with a portal vortex set into its face (Egg set).

The shared egg shell (lathe of EGG_PROFILE, 16 sides) wears a speckled, pearly violet
glaze. Its UVs follow the egg round (azimuth -> u, height -> v, whole texels per face),
so one painted map per half wraps the shell seamlessly. A glowing twin of the shell
floats a hair above it and carries everything that moves: the swirling energy of the
entry vortex on the front (three dark streaks spiralling into a white-hot core), the
smaller counter-turning exit vortex on the back, and portal motes drifting up the shell;
glowing strips carry the swirl unbroken over the shell's steps. A closed obsidian ring
hugs the curved shell round each portal, with raised keystones holding magenta gems.
Five rune glyphs ride a tilted orbit round the egg; a flare runs round the orbit,
lighting each glyph and its comet trail in turn.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, copy, display, fit, lathe, matrix_to_euler, model, place, rgba,
                     save, save_animation, turn)

ID = "teleport_egg"
NAME = "Teleport Egg"
KIND = "item"
COUNTERPART = "item/egg"

# --------------------------------------------------------------------------------------
# Palettes, darkest -> lightest (shadows drift to indigo, lights to pink)
# --------------------------------------------------------------------------------------
SHELL = ["#12022c", "#200452", "#32087c", "#4a0cab", "#650fd6", "#860df3", "#aa00ff", "#bd38ff",
         "#d26cff", "#e896ff", "#fbd4ff"]
VOID = ["#05010f", "#0b0224", "#14033f", "#1f065d", "#2c0a7e"]
GLOW = ["#3a0b9c", "#5d12d8", "#8424ff", "#a94bff", "#cb7dff", "#e8b3ff", "#fbe4ff", "#ffffff"]
GEM = ["#6a0a8c", "#b0139e", "#e82dc0", "#ff6ee0", "#ffb8f0", "#ffffff"]
OBSIDIAN = ["#07030e", "#120a1f", "#1e1231", "#2d1c48", "#422a66", "#5e428c", "#9278c4"]

# --------------------------------------------------------------------------------------
# The shared egg body
# --------------------------------------------------------------------------------------
EGG_PROFILE = [(0, 1.4), (0.7, 3.2), (1.8, 4.3), (3.3, 4.9), (5.0, 5.0), (6.8, 4.7), (8.4, 4.0),
               (9.8, 3.0), (10.9, 1.8), (11.5, 0.6)]
EGG_CENTER = (8, 2.2, 8)
EGG_TOP = EGG_PROFILE[-1][0]
SLICES = [(h0, h1, (r0 + r1) / 2) for (h0, r0), (h1, r1) in zip(EGG_PROFILE, EGG_PROFILE[1:])]

# The shell map: every frame is N x N. Each half of the egg (8 faces of 22.5 degrees) spans
# the full width, 4 texels per face; each slice gets a whole number of rows.
N = 32
FRAMES, FRAMETIME = 32, 2
ROWS = (2, 3, 4, 5, 5, 4, 4, 3, 2)                        # rows per slice, bottom to top
ROW0 = [sum(ROWS[k + 1:]) for k in range(len(ROWS))]      # first row (from the top) of a slice
HALF = {"front": -101.25, "back": 78.75}                  # azimuth at u = 0; each half spans 180

# Portals: centre (azimuth, height on the egg), inner/outer radius of the frame ring.
FRONT = {"name": "front", "phi": 0.0, "h": 5.5, "r_in": 2.45, "r_out": 3.35, "arms": 3, "twist": 4.5,
         "flow": 1, "segments": 16, "keys": ((90.0, 1.2), (210.0, 0.9), (330.0, 0.9))}
BACK = {"name": "back", "phi": 180.0, "h": 5.7, "r_in": 1.8, "r_out": 2.5, "arms": 2, "twist": 3.6,
        "flow": -1, "segments": 12, "keys": ((90.0, 0.9), (270.0, 0.9))}
PORTALS = (FRONT, BACK)

RING_H, KEY_H, SINK = 1.0, 1.35, 0.5
OVERLAY_EPS = 0.05
OVERLAY_SEAM = round(OVERLAY_EPS * math.tan(math.pi / 16) + 0.004, 4)
LEDGE_LIFT = 0.02

# Rune glyphs on a tilted orbit round the egg; angle 0 is the front, 90 is +X.
ORBIT_C, ORBIT_R, ORBIT_TILT = (8.0, 6.2, 8.0), 6.1, 22.0
RUNES = tuple(36.0 + 72.0 * j for j in range(5))
RUNE_SIZE = 1.45

SIZE = 1.3


# --------------------------------------------------------------------------------------
# Small vector helpers
# --------------------------------------------------------------------------------------

def _norm(v):
    length = math.sqrt(sum(c * c for c in v))
    return tuple(c / length for c in v)


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def _add(a, b, k=1.0):
    return tuple(a[i] + b[i] * k for i in range(3))


def clamp(v, lo=0.0, hi=1.0):
    return max(lo, min(hi, v))


# --------------------------------------------------------------------------------------
# Shell geometry: texel <-> egg surface
# --------------------------------------------------------------------------------------

def wrap(phi: float) -> float:
    """Azimuth into [-101.25, 258.75): the front half first, then the back half."""
    return (phi + 101.25) % 360.0 - 101.25


def half_of(phi: float) -> str:
    return "front" if wrap(phi) < 78.75 else "back"


def texel_geo(half: str, c: int, r: int):
    """(azimuth, height, slice radius) at the centre of texel (c, r) of a half's map."""
    phi = HALF[half] + (c + 0.5) * 180.0 / N
    k = next(k for k in range(len(SLICES)) if ROW0[k] <= r < ROW0[k] + ROWS[k])
    h0, h1, rad = SLICES[k]
    f = (r - ROW0[k] + 0.5) / ROWS[k]
    return phi, h1 - f * (h1 - h0), rad


def texel_at(phi: float, h: float):
    """(half, column, row) of the texel that shows the surface point (azimuth, height)."""
    phi = wrap(phi)
    half = half_of(phi)
    c = int(math.floor((phi - HALF[half]) / 180.0 * N))
    h = clamp(h, 0.0, EGG_TOP - 1e-6)
    k = next(k for k, (h0, h1, _) in enumerate(SLICES) if h0 <= h < h1)
    h0, h1, _ = SLICES[k]
    r = ROW0[k] + min(ROWS[k] - 1, int((h1 - h) / (h1 - h0) * ROWS[k]))
    return half, c, r


def profile_r(h: float) -> float:
    h = clamp(h, 0.0, EGG_TOP)
    for (h0, r0), (h1, r1) in zip(EGG_PROFILE, EGG_PROFILE[1:]):
        if h0 <= h <= h1:
            return r0 + (r1 - r0) * (h - h0) / (h1 - h0)
    return EGG_PROFILE[-1][1]


def profile_slope(h: float, window: float = 0.7) -> float:
    a, b = clamp(h - window, 0.0, EGG_TOP), clamp(h + window, 0.0, EGG_TOP)
    return (profile_r(b) - profile_r(a)) / (b - a)


_MIDS = [((h0 + h1) / 2, r) for h0, h1, r in SLICES]


def face_r(h: float) -> float:
    """Radius of the stepped shell's faces, smoothed through the middle of each slice."""
    if h <= _MIDS[0][0]:
        return _MIDS[0][1]
    for (h0, r0), (h1, r1) in zip(_MIDS, _MIDS[1:]):
        if h0 <= h <= h1:
            return r0 + (r1 - r0) * (h - h0) / (h1 - h0)
    return _MIDS[-1][1]


def normal_at(phi: float, h: float):
    p = math.radians(phi)
    return _norm((math.sin(p), -profile_slope(h), math.cos(p)))


def local(portal: dict, phi: float, h: float, rad: float):
    """Front-projected coordinates round a portal centre: (right, up, depth toward viewer)."""
    d = math.radians(phi - portal["phi"])
    return rad * math.sin(d), h - portal["h"], rad * math.cos(d)


def portal_at(phi: float, h: float, rad: float):
    """(portal, rho, theta) of the portal whose side this point is on."""
    for portal in PORTALS:
        x, y, z = local(portal, phi, h, rad)
        if z > 0:
            return portal, math.hypot(x, y), math.atan2(y, x)
    return None, 99.0, 0.0


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------
BAYER = (0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
KEY = _norm((-0.5, 0.8, 0.55))          # key light: upper left, in front
BACKLIGHT = _norm((0.45, 0.55, -0.7))   # a softer light over the back
FILL = _norm((0.7, -0.35, 0.1))         # bounce light on the lower right
SPEC = _norm((-0.62, 0.55, 0.56))      # where the glossy highlight sits: the upper-left shoulder


def threshold(c: int, r: int) -> float:
    return (BAYER[(r % 4) * 4 + c % 4] + 0.5) / 16.0 - 0.5


def glaze(phi: float, h: float) -> float:
    """Baked light on the glaze as a ramp position (0 = darkest): wrapped key light, a
    softer light over the back, bounce light low on the right and a crisp gloss spot."""
    n = normal_at(phi, h)
    key = clamp((_dot(n, KEY) + 0.35) / 1.35)
    back = clamp((_dot(n, BACKLIGHT) + 0.2) / 1.2)
    bounce = max(0.0, _dot(n, FILL))
    gloss = _dot(n, SPEC)
    spec = 3.0 if gloss > 0.992 else 1.1 if gloss > 0.972 else 0.0
    return 4.0 + 3.6 * key + 1.8 * back * (1 - key) + 1.0 * bounce + spec


def shell_colour(phi: float, h: float, rad: float, c: int, r: int) -> str:
    portal, rho, theta = portal_at(phi, h, rad)
    if portal is not None:
        if rho < portal["r_in"]:
            # The void behind the vortex: a dark tunnel, faintly lighter toward its heart.
            k = rho / portal["r_in"]
            return VOID[int(clamp(3.6 - 3.4 * k + threshold(c, r) * 0.5, 0, 4))]
        if rho < portal["r_out"] + 0.12:
            return OBSIDIAN[1]
    pos = glaze(phi, h)
    if portal is not None:
        # Portal light spilling onto the glaze round the frame.
        pos += 1.3 * math.exp(-max(0.0, rho - portal["r_out"]) / 0.45)
        # Marbling: two faint lighter veins curling out of the frame into the glaze.
        swirl = math.cos(2 * theta - portal["flow"] * 2.6 * math.log(rho))
        if swirl > 0.62:
            pos += 0.9 * math.exp(-(rho - portal["r_out"]) / 1.8)
    pos += threshold(c, r) * 0.35
    return SHELL[int(clamp(pos, 1, len(SHELL) - 1))]


def darker(colour: str, steps: int = 1) -> str:
    rgb = rgba(colour)
    for i, c in enumerate(SHELL):
        if rgba(c) == rgb:
            return SHELL[max(1, i - steps)]
    return colour


def paint_shell() -> None:
    for half in ("front", "back"):
        img = canvas(N)
        px = img.load()
        for r in range(N):
            for c in range(N):
                phi, h, rad = texel_geo(half, c, r)
                px[c, r] = rgba(shell_colour(phi, h, rad, c, r))
        # Eggshell speckles: sparse indigo flecks in ones and twos, clear of the portals.
        rng = random.Random(23 if half == "front" else 29)
        for _ in range(26):
            c, r = rng.randrange(N), rng.randrange(1, N - 1)
            phi, h, rad = texel_geo(half, c, r)
            portal, rho, _ = portal_at(phi, h, rad)
            if portal is not None and rho < portal["r_out"] + 0.9:
                continue
            cells = [(c, r)] + ([(c + rng.choice((-1, 1)), r)] if rng.random() < 0.45 else [])
            for (x, y) in cells:
                if 0 <= x < N:
                    px[x, y] = rgba(darker("#%02x%02x%02x" % px[x, y][:3], 2 if (x, y) == (c, r) else 1))
        save(img, "shell_" + half)


def energy(portal: dict, rho: float, theta: float, t: float, c: int, r: int):
    """The swirling disc inside a frame: bright energy, dark streaks spiralling in."""
    r_in = portal["r_in"]
    k = rho / r_in
    flow = portal["flow"]
    s = math.cos(portal["arms"] * theta + flow * portal["twist"] * math.log(rho + 0.25) + flow * 2 * math.pi * t)
    e = 0.5 + 0.4 * (1 - k) ** 1.4
    e -= 0.58 * clamp((s - 0.62) / 0.38)                      # the dark streaks
    e += 0.13 * clamp((-s - 0.55) / 0.45)                     # bright ridges between them
    e += 0.55 * math.exp(-(rho / 0.62) ** 2) * (0.9 + 0.1 * math.cos(4 * math.pi * t))
    if k > 0.84:
        e += 0.1                                              # the lip catches the light
    e += threshold(c, r) * 0.05
    steps = (0.27, 0.37, 0.48, 0.59, 0.69, 0.79, 0.88, 0.95, 0.99)
    if e < steps[0]:
        return None
    for i, s_ in enumerate(steps[1:]):
        if e < s_:
            return GLOW[i]
    return GLOW[-1]


# Portal motes: (azimuth, height, lifetime, phase, drift in azimuth, rise)
def _motes():
    rng = random.Random(7)
    return [(rng.uniform(-180, 180), rng.uniform(0.6, 7.2), rng.uniform(0.35, 0.55), (i * 0.61803) % 1.0,
             rng.uniform(22, 40), rng.uniform(2.2, 3.4)) for i in range(16)]


MOTES = _motes()


def put(img, half_want: str, phi: float, h: float, colour, dc: int = 0, dr: int = 0) -> None:
    half, c, r = texel_at(phi, h)
    if half != half_want:
        return
    c, r = c + dc, r + dr
    if 0 <= c < N and 0 <= r < N:
        cphi, ch, crad = texel_geo(half, c, r)
        portal, rho, _ = portal_at(cphi, ch, crad)
        if portal is not None and rho < portal["r_out"] + 0.35:
            return  # swallowed by the portal
        img.putpixel((c, r), rgba(colour))


def light_frame(half: str, t: float):
    img = canvas(N)
    px = img.load()
    for r in range(N):
        for c in range(N):
            phi, h, rad = texel_geo(half, c, r)
            portal, rho, theta = portal_at(phi, h, rad)
            if portal is None:
                continue
            col = energy(portal, rho, theta, t, c, r) if rho < portal["r_in"] else None
            if col:
                px[c, r] = rgba(col)
    for phi0, h0, life, phase, drift, rise in MOTES:
        s = ((t - phase) % 1.0) / life
        if s >= 1.0:
            continue
        phi, h = phi0 + drift * s, h0 + rise * s
        e = math.sin(math.pi * s)
        if e > 0.75:
            put(img, half, phi, h, GLOW[6])
            for dc, dr in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                put(img, half, phi, h, GLOW[3], dc, dr)
        elif e > 0.42:
            put(img, half, phi, h, GLOW[5])
            put(img, half, phi, h, GLOW[2], 0, 1)
        elif e > 0.15:
            put(img, half, phi, h, GLOW[3])
    return img


def paint_light() -> None:
    for half in ("front", "back"):
        save_animation(animate(lambda t, half=half: light_frame(half, t), FRAMES), "light_" + half,
                       frametime=FRAMETIME)


def paint_caps() -> None:
    # Ledges between slices: solid colours so the overlapping cap faces never flicker.
    save(canvas(16, fill=SHELL[7]), "cap_top")
    save(canvas(16, fill=SHELL[5]), "cap_upper")
    save(canvas(16, fill=SHELL[2]), "cap_lower")


RING_SHADES = 8   # lighting variants of the ring's top, darkest first


def paint_stones() -> None:
    """Polished obsidian: a ring whose top is lit violet along the edge that faces the
    portal, and raised keystones with a rim bevel round the gem socket."""
    rng = random.Random(3)
    top = canvas(32, fill=OBSIDIAN[2])
    px = top.load()
    for i in range(RING_SHADES):
        lit = i / (RING_SHADES - 1)
        body = OBSIDIAN[2 + round(lit * 2)]
        rim = GLOW[1 + round(lit * 2)]
        outer = OBSIDIAN[1 + round(lit * 2)]
        for x in range(32):
            px[x, 3 * i] = rgba(rim)                   # the inner edge, lit by the portal
            px[x, 3 * i + 1] = rgba(body)
            px[x, 3 * i + 2] = rgba(outer)
        for x in range(32):                            # glassy glints in little clusters
            if rng.random() < 0.14:
                px[x, 3 * i + 1] = rgba(OBSIDIAN[min(6, 4 + round(lit * 2))])
                if x + 1 < 32:
                    px[x + 1, 3 * i + 2] = rgba(OBSIDIAN[min(5, 3 + round(lit * 2))])
        for seg in range(0, 8, 2):                     # engraved runes, faintly lit from within
            px[4 * seg + 1, 3 * i + 1] = rgba(GLOW[0])
            px[4 * seg + 2, 3 * i + 1] = rgba(GLOW[1 + round(lit)])
    save(top, "ring_top")

    side = canvas(32, fill=OBSIDIAN[1])
    for x in range(32):
        side.putpixel((x, 0), rgba(OBSIDIAN[4]))
        side.putpixel((x, 1), rgba(OBSIDIAN[2]))
    save(side, "ring_side")

    inner = canvas(32, fill=GLOW[1])
    for x in range(32):
        inner.putpixel((x, 0), rgba(GLOW[4]))
        inner.putpixel((x, 1), rgba(GLOW[3]))
        inner.putpixel((x, 2), rgba(GLOW[2]))
    save(inner, "ring_inner")

    key = canvas(32, fill=OBSIDIAN[3])
    kx = key.load()
    for y in range(5):
        for x in range(6):
            edge = x in (0, 5) or y in (0, 4)
            corner = x in (0, 5) and y in (0, 4)
            c = OBSIDIAN[5] if corner else OBSIDIAN[4] if edge else OBSIDIAN[2]
            if y == 0 and not corner:
                c = GLOW[3]                            # lip facing the portal
            kx[x, y] = rgba(c)
    kx[2, 2] = kx[3, 2] = rgba(OBSIDIAN[1])            # the gem's socket
    kx[1, 1] = rgba(OBSIDIAN[6])
    for x in range(8, 32):                             # keystone sides
        for y in range(8):
            kx[x, y] = rgba(OBSIDIAN[4] if y == 0 else OBSIDIAN[3] if y == 1 else OBSIDIAN[1])
    save(key, "keystone")

    # Magenta gems: a white glint in the lit corner, facets stepping down to plum.
    gem = canvas(32, fill=GEM[2])
    gx = gem.load()
    for y in range(3):
        for x in range(3):
            gx[x, y] = rgba(GEM[5] if x + y == 0 else GEM[4] if x + y == 1 else GEM[3] if x + y == 2
                            else GEM[2])
    for x in range(4, 32):
        for y in range(4):
            gx[x, y] = rgba(GEM[4] if y == 0 else GEM[2] if y == 1 else GEM[1])
    save(gem, "gem")


RUNE_GLYPHS = [
    ["..#..", "#.#.#", ".###.", "..#..", ".#.#."],
    ["#...#", ".#.#.", "..#..", ".#.#.", "#.#.#"],
    [".###.", "#...#", "#.#.#", "#...#", ".###."],
    ["##.##", "#...#", "..#..", "#...#", "##.##"],
    ["#.#.#", "#.#.#", ".###.", "..#..", "..#.."],
]


def rune_brightness(j: int, t: float) -> float:
    """A flare runs round the orbit: each rune lights in turn, then fades."""
    d = (t - j / len(RUNES)) % 1.0
    b = max(0.0, 1.0 - d / 0.5)
    return b * b * (3 - 2 * b)


def rune_frame(t: float):
    img = canvas(32)
    px = img.load()
    for j, glyph in enumerate(RUNE_GLYPHS[:len(RUNES)]):
        b = rune_brightness(j, t)
        u0, v0 = (j % 4) * 8, (j // 4) * 8
        ink = GLOW[5] if b < 0.25 else GLOW[6] if b < 0.6 else GLOW[7]
        halo = GLOW[1] if b < 0.25 else GLOW[2] if b < 0.6 else GLOW[3]
        cells = {(1 + gx, 1 + gy) for gy, row in enumerate(glyph) for gx, ch in enumerate(row) if ch == "#"}
        for (x, y) in cells:
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                if (x + dx, y + dy) not in cells:
                    px[u0 + x + dx, v0 + y + dy] = rgba(halo)
        for (x, y) in cells:
            px[u0 + x, v0 + y] = rgba(ink)
        # The comet trail: a strip that lights from the rune end and shortens as it fades.
        for i in range(16):
            level = b * (1.0 - i / 16.0) - 0.1
            if level <= 0:
                continue
            col = GLOW[6] if level > 0.7 else GLOW[5] if level > 0.52 else GLOW[4] if level > 0.35 \
                else GLOW[3] if level > 0.2 else GLOW[2]
            for x in (2 * j, 2 * j + 1):
                px[x, 16 + i] = rgba(col)
    return img


def paint_runes() -> None:
    save_animation(animate(rune_frame, FRAMES), "runes", frametime=FRAMETIME)


def textures() -> None:
    paint_shell()
    paint_light()
    paint_caps()
    paint_stones()
    paint_runes()


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def _uv(vals):
    return [round(clamp(v, 0.0, 16.0), 4) for v in vals]


def slice_of(element: dict) -> int:
    h0 = element["from"][1] - EGG_CENTER[1]
    return min(range(len(SLICES)), key=lambda k: abs(SLICES[k][0] - h0))


def egg_body():
    """The shared lathe egg, re-mapped so the painted maps wrap it, plus its glowing twin."""
    slabs = lathe(EGG_CENTER, EGG_PROFILE, "shell_front", sides=16, cap="cap_top")
    shell, light = [], []
    for e in slabs:
        k = slice_of(e)
        a = e.get("rotation", {}).get("angle", 0.0)
        faces, glow_faces = {}, {}
        for side, base in (("south", 0.0), ("east", 90.0), ("north", 180.0), ("west", 270.0)):
            if side not in e["faces"]:
                continue
            phi = wrap(base + a)
            half = half_of(phi)
            u0 = (phi - 11.25 - HALF[half]) / 180.0 * 16.0
            uv = _uv([u0, ROW0[k] / 2, u0 + 2.0, (ROW0[k] + ROWS[k]) / 2])
            faces[side] = {"uv": uv, "texture": "#shell_" + half}
            glow_faces[side] = {"uv": uv, "texture": "#light_" + half}
        # Ledges: only the up faces of the upper slices and the down faces of the lower
        # ones can be seen; the rest sit inside the wider neighbour.
        if k >= 3:
            cap = "cap_top" if k == len(SLICES) - 1 else "cap_upper"
            faces["up"] = {"uv": [0, 0, 16, 16], "texture": "#" + cap}
        if k <= 3:
            faces["down"] = {"uv": [0, 0, 16, 16], "texture": "#cap_lower"}
        e["faces"] = faces
        shell.append(e)
        g = copy(e)
        # Float the glowing twin out along the face normal, and widen it a hair so the
        # neighbouring faces (each pushed out along its own normal) still meet.
        axis, across = (0, 2) if "east" in glow_faces else (2, 0)
        g["from"][axis] = round(g["from"][axis] - OVERLAY_EPS, 4)
        g["to"][axis] = round(g["to"][axis] + OVERLAY_EPS, 4)
        g["from"][across] = round(g["from"][across] - OVERLAY_SEAM, 4)
        g["to"][across] = round(g["to"][across] + OVERLAY_SEAM, 4)
        if k + 1 < len(SLICES) and SLICES[k + 1][2] < SLICES[k][2]:
            g["to"][1] = round(g["to"][1] + LEDGE_LIFT, 4)   # meets the ledge patch above it
        g["faces"] = glow_faces
        g["light_emission"] = 15
        g["shade"] = False
        light.append(g)
    return shell, light


def ledge_patches(portal: dict) -> list[dict]:
    """Glowing strips over the shell's up-facing steps where they cross a portal, so the
    swirl runs unbroken over the ledges (they sample the vortex map's boundary row)."""
    parts = []
    half = portal["name"]
    for k in range(len(SLICES) - 1):
        h_step = SLICES[k][1]
        r_lo, r_hi = SLICES[k][2], SLICES[k + 1][2]
        if r_hi >= r_lo:
            continue                                  # a down-facing step: never seen from above
        y_rel = h_step - portal["h"]
        if abs(y_rel) >= portal["r_in"]:
            continue
        reach = math.degrees(math.asin(math.sqrt(portal["r_in"] ** 2 - y_rel ** 2) / r_hi)) + 11.25
        row = ROW0[k]                                 # the top row of the slice below the step
        for i in range(-3, 4):
            phi_c = portal["phi"] + 22.5 * i
            if abs(22.5 * i) > reach:
                continue
            u0 = (wrap(phi_c) - 11.25 - HALF[half]) / 180.0 * 16.0
            w = r_hi * math.tan(math.pi / 16)
            y = EGG_CENTER[1] + h_step + LEDGE_LIFT
            p = box((8 - w, y - 0.02, 8 + r_hi), (8 + w, y, 8 + r_lo + OVERLAY_EPS + 0.01), "light_" + half,
                    faces={"up": ("light_" + half, _uv([u0, row / 2 + 0.1, u0 + 2.0, row / 2 + 0.4]))},
                    skip=("north", "south", "east", "west", "down"), glow=15, shade=False)
            a = wrap(phi_c) if half == "front" else wrap(phi_c) - 180.0
            if half == "back":
                turn(p, 180.0, "y", (8, y, 8))
            if a:
                turn(p, a, "y", (8, y, 8))
            parts.append(p)
    return parts


def oriented(center, x_axis, y_axis, size, tex, faces=None, **kw) -> dict:
    """A box of `size` (along its own x, y, z) centred on `center`, turned so its x and y
    axes point along x_axis and y_axis (z = x cross y)."""
    z_axis = _cross(x_axis, y_axis)
    m = tuple((x_axis[i], y_axis[i], z_axis[i]) for i in range(3))
    sx, sy, sz = size
    e = box((center[0] - sx / 2, center[1] - sy / 2, center[2] - sz / 2),
            (center[0] + sx / 2, center[1] + sy / 2, center[2] + sz / 2), tex, faces=faces, **kw)
    ex, ey, ez = matrix_to_euler(m)
    return turn(e, x=ex, y=ey, z=ez, origin=center)


def ring_point(portal: dict, a: float, rho: float):
    """Point on the shell (and its outward normal) at polar (rho, a) round a portal,
    measured in the portal's front-projected plane."""
    x, y = rho * math.cos(a), rho * math.sin(a)
    h = portal["h"] + y
    rad = face_r(h)
    phi = portal["phi"] + math.degrees(math.asin(clamp(x / rad, -0.999, 0.999)))
    p = math.radians(phi)
    point = (8 + rad * math.sin(p), EGG_CENTER[1] + h, 8 + rad * math.cos(p))
    return point, normal_at(phi, h)


def ring_frame(portal: dict, a_deg: float, rho: float):
    """(point, normal, tangent) on the shell at a portal's polar (rho, a)."""
    a = math.radians(a_deg)
    p, n = ring_point(portal, a, rho)
    p1, _ = ring_point(portal, a + 0.02, rho)
    p0, _ = ring_point(portal, a - 0.02, rho)
    t = _norm(tuple(p1[i] - p0[i] for i in range(3)))
    return p, n, _norm(_add(t, n, -_dot(t, n)))


def frame_ring(portal: dict) -> list[dict]:
    """A closed obsidian ring hugging the curved shell round a portal, with raised
    keystones that each hold a glowing gem."""
    parts = []
    count = portal["segments"]
    rho = (portal["r_in"] + portal["r_out"]) / 2
    width = portal["r_out"] - portal["r_in"]
    length = 2 * portal["r_out"] * math.tan(math.pi / count)      # outer chord: no gaps
    for j in range(count):
        a = 360.0 * (j + 0.5) / count
        p, n, t = ring_frame(portal, a, rho)
        centre = _add(p, n, RING_H / 2 - SINK)
        up = _norm(_add(n, _cross(t, n), -0.35))                      # tilt toward the portal
        lit = clamp((_dot(up, KEY) + 0.2) / 1.1)
        shade = min(RING_SHADES - 1, int(lit * RING_SHADES))
        faces = {
            "up": ("ring_top", [(j % 8) * 2.0, shade * 1.5, (j % 8) * 2.0 + 2.0, shade * 1.5 + 1.5]),
            "north": ("ring_inner", [0, 0, 2.0, RING_H * 1.5]),
            "south": ("ring_side", [0, 0, 2.0, RING_H * 1.5]),
            "east": ("ring_side", [0, 0, 1.5, RING_H * 1.5]),
            "west": ("ring_side", [0, 0, 1.5, RING_H * 1.5]),
        }
        parts.append(oriented(centre, t, n, (length, RING_H, width), "ring_side", faces=faces, skip=("down",)))
    for a, k in portal["keys"]:
        p, n, t = ring_frame(portal, a, rho)
        kw, kl, kh = width + 0.5 * k, length * 1.1 * k, KEY_H * (0.75 + 0.25 * k)
        centre = _add(p, n, kh / 2 - SINK)
        faces = {"up": ("keystone", [0, 0, 3.0, 2.5]),
                 "north": ("keystone", [4, 0, 4 + kl * 1.5, 3.0]), "south": ("keystone", [4, 0, 4 + kl * 1.5, 3.0]),
                 "east": ("keystone", [4, 0, 4 + kw * 1.5, 3.0]), "west": ("keystone", [4, 0, 4 + kw * 1.5, 3.0])}
        parts.append(oriented(centre, t, n, (kl, kh, kw), "keystone", faces=faces, skip=("down",)))
        # The gem: a diamond set into the keystone's top, lit from within.
        g = 0.7 * k
        diag = _norm(_add(t, _cross(t, n), 1.0))
        gc = _add(p, n, kh - SINK + 0.08)
        gem_faces = {"up": ("gem", [0, 0, 1.5, 1.5])}
        for s_ in ("north", "south", "east", "west"):
            gem_faces[s_] = ("gem", [2, 0, 3, 1.5])
        parts.append(oriented(gc, diag, n, (g, 0.45, g), "gem", faces=gem_faces, skip=("down",), glow=15,
                              shade=False))
    return parts


def orbit_point(beta_deg: float):
    beta, tilt = math.radians(beta_deg), math.radians(ORBIT_TILT)
    radial = (math.sin(beta), -math.sin(tilt) * math.cos(beta), math.cos(tilt) * math.cos(beta))
    return _add(ORBIT_C, radial, ORBIT_R), radial


def runes() -> list[dict]:
    parts = []
    rng = random.Random(5)
    for j, beta in enumerate(RUNES):
        centre, radial = orbit_point(beta)
        facing = _norm(_add(_add((0, 0, 0), radial, 0.5), (0.0, 0.12, 1.0), 0.8))
        if _dot(facing, radial) < 0.2:
            facing = _norm(radial)
        up = _norm(_add((0, 1, 0), facing, -facing[1]))
        roll = math.radians(rng.uniform(-16, 16))
        side = _cross(up, facing)
        x_axis = _norm(_add(_add((0, 0, 0), side, math.cos(roll)), up, math.sin(roll)))
        y_axis = _cross(facing, x_axis)
        u0, v0 = (j % 4) * 4.0, (j // 4) * 4.0
        glyph = [u0, v0, u0 + 3.5, v0 + 3.5]
        faces = {"south": ("runes", glyph), "north": ("runes", [glyph[2], glyph[1], glyph[0], glyph[3]])}
        parts.append(oriented(centre, x_axis, y_axis, (RUNE_SIZE, RUNE_SIZE, 0.02), "runes", faces=faces,
                              skip=("east", "west", "up", "down"), glow=15, shade=False))
        # Comet trail behind the rune (it travels toward larger orbit angles).
        for (b0, b1, w, v) in ((beta - 17, beta - 5.5, 0.42, 8.0), (beta - 31, beta - 16, 0.3, 12.0)):
            p0, _ = orbit_point(b0)
            p1, _ = orbit_point(b1)
            strip = [j, v, j + 1, v + 4]
            f = {s: ("runes", strip) for s in ("north", "south", "east", "west")}
            f["up"] = ("runes", [j, v, j + 1, v + 0.5])
            f["down"] = ("runes", [j, v + 3.5, j + 1, v + 4])
            parts.append(bar(p0, p1, w, w, "runes", faces=f, glow=15, shade=False))
    return parts


def build():
    shell, light = egg_body()
    return (shell + light + ledge_patches(FRONT) + ledge_patches(BACK) + frame_ring(FRONT) + frame_ring(BACK)
            + runes())


GRIP = (8, 5.0, 8)


def transforms(parts) -> dict:
    d = display("item", parts, grip=GRIP, size=SIZE, gui_rotation=(12, -14, 0), gui_span=15.6)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, 1)}, GRIP, "fist", 0.45 * SIZE)
    # First person: the portal turned toward the camera, held low on the right.
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.3, 0.12, 1)}, GRIP, (0.47, -0.36, -0.88),
                                       0.5 * SIZE, pose=None)
    d["ground"] = fit(parts, (0, 0, 0), 7.5, lift=2.0)
    return d


def models() -> dict:
    parts = build()
    return {"main": model(parts, transforms(parts))}
