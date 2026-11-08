# Christmas Advent Calendar (issues #1424, #1448)

25 presents from December 1 to December 25, one per day, separate for Survival and Lifesteal.
Players open it with `/advent` (aliases `/adventcalendar`, `/christmas`) or by clicking an NPC.

Code: `src/main/kotlin/com/liam/joshymc/advent/`. Settings: `plugins/Joshymc/advent-calendar.yml`
(copied from the bundled file the first time, never overwritten). Checks:
`./gradlew -I scripts/advent-calendar-check.init.gradle.kts adventCalendarCheck`.

## Turning it on and off (Owner)

```yaml
enabled: true            # false = completely OFF

schedule:
  automatic: true        # open Dec 1 - Dec 25 every year on its own
  timezone: "America/New_York"
  start-month: 12
  start-day: 1
  end-month: 12
  end-day: 25

manual-override:
  enabled: false         # true = open outside the dates
  year: 0                # which year's calendar (0 = this year)
  current-day: 0         # which day is "today" (0 = by date)

missed-days:
  enabled: true
  cost-credits: 25
```

After editing, run `/advent admin reload` (or `/joshymc reload`). No rebuild or restart needed.

The switches are checked top to bottom, and the first one that applies wins:

| Setting | Effect |
|---|---|
| `enabled: false` | **Off**, whatever else is set. `/advent` and the NPC show the "currently disabled" message, nothing can be opened or claimed. Claims and reward history stay saved, and rewards already claimed still finish delivering. |
| `manual-override.enabled: true` | **Open outside the dates.** Only the date check is skipped. One claim per day, missed-day prices and future-day locks still apply. By date (`current-day: 0`): before December 1 nothing is unlocked yet, after December 25 every day counts as missed (recoverable for Credits). `current-day: 7` makes day 7 "today" (days 1-6 missed, 8-25 locked). `year: 2026` runs the 2026 calendar, e.g. in January to let players recover last year's days. |
| `schedule.automatic: true` | **Open December 1 00:00 to December 25 23:59** in `schedule.timezone`, closed otherwise. Comes back every December by itself. |
| `schedule.automatic: false` | **Closed** unless the manual override is on. |

Examples:

- `enabled: false` in mid-December: closed for everyone, nothing lost. Setting it back to `true`
  reopens it; the days that passed while it was off are ordinary missed days (staff can make them
  free, see below).
- `enabled: true`, `automatic: true`, override off, December 26: closed ("returns on December 1, 2027").
- `enabled: true`, `manual-override.enabled: true`, `current-day: 3`, in July: open, day 3 free,
  days 1-2 recoverable for 25 Credits, days 4-25 locked.

Each gamemode can also be switched off on its own: `rewards.survival.enabled` / `rewards.lifesteal.enabled`.

An invalid file (wrong date, timezone, reward line, missing day, ...) is reported in the console
and in `/advent admin status`, and the calendar stays closed until it is fixed. It never guesses.

## Rules

- Every present unlocks at midnight in `schedule.timezone` (a real IANA timezone, so it follows
  New York time, not a fixed UTC offset). "Today" is computed from the clock whenever someone looks,
  so there's no midnight timer.
- Today's present is free. A past present can be recovered for `missed-days.cost-credits` Credits
  each (no bundles, no discounts). Future presents are locked. No playtime or streak requirements.
- Missed-day recovery ends with the calendar (December 25). Only the manual override reopens it.
- Each player can claim each day once per gamemode per year. 2026 day 1 and 2027 day 1 are different
  claims, and Survival and Lifesteal claims are separate.
- Survival's calendar runs only on the server that hosts the Survival economy, and Lifesteal's only on
  the dedicated Lifesteal server (the same rule as Lifesteal quests and bounties). Coins and Credits
  always land in that server's own balances. On a server hosting both gamemodes, only Survival's
  calendar runs (its economy belongs to Survival).

## The menu

A 54-slot chest: a 5x5 grid of presents (day 1 top left, Christmas Day bottom right), the stack size
is the day number. Red/green panes run down the sides, a book at the top explains the rules, and a
barrier in the bottom right closes it.

| Present | Look |
|---|---|
| Available today | red / green shulker box, glowing |
| Claimed | lime glass |
| Missed, recoverable | yellow shulker box, shows the price |
| Missed, free recovery | glowing, "free recovery" |
| Missed, not recoverable | black shulker box |
| Locked | gray shulker box, shows when it opens |
| Christmas Day | glowing nether star, "Christmas Grand Bundle", lists every bundle item |

Each tooltip shows the date, every reward, the status, what clicking does and the price. Clicking a
missed present opens "Unlock December 7?" with Confirm Purchase (cost + your Credits) and Cancel.
GuiManager cancels every click on the menu, plus shift-click, double-click, number keys, off-hand
swap and drags, so no icon can be taken.

## NPC

The existing NPC system runs an NPC's command **as the clicking player** (`player.performCommand`),
so an NPC opening the calendar goes through exactly the same checks as typing `/advent`. No console
command, no new entity, nothing respawned.

