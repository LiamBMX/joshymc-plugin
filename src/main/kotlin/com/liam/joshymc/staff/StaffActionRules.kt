package com.liam.joshymc.staff

/**
 * Which `/voucher` subcommands are too dangerous for a permission (issue #1383). Bukkit-free.
 *
 * A Credit Shop package's reward command runs as the **console** when it is bought, so whoever
 * can set one can run any command with full authority (LuckPerms, `/op`, `/purchase fulfill`).
 * Setting it is therefore console only: no permission node can grant it. Changing what a
 * package costs or which donor tier it grants is a separate node, `joshymc.vouchers.pricing`
 * (default false), so `joshymc.vouchers` alone can't make a rank package cost 0.1 Credits.
 */
object VoucherAdminRules {
    const val PRICING_PERMISSION = "joshymc.vouchers.pricing"

    enum class Gate { NONE, CONSOLE_ONLY, PRICING }

    /** [priceArg] is `args[2]` of `create`: a number means a Credit Shop package with a command. */
    fun gate(subcommand: String, priceArg: String? = null): Gate = when (subcommand.lowercase()) {
        "setcommand" -> Gate.CONSOLE_ONLY
        "create" -> if (priceArg?.toDoubleOrNull() != null) Gate.CONSOLE_ONLY else Gate.NONE
        "setprice", "setrank" -> Gate.PRICING
        else -> Gate.NONE
    }
}

/**
 * Who may configure a portal's `console` action (issue #1383). Bukkit-free. A portal's
 * `command` action now runs with the walking player's own permissions; only the console may
 * set a `console` action, which keeps the old run-as-console behaviour for trusted setups.
 */
object PortalActionRules {
    const val COMMAND = "command"
    const val CONSOLE = "console"

    fun consoleOnly(action: String): Boolean = action.equals(CONSOLE, ignoreCase = true)

    /** How a stored action runs: player = performCommand by the player, console = server console. */
    enum class Runner { PLAYER, CONSOLE, NONE }

    fun runnerFor(action: String): Runner = when (action.lowercase()) {
        COMMAND -> Runner.PLAYER
        CONSOLE -> Runner.CONSOLE
        else -> Runner.NONE
    }
}

/**
 * `/sudo` limits for players (issue #1383). Bukkit-free. `/sudo` runs a command with the
 * *target's* permissions, so a player sending it may only target players below them
 * ([StaffProtection]). Which commands may run is the shared [DelegatedCommandRules] policy
 * (issue #1353: staff commands, asset transfers like `/pay`, `/creditspay`, `/trade`, and
 * arguments to market/team/claim commands are refused). The console is not limited.
 */
object SudoRules {
    /** The command's base label: no slash, no `plugin:` namespace, lower case. */
    fun label(command: String): String = DelegatedCommandRules.label(command)

    /** Label-only check; `/sudo` itself also resolves aliases and permissions ([DelegatedCommands]). */
    fun blocked(command: String): Boolean = !DelegatedCommandRules.decide(command).allowed
}

/**
 * Creative-mode item protection (issue #1383). Bukkit-free. In Creative the client may put
 * any item stack, with any data, into its inventory (middle-click copy, creative drop, a
 * modded client), and tokens, crate keys, money/Credit/Rank vouchers, sell wands and spawners
 * are all recognized by their `joshymc:` data tags. So a Creative inventory action carrying
 * any `joshymc:` data is refused unless the player holds [BYPASS_PERMISSION] (default false).
 */
object CreativeItemRules {
    const val BYPASS_PERMISSION = "joshymc.creative.bypass"
    const val NAMESPACE = "joshymc"

    /** [keys] are the item's persistent data keys as `namespace:key`. */
    fun protectedItem(keys: Collection<String>): Boolean =
        keys.any { it.substringBefore(':', "").equals(NAMESPACE, ignoreCase = true) }
}
