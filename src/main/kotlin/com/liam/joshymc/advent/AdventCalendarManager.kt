package com.liam.joshymc.advent

import com.liam.joshymc.Joshymc
import com.liam.joshymc.advent.AdventRules.Availability
import com.liam.joshymc.advent.AdventRules.DayState
import com.liam.joshymc.advent.AdventRules.RewardLine
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.PurchaseFulfillmentManager
import com.liam.joshymc.manager.PurchaseRewards
import com.liam.joshymc.tutorial.TutorialRules
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitTask
import java.io.InputStreamReader
import java.text.DecimalFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.logging.Level

/**
 * The Christmas Advent Calendar (issue #1424): 25 presents from December 1 to 25, one claim per
 * player per day per gamemode per year, separate calendars for Survival and Lifesteal.
 *
 * - Settings: `advent-calendar.yml` ([AdventConfig]). `enabled: false` (or an invalid file) closes
 *   it completely; stored claims and queued deliveries are kept and still delivered.
 * - "Today" is computed from the clock in the event timezone whenever someone looks, so there is
 *   no timer that has to fire at midnight ([AdventRules.availability]).
 * - A claim is one [AdventStore] transaction (Credits for a catch-up day, the claim row and the
 *   reward snapshot). Rewards then go through the store-delivery queue
 *   ([PurchaseFulfillmentManager.enqueue], reference `advent:<row>`): Coins/Credits land even
 *   offline, items arrive when there's room and are never dropped, and a repeat can't grant twice.
 * - A gamemode's calendar only runs where that gamemode's economy lives
 *   ([TutorialRules.economyBelongsTo]), so Survival Coins never land in a Lifesteal balance.
 */
class AdventCalendarManager(private val plugin: Joshymc) : Listener {

    val store = AdventStore(plugin.databaseManager) { uuid, amount -> plugin.creditsManager.withdraw(uuid, amount) }

    @Volatile var settings: AdventSettings? = null
        private set
    @Volatile var problems: List<String> = emptyList()
        private set

    private var sweepTask: BukkitTask? = null
    private var started = false
    /** Players with a claim in progress (main thread only); bounded by the online players. */
    private val busy = HashSet<UUID>()