```
/npc create advent &c&lAdvent Calendar     (stand where it should be)
/npc setskin advent <skin player name>
/advent admin setnpc advent                (same as /npc setcommand advent advent)
```

Each server has its own NPC table, so Survival and Lifesteal each get their own NPC. Existing spawn
NPCs are never touched or moved.

## Rewards

Every reward line is one of:

```
coins <amount>                  credits <amount>
cratekey <crate id> <quantity>  chattagvoucher <tag id> <quantity>
tokens <quantity>
pending <placeholder> <quantity> <label>
```

`rewards.<gamemode>.default` is used every year. To change one December only, copy all 25 days under
`rewards.<gamemode>.years.<year>`. A claim stores what it gave (the reward snapshot), so editing the
file never changes a present someone already opened.

Delivery reuses the store-delivery queue (`PurchaseFulfillmentManager`, `/purchase list`): each
reward of a claim becomes one delivery with the reference `advent:<reward row id>` (UNIQUE, so a
retry can't give it twice). Coins and Credits are paid even if the player is offline. Items (crate
keys, chat tag vouchers) are handed over when there is inventory space, a unit at a time, and are
never dropped on the ground; the player is told when their inventory is full and gets them within a
minute of freeing space (or on their next join).

### December 2026 (exact issue schedule)

Survival totals: **1,450,000 Coins, 1,400 Credits.** Lifesteal totals: **290,000 Coins, 700 Credits.**
(Both checked by `adventCalendarCheck`.)

| Day | Survival | Lifesteal |
|---|---|---|
| 1 | 50,000 Coins | 10,000 Coins |
| 2 | 100 Credits | 50 Credits |
| 3 | 1 Cabin Crate Key (`cabin`) | 1 standard Lifesteal crate key ⏳ |
| 4 | 75,000 Coins | 15,000 Coins |
| 5 | Snowflake Chat Tag | Frostbite Chat Tag |
| 6 | 100 Credits | 50 Credits |
| 7 | 1 Campfire Crate Key (`campfire`) | 1 standard Lifesteal crate key ⏳ |
| 8 | 100,000 Coins | 20,000 Coins |
| 9 | Snow Globe Collectible ⏳ | Snow Globe Collectible ⏳ |
| 10 | 150 Credits | 75 Credits |
| 11 | 1 Homestead Crate Key (`homestead`) | 1 upgraded Lifesteal crate key ⏳ |
| 12 | 125,000 Coins | 25,000 Coins |
| 13 | Winter Scarf Cosmetic ⏳ | Winter Trail Cosmetic ⏳ |
| 14 | 150 Credits | 75 Credits |
| 15 | 1 Stockpile Crate Key (`stockpile`) | 1 upgraded Lifesteal crate key ⏳ |
| 16 | 150,000 Coins | 30,000 Coins |
| 17 | Christmas Bell Collectible ⏳ | Christmas Bell Collectible ⏳ |
| 18 | 200 Credits | 100 Credits |
| 19 | 1 Harvest Crate Key (`harvest`) | 1 premium Lifesteal crate key ⏳ |
| 20 | 200,000 Coins | 40,000 Coins |
| 21 | Holly Chat Tag | Blizzard Chat Tag |
| 22 | 200 Credits | 100 Credits |
| 23 | 2 Campfire Crate Keys | 2 standard Lifesteal crate keys ⏳ |
| 24 | 250,000 Coins | 50,000 Coins |
| 25 | **Grand Bundle:** 500,000 Coins, 500 Credits, 3 premium crate keys ⏳, Christmas Chat Tag, festive cosmetic ⏳, Christmas 2026 Collectible ⏳ | **Grand Bundle:** 100,000 Coins, 250 Credits, 3 premium Lifesteal crate keys ⏳, Christmas Chat Tag, festive cosmetic ⏳, Christmas 2026 Collectible ⏳ |

The Christmas Grand Bundle is one claim (one click, one transaction): all its rewards are recorded
together and delivered separately, so a full inventory or a missing item never loses the rest.

The five chat tags (`snowflake`, `holly`, `frostbite`, `blizzard`, `christmas`) are new Special Chat
Tags in `tags.yml` (voucher category, each locked behind its own `joshymc.tag.voucher.<id>` node).
They are handed out as the existing (PDC-tagged) Chat Tag Vouchers, so ownership is the normal
`/tags` unlock, and renaming a vanilla item can't fake one.

### ⏳ Waiting rewards (not in JoshyMC yet)

These don't exist on the server yet, so the calendar does **not** substitute anything for them:

| Placeholder | Why it waits |
|---|---|
| `survival_premium_crate_key` | No Survival crate is marked "premium"; the crate ids in the repo are examples and the live ones are on the server. |
| `lifesteal_standard_crate_key`, `lifesteal_upgraded_crate_key`, `lifesteal_premium_crate_key` | Lifesteal has no crates yet. |
| `snow_globe`, `christmas_bell`, `christmas_2026_collectible` | Collectible items need a custom item with art first. |
| `winter_scarf`, `winter_trail`, `survival_festive_cosmetic`, `lifesteal_festive_cosmetic` | JoshyMC has no wearable/trail cosmetic that can be granted as an item yet. |

