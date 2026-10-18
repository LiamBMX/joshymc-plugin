"""Gun (issue #1229): a compact futuristic blaster. Looks only, no functionality.

Built from the issue's description (the concept sketch wasn't attached): a light blue
plated body with a raised top shroud, layered purple and lavender side panels, a thin
red strip along the top and a small red accent at the rear, a dark gunmetal muzzle with
a ringed barrel, and a big curved grip swept back under the body with a trigger guard.

Animation (seamless): a glint sweeps the blue plating now and then, and the red strip
and rear accent pulse softly.

Frame: barrel along +X (muzzle at the high end), up = +Y, the side seen in the inventory
faces +Z. The body is centred on z = 8.
"""
from __future__ import annotations

import math

from art.kit import (animate, arc, bar, bevel, box, canvas, fill, fit, gradient, mirror, mix, model, place,
                     prism, rgba, save, save_animation, shine, speckle, turn, wave)

ID = "gun"
NAME = "Gun"
KIND = "item"
COUNTERPART = "item/crossbow_standby"

# Palettes, dark -> light, shadows shifted toward violet and lights toward cyan.
BLUE = ["#1f2f68", "#2c4d92", "#3f74bd", "#5f9fdc", "#8cc7ef", "#c4e9fb", "#f0fbff"]
PURP = ["#22113f", "#3b1d66", "#5a3194", "#7b4dbd", "#9f74db", "#c4a3ef", "#e7d8fb"]
RED = ["#3d0716", "#7a1023", "#c01f33", "#ee3d45", "#ff7a6b", "#ffc2a8"]
DARK = ["#0a0b13", "#141622", "#1f2232", "#2e3348", "#454c66", "#646d8a"]
CYAN = ["#0a3a52", "#137aa0", "#2cc3e6", "#8af0ff", "#e6fdff"]

C = 8.0  # body centre (z)
GRIP = (4.4, 5.8, 8.0)  # where the hand closes on the handle


# --------------------------------------------------------------------------------------
# Textures
# --------------------------------------------------------------------------------------

def plate(base: list[str], light=4, mid=3, dark=2, size=32, seed=0):
    """A bevelled plate: lit top/left rim, shaded bottom/right rim, soft vertical gradient."""
    img = canvas(size)
    gradient(img, (0, 0, size - 1, size - 1), [base[light], base[mid], base[mid], base[dark]], axis="y")
    speckle(img, (2, 2, size - 3, size - 3), [base[mid]], density=0.05, seed=seed)
    bevel(img, (0, 0, size - 1, size - 1), base[light + 1], base[dark - 1], width=2)
    fill(img, (0, 0, size - 1, 0), base[min(light + 2, len(base) - 1)])
    return img


def shell_frame(t):
    img = plate(BLUE, light=5, mid=4, dark=3, seed=3)
    return shine(img, t, colour=BLUE[6], width=5, strength=0.55, angle=30, pause=0.55)


def red_frame(t):
    k = wave(t)
    img = canvas(16)
    gradient(img, (0, 0, 15, 15), [mix(RED[3], RED[5], k * 0.6), mix(RED[2], RED[4], k * 0.5),
                                   mix(RED[1], RED[3], k * 0.4)], axis="y")
    bevel(img, (0, 0, 15, 15), mix(RED[4], RED[5], k), RED[1], width=1)
    return img


