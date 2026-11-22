"""Lifesteal Heart (issue #1607): the physical Heart item, drawn as the classic red
health heart from above the hotbar.

A flat 32x32 sprite (the issue asks for a simple pixel heart: no 3D model, no
animation). The heart is hand-painted on a 15 x 13 grid and every grid pixel is drawn
2 x 2, so its outline steps match vanilla's chunky hotbar heart; only the glint's white
core uses single pixels. Light comes from the top left: a pink-white glint on the left
lobe, a warm rim along the upper edges, and crimson shadows down the lower-right side.
"""
from art.kit import canvas, rgba, save, sprite

ID = "lifesteal_heart"
NAME = "Lifesteal Heart"
KIND = "item"
COUNTERPART = "item/nether_star"

# Symmetric outline about column 7; the shading is lit from the top left.
GRID = [
    "..OOOO...OOOO..",
    ".OLLLBO.OLLLBO.",
    "OLPPRRBOLRRRRBO",
    "OLPRRRRRRRRRRSO",
    "OLRRRRRRRRRRMSO",
    "OBRRRRRRRRRMBSO",
    ".OBRRRRRRRMBSO.",
    "..OBRRRRRMBSO..",
    "...OBRRRMBSO...",
    "....OBRMBSO....",
    ".....OBSSO.....",
    "......OSO......",
    ".......O.......",
]
SCALE = 2
OFFSET = (1, 3)  # 30 x 26 px, centred in the 32 x 32 texture

PALETTE = {
    "O": "#2b0610",   # outline: near-black, warmed toward red
    "S": "#8e0d24",   # shadow, hue-shifted toward crimson
    "B": "#c8161c",   # base red at the edges
    "M": "#de1f1e",   # mid red, turning into the shadow side
    "R": "#f22a22",   # bright red body
    "L": "#ff6e52",   # warm rim light
    "P": "#ffb2a4",   # glint
}
WHITE = "#fff6f0"


def heart():
    image = canvas(32)
    px = image.load()
    for gy, row in enumerate(GRID):
        for gx, ch in enumerate(row):
            if ch == ".":
                continue
            for dy in range(SCALE):
                for dx in range(SCALE):
                    px[OFFSET[0] + gx * SCALE + dx, OFFSET[1] + gy * SCALE + dy] = rgba(PALETTE[ch])
    # White core in the glint (the one highlight, like vanilla's).
    for x, y in [(5, 7), (6, 7), (5, 8)]:
        px[x, y] = rgba(WHITE)
    return image


def textures() -> None:
    save(heart(), "heart")


def models() -> dict:
    return {"main": sprite("heart")}