Claiming a day with a waiting reward still counts the claim and records the reward as **WAITING**
(the tooltip says so). Nothing is lost: when the reward exists, map it and reload, e.g.

```yaml
placeholders:
  survival_premium_crate_key: "cratekey joshy"      # a real crate id from /crate list
  lifesteal_standard_crate_key: "cratekey ls_basic"
  christmas_2026_collectible: "chattagvoucher christmas"
```

On `/advent admin reload` (and at startup and on each player's join) every WAITING reward whose
placeholder is now mapped to something the server can build is queued for delivery, once.
Mappings may be `cratekey <crate>`, `chattagvoucher <tag>` or `tokens`. Collectibles and cosmetics
that are real items will also need a reward kind for them (future work). A crate or chat tag
written directly in a day (e.g. `cratekey cabin 1`) that doesn't exist on the server waits the same
way, and is delivered once the crate is created.

## Staff tools

`joshymc.advent.admin` (op):

| Command | What it does |
|---|---|
| `/advent admin status` | Enabled/override/automatic, timezone, whether it is open, today's day, claims this year, reward rows by state, placeholders not set up, file problems. |
| `/advent admin history <player> [year]` | Every claim (day, today/catch-up/free, Credits paid, time) with each reward's delivery state. |
| `/advent admin failed` | Rewards whose delivery FAILED or was INTERRUPTED, and a summary of waiting rewards. |
| `/advent admin reload` | Rereads `advent-calendar.yml` and queues waiting rewards that are now deliverable. |
| `/advent admin setnpc <npc id>` | Makes an existing NPC open the calendar. |

`joshymc.advent.admin.recover` (op):

| Command | What it does |
|---|---|
| `/advent admin reissue <reward id>` | Repairs one reward without ever giving it twice: WAITING is re-resolved, READY is queued (UNIQUE reference), a FAILED/INTERRUPTED delivery is retried through `/purchase retry` logic, which only resends what is still owed. A COMPLETED delivery is refused. |
| `/advent admin grantfree <player\|*> <day> [year] [reason]` | Makes a missed day free to recover (e.g. after an outage or while the event was disabled). Still only once per player, and only for past days. `*` = everyone. |
| `/advent admin revokefree <player\|*> <day> [year]` | Removes such a pass. |

Every staff action goes to `/admin logs` (`AdminAudit`) and the `advent_audit` table; every claim is
also written to `advent_audit`.

## Storage and safety

| Table | Contents |
|---|---|
| `advent_claims` | One row per gamemode + year + day + player (UNIQUE): claim type, Credits paid, time. |
| `advent_claim_rewards` | The reward snapshot of each claim: WAITING → READY → QUEUED (+ delivery id). Never deleted. |
| `advent_free_passes` | Free recovery passes (one player or `*`). |
| `advent_audit` | Claims and staff actions. |

- A claim is one transaction: it re-checks that the day isn't claimed, recomputes the price (a price
  that changed since the menu was drawn is refused, never charged), takes the Credits, and writes
  the claim and the reward snapshot. If anything fails, everything rolls back, including the Credits.
- Double clicks: GuiManager's 200 ms click cooldown, a per-player "claim in progress" guard, and the
  UNIQUE claim row (checked by 16 simultaneous claims in the check script: exactly one wins, Credits
  taken once).
- Restarts: claims and the reward snapshot are committed before anything is delivered; a reward left
  READY by a crash is queued by the one-minute sweep; delivery state is the purchase queue's.
- Old years are kept forever; a new year never overwrites them.

## Manual checks on a test server

Automated: `adventCalendarCheck` (schedule, precedence, New York midnight, Dec 25 → 26, annual
return, day states, prices, the bundled calendars and totals, config validation, the claim store on
real SQLite incl. restarts and concurrent claims, layout and wiring). Not automated (needs a server):

1. With the defaults outside December, `/advent` says when it opens. Set `manual-override.enabled: true`,
   `current-day: 5`, reload: day 5 glows, 1-4 show 25 Credits, 6-25 locked.
2. Claim day 5, click it again (refused), reopen the menu (lime glass), restart, still claimed.
3. Recover day 3 with and without enough Credits; check the balance changes once.
4. Fill the inventory, claim a crate key day: the key arrives after freeing a slot, never dropped.
5. `/advent admin grantfree * 2`, then day 2 is free once.
6. `enabled: false` + reload: `/advent` and the NPC show the disabled message; claims are kept.
7. `/npc create advent ...` + `/advent admin setnpc advent`: clicking opens the calendar.
8. On the Lifesteal server: separate claims, Coins/Credits in the Lifesteal balances, the Lifesteal
   crate days show the waiting note.
