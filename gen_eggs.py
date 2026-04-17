from PIL import Image

EGG_SHAPE = [
    [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
    [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
    [0,0,0,0,0,0,1,1,1,0,0,0,0,0,0,0],
    [0,0,0,0,0,1,1,1,1,1,0,0,0,0,0,0],
    [0,0,0,0,1,1,1,1,1,1,1,0,0,0,0,0],
    [0,0,0,0,1,1,1,1,1,1,1,0,0,0,0,0],
    [0,0,0,1,1,1,1,1,1,1,1,1,0,0,0,0],
    [0,0,0,1,1,1,1,1,1,1,1,1,0,0,0,0],
    [0,0,0,1,1,1,1,1,1,1,1,1,0,0,0,0],
    [0,0,0,1,1,1,1,1,1,1,1,1,0,0,0,0],
    [0,0,0,0,1,1,1,1,1,1,1,0,0,0,0,0],
    [0,0,0,0,1,1,1,1,1,1,1,0,0,0,0,0],
    [0,0,0,0,0,1,1,1,1,1,0,0,0,0,0,0],
    [0,0,0,0,0,0,1,1,1,0,0,0,0,0,0,0],
    [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
    [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
]
SPOTS = [(4,6), (5,9), (6,5), (7,8), (7,10), (8,4), (9,7), (9,10), (10,5), (10,8), (11,6), (11,9)]
HIGHLIGHTS = [(3,7), (4,5), (4,6), (3,8), (5,5)]
SHADOWS = [(11,9), (11,10), (12,7), (12,8), (13,7), (13,8), (10,10)]

OUT_DIR = r"C:\Users\liama\IdeaProjects\joshymc\resourcepack\assets\joshymc\textures\item"

def make_egg(filename, base, spot, highlight, shadow, overlay_fn):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    # Fill base
    for y in range(16):
        for x in range(16):
            if EGG_SHAPE[y][x]:
                px[x, y] = (*base, 255)
    # Spots
    for (y, x) in SPOTS:
        if 0 <= y < 16 and 0 <= x < 16 and EGG_SHAPE[y][x]:
            px[x, y] = (*spot, 255)
    # Highlights
    for (y, x) in HIGHLIGHTS:
        if 0 <= y < 16 and 0 <= x < 16 and EGG_SHAPE[y][x]:
            px[x, y] = (*highlight, 255)
    # Shadows
    for (y, x) in SHADOWS:
        if 0 <= y < 16 and 0 <= x < 16 and EGG_SHAPE[y][x]:
            px[x, y] = (*shadow, 255)
    # Overlay
    overlay_fn(px)
    import os
    img.save(os.path.join(OUT_DIR, filename))
    print(f"Saved {filename} ({img.size[0]}x{img.size[1]})")

def set_if_egg(px, x, y, color):
    if 0 <= y < 16 and 0 <= x < 16 and EGG_SHAPE[y][x]:
        px[x, y] = (*color, 255)

# 1. Teleport egg - swirling ender-style spiral dots
def teleport_overlay(px):
    spiral = [(6,7), (7,6), (8,8), (7,9)]
    for (y, x) in spiral:
        set_if_egg(px, x, y, (200, 100, 255))

make_egg("teleport_egg.png",
    base=(150,50,220), spot=(120,30,180), highlight=(200,120,255), shadow=(90,20,140),
    overlay_fn=teleport_overlay)

# 2. Levitation egg - small upward arrows
def levitation_overlay(px):
    # Arrow 1 at col 5
    arrows = [
        (5,6), (6,5), (6,7), (7,6),  # arrow 1
        (8,8), (9,7), (9,9), (10,8),  # arrow 2
    ]
    for (y, x) in arrows:
        set_if_egg(px, x, y, (255, 255, 100))

make_egg("levitation_egg.png",
    base=(240,240,180), spot=(220,220,140), highlight=(255,255,220), shadow=(190,190,120),
    overlay_fn=levitation_overlay)

# 3. Knockback egg - impact starburst from center
def knockback_overlay(px):
    center_y, center_x = 7, 7
    lines = [
        (5,5), (5,7), (5,9),
        (7,4), (7,10),
        (9,5), (9,9),
        (10,7),
    ]
    for (y, x) in lines:
        set_if_egg(px, x, y, (255, 220, 150))

make_egg("knockback_egg.png",
    base=(240,140,40), spot=(200,110,20), highlight=(255,190,100), shadow=(170,90,15),
    overlay_fn=knockback_overlay)

# 4. Swap egg - two arrows pointing opposite directions
def swap_overlay(px):
    # Right arrow top area
    right_arrow = [(5,6), (5,7), (5,8), (4,7), (6,7)]
    # Left arrow bottom area
    left_arrow = [(9,6), (9,7), (9,8), (8,7), (10,7)]
    for (y, x) in right_arrow:
        set_if_egg(px, x, y, (180, 255, 200))
    for (y, x) in left_arrow:
        set_if_egg(px, x, y, (180, 255, 200))

make_egg("swap_egg.png",
    base=(50,210,100), spot=(30,170,70), highlight=(120,255,160), shadow=(20,140,50),
    overlay_fn=swap_overlay)

# 5. Lightning egg - bolt down center
def lightning_overlay(px):
    bolt = [(3,7), (4,7), (5,7), (6,6), (7,6), (7,7), (8,7), (9,8), (10,8), (11,7)]
    for (y, x) in bolt:
        set_if_egg(px, x, y, (255, 255, 220))

make_egg("lightning_egg.png",
    base=(255,230,50), spot=(230,200,20), highlight=(255,255,150), shadow=(200,170,10),
    overlay_fn=lightning_overlay)

# 6. Cobweb egg - web cross pattern radiating from center
def cobweb_overlay(px):
    # Horizontal and vertical lines through center, plus diagonals
    web = [
        # vertical
        (4,7), (5,7), (6,7), (8,7), (9,7), (10,7), (11,7),
        # horizontal
        (7,4), (7,5), (7,6), (7,8), (7,9), (7,10),
        # diagonal hints
        (5,5), (9,9), (5,9), (9,5),
    ]
    for (y, x) in web:
        set_if_egg(px, x, y, (245, 245, 245))

make_egg("cobweb_egg.png",
    base=(210,210,210), spot=(180,180,180), highlight=(240,240,240), shadow=(150,150,150),
    overlay_fn=cobweb_overlay)

# 7. Confusion egg - spiral/swirl in center
def confusion_overlay(px):
    spiral = [(6,6), (6,7), (6,8), (7,8), (8,8), (8,7), (8,6), (7,5), (9,6)]
    for (y, x) in spiral:
        set_if_egg(px, x, y, (200, 255, 80))

make_egg("confusion_egg.png",
    base=(130,230,50), spot=(100,190,30), highlight=(180,255,120), shadow=(80,160,20),
    overlay_fn=confusion_overlay)

# 8. Ender egg - eye/circle with cyan pupil
def ender_overlay(px):
    # Eye outline
    eye_ring = [(6,6), (6,7), (6,8), (7,5), (7,9), (8,6), (8,7), (8,8)]
    for (y, x) in eye_ring:
        set_if_egg(px, x, y, (60, 200, 200))
    # Pupil
    pupil = [(7,7), (7,8)]
    for (y, x) in pupil:
        set_if_egg(px, x, y, (100, 255, 255))

make_egg("ender_egg.png",
    base=(30,150,150), spot=(20,120,120), highlight=(80,200,200), shadow=(15,100,100),
    overlay_fn=ender_overlay)

print("Done! All 8 eggs generated.")
