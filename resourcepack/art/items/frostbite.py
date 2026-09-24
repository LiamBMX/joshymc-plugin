"""Frostbite: a crystalline ice greatsword (Winter Limited Edition).

A deep-blue frozen core with a recessed, aurora-lit fuller of glowing frost runes,
cased in a translucent glacial edge that runs out into a long icicle point, with
jagged crystal serrations along both edges. The guard is a layered hexagonal ice hub
in a silver rim with an aurora gem and splayed snowflake arms hung with icicles; the
grip is silver-blue wrapped in white fur; the pommel is a glowing snowflake crystal;
ice shards float beside the tip.
"""
import math
import random

from PIL import Image, ImageDraw

from art.kit import (bar, box, canvas, copy, display, fill, mirror, model, place, prism, rgba, save, turn)

ID = "frostbite"
NAME = "Frostbite"
KIND = "sword"

# ---------------------------------------------------------------------------------------
# Palette: hand-tuned hue-shifted ramps, darkest -> lightest
# ---------------------------------------------------------------------------------------
NAVY = ["#0a1233", "#111f55", "#182f7c", "#20409e", "#2b56bb", "#3a70d2"]
GLACIER = ["#1d4c8c", "#2767ae", "#3683cc", "#4f9fe0", "#72bced", "#9fd8f7"]
ICE = ["#5fb0e6", "#7fc8f2", "#a2dcf9", "#c6ecfd", "#e6f8ff", "#ffffff"]
FROST = ["#3f7fc0", "#5da3dc", "#86c6ef", "#b3e2fb", "#dcf4ff", "#ffffff"]
SILVER = ["#29324a", "#45516f", "#6a7b9b", "#93a6c3", "#bccce2", "#e8f0fb"]
FUR = ["#8f97c4", "#b6bee0", "#dadff2", "#f1f4fb", "#ffffff"]
RUNE = ["#0e5a88", "#189fd4", "#3de0ff", "#9ef5ff", "#f0ffff"]
TEAL = ["#0c5a5e", "#16918d", "#28cdb8", "#79f0d8", "#d0fff3"]
VIOLET = ["#261260", "#4726a0", "#7248db", "#a07cff", "#d6c4ff"]
# The casing's translucent body drifts from periwinkle at the guard to aurora teal.
BAND = ["#86a8ff", "#74b6fb", "#68c2f5", "#62ccef", "#63d7e6", "#6ee4dc"]

# ---------------------------------------------------------------------------------------
# Layout (model units; handle along +Y through x = z = 8)
# ---------------------------------------------------------------------------------------
HUB_Y, HUB_R = -1.2, 3.8          # hexagonal guard hub: centre height, circumradius
RAIL_TOP = 23.0                   # where the straight core ends and the tip facet begins
CORE_HW = 3.5                     # core half-width including the bevels
FULLER = (6.5, 9.5)               # recessed fuller
FULLER_TOP = 21.2
GRIP = (8, -7.4, 8)
SIZE = 0.93

# Silhouette of the glacial casing: (y, half-width).
OUTLINE = [(0.0, 5.2), (6.0, 5.0), (12.0, 4.75), (18.0, 4.45), (22.0, 4.15), (24.0, 3.7),
           (26.0, 2.95), (28.0, 2.0), (30.0, 1.0), (31.9, 0.0)]
# Crystal serrations: (height, angle from the blade axis, length), per side.
TEETH_RIGHT = ((7.4, 50, 3.4), (13.2, 45, 3.0), (18.7, 40, 2.6), (23.8, 33, 2.0))
TEETH_LEFT = ((4.4, 54, 3.7), (10.3, 48, 3.2), (15.9, 43, 2.8), (21.2, 37, 2.3))

# ---------------------------------------------------------------------------------------
# Projected blade textures: 64px images laid straight onto the blade's flat faces.
# Model x -8..24 -> u 0..16, y 32..0 -> v 0..16: 2 texels per unit like the rest.
# ---------------------------------------------------------------------------------------
WX, WTOP, KP = -8.0, 32.0, 0.5


