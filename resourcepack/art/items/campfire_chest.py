"""Campfire Chest (issue #1454): the placed crate chest of the Campfire crate, a black and
crimson fire-forged war chest.

Charred black planks split by glowing ember cracks, held together by blackened, hammered
iron: heavy riveted armour plates wrap all eight corners (each with a jagged tooth), and
four iron horns rise from the lid's corners, curving outward to red-hot tips. Deep crimson
iron frames it: bands round the base, the seam and the lid, a band round the middle of the
body, crossed braces on both side walls meeting in a riveted boss, and a crimson bevel round
the armoured plate on the lid. On that plate sits the forge seal: an octagonal iron boss
with a glowing ember heart, ringed by eight short iron flame-points. On the front, a forged
flame of three crimson tongues with a living fire inside stands on the lid and drops the
hasp into a large black iron lock plate with a crimson border, side spikes, two fangs, an
ember keyhole and a heavy pull ring. Two iron barrel hinges with crimson leaves sit at the
back.

Animation (one loop): fire rolls up inside the flame tongues, the ember cracks and the
seal's heart breathe, the horn tips glow and fade, and the keyhole smoulders.

Block space, front (lock) = +Z. Built on art/items/_crate_chest.py, so it hides a vanilla
chest and stays inside its block like the other crate chests. Deliberately unlike the
earlier log campfire crate (campfire_crate, kept for rollback): no logs or open fire on
top, a horned silhouette instead.
"""
from __future__ import annotations

import math
import random

from art.items._crate_chest import (B0, B1, COUNTERPART, KIND, L0, L1, LID_TOP, SEAM, flame,  # noqa: F401
                                    keyhole_glow, lock_plate, metal, planks, put, rims, shell)
from art.kit import animate, arc, bar, box, canvas, display, model, prism, save, save_animation, turn, wave

ID = "campfire_chest"
NAME = "Campfire Chest"

CHAR = ["#050405", "#0d0a0c", "#151013", "#1e1719", "#282022", "#33292a", "#433536"]
IRON = ["#08080b", "#111116", "#1b1b22", "#26262f", "#33333e", "#454552", "#5c5c6c"]
CRIMSON = ["#1c0205", "#38040a", "#590911", "#7c0f19", "#a01822", "#c4262e", "#e2453f"]
EMBER = ["#240301", "#520902", "#8c1604", "#cc3207", "#f06512", "#ffa23a", "#ffdf88"]
FIRE = ["#4a0603", "#8e1406", "#d0300a", "#f2601a", "#ff9a32", "#ffcf6a", "#fff3c4"]

PLATE_IN = 1.8                       # how far the lid's armoured plate is inset
PLATE_TOP = LID_TOP + 0.75
ARMOUR = 2.1                         # width of the corner armour plates


# ======================================================================================
# Textures
# ======================================================================================

def charred(size: int = 32) -> object:
    """Charred planks: black boards with an alligator crackle and faint red heat in the
    deepest cracks (not lit; the glowing cracks are separate parts)."""
    img = planks(CHAR, size=size, rows=4, seed=41, knots=2)
    rng = random.Random(7)
    for _ in range(26):
        x, y = rng.randrange(size), rng.randrange(size)
        horizontal = rng.random() < 0.6
        for i in range(rng.randint(2, 5)):
            px, py = (x + i, y) if horizontal else (x, y + i)
            put(img, px % size, py % size, CHAR[0])
    for _ in range(14):
        put(img, rng.randrange(size), rng.randrange(size), CRIMSON[1])
    return img


