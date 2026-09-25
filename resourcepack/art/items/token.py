"""Token: the rare JoshyMC server token.

A thick gold coin: a reeded edge, a raised rolled lip, a recessed field ringed with a
fine engraved legend of dots, and a raised royal crown (cube finials, riveted band, a
rolled base ring) with a faceted blue diamond set in its centre, like the JoshyMC logo.
The reverse carries the logo's hexagonal badge around a second diamond.

Every flat face samples a projected 32 px texture in which the coin spans 28 texels, so at
GUI scale 2 each texel lands on a whole screen pixel even with the icon's tilt, and the
relief is a true extrusion of the pixel art: raised parts show the painting on their
fronts and the painted edge colours on their sides. Animation (32 frames x 2 ticks): a
bright double shine sweeps across the face, the lip and round the reeded edge, a glint
follows it over the top finial and the diamond, sparkles and a slow fire keep the diamond
alive, and a warm golden glow swells from it and fades.
"""
from __future__ import annotations

import math

from PIL import Image

from art.kit import (animate, box, canvas, display, model, place, rgba, save, save_animation, shine, sparkle, turn,
                     wave)

ID = "token"
NAME = "Token"
KIND = "item"
COUNTERPART = "item/resin_clump"

# --------------------------------------------------------------------------------------
# Palettes (darkest -> lightest), hand-tuned hue shifts
# --------------------------------------------------------------------------------------
G = ["#2b1010", "#57250b", "#8a4212", "#bd6d1a", "#dd9626", "#f2ba35", "#ffdb62", "#fff1a8", "#fffbe6"]
F = ["#6e3410", "#8f4a14", "#aa6119", "#c07a20", "#d08e2a", "#dea137"]   # the recessed field
B = ["#0b1446", "#132f8c", "#1a5bcc", "#2a8ff0", "#5cc4ff", "#a8ecff", "#effdff"]
STONE = ["#15101c", "#211a2a", "#30283a", "#453b50"]                     # the badge's dark inlay
WARM = "#ffe58a"          # the breathing glow: a saturated warm gold, not a wash
SHINE = dict(colour="#fffbe8", width=5.0, strength=0.72, angle=35.0, pause=0.35)
TRAIL = dict(colour="#ffffff", width=2.0, strength=0.55, angle=35.0, pause=0.35)
TRAIL_LAG = 0.05
GLOW_AT, GLOW_WIDTH = 0.55, 0.4   # the warm glow swells just after the light crosses the diamond

# --------------------------------------------------------------------------------------
# Layout (model units). The coin stands in the XY plane, centred on (8, 8), face = +Z.
# --------------------------------------------------------------------------------------
C = 8.0
R = 6.4                   # outer radius (apothem of the reeded edge)
R_IN = 5.55               # inner radius of the rim: the step down to the field
TEX = 32                  # face-projected textures are 32 px
SPAN = 28                 # ...and the coin spans 28 of those texels: one per screen pixel at GUI 2
UNIT = 2 * R / SPAN       # 0.457 units per texel
Z_LIP_F, Z_FIELD_F = 9.4, 9.0
Z_FIELD_B, Z_LIP_B = 7.0, 6.6
STAVES = 32
FRAMES, FRAMETIME = 32, 2
RIM_R = SPAN / 2          # texel radius of the coin (14)
LIP_R = 12.1              # texel radius where the lip (ring plate) starts
LEGEND_R = 11.0           # texel radius where the legend ring starts
BEZEL_H, GEM_H = 0.6, 0.45


def wx(tx: float) -> float:
    """Model x of the left edge of face-texture column tx (front view)."""
    return C + (tx - 16) * UNIT


def wy(ty: float) -> float:
    """Model y of the top edge of face-texture row ty."""
    return C - (ty - 16) * UNIT


def bx(tx: float) -> float:
    """Model x of the left edge (as the back viewer sees it) of back-texture column tx."""
    return C - (tx - 16) * UNIT


def uv(t: float) -> float:
    """UV coordinate (0..16) of texel edge t in a 32 px texture."""
    return round(max(0.0, min(16.0, t * 16 / TEX)), 4)


