package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.logging.Level

/**
 * The one place every completed item sale is reported. Sell paths (/sell and its GUI, the
 * server shop, the market, Sell Wands) take the items, pay the player, then call [recordSale]
 * or [recordSales] once with what was actually sold. This manager fans that out to every
 * system that cares about sales, so a new sell path or a new consumer is a single change.
 *
 * Reporting never touches money or items: a failing consumer is logged and skipped, the sale
 * itself already happened.
 */
class SaleManager(private val plugin: Joshymc) {

    /** Where a sale came from, for consumers that want to tell sell paths apart. */
    enum class Source { SELL_GUI, SELL_COMMAND, SERVER_SHOP, MARKET, SELL_WAND }

    /** One material sold in one transaction. [amount] is the number of items actually removed. */
    data class Sale(val player: Player, val material: Material, val amount: Int, val source: Source)

    /** Reports a completed sale of [amount] [material]. Zero or negative amounts are ignored. */
    fun recordSale(player: Player, material: Material, amount: Int, source: Source, earned: Double = 0.0) {
        if (amount <= 0) return
        dispatch(Sale(player, material, amount, source))
        recordEarnings(player, earned, source)
    }

    /**
     * Reports a completed multi-material sale: one record per material with its sold amount.
     * [earned] is the Money actually deposited for the whole sale.
     */
    fun recordSales(player: Player, sold: Map<Material, Int>, source: Source, earned: Double = 0.0) {
        for ((material, amount) in sold) recordSale(player, material, amount, source)
        if (sold.values.any { it > 0 }) recordEarnings(player, earned, source)
    }

    /**
     * Money a sale paid (issue #1387): the tutorial's "Earn Money" objective. Only sales reach
     * this, so admin grants, /pay transfers, refunds and rewards never count as earnings.
     */
    private fun recordEarnings(player: Player, earned: Double, source: Source) {
        if (!earned.isFinite() || earned <= 0.0) return
        try {
            plugin.tutorialSignal(player, com.liam.joshymc.tutorial.TutorialSignal.Earned(earned))
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Failed to report sale earnings of ${player.name} ($source) to the tutorial", e)
        }
    }

    private fun dispatch(sale: Sale) {
        notify("market", sale) { plugin.marketManager.recordTransaction(it.material, "SELL", it.amount) }
        notify("event quests", sale) { plugin.eventQuestManager.recordSale(it.player, it.material, it.amount) }
    }

    private inline fun notify(consumer: String, sale: Sale, block: (Sale) -> Unit) {
        try {
            block(sale)
        } catch (e: Exception) {
            plugin.logger.log(
                Level.WARNING,
                "Failed to report sale of ${sale.amount}x ${sale.material} by ${sale.player.name} " +
                    "(${sale.source}) to $consumer",
                e
            )
        }
    }
}
