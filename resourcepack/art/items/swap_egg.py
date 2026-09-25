"""Swap Egg: a mint-green throwable egg split into two contrasting halves.

The shell is the shared egg body turned on a lathe. A raised zigzag seam runs round its
waist, a white-hot core in a dark setting, dividing a mint top from a magenta bottom, with
a gem knotting every corner in the colour of the half it does not sit in. Each half
carries a band of block arrows in the other half's colour, circling the egg in opposite
directions (the swap icon wrapped round the shell); in the lower band each arrow shoves a
die.

The shell is lit in its texture (shade off): a glossy light from the upper left, baked
per texel from the smooth egg profile, so the lathe steps melt into one round shell and
the mint stays vivid in the inventory.

Animation (32 frames, 3.2 s): the arrow bands scroll round the shell in opposite
directions, and twice a loop the halves trade colours in a wave that bursts out of the
seam: the seam flares, the corner gems and dice change over as it passes, and the crown's
glint twinkles when it lands.
"""
from __future__ import annotations

import math

from art.kit import (animate, box, canvas, display, display_matrix, fit, lathe, mix, model, place, prism, rgba,
                     save_animation, sparkle, turn)

ID = "swap_egg"
NAME = "Swap Egg"
KIND = "item"
COUNTERPART = "item/egg"

# --------------------------------------------------------------------------------------
# The shared egg body
# --------------------------------------------------------------------------------------
EGG_PROFILE = [(0, 1.4), (0.7, 3.2), (1.8, 4.3), (3.3, 4.9), (5.0, 5.0), (6.8, 4.7), (8.4, 4.0),
               (9.8, 3.0), (10.9, 1.8), (11.5, 0.6)]
CENTRE = (8.0, 2.2, 8.0)
CX, CY, CZ = CENTRE
# Lathe slices, bottom to top: (h0, h1, apothem).
SLICES = [(h0, h1, (r0 + r1) / 2) for (h0, r0), (h1, r1) in zip(EGG_PROFILE, EGG_PROFILE[1:])]
# Texture rows per slice (bottom to top), about 2 texels per unit and rounded so every
# slice starts on a whole row. The shell uses rows 0-23; rows 24-31 hold flat swatches.
ROWS = [1, 2, 3, 4, 4, 4, 3, 2, 1]
ROW0 = [sum(ROWS[k + 1:]) for k in range(len(ROWS))]      # top row of each slice
SHELL_ROWS = sum(ROWS)
HALF_FACET = 180.0 / 16

TOP_BAND = (6, 10)          # arrow band rows (inclusive): slice 5 + the top row of slice 4
BOTTOM_BAND = (16, 20)      # the two bottom rows of slice 3 + slice 2
PEAK_H, TROUGH_H = 5.95, 4.45
SEAM_MID = (PEAK_H + TROUGH_H) / 2
SEAM_R = 4.95

# --------------------------------------------------------------------------------------
# Palettes (darkest -> lightest)
# --------------------------------------------------------------------------------------
MINT = ["#04243a", "#06424f", "#05705f", "#00a472", "#00d47f", "#00ff88", "#72ffb9", "#d2ffe6"]
ROSE = ["#26062f", "#4f0b4f", "#86126c", "#bd1d83", "#e82b92", "#ff4fa6", "#ff8fc7", "#ffd4ea"]
INK = "#160a2a"
LIGHT = ["#a9ffd9", "#e4fff2", "#ffffff"]
SETTING = ["#2a1450", "#4a2a7a", "#170a2e"]      # the seam's setting: outer face, top, underside

# --------------------------------------------------------------------------------------
# Animation: 32 frames x 2 ticks = 3.2 s. The halves trade colours twice per loop.
# --------------------------------------------------------------------------------------
FRAMES = 32
FRAMETIME = 2
SWAPS = ((11, 3), (27, 3))          # (frame the wave leaves the seam, frames it takes)
REACH = 14.0                        # px from the seam to the far pole

