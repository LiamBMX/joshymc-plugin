"""Reference item: a steel greatsword with a gold guard, wrapped grip and glowing pommel gem.

Copy this structure: module constants, textures(), models(). Modules whose file name
starts with "_" are examples and never ship in the pack.
"""
from art.kit import (art, bevel, box, canvas, display, fill, gradient, grain, mirror, model, prism, ramp,
                     save, turn)

ID = "_example"
NAME = "Example Blade"
KIND = "sword"

STEEL = ramp("#9aa7b4", 6)
GOLD = ramp("#e0a82e", 6)
LEATHER = ramp("#6b3f22", 5)


def textures() -> None:
    # Blade: bright cutting edges, a darker fuller down the middle, 32px for detail.
    blade = canvas(32)
    gradient(blade, (0, 0, 31, 31), [STEEL[3], STEEL[4], STEEL[3], STEEL[2]], axis="x")
    fill(blade, (13, 0, 18, 31), STEEL[1])
    fill(blade, (14, 0, 17, 31), STEEL[2])
    fill(blade, (0, 0, 1, 31), STEEL[5])
    fill(blade, (30, 0, 31, 31), STEEL[5])
    save(blade, "blade")
    edge = canvas(16)
    gradient(edge, (0, 0, 15, 15), [STEEL[5], STEEL[4]], axis="y")
    save(edge, "edge")

    guard = canvas(16, fill=GOLD[3])
    bevel(guard, (0, 0, 15, 15), GOLD[5], GOLD[1])
    fill(guard, (3, 6, 12, 9), GOLD[2])
    save(guard, "guard")

    grip = canvas(16, fill=LEATHER[2])
    for y in range(0, 16, 3):
        fill(grip, (0, y, 15, y), LEATHER[0])
        fill(grip, (0, y + 1, 15, y + 1), LEATHER[3])
    grain(grip, (0, 0, 15, 15), [LEATHER[1]], axis="x", density=0.3, seed=4)
    save(grip, "grip")

    gem = art([
        "....aa....",
        "..abbcca..",
        ".abbccdda.",
        "abbccdddda",
        "abccddddea",
        "acccddddea",
        ".acddddea.",
        "..acdeea..",
        "....aa....",
        "..........",
    ], {"a": "#5a0d1a", "b": "#ff9aa2", "c": "#ff4a5e", "d": "#d9163a", "e": "#8e0c26"})
    big = canvas(16)
    big.paste(gem.resize((16, 16)), (0, 0))
    save(big, "gem")


def blade() -> list[dict]:
    parts = []
    # Tapering blade: stacked sections, each a little narrower, then a diamond tip.
    widths = [(1.5, 8.0, 5.2), (8.0, 15.0, 4.8), (15.0, 22.0, 4.2), (22.0, 27.0, 3.4)]
    for y0, y1, w in widths:
        parts.append(box((8 - w / 2, y0, 7.3), (8 + w / 2, y1, 8.7), "blade", uv="full",
                         faces={"up": "edge", "down": "edge"}))
    tip = box((8 - 2.0, 25.2, 7.35), (8 + 2.0, 29.2, 8.65), "blade", uv="full")
    parts.append(turn(tip, 45, "z", (8, 27.2, 8)))
    return parts


def hilt() -> list[dict]:
    parts = [box((3.0, -0.5, 6.8), (13.0, 1.5, 9.2), "guard")]          # crossguard
    wing = box((1.0, 0.2, 7.0), (3.4, 2.8, 9.0), "guard")                  # swept guard tips
    parts += [turn(wing, 30, "z", (3.0, 0.5, 8))]
    parts += mirror(parts[-1], "x", 8)
    parts += prism((8, -5.5, 8), 1.1, 10.0, "grip", sides=8)                # round wrapped grip
    parts += prism((8, -11.5, 8), 1.7, 2.4, "guard", sides=8)               # pommel collar
    parts.append(box((6.6, -14.2, 6.6), (9.4, -11.4, 9.4), "gem", uv="full", glow=12))
    return parts


def models() -> dict:
    parts = blade() + hilt()
    return {"main": model(parts, display("sword", parts, grip=(8, -5.0, 8)))}
