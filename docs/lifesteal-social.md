# Lifesteal friends, privacy, private messages and chat channels (issue #1430)

Lifesteal only. On a server that doesn't host Lifesteal nothing here runs: no tables, no
`/friend` or `/channel` (both are released so another plugin may own them), no listener, and
`/msg` keeps its Survival behaviour (plus the mute check below, which every gamemode now has).

## What already existed and was reused

| Existing system | How it's used |
|---|---|
| `/msg` (`/tell`, `/w`, `/whisper`, `/pm`, `/dm`) and `/reply` (`/r`) | Kept. Both now deliver through one path, `command/PrivateMessages`. |
| `/ignore` (`ignores` table) | The block list. Respected by friend requests (either way), private messages, team/local chat and `/ac`. |
| `/messages on\|off` (personal messages setting) | Still applies after the Lifesteal rules. |
| Mutes (`PunishmentManager.isMutedIn`) | Checked first for every private message and channel message. |
| Repeat rule (`ChatSpamDetector`) and `ProfanityFilter` | Applied to Lifesteal private messages and channel commands, like public chat. |
| Vanish (`VanishCommand`), silent join | Hidden staff look offline, are never announced and are never suggested in tab completion. |
| Lifesteal teams and alliances (`LifestealTeamManager`, `/ac`) | Team chat reads the team cache; Alliance chat *is* `/ac`. |
| `PlayerResolver` | Name lookup (never asks Mojang); friendships are stored by UUID, so name changes don't matter. |

There was no friends system, no Lifesteal team chat and no local chat before this.

## Commands

| Command | What it does |
|---|---|
| `/friends` or `/friend` | Opens the Friends menu. |
| `/friend add <player>` | Sends a request (works for offline players the server has seen). If they already asked you, you become friends. |
| `/friend accept <player>` / `deny <player>` | Answers a request. The sender isn't told about a denial. |
| `/friend cancel <player>` | Withdraws your request. |
| `/friend remove <player>` | Ends a friendship (only your own). |
| `/friend list`, `/friend requests` | Chat versions of the menu pages. |
| `/friend settings [option value]` | Opens the settings page, or sets `requests everyone\|nobody`, `status friends\|nobody`, `messages everyone\|friends\|nobody`, `loginnotify on\|off`, `requestnotify on\|off`. |
| `/channel` (`/ch`) | Shows your channel and which ones you can use. |
| `/channel <global\|team\|alliance\|local>` | Switches where typed chat goes (saved). |
| `/channel <team\|alliance\|local> <message>` | Sends one message there without switching. |

Permissions: `joshymc.lifesteal.friends.use` and `joshymc.lifesteal.channel.use` (default true),
`joshymc.lifesteal.social.bypass-privacy` (op; message players whose privacy setting would refuse
it, ignores and mutes still apply). Both commands are LIFESTEAL-scoped in `GamemodeAssignments`.

## Friends menu

54 slots: friend heads (online first, 45 per page), Incoming (45) and Outgoing (46) requests,
filter All/Online/Offline (48), paging (47/51), Close (49), Social Settings (53). Left-click a
friend suggests `/msg <name> ` in chat (the message then goes through the normal path),
right-click opens their profile with Send Message and Remove (with a confirmation page).

Buttons only carry the UUID (and for requests the creation time) of what they showed; every click
re-checks the feature switch, the Lifesteal world, the permission and the stored rows, so a stale
button (an answered, expired or re-sent request; a friendship removed meanwhile) does nothing.
GuiManager cancels clicks in the menu and blocks shift/number-key/double-click moves into it.

## Privacy settings

| Option | Choices | Default |
|---|---|---|
| Friend requests from | Everyone, Nobody | Everyone |
| Online status visible to | Friends, Nobody (never shown to non-friends) | Friends |
| Private messages from | Everyone, Friends only, Nobody | Everyone |
| Friend join/leave notices | On/Off | On |
| Friend request notices | On/Off | On |