def forged(size: int = 16, seed: int = 2, base: int = 3) -> object:
    """Blackened hammered iron: dented facets, a dull sheen at the top, scale at the edges."""
    img = canvas(size)
    rng = random.Random(seed)
    for y in range(size):
        for x in range(size):
            facet = ((x // 4) * 3 + (y // 4) * 5) % 4
            tone = base + (1 if facet == 0 else 0) - (1 if facet == 3 else 0)
            if y < 2:
                tone += 1
            if y >= size - 2 or x in (0, size - 1):
                tone = 1
            elif x % 4 == 3 or y % 4 == 3:
                tone = min(tone, 1 if base < 3 else 2)      # dark rim round each hammer facet
            elif x % 4 == 0 and y % 4 == 0:
                tone += 1
            if rng.random() < 0.06:
                tone = 2
            put(img, x, y, IRON[max(0, min(6, tone))])
    return img


def rivet() -> object:
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 6, y + 0.5 - 6)
            tone = 6 if d < 2.5 else 5 if d < 5 else 4 if d < 8 else 2 if d < 11 else 1
            put(img, x, y, IRON[tone])
    return img


def lacquer(size: int = 16) -> object:
    """Crimson iron strap: lit top edge, dark lower edge, a scratched centre."""
    img = canvas(size)
    rng = random.Random(9)
    for y in range(size):
        for x in range(size):
            if x <= 1:
                c = CRIMSON[6] if x == 0 else CRIMSON[5]
            elif x >= size - 2:
                c = CRIMSON[1] if x == size - 1 else CRIMSON[2]
            else:
                c = CRIMSON[4] if rng.random() > 0.15 else CRIMSON[3]
            put(img, x, y, c)
    return img


def crimson_plain(size: int = 16) -> object:
    img = canvas(size)
    for y in range(size):
        for x in range(size):
            tone = 5 if y < 3 else 4 if y < 10 else 3
            if (x * 3 + y) % 10 == 0:
                tone -= 1
            put(img, x, y, CRIMSON[tone])
    return img


def ember(t: float) -> object:
    """A glowing crack: a hot core line that breathes, cooling to red at the edges."""
    img = canvas(16)
    pulse = wave(t)
    for y in range(16):
        for x in range(16):
            d = abs(x + 0.5 - 8) / 8
            flick = 0.12 * math.sin(2 * math.pi * (t + y / 16 * 1.0))
            heat = (1 - d) * (0.55 + 0.4 * pulse) + flick
            put(img, x, y, EMBER[max(0, min(6, int(heat * 7)))])
    return img


def hot(t: float) -> object:
    """Red-hot iron (the horn tips): glowing toward the end, fading and returning."""
    img = canvas(16)
    pulse = wave(t, 0.25)
    for y in range(16):
        for x in range(16):
            fy = 1 - (y + 0.5) / 16                # 1 at the top (the tip)
            heat = fy * (0.6 + 0.4 * pulse) - abs(x + 0.5 - 8) / 40
            put(img, x, y, EMBER[max(1, min(6, int(heat * 7 + 0.5)))])
    return img


def fire(t: float) -> object:
    return flame(t, 16, seed=3, palette=FIRE)


def heart(t: float) -> object:
    """The forge seal's ember heart: a bright centre with heat rings spreading out."""
    img = canvas(16)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8) / 8
            ring = 0.5 + 0.5 * math.sin(2 * math.pi * (d * 1.6 - t))
            heat = (1 - d) * 0.95 + 0.25 * ring + 0.15
            put(img, x, y, EMBER[max(2, min(6, int(heat * 7)))])
    return img


def keyhole(t: float) -> object:
    return keyhole_glow(EMBER, t)


def textures() -> None:
    save(charred(), "char")
    save(forged(), "iron")
    save(forged(seed=6, base=2), "plate")
    save(rivet(), "rivet")
    save(lacquer(), "crimson")
    save(crimson_plain(), "crimson_plain")
    save(metal(CRIMSON, rivets=(4, 11)), "band")
    save(lock_plate(IRON, hole=CHAR[0]), "lockplate")
    save_animation(animate(ember, 8), "ember", frametime=3, interpolate=True)
    save_animation(animate(hot, 8), "hot", frametime=4, interpolate=True)
    save_animation(animate(fire, 12), "fire", frametime=2)
    save_animation(animate(heart, 8), "heart", frametime=3, interpolate=True)
    save_animation(animate(keyhole, 8), "keyhole", frametime=3, interpolate=True)


# ======================================================================================
# Geometry
# ======================================================================================

