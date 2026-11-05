# Lifesteal closed beta readiness (issue #1369)

Status of the Lifesteal core gameplay loop (join → gather → team up → fight → steal hearts →
survive → progress through the season), what issue #1369 fixed, and what still has to be checked
by hand. Everything below comes from reading the merged code and running the automated checks.
**No live multiplayer test has been run.**

## A. Core gameplay status

| Feature | Status | Notes |
|---|---|---|
| Permanent hearts (10 start, 20 max, 1 min, `MAX_HEALTH` base value) | COMPLETE | `lifesteal/hearts/`, [lifesteal-hearts.md](lifesteal-hearts.md). Changes are committed before they're applied, with versioned conditional updates and a UNIQUE dedupe key per death. |
| Heart stealing (1 per kill, nothing from a 1-heart victim, 30 min pair cooldown) | COMPLETE | `HeartRules.decide` + `HeartStore.applyPvp`, one transaction. Killer at the maximum gets nothing (`KILLER_AT_MAXIMUM = NO_TRANSFER`, still an open blueprint decision). |
| No elimination / no revival | COMPLETE | Fixed rules in `LifestealRules`. |
| Heart withdrawal + redemption | COMPLETE | `/withdrawheart`, right-click. Serial-numbered items, ISSUED→REDEEMED once. #1369: Crafters and beacon payment slots can no longer use a Heart item up. |
| Lifesteal teams (create/invite/accept/decline/leave/kick/transfer/disband/info, max 5) | COMPLETE | `lifesteal/teams/`, [lifesteal-teams.md](lifesteal-teams.md). #1369: kick/transfer by name never guesses between two members with the same cached name. |
| Team PvP (friendly fire off, no teammate heart transfers) | COMPLETE | `LifestealTeamCombatListener`, `heartCheck`. #1369: the Survival `/team pvp` toggle no longer cancels damage in Lifesteal worlds. |
| Combat tag + teleport/transfer restrictions | COMPLETE | One tag (`CombatManager`). On a dedicated Lifesteal server every Survival-scoped teleport (`/home`, `/warp`, `/rtp`, `/back`, ...) is locked away. `/spawn`, `/tpa*`, `/unstuck`, `/afk` and warmups check `CombatTeleportGuard` on every step. `/lobby`, `/survival` and `/lifesteal` transfers refuse tagged players. Lifesteal teams have no `/team home`. #1373: admin-built custom portals (`/portal`) now refuse tagged players too, on entry and again when the 1s portal animation ends. |
| Combat logging | COMPLETE | `lifesteal/combat/`. No heart penalty (the blueprint defines none). Shutdowns, staff kicks and bans are never counted. #1369: JoshyMC anticheat kicks now count, so tripping a check on purpose no longer skips the combat-log NPC. |
| Launch progression (grace 24h, Ranked +72h, End +168h) | COMPLETE | `lifesteal/progression/`. Stored launch time, never auto-starts or resets. |
| Season length (3 months) | PARTIAL (by design) | `seasons.length-months` is validated, but there is no automatic season end or reset, and none is planned for this beta. |
| Player info/help | COMPLETE (#1369) | New `/lifesteal info` (alias `help`): your hearts, the heart rules, the season phase and the commands. Read only. |
| Ranked Team Wars (arenas, lifecycle, inventory snapshots, queue, Elo, stats) | IMPLEMENTED, NOT LIVE-TESTED → **closed to players** | #1369 adds `lifesteal.yml > ranked-team-wars.enabled` (default **false**, a missing key counts as false). While it is false, `/ranked` refuses players and is hidden from their tab completion, and the progression reports Ranked as "unlocked by schedule" but not playable. Staff with `joshymc.lifesteal.ranked.admin` keep arenas, test matches and `/ranked` for testing. |
| Java + Bedrock | COMPLETE in code, needs a manual check | Every Lifesteal table is keyed by UUID, including Floodgate UUIDs. Heart items are plain Nether Stars on Bedrock, since the Bedrock pack never maps them. |
| Gamemode isolation | COMPLETE | Heart, team, combat-log, progression and ranked listeners/tables only exist where Lifesteal is hosted. Every handler checks the world's gamemode. Server-local SQLite, no inventory sync. |

Blocked: nothing in the core loop. Ranked only waits on live testing.

## B. Fixes in issue #1369

| Fix | Files | Why |
|---|---|---|
| Ranked master switch, off by default | `LifestealConfig.kt`, `lifesteal.yml`, `ranked/queue/LifestealRankedQueueManager.kt`, `ranked/queue/RankedCommand.kt`, `ranked/LifestealRankedListener.kt` | Before this fix, passing +72h opened `/ranked` to everyone because matchmaking defaulted to on. The issue requires it to stay unavailable until it has been tested and explicitly enabled. |
| Anticheat kicks use `ILLEGAL_ACTION` | `manager/AntiCheatManager.kt` | The default `PLUGIN` cause is treated as a staff kick, so a tagged player who tripped a check on purpose left without a combat-log NPC. |
| Survival team PvP skips Lifesteal worlds | `manager/TeamManager.kt` | On a server hosting both modes, two Survival teammates on different Lifesteal teams couldn't fight (or steal hearts) in Lifesteal. |
| Heart items refused by Crafters and beacons | `lifesteal/hearts/LifestealHeartListener.kt` | Both used the Nether Star up, destroying the heart and leaving its serial ISSUED forever. |
| Name lookup for kick/transfer | `lifesteal/teams/LifestealTeamManager.kt` | A stale cached name could make `/team kick <name>` or the GUI act on the wrong member. |
| `/lifesteal info` | `lifesteal/LifestealInfoCommand.kt`, `command/ServerTransferCommand.kt`, `plugin.yml` | Players had no way to see their own heart count or a list of the Lifesteal commands. |
| Stale combat-log check | `scripts/LifestealCombatLogCheck.kt` | It looked for teleport messages that #1356 had moved into `CombatTeleportRules`, so the suite already failed on `main`. |

## C. Remaining beta risks (not fixed here)

- **Grim kicks.** Grim's `punishments.yml` commands may kick with a staff cause (`KICK_COMMAND`). That would make a Grim kick an uncounted combat log, the same gap the anticheat fix closes. Check what the installed Grim config runs.
- **`count-timeouts: false`.** Paper keepalive timeouts that come through `PlayerKickEvent` (cause `TIMEOUT`) still count. This only matters if staff set the option to false; the default is true.
- **Open gameplay decisions** (blueprint, deliberately not invented):
  - killer at the maximum gets nothing;
  - teammate heart transfers stay off (`teams.friendly-fire.heart-transfers`);
  - no combat-log heart penalty;
  - ranked forfeits have no heart penalty.
- **Attribution gaps.** Kills by lightning, pets or other indirect sources the game doesn't credit to a player steal no heart. This is documented in [lifesteal-teams.md](lifesteal-teams.md).
- **Main-thread SQLite.** Heart and progression reads/writes on join, death and commands are a few indexed rows on the main thread, matching the plugin's existing pattern. Watch the TPS during the beta.

## D. Testing

Automated, run for this change: `./gradlew build` plus these suites, all passing:
`lifestealCheck`, `lifestealHeartCheck`, `lifestealTeamCheck`, `lifestealTeamCombatCheck`, `lifestealCombatLogCheck`, `lifestealProgressionCheck`, `lifestealRankedCheck`, `lifestealRankedQueueCheck`, `lifestealRankedEloCheck`, `lifestealRankedStatsCheck`, `combatTeleportCheck`, `gamemodeScopeCheck`, `shutdownCheck`, `networkIdentityCheck`, `networkTransferCheck`.
They cover:
- heart start/limits/stealing/cooldown/withdraw/redeem, duplicate deaths and rollback;
- teams, size limit and friendly fire;
- combat tags, teleports, disconnect classification and shutdown;
- grace period and the End unlock;
- restart persistence (fresh database handles);
- isolation and Floodgate-style UUIDs.

These need a real server (manual): actual damage/death events, respawn health, portals, Geyser clients, proxy transfers.

## E. Closed beta checklist

Run on the Lifesteal server with at least two Java players and one Bedrock player.
Before starting, `/jgamemode info` should show Lifesteal valid. Schedule the launch with `/lifesteal admin launch schedule <time> confirm`.

1. A new player joins. `/lifesteal info` shows 10 / 20 hearts, and the health bar shows 10 hearts.
2. Mine, craft and smelt normally (nothing Lifesteal-specific blocks gathering).
3. `/team create <name>`. Invite a second player, who accepts. Invite a third, who declines. `/team` shows the roster.
4. Restart the server. The team, its members and its leader are unchanged. Hearts are unchanged.
5. During grace (`/lifesteal progress` shows it), hits between players do nothing and nobody is combat-tagged.
6. After grace (or with a short test schedule), hitting an enemy tags both players, and `/spawn`, `/tpa` and `/lobby` are refused while tagged. Teammates can't hurt each other, including with arrows and splash potions.
7. An enemy kill moves 1 heart. Killing the same player again within 30 minutes moves nothing.
8. Kill a player at 1 heart. They stay at 1, and the killer gains nothing.
9. `/withdrawheart` at 1 heart is refused. At 2+ hearts, you lose 1 heart and get a Heart item. Right-clicking it gives the heart back. A copy of the same item (creative middle-click) is refused. The item can't be put into a Crafter or a beacon.
10. Log out while tagged. A combat-log NPC appears and an incident shows in `/lifesteal combat logs`. A `/stop` while tagged leaves no NPC and no counted incident.
11. Disconnect and reconnect. Hearts, team and inventory are unchanged.
12. Before +168h an End portal refuses entry. After it (or with a short schedule), the End opens.
13. `/ranked` is refused and isn't tab-completed for players. The ranked unlock announcement says Ranked aren't ready yet.
14. Bedrock player: the hearts show on the health bar, `/team` and the team menu work, and a Heart item can be withdrawn and redeemed.
15. `/survival` → back to `/lifesteal` (behind Velocity). Survival inventory, teams and health are untouched by Lifesteal, and vice versa.
