package com.liam.joshymc.freetag

import com.liam.joshymc.gamemode.ServerGamemode

/**
 * `/freetag` (issue #1339): the free [JoshyMC] Chat Tag Voucher, claimable once per player.
 * Bukkit-free rules; [FreeTagManager] does the delivery and [FreeTagStore] the persistence.
 */
object FreeTags {

    /** The Chat Tag voucher tag handed out, the same one `/voucher give <player> chattag_joshymc` gives. */
    const val TAG_ID = "joshymc"

    /** The `/voucher give` id of that voucher. */
    const val VOUCHER_ID = "chattag_$TAG_ID"

    const val MSG_SUCCESS = "&aYou successfully claimed your free &f[JoshyMC] &achat tag voucher!"
    const val MSG_ALREADY_CLAIMED = "&cYou have already claimed your free JoshyMC chat tag!"
    const val MSG_DELIVERY_FAILED = "&cUnable to deliver your free chat tag. Please try again later."

    enum class Decision {
        /** Not on Survival: the command doesn't exist there. */
        WRONG_GAMEMODE,
        /** A claim row already exists (completed, or one being delivered right now). */
        ALREADY_CLAIMED,
        /** The `joshymc` voucher tag doesn't exist on this server, so there is nothing to deliver. */
        TAG_MISSING,
        /** No free inventory slot: refuse rather than drop the voucher on the ground. */
        NO_ROOM,
        CLAIM,
    }

    /** Fixed order: gamemode, then the claim (so a claimed player is always told so), then delivery checks. */
    fun decide(gamemode: ServerGamemode, claimed: Boolean, tagAvailable: Boolean, hasRoom: Boolean): Decision = when {
        gamemode != ServerGamemode.SURVIVAL -> Decision.WRONG_GAMEMODE
        claimed -> Decision.ALREADY_CLAIMED
        !tagAvailable -> Decision.TAG_MISSING
        !hasRoom -> Decision.NO_ROOM
        else -> Decision.CLAIM
    }
}
