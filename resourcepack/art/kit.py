"""Shared toolkit for JoshyMC's 3D item models (Minecraft / Paper 26.2).

Every item is one module, art/items/<id>.py, holding:

    ID, NAME, KIND       module constants; KIND is a key of KINDS below
    textures()           paints every texture with save(), plus save_layer() for worn looks
    models()             returns {model_name: model(...)} with the names KINDS[KIND] lists

Units are model units: 1 unit = 1 pixel of a vanilla 16x16 item = 1/16 block.
Minecraft rejects element coordinates outside [-16, 32] on any axis, so a model can
span 48 units (3 blocks) before its display transform scales it. 26.2 accepts any
rotation angle, and Euler x/y/z rotations on a single element.

Frames used by the display presets (see display()):
  * handheld (sword, pickaxe, axe, shovel, hoe, mace, trident): build it UPRIGHT.
    The handle runs along +Y, centred on x = 8, z = 8; the business end is at the top.
    Blades and heads that stick out sideways (axe, hoe, scythe, pick) point toward -X.
    Pass the point where the hand closes as grip=(8, y, 8).
  * bow and crossbow: build in the vanilla sprite frame, flat-ish in the XY plane like
    the vanilla bow.png / crossbow_standby.png, so the vanilla transforms apply.
  * helmet: build around a virtual head cube spanning [1.6, 14.4] on every axis
    (the wearer's head, 12.8 units wide); face = -Z (north), up = +Y,
    +X = the wearer's right. It renders on the head through the "head" transform.
  * shield: the board stands in the XY plane; its decorated front faces -Z.
  * boots, elytra, food: any frame, centred near (8, 8, 8); presets fit the GUI.
  * cake (placeable food eaten slice by slice): build in BLOCK space. Model y = 0 is the
    floor of the block it is placed on, centred on x = 8, z = 8; one block is 16 units.
    "main" is the whole cake (also the inventory/held model) and "bite_1".."bite_7" are
    the states with that many of its 8 slices eaten. The plugin shows the placed cake
    with an ItemDisplay (no display transform), turned so slice 1 faces the player.
  * item (eggs, coins, keys, fish, gadgets) and leggings: any frame, centred near
    (8, 8, 8), like boots. "main" may also be a flat sprite(): a painted 2D icon that
    Minecraft extrudes like vanilla items (use item/generated display, no display()).

Animated textures: save_animation(frames, name, ...) stacks same-size square frames
into one strip with a .png.mcmeta, and Minecraft plays it everywhere the item shows
(inventory, hand, ground, head, thrown). animate(), wave(), shine() and sparkle() help
paint seamless loops. Worn equipment layers cannot animate; only item textures can.

Optional module constants for items whose game ids differ from the module name:
    MODEL_KEY      the item_model path under assets/joshymc/items/ (default: ID),
                   e.g. "fish/anchovy" for an item that uses joshymc:fish/anchovy
    EQUIPMENT_KEY  the equipment asset id for worn layers (default: ID)
    COUNTERPART    the vanilla sprite the preview shows beside it, e.g. "item/egg"
"""
from __future__ import annotations

import colorsys
import copy as _copy
import math
import random
import re
from pathlib import Path

from PIL import Image, ImageDraw

# --------------------------------------------------------------------------------------
# Kinds, models and worn layers
# --------------------------------------------------------------------------------------

KINDS: dict[str, tuple[str, ...]] = {
    "sword": ("main",),
    "pickaxe": ("main",),
    "axe": ("main",),
    "shovel": ("main",),
    "hoe": ("main",),
    "mace": ("main",),
    "trident": ("main", "throwing"),
    "bow": ("idle", "pull_0", "pull_1", "pull_2"),
    "crossbow": ("idle", "pull_0", "pull_1", "pull_2", "arrow", "firework"),
    "shield": ("main", "blocking"),
    "helmet": ("main",),
    "boots": ("main",),
    "elytra": ("main",),
    "food": ("main",),
    "cake": ("main",) + tuple(f"bite_{i}" for i in range(1, 8)),
    "item": ("main",),
    "leggings": ("main",),
}
CAKE_SLICES = 8  # the cake is gone after the 8th slice
# "gui" replaces the model in inventories only; "broken" is the elytra at 1 durability.
OPTIONAL_MODELS = ("gui", "broken")
HANDHELD = ("sword", "pickaxe", "axe", "shovel", "hoe", "mace", "trident")
# Worn looks painted with save_layer(). Helmets are 3D models on the head instead.
LAYERS = ("humanoid", "humanoid_leggings", "wings")
REQUIRED_LAYERS = {"boots": ("humanoid",), "elytra": ("wings",), "leggings": ("humanoid_leggings",)}
NAMESPACE = "joshymc"
# Animated frames are re-uploaded to the GPU as they play, so keep them modest.
ANIMATION_SIZES = (16, 32, 64)
MAX_FRAMES = 32

# --------------------------------------------------------------------------------------
# Output (set by check/render/build before calling textures())
# --------------------------------------------------------------------------------------

_out = {"id": None, "textures": None, "layers": None, "painted": set(), "painted_layers": set(),
        "animations": {}}
_NAME = re.compile(r"^[a-z0-9_]+$")


def begin(item_id: str, texture_dir: Path, layer_dir: Path) -> None:
    """Point save()/save_layer() at an output folder. Called by the tools, not by items."""
    _out.update(id=item_id, textures=Path(texture_dir), layers=Path(layer_dir),
                painted=set(), painted_layers=set(), animations={})


def painted() -> set[str]:
    return set(_out["painted"])


def painted_layers() -> set[str]:
    return set(_out["painted_layers"])


def animations() -> dict[str, dict]:
    """name -> the mcmeta "animation" block of every animated texture painted so far."""
    return {name: dict(meta) for name, meta in _out["animations"].items()}


def save(image: Image.Image, name: str) -> None:
    """Save an item texture. Faces refer to it by this name."""
    if not _NAME.match(name):
        raise ValueError(f"texture name {name!r} must be lowercase letters, digits and _")
    folder = _out["textures"]
    if folder is None:
        raise RuntimeError("kit.begin() was not called; run the item through check/render/build")
    folder.mkdir(parents=True, exist_ok=True)
    image.save(folder / f"{name}.png")
    _out["painted"].add(name)
    _out["animations"].pop(name, None)


