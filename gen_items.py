from PIL import Image, ImageDraw
import os, random

tex_dir = 'resourcepack/assets/joshymc/textures/item'
os.makedirs(tex_dir, exist_ok=True)

def clamp(v): return max(0, min(255, int(v)))

def gen_gem(fid, color, accent):
    """Generate a gem/shard shaped item"""
    img = Image.new('RGBA', (16, 16), (0,0,0,0))
    shape = [
        '................','................','......##........',
        '.....####.......','....######......','...########.....',
        '...########.....','....######......','....######......',
        '.....####.......','......##........','................',
        '................','................','................','................',
    ]
    rng = random.Random(fid)
    for y in range(16):
        for x in range(16):
            if shape[y][x] == '#':
                t = y / 16.0
                r = clamp(color[0]*(1-t) + accent[0]*t + rng.randint(-10,10))
                g = clamp(color[1]*(1-t) + accent[1]*t + rng.randint(-10,10))
                b = clamp(color[2]*(1-t) + accent[2]*t + rng.randint(-10,10))
                img.putpixel((x,y), (r,g,b,255))
    img.save(f'{tex_dir}/{fid}.png')

def gen_sword(fid, blade_color, handle_color):
    """Generate a sword shaped item"""
    img = Image.new('RGBA', (16, 16), (0,0,0,0))
    shape = [
        '..............##','..............##','.............##.',
        '............##..','...........##...','..........##....',
        '.........##.....','........##......','.......##.......',
        '......##........','.#...##.........','..#.##..........',
        '...##...........','..##............','..#.............','................',
    ]
    rng = random.Random(fid)
    for y in range(16):
        for x in range(16):
            if shape[y][x] == '#':
                if y >= 10:
                    c = handle_color
                else:
                    c = blade_color
                r = clamp(c[0] + rng.randint(-15,15))
                g = clamp(c[1] + rng.randint(-15,15))
                b = clamp(c[2] + rng.randint(-15,15))
                img.putpixel((x,y), (r,g,b,255))
    img.save(f'{tex_dir}/{fid}.png')

def gen_tool(fid, head_color, handle_color):
    """Generate a pickaxe/axe/shovel shaped item"""
    img = Image.new('RGBA', (16, 16), (0,0,0,0))
    shape = [
        '....#####.......','...#######......','..#########.....',
        '........##......','.........##.....','..........##....',
        '...........##...','............##..','................',
        '................','................','................',
        '................','................','................','................',
    ]
    rng = random.Random(fid)
    for y in range(16):
        for x in range(16):
            if shape[y][x] == '#':
                c = head_color if y < 4 else handle_color
                r = clamp(c[0] + rng.randint(-12,12))
                g = clamp(c[1] + rng.randint(-12,12))
                b = clamp(c[2] + rng.randint(-12,12))
                img.putpixel((x,y), (r,g,b,255))
    img.save(f'{tex_dir}/{fid}.png')

def gen_armor(fid, color):
    """Generate an armor piece item"""
    img = Image.new('RGBA', (16, 16), (0,0,0,0))
    if 'helmet' in fid:
        shape = [
            '................','....######......','...########.....',
            '..##########....','..##########....','..##.####.##....',
            '................','................','................',
            '................','................','................',
            '................','................','................','................',
        ]
    elif 'chestplate' in fid:
        shape = [
            '..##......##....','..##########....','..##########....',
            '...########.....','...########.....','...########.....',
            '...########.....','....######......','................',
            '................','................','................',
            '................','................','................','................',
        ]
    elif 'leggings' in fid:
        shape = [
            '...########.....','...########.....','...########.....',
            '...##....##.....','...##....##.....','...##....##.....',
            '...##....##.....','................','................',
            '................','................','................',
            '................','................','................','................',
        ]
    else:  # boots
        shape = [
            '................','...##....##.....','...##....##.....',
            '..###...###.....','..###...###.....','................',
            '................','................','................',
            '................','................','................',
            '................','................','................','................',
        ]
    rng = random.Random(fid)
    for y in range(16):
        for x in range(16):
            if shape[y][x] == '#':
                t = y / 8.0
                r = clamp(color[0] + rng.randint(-20,20) - int(t*15))
                g = clamp(color[1] + rng.randint(-20,20) - int(t*15))
                b = clamp(color[2] + rng.randint(-20,20) - int(t*15))
                img.putpixel((x,y), (r,g,b,255))
    img.save(f'{tex_dir}/{fid}.png')

