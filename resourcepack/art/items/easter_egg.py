"""Easter Egg: a golden Faberge-style surprise egg, the gold star of the egg set.

The shared egg body (the EGG_PROFILE lathe) wears one animated 32px shell map, wrapped
round the facets and carried onto every ledge so the pattern reads as one surface: four
pastel enamel panels on the lid (pink and mint, each holding a gold four-point star in a
cloisonne keyline) between raised gold straps; a gold calyx crowning the top, with four
claws holding a rose-cut pink diamond; a jewelled girdle round the widest point with the
lid's seam just above it, where warm light leaks from a glowing gap; an Easter zig-zag of
enamel triangles on gold, fluted gold below and a knop ending in a small pink drop.

Animation (one 3.2 s loop): a white glint rises across the egg, slanting over every
panel, drawn on a second, self-lit shell so it reads as real light even on the dim sides;
the front jewel, the stars and the pink diamond twinkle in turn after it passes; the
light in the seam breathes twice, spilling warm light onto its gold lips.
"""
from __future__ import annotations

import math

from art.kit import (animate, bar, canvas, display, fit, lathe, model, place, prism, rgba, rotation_of,
                     save_animation, sparkle, turn, wave)
from art.kit import box as _box

ID = "easter_egg"
NAME = "Easter Egg"
KIND = "item"
COUNTERPART = "item/egg"

# --------------------------------------------------------------------------------------
# The shared egg body: every egg in the set is this lathe
# --------------------------------------------------------------------------------------
EGG_PROFILE = [(0, 1.4), (0.7, 3.2), (1.8, 4.3), (3.3, 4.9), (5.0, 5.0), (6.8, 4.7), (8.4, 4.0),
               (9.8, 3.0), (10.9, 1.8), (11.5, 0.6)]
CX, CY, CZ = 8.0, 2.2, 8.0
SLICES = [(h0, h1, (r0 + r1) / 2) for (h0, r0), (h1, r1) in zip(EGG_PROFILE, EGG_PROFILE[1:])]
# Shell-map rows (first row, count) for each lathe slice, bottom slice first. Texels stay
# roughly square on the surface, about two per unit round the widest part.
SLICE_ROWS = [(27, 2), (24, 3), (21, 3), (17, 4), (13, 4), (9, 4), (5, 4), (1, 4), (0, 1)]
UP_CAP_ROWS = (1, 5, 9, 13)      # rows the upward ledges copy (a step darker: they catch more light)
DOWN_CAP_ROWS = (20, 23, 26)     # rows the downward ledges copy (a step lighter)

# The girdle (egg heights h0, h1 and radius): the jewelled base lip, then the glowing seam.
BASE_LIP = (4.15, 5.0, 5.12)
SEAM = (5.0, 5.5, 4.95)

# Pattern facet (0..7, four texels each; the map repeats every half turn) of world facet k
# (azimuth 22.5 k): K_SHIFT puts panel A's centre, facet 1, in front of the inventory camera.
K_SHIFT = -1
GUI_ROT = (4, 225, 0)
SIZE = 1.2
GRIP = (8, 7.6, 8)

# --------------------------------------------------------------------------------------
# Palettes, darkest -> lightest
# --------------------------------------------------------------------------------------
GOLD = ["#2a1206", "#4e2208", "#7e3a08", "#b0600c", "#dc8e14", "#f5b221", "#ffd23c", "#ffec85", "#fff9dc"]
PINK = ["#5e1f45", "#a13a70", "#dc5f9a", "#f78cbc", "#ffb0d2", "#ffdcec"]
MINT = ["#115048", "#1f8a70", "#3cc79a", "#6fe6bb", "#a6f7d6", "#dcfff0"]
RAMPS = {"G": GOLD, "P": PINK, "M": MINT}
SHINE_BOOST = {"G": 4.0}                     # enamel gets 2.5