def save_animation(frames, name: str, frametime: int = 2, interpolate: bool = False, order=None) -> None:
    """Save an animated texture from a list of same-size square frames (16, 32 or 64 px,
    2 to 32 of them). Faces use it by name like any texture (uv 0..16 covers one frame).

    frametime    game ticks per frame (20 ticks = 1 second); 2-4 suits most loops
    interpolate  True blends each frame into the next every tick: silky glows, pulses and
                 colour shifts from few frames. Leave it off for movement (it smears)
    order        optional frame indices to play, e.g. [0, 1, 2, 3, 2, 1], or a frame
                 repeated to hold it
    """
    frames = [f.convert("RGBA") for f in frames]
    if len(frames) < 2:
        raise ValueError("an animation needs at least 2 frames")
    size = frames[0].size
    if any(f.size != size for f in frames) or size[0] != size[1]:
        raise ValueError("animation frames must all be the same square size")
    strip = Image.new("RGBA", (size[0], size[1] * len(frames)), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        strip.paste(f, (0, i * size[1]))
    save(strip, name)
    meta: dict = {"frametime": int(frametime)}
    if interpolate:
        meta["interpolate"] = True
    if order is not None:
        meta["frames"] = [int(i) for i in order]
    _out["animations"][name] = meta


def animate(paint, count: int) -> list[Image.Image]:
    """count frames from paint(t), t = 0, 1/count, 2/count ... (always below 1). A paint
    that is periodic in t (sin(2*pi*t), wave(t), (t + offset) % 1) loops without a seam."""
    return [paint(i / count) for i in range(count)]


def wave(t: float, offset: float = 0.0) -> float:
    """A smooth 0 -> 1 -> 0 over one loop of t (cosine), shifted by offset (0..1)."""
    return 0.5 - 0.5 * math.cos(2 * math.pi * (t + offset))


def shine(image: Image.Image, t: float, colour="#ffffff", width: float = 3.0, strength: float = 0.7,
          angle: float = 35.0, pause: float = 0.45) -> Image.Image:
    """A copy of image with a glossy band sweeping across its opaque pixels. The band
    crosses during the first (1 - pause) of the loop and is gone for the rest, so it
    reads as a periodic glint. angle tilts the band (degrees from vertical)."""
    out = image.convert("RGBA").copy()
    w, h = out.size
    run = 1.0 - pause
    if t >= run:
        return out
    a = math.radians(angle)
    dx, dy = math.cos(a), math.sin(a)
    lo = min(0.0, h * dy)
    hi = w * dx + max(0.0, h * dy)
    centre = lo - width * 2 + (hi - lo + width * 4) * (t / run)
    c = rgba(colour)
    px = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, al = px[x, y]
            if al == 0:
                continue
            d = abs((x + 0.5) * dx + (y + 0.5) * dy - centre)
            k = max(0.0, 1.0 - d / (width / 2 + 0.5)) * strength
            if k > 0:
                px[x, y] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k), al)
    return out


def sparkle(image: Image.Image, x: int, y: int, amount: float, colour="#ffffff", reach: int = 2) -> None:
    """Draw a 4-point twinkle centred on (x, y) in place; amount 0..1 grows it from a dot
    to arms `reach` pixels long (0 draws nothing). Animate amount with wave()."""
    if amount <= 0.02:
        return
    c = rgba(colour)
    px = image.load()
    w, h = image.size
    arm = round(reach * amount)

    def put(px_, py_, k):
        if 0 <= px_ < w and 0 <= py_ < h:
            r, g, b, a = px[px_, py_]
            alpha = min(255, round(c[3] * k))
            if a == 0:
                px[px_, py_] = (c[0], c[1], c[2], alpha)
            else:
                px[px_, py_] = (round(r + (c[0] - r) * k), round(g + (c[1] - g) * k), round(b + (c[2] - b) * k),
                                max(a, alpha))

    put(x, y, min(1.0, 0.5 + amount))
    for i in range(1, arm + 1):
        k = amount * (1 - (i - 1) / (arm + 1))
        for ddx, ddy in ((i, 0), (-i, 0), (0, i), (0, -i)):
            put(x + ddx, y + ddy, k)


def save_layer(image: Image.Image, layer: str) -> None:
    """Save a worn-equipment texture: "humanoid" (helmet, chest, arms, boots),
    "humanoid_leggings" or "wings" (elytra). Use the base layout (64x32) or a
    2x/4x version (128x64, 256x128) for more detail; see HUMANOID and WINGS."""
    if layer not in LAYERS:
        raise ValueError(f"layer must be one of {LAYERS}")
    folder = _out["layers"] / layer
    folder.mkdir(parents=True, exist_ok=True)
    image.save(folder / f"{_out['id']}.png")
    _out["painted_layers"].add(layer)


# --------------------------------------------------------------------------------------
# Colour
# --------------------------------------------------------------------------------------

def rgba(colour) -> tuple[int, int, int, int]:
    """'#rrggbb', '#rrggbbaa' or a tuple, as an RGBA tuple."""
    if isinstance(colour, str):
        c = colour.lstrip("#")
        if len(c) == 6:
            c += "ff"
        return tuple(int(c[i:i + 2], 16) for i in (0, 2, 4, 6))
    if len(colour) == 3:
        return (*colour, 255)
    return tuple(colour)


def hexc(colour) -> str:
    r, g, b, a = rgba(colour)
    return f"#{r:02x}{g:02x}{b:02x}" + ("" if a == 255 else f"{a:02x}")


def mix(a, b, t: float) -> str:
    """Blend colour a toward b by t (clamped to 0..1)."""
    a, b = rgba(a), rgba(b)
    t = max(0.0, min(1.0, float(t)))
    return hexc(tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(4)))


def _hue_toward(h: float, target: float, amount: float) -> float:
    d = ((target - h + 0.5) % 1.0) - 0.5
    return (h + d * amount) % 1.0


def shade(colour, amount: float, hue_shift: float = 0.08) -> str:
    """Lighten (amount > 0) or darken (amount < 0) like a pixel artist: shadows drift
    toward blue-violet and gain saturation, highlights drift toward warm yellow."""
    r, g, b, a = rgba(colour)
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    if amount < 0:
        h = _hue_toward(h, 0.70, -amount * hue_shift * 2)
        s = min(1.0, s * (1 - amount * 0.35))
        v = max(0.0, v * (1 + amount))
    else:
        h = _hue_toward(h, 0.14, amount * hue_shift * 2)
        s = max(0.0, s * (1 - amount * 0.55))
        v = min(1.0, v + (1 - v) * amount * 1.1)
    r, g, b = colorsys.hsv_to_rgb(h, s, v)
    return hexc((round(r * 255), round(g * 255), round(b * 255), a))


def ramp(base, steps: int = 6, contrast: float = 0.75, hue_shift: float = 0.08) -> list[str]:
    """A hue-shifted palette from darkest to lightest, with `base` in the middle."""
    out = []
    for i in range(steps):
        t = (i / (steps - 1) - 0.5) * contrast * 2
        out.append(shade(base, t, hue_shift) if t else hexc(base))
    return out


# --------------------------------------------------------------------------------------
# Texture painting
# --------------------------------------------------------------------------------------

def canvas(w: int = 16, h: int | None = None, fill=(0, 0, 0, 0)) -> Image.Image:
    return Image.new("RGBA", (w, h or w), rgba(fill))


