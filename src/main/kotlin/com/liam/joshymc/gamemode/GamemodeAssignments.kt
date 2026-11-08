package com.liam.joshymc.gamemode

/**
 * Which gamemode each command belongs to (issues #1289, #1329). Commands are keyed by their
 * plugin.yml name (aliases and `joshymc:` forms resolve to it, and a command's subcommands
 * all run through its one executor, so they share its scope).
 *
 * Every plugin.yml command is either in [COMMANDS] or in [UNASSIGNED] (with the reason);
 * `scripts/GamemodeScopeCheck.kt` fails the check when a new command is in neither.
 *
 * Assigning a scope never changes anything on its own gamemode or while that gamemode is
 * unlocked ([GamemodeIsolation.allowed]): a SURVIVAL command only stops working outside
 * Survival once Survival is locked with `/jgamemode lock survival confirm`.
 *
 * Features (listeners, GUIs, rewards, scheduled tasks) check
 * `plugin.gamemodeLockManager.isAllowed(feature, world)` with a [GamemodeFeature] instead.
 */
object GamemodeAssignments {

    private val NETWORK_COMMANDS = listOf(
        // Plugin administration, server identity, gamemode locks.
        "joshymc", "jgamemode", "jrestart", "world", "dimension", "gencave", "portal", "holo", "npc", "spawndecor",
        // Store fulfillment is console-run by Tebex; refusing it would mark a purchase done without
        // recording it, so it runs everywhere (each server's own purchase_deliveries table).
        "purchase",
        // Rank management (staff ranks + LuckPerms); each starter rank is per gamemode (/freerank).
        "rank", "freerank",
        // Moderation: every server enforces its own punishments; /punish scopes each one.
        "ban", "tempban", "unban", "mute", "tempmute", "unmute", "warn", "unwarn", "pkick", "history", "punish",
        "playerdata", "alts", "report", "reports", "admin", "jmc-violation",
        // Staff utilities.
        "vanish", "modmode", "hidestaff", "staffchat", "stafftag", "sj", "invsee", "tp", "tphere", "sudo", "smite",
        "heal", "feed", "god", "speed", "enchant", "gamemode", "gmc", "gms", "gma", "gmsp", "chat",
        // Communication and account infrastructure.
        "link", "unlink", "msg", "reply", "messages", "ignore", "announce", "live", "promote", "nick", "timezone",
        "settings", "scoreboard", "serverbar", "jmc-ecview",
        // Information.
        "help", "rules", "media", "discord", "ip",
        // The guided tutorial (issue #1387): one walkthrough per gamemode, picked from where the player is.
        "tutorial",
        // Softlock recovery (issue #1241) is server infrastructure, not gameplay.
        "unstuck",
        // Cross-server transfers (issue #1331) move players between gamemodes, so they work on all of them.
        "lobby", "survival", "lifesteal",
        // Issue #1346: /team exists in both gamemodes. TeamCommandRouter sends Lifesteal senders to the
        // Lifesteal team system and everyone else to the Survival TeamCommand, applying the SURVIVAL
        // scope (and joshymc.team) itself, so Survival teams behave exactly as before.
        "team",
        // Issue #1401: /bounty likewise. BountyCommandRouter sends Lifesteal senders to the Lifesteal bounty
        // system and everyone else to the Survival BountyCommand, applying the SURVIVAL scope (and
        // joshymc.bounty) itself, so Survival bounties behave exactly as before.
        "bounty",
        // Issue #1425: /quests likewise. QuestCommandRouter sends Lifesteal senders to the Lifesteal quests
        // and everyone else to the Survival QuestCycleCommand, applying the SURVIVAL scope (and
        // joshymc.quests) itself, so Survival quests behave exactly as before. /daily stays Survival.
        "quests",
        // Issue #1379: kits exist in both gamemodes. Every kit belongs to one gamemode in kits.yml
        // (missing = Survival) and KitManager only shows/claims kits of the sender's current
        // gamemode, so Survival kits never appear on Lifesteal and back.
        "kit", "createkit", "editkit", "deletekit",
    )

