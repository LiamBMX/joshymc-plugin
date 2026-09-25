"""Jack-o'-Lantern Mask: a round, ribbed carved pumpkin worn over the whole head, with a
curly stem, a big autumn leaf and vine tendrils on top. The angry face is cut right through
the ribs and glows candle-orange from inside (light_emission on the cut walls and back wall).

Frame (helmet): the wearer's head is the cube [1.6, 14.4]^3, face at -Z, +X = wearer's right.
The shell is a ring of 16 lobes (each a column of tilted tiles) on a squircle plan, so it
stays round-looking while clearing the corners of the head cube; recessed seams run between
the lobes up to the stem dimple.
"""
from __future__ import annotations

import math
import random

from art import kit
from art.kit import bar, box, canvas, display, fill, mix, model, save, turn

ID = "jack_o_lantern_mask"
NAME = "Jack-o'-Lantern Mask"
KIND = "helmet"

# --------------------------------------------------------------------------------------
# Palettes (dark -> light, hue-shifted: shadows lean to midnight purple, lights to gold)
# --------------------------------------------------------------------------------------
SKIN = ["#5e1a22", "#b8481a", "#f07322", "#ff9530", "#ffb44c", "#ffd36c"]
SEAM = ["#3a1024", "#72261a", "#a8441c", "#c85a20"]
FLESH = ["#b8621e", "#e39434", "#f6bd5a", "#ffdc8a", "#fff0c0"]
GLOW = ["#a8321a", "#d9541c", "#f5801f", "#ffab35", "#ffd160", "#fff1a8"]
STEM = ["#1f1a0c", "#3d3a16", "#5f6222", "#83873a", "#aeae5c", "#d6cf8e"]
LEAF = ["#0f2618", "#1f4524", "#33692e", "#4f8f38", "#80b44a", "#c1d766"]
VINE = ["#1b3316", "#2f5522", "#4b7a2e", "#76a33f"]
TOXIC = ["#2b6e1a", "#52c42a", "#a6ff4a", "#e2ffb0"]

# --------------------------------------------------------------------------------------
# Shell geometry
# --------------------------------------------------------------------------------------
CX = CZ = 8.0
LOBES = 16
THICK = 0.8            # lobe tile thickness
SEAM_DEPTH = 0.36      # how far the seams sit below the lobes
SEAM_T = 0.4           # seam tile thickness
SEAM_GAP = 0.13        # share of each lobe's width left open as a seam

# Outer-surface rings, bottom rim to stem dimple: (y, plan half-width a, squircle exponent n).
# The bottom ring's height is set per lobe by _rim_y() so the rim clears the shoulders.
RINGS = [
    (None, 7.4, 5.0),
    (1.3, 7.8, 8.5),      # squarer where the head's corners are, rounder at the bulge
    (4.7, 8.7, 4.6),
    (8.2, 9.0, 3.7),
    (11.7, 8.7, 4.6),
    (15.35, 7.8, 8.5),    # just above the corners of the hat layer
    (16.7, 6.9, 5.0),
    (17.5, 5.15, 3.2),
    (17.82, 3.2, 2.6),
]
FACE_SECTIONS = (1, 2, 3, 4)  # sections (ring i -> i+1) that carry the carved face


RIM = {0: -0.3, 1: -0.28, 2: -0.22, 3: 0.3, 4: 0.7}


def _rim_y(k: int) -> float | None:
    """Bottom-rim height of lobe k: lowest under the chin and at the back of the neck,
    rising toward the shoulders so the rim only just rests on them."""
    return RIM[min(k % 8, 8 - k % 8)]


def _short(k: int) -> bool:
    """Lobes over the shoulders skip the tucked-under bottom ring and just drop to the rim."""
    return _rim_y(k % LOBES) >= 0.3


_RING_CACHE: dict = {}


def _ring(a: float, n: float):
    """Densely sampled squircle, starting at the front centre and running toward +X."""
    key = (a, n)
    if key not in _RING_CACHE:
        pts = []
        steps = 2400
        for i in range(steps + 1):
            t = -math.pi / 2 + 2 * math.pi * i / steps
            c, s = math.cos(t), math.sin(t)
            pts.append((a * math.copysign(abs(c) ** (2 / n), c), a * math.copysign(abs(s) ** (2 / n), s)))
        cum = [0.0]
        for i in range(1, len(pts)):
            cum.append(cum[-1] + math.dist(pts[i - 1], pts[i]))
        _RING_CACHE[key] = (pts, cum)
    return _RING_CACHE[key]


def _on_ring(a: float, n: float, f: float):
    """(plan point, unit tangent, lobe spacing) at arc fraction f around the ring."""
    pts, cum = _ring(a, n)
    total = cum[-1]
    target = (f % 1.0) * total
    lo, hi = 0, len(cum) - 1
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if cum[mid] <= target:
            lo = mid
        else:
            hi = mid
    seg = cum[hi] - cum[lo]
    k = (target - cum[lo]) / seg if seg else 0.0
    p0, p1 = pts[lo], pts[hi]
    p = (p0[0] + (p1[0] - p0[0]) * k, p0[1] + (p1[1] - p0[1]) * k)
    tx, tz = p1[0] - p0[0], p1[1] - p0[1]
    ln = math.hypot(tx, tz)
    return p, (tx / ln, tz / ln), total / LOBES


def _v(a, b, s=1.0):
    return tuple(a[i] + b[i] * s for i in range(3))


def _sub(a, b):
    return tuple(a[i] - b[i] for i in range(3))


def _dot(a, b):
    return sum(a[i] * b[i] for i in range(3))


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _unit(a):
    ln = math.sqrt(_dot(a, a))
    return tuple(v / ln for v in a)


