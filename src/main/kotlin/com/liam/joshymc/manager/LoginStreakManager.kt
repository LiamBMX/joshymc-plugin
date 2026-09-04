package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.scheduler.BukkitTask
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Daily Login Streak — reuses [EconomyManager] for the $100K daily reward,
 * [CreditsManager] for the weekly-bonus Credits, and [CrateManager] for the
 * weekly-bonus random crate key. Playtime is tracked independently of
 * [PlaytimeManager] (that manager only stores lifetime totals, not "today's
 * eligible playtime"), using [AFKManager.isAfk] so AFK time never counts.
 *
 * State is cached in-memory per online player and flushed to SQLite on
 * important events (join/quit/completion) plus a routine accrual tick, so a
 * restart never rewinds progress by more than the accrual interval.
 */
class LoginStreakManager(private val plugin: Joshymc) : Listener {

    private data class StreakState(
        var currentStreak: Int,
        var longestStreak: Int,
        var lastCompletedDate: LocalDate?,
        var progressDate: LocalDate,
        var todaySeconds: Long,
        var todayCompleted: Boolean,
        var graceAvailable: Boolean,
        var graceRechargeProgress: Int
    )

    var enabled = false
        private set

    private var zone: ZoneId = ZoneId.of(DEFAULT_TIMEZONE)
    private var requiredMinutes = 30
    private var dailyRewardAmount = 100000.0
    private var weeklyBonusEnabled = true
    private var weeklyBonusEveryDays = 7
    private var weeklyBonusCredits = 100.0
    private var eligibleKeys: List<String> = DEFAULT_ELIGIBLE_KEYS
    private var graceEnabled = true
    private var graceRechargeDays = 7
    private var guiTitle = "Daily Login Streak"
    private var guiRefreshSeconds = 2

    private val cache = ConcurrentHashMap<UUID, StreakState>()
    private val trackedGuiPlayers = mutableSetOf<UUID>()

    private var accrualTask: BukkitTask? = null
    private var guiTask: BukkitTask? = null

    private val FILLER = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    fun start() {
        loadConfig()

        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS login_streaks (
                uuid TEXT PRIMARY KEY,
                current_streak INTEGER NOT NULL DEFAULT 0,
                longest_streak INTEGER NOT NULL DEFAULT 0,
                last_completed_date TEXT,
                progress_date TEXT NOT NULL,
                today_seconds INTEGER NOT NULL DEFAULT 0,
                today_completed INTEGER NOT NULL DEFAULT 0,
                grace_available INTEGER NOT NULL DEFAULT 1,
                grace_recharge_progress INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        cache.clear()
        trackedGuiPlayers.clear()
        for (player in Bukkit.getOnlinePlayers()) {
            cache[player.uniqueId] = loadOrCreate(player.uniqueId)
        }

        accrualTask?.cancel()
        accrualTask = Bukkit.getScheduler().runTaskTimer(
            plugin, Runnable { tickAccrual() }, ACCRUAL_INTERVAL_SECONDS * 20L, ACCRUAL_INTERVAL_SECONDS * 20L
        )
        guiTask?.cancel()
        val refreshTicks = (guiRefreshSeconds * 20L).coerceAtLeast(20L)
        guiTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickGui() }, refreshTicks, refreshTicks)

        plugin.logger.info("[LoginStreak] LoginStreakManager started.")
    }

    fun stop() {
        enabled = false
        accrualTask?.cancel(); accrualTask = null
        guiTask?.cancel(); guiTask = null
        for ((uuid, state) in cache) persist(state, uuid)
        cache.clear()
        trackedGuiPlayers.clear()
    }

    fun reloadSettings() {
        stop()
        start()
    }

    private fun loadConfig() {
        enabled = plugin.config.getBoolean("login-streaks.enabled", true)
        zone = runCatching { ZoneId.of(plugin.config.getString("login-streaks.timezone", DEFAULT_TIMEZONE)) }
            .getOrDefault(ZoneId.of(DEFAULT_TIMEZONE))
        requiredMinutes = plugin.config.getInt("login-streaks.required-playtime-minutes", 30).coerceAtLeast(1)
        dailyRewardAmount = plugin.config.getDouble("login-streaks.daily-reward.amount", 100000.0)
        weeklyBonusEnabled = plugin.config.getBoolean("login-streaks.weekly-bonus.enabled", true)
        weeklyBonusEveryDays = plugin.config.getInt("login-streaks.weekly-bonus.every-days", 7).coerceAtLeast(1)
        weeklyBonusCredits = plugin.config.getDouble("login-streaks.weekly-bonus.credits", 100.0)
        eligibleKeys = plugin.config.getStringList("login-streaks.weekly-bonus.eligible-keys")
            .takeIf { it.isNotEmpty() } ?: DEFAULT_ELIGIBLE_KEYS
        graceEnabled = plugin.config.getBoolean("login-streaks.grace.enabled", true)
        graceRechargeDays = plugin.config.getInt("login-streaks.grace.recharge-completed-days", 7).coerceAtLeast(1)
        guiTitle = plugin.config.getString("login-streaks.gui.title", "Daily Login Streak") ?: "Daily Login Streak"
        guiRefreshSeconds = plugin.config.getInt("login-streaks.gui.refresh-seconds", 2).coerceIn(1, 20)
    }

    private fun requiredSeconds(): Long = requiredMinutes * 60L

    // ── Persistence ────────────────────────────────────────

    private fun loadOrCreate(uuid: UUID): StreakState {
        val row = plugin.databaseManager.queryFirst(
            "SELECT current_streak, longest_streak, last_completed_date, progress_date, today_seconds, today_completed, grace_available, grace_recharge_progress FROM login_streaks WHERE uuid = ?",
            uuid.toString()
        ) { rs ->
            StreakState(
                currentStreak = rs.getInt("current_streak"),
                longestStreak = rs.getInt("longest_streak"),
                lastCompletedDate = rs.getString("last_completed_date")?.let { LocalDate.parse(it) },
                progressDate = LocalDate.parse(rs.getString("progress_date")),
                todaySeconds = rs.getLong("today_seconds"),
                todayCompleted = rs.getInt("today_completed") != 0,
                graceAvailable = rs.getInt("grace_available") != 0,
                graceRechargeProgress = rs.getInt("grace_recharge_progress")
            )
        }
        return row ?: StreakState(
            currentStreak = 0,
            longestStreak = 0,
            lastCompletedDate = null,
            progressDate = LocalDate.now(zone),
            todaySeconds = 0,
            todayCompleted = false,
            graceAvailable = true,
            graceRechargeProgress = 0
        )
    }

    private fun persist(state: StreakState, uuid: UUID) {
        plugin.databaseManager.execute(
            "INSERT INTO login_streaks (uuid, current_streak, longest_streak, last_completed_date, progress_date, today_seconds, today_completed, grace_available, grace_recharge_progress) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(uuid) DO UPDATE SET current_streak = excluded.current_streak, longest_streak = excluded.longest_streak, " +
                "last_completed_date = excluded.last_completed_date, progress_date = excluded.progress_date, today_seconds = excluded.today_seconds, " +
                "today_completed = excluded.today_completed, grace_available = excluded.grace_available, grace_recharge_progress = excluded.grace_recharge_progress",
            uuid.toString(), state.currentStreak, state.longestStreak, state.lastCompletedDate?.toString(),
            state.progressDate.toString(), state.todaySeconds, if (state.todayCompleted) 1 else 0,
            if (state.graceAvailable) 1 else 0, state.graceRechargeProgress
        )
    }

    /** Applies an admin mutation by uuid; only keeps the result cached if the player was already tracked (online). */
    private fun mutate(uuid: UUID, block: (StreakState) -> Unit) {
        val existing = cache[uuid]
        val state = existing ?: loadOrCreate(uuid)
        block(state)
        if (existing != null) cache[uuid] = state
        persist(state, uuid)
    }

    // ── Public accessors ──────────────────────────────────

    /** Current/longest streak, reading from the live cache if the player is online or SQLite otherwise. */
    fun getStreakInfo(uuid: UUID): Pair<Int, Int> {
        cache[uuid]?.let { return it.currentStreak to it.longestStreak }
        val row = plugin.databaseManager.queryFirst(
            "SELECT current_streak, longest_streak FROM login_streaks WHERE uuid = ?", uuid.toString()
        ) { rs -> rs.getInt("current_streak") to rs.getInt("longest_streak") }
        return row ?: (0 to 0)
    }

    fun setCurrentStreak(uuid: UUID, amount: Int) = mutate(uuid) { state ->
        state.currentStreak = amount.coerceAtLeast(0)
        if (state.currentStreak > state.longestStreak) state.longestStreak = state.currentStreak
    }

    fun resetCurrentStreak(uuid: UUID) = mutate(uuid) { state ->
        state.currentStreak = 0
        state.lastCompletedDate = null
    }

    fun setLongestStreak(uuid: UUID, amount: Int) = mutate(uuid) { state ->
        state.longestStreak = amount.coerceAtLeast(0)
    }

    fun setGrace(uuid: UUID, available: Boolean) = mutate(uuid) { state ->
        state.graceAvailable = available
        if (available) state.graceRechargeProgress = 0
    }

    /** Admin-only: instantly satisfies today's playtime requirement. Requires the target online (crate key needs an inventory). */
    fun forceCompleteToday(player: Player): Boolean {
        val uuid = player.uniqueId
        val state = cache.getOrPut(uuid) { loadOrCreate(uuid) }
        ensureCurrentDay(state, player)
        if (state.todayCompleted) return false
        state.todaySeconds = requiredSeconds()
        checkCompletion(player, uuid, state)
        persist(state, uuid)
        return true
    }

    /** Admin-only: sets today's accumulated eligible playtime. Crossing the requirement completes the day. */
    fun setTodayPlaytimeMinutes(player: Player, minutes: Int) {
        val uuid = player.uniqueId
        val state = cache.getOrPut(uuid) { loadOrCreate(uuid) }
        ensureCurrentDay(state, player)
        state.todaySeconds = minutes.coerceAtLeast(0) * 60L
        checkCompletion(player, uuid, state)
        persist(state, uuid)
    }

    // ── Day rollover / grace ───────────────────────────────

    /** Returns true if a day boundary was just crossed for this state. */
    private fun ensureCurrentDay(state: StreakState, player: Player?): Boolean {
        val today = LocalDate.now(zone)
        if (state.progressDate == today) return false
        resolveStreakContinuity(state, today, player)
        state.progressDate = today
        state.todaySeconds = 0
        state.todayCompleted = false
        return true
    }

    private fun resolveStreakContinuity(state: StreakState, today: LocalDate, player: Player?) {
        val last = state.lastCompletedDate ?: return
        if (last == today) return
        val gap = ChronoUnit.DAYS.between(last, today)
        if (gap <= 1) return // consecutive — streak intact
        if (gap == 2L && graceEnabled && state.graceAvailable) {
            state.graceAvailable = false
            state.graceRechargeProgress = 0
            if (player != null) notifyGraceUsed(player, state)
        } else {
            breakStreak(state, player)
        }
    }

    private fun breakStreak(state: StreakState, player: Player?) {
        if (state.currentStreak <= 0) return
        val lost = state.currentStreak
        state.currentStreak = 0
        if (player != null) notifyStreakLost(player, lost)
    }

    // ── Completion ─────────────────────────────────────────

    private fun checkCompletion(player: Player, uuid: UUID, state: StreakState) {
        if (state.todayCompleted) return
        if (state.todaySeconds < requiredSeconds()) return

        state.todayCompleted = true
        state.currentStreak += 1
        if (state.currentStreak > state.longestStreak) state.longestStreak = state.currentStreak
        state.lastCompletedDate = state.progressDate

        if (graceEnabled && !state.graceAvailable) {
            state.graceRechargeProgress += 1
            if (state.graceRechargeProgress >= graceRechargeDays) {
                state.graceAvailable = true
                state.graceRechargeProgress = 0
            }
        }

        plugin.economyManager.deposit(uuid, dailyRewardAmount)

        val isWeeklyBonus = weeklyBonusEnabled && state.currentStreak % weeklyBonusEveryDays == 0
        var keyGiven: String? = null
        if (isWeeklyBonus) {
            plugin.creditsManager.deposit(uuid, weeklyBonusCredits)
            keyGiven = giveRandomKey(player)
        }

        notifyCompletion(player, state, isWeeklyBonus, keyGiven)
        refreshGuiIfOpen(player)
    }

    private fun giveRandomKey(player: Player): String? {
        val pool = eligibleKeys.filter { plugin.crateManager.getCrate(it) != null }
        if (pool.isEmpty()) {
            plugin.logger.warning("[LoginStreak] No valid login-streaks.weekly-bonus.eligible-keys configured; skipping crate key reward for ${player.name}.")
            return null
        }
        val chosen = pool.random()
        plugin.crateManager.giveKey(player, chosen, 1)
        return chosen
    }

    // ── Listeners ──────────────────────────────────────────

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (!enabled) return
        val player = event.player
        val uuid = player.uniqueId
        val state = loadOrCreate(uuid)
        if (ensureCurrentDay(state, player)) persist(state, uuid)
        cache[uuid] = state
        notifyJoin(player, state)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        cache[uuid]?.let { persist(it, uuid) }
        cache.remove(uuid)
        trackedGuiPlayers.remove(uuid)
    }

    // ── Accrual tick (shared task, only touches online players) ──

    private fun tickAccrual() {
        if (!enabled) return
        for (player in Bukkit.getOnlinePlayers()) {
            val uuid = player.uniqueId
            val state = cache.getOrPut(uuid) { loadOrCreate(uuid) }
            var changed = ensureCurrentDay(state, player)
            if (!plugin.afkManager.isAfk(player)) {
                state.todaySeconds += ACCRUAL_INTERVAL_SECONDS
                changed = true
                checkCompletion(player, uuid, state)
            }
            if (changed) persist(state, uuid)
        }
    }

    // ── GUI ────────────────────────────────────────────────

    fun openGui(player: Player) {
        val state = cache.getOrPut(player.uniqueId) { loadOrCreate(player.uniqueId) }
        val gui = CustomGui(plugin.commsManager.parseLegacy("&6&l$guiTitle"), 27)
        renderInto(gui, player, state)
        plugin.guiManager.open(player, gui)
        gui.onClose = { trackedGuiPlayers.remove(it.uniqueId) }
        trackedGuiPlayers.add(player.uniqueId)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun tickGui() {
        if (trackedGuiPlayers.isEmpty()) return
        for (uuid in trackedGuiPlayers.toList()) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null) { trackedGuiPlayers.remove(uuid); continue }
            val gui = plugin.guiManager.getOpenGui(player)
            if (gui == null) { trackedGuiPlayers.remove(uuid); continue }
            val state = cache[uuid] ?: continue
            renderInto(gui, player, state)
        }
    }

    private fun refreshGuiIfOpen(player: Player) {
        if (player.uniqueId !in trackedGuiPlayers) return
        val gui = plugin.guiManager.getOpenGui(player) ?: return
        val state = cache[player.uniqueId] ?: return
        renderInto(gui, player, state)
    }

    private fun renderInto(gui: CustomGui, player: Player, state: StreakState) {
        gui.fill(FILLER.clone())

        gui.setItem(4, playerInfoItem(player, state))

        val target = if (state.todayCompleted) state.currentStreak else state.currentStreak + 1
        val nextBonusDay = nextMultiple(target.coerceAtLeast(1), weeklyBonusEveryDays)
        val windowStart = (nextBonusDay - 6).coerceAtLeast(1)
        for (i in 0 until 7) {
            val day = windowStart + i
            val completed = day <= state.currentStreak
            val isToday = !state.todayCompleted && day == target
            val isBonusDay = weeklyBonusEnabled && day % weeklyBonusEveryDays == 0
            gui.setItem(10 + i, streakDayItem(day, completed, isToday, isBonusDay))
        }

        gui.setItem(20, dailyRewardItem(state))
        gui.setItem(22, graceItem(state))
        gui.setItem(24, nextBonusItem(state, target, nextBonusDay))
        gui.setItem(26, closeItem()) { p, _ -> p.closeInventory() }
    }

    private fun nextMultiple(value: Int, n: Int): Int {
        if (value <= 0) return n
        return ((value + n - 1) / n) * n
    }

    private fun formatDuration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        return "${s / 60}m ${s % 60}s"
    }

    private fun noItalic(component: Component) = component.decoration(TextDecoration.ITALIC, false)

    private fun playerInfoItem(player: Player, state: StreakState): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) { meta ->
            meta.owningPlayer = player
            meta.displayName(
                noItalic(Component.text("${player.name}'s Login Streak", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))
            )
            val lore = mutableListOf<Component>()
            lore += Component.empty()
            lore += noItalic(Component.text("Current Streak: ", NamedTextColor.GRAY).append(Component.text("${state.currentStreak} Days", NamedTextColor.WHITE)))
            lore += noItalic(Component.text("Longest Streak: ", NamedTextColor.GRAY).append(Component.text("${state.longestStreak} Days", NamedTextColor.WHITE)))
            lore += Component.empty()
            if (state.todayCompleted) {
                lore += noItalic(Component.text("Today: ", NamedTextColor.GRAY).append(Component.text("✓ COMPLETED", NamedTextColor.GREEN)))
            } else {
                lore += noItalic(
                    Component.text("Today: ", NamedTextColor.GRAY)
                        .append(Component.text("${formatDuration(state.todaySeconds)} / ${formatDuration(requiredSeconds())}", NamedTextColor.WHITE))
                )
                lore += noItalic(
                    Component.text("Remaining: ", NamedTextColor.GRAY)
                        .append(Component.text(formatDuration(requiredSeconds() - state.todaySeconds), NamedTextColor.YELLOW))
                )
            }
            meta.lore(lore)
        }
        return head
    }

    private fun streakDayItem(day: Int, completed: Boolean, isToday: Boolean, isBonusDay: Boolean): ItemStack {
        val material = when {
            isBonusDay && completed -> Material.ENDER_CHEST
            isBonusDay -> Material.CHEST
            completed -> Material.LIME_DYE
            isToday -> Material.CLOCK
            else -> Material.GRAY_DYE
        }
        val nameColor = when {
            isBonusDay -> NamedTextColor.LIGHT_PURPLE
            completed -> NamedTextColor.GREEN
            isToday -> NamedTextColor.YELLOW
            else -> NamedTextColor.GRAY
        }
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(noItalic(Component.text("Day $day", nameColor).decoration(TextDecoration.BOLD, true)))
            val lore = mutableListOf<Component>()
            lore += Component.empty()
            when {
                isBonusDay -> {
                    lore += noItalic(Component.text("WEEKLY STREAK BONUS", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true))
                    lore += Component.empty()
                    if (completed) {
                        lore += noItalic(Component.text("✓ WEEKLY BONUS CLAIMED", NamedTextColor.GREEN))
                    } else {
                        lore += noItalic(Component.text("Rewards:", NamedTextColor.GRAY))
                        lore += noItalic(
                            Component.text("  \$${plugin.economyManager.formatShort(dailyRewardAmount)} + ${plugin.creditsManager.format(weeklyBonusCredits)} Credits + 1 Random Crate Key", NamedTextColor.GREEN)
                        )
                        lore += Component.empty()
                        lore += noItalic(Component.text("Keep your streak alive!", NamedTextColor.GRAY))
                    }
                }
                completed -> lore += noItalic(Component.text("✓ Completed", NamedTextColor.GREEN))
                isToday -> lore += noItalic(Component.text("▶ TODAY'S TARGET", NamedTextColor.YELLOW))
                else -> lore += noItalic(Component.text("Locked", NamedTextColor.DARK_GRAY))
            }
            meta.lore(lore)
        }
        return item
    }

    private fun dailyRewardItem(state: StreakState): ItemStack {
        val item = ItemStack(Material.GOLD_INGOT)
        item.editMeta { meta ->
            meta.displayName(noItalic(Component.text("Daily Reward", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)))
            val lore = mutableListOf<Component>()
            lore += Component.empty()
            lore += noItalic(Component.text("Reward: ", NamedTextColor.GRAY).append(Component.text("\$${plugin.economyManager.formatShort(dailyRewardAmount)}", NamedTextColor.GREEN)))
            lore += noItalic(Component.text("Requirement: ", NamedTextColor.GRAY).append(Component.text("$requiredMinutes Minutes Active Playtime", NamedTextColor.WHITE)))
            lore += Component.empty()
            if (state.todayCompleted) {
                lore += noItalic(Component.text("✓ CLAIMED TODAY", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true))
            } else {
                lore += noItalic(
                    Component.text("Progress: ", NamedTextColor.GRAY)
                        .append(Component.text("${formatDuration(state.todaySeconds)} / ${formatDuration(requiredSeconds())}", NamedTextColor.WHITE))
                )
                lore += noItalic(
                    Component.text("Remaining: ", NamedTextColor.GRAY)
                        .append(Component.text(formatDuration(requiredSeconds() - state.todaySeconds), NamedTextColor.YELLOW))
                )
            }
            meta.lore(lore)
        }
        return item
    }

    private fun graceItem(state: StreakState): ItemStack {
        val item = ItemStack(if (graceEnabled && state.graceAvailable) Material.TOTEM_OF_UNDYING else Material.GRAY_DYE)
        item.editMeta { meta ->
            meta.displayName(noItalic(Component.text("Streak Grace", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true)))
            val lore = mutableListOf<Component>()
            lore += Component.empty()
            when {
                !graceEnabled -> lore += noItalic(Component.text("Status: ", NamedTextColor.GRAY).append(Component.text("DISABLED", NamedTextColor.RED)))
                state.graceAvailable -> {
                    lore += noItalic(Component.text("Status: ", NamedTextColor.GRAY).append(Component.text("✓ AVAILABLE", NamedTextColor.GREEN)))
                    lore += noItalic(Component.text("Protects: ", NamedTextColor.GRAY).append(Component.text("1 Missed Day", NamedTextColor.WHITE)))
                }
                else -> {
                    lore += noItalic(Component.text("Status: ", NamedTextColor.GRAY).append(Component.text("USED", NamedTextColor.RED)))
                    lore += noItalic(Component.text("Recharge: ", NamedTextColor.GRAY).append(Component.text("${state.graceRechargeProgress} / $graceRechargeDays Completed Days", NamedTextColor.WHITE)))
                    lore += Component.empty()
                    val remaining = (graceRechargeDays - state.graceRechargeProgress).coerceAtLeast(0)
                    lore += noItalic(Component.text("Complete $remaining more Login day${if (remaining == 1) "" else "s"}", NamedTextColor.GRAY))
                    lore += noItalic(Component.text("to restore your Grace.", NamedTextColor.GRAY))
                }
            }
            meta.lore(lore)
        }
        return item
    }

    private fun nextBonusItem(state: StreakState, target: Int, nextBonusDay: Int): ItemStack {
        val daysRemaining = (nextBonusDay - state.currentStreak).coerceAtLeast(0)
        val item = ItemStack(Material.NETHER_STAR)
        item.editMeta { meta ->
            meta.displayName(noItalic(Component.text("Next Weekly Bonus", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true)))
            val lore = mutableListOf<Component>()
            lore += Component.empty()
            lore += noItalic(Component.text("Day: ", NamedTextColor.GRAY).append(Component.text("$nextBonusDay", NamedTextColor.WHITE)))
            lore += noItalic(Component.text("Current Streak: ", NamedTextColor.GRAY).append(Component.text("${state.currentStreak}", NamedTextColor.WHITE)))
            lore += Component.empty()
            when {
                !weeklyBonusEnabled -> lore += noItalic(Component.text("Weekly Bonus is currently disabled.", NamedTextColor.DARK_GRAY))
                !state.todayCompleted && target == nextBonusDay -> {
                    lore += noItalic(Component.text("Complete today's $requiredMinutes minutes", NamedTextColor.GRAY))
                    lore += noItalic(Component.text("to unlock:", NamedTextColor.GRAY))
                    lore += Component.empty()
                    lore += noItalic(Component.text("\$${plugin.economyManager.formatShort(dailyRewardAmount)}", NamedTextColor.GREEN))
                    lore += noItalic(Component.text("${plugin.creditsManager.format(weeklyBonusCredits)} Credits", NamedTextColor.GREEN))
                    lore += noItalic(Component.text("1 Random Crate Key", NamedTextColor.GREEN))
                }
                else -> {
                    lore += noItalic(Component.text("Days Remaining: ", NamedTextColor.GRAY).append(Component.text("$daysRemaining", NamedTextColor.WHITE)))
                    lore += Component.empty()
                    lore += noItalic(Component.text("Rewards:", NamedTextColor.GRAY))
                    lore += noItalic(Component.text("  ${plugin.creditsManager.format(weeklyBonusCredits)} Credits", NamedTextColor.GREEN))
                    lore += noItalic(Component.text("  1 Random Crate Key", NamedTextColor.GREEN))
                    lore += noItalic(Component.text("  Plus your normal \$${plugin.economyManager.formatShort(dailyRewardAmount)}", NamedTextColor.GREEN))
                }
            }
            meta.lore(lore)
        }
        return item
    }

    private fun closeItem(): ItemStack {
        val item = ItemStack(Material.BARRIER)
        item.editMeta { it.displayName(noItalic(Component.text("Close", NamedTextColor.RED))) }
        return item
    }

    // ── Notifications ──────────────────────────────────────

    private fun notifyJoin(player: Player, state: StreakState) {
        if (state.todayCompleted) return
        val willTriggerBonus = weeklyBonusEnabled && (state.currentStreak + 1) % weeklyBonusEveryDays == 0
        plugin.commsManager.send(player, Component.text("LOGIN STREAK", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true), CommunicationsManager.Category.ECONOMY)
        plugin.commsManager.send(
            player,
            Component.text("Current: ", NamedTextColor.GRAY).append(Component.text("${state.currentStreak} Days", NamedTextColor.WHITE)),
            CommunicationsManager.Category.ECONOMY
        )
        if (willTriggerBonus) {
            plugin.commsManager.send(player, Component.text("Complete $requiredMinutes minutes today!", NamedTextColor.GRAY), CommunicationsManager.Category.ECONOMY)
            plugin.commsManager.send(
                player,
                Component.text("Rewards: ", NamedTextColor.GRAY)
                    .append(Component.text("\$${plugin.economyManager.formatShort(dailyRewardAmount)} + ${plugin.creditsManager.format(weeklyBonusCredits)} Credits + 1 Random Crate Key", NamedTextColor.GREEN)),
                CommunicationsManager.Category.ECONOMY
            )
        } else {
            plugin.commsManager.send(player, Component.text("Play $requiredMinutes minutes today to continue your streak!", NamedTextColor.GRAY), CommunicationsManager.Category.ECONOMY)
            plugin.commsManager.send(
                player,
                Component.text("Reward: ", NamedTextColor.GRAY).append(Component.text("\$${plugin.economyManager.formatShort(dailyRewardAmount)}", NamedTextColor.GREEN)),
                CommunicationsManager.Category.ECONOMY
            )
        }
    }

    private fun notifyCompletion(player: Player, state: StreakState, weeklyBonus: Boolean, keyGiven: String?) {
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.0f)
        if (weeklyBonus) {
            player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.0f)
            plugin.commsManager.send(player, Component.text("WEEKLY STREAK BONUS!", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true), CommunicationsManager.Category.ECONOMY)
            val keyLabel = keyGiven?.let { plugin.crateManager.getCrate(it)?.displayName ?: it }
            val rewardText = buildString {
                append("\$${plugin.economyManager.formatShort(dailyRewardAmount)}")
                append(" + ${plugin.creditsManager.format(weeklyBonusCredits)} Credits")
                if (keyLabel != null) append(" + 1 $keyLabel Key")
            }
            plugin.commsManager.send(
                player,
                Component.text("Streak: ", NamedTextColor.GRAY).append(Component.text("${state.currentStreak} Days", NamedTextColor.WHITE)),
                CommunicationsManager.Category.ECONOMY
            )
            plugin.commsManager.send(player, Component.text("Rewards: ", NamedTextColor.GRAY).append(Component.text(rewardText, NamedTextColor.GREEN)), CommunicationsManager.Category.ECONOMY)
        } else {
            plugin.commsManager.send(player, Component.text("DAILY LOGIN COMPLETE!", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true), CommunicationsManager.Category.ECONOMY)
            plugin.commsManager.send(
                player,
                Component.text("Streak: ", NamedTextColor.GRAY).append(Component.text("${state.currentStreak} Days", NamedTextColor.WHITE))
                    .append(Component.text("  Reward: ", NamedTextColor.GRAY))
                    .append(Component.text("\$${plugin.economyManager.formatShort(dailyRewardAmount)}", NamedTextColor.GREEN)),
                CommunicationsManager.Category.ECONOMY
            )
        }
    }

    private fun notifyGraceUsed(player: Player, state: StreakState) {
        plugin.commsManager.send(player, Component.text("STREAK GRACE USED", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true), CommunicationsManager.Category.ECONOMY)
        plugin.commsManager.send(player, Component.text("Your Login Streak was protected!", NamedTextColor.GRAY), CommunicationsManager.Category.ECONOMY)
        plugin.commsManager.send(
            player,
            Component.text("Current: ", NamedTextColor.GRAY).append(Component.text("${state.currentStreak} Days", NamedTextColor.WHITE)),
            CommunicationsManager.Category.ECONOMY
        )
        plugin.commsManager.send(player, Component.text("Complete today's $requiredMinutes minutes to continue your streak.", NamedTextColor.GRAY), CommunicationsManager.Category.ECONOMY)
    }

    private fun notifyStreakLost(player: Player, previousStreak: Int) {
        plugin.commsManager.send(player, Component.text("LOGIN STREAK LOST", NamedTextColor.RED).decoration(TextDecoration.BOLD, true), CommunicationsManager.Category.ECONOMY)
        plugin.commsManager.send(
            player,
            Component.text("Previous Streak: ", NamedTextColor.GRAY).append(Component.text("$previousStreak Days", NamedTextColor.WHITE)),
            CommunicationsManager.Category.ECONOMY
        )
        plugin.commsManager.send(player, Component.text("Complete $requiredMinutes minutes today to begin a new streak.", NamedTextColor.GRAY), CommunicationsManager.Category.ECONOMY)
    }

    companion object {
        private const val DEFAULT_TIMEZONE = "America/New_York"
        private const val ACCRUAL_INTERVAL_SECONDS = 10L
        private val DEFAULT_ELIGIBLE_KEYS = listOf("common", "rare", "joshy")
    }
}
