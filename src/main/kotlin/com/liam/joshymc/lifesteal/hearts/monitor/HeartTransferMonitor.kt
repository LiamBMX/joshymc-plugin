package com.liam.joshymc.lifesteal.hearts.monitor

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import java.util.UUID

/**
 * Issue #1508 (audit finding C-2): watches every committed permanent heart transfer (open-world
 * kills, combat-tag credits, Combat NPC kills, ranked kills, ranked environmental credits and
 * ranked disconnect forfeits; all of them reach the one `HeartStore.transferPvp`) for possible
 * alternate-account heart farming, using only evidence the server already keeps (AltManager's
 * hashed connections and the heart transaction history).
 *
 * **Logging only.** [onTransfer] is called after the transfer committed and was applied; it only
 * queues work on `databaseManager.async`, and any failure there is logged and dropped, so it can
 * never delay, refuse, repeat or reverse a heart transfer. Nothing is told to the players
 * involved. Findings go to `lifesteal_heart_monitor_flags`, one `admin_logs` row (no connection
 * signals named there) and, throttled per pair, a chat line to online staff with [PERMISSION].
 * Owned by [com.liam.joshymc.lifesteal.hearts.LifestealHeartManager].
 */
class HeartTransferMonitor(private val plugin: Joshymc) {

    private val store = HeartTransferMonitorStore(plugin.databaseManager)

    @Volatile private var ready = false
    @Volatile var settings: HeartMonitorSettings? = null
        private set

    val active: Boolean get() = ready && settings?.enabled == true

    /** Startup and every reload, after the heart tables exist. */
    fun refresh() {
        settings = plugin.lifestealConfigManager.heartMonitoring.settings
        ready = try {
            store.createTables()
            true
        } catch (e: Exception) {
            plugin.logger.warning("[HeartMonitor] Couldn't create the review table, so heart transfer monitoring is off (hearts are unaffected): ${e.message}")
            false
        }
        val s = settings
        if (s == null) {
            plugin.logger.warning("[HeartMonitor] ${HeartMonitorConfig.BASE} is invalid, so heart transfer monitoring is off (hearts are unaffected).")
            return
        }
        if (!ready || !s.enabled) return
        plugin.logger.info(
            "[HeartMonitor] Logging-only heart transfer monitoring on (window ${s.windowHours}h: shared connection ${if (s.sharedConnection) "on" else "off"}, " +
                "repeated ${off(s.repeatedTransfers)}, one-way ${off(s.oneWayTransfers)}, associated accounts ${off(s.associatedAccounts)}, " +
                "staff notifications ${if (s.notifyStaff) "on" else "off"})."
        )
        val now = System.currentTimeMillis()
        plugin.databaseManager.async("heart monitor retention") {
            try {
                val removed = store.purge(s.retentionDays, now)
                if (removed > 0) plugin.logger.info("[HeartMonitor] Removed $removed reviewed heart transfer flag(s) older than ${s.retentionDays} days.")
            } catch (e: Exception) {
                plugin.logger.warning("[HeartMonitor] Couldn't apply the flag retention: ${e.message}")
            }
        }
    }

    private fun off(n: Int): String = if (n == 0) "off" else n.toString()