GUI_ROTATION = (15, 210, -12)
SIZE = 1.5


def _front_phi(rotation) -> float:
    """Direction (degrees from +X toward +Z) of the facet the inventory camera faces."""
    m = display_matrix(rotation)
    best = max(range(720), key=lambda i: sum(m[2][j] * v for j, v in
                                             enumerate((math.cos(math.radians(i / 2)), 0.0,
                                                        math.sin(math.radians(i / 2))))))
    return round(best / 2 / 22.5) * 22.5


FRONT_PHI = _front_phi(GUI_ROTATION)
U_ORIGIN = FRONT_PHI + 78.75        # the camera-facing facet gets texture columns 12-15


# --------------------------------------------------------------------------------------
# Shell coordinates and the baked light
# --------------------------------------------------------------------------------------

def slice_of_row(y: int):
    """(slice index, texel top height, texel bottom height) of shell texture row y."""
    for k in range(len(SLICES)):
        if ROW0[k] <= y < ROW0[k] + ROWS[k]:
            h0, h1, _ = SLICES[k]
            dh = (h1 - h0) / ROWS[k]
            i = y - ROW0[k]
            return k, h1 - i * dh, h1 - (i + 1) * dh
    raise ValueError(y)


def row_h(y: int) -> float:
    _, ht, hb = slice_of_row(y)
    return (ht + hb) / 2


def column_phi(x: float) -> float:
    """Facet direction under texture column x, on the half facing the inventory camera."""
    phi = U_ORIGIN - (x + 0.5) * 180.0 / 32
    while phi < FRONT_PHI - 90:
        phi += 180
    while phi >= FRONT_PHI + 90:
        phi -= 180
    return phi


_SMOOTH = [(-0.3, 0.0)] + EGG_PROFILE + [(11.75, 0.0)]


def radius(h: float) -> float:
    if h <= _SMOOTH[0][0]:
        return 0.0
    for (h0, r0), (h1, r1) in zip(_SMOOTH, _SMOOTH[1:]):
        if h0 <= h <= h1:
            return r0 + (r1 - r0) * (h - h0) / (h1 - h0)
    return 0.0


U_BRIGHT = 7.0                  # texture column lit hardest: the inventory view's front left


def _light(x: int, y: int) -> float:
    """Brightness 0..1 of shell texel (x, y). Up-facing parts of the smooth egg profile are
    lit, and round the shell the light follows one cosine per half turn, so it is seamless
    where the texture repeats: brightest front left, a shadow front right, and a little
    reflected light again at the right-hand rim."""
    h = row_h(y)
    rp = radius(h + 0.5) - radius(h - 0.5)
    ny = -rp / math.sqrt(1 + rp * rp)                  # vertical part of the surface normal
    around = math.cos(2 * math.pi * (x + 0.5 - U_BRIGHT) / 32)
    return max(0.0, min(1.0, 0.5 + 0.3 * ny + 0.3 * around * math.sqrt(1 - ny * ny)))


LIT = [[_light(x, y) for x in range(32)] for y in range(SHELL_ROWS)]
# Hand-placed glints on the glossy crown (texel -> strength 0..1), front left.
GLINT = {(6, 3): 1.0, (7, 3): 0.7, (6, 4): 0.8, (5, 4): 0.4, (7, 2): 0.45, (8, 2): 0.3, (5, 5): 0.3,
         (9, 1): 0.35,
         (4, 21): 0.45, (5, 21): 0.3, (5, 22): 0.2}                # a reflected glint on the base


def tone(x: int, y: int) -> int:
    """Ramp index of the lit shell at texel (x, y)."""
    return max(1, min(7, round(1.8 + 5.0 * LIT[y][x])))


def seam_h(x: float) -> float:
    """Height of the zigzag seam at texture column x (px; peaks every 8 px)."""
    p = (x % 8.0) / 8.0
    return TROUGH_H + (PEAK_H - TROUGH_H) * abs(2 * p - 1)


