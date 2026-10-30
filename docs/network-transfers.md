# Velocity network and cross-server transfers (issue #1331)

This page covers the backend side of the future JoshyMC network: a Velocity proxy in front of
three Paper servers (Lobby, Survival SMP, Lifesteal SMP), and the `/lobby`, `/survival` and
`/lifesteal` commands that move players between them. Server identity (`network.server-id`,
`network.primary`) is in [network.md](network.md).

```
Velocity proxy (Java :25565, Geyser + Floodgate for Bedrock)
├── lobby       Paper + JoshyMC  (network.server-id: lobby)
├── survival    Paper + JoshyMC  (network.server-id: survival, primary)
└── lifesteal   Paper + JoshyMC  (network.server-id: lifesteal)
```

## What this repository implements, and what still needs deploying

| Implemented in JoshyMC | Still infrastructure work (not done by this change) |
|---|---|
| `network.enabled`, `network.proxy.type`, `network.servers.*`, `network.transfers.*` settings, all **off by default** | Installing and configuring Velocity |
| Startup validation and diagnostics of those settings | Creating the Lobby and Lifesteal Paper servers |
| `TransferManager`: one entry point for every transfer, with destination checks, cooldowns, duplicate prevention and gameplay safety checks | Moving Geyser/Floodgate from the Survival backend to the proxy |
| `/lobby` (`/hub`), `/survival`, `/lifesteal` | Firewalling the backends, DNS, ports |
| Bukkit-free checks: `./gradlew -I scripts/network-check.init.gradle.kts networkTransferCheck` | Real end-to-end transfer tests through a proxy (below) |

Nothing here has been tested through a real Velocity proxy yet. Until that's done, treat
cross-server transfers as untested in production.

**Nothing is shared between servers.** Each backend keeps its own worlds, inventories,
statistics, economy, progression, config and SQLite database. A transfer only asks the proxy
to connect the player to another backend; no inventory, balance or other data goes with them.

## How a transfer works

A transfer is the BungeeCord `Connect` plugin message (channel `BungeeCord` /
`bungeecord:main`) sent through the player's own connection. Velocity implements that
channel when `bungee-plugin-message-channel = true` (its default), so no custom protocol or
proxy plugin is needed. Two limits come with it:

- **It needs a connected player.** Messages travel over that player's connection, so nothing
  can be sent for an offline player, and nothing is sent from the console.
- **Velocity doesn't answer.** If the target server is down or not in `velocity.toml`, Velocity
  tells the player (or does nothing) and they stay where they are. JoshyMC waits
  `network.transfers.timeout-seconds` and then tells the player the destination isn't reachable,
  and logs it.

From this backend's point of view the player then simply logs out: every quit handler runs
exactly as on a normal disconnect, and the destination loads its own data for them.

## Settings (`config.yml > network`)

| Key | Default | Meaning |
|---|---|---|
| `network.enabled` | `false` | This server runs behind the proxy. Off = standalone, exactly as before. |
| `network.proxy.type` | `velocity` | Only `velocity` is supported. Anything else turns transfers off (logged). |
| `network.servers.lobby` / `.survival` / `.lifesteal` | `lobby` / `survival` / `lifesteal` | The proxy server name (the key in velocity.toml's `[servers]`) of each destination. Letters, digits, `-`, `_`, at most 64; never an address. Empty (`""`) = not deployed yet. |
| `network.transfers.enabled` | `false` | Allow `/lobby`, `/survival`, `/lifesteal`. Needs `network.enabled` too. |
| `network.transfers.cooldown-seconds` | `5` | Seconds between two transfers by the same player (0-3600). Starts when a transfer is sent. |
| `network.transfers.timeout-seconds` | `10` | After this long, a transfer that didn't move the player is reported as failed (3-60). |

All of these follow `/joshymc reload` (the server id does not; see network.md).

### Validation

Checked at startup and on reload, logged with a `[Network]` prefix. Player messages stay short.

| Problem | Result |
|---|---|
| `enabled` / `transfers.enabled` not `true`/`false` | Error; treated as `false`. |
| `proxy.type` not `velocity` | Error; transfers disabled. |
| A server name that is an address, hostname, has spaces/dots/colons, or is too long | Error; **that destination** is unavailable. Never a fallback to another one. |
| Two destinations pointing at the same proxy server | Error; both are unavailable. |
| An unknown key under `network.servers` | Warning; ignored. Only the three destinations exist. |
| Transfers on but nothing configured, or `transfers.enabled` without `network.enabled` | Error / warning. |
| `config/paper-global.yml` has `proxies.velocity.enabled: false` | Error; transfers refused until the backend is set up for the proxy. Only that flag is read, never the secret. |
| Geyser-Spigot installed on this backend | Warning: Bedrock players joining through it bypass the proxy and can't be transferred. |
| Another plugin owns `/lobby`, `/hub`, `/survival` or `/lifesteal` | Warning; JoshyMC's command is still `/joshymc:<name>`. |

On a standalone server (the default) the only line is
`[Network] Network mode is off (network.enabled: false); cross-server transfers are disabled on 'survival'.`
and the plugin channel is not even registered.

## Commands and permissions

| Command | Permission (default) | Destination |
|---|---|---|
| `/lobby`, `/hub` | `joshymc.transfer.lobby` (true) | `network.servers.lobby` |
| `/survival` | `joshymc.transfer.survival` (true) | `network.servers.survival` |
| `/lifesteal` | `joshymc.transfer.lifesteal` (true) | `network.servers.lifesteal` |

`joshymc.transfer` grants all three; `joshymc.transfer.bypass-cooldown` (op) skips the
cooldown. Negate `joshymc.transfer.lifesteal` to hide `/lifesteal` until Lifesteal opens, or
leave `network.servers.lifesteal` empty. All three are NETWORK-scope commands (they work on
every gamemode, see gamemode-scopes.md). They're players only. None of the names were taken
by an existing JoshyMC command; if a lobby or proxy plugin also registers them, the startup log
says so.

The future lobby selector (GUI, NPCs, ...) should call
`plugin.transferManager.request(player, TransferDestination.X)` so it gets the same checks.

## Checks before a transfer is sent

In this order, first failure wins (`TransferRules.decide`):

1. Player only, then the destination's permission.
2. Network mode on, transfers on, a supported proxy, channel registered, backend not known to be
   off the proxy.
3. The player is connected through the proxy: Velocity registers `bungeecord:main` on every
   backend connection it makes, so a player whose connection doesn't list it (a direct join, a
   Bedrock player on a backend Geyser) is refused instead of sending a message that goes nowhere.
