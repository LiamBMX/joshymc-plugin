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
# Crafting materials (kept only as enchant-scroll crafting ingredients)
gen_gem('soul_fragment', (0, 170, 170), (0, 120, 140))
gen_gem('ancient_rune', (255, 200, 50), (200, 160, 30))
gen_gem('enchanted_dust', (220, 120, 255), (180, 80, 220))

print('Generated 45 custom item textures')