def art(rows: list[str], palette: dict[str, str]) -> Image.Image:
    """Pixel art from strings. '.' and ' ' are transparent; other characters map
    through palette. All rows must be the same width."""
    width = len(rows[0])
    image = canvas(width, len(rows))
    px = image.load()
    for y, row in enumerate(rows):
        if len(row) != width:
            raise ValueError(f"row {y} is {len(row)} wide, expected {width}")
        for x, ch in enumerate(row):
            if ch not in ". ":
                px[x, y] = rgba(palette[ch])
    return image


def fill(image: Image.Image, box, colour) -> None:
    """Fill the inclusive pixel box (x0, y0, x1, y1)."""
    ImageDraw.Draw(image).rectangle(box, fill=rgba(colour))


def gradient(image: Image.Image, box, colours: list, axis: str = "y") -> None:
    """Banded gradient across the inclusive box through the listed colours (no smooth
    blending, so it stays crisp pixel art)."""
    x0, y0, x1, y1 = box
    n = (y1 - y0 + 1) if axis == "y" else (x1 - x0 + 1)
    draw = ImageDraw.Draw(image)
    for i in range(n):
        c = colours[min(len(colours) - 1, int(i * len(colours) / n))]
        if axis == "y":
            draw.line((x0, y0 + i, x1, y0 + i), fill=rgba(c))
        else:
            draw.line((x0 + i, y0, x0 + i, y1), fill=rgba(c))


def bevel(image: Image.Image, box, light, dark, width: int = 1) -> None:
    """Light top/left edge and dark bottom/right edge inside the inclusive box."""
    x0, y0, x1, y1 = box
    draw = ImageDraw.Draw(image)
    for i in range(width):
        draw.line((x0 + i, y0 + i, x1 - i, y0 + i), fill=rgba(light))
        draw.line((x0 + i, y0 + i, x0 + i, y1 - i), fill=rgba(light))
        draw.line((x0 + i, y1 - i, x1 - i, y1 - i), fill=rgba(dark))
        draw.line((x1 - i, y0 + i, x1 - i, y1 - i), fill=rgba(dark))


def speckle(image: Image.Image, box, colours: list, density: float = 0.15, seed: int = 0) -> None:
    """Scatter single pixels of the given colours over the inclusive box."""
    rng = random.Random(seed)
    x0, y0, x1, y1 = box
    px = image.load()
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if rng.random() < density and px[x, y][3]:
                px[x, y] = rgba(rng.choice(colours))


def grain(image: Image.Image, box, colours: list, axis: str = "y", seed: int = 0,
          min_len: int = 2, max_len: int = 6, density: float = 0.5) -> None:
    """Streaks along an axis: wood grain, brushed metal, bark, fur."""
    rng = random.Random(seed)
    x0, y0, x1, y1 = box
    draw = ImageDraw.Draw(image)
    lanes = range(x0, x1 + 1) if axis == "y" else range(y0, y1 + 1)
    for lane in lanes:
        pos = (y0 if axis == "y" else x0) + rng.randrange(0, 3)
        end = y1 if axis == "y" else x1
        while pos <= end:
            length = rng.randint(min_len, max_len)
            if rng.random() < density:
                c = rgba(rng.choice(colours))
                if axis == "y":
                    draw.line((lane, pos, lane, min(end, pos + length - 1)), fill=c)
                else:
                    draw.line((pos, lane, min(end, pos + length - 1), lane), fill=c)
            pos += length + rng.randint(1, 3)


def outline(image: Image.Image, colour, diagonal: bool = False) -> Image.Image:
    """Return a copy with a 1px outline around opaque pixels (for sprites and cutouts)."""
    src = image.load()
    out = image.copy()
    dst = out.load()
    w, h = image.size
    steps = [(-1, 0), (1, 0), (0, -1), (0, 1)] + ([(-1, -1), (1, -1), (-1, 1), (1, 1)] if diagonal else [])
    for y in range(h):
        for x in range(w):
            if src[x, y][3]:
                continue
            if any(0 <= x + dx < w and 0 <= y + dy < h and src[x + dx, y + dy][3] for dx, dy in steps):
                dst[x, y] = rgba(colour)
    return out


def dither(image: Image.Image, box, a, b, ratio: float = 0.5) -> None:
    """Ordered (Bayer 4x4) dither between colours a and b; ratio is b's share."""
    bayer = (0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    x0, y0, x1, y1 = box
    px = image.load()
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = rgba(b if bayer[(y % 4) * 4 + x % 4] < ratio * 16 else a)


# Worn-equipment texture layouts at the base 64x32 size, as (u, v, width, height).
# Scale every number by 2 or 4 for a 128x64 or 256x128 texture.
HUMANOID = {
    # Helmet (head box, 8x8x8) and its outer shell (hat).
    "head_top": (8, 0, 8, 8), "head_bottom": (16, 0, 8, 8), "head_right": (0, 8, 8, 8),
    "head_front": (8, 8, 8, 8), "head_left": (16, 8, 8, 8), "head_back": (24, 8, 8, 8),
    "hat_top": (40, 0, 8, 8), "hat_right": (32, 8, 8, 8), "hat_front": (40, 8, 8, 8),
    "hat_left": (48, 8, 8, 8), "hat_back": (56, 8, 8, 8),
    # Chestplate: body (8x12x4) and both arms (4x12x4, the left arm mirrors the right).
    "body_top": (20, 16, 8, 4), "body_bottom": (28, 16, 8, 4), "body_right": (16, 20, 4, 12),
    "body_front": (20, 20, 8, 12), "body_left": (28, 20, 4, 12), "body_back": (32, 20, 8, 12),
    "arm_top": (44, 16, 4, 4), "arm_bottom": (48, 16, 4, 4), "arm_outer": (40, 20, 4, 12),
    "arm_front": (44, 20, 4, 12), "arm_inner": (48, 20, 4, 12), "arm_back": (52, 20, 4, 12),
    # Boots: the leg box (4x12x4); boots normally cover its lower 4-6 rows plus the sole.
    "leg_top": (4, 16, 4, 4), "leg_sole": (8, 16, 4, 4), "leg_outer": (0, 20, 4, 12),
    "leg_front": (4, 20, 4, 12), "leg_inner": (8, 20, 4, 12), "leg_back": (12, 20, 4, 12),
}
# Elytra wings: one 10x20x2 box per wing (the right wing mirrors the left one).
# "outer" is the surface you see from behind the player; "inner" faces the back.
WINGS = {
    "top": (24, 0, 10, 2), "bottom": (34, 0, 10, 2), "edge_outer": (22, 2, 2, 20),
    "inner": (24, 2, 10, 20), "edge_inner": (34, 2, 2, 20), "outer": (36, 2, 10, 20),
}


def region(layout: dict, name: str, scale: int = 1) -> tuple[int, int, int, int]:
    """Inclusive pixel box of a named region, for fill()/gradient()/bevel()."""
    u, v, w, h = layout[name]
    return (u * scale, v * scale, (u + w) * scale - 1, (v + h) * scale - 1)


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

SIDES = ("north", "south", "east", "west", "up", "down")
LIMIT = (-16.0, 32.0)


def _face_size(side: str, frm, to) -> tuple[float, float]:
    dx, dy, dz = (to[i] - frm[i] for i in range(3))
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
            "up": (dx, dz), "down": (dx, dz)}[side]