def _ring_point(ring: int, f: float, k: int | None = None):
    y, a, n = RINGS[ring]
    if y is None:
        y = _rim_y(k)
    p, t, spacing = _on_ring(a, n, f)
    return (CX + p[0], y, CZ + p[1]), (t[0], 0.0, t[1]), spacing


def tile_frame(section: int, f: float, k: int | None = None, bottom_y: float | None = None):
    """Frame of the tile of the lobe (or seam) at arc fraction f in a section.

    Returns dict(P=centre of the outer face, x=tangent, y=up the meridian, n=outward normal,
    h=length, spacing=(lower, upper) lobe spacing)."""
    m0, t0, s0 = _ring_point(section, f, k)
    m1, t1, s1 = _ring_point(section + 1, f, k)
    if bottom_y is not None:
        m0 = (m0[0], bottom_y, m0[2])
    u = _unit(_sub(m1, m0))
    t = _unit(_v(t0, t1))
    t = _unit(_v(t, u, -_dot(t, u)))
    n = _cross(u, t)
    return {"P": tuple((m0[i] + m1[i]) / 2 for i in range(3)), "x": t, "y": u, "n": n,
            "h": math.dist(m0, m1), "spacing": (s0, s1)}


def frame_matrix(fr):
    """Rotation taking box axes (x, y, z) to (tangent, meridian, inward)."""
    x, y, n = fr["x"], fr["y"], fr["n"]
    return ((x[0], y[0], -n[0]), (x[1], y[1], -n[1]), (x[2], y[2], -n[2]))


def slab(fr, width: float, depth: float, tex: str, inset: float = 0.0, extra_h: float = 0.0,
         **kw) -> dict:
    """A box whose outer (north) face lies on the frame's outer surface, pushed `inset` inward."""
    p = _v(fr["P"], fr["n"], -inset)
    h = fr["h"] + extra_h
    e = box((p[0] - width / 2, p[1] - h / 2, p[2]), (p[0] + width / 2, p[1] + h / 2, p[2] + depth), tex, **kw)
    ex, ey, ez = kit.matrix_to_euler(frame_matrix(fr))
    return turn(e, x=ex, y=ey, z=ez, origin=p)


def face_point(fr, width: float, h: float, inset: float, u: float, v: float):
    """World point of face coordinates (u, v) (uv units from the face's top-left as the
    viewer sees it) on the outer face of slab(fr, width, ...)."""
    lx = width / 2 - u
    ly = h / 2 - v
    p = _v(fr["P"], fr["n"], -inset)
    return tuple(p[i] + fr["x"][i] * lx + fr["y"][i] * ly for i in range(3))


def _uv_origin(tile) -> tuple[float, float]:
    """Texture offset of a tile: the lobe centre sits on a texel boundary (so mirrored lobes
    carve symmetrically). Wall tiles are re-stacked in shell_layout() so each rib's texture
    runs on unbroken from ring to ring; the rest get a shuffled offset."""
    w, h = tile["width"], tile["fr"]["h"] + tile["extra_h"]
    u0 = math.ceil(w / 2 * 2) / 2 - w / 2
    room = max(0.0, 16 - h - 0.05)
    if tile["section"] in FACE_SECTIONS:
        v0 = 0.0
    else:
        m = min(tile["k"], (LOBES - tile["k"]) % LOBES)
        v0 = round(((m * 5 + tile["section"] * 3) % 9) / 9 * room * 2) / 2
    return round(u0, 4), round(min(v0, room), 4)


_LAYOUT: list = []


def shell_layout():
    """Every shell tile as a dict: kind ("lobe"/"seam"), section, k, fr (frame), width,
    depth, inset, extra_h, lowest, uv0 (texture origin) and tex. Computed once."""
    if not _LAYOUT:
        _LAYOUT.extend(_build_layout())
    return _LAYOUT


def _build_layout():
    tiles = []
    for section in range(len(RINGS) - 1):
        for k in range(LOBES):
            if section == 0 and _short(k):
                continue
            bottom = _rim_y(k) if section == 1 and _short(k) else None
            fr = tile_frame(section, k / LOBES, k, bottom_y=bottom)
            s0, s1 = fr["spacing"]
            lo, hi = min(s0, s1), max(s0, s1)
            width = lo * (1 - SEAM_GAP) + (hi - lo) * 0.3
            tiles.append({"kind": "lobe", "section": section, "k": k, "fr": fr, "width": width,
                          "depth": THICK, "inset": 0.0, "extra_h": 0.1,
                          "lowest": section == 0 or bottom is not None})
        for k in range(LOBES):
            if section == len(RINGS) - 2:
                break   # the crown's seams are the dark underlay showing between its lobes
            k2 = (k + 1) % LOBES
            bottom = None
            if section == 0:
                if _short(k) or _short(k2):
                    continue
                bottom = max(_rim_y(k), _rim_y(k2))
            elif section == 1 and (_short(k) or _short(k2)):
                bottom = max(_rim_y(k), _rim_y(k2))
            fr = tile_frame(section, (k + 0.5) / LOBES, k, bottom_y=bottom)
            s0, s1 = fr["spacing"]
            lo, hi = min(s0, s1), max(s0, s1)
            lobe_w = lo * (1 - SEAM_GAP) + (hi - lo) * 0.3
            width = max(hi - lobe_w, 0.2) + 0.7
            tiles.append({"kind": "seam", "section": section, "k": k, "fr": fr, "width": width,
                          "depth": SEAM_T, "inset": SEAM_DEPTH, "extra_h": 0.1,
                          "lowest": section == 0 or bottom is not None})
    for t in tiles:
        t["uv0"] = _uv_origin(t)
    # Wall tiles: stack each rib's sections down one texture, top section first.
    for kind in ("lobe", "seam"):
        for k in range(LOBES):
            v = 0.45
            for section in sorted(FACE_SECTIONS, reverse=True):
                t = next((t for t in tiles if t["kind"] == kind and t["k"] == k and t["section"] == section), None)
                if t is None:
                    continue
                t["uv0"] = (t["uv0"][0], round(v, 4))
                v += t["fr"]["h"]
    for t in tiles:
        t["tex"] = tile_texture(t)
    return tiles


