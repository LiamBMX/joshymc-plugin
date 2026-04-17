"""
Generates 16x16 egg textures for the JoshyMC resource pack.
Based on the Minecraft egg shape, recolored for each variant.
Requires: pip install Pillow
"""

from PIL import Image

# Base egg shape mask (16x16) — 1 = egg pixel, 0 = transparent
# Designed to look like the vanilla MC egg silhouette
EGG_SHAPE = [
    #0 1 2 3 4 5 6 7 8 9 A B C D E F
    [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],  # 0
    [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],  # 1
    [0,0,0,0,0,0,1,1,1,0,0,0,0,0,0,0],  # 2
    [0,0,0,0,0,1,1,1,1,1,0,0,0,0,0,0],  # 3
    [0,0,0,0,1,1,1,1,1,1,1,0,0,0,0,0],  # 4
    [0,0,0,0,1,1,1,1,1,1,1,0,0,0,0,0],  # 5
    [0,0,0,1,1,1,1,1,1,1,1,1,0,0,0,0],  # 6
    [0,0,0,1,1,1,1,1,1,1,1,1,0,0,0,0],  # 7
    [0,0,0,1,1,1,1,1,1,1,1,1,0,0,0,0],  # 8
    [0,0,0,1,1,1,1,1,1,1,1,1,0,0,0,0],  # 9
    [0,0,0,0,1,1,1,1,1,1,1,0,0,0,0,0],  # A
    [0,0,0,0,1,1,1,1,1,1,1,0,0,0,0,0],  # B
    [0,0,0,0,0,1,1,1,1,1,0,0,0,0,0,0],  # C
    [0,0,0,0,0,0,1,1,1,0,0,0,0,0,0,0],  # D
    [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],  # E
    [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],  # F
]

# Spot positions on the egg (row, col) — gives that speckled egg look
SPOTS = [(4,6), (5,9), (6,5), (7,8), (7,10), (8,4), (9,7), (9,10), (10,5), (10,8), (11,6), (11,9)]

# Highlight pixels (top-left area for light reflection)
HIGHLIGHTS = [(3,7), (4,5), (4,6), (3,8), (5,5)]

# Shadow pixels (bottom-right for depth)
SHADOWS = [(11,9), (11,10), (12,7), (12,8), (13,7), (13,8), (10,10)]


