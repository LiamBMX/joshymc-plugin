"""Knockback Egg: a bold orange egg belted by a steel shock-plate band.

The shell is the shared egg body (lathe() of EGG_PROFILE, turned 11.25 degrees so facet
folds sit on the cardinal directions). Its texture wraps round the egg with a cylindrical
projection (180 degrees of shell per texture width, the rows shared out between the lathe
slices), so the painted markings run on across the facets: four stacks of punchy
warm-charcoal chevrons point away from the band, three tiers above and two below, each
stack centred on a fold above a shock plate. The terraces between slices use radial
textures, so every overlapping slab top shows the same texels. A steel band with riveted
raised lips circles the widest slice; a glowing conduit runs round its recessed channel,
and it carries four bolted shock plates, each with a round lens in a dark socket.

Light is kept on its own layer: a mostly transparent, unshaded, emissive skin just proud
of the shell, so the shockwave and hot chevrons read as real light in the slot and at
night instead of being dimmed by face shading.

Animation (one beat, 24 frames x 2 ticks = 2.4 s, every texture in step): energy streams
along the conduit into the plates as the lenses charge; they fire with a white flash; a
shockwave ring races from the band to both tips, lighting the chevrons one after another
as it passes; the chevrons cool through orange to red embers and back to the idle look.
"""
from __future__ import annotations

import math
import random

from art.kit import (animate, box, canvas, display, fit, lathe, model, place, prism, rgba, save,
                     save_animation, turn)

ID = "knockback_egg"
NAME = "Knockback Egg"
KIND = "item"
COUNTERPART = "item/egg"

# --------------------------------------------------------------------------------------
# The shared egg body (identical for all twelve eggs)
# --------------------------------------------------------------------------------------
EGG_PROFILE = [(0, 1.4), (0.7, 3.2), (1.8, 4.3), (3.3, 4.9), (5.0, 5.0), (6.8, 4.7), (8.4, 4.0),
               (9.8, 3.0), (10.9, 1.8), (11.5, 0.6)]
EGG_CENTER = (8.0, 2.2, 8.0)
Y0 = EGG_CENTER[1]
TIP = EGG_PROFILE[-1][0]
SPIN = 11.25   # turn the lathe so facet folds sit on the four cardinal directions
SLICES = [(h0, h1, (r0 + r1) / 2) for (h0, r0), (h1, r1) in zip(EGG_PROFILE, EGG_PROFILE[1:])]
# Rows of the 32 px shell texture per slice, bottom slice first; row 0 is the tip.
ROWS = [2, 4, 5, 1, 6, 5, 4, 3, 2]
START = [sum(ROWS[k + 1:]) for k in range(len(ROWS))]
BAND_LO, BAND_HI = 3.3, 5.0     # heights (along the egg) the steel band covers: slice 3

# --------------------------------------------------------------------------------------
# Palettes, darkest -> lightest, hue-shifted by hand
# --------------------------------------------------------------------------------------
SHELL = ["#3a0b12", "#6a160e", "#a0260b", "#d24206", "#f26800", "#ff8800", "#ff9a1c", "#ffae33",
         "#ffc458", "#ffdc8c", "#fff4cf"]
# the terrace tops sit in full light, so they are painted near what the lit sides show
TERRACE = ["#6e3004", "#8a4204", "#a65600", "#bf6a06", "#d8821a", "#eea23a", "#ffc46a", "#ffe4a8"]
STEEL = ["#0f131b", "#1c2330", "#2c3545", "#414d60", "#5c6a80", "#7f8fa5", "#a8b7ca", "#d3dde8",
         "#f5f9fc"]