def _uv(v):
    return round(max(0.0, min(16.0, v)), 4)


def proj(frm, to):
    """South and north UVs that project the blade textures onto a box's flat faces."""
    x0, y0, x1, y1 = frm[0], frm[1], to[0], to[1]
    v0, v1 = _uv((WTOP - y1) * KP), _uv((WTOP - y0) * KP)
    south = [_uv((x0 - WX) * KP), v0, _uv((x1 - WX) * KP), v1]
    north = [_uv((x1 - WX) * KP), v0, _uv((x0 - WX) * KP), v1]
    return south, north


def pbox(frm, to, tex, side=None, **kw):
    """A box whose flat faces carry a projected texture. side=None keeps only those
    two faces (a plate); otherwise the remaining faces use texture `side`."""
    south, north = proj(frm, to)
    faces = {"south": (tex, south), "north": (tex, north)}
    if side is None:
        return box(frm, to, tex, faces=faces, skip=("east", "west", "up", "down"), **kw)
    return box(frm, to, side, faces=faces, **kw)


def texel_xy(tx, ty):
    return (tx + 0.5) / 2 + WX, WTOP - (ty + 0.5) / 2


def to_texel(x, y):
    return ((x - WX) * 2, (WTOP - y) * 2)


def half_width(y):
    pts = OUTLINE
    if y <= pts[0][0]:
        return pts[0][1]
    for (ya, wa), (yb, wb) in zip(pts, pts[1:]):
        if ya <= y <= yb:
            return wa + (wb - wa) * (y - ya) / (yb - ya)
    return 0.0


def core_hidden(x, y):
    """True where the opaque core covers the casing."""
    d = abs(x - 8)
    if y <= RAIL_TOP:
        return d < CORE_HW
    return d + (y - RAIL_TOP) < CORE_HW


def clamp(v, a=0.0, b=1.0):
    return max(a, min(b, v))


def solid(name, colour):
    save(canvas(16, fill=colour), name)


# ---------------------------------------------------------------------------------------
# Textures
# ---------------------------------------------------------------------------------------

def tex_blade_face():
    """Core faces: aurora-lit fuller floor, rails with a bright inner ridge, bevels."""
    img = canvas(64)
    px = img.load()
    aurora = [VIOLET[1], VIOLET[1], VIOLET[2], NAVY[4], GLACIER[2], TEAL[1], TEAL[1], TEAL[2]]
    for ty in range(64):
        for tx in range(64):
            x, y = texel_xy(tx, ty)
            d = abs(x - 8)
            if y < -0.5 or d >= CORE_HW:
                continue
            band = 0 if y < 4 else (1 if y < 11 else (2 if y < 17 else 3))
            if d < 1.5:                                     # fuller floor
                c = aurora[min(len(aurora) - 1, int(clamp((y - 1) / 20.5) * len(aurora)))]
                if d >= 1.0:
                    c = NAVY[0]                              # shadow at the walls
            elif d < 2.5:                                    # rails
                col = int((d - 1.5) * 2)
                c = [[GLACIER[1], NAVY[3]], [GLACIER[3], NAVY[4]],
                     [GLACIER[4], NAVY[4]], [GLACIER[5], NAVY[5]]][band][col]
            else:                                            # bevels
                col = int((d - 2.5) * 2)
                c = [[NAVY[3], GLACIER[1]], [NAVY[4], GLACIER[2]],
                     [GLACIER[1], GLACIER[3]], [GLACIER[2], GLACIER[4]]][band][col]
            px[tx, ty] = rgba(c)
    draw = ImageDraw.Draw(img)
    # Frozen fractures across the rails and bevels, and trapped bubbles.
    cracks = [[(4.6, 3.5), (5.4, 4.6), (5.0, 5.4)], [(11.4, 8.0), (10.6, 9.2), (11.0, 10.0)],
              [(4.6, 13.0), (5.6, 14.3)], [(10.8, 16.0), (11.4, 17.2)], [(4.8, 19.5), (5.4, 20.6)]]
    for line in cracks:
        draw.line([to_texel(x, y) for x, y in line], fill=rgba(ICE[1]))
    rng = random.Random(7)
    for _ in range(16):
        x = rng.choice([rng.uniform(4.6, 6.4), rng.uniform(9.6, 11.4)])
        y = rng.uniform(2.5, 22.5)
        tx, ty = to_texel(x, y)
        px[int(tx), int(ty)] = rgba(ICE[3])
    save(img, "blade_face")


