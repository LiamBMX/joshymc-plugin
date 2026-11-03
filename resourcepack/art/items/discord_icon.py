"""Discord GUI icon (issue #1355): the Discord app badge, a blurple squircle with the white
Clyde logomark raised on its face. Clyde's eyes are real holes, so the blurple plate shows
through them one unit deeper. A glossy glint sweeps across the badge every few seconds.

A GUI icon first: it is read straight on in inventories, so the silhouette is the badge
itself and the logo fills most of it. Built with the shared strip extruder in _social_icon.
"""
from __future__ import annotations

from art.kit import animate, canvas, mix, model, rgba, save, save_animation, shine
from art.items import _social_icon as s

ID = "discord_icon"
NAME = "Discord"
KIND = "item"
COUNTERPART = "item/stick"

# Discord's blurple (#5865F2), hue-shifted: cooler and deeper in shadow, warmer and paler in light.
BLURPLE = "#5865f2"
PLATE_TOP = "#6774f5"
PLATE_BOTTOM = "#4450d8"
RIM_LIGHT = "#8c97f8"
RIM_DARK = "#343fb3"
SIDE = ["#2c3496", "#3a44b4", "#4752c4"]
WHITE = "#ffffff"
WHITE_SHADOW = "#e4e7ff"
LOGO_SIDE = ["#b9c0f5", "#d3d8fb"]

# Clyde, Discord's logomark (127.14 x 96.36 path units).
CLYDE = (
    "M107.7,8.07A105.15,105.15,0,0,0,81.47,0a72.06,72.06,0,0,0-3.36,6.83A97.68,97.68,0,0,0,49,6.83,"
    "72.37,72.37,0,0,0,45.64,0,105.89,105.89,0,0,0,19.39,8.09C2.79,32.65-1.71,56.6.54,80.21h0"
    "A105.73,105.73,0,0,0,32.71,96.36,77.7,77.7,0,0,0,39.6,85.25a68.42,68.42,0,0,1-10.85-5.18"
    "c.91-.66,1.8-1.34,2.66-2a75.57,75.57,0,0,0,64.32,0c.87.71,1.76,1.39,2.66,2"
    "a68.68,68.68,0,0,1-10.87,5.19,77,77,0,0,0,6.89,11.1A105.25,105.25,0,0,0,126.6,80.22h0"
    "C129.24,52.84,122.09,29.11,107.7,8.07Z"
)
CLYDE_W, CLYDE_H = 127.14, 96.36
EYES = ((42.45, 53.0), (84.69, 53.0))
EYE_RX, EYE_RY = 12.4, 13.7  # ~8% over the real mark: each eye keeps 4 px at GUI scale 2

LOGO_W = 10.0
FRAMES, FRAMETIME = 16, 3

# z layers (front = +Z): body, bevelled face, raised logo.
Z_BACK, Z_FACE, Z_FRONT, Z_LOGO = 6.2, 9.0, 9.6, 10.7


def masks():
    body = s.blank()
    s.squircle(body, 1.0, 1.0, 15.0, 15.0, n=4.2)
    face = s.inset(body, 0.5)
    logo = s.blank()
    scale = LOGO_W / CLYDE_W
    x0, y0 = 8 - LOGO_W / 2, 8.15 - CLYDE_H * scale / 2
    s.draw_path(logo, CLYDE, x0, y0, scale)
    eyes = s.blank()
    for ex, ey in EYES:
        s.ellipse(eyes, x0 + ex * scale, y0 + ey * scale, EYE_RX * scale, EYE_RY * scale)
    logo.paste(0, mask=eyes)
    return body, face, logo, eyes


