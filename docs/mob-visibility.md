# Mobs OFF (`mob_visibility` setting)

Issue #1475 (replaces the #1421 "visibility only" behavior). Works the same on Survival and
Lifesteal (the listener is unscoped). The setting is the existing `mob_visibility` player
setting in `/settings` (stored by SettingsManager, so it survives reconnects and restarts).

## What it does

For a player with **Mob Visibility** off, every *covered* mob is:

1. **hidden** from that player (Paper `Player.hideEntity`), and no one else;
2. **unable to target** them (`EntityTargetEvent` cancelled at HIGHEST; existing targets and
   Warden anger are dropped on toggle and by the 2s sweep);
3. **unable to hurt** them: melee, projectiles (arrows, tridents, fireballs, shulker bullets,
   llama spit), creeper/ghast explosions, evoker fangs, guardian beams, fire from a mob, a
   witch's splash potions and lingering clouds, and the source-less mob effects (Elder
   Guardian Mining Fatigue and mob melee effects = cause `ATTACK`, Warden Darkness = `WARDEN`);
4. **unhittable and unusable** by that player (damage they cause to a covered mob is
   cancelled, and right-clicks on one are refused), so hidden mobs can't be farmed.

Nothing changes for players with Mobs ON, and nothing is spawned, removed or changed globally.

## Issue #1481 hardening

The #1475 damage rule itself was sound (attributed by `damageSource.causingEntity`, cancelled
at HIGHEST), but several paths around it could still let harm through or leave a mob visible:

- **Cancellation depended on listener order.** The hit was cancelled at HIGHEST with
  `ignoreCancelled = true`; any listener running later (another plugin's HIGHEST handler
  registered after JoshyMC, or a MONITOR one) that un-cancelled it let the damage land. Damage
  and targeting are now re-asserted at MONITOR (logged once per restart when it happens).
- **Projectiles still hit.** Cancelling the damage made arrows bounce off and let fireballs,
  wind charges and shulker bullets explode or apply on impact. `ProjectileHitEvent` is now
  cancelled, so a covered mob's projectile passes through a Mobs OFF player (and their own
  projectiles pass through hidden mobs).
- **Knockback without damage** (Breeze wind charges, Ravager roars, explosion pushes) isn't a
  damage event and could knock players into fall damage. Paper's
  `EntityPushedByEntityAttackEvent` / `EntityKnockbackByEntityEvent` are now judged too.
- **Attribution gaps**: a damager that isn't a projectile (Evoker Fangs, a lingering cloud, an
  Ender Dragon part) was judged as itself. They now resolve to their owner / the dragon, so
  dragon parts are also unhittable for Mobs OFF players.
- **Visibility**: JoshyMC skipped its own hide when another plugin had already hidden the mob,
  so it reappeared when that plugin showed it again; it now always registers its hide. Join
  hides immediately (plus 5 and 40 ticks later) and respawns re-apply.
- Every path (damage, projectiles, knockback, fire, splash potions, clouds) uses the one
  Bukkit-free rule `MobVisibilityRules.blocksHarm(source, victim)`; bosses and the non-`Enemy`
  hostiles (Pufferfish) are named in `HOSTILE_TYPES`.

## Classification

Rules: `mobvisibility/MobVisibilityRules` (Bukkit-free). Only `Mob` entities are ever covered.

| Kind | Rule | Examples | Covered? |
|---|---|---|---|
| Neutral | `NEUTRAL_TYPES` | Enderman, Piglin, Zombified Piglin, Wolf, Bee, Iron Golem, Polar Bear, Llama, Trader Llama, Panda, Goat, Fox, Dolphin, Nautilus, Sulfur Cube | **Always**, provoked or not |
| Hostile | `HOSTILE_TYPES` (bosses, Pufferfish, ...), implements Bukkit `Enemy`, **or** not in any list | Zombie (all variants), Skeleton/Stray/Bogged/Wither Skeleton, Creeper, Spiders, Witch, Slime, Magma Cube, Phantom, illagers, Ravager, Guardians, Blaze, Ghast, Piglin Brute, Hoglin, Zoglin, Silverfish, Endermite, Shulker, Warden, Breeze, bosses, any future mob | Always |
| Passive | `PASSIVE_TYPES` and not `Enemy` | Cow, Sheep, Pig, Villager, Axolotl, Cat, Horse, Snow/Copper Golem, Happy Ghast, ... | Never |

