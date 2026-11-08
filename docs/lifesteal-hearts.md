# Lifesteal hearts (issues #1341, #1344, #1457, #1461)

The core Lifesteal heart system: permanent hearts per player, shown as their maximum health, and
PvP heart stealing (issue #1341), plus physical Heart items: `/withdrawheart` and right-click
redemption (issue #1344, see "Heart items" below). Lifesteal only. It builds on the dedicated
server bootstrap (`docs/lifesteal-server.md`, issue #1336) and reads `lifesteal.yml`.

Code: `src/main/kotlin/com/liam/joshymc/lifesteal/hearts/`

| File | Role |
|---|---|
| `HeartRules.kt` | Bukkit-free rules: limits, kill attribution, transfer decision, cooldown arithmetic, admin changes, withdraw/redeem decisions, serial format |
| `HeartStore.kt` | Bukkit-free SQLite store: records, transfers, admin changes, Heart item registry, withdraw/redeem/grant/void, audit, all transactional |
| `LifestealHeartManager.kt` | Sessions, max health, death handling, withdraw/redeem/grant/void entry points, admin entry point |
| `LifestealHeartListener.kt` | Join / quit / world change / post-respawn / death / Heart item right-click / crafting block |
| `HeartItem.kt` | Builds and reads the Heart item (persistent data only) |
| `WithdrawHeartCommand.kt` | `/withdrawheart` |
| `LifestealHeartsCommand.kt` | `/lifesteal hearts ...` |
| `HeartMessages.kt` | Player messages (the six main Heart item messages are in `lifesteal.yml`) |

## Rules

From `lifesteal.yml` (defaults = blueprint v1.1): `hearts.starting` 10, `hearts.maximum` 20,
`hearts.minimum` 1, `pvp.same-victim-cooldown-minutes` 30.

- A player's first time in a Lifesteal world creates their row with the starting hearts, once
  (primary key + `INSERT OR IGNORE`, one `INITIAL` audit row).
- 1 heart = 2 health points. The heart count is the `MAX_HEALTH` attribute's **base value**, so
  potions and item modifiers still stack on top. Set on join, after respawn (healed to full, like
  vanilla), on entering a Lifesteal world, and right after every heart change.
- A PvP kill (killer and victim both online, in a Lifesteal world, with a loaded record) moves 1
  heart from victim to killer, in this order of checks:
  1. Teammates (issue #1348, `docs/lifesteal-teams.md`, "Team PvP"): killer and victim are in the
     same Lifesteal team, or were until the combat-tag time ago, and the team policy doesn't allow
     teammate heart transfers (the default); or team membership couldn't be read. Nothing happens,
     no cooldown starts; recorded as `TEAMMATES` / `TEAM_UNKNOWN`.
  2. Victim at the minimum (1): nothing happens, nothing drops, no cooldown starts.
  3. Same killer took a heart from this victim within the cooldown: nothing happens.
  4. Killer at the maximum: nothing happens (see "Open decisions").
  5. Otherwise victim −1, killer +1.
- Nobody is ever eliminated, banned or revived. Hearts never go below 1 or above the maximum.
- Deaths with no causing player (fall, lava, drowning, suffocation, void, mobs, fire) are vanilla
  deaths: no heart changes, **unless** the victim was still combat-tagged by another player
  (issue #1461, see "Environmental deaths during PvP" below).

### Kill attribution

The killer is `PlayerDeathEvent.damageSource.causingEntity` when that is a player: the attacker
for melee, the shooter for arrows/tridents/other projectiles, the igniter for player-lit TNT.
Not counted: self-kills, NPC players (`NPC` metadata), a killer who is offline or outside
Lifesteal, and deaths whose final damage had no causing player (except during an active combat
tag, below). The death handler runs at MONITOR with `ignoreCancelled`, so deaths another plugin
cancels never count.

### Environmental deaths during PvP (issue #1461)

Audit #1453 (P2-2(b)): a player who was losing a fight could jump into lava or the void, take
fall damage, drown or shoot themselves, and keep their heart, because only the final damage
counted. Now:

- **What counts as active PvP**: the existing combat tag (`CombatManager`, `config.yml >
  combat.tag-duration-seconds`, 15s by default). It is set only by an eligible PvP hit that
  actually landed (`CombatListener.onDamageTag` → `tagPvp`), so cancelled hits (PvP off,
  teammates with friendly fire off, the launch grace period, Mod Mode) never create one, and
  ranked and hosted Crystal PvP fights never tag. No new timer and nothing persistent: since this
  issue `tagPvp` also remembers, per tagged player, who hit them and when (newest first, at most
  8, memory only), and that list is dropped whenever the tag is removed (expiry, death, quit,
  offline sweep, plugin stop), so it can't outlive the tag or survive a restart.
- **Which deaths**: the direct attribution is `ENVIRONMENT` (no causing player: lava, fire, fall,
  void, drowning, suffocation, mobs, cactus, ...) or `SELF` (own arrow, own TNT, ...). A real PvP
  kill keeps its killer, and an invalid direct killer (NPC, offline, another gamemode) or an
  unloaded record is never replaced by someone else.
- **Who is credited**: the victim's tag must still be running at the moment of death, and an
  attacker's hit counts only while it is itself younger than one tag duration (refreshing your
  own tag by hitting a third player doesn't keep an older attacker's credit alive). Of those, the
  **most recent** attacker who passes the normal attribution checks (online, in a Lifesteal world,
  not an NPC, heart record loaded) is credited; if the newest attacker fails them, the next newest
  is used. The victim is never their own killer. Rules: the Bukkit-free
  `HeartRules.combatTagKiller`.
- **Never credited**: a death while not tagged (unrelated environmental deaths keep the old
  behaviour), a kill by the old combat-log villager path (`isCombatLogged`; there is still no
  combat-log heart penalty), a death while in a ranked match (`matchOf`; ranked hits never tag,
  so the ranked system provides no verified attacker for an environmental death), and `/kill`
  (`minecraft:generic_kill`).
- **Same transfer**: the credited death goes through the unchanged `HeartStore.transferPvp` with
  the same dedupe key as any other death (`pvp:<boot>:<victim>:<tick>`), so the launch window,
  teammates/allies (`heartCheck(killer, victim)`), the 1-heart minimum, the 20-heart maximum
  (`NO_TRANSFER`) and the 30-minute same-victim cooldown all apply exactly as for a sword kill, and
  a repeated death event moves at most one heart. Players see the normal heart messages naming the
  credited attacker; nothing about the combat tracking is shown.
- **Audit**: the transaction stays type `PVP` (so profile and rank statistics count it as a kill),
  and its audit reasons get ` (combat tag credit, died to <damage type>)` appended, e.g.
  `killed by <uuid> (combat tag credit, died to lava)` or `COOLDOWN (combat tag credit, died to
  fall)`, so staff can tell it from a direct kill. Direct kills' audit text is unchanged.
- **Timing**: `CombatListener` removes the tag at NORMAL priority of the death event, so
  `LifestealHeartListener.onDeathCaptureTag` copies it at LOWEST (Lifesteal victims only) and the
  MONITOR handler uses that copy only in the same tick; it's dropped on use and on quit.
- **Limitations**: a disconnect ends the tag, so a player who quits is handled by the combat-log
  / Combat NPC rules, not this; Combat NPC deaths keep their direct attribution; pet kills are
  still not credited to the owner; PvP coin stealing (`docs/lifesteal-bank.md`) and bounties keep
  their own final-damage attribution.

### Anti-farming

`lifesteal_heart_cooldowns` stores the last successful transfer per (killer, victim) pair, in
the database, so logging out, dying or restarting doesn't reset it. Only successful transfers
start it. It's one-directional: the victim can take the heart back right away. It runs on
server wall-clock time (`System.currentTimeMillis()`). A stored time in the future (the clock
went back) counts as "just now", so a clock change can lengthen one cooldown but never skip it.
A negative (corrupt) value counts as no previous transfer. PvP itself is never blocked.

## Persistence and safety

All in the Lifesteal server's own `data.db` (Survival never creates these tables):

| Table | Contents |
|---|---|
| `lifesteal_hearts` | uuid (PK), hearts, initial_granted, version, created_at, updated_at, last_transaction |
| `lifesteal_heart_cooldowns` | (killer, victim) PK, last_transfer_at |
| `lifesteal_heart_transactions` | id (UUID), `dedupe_key` UNIQUE, type PVP/ADMIN/WITHDRAW/REDEEM/ADMIN_GRANT/ADMIN_VOID, outcome, killer, victim, actor, time |
| `lifesteal_heart_audit` | transaction id, uuid, operation (INITIAL, PVP_LOSS, PVP_GAIN, PVP_REJECTED, ADMIN_SET/ADD/REMOVE, WITHDRAW, WITHDRAW_REJECTED, REDEEM, REDEEM_REJECTED, ADMIN_GRANT_ITEM, ITEM_VOID, ITEM_REFUND), previous/new hearts, actor, reason, time, item_quantity, item_serial |
| `lifesteal_heart_items` | serial (PK), status ISSUED/REDEEMED/VOIDED, source WITHDRAW/ADMIN, issued_to, issued_by, issue_transaction, issued_at, closed_by, close_transaction, closed_at |

- **Atomic:** each kill or admin change is one `databaseManager.transaction`. It reads the rows
  fresh under the DB lock, decides, and writes with `WHERE version = <read version>`. A row that
  changed in between makes the update hit nothing, which throws and rolls everything back. The
  victim never loses a heart unless the killer's gain, the cooldown, the transaction row and the
  audit rows commit with it.
- **Idempotent:** each death gets the key `pvp:<boot id>:<victim>:<server tick>`. A repeated
  event finds the key and does nothing. A rolled-back attempt leaves no key, so it can be retried.
- **Rejected kills are recorded too** (`PVP_REJECTED` with the reason: `VICTIM_AT_MINIMUM`,
  `COOLDOWN`, `KILLER_AT_MAXIMUM`, or a missing/unreadable record).
- **Sessions:** the manager keeps the hearts of online Lifesteal players whose row was read
  this session. A kill involving anyone without one is refused (logged). Quitting drops the
  session, and rejoining reads the row again, so a reconnect can't bring back old values.
- **No delayed saves:** every change is committed before it's applied to the player, so a
  disconnect or shutdown can't lose one, and there's no pending work to flush. These are small
  synchronous transactions on the local SQLite file (one per death / admin command / join),
  the same pattern the plugin's other one-shot claims use. Nothing runs per tick.
- **Database failure:** the transaction rolls back, both players are told nothing changed, and
  the console logs it. After shutdown, the closed database refuses all changes.
- **Malformed rows** (hearts not a whole number) are never reset or guessed. The player's health
  is left as it is, kills involving them are refused, and the console says to repair the row
  with `/lifesteal hearts set`. **Out-of-range rows** (e.g. after lowering `hearts.maximum`)
  are applied clamped. The stored value is only replaced by that player's next heart change.
- **Invalid `lifesteal.yml`:** hearts pause. Nothing is granted, stolen or changed, stored
  hearts and players' health stay as they are, and admin commands refuse. Fix the file, then
  `/joshymc reload`.

## Heart items (issue #1344)

A Heart item is one permanent heart in item form. Players make one with `/withdrawheart` and
turn it back into a heart by right-clicking it. Heart items can be stored, dropped, picked up,
put in chests and traded like any item; whoever redeems one gets the heart (it isn't tied to
who withdrew it), and moving an item never changes anyone's hearts.

### The item

- A **Nether Star** named "Heart" (`heart-items.display-name`), with simple lore, an optional
  glint and an optional resource-pack item model (`heart-items.item-model`, blank by default).
- **Identity is server-side.** The item's persistent data holds `joshymc:custom_item_id =
  lifesteal_heart`, a format version and a **serial** (a random UUID). The serial is written to
  `lifesteal_heart_items` in the same transaction that took the heart. Redemption only accepts a
  serial whose row is ISSUED, and moves it to REDEEMED in the same transaction that adds the heart.
  So:
  - a renamed Nether Star (or any item with the right name/lore) is not a Heart item at all;
  - a hand-made item with a made-up serial (NBT editor, `/give` with components) has no row and is
    refused ("forged", logged, AntiDupe alert);
  - a copy of a real item (creative middle-click, a dupe glitch, an inventory rollback) shares
    its serial, so only the first redemption works; later ones are refused as already redeemed
    (CRITICAL AntiDupe alert). Copies are never deleted automatically, just worthless.
  - No signature/HMAC is needed: the registry is stronger than signed data (it also catches
    copies) and items stay valid across restarts, renames, look changes and plugin updates.
- **Never stacks:** each serial is unique, and the item's max stack size is 1. Anything that
  claims to be a stack of hearts is refused. One redemption = one item = one heart.
- Not registered in the ItemManager, so `/joshymc give` can't make serial-less hearts. Staff use
  `/lifesteal hearts give` (below).
- Heart items are never crafting ingredients (a Nether Star is part of the beacon recipe), and
  right-clicking one never uses it as a vanilla item.
- **No recipe, shop, auction or trading GUI** exists (not in blueprint v1.1).

### `/withdrawheart`

Player-only, `joshymc.lifesteal.withdraw` (default true), scope LIFESTEAL. Checks, in order:
this server runs Lifesteal, hearts aren't paused, the player is in a Lifesteal world, alive and
online, their heart session is loaded, `heart-items.withdraw-enabled`, they aren't
combat-tagged (issue #1455, see below), the anti-spam gap
(`action-cooldown-millis`), they have more than the minimum (1) hearts, and they have an empty
inventory slot (Heart items don't stack, so a free slot is needed; the withdrawal is refused
otherwise: there's no other safe delivery for it). Then one transaction takes one heart (fresh
read, conditional update, never below the minimum, so the store refuses the final heart even if
the cached value were wrong) and issues the serial. Only after the commit is the item put into
the free slot, max health updated (10 → 9 hearts = 18 health) and the player data saved.

**No withdrawing in combat** (issue #1455). A combat-tagged player could otherwise turn their
hearts into items mid-fight, hand them to someone, and die at the minimum so the killer gets no
heart. The check reads the existing combat tag (`CombatManager.tagExpiresAt`, the same tag as
`combat.tag-duration-seconds`; no second tracker) through `HeartRules.combatBlocksWithdraw`, before
the anti-spam gap, the inventory check and the transaction. A refused withdrawal says "You cannot
withdraw hearts while in combat." and changes nothing: no heart taken, no serial issued, no
transaction or audit row, inventory and combat tag untouched. Once the tag expires, withdrawing
works normally again. Redeeming Heart items isn't restricted in combat.

### Alt-account heart farming (issue #1457)

Audit #1453 (finding P2-1): every new account starts with 10 hearts and can withdraw 9 of them as
tradeable Heart items, so a freshly made alt could turn into 9 hearts for a main account. Two
protections, neither of which removes trading:

**Withdrawal eligibility** (`heart-items.withdraw-eligibility`). `/withdrawheart` needs both:

- `minimum-lifesteal-playtime-minutes` (default 120) of **active Lifesteal playtime**, and
- `minimum-hours-since-first-join` (default 24) since the player's **first Lifesteal join**.

The first join is the heart record's `created_at` (written once, the first time the player
entered a Lifesteal world here); nothing new is stored for it. Playtime can't come from the
server-wide `/playtime` (`PlaytimeManager`): it also counts AFK time, time on a server that hosts
Survival too, and time from before Lifesteal existed. So the heart system keeps its own counter,
`lifesteal_heart_playtime` (milliseconds per UUID, only ever raised): a player counts while
online, alive, loaded in a Lifesteal world and not AFK (`AFKManager.isAfk`). Every sample (once a
minute, and on quit, world change, respawn, reload, shutdown and right before a withdrawal)
credits the stretch since the previous sample, at most two minutes at once. Offline time is never
counted, restarts keep everything already saved (at most the last minute is lost on a crash), and
the counter starts at 0 for everyone when this update is installed.

The check runs inside the withdrawal transaction, on a fresh read, before the heart is taken or
the serial issued; a refusal changes nothing (`WithdrawResult.NotEligible`). The player sees what
is missing (`messages.playtime`, `messages.account-age`, `{time}` = what is left, e.g. `1h 30m`).
Each refusal kind is written to `lifesteal_heart_audit` (WITHDRAW_REJECTED,
INELIGIBLE_PLAYTIME / INELIGIBLE_ACCOUNT_AGE) and `admin_logs` (HEART_WITHDRAW_INELIGIBLE) at most
once per player per 10 minutes. `/lifesteal hearts get <player>` shows the playtime and what is
still missing. `enabled: false` (or both values 0) lets everyone withdraw again, including
brand-new alts. The minimum (1), maximum (20), starting hearts, combat rule and all transaction
guarantees are unchanged.

**Shared-connection transfer review** (`heart-items.alt-transfer-review`). The only account
association JoshyMC has is AltManager's `player_connections` (salted connection hashes, issue
#862). Households, schools and public networks share connections, so that evidence is never
treated as proof of one owner and nothing is blocked on it: `policy` accepts only `review` (any
other value makes lifesteal.yml invalid). When a **withdrawn** Heart item is redeemed by someone
other than the player who withdrew it (the item row's `issued_to`) and the two accounts were ever
seen on the same connection hash, the redemption goes through unchanged and, in the same
transaction, one `lifesteal_heart_transfer_reviews` row (PK = serial, so once per item) and one
TRANSFER_REVIEW audit row are written. Staff also get a console warning and an `admin_logs` row
(HEART_TRANSFER_REVIEW, the two UUIDs and the serial). Staff-made items, your own items and
unrelated accounts are never reviewed. Players are told nothing; no connection hash or address is
shown or logged. Staff list them with `/lifesteal hearts reviews [player]` (admin permission).
No bans, punishments or confiscation are automatic; staff decide (e.g. `/alts`, then
`/lifesteal hearts set`).

**Limits.** Someone willing to play an alt for 2 active hours over at least a day can still
withdraw its hearts; the rule raises the cost, it can't stop that. Accounts on different
connections (VPN, mobile data) leave no AltManager evidence, so their transfers aren't reviewed.
AFK detection has its own delay, so a few idle minutes before someone is marked AFK still count.
Items withdrawn before this update stay valid.

### Redemption

Right-click (air, or a block that isn't interactable; sneak to redeem while looking at a chest or
door). Paper fires the click once per hand, so only one hand is handled per click: the main hand
when it holds a Heart item, otherwise the off hand. One attempt per player per tick and per
`action-cooldown-millis`, and re-entry is refused. Then one transaction checks the serial,
re-reads the hearts, refuses at the maximum (the store enforces it, not only the listener), marks
the serial REDEEMED, adds the heart and audits it. The item in that hand is removed only after the
commit; any refusal or failure leaves the item where it is. At 20 hearts the item is kept, nothing
is created and the player is told.

### Staff

```
/lifesteal hearts give <player> <amount>     # joshymc.lifesteal.hearts.give (op), online Lifesteal player, 1-36
/lifesteal hearts items <player>             # outstanding (unredeemed) items issued to a player
/lifesteal hearts void <serial> [refund]     # recovery, see below
/lifesteal hearts reviews [player]           # shared-connection transfers (issue #1457)
```

`give` makes real, recorded items (source ADMIN) without changing anyone's hearts. It needs
that many free slots, and the grant is one transaction with one ADMIN_GRANT audit row (quantity).

### Consistency, and what isn't atomic

SQLite can't make an inventory change atomic, so the order is: database first, inventory right
after, in the same server tick, on the main thread. Nothing else (another click, a command, a
disconnect, a shutdown) can run in between, because quits and shutdown are processed on the same
thread. What remains:

- **Crash after a withdrawal's commit, before the player's data file is saved:** the heart is
  gone but the item isn't in the saved inventory. The player data is saved right after every
  Heart item change (`player.saveData()`), which makes this window very small but not zero.
  Recovery: `/lifesteal hearts items <player>` lists the serial, and
  `/lifesteal hearts void <serial> refund` voids it (so the item can never be redeemed, should it
  turn up) and gives the heart back in one transaction (only for withdrawn items, never past the
  maximum).
- **Crash after a redemption's commit:** the heart is saved; if the inventory rolls back, the
  item comes back but its serial is already REDEEMED, so it can't be redeemed again. No dupe.
- **Inventory rollbacks by staff tools** behave the same: rolled-back hearts can't be redeemed twice.
- **Death:** Heart items drop like any item. Nothing in the heart system changes.
- Database failure or shutdown: the transaction rolls back (or the closed database refuses), the
  player is told nothing changed, and the item/heart stay as they were.

### Settings (`lifesteal.yml > heart-items`)

`withdraw-enabled`, `redeem-enabled`, `display-name`, `item-model`, `glint`,
`action-cooldown-millis` (0-60000) and `messages.*` (withdrawn, redeemed, final-heart, maximum,
inventory-full, redeem-failed; `{maximum}` is filled in). A file from before this section exists
gets the defaults with one warning. Nothing here can change the heart limits.
`withdraw-eligibility` (`enabled`, `minimum-lifesteal-playtime-minutes` 0-43200,
`minimum-hours-since-first-join` 0-720, `messages.playtime`, `messages.account-age`) and
`alt-transfer-review` (`enabled`, `policy: review`) are issue #1457; a file without them gets the
defaults (eligibility on, review on) with one warning each.

### Java and Bedrock

Heart items are a vanilla Nether Star with a name, lore and persistent data, which Geyser
translates as-is, so Bedrock players see a Nether Star named "Heart" (the visual fallback).
A configured `item-model` is Java-only: the Bedrock pack (`BedrockPackManager`) only maps
registered custom items, and the Heart item deliberately isn't one. Right-click on Bedrock
arrives as the same `PlayerInteractEvent`, with the same one-hand/one-tick guards. No packets,
no Geyser/NetherNet changes. **Not tested with real Bedrock clients yet.**

## Survival isolation

- `/withdrawheart` refuses on servers and worlds without Lifesteal, and redemption only runs
  there; on the Survival server the listener isn't even registered, so Survival inventories and
  custom items are untouched.
- The listener is registered only when this server hosts Lifesteal
  (`networkIdentityManager.hostedGamemodes`), and the tables are only created there.
- Every handler also checks the player's world with `gamemodeLockManager.gamemodeOf(world)`.
  On a server that maps worlds to both gamemodes, a player leaving a Lifesteal world gets the
  vanilla max health back. That only happens if the heart system had set it, which is marked in
  the player's data (`joshymc:lifesteal_hearts_applied`), so Survival players are never touched.
- The Overload custom enchant leaves max health alone in Lifesteal worlds.

## Java and Bedrock

Everything is keyed by UUID, so Floodgate UUIDs work like any other. Max health is the normal
attribute, which Geyser sends to Bedrock clients. There are no packets, no custom HUD, and no
Geyser/NetherNet changes. `/lifesteal hearts get` marks Bedrock players.

## Admin commands

`/lifesteal` is already the transfer command (issue #1331), so heart management is its `hearts`
subcommand. Plain `/lifesteal` still transfers.

```
/lifesteal hearts get <player>
/lifesteal hearts set <player> <amount>      # must be within minimum..maximum
/lifesteal hearts add <player> <amount>      # stops at the maximum and says so
/lifesteal hearts remove <player> <amount>   # stops at the minimum (1) and says so
```

Heart item staff commands (`give`, `items`, `void`) are described under "Heart items".

These need `joshymc.lifesteal.hearts.admin` (default op) and only work on a server that runs
Lifesteal. Offline players are found through the PlayerResolver (no Mojang lookups). A player
with no row (never played Lifesteal here) is refused instead of getting one created. Every change
is a transaction with an `ADMIN_*` audit row (staff UUID or `console`) and a console line. The
target, if online, is told their new heart count, but not who changed it.

## Open decisions (not in blueprint v1.1)

These are left for a later decision rather than invented:

1. **Killer at the maximum.** Implemented: nothing happens to either player, and no cooldown
   starts. This is fixed in code (`HeartRules.KILLER_AT_MAXIMUM`), not configurable. The
   alternative "victim still loses, killer stays at max" is implemented but unused
   (`VICTIM_LOSES_ONLY`). No Heart item drops and there's no other overflow reward.
2. **Environmental deaths after PvP** (knocked into lava or the void, fall damage from a hit).
   Decided in issue #1461: credited to the most recent valid attacker while the victim's combat
   tag runs (see "Environmental deaths during PvP").
3. **Combat logging** (issue #1350, `docs/lifesteal-combat.md`). No heart penalty for logging out
   itself (the blueprint defines none). A counted combat log in a Lifesteal world leaves a Combat
   NPC (issue #1402, `docs/lifesteal-combat-npc.md`); server shutdowns, staff kicks and server
   errors don't. **Killing that Combat NPC is a PvP kill of its owner**:
   `LifestealHeartManager.onCombatNpcDeath` sends it through the same `HeartStore.transferPvp` as
   a normal kill (dedupe key `combatnpc:<session>`), so the killer can gain a heart and the owner
   lose one, with every normal rule: kill attribution, the 1-heart minimum, the 20-heart maximum,
   the same-victim cooldown, teammates/allies, and the grace period/launch checks. An NPC that
   survives its lifetime moves no heart.
4. **Pets and indirect kills.** A tamed wolf's kill isn't credited to its owner (the wolf is the
   causing entity). Kills by crystals, beds or anchors count only when Paper reports a causing
   player.
5. **Heart item look, stacking and recipes** (issue #1344). The blueprint defines none of them:
   the look is configurable, items never stack because each has its own serial, and there is no
   recipe.

## Testing

Automated (no server): `./gradlew -I scripts/lifesteal-heart-check.init.gradle.kts lifestealHeartCheck`
runs `scripts/LifestealHeartCheck.kt` against real SQLite. It covers:

- the defaults and config validation, heart-to-health conversion and limits;
- attribution (environment, self, NPC, offline, other gamemode, not loaded, Java/Bedrock UUIDs);
- environmental deaths during PvP (issue #1461): direct kills untouched, lava/fall/void and
  self-inflicted deaths credited while tagged, no tag / no hits / expired tag / stale hit / hit
  in the future refused, the victim never credited, most recent of several attackers (and the
  fallback past an ineligible one), exclusions; then in front of a real store: one transfer,
  duplicate death events, the audit note, the cooldown, 1 heart, 20 hearts, teammates, allies,
  an attacker without a record; and the wiring (tag capture at LOWEST before the NORMAL untag,
  same-tick use, cleanup, ranked/Crystal PvP still untagged, Combat NPC deaths unchanged);
- every transfer outcome, cooldown arithmetic (clock going back, corrupt values), and admin set/add/remove and input parsing;
- new players getting 10 hearts once and keeping saved hearts;
- restart persistence of hearts, cooldowns and dedupe keys, and repeated death events;
- malformed and out-of-range rows;
- concurrent kills on one victim (exactly 9 move, hearts conserved), duplicate deliveries,
  reconnect storms and admin changes racing PvP (no lost updates);
- rollback on a mid-transaction failure and on a stale write, and a closed database;
- the Lifesteal-only wiring;
- Heart items (issue #1344): the `heart-items` settings (bundled defaults = the issue's messages,
  an old file without the section, invalid values); withdrawals at 10, 2 and 1 hearts; redemption
  at 8, 19 and 20; several items; trading (another player redeems); forged, garbage and copied
  serials; admin grants (limits, no record, hearts unchanged); void with and without refund, never
  past the maximum; restart persistence; migrating an issue #1341 audit table; 40 concurrent
  withdrawals (exactly 9), one item redeemed from 32 threads (once), concurrent redemptions at 19
  (stops at 20), withdrawals racing PvP (final heart kept); rollback of a failed withdrawal and
  redemption and of a stale write (item stays valid); a closed database; and the source wiring
  (session/inventory checks before the transaction, item handed over/taken only after the
  commit, one hand per click, no ItemManager registration, permissions, LIFESTEAL scope);
- combat-tagged withdrawal (issue #1455): the tag rule (untagged allowed, tagged refused up to
  the expiry, allowed from the expiry on, rapid repeats refused), refused attempts in front of a
  real store (hearts, version, serials, transactions and audit unchanged), withdrawal after expiry,
  the final heart still kept, and the wiring (existing combat tag read before the cooldown,
  inventory, re-entry guard and transaction; `/withdrawheart` is the only entry point; redemption
  and Combat NPC heart transfers unchanged).

Manual, on a real Paper 26.2 Lifesteal server (not done yet, so gameplay is unverified):

1. A new Java and a new Bedrock player join: 10 hearts on both clients.
2. A kills B with a sword: A 11, B 10 → 9, and both see the messages. Repeat within 30 minutes:
   no change, cooldown message. B kills A: a transfer happens.
3. A bow kill transfers. Fall, lava, void, mob and `/kill` deaths don't.
4. Get a victim down to 1 heart, kill them: no change. Get a killer to 20, kill: no change.
5. Respawn: correct max health, full health. Relog and restart: hearts kept.
6. Test `/lifesteal hearts get/set/add/remove` on online and offline players, including bad
   input and an account without permission.
7. On the Survival server: max health stays vanilla, `/lifesteal hearts` refuses, and no
   `lifesteal_*` tables exist in its `data.db`.

Heart items (issue #1344), also not yet run on a live server:

8. At 10 hearts, `/withdrawheart`: 9 hearts (18 health), one Heart item in a free slot. At 1
   heart: refused, nothing made. With a full inventory: refused.
9. Right-click the item at 8 hearts: 9, item gone. At 20: refused, item kept. Hold Heart items in
   both hands and right-click: only one is redeemed. Hold right-click / spam-click: one per gap.
10. Drop the item, have another player pick it up and redeem it. Store it in a chest, restart,
    take it out, redeem it.
11. Creative middle-click a Heart item to clone it, redeem both: the second is refused. Rename a
    Nether Star "Heart" in an anvil: right-click does nothing. Try a beacon recipe with a Heart.
12. Repeat 8-10 on a Bedrock client (Geyser + Floodgate): the item shows as a Nether Star named
    Heart, redemption works once per tap.
13. `/lifesteal hearts give <player> 3`, `items <player>`, `void <serial> refund`, and the same
    commands without permission.
14. On the Survival server, `/withdrawheart` says hearts only exist on Lifesteal.

Combat withdrawal (issue #1455), also not yet run on a live server:

15. Hit another player (both tagged), then `/withdrawheart` (also spammed, and as
    `/joshymc:withdrawheart`): "You cannot withdraw hearts while in combat.", hearts and inventory
    unchanged, `/lifesteal hearts items <player>` shows no new item, and the tag still runs out at
    the same time. After the tag expires, `/withdrawheart` works.
16. While tagged, right-click a Heart item: it still redeems. Kill a Combat NPC: hearts still move
    as before. In a ranked match, `/withdrawheart` still gives the ranked-match message.

Environmental deaths during PvP (issue #1461), also not yet run on a live server:

17. A hits B, B jumps into lava (then: the void, a long fall, drowning, own arrow): A 11, B 9, the
    normal messages; `/lifesteal hearts` audit shows `(combat tag credit, died to ...)`.
18. A hits B, wait for "You are no longer in combat.", B dies to lava: no change. B dies to lava
    without being hit at all: no change.
19. A hits B, then C hits B, B jumps into the void: C is credited. Same with C logged out first:
    A is credited.
20. Repeat 17 within 30 minutes: cooldown message. With B at 1 heart, or A at 20: no change.
21. In a ranked match, die to lava: no heart moves outside the ranked rules. `/kill` while
    tagged: no change. On Survival, die to lava while tagged: nothing Lifesteal happens.
