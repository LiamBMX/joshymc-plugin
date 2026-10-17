"""Candy Corn (issue #1206): one plump kernel of Halloween candy corn, a retextured Cookie
that eats like one.

The kernel stands upright: a wide, rounded yellow base, an orange middle band and a
creamy white tip. It is built from thin horizontal slices (SLICE units tall) whose width
and thickness follow PROFILE, so the sides taper smoothly from the broad base to a
rounded point. Each slice's cross-section is a rounded lozenge of three boxes (LOZENGE:
wide and thin, middle, narrow and deep).

Every side face samples one shared body texture with v measured down from the tip
(1 uv unit = 1 model unit of height), so the colour bands, their softened seams and the
waxy highlight (which leans in with the taper) line up across all the slices. The faces
are unshaded and each lozenge box uses its own tone of that texture (TONES), darkest on
the flanks, so the roundness is painted and the colours stay as bright as candy instead
of the game's shading turning the yellow mustard and the white grey. Slice tops use the
same tone as their box, so the tiny steps never draw lines. The body is animated: a soft
waxy glint sweeps across the candy now and then.
"""
from __future__ import annotations

from art.kit import animate, box, canvas, display, fill, mix, model, place, rgba, save, save_animation, shine

ID = "candy_corn"
NAME = "Candy Corn"
KIND = "food"
COUNTERPART = "item/cookie"

# --------------------------------------------------------------------------------------
# Palettes (darkest -> lightest), hand-picked and hue-shifted: shadows drift warm/red,
# highlights drift toward cream.
# --------------------------------------------------------------------------------------
WHITE = ["#b3a088", "#d8cab0", "#eee5d0", "#fbf6ea", "#fffdf7", "#ffffff"]
ORANGE = ["#b53c04", "#de5807", "#fa750e", "#ff9224", "#ffad4e", "#ffc985"]
YELLOW = ["#c27a08", "#eaa50e", "#ffc81a", "#ffda38", "#ffe96e", "#fff6b4"]
BANDS = (WHITE, ORANGE, YELLOW)

# --------------------------------------------------------------------------------------
# Layout (model units)
# --------------------------------------------------------------------------------------
CX, CZ = 8.0, 8.0
Y0, Y1 = 1.0, 15.0                    # base and tip
H = Y1 - Y0
A, B = 5.6, 3.1                       # half-width and half-thickness at the widest point
SLICE = 0.25                          # thin slices keep the steps up the sides tiny
# Height fraction -> (width, thickness) as fractions of A and B: rounded foot, broad
# shoulder low down, gently bowed sides, rounded point.
PROFILE = [
    (0.00, 0.72, 0.70), (0.03, 0.88, 0.86), (0.07, 0.96, 0.95), (0.13, 1.00, 1.00),
    (0.25, 0.97, 0.99), (0.40, 0.87, 0.93), (0.55, 0.73, 0.84), (0.70, 0.57, 0.72),
    (0.82, 0.42, 0.60), (0.90, 0.30, 0.48), (0.95, 0.20, 0.36), (0.985, 0.10, 0.22),
    (1.00, 0.04, 0.10),
]
# The colour bands, as height fractions from the base: yellow below ORANGE_AT, orange up
# to WHITE_AT, white tip above it.
ORANGE_AT, WHITE_AT = 0.40, 0.72
# Cross-section boxes: (width share, thickness share, front/back tone, side tone), a
# rounded lozenge: the widest box is the dark flank, the deepest one the lit front.
LOZENGE = ((1.00, 0.46, "body_edge", "flank"), (0.84, 0.82, "body_mid", "side_edge"),
           (0.48, 1.00, "body", "side_mid"))
# Texture name -> (brightness, carries the front highlight). Faces are unshaded, so these
# do the game's shading's job; side faces get a plain body without the front's highlight.
TONES = {"body": (1.0, True), "body_mid": (0.95, True), "body_edge": (0.88, True),
         "side_mid": (0.95, False), "side_edge": (0.88, False), "flank": (0.78, False)}


def profile(t: float) -> tuple[float, float]:
    for (t0, a0, b0), (t1, a1, b1) in zip(PROFILE, PROFILE[1:]):
        if t0 <= t <= t1:
            k = (t - t0) / (t1 - t0)
            return A * (a0 + (a1 - a0) * k), B * (b0 + (b1 - b0) * k)
    return A * PROFILE[-1][1], B * PROFILE[-1][2]


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------
PX = 4                                # body texels per uv unit (64 px over 16 units)


def band_at(row: int) -> tuple[int, int, float]:
    """The palettes a body row blends: (upper band, lower band, share of the lower one).
    Bands are 0 white, 1 orange, 2 yellow; the seams soften over a few texels."""
    h = 1 - (row + 0.5) / PX / H            # height fraction of this row
    for i, s in enumerate((WHITE_AT, ORANGE_AT)):
        d = (h - s) * H * PX                 # texels above the seam
        if -1.5 < d < 1.5:
            return i, i + 1, (1.5 - d) / 3
    band = 0 if h >= WHITE_AT else 1 if h >= ORANGE_AT else 2
    return band, band, 0.0


