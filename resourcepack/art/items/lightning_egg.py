"""Lightning Egg: a storm-cloud egg crowned by a copper lightning rod (Egg set).

The shared egg body (the EGG_PROFILE lathe) wears a moody storm-cloud glaze: slate greys
drifting to indigo in shadow, baked light from the front left (seamless on the half-turn
map), soft cloud lobes with silver linings, and a dark storm base. It is unshaded so the
lathe steps melt into one round shell.

On the egg's shoulder sits a copper lightning-rod fitting: a weathered bronze band with a
zigzag of teeth in relief and a verdigris bloom, a riveted lip, a drum, and a paneled
dome of thirty-two leaning copper panels with standing seams. From its boss rise two coil
turns, a blue glass insulator ball, a knob and a spike. A square copper wire bent into a
lightning bolt runs from under the band down the front left of the egg (and the back
right) to a point at the belly. Every copper face is baked from its real normal.

Animation (one 3.2 s loop, 32 frames x 2 ticks, every texture in step): five strikes in
turn, placed where the inventory view sees them. Each big strike sends a faint leader
down from the crown, then a white-hot stroke that washes the cloud with light and lands
with a small burst; it dims, restrikes with new forks, then breaks up into crackling
sparks. The rod's spike flares a star, the glass ball gathers charge and flashes, and the
copper catches a warm glint as each lands. Between them a crackle races across the belly,
a bolt leaps from the wire's point, and jagged arcs jump off the egg's flanks.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, bar, box, canvas, display, fit, lathe, mix, model, place, prism, rgba, rotation_of,
                     save, save_animation, turn)

ID = "lightning_egg"
NAME = "Lightning Egg"
KIND = "item"
COUNTERPART = "item/egg"

# --------------------------------------------------------------------------------------
# The shared egg body (every egg in the set is this lathe)
# --------------------------------------------------------------------------------------
EGG_PROFILE = [(0, 1.4), (0.7, 3.2), (1.8, 4.3), (3.3, 4.9), (5.0, 5.0), (6.8, 4.7), (8.4, 4.0),
               (9.8, 3.0), (10.9, 1.8), (11.5, 0.6)]
CENTRE = (8.0, 2.2, 8.0)
CX, CY, CZ = CENTRE
SLICES = [(h0, h1, (r0 + r1) / 2) for (h0, r0), (h1, r1) in zip(EGG_PROFILE, EGG_PROFILE[1:])]

# The shell map: 32 columns per half turn (4 per 22.5-degree facet; the map repeats on the
# back half), 24 rows from the tip down (about two texels per unit, so they stay square).
N = 32
ROWS = [1, 2, 3, 4, 4, 4, 3, 2, 1]                  # rows per slice, bottom slice first
ROW0 = [sum(ROWS[k + 1:]) for k in range(len(ROWS))]  # first row (from the top) of each slice
SHELL_ROWS = sum(ROWS)
FRONT_PHI = 90.0              # the model's front (+Z); phi runs from +X toward +Z
# Azimuth at u = 0 for 16- and 8-sided parts: whole facets land on whole columns, the
# front one in the middle of the map. The inventory view is turned 20 degrees from the
# front, so it faces azimuth 110.
ORIGIN = {16: FRONT_PHI + 78.75, 8: FRONT_PHI + 67.5}
TAN16 = math.tan(math.radians(11.25))

FRAMES, FRAMETIME = 32, 2     # one 3.2 s loop for every animated texture
GUI_ROTATION = (10, 20, 0)
SIZE = 1.3
GRIP = (8.0, 6.4, 8.0)

# The copper crown (egg heights h, apothems r) sits on the egg's shoulder: a band with a
# zigzag of teeth in relief under a riveted lip, a paneled roof with standing seams, then
# the lightning rod.
BAND = (9.72, 10.1, 3.1)
LIP = (10.1, 10.36, 3.22)
TOOTH = (3.08, 3.19, 0.4)                # inner and outer apothem, half-diagonal
DRUM = (10.36, 10.86, 2.98)
ROOF = ((2.98, 10.86), (2.3, 11.36), (1.2, 11.78))  # (apothem, height) of each panel edge
PANEL = 0.16
BOSS = ((11.7, 11.94, 1.28), (11.94, 12.14, 0.8))
ROD = (12.14, 14.04, 0.4)
COILS = (12.26, 12.52)
BALL_H, BALL_R = 13.24, 1.0
HEAD = (14.04, 14.32, 0.5)
TIP = ((14.32, 14.62, 0.3), (14.62, 14.9, 0.14))
STAR_ARM = 0.62

# --------------------------------------------------------------------------------------
# Palettes, darkest -> lightest (shadows drift to indigo, lights to a warm grey)
# --------------------------------------------------------------------------------------
STORM = ["#0f101b", "#171928", "#202335", "#2a2e43", "#353a51", "#424861", "#505771", "#606882",
         "#727a93", "#868ea5", "#9ca3b7", "#b6bbca", "#d3d6df", "#eff0f4"]
COPPER = ["#240d08", "#401a0f", "#622815", "#88391c", "#ad4d23", "#cc632c", "#e27d3a", "#f19d52",
          "#f9be74", "#fedda6", "#fff6e2"]
PATINA = ["#10332f", "#1a5145", "#277060", "#3b927b", "#5bb498"]
GLASS = ["#060f26", "#0b1d46", "#12306e", "#1b4899", "#2a66c2", "#4a8ae0", "#7fb2f4", "#c3e0ff", "#ffffff"]
WHITE, PALE, YELLOW, GOLD, AMBER, ORANGE = "#ffffff", "#ffffb0", "#ffff00", "#ffd21c", "#ffa11a", "#e8680c"
CLEAR = (0, 0, 0, 0)


def clamp(v, lo=0.0, hi=1.0):
    return max(lo, min(hi, v))


BAYER = (0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)


def dith(x: int, y: int) -> float:
    return (BAYER[(y % 4) * 4 + x % 4] + 0.5) / 16.0 - 0.5


# --------------------------------------------------------------------------------------
# Map geometry and the baked light (shared by the shell and the copper)
# --------------------------------------------------------------------------------------

def col_phi(x: float, sides: int = 16) -> float:
    """Azimuth under map column x (its centre), on the half facing the inventory camera."""
    phi = ORIGIN[sides] - (x + 0.5) * 180.0 / N
    while phi < FRONT_PHI - 90:
        phi += 180
    while phi >= FRONT_PHI + 90:
        phi -= 180
    return phi


def u_left(phi_c: float, sides: int = 16) -> float:
    """u (0..16) of the left edge, seen from outside, of the facet facing phi_c."""
    return round(((ORIGIN[sides] - (phi_c + 180.0 / sides)) % 180.0) * 16.0 / 180.0, 4)


def facet_col(phi_c: float) -> int:
    """First map column of the 16-sided facet facing phi_c."""
    return round(u_left(phi_c) * 2)


KEY_PHI = 128.0     # the front left of the inventory view is lit hardest
SPEC_PHI = 116.0


def lit(phi: float, ny: float) -> float:
    """0..1 baked light for a surface facing azimuth phi with vertical normal part ny.
    Round the egg it follows one cosine per half turn, so it is seamless where maps repeat."""
    around = math.cos(math.radians(2 * (phi - KEY_PHI)))
    return clamp(0.47 + 0.34 * ny + 0.27 * around * math.sqrt(max(0.0, 1 - ny * ny)))


def spec(phi: float, ny: float, sharp: float = 10.0, ny0: float = 0.3) -> float:
    """A glossy highlight on the front left shoulder."""
    around = max(0.0, math.cos(math.radians(2 * (phi - SPEC_PHI))))
    return around ** sharp * math.exp(-((ny - ny0) / 0.45) ** 2)


def slice_of_row(y: int):
    """(slice, top height, bottom height) of shell map row y."""
    for k in range(len(SLICES)):
        if ROW0[k] <= y < ROW0[k] + ROWS[k]:
            h0, h1, _ = SLICES[k]
            dh = (h1 - h0) / ROWS[k]
            i = y - ROW0[k]
            return k, h1 - i * dh, h1 - (i + 1) * dh
    raise ValueError(y)


def row_h(y: float) -> float:
    """Egg height at (fractional) map row y, measured from the top of the map."""
    yi = int(math.floor(clamp(y, 0, SHELL_ROWS - 1e-6)))
    _, ht, hb = slice_of_row(yi)
    return ht - (ht - hb) * (y - yi)


_SMOOTH = [(-0.3, 0.0)] + EGG_PROFILE + [(11.75, 0.0)]


def radius(h: float) -> float:
    if h <= _SMOOTH[0][0] or h >= _SMOOTH[-1][0]:
        return 0.0
    for (h0, r0), (h1, r1) in zip(_SMOOTH, _SMOOTH[1:]):
        if h0 <= h <= h1:
            return r0 + (r1 - r0) * (h - h0) / (h1 - h0)
    return 0.0


def shell_ny(y: int) -> float:
    h = row_h(y + 0.5)
    dr = (radius(h + 0.4) - radius(h - 0.4)) / 0.8
    return -dr / math.sqrt(1 + dr * dr)


def col_width(y: int) -> float:
    """Surface width (units) of one map column on the facets of row y's slice."""
    k, _, _ = slice_of_row(y)
    return SLICES[k][2] * 2 * TAN16 / 4


