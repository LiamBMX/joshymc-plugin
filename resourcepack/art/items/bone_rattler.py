"""Bone Rattler: Halloween Limited Edition mace.

A thick femur shaft bound in blackened iron bands, with a midnight-purple leather grip
crossed by pumpkin-orange cord and a double-knuckle bone pommel. The head is a big
grinning skull lit from inside by toxic green light, which shines out of its eyes, nose
and teeth. It wears a crown of curved rib-bone spikes set in an iron circlet, and a
short chain of vertebrae rattles from its jaw hinge.

Every texture is 32 px, so every part has 2 texels per model unit. The skull boxes
sample orthographic "sheets" (front, side, top, back, underside) at their world
position, so painted details line up across boxes that sit at different depths.
"""
from __future__ import annotations

import math

from PIL import Image

from art.kit import (bar, box, canvas, display, display_euler, display_matrix, fill, fit, grain, mirror,
                     mix, model, move, place, rgba, ramp, save, turn)

ID = "bone_rattler"
NAME = "Bone Rattler"
KIND = "mace"

BONE = ramp("#d2c29f", 8, contrast=0.9, hue_shift=0.4)     # 0 near-black mauve .. 7 cream
IRON = ramp("#423c50", 6, contrast=0.75, hue_shift=0.02)   # blackened iron, purple cast
TOXIC = ramp("#6ae83c", 6, contrast=0.85, hue_shift=0.2)   # toxic green light
PURPLE = ramp("#4b2a70", 5, contrast=0.7, hue_shift=0.05)  # midnight purple leather
ORANGE = ramp("#e8761e", 5, contrast=0.7)                  # pumpkin cord
TEAL = ramp("#46c2b0", 5, contrast=0.7)                    # ghostly glints on the iron
VOID = "#170c22"
HOT = "#f4ffd2"

TOP = 29.0  # world y of the top edge (v = 0) of the skull sheets


# --------------------------------------------------------------------------------------
# Painting helpers
# --------------------------------------------------------------------------------------

def _put(img, x, y, c):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), rgba(c))


def _rows(img, x0, y0, rows, pal):
    """Stamp pixel-art rows at (x0, y0); '.' leaves pixels alone."""
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch != ".":
                _put(img, x0 + i, y0 + j, pal[ch])


def _wrect(img, x0, y0, x1, y1, c):
    """Fill the world rectangle [x0, x1] x [y0, y1] on a front/back-projected sheet
    (u = x, v = TOP - y, 2 px per unit)."""
    fill(img, (int(round(2 * x0)), int(round(2 * (TOP - y1))),
               int(round(2 * x1)) - 1, int(round(2 * (TOP - y0))) - 1), c)


def _zrect(img, z0, y0, z1, y1, c):
    """Fill a world rectangle on the side sheet (u = 16 - z, face toward the left)."""
    fill(img, (int(round(2 * (16 - z1))), int(round(2 * (TOP - y1))),
               int(round(2 * (16 - z0))) - 1, int(round(2 * (TOP - y0))) - 1), c)


def _blob(img, cx, cy, rx, ry, bands, box=None):
    """Pillow shading: concentric pixel-crisp ellipses centred on pixel (cx, cy).
    bands = [(radius_fraction, colour), ...] from the outside in; `box` limits it."""
    x0, y0, x1, y1 = box or (0, 0, img.width - 1, img.height - 1)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            d = math.hypot((x + 0.5 - cx) / rx, (y + 0.5 - cy) / ry)
            for frac, colour in bands:
                if d <= frac:
                    img.putpixel((x, y), rgba(colour))


BP = {str(i): BONE[i] for i in range(8)}
BP.update({"v": VOID, "x": VOID})


# --------------------------------------------------------------------------------------
# Skull sheets
# --------------------------------------------------------------------------------------

