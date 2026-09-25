"""Winter's Wrath: Winter Limited Edition bow.

Each limb is three layered blades of glacier ice, pointed like overlapping crystal scales,
laminated to a polished silver belly and inlaid with glowing cyan frost runes. A six-armed
snowflake medallion on a hexagonal silver boss, set with a faceted aurora gem, crowns the
riser above a spiral-wrapped navy grip; small aurora studs dress the collars and nock caps,
each limb tip flares into a crown of icicles, and the string is spun frost. The pull states
nock a frozen ice arrow with a glowing crystal head; the limbs flex, a six-spoke frost
burst blooms around the head and the runes burn brighter with every stage.

Built in a local upright frame (limbs along +-Y, the arrow flying toward -X, the string
on the +X side), then turned -45 degrees about Z into the vanilla bow.png sprite frame
so the vanilla bow display transforms apply. The inventory uses a "gui" variant with a
navy outline, and the idle first-person pose turns the snowflake face toward the player.
"""
from __future__ import annotations

import math
import random

from art.kit import bar, box, canvas, display, fill, mirror, model, move, place, rgba, save, turn
from art.kit import copy as copy_el

ID = "winters_wrath"
NAME = "Winter's Wrath"
KIND = "bow"

# --------------------------------------------------------------------------------------
# Palettes, darkest to lightest: shadows sink toward navy/violet, lights toward frost.
# --------------------------------------------------------------------------------------
ICE = ["#132257", "#1c3d88", "#2a63b3", "#4a9fdf", "#86cdf3", "#c6ecfc", "#f2fcff"]
FROST = ["#2f70d2", "#5bb2f4", "#9edcfc", "#d5f4ff", "#ffffff"]
SILVER = ["#1d2446", "#3e4a78", "#6f7fa8", "#a6b4d2", "#d8e2f2", "#ffffff"]
NAVY = ["#0b0f2c", "#151d4c", "#212d6c", "#31438f", "#4a5fb3"]
CYAN = ["#0f6d96", "#19a7d6", "#3fe0f7", "#a0f6ff", "#f0ffff"]
TEAL = ["#0b5557", "#128a82", "#25c4ab", "#6cefcf", "#c6fff0"]
VIOLET = ["#28185f", "#472d9b", "#7350d4", "#a27cf2", "#d4c0ff"]
OUTLINE = "#111a44"

# --------------------------------------------------------------------------------------
# Layout (local frame units; z = 8 is the bow's mid plane)
# --------------------------------------------------------------------------------------
TILT = -45.0                 # local -> vanilla sprite frame
GRIP = (6.4, 9.6)            # model XY of the local origin (the riser centre)
ZC = 8.0
AY, AZ = 2.8, 9.6            # the arrow line: height on the riser and depth (inner side)
MED = (-2.4, 0.0)            # snowflake medallion centre
FLEX = {None: 0.0, 0: 0.3, 1: 0.6, 2: 1.0}
NOCK_X = {0: 7.2, 1: 9.0, 2: 10.8}
ARROW_LEN = 18.6
GLOW = {None: 7, 0: 9, 1: 12, 2: 15}

# --------------------------------------------------------------------------------------
# Strip atlas: every strip texture holds strips 2-6 px wide running the full 32 px
# height, so a bar of any width samples a strip that fits it at 2 texels per unit.
# End caps and small tiles live at x 20-31.
# --------------------------------------------------------------------------------------
STRIPS = {2: 0, 3: 2, 4: 5, 5: 9, 6: 14}
CAP_U = 10.0


def _strip_uv(w: float, length: float, v: float = 0.0) -> list[float]:
    px = min(STRIPS, key=lambda s: abs(s - w * 2))
    x0 = STRIPS[px]
    length = min(length, 16.0)
    v = v % 16.0
    if v + length > 16.0:
        v = 16.0 - length
    return [x0 / 2, round(v, 3), (x0 + px) / 2, round(v + length, 3)]


def _cap_uv(w: float, d: float) -> list[float]:
    return [CAP_U, 0.0, CAP_U + min(w, 6.0), min(d, 6.0)]


def _dist(a, b) -> float:
    return math.sqrt(sum((a[i] - b[i]) ** 2 for i in range(3)))


