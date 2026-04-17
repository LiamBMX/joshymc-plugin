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


# ---------------------------------------------------------------------------
# 3. void_bore.png -- ingot shape with void energy veins
# ---------------------------------------------------------------------------
def void_bore(img):
    base = (50, 25, 70)
    dark = (40, 18, 55)
    vein = (180, 80, 255)
    particle = (150, 60, 220, 140)

    # Ingot shape: roughly 10x6, centered
    # rows 5-10, cols 3-12 with beveled corners
    for y in range(5, 11):
        for x in range(4, 13):
            px(img, x, y, base)
    # Bevel: cut corners
    px(img, 4, 5, (0, 0, 0, 0))
    px(img, 12, 5, (0, 0, 0, 0))
    px(img, 4, 10, (0, 0, 0, 0))
    px(img, 12, 10, (0, 0, 0, 0))

    # Top highlight edge
    for x in range(5, 12):
        px(img, x, 5, (65, 35, 85))

    # Bottom shadow edge
    for x in range(5, 12):
        px(img, x, 10, dark)

    # Energy veins running through
    px(img, 6, 6, vein)
    px(img, 7, 7, vein)
    px(img, 8, 7, vein)
    px(img, 9, 8, vein)
    px(img, 10, 7, vein)
    px(img, 5, 8, vein)
    px(img, 6, 9, vein)
    px(img, 10, 9, vein)
    px(img, 11, 8, vein)

    # Particles around
    px(img, 3, 4, particle)
    px(img, 13, 6, particle)
    px(img, 5, 3, particle)
    px(img, 11, 11, particle)
    px(img, 14, 8, (130, 50, 200, 100))


make("void_bore", void_bore)


# ---------------------------------------------------------------------------
# 4. void_bore_5x5.png -- more intense version of void_bore
# ---------------------------------------------------------------------------
def void_bore_5x5(img):
    base = (50, 25, 70)
    dark = (40, 18, 55)
    vein = (220, 100, 255)
    glow = (140, 60, 200, 80)
    particle = (200, 80, 255, 160)

    # Glow aura first (semi-transparent)
    for y in range(4, 12):
        for x in range(3, 14):
            px(img, x, y, glow)

    # Ingot shape: same as void_bore but slightly larger feel
    for y in range(5, 11):
        for x in range(4, 13):
            px(img, x, y, base)
    # Bevel
    px(img, 4, 5, glow)
    px(img, 12, 5, glow)
    px(img, 4, 10, glow)
    px(img, 12, 10, glow)

    # Top highlight
    for x in range(5, 12):
        px(img, x, 5, (70, 40, 95))

    # Bottom shadow
    for x in range(5, 12):
        px(img, x, 10, dark)

    # Intense energy veins - more of them
    px(img, 5, 6, vein)
    px(img, 6, 6, vein)
    px(img, 7, 7, vein)
    px(img, 8, 7, vein)
    px(img, 9, 7, vein)
    px(img, 10, 8, vein)
    px(img, 11, 8, vein)
    px(img, 5, 8, vein)
    px(img, 6, 9, vein)
    px(img, 7, 8, vein)
    px(img, 9, 9, vein)
    px(img, 10, 9, vein)
    px(img, 11, 6, vein)
    px(img, 8, 9, vein)

    # Bright core pixel
    px(img, 8, 7, (255, 150, 255))

    # More particles
    px(img, 2, 4, particle)
    px(img, 14, 5, particle)
    px(img, 3, 11, particle)
    px(img, 13, 10, particle)
    px(img, 5, 2, particle)
    px(img, 11, 12, particle)
    px(img, 1, 7, (180, 70, 240, 100))
    px(img, 14, 7, (180, 70, 240, 100))
    px(img, 8, 3, (200, 100, 255, 120))
    px(img, 8, 12, (200, 100, 255, 120))


make("void_bore_5x5", void_bore_5x5)


# ---------------------------------------------------------------------------
# 5. void_bore_chunk.png -- nether-star shape, most powerful
# ---------------------------------------------------------------------------
def void_bore_chunk(img):
    core_dark = (30, 10, 50)
    mid = (80, 30, 120)
    energy = (200, 100, 255)
    intense = (255, 150, 255)
    ray = (160, 80, 230, 150)
    faint = (120, 50, 180, 80)

    # Star/diamond shape centered at (7,7)-(8,8)
    # Diamond body
    diamond_pixels = []
    cx, cy = 7.5, 7.5
    for y in range(16):
        for x in range(16):
            dx = abs(x - cx)
            dy = abs(y - cy)
            dist = dx + dy  # manhattan distance for diamond
            if dist <= 2.5:
                diamond_pixels.append((x, y, core_dark))
            elif dist <= 3.5:
                diamond_pixels.append((x, y, mid))
            elif dist <= 4.5:
                diamond_pixels.append((x, y, (50, 20, 80)))

    for x, y, c in diamond_pixels:
        px(img, x, y, c)

    # Energy rays radiating outward (4 cardinal + 4 diagonal)
    # Up
    for i in range(1, 5):
        px(img, 7, 7 - 3 - i, ray)
        px(img, 8, 7 - 3 - i, ray)
    # Down
    for i in range(1, 5):
        px(img, 7, 8 + 3 + i, ray)
        px(img, 8, 8 + 3 + i, ray)
    # Left
    for i in range(1, 5):
        px(img, 7 - 3 - i, 7, ray)
        px(img, 7 - 3 - i, 8, ray)
    # Right
    for i in range(1, 5):
        px(img, 8 + 3 + i, 7, ray)
        px(img, 8 + 3 + i, 8, ray)

    # Diagonal faint rays
    for i in range(1, 4):
        px(img, 7 - 2 - i, 7 - 2 - i, faint)
        px(img, 8 + 2 + i, 7 - 2 - i, faint)
        px(img, 7 - 2 - i, 8 + 2 + i, faint)
        px(img, 8 + 2 + i, 8 + 2 + i, faint)

    # Star points (extending the diamond into a star)
    # Top point
    px(img, 7, 3, mid)
    px(img, 8, 3, mid)
    px(img, 7, 2, energy)
    px(img, 8, 2, energy)
    # Bottom point
    px(img, 7, 12, mid)
    px(img, 8, 12, mid)
    px(img, 7, 13, energy)
    px(img, 8, 13, energy)
    # Left point
    px(img, 3, 7, mid)
    px(img, 3, 8, mid)
    px(img, 2, 7, energy)
    px(img, 2, 8, energy)
    # Right point
    px(img, 12, 7, mid)
    px(img, 12, 8, mid)
    px(img, 13, 7, energy)
    px(img, 13, 8, energy)

    # Inner energy ring
    px(img, 7, 5, energy)
    px(img, 8, 5, energy)
    px(img, 7, 10, energy)
    px(img, 8, 10, energy)
    px(img, 5, 7, energy)
    px(img, 5, 8, energy)
    px(img, 10, 7, energy)
    px(img, 10, 8, energy)

    # Bright core
    px(img, 7, 7, intense)
    px(img, 8, 7, intense)
    px(img, 7, 8, intense)
    px(img, 8, 8, intense)

    # White-purple center pixel
    px(img, 7, 7, (255, 220, 255))
    px(img, 8, 8, (255, 220, 255))


make("void_bore_chunk", void_bore_chunk)

print("\nAll 5 textures generated.")