Defaults come from `config.yml > lifesteal-social.privacy-defaults`. Players can only change their
own settings (the store is only ever called with the clicking/typing player's UUID).

Notifications: join/leave notices go to online friends in Lifesteal when the friend's status is
visible and the receiver has notices on; a vanished or private silent join is never announced.
One notice per receiver, friend and kind per `notifications.cooldown-seconds` (relogging can't
spam), and request notices are throttled the same way.

## Private messages

Order in `PrivateMessages.send`:
1. The sender's mute in the gamemode they're in (**every gamemode**). The staff permission audit
   found mutes didn't block `/msg` and `/r`; now `/msg`, its aliases, `joshymc:` forms and `/r`
   are refused. `MutedCommandListener` also blocks muted players from vanilla `/minecraft:msg`,
   `/minecraft:tell`, `/minecraft:w`, `/me` and `/teammsg` (`/tm`) in every gamemode.
2. On Lifesteal (either player in a Lifesteal world): a target hidden from the sender looks like
   "Player not found"; the target's ignore list; you can't message someone you ignore; the target's
   "Private messages from" setting (staff bypass skips only this). `/r` to the player who last
   messaged you skips their audience/vanish check, since they started the conversation.
3. On Lifesteal: the public-chat repeat rule and the profanity filter.
4. Everywhere: the ignore list and the `/messages` toggle (as before; `/r` now also checks ignores).

## Chat channels

- **Global**: ordinary public chat, unchanged.
- **Team**: the sender's current Lifesteal team roster in Lifesteal worlds (format
  `channels.team.format`).
- **Alliance**: the existing `/ac` (alliance membership, `lifesteal.yml > teams.alliances.chat`).
- **Local**: players in the same Lifesteal world within `channels.local.radius` blocks.

When a non-global channel is selected, typed chat is cancelled at HIGH (after mutes, the global
chat mute, staff chat, the repeat rule and the profanity filter ran) and before the chat format and
the Discord bridge, so it never reaches public chat or Discord. Delivery runs on the main thread
with a fresh membership check; if you're no longer in a team/alliance the message isn't sent
anywhere and you're told to switch back. Ignore lists apply. Survival chat isn't touched.

## Friends are social only

A friendship never grants team access, claim/build access, teleports (there is no friend
teleport; `/tpa` is unchanged), shared inventories or storage, PvP protection, or alliance changes.
The check script verifies no system outside `lifesteal/social/`, the wiring and private messages
reads the friend data.

## Storage (Lifesteal server's data.db)

- `lifesteal_friends (uuid_low, uuid_high, created_at)`: one row per pair, lower UUID first
  (CHECK), so a pair exists once.
- `lifesteal_friend_requests (sender, target, status, created_at, expires_at, resolved_at)`:
  PENDING/ACCEPTED/DENIED/CANCELLED/EXPIRED. Every transition is a conditional UPDATE in a
  transaction: two accepts settle once, simultaneous mutual requests make one friendship, racing
  accepts never pass the friend limit (re-checked on accept). Expiry is real time (also across
  downtime); a sweep marks expired rows every minute.
- `lifesteal_social_prefs (uuid, requests_from, status_visible, messages_from, login_notify,
  request_notify, chat_channel, updated_at)`.

Survival and Lifesteal are separate servers with separate databases (`docs/network.md`), so the
Lifesteal server is the one authority for its friendships; nothing is shared across servers.

## Configuration (`config.yml > lifesteal-social`)

`enabled`, `friends.enabled`, `friends.max-friends` (1..500), `friends.request-expiration-seconds`,
`friends.request-cooldown-seconds`, `friends.deny-cooldown-seconds`, `notifications.cooldown-seconds`,
`privacy-defaults.*`, `messaging.enabled` (the Lifesteal privacy/filters for `/msg`; mutes always
apply), `channels.enabled`, `channels.<team|alliance|local>.enabled`, `channels.local.radius`,
`channels.team.format`, `channels.local.format`. Invalid values fall back to the default and are
logged. Turning anything off never deletes stored data.

## Checks

`./gradlew -I scripts/lifesteal-social-check.init.gradle.kts lifestealSocialCheck`

## Manual tests (need a Paper server)

1. A `/friend add` B (online and offline), B accepts from chat and from the menu; both lists show each other.
2. Double-click Accept / accept from chat and menu at once: one friendship, the second says no request.
3. Vanish B: A sees B offline in the menu, no join notice; `/msg B` says "Player not found".
4. B sets private messages to Friends only: a stranger's `/msg B` is refused; A's works.
5. Mute A: `/msg`, `/tell`, `/w`, `/r`, `/minecraft:msg`, `/me` are refused.
6. A in a team: `/channel team`, chat; only teammates see it, nothing in public chat or Discord.
   Leave the team and chat: refused, nothing sent anywhere.
7. `/channel local hi` with someone 50 and 150 blocks away: only the closer one sees it.
8. A Survival server: `/friend` and `/channel` don't exist; `/msg` works as before (except mutes).
