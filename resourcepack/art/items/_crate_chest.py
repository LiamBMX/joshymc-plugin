"""Shared skeleton and painters for the placed crate chests (issue #1389).

Never shipped itself (the name starts with "_"). The Cabin, Homestead and Campfire crate
modules import it so the three crates share one footprint, one lid seam and one set of
hardware, and differ only in their materials and the crown that sits on the lid.

The plugin draws these models with an ItemDisplay over the real crate block, so the
model must completely hide a vanilla chest (x/z 1..15, y 0..14, latch out to z = 16 at
x 7..9, y 7..11). Block space: y = 0 is the floor, the front (lock) faces +Z.

    body   x/z 0.6 .. 15.4, y 0 .. SEAM
    lid    x/z 0.35 .. 15.65, y SEAM .. LID_TOP (the crown starts at LID_TOP)
"""
from __future__ import annotations

import math
import random

from PIL import Image

from art.kit import box, canvas, mix, prism, rgba, shade

KIND = "furniture"
COUNTERPART = "block/chest"

B0, B1 = 0.6, 15.4           # body footprint
L0, L1 = 0.35, 15.65         # lid footprint (a little overhang all round)
SEAM = 9.4                   # where the lid meets the body
LID_TOP = 14.4               # flat top of the lid; crowns build on it
FRONT = B1                   # body front face (+Z)


# --------------------------------------------------------------------------------------
# Pixel helpers
# --------------------------------------------------------------------------------------

def put(img: Image.Image, x: int, y: int, colour) -> None:
    w, h = img.size
    if 0 <= x < w and 0 <= y < h:
        img.putpixel((x, y), rgba(colour))


def ramp_of(colours) -> list[str]:
    return list(colours)


def planks(pal, size: int = 32, rows: int = 4, seed: int = 1, vertical: bool = False, knots: int = 3,
           seam_dark: int = 0, highlight: int = None) -> Image.Image:
    """Boards with grain streaks, a dark seam and a lit top edge on every board.
    pal is dark -> light (at least 6 tones)."""
    img = canvas(size)
    rng = random.Random(seed)
    step = size // rows
    hi = len(pal) - 2 if highlight is None else highlight
    mid = len(pal) // 2
    for a in range(size):            # along the boards
        for b in range(size):        # across the boards
            k = b % step
            board = b // step
            tone = mid + ((board * 5 + 1) % 3) - 1
            if k == step - 1:
                c = pal[seam_dark]
            elif k == 0:
                c = pal[hi]
            else:
                r = rng.random()
                c = pal[tone] if r > 0.35 else pal[max(1, tone - 1)] if r > 0.12 else pal[min(hi, tone + 1)]
            x, y = (b, a) if vertical else (a, b)
            put(img, x, y, c)
    # grain streaks
    for board in range(rows):
        for _ in range(3):
            b = board * step + rng.randrange(1, step - 1)
            a = rng.randrange(size)
            for i in range(rng.randint(4, 10)):
                x, y = (b, (a + i) % size) if vertical else ((a + i) % size, b)
                put(img, x, y, pal[max(1, mid - 2)])
    # knots
    for _ in range(knots):
        board = rng.randrange(rows)
        b = board * step + step // 2
        a = rng.randrange(2, size - 2)
        for (da, db, c) in ((0, 0, pal[1]), (1, 0, pal[2]), (-1, 0, pal[2]), (0, -1, pal[mid + 1])):
            x, y = (b + db, a + da) if vertical else (a + da, b + db)
            put(img, x, y, c)
    return img


def metal(pal, size: int = 16, rivets=(3, 12), rivet_row: int = None) -> Image.Image:
    """A strap of metal: lit top rows, dark bottom rows, two domed rivets."""
    img = canvas(size)
    n = len(pal)
    for y in range(size):
        for x in range(size):
            if y == 0:
                c = pal[n - 1]
            elif y in (1, 2):
                c = pal[n - 2]
            elif y >= size - 2:
                c = pal[1]
            elif y == size - 3:
                c = pal[2]
            else:
                c = pal[n - 3] if (x * 3 + y) % 11 else pal[n - 4]
            put(img, x, y, c)
    ry = size // 2 if rivet_row is None else rivet_row
    for rx in rivets:
        put(img, rx, ry - 1, pal[n - 1])
        put(img, rx + 1, ry - 1, pal[n - 2])
        put(img, rx, ry, pal[n - 2])
        put(img, rx + 1, ry, pal[2])
        put(img, rx + 1, ry + 1, pal[0])
    return img