def row_height(y: int) -> float:
    k, ht, hb = slice_of_row(y)
    return ht - hb


# --------------------------------------------------------------------------------------
# Static shell: storm-cloud lobes over the baked light
# --------------------------------------------------------------------------------------
# Cloud lobes: (centre column, centre row, radius in units). A lobe is the top half of a
# circle carried straight down, so later (lower) lobes pile in front of earlier ones.
LOBES = [
    (2.0, 7.4, 1.7), (8.0, 8.2, 2.5), (14.5, 7.2, 1.9), (20.0, 8.0, 2.7), (27.0, 7.6, 2.1),
    (30.5, 12.4, 2.6), (4.0, 13.0, 2.2), (10.5, 12.4, 3.2), (18.0, 13.2, 2.4), (24.0, 12.2, 2.9),
    (1.5, 17.4, 3.3), (9.5, 17.9, 2.6), (15.5, 17.0, 3.1), (23.0, 17.8, 2.8), (28.5, 17.2, 2.4),
]
CLOUD_K = 0.7                       # how strongly the lobes show over the glaze
SHELL_BASE, SHELL_SPAN = 2.2, 6.0   # the glaze's ramp position in shadow, and its range


def lobe_at(x: int, y: int):
    """(lobe, depth below its rim in rows, dx -1..1) of the front-most lobe over (x, y)."""
    if not 0 <= y < SHELL_ROWS:
        return None
    best = None
    cw, rh = col_width(y), row_height(y)
    for i, (cx, cy, r) in enumerate(LOBES):
        dxu = (((x + 0.5 - cx) + N / 2) % N - N / 2) * cw
        if abs(dxu) >= r:
            continue
        rim = cy - math.sqrt(r * r - dxu * dxu) / rh
        if y + 0.5 >= rim:
            best = (i, y + 0.5 - rim, dxu / r)
    return best


