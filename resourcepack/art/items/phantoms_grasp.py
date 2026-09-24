"""Phantom's Grasp: Halloween Limited Edition bow.

A hooded phantom skull crowns the grip; its two skeletal arms, bone laid on midnight-wood
staves, are the limbs, reaching out to clutch a glowing ectoplasm string in bony fists. A
ghostly teal glow runs along both arms and wisps stream from the elbows. When drawn, a
spectral teal arrow with a wispy flame tip is nocked, the fingers clench tighter and the
skull's jaw drops open.

Built upright (limbs along Y, arrow pointing -X), then tilted -45 degrees about Z into the
vanilla bow.png sprite frame so the vanilla bow transforms apply.
"""
from __future__ import annotations

import math
import random

from art.kit import (bar, bevel, box, canvas, display, display_matrix, fill, mirror, mix, model, ramp, rgba,
                     save, turn, FIST)

ID = "phantoms_grasp"
NAME = "Phantom's Grasp"
KIND = "bow"

# --------------------------------------------------------------------------------------
# Palettes, darkest to lightest. Shadows drift violet, highlights warm.
# --------------------------------------------------------------------------------------
BONE = ramp("#d0c3a6", 6, 0.8)
BONE[0] = mix(BONE[0], "#2a1a40", 0.55)
BONE[1] = mix(BONE[1], "#4b3a66", 0.35)
WOOD = ramp("#4a3070", 6, 0.7)
CLOTH = ramp("#33286a", 6, 0.85)
IRON = ramp("#4a4757", 6, 0.8)
PUMPKIN = ramp("#e8741a", 6, 0.8)
TEAL = ramp("#3cdcc4", 6, 0.8)
TOXIC = ramp("#86f23c", 6, 0.75)
SOCKET = "#0d0716"

# --------------------------------------------------------------------------------------
# Texture layout shared by the strip materials: 32 px textures, 2 texels per unit.
# Each long face samples the strip painted for its width, so every bar gets a clean bevel.
# --------------------------------------------------------------------------------------
STRIPS = ((0.0, 1.0), (1.0, 2.5), (2.5, 4.5), (4.5, 7.5), (7.5, 11.5))   # 2, 3, 4, 6, 8 px wide
LIMITS = (0.8, 1.4, 2.2, 3.2)
CAP_U = 11.5                                    # end caps: px 23-31, rows 0-8
KNOB_UV = [11.5, 5.0, 14.0, 7.5]                # rounded knob tile: px 23-27, rows 10-14


def _strip(width: float, length: float, v0: float) -> list[float]:
    i = sum(width >= t for t in LIMITS)
    u0, u1 = STRIPS[i]
    length = min(length, 16.0)
    v0 = min(max(v0, 0.0), 16.0 - length)
    return [u0, round(v0, 3), u1, round(v0 + length, 3)]


def _cap(w: float, d: float) -> list[float]:
    return [CAP_U, 0.0, round(CAP_U + min(w, 4.5), 3), round(min(d, 4.5), 3)]


def _strip_px(i: int) -> tuple[int, int]:
    u0, u1 = STRIPS[i]
    return int(round(u0 * 2)), int(round(u1 * 2)) - 1


def ubar(p0, p1, w, d, tex, seed=0, rim=False, **kw) -> dict:
    """bar() whose long faces sample a strip painted for their width (bevelled edges).
    rim=True mirrors the north face so the strip's first column lies on the same side of
    the bar from both sides (for lit-edge profiles)."""
    length = math.dist(p0, p1)
    v0 = (seed * 5.37) % max(0.01, 16.0 - min(length, 16.0))
    side = _strip(w, length, v0)
    back = [side[2], side[1], side[0], side[3]] if rim else side
    edge = _strip(d, length, v0 + 3.1)
    faces = {"north": (tex, back), "south": (tex, side), "east": (tex, edge), "west": (tex, edge),
             "up": (tex, _cap(w, d)), "down": (tex, _cap(w, d))}
    faces.update(kw.pop("faces", None) or {})
    return bar(p0, p1, w, d, tex, faces=faces, **kw)


def ubox(frm, to, tex, seed=0, faces=None, **kw) -> dict:
    dx, dy, dz = (abs(to[i] - frm[i]) for i in range(3))
    v0 = (seed * 5.37) % max(0.01, 16.0 - min(dy, 16.0))
    f = {"north": (tex, _strip(dx, dy, v0)), "south": (tex, _strip(dx, dy, v0)),
         "east": (tex, _strip(dz, dy, v0 + 2)), "west": (tex, _strip(dz, dy, v0 + 2)),
         "up": (tex, _cap(dx, dz)), "down": (tex, _cap(dx, dz))}
    f.update(faces or {})
    return box(frm, to, tex, faces=f, **kw)


