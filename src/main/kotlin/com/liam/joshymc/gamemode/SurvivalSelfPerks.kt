package com.liam.joshymc.gamemode

/**
 * Survival-only self-use perks (issue #1521, audit #1505 P1-2). `/heal` and `/feed` are
 * NETWORK commands because staff use them on every server, but healing or feeding **yourself**
 * is a Survival donor convenience. If LuckPerms groups are global (or storage is shared), the
 * same `joshymc.heal`/`joshymc.feed` nodes would reach Lifesteal, so the plugin itself refuses
 * self-use anywhere that isn't Survival, whatever permissions (or wildcards) the player has.
 *
 * Acting on another player stays a staff action, decided by the `.others` node in the executor.
 * Bukkit-free.
 */
object SurvivalSelfPerks {

    enum class Decision { ALLOWED, SURVIVAL_ONLY }

    /** The player-facing refusal: the reason is the gamemode, never a missing permission. */
    const val SURVIVAL_ONLY_MESSAGE = "This command is only available on Survival."

    /**
     * [selfUse] = the sender is the player being healed/fed. [targetGamemode] = the gamemode of
     * the world that player is in. No permission is an input: nothing can override this.
     */
    fun decide(targetGamemode: ServerGamemode, selfUse: Boolean): Decision =
        if (selfUse && targetGamemode != ServerGamemode.SURVIVAL) Decision.SURVIVAL_ONLY else Decision.ALLOWED
}