# chevron paint at rest, warm charcoal: shadowed edge, body, lit edge
CHEV = ["#1b0a0b", "#2f1210", "#4e1f16"]
# light, shown unshaded: hot chevrons from dull ember to white, the ring and spilled glow
HOT = ["#5a1208", "#8f220a", "#c43a0a", "#f06010", "#ff8f1e", "#ffbe3a", "#ffe57a", "#fff8d8", "#ffffff"]
GLOW = ["#e8601a", "#ff8a1a", "#ffa62a", "#ffd452", "#fff3b8"]
LENS = ["#2a0806", "#5c1406", "#a8300a", "#ff6a10", "#ffae2e", "#ffe27a", "#fff8dc", "#ffffff"]
CONDUIT = ["#1c0706", "#4a1208", "#8a240a", "#d8480c", "#ff8a1e", "#ffc84a", "#fff0a0", "#ffffff"]

# --------------------------------------------------------------------------------------
# Timing (t is the loop phase 0..1)
# --------------------------------------------------------------------------------------
FRAMES, FRAMETIME = 24, 2
T_FIRE = 0.375      # the plates fire
T_WAVE = 0.30       # the ring takes this long to reach the tips


def clamp(v: float, a: float = 0.0, b: float = 1.0) -> float:
    return max(a, min(b, v))


def charge(t: float) -> float:
    """Lens charge: builds to 1 up to the shot, then drops away."""
    if t < T_FIRE:
        return (t / T_FIRE) ** 2
    return clamp(1.0 - (t - T_FIRE) / 0.12)


def flash(t: float) -> float:
    """The white flash of the shot itself."""
    dt = (t - T_FIRE) % 1.0
    return clamp(1.0 - dt / 0.09)


def ring(t: float, h: float) -> float:
    """Shockwave brightness at height h (along the egg): a hot front and a fading wake."""
    p = (t - T_FIRE) / T_WAVE
    if not 0.0 <= p < 1.0:
        return 0.0
    if h >= BAND_HI:
        behind = BAND_HI + p * (TIP - BAND_HI) - h
    elif h <= BAND_LO:
        behind = h - (BAND_LO - p * BAND_LO)
    else:
        return 0.0
    fade = 1.0 - 0.45 * p
    if -0.2 <= behind < 0.25:
        return fade
    if 0.25 <= behind < 0.85:
        return fade * 0.55 * (1.0 - (behind - 0.25) / 0.6)
    return 0.0


def reach(h: float) -> float:
    """0 at the band's edge, 1 at the tip (above the band) or the base (below it)."""
    if h >= BAND_HI:
        return (h - BAND_HI) / (TIP - BAND_HI)
    return clamp((BAND_LO - h) / BAND_LO)


def lit(t: float, d: float, hold: float = 0.07, fade: float = 0.33) -> float:
    """Chevron heat: full as the ring reaches it (d = its reach), then cooling."""
    dt = (t - (T_FIRE + d * T_WAVE)) % 1.0
    if dt < hold:
        return 1.0
    if dt < hold + fade:
        return 1.0 - (dt - hold) / fade
    return 0.0


# --------------------------------------------------------------------------------------
# Shell texture layout
# --------------------------------------------------------------------------------------
# 16-wide column templates; the column's centre line runs between index 7 and 8 (a fold).
CHEVRONS = {
    6: [".......##.......",
        "......####......",
        ".....##..##.....",
        "....##....##...."],
    5: [".......##.......",
        "......####......",
        ".....######.....",
        "....###..###....",
        "...###....###..."],
    4: [".......##.......",
        "......####......",
        ".....######.....",
        "....###..###....",
        "...###....###...",
        "................"],
    2: ["................",
        "....###..###....",
        ".....######.....",
        "......####......",
        ".......##......."],
    1: ["....###..###....",
        ".....######.....",
        "......####......",
        ".......##......."],
}


def row_info(row: int) -> tuple[int, float]:
    """(slice, height along the egg) of a shell texture row."""
    for k, n in enumerate(ROWS):
        if START[k] <= row < START[k] + n:
            h0, h1, _ = SLICES[k]
            f = (row - START[k] + 0.5) / n
            return k, h1 - f * (h1 - h0)
    raise ValueError(row)


def col_x(px: int) -> int:
    """Position inside a chevron column, -8..7 (the centre fold between -1 and 0)."""
    return (px + 8) % 16 - 8


