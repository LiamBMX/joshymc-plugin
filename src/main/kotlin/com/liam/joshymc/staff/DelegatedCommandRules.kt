package com.liam.joshymc.staff

/**
 * The one policy for commands that run as somebody other than whoever wrote them (issue #1353,
 * audit findings D-H1 and D-H3). Bukkit-free.
 *
 * Three paths do this: `/sudo <player> <command>` (runs as the target), NPC commands (run as
 * the player who clicks) and portal `command` actions (run as the player who walks in). In
 * every case the author picks the command and somebody else's permissions and belongings pay
 * for it, so the same rules apply to all three:
 *
 *  - **Staff commands are refused.** A command is a staff command when its label or real name
 *    is in [PRIVILEGED] (JoshyMC's staff commands and known permission/operator/server
 *    commands), or when it belongs to another plugin or vanilla and its declared permission
 *    doesn't default to every player (resolved at run time by [DelegatedCommands]).
 *    Otherwise an Owner who clicks a booby-trapped NPC
 *    would run `/lp`, `/world delete`, `/joshymc reload hard`, ... with Owner rights.
 *  - **Staff subcommands are refused**: a first argument in [STAFF_SUBCOMMANDS] (`admin`,
 *    `give`, `force`, ...), because many player commands hide admin branches behind their own
 *    checks (`/crate givekey`, `/team admin`, `/lifesteal admin`).
 *  - **Commands that hand the user's belongings to someone else are refused** ([TRANSFER]:
 *    `/pay`, `/creditspay`, `/trade`, `/trash`, `/withdrawheart`, `/tpaccept`, `/sethome`, ...).
 *  - **Market/team/claim style commands may only open their menu** ([MENU_ONLY]): `/ah`,
 *    `/team`, `/claim` are fine as NPC buttons, but any argument (`/team transfer x`,
 *    `/claim trust x`, `/ah sell 1`, `/bounty add x 1000000`) is refused.
 *
 * The console's own `/sudo` and console-only portal `console` actions are not delegated (the
 * console wrote and runs them), so they are not limited here.
 */
object DelegatedCommandRules {

    enum class Context(val display: String) {
        SUDO("/sudo"),
        NPC("NPC commands"),
        PORTAL("portal commands"),
    }

    enum class Decision(val allowed: Boolean) {
        ALLOWED(true),
        EMPTY(false),
        STAFF_COMMAND(false),
        STAFF_SUBCOMMAND(false),
        TRANSFER(false),
        MENU_ONLY(false),
        ;

        fun message(label: String, context: Context): String? = when (this) {
            ALLOWED -> null
            EMPTY -> "No command given."
            STAFF_COMMAND -> "/$label is a staff command and can't be run through ${context.display}."
            STAFF_SUBCOMMAND -> "Staff subcommands of /$label can't be run through ${context.display}."
            TRANSFER -> "/$label moves the player's belongings, so it can't be run through ${context.display}."
            MENU_ONLY -> "/$label may only open its menu through ${context.display} (no arguments)."
        }
    }

    /**
     * Staff, permission, operator, console-type and server-control commands (label or
     * canonical name). JoshyMC's own staff commands are listed by name here, because many
     * player features (`/pv`, `/fly`, `/hat`, ...) also use `default: op` nodes that LuckPerms
     * hands to players, so a permission default can't tell them apart. Other plugins' and
     * vanilla commands are also judged by their permission default ([staffCommand]).
     */
    val PRIVILEGED: Set<String> = setOf(
        // Permissions, operators, server control, other plugins' admin tools
        "lp", "luckperms", "perm", "perms", "permission", "permissions", "lpb", "lpv",
        "op", "deop", "sudo", "execute", "pex", "manuadd", "stop", "restart", "reload", "rl",
        "whitelist", "pardon", "ban-ip", "pardon-ip", "kill", "give", "summon", "setblock", "fill",
        "clone", "data", "attribute", "item", "loot", "effect", "xp", "experience", "function", "schedule",
        "co", "coreprotect", "rg", "region", "worldguard", "we", "worldedit",
        // JoshyMC staff commands
        "joshymc", "jmc", "eco", "economy", "credits", "purchase", "voucher", "rank", "ranks",
        "rankvoucher", "creditvoucher", "jgamemode", "world", "gencave", "npc", "portal", "jrestart",
        "admin", "punish", "ban", "tempban", "unban", "mute", "tempmute", "unmute", "warn", "unwarn",
        "kick", "pkick", "freeze", "history", "alts", "reports", "invsee", "enderchest", "playerdata",
        "god", "gamemode", "gm", "gmc", "gms", "gma", "gmsp", "tp", "tphere", "tpo", "smite", "enchant",
        "customenchant", "ce", "sellwand", "booster", "mutations", "mcr", "skull", "fakebase", "spawnstash",
        "crateeditor", "createkit", "editkit", "deletekit", "setwarp", "delwarp", "editwarp", "setspawn",
        "dimension", "phome", "holo", "hologram", "spawndecor", "leaderboard", "arena", "chatgame",
        "announce", "broadcast", "bc", "stafftag", "staffchat", "sc", "sj", "hidestaff", "vanish", "v",
        "modmode", "staffmode", "jmc-violation",
    )