def tile_texture(tile) -> str:
    if tile["kind"] == "seam":
        return "seam"
    if tile["section"] in FACE_SECTIONS:
        return f"wall{min(tile['k'], LOBES - tile['k']) % 3}"
    return f"lobe{tile['section']}"


# --------------------------------------------------------------------------------------
# The carved face, in viewer coordinates (vx to the viewer's right, vy up; world x = 16 - vx)
# --------------------------------------------------------------------------------------
HOLES = {
    # Angry eyes: the brow slants down toward the nose.
    "eye_l": [(3.2, 12.55), (7.15, 10.0), (6.3, 8.95), (3.8, 9.15)],
    "nose": [(8.0, 9.75), (9.3, 7.5), (6.7, 7.5)],
    # Toothy grin: two fangs hang from the top lip, one tooth stands on the bottom lip.
    "mouth": [(2.9, 7.3), (4.2, 5.75), (5.0, 5.55), (5.0, 4.45), (6.2, 4.45), (6.2, 5.3),
              (9.8, 5.3), (9.8, 4.45), (11.0, 4.45), (11.0, 5.55), (11.8, 5.75), (13.1, 7.3),
              (12.6, 4.6), (11.2, 3.15), (8.8, 2.55), (8.8, 3.8), (7.2, 3.8), (7.2, 2.55),
              (4.8, 3.15), (3.4, 4.6)],
}
HOLES["eye_r"] = [(16 - x, y) for x, y in reversed(HOLES["eye_l"])]


def world_holes():
    """Hole outlines in world XY (x = 16 - vx), wound counter-clockwise."""
    out = {}
    for name, pts in HOLES.items():
        w = [(16 - x, y) for x, y in pts]
        area = sum(w[i][0] * w[i - 1][1] - w[i - 1][0] * w[i][1] for i in range(len(w)))
        out[name] = w if area > 0 else list(reversed(w))
    return out


def _inside(poly, x, y) -> bool:
    hit = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            hit = not hit
        j = i
    return hit


def carved(x: float, y: float) -> bool:
    return any(_inside(p, x, y) for p in world_holes().values())


# --------------------------------------------------------------------------------------
# Which shell tiles carry the carved face
# --------------------------------------------------------------------------------------
GLOW_Z = 0.55                          # front of the candle-lit back wall (clear of the hat layer)
GLOW_BOX = (2.1, 1.7, 13.9, 13.4)      # its x0, y0, x1, y1
TEXELS = 2                             # texels per unit on 32px textures


FLANK_U = {"east": 12.0, "west": 14.0}   # where each rib flank reads the lobe texture


def _texel_world(tile, face: str, c: int, r: int):
    """World point of texel (c, r) (of a 32px texture) on face `face` of a shell tile."""
    fr, w, h = tile["fr"], tile["width"], tile["fr"]["h"] + tile["extra_h"]
    u0, v0 = tile["uv0"]
    u = (c + 0.5) / TEXELS
    v = (r + 0.5) / TEXELS - v0
    if face == "north":
        return face_point(fr, w, h, tile["inset"], u - u0, v)
    # Flanks read [FLANK_U, FLANK_U + depth] with the outer edge at the high end.
    lz = FLANK_U[face] + tile["depth"] - u
    lx = w / 2 if face == "east" else -w / 2
    ly = h / 2 - v
    base = _v(fr["P"], fr["n"], -tile["inset"])
    return tuple(base[i] + fr["x"][i] * lx + fr["y"][i] * ly - fr["n"][i] * lz for i in range(3))


def face_texels(tile, face: str):
    """(c, r) texels a face of the tile covers on its 32px texture."""
    u0, v0 = tile["uv0"]
    if face != "north":
        u0 = FLANK_U[face]
    span_u = tile["width"] if face == "north" else tile["depth"]
    span_v = tile["fr"]["h"] + tile["extra_h"]
    c0, c1 = int(math.floor(u0 * TEXELS)), int(math.ceil((u0 + span_u) * TEXELS))
    r0, r1 = int(math.floor(v0 * TEXELS)), int(math.ceil((v0 + span_v) * TEXELS))
    return [(c, r) for c in range(c0, min(c1, 32)) for r in range(r0, min(r1, 32))]


def carved_tiles():
    """{tile index: texture name} for every face-section tile the carving cuts through."""
    tiles = shell_layout()
    out = {}
    for i, t in enumerate(tiles):
        if t["section"] not in FACE_SECTIONS or t["fr"]["n"][2] > -0.5:
            continue
        if any(carved(*_texel_world(t, "north", c, r)[:2]) for c, r in face_texels(t, "north")):
            out[i] = f"cut_{t['kind']}{t['section']}_{t['k']}"
    return out


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------
def _section_width(section: int) -> tuple[float, float]:
    t = next(t for t in shell_layout() if t["kind"] == "lobe" and t["section"] == section)
    return t["width"], t["uv0"][0]


def _px(img, x, y, colour):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), kit.rgba(colour))