GLOSS = {  # (slice, row within it) -> brightening at column x = -6 and -5, left of each stack:
    # a lacquer glint, brightest where the shell turns toward the light
    (7, 1): (1, 1), (7, 2): (2, 1), (6, 0): (2, 1), (6, 1): (4, 2), (6, 2): (3, 2), (6, 3): (2, 1),
    (5, 0): (2, 1), (5, 1): (3, 1), (5, 2): (2, 1), (5, 3): (1, 0), (4, 0): (1, 0), (4, 1): (1, 0),
}


def _layout():
    """Static per-texel data: base shell level, chevron (tier, role, reach) or None."""
    base = [[5] * 32 for _ in range(32)]
    chev = [[None] * 32 for _ in range(32)]
    for row in range(32):
        k, _ = row_info(row)
        lvl = 5                  # #FF8800; the tip a touch lighter, the base in its own shadow
        if k >= 7:
            lvl = 6
        elif k == 0:
            lvl = 4
        if row in (START[4] + ROWS[4] - 1, START[2]):   # contact shadow against the band
            lvl -= 1
        gloss = GLOSS.get((k, row - START[k]), (0, 0))
        for px in range(32):
            x = col_x(px)
            base[row][px] = lvl + (gloss[0] if x == -6 else gloss[1] if x == -5 else 0)
    for k, rows in CHEVRONS.items():
        hs = [row_info(START[k] + i)[1] for i, line in enumerate(rows) if "#" in line]
        d = reach(sum(hs) / len(hs))
        for i, line in enumerate(rows):
            for px in range(32):
                if line[col_x(px) + 8] != "#":
                    continue
                above = i > 0 and rows[i - 1][col_x(px) + 8] == "#"
                below = i < len(rows) - 1 and rows[i + 1][col_x(px) + 8] == "#"
                role = 1 if not above else (-1 if not below else 0)
                chev[START[k] + i][px] = (k, role, d)
    # a few speckles in the plain shell, in little clusters
    rng = random.Random(11)
    for _ in range(14):
        row, px = rng.randrange(2, 31), rng.randrange(32)
        if abs(col_x(px) + 0.5) < 5 or chev[row][px] or row_info(row)[0] == 3:
            continue
        base[row][px] -= 1
        if rng.random() < 0.5 and px + 1 < 32 and not chev[row][px + 1]:
            base[row][px + 1] -= 1
    return base, chev


BASE, CHEV_MAP = _layout()


def paint_shell() -> None:
    """The lacquered shell at rest: orange with warm-charcoal hazard chevrons."""
    img = canvas(32)
    px_ = img.load()
    for row in range(32):
        for px in range(32):
            c = CHEV_MAP[row][px]
            px_[px, row] = rgba(CHEV[1 + c[1]] if c else SHELL[max(0, min(len(SHELL) - 1, BASE[row][px]))])
    save(img, "shell")


def flare_frame(t: float):
    """The light layer over the shell: transparent except the shockwave ring, the hot
    chevrons and the glow they spill. It is unshaded and emissive, so it reads as light."""
    img = canvas(32)
    px_ = img.load()
    heat = {k: lit(t, d) for (k, _, d) in {c for row in CHEV_MAP for c in row if c}}
    for row in range(32):
        k, h = row_info(row)
        if k == 3:
            continue
        w = ring(t, h)
        for px in range(32):
            c = CHEV_MAP[row][px]
            if c:
                tier, role, _ = c
                q = max(heat[tier], 0.9 * w)
                if q > 0.06:
                    px_[px, row] = rgba(HOT[max(0, min(8, 1 + round(q * 6) + role))])
                continue
            # the ring: a white-hot front, a gold wake, a dithered tail
            level = 4 if w >= 0.75 else 3 if w >= 0.5 else 2 if w >= 0.3 else 1 if w >= 0.15 else -1
            # the glow a hot chevron spills onto the shell beside it
            hq = 0.0
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if 0 <= row + dy < 32:
                    n = CHEV_MAP[row + dy][(px + dx) % 32]
                    if n and n[0] == k:
                        hq = max(hq, heat[k])
            level = max(level, 1 if hq >= 0.55 else 0 if hq >= 0.3 else -1)
            if level > 1 or (level >= 0 and (px + row) % 2 == 0):
                px_[px, row] = rgba(GLOW[level])
    return img


