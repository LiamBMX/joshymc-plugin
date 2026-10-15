package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.AdminBalanceResult
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.entity.Player
import java.math.BigDecimal
import java.util.UUID

/**
 * Operator-only REMOVE / SET of another player's Coins or Credits, online or offline
 * (issue #1162). Shared by `/eco take|remove|set|reset`, `/money remove|set` and
 * `/credits take|remove|set|reset`.
 *
 * Only server operators and the console may run it; permissions alone never grant it.
 * Targets resolve through [com.liam.joshymc.manager.PlayerResolver] (no Mojang lookups)
 * and must have joined this server, so no account is ever made for a stranger. The
 * change and its `admin_logs` audit row commit in one transaction.
 */
class AdminBalanceCommand(private val plugin: Joshymc) {

    enum class Currency(val label: String, val scale: Int, val max: BigDecimal) {
        COINS("Coins", 2, BigDecimal("10000000000000")),     // 10T: cents stay exact in a double
        CREDITS("Credits", 1, BigDecimal("1000000000")),
    }

    enum class Operation { REMOVE, SET }

    private val category = CommunicationsManager.Category.ECONOMY

    /** Operators and the server console only, for every execution path. */
    fun canUse(sender: CommandSender): Boolean =
        sender is ConsoleCommandSender || sender is RemoteConsoleCommandSender || (sender is Player && sender.isOp)

    /** Handles `<cmd> <sub> <player> <amount>`; [args] starts after the subcommand. */
    fun handle(sender: CommandSender, currency: Currency, operation: Operation, usage: String, args: List<String>) {
        if (!canUse(sender)) {
            Msg.noPermission(sender, category)
            return
        }
        if (args.size < 2) {
            Msg.usage(sender, usage, category)
            return
        }
        apply(sender, currency, operation, args[0], args[1])
    }

    /** `reset <player>`: a SET to zero, kept for the existing /eco and /credits subcommand. */
    fun handleReset(sender: CommandSender, currency: Currency, usage: String, args: List<String>) {
        if (!canUse(sender)) {
            Msg.noPermission(sender, category)
            return
        }
        if (args.isEmpty()) {
            Msg.usage(sender, usage, category)
            return
        }
        apply(sender, currency, Operation.SET, args[0], "0")
    }

    private fun apply(sender: CommandSender, currency: Currency, operation: Operation, targetInput: String, amountInput: String) {
        val target = resolve(targetInput)
        if (target == null) {
            Msg.playerNotFound(sender, targetInput, category)
            return
        }
        val (targetUuid, targetName) = target

        val amount = parseAmount(amountInput, currency, allowZero = operation == Operation.SET)
        if (amount == null) {
            val rule = if (operation == Operation.SET) "0 or more" else "greater than 0"
            Msg.error(
                sender,
                "Invalid amount: $amountInput. Use a number $rule, at most ${currency.scale} decimal place(s) " +
                    "and no more than ${format(currency, currency.max.toDouble())}.",
                category
            )
            return
        }

        val actor = sender as? Player
        val audit: (AdminBalanceResult.Changed) -> Unit = { change ->
            val delta = BigDecimal.valueOf(change.previous).subtract(BigDecimal.valueOf(change.current)).abs()
            val details = "currency=${currency.name} op=${operation.name} previous=${change.previous} " +
                "new=${change.current} changed=${delta.toPlainString()} target_uuid=$targetUuid"
            plugin.adminManager.logAction(actor?.uniqueId, sender.name, "BALANCE_${operation.name}", targetUuid, targetName, details)
            plugin.logger.info(
                "[AdminBalance] ${sender.name}${actor?.let { " (${it.uniqueId})" } ?: ""} ${operation.name} ${currency.label} " +
                    "for $targetName ($targetUuid): ${change.previous} -> ${change.current} (changed ${delta.toPlainString()})"
            )
        }

        val result = try {
            when (currency to operation) {
                Currency.COINS to Operation.REMOVE -> plugin.economyManager.adminRemove(targetUuid, amount, audit)
                Currency.COINS to Operation.SET -> plugin.economyManager.adminSet(targetUuid, amount, audit)
                Currency.CREDITS to Operation.REMOVE -> plugin.creditsManager.adminRemove(targetUuid, amount, audit)
                else -> plugin.creditsManager.adminSet(targetUuid, amount, audit)
            }
        } catch (e: Exception) {
            plugin.logger.severe("[AdminBalance] ${operation.name} ${currency.label} for $targetName ($targetUuid) by ${sender.name} failed: ${e.message}")
            Msg.error(sender, "Could not update $targetName's ${currency.label}: database error. Nothing was changed.", category)
            return
        }

        when (result) {
            is AdminBalanceResult.Insufficient -> Msg.reply(
                sender,
                Component.text(targetName, NamedTextColor.WHITE)
                    .append(Component.text(" only has ", NamedTextColor.RED))
                    .append(amountText(currency, result.balance))
                    .append(Component.text(". Nothing was removed.", NamedTextColor.RED)),
                category
            )
            is AdminBalanceResult.Changed -> Msg.reply(sender, successMessage(currency, operation, targetName, result), category)
        }
    }