def corner_armour() -> list[dict]:
    """Riveted iron plates wrapping every corner of the body and the lid, each with a
    jagged tooth biting into the wall."""
    parts = []
    t = 0.38
    for (lo, hi, y0, y1, w) in ((B0, B1, 0.0, SEAM - 0.05, ARMOUR), (L0, L1, SEAM, LID_TOP + 0.04, ARMOUR - 0.2)):
        for cx in (lo, hi):
            for cz in (lo, hi):
                sx = 1 if cx == lo else -1
                sz = 1 if cz == lo else -1
                # the plate across x (on the front/back wall) and across z (on the side wall)
                parts.append(box((cx - t * sx, y0, cz - t * sz), (cx + w * sx, y1, cz), "iron",
                                 skip=("down",) if y0 == 0 else ()))
                parts.append(box((cx - t * sx, y0, cz), (cx, y1, cz + w * sz), "iron",
                                 skip=("down",) if y0 == 0 else ()))
                ym = (y0 + y1) / 2
                # a tooth on each plate's inner edge
                tooth = box((cx + w * sx - 0.45, ym - 0.45, cz - t * sz), (cx + w * sx + 0.45, ym + 0.45, cz),
                            "iron")
                turn(tooth, 45, "z", (cx + w * sx, ym, cz))
                parts.append(tooth)
                tooth = box((cx - t * sx, ym - 0.45, cz + w * sz - 0.45), (cx, ym + 0.45, cz + w * sz + 0.45), "iron")
                turn(tooth, 45, "x", (cx, ym, cz + w * sz))
                parts.append(tooth)
                # rivets
                for yy in ((y0 + 1.1, y1 - 1.1) if y1 - y0 > 6 else (ym,)):
                    rx = cx + w * 0.45 * sx
                    parts.append(box((rx - 0.3, yy - 0.3, cz - (t + 0.14) * sz), (rx + 0.3, yy + 0.3, cz - t * sz),
                                     "rivet", uv="full"))
                    rz = cz + w * 0.45 * sz
                    parts.append(box((cx - (t + 0.14) * sx, yy - 0.3, rz - 0.3), (cx - t * sx, yy + 0.3, rz + 0.3),
                                     "rivet", uv="full"))
    return parts


def _spike(base, up: float, lean, width: float, hot_glow: int = 6) -> list[dict]:
    """A short forged spike: an iron shaft and a red-hot point (lean = (dx, dz) per unit up)."""
    mid = (base[0] + lean[0] * up * 0.6, base[1] + up * 0.6, base[2] + lean[1] * up * 0.6)
    tip = (base[0] + lean[0] * up, base[1] + up, base[2] + lean[1] * up)
    return [bar(base, mid, width, width, "iron", roll=45),
            bar(mid, tip, width * 0.55, width * 0.55, "hot", roll=45, glow=hot_glow, uv="full")]


def horns() -> list[dict]:
    """Two great iron horns on the lid's back corners (riveted sockets, then tapering
    crescents that rise and curl inward to red-hot tips), short spikes on the front
    corners."""
    parts = []
    for cx in (L0, L1):
        sx = 1 if cx == L0 else -1
        # the back horn
        bx, bz = cx + 1.35 * sx, L0 + 1.5
        parts += prism((bx, LID_TOP + 0.65, bz), 1.1, 1.3, "iron", sides=8, cap="rivet")
        parts.append(box((bx - 0.85, LID_TOP + 1.2, bz - 0.85), (bx + 0.85, LID_TOP + 1.5, bz + 0.85), "crimson_plain"))
        path = [(0.0, 1.0, 0.0, 1.7), (0.3, 2.4, -0.05, 1.5), (0.45, 3.8, -0.1, 1.22), (0.25, 5.0, -0.15, 0.95),
                (-0.35, 6.0, -0.15, 0.68), (-1.1, 6.6, -0.1, 0.42), (-1.7, 6.85, -0.05, 0.2)]
        pts = [(bx - o * sx, LID_TOP + y, bz + dz, w) for (o, y, dz, w) in path]
        for i, (a, b) in enumerate(zip(pts, pts[1:])):
            hot = i >= 4
            kw = {"glow": 9 if i == 5 else 6, "uv": "full"} if hot else {}
            parts.append(bar(a[:3], b[:3], a[3], a[3], "hot" if hot else "iron", roll=45, **kw))
        for y, w, o in ((2.1, 1.6, 0.17), (3.3, 1.38, 0.38)):
            parts.append(box((bx - o * sx - w / 2, LID_TOP + y - 0.14, bz - 0.05 - w / 2),
                             (bx - o * sx + w / 2, LID_TOP + y + 0.14, bz - 0.05 + w / 2), "crimson_plain"))
        # the front corner spike
        fx, fz = cx + 1.15 * sx, L1 - 1.15
        parts += prism((fx, LID_TOP + 0.45, fz), 0.85, 0.9, "iron", sides=8, cap="rivet")
        parts += _spike((fx, LID_TOP + 0.85, fz), 2.3, (-0.12 * sx, 0.12), 0.95, hot_glow=7)
    return parts