def plain_metal(pal, size: int = 16) -> Image.Image:
    img = canvas(size)
    n = len(pal)
    for y in range(size):
        for x in range(size):
            c = pal[n - 3]
            if (x + 2 * y) % 9 == 0:
                c = pal[n - 2]
            elif (x * 5 + y) % 13 == 0:
                c = pal[n - 4]
            put(img, x, y, c)
    return img


def lock_plate(pal, hole="#120a08", size: int = 16) -> Image.Image:
    """A bevelled lock plate with four rivets and a keyhole cut through it."""
    img = canvas(size)
    n = len(pal)
    for y in range(size):
        for x in range(size):
            if x <= 1 or y <= 1:
                c = pal[n - 1] if (x == 0 or y == 0) else pal[n - 2]
            elif x >= size - 2 or y >= size - 2:
                c = pal[1] if (x == size - 1 or y == size - 1) else pal[2]
            else:
                c = pal[n - 3] if (x + y) % 7 else pal[n - 4]
            put(img, x, y, c)
    for rx, ry in ((3, 3), (11, 3), (3, 11), (11, 11)):
        put(img, rx, ry, pal[n - 1])
        put(img, rx + 1, ry, pal[n - 2])
        put(img, rx, ry + 1, pal[n - 2])
        put(img, rx + 1, ry + 1, pal[1])
    return img


def keyhole_glow(pal, t: float, size: int = 16) -> Image.Image:
    """The keyhole's inner light, breathing (pal: dark -> hot)."""
    img = canvas(size)
    pulse = 0.5 + 0.5 * math.sin(2 * math.pi * t)
    n = len(pal)
    for y in range(size):
        for x in range(size):
            d = math.hypot((x + 0.5 - size / 2) / size * 2, (y + 0.5 - size * 0.4) / size * 2)
            v = max(0.0, 1.0 - d) * (0.55 + 0.45 * pulse)
            idx = min(n - 1, int(v * (n - 1) + 0.6))
            put(img, x, y, pal[idx])
    return img


def keyhole_shape(img: Image.Image, colour, size: int = 16) -> None:
    """Paint a keyhole (round top, flared slot) dark into img."""
    s = size / 16
    for y in range(size):
        for x in range(size):
            fx, fy = (x + 0.5) / s, (y + 0.5) / s
            head = math.hypot(fx - 8, fy - 6.2) < 2.1
            slot = 7.0 <= fx <= 9.0 + (fy - 7) * 0.18 and 6.5 <= fy <= 12.2 and fx >= 7.0 - (fy - 7) * 0.18
            if head or slot:
                put(img, x, y, colour)


def flame(t: float, size: int = 16, seed: int = 0, palette=None) -> Image.Image:
    """One tongue of fire with heat bands rolling up it; seamless in t."""
    pal = palette or ["#5a0e04", "#a3200a", "#e0460e", "#f77d1a", "#ffb23a", "#ffe07a", "#fff6cf"]
    img = canvas(size)
    rng = random.Random(seed)
    n = len(pal)
    for y in range(size):
        fy = 1 - (y + 0.5) / size                                    # 0 bottom .. 1 top
        sway = 0.10 * math.sin(2 * math.pi * (t + fy * 0.8 + seed * 0.17)) * fy
        width = 0.46 * (1 - fy ** 1.6) + 0.04
        for x in range(size):
            fx = (x + 0.5) / size - 0.5 - sway
            if abs(fx) > width:
                continue
            core = 1 - abs(fx) / width
            band = 0.5 + 0.5 * math.sin(2 * math.pi * (fy * 2.0 - t * 2))
            heat = core * (1 - fy * 0.75) + 0.18 * band - 0.05
            idx = max(0, min(n - 1, int(heat * n)))
            put(img, x, y, pal[idx])
    # sparks
    for k in range(3):
        ph = (t + k / 3 + rng.random() * 0.1) % 1
        sx = int(size * (0.3 + 0.4 * rng.random()) + math.sin(ph * 6) * 1.5)
        sy = int(size * (1 - ph))
        put(img, sx, max(0, sy - 1), pal[-2])
    return img


