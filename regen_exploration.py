"""
Rewrite exploring_XXX quests in quests.yml to use WALK_DISTANCE type.

Previous version used VISIT_BIOME capped at 50 (Minecraft 1.21 has ~60 total),
which made quests 50-100 all read "Discover 50 biomes" identically. Walk
distance scales cleanly across all 100 tiers and doesn't repeat.

Scaling: quest 001 = 250 blocks, quest 100 = 50000 blocks, quadratic growth
so early quests are fast and late quests are meaningfully harder.

Original money/xp rewards per quest number are preserved.

Run once: python regen_exploration.py
"""
import re
from pathlib import Path

PATH = Path(__file__).parent / "src" / "main" / "resources" / "quests.yml"

text = PATH.read_text(encoding="utf-8")

def difficulty(n: int) -> str:
    if n <= 25: return "EASY"
    if n <= 50: return "MEDIUM"
    if n <= 75: return "HARD"
    return "LEGENDARY"

def amount_for(n: int) -> int:
    # Linear + quadratic growth: 250 at quest 1, ~50000 at quest 100.
    # Linear term keeps early quests distinct; quadratic makes late quests
    # meaningfully longer. Rounded to nearest 50 for tidy numbers.
    raw = 200 + 50 * n + int(4.5 * n * n)
    return max(250, (raw // 50) * 50)

block_pat = re.compile(
    r"^  exploring_(\d{3}):\n"
    r"(.*?)"
    r"(?=^  [a-z]+_\d{3}:|\Z)",
    re.DOTALL | re.MULTILINE,
)

reward_pat = re.compile(
    r"rewards:\n\s+money:\s*([\d.]+)\n\s+xp:\s*(\d+)\n\s+items:\s*\[\]",
    re.MULTILINE,
)

rebuilt_blocks = {}
for m in block_pat.finditer(text):
    n = int(m.group(1))
    body = m.group(2)
    r = reward_pat.search(body)
    if not r:
        raise RuntimeError(f"exploring_{n:03d} has no parseable rewards block")
    money = r.group(1)
    xp = r.group(2)

    blocks = amount_for(n)
    desc = f"Travel {blocks:,} blocks"

    rebuilt_blocks[n] = (
        f"  exploring_{n:03d}:\n"
        f"    name: \"Quest {n:03d}: Exploring Challenge\"\n"
        f"    description: \"{desc}\"\n"
        f"    category: EXPLORATION\n"
        f"    difficulty: {difficulty(n)}\n"
        f"    type: WALK_DISTANCE\n"
        f"    target: \"\"\n"
        f"    amount: {blocks}\n"
        f"    rewards:\n"
        f"      money: {money}\n"
        f"      xp: {xp}\n"
        f"      items: []\n"
        f"    prerequisite: null\n"
    )

if len(rebuilt_blocks) != 100:
    raise RuntimeError(f"expected 100 exploring quests, found {len(rebuilt_blocks)}")

def replace_block(m: re.Match) -> str:
    n = int(m.group(1))
    return rebuilt_blocks[n]

new_text = block_pat.sub(replace_block, text)

PATH.write_text(new_text, encoding="utf-8")
print(f"Rewrote {len(rebuilt_blocks)} exploring quests in {PATH}")
print(f"Scale: quest 001 = {amount_for(1)} blocks, quest 050 = {amount_for(50)} blocks, quest 100 = {amount_for(100)} blocks")
