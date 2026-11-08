package com.liam.joshymc.vanish

/**
 * Bukkit-free vanish visibility rules (issue #1467). `VanishCommand` applies them with Paper's
 * `hidePlayer`/`showPlayer`, which also remove/restore the TAB list entry.
 */
object VanishRules {

    /** Permission that lets a player vanish and see vanished players. */
    const val PERMISSION = "joshymc.vanish"

    /**
     * Metadata key other plugins (TAB, TAB-Bridge, DiscordSRV, PlaceholderAPI, ...) read to
     * learn that a player is vanished. The value is a boolean.
     */
    const val METADATA_KEY = "vanished"

    /**
     * Whether [viewer] must not see the target, from both of JoshyMC's reasons combined:
     * vanish (unless the viewer may see vanished players) and the viewer's `/hidestaff` toggle.
     * A player always sees themselves.
     */
    fun hides(
        viewerIsTarget: Boolean,
        targetVanished: Boolean,
        viewerSeesVanished: Boolean,
        hiddenByHideStaff: Boolean,
    ): Boolean {
        if (viewerIsTarget) return false
        return (targetVanished && !viewerSeesVanished) || hiddenByHideStaff
    }

    /** A stored vanish is kept on join only while the player can still vanish. */
    fun keepOnJoin(stored: Boolean, hasPermission: Boolean): Boolean = stored && hasPermission
}