def facet_square(n, rim_lit, band_lit, rim_dark, band_dark, body, core):
    """A square painted in bands parallel to its edges, so it stays crisp when turned
    45 degrees. The texture's top edge becomes the upper-left edge in the world and
    its right edge the upper-right one; the lower half hides inside the blade."""
    img = canvas(32)
    px = img.load()
    for j in range(n):
        for i in range(n):
            top, right = j, n - 1 - i
            if top == 0:
                c = rim_lit
            elif right == 0:
                c = rim_dark
            elif top <= 2 and top <= right:
                c = band_lit
            elif right <= 2:
                c = band_dark
            elif top + right < n - 2:
                c = body
            else:
                c = core
            px[i, j] = rgba(c)
    return img


def tex_tip():
    """The core's pointed facet: a bevelled square turned to a point."""
    n = int(CORE_HW * math.sqrt(2) * 2 + 0.5)
    save(facet_square(n, ICE[3], GLACIER[4], GLACIER[3], NAVY[5], NAVY[4], NAVY[3]), "tip")


RUNE_GLYPHS = [
    ["..a..", "a.a.a", ".aba.", "a.a.a", "..a.."],
    ["a.a.a", ".aaa.", "..b..", "..a..", "..a.."],
    ["..a..", ".a.a.", "a.b.a", ".a.a.", "..a.."],
    ["a...a", ".a.a.", "..b..", ".a.a.", "a...a"],
]


def tex_runes():
    """Glowing frost runes for the fuller inlay (5 texels wide, cutout)."""
    img = canvas(32)
    px = img.load()
    y = 1
    for k, glyph in enumerate(RUNE_GLYPHS):
        for gy, row in enumerate(glyph):
            for gx, ch in enumerate(row):
                if ch == "a":
                    px[gx, y + gy] = rgba(RUNE[3])
                elif ch == "b":
                    px[gx, y + gy] = rgba(RUNE[4])
        y += 5
        if k < len(RUNE_GLYPHS) - 1:
            for gap in range(3):
                px[2, y + gap] = rgba(RUNE[2])
            y += 3
    save(img, "runes")


