"""YouTube GUI icon (issue #1355): YouTube's play button, a red rounded rectangle with the
white play triangle raised on its face. The triangle has softly rounded corners like the
real mark, and a glossy glint sweeps across the button every few seconds.

A GUI icon first: it is read straight on in inventories, so the silhouette is the wide
button itself. Built with the shared strip extruder in _social_icon, as a set with
discord_icon (same layer depths, bevel and glint timing).
"""
from __future__ import annotations

from PIL import ImageFilter

from art.kit import animate, canvas, mix, model, rgba, save, save_animation, shine
from art.items import _social_icon as s

ID = "youtube_icon"
NAME = "YouTube"
KIND = "item"
COUNTERPART = "item/stick"

# YouTube red (#FF0000), hue-shifted: warmer coral in light, deep crimson in shadow.
RED = "#ff0000"
PLATE_TOP = "#ff4a3d"
PLATE_BOTTOM = "#e0001b"
RIM_LIGHT = "#ff8a7a"
RIM_DARK = "#c4001a"
SIDE = ["#6e0019", "#93001c", "#bf0010"]
WHITE = "#ffffff"
WHITE_SHADOW = "#ffe9e6"
LOGO_SIDE = ["#e8b4b0", "#f6d6d2"]

# Button and triangle in model units (y down, like textures). YouTube's mark is about
# 1.43 : 1 with the triangle a little right of centre.
BUTTON = (0.8, 3.0, 15.2, 13.0)
RADIUS = 3.1
TRIANGLE = ((6.35, 5.4), (6.35, 10.6), (11.05, 8.0))
TRIANGLE_ROUND = 0.3
FRAMES, FRAMETIME = 16, 3

# z layers (front = +Z), the same as discord_icon so the two sit together as a set.
Z_BACK, Z_FACE, Z_FRONT, Z_LOGO = 6.2, 9.0, 9.6, 10.7


def masks():
    body = s.blank()
    s.rounded_rect(body, *BUTTON, RADIUS)
    face = s.inset(body, 0.5)
    # Rounded triangle: shrink the triangle, then grow it back with a round filter.
    sharp = s.blank()
    cx = sum(p[0] for p in TRIANGLE) / 3
    cy = sum(p[1] for p in TRIANGLE) / 3
    k = 1 - TRIANGLE_ROUND * 2.2 / (TRIANGLE[2][0] - TRIANGLE[0][0])
    s.polygon(sharp, [(cx + (x - cx) * k, cy + (y - cy) * k) for x, y in TRIANGLE])
    grow = 2 * round(TRIANGLE_ROUND * s.RES) + 1
    logo = sharp.filter(ImageFilter.MaxFilter(grow))
    return body, face, logo


def _plate(t: float, mask):
    img = canvas(32)
    s.vgradient(img, [PLATE_TOP, RED, RED, PLATE_BOTTOM], y0=6, y1=25)
    px = img.load()
    for y in range(32):
        for x in range(32):
            d = ((x - 9) ** 2 + (y - 10) ** 2) ** 0.5
            k = max(0.0, 1 - d / 14) * 0.16
            if k:
                r, g, b, a = px[x, y]
                px[x, y] = (round(r + (255 - r) * k), round(g + (255 - g) * k), round(b + (255 - b) * k), a)
    m = mask.resize((32, 32))
    mp = m.load()
    for y in range(32):
        for x in range(32):
            if mp[x, y] < 128 and (y > 0 and mp[x, y - 1] >= 128 or x > 0 and y > 0 and mp[x - 1, y - 1] >= 128):
                r, g, b, a = px[x, y]
                px[x, y] = rgba(mix("#%02x%02x%02x" % (r, g, b), RIM_DARK, 0.45))
    return shine(img, t, width=5, strength=0.22, pause=0.55)


def _logo(t: float):
    img = canvas(32)
    s.vgradient(img, [WHITE, WHITE, WHITE_SHADOW], y0=10, y1=21)
    return shine(img, t, colour="#ffc9c2", width=5, strength=0.6, pause=0.55)


def textures() -> None:
    body, _, logo = masks()
    save_animation(animate(lambda t: _plate(t, logo), FRAMES), "youtube_icon", frametime=FRAMETIME)
    save_animation(animate(_logo, FRAMES), "logo", frametime=FRAMETIME)

    rim = canvas(32)
    s.vgradient(rim, [RIM_LIGHT, mix(RIM_LIGHT, RED, 0.55), RED, RIM_DARK], y0=6, y1=25)
    save(rim, "rim")

    for name, stops in (("side_top", [RIM_LIGHT, RED]), ("side", [SIDE[2], SIDE[1]]),
                        ("side_bottom", [SIDE[1], SIDE[0]])):
        img = canvas(16)
        s.vgradient(img, stops)
        save(img, name)
    # The back carries a quiet embossed triangle, so the button isn't blank from behind. It is
    # deliberately not mirrored: seen from behind it still reads as "play" (pointing right).
    back = canvas(32)
    s.vgradient(back, [SIDE[2], SIDE[0]], y0=6, y1=25)
    bp, mp = back.load(), logo.resize((32, 32)).load()
    for y in range(32):
        for x in range(32):
            if mp[x, y] >= 128:
                r, g, b, a = bp[x, y]
                bp[x, y] = rgba(mix("#%02x%02x%02x" % (r, g, b), RED, 0.5))
    save(back, "back")

    for name, stops in (("logo_top", [WHITE, LOGO_SIDE[1]]), ("logo_side", [LOGO_SIDE[1], LOGO_SIDE[0]]),
                        ("logo_bottom", [LOGO_SIDE[0], "#d0938e"])):
        img = canvas(16)
        s.vgradient(img, stops)
        save(img, name)


def build() -> list[dict]:
    body, face, logo = masks()
    # The badge walls use finer strips so the curved corners don't rib.
    parts = s.extrude(body, Z_BACK, Z_FACE, "rim", "side", back="back", top="side_top", bottom="side_bottom",
                      step=0.125)
    parts += s.extrude(face, Z_FACE, Z_FRONT, "youtube_icon", "rim", top="side_top", bottom="side", step=0.125)
    parts += s.extrude(logo, Z_FRONT, Z_LOGO, "logo", "logo_side", top="logo_top", bottom="logo_bottom")
    return parts


def models() -> dict:
    parts = build()
    return {"main": model(parts, s.badge_display(parts, gui_rotation=(0, 0, 0)))}
