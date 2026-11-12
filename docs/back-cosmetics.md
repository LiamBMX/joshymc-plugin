# Back cosmetics: Eagle Wings (issue #1516)

## What it is

**Eagle Wings** (`eagle_wings`, Survival only) is a chest item. Underneath it is a plain
vanilla **Netherite Chestplate**:

- vanilla armor, toughness, knockback resistance and durability
- normal enchanting and repair
- no gliding, no abilities, not unbreakable

While it is worn in the chest slot, a huge 3D pair of eagle wings (about 3.7 blocks tip to
tip, model `joshymc:eagle_wings`) is drawn on the wearer's back.

The worn armor layer (`equipment/eagle_wings.json`) is a leather harness with a gold eagle
crest, which the wings hang from.

To get one, staff run `/joshymc give eagle_wings [amount] [player]` (`joshymc.give`). It is
refused where Survival is locked away.

## Equip and unequip

- **Equip:** put it in the chest slot. Right-click, drag, shift-click and dispensers all work,
  like any chestplate. The wings appear within 5 ticks.
- **Unequip:** take it out of the chest slot. The wings are removed within 5 ticks.

There is no separate cosmetic slot or command.

## Why the wings are a display entity, not an elytra

Vanilla elytra (`wings` equipment layers) are two fixed 10x20 px boxes, so they can't be any
bigger. Instead, `cosmetic/BackCosmeticManager` spawns one **non-persistent `ItemDisplay`**
per wearer. It shows the item model untransformed (`ItemDisplayTransform.NONE`), scaled 1.25.

**Follows, never rides.** The display is teleported to the wearer every tick:

- yaw follows the wearer's body yaw (not their head)
- the client smooths each move over `back-cosmetics.smoothing-ticks` ticks
  (`teleport_duration`)
- crouching blends to a lower, forward-tilted pose over 3 ticks
- the mount position scales with the wearer's `scale` attribute

It is deliberately **not** a passenger. Paper refuses to teleport a player who has passengers
unless the caller passes `RETAIN_PASSENGERS`, so a passenger would break `/spawn`, `/home`,
RTP, warps and every other teleport for the wearer.

The cost is that a fast-moving wearer looking at themselves in F5 sees the wings trail by
about one tick of movement.

## Cleanup

There are no orphans and no duplicates:

- Displays are `isPersistent = false`, so they are never saved with a chunk.
- Each display is tagged `joshymc_back_cosmetic` and carries a PDC owner UUID.
- There is one display per wearer. A new one is only spawned after the old one is removed.

A display is removed when any of these happens:

- the item is unequipped
- the wearer quits, dies or changes world
- a hide reason applies (see below)
- the plugin reloads (`/joshymc reload`) or shuts down (a `ShutdownSequence` step)

A display that ends up more than 6 blocks from its wearer (after a teleport) is respawned
next to them.

Untracked tagged displays are removed at start and whenever a chunk's entities load.

## Who sees the wings

Displays are hidden by default. They are shown only to:

- Java players whose JoshyMC resource pack reported `SUCCESSFULLY_LOADED`
- who can see the wearer (`canSee`)
- who are in the same world as the wearer

The wearer sees their own wings unless `back-cosmetics.show-to-wearer: false`.

The wings are hidden from everyone while the wearer:

- is vanished, in Mod Mode, invisible, in spectator or dead
- is combat-tagged (`back-cosmetics.hide-in-combat`, default true), so the wings never block
  anyone's view in a fight
- is in the event world
- is not standing or sneaking (gliding, swimming, sleeping, riptide)
- is in a world that isn't a Survival world

## Gameplay safety

The display has no hitbox and no collision, and it can't be attacked. It doesn't change the
wearer's hitbox, reach or movement.

The item is an ordinary chestplate. Combat, anticheat, vanish, ranked, hearts, Elo and
inventory snapshots/restores only ever see a Netherite Chestplate.

## Fallbacks

- **Java without the JoshyMC pack:** never sent the display (no missing-model cube). They see
  the vanilla Netherite Chestplate armor.
- **Bedrock:** never sent the display. The icon comes from the Geyser mapping; worn, the
  wings show as Geyser's netherite chestplate armor.
- **Missing model asset:** the display would show the missing-model cube to Java players with
  the pack. Since the model ships in the same bundled pack, this only happens with a
  mismatched pack. Inventories and equipment are never affected.
- **Turning it off:** `features.back-cosmetics: false` stops every display. The item stays a
  normal chestplate.

## Adding more wings

1. Write an art module in the back frame: model (8, 8, 8) is the mount point between the
   shoulder blades, +Z points away from the back, and every part stays at z >= 6. Set
   `BACK_SCALE` in the module.
2. Write a `CustomItem` that implements `BackCosmeticItem` (`backModel`, `backScale`) and
   register it.

`art/render.py` adds "on the back" views to the render for any module with `BACK_SCALE`. Its
`BACK_ANCHOR` matches `BackCosmeticRules.STANDING`.

## Checks

```
./gradlew -I scripts/back-cosmetic-check.init.gradle.kts backCosmeticCheck
```

## Manual Paper test

1. Join with the resource pack. Run `/joshymc give eagle_wings`, then equip it. The wings
   appear. Press F5: they sit centred on the back.
2. Walk, sprint, jump, turn on the spot and crouch. The wings stay attached, turn with the
   body and dip forward when you crouch.
3. Unequip it. The wings disappear. `/kill @e[type=item_display,tag=joshymc_back_cosmetic]`
   reports nothing left behind.
4. Run `/spawn`, `/home`, and go through a Nether portal. The teleports succeed and the
   wings follow.
5. Relog, `/joshymc reload`, then restart the server. The wings come back once, and the
   kill command above finds no duplicates.
6. Get combat-tagged, vanish, drink invisibility, enter spectator mode, glide with an elytra
   swap, and swim. The wings hide each time and come back afterwards.
7. Check that a second Java account without the pack, and a Bedrock account, see only the
   chestplate.
