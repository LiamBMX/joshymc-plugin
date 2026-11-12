"""Eagle Wings: huge feathered eagle wings worn on the back (Survival, issue #1516).

The worn wings are this model, drawn untransformed by a non-persistent ItemDisplay that
rides the wearer (BackCosmeticManager), scaled by BACK_SCALE: about 3.7 blocks from tip to
tip, far past any chestplate or elytra. The same model is the item icon, held item and
item-frame look through its display transforms. The chest slot item underneath is a plain
Netherite Chestplate; its worn humanoid layer is a dark leather harness with gold fittings
and an eagle crest on the chest, so the wings have something to hang from.

Wings: a raised, spread eagle pose. A thick golden leading edge rises from gold shoulder
sockets to the wrists above the wearer's head; under it, rows of lesser, median and greater
coverts in tan, gold and brown shingle over long dark-brown secondaries that hang to the
knees, and ten primaries fan out from the hand to separated, fingered tips (the eagle
silhouette, never a dragon or angel wing). Cream marginal feathers line the upper wing.
Every feather is its own thin plane, tilted and staggered in depth, so the rows cast real
shadows; the underside (seen from in front of the wearer) is a paler, mottled underwing.
The mount is a gold-rimmed leather back plate with an eagle crest medallion and an amber gem.

Animation: a slow gold glint runs over the crest and sockets; the gem breathes.

Frame: model (8, 8, 8) is the mount point on the wearer's back. +X = across the wings,
+Y = up, +Z = away from the back (the dorsal side, seen from behind). Keep every part at
z >= 6 so nothing reaches into the wearer's head or chest.
"""
from __future__ import annotations

import math
import random

import numpy as np
from PIL import Image, ImageDraw

from art.kit import (_set_rotation, HUMANOID, bar, box, copy, display, mirror, model, place, prism, region, rgba, save, save_animation,
                     save_layer, shade, shine, turn)

ID = "eagle_wings"
NAME = "Eagle Wings"
KIND = "chestplate"
COUNTERPART = "item/netherite_chestplate"
# Size of the worn wings: the plugin's ItemDisplay scale (EagleWings.kt backScale).
BACK_SCALE = 1.25

# --------------------------------------------------------------------------------------
# Palette (darkest -> lightest, hue-shifted: shadows lean violet, lights lean gold)
# --------------------------------------------------------------------------------------

DARK = ["#0d0806", "#170e09", "#22140c", "#2f1b10", "#3e2414", "#4e2f1a", "#613c21", "#774b2a", "#8f5d35"]
BROWN = ["#1f120b", "#2e1a10", "#402516", "#55321d", "#6b4125", "#82522f", "#9b663b", "#b47c4b", "#cb955f"]
TAN = ["#3d2410", "#583516", "#76491d", "#966125", "#b57b31", "#cf9844", "#e3b55e", "#f0cf83", "#f8e4ae"]
CREAM = ["#7a6447", "#9d8664", "#bea883", "#d8c6a2", "#eadcbf", "#f6eedb", "#fffaf0"]
UNDER = ["#17100d", "#251a16", "#352621", "#47352d", "#5b463b", "#70594b", "#876d5d", "#9f8571", "#b89e88"]
GOLD = ["#2e1a05", "#553409", "#80520f", "#ab7419", "#d39a2a", "#efc146", "#fbdd7c", "#fff3c4"]
LEATHER = ["#120a08", "#1d110c", "#2a1912", "#382218", "#482d1f", "#5b3a28", "#704a33"]
AMBER = ["#4a1802", "#8c3405", "#d0620c", "#ff9420", "#ffc35a", "#fff0b8"]

# --------------------------------------------------------------------------------------
# Texture helpers (numpy, painted per pixel)
# --------------------------------------------------------------------------------------

BAYER = np.array([0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5], np.float32).reshape(4, 4) / 16 - 0.47


def _lut(colours) -> np.ndarray:
    return np.stack([np.array(rgba(c), np.float32) for c in colours])


def _img(arr: np.ndarray) -> Image.Image:
    return Image.fromarray(np.clip(np.rint(arr), 0, 255).astype(np.uint8), "RGBA")


