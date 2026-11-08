package com.liam.joshymc.flight

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.listener.PvpWorldFlightListener
import com.liam.joshymc.manager.SpawnWorldManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Survival temporary flight (issues #1304 and #1408): the 30-second landing grace and the
 * end of temporary flight. Rules live in the Bukkit-free [SurvivalFlight].
 *
 * Since issue #1408 LuckPerms is the authority for temporary flight: Fly Vouchers grant a
 * timed `joshymc.fly` node ([LuckPermsFlyGrant]) and LuckPerms expires it. This manager only
 * keeps a mirror of that expiry (`lp_synced = 1` rows, re-synced from LuckPerms every second
 * while the player is online in Survival, so staff extending or removing the node is followed)
 * because LuckPerms removing the node doesn't touch `allowFlight`: the mirror is what starts
 * the landing grace and then takes flight away. Any timed `joshymc.fly` grant gets that
 * treatment, not just vouchers.
 *
 * Rows from before issue #1408 (`lp_synced = 0`) are JoshyMC-only balances. They keep working
 * until they're moved into LuckPerms (on join and while online, idempotent, never shortening
 * what LuckPerms already has).
 *
 * Stored per player in `survival_flight_time` (gamemode is always `survival`; nothing here
 * applies outside Survival). `ended` is set once the end has been enforced, so a finished
 * balance is never enforced twice (e.g. on every later join).
 */
class FlightTimeManager(private val plugin: Joshymc) {

    data class Balance(val endsAt: Long, val ended: Boolean, val lpSynced: Boolean = true)

    private val balances = ConcurrentHashMap<UUID, Balance>()

    /** Grace seconds left at the last reminder sent to each player, so every reminder goes out once. */
    private val lastReminder = ConcurrentHashMap<UUID, Long>()

    private var task: BukkitTask? = null
    private var ticks = 0L

    /**
     * One LuckPerms flight change per player at a time (voucher redemption or moving an old
     * balance in), across the asynchronous LuckPerms save. Closes rapid clicks and concurrent
     * redemptions.
     */
    private val busy = ConcurrentHashMap.newKeySet<UUID>()

    /** Old balances that couldn't be moved into LuckPerms this session: not retried every second. */
    private val migrationSkipped = ConcurrentHashMap.newKeySet<UUID>()

