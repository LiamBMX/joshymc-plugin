# Shared GUI icons (issue #1355)

Two custom items exist to be menu buttons on every gamemode (Survival and Lifesteal):

| Icon | `GuiIcon` | Custom item id | Base | Name | Item model |
|---|---|---|---|---|---|
| Discord logo (blurple app badge, white Clyde) | `GuiIcon.DISCORD` | `discord_icon` | `STICK` | `&9&lDiscord` | `joshymc:discord_icon` |
| YouTube play button (red rounded rectangle, white triangle) | `GuiIcon.YOUTUBE` | `youtube_icon` | `STICK` | `&c&lYouTube` | `joshymc:youtube_icon` |

They're visual assets, not player data: nothing is stored, synced or scoped per gamemode
(`gamemodeScope = null`). Existing menus are not changed. Later GUI redesigns add the icons
where they need them.

## Using an icon in a menu

```kotlin
val discord = GuiStyle.icon(
    GuiIcon.DISCORD,
    Component.text("Join our Discord", NamedTextColor.BLUE),
    listOf(Component.text("Click for the invite link", NamedTextColor.GRAY)),
)
gui.setItem(13, discord) { player, _ -> /* the menu's own handler and permission checks */ }
```

- Every call returns a **new** `ItemStack`, built from the registered custom item (`ItemManager`),
  so it's safe to edit further. Shared stacks are never handed out.
- `name` replaces the default name (`&9&lDiscord` / `&c&lYouTube` when omitted). Only the `lore`
  you pass is shown, never the item's own lore. Both are made non-italic.
- Clicks, permissions and navigation stay with the menu. `GuiManager` already cancels clicks on
  GUI slots, so an icon can't be taken out of a menu.
- If the item weren't registered, `icon` still returns a Stick with the icon's model, so a menu
  never shows a bare stick.
- To add an icon, add a `GuiIcon` entry, a `SocialIcon` subclass (or any `CustomItem` with
  `setItemModel(joshymc:<id>)`) registered in `ItemManager`, and an art module
  `resourcepack/art/items/<id>.py`.

## Safety

- The vanilla stick is untouched: only stacks carrying the `minecraft:item_model` component
  `joshymc:<id>` look different. The pack has no `minecraft:stick` override.
- Staff can hand out copies for testing with `/joshymc give discord_icon <amount> [player]` under
  the existing permission. No new commands or permissions were added. `GuiIconListener` stops a
  copy from being crafted (crafting table, crafter) or burned as furnace fuel. Ordinary sticks
  aren't affected.

## Resource pack and deployment

- Art: `resourcepack/art/items/discord_icon.py`, `youtube_icon.py` and the shared extruder
  `_social_icon.py`. Each icon is a 3D badge (75–100 elements) shown straight on in inventories,
  with a glint that sweeps across it every few seconds. Textures are under
  `assets/joshymc/textures/item/<id>/` (`<id>.png` is the badge face).
- **Java**: nothing to do by hand. The build zips `resourcepack/` into the jar and
  `ResourcePackManager` serves the bundled pack, replacing an older
  `plugins/Joshymc/resourcepack.zip`. If `config.yml > resource-pack` points at an externally
  hosted URL instead, re-upload the new pack and update its hash.
- **Bedrock (Geyser)**: `BedrockPackManager` picks up both items automatically (they're
  registered `CustomItem`s), using the Geyser v2 `definition` mappings `minecraft:stick` +
  `joshymc:<id>` → `joshymc:discord_icon` / `joshymc:youtube_icon` and the icons in
  `src/main/resources/bedrock/icons/joshymc/`. Geyser reads the pack and mappings at startup,
  so restart the server (not `/reload`) after deploying. Bedrock shows the flat 64x64 icon
  (no 3D or animation), and in the hand it's held upright like the vanilla stick.

## Tests

- `./gradlew -I scripts/gui-icon-check.init.gradle.kts guiIconCheck` checks the registry,
  item classes (STICK, names/colours, no gamemode scope), wiring, resource pack references,
  unique models, that the vanilla stick is untouched, and the Geyser mappings. It runs on a
  Java 25 launcher because it loads `org.bukkit.Material`.
- `cd resourcepack && python3 -m art.check discord_icon youtube_icon` and
  `python3 -m art.bedrock --check`.

### Visual test (needs a client)

1. `./gradlew runServer`, join with Java 26.2 and accept the pack.
2. `/joshymc give discord_icon 1` and `/joshymc give youtube_icon 1`: the slot shows the logo with no
   stick, the names are bold blue/red, and a glint sweeps across every ~2.4 s. Hold them and
   drop one to check the in-hand and ground views.
3. A plain `/give @s stick` still looks like a vanilla stick.
4. Try crafting torches or fuelling a furnace with an icon: there's no result and it doesn't burn.
5. On a Geyser + Floodgate setup, repeat step 2 on Bedrock after a restart.