def body_px(col: int, row: int, front: bool) -> str:
    upper, lower, share = band_at(row)
    u = (col + 0.5) / PX - 8                 # model x offset from the centre line
    h = 1 - (row + 0.5) / PX / H
    a = profile(min(1.0, max(0.0, h)))[0]
    k = 3
    if front:
        # A soft waxy highlight left of centre (light from the upper left) that leans in with
        # the taper up to the tip, and the right of the face turning away from the light.
        d = abs(u + 0.36 * a)
        if d < 0.45 and 0.08 < h < 0.9:
            k += 2
        elif d < 1.0:
            k += 1
        elif u > 0.35 * a:
            k -= 1
    if h < 0.05:                             # the foot rolls under
        k -= 1
    k = max(0, min(5, k))
    return mix(BANDS[upper][k], BANDS[lower][k], share)


def body(front: bool = True):
    img = canvas(64)
    px = img.load()
    for row in range(64):
        for col in range(64):
            px[col, row] = rgba(body_px(col, row, front))
    return img


def toned(img, f: float):
    """A copy of img darkened by f, drifting warm in the shadows like the hand-picked ramps."""
    out = img.copy()
    px = out.load()
    w, h = out.size
    for y in range(h):
        # The white tip's steps are fine and show a lot of flank, so its shadows stay gentle.
        k = 1 - (1 - f) * (0.55 if band_at(y % 64)[0] == 0 else 1.0)
        for x in range(w):
            r, g, b, a = px[x, y]
            px[x, y] = (min(255, round(r * (k + 0.04))), round(g * k), round(b * (k - 0.06)), a)
    return out


def glint(img, t: float):
    return shine(img, t, "#fffdf4", width=12, strength=0.3, angle=60.0, pause=0.5)


def textures() -> None:
    plain = {True: body(True), False: body(False)}
    for name, (f, front) in TONES.items():
        frames = animate(lambda t: glint(plain[front], t), 24)
        save_animation(frames if f >= 1 else [toned(fr, f) for fr in frames], name, frametime=2)
    # Each box's narrow slice tops match its front tone, so the steps never draw lines.
    for _, _, front, _ in LOZENGE:
        save(toned(plain[False], TONES[front][0]), "ledge_" + front)
    base = canvas(32, fill=YELLOW[1])
    fill(base, (4, 4, 27, 27), mix(YELLOW[1], YELLOW[2], 0.5))
    save(base, "base")


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def slab(y0: float, y1: float, hx: float, hz: float, front: str, side: str, top: bool,
         bottom: bool) -> dict:
    """One box of a slice. Side faces sample the body texture (in this box's tone) at this
    height; the top, when the slice above leaves it showing, samples the body row just
    under it. Only the lowest slice has a bottom."""
    v0, v1 = Y1 - y1, Y1 - y0
    vt = min(H - 0.25, v0 + 0.1)
    faces = {
        "north": (front, [8 - hx, v0, 8 + hx, v1]),
        "south": (front, [8 - hx, v0, 8 + hx, v1]),
        "east": (side, [8 - hz, v0, 8 + hz, v1]),
        "west": (side, [8 - hz, v0, 8 + hz, v1]),
        "up": ("ledge_" + front, [8 - hx, vt, 8 + hx, vt + 0.25]),
        "down": ("base", [8 - hx, 8 - hz, 8 + hx, 8 + hz]),
    }
    skip = tuple(name for name, keep in (("up", top), ("down", bottom)) if not keep)
    return box((CX - hx, y0, CZ - hz), (CX + hx, y1, CZ + hz), front, faces=faces, shade=False, skip=skip)


def kernel() -> list[dict]:
    parts = []
    n = round(H / SLICE)
    sizes = [profile((i + 0.5) / n) for i in range(n)]
    for i, (a, b) in enumerate(sizes):
        y0 = Y0 + i * SLICE
        above = sizes[i + 1] if i + 1 < n else (0.0, 0.0)
        # A top only shows where the slice above is narrower or thinner.
        top = above[0] < a - 1e-6 or above[1] < b - 1e-6
        for wx, wz, front, side in LOZENGE:
            parts.append(slab(y0, y0 + SLICE, a * wx, b * wz, front, side, top, i == 0))
    return parts


def build() -> list[dict]:
    return kernel()


def models() -> dict:
    parts = build()
    d = display("food", parts, gui_rotation=(10, -15, -15), gui_span=15.0)
    fp = d["firstperson_righthand"]
    fp["translation"][1] = round(fp["translation"][1] + 3.0, 3)
    fp["scale"] = [round(s * 0.8, 4) for s in fp["scale"]]
    # Pinched at the foot so the whole kernel stands up out of the fist, front facing out.
    d["thirdperson_righthand"] = place(dict(y=(0, 1, 0.15), z=(0.25, 0, 1)), (CX, Y0 + 2.5, CZ), "fist", 0.55)
    return {"main": model(parts, d)}
