package com.liam.joshymc.eventshop

import com.liam.joshymc.Joshymc
import com.liam.joshymc.eventshop.EventShopStore.ItemKind
import com.liam.joshymc.eventshop.EventShopStore.PurchaseStatus
import com.liam.joshymc.eventshop.EventShopStore.Rotation
import com.liam.joshymc.eventshop.EventShopStore.RotationItem
import com.liam.joshymc.eventshop.EventShopStore.RotationKind
import com.liam.joshymc.eventshop.EventShopStore.RotationStatus
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
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
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlin.random.asKotlinRandom

/**
 * The Survival Event Shop (issue #1299), opened with `/eventshop` or `/eshop`.
 *
 * Six windows a day at the configured :00/:30 times ([EventShopSchedule], default every four
 * hours from midnight, America/New_York), each exactly two hours. When a window starts, one
 * rotation of up to seven distinct listings is picked ([EventShopSelection]) and persisted with a
 * snapshot of every item, price and limit ([EventShopStore]); all players see that rotation until
 * the window's wall-clock end. The clock is checked once a second; ticks are never counted, and
 * every purchase re-checks the deadline itself, so a lagging server can't stretch a window.
 *
 * Restarts: an OPEN rotation still inside its window resumes with its saved items (unannounced);
 * one whose window ended while offline is CLOSED quietly; a window that started while the server
 * was down opens late, for the remaining time only (its end never moves). A window that already
 * has a rotation row (opened, closed by staff, skipped) never gets another, so restarting can't
 * reroll the shop or add a seventh opening.
 *
 * Survival only: everything player-facing requires the player's gamemode
 * (`gamemodeLockManager.gamemodeOf`) to be Survival, and announcements only go to Survival players.
 *
 * Purchases cost Money (EconomyManager), taken with the PAID purchase row in one transaction that
 * also enforces the per-player and stock limits. The item is rebuilt from the rotation snapshot
 * (a registered custom item from its definition, anything else from its serialized stack), and
 * if nothing could be delivered the money goes straight back.
 */
class EventShopManager(private val plugin: Joshymc) {

    companion object {
        val EVENT_GOLD: TextColor = TextColor.color(0xFFB84D)
        val EVENT_ACCENT: TextColor = TextColor.color(0xFFD966)
        val GUI_TITLE: Component = Component.text("JOSHYMC | EVENT SHOP", EVENT_GOLD).decoration(TextDecoration.BOLD, true)
        private val CONFIRM_TITLE: Component = Component.text("Confirm Purchase", EVENT_GOLD).decoration(TextDecoration.BOLD, true)
        private const val GUI_SIZE = 27
        /** The seven item slots: the middle row, columns 1..7. */
        val ITEM_SLOTS = intArrayOf(10, 11, 12, 13, 14, 15, 16)
        private const val INFO_SLOT = 4
        private const val BALANCE_SLOT = 18
        private const val CONFIRM_ITEM_SLOT = 13
        private const val CONFIRM_CANCEL_SLOT = 11
        private const val CONFIRM_BUY_SLOT = 15

        const val USE_PERMISSION = "joshymc.eventshop.use"
        const val ADMIN_PERMISSION = "joshymc.eventshop.admin"
        val SCOPE = GamemodeScope.SURVIVAL

        /** A window opened later than this after its start announces the time left instead. */
        private val LATE_AFTER: Duration = Duration.ofSeconds(60)
        private const val TICK_PERIOD = 20L

        /** Highest price an admin may set; far beyond any real price, well inside a double's exact range. */
        const val MAX_PRICE = 1_000_000_000_000.0
        /** Most items one purchase may hand out (a full 36-slot inventory of 64-stacks). */
        const val MAX_QUANTITY = 36 * 64
        const val MAX_WEIGHT = 1000
        const val MAX_LIMIT = 1_000_000
        const val MAX_NAME_LENGTH = 64
        /** Shortest manual opening, and the latest one may start before the next scheduled window. */
        val MIN_MANUAL: Duration = Duration.ofMinutes(1)

        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a v", Locale.US)
    }

    data class Active(val rotation: Rotation, val items: List<RotationItem>)

    /** What a player has open: the shop page or a confirmation, both tied to one rotation. */
    private data class View(val gui: CustomGui, val rotationId: Int, val isShop: Boolean)

    val store = EventShopStore(plugin.databaseManager, { sql, params -> plugin.databaseManager.insertReturningId(sql, *params) })

    var zone: ZoneId = TimezoneManager.DEFAULT_ZONE
        private set
    /** The parsed schedule; when it isn't valid, no scheduled window opens (manual openings still work). */
    var schedule: EventShopSchedule.Parsed = EventShopSchedule.parse(EventShopSchedule.DEFAULT_TIMES)
        private set

    /** The running rotation and its items, or null while the shop is closed. */
    @Volatile var active: Active? = null
        private set

    private var task: BukkitTask? = null
    private val viewers = HashMap<UUID, View>()
    private val purchasing = HashSet<UUID>()
    private val random = SecureRandom().asKotlinRandom()