def radius(tx: int, ty: int) -> float:
    return math.hypot(tx + 0.5 - 16, ty + 0.5 - 16)


def light_of(tx: int, ty: int) -> float:
    """1 facing the upper-left light, -1 facing away (by direction from the centre)."""
    dx, dy = tx + 0.5 - 16, ty + 0.5 - 16
    r = math.hypot(dx, dy) or 1.0
    return -(dx + dy) / (r * math.sqrt(2))


def put(img, x, y, colour) -> None:
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), rgba(colour))


PAL = {"k": G[0], "o": G[1], "1": G[1], "2": G[2], "3": G[3], "4": G[4], "5": G[5], "6": G[6], "7": G[7],
       "8": G[8], "A": B[0], "B": B[1], "C": B[2], "D": B[3], "E": B[4], "F": B[5], "W": B[6]}


def stamp(img, rows, x, y, pal=PAL) -> None:
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch != ".":
                put(img, x + i, y + j, pal[ch])


def outline_tones(img, rows) -> None:
    """Selective outlining: outline texels ('o') that border the shape from the lit upper
    left turn warm brown, those on the shaded lower right go darkest."""
    def body(i, j):
        return 0 <= j < len(rows) and 0 <= i < len(rows[j]) and rows[j][i] not in ".o"
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch != "o":
                continue
            lit = body(i + 1, j) or body(i, j + 1)          # the shape lies right/below: upper-left rim
            dark = body(i - 1, j) or body(i, j - 1)         # the shape lies left/above: lower-right rim
            if lit and not dark:
                put(img, i, j, G[2])
            elif dark and not lit:
                put(img, i, j, G[0])


# --------------------------------------------------------------------------------------
# The emblem, drawn in face-texture texels (32 x 32; the coin centre is texel edge 16).
# One outline runs round the whole crown; parts are told apart by light, not lines.
# --------------------------------------------------------------------------------------
CROWN = [
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "..............oooo..............",
    ".............o7886o.............",
    ".............o6654o.............",
    ".......oooo..o5433o..oooo.......",
    "......o7886o..oooo..o7886o......",
    "......o6654o........o6654o......",
    "......o5433o........o5433o......",
    ".......o654o........o654o.......",
    ".......o654o........o654o.......",
    ".......o654o........o654o.......",
    ".......o7777777777777776o.......",
    ".......o6555555555555554o.......",
    ".......o5444444444444443o.......",
    ".......o6555555555555553o.......",
    ".......o4333333333333332o.......",
    "......o777777777777777776o......",
    ".......o6575757575757574o.......",
    "........o44444444444443o........",
    ".........oooooooooooooo.........",
]
STUD = ["75", "42"]
STUDS = [(9, 17), (21, 17)]

# Relief boxes that extrude the emblem: (inclusive texel rect, depth in units).
RELIEF = [
    ((8, 16, 23, 20), 0.45),       # band
    ((7, 21, 24, 21), 0.55),       # base ring: a rounded bulge
    ((8, 22, 23, 22), 0.65),
    ((9, 23, 22, 23), 0.45),
    ((8, 13, 10, 15), 0.45),       # prongs
    ((21, 13, 23, 15), 0.45),
    ((14, 7, 17, 9), 0.7),         # finials
    ((7, 10, 10, 12), 0.7),
    ((21, 10, 24, 12), 0.7),
    ((9, 17, 10, 18), 0.55),       # studs
    ((21, 17, 22, 18), 0.55),
]


def rhombus(n: int, cx: float = 16.0, cy: float = 16.0) -> set:
    """Texels of an n-texel-wide diamond centred on texel edge (cx, cy)."""
    h = n / 2
    return {(tx, ty) for ty in range(32) for tx in range(32)
            if abs(tx + 0.5 - cx) + abs(ty + 0.5 - cy) <= h}


GEM = rhombus(6)
BEZEL = rhombus(8)
SETTING = rhombus(10)


def hexagon(rc: float) -> set:
    """Texels of a pointy-topped hexagon of circumradius rc, centred on the coin."""
    w = rc * math.sqrt(3) / 2
    out = set()
    for ty in range(32):
        for tx in range(32):
            dx, dy = abs(tx + 0.5 - 16), abs(ty + 0.5 - 16)
            if dx <= w and dy <= rc - dx / math.sqrt(3):
                out.add((tx, ty))
    return out


