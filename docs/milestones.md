# Milestones (issue #1420)

Infinite, personal progression for **Survival only**, opened with `/milestones` (alias `/milestone`,
`/milestones <category>` opens a category directly). No leaderboards.

Five categories, each with an overall **Level** and individual **objectives**:
Farming, Mining, Fishing, Combat, Exploration. Every player starts at Tier 1 with zero progress;
no historical statistics are imported.

## How progress works

- An activity counts for every objective of its category whose `matches` list shares a token
  with it, and **once** for that category's Level (e.g. 500 sugar cane = Sugar Cane +500 and
  Farming Level +500; sprinting 100 blocks in the Nether = Sprinting +100, Nether Travel +100,
  Exploration Level +100).
- When a track (objective or Level) reaches its requirement it **pauses**. The next tier only
  starts (at 0) once the player claims the completed one.
- Tracks are independent: a paused objective never stops the Level or other objectives, and a
  paused Level never stops objectives.
- Progress is stored in 1/720720 units (`MilestoneMath.SCALE`, divisible by 1-16), so fractional
  progress (spawner kills, distances) is exact and survives sessions and restarts.

## Requirement scaling

Tier `t` of a track needs

```
requirement(t) = ceil( base × (1 + growth × (t − 1)^exponent) )
```

`0 < exponent ≤ 1`: with the default `growth 0.25, exponent 0.6` (objectives) and
`growth 0.3, exponent 0.6` (Levels) every tier needs more than the last but each step adds less
than the one before. Requirements never decrease, never reach zero (base ≥ 1), and are clamped to
10^12 units (tiers to 1,000,000) so nothing can overflow.

| Tier | Objective multiplier | Sugar Cane (base 600) | Level multiplier |
|---:|---:|---:|---:|
| 1 | 1.00 | 600 | 1.00 |
| 2 | 1.25 | 750 | 1.30 |
| 5 | 1.57 | 945 | 1.69 |
| 10 | 1.93 | 1,161 | 2.12 |
| 25 | 2.68 | 1,610 | 3.02 |
| 50 | 3.58 | 2,150 | 4.10 |
| 100 | 4.94 | 2,964 | 5.73 |
| 1000 | 16.76 | 10,059 | 19.92 |

## Objectives and Tier 1 requirements

Tier 1 targets roughly 10-20 minutes of focused activity.

**Farming** (Level base 3,000) — age-based crops count only fully grown (bone meal is fine);
sugar cane / cactus / bamboo / kelp count every naturally grown segment of the broken column
(up to 32 above it), never a placed one; melons, pumpkins and chorus never when placed; sweet
berries and glow berries count when picked (right-click) or broken with berries.

| Objective | Base | Tokens |
|---|---:|---|
| Wheat | 400 | WHEAT |
| Carrots | 400 | CARROTS |
| Potatoes | 400 | POTATOES |
| Beetroot | 400 | BEETROOTS |
| Sugar Cane | 600 | SUGAR_CANE |
| Melons | 300 | MELON |
| Pumpkins | 300 | PUMPKIN |
| Cocoa Beans | 250 | COCOA |
| Nether Wart | 400 | NETHER_WART |
| Cactus | 400 | CACTUS |
| Bamboo | 800 | BAMBOO |
| Sweet Berries | 150 | SWEET_BERRIES |
| Glow Berries | 150 | GLOW_BERRIES |
| Kelp | 600 | KELP |
| Chorus | 300 | CHORUS |

**Mining** (Level base 5,000) — one block = 1, broken with a tool that harvests it; Silk Touch
and Fortune change nothing; deepslate variants fold into the same objective (one block never
counts twice).

| Objective | Base | Tokens |
|---|---:|---|
| Stone | 1,200 | STONE, GRANITE, DIORITE, ANDESITE, TUFF, CALCITE |
| Deepslate | 800 | DEEPSLATE |
| Netherrack | 2,000 | NETHERRACK |
| End Stone | 800 | END_STONE |
| Basalt & Blackstone | 600 | BASALT, SMOOTH_BASALT, BLACKSTONE |
| Coal Ore | 150 | (DEEPSLATE_)COAL_ORE |
| Iron Ore | 120 | (DEEPSLATE_)IRON_ORE |
| Copper Ore | 150 | (DEEPSLATE_)COPPER_ORE |
| Gold Ore | 60 | (DEEPSLATE_)GOLD_ORE |
| Redstone Ore | 80 | (DEEPSLATE_)REDSTONE_ORE |
| Lapis Ore | 50 | (DEEPSLATE_)LAPIS_ORE |
| Diamond Ore | 25 | (DEEPSLATE_)DIAMOND_ORE |
| Emerald Ore | 10 | (DEEPSLATE_)EMERALD_ORE |
| Nether Quartz Ore | 200 | NETHER_QUARTZ_ORE |
| Nether Gold Ore | 150 | NETHER_GOLD_ORE |
| Ancient Debris | 8 | ANCIENT_DEBRIS |