def paint_lobe(section: int):
    """Pumpkin skin for one ring of lobes: a rounded rib (dark edges, a soft highlight a
    little left of centre), faint vertical striations, a few pale lenticels, and the two rib
    flank strips at FLANK_U."""
    rng = random.Random(40 + section)
    w, u0 = _section_width(section)
    img = canvas(32)
    base = 3
    last = len(RINGS) - 2
    for c in range(0, 10):
        q = ((c + 0.5) / TEXELS - u0) / w
        if q < 0.12 or q > 0.9:
            tone = SKIN[base - 1]
        elif 0.3 <= q <= 0.5:
            tone = SKIN[base + 1]
        else:
            tone = SKIN[base]
        fill(img, (c, 0, c, 31), tone)
    # Striations: long faint streaks inside the rib, never on its edges.
    cols = [c for c in range(10) if 0.16 <= ((c + 0.5) / TEXELS - u0) / w <= 0.86]
    for c in cols:
        y = rng.randrange(0, 6)
        while y < 32:
            ln = rng.randint(3, 7)
            if rng.random() < 0.45:
                cur = img.getpixel((c, y))
                tone = mix(kit.hexc(cur), SKIN[base - 1], 0.45) if rng.random() < 0.6 else \
                    mix(kit.hexc(cur), SKIN[base + 1], 0.5)
                fill(img, (c, y, c, min(31, y + ln - 1)), tone)
            y += ln + rng.randint(2, 5)
    # Lenticels: tiny pale flecks.
    for _ in range(5):
        c, y = rng.choice(cols), rng.randrange(0, 32)
        _px(img, c, y, SKIN[5] if section < last else SKIN[4])
    if section == 0:   # the chin curve is in shadow
        for y in range(32):
            for c in range(10):
                cur = kit.hexc(img.getpixel((c, y)))
                img.putpixel((c, y), kit.rgba(mix(cur, SKIN[1], 0.25)))
    if section >= last - 1:   # the crown darkens and greens toward the stem
        amt = 0.16 if section == last - 1 else 0.36
        for y in range(32):
            for c in range(10):
                cur = kit.hexc(img.getpixel((c, y)))
                img.putpixel((c, y), kit.rgba(mix(cur, "#7a4a18", amt)))
    for u in FLANK_U.values():   # rib flanks: darker as they go into the seam
        c = int(u * TEXELS)
        fill(img, (c, 0, c, 31), SEAM[3])
        fill(img, (c + 1, 0, c + 1, 31), SKIN[2])
    return img


def paint_wall_skin(variant: int):
    """Skin for the four wall rings of a rib, painted as one long strip (the rings stack down
    the texture): rounded rib shading, long striations, lenticels, a glossy upper half."""
    rng = random.Random(70 + variant)
    img = canvas(32)
    profile = [SKIN[2], SKIN[3], SKIN[4], SKIN[4], SKIN[3], SKIN[3], SKIN[3], SKIN[2]]
    for c, tone in enumerate(profile):
        fill(img, (c, 0, c, 31), tone)
    for r in range(0, 11):          # upper half: a warm gloss on the lit side of the rib
        _px(img, 3, r, SKIN[5] if r % 5 else SKIN[4])
        if r > 1:
            _px(img, 2, r, SKIN[5] if r % 3 == 0 else SKIN[4])
    for r in range(22, 32):         # lower half: the rib turns away from the light
        for c in range(1, 7):
            cur = kit.hexc(img.getpixel((c, r)))
            img.putpixel((c, r), kit.rgba(mix(cur, SKIN[1], 0.12 + (r - 22) * 0.02)))
    for c in (1, 4, 5):             # striations
        y = rng.randrange(0, 5)
        while y < 32:
            ln = rng.randint(4, 8)
            if rng.random() < 0.5:
                cur = kit.hexc(img.getpixel((c, y)))
                fill(img, (c, y, c, min(31, y + ln - 1)), mix(cur, SKIN[1], 0.4))
            y += ln + rng.randint(2, 6)
    for _ in range(3):              # lenticels
        _px(img, rng.choice((1, 4, 5)), rng.randrange(2, 30), mix(SKIN[4], SKIN[5], 0.5))
    for u in FLANK_U.values():
        c = int(u * TEXELS)
        fill(img, (c, 0, c, 31), SEAM[3])
        fill(img, (c + 1, 0, c + 1, 31), SKIN[2])
    return img


def paint_seam():
    """The recessed groove between ribs (only its middle texel or two ever shows)."""
    img = canvas(32, fill=SEAM[2])
    for c in range(32):
        if c % 3 == 1:
            fill(img, (c, 0, c, 31), SEAM[1])
    return img


def carve(img, tile):
    """Punch the face holes through a tile's texture (outer face and both rib flanks) and
    paint the knife-cut lips around them."""
    out = img.copy()
    holes = set()
    for face in ("north", "east", "west"):
        if tile["kind"] == "seam" and face != "north":
            continue
        for c, r in face_texels(tile, face):
            x, y, _ = _texel_world(tile, face, c, r)
            if carved(x, y):
                holes.add((c, r))
    north = set(face_texels(tile, "north"))
    for c, r in north - holes:
        if (c, r + 1) in holes:
            _px(out, c, r, SKIN[0] if tile["kind"] == "lobe" else SEAM[0])     # lip over a hole: shadowed
        elif (c, r - 1) in holes:
            _px(out, c, r, FLESH[1])                # lip under a hole: lit cut flesh
        elif (c - 1, r) in holes or (c + 1, r) in holes:
            _px(out, c, r, mix(FLESH[0], SKIN[2], 0.5))
    for c, r in holes:
        out.putpixel((c, r), (0, 0, 0, 0))
    return out