# --------------------------------------------------------------------------------------
# Radial textures for the terraces and caps
# --------------------------------------------------------------------------------------
K_SHELL = 1.6          # uv units per model unit on the shell's radial caps (radius 5 -> 8)
K_FLARE = 8 / 5.1      # the same for the light layer, which stands a little proud
K_STEEL = 8 / 5.6      # and for the band's ledges


def h_up(rho: float) -> float:
    """Height on the upper half of the egg where its radius is rho."""
    pts = EGG_PROFILE[4:]
    if rho >= pts[0][1]:
        return pts[0][0]
    for (ha, ra), (hb, rb) in zip(pts, pts[1:]):
        if rb <= rho <= ra:
            return ha + (ra - rho) / (ra - rb) * (hb - ha)
    return TIP


def h_down(rho: float) -> float:
    """Height on the lower half of the egg where its radius is rho."""
    pts = EGG_PROFILE[:5]
    if rho <= pts[0][1]:
        return 0.0
    for (ha, ra), (hb, rb) in zip(pts, pts[1:]):
        if ra <= rho <= rb:
            return ha + (rho - ra) / (rb - ra) * (hb - ha)
    return pts[-1][0]


def paint_caps() -> None:
    """The terraces between slices and the two end caps, radial so every overlapping slab
    top shows the same texels. Tops catch full light and undersides only ambient light,
    so they are painted darker / lighter than the sides to keep the egg reading smooth;
    the tops use an un-shifted ramp, as redder shadows would read as painted rings."""
    for name, upper in (("cap_up", True), ("cap_down", False)):
        img = canvas(32)
        for py in range(32):
            for px in range(32):
                rho = math.hypot((px + 0.5) / 2 - 8, (py + 0.5) / 2 - 8) / K_SHELL
                if upper:
                    put(img, px, py, TERRACE[3 if h_up(rho) > 9.8 else 2])
                else:
                    put(img, px, py, SHELL[7 if h_down(rho) < 0.7 else 8])
        save(img, name)


def flare_cap_frame(t: float, upper: bool):
    """The light layer's terrace faces: the shockwave ring shrinking toward each tip."""
    img = canvas(32)
    for py in range(32):
        for px in range(32):
            rho = math.hypot((px + 0.5) / 2 - 8, (py + 0.5) / 2 - 8) / K_FLARE
            w = ring(t, h_up(rho) if upper else h_down(rho))
            level = 4 if w >= 0.75 else 3 if w >= 0.5 else 2 if w >= 0.3 else 1 if w >= 0.15 else -1
            if level > 1 or (level >= 0 and (px + py) % 2 == 0):
                put(img, px, py, GLOW[level])
    return img


# --------------------------------------------------------------------------------------
# Steel and lens textures
# --------------------------------------------------------------------------------------

def put(img, x: int, y: int, colour) -> None:
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), rgba(colour))


