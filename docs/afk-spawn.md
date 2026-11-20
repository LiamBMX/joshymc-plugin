# AFK spawn (issue #1596)

`/afk` sends a player to the **AFK spawn** set by staff. Nothing else about AFK changed
(rewards, blindness, damage immunity, moving/chatting/commands to leave AFK, the return
teleport, the `afk` void world that's still created at startup).

## Command

| Command | Permission | Default |
|---|---|---|
| `/afk setspawn` | `joshymc.afk.setspawn` | op |

Stand where AFK players should appear and run `/afk setspawn`. It saves the world, X, Y, Z,
yaw and pitch of your exact position and repeats them in chat. The change is recorded in
`/admin logs` as `AFK_SETSPAWN`.

It's refused when the spot isn't safe: no solid floor under you (e.g. while flying), lava,
fire, magma or other hazards under or around you, blocks at your feet/head, outside the
world border or the build height. It's also refused while you're AFK yourself.

## Storage and gamemodes

The spawn lives in the SQLite table `afk_spawns` (`plugins/Joshymc/data.db`), one row per
gamemode, so it survives restarts and reloads. There's nothing to edit in `config.yml`.

The row is chosen by the gamemode of the world the player is in
(`gamemode-lock.server-gamemode`, or `gamemode-lock.worlds` for mapped worlds). Survival and
Lifesteal run on separate servers with separate databases, so each server has its own AFK
spawn; on a server hosting both, setting the Lifesteal spawn never touches the Survival one.

## What `/afk` does

1. Combat-tagged players and players held by a Ranked match are refused (they don't go AFK).
2. **No AFK spawn set** for that gamemode: the player goes AFK where they stand and is told
   no AFK spawn has been set. They're never sent to a guessed location.
3. **Saved world missing** (deleted/renamed/not loaded) or a damaged row: AFK in place,
   the player is told the AFK spawn isn't available, and the console gets a warning.
4. Otherwise the spawn's chunk is loaded (never generated), combat/ranked are checked again,
   and the spot is checked once more (floor, hazards, suffocation, border). Unsafe = AFK in
   place with the same message and a console warning. Safe = teleport, as before.

Players who went AFK in place have no return teleport; moving ends AFK as usual.

**Upgrade note:** before this change `/afk` always teleported to `afk` world `0.5 65 0.5`.
That spot is no longer used, so after updating each server needs one `/afk setspawn`
(e.g. on the bedrock block in the `afk` world) or players will go AFK in place.

## Checks

```
./gradlew -I scripts/afk-spawn-check.init.gradle.kts afkSpawnCheck
```