    /**
     * One committed transfer ([transfer] built from the stored `PvpResult.Done`). [killerName] and
     * [victimName] are taken on the main thread for the log lines. Never throws.
     */
    fun onTransfer(transfer: HeartMonitorRules.Transfer, killerName: String, victimName: String) {
        try {
            val s = settings ?: return
            if (!ready || !s.enabled) return
            plugin.databaseManager.async("heart monitor ${transfer.transactionId}") {
                try {
                    val outcome = store.evaluate(transfer, s, System.currentTimeMillis())
                    if (outcome is HeartTransferMonitorStore.Outcome.Flagged) recorded(outcome, killerName, victimName)
                } catch (e: Exception) {
                    plugin.logger.warning("[HeartMonitor] Couldn't check heart transaction ${transfer.transactionId}: ${e.message} (the transfer itself is unaffected).")
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("[HeartMonitor] Couldn't queue the check of heart transaction ${transfer.transactionId}: ${e.message} (the transfer itself is unaffected).")
        }
    }

    /** Database worker thread: console + `admin_logs`, then (main thread) staff chat when not throttled. */
    private fun recorded(outcome: HeartTransferMonitorStore.Outcome.Flagged, killerName: String, victimName: String) {
        val f = outcome.flag
        plugin.logger.warning(
            "[HeartMonitor] Review flag #${f.id}: $victimName (${f.victim}) -> $killerName (${f.killer}), ${f.source?.label ?: "PvP"}, " +
                "heart transaction ${f.transactionId}. Reason: ${HeartMonitorRules.reason(f.signals)}. Logged for staff review " +
                "(/lifesteal hearts monitor ${f.id}); nothing was blocked or changed. A shared connection is not proof of one owner."
        )
        try {
            // No connection signal names here: /admin logs are readable without the monitor permission.
            plugin.adminManager.logAction(
                null, SYSTEM_ACTOR, "HEART_MONITOR_FLAG", f.killer, killerName,
                "flag=${f.id} tx=${f.transactionId} source=${f.source?.name} victim_uuid=${f.victim} signals=${f.signals.size} " +
                    "(details: /lifesteal hearts monitor ${f.id})",
            )
        } catch (e: Exception) {
            plugin.logger.warning("[HeartMonitor] Couldn't write the staff log for flag #${f.id}: ${e.message}")
        }
        if (!outcome.notify) return
        try {
            Bukkit.getScheduler().runTask(plugin, Runnable { notifyStaff(f, killerName, victimName) })
        } catch (e: Exception) {
            plugin.logger.warning("[HeartMonitor] Couldn't schedule the staff notification for flag #${f.id}: ${e.message}")
        }
    }

    private fun notifyStaff(f: HeartTransferMonitorStore.Flag, killerName: String, victimName: String) {
        val message = staffMessage(f, killerName, victimName)
        for (player in Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(PERMISSION)) continue
            try {
                plugin.commsManager.send(player, message)
            } catch (e: Exception) {
                plugin.logger.warning("[HeartMonitor] Couldn't notify ${player.name} about flag #${f.id}: ${e.message}")
            }
        }
    }

    /** The staff-only chat lines for flag [f]. */
    fun staffMessage(f: HeartTransferMonitorStore.Flag, killerName: String, victimName: String): Component {
        val command = "/lifesteal hearts monitor ${f.id}"
        return Component.text("[Lifesteal Monitor] ", NamedTextColor.GOLD)
            .append(Component.text("Possible heart farming (flag #${f.id}):", NamedTextColor.YELLOW))
            .append(Component.newline())
            .append(Component.text(" $victimName → $killerName", NamedTextColor.WHITE))
            .append(Component.text(" (${f.source?.label ?: "PvP"})", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text(" Reason: ", NamedTextColor.GRAY))
            .append(Component.text(HeartMonitorRules.reason(f.signals), NamedTextColor.YELLOW))
            .append(Component.newline())
            .append(
                Component.text(" Action: Review recommended ", NamedTextColor.GRAY)
                    .append(Component.text("[Details]", NamedTextColor.AQUA))
                    .clickEvent(ClickEvent.runCommand(command))
                    .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)))
            )
    }

    // ── Staff reads (/lifesteal hearts monitor) ─────────────

    fun flags(player: UUID?, status: HeartTransferMonitorStore.Status?, limit: Int = 15): List<HeartTransferMonitorStore.Flag> = store.flags(player, status, limit)

    fun flag(id: Long): HeartTransferMonitorStore.Flag? = store.flag(id)

    fun openCount(): Int = store.count(HeartTransferMonitorStore.Status.OPEN)

    /** Marks flag [id]; audited in `admin_logs`. False when it was already in that state or doesn't exist. */
    fun review(id: Long, status: HeartTransferMonitorStore.Status, staffUuid: UUID?, staffName: String, note: String?): Boolean {
        val changed = store.setStatus(id, status, staffUuid?.toString() ?: "console", note, System.currentTimeMillis())
        if (changed) {
            try {
                plugin.adminManager.logAction(staffUuid, staffName, "HEART_MONITOR_${status.name}", null, null, "flag=$id${note?.let { " note=$it" } ?: ""}".take(500))
            } catch (e: Exception) {
                plugin.logger.warning("[HeartMonitor] Couldn't write the staff log for reviewing flag #$id: ${e.message}")
            }
        }
        return changed
    }

    companion object {
        /** View flags, mark them reviewed and receive notifications (op). */
        const val PERMISSION = "joshymc.lifesteal.hearts.monitor"
        /** Name the monitor's own `admin_logs` rows carry. */
        const val SYSTEM_ACTOR = "Lifesteal Heart Monitor"
    }
}
