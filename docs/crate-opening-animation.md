# Crate opening animation (issue #1503)

Crates redesign phase 4. Opening a crate at its block now plays a short physical animation of
the crate (about 1.5 s) before the usual reward GUI. Rewards, odds, keys and recovery don't
change.

## Sequence

| Stage | Ticks (default 30) | What the opener sees and hears |
|---|---|---|
| 1. Activation | 0–5 | The model pulses and briefly glows in the profile colour. A few ambient particles and the activation sound. |
| 2. Anticipation | 6–20 | The model wobbles (rolls a few degrees, growing). Ambient particles build up, the accent (lock/emblem glow) particles sit on the crate's front, and a chime rises in pitch every 4 ticks. |
| 3. Opening | 21–29 | The model lifts, swells and tips back as if the lid were bursting open. Burst particles, the open sound and the glow outline. |
| 4. Reveal | 30 | The normal flow continues: Spin / Pulse GUI, Instant payout, or the Select / Select 3 pick GUI. |
| 5. Reset | 30–38 (`close-ticks`) | The model settles back to rest, the close sound plays, then the temporary model is removed and the real one shown again. |

### No separate lid

Every crate chest model (`harvest_chest`, `stockpile_chest`, `homestead_chest`,
`campfire_chest`, `cabin_chest`, the monthly `*_chest` models) is **one item model**. An
`ItemDisplay` can't move part of a model, so the lid can't swing open on its own. A real lid
would need every model split into a body and a lid model, each spawned as its own display and
lined up for scale, offset and facing. That would mean remodelling every crate, which this
phase leaves alone (§13 of the issue). So the opening is a pulse, wobble, lift, tilt and glow
of the whole model, with particles and sounds. Vanilla crate blocks (no model, Bedrock, no
pack) get the particles and sounds only. Their lid isn't animated either, because a block lid
animation would be shared with every nearby player.

## Player-specific, not public

Only the opener sees and hears the animation:

- Particles go through `player.spawnParticle` and sounds through `player.playSound`, so other
  players get none of them.
- When the opener sees custom models, the permanent display is hidden **for them only**
  (`hideEntity`). A temporary copy is spawned with the same item, yaw, offset, scale, lift and
  brightness. It is non-persistent, hidden by default, shown only to them and tagged
  `joshymc_crate_anim`. Only the copy moves.
- Everyone else keeps seeing the untouched permanent model. Anyone can open the same crate at
  the same time with their own copy. Sessions are kept per player
  (`CrateOpeningAnimation.Book`), so one player can't end or overwrite another's.
- The crate block, the permanent display and its hitbox (`Interaction`) are never changed.

A shared public animation was rejected because two players opening the same crate would fight
over one display.

## Reward safety

The animation is purely cosmetic. `CrateOpeningAnimator` and `CrateOpeningAnimation` never touch
keys, inventories or `crate_pending_rewards`.

- **Random crates (Spin, Pulse, Instant)**: the key is taken on the click, as before. The
  winner is then drawn with the same single weighted draw and written to
  `crate_pending_rewards` (in flight) **before** the animation starts. At the reveal, Spin and
  Pulse run their normal GUI with that stored winner, and Instant pays that stored row through
  `finishInFlight`. Nothing is drawn twice or paid twice.
- **Select / Select 3**: nothing is drawn or consumed during the animation. The key is still
  only taken when the pick is confirmed in the GUI that opens at the reveal.
- **Mass opening** (sneak + right-click): unchanged, no per-key animation. One short burst
  and sound play after the loop (`mass-open: burst`, or `none`).
- `/crate force` also animates (no key is involved).

### Interruptions

| Event | Result |
|---|---|
| Quit | The animation ends silently. A random crate's stored reward stays pending and is delivered on join (existing recovery). |
| Reload / shutdown | `CrateManager.stop()` stops animations first (copies removed, models re-shown, no reveal), then the existing loop pays every in-flight reward. |
| Teleport / walking more than `max-distance` / changing world | Skips straight to the reveal (reward GUI). |
| Chunk unload | Skips to the reveal. The copy is non-persistent, so it can't be saved. |
| Copy disappears / pack discarded mid-way | The copy is dropped. The animation goes on with particles only. |
| Display spawn fails / animation error / stuck session | Logged. Falls back to particles only, skips to the reveal, or force-finishes after `duration + close + 40` ticks. |
| Animation can't start at all | The crate opens exactly as before (Random crates reveal the already stored reward). |
| Saved copy found on chunk load (crash) | Removed in `EntitiesLoadEvent`. |

## Configuration (`config.yml > crates.opening-animation`)