def cloud_level(x: int, y: int) -> float:
    """Ramp offset from the clouds: a silver lining on each lobe's round top, its upper
    left catching the light, its right flank and the valleys between lobes in shadow."""
    o = lobe_at(x, y)
    if o is None:
        return -1.0
    i, depth, dx = o
    below = lobe_at(x, y + 1)
    if below is not None and below[0] > i and depth > 1.5:
        return -1.0                                  # tucked behind the lobe in front
    if depth < 1:
        return 1.5 if dx < 0.5 else 0.6
    if depth < 2.2 and dx < 0.2:
        return 0.6
    if dx > 0.5:
        return -0.6
    return 0.0


def shell_index(x: int, y: int) -> int:
    phi = col_phi(x)
    ny = shell_ny(y)
    t = SHELL_BASE + SHELL_SPAN * lit(phi, ny) + 1.4 * spec(phi, ny, 6.0, 0.35) + CLOUD_K * cloud_level(x, y)
    k, _, _ = slice_of_row(y)
    if k >= 6:
        t -= 0.7                       # in the shadow of the copper crown
    if y >= 19:
        t -= (y - 18) * 0.6            # the dark underside of the storm
    return int(clamp(round(t), 1, len(STORM) - 1))


def fill_block(px, col: int, row: int, colour) -> None:
    """A 2x2 texel swatch block (1x1 in UV) so mipmaps keep it clean."""
    for i in (0, 1):
        for j in (0, 1):
            px[2 * col + i, 2 * row + j] = rgba(colour)


def swatch(col: int, row: int) -> list[float]:
    return [col + 0.25, row + 0.25, col + 0.75, row + 0.75]


LEDGE_ROW_UP, LEDGE_ROW_DOWN = 12, 13     # swatch block rows in the shell texture


def paint_shell() -> None:
    img = canvas(N)
    px = img.load()
    for y in range(SHELL_ROWS):
        for x in range(N):
            px[x, y] = rgba(STORM[shell_index(x, y)])
    for col in range(16):
        for row in range(12, 16):
            fill_block(px, col, row, STORM[3])
    for k in range(len(SLICES)):
        # ledges are one flat colour all round: the shell's average beside them, the tops a
        # touch lighter (they face the sky), the undersides darker
        up = sum(shell_index(x, ROW0[k]) for x in range(N)) / N
        down = sum(shell_index(x, ROW0[k] + ROWS[k] - 1) for x in range(N)) / N
        fill_block(px, k, LEDGE_ROW_UP, STORM[int(clamp(round(up + 0.4), 1, len(STORM) - 1))])
        fill_block(px, k, LEDGE_ROW_DOWN, STORM[int(clamp(round(down - 1.0), 1, len(STORM) - 1))])
    save(img, "shell")


# --------------------------------------------------------------------------------------
# Lightning on the shell
# --------------------------------------------------------------------------------------

def _line(p0, p1):
    (x0, y0), (x1, y1) = p0, p1
    n = max(abs(x1 - x0), abs(y1 - y0))
    if n == 0:
        return [(x0, y0)]
    return [(round(x0 + (x1 - x0) * k / n), round(y0 + (y1 - y0) * k / n)) for k in range(n + 1)]


def _polyline(points):
    out = []
    for p0, p1 in zip(points, points[1:]):
        seg = _line(p0, p1)
        out += seg if not out else seg[1:]
    return out


class Bolt:
    """A jagged trunk plus two sets of forks: the first stroke lights one set, the
    second stroke (a moment later) the other, so the bolt branches in new places."""

    def __init__(self, seed: int, start, end_row: int, horizontal: bool = False, forks: int = 3):
        rng = random.Random(seed)
        x, y = start
        pts = [(x, y)]
        direction = rng.choice((-1, 1))
        if horizontal:
            while len(pts) < 9:
                x += rng.choice((1, 2, 2, 3))
                y += direction * rng.choice((1, 2, 2, 3))
                y = max(start[1] - 3, min(start[1] + 3, y))
                direction = -direction if rng.random() < 0.75 else direction
                pts.append((x, y))
        else:
            while y < end_row:
                if rng.random() < 0.25:                   # a sharp sideways jag
                    x += direction * rng.choice((2, 3))
                    y += 1
                else:
                    x += direction * rng.choice((1, 1, 2))
                    y += rng.choice((2, 3, 3))
                if rng.random() < 0.7:
                    direction = -direction
                pts.append((x, min(y, end_row)))
        self.points = pts
        self.trunk = _polyline(pts)
        self.forks = ([], [])
        inner = list(range(1, len(pts) - 1))
        for s in (0, 1):
            for i in sorted(rng.sample(inner, min(len(inner), forks))):
                bx, by = pts[i]
                side = rng.choice((-1, 1))
                if horizontal:
                    fork = [(bx, by), (bx + rng.choice((0, 1)), by + side * 2),
                            (bx + rng.choice((1, 2, 3)), by + side * rng.choice((3, 4)))]
                else:
                    fork = [(bx, by), (bx + side * rng.choice((2, 3)), by + rng.choice((1, 2))),
                            (bx + side * rng.choice((3, 4)), by + rng.choice((3, 4))),
                            (bx + side * rng.choice((4, 6)), by + rng.choice((5, 6)))]
                self.forks[s].append(_polyline(fork)[1:])
        # the residual crackle: little sparks hopping off the trunk as it fades
        self.sparks = []
        for _ in range(6):
            bx, by = rng.choice(self.trunk[2:])
            side = rng.choice((-1, 1))
            self.sparks.append([(bx + side, by), (bx + 2 * side, by + rng.choice((-1, 1)))])


