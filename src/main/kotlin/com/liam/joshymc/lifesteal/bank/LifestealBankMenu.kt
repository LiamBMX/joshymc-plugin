package com.liam.joshymc.lifesteal.bank

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.lifesteal.bounty.BountyMoney
import com.liam.joshymc.lifesteal.bank.BankMenuLayout as L
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Slots of the bank menus (issue #1427), Bukkit-free so the checks can verify them. */
object BankMenuLayout {
    const val MAIN_SIZE = 45
    const val SUMMARY_SLOT = 4
    const val DEPOSIT_HEADER_SLOT = 10
    val DEPOSIT_SLOTS = listOf(11, 12, 13, 14, 15)
    const val PVP_INFO_SLOT = 16
    const val WITHDRAW_HEADER_SLOT = 19
    val WITHDRAW_SLOTS = listOf(20, 21, 22, 23, 24)
    const val TERMS_SLOT = 25
    const val MAIN_CLOSE_SLOT = 40

    const val CONFIRM_SIZE = 27
    const val CONFIRM_SLOT = 11
    const val CONFIRM_SUMMARY_SLOT = 13
    const val CANCEL_SLOT = 15
    const val CONFIRM_CLOSE_SLOT = 22

    /** The percentage buttons; then "All" and "Custom Amount". */
    val PERCENTS = listOf(10, 25, 50)

    /** Every clickable slot is distinct and inside its menu. */
    fun valid(): Boolean {
        val main = listOf(SUMMARY_SLOT, DEPOSIT_HEADER_SLOT, PVP_INFO_SLOT, WITHDRAW_HEADER_SLOT, TERMS_SLOT, MAIN_CLOSE_SLOT) + DEPOSIT_SLOTS + WITHDRAW_SLOTS
        val confirm = listOf(CONFIRM_SLOT, CONFIRM_SUMMARY_SLOT, CANCEL_SLOT, CONFIRM_CLOSE_SLOT)
        return main.distinct().size == main.size && main.all { it in 0 until MAIN_SIZE } &&
            confirm.distinct().size == confirm.size && confirm.all { it in 0 until CONFIRM_SIZE } &&
            DEPOSIT_SLOTS.size == PERCENTS.size + 2 && WITHDRAW_SLOTS.size == PERCENTS.size + 2 &&
            // GuiStyle.closeSlot: bottom row, centre (size - 5).
            MAIN_CLOSE_SLOT == MAIN_SIZE - 5
    }
}

/**
 * The Protected Bank menus (issue #1427): one main screen (balances, capacity, fees, the PvP
 * coin-loss explanation, deposit and withdrawal buttons) and a confirmation screen. Built on the
 * shared [CustomGui] / [GuiStyle], so GuiManager cancels every click on menu slots, blocks
 * shift-click / number-key / drag / off-hand pushes from the player's inventory and rate-limits
 * clicks.
 *
 * Menus never move coins themselves and never trust what they showed: a confirmation calls
 * [LifestealBankManager.transfer], the same path as `/bank deposit|withdraw`, which re-reads the
 * balances and re-checks combat, ranked, events and freezes in the tick it runs. Each
 * confirmation screen has one transaction id and is single use, so double clicks move coins once.
 */
class LifestealBankMenu(private val plugin: Joshymc, private val manager: LifestealBankManager) {

    /** Chat prompts for "Custom Amount" (AsyncChatEvent arrives off the main thread). */
    data class AmountInput(val direction: BankRules.Direction, val at: Long)
    private val inputs = ConcurrentHashMap<UUID, AmountInput>()

    private fun text(s: String, color: NamedTextColor = NamedTextColor.GRAY): Component = Component.text(s, color)
    private fun line(label: String, value: String, color: NamedTextColor = NamedTextColor.GOLD): Component =
        text(label, NamedTextColor.GRAY).append(text(value, color))

    private fun refuse(player: Player): Boolean {
        val reason = manager.unavailableReason() ?: manager.accessBlock(player)?.message ?: return false
        manager.send(player, "&c$reason")
        GuiStyle.errorSound(player)
        return true
    }

    // ── Main screen ─────────────────────────────────────────

    fun openMain(player: Player) {
        if (refuse(player)) return
        val balances = manager.balances(player.uniqueId) ?: run {
            manager.send(player, "&cYour balances couldn't be read. Please try again.")
            return
        }
        val s = manager.effectiveBank
        val depositsOpen = manager.bankSettings?.enabled == true
        val capacity = manager.capacityFor(player.uniqueId)
        val coins = manager.coinSettings
        val gui = CustomGui(plugin.commsManager.parseLegacy(s.gui.title), L.MAIN_SIZE)

        gui.setItem(L.SUMMARY_SLOT, GuiStyle.skull(player, text("Your Coins", NamedTextColor.GOLD), listOfNotNull(
            line("Wallet: ", BountyMoney.format(balances.walletCents)),
            line("Protected Bank: ", BountyMoney.format(balances.bankCents), NamedTextColor.GREEN),
            capacity?.let { line("Capacity: ", "${BountyMoney.format(balances.bankCents)} / ${BountyMoney.format(it)}", NamedTextColor.WHITE) }
                ?: line("Capacity: ", "Unlimited", NamedTextColor.WHITE),
            capacity?.let { line("Space left: ", BountyMoney.format((it - balances.bankCents).coerceAtLeast(0)), NamedTextColor.WHITE) },
        )))

        gui.setItem(L.DEPOSIT_HEADER_SLOT, GuiStyle.item(Material.HOPPER, text("Deposit", NamedTextColor.GREEN), listOf(
            text("Move coins from your wallet"), text("into your protected bank."),
            if (depositsOpen) line("Fee: ", BankRules.formatBps(s.depositFeeBps), NamedTextColor.WHITE) else text("Deposits are turned off right now.", NamedTextColor.RED),
        )))
        gui.setItem(L.WITHDRAW_HEADER_SLOT, GuiStyle.item(Material.DISPENSER, text("Withdraw", NamedTextColor.AQUA), listOf(
            text("Move coins from your bank"), text("back into your wallet."),
            line("Fee: ", BankRules.formatBps(s.withdrawalFeeBps), NamedTextColor.WHITE),
        )))
        amountButtons(gui, BankRules.Direction.DEPOSIT, L.DEPOSIT_SLOTS, balances.walletCents, depositsOpen)
        amountButtons(gui, BankRules.Direction.WITHDRAW, L.WITHDRAW_SLOTS, balances.bankCents, true)

        gui.setItem(L.PVP_INFO_SLOT, GuiStyle.item(Material.IRON_SWORD, text("PvP Coin Loss", NamedTextColor.RED), when {
            coins == null || !coins.enabled -> listOf(text("Coin stealing is off right now."), text("Nothing is lost on death."))
            else -> listOfNotNull(
                text("When another player kills you in PvP,"),
                text("they take ", NamedTextColor.GRAY).append(text(BankRules.formatBps(coins.walletLossBps), NamedTextColor.RED)).append(text(" of your wallet")),
                coins.maxPerKillCents?.let { text("(at most ${BountyMoney.format(it)} per death).") },
                Component.empty(),
                text("Coins in your bank are protected", NamedTextColor.GREEN),
                text("and can never be stolen.", NamedTextColor.GREEN),
                Component.empty(),
                text("You can't bank while in combat", NamedTextColor.DARK_GRAY),
                text("or during a ranked match.", NamedTextColor.DARK_GRAY),
            )
        }))
        gui.setItem(L.TERMS_SLOT, GuiStyle.item(Material.PAPER, text("Fees & Capacity", NamedTextColor.YELLOW), listOf(
            line("Deposit fee: ", BankRules.formatBps(s.depositFeeBps), NamedTextColor.WHITE),
            line("Withdrawal fee: ", BankRules.formatBps(s.withdrawalFeeBps), NamedTextColor.WHITE),
            line("Capacity: ", capacity?.let { BountyMoney.format(it) } ?: "Unlimited", NamedTextColor.WHITE),
            text("Fees are shown before you confirm."),
        )))
        gui.setItem(L.MAIN_CLOSE_SLOT, GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    private fun amountButtons(gui: CustomGui, direction: BankRules.Direction, slots: List<Int>, sourceCents: Long, open: Boolean) {
        val verb = if (direction == BankRules.Direction.DEPOSIT) "Deposit" else "Withdraw"
        val from = if (direction == BankRules.Direction.DEPOSIT) "wallet" else "bank"
        if (!open) {
            slots.forEach { slot -> gui.setItem(slot, GuiStyle.item(Material.BARRIER, text("Deposits are off", NamedTextColor.RED), listOf(text("You can still withdraw.")))) }
            return
        }
        val materials = listOf(Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.RAW_GOLD)
        L.PERCENTS.forEachIndexed { i, percent ->
            val cents = BankRules.percentOf(sourceCents, percent)
            gui.setItem(slots[i], GuiStyle.item(materials[i], text("$verb $percent%", NamedTextColor.GOLD), listOf(
                text("$percent% of your $from: ", NamedTextColor.GRAY).append(text(BountyMoney.format(cents), NamedTextColor.GOLD)),
                text("Click to review", NamedTextColor.YELLOW),
            ))) { p, _ ->
                GuiStyle.clickSound(p)
                // Recomputed from the balance now, not the one this screen showed.
                val now = manager.balances(p.uniqueId) ?: return@setItem
                val source = if (direction == BankRules.Direction.DEPOSIT) now.walletCents else now.bankCents
                openConfirm(p, direction, BankRules.percentOf(source, percent))
            }
        }
        gui.setItem(slots[L.PERCENTS.size], GuiStyle.item(Material.GOLD_BLOCK, text("$verb All", NamedTextColor.GOLD), listOf(
            text("Everything in your $from", NamedTextColor.GRAY),
            text("(what fits, if your bank has a capacity).", NamedTextColor.DARK_GRAY),
            text("Click to review", NamedTextColor.YELLOW),
        ))) { p, _ ->
            GuiStyle.clickSound(p)
            openConfirm(p, direction, null)
        }
        gui.setItem(slots[L.PERCENTS.size + 1], GuiStyle.item(Material.OAK_SIGN, text("Custom Amount", NamedTextColor.GREEN), listOf(
            text("Type any amount in chat"), text("(e.g. 5000, 75k or 1.5m)."),
        ))) { p, _ ->
            GuiStyle.clickSound(p)
            inputs[p.uniqueId] = AmountInput(direction, System.currentTimeMillis())
            p.closeInventory()
            manager.send(p, "&eType the amount to ${verb.lowercase()} in chat, or &fcancel&e.")
        }
    }

    // ── Confirmation ────────────────────────────────────────

    /** [cents] null = all. Shows the exact amount, fee and result before anything moves. */
    fun openConfirm(player: Player, direction: BankRules.Direction, cents: Long?) {
        if (refuse(player)) return
        val balances = manager.balances(player.uniqueId) ?: return
        val s = manager.effectiveBank
        val feeBps = if (direction == BankRules.Direction.DEPOSIT) s.depositFeeBps else s.withdrawalFeeBps
        val plan = when (val p = BankRules.plan(direction, cents, balances.walletCents, balances.bankCents, manager.capacityFor(player.uniqueId), feeBps)) {
            is BankRules.PlanResult.Refused -> {
                manager.send(player, "&c${p.reason.message}")
                GuiStyle.errorSound(player)
                return
            }
            is BankRules.PlanResult.Ok -> p.plan
        }
        val deposit = direction == BankRules.Direction.DEPOSIT
        val walletAfter = if (deposit) balances.walletCents - plan.amountCents else balances.walletCents + plan.netCents
        val bankAfter = if (deposit) balances.bankCents + plan.netCents else balances.bankCents - plan.amountCents
        val gui = CustomGui(plugin.commsManager.parseLegacy(s.gui.confirmTitle), L.CONFIRM_SIZE)
        val txId = UUID.randomUUID().toString()
        var used = false
        val verb = if (deposit) "Deposit" else "Withdraw"
        gui.setItem(L.CONFIRM_SUMMARY_SLOT, GuiStyle.item(Material.PAPER, text("$verb ${if (cents == null) "All" else BountyMoney.format(plan.amountCents)}", NamedTextColor.GOLD), listOf(
            line("Amount: ", BountyMoney.format(plan.amountCents)),
            line("Fee: ", BountyMoney.format(plan.feeCents), if (plan.feeCents > 0) NamedTextColor.RED else NamedTextColor.WHITE),
            line(if (deposit) "Into your bank: " else "Into your wallet: ", BountyMoney.format(plan.netCents), NamedTextColor.GREEN),
            Component.empty(),
            line("Wallet after: ", BountyMoney.format(walletAfter)),
            line("Bank after: ", BountyMoney.format(bankAfter), NamedTextColor.GREEN),
            if (cents == null) text("\"All\" uses your balance when you confirm.", NamedTextColor.DARK_GRAY) else Component.empty(),
        )))
        gui.setItem(L.CONFIRM_SLOT, GuiStyle.item(Material.LIME_DYE, text("Confirm", NamedTextColor.GREEN), listOf(text("$verb now.")))) { p, _ ->
            if (used) return@setItem
            used = true
            p.closeInventory()
            when (manager.transfer(p, direction, cents, txId, "gui")) {
                is LifestealBankManager.Outcome.Done -> { GuiStyle.successSound(p); openMain(p) }
                is LifestealBankManager.Outcome.Refused -> GuiStyle.errorSound(p)
            }
        }
        gui.setItem(L.CANCEL_SLOT, GuiStyle.item(Material.RED_DYE, text("Cancel", NamedTextColor.RED), listOf(text("Back to the bank")))) { p, _ ->
            GuiStyle.clickSound(p)
            openMain(p)
        }
        gui.setItem(L.CONFIRM_CLOSE_SLOT, GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Chat input ──────────────────────────────────────────

    fun isAwaiting(uuid: UUID): Boolean {
        val input = inputs[uuid] ?: return false
        if (System.currentTimeMillis() - input.at > INPUT_TIMEOUT_MILLIS) {
            inputs.remove(uuid, input)
            return false
        }
        return true
    }

    /** Main thread, from the chat listener. */
    fun handleInput(player: Player, raw: String) {
        val input = inputs.remove(player.uniqueId) ?: return
        val typed = raw.trim()
        if (typed.equals("cancel", ignoreCase = true)) {
            manager.send(player, "&7Cancelled.")
            return
        }
        if (typed.equals("all", ignoreCase = true)) return openConfirm(player, input.direction, null)
        when (val parsed = BountyMoney.parse(typed)) {
            is BountyMoney.Parsed.Ok -> openConfirm(player, input.direction, parsed.cents)
            else -> {
                manager.send(player, "&c${LifestealBankCommand.parseProblem(parsed)} &eType another amount, or &fcancel&e.")
                inputs[player.uniqueId] = input.copy(at = System.currentTimeMillis())
            }
        }
    }

    fun forget(uuid: UUID) {
        inputs.remove(uuid)
    }

    fun clear() {
        inputs.clear()
    }

    companion object {
        const val INPUT_TIMEOUT_MILLIS = 60_000L
    }
}
