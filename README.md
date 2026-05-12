# JoshyMC Plugin

A single-JAR Paper plugin (Kotlin, Java 21) that powers Joshy's Minecraft server. Replaces a stack of third-party plugins with one custom implementation.

Targets **Paper MC 1.21.11**.

---

## What's in it

A non-exhaustive tour of what the plugin owns:

- **Land & combat**: claims (rectangular + subclaims, claim-block currency), combat tagging with anti-log dupe, PvP arenas with polygon boundaries, per-world player collision toggle
- **Economy**: balances, dynamic stock market, `/shop` GUI buy/sell, sign shops with PDC-preserving item matching, auction house, upgraded hoppers with filters
- **Items & content**: custom items (drills, bores, eggs, weapons, armor, talismans, fishing collection), custom enchants, custom recipes, custom spawners with filtered drops and live storage, crates with keys and animations
- **Player loops**: kits, warps + homes + RTP, TPA, player vaults, quests with tier gating, skills + XP, AFK system, playtime tracking, voting + Votifier listener
- **Cosmetics**: trails, kill effects, join effects, glow colors, emotes, gadgets, chat tags, chat colors, nicknames
- **Social**: ranks (with dual-team collision trick), teams, two-way Discord bridge (JDA), chat games
- **Admin & moderation**: `/admin` GUI panel (info, freeze, snapshot, rollback), bans/mutes/kicks/warns with history, internal anticheat + Grim integration bridge, world-flag overrides, multi-world support (resource world, spawn world, regen-on-demand)
- **Ops**: lag cleaner, recipe blocker, MOTD + welcome, periodic announcements, scoreboard sidebar with per-player timezone, leaderboard holograms

See [`CLAUDE.md`](CLAUDE.md) for the full architectural tour — every manager, listener, and recurring gotcha is documented there.

---

## Installation

1. Grab the latest JAR from [Releases](https://github.com/LiamBMX/joshymc-plugin/releases).
2. Drop it in your Paper server's `plugins/` directory.
3. Start the server. Default config writes itself to `plugins/Joshymc/config.yml`.
4. Edit config to taste, then `/jmcreload` to apply most changes.

Requires Paper 1.21.11+ and Java 21.

---

## Building from source

```bash
./gradlew build       # produces jar/joshymc-1.0-SNAPSHOT-all.jar (shaded fat JAR)
./gradlew runServer   # starts a local Paper 1.21.11 test server with the plugin loaded
```

The Shadow plugin produces a single fat JAR with all dependencies (JDA, etc.) shaded and relocated.

---

## Releases

Releases are tagged `v1.0.X` and ship as GitHub Releases with the shaded JAR attached. The release pipeline is fully automated — see below.

---

## The automated dev pipeline

This repo is set up so issues filed here get fixed and shipped without manual intervention, end to end:

```
Issue filed
   ↓
Claude investigates & opens PR (claude/issue-N branch)
   ↓
CI builds the JAR (compile gate)
   ↓
Claude reviews the PR
   ↓
Auto-merge fires when CI is green (gated: claude/* branch only, sensitive
paths blocked, do-not-auto-merge label is an escape hatch)
   ↓
Issue auto-closes, version bumps, Release workflow runs
   ↓
Shaded JAR uploads to Releases, Discord changelog posts
```

Workflows live in [`.github/workflows/`](.github/workflows/):

| Workflow | Purpose |
|---|---|
| `claude.yml` | Runs Claude on new issues, `@claude` mentions, and Claude-authored PRs |
| `claude-retry.yml` | Re-runs failed Claude jobs every 30 min (recovers from rate limits) |
| `ci.yml` | Builds every PR (compile gate for auto-merge) |
| `auto-merge.yml` | Merges Claude PRs that pass CI, closes linked issue, dispatches release |
| `release.yml` | Builds shaded JAR and publishes GitHub Release |
| `discord-changelog.yml` | Posts release notes to Discord on every published release |
| `progress-labels.yml` | Adds visible `🔨 in-progress` / `📝 in-review` labels to issues and PRs |

**Asking Claude things directly**: mention `@claude` in any issue/PR comment. Example: `@claude what changed in v1.0.50?` Claude reads the repo, replies in the comment. New issues are also picked up automatically.

**Bypassing automation**: add the `do-not-auto-merge` label to any PR to block auto-merge. Workflows that touch `.github/workflows/`, `build.gradle.kts`, `settings.gradle.kts`, or `gradle/wrapper/**` are blocked from auto-merge by default and require a human review.

---

## Style conventions

- **Kotlin only**, no Java files
- **Adventure API** for all text (never legacy `ChatColor`)
- Send messages through `commsManager.send(...)` so the prefix and chat-color rules apply uniformly
- **NamespacedKey-based PDC** for identifying custom items
- Managers handle registration; `Joshymc.kt` only initializes them

---

## Repo layout

```
com.liam.joshymc
├── Joshymc.kt              # Main plugin class — owns all manager lateinits
├── manager/                # ~55 managers, see CLAUDE.md for the full table
├── item/{CustomItem.kt, impl/*}
├── command/                # Each command is its own class
├── listener/               # Bukkit listeners registered in ListenerManager
├── enchant/                # CustomEnchant + CustomEnchantManager
├── gui/GuiManager.kt       # Shared chest-GUI framework
├── discord/                # JDA bot lifecycle, async message queue
├── link/                   # Discord ↔ Minecraft account linking
└── util/                   # TextUtil (Adventure helpers), BlockUtil
```