def box(frm, to, tex: str, faces: dict | None = None, uv="true", offset=(0, 0),
        glow: int = 0, shade: bool = True, skip=()) -> dict:
    """An element between two corners (model units).

    tex     texture for every face (a name passed to save())
    faces   per-side overrides: {"north": "name"} or {"north": ("name", [u0, v0, u1, v1])}
            or ("name", uv, rotation) with rotation 0/90/180/270
    uv      "true": 1 texel per unit, anchored at `offset` (clipped to the texture);
            "full": stretch the whole texture over each face; or a dict side -> uv
    glow    light_emission 1-15: the part stays bright in the dark
    shade   False turns off directional shading (flat, self-lit look)
    skip    sides to leave out (hidden faces cost nothing to drop)
    """
    frm = [round(float(v), 4) for v in frm]
    to = [round(float(v), 4) for v in to]
    for i in range(3):
        if frm[i] > to[i]:
            frm[i], to[i] = to[i], frm[i]
    element = {"from": frm, "to": to, "faces": {}}
    for side in SIDES:
        if side in skip:
            continue
        name, face_uv, rotation = tex, None, 0
        spec = (faces or {}).get(side)
        if isinstance(spec, str):
            name = spec
        elif isinstance(spec, (tuple, list)):
            name, face_uv = spec[0], list(spec[1])
            rotation = spec[2] if len(spec) > 2 else 0
        if face_uv is None:
            if isinstance(uv, dict):
                face_uv = list(uv.get(side, [0, 0, 16, 16]))
            elif uv == "full":
                face_uv = [0, 0, 16, 16]
            else:
                w, h = _face_size(side, frm, to)
                u0, v0 = offset
                face_uv = [u0, v0, min(16.0, u0 + max(w, 0.01)), min(16.0, v0 + max(h, 0.01))]
        face = {"uv": [round(float(v), 4) for v in face_uv], "texture": "#" + name}
        if rotation:
            face["rotation"] = rotation
        element["faces"][side] = face
    if glow:
        element["light_emission"] = int(glow)
    if not shade:
        element["shade"] = False
    return element


def _as_list(elements) -> list[dict]:
    return [elements] if isinstance(elements, dict) else list(elements)


def _axis_matrix(axis: str, degrees: float):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    if axis == "x":
        return ((1, 0, 0), (0, c, -s), (0, s, c))
    if axis == "y":
        return ((c, 0, s), (0, 1, 0), (-s, 0, c))
    return ((c, -s, 0), (s, c, 0), (0, 0, 1))


def _matmul(a, b):
    return tuple(tuple(sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)) for i in range(3))


def _apply(m, v):
    return tuple(sum(m[i][k] * v[k] for k in range(3)) for i in range(3))


def euler_matrix(x: float = 0, y: float = 0, z: float = 0):
    """Minecraft's element rotation: X first, then Y, then Z (Rz * Ry * Rx)."""
    return _matmul(_axis_matrix("z", z), _matmul(_axis_matrix("y", y), _axis_matrix("x", x)))


def matrix_to_euler(m) -> tuple[float, float, float]:
    """Inverse of euler_matrix()."""
    sy = -m[2][0]
    sy = max(-1.0, min(1.0, sy))
    y = math.asin(sy)
    if abs(sy) < 0.99999:
        x = math.atan2(m[2][1], m[2][2])
        z = math.atan2(m[1][0], m[0][0])
    else:
        x = math.atan2(-m[1][2], m[1][1])
        z = 0.0
    return tuple(round(math.degrees(a), 4) for a in (x, y, z))


def rotation_of(element: dict):
    """(matrix, origin) of an element's rotation; identity if it has none."""
    r = element.get("rotation")
    if not r:
        return ((1, 0, 0), (0, 1, 0), (0, 0, 1)), None
    if "axis" in r:
        return _axis_matrix(r["axis"], r["angle"]), tuple(r["origin"])
    return euler_matrix(r.get("x", 0), r.get("y", 0), r.get("z", 0)), tuple(r["origin"])


def _set_rotation(element: dict, m, origin) -> None:
    x, y, z = matrix_to_euler(m)
    origin = [round(float(v), 4) for v in origin]
    nonzero = [(a, v) for a, v in (("x", x), ("y", y), ("z", z)) if abs(v) > 1e-4]
    if not nonzero:
        element.pop("rotation", None)
    elif len(nonzero) == 1:
        element["rotation"] = {"origin": origin, "axis": nonzero[0][0], "angle": nonzero[0][1]}
    else:
        element["rotation"] = {"origin": origin, "x": x, "y": y, "z": z}


def turn(elements, angle: float = 0.0, axis: str = "y", origin=(8, 8, 8),
         x: float | None = None, y: float | None = None, z: float | None = None):
    """Rotate one element or a list about `origin`, on top of any rotation they already
    have, so whole assemblies can be tilted. Either angle+axis, or Euler x/y/z degrees
    (applied X, then Y, then Z). Returns the same elements (modified in place)."""
    g = euler_matrix(x or 0, y or 0, z or 0) if (x is not None or y is not None or z is not None) \
        else _axis_matrix(axis, angle)
    origin = tuple(float(v) for v in origin)
    for element in _as_list(elements):
        m, o = rotation_of(element)
        if o is None:
            o = origin
        rel = tuple(o[i] - origin[i] for i in range(3))
        moved = _apply(g, rel)
        new_origin = tuple(moved[i] + origin[i] for i in range(3))
        d = tuple(new_origin[i] - o[i] for i in range(3))
        element["from"] = [round(element["from"][i] + d[i], 4) for i in range(3)]
        element["to"] = [round(element["to"][i] + d[i], 4) for i in range(3)]
        _set_rotation(element, _matmul(g, m), new_origin)
    return elements


def move(elements, dx: float = 0, dy: float = 0, dz: float = 0):
    """Translate one element or a list (their rotation origins move with them)."""
    for element in _as_list(elements):
        for key in ("from", "to"):
            element[key] = [round(element[key][0] + dx, 4), round(element[key][1] + dy, 4),
                            round(element[key][2] + dz, 4)]
        if "rotation" in element:
            o = element["rotation"]["origin"]
            element["rotation"]["origin"] = [round(o[0] + dx, 4), round(o[1] + dy, 4), round(o[2] + dz, 4)]
    return elements


def copy(elements):
    return _copy.deepcopy(elements)


