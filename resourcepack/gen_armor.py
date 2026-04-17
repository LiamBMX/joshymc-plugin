from PIL import Image

# === COLORS ===
PINK = (255, 182, 193, 255)
DARK_PINK = (210, 130, 145, 255)
DARKER_PINK = (180, 105, 120, 255)
LIGHT_PINK = (255, 215, 225, 255)
WHITE = (255, 248, 250, 255)
CREAM = (255, 235, 238, 255)
NOSE = (255, 120, 140, 255)
EYE = (30, 15, 20, 255)
EYE_SHINE = (255, 255, 255, 255)
TRANS = (0, 0, 0, 0)


def fill(img, x1, y1, x2, y2, color):
    for x in range(x1, x2):
        for y in range(y1, y2):
            img.putpixel((x, y), color)


def shade_bottom(img, x1, y1, x2, y2, color):
    """Add darker row at bottom of a region"""
    for x in range(x1, x2):
        img.putpixel((x, y2 - 1), color)


def shade_right(img, x1, y1, x2, y2, color):
    """Add darker column at right of a region"""
    for y in range(y1, y2):
        img.putpixel((x2 - 1, y), color)


# ============================================================
# LAYER 1: humanoid (helmet, chestplate, arms, boots)
# ============================================================
L1 = Image.new("RGBA", (64, 32), TRANS)

# --- HEAD / HELMET ---
# Top (8,0)-(16,8) - pink with ear bumps
fill(L1, 8, 0, 16, 8, PINK)
fill(L1, 9, 0, 11, 2, LIGHT_PINK)   # left ear base on top
fill(L1, 13, 0, 15, 2, LIGHT_PINK)  # right ear base on top
shade_bottom(L1, 8, 0, 16, 8, DARK_PINK)

# Bottom (16,0)-(24,8)
fill(L1, 16, 0, 24, 8, DARKER_PINK)

# Front face (8,8)-(16,16) - bunny face!
fill(L1, 8, 8, 16, 16, PINK)
# White muzzle area
fill(L1, 10, 12, 14, 15, CREAM)
# Eyes - big cute bunny eyes
L1.putpixel((10, 10), EYE)
L1.putpixel((10, 11), EYE)
L1.putpixel((13, 10), EYE)
L1.putpixel((13, 11), EYE)
# Eye shine
L1.putpixel((10, 10), EYE_SHINE)
L1.putpixel((13, 10), EYE_SHINE)
# Nose - pink triangle
L1.putpixel((11, 12), NOSE)
L1.putpixel((12, 12), NOSE)
# Mouth
L1.putpixel((11, 13), DARKER_PINK)
L1.putpixel((12, 13), DARKER_PINK)
# Blush spots
L1.putpixel((9, 12), NOSE)
L1.putpixel((14, 12), NOSE)
# Ear tops poking above forehead
fill(L1, 9, 8, 11, 10, LIGHT_PINK)
L1.putpixel((10, 8), NOSE)  # inner ear
L1.putpixel((10, 9), NOSE)
fill(L1, 13, 8, 15, 10, LIGHT_PINK)
L1.putpixel((14, 8), NOSE)  # inner ear
L1.putpixel((14, 9), NOSE)
# Shading
shade_bottom(L1, 8, 8, 16, 16, DARK_PINK)
shade_right(L1, 8, 8, 16, 16, DARK_PINK)

# Back (24,8)-(32,16)
fill(L1, 24, 8, 32, 16, PINK)
fill(L1, 26, 8, 28, 10, LIGHT_PINK)  # ear backs
shade_bottom(L1, 24, 8, 32, 16, DARK_PINK)
shade_right(L1, 24, 8, 32, 16, DARK_PINK)

# Right side (0,8)-(8,16)
fill(L1, 0, 8, 8, 16, PINK)
shade_bottom(L1, 0, 8, 8, 16, DARK_PINK)

# Left side (16,8)-(24,16)
fill(L1, 16, 8, 24, 16, PINK)
shade_bottom(L1, 16, 8, 24, 16, DARK_PINK)

# --- BODY / CHESTPLATE ---
# Top (20,16)-(28,20)
fill(L1, 20, 16, 28, 20, PINK)
# Bottom (28,16)-(36,20)
fill(L1, 28, 16, 36, 20, DARKER_PINK)

# Front (20,20)-(28,32) - white belly
fill(L1, 20, 20, 28, 32, PINK)
fill(L1, 22, 21, 26, 30, CREAM)  # white belly oval
fill(L1, 23, 20, 25, 31, CREAM)  # extend belly
shade_bottom(L1, 20, 20, 28, 32, DARK_PINK)
shade_right(L1, 20, 20, 28, 32, DARK_PINK)

