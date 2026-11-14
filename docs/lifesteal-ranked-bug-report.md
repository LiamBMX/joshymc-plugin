# Ranked Team Wars: staff bug report template (issue #1531)

Use one report per problem. Copy the block below into a new GitHub issue (title starting with
`Ranked beta:`) or the staff bug channel, and link it from the matrix row's **Actual / evidence**
column ([lifesteal-ranked-qa-matrix.md](lifesteal-ranked-qa-matrix.md)).

**Privacy.** Use test account names and Minecraft UUIDs only. **Never include IP addresses,
connection hashes, real names, emails or Discord details.** Paper logs addresses on login: cut
those lines out of any log you paste. A shared connection finding is described as "AltManager
flagged a shared connection", never by address.

```markdown
## Ranked beta bug: <short title: what went wrong, where>

**Test ID:** <e.g. IR-07, or "exploratory">
**Date and time (with time zone):** <2026-11-20 19:42 America/New_York>
**Server:** test server <name> (never production)
**JoshyMC build:** <tag, PR number or commit sha; /version JoshyMC>
**Paper / Minecraft version:** <Paper 26.2 build ...>
**Client(s):** <Java x.y / Bedrock (Geyser) x.y>
**Related config changes this session:** <key: old -> new, or "none">

### Who and what
| Role | Name | UUID | Team (name / id) | Side |
|---|---|---|---|---|
| <reporter / participant / outsider / staff> | | | | <A / B / -> |

**Match id:** <from the start message or /lifesteal admin ranked list>
**Match kind / size / arena:** <PUBLIC / CHALLENGE / TEST, 2v2, arena id>
**Queue entry / proposal / test token (if any):** <rq-..., rp-..., rt-...>
**Match state when it happened:** <WAITING / CONFIRMING / PREPARING / COUNTDOWN / ACTIVE / FINISHING / after>

### Expected behavior
<What the matrix row (or this guide) says should happen.>

### Actual behavior
<What happened. Exact chat messages, copied, not paraphrased.>

### Reproduction steps
1. <Starting state: hearts, inventory, team, config.>
2. <Each command/click, in order, with who did it.>
3. <...>

**Reproducibility:** <always / N of M tries / once>

### Evidence
- Screenshots / video: <links>
- `/lifesteal admin ranked info <match>`: <paste>
- `/lifesteal admin ranked rating match <match>` (Elo bugs): <paste>
- `/lifesteal hearts get <player>` before/after (heart bugs): <values>
- Inventory before/after screenshots (inventory bugs): <links>
- `/lifesteal admin ranked restores`, `queuelog`, `reviews` (if relevant): <paste>
- `/admin logs` rows (RANKED_*): <paste>
- `logs/latest.log` excerpt, IP addresses removed: <paste, with timestamps>

### Severity (see the release gate)
<P0 Critical / P1 High / P2 Medium / P3 Low>, because <one line>

### Related
<GitHub issue/PR numbers, e.g. #1506, #1530; earlier reports>
```

## Severity guide

Same scale as [lifesteal-ranked-release-gate.md](lifesteal-ranked-release-gate.md):

| Level | Meaning | Ranked examples |
|---|---|---|
| **P0 Critical** | Creates or destroys value, or corrupts data, and can be reproduced | an item duplicated or permanently lost by a restore; a heart created from nothing or a count outside 1..20; Elo given for a TEST/cancelled match; a snapshot applied twice; a crash that leaves inconsistent hearts/matches after restart |
| **P1 High** | A competitive rule can be broken, or recovery needs manual staff work | an outsider can hurt a participant or change the arena; a refused staff action goes through on a held player; a forfeit that should move a heart doesn't (or the reverse); farming limits bypassed; a match stuck until restart |
| **P2 Medium** | Wrong but contained; a workaround exists | wrong message text; a queue entry that needs `cancelqueue`; a menu button that misbehaves; leaderboards slow to update |
| **P3 Low** | Cosmetic / wording | typos, colours, menu layout |

When in doubt, choose the higher level and say why; the release gate review can lower it.