**Fishing** (Level base 150) — every real catch counts for the Level; tokens are the vanilla
catch, its class (FISH / TREASURE / JUNK; fishing rods are treasure only when enchanted), where it
was caught (OCEAN / RIVER / SWAMP), RAIN and NIGHT. The catch is read before FishingManager swaps
it for a custom fish; its high-Luck fallback catches count by the custom fish's base item.

| Objective | Base | Tokens |
|---|---:|---|
| Cod | 50 | COD |
| Salmon | 25 | SALMON |
| Tropical Fish | 8 | TROPICAL_FISH |
| Pufferfish | 10 | PUFFERFISH |
| Treasure | 5 | TREASURE |
| Junk | 10 | JUNK |
| Ocean Catches | 60 | OCEAN |
| River Catches | 60 | RIVER |
| Rainy Catches | 40 | RAIN |
| Night Catches | 40 | NIGHT |

**Combat** (Level base 800) — only kills the player dealt (melee, or a projectile they shot);
natural mobs full progress, spawner mobs 1/5 (configurable 1-16, exact).

| Objective | Base | Tokens |
|---|---:|---|
| Zombies | 120 | ZOMBIE, HUSK, DROWNED, ZOMBIE_VILLAGER |
| Skeletons | 100 | SKELETON, STRAY, BOGGED |
| Creepers | 60 | CREEPER |
| Spiders | 80 | SPIDER, CAVE_SPIDER |
| Endermen | 40 | ENDERMAN |
| Witches | 20 | WITCH |
| Slimes & Magma Cubes | 80 | SLIME, MAGMA_CUBE |
| Blazes | 60 | BLAZE |
| Wither Skeletons | 40 | WITHER_SKELETON |
| Piglins & Hoglins | 60 | ZOMBIFIED_PIGLIN, PIGLIN, PIGLIN_BRUTE, HOGLIN, ZOGLIN |
| Ghasts | 15 | GHAST |
| Phantoms | 25 | PHANTOM |
| Guardians | 60 | GUARDIAN, ELDER_GUARDIAN |
| Illagers | 40 | PILLAGER, VINDICATOR, EVOKER, RAVAGER |
| Shulkers | 30 | SHULKER |
| Breezes | 20 | BREEZE |
| Player Kills | 10 | PLAYER |

**Exploration** (Level base 20,000; blocks, horizontal distance)

| Objective | Base | Tokens |
|---|---:|---|
| Walking | 3,000 | WALK |
| Sprinting | 5,000 | SPRINT |
| Swimming | 2,000 | SWIM |
| Overworld Travel | 6,000 | OVERWORLD |
| Nether Travel | 3,000 | NETHER |
| End Travel | 3,000 | THE_END |
| Underground Travel | 3,000 | UNDERGROUND (overworld, no sky light) |
| Highland Travel | 3,000 | HIGHLANDS (overworld, Y ≥ 120) |

## Rewards

Every tier pays Coins (EconomyManager):

```
coins(t) = floor( min(cap, base × (1 + growth × (t − 1)^exponent)) )
```

| | base | growth | exponent | cap | Tier 1 | Tier 10 | Tier 100 | cap reached |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| Objectives | 10,000 | 0.25 | 0.6 | 75,000 | 10,000 | 19,342 | 49,384 | ~tier 230 |
| Levels | 25,000 | 0.25 | 0.6 | 200,000 | 25,000 | 48,357 | 123,460 | ~tier 260 |

(For scale: one Daily Quest pays 25,000-50,000.)

Special tiers: **5, 10, 25, 50, 75, 100, then every 25**. Major: **every 100**. Guaranteed, not
random. Defaults (crates `common`, `rare`, `joshy` from the bundled crates.yml):