def chunk(colours, seed: int, size: int = 16, density: float = 0.3) -> Image.Image:
    """A speckled solid (stone, snow, coal): base tone with flecks."""
    img = canvas(size, fill=colours[len(colours) // 2])
    rng = random.Random(seed)
    for y in range(size):
        for x in range(size):
            r = rng.random()
            if r < density * 0.5:
                put(img, x, y, colours[max(0, len(colours) // 2 - 1)])
            elif r < density:
                put(img, x, y, colours[min(len(colours) - 1, len(colours) // 2 + 1)])
    return img


# --------------------------------------------------------------------------------------
# Geometry: the shared chest
# --------------------------------------------------------------------------------------

def only(frm, to, faces: dict, **kw) -> dict:
    """A box that draws only the listed faces."""
    first = next(iter(faces.values()))
    tex = first if isinstance(first, str) else first[0]
    return box(frm, to, tex, faces=faces, skip=tuple(s for s in ("north", "south", "east", "west", "up", "down")
                                                     if s not in faces), **kw)


def shell(body_tex: str, lid_tex: str, top_tex: str, faces_body=None, faces_lid=None) -> list[dict]:
    """The body and the lid blocks (solid, so nothing of the vanilla chest shows)."""
    return [
        box((B0, 0.0, B0), (B1, SEAM, B1), body_tex, faces=faces_body, skip=("down",)),
        box((L0, SEAM, L0), (L1, LID_TOP, L1), lid_tex, faces=dict({"up": top_tex}, **(faces_lid or {}))),
    ]


def rims(tex: str, e: float = 0.18) -> list[dict]:
    """A metal band round the base, under the lid seam and round the lid's top edge."""
    out = []
    for y0, y1, lo, hi in ((0.0, 0.9, B0, B1), (SEAM - 0.8, SEAM, B0, B1), (LID_TOP - 0.8, LID_TOP + 0.05, L0, L1)):
        # the base band has no floor face; the seam band's top would lie in the lid's bottom;
        # the top band leaves the lid's own top showing
        skip = ("down",) if y0 == 0.0 else ("up",)
        out.append(box((lo - e, y0, lo - e), (hi + e, y1, hi + e), tex, skip=skip))
    return out


def top_frame(tex: str, w: float = 0.9, e: float = 0.18) -> list[dict]:
    """A border of boards round the edge of the lid's top (for crowns that leave it showing)."""
    lo, hi, y0, y1 = L0 - e, L1 + e, LID_TOP - 0.01, LID_TOP + 0.12
    return [
        box((lo, y0, lo), (hi, y1, lo + w), tex, skip=("down",)),
        box((lo, y0, hi - w), (hi, y1, hi), tex, skip=("down",)),
        box((lo, y0, lo + w), (lo + w, y1, hi - w), tex, skip=("down",)),
        box((hi - w, y0, lo + w), (hi, y1, hi - w), tex, skip=("down",)),
    ]


def corner_brackets(tex: str, e: float = 0.32, arm: float = 1.6) -> list[dict]:
    """L-shaped brackets on all eight vertical corner runs (body and lid)."""
    out = []
    for (lo, hi, y0, y1) in ((B0, B1, 0.0, SEAM), (L0, L1, SEAM, LID_TOP + 0.05)):
        for cx in (lo, hi):
            for cz in (lo, hi):
                sx = 1 if cx == lo else -1
                sz = 1 if cz == lo else -1
                x0, x1 = cx - e * sx, cx + arm * sx
                z0, z1 = cz - e * sz, cz + arm * sz
                out.append(box((x0, y0, z0), (x1, y1, z0 + 0.0 + (e + 0.02) * sz), tex))   # face across X
                out.append(box((x0, y0, z0), (x0 + (e + 0.02) * sx, y1, z1), tex))         # face across Z
    return out


PLATE_FRONT = 16.02          # just past the vanilla latch (z 15..16) so it is hidden, barely out of the block


def lock(plate_tex: str, glow_tex: str, frame_tex: str, glow: int = 10, width: float = 4.0) -> list[dict]:
    """The lock on the front across the seam: plate, a glowing keyhole and a hasp from the lid.
    Everything stays within z 16.2, so crates placed side by side never overlap."""
    z = PLATE_FRONT
    h = width / 2
    parts = [
        box((8.0 - h, SEAM - 3.6, B1), (8.0 + h, SEAM + 0.6, z), plate_tex,
            uv={"north": [0, 0, 4, 4], "east": [0, 0, 1, 4], "west": [0, 0, 1, 4], "up": [0, 0, 4, 1],
                "down": [0, 0, 4, 1], "south": [0, 0, 16, 16]}),
        # the keyhole: a lit slot set into the plate
        box((7.55, SEAM - 2.9, z), (8.45, SEAM - 1.9, z + 0.04), glow_tex, glow=glow, shade=False, uv="full"),
        box((7.75, SEAM - 2.0, z), (8.25, SEAM - 1.0, z + 0.04), glow_tex, glow=glow, shade=False, uv="full"),
        # hasp hanging off the lid over the plate
        box((7.0, SEAM - 0.4, z), (9.0, SEAM + 2.4, z + 0.12), frame_tex),
        box((7.4, SEAM - 0.1, z + 0.12), (8.6, SEAM + 0.5, z + 0.18), frame_tex),
    ]
    return parts


def lid_frame(tex: str, z: float = L1) -> list[dict]:
    """A raised board frame round the lid's front (the panel the twin emblems sit in)."""
    return [
        box((1.4, SEAM + 0.6, z), (14.6, SEAM + 1.2, z + 0.3), tex),
        box((1.4, LID_TOP - 1.6, z), (14.6, LID_TOP - 1.0, z + 0.3), tex),
        box((1.4, SEAM + 1.2, z), (2.0, LID_TOP - 1.6, z + 0.3), tex),
        box((14.0, SEAM + 1.2, z), (14.6, LID_TOP - 1.6, z + 0.3), tex),
    ]


def round_log(center, radius: float, length: float, axis: str, bark: str, end: str) -> list[dict]:
    """A round log whose two ends show the whole ring texture, centred on every slab."""
    slabs = prism(center, radius, length, bark, axis=axis, cap=end)
    k = 8.0 / radius
    for e in slabs:
        dx = e["to"][0] - e["from"][0]
        dz = e["to"][2] - e["from"][2]
        for side in ("up", "down"):
            if side in e["faces"]:
                e["faces"][side]["uv"] = [round(8 - dx / 2 * k, 4), round(8 - dz / 2 * k, 4),
                                          round(8 + dx / 2 * k, 4), round(8 + dz / 2 * k, 4)]
    return slabs


def hinges(tex: str) -> list[dict]:
    """Two strap hinges on the back, over the seam."""
    out = []
    for x in (3.0, 11.0):
        out.append(box((x, SEAM - 2.2, L0 - 0.3), (x + 2.0, SEAM + 3.0, L0), tex))
        out.append(box((x - 0.2, SEAM - 0.5, L0 - 0.55), (x + 2.2, SEAM + 0.5, L0 - 0.3), tex))
    return out


def feet(tex: str, h: float = 0.0) -> list[dict]:
    """Stubby corner feet (h = 0: none; the chest sits on the floor)."""
    if h <= 0:
        return []
    out = []
    for fx in (B0 + 1.0, B1 - 1.0):
        for fz in (B0 + 1.0, B1 - 1.0):
            out.append(box((fx - 1.0, 0.0, fz - 1.0), (fx + 1.0, h, fz + 1.0), tex))
    return out


def covers_vanilla_chest(elements) -> bool:
    """True when the shell (first two elements) encloses a vanilla chest's box."""
    body, lid = elements[0], elements[1]
    return (body["from"][0] <= 1 and body["to"][0] >= 15 and body["from"][2] <= 1 and body["to"][2] >= 15
            and lid["to"][1] >= 14 and lid["from"][0] <= 1 and lid["to"][0] >= 15)


__all__ = ["KIND", "COUNTERPART", "B0", "B1", "L0", "L1", "SEAM", "LID_TOP", "FRONT", "put", "planks", "metal",
           "plain_metal", "lock_plate", "keyhole_glow", "keyhole_shape", "flame", "chunk", "only", "shell", "rims", "top_frame",
           "corner_brackets", "lock", "lid_frame", "round_log", "hinges", "feet", "covers_vanilla_chest",
           "within_block", "mix", "shade", "PLATE_FRONT"]


def within_block(elements, slack: float = 0.25) -> bool:
    """True when nothing reaches more than `slack` units past the block's sides (x/z 0..16),
    so two crates placed side by side never overlap."""
    from art.kit import corners
    for e in elements:
        for (x, _, z) in corners(e):
            if x < -slack or x > 16 + slack or z < -slack or z > 16 + slack:
                return False
    return True
