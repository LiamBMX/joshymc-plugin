# Ranked Team Wars: closed-beta release gate (issue #1531)

This is the checklist for deciding whether to set `ranked-team-wars.enabled: true` on the
**production** Lifesteal server for the closed beta. It is filled in by people from live
evidence collected with [lifesteal-ranked-qa.md](lifesteal-ranked-qa.md) and
[lifesteal-ranked-qa-matrix.md](lifesteal-ranked-qa-matrix.md). No tool, checklist or passing
automated suite decides it; `rankedBetaReadiness` explicitly never reports READY.

**Current status: NOT DECIDED.** No live ranked match has been played and recorded yet
(see [lifesteal-ranked-beta.md](lifesteal-ranked-beta.md) and the matrix: every row is NOT RUN).

## 1. Severity scale

| Level | Definition |
|---|---|
| **P0 Critical** | Creates or destroys items, hearts or Elo, corrupts stored data, or leaves the server in an unrecoverable state. Reproducible or seen once with evidence. |
| **P1 High** | Breaks a competitive rule (fairness, protection, permission) or needs manual staff recovery, but creates/destroys nothing. |
| **P2 Medium** | Incorrect but contained, with a workaround; no fairness impact. |
| **P3 Low** | Cosmetic. |

A P1 is **release-blocking** unless the reviewers record, in section 4, why it isn't for this beta
(for example: only reachable by staff with console access, and a restriction is in place).

## 2. Hard gates (all must be true)

| # | Gate | How it's shown | Met? | Evidence |
|---|---|---|---|---|
| G1 | No unresolved P0 findings | bug tracker: every `Ranked beta:` P0 closed with a verified fix | ☐ | |
| G2 | No unresolved release-blocking P1 findings | bug tracker + section 4 | ☐ | |
| G3 | No confirmed inventory duplication | IR-01..IR-20 PASS; no dupe report open | ☐ | |
| G4 | No confirmed permanent inventory loss | IR-* PASS; `/lifesteal admin ranked restores` empty after every session | ☐ | |
| G5 | No unauthorized heart generation | HT-* PASS; hearts conserved in every match log (each +1 has a −1) | ☐ | |
| G6 | No exploitable Elo manipulation | EL-* and MM-19/MM-20 PASS | ☐ | |
| G7 | No unauthorized match interference | AS-* and ST-* PASS | ☐ | |
| G8 | No unresolved critical restart corruption | DR-11..DR-16 PASS on the test server | ☐ | |
| G9 | Required live tests completed with evidence | every row in section 3's "required" list PASS with linked evidence | ☐ | |
| G10 | Pending dependencies settled | #1530 merged and TI-01..TI-05 re-run, **or** a recorded restriction (section 5) | ☐ | |
| G11 | Automated suites pass on the release build | guide section 16, run on the exact commit | ☐ | |
| G12 | Open Owner decisions recorded | forfeit heart recipient (`disconnect-forfeit.heart-loss`), killer at 20, challenges unrated | ☐ | |

## 3. Category checklists

Mark each category only from the matrix. "Required" rows must be PASS for G9; the rest must be
PASS, N/A with a reason, or covered by a recorded restriction.

| Cat. | Area | Required rows | Result | Open bugs (P-level) | Reviewer |
|---|---|---|---|---|---|
| A | Matchmaking | MM-01, MM-02, MM-03, MM-04, MM-05, MM-10, MM-11, MM-12, MM-19, MM-22, MM-24, MM-25 | ☐ | | |
| B | Team integrity | TI-06, TI-08, TI-09, TI-12, TI-13, TI-14; TI-01..TI-05 after #1530 | ☐ | | |
| C | Hearts | HT-02, HT-03, HT-04, HT-05, HT-06, HT-09, HT-11, HT-14, HT-16 | ☐ | | |
| D | Inventory restoration | IR-01, IR-02, IR-03, IR-06, IR-07, IR-08, IR-09, IR-11, IR-12, IR-13, IR-14, IR-16, IR-17 | ☐ | | |
| E | Elo | EL-01, EL-02, EL-03, EL-06, EL-07, EL-08, EL-10, EL-11, EL-12, EL-15, EL-18 | ☐ | | |
| F | Arena protection | AS-01, AS-02, AS-03, AS-08, AS-10, AS-11, AS-12, AS-18, AS-19 | ☐ | | |
| G | Spectator protection | AS-04, AS-05, AS-06, AS-07, AS-20, AS-21 | ☐ | | |
| H | Staff permissions | ST-01..ST-08, ST-11, ST-12, ST-13, ST-16, ST-17 | ☐ | | |
| I | Disconnect / restart recovery | DR-01, DR-03, DR-04, DR-06, DR-07, DR-10, DR-11, DR-12, DR-14, DR-16 | ☐ | | |
| J | Performance / stability | PS-01, PS-02, PS-03 | ☐ | | |

## 4. Unresolved findings register

| Bug link | Test ID | Severity | Release-blocking? (P0 always yes) | Reason if not blocking | Owner |
|---|---|---|---|---|---|
| | | | | | |

## 5. Restrictions (only for READY WITH RESTRICTIONS)

A restriction must be something staff can actually enforce with existing settings or process, for
example:

- `ranked-team-wars.team-sizes` limited to sizes that passed (e.g. `[2, 3]`);
- `ranked-team-wars.disconnect-forfeit.heart-loss: none` until the Owner confirms the recipient rule;
- `ranked-team-wars.matchmaking.enabled: false` outside announced beta hours;
- only arenas with a WorldGuard region enabled;
- no team management during matches announced as a rule until #1530 merges (if G10 isn't met).

| Restriction | Setting / process | Why | Until |
|---|---|---|---|
| | | | |

## 6. Decision record

Exactly one of:

- **READY**: every hard gate G1–G12 met, every category passed, no restrictions needed.
- **READY WITH RESTRICTIONS**: G1, G3, G4, G5, G8 met (no P0, no dupes, no loss, no heart
  generation, no restart corruption are never waivable); every other unmet gate is covered by a
  restriction in section 5.
- **NOT READY**: anything else. List what has to happen next.

| Field | Value |
|---|---|
| Decision | ☐ READY ☐ READY WITH RESTRICTIONS ☐ NOT READY |
| Build (tag / commit) tested | |
| Test sessions (dates, links to session logs) | |
| Restrictions in force | |
| Decided by (Owner + reviewers) | |
| Date | |

Turning ranked on in production is then a config change (`ranked-team-wars.enabled: true`,
`/joshymc reload`) made by the Owner or someone they name, with the decision linked in the
announcement. Turning it back off is the same switch and never deletes ratings or history.