ROSE = ["#3d0418", "#7a0c33", "#c21f5a", "#f2508a", "#ff8fb8", "#ffd6e6", "#ffffff"]
AQUA = ["#062f3a", "#0b5f70", "#1497a6", "#35d0d0", "#8ef3ea", "#dffffb", "#ffffff"]
ICE = ["#3a4a66", "#6d84a8", "#a5bfdc", "#d3e6f8", "#eef7ff", "#ffffff", "#ffffff"]

LID_ENAMEL = {"A": "P", "B": "M"}           # lid panels: A faces the inventory camera
STONES = [ICE, ROSE, ICE, AQUA, ICE, ROSE, ICE, AQUA]      # girdle jewels at world azimuth 45 i
STONE_PHASE = [0.64, 0.24, 0.9, 0.56, 0.08, 0.74, 0.3, 0.97]

# Single-texel swatches in the last shell row (uniform colours for caps and small parts).
SWATCH = {"s8_top": (0, "G", 6), "bottom": (2, "G", 4), "base_up": (4, "G", 4), "base_down": (6, "G", 4),
          "collar_up": (12, "G", 6), "bezel": (16, "G", 6), "bezel_side": (18, "G", 4), "prong": (20, "G", 7),
          "nub_down": (22, "G", 4)}
BLEED_SWATCHES = ("base_up",)                # swatches that warm up with the seam light

# --------------------------------------------------------------------------------------
# Shell-map geometry helpers
# --------------------------------------------------------------------------------------


def r4(v: float) -> float:
    return round(v, 4)


def v_of(h: float) -> float:
    """Continuous shell-map texel row (0 = top of the egg) at egg height h."""
    for (h0, h1, _), (top, n) in zip(SLICES, SLICE_ROWS):
        if h0 - 1e-6 <= h <= h1 + 1e-6:
            return top + n * (h1 - h) / (h1 - h0)
    raise ValueError(h)


def h_of(row: int):
    """Egg height at the centre of a shell-map row (None for the atlas rows)."""
    for (h0, h1, _), (top, n) in zip(SLICES, SLICE_ROWS):
        if top <= row < top + n:
            return h1 - (row - top + 0.5) * (h1 - h0) / n
    return None


def psi_view() -> float:
    """Azimuth (degrees, growing with the map's u) that faces the inventory camera."""
    y = math.radians(GUI_ROT[1])
    return (-math.degrees(math.atan2(math.cos(y), -math.sin(y)))) % 360.0


def psi_of_col(tx: int) -> float:
    j = tx // 4
    return 22.5 * ((j - K_SHIFT) % 8) - 11.25 + (tx % 4 + 0.5) * 22.5 / 4


LOCAL_N = {"east": (1, 0, 0), "west": (-1, 0, 0), "north": (0, 0, -1), "south": (0, 0, 1)}


def azimuth(e: dict, side: str) -> float:
    m, _ = rotation_of(e)
    n = LOCAL_N[side]
    w = [sum(m[i][k] * n[k] for k in range(3)) for i in range(3)]
    return (-math.degrees(math.atan2(w[2], w[0]))) % 360.0


def texel(tx: int, ty: int, size: int = 32) -> list[float]:
    """UV of a single texel (inset, so the face is one flat colour)."""
    s = 16 / size
    return [r4(tx * s + s / 4), r4(ty * s + s / 4), r4((tx + 1) * s - s / 4), r4((ty + 1) * s - s / 4)]


def swatch(name: str) -> dict:
    return {"uv": texel(SWATCH[name][0], 31), "texture": "#shell"}