def gen_potion(fid, liquid_color):
    """Generate a potion/brew item"""
    img = Image.new('RGBA', (16, 16), (0,0,0,0))
    shape = [
        '......##........','......##........','.....####.......',
        '......##........','....######......','...########.....',
        '...########.....','...########.....','...########.....',
        '....######......','................','................',
        '................','................','................','................',
    ]
    rng = random.Random(fid)
    for y in range(16):
        for x in range(16):
            if shape[y][x] == '#':
                if y < 3:
                    c = (180, 180, 180)
                else:
                    c = liquid_color
                r = clamp(c[0] + rng.randint(-10,10))
                g = clamp(c[1] + rng.randint(-10,10))
                b = clamp(c[2] + rng.randint(-10,10))
                img.putpixel((x,y), (r,g,b,255))
    img.save(f'{tex_dir}/{fid}.png')

def gen_paper(fid, color):
    """Generate a paper/scroll/book item"""
    img = Image.new('RGBA', (16, 16), (0,0,0,0))
    shape = [
        '....######......','...########.....','...########.....',
        '...########.....','...########.....','...########.....',
        '...########.....','...########.....','...########.....',
        '....######......','................','................',
        '................','................','................','................',
    ]
    rng = random.Random(fid)
    for y in range(16):
        for x in range(16):
            if shape[y][x] == '#':
                r = clamp(color[0] + rng.randint(-8,8))
                g = clamp(color[1] + rng.randint(-8,8))
                b = clamp(color[2] + rng.randint(-8,8))
                img.putpixel((x,y), (r,g,b,255))
    img.save(f'{tex_dir}/{fid}.png')

# Generate all textures
# Crafting materials
gen_gem('void_shard', (120, 0, 180), (80, 0, 140))
gen_gem('soul_fragment', (0, 170, 170), (0, 120, 140))
gen_gem('inferno_core', (255, 140, 0), (200, 80, 0))
gen_gem('crystal_essence', (80, 220, 255), (40, 180, 220))
gen_gem('ancient_rune', (255, 200, 50), (200, 160, 30))
gen_gem('enchanted_dust', (220, 120, 255), (180, 80, 220))

# Weapons
gen_sword('void_blade', (120, 0, 180), (80, 60, 40))
gen_sword('soul_scythe', (0, 170, 170), (80, 60, 40))
gen_sword('inferno_axe', (255, 140, 0), (80, 60, 40))
gen_sword('crystal_mace', (80, 220, 255), (120, 100, 80))
gen_paper('carrot_launcher', (255, 160, 40))

# Tools
gen_tool('auto_miner', (80, 220, 255), (140, 100, 60))
gen_tool('farmers_sickle', (80, 220, 80), (140, 100, 60))
gen_tool('lumberjacks_axe', (255, 180, 60), (140, 100, 60))
gen_tool('excavator', (255, 240, 80), (140, 100, 60))
gen_sword('magnet_wand', (220, 60, 60), (100, 100, 100))

# Armor sets
for piece in ['helmet', 'chestplate', 'leggings', 'boots']:
    gen_armor(f'void_{piece}', (120, 0, 180))
    gen_armor(f'inferno_{piece}', (255, 140, 0))
    gen_armor(f'crystal_{piece}', (80, 220, 255))
    gen_armor(f'soul_{piece}', (0, 170, 170))

# Consumables
gen_paper('money_pouch_small', (120, 200, 120))
gen_paper('money_pouch_medium', (255, 240, 120))
gen_paper('money_pouch_large', (255, 180, 50))
gen_paper('xp_tome', (120, 255, 120))
gen_gem('speed_apple', (80, 220, 255), (60, 180, 220))
gen_gem('strength_apple', (255, 80, 80), (200, 40, 40))
gen_potion('giants_brew', (255, 180, 50))
gen_potion('miners_brew', (80, 200, 255))
gen_gem('wardens_heart', (20, 40, 50), (10, 60, 70))

# Legendary
gen_armor('blaze_kings_crown', (255, 200, 50))
gen_armor('phantom_cloak', (40, 40, 50))
gen_sword('poseidons_trident', (40, 120, 200), (40, 80, 140))
gen_paper('claim_block_token', (120, 200, 120))
gen_paper('skill_tome_mining', (80, 200, 255))
gen_paper('skill_tome_farming', (80, 220, 80))

print('Generated 47 custom item textures')