BADGE = hexagon(8.9)
BADGE_IN = hexagon(6.9)


# --------------------------------------------------------------------------------------
# Painting
# --------------------------------------------------------------------------------------

def paint_field(img) -> None:
    """The recessed field: a shallow dish lit from the upper left, faint sunburst rays."""
    for ty in range(32):
        for tx in range(32):
            r = radius(tx, ty)
            if r >= RIM_R:
                continue
            if r >= LIP_R:
                put(img, tx, ty, G[5])                      # under the lip
                continue
            dx, dy = tx + 0.5 - 16, ty + 0.5 - 16
            lit = 0.05 * (dx + dy)                          # the dish: lower right faces the light
            ray = math.floor((math.atan2(dy, dx) + math.pi) / (2 * math.pi) * 16) % 2
            c = F[3] if ray else F[4]
            if lit > 0.35:
                c = F[4] if ray else F[5]
            elif lit < -0.3:
                c = F[2] if ray else F[3]
            put(img, tx, ty, c)


def paint_legend(img, words: str) -> None:
    """The engraved legend: runs of dots like tiny lettering."""
    ring = sorted((math.atan2(ty + 0.5 - 16, tx + 0.5 - 16), tx, ty)
                  for ty in range(32) for tx in range(32) if LEGEND_R <= radius(tx, ty) < LIP_R)
    for i, (a, tx, ty) in enumerate(ring):
        lit = light_of(tx, ty)
        put(img, tx, ty, F[2] if lit > 0.4 else F[3])       # the lip shades the upper left
        if words[i % len(words)] == "1":
            put(img, tx, ty, G[1])
    for sx in (4, 27):                                      # star separators at 9 and 3 o'clock
        for sy, c in ((14, F[3]), (15, G[7]), (16, G[6]), (17, F[3])):
            if LEGEND_R <= radius(sx, sy) < LIP_R:
                put(img, sx, sy, c)


def paint_gem(img, only=False) -> None:
    """The setting's outline, the gold bezel and the four-facet diamond."""
    if not only:
        for (tx, ty) in SETTING - BEZEL:
            put(img, tx, ty, G[1])
        for (tx, ty) in BEZEL - GEM:
            dx, dy = tx + 0.5 - 16, ty + 0.5 - 16
            put(img, tx, ty, G[7] if dx + dy < -1 else (G[5] if dx + dy < 1 else G[3]))
    for (tx, ty) in GEM:
        dx, dy = tx + 0.5 - 16, ty + 0.5 - 16
        if dx < 0 and dy < 0:
            c = B[5]
        elif dx >= 0 and dy < 0:
            c = B[4]
        elif dx < 0 and dy >= 0:
            c = B[3]
        else:
            c = B[2]
        if abs(dx) + abs(dy) > 2.2:                          # the girdle: darker rim
            c = {B[5]: B[4], B[4]: B[3], B[3]: B[2], B[2]: B[1]}[c]
        put(img, tx, ty, c)
    put(img, 14, 15, B[6])
    put(img, 15, 14, B[6])


def face_base() -> Image.Image:
    img = canvas(TEX)
    paint_field(img)
    paint_legend(img, "1010110101011010100101101011010110100")
    stamp(img, CROWN, 0, 0)
    outline_tones(img, CROWN)
    for x, y in STUDS:
        stamp(img, STUD, x, y)
    paint_gem(img)
    return img


