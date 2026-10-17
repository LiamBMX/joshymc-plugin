package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.PurchaseDeliveryStore.Delivery
import com.liam.joshymc.manager.PurchaseDeliveryStore.State
import com.liam.joshymc.manager.PurchaseRewards.Kind
import com.liam.joshymc.util.depositItemSafely
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Store purchase fulfillment that survives the buyer being offline (issue #1175).
 *
 * The external Tebex plugin runs console commands; JoshyMC never talks to Tebex itself.
 * `/purchase fulfill` (console only) records every purchase line in `purchase_deliveries`
 * ([PurchaseDeliveryStore]) before anything is granted, then:
 *  - Coins / Credits: deposited online or offline, in the same transaction that marks the
 *    row COMPLETED. Exactly once per row.
 *  - Items (tokens, crate keys, vouchers): built by the existing item factories and handed
 *    over while the recipient is online, one unit at a time and only as many as fit.
 *    `remaining` tracks what is still owed; nothing is ever dropped on the ground.
 *  - Donor ranks: granted through [RankManager.grantDonorTier] while the recipient is
 *    online, COMPLETED only once LuckPerms shows the group.
 *
 * With a reference (the store's transaction id) the row's dedupe key is UNIQUE, so a
 * repeated command is recorded once and granted once. Without one, identical commands
 * can't be told apart and each is granted.
 */
class PurchaseFulfillmentManager(private val plugin: Joshymc) : Listener {

    /** What `/purchase fulfill` did with one command. */
    sealed class FulfillResult {
        data class Recorded(val delivery: Delivery) : FulfillResult()
        data class Duplicate(val existing: Delivery) : FulfillResult()
        data class Unresolved(val delivery: Delivery) : FulfillResult()
        data class Rejected(val reason: String, val id: Int?) : FulfillResult()
        data class DatabaseError(val message: String) : FulfillResult()
    }

    val store = PurchaseDeliveryStore(plugin.databaseManager, { sql, params -> plugin.databaseManager.insertReturningId(sql, *params) })
    private var sweepTask: BukkitTask? = null
    private val lastFullNotice = ConcurrentHashMap<UUID, Long>()

    private val catalog = object : PurchaseRewards.Catalog {
        override fun crateExists(id: String) = plugin.crateManager.getCrate(plugin.crateManager.resolveCrateId(id)) != null
        override fun moneyVoucherExists(id: String) = plugin.physicalVoucherManager.getVoucher(id)?.enabled == true
        override fun chatTagVoucherExists(id: String) = plugin.chatTagManager.getVoucherTag(id) != null
    }

    fun start() {
        store.createSchema()

        val recovery = store.recoverInterrupted()
        for (row in recovery.interrupted) {
            plugin.logger.warning(
                "[Purchases] #${row.id} (${row.describe()}, ${row.remaining} undelivered) for ${row.recipientName} (${row.recipientUuid}) " +
                    "was mid-delivery when the server stopped; now INTERRUPTED. Check their inventory, then " +
                    "/purchase retry ${row.id} confirm or /purchase cancel ${row.id}."
            )
        }
        for (row in recovery.requeued) {
            plugin.logger.info("[Purchases] #${row.id} (${row.describe()}) for ${row.recipientName} was waiting on LuckPerms at shutdown; requeued.")
        }
        val open = store.openCounts()
        if (open.isNotEmpty()) {
            plugin.logger.info("[Purchases] Open store deliveries: ${open.entries.joinToString(", ") { "${it.value} ${it.key.name}" }}. See /purchase list.")
        }

        Bukkit.getPluginManager().registerEvents(this, plugin)
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sweep() }, STARTUP_SWEEP_DELAY_TICKS, SWEEP_INTERVAL_TICKS)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            Bukkit.getOnlinePlayers().forEach { deliverFor(it) }
        }, JOIN_DELAY_TICKS)
    }

    fun stop() {
        sweepTask?.cancel()
        sweepTask = null
    }

    // ── Fulfillment entry point ─────────────────────────────────────────────

    /**
     * Records one store purchase line and delivers what it can right away. [recipientInput]
     * is a UUID (dashed or not) or an exact player name; [rewardArgs] is
     * `<type> <args...> [reference]`. Main thread.
     */
    fun fulfill(recipientInput: String, rewardArgs: List<String>, source: String): FulfillResult {
        val parsed = when (val p = PurchaseRewards.parse(rewardArgs, catalog)) {
            is PurchaseRewards.Result.Ok -> p.value
            is PurchaseRewards.Result.Error -> {
                val id = try {
                    store.insertRejected(recipientInput, rewardArgs.firstOrNull() ?: "?", rewardArgs.drop(1).joinToString(" "), p.message, source)
                } catch (e: Exception) {
                    plugin.logger.severe("[Purchases] Could not record rejected store command: ${e.message}")
                    null
                }
                plugin.logger.severe(
                    "[Purchases] REJECTED store command for '$recipientInput': ${p.message} (args: ${rewardArgs.joinToString(" ")}). " +
                        "Nothing was granted." + (id?.let { " Recorded as #$it." } ?: "")
                )
                return FulfillResult.Rejected(p.message, id)
            }
        }
        val spec = parsed.spec
        val recipient = resolveRecipient(recipientInput)
        val dedupeKey = PurchaseRewards.dedupeKey(parsed.reference, recipient?.first?.toString() ?: recipientInput, spec)

        val inserted = try {
            store.insert(recipient?.first, recipient?.second ?: recipientInput, spec, parsed.reference, dedupeKey, source)
        } catch (e: Exception) {
            plugin.logger.severe(
                "[Purchases] Could not record store purchase '${spec.describe()}' for '$recipientInput' ref=${parsed.reference ?: "none"}: " +
                    "${e.message}. NOTHING WAS GRANTED; grant it by hand once the database is healthy."
            )
            return FulfillResult.DatabaseError(e.message ?: e.javaClass.simpleName)
        }

        val delivery = when (inserted) {
            is PurchaseDeliveryStore.InsertResult.Duplicate -> {
                val existing = inserted.existing
                plugin.logger.warning(
                    "[Purchases] Duplicate store command ignored: ref=${parsed.reference} ${spec.describe()} for ${existing.recipientName} " +
                        "is already delivery #${existing.id} (${existing.state.name}). Nothing more was granted."
                )
                return FulfillResult.Duplicate(existing)
            }
            is PurchaseDeliveryStore.InsertResult.Inserted -> inserted.delivery
        }

        plugin.logger.info(
            "[Purchases] #${delivery.id} recorded ${delivery.state.name}: ${spec.describe()} for ${delivery.recipientName} " +
                "(${delivery.recipientUuid ?: "unresolved"}) ref=${parsed.reference ?: "none"} source=$source"
        )
        if (parsed.reference == null) {
            plugin.logger.info("[Purchases] #${delivery.id} has no external reference: a repeated command would be granted again.")
        }
        if (recipient == null) {
            plugin.logger.warning(
                "[Purchases] #${delivery.id}: no player named exactly '$recipientInput' has joined this server, so it was held, not guessed. " +
                    "Use /purchase assign ${delivery.id} <player|uuid> once you know who bought it."
            )
            return FulfillResult.Unresolved(delivery)
        }

        process(delivery)
        return FulfillResult.Recorded(store.get(delivery.id) ?: delivery)
    }

    /**
     * A UUID is taken as given (the store verified it); a name must exactly match a player
     * who has joined this server, never a prefix. Floodgate players resolve like anyone
     * else: by their Floodgate UUID, or the name the server shows (prefix included).
     */
    private fun resolveRecipient(input: String): Pair<UUID, String>? {
        PurchaseRewards.parseUuid(input)?.let { uuid ->
            val name = Bukkit.getPlayer(uuid)?.name ?: plugin.playerResolver.lastKnownName(uuid)
                ?: Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
            return uuid to name
        }
        val player = plugin.playerResolver.resolve(input) ?: return null
        val recordedName = plugin.playerResolver.lastKnownName(player.uniqueId)
        if (!player.isOnline && !player.hasPlayedBefore() && recordedName == null) return null
        return player.uniqueId to (player.name ?: recordedName ?: input)
    }

    /** Same rules as the store path, for `/purchase assign` and `/purchase list <player>`. */
    fun resolvePlayer(input: String): Pair<UUID, String>? = resolveRecipient(input)

    /** True once [uuid] has joined this server, so no balance is ever opened for a UUID nobody has played on. */
    private fun isKnownPlayer(uuid: UUID): Boolean =
        Bukkit.getPlayer(uuid) != null || Bukkit.getOfflinePlayer(uuid).hasPlayedBefore() || plugin.playerResolver.lastKnownName(uuid) != null

    // ── Processing ──────────────────────────────────────────────────────────

    /** Main thread. Delivers [row] now if it can; otherwise it stays PENDING. */
    private fun process(row: Delivery, includeRanks: Boolean = true) {
        if (row.state != State.PENDING) return
        val uuid = row.recipientUuid ?: return
        val kind = row.kind ?: return markFailed(row, "unknown reward type '${row.rawType}'")
        when (kind.delivery) {
            PurchaseRewards.Delivery.CURRENCY -> if (isKnownPlayer(uuid)) processCurrency(row, kind)
            PurchaseRewards.Delivery.ITEM -> Bukkit.getPlayer(uuid)?.let { processItems(it, row, kind) }
            PurchaseRewards.Delivery.RANK -> if (includeRanks) Bukkit.getPlayer(uuid)?.let { processRank(it, row) }
        }
    }

    private fun processCurrency(row: Delivery, kind: Kind) {
        val uuid = row.recipientUuid ?: return
        val amount = row.param.toDoubleOrNull()
        if (amount == null || !amount.isFinite() || amount <= 0.0) return markFailed(row, "invalid stored amount '${row.param}'")

        val outcome = try {
            store.completeCurrency(row.id) {
                when (kind) {
                    Kind.COINS -> plugin.economyManager.deposit(uuid, amount)
                    else -> plugin.creditsManager.deposit(uuid, amount)
                }
            }
        } catch (e: Exception) {
            val next = try {
                store.recordAttemptFailure(row.id, MAX_CURRENCY_ATTEMPTS, "database error: ${e.message}")
            } catch (inner: Exception) {
                null
            }
            plugin.logger.warning("[Purchases] #${row.id} ${row.describe()} for ${row.recipientName} ($uuid) failed (${e.message}); nothing was deposited. Now ${next?.name ?: "unknown"}.")
            return
        }

        when (outcome) {
            PurchaseDeliveryStore.CurrencyOutcome.NOT_PENDING -> return
            PurchaseDeliveryStore.CurrencyOutcome.REJECTED -> {
                plugin.logger.warning("[Purchases] #${row.id} FAILED: ${row.describe()} for ${row.recipientName} ($uuid) was rejected by the balance manager.")
                return
            }
            PurchaseDeliveryStore.CurrencyOutcome.PAID -> Unit
        }
        plugin.logger.info("[Purchases] #${row.id} COMPLETED: ${row.describe()} deposited to ${row.recipientName} ($uuid) ref=${row.externalRef ?: "none"}")
        Bukkit.getPlayer(uuid)?.let { player ->
            val text = if (kind == Kind.COINS) plugin.economyManager.format(amount) else "${plugin.creditsManager.format(amount)} Credits"
            notify(player, Component.text("Your store purchase was delivered: ", NamedTextColor.GREEN)
                .append(Component.text(text, if (kind == Kind.COINS) NamedTextColor.GOLD else NamedTextColor.AQUA))
                .append(Component.text(".", NamedTextColor.GREEN)))
        }
    }

    /**
     * Hands over as many units as fit, one at a time through the shared Mod-Mode-aware
     * [depositItemSafely], then saves the player and records what was delivered. The row is
     * PROCESSING only inside this one synchronous call; a crash inside it leaves the row
     * INTERRUPTED at the next start, for an operator to check.
     */
    private fun processItems(player: Player, snapshot: Delivery, kind: Kind) {
        if (!player.isOnline || player.isDead) return
        val probe = buildUnit(kind, snapshot.param)
            ?: return markFailed(snapshot, "the reward no longer exists on this server (${snapshot.describe()})")
        if (!canFitOne(player, probe)) {
            noticeFull(player, snapshot)
            return
        }
        // Work from the row as claimed, not the snapshot this pass loaded earlier.
        val row = store.claim(snapshot.id) ?: return

        var delivered = 0
        var error: String? = null
        try {
            while (delivered < row.remaining) {
                val unit = if (delivered == 0) probe else buildUnit(kind, row.param)
                if (unit == null) {
                    error = "the reward no longer exists on this server"
                    break
                }
                val leftover = plugin.depositItemSafely(player, unit)
                if (leftover != null && leftover.amount > 0) break
                delivered++
            }
        } catch (e: Exception) {
            error = "delivery error: ${e.message}"
            plugin.logger.severe("[Purchases] #${row.id} delivery to ${player.name} failed after $delivered unit(s): ${e.message}")
        }

        if (delivered > 0) {
            // Write the inventory now, so a crash before the next autosave can't lose what the row will say was delivered.
            try {
                player.saveData()
            } catch (e: Exception) {
                plugin.logger.warning("[Purchases] Could not save ${player.name}'s data after delivery #${row.id}: ${e.message}")
            }
        }

        val left = row.remaining - delivered
        val next = when {
            left == 0 -> State.COMPLETED
            error != null -> State.FAILED
            else -> State.PENDING
        }
        try {
            store.finishItems(row.id, delivered, next, error)
        } catch (e: Exception) {
            // Still PROCESSING, so it turns INTERRUPTED at the next start and can't be sent twice.
            plugin.logger.severe("[Purchases] #${row.id}: delivered $delivered unit(s) to ${player.name} but could not record it: ${e.message}. Review it after a restart.")
            return
        }
        val line = "[Purchases] #${row.id} ${next.name}: delivered $delivered of ${row.remaining} (${row.describe()}) to ${player.name} " +
            "(${player.uniqueId}), $left left, ref=${row.externalRef ?: "none"}" + (error?.let { ", error: $it" } ?: "")
        if (next == State.FAILED) plugin.logger.warning(line) else plugin.logger.info(line)

        if (delivered > 0) {
            notify(player, Component.text("Store purchase delivered: ", NamedTextColor.GREEN)
                .append(Component.text("${delivered}x ${rewardLabel(kind, row.param)}", NamedTextColor.AQUA))
                .append(Component.text(".", NamedTextColor.GREEN)))
        }
        if (next == State.PENDING) noticeFull(player, row.copy(remaining = left), force = delivered > 0)
        if (next == State.FAILED) {
            notify(player, Component.text("Part of your store purchase couldn't be delivered. Staff can see it as delivery #${row.id}.", NamedTextColor.RED))
        }
    }

    /**
     * Donor tier purchase. Shares the donor-change lock with Rank Vouchers and the Credit
     * Shop, adds the group with a targeted `parent add` (never `parent set`) and completes
     * only once LuckPerms shows it.
     */
    private fun processRank(player: Player, snapshot: Delivery) {
        val uuid = player.uniqueId
        val tier = DonorTiers.normalize(snapshot.param) ?: return markFailed(snapshot, "unknown donor tier '${snapshot.param}'")
        if (!plugin.rankManager.tryLockDonorChange(uuid)) {
            // A voucher or Credit Shop rank change is running; look again shortly.
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (player.isOnline) store.get(snapshot.id)?.let { process(it) }
            }, RANK_LOCK_RETRY_TICKS)
            return
        }
        val row = store.claim(snapshot.id, countAttempt = true)
        if (row == null) {
            plugin.rankManager.unlockDonorChange(uuid)
            return
        }
        val earlierAttempt = row.attempts > 1

        val current = plugin.rankManager.currentDonorTier(uuid)
        if (DonorTiers.compare(current, tier) != DonorTiers.Comparison.UPGRADE) {
            plugin.rankManager.unlockDonorChange(uuid)
            if (earlierAttempt && current == tier) {
                // An earlier attempt sent the grant, but the player left (or the server stopped)
                // before LuckPerms confirmed it. It has landed since: record it like a confirmed grant.
                plugin.rankManager.completeDonorGrant(uuid, tier)
                finishRank(row, State.COMPLETED, "tier confirmed after an earlier attempt")
            } else {
                finishRank(row, State.FAILED, "recipient already holds ${current?.let { DonorTiers.displayName(it) }}, " +
                    "not below ${DonorTiers.displayName(tier)}; nothing was granted")
            }
            return
        }

        plugin.rankManager.grantDonorTier(player, tier) { result ->
            plugin.rankManager.unlockDonorChange(uuid)
            when (result) {
                RankManager.DonorGrantResult.CONFIRMED -> {
                    finishRank(row, State.COMPLETED, null)
                    if (player.isOnline) notify(player, Component.text("Store purchase delivered: ", NamedTextColor.GREEN)
                        .append(Component.text("${DonorTiers.displayName(tier)} rank", NamedTextColor.GOLD))
                        .append(Component.text(".", NamedTextColor.GREEN)))
                }
                RankManager.DonorGrantResult.PLAYER_LEFT -> finishRank(row, State.PENDING, "player left before LuckPerms confirmed; retried on their next join")
                else -> {
                    val next = if (row.attempts >= MAX_RANK_ATTEMPTS) State.FAILED else State.PENDING
                    finishRank(row, next, "LuckPerms grant ${result.name} (attempt ${row.attempts} of $MAX_RANK_ATTEMPTS)")
                }
            }
        }
    }

    private fun finishRank(row: Delivery, next: State, note: String?) {
        try {
            store.finish(row.id, next, note)
        } catch (e: Exception) {
            plugin.logger.severe("[Purchases] Could not record #${row.id} as ${next.name}: ${e.message}. It is requeued at the next start.")
            return
        }
        val line = "[Purchases] #${row.id} ${next.name}: ${row.describe()} for ${row.recipientName} (${row.recipientUuid}) " +
            "ref=${row.externalRef ?: "none"}" + (note?.let { " ($it)" } ?: "")
        if (next == State.FAILED) plugin.logger.warning(line) else plugin.logger.info(line)
    }

    private fun markFailed(row: Delivery, reason: String) {
        try {
            store.markFailed(row.id, reason)
        } catch (e: Exception) {
            plugin.logger.severe("[Purchases] Could not mark #${row.id} FAILED: ${e.message}")
        }
        plugin.logger.warning("[Purchases] #${row.id} FAILED: ${row.describe()} for ${row.recipientName} (${row.recipientUuid}): $reason. See /purchase info ${row.id}.")
    }

    // ── Items ───────────────────────────────────────────────────────────────

    /** One unit of the reward from the same factory the admin give commands use; null if it can't be built any more. */
    private fun buildUnit(kind: Kind, param: String): ItemStack? = when (kind) {
        Kind.TOKENS -> plugin.itemManager.getItem("token")?.createItemStack(1)
        Kind.CRATE_KEY -> plugin.crateManager.createKeyStack(plugin.crateManager.resolveCrateId(param), 1)
        Kind.CREDIT_VOUCHER -> param.toIntOrNull()?.takeIf { it > 0 }?.let { plugin.creditVoucherManager.createVoucherStack(it) }
        Kind.RANK_VOUCHER -> plugin.rankVoucherManager.createVoucherStack(param)
        Kind.MONEY_VOUCHER -> plugin.physicalVoucherManager.createVoucherStack(param, 1)
        Kind.CHAT_TAG_VOUCHER -> plugin.chatTagVoucherManager.createVoucherStack(param)
        else -> null
    }

    private fun canFitOne(player: Player, item: ItemStack): Boolean {
        // Mod Mode deliveries go to the saved inventory; depositItemSafely reports when that is full.
        if (plugin.modModeManager.isModMode(player)) return true
        return player.inventory.storageContents.any { slot ->
            slot == null || slot.type.isAir || (slot.isSimilar(item) && slot.amount < slot.maxStackSize)
        }
    }

    private fun rewardLabel(kind: Kind, param: String): String = when (kind) {
        Kind.COINS -> "$param Coins"
        Kind.CREDITS -> "$param Credits"
        Kind.TOKENS -> "Token"
        Kind.CRATE_KEY -> plugin.crateManager.getCrate(plugin.crateManager.resolveCrateId(param))?.keyName ?: "$param key"
        Kind.CREDIT_VOUCHER -> "$param Credit Voucher"
        Kind.RANK_VOUCHER -> "${DonorTiers.displayName(param)} Rank Voucher"
        Kind.MONEY_VOUCHER -> "$param voucher"
        Kind.CHAT_TAG_VOUCHER -> "$param Chat Tag Voucher"
        Kind.RANK -> "${DonorTiers.displayName(param)} rank"
    }

    private fun noticeFull(player: Player, row: Delivery, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = lastFullNotice[player.uniqueId]
        if (!force && last != null && now - last < FULL_NOTICE_COOLDOWN_MS) return
        lastFullNotice[player.uniqueId] = now
        val kind = row.kind ?: return
        notify(player, Component.text("Your inventory is full. ", NamedTextColor.YELLOW)
            .append(Component.text("${row.remaining}x ${rewardLabel(kind, row.param)}", NamedTextColor.AQUA))
            .append(Component.text(" from your store purchase is waiting. Free up space and it arrives within a minute.", NamedTextColor.YELLOW)))
    }

    private fun notify(player: Player, message: Component) {
        plugin.commsManager.send(player, message, CommunicationsManager.Category.ECONOMY)
    }

    // ── Join and periodic passes ────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        // Let LuckPerms and the other join handlers (Mod Mode restore, resource pack) settle first.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) deliverFor(player)
        }, JOIN_DELAY_TICKS)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastFullNotice.remove(event.player.uniqueId)
    }

    /** Loads [player]'s PENDING rows off the main thread, then delivers them on it. */
    private fun deliverFor(player: Player) {
        val uuid = player.uniqueId
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val rows = try {
                store.pendingFor(uuid, BATCH_LIMIT)
            } catch (e: Exception) {
                plugin.logger.warning("[Purchases] Could not load pending deliveries for $uuid: ${e.message}")
                return@Runnable
            }
            if (rows.isNotEmpty()) Bukkit.getScheduler().runTask(plugin, Runnable {
                if (player.isOnline) processAll(rows, includeRanks = true)
            })
        })
    }

    /**
     * Every minute: currency rows for known players (online or not) and item rows for
     * online players, e.g. once someone frees inventory space. Ranks only run on join, on
     * fulfill and on an operator retry, so a LuckPerms problem isn't retried in a loop.
     */
    private fun sweep() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val rows = try {
                store.pendingWithRecipient(SWEEP_LIMIT)
            } catch (e: Exception) {
                plugin.logger.warning("[Purchases] Sweep query failed: ${e.message}")
                return@Runnable
            }
            if (rows.isNotEmpty()) Bukkit.getScheduler().runTask(plugin, Runnable { processAll(rows, includeRanks = false) })
        })
    }

    /** Each row goes through its own conditional claim, so overlapping passes can't deliver one twice. */
    private fun processAll(rows: List<Delivery>, includeRanks: Boolean) {
        for (row in rows) {
            try {
                process(row, includeRanks)
            } catch (e: Exception) {
                plugin.logger.severe("[Purchases] Processing #${row.id} for ${row.recipientName} threw: ${e.message}")
            }
        }
    }

    // ── Operator recovery ───────────────────────────────────────────────────

    /** FAILED / INTERRUPTED → PENDING (only what is still owed), then delivers what it can now. */
    fun retry(id: Int): Delivery? {
        if (!store.retry(id)) return null
        val row = store.get(id) ?: return null
        process(row)
        return store.get(id)
    }

    fun cancel(id: Int, reason: String): Boolean = store.cancel(id, reason)

    /** UNRESOLVED → PENDING for an operator-verified recipient, then delivers what it can now. */
    fun assign(id: Int, uuid: UUID, name: String): Delivery? {
        val row = store.get(id) ?: return null
        val spec = row.spec ?: return null
        val changed = try {
            store.assign(id, uuid, name, PurchaseRewards.dedupeKey(row.externalRef, uuid.toString(), spec))
        } catch (e: Exception) {
            plugin.logger.warning("[Purchases] Assigning #$id to $name ($uuid) conflicts with an existing delivery: ${e.message}")
            return null
        }
        if (!changed) return null
        store.get(id)?.let { process(it) }
        return store.get(id)
    }

    companion object {
        private const val JOIN_DELAY_TICKS = 100L
        private const val STARTUP_SWEEP_DELAY_TICKS = 200L
        private const val SWEEP_INTERVAL_TICKS = 20L * 60
        private const val RANK_LOCK_RETRY_TICKS = 20L * 15
        private const val BATCH_LIMIT = 100
        private const val SWEEP_LIMIT = 200
        private const val MAX_CURRENCY_ATTEMPTS = 10
        private const val MAX_RANK_ATTEMPTS = 3
        private const val FULL_NOTICE_COOLDOWN_MS = 5 * 60 * 1000L
    }
}