def paint_flesh():
    """Inside of the shell: pale pulp with stringy fibres and a few seeds."""
    rng = random.Random(9)
    img = canvas(32, fill=FLESH[2])
    kit.grain(img, (0, 0, 31, 31), [FLESH[1], FLESH[3]], axis="y", seed=3, min_len=3, max_len=8,
              density=0.35)
    for _ in range(8):   # stringy pulp
        x, y = rng.randrange(32), rng.randrange(32)
        for _ in range(rng.randint(5, 10)):
            _px(img, x % 32, y % 32, FLESH[0])
            x += rng.choice((-1, 0, 0, 1))
            y += 1
    for _ in range(6):
        x, y = rng.randrange(1, 30), rng.randrange(1, 30)
        _px(img, x, y, FLESH[4])
        _px(img, x, y + 1, FLESH[3])
    return img


def paint_rim():
    """Cut bottom rim: rind line on the outside, flesh inside."""
    img = canvas(32, fill=FLESH[2])
    fill(img, (0, 1, 31, 1), SKIN[1])
    for c in range(0, 32, 5):
        _px(img, c, 0, FLESH[3])
    return img


def paint_glow():
    """The candle-lit back wall, in world space behind the face: hottest low in the middle
    (where the candle sits), cooling to deep orange at the top and sides."""
    x0, y0, x1, y1 = GLOW_BOX
    img = canvas(32)
    for c in range(32):
        for r in range(32):
            x = x1 - (c + 0.5) / TEXELS
            y = y1 - (r + 0.5) / TEXELS
            d = math.hypot((x - 8.0) / 1.3, (y - 3.8) * 0.62)
            steps = [(1.8, GLOW[5]), (3.5, GLOW[4]), (5.6, GLOW[3]), (7.6, GLOW[2]), (99, GLOW[1])]
            for i, (limit, col) in enumerate(steps):
                if d < limit:
                    tone = col
                    if i + 1 < len(steps) and d > limit - 0.45 and (c + r) % 2 == 0:
                        tone = steps[i + 1][1]
                    break
            img.putpixel((c, r), kit.rgba(tone))
    return img


def paint_wall(bright: bool):
    """Cut walls of the carving, lit from inside: warm rind edge in front, hot flesh deeper."""
    img = canvas(32)
    front, mid, back = (GLOW[3], GLOW[4], GLOW[5]) if bright else (GLOW[2], GLOW[3], GLOW[4])
    for r in range(32):
        _px(img, 0, r, front)
        _px(img, 1, r, mid)
        fill(img, (2, r, 31, r), back if r % 7 else mid)
    return img


def paint_stem(dry: bool):
    """Woody, ridged stem: vertical ridges of dark and light fibre; the upper curl dries to tan."""
    rng = random.Random(21 if dry else 20)
    img = canvas(32)
    tones = [STEM[2], STEM[3], STEM[4], STEM[5]] if dry else [STEM[1], STEM[2], STEM[3], STEM[4]]
    pattern = [0, 1, 2, 1, 3, 1, 0, 2, 1]
    for c in range(32):
        fill(img, (c, 0, c, 31), tones[pattern[c % len(pattern)]])
    kit.grain(img, (0, 0, 31, 31), [tones[0], tones[1]], axis="y", seed=rng.randrange(99),
              min_len=2, max_len=5, density=0.25)
    for _ in range(7):   # knots and scuffs
        _px(img, rng.randrange(32), rng.randrange(32), STEM[1] if dry else STEM[0])
    return img


def paint_stem_end():
    """The cut end of the stem: pale pith, a growth ring and a dark bark edge."""
    img = canvas(16, fill=STEM[1])
    for c in range(16):
        for r in range(16):
            d = math.hypot(c - 7.5, r - 7.5)
            if d < 7.6:
                tone = STEM[5] if d < 3.2 else STEM[4] if d < 4.4 else STEM[5] if d < 5.6 else STEM[3]
                img.putpixel((c, r), kit.rgba(tone))
    fill(img, (7, 7, 8, 8), STEM[3])
    return img


def paint_cap():
    """The stem dimple: dark, weathered rind that greens toward the stem."""
    rng = random.Random(5)
    img = canvas(16)
    for c in range(16):
        for r in range(16):
            d = math.hypot(c - 7.5, r - 7.5)
            img.putpixel((c, r), kit.rgba(mix(SKIN[1], STEM[2], max(0.0, 1 - d / 8))))
    for _ in range(10):
        _px(img, rng.randrange(16), rng.randrange(16), SEAM[1])
    return img


LEAF_LOBES = [(-78, 0.56), (-38, 0.84), (0, 1.0), (38, 0.84), (78, 0.56)]
LEAF_BASE = (16.0, 27.0)     # where the petiole meets the blade, in texels
LEAF_R = 18.0


