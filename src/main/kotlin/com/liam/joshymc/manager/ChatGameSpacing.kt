package com.liam.joshymc.manager

import net.kyori.adventure.text.Component

/**
 * Spacing shared by the chat game announcements (issue #1282). Each
 * announcement opens and closes with one blank line. A winner always follows
 * a player's chat answer, so two announcements never stack blank lines.
 */
object ChatGameSpacing {

    /**
     * One blank chat line. A single space rather than an empty string, so a
     * leading or trailing line break can't be trimmed away.
     */
    val BLANK_LINE: Component = Component.text(" ")
}
