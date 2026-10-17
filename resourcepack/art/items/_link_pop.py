"""Link Pop (issue #1207): a faceted hard-candy jewel set in a little white plastic ring.
Five colours share this module; each art/items/link_pop_<colour>.py only picks a palette.
Retextured Cookies, so they eat like one.

The ring is a real loop (an arc of bars standing in the XY plane, so its opening faces
the inventory camera at an angle), with a short neck up to an octagonal plastic cup and
four claws that grip the gem's corners. The gem is an octagonal brilliant: a flat table,
eight sloping crown facets, a thin girdle and a short pavilion sitting in the cup.

Every facet is one thin element showing a single face. Facets are trapezoids, so their
textures are painted as trapezoids with transparent corners (Minecraft cuts out alpha 0),
and neighbouring facets meet edge to edge. A dim core fills the gem so the stair-stepped
cutout edges read as thin seams. Facets are unshaded and their lighting is painted in:
each texture is lit from the facet's real normal against one light, so the jewel looks
the same glossy candy in every view, with a bright lit side and a glowing, translucent
shadow side (light that went through the candy).
"""
from __future__ import annotations

import math

from art.kit import (_apply, animate, arc, bar, box, canvas, display, display_matrix, fill, lathe, model, rgba,
                     rotation_of, save, save_animation, shine, sparkle, turn)

KIND = "food"
COUNTERPART = "item/cookie"

# Plastic, darkest -> lightest: a cool, slightly milky white.
PLASTIC = ["#b9b8c4", "#c9c8d2", "#d8d6de", "#e5e3e9", "#efeef2", "#f8f7f9", "#ffffff"]

# --------------------------------------------------------------------------------------
# Layout (model units), centred on (CX, *, CZ)
# --------------------------------------------------------------------------------------
CX, CZ = 8.0, 8.0
SECTORS = 8
STEP = 360.0 / SECTORS
TAN = math.tan(math.radians(STEP / 2))

RING_Y, RING_R = 4.0, 3.15         # band centre line
BAND_T, BAND_W = 1.15, 2.6         # radial thickness, width along Z
RING_TOP = RING_Y + RING_R + BAND_T / 2

CUP_Y0, CUP_Y1 = RING_TOP - 0.5, 9.9   # cup foot (sunk into the band) and rim
CUP_R0, CUP_R1 = 1.9, 4.0              # cup apothem at the foot and the rim
LIP = 0.35                             # rim lip height

R_GIRDLE = 4.6                     # gem apothem at the girdle
Y_G0, Y_G1 = 10.35, 11.0           # girdle bottom / top
R_BEZEL, Y_BEZEL = 3.75, 13.3      # where the steep lower crown meets the upper crown
R_TABLE, Y_TABLE = 2.3, 15.0       # table apothem and height
R_CULET, Y_CULET = 2.0, 8.9        # pavilion foot, hidden in the cup
THIN = 0.12                        # facet thickness

# The light the facets are painted with, in model space: from above, over the shoulder
# of the inventory camera (gui rotation 30, 225, 0) and a little to its left.
GUI_ROTATION = (30, 225, 0)


def _light() -> tuple[float, float, float]:
    view = (-0.55, 0.7, 0.45)          # x right, y up, z toward the camera
    m = display_matrix(GUI_ROTATION)
    v = tuple(sum(m[r][c] * view[r] for r in range(3)) for c in range(3))   # transpose
    n = math.sqrt(sum(a * a for a in v))
    return tuple(a / n for a in v)


LIGHT = _light()


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def _sector(e, i: int, y: float = 0.0):
    return turn(e, -i * STEP, "y", (CX, y, CZ))


# The crown in two tiers: (name, outer apothem, outer height, inner apothem, inner height).
CROWN = (("crown_lo", R_GIRDLE, Y_G1, R_BEZEL, Y_BEZEL), ("crown_hi", R_BEZEL, Y_BEZEL, R_TABLE, Y_TABLE))