def sbar(p0, p1, w, d, tex, v=0.0, only=None, **kw) -> dict:
    """bar() whose faces sample the strip that matches their width. The north face is
    flipped so a strip's left column lies on the same side of the bar from both sides."""
    length = _dist(p0, p1)
    front = _strip_uv(w, length, v)
    back = [front[2], front[1], front[0], front[3]]
    side = _strip_uv(d, length, v + 5.3)
    cap = _cap_uv(w, d)
    faces = {"north": (tex, back), "south": (tex, front), "east": (tex, side), "west": (tex, side),
             "up": (tex, cap), "down": (tex, cap)}
    skip = tuple(s for s in faces if only and s not in only)
    return bar(p0, p1, w, d, tex, faces=faces, skip=skip, **kw)


def tile(frm, to, tex, uv, **kw) -> dict:
    """A box whose every face samples the same small tile (mirrored on the north face so
    the tile keeps its orientation in model space from both sides)."""
    faces = {s: list(uv) for s in ("south", "east", "west", "up", "down")}
    faces["north"] = [uv[2], uv[1], uv[0], uv[3]]
    return box(frm, to, tex, uv=faces, **kw)


# --------------------------------------------------------------------------------------
# Texture painting
# --------------------------------------------------------------------------------------

def _put(img, x, y, colour):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.load()[x, y] = rgba(colour)


def _alpha(colour, a: int) -> tuple:
    r, g, b, _ = rgba(colour)
    return (r, g, b, a)


# Column recipes per strip width, back edge (left) to belly edge (right), as ICE indices:
# a dark outline, the frosted rim, luminous body, a deep centre for the runes, lit belly.
ICE_COLS = {2: (1, 4), 3: (1, 5, 3), 4: (1, 5, 3, 2), 5: (1, 5, 4, 2, 3), 6: (1, 5, 4, 3, 2, 3)}


def paint_ice() -> None:
    """Glacier ice strips with clean 45-degree cleavage lines and a few rim glints, plus a
    bevelled crystal-point tile (x 20-27) for the blade ends."""
    img = canvas(32)
    rng = random.Random(11)
    for w, x0 in STRIPS.items():
        cols = ICE_COLS[w]
        for y in range(32):
            for i in range(w):
                _put(img, x0 + i, y, ICE[cols[i]])
        y = rng.randint(1, 4)
        while y < 32:
            for k in range(min(w - 2, 3)):
                _put(img, x0 + 2 + k, y + k, ICE[5] if k == 0 else ICE[4])
            y += rng.randint(6, 9)
        for _ in range(3):
            _put(img, x0 + (1 if w > 2 else 0), rng.randrange(32), ICE[6])
    # crystal point tile (8x8). Placed by point(), the texture's bottom-right corner is
    # the forward tip, top-right the barb and bottom-left the belly corner, so the
    # silhouette edges (top row, right column) get the dark outline, the edge lying over
    # the next blade (bottom row) a bright rim, and the tip a glint.
    for y in range(8):
        for x in range(8):
            c = ICE[4] if x + (7 - y) < 8 else ICE[3]
            if y == 7:
                c = ICE[5]
            if x == 7 or y == 0:
                c = ICE[1]
            _put(img, 20 + x, y, c)
    _put(img, 26, 6, ICE[6])
    _put(img, 25, 6, ICE[5])
    _put(img, 26, 5, ICE[5])
    save(img, "ice")


# Frost crystal columns (FROST indices), lit face to shaded face.
FROST_COLS = {2: (3, 1), 3: (4, 2, 1), 4: (4, 3, 2, 1), 5: (4, 3, 2, 2, 1), 6: (4, 4, 3, 2, 2, 1)}
FROST_TIP_UV = (0, 0, 1, 1)          # glassy tip zone of the 2 px frost strip
FLAKE_TIP_UV = (10, 6, 12, 8)        # bright crystal point tile (4x4 at 20,12)
FLAKE_PLATE_UV = (12.5, 6, 15.5, 9)  # brushed silver plate (6x6 at 25,12)
# Snowflake arms: a white core between two frost edges.
FLAKE_COLS = {2: ("#ffffff", FROST[2]), 3: (FROST[2], "#ffffff", FROST[2]),
              4: (FROST[1], FROST[3], "#ffffff", FROST[2]), 5: (FROST[1], FROST[3], "#ffffff", FROST[3], FROST[1]),
              6: (FROST[1], FROST[2], FROST[4], "#ffffff", FROST[2], FROST[1])}