def swap_at(frame: int, dist: float) -> tuple[bool, bool]:
    """Whether a texel `dist` px from the seam shows traded colours at this frame, and
    whether the wave's leading edge is passing over it."""
    swapped, front = False, False
    for start, length in SWAPS:
        if frame >= start + length:
            swapped = not swapped
            continue
        if frame >= start:
            edge = (frame - start + 1) / length * REACH
            if dist <= edge:
                swapped = not swapped
                front = edge - dist < 1.0 and edge < REACH
        break
    return swapped, front


def flare(frame: int) -> float:
    """Seam brightness boost: full as a wave leaves the seam, fading over 6 frames."""
    best = 0.0
    for start, _ in SWAPS:
        d = (frame - start) % FRAMES
        if d < 6:
            best = max(best, 1.0 - d / 6)
    return best


def twinkle(frame: int) -> float:
    """Size of the glint's twinkle: it bursts as a wave reaches the poles, then shrinks."""
    for start, length in SWAPS:
        d = frame - (start + length - 1)
        if 0 <= d < 3:
            return (1.0, 0.65, 0.3)[d]
    return 0.0


def half_at(frame: int, x: int, y: int):
    """(own ramp, other ramp, wave edge?) for shell texel (x, y) at this frame."""
    hc = row_h(y)
    sh = seam_h(x + 0.5)
    swapped, front = swap_at(frame, abs(hc - sh) * 2)
    top = hc > sh
    return ((MINT, ROSE) if top != swapped else (ROSE, MINT)) + (front,)


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def paint_shell(t: float):
    frame = round(t * FRAMES) % FRAMES
    glow = flare(frame)
    img = canvas(32)
    px = img.load()
    for y in range(SHELL_ROWS):
        hc = row_h(y)
        for x in range(32):
            own, _, front = half_at(frame, x, y)
            dist = abs(hc - seam_h(x + 0.5)) * 2
            i = tone(x, y)
            c = own[i]
            if (x, y) in GLINT:
                c = mix(own[7], "#ffffff", GLINT[(x, y)])                     # the gloss
            if dist < 1.1:
                c = mix(c, INK, 0.6)                                           # groove under the seam
            elif dist < 2.4:
                c = mix(c, mix(own[7], LIGHT[2], 0.4), 0.2 + 0.3 * glow)       # light leaking out
            if front:
                c = mix(c, LIGHT[1], 0.3)                                      # the wave's crest
            px[x, y] = rgba(c)
    # The glints twinkle as each wave lands on the poles.
    tw = twinkle(frame)
    if tw:
        sparkle(img, 6, 3, tw, reach=2)
        sparkle(img, 4, 21, tw * 0.7, colour=LIGHT[1], reach=1)
    # Flat swatches below the shell, each a 2x2 texel block so mipmaps keep it clean; the
    # whole area is opaque so nothing transparent bleeds into them.
    fill_block = lambda col, row, c: [px.__setitem__((2 * col + i, 2 * row + j), rgba(c))
                                      for i in (0, 1) for j in (0, 1)]
    for col in range(16):
        for row in range(12, 16):
            fill_block(col, row, INK)
    # Ledges (block row 12: a slice's top face, 13: its underside), matching the shell
    # just beside them on the camera-facing side.
    for k in range(len(SLICES)):
        for row, y in ((12, ROW0[k]), (13, ROW0[k] + ROWS[k] - 1)):
            own, _, _ = half_at(frame, 13, y)
            fill_block(k, row, own[tone(13, y)])
    # The seam's dark setting (outer face, top, underside), lit up by each flare.
    for col, c in enumerate(SETTING):
        fill_block(col, 14, mix(c, LIGHT[0], 0.35 * glow))
    # Gems on the zigzag's corners, each in the opposite colour to the half it bites
    # into: block row 14 (from column 4) for the peaks, row 15 for the troughs.
    for row, h in ((14, PEAK_H + 0.4), (15, TROUGH_H - 0.4)):
        top = h > SEAM_MID
        swapped, _ = swap_at(frame, 1.0)
        other = ROSE if top != swapped else MINT
        for col, c in enumerate((mix(other[7], "#ffffff", 0.55), mix(other[7], other[6], 0.35), other[6],
                                 other[5])):
            fill_block(4 + col, row, mix(c, "#ffffff", 0.3 * glow))
    return img