    /** False while `features.event-shop` is off (start() never ran, so the tables may not exist). */
    val isRunning: Boolean get() = task != null

    val scheduleTimes: List<LocalTime> get() = if (schedule.valid) schedule.times else emptyList()

    // ── Lifecycle ────────────────────────────────────────

    fun start() {
        stop()
        loadConfig()
        store.createTables()
        recoverAfterRestart(Instant.now())
        for ((txn, name, listing) in store.unsettledPurchases()) {
            plugin.logger.warning("[EventShop] Purchase $txn ($name, listing #$listing) was paid but its delivery was never confirmed (server stopped mid-purchase). Check it by hand.")
        }
        startClock()
        plugin.logger.info("[EventShop] Started (zone ${zone.id}, schedule ${describeSchedule()}).")
    }

    /** Cancels the clock and closes open shop menus. The DB keeps the state, so start() resumes it. */
    fun stop() {
        task?.cancel()
        task = null
        closeViewers(null, null)
        active = null
    }

    private fun loadConfig() {
        val zoneName = plugin.config.getString("event-shop.timezone", "")?.trim().orEmpty()
        zone = if (zoneName.isEmpty()) TimezoneManager.DEFAULT_ZONE else runCatching { ZoneId.of(zoneName) }.getOrElse {
            plugin.logger.warning("[EventShop] Unknown event-shop.timezone '$zoneName'; using ${TimezoneManager.DEFAULT_ZONE.id}.")
            TimezoneManager.DEFAULT_ZONE
        }
        val raw = if (plugin.config.isList("event-shop.schedule")) plugin.config.getStringList("event-shop.schedule") else EventShopSchedule.DEFAULT_TIMES
        schedule = EventShopSchedule.parse(raw)
        if (!schedule.valid) {
            plugin.logger.severe("[EventShop] event-shop.schedule is invalid, so no scheduled openings will happen until it is fixed: ${schedule.problems.joinToString("; ")}.")
            notifyAdmins("event-shop.schedule is invalid, so the Event Shop won't open on its own: ${schedule.problems.joinToString("; ")}.")
        }
    }

    /** Settles every rotation that ended while the server was offline, without announcing anything, and resumes a running one. */
    private fun recoverAfterRestart(now: Instant) {
        for (r in store.expiredOpen(now)) {
            if (store.finishRotation(r.id, RotationStatus.CLOSED, null, "ended while the server was offline")) {
                plugin.logger.info("[EventShop] Rotation #${r.id} ended while the server was offline; closed it quietly.")
            }
        }
        val running = store.openAt(now)
        // Only one rotation is ever open; anything else (impossible unless the DB was edited) is closed.
        for (extra in running.drop(1)) store.finishRotation(extra.id, RotationStatus.CLOSED, null, "another rotation was open")
        val r = running.firstOrNull() ?: return
        val items = store.itemsFor(r.id)
        if (items.isEmpty()) {
            store.finishRotation(r.id, RotationStatus.CLOSED, null, "items missing on restart")
            plugin.logger.severe("[EventShop] Rotation #${r.id} had no saved items on restart; closed it.")
            return
        }
        active = Active(r, items)
        plugin.logger.info("[EventShop] Resumed rotation #${r.id} until ${r.end} with its saved items.")
    }

    private fun tick() {
        val now = Instant.now()
        active?.let { if (!now.isBefore(it.rotation.end)) close(it, null, null) }

        val window = if (schedule.valid) EventShopSchedule.windowAt(now, zone, schedule.times) else null
        if (window != null && store.rotationByKey(window.key) == null) {
            val current = active
            when {
                current == null -> openScheduled(window, now)
                // A manual opening (or its reroll) never holds back a scheduled window.
                isManual(current.rotation) -> {
                    close(current, null, "a scheduled window started")
                    openScheduled(window, now, checkOverlap = false)
                }
                // A rotation from an old schedule is still running: this window is skipped, once.
                else -> recordSkipped(window, "another rotation (#${current.rotation.id}) was running when it started")
            }
        }
        if (viewers.isNotEmpty()) updateTimers(now)
    }

    // ── Rotations ────────────────────────────────────────

    private fun isManual(r: Rotation): Boolean =
        r.kind == RotationKind.MANUAL || (r.kind == RotationKind.REROLL && store.rotation(r.windowId)?.kind == RotationKind.MANUAL)

    /**
     * Opens [window]. With [checkOverlap], a window that started while another rotation was still
     * offering items (one from before a schedule change, found after a restart) is skipped instead.
     */
    private fun openScheduled(window: EventShopSchedule.Window, now: Instant, checkOverlap: Boolean = true) {
        if (checkOverlap && store.anyOfferedAt(window.start)) {
            recordSkipped(window, "another rotation was running when it started")
            return
        }
        val opened = openRotation(
            EventShopStore.NewRotation(window.key, null, RotationKind.SCHEDULED, window.day.toString(), zone.id, window.start, window.end, 0, null),
            null, "Schedule",
        ) ?: return
        val late = Duration.between(window.start, now) > LATE_AFTER
        announceOpen(opened, if (late) Duration.between(now, window.end) else EventShopSchedule.WINDOW)
    }