4. The destination is configured, and isn't this server (`network.server-id` equals the
   destination id or its proxy server name).
5. The server isn't shutting down.
6. No transfer already pending for the player (duplicates and overlapping requests are dropped).
7. Cooldown.
8. Gameplay blockers, from the systems that own them: dead (death screen), combat-tagged
   (`CombatManager.isTagged`, so a transfer can't be used to combat log), in the event world
   (its separate inventory is only restored when leaving it), frozen by staff, in a trade.

Then the player's open inventory is closed, so GUIs that save on close (vaults, Overflow, shops,
Sell GUI) settle on this server first, and the message is sent.

### What relies on normal logout handling

Leaving through the proxy is a disconnect from this backend, so these keep exactly the
guarantees they already have for a logout, and no new code was added for them: Auction House
and Buy Orders (a transfer can only start from a command, after any click has finished, and
the open GUI is closed first), player data saving (Paper saves the player file on quit;
JoshyMC's quit handlers persist as usual), scheduled rewards (AFK rewards, playtime and quest
progress are per server and stop at quit), casino/coinflip games and other sessions (whatever
their quit handling does on a logout). A transfer itself makes no database call.

### Needs future session management (documented, not implemented)

- **No cross-server session lock.** Each server still treats a join as a fresh login. Velocity
  already prevents one account being online twice on the network, which is what keeps sessions
  consistent today.
- **No "send everyone to the lobby" on restart.** Use Velocity's `try` list and
  `failover-on-unexpected-server-disconnect = true` so players kicked by a restart land in the
  lobby. A backend-driven version would need the proxy to be told before shutdown.
- **No server status / player counts.** The lobby selector's "is it online?" display needs a
  ping or proxy query; the transfer itself already fails safely when the server is down.

## Deployment guide

### 1. Velocity proxy (`velocity.toml`)

```toml
bind = "0.0.0.0:25565"
online-mode = true                       # the proxy authenticates Java players
player-info-forwarding-mode = "modern"   # forwards the real UUID, name and skin to backends
forwarding-secret-file = "forwarding.secret"
bungee-plugin-message-channel = true     # needed by /lobby, /survival, /lifesteal

[servers]
lobby = "127.0.0.1:30066"
survival = "127.0.0.1:30067"
lifesteal = "127.0.0.1:30068"
try = ["lobby"]

[forced-hosts]
# optional: "survival.example.net" = ["survival"]

[advanced]
failover-on-unexpected-server-disconnect = true
```

The `[servers]` keys are what `network.servers.*` on every backend must contain. Keep
`forwarding.secret` out of git and chat; never paste it into a JoshyMC config.

### 2. Every Paper backend

`server.properties`:

```properties
online-mode=false          # the proxy authenticates; the backend trusts modern forwarding
server-ip=127.0.0.1        # or a private address; never reachable from the internet
server-port=30067
```

`config/paper-global.yml`:

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: '<contents of the proxy forwarding.secret>'
```

`spigot.yml`: `settings.bungeecord: false` (modern forwarding replaces it).

Backend security: with `online-mode=false`, a backend reachable from the internet would let
anyone join as anyone. Bind backends to localhost or a private network and firewall their
ports so only the proxy can reach them. Modern forwarding's secret is what proves a connection
came from your proxy.

`plugins/Joshymc/config.yml` on each backend:

```yaml
network:
  server-id: survival        # lobby / survival / lifesteal: different on every server
  primary: true              # true on exactly one server (Survival)
  enabled: true
  proxy:
    type: velocity
  servers:
    lobby: lobby
    survival: survival
    lifesteal: lifesteal     # "" until Lifesteal is deployed
  transfers:
    enabled: true
    cooldown-seconds: 5
    timeout-seconds: 10