| Tier | Objectives | Levels |
|---|---|---|
| 5 | +5,000 Coins | 1 Common Key |
| 10 | 1 Common Key | 2 Common Keys |
| 25 | 2 Common Keys | 1 Rare Key + 2 Credits |
| 50 | 1 Rare Key | 2 Rare Keys + 3 Credits |
| 75 | 1 Rare Key + 25,000 Coins | 2 Rare Keys + 3 Credits |
| every 25 above 100 (125, 150, 175, …) | 1 Rare Key | 2 Rare Keys + 3 Credits |
| every 100 (major) | 1 Joshy Key + 5 Credits | 1 Joshy Key + 10 Credits |

Reward lines (`rewards.objective-special` / `rewards.level-special`, any tier number, `every-25`,
`every-100`): `coins <amount>`, `credits <amount>` (≤ 1,000), `cratekey <crate> [qty]`,
`chattag <tag-id>` (an existing Chat Tag voucher tag, given as its voucher), `item <custom-item-id> [qty]`
(≤ 64). Lines naming a crate, tag or item that doesn't exist are logged at startup and skipped,
never faked. Ranks, cosmetics and permissions are deliberately not reward types (no grant path
that is safe offline/idempotent exists for them here); add them as a new `MilestoneRewards.Kind`
if ever needed. No chat tag is configured by default: there is no milestone-exclusive tag yet.

## Claiming

- Per objective (details screen or category icon), per Level (category header), or **Claim All**
  in a category (each tier its own transaction, Level first).
- A claim re-checks everything server side, then in **one transaction**: inserts the
  `milestone_claims` row (PK uuid + track + tier, so a tier can be claimed once ever), moves the
  stored track to tier + 1 / progress 0 only if it is still on that tier, deposits Coins and
  Credits, and writes every physical reward as a `milestone_pending_rewards` row. Any failure
  (database, deposit) rolls all of it back. If a configured reward can't be built at claim time
  (crate deleted since startup) the claim is refused and nothing changes.
- After the commit each pending row is delivered: the row is deleted (conditionally) **before** the
  item is given, and only when the whole stack fits; otherwise it stays in **Pending Rewards**
  (`/milestones` → Pending Rewards, click one or Withdraw All). Nothing is ever dropped. In the
  event world items always stay pending (the event inventory is wiped on exit).
- Rapid clicks: GuiManager's 200ms click cooldown, a per-player in-progress guard, and the unique
  claim row.

## Anti-exploit rules

- **Placed blocks**: every placed block whose material some milestone tracks is marked in the
  chunk's own persistent data (`joshymc:milestone_placed`, packed positions, ≤ 16,384 per chunk).
  Generators (lava + water / blue ice → stone, cobblestone, basalt, obsidian) and blocks set by
  endermen are marked too; pistons move the mark and mark moved blocks; breaks/explosions clear it;
  natural growth into a spot clears a stale mark. A regenerated resource world has fresh chunks, so
  freshly generated resources count normally.
- **Combat**: the spawn reason decides (`full-spawn-reasons`, `reduced-spawn-reasons`; anything
  else, e.g. spawn eggs, commands, plugins, portals, conversions, never counts). Kills only count
  when the player's own hit/projectile killed the mob (no fall/lava/trap finishes), at most
  `max-mob-kills-per-minute`. Combat NPCs, NPCs and hosted-event mobs never count. Stacked mobs
  (mob stacking) count as one kill.
- **PvP**: never self, teammates, an idle victim, the same victim within
  `same-victim-cooldown-minutes` (persisted in `milestone_pvp_kills`), past `max-kills-per-hour`,
  or accounts that ever shared a connection hash (AltManager's `player_connections`; when that
  can't be read the kill doesn't count). Only reads PlayerDeathEvent at MONITOR, so PvP itself is
  untouched.
- **Fishing / combat / exploration idle check**: nothing counts for a player who hasn't moved their
  view or pressed a movement key for `anti-afk.idle-seconds` (180). Catches also need
  `min-seconds-between-catches` (2.5) and count once per hook.
- **Exploration**: sampled once per second (no per-move work). Not counted: vehicles (boats,
  minecarts, horses), elytra, flight, riptide, teleports/portals/world changes/respawns (sample
  reset), jumps faster than `max-blocks-per-second`, movement without a movement key
  (water streams, pistons, knockback), and loops (more than 150 blocks inside a 12-block box over
  the last 60 counted seconds).
