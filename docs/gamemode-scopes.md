# Gamemode scopes (issue #1329)

Every JoshyMC command and the Survival gameplay features that run without a command declare a
`GamemodeScope`. This builds on the gamemode locks from issue #1289 (`gamemode/`) and the server
identity from issue #1323 (`docs/network.md`); there is no second framework.

| Scope | Meaning |
|---|---|
| `NETWORK` | Works on every server, never affected by a lock. |
| `SURVIVAL` | Survival SMP gameplay. Stops outside Survival **only while Survival is locked**. |
| `LIFESTEAL` | Lifesteal gameplay (nothing is assigned yet). |
| unassigned | Never affected (same as before this change). |

## How enforcement works

The rule is `GamemodeIsolation.allowed(scope, current, locked)`: a scope works in the current
gamemode unless its own gamemode is locked and the current gamemode is a different one. A lock
never restricts its own gamemode, so **nothing changes on the Survival server**, locked or not.

- **Commands**: `GamemodeAssignments.COMMANDS` (keyed by plugin.yml name). The executor and tab
  completer of every SURVIVAL/LIFESTEAL command are wrapped in a `GatedCommand`, so aliases,
  `joshymc:` names, `/execute`, `dispatchCommand`, NPC/GUI-run commands and **every subcommand**
  (they all share the executor) are checked. Typed commands are checked again at LOWEST, refused
  commands are hidden from the client's command list, and the sender is told
  "That command is only available on Survival." Bukkit's permission check still runs as before;
  the gate never grants anything.
- **Listeners**: gameplay listeners are registered with `GamemodeScopedListeners.register(plugin,
  listener, feature)`. Each `@EventHandler` keeps its priority and `ignoreCancelled` and skips events
  whose world (player/entity/block/inventory viewer) is outside the feature's gamemode. Join, quit,
  kick and world-change events always run so per-player state is still cleaned up. While nothing is
  locked the wrapper does no lookup at all.
- **Rewards and scheduled tasks** check `gamemodeLockManager.isAllowed(GamemodeFeature.X, player.world)`.

## Scope matrix

### NETWORK commands

| Area | Commands |
|---|---|
| Plugin / server administration | `joshymc`, `jgamemode`, `jrestart`, `world`, `dimension`, `gencave`, `portal`, `holo`, `npc`, `spawndecor` |
| Store fulfillment (Tebex, console) | `purchase` (refusing it would mark a purchase done without recording it) |
| Ranks | `rank`, `freerank` (starter rank is per gamemode) |
| Moderation | `ban`, `tempban`, `unban`, `mute`, `tempmute`, `unmute`, `warn`, `unwarn`, `pkick`, `history`, `punish`, `playerdata`, `alts`, `report`, `reports`, `admin`, `jmc-violation` |
| Staff utilities | `vanish`, `modmode`, `hidestaff`, `staffchat`, `stafftag`, `sj`, `invsee`, `tp`, `tphere`, `sudo`, `smite`, `heal`, `feed`, `god`, `speed`, `enchant`, `gamemode`, `gmc`, `gms`, `gma`, `gmsp`, `chat` |
| Communication / accounts | `link`, `unlink`, `msg`, `reply`, `messages`, `ignore`, `announce`, `live`, `promote`, `nick`, `timezone`, `settings`, `scoreboard`, `serverbar`, `jmc-ecview` |
| Information | `help`, `rules`, `media`, `discord`, `ip` |
| Recovery | `unstuck` |

### SURVIVAL commands

| Area | Commands |
|---|---|
| Economy / currencies | `eco`, `balance`, `pay`, `baltop`, `tokens`, `credits`, `creditspay`, `cshop`, `worth`, `sell`, `sellwand` |
| Markets, shops, auctions, orders, trading | `shop`, `market`, `invest`, `stock`, `ah`, `orders`, `order`, `chestshop`, `trade`, `overflow`, `candy`, `eventshop` |
| Gambling | `coinflip`, `ccoinflip`, `casino`, `mines`, `roulette`, `crash`, `towers` |
| Giveaways, gifts, vouchers, crates, kits | `giveaway`, `gift`, `voucher`, `creditvoucher`, `rankvoucher`, `crate`, `crateeditor`, `kit`, `createkit`, `editkit`, `deletekit` |
| Claims, teams, storage | `claim`, `unclaim`, `subclaim`, `team`, `pv`, `enderchest`, `hopper`, `spawner` |
| Quests, progression, streaks, votes | `quests`, `daily`, `eventquests`, `resurge`, `booster`, `loginstreak`, `mcr`, `jvote`, `talisman`, `fish` |
| Cosmetics and chat perks | `cosmetics`, `killeffect`, `tag`, `chatcolor`, `signitem`, `skull` |
| Custom enchants | `customenchant` |
| Survival PvP | `pvp`, `arena`, `bounty`, `killstreak`, `killtop`, `deathstop`, `leaderboard`, `mutations` |
| Hosted events | `event` |
| Travel | `warp`, `setwarp`, `delwarp`, `editwarp`, `pwarp`, `home`, `sethome`, `delhome`, `phome`, `rtp`, `resource`, `back`, `top` |
| Rank perks and flight | `fly`, `flytime`, `nightvision`, `condense`, `hat`, `craft`, `anvil`, `smithing`, `stonecutter`, `repair`, `trash` |
| Other | `fakebase`, `spawnstash`, `tutorial` |