def _sheet_front() -> tuple[Image.Image, Image.Image]:
    """The skull seen from the front (+Z). Returns (solid, cut): `cut` has the nose
    hole and the gaps between the teeth cleared so the green glow behind shows."""
    img = canvas(32, fill=BONE[5])
    w = _wrect
    # Dome above the circlet: lit from the upper left.
    w(img, 3, 26.5, 13, 29, BONE[6])
    w(img, 4, 27.5, 9, 29, BONE[7])
    w(img, 11, 26.5, 13, 28.5, BONE[5])
    w(img, 3, 26.5, 13, 27, BONE[5])
    w(img, 12, 26.5, 13, 27.5, BONE[4])
    # Temples beside the brow turn away from the light.
    w(img, 1.5, 19, 2.5, 26.5, BONE[4])
    w(img, 13.5, 19, 14.5, 26.5, BONE[3])
    # Forehead between the brow and the circlet, with a shadow under the band.
    w(img, 2.5, 23.5, 13.5, 25.5, BONE[5])
    w(img, 3, 24, 7.5, 24.5, BONE[6])
    w(img, 3.5, 23.5, 6, 24, BONE[6])
    w(img, 11.5, 23.5, 13.5, 25.5, BONE[4])
    w(img, 2.5, 24.5, 13.5, 25.5, BONE[4])
    for x, y in ((7, 9), (12, 10), (24, 9)):                    # pores
        _put(img, x, y, BONE[4])
    _rows(img, 19, 8, [                                  # a hairline crack from the band
        "2...",
        ".2..",
        ".62.",
    ], BP)
    # Brow ridge: two lit arches over the sockets.
    w(img, 2.5, 22.5, 13.5, 23.5, BONE[5])
    w(img, 3, 23, 7, 23.5, BONE[7])
    w(img, 9, 23, 13, 23.5, BONE[6])
    w(img, 7, 23, 9, 23.5, BONE[6])
    w(img, 2.5, 22.5, 3, 23.5, BONE[4])
    w(img, 12.5, 22.5, 13.5, 23.5, BONE[3])
    # Eye sockets (behind the glow plates).
    w(img, 3, 19, 6.5, 22.5, VOID)
    w(img, 9.5, 19, 13, 22.5, VOID)
    # Outer rims, shaded toward the socket.
    w(img, 2, 18.5, 3, 22.5, BONE[5])
    w(img, 2, 18.5, 2.5, 22.5, BONE[6])
    w(img, 13, 18.5, 14, 22.5, BONE[4])
    w(img, 13.5, 18.5, 14, 22.5, BONE[3])
    # Nose column: lit bridge, then the upside-down-heart nose hole.
    w(img, 6.5, 17.5, 9.5, 22.5, BONE[5])
    w(img, 6.5, 20.5, 7.5, 22.5, BONE[6])
    w(img, 9, 20.5, 9.5, 22.5, BONE[4])
    _rows(img, 13, 16, [
        "53xx34",
        "5xxxx4",
        "4xxxx4",
        "4xxxx3",
        "5x77x4",
        "566654",
        "444443",
    ], BP)
    # Cheekbones: lit top edge under each socket.
    for x0, x1 in ((3, 6.5), (9.5, 13)):
        w(img, x0, 18.5, x1, 19, BONE[6])
        w(img, x0, 18, x1, 18.5, BONE[5])
        w(img, x0, 17.5, x1, 18, BONE[4])
    w(img, 3, 18.5, 5, 19, BONE[7])
    w(img, 9.5, 18.5, 11, 19, BONE[7])
    w(img, 12, 17.5, 13, 19, BONE[4])
    # Jaw beside the teeth.
    w(img, 3.5, 13.5, 4.5, 18, BONE[4])
    w(img, 11.5, 13.5, 12.5, 18, BONE[3])
    # Upper teeth: five chunky teeth; the gaps are cut in the "cut" sheet.
    _rows(img, 9, 23, [
        "43x44x44x44x33",
        "76x76x76x76x65",
        "65x65x65x65x54",
    ], BP)
    _rows(img, 9, 26, [                                  # lower teeth
        "76x76x76x76x65",
        "54x54x54x54x43",
    ], BP)
    # Chin.
    w(img, 4.5, 13.5, 11.5, 15, BONE[5])
    w(img, 6, 14, 10, 14.5, BONE[6])
    w(img, 4.5, 13.5, 11.5, 14, BONE[3])
    w(img, 10.5, 13.5, 11.5, 15, BONE[4])

    cut = img.copy()
    src, dst = img.load(), cut.load()
    void = rgba(VOID)
    for y in range(16, 28):
        for x in range(9, 23):
            if src[x, y] == void:
                dst[x, y] = (0, 0, 0, 0)
    return img, cut


def _sheet_side() -> Image.Image:
    """The skull seen from its side (u = 16 - z, face toward the left edge). The flat
    cranium walls get pillow shading so they read as a rounded skull."""
    img = canvas(32, fill=BONE[5])
    z = _zrect
    # Cranium: bright crown, a round highlight on the parietal, darker base and back.
    z(img, 2, 25, 12.5, 29, BONE[6])
    z(img, 6, 26.5, 11, 29, BONE[7])
    z(img, 2, 19.5, 12.5, 24.5, BONE[4])
    _blob(img, 15, 12.5, 10.5, 6.5, [(1.0, BONE[5]), (0.62, BONE[6]), (0.3, BONE[7])], (6, 8, 27, 18))
    z(img, 2, 19.5, 3, 23.5, BONE[3])
    z(img, 2, 19.5, 12.5, 20, BONE[3])
    z(img, 2, 24.5, 12.5, 25, BONE[4])                    # shadow under the circlet
    for zz, yy in ((9.5, 21), (7, 22.5), (11, 23.5), (5, 24)):   # a few pores
        z(img, zz, yy, zz + 0.5, yy + 0.5, BONE[4])
    # squamosal suture: a faint wavy seam over the ear
    for zz, yy in ((9, 22), (8.5, 22.5), (8, 22.5), (7.5, 23), (6.5, 23), (6, 23), (5.5, 22.5), (5, 22.5)):
        z(img, zz, yy, zz + 0.5, yy + 0.5, BONE[4])
    # ear hole: dark canal, shadowed top, lit lower lip
    z(img, 4, 20.5, 5.5, 22, VOID)
    z(img, 4, 22, 5.5, 22.5, BONE[3])
    z(img, 5.5, 20.5, 6, 22.5, BONE[4])
    z(img, 4, 20, 5.5, 20.5, BONE[7])
    # face edge: brow, rims, forehead plate
    z(img, 12, 17.5, 14.5, 26.5, BONE[5])
    z(img, 12.5, 23, 14.5, 23.5, BONE[7])
    z(img, 12.5, 22.5, 14.5, 23, BONE[5])
    z(img, 12, 18.5, 14.5, 22.5, BONE[4])
    # cheekbone arch running back to the ear
    z(img, 8, 19.5, 13, 20, BONE[7])
    z(img, 8, 19, 13, 19.5, BONE[5])
    z(img, 8, 18.5, 13, 19, BONE[3])
    # teeth, jaw body and ramus
    z(img, 11, 15, 13.5, 17.5, BONE[5])
    z(img, 7, 13, 13.5, 15.5, BONE[5])
    z(img, 7, 15, 13.5, 15.5, BONE[6])
    z(img, 7, 13, 13.5, 13.5, BONE[3])
    z(img, 7, 15.5, 9.5, 18.5, BONE[5])
    z(img, 9, 15.5, 9.5, 18.5, BONE[6])
    z(img, 7, 15.5, 7.5, 18.5, BONE[4])
    return img


