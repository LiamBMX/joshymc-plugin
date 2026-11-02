# Lifesteal hearts (issue #1341)

The core Lifesteal heart system: permanent hearts per player, shown as their maximum health, and
PvP heart stealing. Lifesteal only. It builds on the dedicated server bootstrap
(`docs/lifesteal-server.md`, issue #1336) and reads `lifesteal.yml`; it adds no new settings.

Code: `src/main/kotlin/com/liam/joshymc/lifesteal/hearts/`

| File | Role |
|---|---|
| `HeartRules.kt` | Bukkit-free rules: limits, kill attribution, transfer decision, cooldown arithmetic, admin changes |
| `HeartStore.kt` | Bukkit-free SQLite store: records, transfers, admin changes, audit, all transactional |
| `LifestealHeartManager.kt` | Sessions, max health, death handling, admin entry point |
| `LifestealHeartListener.kt` | Join / quit / world change / post-respawn / death |
| `LifestealHeartsCommand.kt` | `/lifesteal hearts ...` |
| `HeartMessages.kt` | Player messages |

## Rules

From `lifesteal.yml` (defaults = blueprint v1.1): `hearts.starting` 10, `hearts.maximum` 20,
`hearts.minimum` 1, `pvp.same-victim-cooldown-minutes` 30.

- A player's first time in a Lifesteal world creates their row with the starting hearts, once
  (primary key + `INSERT OR IGNORE`, one `INITIAL` audit row).
- 1 heart = 2 health points. The heart count is the `MAX_HEALTH` attribute's **base value**, so
  potions and item modifiers still stack on top. Set on join, after respawn (healed to full, like
  vanilla), on entering a Lifesteal world, and right after every heart change.
- A PvP kill (killer and victim both online, in a Lifesteal world, with a loaded record) moves 1
  heart from victim to killer, in this order of checks:
  1. Victim at the minimum (1): nothing happens, nothing drops, no cooldown starts.
  2. Same killer took a heart from this victim within the cooldown: nothing happens.
  3. Killer at the maximum: nothing happens (see "Open decisions").
  4. Otherwise victim −1, killer +1.
- Nobody is ever eliminated, banned or revived. Hearts never go below 1 or above the maximum.
- Deaths with no causing player (fall, lava, drowning, suffocation, void, mobs, fire) are vanilla
  deaths: no heart changes.

### Kill attribution

The killer is `PlayerDeathEvent.damageSource.causingEntity` when that is a player: the attacker
for melee, the shooter for arrows/tridents/other projectiles, the igniter for player-lit TNT.
Not counted: self-kills, NPC players (`NPC` metadata), a killer who is offline or outside
Lifesteal, and deaths whose final damage had no causing player. The death handler runs at
MONITOR with `ignoreCancelled`, so deaths another plugin cancels never count.

### Anti-farming

`lifesteal_heart_cooldowns` stores the last successful transfer per (killer, victim) pair, in
the database, so logging out, dying or restarting doesn't reset it. Only successful transfers
start it. It's one-directional: the victim can take the heart back right away. It runs on
server wall-clock time (`System.currentTimeMillis()`). A stored time in the future (the clock
went back) counts as "just now", so a clock change can lengthen one cooldown but never skip it.
A negative (corrupt) value counts as no previous transfer. PvP itself is never blocked.

## Persistence and safety

All in the Lifesteal server's own `data.db` (Survival never creates these tables):

| Table | Contents |
|---|---|
| `lifesteal_hearts` | uuid (PK), hearts, initial_granted, version, created_at, updated_at, last_transaction |
| `lifesteal_heart_cooldowns` | (killer, victim) PK, last_transfer_at |
| `lifesteal_heart_transactions` | id (UUID), `dedupe_key` UNIQUE, type PVP/ADMIN, outcome, killer, victim, actor, time |
| `lifesteal_heart_audit` | transaction id, uuid, operation (INITIAL, PVP_LOSS, PVP_GAIN, PVP_REJECTED, ADMIN_SET/ADD/REMOVE), previous/new hearts, actor, reason, time |

- **Atomic:** each kill or admin change is one `databaseManager.transaction`. It reads the rows
  fresh under the DB lock, decides, and writes with `WHERE version = <read version>`. A row that
  changed in between makes the update hit nothing, which throws and rolls everything back. The
  victim never loses a heart unless the killer's gain, the cooldown, the transaction row and the
  audit rows commit with it.
- **Idempotent:** each death gets the key `pvp:<boot id>:<victim>:<server tick>`. A repeated
  event finds the key and does nothing. A rolled-back attempt leaves no key, so it can be retried.
- **Rejected kills are recorded too** (`PVP_REJECTED` with the reason: `VICTIM_AT_MINIMUM`,
  `COOLDOWN`, `KILLER_AT_MAXIMUM`, or a missing/unreadable record).
- **Sessions:** the manager keeps the hearts of online Lifesteal players whose row was read
  this session. A kill involving anyone without one is refused (logged). Quitting drops the
  session, and rejoining reads the row again, so a reconnect can't bring back old values.
- **No delayed saves:** every change is committed before it's applied to the player, so a
  disconnect or shutdown can't lose one, and there's no pending work to flush. These are small
  synchronous transactions on the local SQLite file (one per death / admin command / join),
  the same pattern the plugin's other one-shot claims use. Nothing runs per tick.
- **Database failure:** the transaction rolls back, both players are told nothing changed, and
  the console logs it. After shutdown, the closed database refuses all changes.
- **Malformed rows** (hearts not a whole number) are never reset or guessed. The player's health
  is left as it is, kills involving them are refused, and the console says to repair the row
  with `/lifesteal hearts set`. **Out-of-range rows** (e.g. after lowering `hearts.maximum`)
  are applied clamped. The stored value is only replaced by that player's next heart change.
- **Invalid `lifesteal.yml`:** hearts pause. Nothing is granted, stolen or changed, stored
  hearts and players' health stay as they are, and admin commands refuse. Fix the file, then
  `/joshymc reload`.

## Survival isolation

- The listener is registered only when this server hosts Lifesteal
  (`networkIdentityManager.hostedGamemodes`), and the tables are only created there.
- Every handler also checks the player's world with `gamemodeLockManager.gamemodeOf(world)`.
  On a server that maps worlds to both gamemodes, a player leaving a Lifesteal world gets the
  vanilla max health back. That only happens if the heart system had set it, which is marked in
  the player's data (`joshymc:lifesteal_hearts_applied`), so Survival players are never touched.
- The Overload custom enchant leaves max health alone in Lifesteal worlds.

## Java and Bedrock

Everything is keyed by UUID, so Floodgate UUIDs work like any other. Max health is the normal
attribute, which Geyser sends to Bedrock clients. There are no packets, no custom HUD, and no
Geyser/NetherNet changes. `/lifesteal hearts get` marks Bedrock players.

## Admin commands

`/lifesteal` is already the transfer command (issue #1331), so heart management is its `hearts`
subcommand. Plain `/lifesteal` still transfers.

```
/lifesteal hearts get <player>
/lifesteal hearts set <player> <amount>      # must be within minimum..maximum
/lifesteal hearts add <player> <amount>      # stops at the maximum and says so
/lifesteal hearts remove <player> <amount>   # stops at the minimum (1) and says so
```

These need `joshymc.lifesteal.hearts.admin` (default op) and only work on a server that runs
Lifesteal. Offline players are found through the PlayerResolver (no Mojang lookups). A player
with no row (never played Lifesteal here) is refused instead of getting one created. Every change
is a transaction with an `ADMIN_*` audit row (staff UUID or `console`) and a console line. The
target, if online, is told their new heart count, but not who changed it.

## Open decisions (not in blueprint v1.1)

These are left for a later decision rather than invented:

1. **Killer at the maximum.** Implemented: nothing happens to either player, and no cooldown
   starts. This is fixed in code (`HeartRules.KILLER_AT_MAXIMUM`), not configurable. The
   alternative "victim still loses, killer stays at max" is implemented but unused
   (`VICTIM_LOSES_ONLY`). No heart item or other overflow reward exists.
2. **Environmental deaths after PvP** (knocked into lava or the void, fall damage from a hit).
   Only the final damage counts: these cost no heart.
3. **Combat logging.** A tagged player who logs out leaves the existing combat-log NPC. Killing
   it doesn't move hearts.
4. **Pets and indirect kills.** A tamed wolf's kill isn't credited to its owner (the wolf is the
   causing entity). Kills by crystals, beds or anchors count only when Paper reports a causing
   player.

## Testing

Automated (no server): `./gradlew -I scripts/lifesteal-heart-check.init.gradle.kts lifestealHeartCheck`
runs `scripts/LifestealHeartCheck.kt` against real SQLite. It covers:

- the defaults and config validation, heart-to-health conversion and limits;
- attribution (environment, self, NPC, offline, other gamemode, not loaded, Java/Bedrock UUIDs);
- every transfer outcome, cooldown arithmetic (clock going back, corrupt values), and admin set/add/remove and input parsing;
- new players getting 10 hearts once and keeping saved hearts;
- restart persistence of hearts, cooldowns and dedupe keys, and repeated death events;
- malformed and out-of-range rows;
- concurrent kills on one victim (exactly 9 move, hearts conserved), duplicate deliveries,
  reconnect storms and admin changes racing PvP (no lost updates);
- rollback on a mid-transaction failure and on a stale write, and a closed database;
- the Lifesteal-only wiring.

Manual, on a real Paper 26.2 Lifesteal server (not done yet, so gameplay is unverified):

1. A new Java and a new Bedrock player join: 10 hearts on both clients.
2. A kills B with a sword: A 11, B 10 → 9, and both see the messages. Repeat within 30 minutes:
   no change, cooldown message. B kills A: a transfer happens.
3. A bow kill transfers. Fall, lava, void, mob and `/kill` deaths don't.
4. Get a victim down to 1 heart, kill them: no change. Get a killer to 20, kill: no change.
5. Respawn: correct max health, full health. Relog and restart: hearts kept.
6. Test `/lifesteal hearts get/set/add/remove` on online and offline players, including bad
   input and an account without permission.
7. On the Survival server: max health stays vanilla, `/lifesteal hearts` refuses, and no
   `lifesteal_*` tables exist in its `data.db`.