    private val SURVIVAL_COMMANDS = listOf(
        // Economy and currencies (balances live in this server's own database).
        "eco", "balance", "pay", "baltop", "tokens", "credits", "creditspay", "cshop", "worth", "sell", "sellwand",
        // Markets, shops, auctions, orders, trading.
        "shop", "market", "invest", "stock", "ah", "orders", "order", "chestshop", "trade", "overflow", "candy",
        "eventshop",
        // Gambling.
        "coinflip", "ccoinflip", "casino", "mines", "roulette", "crash", "towers",
        // Giveaways, gifts, vouchers, crates (kits are NETWORK since issue #1379).
        "giveaway", "gift", "voucher", "creditvoucher", "rankvoucher", "crate", "crateeditor",
        // The free [JoshyMC] Chat Tag Voucher (issue #1339), a Survival chat tag.
        "freetag",
        // Claims, storage, hoppers, spawners (/team is routed per gamemode, see NETWORK_COMMANDS).
        "claim", "unclaim", "subclaim", "pv", "enderchest", "hopper", "spawner",
        // Quests, progression, streaks, votes.
        "daily", "eventquests", "milestones", "resurge", "booster", "loginstreak", "mcr", "jvote", "talisman", "fish",
        // Cosmetics and chat perks unlocked through Survival crates, ranks and shops.
        "cosmetics", "killeffect", "tag", "chatcolor", "signitem", "skull",
        // Custom enchants (admin-applied, Survival gear).
        "customenchant",
        // Survival PvP: toggles, arenas, kill stats, leaderboards (/bounty is routed per gamemode, see NETWORK_COMMANDS).
        "pvp", "arena", "killstreak", "killtop", "deathstop", "leaderboard", "mutations",
        // Hosted community events (event world, rewards paid in Survival currencies).
        "event",
        // Travel: warps, homes, random teleport, resource world, /back, /top.
        "warp", "setwarp", "delwarp", "editwarp", "pwarp", "home", "sethome", "delhome", "phome", "rtp", "resource",
        "back", "top",
        // Rank perks and flight (Fly Vouchers / flight time are Survival only, issue #1304).
        "fly", "flytime", "nightvision", "condense", "hat", "craft", "anvil", "smithing", "stonecutter", "repair",
        "trash",
        // Fake base bait builds (the tutorial is NETWORK since issue #1387).
        "fakebase", "spawnstash",
    )

    private val LIFESTEAL_COMMANDS = listOf(
        // Heart withdrawal into a physical Heart item (issue #1344). Its executor also refuses
        // anywhere the heart system doesn't run: a LIFESTEAL scope only blocks while Lifesteal is locked.
        "withdrawheart",
        // Ranked Team Wars queues and team challenges (issue #1363). Also only registered where
        // Lifesteal is hosted (released elsewhere), and refused outside Lifesteal worlds.
        "ranked",
        // Lifesteal alliance chat (issue #1405). Also only registered where Lifesteal is hosted.
        "ac",
    )

    val COMMANDS: Map<String, GamemodeScope> =
        NETWORK_COMMANDS.associateWith { GamemodeScope.NETWORK } + SURVIVAL_COMMANDS.associateWith { GamemodeScope.SURVIVAL } +
            LIFESTEAL_COMMANDS.associateWith { GamemodeScope.LIFESTEAL }

    /**
     * Commands deliberately left unassigned (never affected by a lock), with why. These are shared
     * basics whose Lifesteal behavior the blueprint hasn't decided yet; give each a scope once it has.
     */
    val UNASSIGNED: Map<String, String> = mapOf(
        "spawn" to "every server needs a spawn; Lifesteal's spawn rules aren't decided yet",
        "setspawn" to "goes with /spawn",
        "tpa" to "player teleport requests; Lifesteal's teleport rules aren't decided yet",
        "tpahere" to "goes with /tpa",
        "tpaccept" to "goes with /tpa",
        "tpdeny" to "goes with /tpa",
        "tpacancel" to "goes with /tpa",
        "afk" to "AFK status is shared infrastructure (AFK rewards themselves are a Survival feature)",
        "sit" to "harmless emote-style utility",
        "playtime" to "playtime is tracked per server; whether Lifesteal shows it isn't decided yet",
        "playtimetop" to "goes with /playtime",
        "chatgame" to "server-wide chat games; whether Lifesteal runs them isn't decided yet",
    )

    /** Every command name this table knows about, assigned or not. */
    val KNOWN: Set<String> get() = COMMANDS.keys + UNASSIGNED.keys
}

/**
 * Gamemode-specific features that run without a command (listeners, rewards, scheduled tasks),
 * issue #1329. Check them with `gamemodeLockManager.isAllowed(feature, world)`; like commands,
 * they only stop outside their gamemode while that gamemode is locked.
 */
enum class GamemodeFeature(val id: String, val scope: GamemodeScope, val description: String) {
    CUSTOM_ENCHANTS("custom-enchants", GamemodeScope.SURVIVAL, "combat, weapon and tool custom enchant effects"),
    CUSTOM_ITEMS("custom-items", GamemodeScope.SURVIVAL, "custom item abilities (Void Drill, eggs, candies, Sell Wand)"),
    MINING_PERKS("mining-perks", GamemodeScope.SURVIVAL, "Veinminer, Auto Smelt and Tree Feller"),
    QUESTS("quests", GamemodeScope.SURVIVAL, "Daily / Weekly / Quest Master progress"),
    EVENT_QUESTS("event-quests", GamemodeScope.SURVIVAL, "Event Quest progress"),
    LOGIN_STREAK("login-streak", GamemodeScope.SURVIVAL, "daily login streak playtime and rewards"),
    AFK_REWARDS("afk-rewards", GamemodeScope.SURVIVAL, "AFK money, XP and item rewards"),
    MILESTONES("milestones", GamemodeScope.SURVIVAL, "Milestone progress (farming, mining, fishing, combat, exploration)");

    companion object {
        fun fromId(id: String?): GamemodeFeature? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}