    /** Commands whose whole purpose is to move what the user owns to someone else (or destroy it). */
    val TRANSFER: Set<String> = setOf(
        "pay", "creditspay", "cpay", "trade", "trash", "bin", "dispose", "disposal", "withdrawheart",
        "tpaccept", "tpyes", "sethome", "delhome", "deletehome", "removehome",
    )

    /** Commands that are fine as a menu shortcut but whose arguments act on the user's assets. */
    val MENU_ONLY: Set<String> = setOf(
        "ah", "auction", "auctionhouse", "gift", "team", "claim", "claims", "unclaim", "orders",
        "buyorders", "order", "bounty", "bounties", "coinflip", "cf", "ccoinflip", "ccf", "bank",
        "tokens", "token", "giveaway", "gwy", "invest", "stocks", "stock", "mines", "roulette",
        "crash", "towers", "casino",
    )

    /** First arguments that are admin branches of an otherwise player-facing command. */
    val STAFF_SUBCOMMANDS: Set<String> = setOf(
        "admin", "give", "givekey", "giveall", "force", "forceopen", "reload", "set", "setbalance",
        "take", "reset", "wipe", "setlocation", "removelocation", "setcommand", "setprice", "setrank",
        "grant", "revoke", "addblocks", "setblocks", "void", "override", "hearts", "combat",
    )

    /** The command's base label: no slash, no `plugin:` namespace, lower case. */
    fun label(command: String): String =
        command.trim().removePrefix("/").substringBefore(' ').substringAfter(':').lowercase()

    /** Everything after the label, split on whitespace. */
    fun arguments(command: String): List<String> =
        command.trim().removePrefix("/").split(Regex("\\s+")).drop(1).filter { it.isNotEmpty() }

    /**
     * [canonical] is the registered command's real name when the label is an alias (or null),
     * [staffCommand] is true when the resolved command's declared permission doesn't default
     * to every player (false when the command is unknown or declares no permission).
     */
    fun decide(command: String, canonical: String? = null, staffCommand: Boolean = false): Decision {
        val label = label(command)
        if (label.isEmpty()) return Decision.EMPTY
        val names = setOfNotNull(label, canonical?.lowercase()?.substringAfter(':'))
        if (staffCommand || names.any { it in PRIVILEGED }) return Decision.STAFF_COMMAND
        if (names.any { it in TRANSFER }) return Decision.TRANSFER
        val args = arguments(command)
        if (args.isNotEmpty() && args[0].lowercase() in STAFF_SUBCOMMANDS) return Decision.STAFF_SUBCOMMAND
        if (args.isNotEmpty() && names.any { it in MENU_ONLY }) return Decision.MENU_ONLY
        return Decision.ALLOWED
    }

    /** Lowest permission-default rank of a `;`-joined node list: player-facing when any node is. */
    enum class NodeDefault { TRUE, NOT_OP, OP, FALSE, UNREGISTERED }

    /** A command is a staff command unless at least one of its nodes defaults to (non-op) players. */
    fun staffCommand(nodeDefaults: List<NodeDefault>): Boolean =
        nodeDefaults.isNotEmpty() && nodeDefaults.none { it == NodeDefault.TRUE || it == NodeDefault.NOT_OP }
}