def make_egg(base_color, spot_color, highlight_color, shadow_color, overlay_fn=None):
    """Create a 16x16 egg image with the given color scheme."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))

    for y in range(16):
        for x in range(16):
            if EGG_SHAPE[y][x]:
                img.putpixel((x, y), base_color)

    # Add spots
    for (sy, sx) in SPOTS:
        if EGG_SHAPE[sy][sx]:
            img.putpixel((sx, sy), spot_color)

    # Add highlights
    for (hy, hx) in HIGHLIGHTS:
        if EGG_SHAPE[hy][hx]:
            img.putpixel((hx, hy), highlight_color)

    # Add shadows
    for (sy, sx) in SHADOWS:
        if EGG_SHAPE[sy][sx]:
            img.putpixel((sx, sy), shadow_color)

    # Apply any special overlay (icons, patterns)
    if overlay_fn:
        overlay_fn(img)

    return img


def tnt_overlay(img):
    """Draw a small TNT icon/stripe on the egg."""
    red = (200, 40, 30, 255)
    dark_red = (150, 25, 20, 255)
    white = (240, 240, 240, 255)
    black = (40, 30, 30, 255)

    # Red band across the middle of the egg
    for x in range(3, 12):
        for y in [7, 8]:
            if EGG_SHAPE[y][x]:
                img.putpixel((x, y), red)
    # Darker edges of band
    for x in [3, 11]:
        for y in [7, 8]:
            if EGG_SHAPE[y][x]:
                img.putpixel((x, y), dark_red)

    # "T" letter on the band (small, 3 pixels wide)
    img.putpixel((6, 7), white)
    img.putpixel((7, 7), white)
    img.putpixel((8, 7), white)
    img.putpixel((7, 8), white)

    # Fuse on top
    img.putpixel((7, 2), black)
    img.putpixel((8, 1), black)
    # Spark
    img.putpixel((9, 0), (255, 200, 50, 255))
    img.putpixel((8, 0), (255, 150, 30, 255))


def ice_overlay(img):
    """Draw ice crystal / frost marks on the egg."""
    ice_light = (200, 230, 255, 255)
    ice_bright = (150, 210, 255, 255)

    # Small snowflake/crystal pattern in center
    # Vertical line
    img.putpixel((7, 6), ice_light)
    img.putpixel((7, 7), ice_bright)
    img.putpixel((7, 8), ice_light)
    img.putpixel((7, 9), ice_light)
    # Horizontal line
    img.putpixel((6, 7), ice_light)
    img.putpixel((8, 7), ice_light)
    # Diagonals
    img.putpixel((6, 6), ice_bright)
    img.putpixel((8, 8), ice_bright)
    img.putpixel((6, 8), ice_bright)
    img.putpixel((8, 6), ice_bright)

    # Frost along edges
    img.putpixel((4, 4), ice_light)
    img.putpixel((5, 10), ice_light)
    img.putpixel((10, 5), ice_light)
    img.putpixel((9, 9), ice_bright)


def blindness_overlay(img):
    """Draw a dark eye / void symbol on the egg."""
    dark = (20, 10, 30, 255)
    purple = (120, 50, 160, 255)

    # Eye shape in center
    # Top arc
    img.putpixel((6, 6), dark)
    img.putpixel((7, 6), dark)
    img.putpixel((8, 6), dark)
    # Middle (pupil)
    img.putpixel((5, 7), dark)
    img.putpixel((6, 7), purple)
    img.putpixel((7, 7), dark)  # pupil center
    img.putpixel((8, 7), purple)
    img.putpixel((9, 7), dark)
    # Bottom arc
    img.putpixel((6, 8), dark)
    img.putpixel((7, 8), dark)
    img.putpixel((8, 8), dark)

    # Dark drips below
    img.putpixel((7, 9), dark)
    img.putpixel((6, 9), purple)
    img.putpixel((7, 10), purple)


def main():
    out = "assets/joshymc/textures/item"

    # 1. Easter Egg — Golden
    easter = make_egg(
        base_color=(230, 190, 60, 255),     # gold
        spot_color=(200, 155, 30, 255),      # darker gold spots
        highlight_color=(255, 230, 120, 255),# bright gold highlight
        shadow_color=(170, 130, 20, 255),    # deep gold shadow
    )
    easter.save(f"{out}/easter_egg.png")
    print("Created easter_egg.png")

    # 2. Explosive Egg — Red/orange with TNT
    explosive = make_egg(
        base_color=(220, 90, 70, 255),       # red-orange
        spot_color=(180, 60, 45, 255),       # darker red spots
        highlight_color=(255, 150, 120, 255),# light salmon highlight
        shadow_color=(140, 45, 30, 255),     # deep red shadow
        overlay_fn=tnt_overlay,
    )
    explosive.save(f"{out}/explosive_egg.png")
    print("Created explosive_egg.png")

    # 3. Freeze Egg — Icy blue
    freeze = make_egg(
        base_color=(140, 200, 235, 255),     # light blue
        spot_color=(100, 170, 220, 255),     # medium blue spots
        highlight_color=(210, 240, 255, 255),# near white-blue highlight
        shadow_color=(80, 140, 190, 255),    # deeper blue shadow
        overlay_fn=ice_overlay,
    )
    freeze.save(f"{out}/freeze_egg.png")
    print("Created freeze_egg.png")

    # 4. Blindness Egg — Dark purple
    blindness = make_egg(
        base_color=(80, 50, 100, 255),       # dark purple
        spot_color=(55, 30, 75, 255),        # deeper purple spots
        highlight_color=(130, 90, 160, 255), # lighter purple highlight
        shadow_color=(40, 20, 55, 255),      # very dark shadow
        overlay_fn=blindness_overlay,
    )
    blindness.save(f"{out}/blindness_egg.png")
    print("Created blindness_egg.png")

    print("\nAll textures generated!")


if __name__ == "__main__":
    main()