    fun tryLock(uuid: UUID): Boolean = busy.add(uuid)
    fun unlock(uuid: UUID) { busy.remove(uuid) }

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS survival_flight_time (
                uuid TEXT NOT NULL,
                gamemode TEXT NOT NULL DEFAULT 'survival',
                ends_at INTEGER NOT NULL,
                ended INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                lp_synced INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, gamemode)
            )
        """.trimIndent())
        // Issue #1408: 1 = this row mirrors a LuckPerms timed node; 0 = an older JoshyMC-only balance.
        try {
            plugin.databaseManager.execute("ALTER TABLE survival_flight_time ADD COLUMN lp_synced INTEGER NOT NULL DEFAULT 0")
        } catch (_: Exception) {
            // Column already exists.
        }

        // Issue #1470: the player's own /fly choice, so it survives reconnects and restarts.
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_flight_preferences (
                uuid TEXT PRIMARY KEY,
                enabled INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())

        Bukkit.getOnlinePlayers().forEach { load(it.uniqueId); loadPreference(it.uniqueId) }
        task?.cancel()
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 20L, 5L)
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    // ── Balance ──────────────────────────────────────────────────────────

    fun load(uuid: UUID) {
        val row = plugin.databaseManager.queryFirst(
            "SELECT ends_at, ended, lp_synced FROM survival_flight_time WHERE uuid = ? AND gamemode = ?",
            uuid.toString(), GAMEMODE
        ) { Balance(it.getLong("ends_at"), it.getInt("ended") != 0, it.getInt("lp_synced") != 0) }
        if (row == null) balances.remove(uuid) else balances[uuid] = row
    }

    fun unload(uuid: UUID) {
        balances.remove(uuid)
        preferences.remove(uuid)
        fallProtection.remove(uuid)
        lastReminder.remove(uuid)
        migrationSkipped.remove(uuid)
    }

    fun balance(uuid: UUID): Balance? = balances[uuid]

    fun phase(uuid: UUID, now: Long = System.currentTimeMillis()): SurvivalFlight.Phase {
        val b = balances[uuid] ?: return SurvivalFlight.Phase.None
        return SurvivalFlight.phase(b.endsAt, b.ended, now)
    }

    /**
     * True while [player] may fly on Survival flight time: in Survival, with time left or
     * inside the landing grace. `/fly` and the combat/arena flight restores check this
     * alongside `joshymc.fly`. Time left on a mirrored row is the LuckPerms node (the player
     * has `joshymc.fly` anyway); on an old row it's the balance not yet moved into LuckPerms.
     */
    fun hasTemporaryFlight(player: Player): Boolean {
        if (!inSurvival(player.world)) return false
        return when (phase(player.uniqueId)) {
            is SurvivalFlight.Phase.Active, is SurvivalFlight.Phase.Grace -> true
            else -> false
        }
    }

    /**
     * Writes a new end time mirroring LuckPerms. Must run inside the caller's
     * [com.liam.joshymc.manager.DatabaseManager.transaction] when it goes with other writes (a
     * voucher redemption); call [applyWritten] after commit.
     */
    fun writeEnd(uuid: UUID, endsAt: Long) {
        plugin.databaseManager.execute(
            """
            INSERT INTO survival_flight_time (uuid, gamemode, ends_at, ended, updated_at, lp_synced) VALUES (?, ?, ?, 0, ?, 1)
            ON CONFLICT(uuid, gamemode) DO UPDATE SET ends_at = excluded.ends_at, ended = 0, updated_at = excluded.updated_at, lp_synced = 1
            """.trimIndent(),
            uuid.toString(), GAMEMODE, endsAt, System.currentTimeMillis()
        )
    }

    /** Mirrors a committed [writeEnd] in memory and drops any grace reminders in progress. */
    fun applyWritten(uuid: UUID, endsAt: Long) {
        balances[uuid] = Balance(endsAt, ended = false, lpSynced = true)
        lastReminder.remove(uuid)
    }

    private fun persist(uuid: UUID, endsAt: Long) {
        try {
            writeEnd(uuid, endsAt)
            applyWritten(uuid, endsAt)
        } catch (e: Exception) {
            plugin.logger.warning("[FlyTime] Couldn't store the flight end for $uuid: ${e.message}")
        }
    }

    /** Every LuckPerms path to the player's fly node with its expiry, or null when LuckPerms can't be read. */
    private fun luckPermsGrants(player: Player): List<Instant?>? = try {
        LuckPermsFlight.current()?.flyGrants(player.uniqueId)
    } catch (e: Exception) {
        plugin.logger.warning("[FlyTime] Couldn't read LuckPerms flight nodes: ${e.message}")
        null
    }

    /** The player's `/fly` access as LuckPerms explains it: permanent, timed, or none. */
    fun luckPermsAccess(player: Player): FlightTime.Access =
        FlightTime.resolve(player.hasPermission("joshymc.fly"), luckPermsGrants(player), Instant.now())

    // ── LuckPerms sync (issue #1408) ─────────────────────────────────────

    /**
     * Brings the mirror in line with LuckPerms for an online Survival player: a timed fly grant
     * sets the end time (so the landing grace follows its expiry), a grant LuckPerms took away
     * early ends it now (the grace starts), and an old JoshyMC balance is moved into LuckPerms.
     */
    fun sync(player: Player, now: Long = System.currentTimeMillis()) {
        if (!inSurvival(player.world)) return
        val uuid = player.uniqueId
        val b = balances[uuid]
        if (b != null && !b.ended && !b.lpSynced && b.endsAt > now) {
            migrateLegacy(player, b)
            return
        }
        val grants = luckPermsGrants(player)
        when (val access = FlightTime.resolve(player.hasPermission("joshymc.fly"), grants, Instant.now())) {
            is FlightTime.Access.Temporary -> {
                val target = now + access.remaining.toMillis()
                if (b == null || b.ended || !b.lpSynced || kotlin.math.abs(b.endsAt - target) > SYNC_TOLERANCE_MILLIS) persist(uuid, target)
            }
            FlightTime.Access.None -> {
                // LuckPerms is readable and no longer grants flight, but the mirror still says
                // there's time left: staff removed the node early. The grace starts now.
                if (grants != null && b != null && !b.ended && b.lpSynced && b.endsAt > now) persist(uuid, now)
            }
            FlightTime.Access.Permanent -> {}
        }
    }

    /**
     * Moves an older JoshyMC-only balance (issue #1304) into LuckPerms as a timed `joshymc.fly`
     * node lasting until the balance's end (never shortening a longer node), then marks the
     * row as a mirror. Idempotent, so a crash halfway just repeats it on the next try.
     */
    private fun migrateLegacy(player: Player, b: Balance) {
        val uuid = player.uniqueId
        if (uuid in migrationSkipped || !tryLock(uuid)) return
        val writer = try {
            LuckPermsFlyGrant.current()
        } catch (e: Exception) {
            plugin.logger.warning("[FlyTime] LuckPerms API unavailable, can't move ${player.name}'s flight time: ${e.message}")
            null
        }
        if (writer == null) {
            // Keeps working as before (hasTemporaryFlight) until LuckPerms is back.
            migrationSkipped += uuid
            unlock(uuid)
            return
        }
        val untilSec = Math.floorDiv(b.endsAt + 999, 1000L)
        val future = try {
            writer.ensureUntil(uuid, untilSec, FlyVoucherGrants.nowSeconds(System.currentTimeMillis()))
        } catch (e: Exception) {
            plugin.logger.warning("[FlyTime] Couldn't move ${player.name}'s flight time into LuckPerms: ${e.message}")
            migrationSkipped += uuid
            unlock(uuid)
            return
        }
        future.whenComplete { outcome, error ->
            val settle = Runnable {
                try {
                    val plan = outcome?.plan
                    if (error != null || plan == null || plan is FlyVoucherGrants.Plan.Denied || plan is FlyVoucherGrants.Plan.TooLong) {
                        plugin.logger.warning("[FlyTime] Couldn't move ${player.name}'s flight time into LuckPerms: ${error?.message ?: plan}")
                        migrationSkipped += uuid
                        return@Runnable
                    }
                    plugin.databaseManager.executeUpdate(
                        "UPDATE survival_flight_time SET lp_synced = 1, updated_at = ? WHERE uuid = ? AND gamemode = ? AND ends_at = ? AND lp_synced = 0",
                        System.currentTimeMillis(), uuid.toString(), GAMEMODE, b.endsAt
                    )
                    load(uuid)
                    plugin.logger.info("[FlyTime] Moved ${player.name}'s Fly Voucher time into LuckPerms (${FlyVoucherGrants.NODE} until epoch second $untilSec, $plan).")
                } catch (e: Exception) {
                    plugin.logger.warning("[FlyTime] Moved ${player.name}'s flight time into LuckPerms but couldn't mark it: ${e.message}")
                    migrationSkipped += uuid
                } finally {
                    unlock(uuid)
                }
            }
            try {
                Bukkit.getScheduler().runTask(plugin, settle)
            } catch (_: Exception) {
                unlock(uuid) // Plugin disabling: the row stays an old balance and is moved next time (idempotent).
            }
        }
    }

    // ── /fly preference (issue #1470) ────────────────────────────────────

    /**
     * The player's last `/fly` toggle (true = on), cached while online. Absent = never toggled.
     * This is a choice, not access: [restoreFlight] only acts on it when [restoreBlock] is null.
     */
    private val preferences = ConcurrentHashMap<UUID, Boolean>()

    /** Reconnect fall protection: epoch millis it ends at. Used up by the first fall. */
    private val fallProtection = ConcurrentHashMap<UUID, Long>()

    fun loadPreference(uuid: UUID) {
        val row = try {
            plugin.databaseManager.queryFirst(
                "SELECT enabled FROM player_flight_preferences WHERE uuid = ?", uuid.toString()
            ) { it.getInt("enabled") != 0 }
        } catch (e: Exception) {
            plugin.logger.warning("[Fly] Couldn't load the /fly preference for $uuid: ${e.message}")
            null
        }
        if (row == null) preferences.remove(uuid) else preferences[uuid] = row
    }

    fun flightPreference(uuid: UUID): Boolean? = preferences[uuid]

    /**
     * Records a `/fly` toggle. Written straight away (one small row by UUID) so a crash right
     * after still remembers it, and only when the choice actually changed.
     */
    fun setFlightPreference(uuid: UUID, enabled: Boolean) {
        if (!FlightRestore.needsWrite(preferences[uuid], enabled)) return
        try {
            plugin.databaseManager.execute(
                "INSERT INTO player_flight_preferences (uuid, enabled, updated_at) VALUES (?, ?, ?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET enabled = excluded.enabled, updated_at = excluded.updated_at",
                uuid.toString(), if (enabled) 1 else 0, System.currentTimeMillis()
            )
            preferences[uuid] = enabled
        } catch (e: Exception) {
            plugin.logger.warning("[Fly] Couldn't store the /fly preference for $uuid: ${e.message}")
        }
    }

    /**
     * Why [player] can't have `/fly` back right now, or null when they may. The same rules `/fly`
     * applies, plus the places where flight is taken away (event world, ranked matches).
     */
    fun restoreBlock(player: Player): FlightRestore.Block? {
        if (!player.hasPermission("joshymc.fly") && !hasTemporaryFlight(player)) return FlightRestore.Block.NO_PERMISSION
        if (!plugin.gamemodeLockManager.isAllowed(GamemodeScope.SURVIVAL, player.world)) return FlightRestore.Block.GAMEMODE_LOCKED
        if (plugin.combatManager.isTagged(player)) return FlightRestore.Block.COMBAT_TAGGED
        if (plugin.arenaManager.playersInArena.containsKey(player.uniqueId)) return FlightRestore.Block.PVP_ARENA
        val world = player.world.name
        if (world == PvpWorldFlightListener.WORLD_NAME && !player.hasPermission(PvpWorldFlightListener.PERMISSION)) return FlightRestore.Block.WORLD_RESTRICTED
        if (world == "spawn" && !player.hasPermission(SpawnWorldManager.FLY_PERMISSION)) return FlightRestore.Block.WORLD_RESTRICTED
        if (runCatching { plugin.eventManager.isInEventWorld(player) }.getOrDefault(true)) return FlightRestore.Block.EVENT_WORLD
        if (runCatching { plugin.lifestealRankedManager.holds(player) }.getOrDefault(true)) return FlightRestore.Block.RANKED_MATCH
        return null
    }

    /** Open (non-solid, non-liquid) blocks straight below [player]'s feet, up to [FlightRestore.MAX_GAP]. */
    private fun gapBelow(player: Player): Int {
        val loc = player.location
        val world = loc.world ?: return 0
        // Just under the feet, so a player standing on a block (or a slab) counts as grounded.
        var y = Math.floor(loc.y - 0.05).toInt()
        var gap = 0
        while (gap < FlightRestore.MAX_GAP) {
            if (y < world.minHeight) return FlightRestore.MAX_GAP // Over the void.
            val block = world.getBlockAt(loc.blockX, y, loc.blockZ)
            if (block.type.isSolid || block.isLiquid) break
            gap++
            y--
        }
        return gap
    }

    /**
     * Puts [player]'s own `/fly` back when they chose it and may use it here. Never turns flight
     * off and never touches Creative/Spectator, so it can't flicker or override another source.
     * When flight was on but can't come back and the player is high up, they only get a short,
     * one-time fall-damage protection (no flight, no teleport).
     */
    fun restoreFlight(player: Player, protectFall: Boolean) {
        if (!player.isOnline) return
        val creative = player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR
        val pref = preferences[player.uniqueId]
        // Cheap exits before reading permissions or blocks.
        if (creative || pref != true || player.allowFlight) {
            if (player.allowFlight) fallProtection.remove(player.uniqueId)
            return
        }
        val decision = FlightRestore.decide(FlightRestore.Input(
            preference = pref,
            creativeOrSpectator = false,
            alreadyAllowed = false,
            block = restoreBlock(player),
            gapBelow = gapBelow(player),
        ))
        when (decision) {
            is FlightRestore.Decision.Restore -> {
                player.allowFlight = true
                if (decision.flying) player.isFlying = true
                fallProtection.remove(player.uniqueId)
            }
            is FlightRestore.Decision.ProtectFall -> if (protectFall) {
                fallProtection.putIfAbsent(player.uniqueId, System.currentTimeMillis() + FlightRestore.FALL_PROTECTION_MILLIS)
            }
            else -> {}
        }
    }

    /**
     * After join: restore now, then check again a moment later in case another plugin or
     * listener reset flight during login. Rechecks only turn flight on if it's still off.
     */
    fun restoreFlightOnJoin(player: Player) {
        restoreFlight(player, protectFall = true)
        for (delay in FlightRestore.RECHECK_TICKS) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable { restoreFlight(player, protectFall = false) }, delay)
        }
    }

    /** A gamemode switch or world change: put `/fly` back (vanilla clears it on a Survival switch). */
    fun restoreFlightLater(player: Player) {
        Bukkit.getScheduler().runTask(plugin, Runnable { restoreFlight(player, protectFall = false) })
    }

    /**
     * True (and used up) when this fall damage is the reconnect fall [restoreFlight] protected
     * against: within [FlightRestore.FALL_PROTECTION_MILLIS] of joining and not in combat.
     */
    fun consumeFallProtection(player: Player): Boolean {
        val until = fallProtection.remove(player.uniqueId) ?: return false
        return FlightRestore.cancelsFall(until, System.currentTimeMillis(), plugin.combatManager.isTagged(player))
    }

    // ── Enforcement ──────────────────────────────────────────────────────

    /**
     * Flight that doesn't come from temporary flight and must never be taken away when it
     * runs out: Creative/Spectator, permanent `/fly` permission (rank, staff, op), ModMode, and
     * spawn-world flight. A timed `joshymc.fly` node is temporary flight (issue #1408): it's
     * what the grace ends, and LuckPerms may still report it for a few seconds after expiry.
     */
    fun hasIndependentFlight(player: Player): Boolean {
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) return true
        if (luckPermsAccess(player) == FlightTime.Access.Permanent) return true
        if (plugin.modModeManager.isModMode(player)) return true
        if (player.world.name == "spawn" && player.hasPermission(SpawnWorldManager.FLY_PERMISSION)) return true
        return false
    }

    fun inSurvival(world: World?): Boolean = plugin.gamemodeLockManager.gamemodeOf(world) == ServerGamemode.SURVIVAL

    private fun tick() {
        val now = System.currentTimeMillis()
        // Once a second: follow LuckPerms (issue #1408).
        if (ticks++ % SYNC_EVERY_TICKS == 0L) {
            for (player in Bukkit.getOnlinePlayers()) sync(player, now)
        }
        for (player in Bukkit.getOnlinePlayers()) {
            val b = balances[player.uniqueId] ?: continue
            if (b.ended) continue
            when (val phase = SurvivalFlight.phase(b.endsAt, false, now)) {
                is SurvivalFlight.Phase.Grace -> tickGrace(player, b, phase.graceLeftMillis)
                SurvivalFlight.Phase.Expired -> finish(player, b)
                else -> {}
            }
        }
    }

    private fun tickGrace(player: Player, b: Balance, graceLeftMillis: Long) {
        // A new timed grant in LuckPerms (another voucher, staff): back to time left, no grace.
        val access = luckPermsAccess(player)
        if (access is FlightTime.Access.Temporary && inSurvival(player.world)) {
            persist(player.uniqueId, System.currentTimeMillis() + access.remaining.toMillis())
            return
        }
        // Permanent flight, a /fly grant or staff flight took over: the grace has nothing to
        // take away, so it ends quietly.
        if (hasIndependentFlight(player)) {
            markEnded(player.uniqueId, b)
            return
        }
        // Survival flight doesn't apply outside Survival; the clock still runs, but there's
        // nothing to warn about there.
        if (!inSurvival(player.world)) return

        val secondsLeft = SurvivalFlight.ceilSeconds(graceLeftMillis)
        val last = lastReminder[player.uniqueId]
        plugin.commsManager.sendActionBar(
            player,
            Component.text("Flight disabled in ", NamedTextColor.RED)
                .append(Component.text("${secondsLeft}s", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
        )
        if (last == null) {
            // First tick of the grace for this session. Joining partway through shows what's
            // actually left, never a fresh 30 seconds.
            lastReminder[player.uniqueId] = secondsLeft
            plugin.commsManager.send(
                player,
                Component.text("FLIGHT TIME EXPIRED!", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
                    .append(Component.newline())
                    .append(Component.text("You have ", NamedTextColor.YELLOW))
                    .append(Component.text("$secondsLeft second${if (secondsLeft == 1L) "" else "s"}", NamedTextColor.WHITE))
                    .append(Component.text(" to land before your flight is disabled!", NamedTextColor.YELLOW))
            )
            player.showTitle(Title.title(
                Component.text("FLIGHT TIME EXPIRED!", NamedTextColor.RED).decoration(TextDecoration.BOLD, true),
                Component.text("Land within ${secondsLeft}s", NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))
            ))
            return
        }
        if (secondsLeft >= last) return
        lastReminder[player.uniqueId] = secondsLeft
        // Ticks run every quarter second, so each second is seen; a lagging server that skips
        // one still sends a reminder, with the real time left.
        val crossed = (secondsLeft until last).toList()
        if (crossed.any { it.toInt() in SurvivalFlight.CHAT_REMINDERS }) {
            plugin.commsManager.send(
                player,
                Component.text("Flight will be disabled in ", NamedTextColor.YELLOW)
                    .append(Component.text("$secondsLeft second${if (secondsLeft == 1L) "" else "s"}", NamedTextColor.WHITE))
                    .append(Component.text(". Land now!", NamedTextColor.YELLOW))
            )
        }
        if (secondsLeft.toInt() in SurvivalFlight.TITLE_COUNTDOWN) {
            player.showTitle(Title.title(
                Component.text("$secondsLeft", NamedTextColor.RED).decoration(TextDecoration.BOLD, true),
                Component.text("second${if (secondsLeft == 1L) "" else "s"} of flight left", NamedTextColor.YELLOW),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200))
            ))
        }
    }

    /** The grace is over: end the balance and take Survival flight away, even mid-air. */
    private fun finish(player: Player, b: Balance) {
        if (!markEnded(player.uniqueId, b)) return
        lastReminder.remove(player.uniqueId)
        if (hasIndependentFlight(player) || !inSurvival(player.world)) return
        // Still on a timed grant LuckPerms hasn't expired: the next sync mirrors it again.
        if (luckPermsAccess(player) is FlightTime.Access.Temporary) return
        // No teleport, Slow Falling or fall-damage immunity: the player simply loses flight.
        if (player.allowFlight || player.isFlying) {
            player.isFlying = false
            player.allowFlight = false
        }
        plugin.commsManager.send(
            player,
            Component.text("Your flight time has expired! ", NamedTextColor.RED)
                .append(Component.text("Flight has been disabled.", NamedTextColor.GRAY))
        )
    }

    /** Ends [b] once. False when the stored balance has changed since (e.g. a new voucher). */
    private fun markEnded(uuid: UUID, b: Balance): Boolean {
        val rows = try {
            plugin.databaseManager.executeUpdate(
                "UPDATE survival_flight_time SET ended = 1, updated_at = ? WHERE uuid = ? AND gamemode = ? AND ended = 0 AND ends_at = ?",
                System.currentTimeMillis(), uuid.toString(), GAMEMODE, b.endsAt
            )
        } catch (e: Exception) {
            plugin.logger.warning("[FlyTime] Couldn't end flight time for $uuid: ${e.message}")
            return false
        }
        if (rows <= 0) {
            load(uuid)
            return false
        }
        balances[uuid] = b.copy(ended = true)
        return true
    }

    /** On join: settle a balance whose grace ran out while the player was away. */
    fun onJoin(player: Player) {
        load(player.uniqueId)
        lastReminder.remove(player.uniqueId)
        migrationSkipped.remove(player.uniqueId)
        // Follow LuckPerms first: a node that expired while they were away has no time left,
        // and a node still running keeps its real remaining time (a restart never resets it).
        sync(player)
        val b = balances[player.uniqueId] ?: return
        when (phase(player.uniqueId)) {
            SurvivalFlight.Phase.Expired -> finish(player, b)
            // Ended earlier, but flight came back without a source (e.g. a ModMode backup
            // restored after the time ran out): take it away again, quietly. Claim-flight
            // talismans turn it back on by themselves.
            SurvivalFlight.Phase.None -> if (b.ended && inSurvival(player.world) && player.allowFlight && !hasIndependentFlight(player)) {
                player.isFlying = false
                player.allowFlight = false
            }
            else -> stripOutsideSurvival(player)
        }
    }

    /**
     * Survival flight time never carries into another gamemode: a player who only flies on it
     * loses flight when they arrive in a non-Survival world. Their balance stays with Survival.
     */
    fun stripOutsideSurvival(player: Player) {
        if (inSurvival(player.world)) return
        val phase = phase(player.uniqueId)
        if (phase !is SurvivalFlight.Phase.Active && phase !is SurvivalFlight.Phase.Grace) return
        if (hasIndependentFlight(player)) return
        if (!player.allowFlight && !player.isFlying) return
        player.isFlying = false
        player.allowFlight = false
        plugin.commsManager.send(player, Component.text("Survival flight time doesn't work here. Flight disabled.", NamedTextColor.RED))
    }

    companion object {
        const val GAMEMODE = "survival"
        /** The 5-tick task syncs with LuckPerms every 4th run (once a second). */
        private const val SYNC_EVERY_TICKS = 4L
        /** Mirror drift (rounding, LuckPerms' whole seconds) that isn't worth a write. */
        private const val SYNC_TOLERANCE_MILLIS = 2_000L
    }
}