### Deliberately unassigned

`spawn`, `setspawn`, `tpa`, `tpahere`, `tpaccept`, `tpdeny`, `tpacancel`, `afk`, `sit`, `playtime`,
`playtimetop`, `chatgame`: shared basics whose Lifesteal behavior isn't decided yet. They keep
working everywhere. The reasons are in `GamemodeAssignments.UNASSIGNED`.

### Features (`GamemodeFeature`, all SURVIVAL)

| Feature | Where it is enforced |
|---|---|
| `custom-enchants` | `CombatEnchantListener`, `ToolEnchantListener`, `WeaponEnchantListener` (scoped listeners) |
| `custom-items` | `DrillMiningListener`, `EasterEggListener`, `SellWandListener`, `PeanutButterCupListener`, `LinkPopListener`, `JosheesBarListener`, `RurusListener`, `CandyCornListener` (scoped listeners) |
| `mining-perks` | `VeinminerListener`, `AutoSmeltListener`, `TreeFellerListener` (scoped listeners) |
| `quests` | `QuestCycleManager.incrementProgress` (every quest objective, including the timers) |
| `event-quests` | `EventQuestManager.forActive` (every Event Quest objective, including the playtime tick) |
| `login-streak` | `LoginStreakManager.tickAccrual` (playtime accrual and the daily reward) |
| `afk-rewards` | `AFKManager.giveRewards` (the countdown restarts, so time there is never paid later) |

Already scoped before this issue and unchanged: the Viking, Krampus and Snowman sets and Bat Wings
(`CustomItem.gamemodeScope`, checked by `/joshymc give` and crates), the Event Shop, `/flytime`,
Fly Vouchers and Survival flight time, beacon potions (per-gamemode settings) and `/punish` scopes.

### Kept unscoped on purpose

Protection and anti-exploit listeners stay active everywhere so nothing becomes exploitable where
Survival is locked: `ClaimProtectionListener`, furniture/pie protection, `VikingBoomerangListener`
(grindstone refusal), `TomatoListener` (zeroes snowball damage), `SellWandAnvilListener`,
`CustomArmorAnvilListener`, `GuiManager`, punishments and the anticheat.

## Activating Survival isolation safely

The bundled defaults (`gamemode-lock.server-gamemode: survival`, `worlds: {}`, `commands: {}`, nothing
locked) behave exactly as before. The live Survival server needs no change, and locking Survival
there changes nothing either (a lock never restricts its own gamemode).

On the **Lifesteal** server (its own config.yml and database, see `docs/network.md`):

```yaml
network:
  server-id: lifesteal
  primary: false
gamemode-lock:
  server-gamemode: lifesteal
```

then, once the server is up, run `/jgamemode lock survival confirm` (needs `joshymc.jgm.lock`). The
lock is stored in that server's database and survives restarts. From then on every SURVIVAL command
and feature above is refused/skipped there, and NETWORK and unassigned ones keep working. Undo with
`/jgamemode unlock survival confirm`. `/jgamemode info` shows the state.

On one server that hosts both gamemodes in different worlds, map the Lifesteal worlds under
`gamemode-lock.worlds` (e.g. `lifesteal_world: lifesteal`) and lock Survival; the rules then apply per
world.

## Limitations and future work

- **Managers still start on Lifesteal.** Every manager (economy, claims, market drift, spawners,
  crates, ...) still initializes and runs its own tables and timers on the Lifesteal server; with
  Survival locked nothing can reach them through commands, but they aren't switched off. Skipping
  their startup safely needs each one checked for lateinit and cross-manager dependencies.
- **Tick-driven effects aren't gated yet**: passive custom enchants (`PassiveEnchantListener`, e.g.
  Overload changes max health), Quick Hit attribute refreshes, custom armor set bonuses, talismans,
  skills, fishing and mob stacking. They need a "clear on entering a non-Survival world" path before
  they can be scoped, so they are left alone for now. This matters only where Survival items can be
  carried into a Lifesteal world (the world-override setup); separate servers have separate inventories.
- **Claims keep protecting everywhere**: removing protection is a griefing risk, so only claim
  creation (commands) is scoped.
- **Votes, Tebex purchases and chat games** still pay out on whichever server receives them (into
  that server's own database). Votifier and Tebex should be pointed at the right server.
- **Data isolation**: balances, ranks recorded by JoshyMC, quests, punishments and playtime are
  already per server (local SQLite). Nothing here shares or migrates data.
- Commands from **other plugins** can be scoped through `gamemode-lock.commands`; JoshyMC's own
  scopes can't be overridden there.

## Testing

`./gradlew -I scripts/gamemode-scope-check.init.gradle.kts gamemodeScopeCheck` runs
`scripts/GamemodeScopeCheck.kt`: every plugin.yml command is classified, earlier scopes are kept,
aliases resolve to their command, the gate wraps whole executors and tab completion without touching
permissions, Survival/unlocked-Lifesteal/locked-Lifesteal scenarios for every command and feature,
cleanup events always reach scoped listeners, the listener/manager wiring, and the bundled config
defaults.