    private fun recordSkipped(window: EventShopSchedule.Window, reason: String) {
        val id = store.createRotation(
            EventShopStore.NewRotation(window.key, null, RotationKind.SCHEDULED, window.day.toString(), zone.id, window.start, window.end, 0, reason),
            RotationStatus.SKIPPED, emptyList(), null, "Schedule",
        )
        if (id != null) plugin.logger.warning("[EventShop] Scheduled window ${window.key} skipped: $reason.")
    }

    /**
     * Picks the listings and stores the rotation (OPEN), or SKIPPED when no listing is eligible.
     * Returns the new active rotation, or null when nothing opened (already existed, or skipped).
     */
    private fun openRotation(base: EventShopStore.NewRotation, actorUuid: UUID?, actorName: String): Active? {
        val pool = eligiblePool()
        pool.warnings.forEach { plugin.logger.warning("[EventShop] $it") }
        val byId = pool.listings.associateBy { it.id }
        val picked = EventShopSelection.pick(pool.listings.map { EventShopSelection.Candidate(it.id, it.weight) }, EventShopSelection.SLOTS, random)
        if (picked.isEmpty()) {
            val reason = "no enabled, valid listings in the pool"
            val id = store.createRotation(base.copy(note = reason), RotationStatus.SKIPPED, emptyList(), actorUuid, actorName)
            if (id != null) {
                plugin.logger.warning("[EventShop] Rotation #$id skipped: $reason.")
                notifyAdmins("The Event Shop could not open: $reason. Add items with /eventshop admin add <price>.")
            }
            return null
        }
        val underfilled = picked.size < EventShopSelection.SLOTS
        val note = listOfNotNull(base.note, if (underfilled) "underfilled: ${picked.size}/${EventShopSelection.SLOTS} eligible listings" else null)
            .joinToString("; ").ifEmpty { null }
        val draft = picked.mapIndexed { position, listingId ->
            val l = byId.getValue(listingId)
            RotationItem(0, position, l.id, l.displayName, l.kind, l.customItemId, l.itemData, l.quantity, l.price, l.currency, l.perPlayerLimit, l.stockLimit)
        }
        val id = store.createRotation(base.copy(eligibleCount = pool.listings.size, note = note), RotationStatus.OPEN, draft, actorUuid, actorName)
            ?: return null
        val rotation = store.rotation(id) ?: return null
        val a = Active(rotation, draft.map { it.copy(rotationId = id) })
        active = a
        plugin.logger.info("[EventShop] Rotation #$id (${rotation.kind.name.lowercase()}) is open until ${formatClock(rotation.end)}: " +
            a.items.joinToString { "#${it.listingId} ${it.displayName} (${it.price})" } + ".")
        if (underfilled) {
            notifyAdmins("The Event Shop pool is underfilled: only ${picked.size} of ${EventShopSelection.SLOTS} slots could be filled this rotation. Add or enable more listings.")
        }
        return a
    }

    private fun close(a: Active, by: CommandSender?, note: String?) {
        if (active?.rotation?.id == a.rotation.id) active = null
        val closed = store.finishRotation(a.rotation.id, RotationStatus.CLOSED, by?.name, note)
        closeViewers(a.rotation.id, "The Event Shop just closed.")
        if (closed) {
            plugin.logger.info("[EventShop] Rotation #${a.rotation.id} closed" + (by?.let { " by ${it.name}" } ?: "") + ".")
            if (by != null) store.audit((by as? Player)?.uniqueId, by.name, "CLOSE", null, a.rotation.id, note ?: "manual close")
            announce(message("close"), withLink = false)
        }
    }

    // ── Admin overrides ──────────────────────────────────

    sealed interface OverrideResult {
        data class Ok(val active: Active) : OverrideResult
        data class Refused(val reason: String) : OverrideResult
    }

    /**
     * `/eventshop admin open [minutes]`: opens a MANUAL rotation now for [length] (default two
     * hours), ending no later than the next scheduled window so that window opens normally.
     */
    fun openManually(sender: CommandSender, length: Duration = EventShopSchedule.WINDOW): OverrideResult {
        val now = Instant.now()
        if (active?.rotation?.isActiveAt(now) == true) return OverrideResult.Refused("The Event Shop is already open. Use /eventshop admin rotate to replace its items.")
        var end = now.plus(length)
        val nextWindow = if (schedule.valid) EventShopSchedule.nextWindowAfter(now, zone, schedule.times) else null
        if (nextWindow != null && nextWindow.start.isBefore(end)) end = nextWindow.start
        if (Duration.between(now, end) < MIN_MANUAL) return OverrideResult.Refused("The next scheduled opening starts in under a minute.")
        val actorUuid = (sender as? Player)?.uniqueId
        val a = openRotation(
            EventShopStore.NewRotation(null, null, RotationKind.MANUAL, null, zone.id, now, end, 0, "opened by ${sender.name}"),
            actorUuid, sender.name,
        ) ?: return OverrideResult.Refused("No enabled, valid listings are in the pool, so there is nothing to sell.")
        plugin.logger.info("[EventShop] ${sender.name} opened the Event Shop manually until ${formatClock(end)} (rotation #${a.rotation.id}).")
        announceOpen(a, Duration.between(now, end))
        return OverrideResult.Ok(a)
    }

