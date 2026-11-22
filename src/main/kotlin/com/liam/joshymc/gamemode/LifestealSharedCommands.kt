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
        // Issue #1599 (Owner-approved player utilities).
        // Server warps are this server's own `warps` table, set by Lifesteal staff; the teleport goes
        // through TeleportChecks + CombatTeleportGuard, and on Lifesteal also a safe-spot check on the
        // async-loaded destination (WarpCommand). The End lock and ranked arena access refuse the
        // PlayerTeleportEvent itself.
        "warp" to Guard.COMPETITIVE,
        // Random teleport: TeleportChecks, CombatTeleportGuard on every warmup tick, its own safe-spot
        // search; the End stays behind `joshymc.rtp.end` and the progression End lock.
        "rtp" to Guard.COMPETITIVE,
        // The server shop, minus competitive PvP supplies in Lifesteal worlds (LifestealShopRules).
        "shop" to Guard.COMPETITIVE,
        // Issue #1601 phase 1 (cosmetics). Each only picks which of the player's own unlocked
        // cosmetics is equipped, stored in this server's own tables (`player_tags`/
        // `chat_tag_unlocks`, `chat_colors`, `player_kill_effects`); ownership is still the per-tag
        // permission or unlock, `joshymc.chatcolor[.special]` and `joshymc.killeffect.<id>`. Chat
        // tags and colours already render on Lifesteal (CommunicationsManager) and Chat Tag Vouchers
        // already redeem there; kill effects are particles/sounds only (Firework Pop deals no damage).
        // No coins, items or movement, so no competitive guard (the ranked listener still refuses
        // them typed during a match).
        "tag" to Guard.NONE,
        "chatcolor" to Guard.NONE,
        "killeffect" to Guard.NONE,
    )

    /**
     * Issue #1599: SURVIVAL-scoped **staff** commands that also run on a dedicated Lifesteal server,
     * for the parts of them Lifesteal needs. Like [SHARED] they keep their SURVIVAL scope (mixed
     * servers and Survival are unchanged) and use only this server's own data (its own `warps`,
     * `economy`, `crate_*` tables and crates.yml). On Lifesteal:
     *
     *  - Only the invocations [adminInvocation] allows run (e.g. `/event inventory ...`, not the rest
     *    of `/event`); anything else gets the normal "only available on Survival" refusal.
     *  - [visibleWith] (if set) must be held for the command to be listed, tab-completed or run there,
     *    so ordinary players never see a staff command they can't use.
     *  - [minimumRank] is a staff-rank floor on top of the command's own permission checks (which
     *    all still run in the executor): console always passes, ops count as Owner.
     */
    data class AdminShare(
        /** Permission node needed to see/use the command on Lifesteal; null = the command's own permission only. */
        val visibleWith: String?,
        /** Short description of what is allowed on Lifesteal (docs, refusal text). */
        val allowed: String,
    )

    val ADMIN_SHARED: Map<String, AdminShare> = linkedMapOf(
        "setwarp" to AdminShare(null, "/setwarp <name>"),
        "delwarp" to AdminShare(null, "/delwarp <name>"),
        "editwarp" to AdminShare(null, "/editwarp <name>"),
        "eco" to AdminShare(null, "/eco give|take|remove|set|reset"),
        // Players keep the crate browser/preview (crates themselves open by clicking the block); every
        // admin subcommand keeps its CratePermissions node and needs an Admin+ staff rank here.
        "crate" to AdminShare(null, "/crate (browser, preview) and the admin subcommands"),
        "crateeditor" to AdminShare(null, "/crateeditor"),
        "event" to AdminShare("joshymc.event.inventory.recover", "/event inventory list|info|apply|resolve"),
        "enderchest" to AdminShare("joshymc.enderchest.others", "/enderchest <player> (read-only inspection)"),
        "voucher" to AdminShare(null, "/voucher give <player> chattag_<id> [amount], /voucher tag debug"),
    )

    /** First arguments of `/crate` every player may use (mirrors `CratePermissions.PLAYER_SUBCOMMANDS`). */
    private val CRATE_PLAYER_SUBCOMMANDS = setOf("menu", "browse", "help", "list", "preview")

    const val CHAT_TAG_VOUCHER_PREFIX = "chattag_"

    /**
     * Whether `/<command> <args>` is one of the invocations [ADMIN_SHARED] lets through on Lifesteal.
     * [senderName] is the sender's own name (self-service `/enderchest` stays Survival only).
     * [typing] = tab completion of a partial line (the last argument is still being typed), where
     * an incomplete-but-valid prefix is fine; for execution the line must be a whole allowed form.
     */
    fun adminInvocation(command: String, args: List<String>, senderName: String?, typing: Boolean = false): Boolean {
        val a = args.map { it.lowercase() }
        return when (command.lowercase()) {
            "setwarp", "delwarp", "editwarp", "eco", "crate", "crateeditor" -> true
            "event" -> a.firstOrNull() in setOf("inventory", "inv") || (typing && a.size == 1 && "inventory".startsWith(a[0]))
            "enderchest" -> when {
                typing -> a.size == 1
                else -> a.size == 1 && a[0].isNotBlank() && !a[0].equals(senderName, ignoreCase = true)
            }
            "voucher" -> when (a.firstOrNull()) {
                "give" -> if (typing) a.size < 3 || a[2].startsWith(CHAT_TAG_VOUCHER_PREFIX) || CHAT_TAG_VOUCHER_PREFIX.startsWith(a[2])
                    else a.size >= 3 && a[2].startsWith(CHAT_TAG_VOUCHER_PREFIX) && a[2].length > CHAT_TAG_VOUCHER_PREFIX.length
                "tag" -> if (typing) a.size < 2 || "debug".startsWith(a[1]) || a[1] == "debug" else a.size >= 3 && a[1] == "debug"
                else -> typing && a.size == 1 && ("give".startsWith(a[0]) || "tag".startsWith(a[0]))
            }
            else -> false
        }
    }

    /** The staff-rank floor for an allowed [ADMIN_SHARED] invocation on Lifesteal, or null for none. */
    fun minimumRank(command: String, args: List<String>): com.liam.joshymc.staff.StaffRank? = when (command.lowercase()) {
        // Issue #1599 section 6: economy administration is the Owner's alone.
        "eco" -> com.liam.joshymc.staff.StaffRank.OWNER
        // Admin+: warps and crate management.
        "setwarp", "delwarp", "editwarp", "crateeditor" -> com.liam.joshymc.staff.StaffRank.ADMIN
        "crate" -> args.firstOrNull()?.lowercase()?.let { if (it in CRATE_PLAYER_SUBCOMMANDS) null else com.liam.joshymc.staff.StaffRank.ADMIN }
        // Recovery tools keep only their own nodes ("other staff: only their existing tools").
        else -> null
    }

    /** Null = allowed. Console always passes; other non-player senders (command blocks) never pass a floor. */
    fun staffFloorRefusal(
        floor: com.liam.joshymc.staff.StaffRank?,
        isConsole: Boolean,
        isPlayer: Boolean,
        rank: com.liam.joshymc.staff.StaffRank?,
        label: String,
    ): String? = when {
        floor == null || isConsole -> null
        !isPlayer -> "/$label can only be run by staff or the console on Lifesteal."
        rank == null || rank < floor -> "On Lifesteal, /$label needs the ${floor.displayName} staff rank or higher."
        else -> null
    }

    /** Whether [command] is an [ADMIN_SHARED] staff command on a server hosting only Lifesteal, for a sender in [current]. */
    fun adminSharedHere(command: String, current: ServerGamemode, hosted: Set<ServerGamemode>): Boolean =
        command.lowercase() in ADMIN_SHARED &&
            command.lowercase() !in BLACKLIST &&
            current == ServerGamemode.LIFESTEAL &&
            hosted == setOf(ServerGamemode.LIFESTEAL)

    /** The refusal for an [ADMIN_SHARED] command used in a form Lifesteal doesn't share. */
    fun adminFormMessage(command: String): String {
        val share = ADMIN_SHARED[command.lowercase()]
        return if (share == null) "That command is only available on Survival."
        else "On Lifesteal only ${share.allowed} is available; the rest is Survival only."
    }

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

    /**
     * Issue #1599: the server shop in Lifesteal worlds. Coins buy nothing that decides PvP fights:
     * no potions, no custom spawners, no weapons/armor/shields, and none of [COMPETITIVE_MATERIALS]
     * (crystal/anchor PvP, pearls and chorus escapes, gapples, totems, XP for Mending, TNT). Applies
     * to the live shop.yml too, whatever the Owner adds to it. Material names keep it Bukkit-free.
     */
    object LifestealShopRules {
        val COMPETITIVE_MATERIALS: Set<String> = setOf(
            "END_CRYSTAL", "RESPAWN_ANCHOR", "OBSIDIAN", "CRYING_OBSIDIAN", "GLOWSTONE",
            "ENDER_PEARL", "CHORUS_FRUIT", "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "TOTEM_OF_UNDYING",
            "EXPERIENCE_BOTTLE", "TNT", "TNT_MINECART", "ARROW", "SPECTRAL_ARROW", "TIPPED_ARROW",
            "FIREWORK_ROCKET", "COBWEB", "WIND_CHARGE", "ELYTRA", "BOW", "CROSSBOW", "TRIDENT", "MACE",
            "SHIELD", "POTION", "SPLASH_POTION", "LINGERING_POTION", "SPAWNER", "TRIAL_SPAWNER",
            "ENCHANTED_BOOK", "NETHERITE_INGOT", "NETHERITE_SCRAP", "ANCIENT_DEBRIS", "NETHERITE_UPGRADE_SMITHING_TEMPLATE",
            "DIAMOND", "DIAMOND_BLOCK", "SUSPICIOUS_STEW", "GOLDEN_CARROT",
        )

        private val GEAR_SUFFIXES = listOf("_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS", "_SWORD", "_AXE", "_SPEAR")

        /** Whether a shop entry is refused in Lifesteal. [kind] is the entry kind name (MATERIAL, POTION, SPAWNER). */
        fun blocked(material: String, kind: String): Boolean {
            val m = material.uppercase()
            return kind != "MATERIAL" || m in COMPETITIVE_MATERIALS || GEAR_SUFFIXES.any { m.endsWith(it) }
        }

        const val BLOCKED_MESSAGE = "That item isn't sold on Lifesteal."
    }

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
