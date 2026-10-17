package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.candyshop.CandySelection
import com.liam.joshymc.candyshop.CandyShopManager
import com.liam.joshymc.candyshop.CandyShopStore
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.time.Instant

/**
 * `/candy` (alias `/trickortreat`, issue #1211): opens the Candy Man's shop. Both labels run this
 * one executor against the one [CandyShopManager], so they are the same shop.
 *
 * `/candy admin ...` (`joshymc.candy.admin`) manages the pool: additem (from the held candy),
 * removeitem, setprice, setchance, enable, disable, list and schedule. Every change is audited.
 */
class CandyCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val shop get() = plugin.candyShopManager
    private val pink = CandyShopManager.CANDY_PINK

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!shop.isRunning) {
            Msg.error(sender, "The Candy Shop is disabled on this server.")
            return true
        }
        if (args.firstOrNull()?.equals("admin", ignoreCase = true) == true) {
            if (!sender.hasPermission(CandyShopManager.ADMIN_PERMISSION)) {
                Msg.noPermission(sender)
                return true
            }
            admin(sender, args.drop(1))
            return true
        }
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }
        if (!sender.hasPermission("joshymc.candy")) {
            Msg.noPermission(sender)
            return true
        }
        shop.openShop(sender)
        return true
    }

    private fun admin(sender: CommandSender, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "additem" -> addItem(sender, args)
            "removeitem" -> withEntry(sender, args, "/candy admin removeitem <id>") { id, actor ->
                if (shop.store.removeEntry(id, actor.first, actor.second)) {
                    log(sender, "removed entry #$id")
                    Msg.success(sender, "Removed entry #$id from future Candy Shop openings. A running opening keeps its current stock.")
                } else Msg.error(sender, "No entry #$id.")
            }
            "setprice" -> withEntry(sender, args, "/candy admin setprice <id> <price>") { id, actor ->
                val price = parsePrice(sender, args.getOrNull(2)) ?: return@withEntry
                if (shop.store.setPrice(id, price, actor.first, actor.second)) {
                    log(sender, "set entry #$id price to $price")
                    Msg.success(sender, "Entry #$id now costs ${plugin.economyManager.format(price)} from the next opening on.")
                } else Msg.error(sender, "No entry #$id.")
            }
            "setchance" -> withEntry(sender, args, "/candy admin setchance <id> <percentage>") { id, actor ->
                val chance = parseChance(sender, args.getOrNull(2)) ?: return@withEntry
                if (shop.store.setChance(id, chance, actor.first, actor.second)) {
                    log(sender, "set entry #$id chance to $chance%")
                    Msg.success(sender, "Entry #$id now has a ${fmtPct(chance)}% chance from the next opening on.")
                    reportPool(sender)
                } else Msg.error(sender, "No entry #$id.")
            }
            "enable", "disable" -> {
                val enable = args[0].equals("enable", ignoreCase = true)
                withEntry(sender, args, "/candy admin ${args[0].lowercase()} <id>") { id, actor ->
                    if (enable) {
                        val e = shop.store.entry(id)
                        val other = e?.let { shop.store.listEntries().firstOrNull { o -> o.enabled && o.itemId == it.itemId && o.id != id } }
                        if (other != null) {
                            Msg.error(sender, "Entry #${other.id} already sells that candy. Disable or remove it first.")
                            return@withEntry
                        }
                    }
                    if (shop.store.setEnabled(id, enable, actor.first, actor.second)) {
                        log(sender, "${if (enable) "enabled" else "disabled"} entry #$id")
                        Msg.success(sender, "Entry #$id is now ${if (enable) "enabled" else "disabled"} for future openings.")
                        reportPool(sender)
                    } else Msg.error(sender, "No entry #$id, or it is already ${if (enable) "enabled" else "disabled"}.")
                }
            }
            "list" -> list(sender)
            "schedule" -> schedule(sender)
            else -> {
                Msg.usage(sender, "/candy admin <additem|removeitem|setprice|setchance|enable|disable|list|schedule>")
            }
        }
    }

    private fun addItem(sender: CommandSender, args: List<String>) {
        if (sender !is Player) {
            Msg.error(sender, "additem reads the item in your main hand, so it can only be run in-game.")
            return
        }
        if (args.size != 3) {
            Msg.usage(sender, "/candy admin additem <price> <percentage>")
            return
        }
        val held = sender.inventory.itemInMainHand
        if (held.type.isAir) {
            Msg.error(sender, "Hold the candy you want to add in your main hand.")
            return
        }
        // Identified by its custom item id tag, never by display name.
        val itemId = plugin.itemManager.getCustomItemId(held)
        val candy = itemId?.let { shop.candyItem(it) }
        if (itemId == null || candy == null) {
            Msg.error(sender, "That isn't a registered JoshyMC candy. Only custom candies can be sold in the Candy Shop.")
            return
        }
        val price = parsePrice(sender, args[1]) ?: return
        val chance = parseChance(sender, args[2]) ?: return
        val existing = shop.store.entryForItem(itemId)
        if (existing != null) {
            Msg.error(sender, "Entry #${existing.id} already sells that candy. Use /candy admin setprice or setchance, or remove it first.")
            return
        }
        val id = shop.store.addEntry(itemId, price, chance, sender.uniqueId, sender.name)
        log(sender, "added entry #$id: $itemId price=$price chance=$chance%")
        Msg.reply(sender, Component.text("Added ", NamedTextColor.GREEN)
            .append(candy.displayName)
            .append(Component.text(" as entry ", NamedTextColor.GREEN))
            .append(Component.text("#$id", pink))
            .append(Component.text(": ${plugin.economyManager.format(price)}, ${fmtPct(chance)}% chance per opening.", NamedTextColor.GREEN)))
        reportPool(sender)
    }

    private fun withEntry(sender: CommandSender, args: List<String>, usage: String, block: (Int, Pair<java.util.UUID?, String>) -> Unit) {
        // args start at the subcommand, so drop "/candy" and "admin" from the usage's word count.
        val needed = usage.split(" ").size - 2
        if (args.size != needed) {
            Msg.usage(sender, usage)
            return
        }
        val id = args[1].removePrefix("#").toIntOrNull()
        if (id == null || id <= 0) {
            Msg.error(sender, "'${args[1]}' isn't an entry id. See /candy admin list.")
            return
        }
        block(id, (sender as? Player)?.uniqueId to sender.name)
    }

    /** Money price: finite, above zero, at most [CandyShopManager.MAX_PRICE], rounded to cents. */
    private fun parsePrice(sender: CommandSender, raw: String?): Double? {
        val value = raw?.let { plugin.economyManager.parseAmount(it) }
        if (value == null || !value.isFinite() || value <= 0.0) {
            Msg.error(sender, "The price must be a Money amount above 0 (e.g. 10000 or 10k).")
            return null
        }
        if (value > CandyShopManager.MAX_PRICE) {
            Msg.error(sender, "The price can be at most ${plugin.economyManager.format(CandyShopManager.MAX_PRICE)}.")
            return null
        }
        val cents = Math.round(value * 100.0) / 100.0
        if (cents <= 0.0) {
            Msg.error(sender, "The price must be at least $0.01.")
            return null
        }
        return cents
    }

    /** Appearance chance in percent: finite, above 0, at most 100. */
    private fun parseChance(sender: CommandSender, raw: String?): Double? {
        val value = raw?.removeSuffix("%")?.trim()?.toDoubleOrNull()
        if (value == null || !value.isFinite() || value <= 0.0 || value > 100.0) {
            Msg.error(sender, "The percentage must be a number above 0 and at most 100.")
            return null
        }
        return value
    }

    private fun list(sender: CommandSender) {
        val entries = shop.store.listEntries()
        if (entries.isEmpty()) {
            Msg.reply(sender, Component.text("The Candy Shop pool is empty. Hold a candy and use /candy admin additem <price> <percentage>.", NamedTextColor.YELLOW))
            return
        }
        val pool = shop.eligiblePool()
        val plan = CandySelection.plan(pool.entries.map { CandySelection.Candidate(it.id, it.chancePercent) }, shop.mode)
        Msg.reply(sender, Component.text("Candy Shop pool", pink).append(Component.text(" (${shop.mode.name.lowercase()} chances)", NamedTextColor.GRAY)))
        for (e in entries) {
            val candy = shop.candyItem(e.itemId)
            val name: Component = candy?.displayName ?: Component.text("${e.itemId} (not a registered candy)", NamedTextColor.RED)
            val effective = plan.inclusion[e.id]
            var line = Component.text("#${e.id} ", pink).append(name)
                .append(Component.text(" - ${plugin.economyManager.format(e.price)} - ${fmtPct(e.chancePercent)}%", NamedTextColor.GRAY))
            if (effective != null && plan.adjusted) {
                line = line.append(Component.text(" (effective ${fmtPct(effective * 100)}%)", NamedTextColor.YELLOW))
            }
            line = line.append(Component.text(if (e.enabled) " enabled" else " disabled", if (e.enabled) NamedTextColor.GREEN else NamedTextColor.RED))
            sender.sendMessage(line)
        }
        reportPool(sender, plan, pool.warnings)
    }

    /** Tells the admin whether the pool can fill an opening, and whether chances are adjusted. */
    private fun reportPool(
        sender: CommandSender,
        plan: CandySelection.Plan = shop.eligiblePool().let { p -> CandySelection.plan(p.entries.map { CandySelection.Candidate(it.id, it.chancePercent) }, shop.mode) },
        warnings: List<String> = emptyList(),
    ) {
        val total = fmtPct(plan.configuredTotalPercent)
        when {
            !plan.feasible -> plan.problems.forEach { sender.sendMessage(Component.text("⚠ $it", NamedTextColor.RED)) }
            plan.adjusted -> sender.sendMessage(Component.text(
                "Enabled chances add up to $total%, not 300% (3 candies per opening), so they are scaled to 300%. Effective chances are shown above.",
                NamedTextColor.YELLOW,
            ))
            else -> sender.sendMessage(Component.text("Enabled chances add up to 300%, so every chance is exact.", NamedTextColor.GREEN))
        }
        warnings.forEach { sender.sendMessage(Component.text("⚠ $it", NamedTextColor.YELLOW)) }
    }

    private fun schedule(sender: CommandSender) {
        val now = Instant.now()
        val openings = shop.scheduleForAdmin(now)
        Msg.reply(sender, Component.text("Candy Shop schedule (${shop.zone.id})", pink))
        if (openings.isEmpty()) {
            sender.sendMessage(Component.text("No openings scheduled today.", NamedTextColor.GRAY))
            return
        }
        for (o in openings) {
            val color = when (o.status) {
                CandyShopStore.OpeningStatus.OPEN -> NamedTextColor.GREEN
                CandyShopStore.OpeningStatus.SCHEDULED -> NamedTextColor.YELLOW
                CandyShopStore.OpeningStatus.CLOSED -> NamedTextColor.GRAY
                else -> NamedTextColor.RED
            }
            var line = Component.text("#${o.id} ${o.day} ${shop.formatClock(o.start)}-${shop.formatClock(o.end)} ", NamedTextColor.WHITE)
                .append(Component.text(o.status.name, color))
            if (o.status == CandyShopStore.OpeningStatus.OPEN || o.status == CandyShopStore.OpeningStatus.CLOSED) {
                val stock = shop.store.stockFor(o.id).joinToString { "#${it.entryId} ${it.itemId}" }
                if (stock.isNotEmpty()) line = line.append(Component.text(" [$stock]", NamedTextColor.GRAY))
            }
            if (o.status == CandyShopStore.OpeningStatus.SKIPPED && o.note != null) {
                line = line.append(Component.text(" ${o.note}", NamedTextColor.GRAY))
            }
            sender.sendMessage(line)
        }
    }

    private fun log(sender: CommandSender, what: String) {
        plugin.logger.info("[CandyShop] ${sender.name} $what.")
    }

    private fun fmtPct(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!shop.isRunning || !sender.hasPermission(CandyShopManager.ADMIN_PERMISSION)) return emptyList()
        val subs = listOf("additem", "removeitem", "setprice", "setchance", "enable", "disable", "list", "schedule")
        return when (args.size) {
            1 -> listOf("admin").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> if (args[0].equals("admin", true)) subs.filter { it.startsWith(args[1], ignoreCase = true) } else emptyList()
            3 -> when (args[1].lowercase()) {
                "additem" -> listOf("10000", "100k", "1m").filter { it.startsWith(args[2], ignoreCase = true) }
                "removeitem", "setprice", "setchance", "enable", "disable" ->
                    shop.store.listEntries().map { it.id.toString() }.filter { it.startsWith(args[2]) }
                else -> emptyList()
            }
            4 -> when (args[1].lowercase()) {
                "additem", "setchance" -> listOf("1", "10", "25", "50", "60", "100").filter { it.startsWith(args[3]) }
                "setprice" -> listOf("10000", "100k", "1m").filter { it.startsWith(args[3], ignoreCase = true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