_MIRROR_SIDE = {"x": {"east": "west", "west": "east"}, "y": {"up": "down", "down": "up"},
                "z": {"north": "south", "south": "north"}}


def mirror(elements, axis: str = "x", about: float = 8.0) -> list[dict]:
    """Mirrored copies across the plane axis = about (for symmetric designs). Textures
    are mirrored too, so painted details stay symmetric."""
    i = "xyz".index(axis)
    out = []
    for element in copy(_as_list(elements)):
        a, b = element["from"][i], element["to"][i]
        element["from"][i], element["to"][i] = round(2 * about - b, 4), round(2 * about - a, 4)
        swap = _MIRROR_SIDE[axis]
        element["faces"] = {swap.get(side, side): face for side, face in element["faces"].items()}
        for side, face in element["faces"].items():
            u0, v0, u1, v1 = face["uv"]
            flip_v = axis == "y" or (axis == "z" and side in ("up", "down"))
            face["uv"] = [u0, v1, u1, v0] if flip_v else [u1, v0, u0, v1]
        if "rotation" in element:
            m, o = rotation_of(element)
            flip = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
            flip[i][i] = -1
            m = _matmul(flip, _matmul(m, flip))
            o = list(o)
            o[i] = 2 * about - o[i]
            _set_rotation(element, m, o)
        out.append(element)
    return out


def bar(p0, p1, width: float, depth: float, tex: str, roll: float = 0.0, **box_kwargs) -> dict:
    """A box running from point p0 to point p1 with a width x depth cross-section.
    Great for limbs, prongs, branches, curved blades (see arc()). roll spins it
    around its own length. Extra keyword arguments go to box()."""
    p0, p1 = [float(v) for v in p0], [float(v) for v in p1]
    d = [p1[i] - p0[i] for i in range(3)]
    length = math.sqrt(sum(v * v for v in d))
    if length < 1e-6:
        raise ValueError("bar() needs two different points")
    mid = [(p0[i] + p1[i]) / 2 for i in range(3)]
    element = box((mid[0] - width / 2, mid[1] - length / 2, mid[2] - depth / 2),
                  (mid[0] + width / 2, mid[1] + length / 2, mid[2] + depth / 2), tex, **box_kwargs)
    u = [v / length for v in d]
    # Rotation that takes +Y onto u (Rodrigues), after rolling about Y.
    ax = (u[2], 0.0, -u[0])  # (0,1,0) x u
    s = math.sqrt(ax[0] ** 2 + ax[2] ** 2)
    c = u[1]
    if s < 1e-9:
        align = ((1, 0, 0), (0, 1, 0), (0, 0, 1)) if c > 0 else ((1, 0, 0), (0, -1, 0), (0, 0, -1))
    else:
        kx, kz = ax[0] / s, ax[2] / s
        skew = ((0, -kz, 0), (kz, 0, -kx), (0, kx, 0))
        k = (kx, 0.0, kz)
        kk = tuple(tuple(k[i] * k[j] for j in range(3)) for i in range(3))
        align = tuple(tuple((c if i == j else 0) + s * skew[i][j] + (1 - c) * kk[i][j]
                            for j in range(3)) for i in range(3))
    m = _matmul(align, _axis_matrix("y", roll)) if roll else align
    _set_rotation(element, m, mid)
    return element


def arc(center, radius: float, start: float, end: float, segments: int, width: float,
        depth: float, tex: str, plane: str = "xy", **box_kwargs) -> list[dict]:
    """A curve of bars around `center` from angle `start` to `end` (degrees, 0 = +first
    axis of the plane, counter-clockwise). Scythe blades, bow limbs, crescents, rings."""
    a_i, b_i = {"xy": (0, 1), "xz": (0, 2), "zy": (2, 1)}[plane]
    parts = []
    for k in range(segments):
        t0 = math.radians(start + (end - start) * k / segments)
        t1 = math.radians(start + (end - start) * (k + 1) / segments)
        p0, p1 = list(center), list(center)
        p0[a_i] += radius * math.cos(t0)
        p0[b_i] += radius * math.sin(t0)
        p1[a_i] += radius * math.cos(t1)
        p1[b_i] += radius * math.sin(t1)
        # Stretch each segment a little so neighbours overlap instead of leaving gaps.
        mid = [(p0[i] + p1[i]) / 2 for i in range(3)]
        p0 = [mid[i] + (p0[i] - mid[i]) * 1.08 for i in range(3)]
        p1 = [mid[i] + (p1[i] - mid[i]) * 1.08 for i in range(3)]
        parts.append(bar(p0, p1, width, depth, tex, **box_kwargs))
    return parts


def prism(center, radius: float, length: float, tex: str, axis: str = "y", sides: int = 8,
          cap: str | None = None, **box_kwargs) -> list[dict]:
    """A round-ish prism (8 or 16 sides) made of rotated slabs, centred on `center`,
    `length` long along `axis`. `cap` textures the two ends."""
    if sides not in (8, 16):
        raise ValueError("sides must be 8 or 16")
    cx, cy, cz = center
    half = radius * math.tan(math.pi / sides)
    angles = (0, 45) if sides == 8 else (0, 22.5, 45, -22.5)
    faces = {"up": cap, "down": cap} if cap else None
    slabs = []
    for a in angles:
        s1 = box((cx - radius, cy - length / 2, cz - half), (cx + radius, cy + length / 2, cz + half),
                 tex, faces=faces, skip=("north", "south"), **box_kwargs)
        s2 = box((cx - half, cy - length / 2, cz - radius), (cx + half, cy + length / 2, cz + radius),
                 tex, faces=faces, skip=("east", "west"), **box_kwargs)
        if a:
            turn([s1, s2], a, "y", (cx, cy, cz))
        slabs += [s1, s2]
    if axis == "x":
        turn(slabs, 90, "z", (cx, cy, cz))
    elif axis == "z":
        turn(slabs, 90, "x", (cx, cy, cz))
    return slabs


def lathe(center, profile, tex: str, axis: str = "y", sides: int = 8, cap: str | None = None,
          **box_kwargs) -> list[dict]:
    """A round solid turned on a lathe: eggs, coins, orbs, bottles, knobs, bells. profile is
    [(height, radius), ...] from one end to the other, heights measured along `axis` from
    `center`; each step becomes a prism() slice with the average radius of its two ends
    (slices of radius <= 0 are skipped). Extra keyword arguments go to prism()/box()."""
    parts = []
    cx, cy, cz = center
    i = "xyz".index(axis)
    for (h0, r0), (h1, r1) in zip(profile, profile[1:]):
        radius = (r0 + r1) / 2
        length = abs(h1 - h0)
        if radius <= 0 or length <= 1e-6:
            continue
        c = [cx, cy, cz]
        c[i] += (h0 + h1) / 2
        parts += prism(tuple(c), radius, length, tex, axis=axis, sides=sides, cap=cap, **box_kwargs)
    return parts