def lid_plate() -> list[dict]:
    """The armoured plate on the lid with its crimson bevel, and the forge seal on it."""
    parts = []
    # first step: an iron plate with a crimson bevel round its top edge
    lo, hi = L0 + 1.0, L1 - 1.0
    y1 = LID_TOP + 0.6
    parts.append(box((lo, LID_TOP - 0.02, lo), (hi, y1, hi), "plate", skip=("down",)))
    e = 0.12
    for (a, b) in (((lo - e, lo - e), (hi + e, lo + 0.55)), ((lo - e, hi - 0.55), (hi + e, hi + e))):
        parts.append(box((a[0], y1 - 0.2, a[1]), (b[0], y1 + 0.12, b[1]), "crimson_plain"))
    for x0, x1 in ((lo - e, lo + 0.55), (hi - 0.55, hi + e)):
        parts.append(box((x0, y1 - 0.2, lo + 0.55), (x1, y1 + 0.12, hi - 0.55), "crimson_plain"))
    # second step: a hammered plate with rivets round it
    lo, hi = L0 + PLATE_IN + 0.9, L1 - PLATE_IN - 0.9
    y2 = PLATE_TOP + 0.55
    parts.append(box((lo, y1 - 0.02, lo), (hi, y2, hi), "plate", skip=("down",)))
    for x in (lo + 0.4, hi - 0.4):
        for z in (lo + 0.4, 8.0, hi - 0.4):
            parts.append(box((x - 0.25, y2, z - 0.25), (x + 0.25, y2 + 0.16, z + 0.25), "rivet", uv="full"))
    for z in (lo + 0.4, hi - 0.4):
        parts.append(box((8.0 - 0.25, y2, z - 0.25), (8.0 + 0.25, y2 + 0.16, z + 0.25), "rivet", uv="full"))
    # the forge seal: an octagonal iron boss, a crimson ring, an ember heart, eight flame points
    cy = y2
    parts += prism((8.0, cy + 0.45, 8.0), 2.3, 0.9, "iron", sides=8, cap="plate")
    parts += prism((8.0, cy + 0.95, 8.0), 1.8, 0.2, "crimson_plain", sides=8, cap="crimson_plain")
    parts += prism((8.0, cy + 1.1, 8.0), 1.25, 0.3, "heart", sides=8, cap="heart", glow=9, uv="full")
    for i in range(8):
        a = math.radians(22.5 + i * 45)
        rx, rz = math.sin(a), math.cos(a)
        base = (8.0 + rx * 2.0, cy + 0.5, 8.0 + rz * 2.0)
        tip = (8.0 + rx * 3.35, cy + 1.25, 8.0 + rz * 3.35)
        parts.append(bar(base, tip, 0.62, 0.62, "iron", roll=45))
        parts.append(bar(tip, (8.0 + rx * 3.75, cy + 1.75, 8.0 + rz * 3.75), 0.32, 0.32, "crimson_plain", roll=45))
    return parts


