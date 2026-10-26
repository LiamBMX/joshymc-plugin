package com.liam.joshymc.flight

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
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
 * Survival temporary flight time (issue #1304): the one flight balance that Fly Vouchers add
 * to, `/fly` honours, `/flytime` shows and the 30-second landing grace runs from. Rules live
 * in the Bukkit-free [SurvivalFlight].
 *
 * Stored per player in `survival_flight_time` (gamemode is always `survival`; nothing here
 * applies outside Survival). `ended` is set once the end has been enforced, so a finished
 * balance is never enforced twice (e.g. on every later join).
 */
class FlightTimeManager(private val plugin: Joshymc) {

    data class Balance(val endsAt: Long, val ended: Boolean)

    private val balances = ConcurrentHashMap<UUID, Balance>()

    /** Grace seconds left at the last reminder sent to each player, so every reminder goes out once. */
    private val lastReminder = ConcurrentHashMap<UUID, Long>()

    private var task: BukkitTask? = null

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS survival_flight_time (
                uuid TEXT NOT NULL,
                gamemode TEXT NOT NULL DEFAULT 'survival',
                ends_at INTEGER NOT NULL,
                ended INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (uuid, gamemode)
            )
        """.trimIndent())

        Bukkit.getOnlinePlayers().forEach { load(it.uniqueId) }
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
            "SELECT ends_at, ended FROM survival_flight_time WHERE uuid = ? AND gamemode = ?",
            uuid.toString(), GAMEMODE
        ) { Balance(it.getLong("ends_at"), it.getInt("ended") != 0) }
        if (row == null) balances.remove(uuid) else balances[uuid] = row
    }

    fun unload(uuid: UUID) {
        balances.remove(uuid)
        lastReminder.remove(uuid)
    }

    fun balance(uuid: UUID): Balance? = balances[uuid]

    fun phase(uuid: UUID, now: Long = System.currentTimeMillis()): SurvivalFlight.Phase {
        val b = balances[uuid] ?: return SurvivalFlight.Phase.None
        return SurvivalFlight.phase(b.endsAt, b.ended, now)
    }

    /**
     * True while [player] may fly on Survival flight time: in Survival, with time left or
     * inside the landing grace. `/fly` and the combat/arena flight restores check this
     * alongside `joshymc.fly`.
     */
    fun hasTemporaryFlight(player: Player): Boolean {
        if (!inSurvival(player.world)) return false
        return when (phase(player.uniqueId)) {
            is SurvivalFlight.Phase.Active, is SurvivalFlight.Phase.Grace -> true
            else -> false
        }
    }

    /**
     * Writes a new end time. Must run inside the caller's [com.liam.joshymc.manager.DatabaseManager.transaction]
     * when it goes with other writes (a voucher redemption); call [applyWritten] after commit.
     */
    fun writeEnd(uuid: UUID, endsAt: Long) {
        plugin.databaseManager.execute(
            """
            INSERT INTO survival_flight_time (uuid, gamemode, ends_at, ended, updated_at) VALUES (?, ?, ?, 0, ?)
            ON CONFLICT(uuid, gamemode) DO UPDATE SET ends_at = excluded.ends_at, ended = 0, updated_at = excluded.updated_at
            """.trimIndent(),
            uuid.toString(), GAMEMODE, endsAt, System.currentTimeMillis()
        )
    }

    /** Mirrors a committed [writeEnd] in memory and drops any grace reminders in progress. */
    fun applyWritten(uuid: UUID, endsAt: Long) {
        balances[uuid] = Balance(endsAt, ended = false)
        lastReminder.remove(uuid)
    }

    /** The end of the player's LuckPerms timed fly access, if that's the only fly access they have. */
    fun luckPermsAccess(player: Player): FlightTime.Access {
        val grants = try {
            LuckPermsFlight.current()?.flyGrants(player.uniqueId)
        } catch (e: Exception) {
            plugin.logger.warning("[FlyTime] Couldn't read LuckPerms flight nodes: ${e.message}")
            null
        }
        return FlightTime.resolve(player.hasPermission("joshymc.fly"), grants, Instant.now())
    }

    fun luckPermsTemporaryEnd(player: Player, now: Long): Long? =
        (luckPermsAccess(player) as? FlightTime.Access.Temporary)?.let { now + it.remaining.toMillis() }

    // ── Enforcement ──────────────────────────────────────────────────────

    /**
     * Flight that doesn't come from Survival flight time and must never be taken away when it
     * runs out: Creative/Spectator, `/fly` permission (rank, staff or a LuckPerms timed node),
     * ModMode, and spawn-world flight.
     */
    fun hasIndependentFlight(player: Player): Boolean {
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) return true
        if (player.hasPermission("joshymc.fly")) return true
        if (plugin.modModeManager.isModMode(player)) return true
        if (player.world.name == "spawn" && player.hasPermission(SpawnWorldManager.FLY_PERMISSION)) return true
        return false
    }

    fun inSurvival(world: World?): Boolean = plugin.gamemodeLockManager.gamemodeOf(world) == ServerGamemode.SURVIVAL

    private fun tick() {
        val now = System.currentTimeMillis()
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
    }
}