# Back (32,20)-(40,32)
fill(L1, 32, 20, 40, 32, PINK)
# Fluffy cottontail
fill(L1, 35, 24, 38, 27, WHITE)
L1.putpixel((34, 25), CREAM)
L1.putpixel((38, 25), CREAM)
L1.putpixel((36, 23), CREAM)
L1.putpixel((36, 27), CREAM)
shade_bottom(L1, 32, 20, 40, 32, DARK_PINK)
shade_right(L1, 32, 20, 40, 32, DARK_PINK)

# Right side (16,20)-(20,32)
fill(L1, 16, 20, 20, 32, PINK)
shade_right(L1, 16, 20, 20, 32, DARK_PINK)
# Left side (28,20)-(32,32)
fill(L1, 28, 20, 32, 32, PINK)
shade_right(L1, 28, 20, 32, 32, DARK_PINK)

# --- RIGHT ARM ---
# Top (44,16)-(48,20)
fill(L1, 44, 16, 48, 20, PINK)
# Bottom (48,16)-(52,20)
fill(L1, 48, 16, 52, 20, DARKER_PINK)

# Arm sides
fill(L1, 40, 20, 44, 32, PINK)   # outer
fill(L1, 44, 20, 48, 32, PINK)   # front
fill(L1, 48, 20, 52, 32, PINK)   # inner
fill(L1, 52, 20, 56, 32, PINK)   # back

# Paw pads at bottom of front
fill(L1, 45, 29, 47, 31, CREAM)
L1.putpixel((45, 31), NOSE)
L1.putpixel((46, 31), NOSE)
shade_bottom(L1, 44, 20, 48, 32, DARK_PINK)
shade_right(L1, 44, 20, 48, 32, DARK_PINK)
shade_bottom(L1, 40, 20, 44, 32, DARK_PINK)

# --- RIGHT LEG / BOOTS ---
# Top (4,16)-(8,20)
fill(L1, 4, 16, 8, 20, PINK)
# Bottom (8,16)-(12,20) - paw sole
fill(L1, 8, 16, 12, 20, DARKER_PINK)
fill(L1, 9, 17, 11, 19, CREAM)  # paw pads on sole

# Leg sides
fill(L1, 0, 20, 4, 32, PINK)    # outer
fill(L1, 4, 20, 8, 32, PINK)    # front
fill(L1, 8, 20, 12, 32, PINK)   # inner
fill(L1, 12, 20, 16, 32, PINK)  # back

# Paw detail on front
fill(L1, 5, 29, 7, 31, CREAM)
L1.putpixel((5, 31), NOSE)
L1.putpixel((6, 31), NOSE)
# Fluffy cuff at top
fill(L1, 4, 20, 8, 22, WHITE)
fill(L1, 0, 20, 4, 22, WHITE)
fill(L1, 8, 20, 12, 22, WHITE)
fill(L1, 12, 20, 16, 22, WHITE)
shade_bottom(L1, 4, 20, 8, 32, DARK_PINK)

L1.save("assets/joshymc/textures/entity/equipment/humanoid/bunny.png")
print("Created humanoid/bunny.png")

# ============================================================
# LAYER 2: humanoid_leggings
# ============================================================
L2 = Image.new("RGBA", (64, 32), TRANS)

# Body/waist
fill(L2, 20, 16, 28, 20, PINK)
fill(L2, 28, 16, 36, 20, DARKER_PINK)
fill(L2, 16, 20, 20, 32, PINK)
fill(L2, 20, 20, 28, 32, PINK)
fill(L2, 28, 20, 32, 32, PINK)
fill(L2, 32, 20, 40, 32, PINK)

# Belt detail
fill(L2, 20, 20, 28, 22, DARK_PINK)
fill(L2, 32, 20, 40, 22, DARK_PINK)

# Tail on back
fill(L2, 35, 22, 38, 25, WHITE)
L2.putpixel((34, 23), CREAM)
L2.putpixel((38, 23), CREAM)

shade_bottom(L2, 20, 20, 28, 32, DARK_PINK)
shade_bottom(L2, 32, 20, 40, 32, DARK_PINK)

# Right leg
fill(L2, 4, 16, 8, 20, PINK)
fill(L2, 8, 16, 12, 20, DARKER_PINK)
fill(L2, 0, 20, 4, 32, PINK)
fill(L2, 4, 20, 8, 32, PINK)
fill(L2, 8, 20, 12, 32, PINK)
fill(L2, 12, 20, 16, 32, PINK)
shade_bottom(L2, 4, 20, 8, 32, DARK_PINK)
shade_right(L2, 4, 20, 8, 32, DARK_PINK)
shade_bottom(L2, 0, 20, 4, 32, DARK_PINK)

L2.save("assets/joshymc/textures/entity/equipment/humanoid_leggings/bunny.png")
print("Created humanoid_leggings/bunny.png")

# ============================================================
# ITEM ICONS — vanilla armor silhouettes, bunny pink
# ============================================================

