"""Tomato: a ripe, glossy red tomato with a leafy green calyx and a short stem. A retextured
Snowball (issue #1195), thrown like one.

The body is a round, slightly squat and lopsided sphere built from 24 radial wedges stacked
in 9 slices, like the Peanut Butter Cup's filling. Each wedge's reach follows the body
profile and five uneven, shallow lobes that show on the shoulders and fade toward the
blossom end, and the top sinks into a shallow dimple round the stem. Light is baked per face (shade off) from
an atlas of hue-shifted reds: deep crimson drifting toward wine in the shadows, scarlet in
the middle and warm orange-red toward a soft pink-white gloss on the lit shoulder. The
creases run a tone darker, the shoulders round the stem warm toward orange, and the bottom
carries the little pale star of the blossom end.

The calyx is five pointed sepals that lie on the dimple and curl down over the shoulders,
tips lifting, each three tapered segments painted with a lighter midrib, round a short bent
stem with a pale cut end. A dew drop sits on the lit shoulder and catches a glint now and
then (the only animated texture, 16 frames x 3 ticks).

Thrown, the projectile is drawn through "ground", turned to the camera, so the "ground"
transform keeps the tomato snowball-sized and tilted to show the calyx.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, display_matrix, fill, fit, model, rgba, rotation_of,
                     save, save_animation, sparkle, turn, wave)

ID = "tomato"
NAME = "Tomato"
KIND = "item"
COUNTERPART = "item/snowball"

# --------------------------------------------------------------------------------------
# Palettes, darkest -> lightest
# --------------------------------------------------------------------------------------
# Skin: shadows drift toward wine/purple, lights toward orange, the gloss toward pink-white.
RED = ["#2a0410", "#3d0614", "#530918", "#6b0d1b", "#84111d", "#9c151e", "#b31a1e", "#c8201d",
       "#d92a1c", "#e5391f", "#ee4b24", "#f5612c", "#fa7a3a", "#fd9a5c", "#ffc29a", "#ffe7d6"]
# Shoulders by the stem: the same light levels pulled toward orange.
WARM = ["#300a0c", "#461011", "#5e1714", "#771f15", "#8f2816", "#a63216", "#bb3d17", "#cc4a18",
        "#da571a", "#e4651e", "#ec7424", "#f3862e", "#f89b3e", "#fcb35c", "#ffd29a", "#ffeedb"]
LEAF = ["#0b2117", "#10301f", "#164226", "#1e552b", "#286a2f", "#358033", "#469537", "#5ba93c",
        "#79bd46", "#9bd05a"]
STEM = ["#2a3a17", "#3b5120", "#4f6a2a", "#668436", "#809e45", "#a0b95c", "#c3d488"]

# --------------------------------------------------------------------------------------
# Layout (model units). The tomato sits on its blossom end, centred on (CX, *, CZ).
# --------------------------------------------------------------------------------------
CX, CZ = 8.0, 8.0
Y0 = 2.4
# (height above Y0, radius) from the blossom end up to the shoulder; the top is the dimple.
PROFILE = [(0.0, 1.9), (0.6, 3.7), (1.6, 5.1), (2.9, 6.1), (4.4, 6.6), (5.9, 6.4), (7.2, 5.7),
           (8.3, 4.6), (9.1, 3.3), (9.6, 2.3)]
DIMPLE = (9.1, 1.5)          # the dimple floor: height and radius of the sunken cap round the stem
N = 24                       # wedges round the body
STEP = 360.0 / N
LOBES = 5
# Each lobe's depth of crease and bulge, so the tomato is not perfectly regular.
random.seed(1195)
LOBE_AMP = [0.03 + 0.02 * random.random() for _ in range(LOBES)]
LOBE_BULGE = [1.0 + 0.035 * (random.random() - 0.5) for _ in range(LOBES)]
LOBE_PHASE = 9.0             # degrees: no crease lines up with the camera

GUI_ROT = (28.0, 215.0, 0.0)
# Light in view space (x right, y up, z toward the camera): upper left, a little in front.
KEY = (-0.62, 0.62, 0.48)
FILL = (0.7, -0.2, 0.5)      # a faint warm bounce from the lower right


def _norm(v):
    length = math.sqrt(sum(c * c for c in v))
    return tuple(c / length for c in v)


def _dot(a, b) -> float:
    return sum(x * y for x, y in zip(a, b))


KEY_N = _norm(KEY)
FILL_N = _norm(FILL)
HALF = _norm(tuple(a + b for a, b in zip(KEY_N, (0.0, 0.0, 1.0))))
VIEW_M = display_matrix(GUI_ROT)


def to_view(n):
    return tuple(sum(VIEW_M[i][k] * n[k] for k in range(3)) for i in range(3))


def clamp(v: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, v))


def lobe_factor(azimuth: float) -> float:
    """Radius multiplier at this azimuth (degrees): 1 on a lobe, dipping into each crease."""
    a = (azimuth - LOBE_PHASE) % 360.0
    k = int(a // (360.0 / LOBES))
    g = 0.5 + 0.5 * math.cos(math.radians(a * LOBES))          # 1 at a lobe's middle, 0 in a crease
    return LOBE_BULGE[k] * (1.0 - LOBE_AMP[k] * (1.0 - g) ** 1.6)


def crease(azimuth: float) -> float:
    a = (azimuth - LOBE_PHASE) % 360.0
    return 0.5 - 0.5 * math.cos(math.radians(a * LOBES))       # 1 deep in a crease


def lobe_weight(h: float) -> float:
    """Lobes show most on the shoulders and fade toward the blossom end."""
    return clamp(0.25 + h / 7.0, 0.25, 1.0)


def profile_slope(k: int) -> float:
    (h0, r0), (h1, r1) = PROFILE[k], PROFILE[k + 1]
    return (r1 - r0) / (h1 - h0)


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------
# The skin atlas: 16 light levels across (2 px each), tone rows down (4 px each):
# 0 skin, 1 crease, 2 shoulder (warm), 3 blossom end. Faces map onto one 2x2 px patch of
# their level's cell, the row inside the cell picked by height, so neighbouring slices
# never print the exact same pixel pattern.
SKIN_ROWS = ("skin", "crease", "warm", "blossom")


def _cell(img, level: int, row: int, ramp: list[str], seed: int, tone: int) -> None:
    """Paint light level `level`'s cell in tone row `row` with ramp colour `tone`."""
    rnd = random.Random(seed)
    for y in range(4):
        for x in range(2):
            lv = tone
            # A few pixels lean a level up or down on the lit skin, so it reads painted, not
            # flat; the shadows stay clean.
            roll = rnd.random()
            if tone >= 6 and roll < 0.04:
                lv = tone - 1
            elif tone >= 6 and roll > 0.975:
                lv = min(15, tone + 1)
            img.putpixel((level * 2 + x, row * 4 + y), rgba(ramp[lv]))


