# Dedicated Lifesteal server bootstrap (issue #1336)

How to run JoshyMC on its own Lifesteal Paper server, next to the existing Survival SMP. This
builds on the gamemode locks and scopes (`docs/gamemode-scopes.md`, issues #1289/#1329), the
network identity (`docs/network.md`, issue #1323), the shutdown hardening (issue #1325) and the
Velocity transfers (`docs/network-transfers.md`, issue #1331). There is no second framework.

```
Velocity proxy
├── Lobby          (not JoshyMC's job yet)
├── Survival SMP   (Paper + JoshyMC, server-id survival, primary)
└── Lifesteal SMP  (Paper + JoshyMC, server-id lifesteal, secondary)
```

## What this repository does, and what you set up by hand

| Implemented in the plugin | Configured by hand (infrastructure) |
|---|---|
| Recognizing `gamemode-lock.server-gamemode: lifesteal` and logging it at startup | The Paper server, its folder, `server.properties`, ports, worlds |
| Isolating Survival automatically on a dedicated Lifesteal server (commands, scoped listeners, rewards) | Velocity (`velocity.toml`, forwarding secret, server list) |
| Not starting Survival-only managers, timers and worlds there | Geyser / Floodgate on the proxy or the backends |
| `lifesteal.yml` with the approved defaults, and its validation | LuckPerms, WorldGuard and any other plugins, and their data |
| `/jgamemode info` diagnostics | Copying (or not copying) worlds, backups, the Discord bot token |
| A local SQLite database per server (unchanged) | Anything shared between servers (nothing is, by design) |

Hearts and PvP heart stealing are implemented since issue #1341 (`docs/lifesteal-hearts.md`).
Teams (create, invite, leave, kick, transfer, disband; up to 5 players) are implemented since issue
#1346 (`docs/lifesteal-teams.md`); `/team` on Lifesteal opens them, Survival keeps its own teams.
Combat logging (tag notifications, disconnect classification, incident records, no heart
penalty) is implemented since issue #1350 (`docs/lifesteal-combat.md`).
The launch progression (24h grace period, scheduled Ranked Team Wars and End unlocks, `/lifesteal
progress`) is implemented since issue #1352 (`docs/lifesteal-progression.md`).
Elimination and revival never exist (fixed rules). Ranked Team Wars, Elo and season rollover
aren't implemented yet; `lifesteal.yml` holds the settings those later features will read. Nothing here has been tested on a real Lifesteal server yet (see "Testing").

## Requirements

- **Paper 26.2** (the same build line as Survival, `api-version: '26.2'`).
- **Java 25** to run the server (the plugin is compiled to Java 21 bytecode, Paper 26.2 needs 25).
- **Plugin dependencies:** none are required. JoshyMC starts on plain Paper. Optional
  integrations, each skipped quietly when missing:
  - LuckPerms: ranks, donor tiers, `/freerank`, the Discord verification group (primary only).
  - WorldGuard: region protection (flags such as pvp/build are WorldGuard's job).
  - Geyser + Floodgate: Bedrock players (see below).
  - GrimAC: anticheat flags in `/admin`.
  - A Velocity proxy: only when `network.enabled: true` (off by default).
- Survival-only plugin dependencies are not needed on Lifesteal: nothing on Lifesteal looks for them.

## Folder layout

One Paper folder per server; nothing is shared or symlinked between them:

```
servers/
├── survival/                 existing install, untouched
│   └── plugins/Joshymc/{config.yml, data.db, ...}
└── lifesteal/
    ├── paper.jar  server.properties  world/ world_nether/ world_the_end/
    └── plugins/
        ├── joshymc-<version>.jar
        └── Joshymc/          created on first start
            ├── config.yml    this server's own copy
            ├── lifesteal.yml created on first start (Lifesteal servers only)
            ├── data.db       this server's own databases
            ├── playerdata.db
            └── ...
```

Never copy `data.db`, `playerdata.db`, `world/playerdata`, `world/stats` or `world/advancements` from
Survival: inventories, ender chests, statistics, advancements, balances and progression must
start fresh on Lifesteal. Never point two servers at the same folder.

## Configuration

Start the server once so JoshyMC writes its default `config.yml`, stop it, then set (in the
Lifesteal server's `plugins/Joshymc/config.yml` only):

```yaml
network:
  server-id: lifesteal
  primary: false
gamemode-lock:
  server-gamemode: lifesteal
```

Start again. The console shows:

```
Network Server ID: lifesteal
Server Gamemode: LIFESTEAL
Primary Network Instance: false
[Network] Dedicated Lifesteal server: Survival isolated automatically; ...
[Network] Dedicated server: not creating or loading the Survival 'pvp'/'dungeon' worlds.
[Lifesteal] Created lifesteal.yml with the approved defaults.
[Lifesteal] Settings loaded: hearts 10 (1-20), teams up to 5, seasons 3 months.
[GamemodeLock] This server runs Lifesteal; locked: survival (dedicated server).
```

Leave the Survival server's config alone. Its defaults (`server-id: survival`, `primary: true`,
`server-gamemode: survival`) keep it behaving exactly as before, and it never creates
`lifesteal.yml`.

### What "dedicated" means

A server whose `gamemode-lock.server-gamemode` is `lifesteal` and that maps no world to
`survival` under `gamemode-lock.worlds` is a dedicated Lifesteal server. There, at startup:

- **Survival is isolated automatically**, exactly as if `/jgamemode lock survival confirm` had been
  run, and this lock can't be lifted with unlock. Every SURVIVAL command is refused and hidden,
  every SURVIVAL feature's listeners and rewards are skipped (the full list is in
  `docs/gamemode-scopes.md`). NETWORK and unassigned commands keep working.
- **These `features.*` flags count as off**, whatever config.yml says, so their managers never
  start their timers, worlds or announcements: `talismans`, `custom-fishing`, `quests`,
  `event-quests`, `market`, `kill-effects`, `resource-world`, `voting`, `arenas`, `coinflip`,
  `kill-streaks`, `login-streaks`, `orders`, `giveaways`, `gift`, `casino`, `candy-shop`,
  `event-shop` (`DedicatedServerFeatures.SURVIVAL_ONLY`).
- **The `pvp` and `dungeon` worlds are not created or loaded.**

Isolation is decided at startup and only changes on a restart (a `/joshymc reload` that changes
the gamemode settings logs a warning and keeps the old isolation).

A Survival server never isolates anything automatically. A Lifesteal server that also maps a
world to Survival hosts both, so nothing is isolated automatically there; lock by hand if wanted.

**Limitation:** the other managers are still constructed and started on Lifesteal (shared
infrastructure such as the database, settings, punishments, ranks, chat, scoreboard, anticheat,
claims, homes/warps, TPA, AFK). Some create their (empty) tables in Lifesteal's own database.
That data is local to Lifesteal and never touches Survival's database. Deciding which of these
Lifesteal keeps, replaces or turns off belongs to the later Lifesteal issues.

### Invalid gamemode values

`gamemode-lock.server-gamemode` must be `survival` or `lifesteal` (any case). Anything else is a
console error, and `/jgamemode info` marks it invalid:

- If `network.server-id` is a gamemode id (`lifesteal`), that gamemode is used, so a Lifesteal
  server with a typo (`lifestael`) never starts as Survival.
- Otherwise the server falls back to Survival, the setting's original default. Fix the value and
  restart.

### `lifesteal.yml`

Created with the approved Lifesteal Master Blueprint v1.1 defaults on the first start of a
server that runs Lifesteal:

| Setting | Default | Valid values |
|---|---|---|
| `hearts.starting` | 10 | 1..100, between minimum and maximum |
| `hearts.maximum` | 20 | 1..100 |
| `hearts.minimum` | 1 | 1..100, never above starting (players never lose their final heart) |
| `pvp.same-victim-cooldown-minutes` | 30 | 0..525600 (0 = none) |
| `teams.max-size` | 5 | 1..5 (the approved limit; 1..100 before issue #1346) |
| `teams.*` (team system) | see `docs/lifesteal-teams.md` | enabled, invite expiry, creation/disbanding, name length, reserved names |
| `ranked-team-wars.team-sizes` | [2, 3, 4, 5] | distinct sizes 2..teams.max-size |
| `seasons.length-months` | 3 | 1..24 |
| `progression.*` (launch schedule) | see `docs/lifesteal-progression.md` | grace 24h, Ranked +72h, End +168h after launch; no launch time by default |

`config-version: 1` is the file layout version. A newer version is refused.

Fixed rules, deliberately not settings (`LifestealRules`): no permanent elimination, no revival,
players can't lose their final heart, killing a player with exactly 1 heart awards and drops no
heart, Ranked Team Wars keep separate individual and team Elo, use the players' own gear with
inventory and equipment restored afterwards, and hearts stay at risk.

A missing key uses its default (warning). A wrong value is an error, and then the whole file
counts as invalid: `/jgamemode info` says so and later Lifesteal features must refuse to start
rather than guess. Fix it and run `/joshymc reload`. A broken file never stops JoshyMC from
starting.

## Databases

Each server opens only `plugins/Joshymc/data.db` (and `playerdata.db`) inside its own folder. There
is no setting for a database path, no cross-server file access, and nothing is synchronized or
copied: a fresh Lifesteal folder starts with empty databases, and no player record from Survival
exists there. This change adds no tables. Shutdown runs the same isolated steps as Survival
(issue #1325), whether or not the Survival-only managers were started.

## Geyser and Floodgate

Nothing changes: JoshyMC still uses its existing Bedrock helpers (`BedrockUtil`, Floodgate ids in
`PlayerResolver`/`PlayerHeadManager`), writes the Bedrock pack for Geyser when the `bedrock-pack`
feature is on (it stays on for Lifesteal) and adds no packet listeners. Authentication, NetherNet
and Geyser's own settings are untouched. Where to run Geyser/Floodgate (proxy or backend) is in
`docs/network-transfers.md`. A Floodgate player gets the same UUID on both servers, but their data
on each server is separate.

## Velocity

Optional. With `network.enabled: false` (the default) the Lifesteal server runs standalone and no
plugin channel is registered. To join the network follow `docs/network-transfers.md`: set
`network.enabled`, `network.proxy.type: velocity`, `network.transfers.enabled` and the
`network.servers.*` names on every backend, and turn on Paper's Velocity forwarding.

## Validation commands

- `/jgamemode info` (needs `joshymc.jgm.lock` or `joshymc.jgm.unlock`, default false): server id,
  gamemode (and whether its config value was valid), primary flag, every lock and whether it is
  automatic, how many Survival-only startup features are kept off (hover for the list), the
  `lifesteal.yml` status and the main settings, and whether the database is open, transfers are
  on and the Discord bot runs here. It never shows tokens, secrets or addresses.
- The startup log lines above.
- Typing a Survival command (e.g. `/shop`) on Lifesteal must answer "That command is only
  available on Survival."

## Common configuration failures

| Symptom | Cause / fix |
|---|---|
| `gamemode-lock.server-gamemode '...' is not a gamemode` | Typo. Use `lifesteal`, restart. |
| `network.server-id 'survival' is the id of Survival, but gamemode-lock.server-gamemode is lifesteal` | config.yml copied from Survival. Set `server-id: lifesteal`. The server runs as secondary until fixed. |
| `network.primary is not set` | Add `primary: false` on Lifesteal. |
| Survival commands still work on Lifesteal | `server-gamemode` isn't `lifesteal`, or a world is mapped to `survival` under `gamemode-lock.worlds`. Check `/jgamemode info`. |
| `[Lifesteal] lifesteal.yml is invalid` | Read the errors above it, fix the file, `/joshymc reload`. |
| `lifesteal.yml` never created | The server doesn't run Lifesteal (check `server-gamemode`). |
| Two Discord bots answering | `network.secondary-discord-bot: true` with the same token. Turn it off. |

## Rollback

The plugin changes nothing outside the Lifesteal server's own folder, so rolling back is:

1. Stop the Lifesteal server (and remove it from Velocity's `servers`/`try` list if added).
2. To keep its data for later, keep its folder; otherwise delete it. Survival is unaffected
   either way: it never read or wrote anything there.
3. Older JoshyMC builds on Survival ignore the new code paths entirely (Survival never isolates
   anything automatically and never creates `lifesteal.yml`), so rolling the plugin back on
   Survival needs no config change.

## Testing

Automated (Bukkit-free, no server): `./gradlew -I scripts/lifesteal-check.init.gradle.kts lifestealCheck`
runs `scripts/LifestealCheck.kt`: default Survival config, Lifesteal identification, invalid
gamemode values, automatic isolation and GamemodeScope enforcement on a dedicated server, the
Survival-only feature flags (and that the rest are untouched), `lifesteal.yml` defaults and every
validation rule, the startup wiring, and fresh, independent SQLite databases for two servers. The
existing `gamemodeScopeCheck`, `networkIdentityCheck`, `networkTransferCheck` and `shutdownCheck`
cover the frameworks this builds on.

Needs a real server (not automated): start a fresh Paper 26.2 folder with the config above, join
with a Java and a Bedrock player, check the startup log and `/jgamemode info`, try a Survival and a
network command, then stop the server and check the shutdown log has no failed steps. Full
Lifesteal gameplay compatibility can't be claimed until that has been done.
