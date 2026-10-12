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
    fun recordSale(player: Player, material: Material, amount: Int, source: Source) {
        if (amount <= 0) return
        dispatch(Sale(player, material, amount, source))
    }

    /** Reports a completed multi-material sale: one record per material with its sold amount. */
    fun recordSales(player: Player, sold: Map<Material, Int>, source: Source) {
        for ((material, amount) in sold) recordSale(player, material, amount, source)
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