def tex_ice(icon=False):
    """The glacial casing: a translucent band with a bright rim and diagonal glints,
    running out into a ridged icicle point. icon=True paints an opaque, quieter
    version for the inventory, where translucency would blend into the grey slot."""
    mask = Image.new("L", (64, 64), 0)
    ys = [p[0] for p in OUTLINE]
    left = [to_texel(8 - half_width(y), y) for y in ys]
    right = [to_texel(8 + half_width(y), y) for y in reversed(ys)]
    ImageDraw.Draw(mask).polygon(left + right, fill=255)
    m = mask.load()

    def inside(tx, ty):
        return 0 <= tx < 64 and 0 <= ty < 64 and m[tx, ty] > 0

    apex = RAIL_TOP + CORE_HW
    img = canvas(64)
    px = img.load()
    for ty in range(64):
        for tx in range(64):
            if not inside(tx, ty):
                continue
            x, y = texel_xy(tx, ty)
            if core_hidden(x, y):
                px[tx, ty] = rgba(NAVY[1])
                continue
            edge = any(not inside(tx + dx, ty + dy) for dx, dy in ((-1, 0), (1, 0), (0, -1)))
            edge2 = any(not inside(tx + dx, ty + dy) for dx, dy in ((-2, 0), (2, 0)))
            near = any(core_hidden(*texel_xy(tx + dx, ty + dy)) for dx, dy in ((-1, 0), (1, 0), (0, 1)))
            if y > apex - 0.6:                               # the icicle point
                if tx in (31, 32):
                    c = (*rgba(ICE[5] if tx == 31 else ICE[3])[:3], 255)   # ridge
                elif edge:
                    c = (*rgba(ICE[5] if x < 8 else ICE[3])[:3], 255)
                else:
                    c = (*rgba(ICE[3] if x < 8 else ICE[2])[:3], 215)
            elif edge:
                c = (*rgba(ICE[5] if x < 8 else ICE[4])[:3], 255)
            elif near:
                c = (*rgba(GLACIER[3])[:3], 235)
            elif edge2:
                c = (*rgba(ICE[3])[:3], 230)
            else:
                tint = BAND[min(len(BAND) - 1, int(clamp(y / 24.0) * len(BAND)))]
                c = (*rgba(tint)[:3], 200)
            if icon:
                c = (*c[:3], 255)
            px[tx, ty] = c
    if icon:
        save(img, "ice_icon")
        return
    rng = random.Random(21)
    for _ in range(40):
        tx, ty = rng.randrange(64), rng.randrange(64)
        if inside(tx, ty) and px[tx, ty][3] < 255 and not core_hidden(*texel_xy(tx, ty)):
            px[tx, ty] = (*rgba(ICE[4])[:3], 240)
    # Diagonal glints: the classic cue for glass and ice.
    for sx, sy, n in ((4.0, 2.6, 3), (3.8, 9.0, 2), (11.7, 5.2, 3), (12.0, 12.6, 2),
                      (4.1, 16.8, 2), (11.8, 19.6, 2), (6.4, 26.4, 2)):
        tx, ty = (int(v) for v in to_texel(sx, sy))
        for k in range(n):
            if inside(tx + k, ty - k) and not core_hidden(*texel_xy(tx + k, ty - k)):
                px[tx + k, ty - k] = rgba(ICE[5])
    save(img, "ice")


def tex_core_side():
    img = canvas(32, fill=NAVY[2])
    for y in range(0, 32, 6):
        fill(img, (0, y, 31, y), NAVY[3])
    save(img, "core_side")
    bev = canvas(32, fill=GLACIER[3])
    fill(bev, (0, 0, 0, 31), ICE[1])
    save(bev, "bevel_side")


def tex_crystals():
    """Opaque crystals: serration teeth, the frost gem and the floating shards."""
    # Teeth: lit edge first, whiter toward the point (row 0 is the point end).
    tooth = canvas(32)
    px = tooth.load()
    cols = ["#ffffff", "#c4eeff", "#96dcff", "#6cc2f4"]
    base = ["#d8f4ff", "#8fd3fb", "#62b6ee", "#3f95da"]
    for j in range(32):
        for i in range(32):
            c = cols[min(i, 3)]
            if j == 0 and i > 0:
                c = "#e4f8ff"
            elif j >= 5:
                c = base[min(i, 3)]
            px[i, j] = rgba(c)
    save(tooth, "tooth")
    solid("tooth_cap", "#d4f2ff")

    gem = facet_square(4, RUNE[4], RUNE[3], RUNE[3], RUNE[2], RUNE[2], RUNE[1])
    gem.load()[1, 1] = rgba(RUNE[4])
    save(gem, "frost_gem")
    solid("frost_gem_side", RUNE[2])

    shard = canvas(32)
    px = shard.load()
    for j in range(32):
        for i in range(32):
            c = [RUNE[4], RUNE[3], TEAL[3], TEAL[2]][min(i, 3)]
            px[i, j] = rgba(c)
    save(shard, "shard")
    solid("shard_cap", RUNE[3])


