# Lifesteal combat logging (issue #1350)

Combat tagging, combat-log detection and incident records for Lifesteal. Lifesteal only: Survival
(and Survival worlds on a server that hosts both) keeps exactly the behaviour it had.

Code: `src/main/kotlin/com/liam/joshymc/lifesteal/combat/`

| File | Role |
|---|---|
| `CombatLogRules.kt` | Bukkit-free: disconnect classification, enforcement decision, 1-heart guard, dedupe key; `CombatSessions` (opponents per tagged player) |
| `CombatLogStore.kt` | Bukkit-free SQLite store for incidents (`lifesteal_combat_logs`) |
| `LifestealCombatManager.kt` | Observer of the existing combat tag; notifications, quit decision, async incident writes, diagnostics data |
| `LifestealCombatListener.kt` | Kick cause (PlayerKickEvent) and quit cleanup |
| `LifestealCombatCommand.kt` | `/lifesteal combat ...` staff diagnostics |

## One combat tag

There is no second combat-tag framework. The tag is still `CombatManager`'s: the same hit
(`CombatListener.onDamageTag`, MONITOR + ignoreCancelled, attacker/shooter/TNT igniter resolved by
`resolvePlayerSource`), the same duration (`config.yml > combat.tag-duration-seconds`, default 15s;
the blueprint defines no duration, so none was invented), the same action-bar countdown, and the
same in-combat restrictions (flight, elytra, pearls, escape commands).

`CombatManager` gained an optional `TagObserver` (`tagObserver`, null by default). Only the
Lifesteal combat-log manager sets it, and only where the server hosts Lifesteal with a valid
`lifesteal.yml` and `combat-logging.enabled: true`. With no observer (Survival), every code path is
the old one: `tagPvp` = the two `tag` calls it replaced, and `shouldSpawnCombatLogNpc` is always true.