def paint_skin() -> None:
    img = canvas(32)
    for level in range(16):
        _cell(img, level, 0, RED, 10 + level, level)
        _cell(img, level, 1, RED, 40 + level, max(0, level - 1))           # creases: a tone deeper
        _cell(img, level, 2, WARM, 70 + level, level)
    # Blossom end: dull, slightly brown red with a pale star scar (only the bottom uses it).
    for level in range(16):
        for y in range(4):
            for x in range(2):
                img.putpixel((level * 2 + x, 12 + y), rgba(RED[max(0, min(15, level - 3))]))
    save(img, "skin")

    # The blossom-end scar: a tiny pale star on a dark ring, mapped on the bottom cap.
    scar = canvas(16, fill=RED[3])
    px = scar.load()
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            if d < 7.5:
                px[x, y] = rgba(RED[4] if d > 5 else RED[3])
    for x, y in ((7, 7), (8, 7), (7, 8), (8, 8)):
        px[x, y] = rgba(WARM[12])
    for x, y in ((7, 5), (8, 6), (10, 8), (9, 9), (7, 10), (6, 9), (5, 7), (6, 6)):
        px[x, y] = rgba(WARM[10])
    save(scar, "scar")

    # The dimple floor round the stem: warm and in shadow, with a darker ring at the edge.
    dimple = canvas(16, fill=WARM[6])
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            if d > 6:
                dimple.putpixel((x, y), rgba(WARM[4]))
            elif d < 3:
                dimple.putpixel((x, y), rgba(WARM[7]))
    save(dimple, "dimple")


def paint_leaf() -> None:
    """Sepal atlas: four light levels down (8 px rows), each a leaf strip with a midrib
    along its length (u runs across the sepal, v along it)."""
    img = canvas(32)
    for level in range(4):
        base = 2 + level * 2
        for y in range(8):
            for x in range(32):
                edge = min(x, 31 - x)
                c = base
                if edge < 3:
                    c = base - 1                    # the edges curl away from the light
                if 14 <= x <= 17:
                    c = base + 1                    # the midrib catches it
                if (x * 7 + y * 13 + level) % 11 == 0 and edge >= 3:
                    c = base + (1 if level < 3 else 0)
                img.putpixel((x, level * 8 + y), rgba(LEAF[clamp_i(c, 0, len(LEAF) - 1)]))
    save(img, "leaf")