# Arrow band glyphs, drawn pointing right, one 32-px period (5 rows each). The top band
# scrolls right; the bottom band is drawn mirrored so it points and scrolls left. The top
# band carries long arrows alone; in the bottom one each arrow shoves a die (1 and 2).
BANDS = {
    "top": [
        "......Ho........" "......Ho........",
        "HHHHHHHHo......." "HHHHHHHHo.......",
        "AAAAAAAAAo......" "AAAAAAAAAo......",
        "SSSSSSSSo......." "SSSSSSSSo.......",
        "......So........" "......So........",
    ],
    "bottom": [
        ".....Ho....111.." ".....Ho....222..",
        "HHHHHHHo..11111." "HHHHHHHo..22222.",
        "AAAAAAAAo.11111." "AAAAAAAAo.22222.",
        "SSSSSSSo..11111." "SSSSSSSo..22222.",
        ".....So....111.." ".....So....222..",
    ],
}
PIPS = {  # pip texels inside a 5x5 die (a six would merge into two bars at this size)
    2: ((1, 1), (3, 3)),
    3: ((1, 1), (2, 2), (3, 3)),
    4: ((1, 1), (3, 1), (1, 3), (3, 3)),
    5: ((1, 1), (3, 1), (2, 2), (1, 3), (3, 3)),
}
# Faces shown by each die before and after each swap: they re-roll every time.
FACES = {"1": (5, 3), "2": (4, 2)}
DIE_AT = {ch: BANDS["bottom"][1].index(ch) for ch in "12"}


def paint_arrows(t: float):
    frame = round(t * FRAMES) % FRAMES
    img = canvas(32)
    px = img.load()
    for band, rows, step in (("top", TOP_BAND, 1), ("bottom", BOTTOM_BAND, -1)):
        glyphs = BANDS[band]
        ymid = rows[0] + 2
        rolled, _ = swap_at(frame, abs(row_h(ymid) - SEAM_MID) * 2)
        for y in range(rows[0], rows[1] + 1):
            gy = y - rows[0]
            for x in range(32):
                gx = (x - frame * step) % 32
                if step < 0:
                    gx = 31 - gx
                ch = glyphs[gy][gx]
                if ch == ".":
                    continue
                own, other, _ = half_at(frame, x, y)
                i = tone(x, y)
                if ch in "12":
                    face = FACES[ch][1 if rolled else 0]
                    dx = gx - DIE_AT[ch]
                    if step < 0:
                        dx = 4 - dx                     # keep the die's shading on its lit side
                    if (dx, gy) in PIPS[face]:
                        c = own[max(0, i - 4)]
                    elif gy == 4 or dx == 4:
                        c = own[min(7, i + 1)]
                    elif gy == 0 or dx == 0:
                        c = mix(own[7], "#ffffff", 0.5)
                    else:
                        c = own[7] if i >= 4 else own[6]
                elif ch == "o":
                    c = own[max(0, i - 4)]
                else:
                    # arrows keep their full colour on the lit side instead of paling out
                    c = other[max(3, min(5, i)) + {"H": 1, "A": 0, "S": -1}[ch]]
                px[x, y] = rgba(c)
    return img