def wrap16(e: dict, h0: float, h1: float, up, down, tex: str = "shell") -> None:
    """UV one slab of a 16-sided lathe/prism onto the shell map: each side face gets its
    facet's four columns over the rows of its height; a cap gets its facet's columns
    from one row of the cap map ("row", n), so ledges continue the pattern, or a
    swatch ("swatch", name)."""
    faces = e["faces"]
    ends = {}
    for side in ("east", "west", "north", "south"):
        if side in faces:
            j = (round(azimuth(e, side) / 22.5) + K_SHIFT) % 8
            faces[side] = {"uv": [2 * j, r4(v_of(h1) / 2), 2 * j + 2, r4(v_of(h0) / 2)], "texture": "#" + tex}
            ends[side] = j
    for side, spec in (("up", up), ("down", down)):
        if spec is None:
            faces.pop(side, None)
        elif spec[0] == "swatch":
            faces[side] = swatch(spec[1])
        else:
            # The cap's columns match the side face at the slab's east (or south) end; the
            # far end sees them mirrored, so ledge rows are painted facet-symmetric.
            s1 = "east" in ends
            j = ends["east"] if s1 else ends["south"]
            face = {"uv": [2 * j, spec[1] / 2, 2 * j + 2, spec[1] / 2 + 0.5], "texture": "#shell_cap"}
            rot = (270 if side == "up" else 90) if s1 else 0
            if rot:
                face["rotation"] = rot
            faces[side] = face


def wrap8(parts: list, side_uv, up, down, tex: str) -> list:
    """UV the slabs of an 8-sided prism: side_uv(k8) per facet, caps from face dicts."""
    for e in parts:
        faces = e["faces"]
        for side in ("east", "west", "north", "south"):
            if side in faces:
                faces[side] = {"uv": side_uv(round(azimuth(e, side) / 45) % 8), "texture": "#" + tex}
        for side, spec in (("up", up), ("down", down)):
            if spec is None:
                faces.pop(side, None)
            else:
                faces[side] = dict(spec)
    return parts


# --------------------------------------------------------------------------------------
# The shell design: (ramp, index) per texel of the 32x32 map
# --------------------------------------------------------------------------------------
STRAP = {12: 7, 13: 6, 14: 5, 15: 3, 28: 7, 29: 6, 30: 5, 31: 3}     # raised straps, lit from the left


def lid_panel(tx: int):
    """(panel, x) for a lid panel column, else (None, None)."""
    if 0 <= tx <= 11:
        return "A", tx
    if 16 <= tx <= 27:
        return "B", tx - 16
    return None, None


def in_lid_panel(x: int, y: int) -> bool:
    """Panel shape, x 0..11 across, y 0..9 down (rows 5..14): an arched top."""
    if not (0 <= x <= 11 and 0 <= y <= 9):
        return False
    if y == 0:
        return 4 <= x <= 7
    if y == 1:
        return 2 <= x <= 9
    return True


STAR = {  # a tall gold four-point sparkle in each lid panel: (x, row) -> gold index, lit from the upper left
    (5, 7): 6, (6, 7): 5,
    (5, 8): 7, (6, 8): 6,
    (4, 9): 6, (5, 9): 7, (6, 9): 7, (7, 9): 5,
    (3, 10): 6, (4, 10): 7, (5, 10): 8, (6, 10): 8, (7, 10): 6, (8, 10): 5,
    (3, 11): 5, (4, 11): 6, (5, 11): 8, (6, 11): 7, (7, 11): 5, (8, 11): 4,
    (4, 12): 5, (5, 12): 6, (6, 12): 5, (7, 12): 4,
    (5, 13): 5, (6, 13): 4,
    (5, 14): 4, (6, 14): 3,
}


