# Lifesteal closed beta: release candidate 1 (issue #1567)

The filled-in RC record ([lifesteal-beta-templates.md](lifesteal-beta-templates.md) section 1)
for the first Lifesteal closed beta, following [lifesteal-beta-release.md](lifesteal-beta-release.md)
section 1. It records **automated evidence only**. Staging, restart, crash and soak results
(sections 2–4 of the release guide) are still empty because none of them has been run yet.

## Lifesteal beta RC: v1.0.677

**Prepared by / date (time zone):** Claude (automated, issue #1567) / 2026-11-16 (UTC)
**Tag / commit SHA:** `v1.0.677` / `b9457dabd094917001f272a5a689b0cd98c6c2a4` (the `main` head when this was prepared)
**Currently deployed on production:** none (first Lifesteal beta deployment)
**Jar:** `joshymc-1.0.677.jar` (32,006,117 bytes), SHA-256
`4e9acc060653806c938e2e8989892cf13db8412075be093644fd4a186730012d`, `sha256sum` matches the
published `joshymc-1.0.677.jar.sha256`: **yes**. The jar's `plugin.yml` says `version: '1.0.677'`,
`api-version: '26.2'`.
**Artifact location:** GitHub Release `v1.0.677`
(<https://github.com/LiamBMX/joshymc-plugin/releases/tag/v1.0.677>), built by the Release
workflow run <https://github.com/LiamBMX/joshymc-plugin/actions/runs/43785567885> from that
commit. Download it there; never use a local `jar/` build (it says `1.0-SNAPSHOT`).
**Paper version on staging / production:** not recorded yet (Paper 26.2 required, Java 25 runtime)
**Companion plugin versions (LuckPerms, WorldGuard, Grim, Geyser, Floodgate):** not recorded yet

> If a later tag is picked instead (each merged PR is auto-released), this record doesn't apply
> to it: re-run section 1.3 on that tag and record its own checksum.

### Changes since the deployed build

None deployed yet, so there is no range. The Lifesteal fixes the final blocker review (issue
#1566) asked about, checked against `main`:

| Fix | PR | On `v1.0.677`? |
|---|---|---|
| Heart remediation V2 (#1537) | #1543 | yes |
| Progression remediation V2 (#1538) | #1544 | yes |
| Economy security remediation (#1548: welcome reward Survival-only, combat-time bank/bounty shielding, Combat NPC bounties, ranked coins) | #1549 | yes |
| Event world inventory crash safety (#1550, audit INV-01..03) | #1555 | yes |
| Staff flight/speed + Lifesteal rank kits fail closed (#1551, audit STF-01, ISO-04) | #1554 | yes |
| Stale progression/ranked/rank-perk checks (#1552, audit CHK-01..03) | #1553 | yes |
| Ranked portal escape / portal entry (audit #1546 RNK-01 P1, RNK-02 P2) | none | **no**: no `PlayerPortalEvent`/`PortalCreateEvent` handler under `lifesteal/ranked/` |
| Past `progression.launch-timestamp` skips grace (audit #1546 WP-01 P2) | none | **no**: the config seed still accepts a past time |

**Required fixes from the previous known-issues list included:** none required (first RC).
The two open items are covered by restrictions below instead of a fix.

### Automated checks on this commit

Run on a clean checkout of `b9457dab` (Java 21 toolchain, Linux). These are source-level
checks: they compile the plugin's classes and run Bukkit-free rules and SQLite stores in temp
directories. **No Lifesteal server was started for them.**

| Task | Result | Note |
|---|---|---|
| `./gradlew clean build` | PASS | deprecation warnings only |
| Release workflow `build` job | PASS | built `-PreleaseVersion=1.0.677`, published jar + `.sha256` |
| Release workflow Paper startup smoke test | PASS | booted Paper 26.2 with this exact jar and checked `Enabling joshymc v1.0.677`, no enable error. **Bundled default config (Survival)**, not a Lifesteal config, no players |
| `lifestealCheck` (dedicated server bootstrap) | PASS | 434 |
| `lifestealHeartCheck` | PASS | 1292 |
| `heartTransferMonitorCheck` | PASS | 121 |
| `competitiveEligibilityCheck` | PASS | 168 |
| `lifestealCombatLogCheck` | PASS | 197 |
| `lifestealCombatNpcCheck` | PASS | 211 |
| `lifestealBankCheck` | PASS | 196 |
| `lifestealBountyCheck` | PASS | 184 |
| `lifestealEconomySecurityCheck` | PASS | 138 |
| `lifestealTeamCheck` | PASS | 268 |
| `lifestealTeamCombatCheck` | PASS | 679 |
| `lifestealAllianceCheck` | **FAIL on `v1.0.677`**, PASS after the check fix (213) | false positive, see below |
| `lifestealTeamProgressionCheck` | PASS | 181 |
| `lifestealProgressionCheck` | PASS | 165 |
| `lifestealQuestCheck` | PASS | 6231 |
| `lifestealProfileCheck` (achievements) | PASS | 241 |
| `lifestealRankCheck` | PASS | 239 |
| `lifestealSocialCheck` | PASS | 151 |
| `lifestealDiagnosticsCheck` | PASS | 93 |
| `lifestealRankedCheck` (matches, settlement) | PASS | 587 |
| `lifestealRankedQueueCheck` | PASS | 265 |
| `lifestealRankedTestMatchCheck` | PASS | 96 |
| `lifestealRankedEloCheck` | PASS | 237 |
| `lifestealRankedStatsCheck` | PASS | 166 |
| `rankedInventoryCheck` | PASS | 109 |
| `rankedIntegrityCheck` | PASS | 122 |
| `rankedArenaGuardCheck` | PASS | 114 |
| `rankedArenaAccessCheck` | PASS | 89 |
| `rankedSpectateCheck` | PASS | 59 |
| `rankedTeamLockCheck` | PASS | 76 |
| `rankedBetaReadiness` | PASS | 0 doc errors, 1 warning: `ranked-team-wars.enabled` is false (intended) |
| `eventInventoryCheck` | PASS | 491 |
| `combatTeleportCheck` | PASS | 52 |
| `staffPermissionCheck` | PASS | 240 |
| `rankSecurityCheck` | PASS | 1628 |
| `rankPerkCheck` | PASS | 354 |
| `gamemodeScopeCheck` | PASS | 2092 |
| `vanishCheck` | PASS | 22 |
| `tutorialCheck` | PASS | 146 |
| `worldAdminCheck` | PASS | 62 |
| `shutdownCheck` (shutdown + DB close) | PASS | 45 |
| `networkIdentityCheck` | PASS | 31 |
| `networkTransferCheck` | PASS | 145 |
| `serverConnectionCheck` | PASS | 22 |

**`lifestealAllianceCheck` failure, explained.** The one failing assertion was "rating code
doesn't read rivalries". It searched every file under `lifesteal/ranked/` for the substring
`rival`, which now matches the word *arrival* (`RankedArenaAccess.entityArrivalRefused` from
#1524, `RankedSpectateRules.arrivalProblem` from #1511). No ranked code reads rivalries. The
assertion now matches `rival` only as its own word or camelCase part, so a real reference
(`rivalOf`, `isRival`, `RIVAL`) still fails it. Only `scripts/` changed, which isn't in the jar, so
the `v1.0.677` artifact is unaffected.

### Configuration for a dedicated Lifesteal server

Exact keys; the values are what the beta needs. Set them before the first start, on the
Lifesteal server's own copy. Details: [lifesteal-server.md](lifesteal-server.md),
[network.md](network.md), [network-transfers.md](network-transfers.md),
[lifesteal-progression.md](lifesteal-progression.md). No credentials belong in this record.

| File > key | Value | Why |
|---|---|---|
| `config.yml > gamemode-lock.server-gamemode` | `lifesteal` | dedicated Lifesteal server |
| `config.yml > gamemode-lock.worlds` | `{}` | no world mapped to Survival, or the server isn't dedicated and Survival features start (audit ISO-01..03: **mixed servers aren't supported for the beta**) |
| `config.yml > network.server-id` | `lifesteal` | different from Survival's `survival` |
| `config.yml > network.primary` | `false` while Survival is the primary (Discord bot there); `true` only if Lifesteal runs alone | exactly one primary |
| `config.yml > network.enabled` / `network.transfers.enabled` | `false` unless behind the Velocity proxy (then see network-transfers.md) | standalone by default |
| `config.yml > resource-pack.port` | a port the host forwards to the internet (default `8163`) | the plugin serves the pack itself |
| `config.yml > resource-pack.host` | the server's **public** host name or IP | empty means the URL is `http://localhost:<port>/pack.zip`, which players can't download (the pack is required) |
| `config.yml > auto-restart.enabled` / `interval-hours` | record the chosen values (defaults `true` / `12.0`) | a restart in a test session must not be a surprise |
| `lifesteal.yml > progression.enabled` | `true` | |
| `lifesteal.yml > progression.launch-timestamp` | `""` (**must stay empty**) | WP-01: a past value skips grace / opens the End for good. Launch with `/lifesteal admin launch schedule <ISO-8601 with offset> confirm` or `activate confirm` instead |
| `lifesteal.yml > ranked-team-wars.enabled` | `false` | public Ranked stays closed (RNK-01, below); staff TEST matches still work |
| `lifesteal.yml > ranked-team-wars.matchmaking.enabled` | `true` (has no player effect while the key above is false) | |
| `lifesteal.yml > heart-items.withdraw-eligibility.enabled` | `true` | |

**Separate database and player data.** The Lifesteal server has its own server folder: its own
`plugins/Joshymc/` (`data.db`, `playerdata.db`, every `.yml`), its own world folders (vanilla
inventories and ender chests live in the main world's `playerdata/`) and its own LuckPerms
context or storage. Never copy Survival's `data.db` or worlds onto it; nothing is shared between
the two servers (docs/network.md).

**WorldGuard.** Spawn protection on Lifesteal is WorldGuard's, not JoshyMC's (issue #673).
Before opening, regions must cover spawn in the Lifesteal Overworld, Nether and End (build,
chest access, TNT/creeper explosions, fluid flow, ender pearls, chorus fruit, portal creation).
Ranked arenas in use are guarded by JoshyMC; idle arenas and the open world are WorldGuard's.

### Known restrictions for this RC

1. **Dedicated server only** (`server-gamemode: lifesteal`, no Survival world). Mixed deployments
   aren't ready (audit #1546 ISO-01/02/03, WP-03).
2. **Public Ranked stays off** (`ranked-team-wars.enabled: false`) until RNK-01/RNK-02 are fixed:
   a participant can leave an arena through a nether/End portal and the match doesn't notice.
   Staff TEST matches are allowed only in arenas checked to contain no obsidian or portal frame;
   treat a portal exit as a test case and report it.
3. **`progression.launch-timestamp` stays empty**; launch by command (WP-01).
4. Staff LuckPerms review before opening: no wildcards below the intended staff level; who holds
   `joshymc.lifesteal.progression.admin` and `joshymc.lifesteal.progression.end-bypass`
   (audit #1546 WP-04/WP-05).
5. The remaining risks in [lifesteal-beta.md](lifesteal-beta.md) section C (Grim kick causes,
   attribution gaps, main-thread SQLite) still apply; watch for them during the session.

### Resource pack
- Hash logged on staging: not recorded yet (look for `Resource pack server started on port <port> (hash: <sha1>)`)
- Java client checked / Bedrock client checked: no / no

### Staging
- Session log link: none yet
- S1..S15 result summary: **not run**
- Restart checklist (3.2) / crash tests (3.3) / soak log: **not run**

### Deployment
- Not deployed. Fill in from release guide section 1.8 when it happens.

## Live tests the Owner still has to run (PebbleHost)

Short version of the existing steps; each line points to where the pass criteria are written.

| # | Test | Where |
|---|---|---|
| 1 | First startup: clean log, `version joshymc` = `1.0.677`, `/jgamemode info` shows Lifesteal and `lifesteal.yml` valid, then stop and start again just as clean | release guide 1.8 step 6, S1 |
| 2 | Player join: starting hearts, resource pack loads (Java + one Bedrock), `/lifesteal info` | S2; [lifesteal-beta.md](lifesteal-beta.md) E1, E2 |
| 3 | Permanent hearts: withdraw/redeem a Heart item, copies refused, 1-heart minimum, 20-heart maximum | S4; E7–E9 |
| 4 | PvP and heart transfers: grace first, then kill, same-victim cooldown | S4, S5; E5–E8 |
| 5 | Team creation: create, invite/accept, kick, transfer, disband, friendly fire | S8; E3, E4 |
| 6 | Bank and bounties: deposit/withdraw, refused while tagged, coin stealing, bounty place/claim/expire, `/bounty admin check` consistent | S6, S7 |
| 7 | Combat logging: tagged quit leaves a Combat NPC, kill it / let it survive, owner rejoins | S5; [lifesteal-combat-npc.md](lifesteal-combat-npc.md) |
| 8 | Ranked TEST match (staff, portal-free arena): win, loss, cancel, everyone restored | S12, S13; [lifesteal-ranked-qa.md](lifesteal-ranked-qa.md) |
| 9 | Disconnect/reconnect: mid-activity, mid-combat, mid-TEST match | S3; E11 |
| 10 | Controlled restart: `stop` with testers online, then `/jrestart` | release guide 3.1, 3.2 |
| 11 | Afterwards: inventories, ender chests, hearts, bank, teams identical to the before-restart record; `/lifesteal admin ranked restores` and `/event inventory list` empty | release guide 3.1 |
| 12 | TPS, MSPT and memory every 15 minutes through the session | release guide 4.2 |

Crash tests (`kill -9`, release guide 3.3) only on a disposable staging copy, never on the beta
server.

## Decision

**Ready for staging and live testing, with the restrictions above.** The jar is identified and
verified, CI and the Release workflow passed, and every automated suite passes (one after a
check-only fix). Nothing found is a P0. The one open P1 (RNK-01) is inside public Ranked, which
stays disabled. This is **not** approval for players: that needs the live tests above, with no
P0/P1 finding (release guide section 8).
