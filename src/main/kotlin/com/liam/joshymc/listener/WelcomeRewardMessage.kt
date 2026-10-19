package com.liam.joshymc.listener

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import java.text.DecimalFormat

/**
 * The line a player gets after welcoming a new player (issue #1257):
 *
 *   + $10,000 & 1 Credit for welcoming Steve!
 *
 * The rewards are bold and colored, the rest is a light neutral. The
 * newcomer's name is plain [Component.text], never parsed, so it can't
 * carry formatting.
 */
object WelcomeRewardMessage {

    val PLUS: TextColor = TextColor.color(0x55FF55)
    val MONEY: TextColor = TextColor.color(0x55FF55)
    val CREDITS: TextColor = TextColor.color(0x55FFFF)
    val TEXT: TextColor = TextColor.color(0xF5E6CC)
    val NAME: TextColor = TextColor.color(0xFFFFFF)

    fun build(money: Double, credits: Double, newcomerName: String): Component =
        Component.text()
            .append(Component.text("+ ", PLUS).decoration(TextDecoration.BOLD, true))
            .append(Component.text(formatMoney(money), MONEY).decoration(TextDecoration.BOLD, true))
            .append(Component.text(" & ", TEXT))
            .append(Component.text(formatCredits(credits), CREDITS).decoration(TextDecoration.BOLD, true))
            .append(Component.text(" for welcoming ", TEXT))
            .append(Component.text(newcomerName, NAME))
            .append(Component.text("!", TEXT))
            .build()

    /** `$10,000` for whole amounts, `$10,000.50` otherwise. */
    fun formatMoney(amount: Double): String =
        "$" + DecimalFormat(if (amount % 1.0 == 0.0) "#,##0" else "#,##0.00").format(amount)

    /** `1 Credit`, `10 Credits`, `2.5 Credits`. */
    fun formatCredits(amount: Double): String =
        DecimalFormat("#,##0.##").format(amount) + if (amount == 1.0) " Credit" else " Credits"
}