def corners(element: dict) -> list[tuple[float, float, float]]:
    """The 8 corners of an element after its rotation, in model units."""
    (x0, y0, z0), (x1, y1, z1) = element["from"], element["to"]
    pts = [(x, y, z) for x in (x0, x1) for y in (y0, y1) for z in (z0, z1)]
    m, o = rotation_of(element)
    if o is None:
        return pts
    return [tuple(v + o[i] for i, v in enumerate(_apply(m, (p[0] - o[0], p[1] - o[1], p[2] - o[2]))))
            for p in pts]


def bounds(elements) -> tuple[tuple[float, float, float], tuple[float, float, float]]:
    pts = [p for e in _as_list(elements) for p in corners(e)]
    return (tuple(min(p[i] for p in pts) for i in range(3)), tuple(max(p[i] for p in pts) for i in range(3)))


# --------------------------------------------------------------------------------------
# Display transforms
# --------------------------------------------------------------------------------------
# Vanilla 26.2 values (item/handheld, item/handheld_mace, item/bow, item/crossbow,
# item/generated). Rotation is applied X, then Y, then Z, about the model centre.

VANILLA = {
    "handheld": {
        "thirdperson_righthand": ([0, -90, 55], [0, 4.0, 0.5], 0.85),
        "firstperson_righthand": ([0, -90, 25], [1.13, 3.2, 1.13], 0.68),
    },
    "handheld_mace": {
        "thirdperson_righthand": ([0, -90, 55], [0, 4.0, 1], 1.0),
        "firstperson_righthand": ([0, -90, 25], [0, 3, 0.8], 0.9),
    },
    "bow": {
        "thirdperson_righthand": ([-80, 260, -40], [-1, -2, 2.5], 0.9),
        "firstperson_righthand": ([0, -90, 25], [1.13, 3.2, 1.13], 0.68),
    },
    "crossbow": {
        "thirdperson_righthand": ([-90, 0, -60], [2, 0.1, -3], 0.9),
        "firstperson_righthand": ([-90, 0, -55], [1.13, 3.2, 1.13], 0.68),
    },
    "generated": {
        "thirdperson_righthand": ([0, 0, 0], [0, 3, 1], 0.55),
        "firstperson_righthand": ([0, -90, 25], [1.13, 3.2, 1.13], 0.68),
    },
}
# Where the hand closes on a vanilla 16x16 tool sprite (model units).
VANILLA_GRIP = (3.5, 3.5, 8.0)


def display_matrix(rotation) -> tuple:
    """The display rotation as a matrix: Minecraft uses rotationXYZ = Rx * Ry * Rz."""
    rx, ry, rz = rotation
    return _matmul(_axis_matrix("x", rx), _matmul(_axis_matrix("y", ry), _axis_matrix("z", rz)))


def display_euler(m) -> list[float]:
    """Inverse of display_matrix()."""
    sb = max(-1.0, min(1.0, m[0][2]))
    b = math.asin(sb)
    if abs(sb) < 0.99999:
        a = math.atan2(-m[1][2], m[2][2])
        c = math.atan2(-m[0][1], m[0][0])
    else:
        a = math.atan2(m[2][1], m[1][1])
        c = 0.0
    return [round(math.degrees(v), 3) for v in (a, b, c)]


def _transform_point(rotation, translation, scale, p):
    """Where model point p (units) lands, in blocks, for a display transform."""
    m = display_matrix(rotation)
    q = _apply(m, tuple((p[i] / 16 - 0.5) * scale for i in range(3)))
    return tuple(q[i] + translation[i] / 16 for i in range(3))


def _grip_transform(vanilla_key: str, context: str, grip, size: float, extra) -> dict:
    rotation, translation, scale = VANILLA[vanilla_key][context]
    anchor = _transform_point(rotation, translation, scale, VANILLA_GRIP)
    m = _matmul(display_matrix(rotation), extra)
    new_scale = scale * size
    q = _apply(m, tuple((grip[i] / 16 - 0.5) * new_scale for i in range(3)))
    t = [round((anchor[i] - q[i]) * 16, 3) for i in range(3)]
    return {"rotation": display_euler(m), "translation": t, "scale": [round(new_scale, 4)] * 3}


def fit(elements, rotation=(0, 0, 0), span: float = 15.0, lift: float = 0.0) -> dict:
    """A display transform that fits the model into a `span`-unit square seen from the
    front after `rotation` (the GUI slot is 16 units). lift raises it in units."""
    m = display_matrix(rotation)
    pts = [_apply(m, (p[0] / 16 - 0.5, p[1] / 16 - 0.5, p[2] / 16 - 0.5))
           for e in _as_list(elements) for p in corners(e)]
    lo = [min(p[i] for p in pts) for i in range(3)]
    hi = [max(p[i] for p in pts) for i in range(3)]
    size = max(hi[0] - lo[0], hi[1] - lo[1], 1e-6)
    s = min(4.0, (span / 16) / size)
    centre = [(lo[i] + hi[i]) / 2 for i in range(3)]
    t = [round(-centre[0] * s * 16, 3), round(-centre[1] * s * 16 + lift, 3), round(-centre[2] * s * 16, 3)]
    return {"rotation": [float(v) for v in rotation], "translation": t, "scale": [round(s, 4)] * 3}


# --- Placing an item on the player ----------------------------------------------------
# The player stands at the origin facing +Z (their right hand is on the -X side), feet
# at y = 0. These matrices copy Minecraft's LivingEntityRenderer, HumanoidModel and
# ItemInHandLayer, so a transform solved here lands exactly there in game.

ARM_POSES = {  # pose -> (right arm, left arm) as (xRot, yRot, zRot) degrees, from HumanoidModel
    "item": ((-18.0, 0.0, 0.0), (0.0, 0.0, 0.0)),            # holding anything
    "raised": ((-180.0, 0.0, 0.0), (0.0, 0.0, 0.0)),         # trident charging a throw
    "block": ((-54.0, -30.0, 0.0), (0.0, 0.0, 0.0)),         # shield up
    "bow": ((-90.0, -5.7, 0.0), (-90.0, 28.6, 0.0)),         # drawing a bow
    "crossbow": ((-55.6, -45.8, 0.0), (-55.6, 35.0, 0.0)),   # loading a crossbow
    "aim": ((-84.3, -17.2, 0.0), (-86.0, 34.4, 0.0)),        # holding a loaded crossbow
}
# Which pose each model state is seen in.
STATE_POSE = {"pull_0": "bow", "pull_1": "bow", "pull_2": "bow", "throwing": "raised", "blocking": "block"}
CROSSBOW_POSE = {"pull_0": "crossbow", "pull_1": "crossbow", "pull_2": "crossbow", "arrow": "aim", "firework": "aim"}