def plinth() -> list[dict]:
    """A heavy iron plinth round the foot of the body with a crimson lip."""
    return [box((B0 - 0.34, 0.0, B0 - 0.34), (B1 + 0.34, 1.3, B1 + 0.34), "iron", skip=("down",)),
            box((B0 - 0.2, 1.3, B0 - 0.2), (B1 + 0.2, 1.62, B1 + 0.2), "crimson_plain", skip=("down",))]


def bands() -> list[dict]:
    """The crimson band round the middle of the body, a riveted iron strap across the lower
    front, and a forge door on each side wall: a crimson-bordered iron plate with a grate
    of glowing vents and a riveted boss above it."""
    e = 0.16
    parts = [box((B0 - e, 4.6, B0 - e), (B1 + e, 5.4, B1 + e), "band", skip=("up", "down"))]
    for a0, a1 in ((B0 + ARMOUR, 5.0), (11.0, B1 - ARMOUR)):
        parts.append(box((a0, 2.3, B1), (a1, 3.1, B1 + 0.26), "iron"))
        for x in (a0 + 0.55, a1 - 0.55):
            parts.append(box((x - 0.24, 2.46, B1 + 0.26), (x + 0.24, 2.94, B1 + 0.38), "rivet", uv="full"))
    for x, out in ((B1, 1), (B0, -1)):
        def wall(t0, t1):
            return (x + t0, x + t1) if out > 0 else (x - t1, x - t0)
        z0, z1, y0, y1 = 3.4, 12.6, 1.8, 8.3
        xa, xb = wall(0.0, 0.3)
        parts.append(box((xa, y0, z0), (xb, y1, z1), "plate"))
        xa, xb = wall(0.3, 0.46)
        for (zz0, zz1, yy0, yy1) in ((z0, z1, y1 - 0.45, y1), (z0, z1, y0, y0 + 0.45), (z0, z0 + 0.45, y0 + 0.45, y1 - 0.45),
                                     (z1 - 0.45, z1, y0 + 0.45, y1 - 0.45)):
            parts.append(box((xa, yy0, zz0), (xb, yy1, zz1), "crimson_plain"))
        # the grate: four glowing vents behind two iron bars
        for y in (2.75, 3.65, 4.55, 5.45):
            xa, xb = wall(0.3, 0.34)
            parts.append(box((xa, y, 5.0), (xb, y + 0.42, 11.0), "ember", glow=7, shade=False, uv="full"))
        xa, xb = wall(0.3, 0.52)
        for z in (6.9, 9.1):
            parts.append(box((xa, 2.45, z - 0.22), (xb, 6.2, z + 0.22), "iron"))
        parts.append(box((xa, 6.2, 4.6), (xb, 6.6, 11.4), "iron"))
        # the boss above the grate
        xa, xb = wall(0.3, 0.42)
        parts += prism(((xa + xb) / 2, 7.25, 8.0), 0.62, xb - xa, "iron", axis="x", sides=8, cap="rivet")
        xa, xb = wall(0.42, 0.48)
        parts += prism(((xa + xb) / 2, 7.25, 8.0), 0.3, xb - xa, "heart", axis="x", sides=8, cap="heart", glow=7,
                       uv="full")
    return parts


def cracks() -> list[dict]:
    """Glowing ember cracks splitting the charred planks between the iron."""
    parts = []

    def crack(points, face):
        out = []
        for a, b in zip(points, points[1:]):
            if face == "front":
                p0, p1 = (a[0], a[1], B1 + 0.02), (b[0], b[1], B1 + 0.02)
                out.append(bar(p0, p1, 0.26, 0.06, "ember", glow=6, shade=False, uv="full"))
            elif face == "back":
                p0, p1 = (a[0], a[1], B0 - 0.02), (b[0], b[1], B0 - 0.02)
                out.append(bar(p0, p1, 0.26, 0.06, "ember", glow=6, shade=False, uv="full"))
            else:
                x = B1 + 0.02 if face == "east" else B0 - 0.02
                p0, p1 = (x, a[1], a[0]), (x, b[1], b[0])
                out.append(bar(p0, p1, 0.06, 0.26, "ember", glow=6, shade=False, uv="full"))
        return out

    parts += crack([(3.6, 1.6), (4.1, 2.4), (3.7, 3.4), (4.2, 4.4)], "front")
    parts += crack([(12.3, 5.5), (11.8, 6.5), (12.4, 7.5), (12.0, 8.6)], "front")
    parts += crack([(3.9, 5.5), (3.5, 6.4), (4.0, 7.7)], "front")
    parts += crack([(12.1, 1.6), (12.6, 2.6), (12.1, 4.4)], "front")
    parts += crack([(6.0, 5.5), (6.6, 6.9), (6.1, 8.4)], "back")
    parts += crack([(10.2, 5.5), (9.7, 7.0), (10.1, 8.4)], "back")
    return parts