def crown_facet(i: int, tier: int) -> dict:
    name, r_out, y_out, r_in, y_in = CROWN[tier]
    run, rise = r_out - r_in, y_in - y_out
    # The lower tier runs on a little under the upper one, so no gap opens at the bezel.
    length = math.hypot(run, rise) + (0.25 if tier == 0 else 0.0)
    w = 2 * r_out * TAN
    e = box((CX + r_out - length, y_out - THIN, CZ - w / 2), (CX + r_out, y_out, CZ + w / 2),
            f"{name}_{i}", uv="full", shade=False, skip=("down", "north", "south", "east", "west"))
    turn(e, -math.degrees(math.atan2(rise, run)), "z", (CX + r_out, y_out, CZ))
    return _sector(e, i, y_out)


def pavilion_facet(i: int) -> dict:
    run, drop = R_GIRDLE - R_CULET, Y_G0 - Y_CULET
    length = math.hypot(run, drop)
    w = 2 * R_GIRDLE * TAN
    e = box((CX + R_GIRDLE - length, Y_G0, CZ - w / 2), (CX + R_GIRDLE, Y_G0 + THIN, CZ + w / 2),
            f"pavilion_{i}", uv="full", shade=False, skip=("up", "north", "south", "east", "west"))
    turn(e, math.degrees(math.atan2(drop, run)), "z", (CX + R_GIRDLE, Y_G0, CZ))
    return _sector(e, i, Y_G0)


def girdle_facet(i: int) -> dict:
    w = 2 * R_GIRDLE * TAN
    e = box((CX + R_GIRDLE - THIN, Y_G0, CZ - w / 2), (CX + R_GIRDLE, Y_G1, CZ + w / 2),
            f"girdle_{i}", uv="full", shade=False, skip=("up", "down", "north", "south", "west"))
    return _sector(e, i, Y_G0)


def normal(e: dict, local) -> tuple[float, float, float]:
    m, _ = rotation_of(e)
    return _apply(m, local)


def lit(e: dict, local) -> float:
    n = normal(e, local)
    return sum(a * b for a, b in zip(n, LIGHT))


def gem() -> list[dict]:
    parts = []
    for i in range(SECTORS):
        parts += [crown_facet(i, 0), crown_facet(i, 1), girdle_facet(i), pavilion_facet(i)]
    # The table: one face, painted as an octagon on a square.
    parts.append(box((CX - R_TABLE, Y_TABLE - THIN, CZ - R_TABLE), (CX + R_TABLE, Y_TABLE, CZ + R_TABLE),
                     "table", uv="full", shade=False, skip=("down", "north", "south", "east", "west")))
    # A dim core behind the facets: the seams between them show it as thin dark lines.
    inset = 0.3
    profile = [(Y_CULET + 0.4 - Y_G0, R_CULET - inset), (0.0, R_GIRDLE - inset),
               (Y_G1 - Y_G0, R_GIRDLE - inset), (Y_BEZEL - Y_G0, R_BEZEL - inset),
               (Y_TABLE - 0.25 - Y_G0, R_TABLE - inset)]
    parts += lathe((CX, Y_G0, CZ), profile, "core", sides=8, cap="core", shade=False)
    return parts


def ring() -> list[dict]:
    parts = arc((CX, RING_Y, CZ), RING_R, 0, 360, 20, BAND_T, BAND_W, "band", uv="full",
                faces={"west": "band_in", "north": "band_side", "south": "band_side"})
    # Neck and cup: octagonal, flats lined up with the gem's facets.
    h = CUP_Y1 - CUP_Y0
    parts += lathe((CX, CUP_Y0, CZ), [(0.0, CUP_R0), (0.75, CUP_R0 + 0.1)], "neck", sides=8, cap="cup_top")
    # The cup flares out in a soft curve rather than one straight cone.
    profile = [(0.0, CUP_R0 + 0.4), (0.55, CUP_R0 + 0.95), (1.1, CUP_R0 + 1.6), (h - 0.75, CUP_R1)]
    for k, (lo, hi) in enumerate(zip(profile, profile[1:])):
        # Each step a shade lighter toward the rim, so the curve reads as one soft bowl.
        parts += lathe((CX, CUP_Y0 + 0.75, CZ), [lo, hi], f"cup_{k}", sides=8, cap="cup_top")
    parts += lathe((CX, CUP_Y1, CZ), [(0.0, CUP_R1 + 0.2), (LIP, CUP_R1 + 0.2)], "lip", sides=8, cap="cup_top")
    # Four claws over the gem's corners.
    corner = R_GIRDLE / math.cos(math.radians(STEP / 2))
    for k in range(4):
        a = STEP / 2 + k * 90
        # Up the corner of the girdle, then bent in to hug the crown's corner edge.
        knee = (CX + corner - 0.1, Y_G1 - 0.05, CZ)
        rise = 1.1
        r_tip = (R_GIRDLE - (R_GIRDLE - R_BEZEL) * rise / (Y_BEZEL - Y_G1)) / math.cos(math.radians(STEP / 2))
        claw = [
            bar((CX + CUP_R1 + 0.2, CUP_Y1 + 0.1, CZ), knee, 0.9, 0.9, "claw"),
            bar(knee, (CX + r_tip + 0.1, Y_G1 + rise, CZ), 0.8, 0.85, "claw", faces={"east": "claw_top"}),
        ]
        parts += turn(claw, -a, "y", (CX, 0, CZ))
    return parts