    /** `/eventshop admin close`: closes the running rotation early. A scheduled window stays closed for the rest of its time. */
    fun closeManually(sender: CommandSender): OverrideResult {
        val a = active ?: return OverrideResult.Refused("The Event Shop isn't open.")
        close(a, sender, "closed by ${sender.name}")
        return OverrideResult.Ok(a)
    }

    /**
     * `/eventshop admin rotate`: replaces the running rotation's items with a fresh pick until the
     * same end. The old rotation becomes REPLACED (its purchases untouched), and limits still count
     * purchases made before the reroll, since they belong to the same window.
     */
    fun reroll(sender: CommandSender): OverrideResult {
        val now = Instant.now()
        val old = active?.takeIf { it.rotation.isActiveAt(now) }
            ?: return OverrideResult.Refused("The Event Shop isn't open. Use /eventshop admin open to start a rotation.")
        if (eligiblePool().listings.isEmpty()) return OverrideResult.Refused("No enabled, valid listings are in the pool.")
        if (!store.finishRotation(old.rotation.id, RotationStatus.REPLACED, sender.name, "rerolled by ${sender.name}")) {
            return OverrideResult.Refused("That rotation already ended.")
        }
        active = null
        closeViewers(old.rotation.id, "The Event Shop's items were just changed. Open it again to see them.")
        val a = openRotation(
            EventShopStore.NewRotation(null, old.rotation.windowId, RotationKind.REROLL, null, zone.id, now, old.rotation.end, 0,
                "rerolled by ${sender.name}", replacesId = old.rotation.id),
            (sender as? Player)?.uniqueId, sender.name,
        ) ?: return OverrideResult.Refused("The new rotation could not be created; the shop is now closed.")
        plugin.logger.info("[EventShop] ${sender.name} rerolled rotation #${old.rotation.id} into #${a.rotation.id}.")
        return OverrideResult.Ok(a)
    }

    /** `/eventshop admin reload`: rereads the timezone and schedule. The running rotation is kept. */
    fun reload(): EventShopSchedule.Parsed {
        val keep = active
        task?.cancel()
        task = null
        loadConfig()
        active = keep
        startClock()
        return schedule
    }