def tex_guard():
    """Frosted ice for the snowflake arms and the layered hub, a silver rim."""
    guard = canvas(32)
    px = guard.load()
    cols = [FROST[5], FROST[3], FROST[2], FROST[1]]
    for j in range(32):
        for i in range(32):
            c = cols[min(i, 3)]
            if j == 0 and i > 0:
                c = FROST[4]
            px[i, j] = rgba(c)
    rng = random.Random(5)
    for _ in range(18):
        px[rng.randrange(1, 3), rng.randrange(1, 32)] = rgba(FROST[4])
    save(guard, "guard")
    solid("guard_cap", FROST[4])

    hub = canvas(32)
    rows = [FROST[5], FROST[4], FROST[4], FROST[3], FROST[3], FROST[3], FROST[2], FROST[2], FROST[2], FROST[1],
            FROST[1], FROST[0]]
    for j in range(32):
        fill(hub, (0, j, 31, j), rows[min(len(rows) - 1, j)])
    save(hub, "hub")

    inner = canvas(32)
    rows = [GLACIER[5], GLACIER[4], GLACIER[4], GLACIER[3], GLACIER[3], GLACIER[2], GLACIER[2], GLACIER[1],
            GLACIER[1], GLACIER[0]]
    for j in range(32):
        fill(inner, (0, j, 31, j), rows[min(len(rows) - 1, j)])
    save(inner, "hub_inner")

    rim = canvas(32)
    rows = [SILVER[5], SILVER[4], SILVER[4], SILVER[3], SILVER[3], SILVER[3], SILVER[2], SILVER[2], SILVER[2],
            SILVER[1], SILVER[1], SILVER[0]]
    for j in range(32):
        fill(rim, (0, j, 31, j), rows[min(len(rows) - 1, j)])
    save(rim, "silver_rim")

    gem = canvas(32)
    rows = [TEAL[4], TEAL[3], TEAL[2], TEAL[2], TEAL[1], VIOLET[3], VIOLET[2], VIOLET[2], VIOLET[1], VIOLET[1]]
    for j in range(32):
        fill(gem, (0, j, 31, j), rows[min(len(rows) - 1, j)])
    gem.load()[0, 0] = rgba("#ffffff")
    gem.load()[1, 0] = rgba(TEAL[4])
    save(gem, "gem")

    icicle = canvas(32)
    px = icicle.load()
    for j in range(32):
        for i in range(32):
            px[i, j] = rgba([FROST[5], FROST[3], FROST[2]][min(i, 2)] if j else FROST[4])
    save(icicle, "icicle")


def tex_grip():
    silver = canvas(32)
    rows = [SILVER[5], SILVER[4], SILVER[3], SILVER[3], SILVER[2], SILVER[1]]
    for j in range(32):
        fill(silver, (0, j, 31, j), rows[min(j, len(rows) - 1)])
    fill(silver, (0, 0, 0, 31), SILVER[4])
    save(silver, "silver")
    solid("silver_cap", SILVER[3])

    core = canvas(32, fill=SILVER[2])
    fill(core, (0, 0, 0, 31), SILVER[4])
    save(core, "silver_core")

    # White fur: bright crowns, soft strands, lavender shadow tucked under each roll.
    fur = art_rows([
        "wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww",
        "wwlwwwlwwwlwwwlwwwlwwwlwwwlwwwlw",
        "lwllllwllllwllllwllllwllllwlllll",
        "mlmmmlmmmlmmmlmmmlmmmlmmmlmmmlmm",
    ] + ["mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm"] * 4,
        {"w": FUR[4], "l": FUR[3], "m": FUR[2], "d": FUR[1]})
    save(fur, "fur")
    solid("fur_cap", FUR[3])


def art_rows(rows, palette, size=32):
    img = canvas(size)
    px = img.load()
    for j, row in enumerate(rows):
        for i, ch in enumerate(row[:size]):
            if ch != ".":
                px[i, j] = rgba(palette[ch])
    return img


