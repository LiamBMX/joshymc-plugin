"""Shark Helmet: a blocky shark head worn over the player's head, its jaws wide open around
the face.

Frame (helmet): the wearer's head is the cube [1.6, 14.4]^3, face at -Z, up = +Y, +X = the
wearer's right. The shark head is a 17.6-unit cube around it (x, z in [-0.8, 16.8], y in
[0, 17.6]); its inner surfaces stay at 0.6 / 15.4 so they clear the skin's hat layer, and
nothing reaches into the face: the upper teeth stop 0.8 above the eyes.

The big faces are painted 16 texels across (1.1 units a texel, about a vanilla armor texel)
and every shell piece projects its UVs onto those face textures, so the painting runs on
unbroken across the cut pieces. The hide is mottled in soft 2x2 patches to keep the chunky,
8-pixels-a-face look of the concept art.

Front: a navy snout, a white upper lip standing a little proud with a raised middle step,
a jagged row of stepped teeth hanging from it (corner fangs, a pair over the eyes, a small
middle tooth, and a smaller second row set back in the gaps), a real mouth opening the face
shows through (dark inner walls), and a lower jaw whose white corner blocks jut forward with
a corner tooth each, a recessed dark middle under the chin carrying two more teeth.
Sides: the mouth's gape is cut into each front corner as a recess with teeth, behind it a
black eye, then a cyan-lit (self-lit) gill field with three black slits under raised lips,
each with a thin glowing line that pulses slowly. Top: a small stepped dorsal fin, steel
blue along its leading edge. Back: plain navy hide.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, box, canvas, display, mix, model, rgba, save, save_animation, wave)

ID = "shark_helmet"
NAME = "Shark Helmet"
KIND = "helmet"
COUNTERPART = "item/netherite_helmet"

# --------------------------------------------------------------------------------------
# Palette (dark -> light). Sampled from the concept sheet and its five swatches
# (#363a49, #375771, #6a96ab, #b6c5d5, #d0d0d4), lifted a little so the dark navy still
# reads under Minecraft's side shading.
# --------------------------------------------------------------------------------------
NAVY = ["#1b2029", "#242a35", "#2d3441", "#363a49", "#3f4656", "#474e60", "#525a6d", "#5f697d", "#737e93"]
WHITE = ["#7f848e", "#9a9ea8", "#b3b6be", "#c6c8ce", "#d0d0d4", "#e0e1e5", "#eef0f3", "#fafbfc"]
BLUE = ["#142838", "#1c374c", "#254a63", "#375771", "#3f7090", "#4f88a8", "#6a96ab", "#8cb5c9"]
CYAN = ["#2aa6c8", "#4cc6e2", "#86e0f2", "#c4f4fb", "#effdff"]
ICE = ["#94afc4", "#b6c5d5", "#d6e2ec"]
MOUTH = ["#07080a", "#0d0f12", "#13161a", "#1a1e24", "#22272f"]

# --------------------------------------------------------------------------------------
# Frame
# --------------------------------------------------------------------------------------
T = 1.1                      # model units per texel on the painted faces
X0, X1 = -0.8, 16.8          # the shark head cube
Y0, Y1 = 0.0, 17.6
Z0, Z1 = -0.8, 16.8
IN0, IN1 = 0.6, 15.4         # inner surfaces (the hat layer is at 0.8 / 15.2)
LIP = -1.15                  # front of the upper lip and its teeth
WRAP = 0.35                  # how far the lip and jaw wrap out past the side plates
JAW = -1.6                   # front of the lower jaw's corner blocks
MOUTH_X = (1.4, 14.6)        # the opening the face shows through
MOUTH_Y = (2.2, 11.0)
# Gill slits on the side texture: (texel row, (first u, last u)), u = 0 at the front.
GILL_SLITS = ((5, (9, 13)), (8, (9, 13)), (11, (10, 13)))
# The cyan-lit gill field on the side texture, as (u0, v0, u1, v1) texel rectangles: one
# self-lit panel each, so the field glows evenly instead of taking the side's shading.
GILL_FIELD = ((7, 3, 14, 13), (6, 8, 6, 13), (5, 10, 5, 13), (8, 2, 13, 2), (8, 14, 13, 14))
EYE_U, EYE_ROW = 4, 4        # top-left texel of the 2x2 eye on the side texture


def row(v: float) -> float:
    """World y of the top of texel row v of a side face."""
    return Y1 - T * v


def side_z(u: float) -> float:
    """World z of the front edge of side texel column u (u = 0 at the front)."""
    return Z0 + T * u


# --------------------------------------------------------------------------------------
# Painting helpers
# --------------------------------------------------------------------------------------

def put(img, x, y, colour):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((int(x), int(y)), rgba(colour))


def rect(img, x0, y0, x1, y1, colour):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            put(img, x, y, colour)


def mottle(img, box_, tones, seed, weights=None, block=2, specks=0.06, speck_tones=None):
    """Chunky mottling: each block x block cell picks one of `tones`, then a few single
    texels flick lighter or darker."""
    rng = random.Random(seed)
    x0, y0, x1, y1 = box_
    weights = weights or [1] * len(tones)
    for by in range(y0, y1 + 1, block):
        for bx in range(x0, x1 + 1, block):
            c = rng.choices(tones, weights)[0]
            rect(img, bx, by, min(x1, bx + block - 1), min(y1, by + block - 1), c)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if rng.random() < specks:
                put(img, x, y, rng.choice(speck_tones or tones))


def cloud(img, box_, tones, seed, block=2, jitter=0.35):
    """Soft, patchy mottling: a smooth noise field sampled per block x block cell and
    quantised to `tones` (dark -> light), so lighter and darker patches cluster the way the
    concept's hide does instead of scattering as noise."""
    rng = random.Random(seed)
    waves = [(rng.uniform(0.35, 0.9), rng.uniform(0.35, 0.9), rng.uniform(0, 6.3)) for _ in range(3)]
    x0, y0, x1, y1 = box_
    for by in range(y0, y1 + 1, block):
        for bx in range(x0, x1 + 1, block):
            n = sum(math.sin(bx * fx + by * fy + p) for fx, fy, p in waves) / 3
            k = (n + 1) / 2 * (len(tones) - 1) + rng.uniform(-jitter, jitter)
            c = tones[max(0, min(len(tones) - 1, round(k)))]
            rect(img, bx, by, min(x1, bx + block - 1), min(y1, by + block - 1), c)


