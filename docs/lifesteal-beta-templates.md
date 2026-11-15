# Lifesteal closed beta: templates (issue #1558)

Copy-paste templates for the process in [lifesteal-beta-release.md](lifesteal-beta-release.md).
Ranked-specific reports may use [lifesteal-ranked-bug-report.md](lifesteal-ranked-bug-report.md),
which asks for match ids, sides and snapshots.

**Privacy, for every template.** Minecraft names and UUIDs only. Never IP addresses, connection
hashes, real names, emails, Discord details or store order details. Cut login lines (they contain
addresses) out of pasted logs. Exploits and dupes are reported privately until fixed.

## 1. Release candidate record

One per candidate, kept with the staff notes (or a private GitHub issue).

```markdown
## Lifesteal beta RC: v1.0.<N>

**Prepared by / date (time zone):**
**Tag / commit SHA:** v1.0.<N> / <sha>
**Currently deployed on production:** v1.0.<M> (<sha>)
**Jar:** joshymc-1.0.<N>.jar, SHA-256 <hash>, `sha256sum -c` OK: yes/no
**Paper version on staging / production:** <Paper 26.2 build ...>
**Companion plugin versions (LuckPerms, WorldGuard, Grim, Geyser, Floodgate):**

### Changes since the deployed build (git log v1.0.<M>..v1.0.<N>)
| PR | Title | Effect on Lifesteal | Schema change? |
|---|---|---|---|
| #     |       | <none / what changes>  | <none / new table / new column / trigger / data migration> |

**Required fixes from the previous known-issues list included:** <#..., or "none required">

### Automated checks on this commit
| Task | Result | Note |
|---|---|---|
| ./gradlew build | PASS/FAIL | |
| <each suite from section 1.3> | PASS/FAIL | |

### Configuration
- Hand changes since the last RC (file: key old -> new):
- Keys the new build backfilled on its first staging start:
- Ranked enabled / matchmaking enabled / progression enabled:
- auto-restart enabled / interval:

### Resource pack
- Hash logged on staging: <sha1>   Same as deployed build: yes/no
- Java client checked / Bedrock client checked: yes/no

### Staging
- Session log link:
- S1..S15 result summary:
- Restart checklist (3.2) / crash tests (3.3) / soak log:

### Deployment
- Window (date, time, zone), announced:
- Backup path, time, size, integrity_check ok:
- Post-deploy checks (version, /jgamemode info, startup log, player check):
- Rollback path if needed (7.2 / 7.3 / 7.4) and why:
- Result:
```

## 2. Bug report

```markdown
## Lifesteal beta bug: <short title: what went wrong, where>

**Severity:** P0 / P1 / P2 / P3   (lifesteal-beta-release.md section 5)
**Date and time (with time zone):**
**Server:** staging <name> / production
**Server version:** <Paper 26.2 build ...>
**Plugin version and commit:** <1.0.N from Paper's version command> / <sha>
**Client(s):** <Java x.y / Bedrock (Geyser) x.y>
**System:** <hearts / PvP / bank / bounties / teams / quests / achievements / ranked / restore / spawn-dimensions / restart / other>

### Players involved
| Role | Name | UUID (only where needed) |
|---|---|---|
| | | |

### Steps to reproduce
1.
2.
3.

**Reproducible:** always / sometimes (<n of m tries>) / once

### Expected behavior
<what should happen, citing the doc or checklist step if there is one>

### Actual behavior
<what happened, with exact numbers: hearts before/after, balances, item counts>

### Evidence
- Screenshots / video:
- Command output (e.g. /lifesteal hearts get, /bank admin view, /bounty admin check):
- Relevant logs (time range, stack trace first lines, **login lines removed**):

### Impact
**Restart required to fix or contain:** yes / no
**Data recovery required:** yes / no (what: items, hearts, coins, Elo, team) — who needs it:
**Workaround:** <none / describe>
**Contained by turning a system off?** <switch and value, or no>
```

## 3. Known issues

Keep one current list, shared with testers (without the private details or anything that tells
players how to exploit a bug).

```markdown
## Lifesteal beta known issues — build v1.0.<N> (updated <date time zone>)

### Disabled for this beta
| System | Switch | Why |
|---|---|---|
| | | |

### Open issues
| ID / link | Severity | Summary (player-facing words) | Workaround | Fix planned for |
|---|---|---|---|---|
| | P1/P2/P3 | | | next RC / later / won't fix in beta |

### Fixed since the last build
| ID / link | Summary |
|---|---|
| | |

### Not bugs (decisions still open with the Owner)
- <e.g. killer at 20 hearts, forfeit heart recipient: see lifesteal-beta.md section C>
```

## 4. Soak test log

```markdown
## Lifesteal soak test — build v1.0.<N> — <staging/production> — <date>

**Start / end (time zone):**        **Testers (count, Java/Bedrock):**
**Session lead:**                   **Console watcher:**
**Config changes for this session (key old -> new):**

### Measurements (every 15 minutes; write "not measured" when it wasn't)
| Time | Players | TPS 1m/5m/15m | MSPT avg/max | Heap used/max | Note |
|---|---|---|---|---|---|
| | | | | | |

### Activity done
| Time | Activity (PvP / team / ranked / joins-quits / inventory / bank / bounty / world change) | Who |
|---|---|---|
| | | |

### Problems seen
| Time | Kind (exception / DB failure / task failure / disconnect / inventory / hearts / ranked) | What | Bug report link |
|---|---|---|---|
| | | | |

### End of session checks
- /lifesteal admin ranked restores empty: yes/no
- /event inventory list nothing in REVIEW: yes/no
- /bounty admin check consistent: yes/no
- /bank admin failed: nothing new / list
- Clean stop, PRAGMA integrity_check on a copy: ok / problem

**Verdict:** clean / not clean (why)
```