def tex_snow():
    """The glowing snowflake pommel: white-hot edges, icy cyan bodies."""
    img = canvas(32)
    px = img.load()
    for j in range(32):
        for i in range(32):
            px[i, j] = rgba([RUNE[4], RUNE[3], RUNE[2]][min(i, 2)] if j else RUNE[4])
    save(img, "snow")
    solid("snow_cap", RUNE[4])


def textures() -> None:
    tex_blade_face()
    tex_tip()
    tex_runes()
    tex_ice()
    tex_ice(icon=True)
    tex_core_side()
    tex_crystals()
    tex_guard()
    tex_grip()
    tex_snow()


# ---------------------------------------------------------------------------------------
# Geometry helpers
# ---------------------------------------------------------------------------------------

def hexagon(cx, cy, r, depths, tex, **kw):
    """A flat-topped hexagonal plate from three turned boxes; staggered depths keep
    their faces apart and read as cut facets."""
    parts = []
    s, h = r, r * math.sqrt(3) / 2
    for ang, dz in zip((0, 60, 120), depths):
        b = box((cx - s / 2, cy - h, 8 - dz), (cx + s / 2, cy + h, 8 + dz), tex, **kw)
        if ang:
            turn(b, ang, "z", (cx, cy, 8))
        parts.append(b)
    return parts


def diamond(cx, cy, side, dz, tex, uv_front, side_tex, angle=45.0, **kw):
    """A square turned about Z: a crystal facet pointing up and down."""
    faces = {"north": (tex, uv_front), "south": (tex, uv_front)}
    for f in ("east", "west"):
        faces[f] = (side_tex, [0, 0, dz * 2, side])
    for f in ("up", "down"):
        faces[f] = (side_tex, [0, 0, side, dz * 2])
    b = box((cx - side / 2, cy - side / 2, 8 - dz), (cx + side / 2, cy + side / 2, 8 + dz), tex,
            faces=faces, **kw)
    return turn(b, angle, "z", (cx, cy, 8))


def polar(c, ang, length):
    a = math.radians(ang)
    return (c[0] + length * math.cos(a), c[1] + length * math.sin(a), c[2] if len(c) > 2 else 8)


def crystal(p0, p1, w, d, tex, cap=None, back=False, **kw):
    """A flat crystal from p0 to a point at p1 (in the XY plane): a bar capped by a
    square turned so one corner is the point. back=True points the other end too."""
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    length = math.hypot(dx, dy)
    ux, uy = dx / length, dy / length
    ang = math.degrees(math.atan2(uy, ux))
    s = w / math.sqrt(2)
    ends = [(p1, 1)] + ([(p0, -1)] if back else [])
    a = (p0[0] + ux * w / 2, p0[1] + uy * w / 2, p0[2]) if back else p0
    b = (p1[0] - ux * w / 2, p1[1] - uy * w / 2, p1[2])
    parts = [bar(a, b, w, d, tex, **kw)]
    for p, sgn in ends:
        c = (p[0] - sgn * ux * w / 2, p[1] - sgn * uy * w / 2, p[2])
        cap_box = box((c[0] - s / 2, c[1] - s / 2, c[2] - d / 2 + 0.05),
                      (c[0] + s / 2, c[1] + s / 2, c[2] + d / 2 - 0.05), cap or tex, **kw)
        parts.append(turn(cap_box, ang - 45, "z", c))
    return parts


def rod(y0, y1, r, tex, cap):
    """An octagonal rod along the handle; one flat colour on the ends hides the
    coplanar caps of its slabs."""
    return prism((8, (y0 + y1) / 2, 8), r, y1 - y0, tex, cap=cap)


# ---------------------------------------------------------------------------------------
# Parts
# ---------------------------------------------------------------------------------------

