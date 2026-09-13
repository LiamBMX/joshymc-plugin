"""Generate 16x16 RGBA PNG textures for JoshyMC custom items."""
from PIL import Image

OUT = "C:/Users/liama/IdeaProjects/joshymc/resourcepack/assets/joshymc/textures/item"


def make(name, draw_fn):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw_fn(img)
    img.save(f"{OUT}/{name}.png")
    print(f"  wrote {name}.png")


def px(img, x, y, c):
    """Set pixel if in bounds. c is RGB or RGBA tuple."""
    if 0 <= x < 16 and 0 <= y < 16:
        if len(c) == 3:
            c = c + (255,)
        img.putpixel((x, y), c)


# ---------------------------------------------------------------------------
# 1. void_drill.png  -- diamond-pickaxe style, 3-wide head, diagonal
# ---------------------------------------------------------------------------
def void_drill(img):
    # Handle (bottom-left to mid) - diagonal
    handle_dark = (80, 50, 30)
    handle_light = (100, 65, 40)
    # handle runs from ~(3,14) to ~(7,10)
    for i in range(5):
        px(img, 3 + i, 14 - i, handle_dark)
        px(img, 4 + i, 14 - i, handle_light)

    # Grip wrap
    px(img, 4, 13, handle_light)
    px(img, 6, 11, handle_light)

    # Pick head - 3-wide diagonal band (top-right area)
    head = (80, 40, 120)
    accent = (120, 60, 180)
    bright = (180, 100, 255)

    # Main head block - a 3-wide pickaxe head going top-right
    # Top spike
    for i in range(3):
        px(img, 9 + i, 5 - i, head)
        px(img, 10 + i, 5 - i, accent)
    # Middle row of head
    for i in range(4):
        px(img, 8 + i, 7 - i, head)
        px(img, 9 + i, 7 - i, accent)
    # Bottom spike of head
    for i in range(3):
        px(img, 8 + i, 9 - i, head)
        px(img, 7 + i, 9 - i, accent)

    # 3-wide cross-section to suggest 3x3
    px(img, 9, 6, bright)
    px(img, 10, 5, bright)
    px(img, 11, 4, bright)
    px(img, 10, 6, accent)
    px(img, 11, 5, accent)
    px(img, 12, 4, accent)
    px(img, 8, 7, accent)
    px(img, 11, 6, head)
    px(img, 12, 5, head)
    px(img, 13, 4, head)

    # Head connector to handle
    px(img, 8, 9, head)
    px(img, 9, 8, accent)

    # Energy sparks
    spark = (150, 80, 255)
    px(img, 13, 3, spark)
    px(img, 14, 2, (200, 140, 255))
    px(img, 7, 5, spark)
    px(img, 12, 7, (180, 120, 255, 180))


make("void_drill", void_drill)


# ---------------------------------------------------------------------------
# 2. void_drill_5x5.png -- netherite-void, bigger/wider head
# ---------------------------------------------------------------------------
def void_drill_5x5(img):
    handle_dark = (60, 55, 50)
    handle_light = (75, 68, 58)

    # Handle diagonal
    for i in range(4):
        px(img, 3 + i, 14 - i, handle_dark)
        px(img, 4 + i, 14 - i, handle_light)
    px(img, 4, 13, handle_light)
    px(img, 6, 11, handle_light)

    head = (50, 20, 70)
    accent = (90, 40, 130)
    bright = (200, 50, 255)

    # Wider head - 5 pixel wide band to suggest 5x5
    # Top prong
    for i in range(5):
        px(img, 8 + i, 4 - i, head)
        px(img, 9 + i, 4 - i, accent)
    # Upper mid
    for i in range(5):
        px(img, 7 + i, 6 - i, head)
        px(img, 8 + i, 6 - i, accent)
    # Center
    for i in range(5):
        px(img, 7 + i, 8 - i, head)
        px(img, 8 + i, 8 - i, accent)
    # Lower
    for i in range(4):
        px(img, 7 + i, 10 - i, head)
        px(img, 6 + i, 10 - i, accent)
    # Bottom prong
    for i in range(3):
        px(img, 6 + i, 11 - i, head)

    # Connector
    px(img, 7, 10, head)
    px(img, 8, 9, accent)

    # Bright energy highlights on head
    px(img, 10, 4, bright)
    px(img, 12, 2, bright)
    px(img, 9, 6, bright)
    px(img, 11, 5, bright)
    px(img, 13, 3, bright)

    # Energy sparks
    px(img, 14, 1, (230, 100, 255))
    px(img, 15, 0, (255, 150, 255, 180))
    px(img, 6, 4, (200, 80, 255, 160))
    px(img, 13, 6, (220, 80, 255, 150))
    px(img, 5, 6, (180, 60, 255, 120))


make("void_drill_5x5", void_drill_5x5)

print("\nAll 2 textures generated.")