def pose_for(kind: str, state: str) -> str:
    if kind == "crossbow":
        return CROSSBOW_POSE.get(state, "item")
    return STATE_POSE.get(state, "item")


def _np_axis(axis, degrees):
    import numpy as np
    m = np.eye(4)
    m[:3, :3] = np.array(_axis_matrix(axis, degrees))
    return m


def _np_t(x, y, z):
    import numpy as np
    m = np.eye(4)
    m[:3, 3] = (x, y, z)
    return m


def hand_frame(pose: str = "item"):
    """4x4 matrix from the right hand's item space (block units) to world space."""
    import numpy as np
    xr, yr, zr = ARM_POSES[pose][0]
    entity = _np_axis("y", 180) @ np.diag([-1.0, -1.0, 1.0, 1.0]) @ np.diag([0.9375, 0.9375, 0.9375, 1.0]) \
        @ _np_t(0, -1.501, 0)
    arm = _np_t(-5 / 16, 2 / 16, 0) @ _np_axis("z", zr) @ _np_axis("y", yr) @ _np_axis("x", xr)
    return entity @ arm @ _np_axis("x", -90) @ _np_axis("y", 180) @ _np_t(1 / 16, 2 / 16, -10 / 16)


FIST = (0.0, -2 / 16, 2 / 16)  # centre of the closed hand, in the hand's item space
FIRST_PERSON_HAND = (0.56, -0.52, -0.72)  # ItemInHandRenderer.applyItemArmTransform (camera space)


def place(model_axes: dict, grip, target, scale: float = 1.0, pose: str | None = "item") -> dict:
    """Solve a display transform from where the item should end up.

    model_axes  where two model axes should point, e.g. {"y": (0, 1, 0.2), "z": (-1, 0, 0)}
                (world directions for third person, camera directions for first person:
                camera x = right, y = up, -z = into the screen)
    grip        the model point (units) to pin
    target      where it goes: third person, a world point or "fist"; first person
                (pose=None), a camera-space point such as (0.5, -0.45, -0.8)
    """
    import numpy as np
    names = list(model_axes)
    if len(names) != 2:
        raise ValueError("give exactly two model axes")
    a, b = (np.asarray(model_axes[n], dtype=float) for n in names)
    a /= np.linalg.norm(a)
    b -= a * (a @ b)
    b /= np.linalg.norm(b)
    idx = {"x": 0, "y": 1, "z": 2}
    i, j = idx[names[0]], idx[names[1]]
    k = 3 - i - j
    cols = [None, None, None]
    cols[i], cols[j] = a, b
    # Right-handed: e_k = e_i x e_j when (i, j, k) is cyclic.
    cols[k] = np.cross(a, b) if (j - i) % 3 == 1 else np.cross(b, a)
    world = np.stack(cols, axis=1)
    if pose is None:
        frame = np.eye(3)
        origin = np.asarray(FIRST_PERSON_HAND)
        spin, s_frame = frame, 1.0
        goal = np.asarray(target, dtype=float)
    else:
        m = hand_frame(pose)
        spin = m[:3, :3] / 0.9375
        origin = m[:3, 3]
        s_frame = 0.9375
        goal = m[:3, :3] @ np.asarray(FIST) + origin if target == "fist" else np.asarray(target, dtype=float)
    r = spin.T @ world
    g = np.asarray(grip, dtype=float) / 16 - 0.5
    local_goal = spin.T @ (goal - origin) / s_frame
    t = local_goal - r @ (g * scale)
    return {"rotation": display_euler(tuple(map(tuple, r))), "translation": [round(float(v) * 16, 3) for v in t],
            "scale": [round(scale, 4)] * 3}