def clamp_i(v: int, lo: int, hi: int) -> int:
    return max(lo, min(hi, v))


def paint_stem() -> None:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            c = [1, 2, 3, 4, 4, 3, 2, 1][x // 2]
            if (x + y * 3) % 7 == 0:
                c += 1
            img.putpixel((x, y), rgba(STEM[c]))
    save(img, "stem")
    cut = canvas(16, fill=STEM[5])
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            if d > 6:
                cut.putpixel((x, y), rgba(STEM[4]))
            elif d < 2.5:
                cut.putpixel((x, y), rgba(STEM[6]))
    save(cut, "stem_cut")


DROP = ["#8a1a22", "#e0786c", "#f7b9ae", "#ffe2da", "#fff6f2", "#ffffff"]


def _drop_frame(t: float):
    """A dew drop: clear, the red skin showing through it, darker along its lower right
    edge, a bright catch-light top left, and a glint flaring once per loop."""
    img = canvas(16)
    px = img.load()
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            if d >= 7.0:
                continue
            lit = clamp(0.5 - 0.07 * (x - 7.5) - 0.07 * (y - 7.5))
            c = 1 + round(lit * 2)
            if d > 5.6 and x + y > 15:
                c = 0                                   # the shadowed lower right rim
            px[x, y] = rgba(DROP[c])
    for x, y in ((4, 4), (5, 4), (4, 5), (5, 5), (6, 4), (4, 6)):
        px[x, y] = rgba(DROP[5])
    for x, y in ((10, 11), (11, 10)):
        px[x, y] = rgba(DROP[3])                        # light focused through the drop
    flare = clamp((wave(t) - 0.55) / 0.45)
    if flare > 0:
        sparkle(img, 5, 5, flare, reach=3)
    return img


def textures() -> None:
    paint_skin()
    paint_leaf()
    paint_stem()
    save_animation(animate(_drop_frame, 16), "drop", frametime=3)


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def east_normal(e: dict):
    m, _ = rotation_of(e)
    return (m[0][0], m[1][0], m[2][0])


def skin_level(n, warmth: float) -> tuple[int, int]:
    """(light level 0-15, atlas row) for a skin face with model-space normal n."""
    v = to_view(n)
    diffuse = 0.5 + 0.5 * _dot(v, KEY_N)
    bounce = max(0.0, _dot(v, FILL_N)) * 0.12
    spec = max(0.0, _dot(v, HALF)) ** 20
    rim = (1.0 - max(0.0, v[2])) ** 3 * 0.1                # a hint of light round the edge
    lit = clamp(0.08 + 0.78 * diffuse ** 1.25 + bounce + rim)
    level = round(1 + lit * 10.5 + spec * 4.0)
    return int(clamp(level, 0, 15)), (2 if warmth > 0.5 else 0)


def skin_uv(level: int, row: int, h: float, i: int = 0) -> list[float]:
    """uv (16 = whole texture) of a 2x2 px patch in the level's cell, the sub-row picked by
    height and wedge i so the slices don't line up into brick courses."""
    sub = int(h * 1.7 + i * 0.37) % 3
    u0 = level * 2 / 2.0
    v0 = (row * 4 + sub) / 2.0
    return [u0 + 0.05, v0 + 0.05, u0 + 0.95, v0 + 0.95]


def wedge(i: int, y0: float, y1: float, r: float, r0: float = 0.0) -> dict:
    """A slice of sector i from radius r0 out to r, between heights y0 and y1."""
    half = r * math.tan(math.radians(STEP / 2)) * 1.06
    e = box((CX + r0, y0, CZ - half), (CX + r, y1, CZ + half), "skin", shade=False)
    return turn(e, -(i + 0.5) * STEP, "y", (CX, y0, CZ))


def body() -> list[dict]:
    parts = []
    slices = list(zip(PROFILE, PROFILE[1:]))
    for i in range(N):
        az = (i + 0.5) * STEP
        cr = crease(az)
        for k, ((h0, ra), (h1, rb)) in enumerate(slices):
            r = (ra + rb) / 2
            f = 1.0 + (lobe_factor(az) - 1.0) * lobe_weight((h0 + h1) / 2)
            reach = r * f
            top_slice = k == len(slices) - 1
            r_in = DIMPLE[1] if top_slice else 0.0
            e = wedge(i, Y0 + h0, Y0 + h1, reach, r_in)
            radial = east_normal(e)
            beta = math.atan(-profile_slope(k))                # surface tilt from the profile
            n = _norm((radial[0] * math.cos(beta), math.sin(beta), radial[2] * math.cos(beta)))
            h_mid = (h0 + h1) / 2
            warmth = clamp((h_mid - 8.3) / 0.6)
            level, row = skin_level(n, warmth)
            if cr > 0.92 and h_mid > 5.0:
                row = 1                                        # the crease between two lobes
            elif h_mid < 0.8:
                row = 3
            for side, face in e["faces"].items():
                if side == "up" and top_slice:
                    up_n = _norm((radial[0] * 0.35, 1.0, radial[2] * 0.35))
                    lv, rw = skin_level(up_n, 1.0)
                    face["uv"] = skin_uv(lv, 1 if row == 1 else rw, h1, i)
                elif side == "up":
                    # Thin ledges on the shoulders: one step above the wall, so they read as a
                    # rounded curve rather than bright rings.
                    face["uv"] = skin_uv(min(15, level + 1), row, h1, i)
                elif side == "down":
                    dn = _norm((radial[0] * 0.5, -1.0, radial[2] * 0.5))
                    lv, _ = skin_level(dn, 0.0)
                    face["uv"] = skin_uv(lv, 3, h0, i)
                elif side == "east":
                    face["uv"] = skin_uv(level, row, h_mid, i)
                else:
                    face["uv"] = skin_uv(max(0, level - 1), row, h_mid, i)
            # The inner face of the top ring shows only from above, in the dimple's shadow.
            if top_slice:
                e["faces"]["west"]["uv"] = skin_uv(5, 2, h1, i)
            else:
                e["faces"].pop("west", None)
            parts.append(e)
    # The blossom end: a flat cap with the scar, and the dimple floor round the stem.
    parts.append(turn(box((CX - 2.0, Y0 - 0.05, CZ - 2.0), (CX + 2.0, Y0 + 0.3, CZ + 2.0), "scar",
                          uv="full", shade=False, skip=("up",)), 22.0, "y", (CX, Y0, CZ)))
    parts.append(box((CX - DIMPLE[1] - 0.2, Y0 + DIMPLE[0] - 0.4, CZ - DIMPLE[1] - 0.2),
                     (CX + DIMPLE[1] + 0.2, Y0 + DIMPLE[0], CZ + DIMPLE[1] + 0.2), "dimple", uv="full",
                     shade=False, skip=("down", "north", "south", "east", "west")))
    return parts


# --- the calyx -------------------------------------------------------------------------
TOP_Y = Y0 + DIMPLE[0]
SEPALS = 5
SEPAL_PHASE = 30.0
# Each sepal: (length scale, droop, tip lift) so no two curl the same way.
SEPAL_SHAPE = [(1.0, 1.0, 1.0), (0.88, 1.15, 0.7), (1.05, 0.9, 1.2), (0.92, 1.05, 0.9), (0.97, 0.95, 1.1)]


def leaf_uv(level: int) -> list[float]:
    return [0.0, level * 4 + 0.1, 16.0, level * 4 + 3.9]


def leaf_level(direction_up: float) -> int:
    return int(clamp(round(1.2 + 2.2 * direction_up), 0, 3))


def sepal(azimuth: float, scale: float, droop: float, lift: float) -> list[dict]:
    """One sepal, three tapered segments from the stem out over the shoulder."""
    a = math.radians(azimuth)
    out = (math.cos(a), 0.0, -math.sin(a))
    # Points along the sepal: (radius from the axis, height above TOP_Y)
    path = [(0.4, 0.55), (2.1 * scale, 0.75), (3.6 * scale, 0.55 - 0.15 * droop),
            (4.9 * scale, -0.25 * droop + 0.45 * lift)]
    widths = [2.6, 2.1, 1.3]
    parts = []
    for j in range(3):
        (r0, y0), (r1, y1) = path[j], path[j + 1]
        p0 = (CX + out[0] * r0, TOP_Y + y0, CZ + out[2] * r0)
        p1 = (CX + out[0] * r1, TOP_Y + y1, CZ + out[2] * r1)
        e = bar(p0, p1, widths[j], 0.4, "leaf", roll=90.0, shade=False)
        rise = (y1 - y0) / max(0.01, r1 - r0)
        lv = leaf_level(0.6 - rise * 0.8 + 0.35 * math.cos(a - math.radians(135)))
        for side, face in e["faces"].items():
            face["uv"] = leaf_uv(lv if side in ("up", "north", "east") else max(0, lv - 1))
        parts.append(e)
    # The pointed tip.
    (r0, y0), (r1, y1) = path[3], (path[3][0] + 0.9 * scale, path[3][1] + 0.35 * lift)
    p0 = (CX + out[0] * r0, TOP_Y + y0, CZ + out[2] * r0)
    p1 = (CX + out[0] * r1, TOP_Y + y1, CZ + out[2] * r1)
    tip = bar(p0, p1, 0.7, 0.35, "leaf", roll=90.0, shade=False)
    for face in tip["faces"].values():
        face["uv"] = leaf_uv(2)
    parts.append(tip)
    return parts


def calyx() -> list[dict]:
    parts = []
    for s in range(SEPALS):
        scale, droop, lift = SEPAL_SHAPE[s]
        parts += sepal(SEPAL_PHASE + s * 360.0 / SEPALS, scale, droop, lift)
    # The knot where the sepals meet, then the short bent stem with its cut end.
    parts.append(turn(box((CX - 1.1, TOP_Y + 0.3, CZ - 1.1), (CX + 1.1, TOP_Y + 1.3, CZ + 1.1), "leaf",
                          uv={s: leaf_uv(2) for s in ("north", "south", "east", "west", "up", "down")},
                          shade=False), 18.0, "y", (CX, TOP_Y, CZ)))
    stem = box((CX - 0.65, TOP_Y + 1.1, CZ - 0.65), (CX + 0.65, TOP_Y + 2.9, CZ + 0.65), "stem",
               faces={"up": ("stem_cut", [0, 0, 16, 16])}, shade=False)
    turn(stem, 14.0, "z", (CX, TOP_Y + 1.1, CZ))
    turn(stem, 40.0, "y", (CX, TOP_Y + 1.1, CZ))
    parts.append(stem)
    return parts


def surface(azimuth: float, h: float):
    """(point, outward direction) on the body at this azimuth and height above Y0."""
    k = next(j for j in range(len(PROFILE) - 1) if PROFILE[j][0] <= h <= PROFILE[j + 1][0])
    (h0, ra), (h1, rb) = PROFILE[k], PROFILE[k + 1]
    r = ((ra + rb) / 2) * (1.0 + (lobe_factor(azimuth) - 1.0) * lobe_weight((h0 + h1) / 2))
    ref = turn(box((CX, 0, CZ), (CX + 1, 1, CZ + 1), "skin"), -azimuth, "y", (CX, 0, CZ))
    out = east_normal(ref)
    return (CX + out[0] * r, Y0 + h, CZ + out[2] * r), out


def dew_drop() -> list[dict]:
    """A small domed drop on the lit shoulder, facing the inventory camera's light."""
    az = 325.0                                  # just left of the inventory camera, on the lit side
    (x, y, z), out = surface(az, 6.6)
    yaw = math.degrees(math.atan2(out[0], out[2]))
    drop = box((x - 0.78, y - 0.78, z - 0.15), (x + 0.78, y + 0.78, z + 0.45), "drop", uv="full", shade=False,
               glow=4)
    turn(drop, -20.0, "x", (x, y, z))           # lean back with the shoulder
    turn(drop, yaw, "y", (x, y, z))
    return [drop]


def build() -> list[dict]:
    return body() + calyx() + dew_drop()


# --------------------------------------------------------------------------------------
# Display
# --------------------------------------------------------------------------------------

def models() -> dict:
    parts = build()
    d = display(KIND, parts, size=1.0, gui_rotation=GUI_ROT, gui_span=14.5)
    # Thrown, the item is drawn through "ground" facing the camera: snowball-sized, tilted
    # so the calyx shows. Dropped tomatoes use the same view.
    fp = d["firstperson_righthand"]
    fp["translation"][1] = round(fp["translation"][1] + 1.6, 3)
    fp["scale"] = [round(v * 0.8, 4) for v in fp["scale"]]
    tp = d["thirdperson_righthand"]
    tp["scale"] = [round(v * 1.1, 4) for v in tp["scale"]]
    d["ground"] = fit(parts, (18.0, GUI_ROT[1], 0.0), 8.0, lift=2.0)
    d["fixed"] = fit(parts, (0.0, GUI_ROT[1] - 180.0, 0.0), 13.0)
    d["on_shelf"] = fit(parts, (0.0, GUI_ROT[1] - 180.0, 0.0), 12.0)
    return {"main": model(parts, d)}