CROWN_ROW = 3                     # the first shell row under the crown
# (strike frame, bolt, size): 2 = a full strike down from the crown (the rod's star and the
# glass ball flare with it), 1 = a smaller crackle. The map repeats every half turn, so
# columns are placed for the inventory view: it faces azimuth 110 (column 10).
EVENTS = [
    (0, Bolt(11, (facet_col(90) + 1, CROWN_ROW), 20), 2),     # the hero strike, right of centre
    (7, Bolt(10, (2, 15), 0, horizontal=True), 1),            # a crackle racing across the belly
    (13, Bolt(29, (18, CROWN_ROW), 19), 2),                   # down the right flank
    (20, Bolt(37, (7, 12), 20, forks=2), 1),                  # leaping from the wire's point
    (26, Bolt(47, (facet_col(0) + 1, CROWN_ROW), 19), 2),     # at the silhouette, arcs leaping off
]


def _put(px, x, y, colour, over=True):
    if 0 <= y < SHELL_ROWS:
        x %= N
        if over or px[x, y][3] == 0:
            px[x, y] = rgba(colour)


def _glow(px, cells, reach: float, alpha: float):
    """Soft light washing the storm cloud round a stroke (translucent, under the bolt)."""
    cellset = {(x % N, y) for x, y in cells}
    r = int(math.ceil(reach))
    seen = {}
    for (x, y) in cellset:
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                d = math.hypot(dx, dy * 1.1)
                if d == 0 or d > reach:
                    continue
                key = ((x + dx) % N, y + dy)
                k = 1.0 - (d - 1) / reach
                seen[key] = max(seen.get(key, 0.0), k)
    for (x, y), k in seen.items():
        if (x, y) in cellset or not 0 <= y < SHELL_ROWS:
            continue
        a = round(alpha * clamp(k) ** 1.4)
        if a >= 30:
            _put(px, x, y, (255, 250, 170, a), over=False)


def _impact(px, cell):
    """A small burst where the stroke lands."""
    x, y = cell
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        _put(px, x + dx, y + dy, PALE)
    for dx, dy in ((2, 0), (-2, 0)):
        _put(px, x + dx, y + dy, (255, 255, 120, 150), over=False)
    _put(px, x, y, WHITE)


def _fork(px, cells, hot, tip):
    for i, (x, y) in enumerate(cells):
        _put(px, x, y, hot if i < len(cells) - 2 else tip)


def stroke(frame: int, f0: int) -> int:
    return (frame - f0) % FRAMES