def leaf_mask():
    """Palmate pumpkin leaf pointing up the texture, attached at LEAF_BASE: five pointed,
    serrated lobes around a heart-shaped middle.
    Returns {(c, r): (lobe angle, t along the lobe, distance to the edge)}."""
    mask = {}
    for c in range(32):
        for r in range(32):
            x, y = c + 0.5 - LEAF_BASE[0], LEAF_BASE[1] - (r + 0.5)
            best = None
            for ang, ln in LEAF_LOBES:
                a = math.radians(ang)
                ax, ay = math.sin(a), math.cos(a)
                along, across = x * ax + y * ay, -x * ay + y * ax
                length = LEAF_R * ln
                t = along / length
                if not 0.0 <= t <= 1.0:
                    continue
                # Pointed lobe: widest a little under halfway, tapering to a sharp tip.
                half = (0.56 if ang == 0 else 0.5) * length * (t ** 0.55) * ((1 - t) ** 0.8) / 0.41 * 0.5
                half += 1.0 * (1 - t)
                # Serration: small teeth along the outer two-thirds of each lobe.
                if t > 0.3 and (along * 0.9) % 1.0 < 0.34:
                    half -= 0.55
                if abs(across) <= half:
                    edge = half - abs(across)
                    if best is None or edge > best[2]:
                        best = (ang, t, edge)
            if best is None and math.hypot(x, y - 3.4) < 5.6 and y > -0.5:
                best = (0, 0.1, 5.6 - math.hypot(x, y - 3.4))   # the heart of the leaf
            if best:
                mask[(c, r)] = best
    return mask


def paint_leaf(under: bool):
    """The leaf: top (rich green, pale veins, autumn-gold tips) or underside (cooler, teal)."""
    img = canvas(32)
    mask = leaf_mask()
    pal = LEAF if not under else ["#1a3f33", "#2a5c47", "#3c7a5a", "#579670", "#7cb487", "#b4dcaa"]
    for (c, r), (ang, t, edge) in mask.items():
        tone = pal[3]
        if t < 0.22:
            tone = pal[2]                          # shaded heart near the stalk
        elif edge < 1.6 and ang <= 0:
            tone = pal[4]                          # lit edges on the left-hand lobes
        if not under and t > 0.72 and ang in (-78, 0, 78):
            tone = "#d9a032" if edge > 0.9 else "#b0661e"   # autumn turning at the tips
        img.putpixel((c, r), kit.rgba(tone))
    for ang, ln in LEAF_LOBES:                     # veins: a pale midrib per lobe
        a = math.radians(ang)
        for i in range(1, int(LEAF_R * ln * 0.86)):
            c = int(LEAF_BASE[0] + math.sin(a) * i)
            r = int(LEAF_BASE[1] - math.cos(a) * i)
            if (c, r) in mask:
                img.putpixel((c, r), kit.rgba(pal[5] if i > 3 else pal[4]))
                if i % 4 == 0 and i < LEAF_R * ln * 0.7:   # side veins
                    for side in (-1, 1):
                        cc = int(LEAF_BASE[0] + math.sin(a) * (i + 1.5) + math.cos(a) * side * 1.5)
                        rr = int(LEAF_BASE[1] - math.cos(a) * (i + 1.5) + math.sin(a) * side * 1.5)
                        if (cc, rr) in mask:
                            img.putpixel((cc, rr), kit.rgba(pal[4]))
    return _rim_outline(img, pal[0])


def _rim_outline(img, colour):
    """Dark 1px rim just inside a cut-out silhouette."""
    out = img.copy()
    src, dst = img.load(), out.load()
    for c in range(img.width):
        for r in range(img.height):
            if not src[c, r][3]:
                continue
            for dc, dr in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                cc, rr = c + dc, r + dr
                if not (0 <= cc < img.width and 0 <= rr < img.height) or not src[cc, rr][3]:
                    dst[c, r] = kit.rgba(colour)
                    break
    return out


def paint_vine():
    img = canvas(16)
    tones = [VINE[1], VINE[2], VINE[3], VINE[2]]
    for c in range(16):
        fill(img, (c, 0, c, 15), tones[c % 4])
    for r in range(0, 16, 3):
        _px(img, (r * 5) % 16, r, VINE[0])
    return img


def paint_vine_glow():
    """Tendril tips with a faint toxic-green glow (the collection's spooky accent)."""
    img = canvas(16)
    tones = [TOXIC[1], TOXIC[2], TOXIC[3], TOXIC[2]]
    for c in range(16):
        fill(img, (c, 0, c, 15), tones[c % 4])
    return img


def textures() -> None:
    skins = {f"lobe{s}": paint_lobe(s) for s in range(len(RINGS) - 1) if s not in FACE_SECTIONS}
    skins.update({f"wall{v}": paint_wall_skin(v) for v in range(3)})
    skins["seam"] = paint_seam()
    for name, img in skins.items():
        save(img, name)
    tiles = shell_layout()
    for i, name in carved_tiles().items():
        save(carve(skins[tiles[i]["tex"]], tiles[i]), name)
    save(paint_flesh(), "flesh")
    save(paint_rim(), "rim")
    save(paint_glow(), "glow")
    save(paint_wall(True), "wall_hi")
    save(paint_wall(False), "wall_lo")
    save(paint_stem(False), "stem")
    save(paint_stem(True), "stem_dry")
    save(paint_stem_end(), "stem_end")
    save(paint_cap(), "cap")
    save(paint_leaf(False), "leaf")
    save(paint_leaf(True), "leaf_under")
    save(paint_vine(), "vine")
    save(paint_vine_glow(), "vine_glow")


# --------------------------------------------------------------------------------------
# Model
# --------------------------------------------------------------------------------------
def _hit(tile, origin, d):
    """Distance along ray (origin, d) to the tile's outer face, or None."""
    fr = tile["fr"]
    p = _v(fr["P"], fr["n"], -tile["inset"])
    den = _dot(d, fr["n"])
    if abs(den) < 1e-9:
        return None
    s = _dot(_sub(p, origin), fr["n"]) / den
    if s <= 0:
        return None
    q = _sub(_v(origin, d, s), p)
    if abs(_dot(q, fr["x"])) <= tile["width"] / 2 and abs(_dot(q, fr["y"])) <= (fr["h"] + tile["extra_h"]) / 2:
        return s
    return None