def _pick(lut: np.ndarray, tone: np.ndarray) -> np.ndarray:
    """Colours from a ramp for tones 0..1 (ordered dither between steps, crisp pixels)."""
    h, w = tone.shape
    n = len(lut) - 1
    d = np.tile(BAYER, (h // 4 + 1, w // 4 + 1))[:h, :w]
    idx = np.clip(np.floor(tone * n + 0.5 + d * 0.55), 0, n).astype(int)
    return lut[idx]


def feather(w: int, h: int, ramp, tip=None, base=None, shape: str = "round", seed: int = 0,
            bars: int = 0, fringe=None, under: bool = False, shaft: float = 0.42, flat: bool = False) -> Image.Image:
    """One feather, tip at the top of the texture, quill at the bottom.

    ramp    the vane colours; tip/base optional ramps blended in toward the ends
    shape   "round" (coverts, secondaries), "finger" (eagle primaries: the inner vane is
            notched halfway up so the tip narrows into a separated finger), "pointed"
    bars    faint darker cross bars (golden eagle barring)
    fringe  ramp for a pale 1 px rim on the trailing vane
    under   the paler, mottled underside
    flat    no barbs, barring or noise: the calmer inventory-icon version
    """
    rng = np.random.default_rng(seed)
    Y, X = np.mgrid[0:h, 0:w].astype(np.float32)
    t = 1.0 - (Y + 0.5) / h                  # 0 at the quill, 1 at the tip
    cx = shaft * w                           # the shaft sits off-centre: narrow leading vane
    # Half-widths of the two vanes along the length.
    grow = np.clip(t / 0.22, 0, 1) ** 0.6
    if shape == "round":
        cap = np.sqrt(np.clip(1 - ((t - 0.8) / 0.2) ** 2, 0, 1)) * (t > 0.8) + (t <= 0.8)
        lead, trail = 0.40 * grow * cap, 0.58 * grow * cap
    elif shape == "finger":
        notch = np.clip((t - 0.48) / 0.1, 0, 1)
        trail = 0.58 * grow * (1 - 0.66 * notch)
        lead = 0.36 * grow * (1 - 0.3 * notch)
        cap = np.sqrt(np.clip(1 - ((t - 0.86) / 0.14) ** 2, 0, 1)) * (t > 0.86) + (t <= 0.86)
        lead, trail = lead * cap, trail * cap
    else:  # pointed
        taper = np.clip((1 - t) / 0.35, 0, 1) ** 0.7
        lead, trail = 0.40 * grow * taper, 0.56 * grow * taper
    dx = (X + 0.5 - cx) / w
    inside = np.where(dx < 0, -dx <= lead + 1e-3, dx <= trail + 1e-3) & (t > 0.015)
    # Edge distance (in px) for rims and a little roundness in the shading.
    half = np.where(dx < 0, lead, trail) * w
    edge = half - np.abs(X + 0.5 - cx)
    lut = _lut(ramp)
    # Light from the upper left; the vanes roll off toward their edges.
    tone = 0.62 + 0.18 * np.where(dx < 0, 1, -0.4) * np.clip(np.abs(dx) * 3, 0, 1)
    tone -= 0.20 * np.clip(1 - edge / 2.2, 0, 1)
    # Barbs: thin diagonal lines sweeping toward the tip, away from the shaft.
    barb = np.sin((Y + np.abs(X + 0.5 - cx) * 1.3) * (2 * math.pi / 3.0) + rng.uniform(0, 6))
    tone -= 0.09 * (barb > 0.55) * (not flat)
    if bars and not flat:
        band = np.sin(t * bars * 2 * math.pi + 0.6)
        tone -= 0.10 * (band > 0.62) * (t > 0.18)
    tone += rng.normal(0, 0.035, tone.shape) * (not flat)
    if under:
        tone += 0.04 * (rng.random(tone.shape) < 0.12)
        tone -= 0.22 * (edge < 1.0)          # a dark rim so each underside feather separates
    colour = _pick(lut, np.clip(tone, 0, 1))
    dith = np.tile(BAYER, (h // 4 + 1, w // 4 + 1))[:h, :w] + 0.5
    if tip is not None:
        k = np.clip((t - 0.58) / 0.3, 0, 1)
        colour = np.where((k > dith)[..., None], _pick(_lut(tip), np.clip(tone, 0, 1)), colour)
    if base is not None:
        k = np.clip((0.32 - t) / 0.22, 0, 1)
        colour = np.where((k > dith)[..., None], _pick(_lut(base), np.clip(tone + 0.08, 0, 1)), colour)
    if fringe is not None:
        fl = _lut(fringe)
        rim = (edge < 1.0) & (dx > 0) & (t > 0.72)
        colour[rim] = fl[min(len(fl) - 1, 3)]
    # The shaft: a line a little lighter than the vane, pale only at the quill.
    shaft_px = (np.abs(X + 0.5 - cx) < 0.5 + 0.35 * (t < 0.25)) & (t < 0.9)
    lighter = _pick(lut, np.clip(tone + (-0.22 if under else 0.16), 0, 1))
    quill = _lut(CREAM if not under else UNDER[4:])[2]
    shaft_col = np.where((t < 0.22)[..., None], quill, lighter)
    colour = np.where(shaft_px[..., None], shaft_col, colour)
    colour[..., 3] = np.where(inside, 255, 0)
    return _img(colour)


def scales(size: int, ramp, rows: int = 4, seed: int = 0) -> Image.Image:
    """Small rounded feathers stacked like roof tiles (marginal coverts on the leading edge)."""
    lut = _lut(ramp)
    rng = np.random.default_rng(seed)
    Y, X = np.mgrid[0:size, 0:size].astype(np.float32)
    cell = size / rows
    row = np.floor(Y / cell)
    off = (row % 2) * cell / 2
    lx = ((X + off) % cell) / cell - 0.5
    ly = (Y % cell) / cell
    # Each tile is a scallop: bright on top, dark under the next row's overlap.
    d = np.sqrt(lx ** 2 + (ly - 0.15) ** 2 * 0.8)
    tone = 0.78 - 0.55 * ly - 0.25 * (d > 0.5)
    tone += rng.normal(0, 0.04, tone.shape)
    tone -= 0.12 * (np.abs(lx) < 0.06) * (ly > 0.2)
    out = _pick(lut, np.clip(tone, 0, 1))
    out[..., 3] = 255
    return _img(out)


def metal(size: int, ramp, seed: int = 0, glint_t: float | None = None) -> Image.Image:
    """Brushed gold with a bevel."""
    lut = _lut(ramp)
    rng = np.random.default_rng(seed)
    Y, X = np.mgrid[0:size, 0:size].astype(np.float32)
    tone = 0.62 - 0.25 * (Y / size) + 0.06 * np.sin(Y * 1.7 + rng.uniform(0, 6))
    tone += rng.normal(0, 0.03, tone.shape)
    edge = np.minimum(np.minimum(X, Y), np.minimum(size - 1 - X, size - 1 - Y))
    tone = np.where(edge < 1, np.where((X < 1) | (Y < 1), 0.95, 0.18), tone)
    out = _pick(lut, np.clip(tone, 0, 1))
    out[..., 3] = 255
    img = _img(out)
    if glint_t is not None:
        img = shine(img, glint_t, colour=GOLD[-1], width=4, strength=0.8, pause=0.55)
    return img


def leather(size: int, seed: int = 0) -> Image.Image:
    lut = _lut(LEATHER)
    rng = np.random.default_rng(seed)
    Y, X = np.mgrid[0:size, 0:size].astype(np.float32)
    tone = 0.55 + rng.normal(0, 0.07, (size, size))
    edge = np.minimum(np.minimum(X, Y), np.minimum(size - 1 - X, size - 1 - Y))
    tone -= 0.3 * (edge < 1)
    stitch = ((edge >= 1.5) & (edge < 2.5)) & ((X + Y) % 3 < 1.5)
    out = _pick(lut, np.clip(tone, 0, 1))
    out[stitch] = np.array(rgba(GOLD[3]), np.float32)
    out[..., 3] = 255
    return _img(out)


def eagle_mask(size: int) -> np.ndarray:
    """A heraldic spread eagle (wings raised, slotted wingtips, fanned tail, head turned to
    the wearer's right with a hooked beak) as a boolean mask filling a size x size square."""
    ss = 8
    big = Image.new("L", (size * ss, size * ss), 0)
    draw = ImageDraw.Draw(big)

    def pt(u, v):
        return ((u + 1) / 2 * size * ss, (v + 1) / 2 * size * ss)

    wing = [(0.10, -0.12), (0.28, -0.38), (0.50, -0.62), (0.74, -0.84), (0.94, -0.92), (0.92, -0.68),
            (0.80, -0.70), (0.90, -0.46), (0.76, -0.47), (0.84, -0.24), (0.68, -0.26), (0.72, -0.04),
            (0.54, -0.08), (0.48, 0.08), (0.30, 0.04), (0.14, 0.14)]
    for side in (1, -1):
        draw.polygon([pt(side * u, v) for u, v in wing], fill=255)
    draw.ellipse([pt(-0.17, -0.30), pt(0.17, 0.42)], fill=255)                     # body
    draw.ellipse([pt(-0.15, -0.60), pt(0.13, -0.30)], fill=255)                     # head
    draw.polygon([pt(0.08, -0.52), pt(0.30, -0.47), pt(0.27, -0.36), pt(0.18, -0.40), pt(0.08, -0.38)],
                 fill=255)                                                           # hooked beak
    draw.polygon([pt(-0.16, 0.34), pt(0.16, 0.34), pt(0.36, 0.80), pt(0.16, 0.70), pt(0.0, 0.84),
                  pt(-0.16, 0.70), pt(-0.36, 0.80)], fill=255)                       # tail fan
    for side in (1, -1):                                                             # talons
        draw.polygon([pt(side * 0.10, 0.30), pt(side * 0.34, 0.42), pt(side * 0.30, 0.50), pt(side * 0.12, 0.42)],
                     fill=255)
    a = np.asarray(big, np.float32).reshape(size, ss, size, ss).mean(axis=(1, 3))
    return a >= 110


def _shift(m: np.ndarray, dx: int, dy: int) -> np.ndarray:
    out = np.zeros_like(m)
    h, w = m.shape
    out[max(0, dy):h + min(0, dy), max(0, dx):w + min(0, dx)] = m[max(0, -dy):h - max(0, dy), max(0, -dx):w - max(0, dx)]
    return out


def crest(size: int, t: float | None) -> Image.Image:
    """The round crest medallion: a gold disc with a raised spread eagle and an amber eye."""
    lut = _lut(GOLD)
    Y, X = np.mgrid[0:size, 0:size].astype(np.float32)
    u = (X + 0.5) / size * 2 - 1
    v = (Y + 0.5) / size * 2 - 1
    r = np.hypot(u, v)
    tone = 0.42 - 0.18 * v
    tone = np.where(r > 0.88, 0.86 - 0.4 * (v > 0), tone)          # raised rim
    tone = np.where((r > 0.8) & (r <= 0.88), 0.14, tone)            # groove
    inner = int(size * 0.74)
    off = (size - inner) // 2
    mask = np.zeros((size, size), bool)
    mask[off:off + inner, off:off + inner] = eagle_mask(inner)
    shadow = _shift(mask, 1, 1) & ~mask
    light = mask & ~_shift(mask, 1, 1)
    tone = np.where(shadow, 0.08, tone)
    tone = np.where(mask, 0.74 - 0.12 * v, tone)
    tone = np.where(light, 0.97, tone)
    out = _pick(lut, np.clip(tone, 0, 1))
    eye = (int(size * 0.53), int(size * 0.33))
    out[eye[1], eye[0]] = np.array(rgba(AMBER[3]), np.float32)
    out[..., 3] = np.where(r <= 1.0, 255, 0)
    img = _img(out)
    if t is not None:
        img = shine(img, t, colour=GOLD[-1], width=5, strength=0.85, pause=0.5)
    return img


def gem(t: float) -> Image.Image:
    lut = _lut(AMBER)
    size = 16
    Y, X = np.mgrid[0:size, 0:size].astype(np.float32)
    r = np.hypot(X + 0.5 - 8, Y + 0.5 - 8) / 8
    pulse = 0.5 - 0.5 * math.cos(2 * math.pi * t)
    tone = 0.35 + 0.45 * (1 - r) + 0.18 * pulse
    tone = np.where((X > 3) & (X < 7) & (Y > 3) & (Y < 7), 1.0, tone)
    out = _pick(lut, np.clip(tone, 0, 1))
    out[..., 3] = 255
    return _img(out)


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

PRIMARY_TIP = DARK
SECONDARY = BROWN


def textures() -> None:
    # Primaries: near-black-brown fingers on dark-brown vanes with golden quills.
    for i in range(3):
        save(feather(16, 64, DARK[1:], tip=DARK[:6], base=BROWN[3:], shape="finger", seed=10 + i,
                     fringe=BROWN[3:]), f"primary_{i}")
        save(feather(16, 64, UNDER[:7], tip=DARK[1:], base=UNDER[4:], shape="finger", seed=20 + i, under=True, bars=5),
             f"primary_{i}_under")
    # The two outermost primaries: a subtle cream edge toward the tip (the "white accent").
    save(feather(16, 64, DARK[1:], tip=DARK[:6], base=BROWN[3:], shape="finger", seed=13, fringe=CREAM[3:]),
         "primary_pale")
    # Secondaries: deep brown with faint barring, darker tips and a pale fringe at the end.
    for i in range(3):
        save(feather(16, 64, BROWN[:7], tip=DARK[1:], base=BROWN[2:], shape="round", seed=30 + i, bars=4,
                     fringe=BROWN[5:], shaft=0.45), f"secondary_{i}")
        save(feather(16, 64, UNDER[1:], tip=DARK[2:], base=UNDER[4:], shape="round", seed=40 + i, bars=4, under=True,
                     shaft=0.45), f"secondary_{i}_under")
    # Tertials near the body: warm brown, golden edges.
    save(feather(16, 64, BROWN[1:], tip=TAN[2:], shape="round", seed=50, bars=3, fringe=TAN[4:]), "tertial")
    # Coverts in bands: greater (rich brown), median (golden brown), lesser (gold).
    save(feather(16, 32, BROWN[2:], tip=BROWN[1:], base=TAN[2:], shape="round", seed=60, fringe=TAN[4:], shaft=0.48),
         "greater")
    save(feather(16, 32, TAN[1:7], tip=TAN[2:], shape="round", seed=61, fringe=CREAM, shaft=0.48), "median")
    save(feather(16, 16, TAN[3:], tip=CREAM, shape="round", seed=62, shaft=0.5), "lesser")
    save(feather(16, 32, UNDER, shape="round", seed=63, under=True, shaft=0.48), "covert_under")
    save(feather(16, 32, DARK[2:], tip=DARK[1:], base=BROWN[3:], shape="round", seed=64, fringe=GOLD[4:], shaft=0.48),
         "primary_covert")
    save(feather(16, 32, CREAM, tip=TAN[4:], shape="pointed", seed=65, shaft=0.4), "alula")
    # Calmer copies for the inventory icon (the fine barbs shimmer when shrunk to a slot).
    save(feather(16, 64, BROWN[1:], tip=DARK[3:], base=TAN[3:], shape="finger", flat=True), "icon_primary")
    save(feather(16, 64, BROWN[2:], tip=BROWN[1:], base=BROWN[4:], shape="round", flat=True, shaft=0.45),
         "icon_secondary")
    save(feather(16, 32, BROWN[3:], tip=BROWN[2:], base=TAN[3:], shape="round", flat=True, shaft=0.48), "icon_greater")
    save(feather(16, 32, TAN[2:], tip=TAN[3:], shape="round", flat=True, shaft=0.48, fringe=CREAM[2:]), "icon_median")
    save(scales(32, CREAM[1:] , rows=5, seed=1), "marginal")
    save(scales(32, TAN[3:], rows=4, seed=2), "marginal_dark")
    save(metal(32, GOLD, seed=3), "gold")
    save_animation([metal(32, GOLD, seed=3, glint_t=i / 16) for i in range(16)], "gold_glint", frametime=3)
    save_animation([crest(32, i / 16) for i in range(16)], "crest", frametime=3)
    save_animation([gem(i / 12) for i in range(12)], "gem", frametime=3, interpolate=True)
    save(leather(32, seed=4), "leather")
    save_layer(paint_harness(), "humanoid")


# --------------------------------------------------------------------------------------
# Worn harness (humanoid layer, 4x = 256x128)
# --------------------------------------------------------------------------------------

S = 4


def paint_harness() -> Image.Image:
    """Dark leather torso harness: crossed straps, gold buckles, a gold eagle crest on the
    chest, gold shoulder caps with feather pauldrons on the arms, a back plate the wings
    plug into. Only body and arm regions are painted."""
    w, h = 64 * S, 32 * S
    img = np.zeros((h, w, 4), np.float32)
    rng = np.random.default_rng(7)
    leather_lut = _lut(LEATHER)
    gold_lut = _lut(GOLD)
    tan_lut = _lut(TAN)
    brown_lut = _lut(BROWN)

    def put(x, y, col):
        if 0 <= x < w and 0 <= y < h:
            img[y, x] = col

    def rect(box_, lut, tone, noise=0.05):
        x0, y0, x1, y1 = box_
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                k = int(np.clip(tone + rng.normal(0, noise), 0, 0.999) * len(lut))
                put(x, y, lut[k])

    # Body faces: a leather undercoat (vest) with a darker border.
    for name in ("body_front", "body_back", "body_right", "body_left", "body_top", "body_bottom"):
        rect(region(HUMANOID, name, S), leather_lut, 0.45)
    fx0, fy0, fx1, fy1 = region(HUMANOID, "body_front", S)
    bx0, by0, bx1, by1 = region(HUMANOID, "body_back", S)
    fw = fx1 - fx0 + 1
    # Crossed straps on the front (X) and back (X), tan leather with gold rivets.
    for (x0, y0, x1, y1) in ((fx0, fy0, fx1, fy1), (bx0, by0, bx1, by1)):
        ww = x1 - x0 + 1
        hh = y1 - y0 + 1
        for y in range(hh):
            for k, sign in ((0, 1), (1, -1)):
                cx = (ww * 0.12 + (ww * 0.76) * (y / hh)) if sign > 0 else (ww * 0.88 - (ww * 0.76) * (y / hh))
                for dx in range(-3, 4):
                    x = int(cx + dx)
                    tone = 0.62 + (0.15 if dx < -1 else -0.18 if dx > 1 else 0)
                    put(x0 + x, y0 + y, tan_lut[int(np.clip(tone, 0, 0.99) * len(tan_lut))])
                if y % 8 == 4:
                    put(x0 + int(cx), y0 + y, gold_lut[6])
        # Belt across the waist.
        rect((x0, y1 - 7, x1, y1 - 2), brown_lut, 0.35)
        rect((x0 + ww // 2 - 4, y1 - 8, x0 + ww // 2 + 3, y1 - 1), gold_lut, 0.6)
        rect((x0 + ww // 2 - 2, y1 - 6, x0 + ww // 2 + 1, y1 - 3), leather_lut, 0.3)
    # Gold crest on the chest where the straps cross: the medallion's spread eagle, 20 px.
    emblem = np.asarray(crest(20, None), np.float32)
    ex, ey = int(fx0 + fw / 2 - 10), fy0 + 8
    for y in range(20):
        for x in range(20):
            if emblem[y, x, 3] > 0:
                put(ex + x, ey + y, emblem[y, x])
    # Back plate the wings plug into: gold-rimmed panel up the spine.
    rect((bx0 + 9, by0 + 2, bx1 - 9, by0 + 30), leather_lut, 0.7)
    for x in range(bx0 + 9, bx1 - 8):
        put(x, by0 + 2, gold_lut[6])
        put(x, by0 + 30, gold_lut[2])
    for y in range(by0 + 2, by0 + 31):
        put(bx0 + 9, y, gold_lut[5])
        put(bx1 - 9, y, gold_lut[2])
    # Arms: tan feather pauldrons on the shoulders, leather bracer bands below.
    for name in ("arm_outer", "arm_front", "arm_back", "arm_inner", "arm_top"):
        x0, y0, x1, y1 = region(HUMANOID, name, S)
        rect((x0, y0, x1, y1), leather_lut, 0.4)
        if name == "arm_top":
            for x in range(x0, x1 + 1):
                put(x, y0, gold_lut[5])
                put(x, y1, gold_lut[3])
            continue
        # Pauldron: two rows of rounded brown feathers on the shoulder, one gold trim line.
        tile = np.asarray(scales(16, BROWN[3:], rows=3, seed=5), np.float32)
        for y in range(y0, y0 + 10):
            for x in range(x0, x1 + 1):
                put(x, y, tile[(y - y0) % 16, (x - x0) % 16])
        for x in range(x0, x1 + 1):
            put(x, y0 + 10, gold_lut[5])
        rect((x0, y1 - 11, x1, y1 - 6), brown_lut, 0.3)
        for x in range(x0, x1 + 1):
            put(x, y1 - 11, gold_lut[5])
            put(x, y1 - 6, gold_lut[2])
    return _img(img)


# --------------------------------------------------------------------------------------
# Wing layout (one wing, +X side; u outward from x = 8, v up from y = 8, model units)
# --------------------------------------------------------------------------------------

SHOULDER = (3.4, 0.6)
ELBOW = (8.0, 6.6)
WRIST = (12.6, 10.6)
HAND = (16.4, 12.0)              # where the leading primary leaves the hand


def _catmull(pts, n):
    """A smooth curve through pts, n samples."""
    out = []
    ext = [pts[0]] + list(pts) + [pts[-1]]
    segs = len(pts) - 1
    for k in range(n):
        s = k / (n - 1) * segs
        i = min(int(s), segs - 1)
        t = s - i
        p0, p1, p2, p3 = ext[i], ext[i + 1], ext[i + 2], ext[i + 3]
        out.append(tuple(0.5 * ((2 * p1[j]) + (-p0[j] + p2[j]) * t + (2 * p0[j] - 5 * p1[j] + 4 * p2[j] - p3[j]) * t * t
                                + (-p0[j] + 3 * p1[j] - 3 * p2[j] + p3[j]) * t ** 3) for j in range(2)))
    return out


ARM = _catmull([SHOULDER, ELBOW, WRIST, HAND], 41)


def arm_at(s: float):
    """Point on the leading edge, s = 0 at the shoulder, 1 at the hand."""
    f = s * (len(ARM) - 1)
    i = min(int(f), len(ARM) - 2)
    t = f - i
    return tuple(ARM[i][j] + (ARM[i + 1][j] - ARM[i][j]) * t for j in range(2))


def arm_dir(s: float):
    a, b = arm_at(max(0, s - 0.02)), arm_at(min(1, s + 0.02))
    d = (b[0] - a[0], b[1] - a[1])
    n = math.hypot(*d)
    return d[0] / n, d[1] / n


def zbase(u: float, v: float) -> float:
    """Depth of the wing surface: the wing sweeps back toward the tip and cups slightly."""
    return (9.2 + 5.6 * max(0.0, u / 24) ** 1.5 + 0.06 * max(0.0, -v) + 0.5 * max(0.0, (6 - u) / 6)
            + 0.1 * max(0.0, v))


def P(uv, lift: float = 0.0):
    u, v = uv
    return (8 + u, 8 + v, zbase(u, v) + lift)


# Secondaries hang from the forearm (s 0.06..0.6) to a scalloped trailing edge.
N_SEC = 10
# Primaries leave the hand (s 0.6..1.0); tips, innermost first, trace the rounded wingtip.
PRIMARY_TIPS = [(16.4, -9.4), (18.3, -7.8), (20.0, -5.6), (21.4, -3.0), (22.5, 0.0),
                (23.2, 3.4), (23.6, 6.9), (23.4, 10.4), (22.6, 13.5), (21.2, 16.2)]


def _frame(p0, p1, roll: float = 0.0):
    """Rotation whose +Y runs from p0 to p1 and whose +Z (the dorsal face) stays toward +Z,
    so a feather's top side always faces behind the wearer whichever way it points."""
    d = np.array(p1, float) - np.array(p0, float)
    y = d / np.linalg.norm(d)
    z = np.array([0.0, 0.0, 1.0]) - y[2] * y
    z /= np.linalg.norm(z)
    x = np.cross(y, z)
    m = np.stack([x, y, z], axis=1)
    if roll:
        a = math.radians(roll)
        r = np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])
        m = m @ r
    return tuple(tuple(float(v) for v in row) for row in m), float(np.linalg.norm(d))


def oriented(p0, p1, width, depth, tex, roll: float = 0.0, **box_kwargs) -> dict:
    """Like kit.bar(), but with a fixed up side (see _frame)."""
    m, length = _frame(p0, p1, roll)
    mid = [(p0[i] + p1[i]) / 2 for i in range(3)]
    e = box((mid[0] - width / 2, mid[1] - length / 2, mid[2] - depth / 2),
            (mid[0] + width / 2, mid[1] + length / 2, mid[2] + depth / 2), tex, **box_kwargs)
    _set_rotation(e, m, mid)
    return e


def _plane(p0, p1, width, tex, under, roll: float = 0.0, glow=0):
    """A feather: a thin double-sided plane from quill p0 to tip p1."""
    return oriented(p0, p1, width, 0.2, tex, roll=roll, skip=("east", "west", "up", "down"),
                    faces={"south": (tex, [0, 0, 16, 16]), "north": (under, [16, 0, 0, 16])}, glow=glow)


def wing(icon: bool = False) -> list[dict]:
    parts: list[dict] = []
    # --- Primaries: ten long fingered feathers fanning from the hand.
    for i, tip in enumerate(PRIMARY_TIPS):
        s = 0.6 + 0.4 * i / (len(PRIMARY_TIPS) - 1)
        base = arm_at(s)
        base = (base[0] - 0.4, base[1] - 0.8)
        lift = 0.12 * i + 0.06 * max(0, i - 5)      # outer primaries lie over the inner ones
        width = 4.8 - 0.15 * i if i < 5 else 3.9 - 0.15 * (i - 5)
        tex = "primary_pale" if i >= 8 else f"primary_{i % 3}"
        parts.append(_plane(P(base, lift), P(tip, lift + 0.5), width, tex, f"primary_{i % 3}_under"))
    # --- Secondaries: from the forearm down to the trailing edge.
    for j in range(N_SEC):
        k = j / (N_SEC - 1)
        s = 0.07 + 0.53 * k
        base = arm_at(s)
        base = (base[0] + 0.2, base[1] - 0.6)
        tip = (3.0 + 12.5 * k, -10.4 - 0.3 * math.sin(math.pi * k) + 0.4 * (j % 2) + 0.6 * k)
        lift = 0.1 * ((j % 2) * 2 - 1) * 0.5 + 0.02 * j
        tex = f"secondary_{j % 3}"
        parts.append(_plane(P(base, lift), P(tip, lift + 0.35), 4.6, tex, tex + "_under"))
    # --- Tertials: three sandy feathers close to the body, a touch behind the secondaries.
    for j, (b, t_) in enumerate((((3.0, 0.0), (2.8, -9.6)), ((4.4, 1.2), (4.0, -8.4)), ((3.6, -1.5), (5.4, -6.4)))):
        parts.append(_plane(P(b, 0.6 + 0.1 * j), P(t_, 0.95 + 0.1 * j), 4.2 - 0.4 * j, "tertial", "covert_under"))
    # --- Greater coverts over the secondaries' upper halves.
    for j in range(9):
        k = j / 8
        s = 0.05 + 0.6 * k
        a = arm_at(s)
        base = (a[0] + 0.1, a[1] - 0.8)
        d = (0.05 + 0.32 * k, -1.0)
        n = math.hypot(*d)
        length = 7.2 - 1.2 * k
        tip = (base[0] + d[0] / n * length, base[1] + d[1] / n * length)
        parts.append(_plane(P(base, 0.9 + 0.05 * (j % 2)), P(tip, 1.35), 4.0, "greater", "covert_under"))
    # --- Primary coverts over the primaries' quills.
    for i in range(7):
        k = i / 6
        s = 0.6 + 0.38 * k
        a = arm_at(s)
        tip_dir = PRIMARY_TIPS[min(9, int(round(k * 9)))]
        d = (tip_dir[0] - a[0], tip_dir[1] - a[1])
        n = math.hypot(*d)
        base = (a[0] - 0.2, a[1] - 0.6)
        length = 5.0
        tip = (base[0] + d[0] / n * length, base[1] + d[1] / n * length)
        parts.append(_plane(P(base, 1.0 + 0.06 * i), P(tip, 1.4 + 0.06 * i), 3.4, "primary_covert",
                            "covert_under"))
    # --- Median coverts.
    for j in range(10):
        k = j / 9
        s = 0.03 + 0.72 * k
        a = arm_at(s)
        base = (a[0] + 0.1, a[1] - 0.4)
        d = (0.1 + 0.45 * k, -1.0)
        n = math.hypot(*d)
        length = 4.6 - 0.6 * k
        tip = (base[0] + d[0] / n * length, base[1] + d[1] / n * length)
        parts.append(_plane(P(base, 1.55 + 0.05 * (j % 2)), P(tip, 1.85), 3.4, "median", "covert_under"))
    # --- Lesser coverts right under the leading edge.
    for j in range(12):
        k = j / 11
        s = 0.02 + 0.86 * k
        a = arm_at(s)
        base = (a[0], a[1] + 0.2)
        d = (0.15 + 0.5 * k, -1.0)
        n = math.hypot(*d)
        length = 3.0 - 0.5 * k
        tip = (base[0] + d[0] / n * length, base[1] + d[1] / n * length)
        parts.append(_plane(P(base, 2.05 + 0.04 * (j % 2)), P(tip, 2.25), 2.8, "lesser", "covert_under"))
    # --- Leading edge: a thick, tapering golden-cream bar of marginal feathers.
    pts = [arm_at(i / 10) for i in range(11)]
    for i in range(10):
        a, b = pts[i], pts[i + 1]
        width = 2.5 - 0.9 * i / 9
        d = (b[0] - a[0], b[1] - a[1])
        n = math.hypot(*d)
        ext = 0.25 / n
        a2 = (a[0] - d[0] * ext, a[1] - d[1] * ext)
        b2 = (b[0] + d[0] * ext, b[1] + d[1] * ext)
        tex = "marginal" if i < 7 else "marginal_dark"
        f = {side: (tex, [0, 0, 16, 16], 90) for side in ("south", "north", "east", "west")}
        parts.append(oriented(P(a2, 2.1), P(b2, 2.1), width, 1.6, tex, faces=f, uv="full"))
    # --- Alula: three small pointed feathers at the wrist, pointing out along the edge.
    for j in range(3):
        a = arm_at(0.66 + 0.04 * j)
        d = arm_dir(0.7)
        base = (a[0] - d[0] * 0.5, a[1] - d[1] * 0.5 + 0.4)
        tip = (a[0] + d[0] * (4.2 - 0.6 * j) + 0.3, a[1] + d[1] * (4.2 - 0.6 * j) - 0.9 - 0.6 * j)
        parts.append(_plane(P(base, 2.5 + 0.08 * j), P(tip, 2.7 + 0.08 * j), 1.7, "alula", "covert_under"))
    # --- Gold cuff around the leading edge where it leaves the socket.
    c0, c1 = arm_at(0.03), arm_at(0.09)
    parts.append(oriented(P(c0, 2.1), P(c1, 2.1), 3.0, 2.2, "gold_glint", uv="full"))
    # --- Gold socket where the wing meets the back plate.
    sx, sy = SHOULDER
    sock = prism((8 + sx - 0.4, 8 + sy, zbase(sx, sy) + 1.4), 1.7, 2.6, "gold_glint", axis="z", sides=8)
    parts += sock
    return parts


def mount() -> list[dict]:
    """The centre: a compact gold-rimmed leather yoke between the shoulder blades, with the
    crest medallion and an amber gem under it. Nothing hangs below the wing roots."""
    parts = []
    z0 = 6.4
    parts.append(box((5.0, 0.6, z0), (11.0, 9.2, z0 + 2.6), "leather", uv="full"))
    # Gold rims: top, sides, and a pointed lower edge (two angled bars meeting in a V).
    parts.append(box((4.6, 8.7, z0 - 0.1), (11.4, 9.6, z0 + 2.9), "gold", uv="full"))
    parts.append(box((4.6, 0.6, z0 - 0.1), (5.4, 9.6, z0 + 2.9), "gold", uv="full"))
    parts.append(box((10.6, 0.6, z0 - 0.1), (11.4, 9.6, z0 + 2.9), "gold", uv="full"))
    for side in (-1, 1):
        parts.append(oriented((8 + side * 3.0, 1.0, z0 + 1.4), (8, -0.6, z0 + 1.4), 0.9, 3.0, "gold", uv="full"))
    parts.append(box((6.4, -0.6, z0 + 0.1), (9.6, 1.0, z0 + 2.5), "leather", uv="full"))
    # Crest medallion: a gold disc (16-sided prism) facing back, crest texture on its face.
    cz = z0 + 2.6
    parts += prism((8, 5.4, cz + 0.5), 3.1, 1.0, "gold_glint", axis="z", sides=16)
    parts.append(box((5.1, 2.5, cz + 0.6), (10.9, 8.3, cz + 1.05), "crest", uv="full",
                     skip=("east", "west", "up", "down", "north")))
    # Amber gem in the V under the crest.
    parts.append(box((7.25, 0.35, cz - 0.4), (8.75, 1.85, cz + 0.7), "gem", uv="full", glow=9))
    # Straps from the yoke over the shoulders (stop short of the neck).
    for side in (-1, 1):
        x = 8 + side * 3.9
        parts.append(bar((x, 9.4, z0 + 0.9), (x + side * 0.6, 11.0, z0 - 0.4), 1.4, 0.7, "leather", uv="full"))
    return parts


def elements(icon: bool = False) -> list[dict]:
    right = wing(icon)
    return mount() + right + mirror(right, "x", 8.0)


ICON_TEXTURES = {"primary_0": "icon_primary", "primary_1": "icon_primary", "primary_2": "icon_primary",
                 "secondary_0": "icon_secondary", "secondary_1": "icon_secondary", "secondary_2": "icon_secondary",
                 "tertial": "icon_secondary", "greater": "icon_greater", "primary_covert": "icon_greater",
                 "median": "icon_median", "primary_pale": "icon_primary"}


def models() -> dict:
    parts = elements()
    d = display(KIND, parts, size=0.62, gui_rotation=(10, 0, 0), gui_span=15.5)
    # First person: a smaller pair low in the corner, so it never covers the crosshair.
    d["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (0.35, 0, 1)}, (8, 8, 8), (0.62, -0.5, -0.95), 0.28,
                                       pose=None)
    icon = copy(parts)
    for e in icon:
        for face in e["faces"].values():
            face["texture"] = "#" + ICON_TEXTURES.get(face["texture"][1:], face["texture"][1:])
    return {"main": model(parts, d), "gui": model(icon, d)}