def back_base() -> Image.Image:
    """The reverse: the logo's hexagonal badge, a dark inlay and a second diamond."""
    img = canvas(TEX)
    paint_field(img)
    paint_legend(img, "1101011010110100101101010110101101001")
    ring = set()
    for (tx, ty) in BADGE:
        for ddx, ddy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            if (tx + ddx, ty + ddy) not in BADGE:
                ring.add((tx + ddx, ty + ddy))
    for (tx, ty) in ring:
        put(img, tx, ty, G[1])
    for (tx, ty) in BADGE - BADGE_IN:
        lit = light_of(tx, ty)
        outer = any((tx + a, ty + b) not in BADGE for a, b in ((-1, 0), (1, 0), (0, -1), (0, 1)))
        if outer:
            c = G[7] if lit > 0.3 else (G[5] if lit > -0.3 else G[3])
        else:
            c = G[3] if lit > 0.3 else (G[5] if lit > -0.3 else G[6])
        put(img, tx, ty, c)
    for (tx, ty) in BADGE_IN:
        dx, dy = tx + 0.5 - 16, ty + 0.5 - 16
        c = STONE[2] if dx + dy < -4 else (STONE[1] if dx + dy < 4 else STONE[0])
        if abs(dx - dy) < 0.8 and dx + dy < -5:
            c = STONE[3]
        put(img, tx, ty, c)
    paint_gem(img)
    return img


def ring_base() -> Image.Image:
    """The rolled lip: outer texel lit on the upper left, inner texel lit on the lower right."""
    img = canvas(TEX)
    outer = [G[3], G[4], G[5], G[6], G[7]]
    inner = [G[6], G[6], G[5], G[5], G[4]]
    for ty in range(32):
        for tx in range(32):
            r = radius(tx, ty)
            if not LIP_R <= r < RIM_R:
                continue
            lit = light_of(tx, ty)
            k = min(4, int((lit + 1) / 2 * 5))
            put(img, tx, ty, outer[k] if r >= RIM_R - 1 else inner[4 - k])
            if r >= RIM_R - 1 and lit > 0.97:
                put(img, tx, ty, G[8])                      # specular on the rolled lip
    return img


def edge_theta(j: int) -> float:
    """Angle (radians, 0 = +X, counter-clockwise) of edge-texture column j: stave j // 2."""
    k, half = divmod(j, 2)
    step = 360 / STAVES
    return math.radians(90 - k * step + (step / 4 if half == 0 else -step / 4))


def edge_base() -> Image.Image:
    """64 px: rows 0-7 the reeded edge (back -> front), rows 8-15 the rim's inner wall
    (front -> back), rows 16-19 the stave tops. Two columns per stave, clockwise."""
    img = canvas(64)
    for j in range(64):
        theta = edge_theta(j)
        for row in range(8):
            c = G[5] if j % 2 == 0 else G[3]
            if row in (0, 7):
                c = G[6] if j % 2 == 0 else G[5]            # the rim corners catch light
            put(img, j, row, c)
        lit = math.cos(theta + math.pi / 4)                 # the wall facing the upper-left light
        wall = G[5] if lit > 0.5 else (G[4] if lit > 0 else (G[3] if lit > -0.5 else G[2]))
        for row in range(8, 16):
            put(img, j, row, wall)
        for row in range(16, 20):
            put(img, j, row, G[5])
    return img


def gem_side_base() -> Image.Image:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            put(img, x, y, B[3] if y < 4 else B[2])
    return img


# --------------------------------------------------------------------------------------
# Animation
# --------------------------------------------------------------------------------------

def band(xc: float, yc: float, t: float, spec: dict) -> float:
    """kit.shine()'s blend factor at a point in face-texture texel units, so parts that
    are not face-projected (the edge) light up exactly when the band crosses them."""
    run = 1.0 - spec["pause"]
    if t >= run:
        return 0.0
    a = math.radians(spec["angle"])
    dx, dy = math.cos(a), math.sin(a)
    lo, hi = min(0.0, TEX * dy), TEX * dx + max(0.0, TEX * dy)
    w = spec["width"]
    centre = lo - w * 2 + (hi - lo + w * 4) * (t / run)
    d = abs(xc * dx + yc * dy - centre)
    return max(0.0, 1.0 - d / (w / 2 + 0.5)) * spec["strength"]


def glint(img, t: float) -> Image.Image:
    """The double shine every face-projected texture shares."""
    img = shine(img, t, **SHINE)
    return shine(img, (t - TRAIL_LAG) % 1.0, **TRAIL)


def blend(c, target, k):
    return (round(c[0] + (target[0] - c[0]) * k), round(c[1] + (target[1] - c[1]) * k),
            round(c[2] + (target[2] - c[2]) * k), c[3])


