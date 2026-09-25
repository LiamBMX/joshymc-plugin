"""Reference: a small animated item (a glass orb with a swirling glow inside).

Shows the animated-texture helpers: animate() paints frames from a phase t in [0, 1),
wave() gives smooth 0..1..0 pulses, shine() sweeps a glint across a frame, sparkle()
twinkles a point, and save_animation() writes the strip + .png.mcmeta. lathe() turns
the orb. Modules starting with _ never ship.
"""
from __future__ import annotations

import math

from art.kit import (animate, box, canvas, display, lathe, mix, model, ramp, rgba, save, save_animation,
                     shine, sparkle, wave)

ID = "_example_animated"
NAME = "Example Orb"
KIND = "item"
COUNTERPART = "item/ender_eye"

GLASS = ramp("#5fa8ff", 6, contrast=0.8)
GOLD = ramp("#d8a638", 5, contrast=0.8)


def _orb_frame(t: float):
    """One 32x32 frame: a spiral of light turning inside deep blue glass."""
    img = canvas(32)
    px = img.load()
    for y in range(32):
        for x in range(32):
            dx, dy = x - 15.5, y - 15.5
            r = math.hypot(dx, dy) / 16
            a = math.atan2(dy, dx)
            swirl = 0.5 + 0.5 * math.sin(3 * a + 7 * r - 2 * math.pi * t)
            glow = swirl * (1 - r) * (0.75 + 0.25 * wave(t))
            px[x, y] = rgba(mix(GLASS[0], GLASS[5], min(1.0, glow * 1.3)))
    img = shine(img, t, width=4, strength=0.55)
    sparkle(img, 9, 8, wave(t, 0.1), reach=2)
    sparkle(img, 22, 20, wave(t, 0.6), reach=1)
    return img


def textures():
    save_animation(animate(_orb_frame, 16), "orb", frametime=2)
    cap = canvas(16)
    for y in range(16):
        for x in range(16):
            cap.putpixel((x, y), rgba(GOLD[1 + (x + y) % 3]))
    save(cap, "gold")


def build():
    # uv="full" stretches one whole frame over every face, so the swirl shows on each slice.
    profile = [(0, 2.0), (1, 4.2), (2.5, 5.6), (4.5, 6.2), (6.5, 6.2), (8.5, 5.6), (10, 4.2), (11, 2.0)]
    parts = lathe((8, 4, 8), profile, "orb", sides=16, glow=6, uv="full")
    parts.append(box((6, 2, 6), (10, 4, 10), "gold"))
    return parts


def models() -> dict:
    parts = build()
    return {"main": model(parts, display(KIND, parts, size=1.2))}