def design() -> list[list[list]]:
    g = [[["G", 5] for _ in range(32)] for _ in range(32)]

    def put(tx, ty, key, idx):
        g[ty][tx % 32] = [key, idx]

    for tx in range(32):
        c = tx % 4
        put(tx, 0, "G", 6)
        # Crown band under the calyx, softly fluted, with a shadowed groove above the lid panels.
        for row, flute in ((1, (7, 6, 6, 5)), (2, (6, 6, 5, 4)), (3, (6, 5, 5, 3)), (4, (3, 3, 3, 2))):
            put(tx, row, "G", flute[c])
        # Lid panels (rows 5-14) between raised straps.
        for y in range(10):
            row = 5 + y
            if tx in STRAP:
                put(tx, row, "G", STRAP[tx] - (y >= 8))
                continue
            panel, x = lid_panel(tx)
            if panel and in_lid_panel(x, y):
                key = LID_ENAMEL[panel]
                if (x, row) in STAR:
                    put(tx, row, "G", STAR[(x, row)])
                elif any((x + dx, row + dy) in STAR for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    put(tx, row, key, 1)                       # cloisonne keyline round the star
                else:
                    idx = 4 if y <= 6 else 3
                    if not in_lid_panel(x, y - 1) or not in_lid_panel(x - 1, y):
                        idx -= 1                               # shadow under the frame
                    if (x, y) in ((1, 3), (1, 4), (2, 3)):
                        idx = 5                                # gloss on the enamel
                    put(tx, row, key, idx)
                continue
            put(tx, row, "G", 5)                               # gold round the arched tops
        # The lid's lower rim, dark over the seam; the hidden row under the seam.
        put(tx, 15, "G", 2)
        put(tx, 16, "G", 5)
        # Jewelled base lip (rows 17-18): its top edge catches the seam light.
        put(tx, 17, "G", 3)
        put(tx, 18, "G", (5, 7, 6, 4)[c])
        # An Easter zig-zag of enamel triangles on gold, mint under the pink panels.
        q = (tx - 2) % 8
        tri = "M" if ((tx - 2) // 8) % 2 == 0 else "P"
        for row, (lo, hi) in zip(range(19, 23), ((3, 4), (2, 5), (1, 6), (0, 7))):
            if lo <= q <= hi:
                idx = 5 if q == lo else (3 if q == hi else 4)
                put(tx, row, tri, idx - (row == 22 and q not in (lo, hi)))
            else:
                put(tx, row, "G", 5 if row < 21 else 4)
        # A beaded rib, then fluted gold down to the knop.
        put(tx, 23, "G", (7, 6, 3, 2)[c])
        for row, base in zip(range(24, 29), (6, 5, 5, 4, 3)):
            put(tx, row, "G", base + (1, 0, 0, -2)[c])
    # Atlas rows: collar flutes (cols 0-3), calyx leaves (4-7), bottom knop (8-11).
    for c, (a, b) in enumerate(((7, 6), (6, 5), (5, 4), (3, 2))):
        put(c, 29, "G", a)
        put(c, 30, "G", b)
    for c, (a, b) in enumerate(((4, 3), (7, 6), (6, 5), (4, 3))):
        put(4 + c, 29, "G", a)
        put(4 + c, 30, "G", b)
    for c, (a, b) in enumerate(((6, 5), (5, 4), (4, 3), (3, 2))):
        put(8 + c, 29, "G", a)
        put(8 + c, 30, "G", b)
    for name, (tx, key, idx) in SWATCH.items():
        put(tx, 31, key, idx)
    return g


DESIGN = design()
ROW_H = {row: h_of(row) for row in range(29)}

# --------------------------------------------------------------------------------------
# Animation (one 64-tick loop for every texture)
# --------------------------------------------------------------------------------------
SHINE_RUN = 0.5         # the glint rises during this share of the loop, then the gold rests
SHINE_TILT = 2.4        # units the glint climbs across the front: a "/" streak
SHINE_FROM, SHINE_TO = -2.5, 15.0


def blip(t: float, phase: float, width: float = 0.07) -> float:
    d = abs(t - phase) % 1.0
    d = min(d, 1.0 - d)
    return max(0.0, 1.0 - d / width)


def seam_pulse(t: float) -> float:
    return wave(2 * t)


def shine_height(t: float):
    """Egg height of the glint's centre line in front of the camera (None when resting)."""
    if t >= SHINE_RUN:
        return None
    return SHINE_FROM + (SHINE_TO - SHINE_FROM) * t / SHINE_RUN


STRAP_PSI = 22.5 * ((3 - K_SHIFT) % 8)   # azimuth of a strap (they repeat every quarter turn)


def shine_parts(psi: float, h: float, t: float) -> tuple[float, float, float]:
    """(core, halo, trail) of a glossy streak rising up the egg. Across each quarter turn
    (strap to strap) it slants up to the right, so every panel catches its own "/" glint
    and the steps hide on the straps; a quarter-turn period suits the half-turn map."""
    hc = shine_height(t)
    if hc is None:
        return 0.0, 0.0, 0.0
    u = ((psi - STRAP_PSI) % 90.0) / 90.0 - 0.5
    d = h - hc - 2 * SHINE_TILT * u
    return (max(0.0, 1.0 - abs(d) / 0.5), max(0.0, 1.0 - abs(d) / 1.3), max(0.0, 1.0 - abs(d + 1.9) / 0.34))


def shine_amount(psi: float, h: float, t: float) -> float:
    core, halo, trail = shine_parts(psi, h, t)
    return 0.95 * core + 0.3 * halo + 0.7 * trail


GLINTS = ((13, 7, 0.58), (29, 12, 0.66), (22, 2, 0.8), (9, 23, 0.9), (30, 6, 0.08))  # winks on the gold
STAR_FLARES = ((5, 0.33), (21, 0.83))          # the panel stars flare after the glint passes
SEAM_BLOOM = {15: 150, 14: 60}                   # glint-layer alpha of the seam's glow, by row


GIRDLE_ROWS = (17, 18)     # the base lip ring's rows: gold the glint layer cannot reach


def shell_frame(t: float, cap: bool = False):
    """The painted shell (and, with cap=True, its ledge copy: upward ledges a step darker,
    downward ones a step lighter, to even out the light they catch). The glint layer
    covers the lathe's sides, so the painted shine only brightens what it cannot reach:
    the girdle, the ledges and the collar."""
    img = canvas(32)
    px = img.load()
    p = seam_pulse(t)
    top_flash = shine_amount(psi_view(), 11.5, t)
    bleed = {SWATCH[n][0] for n in BLEED_SWATCHES}
    shined = (UP_CAP_ROWS + DOWN_CAP_ROWS) if cap else GIRDLE_ROWS
    for ty in range(32):
        h = ROW_H.get(ty)
        offset = 0
        if cap:
            offset = -1 if ty in UP_CAP_ROWS else (1 if ty in DOWN_CAP_ROWS else 0)
        for tx in range(32):
            key, idx = DESIGN[ty][tx]
            ramp = RAMPS[key]
            k = 0.0
            if ty in shined:
                core, _, trail = shine_parts(psi_of_col(tx), h, t)
                k = 0.95 * core + 0.7 * trail
            elif ty in (29, 30):
                k = 0.8 * top_flash
            idx += offset + round(k * SHINE_BOOST.get(key, 2.5))
            if ty in (15, 17) or (ty == 31 and tx in bleed):
                idx += round(p * 2.4)                     # warm light spilling from the seam
            px[tx, ty] = rgba(ramp[max(0, min(len(ramp) - 1, idx))])
    return img


def glint_frame(t: float):
    """The self-lit glint layer over the shell: transparent except the white core and
    trail of the rising glint, its soft halo, and the twinkles on the stars and gold."""
    img = canvas(32)
    px = img.load()
    p = seam_pulse(t)
    for ty in range(29):
        h = ROW_H[ty]
        bloom = SEAM_BLOOM.get(ty, 0) * p           # light from the seam washing up the lid's rim
        for tx in range(32):
            core, halo, trail = shine_parts(psi_of_col(tx), h, t)
            if core >= 0.3 or trail >= 0.45:
                px[tx, ty] = rgba("#ffffff" if core >= 0.7 else "#fff4cc")
            elif halo > 0.2 or bloom > 8:
                px[tx, ty] = (255, 232, 160, round(max(90 * halo, bloom)))
    for cx, phase in STAR_FLARES:
        a = blip(t, phase, 0.08)
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            sparkle(img, cx + dx, 10 + dy, a, "#fffbe8", reach=3)
    for x, y, phase in GLINTS:
        sparkle(img, x, y, blip(t, phase, 0.05), "#ffffff", reach=2)
    return img


def seam_frame(t: float):
    """The light in the seam: 16 columns round the egg, a gold-to-white breath with a
    brighter shimmer drifting round once a loop."""
    img = canvas(16)
    px = img.load()
    p = seam_pulse(t)
    dim = ("#d9782a", "#ffc25c", "#d06f24")
    bright = ("#fff0c0", "#ffffff", "#ffe8b0")
    for tx in range(16):
        d = abs((tx + 0.5 - 16 * t + 8) % 16 - 8)
        q = min(1.0, 0.15 + 0.85 * p + 0.35 * max(0.0, 1 - d / 2.5))
        for ty in range(3):
            a, b = rgba(dim[ty]), rgba(bright[ty])
            px[tx, ty] = tuple(round(a[i] + (b[i] - a[i]) * q) for i in range(4))
    for ty in range(3, 16):
        for tx in range(16):
            px[tx, ty] = px[tx, 1]
    return img


# Gem atlas (32px): finial facets in rows 0-5, girdle stones in 4x4 cells on rows 8-11.
FINIAL_PHASE = 0.47
CROWN = ((5, 3, 4, 2, 5, 3, 4, 2), (4, 2, 3, 1, 4, 2, 3, 1))
GIRDLE = ((4, 2, 3, 2, 4, 2, 3, 2), (3, 1, 2, 1, 3, 1, 2, 1))
PAVILION = (2, 1, 2, 0, 2, 1, 2, 0)
FINIAL_CAPS = (5, 4, 3, 1)       # table, crown ledge, girdle ledge, girdle underside
STONE_CELL = ((3, 5, 4, 2), (4, 6, 3, 2), (3, 3, 2, 1), (2, 2, 1, 1))


def gem_frame(t: float):
    img = canvas(32)
    px = img.load()
    a = blip(t, FINIAL_PHASE, 0.09)
    lift = round(a * 2)

    def fin(x, y, idx):
        px[x, y] = rgba(ROSE[max(0, min(6, idx + lift))])

    for x in range(8):
        fin(x, 0, CROWN[0][x])
        fin(x, 1, CROWN[1][x])
        fin(x, 2, GIRDLE[0][x])
        fin(x, 3, GIRDLE[1][x])
        fin(x, 4, PAVILION[x])
    for x, idx in enumerate(FINIAL_CAPS):
        fin(x, 5, idx)
    if a > 0.55:
        px[0, 5] = rgba("#ffffff")
    for i, ramp in enumerate(STONES):
        b = blip(t, STONE_PHASE[i], 0.07)
        up = round(b * 2)
        for y in range(4):
            for x in range(4):
                px[4 * i + x, 8 + y] = rgba(ramp[max(0, min(6, STONE_CELL[y][x] + up))])
        if b > 0.5:
            for x, y in ((1, 1), (2, 1), (1, 2)):
                px[4 * i + x, 8 + y] = rgba("#ffffff")
        px[4 * i, 12] = rgba(ramp[2 + up])
    return img


def textures() -> None:
    save_animation(animate(shell_frame, 32), "shell", frametime=2)
    save_animation(animate(lambda t: shell_frame(t, cap=True), 32), "shell_cap", frametime=2)
    save_animation(animate(glint_frame, 32), "glint", frametime=2)
    save_animation(animate(gem_frame, 32), "gems", frametime=2)
    save_animation(animate(seam_frame, 16), "seam", frametime=4, interpolate=True)


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------


def shell() -> list[dict]:
    parts = lathe((CX, CY, CZ), EGG_PROFILE, "shell", sides=16, uv="full")
    radii = [s[2] for s in SLICES]
    for e in parts:
        h0 = e["from"][1] - CY
        k = next(i for i, s in enumerate(SLICES) if abs(s[0] - h0) < 1e-3)
        h0, h1, r = SLICES[k]
        top, n = SLICE_ROWS[k]
        up = down = None
        if k == len(SLICES) - 1:
            up = ("swatch", "s8_top")
        elif r > radii[k + 1] + 1e-6 and h1 != SEAM[0]:
            up = ("row", top)
        if k == 0:
            down = ("swatch", "bottom")
        elif r > radii[k - 1] + 1e-6:
            down = ("row", top + n - 1)
        wrap16(e, h0, h1, up, down)
    return parts


def glint_shell() -> list[dict]:
    """A second, slightly larger shell that carries the glint layer, self-lit (shade off)
    so the shine reads as real white light even on the dimly lit sides of the egg."""
    grown = [(h, r + 0.04) for h, r in EGG_PROFILE]
    parts = lathe((CX, CY, CZ), grown, "glint", sides=16, uv="full", shade=False)
    for e in parts:
        h0 = e["from"][1] - CY
        k = next(i for i, s in enumerate(SLICES) if abs(s[0] - h0) < 1e-3)
        wrap16(e, SLICES[k][0], SLICES[k][1], None, None, "glint")
    return parts


def ring(h0: float, h1: float, radius: float, up, down, tex: str = "shell", **kw) -> list[dict]:
    parts = prism((CX, CY + (h0 + h1) / 2, CZ), radius, h1 - h0, tex, sides=16, **kw)
    for e in parts:
        wrap16(e, h0, h1, up, down, tex)
    return parts


def girdle() -> list[dict]:
    parts = ring(*BASE_LIP, ("swatch", "base_up"), ("swatch", "base_down"))
    # The seam: light leaking from the gap, self-lit so it glows in the dark.
    seam = ring(*SEAM, ("swatch", "base_up"), None, glow=15, shade=False)
    for e in seam:
        for side, face in e["faces"].items():
            if side == "up":
                face.update(uv=texel(0, 1, 16), texture="#seam")
            else:
                j = round(azimuth(e, side) / 22.5) % 16
                face.update(uv=[j, 0, j + 1, 3], texture="#seam")
    parts += seam
    # Jewels set in the base lip: diamonds under the straps, coloured stones mid-panel.
    h0, h1, rb = BASE_LIP
    yc = CY + (h0 + h1) / 2
    for i in range(8):
        big = i % 2 == 0
        w, hh = (0.9, 0.64) if big else (0.8, 0.6)
        bezel = _box((8 + rb - 0.2, yc - hh / 2 - 0.1, 8 - w / 2 - 0.1),
                     (8 + rb + 0.14, yc + hh / 2 + 0.1, 8 + w / 2 + 0.1),
                     "shell", faces={s: ("shell", swatch("bezel_side")["uv"]) for s in ("up", "down", "north", "south")})
        bezel["faces"]["east"] = swatch("bezel")
        bezel["faces"].pop("west")
        stone = _box((8 + rb, yc - hh / 2, 8 - w / 2), (8 + rb + (0.42 if big else 0.38), yc + hh / 2, 8 + w / 2),
                     "gems", glow=9, shade=False)
        for side, face in stone["faces"].items():
            face["uv"] = [2 * i, 4, 2 * i + 2, 6] if side == "east" else texel(4 * i, 12)
        stone["faces"].pop("west")
        parts += turn([bezel, stone], 45.0 * i, "y", (CX, yc, CZ))
    return parts


def finial() -> list[dict]:
    parts = []
    collar = prism((CX, 13.5, CZ), 1.6, 1.0, "shell", sides=8)
    parts += wrap8(collar, lambda k: [0, 14.5, 2, 15.5], swatch("collar_up"), None, "shell")
    # Calyx: eight gold leaves fanning over the crown band.
    for i in range(8):
        deg = 22.5 + 45 * i
        a = math.radians(deg)
        dx, dz = math.cos(a), -math.sin(a)
        p0 = (CX + 1.45 * dx, 13.71, CZ + 1.45 * dz)
        p1 = (CX + 2.75 * dx, 13.09, CZ + 2.75 * dz)
        leaf = bar(p0, p1, 0.95, 0.28, "shell", roll=deg - 90)
        for side, face in leaf["faces"].items():
            face["uv"] = [2, 14.5, 4, 15.5] if side not in ("up", "down") else texel(5, 29)
        parts.append(leaf)
    # Four claws holding a rose-cut pink diamond.
    for i in range(4):
        a = math.radians(45 + 90 * i)
        dx, dz = math.cos(a), -math.sin(a)
        claw = bar((CX + 1.3 * dx, 13.9, CZ + 1.3 * dz), (CX + 1.2 * dx, 14.7, CZ + 1.2 * dz), 0.34, 0.34, "shell")
        for face in claw["faces"].values():
            face["uv"] = texel(SWATCH["prong"][0], 31)
        parts.append(claw)
    gem = [(14.1, 0.8, 0.3, lambda k: [k * 0.5, 2, k * 0.5 + 0.5, 2.5], None, None),
           (14.5, 1.35, 0.5, lambda k: [k * 0.5, 1, k * 0.5 + 0.5, 2], texel(2, 5), texel(3, 5)),
           (14.975, 1.0, 0.45, lambda k: [k * 0.5, 0, k * 0.5 + 0.5, 1], texel(1, 5), None),
           (15.31, 0.56, 0.22, lambda k: [k * 0.5, 0, k * 0.5 + 0.5, 0.5], texel(0, 5), None)]
    for y, r, length, side_uv, up, down in gem:
        layer = prism((CX, y, CZ), r, length, "gems", sides=8, glow=12, shade=False)
        wrap8(layer, side_uv, {"uv": up, "texture": "#gems"} if up else None,
              {"uv": down, "texture": "#gems"} if down else None, "gems")
        parts += layer
    return parts


def knop() -> list[dict]:
    """A fluted gold knop under the egg, ending in a small pink drop that echoes the finial."""
    gold = prism((CX, 1.95, CZ), 1.3, 0.6, "shell", sides=8)
    wrap8(gold, lambda k: [4, 14.5, 6, 15.5], None, swatch("nub_down"), "shell")
    drop = prism((CX, 1.45, CZ), 0.66, 0.44, "gems", sides=8, glow=10, shade=False)
    wrap8(drop, lambda k: [k * 0.5, 1, k * 0.5 + 0.5, 2], None, {"uv": texel(3, 5), "texture": "#gems"}, "gems")
    return gold + drop


def build() -> list[dict]:
    return shell() + girdle() + finial() + knop() + glint_shell()   # the translucent glint last


def transforms(parts: list) -> dict:
    d = display(KIND, parts, grip=GRIP, size=SIZE, gui_rotation=GUI_ROT, gui_span=15.2)
    # First person: a little higher and further out, so the glowing seam stays in view.
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (0.35, 0, 1)}, GRIP, (0.52, -0.34, -0.92),
                                       0.52 * SIZE, pose=None)
    # A thrown egg is drawn facing the camera through "ground" (model +Z toward the viewer),
    # an item frame shows -Z: turn both so a pink panel, not a strap, faces out.
    d["ground"] = fit(parts, (0, GUI_ROT[1], 0), 8.0, lift=2.0)
    d["fixed"] = fit(parts, (0, GUI_ROT[1] - 180, 0), 14.0)
    d["on_shelf"] = fit(parts, (0, GUI_ROT[1] - 180, 0), 12.0)
    return d


def models() -> dict:
    parts = build()
    return {"main": model(parts, transforms(parts))}