def surface_z(x: float, y: float) -> float:
    """z of the first shell surface seen looking at the face (along +Z) at (x, y)."""
    best = None
    for t in shell_layout():
        if t["fr"]["n"][2] > -0.2:
            continue
        s = _hit(t, (x, y, -20.0), (0.0, 0.0, 1.0))
        if s is not None:
            best = s if best is None else min(best, s)
    return -20.0 + best if best is not None else 0.0


def shell() -> list[dict]:
    tiles = shell_layout()
    cut = carved_tiles()
    parts = []
    for i, t in enumerate(tiles):
        fr, w, h = t["fr"], t["width"], t["fr"]["h"] + t["extra_h"]
        u0, v0 = t["uv0"]
        name = cut.get(i, t["tex"])
        lowest = t["lowest"]
        faces = {"south": ("flesh", [0, 0, min(16, w), min(16, h)])}
        skip = ["up"]
        if t["kind"] == "lobe":
            faces["east"] = (name, [FLANK_U["east"], v0, FLANK_U["east"] + t["depth"], v0 + h])
            faces["west"] = (name, [FLANK_U["west"] + t["depth"], v0, FLANK_U["west"], v0 + h])
            if t["section"] == len(RINGS) - 2:
                skip = []
                faces["up"] = ("cap", [0, 0, min(16, w), t["depth"]])
        else:
            skip += ["east", "west"]
        if lowest:
            faces["down"] = ("rim", [0, 0, min(16, w), t["depth"]])
        else:
            skip.append("down")
        if i in cut:
            skip.append("south")
        parts.append(slab(fr, w, t["depth"], name, inset=t["inset"], extra_h=t["extra_h"],
                          offset=(u0, v0), faces=faces, skip=tuple(skip)))
    return parts


def glow_core() -> list[dict]:
    """The lit back wall behind the face and the glowing cut walls around every hole."""
    x0, y0, x1, y1 = GLOW_BOX
    parts = [box((x0, y0, GLOW_Z), (x1, y1, GLOW_Z + 0.2), "glow", glow=15, shade=False,
                 faces={"south": ("flesh", [0, 0, 11.8, 11.7])}, skip=("east", "west", "up", "down"))]
    width, back = 0.3, GLOW_Z + 0.12
    for poly in world_holes().values():
        for i in range(len(poly)):
            (ax, ay), (bx, by) = poly[i], poly[(i + 1) % len(poly)]
            ln = math.hypot(bx - ax, by - ay)
            ux, uy = (bx - ax) / ln, (by - ay) / ln
            ox, oy = uy * width / 2, -ux * width / 2          # toward the solid rind
            samples = [surface_z(ax + (bx - ax) * s + ox * 2, ay + (by - ay) * s + oy * 2)
                       for s in (0.0, 0.25, 0.5, 0.75, 1.0)]
            front = max(samples) + 0.04
            tex = "wall_lo" if ux < -0.3 else "wall_hi"   # inner normal (-uy, ux): down-facing walls are dimmer
            depth = back - front
            mid = (front + back) / 2
            parts.append(bar((ax + ox, ay + oy, mid), (bx + ox, by + oy, mid), width, depth, tex,
                             glow=15, shade=False, skip=("north", "south", "east")))
    return parts


STEM_PATH = [  # (point, thickness): rises from the dimple and curls over toward the wearer's left
    ((8.0, 17.05, 8.15), 2.6),
    ((7.9, 18.90, 8.4), 2.45),
    ((7.45, 20.50, 8.8), 2.2),
    ((6.6, 21.70, 9.2), 1.95),
    ((5.3, 22.25, 9.4), 1.7),
    ((4.05, 21.85, 9.3), 1.45),
    ((3.45, 20.85, 9.0), 1.25),
]


def stem() -> list[dict]:
    parts = []
    # Dimple: an octagonal underlay below the crown; it is the stem hollow in the middle and
    # shows as dark grooves between the crown's lobes.
    r_in = 4.95
    half = r_in * math.tan(math.pi / 8)
    for i, a in enumerate((0, 45, 90, 135)):
        y = 17.12 + 0.012 * i
        e = box((8 - r_in, y - 0.3, 8 - half), (8 + r_in, y, 8 + half), "cap",
                faces={"up": ("cap", [0, 0, 16, 16])}, skip=("down",))
        parts.append(turn(e, a, "y", (8, 8, 8)) if a else e)
    # Calyx: five woody flares where the stem meets the pumpkin.
    for i in range(5):
        e = box((8.6, 17.15, 7.25), (11.3, 17.75, 8.75), "stem")
        turn(e, -14, "z", (8, 17.7, 8))
        parts.append(turn(e, 90 + 72 * i + 18, "y", (8, 17.7, 8)))
    # The stem: faceted segments with a twist, drying toward the cut tip.
    for i in range(len(STEM_PATH) - 1):
        (p0, t0), (p1, t1) = STEM_PATH[i], STEM_PATH[i + 1]
        d = _unit(_sub(p1, p0))
        a = _v(p0, d, -0.22 * t0 if i else 0.0)
        b = _v(p1, d, 0.22 * t1)
        tex = "stem" if i < 3 else "stem_dry"
        faces = {"up": ("stem_end", [0, 0, 16, 16])} if i == len(STEM_PATH) - 2 else None
        parts.append(bar(a, b, (t0 + t1) / 2, (t0 + t1) / 2, tex, roll=12 * i + 20, faces=faces))
    return parts