def _sheet_top() -> Image.Image:
    """The skull from above (u = x, v = z): a lit dome with fine sutures."""
    img = canvas(32, fill=BONE[6])
    fill(img, (2, 2, 29, 7), BONE[5])
    fill(img, (2, 2, 6, 25), BONE[5])
    fill(img, (25, 2, 29, 25), BONE[5])
    fill(img, (10, 10, 18, 17), BONE[7])
    for x, y in ((16, 11), (15, 12), (16, 13), (16, 14), (15, 15), (16, 16), (17, 17), (16, 18),
                 (16, 19), (15, 20), (16, 21)):
        _put(img, x, y, BONE[4])
    for x, y in ((8, 22), (9, 21), (11, 22), (13, 21), (14, 22), (16, 22), (18, 22), (19, 21),
                 (21, 22), (23, 21)):
        _put(img, x, y, BONE[4])
    for x, y in ((10, 9), (12, 10), (14, 10), (18, 10), (20, 10), (22, 9)):
        _put(img, x, y, BONE[4])
    fill(img, (4, 25, 27, 28), BONE[6])                   # forehead and brow tops
    fill(img, (5, 27, 26, 27), BONE[7])
    # the forehead crack runs on over the crown: dark seam, lit lip beside it
    for x, y in ((21, 23), (21, 22), (22, 21), (22, 20), (23, 19), (23, 18), (22, 17)):
        _put(img, x, y, BONE[3])
        _put(img, x + 1, y, BONE[7])
    for x, y in ((9, 14), (19, 11), (12, 19)):             # age spots
        _put(img, x, y, BONE[5])
    return img


def _sheet_back() -> Image.Image:
    """The back of the skull (u = 16 - x): a pillow-shaded occipital bulge."""
    img = canvas(32, fill=BONE[4])
    w = _wrect
    w(img, 1.5, 25, 14.5, 29, BONE[6])
    w(img, 5, 26.5, 11, 29, BONE[7])
    _blob(img, 16, 12, 10, 6.5, [(1.0, BONE[5]), (0.6, BONE[6]), (0.28, BONE[7])], (3, 8, 28, 18))
    w(img, 1.5, 19.5, 14.5, 20, BONE[3])
    w(img, 1.5, 24.5, 14.5, 25, BONE[4])                  # shadow under the circlet
    # lambdoid suture: a faint zigzag high on the back
    for x, y in ((8, 11), (9, 12), (10, 11), (11, 12), (12, 13), (20, 11), (21, 12), (22, 11),
                 (23, 12), (24, 13)):
        _put(img, x, y, BONE[4])
    return img


def _sheet_under() -> Image.Image:
    img = canvas(32, fill=BONE[4])
    fill(img, (0, 0, 31, 3), BONE[3])
    fill(img, (8, 10, 23, 20), BONE[3])
    return img


# --------------------------------------------------------------------------------------
# Other materials
# --------------------------------------------------------------------------------------

def _femur() -> Image.Image:
    """Bone that wraps round the femur columns: long grain, pores, one hairline crack."""
    img = canvas(32, fill=BONE[5])
    grain(img, (0, 0, 31, 31), [BONE[6], BONE[4]], axis="y", seed=11, min_len=3, max_len=8, density=0.3)
    for x, y in ((5, 4), (17, 9), (24, 3), (11, 14), (27, 12), (3, 19), (20, 21), (13, 26)):
        _put(img, x, y, BONE[3])
        _put(img, x, y + 1, BONE[7])
    for x, y in ((8, 10), (9, 11), (9, 12), (10, 13)):
        _put(img, x, y, BONE[3])
    return img


def _knob() -> Image.Image:
    """A round, sphere-shaded knuckle face (cut out to a disc) for the pommel lobes."""
    img = canvas(32)
    _rows(img, 0, 0, [
        "..55544..",
        ".5676654.",
        "567776554",
        "567766554",
        "566665543",
        "455555443",
        "445544433",
        ".4444333.",
        "..33332..",
    ], BP)
    return img