def blade():
    opaque, clear = [], []
    # Recessed fuller floor (faintly lit by the aurora) and the glowing rune inlay.
    opaque.append(pbox((FULLER[0], 0.5, 7.45), (FULLER[1], FULLER_TOP, 8.55), "blade_face", "core_side", glow=4))
    opaque.append(box((6.75, 4.2, 7.4), (9.25, 20.2, 8.6), "runes", glow=15,
                      faces={"south": ("runes", [0, 0, 2.5, 16]), "north": ("runes", [2.5, 0, 0, 16])},
                      skip=("east", "west", "up", "down")))
    # Rails either side of the fuller (thickest), a bridge above it, then the bevels.
    for x0, x1 in ((5.5, FULLER[0]), (FULLER[1], 10.5)):
        opaque.append(pbox((x0, 0.5, 6.8), (x1, RAIL_TOP, 9.2), "blade_face", "core_side"))
    opaque.append(pbox((FULLER[0], FULLER_TOP, 6.8), (FULLER[1], RAIL_TOP, 9.2), "blade_face", "core_side"))
    for x0, x1 in ((8 - CORE_HW, 5.5), (10.5, 8 + CORE_HW)):
        opaque.append(pbox((x0, 0.5, 7.2), (x1, RAIL_TOP, 8.8), "blade_face", "bevel_side"))
    # Pointed tip facet: a turned square whose lower half hides inside the rails.
    side = CORE_HW * math.sqrt(2)
    opaque.append(diamond(8, RAIL_TOP, side, 0.7, "tip", [0, 0, side, side], "bevel_side"))
    # Frost gem where the fuller ends.
    opaque.append(diamond(8, FULLER_TOP + 0.3, 2.0, 1.35, "frost_gem", [0, 0, 2, 2], "frost_gem_side", glow=13))
    # Jagged crystal serrations, built on the right and mirrored so the light stays on top.
    right, left = [], []
    for rows, out in ((TEETH_RIGHT, right), (TEETH_LEFT, left)):
        for y, ang, length in rows:
            base = (8 + half_width(y) - 0.7, y, 8)
            a = math.radians(ang)
            tip = (base[0] + length * math.sin(a), y + length * math.cos(a), 8)
            out += crystal(base, tip, 1.6, 1.2, "tooth", cap="tooth_cap")
            b = math.radians(ang - 22)
            base2 = (base[0] - 0.3, y + 0.9, 8)
            tip2 = (base2[0] + 0.55 * length * math.sin(b), base2[1] + 0.55 * length * math.cos(b), 8)
            out += crystal(base2, tip2, 1.0, 1.0, "tooth", cap="tooth_cap")
    opaque += right + mirror(left, "x", 8)
    # A solid crystal spine rising out of the core's point into the icicle tip.
    opaque += crystal((8, RAIL_TOP + CORE_HW - 1.0, 8), (8, 30.7, 8), 1.2, 1.1, "tooth", cap="tooth_cap")
    # The glacial casing, translucent, drawn after everything opaque.
    clear.append(pbox((0.5, 0.0, 7.6), (15.5, 32.0, 8.4), "ice"))
    return opaque, clear


def guard():
    parts = hexagon(8, HUB_Y, HUB_R + 0.35, (1.38, 1.33, 1.28), "silver_rim")
    parts += hexagon(8, HUB_Y, HUB_R, (1.55, 1.5, 1.45), "hub")
    parts += hexagon(8, HUB_Y, 2.6, (1.85, 1.8, 1.75), "hub_inner")
    parts += hexagon(8, HUB_Y, 1.5, (2.15, 2.1, 2.05), "gem", glow=12)
    # One snowflake arm, built on the right and mirrored.
    arm = []
    start = (8 + HUB_R - 0.6, HUB_Y + 0.1, 8)
    ang, length = 16, 8.8
    end = polar(start, ang, length)
    arm += crystal(start, end, 1.7, 1.6, "guard", cap="guard_cap")
    for t, blen, bw in ((0.36, 3.0, 1.0), (0.7, 2.2, 0.85)):
        p = polar(start, ang, length * t)
        for off, dz in ((-60, 1.0), (60, 0.92)):
            arm += crystal(p, polar(p, ang + off, blen), bw, dz, "guard", cap="guard_cap")
    for t, ilen in ((0.2, 2.6), (0.5, 1.9), (0.82, 1.3)):
        p = polar(start, ang, length * t)
        arm += crystal((p[0], p[1] - 0.2, 8), (p[0] - 0.1, p[1] - 0.2 - ilen, 8), 0.62, 0.62, "icicle",
                       cap="guard_cap")
    parts += arm + mirror(arm, "x", 8)
    return parts


