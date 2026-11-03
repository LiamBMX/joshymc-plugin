"""Shared builder for the social GUI icons (issue #1355): discord_icon and youtube_icon.

Both are "app badges": a thick rounded plate in the brand colour with the white logo raised
on its face, read straight on in inventories. Every flat shape (plate, rim, logo) is painted
as a high-resolution mask and extruded as horizontal strips (one box per run of a 0.25-unit
row, identical rows merged), so curves stay smooth, holes (Discord's eyes) are real holes
that show the plate behind them, and the side walls follow the outline. Front faces sample
one shared 32x32 texture at their own position, so a glint sweeping over the texture
crosses the whole badge in one pass.

The face of the badge points +Z (south), the side Minecraft shows in inventories.
Modules starting with _ never ship.
"""
from __future__ import annotations

import math

from PIL import Image, ImageDraw

from art.kit import box, fit, place

# Mask resolution: pixels per model unit, and the strip height in units.
RES = 32
STEP = 0.25


# --------------------------------------------------------------------------------------
# Masks
# --------------------------------------------------------------------------------------

def blank() -> Image.Image:
    """A 16x16-unit mask (y down, like textures) at RES pixels per unit."""
    return Image.new("L", (16 * RES, 16 * RES), 0)


def rounded_rect(mask: Image.Image, x0: float, y0: float, x1: float, y1: float, r: float, value=255):
    """A rounded rectangle in model units (y measured down from the top, like textures)."""
    ImageDraw.Draw(mask).rounded_rectangle((x0 * RES, y0 * RES, x1 * RES - 1, y1 * RES - 1),
                                           radius=r * RES, fill=value)


def squircle(mask: Image.Image, x0: float, y0: float, x1: float, y1: float, n: float = 4.5, value=255):
    """A superellipse (|x|^n + |y|^n <= 1), the soft "app icon" square."""
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    ax, ay = (x1 - x0) / 2, (y1 - y0) / 2
    pts = []
    for i in range(720):
        a = 2 * math.pi * i / 720
        c, s = math.cos(a), math.sin(a)
        x = cx + ax * math.copysign(abs(c) ** (2 / n), c)
        y = cy + ay * math.copysign(abs(s) ** (2 / n), s)
        pts.append((x * RES, y * RES))
    ImageDraw.Draw(mask).polygon(pts, fill=value)


def polygon(mask: Image.Image, pts, value=255):
    ImageDraw.Draw(mask).polygon([(x * RES, y * RES) for x, y in pts], fill=value)


def ellipse(mask: Image.Image, cx: float, cy: float, rx: float, ry: float, value=255):
    ImageDraw.Draw(mask).ellipse(((cx - rx) * RES, (cy - ry) * RES, (cx + rx) * RES, (cy + ry) * RES), fill=value)


def inset(mask: Image.Image, units: float) -> Image.Image:
    """The mask shrunk by `units` on every side (round erosion)."""
    from PIL import ImageFilter
    px = max(1, round(units * RES))
    size = 2 * px + 1
    return mask.filter(ImageFilter.MinFilter(size if size % 2 else size + 1))


# --- a tiny SVG path reader (M L H V C A Z, absolute and relative) ---------------------

def _arc_points(p0, r, large, sweep, p1, segments=24):
    """Points of an SVG circular arc from p0 to p1 (y down), excluding p0."""
    (x1, y1), (x2, y2) = p0, p1
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    d2 = dx * dx + dy * dy
    if d2 == 0:
        return []
    r = max(r, math.sqrt(d2))
    k = math.sqrt(max(0.0, (r * r - d2) / d2))
    if large == sweep:
        k = -k
    cxp, cyp = k * dy, -k * dx
    cx, cy = cxp + (x1 + x2) / 2, cyp + (y1 + y2) / 2
    a1 = math.atan2((y1 - cy) / r, (x1 - cx) / r)
    a2 = math.atan2((y2 - cy) / r, (x2 - cx) / r)
    da = a2 - a1
    if sweep and da < 0:
        da += 2 * math.pi
    elif not sweep and da > 0:
        da -= 2 * math.pi
    return [(cx + r * math.cos(a1 + da * i / segments), cy + r * math.sin(a1 + da * i / segments))
            for i in range(1, segments + 1)]


