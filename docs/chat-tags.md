# Special (voucher) Chat Tags

The "voucher" category in `tags.yml` (shown to players as **Special Chat Tags**) holds tags that
nobody owns by default. Issue #1391 bundles 15 of them; admins can still add more with
`/voucher create <id> <display>`.

## The bundled tags (issue #1391)

| Id | Shows as | Gradient (name letters) | Permission |
|---|---|---|---|
| `mapleborn` | `[❦ Mapleborn]` | `#E8571E` → `#F2B33D` | `joshymc.tag.voucher.mapleborn` |
| `hearthbound` | `[⌂ Hearthbound]` | `#FFB627` → `#B8693D` | `joshymc.tag.voucher.hearthbound` |
| `moonmoss` | `[☾ Moonmoss]` | `#A3B58F` → `#4F9A9B` | `joshymc.tag.voucher.moonmoss` |
| `lanternlit` | `[✦ Lanternlit]` | `#FFD23F` → `#FF9550` | `joshymc.tag.voucher.lanternlit` |
| `starforged` | `[✧ Starforged]` | `#C9B0FF` → `#5B8DEF` | `joshymc.tag.voucher.starforged` |
| `honeyglow` | `[⬢ Honeyglow]` | `#E3A018` → `#FFF3A8` | `joshymc.tag.voucher.honeyglow` |
| `wildroot` | `[❀ Wildroot]` | `#2F8F3A` → `#9BE08A` | `joshymc.tag.voucher.wildroot` |
| `nightbloom` | `[✿ Nightbloom]` | `#8A3FD1` → `#D7B8FF` | `joshymc.tag.voucher.nightbloom` |
| `sunwoven` | `[☀ Sunwoven]` | `#FFCBA4` → `#F5A01B` | `joshymc.tag.voucher.sunwoven` |
| `cloudbound` | `[☁ Cloudbound]` | `#6EC1FF` → `#F2F8FF` | `joshymc.tag.voucher.cloudbound` |
| `emberkeeper` | `[✹ Emberkeeper]` | `#E82C1E` → `#FF9A3C` | `joshymc.tag.voucher.emberkeeper` |
| `driftwood` | `[Driftwood]` | `#9A7752` → `#E6D3AC` | `joshymc.tag.voucher.driftwood` |
| `glowkeeper` | `[✴ Glowkeeper]` | `#7DF2B5` → `#1FE3F7` | `joshymc.tag.voucher.glowkeeper` |
| `twilightkin` | `[☽ Twilightkin]` | `#8C63C4` → `#F7A8C8` | `joshymc.tag.voucher.twilightkin` |
| `trailmarked` | `[⚒ Trailmarked]` | `#A3A23C` → `#5E9E4C` | `joshymc.tag.voucher.trailmarked` |

### Formatting

- They use the same legacy `&`-format as every other tag (`&#RRGGBB` hex codes, parsed by
  `CommunicationsManager.parseLegacy`). There's no second formatter and no MiniMessage.
- The gradient is one hex code per letter of the name. The opening bracket and symbol use the start
  color and the closing bracket uses the end color. No bold, obfuscated or other decorations.
- Every display ends with `&r ` so the tag's color can't carry over. The chat format puts `&f` before
  the player name anyway.
- The colors are written out in `tags.yml`. To change one, edit the per-letter codes there (the
  comment above each tag shows its two end colors).

### Symbols, emoji and Bedrock

The issue asked for three emoji: 🍁 (Mapleborn), 🍯 (Honeyglow), 🔥 (Emberkeeper). They sit outside
the Basic Multilingual Plane, so Java stores each one as a surrogate pair. Bedrock's glyph sheets only
cover the Basic Multilingual Plane, so Bedrock players (through Geyser) would most likely see an
empty box, and no client could be tested while building this. So all three were swapped for Basic
Multilingual Plane symbols:

| Tag | Requested | Used |
|---|---|---|
| Mapleborn | 🍁 U+1F341 | ❦ U+2766 (floral heart) |
| Honeyglow | 🍯 U+1F36F | ⬢ U+2B22 (black hexagon, honeycomb) |
| Emberkeeper | 🔥 U+1F525 | ✹ U+2739 (twelve-pointed star) |

All the other symbols are single Basic Multilingual Plane code points: ⌂ ☾ ✦ ✧ ❀ ✿ ☀ ☁ ✴ ☽ ⚒.
Java clients draw these from the default font's Unifont fallback, which covers the whole plane.
Bedrock rendering has **not** been checked in a client. If a symbol shows as a box on Bedrock,
change it in `tags.yml`; no resource pack is involved. Because no tag uses a surrogate pair, a color
code can never land inside a character.

## Unlocking

Each tag unlocks on its own. Nothing grants it automatically, and no rank, LuckPerms group or op
holds it:

- **Physical voucher**: `/voucher give <player> chattag_<id> [amount]`, for example
  `/voucher give Steve chattag_mapleborn`. Redeeming it (right-click) unlocks only that tag,
  through the existing redemption path: one use per voucher, and a voucher for a tag the player
  already owns isn't used up.
- **Permission**: grant the one node, for example
  `lp user Steve permission set joshymc.tag.voucher.mapleborn true`. `ChatTagManager` registers each
  `joshymc.tag.voucher.<id>` with default `false`, so ops don't get it either. plugin.yml has no
  wildcard or parent node for them.

The menu (`/tags` → Special Chat Tags) shows each tag as locked or owned, and clicking one checks
ownership again before equipping it.

## Gamemodes

Chat tags are part of Survival: `/tag` (`/tags`) and `/voucher` are SURVIVAL-scoped in
`GamemodeAssignments`, and that's unchanged. The tags load on every server from the bundled
`tags.yml`. Unlocks and equipped tags live in each server's own database (`chat_tag_unlocks`,
`player_tags`), so nothing is shared or synced between Survival and Lifesteal. On a dedicated
Lifesteal server the tag menu is locked away like before.

## Bundled defaults and deletions

On startup, voucher tags missing from a server's `tags.yml` are merged in like every other bundled
tag (`ChatTagDefaults.merge`). Tags already in the file, including admin edits and
`/voucher create` tags, are left alone. `/voucher tag delete <id>` adds the id to
`removed-voucher-defaults` at the top level of `tags.yml`, so a deleted bundled tag doesn't come
back on the next start. Creating the id again with `/voucher create` takes it off that list.

## Checks

```
./gradlew -I scripts/voucher-tag-check.init.gradle.kts voucherTagCheck
```

This checks that all 15 tags load into the voucher category with their own node. It also checks:

- ids and names are unique across every category
- each palette is distinct
- the exact rendered text and per-letter colors
- that no symbol is a surrogate pair
- that nothing carries over into the player name
- the merge, deletion and recreation rules
