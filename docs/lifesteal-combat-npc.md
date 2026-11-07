# Lifesteal Combat NPC (issue #1402)

When a player disconnects while combat-tagged in a Lifesteal world, a **Combat NPC** stands in for
them for 30 seconds. Anyone nearby can attack it. If it dies, the player has a normal Lifesteal
death (drops, hearts by the ordinary rules). If it survives, or the player reconnects first, they
keep their items, the NPC's remaining health and their remaining potion effects.

**Lifesteal only.** The manager (`lifesteal/combat/npc/LifestealCombatNpcManager`) and its listener
only run where this server hosts Lifesteal, its table is only created there, and an NPC is only
left for a player standing in a Lifesteal world. Survival (and Survival worlds on a mixed server)
keep the old villager combat-log NPC exactly as before.

## How it fits the existing systems

| Concern | Reused system |
|---|---|
| Combat tag (15 s, both players, refreshed by each hit, cancelled/environmental/mob damage never tags) | `CombatManager` + `CombatListener.onDamageTag` (unchanged) |
| Which disconnects count (shutdown, staff kicks, server errors, transfers never do) | `LifestealCombatManager` / `CombatLogRules` (#1350) |
| Escape commands blocked while tagged | `CombatListener` blocked list + `CombatTeleportGuard` on every teleport path (#1356); the refusal now says how many seconds are left |
| Action-bar countdown | `CombatManager`'s existing `In combat! Ns` through `commsManager.sendActionBar` |
| Hearts | `LifestealHeartManager.onCombatNpcDeath` → the same `HeartRules.attribute` and `HeartStore.transferPvp` as a player death |
| Friendly fire | `TeamCombatRules.damage` with the owner as the victim |
| Kill/death stats (leaderboards) | `ScoreboardManager.recordDeath` (the same counter `PlayerDeathEvent` uses) |
| Kill streaks (and the kill-streak bounty) | `KillStreakManager.recordDeath` (off on a dedicated Lifesteal server, as for player deaths) |
| Player-shaped entity | vanilla `Mannequin`, like `NPCManager` (no NMS, no new dependency) |

## Lifecycle

```
tagged quit ─► ACTIVE ─killed──────────► DEAD ─(items dropped)─► owner joins ─► COMPLETE
                  │──30 s / removed────► SURVIVED ─► owner joins ─► RECOVERING ─► COMPLETE
                  └──owner reconnects────────────────────────────► RECOVERING ─► COMPLETE
```

All transitions are conditional UPDATEs in `lifesteal_combat_npcs` (Bukkit-free `CombatNpcStore`,
rules in `CombatNpcRules`), run on the main thread, so a death, an expiry and a reconnect in the
same tick can't both win. A partial UNIQUE index allows **one open session per player**, so
repeated quit handlers, reloads and reconnect races can never create a second NPC.

### Disconnect

1. `CombatListener.onQuit` asks the #1350 rules (`shouldSpawnCombatLogNpc`), then
   `lifestealCombatNpcManager.takeOver(player)`. Outside Lifesteal it returns false and the old
   villager is used; in Lifesteal the old villager is never used.
2. The inventory is closed (cursor and crafting grid go back into it), then the whole state is
   captured: all 41 slots (`serializeAsBytes`: enchantments, durability, custom enchants, PDC custom
   item ids, Heart items...), held slot, health, MAX_HEALTH base, absorption, fire, air, XP, every
   potion effect (type, amplifier, remaining duration, ambient/particles/icon), location, the tag
   expiry and the player's own skin textures.
3. The row is inserted (ACTIVE, deadline = now + lifetime). **Only after that commit** is the
   player's own inventory, effects and XP emptied, in the same handler, before Paper writes their
   data file. If the insert fails, nothing is cleared and no NPC is spawned (the player keeps their
   items: a database failure must never delete gear).
4. A `Mannequin` spawns at their position: their name (white) with the red `[Combat Logger]` line
   under it (Mannequin's description line; Minecraft draws it below the name, not above), their
   skin, armor, main and off hand, max health, effects, current health, absorption. It is
   **non-persistent** (never saved with the chunk), its equipment has drop chance 0, and a plugin
   chunk ticket keeps its chunk loaded (and follows it) only while it stands.

### While it stands

- Passive: a Mannequin has no AI, so it never moves on its own or attacks; it can't pick up items; right-clicks, leads, name tags, portals and
  teleports on it are cancelled. Health is never shown.
- Damage is vanilla: armor, enchantments, effects, knockback, fall, fire, lava, drowning,
  explosions, mobs. `allow-player-damage` / `allow-mob-damage` / `allow-environmental-damage`
  can turn categories off (all on by default).
- **Safe zones**: a player hit on a standing Combat NPC is decided last (HIGHEST) by the NPC
  manager and un-cancelled when allowed, so WorldGuard regions, claims and PvP toggles can't make
  it safe. This only applies to that entity and only to damage (no block, item or interaction
  permission changes). Refused: the owner, spectators, Mod Mode, and Lifesteal teammates while
  friendly fire is off.
- Every 0.5 s one task checks the deadline and keeps the latest position/health/effects in memory;
  every 5 s that state is written (async, only while ACTIVE).

### Killed

`EntityDeathEvent` (HIGHEST): the entity's own drops and XP are cleared; the killer is the final
damage's `damageSource.causingEntity` player (environmental and mob deaths have none, nothing is
invented). The death is **committed first** (ACTIVE → DEAD, with killer, damage type and vanilla
death message from the NPC's combat tracker). If that write fails, the death event is cancelled
(revive 1 HP) so the NPC never dies holding items the ACTIVE row would bring back. Then, once:

1. the captured items (armor/off hand as worn, i.e. with the fight's durability loss) and the
   vanilla XP drop (7 × level, max 100) drop where it died, then `drops_done = 1`;
2. the vanilla death message is broadcast (respecting `showDeathMessages`), e.g.
   `Steve was slain by Alex`, `Steve fell from a high place`;
3. hearts: `HeartStore.transferPvp` with dedupe key `combatnpc:<session>` (UNIQUE), so a heart moves
   at most once; minimum/maximum, same-victim cooldown, teammates and the launch progression all
   apply; a victim at 1 heart keeps it and nothing is awarded;
4. kill/death stats and kill streaks, with the owner's UUID as the victim (no fake player record);
5. `LifestealCombatNpcDeathEvent` (bounty integration point, see below).

When the owner next joins: empty inventory, no effects, no XP, full health at their (new) max,
fire/food reset, teleported to their respawn point (bed/anchor, else the Lifesteal world spawn),
and told how they died. Their pre-death items are never restored.

### Survived / reconnected

At the deadline (or when the entity leaves the world without dying) the NPC's final state is saved
(SURVIVED) and it despawns; no death, no hearts, kills or streaks. A reconnect while it stands ends
it (RECONNECTED) the same way. On join the owner gets, in this order: the inventory **set** from the
row (armor with legitimate durability loss, broken pieces gone), held slot, XP, remaining effects
(remaining real time only, so they never extend), position where the NPC stood, the NPC's health
(never full health), absorption/fire/air, and the remaining part of the **original** combat tag
(`CombatManager.resumeTag`, never a fresh one). Then their data is saved and the session settles.

## Restart and crash recovery

- **Shutdown**: every standing NPC's current state is saved (still ACTIVE) and the entity removed.
- **Startup** (`refresh`, once per start): open sessions are read.
  - ACTIVE with at least 1 s left: the NPC spawns again for the *rest of its original lifetime*
    (the deadline is absolute, downtime counts), health/position from the last save, effects
    reduced by the real time that passed.
  - ACTIVE past its deadline (or its world isn't loaded): SURVIVED with the last saved state; no
    damage is invented.
  - DEAD with `drops_done = 0`: the items drop once at the death spot.
- **Ghosts**: NPCs are non-persistent, so a crash can't leave one; any entity with the NPC tag that
  no session owns is removed when its chunk loads.
- **Join while settling**: the join handler (LOWEST, before anything else touches the inventory)
  reads the row directly; if it can't be read, the player is disconnected again instead of playing
  with an empty inventory. Unreadable rows are logged and left untouched.
- **Reload**: standing NPCs keep standing; only settings are re-read.
- **Disabling** `leave-npc` or `combat-logging.enabled` stops new NPCs only; sessions already open
  are still settled normally.

### Remaining crash windows (honest limits)

- Minecraft world saves and SQLite aren't one transaction. A hard crash after the death commit but
  before `drops_done` re-drops at startup; that's correct, because items spawned in that same tick
  never reached a world save. If `drops_done` is written but the server crashes before the chunk
  with the drops is saved, those drops are lost, exactly like any vanilla death drop on a crash.
- A crash mid-hand-over (RECOVERING) re-applies the same saved state on the next join. It is a
  *set*, so it can't duplicate, but if the settle write keeps failing and the player changes their
  inventory before it succeeds (it is retried every 0.5 s), the next join would set it back.
- A hard crash while an NPC stands uses the last 5-second save for health/position and the capture
  for armor durability.

## Bounty integration status

There is **no Lifesteal bounty service** in JoshyMC yet. The Survival `/bounty` system is
Survival-scoped and is not called for Combat NPC deaths. `LifestealCombatNpcDeathEvent` (session
id, owner, killer or null, location, damage type) fires once per committed death, after drops,
hearts and stats; a future bounty system should listen to it, apply its own eligibility rules
(Lifesteal survival-world PvP only, no teammates, no self/alt claims, its 24 h killer-target
cooldown) and dedupe on the session id. No bounty is paid today. The existing kill-streak bounty
(KillStreakManager) runs for NPC kills exactly as for player kills; it is off on a dedicated
Lifesteal server (`features.kill-streaks` is Survival-only).

Ranked Team Wars fights never tag (so never leave an NPC), and players held by a match are skipped;
hosted events happen in the event world, which isn't a Lifesteal world.

## Settings (`lifesteal.yml > combat-logging.npc`)

| Key | Default | |
|---|---|---|
| `lifetime-seconds` | 30 | 5..300, real time |
| `label` | `&c[Combat Logger]` | line under the name |
| `show-username` | true | |
| `allow-player-damage` | true | |
| `allow-mob-damage` | true | |
| `allow-environmental-damage` | true | |
| `messages.restored` / `survived` / `died` | | `{death}` = death message |

Fixed rules (not settings): always passive, always the real inventory/effects, health never shown.
The tag duration stays `config.yml > combat.tag-duration-seconds` (15). An older lifesteal.yml without
the section gets one warning and these defaults.

## Known limitations

- **Bedrock**: Mannequins are a 1.21.9+ entity; whether Geyser shows them with the skin depends on
  the Geyser version. Damage and all outcomes are server-side and work regardless.
- The skin comes from the player's own profile textures at disconnect; without textures (rare for
  Floodgate) the NPC shows the default skin. No Mojang lookups are made.
- Combat NPC deaths aren't posted to the Discord PvP kill log (`PvpKillLogListener` only sees
  `PlayerDeathEvent`), and the vanilla death statistic of the owner isn't increased.
- Armor durability loss on the NPC is whatever vanilla applies to a Mannequin's worn armor.
- A client crash and an intentional log-out look the same to the server; both leave an NPC.

## Manual tests

On a Lifesteal test server with two accounts A and B (and a third C):

1. A hits B: both tagged, action bar counts from 15; `/spawn`, `/home`, `/tpa`, `/rtp` refused with
   the seconds left; `/msg` works. Wait 15 s: tag ends.
2. B quits while tagged: one NPC with B's skin, name, red `[Combat Logger]`, armor and held item,
   no health bar; B's health matches. Quit/rejoin spam creates no second NPC.
3. C (not in the fight) can hit it, also inside a spawn WorldGuard region; normal players in that
   region stay protected; the NPC never hits back, can't be right-clicked, picks nothing up.
4. Fire, lava, fall, creeper and zombie damage hurt it; regeneration/poison tick on it.
5. B rejoins before 30 s: NPC gone, B stands where it stood with its health, items (armor worn
   down if it was hit), remaining effects, and the rest of the tag if any. No duplicate items.
6. B quits again; let 30 s pass; B rejoins later: same state, expired effects gone, no death.
7. B quits; A kills the NPC: one set of drops plus XP, `B was slain by A`, A gains / B loses one
   heart once; with B at 1 heart nothing moves. Kill/death stats +1 once. B rejoins: empty
   inventory, full health, at their respawn point, told how they died.
8. Kill the NPC with lava only: `B tried to swim in lava`, no heart moves, no kill credited.
9. Restart with an NPC standing: it is back for only the remaining time; restart after its
   deadline: B rejoins with the survived state. No ghost entities, no duplicate drops.
10. Survival server/world: tagged quit still gives the old villager behaviour; nothing above runs.

Automated: `./gradlew -I scripts/lifesteal-combat-npc-check.init.gradle.kts lifestealCombatNpcCheck`
(settings, rules, store against real SQLite incl. concurrent races, wiring).
