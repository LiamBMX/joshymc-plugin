package com.liam.joshymc.staff

/**
 * How a staff member may open another player's inventory (issue #1353, audit finding D-H2).
 * Bukkit-free.
 *
 * `/invsee` used to open the target's live inventory for every `joshymc.invsee` holder. Items
 * taken from a player held by a Lifesteal ranked match came back when the match restored its
 * inventory snapshot (a duplication), and any holder could silently strip gear from higher staff.
 *
 *  - Viewing is a read-only copy unless the viewer holds [EDIT_PERMISSION] (or ModMode's
 *    `joshymc.modmode.edit` on the ModMode path).
 *  - Editing falls back to read-only when the target is held by a ranked match or waits for a
 *    snapshot restore, or when staff-rank protection refuses the viewer
 *    ([StaffProtection], equal/higher staff). Editing your own inventory is never refused by
 *    protection.
 *  - [InvseeGuardListener] also cancels clicks in any already-open live view of a player who
 *    becomes held later.
 */
object InvseeRules {
    const val EDIT_PERMISSION = "joshymc.invsee.edit"

    enum class Mode { LIVE, READ_ONLY }

    enum class Reason(val message: String?) {
        NONE(null),
        NO_EDIT_PERMISSION(null),
        RANKED("They're in a ranked match (or waiting for its restore), so their inventory is view-only."),
        PROTECTED("Staff-rank protection: you can only view their inventory."),
    }

    data class Decision(val mode: Mode, val reason: Reason)

    /**
     * [canEdit]: the viewer holds the edit node; [rankedHeld]: the target is held by a ranked
     * match or has a pending snapshot restore; [protectionRefused]: staff-rank protection refused
     * the viewer for this target; [self]: the viewer is the target.
     */
    fun decide(canEdit: Boolean, rankedHeld: Boolean, protectionRefused: Boolean, self: Boolean): Decision = when {
        !canEdit -> Decision(Mode.READ_ONLY, Reason.NO_EDIT_PERMISSION)
        rankedHeld -> Decision(Mode.READ_ONLY, Reason.RANKED)
        protectionRefused && !self -> Decision(Mode.READ_ONLY, Reason.PROTECTED)
        else -> Decision(Mode.LIVE, Reason.NONE)
    }

    /** A click in a live view of [holder]'s inventory by someone else is refused while held. */
    fun liveClickRefused(viewerIsHolder: Boolean, holderRankedHeld: Boolean): Boolean =
        !viewerIsHolder && holderRankedHeld
}