    fun start() {
        store.createSchema()
        loadSettings()
        Bukkit.getPluginManager().registerEvents(this, plugin)
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sweepReady() }, STARTUP_DELAY_TICKS, SWEEP_INTERVAL_TICKS)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { resolveWaiting(null) }, STARTUP_DELAY_TICKS)
        started = true
    }

    /** `/joshymc reload` and `/advent admin reload`: rereads the file, then retries rewards it may now map. */
    fun reload() {
        if (!started) return
        loadSettings()
        resolveWaiting(null)
    }

    fun stop() {
        sweepTask?.cancel()
        sweepTask = null
        if (started) HandlerList.unregisterAll(this)
        busy.clear()
        started = false
    }

    private fun loadSettings() {
        val result = try {
            val file = plugin.configFile(FILE_NAME)
            if (!file.exists()) plugin.saveResource(FILE_NAME, false)
            val yaml = YamlConfiguration()
            yaml.load(file)
            val bundled = plugin.getResource(FILE_NAME)?.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
            AdventConfig.parse(object : AdventConfig.Source {
                // Only scalar settings fall back to the bundled file; reward calendars and placeholders never merge.
                override fun get(path: String): Any? {
                    val own = yaml.get(path, null)
                    if (own != null || path.startsWith("rewards.") || path.startsWith("placeholders.")) return own
                    return bundled?.get(path, null)
                }
                override fun keys(path: String): List<String>? = (yaml.get(path, null) as? ConfigurationSection)?.getKeys(false)?.toList()
            })
        } catch (e: Exception) {
            AdventConfig.Result(null, listOf("Could not read $FILE_NAME: ${e.message}"))
        }
        problems = result.problems
        settings = result.settings
        result.problems.forEach { plugin.logger.severe("[Advent] $FILE_NAME: $it") }
        val s = result.settings
        when {
            s == null -> plugin.logger.severe("[Advent] $FILE_NAME is invalid: the Advent Calendar stays closed until it is fixed (claims and deliveries are kept). Fix it, then /advent admin reload.")
            !s.enabled -> plugin.logger.info("[Advent] Disabled in $FILE_NAME (enabled: false). Claims are kept and claimed rewards are still delivered.")
            else -> {
                val unmapped = s.placeholders.filterValues { it.isBlank() }.keys
                if (unmapped.isNotEmpty()) plugin.logger.info("[Advent] Placeholders not set up yet (claimed copies wait until mapped): ${unmapped.joinToString(", ")}.")
            }
        }
    }

    // ── Who sees which calendar ──────────────────────────────

    /** The gamemode whose calendar [player] uses here, or null when none runs for them on this server. */
    fun gamemodeFor(player: Player): ServerGamemode? {
        val gm = plugin.gamemodeLockManager.gamemodeOf(player)
        return gm.takeIf { runsHere(it) }
    }

    /** That gamemode's economy (Coins/Credits) lives on this server. */
    fun runsHere(gm: ServerGamemode): Boolean =
        TutorialRules.economyBelongsTo(gm, plugin.networkIdentityManager.hostedGamemodes)

    fun availability(now: Instant = Instant.now()): Availability {
        val s = settings ?: return Availability.Disabled
        return AdventRules.availability(s.enabled, s.schedule, now)
    }

    /** Why [player] can't open the calendar right now, as a message; null when they can. */
    fun unavailableMessage(player: Player): Component? {
        val s = settings ?: return legacy(AdventConfig.DEFAULT_TEXTS.disabled)
        val gm = gamemodeFor(player) ?: return legacy("&cThe Advent Calendar isn't available in this gamemode on this server.")
        val calendar = s.calendar(gm)
        if (calendar == null || !calendar.enabled) return legacy(s.texts.disabled)
        return when (val a = availability()) {
            Availability.Disabled -> legacy(s.texts.disabled)
            is Availability.Closed -> when (a.reason) {
                AdventRules.ClosedReason.NOT_STARTED -> legacy(s.texts.notStarted.replace("{date}", dateText(a.opensAt)))
                AdventRules.ClosedReason.ENDED -> legacy(s.texts.ended.replace("{date}", dateText(a.opensAt)))
                AdventRules.ClosedReason.MANUAL_SCHEDULE -> legacy(s.texts.closed)
            }
            is Availability.Open -> if (calendar.rewardsFor(a.year) == null) legacy("&cThe ${a.year} Advent Calendar isn't set up yet.") else null
        }
    }

    // ── The view the menu draws ─────────────────────────────

    data class DayView(val day: Int, val state: DayState, val price: Double?, val rewards: List<String>, val waiting: Boolean)

    data class CalendarView(
        val gamemode: ServerGamemode,
        val year: Int,
        val today: Int,
        val manual: Boolean,
        val nextUnlockAt: java.time.ZonedDateTime?,
        val catchUpEnabled: Boolean,
        val catchUpCost: Double,
        val days: List<DayView>,
    )

    /** Null when [player] can't open it (see [unavailableMessage]). Main thread. */
    fun view(player: Player): CalendarView? {
        if (unavailableMessage(player) != null) return null
        val s = settings ?: return null
        val gm = gamemodeFor(player) ?: return null
        val open = availability() as? Availability.Open ?: return null
        val rewards = s.calendar(gm)?.rewardsFor(open.year) ?: return null
        val claimed = store.claimedDays(gm.id, open.year, player.uniqueId)
        val passes = store.freePassDays(gm.id, open.year, player.uniqueId)
        val days = (1..AdventRules.DAYS).map { day ->
            val state = AdventRules.dayState(day, open.today, day in claimed, s.catchUpEnabled, day in passes)
            val lines = rewards[day].orEmpty()
            DayView(
                day, state, AdventRules.price(state, s.catchUpCost),
                lines.map { describe(it, open.year) },
                lines.any { resolve(it, open.year).state == AdventStore.RewardState.WAITING },
            )
        }
        return CalendarView(gm, open.year, open.today, open.manual, open.nextUnlockAt, s.catchUpEnabled, s.catchUpCost, days)
    }

    // ── Claiming ─────────────────────────────────────────────

    sealed interface ClaimOutcome {
        data class Done(val day: Int, val rewards: List<String>, val waiting: Boolean) : ClaimOutcome
        data class Refused(val message: String) : ClaimOutcome
    }

    /**
     * Claims [day] of [year] for [player] at the price the menu showed ([expectedPrice]). The menu
     * is never trusted: availability, the day's state and the price are recomputed here and again
     * inside the store's transaction. Main thread.
     */
    fun claim(player: Player, year: Int, day: Int, expectedPrice: Double): ClaimOutcome {
        unavailableMessage(player)?.let { return ClaimOutcome.Refused(plain(it)) }
        val s = settings ?: return ClaimOutcome.Refused("The Advent Calendar is closed.")
        val gm = gamemodeFor(player) ?: return ClaimOutcome.Refused("The Advent Calendar isn't available here.")
        val open = availability() as? Availability.Open ?: return ClaimOutcome.Refused("The Advent Calendar is closed.")
        if (open.year != year) return ClaimOutcome.Refused("That calendar is no longer open.")
        if (day !in 1..AdventRules.DAYS) return ClaimOutcome.Refused("There is no present for that day.")
        if (day > open.today) return ClaimOutcome.Refused("That present is still locked. It opens on ${dateText(s.schedule.unlockAt(year, day))}.")
        if (day < open.today && !s.catchUpEnabled && day !in store.freePassDays(gm.id, year, player.uniqueId)) {
            return ClaimOutcome.Refused("Missed presents can't be recovered right now.")
        }
        val lines = s.calendar(gm)?.rewardsFor(year)?.get(day) ?: return ClaimOutcome.Refused("That present isn't set up.")

        val uuid = player.uniqueId
        if (!busy.add(uuid)) return ClaimOutcome.Refused("Your last present is still being opened.")
        try {
            val snapshot = lines.map { resolve(it, year) }
            val result = try {
                store.claim(
                    AdventStore.ClaimRequest(
                        gamemode = gm.id, year = year, day = day, uuid = uuid, playerName = player.name,
                        isToday = day == open.today, catchUpEnabled = s.catchUpEnabled, catchUpCost = s.catchUpCost,
                        expectedPrice = expectedPrice, rewards = snapshot, now = System.currentTimeMillis(),
                    )
                )
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "[Advent] Claim of ${gm.id} $year day $day by ${player.name} failed; nothing was claimed or charged", e)
                return ClaimOutcome.Refused("Couldn't open that present right now (nothing was claimed or charged). Try again.")
            }
            return when (result) {
                is AdventStore.ClaimResult.Claimed -> {
                    plugin.logger.info("[Advent] ${player.name} claimed ${gm.id} $year day $day (${result.claim.type.name}, ${result.claim.creditsPaid} Credits).")
                    result.rewards.filter { it.state == AdventStore.RewardState.READY }.forEach { deliver(it) }
                    val labels = lines.map { describe(it, year) }
                    val waiting = result.rewards.any { it.state == AdventStore.RewardState.WAITING }
                    player.playSound(player.location, if (day == AdventRules.DAYS) Sound.UI_TOAST_CHALLENGE_COMPLETE else Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f)
                    val template = if (day == AdventRules.DAYS) s.texts.christmas else s.texts.claimed
                    plugin.commsManager.send(player, legacy(template.replace("{day}", day.toString()).replace("{rewards}", labels.joinToString(", "))), CommunicationsManager.Category.EVENT)
                    if (waiting) {
                        plugin.commsManager.send(player, legacy("&7Some of this present isn't ready on the server yet. It's saved for you and arrives automatically once it is."), CommunicationsManager.Category.EVENT)
                    }
                    ClaimOutcome.Done(day, labels, waiting)
                }
                AdventStore.ClaimResult.AlreadyClaimed -> ClaimOutcome.Refused("You already opened that present.")
                AdventStore.ClaimResult.NotRecoverable -> ClaimOutcome.Refused("Missed presents can't be recovered right now.")
                is AdventStore.ClaimResult.PriceChanged -> ClaimOutcome.Refused("That present's price changed to ${credits(result.price)}. Nothing was charged; open it again to see the new price.")
                is AdventStore.ClaimResult.InsufficientCredits -> ClaimOutcome.Refused("You need ${credits(result.price)} to recover that present (you have ${credits(plugin.creditsManager.getBalance(uuid))}).")
            }
        } finally {
            busy.remove(uuid)
        }
    }

    // ── Rewards: snapshot, delivery, waiting rows ────────────

    /** What a reward line becomes in the claim snapshot: READY when the server can build it now. */
    fun resolve(line: RewardLine, year: Int): AdventStore.NewReward = when (line) {
        is RewardLine.Grant -> {
            val problem = buildProblem(line.spec)
            AdventStore.NewReward(line.spec.kind.id, line.spec.param, line.spec.quantity, describe(line, year),
                if (problem == null) AdventStore.RewardState.READY else AdventStore.RewardState.WAITING, problem)
        }
        is RewardLine.Pending -> {
            val id = AdventRules.expandYear(line.placeholderId, year)
            val label = AdventRules.expandYear(line.label, year)
            when (val spec = placeholderSpec(id, line.quantity)) {
                is AdventRules.Parsed.Ok -> {
                    val problem = buildProblem(spec.value)
                    if (problem == null) AdventStore.NewReward(spec.value.kind.id, spec.value.param, spec.value.quantity, label, AdventStore.RewardState.READY, null)
                    else AdventStore.NewReward(AdventStore.PENDING_KIND, id, line.quantity, label, AdventStore.RewardState.WAITING, problem)
                }
                is AdventRules.Parsed.Error -> AdventStore.NewReward(AdventStore.PENDING_KIND, id, line.quantity, label, AdventStore.RewardState.WAITING,
                    "placeholder '$id' ${spec.message}")
            }
        }
    }

    private fun placeholderSpec(id: String, quantity: Int): AdventRules.Parsed<PurchaseRewards.Spec> {
        val mapping = settings?.placeholders?.get(id)
        if (mapping.isNullOrBlank()) return AdventRules.Parsed.Error("is not set up yet (advent-calendar.yml > placeholders.$id)")
        return AdventRules.resolvePlaceholder(mapping, quantity)
    }

    /** Null when this server can build [spec] now; otherwise why not. */
    private fun buildProblem(spec: PurchaseRewards.Spec): String? {
        val catalog = plugin.purchaseFulfillmentManager.rewardCatalog
        return when (spec.kind) {
            PurchaseRewards.Kind.CRATE_KEY -> if (catalog.crateExists(spec.param)) null else "crate '${spec.param}' doesn't exist on this server"
            PurchaseRewards.Kind.CHAT_TAG_VOUCHER -> if (catalog.chatTagVoucherExists(spec.param)) null else "chat tag voucher '${spec.param}' doesn't exist on this server"
            else -> null
        }
    }

    /** Hands a READY row to the delivery queue; repeating it is harmless (UNIQUE reference). */
    private fun deliver(row: AdventStore.RewardRow): Boolean {
        val spec = row.spec ?: return false
        buildProblem(spec)?.let { problem ->
            store.backToWaiting(row.id, problem, System.currentTimeMillis())
            return false
        }
        val result = try {
            plugin.purchaseFulfillmentManager.enqueue(row.uuid, row.playerName, spec, reference = "$REFERENCE_PREFIX${row.id}",
                source = "${PurchaseFulfillmentManager.ADVENT_SOURCE_PREFIX}${row.gamemode}:${row.year}:${row.day}")
        } catch (e: Exception) {
            plugin.logger.severe("[Advent] Queueing reward #${row.id} (${spec.describe()} for ${row.playerName}) threw: ${e.message}; retried within a minute.")
            return false
        }
        val delivery = when (result) {
            is PurchaseFulfillmentManager.FulfillResult.Recorded -> result.delivery
            is PurchaseFulfillmentManager.FulfillResult.Duplicate -> result.existing
            else -> {
                plugin.logger.warning("[Advent] Reward #${row.id} (${spec.describe()} for ${row.playerName}) couldn't be queued: $result; retried within a minute.")
                return false
            }
        }
        try {
            store.markQueued(row.id, delivery.id.toLong(), System.currentTimeMillis())
        } catch (e: Exception) {
            plugin.logger.warning("[Advent] Reward #${row.id} was queued as delivery #${delivery.id} but couldn't be marked: ${e.message} (the next pass finds the same delivery).")
        }
        return true
    }

    private fun hostedCalendarGamemodes(): List<ServerGamemode> = ServerGamemode.entries.filter { runsHere(it) }

    /** Every minute: READY rows a crash or a queue error left behind. Runs while the calendar is disabled too. */
    private fun sweepReady() {
        for (gm in hostedCalendarGamemodes()) {
            val rows = try { store.rewardsIn(gm.id, AdventStore.RewardState.READY, null) } catch (e: Exception) {
                plugin.logger.warning("[Advent] Could not list undelivered rewards: ${e.message}")
                return
            }
            rows.forEach { deliver(it) }
        }
    }

    /**
     * WAITING rows ([uuid] = only theirs) whose reward can now be built (a placeholder got mapped, a
     * crate or tag was added) become READY and are queued. At startup, on reload and on join.
     */
    fun resolveWaiting(uuid: UUID?): Int {
        var resolved = 0
        for (gm in hostedCalendarGamemodes()) {
            val rows = try { store.rewardsIn(gm.id, AdventStore.RewardState.WAITING, uuid, if (uuid == null) 2000 else 200) } catch (e: Exception) {
                plugin.logger.warning("[Advent] Could not list waiting rewards: ${e.message}")
                return resolved
            }
            for (row in rows) {
                val spec = if (row.kind == AdventStore.PENDING_KIND) {
                    when (val p = placeholderSpec(row.param, row.quantity)) {
                        is AdventRules.Parsed.Ok -> p.value
                        is AdventRules.Parsed.Error -> null
                    }
                } else row.spec
                if (spec == null || buildProblem(spec) != null) continue
                if (store.resolveWaiting(row.id, spec, System.currentTimeMillis())) {
                    resolved++
                    store.reward(row.id)?.let { deliver(it) }
                }
            }
        }
        if (resolved > 0) plugin.logger.info("[Advent] $resolved waiting reward(s) can now be delivered and were queued.")
        return resolved
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            try {
                resolveWaiting(player.uniqueId)
                for (gm in hostedCalendarGamemodes()) store.rewardsIn(gm.id, AdventStore.RewardState.READY, player.uniqueId).forEach { deliver(it) }
            } catch (e: Exception) {
                plugin.logger.warning("[Advent] Join pass for ${player.name} failed: ${e.message}")
            }
        }, JOIN_DELAY_TICKS)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        busy.remove(event.player.uniqueId)
    }

    // ── Staff tools ─────────────────────────────────────────

    sealed interface ReissueOutcome {
        data class Queued(val deliveryId: Int) : ReissueOutcome
        data class Retried(val deliveryId: Int, val state: String) : ReissueOutcome
        data class Nothing(val reason: String) : ReissueOutcome
    }

    /**
     * Repairs one reward row without ever granting it twice: WAITING is re-resolved, READY is queued
     * (UNIQUE reference), QUEUED retries its delivery only if it is FAILED/INTERRUPTED, through the
     * delivery queue's own retry (it resends only what is still owed).
     */
    fun reissue(rewardId: Long, actor: String): ReissueOutcome {
        val row = store.reward(rewardId) ?: return ReissueOutcome.Nothing("No Advent reward #$rewardId.")
        val now = System.currentTimeMillis()
        return when (row.state) {
            AdventStore.RewardState.WAITING -> {
                resolveWaiting(row.uuid)
                val after = store.reward(rewardId)
                if (after?.state == AdventStore.RewardState.QUEUED && after.deliveryId != null) {
                    store.audit(now, row.gamemode, actor, "REISSUE", row.uuid.toString(), "#$rewardId waiting -> delivery #${after.deliveryId}")
                    ReissueOutcome.Queued(after.deliveryId.toInt())
                } else ReissueOutcome.Nothing("Still waiting: ${after?.waitingReason ?: row.waitingReason ?: "not buildable yet"}.")
            }
            AdventStore.RewardState.READY -> {
                if (!deliver(row)) return ReissueOutcome.Nothing("Couldn't queue it: ${store.reward(rewardId)?.waitingReason ?: "see the console"}.")
                val id = store.reward(rewardId)?.deliveryId?.toInt() ?: return ReissueOutcome.Nothing("Queued, but the delivery id wasn't recorded.")
                store.audit(now, row.gamemode, actor, "REISSUE", row.uuid.toString(), "#$rewardId ready -> delivery #$id")
                ReissueOutcome.Queued(id)
            }
            AdventStore.RewardState.QUEUED -> {
                val deliveryId = row.deliveryId?.toInt() ?: return ReissueOutcome.Nothing("Reward #$rewardId has no delivery recorded.")
                val delivery = plugin.purchaseFulfillmentManager.store.get(deliveryId) ?: return ReissueOutcome.Nothing("Delivery #$deliveryId doesn't exist.")
                if (delivery.state.name !in setOf("FAILED", "INTERRUPTED")) {
                    return ReissueOutcome.Nothing("Delivery #$deliveryId is ${delivery.state.name}; only FAILED or INTERRUPTED deliveries can be retried.")
                }
                val after = plugin.purchaseFulfillmentManager.retry(deliveryId) ?: return ReissueOutcome.Nothing("Delivery #$deliveryId couldn't be retried.")
                store.audit(now, row.gamemode, actor, "REISSUE", row.uuid.toString(), "#$rewardId retried delivery #$deliveryId -> ${after.state.name}")
                ReissueOutcome.Retried(deliveryId, after.state.name)
            }
        }
    }

    // ── Text helpers ─────────────────────────────────────────

    fun describe(line: RewardLine, year: Int): String = when (line) {
        is RewardLine.Grant -> describe(line.spec)
        is RewardLine.Pending -> (if (line.quantity > 1) "${line.quantity}x " else "") + AdventRules.expandYear(line.label, year)
    }

    fun describe(spec: PurchaseRewards.Spec): String = when (spec.kind) {
        PurchaseRewards.Kind.COINS -> "${NUMBER.format(spec.param.toDouble())} Coins"
        PurchaseRewards.Kind.CREDITS -> "${NUMBER.format(spec.param.toDouble())} Credits"
        PurchaseRewards.Kind.TOKENS -> "${spec.quantity} Token${if (spec.quantity == 1) "" else "s"}"
        PurchaseRewards.Kind.CRATE_KEY -> {
            val crate = plugin.crateManager.getCrate(plugin.crateManager.resolveCrateId(spec.param))
            val name = crate?.keyName?.let { plain(legacy(it)) } ?: "${pretty(spec.param)} Crate Key"
            "${spec.quantity}x ${if (spec.quantity == 1) name else plural(name)}"
        }
        PurchaseRewards.Kind.CHAT_TAG_VOUCHER -> (if (spec.quantity > 1) "${spec.quantity}x " else "") + "${pretty(spec.param)} Chat Tag"
        else -> spec.describe()
    }

    private fun plural(name: String) = if (name.endsWith("s")) name else "${name}s"

    private fun pretty(id: String) = id.split('_', '-').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }

    fun credits(amount: Double) = "${plugin.creditsManager.format(amount)} Credits"

    fun dateText(at: java.time.ZonedDateTime?): String = at?.format(DATE) ?: "a date staff will announce"

    fun legacy(text: String): Component = plugin.commsManager.parseLegacy(text)

    fun plain(c: Component): String = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c)

    companion object {
        const val FILE_NAME = "advent-calendar.yml"
        /** Delivery queue reference of reward row N: `advent:N` (UNIQUE there). */
        const val REFERENCE_PREFIX = "advent:"
        private const val STARTUP_DELAY_TICKS = 20L * 15
        private const val SWEEP_INTERVAL_TICKS = 20L * 60
        private const val JOIN_DELAY_TICKS = 20L * 6
        private val NUMBER = DecimalFormat("#,##0.##")
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a z", Locale.US)
    }
}