def _wrap() -> Image.Image:
    """Purple leather wound in a helix, crossed by pumpkin-orange cord. 28 px around
    (8 faces x 3.5 px) so both patterns close on themselves."""
    img = canvas(32, fill=PURPLE[2])
    for y in range(32):
        for x in range(32):
            t = (x + 2 * y) % 7
            c = PURPLE[1] if t == 0 else PURPLE[3] if t == 1 else PURPLE[2]
            img.putpixel((x, y), rgba(c))
    for y in range(32):
        for x in range(32):
            a = (x - y) % 14
            b = (x + y) % 14
            if a == 0 or b == 0:
                _put(img, x, y, ORANGE[3] if a == 0 and b == 0 else ORANGE[2])
            elif a == 1:
                _put(img, x, y, ORANGE[1])
            elif b == 13:
                _put(img, x, y, ORANGE[1])
    return img


def _band() -> Image.Image:
    """Blackened iron. Rows 0-2: a 1.5-unit riveted band (every column of 4 px gets a
    domed rivet, so each face of a band shows one). Rows 4-7: the collar cup. Rows 8+:
    hammered plain iron for the chain links."""
    img = canvas(32, fill=IRON[2])
    for x in range(32):
        k = x % 4
        _put(img, x, 0, IRON[5] if k == 1 else IRON[4])
        _put(img, x, 1, (IRON[3], IRON[2], TEAL[4], IRON[2])[k])
        _put(img, x, 2, (IRON[1], IRON[1], IRON[0], IRON[0])[k])
    for x in range(32):
        k = x % 6
        _put(img, x, 4, IRON[5] if k == 2 else IRON[4])
        _put(img, x, 5, (IRON[3], IRON[3], TEAL[4], IRON[4], IRON[3], IRON[3])[k])
        _put(img, x, 6, (IRON[2], IRON[2], IRON[3], IRON[0], IRON[2], IRON[1])[k])
        _put(img, x, 7, IRON[1])
    fill(img, (0, 8, 31, 31), IRON[2])
    grain(img, (0, 8, 31, 31), [IRON[1], IRON[3]], axis="x", seed=5, density=0.3)
    for x in range(0, 32, 5):
        _put(img, x, 9 + x % 3, IRON[4])
    return img


def _circlet() -> Image.Image:
    """The crown band (2 units tall): a lit bevel, domed rivets with teal glints,
    engraved notches between them, and a dark lower edge."""
    img = canvas(32, fill=IRON[2])
    for x in range(32):
        k = x % 6
        _put(img, x, 0, IRON[5] if k in (1, 4) else IRON[4])
        _put(img, x, 1, (IRON[3], TEAL[4], IRON[4], IRON[3], IRON[2], IRON[3])[k])
        _put(img, x, 2, (IRON[2], IRON[3], IRON[0], IRON[2], IRON[1], IRON[2])[k])
        _put(img, x, 3, IRON[1] if k != 4 else IRON[0])
    return img


def _rib() -> Image.Image:
    """Rib bone for the crown spikes. Each spike segment samples an 8-row band
    (segment 0 on top); the bands darken toward the sharp, age-stained tip."""
    img = canvas(32, fill=BONE[5])
    bands = [(BONE[7], BONE[6], BONE[5], BONE[4]), (BONE[7], BONE[6], BONE[5], BONE[4]),
             (BONE[6], BONE[5], BONE[4], BONE[3]), (BONE[5], BONE[4], BONE[3], BONE[3])]
    for i, cols in enumerate(bands):
        for j, c in enumerate(cols):
            fill(img, (j, 8 * i, j, 8 * i + 7), c)
        fill(img, (4, 8 * i, 31, 8 * i + 7), cols[2])
    for x, y in ((1, 3), (2, 12), (1, 19)):                # a few nicks
        _put(img, x, y, BONE[4])
    return img


def _vert() -> Image.Image:
    """Vertebra bone: lit top rim, pinched waist, lower rim."""
    img = canvas(32, fill=BONE[5])
    fill(img, (0, 0, 31, 0), BONE[7])
    fill(img, (0, 1, 31, 1), BONE[4])
    fill(img, (0, 2, 31, 2), BONE[6])
    fill(img, (0, 3, 31, 3), BONE[3])
    for x in range(1, 32, 4):
        _put(img, x, 1, BONE[3])
    return img


def _eye() -> Image.Image:
    """Glowing socket: an orb of toxic green with a white-hot core."""
    img = canvas(32, fill=VOID)
    pal = {"v": VOID, "0": TOXIC[0], "1": TOXIC[1], "2": TOXIC[2], "3": TOXIC[3],
           "4": TOXIC[4], "5": TOXIC[5], "h": HOT}
    _rows(img, 0, 0, [
        "vv000vv",
        "v01210v",
        "01h4310",
        "0145410",
        "0134310",
        "v01210v",
        "vv000vv",
    ], pal)
    return img