def paint_steel() -> None:
    # band channel, a 180-degree wrap (px 0 and 16 sit under the plates): shadowed under
    # the top lip, the conduit groove across the middle
    band = canvas(32)
    for y, c in enumerate([STEEL[1], STEEL[3], STEEL[0], STEEL[2]]):
        for x in range(32):
            put(band, x, y, c)
    save(band, "band")

    # lips: polished edge, with rivet heads between the plates (px 8 and 24)
    lip = canvas(32)
    for x in range(32):
        put(lip, x, 0, STEEL[7])
        put(lip, x, 1, STEEL[5])
        put(lip, x, 2, STEEL[5])
        put(lip, x, 3, STEEL[3])
    for x in (5, 11, 21, 27):
        put(lip, x, 0, STEEL[8])
        put(lip, x, 1, STEEL[2])
        put(lip, x, 2, STEEL[8])
        put(lip, x, 3, STEEL[1])
    save(lip, "lip")

    ledge = canvas(32)
    for py in range(32):
        for px in range(32):
            rho = math.hypot((px + 0.5) / 2 - 8, (py + 0.5) / 2 - 8) / K_STEEL
            c = STEEL[6] if rho > 5.25 else (STEEL[5] if rho > 4.9 else STEEL[3])
            put(ledge, px, py, c)
    save(ledge, "ledge")

    # shock plate: brushed steel with a bevel, four corner bolts, and a shadow round the
    # raised face plate (which covers texels 3..12 x 3..12 of this face)
    rng = random.Random(5)
    pad = canvas(16, fill=STEEL[4])
    for y in range(16):
        for x in range(16):
            if rng.random() < 0.22:
                put(pad, x, y, STEEL[5] if y % 2 else STEEL[3])
    for i in range(16):
        put(pad, i, 0, STEEL[6])
        put(pad, 0, i, STEEL[6])
        put(pad, i, 15, STEEL[2])
        put(pad, 15, i, STEEL[2])
    for i in range(3, 13):
        put(pad, i, 13, STEEL[2])
        put(pad, 13, i, STEEL[2])
    for bx, by in ((1, 1), (13, 1), (1, 13), (13, 13)):
        put(pad, bx, by, STEEL[8])
        put(pad, bx + 1, by, STEEL[6])
        put(pad, bx, by + 1, STEEL[6])
        put(pad, bx + 1, by + 1, STEEL[1])
    save(pad, "pad")

    # face plate (2.3 x 1.6 units): bevelled, with a dark socket seating the round lens
    face = canvas(16, fill=STEEL[5])
    for y in range(16):
        for x in range(16):
            z_, y_ = (x + 0.5) / 16 * 2.3 - 1.15, (y + 0.5) / 16 * 1.6 - 0.8
            r = math.hypot(z_, y_)
            if r < 0.95:
                put(face, x, y, STEEL[1] if r > 0.7 else STEEL[0])
            elif r < 1.1 and y_ > 0:
                put(face, x, y, STEEL[4])              # soft shade under the socket's lip
    for i in range(16):
        put(face, i, 0, STEEL[7])
        put(face, 0, i, STEEL[7])
        put(face, i, 15, STEEL[3])
        put(face, 15, i, STEEL[3])
    put(face, 1, 1, STEEL[8])
    save(face, "pad_face")

    side = canvas(16, fill=STEEL[3])
    for i in range(16):
        put(side, i, 0, STEEL[5])
    save(side, "pad_side")

    lens_side = canvas(16, fill=STEEL[1])
    for i in range(16):
        put(lens_side, i, 0, STEEL[4])
    save(lens_side, "lens_side")


def lens_frame(t: float):
    """The lens face, radial: a charging core, the flash, then a ripple running out."""
    img = canvas(16)
    q = charge(t)
    f = flash(t)
    p = (t - T_FIRE) / 0.25
    for py in range(16):
        for px in range(16):
            r = math.hypot(px + 0.5 - 8, py + 0.5 - 8) / 8      # 0 centre .. 1 rim
            lvl = 2 + round(q * 3 * (1.0 - 0.6 * r))
            if r < 0.4:
                lvl += 1
            if 0 <= p < 1 and abs(r - p) < 0.2:
                lvl = max(lvl, 5 - round(2 * p))
            lvl = max(lvl, round(7 * f * (1.0 - 0.5 * r)))
            put(img, px, py, LENS[max(0, min(7, lvl))])
    return img