def build() -> list[dict]:
    return ring() + gem()


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def _clamp(i: float, n: int) -> int:
    return max(0, min(n - 1, round(i)))


def trapezoid(name: str, inner: float, candy: list[str], light: float, crown: bool, sector: int,
              upper: bool = False) -> None:
    """A facet on an up/down face: u runs from the inner end (column 0) to the girdle
    (column 31); `inner` is the inner end's width as a share of the girdle's."""
    n = len(candy)
    img = canvas(32)
    px = img.load()
    base = 2.5 + light * 4.4 + (0.55 if sector % 2 else -0.35)   # palette index; alternate facets differ
    glow = max(0.0, -light) * 4.5              # light through the candy on the shadow side
    for u in range(32):
        t = (u + 0.5) / 32                     # 0 at the inner end, 1 at the girdle
        half = 16 * (inner + (1 - inner) * t)
        for v in range(32):
            d = abs(v + 0.5 - 16)
            if d > half:
                continue
            edge = half - d                    # pixels from the facet's side ridges
            if crown:
                # A translucent glow welling up toward the girdle on the shadow side.
                idx = base - 0.4 * (1 - t) + glow * t * t
            else:
                idx = base + 0.6 - 0.8 * t + glow * 0.6
            if edge < 1.0 or u == 0:
                idx += 1.6 if light > 0.15 else -1.2     # lit ridges catch light, others sink
            elif edge < 2.0:
                idx += 0.5 if light > 0.15 else -0.4
            if crown and not upper and u >= 30:
                idx += 1.0                     # the girdle edge
            elif crown and not upper and 25 <= u <= 27:
                idx += 0.9                     # light caught inside the candy, just above the girdle
            px[u, v] = rgba(candy[_clamp(idx, n)])
    # Sugar-glass flecks.
    for k in range(3):
        u = (sector * 7 + k * 11) % 24 + 6
        v = 16 + ((sector * 5 + k * 13) % 11) - 5
        if px[u, v][3]:
            px[u, v] = rgba(candy[_clamp(base + 1.0 + glow, n)])
    if crown and light > 0.5:
        # A glossy streak down the brightest facets.
        for u in range(4, 27):
            v = 16 + round((u - 15) * 0.35) - 3
            for dv in (0, 1):
                if px[u, v + dv][3]:
                    px[u, v + dv] = rgba(candy[-1] if dv == 0 else candy[-2])
    save(img, name)


def girdle(name: str, candy: list[str], light: float) -> None:
    n = len(candy)
    img = canvas(32)
    base = 3.0 + light * 3.6
    for y in range(32):
        idx = base + (1.2 if y < 5 else 0) - (1.0 if y > 26 else 0)
        for x in range(32):
            img.putpixel((x, y), rgba(candy[_clamp(idx + (0.6 if x in (0, 31) else 0), n)]))
    save(img, name)