def textures():
    save_animation(animate(shell_frame, 20), "shell", frametime=3)
    save_animation(animate(red_frame, 20), "red", frametime=3, interpolate=True)

    save(plate(BLUE, light=5, mid=5, dark=4, seed=5), "shell_lt")
    # Flat swatches for thin parts, where a bevelled plate would squash into all rim.
    for name, colours in (("shell_flat", (BLUE[5], BLUE[4])), ("panel_flat", (PURP[4], PURP[3])),
                          ("metal_flat", (DARK[5], DARK[4]))):
        flat = canvas(16, fill=colours[1])
        fill(flat, (0, 0, 15, 3), colours[0])
        save(flat, name)
    # Unbevelled blue for the curved grip, so its segments blend into one smooth sweep.
    smooth = canvas(32)
    gradient(smooth, (0, 0, 31, 31), [BLUE[5], BLUE[4], BLUE[3], BLUE[3], BLUE[2]], axis="x")
    speckle(smooth, (0, 0, 31, 31), [BLUE[3]], density=0.04, seed=17)
    save(smooth, "grip_shell")
    save(plate(PURP, seed=7), "panel")
    save(plate(PURP, light=5, mid=4, dark=3, seed=9), "panel_lt")
    save(plate(DARK, seed=11), "metal")
    save(plate(DARK, light=4, mid=3, dark=2, seed=13), "metal_lt")

    bore = canvas(16, fill=DARK[0])
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5)
            if r > 7.5:
                c = DARK[3]
            elif r > 6:
                c = DARK[2]
            elif r > 4.5:
                c = DARK[1]
            else:
                c = DARK[0]
            bore.putpixel((x, y), rgba(c))
    save(bore, "bore")

    # Grip wrap: purple with dark horizontal ridges for the fingers.
    grip = canvas(32, fill=PURP[3])
    gradient(grip, (0, 0, 31, 31), [PURP[5], PURP[4], PURP[3]], axis="x")
    for y in range(3, 32, 8):
        fill(grip, (0, y, 31, y), PURP[1])
        fill(grip, (0, y + 1, 31, y + 1), PURP[6])
    save(grip, "grip")

    vent = canvas(16, fill=DARK[2])
    for x in range(1, 16, 3):
        fill(vent, (x, 2, x + 1, 13), DARK[0])
        fill(vent, (x + 2, 2, x + 2, 13), DARK[4])
    bevel(vent, (0, 0, 15, 15), DARK[4], DARK[0], width=1)
    save(vent, "vent")

    cell = canvas(16, fill=CYAN[2])
    gradient(cell, (0, 0, 15, 15), [CYAN[4], CYAN[3], CYAN[2], CYAN[1]], axis="y")
    bevel(cell, (0, 0, 15, 15), CYAN[4], CYAN[0], width=1)
    save(cell, "cell")


# --------------------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------------------

def P(frm, to, tex, **kw):
    return box(frm, to, tex, uv="full", **kw)


def body():
    parts = [
        P((1.0, 7.6, 6.0), (14.0, 12.6, 10.0), "shell"),                 # receiver
        P((3.0, 12.6, 6.5), (12.2, 14.0, 9.5), "shell_lt"),               # top shroud
        P((-0.4, 8.2, 6.3), (1.0, 12.4, 9.7), "shell"),                   # rear block
        P((4.4, 14.0, 7.2), (11.4, 14.8, 8.8), "red", glow=9),            # red top strip
        P((-1.4, 10.3, 7.3), (-0.4, 11.7, 8.7), "red", glow=9),           # red rear accent
        P((-0.7, 8.6, 6.8), (-0.4, 9.6, 9.2), "metal_flat"),              # rear vent
        P((0.8, 13.6, 7.4), (2.4, 14.4, 8.6), "metal_lt"),                # rear sight
        P((12.2, 13.4, 7.4), (13.4, 14.2, 8.6), "metal_lt"),              # front sight
        P((11.4, 14.0, 7.6), (12.2, 14.4, 8.4), "metal_flat"),            # strip end cap
        P((8.0, 6.6, 6.6), (14.0, 7.6, 9.4), "panel"),                    # under-rail
    ]
    # Swept rear of the shroud (its red cap continues the top strip) and the sloped nose.
    parts.append(turn(P((0.2, 12.0, 6.5), (3.8, 13.4, 9.5), "shell_lt"), 22, "z", (2.0, 12.6, C)))
    parts.append(turn(P((2.4, 13.4, 7.2), (4.0, 13.9, 8.8), "red", glow=9), 22, "z", (2.0, 12.6, C)))
    parts.append(turn(P((11.4, 11.6, 6.5), (14.6, 13.2, 9.5), "shell_lt"), -28, "z", (13.0, 12.4, C)))
    # Forward fin under the nose.
    parts.append(turn(P((11.0, 6.0, 7.0), (14.6, 7.0, 9.0), "panel"), 12, "z", (14.0, 6.6, C)))
    return parts


def muzzle():
    parts = [P((14.0, 8.2, 6.4), (17.4, 12.2, 9.6), "metal")]             # emitter housing
    parts.append(turn(P((13.6, 11.2, 6.4), (16.6, 12.8, 9.6), "metal"), -24, "z", (15.4, 12.0, C)))
    parts.append(P((14.4, 7.4, 6.9), (18.4, 8.2, 9.1), "metal_lt"))      # lower jaw
    parts += prism((18.9, 10.3, C), 1.35, 3.2, "metal_lt", axis="x", cap="bore")          # barrel
    parts += prism((20.7, 10.3, C), 1.75, 0.9, "metal", axis="x", cap="bore")             # muzzle ring
    parts += prism((17.6, 10.3, C), 1.7, 0.5, "metal", axis="x", cap="metal_flat")        # rear collar
    for x in (18.4, 19.4):                                                                 # cooling rings
        parts += prism((x, 10.3, C), 1.55, 0.35, "metal_flat", axis="x", cap="metal_flat")
    return parts


