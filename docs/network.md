# Network server identity (issue #1323)

Survival SMP and Lifesteal run on **separate Paper servers**, each with its own copy of
JoshyMC, its own `plugins/Joshymc/config.yml`, worlds, player inventories and SQLite
database (`plugins/Joshymc/data.db`). Nothing is shared or synchronized between them.
This page covers the settings that tell each installation which server it is.

## Settings (`config.yml > network`)

| Key | Default | Meaning |
|---|---|---|
| `network.server-id` | `survival` | This server's id: 1-32 lower-case letters, digits, `-` or `_`, starting and ending with a letter or digit. Trimmed and lower-cased when read. Must be different on every server. Read once at startup: changing it needs a restart (`/joshymc reload` keeps the old id and logs a warning). |
| `network.primary` | `true` | Whether this server runs the network-wide jobs (below). Exactly one server should be primary. Follows `/joshymc reload`. |
| `network.secondary-discord-bot` | `false` | On a secondary only: connect the Discord bot anyway. Turn it on only when `discord.token` on that server is a **different bot** of its own. |

`gamemode-lock.server-gamemode` (issue #1289) still says which gamemode the server runs.

## Recommended configurations

Survival SMP (these are the bundled defaults, so an existing Survival server needs no changes
and behaves exactly as before):

```yaml
network:
  server-id: survival
  primary: true
gamemode-lock:
  server-gamemode: survival
```

Lifesteal:

```yaml
network:
  server-id: lifesteal
  primary: false
gamemode-lock:
  server-gamemode: lifesteal
```

Other ids (a future `lobby`, a `survival-2` test server, ...) are allowed on any gamemode. The
plugin never derives the id from a world name and never generates a random one.

## Startup validation

On startup (and on `/joshymc reload`), JoshyMC logs:

```
[joshymc] Network Server ID: lifesteal
[joshymc] Server Gamemode: LIFESTEAL
[joshymc] Primary Network Instance: false
```

and checks what it can see locally:

| Problem | What happens |
|---|---|
| `server-id` missing or blank | Warning; the gamemode's id (`survival` / `lifesteal`) is used. |
| `server-id` invalid (spaces, symbols, too long, not text) | Error; the gamemode's id is used. The primary flag is kept. |
| `primary` missing or not `true`/`false` | Error; the server runs as a **secondary**, so a broken config never starts a second copy of the network-wide jobs. |
| `server-id` names a different gamemode (`survival` on a Lifesteal server, usually a copied config.yml) | Error; the server runs as a **secondary** until one of the two settings is fixed. |

`/jgamemode info` (or plain `/jgamemode`, for holders of `joshymc.jgm.lock` or
`joshymc.jgm.unlock`) shows the locks plus the active server id, gamemode and primary status.

### What the plugin cannot check

Each server only reads its own config file, so JoshyMC **cannot** tell whether two servers
use the same `server-id` or whether more than one (or none) is primary. Before deploying a
new server, make sure by hand that:

1. every server has its own `network.server-id`;
2. exactly one server has `network.primary: true` (Survival SMP for now);
3. a secondary either has no Discord bot, or `network.secondary-discord-bot: true` with its
   own bot token, never the primary's token;
4. `gamemode-lock.server-gamemode` matches the server.

## Primary-only services

| Service | On a secondary |
|---|---|
| Discord bot (chat bridge, staff alerts, reports, punishment forum logs, kill/mining/anti-dupe/combat feeds, slash commands, `/link` DMs, start/stop messages) | Not connected, unless `network.secondary-discord-bot: true`. `/link` replies that the bot is not connected. |
| Update highlights (the "JoshyMC Update vX" post) | Never posted, even with a secondary bot: every server runs the same release, so only the primary announces it. |
| LuckPerms Discord verification group sync (issue #1213) | Off. A secondary's own link table would otherwise remove the group again on a shared LuckPerms database. |

Switching `network.primary` with `/joshymc reload` is safe: the reload always stops the Discord
bot and its tasks first, and the services above read the flag when they start again, so a
server that stops being primary leaves no connection or task behind, and one that becomes
primary connects once.

## Local to every server

Everything else runs on each server, primary or not, because it serves that server's players
and data: gameplay, economy, shops, claims, punishments (bans, mutes and their enforcement),
in-game announcements and chat games, scoreboards, votes (Votifier listens on each server's own
port), Tebex purchase fulfillment, auto restarts, Grim bridge, resource pack hosting, quests,
events and the gamemode locks.

## Limitations of local SQLite storage

Primary checks only stop duplicate jobs; they do not move data between servers.

- **Punishments are per server.** A ban or mute on Lifesteal is stored in Lifesteal's database and
  enforced only there. Its Discord forum log is written to Lifesteal's own punishment outbox
  and stays there (durable, not lost) while that server has no bot; it is posted if that server
  later connects a bot. Posting it through the primary needs a shared store (deferred).
- **Discord links are per server.** Players link on the server where the bot runs (the primary).
  A secondary doesn't see those links, and doesn't sync the verification group.
- **Balances, inventories, ranks recorded by JoshyMC, quests, playtime** are all separate.
- **Discord messages from a secondary** without its own bot (chat, staff alerts) are not delivered.

These need shared network infrastructure (shared database or proxy messaging), which is out of
scope for this change.

## Testing

`./gradlew -I scripts/network-check.init.gradle.kts networkIdentityCheck` runs the Bukkit-free
config checks (`scripts/NetworkIdentityCheck.kt`): defaults, the Lifesteal config, normalization,
validation, missing values and gamemode conflicts.

Needs two real Paper servers (not automated): start Survival (primary) and Lifesteal
(secondary) with the same Discord token and confirm only Survival connects the bot and posts
update highlights; `/joshymc reload` after flipping `network.primary` on each and confirm the
bot connects/disconnects once; `/jgamemode info` on both.