def paint_frost() -> None:
    """Frosted crystal: icicle strips shading from a lit face to a cold shadow, glassy
    translucent points in rows 0-5, snowflake strips with a white core (x 20+ tiles:
    the crystal point and the medallion's brushed silver plate)."""
    img = canvas(32)
    rng = random.Random(23)
    for w, x0 in STRIPS.items():
        cols = FROST_COLS[w]
        for y in range(32):
            for i in range(w):
                c = FROST[cols[i]]
                if y < 6:
                    c = FROST[min(4, cols[i] + (1 if y < 3 else 0))]
                    _put(img, x0 + i, y, _alpha(c, (150, 185, 210, 232, 246, 255)[y]))
                else:
                    _put(img, x0 + i, y, c)
        for y in range(8, 32, 5):
            _put(img, x0, y + rng.randint(0, 2), "#ffffff")
        for y in range(10, 32, 6):
            _put(img, x0 + w - 1, y + rng.randint(0, 2), ICE[3])
    # crystal point tile: bright forward tip (bottom-right), outlined back edges
    for y in range(4):
        for x in range(4):
            c = FROST[3] if x + y < 4 else FROST[2]
            if x == 3 or y == 0:
                c = FROST[1]
            _put(img, 20 + x, 12 + y, c)
    _put(img, 23, 15, "#ffffff")
    _put(img, 22, 15, FROST[4])
    _put(img, 23, 14, FROST[4])
    # brushed silver plate for the hexagonal boss
    for y in range(6):
        for x in range(6):
            _put(img, 25 + x, 12 + y, SILVER[4] if (x + 2 * y) % 5 == 0 else SILVER[3])
    save(img, "frost")

    flake = canvas(32)
    for w, x0 in STRIPS.items():
        for y in range(32):
            for i, c in enumerate(FLAKE_COLS[w]):
                _put(flake, x0 + i, y, c)
    save(flake, "flake")


SILVER_COLS = {2: (4, 2), 3: (5, 3, 1), 4: (2, 5, 3, 1), 5: (2, 5, 4, 3, 1), 6: (2, 5, 4, 3, 2, 1)}