def _socket() -> Image.Image:
    """A frame that rounds off the square socket (its centre is cut out)."""
    img = canvas(32)
    _rows(img, 0, 0, [
        "33...33",
        "3.....3",
        ".......",
        ".......",
        ".......",
        "1.....1",
        "11...11",
    ], {"1": mix(VOID, BONE[1], 0.5), "3": BONE[3]})
    return img


def _bloom() -> Image.Image:
    """A soft translucent halo of toxic light that spills out of each socket."""
    img = canvas(32)
    base = rgba(TOXIC[4])
    for y in range(11):
        for x in range(11):
            d = math.hypot(x + 0.5 - 5.5, y + 0.5 - 5.5) / 5.5
            if d < 1:
                a = int(round(120 * (1 - d) ** 1.6 / 8)) * 8
                if a >= 32:
                    img.putpixel((x, y), (base[0], base[1], base[2], a))
    return img


def _maw() -> Image.Image:
    """The green glow inside the mouth and nose."""
    img = canvas(32, fill=TOXIC[1])
    fill(img, (0, 1, 31, 5), TOXIC[2])
    fill(img, (0, 2, 31, 3), TOXIC[3])
    return img


def textures() -> None:
    front, cut = _sheet_front()
    save(front, "sk_front")
    save(cut, "sk_cut")
    save(_sheet_side(), "sk_side")
    save(_sheet_top(), "sk_top")
    save(_sheet_back(), "sk_back")
    save(_sheet_under(), "sk_under")
    save(canvas(32, fill=mix(VOID, BONE[1], 0.4)), "sk_wall")
    save(_femur(), "femur")
    save(canvas(32, fill=BONE[4]), "cap_bone")
    save(canvas(32, fill=IRON[2]), "cap_iron")
    save(_knob(), "knob")
    save(_wrap(), "wrap")
    save(_band(), "band")
    save(_circlet(), "circlet")
    save(_rib(), "rib")
    save(_vert(), "vert")
    save(_eye(), "eye")
    save(_socket(), "socket")
    save(_maw(), "maw")
    save(_bloom(), "bloom")


# --------------------------------------------------------------------------------------
# Geometry helpers
# --------------------------------------------------------------------------------------

SHEET = {"south": "sk_front", "north": "sk_back", "east": "sk_side", "west": "sk_side",
         "up": "sk_top", "down": "sk_under"}


def _proj_uv(side, frm, to):
    (x0, y0, z0), (x1, y1, z1) = frm, to
    if side == "south":
        return [x0, TOP - y1, x1, TOP - y0]
    if side == "north":
        return [16 - x1, TOP - y1, 16 - x0, TOP - y0]
    if side == "east":
        return [16 - z1, TOP - y1, 16 - z0, TOP - y0]
    if side == "west":
        return [16 - z0, TOP - y1, 16 - z1, TOP - y0]
    if side == "up":
        return [x0, z0, x1, z1]
    return [x0, 16 - z1, x1, 16 - z0]


def pbox(frm, to, tex=None, skip=(), glow=0):
    """A skull box whose faces sample the skull sheets at their world position."""
    names = dict(SHEET, **(tex or {}))
    faces = {side: (names[side], _proj_uv(side, frm, to)) for side in SHEET if side not in skip}
    return box(frm, to, names["south"], faces=faces, skip=skip, glow=glow)


def cyl(cx, cz, y0, y1, r, tex, u_step=0.0, u0=0.0, v0=0.0, cap=None, caps=("up", "down"),
        glow=0, seam=4):
    """An 8-sided column (4 crossed slabs). Its 8 side faces take consecutive strips
    of `tex` (u_step apart), so a painted pattern wraps continuously around it; the
    seam sits on the -X side."""
    half = r * math.tan(math.pi / 8)
    w, h = 2 * half, y1 - y0
    cap = cap or tex
    skip_caps = tuple(s for s in ("up", "down") if s not in caps)

    def uv(k):
        u = u0 + ((k + seam) % 8) * u_step
        return (tex, [round(u, 4), v0, round(u + w, 4), v0 + h])

    capf = {s: cap for s in caps}
    a = box((cx - r, y0, cz - half), (cx + r, y1, cz + half), tex, skip=("north", "south") + skip_caps,
            faces={"east": uv(0), "west": uv(4), **capf}, glow=glow)
    b = box((cx - half, y0, cz - r), (cx + half, y1, cz + r), tex, skip=("east", "west") + skip_caps,
            faces={"north": uv(2), "south": uv(6), **capf}, glow=glow)
    c = box((cx - r, y0, cz - half), (cx + r, y1, cz + half), tex, skip=("north", "south") + skip_caps,
            faces={"east": uv(1), "west": uv(5), **capf}, glow=glow)
    d = box((cx - half, y0, cz - r), (cx + half, y1, cz + r), tex, skip=("east", "west") + skip_caps,
            faces={"north": uv(3), "south": uv(7), **capf}, glow=glow)
    turn([c, d], 45, "y", (cx, y0, cz))
    return [a, b, c, d]


