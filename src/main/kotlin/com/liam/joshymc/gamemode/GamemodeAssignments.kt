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
        // Softlock recovery (issue #1241) is server infrastructure, not gameplay.
        "unstuck",
    )

    private val SURVIVAL_COMMANDS = listOf(
        // Economy and currencies (balances live in this server's own database).
        "eco", "balance", "pay", "baltop", "tokens", "credits", "creditspay", "cshop", "worth", "sell", "sellwand",
        // Markets, shops, auctions, orders, trading.
        "shop", "market", "invest", "stock", "ah", "orders", "order", "chestshop", "trade", "overflow", "candy",
        "eventshop",
        // Gambling.
        "coinflip", "ccoinflip", "casino", "mines", "roulette", "crash", "towers",
        // Giveaways, gifts, vouchers, crates, kits.
        "giveaway", "gift", "voucher", "creditvoucher", "rankvoucher", "crate", "crateeditor",
        "kit", "createkit", "editkit", "deletekit",
        // Claims, teams, storage, hoppers, spawners.
        "claim", "unclaim", "subclaim", "team", "pv", "enderchest", "hopper", "spawner",
        // Quests, progression, streaks, votes.
        "quests", "daily", "eventquests", "resurge", "booster", "loginstreak", "mcr", "jvote", "talisman", "fish",
        // Cosmetics and chat perks unlocked through Survival crates, ranks and shops.
        "cosmetics", "killeffect", "tag", "chatcolor", "signitem", "skull",
        // Custom enchants (admin-applied, Survival gear).
        "customenchant",
        // Survival PvP: toggles, arenas, bounties, kill stats, leaderboards.
        "pvp", "arena", "bounty", "killstreak", "killtop", "deathstop", "leaderboard", "mutations",
        // Hosted community events (event world, rewards paid in Survival currencies).
        "event",
        // Travel: warps, homes, random teleport, resource world, /back, /top.
        "warp", "setwarp", "delwarp", "editwarp", "pwarp", "home", "sethome", "delhome", "phome", "rtp", "resource",
        "back", "top",
        // Rank perks and flight (Fly Vouchers / flight time are Survival only, issue #1304).
        "fly", "flytime", "nightvision", "condense", "hat", "craft", "anvil", "smithing", "stonecutter", "repair",
        "trash",
        // Fake base bait builds and the getting-started guide for Survival.
        "fakebase", "spawnstash", "tutorial",
    )

    val COMMANDS: Map<String, GamemodeScope> =
        NETWORK_COMMANDS.associateWith { GamemodeScope.NETWORK } + SURVIVAL_COMMANDS.associateWith { GamemodeScope.SURVIVAL }

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
    AFK_REWARDS("afk-rewards", GamemodeScope.SURVIVAL, "AFK money, XP and item rewards");

    companion object {
        fun fromId(id: String?): GamemodeFeature? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}
