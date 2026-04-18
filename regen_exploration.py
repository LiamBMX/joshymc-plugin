"""
Rewrite exploring_XXX quests in quests.yml to use VISIT_BIOME type instead
of WALK_DISTANCE, so they are meaningfully different from TIME_PLAYED quests.

Targets saturate at 50 biomes (Minecraft 1.21 has ~60 total). Original money/xp
rewards per quest number are preserved.

Run once: python regen_exploration.py
"""
import re
from pathlib import Path

PATH = Path(__file__).parent / "src" / "main" / "resources" / "quests.yml"

text = PATH.read_text(encoding="utf-8")

# Difficulty tiers (matches existing structure)
def difficulty(n: int) -> str:
    if n <= 25: return "EASY"
    if n <= 50: return "MEDIUM"
    if n <= 75: return "HARD"
    return "LEGENDARY"

# Extract each exploring_NNN block's rewards (money/xp) so we can preserve them.
# Block pattern: from "  exploring_NNN:" up to the next top-level quest or EOF.
block_pat = re.compile(
    r"^  exploring_(\d{3}):\n"
    r"(.*?)"                               # body (non-greedy)
    r"(?=^  [a-z]+_\d{3}:|\Z)",            # until next quest id or EOF
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

    target_biomes = min(n, 50)
    desc = f"Discover {target_biomes} unique biome{'s' if target_biomes != 1 else ''}"

    rebuilt_blocks[n] = (
        f"  exploring_{n:03d}:\n"
        f"    name: \"Quest {n:03d}: Exploring Challenge\"\n"
        f"    description: \"{desc}\"\n"
        f"    category: EXPLORATION\n"
        f"    difficulty: {difficulty(n)}\n"
        f"    type: VISIT_BIOME\n"
        f"    target: \"\"\n"
        f"    amount: {target_biomes}\n"
        f"    rewards:\n"
        f"      money: {money}\n"
        f"      xp: {xp}\n"
        f"      items: []\n"
        f"    prerequisite: null\n"
    )

if len(rebuilt_blocks) != 100:
    raise RuntimeError(f"expected 100 exploring quests, found {len(rebuilt_blocks)}")

# Replace the whole exploring section in one pass.
def replace_block(m: re.Match) -> str:
    n = int(m.group(1))
    return rebuilt_blocks[n]

new_text = block_pat.sub(replace_block, text)

PATH.write_text(new_text, encoding="utf-8")
print(f"Rewrote {len(rebuilt_blocks)} exploring quests in {PATH}")