def zcyl(cx, cy, z0, z1, r, tex, cap):
    """An 8-sided column lying along Z (for the pommel knuckles)."""
    parts = cyl(cx, 0.0, -(z1 - z0) / 2, (z1 - z0) / 2, r, tex, u_step=0.0, cap=cap)
    turn(parts, 90, "x", (cx, 0, 0))
    return move(parts, 0, cy, (z0 + z1) / 2)


def _bez(p0, p1, p2, t):
    return tuple((1 - t) ** 2 * p0[i] + 2 * (1 - t) * t * p1[i] + t * t * p2[i] for i in range(3))


def rib(base, ctrl, tip, w0, w1, d0, d1, n=4):
    """A curved, tapering rib-bone spike along a quadratic curve."""
    pts = [_bez(base, ctrl, tip, i / n) for i in range(n + 1)]
    parts = []
    for i in range(n):
        t = i / max(1, n - 1)
        p0, p1 = pts[i], pts[i + 1]
        d = [p1[k] - p0[k] for k in range(3)]
        ext = 0.2 if i < n - 1 else 0.0
        q0 = [p0[k] - d[k] * 0.1 for k in range(3)]
        q1 = [p1[k] + d[k] * ext for k in range(3)]
        parts.append(bar(q0, q1, w0 + (w1 - w0) * t, d0 + (d1 - d0) * t, "rib", offset=(0, 4 * i)))
    return parts


def vertebra(cx, cy, cz, s=1.0):
    """One vertebra of the rattling chain: body, side wings and a back spine."""
    return [
        box((cx - 1.1 * s, cy - 0.7 * s, cz - 0.85 * s), (cx + 1.1 * s, cy + 0.7 * s, cz + 0.85 * s), "vert"),
        box((cx - 0.8 * s, cy - 0.62 * s, cz - 1.1 * s), (cx + 0.8 * s, cy + 0.62 * s, cz + 1.1 * s), "vert"),
        box((cx - 2.1 * s, cy - 0.3 * s, cz - 0.9 * s), (cx + 2.1 * s, cy + 0.35 * s, cz - 0.1 * s), "vert",
            offset=(0, 1)),
        bar((cx, cy, cz - 0.8 * s), (cx, cy - 1.0 * s, cz - 2.3 * s), 0.7 * s, 0.6 * s, "vert", offset=(0, 1)),
    ]


# --------------------------------------------------------------------------------------
# Parts
# --------------------------------------------------------------------------------------

def handle(compact: bool = False) -> list[dict]:
    """Pommel knuckles, banded grip, upper femur and the collar. The compact version
    (inventory icon) has a shorter grip and femur so the skull can be drawn bigger."""
    lift = 2.0 if compact else 0.0   # the pommel moves up by this much
    drop = 2.0 if compact else 0.0   # the collar (and the head) move down by this much
    pommel = []
    # The femur's two knuckles, with round faces front and back.
    for cx, zf, zb in ((6.1, 10.2, 5.7), (9.9, 10.25, 5.65)):
        pommel += zcyl(cx, -4.2, 5.9, 10.1, 2.1, "femur", "cap_bone")
        pommel.append(box((cx - 2.25, -6.45, zf), (cx + 2.25, -1.95, zf), "knob",
                          faces={"south": ("knob", [0, 0, 4.5, 4.5])}, skip=("north", "east", "west", "up", "down")))
        pommel.append(box((cx - 2.25, -6.45, zb), (cx + 2.25, -1.95, zb), "knob",
                          faces={"north": ("knob", [0, 0, 4.5, 4.5])}, skip=("south", "east", "west", "up", "down")))
    pommel += cyl(8, 8, -3.2, -1.6, 1.95, "femur", u_step=1.6, v0=2, cap="cap_bone", caps=("down",))
    pommel += cyl(8, 8, -2.1, -0.6, 2.45, "band", cap="cap_iron")
    parts = move(pommel, 0, lift, 0)
    # Leather grip between two iron bands.
    parts += cyl(8, 8, -0.6 + lift, 6.4, 2.113, "wrap", u_step=1.75, caps=())
    parts += cyl(8, 8, 6.4, 7.9, 2.45, "band", cap="cap_iron")
    # Upper femur with a thin band, then the iron collar the skull sits in.
    parts += cyl(8, 8, 7.9, 13.0 - drop, 2.0, "femur", u_step=1.657, v0=4, cap="cap_bone", caps=())
    if not compact:
        parts += cyl(8, 8, 10.0, 11.0, 2.25, "band", cap="cap_iron")
    parts += cyl(8, 8, 12.6 - drop, 14.1 - drop, 2.6, "band", cap="cap_iron")
    parts += cyl(8, 8, 14.1 - drop, 16.1 - drop, 3.2, "band", v0=2, cap="cap_iron")
    return parts


