# Mobs OFF (`mob_visibility` setting)

Issue #1421. Works the same on Survival and Lifesteal (the listener is unscoped).

## What it does

A player who turns **Mob Visibility** off in `/settings` stops seeing:

| Kind | Examples | Hidden? |
|---|---|---|
| Hostile | Zombie, Skeleton, Creeper, Spider, Cave Spider, Endermite, Piglin Brute, Hoglin, Warden, any type not listed below | Always |
| Neutral | Enderman, Piglin, Zombified Piglin, Wolf, Bee, Iron Golem, Polar Bear, Llama, Trader Llama, Panda, Goat, Fox, Dolphin, Nautilus, Sulfur Cube | Only while aggressive |
| Passive | Cow, Sheep, Pig, Villager, Axolotl, Cat, Horse, Snow/Copper Golem, Happy Ghast, ... | Never |

Always visible whatever their kind: tamed animals, villagers, armor stands, xray helper shulkers.
The lists live in `mobvisibility/MobVisibilityRules` (Bukkit-free).

"Aggressive" means the mob's live target is a player, or its own anger state is on:
Wolf / Zombified Piglin `isAngry`, Bee anger > 0, a screaming Enderman. Hunting other mobs
(a wolf chasing sheep, an iron golem fighting zombies) is not aggression for this setting.

## Visibility only

The setting only calls Paper's `Player.hideEntity` / `showEntity` for that player. It does
**not** cancel spawning, targeting or damage, touch AI, drops or despawning, or change what
other players see. (Before #1421 it also cancelled mob targeting/damage and all explosion,
magic and wither damage for Mobs OFF players; that is gone, so a hidden mob can now hurt a
player who has Mobs OFF, the same as for everyone else.) `showEntity(plugin, ...)` only lifts
JoshyMC's own hide, so vanish and other plugins' hides are kept.

## How changes are detected

- Spawns (`CreatureSpawnEvent`) and every entity added to a world, including chunk loads
  (`EntityAddToWorldEvent`).
- Target changes (`EntityTargetEvent`) and damage to a neutral mob put it on a watch list;
  the mob is re-checked one tick later and every 10 ticks while watched, and dropped after
  3 seconds calm or when it leaves the world. Anger that simply expires is seen there.
- A sweep every 2 seconds, only around players with Mobs OFF (64 blocks), re-hides hostiles
  and catches aggression no event reported.
- Join, world change, teleport and toggling the setting re-apply to the whole world.
- Paper keeps a player's hidden entities by UUID, so leaving and re-entering tracking range
  keeps the hide.

## API limitations

- **Goat**: ramming is brain-driven and not exposed as a target, so a goat stays visible while
  it rams.
- **Piglin**: no anger API; it counts as aggressive while it targets a player (no gold armor,
  or provoked). A piglin admiring gold is visible.
- **Enderman**: aggression is its player target or `isScreaming`; the stare itself has no event.
- **Bee / Wolf / Zombified Piglin**: anger toward any target counts, also toward a mob.
- Brain-driven targeting that fires no `EntityTargetEvent` is caught by the 2-second sweep,
  so it can take up to 2 seconds to hide.
- A mob already shown by the client when it turns aggressive is removed from that client by
  Paper (`hideEntity` despawns it client-side); it reappears when it calms down.

Checks: `./gradlew -I scripts/mob-visibility-check.init.gradle.kts mobVisibilityCheck`.