Never covered whatever their kind: tamed animals (anyone's pets, including tamed llamas),
villagers, armor stands and other non-`Mob` living entities (Mannequin NPCs, combat-log NPCs),
Citizens NPCs (`NPC` metadata), xray helper shulkers (`joshymc_xray`) and Mob Waves event mobs
(`joshymc_event_mob`, so hosted events play normally).

## Attribution

Damage is judged by `damageSource.causingEntity`, falling back to the damager; an intermediate
entity resolves to its owner (projectile shooter, primed TNT igniter, Evoker Fangs owner,
lingering cloud source, dragon part's dragon). Only mob-caused harm to a Mobs OFF player is cancelled:
player TNT, other players' potions, PvP, fall, lava, fire ticks and drowning are untouched (the
pre-#1421 blanket explosion/magic cancel made Mobs OFF players immune to player TNT and potions;
that is not repeated).

## When it applies

- Spawns (`CreatureSpawnEvent`) and every entity added to a world, including chunk loads
  (`EntityAddToWorldEvent`).
- Join (at once, then 5 and 40 ticks later), respawn, world change and teleport (1 and 40
  ticks later), and toggling.
- A sweep every 2 seconds, only around players with Mobs OFF (64 blocks, loaded entities only).
- Toggling ON lifts JoshyMC's hides (`showEntity(plugin, …)`, so vanish and other plugins'
  hides stay), and doesn't force any target: AI resumes naturally.
- No per-player state is kept by the listener; Paper's hidden set ends with the session.

## Limitations

- **Collision**: a hidden mob still exists server-side. The client can't collide with an entity
  it doesn't know, and covered mobs don't chase the player, but server-side pushing between a
  mob and the player can still happen if a mob walks into them. Paper has no per-player
  collision API short of per-entity exemption lists, which would grow without bound.
- **Environmental farms**: a mob killed by lava, fall damage or another mob still drops loot and
  XP normally (drops are never changed); only damage the Mobs OFF player causes is blocked.
- **Sounds/particles**: some mob sounds are played at a location, not by the entity, and can
  still be heard.
- **Damage over time** (Poison, Wither ticks) has no source entity. Mob effects are refused
  when applied (`ATTACK`/`WARDEN` causes; blocked hits and projectiles never apply theirs), but
  an effect a mob gave the player *before* they turned Mobs OFF runs out normally.
- Another plugin's MONITOR listener running after JoshyMC's can still un-cancel; MONITOR
  handlers aren't supposed to change events, and JoshyMC logs it when it sees one.
- Damage another plugin deals with no source at all (`setHealth`, `damage(x)` without a damager)
  can't be attributed to a mob and isn't blocked.
- Targeting set by brain AI without an `EntityTargetEvent` is cleared by the sweep within 2s;
  it can't deal damage meanwhile, because damage is judged separately.

## Live-server checklist

With two players A (Mobs OFF) and B (Mobs ON) standing together:

1. Zombies, skeletons, creepers already loaded vanish for A at once; B still sees them.
2. Unprovoked Enderman, Piglin, Wolf, Iron Golem, Bee, Llama, Goat vanish for A.
3. Anger a Zombified Piglin with B, then turn A's setting off while it targets A: it vanishes and
   stops attacking A, keeps attacking B.
4. New spawns (spawn egg, night spawns) and mobs in freshly loaded chunks are hidden for A.
5. Skeleton arrows, ghast fireballs, a creeper exploding next to A (lit by B), a witch's potion,
   Warden Darkness / Elder Guardian Mining Fatigue: none affect A; B is affected normally.
6. A's own TNT, a player's harming potion and fall/lava damage still hurt A.
7. A can't damage hidden mobs (sweeping edge, arrows into a crowd) and can't right-click them.
8. Cows, villagers, tamed wolves/cats stay visible and usable for A.
9. Reconnect, restart, `/rtp`, warp and world change keep everything hidden for A.
10. Turn A's setting back on: mobs reappear immediately and behave normally; no duplicates.
11. A Mob Waves event: event mobs are visible and fight A normally.
12. Breeze wind charges and Ravager roars don't push A; a Breeze's charge flies through A.
13. Ender Dragon (End): A doesn't see it, takes no melee/breath damage and can't hit it.
14. A and B fight each other (PvP): damage is normal both ways.
15. Check the log for `[MobVisibility] Another listener un-cancelled`: if it appears, another
    plugin is overriding damage events (JoshyMC still re-cancels them).

Checks: `./gradlew -I scripts/mob-visibility-check.init.gradle.kts mobVisibilityCheck`.