def conduit_frame(t: float):
    """The glowing groove round the band: energy streams into the plates as they charge,
    the whole groove flashes as they fire, then it cools to a dull red."""
    img = canvas(32)
    q, f = charge(t), flash(t)
    for px in range(32):
        to_plate = min(abs(px + 0.5 - c) for c in (0, 16, 32))       # texels to the nearest plate
        dash = (to_plate / 3 + 6 * t) % 1.0 < 0.34                   # dashes stream toward it
        lvl = 1 + round(q * (4.5 if dash else 1.5))
        lvl = max(lvl, round(7 * f))
        put(img, px, 0, CONDUIT[max(0, min(7, lvl))])
        put(img, px, 1, CONDUIT[max(0, min(7, lvl - 1))])
    return img


def textures() -> None:
    paint_shell()
    paint_caps()
    paint_steel()
    # the animated parts all share one 24-frame loop, so they stay in step
    save_animation(animate(flare_frame, FRAMES), "flare", frametime=FRAMETIME)
    save_animation(animate(lambda t: flare_cap_frame(t, True), FRAMES), "flare_up", frametime=FRAMETIME)
    save_animation(animate(lambda t: flare_cap_frame(t, False), FRAMES), "flare_down", frametime=FRAMETIME)
    save_animation(animate(lens_frame, FRAMES), "lens", frametime=FRAMETIME)
    save_animation(animate(conduit_frame, FRAMES), "conduit", frametime=FRAMETIME)


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------
SIDE_PHI = {"east": 0.0, "west": 180.0, "south": 90.0, "north": -90.0}


def face_phi(element: dict, side: str) -> float:
    """Azimuth (degrees from +X toward +Z) an outer side face of a turned slab looks along."""
    r = element.get("rotation")
    return SIDE_PHI[side] - (r["angle"] if r else 0.0)


def wrap_u(phi: float, period: float = 180.0, centre: float = 4.0) -> list[float]:
    """u range of a 22.5-degree facet looking along phi, with the texture wrapped round
    the egg: u grows to the viewer's right and azimuth 0 lands on u = centre."""
    left = -phi - 11.25
    u0 = round(((left + centre * period / 16) % period) * 16 / period, 3) % 16
    return [u0, round(u0 + 22.5 * 16 / period, 3)]


def radial_uv(element: dict, k: float) -> list[float]:
    (x0, _, z0), (x1, _, z1) = element["from"], element["to"]
    hx, hz = (x1 - x0) / 2 * k, (z1 - z0) / 2 * k
    return [round(8 - hx, 4), round(8 - hz, 4), round(8 + hx, 4), round(8 + hz, 4)]


def wrap(parts: list[dict], side_tex: str, v: tuple[float, float], up: str | None, down: str | None,
         k: float) -> list[dict]:
    """Retexture turned slabs: wrapped sides, radial tops and bottoms."""
    for e in parts:
        for side, face in e["faces"].items():
            if side in SIDE_PHI:
                u0, u1 = wrap_u(face_phi(e, side))
                face.update(texture="#" + side_tex, uv=[u0, v[0], u1, v[1]])
            else:
                face.update(texture="#" + (up if side == "up" else down), uv=radial_uv(e, k))
    return parts


def shell() -> list[dict]:
    parts = turn(lathe(EGG_CENTER, EGG_PROFILE, "shell", sides=16, uv="full"), SPIN, "y", (8, 0, 8))
    out = []
    for k in range(len(SLICES)):
        slabs = parts[8 * k: 8 * k + 8]
        out += wrap(slabs, "shell", (START[k] / 2, (START[k] + ROWS[k]) / 2), "cap_up", "cap_down", K_SHELL)
    return out


def light_layer() -> list[dict]:
    """A skin just proud of the shell (0.06 at the sides, 0.05 above and below each slice)
    carrying the animated light (flare_frame, flare_cap_frame). Only lit texels are opaque,
    so at rest it is invisible and the silhouette stays the egg's."""
    proud = [(h, r + 0.06) for h, r in EGG_PROFILE]
    parts = turn(lathe(EGG_CENTER, proud, "flare", sides=16, uv="full", glow=15, shade=False),
                 SPIN, "y", (8, 0, 8))
    out = []
    for k in range(len(SLICES)):
        if k == 3:                                      # hidden under the band
            continue
        slabs = parts[8 * k: 8 * k + 8]
        for e in slabs:
            e["from"][1] = round(e["from"][1] - 0.05, 4)
            e["to"][1] = round(e["to"][1] + 0.05, 4)
        out += wrap(slabs, "flare", (START[k] / 2, (START[k] + ROWS[k]) / 2), "flare_up", "flare_down", K_FLARE)
    return out