def breathe(img, t: float, strength: float = 0.5, reach: float = 12.0) -> Image.Image:
    """A warm glow swelling from the diamond and fading again, once a loop."""
    out = img.copy()
    px = out.load()
    g = swell(t)
    w = rgba(WARM)
    for ty in range(out.height):
        for tx in range(out.width):
            c = px[tx, ty]
            if c[3] == 0:
                continue
            f = max(0.0, 1.0 - radius(tx, ty) / reach) ** 1.2
            k = strength * g * f
            if k > 0.004:
                px[tx, ty] = blend(c, w, k)
    return out


def pulse(t: float, at: float, width: float) -> float:
    """0..1..0 bump centred on loop phase `at` (wraps round the loop)."""
    d = abs((t - at + 0.5) % 1.0 - 0.5)
    return math.cos(d / width * math.pi / 2) ** 2 if d < width else 0.0


def swell(t: float) -> float:
    """The warm glow's strength through the loop (0..1)."""
    return pulse(t, GLOW_AT, GLOW_WIDTH)


def masked(img, texels: set) -> Image.Image:
    out = canvas(img.width)
    src, dst = img.load(), out.load()
    for (tx, ty) in texels:
        dst[tx, ty] = src[tx, ty]
    return out


BASES: dict = {}


def face_frame(t: float) -> Image.Image:
    return glint(breathe(BASES["face"], t), t)


def ring_frame(t: float) -> Image.Image:
    return glint(BASES["ring"], t)


def back_frame(t: float) -> Image.Image:
    return glint(breathe(BASES["back"], t, 0.18), t)


def edge_frame(t: float) -> Image.Image:
    img = BASES["edge"].copy()
    px = img.load()
    hot = rgba(SHINE["colour"])
    for j in range(64):
        theta = edge_theta(j)
        xc, yc = 16 + RIM_R * math.cos(theta), 16 - RIM_R * math.sin(theta)
        k = min(1.0, 1.25 * band(xc, yc, t, SHINE) + band(xc, yc, (t - TRAIL_LAG) % 1.0, TRAIL))
        if k <= 0:
            continue
        for row in range(20):
            px[j, row] = blend(px[j, row], hot, k)
    return img


def gem_frame(t: float) -> Image.Image:
    """The diamond alone: it brightens with the breath, a slow fire circles its facets
    (upper left, upper right, lower right, lower left) and it flashes as the shine passes."""
    img = canvas(TEX)
    paint_gem(img, only=True)
    px = img.load()
    hi = rgba(B[6])
    for (tx, ty) in GEM:
        dx, dy = tx + 0.5 - 16, ty + 0.5 - 16
        q = (0 if dx < 0 else 1) if dy < 0 else (3 if dx < 0 else 2)
        lift = 0.18 * swell(t) + 0.16 * wave(t, -q * 0.25)
        px[tx, ty] = blend(px[tx, ty], hi, lift)
    return glint(img, t)


# Twinkles: (texel x, y, loop phase, width, reach). The first two follow the shine as it
# crosses the top finial and then the diamond; the others keep the gem alive in the pause.
TWINKLES = [(15, 7, 0.29, 0.08, 2), (14, 15, 0.37, 0.12, 3), (18, 17, 0.66, 0.1, 2), (13, 18, 0.86, 0.07, 1)]


def twinkle_frame(t: float) -> Image.Image:
    img = canvas(TEX)
    for x, y, at, width, reach in TWINKLES:
        sparkle(img, x, y, pulse(t, at, width), "#ffffff", reach)
    return img


def gem_side_frame(t: float) -> Image.Image:
    img = BASES["gem_side"].copy()
    lift = 0.35 * swell(t)
    px = img.load()
    hi = rgba(B[5])
    for y in range(16):
        for x in range(16):
            px[x, y] = blend(px[x, y], hi, lift)
    return img