def display(kind: str, elements, grip=None, size: float = 1.0, gui_rotation=None,
            gui_span: float = 15.0) -> dict:
    """Display transforms for a model of this KIND (see the frames in the module doc).
    Start from these, look at the render, then tweak the dict or use place().

    grip          the model point the hand closes on, e.g. (8, 4, 8). Required for
                  handheld kinds; other kinds default to the model's centre
    size          in-hand size multiplier (1.0 = vanilla pixel scale; big models are
                  already big, so try 0.8-1.2)
    gui_rotation  inventory icon rotation; defaults suit each kind
    gui_span      how many of the slot's 16 units the icon may fill
    """
    lo, hi = bounds(elements)
    centre = tuple((lo[i] + hi[i]) / 2 for i in range(3))
    out: dict = {}
    if kind in ("sword", "pickaxe", "axe", "shovel", "hoe", "mace"):
        if grip is None:
            raise ValueError("handheld kinds need grip=(x, y, z)")
        key = "handheld_mace" if kind == "mace" else "handheld"
        upright_to_diagonal = _axis_matrix("z", -45)
        for context in ("thirdperson_righthand", "firstperson_righthand"):
            out[context] = _grip_transform(key, context, grip, size, upright_to_diagonal)
        out["gui"] = fit(elements, gui_rotation or (0, 0, -45), gui_span)
        out["ground"] = fit(elements, (0, 0, -45), 9.0, lift=2.0)
        out["fixed"] = fit(elements, (0, 180, -45), 16.0)
        out["on_shelf"] = fit(elements, (0, 0, -45), 12.0)
    elif kind == "trident":
        if grip is None:
            raise ValueError("trident needs grip=(x, y, z)")
        # Held upright like a staff (prongs up, leaning forward, fan facing outward);
        # "throwing" raises it over the shoulder, pointing ahead.
        out["thirdperson_righthand"] = place({"y": (0, 1, 0.18), "z": (-1, 0, 0)}, grip, "fist", 1.0 * size)
        out["firstperson_righthand"] = place({"y": (-0.35, 0.94, -0.1), "z": (0, 0, 1)}, grip,
                                             (0.42, -0.5, -0.8), 0.68 * size, pose=None)
        out["gui"] = fit(elements, gui_rotation or (0, 0, -45), gui_span)
        out["ground"] = fit(elements, (0, 0, -45), 9.0, lift=2.0)
        out["fixed"] = fit(elements, (0, 180, -45), 16.0)
        out["on_shelf"] = fit(elements, (0, 0, -45), 12.0)
    elif kind in ("bow", "crossbow"):
        for context, (rotation, translation, scale) in VANILLA[kind].items():
            out[context] = {"rotation": list(rotation), "translation": list(translation),
                            "scale": [round(scale * size, 4)] * 3}
        out["gui"] = fit(elements, gui_rotation or (0, 0, 0), gui_span)
        out["ground"] = fit(elements, (0, 0, 0), 9.0, lift=2.0)
        out["fixed"] = fit(elements, (0, 180, 0), 16.0)
        out["on_shelf"] = fit(elements, (0, 0, 0), 12.0)
    elif kind == "shield":
        back = grip or (centre[0], centre[1], hi[2])
        # On the outside of the forearm, front facing outward.
        fist = hand_frame("item")
        import numpy as np
        goal = fist[:3, :3] @ np.asarray(FIST) + fist[:3, 3] + np.array([-0.13, 0.2, -0.02])
        out["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (1, 0, 0)}, back, tuple(goal), 0.9 * size)
        out["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (-0.35, 0, -1)}, centre,
                                             (0.62, -0.5, -1.0), 0.8 * size, pose=None)
        out["gui"] = fit(elements, gui_rotation or (15, 155, -5), gui_span)
        out["ground"] = fit(elements, (0, 0, 0), 9.0, lift=2.0)
        out["fixed"] = fit(elements, (0, 180, 0), 16.0)
        out["on_shelf"] = fit(elements, (0, 180, 0), 12.0)
    elif kind == "helmet":
        out["head"] = {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}
        out["gui"] = fit(elements, gui_rotation or (25, 150, 0), gui_span)
        out["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, grip or centre, "fist", 0.45 * size)
        out["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (0.4, 0, -1)}, grip or centre,
                                             (0.5, -0.45, -0.85), 0.5 * size, pose=None)
        out["ground"] = fit(elements, (0, 0, 0), 8.0, lift=2.0)
        out["fixed"] = fit(elements, (0, 180, 0), 14.0)
        out["on_shelf"] = fit(elements, (0, 180, 0), 12.0)
    else:  # boots, elytra, food, item, leggings
        out["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, grip or centre, "fist", 0.45 * size)
        out["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (0.35, 0, 1)}, grip or centre,
                                             (0.5, -0.42, -0.85), 0.55 * size, pose=None)
        out["gui"] = fit(elements, gui_rotation or (30, 225, 0), gui_span)
        out["ground"] = fit(elements, (0, 0, 0), 8.0, lift=2.0)
        out["fixed"] = fit(elements, (0, 180, 0), 14.0)
        out["on_shelf"] = fit(elements, (0, 180, 0), 12.0)
    return out


def display_for_state(kind: str, state: str, elements, grip=None, size: float = 1.0) -> dict:
    """Display transforms for the extra states: trident "throwing" and shield "blocking".
    Other states (bow pulls, crossbow loads) reuse display()."""
    lo, hi = bounds(elements)
    centre = tuple((lo[i] + hi[i]) / 2 for i in range(3))
    out = display(kind, elements, grip=grip, size=size)
    if kind == "trident" and state == "throwing":
        out["thirdperson_righthand"] = place({"y": (0, 0.3, 1), "z": (-1, 0, 0)}, grip, "fist", 1.0 * size,
                                             pose="raised")
        out["firstperson_righthand"] = place({"y": (-0.12, 0.3, -1), "z": (-1, 0, 0)}, grip,
                                             (0.36, -0.28, -0.62), 0.68 * size, pose=None)
    elif kind == "shield" and state == "blocking":
        back = grip or (centre[0], centre[1], hi[2])
        out["thirdperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, -1)}, back, (-0.16, 1.18, 0.46),
                                             0.9 * size, pose="block")
        out["firstperson_righthand"] = place({"y": (0, 1, 0), "z": (0, 0, 1)}, centre, (0.3, -0.34, -0.8),
                                             0.8 * size, pose=None)
    return out


# --------------------------------------------------------------------------------------
# Models and item definitions
# --------------------------------------------------------------------------------------

def model(elements, display_: dict) -> dict:
    """A 3D model: its elements plus display transforms (usually from display())."""
    return {"elements": _as_list(elements), "display": display_}


def sprite(texture: str, display_: dict | None = None, handheld: bool = False) -> dict:
    """A flat 2D model from one texture, like vanilla items (e.g. a painted GUI icon)."""
    out = {"parent": "minecraft:item/handheld" if handheld else "minecraft:item/generated",
           "textures": {"layer0": "#" + texture}}
    if display_:
        out["display"] = display_
    return out


def item_definition(item_id: str, kind: str, names, oversized_gui: bool = False) -> dict:
    """The assets/joshymc/items/<id>.json tree for this kind."""
    def ref(name):
        return {"type": "minecraft:model", "model": f"{NAMESPACE}:item/{item_id}/{name}"}

    if kind == "bow":
        tree = {"type": "minecraft:condition", "property": "minecraft:using_item",
                "on_false": ref("idle"),
                "on_true": {"type": "minecraft:range_dispatch", "property": "minecraft:use_duration",
                            "scale": 0.05, "fallback": ref("pull_0"),
                            "entries": [{"threshold": 0.65, "model": ref("pull_1")},
                                        {"threshold": 0.9, "model": ref("pull_2")}]}}
    elif kind == "crossbow":
        tree = {"type": "minecraft:select", "property": "minecraft:charge_type",
                "cases": [{"when": "arrow", "model": ref("arrow")},
                          {"when": "rocket", "model": ref("firework")}],
                "fallback": {"type": "minecraft:condition", "property": "minecraft:using_item",
                             "on_false": ref("idle"),
                             "on_true": {"type": "minecraft:range_dispatch", "property": "minecraft:crossbow/pull",
                                         "fallback": ref("pull_0"),
                                         "entries": [{"threshold": 0.58, "model": ref("pull_1")},
                                                     {"threshold": 1.0, "model": ref("pull_2")}]}}}
    elif kind == "trident":
        tree = {"type": "minecraft:condition", "property": "minecraft:using_item",
                "on_false": ref("main"), "on_true": ref("throwing")}
    elif kind == "shield":
        tree = {"type": "minecraft:condition", "property": "minecraft:using_item",
                "on_false": ref("main"), "on_true": ref("blocking")}
    elif kind == "cake":
        tree = {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
                "cases": [{"when": f"bite_{i}", "model": ref(f"bite_{i}")} for i in range(1, CAKE_SLICES)],
                "fallback": ref("main")}
    elif kind == "elytra" and "broken" in names:
        tree = {"type": "minecraft:condition", "property": "minecraft:broken",
                "on_false": ref("main"), "on_true": ref("broken")}
    else:
        tree = ref("main")
    if "gui" in names:
        tree = {"type": "minecraft:select", "property": "minecraft:display_context",
                "cases": [{"when": ["gui"], "model": ref("gui")}], "fallback": tree}
    out = {"model": tree}
    if oversized_gui:
        out["oversized_in_gui"] = True
    return out


def resolve(model_: dict, item_id: str) -> dict:
    """Swap '#name' texture references for full resource locations (used by build)."""
    out = _copy.deepcopy(model_)
    refs = sorted({face["texture"][1:] for e in out.get("elements", []) for face in e["faces"].values()})
    textures = {name: f"{NAMESPACE}:item/{item_id}/{name}" for name in refs}
    for key, value in list(out.get("textures", {}).items()):
        textures[key] = f"{NAMESPACE}:item/{item_id}/{value[1:]}" if value.startswith("#") else value
    if refs:
        textures.setdefault("particle", textures[refs[0]])
    out["textures"] = textures
    return out