def paint_seam(t: float):
    """The zigzag ridge: white-hot core, the edge facing each half tinted with its colour.
    Rows 0-7 and 24-31 are the ridge's top and bottom sides, a step darker."""
    frame = round(t * FRAMES) % FRAMES
    g = flare(frame)
    top_swapped, _ = swap_at(frame, 1.0)
    upper, lower = (MINT, ROSE) if not top_swapped else (ROSE, MINT)
    img = canvas(32)
    px = img.load()
    for y in range(32):
        if y < 8:
            c = mix(upper[6], LIGHT[2], 0.25 + 0.6 * g)
        elif y < 12:
            c = mix(upper[7], LIGHT[2], 0.5 + 0.5 * g)
        elif y < 20:
            c = LIGHT[2]
        elif y < 24:
            c = mix(lower[7], LIGHT[2], 0.5 + 0.5 * g)
        else:
            c = mix(lower[5], LIGHT[2], 0.2 + 0.6 * g)
        for x in range(32):
            px[x, y] = rgba(c)
    return img


def textures() -> None:
    save_animation(animate(paint_shell, FRAMES), "shell", frametime=FRAMETIME)
    save_animation(animate(paint_arrows, FRAMES), "arrows", frametime=FRAMETIME)
    save_animation(animate(paint_seam, FRAMES), "seam", frametime=FRAMETIME)


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def u_left(phi_c: float) -> float:
    """Texture u (0..16) of the left edge, seen from outside, of the facet facing phi_c."""
    return round(((U_ORIGIN - (phi_c + HALF_FACET)) % 180.0) * 16.0 / 180.0, 4)


def face_phi(side: str, a: float) -> float:
    """Direction a slab's side face points after the slab is turned `a` degrees about Y."""
    return {"east": -a, "west": 180.0 - a, "south": 90.0 - a, "north": 270.0 - a}[side]


def angle_of(e: dict) -> float:
    rot = e.get("rotation")
    return rot["angle"] if rot else 0.0


def swatch(col: int, row: int) -> list[float]:
    """UV of the flat swatch block at (col, row); a block is 2x2 texels, 1x1 in UV."""
    return [col + 0.25, row + 0.25, col + 0.75, row + 0.75]


def shell() -> list[dict]:
    parts = lathe(CENTRE, EGG_PROFILE, "shell", sides=16, shade=False)
    for e in parts:
        h0 = e["from"][1] - CY
        k = min(range(len(SLICES)), key=lambda i: abs(SLICES[i][0] - h0))
        v0, v1 = ROW0[k] / 2, (ROW0[k] + ROWS[k]) / 2
        for side in list(e["faces"]):
            face = e["faces"][side]
            if side == "up":
                if k < 3:                          # hidden under the wider slice above
                    del e["faces"][side]
                else:
                    face["uv"] = swatch(k, 12)
            elif side == "down":
                if k > 3:                          # hidden on the wider slice below
                    del e["faces"][side]
                else:
                    face["uv"] = swatch(k, 13)
            else:
                u = u_left(face_phi(side, angle_of(e)))
                face["uv"] = [u, v0, u + 2, v1]
    return parts


def band_ring(k: int, row_a: int, row_b: int) -> list[dict]:
    """The arrow skin just outside slice k over texture rows row_a..row_b (inclusive)."""
    _, ht, _ = slice_of_row(row_a)
    _, _, hb = slice_of_row(row_b)
    r = SLICES[k][2] + 0.05
    slabs = prism((CX, CY + (ht + hb) / 2, CZ), r, ht - hb, "arrows", sides=16, glow=10, shade=False)
    for e in slabs:
        e["faces"].pop("up", None)
        e["faces"].pop("down", None)
        for side, face in e["faces"].items():
            u = u_left(face_phi(side, angle_of(e)))
            face["uv"] = [u, row_a / 2, u + 2, (row_b + 1) / 2]
    return slabs


def arrow_bands() -> list[dict]:
    return band_ring(5, 6, 9) + band_ring(4, 10, 10) + band_ring(3, 16, 17) + band_ring(2, 18, 20)