def plane(p0, p1, w, tex, uv, glow=0, z=8.0) -> dict:
    """A two-sided card in the bow plane (wisps, membranes, flames). The back face is
    flipped so both sides show the same world-space shape."""
    u0, v0, u1, v1 = uv
    return bar((p0[0], p0[1], z), (p1[0], p1[1], z), w, 0.0, tex, glow=glow,
               faces={"south": (tex, [u0, v0, u1, v1]), "north": (tex, [u1, v0, u0, v1])},
               skip=("east", "west", "up", "down"))


def knob(c, size, depth, tex="bone", z=8.0, rot=0.0) -> list[dict]:
    """A rounded joint: a bevelled cube plus a smaller cube turned 45 degrees."""
    x, y = c
    faces = {s: (tex, KNOB_UV) for s in ("north", "south", "east", "west", "up", "down")}
    a = box((x - size / 2, y - size / 2, z - depth / 2), (x + size / 2, y + size / 2, z + depth / 2), tex,
            faces=faces)
    r = size * 0.41
    b = box((x - r, y - r, z - depth / 2 + 0.12), (x + r, y + r, z + depth / 2 - 0.12), tex, faces=faces)
    turn(b, 45 + rot, "z", (x, y, z))
    if rot:
        turn(a, rot, "z", (x, y, z))
    return [a, b]


# --------------------------------------------------------------------------------------
# Painting
# --------------------------------------------------------------------------------------

def _profiles(img, profiles) -> None:
    for i, prof in enumerate(profiles):
        x0, x1 = _strip_px(i)
        for k in range(x1 - x0 + 1):
            fill(img, (x0 + k, 0, x0 + k, 31), prof[k])


def _knob_tile(img, light, mid, dark, rim, glint) -> None:
    """5x5 rounded knob at px (23..27, 10..14), lit from the top left."""
    rows = ["rllmr", "lggmd", "lgmmd", "mmmdd", "rdddr"]
    pal = {"r": rim, "l": light, "g": glint, "m": mid, "d": dark}
    px = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            px[23 + x, 10 + y] = rgba(pal.get(ch, rim))


def _streaks(img, strips, colours, seed, min_len=3, max_len=7, gap=(3, 8), edge=True) -> None:
    """Grain / fold streaks running along v inside the chosen strips."""
    px = img.load()
    rng = random.Random(seed)
    for i in strips:
        x0, x1 = _strip_px(i)
        lo, hi = (x0 + 1, x1 - 1) if edge and x1 - x0 >= 2 else (x0, x1)
        for x in range(lo, hi + 1):
            y = rng.randint(0, 5)
            while y < 32:
                n = rng.randint(min_len, max_len)
                c = rgba(rng.choice(colours))
                for k in range(n):
                    if y + k < 32:
                        px[x, y + k] = c
                y += n + rng.randint(*gap)


def _grid(img, rows, pal, x0=0, y0=0) -> None:
    px = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == " ":
                continue
            px[x0 + x, y0 + y] = (0, 0, 0, 0) if ch == "." else rgba(pal[ch])


def paint_bone() -> None:
    B = BONE
    img = canvas(32)
    _profiles(img, [
        [B[4], B[3]],
        [B[4], B[5], B[3]],
        [B[3], B[5], B[4], B[2]],
        [B[2], B[4], B[5], B[4], B[3], B[2]],
        [B[2], B[3], B[4], B[5], B[5], B[4], B[3], B[2]],
    ])
    px = img.load()
    rng = random.Random(7)
    # sparse pits and hairline cracks, kept off the edge columns
    for i in (2, 3, 4):
        x0, x1 = _strip_px(i)
        y = rng.randint(1, 4)
        while y < 30:
            x = rng.randint(x0 + 1, x1 - 1)
            px[x, y] = rgba(B[2])
            if rng.random() < 0.5:
                px[min(x1 - 1, x + 1), y + 1] = rgba(B[1])
            y += rng.randint(5, 8)
    for i, col in ((1, 1), (2, 1), (3, 2), (4, 3)):
        x0, _ = _strip_px(i)
        for y in range(rng.randint(0, 5), 32, rng.randint(6, 9)):
            px[x0 + col, y] = rgba("#fffdf4")
    fill(img, (23, 0, 31, 8), B[3])            # bone end grain
    fill(img, (24, 1, 30, 7), B[4])
    fill(img, (26, 3, 28, 5), B[2])
    _knob_tile(img, B[5], B[4], B[2], B[1], "#fffdf4")
    # claw tips: dark points fading into bone (px 23-26, rows 16-19)
    for y, c in enumerate((B[0], B[1], B[2], B[3])):
        fill(img, (23, 16 + y, 26, 16 + y), c)
    save(img, "bone")