def ring_of(radius: float, y0: float, y1: float, tex: str, **kw) -> list[dict]:
    """A 16-sided ring between heights y0 and y1, spun like the shell."""
    return turn(prism((8, (y0 + y1) / 2, 8), radius, y1 - y0, tex, sides=16, **kw), SPIN, "y", (8, 0, 8))


YC = Y0 + (BAND_LO + BAND_HI) / 2      # the band's centre line


def band() -> list[dict]:
    y_lo, y_hi = Y0 + 3.22, Y0 + 5.08
    parts = wrap(ring_of(5.25, y_lo + 0.3, y_hi - 0.3, "band"), "band", (0, 2), "ledge", "ledge", K_STEEL)
    parts += wrap(ring_of(5.5, y_hi - 0.3, y_hi, "lip"), "lip", (0, 1), "ledge", "ledge", K_STEEL)
    parts += wrap(ring_of(5.5, y_lo, y_lo + 0.3, "lip"), "lip", (1, 2), "ledge", "ledge", K_STEEL)
    conduit = ring_of(5.31, YC - 0.24, YC + 0.24, "conduit", glow=10, shade=False)
    for e in conduit:
        e["faces"].pop("up")
        e["faces"].pop("down")
    parts += wrap(conduit, "conduit", (0, 1), None, None, 0)
    return parts


def plate() -> list[dict]:
    """One shock plate on the +X side of the band: base, raised face, glowing lens."""
    base = box((13.0, YC - 1.15, 8 - 1.7), (13.85, YC + 1.15, 8 + 1.7), "pad_side",
               faces={"east": ("pad", [0, 0, 16, 16])})
    face = box((13.85, YC - 0.8, 8 - 1.15), (14.1, YC + 0.8, 8 + 1.15), "pad_side",
               faces={"east": ("pad_face", [0, 0, 16, 16])})
    lens = prism((14.25, YC, 8), 0.72, 0.3, "lens_side", axis="x", sides=8, cap="lens", glow=12, shade=False)
    for e in lens:
        for side in ("up", "down"):
            e["faces"][side]["uv"] = radial_uv(e, 8 / 0.75)
    return [base, face] + lens


def plates() -> list[dict]:
    parts = []
    for phi in (45, 135, 225, 315):
        parts += turn(plate(), -phi, "y", (8, 0, 8))
    return parts


def build() -> list[dict]:
    return shell() + band() + plates() + light_layer()


GRIP = (8.0, Y0 + 4.4, 8.0)   # the fist closes round the band, the egg rising out of it
SIZE = 1.4


def models() -> dict:
    parts = build()
    # Every view looks straight at a shock plate and its chevron stacks: the GUI (level, so
    # the lit faces stay bright orange), the thrown egg (billboarded through "ground"), item
    # frames and shelves, and the plate facing forward in the hand.
    d = display(KIND, parts, grip=GRIP, size=SIZE, gui_rotation=(0, -45, 0))
    d["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (-1, 0, -1)}, GRIP, "fist", 0.45 * SIZE)
    d["firstperson_righthand"] = place({"y": (-0.2, 1, 0), "z": (0.45, 0, 1)}, GRIP, (0.5, -0.3, -0.9),
                                       0.46 * SIZE, pose=None)
    d["ground"] = fit(parts, (0, 45, 0), 8.0, lift=2.0)
    d["fixed"] = fit(parts, (0, 135, 0), 14.0)
    d["on_shelf"] = fit(parts, (0, 135, 0), 12.0)
    return {"main": model(parts, d)}