- **Worlds**: only worlds the gamemode lock maps to Survival (overworld, nether, end, resource);
  never Lifesteal worlds, the event world, `worlds.excluded` (`spawn`, `afk`), Creative/Spectator
  players or Mod Mode staff. `worlds.allowed` (empty = all) restricts further. On a dedicated
  Lifesteal server the feature doesn't start (`DedicatedServerFeatures.SURVIVAL_ONLY`).

## Persistence and performance

- data.db tables: `milestone_progress` (uuid + track → tier, progress), `milestone_claims`,
  `milestone_pending_rewards`, `milestone_pvp_kills`. No world is stored, so world resets never
  touch milestones.
- Progress is loaded asynchronously on join (until then nothing is recorded), kept in memory while
  online, and saved in batches every 30s, on quit and at shutdown (synchronously, before the
  database closes). Saves are guarded by `WHERE tier = ?`, so a late save can never undo a claim.
  A failed load is retried, never replaced by an empty book. All per-player caches are removed on
  quit; placed-block caches are dropped on chunk/world unload.
- `/joshymc reload` saves everything, reloads `milestones.yml` and reloads online players.

## Configuration

`plugins/Joshymc/milestones.yml` (created from the bundled file when missing, never overwritten;
missing keys fall back to the bundled defaults). `config.yml > features.milestones` turns the
whole system off. Permission `joshymc.milestones.use` (default true). Sections: `enabled`,
`worlds`, `anti-afk`, `mining`, `fishing`, `combat` (+ `pvp`), `exploration`, `announcements`
(server-wide on claimed tiers 25, 50 and every 100; private completion/claim messages), `gui`,
`rewards`, `categories` (per category `enabled`, `name`, `icon`, `unit`, `level`, `scaling`, and
objectives with `name`, `icon`, `base`, `matches`, optional `growth`/`exponent`/`coins-base`/`enabled`).

## Checks and manual test plan

Automated: `./gradlew -I scripts/milestone-check.init.gradle.kts milestoneCheck` (scaling, rewards,
book, rules, layouts, the bundled yml through the real parser, and the store on a real SQLite file
including restart persistence).

The Bukkit parts (listeners, chunk markers, GUI, inventory delivery) need a server. Manual plan:

1. `/milestones`: five categories, Pending Rewards, info; each category sorted (claimable, in
   progress by %, unstarted); details screen shows tier, numbers, %, bar, rewards, upcoming tiers.
2. Harvest grown wheat → Wheat and Farming Level move; break unripe wheat / a placed melon / placed
   sugar cane → nothing; break a 3-tall natural cane from the bottom → +3.
3. Place and break diamond ore → nothing. Build a cobble/stone generator, mine its output → nothing.
   Mine natural stone with a pickaxe → +1; with a fist → nothing.
4. Kill a natural zombie → +1; five spawner zombies → +1; spawn-egg zombie → nothing; kill a
   player twice within 30 min → second doesn't count; kill an alt on the same connection → nothing.
5. Fish: each catch counts once; stand completely still (no mouse/keys) for 3 min → stops counting.
6. Walk/sprint/swim → Exploration; boat/elytra/ender pearl/`/spawn` → nothing.
7. Complete a tier → private message, progress pauses; claim → coins deposited, tier 2 at 0.
   Fill the inventory, claim a key tier → key appears in Pending Rewards; withdraw it.
   Spam-click claim → paid once. Restart → progress, claims and pending rewards are kept.
8. Claim a tier 25 → server-wide broadcast once.
9. In a Lifesteal world / the event world / spawn / Creative: nothing counts; `/milestones` refuses
   outside Survival.

## Limitations / follow-up

- Blocks placed **before** this feature shipped have no marker and count as natural once.
- Markers are per chunk (max 16,384); in a chunk that exceeds it the oldest markers are dropped.
- Blocks removed without an event we can see (e.g. some plugins' world edits) can leave a stale
  marker, which only ever under-counts.
- Exploration counts horizontal distance; vertical swimming/climbing doesn't count.
- No milestone-exclusive chat tag or cosmetic exists yet; add one with `chattag <id>` once created.
- Announcements fire on claim (the moment a tier is reached on the player's record), not at the
  moment progress fills.