def textures() -> None:
    BASES.update(face=face_base(), ring=ring_base(), back=back_base(), edge=edge_base(),
                 gem_side=gem_side_base())
    faces = animate(face_frame, FRAMES)
    save_animation(faces, "face", frametime=FRAMETIME)
    save_animation([masked(f, BEZEL) for f in faces], "bezel", frametime=FRAMETIME)
    save_animation(animate(ring_frame, FRAMES), "ring", frametime=FRAMETIME)
    backs = animate(back_frame, FRAMES)
    save_animation(backs, "back", frametime=FRAMETIME)
    save_animation([masked(f, BEZEL) for f in backs], "back_bezel", frametime=FRAMETIME)
    save_animation(animate(edge_frame, FRAMES), "edge", frametime=FRAMETIME)
    save_animation(animate(gem_frame, FRAMES), "gem", frametime=FRAMETIME, interpolate=True)
    save_animation(animate(twinkle_frame, FRAMES), "twinkle", frametime=FRAMETIME, interpolate=True)
    save_animation(animate(gem_side_frame, FRAMES), "gem_side", frametime=FRAMETIME, interpolate=True)
    save(canvas(16, fill=G[5]), "gold")


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------
E = 0.02   # half-width of a degenerate UV strip: sides sample one texel row/column


def plate(x0, y0, x1, y1, z, tex, back=False, glow=0) -> dict:
    """A flat face-projected plate over texels x0..x1, y0..y1 (inclusive) at depth z."""
    face_uv = [uv(x0), uv(y0), uv(x1 + 1), uv(y1 + 1)]
    if back:
        frm, to = (bx(x1 + 1), wy(y1 + 1), z), (bx(x0), wy(y0), z)
        return box(frm, to, tex, faces={"north": (tex, face_uv)},
                   skip=("south", "east", "west", "up", "down"), shade=False, glow=glow)
    frm, to = (wx(x0), wy(y1 + 1), z), (wx(x1 + 1), wy(y0), z)
    return box(frm, to, tex, faces={"south": (tex, face_uv)},
               skip=("north", "east", "west", "up", "down"), shade=False, glow=glow)


def relief(rect, depth: float, tex: str = "face", back: bool = False, glow: int = 0) -> dict:
    """Extrude texels x0..x1, y0..y1 of a projected texture: the front shows the painting,
    each side shows the painted colours along that edge (lit left/top, shaded right/bottom)."""
    x0, y0, x1, y1 = rect
    cl, cr = uv(x0 + 0.5), uv(x1 + 0.5)
    ct, cb = uv(y0 + 0.5), uv(y1 + 0.5)
    vs = [uv(y0), uv(y1 + 1)]
    front = [uv(x0), uv(y0), uv(x1 + 1), uv(y1 + 1)]
    if back:
        frm, to = (bx(x1 + 1), wy(y1 + 1), Z_FIELD_B - depth), (bx(x0), wy(y0), Z_FIELD_B)
        faces = {"north": (tex, front),
                 "east": (tex, [cl - E, vs[0], cl + E, vs[1]]),       # the back's left is +X
                 "west": (tex, [cr - E, vs[0], cr + E, vs[1]]),
                 "up": (tex, [uv(x1 + 1), ct - E, uv(x0), ct + E]),
                 "down": (tex, [uv(x1 + 1), cb - E, uv(x0), cb + E])}
        return box(frm, to, tex, faces=faces, skip=("south",), shade=False, glow=glow)
    frm, to = (wx(x0), wy(y1 + 1), Z_FIELD_F), (wx(x1 + 1), wy(y0), Z_FIELD_F + depth)
    faces = {"south": (tex, front),
             "west": (tex, [cl - E, vs[0], cl + E, vs[1]]),
             "east": (tex, [cr - E, vs[0], cr + E, vs[1]]),
             "up": (tex, [uv(x0), ct - E, uv(x1 + 1), ct + E]),
             "down": (tex, [uv(x0), cb - E, uv(x1 + 1), cb + E])}
    return box(frm, to, tex, faces=faces, skip=("north",), shade=False, glow=glow)


def rects_of(mask: set) -> list[tuple]:
    """A texel mask as inclusive rectangles: each row's runs, merged down the rows while
    they keep the same extent (so straight sides become one box)."""
    rows: dict = {}
    for (x, y) in mask:
        rows.setdefault(y, []).append(x)
    runs = {}
    for y, xs in rows.items():
        xs.sort()
        segs, start = [], xs[0]
        for a, b in zip(xs, xs[1:] + [None]):
            if b != a + 1:
                segs.append((start, a))
                start = b
        runs[y] = segs
    rects, open_ = [], {}
    for y in range(min(runs), max(runs) + 2):
        segs = set(runs.get(y, []))
        for key in [k for k in open_ if k not in segs]:
            rects.append((key[0], open_.pop(key), key[1], y - 1))
        for key in segs:
            open_.setdefault(key, y)
    return sorted(rects, key=lambda r: (r[1], r[0]))


