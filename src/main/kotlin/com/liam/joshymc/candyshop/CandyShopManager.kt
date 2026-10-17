package com.liam.joshymc.candyshop

import com.liam.joshymc.Joshymc
import com.liam.joshymc.candyshop.CandyShopStore.OpeningStatus
import com.liam.joshymc.candyshop.CandyShopStore.PurchaseStatus
import com.liam.joshymc.candyshop.CandyShopStore.StockItem
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.manager.TimezoneManager
import com.liam.joshymc.util.depositItemSafely
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The Candy Man's rotating Candy Shop (issue #1211), opened with `/candy` or `/trickortreat`.
 *
 * Four openings a day at random quarter hours at least three hours apart ([CandySchedule]), each
 * open for 30 minutes with three different candies picked once when it opens ([CandySelection]).
 * Everything is persisted in [CandyShopStore] before it takes effect, so a restart resumes the
 * same schedule and the same stock and never repeats an announcement:
 *  - a window that passed entirely while the server was offline becomes MISSED, silently;
 *  - an opening that was OPEN but whose window ended while offline is CLOSED, silently;
 *  - an OPEN opening still inside its window resumes with its saved stock, unannounced;
 *  - a SCHEDULED opening whose window is running at startup opens for the remaining time and
 *    announces that once (it was never announced before).
 *
 * The clock is wall-clock time checked once a second; ticks are never counted.
 *
 * Purchases cost Money (EconomyManager) only, never Credits. The price comes from the opening's
 * persisted stock snapshot, never the GUI. Pool edits only affect later openings: the running one
 * keeps the snapshot it opened with, except that a candy no longer registered as a candy can't be
 * bought.
 */
class CandyShopManager(private val plugin: Joshymc) {

    companion object {
        val CANDY_PINK: TextColor = TextColor.color(0xFF6EC7)
        private val GUI_TITLE: Component = Component.text("Candy Man's Shop", CANDY_PINK).decoration(TextDecoration.BOLD, true)
        private const val GUI_SIZE = 27
        private val CANDY_SLOTS = intArrayOf(11, 13, 15)
        private const val INFO_SLOT = 4
        private const val BALANCE_SLOT = 18
        const val ADMIN_PERMISSION = "joshymc.candy.admin"

        /** An opening found running later than this after its start announces the time left instead. */
        private val LATE_AFTER: Duration = Duration.ofSeconds(60)
        private const val TICK_PERIOD = 20L

        /** Highest price an admin may set; far beyond any real price, well inside a double's exact range. */
        const val MAX_PRICE = 1_000_000_000_000.0
    }

    data class Active(val opening: CandyShopStore.Opening, val stock: List<StockItem>)

    private data class View(val gui: CustomGui, val openingId: Int)

    val store = CandyShopStore(plugin.databaseManager, { sql, params -> plugin.databaseManager.insertReturningId(sql, *params) })

    var zone: ZoneId = TimezoneManager.DEFAULT_ZONE
        private set
    var mode: CandySelection.Mode = CandySelection.Mode.NORMALIZE
        private set

    /** The running opening and its stock, or null while the shop is closed. */
    @Volatile var active: Active? = null
        private set

    private var cache: List<CandyShopStore.Opening> = emptyList()
    private var lastEnsuredDay: LocalDate? = null
    private var lastRefresh: Instant = Instant.EPOCH
    private var task: BukkitTask? = null
    private val viewers = HashMap<UUID, View>()
    private val purchasing = HashSet<UUID>()

    /** False while `features.candy-shop` is off (start() never ran, so the tables may not exist). */
    val isRunning: Boolean get() = task != null

    // ── Lifecycle ────────────────────────────────────────