def lock_and_flame() -> list[dict]:
    """The black iron lock plate (covers the vanilla latch) and the forged flame on the lid
    that drops the hasp into it."""
    parts = []
    z0, z1 = B1 - 0.05, 16.02
    parts.append(box((5.4, 4.9, z0), (10.6, 10.2, z1), "lockplate",
                     uv={"north": [0, 0, 4, 4], "east": [0, 0, 1, 4], "west": [0, 0, 1, 4], "up": [0, 0, 4, 1],
                         "down": [0, 0, 4, 1], "south": [0, 0, 16, 16]}))
    # crimson border
    zb = z1 + 0.1
    parts.append(box((5.2, 9.85, z1 - 0.1), (10.8, 10.35, zb), "crimson_plain"))
    parts.append(box((5.2, 4.75, z1 - 0.1), (10.8, 5.25, zb), "crimson_plain"))
    for x in (5.2, 10.3):
        parts.append(box((x, 5.25, z1 - 0.1), (x + 0.5, 9.85, zb), "crimson_plain"))
    # side spikes
    for side in (-1, 1):
        x = 8.0 + side * 2.8
        parts.append(bar((x, 7.5, z1 - 0.2), (x + side * 1.5, 7.5, z1 - 0.2), 0.7, 0.36, "iron", roll=0))
        parts.append(bar((x + side * 1.4, 7.5, z1 - 0.2), (x + side * 2.1, 7.5, z1 - 0.2), 0.34, 0.24, "crimson_plain"))
    # two fangs under the plate
    for x in (6.4, 9.6):
        f = box((x - 0.55, 4.0, z1 - 0.25), (x + 0.55, 5.1, z1 + 0.02), "iron")
        turn(f, 45, "z", (x, 4.75, z1))
        parts.append(f)
    # the keyhole
    parts.append(box((7.45, 7.3, z1 - 0.03), (8.55, 8.4, z1 + 0.03), "keyhole", glow=11, shade=False, uv="full"))
    parts.append(box((7.7, 5.8, z1 - 0.03), (8.3, 7.45, z1 + 0.03), "keyhole", glow=11, shade=False, uv="full"))
    # the pull ring hanging from a staple under the keyhole
    parts.append(box((7.6, 5.1, z1), (8.4, 5.6, z1 + 0.16), "iron"))
    parts += arc((8.0, 4.15, z1 + 0.08), 0.85, 0, 360, 8, 0.3, 0.16, "iron", plane="xy")
    # the hasp from the flame's foot down onto the plate
    parts.append(box((7.35, 8.95, z1), (8.65, 11.0, z1 + 0.16), "iron"))
    parts.append(box((7.7, 9.2, z1 + 0.16), (8.3, 9.8, z1 + 0.22), "rivet", uv="full"))
    # --- the forged flame on the lid's front
    z = L1
    parts.append(box((5.3, 10.55, z - 0.05), (10.7, 11.25, z + 0.42), "crimson"))    # the cup
    parts.append(box((5.9, 10.3, z - 0.05), (10.1, 10.6, z + 0.3), "iron"))
    # centre tongue: a crimson shell and fire inside, swaying a little
    centre = [(8.0, 11.0, 2.2), (8.3, 12.1, 1.9), (7.9, 13.3, 1.45), (8.25, 14.4, 0.95), (8.6, 15.3, 0.5),
              (8.4, 15.9, 0.22)]
    for (a, b) in zip(centre, centre[1:]):
        parts.append(bar((a[0], a[1], z + 0.15), (b[0], b[1], z + 0.15), a[2], 0.38, "crimson_plain"))
        if a[2] > 0.5:
            parts.append(bar((a[0], a[1] + 0.1, z + 0.36), (b[0], b[1], z + 0.36), a[2] * 0.55, 0.06, "fire", glow=10,
                             shade=False, uv="full"))
    for side in (-1, 1):
        tongue = [(8.0 + side * 1.3, 11.0, 1.45), (8.0 + side * 2.2, 12.2, 1.2), (8.0 + side * 2.3, 13.3, 0.85),
                  (8.0 + side * 1.9, 14.2, 0.45), (8.0 + side * 1.5, 14.7, 0.2)]
        for (a, b) in zip(tongue, tongue[1:]):
            parts.append(bar((a[0], a[1], z + 0.1), (b[0], b[1], z + 0.1), a[2], 0.32, "crimson_plain"))
            if a[2] > 0.6:
                parts.append(bar((a[0], a[1] + 0.1, z + 0.27), (b[0], b[1], z + 0.27), a[2] * 0.5, 0.06, "fire",
                                 glow=10, shade=False, uv="full"))
        # a small outer lick
        parts.append(bar((8.0 + side * 2.6, 11.0, z + 0.05), (8.0 + side * 3.4, 12.2, z + 0.05), 0.7, 0.26,
                         "crimson_plain"))
        parts.append(bar((8.0 + side * 3.4, 12.2, z + 0.05), (8.0 + side * 3.3, 12.9, z + 0.05), 0.36, 0.24,
                         "crimson_plain"))
    return parts


