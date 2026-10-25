package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.eventshop.EventShopAdminMenu
import com.liam.joshymc.eventshop.EventShopManager
import com.liam.joshymc.eventshop.EventShopManager.OverrideResult
import com.liam.joshymc.eventshop.EventShopSchedule
import com.liam.joshymc.eventshop.EventShopSelection
import com.liam.joshymc.eventshop.EventShopStore
import com.liam.joshymc.eventshop.EventShopStore.Field
import com.liam.joshymc.eventshop.EventShopStore.ItemKind
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `/eventshop` (alias `/eshop`, issue #1299): opens the Survival Event Shop. Both labels run this
 * one executor against the one [EventShopManager], so they are the same shop.
 *
 * `/eventshop admin ...` (`joshymc.eventshop.admin`) manages the pool and the shop: add (from the
 * held item), remove, list, edit, enable, disable, reload, status, rotate, open, close, and with
 * no subcommand the admin menu. Every change is audited in `event_shop_audit` and logged.
 */
class EventShopCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val shop get() = plugin.eventShopManager
    private val gold = EventShopManager.EVENT_GOLD
    private val adminMenu by lazy { EventShopAdminMenu(plugin) }

    private val subcommands = listOf("add", "remove", "list", "edit", "enable", "disable", "reload", "status", "rotate", "open", "close")
    private val fields = Field.entries.map { it.name.lowercase() }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!shop.isRunning) {
            Msg.error(sender, "The Event Shop is disabled on this server.")
            return true
        }
        if (args.firstOrNull()?.equals("admin", ignoreCase = true) == true) {
            if (!sender.hasPermission(EventShopManager.ADMIN_PERMISSION)) {
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
        if (!sender.hasPermission(EventShopManager.USE_PERMISSION)) {
            Msg.noPermission(sender)
            return true
        }
        shop.openShop(sender)
        return true
    }

    private fun actor(sender: CommandSender): UUID? = (sender as? Player)?.uniqueId

    private fun admin(sender: CommandSender, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            null, "gui", "menu" -> {
                if (sender is Player) adminMenu.open(sender) else usage(sender)
            }
            "add" -> add(sender, args)
            "remove" -> withListing(sender, args, 2, "/eventshop admin remove <listing-id>") { id ->
                if (shop.store.removeListing(id, actor(sender), sender.name)) {
                    log(sender, "removed listing #$id")
                    Msg.success(sender, "Removed listing #$id from future Event Shop rotations. A running rotation keeps its current items.")
                    reportPool(sender)
                } else Msg.error(sender, "No listing #$id.")
            }
            "enable", "disable" -> {
                val enable = args[0].equals("enable", ignoreCase = true)
                withListing(sender, args, 2, "/eventshop admin ${args[0].lowercase()} <listing-id>") { id ->
                    if (shop.store.setEnabled(id, enable, actor(sender), sender.name)) {
                        log(sender, "${if (enable) "enabled" else "disabled"} listing #$id")
                        Msg.success(sender, "Listing #$id is now ${if (enable) "enabled" else "disabled"} for future rotations.")
                        reportPool(sender)
                    } else Msg.error(sender, "No listing #$id, or it is already ${if (enable) "enabled" else "disabled"}.")
                }
            }
            "edit" -> edit(sender, args)
            "list" -> list(sender)
            "status" -> status(sender)
            "reload" -> {
                val parsed = shop.reload()
                plugin.eventShopManager.store.audit(actor(sender), sender.name, "RELOAD", null, null, "schedule=${shop.describeSchedule()}")
                log(sender, "reloaded the Event Shop settings")
                if (parsed.valid) Msg.success(sender, "Event Shop settings reloaded. Schedule: ${shop.describeSchedule()}.")
                else {
                    Msg.error(sender, "Reloaded, but event-shop.schedule is invalid, so no scheduled openings will happen until it's fixed:")
                    parsed.problems.forEach { sender.sendMessage(Component.text("⚠ $it", NamedTextColor.RED)) }
                }
            }
            "rotate" -> when (val r = shop.reroll(sender)) {
                is OverrideResult.Ok -> {
                    log(sender, "rerolled the Event Shop into rotation #${r.active.rotation.id}")
                    Msg.success(sender, "New rotation #${r.active.rotation.id} with ${r.active.items.size} items, until ${shop.formatClock(r.active.rotation.end)}. Past purchases are untouched.")
                }
                is OverrideResult.Refused -> Msg.error(sender, r.reason)
            }
            "open" -> {
                val minutes = args.getOrNull(1)?.let { raw ->
                    raw.toLongOrNull()?.takeIf { it in 1..EventShopSchedule.WINDOW.toMinutes() } ?: run {
                        Msg.error(sender, "Minutes must be a whole number from 1 to ${EventShopSchedule.WINDOW.toMinutes()}.")
                        return
                    }
                }
                when (val r = shop.openManually(sender, minutes?.let { Duration.ofMinutes(it) } ?: EventShopSchedule.WINDOW)) {
                    is OverrideResult.Ok -> Msg.success(sender, "Opened manual rotation #${r.active.rotation.id} with ${r.active.items.size} items until " +
                        "${shop.formatClock(r.active.rotation.end)}. It ends early if a scheduled opening comes first.")
                    is OverrideResult.Refused -> Msg.error(sender, r.reason)
                }
            }
            "close" -> when (val r = shop.closeManually(sender)) {
                is OverrideResult.Ok -> Msg.success(sender, "Closed rotation #${r.active.rotation.id}. The next scheduled opening still happens as normal.")
                is OverrideResult.Refused -> Msg.error(sender, r.reason)
            }
            else -> usage(sender)
        }
    }

    private fun usage(sender: CommandSender) =
        Msg.usage(sender, "/eventshop admin [add|remove|list|edit|enable|disable|reload|status|rotate|open|close]")

    private fun add(sender: CommandSender, args: List<String>) {
        if (sender !is Player) {
            Msg.error(sender, "add reads the item in your main hand, so it can only be run in-game.")
            return
        }
        if (args.size !in 2..3) {
            Msg.usage(sender, "/eventshop admin add <price> [quantity]")
            return
        }
        val held = sender.inventory.itemInMainHand
        if (held.type.isAir || held.amount <= 0) {
            Msg.error(sender, "Hold the item you want to sell in your main hand.")
            return
        }
        val price = parsePrice(sender, args[1]) ?: return
        val unit = held.asOne()
        val quantity = if (args.size == 3) parseQuantity(sender, args[2], unit.maxStackSize) ?: return else held.amount

        // A registered custom item that hasn't been changed is stored as a reference and rebuilt
        // from its definition at delivery; anything else is stored as its full serialized stack.
        val customId = plugin.itemManager.getCustomItemId(unit)
        val def = customId?.let { plugin.itemManager.getItem(it) }
        if (customId != null && def == null) {
            Msg.error(sender, "That item carries the custom item id '$customId', which is no longer registered.")
            return
        }
        val scope = def?.gamemodeScope
        if (scope != null && scope != GamemodeScope.SURVIVAL && scope != GamemodeScope.NETWORK) {
            Msg.error(sender, "That item belongs to ${scope.gamemode?.displayName ?: scope.id}, not Survival.")
            return
        }
        val isReference = def != null && unit.isSimilar(def.createItemStack(1))
        val kind = if (isReference) ItemKind.CUSTOM else ItemKind.STACK
        // A custom name if it has one, else the material ("DIAMOND_SWORD" -> "Diamond Sword").
        val meta = unit.itemMeta
        val custom = meta?.displayName() ?: meta?.takeIf { it.hasItemName() }?.itemName()
        val name = (custom?.let { PlainTextComponentSerializer.plainText().serialize(it) }?.takeIf { it.isNotBlank() }
            ?: unit.type.name.lowercase().split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
            .take(EventShopManager.MAX_NAME_LENGTH)
        val id = shop.store.addListing(name, kind, if (isReference) customId else null, shop.serialize(unit), unit.type.name, quantity, price, sender.uniqueId, sender.name)
        log(sender, "added listing #$id: $name ($kind${customId?.let { " $it" } ?: ""}) x$quantity price=$price")
        Msg.reply(sender, Component.text("Added ", NamedTextColor.GREEN)
            .append(unit.effectiveName().hoverEvent(unit.asHoverEvent()))
            .append(Component.text(" as listing ", NamedTextColor.GREEN))
            .append(Component.text("#$id", gold))
            .append(Component.text(": x$quantity for ${plugin.economyManager.format(price)} " +
                (if (isReference) "(custom item '$customId')." else "(full item data saved)."), NamedTextColor.GREEN)))
        reportPool(sender)
    }

    private fun edit(sender: CommandSender, args: List<String>) {
        val usage = "/eventshop admin edit <listing-id> <${fields.joinToString("|")}> <value>"
        if (args.size < 4) {
            Msg.usage(sender, usage)
            return
        }
        val field = Field.entries.firstOrNull { it.name.equals(args[2], ignoreCase = true) }
        if (field == null) {
            Msg.usage(sender, usage)
            return
        }
        if (field != Field.NAME && args.size != 4) {
            Msg.usage(sender, usage)
            return
        }
        withListing(sender, args, null, usage) { id ->
            val listing = shop.store.listing(id) ?: run {
                Msg.error(sender, "No listing #$id.")
                return@withListing
            }
            val raw = args[3]
            val value: Any = when (field) {
                Field.PRICE -> parsePrice(sender, raw) ?: return@withListing
                Field.QUANTITY -> parseQuantity(sender, raw, shop.template(listing)?.maxStackSize ?: 64) ?: return@withListing
                Field.WEIGHT -> parseInt(sender, raw, 1, EventShopManager.MAX_WEIGHT, "The weight") ?: return@withListing
                Field.LIMIT -> parseInt(sender, raw, 0, EventShopManager.MAX_LIMIT, "The per-player limit (0 = none)") ?: return@withListing
                Field.STOCK -> parseInt(sender, raw, 0, EventShopManager.MAX_LIMIT, "The stock per rotation (0 = unlimited)") ?: return@withListing
                Field.NAME -> {
                    val name = args.drop(3).joinToString(" ").trim()
                    if (name.isEmpty() || name.length > EventShopManager.MAX_NAME_LENGTH) {
                        Msg.error(sender, "The name must be 1 to ${EventShopManager.MAX_NAME_LENGTH} characters.")
                        return@withListing
                    }
                    name
                }
                Field.CURRENCY -> {
                    val currency = EventShopStore.Currency.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    if (currency == null) {
                        Msg.error(sender, "The Event Shop only sells for Money (currency 'money').")
                        return@withListing
                    }
                    currency.name
                }
            }
            if (shop.store.editListing(id, field, value, actor(sender), sender.name)) {
                log(sender, "set listing #$id ${field.name.lowercase()} to $value")
                Msg.success(sender, "Listing #$id: ${field.name.lowercase()} is now ${display(field, value)} from the next rotation on.")
            } else Msg.error(sender, "No listing #$id.")
        }
    }

    private fun display(field: Field, value: Any): String = when (field) {
        Field.PRICE -> plugin.economyManager.format(value as Double)
        Field.LIMIT, Field.STOCK -> if (value == 0) "unlimited" else value.toString()
        else -> value.toString()
    }

    /** Runs [block] with the listing id in `args[1]`; [size] is the exact arg count (null = at least 2). */
    private fun withListing(sender: CommandSender, args: List<String>, size: Int?, usage: String, block: (Int) -> Unit) {
        if ((size != null && args.size != size) || args.size < 2) {
            Msg.usage(sender, usage)
            return
        }
        val id = args[1].removePrefix("#").toIntOrNull()
        if (id == null || id <= 0) {
            Msg.error(sender, "'${args[1]}' isn't a listing id. See /eventshop admin list.")
            return
        }
        block(id)
    }

    /** Money price: finite, above zero, at most [EventShopManager.MAX_PRICE], rounded to cents. */
    private fun parsePrice(sender: CommandSender, raw: String?): Double? {
        val value = raw?.let { plugin.economyManager.parseAmount(it) }
        if (value == null || !value.isFinite() || value <= 0.0) {
            Msg.error(sender, "The price must be a Money amount above 0 (e.g. 10000 or 10k).")
            return null
        }
        if (value > EventShopManager.MAX_PRICE) {
            Msg.error(sender, "The price can be at most ${plugin.economyManager.format(EventShopManager.MAX_PRICE)}.")
            return null
        }
        val cents = Math.round(value * 100.0) / 100.0
        if (cents <= 0.0) {
            Msg.error(sender, "The price must be at least $0.01.")
            return null
        }
        return cents
    }

    /** Items per purchase: 1 up to a full inventory (36 slots of this item's stack size). */
    private fun parseQuantity(sender: CommandSender, raw: String, maxStack: Int): Int? {
        val max = (36 * maxStack.coerceAtLeast(1)).coerceAtMost(EventShopManager.MAX_QUANTITY)
        return parseInt(sender, raw, 1, max, "The quantity")
    }

    private fun parseInt(sender: CommandSender, raw: String, min: Int, max: Int, what: String): Int? {
        val value = raw.toIntOrNull()
        if (value == null || value < min || value > max) {
            Msg.error(sender, "$what must be a whole number from $min to $max.")
            return null
        }
        return value
    }

    private fun list(sender: CommandSender) {
        val listings = shop.store.listListings()
        if (listings.isEmpty()) {
            Msg.reply(sender, Component.text("The Event Shop pool is empty. Hold an item and use /eventshop admin add <price> [quantity].", NamedTextColor.YELLOW))
            return
        }
        Msg.reply(sender, Component.text("Event Shop listings (${listings.size})", gold))
        for (l in listings) {
            val problem = shop.problemOf(l)
            var line = Component.text("#${l.id} ", gold)
                .append(Component.text(l.displayName, NamedTextColor.WHITE))
                .append(Component.text(" - x${l.quantity} - ${plugin.economyManager.format(l.price)} - weight ${l.weight}", NamedTextColor.GRAY))
            if (l.perPlayerLimit > 0) line = line.append(Component.text(" - limit ${l.perPlayerLimit}/player", NamedTextColor.GRAY))
            if (l.stockLimit > 0) line = line.append(Component.text(" - stock ${l.stockLimit}", NamedTextColor.GRAY))
            line = line.append(Component.text(if (l.kind == ItemKind.CUSTOM) " [custom: ${l.customItemId}]" else " [saved item]", NamedTextColor.DARK_GRAY))
            line = line.append(Component.text(if (l.enabled) " enabled" else " disabled", if (l.enabled) NamedTextColor.GREEN else NamedTextColor.RED))
            if (problem != null) line = line.append(Component.text(" (not eligible: $problem)", NamedTextColor.RED))
            shop.template(l)?.let { line = line.hoverEvent(it.asHoverEvent()) }
            sender.sendMessage(line)
        }
        reportPool(sender)
    }

    /** Tells the admin whether the pool can fill all seven slots. */
    private fun reportPool(sender: CommandSender) {
        val pool = shop.eligiblePool()
        val n = pool.listings.size
        when {
            n == 0 -> sender.sendMessage(Component.text("⚠ No eligible listings: the Event Shop can't open until one is added and enabled.", NamedTextColor.RED))
            n < EventShopSelection.SLOTS -> sender.sendMessage(Component.text(
                "⚠ Only $n eligible listings: rotations will be underfilled and show only those $n until there are ${EventShopSelection.SLOTS}.", NamedTextColor.YELLOW))
            else -> sender.sendMessage(Component.text("$n eligible listings: every rotation picks ${EventShopSelection.SLOTS} different ones.", NamedTextColor.GREEN))
        }
        pool.warnings.forEach { sender.sendMessage(Component.text("⚠ $it", NamedTextColor.YELLOW)) }
    }

    private fun status(sender: CommandSender) {
        val now = Instant.now()
        val a = shop.active?.takeIf { it.rotation.isActiveAt(now) }
        Msg.reply(sender, Component.text("Event Shop status", gold))
        if (a != null) {
            sender.sendMessage(Component.text("OPEN", NamedTextColor.GREEN)
                .append(Component.text(" - rotation #${a.rotation.id} (${a.rotation.kind.name.lowercase()})", NamedTextColor.GRAY))
                .append(Component.text(" - ${shop.formatDuration(Duration.between(now, a.rotation.end))} left, closes at ${shop.formatClock(a.rotation.end)}", NamedTextColor.YELLOW)))
            for (item in a.items) {
                val sold = shop.store.soldCount(a.rotation.windowId, item.listingId)
                sender.sendMessage(Component.text("  ${item.position + 1}. #${item.listingId} ${item.displayName}", NamedTextColor.WHITE)
                    .append(Component.text(" x${item.quantity} - ${plugin.economyManager.format(item.price)} - sold $sold" +
                        if (item.stockLimit > 0) "/${item.stockLimit}" else "", NamedTextColor.GRAY)))
            }
            if (a.items.size < EventShopSelection.SLOTS) {
                sender.sendMessage(Component.text("⚠ This rotation is underfilled: ${a.items.size}/${EventShopSelection.SLOTS} slots.", NamedTextColor.YELLOW))
            }
        } else {
            sender.sendMessage(Component.text("CLOSED", NamedTextColor.RED))
        }
        val next = shop.nextOpening(now)
        sender.sendMessage(Component.text("Next opening: ", NamedTextColor.GRAY)
            .append(Component.text(next?.let { "${shop.formatClock(it.start)} (in ${shop.formatDuration(Duration.between(now, it.start))})" } ?: "none scheduled", NamedTextColor.YELLOW)))
        val eligible = shop.eligiblePool().listings.size
        sender.sendMessage(Component.text("Eligible listings: ", NamedTextColor.GRAY)
            .append(Component.text("$eligible", if (eligible >= EventShopSelection.SLOTS) NamedTextColor.GREEN else NamedTextColor.RED))
            .append(Component.text(if (eligible < EventShopSelection.SLOTS) " (underfilled, needs ${EventShopSelection.SLOTS})" else "", NamedTextColor.YELLOW)))
        if (shop.schedule.valid) {
            sender.sendMessage(Component.text("Schedule: ", NamedTextColor.GRAY).append(Component.text(shop.describeSchedule(), NamedTextColor.WHITE)))
            for (w in shop.windowsForAdmin(now)) {
                val r = shop.store.rotationByKey(w.key)
                val state = when {
                    r != null -> r.status.name + " #${r.id}"
                    w.end <= now -> "NOT OPENED"
                    w.isActiveAt(now) -> "OPENING"
                    else -> "UPCOMING"
                }
                sender.sendMessage(Component.text("  ${w.day} ${shop.formatClock(w.start)} - ${shop.formatClock(w.end)} ", NamedTextColor.WHITE)
                    .append(Component.text(state, NamedTextColor.GRAY)))
            }
        } else {
            sender.sendMessage(Component.text("Schedule is INVALID (no scheduled openings):", NamedTextColor.RED))
            shop.schedule.problems.forEach { sender.sendMessage(Component.text("  ⚠ $it", NamedTextColor.RED)) }
        }
    }

    private fun log(sender: CommandSender, what: String) {
        plugin.logger.info("[EventShop] ${sender.name} $what.")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!shop.isRunning || !sender.hasPermission(EventShopManager.ADMIN_PERMISSION)) return emptyList()
        return when (args.size) {
            1 -> listOf("admin").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> if (args[0].equals("admin", true)) subcommands.filter { it.startsWith(args[1], ignoreCase = true) } else emptyList()
            3 -> when (args[1].lowercase()) {
                "add" -> listOf("10000", "100k", "1m").filter { it.startsWith(args[2], ignoreCase = true) }
                "remove", "edit", "enable", "disable" -> shop.store.listListings().map { it.id.toString() }.filter { it.startsWith(args[2]) }
                "open" -> listOf("30", "60", "120").filter { it.startsWith(args[2]) }
                else -> emptyList()
            }
            4 -> when (args[1].lowercase()) {
                "add" -> listOf("1", "16", "64").filter { it.startsWith(args[3]) }
                "edit" -> fields.filter { it.startsWith(args[3], ignoreCase = true) }
                else -> emptyList()
            }
            5 -> if (args[1].equals("edit", true) && args[3].equals("currency", true)) listOf("money").filter { it.startsWith(args[4], true) } else emptyList()
            else -> emptyList()
        }
    }
}