def paint_wood() -> None:
    W = WOOD
    img = canvas(32)
    _profiles(img, [
        [W[3], W[1]],
        [W[2], W[3], W[1]],
        [W[1], W[3], W[2], W[1]],
        [W[1], W[2], W[3], W[2], W[3], W[1]],
        [W[1], W[2], W[3], W[2], W[4], W[3], W[2], W[1]],
    ])
    _streaks(img, range(1, 5), [W[0], W[0], W[4]], seed=3)
    fill(img, (23, 0, 31, 8), W[2])
    for r, c in ((4, W[1]), (3, W[3]), (2, W[1]), (1, W[3])):
        fill(img, (27 - r, 4 - r, 27 + r, 4 + r), c)
    fill(img, (27, 4, 27, 4), W[0])
    _knob_tile(img, W[4], W[3], W[1], W[0], W[5])
    save(img, "wood")


def paint_cloth() -> None:
    """Midnight hood cloth: soft folds with a lighter ridge."""
    C = CLOTH
    img = canvas(32)
    _profiles(img, [
        [C[3], C[1]],
        [C[4], C[2], C[1]],
        [C[4], C[3], C[2], C[1]],
        [C[4], C[3], C[2], C[3], C[2], C[1]],
        [C[1], C[2], C[3], C[4], C[3], C[2], C[2], C[1]],
    ])
    _streaks(img, range(2, 5), [C[0], C[1]], seed=12, min_len=5, max_len=10, gap=(4, 9))
    fill(img, (23, 0, 31, 8), C[1])
    save(img, "cloth")


def paint_iron() -> None:
    I = IRON
    img = canvas(32)
    _profiles(img, [
        [I[3], I[1]],
        [I[4], I[2], I[1]],
        [I[4], I[3], I[2], I[1]],
        [I[4], I[3], I[2], I[2], I[1], I[0]],
        [I[4], I[3], I[3], I[2], I[2], I[2], I[1], I[0]],
    ])
    px = img.load()
    for cx in (11, 18):                    # rivets down the wider strips
        for y in range(2, 31, 6):
            px[cx, y] = rgba(I[5])
            px[cx + 1, y] = rgba(I[3])
            px[cx, y + 1] = rgba(I[2])
            px[cx + 1, y + 1] = rgba(I[0])
    for x0 in (0, 2, 5, 9, 15):            # a bright specular line on each lit edge
        for y in range(0, 32, 9):
            px[x0, y] = rgba(I[5])
    fill(img, (23, 0, 31, 8), I[2])
    bevel(img, (23, 0, 31, 8), I[4], I[0])
    _knob_tile(img, I[5], I[3], I[1], I[0], "#ffffff")
    save(img, "iron")