def paint_silver() -> None:
    """Polished cold silver with engraved bands inlaid with frost enamel."""
    img = canvas(32)
    rng = random.Random(5)
    for w, x0 in STRIPS.items():
        cols = SILVER_COLS[w]
        for y in range(32):
            for i in range(w):
                _put(img, x0 + i, y, SILVER[cols[i]])
        y = rng.randint(3, 6)
        while y < 31:
            for i in range(w):
                _put(img, x0 + i, y, SILVER[1])
                _put(img, x0 + i, y + 1, SILVER[5] if cols[i] >= 4 else SILVER[4])
            if w >= 3:
                _put(img, x0 + w // 2, y, CYAN[2])
            y += rng.randint(7, 10)
        for _ in range(2):
            _put(img, x0 + (1 if w > 2 else 0), rng.randrange(32), "#ffffff")
    fill(img, (20, 0, 31, 11), SILVER[3])
    fill(img, (20, 0, 31, 0), SILVER[4])
    fill(img, (20, 0, 20, 11), SILVER[4])
    fill(img, (21, 11, 31, 11), SILVER[1])
    fill(img, (31, 1, 31, 11), SILVER[1])
    fill(img, (22, 2, 23, 3), SILVER[5])
    save(img, "silver")


def paint_leather() -> None:
    """Navy leather strap: a lit upper edge pricked with frost-silver stitches, then the
    body darkening toward the edge tucked under the next wrap."""
    img = canvas(32)
    rows = (NAVY[4], NAVY[3], NAVY[2], NAVY[1])
    for y in range(32):
        for x in range(32):
            _put(img, x, y, rows[y % 4])
            if y % 4 == 0 and x % 3 == 1:
                _put(img, x, y, SILVER[3])
            if y % 4 == 2 and x % 7 == 3:
                _put(img, x, y, NAVY[3])
    save(img, "leather")


RUNES3 = [[".#.", "###", ".#."], ["#.#", ".#.", "#.#"], ["###", "#.#", "###"], ["#..", "###", "..#"],
          ["..#", "###", "#.."], ["###", ".#.", ".#."], [".#.", "#.#", ".#."]]
RUNES2 = [["##", "#.", "##"], [".#", "##", "#."], ["#.", "##", ".#"], ["##", ".#", "##"], ["#.", "#.", "##"]]


def paint_runes(name: str, hot: bool) -> None:
    """Cutout frost runes: a glyph every five rows, joined by a faint vein."""
    img = canvas(32)
    rng = random.Random(41)
    body, lit, vein = (CYAN[3], "#ffffff", CYAN[2]) if hot else (CYAN[2], CYAN[3], CYAN[1])
    for w, glyphs in ((2, RUNES2), (3, RUNES3)):
        x0 = STRIPS[w]
        y = 1
        last = None
        while y < 32:
            glyph = rng.choice([g for g in glyphs if g is not last])
            last = glyph
            for r, row in enumerate(glyph):
                for i, ch in enumerate(row):
                    if ch == "#":
                        _put(img, x0 + i, y + r, lit if (r == 0 or (r == 1 and i == w // 2)) else body)
            for g in (3, 4):
                _put(img, x0 + w // 2, y + g, _alpha(vein, 200))
            y += 5
    save(img, name)


def paint_string(name: str, hot: bool) -> None:
    """Spun frost: a bright cord with a slow twist of lighter fibre."""
    img = canvas(32)
    base, twist = (CYAN[3], "#ffffff") if hot else (CYAN[2], "#7eeefc")
    for y in range(32):
        _put(img, 0, y, twist if y % 3 == 0 else base)
        _put(img, 1, y, twist if y % 3 == 1 else base)
    save(img, name)


def paint_outline() -> None:
    """The flat navy used for the inventory icon's outline plates."""
    save(canvas(32, fill=OUTLINE), "outline")


def paint_gem() -> None:
    """Aurora gem: the pavilion (5x5 at 0,0), violet shading to teal, and the raised table
    (3x3 at 8,0) with its white glint. Lit edges are the top row and left column."""
    pav = [
        [VIOLET[4], VIOLET[4], VIOLET[3], TEAL[4], TEAL[3]],
        [VIOLET[4], VIOLET[3], VIOLET[2], TEAL[3], TEAL[2]],
        [VIOLET[3], VIOLET[2], VIOLET[2], TEAL[2], TEAL[1]],
        [TEAL[4], TEAL[3], TEAL[2], TEAL[1], VIOLET[1]],
        [TEAL[3], TEAL[2], TEAL[1], VIOLET[1], VIOLET[0]],
    ]
    table = [["#ffffff", VIOLET[4], TEAL[4]], [VIOLET[4], VIOLET[3], TEAL[3]], [TEAL[4], TEAL[3], TEAL[2]]]
    img = canvas(32)
    for y, row in enumerate(pav):
        for x, c in enumerate(row):
            _put(img, x, y, c)
    for y, row in enumerate(table):
        for x, c in enumerate(row):
            _put(img, 8 + x, y, c)
    save(img, "gem")


def paint_arrow() -> None:
    """Shaft (u runs along the arrow), aurora crystal vanes, the glowing head and aura."""
    shaft = canvas(32)
    for x in range(32):
        for y, c in enumerate((FROST[3], FROST[2], ICE[3])):
            _put(shaft, x, y, c)
        if x % 5 == 0:
            _put(shaft, x, 0, "#ffffff")
            _put(shaft, x, 1, CYAN[2])
    save(shaft, "shaft")

    fletch = canvas(32)
    shape = ["......aabbc", "...aabbbccd", "aaabbbcccdd"]
    grad = {"a": TEAL[3], "b": TEAL[2], "c": VIOLET[3], "d": VIOLET[2]}
    for y, row in enumerate(shape):
        for x, ch in enumerate(row):
            if ch != ".":
                _put(fletch, x, y, grad[ch])
    for x, ch in enumerate(shape[0]):
        if ch != ".":
            _put(fletch, x, 0, FROST[4] if x % 3 else TEAL[4])
    save(fletch, "fletch")

    for name, hot in (("head", False), ("head_hot", True)):
        head = canvas(32)
        rows = (CYAN[3], CYAN[2], CYAN[2], CYAN[1]) if not hot else ("#ffffff", CYAN[3], CYAN[2], CYAN[2])
        for x in range(32):
            for y, c in enumerate(rows):
                _put(head, x, y, c)
            if x % 3 == 0:
                _put(head, x, 1, CYAN[4] if not hot else "#ffffff")
        save(head, name)

    aura = canvas(32)
    for x in range(32):
        _put(aura, x, 0, _alpha(FROST[4], 225))
        _put(aura, x, 1, _alpha(CYAN[3], 200))
    save(aura, "aura")


def textures() -> None:
    paint_ice()
    paint_frost()
    paint_silver()
    paint_leather()
    paint_runes("rune", hot=False)
    paint_runes("rune_hot", hot=True)
    paint_string("string", hot=False)
    paint_string("string_hot", hot=True)
    paint_outline()
    paint_gem()
    paint_arrow()


# --------------------------------------------------------------------------------------
# Geometry (local frame)
# --------------------------------------------------------------------------------------

def _bez(p0, p1, p2, t):
    a, b, c = (1 - t) ** 2, 2 * (1 - t) * t, t * t
    return (a * p0[0] + b * p1[0] + c * p2[0], a * p0[1] + b * p1[1] + c * p2[1])


def _bez_d(p0, p1, p2, t):
    return (2 * (1 - t) * (p1[0] - p0[0]) + 2 * t * (p2[0] - p1[0]),
            2 * (1 - t) * (p1[1] - p0[1]) + 2 * t * (p2[1] - p1[1]))


def _unit(v):
    n = math.hypot(v[0], v[1])
    return (v[0] / n, v[1] / n)


def _rot(v, deg):
    a = math.radians(deg)
    return (v[0] * math.cos(a) - v[1] * math.sin(a), v[0] * math.sin(a) + v[1] * math.cos(a))


def _add(p, v, s=1.0):
    return (p[0] + v[0] * s, p[1] + v[1] * s)


def _ang(v) -> float:
    return math.degrees(math.atan2(v[1], v[0]))


def P(xy, z=ZC):
    return (xy[0], xy[1], z)


def point(centre, direction, diag, depth, tex, uv, zc=ZC, **kw) -> dict:
    """A square turned 45 degrees to `direction`: a crisp 90-degree crystal point whose
    back corners match a bar of width `diag` ending at `centre`."""
    s = diag / math.sqrt(2) / 2
    el = tile((centre[0] - s, centre[1] - s, zc - depth / 2), (centre[0] + s, centre[1] + s, zc + depth / 2),
              tex, uv, **kw)
    return turn(el, _ang(direction) + 45, "z", (centre[0], centre[1], zc))


def spike(base, direction, lengths, widths, curl=0.0, glow=0, tex="frost", tip_uv=FROST_TIP_UV,
          v0=4.0) -> list[dict]:
    """A tapering crystal: bars narrowing (and curling) to a sharp point."""
    parts = []
    p = base
    d = _unit(direction)
    for k, (ln, w) in enumerate(zip(lengths, widths)):
        q = _add(p, d, ln)
        parts.append(sbar(P(_add(p, d, -0.2 if k else 0.0)), P(q), w, w * 0.9 + 0.2, tex, v=v0 + k * 4.0,
                          glow=glow))
        p = q
        d = _rot(d, curl)
    parts.append(point(p, d, widths[-1], widths[-1] * 0.9 + 0.1, tex, tip_uv, glow=glow))
    return parts


def limb_curve(flex: float):
    root = (1.2, 3.4)
    ctrl = (1.3 + 0.3 * flex, 8.0 - 0.3 * flex)
    tip = (4.8 + 1.4 * flex, 10.6 - 0.8 * flex)
    return root, ctrl, tip


# (start t, end t, width, depth) of the three layered ice blades
BLADES = ((0.0, 0.46, 3.0, 2.4), (0.36, 0.76, 2.4, 2.1), (0.66, 1.0, 1.8, 1.8))
ICE_POINT_UV = (10.0, 0.0, 14.0, 4.0)
GEM_STUD_UV = (4.0, 0.0, 5.5, 1.5)    # the gem's table tile, for the small studs


def upper_limb(flex: float, glow: int, rune: str) -> tuple[list[dict], tuple]:
    """The upper limb in the local frame, plus its string anchor."""
    p0, p1, p2 = limb_curve(flex)
    Q = lambda t: _bez(p0, p1, p2, t)
    T = lambda t: _unit(_bez_d(p0, p1, p2, t))
    N = lambda t: (T(t)[1], -T(t)[0])          # belly normal, toward the string
    parts: list[dict] = []
    # silver belly laminate: runs the whole limb just proud of the ice's belly edge
    for k in range(5):
        t0, t1 = k / 5, (k + 1) / 5
        a = _add(_add(Q(t0), N(t0), 0.15), T(t0), -0.2)
        b = _add(_add(Q(t1), N(t1), 0.15), T(t1), 0.2)
        parts.append(sbar(P(a), P(b), 0.9, 1.7 - 0.15 * k, "silver", v=k * 2.2))
    # three ice blades hung back from the belly line, layered like crystal scales; the
    # first two end in crystal points that overlap the next, narrower blade
    for n, (s, e, w, d) in enumerate(BLADES):
        off = w / 2 - 0.25
        ts = [s, (s + e) / 2, e]
        for k in range(2):
            ta, tb = ts[k], ts[k + 1]
            a = _add(_add(Q(ta), N(ta), -off), T(ta), -0.15 if (k or n) else -0.9)
            b = _add(_add(Q(tb), N(tb), -off), T(tb), 0.15)
            dk = d + 0.08 * k            # staggered so the overlapping joint never z-fights
            parts.append(sbar(P(a), P(b), w, dk, "ice", v=n * 5.1 + k * 2.4))
            # rune inlay; each plate stops short of the joint so no two plates overlap
            ra = _add(_add(Q(ta), N(ta), -off), T(ta), 0.3 if k == 0 else 0.22)
            rb = _add(_add(Q(tb), N(tb), -off), T(tb), -0.3 if k == 1 else -0.4)
            if n == 2 and k == 1:
                rb = _add(_add(Q(tb - 0.1), N(tb - 0.1), -off), T(tb), 0.0)
            parts.append(sbar(P(ra), P(rb), 1.5 if w > 2 else 1.0, dk + 0.1, rune, v=n * 4.7 + k * 2.1,
                              only=("north", "south"), glow=glow))
        if n < 2:
            # the blade ends in a crystal point, set back so its rear corner juts out as a
            # small barb over the next, narrower blade
            end = _add(Q(e), N(e), -off - 0.3)
            parts.append(point(end, T(e), w + 0.5, d, "ice", ICE_POINT_UV))
    # silver collar where the limb leaves the riser
    c0 = _add(Q(0.0), N(0.0), -1.25)
    parts.append(sbar(P(_add(c0, T(0), -0.45)), P(_add(c0, T(0), 0.5)), 3.8, 2.9, "silver", v=3.0))
    stud = _add(_add(c0, T(0), 0.02), N(0.0), -0.2)
    parts.append(point(stud, (1.0, -1.0), 1.3, 3.3, "gem", GEM_STUD_UV, glow=glow))
    # the nock cap and the icicle crown
    tip, t1 = Q(1.0), T(1.0)
    n1 = N(1.0)
    cap_c = _add(tip, n1, -0.5)
    parts.append(sbar(P(_add(cap_c, t1, -0.6)), P(_add(cap_c, t1, 0.9)), 2.2, 2.2, "silver", v=7.0))
    parts.append(point(_add(cap_c, t1, 0.15), (1.0, -1.0), 1.1, 2.6, "gem", GEM_STUD_UV, glow=glow))
    crown = _add(cap_c, t1, 0.8)
    cg = max(5, glow - 4)                     # the icicles catch more frost-light as it draws
    parts += spike(crown, _rot(t1, 38), (1.9, 1.6, 1.2), (1.6, 1.15, 0.7), curl=12.0, glow=cg)
    parts += spike(_add(crown, n1, -0.6), _rot(t1, 102), (1.2, 0.9), (1.1, 0.6), curl=10.0, glow=cg)
    parts += spike(_add(crown, n1, 0.5), _rot(t1, -28), (0.9, 0.7), (0.9, 0.5), curl=-8.0, glow=cg)
    parts += spike(_add(_add(tip, n1, 0.55), t1, -0.3), _rot(t1, 196), (0.8,), (0.55,), glow=cg)
    parts += spike(_add(_add(tip, n1, 0.5), t1, 0.5), _rot(t1, 214), (0.6,), (0.4,), glow=cg)
    anchor = _add(_add(tip, n1, 0.7), t1, 0.35)
    return parts, anchor


def riser() -> list[dict]:
    parts = [sbar((0, -3.6, ZC), (0, 3.6, ZC), 2.4, 2.0, "silver", v=1.0)]
    # four spiral straps; each lower wrap overlaps the one above it
    for k, c in enumerate((-2.0, -1.0, 0.0, 1.0)):
        grow = 0.06 * (3 - k)
        strap = box((-0.1 - grow, c - 0.7, 6.75 - grow), (1.75 + grow, c + 0.7, 9.25 + grow), "leather")
        parts.append(turn(strap, 11, "z", (0.8, c, ZC)))
    for y0, y1 in ((1.4, 1.9), (-3.0, -2.5)):
        parts.append(sbar((0.8, y0, ZC), (0.8, y1, ZC), 2.2, 3.1, "silver", v=9.0))
    return parts


def medallion(glow: int) -> list[dict]:
    cx, cy = MED
    parts = []
    # five visible arms (the sixth hides in the riser), each with a V of dendrite branches
    # and a crystal point; the arms carry a faint frost glow that grows with the draw
    ag = max(3, glow - 3)
    for ang in (60, 120, 180, 240, 300):
        u = (math.cos(math.radians(ang)), math.sin(math.radians(ang)))
        parts.append(sbar(P(_add(MED, u, 1.8)), P(_add(MED, u, 4.3)), 1.5, 1.5, "flake", v=6.0, glow=ag))
        base = _add(MED, u, 3.0)
        for side, bd in ((56, 1.1), (-56, 1.2)):
            parts.append(sbar(P(_add(base, u, -0.2)), P(_add(base, _rot(u, side), 1.35)), 0.6, bd, "flake",
                              v=9.0, glow=ag))
        parts.append(point(_add(MED, u, 4.3), u, 1.5, 1.4, "frost", FLAKE_TIP_UV, glow=ag))
    # hexagonal silver boss (three rotated plates), true to an ice crystal's habit
    R = 2.2
    for k, depth in enumerate((2.12, 2.24, 2.36)):     # proud of the riser (2.0), staggered
        plate = tile((cx - R / 2, cy - R * 0.866, ZC - depth / 2), (cx + R / 2, cy + R * 0.866, ZC + depth / 2),
                     "frost", FLAKE_PLATE_UV)
        parts.append(turn(plate, 60 * k, "z", (cx, cy, ZC)))
    # the faceted aurora gem: pavilion and raised table, square to the local frame so
    # they read as diamonds once the bow is tilted
    gg = min(15, glow + 3)
    parts.append(tile((cx - 1.15, cy - 1.15, 6.7), (cx + 1.15, cy + 1.15, 9.3), "gem", (0, 0, 2.5, 2.5), glow=gg))
    parts.append(tile((cx - 0.65, cy - 0.65, 6.45), (cx + 0.65, cy + 0.65, 9.55), "gem", (4, 0, 5.5, 1.5), glow=gg))
    return parts


def string(anchor, draw, width=0.5) -> list[dict]:
    lo = (anchor[0], -anchor[1])
    tex = "string_hot" if draw == 2 else "string"
    glow = {None: 6, 0: 8, 1: 11, 2: 14}[draw]
    if draw is None:
        mid = P((anchor[0], 0.0))
        pts = [(P(anchor), mid), (mid, P(lo))]
    else:
        nock = (NOCK_X[draw] + 0.2, AY, AZ)
        pts = [(P(anchor), nock), (nock, P(lo))]
    parts = []
    for a, b in pts:
        L = min(_dist(a, b), 16.0)
        uv = {"north": [0, 0, 0.5, L], "south": [0, 0, 0.5, L], "east": [0.5, 0, 1.0, L],
              "west": [0.5, 0, 1.0, L], "up": [0, 0, 0.5, 0.5], "down": [0, 0, 0.5, 0.5]}
        parts.append(bar(a, b, width, width, tex, uv=uv, glow=glow))
    return parts


def arrow(draw) -> list[dict]:
    xn = NOCK_X[draw]
    head_len = 3.0
    xh = xn - ARROW_LEN + head_len          # where the head meets the shaft
    y, z = AY, AZ
    parts = []
    # shaft and nock
    L = xn - 0.8 - xh
    parts.append(box((xh, y - 0.5, z - 0.5), (xn - 0.8, y + 0.5, z + 0.5), "shaft",
                     uv={"north": [L, 0, 0, 1.5], "south": [0, 0, L, 1.5], "up": [0, 0, L, 1.5],
                         "down": [0, 0, L, 1.5], "east": [0, 0, 1, 1], "west": [0, 0, 1, 1]}))
    parts.append(sbar((xn - 1.0, y, z), (xn + 0.3, y, z), 1.3, 1.3, "silver", v=2.0))
    for bx in (xn - 5.1, xh + 1.0):
        parts.append(sbar((bx, y, z), (bx + 0.5, y, z), 1.3, 1.3, "silver", v=11.0))
    # three aurora crystal vanes
    for roll in (90, 210, 330):
        vane = box((xn - 4.6, y + 0.45, z - 0.07), (xn - 1.1, y + 1.95, z + 0.07), "fletch",
                   uv={"south": [0, 0, 5.5, 1.5], "north": [5.5, 0, 0, 1.5]}, skip=("east", "west", "up", "down"))
        parts.append(turn(vane, roll - 90, "x", (0, y, z)))
    # the glowing crystal head, diamond in section, stepping to a point
    hg = GLOW[draw]
    head_tex = "head_hot" if draw == 2 else "head"
    parts.append(sbar((xh + 0.4, y, z), (xh - 0.3, y, z), 1.5, 1.5, "silver", v=13.0))
    for x0, x1, r in ((xh - 0.2, xh - 1.4, 0.85), (xh - 1.3, xh - 2.3, 0.6), (xh - 2.2, xh - 3.0, 0.32)):
        uv = {k: [0, 0, abs(x0 - x1), 2 * r] for k in ("north", "south", "up", "down")}
        uv.update({k: [0, 0, 2 * r, 2 * r] for k in ("east", "west")})
        seg = box((x1, y - r, z - r), (x0, y + r, z + r), head_tex, uv=uv, glow=hg)
        parts.append(turn(seg, 45, "x", (0, y, z)))
    # frost barbs swept back from the head
    for sy in (1, -1):
        b0, b1 = (xh - 1.1, y + 0.3 * sy), (xh + 0.5, y + 1.45 * sy)
        parts.append(sbar(P(b0, z), P(b1, z), 0.6, 0.6, "frost", v=12.0, glow=hg))
        parts.append(point(b1, _unit((b1[0] - b0[0], b1[1] - b0[1])), 0.6, 0.48, "frost", FROST_TIP_UV, glow=hg,
                           zc=z))
    # a six-spoke frost burst blooms around the head as the bow is drawn: from the
    # archer's eye, looking down the shaft, it is a snowflake
    if draw >= 1:
        ln = 1.1 if draw == 1 else 1.9
        for k in range(6):
            a = math.radians(30 + 60 * k)
            dvec = (0.3, math.cos(a), math.sin(a))
            norm = math.sqrt(sum(c * c for c in dvec))
            dvec = tuple(c / norm for c in dvec)
            p0 = (xh - 1.2, y, z)
            p1 = tuple(p0[i] + dvec[i] * ln for i in range(3))
            parts.append(bar(p0, p1, 0.45, 0.45, "aura", uv={s: [0, 0, 1, 1] for s in
                                                              ("north", "south", "east", "west", "up", "down")},
                             glow=15))
    return parts


def outline(parts, grow=0.8, z=5.4) -> list[dict]:
    """Flat dark plates behind the model, each a little larger than its element, so the
    inventory icon gets a crisp outline like a vanilla sprite. Everything here rotates
    about Z only, so the plates stay flat."""
    out = []
    for e in parts:
        c = copy_el(e)
        (x0, y0, _), (x1, y1, _) = c["from"], c["to"]
        c["from"] = [round(x0 - grow, 4), round(y0 - grow, 4), z]
        c["to"] = [round(x1 + grow, 4), round(y1 + grow, 4), z + 0.1]
        c["faces"] = {"south": {"uv": [0.0, 0.0, 1.0, 1.0], "texture": "#outline"}}
        c.pop("light_emission", None)
        c["shade"] = False
        out.append(c)
    return out


def bow(draw=None, gui=False) -> list[dict]:
    flex = FLEX[draw]
    glow = GLOW[draw]
    rune = "rune_hot" if draw == 2 else "rune"
    limb, anchor = upper_limb(flex, glow, rune)
    body = riser() + medallion(glow) + limb + mirror(limb, "y", 0.0)
    parts = body + string(anchor, draw, width=0.9 if gui else 0.5)
    if draw is not None:
        parts += arrow(draw)
    if gui:
        parts = outline(body) + parts
    turn(parts, TILT, "z", (0, 0, ZC))
    move(parts, GRIP[0], GRIP[1], 0)
    return parts


def _showcase_first_person() -> dict:
    """Idle first-person pose: the bow held upright at the right of the screen with its
    snowflake face turned toward the player (vanilla shows a bow almost edge-on). The pull
    states keep the vanilla transform so the game's draw animation aims the arrow."""
    import numpy as np
    up = np.array([0.2, 0.96, -0.2])                  # the upper limb, in camera space
    up /= np.linalg.norm(up)
    face = np.array([-0.6, 0.0, 0.8])                 # the snowflake face (+Z)
    face -= up * (up @ face)
    face /= np.linalg.norm(face)
    belly = np.cross(up, face)                        # the string side (local +X)
    x_axis, y_axis = 0.7071 * (up + belly), 0.7071 * (up - belly)
    return place({"x": tuple(x_axis), "y": tuple(y_axis)}, (GRIP[0], GRIP[1], ZC), (0.56, -0.44, -0.85), 0.68,
                 pose=None)


def models() -> dict:
    idle = bow(None)
    disp = display("bow", idle)
    disp["firstperson_righthand"] = _showcase_first_person()
    out = {"idle": model(idle, disp)}
    for k in range(3):
        parts = bow(k)
        out[f"pull_{k}"] = model(parts, display("bow", parts))
    icon = bow(None, gui=True)
    out["gui"] = model(icon, display("bow", icon, gui_span=16.0))
    return out