    /** Runs [tick] now and then once a second. Callers cancel any previous task first, so there is only ever one. */
    private fun startClock() {
        tick()
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            try {
                tick()
            } catch (e: Exception) {
                plugin.logger.severe("[EventShop] Tick failed: ${e.message}")
            }
        }, TICK_PERIOD, TICK_PERIOD)
    }

    // ── Pool ─────────────────────────────────────────────

    data class Pool(val listings: List<EventShopStore.Listing>, val warnings: List<String>)

    /** Enabled listings whose item can still be built (and whose custom item, if any, is still registered). */
    fun eligiblePool(): Pool {
        val warnings = mutableListOf<String>()
        val listings = store.listListings().filter { l ->
            if (!l.enabled) return@filter false
            val problem = problemOf(l) ?: return@filter true
            warnings += "Listing #${l.id} (${l.displayName}) is left out: $problem."
            false
        }
        return Pool(listings, warnings)
    }

    /** Why [l] can't be offered, or null when it can. */
    fun problemOf(l: EventShopStore.Listing): String? = when {
        l.price <= 0.0 || !l.price.isFinite() || l.price > MAX_PRICE -> "invalid price"
        l.quantity !in 1..MAX_QUANTITY -> "invalid quantity"
        l.weight !in 1..MAX_WEIGHT -> "invalid weight"
        else -> templateProblem(l.kind, l.customItemId, l.itemData)
    }

    private fun templateProblem(kind: ItemKind, customItemId: String?, itemData: String): String? {
        val stack = template(kind, customItemId, itemData) ?: return when (kind) {
            ItemKind.CUSTOM -> "custom item '$customItemId' is no longer registered"
            ItemKind.STACK -> "the saved item can't be read"
        }
        val taggedId = plugin.itemManager.getCustomItemId(stack)
        if (taggedId != null && plugin.itemManager.getItem(taggedId) == null) return "custom item '$taggedId' is no longer registered"
        val scope = taggedId?.let { plugin.itemManager.getItem(it)?.gamemodeScope }
        if (scope != null && scope != GamemodeScope.SURVIVAL && scope != GamemodeScope.NETWORK) return "'$taggedId' doesn't belong to Survival"
        return null
    }

    /** One unit of the item, rebuilt fresh: custom items from their definition, the rest from the saved stack. */
    fun template(kind: ItemKind, customItemId: String?, itemData: String): ItemStack? = when (kind) {
        ItemKind.CUSTOM -> customItemId?.let { plugin.itemManager.getItem(it) }?.createItemStack(1)
        ItemKind.STACK -> deserialize(itemData)
    }?.takeIf { !it.type.isAir }?.apply { amount = 1 }

    fun template(item: RotationItem): ItemStack? = template(item.kind, item.customItemId, item.itemData)
    fun template(l: EventShopStore.Listing): ItemStack? = template(l.kind, l.customItemId, l.itemData)

    fun serialize(item: ItemStack): String = Base64.getEncoder().encodeToString(item.serializeAsBytes())

    private fun deserialize(data: String): ItemStack? =
        runCatching { ItemStack.deserializeBytes(Base64.getDecoder().decode(data)) }.getOrNull()

    // ── Player side ──────────────────────────────────────

    /** Whether [player] is somewhere the Survival shop may be used (their gamemode is Survival). */
    fun isSurvival(player: Player): Boolean =
        plugin.gamemodeLockManager.gamemodeOf(player) == ServerGamemode.SURVIVAL

    /** `/eventshop` and `/eshop`: the shop menu, or when it's closed, when it opens next. */
    fun openShop(player: Player) {
        if (!isSurvival(player)) {
            plugin.commsManager.send(player, Component.text("The Event Shop is only available on Survival.", NamedTextColor.RED))
            return
        }
        if (plugin.eventManager.isInEventWorld(player)) {
            plugin.commsManager.send(player, Component.text("The Event Shop can't be used in the event world. Use /spawn to leave first.", NamedTextColor.RED))
            return
        }
        val now = Instant.now()
        val a = active?.takeIf { it.rotation.isActiveAt(now) }
        if (a == null) {
            sendClosedInfo(player, now)
            return
        }

        val gui = CustomGui(GUI_TITLE, GUI_SIZE)
        for (slot in 0 until GUI_SIZE) {
            gui.inventory.setItem(slot, GuiStyle.filler(if (slot % 2 == 0) Material.ORANGE_STAINED_GLASS_PANE else Material.YELLOW_STAINED_GLASS_PANE))
        }
        gui.setItem(INFO_SLOT, infoItem(a, now))
        gui.setItem(BALANCE_SLOT, balanceItem(player))
        for (slot in ITEM_SLOTS) {
            gui.setItem(slot, GuiStyle.item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, Component.text("Empty Slot", NamedTextColor.GRAY),
                listOf(Component.text("Nothing is sold here this rotation.", NamedTextColor.DARK_GRAY))))
        }
        for (item in a.items) {
            val slot = ITEM_SLOTS.getOrNull(item.position) ?: continue
            val display = displayItem(a, item, player)
            gui.setItem(slot, display) { p, _ -> openConfirm(p, a.rotation.id, item.position, item.listingId) }
        }
        gui.setItem(GuiStyle.closeSlot(GUI_SIZE), GuiStyle.close()) { p, _ -> p.closeInventory() }
        track(player, gui, a.rotation.id, isShop = true)
        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f)
    }

    private fun sendClosedInfo(player: Player, now: Instant) {
        plugin.commsManager.send(player, Component.text("EVENT SHOP IS CURRENTLY CLOSED!", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
        val next = nextOpening(now)
        if (next != null) {
            player.sendMessage(Component.text("Next Opening: ", NamedTextColor.GRAY)
                .append(Component.text(formatClock(next.start), EVENT_ACCENT))
                .append(Component.text(" (in ${formatDuration(Duration.between(now, next.start))})", NamedTextColor.DARK_GRAY)))
            player.sendMessage(Component.text("Duration: ", NamedTextColor.GRAY).append(Component.text("2 Hours", EVENT_ACCENT)))
        } else {
            player.sendMessage(Component.text("No opening is scheduled right now.", NamedTextColor.GRAY))
        }
    }

    /** The next scheduled window that will actually open (skipping one already used today). */
    fun nextOpening(now: Instant): EventShopSchedule.Window? {
        if (!schedule.valid) return null
        val current = EventShopSchedule.windowAt(now, zone, schedule.times)
        if (current != null && store.rotationByKey(current.key) == null && active == null) return current
        return EventShopSchedule.nextWindowAfter(now, zone, schedule.times)
    }

    private fun track(player: Player, gui: CustomGui, rotationId: Int, isShop: Boolean) {
        gui.onClose = { p -> viewers.remove(p.uniqueId)?.let { if (it.gui !== gui) viewers[p.uniqueId] = it } }
        viewers[player.uniqueId] = View(gui, rotationId, isShop)
    }

    private fun displayItem(a: Active, item: RotationItem, player: Player): ItemStack {
        val stack = template(item) ?: return GuiStyle.item(
            Material.BARRIER, Component.text("Unavailable", NamedTextColor.RED),
            listOf(Component.text("This item can't be sold right now.", NamedTextColor.GRAY)),
        )
        stack.amount = item.quantity.coerceIn(1, stack.maxStackSize.coerceAtLeast(1))
        stack.editMeta { meta ->
            val lore = (meta.lore() ?: emptyList()).toMutableList()
            lore += Component.empty()
            lore += purchaseLines(a, item, player)
            lore += Component.text("Click to Purchase", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
            meta.lore(lore)
        }
        return stack
    }

    private fun purchaseLines(a: Active, item: RotationItem, player: Player): List<Component> {
        val lines = mutableListOf<Component>()
        lines += line("Price: ", plugin.economyManager.format(item.price), NamedTextColor.GOLD)
        lines += line("Currency: ", "Money", NamedTextColor.GOLD)
        lines += line("Quantity: ", "x${item.quantity}", NamedTextColor.WHITE)
        if (item.perPlayerLimit > 0) {
            val bought = store.soldCount(a.rotation.windowId, item.listingId, player.uniqueId)
            lines += line("Your Purchases: ", "$bought/${item.perPlayerLimit}", NamedTextColor.WHITE)
        }
        if (item.stockLimit > 0) {
            val left = (item.stockLimit - store.soldCount(a.rotation.windowId, item.listingId)).coerceAtLeast(0)
            lines += line("Stock Left: ", "$left/${item.stockLimit}", if (left > 0) NamedTextColor.GREEN else NamedTextColor.RED)
        }
        return lines
    }

    private fun line(label: String, value: String, color: TextColor): Component =
        Component.text(label, NamedTextColor.GRAY).append(Component.text(value, color)).decoration(TextDecoration.ITALIC, false)

    private fun infoItem(a: Active, now: Instant): ItemStack {
        val lore = mutableListOf(
            Component.text("EVENT SHOP IS NOW OPEN!", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true),
            Component.empty(),
            line("Time Remaining: ", formatDuration(Duration.between(now, a.rotation.end)), NamedTextColor.YELLOW),
            line("Available Items: ", a.items.size.toString(), NamedTextColor.YELLOW),
            line("Closes at: ", formatClock(a.rotation.end), NamedTextColor.YELLOW),
            Component.empty(),
            Component.text("Every player sees these items", NamedTextColor.GRAY),
            Component.text("until the shop closes.", NamedTextColor.GRAY),
            Component.text("Paid with Money.", NamedTextColor.GRAY),
        )
        return GuiStyle.item(Material.CLOCK, Component.text("Event Shop", EVENT_GOLD).decoration(TextDecoration.BOLD, true), lore)
    }

    private fun balanceItem(player: Player): ItemStack = GuiStyle.item(
        Material.SUNFLOWER,
        Component.text("Your Money", NamedTextColor.GOLD),
        listOf(Component.text(plugin.economyManager.format(plugin.economyManager.getBalance(player.uniqueId)), NamedTextColor.YELLOW)),
    )

    private fun updateTimers(now: Instant) {
        val a = active ?: return
        for (view in viewers.values) {
            if (view.isShop && view.rotationId == a.rotation.id) view.gui.inventory.setItem(INFO_SLOT, infoItem(a, now))
        }
    }

    /** Closes the shop menus of [rotationId] (every shop menu when null), telling viewers [reason]. */
    private fun closeViewers(rotationId: Int?, reason: String?) {
        for ((uuid, view) in viewers.toMap()) {
            if (rotationId != null && view.rotationId != rotationId) continue
            viewers.remove(uuid)
            val player = Bukkit.getPlayer(uuid) ?: continue
            if (plugin.guiManager.getOpenGui(player) === view.gui) {
                player.closeInventory()
                if (reason != null) plugin.commsManager.send(player, Component.text(reason, NamedTextColor.RED))
            }
        }
    }

    /** The confirmation screen for one item. Nothing is decided here; [purchase] re-checks everything. */
    private fun openConfirm(player: Player, rotationId: Int, position: Int, listingId: Int) {
        val a = current(player, rotationId) ?: return
        val item = a.items.firstOrNull { it.position == position && it.listingId == listingId } ?: return
        val gui = CustomGui(CONFIRM_TITLE, GUI_SIZE)
        gui.fill(GuiStyle.filler())
        gui.setItem(CONFIRM_ITEM_SLOT, displayItem(a, item, player).also { stack ->
            stack.editMeta { meta -> meta.lore(meta.lore()?.dropLast(1)) }
        })
        gui.setItem(CONFIRM_CANCEL_SLOT, GuiStyle.item(Material.RED_STAINED_GLASS_PANE, Component.text("Cancel", NamedTextColor.RED),
            listOf(Component.text("Back to the Event Shop", NamedTextColor.GRAY)))) { p, _ ->
            GuiStyle.clickSound(p)
            openShop(p)
        }
        gui.setItem(CONFIRM_BUY_SLOT, GuiStyle.item(Material.LIME_STAINED_GLASS_PANE, Component.text("Confirm Purchase", NamedTextColor.GREEN),
            listOf(
                line("Buy x${item.quantity} for ", plugin.economyManager.format(item.price), NamedTextColor.GOLD),
            ))) { p, _ ->
            purchase(p, rotationId, position, listingId)
            if (current(p, rotationId, silent = true) != null) openShop(p)
        }
        track(player, gui, rotationId, isShop = false)
        plugin.guiManager.open(player, gui)
        GuiStyle.clickSound(player)
    }

    /** The running rotation if it is [rotationId] and still inside its window; otherwise closes the menu and says so. */
    private fun current(player: Player, rotationId: Int, silent: Boolean = false): Active? {
        val a = active
        if (a == null || a.rotation.id != rotationId || !a.rotation.isActiveAt(Instant.now())) {
            if (!silent) {
                player.closeInventory()
                reject(player, "This Event Shop rotation has ended. That item is no longer for sale.")
            }
            return null
        }
        return a
    }

    /**
     * Buys one listing. Every check runs against the server's own state at click time: the
     * rotation must be the running one and inside its window, the position must still hold the
     * listing the menu showed, and price, quantity and limits come from the rotation snapshot.
     * Money is taken (atomic conditional UPDATE) and the purchase recorded in the same transaction
     * that checks the limits, then the item is delivered; if none of it could be stored the money
     * goes straight back. A full inventory is refused before any money moves. Returns true when
     * the player was charged and received the item.
     */
    fun purchase(player: Player, rotationId: Int, position: Int, listingId: Int): Boolean {
        val uuid = player.uniqueId
        if (!isSurvival(player) || plugin.eventManager.isInEventWorld(player)) {
            player.closeInventory()
            return false
        }
        val a = current(player, rotationId) ?: return false
        val item = a.items.firstOrNull { it.position == position && it.listingId == listingId }
        if (item == null) {
            player.closeInventory()
            reject(player, "That item is no longer for sale.")
            return false
        }
        val template = template(item)?.takeIf { templateProblem(item.kind, item.customItemId, item.itemData) == null }
        if (template == null || item.price <= 0.0 || !item.price.isFinite() || item.quantity !in 1..MAX_QUANTITY) {
            reject(player, "That item can't be sold right now.")
            return false
        }
        if (!plugin.modModeManager.isModMode(player) && spaceFor(player, template) < item.quantity) {
            reject(player, "Your inventory doesn't have room for x${item.quantity}. Make room before buying.")
            return false
        }
        if (!purchasing.add(uuid)) return false
        try {
            val txnId = UUID.randomUUID().toString()
            var purchaseId: Int? = null
            var refusal: String? = null
            try {
                plugin.databaseManager.transaction {
                    if (item.stockLimit > 0 && store.soldCount(a.rotation.windowId, item.listingId) >= item.stockLimit) {
                        refusal = "That item is sold out for this rotation."
                        return@transaction
                    }
                    if (item.perPlayerLimit > 0 && store.soldCount(a.rotation.windowId, item.listingId, uuid) >= item.perPlayerLimit) {
                        refusal = "You've reached the limit of ${item.perPlayerLimit} for that item this rotation."
                        return@transaction
                    }
                    if (!plugin.economyManager.withdraw(uuid, item.price)) {
                        refusal = "You need ${plugin.economyManager.format(item.price)} to buy that item."
                        return@transaction
                    }
                    purchaseId = store.insertPurchase(txnId, a.rotation, item, uuid, player.name)
                }
            } catch (e: Exception) {
                plugin.logger.severe("[EventShop] Purchase by ${player.name} failed before delivery (nothing charged): ${e.message}")
                reject(player, "Something went wrong. You were not charged.")
                return false
            }
            val id = purchaseId
            if (id == null) {
                reject(player, refusal ?: "That purchase couldn't be completed. You were not charged.")
                return false
            }

            val leftovers = mutableListOf<ItemStack>()
            var left = item.quantity
            val max = template.maxStackSize.coerceAtLeast(1)
            while (left > 0) {
                val n = minOf(max, left)
                left -= n
                plugin.depositItemSafely(player, template.clone().apply { amount = n })?.takeIf { it.amount > 0 }?.let { leftovers += it }
            }
            val undelivered = leftovers.sumOf { it.amount }
            if (undelivered >= item.quantity) {
                refund(player, id, txnId, item)
                return false
            }
            // Paid items are never deleted: anything that didn't fit after all lands at the player's feet.
            for (stack in leftovers) player.world.dropItemNaturally(player.location, stack)
            val note = if (undelivered > 0) "$undelivered dropped at the player's feet" else null
            runCatching { store.settlePurchase(id, PurchaseStatus.DELIVERED, note) }.onFailure {
                plugin.logger.severe("[EventShop] Purchase $txnId was delivered but couldn't be marked delivered: ${it.message}")
            }
            plugin.logger.info("[EventShop] ${player.name} ($uuid) bought listing #${item.listingId} (${item.displayName} x${item.quantity}) for ${item.price} " +
                "(transaction $txnId, rotation #${a.rotation.id})" + (note?.let { "; $it" } ?: "") + ".")
            plugin.commsManager.send(player, Component.text("You bought ", NamedTextColor.GREEN)
                .append(template.effectiveName().hoverEvent(template.asHoverEvent()))
                .append(Component.text(" x${item.quantity} for ", NamedTextColor.GREEN))
                .append(Component.text(plugin.economyManager.format(item.price), NamedTextColor.GOLD))
                .append(Component.text("!", NamedTextColor.GREEN))
                .hoverEvent(HoverEvent.showText(Component.text("Transaction $txnId", NamedTextColor.GRAY))))
            if (undelivered > 0) {
                plugin.commsManager.send(player, Component.text("Your inventory filled up, so $undelivered of them were dropped at your feet.", NamedTextColor.YELLOW))
            }
            GuiStyle.successSound(player)
            return true
        } finally {
            purchasing.remove(uuid)
        }
    }

    private fun refund(player: Player, purchaseId: Int, txnId: String, item: RotationItem) {
        try {
            plugin.databaseManager.transaction {
                if (store.settlePurchase(purchaseId, PurchaseStatus.REFUNDED, "item could not be stored")) {
                    plugin.economyManager.deposit(player.uniqueId, item.price)
                }
            }
            plugin.logger.warning("[EventShop] Purchase $txnId by ${player.name} couldn't be delivered; refunded ${item.price}.")
            reject(player, "Your item couldn't be stored, so your ${plugin.economyManager.format(item.price)} was refunded.")
        } catch (e: Exception) {
            plugin.logger.severe("[EventShop] Purchase $txnId by ${player.name} (${player.uniqueId}) was not delivered and the refund of ${item.price} failed: ${e.message}. Refund it by hand.")
            reject(player, "Your item couldn't be stored. Staff have been told to refund you.")
        }
    }

    /** How many of [item] the player's storage slots can take right now. */
    private fun spaceFor(player: Player, item: ItemStack): Int {
        val max = item.maxStackSize.coerceAtLeast(1)
        var space = 0L
        for (slot in player.inventory.storageContents) {
            space += when {
                slot == null || slot.type.isAir -> max
                slot.isSimilar(item) -> (max - slot.amount).coerceAtLeast(0)
                else -> 0
            }
        }
        return space.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun reject(player: Player, text: String) {
        plugin.commsManager.send(player, Component.text(text, NamedTextColor.RED))
        GuiStyle.errorSound(player)
    }

    // ── Messages ─────────────────────────────────────────

    private val defaults = mapOf(
        "open" to "&6&lEVENT SHOP IS NOW OPEN!\n&f{count} random items are available for the next {duration}.\n&7Use &6/eventshop &7or &6/eshop &7to browse!",
        "close" to "&6&lThe Event Shop has closed!\n&7Check back for the next rotation.",
    )

    private fun message(key: String, vararg replacements: Pair<String, String>): Component {
        var raw = plugin.config.getString("event-shop.messages.$key")?.takeIf { it.isNotBlank() } ?: defaults.getValue(key)
        for ((from, to) in replacements) raw = raw.replace(from, to)
        return plugin.commsManager.parseLegacy(raw)
    }

    private fun announceOpen(a: Active, remaining: Duration) {
        val count = a.items.size
        announce(message("open",
            "{count}" to count.toString(),
            "{duration}" to formatLongDuration(remaining),
        ), withLink = true)
    }

    /** Sends [message] to every Survival player (never into Lifesteal) and the console. */
    private fun announce(message: Component, withLink: Boolean) {
        val line = if (!withLink) message else message.append(Component.text(" "))
            .append(Component.text("[Open Shop]", EVENT_GOLD).decoration(TextDecoration.BOLD, true)
                .clickEvent(ClickEvent.runCommand("/eventshop"))
                .hoverEvent(HoverEvent.showText(Component.text("Click to open the Event Shop", NamedTextColor.GRAY))))
        for (p in Bukkit.getOnlinePlayers()) {
            if (isSurvival(p)) {
                plugin.commsManager.sendRaw(p, Component.empty())
                plugin.commsManager.sendRaw(p, line)
                plugin.commsManager.sendRaw(p, Component.empty())
            }
        }
        Bukkit.getConsoleSender().sendMessage(message)
    }

    fun notifyAdmins(text: String) {
        val msg = Component.text("[Event Shop] ", EVENT_GOLD).append(Component.text(text, NamedTextColor.RED))
        for (p in Bukkit.getOnlinePlayers()) if (p.hasPermission(ADMIN_PERMISSION)) plugin.commsManager.sendRaw(p, msg)
    }

    // ── Formatting ───────────────────────────────────────

    fun formatClock(instant: Instant): String = CLOCK.format(instant.atZone(zone))

    fun describeSchedule(): String =
        if (schedule.valid) schedule.times.joinToString(", ") { EventShopSchedule.fmt(it) } + " (${zone.id})"
        else "INVALID: ${schedule.problems.joinToString("; ")}"

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

    /** "2 hours", "1 hour 15 minutes", "45 minutes": for announcements. */
    fun formatLongDuration(d: Duration): String {
        val minutes = ((d.seconds + 59) / 60).coerceAtLeast(1)
        val h = minutes / 60
        val m = minutes % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "$h hour" + if (h == 1L) "" else "s"
        if (m > 0) parts += "$m minute" + if (m == 1L) "" else "s"
        return parts.joinToString(" ")
    }

    /** Today's scheduled windows (and a still-running one from yesterday), for `/eventshop admin status`. */
    fun windowsForAdmin(now: Instant = Instant.now()): List<EventShopSchedule.Window> {
        if (!schedule.valid) return emptyList()
        val today = LocalDate.ofInstant(now, zone)
        return EventShopSchedule.windowsAround(now, zone, schedule.times)
            .filter { it.day == today || it.isActiveAt(now) }
    }
}