gamemode-lock:
  server-gamemode: survival  # or lifesteal
```

### 3. Java Edition routing

Java players connect to the proxy address. Velocity authenticates them with Mojang
(`online-mode = true`) and forwards the real UUID; the backend uses that UUID unchanged, so
every JoshyMC table keyed by UUID keeps matching. **If forwarding is misconfigured the backend
would make offline-mode UUIDs and every player would look new**: check the first join on each
backend (balance, ranks, claims) before opening the network.

### 4. Bedrock Edition routing, Geyser and Floodgate

Today Geyser-Spigot and Floodgate run on the Survival backend. **This change leaves that
untouched** (same Bedrock port, NetherNet settings, Bedrock resource pack).

Recommended arrangement once the network goes live:

- **Geyser-Velocity on the proxy**, listening on the existing Bedrock port, `remote.address`
  pointing at the proxy itself, `auth-type: floodgate`. Remove Geyser from the backends:
  a backend Geyser connects Bedrock players straight to that backend, bypassing the proxy,
  so they couldn't change servers (JoshyMC refuses with "only works when you join through the
  network address").
- **Floodgate on the proxy and on every backend**, with the **same `key.pem`** everywhere and
  `send-floodgate-data: true` on the proxy. The proxy's Floodgate gives Bedrock players their
  UUID; Velocity's modern forwarding carries it to each backend, so a Bedrock player has the
  same UUID on Lobby, Survival and Lifesteal. JoshyMC never generates or changes UUIDs.
- **Keep Floodgate's `username-prefix` and linking settings as they are now.** Floodgate derives
  Bedrock UUIDs from the Xbox id the same way on the proxy as on the backend, so moving
  Floodgate keeps existing Bedrock players' data, but changing the prefix or account linking
  would change names/UUIDs.
- Only one Geyser per player path: never run Geyser on both the proxy and a backend for the
  same port, or Bedrock players get two sessions.
- **Bedrock custom items**: JoshyMC writes its Bedrock pack and mappings into Geyser's folder
  (issue #1314). With Geyser on the proxy, set `bedrock-pack.geyser-folder` on the Survival
  server to the proxy's Geyser folder if they share a disk, or copy
  `plugins/Joshymc/bedrock/` to the proxy's Geyser `packs/` and `custom_mappings/` after each
  update, then restart the proxy.

Moving Geyser is a separate, deliberate migration step: plan a maintenance window and keep the
current setup as the rollback.

### 5. Activation order

1. Back up every server folder (worlds, `plugins/Joshymc/data.db`).
2. Start Velocity with the config above; start each backend configured for the proxy, with
   `network.enabled: true` and `network.transfers.enabled: false`.
3. Join through the proxy, check UUID-keyed data (balance, rank, claims) on Survival for a Java
   and a Bedrock account.
4. Turn on `network.transfers.enabled` on each backend, `/joshymc reload`, and check the
   `[Network] Cross-server transfers enabled ...` line lists the right routes.
5. Run the transfer tests below.

### 6. Transfer tests (need a real proxy, not automated)

- `/lobby`, `/survival`, `/lifesteal` from each server, Java and Bedrock: arrives at the right
  server, same UUID, that server's own inventory and balance.
- `/survival` on Survival: "You're already on Survival SMP."
- Repeat a command within the cooldown: refused with the seconds left.
- While combat-tagged, dead, in the event world, frozen, trading: refused.
- Stop the Lifesteal backend, `/lifesteal`: Velocity's message or, after the timeout, "isn't
  reachable right now"; the player stays put and keeps their items.
- Remove `lifesteal` from velocity.toml, `/lifesteal`: same as above.
- Join a backend directly (not through the proxy) on a test setup: refused.
- Restart a backend with players on it: they fail over to the lobby (`try` list).

### 7. Rollback

Network mode never changes or migrates data, so rollback is configuration only:

1. Set `network.enabled: false` (and `network.transfers.enabled: false`) on the Survival
   server; `/joshymc reload` or restart. The commands answer "Server switching isn't available
   on this server." and no channel is registered.
2. Restore `server.properties` `online-mode=true`, `server-ip`/`server-port` to the public
   values, `proxies.velocity.enabled: false`.
3. If Geyser was moved, put Geyser-Spigot back on Survival with its original config.
4. Players connect to Survival directly again, with the same UUIDs and data.

## Automated checks

`./gradlew -I scripts/network-check.init.gradle.kts networkTransferCheck` runs
`scripts/TransferCheck.kt`: the bundled config is standalone and leaves the server identity as
it was, network-disabled refusals, destination resolution and validation (addresses,
hostnames, duplicates, unknown keys, blank entries), unsupported proxy types, missing proxy /
direct joins, current-server detection, permission and player-only order, shutdown, pending
duplicates, cooldowns, every gameplay blocker, tracker cancellation/timeout/quit, the `Connect`
message bytes, and the plugin.yml commands, permissions and scopes (existing commands kept).