def cranium() -> list[dict]:
    """Stacked, chamfered layers approximating a rounded dome (front face at z = 12.5)."""
    return [
        pbox((3, 19.5, 4.2), (13, 20.5, 12.5)),
        pbox((4, 19.5, 3.4), (12, 20.5, 12.5)),
        pbox((1.8, 20.5, 4.2), (14.2, 25, 12.5)),
        pbox((2.4, 20.5, 3.0), (13.6, 25, 12.5)),
        pbox((3.4, 20.5, 2.3), (12.6, 25, 12.5)),
        pbox((2.2, 25, 4.0), (13.8, 26.5, 12.5)),
        pbox((3.4, 25, 2.6), (12.6, 26.5, 12.5)),
        pbox((3, 26.5, 4.4), (13, 27.5, 12.5)),
        pbox((4.2, 26.5, 3.3), (11.8, 27.5, 12.5)),
        pbox((4, 27.5, 5), (12, 28.3, 11.8)),
        pbox((5, 27.5, 4.2), (11, 28.3, 11.8)),
        pbox((5.4, 28.3, 5.6), (10.6, 29, 10.8)),
    ]


def face() -> list[dict]:
    parts = [
        # forehead, below and above the circlet
        pbox((2.5, 23.5, 12.5), (13.5, 25.5, 13.5)),
        pbox((3.2, 26.5, 12.5), (12.8, 27.5, 13.1)),
        # brow ridge (overhangs the sockets)
        pbox((2.5, 22.5, 12.5), (13.5, 23.5, 14), tex={"down": "sk_wall"}),
        # outer socket rims and the cheekbone arches running back to the ears
        pbox((2, 18.5, 12), (3, 22.5, 13.5), tex={"east": "sk_wall"}),
        pbox((13, 18.5, 12), (14, 22.5, 13.5), tex={"west": "sk_wall"}),
        pbox((1.5, 18.5, 8), (2.2, 20, 12.6)),
        pbox((13.8, 18.5, 8), (14.5, 20, 12.6)),
        # nose column with the heart-shaped nose hole cut out
        pbox((6.5, 17.5, 12.5), (9.5, 22.5, 13.5), tex={"south": "sk_cut", "east": "sk_wall", "west": "sk_wall"}),
        # cheekbones under the sockets
        pbox((3, 17.5, 12.5), (6.5, 19, 13.5), tex={"up": "sk_wall"}),
        pbox((9.5, 17.5, 12.5), (13, 19, 13.5), tex={"up": "sk_wall"}),
        # upper teeth
        pbox((4.5, 16, 12), (11.5, 17.5, 13), tex={"south": "sk_cut"}),
        # neck vertebra under the cranium
        box((6.5, 15.8, 5.5), (9.5, 19.5, 10), "vert"),
    ]
    # glowing insides: eye orbs (with rounded socket frames), nose, mouth
    for x0 in (3, 9.5):
        parts.append(box((x0, 19, 12.5), (x0 + 3.5, 22.5, 12.7), "eye",
                         faces={"south": ("eye", [0, 0, 3.5, 3.5])}, skip=("north",), glow=15))
        parts.append(box((x0, 19, 12.8), (x0 + 3.5, 22.5, 12.8), "socket",
                         faces={"south": ("socket", [0, 0, 3.5, 3.5])},
                         skip=("north", "east", "west", "up", "down")))
        parts.append(box((x0 - 1.0, 18.0, 13.6), (x0 + 4.5, 23.5, 13.6), "bloom",
                         faces={"south": ("bloom", [0, 0, 5.5, 5.5])},
                         skip=("north", "east", "west", "up", "down"), glow=15))
    parts.append(box((7, 17.6, 12.6), (9, 21.0, 12.9), "maw", skip=("north",), glow=11))
    parts.append(box((4.6, 14.5, 11.3), (11.4, 17.5, 11.7), "maw", glow=11))
    # lower jaw: chin, teeth and an L-shaped mandible each side, hinged open a little
    jaw = [
        pbox((4.5, 13.5, 11), (11.5, 15, 13)),
        pbox((4.5, 15, 11.5), (11.5, 16, 12.5), tex={"south": "sk_cut"}),
        pbox((3.5, 13.5, 8.5), (4.5, 15.5, 12.5)),
        pbox((11.5, 13.5, 8.5), (12.5, 15.5, 12.5)),
        pbox((3.5, 15.5, 7.5), (4.5, 18.5, 9.5)),
        pbox((11.5, 15.5, 7.5), (12.5, 18.5, 9.5)),
    ]
    turn(jaw, 15, "x", (8, 18, 8))
    return parts + jaw


def circlet() -> list[dict]:
    """The iron band round the crown, chamfered at the back corners."""
    y0, y1 = 24.75, 26.75
    parts = [
        box((1.5, y0, 13.5), (14.5, y1, 14.2), "circlet"),
        box((1.5, y0, 4.0), (2.2, y1, 13.5), "circlet"),
        box((13.8, y0, 4.0), (14.5, y1, 13.5), "circlet"),
        box((3.3, y0, 1.9), (12.7, y1, 2.6), "circlet"),
    ]
    corner = bar((1.85, (y0 + y1) / 2, 4.2), (3.6, (y0 + y1) / 2, 2.25), 0.7, y1 - y0 - 0.06, "circlet",
                 roll=90)
    parts += [corner] + mirror(corner, "x", 8)
    return parts


