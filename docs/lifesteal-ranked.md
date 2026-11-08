# Ranked Team Wars: arenas and match lifecycle (issue #1361)

Lifesteal only. This is the infrastructure Ranked Team Wars run on: arenas, the match state machine,
participant validation, inventory snapshots, teleports in and out, death and disconnect handling,
crash recovery and match records. Player Elo and Team Elo are applied after a match is recorded
COMPLETED (issue #1365, [lifesteal-ranked-elo.md](lifesteal-ranked-elo.md)); there is no
leaderboard and no reward yet. Staff
start test matches by hand (`testmatch`); public matchmaking, queues and team challenges (issue
#1363, [lifesteal-ranked-queue.md](lifesteal-ranked-queue.md)) create theirs through the same
lifecycle (`LifestealRankedManager.createQueuedMatch`, which shares every check with `createMatch`).

Code: `src/main/kotlin/com/liam/joshymc/lifesteal/ranked/`

| File | What it is |
|---|---|
| `RankedArena.kt` | Bukkit-free arena model (`RankedArena`, `Point`, `Box`), `RankedArenaRules` (ids, names, sizes, validation, availability) |
| `RankedMatchRules.kt` | Bukkit-free `MatchState` machine, roster, participant validation, victory decision, allowed commands |
| `RankedStore.kt` | Bukkit-free SQLite store: arenas, matches, participants, snapshots, event log |
| `LifestealRankedManager.kt` | The one match manager (lifecycle, snapshots, teleports, recovery, admin actions) |
| `LifestealRankedListener.kt` | Rules on real events (damage, death, respawn, join/quit, teleports, boundaries, inventory protection) |
| `LifestealRankedCommand.kt` | `/lifesteal admin arena ...` and `/lifesteal admin ranked ...` |

## Approved rules kept

2v2 to 5v5 (`lifesteal.yml > ranked-team-wars.team-sizes`). Players represent their existing
Lifesteal team and fight with their **real** inventory and equipment: no kits. Inventories are
restored afterwards, so nobody permanently loses items in a ranked match. **Hearts stay at risk**:
kills go through the normal heart service (`LifestealHeartManager.onDeath` → `HeartStore.transferPvp`)
with the same 1..20 limits, same-victim cooldown, team check and dedupe key as any Lifesteal kill.
Restoring an inventory never touches hearts and there is no separate ranked heart balance. Being
knocked out of a match is not Lifesteal elimination: players at 1 heart are ordinary players. Ranked
unlocks on Day 3 (`LifestealProgressionManager.rankedStatus() != LOCKED`; a disabled progression
doesn't hold it back, an unknown one does).

## Arenas

Stored in `data.db` (`lifesteal_ranked_arenas`), never in a config file, so they survive restarts
and reloads. Each arena has an id (`a-z0-9_-`, up to 32), a display name, a world (stored by name),
a boundary box, 1-5 Team A and 1-5 Team B spawns (players share spawns round-robin when a side has
more players than spawns), 0-5 spectator points, the sizes it hosts, and an enabled flag.

Validation (`RankedArenaRules.validate`, never loads a world or chunk):

- the world must be loaded and a Lifesteal world (arenas never load or create worlds);
- the boundary must be set, at least 5x3x5 and at most 512 blocks per side;
- every spawn must be inside the boundary; spectator points must be **outside** it;
- at least one allowed size must also be in `ranked-team-wars.team-sizes`;
- an enabled arena may not overlap another enabled arena in the same world, so a fight can never
  reach an unrelated arena.

READY / INCOMPLETE / INVALID. Only READY arenas can be enabled; an edit that makes an enabled
arena unusable disables it. Availability is computed, never stored: AVAILABLE, RESERVED (match
created/preparing), ACTIVE (countdown/fight), RESETTING (finishing), DISABLED (off or not ready).
Arenas held by a match can't be edited or deleted; deleting is a soft delete so records keep their
arena id. Arenas **never change blocks**: placing, breaking, buckets and signs are refused for
participants, and explosions inside an arena a match is using break no blocks. A bounded block
restoration (like the Spleef/CPvP floor snapshots) is the way to allow building later.

At preparation the spawns' chunks are loaded with `getChunkAtAsync` and every spawn is checked with
`SafeSpots` (solid floor, two passable blocks, no lava/fire); an unsafe spawn cancels the match. An
arena with other players standing in it is refused.

## Match lifecycle

`CREATED → VALIDATING → PREPARING → COUNTDOWN → ACTIVE → FINISHING → COMPLETED`, any state before
FINISHING → `CANCELLED`. Every transition is checked by `MatchState.canMoveTo` and stored with a
conditional UPDATE on the current state, so it happens once. Match ids are `rtw-` + 12 hex digits.

1. **Validation** (nothing is touched yet): both teams exist and differ, the size is 2..5 and
   allowed, each side has `size` online members (the first online members in roster order: leader
   first, then by join time), every participant is a member of their team, online, in a Lifesteal
   world, has a loaded heart session with valid hearts, isn't in another match or still waiting for
   an earlier ranked restore, isn't switching servers, combat-tagged, dead, outside Survival game
   mode, in Mod Mode, frozen, trading or in the event world; the arena is AVAILABLE and empty;
   Ranked Team Wars have unlocked for the season; teams and hearts are running.
2. **Reservation + snapshot**: the match row (PREPARING), every participant (`open = 1`) and every
   inventory snapshot are written in **one transaction**. Partial UNIQUE indexes make a second open
   match in the same arena, a player in two open matches, and a second pending snapshot for a player
   impossible; if the insert fails nothing was changed.
3. **Preparation**: flight off (given back afterwards to players who may fly), chunks loaded, spawns
   checked, players teleported (`teleportAsync`). Anything failing within 20s cancels the match and
   restores everyone.
4. **Countdown** (`countdown-seconds`, default 10): titles, no damage.
5. **Active**: only players of the same match hurt each other (team friendly-fire rules still apply).
   `max-match-minutes` (default 20) cancels an unfinished match with no winner.
6. **Finishing → Completed**: the winner is stored first, then everyone online is restored and moved
   back, loose leftovers inside the boundary (dropped items, arrows/tridents, End Crystals, XP orbs,
   in loaded chunks only) are removed, and the match is completed.

### Victory

A side with nobody left fighting loses. Defeats (death, disconnect, staff removal) are decided on the
next tick, so the last players of both sides dying in the same tick is **no winner** and the match is
cancelled rather than going to whichever event fired first. The blueprint gives no other victory rule.

## Inventory snapshots

What is stored (`lifesteal_ranked_snapshots`): all 41 inventory slots (36 storage, 4 armor, off-hand)
as Paper's lossless `ItemStack.serializeAsBytes` (Base64), so durability, enchantments, custom
enchants, custom item ids (PDC), Heart item serials, names and lore are kept byte for byte; the held
hotbar slot; and the authoritative return location. The cursor and 2x2 crafting grid are emptied into
the inventory first. Snapshots are UNIQUE per (match, player) and never overwritten.

Restoring **sets** the inventory to the snapshot (cursor and crafting grid cleared first), saves the
player, then marks the snapshot RESTORED with a conditional UPDATE, exactly once. It happens:

| When | Where |
|---|---|
| match won, lost or cancelled | at once for everyone online and alive, then moved back |
| died in the match | nothing drops (no XP orbs either); respawn at the return location, restored on respawn |
| disconnected | on their next join (or respawn if they left dead), then moved back |
| server shutdown | synchronously in `onDisable`, before the database closes |
| crash / restart | on their next join; the match was recorded CANCELLED (or COMPLETED if it already had a winner) |

Because restoring is a set, applying a snapshot twice gives the same inventory: a crash between the
player save and the RESTORED mark is harmless, and the next join simply applies it again. If a
snapshot can't be read nothing is changed and it stays pending (`/lifesteal admin ranked restore`).
The match record's `restoration` becomes COMPLETE once every snapshot is restored.

While a match holds a player, nothing can leave or enter their inventory for good, so restoring can
never duplicate items and extracting "match" items is impossible: dropping, picking up, every
container and menu (ender chest, chests, anvils, villagers, other plugins' GUIs), entity use (item
frames, armor stands, mounts), paintings/item frames, block placing/breaking, buckets, signs, every
command except private messages, Heart item redeem/withdraw, all vouchers, and store deliveries
(`PurchaseFulfillmentManager` waits until the match is over) are refused. End Crystals can be placed
(removed afterwards). Consumables used in the match (potions, golden apples, pearls, totems) come back
with the snapshot; anything picked up in the arena doesn't.

**Not restored on purpose**: hearts (normal Lifesteal rules), XP levels (kept on death, XP orbs never
drop), potion effects, hunger. Other plugins that put items straight into a held player's inventory
mid-match (not through JoshyMC's deliveries) would be overwritten by the restore.

## Teleports, transfers, boundaries

Held players can't teleport out of their arena by any cause (`PlayerTeleportEvent`, so `/spawn`,
`/home`, `/tpa`, `/rtp`, `/team home`, warps, GUIs, NPC and console commands, portals all fail); ender
pearls and chorus fruit landing inside the arena are fine. `CombatTeleportGuard.blocks` refuses them
first with a clear message, and server transfers (`/lobby`, `/hub`, `/survival`, future selectors)
are refused through `TransferBlocker.RANKED_MATCH`, also while a restore is still pending. Walking out
of the boundary is pushed back (exact coordinates, like the PvP arenas), mounting is refused. Anyone
else can't walk or teleport into an arena a match is using and can't hurt its players. Staff recover
players with `/lifesteal admin ranked remove <player>`, which takes them out through the match system
(restored, moved back) instead of a separate unrestricted teleport.

## Combat tags, disconnects and forfeits

Ranked hits never combat-tag (like hosted Crystal PvP), and participants pass the personal PvP
toggle. That keeps the combat-log NPC (which would drop real gear) out of ranked matches; the match
handles disconnects itself:

- before the fight (PREPARING/COUNTDOWN): the match is cancelled; the leaver's snapshot waits for
  their return;
- in the fight: a **forfeit** (issue #1465). They're out of the match (`DISCONNECT`), and one heart
  may go to the opponent who hit them most recently, through the normal heart rules. Staff kicks
  and server errors only take them out (`DISCONNECT_EXEMPT`), and a shutdown is left to shutdown;
- on rejoin: restored and moved back, told what the forfeit did; they can't rejoin the match.

The full policy (classification, recipient rule, Elo, same-tick tie-break, limitations) is in
[lifesteal-ranked-beta.md](lifesteal-ranked-beta.md), section G. For Elo (issue #1365) a disconnect is
an ordinary "out": the match result stands and the disconnected player is rated with their side, so
quitting can't dodge a loss.

## Spectators

`/lifesteal admin ranked spectate <match>` (`joshymc.lifesteal.ranked.spectate`, op) teleports a staff
member to the arena's first spectator point (outside the boundary). Spectators are tracked separately
from participants, take and deal no damage, can't pick up items, open containers or change blocks,
can't walk into the arena, and can never be part of a heart transfer. `unspectate` stops; it also ends
with the match. There's no public spectator mode or GUI yet.

## Records

`lifesteal_ranked_matches` is the match record: id, season id, arena id, both team ids and names, size,
test flag, kind (`TEST` / `PUBLIC` / `CHALLENGE`, issue #1363; rows from before it are read as
`TEST`) and the queue proposal that created it (`queue_ref`), state, creator, created/started/ended
times, winner side and team, outcome (WIN / CANCELLED), cancel reason, restoration status. Participants (UUID, side, team, status, defeat reason)
are in `lifesteal_ranked_participants`, every transition/defeat/disconnect/restore in
`lifesteal_ranked_events`. Only UUIDs, team ids/names and coordinates are stored. Lifecycle edges
(creation, transitions, restorations) are short synchronous transactions on the local SQLite file,
like the heart system; defeats and disconnects during combat are queued with `databaseManager.async`.
Nothing touches the database per hit or per tick.

## Commands

All `joshymc.lifesteal.ranked.admin` (op), subcommands of the existing `/lifesteal` executor.

```
/lifesteal admin arena create <id> [display name]      (in the world you stand in)
/lifesteal admin arena setcorner <id> <1|2>            (block you stand in; both = boundary)
/lifesteal admin arena setspawn <id> <a|b>             (adds your position, up to 5)
/lifesteal admin arena setspectator <id>               (adds your position, outside the boundary)
/lifesteal admin arena clearspawns <id> <a|b|spectator>
/lifesteal admin arena sizes <id> <2,3,4,5|all>
/lifesteal admin arena setname <id> <display name>
/lifesteal admin arena enable|disable <id>
/lifesteal admin arena list | info <id>
/lifesteal admin arena delete <id> confirm
/lifesteal admin ranked testmatch <teamA> <teamB> <2-5> [arena]
/lifesteal admin ranked list | info <match>
/lifesteal admin ranked cancel <match> confirm
/lifesteal admin ranked remove <player> confirm        (admin recovery)
/lifesteal admin ranked restore <player>               (apply a pending snapshot now)
/lifesteal admin ranked spectate <match> | unspectate  (also joshymc.lifesteal.ranked.spectate)
```

Test matches are recorded with `test = 1` and say TEST everywhere. They use the real lifecycle,
inventory and heart rules and **don't bypass the seasonal unlock** (before launch and during grace,
PvP is blocked anyway, so a test match couldn't finish). A held staff participant can still use
`/lifesteal admin ranked ...`.

## Survival isolation

On a server that doesn't host Lifesteal: no tables are created, the listener isn't registered, every
action refuses, and the hooks in shared code (`CombatManager.canPvP`, `CombatListener`,
`CombatTeleportGuard`, `TransferManager`, the voucher listeners, `PurchaseFulfillmentManager`) all ask
the manager's empty in-memory maps, so they never change anything there. Survival teams, arenas, PvP
and inventories are never touched. Java and Bedrock (Geyser/Floodgate) players are handled the same:
everything is keyed by UUID (Floodgate's stable ids), items go through Paper's own serializer, and
there's no packet code.

## Checks

```
./gradlew -I scripts/lifesteal-ranked-check.init.gradle.kts lifestealRankedCheck
```

Bukkit-free rules, the store on a real SQLite database (restarts and crashes simulated by reopening
it), heart integration through the real `HeartStore`, the lifesteal.yml settings and the wiring.

## Manual tests (need a live Paper server)

Not covered by the checks (item serialization, teleports and events need a running server):

1. Build an arena in a Lifesteal world (corners, two spawns per side, spectator point outside), enable it.
2. Two Lifesteal teams of 2, after Ranked unlocked: `testmatch A B 2`. Players move in, countdown, fight.
3. Before the match: give players custom items (a crate weapon with custom enchants, a damaged
   netherite piece, a Heart item, a named shulker). After winning, losing, dying, `/lifesteal admin
   ranked cancel`, and a disconnect + rejoin: inventories are exactly as before; no item duplicated.
4. A kill in the match moves one heart (check `/lifesteal hearts get`); a victim at 1 heart keeps it;
   a killer at 20 gains none; restoring doesn't change hearts.
5. Try `/spawn`, `/home`, `/tpa`, `/rtp`, `/team home`, `/lobby`, `/ec`, `/pv`, dropping items, opening
   a chest, placing blocks, an ender pearl out of the boundary, walking out: all refused.
6. Kill the last players of both teams at once (e.g. one crystal): cancelled, no winner.
7. Stop the server mid-match: everyone online is restored and moved back. Kill the process mid-match
   (`kill -9`): on restart the match is CANCELLED and each player is restored on join.
8. Same with a Bedrock (Geyser/Floodgate) player on a team with a Java player.
9. On the Survival server: `/lifesteal admin arena list` refuses; nothing else changes.