    fun start() {
        stop()
        loadConfig()
        store.createTables()
        recoverAfterDowntime(Instant.now())
        for ((id, name, item) in store.unsettledPurchases()) {
            plugin.logger.warning("[CandyShop] Purchase #$id ($name, $item) was paid but its delivery was never confirmed (server stopped mid-purchase). Check it by hand.")
        }
        tick()
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            try {
                tick()
            } catch (e: Exception) {
                plugin.logger.severe("[CandyShop] Tick failed: ${e.message}")
            }
        }, TICK_PERIOD, TICK_PERIOD)
        plugin.logger.info("[CandyShop] Started (zone ${zone.id}, ${mode.name.lowercase()} chances).")
    }

    /** Cancels the clock and closes open shop menus. The DB keeps the state, so start() resumes it. */
    fun stop() {
        task?.cancel()
        task = null
        closeViewers(null)
        active = null
        lastEnsuredDay = null
    }

    private fun loadConfig() {
        val zoneName = plugin.config.getString("candy-shop.timezone", "")?.trim().orEmpty()
        zone = if (zoneName.isEmpty()) TimezoneManager.DEFAULT_ZONE else runCatching { ZoneId.of(zoneName) }.getOrElse {
            plugin.logger.warning("[CandyShop] Unknown candy-shop.timezone '$zoneName'; using ${TimezoneManager.DEFAULT_ZONE.id}.")
            TimezoneManager.DEFAULT_ZONE
        }
        val modeName = plugin.config.getString("candy-shop.probability-mode", "normalize")?.trim()?.uppercase().orEmpty()
        mode = runCatching { CandySelection.Mode.valueOf(modeName) }.getOrElse {
            plugin.logger.warning("[CandyShop] Unknown candy-shop.probability-mode '$modeName'; using normalize.")
            CandySelection.Mode.NORMALIZE
        }
    }

    /** Settles every window that ended while the server was offline, without announcing anything. */
    private fun recoverAfterDowntime(now: Instant) {
        for (o in store.unfinishedBefore(now)) {
            when (o.status) {
                OpeningStatus.OPEN -> if (store.markClosed(o.id, "closed while the server was offline")) {
                    plugin.logger.info("[CandyShop] Opening #${o.id} ended while the server was offline; closed it quietly.")
                }
                OpeningStatus.SCHEDULED -> if (store.markMissed(o.id)) {
                    plugin.logger.info("[CandyShop] Opening #${o.id} (${o.start}) was missed during downtime; not replayed.")
                }
                else -> {}
            }
        }
        refresh(now)
        val running = cache.firstOrNull { it.status == OpeningStatus.OPEN && it.isActiveAt(now) } ?: return
        val stock = store.stockFor(running.id)
        if (stock.size != CandySelection.SLOTS) {
            store.markClosed(running.id, "stock missing on restart")
            plugin.logger.severe("[CandyShop] Opening #${running.id} had ${stock.size} stock rows instead of ${CandySelection.SLOTS}; closed it.")
            refresh(now)
            return
        }
        active = Active(running, stock)
        plugin.logger.info("[CandyShop] Resumed opening #${running.id} until ${running.end} with its saved stock.")
    }

    private fun refresh(now: Instant) {
        cache = store.openingsBetween(now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(2)))
        lastRefresh = now
    }

    // ── Schedule ─────────────────────────────────────────

    /** Generates [day]'s four openings if they don't exist yet. */
    private fun ensureDay(day: LocalDate) {
        if (store.openingsForDay(day).isNotEmpty()) return
        val dayStart = day.atStartOfDay(zone).toInstant()
        val nextDayStart = day.plusDays(1).atStartOfDay(zone).toInstant()
        val previous = store.lastStartBefore(dayStart)
        val next = store.firstStartAtOrAfter(nextDayStart)
        val starts = CandySchedule.generate(day, zone, previous, next)
        if (starts == null) {
            plugin.logger.severe("[CandyShop] No valid schedule exists for $day (previous $previous, next $next).")
            return
        }
        store.insertDay(day, zone, starts)
        plugin.logger.info("[CandyShop] Scheduled $day: ${starts.joinToString { formatClock(it) }} (${zone.id}).")
    }

    private fun tick() {
        val now = Instant.now()
        val today = LocalDate.ofInstant(now, zone)
        if (today != lastEnsuredDay) {
            ensureDay(today)
            ensureDay(today.plusDays(1))
            lastEnsuredDay = today
            refresh(now)
        } else if (Duration.between(lastRefresh, now).toMinutes() >= 10) {
            refresh(now)
        }

        active?.let { if (!now.isBefore(it.opening.end)) close(it) }

        for (o in cache) {
            if (o.status != OpeningStatus.SCHEDULED) continue
            if (!now.isBefore(o.end)) {
                // Only reachable if the server froze through a whole window; never replayed.
                store.markMissed(o.id)
                refresh(now)
                break
            }
            if (!now.isBefore(o.start) && active == null) {
                open(o, now)
                break
            }
        }
        if (viewers.isNotEmpty()) updateTimers(now)
    }

    private fun open(o: CandyShopStore.Opening, now: Instant) {
        val pool = eligiblePool()
        val plan = CandySelection.plan(pool.entries.map { CandySelection.Candidate(it.id, it.chancePercent) }, mode)
        if (!plan.feasible) {
            val reason = (plan.problems + pool.warnings).joinToString(" ")
            if (store.markSkipped(o.id, reason)) {
                plugin.logger.warning("[CandyShop] Opening #${o.id} skipped: $reason")
                notifyAdmins("The Candy Shop could not open: $reason Fix the pool with /candy admin list.")
            }
            refresh(now)
            return
        }
        pool.warnings.forEach { plugin.logger.warning("[CandyShop] $it") }

        val byId = pool.entries.associateBy { it.id }
        val stock = CandySelection.sample(plan).mapIndexed { position, id ->
            val e = byId.getValue(id)
            StockItem(o.id, position, e.id, e.itemId, e.price, e.chancePercent, plan.inclusion.getValue(id) * 100.0)
        }
        val note = "mode=${mode.name.lowercase()} configured-total=${"%.2f".format(plan.configuredTotalPercent)}%" +
            if (plan.adjusted) " (normalized to 300%)" else ""
        if (!store.markOpen(o.id, stock, note)) {
            refresh(now)
            return
        }
        active = Active(o.copy(status = OpeningStatus.OPEN), stock)
        refresh(now)
        plugin.logger.info("[CandyShop] Opening #${o.id} is open until ${formatClock(o.end)}: ${stock.joinToString { "${it.itemId} (#${it.entryId}, ${it.price})" }}.")

        val late = Duration.between(o.start, now) > LATE_AFTER
        if (late) {
            val minutes = Duration.between(now, o.end).toMinutes().coerceAtLeast(1)
            announce(message("resumed", "{minutes}" to minutes.toString()), withLink = true)
        } else {
            announce(message("open"), withLink = true)
        }
    }

    private fun close(a: Active) {
        active = null
        val closed = store.markClosed(a.opening.id)
        closeViewers(a.opening.id)
        refresh(Instant.now())
        if (closed) {
            plugin.logger.info("[CandyShop] Opening #${a.opening.id} closed.")
            announce(message("close"), withLink = false)
        }
    }

    // ── Pool ─────────────────────────────────────────────

    data class Pool(val entries: List<CandyShopStore.PoolEntry>, val warnings: List<String>)

    /** Enabled entries that are registered candies, one per item id. */
    fun eligiblePool(): Pool {
        val warnings = mutableListOf<String>()
        val seen = HashSet<String>()
        val entries = store.listEntries().filter { e ->
            when {
                !e.enabled -> false
                candyItem(e.itemId) == null -> {
                    warnings += "Entry #${e.id} (${e.itemId}) is not a registered candy and is left out."
                    false
                }
                !seen.add(e.itemId) -> {
                    warnings += "Entry #${e.id} repeats ${e.itemId} and is left out."
                    false
                }
                else -> true
            }
        }
        return Pool(entries, warnings)
    }

    /** The registered candy with [itemId], or null if it isn't one (removed, or not a candy). */
    fun candyItem(itemId: String): CustomItem? = plugin.itemManager.getItem(itemId)?.takeIf { it.isCandy }

    // ── Player side ──────────────────────────────────────

    /** `/candy` and `/trickortreat`: the one shop menu, or when it's closed, when it comes back. */
    fun openShop(player: Player) {
        if (plugin.eventManager.isInEventWorld(player)) {
            plugin.commsManager.send(player, Component.text("The Candy Shop can't be used in the event world. Use /spawn to leave first.", NamedTextColor.RED))
            return
        }
        val now = Instant.now()
        val a = active?.takeIf { it.opening.isActiveAt(now) }
        if (a == null) {
            plugin.commsManager.send(player, message("closed-info"))
            val next = store.nextScheduled(now)
            if (next != null) {
                plugin.commsManager.send(player, Component.text("Next visit in ", NamedTextColor.GRAY)
                    .append(Component.text(formatDuration(Duration.between(now, next.start)), CANDY_PINK))
                    .append(Component.text(".", NamedTextColor.GRAY)))
            }
            return
        }

        val gui = CustomGui(GUI_TITLE, GUI_SIZE)
        for (slot in 0 until GUI_SIZE) {
            gui.inventory.setItem(slot, GuiStyle.filler(if (slot % 2 == 0) Material.PINK_STAINED_GLASS_PANE else Material.MAGENTA_STAINED_GLASS_PANE))
        }
        gui.setItem(INFO_SLOT, infoItem(a, now))
        gui.setItem(BALANCE_SLOT, balanceItem(player))
        for (stock in a.stock) {
            val slot = CANDY_SLOTS.getOrNull(stock.position) ?: continue
            val display = displayItem(stock) ?: continue
            gui.setItem(slot, display) { p, _ -> purchase(p, a.opening.id, stock.position, stock.entryId) }
        }
        gui.setItem(GuiStyle.closeSlot(GUI_SIZE), GuiStyle.close()) { p, _ -> p.closeInventory() }
        gui.onClose = { p -> viewers.remove(p.uniqueId)?.let { if (it.gui !== gui) viewers[p.uniqueId] = it } }

        viewers[player.uniqueId] = View(gui, a.opening.id)
        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f)
    }

    private fun displayItem(stock: StockItem): ItemStack? {
        val def = candyItem(stock.itemId) ?: return GuiStyle.item(
            Material.BARRIER, Component.text("Unavailable", NamedTextColor.RED),
            listOf(Component.text("This candy can't be sold right now.", NamedTextColor.GRAY)),
        )
        val stack = def.createItemStack(1)
        stack.editMeta { meta ->
            val lore = (meta.lore() ?: emptyList()).toMutableList()
            lore += Component.empty()
            lore += Component.text("Price: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.economyManager.format(stock.price), NamedTextColor.GOLD))
                .decoration(TextDecoration.ITALIC, false)
            lore += Component.text("Click to Purchase", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
            meta.lore(lore)
        }
        return stack
    }

    private fun infoItem(a: Active, now: Instant): ItemStack = GuiStyle.item(
        Material.CLOCK,
        Component.text("Candy Man's Shop", CANDY_PINK).decoration(TextDecoration.BOLD, true),
        listOf(
            Component.text("Closes in ", NamedTextColor.GRAY)
                .append(Component.text(formatDuration(Duration.between(now, a.opening.end)), NamedTextColor.YELLOW)),
            Component.empty(),
            Component.text("Each click buys one candy.", NamedTextColor.GRAY),
            Component.text("Paid with Money.", NamedTextColor.GRAY),
        ),
    )

    private fun balanceItem(player: Player): ItemStack = GuiStyle.item(
        Material.SUNFLOWER,
        Component.text("Your Money", NamedTextColor.GOLD),
        listOf(Component.text(plugin.economyManager.format(plugin.economyManager.getBalance(player.uniqueId)), NamedTextColor.YELLOW)),
    )

    private fun updateTimers(now: Instant) {
        val a = active ?: return
        for (view in viewers.values) {
            if (view.openingId == a.opening.id) view.gui.inventory.setItem(INFO_SLOT, infoItem(a, now))
        }
    }

    /** Closes the shop menus of [openingId] (every shop menu when null), telling viewers why. */
    private fun closeViewers(openingId: Int?) {
        for ((uuid, view) in viewers.toMap()) {
            if (openingId != null && view.openingId != openingId) continue
            viewers.remove(uuid)
            val player = Bukkit.getPlayer(uuid) ?: continue
            if (plugin.guiManager.getOpenGui(player) === view.gui) {
                player.closeInventory()
                if (openingId != null) plugin.commsManager.send(player, Component.text("The Candy Shop just closed.", NamedTextColor.RED))
            }
        }
    }

    /**
     * Buys one candy. Every check runs against the server's own state at click time: the opening
     * must be the running one and inside its window, the slot must still hold the entry the menu
     * showed, and the price is the stock snapshot's. Money is taken (atomic conditional UPDATE)
     * and the purchase recorded in one transaction; then the candy is delivered, and if it can't
     * be stored after all the money goes straight back. A full inventory is refused before any
     * money moves.
     */
    fun purchase(player: Player, openingId: Int, position: Int, entryId: Int) {
        val uuid = player.uniqueId
        if (plugin.eventManager.isInEventWorld(player)) {
            player.closeInventory()
            return
        }
        val now = Instant.now()
        val a = active
        if (a == null || a.opening.id != openingId || !a.opening.isActiveAt(now)) {
            player.closeInventory()
            reject(player, "The Candy Shop has closed. That candy is no longer for sale.")
            return
        }
        val stock = a.stock.firstOrNull { it.position == position && it.entryId == entryId }
        if (stock == null) {
            player.closeInventory()
            reject(player, "That candy is no longer for sale.")
            return
        }
        val def = candyItem(stock.itemId)
        if (def == null) {
            reject(player, "That candy can't be sold right now.")
            return
        }
        val candy = def.createItemStack(1)
        if (!plugin.modModeManager.isModMode(player) && !hasRoom(player, candy)) {
            reject(player, "Your inventory is full. Make room before buying candy.")
            return
        }
        if (!purchasing.add(uuid)) return
        try {
            var purchaseId: Int? = null
            try {
                plugin.databaseManager.transaction {
                    if (plugin.economyManager.withdraw(uuid, stock.price)) {
                        purchaseId = store.insertPurchase(stock, uuid, player.name)
                    }
                }
            } catch (e: Exception) {
                plugin.logger.severe("[CandyShop] Purchase by ${player.name} failed before delivery (nothing charged): ${e.message}")
                reject(player, "Something went wrong. You were not charged.")
                return
            }
            val id = purchaseId
            if (id == null) {
                reject(player, "You need ${plugin.economyManager.format(stock.price)} to buy that candy.")
                return
            }

            val leftover = plugin.depositItemSafely(player, candy)
            if (leftover == null || leftover.amount <= 0) {
                runCatching { store.settlePurchase(id, PurchaseStatus.DELIVERED) }.onFailure {
                    plugin.logger.severe("[CandyShop] Purchase #$id was delivered but couldn't be marked delivered: ${it.message}")
                }
                plugin.logger.info("[CandyShop] ${player.name} ($uuid) bought ${stock.itemId} for ${stock.price} (purchase #$id, opening #$openingId).")
                plugin.commsManager.send(player, Component.text("You bought ", NamedTextColor.GREEN)
                    .append(def.displayName)
                    .append(Component.text(" for ", NamedTextColor.GREEN))
                    .append(Component.text(plugin.economyManager.format(stock.price), NamedTextColor.GOLD))
                    .append(Component.text("!", NamedTextColor.GREEN)))
                GuiStyle.successSound(player)
            } else {
                refund(player, id, stock)
            }
            viewers[uuid]?.takeIf { it.openingId == openingId }?.gui?.inventory?.setItem(BALANCE_SLOT, balanceItem(player))
        } finally {
            purchasing.remove(uuid)
        }
    }

    private fun refund(player: Player, purchaseId: Int, stock: StockItem) {
        try {
            plugin.databaseManager.transaction {
                if (store.settlePurchase(purchaseId, PurchaseStatus.REFUNDED, "candy could not be stored")) {
                    plugin.economyManager.deposit(player.uniqueId, stock.price)
                }
            }
            plugin.logger.warning("[CandyShop] Purchase #$purchaseId by ${player.name} couldn't be delivered; refunded ${stock.price}.")
            reject(player, "Your candy couldn't be stored, so your ${plugin.economyManager.format(stock.price)} was refunded.")
        } catch (e: Exception) {
            plugin.logger.severe("[CandyShop] Purchase #$purchaseId by ${player.name} (${player.uniqueId}) was not delivered and the refund of ${stock.price} failed: ${e.message}. Refund it by hand.")
            reject(player, "Your candy couldn't be stored. Staff have been told to refund you.")
        }
    }

    private fun hasRoom(player: Player, item: ItemStack): Boolean =
        player.inventory.storageContents.any { it == null || it.type.isAir || (it.isSimilar(item) && it.amount < it.maxStackSize) }

    private fun reject(player: Player, text: String) {
        plugin.commsManager.send(player, Component.text(text, NamedTextColor.RED))
        GuiStyle.errorSound(player)
    }

    // ── Messages ─────────────────────────────────────────

    private val defaults = mapOf(
        "open" to "&d&l🍬 Candy Man&d: &f\"I've arrived with some sweet surprises! The Candy Shop is now &a&lOPEN&f for 30 minutes! Use &d/candy&f to see what's for sale!\"",
        "resumed" to "&d&l🍬 Candy Man&d: &f\"I've arrived with some sweet surprises! The Candy Shop is &a&lOPEN&f for {minutes} more minutes! Use &d/candy&f to see what's for sale!\"",
        "close" to "&d&l🍬 Candy Man&d: &f\"That's all for now! The Candy Shop is &c&lCLOSED&f. I'll be back with more treats!\"",
        "closed-info" to "&dThe Candy Man isn't around right now! Come back when the Candy Shop opens.",
    )

    private fun message(key: String, vararg replacements: Pair<String, String>): Component {
        var raw = plugin.config.getString("candy-shop.messages.$key")?.takeIf { it.isNotBlank() } ?: defaults.getValue(key)
        for ((from, to) in replacements) raw = raw.replace(from, to)
        return plugin.commsManager.parseLegacy(raw)
    }

    private fun announce(message: Component, withLink: Boolean) {
        val line = if (!withLink) message else message.append(Component.text(" "))
            .append(Component.text("[Open Shop]", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true)
                .clickEvent(ClickEvent.runCommand("/candy"))
                .hoverEvent(HoverEvent.showText(Component.text("Click to open the Candy Shop", NamedTextColor.GRAY))))
        for (p in Bukkit.getOnlinePlayers()) plugin.commsManager.sendRaw(p, line)
        Bukkit.getConsoleSender().sendMessage(message)
    }

    private fun notifyAdmins(text: String) {
        val msg = Component.text("[Candy Shop] ", CANDY_PINK).append(Component.text(text, NamedTextColor.RED))
        for (p in Bukkit.getOnlinePlayers()) if (p.hasPermission(ADMIN_PERMISSION)) plugin.commsManager.sendRaw(p, msg)
    }

    // ── Formatting ───────────────────────────────────────

    fun formatClock(instant: Instant): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(instant.atZone(zone))

    fun formatDuration(d: Duration): String {
        val total = d.seconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    /** Today's (and any still-running yesterday's) openings in order, for `/candy admin schedule`. */
    fun scheduleForAdmin(now: Instant = Instant.now()): List<CandyShopStore.Opening> {
        val today = LocalDate.ofInstant(now, zone)
        return (store.openingsForDay(today.minusDays(1)).filter { it.isActiveAt(now) } + store.openingsForDay(today))
            .distinctBy { it.id }
    }
}