    private fun successMessage(currency: Currency, operation: Operation, targetName: String, change: AdminBalanceResult.Changed): Component {
        val name = Component.text(targetName, NamedTextColor.WHITE)
        val newBalance = Component.newline()
            .append(Component.text("New balance: ", NamedTextColor.GRAY))
            .append(amountText(currency, change.current))
            .append(Component.text(".", NamedTextColor.GRAY))
        return when (operation) {
            Operation.REMOVE -> Component.text("Removed ", NamedTextColor.GREEN)
                .append(amountText(currency, change.previous - change.current))
                .append(Component.text(" from ", NamedTextColor.GREEN))
                .append(name)
                .append(Component.text(".", NamedTextColor.GREEN))
                .append(newBalance)
            Operation.SET -> Component.text("Set ", NamedTextColor.GREEN)
                .append(name)
                .append(Component.text(if (currency == Currency.COINS) "'s balance to " else "'s Credits balance to ", NamedTextColor.GREEN))
                .append(amountText(currency, change.current))
                .append(Component.text(" (was ", NamedTextColor.GRAY))
                .append(amountText(currency, change.previous))
                .append(Component.text(").", NamedTextColor.GRAY))
        }
    }

    private fun amountText(currency: Currency, amount: Double): Component = when (currency) {
        Currency.COINS -> Component.text(format(currency, amount), NamedTextColor.GOLD)
        Currency.CREDITS -> Component.text("${format(currency, amount)} Credits", NamedTextColor.AQUA)
    }

    private fun format(currency: Currency, amount: Double): String = when (currency) {
        Currency.COINS -> plugin.economyManager.format(amount)
        Currency.CREDITS -> plugin.creditsManager.format(amount)
    }

    /**
     * The player named [input], only if they have joined this server (online, local
     * player data, or JoshyMC's `player_names`). Never contacts Mojang and never returns
     * a made-up UUID, so Floodgate players resolve by their stored UUID like anyone else.
     */
    private fun resolve(input: String): Pair<UUID, String>? {
        val player = plugin.playerResolver.resolve(input) ?: return null
        val recordedName = plugin.playerResolver.lastKnownName(player.uniqueId)
        if (!player.isOnline && !player.hasPlayedBefore() && recordedName == null) return null
        return player.uniqueId to (player.name ?: recordedName ?: input)
    }

    companion object {
        private val SUFFIXES = listOf(
            "k" to BigDecimal("1000"),
            "m" to BigDecimal("1000000"),
            "b" to BigDecimal("1000000000"),
            "t" to BigDecimal("1000000000000"),
        )

        /**
         * Parses an amount exactly (no floating-point rounding): plain decimals, `,`
         * separators, a leading `$`, and k/m/b/t suffixes. Rejects NaN/Infinity, negatives,
         * zero unless [allowZero], more decimals than the currency keeps, and amounts over
         * the currency's maximum.
         */
        fun parseAmount(input: String, currency: Currency, allowZero: Boolean): Double? {
            var text = input.trim().replace(",", "").removePrefix("$").lowercase()
            var multiplier = BigDecimal.ONE
            SUFFIXES.firstOrNull { text.endsWith(it.first) }?.let { (suffix, factor) ->
                text = text.removeSuffix(suffix)
                multiplier = factor
            }
            if (text.isEmpty() || !text.all { it.isDigit() || it == '.' }) return null
            val value = try {
                BigDecimal(text).multiply(multiplier)
            } catch (e: NumberFormatException) {
                return null
            }
            if (value.signum() < 0 || (value.signum() == 0 && !allowZero)) return null
            if (value.stripTrailingZeros().scale() > currency.scale) return null
            if (value > currency.max) return null
            return value.toDouble()
        }
    }
}