def table_frame(candy: list[str], t: float):
    n = len(candy)
    img = canvas(32)
    px = img.load()
    a = R_TABLE
    for y in range(32):
        for x in range(32):
            fx, fz = (x + 0.5 - 16) / 16 * a, (y + 0.5 - 16) / 16 * a
            ax, az = abs(fx), abs(fz)
            if (ax + az) / math.sqrt(2) > a:
                continue
            edge = min(a - ax, a - az, a - (ax + az) / math.sqrt(2))
            # Pavilion facets seen through the table: a star of alternating wedges.
            ang = (math.degrees(math.atan2(fz, fx)) + 360) % 360
            wedge = int(ang // 22.5) % 2
            r = math.hypot(fx, fz) / a
            idx = 4.6 + (0.8 if wedge else -0.3) - 1.2 * r
            if r < 0.28:
                idx = 5.6                      # the bright heart of the jewel
            if edge < 0.12:
                idx = 6.6                      # crisp table edge
            px[x, y] = rgba(candy[_clamp(idx, n)])
    # A soft window reflection near the lit corner.
    for x, y in ((9, 8), (10, 8), (8, 9), (9, 9), (10, 9), (8, 10), (9, 10), (12, 7), (13, 7)):
        px[x, y] = rgba(candy[-1])
    out = shine(img, t, candy[-1], width=3.5, strength=0.65, angle=40.0, pause=0.55)
    # A twinkle once the shine has passed, gone again before the loop restarts.
    twinkle = math.sin(math.pi * (t - 0.55) / 0.35) if 0.55 < t < 0.9 else 0.0
    sparkle(out, 21, 11, twinkle, "#ffffff", reach=3)
    return out


def textures_for(candy: list[str]) -> None:
    # Facet lighting comes from each facet's real normal, so paint them from the geometry.
    for i in range(SECTORS):
        for tier, (name, r_out, _, r_in, _) in enumerate(CROWN):
            e = crown_facet(i, tier)
            trapezoid(f"{name}_{i}", r_in / r_out, candy, lit(e, (0, 1, 0)), True, i, upper=tier == 1)
        e = pavilion_facet(i)
        trapezoid(f"pavilion_{i}", R_CULET / R_GIRDLE, candy, lit(e, (0, -1, 0)), False, i)
        e = girdle_facet(i)
        girdle(f"girdle_{i}", candy, lit(e, (1, 0, 0)))
    save_animation(animate(lambda t: table_frame(candy, t), 16), "table", frametime=3)
    save(canvas(16, fill=candy[2]), "core")

    # Plastic: the band is rounded across its width (stretched over each face), with one
    # glossy highlight running round the ring.
    band = canvas(16)
    across = [3, 4, 5, 6, 6, 5, 5, 5, 4, 4, 4, 4, 3, 3, 2, 2]
    for x, shade_ in enumerate(across):
        fill(band, (x, 0, x, 15), PLASTIC[shade_])
    save(band, "band")
    save(canvas(16, fill=PLASTIC[3]), "band_in")
    side = canvas(16)
    for x, shade_ in enumerate([5, 5, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 3, 3, 3, 3]):
        fill(side, (x, 0, x, 15), PLASTIC[shade_])
    save(side, "band_side")
    save(canvas(16, fill=PLASTIC[3]), "neck")
    for k, shade_ in enumerate((3, 4, 5)):
        cup = canvas(16, fill=PLASTIC[shade_])
        fill(cup, (0, 0, 15, 0), PLASTIC[min(6, shade_ + 1)])
        save(cup, f"cup_{k}")
    save(canvas(16, fill=PLASTIC[6]), "lip")
    save(canvas(16, fill=PLASTIC[5]), "cup_top")
    save(canvas(16, fill=PLASTIC[5]), "claw")
    save(canvas(16, fill=PLASTIC[5]), "claw_top")


def models_for() -> dict:
    parts = build()
    d = display("food", parts, size=1.35, gui_rotation=GUI_ROTATION, gui_span=15.0)
    fp = d["firstperson_righthand"]
    fp["translation"][1] = round(fp["translation"][1] + 2.5, 3)
    return {"main": model(parts, d)}


def make(candy: list[str]):
    """(textures, models) for one colour of Link Pop."""
    return (lambda: textures_for(candy)), models_for