def crown(spread: float = 1.0, back: bool = True) -> list[dict]:
    """Curved rib-bone spikes set in the circlet. `spread` scales how far the side
    horns sweep outward; the inventory model uses a tighter crown without the back
    pair, which only adds clutter behind the skull at icon size."""
    parts = rib((8, 26.2, 14.0), (8, 29.8, 15.2), (8, 31.4, 12.8), 1.9, 0.6, 1.3, 0.5)
    hx = 1.9 - 4.5 * spread
    side = rib((4.4, 26.2, 14.0), (3.6, 29.2, 15.2), (2.6, 30.8, 13.6), 1.6, 0.5, 1.1, 0.45)
    side += rib((1.9, 26.0, 9.2), (hx + 0.9, 26.4, 9.8), (hx + 0.2, 29.8, 9.0), 1.8, 0.55, 1.2, 0.5)
    if back:
        side += rib((2.3, 26.0, 4.0), (0.6, 28.0, 2.2), (1.6, 30.6, 0.6), 1.5, 0.5, 1.0, 0.45)
    parts += side + mirror(side, "x", 8)
    return parts


def chain() -> list[dict]:
    """Vertebrae hanging from an iron eye on the jaw's +X side (the side that faces the
    ground when the mace is held, and the lower side of the inventory icon). Each bone
    is twisted a little differently so the chain looks mid-rattle."""
    parts = [box((12.5, 16.1, 9.2), (13.4, 17.1, 10.2), "band", offset=(0, 8))]
    links = [(13.6, 15.4), (13.8, 12.9), (13.8, 10.5), (13.5, 8.3)]
    verts = [(13.7, 14.2, 1.0, 0, 0), (13.9, 11.7, 0.92, 14, -6), (13.8, 9.4, 0.84, -12, 5),
             (13.5, 7.3, 0.76, 18, -8)]
    for (x, y), (vx, vy, s, twist, tilt) in zip(links, verts):
        parts.append(box((x - 0.35, y - 0.6, 9.35), (x + 0.35, y + 0.6, 10.05), "band", offset=(0, 8)))
        bone = vertebra(vx, vy, 9.7, s)
        turn(bone, origin=(vx, vy, 9.7), y=twist, z=tilt)
        parts += bone
    # the tail: a small tapering bone tip
    parts.append(bar((13.45, 6.7, 9.75), (13.2, 5.5, 9.9), 0.7, 0.7, "rib", offset=(0, 8)))
    parts.append(bar((13.2, 5.7, 9.9), (13.0, 4.8, 10.0), 0.45, 0.45, "rib", offset=(0, 12)))
    return parts


# --------------------------------------------------------------------------------------
# Model and display
# --------------------------------------------------------------------------------------

def _mul(a, b):
    return tuple(tuple(sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)) for i in range(3))


def spin(d: dict, degrees: float) -> dict:
    """The same display transform with the model first spun about its shaft (the grip
    sits on the shaft, so it stays in the fist)."""
    out = dict(d)
    out["rotation"] = display_euler(_mul(display_matrix(d["rotation"]), display_matrix((0, degrees, 0))))
    return out


GRIP = (8, 2.6, 8)


def grow(elements: list[dict], k: float, pivot) -> list[dict]:
    """Scale elements (and their rotation origins) by k about a pivot point."""
    for e in elements:
        for key in ("from", "to"):
            e[key] = [round(pivot[i] + (e[key][i] - pivot[i]) * k, 4) for i in range(3)]
        if "rotation" in e:
            o = e["rotation"]["origin"]
            e["rotation"]["origin"] = [round(pivot[i] + (o[i] - pivot[i]) * k, 4) for i in range(3)]
    return elements


HEAD_GROW = 1.15
GUI_ROTATION = (0, 0, -45)


def build(compact: bool = False) -> list[dict]:
    if not compact:
        return handle() + cranium() + face() + circlet() + crown() + chain()
    # Inventory icon: shorter handle, tighter crown and a slightly larger head, so the
    # skull and its green eyes read at small GUI scales.
    head = cranium() + face() + circlet() + crown(spread=0.45, back=False) + chain()
    grow(head, HEAD_GROW, (8, 14.1, 8))
    return move(handle(compact=True) + move(head, 0, -2.0, 0), 0, -2.0, 0)


def models() -> dict:
    parts = build()
    d = display("mace", parts, grip=GRIP, size=0.66)
    # Third person: face the skull outward (the player's right) so onlookers see it grin,
    # with the vertebrae hanging below the shaft.
    d["thirdperson_righthand"] = spin(d["thirdperson_righthand"], 180)
    # First person: held low on the right, the skull turned to grin at the player,
    # its crown well clear of the crosshair.
    d["firstperson_righthand"] = place({"y": (-0.3, 0.9, -0.3), "z": (-0.6, 0.0, 0.8)}, GRIP,
                                       (0.58, -0.7, -0.9), 0.40, pose=None)
    # Inventory: the compact build (shorter handle, tighter crown, larger head).
    icon = build(compact=True)
    gd = dict(d)
    gd["gui"] = fit(icon, GUI_ROTATION, 16.0)
    return {"main": model(parts, d), "gui": model(icon, gd)}