def _cubic(p0, c1, c2, p1, segments=24):
    out = []
    for i in range(1, segments + 1):
        t = i / segments
        u = 1 - t
        out.append((u ** 3 * p0[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t ** 3 * p1[0],
                    u ** 3 * p0[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t ** 3 * p1[1]))
    return out


def svg_path(d: str) -> list[list[tuple[float, float]]]:
    """Subpaths of an SVG path as point lists (enough of the grammar for logo marks)."""
    import re
    tokens = re.findall(r"[MmLlHhVvCcAaZz]|-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?", d)
    i, cmd = 0, None
    cur, start = (0.0, 0.0), (0.0, 0.0)
    paths: list[list[tuple[float, float]]] = []
    pts: list[tuple[float, float]] = []

    def num():
        nonlocal i
        v = float(tokens[i])
        i += 1
        return v

    while i < len(tokens):
        if tokens[i].isalpha():
            cmd = tokens[i]
            i += 1
            if cmd in "Zz":
                if pts:
                    paths.append(pts)
                pts, cur = [], start
                continue
        rel = cmd.islower()
        c = cmd.upper()
        ox, oy = cur if rel else (0.0, 0.0)
        if c == "M":
            cur = (ox + num(), oy + num())
            start = cur
            if pts:
                paths.append(pts)
            pts = [cur]
            cmd = "l" if rel else "L"
        elif c == "L":
            cur = (ox + num(), oy + num())
            pts.append(cur)
        elif c == "H":
            cur = ((ox if rel else 0) + num(), cur[1])
            pts.append(cur)
        elif c == "V":
            cur = (cur[0], (oy if rel else 0) + num())
            pts.append(cur)
        elif c == "C":
            c1 = (ox + num(), oy + num())
            c2 = (ox + num(), oy + num())
            p1 = (ox + num(), oy + num())
            pts += _cubic(cur, c1, c2, p1)
            cur = p1
        elif c == "A":
            r = num()
            num()  # ry: every arc here is circular
            num()  # x-axis rotation
            large, sweep = int(num()), int(num())
            p1 = (ox + num(), oy + num())
            pts += _arc_points(cur, r, large, sweep, p1)
            cur = p1
        else:
            raise ValueError(f"unsupported path command {cmd}")
    if pts:
        paths.append(pts)
    return paths


def draw_path(mask: Image.Image, d: str, x0: float, y0: float, scale: float, value=255):
    """Fill an SVG path, scaled by `scale` units per path unit and placed at (x0, y0)."""
    draw = ImageDraw.Draw(mask)
    for sub in svg_path(d):
        draw.polygon([((x0 + x * scale) * RES, (y0 + y * scale) * RES) for x, y in sub], fill=value)


# --------------------------------------------------------------------------------------
# Extrusion
# --------------------------------------------------------------------------------------

def runs(mask: Image.Image, step_units: float = STEP) -> list[tuple[float, float, list[tuple[float, float]]]]:
    """[(y_top, y_bottom, [(x0, x1), ...]), ...] in model units, rows merged when equal."""
    w, h = mask.size
    px = mask.load()
    rows = []
    step = round(step_units * RES)
    for top in range(0, h, step):
        y = min(h - 1, top + step // 2)
        spans, inside, sx = [], False, 0
        for x in range(w + 1):
            on = x < w and px[x, y] >= 128
            if on and not inside:
                inside, sx = True, x
            elif not on and inside:
                inside = False
                a = round(sx / RES / step_units) * step_units
                b = round(x / RES / step_units) * step_units
                if b > a:
                    spans.append((a, b))
        rows.append((top / RES, (top + step) / RES, spans))
    merged = []
    for y0, y1, spans in rows:
        if merged and merged[-1][2] == spans and spans:
            merged[-1] = (merged[-1][0], y1, spans)
        else:
            merged.append((y0, y1, spans))
    return [r for r in merged if r[2]]


def extrude(mask: Image.Image, z0: float, z1: float, front: str, side: str, back: str | None = None,
            top: str | None = None, bottom: str | None = None, glow: int = 0,
            shade: bool = False, step: float = STEP) -> list[dict]:
    """Boxes for a mask between z0 and z1. The front (+Z) face samples `front` at its own
    position; sides use `side`, upward faces `top` and downward faces `bottom` (both default
    `side`); `back` (default `side`) covers the -Z face. Shading is off by default: the
    side textures carry their own light (lit tops, darker undersides), so the brand colours
    on the face show exactly as painted in every view."""
    parts = []
    for ty0, ty1, spans in runs(mask, step):
        y0, y1 = 16 - ty1, 16 - ty0  # texture rows run down, model y runs up
        for x0, x1 in spans:
            faces = {
                "south": (front, [x0, ty0, x1, ty1]),
                "north": (back or side, [16 - x1, ty0, 16 - x0, ty1]),
                "east": (side, [16 - z1, ty0, 16 - z0, ty1]),
                "west": (side, [z0, ty0, z1, ty1]),
                "up": (top or side, [x0, 16 - z1, x1, 16 - z0]),
                "down": (bottom or side, [x0, z0, x1, z1]),
            }
            parts.append(box((x0, y0, z0), (x1, y1, z1), side, faces=faces, glow=glow, shade=shade))
    return parts


def vgradient(img: Image.Image, stops, y0: int = 0, y1: int | None = None) -> None:
    """A smooth per-row blend through `stops` (top to bottom), over the opaque pixels."""
    from art.kit import mix, rgba
    w, h = img.size
    y1 = h - 1 if y1 is None else y1
    px = img.load()
    for y in range(h):
        f = min(1.0, max(0.0, (y - y0) / max(1, y1 - y0))) * (len(stops) - 1)
        i = min(len(stops) - 2, int(f))
        c = rgba(mix(stops[i], stops[i + 1], f - i))
        for x in range(w):
            px[x, y] = c


def mask_bounds(mask: Image.Image) -> tuple[float, float, float, float]:
    x0, y0, x1, y1 = mask.getbbox()
    return x0 / RES, y0 / RES, x1 / RES, y1 / RES


# --------------------------------------------------------------------------------------
# Display
# --------------------------------------------------------------------------------------

def badge_display(parts, gui_rotation=(0, -12, 0), gui_span=15.0) -> dict:
    """Straight-on in inventories (a slight turn shows the thickness), held like a card."""
    lo_x = min(min(e["from"][0], e["to"][0]) for e in parts)
    hi_x = max(max(e["from"][0], e["to"][0]) for e in parts)
    lo_y = min(min(e["from"][1], e["to"][1]) for e in parts)
    hi_y = max(max(e["from"][1], e["to"][1]) for e in parts)
    centre = ((lo_x + hi_x) / 2, (lo_y + hi_y) / 2, 8)
    return {
        "gui": fit(parts, gui_rotation, gui_span),
        "thirdperson_righthand": place({"y": (0, 1, 0), "z": (0, 0, -1)}, centre, "fist", 0.5),
        "firstperson_righthand": place({"y": (0, 1, 0), "z": (0.35, 0, 1)}, centre, (0.5, -0.22, -0.85), 0.5,
                                       pose=None),
        "ground": fit(parts, (0, 0, 0), 8.0, lift=2.0),
        "fixed": fit(parts, (0, 180, 0), 14.0),
        "on_shelf": fit(parts, (0, 180, 0), 12.0),
    }