On Lifesteal the observer adds, per tagged player (`CombatSessions`, main thread, UUID keys only):
the session start, last hit, expiry, and up to 8 opponent UUIDs. A session ends when the tag runs
out, on death, on quit, or by a 30-second sweep (so a missed callback can't leak); at most 2000
sessions exist at once.

- **Messages** (`combat-logging.messages`, `&` codes, `notifications: true`): "&cYou are now in
  combat! Do not disconnect." once when a session starts (never on refresh hits), and "&aYou are
  no longer in combat." once when the tag runs out. Remaining time stays on the existing
  action-bar countdown; the scoreboard is untouched.
- **Friendly fire**: a teammate's hit is cancelled at HIGHEST by `LifestealTeamCombatListener`
  (issue #1348) and tagging runs at MONITOR with `ignoreCancelled`, so it never tags. Enemy hits tag
  normally. Environmental damage (fall, lava, mobs) never tags (no player source).
- **Teams**: joining, leaving, kicking or disbanding never touches the tag or the session, so the
  opponents and restrictions stay. Leaving/kicking/disbanding is already refused while tagged.

## Disconnect classification

Paper reports a quit reason (`DISCONNECTED`, `KICKED`, `TIMED_OUT`, `ERRONEOUS_STATE`) and, on the
kick event, a kick cause. It can't tell whether a disconnect was intentional: alt-F4, a client
crash and a proxy dropping the player all look like `DISCONNECTED`, and a frozen client, a pulled
cable and a Bedrock/NetherNet interruption all look like `TIMED_OUT`. `CombatLogRules.classify`
checks, in this order:

| Kind | When | Counted |
|---|---|---|
| `SERVER_SHUTDOWN` | `Bukkit.isStopping()`, the manager already stopping, or kick cause `RESTART_COMMAND` | never |
| `TRANSFER` | a `/lobby`/`/survival`/... transfer was already pending (requested before the tag; transfers are refused while tagged) | yes |
| `STAFF_KICK` | kick cause `KICK_COMMAND`, `BANNED`, `IP_BANNED`, `WHITELIST`, `PLUGIN` | never |
| `PLAYER_KICK` | any other kick cause (`FLYING_PLAYER`, `SPAM`, `INVALID_*`, `IDLING`, `DUPLICATE_LOGIN`, `RESOURCE_PACK_REJECTION`, `TIMEOUT`, `UNKNOWN`, ...), or `KICKED` with no kick event seen | yes |
| `TIMED_OUT` | quit reason `TIMED_OUT` | `count-timeouts` (default true) |
| `SERVER_ERROR` | quit reason `ERRONEOUS_STATE` | never |
| `QUIT` | everything else (`DISCONNECTED`) | yes |

Spoofing: a player can't choose a staff kick cause. Every cause a client can provoke counts.
`PLUGIN` is exempt because JoshyMC punishments and most plugin kicks use it; a plugin that kicks for
something the player triggers on purpose would also be exempt (limitation). An administrative
*transfer* doesn't exist (only players start transfers).

## Penalty policy

Blueprint v1.1 defines **no** combat-log penalty. So:

- **No heart is ever taken for combat logging.** `combat-logging.heart-penalty` accepts only `none`;
  anything else makes `lifesteal.yml` invalid (Lifesteal features refuse to run rather than guess).
  No hearts are created and no killer is rewarded. `CombatLogRules.heartsAfterPenalty` (the 1-heart
  guard) and `Enforcement.heartsToRemove` (always 0) are the single place a later approved penalty
  would go; it would have to run through `HeartStore` like every other heart change.
- **Counted** disconnects keep the combat-log NPC every server already had (the player dies, a
  villager holds their items for 60 seconds), unchanged unless `leave-npc: false`. That death has no
  causing player, so it never moves a heart, and killing the NPC doesn't either.
- **Not counted** (shutdown, staff kick, server error, timeouts with `count-timeouts: false`): no
  NPC, no death, no item drop on Lifesteal. The incident is still recorded.
- No elimination, no revival, nothing below 1 heart (fixed `LifestealRules`).

Death and disconnect never double up: a death clears the tag (`CombatListener.onDeath` →
`untag`), so a disconnect right after dying (or on the respawn screen) is not tagged and creates no
incident. Ordinary PvP heart stealing (issue #1341) is untouched.

## Incidents

`lifesteal_combat_logs` in the Lifesteal server's own `data.db` (never created on Survival):
`id` (UUID), `dedupe_key` UNIQUE (`combatlog:<boot>:<player>:<session>`), `player`, `opponents`
(UUIDs), `server_id`, `combat_started_at`, `last_hit_at`, `tag_expires_at`, `disconnected_at`,
`disconnect_kind`, `quit_reason`, `kick_cause`, `counted`, `penalty_status`, `npc_spawned`,
`heart_transaction_id` (always null while no penalty exists).

- Written on the database thread (`databaseManager.async`), `INSERT OR IGNORE`, so duplicate quit
  events and retries never make a second row. A failed write is logged and kept (last 50) for
  `/lifesteal combat failures`.
- No names, addresses, chat or session details are stored; Java and Bedrock (Floodgate) players
  are both plain UUIDs, never names.
- Retention: rows older than `retention-days` (90) are deleted at startup.
- Reconnects: the tag is removed on quit, so rejoining (even repeatedly) starts untagged and never
  creates or clears anything. The NPC inventory rule on rejoin is the existing one.
- Restarts: tags and sessions live in memory only, so a restart never resumes or re-applies
  anything; incidents written before stay.

## Shutdown

`lifestealCombatManager.stop` is a `ShutdownSequence` step (before `combatManager.stop` and the
database shutdown): it detaches the observer, so no new decision is made. Quits seen while the
server is stopping are `SERVER_SHUTDOWN`: never counted, no NPC. Already-queued incident writes
finish with the database shutdown (issue #1325).

## Transfers and teleports

Both were already enforced server-side for every gamemode, and stay so:

- **Transfers**: `TransferManager` refuses combat-tagged players (`TransferBlocker.COMBAT_TAGGED`)
  in the one central `request`, so `/lobby`, `/hub`, `/survival`, `/lifesteal` and any future
  selector GUI calling `transferManager.request` are covered, not only the commands. There is no
  transfer override (the `joshymc.transfer.bypass-cooldown` permission only skips the cooldown).
- **Teleports**: `/spawn`, `/home`, `/warp`, `/pwarp`, `/rtp`, `/tpa`, `/tpahere`, `/tpaccept` (for
  the requester), `/back`, `/top` go through `TeleportChecks.checkAndApply` (refused while tagged) and the
  warmup re-checks the tag before teleporting; `CombatListener` also blocks those commands and
  their namespaced forms while tagged. Server-controlled teleports (events, arenas, stuck recovery,
  staff `/tp`) aren't blocked. These rules are server-wide security and deliberately not settings.

## Settings (`lifesteal.yml > combat-logging`)

| Key | Default | |
|---|---|---|
| `enabled` | true | false = plain server-wide behaviour on Lifesteal (no records, NPC for every tagged quit) |
| `leave-npc` | true | counted combat logs leave the existing NPC |
| `count-timeouts` | true | timeouts count as combat logs |
| `heart-penalty` | none | only `none` (blueprint) |
| `notifications` | true | the two chat lines |
| `retention-days` | 90 | 1..3650 |
| `diagnostic-logging` | false | one console line per incident |
| `messages.tagged` / `messages.expired` | the issue's texts | `&` codes, not empty |

A missing section is one warning and the defaults (older files keep working).

## Staff diagnostics

`/lifesteal combat ...` (`joshymc.lifesteal.combat.admin`, op), read-only, Lifesteal servers only:

- `status <player>`: in combat or not, seconds left, session start, last hit, opponents, counted
  combat logs in the last 30 days.
- `logs [player]`: the 10 newest incidents.
- `incident <id>`: one incident (first 4+ characters of the id): opponents, server, times, kind,
  quit reason/kick cause, result, NPC, heart transaction.
- `failures`: incident writes that failed since the restart.

There's no command to apply, remove or change a penalty.

## Open decisions (not in blueprint v1.1)

1. Whether combat logging should cost a heart (and whether the last attacker would get it). Not
   implemented; `heart-penalty: none` is the only value.
2. Whether timeouts (including Bedrock network drops) should count. Default yes (as before).
3. Whether the combat-log NPC (death + 60s item hold) is wanted on Lifesteal. Kept (pre-existing).

## Testing

Automated (no server): `./gradlew -I scripts/lifesteal-combat-log-check.init.gradle.kts lifestealCombatLogCheck`
(`scripts/LifestealCombatLogCheck.kt`): settings (defaults, old files, refused penalties and bad
values), session start/refresh/expiry/end/sweep/bounds, Java and Bedrock UUIDs, every disconnect
kind (normal, timeout, staff and player-caused kicks, shutdown, transfer, server error), enforcement
(no heart ever removed, exempt kinds leave no NPC, the 1-heart guard), the store against real SQLite
(duplicates, 32 racing writes, restart persistence, retention, malformed rows, a closed database),
and source checks for the wiring (observer null on Survival, MONITOR/ignoreCancelled tagging,
friendly fire at HIGHEST, death clears the tag, Lifesteal-only registration, async writes,
shutdown step, transfer and teleport gates, op-only diagnostics).

Manual, on a real Paper 26.2 Lifesteal server behind Velocity (not done yet):

1. A hits B: both get the chat line once and the countdown; hitting again doesn't repeat it; after
   15s both get "no longer in combat".
2. Teammate hits (friendly fire off): no tag. Enemy hits from a bow: both tagged.
3. B quits while tagged: NPC appears, `/lifesteal combat logs` shows a COUNTED `disconnected`
   incident naming A; B's hearts unchanged; rejoining doesn't add another incident.
4. Staff `/kick B` while tagged: no NPC, B keeps items, incident "kicked by staff/server".
5. `/stop` while two players are tagged: no NPCs, no counted incidents.
6. A Bedrock player (Geyser/Floodgate) tagged then disconnecting (and losing Wi-Fi): incident with
   the Floodgate UUID, `disconnected` or `timed out`.
7. While tagged, `/lobby`, `/survival`, `/spawn`, `/home`, `/tpa`, `/warp`, `/rtp` are refused.
8. B dies, then quits on the respawn screen: no incident.
9. Survival server: tagging, NPC, messages unchanged; no `lifesteal_combat_logs` table.