def paint_wrap() -> None:
    """Pumpkin-leather grip wound in bands, each with a lit edge and a shadowed seam."""
    P = PUMPKIN
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            k = (y + (x // 4)) % 4
            px[x, y] = rgba((P[4], P[3], P[2], P[1])[k])
    for x in range(0, 32, 8):              # ivory stitch pairs down the seam
        for y in range(1, 32, 4):
            px[(x + 5) % 32, (y + (x + 5) // 4) % 32] = rgba(BONE[4])
    save(img, "wrap")


def paint_glow() -> None:
    T = TEAL
    img = canvas(32)
    _profiles(img, [
        [T[5], T[3]],
        [T[3], T[5], T[3]],
        [T[3], T[5], T[4], T[2]],
        [T[2], T[4], T[5], T[5], T[4], T[2]],
        [T[2], T[3], T[4], T[5], T[5], T[4], T[3], T[2]],
    ])
    px = img.load()
    for i in range(5):
        x0, x1 = _strip_px(i)
        for y in range(1, 32, 5):
            px[(x0 + x1 + 1) // 2, y] = rgba("#f2fffb")
    fill(img, (23, 0, 31, 8), T[4])
    _knob_tile(img, T[5], T[4], T[2], T[1], "#f2fffb")
    save(img, "glow")


def paint_ecto() -> None:
    G, T = TOXIC, TEAL
    img = canvas(32)
    _profiles(img, [
        [G[5], T[4]],
        [G[4], G[5], T[3]],
        [G[3], G[5], G[4], T[3]],
        [G[2], G[4], G[5], G[5], G[3], T[3]],
        [G[2], G[3], G[4], G[5], G[5], G[4], G[3], T[3]],
    ])
    px = img.load()
    for i in range(5):
        x0, x1 = _strip_px(i)
        for y in range(2, 32, 7):
            px[(x0 + x1) // 2, y] = rgba("#fbffe8")
    fill(img, (23, 0, 31, 8), G[4])
    _knob_tile(img, "#fbffe8", G[4], G[2], T[3], "#ffffff")
    save(img, "ecto")


def paint_shaft() -> None:
    T = TEAL
    img = canvas(32)
    _profiles(img, [
        [T[4], T[2]],
        [T[3], T[5], T[2]],
        [T[2], T[4], T[3], T[1]],
        [T[1], T[3], T[4], T[4], T[2], T[1]],
        [T[1], T[2], T[3], T[4], T[4], T[3], T[2], T[1]],
    ])
    px = img.load()
    for y in range(0, 32, 4):               # spectral banding along the shaft
        for x in range(0, 23):
            px[x, y] = rgba(mix(px[x, y], "#ffffff", 0.4))
    fill(img, (23, 0, 31, 8), T[4])
    _knob_tile(img, "#e4fff8", T[4], T[2], T[1], "#ffffff")
    save(img, "shaft")


def paint_eye() -> None:
    img = canvas(16, fill=TOXIC[3])
    fill(img, (0, 0, 1, 1), TOXIC[4])
    fill(img, (0, 0, 0, 0), "#f4ffe0")
    fill(img, (1, 1, 1, 1), TOXIC[2])
    save(img, "eye")


def paint_void() -> None:
    """The dark inside of the hood, with a faint spectral mist."""
    img = canvas(32, fill="#0b0714")
    px = img.load()
    rng = random.Random(21)
    for _ in range(40):
        x, y = rng.randint(0, 31), rng.randint(0, 31)
        px[x, y] = rgba(rng.choice(("#16243a", "#123a3f", "#1a1030")))
    save(img, "void")


# The skull at 2 texels per unit: cranium 9x7 (4.5 x 3.5 units), jaw 7x3, brow 9x1.
CRANIUM = [
    "..deeed..",
    ".deeeeddc",
    "deeeeeddc",
    "dssdedssb",
    "dssdddssb",
    "eddbnbdcb",
    ".ctgtgtb.",
]
JAW = [
    "gtgtgtg",
    "cddddcb",
    ".bccbb.",
]
BROW = ["deeeeeddb"]


def paint_skull() -> None:
    B = BONE
    pal = {"b": B[2], "c": B[3], "d": B[4], "e": B[5], "s": SOCKET, "n": B[1], "t": "#fbf7ea", "g": B[1]}
    img = canvas(32, fill=B[3])
    _grid(img, CRANIUM, pal)
    _grid(img, JAW, pal, 0, 8)
    _grid(img, BROW, pal, 0, 12)
    save(img, "skull")


# Translucent effect cards, hand-drawn at 2 texels per unit.
_FX = {"x": (2, 0.32), "o": (3, 0.52), "w": (4, 0.72), "W": (5, 0.88)}

WISP = [          # 8x12: root at the bottom, curling tips at the top
    "......x.",
    ".....xo.",
    "....xow.",
    "...xowx.",
    "..xowx.x",
    ".xowo.xo",
    ".owWo.ow",
    "xowWwxow",
    "owWWwow.",
    "owWWWwo.",
    ".owWWo..",
    "..owo...",
]
FLAME = [         # 6x10: trailing tip at the top, white-hot core at the base
    "..x...",
    "..ox..",
    ".xwo.x",
    ".owxo.",
    "xowWo.",
    "owWYwx",
    "owYYWo",
    ".wYYwo",
    ".oWYw.",
    "..ow..",
]
FLETCH = [        # 3x6: head end at the top, shaft side at the left
    "x..",
    "ox.",
    "wo.",
    "wox",
    "Wwo",
    "Wwo",
]


def _fx(img, rows, x0, y0, core=None) -> None:
    px = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            if ch == "Y":
                r, g, b, _ = rgba(core or "#f4ffe0")
                px[x0 + x, y0 + y] = (r, g, b, 255)
                continue
            step, a = _FX[ch]
            r, g, b, _ = rgba(TEAL[step] if ch != "W" or core is None else TOXIC[4])
            px[x0 + x, y0 + y] = (r, g, b, int(255 * a))


def paint_fx() -> None:
    img = canvas(32)
    _fx(img, WISP, 0, 0)
    _fx(img, FLAME, 10, 0, core="#f4ffe0")
    _fx(img, FLETCH, 18, 0)
    px = img.load()
    # soft glow halo for the ectoplasm strand (px 22-24)
    r, g, b, _ = rgba(mix(TOXIC[5], TEAL[5], 0.45))
    for x, a in ((22, 0.22), (23, 0.42), (24, 0.22)):
        for y in range(32):
            px[x, y] = (r, g, b, int(255 * a))
    # ghost membrane: translucent teal with brighter veins (px 0-3, rows 16-31)
    for y in range(16, 32):
        for x in range(0, 4):
            c, a = rgba(TEAL[3]), 0.45
            if (x + y // 2) % 5 == 0:
                c, a = rgba(TEAL[5]), 0.8
            px[x, y] = (c[0], c[1], c[2], int(255 * a))
    # spectral light inside the hood (px 4-15, rows 16-27), fading toward the rim
    for y in range(12):
        for x in range(12):
            edge = min(x, y, 11 - x, 11 - y)
            c, a = (TEAL[4], 0.42) if edge >= 2 else (TEAL[3], 0.3) if edge == 1 else (TEAL[2], 0.18)
            r, g, b, _ = rgba(c)
            px[4 + x, 16 + y] = (r, g, b, int(255 * a))
    save(img, "fx")


WISP_UV = [0, 0, 4, 6]
FLAME_UV = [5, 0, 8, 5]
FLETCH_UV = [9, 0, 10.5, 3]
MEMBRANE_UV = [0, 8, 0.6, 11.6]
HALO_UV = [11, 0, 12.5, 16]
AURA_UV = [2, 8, 7.6, 14]


def textures() -> None:
    paint_bone()
    paint_wood()
    paint_cloth()
    paint_iron()
    paint_wrap()
    paint_glow()
    paint_ecto()
    paint_shaft()
    paint_eye()
    paint_void()
    paint_skull()
    paint_fx()


# --------------------------------------------------------------------------------------
# Geometry (build frame: bow upright, arrow toward -X, string on the +X side)
# --------------------------------------------------------------------------------------
Z = 8.0                  # bow plane
CY = 8.3                 # arrow height; the limbs mirror across this line
PIVOT = (8.0, 8.0, 8.0)  # the whole bow turns about this into the sprite frame
TILT = -45.0
GRIP = (7.7, 5.45, 8.0)  # centre of the hand on the grip
MED = (7.2, 11.45)       # hooded skull centre
SHOULDER = (6.9, 14.0)
ARROW_LEN = 18.2
VANILLA_BOW_GRIP = (5.3, 10.8, 8.0)   # the wrapped grip on vanilla bow.png


def _dir(a):
    r = math.radians(a)
    return (math.sin(r), math.cos(r))


def _back(a):
    r = math.radians(a)
    return (-math.cos(r), math.sin(r))


def _add(p, v, k=1.0):
    return (p[0] + v[0] * k, p[1] + v[1] * k)


def _p3(p, z=Z):
    return (p[0], p[1], z)


def to_sprite(p):
    a = math.radians(TILT)
    x, y = p[0] - PIVOT[0], p[1] - PIVOT[1]
    return (PIVOT[0] + x * math.cos(a) - y * math.sin(a), PIVOT[1] + x * math.sin(a) + y * math.cos(a), p[2])


def hooded_skull(jaw_drop: float) -> list[dict]:
    """Built upright around MED, then counter-tilted so it stands upright in the icon."""
    cx, cy = MED
    g = []

    def at(u, v):
        return (cx + u, cy + v)

    # the dark void inside the hood
    g.append(box((cx - 2.8, cy - 2.9, Z - 1.3), (cx + 2.8, cy + 3.1, Z + 1.3), "void",
                 uv={s: [0, 0, 5.6, 6] for s in ("north", "south", "east", "west", "up", "down")}))
    # a ghostly light glowing out of the void behind the skull, one card per side
    for side in (-1, 1):
        g.append(plane((cx, cy - 2.9), (cx, cy + 3.1), 5.6, "fx", AURA_UV, glow=12, z=Z + side * 1.34))
    # the cowl: one continuous band around the face, deeper than the skull so the face
    # sits recessed, flaring out at the hem
    path = ((-3.0, -3.2), (-3.1, 1.1), (-2.65, 2.85), (-1.55, 4.15), (0.35, 3.8), (1.95, 3.05),
            (2.95, 1.7), (3.05, -3.2))
    widths = (1.2, 1.3, 1.45, 1.5, 1.35, 1.3, 1.2)
    for k in range(len(path) - 1):
        a, b = path[k], path[k + 1]
        d = (b[0] - a[0], b[1] - a[1])
        n = math.hypot(*d)
        d = (d[0] / n, d[1] / n)
        g.append(ubar(_p3(at(*_add(a, d, -0.2))), _p3(at(*_add(b, d, 0.25))), widths[k], 4.1, "cloth",
                      seed=k + 2, rim=True))
    for s in (-1, 1):   # hem flares
        g.append(ubar(_p3(at(3.05 * s, -2.3)), _p3(at(3.75 * s, -3.95)), 1.0, 3.6, "cloth", seed=8 + s))
    # the cowl's point droops back like limp cloth
    g.append(ubar(_p3(at(-1.3, 4.0)), _p3(at(-2.75, 4.75)), 1.3, 3.0, "cloth", seed=10))
    # the skull: cranium, brow ridge and a jaw that drops open as the bow is drawn
    top = cy + 2.45
    face = ("skull", [0, 0, 4.5, 3.5])
    g.append(ubox((cx - 2.25, top - 3.5, Z - 1.8), (cx + 2.25, top, Z + 1.8), "bone", seed=2,
                  faces={"north": face, "south": face}))
    brow = ("skull", [0, 6, 4.5, 6.5])
    g.append(ubox((cx - 2.25, top - 1.5, Z - 1.95), (cx + 2.25, top - 1.05, Z + 1.95), "bone", seed=4,
                  faces={"north": brow, "south": brow}))
    jaw_face = ("skull", [0, 4, 3.5, 5.5])
    jt = top - 3.5 - jaw_drop
    g.append(ubox((cx - 1.75, jt - 1.5, Z - 1.6), (cx + 1.75, jt, Z + 1.6), "bone", seed=6,
                  faces={"north": jaw_face, "south": jaw_face}))
    # glowing eyes, through the skull so both faces show them
    for ex in (-1.25, 1.25):
        g.append(box((cx + ex - 0.5, top - 2.5, Z - 1.85), (cx + ex + 0.5, top - 1.5, Z + 1.85), "eye",
                     uv={s: [0, 0, 2, 2] for s in ("north", "south", "east", "west", "up", "down")}, glow=15))
    turn(g, -TILT, "z", (cx, cy, Z))
    return g


def riser(jaw_drop: float) -> list[dict]:
    p = []
    # midnight-wood spine along the back of the riser
    p.append(ubar((6.4, 2.2, Z), (6.4, 13.6, Z), 1.4, 2.0, "wood", seed=3))
    # grip wrapped in pumpkin leather, iron caps above and below (the upper one is the shelf)
    p.append(ubox((6.4, 3.6, Z - 1.25), (9.0, 7.3, Z + 1.25), "wrap"))
    p.append(ubox((5.9, 3.0, Z - 1.5), (9.4, 3.6, Z + 1.5), "iron", seed=1))
    p.append(ubox((5.9, 7.3, Z - 1.5), (9.4, 7.95, Z + 1.5), "iron", seed=2))
    # the phantom's spine: vertebrae knuckled down the back of the grip
    for k, y in enumerate((3.95, 4.95, 5.95, 6.9)):
        p.append(ubox((5.15, y - 0.36, Z - 0.75), (6.0, y + 0.36, Z + 0.75), "bone", seed=20 + k,
                      faces={s_: ("bone", KNOB_UV) for s_ in ("north", "south", "west")}))
    p += hooded_skull(jaw_drop)
    return p


def arm(flex: float, curl: float) -> tuple[list[dict], tuple]:
    """The upper limb; returns its parts and where the string enters the fist."""
    p = []
    S = SHOULDER
    a1 = 24 + 3 * flex
    E = _add(S, _dir(a1), 3.8)
    a2 = 56 + 6 * flex
    W = _add(E, _dir(a2), 4.4)
    a3 = 98 + 8 * flex
    K = _add(W, _dir(a3), 2.0)

    # shoulder
    p += knob(S, 2.1, 2.1, rot=a1)
    # humerus: bone on a midnight-wood stave, a glow seam between them
    n1 = _back(a1)
    p.append(ubar(_p3(_add(S, _dir(a1), 0.8)), _p3(_add(E, _dir(a1), -0.7)), 1.3, 1.5, "bone", seed=1))
    p.append(ubar(_p3(_add(S, n1, 1.35)), _p3(_add(_add(E, n1, 1.35), _dir(a1), 0.3)), 1.15, 1.4, "wood", seed=2))
    p.append(ubar(_p3(_add(S, n1, 0.7)), _p3(_add(E, n1, 0.7)), 0.3, 1.7, "glow", seed=3, glow=13))
    # elbow: knob, iron guard and a back-swept spike
    p += knob(E, 2.0, 1.9, rot=a1)
    ring_c = _add(E, _dir(a1), -1.2)
    p.append(ubar(_p3(_add(ring_c, _dir(a1), -0.3)), _p3(_add(ring_c, _dir(a1), 0.3)), 2.3, 2.0, "iron", seed=4))
    ext = (_dir(a1)[0] - _dir(a2)[0], _dir(a1)[1] - _dir(a2)[1])
    el = math.hypot(*ext)
    ext = (ext[0] / el, ext[1] / el)
    p.append(ubar(_p3(_add(E, ext, 0.6)), _p3(_add(E, ext, 1.8)), 0.85, 0.85, "iron", seed=5))
    p.append(ubar(_p3(_add(E, ext, 1.6)), _p3(_add(E, ext, 2.6)), 0.42, 0.42, "iron", seed=6))
    # forearm: ulna and radius with a ghostly membrane between, stave and glow behind
    n2 = _back(a2)
    p.append(ubar(_p3(_add(_add(E, n2, 0.6), _dir(a2), 0.6)), _p3(_add(W, n2, 0.45)), 0.7, 1.2, "bone", seed=7))
    p.append(ubar(_p3(_add(_add(E, n2, -0.55), _dir(a2), 0.8)), _p3(_add(W, n2, -0.42)), 0.7, 1.2, "bone", seed=8))
    p.append(plane(_add(_add(E, n2, 0.03), _dir(a2), 0.9), _add(W, n2, 0.02), 0.6, "fx", MEMBRANE_UV, glow=10))
    p.append(ubar(_p3(_add(E, n2, 1.6)), _p3(_add(W, n2, 1.3)), 1.1, 1.3, "wood", seed=9))
    p.append(ubar(_p3(_add(E, n2, 1.03)), _p3(_add(W, n2, 0.84)), 0.28, 1.6, "glow", seed=10, glow=13))
    # wrist shackle
    p.append(ubar(_p3(_add(W, _dir(a2), -0.7)), _p3(_add(W, _dir(a2), 0.45)), 2.4, 2.6, "iron", seed=11))
    # palm and four clawed fingers stacked across the depth, thumb on the palm side
    p.append(ubar(_p3(W), _p3(_add(K, _dir(a3), 0.2)), 1.1, 2.6, "bone", seed=12))
    fingers = ((Z + 0.95, 0.9, -0.2), (Z + 0.32, 1.05, 0.05), (Z - 0.32, 1.0, 0.0), (Z - 0.95, 0.85, -0.3))
    claw = ("bone", [11.5, 8.0, 13.5, 10.0])
    for zi, scale, off in fingers:
        k = _add(K, _dir(a3), off)
        ang = a3
        for j, seg in enumerate((1.3, 1.05, 0.95)):
            ang += curl
            nxt = _add(k, _dir(ang), seg * scale)
            extra = {"faces": {"north": claw, "south": claw, "east": claw, "west": claw}} if j == 2 else {}
            p.append(ubar(_p3(_add(k, _dir(ang), -0.12), zi), _p3(nxt, zi), 0.55 - 0.05 * j, 0.55, "bone",
                          seed=13 + j, **extra))
            k = nxt
    tb = _add(_add(W, _dir(a3), 0.7), _back(a3), -0.6)
    t1 = _add(tb, _dir(a3 + 30), 1.1)
    t2 = _add(t1, _dir(a3 - 25), 0.9)
    p.append(ubar(_p3(tb), _p3(t1), 0.6, 0.6, "bone", seed=16))
    p.append(ubar(_p3(t1), _p3(t2), 0.5, 0.55, "bone", seed=17, faces={"north": claw, "south": claw}))
    # ghostly wisp streaming from the elbow
    p.append(plane(_add(E, ext, 0.3), _add(_add(E, ext, 4.9), _dir(a1), 0.5), 3.2, "fx", WISP_UV, glow=10))
    T = _add(_add(K, _dir(a3), 0.5), _back(a3), -1.05)
    return p, T


def string(tu, tl, draw: float) -> tuple[list[dict], list]:
    """The ectoplasm strand (straight, or drawn into a V at the nock) with a soft halo."""
    segments = [(tl, tu)] if draw <= 0 else [((tu[0] + draw, CY), tu), (tl, (tu[0] + draw, CY))]
    out = []
    for k, (a, b) in enumerate(segments):
        out.append(ubar(_p3(a), _p3(b), 0.55, 0.55, "ecto", seed=k + 1, glow=15))
        out.append(plane(a, b, 1.5, "fx", HALO_UV, glow=15))
    return out, segments


def drips(segments) -> list[dict]:
    """Ectoplasm drips hanging straight down in the sprite frame (after the tilt)."""
    out = []
    spots = ((0, 0.42, 1.0), (1, 0.55, 0.8)) if len(segments) == 2 else ((0, 0.3, 1.0), (0, 0.7, 0.75))
    for seg, f, size in spots:
        a, b = (to_sprite(_p3(q)) for q in segments[seg])
        x, y = a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f
        out.append(ubox((x - 0.14, y - 0.9 * size, Z - 0.14), (x + 0.14, y, Z + 0.14), "ecto", glow=15))
        r = 0.3 * size
        out.append(box((x - r, y - 0.9 * size - 2 * r, Z - r), (x + r, y - 0.9 * size + 0.1, Z + r), "ecto",
                       uv={s: KNOB_UV for s in ("north", "south", "east", "west", "up", "down")}, glow=15))
    return out


def arrow(nock_x: float) -> list[dict]:
    p = []
    tip = nock_x - ARROW_LEN
    p.append(ubar((nock_x - 0.1, CY, Z), (tip + 1.2, CY, Z), 0.45, 0.45, "shaft", seed=3, glow=10))
    # spectral head: a flat diamond seen from the sides plus one across it
    kn = {s: ("shaft", KNOB_UV) for s in ("north", "south", "east", "west", "up", "down")}
    c = (tip + 1.2, CY, Z)
    head = box((c[0] - 0.85, CY - 0.85, Z - 0.16), (c[0] + 0.85, CY + 0.85, Z + 0.16), "shaft", faces=kn, glow=15)
    p.append(turn(head, 45, "z", c))
    head2 = box((c[0] - 0.85, CY - 0.16, Z - 0.85), (c[0] + 0.85, CY + 0.16, Z + 0.85), "shaft", faces=kn, glow=15)
    p.append(turn(head2, 45, "y", c))
    # wispy flame engulfing the tip and streaming back, crossed so it has volume
    p.append(plane((tip - 0.5, CY - 0.1), (tip + 4.3, CY + 0.45), 2.8, "fx", FLAME_UV, glow=15))
    flame2 = plane((tip - 0.3, CY), (tip + 3.9, CY + 0.25), 2.4, "fx", FLAME_UV, glow=15)
    p.append(turn(flame2, 90, "x", (tip, CY, Z)))
    # ghost fletching
    p.append(plane((nock_x - 0.4, CY + 0.95), (nock_x - 3.4, CY + 0.95), 1.5, "fx", FLETCH_UV, glow=8))
    lo = FLETCH_UV
    p.append(plane((nock_x - 0.4, CY - 0.95), (nock_x - 3.4, CY - 0.95), 1.5, "fx", [lo[2], lo[1], lo[0], lo[3]],
                   glow=8))
    # nock
    p.append(ubox((nock_x - 0.5, CY - 0.4, Z - 0.4), (nock_x + 0.2, CY + 0.4, Z + 0.4), "bone", seed=2))
    return p


def bow(draw: int) -> tuple[list[dict], tuple]:
    """draw 0 = idle, 1-3 = pull_0..pull_2."""
    flex = (0.0, 0.35, 0.7, 1.0)[draw]
    curl = (44.0, 50.0, 57.0, 64.0)[draw]
    pull = (0.0, 2.2, 3.9, 5.6)[draw]
    jaw = (0.0, 0.25, 0.5, 0.75)[draw]
    upper, tu = arm(flex, curl)
    lower = mirror(upper, "y", CY)
    tl = (tu[0], 2 * CY - tu[1])
    strand, segments = string(tu, tl, pull)
    parts = riser(jaw) + upper + lower + strand
    if draw:
        parts += arrow(tu[0] + pull)
    turn(parts, TILT, "z", PIVOT)
    parts += drips(segments)
    return parts, to_sprite(GRIP)


def _pin(rotation, scale, grip, goal) -> list[float]:
    """Translation that puts model point `grip` at `goal` (item space, blocks)."""
    m = display_matrix(rotation)
    g = [(grip[i] / 16 - 0.5) * scale for i in range(3)]
    q = [sum(m[i][k] * g[k] for k in range(3)) for i in range(3)]
    return [round((goal[i] - q[i]) * 16, 3) for i in range(3)]


def _anchor(rotation, translation, scale, point) -> list[float]:
    """Where a model point lands (item space, blocks) under a display transform."""
    m = display_matrix(rotation)
    g = [(point[i] / 16 - 0.5) * scale for i in range(3)]
    return [translation[i] / 16 + sum(m[i][k] * g[k] for k in range(3)) for i in range(3)]


FP_SCALE = 0.5
FP_SHIFT = (0.4, -2.2, -1.5)   # units, relative to where vanilla holds its grip


def views(grip, ref) -> dict:
    d = display("bow", ref, gui_span=16.0)
    tp = d["thirdperson_righthand"]
    tp["translation"] = _pin(tp["rotation"], tp["scale"][0], grip, FIST)
    fp = d["firstperson_righthand"]
    goal = _anchor(fp["rotation"], fp["translation"], fp["scale"][0], VANILLA_BOW_GRIP)
    goal = [goal[i] + FP_SHIFT[i] / 16 for i in range(3)]
    fp["scale"] = [FP_SCALE] * 3
    fp["translation"] = _pin(fp["rotation"], FP_SCALE, grip, goal)
    return d


def models() -> dict:
    idle, grip = bow(0)
    out = {"idle": model(idle, views(grip, idle))}
    for i, name in enumerate(("pull_0", "pull_1", "pull_2")):
        parts, grip = bow(i + 1)
        out[name] = model(parts, views(grip, idle))
    return out
