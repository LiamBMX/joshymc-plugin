# Special (voucher) Chat Tags

The "voucher" category in `tags.yml` (shown to players as **Special Chat Tags**) holds tags that
nobody owns by default. Issue #1391 bundles 15 of them, and the Christmas Advent Calendar
(issue #1424, `docs/advent-calendar.md`) adds 5 more it hands out as vouchers: `snowflake` (❄),
`holly` (☘), `frostbite` (❅), `blizzard` (❆) and `christmas` (★). Admins can still add more with
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

## The `/tags` menus (issue #1393)

- **Main menu** (27 slots): Bundled Chat Tags (slot 12, a bundle) and Special Chat Tags (slot 14,
  a name tag). Nothing else, no filler. Each shows how many tags you have unlocked.
- **Bundled Chat Tags** (54 slots): one icon per themed category loaded from `tags.yml`, in file
  order, with its tag count and your unlocked count. Clicking one opens its tags.
- **Tag lists** (54 slots, a bundle's tags or Special Chat Tags): each tag shows a preview with
  your name (`[Tag] Name: Hello!`), Locked/Unlocked and Equipped. Click an unlocked tag to equip
  it. Right-click any tag, locked or not, for the same preview in chat, sent only to you.
- Bottom row of every 54-slot page: Previous 45, Back 49, Remove Current Tag 51, Next 53. Removing
  only unequips; the unlock stays. Back goes tag list → bundles → main menu, and Special → main.
- Every click looks the tag up again and re-checks ownership, so a stale page (after a deletion or
  a revoked permission) can't equip anything.

The layout and the classification are the Bukkit-free `ChatTagMenus`.

### Bundled vs Special: how each category is classified

The classification comes from how the code unlocks tags, not from category names:

| Category | Menu | Why |
|---|---|---|
| `voucher` | Special | Each tag has its own `joshymc.tag.voucher.<id>` node (default false) and is the only kind a Chat Tag Voucher can redeem (`isVoucherTag`). |
| `vibe`, `nature`, `animal`, `music`, `pirate`, `skill`, `military`, `meme`, `mythical`, `cyberpunk`, `cosmic` (and any later approved category) | Bundled | Themed collections. |

Ownership is always the per-tag check `ChatTagManager.canUse`: the tag's `permission:` from
`tags.yml` (null = everyone), or a voucher unlock in `chat_tag_unlocks`. There is **no
category-wide permission in the code** (no `joshymc.tag.<category>` node is ever checked), and
opening a bundle grants nothing.

**Needs Owner verification:** every bundled tag in the shipped `tags.yml` has `permission: null`,
so all bundled tags are currently free for everyone and the menu shows them as unlocked. The
repository has no Tebex package mapping for chat tag bundles (only the per-tag `chattagvoucher`
reward in `docs/store-fulfillment.md`). The menu says the bundles are "available through the
JoshyMC store" because the issue asked for that wording, but no package was created or verified.
If a bundle should be sold, give its tags a `permission:` in the server's `tags.yml` (the same
node on every tag of the bundle works) and have the Tebex package grant that node. The menu then
shows it locked until the player owns it. No code change is needed.

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
./gradlew -I scripts/chat-tag-menu-check.init.gradle.kts chatTagMenuCheck
```

The menu check covers the slot layout, paging, the classification, that every Special tag has its
own node and the bundled ones are unchanged, the re-checked clicks and the private previews.

This checks that all 20 bundled tags (15 + the 5 Advent tags) load into the voucher category with their own node. It also checks:

- ids and names are unique across every category
- each palette is distinct
- the exact rendered text and per-letter colors
- that no symbol is a surrogate pair
- that nothing carries over into the player name
- the merge, deletion and recreation rules
