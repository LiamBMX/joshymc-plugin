package com.liam.joshymc.gamemode

/**
 * Shared commands on the dedicated Lifesteal server (issue #1573), Bukkit-free.
 *
 * A dedicated Lifesteal server isolates Survival ([DedicatedServer]), so every SURVIVAL command
 * is refused there. Some of them are plain player utilities that only read or change this
 * server's own data (its own `economy`, `homes`, auction and overflow tables, its own
 * `sell-prices.yml`), so on a server whose data is Lifesteal's they are Lifesteal's too. Those
 * are listed in [SHARED]: they keep their SURVIVAL scope (and so stay refused in Lifesteal
 * worlds of a mixed server, whose economy is Survival's), and additionally work in Lifesteal
 * on a server that hosts only Lifesteal ([sharedHere]).
 *
 * Survival is never unlocked by this: [GamemodeIsolation.allowed] is unchanged, every other
 * SURVIVAL command and feature stays refused, and nothing here grants a permission (each
 * command's own Bukkit permission still applies).
 *
 * A [Guard.COMPETITIVE] command can move items, coins or the player, so on Lifesteal it is
 * also refused while the player is combat-tagged or held by / spectating / waiting on a restore
 * of a Ranked Team Wars match ([competitiveRefusal]). The gate runs in the command's executor
 * wrapper, so aliases, `joshymc:` names, GUIs and dispatched commands are all covered.
 */
object LifestealSharedCommands {

    enum class Guard {
        /** Read-only or harmless; no extra check. */
        NONE,
        /** Moves items, coins or the player: refused in combat and around ranked matches. */
        COMPETITIVE,
    }

    /** SURVIVAL-scoped JoshyMC commands (plugin.yml names) that also run on a dedicated Lifesteal server. */
    val SHARED: Map<String, Guard> = linkedMapOf(
        // Economy: this server's own wallet balances (on a Lifesteal-only server they are Lifesteal's coins).
        "balance" to Guard.NONE,
        "baltop" to Guard.NONE,
        "worth" to Guard.NONE,
        // Selling to the server pays from this server's sell-prices.yml through the normal
        // SellPriceManager path (Lifesteal rank perks apply in Lifesteal worlds).
        "sell" to Guard.COMPETITIVE,
        // Paying another player: refused in combat like bank deposits and bounty contributions
        // (issue #1548), so coins can't be shielded from PvP coin stealing mid-fight.
        "pay" to Guard.COMPETITIVE,
        // Player-to-player auction house, and the withdraw-only overflow its deliveries fall back to.
        "ah" to Guard.COMPETITIVE,
        "overflow" to Guard.COMPETITIVE,
        // Trading already works on Lifesteal by shift + right-click (TradeInteractListener is unscoped).
        "trade" to Guard.COMPETITIVE,
        // Homes: the Lifesteal rank `homes` perk already sets the limit in Lifesteal worlds (issue #1428);
        // teleports still go through TeleportChecks (combat tag, ranked hold, End lock, arena access).
        "home" to Guard.COMPETITIVE,
        "sethome" to Guard.COMPETITIVE,
        "delhome" to Guard.NONE,
    )

    /**
     * The Lifesteal command blacklist from issue #1573, as JoshyMC command names, with how each
     * is enforced. None of them may ever be [SHARED].
     */
    val BLACKLIST: Map<String, String> = linkedMapOf(
        "fly" to "SURVIVAL scope, never shared: refused for everyone in Lifesteal, staff included, as before",
        "repair" to "SURVIVAL scope, never shared (its alias /fix runs the same executor)",
        "back" to "SURVIVAL scope, never shared",
        "heal" to "self-use refused outside Survival for everyone (issue #1521, in the executor); others = staff joshymc.heal.others",
        "feed" to "self-use refused outside Survival for everyone (issue #1521, in the executor); others = staff joshymc.feed.others",
        "god" to "self-use in Lifesteal only for staff ranks ([godSelfUse]); others = staff joshymc.god.others",
    )

    /** Blacklisted names that aren't JoshyMC commands: aliases of JoshyMC ones, or another plugin's. */
    val BLACKLIST_NOT_JOSHYMC: Map<String, String> = linkedMapOf(
        "fix" to "alias of /repair (same executor, same refusal)",
        "near" to "not a JoshyMC command (EssentialsX /near); see docs/lifesteal-shared-commands.md",
    )

    /**
     * Whether a SURVIVAL-scoped [command] may run for a sender in [current] on a server hosting
     * [hosted]: only a [SHARED] command, only in Lifesteal, only where this server hosts nothing
     * but Lifesteal (so its data is Lifesteal's; same rule as `TutorialRules.economyBelongsTo`).
     */
    fun sharedHere(command: String, current: ServerGamemode, hosted: Set<ServerGamemode>): Boolean =
        command.lowercase() in SHARED &&
            command.lowercase() !in BLACKLIST &&
            current == ServerGamemode.LIFESTEAL &&
            hosted == setOf(ServerGamemode.LIFESTEAL)

    fun guardOf(command: String): Guard? = SHARED[command.lowercase()]

    /** The live state a [Guard.COMPETITIVE] check reads. A check that couldn't run counts as true. */
    data class CompetitiveState(
        val combatTagged: Boolean,
        /** Held by a ranked match, spectating one, or with a ranked inventory restore still pending. */
        val ranked: Boolean,
    )

    /** Null = allowed. Fixed order: combat, then ranked. */
    fun competitiveRefusal(guard: Guard, state: CompetitiveState, label: String): String? = when {
        guard != Guard.COMPETITIVE -> null
        state.combatTagged -> "You can't use /$label while in combat."
        state.ranked -> "You can't use /$label during a Ranked Team Wars match."
        else -> null
    }

    /** The refusal for a blacklisted command typed in Lifesteal, instead of "only available on Survival". */
    fun blacklistMessage(command: String): String = "/${command.lowercase()} is disabled on Lifesteal."

    enum class GodDecision { ALLOWED, STAFF_ONLY }

    const val GOD_STAFF_ONLY_MESSAGE = "God Mode is staff-only on Lifesteal."

    /**
     * Turning God Mode on for **yourself** outside Survival needs a staff rank ([isStaff]: an
     * explicitly granted staff group, ops = Owner; donor and Lifesteal ranks never are). Turning
     * it off, Survival, and toggling someone else (already `joshymc.god.others`) are unaffected.
     * Kills in God Mode still earn nothing (issue #1487), staff or not.
     */
    fun godSelfUse(gamemode: ServerGamemode, selfUse: Boolean, enabling: Boolean, isStaff: Boolean): GodDecision =
        if (selfUse && enabling && gamemode != ServerGamemode.SURVIVAL && !isStaff) GodDecision.STAFF_ONLY else GodDecision.ALLOWED
}