def _plate(t: float, mask, eyes):
    img = canvas(32)
    s.vgradient(img, [PLATE_TOP, BLURPLE, BLURPLE, PLATE_BOTTOM])
    px = img.load()
    # A soft top-left sheen, so the badge reads as a slightly domed glossy tile.
    for y in range(32):
        for x in range(32):
            d = ((x - 9) ** 2 + (y - 7) ** 2) ** 0.5
            k = max(0.0, 1 - d / 15) * 0.10
            if k:
                r, g, b, a = px[x, y]
                px[x, y] = (round(r + (255 - r) * k), round(g + (255 - g) * k), round(b + (255 - b) * k), a)
    # Logo shadow: the plate darkens just below and right of Clyde (and inside the eyes).
    m = mask.resize((32, 32))
    mp = m.load()
    for y in range(32):
        for x in range(32):
            if mp[x, y] < 128 and (y > 0 and mp[x, y - 1] >= 128 or x > 0 and y > 0 and mp[x - 1, y - 1] >= 128):
                r, g, b, a = px[x, y]
                px[x, y] = rgba(mix("#%02x%02x%02x" % (r, g, b), RIM_DARK, 0.45))
    # The eye holes show flat brand blurple, never the shadow pass.
    ep = eyes.resize((32, 32)).load()
    for y in range(32):
        for x in range(32):
            if ep[x, y] >= 128:
                px[x, y] = rgba(BLURPLE)
    return shine(img, t, width=5, strength=0.22, pause=0.55)


def _logo(t: float):
    img = canvas(32)
    s.vgradient(img, [WHITE, WHITE, WHITE_SHADOW])
    return shine(img, t, colour="#c9cffc", width=5, strength=0.6, pause=0.55)


def textures() -> None:
    _, _, logo, eyes = masks()
    save_animation(animate(lambda t: _plate(t, logo, eyes), FRAMES), "discord_icon", frametime=FRAMETIME)
    save_animation(animate(_logo, FRAMES), "logo", frametime=FRAMETIME)

    rim = canvas(32)
    s.vgradient(rim, [RIM_LIGHT, mix(RIM_LIGHT, BLURPLE, 0.55), BLURPLE, RIM_DARK])
    save(rim, "rim")

    # Walls: lit on top, mid on the sides, deep underneath; the back a calm dark blurple.
    for name, stops in (("side_top", [RIM_LIGHT, BLURPLE]), ("side", [SIDE[2], SIDE[1]]),
                        ("side_bottom", [SIDE[1], SIDE[0]])):
        img = canvas(16)
        s.vgradient(img, stops)
        save(img, name)
    # The back carries a quiet embossed Clyde, so the badge isn't blank from behind.
    back = canvas(32)
    s.vgradient(back, [SIDE[2], SIDE[0]])
    bp, mp = back.load(), logo.resize((32, 32)).load()
    for y in range(32):
        for x in range(32):
            if mp[x, y] >= 128:
                r, g, b, a = bp[x, y]
                bp[x, y] = rgba(mix("#%02x%02x%02x" % (r, g, b), BLURPLE, 0.55))
    save(back, "back")

    for name, stops in (("logo_top", [WHITE, LOGO_SIDE[1]]), ("logo_side", [LOGO_SIDE[1], LOGO_SIDE[0]]),
                        ("logo_bottom", [LOGO_SIDE[0], "#9aa2e6"])):
        img = canvas(16)
        s.vgradient(img, stops)
        save(img, name)


def build() -> list[dict]:
    body, face, logo, _ = masks()
    # The badge walls use finer strips so the curved corners don't rib.
    parts = s.extrude(body, Z_BACK, Z_FACE, "rim", "side", back="back", top="side_top", bottom="side_bottom",
                      step=0.125)
    parts += s.extrude(face, Z_FACE, Z_FRONT, "discord_icon", "rim", top="side_top", bottom="side", step=0.125)
    parts += s.extrude(logo, Z_FRONT, Z_LOGO, "logo", "logo_side", top="logo_top", bottom="logo_bottom")
    return parts


def models() -> dict:
    parts = build()
    return {"main": model(parts, s.badge_display(parts, gui_rotation=(0, 0, 0)))}