# --- Helmet (vanilla-style shape with bunny ears) ---
h = Image.new("RGBA", (16, 16), TRANS)
# Main helmet dome
fill(h, 3, 5, 13, 12, PINK)
fill(h, 4, 4, 12, 5, PINK)
fill(h, 5, 3, 11, 4, PINK)
# Face cutout
fill(h, 5, 8, 11, 12, TRANS)
# Visor rim
fill(h, 4, 7, 12, 8, DARK_PINK)
# Shading
for x in range(3, 13):
    if h.getpixel((x, 11)) == PINK:
        h.putpixel((x, 11), DARK_PINK)
for y in range(4, 12):
    if h.getpixel((12, y)) == PINK:
        h.putpixel((12, y), DARK_PINK)
# Highlight
for x in range(5, 10):
    if h.getpixel((x, 4)) == PINK:
        h.putpixel((x, 4), LIGHT_PINK)
# Bunny ears
fill(h, 4, 0, 6, 4, PINK)
fill(h, 10, 0, 12, 4, PINK)
# Inner ears
fill(h, 4, 1, 6, 3, NOSE)
fill(h, 10, 1, 12, 3, NOSE)
# Ear tips
h.putpixel((5, 0), LIGHT_PINK)
h.putpixel((10, 0), LIGHT_PINK)
h.save("assets/joshymc/textures/item/bunny_helmet.png")
print("Created bunny_helmet.png")

# --- Chestplate (vanilla T-shape with belly) ---
c = Image.new("RGBA", (16, 16), TRANS)
# Shoulders
fill(c, 1, 2, 15, 5, PINK)
# Body
fill(c, 4, 5, 12, 15, PINK)
# Sleeves
fill(c, 1, 5, 4, 12, PINK)
fill(c, 12, 5, 15, 12, PINK)
# Neck cutout
fill(c, 6, 2, 10, 4, TRANS)
# White belly
fill(c, 6, 6, 10, 13, CREAM)
# Shading
for x in range(4, 12):
    c.putpixel((x, 14), DARK_PINK)
for y in range(5, 15):
    if c.getpixel((11, y)) == PINK:
        c.putpixel((11, y), DARK_PINK)
for y in range(5, 12):
    c.putpixel((14, y), DARK_PINK)
# Highlight on shoulders
for x in range(2, 6):
    c.putpixel((x, 2), LIGHT_PINK)
for x in range(10, 14):
    c.putpixel((x, 2), LIGHT_PINK)
c.save("assets/joshymc/textures/item/bunny_chestplate.png")
print("Created bunny_chestplate.png")

# --- Leggings (vanilla pants shape with tail) ---
lg = Image.new("RGBA", (16, 16), TRANS)
# Waistband
fill(lg, 4, 1, 12, 4, PINK)
fill(lg, 4, 1, 12, 2, DARK_PINK)  # belt
# Left leg
fill(lg, 4, 4, 7, 15, PINK)
# Right leg
fill(lg, 9, 4, 12, 15, PINK)
# Inner thigh shading
for y in range(4, 15):
    lg.putpixel((7, y), DARK_PINK) if lg.getpixel((7, y)) != TRANS else None
    lg.putpixel((9, y), DARK_PINK) if lg.getpixel((9, y)) != TRANS else None
# Bottom shading
for x in range(4, 7):
    lg.putpixel((x, 14), DARK_PINK)
for x in range(9, 12):
    lg.putpixel((x, 14), DARK_PINK)
# Fluffy tail
lg.putpixel((7, 2), WHITE)
lg.putpixel((8, 2), WHITE)
lg.putpixel((7, 3), WHITE)
lg.putpixel((8, 3), WHITE)
lg.putpixel((8, 1), WHITE)
lg.save("assets/joshymc/textures/item/bunny_leggings.png")
print("Created bunny_leggings.png")

# --- Boots (vanilla boot shape with paw pads) ---
b = Image.new("RGBA", (16, 16), TRANS)
# Left boot
fill(b, 2, 5, 7, 12, PINK)
fill(b, 2, 12, 8, 15, PINK)  # foot extends forward
# Right boot
fill(b, 9, 5, 14, 12, PINK)
fill(b, 8, 12, 14, 15, PINK)
# Fluffy cuffs
fill(b, 2, 5, 7, 7, WHITE)
fill(b, 9, 5, 14, 7, WHITE)
# Toe highlight
fill(b, 2, 12, 4, 13, LIGHT_PINK)
fill(b, 12, 12, 14, 13, LIGHT_PINK)
# Paw pad on sole
fill(b, 4, 14, 6, 15, CREAM)
fill(b, 10, 14, 12, 15, CREAM)
# Shading
for y in range(7, 15):
    b.putpixel((2, y), DARK_PINK)
    b.putpixel((9, y), DARK_PINK)
for x in range(2, 8):
    b.putpixel((x, 14), DARK_PINK)
for x in range(8, 14):
    b.putpixel((x, 14), DARK_PINK)
b.save("assets/joshymc/textures/item/bunny_boots.png")
print("Created bunny_boots.png")

print("\nAll done!")
