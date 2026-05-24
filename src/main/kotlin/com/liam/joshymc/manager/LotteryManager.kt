package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Sound
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

class LotteryManager(private val plugin: Joshymc) {

    companion object {
        const val TICKET_COST = 10_000.0
        const val BASE_PRIZE = 50_000.0
        const val PRIZE_PER_TICKET = 10_000.0
        private const val DRAW_INTERVAL_MINUTES = 60
    }

    // uuid -> ticket count for the current round
    private val tickets = mutableMapOf<UUID, Int>()
    private var taskId = -1
    private var minuteCounter = 0

    fun start() {
        tickets.clear()
        minuteCounter = 0

        // Tick every real minute (1200 ticks)
        taskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            minuteCounter++
            onMinuteTick()
        }, 1200L, 1200L)

        plugin.logger.info("[Lottery] LotteryManager started. Draw every $DRAW_INTERVAL_MINUTES minutes.")
    }

    fun stop() {
        if (taskId != -1) {
            plugin.server.scheduler.cancelTask(taskId)
            taskId = -1
        }
    }

    private fun onMinuteTick() {
        val minuteInHour = minuteCounter % DRAW_INTERVAL_MINUTES
        when {
            minuteInHour == 0 -> drawLottery()
            minuteInHour == 15 -> broadcastReminder(45)
            minuteInHour == 30 -> broadcastReminder(30)
            minuteInHour == 45 -> broadcastReminder(15)
            minuteInHour in 55..59 -> broadcastReminder(DRAW_INTERVAL_MINUTES - minuteInHour)
        }
    }

    private fun broadcastReminder(minutesLeft: Int) {
        val timeStr = if (minutesLeft == 1) "1 minute" else "$minutesLeft minutes"
        val prize = getPrize()
        val totalTickets = getTotalTickets()
        val poolLine = if (totalTickets > 0) {
            Component.text("  Prize Pool: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.economyManager.format(prize), NamedTextColor.GREEN))
                .append(Component.text("  |  $totalTickets ticket(s) sold", NamedTextColor.DARK_GRAY))
        } else {
            Component.text("  Prize Pool: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.economyManager.format(BASE_PRIZE), NamedTextColor.GREEN))
                .append(Component.text("  (no entries yet)", NamedTextColor.DARK_GRAY))
        }

        Bukkit.broadcast(
            Component.text("🎫 ", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                .append(Component.text("Lottery", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))
                .append(Component.text(" — draw in $timeStr!", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
        )
        Bukkit.broadcast(poolLine)
        Bukkit.broadcast(
            Component.text("  Use ", NamedTextColor.GRAY)
                .append(Component.text("/lottery buy <amount>", NamedTextColor.AQUA))
                .append(Component.text(" to enter. Each ticket costs ", NamedTextColor.GRAY))
                .append(Component.text(plugin.economyManager.format(TICKET_COST), NamedTextColor.GOLD))
                .append(Component.text(".", NamedTextColor.GRAY))
        )
        for (player in Bukkit.getOnlinePlayers()) {
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.0f)
        }
    }

    private fun drawLottery() {
        val totalTickets = getTotalTickets()

        if (tickets.isEmpty() || totalTickets == 0) {
            Bukkit.broadcast(
                Component.text("🎫 ", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                    .append(Component.text("Lottery", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))
                    .append(Component.text(" — No one entered this round. No winner!", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
            )
            tickets.clear()
            return
        }

        val prize = getPrize()

        // Weighted random selection: pick a roll in [0, totalTickets)
        val roll = ThreadLocalRandom.current().nextInt(totalTickets)
        var cumulative = 0
        var winner: UUID? = null
        for ((uuid, count) in tickets) {
            cumulative += count
            if (roll < cumulative) {
                winner = uuid
                break
            }
        }
        if (winner == null) winner = tickets.keys.last()

        val winnerName = Bukkit.getOfflinePlayer(winner).name ?: "Unknown"
        val winnerTickets = tickets[winner] ?: 0
        val winChance = String.format("%.1f", winnerTickets.toDouble() / totalTickets * 100)
        val prizeStr = plugin.economyManager.format(prize)

        plugin.economyManager.deposit(winner, prize)

        Bukkit.broadcast(
            Component.text("🎫 ", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                .append(Component.text("Lottery Winner! ", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))
                .append(Component.text(winnerName, NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true))
                .append(Component.text(" won ", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
                .append(Component.text(prizeStr, NamedTextColor.GREEN).decoration(TextDecoration.BOLD, false))
                .append(Component.text("!", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
        )
        Bukkit.broadcast(
            Component.text("  $totalTickets ticket(s) sold | $winnerTickets ticket(s) held by winner ($winChance% chance)", NamedTextColor.DARK_GRAY)
        )
        for (player in Bukkit.getOnlinePlayers()) {
            player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f)
        }

        tickets.clear()
    }

    fun buyTickets(uuid: UUID, count: Int): Boolean {
        val cost = TICKET_COST * count
        if (!plugin.economyManager.has(uuid, cost)) return false
        plugin.economyManager.withdraw(uuid, cost)
        tickets[uuid] = (tickets[uuid] ?: 0) + count
        return true
    }

    fun getTicketCount(uuid: UUID): Int = tickets[uuid] ?: 0

    fun getTotalTickets(): Int = tickets.values.sum()

    fun getPrize(): Double = BASE_PRIZE + getTotalTickets() * PRIZE_PER_TICKET

    fun getMinutesUntilDraw(): Int {
        val minuteInHour = minuteCounter % DRAW_INTERVAL_MINUTES
        return if (minuteInHour == 0 && minuteCounter == 0) DRAW_INTERVAL_MINUTES
        else DRAW_INTERVAL_MINUTES - minuteInHour
    }
}
