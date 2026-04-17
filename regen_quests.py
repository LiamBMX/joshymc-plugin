import openpyxl, re

EXCEL = 'C:/Users/liama/Downloads/Quest_Tracker.xlsx'
wb = openpyxl.load_workbook(EXCEL)

SHEET_MAP = {
    'Mining': 'MINING', 'Slaying': 'COMBAT', 'Exploring': 'EXPLORATION',
    'Farming': 'FARMING', 'Fishing': 'FISHING', 'Time Played': 'TIME_PLAYED',
}
DIFF_MAP = {'Easy': 'EASY', 'Medium': 'MEDIUM', 'Hard': 'HARD', 'Expert': 'LEGENDARY', 'Extreme': 'LEGENDARY'}
PREFIX_MAP = {'Mining': 'mining', 'Slaying': 'slaying', 'Exploring': 'exploring', 'Farming': 'farming', 'Fishing': 'fishing', 'Time Played': 'timeplayed'}

def parse_number(text):
    nums = re.findall(r'[\d,]+\.?\d*', text.replace(',', ''))
    return int(float(nums[0])) if nums else 1

def parse_time_played_seconds(obj):
    days = hours = 0
    m = re.search(r'(\d+)\s*d', obj)
    if m: days = int(m.group(1))
    m = re.search(r'(\d+)\s*h', obj)
    if m: hours = int(m.group(1))
    return days * 86400 + hours * 3600

def parse_objective(sheet, obj):
    ol = obj.lower()
    if sheet == 'Fishing': return 'CATCH_FISH', '', parse_number(obj)
    if sheet == 'Time Played': return 'TIME_PLAYED', '', parse_time_played_seconds(obj)
    if 'excavate' in ol: return 'BREAK_BLOCK', '', parse_number(obj)
    if 'walk' in ol: return 'WALK_DISTANCE', '', parse_number(obj)
    if 'player' in ol: return 'KILL_PLAYER', '', parse_number(obj)
    if 'boss' in ol: return 'KILL_MOB', 'WARDEN', parse_number(obj)
    mining = {'stone':'STONE','coal':'COAL_ORE','copper ore':'COPPER_ORE','iron ore':'IRON_ORE','gold ore':'GOLD_ORE','lapis ore':'LAPIS_ORE','redstone ore':'REDSTONE_ORE','diamond':'DIAMOND_ORE','obsidian':'OBSIDIAN','ancient debris':'ANCIENT_DEBRIS'}
    for k,v in mining.items():
        if k in ol and ('mine' in ol or 'extract' in ol): return 'BREAK_BLOCK', v, parse_number(obj)
    if 'amethyst' in ol: return 'BREAK_BLOCK', 'AMETHYST_CLUSTER', parse_number(obj)
    if 'gravel' in ol: return 'BREAK_BLOCK', 'GRAVEL', parse_number(obj)
    mobs = {'pigs':'PIG','cows':'COW','sheep':'SHEEP','chickens':'CHICKEN','rabbits':'RABBIT','zombies':'ZOMBIE','skeletons':'SKELETON','creepers':'CREEPER','spiders':'SPIDER','drowned':'DROWNED','endermen':'ENDERMAN','witches':'WITCH','blazes':'BLAZE','husks':'HUSK','strays':'STRAY','elder guardians':'ELDER_GUARDIAN','withers':'WITHER','ender dragons':'ENDER_DRAGON','wardens':'WARDEN','raid captains':'PILLAGER'}
    for k,v in mobs.items():
        if k in ol: return 'KILL_MOB', v, parse_number(obj)
    farm = {'wheat':('HARVEST_CROP','WHEAT'),'carrots':('HARVEST_CROP','CARROTS'),'seeds':('PLACE_BLOCK',''),'melons':('HARVEST_CROP','MELON'),'pumpkins':('HARVEST_CROP','PUMPKIN'),'apples':('BREAK_BLOCK',''),'potions':('CRAFT_ITEM',''),'mushrooms':('BREAK_BLOCK','BROWN_MUSHROOM'),'cocoa':('HARVEST_CROP','COCOA'),'kelp':('HARVEST_CROP','KELP'),'sweet berries':('HARVEST_CROP','SWEET_BERRY_BUSH'),'nether wart':('HARVEST_CROP','NETHER_WART'),'chorus':('BREAK_BLOCK','CHORUS_PLANT'),'bamboo':('BREAK_BLOCK','BAMBOO'),'sugar cane':('HARVEST_CROP','SUGAR_CANE')}
    for k,(t,tgt) in farm.items():
        if k in ol: return t, tgt, parse_number(obj)
    return 'BREAK_BLOCK', '', parse_number(obj)

def reward(diff, idx, total):
    t = idx / max(1, total - 1) if total > 1 else 0.5
    if diff == 'EASY': return int(5000+t*10000), int(50+t*100)
    elif diff == 'MEDIUM': return int(20000+t*60000), int(200+t*300)
    elif diff == 'HARD': return int(100000+t*400000), int(500+t*1500)
    else: return int(500000+t*4500000), int(2000+t*8000)

quests = {}
for sheet_name, category in SHEET_MAP.items():
    ws = wb[sheet_name]
    prefix = PREFIX_MAP[sheet_name]
    diff_counts, rows = {}, []
    for row in ws.iter_rows(min_row=3, values_only=True):
        if row[0] is None: break
        diff = 'EASY'
        for k,v in DIFF_MAP.items():
            if k in str(row[3]): diff = v; break
        diff_counts[diff] = diff_counts.get(diff, 0) + 1
        rows.append((str(row[0]), str(row[1]), str(row[2]), diff))

    diff_idx = {}
    for num, name, objective, diff in rows:
        idx = diff_idx.get(diff, 0); diff_idx[diff] = idx + 1
        qtype, target, amount = parse_objective(sheet_name, objective)
        money, xp = reward(diff, idx, diff_counts[diff])
        qid = f'{prefix}_{int(num):03d}'
        quests[qid] = dict(name=name, description=objective, category=category, difficulty=diff, type=qtype, target=target, amount=amount, money=money, xp=xp)

output = 'quests:\n'
for qid, q in quests.items():
    output += f'  {qid}:\n    name: "{q["name"]}"\n    description: "{q["description"]}"\n    category: {q["category"]}\n    difficulty: {q["difficulty"]}\n    type: {q["type"]}\n    target: "{q["target"]}"\n    amount: {q["amount"]}\n    rewards:\n      money: {q["money"]}\n      xp: {q["xp"]}\n      items: []\n    prerequisite: null\n'

for p in ['src/main/resources/quests.yml', 'run/plugins/Joshymc/quests.yml']:
    with open(p, 'w', encoding='utf-8') as f: f.write(output)

print(f'{len(quests)} quests')
cats = {}
for q in quests.values(): cats[q['category']] = cats.get(q['category'], 0) + 1
for c, n in sorted(cats.items()): print(f'  {c}: {n}')