def navy_skin(img, box_, seed, lift=0):
    """The shark's dark navy-slate hide: cloudy patches plus a few lone lighter texels."""
    n = lambda i: NAVY[max(0, min(len(NAVY) - 1, i + lift))]
    cloud(img, box_, [n(3), n(4), n(5), n(5), n(6)], seed)
    rng = random.Random(seed + 99)
    x0, y0, x1, y1 = box_
    for _ in range(max(1, (x1 - x0 + 1) * (y1 - y0 + 1) // 40)):
        put(img, rng.randint(x0, x1), rng.randint(y0, y1), n(rng.choice((3, 6, 7))))


def white_skin(img, box_, seed, base=5):
    mottle(img, box_, [WHITE[base], WHITE[base - 1], WHITE[base + 1]], seed, weights=[6, 2, 2],
           specks=0.05, speck_tones=[WHITE[base - 2], WHITE[base + 2] if base + 2 < len(WHITE) else WHITE[-1]])


# --------------------------------------------------------------------------------------
# Textures (16 px; one texel = 1.1 units on the shell)
# --------------------------------------------------------------------------------------

def paint_front():
    """The front as seen from ahead (u = 0 on the viewer's left = the wearer's right).
    Rows 0-2 navy snout with the lip's raised middle step (row 2, u 6-9); rows 3-5 the white
    upper lip; the corner columns (u 0-1, 14-15) navy below it."""
    img = canvas(16)
    navy_skin(img, (0, 0, 15, 15), seed=11)
    for y, (a, b) in ((2, (6, 9)), (3, (1, 14)), (4, (0, 15)), (5, (0, 15))):
        for x in range(a, b + 1):
            put(img, x, y, WHITE[7] if y < 4 else WHITE[6])
    # A soft grey lower edge where the teeth hang from the lip.
    for x in range(0, 16):
        put(img, x, 5, WHITE[5] if x % 3 else WHITE[4])
    for x in (0, 15):
        put(img, x, 3, NAVY[6])
    # Lighter flecks on the snout, like the concept's lit pixels.
    for x, y in ((3, 0), (4, 0), (11, 1), (12, 1), (7, 0)):
        put(img, x, y, NAVY[7])
    return img


def paint_side():
    """The wearer's left side as seen from outside (u = 0 at the front); the right side uses
    it mirrored. The jaw line wraps in from the front (the gape below it is a 3D recess with
    its own dark walls), then the eye, the pale cheek, and the cyan gill field with its
    three slits."""
    img = canvas(16)
    navy_skin(img, (0, 0, 15, 15), seed=23, lift=1)
    # Cyan-lit gill field (drawn self-lit, so these are the colours seen): steel blue,
    # brightest just behind the jaw and low down, deepening to ocean blue toward the back
    # and the top, in soft 2-texel steps.
    field = {(x, y) for u0, v0, u1, v1 in GILL_FIELD for y in range(v0, v1 + 1) for x in range(u0, u1 + 1)}
    for x, y in field:
        front = 1 - (x - 5) / 9
        low = (y - 2) / 12
        k = 0.5 * front + 0.5 * low + 0.08 * (((x // 2) + (y // 2)) % 2)
        if any(n not in field for n in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1))):
            k -= 0.35    # the field's rim deepens into the navy hide
        put(img, x, y, BLUE[max(1, min(5, int(1.6 + k * 4.2)))])
    # Gill slits: three black bars (their lit lines and raised lips are separate parts).
    for y, (a, b) in GILL_SLITS:
        rect(img, a, y, b, y, MOUTH[0])
    # Eye on the navy level with the lip, behind its wrap: black, a dark socket rim and one
    # ice glint (EYE_ROWS / EYE_U match the 3D eye).
    rect(img, EYE_U - 1, EYE_ROW - 1, EYE_U + 2, EYE_ROW + 2, NAVY[2])
    rect(img, EYE_U, EYE_ROW, EYE_U + 1, EYE_ROW + 1, MOUTH[0])
    put(img, EYE_U, EYE_ROW, ICE[1])
    # Upper jaw line wrapping in from the front (under and just behind the lip's 3D wrap).
    rect(img, 0, 3, 2, 5, WHITE[5])
    # Lower jaw / cheek: the pale underside sweeping back under the eye, greyer than the
    # teeth so it reads as countershading rather than more teeth.
    for y, (a, b) in {11: (0, 3), 12: (0, 4), 13: (0, 5), 14: (0, 5), 15: (0, 4)}.items():
        rect(img, a, y, b, y, WHITE[3])
        put(img, b, y, WHITE[1])
    # Darker bottom edge.
    for x in range(6, 16):
        put(img, x, 15, NAVY[3])
    return img


def paint_top():
    """Seen from above, the front at the top of the texture."""
    img = canvas(16)
    navy_skin(img, (0, 0, 15, 15), seed=37, lift=1)
    for x, y in ((2, 3), (3, 3), (9, 6), (10, 6), (5, 11), (13, 9), (6, 2)):
        put(img, x, y, NAVY[8])
    return img


def paint_back():
    img = canvas(16)
    navy_skin(img, (0, 0, 15, 15), seed=41, lift=-1)
    for x in range(16):
        put(img, x, 15, NAVY[1])
        put(img, x, 14, NAVY[2] if x % 2 else NAVY[3])
    return img


def paint_inner():
    """Mouth interior and lining: near-black with a cold navy tint."""
    img = canvas(16)
    mottle(img, (0, 0, 15, 15), [MOUTH[1], MOUTH[2], MOUTH[3]], seed=5, weights=[3, 4, 2], specks=0.04,
           speck_tones=[MOUTH[0], MOUTH[4]])
    return img


def paint_jaw():
    """White jaw material (the shark's pale underside): rows 0-1 lit rim, then mottled white;
    the bottom half (rows 8-15) is the shaded underside."""
    img = canvas(16)
    white_skin(img, (0, 0, 15, 7), seed=7, base=5)
    white_skin(img, (0, 8, 15, 15), seed=8, base=3)
    for x in range(16):
        put(img, x, 0, WHITE[7] if x % 4 else WHITE[6])
    return img


def paint_tooth():
    """Teeth: bright at the root band, cooling to grey toward the tip; columns 8-15 are the
    darker inside faces."""
    img = canvas(16)
    for y in range(16):
        tone = WHITE[7] if y == 0 else WHITE[6] if y < 2 else WHITE[5] if y < 4 else WHITE[4]
        rect(img, 0, y, 7, y, tone)
        rect(img, 8, y, 15, y, WHITE[2] if y < 3 else WHITE[1])
    for y in range(16):
        put(img, 0, y, WHITE[7])
    return img


def paint_fin():
    """Fin and gill-lip atlas. Column 2: light steel blue. Columns 3-13: the blade seen
    side-on, a steel-blue sheen along its leading side darkening to navy toward the trailing
    edge (u grows away from the leading edge). Column 14: the treads of the stepped leading
    edge, lit at the front, navy behind."""
    img = canvas(16)
    rect(img, 0, 0, 1, 15, NAVY[5])
    rect(img, 2, 0, 2, 15, BLUE[6])
    for x in range(3, 16):
        tone = [BLUE[5], BLUE[4], BLUE[3], NAVY[7], NAVY[6]][x - 3] if x < 8 else NAVY[5]
        rect(img, x, 0, x, 15, tone)
    for y in range(0, 16, 3):      # faint growth bands across the blade
        rect(img, 5, y, 7, y, NAVY[6])
    rect(img, 14, 0, 15, 15, NAVY[6])
    rect(img, 14, 0, 15, 0, BLUE[6])
    rect(img, 14, 1, 15, 1, BLUE[4])
    return img


def paint_eye():
    """The 3D eye (2x2 texels): glossy black with one ice-blue glint at the front-top."""
    img = canvas(16, fill=MOUTH[0])
    put(img, 0, 0, ICE[1])
    put(img, 1, 1, MOUTH[2])
    return img


def gill_frame(t: float):
    """The glowing gill line: cyan with a soft brightening that runs from the front of the
    slit to the back over one loop, the whole line breathing gently."""
    img = canvas(16)
    for x in range(16):
        pulse = 0.5 + 0.5 * math.cos(2 * math.pi * (x / 16 - t))
        breath = 0.8 + 0.2 * wave(t)
        k = (0.25 + 0.75 * pulse) * breath
        rect(img, x, 0, x, 15, mix(BLUE[5], CYAN[0], k))
        rect(img, x, 4, x, 11, mix(BLUE[6], CYAN[1], k))
    return img


def textures() -> None:
    save(paint_front(), "front")
    save(paint_side(), "side")
    save(paint_top(), "top")
    save(paint_back(), "back")
    save(paint_inner(), "inner")
    save(paint_jaw(), "jaw")
    save(paint_tooth(), "tooth")
    save(paint_fin(), "fin")
    save(paint_eye(), "eye")
    save_animation(animate(gill_frame, 8), "gill", frametime=4, interpolate=True)


# --------------------------------------------------------------------------------------
# Geometry helpers
# --------------------------------------------------------------------------------------

def _size(side, frm, to):
    dx, dy, dz = (to[i] - frm[i] for i in range(3))
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
            "up": (dx, dz), "down": (dx, dz)}[side]


def proj(side, frm, to):
    """UVs of a face projected onto the cube's painted face for that side."""
    (x0, y0, z0), (x1, y1, z1) = frm, to
    if side == "north":
        return [(X1 - x1) / T, (Y1 - y1) / T, (X1 - x0) / T, (Y1 - y0) / T]
    if side == "south":
        return [(x0 - X0) / T, (Y1 - y1) / T, (x1 - X0) / T, (Y1 - y0) / T]
    if side == "west":
        return [(z0 - Z0) / T, (Y1 - y1) / T, (z1 - Z0) / T, (Y1 - y0) / T]
    if side == "east":   # mirrored so the front of the side texture stays at the front
        return [(z1 - Z0) / T, (Y1 - y1) / T, (z0 - Z0) / T, (Y1 - y0) / T]
    if side == "up":
        return [(x0 - X0) / T, (z0 - Z0) / T, (x1 - X0) / T, (z1 - Z0) / T]
    return [(x0 - X0) / T, (Z1 - z1) / T, (x1 - X0) / T, (Z1 - z0) / T]


SHELL_TEX = {"north": "front", "south": "back", "east": "side", "west": "side", "up": "top", "down": "inner"}


def _clamp_uv(uv):
    return [round(min(16.0, max(0.0, v)), 4) for v in uv]


def part(frm, to, spec: dict, **kw) -> dict:
    """A box whose faces are given per side:
        "shell"            projected onto the cube's painted face for that side
        "shell:<tex>"      projected, but onto <tex>
        (tex, u, v)        <tex> from texel (u, v) at T units per texel
        (tex, u, v, True)  the same, mirrored left-right
        (tex, [uv])        explicit UVs
    Sides left out are skipped (hidden faces)."""
    faces = {}
    for side, s in spec.items():
        if isinstance(s, str):
            tex = SHELL_TEX[side] if s == "shell" else s.split(":", 1)[1]
            faces[side] = (tex, _clamp_uv(proj(side, frm, to)))
        elif len(s) == 2:
            faces[side] = (s[0], _clamp_uv(s[1]))
        else:
            tex, u, v = s[:3]
            w, h = _size(side, frm, to)
            uv = [u, v, u + w / T, v + h / T]
            if len(s) > 3 and s[3]:
                uv = [uv[2], uv[1], uv[0], uv[3]]
            faces[side] = (tex, _clamp_uv(uv))
    skip = tuple(side for side in ("north", "south", "east", "west", "up", "down") if side not in spec)
    first = next(iter(faces.values()))[0]
    return box(frm, to, first, faces=faces, skip=skip, **kw)


def mirror_x(elements):
    """Mirror parts across x = 8 (the wearer's left <-> right), keeping each face's texture
    reading the right way: side faces stay front-at-u0, front faces flip."""
    out = []
    for e in elements:
        a, b = e["from"], e["to"]
        m = {"from": [round(16 - b[0], 4), a[1], a[2]], "to": [round(16 - a[0], 4), b[1], b[2]], "faces": {}}
        for side, face in e["faces"].items():
            new = {"east": "west", "west": "east"}.get(side, side)
            f = dict(face)
            u0, v0, u1, v1 = f["uv"]
            f["uv"] = [u1, v0, u0, v1]
            m["faces"][new] = f
        for key in ("light_emission", "shade"):
            if key in e:
                m[key] = e[key]
        if "rotation" in e:
            r = dict(e["rotation"])
            o = list(r["origin"])
            o[0] = round(16 - o[0], 4)
            r["origin"] = o
            if "axis" in r:
                if r["axis"] in ("y", "z"):
                    r["angle"] = -r["angle"]
            else:
                r["y"], r["z"] = -r.get("y", 0), -r.get("z", 0)
            m["rotation"] = r
        out.append(m)
    return out


# --------------------------------------------------------------------------------------
# Model
# --------------------------------------------------------------------------------------

def shell() -> list[dict]:
    """The cube: crown, back plate and the snout over the mouth (the sides and the front
    columns are in side_pieces()). Inner faces are the dark lining."""
    return [
        # Crown: the whole top slab.
        part((X0, IN1, Z0), (X1, Y1, Z1), {"north": "shell", "south": "shell", "east": "shell", "west": "shell",
                                           "up": "shell", "down": "shell"}),
        # Back plate between the side plates.
        part((IN0, Y0, IN1), (IN1, IN1, Z1), {"south": "shell", "north": "shell:inner", "down": "shell:inner"}),
        # Snout over the mouth; its underside is the roof of the mouth.
        part((MOUTH_X[0], MOUTH_Y[1], Z0), (MOUTH_X[1], IN1, IN0), {"north": "shell", "down": "shell:inner",
                                                                    "south": "shell:inner"}),
    ]


GAPE_Y = (5.5, MOUTH_Y[1])   # floor and roof of the gape cut into each front corner
GAPE_Z = 3.6                 # how far back along the side it runs
GAPE_X = -0.1                # its back wall (still outside the hat layer)


def side_pieces() -> list[dict]:
    """Both side plates and the front columns framing the mouth, split so the mouth's gape
    cuts into each front corner as a real recess (open to the front and the side) with dark
    walls. Built from the wearer's-left layout; the right side is the same with x flipped,
    re-projected so the painted faces stay continuous."""
    g0, g1 = GAPE_Y
    left = [  # (x0, x1, y0, y1, z0, z1, faces); "out" / "in" are the -X / +X faces
        (X0, MOUTH_X[0], MOUTH_Y[0], g0, Z0, IN0,
         {"north": "shell", "out": "shell", "in": "shell:inner", "up": "shell:inner", "south": "shell:inner"}),
        (GAPE_X, MOUTH_X[0], g0, g1, Z0, IN0,
         {"north": "shell", "out": "shell:inner", "in": "shell:inner", "south": "shell:inner"}),
        (X0, MOUTH_X[0], g1, IN1, Z0, IN0,
         {"north": "shell", "out": "shell", "in": "shell:inner", "down": "shell:inner", "south": "shell:inner"}),
        (X0, IN0, Y0, g0, IN0, GAPE_Z, {"out": "shell", "in": "shell:inner", "up": "shell:inner",
                                        "down": "shell:inner"}),
        (GAPE_X, IN0, g0, g1, IN0, GAPE_Z, {"out": "shell:inner", "in": "shell:inner"}),
        (X0, IN0, g1, IN1, IN0, GAPE_Z, {"out": "shell", "in": "shell:inner", "down": "shell:inner"}),
        (X0, IN0, Y0, IN1, GAPE_Z, Z1, {"out": "shell", "in": "shell:inner", "south": "shell",
                                        "down": "shell:inner", "north": "shell:inner"}),
    ]
    parts = []
    for x0, x1, y0, y1, z0, z1, faces in left:
        for side_out, side_in, (a, b) in (("west", "east", (x0, x1)), ("east", "west", (16 - x1, 16 - x0))):
            spec = {}
            for key, val in faces.items():
                spec[{"out": side_out, "in": side_in}.get(key, key)] = val
            parts.append(part((a, y0, z0), (b, y1, z1), spec))
    # Teeth round the gape: two hanging from its roof, one standing on its floor.
    teeth = []
    for zc, w, h, tip in ((0.6, 1.1, 1.0, 0.7), (2.6, 1.0, 0.9, 0.6)):
        teeth += side_tooth(zc, w, g1, h, tip, down=True)
    teeth += side_tooth(1.7, 1.0, g0, 0.9, 0.6, down=False)
    return parts + teeth + mirror_x(teeth)


def side_tooth(zc, w, base, h, tip_h, down=True):
    """A tooth in the gape on the wearer's left, pointing down from the roof (or up from the
    floor): a root block w x h along z and a half-width point."""
    x0, x1 = X0 - 0.08, GAPE_X - 0.05
    sgn = -1 if down else 1
    out = []
    for (wz, y_a, y_b, v) in ((w, base, base + sgn * h, 0), (w / 2, base + sgn * h, base + sgn * (h + tip_h), 2)):
        lo, hi = sorted((y_a, y_b))
        face = {"west": ("tooth", 0, v), "north": ("tooth", 8, v), "south": ("tooth", 8, v),
                ("down" if down else "up"): ("tooth", 0, 3)}
        out.append(part((x0 + (0.05 if v else 0), lo, zc - wz / 2), (x1, hi, zc + wz / 2), face))
    return out


def upper_jaw() -> list[dict]:
    """The white upper lip standing a little proud of the snout (with a raised middle step),
    wrapping round both front corners, and the teeth hanging from it."""
    parts = [
        part((X0, MOUTH_Y[1], LIP), (X1, 14.3, Z0), {"north": "shell", "up": ("jaw", 0, 0),
                                                       "down": ("jaw", 0, 8)}),
        part((5.8, 14.3, LIP), (10.2, 15.4, Z0), {"north": "shell", "up": ("jaw", 0, 0), "east": ("jaw", 0, 1),
                                                    "west": ("jaw", 0, 1)}),
    ]
    wrap = part((X0 - WRAP, MOUTH_Y[1], LIP), (X0, 14.3, 1.4),
                {"north": ("jaw", 0, 1), "west": ("jaw", 0, 1), "up": ("jaw", 0, 0), "down": ("jaw", 0, 8),
                 "south": ("jaw", 0, 8)})
    parts += [wrap] + mirror_x([wrap])
    teeth = []
    # Chunky blocks with a short step to the point: corner fangs, a pair over the eyes, a
    # small middle tooth. All stop 0.8 above the eyes (row 4 of the face, y 6.4-8.0).
    teeth += tooth_down(0.3, 2.4, 1.3, 1.3, 0.9, z0=LIP - 0.05)
    teeth += tooth_down(4.7, 2.2, 1.3, 1.1, 0.9)
    teeth += tooth_down(8.0, 1.6, 1.1, 0.0, 0.0)
    # A second, smaller row set back in the gaps, the way sharks carry spare rows.
    teeth += tooth_down(2.55, 1.3, 0.7, 0.6, 0.5, z0=-0.35, z1=0.35)
    teeth += tooth_down(6.4, 1.2, 0.6, 0.6, 0.4, z0=-0.35, z1=0.35)
    parts += teeth
    parts += mirror_x([e for e in teeth if e["to"][0] < 7.1])
    return parts


def _tooth_faces(v):
    return {"north": ("tooth", 0, v), "east": ("tooth", 8, v), "west": ("tooth", 8, v)}


def tooth_down(cx, w, h, tip_w, tip_h, top=MOUTH_Y[1], z0=LIP, z1=-0.5):
    """A hanging tooth: a root block w x h and, when tip_w > 0, a narrower point below it."""
    out = [part((cx - w / 2, top - h, z0), (cx + w / 2, top, z1), dict(_tooth_faces(0), down=("tooth", 0, 3)))]
    if tip_w > 0:
        out.append(part((cx - tip_w / 2, top - h - tip_h, z0 + 0.08), (cx + tip_w / 2, top - h, z1 - 0.08),
                        dict(_tooth_faces(2), down=("tooth", 0, 4))))
    return out


def tooth_up(cx, w, h, tip_w, tip_h, base, z0, z1):
    """A standing tooth: a root block w x h and, when tip_w > 0, a narrower point above it."""
    out = [part((cx - w / 2, base, z0), (cx + w / 2, base + h, z1), dict(_tooth_faces(2), up=("tooth", 0, 1)))]
    if tip_w > 0:
        out.append(part((cx - tip_w / 2, base + h, z0 + 0.08), (cx + tip_w / 2, base + h + tip_h, z1 - 0.08),
                        dict(_tooth_faces(0), up=("tooth", 0, 0))))
    return out


JAW_MID_TOP = 1.6            # the recessed middle of the lower jaw, level with the chin's underside


def lower_jaw() -> list[dict]:
    """The lower jaw: two white corner blocks that jut forward and wrap back along the
    sides, each carrying a corner tooth, and between them a recessed dark middle (the open
    mouth running down under the chin) with two teeth standing on it."""
    parts = [
        part((2.5, -0.3, -0.9), (13.5, JAW_MID_TOP, 0.7), {"north": ("inner", 0, 0), "up": ("inner", 0, 4),
                                                          "down": ("jaw", 0, 9), "south": ("inner", 0, 8)}),
    ]
    corner = [
        part((X0 - WRAP, -0.3, JAW), (2.5, MOUTH_Y[0], IN0), {"north": ("jaw", 0, 1), "west": ("jaw", 0, 1),
                                                               "east": ("jaw", 0, 1), "up": ("jaw", 0, 0),
                                                               "down": ("jaw", 0, 9), "south": ("inner", 0, 8)}),
        part((X0 - WRAP, -0.3, IN0), (X0, MOUTH_Y[0], 2.5), {"west": ("jaw", 0, 1), "up": ("jaw", 0, 0),
                                                              "down": ("jaw", 0, 9), "south": ("jaw", 0, 8)}),
    ]
    corner += tooth_up(0.3, 2.2, 1.3, 1.1, 0.9, base=MOUTH_Y[0], z0=JAW + 0.1, z1=-0.6)
    inner = tooth_up(4.4, 2.0, 1.1, 1.0, 0.8, base=JAW_MID_TOP, z0=-0.8, z1=-0.1)
    parts += corner + mirror_x(corner) + inner + mirror_x(inner)
    return parts


def gills_and_eyes() -> list[dict]:
    """On the wearer's left side (mirrored to the right): the self-lit gill field panels, a
    thin glowing line along the bottom of each slit, and the eye."""
    parts = []
    for u0, v0, u1, v1 in GILL_FIELD:
        parts.append(part((X0 - 0.1, row(v1 + 1), side_z(u0)), (X0, row(v0), side_z(u1 + 1)),
                          {"west": "shell"}, shade=False, glow=3))
    for v, (a, b) in GILL_SLITS:
        y = row(v + 1)
        parts.append(part((X0 - 0.18, y, side_z(a)), (X0 - 0.1, y + 0.3, side_z(b + 1)),
                          {"west": ("gill", [0, 4, 16, 12]), "up": ("gill", [0, 0, 16, 4]),
                           "down": ("gill", [0, 0, 16, 4])}, glow=8, shade=False))
        # A raised lip along the top of the slit: a lit ridge with a dark underside, so the
        # slit reads as a real opening under it.
        top = row(v)
        parts.append(part((X0 - 0.42, top, side_z(a) - 0.1), (X0 - 0.1, top + 0.3, side_z(b + 1) + 0.1),
                          {"west": ("fin", [3, 0, 4, 1]), "up": ("fin", [2, 0, 3, 1]),
                           "down": ("inner", [0, 0, 6, 1]), "north": ("fin", [4, 0, 5, 1]),
                           "south": ("fin", [4, 0, 5, 1])}, shade=False, glow=3))
    parts.append(part((X0 - 0.15, row(EYE_ROW + 2) + 0.1, side_z(EYE_U) + 0.1),
                      (X0, row(EYE_ROW) - 0.1, side_z(EYE_U + 2) - 0.1),
                      {"west": ("eye", [0, 0, 2, 2]), "north": ("eye", [0, 0, 1, 2]), "up": ("eye", [0, 0, 2, 1])}))
    return parts + mirror_x(parts)


FIN_STEP = 0.88                  # height of each slice of the fin
# The fin side-on, bottom slice first: (front z, back z, thickness). The fronts step back
# along a 51-degree leading edge, the backs make a concave trailing edge whose tip hooks
# back, and the slices thin toward the tip so the fin tapers seen from ahead.
FIN_SLICES = ((7.4, 13.9, 1.7), (8.5, 13.3, 1.5), (9.6, 13.0, 1.3), (10.7, 13.0, 1.1), (11.8, 13.2, 0.9),
              (12.7, 13.5, 0.7))


def fin() -> list[dict]:
    """A small swept-back dorsal fin built as stacked slices: a stepped leading edge whose
    treads catch the light (like the concept's chestplate fin), a steel-blue band following
    it down the blade, navy behind."""
    parts = []
    for i, (z0, z1, thick) in enumerate(FIN_SLICES):
        y0 = Y1 + i * FIN_STEP
        x0, x1 = 8 - thick / 2, 8 + thick / 2
        span = (z1 - z0) / T
        v = i * 2.5
        parts.append(part((x0, y0, z0), (x1, y0 + FIN_STEP, z1), {
            "west": ("fin", [3, v, 3 + span, v + 0.8]), "east": ("fin", [3 + span, v, 3, v + 0.8]),
            "north": ("fin", [3, v, 4, v + 0.8]), "up": ("fin", [14, 0, 15, span]),
            "south": ("fin", [9, v, 10, v + 0.8])}))
    return parts


def build() -> list[dict]:
    return shell() + side_pieces() + upper_jaw() + lower_jaw() + gills_and_eyes() + fin()


def models() -> dict:
    parts = build()
    return {"main": model(parts, display("helmet", parts, gui_rotation=(25, 145, 0)))}