def back_hinges() -> list[dict]:
    """Long iron strap hinges with crimson inlay running from the lid's top down to the
    plinth, iron knuckles at the seam, and a glowing flame brand between them."""
    parts = []
    for x in (4.2, 11.8):
        parts += prism((x, SEAM, L0 + 0.08), 0.5, 2.4, "iron", axis="x", sides=8, cap="rivet")
        parts.append(box((x - 0.85, 1.3, B0 - 0.26), (x + 0.85, SEAM - 0.4, B0), "iron"))
        parts.append(box((x - 0.85, SEAM + 0.4, L0 - 0.2), (x + 0.85, LID_TOP + 0.3, L0), "iron"))
        parts.append(box((x - 0.3, 1.8, B0 - 0.34), (x + 0.3, SEAM - 0.9, B0 - 0.26), "crimson_plain"))
        parts.append(box((x - 0.3, SEAM + 0.9, L0 - 0.24), (x + 0.3, LID_TOP - 0.3, L0 - 0.2), "crimson_plain"))
        for y in (2.2, SEAM - 1.4):
            parts.append(box((x - 0.25, y - 0.25, B0 - 0.38), (x + 0.25, y + 0.25, B0 - 0.26), "rivet", uv="full"))
    # the brand: a three-tongued flame burnt into the back
    z = B0 - 0.02
    for (a, b, w) in (((8.0, 1.9), (8.25, 3.0), 0.5), ((8.25, 3.0), (7.9, 4.0), 0.36),
                      ((7.4, 2.0), (6.9, 3.1), 0.32), ((8.6, 2.0), (9.1, 3.1), 0.32)):
        parts.append(bar((a[0], a[1], z), (b[0], b[1], z), w, 0.06, "ember", glow=6, shade=False, uv="full"))
    parts.append(box((6.9, 1.7, z - 0.03), (9.1, 1.95, z + 0.03), "ember", glow=6, shade=False, uv="full"))
    return parts


def models() -> dict:
    p = shell("char", "char", "plate")
    p += rims("band")[1:]
    p += plinth()
    p += corner_armour()
    p += bands()
    p += cracks()
    p += lid_plate()
    p += horns()
    p += lock_and_flame()
    p += back_hinges()
    return {"main": model(p, display(KIND, p, size=1.0, gui_rotation=(28, 25, 0), gui_span=15.0))}