def staves() -> list[dict]:
    """The reeded edge and the rim's inner wall: 32 staves, each with its own two columns
    of the edge texture so the shine travels round the rim."""
    parts = []
    w = 2 * R * math.tan(math.pi / STAVES) + 0.03
    for k in range(STAVES):
        u0, u1 = k * 16 / STAVES, (k + 1) * 16 / STAVES
        e = box((C - w / 2, C + R_IN, Z_LIP_B), (C + w / 2, C + R, Z_LIP_F), "edge",
                faces={"up": ("edge", [u0, 0, u1, 2]), "down": ("edge", [u0, 2, u1, 4]),
                       "south": ("edge", [u0, 4, u1, 5]), "north": ("edge", [u0, 4, u1, 5])},
                skip=("east", "west"))
        parts.append(turn(e, -k * 360 / STAVES, "z", (C, C, 8)))
    return parts


def jewel(back: bool = False) -> list[dict]:
    """The bezel and diamond: turned square prisms for their walls, face-projected plates
    over them so their fronts stay pixel-crisp."""
    s = -1 if back else 1
    z0 = Z_FIELD_B if back else Z_FIELD_F
    parts = []
    for half_diag, za, zb, side, glow in ((4.5 * UNIT, 0.0, BEZEL_H, "gold", 0),
                                          (3.5 * UNIT, BEZEL_H, BEZEL_H + GEM_H, "gem_side", 11)):
        a = half_diag * math.sqrt(2)
        lo, hi = sorted((z0 + s * za, z0 + s * (zb - 0.05)))
        e = box((C - a / 2, C - a / 2, lo), (C + a / 2, C + a / 2, hi), side, uv="full", glow=glow,
                skip=("south",) if back else ("north",))
        parts.append(turn(e, 45, "z", (C, C, 8)))
    zb = z0 + s * BEZEL_H
    zg = z0 + s * (BEZEL_H + GEM_H)
    parts.append(plate(12, 12, 19, 19, zb, "back_bezel" if back else "bezel", back=back))
    parts.append(plate(13, 13, 18, 18, zg, "gem", back=back, glow=13))
    return parts


def build() -> list[dict]:
    parts = staves()
    lo, hi = 16 - SPAN // 2, 15 + SPAN // 2          # the coin's texels, 2..29
    parts.append(plate(lo, lo, hi, hi, Z_FIELD_F, "face"))
    parts.append(plate(lo, lo, hi, hi, Z_LIP_F + 0.04, "ring"))
    parts.append(plate(lo, lo, hi, hi, Z_FIELD_B, "back", back=True))
    parts.append(plate(lo, lo, hi, hi, Z_LIP_B - 0.04, "ring", back=True))
    parts += [relief(rect, depth) for rect, depth in RELIEF]
    parts += [relief(rect, 0.45, "back", back=True) for rect in rects_of(BADGE - BADGE_IN)]
    parts += jewel() + jewel(back=True)
    # Sparkles float just proud of the diamond (translucent, so drawn last).
    parts.append(plate(6, 4, 25, 23, Z_FIELD_F + BEZEL_H + GEM_H + 0.06, "twinkle", glow=15))
    return parts


def models() -> dict:
    parts = build()
    d = display(KIND, parts, gui_rotation=(10, 22, 0), gui_span=16.0)
    rim = (C, C - R + 0.9, 8.0)                     # pinched by its lower rim
    d["thirdperson_righthand"] = place({"y": (0.0, 1.0, 0.3), "z": (-0.45, 0.0, 1.0)}, rim, "fist", 0.5)
    d["firstperson_righthand"] = place({"y": (-0.08, 1.0, 0.0), "z": (0.3, 0.1, 1.0)}, (C, C, 8.0),
                                       (0.5, -0.31, -0.9), 0.58, pose=None)
    return {"main": model(parts, d)}