def _leaf_panel(v_lo: float, v_hi: float, u_lo: float, u_hi: float) -> dict:
    """Flat panel showing leaf texels v_lo..v_hi (uv units) in the leaf's local frame:
    the petiole at the origin, the leaf pointing to -Z, its right side to +X."""
    bx, bz = LEAF_BASE[0] / TEXELS, LEAF_BASE[1] / TEXELS
    return box((u_lo - bx, 0.0, v_lo - bz), (u_hi - bx, 0.12, v_hi - bz), "leaf",
               faces={"up": ("leaf", [u_lo, v_lo, u_hi, v_hi]),
                      "down": ("leaf_under", [u_lo, v_hi, u_hi, v_lo])},
               skip=("north", "south", "east", "west"))


LEAF_FOLDS = (10.6, 7.6)     # uv v where the leaf bends (base panel | middle | tip)
LEAF_BENDS = (-3.0, -14.0, -30.0)
LEAF_SPAN = (2.0, 14.0)      # uv u the panels cover
LEAF_ATTACH = (9.35, 18.15, 7.3)
LEAF_YAW = -62.0


LEAF_CUP = 16.0              # each half of the leaf lifts this much off the midrib


def leaf() -> list[dict]:
    """One big pumpkin leaf, cupped along its midrib, lying over the crown and bending down
    the front-right shoulder."""
    bx, bz = LEAF_BASE[0] / TEXELS, LEAF_BASE[1] / TEXELS
    lap = 0.15   # panels overlap a little at each fold so the bends never open a crack
    spans = [(LEAF_FOLDS[0] - lap, 14.4), (LEAF_FOLDS[1] - lap, LEAF_FOLDS[0] + lap), (4.4, LEAF_FOLDS[1] + lap)]
    segs = []
    for v_lo, v_hi in spans:
        left = _leaf_panel(v_lo, v_hi, LEAF_SPAN[0], bx)
        right = _leaf_panel(v_lo, v_hi, bx, LEAF_SPAN[1])
        turn(left, -LEAF_CUP, "z", (0, 0, 0))
        turn(right, LEAF_CUP, "z", (0, 0, 0))
        segs.append([left, right])
    fold0 = (0.0, 0.0, LEAF_FOLDS[0] - bz)
    fold1 = (0.0, 0.0, LEAF_FOLDS[1] - bz)
    # Bend from the tip inward so each fold carries everything beyond it.
    turn(segs[2], LEAF_BENDS[2] - LEAF_BENDS[1], "x", fold1)
    turn(segs[1] + segs[2], LEAF_BENDS[1] - LEAF_BENDS[0], "x", fold0)
    parts = segs[0] + segs[1] + segs[2]
    turn(parts, LEAF_BENDS[0], "x", (0, 0, 0))
    turn(parts, LEAF_YAW, "y", (0, 0, 0))
    return kit.move(parts, *LEAF_ATTACH)


def _curve(points, thickness, tex, taper=1.0, glow_tail: int = 0) -> list[dict]:
    """Bars along a polyline, tapering; the last `glow_tail` segments glow toxic green."""
    parts = []
    n = len(points) - 1
    for i in range(n):
        t = thickness * (1 - (1 - taper) * i / max(1, n - 1))
        d = _unit(_sub(points[i + 1], points[i]))
        a = _v(points[i], d, -0.12 * t) if i else points[i]
        b = _v(points[i + 1], d, 0.12 * t)
        if i >= n - glow_tail:
            parts.append(bar(a, b, t, t, "vine_glow", roll=37 * i, glow=10))
        else:
            parts.append(bar(a, b, t, t, tex, roll=37 * i))
    return parts


def _corkscrew(start, axis, side, radius0, radius1, turns, length, segments):
    axis = _unit(axis)
    side = _unit(_v(side, axis, -_dot(side, axis)))
    other = _cross(axis, side)
    pts = []
    for i in range(segments + 1):
        s = i / segments
        ang = 2 * math.pi * turns * s
        r = radius0 + (radius1 - radius0) * s
        centre = _v(start, axis, length * s)
        off = _v(_v((0, 0, 0), side, r * (math.cos(ang) - 1)), other, r * math.sin(ang))
        pts.append(_v(centre, off))
    return pts


def vines() -> list[dict]:
    """A cut length of vine sprawling over the back-left of the crown, ending in corkscrew
    tendrils, plus a small curl springing from the stem on the right."""
    parts = []
    runner = [(7.0, 17.65, 9.2), (5.4, 17.75, 10.6), (3.9, 17.4, 11.9), (2.7, 16.65, 12.9)]
    parts += _curve(runner, 0.75, "vine", taper=0.8)
    parts += _curve(_corkscrew((2.7, 16.8, 12.9), (-0.35, 0.8, 0.5), (1, 0, 0), 0.95, 0.4, 1.8, 4.4, 10),
                    0.42, "vine", taper=0.7, glow_tail=3)
    parts += _curve(_corkscrew((9.2, 18.1, 9.3), (0.55, 0.75, 0.35), (0, 1, 0), 0.8, 0.35, 1.6, 3.6, 8),
                    0.36, "vine", taper=0.7, glow_tail=3)
    return parts


def models() -> dict:
    parts = shell() + glow_core() + stem() + leaf() + vines()
    d = display("helmet", parts, gui_rotation=(12, 160, 0))
    lo, hi = kit.bounds(parts)
    centre = tuple((lo[i] + hi[i]) / 2 for i in range(3))
    # First person: a little smaller and higher than the preset, so the carved face shows.
    d["firstperson_righthand"] = kit.place({"y": (0, 1, 0), "z": (0.45, 0, -1)}, centre,
                                           (0.52, -0.28, -0.95), 0.4, pose=None)
    return {"main": model(parts, d)}