def seam_ridge() -> list[dict]:
    """The zigzag: per facet, a dark setting bar with a narrower white-hot core laid on
    it, and a glowing stud knotting each corner."""
    parts = []
    half = SEAM_R * math.tan(math.radians(HALF_FACET))
    corner = SEAM_R / math.cos(math.radians(HALF_FACET))
    setting = {"east": ("shell", swatch(0, 14)), "up": ("shell", swatch(1, 14)),
               "down": ("shell", swatch(2, 14)), "north": ("shell", swatch(2, 14)),
               "south": ("shell", swatch(2, 14))}
    core = {"up": ("seam", [0, 0, 16, 4]), "down": ("seam", [0, 12, 16, 16]),
            "north": ("seam", [0, 4, 16, 12]), "south": ("seam", [0, 4, 16, 12])}
    for j in range(16):
        phi_c = 22.5 * j
        ul = u_left(phi_c) * 2                                # px
        h_left, h_right = seam_h(ul), seam_h(ul + 4)
        chord = 2 * half
        length = math.hypot(chord, h_left - h_right)
        yc = CY + (h_left + h_right) / 2
        psi = -math.degrees(math.atan2(h_left - h_right, chord))
        for w, out, ext, tex, faces, glow in ((0.98, 0.17, 0.55, "shell", setting, 0),
                                              (0.52, 0.3, 0.3, "seam", core, 12)):
            bar = box((CX + SEAM_R - 0.1, yc - w / 2, CZ - (length + ext) / 2),
                      (CX + SEAM_R + out, yc + w / 2, CZ + (length + ext) / 2), tex,
                      faces=faces, uv="full", glow=glow, shade=False, skip=("west",))
            turn(bar, psi, "x", (CX + SEAM_R, yc, CZ))
            turn(bar, -phi_c, "y", (CX, 0, CZ))
            parts.append(bar)
        # a gem knotting this stroke to the next one (its left corner): pink on the
        # peaks, mint in the troughs, faceted by colour since the shell is unshaded
        row = 14 if h_left > SEAM_MID else 15
        s = 0.74
        gem = {"east": ("shell", swatch(5, row)), "up": ("shell", swatch(4, row)),
               "south": ("shell", swatch(4, row)), "north": ("shell", swatch(6, row)),
               "down": ("shell", swatch(7, row))}
        stud = box((CX + corner - 0.25, CY + h_left - s / 2, CZ - s / 2),
                   (CX + corner + 0.36, CY + h_left + s / 2, CZ + s / 2), "shell",
                   faces=gem, glow=15, shade=False, skip=("west",))
        turn(stud, 45, "x", (CX + corner, CY + h_left, CZ))
        turn(stud, -(phi_c + HALF_FACET), "y", (CX, 0, CZ))
        parts.append(stud)
    return parts


def build() -> list[dict]:
    return shell() + arrow_bands() + seam_ridge()


def facing(yaw_to: tuple[float, float, float]) -> tuple[float, float, float]:
    """Where the model's +Z axis must point so the lit (inventory-front) side of the shell
    faces direction yaw_to (a horizontal vector)."""
    target = math.degrees(math.atan2(yaw_to[2], yaw_to[0]))
    theta = FRONT_PHI - target               # rotation that carries FRONT_PHI onto target
    return (math.cos(math.radians(90 - theta)), 0.0, math.sin(math.radians(90 - theta)))


def models() -> dict:
    parts = build()
    d = display(KIND, parts, size=SIZE, gui_rotation=GUI_ROTATION, gui_span=15.2)
    centre = (CX, CY + 5.6, CZ)
    # Show the lit side of the shell to the viewer everywhere it is held, dropped or thrown.
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": facing((0, 0, 1))}, centre, "fist", 0.45 * SIZE)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": facing((-0.45, 0, 0.9))}, centre,
                                       (0.48, -0.3, -0.9), 0.62, pose=None)
    yaw = GUI_ROTATION[1]
    d["ground"] = fit(parts, (0, yaw, 0), 8.0, lift=2.0)
    d["fixed"] = fit(parts, (0, yaw, 0), 14.0)
    d["on_shelf"] = fit(parts, (0, yaw, 0), 12.0)
    return {"main": model(parts, d)}