def side_panels():
    """Layered panels on the -Z side, mirrored to +Z."""
    z0 = 6.0
    side = [
        P((3.4, 9.0, z0 - 0.4), (9.6, 10.8, z0), "panel"),                # purple side panel
        P((4.0, 9.4, z0 - 0.65), (7.4, 10.4, z0 - 0.4), "panel_lt"),      # lavender inset
        P((8.6, 10.9, z0 - 0.3), (12.2, 11.4, z0), "panel_flat"),         # stripe toward the nose
        P((10.2, 8.8, z0 - 0.3), (12.4, 10.4, z0), "vent"),               # heat vent
        P((4.2, 13.0, 6.5 - 0.25), (11.6, 13.3, 6.5), "panel_flat"),      # shroud pinstripe
        P((0.0, 10.6, 6.3 - 0.4), (1.4, 11.6, 6.3), "red", glow=9),       # red rear side tab
        P((0.0, 8.6, 6.3 - 0.35), (1.4, 10.2, 6.3), "cell", glow=10),     # power cell window
        P((14.2, 9.0, 6.4 - 0.3), (16.8, 11.4, 6.4), "metal_lt"),         # muzzle side plate
        P((1.4, 7.7, z0 - 0.25), (13.6, 8.2, z0), "shell_flat"),          # lower body trim
    ]
    for x, y in ((2.6, 11.6), (2.6, 8.7)):                                # fasteners
        side.append(P((x - 0.3, y - 0.3, z0 - 0.25), (x + 0.3, y + 0.3, z0), "metal_flat"))
    return side + mirror(side, "z", C)


def handle():
    """The big curved grip swept back under the body, plus the trigger and its guard."""
    centre, radius = (-4.0, 8.4, C), 8.4
    start, end = -2.0, -62.0
    parts = arc(centre, radius, start, end, 8, 3.8, 3.0, "grip_shell")
    parts += arc(centre, radius, start - 3, end + 6, 7, 1.2, 3.2, "grip")          # purple inset band
    parts += arc(centre, radius + 1.85, start - 4, end + 4, 7, 1.0, 2.6, "grip")   # purple finger edge
    # Pommel cap at the bottom of the curve.
    a = math.radians(end - 3)
    px, py = centre[0] + radius * math.cos(a), centre[1] + radius * math.sin(a)
    tx, ty = -math.sin(a), math.cos(a)
    parts.append(bar((px - tx * 0.2, py - ty * 0.2, C), (px + tx * 1.4, py + ty * 1.4, C), 1.6, 3.6, "shell_lt"))
    parts.append(bar((px - tx * 0.6, py - ty * 0.6, C), (px - tx * 0.1, py - ty * 0.1, C), 1.2, 2.6, "metal"))
    # Trigger guard and trigger.
    parts += arc((7.0, 7.6, C), 2.4, 190, 350, 6, 0.6, 1.6, "metal_flat")
    parts.append(bar((9.3, 7.7, C), (9.4, 6.6, C), 0.6, 1.6, "metal_flat"))
    parts.append(bar((6.6, 7.6, C), (7.0, 5.9, C), 0.6, 0.9, "panel_flat"))
    return parts


def build():
    return body() + muzzle() + side_panels() + handle()


def models() -> dict:
    parts = build()
    disp = {
        "thirdperson_righthand": place({"x": (0, 0, 1), "y": (0, 1, 0)}, GRIP, "fist", 0.6),
        "firstperson_righthand": place({"x": (-0.4, 0.08, -1), "y": (0, 1, 0)}, GRIP, (0.40, -0.36, -0.75),
                                       0.5, pose=None),
        "gui": fit(parts, (8, -12, 14), 14.5),
        "ground": fit(parts, (0, 0, 0), 9.0, lift=2.0),
        "fixed": fit(parts, (0, 180, 0), 14.0),
        "on_shelf": fit(parts, (0, 180, 0), 12.0),
    }
    return {"main": model(parts, disp)}