Defaults are safe and backfilled. `crates.yml` is untouched, so existing crates need no
changes.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch (off = exactly the old behaviour). |
| `duration-ticks` | `30` | Click to reward GUI, 10..80. |
| `close-ticks` | `8` | Settle back after the GUI opens, 0..40. |
| `particle-intensity` | `1.0` | 0..2. Counts are capped (≤6 per ambient spawn, ≤40 per burst). |
| `sound-volume` | `0.8` | 0..1. |
| `movement-intensity` | `1.0` | 0..2. 0 = particles and sounds only. |
| `max-distance` | `16` | Blocks before skipping to the GUI. |
| `mass-open` | `burst` | `burst` or `none`. |
| `default-profile` | `default` | Fallback profile. |
| `monthly-themes` | `true` | Use the month's profile while a crate shows its monthly model. |
| `crates.<id>` | — | Per-crate `enabled`, `profile`, `duration-ticks`, `close-ticks`, `particle-intensity`, `sound-volume`, `movement-intensity`. |
| `profiles.<id>` | see file | `ambient`, `accent`, `burst` (Bukkit particle names, colour particles use `color`), `color` (`#RRGGBB`), and `activate-sound`, `build-sound`, `open-sound`, `reveal-sound`, `close-sound` (Minecraft sound keys, or `none`). |

A bad value is logged and replaced by its default. It never stops a crate from opening. An
unknown particle (or one that needs other data) is logged once and drawn as `END_ROD`.

### Profile choice

A crate's profile is picked in this order:

1. The crate's override (`crates.<id>.profile`).
2. This month's profile, only while the crate shows its monthly model (`monthly.crates`).
3. The profile named after the crate id.
4. `default-profile`.
5. The built-in profile.

Bundled profiles:

| Profile | Look |
|---|---|
| `harvest` | golden wax sparkles, warm gold |
| `stockpile` | electric sparks and crits, steel, iron sounds |
| `homestead` | witch/enchant particles, purple, amethyst and enchanting sounds |
| `campfire` | flames and lava embers, crackle |
| `cabin` | cherry leaves and green sparkles, pink |
| `january` | snowflakes, icy blue |
| `february` | hearts |
| `march` | clover green |
| `april` | rain drips and blossoms |
| `may` | spore blossoms and soft green |
| `june` | sunshine |
| `july` | fireworks |
| `august` | summer heat |
| `september` | orange tinted leaves |
| `october` | soul flames and witch magic, orange |
| `november` | embers and maple-red leaves |
| `december` | snowflakes, red and fireworks |

## Bedrock / resource pack

Java players with the pack loaded see their animated copy of the custom model. Players without
the pack, Bedrock players and crates without a model (or with a missing one) see the normal
vanilla block with the same particles and sounds. Missing models and pack failures never affect
the reward: the GUI opens at the reveal either way.

## Performance

- One repeating task runs only while at least one animation plays, and is cancelled when idle.
- No per-tick world scans: each tick looks up one player and one display by UUID.
- Copies are non-persistent and removed on finish, quit, reload and shutdown.
- The transformation is sent every 2 ticks with client-side interpolation.
- Particles are capped and sent to one player.

## Checks

```bash
./gradlew -I scripts/crate-animation-check.init.gradle.kts crateAnimationCheck
```

`scripts/CrateAnimationCheck.kt` covers settings and defaults, overrides, disabled crates,
profile resolution (incl. monthly), the timeline (start, completion, one reveal per open,
stage order), particle caps, poses (rest at both ends, no floor clipping, bounded motion,
movement 0), sessions (two players on one crate, different crates, cancel, stale finishes,
no leaks), interruptions (teleport, world, chunk unload, timeout), the bundled config, and the
wiring: one key per click, one weighted draw stored before the animation, Spin/Pulse/Instant
reveal the stored reward, Select/Select 3 draw and consume nothing, mass open = one burst,
quit/reload/shutdown order, join recovery, non-persistent hidden copies, cleanup and the
untouched hitbox.

## Live Paper test

1. `./gradlew runServer`, join with the resource pack, register a crate with a model
   (`/crate setlocation harvest`) and get keys (`/crate givekey harvest <you> 5`).
2. Right-click with one key and watch the pulse, wobble, lift and glow, with gold particles
   and a rising chime. The Spin/Pulse GUI should open at about 1.5 s and pay the reward it
   shows.
3. Ask a second player to watch: they should see a still model, with no particles or sounds.
   Then both open the same crate at once: each gets their own animation.
4. Try `/crate rotate east` and a model with `scale: 1.5`: the animated copy should match the
   facing and size.
5. Quit mid-animation and rejoin: the reward is delivered once (`crate_pending_rewards` empty
   afterwards). Run `/joshymc reload` mid-animation: the reward is paid once, and the model
   is back for you.
6. Teleport away mid-animation: the GUI opens right away.
7. Join with the pack declined, or from Bedrock: you should see particles and sounds around
   the vanilla block, and the crate should open normally.
8. Sneak + right-click with a stack: one burst, the summary, no animation per key.
9. Select / Select 3 crate: the animation plays, then the pick GUI opens. Closing it without
   picking uses no key.
10. Set `crates.opening-animation.enabled: false` and reload: the crate opens instantly, as before.
11. `/crate models` shows no untracked entities afterwards. No `joshymc_crate_anim`
    entities should remain (`/execute if entity @e[tag=joshymc_crate_anim]` fails).
