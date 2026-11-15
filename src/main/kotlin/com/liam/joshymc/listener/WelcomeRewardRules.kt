package com.liam.joshymc.listener

import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.tutorial.TutorialRules

/**
 * Who may open and earn the chat "welcome" reward (issue #1548, LS-ECO-01), Bukkit-free.
 *
 * The reward ($10,000 Money + 1 Credit per welcomer) is a Survival SMP feature. It is paid into
 * this server's `economy` table, so it only exists where that table is Survival's
 * ([TutorialRules.economyBelongsTo]): never on the dedicated Lifesteal server, whatever world the
 * players stand in. On a Survival server that also maps worlds to Lifesteal, a newcomer who joins
 * in a Lifesteal world opens no window, and a welcomer standing in a Lifesteal world is never paid.
 * The join message, the join number and the MOTD are not rewards and stay on every server.
 */
object WelcomeRewardRules {

    /** Whether a first join by a player in [newcomerGamemode] opens the 30-second welcome window. */
    fun opensWindow(hosted: Set<ServerGamemode>, newcomerGamemode: ServerGamemode): Boolean =
        TutorialRules.economyBelongsTo(ServerGamemode.SURVIVAL, hosted) && newcomerGamemode == ServerGamemode.SURVIVAL

    /**
     * Whether a welcomer currently in [welcomerGamemode] is paid. Checked when the "welcome" line is
     * read **and** again on the main thread right before the deposit, so a window opened earlier
     * never pays a welcomer who has moved into a Lifesteal world (or a server whose economy stopped
     * being Survival's) in between.
     */
    fun pays(hosted: Set<ServerGamemode>, welcomerGamemode: ServerGamemode): Boolean =
        TutorialRules.economyBelongsTo(ServerGamemode.SURVIVAL, hosted) && welcomerGamemode == ServerGamemode.SURVIVAL
}
