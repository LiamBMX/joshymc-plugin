package com.liam.joshymc.crate

/**
 * Issue #1541 (audit CR-P2-07): which permission node each crate administration action needs.
 * Bukkit-free. `joshymc.crate.admin` used to gate all of them, so staff trusted to nudge a
 * model could also mint keys and force rewards. Every action now has its own node and **no**
 * node implies another: there is no parent, no wildcard child and no fallback to the old node,
 * which grants nothing any more (plugin.yml keeps it only as a deprecated, `default: false`
 * entry so old LuckPerms grants show up as harmless). Ops still pass every check through
 * Bukkit's op default; see docs/staff-permissions.md.
 *
 * Reward-generating nodes ([GIVEKEY], [FORCE], [EDITOR]) are separate from the harmless ones
 * ([DISPLAY], [LOCATION], [MONTHLY]), so display-only or location-only staff can never create a
 * key, a reward or a reward path.
 */
object CratePermissions {
    /** `/crate givekey` (mints keys). */
    const val GIVEKEY = "joshymc.crate.givekey"
    /** `/crate force` (opens a crate, so pays a reward, without a key). */
    const val FORCE = "joshymc.crate.force"
    /** `/crate monthly status|set|reset` (which month's crate rotating locations open). */
    const val MONTHLY = "joshymc.crate.monthly"
    /** `/crate display`, `/crate rotate` (incl. quick rotate) and `/crate models` (looks only). */
    const val DISPLAY = "joshymc.crate.display"
    /** `/crate setlocation|removelocation|locations` (where crates are, never what they give). */
    const val LOCATION = "joshymc.crate.location"
    /** `/crateeditor` and every click/chat step inside it (rewards, odds, keys, deletion). */
    const val EDITOR = "joshymc.crateeditor"
    /** Crash-interrupted reward recovery (issue #1529), unchanged. */
    const val RECOVER = "joshymc.crate.rewards.recover"
    /** The old all-in-one node. Grants nothing: never check it. */
    const val LEGACY_ADMIN = "joshymc.crate.admin"

    /** Every crate staff node, for "is this a crate staff member" hints. Excludes [LEGACY_ADMIN]. */
    val STAFF_NODES: List<String> = listOf(GIVEKEY, FORCE, MONTHLY, DISPLAY, LOCATION, EDITOR, RECOVER)

    /** Nodes that can create keys or rewards (directly or through the editor). */
    val REWARD_NODES: Set<String> = setOf(GIVEKEY, FORCE, EDITOR)

    /** `/crate <sub>` admin subcommands and the one node each needs (`diagnose` is [diagnose]). */
    val SUBCOMMANDS: Map<String, String> = linkedMapOf(
        "setlocation" to LOCATION,
        "removelocation" to LOCATION,
        "locations" to LOCATION,
        "givekey" to GIVEKEY,
        "force" to FORCE,
        "models" to DISPLAY,
        "rotate" to DISPLAY,
        "display" to DISPLAY,
        "monthly" to MONTHLY,
        "rewards" to RECOVER,
    )

    /** `/crate` subcommands every player may use (also the only ones allowed through delegation). */
    val PLAYER_SUBCOMMANDS: Set<String> = setOf("menu", "browse", "help", "list", "preview")

    /** The node [sub] needs, or null for player subcommands and unknown words. */
    fun nodeFor(sub: String): String? = SUBCOMMANDS[sub.lowercase()]

    /** `/crate diagnose` is read-only and useful to both location and display staff. */
    fun diagnose(has: (String) -> Boolean): Boolean = has(LOCATION) || has(DISPLAY)

    /** Whether [sub] (a `/crate` first argument) is allowed for a sender with [has]. */
    fun allows(sub: String, has: (String) -> Boolean): Boolean {
        val s = sub.lowercase()
        if (s == "diagnose" || s == "inspect") return diagnose(has)
        val node = nodeFor(s) ?: return true
        return has(node)
    }

    /** Admin subcommands to offer in tab completion, in a stable order. */
    fun adminSubcommands(has: (String) -> Boolean): List<String> =
        (SUBCOMMANDS.keys.filter { has(SUBCOMMANDS.getValue(it)) } + listOfNotNull("diagnose".takeIf { diagnose(has) }))

    /** Crate staff of any kind (gets the "Admin:" hints when a crate refuses to open). */
    fun isCrateStaff(has: (String) -> Boolean): Boolean = STAFF_NODES.any(has)
}