def paint_strike(px, bolt: Bolt, d: int, size: int) -> None:
    trunk = bolt.trunk
    n = len(trunk)
    if d == FRAMES - 1 and size == 2:                     # the leader feels its way down
        for i, (x, y) in enumerate(trunk[: max(3, n // 3)]):
            _put(px, x, y, YELLOW if i % 2 else GOLD)
    elif d == 0:                                          # the stroke: white-hot, forks out
        for fork in bolt.forks[0]:
            _fork(px, fork, YELLOW, GOLD)
        for (x, y) in trunk:
            _put(px, x, y, WHITE)
        for (x, y) in trunk:
            _put(px, x + 1, y, PALE, over=False)
        _glow(px, trunk, 3.2 if size == 2 else 2.0, 150 if size == 2 else 110)
        _impact(px, trunk[-1])
    elif d == 1:                                          # it dims for a moment
        for (x, y) in trunk:
            _put(px, x, y, GOLD)
        for fork in bolt.forks[0]:
            _fork(px, fork[:2], YELLOW, GOLD)
    elif d == 2:                                          # a second stroke, new forks
        for fork in bolt.forks[1]:
            _fork(px, fork, YELLOW, GOLD)
        for (x, y) in trunk:
            _put(px, x, y, PALE)
        for (x, y) in trunk:
            _put(px, x + 1, y, YELLOW, over=False)
        if size == 2:
            _glow(px, trunk, 2.2, 100)
            _impact(px, trunk[-1])
    elif d == 3:
        for fork in bolt.forks[1]:
            _fork(px, fork[:2], GOLD, AMBER)
        for (x, y) in trunk:
            _put(px, x, y, YELLOW)
        for spark in bolt.sparks[:2]:
            _fork(px, spark, PALE, YELLOW)
    elif d == 4:                                          # it breaks up and crackles out
        for i, (x, y) in enumerate(trunk):
            if (i // 2) % 3 != 2:
                _put(px, x, y, GOLD if i % 4 else YELLOW)
        for spark in bolt.sparks[2:4]:
            _fork(px, spark, YELLOW, GOLD)
    elif d == 5:                                          # a last few sparks wink out
        for i, (x, y) in enumerate(trunk):
            if i % 5 == 2:
                _put(px, x, y, GOLD)
        for spark in bolt.sparks[4:]:
            _put(px, *spark[0], YELLOW)


# Glowing swatch blocks in the bolt texture (block row, column): the tip star and the arcs.
STAR_BLOCK = (13, 0)
ARC_BLOCKS = {"right": (13, 1), "left": (13, 2)}
# (event index, strokes it lights) for each arc that leaps off the shell
ARC_TIMING = {"right": ((1, (1, 2)), (4, (0, 2))), "left": ((3, (1, 2)), (4, (3,)))}
STROKE_COLOUR = {0: WHITE, 1: YELLOW, 2: PALE, 3: GOLD}


def star_colour(frame: int):
    best = None
    for f0, _, size in EVENTS:
        if size != 2:
            continue
        d = stroke(frame, f0)
        c = {FRAMES - 1: GOLD, 0: WHITE, 2: PALE, 3: YELLOW}.get(d)
        if c and (best is None or d == 0):
            best = c
    return best


def arc_colour(frame: int, which: str):
    for ev, strokes in ARC_TIMING[which]:
        d = stroke(frame, EVENTS[ev][0])
        if d in strokes:
            return STROKE_COLOUR[d]
    return None


def bolt_frame(t: float):
    frame = round(t * FRAMES) % FRAMES
    img = canvas(N)
    px = img.load()
    for f0, bolt, size in EVENTS:
        paint_strike(px, bolt, stroke(frame, f0), size)
    fill_block(px, STAR_BLOCK[1], STAR_BLOCK[0], star_colour(frame) or CLEAR)
    for which, (row, col) in ARC_BLOCKS.items():
        fill_block(px, col, row, arc_colour(frame, which) or CLEAR)
    return img


# --------------------------------------------------------------------------------------
# Copper: every band is a lit map like the shell's, so the parts shade together
# --------------------------------------------------------------------------------------

def roof_ny(tier: int) -> float:
    (rb, hb), (rt, ht) = ROOF[tier], ROOF[tier + 1]
    return math.sin(math.atan2(rb - rt, ht - hb))


# name -> (first row, rows, sides, vertical normal part per row)
BANDS = {
    "band": (0, 2, 16, (0.0, -0.3)),
    "lip": (21, 2, 16, (0.5, -0.1)),
    "tooth": (3, 2, 16, (0.2, -0.2)),
    "roof0": (5, 2, 16, (roof_ny(0) - 0.05, roof_ny(0) - 0.12)),
    "roof1": (7, 2, 16, (roof_ny(1) - 0.02, roof_ny(1) - 0.08)),
    "boss": (9, 2, 8, (0.45, -0.2)),
    "drum": (23, 2, 16, (0.2, -0.05)),
    "boss0": (25, 2, 16, (0.6, 0.3)),
    "rod": (11, 6, 8, (0.0,) * 6),
    "coil": (17, 2, 8, (0.45, -0.35)),
    "head": (19, 2, 8, (0.3, -0.3)),
}
NAMED_ROW, PALETTE_ROW = 14, 15      # swatch block rows (texel rows 28-29 and 30-31)
NAMED = ("band_up", "band_down", "boss_up", "coil_up", "coil_down", "head_up", "head_down", "tip_up",
         "tip2_up", "edge", "lip_up", "lip_down", "drum_up", "boss0_up")


def copper_index(phi: float, ny: float, x: int = 0, y: int = 0, gloss: float = 1.0) -> int:
    t = 1.3 + 7.2 * lit(phi, ny) + 2.4 * gloss * spec(phi, ny, 8.0, 0.2) + dith(x, y) * 0.5
    return int(clamp(round(t), 0, len(COPPER) - 1))


def copper_base():
    """The copper atlas at rest: every band's lit map, the band's verdigris and rivets, the
    ledge swatches and a palette row for small parts."""
    img = canvas(32, fill=COPPER[4])
    px = img.load()
    for name, (row0, rows, sides, nys) in BANDS.items():
        for i in range(rows):
            for x in range(32):
                gloss = 0.4 if name == "band" else 1.0
                px[x, row0 + i] = rgba(COPPER[copper_index(col_phi(x, sides), nys[i], x, row0 + i, gloss)])
    # the band is darker, weathered bronze with verdigris in its lower row; the lip above it
    # carries a rivet over every other tooth
    rng = random.Random(9)
    for x in range(32):
        phi = col_phi(x)
        px[x, 0] = rgba(COPPER[max(1, copper_index(phi, 0.1, x, 0, 0.3) - 3)])
        px[x, 1] = rgba(PATINA[1 + round(2 * lit(phi, -0.2))] if rng.random() < 0.55 else
                        COPPER[max(0, copper_index(phi, -0.4, x, 1, 0.2) - 3)])
        px[x, 22] = rgba(COPPER[max(1, copper_index(phi, -0.3, x, 22, 0.5) - 1)])
    for phi_c in (0, 45, 90, 135):
        c = facet_col(phi_c) + 1
        l = lit(col_phi(c), 0.3)
        px[c, 21] = rgba(COPPER[min(10, 6 + round(4 * l))])
        px[c + 1, 21] = rgba(COPPER[max(0, 3 + round(3 * l))])
        px[c, 22] = rgba(COPPER[max(0, 1 + round(2 * l))])
    # the coil's windings: a dark gap line
    for x in range(32):
        px[x, 18] = rgba(COPPER[1])
    for col in range(16):
        for row in (NAMED_ROW, PALETTE_ROW):
            fill_block(px, col, row, COPPER[4])
    front = facet_col(FRONT_PHI) + 1
    named = {"band_up": px[front, 0], "band_down": COPPER[1], "boss_up": COPPER[8], "coil_up": COPPER[8],
             "coil_down": COPPER[2], "head_up": COPPER[9], "head_down": COPPER[3], "tip_up": COPPER[9],
             "tip2_up": COPPER[10], "edge": COPPER[2], "lip_up": px[front, 21], "lip_down": COPPER[2],
             "drum_up": px[front, 5], "boss0_up": COPPER[9]}
    for i, name in enumerate(NAMED):
        fill_block(px, i, NAMED_ROW, named[name])
    for i, c in enumerate(COPPER):
        fill_block(px, i, PALETTE_ROW, c)
    return img


def cap_flash(frame: int) -> float:
    """How strongly the copper catches each strike's flash."""
    best = 0.0
    for f0, _, size in EVENTS:
        d = stroke(frame, f0)
        k = {0: 1.0, 1: 0.2, 2: 0.35, 3: 0.12}.get(d, 0.0) * (1.0 if size == 2 else 0.3)
        best = max(best, k)
    return best


_RAMP_OF = {rgba(c)[:3]: (COPPER, i) for i, c in enumerate(COPPER)}
_RAMP_OF.update({rgba(c)[:3]: (PATINA, i) for i, c in enumerate(PATINA)})


def copper_frame(t: float, base):
    """The copper lit up by the flash: every tone steps up its ramp and warms toward the
    lightning's pale yellow for a moment."""
    f = cap_flash(round(t * FRAMES) % FRAMES)
    out = base.copy()
    if f <= 0:
        return out
    px = out.load()
    step = 1 if f >= 0.4 else 0
    for y in range(out.height):
        for x in range(out.width):
            ramp, i = _RAMP_OF.get(px[x, y][:3], (None, 0))
            if ramp is None:
                continue
            c = ramp[min(len(ramp) - 1, i + step)]
            px[x, y] = rgba(mix(c, "#fff3c2", 0.16 * f))
    return out


def ball_flash(frame: int) -> float:
    """How brightly the glass ball lights up: it gathers charge for a few frames before a
    big strike, then flares as the strokes pass through it."""
    best = 0.0
    for f0, _, size in EVENTS:
        d = stroke(frame, f0)
        k = {FRAMES - 3: 0.1, FRAMES - 2: 0.18, FRAMES - 1: 0.3, 0: 1.0, 1: 0.45, 2: 0.8, 3: 0.35,
             4: 0.15}.get(d, 0.0)
        if size != 2:
            k = 0.0 if d >= FRAMES - 3 else k * 0.4
        best = max(best, k)
    return best


def glass_frame(t: float):
    frame = round(t * FRAMES) % FRAMES
    g = ball_flash(frame)
    img = canvas(32, fill=GLASS[2])
    px = img.load()
    for y in range(8):
        ny = 0.8 - y * 0.23
        core = 1.0 - abs(y - 3.5) / 4.0
        for x in range(32):
            phi = col_phi(x, 8)
            t_ = 1.0 + 6.0 * lit(phi, ny) + 3.0 * spec(phi, ny, 10.0, 0.45) + dith(x, y) * 0.4
            c = GLASS[int(clamp(round(t_), 0, len(GLASS) - 1))]
            if g > 0:
                c = mix(c, "#fffbd0", clamp(g * (0.35 + 0.75 * core)))
            px[x, y] = rgba(c)
    fill_block(px, 0, 12, mix(GLASS[6], "#fffbd0", g * 0.6))
    fill_block(px, 1, 12, mix(GLASS[1], "#ffe680", g * 0.6))
    return img


def textures() -> None:
    paint_shell()
    base = copper_base()
    save_animation(animate(lambda t: copper_frame(t, base), FRAMES), "copper", frametime=FRAMETIME)
    save_animation(animate(bolt_frame, FRAMES), "bolts", frametime=FRAMETIME)
    save_animation(animate(glass_frame, FRAMES), "glass", frametime=FRAMETIME)


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def face_phi(side: str, a: float) -> float:
    return {"east": -a, "west": 180.0 - a, "south": 90.0 - a, "north": 270.0 - a}[side]


def angle_of(e: dict) -> float:
    rot = e.get("rotation")
    return rot["angle"] if rot else 0.0


def slice_index(e: dict) -> int:
    h0 = e["from"][1] - CY
    return min(range(len(SLICES)), key=lambda i: abs(SLICES[i][0] - h0))


def wrap(parts, tex: str, v0: float, v1: float, sides: int, up=None, down=None):
    """UV turned slabs onto a lit map: each side face gets its facet's columns over v0..v1;
    caps get a uniform swatch (tex, uv) or are dropped."""
    for e in parts:
        for side in list(e["faces"]):
            face = e["faces"][side]
            if side in ("up", "down"):
                spec_ = up if side == "up" else down
                if spec_ is None:
                    del e["faces"][side]
                else:
                    face["texture"], face["uv"] = "#" + spec_[0], spec_[1]
            else:
                u = u_left(face_phi(side, angle_of(e)), sides)
                face["uv"] = [u, v0, u + 32.0 / sides, v1]
                face["texture"] = "#" + tex
    return parts


SHELL_SLICES = range(0, 7)      # the top two slices sit inside the copper


def shell() -> list[dict]:
    parts = lathe(CENTRE, EGG_PROFILE, "shell", sides=16, shade=False)
    radii = [s[2] for s in SLICES]
    out = []
    for e in parts:
        k = slice_index(e)
        if k not in SHELL_SLICES:
            continue
        up = ("shell", swatch(k, LEDGE_ROW_UP)) if radii[k + 1] < radii[k] else None
        down = ("shell", swatch(k, LEDGE_ROW_DOWN)) if k == 0 or radii[k - 1] < radii[k] else None
        wrap([e], "shell", ROW0[k] / 2, (ROW0[k] + ROWS[k]) / 2, 16, up, down)
        out.append(e)
    return out


OVERLAY_SLICES = range(1, 7)


def overlay() -> list[dict]:
    proud = [(h, r + 0.06) for h, r in EGG_PROFILE]
    parts = lathe(CENTRE, proud, "bolts", sides=16, glow=15, shade=False)
    out = []
    for e in parts:
        k = slice_index(e)
        if k in OVERLAY_SLICES:
            out.append(wrap([e], "bolts", ROW0[k] / 2, (ROW0[k] + ROWS[k]) / 2, 16)[0])
    return out


LOCAL_NORMALS = {"east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0),
                 "south": (0, 0, 1), "north": (0, 0, -1)}


def bake(parts, lift: int = 0, gloss: float = 1.0, lo: int = 1, hi: int = 10):
    """Give every face of these copper parts the palette colour its real (world) normal
    catches under the baked light, so turned and rolled pieces shade correctly."""
    for e in parts:
        m, _ = rotation_of(e)
        for side, face in e["faces"].items():
            n = [sum(m[i][k] * LOCAL_NORMALS[side][k] for k in range(3)) for i in range(3)]
            phi = math.degrees(math.atan2(n[2], n[0]))
            idx = copper_index(phi, n[1], gloss=gloss) + lift
            face.update(pal(int(clamp(idx, lo, hi))))
    return parts


def named(name: str) -> tuple:
    return ("copper", swatch(NAMED.index(name), NAMED_ROW))


def pal(i: int) -> dict:
    return {"uv": swatch(i, PALETTE_ROW), "texture": "#copper"}


def ring(h0: float, h1: float, r: float, band: str, up=None, down=None, tex: str = "copper", **kw):
    row0, rows, sides, _ = BANDS[band]
    parts = prism((CX, CY + (h0 + h1) / 2, CZ), r, h1 - h0, tex, sides=sides, shade=False, **kw)
    return wrap(parts, tex, row0 / 2, (row0 + rows) / 2, sides, up, down)


def teeth() -> list[dict]:
    """A zigzag of copper teeth in relief on the band: squares set on their points, their
    upper halves tucked behind the lip."""
    r0, r1, d = TOOTH
    row0, rows, _, _ = BANDS["tooth"]
    parts = []
    for j in range(16):
        phi_c = 22.5 * j
        s = d * math.sqrt(2)
        yc = CY + BAND[1]
        u = u_left(phi_c)
        t = box((CX + r0, yc - s / 2, CZ - s / 2), (CX + r1, yc + s / 2, CZ + s / 2), "copper",
                faces={"east": ("copper", [u, row0 / 2, u + 2, (row0 + rows) / 2])},
                shade=False, skip=("west",))
        for side in ("up", "down", "north", "south"):
            t["faces"][side] = pal(2)
        turn(t, 45, "x", (CX + (r0 + r1) / 2, yc, CZ))
        turn(t, -phi_c, "y", (CX, 0, CZ))
        parts.append(t)
    return parts


def roof() -> list[dict]:
    """The paneled roof: per tier, sixteen copper panels leaning in toward the rod, each
    joint covered by a standing seam that catches the light."""
    parts = []
    for tier in range(len(ROOF) - 1):
        (rb, hb), (rt, ht) = ROOF[tier], ROOF[tier + 1]
        length = math.hypot(rb - rt, ht - hb)
        lean = math.degrees(math.atan2(rb - rt, ht - hb))
        width = 2 * rb * TAN16 * 1.01
        rm, ym = (rb + rt) / 2, CY + (hb + ht) / 2
        row0, rows, _, _ = BANDS[f"roof{tier}"]
        for j in range(16):
            phi_c = 22.5 * j
            u = u_left(phi_c)
            p = box((CX + rm - PANEL, ym - length / 2, CZ - width / 2), (CX + rm, ym + length / 2, CZ + width / 2),
                    "copper", faces={"east": ("copper", [u, row0 / 2, u + 2, (row0 + rows) / 2])},
                    shade=False, skip=("west",))
            for side in ("up", "down", "north", "south"):
                p["faces"][side] = pal(2)
            turn(p, lean, "z", (CX + rm, ym, CZ))
            turn(p, -phi_c, "y", (CX, 0, CZ))
            parts.append(p)
        if tier == 0:
            ny = roof_ny(0)
            for j in range(16):
                phi_e = 22.5 * j + 11.25
                a = math.radians(phi_e)
                cb, ct = rb / math.cos(math.radians(11.25)), rt / math.cos(math.radians(11.25))
                p0 = (CX + cb * math.cos(a), CY + hb + 0.02, CZ + cb * math.sin(a))
                p1 = (CX + ct * math.cos(a), CY + ht, CZ + ct * math.sin(a))
                seam = bar(p0, p1, 0.2, 0.2, "copper", shade=False)
                parts += bake([seam], lift=1, gloss=1.4, lo=3)
    return parts


def crown() -> list[dict]:
    parts = ring(*BAND, "band", up=None, down=named("band_down"))
    parts += ring(*LIP, "lip", up=named("lip_up"), down=named("lip_down"))
    parts += ring(*DRUM, "drum", up=named("drum_up"))
    return parts + teeth() + roof()


def rod() -> list[dict]:
    parts = ring(*BOSS[0], "boss0", up=named("boss0_up"))
    parts += ring(*BOSS[1], "boss", up=named("boss_up"))
    parts += ring(*ROD, "rod")
    for h in COILS:
        parts += ring(h, h + 0.17, 0.58, "coil", up=named("coil_up"), down=named("coil_down"))
    # the glass insulator ball, lit up by every strike that passes through it
    ball = lathe((CX, CY + BALL_H, CZ), [(-0.76, 0.38), (-0.42, 0.88), (0.0, BALL_R), (0.42, 0.88), (0.76, 0.38)],
                 "glass", sides=8, shade=False, glow=12)
    for i, e in enumerate(ball):
        k = i // 4
        wrap([e], "glass", k, k + 1, 8, up=("glass", swatch(0, 12)) if k >= 2 else None,
             down=("glass", swatch(1, 12)) if k <= 1 else None)
    parts += ball
    parts += ring(*HEAD, "head", up=named("head_up"), down=named("head_down"))
    parts += ring(*TIP[0], "head", up=named("tip_up"))
    parts += ring(*TIP[1], "head", up=named("tip2_up"))
    return parts


def glow_uv(block) -> dict:
    row, col = block
    return {"uv": swatch(col, row), "texture": "#bolts"}


def star() -> list[dict]:
    """A spark that flares on the rod's point as each strike lands."""
    y = CY + TIP[1][1] - 0.12
    a = STAR_ARM
    parts = []
    for spin in (45, -45):
        s = box((CX - 0.1, y - a, CZ - 0.1), (CX + 0.1, y + a, CZ + 0.1), "bolts", glow=15, shade=False)
        turn(s, spin, "z", (CX, y, CZ))
        parts.append(s)
    parts.append(box((CX - 0.1, y - 0.1, CZ - a), (CX + 0.1, y + 0.1, CZ + a), "bolts", glow=15, shade=False))
    for p in parts:
        for side in p["faces"]:
            p["faces"][side] = glow_uv(STAR_BLOCK)
    return parts


# Arcs leaping off the shell at the inventory view's left and right edges:
# (radius from the axis, egg height) points, in the plane through +X (right) or -X (left).
ARC_PATHS = {
    "right": [(4.5, 7.6), (5.2, 7.2), (5.0, 6.7), (5.9, 6.3), (5.5, 5.8), (6.3, 5.2), (5.9, 4.8), (5.12, 4.2)],
    "left": [(4.95, 5.6), (5.7, 5.1), (5.4, 4.6), (6.2, 4.1), (5.8, 3.5), (5.2, 3.1), (4.75, 2.5)],
}


def arcs() -> list[dict]:
    parts = []
    for which, path in ARC_PATHS.items():
        sign = 1 if which == "right" else -1
        pts = [(CX + sign * r, CY + h, CZ) for r, h in path]
        for p0, p1 in zip(pts, pts[1:]):
            b = bar(p0, p1, 0.26, 0.26, "bolts", glow=15, shade=False)
            for side in b["faces"]:
                b["faces"][side] = glow_uv(ARC_BLOCKS[which])
            parts.append(b)
    return parts


# The conductor: a square copper wire bent into a lightning bolt, standing just proud of
# the shell from under the band to a point at the belly. (distance from the axis, egg
# height, offset across the facet) points in the plane facing +X; positive offsets sit on
# the viewer's left.
WIRE = [(3.3, 10.06, 0.42), (3.78, 8.8, -0.34), (4.66, 8.32, 0.5), (4.68, 6.98, -0.36), (5.0, 6.36, -0.62)]
WIRE_W = 0.58
WIRE_TIP = (5.02, 6.08, -0.72, 0.34)     # the point: distance, height, offset, half-diagonal


def strap(phi_c: float) -> list[dict]:
    """The wire built facing +X, then turned to face phi_c."""
    parts = []
    pts = [(CX + r, CY + h, CZ + o) for r, h, o in WIRE]
    for i, (p0, p1) in enumerate(zip(pts, pts[1:])):
        d = [p1[j] - p0[j] for j in range(3)]
        n = math.sqrt(sum(v * v for v in d))
        e = (WIRE_W * 0.45) / n
        a = p0 if i == 0 else tuple(p0[j] - d[j] * e for j in range(3))
        b = tuple(p1[j] + d[j] * e for j in range(3))
        parts.append(bar(a, b, WIRE_W, WIRE_W, "copper", shade=False))
    r, h, o, hd = WIRE_TIP
    tip = box((CX + r - 0.18, CY + h - hd, CZ + o - hd), (CX + r + 0.18, CY + h + hd, CZ + o + hd), "copper",
              shade=False)
    turn(tip, 45, "x", (CX + r, CY + h, CZ + o))
    parts.append(tip)
    turn(parts, -phi_c, "y", (CX, 0, CZ))
    return bake(parts, lift=1, gloss=1.2, lo=2, hi=9)


STRAP_PHI = FRONT_PHI + 45          # the front left of the inventory view (and the back right)


def straps() -> list[dict]:
    return strap(STRAP_PHI) + strap(STRAP_PHI + 180)


def build() -> list[dict]:
    return shell() + crown() + rod() + overlay() + straps() + arcs() + star()


def transforms(parts) -> dict:
    d = display(KIND, parts, grip=GRIP, size=SIZE, gui_rotation=GUI_ROTATION, gui_span=15.8)
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, 1)}, GRIP, "fist", 0.45 * SIZE)
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.3, 0.12, 1)}, GRIP, (0.47, -0.36, -0.88),
                                       0.5 * SIZE, pose=None)
    # A thrown egg is drawn facing the camera through "ground" (model +Z toward the viewer);
    # item frames and shelves show -Z. Turn all of them like the inventory icon.
    yaw = GUI_ROTATION[1]
    d["ground"] = fit(parts, (0, yaw, 0), 8.0, lift=2.0)
    d["fixed"] = fit(parts, (0, yaw + 180, 0), 14.0)
    d["on_shelf"] = fit(parts, (0, yaw + 180, 0), 12.0)
    return d


def models() -> dict:
    parts = build()
    return {"main": model(parts, transforms(parts))}