def grip_parts():
    parts = []
    parts += rod(-5.3, -4.3, 2.0, "silver", "silver_cap")           # top ferrule
    parts += rod(-11.6, -5.3, 1.25, "silver_core", "silver_cap")    # the handle itself
    for y0, y1 in ((-7.2, -5.3), (-9.3, -7.6), (-11.4, -9.7)):   # fur rolls
        parts += rod(y0, y1, 1.9, "fur", "fur_cap")
    for y0, y1 in ((-7.6, -7.2), (-9.7, -9.3)):                  # silver cords between
        parts += rod(y0, y1, 1.55, "silver", "silver_cap")
    parts += rod(-12.3, -11.4, 1.95, "silver", "silver_cap")        # bottom ferrule
    return parts


def pommel():
    parts = []
    c = (8, -13.5, 8)
    for ang, length, branch in ((0, 3.0, True), (180, 3.0, True), (240, 2.65, True), (300, 2.65, True),
                                (60, 1.75, False), (120, 1.75, False)):
        parts += crystal(c, polar(c, ang, length), 1.0, 1.0, "snow", cap="snow_cap", glow=12)
        if branch:
            mid = polar(c, ang, length * 0.58)
            for off, dz in ((-60, 0.6), (60, 0.52)):
                parts += crystal(mid, polar(mid, ang + off, 1.0), 0.55, dz, "snow", cap="snow_cap", glow=12)
    parts += hexagon(8, c[1], 1.45, (1.15, 1.1, 1.05), "gem", glow=14)
    parts += hexagon(8, c[1], 0.75, (1.35, 1.3, 1.25), "snow_cap", glow=15)
    return parts


def shards():
    out = []
    for cx, cy, ang, length, w, yaw in ((3.3, 27.4, 28, 3.2, 1.05, 25), (12.9, 25.2, -22, 3.6, 1.15, -30),
                                        (11.4, 30.3, -40, 2.1, 0.75, 40)):
        a = math.radians(90 + ang)
        u = (math.cos(a), math.sin(a))
        p0 = (cx - u[0] * length / 2, cy - u[1] * length / 2, 8)
        p1 = (cx + u[0] * length / 2, cy + u[1] * length / 2, 8)
        shard = crystal(p0, p1, w, w * 0.8, "shard", cap="shard_cap", back=True, glow=13)
        out += turn(shard, yaw, "y", (cx, cy, 8))
    return out


def transforms(parts):
    d = display("sword", parts, grip=GRIP, size=SIZE)
    # Third person: carried with the blade raised and angled out from the body.
    lift = math.radians(36)
    d["thirdperson_righthand"] = place({"y": (-0.55, math.sin(lift), math.cos(lift)), "z": (1, 0, 0.45)},
                                       GRIP, "fist", 0.85 * SIZE)
    # First person: the blade rises from the lower right, clear of the crosshair.
    d["firstperson_righthand"] = place({"y": (-0.3, 0.92, -0.25), "z": (-0.6, 0, 0.8)},
                                       GRIP, (0.62, -0.55, -0.95), 0.6 * SIZE, pose=None)
    return d


def models() -> dict:
    opaque, clear = blade()
    parts = opaque + guard() + grip_parts() + pommel() + shards() + clear   # translucent last
    shown = transforms(parts)
    # Inventory icon: the same sculpt with an opaque casing so it reads crisply in a slot.
    icon = copy(parts)
    for face in icon[-1]["faces"].values():
        face["texture"] = "#ice_icon"
    return {"main": model(parts, shown), "gui": model(icon, copy(shown))}
